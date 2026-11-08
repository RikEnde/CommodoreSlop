// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * A PRG file: a two-byte load address, low byte first, then the bytes to put
 * there. This is how the KERNAL saves a file to tape or disk.
 */
class Program(val loadAddress: Int, val data: ByteArray) {
    val endAddress: Int get() = loadAddress + data.size

    /** True for a BASIC program, which loads at `$0801`. */
    val isBasic: Boolean get() = loadAddress == BASIC_START

    companion object {
        const val BASIC_START = 0x0801

        /** @throws IllegalArgumentException if [image] is too short or runs past `$FFFF`. */
        fun parse(image: ByteArray): Program {
            require(image.size >= 3) { "A PRG file needs a load address and at least one byte" }
            val address = (image[0].toInt() and 0xFF) or ((image[1].toInt() and 0xFF) shl 8)
            val data = image.copyOfRange(2, image.size)
            require(address + data.size <= 0x10000) { "The program runs past the end of memory" }
            return Program(address, data)
        }
    }
}

/**
 * Put [program] in RAM as `LOAD "NAME",8,1` would, without a drive: the bytes
 * at their load address, and BASIC's end-of-program pointers (`$2D-$32`) and
 * the KERNAL's end address (`$AE-$AF`) just past them. A BASIC program can then
 * be `RUN`; machine code needs its `SYS` address. Best called at the `READY.`
 * prompt.
 */
fun C64Machine.load(program: Program) {
    program.data.copyInto(ram, program.loadAddress)
    val end = program.endAddress
    for (pointer in listOf(0x2D, 0x2F, 0x31, 0xAE)) {
        ram[pointer] = (end and 0xFF).toByte()
        ram[pointer + 1] = (end shr 8).toByte()
    }
}
