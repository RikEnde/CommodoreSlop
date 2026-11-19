// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.debugger

import c64slop.cpu.Cpu6502
import c64slop.cpu.Debugger
import c64slop.cpu.StepKind

/**
 * The machine's half of the debugger: it owns the [Debugger], decides when to
 * make a [DebugSnapshot], and says in words why the machine stopped. Use it
 * only on the thread that runs the machine; the window talks to it through
 * [target], which hops threads with the machine's own command queue.
 *
 * A machine loop uses it like this:
 *
 * ```
 * // Once per loop, after running queued commands:
 * if (paused != wasPaused) session.pausedChanged(paused)
 * session.tick(cpu, paused, frameCount)
 * // Around every step:
 * if (session.shouldStop(cpu)) { paused = true; break }
 * machine.step()
 * session.stepped(cpu)
 * ```
 *
 * @param peek reads memory as the CPU sees it, without side effects.
 * @param framesPerSnapshot how many [tick]s apart the snapshots are while the
 *   machine runs; 5 frames at 50 Hz is ten a second.
 */
class DebugSession(
    private val peek: (address: Int) -> Int,
    private val framesPerSnapshot: Int = 5,
) {
    /** Breakpoints and stepping. Change it only on the machine's thread. */
    val debugger = Debugger(peek)

    @Volatile private var receiver: ((DebugSnapshot) -> Unit)? = null
    private var stopReason: String? = null
    private var snapshotDue = false
    private var ticksSinceSnapshot = 0

    /**
     * The [DebugTarget] for a debugger window.
     *
     * @param post runs a block on the machine's thread, before the next step.
     * @param cpu the machine's CPU now (a power cycle may replace it).
     * @param setPaused pauses or resumes the machine; called on the machine's thread.
     */
    fun target(post: (() -> Unit) -> Unit, cpu: () -> Cpu6502, setPaused: (Boolean) -> Unit): DebugTarget =
        object : DebugTarget {
            override fun debugContinue() = resume { debugger.resume() }
            override fun debugPause() = post { setPaused(true) }
            override fun debugStepInto() = resume { debugger.stepInto() }
            override fun debugStepOver() = resume { debugger.stepOver(cpu()) }
            override fun debugStepOut() = resume { debugger.stepOut(cpu()) }
            override fun debugRunTo(address: Int) = resume { debugger.runTo(address) }
            override fun toggleBreakpoint(address: Int) = change { debugger.toggleBreakpoint(address) }
            override fun setBreakpoint(address: Int, on: Boolean) = change { debugger.setBreakpoint(address, on) }
            override fun clearBreakpoints() = change { debugger.clearBreakpoints() }
            override fun watch(receiver: ((DebugSnapshot) -> Unit)?) {
                this@DebugSession.receiver = receiver
                if (receiver != null) post { snapshotDue = true }
            }

            private fun resume(command: () -> Unit) = post {
                command()
                setPaused(false)
            }

            private fun change(command: () -> Unit) = post {
                command()
                snapshotDue = true
            }
        }

    /**
     * Call before each step. True means the debugger stopped the machine: don't
     * step, pause the machine. Cheap when there are no breakpoints and no step
     * under way.
     */
    fun shouldStop(cpu: Cpu6502): Boolean {
        if (!debugger.active || !debugger.shouldStop(cpu)) return false
        val pc = cpu.programCounter
        stopReason = when (debugger.stopReason) {
            Debugger.StopReason.BREAKPOINT -> "Breakpoint at $%04X".format(pc)
            Debugger.StopReason.RUN_TO -> "Ran to $%04X".format(pc)
            else -> when (cpu.lastStep) {
                StepKind.IRQ, StepKind.NMI -> "Stepped into the ${cpu.lastStep} handler"
                else -> "Stepped"
            }
        }
        return true
    }

    /** Call after each step. */
    fun stepped(cpu: Cpu6502) {
        if (debugger.active) debugger.stepped(cpu)
    }

    /** The machine stopped for its own reason, such as a JAM opcode; [reason] says it for the window. */
    fun stopped(reason: String) {
        stopReason = reason
        snapshotDue = true
    }

    /** Call when the machine pauses or runs again, for any reason. */
    fun pausedChanged(paused: Boolean) {
        if (!paused) stopReason = null
        snapshotDue = true
    }

    /** Call once per pass of the machine loop, paused or not: sends a snapshot when one is due. */
    fun tick(cpu: Cpu6502, paused: Boolean, frame: Long) {
        if (!snapshotDue && (paused || ++ticksSinceSnapshot < framesPerSnapshot)) return
        snapshotDue = false
        ticksSinceSnapshot = 0
        val receiver = receiver ?: return
        receiver(DebugSnapshot(
            state = cpu.state(),
            lastStep = cpu.lastStep,
            halted = cpu.halted,
            cycles = cpu.totalCycles,
            frame = frame,
            memory = IntArray(0x10000) { peek(it) },
            breakpoints = debugger.breakpoints,
            paused = paused,
            stopReason = if (paused) stopReason ?: "Paused" else null,
        ))
    }
}
