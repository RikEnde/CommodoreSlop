// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.C64Clock
import c64slop.c64.Joystick
import c64slop.c64.KeyboardMatrix.Key
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyMappingTest {
    @Test
    fun physicalLayoutFollowsTheC64Rows() {
        assertEquals(listOf(Key.PLUS), KeyMapping.forKeyCode(KeyEvent.VK_MINUS))
        assertEquals(listOf(Key.MINUS), KeyMapping.forKeyCode(KeyEvent.VK_EQUALS))
        assertEquals(listOf(Key.AT), KeyMapping.forKeyCode(KeyEvent.VK_OPEN_BRACKET))
        assertEquals(listOf(Key.ASTERISK), KeyMapping.forKeyCode(KeyEvent.VK_CLOSE_BRACKET))
        assertEquals(listOf(Key.UP_ARROW), KeyMapping.forKeyCode(KeyEvent.VK_BACK_SLASH))
        assertEquals(listOf(Key.COLON), KeyMapping.forKeyCode(KeyEvent.VK_SEMICOLON))
        assertEquals(listOf(Key.SEMICOLON), KeyMapping.forKeyCode(KeyEvent.VK_QUOTE))
        assertEquals(listOf(Key.LEFT_ARROW), KeyMapping.forKeyCode(KeyEvent.VK_BACK_QUOTE))
        assertEquals(listOf(Key.POUND), KeyMapping.forKeyCode(KeyEvent.VK_INSERT))
        assertEquals(listOf(Key.EQUALS), KeyMapping.forKeyCode(KeyEvent.VK_PAGE_DOWN))
        assertEquals(listOf(Key.RUN_STOP), KeyMapping.forKeyCode(KeyEvent.VK_ESCAPE))
        assertEquals(listOf(Key.COMMODORE), KeyMapping.forKeyCode(KeyEvent.VK_ALT))
        assertEquals(listOf(Key.CTRL), KeyMapping.forKeyCode(KeyEvent.VK_TAB))
        assertEquals(listOf(Key.Q), KeyMapping.forKeyCode(KeyEvent.VK_Q))
        assertEquals(listOf(Key.DIGIT_0), KeyMapping.forKeyCode(KeyEvent.VK_0))
    }

    @Test
    fun shiftKeysKeepTheirSide() {
        assertEquals(listOf(Key.LEFT_SHIFT), KeyMapping.forKeyCode(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT))
        assertEquals(listOf(Key.RIGHT_SHIFT), KeyMapping.forKeyCode(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT))
        assertEquals(Key.RIGHT_SHIFT, KeyMapping.naturalModifier(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT))
    }

    @Test
    fun missingKeysPressShiftWithTheirPartner() {
        assertEquals(listOf(Key.CURSOR_RIGHT), KeyMapping.forKeyCode(KeyEvent.VK_RIGHT))
        assertEquals(listOf(Key.LEFT_SHIFT, Key.CURSOR_RIGHT), KeyMapping.forKeyCode(KeyEvent.VK_LEFT))
        assertEquals(listOf(Key.LEFT_SHIFT, Key.CURSOR_DOWN), KeyMapping.forKeyCode(KeyEvent.VK_UP))
        assertEquals(listOf(Key.F1), KeyMapping.forKeyCode(KeyEvent.VK_F1))
        assertEquals(listOf(Key.LEFT_SHIFT, Key.F1), KeyMapping.forKeyCode(KeyEvent.VK_F2))
        assertEquals(listOf(Key.LEFT_SHIFT, Key.F7), KeyMapping.forKeyCode(KeyEvent.VK_F8))
    }

    @Test
    fun restoreAndShiftLockAreNotMatrixKeys() {
        assertTrue(KeyMapping.isRestore(KeyEvent.VK_PAGE_UP))
        assertTrue(KeyMapping.isRestore(KeyEvent.VK_F12))
        assertTrue(KeyMapping.isShiftLock(KeyEvent.VK_CAPS_LOCK))
        assertEquals(emptyList(), KeyMapping.forKeyCode(KeyEvent.VK_PAGE_UP))
    }

    @Test
    fun symbolsOverrideTheHostModifiers() {
        assertEquals(KeyMapping.NaturalStroke(Key.EQUALS, shift = false), stroke(KeyEvent.VK_EQUALS, '='))
        assertEquals(KeyMapping.NaturalStroke(Key.PLUS, shift = false), stroke(KeyEvent.VK_EQUALS, '+'))
        assertEquals(KeyMapping.NaturalStroke(Key.COLON, shift = false), stroke(KeyEvent.VK_SEMICOLON, ':'))
        assertEquals(KeyMapping.NaturalStroke(Key.DIGIT_2, shift = true), stroke(KeyEvent.VK_QUOTE, '"'))
        assertEquals(KeyMapping.NaturalStroke(Key.AT, shift = false), stroke(KeyEvent.VK_2, '@'))
        assertEquals(KeyMapping.NaturalStroke(Key.DIGIT_8, shift = true), stroke(KeyEvent.VK_9, '('))
        assertEquals(KeyMapping.NaturalStroke(Key.POUND, shift = false), stroke(KeyEvent.VK_3, '£'))
    }

    @Test
    fun lettersAndControlKeysFollowTheHostModifiers() {
        assertEquals(KeyMapping.NaturalStroke(Key.A), stroke(KeyEvent.VK_A, 'a'))
        assertEquals(KeyMapping.NaturalStroke(Key.A), stroke(KeyEvent.VK_A, 'A'))
        assertEquals(KeyMapping.NaturalStroke(Key.DIGIT_1), stroke(KeyEvent.VK_1, '1', control = true), "CTRL+1 is black")
        assertEquals(KeyMapping.NaturalStroke(Key.RETURN), stroke(KeyEvent.VK_ENTER, '\n'))
        assertEquals(KeyMapping.NaturalStroke(Key.CLR_HOME), stroke(KeyEvent.VK_HOME, KeyEvent.CHAR_UNDEFINED))
        assertEquals(KeyMapping.NaturalStroke(Key.SPACE), stroke(KeyEvent.VK_SPACE, ' '))
        assertEquals(KeyMapping.NaturalStroke(Key.F1), stroke(KeyEvent.VK_F1, KeyEvent.CHAR_UNDEFINED))
        assertEquals(KeyMapping.NaturalStroke(Key.F3, shift = true), stroke(KeyEvent.VK_F4, KeyEvent.CHAR_UNDEFINED))
        assertEquals(KeyMapping.NaturalStroke(Key.CURSOR_DOWN, shift = true), stroke(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED))
    }

    @Test
    fun optionComposedSymbolsDropCtrl() {
        // German Mac layout: Option+5 is [. US Mac layout: Option+A is å.
        assertEquals(KeyMapping.NaturalStroke(Key.COLON, shift = true, control = false), stroke(KeyEvent.VK_5, '[', alt = true))
        assertEquals(KeyMapping.NaturalStroke(Key.A), stroke(KeyEvent.VK_A, 'å', alt = true))
    }

    @Test
    fun charactersTheC64LacksDoNothing() {
        assertNull(stroke(KeyEvent.VK_2, 'é'))
        assertNull(stroke(KeyEvent.VK_BACK_QUOTE, '`'))
        assertNull(stroke(KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED), "Option is for composing")
    }

    @Test
    fun deadKeysTypeTheirSymbol() {
        assertEquals(KeyMapping.NaturalStroke(Key.UP_ARROW, shift = false), stroke(KeyEvent.VK_DEAD_CIRCUMFLEX, KeyEvent.CHAR_UNDEFINED))
        assertEquals(KeyMapping.NaturalStroke(Key.E), stroke(KeyEvent.VK_E, 'é'))
    }

    @Test
    fun arrowsAndSpaceWorkTheJoystick() {
        assertEquals(Joystick.Direction.UP, KeyMapping.joystickDirection(KeyEvent.VK_UP))
        assertEquals(Joystick.Direction.LEFT, KeyMapping.joystickDirection(KeyEvent.VK_LEFT))
        assertEquals(Joystick.Direction.FIRE, KeyMapping.joystickDirection(KeyEvent.VK_SPACE))
        assertNull(KeyMapping.joystickDirection(KeyEvent.VK_A))
    }

    @Test
    fun naturalLayoutTypesOnTheRealRom() {
        val machine = testRoms()?.newMachine() ?: return
        val natural = NaturalKeyboard()
        machine.runForSeconds(3.0)
        fun frames(count: Int) = repeat(count) {
            natural.onFrame(machine.keyboard)
            machine.runForCycles(C64Clock.CYCLES_PER_FRAME.toLong())
        }
        fun tap(keyCode: Int, char: Char, shift: Boolean = false) {
            if (shift) natural.modifier(Key.LEFT_SHIFT, true)
            natural.keyDown(keyCode, KeyMapping.naturalStroke(keyCode, char, control = false, alt = false)!!)
            frames(3)
            natural.keyUp(keyCode)
            if (shift) natural.modifier(Key.LEFT_SHIFT, false)
            frames(3)
        }
        tap(KeyEvent.VK_EQUALS, '=')
        tap(KeyEvent.VK_EQUALS, '+', shift = true)
        tap(KeyEvent.VK_SEMICOLON, ':', shift = true)
        tap(KeyEvent.VK_2, '@', shift = true)
        tap(KeyEvent.VK_9, '(', shift = true)
        tap(KeyEvent.VK_A, 'a')
        // Shifted, a letter is a graphics character in the power-on character set.
        tap(KeyEvent.VK_A, 'A', shift = true)
        val screen = machine.screenLines()
        assertEquals("=+:@(A#", screen[6], screen.joinToString("\n"))
    }

    private fun stroke(keyCode: Int, char: Char, control: Boolean = false, alt: Boolean = false) =
        KeyMapping.naturalStroke(keyCode, char, control, alt)
}
