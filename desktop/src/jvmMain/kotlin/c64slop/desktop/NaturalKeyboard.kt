// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.KeyboardMatrix
import c64slop.c64.KeyboardMatrix.Key

/**
 * The Natural keyboard layout: each host key presses whatever the C64 needs to
 * type the character the host produced. Runs on the emulation thread.
 * Adapted from Beebslop's `NaturalKeyboard`.
 *
 * The two keyboards disagree about SHIFT: on a US keyboard " is Shift+' but on
 * the C64 it is SHIFT+2, and : is shifted on the host but not on the C64. So a
 * key can override the modifiers the host is holding for as long as it is
 * down, which only works for one key at a time: a new key replaces the one
 * before it. Events wait in a queue because the KERNAL misses a key that is
 * replaced or released too soon.
 *
 * The host's left and right Shift stay apart, as the KERNAL treats them alike
 * but some programs don't. An override that needs SHIFT presses the left one.
 */
class NaturalKeyboard {
    private sealed interface Event {
        data class Modifier(val key: Key, val down: Boolean) : Event
        data class KeyDown(val hostKey: Int, val stroke: KeyMapping.NaturalStroke) : Event
        data class KeyUp(val hostKey: Int) : Event
    }

    private val events = ArrayDeque<Event>()
    private val hostModifiers = mutableSetOf<Key>()
    private val appliedModifiers = mutableSetOf<Key>()
    private var activeHostKey: Int? = null
    private var active: KeyMapping.NaturalStroke? = null
    private var waitFrames = 0

    fun modifier(key: Key, down: Boolean) { events += Event.Modifier(key, down) }
    fun keyDown(hostKey: Int, stroke: KeyMapping.NaturalStroke) { events += Event.KeyDown(hostKey, stroke) }
    fun keyUp(hostKey: Int) { events += Event.KeyUp(hostKey) }

    /** Forget everything; the caller releases the matrix keys. */
    fun reset() {
        events.clear()
        hostModifiers.clear()
        appliedModifiers.clear()
        activeHostKey = null
        active = null
        waitFrames = 0
    }

    /** Call once per emulated frame. */
    fun onFrame(keyboard: KeyboardMatrix) {
        if (waitFrames > 0 && --waitFrames > 0) return
        while (waitFrames == 0) {
            when (val event = events.firstOrNull() ?: return) {
                is Event.Modifier -> {
                    if (event.down) hostModifiers += event.key else hostModifiers -= event.key
                    applyModifiers(keyboard)
                    events.removeFirst()
                }
                is Event.KeyDown -> keyDown(keyboard, event)
                is Event.KeyUp -> {
                    events.removeFirst()
                    if (event.hostKey == activeHostKey) {
                        releaseActive(keyboard)
                        waitFrames = RELEASE_FRAMES
                    }
                }
            }
        }
    }

    private fun keyDown(keyboard: KeyboardMatrix, event: Event.KeyDown) {
        // The host repeats a key while it is held. The KERNAL has its own auto-repeat.
        if (event.hostKey == activeHostKey) {
            events.removeFirst()
            return
        }
        // Two host keys can share a C64 key, such as 2 and '. The KERNAL must see
        // it go up between them.
        if (active?.key == event.stroke.key) {
            releaseActive(keyboard)
            waitFrames = RELEASE_FRAMES
            return
        }
        events.removeFirst()
        active?.let { keyboard.release(it.key) }
        activeHostKey = event.hostKey
        active = event.stroke
        applyModifiers(keyboard)
        keyboard.press(event.stroke.key)
        waitFrames = HOLD_FRAMES
    }

    private fun releaseActive(keyboard: KeyboardMatrix) {
        active?.let { keyboard.release(it.key) }
        activeHostKey = null
        active = null
        applyModifiers(keyboard)
    }

    /** Writes only what changed, so keys pressed by the typist are left alone. */
    private fun applyModifiers(keyboard: KeyboardMatrix) {
        val stroke = active
        val wanted = buildSet {
            when (stroke?.shift) {
                true -> add(Key.LEFT_SHIFT)
                false -> {}
                null -> hostModifiers.filter { it in SHIFTS }.forEach(::add)
            }
            if (stroke?.control ?: (Key.CTRL in hostModifiers)) add(Key.CTRL)
        }
        (appliedModifiers - wanted).forEach(keyboard::release)
        (wanted - appliedModifiers).forEach(keyboard::press)
        appliedModifiers.clear()
        appliedModifiers += wanted
    }

    private companion object {
        const val HOLD_FRAMES = 2
        const val RELEASE_FRAMES = 2
        val SHIFTS = setOf(Key.LEFT_SHIFT, Key.RIGHT_SHIFT)
    }
}
