// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

val romDirectory = File(System.getProperty("c64slop.romDirectory") ?: "roms")

/** A fresh machine with the real ROMs, or null (and a message) if they are missing. */
fun newMachine(): C64Machine? {
    val kernal = File(romDirectory, "kernal.rom")
    val basic = File(romDirectory, "basic.rom")
    val chargen = File(romDirectory, "chargen.rom")
    if (!kernal.isFile || !basic.isFile || !chargen.isFile) {
        println("Skipping: C64 ROMs not found in ${romDirectory.absolutePath}")
        return null
    }
    return C64Machine(kernal.readBytes(), basic.readBytes(), chargen.readBytes())
}

/** Run [block] on a machine that has booted to `READY.`; skips without the ROMs. */
fun withBootedMachine(block: (C64Machine) -> Unit) {
    val machine = newMachine() ?: return
    machine.runForSeconds(3.0)
    block(machine)
}

const val FRAME_CYCLES = C64Clock.CYCLES_PER_FRAME.toLong()

fun C64Machine.frames(count: Int) = runForCycles(count * FRAME_CYCLES)

/** Type [text] through the keyboard matrix and wait until it has all gone in. */
fun C64Machine.type(text: String) {
    val typist = Typist()
    typist.enqueue(text)
    var frames = 0
    while (typist.busy) {
        typist.onFrame(this)
        frames(1)
        check(++frames < 20_000) { "typing did not finish" }
    }
    frames(10)
}

/** Writes the current picture as a PNG. */
fun C64Machine.savePicture(file: File) {
    val image = BufferedImage(Vic6569.WIDTH, Vic6569.HEIGHT, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, Vic6569.WIDTH, Vic6569.HEIGHT, vic.pixels, 0, Vic6569.WIDTH)
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
}

fun C64Machine.pixel(x: Int, y: Int): Int = vic.pixels[y * Vic6569.WIDTH + x]
