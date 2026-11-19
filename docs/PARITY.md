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
  checked separately. `fifteen` and `slant` generate their descriptions
  in Kotlin; `FifteenDescTest` and `SlantDescTest` compare them with the
  goldens and with fresh oracle games.
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
strict header/version/length checks. `AUXINFO` is obfuscated and
hex-encoded as upstream does; `AuxInfo` ports that masking, and
`SlantSaveTest` pins it. Game-specific parameter and description
validation is still C and belongs to Phase 4.
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
| Input at the displayed size | semantic | taps, swipes, drags, and clicks map to cells |
| Draw primitives | strict | geometry and colors |
| Screenshots | ui | layout and theme |

## 7. Component status

The table is updated in the same PR that changes a status. Only the
owner of the "Authority" column is authoritative; in every other case
C wins.

| Component | Status | Authority |
|---|---|---|
| `midend` | dual-run: semantic subset in `engine/Midend.kt`; the C shell serves C backends, and `KotlinGameEngine` builds the dialogs, the preferences page, and the generation path for a Kotlin backend | C |
| `random` | parity: `engine/RandomState.kt`, strict differential | oracle streams |
| `SAVEFILE` container | parity: `engine/SgtpSave.kt`, both directions | C save corpus |
| `sort`, `combi` | parity: `engine/Sort.kt`, `engine/Combinations.kt` | C order |
| `dsf`, `tdq`, `findloop`, `matching`, `tree234` | parity: differential tests | C behavior |
| `divvy` | parity: `engine/Divvy.kt`, differential test | C behavior |
| `laydomino` | parity: `engine/LayDomino.kt`, differential test | C behavior |
| `grid` | parity: `engine/Grid.kt`, `engine/Penrose.kt`, `engine/Hat.kt`, `engine/Spectre.kt`; `GridParityTest` compares the description generator, validation, `grid_new`, size, nearest edge, and incentre for all 18 types, including the aperiodic Penrose, hat, and spectre patches | C behavior |
| `loopgen` | parity: `engine/Loopgen.kt`, `LoopgenParityTest` (`none` and `count`); `pearl` is the first consumer and its bias is pinned by `PearlBiasTest` (incremental versus from-scratch on every call) and by the `DESC`/`AUXINFO` goldens | C behavior |
| `latin` | parity: `engine/Latin.kt`, strict differential test; keen uses the full callback path | C behavior |
| `renderer` | new: Compose display-list renderer; C geometry | C traces |
| games (see the table below) | mixed: `fifteen`, `samegame`, `flood`, and `pegs` are `kotlin` (first enablement wave, 2026-11-18); `sixteen`, `flip`, `twiddle`, and `guess` are `kotlin` (second enablement wave, 2026-11-18); `blackbox`, `lightup`, `undead`, and `rect` are `kotlin` (third enablement wave, 2026-11-18); `slant`, `range`, `towers`, and `unequal` are `kotlin` (fourth enablement wave, 2026-11-18); `magnets`, `pattern`, `mosaic`, `signpost`, and `untangle` are `kotlin` (fifth enablement wave, 2026-11-18); `galaxies`, `bridges`, `unruly`, `filling`, and `mines` are `kotlin` (sixth enablement wave, 2026-11-18); `netslide`, `palisade`, `inertia`, `tents`, `net`, `tracks`, `dominosa`, `map`, `keen`, `singles`, `pearl`, and `loopy` are `kotlin` (seventh enablement wave, 2026-11-18); `solo` and `cube` are `kotlin` (eighth enablement wave, 2026-11-19), so every ported game is `kotlin` and the other games are `C` | the game inventory below |

Statuses: `C` — C only; `dual-run` — both implementations, C decides;
`parity` — the Kotlin implementation passes the matrix; `kotlin` — the
app has no runtime C path for the game. The vendored C may remain as
the oracle, chooser metadata, and saved-game identification. Removing
the C source is not required for `kotlin`; see ADR 0008.

The owner waived the two-release wait for the ported games on
2026-11-18 (decision 1a). A ported game still becomes `kotlin` only in
an enablement wave (`docs/adr/0011-enable-games-in-waves.md`), so the
older "two-release wait" sentences in the per-game notes name the gate
that the waiver replaced; they do not enable a game.

### Seam item S9: the current-key label

`Game.currentKeyLabel` is a tested helper, and S9 is not applicable to
the Android shell: this frontend never displays a current key label.
Only the Emscripten frontend reads `midend_current_key_label`
(`emcc.c:310-311`); `android.c` has no call, and the shell's on-screen
keys are the static `key_label.name` values that
`midend_request_keys_by_game` returns (`android.c:778-806`). The helper
stays because another frontend or a future on-screen hint may use it.
The per-game S9 sentences below name this decision.

### Seam item D-01: the spoken board

`Game.spokenBoard` is an accessibility operation that upstream does not
have, recorded as D-035. It is outside the parity matrix: it reads the
state and the UI and writes neither, so no rule, seed, `DESC`,
`AUXINFO`, `UI`, `TIME`, or `.sgtp` value depends on it, and the palette,
the geometry, and the draw primitives are untouched. The operation is a
pure function of one state, one UI, and the tile size, so a spoken table
under `testdata/spoken/` is reviewed like a golden while the game's
`DESC` and trace goldens stay independent of it.

Wave 3 added the shared colour, digit, and pencil vocabulary
(`engine/SpokenColour.kt`, `digitSpeech`, `pencilDigits`) and the twelve
games `samegame`, `flood`, `filling`, `pegs`, `lightup`, `magnets`,
`range`, `towers`, `unequal`, `keen`, `undead`, and `dominosa`. Wave 4
added the fifteen games whose units are not all cells: the edges of
`loopy` and `palisade`, the points of `untangle`, the palette and pegs
of `guess`, the laser ring of `blackbox`, the moving bands of
`netslide`, and the cells of `slant`, `pearl`, `tracks`, `bridges`,
`map`, `net`, `inertia`, `galaxies`, and `pattern`. Wave 5 added the
last two: `solo` speaks the digit and pencil grammar, the killer cage
sums, and the cursor with its pencil mode with the two pointer actions
the game has; `cube` speaks its squares and the roll each square's own
rectangle makes, and a triangle board names its row and triangle
instead of a rectangular position, because the other three solids have
no rectangle. Every ported backend now has the operation. A game name
is spoken from its own state, never from a pixel, and a move that a
single unit cannot name (a drag, an edge midpoint, a keypad digit) is
left on the input path instead of being invented. The unit models, the
gesture limits, and the per-game cap are in `docs/DESIGN.md` section 11;
the tests are in `docs/TESTING.md`; the deviation is D-035.

### Game inventory

Measured from the code at the vendored revision. Presets are counted
for the Android build, which compiles with `SMALL_SCREEN` (see
`app/src/main/jni/cmake/platforms/android.cmake`). The count includes
submenu entries when a game groups its presets.

| Game | Display name | Default params | Presets | Solver | Text format | Timed | Platform randomness | Status |
|---|---|---|---:|---|---|---|---|---|
| `blackbox` | Black Box | 8x8, 5 balls | 5 | yes | no | no | no | `kotlin` |
| `bridges` | Bridges | 7x7, Easy | 9 | yes | yes | no | no | `kotlin` |
| `cube` | Cube | 4x4 | 4 | no | no | no | no | `kotlin` (D-036) |
| `dominosa` | Dominosa | order 6, Basic | 12 | yes | yes | no | no | `kotlin` |
| `fifteen` | Fifteen | 4x4 | 3 | yes | yes | no | no | `kotlin` |
| `filling` | Filling | 13x9 | 3 | yes | yes | no | no | `kotlin` |
| `flip` | Flip | 5x5 Crosses | 6 | yes | yes | no | no | `kotlin` |
| `flood` | Flood | 12x12, 6 colors | 7 | yes | yes | no | no | `kotlin` |
| `galaxies` | Galaxies | 7x7 Normal | 6 | yes | yes | no | no | `kotlin` |
| `guess` | Guess | 6 colors, 4 pegs | 2 | yes | no | no | no | `kotlin` |
| `inertia` | Inertia | 10x8 | 3 | yes | yes | no | no | `kotlin` |
| `keen` | Keen | 6x6 Normal | 10 | yes | no | no | no | `kotlin` |
| `lightup` | Light Up | 7x7 Easy | 9 | yes | yes | no | no | `kotlin` |
| `loopy` | Loopy | 7x7 Easy, Square | 20 | yes | yes | no | no | `kotlin` |
| `magnets` | Magnets | 6x5 Tricky | 8 | yes | yes | no | no | `kotlin` |
| `map` | Map | 20x15, 30 regions | 6 | yes | no | no | no | `kotlin` |
| `mines` | Mines | 9x9, 10 mines | 4 | yes | yes | yes | no | `kotlin` |
| `mosaic` | Mosaic | 10x10, aggressive | 6 | yes | yes | no | no | `kotlin` |
| `net` | Net | 5x5, non-wrapping | 8 | yes | no | no | yes | `kotlin` |
| `netslide` | Netslide | 3x3 | 9 | yes | no | no | no | `kotlin` |
| `palisade` | Palisade | 5x5, regions of 5 | 4 | yes | yes | no | no | `kotlin` |
| `pattern` | Pattern | 15x15 | 5 | yes | yes | no | no | `kotlin` |
| `pearl` | Pearl | 8x8 Tricky | 8 | yes | yes | no | no | `kotlin` |
| `pegs` | Pegs | 7x7 Cross | 9 | no | yes | no | no | `kotlin` |
| `range` | Range | 9x6 | 4 | yes | yes | no | no | `kotlin` |
| `rect` | Rectangles | 7x7 | 5 | yes | yes | no | no | `kotlin` |
| `samegame` | Same Game | 5x5, 3 colors | 5 | no | yes | no | no | `kotlin` |
| `signpost` | Signpost | 4x4, corner start | 6 | yes | yes | no | no | `kotlin` |
| `singles` | Singles | 5x5 Easy | 10 | yes | yes | no | no | `kotlin` |
| `sixteen` | Sixteen | 4x4 | 5 | yes | yes | no | no | `kotlin` |
| `slant` | Slant | 8x8 Easy | 6 | yes | yes | no | no | `kotlin` |
| `solo` | Solo | 3x3, Block | 16 | yes | yes | no | no | `kotlin` (D-025, no parity) |
| `tents` | Tents | 8x8 Easy | 6 | yes | yes | no | no | `kotlin` |
| `towers` | Towers | 5x5 Easy | 7 | yes | yes | no | no | `kotlin` |
| `tracks` | Train Tracks | 8x8 Tricky | 12 | yes | yes | no | no | `kotlin` |
| `twiddle` | Twiddle | 3x3, radius 2 | 8 | yes | yes | no | no | `kotlin` |
| `undead` | Undead | 4x4 Normal | 8 | yes | yes | no | no | `kotlin` |
| `unequal` | Unequal | 4x4 Easy | 12 | yes | yes | no | no | `kotlin` |
| `unruly` | Unruly | 8x8 Trivial | 7 | yes | yes | no | no | `kotlin` |
| `untangle` | Untangle | 10 points | 5 | yes | no | no | no | `kotlin` |

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
  `DESC` or the move history. A save stores the clock in the `TIME`
  record, which is not part of the state payload.
- `net` asks the frontend for a random seed when it jumbles tiles. The
  oracle returns a fixed seed, so the oracle path is deterministic.
- Generation cancellation is not a deviation. The C path already stops:
  `GameGenerator.generate` interrupts its worker and destroys the
  generator process. The Kotlin path now stops the same way through
  `Cancellation`: `KotlinGames.generate` passes the token to
  `Midend.newGame` and `Game.newDesc`, and a game with a retry loop
  reads it at the top of an attempt, so Cancel frees the worker instead
  of letting the board run to completion. A check is a read, so an
  accepted generation with the same seed keeps the C `DESC`,
  `AUXINFO`, and save text;
  `GenerationCancelTest` pins the `tracks 22x22dh` oracle text with and
  without a token. A cancelled run throws `GenerationCancelled` and
  publishes no game, which the shell reports as nothing rather than as
  an error. The seam is engine-only: no Android type, no thread, and no
  dependency.
- `fifteen` is the first port target (`ROADMAP.md`, Phase 4). Its
  Kotlin generator, moves, history, solver, text, status, keys, save
  file, and drawing pass the matrix in `FifteenDescTest`,
  `FifteenGameTest`, `FifteenSaveTest`, and `FifteenTraceTest`. The app
  selects `KotlinGameEngine` for every `fifteen` game, so the status is
  `kotlin`. Its settings, seed, game-ID, and preference pages come from
  the engine schema, generation runs on the worker, and a solve error
  shows the C message. The C game remains in the vendored tree for the
  oracle and chooser metadata; it is not a runtime backend. The seams are
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0008-run-fifteen-on-kotlin.md`.
- `slant` is `kotlin` (fourth enablement wave, 2026-11-18). Its
  generator, solver, state, moves, undo/redo, solve, text, keys, save
  file, drawing, layout, and input pass the matrix in `SlantDescTest`,
  `SlantGameTest`, `SlantSaveTest`, `SlantTraceTest`, and
  `SlantInputParityTest`. The solve is also pinned after a played move:
  C diffs the answer against the first state, not the current board
  (`slant.c:1596-1601`), so the answer text of a moved game is the same
  as a fresh one. The cursor is drawn (`Slant.kt` uses
  `COL_CURSOR`), and `SlantTraceTest` pins the cursor frame against the
  oracle. The owner waived the two-release wait for `slant` on
  2026-11-14; the general waiver of 2026-11-18 supersedes that one, so
  the wait is not counted twice. `KotlinGamesTest` pins the enabled set
  and the generated save; `FourthWaveTileSizeInputTest` taps a border
  point (inert) and a square at two tile sizes on the Pixel 4a 5G. The
  seams are `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.
- `samegame` is `kotlin` (first enablement wave, 2026-11-18). Its
  generator, state, moves, score,
  selection, status text, save file, drawing, layout, and pointer input
  pass the matrix in `SameGameDescTest`, `SameGameGameTest`,
  `SameGameSaveTest`, `SameGameTraceTest`, and
  `SameGameInputParityTest`. The pointer test uses the oracle's
  `pointsSized` operation, so a select and a remove run in one C midend
  and are compared with the Kotlin `processKey` path.
  `KotlinGames.gameFor` knows the Kotlin game and `KotlinGames.handles`
  selects it for the app. The game has no preference page (C
  `get_prefs` is NULL) and no deviation entry. `KotlinGamesTest` pins the
  enabled set and the generated save; `FirstWaveTileSizeInputTest`
  sends a real tap and a real swipe at two tile sizes on the Pixel 4a 5G.
  The seam is `docs/adr/0007-kotlin-game-seam.md`, and the wave policy
  is `docs/adr/0011-enable-games-in-waves.md`.
- `flood` is `kotlin` (first enablement wave, 2026-11-18). Its
  generator, solver, state, moves, move limit, completion, status text,
  save file, drawing, layout, pointer input, and keyboard input pass the
  matrix in `FloodDescTest`, `FloodGameTest`, `FloodSaveTest`,
  `FloodTraceTest`, and `FloodInputParityTest`. `FloodSaveTest` pins the
  `PARAMS`/`CPARAMS` asymmetry of the short form: the id is `12x12`,
  `PARAMS` is `12x12c6m5`, and only `CPARAMS` keeps the chosen colours.
  `KotlinGames.gameFor` knows the Kotlin game and `KotlinGames.handles`
  selects it for the app. The game has no preference page (C
  `get_prefs` is NULL) and no deviation entry. `KotlinGamesTest` pins
  the enabled set and the generated save;
  `FirstWaveTileSizeInputTest` sends a real tap and a real swipe at two
  tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md`, and the wave policy is
  `docs/adr/0011-enable-games-in-waves.md`.
- `flip` is `kotlin` (second enablement wave, 2026-11-18). Its
  parameters, description generator, state, loose move grammar,
  status text, solver, save file, palette, drawing, layout, and input
  pass the matrix in `FlipDescTest`, `FlipGameTest`, `FlipSaveTest`,
  `FlipTraceTest`, and `FlipInputParityTest`. The seeded Random game
  writes Crosses in `PARAMS` and Random in `CPARAMS`, and the id drops
  the shape. The swipe is inert: `BackendName.dragMode` is `PREVENT`,
  so a tap is the move. The game has no preference page (C `get_prefs`
  is NULL) and no deviation entry. `KotlinGames.gameFor` knows the
  Kotlin game and `KotlinGames.handles` selects it for the app.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `SecondWaveTileSizeInputTest` sends a real tap and the prevented
  swipe at two tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md`, and the wave policy is
  `docs/adr/0011-enable-games-in-waves.md`.
