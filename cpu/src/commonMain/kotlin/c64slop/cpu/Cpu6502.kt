// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

import c64slop.cpu.AddressingMode.*
import c64slop.cpu.Operation.*

/**
 * Portable NMOS 6502, executing one whole instruction (or interrupt) at a time.
 *
 * Bus operations are emitted in order, including dummy accesses. Scheduling and
 * machine-specific wait states belong to the machine, not to this class. On the
 * C64, the 6510's RDY input (the VIC-II's BA) is modelled by the bus holding up
 * reads, and the 6510's I/O port at $00/$01 is part of the machine's bus.
 * This is not a pin-level model: mid-instruction reset and interrupt-vector
 * hijacking are not implemented. See README for the interrupt sampling contract.
 *
 * All 256 opcodes execute, including the undocumented ones, as on the real chip.
 * Set [rejectUndocumentedOpcodes] to throw [UndocumentedOpcodeException] instead,
 * which is useful for catching a program (or emulator) that ran into data.
 */
class Cpu6502(private val bus: MemoryBus) {
    var accumulator: Int = 0
        private set
    var x: Int = 0
        private set
    var y: Int = 0
        private set
    var stackPointer: Int = 0
        private set
    var programCounter: Int = 0
        private set
    var status: Int = StatusFlag.UNUSED or StatusFlag.INTERRUPT_DISABLE
        private set

    /** CPU bus cycles since construction; excludes machine-inserted wait states. */
    var totalCycles: Long = 0
        private set

    /** Strict mode: throw after fetching an undocumented opcode instead of running it. */
    var rejectUndocumentedOpcodes: Boolean = false

    /**
     * True after a JAM opcode. A halted CPU ignores IRQ and NMI; each [step]
     * performs one idle read of $FFFF so the machine's clock keeps running.
     * Only [reset] (or [loadState]) restarts it.
     */
    var halted: Boolean = false
        private set

    /** Level-sensitive input. The machine must release it when the source clears. */
    var irqAsserted: Boolean = false

    private var nmiPending = false
    private var irqMasked = true

    /** Call once per active NMI edge. Multiple edges before service coalesce. */
    fun requestNmi() {
        nmiPending = true
    }

    /**
     * The SO (set overflow) input: call once per falling edge, and V is set.
     * The 1541 wires its byte-ready signal here, and its DOS waits for each
     * byte from the disk with `BVC *`.
     */
    fun setOverflow() {
        setFlag(StatusFlag.OVERFLOW, true)
    }

    fun state(): CpuState = CpuState(accumulator, x, y, stackPointer, programCounter, status)

    /**
     * Initialize registers for a debugger/test. No bus cycles are emitted.
     * Clears pending NMI and resamples I; leaves IRQ input and cycle count alone.
     * This is deliberately not an exact save/restore of interrupt pipeline state.
     */
    fun loadState(state: CpuState) {
        accumulator = state.accumulator
        x = state.x
        y = state.y
        stackPointer = state.stackPointer
        programCounter = state.programCounter
        loadStatus(state.status)
        irqMasked = flag(StatusFlag.INTERRUPT_DISABLE)
        nmiPending = false
        halted = false
    }

    /**
     * Seven-cycle reset sequence. Stack reads replace the interrupt's writes.
     * A/X/Y and D survive a warm reset, as on the NMOS chip. Power-on register
     * values here are deterministic conveniences, not hardware guarantees.
     */
    fun reset(): Int {
        read(programCounter)
        read(programCounter)
        repeat(3) {
            read(0x0100 or stackPointer)
            stackPointer = (stackPointer - 1) and 0xFF
        }
        setFlag(StatusFlag.INTERRUPT_DISABLE, true)
        programCounter = readVector(0xFFFC)
        irqMasked = true
        nmiPending = false
        halted = false
        return 7
    }

