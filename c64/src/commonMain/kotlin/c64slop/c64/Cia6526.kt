// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/** What is wired to a CIA's ports. Values are 8-bit; undriven inputs read 1. */
interface CiaPorts {
    /** Levels on the port A pins, given what the CIA drives (outputs, with inputs high). */
    fun readA(driven: Int): Int = driven
    fun readB(driven: Int): Int = driven

    /** Called when the levels the CIA drives change: outputs, with inputs high. */
    fun writeA(driven: Int) {}
    fun writeB(driven: Int) {}
}

/**
 * A 6526 Complex Interface Adapter, clocked by φ2. The C64 has two: CIA 1
 * scans the keyboard and joysticks and interrupts the CPU's IRQ; CIA 2 drives
 * the serial bus and the VIC-II's bank, and interrupts its NMI.
 *
 * Register behavior follows the MOS 6526 datasheet and MAME's `mos6526.cpp`.
 * Each [tick] is one cycle:
 *
 * - A timer counts N, N-1, ... 0, and on the next count underflows: it flags,
 *   reloads N and, in one-shot mode, stops. So it underflows every N+1 cycles.
 * - Timer B counts φ2, or timer A's underflows. CNT is not connected on the
 *   C64's CIAs here, so its modes don't count.
 * - Starting a timer, or loading it with the force-load strobe, holds the
 *   counter for one cycle before it counts.
 * - In one-shot mode, writing a timer's high byte loads and starts it.
 * - PB6 and PB7 can show timer A's and B's underflows, as a pulse or a toggle.
 * - The time-of-day clock counts tenths of a second in BCD, in 12-hour form,
 *   from the 50 Hz [todPulse] (divided by 5, or by 6 if CRA bit 7 asks for
 *   60 Hz). Reading the hours latches the clock until the tenths are read;
 *   writing the hours stops it until the tenths are written. It interrupts
 *   when it matches the alarm.
 * - The serial port shifts out a byte at half timer A's underflow rate and then
 *   interrupts; nothing listens. Shifting in needs CNT, so it doesn't happen.
 *
 * Not yet emulated: the IRQ output's one-cycle delay and the 6526A's
 * differences; exact timing of register writes against the count has not been
 * checked against real hardware.
 */
class Cia6526(private val ports: CiaPorts = object : CiaPorts {}) : IoDevice {
    var pra = 0; private set
    var prb = 0; private set
    var ddra = 0; private set
    var ddrb = 0; private set
    var cra = 0; private set
    var crb = 0; private set

    /** Interrupt flags (ICR bits 0-4) and the mask. */
    var icr = 0; private set
    var icrMask = 0; private set

    private val timerA = Timer()
    private val timerB = Timer()
    val timerACounter: Int get() = timerA.counter
    val timerBCounter: Int get() = timerB.counter

    private var pb6 = false
    private var pb7 = false
    private var pb6Pulse = false
    private var pb7Pulse = false

    private var sdr = 0
    private var shiftCount = 0

    private var flag = true

    // Time of day, in BCD.
    private val tod = IntArray(4) // tenths, seconds, minutes, hours (bit 7: PM)
    private val alarm = IntArray(4)
    private val todLatch = IntArray(4)
    private var todLatched = false
    private var todStopped = true
    private var todPrescaler = 0

    /** The IRQ output, active when an enabled flag is set. */
    val irq: Boolean get() = icr and icrMask != 0

    /** Port levels driven by the CIA: outputs as written, inputs pulled high. */
    val drivenA: Int get() = (pra and ddra) or ddra.inv() and 0xFF
    val drivenB: Int get() {
        var value = (prb and ddrb) or ddrb.inv() and 0xFF
        if (cra and CR_PBON != 0) value = (value and 0xBF) or (if (timerOutput(cra, pb6, pb6Pulse)) 0x40 else 0)
        if (crb and CR_PBON != 0) value = (value and 0x7F) or (if (timerOutput(crb, pb7, pb7Pulse)) 0x80 else 0)
        return value
    }

    /** The RESET input. Timers and their latches go to `$FFFF`; the clock stops at 1:00:00.0 AM. */
    fun reset() {
        pra = 0; prb = 0; ddra = 0; ddrb = 0
        cra = 0; crb = 0
        icr = 0; icrMask = 0
        timerA.reset(); timerB.reset()
        pb6 = false; pb7 = false; pb6Pulse = false; pb7Pulse = false
        sdr = 0; shiftCount = 0
        tod[0] = 0; tod[1] = 0; tod[2] = 0; tod[3] = 0x01
        alarm.fill(0)
        todLatched = false
        todStopped = true
        todPrescaler = 0
        ports.writeA(drivenA)
        ports.writeB(drivenB)
    }

