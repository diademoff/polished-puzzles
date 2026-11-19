# Testing

This document describes test levels, tools, the corpus, and CI.
The parity contract is in `PARITY.md`; commands are in `AGENTS.md`.

## 1. Levels

| Level | Where | Time | What it checks |
|---|---|---|---|
| Unit (JVM) | `app/src/test`, `engine/src/commonTest` | seconds | pure logic, RNG vectors, parsers, normalizers |
| Parity (JVM + oracle process) | `parity/` | seconds to minutes | equivalence between C and our implementation |
| Instrumentation | `app/src/androidTest` | minutes | JNI, lifecycle, real Android |
| Screenshot | `app/src/androidTest` or JVM | minutes | layout, theme, UI regressions |
| Fuzz | `tools/fuzz` | hours, scheduled | crashes, UB, deserialization |

Rule: **fast tests must stay fast**. Anything that needs an emulator
does not go into `testDebugUnitTest`.

## 2. Oracle

- Built for the host: `tools/oracle/build.sh`
  -> `build/oracle/puzzles-oracle`.
- Speaks line-based JSON, stateless: the state is passed as a string.
- One process per test class; use a batch/trace mode when needed.
- An oracle crash is a test failure, not a skip.
- The protocol version is in `UPSTREAM.lock` (`oracle_protocol`).

Minimal scenario:

```
new(game, params, seed) -> state
key(state, x, y, k)     -> state + flags
keySized(state, w, h, x, y, k) -> state after resizing
gestureSized(state, w, h, x0, y0, x1, y1, steps) -> state after a swipe
pointsSized(state[, w, h], points[, trace]) -> state after several clicks
traceWarm(state[, w, h], points) -> state + warm trace
layout(state, w, h)     -> game size after midend_size
config(game[, params])  -> title + settings item list
configOK(game[, params], values) -> full params, or the validation error
prefs(game[, state, text]) -> preference item list, or the saved text
defaultParams(game)     -> full default params
keys(state, seq[, prefs]) -> state + flags
undo/redo(state)        -> state + flags
serialise(state)        -> savefile
load(savefile)          -> state
solve(state)            -> state
requestkeys(state[, fullCursor]) -> arrow mode + key labels
trace(state[, seq, prefs]) -> draw primitives
randomBits(seed|state)  -> values + encoded generator state
randomUpto(seed|state)  -> values + encoded generator state
sort(values, mode)      -> sorted values
combi(r, n)             -> combinations in upstream order
dsf(kind, n, ops)       -> roots, sizes, minima, flip senses
divvy(w, h, k, seed|state) -> roots, sizes, attempts, encoded state
latin(kind, seed|state, grid, maxdiff) -> grid, state, difficulty, isLatin
tdq(n, ops)             -> every removal
findloop(n, edges)      -> loop edges and bridges
matching(nl, nr, adj)   -> matching and permuted adjacency
tree(ops)               -> operation results and index order
grid(kind, type, w, h, ...) -> grid incidence, desc, error, edge, or size
```

`id` re-reads a save file. It is used by the save corpus test to prove
that a Kotlin-written file is accepted by C.

