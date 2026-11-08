// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import kotlin.math.PI
import kotlin.math.sin

/**
 * The MOS 6581 SID, clocked by φ2: three voices, each an oscillator, a
 * waveform selector and an ADSR envelope, then a multimode filter and the
 * master volume.
 *
 * Registers `$D400-$D41C`, repeating every 32 bytes. Each voice has 7: frequency
 * (16 bits), pulse width (12 bits), control (gate, sync, ring modulation, test,
 * triangle, sawtooth, pulse, noise), attack/decay and sustain/release. Then the
 * filter cutoff (11 bits), resonance and routing, and mode and volume. Reading
 * gives the paddles (none connected: `$FF`), voice 3's oscillator and envelope,
 * and for the write-only registers the last value written, as the bus holds it.
 *
 * - **Oscillators.** A 24-bit accumulator adds the frequency every cycle, so
 *   the pitch is f × 985,248 / 2^24 Hz. Sawtooth is its top 12 bits; triangle
 *   folds bit 23 over the next 12; pulse compares the top 12 bits with the
 *   width. Noise is a 23-bit shift register (taps 17 and 22), stepped as bit 19
 *   rises, and output from 8 of its bits. Sync resets an oscillator when the
 *   previous voice's bit 23 rises; ring modulation inverts the triangle with
 *   it. TEST holds the accumulator at 0 and resets the noise register.
 *   Combined waveforms are approximated as the AND of their parts.
 * - **Envelopes.** The rate counter periods and the exponential decay steps
 *   are the ones reSID measured; attack rises linearly to 255, decay falls to
 *   the sustain level (S × 17), and release to 0, each step slower at lower
 *   levels.
 * - **Filter.** A state-variable filter with low-, band- and high-pass outputs.
 *   The 6581's cutoff curve varies from chip to chip; here the cutoff rises
 *   linearly from 30 Hz to 12 kHz over the register's range, as on the 8580.
 * - **Output.** The voices, filtered or not, are mixed and scaled by the
 *   volume. The 6581 also has a DC offset that the volume scales, which is why
 *   writing the volume register clicks; programs use that to play samples, so
 *   it is kept.
 *
 * The behavior follows the SID datasheet and Dag Lem's reSID, as described in
 * its documentation; no reSID code is used. Not emulated: the ADSR delay bug,
 * the analogue character of the 6581's filter and DACs, and the waveform DAC
 * holding its value when no waveform is selected.
 *
 * [startSampling] resamples the output for the host, as [C64Machine] runs.
 */
class Sid6581 : IoDevice {
    private val voices = Array(3) { Voice() }

    var cutoff = 0; private set
    var resonanceRouting = 0; private set
    var modeVolume = 0; private set
    private var lastWrite = 0

    val volume: Int get() = modeVolume and 0x0F
    fun frequency(voice: Int) = voices[voice].frequency
    fun envelope(voice: Int) = voices[voice].envelope
    fun gate(voice: Int) = voices[voice].control and GATE != 0

    var writes: Long = 0
        private set

    // Filter state.
    private var low = 0f
    private var band = 0f
    private var filterW = 0f
    private var filterDamping = 1f

    /** The current output, about -1 to 1. */
    var level: Float = 0f
        private set

    init {
        updateFilter()
    }

    fun reset() {
        for (voice in voices) voice.reset()
        cutoff = 0
        resonanceRouting = 0
        modeVolume = 0
        low = 0f
        band = 0f
        updateFilter()
    }

    override fun read(offset: Int): Int = peek(offset)

    override fun peek(offset: Int): Int = when (offset and 0x1F) {
        0x19, 0x1A -> 0xFF
        0x1B -> voices[2].waveform() shr 4
        0x1C -> voices[2].envelope
        else -> lastWrite
    }

    override fun write(offset: Int, value: Int) {
        val data = value and 0xFF
        lastWrite = data
        writes++
        val register = offset and 0x1F
        if (register < 21) {
            val voice = voices[register / 7]
            when (register % 7) {
                0 -> voice.frequency = (voice.frequency and 0xFF00) or data
                1 -> voice.frequency = (voice.frequency and 0x00FF) or (data shl 8)
                2 -> voice.pulseWidth = (voice.pulseWidth and 0xF00) or data
                3 -> voice.pulseWidth = (voice.pulseWidth and 0x0FF) or ((data and 0x0F) shl 8)
                4 -> voice.writeControl(data)
                5 -> voice.attackDecay = data
                6 -> voice.sustainRelease = data
            }
            return
        }
        when (register) {
            0x15 -> cutoff = (cutoff and 0x7F8) or (data and 0x07)
            0x16 -> cutoff = (cutoff and 0x007) or (data shl 3)
            0x17 -> resonanceRouting = data
            0x18 -> modeVolume = data
        }
        updateFilter()
    }

