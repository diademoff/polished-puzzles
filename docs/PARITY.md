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
| `midend` | dual-run: semantic subset in `engine/Midend.kt`; shell in C | C |
| `random` | parity: `engine/RandomState.kt`, strict differential | oracle streams |
| `SAVEFILE` container | parity: `engine/SgtpSave.kt`, both directions | C save corpus |
| `sort`, `combi` | parity: `engine/Sort.kt`, `engine/Combinations.kt` | C order |
| `dsf`, `tdq`, `findloop`, `matching`, `tree234` | parity: differential tests | C behavior |
| `divvy` | parity: `engine/Divvy.kt`, differential test | C behavior |
| `laydomino` | parity: `engine/LayDomino.kt`, differential test | C behavior |
| `grid`, `loopgen`, `latin` | C until the first game consumer (ADR 0006) | C |
| `renderer` | new: Compose display-list renderer; C geometry | C traces |
| games (see the table below) | mixed: `fifteen` is `kotlin`, `slant`, `samegame`, `flood`, `sixteen`, `flip`, `twiddle`, `pegs`, `guess`, `blackbox`, `cube`, `filling`, `netslide`, `unruly`, `range`, `lightup`, `mosaic`, `palisade`, `inertia`, and `tents` are `dual-run`, the other games are `C` | the game inventory below |

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
| `blackbox` | Black Box | 8x8, 5 balls | 5 | yes | no | no | no | `dual-run` |
| `bridges` | Bridges | 7x7, Easy | 9 | yes | yes | no | no | `C` |
| `cube` | Cube | 4x4 | 4 | no | no | no | no | `dual-run` |
| `dominosa` | Dominosa | order 6, Basic | 12 | yes | yes | no | no | `C` |
| `fifteen` | Fifteen | 4x4 | 3 | yes | yes | no | no | `kotlin` |
| `filling` | Filling | 13x9 | 3 | yes | yes | no | no | `dual-run` |
| `flip` | Flip | 5x5 Crosses | 6 | yes | yes | no | no | `dual-run` |
| `flood` | Flood | 12x12, 6 colors | 7 | yes | yes | no | no | `dual-run` |
| `galaxies` | Galaxies | 7x7 Normal | 6 | yes | yes | no | no | `C` |
| `guess` | Guess | 6 colors, 4 pegs | 2 | yes | no | no | no | `dual-run` |
| `inertia` | Inertia | 10x8 | 3 | yes | yes | no | no | `dual-run` |
| `keen` | Keen | 6x6 Normal | 10 | yes | no | no | no | `C` |
| `lightup` | Light Up | 7x7 Easy | 9 | yes | yes | no | no | `dual-run` |
| `loopy` | Loopy | 7x7 Easy, Square | 20 | yes | yes | no | no | `C` |
| `magnets` | Magnets | 6x5 Tricky | 8 | yes | yes | no | no | `dual-run` |
| `map` | Map | 20x15, 30 regions | 6 | yes | no | no | no | `C` |
| `mines` | Mines | 9x9, 10 mines | 4 | yes | yes | yes | no | `C` |
| `mosaic` | Mosaic | 10x10, aggressive | 6 | yes | yes | no | no | `dual-run` |
| `net` | Net | 5x5, non-wrapping | 8 | yes | no | no | yes | `C` |
| `netslide` | Netslide | 3x3 | 9 | yes | no | no | no | `dual-run` |
| `palisade` | Palisade | 5x5, regions of 5 | 4 | yes | yes | no | no | `dual-run` |
| `pattern` | Pattern | 15x15 | 5 | yes | yes | no | no | `C` |
| `pearl` | Pearl | 8x8 Tricky | 8 | yes | yes | no | no | `C` |
| `pegs` | Pegs | 7x7 Cross | 9 | no | yes | no | no | `dual-run` |
| `range` | Range | 9x6 | 4 | yes | yes | no | no | `dual-run` |
| `rect` | Rectangles | 7x7 | 5 | yes | yes | no | no | `C` |
| `samegame` | Same Game | 5x5, 3 colors | 5 | no | yes | no | no | `dual-run` |
| `signpost` | Signpost | 4x4, corner start | 6 | yes | yes | no | no | `C` |
| `singles` | Singles | 5x5 Easy | 10 | yes | yes | no | no | `C` |
| `sixteen` | Sixteen | 4x4 | 5 | yes | yes | no | no | `dual-run` |
| `slant` | Slant | 8x8 Easy | 6 | yes | yes | no | no | `dual-run` |
| `solo` | Solo | 3x3, Block | 16 | yes | yes | no | no | `C` |
| `tents` | Tents | 8x8 Easy | 6 | yes | yes | no | no | `dual-run` |
| `towers` | Towers | 5x5 Easy | 7 | yes | yes | no | no | `C` |
| `tracks` | Train Tracks | 8x8 Tricky | 12 | yes | yes | no | no | `C` |
| `twiddle` | Twiddle | 3x3, radius 2 | 8 | yes | yes | no | no | `dual-run` |
| `undead` | Undead | 4x4 Normal | 8 | yes | yes | no | no | `C` |
| `unequal` | Unequal | 4x4 Easy | 12 | yes | yes | no | no | `C` |
| `unruly` | Unruly | 8x8 Trivial | 7 | yes | yes | no | no | `dual-run` |
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
- `slant` is the second port target. Its generator, solver, state,
  moves, undo/redo, solve, text, keys, save file, drawing, layout, and
  input pass the matrix in `SlantDescTest`, `SlantGameTest`,
  `SlantSaveTest`, `SlantTraceTest`, and `SlantInputParityTest`. It is
  `dual-run`: `KotlinGames.gameFor` knows the Kotlin game, but
  `KotlinGames.handles` keeps the app on C until the configuration
  dialog, cursor drawing, and the two-release wait are done. The seams
  are `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.
- `samegame` is `dual-run`. Its generator, state, moves, score,
  selection, status text, save file, drawing, layout, and pointer input
  pass the matrix in `SameGameDescTest`, `SameGameGameTest`,
  `SameGameSaveTest`, `SameGameTraceTest`, and
  `SameGameInputParityTest`. The pointer test uses the oracle's
  `pointsSized` operation, so a select and a remove run in one C midend
  and are compared with the Kotlin `processKey` path.
  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses
  C: the configuration dialog, the `Fifteen` casts in
  `KotlinGameEngine`, and the two-release wait are open. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.
- `sixteen` is `dual-run`. Its generator, state, moves, loose move
  bounds, cursor modes, keypad null move, status text, solver, save
  file, drawing, layout, and pointer input pass the matrix in
  `SixteenDescTest`, `SixteenGameTest`, `SixteenSaveTest`,
  `SixteenTraceTest`, and `SixteenInputParityTest`. The save test pins
  the `full`-argument quirk: `m` stays in the short id and in both
  `PARAMS` and `CPARAMS`, unlike flood and fifteen. `KotlinGames.gameFor`
  knows the Kotlin game, but the app still uses C: the configuration
  dialog and the two-release wait are open. A `SOLVE` record is undone
  and redone against the oracle, because the midend clears the animation
  for every record type that is not `MOVE`. A `MOVE` record whose move
  text is `S` is Kotlin-only: the C debug oracle asserts in `game_redraw`
  when that whole board is replaced, which is an upstream animation bug
  in the test tooling, not a deviation. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.
- `twiddle` is `dual-run`. Its generator, state, rotations, orientation,
  moves, status text, solver, save file, drawing, layout, and input pass
  the matrix in `TwiddleDescTest`, `TwiddleGameTest`, `TwiddleSaveTest`,
  `TwiddleTraceTest`, and `TwiddleInputParityTest`. The save test pins
  the `full`-argument quirk: `m` and the `r`/`o` flags stay in the short
  id and in both `PARAMS` and `CPARAMS`. A solve sets `completed = 1`
  and status +1, as in C, but it never raises the completion event;
  `changedState` decides that. The rotation animation and the
  completion flash are not ported, which `PARITY.md` section 1 allows,
  so strict traces come from loaded states or `traceNew`, never from a
  move in a live midend. `KotlinGames.gameFor` knows the Kotlin game,
  but the app still uses C: the configuration dialog and the
  two-release wait are open. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.
- `pegs` is `dual-run`. Its parameters, generator, state, moves, drag
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
  the id do not. `KotlinGames.gameFor` knows the Kotlin game, but the
  app still uses C: the configuration dialog, the `Fifteen` casts in
  `KotlinGameEngine`, the unconditional status text, the missing
  no-preferences path, and the two-release wait are open. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.
- `guess` is `dual-run`. Its parameters, generator, loose move and UI
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
  preference lives on the engine UI with a false default until the app
  adapter applies the stored value. `KotlinGames.gameFor` knows the
  Kotlin game, but the app still uses C: the preference schema, the
  configuration dialog, the app adapter, and the two-release wait are
  open. The seam is `docs/adr/0007-kotlin-game-seam.md` and
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
  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses C:
  the configuration schema, the preference-dialog skip, the `Fifteen`
  casts in `KotlinGameEngine`, and the two-release wait are open. The
  seam is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.
- `cube` is `dual-run`. Its parameters, generator, state, roll, face
  permutation, loose move grammar, completion, status text, save file,
  palette, drawing, layout, input, and `requestKeys` pass the matrix in
  `CubeDescTest`, `CubeGameTest`, `CubeSaveTest`, `CubeTraceTest`, and
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
  `validate_params` accepts a huge area and then enumerates every grid
  square, as C does; that guard is enablement debt, not a deviation (see
  `TESTING.md`). `KotlinGames.gameFor` knows the Kotlin game, but the
  app still uses C: the configuration schema, the preference-dialog
  skip, the `Fifteen` casts in `KotlinGameEngine`, the huge-area guard,
  and the two-release wait are open. The seam is
  `docs/adr/0007-kotlin-game-seam.md`.
- `filling` is `dual-run`. Its parameters, description generator,
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
  allocates about 8.6 GB of boards and hangs, as C does. That guard is
  enablement debt, not a deviation (see `TESTING.md`). `KotlinGames.gameFor`
  knows the Kotlin game, but the app still uses C: the configuration
  schema, the preference-dialog skip, the `Fifteen` casts in
  `KotlinGameEngine`, the huge-area guard, the S9 call site for
  `currentKeyLabel`, and the two-release wait are open. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `netslide` is `dual-run`. Its parameters and presets, the `tree234`
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
  about 4.3 GB. That guard is enablement debt, not a deviation (see
  `TESTING.md`). `KotlinGames.gameFor` knows the Kotlin game, but the
  app still uses C: the five-field configuration dialog, the
  preference-dialog skip, the `Fifteen` casts in `KotlinGameEngine`,
  the huge-area guard, and the two-release wait are open. `currentKeyLabel`
  is a tested helper because C has no Android call site for it. The seam
  is `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `unruly` is `dual-run`. Its parameters and presets, the run-length
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
  the solver over it. No test calls `newDesc` or `newState` on a huge
  accepted area; `validateParams` pins both the accepted `46340x46340`
  boundary and the rejected `65536x65536` side, and the generation
  tests run with a timeout. That guard is enablement debt, not a
  deviation (see `TESTING.md`). `KotlinGames.gameFor` knows the
  Kotlin game, but the app still uses C: the four-field configuration
  dialog, the preference-dialog skip, the `Fifteen` casts in
  `KotlinGameEngine`, the huge-area guard, and the two-release wait are
  open. `currentKeyLabel` is a tested helper because C has no Android
  call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `range` is `dual-run`. Its parameters and presets, the `signed char`
  decode and the validation order, the run-length description, the two
  shuffles per generator attempt with its retries, the four-technique
  solver, state, the `sscanf("%c,%d,%d")` move grammar, completion,
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

  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses
  C: the two-field configuration dialog, the preference-dialog skip,
  the `Fifteen` casts in `KotlinGameEngine`, and the two-release wait
  are open. The `Mouse button order` preference (`fill` or `dot`) has
  no engine hook yet; the adapter must parse
  `left-mouse-button=fill|dot` and set `Range.Ui.swapButtons` after
  `newUi` and after `Midend.fromSave`, because there is no `decodeUi`
  and a load recreates the UI. The oracle's `pointsSized` and
  `traceWarm` have no `prefs` field, so the swapped click is pinned on
  the engine side against `range.c:1368-1375`; a `prefs` field is
  needed before that click can be compared with C. `currentKeyLabel`
  is a tested helper because C has no Android call site for it. The
  seam is `docs/adr/0007-kotlin-game-seam.md` and
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
  default. The app adapter must parse it and set the live UI after
  `newUi` and after `Midend.fromSave`, then force a redraw; the value
  must not enter `DrawState.flags`, because C reads it in `tile_redraw`
  and a warm frame after a preference change alone is empty.
  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses
  C: the configuration schema (Width, Height, `%age of black squares`,
  Symmetry, Difficulty), the preference schema and application, the
  `Fifteen` casts in `KotlinGameEngine`, and the two-release wait are
  open. `currentKeyLabel` is a tested helper because C has no Android
  call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `mosaic` is `dual-run`. Its parameters and six presets, the decode
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
  needed. An aggressive `100x100` board takes 30-45 s in C; the app
  must generate off the UI thread, or accept a guard that C does not
  have. `KotlinGames.gameFor` knows the Kotlin game, but the app still
  uses C: the configuration schema (Height, Width, Aggressive), the
  preference-dialog skip, the `Fifteen` casts in `KotlinGameEngine`,
  and the two-release wait are open. `currentKeyLabel` is a tested
  helper because C has no Android call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `palisade` is `dual-run`. Its parameters and four presets, the decode
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
  preference is serialized, so the app must apply both to the live UI
  after `newUi` and after `Midend.fromSave`, call `forceRedraw`, and
  re-request the keys for `cursor-mode`. `KotlinGameEngine.requestKeys`
  already passes `palisadeFullCursor` to `Palisade.requestKeys(params,
  fullCursor)`. The completion flash is not ported; animations and
  transitions may differ (section 1) and the oracle's `allow_flash` is
  0, so no trace can contain it.

  D-007 covers generation: C accepts an area up to `INT_MAX` and then
  allocates tens of gigabytes, so the port rejects an area above 2^20
  cells at `newDesc` before any RNG draw. Validation keeps the C
  messages and the accepted `46340x46340` boundary. The app's
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

  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses
  C: the three-field configuration dialog (Width, Height, Region size),
  the two-preference schema and its application, the `Fifteen` casts in
  `KotlinGameEngine`, and the two-release wait are open. S9 is not
  needed, because `current_key_label` is `NULL`. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `inertia` is `dual-run`. Its parameters and three presets, the
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
  finish. The port rejects an area above 2^20 cells at `newDesc` before
  any RNG draw. Validation keeps the C messages and the accepted
  `46340x46340` boundary; only generation is guarded.

  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses
  C: the two-field configuration dialog (Width, Height), the
  preference-dialog skip, the `Fifteen` casts in `KotlinGameEngine`,
  the D-008 guard message, and the two-release wait are open. The app
  `solveEvent` must show a message instead of throwing when the board
  is already solved or dead. `currentKeyLabel` is a tested helper
  because the Android shell has no call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `tents` is `dual-run`. Its parameters and six presets, the decode
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

  Enablement debt. `validate_params` accepts any area up to `INT_MAX`,
  and Easy on a large board is slow: 20x20 takes about 0.6 s and 25x25
  Easy takes 39 s in C. Generation must move off the UI thread, and a
  size bound is an owner decision that needs a deviation entry and a
  test. The params-dependent `can_format_as_text_now`
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
  byte. `KotlinGames.gameFor` knows the
  Kotlin game, but the app still uses C: the three-field configuration
  dialog (Width, Height, Difficulty with `":Easy:Tricky"`), the
  preference-dialog skip (`get_prefs` is `NULL`), the `Fifteen` casts
  in `KotlinGameEngine`, and the two-release wait are open. The app
  `solveEvent` must show a message instead of throwing when a
  hand-written board fails to solve. `currentKeyLabel` is a tested
  helper because the Android shell has no call site for it. The seam is
  `docs/adr/0007-kotlin-game-seam.md` and
  `docs/adr/0009-game-ui-and-auxinfo.md`.

