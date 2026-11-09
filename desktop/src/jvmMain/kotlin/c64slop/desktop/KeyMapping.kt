// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.Joystick
import c64slop.c64.KeyboardMatrix.Key
import c64slop.c64.KeyboardText
import java.awt.event.KeyEvent

/**
 * Host keyboard to C64 keyboard. Adapted from Beebslop's `KeyMapping`.
 *
 * In the Physical layout keys map by position, so shifted symbols are the
 * C64's own (Shift+2 is "). The rows of symbols follow the C64's: - and = are
 * its + and -, [ ] \ its @ * ↑, ; and ' its : and ;. The C64 has three keys a
 * US keyboard lacks: £ is on Insert, = on Page Down, and RESTORE on Page Up or
 * F12. The host's Up and Left press SHIFT with the C64's two cursor keys; F2,
 * F4, F6 and F8 are SHIFT with F1, F3, F5 and F7.
 *
 * The Natural layout types the character the host produced, through
 * [naturalStroke] and [NaturalKeyboard].
 */
object KeyMapping {
    private val physical: Map<Int, List<Key>> = buildMap {
        for (c in 'A'..'Z') put(KeyEvent.VK_A + (c - 'A'), listOf(KeyboardText.letter(c)))
        for (d in '0'..'9') put(KeyEvent.VK_0 + (d - '0'), listOf(KeyboardText.digit(d)))
        val functions = listOf(Key.F1, Key.F3, Key.F5, Key.F7)
        for (n in 0..7) put(KeyEvent.VK_F1 + n,
            if (n % 2 == 0) listOf(functions[n / 2]) else listOf(Key.LEFT_SHIFT, functions[n / 2]))
        val singles = mapOf(
            KeyEvent.VK_SPACE to Key.SPACE, KeyEvent.VK_ENTER to Key.RETURN,
            KeyEvent.VK_BACK_SPACE to Key.INST_DEL, KeyEvent.VK_DELETE to Key.INST_DEL,
            KeyEvent.VK_HOME to Key.CLR_HOME, KeyEvent.VK_ESCAPE to Key.RUN_STOP,
            KeyEvent.VK_TAB to Key.CTRL, KeyEvent.VK_CONTROL to Key.CTRL,
            KeyEvent.VK_SHIFT to Key.LEFT_SHIFT, KeyEvent.VK_ALT to Key.COMMODORE,
            KeyEvent.VK_RIGHT to Key.CURSOR_RIGHT, KeyEvent.VK_DOWN to Key.CURSOR_DOWN,
            KeyEvent.VK_BACK_QUOTE to Key.LEFT_ARROW, KeyEvent.VK_MINUS to Key.PLUS,
            KeyEvent.VK_EQUALS to Key.MINUS, KeyEvent.VK_OPEN_BRACKET to Key.AT,
            KeyEvent.VK_CLOSE_BRACKET to Key.ASTERISK, KeyEvent.VK_BACK_SLASH to Key.UP_ARROW,
            KeyEvent.VK_SEMICOLON to Key.COLON, KeyEvent.VK_QUOTE to Key.SEMICOLON,
            KeyEvent.VK_INSERT to Key.POUND, KeyEvent.VK_PAGE_DOWN to Key.EQUALS,
            KeyEvent.VK_COMMA to Key.COMMA, KeyEvent.VK_PERIOD to Key.FULL_STOP,
            KeyEvent.VK_SLASH to Key.SLASH,
        )
        singles.forEach { (code, key) -> put(code, listOf(key)) }
        put(KeyEvent.VK_LEFT, listOf(Key.LEFT_SHIFT, Key.CURSOR_RIGHT))
        put(KeyEvent.VK_UP, listOf(Key.LEFT_SHIFT, Key.CURSOR_DOWN))
    }

    /** The C64 keys a host key presses in the Physical layout: none, one, or SHIFT and one. */
    fun forKeyCode(keyCode: Int, location: Int = KeyEvent.KEY_LOCATION_STANDARD): List<Key> =
        if (keyCode == KeyEvent.VK_SHIFT && location == KeyEvent.KEY_LOCATION_RIGHT) listOf(Key.RIGHT_SHIFT)
        else physical[keyCode].orEmpty()

    /** RESTORE is not in the matrix: it pulls NMI low. */
    fun isRestore(keyCode: Int): Boolean = keyCode == KeyEvent.VK_PAGE_UP || keyCode == KeyEvent.VK_F12

