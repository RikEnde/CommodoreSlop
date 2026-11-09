// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The KERNAL talks to a 1541 running its real DOS ROM over the serial bus.
 * Skips without the ROMs.
 */
class DiskDriveRomTest {
    /** Wait for a row containing [text] below the last row containing [after]. */
    private fun C64Machine.waitFor(text: String, seconds: Int, after: String) {
        repeat(seconds * 50) {
            val screen = screenLines()
            val from = screen.indexOfLast { it.contains(after) } + 1
            if (from > 0 && screen.drop(from).any { it.contains(text) }) return
            frames(1)
        }
        error("No \"$text\" after \"$after\" in $seconds s:\n" + screenLines().joinToString("\n"))
    }

    private fun bootedWithDrive(disk: Disk): C64Machine? {
        val machine = newMachine(drive = true) ?: return null
        machine.drive!!.disk = disk
        machine.runForSeconds(3.0)
        return machine
    }

    @Test
    fun theDriveAnswersOnTheBus() {
        val machine = bootedWithDrive(Disk.blank("EMPTY", "AB")) ?: return
        // The drive's own DOS reports its version after power-on.
        // INPUT# only works in a program.
        machine.type("10 OPEN 15,8,15:INPUT#15,A,B$,C,D:CLOSE 15:PRINT A;B$\nRUN\n")
        machine.waitFor("READY.", 10, after = "RUN")
        val screen = machine.screenLines()
        assertTrue(screen.any { it.contains("73 CBM DOS V2.6 1541") }, screen.joinToString("\n"))
    }

    @Test
    fun savesListsAndLoadsAProgram() {
        val disk = Disk.blank("TEST DISK", "AB")
        val machine = bootedWithDrive(disk) ?: return
        machine.type("10 PRINT \"SAVED ON DISK\"\n")
        machine.type("SAVE \"HELLO\",8\n")
        machine.waitFor("READY.", 60, after = "SAVE \"HELLO\"")

        machine.type("NEW\n")
        machine.type("LOAD \"$\",8\n")
        machine.waitFor("READY.", 60, after = "LOAD \"$\"")
        machine.type("LIST\n")
        machine.waitFor("READY.", 10, after = "LIST")
        val directory = machine.screenLines().joinToString("\n")
        assertTrue(directory.contains("0 \"TEST DISK       \" AB 2A"), directory)
        assertTrue(directory.contains("1    \"HELLO\"            PRG"), directory)
        assertTrue(directory.contains("663 BLOCKS FREE."), directory)

        machine.type("NEW\n")
        machine.type("LOAD \"HELLO\",8\n")
        machine.waitFor("READY.", 60, after = "LOAD \"HELLO\"")
        machine.type("RUN\n")
        machine.waitFor("SAVED ON DISK", 5, after = "RUN")

        // The file reached the D64 image, through the GCR the DOS wrote.
        val d64 = disk.toD64()
        val entry = Disk.sectorOffset(18, 1)
        assertEquals(0x82, d64[entry + 2].toInt() and 0xFF, "a closed PRG file")
        assertEquals("HELLO", (0 until 5).map { d64[entry + 5 + it].toInt().toChar() }.joinToString(""))
    }

    @Test
    fun aD64RoundTripsThroughGcr() {
        val disk = Disk.blank("ROUND TRIP", "XY")
        val image = disk.toD64()
        assertEquals(Disk.SECTORS_35 * Disk.SECTOR_SIZE, image.size)
        val again = Disk.parseD64(image)
        assertTrue(image.contentEquals(again.toD64()))
        assertEquals("ROUND TRIP", again.name)
    }
}
