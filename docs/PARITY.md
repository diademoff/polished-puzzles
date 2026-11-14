# Parity: the upstream contract

This is the main document of the project. It answers one question:
**what exactly must match upstream, and what are we allowed to change?**

Upstream C is the oracle. Our implementation (UI, renderer, later the
Kotlin core) is correct only if it passes the parity check at the
declared level.

## 1. What must match

| Area | Must match | May differ |
|---|---|---|
| Game rules | always | — |
| State after a move | always | — |
| `canUndo` / `canRedo` / `completed` | always | — |
| `params + seed -> DESC` | always | — |
| `SAVEFILE` / `.sgtp` | always | — |
| `requestKeys` | always | — |
| `presets`, `status`, `htmlHelpTopic` | always | — |
| Draw primitives: geometry | always | — |
| Draw primitives: clip/blitter order | always | — |
| Game board palette (parity mode) | always | — |
| Game board palette (UI mode) | — | always |
| Typography, spacing, shapes | — | always |
| Animations and transitions | — | always |
| Shell: toolbar, menus, dialogs | — | always |
| Accessibility and content descriptions | — | always |

How to read the table: if a row is not in "must match", any difference
is allowed without a separate approval. If it is, the only path is
`DEVIATIONS.md` plus a test.

## 2. Test levels

Every test must declare a level. A test without a level is not a
parity test.

### `strict`

Compared:

- serialized state;
- public flags and strings;
- draw primitives: geometry, order, colors as roles, text;
- clip/unclip and blitter operations.

Used for the renderer and for games marked as `dual-run`.

### `semantic`

Compared:

- state (normalized);
- `canUndo`, `canRedo`, `completed`;
- `status`, `presets`, `requestKeys`;
- `params + seed -> DESC`.

Drawing is not compared. Used for logic and for intermediate port
stages.

### `ui`

Compared: screenshots or visual snapshots with a tolerance.
Shell differences are allowed by definition. The level catches layout
regressions, not rule changes.

## 3. Trace normalization

Without normalization, a strict test is either always red or
meaninglessly green.

- **Coordinates** are in game coordinates. Origin and scale are
  normalized; device pixels never enter the trace.
- **Colors** are compared as roles (`COL_BACKGROUND`,
  `COL_FOREGROUND`, `COL_HIGHLIGHT`, ...) against the reference
  palette. In UI mode the role maps to the theme.
- **Text** is compared as string + anchor + size class. Glyph metrics,
  kerning, and font files are not compared.
- **Float** tolerance is `1e-3`.
- **Clip/unclip** is strict, including order.
- **Blitter** `alloc/free/save/load` is compared as an operation
  sequence.
- **Excluded from the trace:** `postInvalidateOnAnimation`, lifecycle
  events, frame timings, logs.
- **Schema version** is `traceSchema: 1`. It changes only together with
  the normalizer and the goldens.

The executable contract is `docs/adr/0003-strict-draw-traces.md`. In
short: the trace is JSON, numbers are integers in milli-units
(`round(value * 1000)`), colours are palette indices plus the palette
table, and `TraceNormalizer` writes the canonical one-operation-per-line
form used by the goldens. The oracle records it with
`{"op":"trace","state":"..."}` or, for a new game,
`{"op":"traceNew","game":"...","params":"...","seed":"..."}`.

## 4. Seed contract

The seed is a public API of the project. It must stay shared with
upstream forever.

- `random.c` is ported **bit-exact** in `engine/RandomState.kt`. The
  RNG must not change. `RandomParityTest` compares raw streams, every
  bit width, many limits, and encoded-state transfer with the oracle.
- `params + seed -> DESC` is a golden test for every game. For games
  that are still `C`, the value is produced by C and the Kotlin RNG is
  checked separately. `fifteen` now generates its description in
  Kotlin, and `FifteenDescTest` compares it with the goldens and with
  fresh oracle games.
- `DESC` does not change for the same seed.
- Any `DESC` change is a breaking deviation with a separate test.
- `savefile_version` does not break.
- Old `.sgtp` files must load; keep a corpus of old versions.
- Games that use time or platform randomness must have a
  deterministic path for tests.

## 5. Save format

`SAVEFILE` is not an internal format. It is a promise to users
(README: exchange saved states between platforms).

Required properties:

- read and write `.sgtp` in both directions with C;
- preserve move history and the position in that history;
- forward and backward compatibility within supported versions;
- no crash on a corrupt file (the fuzzer checks this).

