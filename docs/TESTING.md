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
layout(state, w, h)     -> game size after midend_size
keys(state, seq)        -> state + flags
undo/redo(state)        -> state + flags
serialise(state)        -> savefile
load(savefile)          -> state
solve(state)            -> state
requestkeys(state)      -> arrow mode + key labels
trace(state[, seq, prefs]) -> draw primitives
randomBits(seed|state)  -> values + encoded generator state
randomUpto(seed|state)  -> values + encoded generator state
sort(values, mode)      -> sorted values
combi(r, n)             -> combinations in upstream order
dsf(kind, n, ops)       -> roots, sizes, minima, flip senses
tdq(n, ops)             -> every removal
findloop(n, edges)      -> loop edges and bridges
matching(nl, nr, adj)   -> matching and permuted adjacency
tree(ops)               -> operation results and index order
```

`id` re-reads a save file. It is used by the save corpus test to prove
that a Kotlin-written file is accepted by C.

`keys` replays a whole sequence in one midend. It is required for
cursor games: the cursor lives in the frontend state (`game_ui`), which
is not part of the save file, so separate `key` requests would reset it.

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
  font changes every pixel.
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

The flip solver has one known seam gap. `Game.solve` has no error
out-parameter, so an insoluble hand-written position returns `null` in
Kotlin, while C reports `No solution exists for this position`. The
current `null` behavior is pinned by
`FlipGameTest.an insoluble hand-written position cannot be solved`, with
no `DEVIATIONS.md` entry: generated games are always soluble, and the
gap is enablement seam debt, recorded in `work/port-queue.md`. A fix
belongs in the seam as a solver result type, not as a field on the game
state.
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
- `GamePlayParameterizedTest` asserts that the running `fifteen` engine
  is `KotlinGameEngine`, then completes the 2x2 game through the real
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

Device run on the API 36 emulator, 2026-11-14:

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
