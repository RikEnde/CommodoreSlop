// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saves BASIC programs to tape and loads them back through the real KERNAL's
 * cassette routines. Skips without the ROMs.
 */
class CassetteRomTest {
    /** Wait for a row containing [text], below the last row containing [after] if given. */
    private fun C64Machine.waitFor(text: String, seconds: Int, after: String? = null) {
        repeat(seconds * 50) {
            val screen = screenLines()
            val from = if (after == null) 0 else screen.indexOfLast { it.contains(after) } + 1
            if (from >= 0 && screen.drop(from).any { it.contains(text) } && (after == null || from > 0)) return
            frames(1)
        }
        error("No \"$text\" after $seconds s:\n" + screenLines().joinToString("\n"))
    }

    private fun saveProgram(): Tape? {
        var tape: Tape? = null
        withBootedMachine { machine ->
            machine.type("10 PRINT \"SAVED ON TAPE\"\n20 GOTO 10\n")
            machine.type("SAVE \"HELLO\"\n")
            machine.waitFor("PRESS RECORD & PLAY ON TAPE", 2)
            val blank = Tape.blank()
            machine.datasette.tape = blank
            machine.datasette.recordDown = true
            machine.waitFor("SAVING HELLO", 5)
            assertTrue(machine.datasette.motorRunning, "the motor runs while saving")
            // The KERNAL writes a leader, the header twice, then the data twice.
            machine.waitFor("READY.", 120, after = "SAVING HELLO")
            assertTrue(!machine.datasette.motorRunning, "and stops afterwards")
            machine.datasette.recordDown = false
            tape = blank
        }
        return tape
    }

    private fun loadProgram(tape: Tape): C64Machine? {
        var loaded: C64Machine? = null
        withBootedMachine { machine ->
            tape.rewind()
            machine.datasette.tape = tape
            machine.type("LOAD\n")
            machine.waitFor("PRESS PLAY ON TAPE", 2)
            machine.datasette.playDown = true
            machine.waitFor("FOUND HELLO", 60)
            // The KERNAL waits for the Commodore key, or a few seconds.
            machine.waitFor("LOADING", 20)
            machine.waitFor("READY.", 120, after = "LOADING")
            loaded = machine
        }
        return loaded
    }

    @Test
    fun savesAndLoadsAProgram() {
        val tape = saveProgram() ?: return
        assertTrue(tape.length > 10_000, "${tape.length} pulses recorded")
        // Through a TAP image and back.
        val copy = Tape.parse(tape.toTap())
        assertEquals(tape.length, copy.length)
        val machine = loadProgram(copy) ?: return
        machine.type("LIST\n")
        val screen = machine.screenLines()
        val text = screen.joinToString("\n")
        assertTrue(screen.contains("10 PRINT \"SAVED ON TAPE\""), text)
        assertTrue(screen.contains("20 GOTO 10"), text)
    }
}
