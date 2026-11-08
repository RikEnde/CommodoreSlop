// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.C64Machine
import c64slop.c64.Typist
import c64slop.c64.Vic6569
import c64slop.c64.load
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Frames to wait after power-on before typing: the KERNAL needs about 2.5 s to test RAM and show READY. */
const val BOOT_FRAMES = 150

const val USAGE = """Usage: c64slop [options]
  --roms DIR          Directory with kernal.rom, basic.rom and chargen.rom (default: built in)
  --scale 1|2|3       Window size; 1 is 384 x 272 (default: 2)
  --screenshot FILE   Run without a window, save a PNG of the picture, then exit
  --frames N          50ths of a second to run before the screenshot (default: 200)
  --tape FILE         Put a TAP cassette image in the datasette
  --prg FILE          Load a PRG program after boot, and RUN it if it is BASIC
  --type TEXT         Type TEXT after boot; \n starts a new line
  --joystick 1|2      Arrow keys and Space work a joystick in port 1 or 2
  --no-sound          Run without sound
  --log-keys          Print each host key event and the C64 key it maps to"""

data class Options(
    /** Null means the ROMs built into the jar. */
    val romDirectory: File? = null,
    val scale: Int = 2,
    val screenshot: File? = null,
    val screenshotFrames: Int = 200,
    val logKeys: Boolean = false,
    val sound: Boolean = true,
    val tape: File? = null,
    val program: File? = null,
    val typeText: String? = null,
    /** 0 for none, else the port the arrow keys work. */
    val joystickPort: Int = 0,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var options = Options()
            var i = 0
            fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("${args[i - 1]} needs a value")
            while (i < args.size) {
                options = when (val arg = args[i]) {
                    "--roms" -> options.copy(romDirectory = File(value()))
                    "--scale" -> options.copy(scale = value().toIntOrNull()?.takeIf { it in 1..3 }
                        ?: throw IllegalArgumentException("--scale must be 1, 2 or 3"))
                    "--screenshot" -> options.copy(screenshot = File(value()))
                    "--frames" -> options.copy(screenshotFrames = value().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--frames must be a positive number"))
                    "--log-keys" -> options.copy(logKeys = true)
                    "--no-sound" -> options.copy(sound = false)
                    "--tape" -> options.copy(tape = File(value()))
                    "--prg" -> options.copy(program = File(value()))
                    "--type" -> options.copy(typeText = value().replace("\\n", "\n"))
                    "--joystick" -> options.copy(joystickPort = value().toIntOrNull()?.takeIf { it in 1..2 }
                        ?: throw IllegalArgumentException("--joystick must be 1 or 2"))
                    "--help", "-h" -> {
                        println(USAGE)
                        exitProcess(0)
                    }
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            return options
        }
    }
}

/**
 * The ROMs (© Commodore): the KERNAL, BASIC V2 and the character generator.
 * The build copies roms/kernal.rom, roms/basic.rom and roms/chargen.rom into
 * the jar, so it runs from anywhere; --roms DIR reads them from a directory
 * instead.
 */
class Roms(val kernal: ByteArray, val basic: ByteArray, val chargen: ByteArray) {
    fun newMachine() = C64Machine(kernal, basic, chargen)

    companion object {
        private const val RESOURCES = "/c64slop/roms/"

        /** Each ROM and its size: 8 KiB for the KERNAL and BASIC, 4 KiB for the characters. */
        private val SIZES = mapOf("kernal.rom" to 0x2000, "basic.rom" to 0x2000, "chargen.rom" to 0x1000)

        /** Loads the ROMs from [directory], or the built-in ones if it is null. */
        fun load(directory: File?): Roms {
            fun read(name: String): ByteArray {
                val (where, bytes) = if (directory == null) {
                    val stream = Roms::class.java.getResourceAsStream(RESOURCES + name)
                        ?: throw IOException("The built-in $name is missing from the jar")
                    "Built-in $name" to stream.use { it.readBytes() }
                } else {
                    val file = File(directory, name)
                    if (!file.isFile) throw IOException("Missing ${file.absolutePath}")
                    file.absolutePath to file.readBytes()
                }
                val size = SIZES.getValue(name)
                if (bytes.size != size) throw IOException("$where must be ${size / 1024} KiB, was ${bytes.size} bytes")
                return bytes
            }
            return Roms(read("kernal.rom"), read("basic.rom"), read("chargen.rom"))
        }
    }
}

/** The directory the running jar is in; in the packaged app, C64slop.app/Contents/app. */
fun jarDirectory(): File? =
    runCatching { File(Roms::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()

/** [name] in the current directory if it is there, else [name] beside the jar, where the packaged app keeps its media. */
private fun mediaDirectory(name: String): File {
    val here = File(name)
    if (here.isDirectory) return here.absoluteFile
    val beside = jarDirectory()?.let { File(it, name) }
    return if (beside?.isDirectory == true) beside else here.absoluteFile
}

/** Where Insert Tape starts browsing. */
fun defaultTapeDirectory(): File = mediaDirectory("tapes")

/** Where Load Program starts browsing. */
fun defaultProgramDirectory(): File = mediaDirectory("programs")

/** Where Save As starts: [directory], unless it is inside the app; then ~/Documents, or home. */
fun saveDirectory(directory: File): File {
    if (!isBundled(directory)) return directory
    val home = File(System.getProperty("user.home"))
    return File(home, "Documents").takeIf { it.isDirectory } ?: home
}

/**
 * True if [file] is inside the jar's directory, which only holds media in the
 * packaged app. Writing there would change the signed bundle (macOS then calls
 * the app damaged), and fails anyway in /Applications.
 */
fun isBundled(file: File): Boolean {
    val app = jarDirectory()?.canonicalFile ?: return false
    return generateSequence(file.canonicalFile) { it.parentFile }.any { it == app }
}

/**
 * Runs [Options.screenshotFrames] frames (50ths of a second) as fast as
 * possible, with the tape, program and text from [options], and writes the
 * picture to [file]. A tape plays only if the text starts it, with PLAY down.
 */
fun saveScreenshot(roms: Roms, options: Options, file: File) {
    val machine = roms.newMachine()
    options.tape?.let {
        machine.datasette.tape = TapeFile.load(it)
        machine.datasette.playDown = true
    }
    val typist = Typist()
    options.program?.let { programFile ->
        val program = ProgramFile.load(programFile)
        typist.enqueueAction(BOOT_FRAMES) { it.load(program) }
        if (program.isBasic) typist.enqueue("RUN\n")
    }
    options.typeText?.let { typist.enqueue(it, delayFrames = BOOT_FRAMES) }
    repeat(options.screenshotFrames) {
        typist.onFrame(machine)
        Emulator.runFrame(machine)
    }
    val view = ScreenPanel.VIEW
    val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, view.width, view.height, machine.vic.pixels, view.y * Vic6569.WIDTH + view.x, Vic6569.WIDTH)
    file.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(image, "png", file)
}

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "C64slop")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }
    val roms = try {
        Roms.load(options.romDirectory)
    } catch (e: IOException) {
        val message = if (options.romDirectory == null) {
            "${e.message}\n\nThis build is incomplete. Pass --roms DIR with the C64 kernal.rom, " +
                "basic.rom and chargen.rom."
        } else {
            "${e.message}\n\nPut the C64 kernal.rom (8 KiB), basic.rom (8 KiB) and chargen.rom (4 KiB) " +
                "in that directory, or leave out --roms to use the built-in ones."
        }
        System.err.println(message)
        if (options.screenshot == null && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "C64slop", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
    options.screenshot?.let {
        try {
            saveScreenshot(roms, options, it)
        } catch (e: IOException) {
            System.err.println("Couldn't load a tape or program: ${e.message}")
            exitProcess(1)
        }
        println("Saved ${it.path}")
        return
    }
    SwingUtilities.invokeLater { EmulatorWindow(roms, options).show() }
}
