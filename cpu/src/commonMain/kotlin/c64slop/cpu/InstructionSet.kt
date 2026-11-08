// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import c64slop.cpu.AddressingMode.*
import c64slop.cpu.Operation.*

/**
 * All 256 NMOS opcodes: the 151 documented ones, then the 105 undocumented ones.
 *
 * Many opcodes encode an operation and addressing mode using three bit fields:
 *
 *     aaa bbb cc
 *      |   |  +-- instruction group
 *      |   +----- addressing mode within that group
 *      +--------- operation within that group
 *
 * Group 01 is especially regular. Its aaa field selects the operation:
 *
 *     000 ORA    001 AND    010 EOR    011 ADC
 *     100 STA    101 LDA    110 CMP    111 SBC
 *
 * Its bbb field selects the addressing mode. For example, LDA is 101 bbb 01:
 *
 *     bbb   Mode                 Example          Opcode   Binary
 *     000   Indexed indirect     LDA ($44,X)      $A1      101 000 01
 *     001   Zero page            LDA $44          $A5      101 001 01
 *     010   Immediate            LDA #$44         $A9      101 010 01
 *     011   Absolute             LDA $1234        $AD      101 011 01
 *     100   Indirect indexed     LDA ($44),Y      $B1      101 100 01
 *     101   Zero page, X         LDA $44,X        $B5      101 101 01
 *     110   Absolute, Y          LDA $1234,Y      $B9      101 110 01
 *     111   Absolute, X          LDA $1234,X      $BD      101 111 01
 *
 * Other group-01 operations share these modes, except for immediate STA, which
 * has no documented opcode. Groups 00 and 10 use different mappings and have
 * exceptions. The 3/3/2 grouping in the comments below shows bit positions;
 * it does not imply that every instruction follows this decoding scheme.
 *
 * Operand values follow the opcode in memory; they are not part of its bits:
 *
 *     LDA #$42    -> A9 42
 *     LDA #$99    -> A9 99
 *     LDA $1234   -> AD 34 12    (address low byte first)
 *
 * The hardware decoder matches opcode bits and execution cycles, allowing
 * related instructions to share control logic. Undocumented opcodes can activate
 * overlapping controls. Here, an explicit table keeps supported combinations
 * and exceptions visible without recreating the chip's decoder circuitry.
 *
 * Encoding: https://llx.com/Neil/a2/opcodes.html
 * Hardware decoding: https://www.pagetable.com/31
 */
