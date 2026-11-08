// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * The application icon: a pixel-art "breadbin" home computer, a beige wedge
 * with brown keys, a column of amber function keys and a red power light.
 * A generic 8-bit computer, not any maker's logo. Drawn from a 32 x 32 grid
 * and scaled with nearest-neighbour filtering, so it stays crisp at every
 * size. Adapted from Beebslop's `AppIcon`.
 */
object AppIcon {
    private val ART = arrayOf(
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "....oooooooooooooooooooooooo....",
        "....ohhhhhhhhhhhhhhhhhhhhhho....",
        "....occcccccccccccccccccccco....",
        "....ockckckckckckckckckcffco....",
        "....occcccccccccccccccccccco....",
        "...ockckckckckckckckckckcffco...",
        "...occcccccccccccccccccccccco...",
        "...occkckckckckckckckckccffco...",
        "...occcccccccccccccccccccccco...",
        "..ockckckckckckckckckckccffcco..",
        "..occcccccccccccccccccccccccco..",
        "..occcccckkkkkkkkkkkkkccccccco..",
        "..occcccccccccccccccccccccccco..",
        "..oCCrCCCCCCCCCCCCCCCCCCCCCCCo..",
        "..oCCCCCCCCCCCCCCCCCCCCCCCCCCo..",
        "..oooooooooooooooooooooooooooo..",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
        "................................",
    )

    private val PALETTE = mapOf(
        '.' to 0x00000000,
        'o' to 0xFF3A3028.toInt(), // outline
        'h' to 0xFFE4D4AE.toInt(), // top edge highlight
        'c' to 0xFFC8B48C.toInt(), // case
        'C' to 0xFF9C8662.toInt(), // front of the case
        'k' to 0xFF5A3E2E.toInt(), // keys
        'f' to 0xFFD9A441.toInt(), // function keys
        'r' to 0xFFE0302A.toInt(), // power light
    )

    private val base: BufferedImage by lazy {
        BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
            for ((y, row) in ART.withIndex()) {
                for ((x, char) in row.withIndex()) setRGB(x, y, PALETTE.getValue(char))
            }
        }
    }

    /** The icon at [size] x [size] pixels. */
    fun render(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(base, 0, 0, size, size, null)
        g.dispose()
        return image
    }

    /** The icon as PNG bytes at [size] x [size] pixels. */
    fun png(size: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(render(size), "png", it) }.toByteArray()

    /**
     * Writes the icon for a packaged app: .icns (macOS), .ico (Windows) or .png
     * (Linux), chosen by [file]'s extension. Both containers hold PNG images.
     */
    fun writeFile(file: File) {
        val bytes = when (file.extension.lowercase()) {
            "icns" -> icns()
            "ico" -> ico()
            "png" -> png(512)
            else -> throw IllegalArgumentException("Unknown icon type: ${file.name}")
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /** Apple icon image: a big-endian "icns" header, then typed PNG entries. */
    private fun icns(): ByteArray {
        // ic11-ic14 and ic10 are the Retina (@2x) versions of 16, 32, 128, 256 and 512.
        // Not icp6: iconutil reads it as 48 pixels.
        val types = listOf(
            "icp4" to 16, "icp5" to 32, "ic07" to 128, "ic08" to 256, "ic09" to 512,
            "ic11" to 32, "ic12" to 64, "ic13" to 256, "ic14" to 512, "ic10" to 1024,
        )
        val entries = ByteArrayOutputStream()
        val out = DataOutputStream(entries)
        for ((type, size) in types) {
            val data = png(size)
            out.writeBytes(type)
            out.writeInt(8 + data.size)
            out.write(data)
        }
        val file = ByteArrayOutputStream()
        DataOutputStream(file).apply {
            writeBytes("icns")
            writeInt(8 + entries.size())
            write(entries.toByteArray())
        }
        return file.toByteArray()
    }

    /** Windows icon: a little-endian directory of PNG images (Vista and later). */
    private fun ico(): ByteArray {
        val images = listOf(16, 32, 48, 64, 128, 256).map { it to png(it) }
        val header = ByteBuffer.allocate(6 + 16 * images.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(images.size.toShort())
        var offset = header.capacity()
        for ((size, data) in images) {
            header.put((size % 256).toByte()).put((size % 256).toByte()) // 0 means 256
            header.put(0).put(0).putShort(1).putShort(32)
            header.putInt(data.size).putInt(offset)
            offset += data.size
        }
        return ByteArrayOutputStream().apply {
            write(header.array())
            for ((_, data) in images) write(data)
        }.toByteArray()
    }

    /** Window icons (title bar, Windows taskbar, Linux docks) and the macOS Dock icon. */
    fun install(frame: JFrame) {
        frame.iconImages = listOf(16, 32, 64, 128, 256).map(::render)
        try {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = render(512) as Image
        } catch (_: UnsupportedOperationException) {
            // No taskbar on this platform; window icons are enough.
        } catch (_: SecurityException) {
        }
    }
}
