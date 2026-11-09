# Resume prompt: C64slop

Paste everything below the line into a new session started in
`/Users/rik/projects/commodore/c64`.

---

We are building **C64slop**, a PAL Commodore 64 emulator in Kotlin
Multiplatform, modelled on the BBC Micro emulator at
`/Users/rik/projects/acorn/beebslop`. Follow Beebslop's code style,
architecture and design as closely as possible: a platform-free emulation
core in `commonMain`, a Swing desktop front end, and headless tests that run
the real ROMs. Read Beebslop's README, `bbc/` and `desktop/` sources before
writing desktop code; the C64 files mirror them.

## Rules for this session

- Edit sources with the Write and Edit tools, never with Python or sed, so
  every change is reviewable.
- Write one file at a time and check each is complete before the next. In the
  previous session several writes were cut off part way; if that happens,
  rewrite the file and say so.
- Don't commit or `git init` unless asked. The directory is not a git repo yet.
- Typed BASIC lines in tests must stay under 80 characters, the C64's logical
  line limit; otherwise only the last part of the line runs.

## Building

There is no system Java. Gradle runs offline from `~/.gradle`, which the
sandbox can write. The Kotlin compiler writes to `java.io.tmpdir`, which must
point at `$TMPDIR`. Create this helper and use it for every build:

```sh
cat > $TMPDIR/gw.sh <<'EOF'
#!/bin/sh
export JAVA_HOME=/Users/rik/projects/acorn/beebslop/.tools/jdk
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR -Djava.net.preferIPv4Stack=true"
cd /Users/rik/projects/commodore/c64 && exec ./gradlew --offline -Pkotlin.compiler.execution.strategy=in-process "$@"
EOF
chmod +x $TMPDIR/gw.sh
$TMPDIR/gw.sh :cpu:jvmTest :c64:jvmTest
```

## Done (all tests pass)

- Gradle wrapper (9.7.0), `settings.gradle.kts` (modules `:cpu`, `:c64`,
  `:desktop`), root `build.gradle.kts`, `gradle.properties`, `LICENSE`
  (still says "The Beebslop contributors": change to C64slop), `LICENSES/`.
- `cpu/`: Beebslop's 6502 core and its tests, package `c64slop.cpu`. The
  6510's I/O port and RDY live on the C64's bus, not in the CPU.
- `roms/`: `kernal.rom`, `basic.rom`, `chargen.rom`, which the tests and the
  desktop build use. Whether they stay in the repository is the user's
  decision; ask before publishing anything.
- `c64/src/commonMain/kotlin/c64slop/c64/`:
  - `C64Clock.kt`: φ2 cycles, 985,248 Hz, 19,656 a frame.
  - `C64MemoryBus.kt`: PLA banking (no cartridge), I/O decoding, and RDY:
    reads wait while the VIC-II's BA is low.
  - `IoDevice.kt` (`IoDevice`, `IoRegion`), `ProcessorPort.kt` (`$00`/`$01`).
  - `Cia6526.kt`: ports, timers A/B, TOD, ICR, serial out, FLAG.
  - `Vic6569.kt`: cycle-stepped VIC-II after Christian Bauer's article: bad
    lines, all modes, sprites, borders, raster IRQ, collisions. Picture is
    504 x 312; picture column x is X coordinate x - 100; row y is raster y.
  - `Sid6581.kt`: 3 voices, ADSR, filter, resampling like Beebslop's SN76489.
  - `KeyboardMatrix.kt` (keys, SHIFT LOCK, RESTORE, `Joystick`),
    `KeyboardText.kt`, `Typist.kt`.
  - `Tape.kt` (`Tape` with TAP v0/v1, `Datasette`), `SerialBus.kt` (no
    devices yet), `Program.kt` (PRG parsing and `C64Machine.load`).
  - `C64Machine.kt`: wiring; `screenLines()` reads the text screen.
- `c64/src/jvmTest/`: `MachineHelpers.kt`, `RomBootTest.kt`,
  `VideoRomTest.kt`, `CassetteRomTest.kt` (save then load through the KERNAL).
- `Typist.enqueueAction` runs an action at its place in the queue (used to
  load a PRG after boot, before typing `RUN`).
- `desktop/` (all compile; 18 tests pass, none skipped):
  `AudioOutput.kt`, `MediaFiles.kt` (`TapeFile`, `ProgramFile`),
  `KeyMapping.kt` (Physical and Natural layouts; letters follow the host's
  SHIFT in Natural), `NaturalKeyboard.kt` (keeps left/right SHIFT apart),
  `ScreenPanel.kt` (view `Rectangle(92, 15, 384, 272)`), `Emulator.kt`
  (reference-counted physical keys, RUN/STOP + RESTORE, tape keys, PRG
  loading, fast tape), `Main.kt` (`Options`, `Roms`, screenshot mode),
  `EmulatorWindow.kt` (menus Machine, Edit, Keys, Tape, Joystick, View,
  Help), `AppIcon.kt` (beige breadbin computer), `IconExport.kt`.
  Tests: `KeyMappingTest`, `EmulatorTest`, `MediaFilesTest`, `AppIconTest`,
  helper `TestRoms.kt`.
- `.gitignore`, `.gitattributes`.
- A real 1541 (device 8): `Drive1541.kt` (its own `Cpu6502`, 2 KiB RAM,
  `roms/dos1541.rom`, two `Via6522` copied from Beebslop, wiring per MAME
  `c1541.cpp`), `Disk.kt` (D64 <-> byte-aligned GCR tracks, blank disks),
  `SerialBus` with attached devices, `Cpu6502.setOverflow()` (SO pin, byte
  ready). The drive catches up with the C64 after each C64 cycle. Desktop:
  `--disk`, `--no-drive`, Disk menu, `DiskFile`, saves on motor stop.
  Tests: `DiskDriveRomTest` (c64), `DiskTest` (desktop: T-Rex D64 loads
  byte-exact and runs; SAVE reaches the file). `disks/trex64.d64` shipped.
- `tapes/trex64.tap`: T-Rex 64 (MIT, Maciej Malecki), recorded by our own
  KERNAL SAVE from the release's `rex.prg` and verified to load back; see
  `tapes/README.md`. It plays: title, joystick 2, scrolling and sprites all
  work. (At GET READY it waits for joystick FIRE, not Space.)
- Headless check: `java -jar desktop/build/libs/c64slop.jar --screenshot
  $TMPDIR/boot.png --type '...'` (build with `:desktop:fatJar`).

## To do, in order

1. Not yet tried by hand: the window itself (keys, menus, sound). Ask the
   user to run `./run.sh` and report problems. (`run.sh`, README, NOTICE,
   ACKNOWLEDGEMENTS, REUSE.toml, `LICENSES/LicenseRef-Commodore-ROM.txt` are
   written; `LICENSE` now names the C64slop contributors.)
2. Whether the Commodore ROMs stay in the repository is the user's decision.
4. commonTest unit tests for the CIA, VIC-II registers, SID, memory bus,
   keyboard and TAP format.
5. Later: cartridges; G64 images and bit-level GCR for copy protection; D64
   error bytes; a fast-loader test (e.g. a game with its own drive code).
