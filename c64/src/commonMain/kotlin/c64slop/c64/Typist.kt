// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import c64slop.c64.KeyboardMatrix.Key

/**
 * Presses keys frame by frame, like a very steady typist: pasted text, or key
 * chords from a menu. Call [onFrame] once per frame (a 50th of a second). The
 * KERNAL scans the keyboard from its 60 Hz interrupt, so each key is held, then
 * released, for a few frames; RETURN gets extra time so BASIC can tokenise the
 * line before the next one arrives.
 *
 * In a chord, the modifiers go down a frame before the last key, as a person
 * would press them. SHIFT LOCK is released before typing, since it would turn
 * letters into graphics characters.
 *
 * Adapted from Beebslop's `Typist`.
 */
class Typist {
    private sealed interface Item {
        data class Text(val char: Char) : Item

        /** Keys held together; [onPress] runs once they are all down (e.g. RESTORE). */
        data class Chord(val keys: List<Key>, val holdFrames: Int, val onPress: ((C64Machine) -> Unit)?) : Item

        /** Something to do to the machine between keys, such as loading a program. */
        data class Action(val action: (C64Machine) -> Unit) : Item
    }

    private val pending = ArrayDeque<Item>()
    private var waitFrames = 0
    private var held: List<Key> = emptyList()
    private var finalKey: Key? = null
    private var finalHoldFrames = 0
    private var finalAction: ((C64Machine) -> Unit)? = null

    val busy: Boolean get() = pending.isNotEmpty() || held.isNotEmpty() || waitFrames > 0

    /** Queue [text]; characters the C64 cannot type are skipped and returned. */
    fun enqueue(text: String, delayFrames: Int = 0): Set<Char> {
        val skipped = mutableSetOf<Char>()
        val normalised = text.replace("\r\n", "\n").replace('\t', ' ')
        for (char in normalised) {
            if (KeyboardText.canType(char)) pending += Item.Text(char) else skipped += char
        }
        waitFrames = maxOf(waitFrames, delayFrames)
        return skipped
    }

    /** Press [keys] together for [holdFrames] frames, then release them. */
    fun enqueueChord(keys: List<Key>, holdFrames: Int = PRESS_FRAMES, onPress: ((C64Machine) -> Unit)? = null) {
        require(keys.isNotEmpty())
        pending += Item.Chord(keys, holdFrames, onPress)
    }

    /** Run [action] once the keys queued before it are typed, after waiting [delayFrames]. */
    fun enqueueAction(delayFrames: Int = 0, action: (C64Machine) -> Unit) {
        pending += Item.Action(action)
        waitFrames = maxOf(waitFrames, delayFrames)
    }

    fun cancel(machine: C64Machine) {
        pending.clear()
        waitFrames = 0
        finalKey = null
        finalAction = null
        releaseHeld(machine)
    }

    fun onFrame(machine: C64Machine) {
        if (waitFrames > 0) {
            waitFrames--
            return
        }
        finalKey?.let { key ->
            machine.keyboard.press(key)
            finalKey = null
            waitFrames = finalHoldFrames
            finalAction?.invoke(machine)
            finalAction = null
            return
        }
        if (held.isNotEmpty()) {
            waitFrames = if (Key.RETURN in held) RETURN_FRAMES else RELEASE_FRAMES
            releaseHeld(machine)
            return
        }
        when (val item = pending.removeFirstOrNull() ?: return) {
            is Item.Text -> {
                val stroke = KeyboardText.forCharacter(item.char) ?: return
                machine.keyboard.shiftLock = false
                press(machine, listOfNotNull(Key.LEFT_SHIFT.takeIf { stroke.shift }, stroke.key), PRESS_FRAMES, null)
            }
            is Item.Chord -> press(machine, item.keys, item.holdFrames, item.onPress)
            is Item.Action -> {
                item.action(machine)
                waitFrames = RELEASE_FRAMES
            }
        }
    }

    private fun press(machine: C64Machine, keys: List<Key>, holdFrames: Int, onPress: ((C64Machine) -> Unit)?) {
        held = keys
        keys.dropLast(1).forEach(machine.keyboard::press)
        // The last key goes down on a later frame (at once for a single key).
        finalKey = keys.last()
        finalHoldFrames = holdFrames
        finalAction = onPress
        waitFrames = if (keys.size > 1) MODIFIER_LEAD_FRAMES else 0
        if (keys.size == 1) onFrame(machine)
    }

    private fun releaseHeld(machine: C64Machine) {
        held.forEach(machine.keyboard::release)
        held = emptyList()
    }

    private companion object {
        const val PRESS_FRAMES = 2
        const val RELEASE_FRAMES = 2
        const val RETURN_FRAMES = 8
        const val MODIFIER_LEAD_FRAMES = 1
    }
}
