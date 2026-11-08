// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/** A chip in the I/O area. Offsets are relative to the start of its [IoRegion]. */
interface IoDevice {
    fun read(offset: Int): Int
    fun write(offset: Int, value: Int)

    /** Read for debuggers and tests, without side effects such as clearing flags. */
    fun peek(offset: Int): Int
}

/**
 * The I/O area at `$D000-$DFFF`, when the PLA maps it in. Each chip answers
 * throughout its range and repeats its registers within it: the VIC-II every
 * 64 bytes, the SID every 32, the CIAs every 16 (*C64 Programmer's Reference
 * Guide*, appendix L; MAME `c64.cpp`).
 */
enum class IoRegion(val first: Int, val last: Int, val registers: Int) {
    VIC(0xD000, 0xD3FF, 0x40),
    SID(0xD400, 0xD7FF, 0x20),
    COLOR_RAM(0xD800, 0xDBFF, 0x400),
    CIA1(0xDC00, 0xDCFF, 0x10),
    CIA2(0xDD00, 0xDDFF, 0x10),
    IO1(0xDE00, 0xDEFF, 0x100),
    IO2(0xDF00, 0xDFFF, 0x100),
    ;

    companion object {
        private val byPage = Array(16) { page -> entries.single { (0xD000 + page * 0x100) in it.first..it.last } }

        /** The region for an address in `$D000-$DFFF`. */
        fun of(address: Int): IoRegion = byPage[(address shr 8) and 0x0F]
    }
}
