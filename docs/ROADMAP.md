# Roadmap

Goal: make the puzzle collection pleasant to use on Android without
changing game rules, the seed, or the save format.

The roadmap is a set of phases with verifiable exit criteria. If a
criterion is not met, the phase is not finished, even if the code is
already written.

## Phase 0 — Baseline

Without this, no parity test can be trusted.

Tasks:

- build the host oracle from `app/src/main/jni` for Linux/macOS;
- wire in `fuzzpuzz` (it currently lives in `not-in-use/`, while CMake
  expects it next to `jni/CMakeLists.txt`);
- fill `UPSTREAM.lock` and add the CI check;
- add `app/src/main/jni/UPSTREAM-FILES.txt` — the vendored allowlist;
- freeze the first goldens: `params + seed -> DESC` for every game;
- add `parity/` as a JVM module and a smoke test;
- add CI: jvm, oracle-pinned, oracle-current, upstream-diff;
- measure the baseline in `docs/PERFORMANCE.md`: cold/warm start,
  jank, frames, PSS, APK size, build time. Report P50/P90/P99 on
  reference devices;
- set up local and CI AVDs: API 21 smoke, API 36 phone, tablet;
- inventory the games and fill the status table in `PARITY.md`.

Exit criteria:

- `tools/oracle/build.sh` works on a clean machine;
- the parity smoke test is green;
- `upstream-diff` fails when a vendored file is changed;
- `UPSTREAM.lock` is filled;
- the baseline is recorded: devices, build, P50/P90/P99.

## Phase 1 — Shell

Modernize the shell. Do not touch the C engine; it remains the only
source of truth.

Tasks:

- Compose/M3: chooser, toolbar, settings, help, dialogs;
- dark theme and edge-to-edge with correct insets;
- typography, spacing scale, Material Symbols;
- accessibility: TalkBack, 48dp, contrast, font scale 200%, RTL;
- predictive back;
- screenshot tests and an accessibility scan.

Exit criteria:

- no changes to C logic;
- screenshot tests are green;
- the accessibility audit has no P0/P1;
- the app looks modern on phones and tablets.

## Phase 2 — Renderer

Replace Bitmap/Canvas with Compose Canvas without changing geometry.

Tasks:

- recording backend: C primitives -> JSON trace;
- trace normalizer (`traceSchema: 1`);
- Compose renderer that implements the same `drawing_api`;
- theme layer on top of geometry;
- strict traces for every game and preset.

Exit criteria:

- strict traces match the oracle for every game and preset;
- the theme does not change geometry;
- the budgets in `docs/PERFORMANCE.md` are not exceeded; regression
  ≤ 10%;
- blitter and clip behave the same.

Status: the trace contract, the oracle recorder, the normalizer, the
Compose display-list renderer, and strict traces for all 288 presets are
in place. The theme does not change the board. Blitter and clip are
implemented and checked visually on Galaxies and Map. The frame budget
is measured on the emulator only: P50 matches the old renderer, P90/P99
are worse on the software GPU. The absolute verdict is D-09.

## Phase 3 — Infrastructure

Port infrastructure, not games.

Tasks:

- `midend` on Kotlin Multiplatform: undo/redo, save/load, timer,
  config, key mapping;
- libraries: `dsf`, `tree234`, `findloop`, `loopgen`, `grid`, `latin`,
  `sort`, `combi`, `tdq`, `matching`, `random`;
- `random.c` bit-exact;
- differential tests on the generated corpus;
- cross-compatible `.sgtp` in both directions.

Exit criteria:

- the parity corpus is green;
- `.sgtp` reads and writes in both directions;
- seed -> DESC matches for every game;
- iteration order and RNG are deterministic.

Status: closed on 2026-11-14. The phase started while D-09 was still
open, by owner decision; it does not touch the renderer and does not
change the D-09 evidence.

The `:engine` Kotlin Multiplatform module exists. Ported and pinned:
`random.c` (`RandomParityTest`), the `SAVEFILE` container with a
committed corpus (`SaveParityTest`), `arraysort` and `combi`
(`DeterministicHelpersParityTest`), and `dsf`, `tdq`, `findloop`,
`matching`, and `tree234` (one differential test each). The exit
criteria are met: the parity corpus is green, `.sgtp` is read and
written in both directions, `seed -> DESC` is green for every game,
and the RNG and every ported order are deterministic.

