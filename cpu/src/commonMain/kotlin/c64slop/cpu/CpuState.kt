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

    /** The status flags NV-BDIZC, a capital letter for each one set: `nv-BdIzC`. */
    val flags: String
        get() = "NV-BDIZC".mapIndexed { i, c ->
            if (c == '-' || status and (0x80 shr i) != 0) c else c.lowercaseChar()
        }.joinToString("")

    /** One line for a log or a debugger: `PC=3FB6 A=00 X=FF Y=00 SP=FF P=34 nv-BdIzc`. */
    val summary: String
        get() = "PC=${hex4(programCounter)} A=${hex2(accumulator)} X=${hex2(x)} Y=${hex2(y)} " +
            "SP=${hex2(stackPointer)} P=${hex2(status)} $flags"
}
