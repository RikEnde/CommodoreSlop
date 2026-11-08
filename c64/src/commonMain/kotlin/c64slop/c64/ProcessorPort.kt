// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * The 6510's built-in I/O port: its data direction register at `$00` and its
 * data register at `$01`. The CPU core is a plain 6502, so the port lives here,
 * on the bus.
 *
 * | Bit | Line | Direction (as the KERNAL sets it) |
 * |---|---|---|
 * | 0 | LORAM: BASIC ROM | out |
 * | 1 | HIRAM: KERNAL ROM | out |
 * | 2 | CHAREN: I/O or character ROM | out |
 * | 3 | Cassette write | out |
 * | 4 | Cassette sense: 0 while a datasette key is down | in |
 * | 5 | Cassette motor: 0 runs it | out |
 *
 * Bits 0-2 have pull-up resistors, so after reset, with every bit an input,
 * they read 1 and the ROMs and I/O are mapped in. Bits 6 and 7 are not bonded
 * out on the 6510 and read 0 here. The real chip's floating inputs fade over
 * time, which a few copy-protection schemes measure; that is not emulated.
 */
class ProcessorPort {
    var direction: Int = 0
        private set
    var data: Int = 0
        private set

    /** Cassette sense input: true while PLAY (or another datasette key) is down. */
    var senseDown: Boolean = false

    fun reset() {
        direction = 0
        data = 0
    }

    fun read(address: Int): Int = if (address and 1 == 0) direction else levels()

    fun write(address: Int, value: Int) {
        if (address and 1 == 0) direction = value and 0xFF else data = value and 0xFF
    }

    /** What the pins carry: outputs as written, inputs from their pull-ups and the datasette. */
    fun levels(): Int {
        val inputs = 0x07 or (if (senseDown) 0 else 0x10)
        return ((data and direction) or (inputs and direction.inv())) and 0xFF
    }

    val loram: Boolean get() = levels() and 0x01 != 0
    val hiram: Boolean get() = levels() and 0x02 != 0
    val charen: Boolean get() = levels() and 0x04 != 0

    /** The cassette write line's level. */
    val cassetteWrite: Boolean get() = direction and 0x08 != 0 && data and 0x08 != 0

    /** The motor runs while bit 5 is an output driven low. */
    val motorOn: Boolean get() = direction and 0x20 != 0 && data and 0x20 == 0
}
