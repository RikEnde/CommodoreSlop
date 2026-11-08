// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconTest {
    @Test
    fun rendersCrispComputerAtAnySize() {
        val icon = AppIcon.render(512)
        assertEquals(512, icon.width)
        assertEquals(0, icon.getRGB(0, 0) ushr 24, "corners are transparent")
        // Each grid cell is 16 x 16 pixels here.
        fun cell(x: Int, y: Int) = icon.getRGB(x * 16 + 8, y * 16 + 8)
        assertEquals(0xFF5A3E2E.toInt(), cell(6, 11), "brown key")
        assertEquals(0xFFD9A441.toInt(), cell(25, 13), "amber function key")
        assertEquals(0xFFE0302A.toInt(), cell(5, 21), "red power light")
        File("build/icon").mkdirs()
        ImageIO.write(icon, "png", File("build/icon/c64slop-512.png"))
    }
}
