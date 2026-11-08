// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import java.io.File

/** Build-time entry point: writes the app icon file for jpackage. Usage: IconExport FILE.icns|ico|png */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val path = args.singleOrNull() ?: error("Usage: IconExport FILE.icns|ico|png")
    AppIcon.writeFile(File(path))
}
