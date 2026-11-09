// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

/**
 * One decoded instruction.
 *
 * [bytes] are the opcode and its operand, 1 to 3 bytes. [target] is the address
 * the operand names, if it names one: for `LDA $1234,X` it is $1234, for
 * `BNE` the branch destination, for `JMP ($0314)` the pointer $0314. Immediate,
 * implied and accumulator instructions have none.
 */
data class DisassembledInstruction(
    /** Where the instruction runs from: the origin, if the listing has one. */
    val address: Int,
    val bytes: List<Int>,
    val mnemonic: String,
    /** As written in assembly, such as `#$41`, `$1234,X` or `($FB),Y`; empty if there is none. */
    val operand: String,
    val target: Int?,
    /** False for the undocumented NMOS opcodes, such as LAX and SLO. */
    val documented: Boolean,
) {
    val length: Int get() = bytes.size

    /** The address of the instruction after this one. */
    val next: Int get() = (address + length) and 0xFFFF

    /** `LDA $1234,X`. */
    val text: String get() = if (operand.isEmpty()) mnemonic else "$mnemonic $operand"

    /**
     * A listing line in the style of a machine code monitor:
     * `C000  BD 34 12  LDA $1234,X`, with `*` before undocumented mnemonics.
     */
    override fun toString(): String {
        val hex = bytes.joinToString(" ") { hex2(it) }.padEnd(8)
        val mark = if (documented) " " else "*"
        return "${hex4(address)}  $hex $mark$text"
    }
}

/**
 * A disassembler for the NMOS 6502, including the undocumented opcodes. It
 * knows nothing about any machine: it reads memory through [peek], which must
 * not have side effects (reading a timer or a keyboard register to show it must
 * not acknowledge it), and asks [label] for a name for each address an operand
 * names, so a machine can show `JSR CHROUT` instead of `JSR $FFD2`.
 *
 * Addresses wrap at 64 KiB, as on the CPU. Bytes after the end of memory come
 * from the start.
 *
 * Code is often stored in one place and copied elsewhere to run, as a
 * decompressor does. Each method takes an `origin`: the address the code will
 * run from. The bytes are read where they are now, but the listing numbers
 * them from the origin, and branch destinations count from it, so they match
 * the code once it is in place. The origin is the address read by default.
 */
class Disassembler(
    private val peek: (address: Int) -> Int,
    private val label: (address: Int) -> String? = { null },
) {
    /** Decodes the instruction stored at [address], as if it were at [origin]. */
    fun instruction(address: Int, origin: Int = address): DisassembledInstruction {
        val start = origin and 0xFFFF
        val opcode = peek(address and 0xFFFF) and 0xFF
        val instruction = InstructionSet.instructions[opcode] ?: error("No instruction for opcode $opcode")
        val mode = instruction.mode
        val bytes = listOf(opcode) + List(operandLength(mode)) { peek((address + 1 + it) and 0xFFFF) and 0xFF }
        val byte = bytes.getOrElse(1) { 0 }
        val word = byte or (bytes.getOrElse(2) { 0 } shl 8)
        val target = when (mode) {
            AddressingMode.IMPLIED, AddressingMode.ACCUMULATOR, AddressingMode.IMMEDIATE -> null
            AddressingMode.ZERO_PAGE, AddressingMode.ZERO_PAGE_X, AddressingMode.ZERO_PAGE_Y,
            AddressingMode.INDEXED_INDIRECT, AddressingMode.INDIRECT_INDEXED -> byte
            AddressingMode.ABSOLUTE, AddressingMode.ABSOLUTE_X, AddressingMode.ABSOLUTE_Y,
            AddressingMode.INDIRECT -> word
            // The offset is signed and counts from the next instruction.
            AddressingMode.RELATIVE -> (start + 2 + byte.toByte()) and 0xFFFF
        }
        // Absolute $0012 keeps four digits: it is a different opcode from zero page $12.
        val name = target?.let { label(it) ?: if (mode.zeroPage) "$" + hex2(it) else "$" + hex4(it) }
        val operand = when (mode) {
            AddressingMode.IMPLIED -> ""
            AddressingMode.ACCUMULATOR -> "A"
            AddressingMode.IMMEDIATE -> "#$" + hex2(byte)
            AddressingMode.ZERO_PAGE, AddressingMode.ABSOLUTE, AddressingMode.RELATIVE -> name!!
            AddressingMode.ZERO_PAGE_X, AddressingMode.ABSOLUTE_X -> "$name,X"
            AddressingMode.ZERO_PAGE_Y, AddressingMode.ABSOLUTE_Y -> "$name,Y"
            AddressingMode.INDIRECT -> "($name)"
            AddressingMode.INDEXED_INDIRECT -> "($name,X)"
            AddressingMode.INDIRECT_INDEXED -> "($name),Y"
        }
        return DisassembledInstruction(start, bytes, instruction.operation.name, operand, target, instruction.documented)
    }

    /** [count] instructions, one after the other, stored from [address], as if they were at [origin]. */
    fun instructions(address: Int, count: Int, origin: Int = address): List<DisassembledInstruction> {
        val result = mutableListOf<DisassembledInstruction>()
        var offset = 0
        while (result.size < count) {
            result += instruction(address + offset, origin + offset).also { offset += it.length }
        }
        return result
    }

    /**
     * The instructions that start from [start] up to and including [end], as
     * if they were at [origin]. The last one can run past [end] by up to two
     * bytes.
     */
    fun range(start: Int, end: Int, origin: Int = start): List<DisassembledInstruction> {
        val result = mutableListOf<DisassembledInstruction>()
        var offset = 0
        val size = ((end - start) and 0xFFFF) + 1
        while (offset < size) {
            result += instruction(start + offset, origin + offset).also { offset += it.length }
        }
        return result
    }

    /** A listing of [range], one [DisassembledInstruction.toString] per line. */
    fun listing(start: Int, end: Int, origin: Int = start): String = range(start, end, origin).joinToString("\n")

    private companion object {
        fun operandLength(mode: AddressingMode): Int = when (mode) {
            AddressingMode.IMPLIED, AddressingMode.ACCUMULATOR -> 0
            AddressingMode.ABSOLUTE, AddressingMode.ABSOLUTE_X, AddressingMode.ABSOLUTE_Y,
            AddressingMode.INDIRECT -> 2
            else -> 1
        }

        val AddressingMode.zeroPage: Boolean
            get() = this == AddressingMode.ZERO_PAGE || this == AddressingMode.ZERO_PAGE_X ||
                this == AddressingMode.ZERO_PAGE_Y || this == AddressingMode.INDEXED_INDIRECT ||
                this == AddressingMode.INDIRECT_INDEXED
    }
}

private const val HEX = "0123456789ABCDEF"

/** The low byte of [value] as two uppercase hex digits. */
internal fun hex2(value: Int): String = "${HEX[(value shr 4) and 0xF]}${HEX[value and 0xF]}"

/** The low 16 bits of [value] as four uppercase hex digits. */
internal fun hex4(value: Int): String = hex2(value shr 8) + hex2(value)
