// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.C64Machine
import c64slop.c64.Program
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** The real-time emulator thread, without a window or sound. Skips without the ROMs. */
class EmulatorTest {
    /** `10 PRINT "OK";:GOTO 10`, tokenised, at `$0801`. */
    private val loop = Program(
        Program.BASIC_START,
        byteArrayOf(
            0x12, 0x08, 0x0A, 0x00, 0x99.toByte(), 0x20, 0x22, 0x4F, 0x4B, 0x22, 0x3B, 0x3A, 0x89.toByte(),
            0x20, 0x31, 0x30, 0x00, 0x00, 0x00,
        ),
    )

    private fun Emulator.screen(): List<String> {
        val screen = CompletableFuture<List<String>>()
        post { screen.complete(it.screenLines()) }
        return screen.get(5, TimeUnit.SECONDS)
    }

    /** Polls the screen, in emulated time, until [done] holds. */
    private fun Emulator.waitFor(what: String, seconds: Int, done: (List<String>) -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (done(screen())) return
            Thread.sleep(50)
        }
        fail("No $what after $seconds s:\n" + screen().joinToString("\n"))
    }

    @Test
    fun loadsAndRunsAProgramThenStopsItWithRunStopRestore() {
        val roms = testRoms() ?: return
        val emulator = Emulator(
            createMachine = roms::newMachine,
            soundEnabled = false,
            listener = object : Emulator.Listener {
                override fun frameReady(frameCount: Long) {}
                override fun stopped(error: Throwable) = throw AssertionError(error)
            },
        )
        emulator.loadProgram(loop, run = true, delayFrames = BOOT_FRAMES)
        emulator.start()
        try {
            emulator.waitFor("program output", 15) { screen -> screen.count { it.contains("OKOKOK") } > 5 }
            emulator.runStopRestore()
            // The KERNAL's NMI handler clears the screen and BASIC prints READY.
            emulator.waitFor("READY. after RUN/STOP + RESTORE", 5) { screen ->
                screen.none { it.contains("OKOK") } && screen.any { it == "READY." }
            }
        } finally {
            emulator.stop()
        }
    }

    @Test
    fun aQuickTapStillReachesTheC64() {
        val roms = testRoms() ?: return
        val emulator = Emulator(
            createMachine = roms::newMachine,
            soundEnabled = false,
            listener = object : Emulator.Listener {
                override fun frameReady(frameCount: Long) {}
                override fun stopped(error: Throwable) = throw AssertionError(error)
            },
        )
        emulator.start()
        try {
            emulator.waitFor("READY.", 10) { screen -> screen.any { it == "READY." } }
            // The press and release reach the emulation thread together, between two
            // frames, as a quick tap can when the thread runs a burst of frames.
            for (key in listOf(c64slop.c64.KeyboardMatrix.Key.H, c64slop.c64.KeyboardMatrix.Key.I)) {
                emulator.press(listOf(key))
                emulator.release(listOf(key))
                Thread.sleep(200)
            }
            emulator.waitFor("HI typed", 5) { screen -> screen.any { it == "HI" } }
        } finally {
            emulator.stop()
        }
    }

    @Test
    fun runFrameRunsOneWholeFrame() {
        val machine: C64Machine = testRoms()?.newMachine() ?: return
        Emulator.runFrame(machine)
        val elapsed = Emulator.runFrame(machine)
        // An instruction can overrun the frame by a few cycles; the next one is that much shorter.
        assertTrue(elapsed in 19_656L - 10..19_656L + 10, "$elapsed cycles")
    }
}
