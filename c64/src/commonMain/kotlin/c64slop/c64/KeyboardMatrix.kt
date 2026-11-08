// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * The C64 keyboard: 64 keys in an 8 by 8 matrix between CIA 1's port A and
 * port B, and RESTORE, which is wired to the NMI line instead.
 *
 * The KERNAL drives one port A line low at a time and reads port B: a key down
 * joins its column (port A bit) to its row (port B bit). The matrix conducts
 * both ways, so driving port B low shows up on port A too; programs that scan
 * "backwards" rely on that. The joysticks share the same lines: port 2 on port
 * A, port 1 on port B, pulling them low ([Joystick]).
 *
 * SHIFT LOCK is a latching key wired in parallel with the left SHIFT.
 *
 * Key positions are from the *C64 Programmer's Reference Guide* (appendix C)
 * and MAME's `c64.cpp`.
 */
class KeyboardMatrix {
    /** Pressed keys: bit `row` of `columns[column]`. */
    private val columns = IntArray(8)

    /** The latching SHIFT LOCK key. */
    var shiftLock: Boolean = false

    /** RESTORE, which pulls NMI low while it is down. */
    var restore: Boolean = false

    fun press(key: Key) { columns[key.column] = columns[key.column] or (1 shl key.row) }
    fun release(key: Key) { columns[key.column] = columns[key.column] and (1 shl key.row).inv() }
    fun releaseAll() {
        columns.fill(0)
        restore = false
    }

    fun isPressed(key: Key): Boolean = columns[key.column] and (1 shl key.row) != 0

    private fun column(index: Int): Int =
        if (index == Key.LEFT_SHIFT.column && shiftLock) columns[index] or (1 shl Key.LEFT_SHIFT.row) else columns[index]

    /** Port B (rows) as the keyboard leaves it, given the levels on port A (columns). */
    fun rows(portA: Int): Int {
        var rows = 0xFF
        for (c in 0 until 8) if (portA and (1 shl c) == 0) rows = rows and column(c).inv()
        return rows and 0xFF
    }

    /** Port A (columns) as the keyboard leaves it, given the levels on port B (rows). */
    fun columns(portB: Int): Int {
        var result = 0xFF
        val low = portB.inv() and 0xFF
        for (c in 0 until 8) if (column(c) and low != 0) result = result and (1 shl c).inv()
        return result
    }

    /** Key positions: column is the port A bit, row the port B bit. */
    enum class Key(val column: Int, val row: Int) {
        INST_DEL(0, 0), RETURN(0, 1), CURSOR_RIGHT(0, 2), F7(0, 3), F1(0, 4), F3(0, 5), F5(0, 6), CURSOR_DOWN(0, 7),
        DIGIT_3(1, 0), W(1, 1), A(1, 2), DIGIT_4(1, 3), Z(1, 4), S(1, 5), E(1, 6), LEFT_SHIFT(1, 7),
        DIGIT_5(2, 0), R(2, 1), D(2, 2), DIGIT_6(2, 3), C(2, 4), F(2, 5), T(2, 6), X(2, 7),
        DIGIT_7(3, 0), Y(3, 1), G(3, 2), DIGIT_8(3, 3), B(3, 4), H(3, 5), U(3, 6), V(3, 7),
        DIGIT_9(4, 0), I(4, 1), J(4, 2), DIGIT_0(4, 3), M(4, 4), K(4, 5), O(4, 6), N(4, 7),
        PLUS(5, 0), P(5, 1), L(5, 2), MINUS(5, 3), FULL_STOP(5, 4), COLON(5, 5), AT(5, 6), COMMA(5, 7),
        POUND(6, 0), ASTERISK(6, 1), SEMICOLON(6, 2), CLR_HOME(6, 3), RIGHT_SHIFT(6, 4), EQUALS(6, 5), UP_ARROW(6, 6), SLASH(6, 7),
        DIGIT_1(7, 0), LEFT_ARROW(7, 1), CTRL(7, 2), DIGIT_2(7, 3), SPACE(7, 4), COMMODORE(7, 5), Q(7, 6), RUN_STOP(7, 7),
    }
}

/**
 * A digital joystick: four switches and a fire button, each pulling one CIA 1
 * port line low (bits 0-4: up, down, left, right, fire).
 */
class Joystick {
    var state: Int = 0
        private set

    fun press(direction: Direction) { state = state or direction.bit }
    fun release(direction: Direction) { state = state and direction.bit.inv() }
    fun releaseAll() { state = 0 }

    /** The port lines it leaves high: all but the switches that are closed. */
    val lines: Int get() = state.inv() and 0xFF

    enum class Direction(val bit: Int) { UP(0x01), DOWN(0x02), LEFT(0x04), RIGHT(0x08), FIRE(0x10) }
}