    /** Returns the number of CPU bus cycles consumed. */
    fun step(): Int {
        val startCycles = totalCycles
        if (halted) {
            read(0xFFFF)
        } else if (nmiPending) {
            nmiPending = false
            interrupt(0xFFFA)
        } else if (irqAsserted && !irqMasked) {
            interrupt(0xFFFE)
        } else {
            val instructionAddress = programCounter
            val opcode = fetchByte()
            val instruction = InstructionSet.instructions[opcode]!!
            if (!instruction.documented && rejectUndocumentedOpcodes) {
                throw UndocumentedOpcodeException(opcode, instructionAddress)
            }
            val previousInterruptDisable = flag(StatusFlag.INTERRUPT_DISABLE)
            execute(instruction)

            // These instructions change I after the interrupt polling point.
            irqMasked = when (instruction.operation) {
                CLI, SEI, PLP -> previousInterruptDisable
                else -> flag(StatusFlag.INTERRUPT_DISABLE)
            }
        }
        return (totalCycles - startCycles).toInt()
    }

    private fun execute(instruction: Instruction) {
        val (operation, mode) = instruction
        when (operation) {
            ADC -> addWithCarry(readOperand(mode))
            SBC -> subtractWithCarry(readOperand(mode))
            AND -> accumulator = updateNegativeAndZero(accumulator and readOperand(mode))
            ORA -> accumulator = updateNegativeAndZero(accumulator or readOperand(mode))
            EOR -> accumulator = updateNegativeAndZero(accumulator xor readOperand(mode))
            LDA -> accumulator = updateNegativeAndZero(readOperand(mode))
            LDX -> x = updateNegativeAndZero(readOperand(mode))
            LDY -> y = updateNegativeAndZero(readOperand(mode))
            STA -> write(resolveAddress(mode, Access.WRITE), accumulator)
            STX -> write(resolveAddress(mode, Access.WRITE), x)
            STY -> write(resolveAddress(mode, Access.WRITE), y)
            CMP -> compare(accumulator, readOperand(mode))
            CPX -> compare(x, readOperand(mode))
            CPY -> compare(y, readOperand(mode))
            BIT -> {
                val value = readOperand(mode)
                setFlag(StatusFlag.ZERO, accumulator and value == 0)
                setFlag(StatusFlag.NEGATIVE, value and 0x80 != 0)
                setFlag(StatusFlag.OVERFLOW, value and 0x40 != 0)
            }
            ASL -> modify(mode) { shiftLeft(it) }
            LSR -> modify(mode) { shiftRight(it) }
            ROL -> modify(mode) { rotateLeft(it) }
            ROR -> modify(mode) { rotateRight(it) }
            INC -> modify(mode) { it + 1 }
            DEC -> modify(mode) { it - 1 }
            BCC -> branch(!flag(StatusFlag.CARRY))
            BCS -> branch(flag(StatusFlag.CARRY))
            BEQ -> branch(flag(StatusFlag.ZERO))
            BNE -> branch(!flag(StatusFlag.ZERO))
            BMI -> branch(flag(StatusFlag.NEGATIVE))
            BPL -> branch(!flag(StatusFlag.NEGATIVE))
            BVC -> branch(!flag(StatusFlag.OVERFLOW))
            BVS -> branch(flag(StatusFlag.OVERFLOW))
            JMP -> jump(mode)
            JSR -> jumpToSubroutine()
            RTS -> returnFromSubroutine()
            BRK -> softwareInterrupt()
            RTI -> returnFromInterrupt()
            PHA -> { impliedCycle(); push(accumulator) }
            PHP -> { impliedCycle(); push(status or StatusFlag.BREAK) }
            PLA -> {
                impliedCycle()
                read(0x0100 or stackPointer)
                accumulator = updateNegativeAndZero(pull())
            }
            PLP -> {
                impliedCycle()
                read(0x0100 or stackPointer)
                loadStatus(pull())
            }
            NOP -> if (mode == IMPLIED) executeImplied(NOP) else readOperand(mode)

            // Undocumented. Each combined operation sets flags in sequence, so
            // the second operation's flags win where they overlap.
            SLO -> accumulator = updateNegativeAndZero(accumulator or modify(mode) { shiftLeft(it) })
            RLA -> accumulator = updateNegativeAndZero(accumulator and modify(mode) { rotateLeft(it) })
            SRE -> accumulator = updateNegativeAndZero(accumulator xor modify(mode) { shiftRight(it) })
            RRA -> addWithCarry(modify(mode) { rotateRight(it) })
            DCP -> compare(accumulator, modify(mode) { it - 1 })
            ISC -> subtractWithCarry(modify(mode) { it + 1 })
            SAX -> write(resolveAddress(mode, Access.WRITE), accumulator and x)
            LAX -> {
                accumulator = updateNegativeAndZero(readOperand(mode))
                x = accumulator
            }
            ANC -> {
                accumulator = updateNegativeAndZero(accumulator and readOperand(mode))
                setFlag(StatusFlag.CARRY, accumulator and 0x80 != 0)
            }
            ALR -> accumulator = updateNegativeAndZero(shiftRight(accumulator and readOperand(mode)))
            ARR -> andRotateRight(readOperand(mode))
            SBX -> {
                val value = readOperand(mode)
                val masked = accumulator and x
                setFlag(StatusFlag.CARRY, masked >= value)
                x = updateNegativeAndZero(masked - value)
            }
            LAS -> {
                val value = readOperand(mode) and stackPointer
                accumulator = updateNegativeAndZero(value)
                x = value
                stackPointer = value
            }
            ANE -> accumulator = updateNegativeAndZero((accumulator or UNSTABLE_MAGIC) and x and readOperand(mode))
            LXA -> {
                accumulator = updateNegativeAndZero((accumulator or UNSTABLE_MAGIC) and readOperand(mode))
                x = accumulator
            }
            SHA -> storeAndHighByte(mode, accumulator and x)
            SHX -> storeAndHighByte(mode, x)
            SHY -> storeAndHighByte(mode, y)
            TAS -> {
                stackPointer = accumulator and x
                storeAndHighByte(mode, stackPointer)
            }
            JAM -> jam()
            else -> executeImplied(operation)
        }
    }

