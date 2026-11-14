# Performance

The goal is not to "optimize everything". The goal is to make the app
**responsive**: instant touch feedback, smooth frames, fast start, no
ANRs, and no freezes during generation. "Blazing fast" means measurable
budgets, not a subjective feeling.

This document defines budgets, layer rules, the measurement method, and
CI gates. The numbers are initial targets. After Phase 0 they are
refined against the baseline and fixed in an issue or ADR.

## 1. Principles

1. **Only the release build counts.** A debug build is not a benchmark.
2. **P90/P99, not averages.** Averages hide freezes.
3. **Same scene, device, and build.** Otherwise the comparison is
   meaningless.
4. **Measure first, optimize second.** Do not change code without a
   profile.
5. **Do not trade correctness for speed.** Parity, seed, and the save
   format come first.
6. **A budget is part of the definition of done.** A change that breaks
   a budget does not merge without an explanation.

## 2. Reference devices

| Tier | Example | Purpose |
|---|---|---|
| A | Pixel 4a/6a class, 90Hz | main absolute budget |
| B | 2 GB RAM, Android 8–9, 60Hz | lower bound for `minSdk 21` |
| Tablet | 10", 60Hz | adaptive layouts |

An emulator is fine for relative CI checks and screenshots, but not for
absolute numbers. Use the same AVD and host for relative comparisons,
cold boot, and no snapshots. Absolute budgets are measured on a
physical device.

## 3. Budgets

All values are for the release build, R8 enabled, screen on, device
charged, and "do not disturb" enabled.

| Metric | Budget | Condition |
|---|---|---|
| Cold start TTID | P90 ≤ 1000ms (A), ≤ 2000ms (B) | to the first frame |
| Warm start | P90 ≤ 400ms | app already in memory |
| First frame of a saved game | ≤ 300ms | no generation |
| Frame time | 99% ≤ 16.7ms @60Hz | gameplay, no jank |
| Dropped frames | < 1% | 60Hz, 10-minute session |
| Worst frame | ≤ 32ms | except generation/loading |
| Touch-to-visible | P95 ≤ 100ms | tap on the board |
| Main-thread block | ≤ 16ms | any operation |
| Generation progress | ≤ 100ms after request | UI does not hang |
| Generation cancel | ≤ 200ms | Cancel button |
| Save/load state | ≤ 50ms typical, ≤ 200ms max | otherwise move to background |
| PSS | baseline + 10% | typical session |
| GC in steady state | no frame-driven collections | gameplay |
| APK/AAB size | baseline + 5% | without justification |
| Battery | no measurable regression | 30-minute scenario |
| Help | ≤ 300ms to content | local WebView |

Puzzle generation is a separate case. Hard presets can take seconds.
For them the budget is not "fast"; it is "does not block the UI, shows
progress, and can be cancelled". Per-game P95 is recorded in Phase 0
and must not get worse.

## 4. Measurement method

Tools:

- **Macrobenchmark** — startup, frame timing, Baseline Profiles;
- **Perfetto / systrace** — causes of jank and thread blocks;
- **`dumpsys gfxinfo` / FrameMetrics** — frame distribution;
- **Android Studio Profiler** — CPU, memory, allocations;
- **APK Analyzer** — size, duplicates, resources;
- **StrictMode** (debug) — disk and network operations on the main
  thread.

Rules:

- 10 runs; report P50/P90/P99 and the worst frame;
- record device, build, scenario, and temperature;
- do not compare different Android versions or devices;
- keep traces in `build/perf/` and attach them to the PR;
- a regression above 10% blocks the release. It is not noise.

## 5. Layer rules

### 5.1 Startup

- No disk I/O, parsing, or generation on the main thread before the
  first frame.
- Load the game list, icons, and help lazily.
- Audit heavy dependencies and remove unused ones. Candidate:
  `kotlin-reflect`.
- Use Baseline Profiles and `profileinstaller` in release.
- Do not initialize anything that the first screen does not need.

### 5.2 Compose

