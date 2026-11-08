// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.Program
import c64slop.c64.Tape
import java.io.File
import java.io.IOException

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

/** PRG program files on disk. */
object ProgramFile {
    /** @throws IOException if the file can't be read or isn't a program. */
    fun load(file: File): Program = try {
        Program.parse(file.readBytes())
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    }
}
