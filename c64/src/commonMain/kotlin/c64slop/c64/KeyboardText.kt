// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import c64slop.c64.KeyboardMatrix.Key

/**
 * Which C64 key types a character, as the KERNAL reads the keyboard in its
 * power-on character set (upper case and graphics).
 *
 * Letters type unshifted whatever their case: in that character set an
 * unshifted letter shows as a capital, and SHIFT gives a graphics character.
 * So pasted listings work whether they are in upper or lower case. PETSCII
 * has `↑` where ASCII has `^`, `←` where it has `_`, and `£` where it has `\`;
 * those type the C64's keys. π is SHIFT+`↑`.
 */
object KeyboardText {
    /** A key to press for a character, with or without SHIFT. */
    data class Stroke(val key: Key, val shift: Boolean)

    private val unshifted: Map<Char, Key> = mapOf(
        ' ' to Key.SPACE, '\n' to Key.RETURN, '\r' to Key.RETURN,
        '+' to Key.PLUS, '-' to Key.MINUS, '£' to Key.POUND, '\\' to Key.POUND, '@' to Key.AT,
        '*' to Key.ASTERISK, '^' to Key.UP_ARROW, '↑' to Key.UP_ARROW, '_' to Key.LEFT_ARROW, '←' to Key.LEFT_ARROW,
        ':' to Key.COLON, ';' to Key.SEMICOLON, '=' to Key.EQUALS,
        ',' to Key.COMMA, '.' to Key.FULL_STOP, '/' to Key.SLASH,
    )

    private val shifted: Map<Char, Key> = mapOf(
        '!' to Key.DIGIT_1, '"' to Key.DIGIT_2, '#' to Key.DIGIT_3, '$' to Key.DIGIT_4,
        '%' to Key.DIGIT_5, '&' to Key.DIGIT_6, '\'' to Key.DIGIT_7, '(' to Key.DIGIT_8,
        ')' to Key.DIGIT_9, '[' to Key.COLON, ']' to Key.SEMICOLON,
        '<' to Key.COMMA, '>' to Key.FULL_STOP, '?' to Key.SLASH, 'π' to Key.UP_ARROW,
    )

    /** The key for [char], or null if the C64 cannot type it. */
    fun forCharacter(char: Char): Stroke? {
        if (char in 'A'..'Z' || char in 'a'..'z') return Stroke(letter(char), shift = false)
        if (char in '0'..'9') return Stroke(digit(char), shift = false)
        unshifted[char]?.let { return Stroke(it, shift = false) }
        shifted[char]?.let { return Stroke(it, shift = true) }
        return null
    }

    fun canType(char: Char): Boolean = forCharacter(char) != null

    fun letter(char: Char): Key = Key.valueOf(char.uppercaseChar().toString())

    fun digit(char: Char): Key = Key.valueOf("DIGIT_$char")
}