`grid`, `latin`, `loopgen`, and `midend` move to Phase 4 and are
ported with their first consumer. They have no meaningful standalone
oracle: a grid is validated by a board, a Latin solver by a puzzle, a
loop by a generator, and the midend by a game's vtable. The decision
and the porting method are in
`docs/adr/0006-port-libraries-with-consumer.md`. `seed -> DESC`
remains oracle-backed until Phase 4 ports each generator; the existing
`GoldenDescTest` is green.

`grid` and `loopgen` are now exceptions to that order: `engine/Grid.kt`,
the aperiodic tiling helpers, and `engine/Loopgen.kt` are ported and
pinned by `GridParityTest` and `LoopgenParityTest` before `pearl` and
`loopy` consume them. The loop generator is differential-tested with a
test-only bias, so it does not need a game to be verified.

## Phase 4 — Games

One game at a time, in dual-run mode, behind a feature flag.

Phase 4 also ports the libraries deferred from Phase 3 with their first
consumer: `midend` with fifteen, and `latin` with keen, towers, unequal,
or singles. `grid` and `loopgen` are ported already. Neither
`slant` nor `mines` includes `grid.h` in this pinned upstream. See
`docs/adr/0006-port-libraries-with-consumer.md`.

Order:

1. `fifteen` — the whole pipeline, has a solver, no dependencies.
2. `slant` or `mines` — generation, undo.
3. `net` — complex drawing, a platform random seed, a solver.
4. `loopy` — the boss: 18 grids, aperiodic tilings, solver.

This game order is a plan. The library order is driven by the first
consumer, and the live port queue is in `work/port-queue.md`
(untracked).

For each game, use the matrix from `PARITY.md`:

- `params + seed -> DESC`;
- new game;
- move sequence;
- undo/redo;
- `completed`;
- `requestKeys`, `presets`, `status`;
- `.sgtp` in both directions;
- solver agreement (if present);
- strict trace;
- UI screenshots.

Status: `fifteen` is `kotlin` (2026-11-14). `samegame`, `flood`, and
`pegs` are `kotlin` since 2026-11-18 (the first enablement wave).
`sixteen`, `flip`, `twiddle`, and `guess` are `kotlin` since 2026-11-18
(the second enablement wave). `blackbox`, `lightup`, `undead`, and
`rect` are `kotlin` since 2026-11-18 (the third enablement wave).
`slant`, `range`, `towers`, and `unequal` are `kotlin` since
2026-11-18 (the fourth enablement wave), `magnets`, `pattern`,
`mosaic`, `signpost`, and `untangle` are `kotlin` since 2026-11-18 (the
fifth enablement wave), `galaxies`, `bridges`, `unruly`, `filling`, and
`mines` are `kotlin` since 2026-11-18 (the sixth enablement wave), and
`netslide`, `palisade`, `inertia`, `tents`, `net`, `tracks`, `dominosa`,
`map`, `keen`, `singles`, `pearl`, and `loopy` are `kotlin` since
2026-11-18 (the seventh enablement wave). `cube` and `solo` stay on C.
The configuration dialog comes
from the schema declared in `Game.configure`, and the cursor is drawn
(`Slant.kt` uses `COL_CURSOR`; `SlantTraceTest` pins the cursor frame
against the oracle). The Kotlin `Game` seam, the semantic midend,
`Fifteen`, and the drawing sink
are in `engine/`; the seams are `docs/adr/0007-kotlin-game-seam.md`,
`docs/adr/0008-run-fifteen-on-kotlin.md`, and
`docs/adr/0009-game-ui-and-auxinfo.md`.

Every row of the matrix is green on the JVM: `params + seed -> DESC`,
new game, moves, undo and redo, `completed`, `requestKeys`, `presets`,
`status`, `.sgtp` in both directions, the hint solver, and the strict
trace. The evidence is `FifteenDescTest`, `FifteenGameTest`,
`FifteenSaveTest`, and `FifteenTraceTest`.