    private fun shiftLeft(value: Int): Int {
        setFlag(StatusFlag.CARRY, value and 0x80 != 0)
        return (value shl 1) and 0xFF
    }

    private fun shiftRight(value: Int): Int {
        setFlag(StatusFlag.CARRY, value and 1 != 0)
        return value ushr 1
    }

    private fun rotateLeft(value: Int): Int {
        val carryIn = carryBit()
        setFlag(StatusFlag.CARRY, value and 0x80 != 0)
        return ((value shl 1) or carryIn) and 0xFF
    }

    private fun rotateRight(value: Int): Int {
        val carryIn = carryBit() shl 7
        setFlag(StatusFlag.CARRY, value and 1 != 0)
        return (value ushr 1) or carryIn
    }

    private fun executeImplied(operation: Operation) {
        impliedCycle()
        when (operation) {
            CLC -> setFlag(StatusFlag.CARRY, false)
            SEC -> setFlag(StatusFlag.CARRY, true)
            CLD -> setFlag(StatusFlag.DECIMAL, false)
            SED -> setFlag(StatusFlag.DECIMAL, true)
            CLI -> setFlag(StatusFlag.INTERRUPT_DISABLE, false)
            SEI -> setFlag(StatusFlag.INTERRUPT_DISABLE, true)
            CLV -> setFlag(StatusFlag.OVERFLOW, false)
            DEX -> x = updateNegativeAndZero(x - 1)
            DEY -> y = updateNegativeAndZero(y - 1)
            INX -> x = updateNegativeAndZero(x + 1)
            INY -> y = updateNegativeAndZero(y + 1)
            TAX -> x = updateNegativeAndZero(accumulator)
            TAY -> y = updateNegativeAndZero(accumulator)
            TSX -> x = updateNegativeAndZero(stackPointer)
            TXA -> accumulator = updateNegativeAndZero(x)
            TYA -> accumulator = updateNegativeAndZero(y)
            TXS -> stackPointer = x
            NOP -> Unit
            else -> error("Not an implied instruction: $operation")
        }
    }

