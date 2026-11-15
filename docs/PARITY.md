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
| `grid`, `loopgen`, `latin` | C until the first game consumer (ADR 0006) | C |
| `renderer` | new: Compose display-list renderer; C geometry | C traces |
| games (see the table below) | mixed: `fifteen` is `kotlin`, `slant`, `samegame`, `flood`, `sixteen`, `flip`, `twiddle`, `pegs`, `guess`, `blackbox`, `cube`, and `filling` are `dual-run`, the other games are `C` | the game inventory below |

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
| `pegs` | Pegs | 7x7 Cross | 9 | no | yes | no | no | `dual-run` |
| `range` | Range | 9x6 | 4 | yes | yes | no | no | `C` |
| `rect` | Rectangles | 7x7 | 5 | yes | yes | no | no | `C` |
| `samegame` | Same Game | 5x5, 3 colors | 5 | no | yes | no | no | `dual-run` |
| `signpost` | Signpost | 4x4, corner start | 6 | yes | yes | no | no | `C` |
| `singles` | Singles | 5x5 Easy | 10 | yes | yes | no | no | `C` |
| `sixteen` | Sixteen | 4x4 | 5 | yes | yes | no | no | `dual-run` |
| `slant` | Slant | 8x8 Easy | 6 | yes | yes | no | no | `dual-run` |
| `solo` | Solo | 3x3, Block | 16 | yes | yes | no | no | `C` |
| `tents` | Tents | 8x8 Easy | 6 | yes | yes | no | no | `C` |
| `towers` | Towers | 5x5 Easy | 7 | yes | yes | no | no | `C` |
| `tracks` | Train Tracks | 8x8 Tricky | 12 | yes | yes | no | no | `C` |
| `twiddle` | Twiddle | 3x3, radius 2 | 8 | yes | yes | no | no | `dual-run` |
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
