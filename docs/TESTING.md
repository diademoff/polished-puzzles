# Testing

This document describes test levels, tools, the corpus, and CI.
The parity contract is in `PARITY.md`; commands are in `AGENTS.md`.

## 1. Levels

| Level | Where | Time | What it checks |
|---|---|---|---|
| Unit (JVM) | `app/src/test`, `parity/src/test` | seconds | pure logic, parsers, normalizers |
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
undo/redo(state)        -> state + flags
serialise(state)        -> savefile
load(savefile)          -> state
solve(state)            -> state
draw(state)             -> primitives
```

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
├─ saves/                    # .sgtp fixtures, including old versions
├─ regressions/<game>/       # issue-*.json: seed, params, moves, expected
├─ corpus/<game>/            # fuzz corpus, minimized
├─ golden/<game>/            # traces, colors, keys, presets
└─ manifest.json             # sha256, provenance, upstream commit
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
- only `random.c`-compatible RNG;
- stable and explicit sorting;
- fixed time in tests.

## 8. Goldens and screenshots

- Geometry goldens are JSON/JSON.gz and are diffed in the PR.
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
- `ChooserScreenshotsTest` runs the chooser through a Compose rule, but
  only under fastlane: it skips itself when the `device_type`
  instrumentation argument is absent. It is a screenshot producer, not a
  behavior gate.
- Pixel screenshot comparison is open. The fastlane screenshots are
  store artifacts, not a regression gate. A comparison needs a
  deterministic device or a host renderer, so the phase exit criterion
  "screenshot tests are green" is still debt.

Device run on the API 36 emulator, 2026-11-14:

- 49 tests, 45 passed, 3 skipped (the fastlane screenshot tests skip
  themselves without the fastlane argument), 1 failed.
- The failure is `GamePlayParameterizedTest[Palisade 2x3n3:d33]`: the
  key sequence does not complete the puzzle on this emulator. The same
  failure exists at commit `1cd9f4cb`, before the Compose shell, so it
  is not a shell regression. Keep the case visible; do not delete it to
  make the suite green.
- The four chooser screen tests pass.

## 9. CI

Jobs:

1. **jvm** — unit + parity against the pinned upstream.
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