The app selects `KotlinGameEngine` for `fifteen`, `samegame`, `flood`,
`pegs`, `sixteen`, `flip`, `twiddle`, `guess`, `blackbox`, `lightup`,
`undead`, `rect`, `slant`, `range`, `towers`, `unequal`, `magnets`,
`pattern`, `mosaic`, `signpost`, `untangle`, `galaxies`, `bridges`,
`unruly`, `filling`, `mines`, `netslide`, `palisade`, `inertia`,
`tents`, `net`, `tracks`, `dominosa`, `map`, `keen`, `singles`, `pearl`,
and `loopy`, and bypasses the C generator for them. `KotlinGamesTest` pins the enabled set and the
generated saves; `GamePlayParameterizedTest` asserts that the running
engine is `KotlinGameEngine` for every enabled game,
`FirstWaveTileSizeInputTest` covers the first wave,
`SecondWaveTileSizeInputTest` covers the second wave,
`ThirdWaveTileSizeInputTest` covers the third wave,
`FourthWaveTileSizeInputTest` covers the fourth,
`FifthWaveTileSizeInputTest` covers the fifth,
`SixthWaveTileSizeInputTest` covers the sixth, and
`SeventhWaveTileSizeInputTest` covers the seventh, each with real
gestures at two tile sizes. The C games stay
in the vendored tree as
the oracle and for chooser metadata, but they are not reachable as
gameplay backends.

The owner waived the two-release wait on 2026-11-14 for `fifteen` and
on 2026-11-18 for the ported games (decision 1a). UI screenshots are
still local only (D-04).

### Enablement waves (2026-11-18)

The owner waived the two-release wait for the ported games on
2026-11-18 (decision 1a) and chose enablement in waves. The waiver
covers the wait only. It does not waive the per-game checks, and it
does not enable every game.

Rules for a wave:

- a game is enabled only after its parity matrix is green, its device
  test passes, and `KotlinGames.handles` returns true for it;
- before a game is added to `KotlinGames.APP_GAMES`, check that
  `docs/DEVIATIONS.md` has no entry for it with a `Review:` line, that
  `docs/PARITY.md` lists no open size or retry debt the owner must
  decide, and that its preference schema is declared exactly when the
  C table has a non-NULL `get_prefs`; a game with any of these stays
  on C;
- a `Review:` deviation still needs the owner's review before that game
  is enabled, even inside a wave;
- the enabled set is per game and evidence-based. `APP_GAMES` is the
  per-game switch; `KotlinGames.ENABLED` stays the emergency switch for
  all games;
- goldens stay local (decision 4a);
- the generation policy is the owner's: decision 2b made the size limits
  warning-only, and on 2026-11-18 the owner replaced that with hard
  maxima for the thirteen blocked games. The maxima are in `newDesc`
  (D-026..D-034, D-007, D-008, and D-014 tightened), the measurement is
  `work/generation-limits.md`, and each cap has a parity test at and
  above it. A wave does not add or raise a cap on its own.

First wave, 2026-11-18: `samegame`, `flood`, and `pegs` are enabled
with `fifteen`. Each device test is green at two tile sizes on the
Pixel 4a 5G (Android 16 / API 36), and each of the three has no
`Review:` deviation, no flagged size or retry debt, and no preference
page (C `get_prefs` is NULL).

Second wave, 2026-11-18: `sixteen`, `flip`, `twiddle`, and `guess` are
enabled. Each device test is green at two tile sizes on the same phone.
None of the four has a `Review:` deviation or an open retry or hang
debt in `docs/PARITY.md`. `sixteen`, `flip`, and `twiddle` have a NULL
C `get_prefs` and no Kotlin schema; `guess` has a non-NULL C
`get_prefs` and declares the matching one-item `show-labels` schema,
which the preference page builds from. The reserve list for the next
wave was checked again; none of its games is enabled here.

