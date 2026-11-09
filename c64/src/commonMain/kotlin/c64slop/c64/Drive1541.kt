// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import c64slop.cpu.Cpu6502
import c64slop.cpu.MemoryBus

/**
 * A Commodore 1541 disk drive, device [deviceNumber] on the [serialBus]: a
 * computer of its own, with a 6502 at 1 MHz, 2 KiB of RAM, the 16 KiB DOS ROM
 * and two 6522 VIAs, wired as in MAME's `c1541.cpp`:
 *
 * | Address | Contents |
 * |---|---|
 * | `$0000-$07FF` | RAM |
 * | `$1800-$180F` | VIA 1: the serial bus |
 * | `$1C00-$1C0F` | VIA 2: the disk mechanism |
 * | `$C000-$FFFF` | DOS 2.6 ROM |
 *
 * (each repeating through the unused address lines).
 *
 * - **VIA 1** port B: bit 0 DATA in, 1 DATA out, 2 CLK in, 3 CLK out, 4 ATN
 *   acknowledge, 5-6 the device number jumpers, 7 ATN in. Inputs read 1 while
 *   the line is pulled low; outputs pull it low when 1. CA1 sees ATN, so the
 *   drive is interrupted when the computer calls the bus to attention.
 *   Hardware answers ATN at once: DATA is pulled low while ATN and ATN
 *   acknowledge disagree, until the DOS catches up.
 * - **VIA 2** port A is the byte from (or for) the disk. Port B: bits 0-1
 *   step the head, 2 runs the motor, 3 lights the LED, 4 reads the write
 *   protect sensor (0 when the notch is covered), 5-6 choose the speed zone,
 *   7 reads SYNC (0 over a sync mark). CA2 lets byte-ready set the CPU's
 *   overflow flag (SOE); CB2 is high to read, low to write. CA1 is byte-ready.
 *
 * The head moves in half-tracks as the stepper phases turn; data is on the
 * whole tracks. While the motor runs, a byte passes the head every 26-32 µs
 * ([Disk.cyclesPerByte], from the zone the DOS selected). Reading, a byte
 * that is not part of a sync mark is latched and signals byte-ready; writing,
 * the byte on port A is recorded, and byte-ready asks for the next one.
 *
 * [step] runs one drive instruction; [C64Machine] keeps the drive level with
 * its own clock, to within an instruction.
 */
class Drive1541(rom: ByteArray, private val serialBus: SerialBus, val deviceNumber: Int = 8) : SerialBus.Device {
    init {
        require(rom.size == ROM_SIZE) { "The 1541 ROM must be 16 KiB, was ${rom.size} bytes" }
        require(deviceNumber in 8..11)
    }

    private val rom = rom.copyOf()
    val ram = ByteArray(0x800)

    /** The disk in the drive, or null. */
    var disk: Disk? = null
        set(value) {
            field = value
            // The write protect sensor is blocked while a disk slides in or out;
            // the DOS watches it to notice a disk change.
            sensorBlockedCycles = DISK_CHANGE_CYCLES
        }

    /** Cycles of the drive's 1 MHz clock since power-on. */
    var cycles: Long = 0
        private set

    /** Half-track under the head: 0 is track 1, 2 is track 2, and so on. */
    var halfTrack: Int = 34 // track 18, where the DOS expects it after power-on
        private set

    val track: Int get() = halfTrack / 2 + 1

    /** Position of the head around the track, in bytes. */
    var position: Int = 0
        private set

    // VIA 1 outputs.
    private var dataOut = false
    private var clkOut = false
    private var atnAcknowledge = false

    // VIA 2 outputs.
    private var stepperPhase = 2
    var motorOn: Boolean = false
        private set
    var ledOn: Boolean = false
        private set
    private var zone = 3
    private var writeByte = 0xFF

    // The read circuitry.
    private var readLatch = 0
    private var sync = false
    private var lastByte = 0
    private var byteCycles = 0
    private var byteReadyPulse = false
    private var sensorBlockedCycles = 0

    val via1 = Via6522(object : ViaPorts {
        override fun readB(driven: Int): Int {
            var pins = driven and 0x1A
            if (!serialBus.data) pins = pins or 0x01
            if (!serialBus.clk) pins = pins or 0x04
            pins = pins or (((deviceNumber - 8) and 3) shl 5)
            if (serialBus.atnAsserted) pins = pins or 0x80
            return pins
        }

        override fun writeB(driven: Int) {
            dataOut = driven and 0x02 != 0
            clkOut = driven and 0x08 != 0
            atnAcknowledge = driven and 0x10 != 0
        }
    })

