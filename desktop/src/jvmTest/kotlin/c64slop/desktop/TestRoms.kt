// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import java.io.File
import java.io.IOException

val romDirectory = File(System.getProperty("c64slop.romDirectory") ?: "../roms")

/** The real ROMs, or null (and a message) if they are missing, so the test can skip. */
fun testRoms(): Roms? = try {
    Roms.load(romDirectory)
} catch (e: IOException) {
    println("Skipping: ${e.message}")
    null
}
