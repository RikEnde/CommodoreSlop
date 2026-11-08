// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Draws pictures with the real ROMs and checks their pixels. Each test also
 * writes a PNG to build/test-frames. The tests skip, with a message, if the
 * ROMs are missing.
 */
class VideoRomTest {
    private val frames = File("build/test-frames")
    private val palette = Vic6569.PALETTE

    // The 40-column display window starts at X coordinate 24 (picture column
    // 124) and the 25-row one at raster line 51.
    private fun textX(column: Int) = 124 + column * 8
    private fun textY(row: Int) = 51 + row * 8

    /** Pixels of the character cell at [column], [row]. */
    private fun C64Machine.cell(column: Int, row: Int) =
        (0 until 8).flatMap { y -> (0 until 8).map { x -> pixel(textX(column) + x, textY(row) + y) } }

    @Test
    fun bootScreenIsLightBlueOnBlue() = withBootedMachine { machine ->
        machine.savePicture(File(frames, "boot.png"))
        assertEquals(palette[14], machine.pixel(100, 150), "light blue border")
        assertEquals(palette[6], machine.pixel(textX(20), textY(12)), "blue background")
        // "READY." on row 5: the R has light blue text pixels.
        val r = machine.cell(0, 5)
        assertTrue(r.count { it == palette[14] } in 10..40, "R drawn")
        assertEquals(setOf(palette[6], palette[14]), r.toSet())
        assertTrue(machine.cell(20, 12).all { it == palette[6] }, "empty cell")
    }

    @Test
    fun borderAndBackgroundRegistersAndColorRam() = withBootedMachine { machine ->
        machine.type("POKE 53280,0:POKE 53281,1:POKE 55296,2:POKE 1024,160\n")
        machine.frames(2)
        machine.savePicture(File(frames, "colours.png"))
        assertEquals(palette[0], machine.pixel(100, 150), "black border")
        assertEquals(palette[1], machine.pixel(textX(20), textY(12)), "white background")
        // Screen code 160 is a reverse space: the whole cell takes Color RAM's red.
        assertTrue(machine.cell(0, 0).all { it == palette[2] }, "red block at the top left")
    }

    @Test
    fun spritesShowWhereTheirCoordinatesSay() = withBootedMachine { machine ->
        // Sprite 0: a solid block from $0340 (pointer 13), at X 24, Y 50: the
        // top left corner of the display window. Sprite 1, X-expanded, at X 100.
        machine.type(
            "FOR I=0 TO 62:POKE 832+I,255:NEXT\n" +
                "POKE 2040,13:POKE 2041,13:POKE 53248,24:POKE 53249,50\n" +
                "POKE 53250,100:POKE 53251,100:POKE 53287,7:POKE 53288,5\n" +
                "POKE 53277,2:POKE 53269,3\n",
        )
        machine.frames(2)
        machine.savePicture(File(frames, "sprites.png"))
        assertEquals(palette[7], machine.pixel(124, 51), "sprite 0's top left at the display window's")
        assertEquals(palette[7], machine.pixel(124 + 23, 51 + 20), "24 by 21 pixels")
        assertTrue(machine.pixel(124 + 24, 51 + 10) != palette[7], "and no wider")
        assertTrue(machine.pixel(124, 51 + 21) != palette[7], "or taller")
        val y = 101 + 10
        assertEquals(palette[5], machine.pixel(200, y), "sprite 1 starts at X 100")
        assertEquals(palette[5], machine.pixel(200 + 47, y), "X-expanded to 48 pixels")
        assertTrue(machine.pixel(200 + 48, y) != palette[5])
        assertTrue(machine.pixel(199, y) != palette[5])
    }

    @Test
    fun spriteCollisionsAreRecorded() = withBootedMachine { machine ->
        machine.type(
            "FOR I=0 TO 62:POKE 832+I,255:NEXT:POKE 2040,13:POKE 2041,13\n" +
                "POKE 53248,100:POKE 53249,100:POKE 53250,110:POKE 53251,105\n" +
                "POKE 53269,3\n",
        )
        machine.frames(2)
        assertEquals(3, machine.bus.read(0xD01E) and 3, "sprites 0 and 1 overlap")
        assertEquals(0, machine.bus.read(0xD01E), "reading clears it")
    }

    @Test
    fun hiresBitmapMode() = withBootedMachine { machine ->
        // Bitmap at $2000, colours from the screen at $0400: white on black for
        // the first cell, and a pattern of alternate lines in its bitmap.
        machine.type(
            "FOR I=8192 TO 8199 STEP 2:POKE I,255:POKE I+1,0:NEXT:POKE 1024,16\n" +
                "POKE 53272,24:POKE 53265,59\n",
        )
        machine.frames(2)
        machine.savePicture(File(frames, "bitmap.png"))
        val cell = (0 until 8).map { y -> machine.pixel(textX(0) + 3, textY(0) + y) }
        assertEquals(List(4) { listOf(palette[1], palette[0]) }.flatten(), cell)
    }

    @Test
    fun badLinesTakeFortyCyclesFromTheCpu() {
        val machine = newMachine() ?: return
        machine.runForSeconds(3.0)
        // With the display on, each frame has 25 bad lines of 40-43 stolen cycles.
        val frameStart = machine.vic.frames
        while (machine.vic.frames == frameStart) machine.step()
        val cpuBefore = machine.cpu.totalCycles
        val clockBefore = machine.clock.cycles
        while (machine.vic.frames == frameStart + 1) machine.step()
        val stolen = (machine.clock.cycles - clockBefore) - (machine.cpu.totalCycles - cpuBefore)
        assertTrue(stolen in 25 * 40L..25 * 43L + 20, "stolen $stolen cycles")
    }
}
