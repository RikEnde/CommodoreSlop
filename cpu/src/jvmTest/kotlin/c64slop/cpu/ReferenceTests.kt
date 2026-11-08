// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/** JVM-only fixture I/O; the CPU being tested is entirely common Kotlin. */
class ReferenceTests {
    private data class BusAccess(val address: Int, val value: Int, val kind: String)

    private class RecordingBus : MemoryBus {
        val memory = IntArray(65536)
        val accesses = mutableListOf<BusAccess>()
        override fun read(address: Int): Int = memory[address].also {
            accesses += BusAccess(address, it, "read")
        }
        override fun write(address: Int, value: Int) {
            memory[address] = value
            accesses += BusAccess(address, value, "write")
        }
    }

    @Test fun allOpcodesMatchIndependentRegisterMemoryAndBusVectors() {
        val bus = RecordingBus()
        val cpu = Cpu6502(bus)
        val resource = checkNotNull(javaClass.getResourceAsStream("/reference/6502-vectors.jsonl.gz"))
        var caseCount = 0
        val opcodes = mutableSetOf<Int>()
        GZIPInputStream(resource).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val test = Json.parseToJsonElement(line).jsonObject
                val name = test.getValue("name").jsonPrimitive.content
                val initial = test.getValue("initial").jsonObject
                val expected = test.getValue("final").jsonObject
                bus.memory.fill(0)
                initial.getValue("ram").jsonArray.forEach { pair ->
                    bus.memory[pair.jsonArray[0].jsonPrimitive.int] = pair.jsonArray[1].jsonPrimitive.int
                }
                bus.accesses.clear()
                cpu.loadState(initial.cpuState())
                val cycles = cpu.step()
                assertEquals(expected.cpuState(), cpu.state(), "$name: registers")
                expected.getValue("ram").jsonArray.forEach { pair ->
                    val address = pair.jsonArray[0].jsonPrimitive.int
                    assertEquals(pair.jsonArray[1].jsonPrimitive.int, bus.memory[address], "$name: RAM $address")
                }
                val expectedAccesses = test.getValue("cycles").jsonArray.map { entry ->
                    val cycle = entry.jsonArray
                    BusAccess(cycle[0].jsonPrimitive.int, cycle[1].jsonPrimitive.int, cycle[2].jsonPrimitive.content)
                }
                assertEquals(expectedAccesses.size, cycles, "$name: cycle count")
                assertEquals(expectedAccesses, bus.accesses, "$name: bus trace")
                opcodes += name.substringBefore(' ').toInt(16)
                caseCount++
            }
        }
        assertEquals(60_700, caseCount)
        assertEquals(256, opcodes.size)
    }

    @Test fun klausDormannFunctionalTestReachesSuccessTrap() {
        val memory = checkNotNull(javaClass.getResourceAsStream("/reference/6502_functional_test.bin"))
            .use { it.readBytes() }
        assertEquals(65536, memory.size)
        val cpu = Cpu6502(object : MemoryBus {
            override fun read(address: Int): Int = memory[address].toInt() and 0xFF
            override fun write(address: Int, value: Int) { memory[address] = value.toByte() }
        })
        cpu.loadState(CpuState(programCounter = 0x0400))
        repeat(40_000_000) { instruction ->
            val before = cpu.programCounter
            cpu.step()
            if (cpu.programCounter == before) {
                assertEquals(0x3469, before, "Functional test trapped after $instruction instructions: ${cpu.state()}")
                return
            }
        }
        fail("Functional test did not reach its success trap: ${cpu.state()}")
    }

    private fun JsonObject.cpuState(): CpuState = CpuState(
        accumulator = getValue("a").jsonPrimitive.int,
        x = getValue("x").jsonPrimitive.int,
        y = getValue("y").jsonPrimitive.int,
        stackPointer = getValue("s").jsonPrimitive.int,
        programCounter = getValue("pc").jsonPrimitive.int,
        status = (getValue("p").jsonPrimitive.int or StatusFlag.UNUSED) and StatusFlag.BREAK.inv(),
    )
}
