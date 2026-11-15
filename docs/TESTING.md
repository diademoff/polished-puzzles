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
The optional `prefs` field applies game preferences first, in the
upstream preferences file format, so a game mode that only `game_ui`
holds (for example palisade's `cursor-mode=full`) can be compared at the
state level.

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
- `GameIdTest` pins the parameter validation of `midend_game_id_int`: a
  game ID validates with `full = false` because it has a description,
  so a short id is accepted, while `newGame`, which has no description,
  validates the full form and rejects it.
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
  `Lightup.Ui.drawBlobsWhenLit`, because the seam has no preference
  hook. The app adapter must parse `show-lit-blobs=true|false` and set
  `Lightup.Ui.drawBlobsWhenLit` on the live UI after `newUi` and after
  `Midend.fromSave`, because there is no `decodeUi` and a load recreates
  the UI. It must force a redraw after the change, as C recreates the
  drawstate on a preference change. The value must not enter
  `DrawState.flags`: C reads it in `tile_redraw`
  (`lightup.c:2219`), so a warm frame after a preference change alone
  is empty. The truth of this rule is pinned by `LightupTest` and
  `LightupInputParityTest`; the app wiring is still open.
- S8 and S9: the configuration dialog (Width, Height,
  `%age of black squares`, Symmetry, and Difficulty) and the preference
  schema are open, and `currentKeyLabel` is a tested helper with no
  Android call site. `LIGHTUP` is in `KotlinGames.gameFor` and not in
  `APP_GAMES`, so `KotlinGames.handles(LIGHTUP)` is false and the app
  still uses C. The two-release wait also applies.

`unruly` has two C undefined-behavior guards, one seam decision, and
two enablement debts:

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
  because the seam has no error out-parameter. C sets
  `No solution found.` or `Puzzle is invalid.` and returns `NULL`. The
  seven presets always solve, so the English messages are pinned only
  by the engine test and `UnrulyGameTest`; this is the same seam debt
  as `flip`, not a deviation.
- `validate_params` accepts `46340x46340`, because the area check
  stays `w > INT_MAX / h` as C writes it. Generation would then
  allocate a 2,147,395,600-cell grid and mask, more than 10 GB in this
  port, and run the solver over it. No test calls `newDesc` or
  `newState` on a huge accepted area; `validateParams` pins the
  accepted `46340x46340` side, and `UnrulyDescTest` compares the
  rejected `65536x65536` message with C.
  The configuration dialog needs a guard before `UNRULY` is enabled.
  A guard rejects parameters that C accepts, so it needs a new
  deviation entry and a test, and it must not change any accepted
  normal parameter. Until then this is enablement debt, not a
  deviation.
- The generator retries without a bound in two nested loops
  (`unruly.c:1379-1476`). A solver defect that over-fills can make the
  "too easy" test fail forever. The generation tests run with a
  timeout; no production guard is added, because a bound would change
  the seed stream.

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
  lives on `Range.Ui.swapButtons` with a false default, because the
  seam has no preference hook. The app adapter must parse the value
  and set `swapButtons` after `newUi` and after `Midend.fromSave`,
  because `range.c` has no `decodeUi` and a load recreates the UI. The
  value must reach the live `Ui`, not only the stored text; the shell
  already reloads prefs when `preferencesName` changes. The oracle's
  `pointsSized` and `traceWarm` have no `prefs` field, so a swapped
  click cannot be compared with C yet; `RangeInputParityTest` pins the
  rule on the engine side against `range.c:1368-1375` (a left press
  cycles forwards and a right press backwards, and a select key is not
  swapped). A `prefs` field on `pointsSized` is needed before that
  click can be C-compared. Until then this is enablement debt, not a
  deviation.
- `currentKeyLabel` is a tested helper because C has no Android call
  site yet (seam item S9).

The flip solver has one known seam gap. `Game.solve` has no error
out-parameter, so an insoluble hand-written position returns `null` in
Kotlin, while C reports `No solution exists for this position`. The
current `null` behavior is pinned by
`FlipGameTest.an insoluble hand-written position cannot be solved`, with
no `DEVIATIONS.md` entry: generated games are always soluble, and the
gap is enablement seam debt, recorded in `work/port-queue.md`. A fix
belongs in the seam as a solver result type, not as a field on the game
state.

`guess` has two enablement debts, both recorded in `work/port-queue.md`:

- The `show-labels` preference lives on `Guess.Ui` with a false
  default, because the seam has no preference hook. The app adapter
  must apply the stored per-backend preference after `newUi` and after
  `decodeUi`. C's order is `new_ui`, `set_prefs`, `decode_ui`
  (`midend.c:2669-2673`); for guess the last two commute, because
  `decode_ui` does not touch `show_labels`, so applying the preference
  after `Midend.fromSave` is observationally identical. No preference
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

`filling` has one enablement debt:

- `validate_params` accepts `46340x46340`, because the area check stays
  `w > INT_MAX / h` as C writes it. Generation then allocates about
  8.6 GB of `int` boards and does not finish. No test calls `newDesc`
  or `newState` on a huge accepted area; only the rejected
  `46341x46341` boundary is pinned. The configuration dialog needs a
  guard before `FILLING` is enabled. A guard rejects parameters that C
  accepts, so it needs a new deviation entry and a test, and it must not
  change any accepted normal parameter. Until then this is enablement
  debt, not a deviation.

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
  C has the same accepted boundary and allocates about 4.3 GB. No test
  calls `newDesc` or `newState` on a huge accepted area; only the
  rejected `46341x46341` side is pinned. The configuration dialog needs
  a guard before `NETSLIDE` is enabled. A guard rejects parameters that
  C accepts, so it needs a new deviation entry and a test, and it must
  not change any accepted normal parameter. Until then this is
  enablement debt, not a deviation.

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
  capped at 10000 cells, so this is latency, not a hang. The app must
  generate a board off the UI thread, or the owner must accept a guard
  that C does not have (a deviation with a test). No test generates an
  aggressive `100x100`; the accepted `100x100` and `3x3333` boundaries
  are pinned in validation only. S8 and S9 are still open, and
  `MOSAIC` is in `KotlinGames.gameFor` but not in `APP_GAMES`, so
  `KotlinGames.handles(MOSAIC)` is false and the app still uses C.

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
- Enablement: the app still uses C. The three-field configuration
  dialog (Width, Height, Region size), the two-preference schema
  (`cursor-mode` and `clear-complete-regions`) with an apply after
  `newUi` and after `Midend.fromSave` plus a forced redraw and a key
  re-request, the `Fifteen` casts in `KotlinGameEngine`, and the
  two-release wait are open. `KotlinGameEngine.requestKeys` already
  passes `palisadeFullCursor` to the game, and S9 is not needed because
  `current_key_label` is `NULL`. `PALISADE` is in `KotlinGames.gameFor`
  but not in `APP_GAMES`, so `KotlinGames.handles(PALISADE)` is false.

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
