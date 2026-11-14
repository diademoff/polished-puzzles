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