    val via2 = Via6522(object : ViaPorts {
        override fun readA(driven: Int): Int = readLatch

        override fun writeA(driven: Int) {
            writeByte = driven
        }

        override fun readB(driven: Int): Int {
            var pins = driven and 0x6F
            if (writeProtectSensor()) pins = pins or 0x10
            if (!sync) pins = pins or 0x80
            return pins
        }

        override fun writeB(driven: Int) {
            val phase = driven and 3
            if (phase == (stepperPhase + 1) and 3) moveHead(+1)
            if (phase == (stepperPhase - 1) and 3) moveHead(-1)
            stepperPhase = phase
            motorOn = driven and 0x04 != 0
            ledOn = driven and 0x08 != 0
            zone = (driven shr 5) and 3
        }
    })

    private val bus = object : MemoryBus {
        override fun read(address: Int): Int {
            tick()
            return when {
                address and 0x8000 != 0 -> rom[address and 0x3FFF].toInt() and 0xFF
                address and 0x1800 == 0 -> ram[address and 0x7FF].toInt() and 0xFF
                address and 0x1C00 == 0x1800 -> via1.read(address and 0x0F)
                address and 0x1C00 == 0x1C00 -> via2.read(address and 0x0F)
                else -> address shr 8 // Nothing answers: the bus keeps the address's high byte.
            }
        }

        override fun write(address: Int, value: Int) {
            tick()
            when {
                address and 0x8000 != 0 -> {}
                address and 0x1800 == 0 -> ram[address and 0x7FF] = value.toByte()
                address and 0x1C00 == 0x1800 -> via1.write(address and 0x0F, value)
                address and 0x1C00 == 0x1C00 -> via2.write(address and 0x0F, value)
            }
        }
    }

    val cpu = Cpu6502(bus)

    /** The drive pulls CLK low as VIA 1 says. */
    override val clkPulled: Boolean get() = clkOut

    /** DATA low as VIA 1 says, or while ATN and its acknowledge disagree. */
    override val dataPulled: Boolean get() = dataOut || (serialBus.atnAsserted != atnAcknowledge)

    init {
        serialBus.attach(this)
        reset()
    }

    /** The RESET line, shared with the computer: the CPU and both VIAs restart. */
    fun reset() {
        via1.reset()
        via2.reset()
        cpu.reset()
    }

    /** Runs one instruction or interrupt; returns its cycles. */
    fun step(): Int {
        cpu.irqAsserted = via1.irq || via2.irq
        return cpu.step()
    }

    /** Whether the write protect sensor sees light: no notch cover, or no disk. */
    private fun writeProtectSensor(): Boolean {
        if (sensorBlockedCycles > 0) return false
        return disk?.writeProtected != true
    }

    private fun moveHead(direction: Int) {
        val oldTrack = disk?.let { if (halfTrack % 2 == 0) it.track(track).size else null }
        halfTrack = (halfTrack + direction).coerceIn(0, 2 * Disk.MAX_TRACKS - 1)
        // Keep the angle: the new track may hold a different number of bytes.
        val newTrack = disk?.let { if (halfTrack % 2 == 0) it.track(track).size else null }
        if (oldTrack != null && newTrack != null) position = (position.toLong() * newTrack / oldTrack).toInt()
    }

    /** One cycle of the 1 MHz clock. */
    private fun tick() {
        cycles++
        via1.tick()
        via2.tick()
        via1.setCa1(serialBus.atnAsserted)
        if (sensorBlockedCycles > 0) sensorBlockedCycles--
        if (byteReadyPulse) {
            byteReadyPulse = false
            via2.setCa1(true)
        }
        if (!motorOn) return
        if (++byteCycles < Disk.cyclesPerByte(zone)) return
        byteCycles = 0
        nextByte()
    }

    /** One byte passes the head. */
    private fun nextByte() {
        val surface = disk
        val ring = if (surface == null || halfTrack % 2 != 0) null else surface.track(track)
        if (ring == null) {
            // No disk, or between tracks: no flux changes, so no sync and no data.
            sync = false
            return
        }
        position %= ring.size
        val reading = via2.cb2Output
        if (reading) {
            val value = ring[position].toInt() and 0xFF
            sync = value == 0xFF && lastByte == 0xFF
            lastByte = value
            if (!sync) {
                readLatch = value
                byteReady()
            }
        } else {
            sync = false
            if (!surface!!.writeProtected) surface.write(track, position, writeByte)
            lastByte = writeByte
            byteReady()
        }
        position = (position + 1) % ring.size
    }

    private fun byteReady() {
        if (via2.ca2Output) cpu.setOverflow()
        via2.setCa1(false)
        byteReadyPulse = true
    }

    companion object {
        const val ROM_SIZE = 0x4000
        const val CYCLES_PER_SECOND = 1_000_000

        /** How long the sensor is blocked while a disk goes in or out: half a second. */
        private const val DISK_CHANGE_CYCLES = 500_000
    }
}