Third wave, 2026-11-18: `blackbox`, `lightup`, `undead`, and `rect` are
enabled. Each device test is green at two tile sizes on the same phone
and none of the four skipped. The candidates were checked in order
`blackbox`, `lightup`, `undead`, `cube`: `blackbox` (D-003 has no
`Review:` line and the drawn-count guard is in place), `lightup`
(D-006 has no `Review:` line and both non-terminating parameter sets
are rejected before generation), and `undead` (no deviation) qualify.
`cube` is deferred: `docs/PARITY.md` records an open enablement debt,
where an accepted `46340x46340` area enumerates every square and hangs,
and the note says the game needs a guard before `CUBE` is enabled.
`rect` fills that slot (D-022 has no `Review:` line, the C square bound
is checked before allocation, and the game has no preference page).
`blackbox` and `rect` have a NULL C `get_prefs` and no schema;
`lightup` has a non-NULL C `get_prefs` and declares the matching
`show-lit-blobs` preference; `undead` has a non-NULL C `get_prefs` and
declares the matching `pencil-keep-highlight` and `monsters`
preferences. All four configuration dialogs come from the declared
`Game.configure` schema.

Fourth wave, 2026-11-18: `slant`, `range`, `towers`, and `unequal` are
enabled. Each device test is green at two tile sizes on the same phone
and none of the four skipped. The candidates were checked in order
`range`, `unequal`, `towers`, `slant`: all four have no `Review:`
deviation in `docs/DEVIATIONS.md`, no open hang or unbounded-retry debt
in `docs/PARITY.md`, and a declared `Game.configure` plus a preference
schema that matches C. `slant` is the original pilot: its prefs schema,
cursor drawing, and `SlantTraceTest` were already in place; its
two-release waiver of 2026-11-14 is superseded by the general waiver,
not doubled. `range`, `towers`, and `unequal` each declare the C
`get_prefs` schema: the Range mouse-button order, the two Towers
preferences, and the Unequal `pencil-keep-highlight`. All four
configuration dialogs come from the declared `Game.configure` schema.

Fifth wave, 2026-11-18: `magnets`, `pattern`, `mosaic`, `signpost`,
and `untangle` are enabled. Each device test is green at two tile sizes
on the same phone and none of the five skipped. The five candidates were
the whole list: none has a `Review:` deviation in
`docs/DEVIATIONS.md`, each declares its `Game.configure` and the
preference schema that matches the C `get_prefs`, and the only open item
was the size bound, which the owner made warning-only (decision 2b).
`Game.generationWarning` names the measured slow cases: `16x14` and
above for magnets, 2500 cells and above for pattern, above 2500 cells
for mosaic, 400 cells and above for signpost, and above 100 points for
untangle. `SlowGenerationDialogTest` checks the warning and Cancel for
the aggressive `100x100` mosaic, the slowest enabled case. The
unbounded generator retries in pattern, signpost, and mosaic are the C
loops; the warning names the sizes where they are long, and no new hard
cap is added.

Sixth wave, 2026-11-18: `galaxies`, `bridges`, `unruly`, `filling`,
and `mines` are enabled. All five were the candidate list, so no
reserve game was needed and `cube` stays on C. Each device test is
green at two tile sizes on the same phone and none of the five skipped.
None has a `Review:` deviation in `docs/DEVIATIONS.md`: `bridges` and
`unruly` have no entry at all, `filling` is D-004, `mines` is D-002
(the Android presets), and `galaxies` is D-023, and none of those three
has a `Review:` line. Each declares its `Game.configure` and the
preference schema that matches the C `get_prefs`. The only open item
was the size or retry bound, which the owner made warning-only
(decision 2b): `Game.generationWarning` names every board above 225
cells for `galaxies` and `bridges`, above 196 for `unruly`, above 221
for `filling`, and above 256 for `mines`. The C generator retry loops
in all five are unbounded and the port keeps them unchanged, so the
Kotlin hang is the C hang; no new hard cap is added.
`SlowGenerationDialogTest` checks the warning and Cancel for a custom
`16x16` galaxies board. `cube` is still deferred: its enumeration hang
is not solved by an existing warning, because its generation is one
pass over the accepted squares and the huge area is a memory question.

