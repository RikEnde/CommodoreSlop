// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import kotlin.test.*

class Cpu6502Test {
    private data class BusAccess(val address: Int, val value: Int, val write: Boolean = false)

    private class TestBus : MemoryBus {
        val memory = IntArray(65536)
        val accesses = mutableListOf<BusAccess>()
        override fun read(address: Int): Int = memory[address].also {
            accesses += BusAccess(address, it)
        }
        override fun write(address: Int, value: Int) {
            accesses += BusAccess(address, value, write = true)
            memory[address] = value
        }
        fun program(address: Int, vararg bytes: Int) {
            bytes.forEachIndexed { index, byte -> memory[(address + index) and 0xFFFF] = byte }
        }
        fun vector(address: Int, target: Int) {
            memory[address] = target and 0xFF
            memory[address + 1] = target ushr 8
        }
    }

    private val bus = TestBus()
    private val cpu = Cpu6502(bus)

    private fun start(vararg bytes: Int, state: CpuState = CpuState(programCounter = 0x2000)) {
        bus.program(state.programCounter, *bytes)
        cpu.loadState(state)
    }

    @Test fun resetReadsVectorAndStackWithoutWriting() {
        bus.vector(0xFFFC, 0xC000)
        assertEquals(7, cpu.reset())
        assertEquals(0xC000, cpu.programCounter)
        assertEquals(0xFD, cpu.stackPointer)
        assertEquals(listOf(0, 0, 0x100, 0x1FF, 0x1FE, 0xFFFC, 0xFFFD), bus.accesses.map { it.address })
        assertTrue(bus.accesses.none { it.write })
        assertEquals(7L, cpu.totalCycles)
    }

    @Test fun warmResetPreservesRegistersAndDecimalFlag() {
        cpu.loadState(CpuState(accumulator = 42, x = 10, y = 20, status = 0x28))
        bus.vector(0xFFFC, 0x1234)
        cpu.reset()
        assertEquals(CpuState(42, 10, 20, 0xFA, 0x1234, 0x2C), cpu.state())
    }

    @Test fun executesSmallProgramAndStoresResult() {
        start(0xA9, 0x10, 0x18, 0x69, 0x20, 0x85, 0x80)
        assertEquals(listOf(2, 2, 2, 3), List(4) { cpu.step() })
        assertEquals(0x30, bus.memory[0x80])
        assertEquals(0x2007, cpu.programCounter)
    }

    @Test fun indexedReadAddsPageCrossingCycleAtUncorrectedAddress() {
        start(0xBD, 0xFF, 0x40, state = CpuState(x = 1, programCounter = 0x2000))
        bus.memory[0x4100] = 0x80
        assertEquals(5, cpu.step())
        assertEquals(0x80, cpu.accumulator)
        assertEquals(listOf(0x2000, 0x2001, 0x2002, 0x4000, 0x4100), bus.accesses.map { it.address })
    }

    @Test fun indexedStoreAlwaysDoesDummyRead() {
        start(0x9D, 0x10, 0x40, state = CpuState(accumulator = 42, x = 1, programCounter = 0x2000))
        assertEquals(5, cpu.step())
        assertEquals(BusAccess(0x4011, 0), bus.accesses[3])
        assertEquals(BusAccess(0x4011, 42, true), bus.accesses[4])
    }

    @Test fun zeroPageIndexWraps() {
        start(0xB5, 0xFF, state = CpuState(x = 2, programCounter = 0x2000))
        bus.memory[1] = 0x42
        assertEquals(4, cpu.step())
        assertEquals(0x42, cpu.accumulator)
        assertEquals(listOf(0x2000, 0x2001, 0xFF, 1), bus.accesses.map { it.address })
    }

    @Test fun indirectPointerWrapsWithinZeroPage() {
        start(0xB1, 0xFF, state = CpuState(y = 1, programCounter = 0x2000))
        bus.memory[0xFF] = 0xFF
        bus.memory[0] = 0x40
        bus.memory[0x4100] = 0xA5
        assertEquals(6, cpu.step())
        assertEquals(0xA5, cpu.accumulator)
        assertEquals(listOf(0x2000, 0x2001, 0xFF, 0, 0x4000, 0x4100), bus.accesses.map { it.address })
    }

    @Test fun indirectJumpHasNmosPageWrapBug() {
        start(0x6C, 0xFF, 0x30)
        bus.memory[0x30FF] = 0x78
        bus.memory[0x3000] = 0x56
        bus.memory[0x3100] = 0x99
        assertEquals(5, cpu.step())
        assertEquals(0x5678, cpu.programCounter)
    }

