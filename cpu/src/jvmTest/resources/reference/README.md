# Independent test fixtures

These are test inputs, not production code. They are stored locally so test runs
are repeatable without a network connection. Upstream licenses are included.

## SingleStepTests / 65x02

Repository: https://github.com/SingleStepTests/65x02

Pinned commit: `2f6980a2d95757486c7bee24355c360e40e2a224`

`6502-vectors.jsonl.gz` contains entries from each of the 256 opcode files
`6502/v1/xx.json`, taken from the start of each upstream array:

- all 10,000 entries of `69.json` (ADC), `e9.json` (SBC) and `6b.json` (ARR);
- the first 1,000 entries of `0b.json`, `2b.json` (ANC), `4b.json` (ALR),
  `8b.json` (ANE), `ab.json` (LXA) and `cb.json` (SBX);
- the first 100 entries of every other file.

Ordering is ascending opcode, then upstream array order. Total: 60,700 cases.
The vectors for ANE and LXA imply the magic constant `$EE`. The JAM vectors
record the first 11 bus cycles after the opcode.

Each original JSON object is preserved semantically, serialized as compact JSON
on one line, followed by a newline, then gzip-compressed with mtime zero.
The runner interprets virtual status bits B/U as stack conventions and compares
the remaining register state, final RAM, and the complete ordered bus trace.

Fixture SHA-256:
`b03005a6db2c6b766f2b7aca4543737fb8f81e2d405369f954a1d817c58f356f`

License: MIT, Copyright (c) 2024 Thomas Harte et al. See `single-step-LICENSE.txt`.
These vectors derive from an independent implementation; they are not physical
silicon measurements and are not exhaustive across all processor inputs.

## Klaus Dormann functional test

Repository: https://github.com/Klaus2m5/6502_65C02_functional_tests

Pinned commit: `7954e2dbb49c469ea286070bf46cdd71aeb29e4b`

`6502_functional_test.bin` is the unmodified upstream
`bin_files/6502_functional_test.bin`. The associated assembly source is included
as `6502_functional_test.a65`. Start PC: $0400. Success trap: $3469.

Binary SHA-256:
`fa12bfc761e6f9057e4cc01a665a7b800ff01ae91f598af1e39a1201d01953fd`

Source SHA-256:
`f2665bd02288866c2b210b908e3f387926b4c9f0e0af5ad5513c474361ad1265`

License: GPL-3.0; see the upstream source header and `klaus-LICENSE.txt`.
The fixture and its source/license are kept together. The test runner loads the
binary into an emulated RAM image; it is not included in the production library.
