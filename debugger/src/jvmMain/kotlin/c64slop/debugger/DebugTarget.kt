// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.debugger

/**
 * What the debugger window asks of a running machine. Every method may be
 * called from any thread, and only asks: the machine carries it out on its own
 * thread, and the window learns the result from the next [DebugSnapshot].
 * [DebugSession.target] makes one for a machine.
 */
interface DebugTarget {
    /** Run on until a breakpoint. */
    fun debugContinue()

    /** Stop where it is. */
    fun debugPause()

    /** Run one instruction, following calls and interrupts. */
    fun debugStepInto()

    /** Run one instruction, or a whole subroutine call; interrupts run to their RTI. */
    fun debugStepOver()

    /** Run until the current subroutine or interrupt handler returns. */
    fun debugStepOut()

    /** Run until the PC reaches [address], or a breakpoint. */
    fun debugRunTo(address: Int)

    fun toggleBreakpoint(address: Int)

    fun setBreakpoint(address: Int, on: Boolean)

    fun clearBreakpoints()

    /**
     * Send snapshots to [receiver] from now on, on the machine's thread: one
     * straight away, then whenever the machine stops or something changes, and
     * a few times a second while it runs. Null stops them.
     */
    fun watch(receiver: ((DebugSnapshot) -> Unit)?)
}
