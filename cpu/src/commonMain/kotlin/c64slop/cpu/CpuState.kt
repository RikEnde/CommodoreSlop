// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

/** Register values for inspection or initialization, not a whole-machine save state. */
data class CpuState(
    val accumulator: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val stackPointer: Int = 0xFD,
    val programCounter: Int = 0,
    val status: Int = StatusFlag.UNUSED or StatusFlag.INTERRUPT_DISABLE,
) {
    init {
        require(accumulator in 0..0xFF)
        require(x in 0..0xFF)
        require(y in 0..0xFF)
        require(stackPointer in 0..0xFF)
        require(programCounter in 0..0xFFFF)
        require(status in 0..0xFF)
    }
}
