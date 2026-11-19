// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.debugger

import c64slop.cpu.Cpu6502
import c64slop.cpu.CpuState
import c64slop.cpu.MemoryBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session driven by a minimal machine: a 6502 on 64 KiB of RAM, one "frame" per 10 steps. */
class DebugSessionTest {
    private val memory = IntArray(0x10000)
    private val cpu = Cpu6502(object : MemoryBus {
        override fun read(address: Int) = memory[address]
        override fun write(address: Int, value: Int) { memory[address] = value }
    })
    private val session = DebugSession({ memory[it] }, framesPerSnapshot = 2)
    private val queue = ArrayDeque<() -> Unit>()
    private var paused = false
    private var wasPaused = false
    private var frame = 0L
    private val target = session.target(post = { queue += it }, cpu = { cpu }, setPaused = { paused = it })
    private val snapshots = mutableListOf<DebugSnapshot>()

    init {
        // $2000: INX / JSR $3000 / JMP $2000;  $3000: INY / RTS
        listOf(0xE8, 0x20, 0x00, 0x30, 0x4C, 0x00, 0x20).forEachIndexed { i, b -> memory[0x2000 + i] = b }
        listOf(0xC8, 0x60).forEachIndexed { i, b -> memory[0x3000 + i] = b }
        cpu.loadState(CpuState(programCounter = 0x2000, stackPointer = 0xFF))
    }

    /** One pass of a machine loop, as in DebugSession's documentation. */
    private fun loop(passes: Int = 1) = repeat(passes) {
        while (queue.isNotEmpty()) queue.removeFirst()()
        if (paused != wasPaused) {
            wasPaused = paused
            session.pausedChanged(paused)
        }
        session.tick(cpu, paused, frame)
        if (!paused) {
            for (n in 0 until 10) {
                if (session.shouldStop(cpu)) {
                    paused = true
                    return@repeat
                }
                cpu.step()
                session.stepped(cpu)
            }
            frame++
        }
    }

    @Test
    fun snapshotsOnlyWhileWatched() {
        loop(4)
        assertTrue(snapshots.isEmpty())
        target.watch { snapshots += it }
        loop()
        assertEquals(1, snapshots.size, "one straight away")
        loop(4)
        assertEquals(3, snapshots.size, "then every other frame while running")
        target.watch(null)
        loop(4)
        assertEquals(3, snapshots.size)
    }

    @Test
    fun breakpointStopsAndSaysWhy() {
        target.watch { snapshots += it }
        target.setBreakpoint(0x3000, true)
        loop(3)
        assertTrue(paused)
        val last = snapshots.last()
        assertTrue(last.paused)
        assertEquals("Breakpoint at \$3000", last.stopReason)
        assertEquals(0x3000, last.state.programCounter)
        assertEquals(listOf(0x3000), last.breakpoints)
        assertEquals(0xC8, last.peek(0x3000))
    }

    @Test
    fun stepsAndContinues() {
        target.watch { snapshots += it }
        target.setBreakpoint(0x3000, true)
        loop(3)
        target.debugStepOut()
        loop(3)
        assertEquals(0x2004, cpu.programCounter)
        assertEquals("Stepped", snapshots.last().stopReason)
        target.debugStepInto()
        loop(3)
        assertEquals(0x2000, cpu.programCounter)
        target.debugContinue()
        loop(3)
        assertEquals("Breakpoint at \$3000", snapshots.last().stopReason)
        target.debugRunTo(0x2004)
        loop(3)
        assertEquals("Ran to \$2004", snapshots.last().stopReason)
    }

    @Test
    fun pauseAndResumeClearTheReason() {
        target.watch { snapshots += it }
        target.debugPause()
        loop(2)
        assertEquals("Paused", snapshots.last().stopReason)
        session.stopped("Halted by JAM at \$1234")
        loop()
        assertEquals("Halted by JAM at \$1234", snapshots.last().stopReason)
        target.debugContinue()
        loop()
        assertFalse(snapshots.last().paused)
        assertNull(snapshots.last().stopReason)
    }

    @Test
    fun breakpointChangesSendASnapshotWhilePaused() {
        target.watch { snapshots += it }
        target.debugPause()
        loop(3)
        val before = snapshots.size
        target.toggleBreakpoint(0x2004)
        loop()
        assertEquals(before + 1, snapshots.size)
        assertEquals(listOf(0x2004), snapshots.last().breakpoints)
        target.clearBreakpoints()
        loop()
        assertEquals(emptyList(), snapshots.last().breakpoints)
    }
}