- `sixteen` is `kotlin` (second enablement wave, 2026-11-18). Its
  generator, state, moves, loose move
  bounds, cursor modes, keypad null move, status text, solver, save
  file, drawing, layout, and pointer input pass the matrix in
  `SixteenDescTest`, `SixteenGameTest`, `SixteenSaveTest`,
  `SixteenTraceTest`, and `SixteenInputParityTest`. The save test pins
  the `full`-argument quirk: `m` stays in the short id and in both
  `PARAMS` and `CPARAMS`, unlike flood and fifteen. Only a click on the
  frame band moves a row or column; a click inside a cell is a UI
  update. `BackendName.dragMode` is `PREVENT`, so a swipe is inert.
  `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app. The game has no
  preference page (C `get_prefs` is NULL) and no deviation entry.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `SecondWaveTileSizeInputTest` sends the band tap and the prevented
  swipe at two tile sizes on the Pixel 4a 5G. A `SOLVE` record is undone
  and redone against the oracle, because the midend clears the animation
  for every record type that is not `MOVE`. A `MOVE` record whose move
  text is `S` is Kotlin-only: the C debug oracle asserts in `game_redraw`
  when that whole board is replaced, which is an upstream animation bug
  in the test tooling, not a deviation. The seam is
  `docs/adr/0007-kotlin-game-seam.md`, and the wave policy is
  `docs/adr/0011-enable-games-in-waves.md`.
- `twiddle` is `kotlin` (second enablement wave, 2026-11-18). Its
  generator, state, rotations, orientation,
  moves, status text, solver, save file, drawing, layout, and input pass
  the matrix in `TwiddleDescTest`, `TwiddleGameTest`, `TwiddleSaveTest`,
  `TwiddleTraceTest`, and `TwiddleInputParityTest`. The save test pins
  the `full`-argument quirk: `m` and the `r`/`o` flags stay in the short
  id and in both `PARAMS` and `CPARAMS`. A solve sets `completed = 1`
  and status +1, as in C, but it never raises the completion event;
  `changedState` decides that. The rotation animation and the
  completion flash are not ported, which `PARITY.md` section 1 allows,
  so strict traces come from loaded states or `traceNew`, never from a
  move in a live midend. `BackendName.dragMode` is `UNMODIFIED`, so the
  shell delivers the press at a swipe start; the game accepts only the
  one-tile band around a region centre, and a press in the rejected
  strip changes nothing. `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app. The game has no
  preference page (C `get_prefs` is NULL) and no deviation entry.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `SecondWaveTileSizeInputTest` sends the band tap and the inert drag at
  two tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md`, and the wave policy is
  `docs/adr/0011-enable-games-in-waves.md`.
- `pegs` is `kotlin` (first enablement wave, 2026-11-18). Its
  parameters, generator, state, moves, drag
  and keyboard input, status, text, save file, drawing, layout, and the
  warm redraw pass the matrix in `PegsDescTest`, `PegsGameTest`,
  `PegsSaveTest`, `PegsTraceTest`, and `PegsInputParityTest`.
  `PegsSaveTest` pins the `5x5random` hole: the writer emits
  `PARAMS 5x5cross`, and upstream rejects its own save. It also pins
  the Octagon `PARAMS`/`CPARAMS` split. `PegsTraceTest` compares the
  cold traces and, through the oracle's `traceWarm` operation, the warm
  path: the dirty-cell cache, the sprite `blitter_load`, and the
  absence of `blitter_new` after the first draw. The completion flash
  is not ported, which section 1 allows. The short `5x5` game-id
  validation now matches C: `fromGameId` validates in short mode
  (`e3e08f00`). One narrow difference remains: C decodes a descriptive
  id over the previous parameters and Kotlin starts from the defaults,
  so `currentParams.type` can differ; the description, the state, and
  the id do not. `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app. The game has no
  preference page (C `get_prefs` is NULL) and no deviation entry.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `FirstWaveTileSizeInputTest` sends a real tap and the real drag at two
  tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md`, and the wave policy is
  `docs/adr/0011-enable-games-in-waves.md`.
- `guess` is `kotlin` (second enablement wave, 2026-11-18). Its
  parameters, generator, loose move and UI
  grammars, state, hints, drag, hold, clear and keyboard input, the
  reveal, status, save file, palette, drawing, and layout pass the
  matrix in `GuessDescTest`, `GuessGameTest`, `GuessSaveTest`,
  `GuessTraceTest`, and `GuessInputParityTest`. `GuessSaveTest` pins
  the `UI` record in both directions, the legacy record without holds,
  and the character-stream decoder. `GuessTraceTest` covers the cold
  traces, the labels preference, the cursor, the solved reveal, and the
  warm drag, release, and post-move frames. `GuessInputParityTest`
  sends real clicks and drags at tiles 48, 63, 82, and 100, two
  letterboxed drags, the draft, hold, and clear keys, and a winning
  submit. C reads and writes `curr_pegs->pegs[npegs]` on backspace at
  the Submit slot, which is undefined behavior, and it aborts on the
  unmarkable submit in a build with assertions. The port treats the
  Submit slot as empty on backspace and lets the midend map the
  rejected unmarkable move to `NoEffect`, which is C's release-build
  behavior. `GuessTest` pins both results, so they are port
  definitions, not game-rule deviations. The `show-labels`
  preference lives on the engine UI with a false default; the midend
  applies the stored value through `Game.setPrefs`, and the preference
  page comes from the declared schema, so no engine field is specific
  to the app. `BackendName.dragMode` is `REVERT_TO_START`, so a colour
  drag is a real gesture and its focus is the guess peg.
  `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app. `KotlinGamesTest` pins
  the enabled set and the generated save;
  `SecondWaveTileSizeInputTest` sends the inert empty submit, a real
  colour drag, and the submit at two tile sizes on the Pixel 4a 5G. The
  seam is `docs/adr/0007-kotlin-game-seam.md`, the wave policy is
  `docs/adr/0011-enable-games-in-waves.md`, and the auxiliary rules are
  `docs/adr/0009-game-ui-and-auxinfo.md`.
