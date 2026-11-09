// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import c64slop.cpu.Cpu6502

/**
 * One PAL Commodore 64, as just switched on. UI, file loading and real-time
 * pacing live outside.
 *
 * Wiring, from the *C64 Programmer's Reference Guide* (appendix L) and MAME's
 * `c64.cpp`:
 * - CIA 1: port A drives the keyboard columns and reads joystick 2; port B
 *   reads the keyboard rows and joystick 1. FLAG is the datasette's read line.
 *   Its interrupt, and the VIC-II's, go to IRQ.
 * - CIA 2: port A bits 0-1 choose the VIC-II's 16 KiB bank (inverted), bits
 *   3-7 are the serial bus; port B is the user port, with nothing on it. Its
 *   interrupt, and RESTORE, go to NMI.
 * - The 6510's own port ([ProcessorPort]) banks the ROMs and drives the
 *   datasette's motor and write line, and reads its keys.
 * - Both CIAs' time-of-day clocks count the 50 Hz mains.
 * - Given [driveRom], a 1541 ([Drive1541]) is device 8 on the serial bus. It
 *   runs at 1 MHz, a little faster than the C64's 985,248 Hz; after each C64
 *   cycle it runs instructions until it has caught up, so the two stay within
 *   an instruction of each other, which the serial protocol and fast loaders
 *   tolerate.
 */
class C64Machine(kernalRom: ByteArray, basicRom: ByteArray, charRom: ByteArray, driveRom: ByteArray? = null) {
    val ram = ByteArray(0x10000)
    val colorRam = ByteArray(0x400)
    val clock = C64Clock()
    val port = ProcessorPort()
    val keyboard = KeyboardMatrix()
    val joystick1 = Joystick()
    val joystick2 = Joystick()
    val serialBus = SerialBus()
    val datasette = Datasette()

    val cia1: Cia6526 = Cia6526(object : CiaPorts {
        override fun readA(driven: Int): Int {
            val joystick = driven and joystick2.lines
            return joystick and keyboard.columns(cia1.drivenB and joystick1.lines)
        }

        override fun readB(driven: Int): Int {
            val joystick = driven and joystick1.lines
            return joystick and keyboard.rows(cia1.drivenA and joystick2.lines)
        }
    })

    val cia2: Cia6526 = Cia6526(object : CiaPorts {
        override fun readA(driven: Int): Int =
            (driven and 0x3F) or (if (serialBus.clk) 0x40 else 0) or (if (serialBus.data) 0x80 else 0)

        override fun writeA(driven: Int) = serialBus.drive(driven)
    })

    val vic = Vic6569(ram, charRom, colorRam) { (cia2.drivenA and 3) xor 3 }
    val sid = Sid6581()
    val bus = C64MemoryBus(ram, colorRam, basicRom, kernalRom, charRom, port, clock, vic)
    val cpu = Cpu6502(bus)

    /** The 1541 on the serial bus, or null without a drive ROM. */
    val drive: Drive1541? = driveRom?.let { Drive1541(it, serialBus) }

    private var nmiLine = false
    private var mainsPhase = 0

    /** How far the drive is behind the C64, in units of 1/(985,248 × 1,000,000) s. */
    private var driveLag = 0L

    init {
        // DRAM powers up in a pattern of alternating blocks; the KERNAL clears
        // what it uses. A fixed pattern keeps runs repeatable.
        for (i in ram.indices) ram[i] = if (i and 0x40 == 0) 0x00 else 0xFF.toByte()
        bus.attach(IoRegion.VIC, vic)
        bus.attach(IoRegion.SID, sid)
        bus.attach(IoRegion.CIA1, cia1)
        bus.attach(IoRegion.CIA2, cia2)
        clock.onTick = { tick() }
        cpu.reset()
    }

    /**
     * Pull the RESET line, as a reset button on the user or cartridge port does
     * (the breadbin C64 has none of its own). It resets the CPU, both CIAs, the
     * SID, the processor port, and the drive, which shares the line through the
     * serial bus; RAM and the VIC-II's registers survive.
     */
    fun reset() {
        port.reset()
        cia1.reset()
        cia2.reset()
        sid.reset()
        drive?.reset()
        cpu.reset()
    }

    /** Execute one CPU instruction or interrupt; return the elapsed cycles. */
    fun step(): Int {
        val before = clock.cycles
        cpu.irqAsserted = vic.irq || cia1.irq
        // NMI is edge-triggered: one request per falling edge of the line.
        val nmi = cia2.irq || keyboard.restore
        if (nmi && !nmiLine) cpu.requestNmi()
        nmiLine = nmi
        cpu.step()
        return (clock.cycles - before).toInt()
    }

    /** Run until at least [count] cycles have elapsed; stops at an instruction boundary. */
    fun runForCycles(count: Long) {
        require(count >= 0)
        val start = clock.cycles
        while (clock.cycles - start < count) step()
    }

    fun runForSeconds(seconds: Double) = runForCycles((seconds * C64Clock.CYCLES_PER_SECOND).toLong())

    /**
     * The text screen: 25 rows of 40 characters from the video matrix the
     * VIC-II is showing, as ASCII. Screen codes are taken in the power-on
     * character set; reverse video is ignored, and graphics characters show
     * as `#`.
     */
    fun screenLines(): List<String> {
        val base = vic.videoMatrixAddress
        return (0 until 25).map { row ->
            (0 until 40).map { column -> screenCodeToChar(ram[(base + row * 40 + column) and 0xFFFF].toInt() and 0x7F) }
                .joinToString("").trimEnd()
        }
    }

    private fun tick() {
        vic.tick()
        cia1.tick()
        cia2.tick()
        sid.tick()
        datasette.tick(port.motorOn, port.cassetteWrite)
        port.senseDown = datasette.keyDown
        cia1.setFlagInput(datasette.readLine)
        mainsPhase += MAINS_HZ
        if (mainsPhase >= C64Clock.CYCLES_PER_SECOND) {
            mainsPhase -= C64Clock.CYCLES_PER_SECOND
            cia1.todPulse()
            cia2.todPulse()
        }
        drive?.let {
            driveLag += Drive1541.CYCLES_PER_SECOND
            while (driveLag > 0) driveLag -= it.step().toLong() * C64Clock.CYCLES_PER_SECOND
        }
    }

    companion object {
        const val MAINS_HZ = 50

        /** A screen code, 0-127, as ASCII; `@` is 0, letters 1-26, and 32-63 match ASCII. */
        fun screenCodeToChar(code: Int): Char = when (code) {
            0 -> '@'
            in 1..26 -> 'A' + (code - 1)
            27 -> '['
            28 -> '£'
            29 -> ']'
            30 -> '↑'
            31 -> '←'
            in 32..63 -> code.toChar()
            else -> '#'
        }
    }
}