Seventh wave, 2026-11-18: `netslide`, `palisade`, `inertia`, `tents`,
`net`, `tracks`, `dominosa`, `map`, `keen`, `singles`, `pearl`, and
`loopy` are enabled. This is the thirteen-game list that a `Review:`
deviation blocked, minus `solo`: the owner measured the real generation
time of each game and set a hard maximum in `newDesc` before the first
draw, so the app can no longer start an endless generation. Each game
now has a measured cap: 90,000 cells for `netslide` (D-026), 50 regions
for `palisade` on top of the D-007 area, 40,000 cells for `inertia`
(D-008), 484 for `tents` (D-027), 10,000 for `net` (D-028), 484 for
`tracks` (D-029), a maximum number of 9 for `dominosa` (D-030), 100
regions for `map` (D-014), 400 cells for `singles` (D-031), 121 for
`pearl` (D-032), and 400 for `loopy` (D-033). `keen` needs no cap:
validation already stops at 9x9 and generation measures 0.48 s on the
phone. The caps are generation-only, so every parameter set, save, and
game id that C accepts still loads. Each of the twelve declares its
`Game.configure` and the preference schema that matches the C
`get_prefs`, and each keeps its `Review:` entry: the entry records the
owner direction of 2026-11-18 for the seventh wave in ADR 0011, which
is what cleared the review. `SeventhWaveTileSizeInputTest`
covers the twelve on the Pixel 4a 5G at two tile sizes, and every case
loads a frozen game id, so the device suite generates no board above a
cap. `solo` is not enabled: D-025 waives its parity and the app stays
on C until a separate owner decision. `cube` stays on C with its note
unchanged.

The other ported games stay `dual-run` on
C. The policy is `docs/adr/0011-enable-games-in-waves.md`.

Exit criteria per game:

- the matrix is green;
- the app has no runtime C path for the game;
- two releases without P1 bugs, or an explicit owner waiver;
- the C source may remain for the oracle and chooser metadata; removing
  it is a separate change when nothing needs it.

## Phase 5 — Polish

- performance budgets from `docs/PERFORMANCE.md`: startup, frames,
  PSS, APK, battery;
- Macrobenchmark and Baseline Profiles;
- full accessibility audit;
- UI localization (do not touch C translations until upstream #1 is
  fixed);
- final design review;
- Play release.

Exit criteria:

- budgets in `docs/PERFORMANCE.md` are met;
- regression against the baseline is ≤ 10% for startup and frames;
- no P0/P1;
- the parity suite is green on the release commit;
- `DEVIATIONS.md` is current.

## Cross-cutting tracks

These run all the time, not as separate phases:

- **Parity** — new tests and corpus.
- **Fuzz** — scheduled; results go to `testdata/`.
- **Regressions** — every fixed bug becomes a fixture.
- **Deviations** — every difference has a reason and a test.
- **Upstream sync** — on a schedule and before release.
- **Accessibility** — do not postpone it to Phase 5.
- **Performance** — measure after every phase using
  `docs/PERFORMANCE.md`; regression > 10% is a blocker.

## Phase rules

- Do not start the next phase until the exit criteria are met.
- Do not mix a mechanical move and a logic change in one PR.
- Do not remove the C implementation until the game passes the matrix
  and two releases.
- Any roadmap change goes through an ADR or a separate PR with a
  reason.

## Open debt

Work that is known to be missing, deferred, or unverified. Debt is not a
deviation: it is unfinished work. Each item names what closes it. An
item leaves this table only when the closing evidence exists, not when
the work is started.