    private enum class Access { READ, WRITE, MODIFY }

    private fun readOperand(mode: AddressingMode): Int = read(resolveAddress(mode, Access.READ))

    private fun resolveAddress(mode: AddressingMode, access: Access): Int = when (mode) {
        IMMEDIATE -> {
            val address = programCounter
            programCounter = (programCounter + 1) and 0xFFFF
            address
        }
        ZERO_PAGE -> fetchByte()
        ZERO_PAGE_X -> zeroPageIndexed(x)
        ZERO_PAGE_Y -> zeroPageIndexed(y)
        ABSOLUTE -> fetchWord()
        ABSOLUTE_X -> indexedAddress(fetchWord(), x, access)
        ABSOLUTE_Y -> indexedAddress(fetchWord(), y, access)
        INDEXED_INDIRECT -> {
            val operand = fetchByte()
            read(operand)
            readZeroPageWord((operand + x) and 0xFF)
        }
        INDIRECT_INDEXED -> indexedAddress(readZeroPageWord(fetchByte()), y, access)
        else -> error("Addressing mode $mode has no ordinary operand address")
    }

    private fun zeroPageIndexed(index: Int): Int {
        val base = fetchByte()
        read(base)
        return (base + index) and 0xFF
    }

    private fun indexedAddress(base: Int, index: Int, access: Access): Int {
        val address = (base + index) and 0xFFFF
        if (access != Access.READ || differentPage(base, address)) {
            // The low-byte addition happens before the carry reaches the high byte.
            read((base and 0xFF00) or (address and 0xFF))
        }
        return address
    }

    /** Returns the new value, already masked to a byte. */
    private inline fun modify(mode: AddressingMode, transform: (Int) -> Int): Int {
        if (mode == ACCUMULATOR) {
            impliedCycle()
            accumulator = updateNegativeAndZero(transform(accumulator))
            return accumulator
        }
        val address = resolveAddress(mode, Access.MODIFY)
        val original = read(address)
        write(address, original) // NMOS read-modify-write always writes twice.
        val result = updateNegativeAndZero(transform(original))
        write(address, result)
        return result
    }

    /**
     * SHA/SHX/SHY/TAS store a register ANDed with (base high byte + 1). If the
     * index crosses a page, the stored value also replaces the address's high
     * byte, because the carry into the high byte is never applied.
     */
    private fun storeAndHighByte(mode: AddressingMode, register: Int) {
        val base = when (mode) {
            ABSOLUTE_X, ABSOLUTE_Y -> fetchWord()
            INDIRECT_INDEXED -> readZeroPageWord(fetchByte())
            else -> error("Addressing mode $mode is not used by SHA/SHX/SHY/TAS")
        }
        val indexed = indexedAddress(base, if (mode == ABSOLUTE_X) x else y, Access.WRITE)
        val value = register and ((base ushr 8) + 1)
        val address = if (differentPage(base, indexed)) (value shl 8) or (indexed and 0xFF) else indexed
        write(address, value)
    }

    /**
     * JAM stops the CPU's instruction sequencer. The address bus then idles at
     * $FFFF/$FFFE; this matches the first 11 cycles recorded in the reference
     * vectors, after which [step] emits one $FFFF read per call until reset.
     */
    private fun jam() {
        read(programCounter)
        read(0xFFFF)
        read(0xFFFE)
        read(0xFFFE)
        repeat(6) { read(0xFFFF) }
        halted = true
    }

