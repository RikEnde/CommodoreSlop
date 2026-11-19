// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebuggerTest {
    private class Ram : MemoryBus {
        val memory = IntArray(0x10000)
        override fun read(address: Int): Int = memory[address]
        override fun write(address: Int, value: Int) { memory[address] = value }
        fun program(address: Int, vararg bytes: Int) {
            bytes.forEachIndexed { i, byte -> memory[(address + i) and 0xFFFF] = byte }
        }
    }

    private val ram = Ram()
    private val cpu = Cpu6502(ram)
    private val debugger = Debugger { ram.memory[it] }

    /**
     * $2000  JSR $3000
     * $2003  LDA #$01
     * $2005  JMP $2005
     * $3000  INX
     * $3001  JSR $3100
     * $3004  RTS
     * $3100  INY
     * $3101  RTS
     * $4000  IRQ handler: INC $10 / RTI
     */
    private fun load(status: Int = StatusFlag.UNUSED or StatusFlag.INTERRUPT_DISABLE) {
        ram.program(0x2000, 0x20, 0x00, 0x30, 0xA9, 0x01, 0x4C, 0x05, 0x20)
        ram.program(0x3000, 0xE8, 0x20, 0x00, 0x31, 0x60)
        ram.program(0x3100, 0xC8, 0x60)
        ram.program(0x4000, 0xE6, 0x10, 0x40)
        ram.program(0xFFFE, 0x00, 0x40)
        cpu.loadState(CpuState(programCounter = 0x2000, stackPointer = 0xFF, status = status))
    }

    /** Runs as the machine would, up to [limit] steps; returns the PC it stopped at, or -1. */
    private fun run(limit: Int = 1000, beforeStep: (Int) -> Unit = {}): Int {
        repeat(limit) { n ->
            if (debugger.active && debugger.shouldStop(cpu)) return cpu.programCounter
            beforeStep(n)
            cpu.step()
            if (debugger.active) debugger.stepped(cpu)
        }
        return -1
    }

    @Test
    fun stopsBeforeABreakpoint() {
        load()
        debugger.setBreakpoint(0x3100)
        assertEquals(0x3100, run())
        assertEquals(Debugger.StopReason.BREAKPOINT, debugger.stopReason)
        assertEquals(0, cpu.y) // INY hasn't run.
    }

    @Test
    fun continuingPassesTheBreakpointItStoppedAt() {
        load()
        debugger.setBreakpoint(0x2005) // The JMP loops to itself.
        assertEquals(0x2005, run())
        debugger.resume()
        assertEquals(0x2005, run()) // Once round the loop, then stops again.
        assertEquals(Debugger.StopReason.BREAKPOINT, debugger.stopReason)
    }

    @Test
    fun noBreakpointsMeansNothingToCheck() {
        assertFalse(debugger.active)
        debugger.toggleBreakpoint(0x1234)
        assertTrue(debugger.active)
        assertEquals(listOf(0x1234), debugger.breakpoints)
        debugger.toggleBreakpoint(0x1234)
        assertFalse(debugger.active)
        debugger.setBreakpoint(1)
        debugger.setBreakpoint(1)
        debugger.clearBreakpoints()
        assertFalse(debugger.active)
    }

    @Test
    fun stepIntoFollowsTheCall() {
        load()
        debugger.stepInto()
        assertEquals(0x3000, run())
        assertEquals(Debugger.StopReason.STEP, debugger.stopReason)
        debugger.stepInto()
        assertEquals(0x3001, run())
        assertFalse(debugger.active) // Back to running, nothing to check.
    }

    @Test
    fun stepOverRunsTheWholeCall() {
        load()
        debugger.stepOver(cpu)
        assertEquals(0x2003, run())
        assertEquals(1, cpu.x)
        assertEquals(1, cpu.y)
        assertEquals(0xFF, cpu.stackPointer)
        debugger.stepOver(cpu) // Not a call: one instruction.
        assertEquals(0x2005, run())
    }

    @Test
    fun stepOverStillStopsAtABreakpointInsideTheCall() {
        load()
        debugger.setBreakpoint(0x3100)
        debugger.stepOver(cpu)
        assertEquals(0x3100, run())
        assertEquals(Debugger.StopReason.BREAKPOINT, debugger.stopReason)
    }

    @Test
    fun stepOverWaitsForTheSameStackLevel() {
        // $5000 calls itself once, via JSR $5000 at $5005: the recursive call
        // returns to $5008 first, deeper in the stack, which must not count.
        ram.program(0x5000, 0xE8, 0xE0, 0x02, 0xB0, 0x03, 0x20, 0x00, 0x50, 0x60)
        // INX / CPX #2 / BCS +3 / JSR $5000 / RTS
        cpu.loadState(CpuState(programCounter = 0x5005, x = 0, stackPointer = 0xF0))
        debugger.stepOver(cpu)
        assertEquals(0x5008, run())
        assertEquals(0xF0, cpu.stackPointer)
        assertEquals(2, cpu.x)
    }

    @Test
    fun stepOverSkipsAnInterrupt() {
        load(status = StatusFlag.UNUSED) // Interrupts enabled.
        cpu.loadState(cpu.state().copy(programCounter = 0x2003))
        debugger.stepOver(cpu)
        // The IRQ arrives before LDA, and is serviced first.
        assertEquals(0x2005, run { n -> cpu.irqAsserted = n == 0 })
        assertEquals(1, ram.memory[0x10])
        assertEquals(1, cpu.accumulator)
    }

    @Test
    fun stepIntoStopsInAnInterruptHandler() {
        load(status = StatusFlag.UNUSED)
        cpu.irqAsserted = true
        debugger.stepInto()
        assertEquals(0x4000, run())
        assertEquals(StepKind.IRQ, cpu.lastStep)
    }

    @Test
    fun stepOutReturnsToTheCaller() {
        load()
        debugger.setBreakpoint(0x3100)
        run()
        debugger.setBreakpoint(0x3100, on = false)
        debugger.stepOut(cpu)
        assertEquals(0x3004, run())
        debugger.stepOut(cpu)
        assertEquals(0x2003, run())
    }

    @Test
    fun stepOutLeavesAnInterruptHandler() {
        load(status = StatusFlag.UNUSED)
        cpu.irqAsserted = true
        debugger.stepInto()
        run()
        cpu.irqAsserted = false
        debugger.stepOut(cpu)
        assertEquals(0x2000, run())
        assertEquals(1, ram.memory[0x10])
    }

    @Test
    fun stepOutCopesWithTheStackWrapping() {
        // At SP = $00 the RTS pulls from $0101-$0102 and leaves SP = $02.
        ram.program(0x0101, 0x02, 0x20) // returns to $2003
        ram.program(0x3000, 0x60)
        ram.program(0x2003, 0xEA)
        cpu.loadState(CpuState(programCounter = 0x3000, stackPointer = 0x00))
        debugger.stepOut(cpu)
        assertEquals(0x2003, run())
    }

    @Test
    fun runToStopsAtTheAddress() {
        load()
        debugger.runTo(0x3101)
        assertEquals(0x3101, run())
        assertEquals(Debugger.StopReason.RUN_TO, debugger.stopReason)
        assertEquals(1, cpu.y)
    }

    @Test
    fun stepIntoAHaltedCpuStopsAfterOneStep() {
        ram.program(0x2000, 0x02) // JAM
        cpu.loadState(CpuState(programCounter = 0x2000))
        cpu.step()
        debugger.stepInto()
        assertEquals(0x2001, run())
        assertEquals(StepKind.HALTED, cpu.lastStep)
    }

    @Test
    fun summarisesTheRegisters() {
        val state = CpuState(accumulator = 0x41, x = 0xFF, y = 0, stackPointer = 0xFD, programCounter = 0x3FB6, status = 0xB5)
        assertEquals("Nv-BdIzC", state.flags)
        assertEquals("PC=3FB6 A=41 X=FF Y=00 SP=FD P=B5 Nv-BdIzC", state.summary)
    }
}
