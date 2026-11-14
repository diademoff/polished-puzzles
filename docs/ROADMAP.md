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

Status: started on 2026-11-14 by owner decision while D-09 is still
open; Phase 3 does not touch the renderer and does not change the D-09
evidence. The `:engine` Kotlin Multiplatform module exists. Ported and
pinned: `random.c` (`RandomParityTest`), the `SAVEFILE` container with a
committed corpus (`SaveParityTest`), `arraysort`, and `combi`
(`DeterministicHelpersParityTest`). Still C: `midend`, `dsf`, `tdq`,
`grid`, `latin`, `findloop`, `loopgen`, and game logic. `seed -> DESC` remains oracle-backed until
Phase 4 ports each generator; the existing `GoldenDescTest` is green.
The porting order is in `docs/adr/0005-engine-infrastructure-port.md`.

## Phase 4 — Games

One game at a time, in dual-run mode, behind a feature flag.

Order:

1. `fifteen` — the whole pipeline, has a solver, no dependencies.
2. `slant` or `mines` — grid, generation, undo.
3. `net` — `loopgen`, complex drawing, no solver.
4. `loopy` — the boss: 18 grids, aperiodic tilings, solver.

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

Exit criteria per game:

- the matrix is green;
- two releases without P1 bugs;
- the feature flag is on by default;
- only then remove the C implementation.

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