    /** ARR: AND, then ROR A; flags come from the adder, and NMOS D mode adjusts. */
    private fun andRotateRight(operand: Int) {
        val masked = accumulator and operand
        val carryIn = carryBit()
        var result = (masked ushr 1) or (carryIn shl 7)
        if (!flag(StatusFlag.DECIMAL)) {
            updateNegativeAndZero(result)
            setFlag(StatusFlag.CARRY, result and 0x40 != 0)
            setFlag(StatusFlag.OVERFLOW, ((result ushr 6) xor (result ushr 5)) and 1 != 0)
            accumulator = result
            return
        }
        setFlag(StatusFlag.NEGATIVE, carryIn != 0)
        setFlag(StatusFlag.ZERO, result == 0)
        setFlag(StatusFlag.OVERFLOW, (masked xor result) and 0x40 != 0)
        if ((masked and 0x0F) + (masked and 0x01) > 5) result = (result and 0xF0) or ((result + 6) and 0x0F)
        val carry = (masked and 0xF0) + (masked and 0x10) > 0x50
        setFlag(StatusFlag.CARRY, carry)
        if (carry) result = (result + 0x60) and 0xFF
        accumulator = result
    }

    private fun branch(condition: Boolean) {
        val displacement = fetchByte().toByte().toInt()
        if (!condition) return
        read(programCounter)
        val destination = (programCounter + displacement) and 0xFFFF
        if (differentPage(programCounter, destination)) {
            read((programCounter and 0xFF00) or (destination and 0xFF))
        }
        programCounter = destination
    }

    private fun jump(mode: AddressingMode) {
        val address = fetchWord()
        programCounter = if (mode == INDIRECT) {
            // Original 6502 JMP ($xxFF) reads its high byte from $xx00.
            val low = read(address)
            val high = read((address and 0xFF00) or ((address + 1) and 0xFF))
            low or (high shl 8)
        } else address
    }

    private fun jumpToSubroutine() {
        val low = fetchByte()
        read(0x0100 or stackPointer)
        push(programCounter ushr 8)
        push(programCounter and 0xFF)
        val high = fetchByte()
        programCounter = low or (high shl 8)
    }

    private fun returnFromSubroutine() {
        impliedCycle()
        read(0x0100 or stackPointer)
        val low = pull()
        val high = pull()
        val returnAddress = low or (high shl 8)
        read(returnAddress)
        programCounter = (returnAddress + 1) and 0xFFFF
    }

    private fun softwareInterrupt() {
        fetchByte() // BRK skips a padding byte, even though its opcode is one byte.
        push(programCounter ushr 8)
        push(programCounter and 0xFF)
        push(status or StatusFlag.BREAK)
        setFlag(StatusFlag.INTERRUPT_DISABLE, true)
        programCounter = readVector(0xFFFE)
    }

    private fun interrupt(vector: Int) {
        read(programCounter)
        read(programCounter)
        push(programCounter ushr 8)
        push(programCounter and 0xFF)
        push(status) // B is clear for hardware interrupts, set only by PHP/BRK.
        setFlag(StatusFlag.INTERRUPT_DISABLE, true)
        programCounter = readVector(vector)
        irqMasked = true
    }

    private fun returnFromInterrupt() {
        impliedCycle()
        read(0x0100 or stackPointer)
        loadStatus(pull())
        val low = pull()
        val high = pull()
        programCounter = low or (high shl 8)
    }

    private fun addWithCarry(value: Int) {
        val original = accumulator
        val carry = carryBit()
        val binarySum = original + value + carry
        if (!flag(StatusFlag.DECIMAL)) {
            setFlag(StatusFlag.CARRY, binarySum > 0xFF)
            setFlag(StatusFlag.OVERFLOW, (original xor binarySum) and (value xor binarySum) and 0x80 != 0)
            accumulator = updateNegativeAndZero(binarySum)
            return
        }

        // NMOS decimal flags do not simply describe the final BCD result.
        // Z uses the binary sum; N/V use the low-digit-adjusted intermediate.
        var lowDigit = (original and 0x0F) + (value and 0x0F) + carry
        if (lowDigit >= 10) lowDigit = ((lowDigit + 6) and 0x0F) + 0x10
        var decimalSum = (original and 0xF0) + (value and 0xF0) + lowDigit
        setFlag(StatusFlag.ZERO, binarySum and 0xFF == 0)
        setFlag(StatusFlag.NEGATIVE, decimalSum and 0x80 != 0)
        setFlag(StatusFlag.OVERFLOW, (original xor decimalSum) and (value xor decimalSum) and 0x80 != 0)
        if (decimalSum >= 0xA0) decimalSum += 0x60
        setFlag(StatusFlag.CARRY, decimalSum > 0xFF)
        accumulator = decimalSum and 0xFF
    }