    /** The host's Caps Lock latches like SHIFT LOCK. */
    fun isShiftLock(keyCode: Int): Boolean = keyCode == KeyEvent.VK_CAPS_LOCK

    /**
     * A key press in the Natural layout. A null [shift] follows what the host
     * is holding; true or false overrides it while the key is down.
     */
    data class NaturalStroke(val key: Key, val shift: Boolean? = null, val control: Boolean? = null)

    /** The C64 modifier a host key stands for, in the Natural layout. */
    fun naturalModifier(keyCode: Int, location: Int = KeyEvent.KEY_LOCATION_STANDARD): Key? = when (keyCode) {
        KeyEvent.VK_SHIFT -> if (location == KeyEvent.KEY_LOCATION_RIGHT) Key.RIGHT_SHIFT else Key.LEFT_SHIFT
        KeyEvent.VK_CONTROL, KeyEvent.VK_TAB -> Key.CTRL
        else -> null
    }

    /**
     * Stroke for a host key that produced [char], or null if it should do nothing.
     * With [alt] (Option or AltGr) the host may have composed a symbol; then the
     * symbol is typed, and CTRL must not reach the C64.
     *
     * Letters follow the host's SHIFT, as on the C64: unshifted they are capitals
     * in the power-on character set (lower case after C= + SHIFT), shifted they
     * are graphics (capitals).
     */
    fun naturalStroke(keyCode: Int, char: Char, control: Boolean, alt: Boolean): NaturalStroke? {
        if (keyCode in positionalInNatural) {
            val keys = forKeyCode(keyCode)
            // Host Left and Up, and F2-F8, are SHIFT with a C64 key.
            return keys.lastOrNull()?.let { NaturalStroke(it, if (keys.size > 1) true else null) }
        }
        deadKeys[keyCode]?.let { return symbolStroke(it, alt) }
        // CTRL combines with the key itself, whatever character the host makes.
        if (control && !alt) return byPosition(keyCode)?.let { NaturalStroke(it) }
        if (char in 'a'..'z' || char in 'A'..'Z') return NaturalStroke(KeyboardText.letter(char))
        symbolStroke(char, alt)?.let { return it }
        // An accented letter is what follows a dead key: type its letter.
        if (keyCode in KeyEvent.VK_A..KeyEvent.VK_Z) return byPosition(keyCode)?.let { NaturalStroke(it) }
        return null
    }

    private fun symbolStroke(char: Char, alt: Boolean): NaturalStroke? {
        val stroke = KeyboardText.forCharacter(char) ?: return null
        return NaturalStroke(stroke.key, stroke.shift, if (alt) false else null)
    }

    /** Letters and digits by position; symbols differ between the keyboards. */
    private fun byPosition(keyCode: Int): Key? =
        if (keyCode in KeyEvent.VK_A..KeyEvent.VK_Z || keyCode in KeyEvent.VK_0..KeyEvent.VK_9) {
            forKeyCode(keyCode).firstOrNull()
        } else {
            null
        }

    /** Dead keys wait for the next key on the host. Here they type at once. */
    private val deadKeys = mapOf(
        KeyEvent.VK_DEAD_CIRCUMFLEX to '^', KeyEvent.VK_DEAD_ACUTE to '\'', KeyEvent.VK_DEAD_DIAERESIS to '"',
    )

    /**
     * With a joystick on (port 2 by default), the arrows move it and don't
     * reach the keyboard. Space fires, and is the C64's Space as well, for
     * screens that say "press SPACE".
     */
    fun joystickDirection(keyCode: Int): Joystick.Direction? = when (keyCode) {
        KeyEvent.VK_UP -> Joystick.Direction.UP
        KeyEvent.VK_DOWN -> Joystick.Direction.DOWN
        KeyEvent.VK_LEFT -> Joystick.Direction.LEFT
        KeyEvent.VK_RIGHT -> Joystick.Direction.RIGHT
        KeyEvent.VK_SPACE -> Joystick.Direction.FIRE
        else -> null
    }

    /** Keys that don't type a symbol, so the host's SHIFT and CTRL apply as they are. */
    private val positionalInNatural = setOf(
        KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE, KeyEvent.VK_HOME,
        KeyEvent.VK_ESCAPE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
    ) + (KeyEvent.VK_F1..KeyEvent.VK_F8)
}