    @Test fun instructionFetchWrapsAtEndOfAddressSpace() {
        start(0xA9, 0x42, state = CpuState(programCounter = 0xFFFF))
        assertEquals(2, cpu.step())
        assertEquals(0x42, cpu.accumulator)
        assertEquals(1, cpu.programCounter)
    }

    @Test fun branchesHaveTwoThreeOrFourCycles() {
        start(0xD0, 0x02, state = CpuState(programCounter = 0x2000, status = 0x22))
        assertEquals(2, cpu.step())
        assertEquals(0x2002, cpu.programCounter)
        start(0xD0, 0xFE)
        assertEquals(3, cpu.step())
        assertEquals(0x2000, cpu.programCounter)
        start(0xD0, 0xFE, state = CpuState(programCounter = 0x20FE))
        bus.accesses.clear()
        assertEquals(4, cpu.step())
        assertEquals(0x20FE, cpu.programCounter)
        assertEquals(listOf(0x20FE, 0x20FF, 0x2100, 0x21FE), bus.accesses.map { it.address })
    }

    @Test fun readModifyWriteWritesOriginalThenModifiedByte() {
        start(0x06, 0x80)
        bus.memory[0x80] = 0x81
        assertEquals(5, cpu.step())
        assertEquals(listOf(BusAccess(0x80, 0x81, true), BusAccess(0x80, 2, true)), bus.accesses.filter { it.write })
        assertEquals(1, cpu.status and StatusFlag.CARRY)
    }

    @Test fun subroutinePushesReturnAddressBeforeFetchingHighOperand() {
        start(0x20, 0x00, 0x30)
        bus.memory[0x3000] = 0x60
        assertEquals(6, cpu.step())
        assertEquals(listOf(0x2000, 0x2001, 0x1FD, 0x1FD, 0x1FC, 0x2002), bus.accesses.map { it.address })
        assertEquals(0x20, bus.memory[0x1FD])
        assertEquals(0x02, bus.memory[0x1FC])
        assertEquals(6, cpu.step())
        assertEquals(0x2003, cpu.programCounter)
        assertEquals(0xFD, cpu.stackPointer)
    }

    @Test fun stackWrapsWithinPageOne() {
        start(0x48, 0x68, state = CpuState(accumulator = 0x80, stackPointer = 0, programCounter = 0x2000))
        assertEquals(3, cpu.step())
        assertEquals(0xFF, cpu.stackPointer)
        assertEquals(0x80, bus.memory[0x100])
        assertEquals(4, cpu.step())
        assertEquals(0, cpu.stackPointer)
        assertEquals(0x80, cpu.accumulator)
    }

    @Test fun brkPushesPcPlusTwoAndBreakFlagThenRtiRestores() {
        start(0x00, 0xEA, state = CpuState(programCounter = 0x2000, status = 0x29))
        bus.vector(0xFFFE, 0x3000)
        bus.memory[0x3000] = 0x40
        assertEquals(7, cpu.step())
        assertEquals(0x20, bus.memory[0x1FD])
        assertEquals(0x02, bus.memory[0x1FC])
        assertEquals(0x39, bus.memory[0x1FB])
        assertEquals(0x2D, cpu.status)
        assertEquals(6, cpu.step())
        assertEquals(0x2002, cpu.programCounter)
        assertEquals(0x29, cpu.status)
    }

    @Test fun irqPushesCurrentPcWithBreakClearAndPreservesDecimal() {
        start(0xEA, state = CpuState(programCounter = 0x2000, status = 0x28))
        bus.vector(0xFFFE, 0x3000)
        cpu.irqAsserted = true
        assertEquals(7, cpu.step())
        assertEquals(0x3000, cpu.programCounter)
        assertEquals(0, bus.memory[0x1FC])
        assertEquals(0x28, bus.memory[0x1FB])
        assertEquals(0x2C, cpu.status)
    }

    @Test fun cliAllowsOneMoreInstructionBeforePendingIrq() {
        start(0x58, 0xEA, 0xEA)
        bus.vector(0xFFFE, 0x3000)
        cpu.irqAsserted = true
        assertEquals(2, cpu.step())
        assertEquals(2, cpu.step())
        assertEquals(0x2002, cpu.programCounter)
        assertEquals(7, cpu.step())
        assertEquals(0x3000, cpu.programCounter)
    }

