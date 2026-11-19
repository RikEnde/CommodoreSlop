// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C64LabelsTest {
    @Test
    fun namesTheKernalAndIo() {
        assertEquals("CHROUT", C64Labels.forAddress(0xFFD2))
        assertEquals("R6510", C64Labels.forAddress(0x0001))
        assertEquals("CINV", C64Labels.forAddress(0x0314))
        assertEquals("VIC", C64Labels.forAddress(0xD000))
        assertEquals("SID", C64Labels.forAddress(0xD400))
        assertEquals("CIA1_PRA", C64Labels.forAddress(0xDC00))
        assertEquals("CIA1_ICR", C64Labels.forAddress(0xDC0D))
        assertEquals("CIA2_CRB", C64Labels.forAddress(0xDD0F))
        // Only the first address of each register is named.
        assertNull(C64Labels.forAddress(0xDC10))
        assertNull(C64Labels.forAddress(0xC000))
    }

    /** Every name in the jump table is a JMP in the real KERNAL, and the indirect ones use the named vectors. */
    @Test
    fun jumpTableMatchesTheKernal() {
        val file = File(romDirectory, "kernal.rom")
        if (!file.isFile) return println("Skipping: no ${file.absolutePath}")
        val kernal = file.readBytes()
        fun byte(address: Int) = kernal[address - 0xE000].toInt() and 0xFF
        val entries = (0xFF81..0xFFF3).filter { C64Labels.forAddress(it) != null }
        assertEquals(39, entries.size)
        for (address in entries) {
            assertTrue(byte(address) == 0x4C || byte(address) == 0x6C, "${C64Labels.forAddress(address)} is a JMP")
        }
        val indirect = mapOf(
            "OPEN" to "IOPEN", "CLOSE" to "ICLOSE", "CHKIN" to "ICHKIN", "CHKOUT" to "ICKOUT",
            "CLRCHN" to "ICLRCH", "CHRIN" to "IBASIN", "CHROUT" to "IBSOUT", "STOP" to "ISTOP",
            "GETIN" to "IGETIN", "CLALL" to "ICLALL",
        )
        for (address in entries) {
            val vector = indirect[C64Labels.forAddress(address)] ?: continue
            assertEquals(0x6C, byte(address), "${C64Labels.forAddress(address)} is JMP (...)")
            val pointer = byte(address + 1) or (byte(address + 2) shl 8)
            assertEquals(vector, C64Labels.forAddress(pointer), "${C64Labels.forAddress(address)} goes through $vector")
        }
    }
}