- `blackbox` is `dual-run`. Its parameters, generator, loose move and
  `UI` grammars, state, laser rules, grid-seeded reveal check, status
  text, solver, save file, palette, drawing, layout, and input pass the
  matrix in `BlackBoxDescTest`, `BlackBoxGameTest`, `BlackBoxSaveTest`,
  `BlackBoxTraceTest`, and `BlackBoxInputParityTest`. The save test pins
  the `E<int>` attempt counter in both directions. The trace test covers
  the cold traces, the dirty-cell cache, the always-redrawn reveal
  button, the flash, the cursor, and the warm path, including a sized
  warm frame. Black Box is the first game with both a status bar and a
  warm trace, so `KotlinTraceSink.status` de-duplicates an unchanged
  text as `drawing.c` does; a warm frame has no repeated status line.
  `BlackBoxInputParityTest` sends real laser clicks at tiles 48, 63,
  82, 100, and 51 (the letterboxed origin (1,147)); a ball toggle,
  right-button lock, column lock and clear, row lock, and reveal at
  tile 63; and vacuous letterbox, frame-band, grid-corner,
  one-pixel-outside, drag, modified-cursor-key, and keypad-digit
  events. It compares `requestKeys`, and checks that a UI-only flash,
  an unavailable reveal, and every vacuous event change neither the
  board nor the move count. D-003 rejects a drawn ball count above the
  cell count after the count draw instead of hanging; a count that fits
  keeps the C seed, `DESC`, and save text, whatever `maxBalls` is.
  D-003 has no `Review:` line, so the game joins a wave without an
  owner review. `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app (third enablement wave,
  2026-11-18). The game has no preference page (C `get_prefs` is NULL).
  `ThirdWaveTileSizeInputTest` taps two top range cells at two tile
  sizes on the Pixel 4a 5G: a tap on an unfired range cell fires the
  laser and adds one move, and the status line stays
  `Balls marked: 0 / 1`.
  The seam is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.
- `cube` is `kotlin` (eighth enablement wave, 2026-11-19). Its
  parameters, generator, state, roll, face permutation, loose move
  grammar, completion, status text, save file, palette, drawing, layout,
  input, and `requestKeys` pass the matrix in `CubeDescTest`,
  `CubeGameTest`, `CubeSaveTest`, `CubeTraceTest`, and
  `CubeInputParityTest`. The save test pins the ignored `full` argument
  and the clamp of an out-of-range description index. The trace test
  covers the four frozen traces, a moved, a completed, and a post-win
  loaded state, a rotation sweep, and sized traces at tiles 48, 63, 82,
  and 100. The input test sends real clicks and press-drag-release
  gestures at those four tiles and at the letterboxed origin (0,146),
  and checks the centre and left-edge no-ops, the 45-degree diagonals,
  the cursor and keypad directions on every preset, `CTRL`/`SHFT` cursor
  keys, the unused buttons, and the win with undo, redo, and
  `requestKeys` after every step. A letterbox click is a move, because
  C has no board-bounds test. The animation is not ported, which section
  1 allows, so strict traces come from `traceNew` or a loaded save.
  `validate_params` keeps the C enumeration of every grid square, and
  D-036 refuses an area above 250,000 cells in `newDesc` before the
  first allocation or draw. The cap is a memory bound: the port holds
  one Kotlin object per square, and the phone process runs out of its
  256 MB heap at 1,960,000 cells, where C still generates. A larger
  save or game id still loads and plays. `EighthWaveTileSizeInputTest`
  taps at two tile sizes on the Pixel 4a 5G: a tap rolls the die one
  step in the bearing of the tap and adds one move, and a tap half a
  cell left of the die on the left edge is inert. `KotlinGames.gameFor`
  knows the Kotlin game, and the app selects it. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.
- `filling` is `kotlin` (sixth enablement wave, 2026-11-18). Its
  parameters, description generator,
  deduction solver, state, move grammar, completion, status text, save
  file, palette, drawing, layout, input, and `requestKeys` pass the
  matrix in `FillingDescTest`, `FillingGameTest`,
  `FillingInputParityTest`, `FillingTraceTest`, and the engine
  `FillingTest`. The generator and the solver decide every `DESC`, so
  `FillingDescTest` compares every preset with four seeds and pins the
  decode quirks and the wide clue alphabet. `FillingGameTest` compares
  the move grammar, the solver strings including a stalled board and a
  solve after a real fill, completion, undo, redo, and a real click plus
  digit. `FillingSaveTest` reads the four `filling` saves in the corpus,
  rewrites them byte for byte, makes C load a Kotlin-written save for
  every preset, and round-trips a played fill, an undone position with a
  live redo branch, a solve, and a restart in both directions. The trace
  test covers the three frozen traces, a selected cell, the cursor ring,
  a completed `1x2` board, a partially filled board with an overfull
  region, and sized traces at tiles 48, 63, 82, and 100, plus the
  letterboxed 411x702 viewport at origin (2,206). A warm frame after a
  click and after a click plus digit is exactly `start_draw`,
  `end_draw`.

  The input test sends real clicks and a click plus digit at tiles 48,
  63, 82, and 100, then repeats the four tiles with a nonzero letterbox
  origin. It also covers a cursor plus digit, a digit alone, a clear
  with and without a filled cell, and a letterbox click followed by the
  cursor-select keys. Every real fill changes the board and adds one
  move; a selection click, a clear with nothing to clear, and an
  out-of-grid cursor change neither. `requestKeys` is compared on every
  preset: arrow mode 3 with `1`..`9`, `0`, and `Clear`, and no key needs
  arrows.

  Three C defects leave a defined result for the port instead of a
  deviation: `current_key_label` reads `ui->sel` with no NULL test
  (`filling.c:1450`); an Android letterbox click leaves the cursor
  outside the grid and the cursor-select paths index the clue and
  selection arrays without a bounds test (`filling.c:1449`,
  `1503-1508`, `1531-1532`, `1545-1546`); and a stalled solve returns a
  partial board while C sets an error the midend ignores
  (`filling.c:1378-1390`). The port treats a null selection as "not
  selected", an out-of-grid cursor as "no cell", and returns the
  partial board. The C oracle cannot be asked for the first two paths,
  so they are pinned in the engine test; the partial solve is compared
  with the oracle.

  One C solver defect is deviation D-004. The solver asserts on an
  overfull 1-region (`filling.c:796`), and the shipped C code keeps
  assertions: `app/src/main/jni/cmake/setup.cmake:17-25` removes
  `-DNDEBUG` from every release flag, so C aborts on a reachable board
  such as `2x2:1a1a`. The port skips the region and returns the best
  board, `s1212` with status 0 and `cheated` set. `FillingTest` pins the
  result, because the oracle aborts on that board.

  `validate_params` accepts `46340x46340`, and generation then
  allocates about 8.6 GB of boards. The C `make_board` restarts the
  whole board through `goto retry` with no bound, and the port keeps
  that loop in `FillingSolver.makeBoard`. The owner made the size bound
  warning-only (decision 2b): `Game.generationWarning` names every board
  above 221 cells, the largest preset, and no parameter set is rejected.
  No test generates a huge accepted area. S9 is not applicable
  (section 7): the Android shell never displays `current_key_label`.
  `KotlinGames.handles(FILLING)` is true (sixth enablement wave,
  2026-11-18), and `SixthWaveTileSizeInputTest` clicks the half-tile
  border, the digit with no selection, a region, and its digit at two
  tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `netslide` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and presets, the `tree234`
  generator, the before-shuffle barrier candidate list, the shuffle,
  the stored `AUXINFO` answer, state, loose move grammar, slide
  arithmetic, pointer bands, cursor ring, `requestKeys`, save file,
  palette, drawing, layout, and input pass the matrix in
  `NetslideDescTest`, `NetslideGameTest`, `NetslideSaveTest`,
  `NetslideInputParityTest`, `NetslideTraceTest`, and the engine
  `NetslideTest`. `NetslideSaveTest` reads all ten `netslide` corpus
  saves, rewrites them byte for byte, and compares id, status, undo,
  redo, text, and `AUXINFO`. It makes C load a Kotlin save for every
  preset plus wrapping, barrier-rate, move-target, and non-square
  vectors, and round-trips a row slide, a column slide, the identity
  `R0,3`, an undone position with a live redo branch, a `SOLVE`, and a
  `RESTART` in both directions. The `full` split is pinned: the `b`
  rate stays in `CPARAMS` only, so `PARAMS 3x3` and `CPARAMS 3x3b1`
  describe the same save and the id has no `b`. The preset strings keep
  `3x3b1` verbatim, because the short form would drop the rate.

  `NetslideInputParityTest` sends real row and column slides at tiles
  48, 63, 82, and 100 and at the letterboxed 411x702 viewport (tile 82,
  origin (0,145)), and checks centre, corner, centre-band, outside, and
  letterbox clicks as inert. Every real slide changes the board and
  adds one move and one state; an inert click returns `Unused`, changes
  neither, and leaves the warm frame unchanged. A drag is not
  supported: `BackendName.dragMode` is `PREVENT` and `interpret_move`
  returns `NULL`, so a press, drag, and release makes one move at the
  press. The cursor ring is walked with `RIGHT`/`LEFT` on the top and
  bottom rows and `UP`/`DOWN` on the side columns, then a select is
  compared with the oracle. `requestKeys` is arrow mode 3 with no
  labels on every preset.

  D-005 rejects a NaN barrier rate with `Barrier probability must be a
  number` instead of reproducing the C abort at `netslide.c:629`.
  `validate_params` accepts `46340x46340` and generation then allocates
  about 17 GB of two `IntArray`s and does not finish, as C does with
  about 4.3 GB. D-026 rejects an area above 90,000 cells (300x300) at
  generation with `Width times height is too large for this build`,
  before the first draw; the measured 300x300 phone generation is
  4.97 s. Validation and the load path keep the C behavior.
  `KotlinGames.handles(NETSLIDE)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` slides a row and a
  column at two tile sizes. `currentKeyLabel`
  is a tested helper because C has no Android call site for it. The seam
  is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `unruly` is `kotlin` (sixth enablement wave, 2026-11-18). Its
  parameters and presets, the run-length
  description, the nested fill and winnow loops, the five-technique
  deduction solver, state, loose move grammar, completion, the empty
  status text, the cursor, `requestKeys`, the save file, palette,
  drawing, layout, and input pass the matrix in `UnrulyDescTest`,
  `UnrulyGameTest`, `UnrulySaveTest`, `UnrulyInputParityTest`,
  `UnrulyTraceTest`, and the engine `UnrulyTest`. The generator and the
  solver decide every `DESC`, so `UnrulyDescTest` compares all seven
  presets with nine seed strings and a numeric sweep, and pins the
  decode quirks, the validation order, the run alphabet, and the
  golden ids. `UnrulyGameTest` compares the move grammar including
  junk, spaces, signs, leading zeros, and 32-bit wrap, every preset's
  `SOLVE`, a stalled and an invalid board, completion, undo, redo, a
  restart, real clicks to a full board, and the warm click frame.
  `UnrulySaveTest` reads all eight `unruly` corpus saves, rewrites
  them byte for byte, and compares id, status, undo, redo, and text;
  it makes C load a Kotlin save for every preset and round-trips a
  played click, an undone position with a live redo branch, a `SOLVE`,
  and a `RESTART`. A hand-written `AUXINFO` record round-trips and the
  solve ignores it. The `full` split is pinned:
  `PARAMS 8x8dt` and `CPARAMS 8x8dn` in the same save, and no `d` in
  the id. The preset strings keep `8x8dn` verbatim, because the short
  form would drop the difficulty.

  The solve reads the decoded initial grid, because `midend_solve`
  passes `states[0]` and C never reads the current board. A click that
  contradicts the answer does not change the `SOLVE` record, and
  `UnrulyGameTest` pins that case, the engine test, and the
  `Midend.solve` path.

  `UnrulyInputParityTest` sends real clicks at tiles 48, 63, 82, and
  100 and at the 411x702 letterbox of every board size (tile 45 with
  origin (3,148), 37 with (2,147), and 27 with (3,148)). It checks the
  left cycle, the right and middle mapping, the half-tile border, the
  last cell and one pixel past it, a clue click, a press, drag, and
  release (one move at the press), and a click followed by a cursor key
  and a select. Every real placement changes the grid and adds one
  state; a border or letterbox click, a clue click, a lone drag, and a
  lone release change neither. `requestKeys` is arrow mode 3 with no
  labels on every preset. The keyboard is compared with the oracle,
  including modified cursor keys and a keypad digit.

  Two C undefined-behavior spots are port guards, not game-rule
  changes. `encode_params` writes a plain `d` for a difficulty outside
  `0..2` instead of reading past `unruly_diffchars` (`unruly.c:238`),
  and the unique technique skips a pair that would index `-1` or
  overwrite a value instead of tripping the two C asserts
  (`unruly.c:671`, `674`). Validation runs before encoding, and the
  counters are rebuilt from the grid, so neither guard is reachable
  from a validated save; both are pinned by `UnrulyDescTest` and
  `UnrulyTest`. A failed solve returns null, because the seam has no
  error out-parameter; C reports `No solution found.` or `Puzzle is
  invalid.` instead. The seven presets always solve, so this is the
  `flip` seam debt and not a deviation.

  `validate_params` accepts `46340x46340`, and the generator's retry
  loops are unbounded, so a huge accepted area would allocate a
  2.1-billion-cell grid and mask, more than 10 GB in this port, and run
  the solver over it. The C `new_game_desc` retries the fill and winnow
  loop with no bound, and `UnrulySolver.generate` is that loop. No test
  calls `newDesc` or `newState` on a huge accepted area;
  `validateParams` pins both the accepted `46340x46340` boundary and the
  rejected `65536x65536` side, and the generation tests run with a
  timeout. The owner made the size bound warning-only (decision 2b):
  `Game.generationWarning` names every board above 196 cells, the
  largest preset, and no parameter set is rejected. `currentKeyLabel` is
  a tested helper because C has no Android call site for it.
  `KotlinGames.handles(UNRULY)` is true (sixth enablement wave,
  2026-11-18), and `SixthWaveTileSizeInputTest` clicks the half-tile
  border and a cell at two tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `range` is `kotlin` (fourth enablement wave, 2026-11-18). Its
  parameters and presets, the `signed char` decode and the validation
  order, the run-length description, the two shuffles per generator
  attempt with its retries, the four-technique solver, state, the
  `sscanf("%c,%d,%d")` move grammar, completion,
  the sticky status, the first-state solve, the hint, the empty status
  text, the cursor, `requestKeys`, the save file, palette, drawing,
  layout, and input pass the matrix in `RangeDescTest`,
  `RangeGameTest`, `RangeSaveTest`, `RangeInputParityTest`,
  `RangeTraceTest`, and the engine `RangeTest`. `RangeDescTest` pins
  the `signed char` quirks (`300x6` is `44x6`, `+9x6` is `9x9`),
  the accepted `64x64` boundary, the run alphabet, the golden ids,
  and the retry seeds `3x3` seed 3 and `3x5` seed 2. `RangeGameTest`
  pins the accepted and rejected move forms, a clue overwritten by a
  move, the black-set-only win with and without dots, the sticky
  status, undo and redo, every preset's `SOLVE`, the `S` answer of an
  already solved board, and the hint. `RangeSaveTest` reads all five
  corpus saves, rewrites them byte for byte, and compares id, status,
  undo, redo, and text; it makes C load a Kotlin save for every preset
  and round-trips a played click, an undone position with a live redo
  branch, a `SOLVE`, a `RESTART`, and a `MOVE` that starts with `S`.
  A hand-written `AUXINFO` record round-trips and the solve ignores
  it. `PARAMS` and `CPARAMS` are always equal, and `default.sgtp` and
  `9x6.sgtp` are the same file.

  The solve reads the decoded initial grid, because `midend_solve`
  passes `states[0]` and C never reads the current board.
  `RangeGameTest` plays a click that contradicts the answer and pins
  that the `SOLVE` record still matches C.

  `RangeInputParityTest` sends real clicks at tiles 48, 63, 82, and
  100 and at the 411x702 letterbox of every preset, where at least one
  origin is nonzero (the `9x6` board has origin (0,207) at tile 41).
  Every click of the left and right cycles changes the cell, adds one
  `MOVE`, and adds one state. A clue click with each button, a border
  or letterbox click, a lone drag, and a lone release return `Unused`
  and change neither the grid nor the history. The test also covers
  the press, drag, and release (one move at the press), the
  click-then-cursor rule, the arrow ring, the shift paint, modified
  cursor keys, and a keypad digit. `requestKeys` is arrow mode 3 with
  no labels on every preset.

  One C undefined-behavior spot gets a defined port result, pinned by
  `RangeTest`, and is not a deviation. On an all-black board the
  connectedness reasoning finds no DFS root and writes outside
  `dfs_parent`/`dfs_depth` (`range.c:465-471`); the port treats "no
  non-black cell" as "no moves". The shipped C build keeps assertions,
  so the oracle cannot be asked about that path.

  The recursion reasoning takes the move buffer by value
  (`range.c:605-638`). The `do_solve` call on a duplicated state
  advances only its own copy of the end pointer, so a branch's moves
  never reach the caller's buffer. A `SOLVE` string therefore holds
  only the moves of the techniques 0-2, and a board that only
  recursion can crack answers `S`; the hint on the same board finds no
  move. The port runs every branch on a scratch list and discards it.
  `RangeGameTest` pins `3x3:d3_3c` (`S`, and `h` does not move),
  `4x3:1e4e` (`SB,1,0B,0,1W,1,1W,0,2W,2,0`), and `4x4:2_5k5b` (eight
  moves) against the oracle.
  `validate_params` bounds `w + h` at 128, so the area is at most 4096
  cells and no huge-area guard is needed.

  `KotlinGames.gameFor` knows the Kotlin game and `KotlinGames.handles`
  selects it for the app. `Game.prefs` declares the
  `Mouse button order` choice (`fill` or `dot`) and `Game.setPrefs`
  copies it to `Range.Ui.swapButtons` after `newUi` and after
  `Midend.fromSave`, because there is no `decodeUi` and a load recreates
  the UI. `PrefsSchemaParityTest` compares the schema and the text with
  C, `PrefsApplicationTest` checks the live field and `RangeTest` the
  swapped click; the oracle's `prefs` op is the schema check. The
  `pointsSized` and `traceWarm` preference fields still carry no range
  trace comparison. `currentKeyLabel`
  is a tested helper because C has no Android call site for it.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `FourthWaveTileSizeInputTest` taps a clue (inert) and an empty cell
  at two tile sizes on the Pixel 4a 5G. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `lightup` is `dual-run`. Its parameters and nine presets, the
  decode quirks and validation order, the run-length description, the
  solver-gated generator, the black-percentage ramp, state, the `L`/`I`
  move grammar, the sticky completion, the solve diff with its
  `F_IMPOSSIBLE` marks, the text format, the cursor, `requestKeys`, the
  save file, palette, drawing, layout, and input pass the matrix in
  `LightupDescTest`, `LightupGameTest`, `LightupSaveTest`,
  `LightupTraceTest`, `LightupInputParityTest`, and the engine
  `LightupTest`. `LightupDescTest` compares every preset at the golden
  seed and a numeric sweep, the decode quirks, the exact messages, the
  ramp fixtures, and the guard messages. `LightupGameTest` compares the
  move grammar, the two `STYLUS_BASED` cycles, the select and modified
  keys, a hidden-cursor select, completion, every preset's `SOLVE` with
  its `L`/`I` counts, a solve after a wrong light, the initial-state
  fallback, undo, redo, restart, and the vacuous clicks at the
  preferred tile. `LightupSaveTest` reads all ten corpus saves,
  rewrites them byte for byte, and compares id, status, undo, redo, and
  text; it pins the `PARAMS`/`CPARAMS` split and round-trips a played
  light, an impossible mark, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART`. No save has a `UI` or an
  `AUXINFO` record.

  `LightupTraceTest` covers the nine frozen traces, the frame polygon,
  the column-major draw order, the clue text and its error colour, a
  light, an overlap, an impossible mark, the cursor ring, sized traces
  at tiles 48, 63, 82, and 100, the letterboxed 411x702 view, the
  `show-lit-blobs` blob, and the warm path. `LightupInputParityTest`
  sends real left and right stylus cycles with releases at those four
  tiles and at the 411x702 letterbox (tile 51, origin (2,147)), checks
  a clue, black cell, the border bands, the letterbox, and a middle
  click as inert, and covers the arrows, modified arrows, a
  hidden-cursor select, `SHFT`/`CTRL` on a select, a keypad digit, TAB,
  backspace, a press-drag-release, a lone drag, and a lone release.
  Every real light or mark changes the grid and adds one move and one
  state; a release and a UI-only action change neither. The preference
  is compared on and off through the oracle's `prefs` field; the state,
  id, status, and save do not change, and the cached `DrawState.flags`
  do not contain the value.

  The generator is gated by the solver twice per attempt, and the
  surviving clues are the ones the solver marked `F_NUMBERUSED`, so a
  solver difference changes every `DESC`. The `SOLVE` string is the
  solver's final board diff, including the `F_IMPOSSIBLE` marks, in
  column-major order; the presets carry 0 to 45 `I` entries. Both are
  compared exactly. Two accepted parameter sets do not terminate in C:
  `46340x46340` allocates about 17 GB, and `blackpc = 100` with
  difficulty 1 or 2 loops because the difficulty check rejects every
  all-black attempt. The port rejects both before generation; this is
  D-006, and the valid parameters are unchanged. The `show-lit-blobs`
  preference lives on `Lightup.Ui.drawBlobsWhenLit` with a true
  default. `Game.setPrefs` applies it to the live UI after `newUi` and after
  `Midend.fromSave`, and the shell forces a redraw; the value must not
  enter `DrawState.flags`, because C reads it in `tile_redraw`
  and a warm frame after a preference change alone is empty.
  D-006 has no `Review:` line and the two non-terminating parameter
  sets are rejected before generation, so the game joins a wave.
  `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app (third enablement wave,
  2026-11-18). `ThirdWaveTileSizeInputTest` taps a frame point and two
  white cells at two tile sizes on the Pixel 4a 5G: the frame tap has
  no cell and changes nothing, and each white-cell tap cycles the cell
  to a light and adds one move. `currentKeyLabel` is a tested helper
  because C has no Android
  call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `mosaic` is `kotlin` (fifth enablement wave, 2026-11-18). Its parameters and six presets, the decode
  quirks and validation order, the run-length description, the
  solver-gated generator with its two shuffles, state, the loose move
  grammar, the completion counter, the solve bits, the text format, the
  cursor, `requestKeys`, the save file, palette, drawing, layout, and
  input pass the matrix in `MosaicDescTest`, `MosaicGameTest`,
  `MosaicSaveTest`, `MosaicTraceTest`, `MosaicInputParityTest`, and the
  engine `MosaicTest`. `MosaicDescTest` compares the seven golden
  entries, nine seed strings, a numeric sweep, the three retry fixtures,
  and the aggressive `h0` variant. `MosaicGameTest` compares the move
  grammar, the left and right cycles, a clue click, the three drag
  shapes, the fabricated release, completion, undo, redo, restart, and
  every preset's `SOLVE` string. `MosaicSaveTest` reads all seven corpus
  saves byte for byte and compares id, status, undo, redo, and text in
  both directions; it pins the `PARAMS 50x50` and `CPARAMS 50x50h0`
  split. `MosaicTraceTest` covers the six frozen traces, the margin
  cells, the cursor ring, the warm path, tiles 48, 63, 82, and 100, the
  letterboxed 411x702 view, and the invisible `last_state` of -1.

  `MosaicInputParityTest` sends real left and right press and release
  pairs at those four tiles and at the 411x702 letterbox (tile 37,
  origin (2,147)), checks a clue cell, the border bands, both margins, a
  middle press, and a letterbox click, and covers the horizontal,
  vertical, and diagonal drags, the press-without-release `e` fill,
  unknown and empty moves, the cursor keys, modified arrows, a keypad
  digit, the selects, `u`/`r`, the completed freeze, and `requestKeys`.
  Every real press or drag changes the board and adds one move and one
  state; a release, an inert click, and a UI-only action change
  neither.

  The completion state is derived: `game_status` is
  `not_completed_clues == 0`, and the counter is rebuilt from per-clue
  `SOLVED` bits that are refreshed only in the 3x3 block of the changed
  cell. The flags are history, so a `DESC` with no clues loads
  completed, a full solution loads open, and a `SOLVE` sets the counter
  to 0 without touching the flags. The move grammar is loose: an
  unknown or empty move is an accepted copy, and a raw `last_state` is
  stored unvalidated. Both are C behavior, not deviations. The
  encoder's 27-hidden-cell run writes `{` because of `space_count <=
  'z'` (`mosaic.c:809-816`); the port reproduces it literally and
  `MosaicTest` pins it, but no generated board reaches it (4400 samples
  stayed in `0`-`9a`-`z`, longest run 26), so no deviation entry is
  needed. An aggressive `100x100` board takes 30-45 s in C. The owner
  made the size bound warning-only (decision 2b): `Game.generationWarning`
  names every board above 2500 cells, `SlowGenerationDialogTest` checks
  the warning and Cancel for the aggressive `100x100` case, and no
  parameter set is rejected. The app generates off the UI thread.
  `KotlinGames.handles` selects the Kotlin game (fifth enablement wave,
  2026-11-18), and `FifthWaveTileSizeInputTest` taps a clue and the
  margin at two tile sizes on the Pixel 4a 5G.
  `currentKeyLabel` is a tested
  helper because C has no Android call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `palisade` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and four presets, the decode
  quirks and validation order, the run-length description, the
  solver-gated generator, the two-bit border state, the move grammar,
  the solve, the text format, both cursor modes, the save file, palette,
  drawing, layout, and input pass the matrix in `PalisadeDescTest`,
  `PalisadeGameTest`, `PalisadeSaveTest`, `PalisadeTraceTest`,
  `PalisadeInputParityTest`, `PalisadeCursorTest`, `DivvyParityTest`,
  and the engine `PalisadeTest` and `DivvyTest`. `PalisadeDescTest`
  compares the five golden descriptions, the four presets at every seed
  with their full saves, the numeric seed sweep, the decode quirks and
  validation messages, the winnow/divvy partition through the encoded
  RNG state, the accepted huge area that D-007 refuses only at
  generation, and a generated save that reloads through the engine.
  `PalisadeGameTest` compares the move grammar, real clicks, both cursor
  modes, the `H/J/K/L` aliases, control and shift, undo and redo, the
  completion event, the solve with and without `AUXINFO`, and both
  `requestKeys` modes. `PalisadeSaveTest` reads the
  five corpus saves byte for byte and pins the `k == wh` split save and
  the empty seed. `PalisadeTraceTest` covers the four frozen traces, a
  loaded save, the cursor ring, the full cursor's eight lines, the warm
  path, tiles 48, 63, 82, and 100, the 411x702 letterbox, the
  `clear-complete-regions` preference, and the `S` answer.

  The generator depends on the ported `divvy` library, and
  `DivvyParityTest` compares the canonical roots, class sizes, attempt
  count, and the encoded RNG state after the call, including the retry
  fixtures. The RNG order is one shuffle of the winnow order, then one
  `divvy_rectangle` per solver-gate attempt; a retry does not reshuffle
  the winnow order and the winnow itself draws nothing.

  `PalisadeInputParityTest` sends real fence clicks at tiles 48, 63, 82,
  and 100 and at the 411x702 letterbox (tile 67, origin (2,147)). Every
  fence changes the border bytes and adds one move, and the final state,
  id, status, and trace match `pointsSized`. It also covers the
  half-grid cursor that continues from a clicked edge, the full-grid
  cursor and its control and shift cycles, the `H/J/K/L` letters in both
  modes, the full-grid completion of `2x3n3:d33`, empty and one-sided
  moves, a both-bits edge, and inert letterbox, margin, middle, drag,
  release, and outward-edge clicks. The oracle `requestkeys` operation
  takes an optional `fullCursor` field, so the four `H/J/K/L` labels,
  their buttons, `needsArrows`, and the arrow mode are compared in both
  modes. The oracle `keys` operation takes an optional `prefs` field, so
  the full-grid sequences are compared at the state level, not only in
  the drawing trace. Both fields keep the old default.

  `cursor-mode` (`Half-grid` by default, `Full-grid` when set) changes
  the input mode, the cursor shape, and the requested keys; it does not
  change the id, status, text, or save. `clear-complete-regions` changes
  only the drawn color of a `MAYBE` edge in a complete region. Neither
  preference is serialized, so the midend applies both to the live UI
  after `newUi` and after `Midend.fromSave`; the shell then redraws and
  re-requests the keys for `cursor-mode`. `KotlinGameEngine.requestKeys`
  already passes `palisadeFullCursor` to `Palisade.requestKeys(params,
  fullCursor)`. The completion flash is not ported; animations and
  transitions may differ (section 1) and the oracle's `allow_flash` is
  0, so no trace can contain it.

  D-007 covers generation: C accepts an area up to `INT_MAX` and then
  allocates tens of gigabytes, so the port rejects an area above 2^20
  cells, and a region count `w*h/k` above 50, at `newDesc` before any
  RNG draw. The region cap is the measured one: 25x20n10 (50 regions)
  is 7.2 s on the phone and 30x20n10 (60) is 27.1 s, while no preset
  has more than 18 regions. Validation keeps the C messages and the
  accepted `46340x46340` boundary; only generation is guarded, so a
  larger save or game id still loads. The app's
  configuration dialog must surface that guard.

  One save difference is recorded as debt, not as a deviation.
  `midend.c:2606-2613` drops the `SEED` record when `CPARAMS` fails the
  full validation, which the `k == wh` split save
  (`PARAMS 5x5n5`, `CPARAMS 5x5n25`) does. The Kotlin `Midend.fromSave`
  keeps the seed. The id, status, text, solve, `NSTATES`, and all move
  records are identical, and `PalisadeSaveTest` pins both sides. A C
  load of that one hand-written save therefore re-serializes without
  `SEED`, while Kotlin keeps it. No generated save and no corpus save
  reaches this path.

  `KotlinGames.handles(PALISADE)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` toggles a fence twice
  at two tile sizes. S9 is not
  needed, because `current_key_label` is `NULL`. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `inertia` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and three presets, the
  generator, the slide and stop movement, the mine and gem rules, the
  solver, the text format, the death counter, the save file, the
  palette, drawing, layout, and input pass the matrix in
  `InertiaDescTest`, `InertiaGameTest`, `InertiaSaveTest`,
  `InertiaTraceTest`, `InertiaInputParityTest`, and the engine
  `InertiaTest`. `InertiaDescTest` compares the four golden
  descriptions, the presets at nine seed strings, a numeric sweep, the
  decode quirks, the validation order, and the generator attempt
  counts. `InertiaGameTest` compares real moves in all eight
  directions, the letterbox click, the death and gem order, the empty
  `MOVE`, the `SOLVE` text and hints, the failed solves, the `S`
  payload and its revive quirk, and the `UI D<deaths>` record.
  `InertiaSaveTest` reads the four corpus saves byte for byte, and
  `InertiaTraceTest` compares the three frozen traces, cold loaded
  states, and the warm path with inert input.

  A warm frame after a real move is not compared: the oracle has no
  timer and records the animation frame at `anim_pos == 0`, while the
  port has no animation. The strict traces use `traceNew`, a loaded
  state, or inert input, and the animation frame is pinned Kotlin-only.
  `inertia_follow` is emitted after the cells and before the status, as
  `inertia.c` does from `game_redraw`.

  D-008 covers generation: C accepts an area up to `INT_MAX`, then
  `gengrid` allocates two byte arrays of about 17 GB each and an int
  array of about 69 GB for `46340x46340`, plus the grid, and does not
  finish. The port rejects an area above 40,000 cells (200x200) at
  `newDesc` before any RNG draw; the measured 250x250 generation is
  10.8 s on the phone, so 200x200 is the cap. Validation keeps the C
  messages and the accepted `46340x46340` boundary; only generation is
  guarded, so a larger save or game id still loads.

  `KotlinGames.handles(INERTIA)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` moves the dot twice at
  two tile sizes. The
  shell shows the game's message when the board is already solved or
  dead. `currentKeyLabel` is a tested helper
  because the Android shell has no call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `tents` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and six presets, the decode
  quirks and validation order, the run-length description, the
  generator, the state, the stylus clicks and drags, the cursor and
  letter keys, completion, the solver, the text format, the save file,
  palette, drawing, layout, and input pass the matrix in
  `TentsDescTest`, `TentsGameTest`, `TentsSaveTest`, `TentsTraceTest`,
  `TentsInputParityTest`, and the engine `TentsTest`. `TentsDescTest`
  compares every preset at the golden seed and nine seed strings, a
  61-seed sweep over `8x8de`, `8x8dt`, `4x4de`, `4x4dt`, `5x4de`, and
  `5x4dt`, the decode quirks, the validation messages, and the `4x4`
  difficulty downgrade. It also pins the generated aux answer against
  the oracle's own `SOLVE` text for every preset.
  `TentsGameTest` compares the move grammar, the real press, drag, and
  release batches, the select and letter keys, the win with its
  completion event, undo and redo across the win, the sticky flag, the
  solve with and without `AUXINFO`, the initial-grid solve after a
  wrong move, the two solver failures, and `requestKeys`.
  `TentsSaveTest` reads the seven corpus saves byte for byte and pins
  the `PARAMS`/`CPARAMS` split, the absent `UI` record, and the
  generated `AUXINFO`. A Kotlin-generated save is byte-identical to the
  oracle save for every preset and nine seeds.
  `TentsTraceTest` covers the six frozen traces, the tree and cursor
  geometry, the two clipped error diamonds, the sized views, the
  letterbox, and the warm path.

  The generator keeps the C RNG order: the per-iteration
  `random_upto`, each tent's adjacency list built in `U, L, R, D` order
  with the tents in row-major order, the seeded `Matching.match`, and
  the retry condition that calls the `diff - 1` and `diff` solvers. The
  `treemap` id *values* are not observable: a consistent renumbering of
  the right vertices leaves the matching and the description unchanged.
  A reordering of the adjacency lists, or a different candidate
  partition, changes every description while still producing valid
  puzzles, so the golden descriptions, the nine seed strings, and the
  61-seed sweep are the guards. `MatchingParityTest` pins the library
  itself.

  The solver reads the decoded initial grid, because `midend_solve`
  passes `states[0]` and C never reads the current board. `Tents.Shared`
  keeps that grid, `dupGame` shares it, and `TentsGameTest` plays a
  wrong tent before the solve and pins that the `SOLVE` record still
  matches C. `completed` is sticky: `executeMove` copies the flag and
  only sets it, so a post-win move keeps status 1 and raises no second
  event. Both rules are measured against the oracle.

  `TentsInputParityTest` sends real press, drag, and release batches and
  the three-state left and right click cycles at tiles 48, 63, 82, and
  100 and at the 411x702 letterbox of every preset. Each cycle changes
  the grid and adds one move and one state; each drag produces one move
  at the release. The `FROMCOORD` bands, a press without a release, a
  middle press, a cancelled drag, and a click on a tree change neither
  the grid nor the count. `requestKeys` is arrow mode 3 with no labels
  in the default mode; the Android full-cursor mode asks for arrow mode
  1 with no labels, because `game_request_keys` is `NULL` and only the
  midend's arrow default changes.

  D-009 covers the two descriptions that pass `validate_desc` and abort
  the C decoder: `8x8:ddddddddz,...` at `tents.c:1292` and
  `8x8:dddddddcz_,...` at `tents.c:1294`. The port throws
  `tents: the description runs past the grid` and
  `tents: the description ends on a non-tree cell`. The shipped app
  keeps assertions, so the exception is the intended safe behavior;
  every valid description is unchanged and no generated board reaches
  either path.

  D-027 covers generation: C accepts any area up to `INT_MAX`, and
  Easy on a large board is slow: 20x20 takes about 0.6 s and 25x25
  Easy 39 s in C. The port rejects an area above 484 cells (22x22) at
  `newDesc` before the first draw, with a fixed message; the measured
  22x22 Easy generation is 6.87 s on the phone and 23x23 Easy does not
  finish in 12 s on the host. Validation and the load path keep the C
  behavior, so a larger save or game id still loads. The
  params-dependent `can_format_as_text_now`
  (`tents.c:1390-1393`, `w <= 1998 && h <= 1998`) has no seam hook:
  `Game.canFormatAsText` is a constant and `Midend.textFormat` checks
  only that constant, so a hand-written board above 1998 cells would
  return text where C returns null. The path is unreachable from normal
  play.

  The generator writes the C aux answer. `newDesc` returns
  `Description(desc, auxInfo)`, where the aux text is `S` followed by
  one `;Tx,y` per tent in row-major order (`tents.c:1175-1182`), so a
  Kotlin-generated save carries the same `AUXINFO` record as C and the
  whole save text is byte-identical. `TentsDescTest` pins the aux text
  against the oracle's own `SOLVE` record, and `TentsSaveTest` compares
  the generated save byte for byte for every preset and nine seeds.
  Every corpus save that already carries `AUXINFO` keeps it byte for
  byte. `KotlinGames.handles(TENTS)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` places a tent twice at
  two tile sizes. The
  shell shows the game's message
  when a hand-written board fails to solve. `currentKeyLabel` is a tested
  helper because the Android shell has no call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `magnets` is `kotlin` (fifth enablement wave, 2026-11-18). Its parameters and eight presets, the decode
  quirks, the validation order, the two-attempt description generator,
  `laydomino`, the magnet and neutral cycles, the `D` clue toggle, the
  state, the solver, the text format, the save file, palette, drawing,
  layout, input, and `requestKeys` pass the matrix in
  `MagnetsDescTest`, `MagnetsGameTest`, `MagnetsSaveTest`,
  `MagnetsTraceTest`, `MagnetsInputParityTest`, the engine
  `MagnetsTest`, and the `laydomino` pair `LayDominoParityTest` and
  `LayDominoTest`. Every preset and nine seed strings, plus a numeric
  sweep, guard the generator stream: `LayDomino.layout`, one shuffle in
  `lay_dominoes`, the strip-clues shuffle, and the retry gate. The
  generated `AUXINFO` is compared with the oracle, so the solver inside
  the gate is pinned too.

  `LayDominoTest` pins three measured vectors: 6x5 seed `12345` grid
  `1,0,3,2,5,4,...` with two repair passes, the odd-area 3x3 with one
  singleton at index 6 and one pass, and the one-row 1x5 board with no
  pass. `LayDominoParityTest` compares the grid, the prealloc grid, the
  attempt count, and the encoded random state for 15 shapes from 1x1 to
  10x9, including seeds that need 1, 2, 3, and 6 repair passes; the
  encoded state proves that every candidate and neighbour shuffle drew
  the same numbers. `dominosa` can reuse the library later without a
  new port.

  `MagnetsGameTest` compares the move grammar and the measured
  acceptance table, the left and right cycles, the `D` clue toggle, the
  singleton no-ops, completion and the sticky flag, undo, redo,
  restart, the solve with and without `AUXINFO`, the initial-state
  fallback after a wrong move, the `S;` solve of a solved board, the
  key labels, and `requestKeys`. `MagnetsInputParityTest` sends real
  left and right clicks at tiles 48, 63, 82, and 100 and in the 411x702
  letterbox, real clue clicks at the same views, the cursor keys, both
  select buttons, and the inert middle, corner, drag, and release
  cases. Every real magnet or neutral cycle changes the grid or the
  flags and adds one move and one state; every real `D` toggle changes
  the shading and adds one move while the grid stays; an inert click
  changes neither the board, the shading, nor the count.
  `requestKeys` is arrow mode 3 with no labels, and the full-cursor
  mode asks for arrow mode 1 with no labels, because `game_request_keys`
  is `NULL`.

  `MagnetsSaveTest` reads all nine corpus saves byte for byte and
  reloads them in C with the same id, status, undo, redo, and text. It
  round-trips a played move, an undone position with a live redo
  branch, a `D` toggle, a `SOLVE`, and a `RESTART` in both directions,
  and pins the `PARAMS`/`CPARAMS` split (`PARAMS 6x5de`,
  `CPARAMS 6x5dtS`), the absent `UI` record, and the `AUXINFO` round
  trip. `MagnetsTraceTest` compares the eight frozen traces, a cold
  loaded state, a visible cursor over a magnet (`DS_CURSOR`,
  `COL_CURSOR`, `COL_TEXT`), a set neutral and a not-neutral cell (the
  background-colored `line` cross and the `COL_NOT` question mark), a
  `COL_DONE` clue after `D`, a `COL_ERROR` clue, sized traces at tiles
  48, 63, 82, and 100, the letterbox, the first-draw corner symbols and
  two whole-window updates, and a Kotlin-only warm frame with the
  changed tile and clue. The completion flash is not ported; section 1
  allows animations to differ and the oracle never emits one.

  Size bound. Generation is synchronous; the app calls it on its worker:
  `16x14de` takes about 5.9 s and `20x20` does not finish in 90 s,
  while the eight presets are under 20 ms. The owner made the size bound
  warning-only (decision 2b): `Game.generationWarning` names every board
  of 224 cells or more, so `16x14de` warns, and no parameter set is
  rejected. `LayDomino.checkedArea` is the only area guard on the path:
  it rejects an area above `INT_MAX / 2` with
  `laydomino area is too large`, because the C candidate list count
  `2*w*h` must fit an `int`. That guard rejects only areas the presets
  and tests never generate, and it runs before the first RNG draw.
  `KotlinGames.handles` selects the Kotlin game (fifth enablement wave,
  2026-11-18), and `FifthWaveTileSizeInputTest` clicks a cell and the
  border corner at two tile sizes on the Pixel 4a 5G. S9 is a tested
  `currentKeyLabel` helper with no Android call site.

- `pattern` is `kotlin` (fifth enablement wave, 2026-11-18). Its parameters and five presets, the decode
  and validation quirks, the generator and its line-solver gate, the
  description grammar and suffix, the state, the `STYLUS_BASED`
  three-way cycles, the drag snap and clamp rules, the unsnapped middle
  rectangle, the cursor, the no-op win, the solve with and without
  `AUXINFO`, the text format, the save file, palette, drawing, layout,
  input, and `requestKeys` pass the matrix in `PatternDescTest`,
  `PatternGameTest`, `PatternSaveTest`, `PatternTraceTest`,
  `PatternInputParityTest`, and the engine `PatternTest`.

  The generator draws one `random_upto(100000000)` per cell, averages
  every 3x3 neighbourhood once in single precision, and retries until
  no row or column is monochrome and the line solver finishes. The
  five goldens, the seed sweep, and the corpus `AUXINFO` are the guard:
  a missing draw, a different float order, or a different solver bound
  changes every description. `PatternTest` pins the retry attempt
  counts, and `PatternDescTest` pins the small-size descriptions and
  the reachable solver failure on `4x4:2/1/2/1/1.1/2/1/1`.

  `PatternGameTest` compares the move grammar, the left and right
  cycles, the drag rules, the cursor keys, the immutable select, the
  no-op win, the real win, undo, redo, restart, and the solve. A solve
  after an `S` move comes from the decoded initial grid, as C reads
  `states[0]`, and the crafted `MOVE S1100` save pins that.
  `PatternInputParityTest` sends real left and right cycles at tiles
  24, 48, 63, 82, and 100 and at the 781x781 and 411x702 letterboxes,
  plus the snapped and clamped drags, the middle rectangle, an inert
  clue-band click, an immutable click, an immutable select, the cursor
  corner walk, the no-op win, and `requestKeys`. Every real cycle
  changes the grid and adds one move and one state; an inert or
  immutable click changes neither; a select on an immutable clue cell
  adds a state with no visible change, as C does. The final trace is
  compared with `pointsSized` where the input is a pointer.

  `PatternSaveTest` reads the six corpus saves byte for byte and
  reloads them in C with the same id, status, undo, redo, and text.
  `PatternTraceTest` compares the five frozen traces, a dense row with
  the one-space separator, a cold loaded
  state, the cursor ring and guided clues, a `COL_ERROR` clue, a
  completed frame, the warm path, sized traces at tiles 48, 63, 82,
  and 100, and the 411x702 letterbox. No trace has a blitter, a status
  line, or a flash frame.

  Size bound. Generation is synchronous; the app calls it on its worker:
  the
  five presets are under 30 ms, `50x50` takes about 16 s, and `100x100`
  does not finish in a minute. The owner made the size bound warning-only
  (decision 2b): `Game.generationWarning` names every board of 2500 cells
  or more, so `50x50` warns, and no parameter set is rejected. The app
  generates off the UI thread. The generator retries until no row or
  column is monochrome and the line solver finishes, which is the C loop
  with no bound; the warning names the sizes where that loop is long.
  The solve of `4x4:2/1/2/1/1.1/2/1/1` returns null
  and `Midend.solveAttempt` carries the C message, which the shell
  shows. `KotlinGames.handles` selects the Kotlin game (fifth enablement
  wave, 2026-11-18), and `FifthWaveTileSizeInputTest` clicks a cell and
  the clue band at two tile sizes on the Pixel 4a 5G. S9 is a tested
  `currentKeyLabel` helper with no Android call site.

- `signpost` is `kotlin` (fifth enablement wave, 2026-11-18). Its parameters and six presets, the decode
  and validation quirks, the generator and its RNG order, the
  description grammar, the state, the move grammar, the arrow chaining,
  the two-state solve and its two error strings, the text format with
  the `h`-column bound, the sticky completion, `changedState`, the
  palette, drawing, layout, input, `currentKeyLabel`, and `requestKeys`
  pass the matrix in `SignpostDescTest`, `SignpostGameTest`,
  `SignpostSaveTest`, `SignpostTraceTest`, `SignpostInputParityTest`,
  and the engine `SignpostTest`.

  The generator draws the free-ends head and tail pair, one
  `random_upto` per filled cell, and the `n-1` shuffle. The six
  goldens, the seed sweep, a pinned fill-retry fixture, and a sweep
  that proves the strip never retries for the presets guard the stream:
  a reordered draw or a hoisted free-ends pick changes every
  description. `SignpostTest` pins the retry counts, and
  `SignpostDescTest` pins the decode quirks and the validation order.

  `SignpostGameTest` compares the move grammar through a save replay,
  the `L`, `C`, `X`, `S`, and `H` forms, the number and colour chaining,
  undo, redo, completion, and both solver errors. A 2x2 board pins the
  `numsi` clear bound: the loop stops at `n-1`, the completion check
  reads a stale `numsi[n]`, and the removed cell is linked and numbered
  again; the text after one more move matches C. `State` carries the
  initial board and `solveAttempt` tries the current state and then the
  initial one, as C does; a link that breaks the current board pins the
  fallback. A
  board that the solver completes has one forced solution, so two
  successful but different payloads cannot occur; the order is pinned
  Kotlin-only with a different solved board as the fallback.

  `SignpostInputParityTest` sends real press-drag-release links at
  tiles 48, 63, 82, and 100 and the letterboxed 7x7c view at origin
  (2,147). It also covers the reverse right drag, the cut on a release
  outside the board, the four no-op shapes, keyboard drags and
  shortcuts, and the whole-set cut. Every real link changes the board
  and adds one state; every no-op changes neither and asserts that the
  press reached the game, so a missing press path cannot pass.

  `SignpostTraceTest` compares the six frozen traces, cold traces at
  tiles 48, 63, 82, and 100, the two 411x702 letterboxes, and the warm
  path. One `T x T` blitter is allocated in the drawstate, before the
  first `start_draw`. The warm trace pins the `blitter_load` and its
  update, the mid-drag `blitter_save`, and the frame after a release
  with no save. `traceWarm` records only the final redraw, so the erase
  on the release frame is not in a compared trace; the engine test
  records that frame itself and asserts the erase. No frame has a flash
  or a status line.

  The text form keeps the C `state->h` column bound. For `w > h` the
  last columns are dropped and that is reproduced. For `w < h` C reads
  past three arrays, so the port defines the out-of-range cell as
  direction N, number 0, and no flags; this is a definition of
  undefined behavior, not a deviation. Above 99 cells `textFormat`
  returns `""`, which is what the oracle prints for a null text, because
  `canFormatAsText` is a constant seam and the params-aware hook is
  missing.

  Size bound. Generation is synchronous; the app calls it on its worker;
  the presets
  are milliseconds, but the fill and strip loop retries through the
  solver with no bound, as C does. The owner made the size bound
  warning-only (decision 2b): `Game.generationWarning` names every board
  of 400 cells or more, so `20x20` warns, and no parameter set is
  rejected. `Midend.solveAttempt` carries the two C messages, and the shell shows
  them. The engine declares the Width, Height, and `Start and end in
  corners` configuration schema, and the app builds the dialog from it.
  The `Victory rotation effect` (`flash-type`,
  `:Unidirectional:Meshing gears`) preference reaches the live `Ui`, and
  the status text is gated on `wantsStatusBar`. The `flash-type` value
  stays on the UI and never enters the state, id, status, text, or
  save. S9 is a tested `currentKeyLabel` helper with no Android call
  site. `KotlinGames.handles` selects the Kotlin game (fifth enablement
  wave, 2026-11-18), and `FifthWaveTileSizeInputTest` drags a link and
  presses the final number at two tile sizes on the Pixel 4a 5G.

- `undead` is `dual-run`. Its parameters and eight presets, the decode
  and validation quirks, the generator and its RNG order, the
  description grammar, the state and the fixed cells, the move
  grammar, the solve and its two error strings, the text format, the
  sticky completion, `changedState`, the palette, the thick-line
  drawing, layout, input, `currentKeyLabel`, and `requestKeys` pass the
  matrix in `UndeadDescTest`, `UndeadGameTest`, `UndeadSaveTest`,
  `UndeadTraceTest`, `UndeadInputParityTest`, and the engine
  `UndeadTest`.

  The generator draws one `random_upto(5)` per cell, one
  `random_upto` per filled unique path, and one `random_upto(3)` per
  still-unfilled monster. The nine golden descriptions, the nine seed
  strings, the numeric sweep, and a pinned seed for every retry gate
  guard the stream; the `Float` ratio and the stable path sort are
  part of it. `UndeadDescTest` pins the retry counts, and `UndeadTest`
  pins the decode table.

  `UndeadSaveTest` reads all nine corpus saves, rewrites them byte for
  byte, and reloads them in C with the same id, status, undo, redo, and
  text. A Kotlin save for every preset and nine seeds is loaded by C.
  The `PARAMS`/`CPARAMS` split is pinned: a generated Easy or Tricky
  save has the Normal long form in `PARAMS` and the real difficulty in
  `CPARAMS`, and the short id drops the difficulty. In-memory history
  round-trips a real cell change, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART`. No save has a `UI` or an
  `AUXINFO` record, and a hand-written `AUXINFO` is ignored by the
  solver and kept in the text.

  `UndeadInputParityTest` sends real cell selects and count-row ghost,
  vampire, and zombie presses at tiles 48, 63, 82, and 100, plus clue
  presses at the same tiles and a real cell change and a clue press at
  the 411x702 letterbox with the measured (1,115) origin. It also
  derives the three count boxes from the oracle trace and clicks each
  exact edge, one pixel outside, and one pixel inside at tiles 64 and
  63; the state and the move count agree on both sides. A mirror and a
  fixed cell keep a visible pencil highlight, compared with the
  oracle's final frame. The pencil select pair and both right-button
  presses are UI-only at every tile and at the letterbox; the count row
  and the clue each add one move. Every pointer frame is compared with
  `pointsSized`, so the highlight and the pencil triangle are checked;
  a count-row change alters the guess and the status stays 0, a clue
  click toggles its mark, and an inert click changes neither.
  `requestKeys` is arrow mode 2 with the five labels `Ghost`,
  `Vampire`, `Zombie`, `Clear`, and `Mark`.

  Two upstream undefined behaviours have port definitions, not
  deviations. The C path arrays hold `wh` entries but a ray can revisit
  a cell through both mirror channels, so the port sizes them `4*wh`
  entries (the number of cell and direction states) and caps a walk at
  `4*wh` steps; this changes no accepted description. The cap is not
  reachable from a border entry: the ray map is injective and an orbit
  from an entry state cannot enter a cycle, so no path repeats a
  `(cell, direction)` state and every walk is shorter than `4*w*h`.
  `UndeadTest` pins the allocation and the longest known ray. In the
  solver a mask of 0 leaves `loop.guess` uninitialised in C, so the
  port defines it as "no allowed type" and stops the enumeration. The
  oracle is never asked to compare a corrupted path, and a generated
  board never reaches either case. The thick line is `draw_thick_line`
  with integer endpoints and `T/16` as a float; the trace records `t`
  in thousandths and both endpoints in thousandths, so a mirror at
  tile 64 is `thick_line 4000 ...`. `undead` is the first game-level consumer
  of the primitive.

  Enablement debt. Generation is synchronous; the app calls it on its worker;
  the presets
  are milliseconds, but a hand-written maximum `13x4` board can take
  about 1.3 s, so the app generates off the UI thread. The solver
  is a brute force bounded by `3 ** num_total`, so a hand-written
  description can be expensive and the app must run `SOLVE` off the UI
  thread. `Midend.solveAttempt` carries `Puzzle is inconsistent` and
  `Puzzle is unsolvable`, and the shell shows them. The midend applies
  `pencil-keep-highlight` (boolean, default true) and `monsters`
  (`:Pictures:Letters`, default Pictures) after `newUi` and after
  `Midend.fromSave`; the shell then forces a redraw for `monsters`;
  neither value is serialised. The engine declares the Width, Height,
  and `:Easy:Normal:Tricky` configuration schema, and the app builds the
  dialog from it. The status text is gated on `wantsStatusBar` (it is
  false). S9 is a tested `currentKeyLabel` helper
  with no
  Android call site. `UNDEAD` is enabled (third enablement wave,
  2026-11-18); its C `get_prefs` is non-NULL and the declared
  `pencil-keep-highlight` and `monsters` preferences match it, and its
  Width, Height, and difficulty configuration comes from
  `Game.configure`.
  `ThirdWaveTileSizeInputTest` taps a cell and a clue at two tile
  sizes on the Pixel 4a 5G: the cell tap only highlights it, so it adds
  no move and changes no save text (the game has no `UI` record), and
  the clue tap toggles the mark and adds one move.

- `untangle` is `kotlin` (fifth enablement wave, 2026-11-18). Its parameters and five presets, the
  decode and validation quirks, the generator and its two RNG draws,
  the description and move grammars, the state and the sticky
  completion, the solver, the empty text form, the palette, the
  drawing, layout, input, and `requestKeys` pass the matrix in
  `UntangleDescTest`, `UntangleGameTest`, `UntangleSaveTest`,
  `UntangleTraceTest`, `UntangleInputParityTest`, and the engine
  `UntangleTest`.

  The generator draws one `w*h` grid shuffle and one or more `n` label
  shuffles. The five presets, nine seeds, and a numeric sweep pin the
  stream, and the exact `make_circle` coordinate table pins the double
  `sin`/`cos` values at denominator 64. Those float coordinates reach
  the description, the auxiliary answer, the save, and every trace, so
  a host whose libm differs would change all four; the port keeps C's
  order and truncation.

  `UntangleSaveTest` reads all six corpus saves, rewrites them byte for
  byte, and compares id, status, undo, redo, text, and the raw
  `AUXINFO` bytes with C. `PARAMS` and `CPARAMS` are always equal
  because `encode_params` ignores `full`. A Kotlin save for every
  preset and nine seeds matches a fresh C game and reloads in C with
  the same text. In-memory history covers a click, a drag, an undo
  with a live redo branch, a `SOLVE`, a `RESTART`, and the empty
  `MOVE`; an `S` move fails in both engines. No save has a `UI`
  record.

  `UntangleInputParityTest` sends real press/release clicks and
  press-drag-drag-release drags at tiles 48, 63, 82, and 100 and at a
  letterbox whose origin is subtracted on both sides, and compares the
  final frame with `pointsSized`. It also covers the four arrow
  quadrants, the failed first arrow that leaves `cursorpoint = 0`, the
  select pair, the space and `SHFT+TAB` cycle, a modified arrow, the
  midend's stripping of `SHFT+CURSOR_SELECT`, the inert centre,
  letterbox, and no-press events, and `requestKeys` for every preset.
  `UntangleGameTest` pins the fixed 60-pixel hit radius at the four
  tile sizes: 59 pixels hits and 61 misses. The pointer snap is
  engine-only because the oracle's `pointsSized` has no `prefs` field:
  a one-pixel negative release commits at grid 0, and 36 pixels outside
  reaches grid -1 and cancels. The keyboard snap is compared with the
  oracle `keys` prefs.

  `UntangleTraceTest` compares the five frozen traces, a loaded save,
  sized traces, the 411x702 letterbox, the cursor frame, the press
  frame with the drag point and its neighbours, the release frame, the
  vertex-number and crossed-edge preferences, and the empty warm
  frame. Untangle draws a full settled frame, so a strict trace is
  taken from `traceNew`, a loaded save, or a drag move, never after an
  undo or a `SOLVE` in a live midend. Colour 0 is darker than the
  board, so the midend clear and the game rect differ.

  Size bound and preferences. Generation is synchronous; the app calls
  it on its worker; the presets are milliseconds, but `validate_params`
  accepts `n` up to `INT_MAX / 3`, where `newDesc` allocates about
  17 GB. The owner made the size bound warning-only (decision 2b):
  `Game.generationWarning` names every board above 100 points, and no
  parameter set is rejected. `Midend.solveAttempt` carries
  `Solution not known for this puzzle` or
  `Internal error: aux_info badly formatted`, and the shell shows them.
  The engine declares the one-field `Number of points` schema, and the
  app builds the dialog from it. `Game.setPrefs`
  applies `snap-to-grid`, `show-crossed-edges`, and `vertex-style`
  after `newUi` and after `Midend.fromSave`, with a forced redraw for
  the two drawing preferences. The status text is gated on
  `wantsStatusBar` (it is false). S9 is not needed because
  `current_key_label` is
  `NULL`. `KotlinGames.handles` selects the Kotlin game (fifth
  enablement wave, 2026-11-18), and `FifthWaveTileSizeInputTest` drags
  two vertices and presses the board corner at two tile sizes on the
  Pixel 4a 5G.

- `net` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and eight presets, the decode and
  validation quirks, the generator and its solver gate, the
  description and move grammars, the state, the FIFO solver and its
  fallback solve, the Jumble and the platform seed, the UI record, the
  palette, drawing, layout, input, and `requestKeys` pass the matrix in
  `NetDescTest`, `NetGameTest`, `NetSaveTest`, `NetTraceTest`,
  `NetInputParityTest`, and the engine `NetTest`.

  The generator is gated on `net_solver`, because every preset asks for
  a unique solution. The solver work list is a FIFO ring with a
  `deadendmax` array indexed by direction, so a different order changes
  which orientations survive, the `LOCKED` marks, and every `DESC`. The
  nine golden descriptions, nine seed strings, a numeric sweep, and the
  named `perturb`, `begin_generation`, and full-reshuffle retry
  fixtures pin that order. `AUXINFO` is the unshuffled grid, so the
  generated auxiliary answer pins the generator before the shuffle,
  even when only the barrier rate changes.

  `solve_game` reads the first state in C and diffs the current one.
  `Net.Shared` keeps the decoded initial board, and `Net.solveAttempt`
  runs `AUXINFO` or the solver on that board; `NetGameTest` and
  `NetSaveTest` pin a solve from the initial state and after real
  moves.

  `NetSaveTest` reads all nine corpus saves, rewrites them byte for
  byte, and compares id, status, undo, redo, text, `AUXINFO`, and `UI`
  with C. A Kotlin save for every preset loads in C. The `PARAMS:5x5` /
  `CPARAMS:5x5b0.5a` split is pinned for a non-preset board; for a
  preset the two are equal. In-memory history covers a rotation, a
  lock, a Jumble, an undone position with a live redo branch, a
  `SOLVE`, and a `RESTART`, and the `UI` record round-trips in both
  directions. A load decodes onto the `newUi` object, so the
  frontend random state, the cursor, and `unlocked-loops` survive; a
  save without a `UI` record keeps the base UI.

  `NetInputParityTest` sends real release-committed rotations and real
  middle locks at tiles 48, 63, 82, and 100 and at the 412x702
  letterbox, the four drag quadrants and a circular drag, the keyboard
  keys, a real Jumble, the cursor and select pair, and the origin and
  source keys. Every real rotation, lock, or Jumble changes the board
  and adds one move and one state; an inert press, band click,
  letterbox click, or modified non-cursor key changes neither. State,
  id, status, and `UI` are compared for every pointer gesture. The
  final frame is compared with `pointsSized` for the release click, the
  middle lock, the drag quadrants, and the circular drag. A rotation
  frame is compared from the reloaded moved save, because the oracle's
  own frame after a rotation is the animation-start frame.

  `NetTraceTest` compares the eight frozen traces and `traceNew` for
  every preset, sized cold traces at tiles 48, 63, 82, and 100, the
  412x702 letterbox at tile 80 with origin (1,146), the warm lock, the
  warm drag and release, the `unlocked-loops` preference, and the
  zero-size grid rectangles. No trace contains a blitter, a thick
  primitive, or a text operation.

  `NetGameTest` also pins the sticky `completed` flag from a save
  replay: a lock completes the solved board, and a later rotation or
  lock keeps status 1 and the banner even though the network is
  broken. A separate save reaches the `Auto-solved. Active: a/n2`
  branch, where a solve follows a completed-then-broken board, and
  compares the exact text with the oracle.

  D-010 rejects a NaN barrier rate instead of aborting at the C `int`
  cast, and D-011 returns a solve error instead of tripping the C
  `AUXINFO` assertion. Both entries carry a scope note: the NaN test
  runs before the 2-wide wrapping test, and a non-hex `AUXINFO`
  character reads as zero. Valid parameters, descriptions, and
  auxiliary answers are unchanged. Enablement debt. The engine declares
  the five-field configuration schema (Width, Height, `Walls wrap
  around`, `Barrier probability`, `Ensure unique solution`), and the
  app builds the dialog from it. `Game.setPrefs` applies the
  `unlocked-loops` boolean after `newUi` and after `Midend.fromSave`
  with a forced redraw, and the shell shows the solve message. D-028
  rejects an area above 10,000 cells (100x100) at `newDesc` before the
  first draw, with a fixed message; the measured 100x100 host
  generation is 0.87 s and 125x125 is 10.1 s on the phone. Validation
  and the load path keep the C behavior, so a larger save or game id
  still loads. The unbounded retry loops are the C loops. The app
  generates off the UI thread. S9 is a
  tested `currentKeyLabel` helper with no Android call site.
  `KotlinGames.handles(NET)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` rotates a tile twice
  at two tile sizes.

- `tracks` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and presets, generator and
  solver gate, state, move grammar, text form, save file, palette,
  drawing, layout, and input pass the matrix in `TracksDescTest`,
  `TracksGameTest`, `TracksSaveTest`, `TracksTraceTest`,
  `TracksInputParityTest`, and the engine `TracksTest`; the game
  inventory row lists the details. D-012 covers the outer non-clue
  track edge that aborts the C solver. D-029 rejects an area above 484
  cells (22x22) at `newDesc` before the first draw with
  `Width times height is too large for this build`: the measured
  22x22 Hard generation is 2.8-24.1 s on the phone over seeds 1-6,
  while 23x23 Hard is 10.0 s on the host already. Validation and the
  load path keep the C behavior, so a hand-written `23x22` save still
  loads and replays its moves. `KotlinGames.handles(TRACKS)` is true (seventh enablement
  wave, 2026-11-18), and `SeventhWaveTileSizeInputTest` taps a square
  and an edge band at two tile sizes.

- `dominosa` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and presets, generator with
  its three allocators and solver gate, solver, state, move grammar,
  text form, save file, palette, drawing, layout, and input pass the
  matrix in `DominosaDescTest`, `DominosaGameTest`,
  `DominosaSaveTest`, `DominosaTraceTest`,
  `DominosaInputParityTest`, and the engine `DominosaTest`; the game
  inventory row lists the details. D-013 covers a validated
  description whose highest domino has no placement. D-030 rejects a
  maximum number above 9 at `newDesc` before the first draw with
  `Maximum number is too large for this build`: `9de` is 0.9-18.4 s on
  the phone over seeds 1-5 and `10de` is 12.5 s with one seed. The cap
  is a single number on purpose;
  Basic stays fast above 9, but the measurement did not cover Hard or
  Basic at those sizes. Validation and the load path keep the C
  behavior, so a larger save still loads.
  `KotlinGames.handles(DOMINOSA)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` places and removes a
  domino at two tile sizes.

- `map` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and presets, two random streams,
  generator retry gate, solver, aux four-colouring, state, move
  grammar, text form, save file, palette, drawing, layout, and input
  pass the matrix in `MapDescTest`, `MapGameTest`, `MapSaveTest`,
  `MapTraceTest`, `MapInputParityTest`, and the engine `MapTest`; the
  game inventory row lists the details. D-014 keeps the 2^20-cell and
  4096-region structural limits on `newState` and `validateDesc`, and
  adds a generation-only cap of 100 regions with
  `Too many regions for this build`: `100x100n100dn` is 0.18 s on the
  phone, while `60x60n200dn` seed 12345 hung for 30 s. The 200-region
  test loads the C save and replays a move, so the cap is proven to be
  generation-only. The stall is seed-dependent, so the 100-region cap
  is a policy choice, not a guarantee. D-015 defines the C undefined
  and assert paths. `KotlinGames.handles(MAP)` is true (seventh
  enablement wave, 2026-11-18), and `SeventhWaveTileSizeInputTest` drags
  from a clue region to two other regions at two tile sizes.
- `mines` is `kotlin` (sixth enablement wave, 2026-11-18). Its
  parameters and four presets, the decode
  and validation quirks, the deferred interactive description, the
  gated generator with its RNG order and two upstream quirks, the
  first-click public/private description pair, the move grammar, the
  chord and button-priority rules, the death and win state, the solve,
  the text, the palette, drawing, layout, input, and `requestKeys` pass
  the matrix in `MinesDescTest`, `MinesSaveTest`, `MinesTraceTest`,
  `MinesInputParityTest`, and the engine `MinesTest`.

  The double post-increment of `nperturbs_since_last_new_open` is
  pinned by the `9x9n35` seed `18` first click: the oracle layout is
  `0,0,mf4ac480d0763fb8209c80` after 103 attempts, while removing the
  second increment gives `0,0,m45540a73c0b7fbb896938` after 101. The
  never-assigned `prevret` has no layout fixture: assigning it makes
  every tested base case retry without end, so the finite attempt count
  and the layout above are the guards. A bounded search covered 1,767
  base cases over small boards and the presets, and none reached a
  layout under the fix.

  `MinesSaveTest` reads the five frozen corpus saves, rewrites them
  byte for byte, and compares id, status, undo, redo, text, `UI`, and
  `TIME` with C; every file has no `PRIVDESC` and `TIME 0`. In-memory
  history covers an open, a flag, a correct chord, a death, a win, a
  live redo branch, a `SOLVE`, a `RESTART`, and an undone position, and
  every shape is reloaded in Kotlin and in C. The `PARAMS:9x9n8` /
  `CPARAMS:9x9n10` split, the `DESC`/`PRIVDESC` pair after the first
  click, and the record order are pinned. `TIME` uses the C `%g` writer
  and the C `atof` reader: a hand-written `3.5junk` is 3.5, `0x10` is
  16, `-0` keeps its sign, and `inf` and `nan` round-trip. A
  non-finite clock is a hand-written record only, and its status prefix
  depends on the C undefined `(int)` cast, so that prefix is a defined
  Kotlin result pinned by `MinesTest`. An invalid `RESTART` record
  reports the C message `Save file contained an invalid restart move`.

  The timer has no oracle clock: the oracle's timer callbacks are
  empty. `MinesTest` pins the `[m:ss] ` prefix, the stop on death and
  win, the sticky completion after undo, and the live clock after a
  solve. The `BUTTON_BEATS(LEFT, RIGHT)` rule is pinned in both
  orders: a right press while the left button is held is ignored, and a
  left press after a right press is accepted and writes the same
  flag-then-open save as C. The fabricated right release itself has no
  mines-visible effect.

  C undefined behavior is defined by the port and pinned, not entered
  in `DEVIATIONS.md`: `atoi` and the move `%d` scan saturate where C is
  undefined and the saturation vectors run through `decodeParams` and
  `executeMove`, `decode_ui` now reproduces the glibc `long`-to-`int`
  wrap, a non-hex bitmap character reads as zero, an RNG text that
  does not decode is a zero state, and a perturbation that C asserts
  on returns no change so the solver retries. The generator's
  `mineperturb` assertions and the unbounded retry loop are enablement
  debt, described in `TESTING.md`.

  `MinesInputParityTest` compares the layout at six viewports, real
  opens, flags, correct and wrong-flag chords, the first-click
  `PRIVDESC`, the cursor walk and the two select keys, undo and redo
  after a death, and `requestKeys` with and without the full-cursor
  hint. It sends opens at tiles 20, 48, 63, 82, and 100, a four-pixel
  letterbox at tiles 48, 63, 82, and 100, and the 411x702 letterbox,
  and compares the final frame with `pointsSized`. Every real open,
  flag, wrong-flag chord, and win changes the board and adds one state;
  an inert press, a middle press on a covered cell, a too-few-flags
  chord, a letterbox click, and a click after death or a win change
  neither, each followed by a positive control.

  Mines has no preferences, so the engine declares only the four-field
  `Width`, `Height`, `Mines` (with the `%` rule), and `Ensure
  solubility` schema, and `Midend.solveAttempt` carries the
  `Game has not been started yet` message. Generation runs on the app's
  worker. `validate_params` accepts an area up to `2^28 - 1`, and the C
  `minegen` retries with no bound, which the port mirrors. The owner
  made the size bound warning-only (decision 2b):
  `Game.generationWarning` names every board above 256 cells, the
  largest preset, and no parameter set is rejected.
  `KotlinGames.handles(MINES)` is true (sixth enablement wave,
  2026-11-18), and `SixthWaveTileSizeInputTest` taps the border, opens a
  tile, and holds a press to flag a covered tile at two tile sizes on
  the Pixel 4a 5G. Mines is the one timed game, so the device test drops
  the `TIME` record before it compares the save text. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.

- `keen` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and ten presets, the decode
  quirks and validation order, the Latin-square generator with its two
  order shuffles and two solver gates, the clue choice, the state and
  the shared blocks, the move grammar, the sticky completion, the
  `AUXINFO` solve and the clue solve, the empty text form, the palette,
  the thick block outlines, drawing, layout, input, `currentKeyLabel`,
  and `requestKeys` pass the matrix in `KeenDescTest`,
  `KeenGameTest`, `KeenSaveTest`, `KeenTraceTest`,
  `KeenInputParityTest`, the engine `KeenTest`, and the shared
  `LatinParityTest` and `LatinTest`.

  `Latin.kt` is differentially pinned before Keen uses it. The oracle's
  `latin` operation compares `latin_generate` and `latin_generate_rect`
  grids and the encoded generator state for orders 1..9, rectangles,
  and resumed states, and it compares `latin_solver` at every
  `maxdiff` from 0 to 4 over complete, unique, set, extreme-set,
  forcing, recursive, ambiguous, invalid, and impossible grids.
  `KeenDescTest` then pins Keen's own callback layer: the box order by
  class minimum, the transposed cube indexing, the ADD/MUL odometer,
  the Hard early return, and the validator. The engine `KeenTest` pins
  the five difficulty slots `0, 2, 3, 3, 4`.

  The save matrix reads all eleven corpus saves (`default` plus the ten
  presets), rewrites them byte for byte, and compares id, status,
  `canUndo`, `canRedo`, text, `textFormat`, and `AUXINFO` with C. A
  Kotlin save for every preset and nine seeds loads in C with the same
  id, text, status, and `AUXINFO`. The `PARAMS`/`CPARAMS` split is
  pinned: the long-term params are the short width decoded over the
  defaults, so `5dem` writes `PARAMS 5dn` and `CPARAMS 5dem`, and the
  short id has no `d` or `m`. In-memory history round-trips number
  entry, a pencil mark, `M`, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART` in both directions. No save has a
  `UI`, `TIME`, `PRIVDESC`, or `HEXSEED` record.

  The state of a new game comes from the long-term params and the state
  of a load from `CPARAMS`, as C does (`midend.c:628`,
  `midend.c:2634-2642`). `multiplication_only` is in the full encoding
  and not in the short one, so a new `5dem` state draws `5×` and a
  loaded `5dem` save draws `5`. `KeenGameTest` and `KeenTraceTest` pin
  both frames. The `Midend` change touches every game; the whole suite,
  including every frozen trace, stays green. `MinesSaveTest` pins the
  same rule for the mine count: a new `9x9n10` game has state `n = 8`
  from the short params and a load has `n = 10` from `CPARAMS`.

  `KeenInputParityTest` sends real presses and digits at tiles 48, 63,
  82, and 100, a right-button pencil mark and its toggle, `M` at every
  tile and at the 411x702 letterbox, the cursor and the two select
  keys, and the border, letterbox, drag, release, middle press, and
  hidden-digit cases. It compares state, id, status, undo, redo, and
  the final frame with `pointsSized`. Every real number change fills
  the cell and adds one move; `M` fills every empty pencil bitmap and
  adds one move with status 0; an inert event changes neither the
  board nor the count. `KeenTraceTest` pins the error colours for two
  equal digits and for a complete block with a wrong clue. `requestKeys`
  is arrow mode 2 with the labels `1`..`w`, `Clear`, and `Mark`, every
  key reports `needsArrows` false, and the full-cursor hint does not
  change the result.

  C undefined behavior is defined by the port and pinned, not entered
  in `DEVIATIONS.md`: `encode_params` writes a plain `d` for a
  difficulty outside `0..4`, the move `%d` scan and `atoi` saturate,
  the block-structure repeat count is bounded, and the block outline
  reserves the polyomino perimeter bound. None is reachable from a
  validated parameter set, a validated description, or a generated
  move. The `SOLVE` values come from `AUXINFO` when present, so every
  generated save solves with the stored answer.

  One C assertion is reachable from a validated description. A clue
  value that includes an operation bit can turn a larger block into a
  subtraction or division block, and C then aborts at
  `assert(n == 2)` in `solver_common` (`keen.c:377`). D-016 defines the
  Kotlin result as `DIFF_IMPOSSIBLE`; no generated game reaches it.

  Size bound. No generation guard is needed. `validate_params` already
  rejects a grid size outside `3..9` with `Grid size must be between 3
  and 9`, so the largest possible board is 9x9. The measured host cost
  is flat at 0.25-0.29 s across every difficulty, and Unreasonable is
  0.48 s on the phone. No new rule and no deviation entry is added.

  Enablement debt. The engine declares the four-field configuration
  schema (`Grid size`, `Difficulty` with
  `":Easy:Normal:Hard:Extreme:Unreasonable"`, `Multiplication only`),
  and the app builds the dialog from it. `pencil-keep-highlight` is
  applied after `newUi` and after `Midend.fromSave`, the status text is
  gated on `wantsStatusBar` (it is false), and `Midend.solveAttempt`
  carries `No solution exists for this puzzle` and `Multiple solutions
  exist for this puzzle`. The
  app generates off the UI thread (`9dn` takes about 100 ms in C but
  the retry count is unbounded). `currentKeyLabel` is a tested
  helper because the Android shell has no call site (S9).
  `KotlinGames.handles(KEEN)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` enters two digits at
  two tile sizes.

- `towers` is `kotlin` (fourth enablement wave, 2026-11-18). Its parameters and seven presets, the decode
  quirks and validation order, the Latin-square generator with the
  Easy-only empty-grid pre-check before both shuffles, the clue-removal
  shuffle above Easy, the state and the shared clues, the move grammar,
  the sticky completion, the `AUXINFO` solve and the aux-free solve,
  the text format, the palette, the 3D tower drawing, layout, input,
  `currentKeyLabel`, and `requestKeys` pass the matrix in
  `TowersDescTest`, `TowersGameTest`, `TowersSaveTest`,
  `TowersTraceTest`, `TowersInputParityTest`, and the engine
  `TowersTest`, plus the shared `LatinParityTest` and `LatinTest`.

  The generator draws one Latin square, an Easy-only pre-check that
  may restart the attempt before either shuffle, one order shuffle, and
  the clue-removal shuffle above Easy. The gate is the same solver
  call, so the strategy slots, the shared `started` flag, the
  `solver_hard` early return, and the validator are all part of the
  RNG contract. A three-by-three board at Extreme or Unreasonable
  generates at Hard while `CPARAMS` keeps the request, as C does.

  The solver keeps the literal flat cube indices. `cube[cell*w + n-1]`
  decodes, through `cubepos`, as the transposed cell of a row-major
  position, while placement uses `(pos%w, pos/w)`; both forms are
  copied as written. A "tidy" transposed index would still produce
  valid puzzles, so only the eight descriptions, the seed sweeps, the
  retry fixtures, and the solve strings catch it.

  The save matrix reads all eight corpus saves (`default` plus the
  seven presets), rewrites them byte for byte, and compares id, status,
  `canUndo`, `canRedo`, text, `textFormat`, and the raw `AUXINFO` with
  C. A Kotlin save for every preset and nine seeds loads in C with the
  same id, text, status, and `AUXINFO`. The `PARAMS`/`CPARAMS` split is
  pinned: the long-term copy is the bare width decoded over the
  defaults, so `5dh` writes `PARAMS :3:5de` and `CPARAMS :3:5dh`, and
  the short id has no `d`. In-memory history round-trips number entry,
  a pencil mark, `M`, a `D` move, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART` in both directions. No save has a
  `UI`, `TIME`, `PRIVDESC`, or `HEXSEED` record, and every save has an
  `AUXINFO`.

  `TowersInputParityTest` sends real presses, pencil toggles, and real
  number entries at tiles 48, 63, 82, and 100 and at the 411x702
  letterbox (tile 56, origin (2,148)), the 3D hit test on a board with
  tall towers, the clue bands and an absent clue, the cursor, a
  duplicate-digit error frame, and the border, drag, release, middle
  press, hidden-digit, and pencil-on-filled cases. State, id, status,
  undo, redo, and the final frame with `pointsSized` are compared.
  Every real number change fills or clears the cell and adds one move;
  an inert event changes neither the board nor the count. `requestKeys`
  is arrow mode 2 with the labels `1`..`w`, `Clear`, and `Mark`, every
  key reports `needsArrows` false, and the full-cursor hint does not
  change the result.

  Neither preference is serialised, because Towers has no `UI` record.
  `appearance` changes the drawing and the hit test, and
  `pencil-keep-highlight` changes the input only. The 2D frame and the
  keep-highlight frame are compared with the oracle's `trace` `prefs`
  field; the `pointsSized` operation has no `prefs` field, so the 2D
  hit test and the highlight cancel are pinned engine-side.

  C undefined behavior is defined by the port and pinned, not entered
  in `DEVIATIONS.md`: `encode_params` writes a plain `d` for a
  difficulty outside `0..3`, the `atoi` and move `%d` parses saturate,
  and `new_game` rejects an invalid description instead of asserting.
  None is reachable from a validated parameter set, a validated
  description, or a generated move. No Towers abort is reachable beyond
  these definitions.

  Enablement debt. The engine declares the two-field configuration
  schema (`Grid size`, `Difficulty` with
  `":Easy:Hard:Extreme:Unreasonable"`), and the app builds the dialog
  from it. `appearance` and `pencil-keep-highlight` reach the live `Ui`
  after `newUi` and after `Midend.fromSave`, with a forced redraw for
  `appearance` (Towers has no `UI` record). The status text is gated on
  `wantsStatusBar` (it is false), and `Midend.solveAttempt` carries `No
  solution exists for this puzzle` and `Multiple solutions exist for
  this puzzle`. The app generates off the UI thread (`9du`
  takes seconds). `currentKeyLabel` is a tested helper because the Android
  shell has no call site (S9). `KotlinGames.gameFor` knows the Kotlin
  game and `KotlinGames.handles` selects it for the app.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `FourthWaveTileSizeInputTest` taps the outer border (inert), selects
  a cell, and sends a real digit at two tile sizes on the Pixel 4a 5G.
  The seam is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `unequal` is `kotlin` (fourth enablement wave, 2026-11-18). Its parameters and twelve presets, the
  decode quirks and validation order, the description grammar, the
  solver-gated generator, the transposed clue slice, the Adjacent
  validator gap, state, the move grammar, completion and error marks,
  the text format, the `AUXINFO` solve, the palette, drawing, layout,
  input, `currentKeyLabel`, and `requestKeys` pass the matrix in
  `UnequalDescTest`, `UnequalGameTest`, `UnequalSaveTest`,
  `UnequalTraceTest`, `UnequalInputParityTest`, and the engine
  `UnequalTest`, plus the shared `LatinParityTest` and `LatinTest`.

  The generator draws one Latin square, one shuffle of the number
  codes, one shuffle of the four inequality codes, and then the
  solver-gated assembly and strip. The scratch list is built once and
  re-shuffled across retries, so a retry permutes the previous
  permutation. The clue choice reproduces the upstream transposed cube
  read (`hints[loc*order + j]` against the cube layout), and the
  Adjacent validator skips the last row and the last column as sources.
  A tidied slice or a fixed validator still generates valid puzzles, so
  the thirteen goldens, the nine seed strings, the numeric sweep, and
  the retry fixtures are the guard.

  The hint needs the partial cube of a failed `latin_solver_alloc`, so
  the engine exposes it (`b1c1e809`, `feat(engine): expose the partial
  latin cube`) and `H` copies the cube on success and on failure.
  `UnequalTest` pins the duplicate-digit hint, and `UnequalGameTest`
  compares the frame with the oracle.

  The save matrix reads all thirteen corpus saves, rewrites them byte
  for byte, and compares id, status, `canUndo`, `canRedo`, text,
  `textFormat`, and the raw `AUXINFO` with C. A Kotlin save for every
  preset and nine seeds loads in C with the same id, text, status, and
  `AUXINFO`. The `PARAMS`/`CPARAMS` split is pinned for the Tricky, the
  Extreme, and the Adjacent presets: `5dk` writes `PARAMS 5de` and
  `CPARAMS 5dk`, `5adk` writes `5ade` and `5adk`, and the short id
  keeps `a` but not `d`. In-memory history round-trips number entry, a
  pencil mark, a real clue band cycle, a spent `F` toggle, `M`, `H`, an
  undone position with a live redo branch, a `SOLVE`, and a `RESTART`
  in both directions. No save has a `UI`, `TIME`, `PRIVDESC`, or
  `HEXSEED` record, and every save has an `AUXINFO`.

  `UnequalInputParityTest` sends real presses, pencil toggles, number
  entries, and the clue band cycle at tiles 48, 63, 82, and 100 and at
  the 411x702 letterbox (tile 51 for `5de`, origin (3,148)); it pins
  the duplicate-digit error frame, the cursor and its clamp, and the
  border, gap, drag, release, middle, hidden-digit, and
  pencil-on-filled cases. State, id, status, undo, redo, and the final
  frame with `pointsSized` are compared. Every real number or clue
  change alters the board and adds one move; an inert event changes
  neither. `requestKeys` is arrow mode 2 with the labels
  `1`..`order`, `Clear`, `Mark`, and `Mark/Hints`, every key reports
  `needsArrows` false, and the full-cursor hint does not change the
  result.

  `pencil-keep-highlight` is the only preference. It is read on every
  key and is not serialised, because `encode_ui` and `decode_ui` are
  `NULL`; a load keeps the default true. The keyboard path sets
  `hcursor`, so the preference cannot change it and the oracle agrees
  for both values. `pointsSized` and `traceWarm` have no `prefs` field,
  so the pointer path is pinned engine-side against `unequal.c`.

  C undefined behavior is defined by the port and pinned, not entered
  in `DEVIATIONS.md`: `encode_params` writes a plain `d` for a
  difficulty outside `0..4` instead of reading past
  `unequal_diffchars`, the `atoi` and move `%d` parses saturate, and
  `new_game` rejects an invalid description instead of asserting. The
  C preconditions and the unreachable asserts are listed in
  `TESTING.md`. No Unequal abort is reachable beyond these definitions.

  Enablement debt. The engine declares the three-field configuration
  schema (`Mode` with `":Unequal:Adjacent"`, `Size (s*s)`, `Difficulty`
  with `":Trivial:Easy:Tricky:Extreme:Recursive"`), and the app builds
  the dialog from it. `pencil-keep-highlight` reaches the live `Ui`
  after `newUi` and after `Midend.fromSave`. The status text is gated on
  `wantsStatusBar` (it is false), and `Midend.solveAttempt` carries
  `Solve operation failed` and `Solve unavailable (already solved?)`. The
  app generates off the UI thread. `currentKeyLabel` is a
  tested helper because the Android shell has no call site (S9).
  `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app. `KotlinGamesTest` pins
  the enabled set and the generated save;
  `FourthWaveTileSizeInputTest` taps the outer border (inert), selects
  a cell, and sends a real digit at two tile sizes on the Pixel 4a 5G.
  The seam is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `singles` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and ten presets, the decode
  quirks and validation order, the description grammar, the gated
  generator, the state, the move grammar, the error marks and the
  sticky completion, the deduction solver, the text format, the
  palette, drawing, layout, input, `currentKeyLabel`, and `requestKeys`
  pass the matrix in `SinglesDescTest`, `SinglesGameTest`,
  `SinglesSaveTest`, `SinglesTraceTest`, `SinglesInputParityTest`, and
  the engine `SinglesTest`, including the shared `LatinParityTest`.

  The generator draws one Latin rectangle of order `max(w, h)`, one
  shuffle of the `w*h` cells, the black placement in that shuffled
  order, and one `o`-element `best_black_col` shuffle per black cell in
  increasing cell index. The black placement applies the solver after
  each cell (`solve_allblackbutone`, then `solve_removesplits`), and a
  failed solver gate reshuffles every black cell without redrawing the
  Latin rectangle; the twenty-first failure returns to the Latin draw.
  The solver order is the contract: `solve_sneaky` in the gate only,
  then `singlesep`, `doubles`, `corners`, `offsetpair` at Tricky and
  above, then the `allblackbutone`/`removesplits` loop with the `> 0`
  tests. The eleven golden descriptions, the nine seed strings, the
  numeric sweep, and the retry fixtures (`2x20dk`, `2x30dk`, `3x30dk`)
  are the guard; the engine `GenerationStats` pins that both retry
  loops ran.

  The save matrix reads all eleven corpus saves (`5x5de` plus the ten
  presets, with `default` equal to `5x5de`), rewrites them byte for
  byte, and compares id, status, `canUndo`, `canRedo`, text, and
  `textFormat` with C. A Kotlin save for every preset and three seeds
  loads in C with the same id, status, and text. The short id drops the
  difficulty, so `5x5de` and `5x5dk` share the `5x5` prefix, and
  `2x2dk` writes `PARAMS 2x2de` and `CPARAMS 2x2dk`. In-memory history
  round-trips a black and a circle click, an outside click that toggles
  `show-black-nums` without changing the save, an undone position with
  a live redo branch, a `SOLVE`, and a `RESTART` in both directions. No
  save has an `AUXINFO`, `PRIVDESC`, `UI`, or `TIME` record.

  One narrow difference is inherited: C decodes a descriptive game id
  over the previous long-term parameters, and Kotlin starts from the
  defaults (`midend.c:1874-1898`; `Midend.kt:791`). A pasted
  `5x5:DESC` therefore writes `CPARAMS 5x5de` even when the previous
  game was `5x5dk`; the description, the state, the short id, and the
  moves do not differ. This is the same shape as the `pegs` note above,
  and `SinglesDescTest` pins the Kotlin result. The save loader keeps
  `CPARAMS 5x5dk` for the same description.

  `SinglesInputParityTest` and `SinglesGameTest` send the real
  black/circle cycle at tiles 48, 63, 82, and 100 and at the 411x702
  letterbox (tile 68, origin (1,147)), so a left press is
  `empty -> black -> circle -> empty` and a right press is
  `empty -> circle -> black -> empty`. The letterbox band toggles
  `show-black-nums` and changes no state, with a click one cell inside
  as the positive control. The cursor wraps at all four edges, and
  `SinglesTraceTest` compares the four wrap frames with the oracle; a
  middle press, drag, and release are inert, and an error pair is
  marked in `COL_ERROR`. State, id, status, undo, redo, and the final
  frame with `pointsSized` are compared. Every real black or circle
  change alters the board and adds one move while the status stays 0;
  every inert event changes neither the board nor the count.
  `requestKeys` is compared on every preset: arrow mode 3 with no
  labels, and the Android full-cursor hint gives the C midend's forced
  `ARROWS_ONLY` (mode 1) with the same empty list.

  `show-black-nums` is the only preference. C keeps it in the UI, where
  it changes the number drawn on a black cell and nothing else; it is
  not in `State`, the id, the save, or `DrawState.flags`. The port
  exposes `Singles.Ui.showBlackNums` and reads it in `redraw`. Because
  `encode_ui` is `NULL`, a load creates a fresh UI; the midend applies
  the preference after `newUi` and after `Midend.fromSave`.

  D-017 defines the 62 boundary. C accepts a side of 62, generates a
  description whose digit 62 is `[`, and aborts in `new_game`; the port
  rejects generation with `Puzzle is too large` before any draw and
  keeps every side 2..61 and every preset byte-identical. D-031 then
  rejects an area above 400 cells (a side of 20 on a square board) with
  `Width times height is too large for this build` before any draw;
  the measured 21x21 Easy generation is 9.88 s on the phone and 20x20
  is 0.25 s. Validation and the load path keep the C behavior, so a
  larger save still loads. The largest
  accepted side (`61x2de` seed `3`) is compared with the oracle, and a
  hand-written `62x2de` save with `Z` still loads in both engines.
  Enablement
  debt. The engine declares the three-field configuration schema
  (`Width`, `Height`, `Difficulty` with `":Easy:Tricky"`), and the app
  builds the dialog from it. `show-black-nums` reaches the live `Ui`
  after `newUi` and after `Midend.fromSave` with a forced redraw, the
  status text is gated on `wantsStatusBar` (it is false), and
  `Midend.solveAttempt` carries `Unable to solve puzzle.`. The app
  generates off the UI thread.
  `currentKeyLabel` is a
  tested helper because the Android shell has no call site (S9).
  `KotlinGames.handles(SINGLES)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` cycles black and
  circle at two tile sizes.

- `pearl` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and eight presets, the decode
  quirks and validation order, the `DESC` grammar, the loopgen bias,
  the solver-gated generation with the upstream clue-removal bugs, the
  state, the loose move grammar and drag model, the deduction solver,
  the hint, the text form, the palette, drawing, layout, input,
  `currentKeyLabel`, and `requestKeys` pass the matrix in
  `PearlDescTest`, `PearlGameTest`, `PearlSaveTest`, `PearlTraceTest`,
  `PearlInputParityTest`, and the engine `PearlTest` and
  `PearlBiasTest`. The bias is pinned by comparing its incremental
  context with a from-scratch recomputation on every call of a real
  generation, because the oracle has no pearl bias operation.

  The generator is the C order: `generate_loop` on a square grid of
  `w-1` by `h-1` faces, the maximal clue array, the two solver gates,
  and the clue-removal list. That list is filled with the straight
  positions twice and read from `straights` for both counters; the
  second shuffle still consumes its RNG draws. The port reproduces both
  bugs because they decide every description. Three upstream
  out-of-bounds paths are defined and pinned: the drag path gets one
  extra entry, a self-crossing drag stops before a visited cell instead
  of growing past that entry, and the clue-removal buffer is `2*w*h`
  (D-019). The oracle is not asked about any of them.

  `KotlinGames.handles(PEARL)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` draws a line and marks
  two circles at two tile sizes. The engine declares
  the four-field configuration schema (Width, Height, `:Easy:Tricky`,
  `Allow unsoluble`), and the app builds the dialog from it.
  `appearance` reaches the live `Ui` after `newUi` and after
  `Midend.fromSave` with a re-run of the size and a forced redraw, the
  status line stays off (`wantsStatusBar` is false), and
  `Midend.solveAttempt` carries the two C solve messages (`Unable to
  find solution`, `invalid char in aux`). The `appearance`
  preference is a
  `Ui` field read by the UI-aware `computeSize` and by drawing; it is
  not serialized and not in the drawstate, so a redraw without the
  resize keeps the old geometry. Generation keeps the C cost and is
  seed-sensitive: `11x11de` is 1.1-5.6 s on the phone over seeds 1-6,
  `12x12de` is 0.9-25.7 s over the same seeds, `13x13de` is 43.9 s, and
  `14x14de` does not finish. D-032 rejects an area above 121 cells
  (11x11) at `newDesc` before the first draw, with a fixed message.
  Validation and the load path keep the C parameter bound, so a larger
  save still loads. The app generates off the UI thread. D-019 keeps
  its own generator guards.
  S9 is a
  tested `currentKeyLabel` helper with no Android call site.

- `loopy` is `kotlin` (seventh enablement wave, 2026-11-18). Its parameters and twenty presets, the loopy
  grid-type index (a separate list, not the `GridType` order), the
  decode quirks and validation order, the `DESC` grammar with the
  optional grid description, generation (a null-bias `generate_loop`,
  the full-clue array, the four solver stages, the uniqueness and
  "too easy" retries, and the run-length clue encoder), the state, the
  `%d[ynu]` move grammar, the three-state cycle of both pointer and
  keyboard buttons, the auto-follow walk in its two modes, the solver,
  completion, the square-only text form, the palette, the thick-line
  drawing with its cursor blitter, layout, input, and `requestKeys`
  pass the matrix in `LoopyDescTest`, `LoopyGameTest`,
  `LoopySaveTest`, `LoopyTraceTest`, `LoopyInputParityTest`, and the
  engine `LoopyTest`.

  The port keeps the C quirks that decide output: the auto-follow walk
  tests the state of the originally clicked edge while it walks the
  candidates, and when a walk returns to the clicked edge it ends only
  that direction, so C emits a closed loop twice in one move and the
  port does the same. The cursor walks in grid units until the nearest
  edge changes, and a modified cursor move is unused. The C
  `assert(params->diff < DIFF_MAX)` is a message in the port
  (D-021); it is unreachable from a save or the chooser, and a
  hand-made value writes a bare `d` that decodes as Easy. The C square
  bound `(w + 1) * (h + 1) <= INT_MAX` is pinned at 46339 and 46340.

  `KotlinGames.handles(LOOPY)` is true (seventh enablement wave,
  2026-11-18), and `SeventhWaveTileSizeInputTest` draws an edge and
  toggles another off at two tile sizes. The engine declares the
  four-field configuration schema (Width, Height, an eighteen-entry
  Grid type, and `:Easy:Normal:Tricky:Hard`), and the app builds the
  dialog from it. `draw-faint-lines` and `auto-follow` reach the live
  `Ui` after `newUi` and after `Midend.fromSave` (there is no `UI`
  record) with a forced redraw, the status line stays off
  (`wantsStatusBar` is false), and `Midend.solveAttempt` carries the
  solve payload. D-033 rejects an area above 400 cells (20x20) at
  `newDesc` before the first draw, for every grid type, with a fixed
  message; the measured 20x20 squares Hard generation is 4.14 s on the
  phone and 25x25 is 11.36 s. Validation and the load path keep the C
  parameter bound, so a larger save still loads. The app generates off
  the UI thread. S9 is not needed because `current_key_label` is NULL.

- `rect` is `dual-run`. Its five presets, the `%dx%d` parameter
  grammar with the `%g` expansion and the `a` unique flag, the
  validation order, the description grammar, the random tiling with
  the singleton repairs and the two expansion passes, the solver with
  its randomized winnowing, the stored `AUXINFO` answer, the `H`/`V`/
  `R`/`E` moves with the drag model, the text form, the palette, the
  frame cache, layout, input, and `requestKeys` pass the matrix in
  `RectDescTest`, `RectGameTest`, `RectSaveTest`, `RectTraceTest`,
  `RectInputParityTest`, and the engine `RectTest`.

  The C `execute_move` S branch returns before its completion check, so
  a cheated solve is never marked completed and its status stays 0 with
  `Auto-solved.`; the port keeps that and a test pins it. The stored
  answer short-circuits the solver, and the non-unique mode can return
  a different valid tiling when the answer is missing. An expansion
  factor that shrinks a side of 1 to 0 aborts C inside the generator;
  the port throws before it allocates (D-022). The C square bound
  `w * h <= INT_MAX` is checked before anything is allocated.

  `rect` is enabled (third enablement wave, 2026-11-18): it is in
  `APP_GAMES` and `handles(RECT)` is true. D-022 has no `Review:` line
  and the C square bound `w * h <= INT_MAX` is checked before anything
  is allocated, so the game joins a wave. `ThirdWaveTileSizeInputTest`
  taps a square centre and two edge centres at two tile sizes on the
  Pixel 4a 5G: the centre tap resolves to no `H` or `V` move, and each
  edge tap toggles that edge and adds one move. The engine declares the
  four-field configuration schema (Width, Height, Expansion factor,
  `Ensure unique solution`), and the app builds the dialog from it. The
  status bar stays on, showing `Auto-solved.` and `COMPLETED!`, and
  `Midend.solveAttempt` carries the solve payload. Generation runs off
  the UI thread. There is no `UI` record and no preference. S9 is a
  tested `currentKeyLabel` helper with no Android call site.

- `galaxies` is `kotlin` (sixth enablement wave, 2026-11-18). Its
  parameters and six presets, the
  `%dx%dd[char]` grammar, the validation order, the description grammar,
  the generator, the deduction solver with its recursion, state, the
  `E`/`U`/`M`/`A`/`S` moves, the text form, the cursor and key labels,
  the save file, palette, drawing with three blitters, layout, and input
  pass the matrix in `GalaxiesDescTest`, `GalaxiesGameTest`,
  `GalaxiesSaveTest`, `GalaxiesTraceTest`, `GalaxiesInputParityTest`,
  and the engine `GalaxiesTest`.

  The generator is the whole chain: a shuffled pass over the
  `(2w+1) x (2h+1)` space array, the block expansion and dot move, dot
  placement with the obvious associations, ten wiggliness attempts with
  a strict best, the outline pass, `check_complete`, and the solver gate
  at the requested difficulty. The RNG draws only in the two shuffles,
  so the seven golden entries, the six presets at eight seed strings,
  and the full save including `AUXINFO` pin the stream. `AUXINFO` is
  `diff_game(blank, state, true)`: an `S` prefix, one `a` association per
  tile, and one `E` per interior edge, in x-major space order.
  `solve_game` applies the stored answer when it is present, otherwise
  tries the current then the initial state at Unreasonable, clears every
  association, and returns the edge-only diff.

  The solver runs the obvious associations, the opposite-edge rule, the
  empty-tile rule, the line-of-sight expansion, the exclave extension,
  and a recursion capped at five levels. `execute_move` keeps the C
  `sscanf` grammar: whitespace around numbers, a sign, leading zeros,
  one optional trailing semicolon, saturating overflow, and no trailing
  junk. The parameters use the C `atoi`/`%d` saturation, and the short
  encoder drops `d<char>`, so an Unreasonable game writes `PARAMS 7x7dn`
  and `CPARAMS 7x7du`, and the id has no difficulty.

  A left click rounds to the nearest edge; a click exactly on a vertex
  or a tile centre rounds to the horizontal edge because the distance
  test is not strict. A right press starts an arrow drag from a nearby
  dot or from an associated tile, and the release emits `U` and/or `A`.
  The keyboard cursor walks every space coordinate, so it has a polygon
  ring on a tile, a narrow bar on an edge, a small square on a vertex,
  and a red dot on a dot. `currentKeyLabel` is a tested helper (S9 has
  no Android call site). The drawstate allocates three `TILE_SIZE`
  blitters (drag, mirror, and cursor) before the first `start_draw`, and
  an arrow is three `line` primitives. The six frozen traces, the sized
  traces at 32, 48, 63, 82, and 100, the 411x702 and 1080x1920
  letterboxes, the 200x200 viewport, tile/edge/vertex/dot cursor frames,
  a keyboard drag, the warm drag path, and the port-only mid-drag frame
  are compared.

  `GalaxiesSaveTest` reads the seven corpus saves, rewrites them byte
  for byte, compares id, status, undo, redo, text, and the reload in C,
  makes C load a Kotlin save for every preset and eight seeds, pins the
  `PARAMS`/`CPARAMS` split, and round-trips an edge click, a right drag,
  undo with a live redo branch, a `SOLVE`, and a `RESTART`. No save has
  a `UI`, `TIME`, `PRIVDESC`, or `HEXSEED` record, and every save has an
  `AUXINFO`. `GalaxiesInputParityTest` compares the layout at five
  viewports, the edge rounding, real edge clicks and right drags at
  tiles 48, 63, 82, and 100, a letterboxed click at three presets, the
  inert pointer shapes, a drag off an associated tile, the modifier
  strip, the keyboard model with the hint and the shortcuts, undo and
  redo, a real completion at tile 63 with one event, and a non-vacuous
  post-click frame.

  One C assertion in `generate_pass` (`galaxies.c:1386`) is kept as a
  defensive skip: `dot_is_possible` rejects every dot whose adjacent
  tiles would have an off-grid or associated opposite, so no generated
  dot reaches the failing `solver_obvious_dot`. The port leaves such a
  dot unassociated instead of aborting; the measured matrix does not
  reach the branch.

  D-023 defines the hand-made difficulty outside `0..1` as
  `Unknown difficulty level` and the out-of-range encoder as a bare `d`,
  which reproduces the C difficulty-2 string. `galaxies` has no
  preference and no `UI` record, and no error colour or error frame.

  `validate_params` accepts an area up to about 5.36e8 cells:
  `23169x23169` is accepted and `23170x23170` is rejected by the
  `4*w*h` bound. Generation then allocates the `(2w+1)(2h+1)` space
  array, so a large accepted board is effectively unbounded work. The C
  generator retries until the solver grades the board at the requested
  difficulty, with ten wiggliness attempts per try and no bound on the
  tries, and the port keeps that loop. The owner made the size bound
  warning-only (decision 2b): `Game.generationWarning` names every board
  above 225 cells, the largest preset, and no parameter set is rejected.
  No test generates a huge accepted board. `currentKeyLabel` is a
  tested helper because C has no Android call site.
  `KotlinGames.handles(GALAXIES)` is true (sixth enablement wave,
  2026-11-18), and `SixthWaveTileSizeInputTest` clicks an occupied
  square, clicks an edge, holds a press in the board corner, and right
  drags an arrow from a dot to a square at two tile sizes on the Pixel
  4a 5G. The seam is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `solo` is `kotlin` (eighth enablement wave, 2026-11-19) with **no
  parity claim** for the killer overflow (D-025, owner decision
  2026-11-17). Its parameters
  and sixteen presets, the `c x r`/`jigsaw`/`X`/killer grammar, the
  decode and validation order, the generator chain, the deduction
  solver, the `P`/`R`/`M`/`S` moves, the text form, the cursor and
  pencil highlight, the save file, palette, drawing, layout, and input
  are covered by `SoloDescTest`, `SoloGameTest`, `SoloSaveTest`,
  `SoloTraceTest`, `SoloInputParityTest`, and the engine `SoloTest`,
  except for the D-025 seeds described below. The generator chain
  is the `divvy` partition for a jigsaw, the killer cages, the
  randomized backtracking fill with its step cap, the symmetry classes,
  the clue removal loop, and the solver gate at the requested
  difficulty. The solver order is blockwise positional elimination, the
  four killer techniques, rows, columns, diagonals, numeric
  elimination, intersection, set elimination, positional set
  elimination, forcing chains, and recursion. `SoloDescTest` compares
  every preset with eight seeds and their full saves except the D-025
  killer seeds, the seventeen frozen descriptions at seed 12345, the
  decode quirks, the exact validation messages, the grid/jigsaw/killer
  suffix grammar, and eight symmetry values.

  The vendored `merge_some_cages` writes each adjacent pair but never
  increments `npairs` (`solo.c:3487-3494`), so its list is always empty
  and it always returns false. Killer generation therefore keeps the
  first cage layout that grades at the requested difficulty, and the
  cage-merge loop is dead code. The port reproduces the vendored
  behavior exactly, and the killer `DESC` and `AUXINFO` goldens pin it.
  A literal fix (`npairs++`) changes the killer description, so the
  frozen `3x3ka` entry and the oracle comparison fail. D-025 is a
  deliberate, owner-approved exception: C lets the singleton remover
  merge into a full cage row, which writes into the next row of
  `blocks_data` (in bounds for a non-last row, undefined for the last
  row), continues the singleton loop, and runs `gridgen` on the
  corrupted cages. The port detects the overflow, abandons the attempt,
  and retries from the advanced RNG state, so for those seeds it
  returns a different, valid killer `DESC`. The observed seeds include
  `2x2k` 1/3/4, `3jka` 1/6/8/9, and `2x2xka` 1; `3x3ka` at seeds 1-10
  does not overflow. No parity is claimed for these cases.

  `solve_game` reads `state->grid`, which C fills from `states[0]`, not
  from the current board. `Game.solveGame` has a three-argument
  overload that carries `states[0]` beside the current state, as
  `midend_solve` does (`midend.c:2109-2111`), and `Midend.solveAttempt`
  passes it. Solo solves from that first state, so a legal `RESTART`
  with a different description returns the first description's answer,
  as C does. The state keeps the initial clue grid on a shared object,
  so a solve after a wrong move also returns the original `S` answer;
  `SoloGameTest` and `SoloTest` pin the moved, no-`AUXINFO`, and
  second-solve cases, and
  `SoloSaveTest::a restart with a different description solves the
  first one` pins the restarted save against the oracle. A stored
  `AUXINFO` is returned unchanged, as C does. The generator
  writes the solved grid as `AUXINFO`, so every generated save outside
  D-025 matches C byte for byte.

  `SoloSaveTest` reads all seventeen corpus saves, rewrites them byte
  for byte, and compares id, status, undo, redo, text, and the C reload;
  it makes C load a Kotlin save for every preset and eight seeds and
  pins the `PARAMS`/`CPARAMS` split, the absence of `UI`, `TIME`,
  `PRIVDESC`, and `HEXSEED`, and the present `AUXINFO`. In-memory
  history covers a real click plus digit, undo with a live redo branch,
  a `SOLVE`, and a `RESTART`, with the C text form compared after every
  step. `SoloTraceTest` freezes all sixteen first frames, compares sized
  frames at 32, 48, 63, 82, and 100, the 411x702 and 200x200 views,
  cursor, pencil, and duplicate-digit error frames, and the warm path;
  every block is one `thick_poly` at 3.000, every cell is clipped, and
  the game allocates no blitter. `SoloInputParityTest` compares the
  layout at six presets and four tile sizes, a real click and digit at
  every view, three letterboxed views, clue, middle, border, drag, and
  release no-ops, the pencil and right-button flows, the clear and mark
  keys, the cursor and modified cursor keys, a keypad digit, undo and
  redo, and a real completion with one event.

  D-024 defines the empty grid (`r <= 0`), the hand-made difficulty,
  killer-difficulty, and symmetry ranges, and a hand-written killer
  description with an unclued cage. `validateParams` keeps the C
  acceptance and messages; only `newDesc` and `newState` refuse the
  empty grid, and only drawing and solving define the unclued cage.
  D-034 bounds generation by difficulty band: 25 symbols for Block,
  Simple, and Intersect, 16 for Set and Extreme, and 9 for Recursive,
  with `Board size and difficulty are too large to generate in this
  build` before the first draw. The bands come from the measurement:
  `5x5db` (25 symbols) is 1.24 s on the phone, `4x4da` (16) is 0.73 s
  on the host, `3x3du` (9) is 0.45 s on the host, and the next size in
  each band does not finish. Validation and the load path keep the C
  acceptance, so a larger save still loads. `SoloFirstGenerationTest`
  pins the first 25-symbol generation of a process: it generates
  `5x5db` and `5x5di` in a child JVM and compares the whole save with
  the oracle, and it repeats a generation in the same child to show the
  settled result is the same. The child runs with
  `-XX:-UseOnStackReplacement`. That flag is a test-environment
  workaround, not a parity claim: on Arch OpenJDK 17.0.21 the JIT
  miscompiles the on-stack replacement of `Solo.solver`, so a fresh
  JVM sometimes returns a different `DESC` with the same `AUXINFO` on
  the first 25-symbol generation of the process. The port is right:
  interpreted (`-Xint`), C1 only (`-XX:TieredStopAtLevel=1`), compiled
  up front (`-Xcomp`), no on-stack replacement, and Java 25 all match
  the oracle. The measurement on 2026-11-18: without the flag the same
  test failed 5 of 6 runs, and the child disagreed with the oracle at
  position 146 of the clue-removal loop with byte-identical `grid`,
  `blocks`, and solver input; with the flag it passed 4 of 4. The same
  cause explains why `GenerationLimitTest` warms `solo` up once before
  it compares the 25-symbol band. No engine change is involved; a JVM
  bug report is the follow-up.
  D-025 waives parity for the killer seeds above; every other seed and
  description is compared with the oracle, and the app runs the Kotlin
  engine on the owner decision of 2026-11-19. The two guarantees that
  remain are the save format and reload: a Kotlin `solo` save loads in
  the C build and every corpus save round-trips, which is what lets a
  player move a game between the two engines. A seed listed in D-025
  produces a different but valid and reloadable `DESC` than C for the
  same seed, and no test claims otherwise. `EighthWaveTileSizeInputTest`
  taps a cell and presses a keypad digit at two tile sizes on the Pixel
  4a 5G, and a tap on a given is inert. `currentKeyLabel`
  is a tested helper with no Android call site. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

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
- Spoken tables are `testdata/spoken/<game>.txt`: one small deterministic
  preset per game with a spoken board, listing the board shape, the
  summary, and every unit label and pointer action in board order.
  `BoardSemanticsGoldenTest` compares the live text with the table, and
  the label is `SpokenUnit.spokenLabel`, the function the shell uses.
  The tables describe our accessibility words, not oracle output, so a
  change is reviewed like a string resource and not regenerated from a
  run.
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
