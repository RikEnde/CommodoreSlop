// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

/**
 * Breakpoints and stepping for a [Cpu6502], independent of any machine.
 *
 * The debugger never runs the CPU. The machine's own loop asks it before
 * every step, and tells it after:
 *
 * ```
 * while (...) {
 *     if (debugger.active && debugger.shouldStop(cpu)) { pause; break }
 *     machine.step()
 *     if (debugger.active) debugger.stepped(cpu)
 * }
 * ```
 *
 * That leaves timing, interrupt lines and the rest of the machine to the
 * machine. It reads memory through [peek], which must not have side effects,
 * and only to see which instruction is next. "Stopped at X" means the CPU is
 * about to execute X: a breakpoint stops before its instruction runs.
 *
 * Commands such as [stepOver] take effect when the machine runs again. After
 * any stop, or any command, the instruction at the PC runs before breakpoints
 * are checked again, so continuing from a breakpoint doesn't stop on it at once.
 *
 * Not thread safe: use it from the thread that runs the CPU.
 */
class Debugger(private val peek: (address: Int) -> Int) {
    enum class StopReason {
        /** The PC reached a breakpoint. */
        BREAKPOINT,

        /** A step into, over or out finished. */
        STEP,

        /** The PC reached the address of [runTo]. */
        RUN_TO,
    }

    private enum class Mode { RUN, STEP_INTO, STEP_OVER, STEP_OUT, RETURN_TO, RUN_TO }

    private val breaks = BooleanArray(0x10000)
    private var breakCount = 0
    private var mode = Mode.RUN

    /** False for the first check after a stop or a command, so the PC's own breakpoint is passed. */
    private var armed = true

    /** The step has done what it was asked to: stop at the next check. */
    private var completed = false
    private var target = 0
    private var stackLevel = 0

    /** Interrupts entered and not yet returned from, during a step over. */
    private var depth = 0
    private var opcodeBefore = 0

    /** Why the last stop happened. */
    var stopReason: StopReason? = null
        private set

    /** False when there is nothing to check, so the machine can skip the calls. */
    val active: Boolean get() = mode != Mode.RUN || breakCount > 0

    /** The breakpoint addresses, lowest first. */
    val breakpoints: List<Int> get() = (0..0xFFFF).filter { breaks[it] }

    fun isBreakpoint(address: Int): Boolean = breaks[address and 0xFFFF]

    fun setBreakpoint(address: Int, on: Boolean = true) {
        val a = address and 0xFFFF
        if (breaks[a] != on) {
            breaks[a] = on
            breakCount += if (on) 1 else -1
        }
    }

    /** Sets a breakpoint at [address] or removes the one there; returns whether there is one now. */
    fun toggleBreakpoint(address: Int): Boolean {
        val on = !isBreakpoint(address)
        setBreakpoint(address, on)
        return on
    }

    fun clearBreakpoints() {
        breaks.fill(false)
        breakCount = 0
    }

    /** Run until a breakpoint. */
    fun resume() = start(Mode.RUN)

    /** Execute one instruction, or take one interrupt and stop at the first instruction of its handler. */
    fun stepInto() = start(Mode.STEP_INTO)

    /**
     * Execute one instruction, treating a subroutine call as one instruction:
     * after a JSR, stop when it returns to the instruction after the JSR, at
     * the same stack level, so recursion doesn't stop early. An interrupt taken
     * on the way runs to its RTI without stopping.
     *
     * A routine that returns somewhere else, such as one that reads data placed
     * after its JSR and returns past it, never comes back to that instruction,
     * so this runs on to the next breakpoint. [stepOut] handles that case.
     */
    fun stepOver(cpu: Cpu6502) {
        if (peek(cpu.programCounter) == JSR) {
            start(Mode.RETURN_TO)
            target = (cpu.programCounter + 3) and 0xFFFF
            stackLevel = cpu.stackPointer
        } else {
            start(Mode.STEP_OVER)
        }
    }

    /**
     * Run until the current subroutine or interrupt handler returns: stop
     * after the RTS or RTI that takes the stack above where it is now.
     */
    fun stepOut(cpu: Cpu6502) {
        start(Mode.STEP_OUT)
        stackLevel = cpu.stackPointer
    }

    /** Run until the PC reaches [address], or a breakpoint. */
    fun runTo(address: Int) {
        start(Mode.RUN_TO)
        target = address and 0xFFFF
    }

    private fun start(newMode: Mode) {
        mode = newMode
        armed = false
        completed = false
        depth = 0
    }

    /**
     * Call before each step. True means stop: don't step, and see [stopReason].
     * False means go ahead and step, then call [stepped].
     */
    fun shouldStop(cpu: Cpu6502): Boolean {
        val pc = cpu.programCounter
        val reason = when {
            armed && breaks[pc] -> StopReason.BREAKPOINT
            completed -> StopReason.STEP
            !armed -> null
            mode == Mode.RETURN_TO && pc == target && atOrAbove(cpu.stackPointer, stackLevel) -> StopReason.STEP
            mode == Mode.RUN_TO && pc == target -> StopReason.RUN_TO
            else -> null
        }
        if (reason != null) {
            stopReason = reason
            mode = Mode.RUN
            completed = false
            armed = false
            return true
        }
        armed = true
        if (mode == Mode.STEP_OVER || mode == Mode.STEP_OUT) opcodeBefore = peek(pc)
        return false
    }

    /** Call after each step that [shouldStop] allowed. */
    fun stepped(cpu: Cpu6502) {
        val kind = cpu.lastStep
        when (mode) {
            Mode.RUN, Mode.RETURN_TO, Mode.RUN_TO -> {}
            Mode.STEP_INTO -> completed = true
            Mode.STEP_OVER -> when (kind) {
                StepKind.IRQ, StepKind.NMI -> depth++
                StepKind.HALTED -> completed = true
                StepKind.INSTRUCTION -> when {
                    depth == 0 -> completed = true
                    opcodeBefore == RTI -> depth--
                }
            }
            Mode.STEP_OUT -> if (
                kind == StepKind.HALTED ||
                kind == StepKind.INSTRUCTION && (opcodeBefore == RTS || opcodeBefore == RTI) &&
                above(cpu.stackPointer, stackLevel)
            ) {
                completed = true
            }
        }
    }

    private companion object {
        const val JSR = 0x20
        const val RTS = 0x60
        const val RTI = 0x40

        // The stack pointer wraps, so "above" means within half a page upwards.
        fun above(sp: Int, level: Int) = ((sp - level) and 0xFF) in 1..0x7F
        fun atOrAbove(sp: Int, level: Int) = ((sp - level) and 0xFF) in 0..0x7F
    }
}
