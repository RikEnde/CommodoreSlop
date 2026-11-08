// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * The MOS 6569 VIC-II, the PAL C64's video chip, run one cycle at a time: 63
 * cycles a line, 312 lines a frame, 8 pixels a cycle.
 *
 * The sequence within a line follows Christian Bauer's *The MOS 6567/6569
 * video controller (VIC-II) and its application in the Commodore 64* (1996):
 *
 * - **Bad lines.** In lines `$30-$F7`, when the low 3 bits of the raster match
 *   YSCROLL and DEN was set in line `$30`, the VIC-II reads a row of the video
 *   matrix and Color RAM (c-accesses, cycles 15-54). It pulls BA low from cycle
 *   12 to 54, so the CPU loses 40-43 cycles.
 * - **Graphics.** The g-accesses in cycles 16-55 read the character generator or
 *   bitmap for the 40 columns. VC, VCBASE, RC and VMLI count as in Bauer's
 *   section 3.7.2, and outside display state the VIC-II fetches the idle byte
 *   at `$3FFF` (`$39FF` with ECM). All eight mode combinations are drawn, the
 *   three invalid ones as black, with XSCROLL shifting the picture right.
 * - **Sprites.** Bauer's section 3.8.1: DMA starts in cycles 55-56 when a
 *   sprite's Y matches the raster, display in cycle 58, MCBASE steps in cycles
 *   15-16, with the Y-expansion flip-flop. Sprite n's pointer and data are read
 *   in the two cycles starting at cycle 58 + 2n (wrapping into the next line
 *   for sprites 3-7), with BA low from 3 cycles before. Each sprite shifts out
 *   24 pixels (48 X-expanded) from where its X coordinate matches, in high
 *   resolution or multicolor, behind or in front of the foreground graphics.
 *   Sprite-sprite and sprite-background collisions set `$D01E` and `$D01F`.
 * - **Border.** The main and vertical border flip-flops follow the six rules of
 *   Bauer's section 3.9, compared pixel by pixel, so opening the borders works.
 * - **Interrupts.** Raster compare (at cycle 1, or 2 in line 0, and when the
 *   compare value is written) and the two collision interrupts.
 *
 * The picture is [WIDTH] by [HEIGHT] pixels of RGB: every pixel of every
 * line, including blanking. X coordinates count as on the chip, from `$194` at
 * cycle 1 to `$1F7`, then from 0; so picture column `x` shows X coordinate
 * `x - 100` (or `x + 404` left of zero). Picture row `y` is raster line `y`.
 *
 * Simplified: each cycle's 8 pixels are drawn with the registers as they are at
 * the end of that cycle, so a mid-line register change takes effect on a cycle
 * boundary rather than at its exact pixel; the c-accesses in the three cycles
 * after BA falls read the real video matrix instead of `$FF`; the light pen
 * and the "sprite crunch" and other sprite DMA glitches are not emulated.
 */
