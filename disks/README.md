# Disks

Disk images that ship with C64slop, as D64 files for the 1541. **Disk > Insert
Disk...** opens here.

## trex64.d64: T-Rex 64

[T-Rex 64](https://maciejmalecki.itch.io/trex64), the game that is also on
[`tapes/trex64.tap`](../tapes/README.md): a side-scroller by the Lockdown studio
(2020-2022), with code, graphics, music and sound effects by Maciej Małecki,
and level design and graphics by Zuza Małecka and Ola Małecka. Its source is on
[GitHub](https://github.com/maciejmalecki/trex64) under the MIT License,
Copyright (c) 2021 Maciej Malecki; the license is in
[`trex64-LICENSE.txt`](trex64-LICENSE.txt).

To play it:

1. **Disk > Insert Disk...** and choose `trex64.d64`.
2. Type `LOAD "*",8` and press RETURN. It loads in about a minute, at the
   KERNAL's real speed. `LOAD "$",8` then `LIST` shows the directory instead.
3. At `READY.`, type `RUN`.
4. Choose **Joystick > Port 2**: the arrow keys move the joystick and Space
   fires. See [`tapes/README.md`](../tapes/README.md) for the controls.

`./run.sh --disk disks/trex64.d64 --type 'LOAD"*",8\n' --joystick 2` does the
first two steps for you.

The emulator writes changes back to a disk image file. Make a copy first if you
want to keep the original, or use **Disk > Write Protect**.

### Provenance

`rex.d64` from `trex.zip` in the
[1.0.2 release](https://github.com/maciejmalecki/trex64/releases/tag/1.0.2),
unchanged.

| File | SHA-1 |
|---|---|
| `trex64.d64` | `8f1d14734f49c445109ad31707fa6599d057f16c` |
