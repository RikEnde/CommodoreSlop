// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.Disk
import c64slop.c64.Program
import c64slop.c64.Tape
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** TAP cassette images on disk. Adapted from Beebslop's `TapeFile`. */
object TapeFile {
    /**
     * Reads a TAP image.
     * @throws IOException if the file can't be read or isn't a TAP image.
     */
    fun load(file: File): Tape = try {
        Tape.parse(file.readBytes())
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    }

    /** Writes [image], a TAP image. */
    fun save(image: ByteArray, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeBytes(image)
    }
}

/** D64 disk images on disk. Adapted from Beebslop's `DiscFile`. */
object DiskFile {
    /**
     * Reads a D64 image. A file the host won't let us write is inserted write-protected.
     * @throws IOException if the file can't be read or isn't a D64 image.
     */
    fun load(file: File): Disk {
        val disk = try {
            Disk.parseD64(file.readBytes())
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        disk.writeProtected = !file.canWrite()
        return disk
    }

    /** Writes [disk] to [file] as a D64 image, replacing the file in one step. */
    fun save(disk: Disk, file: File) {
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeBytes(disk.toD64())
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** PRG program files on disk. */
object ProgramFile {
    /** @throws IOException if the file can't be read or isn't a program. */
    fun load(file: File): Program = try {
        Program.parse(file.readBytes())
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    }
}
