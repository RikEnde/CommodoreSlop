// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/** What is wired to a VIA's ports. Values are 8-bit; undriven inputs read 1. */
interface ViaPorts {
    /** Levels on the port A pins, given what the VIA drives (outputs, with inputs high). */
    fun readA(driven: Int): Int = 0xFF
    fun readB(driven: Int): Int = 0xFF

    /** Called when the levels the VIA drives change: outputs, with inputs high. */
    fun writeA(driven: Int) {}
    fun writeB(driven: Int) {}
}

/**
 * A 6522 Versatile Interface Adapter, clocked by the 1541's 1 MHz clock. The
 * drive has two: one for the serial bus, one for the disk mechanism.
 *
 * Copied from Beebslop, where register behavior follows the BBC Micro's
 * *Advanced User Guide* (chapter 22) and MAME's `6522via.cpp`. Each [tick] is
 * one 1 MHz cycle:
 *
 * - Timer 1 counts N, N-1, ... 0, `&FFFF`, then reloads N from its latch, so it
 *   runs for N+2 cycles. It reloads in one-shot mode too, but only interrupts
 *   again after T1C-H is written. The flag is set as the counter reaches `&FFFF`.
 * - Timer 2 is one-shot: it flags once as it reaches `&FFFF`, then keeps counting.
 * - After T1C-H or T2C-H is written, the counter holds its new value for one
 *   cycle before counting.
 *
 * Not yet emulated: the shift register only stores its value; T2 cannot count
 * PB6 pulses. Exact timing against the CPU's access within a cycle has not been
 * checked against real hardware.
 */
class Via6522(private val ports: ViaPorts = object : ViaPorts {}) : IoDevice {
    var ora = 0; private set
    var orb = 0; private set
    var ddra = 0; private set
    var ddrb = 0; private set
    var acr = 0; private set
    var pcr = 0; private set
    var ifr = 0; private set
    var ier = 0; private set

    var timer1 = 0; private set
    var timer1Latch = 0; private set
    var timer2 = 0; private set
    private var timer2LatchLow = 0
    private var timer1Hold = false
    private var timer1Reload = false
    private var timer2Hold = false
    private var timer1Armed = false
    private var timer2Armed = false
    private var pb7 = true
    private var shiftRegister = 0

    private var latchA = 0
    private var latchB = 0
    private var ca1 = true
    private var ca2 = true
    private var cb1 = true
    private var cb2 = true
    private var ca2Out = true
    private var cb2Out = true
    private var ca2PulseEnds = false
    private var cb2PulseEnds = false

    /** The IRQ output, active when an enabled flag is set. */
    val irq: Boolean get() = ifr and ier and 0x7F != 0

    /** Level driven on CA2 and CB2 when they are outputs. */
    val ca2Output: Boolean get() = ca2Out
    val cb2Output: Boolean get() = cb2Out

    /** The RESET input. Timers, their latches and the shift register are not reset. */
    fun reset() {
        ora = 0; orb = 0; ddra = 0; ddrb = 0
        acr = 0; pcr = 0; ifr = 0; ier = 0
        timer1Armed = false
        timer2Armed = false
        pb7 = true
        latchA = 0; latchB = 0
        ca2Out = true; cb2Out = true
        ca2PulseEnds = false; cb2PulseEnds = false
        outputA()
        outputB()
    }

    /** One cycle of the 1 MHz clock. */
    fun tick() {
        if (timer1Reload) {
            timer1Reload = false
            timer1 = timer1Latch
        } else if (timer1Hold) {
            timer1Hold = false
        } else {
            timer1 = (timer1 - 1) and 0xFFFF
            if (timer1 == 0xFFFF) {
                if (timer1Armed) {
                    setFlag(T1)
                    if (acr and ACR_T1_PB7 != 0) {
                        pb7 = !pb7
                        outputB()
                    }
                    if (acr and ACR_T1_CONTINUOUS == 0) timer1Armed = false
                }
                timer1Reload = true
            }
        }
        if (timer2Hold) {
            timer2Hold = false
        } else {
            timer2 = (timer2 - 1) and 0xFFFF
            if (timer2 == 0xFFFF && timer2Armed) {
                setFlag(T2)
                timer2Armed = false
            }
        }
        if (ca2PulseEnds) { ca2PulseEnds = false; ca2Out = true }
        if (cb2PulseEnds) { cb2PulseEnds = false; cb2Out = true }
    }

    override fun read(offset: Int): Int = when (offset and 0x0F) {
        0 -> {
            val value = if (acr and ACR_LATCH_B != 0) latchB else inputB()
            clearFlags(CB1 or (if (cb2IndependentInput()) 0 else CB2))
            if (cb2HandshakeOrPulse()) startCb2Handshake()
            value
        }
        1 -> {
            val value = if (acr and ACR_LATCH_A != 0) latchA else inputA()
            clearFlags(CA1 or (if (ca2IndependentInput()) 0 else CA2))
            if (ca2HandshakeOrPulse()) startCa2Handshake()
            value
        }
        4 -> { clearFlags(T1); timer1 and 0xFF }
        8 -> { clearFlags(T2); timer2 and 0xFF }
        10 -> { clearFlags(SR); shiftRegister }
        else -> peek(offset)
    }

    override fun peek(offset: Int): Int = when (offset and 0x0F) {
        0 -> if (acr and ACR_LATCH_B != 0) latchB else inputB()
        1, 15 -> if (acr and ACR_LATCH_A != 0) latchA else inputA()
        2 -> ddrb
        3 -> ddra
        4 -> timer1 and 0xFF
        5 -> timer1 shr 8
        6 -> timer1Latch and 0xFF
        7 -> timer1Latch shr 8
        8 -> timer2 and 0xFF
        9 -> timer2 shr 8
        10 -> shiftRegister
        11 -> acr
        12 -> pcr
        13 -> ifr or (if (irq) 0x80 else 0)
        else -> ier or 0x80
    }