| ID | Area | What is missing | Why it is deferred | Closes when |
|---|---|---|---|---|
| D-01 | Accessibility, P0 | The game board has no screen-reader semantics. `GameView` draws the board and exposes no virtual nodes, so TalkBack cannot read a cell or make a move. The shell is labeled; the board is not. Wave 1 landed on 2026-11-19: the `Game.spokenBoard` seam with `gridSpeech` and the 400-unit cap, the board and unit semantics in the Compose host, the live-region diff, and `tents` as the pilot (`docs/adr/0013-spoken-board.md`, D-035). Wave 2 landed on 2026-11-19 with the ten plain cell games (`fifteen`, `sixteen`, `flip`, `twiddle`, `mines`, `mosaic`, `signpost`, `rect`, `singles`, `unruly`), content-only unit labels composed by the shell, the committed spoken tables under `testdata/spoken/`, and the cross-game bounds, geometry parity, landscape, and keyboard tests. Wave 3 landed on 2026-11-19 with the colour, digit, and pencil vocabulary in the engine (`BoardColour`, `digitSpeech`, `pencilDigits`, `SPOKEN_SELECTED`) and twelve more games (`samegame`, `flood`, `filling`, `pegs`, `lightup`, `magnets`, `range`, `towers`, `unequal`, `keen`, `undead`, `dominosa`), including their off-grid clue strips. Wave 4 landed on 2026-11-19 with the fifteen games whose units are not all cells: the edges of `loopy` and `palisade`, the points of `untangle`, the palette and pegs of `guess`, the laser ring of `blackbox`, the moving bands of `netslide`, and the cells of `slant`, `pearl`, `tracks`, `bridges`, `map`, `net`, `inertia`, `galaxies`, and `pattern`. Every Kotlin game now returns a spoken board except `cube`, which has no two-dimensional unit model. The unit models, the gesture limits, and the per-game cap are in `docs/DESIGN.md` section 11, and the tests are in `docs/TESTING.md`. Wave 5 owes the full device table, the large-board decision, the C fallback record, and the D-03 listening pass. | The seam needs game state, which only the Kotlin layer has; each wave is a per-game description reviewed against the spec's grammar rather than guessed from pixels. | Every Kotlin game returns a spoken board (cells, edges, off-grid units, and the cursor), the C fallback is recorded, and a spoken audit on a device passes (D-03). |
| D-02 | Performance | Tier A is recorded on a Pixel 4a 5G at 60 Hz. Tier B and the tablet are missing. | Only one physical phone exists: no 90 Hz tier A phone, no tier B phone, and no tablet. | Tier B and tablet numbers are in `tools/perf/baseline.md` and `docs/PERFORMANCE.md` has their verdicts. |
| D-03 | Accessibility | No spoken TalkBack pass. Labels, 48dp targets, headings, contrast, RTL, and 200% are checked automatically; pronunciation, traversal order, and announcements are not. | TalkBack needs a person listening to the device. | A written audit of every shell screen with no P0/P1, attached to the phase report. |
| D-04 | Testing | Screenshot goldens are local and git-ignored by decision, so CI cannot gate pixels and a fresh checkout skips the comparison. | The owner asked not to commit binary screenshots. | Either the goldens are committed, or a host renderer (for example Paparazzi or Roborazzi) is adopted by an ADR. |
| D-06 | CI | No workflow has ever run on GitHub. `jvm`, `android`, `upstream-diff`, `oracle-current`, and `fuzz` exist and were exercised locally only. | The repository owner must push and start the first run. | Five green runs on GitHub, with branch protection requiring them. |
| D-07 | CI | No emulator job. Instrumentation and the accessibility checks run on the local `puzzles_api36` AVD only. | An emulator workflow is unverified until it runs on GitHub. | The Android job starts an AVD and runs `connectedDebugAndroidTest` green on GitHub. |
| D-09 | Performance | The Compose renderer missed the 60 Hz frame budget on the physical tier A device: fifteen 4x4, P50 73 ms and P90 109 ms per move on a new board, 650 ms P50 at about 200 moves, 100% janky frames. The recorded scene held every pass of the session, so a frame grew with the move count. | Fixed on 2026-11-18 (`BoardRecorder` keeps one pass; the shell forces a full repaint before each pass). Measured after the fix on the same phone: P50 17 ms and P99 18 ms on a new board, P50 12 ms and P99 14 ms at 100 and 200 moves, janky 0–1% on a warm session. The new-board case misses 16.7 ms by 1–2 ms, and the GPU composition alone is 11 ms there. | A physical device meets the frame budget on a new board too, and the P90 regression against the bitmap renderer is at most 10%, or the renderer is optimized to that level on the reference AVD. |