`engine/SgtpSave.kt` implements the container: ordered records,
repeated `MOVE`/`SOLVE`/`RESTART`, unknown keys, canonical output, and
strict header/version/length checks. Game-specific parameter and
description validation is still C and belongs to Phase 4.
`testdata/saves/` is the corpus. `SaveParityTest` reads and rewrites
every file, then makes the oracle load both versions and compare the
game id. `pegs 5x5random` is excluded because upstream itself cannot
reload the save it writes for that preset; the test pins the exclusion
rather than hiding it.

## 6. Game matrix

A game is ported only after every item passes at the declared level.

| Check | Level | What it catches |
|---|---|---|
| `params + seed -> DESC` | semantic | RNG, parameters, generator |
| New game | semantic | initial state |
| Move sequence | semantic | rules, `canUndo/canRedo` |
| Undo/redo invariants | semantic | state history |
| `completed` | semantic | win condition |
| `requestKeys`, `presets`, `status` | semantic | public strings |
| `.sgtp` from C into Kotlin | strict/semantic | deserialization |
| `.sgtp` from Kotlin into C | strict/semantic | serialization |
| Solver (if present) | semantic | correctness and uniqueness |
| `midend_size` layout | semantic | board size for a viewport |
| Input at the displayed size | semantic | taps, drags, and clicks map to cells |
| Draw primitives | strict | geometry and colors |
| Screenshots | ui | layout and theme |

## 7. Component status

The table is updated in the same PR that changes a status. Only the
owner of the "Authority" column is authoritative; in every other case
C wins.

| Component | Status | Authority |
|---|---|---|
| `midend` | dual-run: semantic subset in `engine/Midend.kt`; shell in C | C |
| `random` | parity: `engine/RandomState.kt`, strict differential | oracle streams |
| `SAVEFILE` container | parity: `engine/SgtpSave.kt`, both directions | C save corpus |
| `sort`, `combi` | parity: `engine/Sort.kt`, `engine/Combinations.kt` | C order |
| `dsf`, `tdq`, `findloop`, `matching`, `tree234` | parity: differential tests | C behavior |
| `grid`, `loopgen`, `latin` | C until the first game consumer (ADR 0006) | C |
| `renderer` | new: Compose display-list renderer; C geometry | C traces |
| games (see the table below) | not started | C |

Statuses: `C` — C only; `dual-run` — both implementations, C decides;
`parity` — the Kotlin implementation passes the matrix; `kotlin` — the
app has no runtime C path for the game. The vendored C may remain as
the oracle, chooser metadata, and saved-game identification. Removing
the C source is not required for `kotlin`; see ADR 0008.

### Game inventory

Measured from the code at the vendored revision. Presets are counted
for the Android build, which compiles with `SMALL_SCREEN` (see
`app/src/main/jni/cmake/platforms/android.cmake`). The count includes
submenu entries when a game groups its presets.

