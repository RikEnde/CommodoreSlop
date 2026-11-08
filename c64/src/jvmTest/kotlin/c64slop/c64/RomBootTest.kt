// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real KERNAL, BASIC V2 and character ROMs from roms/. The tests skip,
 * with a message, if the images are missing.
 */
class RomBootTest {
    @Test
    fun resetVectorEntersTheKernal() {
        val machine = newMachine() ?: return
        // KERNAL 901227-03's reset vector points at $FCE2.
        assertEquals(0xFCE2, machine.cpu.state().programCounter)
    }

    @Test
    fun bootsToReady() = withBootedMachine { machine ->
        val screen = machine.screenLines()
        val text = screen.joinToString("\n")
        assertEquals("", screen[0], text)
        assertEquals("    **** COMMODORE 64 BASIC V2 ****", screen[1], text)
        assertEquals(" 64K RAM SYSTEM  38911 BASIC BYTES FREE", screen[3], text)
        assertEquals("READY.", screen[5], text)
        assertTrue(screen.drop(6).all { it.isEmpty() }, text)
    }

    @Test
    fun framesArriveAt50Hz() = withBootedMachine { machine ->
        val before = machine.vic.frames
        machine.runForSeconds(1.0)
        assertEquals(50.125, (machine.vic.frames - before).toDouble(), 1.0)
    }

    @Test
    fun theKernalsTimerInterruptRunsTheJiffyClock() = withBootedMachine { machine ->
        // TI counts 60ths of a second at $A0-$A2, from CIA 1's timer A.
        fun jiffies() = (0..2).fold(0) { acc, i -> (acc shl 8) or (machine.ram[0xA0 + i].toInt() and 0xFF) }
        val before = jiffies()
        machine.runForSeconds(1.0)
        assertEquals(60.0, (jiffies() - before).toDouble(), 1.0)
    }

    @Test
    fun typesAndRunsBasic() = withBootedMachine { machine ->
        machine.type("PRINT 6*7\n")
        val screen = machine.screenLines()
        assertEquals("PRINT 6*7", screen[6], screen.joinToString("\n"))
        assertEquals(" 42", screen[7], screen.joinToString("\n"))
        assertEquals("READY.", screen[9], screen.joinToString("\n"))
    }

    @Test
    fun noDriveMeansDeviceNotPresent() = withBootedMachine { machine ->
        machine.type("LOAD \"X\",8\n")
        machine.frames(25)
        val screen = machine.screenLines()
        assertTrue(screen.any { it.contains("?DEVICE NOT PRESENT  ERROR") }, screen.joinToString("\n"))
    }

    @Test
    fun loadedBasicProgramRuns() = withBootedMachine { machine ->
        // 10 PRINT "HI"  (tokenised: next-line link, line number, PRINT token $99)
        val line = byteArrayOf(0x0C, 0x08, 10, 0, 0x99.toByte(), 0x20, 0x22, 0x48, 0x49, 0x22, 0, 0, 0)
        machine.load(Program(Program.BASIC_START, line))
        machine.type("RUN\n")
        val screen = machine.screenLines()
        assertTrue(screen.contains("HI"), screen.joinToString("\n"))
    }
}
