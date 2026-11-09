// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.C64Machine
import c64slop.c64.Typist
import c64slop.c64.Vic6569
import c64slop.c64.load
import c64slop.cpu.Disassembler
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
  --roms DIR          Directory with kernal.rom, basic.rom, chargen.rom and dos1541.rom (default: built in)
  --scale 1|2|3       Window size; 1 is 384 x 272 (default: 2)
  --screenshot FILE   Run without a window, save a PNG of the picture, then exit
  --disassemble A-B   Run without a window, then print the machine code from hex address
                      A to B, as in --disassemble E5CD-E5FF
  --origin ADDR       Number the listing from hex ADDR, for code that is copied there to run
  --frames N          50ths of a second to run before the screenshot or listing (default: 200)
  --tape FILE         Put a TAP cassette image in the datasette
  --disk FILE         Put a D64 disk image in the 1541, drive 8
  --no-drive          Leave out the 1541, even if dos1541.rom is there
  --prg FILE          Load a PRG program after boot, and RUN it if it is BASIC
  --type TEXT         Type TEXT after boot; \n starts a new line
  --joystick 0|1|2    The port the arrow keys and Space work as a joystick; 0 for none,
                      so the arrows are the cursor keys (default: 2)
  --no-sound          Run without sound
  --log-keys          Print each host key event and the C64 key it maps to"""

data class Options(
    /** Null means the ROMs built into the jar. */
    val romDirectory: File? = null,
    val scale: Int = 2,
    val screenshot: File? = null,
    /** Frames to run before the screenshot or the listing. */
    val screenshotFrames: Int = 200,
    /** Addresses to disassemble after running headless, or null. */
    val disassemble: IntRange? = null,
    /** Where the code in [disassemble] runs from, if it is copied there first; null for where it is. */
    val origin: Int? = null,
    val logKeys: Boolean = false,
    val sound: Boolean = true,
    val tape: File? = null,
    val disk: File? = null,
    val drive: Boolean = true,
    val program: File? = null,
    val typeText: String? = null,
    /**
     * The port the arrow keys and Space work as a joystick, or 0 for none.
     * Port 2 by default, where most games read it.
     */
    val joystickPort: Int = 2,
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
                    "--disassemble" -> options.copy(disassemble = parseRange(value()))
                    "--origin" -> options.copy(origin = address(value())
                        ?: throw IllegalArgumentException("--origin needs a hex address, as in 014E"))
                    "--log-keys" ->options.copy(logKeys = true)
                    "--no-sound" -> options.copy(sound = false)
                    "--tape" -> options.copy(tape = File(value()))
                    "--disk" -> options.copy(disk = File(value()))
                    "--no-drive" -> options.copy(drive = false)
                    "--prg" -> options.copy(program = File(value()))
                    "--type" -> options.copy(typeText = value().replace("\\n", "\n"))
                    "--joystick" -> options.copy(joystickPort = value().toIntOrNull()?.takeIf { it in 0..2 }
                        ?: throw IllegalArgumentException("--joystick must be 0, 1 or 2"))
                    "--help", "-h" -> {
                        println(USAGE)
                        exitProcess(0)
                    }
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            if (options.origin != null && options.disassemble == null) {
                throw IllegalArgumentException("--origin only goes with --disassemble")
            }
            return options
        }

        /** A hex address such as `E5CD`, `$E5CD` or `0xE5CD`, or null. */
        private fun address(text: String): Int? =
            text.trim().removePrefix("$").removePrefix("0x").toIntOrNull(16)?.takeIf { it in 0..0xFFFF }

        /** `E5CD-E5FF`, or `$E5CD-$E5FF`, in hex; a single address gives one byte's worth. */
        private fun parseRange(text: String): IntRange {
            val parts = text.split("-")
            val start = address(parts[0])
            val end = if (parts.size == 2) address(parts[1]) else start.takeIf { parts.size == 1 }
            if (start == null || end == null || end < start) {
                throw IllegalArgumentException("--disassemble needs hex addresses START-END, as in E5CD-E5FF")
            }
            return start..end
        }
    }
}

/**
 * The ROMs (© Commodore): the KERNAL, BASIC V2 and the character generator,
 * and the 1541's DOS, which gives the machine a disk drive if present. The
 * build copies roms/kernal.rom, roms/basic.rom, roms/chargen.rom and
 * roms/dos1541.rom into the jar, so it runs from anywhere; --roms DIR reads
 * them from a directory instead.
 */
class Roms(val kernal: ByteArray, val basic: ByteArray, val chargen: ByteArray, val dos: ByteArray?) {
    fun newMachine() = C64Machine(kernal, basic, chargen, dos)

    companion object {
        const val DOS = "dos1541.rom"
        private const val RESOURCES = "/c64slop/roms/"

        /** Each ROM and its size: 8 KiB for the KERNAL and BASIC, 4 KiB for the characters, 16 KiB for the DOS. */
        private val SIZES = mapOf("kernal.rom" to 0x2000, "basic.rom" to 0x2000, "chargen.rom" to 0x1000, DOS to 0x4000)

        /**
         * Loads the ROMs from [directory], or the built-in ones if it is null.
         * Without [drive], or without dos1541.rom, there is no disk drive.
         */
        fun load(directory: File?, drive: Boolean = true): Roms {
            fun builtIn(name: String) = Roms::class.java.getResourceAsStream(RESOURCES + name)
            val hasDos = if (directory == null) builtIn(DOS)?.use { true } == true else File(directory, DOS).isFile
            fun read(name: String): ByteArray {
                val (where, bytes) = if (directory == null) {
                    val stream = builtIn(name) ?: throw IOException("The built-in $name is missing from the jar")
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
            return Roms(read("kernal.rom"), read("basic.rom"), read("chargen.rom"), if (drive && hasDos) read(DOS) else null)
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

/** Where Insert Disk starts browsing. */
fun defaultDiskDirectory(): File = mediaDirectory("disks")

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
 * the app damaged), and fails anyway in /Applications, so such disks are
 * inserted without a backing file.
 */
fun isBundled(file: File): Boolean {
    val app = jarDirectory()?.canonicalFile ?: return false
    return generateSequence(file.canonicalFile) { it.parentFile }.any { it == app }
}

/** Writes the picture after [runHeadless] to [file] as a PNG. */
fun saveScreenshot(roms: Roms, options: Options, file: File) = saveScreenshot(runHeadless(roms, options), file)

/** Writes [machine]'s last frame to [file] as a PNG. */
fun saveScreenshot(machine: C64Machine, file: File) {
    val view = ScreenPanel.VIEW
    val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, view.width, view.height, machine.vic.pixels, view.y * Vic6569.WIDTH + view.x, Vic6569.WIDTH)
    file.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(image, "png", file)
}

/**
 * A listing of [range] as the CPU sees memory after [runHeadless]: RAM, ROM or
 * I/O, as the banking is then. Reading for it changes nothing. The listing
 * numbers the code from [origin], where it will run once copied.
 */
fun disassemble(machine: C64Machine, range: IntRange, origin: Int = range.first): String =
    Disassembler(machine.bus::peek).listing(range.first, range.last, origin)

/**
 * Builds a machine and runs [Options.screenshotFrames] frames (50ths of a
 * second) as fast as possible, with the tape, disk, program and text from
 * [options]. A tape plays only if the text starts it, with PLAY down. Disk
 * changes are not saved.
 */
fun runHeadless(roms: Roms, options: Options): C64Machine {
    val machine = roms.newMachine()
    options.tape?.let {
        machine.datasette.tape = TapeFile.load(it)
        machine.datasette.playDown = true
    }
    options.disk?.let { machine.drive?.disk = DiskFile.load(it) }
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
    return machine
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
        Roms.load(options.romDirectory, options.drive)
    } catch (e: IOException) {
        val message = if (options.romDirectory == null) {
            "${e.message}\n\nThis build is incomplete. Pass --roms DIR with the C64 kernal.rom, " +
                "basic.rom and chargen.rom."
        } else {
            "${e.message}\n\nPut the C64 kernal.rom (8 KiB), basic.rom (8 KiB) and chargen.rom (4 KiB) " +
                "in that directory, and dos1541.rom (16 KiB) for a disk drive, or leave out --roms " +
                "to use the built-in ones."
        }
        System.err.println(message)
        if (options.screenshot == null && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "C64slop", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
    if (roms.dos == null && options.disk != null) {
        val where = options.romDirectory?.let { "put dos1541.rom in ${it.path}" } ?: "this build has no dos1541.rom"
        System.err.println("--disk needs the 1541: $where, and don't pass --no-drive.")
        exitProcess(2)
    }
    if (options.screenshot != null || options.disassemble != null) {
        val machine = try {
            runHeadless(roms, options)
        } catch (e: IOException) {
            System.err.println("Couldn't load a tape, disk or program: ${e.message}")
            exitProcess(1)
        }
        options.disassemble?.let { println(disassemble(machine, it, options.origin ?: it.first)) }
        options.screenshot?.let {
            saveScreenshot(machine, it)
            println("Saved ${it.path}")
        }
        return
    }
    SwingUtilities.invokeLater { EmulatorWindow(roms, options).show() }
}
