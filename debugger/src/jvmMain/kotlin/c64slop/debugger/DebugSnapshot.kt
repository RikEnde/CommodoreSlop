// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.debugger

import c64slop.cpu.CpuState
import c64slop.cpu.StepKind

/**
 * The machine as the debugger window shows it, copied on the emulation thread
 * so the window can read it at leisure without touching the live machine.
 * [DebugSession] makes them.
 */
class DebugSnapshot(
    val state: CpuState,
    val lastStep: StepKind,
    val halted: Boolean,
    /** CPU cycles since power on, without any wait states the machine adds. */
    val cycles: Long,
    /** Video frames since power on, as the machine counts them. */
    val frame: Long,
    /** All 64 KiB as the CPU sees it now (RAM, ROM, I/O), read without side effects. */
    val memory: IntArray,
    val breakpoints: List<Int>,
    val paused: Boolean,
    /** Why the machine is stopped, or null while it runs. */
    val stopReason: String?,
) {
    fun peek(address: Int): Int = memory[address and 0xFFFF]
}
