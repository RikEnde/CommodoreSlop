// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.Tape
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaFilesTest {
    private val dir = File("build/test-media")

    @Test
    fun savesTapAndLoadsItBack() {
        val tape = Tape.blank()
        val pulses = (0 until 500).map { if (it % 7 == 0) 512 else 368 } + 100_000
        pulses.forEach(tape::writePulse)
        val file = File(dir, "test.tap")
        TapeFile.save(tape.toTap(), file)
        assertEquals("C64-TAPE-RAW", file.readBytes().copyOf(12).decodeToString())
        val loaded = TapeFile.load(file)
        assertEquals(pulses, (0 until loaded.length).map { loaded[it] })
        assertFailsWith<IOException> { TapeFile.load(File(dir, "bad.tap").apply { writeText("not a tape") }) }
    }

    @Test
    fun shippedTapeIsIntact() {
        val file = File("../tapes/trex64.tap")
        if (!file.isFile) return println("Skipping: no ${file.absolutePath}")
        val tape = TapeFile.load(file)
        assertEquals(857_795, tape.length)
        // KERNAL tapes start with a leader of short pulses, 0x30 units of 8 cycles.
        assertTrue((0 until 1000).all { tape[it] in 0x28 * 8..0x38 * 8 }, "leader")
    }

    @Test
    fun loadsPrgFiles() {
        dir.mkdirs()
        val file = File(dir, "test.prg").apply { writeBytes(byteArrayOf(0x00, 0xC0.toByte(), 0x60)) }
        val program = ProgramFile.load(file)
        assertEquals(0xC000, program.loadAddress)
        assertEquals(1, program.data.size)
        assertFailsWith<IOException> { ProgramFile.load(File(dir, "short.prg").apply { writeBytes(byteArrayOf(1)) }) }
    }

    @Test
    fun screenshotShowsTheBootScreen() {
        val roms = testRoms() ?: return
        val file = File(dir, "boot.png")
        saveScreenshot(roms, Options(screenshotFrames = 150), file)
        val image = ImageIO.read(file)
        assertEquals(384, image.width)
        assertEquals(272, image.height)
        // Light blue border, blue background: palette entries 14 and 6.
        val border = image.getRGB(4, 4) and 0xFFFFFF
        val background = image.getRGB(192, 200) and 0xFFFFFF
        assertTrue(border != background, "border %06X, background %06X".format(border, background))
    }

    @Test
    fun parsesOptions() {
        val options = Options.parse(arrayOf("--scale", "3", "--joystick", "2", "--type", "LIST\\n", "--no-sound"))
        assertEquals(3, options.scale)
        assertEquals(2, options.joystickPort)
        assertEquals("LIST\n", options.typeText)
        assertEquals(false, options.sound)
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--scale", "4")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--joystick")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--disc", "x.d64")) }
        val disk = Options.parse(arrayOf("--disk", "x.d64", "--no-drive"))
        assertEquals("x.d64", disk.disk?.path)
        assertEquals(false, disk.drive)
    }
}