    @Test fun seiUsesOldInterruptMaskForNextBoundary() {
        start(0x78, 0xEA, state = CpuState(programCounter = 0x2000, status = 0x20))
        bus.vector(0xFFFE, 0x3000)
        cpu.step()
        cpu.irqAsserted = true
        assertEquals(7, cpu.step())
        assertEquals(0x3000, cpu.programCounter)
    }

    @Test fun plpDelaysInterruptMaskButRtiDoesNot() {
        start(0x28, 0xEA)
        bus.memory[0x1FE] = 0x20
        bus.vector(0xFFFE, 0x3000)
        bus.memory[0x3000] = 0x40
        cpu.irqAsserted = true
        assertEquals(4, cpu.step())
        assertEquals(2, cpu.step())
        assertEquals(7, cpu.step())
        assertEquals(6, cpu.step())
        assertEquals(0x2002, cpu.programCounter)
        assertEquals(7, cpu.step())
    }

    @Test fun nmiHasPriorityAndIsConsumedOnce() {
        start(0xEA, state = CpuState(programCounter = 0x2000, status = 0x20))
        bus.vector(0xFFFA, 0x4000)
        bus.vector(0xFFFE, 0x3000)
        bus.memory[0x4000] = 0xEA
        cpu.irqAsserted = true
        cpu.requestNmi()
        assertEquals(7, cpu.step())
        assertEquals(0x4000, cpu.programCounter)
        assertEquals(2, cpu.step())
        assertEquals(0x4001, cpu.programCounter)
    }

    @Test fun statusNormalizesVirtualBits() {
        cpu.loadState(CpuState(status = 0x10))
        assertEquals(0x20, cpu.status)
        start(0x08, 0x28, state = CpuState(programCounter = 0x2000, status = 0xC9))
        cpu.step()
        assertEquals(0xF9, bus.memory[0x1FD])
        cpu.step()
        assertEquals(0xE9, cpu.status)
    }

    @Test fun decimalAdcFlagsComeFromNmosIntermediateResults() {
        start(0x69, 0x50, state = CpuState(accumulator = 0x50, programCounter = 0x2000, status = 0x28))
        assertEquals(2, cpu.step())
        assertEquals(0, cpu.accumulator)
        assertEquals(0xE9, cpu.status) // N/V/C set, Z clear despite final A = 0.
    }

    @Test fun decimalSbcFlagsComeFromBinarySubtraction() {
        start(0xE9, 0x01, state = CpuState(accumulator = 0, programCounter = 0x2000, status = 0x29))
        assertEquals(2, cpu.step())
        assertEquals(0x99, cpu.accumulator)
        assertEquals(0xA8, cpu.status)
    }

    @Test fun strictModeRejectsUndocumentedOpcodeAfterItsFetch() {
        cpu.rejectUndocumentedOpcodes = true
        start(0xA7, 0x10)
        val failure = assertFailsWith<UndocumentedOpcodeException> { cpu.step() }
        assertEquals(0xA7, failure.opcode)
        assertEquals(0x2000, failure.address)
        assertEquals(1L, cpu.totalCycles)
    }

    @Test fun undocumentedOpcodesRunByDefault() {
        bus.memory[0x10] = 0x81
        start(0xA7, 0x10) // LAX $10
        assertEquals(3, cpu.step())
        assertEquals(0x81, cpu.accumulator)
        assertEquals(0x81, cpu.x)
        assertTrue(cpu.status and StatusFlag.NEGATIVE != 0)
    }

    @Test fun jamHaltsUntilResetAndIgnoresInterrupts() {
        bus.vector(0xFFFC, 0x3000)
        start(0x02, 0xEA)
        assertEquals(11, cpu.step())
        assertTrue(cpu.halted)
        assertEquals(0x2001, cpu.programCounter)

        cpu.irqAsserted = true
        cpu.requestNmi()
        bus.accesses.clear()
        assertEquals(1, cpu.step())
        assertEquals(listOf(BusAccess(0xFFFF, bus.memory[0xFFFF])), bus.accesses)
        assertEquals(0x2001, cpu.programCounter)

        cpu.reset()
        assertFalse(cpu.halted)
        assertEquals(0x3000, cpu.programCounter)
    }

    @Test fun registerInitializationRejectsOutOfRangeValues() {
        assertFailsWith<IllegalArgumentException> { CpuState(accumulator = 256) }
        assertFailsWith<IllegalArgumentException> { CpuState(programCounter = -1) }
    }

    @Test fun instructionTableDefinesAllOpcodesAndMarks151Documented() {
        assertTrue(InstructionSet.instructions.all { it != null })
        assertEquals(151, InstructionSet.instructions.count { it!!.documented })
    }
}