class Vic6569(
    private val ram: ByteArray,
    charRom: ByteArray,
    private val colorRam: ByteArray,
    /** The VIC-II's 16 KiB bank, 0-3, from CIA 2's port A. */
    private val bank: () -> Int,
) : IoDevice {
    private val chargen = charRom.copyOf()

    // Registers.
    val spriteX = IntArray(8)
    val spriteY = IntArray(8)
    var control1 = 0; private set // $D011 without the raster bit
    var control2 = 0; private set // $D016
    var memoryPointers = 0; private set // $D018
    var spriteEnable = 0; private set
    var spriteYExpand = 0; private set
    var spritePriority = 0; private set
    var spriteMulticolor = 0; private set
    var spriteXExpand = 0; private set
    var spriteSpriteCollision = 0; private set
    var spriteBackgroundCollision = 0; private set
    var interruptFlags = 0; private set
    var interruptMask = 0; private set
    var rasterCompare = 0; private set
    val colors = IntArray(15) // $D020-$D02E: border, background 0-3, sprite multicolor 0-1, sprites 0-7

    /** Current raster line, 0-311, and cycle within it, 1-63. */
    var raster = 0; private set
    var cycle = 1; private set

    /** Frames completed (one each time the raster wraps to line 0). */
    var frames: Long = 0
        private set

    /** True while BA is low: the CPU must not read. */
    var baLow = false
        private set

    /** The last byte the VIC-II fetched; it floats on the bus for open addresses. */
    var lastFetch = 0xFF
        private set

    /** The IRQ output. */
    val irq: Boolean get() = interruptFlags and interruptMask and 0x0F != 0

    /** The picture, updated as it is drawn. */
    val pixels = IntArray(WIDTH * HEIGHT)

    // Display state.
    private var vc = 0
    private var vcBase = 0
    private var rc = 0
    private var vmli = 0
    private var displayState = false
    private var badLinesEnabled = false
    private var rasterMatched = false
    private val matrixChars = IntArray(40)
    private val matrixColors = IntArray(40)
    private val lineChars = IntArray(40)
    private val lineColors = IntArray(40)
    private val lineData = IntArray(40)
    private var mainBorder = true
    private var verticalBorder = true

    // Sprites.
    private val spriteDma = BooleanArray(8)
    private val spriteDisplay = BooleanArray(8)
    private val spriteExpandFlop = BooleanArray(8) { true }
    private val mc = IntArray(8)
    private val mcBase = IntArray(8)
    private val spriteData = IntArray(8)
    private val spriteFetched = BooleanArray(8)
    private val spriteArmed = BooleanArray(8)
    private val spriteShifting = BooleanArray(8)
    private val spriteOffset = IntArray(8)
    private var spritesActive = 0

    /** Run one cycle: the VIC-II's own accesses, then this cycle's 8 pixels. */
    fun tick() {
        startOfCycle()
        val badLine = isBadLine()
        if (badLine) displayState = true

        when (cycle) {
            14 -> {
                vc = vcBase
                vmli = 0
                if (badLine) rc = 0
            }
            15 -> stepMcBase(2)
            16 -> {
                stepMcBase(1)
                for (n in 0 until 8) {
                    if (mcBase[n] == 63) {
                        spriteDma[n] = false
                        spriteDisplay[n] = false
                    }
                }
            }
            55 -> {
                for (n in 0 until 8) if (spriteYExpand and (1 shl n) != 0) spriteExpandFlop[n] = !spriteExpandFlop[n]
                startSpriteDma()
            }
            56 -> startSpriteDma()
            58 -> {
                for (n in 0 until 8) {
                    mc[n] = mcBase[n]
                    if (spriteDma[n] && spriteY[n] == raster and 0xFF) spriteDisplay[n] = true
                }
                if (rc == 7) {
                    vcBase = vc
                    displayState = badLine
                }
                if (displayState) rc = (rc + 1) and 7
            }
        }

        // Graphics fetches: g-access for the column VMLI points at, then the
        // c-access that fills the matrix line for the next one.
        if (cycle in 16..55) gAccess()
        if (badLine && cycle in 15..54) cAccess()
        fetchSprite()

        baLow = (badLine && cycle in 12..54) || spriteBa()
        draw()
        endOfCycle()
    }

    private fun startOfCycle() {
        if (cycle == 1) {
            if (raster == 0) {
                vcBase = 0
                badLinesEnabled = false
            }
            // Sprites fetched at the end of the last line show in this one.
            for (n in 0 until 3) armSprite(n)
            if (raster != 0) checkRasterInterrupt()
        } else if (cycle == 2 && raster == 0) {
            checkRasterInterrupt()
        }
        if (raster == 0x30 && control1 and DEN != 0) badLinesEnabled = true
    }

    private fun endOfCycle() {
        if (cycle == 63) {
            verticalBorderAtLineEnd()
            cycle = 1
            raster++
            if (raster == LINES) {
                raster = 0
                frames++
            }
        } else {
            cycle++
        }
    }

    private fun isBadLine() =
        badLinesEnabled && raster in 0x30..0xF7 && raster and 7 == control1 and 7

    private fun cAccess() {
        val address = ((memoryPointers and 0xF0) shl 6) or vc
        matrixChars[vmli] = fetch(address)
        matrixColors[vmli] = colorRam[vc and 0x3FF].toInt() and 0x0F
    }

    private fun gAccess() {
        val column = cycle - 16
        val ecm = control1 and ECM != 0
        if (displayState) {
            val char = matrixChars[vmli]
            val address = if (control1 and BMM != 0) {
                ((memoryPointers and 0x08) shl 10) or (vc shl 3) or rc
            } else {
                ((memoryPointers and 0x0E) shl 10) or ((if (ecm) char and 0x3F else char) shl 3) or rc
            }
            lineData[column] = fetch(if (ecm) address and 0x39FF else address)
            lineChars[column] = char
            lineColors[column] = matrixColors[vmli]
            vc = (vc + 1) and 0x3FF
            vmli = (vmli + 1) and 0x3F
        } else {
            lineData[column] = fetch(if (ecm) 0x39FF else 0x3FFF)
            lineChars[column] = 0
            lineColors[column] = 0
        }
    }

    private fun stepMcBase(step: Int) {
        for (n in 0 until 8) if (spriteExpandFlop[n]) mcBase[n] = minOf(mcBase[n] + step, 63)
    }

    private fun startSpriteDma() {
        for (n in 0 until 8) {
            if (spriteEnable and (1 shl n) != 0 && spriteY[n] == raster and 0xFF && !spriteDma[n]) {
                spriteDma[n] = true
                mcBase[n] = 0
                if (spriteYExpand and (1 shl n) != 0) spriteExpandFlop[n] = false
            }
        }
    }

    /** The p-access and three s-accesses for the sprite whose turn it is. */
    private fun fetchSprite() {
        val n = SPRITE_FETCH[cycle]
        if (n < 0 || !spriteDma[n]) return
        val pointer = fetch(((memoryPointers and 0xF0) shl 6) or 0x3F8 or n)
        var data = 0
        repeat(3) {
            data = (data shl 8) or fetch((pointer shl 6) or mc[n])
            mc[n] = (mc[n] + 1) and 0x3F
        }
        spriteData[n] = data
        spriteFetched[n] = spriteDisplay[n]
        // Sprites 3-7 are fetched in the line they show in; 0-2 at the end of
        // the line before, and armed when the next line starts.
        if (n >= 3) armSprite(n)
    }

    /** Let the data last fetched shift out when the raster reaches the sprite's X. */
    private fun armSprite(n: Int) {
        spriteArmed[n] = spriteFetched[n]
        spriteFetched[n] = false
        if (spriteArmed[n]) spritesActive = spritesActive or (1 shl n)
    }

    private fun spriteBa(): Boolean {
        for (n in 0 until 8) {
            if (!spriteDma[n]) continue
            val first = SPRITE_POINTER_CYCLE[n]
            // BA is low from 3 cycles before the p-access until the second s-access cycle.
            val sinceStart = (cycle - (first - 3) + 63) % 63
            if (sinceStart <= 4) return true
        }
        return false
    }

    /** A byte as the VIC-II sees memory: its bank of RAM, with the character ROM in banks 0 and 2. */
    private fun fetch(address: Int): Int {
        val bankNumber = bank() and 3
        val value = if (bankNumber and 1 == 0 && address and 0x3000 == 0x1000) {
            chargen[address and 0x0FFF].toInt() and 0xFF
        } else {
            ram[(bankNumber shl 14) or (address and 0x3FFF)].toInt() and 0xFF
        }
        lastFetch = value
        return value
    }

    private fun checkRasterInterrupt() {
        val match = raster == rasterCompare
        if (match && !rasterMatched) interruptFlags = interruptFlags or IRQ_RASTER
        rasterMatched = match
    }

    private fun verticalBorderAtLineEnd() {
        val rsel = control1 and RSEL != 0
        if (raster == (if (rsel) 251 else 247)) verticalBorder = true
        if (raster == (if (rsel) 51 else 55) && control1 and DEN != 0) verticalBorder = false
    }

    // Drawing.

    private fun draw() {
        val firstPixel = (cycle - 1) * 8
        val row = raster * WIDTH
        val csel = control2 and CSEL != 0
        val rsel = control1 and RSEL != 0
        val left = if (csel) 24 else 31
        val right = if (csel) 344 else 335
        val top = if (rsel) 51 else 55
        val bottom = if (rsel) 251 else 247
        val xScroll = control2 and 7
        val mode = ((control1 and (ECM or BMM)) or (control2 and MCM)) shr 4
        for (i in 0 until 8) {
            val px = firstPixel + i
            val x = px - 100
            if (x == right) mainBorder = true
            if (x == left) {
                if (raster == bottom) verticalBorder = true
                if (raster == top && control1 and DEN != 0) verticalBorder = false
                if (!verticalBorder) mainBorder = false
            }
            // Graphics: column and bit for this pixel, after XSCROLL.
            val offset = x - 24 - xScroll
            var colour = colors[1]
            var foreground = false
            if (offset >= 0 && offset < 320) {
                val column = offset shr 3
                val bit = offset and 7
                val data = lineData[column]
                val char = lineChars[column]
                val colorNibble = lineColors[column]
                when (mode) {
                    0 -> { // standard text
                        foreground = data and (0x80 shr bit) != 0
                        colour = if (foreground) colorNibble else colors[1]
                    }
                    1 -> if (colorNibble and 8 != 0) { // multicolor text
                        val pair = (data shr (6 - (bit and 6))) and 3
                        foreground = pair >= 2
                        colour = when (pair) {
                            0 -> colors[1]
                            1 -> colors[2]
                            2 -> colors[3]
                            else -> colorNibble and 7
                        }
                    } else {
                        foreground = data and (0x80 shr bit) != 0
                        colour = if (foreground) colorNibble and 7 else colors[1]
                    }
                    2 -> { // standard bitmap
                        foreground = data and (0x80 shr bit) != 0
                        colour = if (foreground) char shr 4 else char and 0x0F
                    }
                    3 -> { // multicolor bitmap
                        val pair = (data shr (6 - (bit and 6))) and 3
                        foreground = pair >= 2
                        colour = when (pair) {
                            0 -> colors[1]
                            1 -> char shr 4
                            2 -> char and 0x0F
                            else -> colorNibble
                        }
                    }
                    4 -> { // extended background color text
                        foreground = data and (0x80 shr bit) != 0
                        colour = if (foreground) colorNibble else colors[1 + (char shr 6)]
                    }
                    else -> { // invalid modes 5-7: black, but the graphics still collide
                        val multicolor = mode == 7 || (mode == 5 && colorNibble and 8 != 0)
                        foreground = if (multicolor) (data shr (6 - (bit and 6))) and 3 >= 2 else data and (0x80 shr bit) != 0
                        colour = 0
                    }
                }
            }
            if (spritesActive != 0) {
                val sprite = spritePixel(px, foreground)
                if (sprite >= 0) colour = sprite
            }
            pixels[row + px] = PALETTE[if (mainBorder) colors[0] and 0x0F else colour and 0x0F]
        }
    }

    /**
     * The colour sprites give pixel [px], or -1. Advances their shift registers
     * and records collisions; [foreground] says the graphics there are foreground.
     */
    private fun spritePixel(px: Int, foreground: Boolean): Int {
        val xCoordinate = if (px >= 100) px - 100 else px + 404
        var colour = -1
        var drawn = 0
        for (n in 0 until 8) {
            val bit = 1 shl n
            if (spritesActive and bit == 0) continue
            if (!spriteShifting[n]) {
                if (!spriteArmed[n]) {
                    spritesActive = spritesActive and bit.inv()
                    continue
                }
                if (spriteX[n] != xCoordinate) continue
                spriteArmed[n] = false
                spriteShifting[n] = true
                spriteOffset[n] = 0
            }
            val expanded = spriteXExpand and bit != 0
            val index = if (expanded) spriteOffset[n] shr 1 else spriteOffset[n]
            spriteOffset[n]++
            if (spriteOffset[n] >= (if (expanded) 48 else 24)) spriteShifting[n] = false
            val pixel = if (spriteMulticolor and bit != 0) {
                when ((spriteData[n] shr (22 - (index and 0x1E))) and 3) {
                    0 -> -1
                    1 -> colors[5]
                    2 -> colors[7 + n]
                    else -> colors[6]
                }
            } else {
                if (spriteData[n] and (0x800000 shr index) != 0) colors[7 + n] else -1
            }
            if (pixel < 0) continue
            drawn = drawn or bit
            if (foreground) collideWithBackground(bit)
            // The lowest-numbered sprite wins; it hides behind foreground graphics if asked.
            if (drawn == bit) {
                colour = if (spritePriority and bit != 0 && foreground) -2 else pixel
            }
        }
        if (drawn and (drawn - 1) != 0) collideSprites(drawn)
        return if (colour == -2) -1 else colour
    }

    private fun collideWithBackground(bits: Int) {
        if (spriteBackgroundCollision == 0) interruptFlags = interruptFlags or IRQ_SPRITE_BACKGROUND
        spriteBackgroundCollision = spriteBackgroundCollision or bits
    }

    private fun collideSprites(bits: Int) {
        if (spriteSpriteCollision == 0) interruptFlags = interruptFlags or IRQ_SPRITE_SPRITE
        spriteSpriteCollision = spriteSpriteCollision or bits
    }

    // Registers.

    override fun read(offset: Int): Int = when (offset and 0x3F) {
        0x1E -> spriteSpriteCollision.also { spriteSpriteCollision = 0 }
        0x1F -> spriteBackgroundCollision.also { spriteBackgroundCollision = 0 }
        else -> peek(offset)
    }

    override fun peek(offset: Int): Int = when (val register = offset and 0x3F) {
        in 0x00..0x0F -> if (register and 1 == 0) spriteX[register shr 1] and 0xFF else spriteY[register shr 1]
        0x10 -> (0 until 8).fold(0) { msb, n -> msb or ((spriteX[n] shr 8) shl n) }
        0x11 -> (control1 and 0x7F) or ((raster and 0x100) shr 1)
        0x12 -> raster and 0xFF
        0x13, 0x14 -> 0
        0x15 -> spriteEnable
        0x16 -> control2 or 0xC0
        0x17 -> spriteYExpand
        0x18 -> memoryPointers or 0x01
        0x19 -> interruptFlags or 0x70 or (if (irq) 0x80 else 0)
        0x1A -> interruptMask or 0xF0
        0x1B -> spritePriority
        0x1C -> spriteMulticolor
        0x1D -> spriteXExpand
        0x1E -> spriteSpriteCollision
        0x1F -> spriteBackgroundCollision
        in 0x20..0x2E -> colors[register - 0x20] or 0xF0
        else -> 0xFF
    }

    override fun write(offset: Int, value: Int) {
        val data = value and 0xFF
        when (val register = offset and 0x3F) {
            in 0x00..0x0F -> {
                val n = register shr 1
                if (register and 1 == 0) spriteX[n] = (spriteX[n] and 0x100) or data else spriteY[n] = data
            }
            0x10 -> for (n in 0 until 8) spriteX[n] = (spriteX[n] and 0xFF) or (((data shr n) and 1) shl 8)
            0x11 -> {
                control1 = data and 0x7F
                rasterCompare = (rasterCompare and 0xFF) or ((data and 0x80) shl 1)
                checkRasterInterrupt()
            }
            0x12 -> {
                rasterCompare = (rasterCompare and 0x100) or data
                checkRasterInterrupt()
            }
            0x15 -> spriteEnable = data
            0x16 -> control2 = data and 0x3F
            0x17 -> {
                spriteYExpand = data
                // The flip-flop stays set while expansion is off.
                for (n in 0 until 8) if (data and (1 shl n) == 0) spriteExpandFlop[n] = true
            }
            0x18 -> memoryPointers = data and 0xFE
            0x19 -> interruptFlags = interruptFlags and (data and 0x0F).inv()
            0x1A -> interruptMask = data and 0x0F
            0x1B -> spritePriority = data
            0x1C -> spriteMulticolor = data
            0x1D -> spriteXExpand = data
            in 0x20..0x2E -> colors[register - 0x20] = data and 0x0F
        }
    }

    /** The address of the video matrix as the CPU sees it: bank and `$D018` bits 4-7. */
    val videoMatrixAddress: Int get() = ((bank() and 3) shl 14) or ((memoryPointers and 0xF0) shl 6)

    companion object {
        const val WIDTH = 63 * 8
        const val HEIGHT = 312
        const val LINES = 312

        const val IRQ_RASTER = 0x01
        const val IRQ_SPRITE_BACKGROUND = 0x02
        const val IRQ_SPRITE_SPRITE = 0x04

        private const val RSEL = 0x08
        private const val DEN = 0x10
        private const val BMM = 0x20
        private const val ECM = 0x40
        private const val CSEL = 0x08
        private const val MCM = 0x10

        /** The cycle of each sprite's p-access (Bauer, the 6569 timing diagram). */
        private val SPRITE_POINTER_CYCLE = intArrayOf(58, 60, 62, 1, 3, 5, 7, 9)

        /** Which sprite is fetched in each cycle, 1-63, or -1. */
        private val SPRITE_FETCH = IntArray(64) { -1 }.also { table ->
            SPRITE_POINTER_CYCLE.forEachIndexed { n, cycle -> table[cycle] = n }
        }

        /**
         * The 16 colours as RGB: Philip "Pepto" Timmermann's *Colodore* palette,
         * measured from a PAL C64.
         */
        val PALETTE = intArrayOf(
            0x000000, 0xFFFFFF, 0x813338, 0x75CEC8, 0x8E3C97, 0x56AC4D, 0x2E2C9B, 0xEDF171,
            0x8E5029, 0x553800, 0xC46C71, 0x4A4A4A, 0x7B7B7B, 0xA9FF9F, 0x706DEB, 0xB2B2B2,
        )
    }
}
