// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * Machine time in cycles of the PAL C64's system clock, φ2: 985,248 Hz, the
 * 17.734475 MHz crystal divided by 18. One cycle is one CPU bus cycle and one
 * VIC-II cycle of 8 pixels.
 *
 * The CPU normally takes one cycle per bus access. The VIC-II takes the bus
 * from it on bad lines and for sprite data by pulling BA low, which is wired to
 * the 6510's RDY: the CPU then stops at its next read, and resumes when BA goes
 * high again. Writes go ahead, which is why the VIC-II gives three cycles'
 * warning: the 6510 never writes more than three times in a row. The bus models
 * this by repeating [cycle] before a read while BA is low.
 */
class C64Clock {
    var cycles: Long = 0
        private set

    /** Called after each cycle with the new time; the machine clocks its chips here. */
    var onTick: ((Long) -> Unit)? = null

    /** Advance time without the CPU (also useful in tests). */
    fun advance(count: Int) {
        require(count >= 0)
        val listener = onTick
        if (listener == null) {
            cycles += count
            return
        }
        repeat(count) {
            cycles++
            listener(cycles)
        }
    }

    /** One bus cycle. */
    internal fun cycle() {
        cycles++
        onTick?.invoke(cycles)
    }

    companion object {
        const val CYCLES_PER_SECOND = 985_248

        /** 63 cycles a line, 312 lines a frame: 50.125 frames a second. */
        const val CYCLES_PER_FRAME = 63 * 312
    }
}
