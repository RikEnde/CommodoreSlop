# Acknowledgements

C64slop exists because many people spent years working out how the Commodore 64
behaves, and wrote it down. This is a record of the work it relies on. It will
grow as the emulator does.

## The machine

The Commodore 64 was designed at Commodore and MOS Technology, and launched in
1982. Among its designers, Robert Russell and Robert Yannes worked on the system,
Al Charpentier and Charles Winterble designed the VIC-II, and Robert Yannes the
SID. Its BASIC V2 came from Microsoft's 6502 BASIC; the KERNAL is Commodore's.

## Documentation

- **Christian Bauer**: *The MOS 6567/6569 video controller (VIC-II) and its
  application in the Commodore 64* (1996), the basis of our VIC-II: bad lines,
  the sprite and border sequences, and the timing of every cycle of a line.
- The *Commodore 64 Programmer's Reference Guide*: the memory map, the PLA's
  banking (appendix L), the keyboard matrix (appendix C) and the serial bus.
- The MOS 6526 CIA and 6581 SID datasheets.
- **Philip "Pepto" Timmermann**: the Colodore palette, measured from a PAL C64.

## Emulators

- **[MAME](https://github.com/mamedev/mame)**, BSD-3-Clause: the C64 driver
  (`c64.cpp`), the 6526 CIA (`mos6526.cpp`) and the 1541 (`c1541.cpp`), by
  Curt Coder, for the chips' wiring and register behavior, and the 6522 VIA
  (`6522via.cpp`), by Peter Trauner and Mathis Rosenhauer, through Beebslop.
  No MAME code is used. Its ROM hashes confirmed our 1541 ROM.
- **[reSID](https://en.wikipedia.org/wiki/ReSID)**, by Dag Lem: the SID's
  envelope rate periods and exponential decay, as its documentation describes
  them. reSID is GPL, and none of its code is used here.
- **[VICE](https://vice-emu.sourceforge.io/)**, by its many contributors: a
  reference for how the machine should behave, for the TAP cassette image
  format, which began with Per Håkan Sundell's CCS64, and for the D64 disk
  image format. Our 1541 ROM image comes from its source tree. VICE is GPL, and
  none of its code is used here.

## Tapes and disks

- **Maciej Małecki, Zuza Małecka and Ola Małecka**:
  [T-Rex 64](https://github.com/maciejmalecki/trex64), released under the MIT
  License, which made it possible to ship a game; see
  [`tapes/README.md`](tapes/README.md) and [`disks/README.md`](disks/README.md).

## 6502 tests and references

The CPU comes from [Beebslop](https://github.com/RikEnde/Beebslop), which took it
from [Elkslop](https://github.com/RikEnde/elkslop), and with it the work it was
checked against:

- **Thomas Harte and contributors**:
  [SingleStepTests 65x02](https://github.com/SingleStepTests/65x02) (MIT), whose
  per-cycle vectors check every one of our 256 opcodes.
- **Klaus Dormann**:
  [6502 functional test](https://github.com/Klaus2m5/6502_65C02_functional_tests)
  (GPL-3.0), included as a test fixture.
- **Norbert Landsteiner**:
  [6502 instruction set reference](https://www.masswerk.at/6502/6502_instruction_set.html),
  including the undocumented opcodes and their names.
- **Neil Parker**: [6502 opcode encoding](https://llx.com/Neil/a2/opcodes.html).
- **Michael Steil**: [how the 6502 decodes instructions](https://www.pagetable.com/31).

Licenses and pinned versions for the test fixtures are in
[`cpu/src/jvmTest/resources/reference/README.md`](cpu/src/jvmTest/resources/reference/README.md).

If we've missed anyone, or credited something wrongly, please tell us.