- Use stable keys in lists and `remember` for heavy objects.
- Use `derivedStateOf` instead of recomputing on every recomposition.
- Do not create objects in the composition body inside frame loops.
- Do not animate layout properties when transform/alpha is enough.
- Use lazy lists for the chooser and long settings screens.
- Do not keep heavy state in `mutableStateOf` if it does not affect
  the UI.

### 5.3 Game board and renderer

- Reuse `Paint`, `Path`, `Matrix`, and `Bitmap`.
- Do not allocate in `onDraw` or in `drawing_api` callbacks.
- Recreate a Bitmap only when the size really changes.
- Do not enable hardware layers without a reason.
- Use `postInvalidateOnAnimation` for frame sync, not more than once
  per frame.
- Do not copy the full Bitmap on every move if a region can be
  invalidated.
- Check blitter and clip for unnecessary copies.

### 5.4 Engine and generation

- Generate in a separate process (`libpuzzlesgen.so`), not in the UI.
- Serialize and deserialize large state in the background.
- Do not copy large arrays without a reason.
- Cancel must really kill the generation process.
- Timers run only while the screen is active. No wake locks.

### 5.5 Storage

- Use `SharedPreferences.apply()` instead of `commit()` on the UI
  thread.
- Do not store large blobs in prefs; keep only the needed game state.
- Saving in `onPause` must take less than 50ms. Otherwise move it to
  the background and show that the game is saved.

### 5.6 Native and packaging

- Use `-O2`/`-Os`, LTO, and strip for release. Do not change vendored C.
- Do not pack unnecessary ABIs into one artifact; use an App Bundle.
- `useLegacyPackaging = true` is needed for `libpuzzlesgen.so`. Measure
  size and extraction time before changing it.
- Keep R8 and resource shrinking on. Check that JNI code is not removed.
- Assets: no duplicates, no unused icons or documentation.

### 5.7 Battery

- No background services, network, or periodic jobs.
- The game timer ticks only while the screen is visible.
- Animations stop when the app goes to the background.

## 6. CI and gates

- **Nightly perf job** on a physical device or a stable emulator:
  startup, frame timing, APK size.
- **PR gate** — stable metrics only (APK size, lint, unit benchmarks).
  Unstable metrics are advisory.
- **Release gate** — budgets from section 3 and no regression above
  10%.
- Results are stored as artifacts and compared with the baseline.
- Baseline Profiles are generated on the release build and checked by
  Macrobenchmark.

## 7. Anti-patterns

- measuring a debug build;
- looking at averages instead of P90/P99;
- optimizing without a trace;
- allocating in `onDraw`;
- recreating a Bitmap on every frame or move;
- generating or serializing on the main thread;
- warming everything up at startup "just in case";
- adding a library for one function;
- disabling R8 "so it does not break";
- adding a wake lock for a timer;
- caching without a size limit;
- hiding jank behind an animation.

## 8. Definition of done

For a change that affects UI, rendering, startup, or generation:

- [ ] Before/after measurement on a reference device exists.
- [ ] Budgets from section 3 are not exceeded.
- [ ] No new allocations in the frame.
- [ ] No main-thread block above 16ms.
- [ ] No APK regression above 5%.
- [ ] The trace or report is attached to the PR.

## 9. Phase mapping

- **Phase 0** — baseline: cold/warm start, jank, APK, build time.
  Record numbers and devices. The procedure is in
  `tools/perf/baseline.md`. The baseline is not recorded yet: no
  reference device is available, so this exit criterion is open.
- **Phase 1** — do not make startup worse after the Compose shell. The
  shell work started before the Phase 0 baseline was recorded, as an
  explicit decision: no device was available and the shell does not
  touch the engine. The baseline must be captured on the first
  available device, and the Phase 1 comparison is made against it. Until
  then, Phase 1 cannot close.
- **Phase 2** — the renderer is not worse than the baseline on frames.
- **Phase 3** — the KMP core is not slower than C on the parity corpus.
- **Phase 4** — every game passes the frame and time budgets.
- **Phase 5** — final budgets, Macrobenchmark, Baseline Profiles.
