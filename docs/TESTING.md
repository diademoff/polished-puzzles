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
layout(state, w, h)     -> game size after midend_size
keys(state, seq)        -> state + flags
undo/redo(state)        -> state + flags
serialise(state)        -> savefile
load(savefile)          -> state
solve(state)            -> state
requestkeys(state)      -> arrow mode + key labels
trace(state)            -> draw primitives
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

`trace` records one `midend_redraw` through the recording drawing API.
`traceNew` creates the game first, so a trace does not depend on the
save format. The contract is `docs/adr/0003-strict-draw-traces.md`.

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
  centers back into `processKey`; drawing and input must agree.
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
  requested keys, the status text, and the text format.
- `FifteenSaveTest` is strict for the save text. It reads every
  `fifteen` file in the corpus into the Kotlin midend, rewrites it byte
  for byte, and makes the oracle load both versions. It also makes the
  oracle load a Kotlin-written new game.
- `FifteenInputParityTest` is semantic. It asks the oracle to choose a
  board size for phone, tablet, landscape, and tiny viewports, then
  compares the Kotlin layout and the C/Kotlin state after clicking the
  gap, every in-board neighbour, and a diagonal cell at that size. It
  also compares a swipe into the gap and a swipe that starts on the gap,
  using the same `DOWN`, `DRAG`, `UP` sequence as the shell.
- `FifteenTraceTest` is strict. It draws the Kotlin board through
  `KotlinTraceSink`, normalises it with the same `TraceNormalizer` as
  the oracle, and compares the result with a fresh oracle trace and
  with every frozen `fifteen` golden.
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
