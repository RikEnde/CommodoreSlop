// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.c64

/**
 * The serial (IEC) bus to disk drives and printers: ATN, CLK and DATA, each
 * an open-collector line that is high unless something pulls it low.
 *
 * The C64 drives its side from CIA 2's port A through 7406 inverters, so
 * writing 1 to bit 3, 4 or 5 pulls ATN, CLK or DATA low, and reads CLK and
 * DATA back directly on bits 6 and 7 (*C64 Programmer's Reference Guide*,
 * appendix L). Only the computer drives ATN. Each [Device] can pull CLK and
 * DATA low too. With no device attached, a command to device 8 ends in
 * `?DEVICE NOT PRESENT ERROR`, as on a C64 with no drive switched on.
 */
class SerialBus {
    /** Something on the bus besides the computer, such as a [Drive1541]. */
    interface Device {
        val clkPulled: Boolean
        val dataPulled: Boolean
    }

    private val devices = mutableListOf<Device>()

    var atnPulled = false; private set
    var clkPulled = false; private set
    var dataPulled = false; private set

    fun attach(device: Device) { devices += device }

    /** Set the C64's outputs from CIA 2's port A levels. */
    fun drive(portA: Int) {
        atnPulled = portA and 0x08 != 0
        clkPulled = portA and 0x10 != 0
        dataPulled = portA and 0x20 != 0
    }

    /** ATN is low: the computer is sending a command. */
    val atnAsserted: Boolean get() = atnPulled

    /** Line levels: high unless anything pulls them low. */
    val clk: Boolean get() = !clkPulled && devices.none { it.clkPulled }
    val data: Boolean get() = !dataPulled && devices.none { it.dataPulled }
}
