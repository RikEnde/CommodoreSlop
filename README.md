# C64slop

An emulator of the PAL Commodore 64, in Kotlin Multiplatform. It is built the
same way as its siblings [Beebslop](https://github.com/RikEnde/Beebslop), a BBC
Micro emulator, and [Elkslop](https://github.com/RikEnde/elkslop), an Acorn
Electron emulator: a platform-free emulation core, a Swing desktop front end,
and headless tests that run the real ROMs.

## Run it

```sh
./run.sh                           # open the emulator window
./run.sh --scale 3                 # a 1152 x 816 window
./run.sh --screenshot boot.png     # no window: boot, save a PNG, exit
./run.sh --prg game.prg                              # load and run a program
./run.sh --disk disks/trex64.d64 --type 'LOAD"*",8\n' # load from disk
./run.sh --tape tapes/trex64.tap --type 'LOAD\n'     # load from tape
./run.sh --help
```

`run.sh` builds the desktop jar with Gradle if needed, then runs it. It needs a
JDK 17 or newer: `$JAVA_HOME`, else `.tools/jdk`, else `java` on the `PATH`. The
ROMs are built into the jar, so `desktop/build/libs/c64slop.jar` runs from any
directory without `--roms`; `--roms DIR` reads them from a directory instead.
To run without Gradle or Java installed, see [Standalone app](#standalone-app).

| Option | Meaning |
|---|---|
| `--roms DIR` | Directory with `kernal.rom`, `basic.rom`, `chargen.rom` and `dos1541.rom` (default: built in) |
| `--scale 1\|2\|3` | Window size; 1 is 384 x 272 (default: 2) |
| `--screenshot FILE` | Run without a window, save a PNG of the picture, then exit |
| `--frames N` | 50ths of a second to run before the screenshot (default: 200) |
| `--tape FILE` | Put a TAP cassette image in the datasette |
| `--disk FILE` | Put a D64 disk image in the 1541, drive 8 |
| `--no-drive` | Leave out the 1541, even if `dos1541.rom` is there |
| `--prg FILE` | Load a PRG program after boot, and `RUN` it if it is BASIC |
| `--type TEXT` | Type TEXT after boot; `\n` starts a new line |
| `--joystick 0\|1\|2` | The port the arrow keys and Space work as a joystick; 0 for none, so the arrows are the cursor keys (default: 2) |
| `--no-sound` | Run without sound |
| `--log-keys` | Print each host key event and the C64 key it maps to |

The window shows the picture and runs in real time. **Machine** has Pause
(Cmd+P, or Ctrl+Shift+P off macOS), Reset, Power Cycle, Load Program and a Sound
switch; **Edit** has Paste as Typing; **Keys** has RUN/STOP + RESTORE, keys a
host keyboard lacks, f1-f8 and the keyboard layout; **Tape** inserts, plays,
records, rewinds, ejects and saves cassettes, and turns on Fast Loading;
**Disk** inserts, ejects, write-protects and saves the 1541's disk; **Joystick** chooses the port the arrow keys work; **View** sets the size;
**Help > Keyboard** lists the keys.

### Keyboard

There are two layouts, chosen in **Keys > Keyboard Layout**:

- **Physical** (the default, for games) maps keys by position, so the symbols
  are the C64's own: Shift+2 is `"`, the `-` and `=` keys are `+` and `-`.
- **Natural** (for typing) types the character your own keyboard produces.
  The key pressed last replaces any key still held, so it is less suited to
  games.

| C64 key | Host key |
|---|---|
| RUN/STOP | Esc (Keys menu: RUN/STOP + RESTORE) |
| RESTORE | Page Up or F12 |
| CLR/HOME | Home |
| INST/DEL | Backspace or Delete |
| CTRL | Ctrl or Tab |
| C= | Option/Alt |
| SHIFT LOCK | Caps Lock |
| `£` and `=` | Insert and Page Down |
| `@ * ↑` | `[`, `]` and `\` in the Physical layout |
| `: ;` | `;` and `'` in the Physical layout |
| `←` | `` ` `` |
| f1-f8 | F1-F8 (on a Mac, hold fn if they control the screen or sound) |
| Cursor keys | Arrows, with **Joystick > None**; Left and Up press SHIFT for you |

As on the real machine, a letter typed without SHIFT is a capital, and with
SHIFT a graphics character; C= + SHIFT switches to the lower-case character set.
Paste as Typing types letters in either case as capitals, so listings paste
whatever their case, and skips characters the C64 can't type, such as `{`.

No joystick is needed: the arrow keys act as one in port 2, where most games
read it, and Space is its fire button as well as the C64's own Space, so screens
that say "press SPACE" work too. The title bar shows `[JOYSTICK 2]`. To use the
arrows as the C64's cursor keys, for editing BASIC, choose **Joystick > None**
(or pass `--joystick 0`); **Port 1** is for the few games that read that port.

### Programs

**Machine > Load Program...** (or `--prg`) puts a PRG file in memory, as
`LOAD "NAME",8,1` would, and types `RUN` if it is a BASIC program (one that
loads at `$0801`, which includes most games with a BASIC `SYS` line). For
machine code loaded elsewhere it tells you the address to `SYS`.

### Disks

With `dos1541.rom` (built in, or in the `--roms` directory), a Commodore 1541
is device 8 on the serial bus. It is emulated as the real drive is built: its
own 6502 runs the 1541's DOS ROM, with two 6522 VIAs and a disk surface of GCR
bytes, so the KERNAL's serial routines talk to it as to real hardware, and
loaders that run their own code in the drive can work too.

**Disk > Insert Disk...** puts a D64 image (35 or 40 tracks) in the drive.
`LOAD "$",8` then `LIST` shows the directory, `LOAD "NAME",8` (or `LOAD "*",8`
for the first file) loads a program, and `SAVE "NAME",8` saves one. DOS
commands work as usual: `OPEN 15,8,15,"N:NAME,ID"` formats the disk,
`"S:NAME"` scratches a file. Loading runs at the real speed, about 400 bytes a
second; programs with their own fast loaders are quicker.

Changes go back to the image file when the drive's motor stops, a moment after
the last access, and when the disk is ejected or the window closes. A file you
can't write to is inserted write-protected, and **Write Protect** makes the DOS
refuse to write with `WRITE PROTECT ON`. **New Blank Disk** makes a formatted,
empty disk in memory; **Save Disk As...** writes it to a file, which then gets
its changes. The title bar shows the disk's name and `[DISK]` while the drive
runs.

The drive reads and writes whole bytes, as the DOS does. Copy protections that
rely on bit-level timing, odd track lengths or half-tracks don't work, and the
D64's error bytes are ignored.

[`disks/`](disks/README.md) holds T-Rex 64's released D64.

### Tapes

Cassettes are TAP images, version 0 or 1. **Tape > Insert Tape...** puts one in
the datasette; type `LOAD` and RETURN, and at `PRESS PLAY ON TAPE` choose
**Tape > Play**. Tapes play at their real speed, a few minutes for most
programs; **Tape > Fast Loading** runs flat out while the motor is on. The title
bar shows the tape's name, `[PLAY]` or `[RECORD]`, `[FAST]` and `[MOTOR]`.

To save, choose **Tape > New Blank Tape** (or keep a tape in to record over it
from where it's wound), type `SAVE "NAME"`, and at `PRESS RECORD & PLAY ON TAPE`
choose **Tape > Record**. **Tape > Save Tape As...** then writes the whole tape
to a `.tap` file. **Rewind** lets the keys up, as on the real datasette;
rewind before loading what you just saved.

[`tapes/`](tapes/README.md) holds [T-Rex 64](https://github.com/maciejmalecki/trex64),
an MIT-licensed game by Maciej Małecki, Zuza Małecka and Ola Małecka, recorded
by C64slop's own KERNAL `SAVE`. [tapes/README.md](tapes/README.md) says how to
load and play it:

```sh
./run.sh --tape tapes/trex64.tap --type 'LOAD\n' --joystick 2   # then Tape > Play
```

### Standalone app

```sh
./gradlew :desktop:packageApp    # desktop/build/jpackage/app-image/C64slop.app
./gradlew :desktop:packageDmg    # desktop/build/jpackage/dmg/C64slop-1.0.0.dmg
```

These build an app that needs neither Gradle nor Java: jpackage bundles a
trimmed Java runtime. It contains the ROMs, the tapes in `tapes/` and the disks
in `disks/`, and **Insert Tape** and **Insert Disk** open on those. Disks from
inside the app are never written back to: their changes stay in memory until
**Save Disk As...**, which opens in `~/Documents`. Writing inside the app would
break its signature, and fails anyway in `/Applications`.

jpackage only builds for the OS it runs on, so `packageDmg` works on macOS only;
on Windows and Linux, `packageApp` makes an app folder with the same contents.
It needs a JDK 17 or newer. The app is unsigned (jpackage signs it ad hoc), so
on first launch macOS refuses to open it by double-click: right-click it, choose
**Open**, then **Open** again.

## Platform independence

The emulation core lives in `commonMain` source sets and depends only on the
common Kotlin standard library. It declares JVM and Kotlin/Native macOS, Linux and
Windows targets. Loading ROM files lives in `jvmTest`. Only the JVM tests have
been run so far. The `desktop` front end is JVM-only, because it uses Swing.

## Build and test

Use a JDK 17 or newer. The Gradle wrapper pins Gradle 9.7.0 and verifies its
archive checksum; Kotlin is pinned to 2.4.21.

```sh
./gradlew :cpu:jvmTest :c64:jvmTest :desktop:jvmTest
```

Tests use checked-in fixtures and ROMs; they download nothing.

## CPU module

`cpu/src/commonMain/kotlin/c64slop/cpu` is Beebslop's 6502 core: all 256 NMOS
opcodes, documented and undocumented, with their bus cycles, checked against
60,700 [SingleStepTests](https://github.com/SingleStepTests/65x02) vectors and
[Klaus Dormann's functional test](https://github.com/Klaus2m5/6502_65C02_functional_tests).
See Beebslop's README for the details. The 6510's I/O port and its RDY line are
not in the CPU: they live on the C64's bus ([`ProcessorPort.kt`],
[`C64MemoryBus.kt`]). C64slop adds the SO input (`setOverflow()`), which sets
the V flag: the 1541 wires its byte-ready signal there.

## C64 machine module

The production code is in `c64/src/commonMain/kotlin/c64slop/c64`.

| File | Contents |
|---|---|
| `C64Machine.kt` | One C64: the chips and their wiring, and `screenLines()` for tests |
| `C64Clock.kt` | Machine time in φ2 cycles: 985,248 a second, 19,656 a frame |
| `C64MemoryBus.kt` | The PLA's banking, I/O decoding, and RDY while the VIC-II has the bus |
| `ProcessorPort.kt` | The 6510's port at `$00`/`$01`: ROM banking and the datasette |
| `IoDevice.kt` | The I/O chips' interface, and their places at `$D000-$DFFF` |
| `Cia6526.kt` | The 6526 CIA, used twice: ports, timers A and B, time of day, interrupts |
| `Vic6569.kt` | The PAL VIC-II, run cycle by cycle: text, bitmaps, sprites, borders, raster IRQ |
| `Sid6581.kt` | The 6581 SID: three voices, ADSR envelopes, the filter, and resampling for the host |
| `KeyboardMatrix.kt` | The 8 x 8 keyboard matrix, SHIFT LOCK, RESTORE and the joysticks |
| `KeyboardText.kt` | Which key and SHIFT type each character |
| `Typist.kt` | Presses keys frame by frame, for pasted text and menu chords |
| `Tape.kt` | A cassette as TAP pulses, and the 1530 datasette |
| `Program.kt` | PRG files, and loading one into memory |
| `SerialBus.kt` | The IEC serial bus: ATN, CLK and DATA, wired-AND between the C64 and its devices |
| `Drive1541.kt` | The 1541 disk drive: its 6502, RAM, DOS ROM, two VIAs, head and serial port |
| `Via6522.kt` | The 6522 VIA, copied from Beebslop, used twice in the 1541 |
| `Disk.kt` | A disk as rings of GCR bytes, made from and saved to D64 images |

### Memory map

With no cartridge, as the KERNAL leaves it (`$01` = `$37`):

| Address | Contents |
|---|---|
| `$0000-$0001` | The 6510's I/O port |
| `$0002-$9FFF` | RAM |
| `$A000-$BFFF` | BASIC V2 ROM (RAM beneath) |
| `$C000-$CFFF` | RAM |
| `$D000-$DFFF` | I/O, or the character ROM (RAM beneath) |
| `$E000-$FFFF` | KERNAL ROM (RAM beneath) |

| I/O | Device | Emulated |
|---|---|---|
| `$D000-$D3FF` | VIC-II | Yes, without the light pen |
| `$D400-$D7FF` | SID | Yes; the paddles read `$FF` |
| `$D800-$DBFF` | Color RAM, 4 bits wide | Yes |
| `$DC00-$DCFF` | CIA 1: keyboard, joysticks, IRQ | Yes |
| `$DD00-$DDFF` | CIA 2: serial bus, VIC-II bank, NMI | Yes; a 1541 on the serial bus, nothing on the user port |
| `$DE00-$DFFF` | I/O 1 and 2, for cartridges | Read what the VIC-II last fetched |

### Verification

`c64/src/jvmTest` runs the real ROMs:

- the KERNAL boots to `READY.` with `38911 BASIC BYTES FREE`, frames arrive at
  50 Hz, and its timer interrupt runs the jiffy clock;
- BASIC programs are typed through the keyboard matrix and run, and a program
  loaded into memory runs;
- `LOAD "$",8` with no drive gives `DEVICE NOT PRESENT`;
- with the 1541, its DOS reports `73,CBM DOS V2.6 1541`, and a program is saved
  to a blank disk, listed in the directory and loaded back; a D64 survives the
  trip through GCR unchanged;
- the boot screen's colours, the border and background registers and Color RAM,
  sprites where their coordinates say and their collisions, hi-res bitmap
  mode, and bad lines taking 40 cycles from the CPU;
- the KERNAL saves a program to tape, which goes through a TAP image and loads
  back.

`desktop/src/jvmTest` checks the key mappings, typing in the Natural layout on
the real ROMs, a program loaded and run on the real-time emulator thread and
stopped with RUN/STOP + RESTORE, TAP and PRG files, screenshots and options. It
loads T-Rex 64 from its D64 with `LOAD "*",8`, comparing every byte with the
file on the disk, and runs it; and a `SAVE` reaches the D64 file once the
drive's motor stops.

## License

C64slop is licensed under the [BSD 3-Clause License](LICENSE). Third-party files
keep their own licenses: see [`NOTICE`](NOTICE), [`REUSE.toml`](REUSE.toml) and
[`LICENSES/`](LICENSES/).

## Acknowledgements

See [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).

## ROMs

`roms/` holds the three ROMs the C64 needs, and the 1541's. The desktop build
copies them into its jar and app. They are copyright Commodore Business
Machines and are included without a licence; see
[`LICENSES/LicenseRef-Commodore-ROM.txt`](LICENSES/LicenseRef-Commodore-ROM.txt).

| File | Contents | SHA-1 |
|---|---|---|
| `roms/kernal.rom` | KERNAL, 901227-03, 8 KiB | `1d503e56df85a62fee696e7618dc5b4e781df1bb` |
| `roms/basic.rom` | BASIC V2, 901226-01, 8 KiB | `79015323128650c742a3694c9429aa91f355905e` |
| `roms/chargen.rom` | Character generator, 901225-01, 4 KiB | `adc7c31e18c7c7413d54802ef2f4193da14711aa` |
| `roms/dos1541.rom` | 1541 DOS 2.6, 325302-01 and 901229-05, 16 KiB | `ab16f56989b27d89babe5f89c5a8cb3da71a82f0` |

`dos1541.rom` is VICE's `data/DRIVES/dos1541-325302-01+901229-05.bin`, from
its [Subversion mirror](https://github.com/VICE-Team/svn-mirror). Its two
halves match MAME's `325302-01.uab4` (SHA-1
`8e0547430135ba462525c224e76356bd3d430f11`) and `901229-05 ae.uab5` (SHA-1
`f5d60777440829e46dc91285e662ba072acd2d8b`).

[`ProcessorPort.kt`]: c64/src/commonMain/kotlin/c64slop/c64/ProcessorPort.kt
[`C64MemoryBus.kt`]: c64/src/commonMain/kotlin/c64slop/c64/C64MemoryBus.kt
