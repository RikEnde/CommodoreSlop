// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.Vic6569
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JPanel

/**
 * Shows the latest picture, cropped to [VIEW], scaled to fit with its shape
 * and letterboxed in black. Adapted from Beebslop's `ScreenPanel`.
 */
class ScreenPanel(private val emulator: Emulator) : JPanel() {
    private val image = BufferedImage(Vic6569.WIDTH, Vic6569.HEIGHT, BufferedImage.TYPE_INT_RGB)
    private val pixels = (image.raster.dataBuffer as DataBufferInt).data

    var scale: Int = 1
        set(value) {
            field = value
            preferredSize = Dimension(VIEW.width * value, VIEW.height * value)
            revalidate()
        }

    init {
        background = Color.BLACK
        isFocusable = true
        focusTraversalKeysEnabled = false // Tab is the C64's CTRL.
        isDoubleBuffered = true
        scale = 1
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        emulator.copyFrame(pixels)
        val target = fitRectangle(width, height)
        (g as Graphics2D).setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        g.drawImage(
            image,
            target.x, target.y, target.x + target.width, target.y + target.height,
            VIEW.x, VIEW.y, VIEW.x + VIEW.width, VIEW.y + VIEW.height,
            null,
        )
    }

    companion object {
        /**
         * The part of the picture to show: the 320 x 200 display window with
         * borders of 32 pixels at the sides and 36 at the top and bottom, about
         * what a PAL television showed. The window starts at X coordinate 24
         * (picture column 124) and raster line 51.
         */
        val VIEW = Rectangle(92, 15, 384, 272)

        /** Largest rectangle the shape of [VIEW] centred in a width x height area. */
        fun fitRectangle(width: Int, height: Int): Rectangle {
            val w = minOf(width, height * VIEW.width / VIEW.height)
            val h = w * VIEW.height / VIEW.width
            return Rectangle((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