| Game | Display name | Default params | Presets | Solver | Text format | Timed | Platform randomness | Status |
|---|---|---|---:|---|---|---|---|---|
| `blackbox` | Black Box | 8x8, 5 balls | 5 | yes | no | no | no | `C` |
| `bridges` | Bridges | 7x7, Easy | 9 | yes | yes | no | no | `C` |
| `cube` | Cube | 4x4 | 4 | no | no | no | no | `C` |
| `dominosa` | Dominosa | order 6, Basic | 12 | yes | yes | no | no | `C` |
| `fifteen` | Fifteen | 4x4 | 3 | yes | yes | no | no | `kotlin` |
| `filling` | Filling | 13x9 | 3 | yes | yes | no | no | `C` |
| `flip` | Flip | 5x5 Crosses | 6 | yes | yes | no | no | `C` |
| `flood` | Flood | 12x12, 6 colors | 7 | yes | yes | no | no | `C` |
| `galaxies` | Galaxies | 7x7 Normal | 6 | yes | yes | no | no | `C` |
| `guess` | Guess | 6 colors, 4 pegs | 2 | yes | no | no | no | `C` |
| `inertia` | Inertia | 10x8 | 3 | yes | yes | no | no | `C` |
| `keen` | Keen | 6x6 Normal | 10 | yes | no | no | no | `C` |
| `lightup` | Light Up | 7x7 Easy | 9 | yes | yes | no | no | `C` |
| `loopy` | Loopy | 7x7 Easy, Square | 20 | yes | yes | no | no | `C` |
| `magnets` | Magnets | 6x5 Tricky | 8 | yes | yes | no | no | `C` |
| `map` | Map | 20x15, 30 regions | 6 | yes | no | no | no | `C` |
| `mines` | Mines | 9x9, 10 mines | 4 | yes | yes | yes | no | `C` |
| `mosaic` | Mosaic | 10x10, aggressive | 6 | yes | yes | no | no | `C` |
| `net` | Net | 5x5, non-wrapping | 8 | yes | no | no | yes | `C` |
| `netslide` | Netslide | 3x3 | 9 | yes | no | no | no | `C` |
| `palisade` | Palisade | 5x5, regions of 5 | 4 | yes | yes | no | no | `C` |
| `pattern` | Pattern | 15x15 | 5 | yes | yes | no | no | `C` |
| `pearl` | Pearl | 8x8 Tricky | 8 | yes | yes | no | no | `C` |
| `pegs` | Pegs | 7x7 Cross | 9 | no | yes | no | no | `C` |
| `range` | Range | 9x6 | 4 | yes | yes | no | no | `C` |
| `rect` | Rectangles | 7x7 | 5 | yes | yes | no | no | `C` |
| `samegame` | Same Game | 5x5, 3 colors | 5 | no | yes | no | no | `C` |
| `signpost` | Signpost | 4x4, corner start | 6 | yes | yes | no | no | `C` |
| `singles` | Singles | 5x5 Easy | 10 | yes | yes | no | no | `C` |
| `sixteen` | Sixteen | 4x4 | 5 | yes | yes | no | no | `C` |
| `slant` | Slant | 8x8 Easy | 6 | yes | yes | no | no | `C` |
| `solo` | Solo | 3x3, Block | 16 | yes | yes | no | no | `C` |
| `tents` | Tents | 8x8 Easy | 6 | yes | yes | no | no | `C` |
| `towers` | Towers | 5x5 Easy | 7 | yes | yes | no | no | `C` |
| `tracks` | Train Tracks | 8x8 Tricky | 12 | yes | yes | no | no | `C` |
| `twiddle` | Twiddle | 3x3, radius 2 | 8 | yes | yes | no | no | `C` |
| `undead` | Undead | 4x4 Normal | 8 | yes | yes | no | no | `C` |
| `unequal` | Unequal | 4x4 Easy | 12 | yes | yes | no | no | `C` |
| `unruly` | Unruly | 8x8 Trivial | 7 | yes | yes | no | no | `C` |
| `untangle` | Untangle | 10 points | 5 | yes | no | no | no | `C` |

Notes:

- `nullgame` is built by upstream but has no display name and is not in
  the playable game list. The oracle and the app exclude it.
- `SMALL_SCREEN` removes the largest preset in `loopy`, `mines`, `net`,
  and `rect`. The Android presets therefore differ from a desktop
  upstream build. This is recorded as deviation D-002 in
  `docs/DEVIATIONS.md` and pinned by `PresetDeviationTest`. The oracle
  is compiled with the same definitions, so the parity comparison is
  between the app and the same engine.
- `mines` is the only timed game. The timer affects the clock, not
  `DESC`, the move history, or the save file.
- `net` asks the frontend for a random seed when it jumbles tiles. The
  oracle returns a fixed seed, so the oracle path is deterministic.
- `fifteen` is the first port target (`ROADMAP.md`, Phase 4). Its
  Kotlin generator, moves, history, solver, text, status, keys, save
  file, and drawing pass the matrix in `FifteenDescTest`,
  `FifteenGameTest`, `FifteenSaveTest`, and `FifteenTraceTest`. The app
  selects `KotlinGameEngine` for every `fifteen` game, so the status is
  `kotlin`. The C game remains in the vendored tree for the oracle and
  chooser metadata; it is not a runtime backend. The seams are
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0008-run-fifteen-on-kotlin.md`.


## 8. Goldens

- Goldens are text (JSON/JSON.gz), not PNG.
- Every golden has provenance: game, parameters, seed, upstream
  commit, oracle protocol.
- `testdata/manifest.json` stores the `sha256` and the version. The
  test `GoldenManifestTest` fails when a hash or the file list drifts.
- Regenerate only with `tools/gen-goldens.sh`, and review the diff.
- Strict draw traces are canonical text in `testdata/trace/<game>/`.
  They are generated with `tools/gen-traces.sh` and compared by
  `StrictTraceTest`. Every game and every preset is frozen with the
  shared seed.
- The save corpus is `testdata/saves/`: the initial state of every
  readable preset plus `fifteen` cases for `MOVE`, undo with redo
  history, and `SOLVE`. It is generated with `tools/gen-saves.sh`.
- Never update a golden to "fix" a red test. Find the cause first,
  then add a `DEVIATIONS.md` entry or fix the code.

## 9. When the oracle disagrees

1. Reproduce with a minimal test.
2. Classify: our bug, upstream bug, or intentional deviation.
3. Our bug — fix it and pin it.
4. Upstream bug — file an issue or a patch; do not edit a vendored
   file silently.
5. Deviation — add a `DEVIATIONS.md` entry and a test that pins it.

The parity suite is always green. A red suite blocks the merge.
