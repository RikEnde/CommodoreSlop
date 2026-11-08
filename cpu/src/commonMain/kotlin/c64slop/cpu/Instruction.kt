// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

internal enum class AddressingMode {
    IMPLIED, ACCUMULATOR, IMMEDIATE,
    ZERO_PAGE, ZERO_PAGE_X, ZERO_PAGE_Y,
    ABSOLUTE, ABSOLUTE_X, ABSOLUTE_Y,
    INDIRECT, INDEXED_INDIRECT, INDIRECT_INDEXED, RELATIVE,
}

internal enum class Operation {
    // Documented.
    ADC, AND, ASL, BCC, BCS, BEQ, BIT, BMI, BNE, BPL, BRK, BVC, BVS,
    CLC, CLD, CLI, CLV, CMP, CPX, CPY, DEC, DEX, DEY, EOR, INC, INX,
    INY, JMP, JSR, LDA, LDX, LDY, LSR, NOP, ORA, PHA, PHP, PLA, PLP,
    ROL, ROR, RTI, RTS, SBC, SEC, SED, SEI, STA, STX, STY, TAX, TAY,
    TSX, TXA, TXS, TYA,

    // Undocumented, stable: read-modify-write combinations.
    SLO, // ASL memory, then ORA
    RLA, // ROL memory, then AND
    SRE, // LSR memory, then EOR
    RRA, // ROR memory, then ADC
    DCP, // DEC memory, then CMP
    ISC, // INC memory, then SBC

    // Undocumented, stable: other.
    SAX, // store A AND X
    LAX, // load A and X
    ANC, // AND immediate, carry = bit 7
    ALR, // AND immediate, then LSR A
    ARR, // AND immediate, then ROR A with unusual flags
    SBX, // X = (A AND X) - immediate, flags as CMP
    LAS, // A, X, S = memory AND S

    // Undocumented, unstable on real chips. See README for the chosen behavior.
    ANE, // A = (A OR magic) AND X AND immediate
    LXA, // A, X = (A OR magic) AND immediate
    SHA, // store A AND X AND (high byte + 1)
    SHX, // store X AND (high byte + 1)
    SHY, // store Y AND (high byte + 1)
    TAS, // S = A AND X, then store S AND (high byte + 1)

    JAM, // halt until reset
}

internal data class Instruction(
    val operation: Operation,
    val mode: AddressingMode,
    val documented: Boolean = true,
)

/** Thrown only by a CPU in strict mode, which rejects undocumented opcodes. */
class UndocumentedOpcodeException(val opcode: Int, val address: Int) : IllegalStateException(
    "Undocumented NMOS 6502 opcode 0x${opcode.toString(16).padStart(2, '0')} " +
        "at 0x${address.toString(16).padStart(4, '0')}",
)