- `magnets` is `dual-run`. Its parameters and eight presets, the decode
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

  Enablement debt. Generation runs on the calling thread today:
  `16x14de` takes about 5.9 s and `20x20` does not finish in 90 s,
  while the eight presets are under 20 ms. The app must generate off
  the UI thread. `validate_params` accepts an area up to `INT_MAX`, so
  a size bound would reject parameters that C accepts; that is an owner
  decision that needs a deviation entry and a test, and no params bound
  is added in this slice. `LayDomino.checkedArea` is the only area
  guard on the path: it rejects an area above `INT_MAX / 2` with
  `laydomino area is too large`, because the C candidate list count
  `2*w*h` must fit an `int`. That guard rejects only areas the presets
  and tests never generate, and it runs before the first RNG draw.
  `KotlinGames.gameFor` knows the Kotlin game, but the app still uses
  C: the S8 configuration schema (Width, Height,
  Difficulty with `":Easy:Tricky"`, Strip clues), the
  preference-dialog skip (`get_prefs` is `NULL`), the `Fifteen` casts
  in `KotlinGameEngine`, the generation off the UI thread, and the
  two-release wait are open. S9 is a tested `currentKeyLabel` helper
  with no Android call site. `MAGNETS` is not in `APP_GAMES`, so
  `handles(MAGNETS)` is false.

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