`keys` replays a whole sequence in one midend. It is required for
cursor games: the cursor lives in the frontend state (`game_ui`), which
is not part of the save file, so separate `key` requests would reset it.
The optional `prefs` field applies game preferences first, in the
upstream preferences file format, so a game mode that only `game_ui`
holds (for example palisade's `cursor-mode=full`) can be compared at the
state level.

`config`, `configOK`, `prefs`, and `defaultParams` expose the settings
and preference seam. `config` returns `game_configure` in C order and
`configOK` applies `index=value` edits before `custom_params`,
`validate_params`, and the full encoding. `prefs` returns the
`CFG_PREFS` schema, or with a `state` and a `text` loads the text into a
live midend and saves it again. The state is required because C applies
the values to `me->ui` and reads them back from it. `defaultParams`
returns the full default parameter text.

`requestkeys` asks the game for its extra keys and the arrow mode. The
optional `fullCursor` field passes the Android full-cursor hint to the
game, as `android.c` does through `midend_request_keys_by_game`. Without
it the operation keeps the old behaviour, which always passes false.

`layout` runs `midend_size(..., user_size = true)` for a viewport and
reports the resulting board size. `keySized` does the same, then subtracts
the letterbox origin and sends a game-local key, as `android.c` does, so a
differential test can exercise input at a tile size and origin other than
the preferred ones. The ordinary `key` operation uses the preferred size
and cannot see a mismatch between the drawn and the input geometry.

`gestureSized` resizes and removes the letterbox origin the same way,
then sends the shell's swipe sequence: `DOWN` at the start, interpolated
`DRAG` events, then `UP` at the end. It catches a game that handles a click but not a drag or a
release, and it checks that a swipe does not add more moves than C does.

`pointsSized` takes a semicolon-separated list of `x,y,button` triples
and applies every click inside one midend, so a game whose two-step
removal keeps the first step in `game_ui` can be compared with C. The
optional `w` and `h` size the midend to a viewport and remove the
letterbox origin, like `keySized`. The state fields and the trace
header then report the size the midend draws at, not the preferred
size. With `trace: 1` the response also
holds one final redraw. The drawing layer caches the last status text,
so the tool resets that cache before the final redraw: the trace carries
the status line exactly once, including the selection text that is not
visible through a plain `trace` after a key sequence.

`trace` records one `midend_redraw` through the recording drawing API.
`traceNew` creates the game first, so a trace does not depend on the
save format. The `trace` operation accepts an optional key sequence and
preferences, so a trace can cover frontend state such as a visible
cursor or a grounded fade. The contract is
`docs/adr/0003-strict-draw-traces.md`.

`inertia_follow <bool>` is an Android-only drawing callback inside a
trace. `inertia` emits it from `game_redraw` after the cells and before
the status line, so the shell can hide the primary controls while the
auto-solver steers the player. The value is
`cheated && soln != null && solnpos < len`. `KotlinTraceSink` records
the same operation at the same position, and the app forwards it to
`ActivityCallbacks.inertiaFollow`.

`traceWarm` records a redraw on a drawstate that is already warm. It
opens the save, draws one full frame and applies its `x,y,button`
points with a `discard` flag in the trace callbacks. The flag stops
operation recording but does not stop blitter allocation, drawing, or
the id counter, so the game's cache reaches the state a second frame
would see. The tool then clears the recorded operations, keeps the
drawstate and the blitter ids, enables recording, and calls
`midend_redraw` once. The optional `w` and `h` size the midend and
subtract the letterbox origin before the points, as `pointsSized`
does. The result is the one trace that the cold operations cannot
produce: a dirty-cell-only redraw with `blitter_load`, and no
`blitter_new`. A warm trace therefore has no whole-window clear and no
`changed_state`, and its blitter operations refer to an id allocated
before recording started. A consumer must not feed it to
`TraceNormalizer` without a live-blitter seed; `PegsTraceTest` injects
the allocation for validation.

One upstream bug affects the test tooling, not the port: `sixteen`'s
`game_redraw` asserts when a whole board is replaced. The midend clears
the animation for every record type that is not `MOVE`, so a `SOLVE` or
`RESTART` record is safe to undo and redo. A `MOVE` record whose move
text is `S` is not: the C debug oracle aborts at `sixteen.c:1076` on
undo from it and on redo into it. `SixteenSaveTest` compares the `SOLVE`
history with the oracle and pins that one `MOVE :1:S` save in Kotlin
only, with a comment. This is an upstream animation bug
(`work/specs/sixteen.md`, section 10, risk 2), not a game deviation.

## 3. Parity tests

Every test declares:

- the game;
- the level (`strict`, `semantic`, `ui`);
- the input: params, seed, key sequence;
- what is compared.

A conceptual example:

```kotlin
@ParityTest(game = "fifteen", level = Level.SEMANTIC)
fun `undo restores previous state`() {
    val start = oracle.newGame("fifteen", "4x4", seed = "12345")
    val afterMove = oracle.key(start, 0, 0, 'L'.code)
    val undone = oracle.undo(afterMove)
    assertEquals(start.canonicalState(), undone.canonicalState())
}
```

A test must not know about Android. If it needs an emulator, it is an
instrumented test, not a parity test.

### Shared components

Shared libraries have their own differential suite before a game uses
them:

- `GridParityTest` covers `Grid`, `Penrose`, `Hat`, and `Spectre`
  through the `grid` op: the description generator, the validation
  messages, `grid_new`, the size, the nearest edge, and the incentre
  for every type, including the aperiodic Penrose P2 and P3, hat, and
  spectre patches.
- `LoopgenParityTest` covers `Loopgen` through the `loopgen` op, which
  offers the `none` and `count` biases. `pearl` is the first game
  consumer of both libraries. D-020 rejects a one-face grid in
  `generateLoop`, where upstream `can_colour_face` never returns;
  `LoopgenTest` pins the exception, and the oracle op has the same
  guard.
- Pearl's own bias has no oracle op. `PearlBiasTest` compares the
  incremental context with a from-scratch rebuild on every call of a
  real generation, including the vertex bits, the neighbour arrays, the
  to-do queues, and the score. `PearlDescTest`, the nine goldens, the
  eight traces, and the nine corpus saves pin the result end to end.
- D-019 defines the three upstream buffer overruns (the drag path, the
  self-crossing drag extension, and the clue-removal list). `PearlTest`
  pins the `sz + 1` drag entry, the 24-edge committed path, the stop at
  a visited cell, the `2*w*h` clue-removal capacity, and the repeated
  straight list. `PearlInputParityTest` sends the self-crossing gesture
  through the real input path at tile 48. The oracle is not asked about
  any of these paths, because the unmodified build overruns its heap.
- Pearl generation keeps the C cost and the C parameter bound:
  `13x13de` takes 1.4 to 14.6 seconds and `14x14` or larger does not
  finish in 25 seconds, so the app generates off the UI thread. A
  size limit is an owner decision (D-019).

### App adapter tests

The app adapter is covered on the JVM, without an Android runtime:

- `KotlinGameEngineConfigTest` opens every dialog through a fake
  `ConfigBuilder`: the field order and defaults for `fifteen` and
  `slant`, every item type, `configOK` for every preset and for an
  invalid edit with the C message, the seed and game-ID validation
  errors, the `one-key-shortcuts`-only page for a game without game
  preferences, the page for a game with them, the apply-before-save
  order, and the `wantsStatusBar` gate.
- `KotlinPrefsEngineTest` builds the preference page with no midend,
  no UI, and no generation, and checks the schema, the parse, an edit,
  the save round trip, and the unchanged generated save.
- `GameGeneratorTest` checks the shared worker submission: success,
  a `RuntimeException` mapped to the failure callback, and a cancelled
  task that leaves no callback.
- `KotlinGamesTest` checks that `handles` is true for exactly the
  enabled set (`fifteen`, `samegame`, `flood`, `pegs`, `sixteen`,
  `flip`, `twiddle`, `guess`, `blackbox`, `lightup`, `undead`, `rect`,
  `slant`, `range`, `towers`, and `unequal`) and false for
  every other backend, that the engine registry `PortedGames` covers
  every `BackendName` and resolves through `gameFor`, that the prefs
  gate rejects a game with C preferences but no schema (and a stray
  schema), that `defaultParams` is the engine's full default for every
  known game, that each enabled generator writes its frozen save and
  reloads it through `Midend` with the same backend, and that the
  `fifteen` generator is a pure function of its arguments.
- `ConfigNameIndexTest` checks that a duplicate display name in an open
  dialog resolves to the first item, as `configItemWithName`
  (`android.c:402-414`) does.

The instrumented `GamePlayParameterizedTest` still covers the real tap,
swipe, and key paths for every enabled game on a device or emulator.

The instrumented adapter tests cover the shell seams that a JVM test
cannot reach:

- `FifteenTileSizeInputTest` reads the live board geometry, checks that
  the tile size is not the preferred one (a viewport that yields the
  preferred tile reports a skip), changes the `limitDpi`
  preference and checks that the size follows, then sends a real tap and
  a real swipe at cell centres computed from `originX`, `originY`, and
  the tile size. Each gesture must add exactly one move.
- `FirstWaveTileSizeInputTest` is the shared device test for the first
  enablement wave. One parameterized case per game (`samegame`,
  `flood`, `pegs`) launches the game, reads the live `BoardGeometry`,
  sends a real tap at the default `limitDpi`, changes `limitDpi` to
  `off`, checks that the tile changed and is not the preferred one, and
  sends a real swipe at cell centres computed from the second geometry.
  It asserts `KotlinGameEngine`, the move count before and after each
  gesture, that the save text changes exactly when a move was applied,
  and the status line. The shell's `dragMode` decides the gestures:
  `samegame` and `flood` are `PREVENT`, so their swipe is ignored and
  their move gesture is a tap (`samegame` needs two, select then
  remove); `pegs` is `REVERT_OFF_SCREEN`, so its tap cancels and its
  swipe moves a peg.
- `SecondWaveTileSizeInputTest` is the shared device test for the second
  enablement wave. One parameterized case per game (`sixteen`, `flip`,
  `twiddle`, `guess`) launches the game, reads the live `BoardGeometry`,
  and runs a step script on the board at the default `limitDpi`, then
  changes `limitDpi` to `off`, checks that the tile changed and is not
  the preferred one, and runs the second script there. Every point is
  computed from the geometry the drawing layer uses, so the cells come
  from the same tile and origin. Each step asserts the move count, that
  the save text changed exactly when the gesture was a move or a stored
  draft, and the status line when the game has one. The shell's
  `dragMode` decides the gestures: `sixteen` (`PREVENT`) taps the frame
  band and its swipe is swallowed; `flip` (`PREVENT`) taps a cell and
  its swipe is swallowed; `twiddle` (`UNMODIFIED`) taps a rotation band
  and its inert drag starts in the strip the game rejects; `guess`
  (`REVERT_TO_START`) taps submit with no draft, drags a colour into a
  guess peg, and taps submit again.
- `ThirdWaveTileSizeInputTest` is the shared device test for the third
  enablement wave. One parameterized case per game (`blackbox`,
  `lightup`, `undead`, `rect`) launches the game, reads the live
  `BoardGeometry`, and runs a step script on the board at the default
  `limitDpi`, then changes `limitDpi` to `off`, checks that the tile
  changed and is not the preferred one, and runs the second script
  there. Every point comes from the geometry the drawing layer uses.
  Each step asserts the move count and that the save text changed
  exactly when the gesture was a move; these four games have no `UI`
  record, so an inert tap leaves the save text alone. The C input code
  decides the gestures: `blackbox` fires a laser with a tap on a top
  range cell; `lightup` taps a point in the frame (no cell, inert) and
  then white cells (one light and one move each); `undead` taps a cell
  (highlight only, no move) and a clue (the mark toggles, one move);
  `rect` taps a square centre (inert) and edge centres (`H` moves).
  `blackbox` also asserts its live status line.
- `FourthWaveTileSizeInputTest` is the shared device test for the fourth
  enablement wave. One parameterized case per game (`slant`, `range`,
  `towers`, `unequal`) launches the game, reads the live
  `BoardGeometry`, and runs a step script at the default `limitDpi`,
  then changes `limitDpi` to `off`, checks that the tile changed and is
  not the preferred one, and runs the second script there. Every point
  comes from the geometry the drawing layer uses, and every game's
  stored preferences are reset to the C defaults for the run. Each step
  asserts the move count, that the save text changed exactly when the
  gesture was a move, and the exact `MOVE` record. The C input code
  decides the gestures: `range` taps a clue (inert) and an empty cell
  (`B` move); `slant` taps the outer border (inert) and a square
  (a `\` move); `towers` and `unequal` tap the outer border (inert),
  select a cell with a tap, and send a real hardware digit through
  `ViewActions.pressKey` (`R` move).
- `FifthWaveTileSizeInputTest` is the shared device test for the fifth
  enablement wave. One parameterized case per game (`magnets`,
  `pattern`, `mosaic`, `signpost`, `untangle`) launches the game, reads
  the live `BoardGeometry`, and runs a step script at the default
  `limitDpi`, then changes `limitDpi` to `off`, checks that the tile
  changed and is not the preferred one, and runs the second script
  there. Every point comes from the geometry the drawing layer uses, and
  each game's stored preferences are reset to the C defaults for the
  run. Each step asserts the move count, that the save text changed
  exactly when the gesture was a move, and the exact `MOVE` record. The
  C input code decides the gestures: `magnets` clicks a cell (`+` move)
  and the border corner (inert); `pattern` clicks an unknown cell (`F`
  move) and the clue band (inert); `mosaic` taps a shown clue (`t`
  move) and the half-tile margin (inert); `signpost` drags a link (`L`
  move) and presses the final immutable number (inert); `untangle`
  drags a vertex (`P` move) and presses the board corner (inert, outside
  the fixed 60-pixel radius). The `untangle` record holds the last pixel
  the drag reported, so those two steps check the vertex, the live tile
  denominator, and a point between the start and the requested end.
- `SixthWaveTileSizeInputTest` is the shared device test for the sixth
  enablement wave. One parameterized case per game (`galaxies`,
  `bridges`, `unruly`, `filling`, `mines`) launches the game, reads the
  live `BoardGeometry`, and runs a step script at the default
  `limitDpi`, then changes `limitDpi` to `off`, checks that the tile
  changed and is not the preferred one, and runs the second script
  there. Every point comes from the geometry the drawing layer uses, and
  each game's stored preferences are reset to the C defaults for the
  run. Each step asserts the move count, that the save text changed
  exactly when the gesture was a move, and the exact `MOVE` record. The
  save comparison drops the `TIME` record, because `mines` is the one
  timed game and its clock rewrites that record on every tick. The C
  input code decides the gestures: `galaxies` clicks an occupied square
  (inert) and an edge (`E` move), holds a press in the board corner
  (inert), and right drags from a dot to a square (`A` move);
  `bridges` clicks an empty cell (inert), drags from an island to the
  next one (`L` move), and clicks an island for the C lock (`M` move);
  `unruly` clicks the half-tile border (inert) and a cell (`P` move);
  `filling` clicks the border and sends the digit with no selection
  (both inert), then clicks a region and sends its digit (`i_N` move);
  `mines` taps the border (inert), opens a tile (`O` move), and flags a
  covered tile with a held press (`F` move). A held press becomes
  `RIGHT_BUTTON`, so the test injects the down, lets the main looper
  run the shell's long-press callback, then injects the move and the
  release; the digit key goes through `GamePlay.sendKey`, the call the
  Compose key row makes.
- `SeventhWaveTileSizeInputTest` is the shared device test for the
  seventh enablement wave. One parameterized case per game
  (`netslide`, `palisade`, `inertia`, `tents`, `net`, `tracks`,
  `dominosa`, `map`, `keen`, `singles`, `pearl`, `loopy`) launches the
  game from a frozen game id, reads the live `BoardGeometry`, and runs
  a step script at the default `limitDpi`, then changes `limitDpi` to
  `off`, checks that the tile changed and is not the preferred one, and
  runs the second script there. Every point comes from the geometry the
  drawing layer uses, and each game's stored preferences are reset to
  the C defaults for the run. Each step asserts the move count, that
  the save text changed exactly when the gesture was a move, and the
  exact `MOVE` record. Because every case loads a game id, the suite
  generates no board and cannot exceed a generation cap (D-007, D-008,
  D-014, and D-026 to D-033); the caps are pinned in the engine and
  parity tests. The C input code decides the
  gestures: `netslide` taps a cell (inert) and then the left or top
  margin band, which slides a row or column; `palisade` taps the board
  corner (inert) and the lower half of a cell, which toggles the nearest
  fence (C has no fence drag); `inertia` taps a cell, which moves the dot in the
  nearest compass octant, and the dot's own cell (inert); `tents` taps
  a tree (inert) and blank cells, which cycle tent then grass; `net`
  taps the grid gutter (inert) and tiles, which rotate left; `tracks`
  taps a square centre and an edge band, and the outer border (inert);
  `dominosa` taps the right half of a square to place and then remove a
  domino, and the border (inert); `map` drags from a clue-coloured
  region to an uncoloured one (the only C path that recolours), and a
  tap never changes the board; `keen` taps a cell and sends `1` or `2`,
  and a digit with no highlighted cell is inert; `singles` taps a cell
  to cycle black and circle, holds a press for the right-button circle,
  and taps the border, which toggles the display preference only;
  `pearl` taps a cell centre (inert), taps near an edge to draw a line,
  and holds a press on an unlined edge to mark a circle; `loopy` taps
  the board corner (inert) and near edges, which cycle yes and no.
- `EighthWaveTileSizeInputTest` is the shared device test for the
  eighth enablement wave, `solo` and `cube`, with the same structure and
  the same assertions. Every case loads a frozen game id, so the suite
  generates no board and cannot exceed the D-036 cap. `solo` taps a
  given cell (inert), taps a free cell and sends a keypad digit; `cube`
  taps half a cell left of the die on the left edge, where no square
  lies beyond it (inert), and taps a point to roll the die one step in
  the bearing of the tap.
- `SlowGenerationDialogTest` opens the real type menu and the real
  settings dialog and checks the slow-generation warning. The Towers
  case sets `9du`, cancels the warning (the engine must not change), and
  then continues once and cancels the progress dialog. The Mosaic case
  sets the aggressive `100x100` board, the slowest game of the fifth
  wave, and cancels; the heavy board never starts. The Galaxies case
  sets a custom `16x16` board, the first sixth-wave size above the 225
  cell threshold, and cancels.
- `GenerationCancelTest` starts a real slow generation and cancels it
  while the worker is still running. It launches `towers 5de`, opens the
  custom settings dialog, sets `9du` (seconds on the phone), continues
  through the warning, waits for the real progress dialog, taps Cancel,
  and then checks that the dialog and any error dialog are gone, that
  the live engine keeps its instance, game ID, parameters, and save text
  for the settle window, and that `GamePlay.lastGenerationCancel` is
  cancelled. The token check is the part that fails when the worker is
  not stopped: the shell always cancelled the future, so the previous
  game looked the same while the board kept being generated. The case
  never waits for the heavy board to finish.
- `FifteenSettingsDialogTest` opens the type menu and Custom, checks the
  `Width` and `Height` fields against the engine schema, starts a 3x3
  game, and checks that an invalid width shows the C validation message
  and keeps the old engine for a bounded settle window. A field is found
  by the floating label of its `TextInputLayout`, not by a sibling text
  view, because the label is now part of the field (`TestUtils.hasFieldLabel`).
- `FifteenGameIdDialogTest` checks that the game-ID dialog is prefilled
  with the current ID and that a valid ID becomes the new game with
  `Moves: 0`.
- `FifteenSeedDialogTest` checks the `4x4#<uuid>` seed text and that the
  same fixed seed produces the same game ID on two launches.
- `PrefsActivityTest` runs the real `PrefsActivity`: the global rows, a
  switch that writes its key, and the `fifteen` page, which must be
  exactly `Fifteen.prefs` plus the shell's arrow-key switch. It also
  checks that the `Use system colors` row is offered exactly where the
  platform has a wallpaper palette, that its widget is a
  `MaterialSwitch`, and that the switch writes `systemColors`.
- `ConfigDialogControlsTest` opens two configuration dialogs through the
  real type menu. `theCustomSizeFieldIsAMaterialTextField` requires the
  `Width` and `Height` views to be `TextInputEditText` inside the
  `TextInputLayout` whose floating label is the field name.
  `theCustomCheckboxIsAMaterialCheckboxThatReachesTheEngine` opens the
  `signpost` dialog, ticks `Start and end in corners` in a
  `MaterialCheckBox`, and requires the new game's params to change from
  `4x4` to `4x4c`, so the control value still reaches the C engine.
- `ShellThemeTest` covers the Material 3 shell. `theKeyBarKeepsTheUndoAndRedoContract`
  opens a solved fifteen game and checks the Compose states of the keys:
  Undo and Redo are disabled, a tap enables Undo, its click returns the
  status to `Moves: 0`, and Redo becomes enabled; the bar and the key
  containers must not be the same token. The two
  `theSystemColorsSetting…` cases read the resolved `colorPrimary` and
  `colorSurface` of a freshly built settings screen: with the setting off
  they are `shell_primary` and `shell_surface`, with it on they are the
  Material 3 dynamic scheme's roles for the same configuration (light and
  night). The first case also captures the engine's colours and its
  recorded `BoardFrame` for the same solved board with the setting on and
  off, and requires both to be identical: the shell palette never reaches
  the board. The cases are skipped below Android 12.
- `FifteenChooserLaunchTest` clicks the `fifteen` row in the real
  chooser and checks that `GamePlay` opens with the Kotlin engine and
  `Moves: 0`.
- `ShellLayoutTest` covers the shell configurations that the portrait,
  left-to-right, 100% text run on a phone window cannot see; each was a
  P0 or a large-screen rule in `docs/DESIGN.md` section 14.
  `theBoardIsDrawnInLandscape` opens a solved fifteen game, checks that
  the `game_canvas` view exists, has a non-zero size, is displayed, and
  that a screenshot of its bounds carries more than one colour, then
  rotates to landscape and back and repeats the check. At every step
  the game id and the save text (without the `TIME` record) must be
  unchanged. `theButtonBarStaysInTheWindowInRtl` sets the window layout
  direction to right-to-left, as a right-to-left locale does, and
  checks that the Undo and Redo keys are 48dp and lie inside both the
  key bar and the window. It then taps the tile left of the gap and
  asserts the exact `MOVE` record `M2,3`, because a mirrored board
  would move the tile on the right, and clicks Undo, which must return
  the status to `Moves: 0`. The board and the key geometry do not
  mirror; the shell around them does. The third case,
  `theSettingsTitlesWrapAtTwoHundredPercentText`, sets the system text
  scale to 200%, finds the `Show reminders of controls` title, and
  asserts that the layout carries no ellipsis, that the view is at
  least as tall as its layout, and that the title wrapped to two lines.
  It restores the original scale in a `finally` block.
  `theShellKeepsTheReadingMeasureOnAnExpandedWindow` simulates an
  expanded window with `wm size 2400x3000`, which is 914dp by 1143dp at
  the phone's 420 dpi, opens the real chooser and the real settings
  screen, and asserts that the chooser list is 840dp wide and centred
  and that the settings rows are 840dp wide and centred.
  `theGameSurvivesAWindowResize` opens a solved fifteen game at the
  phone window, grows the window to the same simulated expanded size
  while the game is open, and asserts that the board frame carries
  drawing operations, that the game id and the save text are unchanged,
  that the Undo key is on screen, at least 48dp, and inside the window,
  that a real tap applies `M2,3`, and that a wider-than-tall simulated
  window switches the layout to landscape with the key bar in its side
  column and the same save text. It returns to the phone window and
  asserts the board is still drawn and the game is still the same. Both
  cases restore the `wm size` override in a `finally` block. The two
  reading-measure cases fail on the code before the change: `the
  chooser list is 914.2857dp wide, not capped at 840.0dp` and `the
  settings list is 2400 of 2400px, not capped`.
  All five cases fail on the code before their fixes: no `game_canvas`
  view in landscape, `Undo` outside the window in RTL, an ellipsized
  title at 200%, and the two uncapped lists.
- `ShellAccessibilityTest` covers the shell accessibility findings that
  a device can check without a screen reader. Each case fails on the
  code before its fix.
  `theStatusLineIsSilentForAUnitBoard` opens a frozen `cube` game,
  reads the accessibility node of `status_bar` (no live region),
  sends one arrow key, and checks that the node is still silent when
  the text reaches `Moves: 1`. Every ported backend has a spoken board
  after D-01 wave 5, so the board speaks its own changes and the
  status line must not repeat them; `StatusLiveRegionTest` pins the
  polite fallback for a board without units, which no launchable game
  has any more.
  `anUnchangedStatusIsNotWrittenToTheViewAgain`
  counts text changes of the status view: three repeats of the same
  text must not touch the view, and one changed text must, so the
  polite live region cannot announce a redraw or a timer tick again.
  `theLetterKeysAreNotSpokenAsBareLetters` opens a frozen Guess game and
  fails if any key is described by a single letter; the `L` key must be
  spoken as the game's own word `Labels`.
  `theConfigurationFieldsCarryTheirOwnLabelAndAre48DpTall` opens the
  `tents` custom dialog and checks both text rows (`Width`, `Height`)
  expose their label as the field's hint and are at least 48dp tall,
  and that the `Difficulty` choices row carries its own description.
  `theKeyLabelStaysInsideTheKeyAtTwoHundredPercentText` sets the system
  text scale to 200%, opens a frozen `keen` game, and checks the
  resolved label size of every text key leaves room inside the key.
  `theLandscapeStatusLineClearsTheNavigationBar` checks that the text of
  `status_bar` ends above the bottom system bar in portrait and
  landscape, measured against the root window insets, and that the
  status text survives the rotation.
- `ChooserAccessibilityTest.theRowIsOneTalkableItemWithAChangeableStarState`
  checks that a game row is a single clickable node that carries the
  game name, exposes no star state while unstarred, offers a custom
  action spoken as `Add to starred`, and reports the `Starred` state
  after that action.
- `BoardSemanticsTest` reads the D-01 node tree on the pilot: the board
  shape, the four actions, cell labels with their collection position,
  a click that makes the real move with one announcement, the status
  line staying out of the way while the board has units, and a 21x21
  board that exposes the cursor unit only.
- `BoardWaveFourSemanticsTest` reads the same tree on the three shapes
  that are not a plain cell grid. A frozen `slant` cell is labelled
  `Row 1, column 1, blank, clue 2 up and right, clue 1 down and left`,
  offers `Clockwise` and `Anticlockwise`, and the clockwise action
  writes the real `\0,0` move and the live region reads the turned
  cell. A frozen `bridges` island is labelled `Row 1, column 1, island
  3, 0 bridges` and offers `Mark`, which writes `M0,0` and reads
  `marked`; a bridge cell offers no action at all, because a bridge is
  a drag from one island to another. A frozen `guess` palette swatch
  reads `red` and offers nothing, four digit keys fill the first row,
  and the `current row` unit offers `Submit`, which writes `G1,2,1,2`.
  The fourth case compares every unit node of `loopy`, `untangle`, and
  `guess` with its own mapped rectangle through
  `assertEveryUnitLandsOnTheDrawing`, so the 2.6x geometry guard covers
  an edge, a point, and a palette and not only a cell.
- `BoardWaveThreeSemanticsTest` reads the same tree on the two shapes
  that wave 3 added: a `towers` clue unit carries its sight count and
  the game's `Done` toggle, the toggle makes the real `D0,-1` move and
  the live region reads `Column 1, 1 from the top, done.`; and a frozen
  three by three `flood` cell is labelled with its colour name, offers
  `Fill`, and one double tap floods the top-left cell, which the live
  region reads as `Row 1, column 1, yellow.` Both boards are frozen, so
  the case generates nothing.
- `BoardGeometryParityTest` is the 2.6x guard: for `fifteen`, `mines`,
  and `unruly`, the unit on the drawing cursor rectangle lands on the
  pixels that the render matrix gives it, within 1.5dp. With the matrix
  removed the `mines` case fails with `unit left expected:<55.4> but
  was:<21.0>` on the Pixel 4a 5G. The wave 4 case of
  `BoardWaveFourSemanticsTest` extends the same guard to a unit that is
  not a cell: it compares every node of `loopy`, `untangle`, and
  `guess` with its own mapped rectangle, because an edge, a point, and
  a palette have no cursor rectangle and no collection position.
- `BoardLandscapeSemanticsTest` rotates `fifteen` to landscape and
  requires the same 16 units, the same labels, the 4 by 4 collection
  shape, and the same pixel geometry. It fails with `expected:<16> but
  was:<0>` when the game does not speak.
- `BoardKeyNavigationTest` plays `unruly` with the hardware keys: the
  game view holds the focus, the arrow keys move the game cursor and
  the live region reads the one or two changed units, Enter writes a
  real move, and the unit nodes are still there afterwards. It fails
  with `the board did not expose its units expected:<64> but was:<0>`
  without the game's spoken board.
- `BoardSpokenTableTest` is the D-01 evidence table: one parameterized
  case per backend, 40 in all, and each opens the frozen seed-12345
  board its spoken table was written from. The test APK takes
  `testdata/spoken/` as an asset directory, so the device reads the same
  file that `BoardSemanticsGoldenTest` compares on the host and one file
  stays the source of truth. A case asserts the board label and
  `collectionInfo`, every unit node in board order against the table
  line for line, each unit's actions against the game's own action list,
  the collection position of a cell and the absence of one outside the
  grid, and the board actions (`Restart` and `Show solution` on a fresh
  board, where the shell enables the solution). The table is the engine
  board with the game cursor hidden; the shell shows the cursor of a
  game whose arrow keys are on, so the case allows exactly that one
  unit to gain the `, cursor` suffix and nothing else. The class took
  23.9 s in the full run and 27.4 s alone on the Pixel 4a 5G, so the
  whole table costs about half a second per game.
- `BoardCapSemanticsTest` is the D-01 S2 contract above the cap: one
  case for each of the fourteen games whose cap
  `docs/DESIGN.md` section 11 documents (`palisade`, `netslide`, `net`,
  `loopy`, `tents`, `tracks`, `singles`, `inertia`, `cube`, `untangle`,
  `blackbox`, `guess`, `map`, `mines`). Each case builds the board from
  a hand-written description or from the frozen C description in the
  case, so no case generates anything, and each proves four things: the
  board is above 400 units, a fresh board that hides its cursor speaks
  at most one unit, one arrow press gives exactly the cursor unit, and
  the shell composable renders one unit node with that label and the
  four board actions. It never opens a game screen, so a 90,000-cell
  `netslide` costs a load and not a draw.
- `BoardFallbackSemanticsTest` pins the C fallback that no launch
  reaches: it installs `GameEngineImpl.cEngineForTest(...)`, a real C
  engine built from a game ID, in the live fifteen screen and redraws
  it through the view. The board node is then named only by the game
  and its status text (`Fifteen. Moves: 0`), has no `collectionInfo`,
  no unit node and no announcement, and still carries `Restart` and
  `Show solution`; the drawing pass that produces it runs inside the C
  engine's own callback, so a re-entrant engine call would fail the
  case instead of the app.

## 4. Corpus

```
testdata/
├─ saves/
│  ├─ initial/<game>/        # every readable preset, one .sgtp each
│  └─ cases/                 # MOVE, undo with redo, SOLVE
├─ regressions/<game>/       # issue-*.json: seed, params, moves, expected
├─ corpus/<game>/            # fuzz corpus, minimized
├─ golden/<game>/            # params + seed -> DESC, presets
├─ trace/<game>/             # strict draw traces, one per preset
├─ spoken/<game>.txt         # spoken board labels and actions, one preset
└─ manifest.json             # sha256, provenance, upstream commit for DESC
```

Rules:

- fixtures must be deterministic and minimal;
- large corpora use Git LFS and are recorded in the manifest;
- every file records its provenance: issue, seed, upstream commit;
- do not add user data without permission and anonymization.

## 5. Regressions from history

Every fixed upstream bug has a seed, parameters, and a move sequence.
Format:

```json
{
  "issue": "upstream/1234",
  "game": "net",
  "params": "5x5",
  "seed": "12345",
  "moves": ["C0,0", "L0,0"],
  "expect": {
    "state": "golden/net/issue-1234.state",
    "completed": false
  },
  "level": "semantic"
}
```

Rule: fix a bug, add a fixture. The fixture runs against both
implementations until C is removed.

## 6. Fuzzing

`fuzzpuzz.c` loads a save file, processes moves, draws through the
null drawing API, and reserializes. It lives in `app/src/main/jni/`,
next to `CMakeLists.txt`, and `tools/oracle/build.sh --with-fuzz`
builds the stand-alone frontend as `build/oracle/fuzzpuzz`.

Process:

1. Build with ASan/UBSan and AFL++/libFuzzer.
2. Seed the corpus with `jni/icons/*.sav` and `testdata/saves/`.
3. Minimize every finding and put it in `testdata/corpus/`.
4. Add a regression fixture with the expected behavior.
5. Our implementation must either behave the same or have a
   `DEVIATIONS.md` entry.

## 7. Determinism

Forbidden in parity paths:

- `HashMap`/`Set` where iteration order matters;
- `System.currentTimeMillis`, `Random` without a seed;
- locale-dependent number and string formatting;
- float comparison without a tolerance;
- dependence on thread scheduling.

Required:

- explicit seed and params;
- only `random.c`-compatible RNG: use `engine.RandomState`, never
  `kotlin.random.Random`, in a parity path;
- stable and explicit sorting;
- fixed time in tests.

## 8. Goldens and screenshots

- Geometry goldens are JSON/JSON.gz and are diffed in the PR.
- Strict draw traces are canonical text in
  `testdata/trace/<game>/<params>.trace`, one operation per line. Every
  game and every preset is frozen: 288 files for the Android build.
  They are regenerated with `tools/gen-traces.sh` and compared in
  `StrictTraceTest`. The contract is `docs/adr/0003-strict-draw-traces.md`.
- Screenshots cover UI regressions. Compare with a tolerance, on a
  fixed configuration (size, density, theme, font).
- Do not compare screenshots across devices and Android versions
  without normalization.
- Update goldens in a separate commit with an explanation.

Shell tests:

- The chooser rows are a pure function (`chooserSections`) and are
  tested on the JVM in `ChooserSectionsTest`.
- `ChooserScreenTest` runs the chooser screen on a device or emulator
  without the activity: click, long press, the starred section order,
  and compact mode.
- `ChooserAccessibilityTest` checks the rules from `DESIGN.md` section
  11 that a test can check: every clickable node has a spoken label,
  every target is at least 48dp, the star toggle is exposed as a long
  click action, and the section labels are headings.
- `ChooserScreenshotTest` is the pixel comparison gate: light list,
  dark list, light grid, on the reference AVD. The status bar is
  excluded, toasts are waited out, and a frame that is still settling
  is retried. The goldens are recorded locally in
  `app/src/androidTest/assets/screens/`, which is git-ignored, so a
  fresh checkout skips these tests. Record them on the reference AVD
  with the procedure in the test class; another density, renderer, or
  font changes every pixel. The recorded goldens are 1080x2400, from
  the AVD in `tools/perf/baseline.md` (1080x2400, 420 dpi), so the
  class skips itself with a message when the display size or density
  does not match. It reports a skip, not a failure, on a physical
  device. The one exception is the reference size and density with the
  sides swapped: that is the reference display left in landscape, so it
  fails with the observed size instead of hiding a real regression. The
  three cases are decided by the pure `ReferenceCaptureGate`, tested on
  the JVM in `ReferenceCaptureGateTest`. The skip message is in the UTP
  log (`androidTest-results/.../utp.0.log`); the JUnit XML records only
  that the test was skipped.
- `ChooserScreenshotsTest` runs the chooser under fastlane only: it
  skips itself when the `device_type` argument is absent. It is a
  screenshot producer for the store, not a behavior gate.

Engine tests:

- `engine/src/commonTest` checks the SHA-1 vectors, fixed RNG streams,
  state encoding, generator copies, and the save parser's canonical
  form and error cases.
- `FifteenTest` checks parameter and description validation, a save
  round trip, solve with undo and redo, the hint solver, input at a
  resized tile size, invalid moves, the text format, the palette, and
  an invalid save. One test derives every cell rectangle from the
  drawing sink at tile sizes 48, 63, 82, and 100, then feeds the cell
  centers back into `processKey`; drawing and input must agree. The
  completion event is consumed once by a winning move, not by a solve
  or a no-effect key, and undo and redo cross the completion boundary.
- `GameSeamTest` checks the seam operations that more than one game
  uses: the `canSolve` property, and that slant keeps its status text
  empty while `wantsStatusBar` is false.
- `GameIdTest` pins the parameter validation of `midend_game_id_int`: a
  game ID validates with `full = false` because it has a description,
  so a short id is accepted, while `newGame`, which has no description,
  validates the full form and rejects it.
- `SpokenBoardTest` pins the D-01 seam on the pilot: the default of a
  game without the hook is an empty board, `tents` speaks its cells and
  clues in board order with the game's pointer buttons, the cursor and
  error suffixes, and the 400-cell cap. Cell texts are content only;
  `BoardSpeechTest` pins `spokenLabel` as the coordinate the shell adds.
- `SpokenBoardGamesTest` pins the ten wave 2 cell games from frozen
  seed-12345 IDs: the content words, the cursor suffix, and the pointer
  buttons each state offers, including `mines` mark and unmark,
  `mosaic` and `unruly` colour cycles, and the `twiddle` block bounds.
- `SpokenBoardWaveThreeTest` pins the twelve wave 3 games the same way:
  the colour names of `samegame` and its two-stage tap, `flood` fill,
  the `keen` block clue and its pencil marks, the `towers` sight clue
  and `Done` toggle, the `unequal` sign words and `given`, `filling`'s
  value origin and its `correct` and `wrong` region state, `light up`,
  `magnets` dominoes and count clues, the `range` colour cycle,
  `undead` mirrors, clues, and count markers, and `dominosa` numbers.
  It also pins the digit grammar itself (`given 5`, `5`,
  `pencil 1 4 7`, `empty`, and the `pencilDigits` bitmask) and the
  `wrong` suffix on a duplicate `unequal` digit.
- `SpokenColourTest` is the colour check: the shared vocabulary has one
  name per colour and no value or hex code, one game colour index always
  gives the same name, the two colour games spell the colours they share
  with the same word, and a colour name is in the label itself, from a
  hand-built one-row board, so the colour-blind path does not depend on
  the palette.
- `SpokenBoardWaveFourTest` pins the fifteen wave 4 games the same way,
  and it pins the design promise that a unit action is real: it sends
  the press and the release a finger makes at the unit centre, executes
  the move the game returns, and reads the changed board back, for the
  `loopy` edge, the `palisade` border, the `slant` slash, the `pattern`
  cell, the `tracks` square, the `bridges` mark, the `guess` submit,
  the `blackbox` laser and lock, the `net` turn, the `netslide` band,
  and the `inertia` direction. It also pins the units that have no
  action and why (`untangle`, `pearl`, `map`, `galaxies`), the cap by
  unit count, and the three helpers `sideSpeech`, `unitAt`, and
  `cappedUnits`.
- `SpokenBoardWaveFiveTest` pins the last two backends. `solo` speaks
  the given, the digit, the pencil marks, the killer cage sum, and the
  cursor with its pencil mode, and offers `Select`, `Pencil`, `Ink`,
  `Mark`, and `Clear` only where `interpret_move` really performs them.
  `cube` speaks each square and the roll its own rectangle makes, checks
  that a triangle board names a row and a triangle and that its unit
  centre really rolls the die, numbers the six rows of the icosahedron
  preset, and pins the capped board: 21x20 cube cells and 486 triangle
  cells each return the cursor unit alone.
- `SpokenBoardBoundsTest` runs every preset of every ported backend
  from the frozen golden IDs: a game without the hook returns the empty
  default, every unit rectangle is inside `computeSize`, the grid is
  complete and row-major, grid units do not overlap, a cell label does
  not repeat its coordinate, and a board above 400 cells exposes at
  most one unit. It also holds the wave 4 shapes: a board with no cell
  grid (`loopy`, `guess`, `untangle`) reports no row and no column and
  keeps its unit count inside the cap, and only the six games whose
  units are not one per cell (`loopy`, `guess`, `untangle`, `palisade`,
  `blackbox`, `netslide`) may return the cursor unit alone while the
  cell grid is still under the cap. Wave 5 adds `cube`, whose cube
  solid is a rectangle and whose three triangle solids are not, so a
  preset may report no grid; the test still requires that at least one
  cube preset reports its rectangle.
- `BoardSemanticsGoldenTest` compares the live spoken board of every
  covered backend with `testdata/spoken/<game>.txt`. A changed word or
  a lost action is a review diff, not a silent drift.
- `DrawStateTest` pins the drawstate lifecycle with a test-only game
  that allocates one blitter. The first draw allocates it before
  `start_draw`; a move, undo, redo, solve, and restart keep the same
  instance and write no `blitter_free` or `blitter_new`; a tile size
  change, a different sink, and `forceRedraw` free the old cache
  through the sink that created it and create a new one; `freeDrawState`
  frees exactly once and does nothing when no cache exists.
- `FirstDrawTest` pins `midend_redraw`: a new game reports
  `changed_state`, clears the window, draws the game, sets the status,
  updates the whole area, and ends the draw. A move and a restart keep
  the drawstate, so their trace has no whole-window clear and no
  whole-area update. A forced redraw restores the clear.
- `KeyCanonicalisationTest` pins the `midend_process_key` rules that
  run before a game sees a key: Enter, Return, Ctrl+J, and Ctrl+M as
  `CURSOR_SELECT`; Space as `CURSOR_SELECT2`; DEL and Ctrl+H as
  backspace; Ctrl+Shift+Z as redo and Ctrl+Z as undo; Ctrl on a click
  rejected; the keypad flag kept only on a digit; and the held-button
  rules for a drag, a release, a drag with no press, and an overlapping
  second press. Each case asserts a real move, a UI change, or the
  button that reaches `interpretMove`.
- `KeyShortcutTest` pins the `midend_really_process_key` order: the
  game sees a key first, and the one-key shortcuts apply only to
  `Unused`. `'u'`, `'U'`, `'*'`, `0x1A`, `0x1F`, Ctrl+Z, and `UI_UNDO`
  undo exactly one move; `'r'`, `'R'`, `'#'`, `0x12`, `0x19`, and
  `UI_REDO` redo exactly one move; a game that consumes `'u'` keeps it;
  `UI_SOLVE` and `'\x13'` solve a solvable game and return `Unused` for
  a game without a solver; `UI_*` never reaches `interpretMove`; and the
  new-game and quit aliases return `Unused` because the shell owns those
  actions.
- `BoardGeometryTest` checks that the one geometry value used for
  drawing, input, the cursor, and size negotiation fits and centers the
  board on phone, tablet, landscape, and tiny viewports.
- `RandomParityTest` is strict. It compares `random_bits` for every
  bit width 1..31 and `random_upto` for limits up to 2^27, across ten
  seeds, and transfers encoded generator state in both directions.
- `DeterministicHelpersParityTest` is strict. It compares
  `arraysort` with the oracle for ascending, descending, and a
  low-bits tie-break, including duplicate keys; it also compares the
  full `combi` order for every `1 <= r <= n <= 9`.
- `DsfParityTest` compares the plain, minimum-tracking, and
  flip-tracking forests over merge scripts.
- `TdqParityTest` compares every removal, including duplicates.
- `FindLoopParityTest` compares the loop edges, bridges, and component
  sizes on trees, cycles, complete graphs, and random graphs.
- `MatchingParityTest` compares the pairing and the seeded adjacency
  permutation.
- `Tree234ParityTest` compares add/find/delete/index results and the
  final sorted order.
- `DivvyParityTest` compares the canonical elements, the class sizes,
  the attempt count, and the encoded generator state of
  `divvy_rectangle` over rectangles, paths, `k = 1`, `k = 2`, `k = w*h`,
  and seeds that need a retry. `DivvyTest` pins fixed vectors and checks
  that every region is a connected k-omino.
- `LayDominoParityTest` compares the laid grid, the attempt count, and
  the encoded generator state of the direct `laydomino` oracle op for
  even and odd areas, one-row boards, and seeds that need several
  repair passes. `LayDominoTest` pins fixed vectors, the valid-tiling
  invariant over a sweep, and the invalid input guards.
- `LatinParityTest` is strict. It compares `latin_generate` and
  `latin_generate_rect` grids and the encoded generator state for
  orders 1..9, one-row and one-column rectangles, 4x6, and 6x4, and it
  resumes from an encoded state. It also compares the generic
  `latin_solver` difficulty and the returned grid at every `maxdiff`
  from 0 to 4 for complete, unique, set, extreme-set, forcing,
  recursive, ambiguous, invalid, and impossible grids, and it pins
  `latin_check`. `LatinTest` pins fixed vectors, the Latin square and
  placement invariants, and the context copy and free protocol that a
  game solver uses. The game callback layer is pinned by
  `KeenDescTest`: box order, transposed cube indexing, the ADD/MUL
  odometer, the Hard early return, the validator, and Keen's difficulty
  slots `0, 2, 3, 3, 4`.
- `PegsTest` checks the pegs parameter quirks, the state ownership, the
  text format, the key label, the loose move grammar, the lax
  completion rule, the cursor scan order, and the generator's retry
  stream. The oracle cannot expose some of these, so they are
  engine-only pins.
- `GuessTest` checks the guess parameter quirks, the description
  vectors on both RNG paths, the UI character-stream parser, the
  palette arithmetic, and the geometry. It also pins the two paths
  that the C debug oracle cannot compare: backspace at the Submit slot,
  which is out of bounds in C, and the unmarkable submit after a drag,
  which aborts the assertion build.
- `FifteenDescTest` is semantic. It compares `params + seed -> DESC`
  from the Kotlin generator with the frozen goldens and with fresh
  oracle games.
- `FifteenGameTest` is semantic. It compares the serialised state after
  new games, cursor keys, clicks and slides, undo, redo, hint-key
  moves, solve, and a winning move. It also compares presets, the
  requested keys, the status text, and the text format, and it checks
  that the completion event fires on the winning move and on the redo,
  but not on the solve.
- `FifteenSaveTest` is strict for the save text. It reads every
  `fifteen` file in the corpus into the Kotlin midend, rewrites it byte
  for byte, and makes the oracle load both versions. It also makes the
  oracle load a Kotlin-written new game.
- `FifteenInputFuzzTest` is semantic and deterministic. It sends 120
  random steps per case to C and to Kotlin at the same viewport: clicks,
  drags, releases, cursor keys, the hint key, undo, redo, solve, and
  points in the letterbox margin. The seed is frozen, and a failure
  names the case, step, key, and point.
- `FifteenInputParityTest` is semantic. It asks the oracle to choose a
  board size for phone, tablet, landscape, and tiny viewports, then
  compares the Kotlin layout and the C/Kotlin state after clicking the
  gap, every in-board neighbour, and a diagonal cell at that size. It
  also compares a swipe into the gap and a swipe that starts on the gap,
  using the same `DOWN`, `DRAG`, `UP` sequence as the shell.
- `KeyCanonicalisationParityTest` is semantic. It sends the same
  canonicalising sequences to C through `keys`, `key`, and
  `pointsSized`: Enter, Space, Return, Ctrl+ASCII, and Ctrl+Shift+Z redo
  for fifteen and flip, plus a press, drag, and release at real cells,
  including a drag with no press and an overlapping second press. It
  compares the save, id, status, and history flags, and asserts the
  real move or the redo.
- `KeyShortcutParityTest` is semantic. It replays one fifteen move and
  then `u`, `r`, Ctrl+Z, and Ctrl+Shift+Z in one oracle `keys`
  sequence, and the same sequence in Kotlin. It also covers `U`, `R`,
  `*`, and `#`. The save, id, status, and history flags must match, and
  the final state must keep exactly one move.
- `FifteenTraceTest` is strict. It draws the Kotlin board through
  `KotlinTraceSink`, normalises it with the same `TraceNormalizer` as
  the oracle, and compares the result with a fresh oracle trace and
  with every frozen `fifteen` golden.
- `SlantDescTest` compares the Kotlin generator with the oracle for 90
  params and seed combinations, including both difficulties and the
  12x10 preset.
- `SlantGameTest` compares new games, text, clicks, cursor keys, direct
  slash keys, undo/redo, solve, and saves in both directions.
- `SlantSaveTest` reads every `slant` save in the corpus, rewrites it
  byte for byte, and pins the obfuscated `AUXINFO` record.
- `SlantTraceTest` is strict. It is the first trace of `line`, `circle`,
  `clip`, and `unclip`, and it matches every frozen `slant` golden. It
  also checks the cursor after a cursor key and the grounded fade on and
  off, each against the oracle with the same key sequence or preference.
- `SlantInputParityTest` compares layout and clicks at several
  viewports, including the letterbox margin, and a swipe that must
  change only its start cell. It also sends Shift+Right and Ctrl+Right
  before a select: C ignores both modified cursor keys, so the select
  must reach the same cell as a plain Right, and the test asserts the
  cursor position and the save.
- `SameGameDescTest` is semantic. It compares parameters, validation,
  presets, and `params + seed -> DESC`, including non-soluble generation
  and a numeric seed sweep.
- `SameGameGameTest` is semantic. It compares new games, text, status
  text, score, gravity, completion, impossibility, undo/redo, and
  crafted saves with the oracle. It also drives a keyboard removal
  through the same cells as the pointer path.
- `SameGameSaveTest` is strict for the save text. It reads every
  `samegame` save in the corpus, rewrites it byte for byte, and makes
  the oracle load both versions. It also makes the oracle load a
  Kotlin-written save and pins the `PARAMS`/`CPARAMS` asymmetry of a
  non-soluble parameter string: the short form stays in `PARAMS`, the
  full form with `r` is only in `CPARAMS`, and the game ID uses the
  short form.
- `SameGameInputParityTest` is semantic. It compares `computeSize` and
  the layout for phone, tablet, landscape, and tiny viewports and for
  tile sizes 48, 63, 82, and 100, then sends the same view coordinates
  through `BoardGeometry` to Kotlin and through `pointsSized` to C. The
  first tap must only select and leave the save unchanged; the second
  must remove the group. The state, ID, status, and a final redraw with
  its status line are compared at every viewport, including letterboxed
  origins. A modified-key test sends Shift+Right and Ctrl+Right before
  two selects: C ignores both modified cursor keys, so the removal must
  match a plain Right, and the test asserts the cursor position.
- `SameGameTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus completed, impossible, and
  blank states and a selected group.
- `FloodDescTest` is semantic. It compares parameters and their
  validation messages, the seven presets, and `params + seed -> DESC`
  for the presets, for boards outside the preset list, and for a numeric
  seed sweep.
- `FloodGameTest` is semantic. It compares new games, text, status
  text, the move limit, completion, the solver string, undo/redo,
  pointer and keyboard paths, and the layout at four viewports and at
  tile sizes 48, 63, 82, and 100.
- `FloodSaveTest` is strict for the save text. It reads every `flood`
  save in the corpus, rewrites it byte for byte, and makes the oracle
  load both versions. It also makes the oracle load a Kotlin-written
  save for every preset and pins the `PARAMS`/`CPARAMS` asymmetry of
  the short form: the id is `12x12`, `PARAMS` is `12x12c6m5`, and only
  `CPARAMS` keeps the chosen colours. A played move, an undone position
  with a live redo branch, a solve, and a restart are built in memory,
  so the history records are covered while the corpus stays frozen.
- `FloodInputParityTest` is semantic. It compares `computeSize` and the
  layout for phone, tablet, landscape, and tiny viewports and for tile
  sizes 48, 63, 82, and 100, then sends the same view coordinates
  through `BoardGeometry` to Kotlin and through `pointsSized` to C. The
  state, id, status, status text, and the final redraw are compared
  after every tap, including a letterboxed origin. A tap on the current
  colour and a tap in the letterbox must change nothing, and a tap that
  reaches the move limit must report the loss. The keyboard path
  compares the arrow mode and the fill. A modified-key test sends
  Shift+Right and Ctrl+Right before a select: C ignores both modified
  cursor keys, so the fill must match a plain Right, and the test
  asserts the cursor position.
- `FloodTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus moved, solved, and cheated
  states and a tile-15 trace where the separator is not drawn.
- `SixteenDescTest` is semantic. It compares parameter decoding and
  encoding, the validation messages, the five presets, and
  `params + seed -> DESC` for the presets, extra seeds, and a numeric
  sweep. The target generator is covered by vectors from `m1` to `m50`,
  including the rejected retry attempts, because no preset carries `m`.
- `SixteenGameTest` is semantic. It compares new games, text, status
  text, crafted moves including the loose bounds and a full cycle, real
  frame clicks at the phone viewport, keyboard sequences, the three
  cursor modes, the keypad null move, completion, undo/redo, and solve.
  `undo and redo across a solve match the oracle` compares the state,
  id, status, and history flags with C; the `SOLVE` record never
  animates, so the C debug oracle runs it.
- `SixteenSaveTest` is strict for the save text. It reads every
  `sixteen` save in the corpus, rewrites it byte for byte, and makes
  the oracle load both versions. It also makes the oracle load a
  Kotlin-written save for every preset and pins the `full`-argument
  quirk: `m` stays in the short id and in both `PARAMS` and `CPARAMS`,
  unlike flood and fifteen. A played move, an undone position with a
  live redo branch, a solve, and a restart are built in memory, so the
  history records are covered while the corpus stays frozen. One save
  shape is Kotlin-only: a `MOVE` record whose move text is `S` aborts
  the C debug oracle in `game_redraw`, so
  `a MOVE record with the solve text is pinned without the oracle`
  asserts the exact save text and the Kotlin undo/redo behaviour.
- `SixteenInputParityTest` is semantic. It compares the layout at
  eleven viewports, then sends real clicks on the four frame bands at
  tile sizes 48, 63, 82, and 100, including a 293x400 view with a
  non-zero letterbox origin on both axes. Every click must add one
  MOVE record and one move to the count, and the state, id, and status
  are compared with `pointsSized`. A click in a cell, on the frame
  corner, and in the letterbox must change nothing, and the pixel next
  to the letterbox must still move. The modified-key test compares
  `MOD_SHFT` and `MOD_CTRL` on cursor keys and `MOD_NUM_KEYPAD` on a
  digit with the oracle. `CTRL` on a click and `CTRL` on a digit never
  reach the C game, so those cases are Kotlin-only and are pinned by
  the engine test.
- `SixteenTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus moved, solved, and target
  states, the four cursor arms, and sized traces at tiles 33, 63, and
  100.
- `FlipDescTest` is semantic. It compares parameter decoding and
  encoding, the validation messages, the six presets, and
  `params + seed -> DESC` for the presets, extra shapes, and a numeric
  seed sweep. The seed 1061 vector pins the all-zero grid retry, and the
  Random ids pin the tree order and the duplicate-row retries.
- `FlipGameTest` is semantic. It compares new games, text, status text,
  the loose move grammar, real clicks at the phone viewport, completion,
  and undo/redo across the completing move. The solver is compared
  through its `S` hint mask and the clicks that finish the game: a solve
  stays open at status 0, sets the hint bits, and never raises the
  completion event, and a click after completion changes the board but
  not the count.
- `FlipSaveTest` is strict for the save text. It reads every `flip` save
  in the corpus, rewrites it byte for byte, and makes the oracle load
  both versions. It also makes the oracle load a Kotlin-written save for
  every preset and pins the `PARAMS`/`CPARAMS` asymmetry: a seeded
  Random game writes Crosses in `PARAMS` and Random in `CPARAMS`, while
  the game id drops the shape. A played move, an undone position with a
  live redo branch, a hint/solve state, a cheated completion, and a
  restart are built in memory, so the history records are covered while
  the corpus stays frozen.
- `FlipInputParityTest` is semantic. It compares the layout for phone,
  tablet, landscape, tiny, and one-pixel-short viewports, then sends
  real clicks at tile sizes 48, 63, 82, and 100, including letterboxed
  origins. Every move must add one record and one to the count and
  change the board, while a border pixel in the same view must change
  nothing. It also compares the requested arrow mode, the cursor keys
  and their modifiers, and the swipe sequence, which flip ignores: only
  the DOWN click is a move.
- `FlipTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus moved, hinted, and completed
  states and a cursor that changes only the diagram colour of one cell.
- `TwiddleDescTest` is semantic. It compares parameter decoding and
  encoding, the validation messages, the eight presets, and
  `params + seed -> DESC` for the presets, extra boards, and a numeric
  seed sweep. The retry fixtures pin the generator loop: the
  `prevmoves` counters are zeroed on every pass, and each attempt draws
  `x`, then `y`, then `r`. A generator that also resets the grid on a
  retry passes these literals too, so no fixture separates it.
- `TwiddleGameTest` is semantic. It compares new games, text, the status
  line including the target suffix, the move grammar, the requested
  keys, the palette, completion, undo/redo, and solve. A sorted
  description stays open until a move, which is the upstream quirk. The
  completion event fires on the last move and on the redo, but not on a
  solve.
- `TwiddleSaveTest` is strict for the save text. It reads every
  `twiddle` save in the corpus, rewrites it byte for byte, and makes the
  oracle load both versions. It also makes the oracle load a
  Kotlin-written save for every preset and for a move target, and pins
  the `full`-argument quirk: `m` and the `r`/`o` flags stay in the short
  id and in both `PARAMS` and `CPARAMS`. A played move, an undone
  position with a live redo branch, a solve, a completed state, and a
  restart are built in memory, so the history records are covered while
  the corpus stays frozen.
- `TwiddleInputParityTest` is semantic. It compares the layout at ten
  viewports, then sends real clicks on the band around every rotation
  centre at tile sizes 48, 63, 82, and 100, including the 411x702 view
  with a non-zero letterbox origin. Every accepted click must add one
  move and change the board; a pixel outside a band, the letterbox, the
  outer frame, a middle click, and a lone drag or release must change
  neither the board nor the count. It also covers the right button, a
  press-drag-release, the letter keys `a`-`d` and `A`-`D`, the keypad
  corners, the keypad edges on `4x4n2` and their rejection on `3x3n2`,
  the cursor keys with their modifiers, the cursor visibility rules, and
  the requested arrow mode for every preset.
- `TwiddleTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus moved, completed, solved, and
  target states, the cursor edges, and sized traces. The rotation
  animation and the flash are not ported, so a trace after a move in a
  live midend is out of scope; moved states are loaded from a save.
- `PegsDescTest` is semantic. It compares the nine presets, parameter
  decoding and encoding, the validation messages in both modes, and
  `params + seed -> DESC` for every preset over a seed sweep. Cross is
  checked to ignore the seed while Random and Octagon use it. The
  numeric and seed vectors pin the tree order, the retry stream, and
  all three Octagon hole classes.
- `PegsGameTest` is semantic. It compares new games, text, status, a
  real drag, the move count, undo and redo, the lax completion rule,
  the absent solver, the requested keys, and the absence of `AUXINFO`
  and `UI` records.
- `PegsSaveTest` is strict for the save text. It reads all nine
  readable `pegs` saves, rewrites them byte for byte, and reloads them
  in C with the same id, status, undo, redo, and text. A Kotlin save
  for every preset loads in C, except `5x5random`: the test pins its
  exact text, the `PARAMS 5x5cross` long-term form, and both
  validation errors, because upstream cannot reload its own save. It
  also pins the Cross and Octagon long-term forms. A drag move, an
  undone position with a live redo branch, a completed board, and a
  restart are built in memory and round-tripped in both directions,
  and a live Kotlin drag, undo, redo, restart, and completion must
  write the same records.
- `PegsTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus a moved state, sized traces
  at tiles 48, 63, 82, and 100, cursor and jumping tiles, and a drag
  frame. The warm tests use the oracle's `traceWarm`: a first full
  draw, a real press and drag, then one recorded redraw. They compare
  the C and Kotlin operations, require no `blitter_new`, require the
  `blitter_load` that restores the drag sprite, and check that the
  warm trace is shorter than the first frame. A second drag frame is
  compared the same way.
- `PegsInputParityTest` is semantic. It compares the layout for seven
  viewports and sends real press-drag-release gestures at tiles 48,
  63, 82, and 100, plus the 292x390 and 383x511 viewports with non-zero
  origins on both axes. Every real move must add one `MOVE` and one
  state and change the board. A tap, one-cell, three-cell, diagonal,
  and off-board drag must change neither, and each is paired with a
  check that the press was accepted. It also covers the keyboard jump,
  the second select that cancels it, the obstacle-clamping cursor, and
  modified cursor keys.
- `GuessDescTest` is semantic. It compares the two presets, the
  decoding quirks, the validation messages, and `params + seed -> DESC`
  for every preset, nine seeds, and a numeric sweep, including the
  no-multiple retry fixtures.
- `GuessGameTest` is semantic. It compares new games, text, status
  text, the loose move grammar, hints, drafts and holds, drags,
  backspace, labels, completion, undo and redo, and repeated solves.
  It also compares the requested arrow mode and key labels, including
  the `Clear` label and its `needsArrows` flag.
- `GuessSaveTest` is strict for the save text. It reads every `guess`
  save in the corpus, rewrites it byte for byte, and makes the oracle
  load both versions. It pins the `UI` record in both directions, the
  legacy record without holds, the character-stream decoder quirks, and
  the rejected move after a solve or a win.
- `GuessTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus the palette, labels-on, a
  visible cursor, solved reveals, and, through the oracle's `traceWarm`,
  the warm drag, release, and post-move frames with their blitter
  sequence.
- `GuessInputParityTest` is semantic. It compares the layout at four
  exact board sizes and four letterboxed viewports, then sends real
  colour clicks, a colour drag, and a winning submit at tiles 48, 63,
  82, and 100, plus two letterboxed drags with origins (0,36) and
  (0,195). A real guess must change the draft and add one state, while
  a colour click and the draft, hold, and clear keys must change
  neither the state nor the count.
- `BlackBoxDescTest` is semantic. It compares the five presets, the
  `w`/`h`/`m`/`M` decoding quirks, the saturating `atoi`, the
  validation messages in both modes, and `params + seed -> DESC` for
  every preset, a numeric seed sweep, the nonzero count draw, and the
  collision retry. It pins the description generator order and D-003.
  The little-endian byte order of the grid-seeded reveal generator is
  pinned by the engine test `BlackBoxTest.the reveal generator seeds
  from little-endian grid bytes`; a big-endian seed gives a different
  `random_upto` value there.
- `BlackBoxGameTest` is semantic. It compares new games, the loose move
  grammar, the laser exits, the cagey reveal and its grid-seeded
  choice, the two solve quirks, the lock thresholds, every status text
  branch including the `errors` suffix, the `E<int>` decoder, the
  current key label, the requested keys, and the hidden-state guards.
- `BlackBoxSaveTest` is strict for the save text. It reads every
  `blackbox` save in the corpus, rewrites it byte for byte, and makes
  the oracle load both versions. A Kotlin save for every preset loads
  in C. It pins the `UI` record in both directions, every decoder
  quirk, a save without a `UI` record, the history cases, and the
  rejected move after a reveal. There is no `AUXINFO`.
- `BlackBoxTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus the palette, the flash of a
  hit and an exit, the reveal button, the cursor, and revealed states,
  including a double solve and its 20 red rings. The warm tests use the
  oracle's `traceWarm`: a first full draw, then the input frames, then
  one recorded redraw. A warm frame has no frame, no blitter, no
  whole-window update, and no repeated status. One warm case draws at
  tile 63, so the Kotlin warm helper is compared at a viewport size,
  not only at the preferred tile.
- `BlackBoxInputParityTest` is semantic. It compares the layout for
  five viewports and sends real laser clicks at tiles 48, 63, 82, and
  100 and at the letterboxed origin (1,147). It covers a ball toggle, a
  right-button lock, a column lock and its clear path, a row lock, a
  reveal, a UI-only flash, an unavailable reveal, the cursor keys, a
  keyboard toggle, `requestKeys`, and the vacuous cases: a letterbox
  click, a frame-band click, the three grid corners other than (0,0), a
  pixel just left of tile 0, a drag after the press, a modified cursor
  key, and a keypad digit. Every real laser or toggle must change the
  board and add one state; a UI-only flash, an unavailable reveal, and
  every vacuous event must change neither, while a positive control
  click next to each vacuous case must act. Every case compares the
  save, id, status, status text, `UI` record, and the final
  `pointsSized` trace with the oracle.
- `CubeDescTest` is semantic. It compares the parameter decode quirks
  and the `atoi` paths, the validation messages and their order, the
  four presets, and `params + seed -> DESC` for every preset, the extra
  parameter vectors, fixed seeds, and a numeric sweep. It pins the
  rejected side of the area boundary (`46341x46341` and the triangle
  overflow pairs). It does not call the validator on the accepted side,
  because that enumerates every square and hangs, as C does.
- `CubeGameTest` is semantic. It compares new games, text, status, and
  the loose move grammar; the cursor and keypad direction table on every
  preset; the cube-only `UP` sign negation through the face permutation;
  the 13-move win and its frozen count; undo and redo across completion
  with one event; the palette, the requested keys, and `computeSize`
  with the cursor rectangle at tiles 48, 63, 82, and 100. It also pins
  the C roll rule: a real rotation moves the die and counts one move,
  and a mixed die changes its face permutation while an all-one-colour
  die keeps it; an exact-centre click changes neither the faces nor the
  count.
- `CubeSaveTest` is strict for the save text. It reads the five `cube`
  saves in the corpus, rewrites them byte for byte, and makes the oracle
  load both versions. It makes the oracle load a Kotlin save for every
  preset and for the extra vectors, and pins the ignored `full`
  argument: the solid letter and both dimensions stay in the short id
  and in both `PARAMS` and `CPARAMS`. A played move, an undone position
  with a live redo branch, the 13-move completion, a post-win move, and
  a restart are built in memory and round-tripped in both directions.
  It also pins the description quirks: an out-of-range index clamps to
  zero, spare low bits are ignored, and the description text is
  preserved. The solve operation is unavailable.
- `CubeTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus moved, completed, and post-win
  states loaded from saves, a rotation sweep on every preset, and sized
  traces at tiles 48, 63, 82, and 100 with an inert centre click. The
  trace counts are 27, 24, 36, and 72 operations. The animation is not
  ported, so a trace after a move in a live midend is out of scope.
- `CubeInputParityTest` is semantic. It compares the layout for five
  viewports, then sends real clicks at tiles 48, 63, 82, and 100 and at
  the letterboxed 411x702 viewport with origin (0,146). The exact centre
  and the left border are no-ops. A press, drag, and release at all four
  tiles and at the letterbox origin makes exactly one move at the press.
  The 45-degree diagonals resolve to the vertical moves, and the cursor
  and keypad directions match the oracle on every preset. `CTRL`,
  `SHFT`, and `CTRL+SHFT` cursor keys move, and a plain digit does not.
  The right and middle buttons, the select keys, Enter, Space, TAB, a
  lone drag, and a lone release change nothing. The test drives the
  13-move win through real cursor keys, then undo, redo, and a post-win
  move, and compares the save, id, status, status text, and
  `requestKeys` after every step. Every real rotation must move the die,
  change the board, and add one move; every vacuous event must change
  neither the board nor the count. `requestKeys` is arrow mode 1 with no
  labels.

- `FillingDescTest` is semantic. It compares the three presets, the
  default, the `decode_params` quirks (`13`, ` 13x9`, `+13x9`, `13X9`,
  `13x 9`, `13x+9`), the three validation messages and their order, and
  `params + seed -> DESC` for every preset with the golden seed and
  additional seeds. It pins the accepted wide clue alphabet (`:` is 10
  for `13x9`, `?` is 15 and `A` is 17 for `17x13`) and the run
  arithmetic `z` = 26, `za` = 27, `zz` = 52. It pins only the rejected
  side of the area boundary; no test generates a huge accepted area.
- `FillingGameTest` is semantic. It compares new games, text, and
  status; the `strtol` base-0 move grammar including octal, hex, 32-bit
  wrap, white space, and a sign; the solver strings for every preset, a
  stalled `5x5:y` board, small deduction fixtures, an `AUXINFO` answer,
  and a solve after a real fill. The fill makes the clue board and the
  current board differ, so a solver that reads the current board
  returns a different answer. Completion, undo, and redo; a real click
  plus digit; and the warm click and click-plus-digit frames are also
  compared. `requestKeys` is compared on every preset, including the
  `1`..`9`, `0`, and `Clear` labels and `needsArrows`.
- `FillingSaveTest` is strict for the save text. It reads the four
  `filling` saves in the corpus, rewrites them byte for byte, and makes
  the oracle load both versions. It also makes the oracle load a
  Kotlin-written save for every preset. A played fill, an undone
  position with a live redo branch, a solve, and a restart are built in
  memory and round-tripped in both directions, and a live click plus
  digit, solve, and restart must write the same records. There is no
  `AUXINFO` and no `UI` record, and `PARAMS` equals `CPARAMS`.
- `FillingTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, plus a selected cell, the cursor
  ring, a completed `1x2` board, a partially filled board with an
  overfull 1-region and a correct 2-region, and sized traces at tiles 48,
  63, 82, and 100 and at the letterboxed 411x702 viewport (tile 29,
  origin (2,206)). It also asserts that the cold trace of every preset
  carries no status line.
- `FillingInputParityTest` is semantic. It compares the layout for
  thirteen viewports, then sends real clicks and a click plus digit at
  tiles 48, 63, 82, and 100. A second set of the four tiles uses a
  letterboxed viewport with a nonzero origin. It covers a drag
  selection, a cursor plus digit, a digit alone, a clear with nothing
  to clear, a clear on a filled cell, and a letterbox click followed by
  the cursor-select keys. A real fill must change the board and add one
  move; a selection click, a clear with nothing to clear, and an
  out-of-grid cursor must change neither. The `2x2` digit rule is
  compared, so `3` is accepted on `2x2` and rejected on `1x2`.

- `NetslideDescTest` is semantic. It compares the parameter decode and
  encode quirks, including the short form that drops `b`, the `%g` text
  for `0.1`, `0.25`, and `0.9999`, and the `strtod` rescan that makes
  `3x3b1e-1m5` lose its move target. It compares the five validation
  messages in order, the nine presets with their verbatim `3x3b1` text,
  and `params + seed -> DESC + AUXINFO` for 23 parameter strings and
  nine seeds. It pins the aux answer `S15c9d6234`, the generator draw
  state, and D-005: `3x3bnan` gets `Barrier probability must be a
  number`, while C accepts the rate and aborts at `netslide.c:629`.
- `NetslideGameTest` is semantic. It compares new games, text, and
  status, the loose `sscanf` move grammar including junk, spaces, signs,
  and leading zeros, the row and column slide arithmetic (`|d| = w` is
  an identity that still counts, `|d| > 1` is a scramble), the pointer
  bands at tile 48, the cursor ring with a select at each end, undo and
  redo with the completion event, a restart, and a solve with and
  without `AUXINFO`. It also round-trips the corpus save and a save for
  every preset and extra vector in both directions.
- `NetslideSaveTest` is strict for the save text. It reads all ten
  `netslide` saves in the corpus, rewrites them byte for byte, and
  makes the oracle compare the id, status, undo, redo, text, and
  `AUXINFO`. A Kotlin save for every preset plus wrapping, barrier-rate,
  move-target, non-square, and `2x2` vectors loads in C with the same
  id, canonical text, and `AUXINFO` length. The `full` split is pinned
  (`PARAMS 3x3`, `CPARAMS 3x3b1`, no `b` in the id), and a row slide, a
  column slide, the identity `R0,3`, an undone position with a live
  redo branch, a `SOLVE`, and a `RESTART` are built in memory and
  round-tripped in both directions. A live Kotlin row-plus-column
  history, its undo branch, its redo, a solve, and a restart are loaded
  by C. No `UI`, `PRIVDESC`, or `TIME` record appears.
- `NetslideTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, cold traces of loaded and moved
  states, the cursor traces with `RIGHT`, `SELECT`, and `SELECT2`, warm
  cursor and inert frames (97 operations, no tile, no status, no clear),
  a warm frame of a save whose last record is a `MOVE` (136 operations:
  the three cells of the last moved row are redrawn because the cache
  keeps them dirty, and no other tile is), and sized traces at tiles 48,
  39, 82, 68, 58, and 22: the 241x241 and 200x200 views, the 411x702
  letterbox at tile 82 for 3x3, 68 for 4x4, and 58 for 5x5, and the
  200x200 4x7 board at tile 22. No trace contains a blitter operation.
- `NetslideInputParityTest` is semantic, with one strict warm frame. It
  compares the layout at tiles 48, 63, 82, and 100 and at the
  letterboxed 411x702 viewport with origin (0,145), then sends real row
  and column slides in all four bands at each view. It checks the
  centre cell, a frame corner, both centre bands, a pixel past the right
  edge, and a letterbox click as inert. It also sends a press, drag,
  and release at tile 63 (one move at the press), a full cursor ring
  with a select, and `requestKeys` on every preset. A real slide must
  change the board, add one move, and add one state; an inert click
  must change neither, must return `Unused`, and must leave the warm
  drawstate unchanged.
- `NetslideTest` is the engine test. It pins the deep copy, the slide
  arithmetic, the loose move grammar, the `S` move, `compute_active`,
  the corner flags, the five status patterns, the cursor ring, the
  pointer bands, the cursor-hiding click, `currentKeyLabel`,
  `cursorLocation`, `computeSize`, the palette, and `formatG`.

- `UnrulyDescTest` is semantic. It compares the seven presets with
  their verbatim `8x8dt` text, the decode quirks (`8`, `8x8x8`, `8X8`,
  a trailing space, `8x8dte`, `8u8`, `8x8tu`), the `u`/`d` order, the
  six validation messages in C order (odd, minimum size, area, too
  tall, too long, unknown difficulty), and `params + seed -> DESC` for
  every preset with nine seed strings and a numeric sweep. It pins the
  golden ids, the generated run alphabet without `y`/`Y`, the accepted
  `46340x46340` and `8x34u` boundaries without generating them, and
  the invalid-difficulty encoder guard: an index outside `0..2`
  writes a plain `d` instead of reading past `unruly_diffchars`.
  The rejected `65536x65536` area is also compared with the C message,
  because C validates before it allocates. Generation runs under a
  two-minute timeout, because the retry loops are unbounded.
- `UnrulyGameTest` is semantic. It compares new games, text, and
  status; the `sscanf` move grammar including junk, spaces, signs,
  leading zeros, and 32-bit wrap; every preset's `SOLVE` string; a
  stalled `8x8:zzo` board and an invalid `8x8:AAAzzl` board, where
  Kotlin returns null and C names `No solution found.` or
  `Puzzle is invalid.`; an already solved board; completion, undo,
  redo, and a restart; 45 real clicks to a full board; and the warm
  click frame. The solve reads the decoded initial grid, so a click
  that contradicts the answer leaves the `SOLVE` record unchanged;
  the test pins that case and the `Midend.solve` call. `requestKeys`
  is arrow mode 3 with no labels on every preset.
- `UnrulySaveTest` is strict for the save text. It reads all eight
  `unruly` saves in the corpus, rewrites them byte for byte, and makes
  the oracle compare id, status, undo, redo, and text. A Kotlin save
  for every preset loads in C with the same id and text. The `full`
  split is pinned (`PARAMS 8x8dt`, `CPARAMS 8x8dn`, no `d` in the id),
  and a played click, an undone position with a live redo branch, a
  `SOLVE`, and a `RESTART` are built in memory and round-tripped in
  both directions. A hand-written `AUXINFO` record round-trips and is
  ignored by the solve. No `UI` record appears.
- `UnrulyTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, a horizontal three, a vertical
  three, a count error, the two unique match bars, and a completed
  board without a flash. Sized traces cover tiles 48, 63, 82, and 100
  for `8x8`, `10x10`, and `14x14`, the three 411x702 letterbox
  viewports at origin (3,148), (2,147), and (3,148), and the 200x200
  views. A warm frame after one click and after two clicks is exactly
  `start_draw`, `end_draw`, and no trace contains a status line, a
  blitter, a line, or a polygon.
- `UnrulyInputParityTest` is semantic. It compares the layout for
  every preset at the four exact tile sizes and at the 411x702,
  720x1280, 1024x600, and 200x200 viewports, then sends real clicks at
  tiles 48, 63, 82, and 100 and at the 411x702 letterbox of every
  board size (tile 45 with origin (3,148), 37 with (2,147), and 27 with
  (3,148)). It covers the left cycle (one, zero, clear), the right and
  middle mapping, the half-tile border, the last cell and one pixel
  past it, a clue click with a visible ring, a press, drag, and
  release (one move at the press), a click then a cursor key and a
  select, and the keyboard including modified cursor keys and a keypad
  digit. TAB, a control character, a letter, and a letter with SHFT are
  unused, with and without a visible ring, and leave the state, the
  move list, and the history unchanged, as C does. Every real placement
  changes the grid and adds one state; a border or letterbox click, a
  clue click, a lone drag, and a lone release change neither the grid
  nor the save.
- `UnrulyTest` is the engine test. It pins the parameter decode and
  encode, the validation order, `validateDesc`, the immutable mask and
  the initial grid, the three-way count result, the error bits, the
  five solver techniques and the restart rule, generation
  determinism, the move grammar, the sticky completion and the unique
  rule, `changedState`, undo, redo, restart, the solve from the
  initial grid, `currentKeyLabel`, `cursorLocation`, `computeSize`,
  the palette, the drawstate cache, the error frames, and the
  encoder and unique-pair guards.
- `RangeDescTest` is semantic. It compares the four presets with the
  oracle titles and `params`, the `signed char` decode (`300x6` is
  `44x6`, `+9x6` and `9X6` are `9x9`, `9x6x7` is `9x6`, `128` is
  `-128`, and `65536x65536` is `0x0`), the five validation messages in
  C order including the accepted `64x64` and `127x1` boundaries, the
  run alphabet, the five frozen golden entries, and
  `params + seed -> DESC` for every preset with nine seed strings and
  a numeric sweep. It pins the retry seeds `3x3` seed 3 and `3x5`
  seed 2, and it checks that `newDesc` never writes aux info.
- `RangeGameTest` is semantic. It compares new games, text, and status
  for the default and four presets, the `1x3` game-id text, the
  `sscanf("%c,%d,%d")` move grammar including spaces, signs, leading
  zeros, 32-bit wrap, and the rejected forms, a clue overwritten by a
  move, the black-set-only win with and without dots, the sticky
  status, `changedState`, undo and redo across a completion, every
  preset's `SOLVE`, the `S` answer of an already solved first state,
  the hint on a fresh, a moved, and a full board, and the keys. The
  solve reads the decoded initial grid, so a click that contradicts
  the answer leaves the `SOLVE` record unchanged; the test pins that
  case. `requestKeys` is arrow mode 3 with no labels on every preset.
- `RangeSaveTest` is strict for the save text. It reads all five
  `range` saves in the corpus, rewrites them byte for byte, and makes
  the oracle compare id, status, undo, redo, and text. `PARAMS` and
  `CPARAMS` are equal, the record order is pinned, and `default.sgtp`
  equals `9x6.sgtp` because the default params are `9x6`. A Kotlin
  save for every preset loads in C with the same id and text. A played
  click, an undone position with a live redo branch, a `SOLVE`, a
  `RESTART`, and a `MOVE` that starts with `S` are built in memory and
  round-tripped in both directions; a live click, solve, and restart
  write the oracle text. A hand-written `AUXINFO` record round-trips
  and is ignored by the solve, and the trailing comma and the
  signed-char parameter id round-trip. No `UI` record appears.
- `RangeTraceTest` is strict. It compares every preset with a fresh
  oracle trace and the frozen trace, one outline plus a fill and an
  update per cell, a cursor trace, a moved board, the error marks of
  adjacent blacks, a small or a big clue, and a split board, the warm
  path after a real click and after a border click, a cursor on a
  black cell, sized traces at tiles 48, 63, 82, and 100, a 411x702
  letterbox, and the lowlight derived from a second background. No
  trace contains a blitter, a line, a circle, or a status line.
- `RangeInputParityTest` is semantic. It compares the layout of every
  preset at the four exact tile sizes and the 411x702, 720x1280,
  1024x600, and 200x200 viewports, then sends real clicks at tiles 48,
  63, 82, and 100 and at the 411x702 letterbox of every preset, where
  at least one origin is nonzero (the `9x6` board has origin (0,207)
  at tile 41). Each click of the left cycle (`B`, `W`, `E`) and the
  right cycle (`W`, `B`, `E`) must change the cell, add one `MOVE`,
  and add one state. A clue click with each button, a border or
  letterbox click, a lone drag, and a lone release return `Unused` and
  change neither the grid nor the history. It also covers the
  press, drag, and release (one move at the press), a click followed
  by two cursor keys and a select, the arrow ring, the shift paint
  (including the both-cells-non-empty update), `CTRL`+arrow, a keypad
  digit, and `requestKeys` on every preset. The `Mouse button order`
  preference is pinned on the engine side, because the oracle's
  `pointsSized` cannot apply a preference.
- `RangeTest` is the engine test. It pins the frozen `9x6` solve
  string, the two C undefined-behavior guards, the sticky completion,
  copy and equality, the generator attempt count, a grid that does
  not match its params, the stored description tail, and
  `currentKeyLabel`. The output-order regression on a stalled board is
  the frozen `4x4:1o` answer `SB,1,0B,0,1W,1,1W,0,2W,2,0`.
- `LightupDescTest` is semantic. It compares the nine presets, the
  decode quirks, the exact validation messages in C order, and
  `params + seed -> DESC` for every preset with the golden seed and a
  numeric sweep. It also pins the black-percentage ramp fixtures and
  the two D-006 guard messages.
- `LightupGameTest` is semantic. It compares new games, text, status,
  the move grammar, the `STYLUS_BASED` cycles, the select and modified
  keys, a hidden-cursor select, completion and its stickiness, the
  solver strings with their `I` marks, a solve after a wrong light,
  undo, redo, restart, `requestKeys`, `currentKeyLabel`, and the
  vacuous clicks at the preferred tile.
- `LightupSaveTest` is strict for the save text. It reads all ten
  `lightup` saves in the corpus, rewrites them byte for byte, and makes
  C compare id, status, undo, redo, and text. It pins the
  `PARAMS`/`CPARAMS` split, makes C load a Kotlin save for every preset,
  and round-trips a played light, an impossible mark, an undone
  position with a live redo branch, a `SOLVE`, and a `RESTART`. The
  `SOLVE` case uses the 14x14 Hard board: the 410-character answer with
  30 `L` and 45 `I` entries is compared byte for byte, the same board
  is solved from the start save, and C reloads the Kotlin save with the
  same id, status, and text. No save has a `UI` or an `AUXINFO` record.
- `LightupTraceTest` is strict. It compares the nine frozen traces and
  the oracle `traceNew` for every preset, the frame polygon, the
  column-major tile order, the clue text and its error colour, a light,
  an overlap, an impossible mark, the cursor ring, sized traces at
  tiles 48, 63, 82, and 100, the letterboxed 411x702 view, the
  `show-lit-blobs` blob line, and the warm path.
- `LightupInputParityTest` is semantic. It compares the layout at the
  four exact tiles (48, 63, 82, and 100) and the 411x702 letterbox at
  tile 51 with origin (2,147), then sends real `STYLUS_BASED` left and
  right cycles with matching releases at all five views. Every press
  must change the grid and add one move and one state; a release adds
  neither. A clue, black cell, the four border bands, the letterbox,
  and a middle click change neither. It also covers the four arrows,
  `SHFT` and `CTRL` on an arrow, `SHFT` and `CTRL` on a select, a
  hidden-cursor select, a keypad digit, TAB, and backspace, plus a
  press-drag-release, a lone drag, and a lone release. A real-click win
  on the clue-less 3x3 board is compared click by click, then after
  undo, redo, and a post-win click: the save, id, and status must match
  at every step, and C must reload the final save. The
  `show-lit-blobs` setting is compared on and off through the oracle's
  `prefs` field; the state, id, status, and save must not change, and
  the cached `DrawState.flags` must not contain the preference.
  `requestKeys` is compared on every preset: arrow mode 3 and no
  labels.
- `LightupTest` is the engine test. It pins the parameters, the guard
  order and both D-006 messages, the `newDesc` rejection, the
  description grammar, copy and array independence, the `STYLUS_BASED`
  return values, `currentKeyLabel` without an out-of-bounds read, the
  sticky completion, the initial-state solve, the dirty cache, a
  preference change that dirties no tile while `forceRedraw` applies
  it, the preference outside the cached flags, and the palette.
- `MosaicDescTest` is semantic. It compares the six presets and the
  default 10x10 aggressive board, the `decode_params` quirks (`10`,
  `10X10`, `+10x10`, a leading space, `010x010`, `h1`, `h0`, `h2`, a
  bare `h`, and `10x`), the two validation messages in C order with the
  accepted `100x100` and `3x3333` boundaries, and
  `params + seed -> DESC` for the seven golden entries, nine seed
  strings, and a numeric sweep. The retry fixtures `3x3` seed 12345,
  `5x5` seed 2, and `10x10` seed 1 cover both retry counters, and the
  aggressive `3x3h0` variant differs only when a needed clue is hidden.
  `validate_desc` counts a letter as a run and rejects space, `{`,
  uppercase, and `,`; no description writes `AUXINFO`.
- `MosaicGameTest` is semantic. It compares new games, text, status,
  the loose move grammar (trailing junk, a bare comma, leading zeros,
  32-bit wrap, and an unknown or empty move accepted as a copy), the
  left and right cycles, a clue click, the three drag shapes, the
  fabricated release, completion and its sticky status, undo, redo,
  restart, the solver string for every preset, and a failed solve. The
  completion counter is derived from the per-clue `SOLVED` bits and is
  rebuilt by a recount after each move; it is not a flag. A description
  with no clues loads completed, and one that is already a full
  solution loads open.
- `MosaicSaveTest` is strict for the save text. It reads all seven
  `mosaic` saves in the corpus, rewrites them byte for byte, and makes
  C compare id, status, undo, redo, and text. A Kotlin save for every
  preset loads in C. The `PARAMS 50x50` and `CPARAMS 50x50h0` split is
  pinned with an id that has no `h`, and a played click, an undone
  position with a live redo branch, a `SOLVE`, and a `RESTART` round
  trip in both directions, including a raw `last_state` of 7 or -1. No
  save has an `AUXINFO` or a `UI` record.
- `MosaicTraceTest` is strict. It compares the six frozen traces and
  the oracle `traceNew` for every preset, the margin column and row and
  the corner, the cursor ring, a marked clue, the warm path, sized
  traces at tiles 48, 63, 82, and 100, the 411x702 letterbox at tile 37
  with origin (2,147), and a cell whose raw `last_state` is -1, which
  the drawstate sentinel hides. No trace contains a blitter, a line, a
  circle, or a polygon.
- `MosaicInputParityTest` is semantic. It compares the layout at tiles
  48, 63, 82, and 100 and the 411x702 letterbox at tile 37 with origin
  (2,147), then sends real left and right press and release pairs at
  all five views. Every press must change the board, cycle the expected
  value, and add exactly one move and one state; every release adds
  neither. A clue click cycles like any other cell and leaves the text
  format unchanged. The top and left bands, the right and bottom
  margins, a middle press, and a letterbox click are inert, and a
  margin click hides a visible ring with an `Unused` result. The
  horizontal, vertical, and diagonal drags and the press-without-
  release `e` fill are compared with `pointsSized` and its final trace.
  Unknown and empty moves copy the board and the status but still make
  one history entry, as C does. The cursor keys, `SHFT` and `CTRL` on
  an arrow, a keypad digit, Enter and Space selects, a hidden-cursor
  select, and `u`/`r` are compared with `keys`. A real-click win is
  compared click by click and freezes every non-arrow input, while an
  arrow still reveals and moves. `requestKeys` is arrow mode 3 with no
  labels on every preset.
- `MosaicTest` is the engine test. It pins the presets, the decode and
  encode of the aggressive flag, the validation order, the description
  grammar, the initial flags, the encoder's 27-cell run, the overlong
  description guard, the retry attempt counts, the shared clue board,
  the `s` move, `changedState`, `currentKeyLabel`, the fresh UI and
  drawstate values, and the palette.
- `PalisadeDescTest` is semantic. It compares the five golden
  descriptions, the four presets at every seed with their full saves,
  a numeric seed sweep, the decode quirks and validation messages, the
  winnow/divvy partition through the encoded RNG state, the accepted
  huge area that D-007 refuses only at generation, and a generated
  save that reloads through the engine.
- `PalisadeGameTest` is semantic. It compares new games, text, status,
  real clicks, inert clicks, the half-grid cursor sequences, the
  full-grid completion of `2x3n3:d33`, control and shift, the loose move
  grammar (valid and rejected), one-sided and both-bits borders, an `S`
  completion, undo and redo, the completion event, the solve with and
  without `AUXINFO`, and both `requestKeys` modes. The full mode is
  compared through the oracle's `fullCursor` field: labels `Left`,
  `Bottom`, `Top`, `Right`, buttons `H/J/K/L`, `needsArrows` true, and
  arrow mode 1; the half mode is arrow mode 3 with no labels.
- `PalisadeSaveTest` is strict for the save text. It reads all five
  `palisade` saves in the corpus, rewrites them byte for byte, and
  makes C agree on the id, status, undo, redo, text, and resave. It
  pins the `k == wh` split save (`PARAMS 5x5n5`, `CPARAMS 5x5n25`),
  the empty seed, a garbage `AUXINFO`, and the in-memory history
  shapes. The split save has one known difference, recorded as debt:
  C drops `SEED` when `CPARAMS` fails the full validation
  (`midend.c:2606-2613`) and Kotlin keeps it; the id, status, text,
  solve, and move records are identical.
- `PalisadeTraceTest` is strict. It compares the four frozen traces and
  the oracle `traceNew` for every preset, a loaded save, the cursor
  ring, the full cursor's eight corner lines, the warm path after a
  click and after a reveal, sized click traces at tiles 48, 63, 82, and
  100, the 411x702 letterbox, `clear-complete-regions` on and off, the
  `S` answer, and the first-draw reset after a resize or a forced
  redraw. No trace contains a blitter, a circle, or a flash color.
- `PalisadeInputParityTest` is semantic. It compares the layout at
  tiles 48, 63, 82, and 100 and the 411x702 letterbox of every preset
  (tiles 67, 45, 37, and 25, all with a nonzero origin on both axes).
  It sends real fence clicks on four distinct edges at each tile and
  the letterbox: every fence changes the border bytes and adds one move
  and one state, and the final state, id, status, and trace match
  `pointsSized`. The half-grid cursor continues from the clicked edge
  through reveal, `ON -> OFF -> maybe -> ON`, the corner no-effect, and
  a second fence. The full-grid cursor is compared through the oracle's
  `prefs` field: three control toggles, three shift toggles, the `H/J/K/L`
  letters in both modes, an outward-edge letter that changes nothing,
  and the completion of `2x3n3:d33` with the completion event. Empty,
  one-sided, and both-bits moves match the C state; a both-bits edge
  ignores a click and a select. Letterbox, margin, middle, lone drag,
  lone release, and outward-edge clicks change neither the border nor
  the count. The two preferences are pinned as non-state: `cursor-mode`
  and `clear-complete-regions` change no id, status, text, or save, and
  the clear pref is compared through the oracle `trace` with `prefs`.
- `DivvyParityTest` compares the canonical roots, class sizes, attempt
  count, and encoded generator state of the direct `divvy` oracle op
  for the palisade presets, path rectangles, `k = 1`, `k = 2`, and a
  retry seed; `DivvyTest` pins the fixed vectors, the invalid input
  guards, and the connected-k-omino invariant.
- `LayDominoParityTest` compares the grid, attempt count, and encoded
  generator state of the direct `laydomino` oracle op for even and odd
  areas, one-row boards, and seeds that need several repair passes;
  `LayDominoTest` pins the fixed vectors, the invalid input guards, the
  repair-pass counts, and the valid-tiling invariant.
- `PalisadeTest` is the engine test. It pins the parameters and
  presets, the decode quirks, the validation order and the D-007 area
  guard, the frozen description vectors, the run encoder, the
  `validateDesc` grammar, the `newState` completed rule and the shared
  clues, the copy, the `F` grammar, one-sided and both-bits borders, the
  `S` move, the half-grid and full-grid cursor sequences, the `H/J/K/L`
  aliases, a real click, the cursor geometry, `requestKeys` in both
  modes, `computeSize`, the palette, the solver and `isSolved`, the
  solve with and without `AUXINFO`, the text format, the `changedState`
  rules, undo/redo/restart events, and a divvy retry.
- `InertiaDescTest` is semantic. It compares the four golden
  descriptions, the three presets at nine seed strings, a numeric seed
  sweep over `2x3`, `2x4`, `3x3`, `4x4`, and `5x5` and the presets, the
  five decode quirks, the validation order and messages, the accepted
  `46340x46340` boundary that D-008 refuses only at generation, and the
  generator attempt counts from a C counter probe. No test generates a
  huge accepted area.
- `InertiaGameTest` is semantic. It compares new games, text, and
  status for every preset, all eight direction digits on the open 5x5
  board, a real move against an inert keypad 5, a death with its gem
  count and `D1`, a gem before a mine and a mine before a gem, a real
  win with the completion event across undo and redo, the letterbox
  click, a click on the player's tile, a click into a wall, the empty
  `MOVE` record as direction 0, the solver and its `SOLVE` record, a
  followed tour to `Auto-solved.`, the two failed-solve messages, the
  unvalidated `S` payload as a defined `NoEffect`, the `dup_game` revive
  save, a dead state with `UI D2`, and both `requestKeys` modes.
- `InertiaSaveTest` is strict for the save text and semantic for the
  state. It reads the four corpus saves, rewrites them byte for byte,
  and reloads them in C; a Kotlin save for every preset loads in C with
  the same id and text. A played move, a live redo branch, a `SOLVE`, a
  `RESTART`, and the real `icons/inertia.sav` with 20 `MOVE` records and
  `STATEPOS 5` round-trip in both directions. The `UI` record is
  `D<deaths>` (`inertia.c:1515-1523`), is always written including
  `D0`, and follows the prefix `sscanf("D%d")` table: `D 7` is `D7`,
  `D-3` stays `D-3`, `X9` and ` D4` become `D0`, and `D12junk` becomes
  `D12`.
- `InertiaTraceTest` is strict. It compares the three frozen traces and
  `traceNew` for every preset, cold traces from the initial, dead,
  completed, solved, mid-follow, deaths, and restarted saves, the dead
  star and the status strings, sized cold traces at 411x702 and the four
  exact-fit viewports, and the warm frame with no input and with an
  inert click. `inertia_follow` must be after the cells and before the
  status. A warm frame after a real move is Kotlin-only, because the
  oracle records the animation frame at `anim_pos == 0`.
- `InertiaInputParityTest` is semantic. It compares the layout at tiles
  48, 63, 82, and 100 and the 411x702 letterbox (tile 39 with origin
  (1,185), and the 15x12 board at tile 26 with origin (4,188)). All
  eight nearest-octant clicks are real moves at each exact tile and in
  the letterbox, and each one changes the player and adds one state and
  one move. One pixel on the `COORD(5)` boundary moves right; the pixel
  before it is the player's cell and is inert. A click in the letterbox
  is a real move with no bounds test. The arrow keys, all eight keypad
  digits, and the four diagonal digits make the matching move; a plain
  `8`, keypad 5, a control or shift cursor key, space, and a select
  without a tour change neither the player, the gems, nor the count.
  Inert clicks, a middle press, a lone drag, and a lone release at each
  tile keep the state and match the settled `pointsSized` trace. A death
  adds `D1`, a loaded `D2` keeps `DEAD!   Deaths: 2`, and a won `D2`
  keeps `COMPLETED!   Deaths: 2`. A gem on the path is collected and
  the move stops; the loaded empty `MOVE` goes up and traces like the
  oracle. The settled cursor rectangle is the player's cell, and a
  solve-follow select makes the tour move and reaches `Auto-solved.`
- `InertiaTest` is the engine test. It pins the parameters and presets,
  the decode quirks, the validation order and the D-008 guard, the
  untouched `RandomState` across a refused generation, the exact 2^20
  generation boundary, the description grammar, the initial state, the
  text format, the status
  strings, the move grammar and the movement loop, the death and
  completion transitions, the click octants and `FROMCOORD` bands, the
  `S` payload and the revive, `encodeUi`/`decodeUi`, `currentKeyLabel`,
  the solver strings, `computeSize`, the palette, `requestKeys`, the
  cursor rectangle, and the follow/status draw order.
- `TentsDescTest` is semantic. It compares the six presets, the seven
  golden descriptions, every preset at nine seed strings, a 61-seed
  sweep over `8x8de`, `8x8dt`, `4x4de`, `4x4dt`, `5x4de`, and `5x4dt`,
  the decode quirks, the validation order and messages, the `4x4`
  difficulty downgrade, the `validateDesc` strings, and the two D-009
  abort descriptions. It pins the generated aux text against the
  oracle's own `SOLVE` record for every preset and three seeds. The
  generator keeps the candidate partition, each tent's adjacency list
  in `U, L, R, D` order with the tents in row-major order, the seeded
  `Matching.match`, and the retry stream, so a reordering changes every
  description; the sweep and the goldens are the guard. The `treemap`
  id values are not observable, because a consistent renumbering of the
  right vertices leaves the matching unchanged.
- `TentsGameTest` is semantic. It compares new games, text, and status
  for every preset, the move grammar, real press, drag, and release
  batches, the three-state left and right click cycles, the select and
  letter keys, a real win with its completion event, undo and redo
  across the win, the sticky post-win status, the solve with and without
  `AUXINFO`, an `AUXINFO` that is invalid for the board, a solve after a
  wrong user move, every preset's `SOLVE`, the two solver failure
  messages mapped to the exact Kotlin strings, the zero-clue solve that
  is not a win, the `PARAMS`/`CPARAMS` split, and `requestKeys`.
- `TentsSaveTest` is strict for the save text. It reads all seven corpus
  saves, rewrites them byte for byte, and reloads them in C with the
  same id, status, undo, redo, and text. A Kotlin save for every preset
  loads in C, and is byte-identical to the oracle save for nine seeds,
  including the generated `AUXINFO`. A played click, a drag, a live redo
  branch, a `SOLVE`, and a `RESTART` round-trip in both directions. The
  `PARAMS`/`CPARAMS` split, the generated `AUXINFO`, and the absent `UI`
  record are pinned. A tree move and the two D-009 descriptions are
  defined errors. The corpus was not regenerated.
- `TentsTraceTest` is strict. It compares the six frozen traces and
  `traceNew` for every preset, the exact operation counts, the tree
  trunk and five leaf circles, the cursor ring, the two clipped error
  diamonds of adjacent tents, sized cold traces at tiles 48, 63, 82, and
  100, the 411x702 letterbox, and the warm path after a press, a drag,
  and a release. Every frame keeps the `w + h + 2` grid lines, because
  the C `started` flag is never set. No trace has a status line, a
  blitter, or a flash color.
- `TentsInputParityTest` is semantic. It compares the layout at tiles
  48, 63, 82, and 100 and the 411x702 letterbox of every preset, the
  `FROMCOORD` bands, and real input at all five views. A press and
  release changes a blank cell and adds one move and one state; a press,
  drag, and release produces one move at the release; the three-state
  left cycle and the right cycle are compared for each round; a press
  without a release, a middle press, a cancelled drag, an out-of-band
  press, and a click on a tree change neither the grid nor the count.
  `requestKeys` is arrow mode 3 with no labels, and the Android
  full-cursor mode is arrow mode 1 with no labels.
- `TentsTest` is the engine test. It pins the parameters and presets,
  the decode quirks, the validation order, the description grammar, the
  initial state and its shared clues, the two D-009 exceptions, the
  text format, the move grammar, the stylus cycles, the drag lock, the
  cursor and select keys, `currentKeyLabel`, the sticky completion, the
  status values, the solver and its initial-grid rule, the two solver
  failures, the engine generation attempt counts, the generated aux
  answer, a warm redraw that changes a clue's error flag,
  `computeSize`, the palette, `requestKeys`, the cursor rectangle, the
  error marks, and the always-drawn grid lines.
- `MagnetsDescTest` is semantic. It compares the eight presets and the
  default, the nine golden descriptions, every preset at nine seed
  strings, a numeric sweep, the decode quirks, the validation order and
  messages, the `46340x46340` boundary and the `65536x65536` rejection,
  the `"" -> 6x5de` downgrade, and the `PARAMS`/`CPARAMS` split. It
  compares the generated `AUXINFO` with the oracle save, so the solver
  inside the retry gate is pinned, and it pins the `6x5de` and `6x5dt`
  attempt counts (1 and 37) from `newDescWithAttempts`. The generator
  stream is `LayDomino.layout`, one shuffle in `lay_dominoes`, the
  strip-clues shuffle, and the retry gate; a reordering changes every
  description, so the goldens and the sweep are the guard.
- `MagnetsGameTest` is semantic. It compares new games, text, and
  status, the move grammar and the measured acceptance table, the left
  and right cycles, the four clue bands, the `D` clue move, the
  singleton no-ops, a real win with its completion event, the sticky
  flag, undo, redo, restart, the solve with and without `AUXINFO`, the
  two-attempt fallback on the decoded initial state, the overwrite of a
  wrong magnet, the `S;` solve of a solved board, every preset's
  `SOLVE`, `changedState`, `currentKeyLabel`, and `requestKeys`. The
  state-count test asserts that a real magnet changes the grid and the
  flags and adds one move and one state, that a `D` toggle changes only
  the shading and adds one move, and that an inert click changes
  neither.
- `MagnetsSaveTest` is strict for the save text. It reads all nine
  `magnets` corpus saves, rewrites them byte for byte, and reloads them
  in C with the same id, status, undo, redo, text, and `AUXINFO`. A
  Kotlin save for every preset loads in C, and a generated save is
  byte-identical to the oracle for eight seeds. It round-trips a played
  magnet, a `D` clue toggle, a live redo branch, a `SOLVE`, and a
  `RESTART` in both directions, pins the `PARAMS 6x5de` /
  `CPARAMS 6x5dtS` split and the absent `UI` record, and solves a save
  without `AUXINFO` through the initial-state fallback.
  `default.sgtp` and `6x5de.sgtp` are the same file.
- `MagnetsTraceTest` is strict. It compares the eight frozen traces,
  a cold loaded state, a visible cursor over a magnet (`DS_CURSOR`,
  `COL_CURSOR`, `COL_TEXT`), a set neutral and a not-neutral cell (the
  background-colored `line` cross and the `COL_NOT` question mark), a
  `COL_DONE` clue after a `D` toggle, a `COL_ERROR` clue after an
  overfull row, sized traces at tiles 48, 63, 82, and 100, the 411x702
  letterbox, the first-draw corner symbols and the two whole-window
  updates, and a Kotlin-only warm frame with the changed tile and
  clue. Every frozen trace contains the `circle` operations of the
  rounded domino halves. No trace contains a blitter, a polygon, a
  clip, or a flash color; only the neutral trace adds `line`
  operations.
- `MagnetsInputParityTest` is semantic. It compares the layout at
  tiles 48, 63, 82, and 100 and the 411x702 letterbox, the `FROMCOORD`
  cell and clue bands, and real left and right clicks at all five
  views. Every real magnet or neutral cycle changes the grid or the
  flags and adds one move and one state; every real clue click changes
  the shading, keeps the grid, and adds one move and one state. A
  middle press, a corner click, an out-of-band click, a lone drag, and
  a lone release change neither the board, the shading, nor the count;
  a press-drag-release makes one move at the press. The cursor keys,
  `SHFT` and `CTRL` on an arrow, both select buttons, TAB, backspace,
  letters, and keypad digits are compared with the oracle.
  `requestKeys` is arrow mode 3 with no labels, and the full-cursor
  mode is arrow mode 1 with no labels.
- `MagnetsTest` is the engine test. It pins the parameters and eight
  presets, the decode and encode quirks, the validation order and
  messages, the `validateDesc` grammar, the text format, the move
  grammar and the three-state cycles, the `D` toggle as a state move,
  the singleton no-ops, the deep state copy with shared counts, the
  solver with and without `AUXINFO`, the initial-state fallback, a
  completion that hides the cursor and reports once, the key-label
  table, `requestKeys`, `computeSize`, the eight pinned attempt counts,
  the saturated `atoi` and move scanner, and a strip board that keeps
  the missing clues and solves.
- `LayDominoTest` pins three measured vectors. The 6x5 seed `12345`
  grid is `1,0,3,2,5,4,...` and needs two repair passes; the odd-area
  3x3 has one singleton at index 6 and one pass; the one-row 1x5 board
  needs no pass. The encoded state is recorded for each vector, so a
  missing candidate or neighbour shuffle fails even when the tiling
  looks valid. The test also sweeps every `1 <= w,h <= 8` over four
  string seeds and one byte-seed state for partner symmetry,
  orthogonality, and the `w*h % 2` singleton count, pins the repair
  counts 0, 1, 2, and 6, checks the scratch buffers, and rejects
  invalid sizes and short buffers.
- `LayDominoParityTest` compares the grid, the prealloc grid, the
  attempt count, and the encoded generator state of the direct
  `laydomino` oracle op for 15 shapes from 1x1 to 10x9, including
  seeds with 1, 2, 3, and 6 repair passes, and continues from an
  encoded state. It also pins the oracle input guards.

`tents` has one deviation, two enablement debts, and the usual S9
item:

- D-009: two `8x8de` descriptions pass `validate_desc` and abort the C
  decoder. `8x8:ddddddddz,0,...` fails
  `i >= 0 && i <= w*h` at `tents.c:1292`; `8x8:dddddddcz_,0,...` fails
  `type == TREE` at `tents.c:1294`. The shipped app keeps assertions, so
  each is a real SIGABRT, and without assertions the same path writes
  outside the grid. The port throws
  `tents: the description runs past the grid` and
  `tents: the description ends on a non-tree cell`. `TentsTest`,
  `TentsDescTest`, and `TentsSaveTest` pin both; no test sends them to
  the oracle. Every valid description is unchanged.
- Generation cost: Easy on a large board is slow. 20x20 takes about
  0.6 s and 25x25 Easy takes 39 s in C, and `validate_params` accepts an
  area up to `INT_MAX`. The app generates off the UI thread, and D-027
  rejects an area above 484 cells (22x22) at `newDesc` before the first
  draw with `Width times height is too large for this build`; the
  measured 22x22 Easy generation is 6.87 s on the phone, and 23x23 does
  not finish in 12 s on the host. Validation and the load path keep the
  C acceptance. The generation tests stay on the six presets and small
  boards.
- The params-dependent `can_format_as_text_now`
  (`tents.c:1390-1393`, `w <= 1998 && h <= 1998`) has no seam hook.
  `Game.canFormatAsText` is a constant and `Midend.textFormat` checks
  only that constant, so a hand-written board above 1998 cells returns
  text where C returns null. Normal play cannot reach the size, so this
  is seam debt, not a deviation. A params-aware hook would be a
  speculative feature for one game.
- The initial-grid solve and the sticky completion are measured C
  behavior, not deviations. C `midend_solve` passes `states[0]`, so
  `Tents.Shared` keeps the decoded initial grid and `solve` reads it;
  `TentsGameTest` plays a wrong tent before the solve and pins the same
  `SOLVE` record. `completed` is copied by `executeMove` and only ever
  set, so a post-win move keeps status 1 and raises no second event;
  undo reaches the still-completed state, the next undo clears the
  status, and redo restores it and raises the event again.
- The generated `AUXINFO` is the C answer. `newDesc` returns
  `Description(desc, auxInfo)` with `S` and one `;Tx,y` per tent in
  row-major order, so a Kotlin-generated save is byte-identical to a C
  save for every preset and nine seeds. `TentsDescTest` pins the aux
  text as the oracle's `SOLVE` record, and `TentsSaveTest` compares the
  whole save. Every corpus save that already carries `AUXINFO` keeps it
  byte for byte. `solve` still reads the initial grid and returns an
  existing aux verbatim.
- The app runs the Kotlin backend (seventh enablement wave,
  2026-11-18). The engine declares the three-field configuration schema
  (Width, Height, Difficulty with `":Easy:Tricky"`), and the app builds
  the dialog from it with the `one-key-shortcuts` page because
  `get_prefs`/`set_prefs` are `NULL`. Generation runs off the UI thread,
  and the app surfaces the D-009 message. The shell shows the game's
  message when a hand-written board fails to solve. S9 is a
  tested `currentKeyLabel` helper with no Android call site.
  `KotlinGamesTest` and `SeventhWaveTileSizeInputTest` cover it.

`magnets` has no deviation and three enablement items:

- Generation cost: the eight presets generate in under 20 ms, but
  `16x14de` takes about 5.9 s and `20x20` does not finish in 90 s.
  `validate_params` accepts an area up to `INT_MAX`, and the retry loop
  is unbounded. The app generates off the UI thread. The owner made the
  size bound warning-only (decision 2b): `Game.generationWarning` names
  every board of 224 cells or more, so `16x14de` warns and no parameter
  set is rejected. No test generates a huge accepted area.
- `laydomino` is done. `LayDominoParityTest` compares the grid,
  prealloc grid, attempt count, and encoded random state against the
  direct `laydomino` oracle op for 15 shapes, including seeds with 1,
  2, 3, and 6 repair passes and a continuation from an encoded state.
  `LayDominoTest` pins the 6x5, 3x3, and 1x5 vectors and the valid
  tiling over `1 <= w,h <= 8`. A wrong candidate shuffle or neighbour
  shuffle changes the encoded state and, through magnets, every `DESC`.
- The initial-grid solve and the two attempts are measured C
  behavior. C `midend_solve` passes `states[0]`, so `Magnets.Common`
  keeps the decoded initial grid; `solve` first runs the current state
  and then the decoded initial state, and the diff is always against
  the current state. `MagnetsGameTest` and `MagnetsTest` pin the
  fallback after a wrong neutral domino, the `AUXINFO` path that wins
  over a wrong board, the overwrite of a wrong magnet, and the `S;`
  solve of an already solved board.
- The app uses the Kotlin backend. The engine
  declares the four-field configuration schema (Width, Height,
  Difficulty with `":Easy:Tricky"`, Strip clues), and the app builds
  the dialog from it with the `one-key-shortcuts` page because
  `get_prefs`/`set_prefs` are `NULL`. Generation runs off the UI
  thread. S9 is a tested `currentKeyLabel` helper
  with no Android call site. `KotlinGames.handles(MAGNETS)` is true
  (fifth enablement wave, 2026-11-18), and
  `FifthWaveTileSizeInputTest` clicks a cell and the border corner at
  two tile sizes on the Pixel 4a 5G.

`pattern` has no deviation and three enablement items:

- `PatternDescTest` is semantic. It compares the five golden
  descriptions and the default with fresh oracle games, a numeric seed
  sweep per preset, the small sizes `1x2`, `2x1`, `1x5`, `5x1`, `2x2`,
  `2x3`, `3x2`, `3x3`, `4x4`, and `5x5`, the retry counts from
  `newDescWithAttempts`, the decode quirks (`6`, `+6x5`, `4X4`, `06x05`,
  `4x4x4`, a leading and a trailing space), the validation messages and
  order, the accepted `46341x46340` boundary and the rejected
  `46341x46341`, the `DESC` grammar with a bare `/` and the suffix
  cases, and the `15x15` clue that selects the small font. No test
  generates an accepted huge area.
- `PatternGameTest` is semantic. It compares new games, text, status,
  the move grammar and its accepted and rejected forms, the left and
  right cycles, the drag snap and the clamp, the middle rectangle, the
  immutable select, the no-op win, the real win, the sticky completion,
  undo, redo, restart, the solve with and without `AUXINFO`, the
  reachable solver failure, the solve of a crafted `MOVE S1100` save
  that must come from the decoded initial grid and not the played grid,
  `currentKeyLabel`, and `requestKeys`.
- `PatternSaveTest` is strict for the save text. It reads the six
  corpus saves, rewrites them byte for byte, and reloads them in C with
  the same id, status, undo, redo, and text. `PARAMS` and `CPARAMS` are
  always equal. A Kotlin save for every preset loads in C. It round
  trips a played click, an undone position with a live redo branch, a
  `SOLVE`, a `RESTART`, a save without `AUXINFO`, an empty seed, and a
  suffix description with all cells immutable. No save has a `UI`
  record. `default.sgtp` and `15x15.sgtp` are the same file.
- `PatternTraceTest` is strict. It compares the five frozen traces and
  the oracle `traceNew` for every preset, a dense row whose five clues
  use the one-space separator, a cold loaded state, a
  visible cursor with the two `COL_CURSOR` polygons and the two guided
  clue colours, a `COL_ERROR` clue, the completed `2x2` frame, the warm
  path after a press, a release, a cursor key, and a win, sized traces
  at tiles 48, 63, 82, and 100, and the 411x702 letterbox at tile 25
  with origin (6,152). No trace contains a blitter, a status line, or
  a flash frame.
- `PatternInputParityTest` is semantic and `ui`. It compares the layout
  at tiles 24, 48, 63, 82, and 100 and at the 781x781 (tile 48, origin
  (6,6)) and 411x702 (tile 25, origin (6,152)) letterboxes. Real left
  and right press and release pairs run the full three-way cycle at
  every view: every step changes the grid, adds one move and one state,
  and the final state, id, status, history flags, and trace match
  `pointsSized`. The snaps are compared in both axes, a drag past the
  right edge clamps to the last column, and the unsnapped middle drag
  marks the `U0,0,2,2` rectangle. A clue-band click at `TOCOORD(0)-1`
  and a letterbox tap are inert; a press at `TOCOORD(0)` moves; an
  immutable click changes neither the grid nor the count. A select on
  an immutable clue cell adds one state and one `U` move without a
  visible change, and a cursor walk to the four corners draws the two
  cursor polygons. The `2x2` no-op win reaches status 1 with one
  completion event and no grid change. `requestKeys` is arrow mode 3
  with no labels on every preset, and the full-cursor hint is mode 1.
- `PatternTest` is the engine test. It pins the parameters and five
  presets, the decode and encode quirks, the validation order and
  boundaries, the description grammar and its messages, the small
  descriptions, the retry attempt counts at seed `12345` and seed `0`,
  the suffix cells, the deep state copy with shared clue data, the
  solver and the hard-board null, the text format, the palette,
  `computeSize`, `currentKeyLabel`, `requestKeys`, and the derived
  font size. The attempt counts have no C counterpart, so they are the
  fixture for the retry stream.
- The `STYLUS_BASED` cycles are C behaviour, not a port choice. A left
  press cycles unknown to filled to empty to unknown, a right press
  runs the same cycle backwards, and a middle press always targets
  unknown. A pointer press only starts a drag preview; the move happens
  at the release. A click on an immutable cell is UI only, while a
  cursor select on one produces a 1x1 `U` move. The left and right
  fills snap to the dominant axis from the press, and a middle drag
  does not snap.
- The no-op win is measured C behavior. `2x2:1/1//2,aaAA` loads at
  status 0 because `new_game` does not run the completion test. The
  first select only reveals the hidden cursor; the second produces a
  1x1 move even on the locked cell, and the completion test then sets
  status 1 and raises one event. The grid does not change.
- The solve quirks are C behavior. Every generated game carries
  `AUXINFO` as `S` plus one `'1'` or `'0'` per cell, and `solve`
  returns it verbatim. An aux-less save recomputes the same answer with
  the line solver. The `S` move sets `completed` and `cheated`, so a
  solve reaches status 1 but raises no completion event. `completed`
  is sticky, a post-win move keeps status 1, and a redo into the
  completed state raises the event again. The hand-written
  `4x4:2/1/2/1/1.1/2/1/1` cannot be finished one line at a time; C
  returns `Solving algorithm cannot complete this puzzle`,
  `Pattern.solveGame` carries it in `SolveOutcome.error`, and
  `SolveErrorParityTest` compares it with the oracle.
- Generation cost is enablement debt: the five presets are under
  30 ms, `40x40` takes about 0.3 s, `50x50` takes about 16 s, and
  `100x100` does not finish in a minute. The app generates off the UI
  thread, and the generator retries until no row or column is
  monochrome, which is the C loop with no bound. The owner made the size
  bound warning-only (decision 2b): `Game.generationWarning` names every
  board of 2500 cells or more, and no parameter set is rejected. The
  engine
  declares the two-field configuration schema (Width, Height), and the
  app builds the dialog from it with the `one-key-shortcuts` page
  because `get_prefs`/`set_prefs` are `NULL`. The status text is gated
  on `wantsStatusBar`, and `Midend.solveAttempt` carries the solve
  message. S9
  is a tested `currentKeyLabel` helper with no Android call site.
  `KotlinGames.handles(PATTERN)` is true (fifth enablement wave,
  2026-11-18), and `FifthWaveTileSizeInputTest` clicks a cell and the
  clue band at two tile sizes on the Pixel 4a 5G.

`signpost` has no deviation and three enablement items:

- `SignpostDescTest` is semantic. It compares the six presets, the
  default, and both encodings with the oracle, the seven golden
  descriptions, every preset at the nine named seeds, and a numeric
  sweep over `4x4`, `4x4c`, `5x5c`, and `4x5c`. The free-ends presets
  guard the head and tail picks, and the corner presets guard the
  fill draws; a pinned fill-retry fixture (`4x4` seed 3) and a sweep
  that proves the strip never retries for the presets guard the retry
  stream. It also pins the decode quirks (`4`, `4c`, `4x4junk`,
  `+4x4`, ` 4x4`, `4x-4`, `04x04`, `4x4c5`, `4x4x4`, and other
  suffixes), the validation
  order and messages, the `DESC` grammar with `0a`, `16a`, a 17th
  group, a bad character, and a short description, and that no
  generated game has aux text.
- `SignpostGameTest` is semantic. It compares the new game, text, and
  status of every preset, the move grammar through a save replay
  (`L`, `C`, `X`, `S`, `H`, signs, leading zeros, trailing junk, and
  junk text), the number and colour chaining, the `DSF` loop refusal,
  undo and redo, the solve, and both solver error strings
  (`Puzzle is impossible.` and `Unable to solve puzzle.`). One 2x2
  board pins the `numsi` clear bound: the loop stops at `n-1`, so
  `check_completion` reads a stale `numsi[n]` and links number `n-1` to
  the removed cell again; the text after one more move is the C
  comparison. The text form is compared at 99 cells on `11x9` and at
  100 cells on `10x10`.
- `SignpostSaveTest` is strict for the save text. It reads the seven
  corpus saves, rewrites them byte for byte, and reloads them in C
  with the same id, status, undo, redo, and text. A Kotlin save for
  every preset and three seeds loads in C. It round trips a played
  link, a cut, an undone position with a live redo branch, a `SOLVE`,
  a `RESTART`, a `MOVE H`, and a hand-written `S` save. `PARAMS` keeps
  the short form and `CPARAMS` the full form with `c`; a `PARAMS 4x4c`
  save is accepted and rewritten byte for byte. No save has a `UI` or
  an `AUXINFO` record.
- `SignpostTraceTest` is strict. It compares the six frozen traces at
  the preferred tile with the oracle, including the 78-colour palette,
  the operation counts, the single `changed_state`, and one `T x T`
  `blitter_new`. Cold traces run at tiles 48, 63, 82, and 100 and at
  the 411x702 letterboxes (tile 82, origin (0,146) for `4x4c`; tile
  51, origin (2,147) for `7x7c`). The warm path compares a press, a
  mid-drag frame, the frame after a release, and a link frame. The
  blitter cache is the guard: the warm frames pin `blitter_load` with
  its update before a changed drag frame, a live drag adds
  `blitter_save` and the indicator polygon, and the frame after a
  release has no save. `traceWarm` records only the final redraw, so the
  erase on the release frame itself is pinned Kotlin-only: the engine
  test records that frame and asserts `blitter_load`, its update, and no
  save. A cut preview with the pointer outside the grid, and a keyboard
  drag that moves the ring and the sprite together, are compared too.
  No trace has a flash or a status line.
- `SignpostInputParityTest` is semantic and `ui`. It compares the
  layout at phone, tablet, landscape, small, and the four exact fits.
  Real press-drag-release links run at tiles 48, 63, 82, and 100 and
  at the 411x702 letterbox `7x7c` view with origin (2,147); each
  makes one `L`, adds one state, and matches the oracle state and
  text. The reverse right drag links in the other order, a release
  outside the board makes `C` or `X`, and a click on the source adds
  nothing. The four no-op shapes (a left press on the final immutable
  number, a right press on the first, a lone release, and a drag that
  never leaves its source) change neither the board nor the count;
  each no-op also asserts that the press reached the game, and a real
  link follows as the vacuity guard. Keyboard sequences cover arrows,
  a modified arrow, the two selects, `x`, `X`, backspace, TAB, a keypad
  digit, `u`, and `r`; the keyboard drag labels change from `To here`
  to `Cancel`. `requestKeys` is arrow mode 3 with one `Clear` label on
  button 8.
- `SignpostTest` is the engine test. It pins the parameters and six
  presets, the decode and encode quirks, the validation order, the
  goldens, the fill and strip retry counts, the description grammar,
  the state fields, the stale `numsi[n]` read, the move grammar,
  chaining, completion, the text form including the 99-cell and
  100-cell boundary, `currentKeyLabel`, `changedState` including the
  null guard, `requestKeys`, `cursorLocation`, the palette,
  `computeSize`, the drawstate blitter, the release-frame erase, and
  the current-then-initial solve order. The solve order has no
  two-success fixture, because a board that the solver completes has
  one forced solution; the test attaches a different solved board as
  the fallback and checks that it is never used.
- The two-state solve is measured C behavior. `solve_game` tries the
  current state first and the decoded initial state second. `State`
  carries the initial board and `solveAttempt` reproduces the order. A
  link that makes the current board impossible returning the initial
  board's
  `SOLVE` record pins it; the `S` move sets `usedSolve`, so the solve
  reaches status 1 without a completion event.
- The text form keeps the C `state->h` column bound. For `w > h` the
  last columns of every row are dropped and the port reproduces it;
  for `w < h` C reads `dirs`, `nums`, and `flags` past their arrays
  and writes past the buffer, so the port defines the out-of-range
  cell as direction N, number 0, and no flags and pins that
  Kotlin-only. The oracle is not asked for a `w < h` text. Above 99
  cells `textFormat` returns `""`, which is what the oracle prints
  for the null text of `can_format_as_text_now == false`, because the
  seam constant has no params-aware hook.
- Size bound. Generation runs on the calling thread; the six
  presets are milliseconds, but `validate_params` accepts an area up
  to `INT_MAX`, and the fill and strip loop retries through the solver
  with no bound, as C does. The owner made the bound warning-only
  (decision 2b): `Game.generationWarning` names every board of 400
  cells or more, and no parameter set is rejected.
  `Midend.solveAttempt` carries the two C solver
  messages, and the shell shows them. The engine declares the Width,
  Height, and `Start and end in corners` schema, and the app builds the
  dialog from it. The `Victory rotation effect` (`flash-type`)
  preference with `:Unidirectional:Meshing gears` stays on the live `Ui`
  and out of the state, id, status, text, and save, and the status text
  is gated on `wantsStatusBar`. S9 is a
  tested `currentKeyLabel` helper with no Android call site.
  `KotlinGames.handles(SIGNPOST)` is true (fifth enablement wave,
  2026-11-18), and `FifthWaveTileSizeInputTest` drags a link and presses
  the final immutable number at two tile sizes on the Pixel 4a 5G.

`inertia` has one deviation and three open items:

- D-008: C `validate_params` accepts an area up to `INT_MAX` cells,
  including `46340x46340`, because the only upper test is
  `w > INT_MAX / h`. `new_game_desc` then allocates a grid and three
  `wh*8` scratch arrays: two byte arrays of about 17 GB each and an
  int array of about 69 GB, plus the grid. The unbounded attempt loop
  does not finish. The port rejects an area above 2^20 cells at
  `newDesc` before any RNG draw with
  `Width times height is too large for this build`, keeps the C
  messages and the accepted `46340x46340` and `1024x1024` boundaries in
  validation, and changes no valid game. `InertiaTest` and
  `InertiaDescTest` pin both sides. Validation is unchanged; only
  generation is guarded. The app's configuration dialog must surface
  the guard.
- Solve failure: `inertia` returns no move when the board is already
  solved or dead, so `Midend.solve` returns false and the app's
  `KotlinGameEngine.solveEvent` throws the game's
  `Midend.solveAttempt` message, and the shell shows it. Both cases are
  normal for inertia.
- The app runs the Kotlin backend (seventh enablement wave,
  2026-11-18). The app surfaces the D-008 guard and shows the
  `one-key-shortcuts` page because `get_prefs`/`set_prefs` are `NULL`.
  The `inertiaFollow` DrawSink operation restores `hidePrimary`. S9 is a
  tested public helper with no Android call site. `KotlinGamesTest` and
  `SeventhWaveTileSizeInputTest` cover it.

`lightup` has one deviation and two enablement debts:

- D-006: C accepts `46340x46340`, allocates about 17 GB, and does not
  terminate; C also accepts `blackpc = 100` with difficulty 1 or 2 and
  loops forever, because the difficulty check rejects every all-black
  attempt and the `blackpc < 90` ramp cannot fire. The port rejects the
  first with `Width times height is too large for this build` above
  2^20 cells and the second with
  `A board of 100% black squares cannot be generated at this difficulty`.
  The exact 2^20 boundary and every valid parameter set are unchanged,
  including `blackpc = 100` at difficulty 0. `LightupTest` and
  `LightupDescTest` pin both messages and the unchanged all-black
  description.
- The `show-lit-blobs` preference (boolean, default true) lives on
  `Lightup.Ui.drawBlobsWhenLit`. `Game.setPrefs` applies it to the live
  UI after `newUi` and after `Midend.fromSave`, because there is no
  `decodeUi` and a load recreates the UI. The shell forces a redraw
  after the change, as C recreates the drawstate on a preference change. The
  value must not enter
  `DrawState.flags`: C reads it in `tile_redraw`
  (`lightup.c:2219`), so a warm frame after a preference change alone
  is empty. The truth of this rule is pinned by `LightupTest` and
  `LightupInputParityTest`; the app wiring is in place.
- S9: `currentKeyLabel` is a tested helper with no Android call site.
  `LIGHTUP` is enabled (third enablement wave, 2026-11-18), so
  `KotlinGames.handles(LIGHTUP)` is true; D-006 has no `Review:` line,
  and the declared `show-lit-blobs` schema matches the non-NULL C
  `get_prefs`. `ThirdWaveTileSizeInputTest` covers two tile sizes on
  the Pixel 4a 5G.

`unruly` has two C undefined-behavior guards, one seam decision, and
one size bound:

- `encode_params` indexes `unruly_diffchars[diff]` with no bound
  (`unruly.c:238`). Validation runs before every encoding in the
  midend, so an invalid `diff` never reaches it. The port writes a
  plain `d` for any index outside `0..2`; `UnrulyDescTest` pins the
  three values `3`, `99`, and `-1`. This is a port definition, not a
  game-rule change.
- `unruly_solver_check_uniques` asserts `nonmatch != -1` and
  `grid[i1] == EMPTY` (`unruly.c:671`, `674`). The counters are
  rebuilt from the grid and updated on every placement, and a cell
  holds only `EMPTY`, `N_ONE`, or `N_ZERO`, so a consistent state
  cannot reach either assert. The port skips the pair instead of
  indexing `-1` or overwriting a value; `UnrulyTest` pins the
  already-blocked pair and a lying counter. The oracle is not asked
  for either path, because the shipped C build keeps assertions.
- A stalled or inconsistent board returns null from `Game.solve`,
  because the game has not overridden `solveGame`. C sets
  `No solution found.` or `Puzzle is invalid.` and returns `NULL`. The
  seven presets always solve, so the English messages are pinned only
  by the engine test and `UnrulyGameTest`; this is engine debt, not a
  deviation.
- `validate_params` accepts `46340x46340`, because the area check
  stays `w > INT_MAX / h` as C writes it. Generation would then
  allocate a 2,147,395,600-cell grid and mask, more than 10 GB in this
  port, and run the solver over it. No test calls `newDesc` or
  `newState` on a huge accepted area; `validateParams` pins the
  accepted `46340x46340` side, and `UnrulyDescTest` compares the
  rejected `65536x65536` message with C.
  The owner made the size bound warning-only (decision 2b):
  `Game.generationWarning` names every board above 196 cells, the
  largest preset, and no parameter set is rejected. No guard is added,
  and no deviation entry is needed.
- The generator retries without a bound in two nested loops
  (`unruly.c:1379-1476`). A solver defect that over-fills can make the
  "too easy" test fail forever. `UnrulySolver.generate` is that loop, so
  the C hang and the Kotlin hang are the same hang. The generation tests
  run with a timeout; no production guard is added, because a bound
  would change the seed stream. `KotlinGames.handles(UNRULY)` is true
  (sixth enablement wave, 2026-11-18), and
  `SixthWaveTileSizeInputTest` clicks the border and a cell at two tile
  sizes on the Pixel 4a 5G.

`range` has one C undefined-behavior guard, one C-defined solver rule,
one seam decision, and one enablement debt:

- The connectedness reasoning finds its DFS root with
  `for (i = 0; i < n && grid[i] == BLACK; ++i);` and then writes
  `dfs_parent[i]` and `dfs_depth[i]` (`range.c:465-471`). On an
  all-black board `i == n`, so both writes are outside the arrays. A
  player can paint every cell black with clicks and then press `h`, so
  the path is reachable. The port treats "no non-black cell" as "no
  moves"; `RangeTest` pins the empty answer and that `h` returns
  `Unused`. `RangeGameTest` pins the hint guard only and does not ask
  the oracle. The shipped C build keeps assertions, and an
  AddressSanitizer build of the oracle aborts at `range.c:467`.
- The recursion reasoning takes the move buffer by value and returns it
  unchanged (`range.c:605-638`). The `do_solve` call on a duplicated
  state advances only its own copy of the end pointer, so the moves a
  branch finds never reach the caller's buffer. The `SOLVE` string
  therefore holds only the moves of the techniques 0-2, and a board
  that only recursion can crack answers `S`; the hint on the same
  board finds no move. The port runs every branch on a scratch list
  and discards it. `RangeGameTest` pins `3x3:d3_3c` (`S`, and `h`
  does not move), `4x3:1e4e`
  (`SB,1,0B,0,1W,1,1W,0,2W,2,0`), and `4x4:2_5k5b` (eight moves)
  against the oracle. The guard is not a deviation, and
  `DEVIATIONS.md` is unchanged.
- `solve_game` reads `states[0]` in C (`midend.c:2109`), while the
  Kotlin seam passes the current state. `Range` keeps the decoded
  initial grid in `Shared` and solves that, so a move does not change
  the `SOLVE` record. `RangeGameTest` plays a click that contradicts
  the answer and pins that the record still matches C. This is a seam
  decision, not a game-rule change.
- The parameters are two `signed char` fields (`range.c:92`), so
  `decode_params` truncates and the `atoi` quirks are part of the
  format: `300x6` is `44x6`, `+9x6` and `9X6` are `9x9`, and `9x` is
  rejected. `RangeDescTest` pins each quirk against the oracle. The
  `w + h <= 128` validation bound caps the board at `64x64`, so unlike
  `unruly`, `filling`, and `netslide`, no huge-area guard is needed.
- The `Mouse button order` preference (`left-mouse-button=fill|dot`)
  lives on `Range.Ui.swapButtons` with a false default. `Game.prefs`
  declares the choice and `Game.setPrefs` copies it, and the midend
  applies the value after `newUi` and after `Midend.fromSave`, because
  `range.c` has no `decodeUi` and a load recreates the UI. The shell
  reloads prefs when `preferencesName` changes.
  `RangeInputParityTest` pins the rule on the engine side against
  `range.c:1368-1375` (a left press cycles forwards and a right press
  backwards, and a select key is not swapped); `RangeTest` checks that
  `left-mouse-button=dot` reaches the live UI and swaps the click, and
  `PrefsApplicationTest` covers the field. The oracle `prefs` op
  compares the schema and the text round trip in
  `PrefsSchemaParityTest`.
- `currentKeyLabel` is a tested helper because C has no Android call
  site yet (seam item S9).

`Game.solveGame` carries the C error channel, so an insoluble position
returns `null` with `No solution exists for this position` in
`SolveOutcome.error`, as C reports it. `FlipTest` pins both halves, and
`SolveErrorParityTest` compares the text with the oracle. Generated
games are always soluble; the message is reachable only through a
hand-written description.

`guess` has two enablement debts, both recorded in `work/port-queue.md`:

- The `show-labels` preference lives on `Guess.Ui` with a false
  default. `Game.setPrefs` applies the stored per-backend preference
  after `newUi` and after `decodeUi`. C's order is `new_ui`,
  `set_prefs`, `decode_ui` (`midend.c:2669-2673`); for guess the last
  two commute, because `decode_ui` does not touch `show_labels`, so
  applying the preference after `Midend.fromSave` is observationally
  identical. No preference
  seam is added in this slice, and there is no `DEVIATIONS.md` entry.
- Backspace at the Submit slot reads and writes
  `curr_pegs->pegs[npegs]` in C (`guess.c:974-980`); the port treats the
  slot as empty and never writes it. The unmarkable submit
  (`guess.c:956-963`) reaches `midend.c:1052`, where a build with
  assertions aborts on the rejected move. The C debug oracle aborts on
  that path, so it cannot compare the result. Kotlin keeps C's move
  text and lets the midend map the rejected move to `NoEffect`, which
  is C's release-build behavior. `GuessTest` pins both results.
  Neither is a game-rule deviation, so `DEVIATIONS.md` is unchanged.

`blackbox` has one deviation and one shared sink rule:

- D-003 draws the ball count first, as C does, and rejects only a drawn
  count above the arena cell count in `newDesc`, because C can then
  retry forever for a free cell. A drawn count that fits generates the C
  description, so a maximum above the cell count does not change a seed
  that C can generate. The validator keeps the C messages, and every
  parameter set whose drawn count fits (`nballs <= w*h`) keeps the seed,
  `DESC`, and save text. The parity tests run under a five-second
  timeout: one compares a fitting `w3h3m1M100` seed and other ranges
  with the oracle, and one pins the throw. The engine tests pin the same
  two results.
- Black Box is the first port with both a status bar and a warm trace.
  Upstream `drawing.c` records a status text only when it changed, so a
  warm frame repeats no status line. `KotlinTraceSink.status` keeps the
  last text for the same reason. A cold trace still has exactly one
  status line, and a warm frame emits a changed status exactly once;
  `BlackBoxTraceTest` pins both.
- D-003 has no `Review:` line, so the game is enabled in the third
  wave (2026-11-18). The game has no preference page (C `get_prefs` is
  NULL) and declares no schema. `ThirdWaveTileSizeInputTest` fires two
  lasers at two tile sizes on the Pixel 4a 5G and checks the move
  count, the save text, and the status line.

`cube` has one enablement debt and no deviation:

- `validate_params` accepts a grid area up to `INT_MAX` and then
  enumerates every square to count the colour classes
  (`cube.c:583-595`). `46341x46341` is rejected by the area check and is
  fast, but the accepted `46340x46340` would enumerate 2,147,395,600
  squares and hang, exactly as C does. The port keeps the C behavior, so
  no test calls `validateParams` or `newState` on a huge accepted area.
  The parity and engine tests pin only the rejected side of the
  boundary. Any app path that validates user input, in particular the
  configuration dialog, needs a guard before `CUBE` is enabled. A guard
  would reject parameters that C accepts, so it needs a new deviation
  entry and a test, and it must not change any accepted normal
  parameter. Until then this is enablement debt, not a deviation.

`filling` has two C undefined-behavior decisions, one C solver crash,
one C solver defect, one deviation, and one enablement debt:

- `current_key_label` reads `ui->sel` with no NULL test
  (`filling.c:1450`). A fresh UI has `sel == NULL`, so a key label over
  a non-clue cell dereferences NULL. The port treats a null selection as
  "not selected" and returns `Select`. `FillingTest` pins it.
- An Android letterbox click sets `cur_x` and `cur_y` from the raw cell
  formula, even outside the board (`filling.c:1503-1508`), and the
  cursor-select paths index `clues[w*cur_y+cur_x]` and
  `sel[w*cur_y+cur_x]` without a bounds test (`filling.c:1449`,
  `1531-1532`, `1545-1546`). The port treats an out-of-grid cursor as
  "no cell": nothing is selected, toggled, or filled, and no read or
  write leaves the array. `FillingTest` pins the cursor and selection
  state at the preferred tile; `FillingInputParityTest` pins the same
  result at tiles 48, 63, 82, and 100. The C oracle is not asked for
  the cursor-select paths, because it reads out of bounds; the letterbox
  click itself is compared, because it does not read the clue board.
- The solver asserts on an overfull region of 1s
  (`assert(s->board[i] != 1)`, `filling.c:796`), which two adjacent 1
  clues or two player-filled 1s can produce. The shipped C code keeps
  assertions: `app/src/main/jni/cmake/setup.cmake:17-25` removes
  `-DNDEBUG` from every release flag, so a release build aborts like
  the oracle. The port does not abort; it skips the region and still
  returns a board. `FillingTest` pins `2x2:1a1a` as `s1212` with status
  0 and `cheated` set. This is deviation D-004. The oracle aborts on
  that fixture, so the case is not compared.
- A stalled solve still returns the best partial board while C sets
  `Sorry, I couldn't find a solution` and the midend ignores the error
  (`filling.c:1378-1390`, `midend.c:2113-2117`). C applies the partial
  board. The port returns the same partial board, so Kotlin also applies
  it and `Midend.solve` returns true. The status stays 0 and `cheated`
  is set. `FillingTest` and `FillingGameTest` pin the `5x5:y` answer
  `s` plus 25 zeros; a port that returned `null` would differ. This is
  C behavior, not a game-rule change.

The first, second, and fourth decisions are port definitions, so
`DEVIATIONS.md` gains only D-004.

`filling` has one size bound:

- `validate_params` accepts `46340x46340`, because the area check stays
  `w > INT_MAX / h` as C writes it. Generation then allocates about
  8.6 GB of `int` boards. No test calls `newDesc` or `newState` on a
  huge accepted area; only the rejected `46341x46341` boundary is
  pinned. The C `make_board` restarts the whole board through
  `goto retry` with no bound, and `FillingSolver.makeBoard` keeps that
  loop. The owner made the size bound warning-only (decision 2b):
  `Game.generationWarning` names every board above 221 cells, the
  largest preset, and no parameter set is rejected. `KotlinGames.handles`
  is true (sixth enablement wave, 2026-11-18), and
  `SixthWaveTileSizeInputTest` clicks the border, the digit with no
  selection, a region, and its digit at two tile sizes on the Pixel 4a
  5G.

`netslide` has one deviation and one enablement debt:

- D-005: `3x3bnan` passes C `validate_params` because both range tests
  compare false with NaN. `new_game_desc` then casts NaN to `int` at
  `netslide.c:628`, which is undefined behavior and fails the assertion
  at `netslide.c:629`; the shipped build aborts. The port rejects the
  rate with `Barrier probability must be a number` before generation.
  `NetslideDescTest` pins the message and `Midend.newGame`; the oracle
  is not asked to abort.
- `validate_params` accepts `46340x46340`, because the area check stays
  `w > INT_MAX / h` as C writes it. Generation then allocates two
  `IntArray`s of 2,147,395,600 cells, about 17 GB, and does not finish.
  C has the same accepted boundary and allocates about 4.3 GB. D-026
  rejects an area above 90,000 cells (300x300) at `newDesc` before the
  first draw, with `Width times height is too large for this build`;
  validation and the load path keep the C acceptance. `NetslideTest`
  and `GenerationLimitTest` pin the cap and the message. A save or game
  id above the cap still loads and plays.
- The app runs the Kotlin backend (seventh enablement wave,
  2026-11-18), covered by `KotlinGamesTest` and
  `SeventhWaveTileSizeInputTest`.

`mosaic` has no deviation. Three rules need care in the app, and the
aggressive 100x100 latency is an enablement debt:

- The completion state is derived, not stored. `game_status` is
  `not_completed_clues == 0`, and that counter is the number of shown
  clues without a `SOLVED` bit. Only the 3x3 block of the changed cell
  is refreshed, so the flags are history, not a function of the board:
  a `DESC` with no clues loads completed, a `DESC` that is already a
  full solution loads open, and a `SOLVE` sets the counter to 0 without
  touching the flags, so a later move reopens the status. The port
  keeps the counter and the incremental flags. The app must use
  `changedState` for the win and must not treat `status == 1` as the
  event, because a solve and a loaded completed save also report 1.
  `MosaicGameTest` and `MosaicInputParityTest` pin each case.
- The move grammar is loose: an unknown move type or an empty move is
  accepted as a copy of the state with one history entry, only the
  commas between parameters are checked, the last parameter keeps
  unparsed trailing text, and `last_state` is not validated (7 stores
  7, and -1 collides with the drawstate sentinel). These are C
  behavior, not deviations, and `MosaicGameTest`, `MosaicTraceTest`,
  and `MosaicInputParityTest` pin them. A stricter parser would change
  what a `.sgtp` may contain.
- The encoder compares the hidden-run counter with `<=` before it
  increments (`mosaic.c:809-816`), so a run of 27 hidden cells stores
  123 (`{`), a run of 28 emits `{` and drops one cell, and a run of 29
  emits `{a`. The port reproduces the arithmetic literally, and
  `MosaicTest` pins `1{2` for runs 27 and 28 and `1{a2` for 29. A
  generated board never reaches it: `validate_desc` rejects `{`, but
  4400 sampled boards across the presets used only `0`-`9` and `a`-`z`,
  and the longest run was 26. This is not a deviation, because it is
  unreachable from generated boards and reproduces C exactly.
- The aggressive `100x100` board takes 30-45 s in C, because
  `hide_clues` calls the solver once per needed clue. The area is
  capped at 10000 cells, so this is latency, not a hang. The app
  generates a board off the UI thread. No test generates an
  aggressive `100x100`; the accepted `100x100` and `3x3333` boundaries
  are pinned in validation only. The owner made that bound warning-only
  (decision 2b): `Game.generationWarning` names every board above 2500
  cells, and `SlowGenerationDialogTest` checks the warning and Cancel
  for the aggressive `100x100` case. `KotlinGames.handles(MOSAIC)` is
  true (fifth enablement wave, 2026-11-18), and
  `FifthWaveTileSizeInputTest` taps a clue and the margin at two tile
  sizes on the Pixel 4a 5G. S9 is not applicable (PARITY section 7):
  the Android shell never displays `current_key_label`.

`palisade` has one deviation and two open items:

- D-007: C `validate_params` accepts an area up to `INT_MAX`, and
  `new_game_desc` then allocates clue, rim, scratch, winnow-order, and
  `4*wh` addable tables, which is tens of gigabytes. The port rejects an
  area above 2^20 cells at `newDesc` before any RNG draw, keeps the C
  messages and the accepted `46340x46340` boundary in validation, and
  changes no valid game. `PalisadeTest` and `PalisadeDescTest` pin both
  sides. The app's configuration dialog must apply the guard.
- Save debt: `midend.c:2606-2613` drops the `SEED` record when
  `CPARAMS` fails the full validation. The `k == wh` split save
  (`PARAMS 5x5n5`, `CPARAMS 5x5n25`) is the only reachable shape, and
  C re-serializes it without `SEED` while Kotlin keeps it. The id,
  status, text, solve, and every move record are identical, so no
  deviation entry is added. `PalisadeSaveTest` pins both sides; fixing
  the shared `Midend.fromSave` would change the save text of that one
  hand-written file, and no corpus or generated save reaches it.
- Enablement: the app runs the Kotlin backend (seventh enablement
  wave, 2026-11-18). The engine declares the three-field configuration
  schema (Width, Height, Region size), and the app builds the dialog
  from it. D-007 rejects more than 50 regions at generation. The
  two-preference schema (`cursor-mode` and `clear-complete-regions`) is
  applied through `Game.setPrefs` after `newUi` and after
  `Midend.fromSave`, with a forced redraw and a key re-request.
  `KotlinGameEngine.requestKeys` already
  passes `palisadeFullCursor` to the game, and S9 is not needed because
  `current_key_label` is `NULL`. `KotlinGamesTest` and
  `SeventhWaveTileSizeInputTest` cover it.

`undead` has no deviation and three enablement items:

- `UndeadDescTest` is semantic. It compares the eight presets, the
  default, and both encodings with the oracle, the nine golden
  descriptions, every preset at the nine named seeds, and a numeric
  sweep. It pins the decode quirks (`4x4d`, `4x4dte`, `4X5`,
  `4x4dnjunk`, `4x`, `-4x4`, `4x-4`, the empty string, and the area
  boundaries `18x3`, `3x18`, `13x4`, `19x3`, and `3x19`), the
  validation order and messages, and a seed for every retry gate:
  `num_total <= 4`, the ratio bounds, a path over `max_length`, a
  missing monster type, a Tricky singleton, and an Easy depth over 3.
  The `Float` ratio and the stable `num_monsters` sort are guarded by
  the goldens and the sweep, and `UndeadDescTest` pins the retry counts.
  A `4x4` board whose longest ray is 22 cells, longer than `w*h = 16`,
  loads and solves like the oracle.
- `UndeadGameTest` is semantic. It compares the new game, text,
  status, and empty status text of every preset, real selects and the
  three count-row moves, the keyboard, and the count-row geometry at
  tiles 48, 63, 82, and 100, the move grammar through a save replay
  (`G`, `V`, `Z`, `g`, `v`, `z`, `E`, `M`, `D`, the optional
  separator, a leading `;`, a double `;;`, an empty move, signs,
  spaces, and out-of-range ids), undo and redo, the real win and its
  one completion event, a post-win move, a redo into the win, the
  hint payload for every preset, a wrong move that does not change the
  payload, a solve on a solved board, and both solver errors
  (`Puzzle is inconsistent` and `Puzzle is unsolvable`). It also pins
  the inert mirror, letterbox, and fixed-cell clicks and the empty
  status text.
- `UndeadSaveTest` is strict for the save text, semantic for the
  state. It reads the nine corpus saves, rewrites them byte for byte,
  and reloads them in C with the same id, status, undo, redo, and
  text. A Kotlin save for every preset and nine seeds loads in C. The
  `PARAMS`/`CPARAMS` split is pinned: `PARAMS` is the Normal long form
  and `CPARAMS` keeps the Easy or Tricky difficulty, and the short id
  drops it. It round trips a played `G0`, a pencil `g0`, a clear
  `E0`, a clue `D1,0`, an `M`, an empty `MOVE`, a `SOLVE`, a
  `RESTART`, and an undone position with a live redo branch. The
  in-memory history case plays a real cell change through the count
  row, undoes it with a live redo, redoes it, solves, and restarts,
  comparing each text with the oracle. A hand-written fixed board
  loads, is not editable by a click, and solves in both directions; a
  hand-written `AUXINFO` is ignored and stays in the save; an `S`
  move is cheated in both directions. No generated save has a `UI` or
  an `AUXINFO` record.
- `UndeadTraceTest` is strict. It compares the eight frozen traces at
  the preferred tile with the oracle, including the 12-colour palette,
  the three count icons, the eight hint texts in the stable sort
  order, the four mirrors, and the column-major cell order. Cold
  traces run at tiles 48, 63, 82, and 100 and at the 411x702
  letterboxes (tile 63, origin (1,115); tile 55, origin (0,118); tile
  43, origin (2,126)). The cursor, the normal highlight, the pencil
  highlight, the Android same-cell toggle, a done clue, a clue error,
  a count error, a `cell_errors`-only redraw, ascii mode, a solved
  state, a cheated solve, a fixed board, and the warm path are
  compared. The `cell_errors` case draws a first frame, selects a
  floor cell, presses the ghost count icon, and records that input's
  own frame on the same drawstate. The frame has the guessed cell, two
  cells whose guess and pencils did not change (one is a mirror), one
  hint error, and no other cell. The oracle cannot record an input's
  frame, so the test pins the operations. No trace has a flash or a
  status line. The thick-line trace unit is fixed:
  `thick_line t x1 y1 x2 y2 c`, where `t` is the thickness in
  thousandths and both endpoints are in thousandths (the oracle uses
  `tc_milli`), so a mirror at tile 64 is `thick_line 4000 ...`.
- `UndeadInputParityTest` is semantic and `ui`. It compares the layout
  at 411x702 (tiles 63, 55, and 43 with the measured origins), the
  exact sizes at tiles 48, 63, 82, and 100, real cell selects at all
  four tiles and at the 411x702 letterbox, and the count row at all
  four tiles and at the letterbox. A separate case derives the ghost,
  vampire, and zombie hit boxes from the oracle trace and clicks every
  exact box edge, one pixel outside, and one pixel inside at tiles 64
  and 63; only the inside clicks make a move. A cell change at the
  count row alters the guess, adds one move and one state, and leaves
  the status at 0; a clue click adds one `D` move and toggles the mark
  without touching the guess or the status; a second click on the same
  clue removes it. The pencil select pair, a right press that enters
  pencil mode, and a second right press that hides the highlight change
  neither the board nor the history at every tile and at the letterbox.
  A mirror click and a fixed-cell click also keep a visible pencil
  highlight, and the final frame is compared with the oracle. A
  mirror, a letterbox point, the middle button, the one-pixel bands of
  the cell rect and of the count row, a lone drag and release, and a
  press-plus-drag are inert. The
  final frame is compared with `pointsSized` for a select at every
  tile and the letterbox, a clue toggle, a pencil pair, both right
  presses, and a real count-row move, so the highlight and the pencil
  triangle are part of the comparison. The keyboard covers arrows, a
  modified arrow, both selects, digits, the letters, the clear keys,
  `M`, and a key with the cursor hidden; the `pencil-keep-highlight`
  cases compare the mouse and keyboard paths; `requestKeys` is arrow
  mode 2 with the five labels `Ghost`, `Vampire`, `Zombie`, `Clear`,
  and `Mark` on every preset.
- `UndeadTest` is the engine test. It pins the parameters and eight
  presets, the `atoi` decode quirks including saturation, the encode
  fallback, the validation boundaries, the description grammar and
  lenient forms, the text format, the fixed cells, the move grammar,
  the error arrays, the solver payload and the two messages, the
  path arrays, the five key labels, `currentKeyLabel` with the hidden
  highlight quirk, `setCursorVisible`, `changedState`, the two
  preferences and their defaults, the palette, `computeSize`, the
  drawstate caches, and the copy semantics.
- Two upstream undefined behaviours have port definitions, not
  deviation entries. The C path arrays hold `wh` entries, but a ray
  can revisit a cell through both mirror channels; the port sizes
  `p`, `xy`, and `mapping` at `4*wh` entries (the number of cell and
  direction states) and caps a walk at `4*wh` steps. This changes no
  accepted description, and the oracle is not asked to compare a
  corrupted path; `UndeadTest` pins the `4*wh` length and a 22-cell
  ray on a 16-cell board. No test reaches the walk cap, and none can:
  the ray map is injective, a cycle has no border entry, so a walk
  never repeats a `(cell, direction)` state and is shorter than
  `4*w*h`, below the `4*wh` cap. The allocation test is the pin.
  In `solve_iterative` and
  `solve_bruteforce` a mask of 0 leaves `loop.guess` uninitialised in
  C; the port defines it as "no allowed type" and stops the
  enumeration. `UndeadTest` and the solve error cases pin the result.
- Enablement debt. Generation runs on the calling thread: the eight
  presets take about 1 ms, but a hand-written maximum `13x4` board can
  take about 1.3 s, so the app generates off the UI thread. The
  solver is a brute force bounded by `3 ** num_total`, so a
  hand-written description can be expensive; the app must run `SOLVE`
  off the UI thread. `Undead.solveGame` carries `Puzzle is inconsistent` and
  `Puzzle is unsolvable`, and the shell shows the message. The engine
  declares the Width, Height, and `:Easy:Normal:Tricky` schema, and the
  app builds the dialog from it. `pencil-keep-highlight` (boolean,
  default true) and `monsters` (`:Pictures:Letters`, default Pictures)
  are applied after `newUi` and after `Midend.fromSave`, with a forced
  redraw for `monsters`. The status text is gated on `wantsStatusBar`. Neither
  preference is serialised. S9 is a tested `currentKeyLabel` helper
  with no Android call site. `UNDEAD` is enabled (third enablement wave,
  2026-11-18), so `KotlinGames.handles(UNDEAD)` is true. The game has
  no deviation entry and no open retry or hang item; its declared
  `pencil-keep-highlight` and `monsters` preferences match the non-NULL
  C `get_prefs`, and its Width, Height, and difficulty configuration
  comes from `Game.configure`. `ThirdWaveTileSizeInputTest` sends a
  real cell tap and a real clue tap at two tile sizes on the Pixel 4a
  5G.

- `untangle` has no deviation and three enablement items:

  - `UntangleDescTest` is semantic. It compares the five presets and
    the default with the oracle, the `atoi` decode quirks and the two
    validation messages, the two-pass description validator, every
    preset at nine seeds, a numeric seed sweep, and a frozen
    description plus `AUXINFO`. The generator draws one `w*h` grid
    shuffle and one or more `n` label shuffles, so the sweep guards
    both. The float evidence is the exact `make_circle` table at
    denominator 64 in `UntangleTest`: every preset's `sin`/`cos`
    coordinates are pinned as integers, because those coordinates
    reach `DESC`, `AUXINFO`, every save, and every trace. The same
    values match the oracle on this host; a host that disagreed would
    need a coordinate table or a correctly rounded `sin`, not a golden
    update.
  - `UntangleGameTest` is semantic. It compares the new game, the
    empty text form and status text, undo and redo with a live redo
    branch, a real completion and its one event, the solver for every
    preset, a solve after a move, a solved board, and the missing
    `AUXINFO` case. It sends real clicks and press-drag-drag-release
    drags at tiles 48, 63, 82, and 100 and compares state, id, status,
    and the final frame with `pointsSized`. The fixed hit radius is
    60 pixels: a click 59 pixels from a point hits and 61 misses, at
    every tile size. The radius does not scale with the tile, so the
    same offset is a different fraction of the disc.
  - `UntangleSaveTest` is strict for the save text and semantic for
    the state. It reads all six corpus saves, rewrites them byte for
    byte, and compares id, status, undo, redo, text, and the raw
    `AUXINFO` bytes with C; `PARAMS` equals `CPARAMS` because
    `encode_params` ignores `full`. A Kotlin save for every preset and
    nine seeds is byte-identical to a fresh C game and reloads in C.
    In-memory history plays a click and a drag, undoes to a live redo,
    redoes, saves, restarts, and replays the empty `MOVE`; a `MOVE`
    whose value is `S` fails in both engines. No save has a `UI`
    record.
  - `UntangleInputParityTest` is semantic and `ui`. It sends real
    clicks and drags at tiles 48, 63, 82, and 100 and at a small
    letterbox whose origin is subtracted on both sides; every real
    click or drag adds one move and one state and changes the point,
    and an inert press changes neither. It covers the four arrow
    quadrants, the failed first arrow that leaves `cursorpoint = 0`,
    the select pair, space and `SHFT+TAB`, a modified arrow, the
    midend stripping `SHFT+CURSOR_SELECT` to a plain select, the inert
    centre, letterbox, drag-without-press, and release-without-press
    cases, and `requestKeys` for every preset with and without the
    full-cursor hint. The full-cursor hint is the C midend's forced
    `ANDROID_ARROWS_ONLY`, so the mode is 1 instead of 3; the labels
    stay empty.
  - `UntangleTraceTest` is strict. It compares the five frozen traces,
    a loaded save, tiles 48, 63, 82, and 100, the 411x702 letterbox,
    the cursor frame, the press frame with the drag point and its
    neighbours, the release frame, `vertex-style=number`,
    `show-crossed-edges=true`, and the warm path, which settles to
    exactly `start_draw` and `end_draw`. The animation and the flash
    are not ported, and C draws an animation frame at `anim_pos == 0`
    after an undo or a `SOLVE`, so a strict trace is taken from
    `traceNew`, a loaded save, or a drag move, never after an undo or
    a solve in a live midend.
  - `UntangleTest` is the engine test. It pins the parameters, the
    constants, `squarert`, `cross`, the exact `make_circle` table, the
    shared graph and copy semantics, the sticky completion, the move
    grammar (`S;P...`, `SP...`, the empty move, a rejected `S`, a
    rejected second `S`, `P9` in range for `n = 10` and `P10` out of
    range), `normsq` truncation, the drag and snap arithmetic, the
    60-pixel hit test, the two solver errors, the single-precision
    symmetry choice and `+0.5F` rounding, and the cursor rectangle.
  - The pointer snap cannot be compared through the oracle because
    `pointsSized` has no `prefs` field. It is engine-only: with
    `snap-to-grid` on, a release at `x = -1` truncates to grid 0 and
    commits `P0:5,5/18` at `n = 10`, `T = 64`, and 36 pixels outside
    reaches grid -1 and cancels. The keyboard snap is compared with
    the oracle `keys` prefs path. The three preferences
    (`snap-to-grid`, `show-crossed-edges`, `vertex-style`) are not
    serialised and never enter the state, id, status, text, or save.
  - Size bound. `validate_params` accepts `n` up to
    `INT_MAX / 3`, where the generator allocates about 17 GB. The owner
    made the bound warning-only (decision 2b) and no parameter set is
    rejected. `Untangle.solveGame` carries
    `Solution not known for this puzzle`
  and `Internal error: aux_info badly formatted`, and the shell shows
  the message. The engine declares the one-field `Number of points`
  schema, and the app builds the dialog from it. The three preferences
  are applied through `Game.setPrefs` after `newUi` and after
  `Midend.fromSave`, with a forced redraw for the two drawing
  preferences. The status text is gated on `wantsStatusBar` (it is
  false). S9 is not needed
    because `current_key_label` is `NULL`. The owner made that bound
    warning-only (decision 2b): `Game.generationWarning` names every
    board above 100 points.
    `KotlinGames.handles(UNTANGLE)` is true (fifth enablement wave,
    2026-11-18), and `FifthWaveTileSizeInputTest` drags two vertices and
    presses the board corner at two tile sizes on the Pixel 4a 5G.

- `bridges` has no deviation and three enablement items:

  - `BridgesDescTest` is semantic. It compares the nine presets and the
    default with the oracle, the `atoi` decode quirks, the validation
    order and messages, the golden descriptions, every preset at nine
    seed strings, a numeric seed sweep, and the two retry fixtures. A
    two-invalid-field case pins the C message order: width, then area,
    then `maxb`, then `islands`, then `expansion`. The generated
    `AUXINFO` is compared with the oracle save, so the solver inside
    the retry gate and the island creation order are pinned. The whole
    save keeps the `PARAMS ...d0` / `CPARAMS ...dN` split. Generation
    runs under a two-minute timeout, because the retry loops are
    unbounded.
  - `BridgesGameTest` is semantic. It compares the fresh game, the move
    grammar, the solver from the initial board and from scratch, two
    hand-written stage-3 boards including the `maxv`/`maxh` carry-over,
    the two C error strings, undo, redo, completion and its event, a
    hint, the keyboard keys, and `requestKeys` on every preset. Real
    clicks and drag batches are compared in `BridgesInputParityTest`.
    The solver cases run under a two-minute timeout, so a solver that
    loops fails instead of hanging.
  - `BridgesSaveTest` is strict for the save text and semantic for the
    state. It reads all ten corpus saves, rewrites them byte for byte,
    and compares id, status, undo, redo, text, and `AUXINFO` with C. A
    Kotlin save for every preset loads in C, and an oracle save loads
    in Kotlin. The `PARAMS 7x7i30e10m2d0` / `CPARAMS 7x7i30e10m2d1`
    split is pinned. In-memory history plays a bridge, a no-line, and
    a lock, undoes to a live redo branch, and round-trips a `SOLVE`, a
    `RESTART`, and a hint move that starts with `S`. A hand-written
    `UI` record is ignored, and a corrupt description fails in both
    engines. The corpus was not regenerated.
  - `BridgesTraceTest` is strict. It compares the nine frozen traces
    and the oracle `traceNew` for every preset, the ten-role palette,
    the two whole-window updates, sized cold traces at tiles 48, 63,
    82, and 100, the 411x702 letterbox at tile 51 with origin (2,147),
    a warm press, a warm drag and release, the cursor island, the loop
    and impossible-clue warnings, and the hints preference. The loop
    warning is also compared on an asymmetric graph of two cycles
    joined by one bridge: only the cycle cells are warned. A solved
    board is traced too; every island must draw the mark background
    and every bridge cell a lock background. No trace contains a
    blitter or a flash frame.
  - `BridgesInputParityTest` is semantic. It compares the layout at
    tiles 48, 63, 82, and 100 and the 411x702 letterbox, then sends a
    real lock click, a bridge drag cycle, and a right-button no-line
    at all five views. Every real lock, bridge, or no-line changes the
    grid, adds one move and one state, and matches state, id, status,
    and the final frame with `pointsSized`. An empty click, an outside
    press, a letterbox click, a lone release, a drag inside one cell,
    a press on one island released on another, a drag back to the
    source, a drag over a marked edge, and a drag over a blocked
    crossing change neither the grid nor the count. The cursor keys,
    the select pair, the select-cancel lock quirk, `L`, `G`, and the
    digit jump are compared with the oracle. `SHFT`+arrow places a
    no-line and `CTRL`+arrow a bridge, because C sets
    `drag_is_noline = !control`.
  - `BridgesTest` is the engine test. It pins the parameters, the
    decode quirks, the validation messages and order, the description
    decoder, the row-major island order and the `maxv`/`maxh` start
    values, the retry attempt counts, the generated description and
    `AUXINFO`, the initial-board solve, the two solver messages, the
    move grammar, `L`, `N`, and `M`, the sticky completion, the
    changed-state event, the cursor and select quirks, the digit-jump
    ties, the text format, the hint diff, `requestKeys`,
    `currentKeyLabel`, `computeSize`, the palette, the first draw, the
    dirty cache, and the hints preference. It also pins the `findloop`
    neighbour order: an island returns left, right, up, down, and a
    line cell returns left then right or up then down. A stage-3 solve
    is pinned with a timeout, because a `maxv`/`maxh` reset per
    `solve_sub` pass changes the answer and a reset per stage-3 island
    can loop. Generation and solver tests run under a two-minute
    timeout.
  - The solver order is part of the description contract. `solve_sub`
    restarts stage 1 after any change, then runs stage 2, then stage 3;
    stage 3 lowers `maxv`/`maxh`, and the lowering must survive the
    restart and `map_clear`. The engine test `the solver maxima survive
    the restart` pins that: a reset per `solve_sub` pass adds an `N`
    token to the answer, and a reset per stage-3 island can loop until
    its timeout. The generator gates every board on the
    solver at `difficulty - 1` and again at `difficulty`, so a different
    stage order changes every Medium and Hard `DESC`. The island order
    is creation order in the generator and row-major after `new_game`;
    `game_state_diff` walks that order, so the generated `AUXINFO` is
    in creation order while a hint diff is in row-major order.
  - `Game.solve` keeps the initial board, so the port executes the
    `AUXINFO` answer there, then diffs against the current state.
    Running the answer on the current board would leave a player's
    wrong bridge in place.
  - Size bound and preferences. The engine declares the seven
  configuration items and the `show-hints` preference, and the app
  builds the dialog from that schema. The preference is applied after
  `newUi` and after `Midend.fromSave` and forces a redraw, because it is
  not serialised and not in the drawstate. `Bridges.solveGame` carries
  `Generated aux string is not a valid move (!).` and `Game does not
  have a (non-recursive) solution.`, and the shell shows the message.
  The accepted `46340x46340` area is warning-only (owner decision 2b):
  `Game.generationWarning` names every board above 225 cells, the
  largest preset, and no parameter set is rejected. The island
  placement retries the whole board with no bound, and the per-attempt
  island tries stop at the C `MAX_NEWISLAND_TRIES`, so the C and Kotlin
  loops hang alike. Generation already runs on the app's worker.
  `KotlinGames.handles(BRIDGES)` is true (sixth enablement wave,
  2026-11-18), and `SixthWaveTileSizeInputTest` clicks an empty cell,
  drags an island to the next one, and clicks an island for the lock at
  two tile sizes on the Pixel 4a 5G.

- `net` has two deviations (D-010 and D-011):

  - `NetDescTest` is semantic. It compares the eight presets and the
    default with the oracle, the nine golden descriptions, every preset
    at nine seed strings, a numeric sweep over `3x3`, `3x3w`, `4x4`,
    `5x5`, and `4x3w`, and the named `perturb`, `begin_generation`, and
    full-reshuffle retry fixtures. The decode quirks cover `5`, `+5x5`,
    `5x5x5`, `5x5w`, `5x5a`, `5x5b.5`, `5x5b0.5`, `5x5b1`,
    `5x5b1e-3`, `5x5b`, `5x5ba`, `5x5garbage`, and the rejected
    `-5x5`, `0x5`, `5x5b-0.5`, `5x5b0x10`, `5x5b2`, `1x1`, `0x0`,
    `2x2w`, and `-5x5b2`. The validation order, the full-only `2x2w`
    rule, the accepted `46340x46340` boundary without generation, the
    `%g` text for nine rates, and the generated `AUXINFO` are compared
    with the oracle. `PARAMS` is the short form and `CPARAMS` the full
    form, so `5x5b0.5a` writes `PARAMS:5x5` and `CPARAMS:5x5b0.5a`;
    they are equal for every preset. The test
    `a NaN barrier rate is rejected instead of aborting` pins D-010.
  - `NetGameTest` is semantic. It compares new games, text, status, the
    release-committed left click, the middle lock and unlock, the four
    drag quadrants with the `d0`, `dF`, `dA`, `dC` tie order, the
    start-quadrant drag with no move, the circular drag that emits one
    move per quarter turn, the keyboard `a`, `s`, `d`, `f`, and `j`,
    the origin and source keys on both wrapping and non-wrapping
    boards, the status text after a source move, and `requestKeys`. The
    solve is compared from the initial state and after a real move,
    because C solves `states[0]` and diffs the current state. Undo and
    redo are compared across a rotation, a lock, and a Jumble, and a
    new move after an undo truncates the redo branch. Two save-replay
    cases pin the status branches: a lock completes the solved board
    and a later rotation or lock keeps `completed` and the
    `COMPLETED!` banner (`COMPLETED! ` or `COMPLETED! Active: 1/25`
    against the oracle), and a solve after the broken board reaches
    the `Auto-solved. Active: 1/25` branch. A port that recomputed
    completion would fail both.
  - `NetSaveTest` is strict for the save text and semantic for the
    state. It reads all nine corpus saves, rewrites them byte for byte,
    and compares id, status, undo, redo, text, `AUXINFO`, and `UI` with
    C. A Kotlin save for every preset loads in C. The `UI` record
    round-trips in both directions, including `O 1, 1;C 3, 2junk` and
    an out-of-range pair that leaves the base UI at the centre. The
    `AUXINFO` round-trips, the fallback solver without `AUXINFO`
    matches the oracle from the initial board and after a move, and the
    `PARAMS:5x5` / `CPARAMS:5x5b0.5a` split is pinned. In-memory
    history covers a rotation, a lock, a Jumble at the oracle's NUL
    platform seed, an undone position with a live redo branch, a
    `SOLVE`, and a `RESTART`. A save without a `UI` record keeps the
    base UI and its Jumble continues from the platform seed. A crafted
    `AUXINFO` record that decodes to a non-hex byte is a defined,
    reachable solve refusal in Kotlin, and the oracle loads the same
    save (D-011).
  - `NetTraceTest` is strict. It compares the eight frozen traces and
    the oracle `traceNew` for every preset, the eight-role palette,
    the clipped edge tiles, a zero-size `rect 4 4 0 0 2`, the float
    endpoint box, sized cold traces at tiles 48, 63, 82, and 100, the
    412x702 letterbox at tile 80 with origin (1,146), the
    `unlocked-loops` preference, and the warm lock frame that emits no
    unchanged status. A warm drag and release is compared from the
    reloaded moved save, because the oracle's own frame after a
    rotation is the animation-start frame. The input's own frame
    draws exactly the one locked cell, while the cold first draw
    draws all 49 cells. No trace contains a blitter, a thick
    primitive, or a text operation.
  - `NetInputParityTest` is semantic. It compares the layout at tiles
    48, 63, 82, and 100 and the 412x702 letterbox, then sends real
    release-committed rotations and real middle locks at all five
    views, the four drag quadrants at the four tiles, a circular drag
    at tiles 32, 48, and 100, and the keyboard `a`, `s`, `d`, `f`, and
    `j` at the four tiles. It compares the cursor keys, the select
    pair, the locked-tile ring, and the origin and source keys with the
    oracle. State, id, status, and `UI` are compared for every pointer
    gesture; the final frame is compared with `pointsSized` for the
    release click, the middle lock, the drag quadrants, and the circular
    drag. A rotation frame is compared from the reloaded moved save.
    Every real rotation, lock, and Jumble
    changes the board and adds one move and one state; an inert press,
    input-band click, letterbox click, and modified non-cursor key
    change neither, with a real click as the positive control in the
    same test.
  - `NetTest` is the engine test. It pins the presets, the state
    decoder, wrapping re-derived from the barriers, the move grammar
    including a trailing `;`, the sticky completion before and after a
    move, the completion event, undo and redo, the status patterns,
    the solve with and without `AUXINFO`, every preset and a real
    move, the unsolvable board, the non-rotation and non-hex
    `AUXINFO` paths, `requestKeys`, `currentKeyLabel`, the `UI` record
    on the base object, `newUi`, the platform seed, the empty text
    form, the origin and source moves, the real-input invariants, the
    no-op Jumble, and the geometry and palette.
  - The solver order is part of the description contract. `net_solver`
    is both the uniqueness gate and the fallback solve, and its to-do
    list is a FIFO ring with a `deadendmax` array indexed by direction.
    The generator is gated on a solver result of `+1`, so a stack, a
    different re-add order, or a different direction iteration changes
    the `LOCKED` marks and every `DESC`. The nine goldens, the nine
    seed strings, the numeric sweep, and the retry fixtures pin it;
    `AUXINFO` is the unshuffled grid and pins the generator before the
    shuffle.
  - The initial-state solve is a seam rule. C `solve_game` reads
    `states[0]` for the answer and the current state for the diff;
    `Net.Shared` keeps the decoded initial board. A solve after real
    moves therefore matches C, and `NetGameTest` and `NetSaveTest` pin
    both shapes.
  - The base UI is preserved on a load. C creates the `UI` with
    `new_ui` and then decodes `org` and `cx`/`cy` on top; the record
    does not contain the cursor, `cur_visible`, the drag state, the
    frontend random state, or `unlocked-loops`. The Kotlin `decodeUi`
    copies the base object, so the platform seed and the cursor
    survive. A save without a `UI` record keeps the `newUi` centre and
    the platform seed.
  - D-010: `5x5bNaN` passes C `validate_params` and aborts on the
    `(int)` cast at `net.c:1533`; Kotlin returns
    `Barrier probability must be a number` before allocation or
    generation. The scope note records that the Kotlin NaN test runs
    before the 2-wide wrapping test, so only a `Params` value built in
    code sees the message order difference. D-011: the description
    `123456789` with the auxiliary text `000000000` trips
    `assert(tt == ft)` at `net.c:1862`, and a non-hex `AUXINFO` byte
    leaves the C tile uninitialised (`net.c:1814-1820`); Kotlin
    returns no move and
    `The auxiliary answer is not a rotation of the description` for a
    non-hex byte on a non-blank cell, and reads the byte as zero.
    Neither test sends the aborting input to the oracle. Valid
    parameters and auxiliary answers are unchanged.
  - Enablement debt. The engine declares the five-field configuration
  schema (Width, Height, `Walls wrap around`, `Barrier probability`,
  `Ensure unique solution`), and the app builds the dialog from it.
  `Game.setPrefs` applies the `unlocked-loops` boolean after `newUi`
  and after `Midend.fromSave` with a forced redraw, and the shell shows
  the solve message. The app generates
  off the UI thread, and
    D-028 rejects an area above 10,000 cells (100x100) at `newDesc` with
    `Width times height is too large for this build`, while validation
    and the load path keep the C acceptance and the retry loops stay the
    C loops. S9 is a tested `currentKeyLabel` helper with no Android call
    site. The app runs the Kotlin backend (seventh enablement wave,
    2026-11-18), covered by `KotlinGamesTest` and
    `SeventhWaveTileSizeInputTest`.

- `MinesDescTest` is semantic. It compares the four presets, the decode
  quirks (`9`, `+9x9`, `9x9n1.5`, `9x9X-1`, `9x9junk`, `300x6`), the
  nine validation messages and their order, the full and short
  encodings, `params + seed -> DESC` for the five goldens and a seed
  sweep, the first-click description, the layout-description grammar,
  and the exact bitmap length.
- `MinesTest` is the engine test. It pins the deferred layout, the
  generator, the perturbation counter through the `9x9n35` seed `18`
  layout (103 attempts, so the big-perturbation branch runs), and the
  dead `prevret` (assigning it does not produce a layout: every tested
  base case retries without end, so the finite attempt count is the
  guard), the shared mutable `Layout`,
  the move grammar including the whole-move rejection for an
  out-of-range `F`/`O`/`C` token, the chord results, death and win, the
  solve, the text form, the `UI` character parser, the timing formula,
  the `[m:ss] ` prefix, `TIME` with the C `atof` prefix (including
  `3.5junk`, `0x10`, `inf`, and `nan`), the `atoi` and move `%d`
  saturation, undo, redo, restart, the key labels, `requestKeys`, the
  palette, and the geometry.
- `MinesSaveTest` is strict for the save text and semantic for the
  state. It reads the five corpus saves, rewrites them byte for byte,
  and compares id, status, undo, redo, text, `UI`, and `TIME` with C.
  A Kotlin save for every preset and nine seeds loads in C. In-memory
  history covers an open, a flag, a correct chord, a death, a win, an
  undone position with a live redo branch, a `SOLVE`, a `RESTART`, and
  an empty `MOVE`, and every shape reloads in both engines. The test
  pins the first-click `DESC`/`PRIVDESC` pair, the `PARAMS:9x9n8` /
  `CPARAMS:9x9n10` split, the record order, and that a load with
  `PRIVDESC` rebuilds state 0 all-covered while a load without it
  opens the click. The `UI` record round-trips in both directions,
  including the glibc `%d` wrap (`D2147483648` is `-2147483648`), `C`,
  `D-3`, and `D3Cjunk`. `TIME` uses the C `%g` writer and the C
  `atof` reader: a hand-written `3.5junk` is 3.5, `0x1.8p1` is 3,
  `-0` keeps its sign, and `inf` and `nan` round-trip. A non-finite
  clock is hand-written only; the C status prefix casts it to `int`,
  which is undefined, so that one result is a Kotlin definition. An
  invalid `RESTART` record reports the C message
  `Save file contained an invalid restart move`.
- `MinesTraceTest` is strict. It compares the four frozen traces and
  `traceNew` for every preset, sized cold traces after a click at tiles
  48, 63, 82, and 100, the 411x702 letterbox at tile 43 with origin
  (4,149), the start cross after an undo and after a load, the dead,
  won, solved, and post-death-solve frames, a flag, a hover highlight,
  the cursor, the wrong-number tile, the 20-entry palette, and the warm
  frame that is exactly `start_draw`, `end_draw`. No trace contains a
  blitter, a thick primitive, or a text fallback.
- `MinesInputParityTest` is semantic. It compares the layout at six
  viewports, then sends real opens at tiles 20, 48, 63, 82, and 100, a
  four-pixel letterbox at tiles 48, 63, 82, and 100, and the 411x702
  letterbox with the origin subtracted on both sides. It sends a first
  click, a flag and unflag, a correct chord (`C1,0`, which adds one
  state but opens nothing because every covered neighbour is already
  flagged), a wrong-flag chord that opens the mine, the cursor walk and
  both select keys, the one-key undo and redo after a death, and
  `requestKeys` with and without the full-cursor hint, which C forces
  to `ANDROID_ARROWS_ONLY` (1) even for a game with no `request_keys`.
  State, id, status, `UI`, `TIME`, and the final frame with
  `pointsSized` are compared for every real pointer gesture. Every real
  open, flag, wrong-flag chord, and win changes the board and adds one
  move and one state; a right press outside the grid, a drag with no
  press, a release with no press, a middle press on a covered cell, a
  cursor key that cannot advance, a right-press chord with too few
  flags, a too-few-flags middle chord, a letterbox click, and a click
  after a death or a win change neither, each followed by a positive
  control. The button-priority rule is pinned in both orders: a right
  press while the left button is held is ignored, and a left press after
  a right press is accepted and writes the same flag-then-open save as
  C. The fabricated right release itself has no mines-visible effect, so
  the right-then-left case pins the ordering, not the internal call.
- Mines has C undefined behavior in four places, and the port defines
  each one without a `DEVIATIONS.md` entry because a valid game never
  reaches it: `atoi` and the move `%d` scan saturate, `decode_ui` keeps
  the low 32 bits as glibc does, a non-hex bitmap character reads as
  zero, and an impossible perturbation returns no change so the
  generator retries. Mines has no preferences, so the
  engine declares only the four-field configuration schema (Width,
  Height, Mines with the `%` rule, `Ensure solubility`), and
  `Midend.solveAttempt` carries the solve message. The size bound and
  the retry loop are warning-only (owner decision 2b):
  `Game.generationWarning` names every board above 256 cells, the
  largest preset, and no parameter set is rejected. The C `minegen`
  retries with no bound and unlocks the big perturbations after 100
  attempts, and the port keeps both rules, so the C and Kotlin loops
  hang alike. `KotlinGames.handles(MINES)` is true (sixth enablement
  wave, 2026-11-18), and `SixthWaveTileSizeInputTest` taps the border,
  opens a tile, and holds a press to flag a covered tile at two tile
  sizes on the Pixel 4a 5G.

- `TracksDescTest` is semantic. It compares the twelve presets, the
  short and full encodings, the `PARAMS ...dt` / `CPARAMS ...dN` split,
  the decode quirks, the validation order, the 4x4 downgrade, the two
  retry paths, and the run-length encoder against the goldens and fresh
  oracle games.
- `TracksGameTest` is semantic. It compares the solve payloads and the
  fallback to the captured initial board, the loose move grammar,
  completion and error marking, the text form for every preset and a
  moved state, undo and redo, the completion event, and `requestKeys`
  (arrow mode 3 without the full-cursor hint, 1 with it). The text form
  ends with the C newline after the final `B` line, and the tests pin
  that byte. D-012 is pinned here: the save with
  `MOVE    :7:S;TU0,0` aborts the C solver, while Kotlin returns
  `S;NR0,0;tU0,0;...` and completes the board.
- `TracksSaveTest` is strict for the save text and semantic for the
  state. It reads all thirteen corpus saves, rewrites them byte for
  byte, and compares id, status, undo, redo, and text with C. It also
  makes C load a Kotlin save for every preset, `4x4dt`, `4x4dh`, and
  `8x8dto`, and pins that no save has `AUXINFO` or `UI` and that
  `PARAMS` is always `...dt` while `CPARAMS` keeps the difficulty. An
  in-memory history covers a real click, a real drag, an error-marked
  board, an undone position with a live redo branch, a `SOLVE`, and a
  `RESTART`, and every shape reloads in both engines.
- `TracksTraceTest` is strict. It compares the twelve frozen traces and
  `traceNew` for every preset, the first-frame order, the cursor, the
  letterbox, the warm empty frame at tiles 33, 48, 63, 82, and 100, and
  the sized frames after a click. Tracks is the first on-screen consumer
  of the float thick line and `draw_thick_circle`: the test pins the 70
  `thick_line` and 6 `thick_circle` operations of `8x8dt`, the curved
  sleeper `thick_line 3750 63000 73000 59250 73000 5`, the two thick
  circles of a curved tile, and the fractional sleeper centers at tiles
  63 and 82, where `TILE/8` is 7.5 and 9.75.
- `TracksInputParityTest` is semantic. It compares the layout at five
  viewports, real square and edge clicks and real drags at tiles 48,
  63, 82, and 100, the 411x702 letterbox with origin (21,167), right and
  middle clicks and drags, and a click on an error-marked board. State,
  id, status, and the final frame with `pointsSized` are compared for
  every real gesture that records a trace, including a multi-item drag
  move; the four edge-band clicks compare the move, the id, and the
  history size only. Every real track change alters the board and adds
  one move; an inert letterbox, border, clue, diagonal, off-grid
  press/release, or modified-arrow event changes neither, each with a
  positive control. An off-grid drag after an off-grid press adds one
  empty move, as in C. The keyboard test covers the corner skip, the
  clamp, a clue centre, and both selects.
- `TracksTest` is the engine test. It pins the RNG state after
  generation, both retry paths, the shared clue block, the description
  grammar, the move grammar and flip strings, the solver attempts, the
  exact text form, the palette, the float geometry, the cursor, the key
  labels, and the three C undefined-behavior definitions: the off-grid
  press leaves the click origin at 0, an off-grid drag skips the
  off-grid cells and returns one empty move, and `atoi` and the move
  `%d` scan saturate. C `check_completion` indices are unreachable from
  a validated description. D-012 is the one deviation for this game.
  Enablement debt: the engine declares the four-field configuration
  schema (Width, Height, `:Easy:Tricky:Hard`, `Disallow consecutive 1
  clues`), the status text is gated, and `Midend.solveAttempt` carries
  the solve message. D-029 rejects an area above 484 cells (22x22) at
  `newDesc` with `Width times height is too large for this build`;
  validation and the load path keep the C acceptance. Tracks has no
  preferences and no `UI` record. S9 is a tested `currentKeyLabel`
  helper with no Android call site. The app runs the Kotlin backend
  (seventh enablement wave, 2026-11-18), covered by `KotlinGamesTest`
  and `SeventhWaveTileSizeInputTest`.

- `DominosaDescTest` is semantic. It compares the twelve presets and the
  default, the decode quirks (`6`, `6dh`, `6dth`, `6da`, `+6`, ` 6`,
  `6x`), the parameter validation order and its rejected messages, the
  `46339` accepted / `46340` rejected boundary, the `1dt`/`1db`/`1de`
  and `2db`/`2dh` difficulty caps, the `PARAMS ...db` / `CPARAMS ...dt`
  split for `3dt`, `1de`, `2dh`, and `6de`, and `params + seed -> DESC`
  for the thirteen goldens, nine seed strings, and a numeric sweep. The
  retry fixtures cover the Trivial and Basic allocators, the Hard
  `run_solver(DIFF_BASIC)` gate, and an Extreme board that needs the
  forcing chain. The auxiliary answer is compared with the oracle
  `SOLVE` for every preset. The description tests pin the RNG order:
  the laydomino shuffle and repairs, the allocator shuffles, the
  per-placement `random_upto(2)`, and the two solver gates.
- `DominosaGameTest` is semantic. It pins the identity and flags, the
  `requestKeys` payload (arrow mode 3, the labels `0..n`, unchanged by
  the full-cursor hint) against the oracle for every preset, the
  payloads with and without `AUXINFO`, and the 651-character `6db`
  solver string. That string pins the `E` and `D` payload and the
  vertical-before-horizontal placement order, but it cannot pin the
  internal compaction of `rule_out_placement`: the `E` pass lists
  placements by index, so the emitted order carries no information
  about the internal list order, and the measured `6db` payload is the
  same under a stable removal. The compaction is pinned directly in
  `DominosaTest`. This test also
  pins the move acceptance table, the text form, the completion
  event, undo/redo/restart, the key labels `Place`/`Remove`/`Line`, and
  D-013.
- `DominosaSaveTest` is strict for the save text and semantic for the
  state. It reads all thirteen corpus saves, rewrites them byte for
  byte, and compares id, status, undo, redo, and text with C. It makes
  C load a Kotlin save for every preset, `1dt`, `1da`, `2dh`, `2da`,
  and nine seeds, and pins the `PARAMS ...db` / `CPARAMS ...dt` split,
  the `AUXINFO` length, and the absence of `UI`, `TIME`, and
  `PRIVDESC`. In-memory history covers a real placement, a real
  removal, a real edge, an undone position with a live redo branch, a
  `SOLVE`, a `RESTART`, and an `S` move, and every shape reloads in
  both engines. A hand-written short `AUXINFO` is defined as `.`
  beyond the text, and a bad `DESC` and a bad `PARAMS` report the C
  generic messages.
- `DominosaTraceTest` is strict. It compares the twelve frozen traces
  and sized traces after a real click at tiles 48, 63, 82, and 100,
  the 411x702 letterbox, the warm empty frame after real input, the
  two-tile redraw after a domino and after an edge, the cursor at each
  parity and the useful second ring, and the two highlight colours. No
  trace contains a flash frame, a blitter, or a thick primitive.
- `DominosaInputParityTest` is semantic and ui. It compares the layout
  and the letterbox origin at five viewports, then sends real
  placements and removals, real edges, and drags at tiles 48, 63, 82,
  and 100, and the 411x702 letterbox with origin (1,178) with the
  origin subtracted on both sides. It pins the measured `208x176`
  boundary table, including `x = BORDER-1` and `y = BORDER-1`
  separately, the last cell and one pixel past it on each axis, the
  four letterbox bands, the same edge twice as a toggle, the refusal
  of an edge on a covered square, the keyboard cursor and both
  selects, the digit highlights with and without the keypad flag, a
  modified arrow, a third highlight, undo and redo with no history, a
  solve on a solved board, and a real click completion. State, id,
  status, and the final frame with `pointsSized` are compared for
  every real placement, removal, and edge. Every real placement and
  removal changes the board and adds one move; every inert click,
  drag, release, middle press, digit above `n`, letter, `TAB`,
  letterbox click, and out-of-board click changes neither, each with a
  positive control.
- `DominosaTest` is the engine test. It pins the decode and validation
  tables, the six description messages, the `atoi` saturation, the
  out-of-range difficulty encoded as `d?`, the short-`AUXINFO`
  definition, the text form and its empty
  result above the C `n < 1000` bound, the solver reach for the Hard
  and Extreme techniques, the swap-with-last compaction of
  `rule_out_placement` on the internal domino and square lists (the
  `3dt` fixture asserts the exact order, which a stable removal
  changes), the allocator retry counters, the move
  grammar and the edge echo pair, the completion counter, the cursor,
  the palette, and the geometry. D-013 is pinned here: the description
  `00222301130313132021` has no `3-3` placement; C aborts at
  `dominosa.c:1546` with `Assertion 'di == sc->dc' failed`, while
  Kotlin returns the measured `S;E...;D...` text. The other C
  undefined-behavior spots are port definitions without a deviation
  entry: `atoi` saturates, an unknown difficulty encodes as `d?`, a
  missing auxiliary character reads as `.`, the set mask is a signed
  64-bit `Long`, and `isdigit` is defined after the keypad mask.
  Enablement debt: `can_format_as_text_now` is params-dependent
  (`n < 1000`) and the seam has no such hook, so `textFormat` returns
  `""` for `n >= 1000`; the engine declares the two-field configuration
  schema (`Maximum number on dominoes`,
  `:Trivial:Basic:Hard:Extreme:Ambiguous`) with its twelve presets, and
  `Midend.solveAttempt` carries the solve message. D-030 rejects a
  maximum number above 9 at `newDesc` with `Maximum number is too large
  for this build`; the accepted `46339` order still validates and
  loads. Dominosa has no preferences and no `UI` record. S9 is a tested
  `currentKeyLabel` helper with no Android call site. The app runs the
  Kotlin backend (seventh enablement wave, 2026-11-18), covered by
  `KotlinGamesTest` and `SeventhWaveTileSizeInputTest`.

- `MapDescTest` is semantic. It compares the six presets and the
  default with the goldens and fresh oracle games, the short and full
  encodings, the decode quirks (`20x15` gives `n = 37`, `20x15n3.5`
  gives `n = 3`, `20x15n30d`, `20x15n30dz`, `-4x5`, and a saturating
  `atoi`), the validation order, the `PARAMS ...dn` / `CPARAMS ...de`
  split, and `params + seed -> DESC` over nine seed strings and a
  numeric sweep. The generator order is pinned by the encoded
  `RandomState` after `newDesc`, so the region-seed placement, the
  weighted extension, the four-colour recursion with its reverse trial
  order, the clue-removal loop, and the one region shuffle are all
  fixed. The retry fixtures pin the difficulty gate and the `tries`
  counter. The auxiliary answer is the full four-colouring, one `c:i`
  per region in order. Map has two seed streams: the game seed drives
  the generator and every `DESC` and `AUXINFO`, while the description
  text seeds a second, independent stream for the diagonal smoothing
  and the canonical edge and region points. The test pins that the
  second stream follows the text: two descriptions with the same edge
  list split the cells differently, and `newState` is deterministic.
- `MapGameTest` is semantic. It pins the two hand-written new-game
  shapes, the real left and right drag strings, completion, the sticky
  `completed` flag, undo and redo, the completion event, the two solve
  modes, the two C error strings, and `requestKeys` (arrow mode 3,
  `Regions`/`Colours`, no `needsArrows`, unchanged by the full-cursor
  hint). A solve with `AUXINFO` returns the stored answer; a solve
  without it starts from the captured initial clues, not the current
  board, and diffs against the current colouring. A hand-written move
  can overwrite a clue; the oracle loads that save without a solve,
  and the Kotlin solve includes the clue in the diff and restores it.
- `MapSaveTest` is strict for the save text and semantic for the
  state. It reads all seven corpus saves, rewrites them byte for byte,
  and compares id, status, undo, redo, text, text format, and the raw
  `AUXINFO` text with C. It pins the record order, the absence of
  `UI`, `TIME`, and `PRIVDESC`, the `PARAMS ...dn` / `CPARAMS ...de`
  split for the 20x15 Easy save, the same split for the 30x25 Hard
  save, and that `default.sgtp` equals `20x15n30dn.sgtp`. A Kotlin
  save for every preset and nine seeds loads in C. In-memory history
  covers a recolour, a pencil mark, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART`, and every shape reloads in both
  engines. A save without `AUXINFO` loads and solves from the clues,
  a hand-written `AUXINFO` round-trips, and an empty seed stays a
  zero-length `SEED` record.
- `MapTraceTest` is strict. It compares the six frozen traces and
  `traceNew` for every preset, the first frame (the window clear, the
  grid background, 300 `clip`/`unclip` pairs, a diagonal triangle, and
  one `23x23` blitter created before `start_draw` and never freed), the
  sized fresh frames, the damaged-cell warm frames with the
  `blitter_load`/`blitter_save` pair, and the letterbox. The label
  preferences `show-labels=regions` and `show-labels=colours` are
  compared with the oracle text operations, including the pencil
  letters. The stipple preference is compared at the `T/7` and `T/4`
  radii. The flash preference is stored on the live UI but draws
  nothing: the oracle's `allow_flash` is 0, so all three values are
  compared as the same frame. The test also pins the error diamond and
  the cursor epsilon.
- `MapInputParityTest` is semantic and ui. It compares the layout at
  six viewports, real recolours and right-button pencil drags at tiles
  48, 63, 82, and 100 and at the 411x702 letterbox, and the four
  quadrants of a diagonally split cell. State, id, status, the history
  size, and the final frame with `pointsSized` are compared for every
  real gesture. Every real recolour changes the board and the status
  and adds one move and one state; a pencil drag changes the board and
  the count but not the status. Border, same-region, lone-release,
  drag-into-border, and letterbox clicks change neither, at every tile
  and in the letterbox, each followed by a positive control. The test
  also pins `requestKeys`, the two requested keys as UI-only updates,
  the cursor move, and that unknown keys and modified arrows are
  unused.
- `MapTest` is the engine test. It pins the decode and validation
  tables, the `atoi` saturation, the plain `d` for an out-of-range
  difficulty, the description messages, the retry counters, the
  deterministic geometry, the move grammar, the completion and
  `changedState` rules, the two solve errors, the initial-clue solve,
  the key labels, the UI defaults, the palette, the size search, the
  drawstate and blitter lifecycle, the colour labels, the stipple
  radius, and the error marker. D-014 is pinned here: the C validation
  boundary `32767x32767` accepted and `32768x32768` rejected, the
  `2^20` area and `4096` region limits on `newDesc`, `newState`, and
  `validateDesc`, and the two fixed messages. D-015 is pinned here
  too: the saturating move index, the plain `d`, the clue overwrite
  restored by the solve diff, and the skipped missing canonical point.
  The owner review notes are in `docs/DEVIATIONS.md`.
- Map enablement debt. The engine declares the four-field configuration
  schema (Width, Height, Regions, `:Easy:Normal:Hard:Unreasonable`),
  and the app builds the dialog from it. `flash-type`, `show-labels`,
  and `stipple-style` reach the live `Ui` through `Game.setPrefs` after
  `newUi` and after `Midend.fromSave` with a forced redraw (map has no
  `UI` record), and `Map.solveGame` carries `Puzzle is inconsistent`
  and `Unable to find a unique solution for this puzzle`. The app generates off the UI
  thread. D-014 caps generation at 100 regions and keeps the 2^20 area
  and 4096-region limits on the load path; D-015 defines the C
  undefined-behavior paths. The app runs the Kotlin backend (seventh
  enablement wave, 2026-11-18), covered by `KotlinGamesTest` and
  `SeventhWaveTileSizeInputTest`. S9 is a tested `currentKeyLabel`
  helper with no Android call site.

- `KeenDescTest` is semantic. It compares the ten presets and the
  default with the oracle, the eleven golden descriptions at the shared
  seed, nine seed strings per preset, the decode quirks (`6m`, `6dnxyz`,
  `6dm`, `6d`, `6dq`, `2`, `10`, and `+5`), the validation order and
  messages, and the `3x3` difficulty downgrade. It also pins
  the `PARAMS ...dn` / `CPARAMS ...dem` split, the new-game short state
  and the load full state, the retry fixture ids (the engine test pins
  the attempt count), and the `AUXINFO` answer. The generator draws one
  Latin square, one order shuffle, one `random_upto(4)` per
  domino-capable singleton, a second order shuffle, and then the two
  solver gates; the clue choice draws nothing. A missing or reordered
  draw changes every id, so the goldens and sweeps are the guard.
- `KeenGameTest` is semantic and strict where a frame is compared. It
  compares every preset's `SOLVE`, key sequences including the Android
  pencil branches and modified keys, undo and redo after a digit and a
  solve, completion by real clicks and digits, `requestKeys` in both
  full-cursor modes, and the absent text form. A dedicated regression
  pins the `Midend` state-params rule: a new `5dem` game gets the
  short-form value (no flag, operator drawn), and a load of the same
  save gets the full-form value (flag on, no operator). A D-016
  regression loads a save whose clue value carries the SUB operation
  bit and aborts the C solve, and pins the defined Kotlin refusal.
- `KeenSaveTest` is strict for the save text and semantic for the
  state. It reads all eleven corpus saves, rewrites them byte for byte,
  and compares id, status, undo, redo, text, `textFormat`, and
  `AUXINFO` with C. A Kotlin save for every preset and nine seeds loads
  in C with the same id and text. In-memory history covers number entry,
  a pencil mark, `M`, an undone position with a live redo branch, a
  `SOLVE`, and a `RESTART`. No save has a `UI`, `TIME`, `PRIVDESC`, or
  `HEXSEED` record, and a save without `AUXINFO` solves from the clues.
- `KeenTraceTest` is strict. It compares the ten frozen traces, the
  loaded multiplication-only saves, sized loaded-save frames at tiles
  48, 63, 82, and 100, the same sizes for a fresh game, the 411x702
  letterbox, the cursor and pencil triangle, a digit and a pencil mark,
  both error colours, a completed board, and the warm path with its
  block polygons. Every Keen frame is blitter-free and carries one
  `thick_poly` per block.
- `KeenInputParityTest` is semantic and ui. It compares the layout at
  six viewports, real presses and digits at tiles 48, 63, 82, and 100,
  a pencil mark and its toggle, `M` at every tile and at the letterbox,
  the border bands and cell edges, the cursor and select keys, and the
  inert drag, release, middle press, hidden digit, and pencil-on-filled
  cases. State, id, status, undo, redo, and the final frame with
  `pointsSized` are compared. Every real number change fills the cell
  and adds one move; `M` changes every empty pencil bitmap and adds one
  move with status 0; an inert event changes neither the board nor the
  count.
- `KeenTest` is the engine test. It pins the decode and validation
  tables, the plain `d` for an out-of-range difficulty, the saturating
  move parse, the block-structure bounds, the move grammar and the
  sticky completion, `changedState`, the two solve errors, the shared
  `Shared` instance of a copied state, the five solver slots
  `0, 2, 3, 3, 4`, the D-016 clue assert, the palette and size search,
  `currentKeyLabel`, and the UI default. The engine declares the
  configuration schema, `pencil-keep-highlight` is applied after `newUi`
  and `fromSave`, the status is gated on `wantsStatusBar`, and the two
  solve messages are carried. No generation guard is needed:
  validation caps the grid at 9x9, which measures 0.25-0.29 s on the
  host. The app runs the Kotlin backend (seventh enablement wave,
  2026-11-18), covered by `KotlinGamesTest` and
  `SeventhWaveTileSizeInputTest`. S9 is a tested `currentKeyLabel`
  helper with no Android call site.

- `TowersDescTest` is semantic. It compares the seven presets and the
  default with the oracle, the eight golden descriptions at the shared
  seed (the empty params string plus the presets), nine seed strings
  and a numeric sweep per preset, the decode quirks (`+5`, `05`,
  ` 5`, `5de `, `5dhx`, `5d`, `5dq`), the validation order and
  messages, the description grammar and every rejection, the retry
  fixtures, the `AUXINFO` answer, and that two calls leave the same
  random stream. The three-by-three clamp is pinned by id *and* by
  `CPARAMS` for `3dh`, `3dx`, and `3du`.
- `TowersGameTest` is semantic and strict where a frame is compared.
  It compares every preset's `SOLVE`, the generated difficulty, key
  sequences including the Android pencil branches and modified keys,
  real clicks at tiles 48, 63, 82, and 100, the 411x702 letterbox, all
  four clue bands, `M` and `D` as undoable history, undo and redo
  after a digit, a `D` move, and a solve, completion by real keys with
  one event, the text form in every shape, and `requestKeys`.
- `TowersSaveTest` is strict for the save text and semantic for the
  state. It reads all eight corpus saves, rewrites them byte for byte,
  and compares id, status, undo, redo, text, `textFormat`, and the raw
  `AUXINFO` with C. A Kotlin save for every preset and nine seeds loads
  in C with the same id, text, status, and `AUXINFO`. In-memory
  history covers number entry, a pencil mark, `M`, a `D` move, an
  undone position with a live redo branch, a `SOLVE`, and a
  `RESTART`. `PARAMS` is the bare width decoded over the defaults
  (`5de`) and `CPARAMS` keeps the request (`5dh`). No save has a `UI`,
  `TIME`, `PRIVDESC`, or `HEXSEED` record, and a save without
  `AUXINFO` solves from the clues.
- `TowersTraceTest` is strict. It compares the seven frozen traces,
  loaded-save and fresh-game frames at tiles 48, 63, 82, and 100, the
  411x702 letterbox, the warm empty frames after a click, a pencil
  toggle, a digit, and `M`, a cold frame with the two tower faces and
  the digit, `M` and `D` marks in one frame, the `COL_ERROR` digits, a
  completed board with no flash, the 2D `appearance` frame, a cursor
  frame where `pencil-keep-highlight=false` is the C no-op, and the
  mouse frame where the same preference hides the pencil highlight.
  Every frame is blitter-free.
- `TowersInputParityTest` is semantic and ui. It compares the layout at
  five viewports and tiles 48, 63, 82, and 100, real presses, pencil
  toggles, and real number entries at those four tiles and at the
  411x702 letterbox (tile 56, origin (2,148)), the 3D hit test on a
  board with tall towers, including a click in the lower-right triangle
  and one pixel outside it, all four clue bands and an absent clue, the
  cursor, a duplicate-digit error frame, and the border, drag,
  release, middle press, hidden-digit, and pencil-on-filled cases.
  State, id, status, undo, redo, and the final frame with
  `pointsSized` are compared. Every real number change fills or clears
  the cell and adds one move; an inert event changes neither. It also
  compares `requestKeys` with the oracle: arrow mode 2, the labels
  `1`..`w`, `Clear`, and `Mark`, and `needsArrows` false, with and
  without the full-cursor hint.
- `TowersTest` is the engine test. It pins the parameter and decode
  quirks, the plain `d` for an out-of-range difficulty, the saturating
  `atoi` and `%d` parses, the validation order, the description
  messages, the eight goldens, the retry attempt counts, the
  three-by-three clamp, the generator determinism, the `AUXINFO`
  solution, the five solver slots, the shared `Shared` instance of a
  copied state, the Android highlight branches, the 3D hit test with
  its last-candidate rule, the cursor, the move grammar and sticky
  completion, `checkErrors`, `changedState`, undo and redo across a
  solve and a restart, the two solve errors, the text form, the key
  labels, the UI defaults, the palette, the size search, and the
  drawstate arrays. The solver keeps the literal flat cube indices:
  `cube[cell*w + n-1]` is the transposed cell of a row-major position,
  while placement uses `(pos%w, pos/w)`. A tidied index still solves
  and still generates valid puzzles, so only the description and solve
  goldens catch it. C undefined behavior is defined and pinned without
  a deviation entry: an out-of-range encode writes a plain `d`, the
  `atoi` and `%d` parses saturate, and an invalid description is
  rejected instead of aborting `new_game`.
- Towers enablement debt. The engine declares the two-field
  configuration schema (`Grid size` and `Difficulty` with
  `":Easy:Hard:Extreme:Unreasonable"`), and the app builds the dialog
  from it. `appearance` and `pencil-keep-highlight` reach the live `Ui`
  after `newUi` and after `Midend.fromSave` (Towers has no `UI` record)
  with a forced redraw for `appearance`. The status text is gated on
  `wantsStatusBar`, and `Towers.solveGame` carries the two solve
  messages. The app generates off
  the UI thread. `KotlinGames.gameFor` knows the Kotlin game and
  `KotlinGames.handles` selects it for the app (fourth enablement wave,
  2026-11-18). S9 is a tested `currentKeyLabel` helper with no
  Android call site. `KotlinGamesTest` pins the enabled set and the
  generated save; `FourthWaveTileSizeInputTest` selects a cell and
  sends a real digit at two tile sizes on the Pixel 4a 5G.

- `UnequalDescTest` is semantic. It compares the twelve presets and the
  default with the oracle, the thirteen golden descriptions at the
  shared seed, nine seed strings and a numeric sweep per preset, the
  decode quirks (`4`, `4a`, `4dxx`, `4q`, `+4de`, `-4de`, ` 4de`,
  `04de`, `4ade`), the validation order and the three messages, the
  description grammar including the run-length form and every
  rejection, the retry fixtures, the `AUXINFO` answer, and the
  generator state after the call. The generator draws one Latin square,
  one shuffle of the number codes, one shuffle of the four inequality
  codes, and then the solver-gated assembly and strip; the scratch list
  is built once and re-shuffled across retries, so a retry permutes the
  previous permutation. The clue choice reads the transposed cube
  slice, `hints[loc*order + j]`, because `loc` is the row-major cell
  index while `hints` is the Latin cube layout. Only the descriptions
  and the sweeps catch a tidied read, because a "correct" read still
  generates valid puzzles.
- `UnequalGameTest` is semantic and strict where a frame is compared.
  It compares the new game and its two array layouts, every preset's
  `SOLVE`, the move grammar `R`/`P`/`F`/`M`/`H`/`S` and its `sscanf`
  quirks, real clicks and keys at tiles 48, 63, 82, and 100, the four
  clue bands, the 411x702 letterbox, the cursor and its clamp, the
  modified-arrow clue cycle, completion and the sticky status,
  `changedState`, undo and redo, the hint on a duplicate board, the
  text form in both modes, the palette, the size table, `requestKeys`,
  `currentKeyLabel`, and `cursorLocation`. A malformed `AUXINFO` fails
  the solve and adds no history in both implementations. The Adjacent
  validator skips the last row and the last column as sources, as
  `unequal.c` does, so a wrong pair there is accepted by the Trivial
  gate that `difficulty` reads, while a wrong interior pair is
  rejected. The four fixtures are one Latin square with one unflagged
  adjacent pair. A full solve runs at the Recursive level, where the
  Latin set elimination catches the emptied candidate first, so C
  rejects that board too.
- `UnequalSaveTest` is strict for the save text and semantic for the
  state. It reads all thirteen corpus saves, rewrites them byte for
  byte, and compares id, status, undo, redo, text, `textFormat`, and
  the raw `AUXINFO` with C. A Kotlin save for every preset and nine
  seeds loads in C with the same id, text, status, and `AUXINFO`.
  `PARAMS` is the short form decoded over the defaults and `CPARAMS`
  keeps the request: `5dk` writes `5de`, `5adk` writes `5ade`, and the
  short id keeps `a` but drops `d`. In-memory history covers number
  entry, a pencil mark, a real clue band cycle (spend and clear), a
  spent `F` toggle, `M`, `H`, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART`. No save has a `UI`, `TIME`,
  `PRIVDESC`, or `HEXSEED` record, a hand-written `AUXINFO` round-trips
  and the solve returns it, and a corrupt move is rejected on load.
- `UnequalTraceTest` is strict. It compares the twelve frozen traces,
  sized loaded-save frames at tiles 48, 63, 82, and 100, the 411x702
  letterbox, the warm empty frames after a click, a digit, `M`, `H`,
  and `F`, a cold pencil-highlight frame, the spent and error clue
  colours, a duplicate-digit frame, a completed board with no flash,
  and the hint marks with their computed size. Every frame is
  blitter-free and has no clip.
- `UnequalInputParityTest` is semantic and ui. It compares the layout
  at five viewports and four exact tiles, real presses, pencil toggles,
  number entries, and the clue band cycle at tiles 48, 63, 82, and 100
  and at the 411x702 letterbox (tile 51 for `5de`, origin (3,148)), the
  keyboard in both modes, the cursor and its clamp, a duplicate-digit
  error frame and its positive control, and the border, gap, drag,
  release, middle, hidden-digit, and pencil-on-filled cases. State, id,
  status, undo, redo, and the final frame with `pointsSized` are
  compared. Every real number or clue change alters the board and adds
  one move; an inert event changes neither. `requestKeys` is compared
  on every preset and with the full-cursor hint: arrow mode 2, the
  labels `1`..`order`, `Clear`, `Mark`, and `Mark/Hints`, and
  `needsArrows` false for every key.
- `UnequalTest` is the engine test. It pins the decode and validation
  tables, the plain `d` for an out-of-range difficulty, the saturating
  `atoi` and `%d` parses, the two array layouts, the move grammar and
  the sticky completion, `changedState`, the five solver slots, the
  transposed clue slice through the retry attempt counts, the 50-try
  fallback, the partial cube of a failed `latin_solver_alloc` on a
  duplicate board, the order-10 key off-by-one, the `h`/`m` digit
  shadowing, `currentKeyLabel`, `cursorLocation`, the UI default, the
  palette, the size search, and the drawstate arrays.
- The `H` hint needs the partial cube. `latin_solver_alloc` fills the
  cube and returns false as soon as a digit cannot be placed
  (`latin.c:566-597`), and `solver_state` copies the cube into
  `state->hints` even on failure (`unequal.c:898-913`). `LatinSolver`
  now exposes the partially built solver, `alloc` keeps its old
  contract, and `Unequal.solverState` copies the cube in both cases.
  A duplicate digit followed by `H` therefore draws the C frame.
- `pencil-keep-highlight` is the only Unequal preference. It is read in
  `interpret_move` and changes the same-digit no-op and the post-entry
  highlight cancel. It is not serialised, because `encode_ui` and
  `decode_ui` are `NULL`, so a load keeps the default true. The
  keyboard path sets `hcursor`, so the preference cannot change it and
  the oracle agrees for both values. The oracle `pointsSized` and
  `traceWarm` operations have no `prefs` field, so the pointer path is
  pinned engine-side and by the Kotlin `warmFrame` helper.
- Unequal C undefined behavior is defined by the port and pinned
  without a deviation entry: `encode_params` writes a plain `d` for a
  difficulty outside `0..4` instead of reading past
  `unequal_diffchars`, the `atoi` and move `%d` parses saturate, and
  `new_game` throws on an invalid description instead of asserting.
  `interpret_move` reads `ds->order`, which is safe by construction in
  the seam, and `game_changed_state` indexes `nums[hx,hy]` under the
  clamped-cursor invariant, so the port keeps the invariant and adds no
  guard. The `gg_best_clue` and `game_strip` asserts are unreachable
  from a generated board. No Unequal abort is reachable beyond these
  definitions.
- Unequal enablement debt. The engine declares the three-field
  configuration schema (`Mode` with `":Unequal:Adjacent"`, `Size
  (s*s)`, and `Difficulty` with
  `":Trivial:Easy:Tricky:Extreme:Recursive"`), and the app builds the
  dialog from it. `pencil-keep-highlight` reaches the live `Ui` after
  `newUi` and after `Midend.fromSave` (there is no `UI` record). The
  status text is gated on `wantsStatusBar` (it is false), and
  `Midend.solveAttempt` carries `Solve operation failed` and `Solve
  unavailable (already solved?)`. The app generates off the UI thread.
  `KotlinGames.gameFor` knows the Kotlin game and `KotlinGames.handles`
  selects it for the app (fourth enablement wave, 2026-11-18). S9 is a
  tested `currentKeyLabel` helper with no Android call site.
  `KotlinGamesTest` pins the enabled set and the generated save;
  `FourthWaveTileSizeInputTest` selects a cell and sends a real digit
  at two tile sizes on the Pixel 4a 5G.

- Singles enablement debt. The engine declares the three-field
  configuration schema (`Width`, `Height`, `Difficulty` with
  `":Easy:Tricky"`), and the app builds the dialog from it.
  `show-black-nums` reaches the live `Ui` after `newUi` and after
  `Midend.fromSave` (there is no `UI` record) with a forced redraw. The
  status text is gated on `wantsStatusBar` (it is false), and
  `Midend.solveAttempt` carries `Unable to solve puzzle.`. The app generates
  off the UI thread. D-031 rejects an area above 400 cells (20x20) at
  `newDesc` with `Width times height is too large for this build`, and
  the generator retry loop stays the C loop. S9 is a tested
  `currentKeyLabel` helper with no Android call site. The app runs the
  Kotlin backend (seventh enablement wave, 2026-11-18), covered by
  `KotlinGamesTest` and `SeventhWaveTileSizeInputTest`.

- `SinglesDescTest` is semantic. It compares the ten presets and the
  default with the oracle, the eleven golden descriptions at the shared
  seed, nine seed strings and a numeric sweep per preset, the decode
  quirks (`5`, `5dk`, `05x5dk`, `+5x5dk`, `" 5x5dk"`, `5x5dK`, `5d`,
  `5x`), the validation order and the three messages, the description
  grammar, and the retry fixtures `2x20dk`, `2x30dk`, and `3x30dk`. It
  also compares the largest accepted side (`61x2de` seed `3`) with the
  oracle, loads a hand-written `62x2de` save whose description holds
  `Z`, and pins that a pasted `5x5:DESC` id starts from the defaults,
  so its later `CPARAMS` is `5x5de` where C keeps the previous
  difficulty. The generator draws one Latin rectangle of order
  `max(w, h)`, one shuffle of the `w*h` cells, the black placement in
  the shuffled order, and one `o`-element `best_black_col` shuffle per
  black cell in increasing cell index. A failed solver gate reshuffles
  every black cell only; the twenty-first failure returns to the Latin
  draw. The solver order is `solve_sneaky` in the gate only, then
  `singlesep`, `doubles`, `corners`, `offsetpair` at Tricky and above,
  then the `allblackbutone`/`removesplits` loop with its `> 0` tests.
  A wrong draw order still produces a valid-looking board, so only the
  descriptions and the sweeps catch it.
- `SinglesGameTest` is semantic and strict where a frame is compared.
  It compares the new game, the text form, the move grammar, the real
  black and circle cycles at tiles 48, 63, 82, and 100, the cursor and
  its wrap at all four edges, both select keys and Enter/Space, the
  inert middle press, drag, and release, undo and redo with a live redo
  branch, completion and the sticky status, the three error rules,
  every preset's `SOLVE`, the fallback to the initial state, and
  `changedState`. Every real black or circle step alters the board and
  adds one move with status 0, and the final frame at each tile is
  compared with `pointsSized`.
- `SinglesSaveTest` is strict for the save text and semantic for the
  state. It reads all eleven corpus saves (`default` plus the ten
  presets), rewrites them byte for byte, and compares id, status,
  `canUndo`, `canRedo`, text, and `textFormat` with C. A Kotlin save
  for every preset and three seeds loads in C with the same id, status,
  and text. The short id drops the difficulty, and `2x2dk` writes
  `PARAMS 2x2de` and `CPARAMS 2x2dk`. In-memory history covers a black
  and a circle click, an outside click that toggles `show-black-nums`
  and leaves the save unchanged, an undone position with a live redo
  branch, a `SOLVE`, and a `RESTART`, and every shape reloads in both
  engines. No save has an `AUXINFO`, `PRIVDESC`, `UI`, or `TIME`
  record, and an empty and a hex seed keep the shared container
  behavior.
- `SinglesTraceTest` is strict. It compares the ten frozen traces and
  `traceNew` for every preset, sized cold frames at tiles 48, 63, 82,
  and 100, the 411x702 letterbox, the cursor ring at the wrap of all
  four edges, the `COL_ERROR` marks, the `COL_BLACKNUM` preference
  frame, the two-digit text size, the warm empty frame after a real
  input, the changed frame after a real black and circle click at tile
  48 and in the letterbox, and the engine-only dirty frame after an
  undo. No frame has a blitter, a clip, a thick primitive, flash, or a
  status bar.
- `SinglesInputParityTest` is semantic and ui. It compares the layout
  at five viewports and tiles 48, 63, 82, and 100, the black and circle
  cycles at those tiles and at the 411x702 letterbox (tile 68, origin
  (1,147)), the half-tile border, the last cell and one pixel past it,
  the letterbox preference toggle with an inside positive control, a
  lone release, a lone drag, and an overlapping right press. State, id,
  status, undo, redo, and the final frame with `pointsSized` are
  compared. Every real black or circle change alters the board and adds
  one move; an inert event changes neither. `requestKeys` is compared
  on every preset with and without the full-cursor hint: arrow mode 3
  with no labels, and mode 1 (`ARROWS_ONLY`) with the same empty list
  when the hint is set, as the C midend forces for a NULL
  `request_keys`.
- `KotlinGamesTest` pins that `SINGLES` maps to `Singles` in
  `KotlinGames.gameFor` and that `KotlinGames.handles(SINGLES)` is
  true, and `HelpTopicTest` pins the `singles` help topic.
- `SinglesTest` is the engine test. It pins the parameter and decode
  quirks, the plain `d` for an out-of-range difficulty, the saturating
  `atoi` in `decodeParams` and the saturating `%d` in `executeMove`,
  the validation order, the description
  messages, the `n2c`/`c2n` alphabet and the 62 hole, the move grammar
  and the empty move, the sticky completion, the three error rules, the
  solver diff, the `best_black_col` fallback, the two retry counters,
  the tiny-board downgrade, D-017, `currentKeyLabel`, `cursorLocation`,
  the UI default, the palette, `computeSize`, and the drawstate array.
- `show-black-nums` is the only Singles preference. C keeps it in the
  UI, where it changes the number drawn on a black cell and nothing
  else; it is not in `State`, the id, the save, or `DrawState.flags`.
  The port exposes `Singles.Ui.showBlackNums` and reads it in
  `redraw`, and `SinglesTraceTest` compares the frame with the oracle's
  `trace` `prefs` field and pins that the save is unchanged. Because
  `encode_ui` and `decode_ui` are `NULL`, a load creates a fresh UI;
  the midend applies the preference after `newUi` and after
  `Midend.fromSave`.
- D-017. C accepts a side of 62, generates a description whose digit
  62 is `[`, and aborts at `singles.c:1452`. `62x2de` seed `5` aborts
  after 40.9 s (SIGABRT, exit 134), seed `19` aborts, and `2x62de` seed
  `20` aborts; no `62` shape generated a description. The Kotlin
  `validateParams` still accepts `2..62`, and only `newDesc` rejects a
  side of 62 with `Puzzle is too large` before any draw. Every side
  `2..61`, every preset, seed, and save text is unchanged, and
  `SinglesDescTest` compares the largest accepted side (`61x2de` seed
  `3`) with the oracle. The engine test pins seeds `1` and `5` and the
  untouched generator state; the parity test pins seeds `1`, `5`, and
  `19` and the same message through `Midend.newGame`.

- The `Midend` state-params rule is cross-cutting. `newGame` and
  `fromGameId` build state 0 from the short (long-term) params, as
  `midend_new_game` does, and `fromSave` builds it from `CPARAMS`.
  `KeenGameTest` pins the Keen operator quirk, `KeenTraceTest` pins both
  frames, and `MinesSaveTest` pins the mine count: a new `9x9n10` game
  has `n = 8` from the short params and a load has `n = 10` from
  `CPARAMS`. Every other game's state and frozen trace is part of the
  same suite, so a regression in the shared rule fails there.

- `EmptySeedSaveTest` is strict for the save text. It opens fifteen and
  flood with an empty seed and asserts that Kotlin writes a
  zero-length `SEED` record instead of `HEXSEED`, that the text equals
  the C text, that the parser and writer round-trip it, and that C
  loads the Kotlin save.
- `OracleTraceSequenceTest` checks the `seq` field of a `trace`
  request. It replays one key on an `inertia` game that uses blitters
  and asserts that the trace starts with `blitter_new`, has no stale
  `blitter_free` before the first `start_draw`, and really differs from
  the trace of the same state without a sequence.
- `KotlinGamesTest` pins the app-side selection and checks that the
  generated save is the frozen oracle save.
- `GamePlayParameterizedTest` asserts that the running engine is
  `KotlinGameEngine` exactly when `KotlinGames.handles` reports the
  backend, so one case checks each of the 26 enabled backends and each
  of the 14 backends that still run on C. The test keeps no list of its
  own. It then completes the 2x2 game through the real
  shell on the emulator. The fifteen cases cover a hardware key, a tap,
  and a swipe, so tap and drag coordinate mapping is covered, not only
  key input. Parameter names include the index, so two cases with the
  same game ID cannot silently replace each other.
- `GamePlaySaveResumeTest` plays a move in fifteen through the real
  shell, closes the activity so `onPause` saves the game, adds the
  trailing spaces that Android gives to a value ending in a newline,
  then launches without an intent. It checks that the Kotlin backend
  resumes with the same move count and accepts another move. This is
  the full Activity to SharedPreferences to parser path, not only the
  `SavedGameText` helper.
- `GamePlayLandscapeInputTest` rotates fifteen to landscape, waits for
  the letterboxed board to be laid out, then taps the middle cell of the
  bottom row. The origin is larger than a tile in landscape, so a missing
  or doubled origin subtraction moves the tap outside the board.
- `SaveParityTest` is strict. It parses and rewrites every file in
  `testdata/saves/`, then makes the oracle load the original and the
  Kotlin text and compare the game id. It also recomputes the preset
  set, so a missing corpus file or a silently skipped preset fails.
  The only exclusion is `pegs 5x5random`, which upstream cannot reload;
  the test asserts that it is still unreadable.
- `tools/gen-saves.sh` regenerates the corpus. Review the diff; do not
  regenerate it to make a red test green.
- `BoardRecorderTest` is a JVM test over all 288 oracle traces. It feeds
  each trace through `BoardRecorder`, the same `ViewCallbacks` seam that
  the C engine uses, and compares the resulting `DrawOp` list. It
  proves that the renderer model represents every primitive of every
  game and preset without a device. The Compose half is checked by the
  instrumentation suite and visually on fifteen, galaxies, and map.

Device run on the Pixel 4a 5G (physical), 2026-11-18, Android 16 / API 36:

- `:app:connectedDebugAndroidTest` ran 67 cases: 61 passed, 0 failed,
  6 skipped. Three skips are the fastlane screenshot producers, which
  need the `device_type` argument. The other three are the chooser
  goldens: the phone captures 1080x2340 at 420 dpi, not the 1080x2400
  reference in `tools/perf/baseline.md`.
- At the same HEAD (`ac01604e`) the JVM suites reported unit 106 from
  `:app:testDebugUnitTest`, engine 1123 from `:engine:jvmTest`, and
  parity 1885 from `:parity:test` against the host oracle.
  `:app:lintDebug` reported 0 errors and 547 warnings.
- This run proves these definition-of-done rows: a real tap and swipe
  (`GamePlayParameterizedTest`, `FifteenTileSizeInputTest`,
  `GamePlayLandscapeInputTest`), input at a non-default tile size
  (`FifteenTileSizeInputTest`), the letterbox origin
  (`GamePlayLandscapeInputTest`), the settings, seed, and game-ID
  dialogs (`FifteenSettingsDialogTest`, `FifteenSeedDialogTest`,
  `FifteenGameIdDialogTest`), the real preferences activity
  (`PrefsActivityTest`), and the chooser launch
  (`FifteenChooserLaunchTest`).
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance. A physical phone
  cannot replace the reference AVD for the goldens, and a debug build is
  not a performance baseline.

First enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 /
API 36, at the first-wave code commit `58c7b52e`:

- The enabled set is `fifteen`, `samegame`, `flood`, and `pegs`.
  `KotlinGames.handles` returns true for exactly these four and false
  for every other backend.
- `:app:connectedDebugAndroidTest` ran 70 cases: 64 passed, 0 failed,
  6 skipped. The skips are the same six as the earlier run (three
  fastlane screenshot producers and three chooser goldens). The suite
  took 2 min 6 s on the phone. The three new cases are
  `FirstWaveTileSizeInputTest[samegame]`, `[flood]`, and `[pegs]`.
- `:engine:jvmTest` -> 1123 tests, 0 failures; `:parity:test` -> 1885
  tests, 0 failures; `:app:testDebugUnitTest` -> 107 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- This run proves the first wave on a device: each new game launches on
  `KotlinGameEngine`, accepts a real tap and a real swipe at two
  distinct tile sizes, and the move count and save text change only
  when the game applies a move. The per-game gestures are the ones the
  shell allows: `samegame` and `flood` are `PREVENT` drag games, so the
  tap is their move and the swipe is deliberately inert; `pegs` moves
  on the swipe and its tap cancels the drag.
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance.

Second enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 /
API 36, after the second-wave code commit:

- The enabled set is `fifteen`, `samegame`, `flood`, `pegs`, `sixteen`,
  `flip`, `twiddle`, and `guess`. `KotlinGames.handles` returns true
  for exactly these eight and false for every other backend.
- `:app:connectedDebugAndroidTest` ran 74 cases: 68 passed, 0 failed,
  6 skipped. The skips are the same six as the earlier run (three
  fastlane screenshot producers and three chooser goldens). The four
  new cases are `SecondWaveTileSizeInputTest[sixteen]`, `[flip]`,
  `[twiddle]`, and `[guess]`. The 43 `GamePlayParameterizedTest` cases
  include the four new backends and complete a game on the Kotlin
  engine.
- `:engine:jvmTest` -> 1123 tests, 0 failures; `:parity:test` -> 1885
  tests, 0 failures; `:app:testDebugUnitTest` -> 107 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- This run proves the second wave on a device: each new game launches on
  `KotlinGameEngine`, accepts a real gesture at two distinct tile sizes,
  and the move count and save text change only when the game applies a
  move or stores a draft. The per-game gestures are the ones the shell
  allows: `sixteen` taps a frame band and its swipe is swallowed by
  `PREVENT`; `flip` taps a cell and its swipe is swallowed; `twiddle`
  taps a rotation band and its inert drag starts in the rejected strip
  (the shell is `UNMODIFIED`, so the press reaches the game); `guess`
  taps submit with no draft (inert), drags a colour into a peg (a draft
  in the `UI` record, no move), and taps submit again (one move).
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance.

Third enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 /
API 36, after the third-wave code commit:

- The enabled set is `fifteen`, `samegame`, `flood`, `pegs`, `sixteen`,
  `flip`, `twiddle`, `guess`, `blackbox`, `lightup`, `undead`, and
  `rect`. `KotlinGames.handles` returns true for exactly these twelve
  and false for every other backend.
- `:app:connectedDebugAndroidTest` ran 78 cases: 72 passed, 0 failed,
  6 skipped. The skips are the same six as the earlier runs (three
  fastlane screenshot producers and three chooser goldens). The four
  new cases are `ThirdWaveTileSizeInputTest[blackbox]`, `[lightup]`,
  `[undead]`, and `[rect]`, and none of them skipped. The 43
  `GamePlayParameterizedTest` cases include the four new backends and
  complete a game on the Kotlin engine.
- `:engine:jvmTest` -> 1123 tests, 0 failures; `:parity:test` -> 1885
  tests, 0 failures; `:app:testDebugUnitTest` -> 107 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- This run proves the third wave on a device: each new game launches on
  `KotlinGameEngine`, accepts a real tap at two distinct tile sizes,
  and the move count and save text change only when the game applies a
  move. The per-game gestures are the ones the C input code gives:
  `blackbox` fires one laser per tap on a top range cell (`F` move) and
  the status line stays `Balls marked: 0 / 1`; `lightup` ignores a tap
  in the half-tile frame and turns a white cell into a light (`L`
  move); `undead` only highlights a cell on a tap (no move and no save
  text, because the game has no `UI` record) and toggles a clue mark on
  a clue tap (`D` move); `rect` resolves a square-centre tap to no move
  and an edge-centre tap to an `H` move.
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance.

Fourth enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 /
API 36, after the fourth-wave code commit:

- The enabled set is `fifteen`, `samegame`, `flood`, `pegs`, `sixteen`,
  `flip`, `twiddle`, `guess`, `blackbox`, `lightup`, `undead`, `rect`,
  `slant`, `range`, `towers`, and `unequal`. `KotlinGames.handles`
  returns true for exactly these sixteen and false for every other
  backend.
- `:app:connectedDebugAndroidTest` ran 82 cases: 76 passed, 0 failed,
  6 skipped. The skips are the same six as the earlier runs (three
  fastlane screenshot producers and three chooser goldens). The four
  new cases are `FourthWaveTileSizeInputTest[range]`, `[slant]`,
  `[towers]`, and `[unequal]`, and none of them skipped. The 43
  `GamePlayParameterizedTest` cases include the four new backends and
  complete a game on the Kotlin engine.
- `:engine:jvmTest` -> 1123 tests, 0 failures; `:parity:test` -> 1885
  tests, 0 failures; `:app:testDebugUnitTest` -> 107 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- This run proves the fourth wave on a device: each new game launches
  on `KotlinGameEngine`, accepts a real gesture at two distinct tile
  sizes, and the move count, save text, and exact `MOVE` record change
  only when the game applies a move. The per-game gestures are the ones
  the C input code gives: `range` ignores a clue tap and blackens an
  empty cell (`B` move); `slant` ignores a tap in the outer border and
  cycles a square to `\`; `towers` and `unequal` ignore a border tap,
  select a cell with a tap, and take a real hardware digit (`R` move).
  The test removes the stored game preferences before the run, so the
  C defaults decide the button order.
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance.

Fifth enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 /
API 36, after the fifth-wave code commit:

- The enabled set is `fifteen`, `samegame`, `flood`, `pegs`, `sixteen`,
  `flip`, `twiddle`, `guess`, `blackbox`, `lightup`, `undead`, `rect`,
  `slant`, `range`, `towers`, `unequal`, `magnets`, `pattern`,
  `mosaic`, `signpost`, and `untangle`. `KotlinGames.handles` returns
  true for exactly these 21 and false for every other backend.
- `:app:connectedDebugAndroidTest` ran 89 cases: 83 passed, 0 failed,
  6 skipped. The skips are the same six as the earlier runs (three
  fastlane screenshot producers and three chooser goldens). The five
  new cases are `FifthWaveTileSizeInputTest[magnets]`, `[pattern]`,
  `[mosaic]`, `[signpost]`, and `[untangle]`, and none of them skipped.
  The 43 `GamePlayParameterizedTest` cases include the five new
  backends and complete a game on the Kotlin engine.
  `SlowGenerationDialogTest` ran both cases: the Towers one unchanged,
  and the new Mosaic one warns for the aggressive `100x100` board and
  keeps the current game on Cancel.
- `:engine:jvmTest` -> 1135 tests, 0 failures; `:parity:test` -> 1885
  tests, 0 failures; `:app:testDebugUnitTest` -> 112 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- This run proves the fifth wave on a device: each new game launches on
  `KotlinGameEngine`, accepts a real gesture at two distinct tile
  sizes, and the move count, save text, and exact `MOVE` record change
  only when the game applies a move. The per-game gestures are the ones
  the C input code gives: `magnets` ignores a border-corner click and
  makes a positive magnet from a cell click; `pattern` ignores a
  clue-band click and fills an unknown cell; `mosaic` ignores a margin
  click and marks a shown clue; `signpost` ignores a press on the final
  immutable number, links `(0,0)` to `(1,1)`, and then links the chain
  on; `untangle` ignores a board-corner press and drags two vertices.
  The `untangle` record holds the last interpolated drag pixel, not the
  requested end, so its two steps check the vertex, the live tile
  denominator, and a point between the start and the end.
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance.

Sixth enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 /
API 36, after the sixth-wave code commit:

- The enabled set is `fifteen`, `samegame`, `flood`, `pegs`, `sixteen`,
  `flip`, `twiddle`, `guess`, `blackbox`, `lightup`, `undead`, `rect`,
  `slant`, `range`, `towers`, `unequal`, `magnets`, `pattern`,
  `mosaic`, `signpost`, `untangle`, `galaxies`, `bridges`, `unruly`,
  `filling`, and `mines`. `KotlinGames.handles` returns true for
  exactly these 26 and false for every other backend.
- `:app:connectedDebugAndroidTest` ran 95 cases: 89 passed, 0 failed,
  6 skipped. The skips are the same six as the earlier runs (three
  fastlane screenshot producers and three chooser goldens). The five
  new cases are `SixthWaveTileSizeInputTest[galaxies]`, `[bridges]`,
  `[unruly]`, `[filling]`, and `[mines]`, and none of them skipped.
  The 43 `GamePlayParameterizedTest` cases include the new backends and
  complete a game on the Kotlin engine. `SlowGenerationDialogTest` ran
  three cases: Towers and Mosaic unchanged, and the new Galaxies one
  warns for a custom `16x16` board and keeps the current game on
  Cancel.
- `:engine:jvmTest` -> 1135 tests, 0 failures; `:parity:test` -> 1885
  tests, 0 failures; `:app:testDebugUnitTest` -> 113 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- This run proves the sixth wave on a device: each new game launches on
  `KotlinGameEngine`, accepts a real gesture at two distinct tile
  sizes, and the move count, save text, and exact `MOVE` record change
  only when the game applies a move. The per-game gestures are the ones
  the C input code gives: `galaxies` ignores a click on an occupied
  square and a corner press, toggles an edge (`E1,2`), and right drags
  an arrow (`A5,1,5,3`); `bridges` ignores a click on an empty cell,
  bridges an island to the next one (`L1,0,4,0,1` and `L1,0,1,3,1`),
  and locks an island (`M4,0`); `unruly` ignores a border click and
  places `P1,0,0` and `P1,1,1`; `filling` ignores a border click and a
  digit with no selection, then fills `0_1` and `10_1` from a region
  click and its digit; `mines` ignores a border tap, opens `O0,0`, and
  flags `F8,8` with a held press. The held press is a real injected
  down, the shell's long-press callback, a move, and a release; the
  digit goes through `GamePlay.sendKey`. The save comparison drops the
  `TIME` record, because `mines` is the one timed game and its clock
  rewrites that record on every tick.
- These rows stay manual: the pixel goldens on the reference AVD,
  TalkBack, contrast, font scale 200%, and performance.

- 63 tests, 60 passed, 3 skipped (the fastlane screenshot tests skip
  themselves without the fastlane argument), 0 failed. The Compose
  renderer is active in all game tests.
- `GamePlayParameterizedTest[Palisade 2x3n3:d33]` was stale, not a
  shell regression. It sent `L`, which only toggles an edge in the
  legacy half-grid cursor mode; Palisade defaults to the full-grid
  cursor. The case now uses the arrow and select keys of the default
  mode, and it passes.
- The four screen tests, the four accessibility tests, and the three
  screenshot comparisons pass.

Hygiene re-run, Pixel 4a 5G (physical), 2026-11-18, Android 16 / API
36, after the hygiene-fix commits:

- `:app:connectedDebugAndroidTest` ran 95 cases: 89 passed, 0 failed,
  6 skipped. The six skips are the three fastlane screenshot producers
  and the three chooser goldens. `GamePlayParameterizedTest` now asserts
  that `gameEngine is KotlinGameEngine` equals `KotlinGames.handles(backend)`
  in every case, so it covers all 26 enabled backends and the 14 that
  still run on C, and a stale hand-kept list cannot narrow it.
- `:engine:jvmTest` -> 1136 tests, 0 failures; `:parity:test` -> 1887
  tests, 0 failures; `:app:testDebugUnitTest` -> 116 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings.
- The slow-generation unit gate now asks the warning for a game that
  declares a threshold and is not in `KotlinGames.APP_GAMES`, so the
  `handles` gate, not an absent threshold, is what keeps a C backend
  quiet. The Cancel check of the slow-warning dialog compares the saved
  game text with the engine, and an inert wave gesture holds its save
  text for a bounded settle window instead of returning at once.

Seventh enablement wave, Pixel 4a 5G (physical), 2026-11-18, Android
16 / API 36:

- `:app:connectedDebugAndroidTest` ran 107 cases: 101 passed, 0 failed,
  6 skipped. The six skips are the same three fastlane producers and
  three chooser goldens. `SeventhWaveTileSizeInputTest` ran all twelve
  cases and none skipped, so each game used two non-preferred tile
  sizes. `GamePlayParameterizedTest` now covers all 38 enabled backends
  and the 2 that still run on C.
- `:engine:jvmTest` -> 1151 tests, 0 failures; `:parity:test` -> 1915
  tests, 0 failures; `:app:testDebugUnitTest` -> 105 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings;
  `tools/check-upstream-diff.sh --strict` and
  `tools/check-upstream-lock.sh` both pass.
- Two gestures were fixed during the run. Palisade's exact cell centre
  is a tie between the vertical and horizontal edges, and the one-pixel
  density rounding chose the top fence; the test point is now three
  quarters down the cell, which is the bottom fence at every tile size.
  Loopy's taps were half a tile apart, so two of them landed on the
  same edge at the live tile size; they are now one tile apart, on the
  midpoint of each edge, and the expected moves are `3y`, `1y`, `5y`,
  and `8y`.

Eighth enablement wave, Pixel 4a 5G (physical), 2026-11-19, Android
16 / API 36:

- `:app:connectedDebugAndroidTest` ran 145 cases: 139 passed, 0 failed,
  6 skipped. The six skips are the same three fastlane producers and
  three chooser goldens. `EighthWaveTileSizeInputTest` ran both cases
  and neither skipped, so `solo` and `cube` each used two non-preferred
  tile sizes; every case loads a frozen game id, so the suite generated
  no board and cannot exceed the D-036 cap.
  `GamePlayParameterizedTest` covers all 40 enabled backends, so both
  new games complete through the shell on the Kotlin engine.
- `:engine:jvmTest` -> 1223 tests, 0 failures; `:parity:test` -> 1921
  tests, 0 failures; `:app:testDebugUnitTest` -> 117 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings;
  `tools/check-upstream-diff.sh --strict` and
  `tools/check-upstream-lock.sh` both pass.
- Two shell tests used `cube` as the "C game with no board units"
  fixture, which the eighth wave removed. `ShellAccessibilityTest` now
  asserts the silent status line of a unit board, and
  `BoardSemanticsTest` checks the same rule on `tents` and `cube`; the
  polite fallback is pinned without a device by `StatusLiveRegionTest`,
  which also drove a small fix: the engine can write the status text
  before the first drawing pass, so `onBoardRedrawn` now clears the
  polite flag as soon as the board has units.

Shell P0 wave, Pixel 4a 5G (physical), 2026-11-18, Android 16 / API
36, after the shell accessibility and guideline audits:

- Three P0 defects, all reproduced on the phone before the fix. The
  board was not drawn in landscape: `layout-land/main.xml` had no
  `game_canvas` view, so the Compose board had nothing to draw on and
  `GameView.onDraw` stayed empty. The button bar was off-screen in a
  right-to-left layout: the key box placed its content at the start
  edge and then added positive absolute offsets, so every key landed
  outside the window. Settings titles ellipsized at 200% text:
  `PreferenceThemeOverlay` maps the `SwitchPreferenceCompat` and
  `ListPreference` styles to their Material variants, which set
  `android:layout` but not `singleLineTitle`, so the title view kept
  the layout's `singleLine="true"`.
- Fixes. `layout-land/main.xml` now wraps `GameView` in the same
  `game_area` FrameLayout as portrait, with the transparent `ComposeView`
  under the transparent `GameView`, and the same layout rules, so the
  board keeps its geometry. `Buttons` places the key box at the
  absolute top left (`BiasAbsoluteAlignment`); `BiasAlignment` is
  direction-aware and mirrors its horizontal bias, which is why it
  does not fix the layout. `PrefsTheme` sets `singleLineTitle` to
  `false`, which reaches every preference subclass style.
- `:app:connectedDebugAndroidTest` ran 110 cases: 104 passed, 0 failed,
  6 skipped. The six skips are the same three fastlane producers and
  three chooser goldens. The three new cases are
  `ShellLayoutTest.theBoardIsDrawnInLandscape`,
  `theButtonBarStaysInTheWindowInRtl`, and
  `theSettingsTitlesWrapAtTwoHundredPercentText`.
- `:engine:jvmTest` -> 1151 tests, 0 failures; `:parity:test` -> 1915
  tests, 0 failures; `:app:testDebugUnitTest` -> 105 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings;
  `tools/check-upstream-diff.sh --strict` and
  `tools/check-upstream-lock.sh` both pass.
- The three cases fail on the old code for the three separate reasons:
  `the game layout has no game_canvas view`, `The component with
  ContentDescription = 'Undo' ... is not displayed`, and `the title is
  ellipsized at 200% text: lines=1 maxLines=1 ellipsize=MARQUEE`. The
  RTL case checks the exact `MOVE` record, so it would also fail if the
  board mirrored.
- Device state changed for the run and restored: the system text scale
  (the test does this itself), the window layout direction (per test
  activity), and the display rotation. The per-app locale and the
  rotation settings were used for the manual screenshots and restored.

Shell accessibility P1 wave, Pixel 4a 5G (physical), 2026-11-18,
Android 16 / API 36, after the same two audits:

- Seven fixes, each reproduced or measured on the phone before the
  change. The status line was not a live region, so a move, an undo or
  the solver changed it silently. A letter key was spoken as the bare
  character because the shell passed `c.toString()` as the content
  description and dropped the names the engine keeps in
  `Game.KeyLabel.name`. A configuration field carried its label only as
  an `addLabeledBy` relation, and its `minHeight` was written through
  the wrong property, so it measured 45dp. The chooser row exposed no
  star state and no action. In landscape the status line sat under the
  navigation bar. A key label was a fixed 24sp in a 48dp key.
- Fixes. `GamePlay.setStatus` sets
  `ACCESSIBILITY_LIVE_REGION_POLITE` on a non-empty text and `NONE` on
  an empty one, and returns before it touches the view when the text is
  already shown. `GameEngine.keyNames` returns the game's own key
  names (the C engine keeps the empty default), `Buttons` speaks them
  and falls back to `Letter %1$s` for a key with no name.
  `ConfigViewsBuilder` sets the field's `hint` and the spinner's
  `contentDescription` to the row label and sets `minimumHeight`.
  `ChooserScreen` makes the row one merged node with the name, the
  description, a `Starred` state description and a custom action
  spoken as `Add to starred` or `Remove from starred`; the drawn text
  is no longer a separate stop. In landscape `status_bar` pads itself
  by the bottom system bar. `keyLabelSize` clamps the label to 70% of
  the key, and the label line height follows the clamped size.
- `:app:connectedDebugAndroidTest` ran 117 cases: 111 passed, 0 failed,
  6 skipped. The six skips are the same three fastlane producers and
  three chooser goldens. The seven new cases are the six of
  `ShellAccessibilityTest` and
  `ChooserAccessibilityTest.theRowIsOneTalkableItemWithAChangeableStarState`.
- `:engine:jvmTest` -> 1151 tests, 0 failures; `:parity:test` -> 1915
  tests, 0 failures; `:app:testDebugUnitTest` -> 105 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings;
  `tools/check-upstream-diff.sh --strict` and
  `tools/check-upstream-lock.sh` both pass.
- Each case fails on the code before its fix, with these messages:
  `the status line 'Moves: 0' is not a polite live region
  expected:<1> but was:<0>`, `the same status was written to the view
  again expected:<0> but was:<3>`, `these keys are spoken as bare
  letters: [L]`, `the text rows are not labelled Width and Height:
  [null, null]`, `the Width field is 118px tall, below 48dp (126px)`,
  `no custom action spoken as 'Add to starred' in []`, `the landscape
  status line ends at 1069px, under the navigation bar (63 px, lowest
  allowed 1017)`, and `a key label is 48.0 dp of text in a 48.0dp key
  at 200% text`.
- The navigation bar case ran with gesture navigation (a 63px bottom
  inset in landscape). Both navigation modes were also checked by hand
  with `cmd overlay enable-exclusive` and `uiautomator` dumps, then the
  overlays were restored to gesture navigation.
- Device state changed for the run and restored: the system text scale
  (the test does this itself), the display rotation, and the three
  animation scales, which Espresso needs at 0.

Adaptive layout wave, Pixel 4a 5G (physical), 2026-11-19, Android 16 /
API 36:

- The shell has one window size class rule now (`ui/WindowSize.kt`,
  `docs/adr/0012-adaptive-layouts.md`). It is fed by the allowed
  dependency `androidx.compose.material3:material3-window-size-class`,
  whose version comes from the Compose BOM. The chooser list, the
  settings list, and the help page keep the 840dp reading measure and
  are centred from the medium class up; dialogs are capped at 560dp;
  the game screen shows its title and action labels from the medium
  class up instead of at the old 480dp and 620dp thresholds. The board,
  the key grid, the engine, and the parity resources are untouched.
- `:app:connectedDebugAndroidTest` ran 132 cases: 126 passed, 0 failed,
  6 skipped; the task took 4 min 31 s. The six skips are the three
  fastlane producers and the three chooser goldens, as before. The two
  new cases are
  `ShellLayoutTest.theShellKeepsTheReadingMeasureOnAnExpandedWindow`
  and `theGameSurvivesAWindowResize`; both use `wm size 2400x3000`
  (914dp by 1143dp at 420 dpi) to simulate an expanded window on the
  phone, and both restore the override in a `finally` block.
- The two new cases fail on the code before the change: `the chooser
  list is 914.2857dp wide, not capped at 840.0dp` and `the settings
  list is 2400 of 2400px, not capped`.
- `:engine:jvmTest` -> 1154 tests, 0 failures; `:parity:test` -> 1915
  tests, 0 failures (both re-run with `--rerun-tasks`);
  `:app:testDebugUnitTest` -> 106 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings, the same count as before;
  `tools/check-upstream-diff.sh --strict`,
  `tools/check-upstream-lock.sh`, and `git diff --check` are clean.
- Device state changed for the run and restored: the `wm size` override
  of the two window cases, the display rotation, and the three
  animation scales. `wm density`, `font_scale`,
  `accelerometer_rotation`, and `user_rotation` were left at the values
  the run started with (420, 1.0, 1, 0). Screenshots of the shell at an
  expanded window are in `/tmp/opencode/adaptive/` and are not
  committed.

D-01 spoken board close-out, Pixel 4a 5G (physical), 2026-11-19,
Android 16 / API 36:

- `:app:connectedDebugAndroidTest` ran 200 cases: 194 passed, 0 failed,
  6 skipped, 4 min 53 s (292.5 s). The six skips are the same three
  fastlane producers and three chooser goldens. The wave adds 55 cases:
  `BoardSpokenTableTest` with 40, one per backend, in 27.4 s;
  `BoardCapSemanticsTest` with 14, one per documented above-cap game;
  and `BoardFallbackSemanticsTest` with one. Every case loads a frozen
  game id or builds its state from a description, so the wave generates
  no board at all.
- `:engine:jvmTest` -> 1223 tests, 0 failures; `:parity:test` -> 1921
  tests, 0 failures; `:app:testDebugUnitTest` -> 117 tests, 0 failures;
  `:app:lintDebug` -> 0 errors, 547 warnings;
  `tools/check-upstream-diff.sh --strict` and
  `tools/check-upstream-lock.sh` both pass.
- The device table reads `testdata/spoken/` from the test APK assets
  (`app/build.gradle.kts` adds that directory to the `androidTest`
  source set), so the phone compares against the committed table and
  not against a second copy. Two mismatches were found by the first run
  and neither is a product defect: the case predicted the board actions
  from the C key constant `UI_SOLVE` instead of the shell's
  `UIVisibility.SOLVE` bit, and the tables are built with the game
  cursor hidden while the shell shows the cursor of a game whose arrow
  keys are on, so the affected unit gains `, cursor`. The case now takes
  the shell's bit and allows that one suffix, and the second mismatch is
  a rule in `docs/PARITY.md` rather than an edit to the tables.
- `BoardFallbackSemanticsTest` needed a way to build a C engine, since
  `fromLaunch` sends every backend to the Kotlin engine and the external
  `fromGameID` cannot be renamed without breaking its JNI registration.
  `GameEngineImpl.cEngineForTest` is the small `@VisibleForTesting`
  wrapper; `GamePlay.gameEngine` gained a `@VisibleForTesting` internal
  setter so the live screen can hold that engine.

## 9. CI

Jobs:

1. **jvm** — `:engine:jvmTest` plus parity against the pinned
   upstream.
2. **oracle-current** — parity against current upstream. A difference
   means behavior changed during sync.
3. **android** — build, unit tests, lint. Instrumentation and
   screenshots need a device and are not in the job yet.
4. **fuzz** — scheduled, not on every PR.
5. **upstream-diff** — vendored files were not changed.

Requirements:

- JVM tests need no emulator and run through `ProcessBuilder`;
- Android tests are a separate, slower job;
- cache the oracle by `upstream_commit + compiler`;
- on failure, save traces and a minimal repro;
- a flaky test is not retried blindly: quarantine it and open an issue.

## 10. Android emulator

Use the emulator for:

- instrumented tests (`connectedDebugAndroidTest`);
- UI iteration and layout checks;
- screenshot tests with a fixed AVD;
- API coverage: 21 (minimum), 29/31 (dynamic color), 36 (target);
- tablet, landscape, RTL, and font scale 200%.

Do not use it for:

- absolute performance budgets (use a physical device);
- battery, thermal, touch latency, stylus, or 120Hz checks;
- release sign-off.

CI:

- one phone AVD on API 36 and one smoke AVD on API 21;
- Gradle Managed Devices or an emulator action;
- ATD images for speed;
- fixed config: locale `en-US`, font scale 1.0, animations off,
  `-no-window -no-audio -no-snapshot`, stable GPU mode;
- keep the screenshot job separate from the fast JVM parity job.

Local:

- create AVDs with Android Studio or `avdmanager`;
- cold boot for reproducible screenshots;
- do not benchmark on a debug build or a noisy host.

## 11. Performance and accessibility

- Budgets, method, and anti-patterns are in `docs/PERFORMANCE.md`.
- Phase 0: record the baseline (cold/warm start, frames, PSS, APK) on
  reference devices; report P50/P90/P99.
- Nightly perf job: Macrobenchmark, FrameMetrics, APK size; compare
  with the baseline. A regression above 10% blocks the release.
- PR gate: stable metrics only; unstable metrics are advisory.
- Phase 5: final budgets, Baseline Profiles, Macrobenchmark.
- Accessibility: TalkBack pass, 48dp, contrast, font scale 200%, RTL.
  Check in CI where possible and by hand before release.