    override fun write(offset: Int, value: Int) {
        val data = value and 0xFF
        when (offset and 0x0F) {
            0 -> {
                orb = data
                outputB()
                clearFlags(CB1 or (if (cb2IndependentInput()) 0 else CB2))
                if (cb2HandshakeOrPulse()) startCb2Handshake()
            }
            1 -> {
                ora = data
                outputA()
                clearFlags(CA1 or (if (ca2IndependentInput()) 0 else CA2))
                if (ca2HandshakeOrPulse()) startCa2Handshake()
            }
            15 -> { ora = data; outputA() }
            2 -> { ddrb = data; outputB() }
            3 -> { ddra = data; outputA() }
            4, 6 -> timer1Latch = (timer1Latch and 0xFF00) or data
            5 -> {
                timer1Latch = (timer1Latch and 0x00FF) or (data shl 8)
                timer1 = timer1Latch
                timer1Hold = true
                timer1Reload = false
                timer1Armed = true
                clearFlags(T1)
                if (acr and ACR_T1_PB7 != 0) { pb7 = false; outputB() }
            }
            7 -> {
                timer1Latch = (timer1Latch and 0x00FF) or (data shl 8)
                clearFlags(T1)
            }
            8 -> timer2LatchLow = data
            9 -> {
                timer2 = timer2LatchLow or (data shl 8)
                timer2Hold = true
                timer2Armed = true
                clearFlags(T2)
            }
            10 -> { shiftRegister = data; clearFlags(SR) }
            11 -> {
                if (acr and ACR_LATCH_A == 0 && data and ACR_LATCH_A != 0) latchA = inputA()
                if (acr and ACR_LATCH_B == 0 && data and ACR_LATCH_B != 0) latchB = inputB()
                acr = data
                outputB()
            }
            12 -> {
                pcr = data
                if (data and 0x0C == 0x0C) ca2Out = data and 0x02 != 0
                if (data and 0xC0 == 0xC0) cb2Out = data and 0x20 != 0
            }
            13 -> clearFlags(data and 0x7F)
            14 -> ier = if (data and 0x80 != 0) ier or (data and 0x7F) else ier and (data and 0x7F).inv()
        }
    }

    /** CA1 input level. The active edge is chosen by PCR bit 0. */
    fun setCa1(level: Boolean) {
        if (level == ca1) return
        ca1 = level
        if (level == (pcr and 0x01 != 0)) {
            if (acr and ACR_LATCH_A != 0) latchA = inputA()
            setFlag(CA1)
            if (pcr and 0x0E == 0x08) ca2Out = true // handshake: data taken
        }
    }

    /** CA2 input level. Used when PCR bit 3 is clear; bit 2 chooses the edge. */
    fun setCa2(level: Boolean) {
        if (level == ca2) return
        ca2 = level
        if (pcr and 0x08 == 0 && level == (pcr and 0x04 != 0)) setFlag(CA2)
    }

    fun setCb1(level: Boolean) {
        if (level == cb1) return
        cb1 = level
        if (level == (pcr and 0x10 != 0)) {
            if (acr and ACR_LATCH_B != 0) latchB = inputB()
            setFlag(CB1)
            if (pcr and 0xE0 == 0x80) cb2Out = true
        }
    }

    fun setCb2(level: Boolean) {
        if (level == cb2) return
        cb2 = level
        if (pcr and 0x80 == 0 && level == (pcr and 0x40 != 0)) setFlag(CB2)
    }

    private fun drivenA() = (ora and ddra) or ddra.inv() and 0xFF
    private fun drivenB(): Int {
        var value = (orb and ddrb) or ddrb.inv() and 0xFF
        if (acr and ACR_T1_PB7 != 0) value = (value and 0x7F) or (if (pb7) 0x80 else 0)
        return value
    }

    /** Port A reads the pins, even for outputs. */
    private fun inputA() = ports.readA(drivenA()) and 0xFF

    /** Port B reads the output register for output bits, and the pins for inputs. */
    private fun inputB(): Int {
        val pins = ports.readB(drivenB())
        var value = (pins and ddrb.inv()) or (orb and ddrb)
        if (acr and ACR_T1_PB7 != 0) value = (value and 0x7F) or (if (pb7) 0x80 else 0)
        return value and 0xFF
    }

    private fun outputA() = ports.writeA(drivenA())
    private fun outputB() = ports.writeB(drivenB())

    private fun ca2IndependentInput() = pcr and 0x0A == 0x02
    private fun cb2IndependentInput() = pcr and 0xA0 == 0x20
    private fun ca2HandshakeOrPulse() = pcr and 0x0C == 0x08
    private fun cb2HandshakeOrPulse() = pcr and 0xC0 == 0x80

    private fun startCa2Handshake() {
        ca2Out = false
        if (pcr and 0x02 != 0) ca2PulseEnds = true
    }

    private fun startCb2Handshake() {
        cb2Out = false
        if (pcr and 0x20 != 0) cb2PulseEnds = true
    }

    private fun setFlag(flag: Int) { ifr = ifr or flag }
    private fun clearFlags(flags: Int) { ifr = ifr and flags.inv() and 0x7F }

    companion object {
        const val CA2 = 0x01
        const val CA1 = 0x02
        const val SR = 0x04
        const val CB2 = 0x08
        const val CB1 = 0x10
        const val T2 = 0x20
        const val T1 = 0x40

        private const val ACR_LATCH_A = 0x01
        private const val ACR_LATCH_B = 0x02
        private const val ACR_T1_CONTINUOUS = 0x40
        private const val ACR_T1_PB7 = 0x80
    }
}