    private fun subtractWithCarry(value: Int) {
        val original = accumulator
        val borrow = 1 - carryBit()
        val difference = original - value - borrow
        setFlag(StatusFlag.CARRY, difference >= 0)
        setFlag(StatusFlag.OVERFLOW, (original xor value) and (original xor difference) and 0x80 != 0)
        accumulator = updateNegativeAndZero(difference)
        if (!flag(StatusFlag.DECIMAL)) return

        // NMOS SBC keeps N/Z/V from the binary subtraction, even in decimal mode.
        var lowDigit = (original and 0x0F) - (value and 0x0F) - borrow
        if (lowDigit < 0) lowDigit = ((lowDigit - 6) and 0x0F) - 0x10
        var decimalDifference = (original and 0xF0) - (value and 0xF0) + lowDigit
        if (decimalDifference < 0) decimalDifference -= 0x60
        accumulator = decimalDifference and 0xFF
    }

    private fun compare(register: Int, value: Int) {
        setFlag(StatusFlag.CARRY, register >= value)
        updateNegativeAndZero(register - value)
    }

    private fun flag(mask: Int): Boolean = status and mask != 0
    private fun carryBit(): Int = if (flag(StatusFlag.CARRY)) 1 else 0

    private fun setFlag(mask: Int, enabled: Boolean) {
        status = if (enabled) status or mask else status and mask.inv()
    }

    private fun updateNegativeAndZero(value: Int): Int {
        val byte = value and 0xFF
        setFlag(StatusFlag.ZERO, byte == 0)
        setFlag(StatusFlag.NEGATIVE, byte and 0x80 != 0)
        return byte
    }

    private fun loadStatus(value: Int) {
        // B and the unused bit are stack-image conventions, not mutable latches.
        status = (value or StatusFlag.UNUSED) and StatusFlag.BREAK.inv()
    }

    private fun impliedCycle() { read(programCounter) }
    private fun differentPage(first: Int, second: Int): Boolean = (first xor second) and 0xFF00 != 0

    private fun fetchByte(): Int {
        val value = read(programCounter)
        programCounter = (programCounter + 1) and 0xFFFF
        return value
    }

    private fun fetchWord(): Int {
        val low = fetchByte()
        return low or (fetchByte() shl 8)
    }

    private fun readZeroPageWord(address: Int): Int {
        val low = read(address)
        return low or (read((address + 1) and 0xFF) shl 8)
    }

    private fun readVector(address: Int): Int {
        val low = read(address)
        return low or (read(address + 1) shl 8)
    }

    private fun push(value: Int) {
        write(0x0100 or stackPointer, value)
        stackPointer = (stackPointer - 1) and 0xFF
    }

    private fun pull(): Int {
        stackPointer = (stackPointer + 1) and 0xFF
        return read(0x0100 or stackPointer)
    }

    private fun read(address: Int): Int {
        val value = bus.read(address and 0xFFFF) and 0xFF
        totalCycles++
        return value
    }

    private fun write(address: Int, value: Int) {
        bus.write(address and 0xFFFF, value and 0xFF)
        totalCycles++
    }

    private companion object {
        /**
         * ANE and LXA OR the accumulator with a chip-dependent constant (it also
         * varies with temperature). $EE is the most common value and the one used
         * by the reference vectors. Programs relying on it are unreliable on real
         * hardware too.
         */
        const val UNSTABLE_MAGIC = 0xEE
    }
}