internal object InstructionSet {
    val instructions: Array<Instruction?> = arrayOfNulls<Instruction>(256).apply {
        this[0x00] = Instruction(BRK, IMPLIED)          // 000 000 00
        this[0x01] = Instruction(ORA, INDEXED_INDIRECT) // 000 000 01
        this[0x05] = Instruction(ORA, ZERO_PAGE)        // 000 001 01
        this[0x06] = Instruction(ASL, ZERO_PAGE)        // 000 001 10
        this[0x08] = Instruction(PHP, IMPLIED)          // 000 010 00
        this[0x09] = Instruction(ORA, IMMEDIATE)        // 000 010 01
        this[0x0A] = Instruction(ASL, ACCUMULATOR)      // 000 010 10
        this[0x0D] = Instruction(ORA, ABSOLUTE)         // 000 011 01
        this[0x0E] = Instruction(ASL, ABSOLUTE)         // 000 011 10

        this[0x10] = Instruction(BPL, RELATIVE)         // 000 100 00
        this[0x11] = Instruction(ORA, INDIRECT_INDEXED) // 000 100 01
        this[0x15] = Instruction(ORA, ZERO_PAGE_X)      // 000 101 01
        this[0x16] = Instruction(ASL, ZERO_PAGE_X)      // 000 101 10
        this[0x18] = Instruction(CLC, IMPLIED)          // 000 110 00
        this[0x19] = Instruction(ORA, ABSOLUTE_Y)       // 000 110 01
        this[0x1D] = Instruction(ORA, ABSOLUTE_X)       // 000 111 01
        this[0x1E] = Instruction(ASL, ABSOLUTE_X)       // 000 111 10

        this[0x20] = Instruction(JSR, ABSOLUTE)         // 001 000 00
        this[0x21] = Instruction(AND, INDEXED_INDIRECT) // 001 000 01
        this[0x24] = Instruction(BIT, ZERO_PAGE)        // 001 001 00
        this[0x25] = Instruction(AND, ZERO_PAGE)        // 001 001 01
        this[0x26] = Instruction(ROL, ZERO_PAGE)        // 001 001 10
        this[0x28] = Instruction(PLP, IMPLIED)          // 001 010 00
        this[0x29] = Instruction(AND, IMMEDIATE)        // 001 010 01
        this[0x2A] = Instruction(ROL, ACCUMULATOR)      // 001 010 10
        this[0x2C] = Instruction(BIT, ABSOLUTE)         // 001 011 00
        this[0x2D] = Instruction(AND, ABSOLUTE)         // 001 011 01
        this[0x2E] = Instruction(ROL, ABSOLUTE)         // 001 011 10

        this[0x30] = Instruction(BMI, RELATIVE)         // 001 100 00
        this[0x31] = Instruction(AND, INDIRECT_INDEXED) // 001 100 01
        this[0x35] = Instruction(AND, ZERO_PAGE_X)      // 001 101 01
        this[0x36] = Instruction(ROL, ZERO_PAGE_X)      // 001 101 10
        this[0x38] = Instruction(SEC, IMPLIED)          // 001 110 00
        this[0x39] = Instruction(AND, ABSOLUTE_Y)       // 001 110 01
        this[0x3D] = Instruction(AND, ABSOLUTE_X)       // 001 111 01
        this[0x3E] = Instruction(ROL, ABSOLUTE_X)       // 001 111 10

        this[0x40] = Instruction(RTI, IMPLIED)          // 010 000 00
        this[0x41] = Instruction(EOR, INDEXED_INDIRECT) // 010 000 01
        this[0x45] = Instruction(EOR, ZERO_PAGE)        // 010 001 01
        this[0x46] = Instruction(LSR, ZERO_PAGE)        // 010 001 10
        this[0x48] = Instruction(PHA, IMPLIED)          // 010 010 00
        this[0x49] = Instruction(EOR, IMMEDIATE)        // 010 010 01
        this[0x4A] = Instruction(LSR, ACCUMULATOR)      // 010 010 10
        this[0x4C] = Instruction(JMP, ABSOLUTE)         // 010 011 00
        this[0x4D] = Instruction(EOR, ABSOLUTE)         // 010 011 01
        this[0x4E] = Instruction(LSR, ABSOLUTE)         // 010 011 10

        this[0x50] = Instruction(BVC, RELATIVE)         // 010 100 00
        this[0x51] = Instruction(EOR, INDIRECT_INDEXED) // 010 100 01
        this[0x55] = Instruction(EOR, ZERO_PAGE_X)      // 010 101 01
        this[0x56] = Instruction(LSR, ZERO_PAGE_X)      // 010 101 10
        this[0x58] = Instruction(CLI, IMPLIED)          // 010 110 00
        this[0x59] = Instruction(EOR, ABSOLUTE_Y)       // 010 110 01
        this[0x5D] = Instruction(EOR, ABSOLUTE_X)       // 010 111 01
        this[0x5E] = Instruction(LSR, ABSOLUTE_X)       // 010 111 10

        this[0x60] = Instruction(RTS, IMPLIED)          // 011 000 00
        this[0x61] = Instruction(ADC, INDEXED_INDIRECT) // 011 000 01
        this[0x65] = Instruction(ADC, ZERO_PAGE)        // 011 001 01
        this[0x66] = Instruction(ROR, ZERO_PAGE)        // 011 001 10
        this[0x68] = Instruction(PLA, IMPLIED)          // 011 010 00
        this[0x69] = Instruction(ADC, IMMEDIATE)        // 011 010 01
        this[0x6A] = Instruction(ROR, ACCUMULATOR)      // 011 010 10
        this[0x6C] = Instruction(JMP, INDIRECT)         // 011 011 00
        this[0x6D] = Instruction(ADC, ABSOLUTE)         // 011 011 01
        this[0x6E] = Instruction(ROR, ABSOLUTE)         // 011 011 10

        this[0x70] = Instruction(BVS, RELATIVE)         // 011 100 00
        this[0x71] = Instruction(ADC, INDIRECT_INDEXED) // 011 100 01
        this[0x75] = Instruction(ADC, ZERO_PAGE_X)      // 011 101 01
        this[0x76] = Instruction(ROR, ZERO_PAGE_X)      // 011 101 10
        this[0x78] = Instruction(SEI, IMPLIED)          // 011 110 00
        this[0x79] = Instruction(ADC, ABSOLUTE_Y)       // 011 110 01
        this[0x7D] = Instruction(ADC, ABSOLUTE_X)       // 011 111 01
        this[0x7E] = Instruction(ROR, ABSOLUTE_X)       // 011 111 10

        this[0x81] = Instruction(STA, INDEXED_INDIRECT) // 100 000 01
        this[0x84] = Instruction(STY, ZERO_PAGE)        // 100 001 00
        this[0x85] = Instruction(STA, ZERO_PAGE)        // 100 001 01
        this[0x86] = Instruction(STX, ZERO_PAGE)        // 100 001 10
        this[0x88] = Instruction(DEY, IMPLIED)          // 100 010 00
        this[0x8A] = Instruction(TXA, IMPLIED)          // 100 010 10
        this[0x8C] = Instruction(STY, ABSOLUTE)         // 100 011 00
        this[0x8D] = Instruction(STA, ABSOLUTE)         // 100 011 01
        this[0x8E] = Instruction(STX, ABSOLUTE)         // 100 011 10

        this[0x90] = Instruction(BCC, RELATIVE)         // 100 100 00
        this[0x91] = Instruction(STA, INDIRECT_INDEXED) // 100 100 01
        this[0x94] = Instruction(STY, ZERO_PAGE_X)      // 100 101 00
        this[0x95] = Instruction(STA, ZERO_PAGE_X)      // 100 101 01
        this[0x96] = Instruction(STX, ZERO_PAGE_Y)      // 100 101 10
        this[0x98] = Instruction(TYA, IMPLIED)          // 100 110 00
        this[0x99] = Instruction(STA, ABSOLUTE_Y)       // 100 110 01
        this[0x9A] = Instruction(TXS, IMPLIED)          // 100 110 10
        this[0x9D] = Instruction(STA, ABSOLUTE_X)       // 100 111 01

        this[0xA0] = Instruction(LDY, IMMEDIATE)        // 101 000 00
        this[0xA1] = Instruction(LDA, INDEXED_INDIRECT) // 101 000 01
        this[0xA2] = Instruction(LDX, IMMEDIATE)        // 101 000 10
        this[0xA4] = Instruction(LDY, ZERO_PAGE)        // 101 001 00
        this[0xA5] = Instruction(LDA, ZERO_PAGE)        // 101 001 01
        this[0xA6] = Instruction(LDX, ZERO_PAGE)        // 101 001 10
        this[0xA8] = Instruction(TAY, IMPLIED)          // 101 010 00
        this[0xA9] = Instruction(LDA, IMMEDIATE)        // 101 010 01
        this[0xAA] = Instruction(TAX, IMPLIED)          // 101 010 10
        this[0xAC] = Instruction(LDY, ABSOLUTE)         // 101 011 00
        this[0xAD] = Instruction(LDA, ABSOLUTE)         // 101 011 01
        this[0xAE] = Instruction(LDX, ABSOLUTE)         // 101 011 10

        this[0xB0] = Instruction(BCS, RELATIVE)         // 101 100 00
        this[0xB1] = Instruction(LDA, INDIRECT_INDEXED) // 101 100 01
        this[0xB4] = Instruction(LDY, ZERO_PAGE_X)      // 101 101 00
        this[0xB5] = Instruction(LDA, ZERO_PAGE_X)      // 101 101 01
        this[0xB6] = Instruction(LDX, ZERO_PAGE_Y)      // 101 101 10
        this[0xB8] = Instruction(CLV, IMPLIED)          // 101 110 00
        this[0xB9] = Instruction(LDA, ABSOLUTE_Y)       // 101 110 01
        this[0xBA] = Instruction(TSX, IMPLIED)          // 101 110 10
        this[0xBC] = Instruction(LDY, ABSOLUTE_X)       // 101 111 00
        this[0xBD] = Instruction(LDA, ABSOLUTE_X)       // 101 111 01
        this[0xBE] = Instruction(LDX, ABSOLUTE_Y)       // 101 111 10

        this[0xC0] = Instruction(CPY, IMMEDIATE)        // 110 000 00
        this[0xC1] = Instruction(CMP, INDEXED_INDIRECT) // 110 000 01
        this[0xC4] = Instruction(CPY, ZERO_PAGE)        // 110 001 00
        this[0xC5] = Instruction(CMP, ZERO_PAGE)        // 110 001 01
        this[0xC6] = Instruction(DEC, ZERO_PAGE)        // 110 001 10
        this[0xC8] = Instruction(INY, IMPLIED)          // 110 010 00
        this[0xC9] = Instruction(CMP, IMMEDIATE)        // 110 010 01
        this[0xCA] = Instruction(DEX, IMPLIED)          // 110 010 10
        this[0xCC] = Instruction(CPY, ABSOLUTE)         // 110 011 00
        this[0xCD] = Instruction(CMP, ABSOLUTE)         // 110 011 01
        this[0xCE] = Instruction(DEC, ABSOLUTE)         // 110 011 10

        this[0xD0] = Instruction(BNE, RELATIVE)         // 110 100 00
        this[0xD1] = Instruction(CMP, INDIRECT_INDEXED) // 110 100 01
        this[0xD5] = Instruction(CMP, ZERO_PAGE_X)      // 110 101 01
        this[0xD6] = Instruction(DEC, ZERO_PAGE_X)      // 110 101 10
        this[0xD8] = Instruction(CLD, IMPLIED)          // 110 110 00
        this[0xD9] = Instruction(CMP, ABSOLUTE_Y)       // 110 110 01
        this[0xDD] = Instruction(CMP, ABSOLUTE_X)       // 110 111 01
        this[0xDE] = Instruction(DEC, ABSOLUTE_X)       // 110 111 10

        this[0xE0] = Instruction(CPX, IMMEDIATE)        // 111 000 00
        this[0xE1] = Instruction(SBC, INDEXED_INDIRECT) // 111 000 01
        this[0xE4] = Instruction(CPX, ZERO_PAGE)        // 111 001 00
        this[0xE5] = Instruction(SBC, ZERO_PAGE)        // 111 001 01
        this[0xE6] = Instruction(INC, ZERO_PAGE)        // 111 001 10
        this[0xE8] = Instruction(INX, IMPLIED)          // 111 010 00
        this[0xE9] = Instruction(SBC, IMMEDIATE)        // 111 010 01
        this[0xEA] = Instruction(NOP, IMPLIED)          // 111 010 10
        this[0xEC] = Instruction(CPX, ABSOLUTE)         // 111 011 00
        this[0xED] = Instruction(SBC, ABSOLUTE)         // 111 011 01
        this[0xEE] = Instruction(INC, ABSOLUTE)         // 111 011 10

        this[0xF0] = Instruction(BEQ, RELATIVE)         // 111 100 00
        this[0xF1] = Instruction(SBC, INDIRECT_INDEXED) // 111 100 01
        this[0xF5] = Instruction(SBC, ZERO_PAGE_X)      // 111 101 01
        this[0xF6] = Instruction(INC, ZERO_PAGE_X)      // 111 101 10
        this[0xF8] = Instruction(SED, IMPLIED)          // 111 110 00
        this[0xF9] = Instruction(SBC, ABSOLUTE_Y)       // 111 110 01
        this[0xFD] = Instruction(SBC, ABSOLUTE_X)       // 111 111 01
        this[0xFE] = Instruction(INC, ABSOLUTE_X)       // 111 111 10

        addUndocumented()
    }

