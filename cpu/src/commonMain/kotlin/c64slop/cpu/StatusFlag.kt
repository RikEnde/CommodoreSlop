// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

/** Bit positions in the processor status byte. */
object StatusFlag {
    const val CARRY = 0x01
    const val ZERO = 0x02
    const val INTERRUPT_DISABLE = 0x04
    const val DECIMAL = 0x08
    const val BREAK = 0x10
    const val UNUSED = 0x20
    const val OVERFLOW = 0x40
    const val NEGATIVE = 0x80
}
