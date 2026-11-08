// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

import c64slop.cpu.MemoryBus

/**
 * The CPU's view of memory, as the PLA decodes it from the processor port's
 * LORAM, HIRAM and CHAREN lines. No cartridge is fitted, so GAME and EXROM are
 * high (*C64 Programmer's Reference Guide*, appendix L; MAME `c64.cpp`):
 *
 * | Address | LORAM HIRAM both 1 | HIRAM only | LORAM only | neither |
 * |---|---|---|---|---|
 * | `$A000-$BFFF` | BASIC | RAM | RAM | RAM |
 * | `$D000-$DFFF` | I/O or char ROM | I/O or char ROM | I/O or char ROM | RAM |
 * | `$E000-$FFFF` | KERNAL | KERNAL | RAM | RAM |
 *
 * CHAREN chooses between I/O (1) and the character ROM (0) at `$D000`. Writes
 * always go to the RAM beneath a ROM; with I/O mapped in, they go to the chips
 * instead. `$00` and `$01` are the 6510's own port; a write there also reaches
 * RAM. Color RAM is 4 bits wide: its upper nibble, and the empty I/O1 and I/O2
 * pages, read whatever the VIC-II last fetched, as the data bus floats.
 *
 * Each read waits while the VIC-II holds BA low, then takes one cycle; each write
 * takes one cycle ([C64Clock]).
 */
class C64MemoryBus(
    val ram: ByteArray,
    val colorRam: ByteArray,
    basicRom: ByteArray,
    kernalRom: ByteArray,
    charRom: ByteArray,
    val port: ProcessorPort,
    val clock: C64Clock,
    private val vic: Vic6569,
) : MemoryBus {
    private val basic = basicRom.copyOf()
    private val kernal = kernalRom.copyOf()
    private val chargen = charRom.copyOf()
    private val devices = arrayOfNulls<IoDevice>(IoRegion.entries.size)

    init {
        require(ram.size == 0x10000) { "RAM must be 64 KiB" }
        require(colorRam.size == 0x400) { "Color RAM must be 1 KiB" }
        require(basic.size == 0x2000) { "BASIC ROM must be 8 KiB" }
        require(kernal.size == 0x2000) { "KERNAL ROM must be 8 KiB" }
        require(chargen.size == 0x1000) { "Character ROM must be 4 KiB" }
    }

    /** Connect a chip to its I/O region; color RAM is part of the bus. */
    fun attach(region: IoRegion, device: IoDevice?) {
        require(region != IoRegion.COLOR_RAM) { "Color RAM is built into the bus" }
        devices[region.ordinal] = device
    }

    override fun read(address: Int): Int {
        // RDY: the CPU stops at a read while the VIC-II wants the bus.
        do clock.cycle() while (vic.baLow)
        return readMapped(address and 0xFFFF, sideEffects = true)
    }

    override fun write(address: Int, value: Int) {
        clock.cycle()
        val location = address and 0xFFFF
        val data = value and 0xFF
        if (location < 2) port.write(location, data)
        if (location in 0xD000..0xDFFF && ioVisible()) {
            writeIo(location, data)
        } else {
            ram[location] = data.toByte()
        }
    }

    /** Debugger read without changing time or device state. */
    fun peek(address: Int): Int = readMapped(address and 0xFFFF, sideEffects = false)

    /** What the PLA maps at [address] now, for tests and debuggers. */
    fun mapping(address: Int): Mapping = when ((address and 0xFFFF) shr 12) {
        0xA, 0xB -> if (port.loram && port.hiram) Mapping.BASIC else Mapping.RAM
        0xD -> when {
            !port.loram && !port.hiram -> Mapping.RAM
            port.charen -> Mapping.IO
            else -> Mapping.CHARACTER_ROM
        }
        0xE, 0xF -> if (port.hiram) Mapping.KERNAL else Mapping.RAM
        else -> Mapping.RAM
    }

    enum class Mapping { RAM, BASIC, KERNAL, CHARACTER_ROM, IO }

    private fun ioVisible() = port.charen && (port.loram || port.hiram)

    private fun readMapped(address: Int, sideEffects: Boolean): Int {
        if (address < 2) return port.read(address)
        return when (mapping(address)) {
            Mapping.RAM -> ram[address].toInt() and 0xFF
            Mapping.BASIC -> basic[address and 0x1FFF].toInt() and 0xFF
            Mapping.KERNAL -> kernal[address and 0x1FFF].toInt() and 0xFF
            Mapping.CHARACTER_ROM -> chargen[address and 0x0FFF].toInt() and 0xFF
            Mapping.IO -> readIo(address, sideEffects)
        }
    }

    private fun readIo(address: Int, sideEffects: Boolean): Int {
        val region = IoRegion.of(address)
        if (region == IoRegion.COLOR_RAM) {
            return (colorRam[address and 0x3FF].toInt() and 0x0F) or (vic.lastFetch and 0xF0)
        }
        val device = devices[region.ordinal] ?: return vic.lastFetch
        val offset = (address - region.first) and (region.registers - 1)
        return if (sideEffects) device.read(offset) else device.peek(offset)
    }

    private fun writeIo(address: Int, value: Int) {
        val region = IoRegion.of(address)
        if (region == IoRegion.COLOR_RAM) {
            colorRam[address and 0x3FF] = (value and 0x0F).toByte()
            return
        }
        devices[region.ordinal]?.write((address - region.first) and (region.registers - 1), value)
    }
}
