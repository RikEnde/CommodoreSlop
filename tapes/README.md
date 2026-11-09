# Tapes

Cassette images that ship with C64slop, as TAP files. **Tape > Insert Tape...**
opens here.

## trex64.tap: T-Rex 64

[T-Rex 64](https://maciejmalecki.itch.io/trex64) is a side-scrolling game by
the Lockdown studio (2020-2022): a T-Rex runs through three worlds, jumping over
cactuses, lava and water and ducking under birds. Code, graphics, music and sound
effects are by Maciej Małecki; level design and graphics by Zuza Małecka and Ola
Małecka. Its source is on [GitHub](https://github.com/maciejmalecki/trex64)
under the MIT License, Copyright (c) 2021 Maciej Malecki; the license is in
[`trex64-LICENSE.txt`](trex64-LICENSE.txt).

To play it:

1. **Tape > Insert Tape...** and choose `trex64.tap`.
2. Type `LOAD` and press RETURN; at `PRESS PLAY ON TAPE`, choose **Tape > Play**.
3. After `FOUND T-REX 64`, press C= (Option/Alt) or wait a few seconds. Loading
   takes about 6½ minutes at real speed; **Tape > Fast Loading** is much quicker.
4. At `READY.`, type `RUN`.
5. Choose **Joystick > Port 2**: the arrow keys move the joystick and Space
   fires. FIRE or Up jumps, Down ducks; FIRE also starts each level at `GET
   READY!`. On the title screen, f1 switches to keyboard control instead (Space
   jumps, C= ducks), f3 turns the music off and f5 chooses the starting level.

`./run.sh --tape tapes/trex64.tap --type 'LOAD\n' --joystick 2` does the first
steps for you: choose **Tape > Play** when the prompt appears.

### Provenance

The game came as `rex.prg` in `trex.zip` from the
[1.0.2 release](https://github.com/maciejmalecki/trex64/releases/tag/1.0.2)
(SHA-1 of `rex.prg`: `65a43fefb3868b27da4e55f7bce28eaca73b6430`). The release
has no tape image, so C64slop made one the way a C64 owner would have: it put
the program in memory, typed `SAVE "T-REX 64"` and recorded the KERNAL's output
on a blank tape. A fresh machine then loaded it back from the TAP file, byte for
byte. The game supports tape: it is a single file.

| File | SHA-1 |
|---|---|
| `trex64.tap` | `bb890e6419a75f9b1a4a94927f5c391a54409222` |