    init {
        reset()
    }

    /** One φ2 cycle. */
    fun tick() {
        val pulses = pb6Pulse || pb7Pulse
        pb6Pulse = false
        pb7Pulse = false

        var underflowA = false
        if (cra and CR_START != 0 && cra and CRA_INMODE == 0) underflowA = timerA.count()
        if (underflowA) {
            setFlag(TA)
            if (cra and CR_RUNMODE != 0) cra = cra and CR_START.inv()
            pb6 = !pb6
            pb6Pulse = true
            shiftOut()
        }

        var underflowB = false
        if (crb and CR_START != 0) {
            underflowB = when ((crb shr 5) and 3) {
                0 -> timerB.count()
                2, 3 -> underflowA && timerB.count()
                else -> false // CNT
            }
        }
        if (underflowB) {
            setFlag(TB)
            if (crb and CR_RUNMODE != 0) crb = crb and CR_START.inv()
            pb7 = !pb7
            pb7Pulse = true
        }
        if ((underflowA || underflowB || pulses) && (cra or crb) and CR_PBON != 0) ports.writeB(drivenB)
    }

    /** One cycle of the 50 Hz power-line clock on the TOD pin. */
    fun todPulse() {
        todPrescaler++
        if (todPrescaler < if (cra and CRA_TODIN != 0) 5 else 6) return
        todPrescaler = 0
        if (todStopped) return
        tod[0] = (tod[0] + 1) % 10
        if (tod[0] == 0) {
            tod[1] = bcdIncrement(tod[1], 0x60)
            if (tod[1] == 0) {
                tod[2] = bcdIncrement(tod[2], 0x60)
                if (tod[2] == 0) tod[3] = nextHour(tod[3])
            }
        }
        if (tod.contentEquals(alarm)) setFlag(ALARM)
    }

    /** The FLAG input; a falling edge sets the FLAG interrupt flag. */
    fun setFlagInput(level: Boolean) {
        if (flag && !level) setFlag(FLAG)
        flag = level
    }

    override fun read(offset: Int): Int = when (offset and 0x0F) {
        0x08 -> {
            val value = if (todLatched) todLatch[0] else tod[0]
            todLatched = false
            value
        }
        0x0B -> {
            if (!todLatched) {
                tod.copyInto(todLatch)
                todLatched = true
            }
            todLatch[3]
        }
        0x0D -> {
            val value = icr or (if (irq) 0x80 else 0)
            icr = 0
            value
        }
        else -> peek(offset)
    }

    override fun peek(offset: Int): Int = when (offset and 0x0F) {
        0x00 -> ports.readA(drivenA) and 0xFF
        0x01 -> {
            // Output bits read what the CIA drives, ANDed with the pins: a key
            // or joystick can still pull one low.
            ports.readB(drivenB) and 0xFF
        }
        0x02 -> ddra
        0x03 -> ddrb
        0x04 -> timerA.counter and 0xFF
        0x05 -> timerA.counter shr 8
        0x06 -> timerB.counter and 0xFF
        0x07 -> timerB.counter shr 8
        0x08 -> if (todLatched) todLatch[0] else tod[0]
        0x09 -> if (todLatched) todLatch[1] else tod[1]
        0x0A -> if (todLatched) todLatch[2] else tod[2]
        0x0B -> if (todLatched) todLatch[3] else tod[3]
        0x0C -> sdr
        0x0D -> icr or (if (irq) 0x80 else 0)
        0x0E -> cra and CR_LOAD.inv()
        else -> crb and CR_LOAD.inv()
    }

