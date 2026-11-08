// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.cpu

/**
 * One call represents one CPU bus cycle, including dummy reads and writes.
 * Addresses are unsigned 16-bit values; data is an unsigned 8-bit value.
 *
 * A machine can advance its hardware clock inside these methods. The CPU has
 * no knowledge of RAM, ROM, peripherals, wall-clock time, or the host platform.
 * Implementations must return normally and must not call back into step/reset.
 */
interface MemoryBus {
    fun read(address: Int): Int
    fun write(address: Int, value: Int)
}