    /** One φ2 cycle. */
    fun tick() {
        for (i in 0 until 3) voices[i].clockOscillator()
        // Hard sync: an oscillator restarts as the previous one's bit 23 rises.
        for (i in 0 until 3) {
            val source = voices[(i + 2) % 3]
            if (voices[i].control and SYNC != 0 && source.msbRising) voices[i].accumulator = 0
        }
        var direct = 0f
        var filterInput = 0f
        for (i in 0 until 3) {
            val voice = voices[i]
            voice.clockEnvelope()
            val ringSource = voices[(i + 2) % 3].accumulator
            val output = (voice.waveform(ringSource) - 0x800) * voice.envelope / (2048f * 255f)
            if (resonanceRouting and (1 shl i) != 0) {
                filterInput += output
            } else if (i != 2 || modeVolume and VOICE3_OFF == 0) {
                direct += output
            }
        }
        // State-variable filter, one step per cycle.
        val high = filterInput - low - filterDamping * band
        band += filterW * high
        low += filterW * band
        var filtered = 0f
        if (modeVolume and LOW_PASS != 0) filtered += low
        if (modeVolume and BAND_PASS != 0) filtered += band
        if (modeVolume and HIGH_PASS != 0) filtered += high
        level = ((direct + filtered) / 3f + DC_OFFSET) * volume / 15f
        if (sampleRate != 0) sample()
    }

    private fun updateFilter() {
        val frequency = 30f + cutoff * (12_000f - 30f) / 2047f
        filterW = (2 * sin(PI * frequency / C64Clock.CYCLES_PER_SECOND)).toFloat()
        // Resonance 0-15 lowers the damping from 1/0.707 towards 1/1.7.
        val q = 0.707f + (resonanceRouting shr 4) * (1.7f - 0.707f) / 15f
        filterDamping = 1f / q
    }

    private class Voice {
        var frequency = 0
        var pulseWidth = 0
        var control = 0
        var attackDecay = 0
        var sustainRelease = 0

        var accumulator = 0
        var msbRising = false
        private var noise = NOISE_SEED

        var envelope = 0
        private var state = State.RELEASE
        private var rateCounter = 0
        private var exponentialCounter = 0

        fun reset() {
            frequency = 0; pulseWidth = 0; control = 0; attackDecay = 0; sustainRelease = 0
            accumulator = 0; msbRising = false; noise = NOISE_SEED
            envelope = 0; state = State.RELEASE; rateCounter = 0; exponentialCounter = 0
        }

        fun writeControl(data: Int) {
            val wasGate = control and GATE != 0
            control = data
            val gate = data and GATE != 0
            if (gate && !wasGate) {
                state = State.ATTACK
            } else if (!gate && wasGate) {
                state = State.RELEASE
            }
            if (data and TEST != 0) {
                accumulator = 0
                noise = NOISE_SEED
            }
        }

        fun clockOscillator() {
            if (control and TEST != 0) {
                msbRising = false
                return
            }
            val before = accumulator
            accumulator = (accumulator + frequency) and 0xFFFFFF
            msbRising = before and 0x800000 == 0 && accumulator and 0x800000 != 0
            if (before and 0x080000 == 0 && accumulator and 0x080000 != 0) {
                val feedback = ((noise shr 22) xor (noise shr 17)) and 1
                noise = ((noise shl 1) or feedback) and 0x7FFFFF
            }
        }

        /** The 12-bit waveform output; [ringSource] is the previous voice's accumulator. */
        fun waveform(ringSource: Int = 0): Int {
            var output = 0xFFF
            var any = false
            if (control and TRIANGLE != 0) {
                var msb = accumulator and 0x800000
                if (control and RING != 0) msb = msb xor (ringSource and 0x800000)
                val folded = if (msb != 0) accumulator.inv() else accumulator
                output = output and ((folded shr 11) and 0xFFF)
                any = true
            }
            if (control and SAWTOOTH != 0) {
                output = output and (accumulator shr 12)
                any = true
            }
            if (control and PULSE != 0) {
                output = output and (if (control and TEST != 0 || accumulator shr 12 >= pulseWidth) 0xFFF else 0)
                any = true
            }
            if (control and NOISE != 0) {
                output = output and noiseOutput()
                any = true
            }
            return if (any) output else 0
        }