    override fun write(offset: Int, value: Int) {
        val data = value and 0xFF
        when (offset and 0x0F) {
            0x00 -> { pra = data; ports.writeA(drivenA) }
            0x01 -> { prb = data; ports.writeB(drivenB) }
            0x02 -> { ddra = data; ports.writeA(drivenA) }
            0x03 -> { ddrb = data; ports.writeB(drivenB) }
            0x04 -> timerA.latch = (timerA.latch and 0xFF00) or data
            0x05 -> writeTimerHigh(timerA, data, CR_RUNMODE and cra != 0) { cra = cra or CR_START }
            0x06 -> timerB.latch = (timerB.latch and 0xFF00) or data
            0x07 -> writeTimerHigh(timerB, data, CR_RUNMODE and crb != 0) { crb = crb or CR_START }
            0x08 -> if (crb and CRB_ALARM != 0) alarm[0] = data and 0x0F else {
                tod[0] = data and 0x0F
                todStopped = false
            }
            0x09 -> if (crb and CRB_ALARM != 0) alarm[1] = data and 0x7F else tod[1] = data and 0x7F
            0x0A -> if (crb and CRB_ALARM != 0) alarm[2] = data and 0x7F else tod[2] = data and 0x7F
            0x0B -> if (crb and CRB_ALARM != 0) alarm[3] = data and 0x9F else {
                tod[3] = data and 0x9F
                todStopped = true
            }
            0x0C -> {
                sdr = data
                if (cra and CRA_SPMODE != 0) shiftCount = 16
            }
            0x0D -> icrMask = if (data and 0x80 != 0) icrMask or (data and 0x1F) else icrMask and (data and 0x1F).inv()
            0x0E -> {
                if (cra and CR_START == 0 && data and CR_START != 0) {
                    timerA.hold = true
                    pb6 = true
                }
                if (data and CR_LOAD != 0) timerA.load()
                cra = data and CR_LOAD.inv()
                ports.writeB(drivenB)
            }
            0x0F -> {
                if (crb and CR_START == 0 && data and CR_START != 0) {
                    timerB.hold = true
                    pb7 = true
                }
                if (data and CR_LOAD != 0) timerB.load()
                crb = data and CR_LOAD.inv()
                ports.writeB(drivenB)
            }
        }
    }

    private inline fun writeTimerHigh(timer: Timer, data: Int, oneShot: Boolean, start: () -> Unit) {
        timer.latch = (timer.latch and 0x00FF) or (data shl 8)
        val running = if (timer === timerA) cra and CR_START != 0 else crb and CR_START != 0
        if (!running) timer.load()
        if (oneShot && !running) start()
    }

    private fun shiftOut() {
        if (shiftCount == 0 || cra and CRA_SPMODE == 0) return
        // Each bit takes two underflows, one for each CNT edge.
        if (--shiftCount == 0) setFlag(SP)
    }

    private fun timerOutput(control: Int, toggle: Boolean, pulse: Boolean) =
        if (control and CR_OUTMODE != 0) toggle else pulse

    private fun setFlag(flag: Int) { icr = icr or flag }

    private class Timer {
        var counter = 0xFFFF
        var latch = 0xFFFF
        var hold = false

        fun reset() {
            counter = 0xFFFF
            latch = 0xFFFF
            hold = false
        }

        fun load() {
            counter = latch
            hold = true
        }

        /** Count once; true on an underflow, which reloads the counter. */
        fun count(): Boolean {
            if (hold) {
                hold = false
                return false
            }
            if (counter == 0) {
                counter = latch
                return true
            }
            counter--
            return false
        }
    }

    companion object {
        const val TA = 0x01
        const val TB = 0x02
        const val ALARM = 0x04
        const val SP = 0x08
        const val FLAG = 0x10

        private const val CR_START = 0x01
        private const val CR_PBON = 0x02
        private const val CR_OUTMODE = 0x04
        private const val CR_RUNMODE = 0x08
        private const val CR_LOAD = 0x10
        private const val CRA_INMODE = 0x20
        private const val CRA_SPMODE = 0x40
        private const val CRA_TODIN = 0x80
        private const val CRB_ALARM = 0x80

        /** BCD increment, wrapping to 0 at [limit] (`$60` for seconds and minutes). */
        private fun bcdIncrement(value: Int, limit: Int): Int {
            var next = value + 1
            if (next and 0x0F == 0x0A) next = (next and 0xF0) + 0x10
            return if (next >= limit) 0 else next
        }

        /** 12-hour BCD: 11 to 12 flips AM and PM, and 12 goes to 1. */
        private fun nextHour(value: Int): Int {
            val pm = value and 0x80
            return when (val hour = value and 0x1F) {
                0x11 -> 0x12 or (pm xor 0x80)
                0x12 -> 0x01 or pm
                else -> bcdIncrement(hour, 0x13) or pm
            }
        }
    }
}
