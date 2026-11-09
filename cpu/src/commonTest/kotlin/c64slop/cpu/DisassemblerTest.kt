// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisassemblerTest {
    private val memory = IntArray(0x10000)

    private fun at(address: Int, vararg bytes: Int): Disassembler {
        bytes.forEachIndexed { i, byte -> memory[(address + i) and 0xFFFF] = byte }
        return Disassembler({ memory[it] })
    }

    private fun text(vararg bytes: Int): String = at(0xC000, *bytes).instruction(0xC000).text

    @Test
    fun everyAddressingMode() {
        assertEquals("RTS", text(0x60))
        assertEquals("ASL A", text(0x0A))
        assertEquals("LDA #\$41", text(0xA9, 0x41))
        assertEquals("LDA \$FB", text(0xA5, 0xFB))
        assertEquals("LDA \$FB,X", text(0xB5, 0xFB))
        assertEquals("LDX \$FB,Y", text(0xB6, 0xFB))
        assertEquals("STA \$D020", text(0x8D, 0x20, 0xD0))
        assertEquals("LDA \$0400,X", text(0xBD, 0x00, 0x04))
        assertEquals("LDA \$0400,Y", text(0xB9, 0x00, 0x04))
        assertEquals("JMP (\$0314)", text(0x6C, 0x14, 0x03))
        assertEquals("LDA (\$FB,X)", text(0xA1, 0xFB))
        assertEquals("STA (\$FB),Y", text(0x91, 0xFB))
    }

    @Test
    fun absoluteKeepsFourDigitsInZeroPage() {
        // LDA $0012 is $AD, three bytes; LDA $12 is $A5, two. The listing must tell them apart.
        assertEquals("LDA \$0012", text(0xAD, 0x12, 0x00))
        assertEquals("LDA \$12", text(0xA5, 0x12))
    }

    @Test
    fun branchesShowTheirDestination() {
        assertEquals("BNE \$C000", text(0xD0, 0xFE))
        assertEquals("BEQ \$C012", text(0xF0, 0x10))
        assertEquals(0xC012, at(0xC000, 0xF0, 0x10).instruction(0xC000).target)
        // Forwards from the top of memory wraps to the bottom.
        assertEquals("BCC \$0001", at(0xFFFE, 0x90, 0x01).instruction(0xFFFE).text)
    }

    @Test
    fun undocumentedOpcodesAreMarked() {
        val lax = at(0xC000, 0xA7, 0x10).instruction(0xC000)
        assertEquals("LAX \$10", lax.text)
        assertFalse(lax.documented)
        assertEquals("C000  A7 10    *LAX \$10", lax.toString())
        assertEquals("JAM", text(0x02))
        assertEquals("NOP \$1234,X", text(0x1C, 0x34, 0x12))
    }

    @Test
    fun listsARange() {
        // LDX #0 / loop: LDA $0400,X / STA $0500,X / INX / BNE loop / RTS
        val disassembler = at(0x1000, 0xA2, 0x00, 0xBD, 0x00, 0x04, 0x9D, 0x00, 0x05, 0xE8, 0xD0, 0xF7, 0x60)
        assertEquals(
            listOf(
                "1000  A2 00     LDX #\$00",
                "1002  BD 00 04  LDA \$0400,X",
                "1005  9D 00 05  STA \$0500,X",
                "1008  E8        INX",
                "1009  D0 F7     BNE \$1002",
                "100B  60        RTS",
            ).joinToString("\n"),
            disassembler.listing(0x1000, 0x100B),
        )
        assertEquals(listOf(0x1000, 0x1002, 0x1005), disassembler.instructions(0x1000, 3).map { it.address })
    }

    @Test
    fun originNumbersCodeWhereItWillRun() {
        // A loop stored at $7C66 that a decompressor copies to $014E before running it.
        val disassembler = at(0x7C66, 0xBD, 0x08, 0x7B, 0x9D, 0x1C, 0x07, 0xE8, 0xD0, 0xF7, 0x20, 0x26, 0x01)
        assertEquals(
            listOf(
                "014E  BD 08 7B  LDA \$7B08,X",
                "0151  9D 1C 07  STA \$071C,X",
                "0154  E8        INX",
                "0155  D0 F7     BNE \$014E",
                "0157  20 26 01  JSR \$0126",
            ).joinToString("\n"),
            disassembler.listing(0x7C66, 0x7C71, origin = 0x014E),
        )
        assertEquals(
            listOf(0x014E, 0x0151, 0x0154),
            disassembler.instructions(0x7C66, 3, origin = 0x014E).map { it.address },
        )
        val branch = disassembler.instruction(0x7C6D, origin = 0x0155)
        assertEquals(0x014E, branch.target)
        assertEquals(0x0157, branch.next)
    }

    @Test
    fun labelsNameAddresses() {
        at(0xC000, 0x20, 0xD2, 0xFF)
        val names = mapOf(0xFFD2 to "CHROUT")
        val disassembler = Disassembler({ memory[it] }, { names[it] })
        val jsr = disassembler.instruction(0xC000)
        assertEquals("JSR CHROUT", jsr.text)
        assertEquals(0xFFD2, jsr.target)
        assertNull(disassembler.instruction(0xC003).target, "BRK names no address")
    }

    @Test
    fun decodesEveryOpcode() {
        val disassembler = Disassembler({ memory[it] })
        var documented = 0
        for (opcode in 0..255) {
            memory[0] = opcode
            val instruction = disassembler.instruction(0)
            assertTrue(instruction.length in 1..3, "opcode $opcode")
            assertEquals(opcode, instruction.bytes[0])
            if (instruction.documented) documented++
        }
        assertEquals(151, documented)
    }

    @Test
    fun readsOnlyWhatItNeeds() {
        val reads = mutableListOf<Int>()
        memory[0x2000] = 0xEA
        Disassembler({ reads += it; memory[it] }).instruction(0x2000)
        assertEquals(listOf(0x2000), reads, "a one-byte NOP reads only its own address")
    }
}
