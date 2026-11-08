// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * A cassette, as the datasette's read head sees it: a list of pulses, each the
 * time in φ2 cycles from one falling edge of the signal to the next. The
 * KERNAL writes a bit as two pulses, short-long or long-short, and tells them
 * apart by their length; this is what TAP images store. It moves only while the
 * motor runs. Recording overwrites from the position and lengthens the tape.
 *
 * [parse] reads a TAP image, version 0 or 1: a 20-byte header (`C64-TAPE-RAW`,
 * the version, three reserved bytes and the data length), then one byte per
 * pulse giving its length in units of 8 cycles. A zero byte is an overflow: in
 * version 1 the next three bytes give the length in cycles; in version 0 it
 * stands for a pause, taken as 256 units. [toTap] writes version 1.
 */
class Tape private constructor(private var pulses: IntArray, size: Int) {
    /** Pulses recorded, from the start of the tape. */
    var length: Int = size
        private set

    /** Tape position in pulses. */
    var position: Int = 0
        private set

    val atEnd: Boolean get() = position >= length

    /** Length in seconds of the whole tape. */
    val seconds: Double get() {
        var cycles = 0L
        for (i in 0 until length) cycles += pulses[i]
        return cycles / C64Clock.CYCLES_PER_SECOND.toDouble()
    }

    fun rewind() { position = 0 }

    /** The next pulse's length in cycles, or null past the end. */
    fun nextPulse(): Int? = if (atEnd) null else pulses[position++]

    /** Record a pulse of [cycles] at the position. */
    fun writePulse(cycles: Int) {
        require(cycles > 0)
        if (position >= pulses.size) pulses = pulses.copyOf(maxOf(pulses.size * 2, position + 1, 1 shl 16))
        pulses[position++] = cycles
        if (position > length) length = position
    }

    /** The pulse at [index], for tests and export. */
    operator fun get(index: Int): Int = pulses[index]

    /** The tape as a TAP image, version 1. */
    fun toTap(): ByteArray {
        val data = ArrayList<Byte>(length + 16)
        for (i in 0 until length) {
            val units = (pulses[i] + 4) / 8
            if (units in 1..255) {
                data += units.toByte()
            } else {
                data += 0
                for (shift in listOf(0, 8, 16)) data += (pulses[i] ushr shift).toByte()
            }
        }
        val header = ByteArray(HEADER_SIZE)
        MAGIC.forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[12] = 1
        for ((i, shift) in listOf(0, 8, 16, 24).withIndex()) header[16 + i] = (data.size ushr shift).toByte()
        return header + data.toByteArray()
    }

    companion object {
        private const val MAGIC = "C64-TAPE-RAW"
        private const val HEADER_SIZE = 20

        /** A version-0 overflow: a pause of unknown length. */
        private const val VERSION_0_OVERFLOW = 256 * 8

        /** An empty tape, for recording. */
        fun blank(): Tape = Tape(IntArray(1 shl 16), 0)

        /** @throws IllegalArgumentException if [image] is not a well-formed TAP file. */
        fun parse(image: ByteArray): Tape {
            require(image.size >= HEADER_SIZE && MAGIC.indices.all { image[it].toInt() == MAGIC[it].code }) {
                "Not a C64 TAP image"
            }
            val version = image[12].toInt()
            require(version == 0 || version == 1) { "TAP version $version is not supported" }
            val size = (0 until 4).fold(0L) { acc, i -> acc or ((image[16 + i].toLong() and 0xFF) shl (8 * i)) }
            require(size <= image.size - HEADER_SIZE) { "TAP image is truncated" }
            val end = HEADER_SIZE + size.toInt()
            val pulses = IntArray(size.toInt())
            var count = 0
            var i = HEADER_SIZE
            while (i < end) {
                val units = image[i++].toInt() and 0xFF
                pulses[count++] = when {
                    units != 0 -> units * 8
                    version == 0 -> VERSION_0_OVERFLOW
                    else -> {
                        require(end - i >= 3) { "TAP image is truncated" }
                        val cycles = (image[i].toInt() and 0xFF) or ((image[i + 1].toInt() and 0xFF) shl 8) or
                            ((image[i + 2].toInt() and 0xFF) shl 16)
                        i += 3
                        maxOf(cycles, 1)
                    }
                }
            }
            return Tape(pulses.copyOf(maxOf(count, 1 shl 16)), count)
        }
    }
}

/**
 * The 1530 datasette: its PLAY and RECORD keys, its motor, and its read and
 * write heads, on the cassette port. The motor runs while the processor port
 * asks for it and a key is down ([ProcessorPort]). Playing, each pulse of the
 * [tape] gives a falling edge on the read line, which goes to CIA 1's FLAG
 * input. Recording, each rising edge of the processor port's write line ends a
 * pulse, timed from the one before.
 */
class Datasette {
    var tape: Tape? = null
        set(value) {
            field = value
            countdown = 0
            sinceEdge = -1
        }

    /** PLAY is down. */
    var playDown: Boolean = false

    /** RECORD (with PLAY) is down. */
    var recordDown: Boolean = false

    /** The cassette sense switch is closed while a key is down. */
    val keyDown: Boolean get() = playDown || recordDown

    /** The level on the read line. */
    var readLine: Boolean = true
        private set

    var motorRunning: Boolean = false
        private set

    private var countdown = 0
    private var pulseLength = 0
    private var lastWrite = false
    private var sinceEdge = -1

    val recording: Boolean get() = motorRunning && recordDown

    /** One φ2 cycle, with the processor port's motor and write lines. */
    fun tick(motor: Boolean, write: Boolean) {
        motorRunning = motor && keyDown
        val cassette = tape
        if (!motorRunning || cassette == null) {
            readLine = true
            lastWrite = write
            return
        }
        if (recordDown) {
            if (sinceEdge >= 0) sinceEdge++
            if (write && !lastWrite) {
                if (sinceEdge > 0) cassette.writePulse(sinceEdge)
                sinceEdge = 0
            }
            lastWrite = write
            return
        }
        if (countdown == 0) {
            val next = cassette.nextPulse()
            if (next == null) {
                readLine = true
                return
            }
            pulseLength = next
            countdown = next
        }
        // Low for the first half of each pulse: the falling edge starts it.
        readLine = countdown <= pulseLength / 2
        countdown--
    }
}
