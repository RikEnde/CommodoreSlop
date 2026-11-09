// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.C64Machine
import c64slop.c64.Disk
import c64slop.c64.Typist
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 1541 with real D64 files. Skips without the ROMs or the shipped disk. */
class DiskTest {
    private val trex = File("../disks/trex64.d64")

    private fun C64Machine.frames(count: Int, typist: Typist? = null) = repeat(count) {
        typist?.onFrame(this)
        Emulator.runFrame(this)
    }

    private fun C64Machine.type(text: String) {
        val typist = Typist()
        typist.enqueue(text)
        while (typist.busy) frames(1, typist)
    }

    private fun C64Machine.waitFor(text: String, seconds: Int, after: String) {
        repeat(seconds * 50) {
            val screen = screenLines()
            val from = screen.indexOfLast { it.contains(after) } + 1
            if (from > 0 && screen.drop(from).any { it.contains(text) }) return
            frames(1)
        }
        error("No \"$text\" after \"$after\" in $seconds s:\n" + screenLines().joinToString("\n"))
    }

    /** A file's bytes, following its chain of sectors from the directory: the way the DOS reads it. */
    private fun fileFromD64(d64: ByteArray, index: Int): ByteArray {
        fun byte(track: Int, sector: Int, offset: Int) = d64[Disk.sectorOffset(track, sector) + offset].toInt() and 0xFF
        val entry = 2 + 32 * index
        var track = byte(18, 1, entry + 1)
        var sector = byte(18, 1, entry + 2)
        val out = java.io.ByteArrayOutputStream()
        while (track != 0) {
            val nextTrack = byte(track, sector, 0)
            val nextSector = byte(track, sector, 1)
            val last = if (nextTrack == 0) nextSector else 255
            for (i in 2..last) out.write(byte(track, sector, i))
            track = nextTrack
            sector = nextSector
        }
        return out.toByteArray()
    }

    @Test
    fun loadsAGameFromAD64() {
        val roms = testRoms()?.takeIf { it.dos != null } ?: return
        if (!trex.isFile) return println("Skipping: no ${trex.absolutePath}")
        val machine = roms.newMachine()
        machine.drive!!.disk = DiskFile.load(trex)
        machine.frames(150)
        machine.type("LOAD \"$\",8\n")
        machine.waitFor("READY.", 30, after = "LOAD \"$\"")
        machine.type("LIST\n")
        machine.waitFor("BLOCKS FREE.", 10, after = "LIST")
        val directory = machine.screenLines().joinToString("\n")
        assertTrue(directory.contains("PRG"), directory)

        machine.type("LOAD \"*\",8\n")
        machine.waitFor("READY.", 120, after = "LOAD \"*\"")
        val file = fileFromD64(trex.readBytes(), 0)
        val address = (file[0].toInt() and 0xFF) or ((file[1].toInt() and 0xFF) shl 8)
        assertEquals(0x0801, address)
        for (i in 2 until file.size) {
            assertEquals(file[i], machine.ram[address + i - 2], "byte at $%04X".format(address + i - 2))
        }
        machine.type("RUN\n")
        machine.frames(250)
        // The title screen's VIC-II setup: border and background black.
        assertEquals(0, machine.vic.colors[0] and 0x0F, "border")
    }

    @Test
    fun savesChangesBackToTheFileWhenTheMotorStops() {
        val roms = testRoms()?.takeIf { it.dos != null } ?: return
        val file = File("build/test-disks/save.d64").apply {
            parentFile.mkdirs()
            writeBytes(Disk.blank("SAVE TEST", "ST").toD64())
        }
        val motorOff = CountDownLatch(2) // once after power-on's reset, once after SAVE
        val emulator = Emulator(
            createMachine = roms::newMachine,
            soundEnabled = false,
            listener = object : Emulator.Listener {
                override fun frameReady(frameCount: Long) {}
                override fun stopped(error: Throwable) = throw AssertionError(error)
                override fun diskMotorChanged(on: Boolean) { if (!on) motorOff.countDown() }
            },
        )
        emulator.fastTape = true
        emulator.insertDisk(DiskFile.load(file), file)
        emulator.type("10 PRINT \"SAVED\"\nSAVE \"PROGRAM\",8\n", delayFrames = BOOT_FRAMES)
        emulator.start()
        try {
            assertTrue(motorOff.await(60, TimeUnit.SECONDS), "the drive stops after saving")
            val saved = CompletableFuture<Boolean>()
            emulator.post { saved.complete(true) }
            saved.get(5, TimeUnit.SECONDS)
        } finally {
            emulator.stop()
        }
        val d64 = file.readBytes()
        val entry = Disk.sectorOffset(18, 1) + 5
        assertEquals("PROGRAM", (0 until 7).map { d64[entry + it].toInt().toChar() }.joinToString(""))
    }
}