    /**
     * Undocumented opcodes. Most are a group-01 operation and a group-10
     * operation decoded at the same time (bits 11 select both), which is why
     * SLO is "ASL + ORA", LAX is "LDA + LDX", and so on. Names follow
     * https://www.masswerk.at/6502/6502_instruction_set.html#illegals.
     */
    private fun Array<Instruction?>.addUndocumented() {
        fun set(opcode: Int, operation: Operation, mode: AddressingMode) {
            check(this[opcode] == null) { "Opcode $opcode is already defined" }
            this[opcode] = Instruction(operation, mode, documented = false)
        }

        // Read-modify-write combinations share one opcode pattern per operation.
        val rmwModes = listOf(
            0x03 to INDEXED_INDIRECT, 0x07 to ZERO_PAGE, 0x0F to ABSOLUTE, 0x13 to INDIRECT_INDEXED,
            0x17 to ZERO_PAGE_X, 0x1B to ABSOLUTE_Y, 0x1F to ABSOLUTE_X,
        )
        for ((base, operation) in listOf(0x00 to SLO, 0x20 to RLA, 0x40 to SRE, 0x60 to RRA, 0xC0 to DCP, 0xE0 to ISC)) {
            for ((offset, mode) in rmwModes) set(base + offset, operation, mode)
        }

        set(0x83, SAX, INDEXED_INDIRECT)
        set(0x87, SAX, ZERO_PAGE)
        set(0x8F, SAX, ABSOLUTE)
        set(0x97, SAX, ZERO_PAGE_Y)

        set(0xA3, LAX, INDEXED_INDIRECT)
        set(0xA7, LAX, ZERO_PAGE)
        set(0xAF, LAX, ABSOLUTE)
        set(0xB3, LAX, INDIRECT_INDEXED)
        set(0xB7, LAX, ZERO_PAGE_Y)
        set(0xBF, LAX, ABSOLUTE_Y)

        set(0x0B, ANC, IMMEDIATE)
        set(0x2B, ANC, IMMEDIATE)
        set(0x4B, ALR, IMMEDIATE)
        set(0x6B, ARR, IMMEDIATE)
        set(0x8B, ANE, IMMEDIATE)
        set(0xAB, LXA, IMMEDIATE)
        set(0xCB, SBX, IMMEDIATE)
        set(0xEB, SBC, IMMEDIATE) // Identical to documented $E9.

        set(0x93, SHA, INDIRECT_INDEXED)
        set(0x9F, SHA, ABSOLUTE_Y)
        set(0x9E, SHX, ABSOLUTE_Y)
        set(0x9C, SHY, ABSOLUTE_X)
        set(0x9B, TAS, ABSOLUTE_Y)
        set(0xBB, LAS, ABSOLUTE_Y)

        // NOPs that still perform their addressing mode's reads.
        for (opcode in intArrayOf(0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA)) set(opcode, NOP, IMPLIED)
        for (opcode in intArrayOf(0x80, 0x82, 0x89, 0xC2, 0xE2)) set(opcode, NOP, IMMEDIATE)
        for (opcode in intArrayOf(0x04, 0x44, 0x64)) set(opcode, NOP, ZERO_PAGE)
        for (opcode in intArrayOf(0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4)) set(opcode, NOP, ZERO_PAGE_X)
        set(0x0C, NOP, ABSOLUTE)
        for (opcode in intArrayOf(0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC)) set(opcode, NOP, ABSOLUTE_X)

        for (opcode in intArrayOf(0x02, 0x12, 0x22, 0x32, 0x42, 0x52, 0x62, 0x72, 0x92, 0xB2, 0xD2, 0xF2)) {
            set(opcode, JAM, IMPLIED)
        }
    }
}