        private fun noiseOutput(): Int =
            ((noise shr 11) and 0x800) or ((noise shr 10) and 0x400) or ((noise shr 7) and 0x200) or
                ((noise shr 5) and 0x100) or ((noise shr 4) and 0x080) or ((noise shr 1) and 0x040) or
                ((noise shl 1) and 0x020) or ((noise shl 2) and 0x010)

        fun clockEnvelope() {
            val rate = when (state) {
                State.ATTACK -> attackDecay shr 4
                State.DECAY_SUSTAIN -> attackDecay and 0x0F
                State.RELEASE -> sustainRelease and 0x0F
            }
            if (++rateCounter < RATE_PERIODS[rate]) return
            rateCounter = 0
            if (state == State.ATTACK) {
                exponentialCounter = 0
                envelope++
                if (envelope >= 0xFF) {
                    envelope = 0xFF
                    state = State.DECAY_SUSTAIN
                }
                return
            }
            if (++exponentialCounter < exponentialPeriod()) return
            exponentialCounter = 0
            if (state == State.DECAY_SUSTAIN) {
                if (envelope > (sustainRelease shr 4) * 0x11) envelope--
            } else if (envelope > 0) {
                envelope--
            }
        }

        private fun exponentialPeriod() = when {
            envelope >= 93 -> 1
            envelope >= 54 -> 2
            envelope >= 26 -> 4
            envelope >= 14 -> 8
            envelope >= 6 -> 16
            else -> 30
        }

        private enum class State { ATTACK, DECAY_SUSTAIN, RELEASE }
    }

    // Host-rate resampling, as in Beebslop's SN76489: each sample is the mean
    // level over the cycles it covers, then a DC-blocking high-pass filter.
    private var sampleRate = 0
    private var samples = FloatArray(0)
    private var sampleCount = 0
    private var phase = 0
    private var levelSum = 0f
    private var levelCycles = 0
    private var lastInput = 0f
    private var lastOutput = 0f

    /** Samples available to [drainSamples]. */
    val bufferedSamples: Int get() = sampleCount

    /**
     * Start producing samples, about -1 to 1, at [rate] Hz. Up to [capacity]
     * samples are buffered; if the host does not drain them, newer ones are dropped.
     */
    fun startSampling(rate: Int, capacity: Int = rate / 5) {
        require(rate in 1..C64Clock.CYCLES_PER_SECOND && capacity > 0)
        sampleRate = rate
        samples = FloatArray(capacity)
        sampleCount = 0
        phase = 0
        levelSum = 0f
        levelCycles = 0
        lastInput = level
        lastOutput = 0f
    }

    /** Move buffered samples into [destination]; returns how many were copied. */
    fun drainSamples(destination: FloatArray): Int {
        val n = minOf(sampleCount, destination.size)
        samples.copyInto(destination, 0, 0, n)
        samples.copyInto(samples, 0, n, sampleCount)
        sampleCount -= n
        return n
    }

    private fun sample() {
        levelSum += level
        levelCycles++
        phase += sampleRate
        if (phase < C64Clock.CYCLES_PER_SECOND) return
        phase -= C64Clock.CYCLES_PER_SECOND
        val input = levelSum / levelCycles
        lastOutput = input - lastInput + DC_BLOCK * lastOutput
        lastInput = input
        if (sampleCount < samples.size) samples[sampleCount++] = lastOutput
        levelSum = 0f
        levelCycles = 0
    }

    companion object {
        const val GATE = 0x01
        const val SYNC = 0x02
        const val RING = 0x04
        const val TEST = 0x08
        const val TRIANGLE = 0x10
        const val SAWTOOTH = 0x20
        const val PULSE = 0x40
        const val NOISE = 0x80

        private const val LOW_PASS = 0x10
        private const val BAND_PASS = 0x20
        private const val HIGH_PASS = 0x40
        private const val VOICE3_OFF = 0x80

        private const val NOISE_SEED = 0x7FFFF8

        /** The 6581's DC offset, relative to one voice at full scale. */
        private const val DC_OFFSET = 0.15f

        /** Pole of the DC-blocking filter; about 40 Hz at 48 kHz. */
        private const val DC_BLOCK = 0.995f

        /** Cycles per envelope step for each rate setting (reSID's measurements). */
        val RATE_PERIODS = intArrayOf(9, 32, 63, 95, 149, 220, 267, 313, 392, 977, 1954, 3126, 3907, 11720, 19532, 31251)
    }
}
