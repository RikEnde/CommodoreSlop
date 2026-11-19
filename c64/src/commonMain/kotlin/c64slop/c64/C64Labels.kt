// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * Names for the addresses C64 software calls and pokes most: the KERNAL jump
 * table at the top of memory, the 6510's port, the KERNAL's RAM vectors in page
 * 3, and the I/O chips. A debugger or disassembler can show `JSR CHROUT`
 * instead of `JSR $FFD2`, and `STA CIA1_PRA` instead of `STA $DC00`. Adapted
 * from Beebslop's `BbcLabels`.
 *
 * The KERNAL routine, port and vector names are Commodore's, as given in the
 * Commodore 64 Programmer's Reference Guide (see ACKNOWLEDGEMENTS.md). The
 * 6526 register names are the chip maker's (`PRA`, `TA_LO`, `ICR`, and so
 * on); the `CIA1_` and `CIA2_` prefixes that say which chip are C64slop's own.
 * The VIC-II, SID and colour RAM are named only at their base. Only each
 * register's first address is named, not the copies above it in its block.
 */
object C64Labels {
    private val ciaRegisters = listOf(
        "PRA", "PRB", "DDRA", "DDRB", "TA_LO", "TA_HI", "TB_LO", "TB_HI",
        "TOD_10THS", "TOD_SEC", "TOD_MIN", "TOD_HR", "SDR", "ICR", "CRA", "CRB",
    )

    private val names: Map<Int, String> = buildMap {
        // The KERNAL jump table.
        putAll(mapOf(
            0xFF81 to "CINT", 0xFF84 to "IOINIT", 0xFF87 to "RAMTAS", 0xFF8A to "RESTOR",
            0xFF8D to "VECTOR", 0xFF90 to "SETMSG", 0xFF93 to "SECOND", 0xFF96 to "TKSA",
            0xFF99 to "MEMTOP", 0xFF9C to "MEMBOT", 0xFF9F to "SCNKEY", 0xFFA2 to "SETTMO",
            0xFFA5 to "ACPTR", 0xFFA8 to "CIOUT", 0xFFAB to "UNTLK", 0xFFAE to "UNLSN",
            0xFFB1 to "LISTEN", 0xFFB4 to "TALK", 0xFFB7 to "READST", 0xFFBA to "SETLFS",
            0xFFBD to "SETNAM", 0xFFC0 to "OPEN", 0xFFC3 to "CLOSE", 0xFFC6 to "CHKIN",
            0xFFC9 to "CHKOUT", 0xFFCC to "CLRCHN", 0xFFCF to "CHRIN", 0xFFD2 to "CHROUT",
            0xFFD5 to "LOAD", 0xFFD8 to "SAVE", 0xFFDB to "SETTIM", 0xFFDE to "RDTIM",
            0xFFE1 to "STOP", 0xFFE4 to "GETIN", 0xFFE7 to "CLALL", 0xFFEA to "UDTIM",
            0xFFED to "SCREEN", 0xFFF0 to "PLOT", 0xFFF3 to "IOBASE",
        ))
        // The 6510's on-chip port.
        put(0x0000, "D6510")
        put(0x0001, "R6510")
        // The KERNAL's RAM vectors.
        putAll(mapOf(
            0x0314 to "CINV", 0x0316 to "CBINV", 0x0318 to "NMINV", 0x031A to "IOPEN",
            0x031C to "ICLOSE", 0x031E to "ICHKIN", 0x0320 to "ICKOUT", 0x0322 to "ICLRCH",
            0x0324 to "IBASIN", 0x0326 to "IBSOUT", 0x0328 to "ISTOP", 0x032A to "IGETIN",
            0x032C to "ICLALL", 0x032E to "USRCMD", 0x0330 to "ILOAD", 0x0332 to "ISAVE",
        ))
        // I/O, as IoRegion maps it.
        put(IoRegion.VIC.first, "VIC")
        put(IoRegion.SID.first, "SID")
        put(IoRegion.COLOR_RAM.first, "COLOR_RAM")
        for ((i, name) in ciaRegisters.withIndex()) {
            put(IoRegion.CIA1.first + i, "CIA1_$name")
            put(IoRegion.CIA2.first + i, "CIA2_$name")
        }
    }

    /** The name for [address], or null. Fits the `label` parameter of `Disassembler`. */
    fun forAddress(address: Int): String? = names[address and 0xFFFF]
}
