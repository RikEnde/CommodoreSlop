// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import kotlin.test.Test
import kotlin.test.assertEquals

class ArithmeticTest {
    private val memory = IntArray(65536)
    private val cpu = Cpu6502(object : MemoryBus {
        override fun read(address: Int): Int = memory[address]
        override fun write(address: Int, value: Int) { memory[address] = value }
    })

    @Test fun binaryAdcForEveryOperandAndCarryCombination() {
        memory[0] = 0x69
        for (left in 0..255) for (right in 0..255) for (carry in 0..1) {
            memory[1] = right
            cpu.loadState(CpuState(accumulator = left, status = 0x20 or carry))
            cpu.step()
            val sum = left + right + carry
            val signedSum = left.toByte().toInt() + right.toByte().toInt() + carry
            val expectedFlags = flags(sum, sum > 255, signedSum !in -128..127)
            assertEquals(sum and 255, cpu.accumulator, "ADC $left + $right + $carry")
            assertEquals(expectedFlags, cpu.status)
        }
    }

    @Test fun binarySbcForEveryOperandAndCarryCombination() {
        memory[0] = 0xE9
        for (left in 0..255) for (right in 0..255) for (carry in 0..1) {
            memory[1] = right
            cpu.loadState(CpuState(accumulator = left, status = 0x20 or carry))
            cpu.step()
            val difference = left - right - (1 - carry)
            val signedDifference = left.toByte().toInt() - right.toByte().toInt() - (1 - carry)
            val expectedFlags = flags(difference, difference >= 0, signedDifference !in -128..127)
            assertEquals(difference and 255, cpu.accumulator, "SBC $left - $right - ${1 - carry}")
            assertEquals(expectedFlags, cpu.status)
        }
    }

    @Test fun decimalResultsForEveryValidBcdOperandAndCarryCombination() {
        for (opcode in listOf(0x69, 0xE9)) {
            memory[0] = opcode
            for (left in 0..99) for (right in 0..99) for (carry in 0..1) {
                memory[1] = bcd(right)
                cpu.loadState(CpuState(accumulator = bcd(left), status = 0x28 or carry))
                cpu.step()
                val result = if (opcode == 0x69) left + right + carry else left - right - (1 - carry)
                val expectedCarry = if (opcode == 0x69) result >= 100 else result >= 0
                assertEquals(bcd((result + 100) % 100), cpu.accumulator, "BCD opcode $opcode, $left, $right, $carry")
                assertEquals(if (expectedCarry) 1 else 0, cpu.status and StatusFlag.CARRY)
            }
        }
    }

    private fun bcd(value: Int): Int = ((value / 10) shl 4) or (value % 10)

    private fun flags(result: Int, carry: Boolean, overflow: Boolean): Int {
        var flags = StatusFlag.UNUSED
        if (result and 255 == 0) flags = flags or StatusFlag.ZERO
        if (result and 128 != 0) flags = flags or StatusFlag.NEGATIVE
        if (carry) flags = flags or StatusFlag.CARRY
        if (overflow) flags = flags or StatusFlag.OVERFLOW
        return flags
    }
}
