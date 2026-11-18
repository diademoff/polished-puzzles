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
2026-11-18 (the fourth enablement wave). The configuration dialog comes
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
`undead`, `rect`, `slant`, `range`, `towers`, and `unequal`, and bypasses the C
generator for them. `KotlinGamesTest` pins the enabled set and the
generated saves; `GamePlayParameterizedTest` asserts that the running
engine is `KotlinGameEngine` for every enabled game,
`FirstWaveTileSizeInputTest` covers the first wave,
`SecondWaveTileSizeInputTest` covers the second wave,
`ThirdWaveTileSizeInputTest` covers the third wave, and
`FourthWaveTileSizeInputTest` covers the fourth, each with real
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
- size limits stay warning-only (decision 2b); no wave adds a hard cap.

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
| D-01 | Accessibility, P0 | The game board has no screen-reader semantics. `GameView` draws from C and exposes no virtual nodes, so TalkBack cannot read a cell or make a move. The shell is labeled; the board is not. | The Kotlin layer has no game state. Per-cell nodes need the engine seam, which is the Phase 2 renderer and the Phase 3 state mirror. | The board exposes cells, edges, and the cursor as virtual nodes, and a spoken audit on a device passes. |
| D-02 | Performance | No absolute baseline. Only emulator numbers exist in `tools/perf/baseline.md`. | No physical tier A, tier B, or tablet device is available. | The device table in `tools/perf/baseline.md` is filled and `docs/PERFORMANCE.md` has the verdicts. |
| D-03 | Accessibility | No spoken TalkBack pass. Labels, 48dp targets, headings, contrast, RTL, and 200% are checked automatically; pronunciation, traversal order, and announcements are not. | TalkBack needs a person listening to the device. | A written audit of every shell screen with no P0/P1, attached to the phase report. |
| D-04 | Testing | Screenshot goldens are local and git-ignored by decision, so CI cannot gate pixels and a fresh checkout skips the comparison. | The owner asked not to commit binary screenshots. | Either the goldens are committed, or a host renderer (for example Paparazzi or Roborazzi) is adopted by an ADR. |
| D-06 | CI | No workflow has ever run on GitHub. `jvm`, `android`, `upstream-diff`, `oracle-current`, and `fuzz` exist and were exercised locally only. | The repository owner must push and start the first run. | Five green runs on GitHub, with branch protection requiring them. |
| D-07 | CI | No emulator job. Instrumentation and the accessibility checks run on the local `puzzles_api36` AVD only. | An emulator workflow is unverified until it runs on GitHub. | The Android job starts an AVD and runs `connectedDebugAndroidTest` green on GitHub. |
| D-09 | Performance | The Compose renderer's frame budget is measured on the emulator only. P50 matches the old bitmap renderer; P90/P99 are worse on `swiftshader_indirect`. | The absolute budget needs a physical device, and the renderer may need a retained-surface optimization once hardware evidence exists. | A physical device meets the frame budget and the P90 regression is at most 10%, or the renderer is optimized to that level on the reference AVD. |
