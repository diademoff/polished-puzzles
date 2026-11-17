# Architecture

This document describes the current structure of the project and the
target structure that the roadmap moves toward. If the document and
the code disagree, the code is right, but the document must be updated
in the same commit.

## 1. Layers

```
┌────────────────────────────────────────────────────────────────┐
│ Android UI (Kotlin, Compose)                                   │
│ chooser, toolbar, dialogs, settings, help, buttons             │
├────────────────────────────────────────────────────────────────┤
│ GameEngine (Kotlin interface)                                  │
│ contract between UI and engine                                 │
├────────────────────────────────────────────────────────────────┤
│ JNI bridge                                                     │
│ GameEngineImpl <-> android.c                                   │
├────────────────────────────────────────────────────────────────┤
│ Android frontend (C)                                           │
│ android.c: drawing_api, keys, config, serialise, timer         │
├────────────────────────────────────────────────────────────────┤
│ Upstream core (C, vendored)                                    │
│ midend, games, grid, loopgen, solvers, random, save format     │
└────────────────────────────────────────────────────────────────┘
```

The point of the split: **game semantics do not know about Android**,
and Android does not know about game rules. Everything that crosses
the border is described by a contract: `GameEngine`, `ViewCallbacks`,
`SAVEFILE`, and the oracle protocol.

`engine/` is the portable Kotlin core under construction. It is a
Kotlin Multiplatform module with `commonMain` code and a JVM target for
the parity tests. The app does not depend on it yet: C still owns the
runtime. The first ported pieces are `RandomState` and `SgtpSave`; see
`docs/adr/0005-engine-infrastructure-port.md`.

## 2. Current layout

```
app/src/main/
├─ kotlin/name/boyle/chris/sgtpuzzles/  # UI and Android logic
│  ├─ GameChooser.kt                    # chooser activity and preferences
│  ├─ backend/KotlinGameEngine.kt       # Kotlin backend selection and adapter
│  ├─ chooser/ChooserScreen.kt          # Compose chooser screen
│  ├─ ui/Theme.kt                       # Compose theme over the shell palette
│  ├─ GamePlay.kt                       # game screen
│  ├─ GameView.kt                       # Canvas + gestures + renderer
│  ├─ buttons/Buttons.kt                # Compose button bar
│  ├─ backend/GameEngine.kt             # engine contract
│  ├─ backend/GameEngineImpl.kt         # JNI implementation
│  ├─ config/                           # settings dialogs
│  └─ launch/                           # launch and game generation
├─ jni/
│  ├─ android.c, android.h              # our Android frontend
│  ├─ CMakeLists.txt                    # shared build
│  ├─ *.c, *.h                          # vendored upstream
│  ├─ cmake/platforms/android.cmake     # Android settings
│  ├─ executable/android-gen.c          # puzzlesgen
│  └─ icons/, unfinished/, auxiliary/   # data and auxiliary targets
├─ res/                                 # layouts, strings, themes
└─ assets/{en,de}/                      # offline help
```

```
engine/src/
├─ commonMain/kotlin/name/boyle/chris/sgtpuzzles/engine/
│  ├─ RandomState.kt                    # bit-exact random.c
│  ├─ SgtpSave.kt                       # SAVEFILE container
│  ├─ Sort.kt                           # bit-exact arraysort
│  ├─ Combinations.kt                   # bit-exact combi order
│  ├─ Dsf.kt                            # disjoint-set forest
│  ├─ Tdq.kt                            # de-duplicating to-do queue
│  ├─ FindLoop.kt                       # Tarjan bridge finding
│  ├─ Matching.kt                       # Hopcroft-Karp matching
│  ├─ Tree234.kt                        # tree234 public behavior
│  ├─ Divvy.kt                          # random k-omino rectangle divider
│  ├─ Game.kt                           # game seam and drawing sink
│  ├─ Midend.kt                         # history and save semantics
│  └─ Fifteen.kt                        # first ported game
└─ commonTest/kotlin/.../engine/        # fixed vectors and format tests
```

`buildSrc/` generates `BackendNames.kt` from `CMakeLists.txt` and the
resources. `fastlane/` holds the store metadata.

## 3. Target layout

```
app/            # Android UI only; will depend on :engine
engine/         # Kotlin Multiplatform core (RNG, save container)
parity/         # JVM tests and the oracle client
tools/oracle/   # host build of the C oracle
testdata/       # saves, regressions, corpus, goldens
docs/           # contracts and decisions
```

Why this shape:

- **`app/` vs `engine/`** separates the shipped UI from the portable
  logic. `app/` depends on `:engine`; `KotlinGames` selects the Kotlin
  backend for `fifteen`, and the rest of the games still reach C
  through the same `GameEngine` seam.
- **`parity/` as a separate JVM module** means the tests need no
  Android and no emulator. They run in seconds and are reused by the
  future `engine/`.
- **`tools/oracle/`** is a development tool. It does not ship in the APK.
- **`testdata/`** outlives the tests, is reviewed separately, and may
  be large.
- **`docs/`** holds contracts, not code. `AGENTS.md` stays short and
  links here.

Moving `app/src/main/jni` into `jni/upstream/` + `jni/android/` is a
separate mechanical commit after Phase 0. It touches
`GenerateBackendsTask` (it reads `jniDir/CMakeLists.txt` and
`jniDir/<game>.c`) and the paths in `app/build.gradle.kts`.

## 4. Key seams

### GameEngine

`app/src/main/kotlin/.../backend/GameEngine.kt` is the only contract
the UI sees. Behind it can be a C implementation, a Kotlin
implementation, or a test stub. This is the dual-run point: a game is
ported only when both implementations pass the parity matrix.

### Kotlin backend

`KotlinGames` maps a `BackendName` to a Kotlin `Game` and is the single
selection point. `GameEngineImpl.fromLaunch` returns
`KotlinGameEngine` for a selected game; `GamePlay.startGameGeneration`
uses `KotlinGames.generate` instead of the generator process.
`KotlinGameEngine` implements `GameEngine`, owns a `Midend`, and
forwards drawing to the same `ViewCallbacks` seam as C. The design and
the `kotlin` status definition are in
`docs/adr/0008-run-fifteen-on-kotlin.md`.

### Kotlin game and midend

`engine/Game.kt` is the Kotlin counterpart of the upstream `struct
game`. A game owns its parameters, description generator, state, moves,
solver, text, status, keys, and drawing. `engine/Midend.kt` owns the
history and the `.sgtp` records. `engine/Fifteen.kt` is the first
implementation.

`Midend.newGame` and `Midend.fromGameId` build state 0 from the decoded
short (long-term) parameters, as `midend_new_game` does
(`midend.c:628`); `Midend.fromSave` builds it from `CPARAMS`. A game
may keep a play-time parameter out of the short encoding, which is why
Keen's new game draws its operator and a loaded save does not. The
rule is shared, so every game's tests and frozen traces cover it.

`DrawSink` is the Kotlin drawing seam. `Fifteen` records the same
primitives as the C game, and `FifteenTraceTest` compares the result
with the frozen traces through `TraceNormalizer`. The app still draws
through `ViewCallbacks` and `BoardRecorder`; the two seams are adapted
when the app selects a Kotlin game.

### ViewCallbacks

C draws with primitives: lines, polygons, circles, text, blitter, and
clip. `BoardRecorder` implements `ViewCallbacks` and turns them into a
`BoardFrame` display list. `BoardCanvas` draws that list on a Compose
Canvas. `GameView` keeps input, zoom, and the view transform; it no
longer draws the board in production. The design is in
`docs/adr/0004-compose-display-list-renderer.md`. Geometry is checked
against the oracle; the theme does not change the board.

### SAVEFILE

`midend_serialise` is the canonical state: parameters, seed, move
history, and the position in that history. It is both the exchange
format with other platforms and the main parity oracle.
`engine/Sort.kt` ports upstream `arraysort()`, a heapsort whose tie
order is observable, and `engine/Combinations.kt` ports `combi.c`.
`DeterministicHelpersParityTest` compares both with the oracle.
`Dsf`, `Tdq`, `FindLoop`, `Matching`, `Tree234`, and `Latin` are the
solver utilities listed in `docs/adr/0006-port-libraries-with-consumer.md`.
`Latin.kt` ports `latin.c` with the cube layout, the strategy restart
loop, the recursion and context copy protocol, and the first-solution
write-back. `LatinParityTest` compares generation, the encoded RNG
state, and the solver difficulty at every `maxdiff` with the oracle;
`keen` is the first game consumer of its callback layer.
`Grid.kt`, `Penrose.kt`, `Hat.kt`, and `Spectre.kt` port `grid.c`.
`GridParityTest` compares the description generator, the validated
description, `grid_new`, `grid_compute_size`, the nearest edge, and the
incentre of every type with the `grid` oracle op. The aperiodic patches
(Penrose P2 and P3, hats, spectres) are compared cell by cell, so a
wrong substitution or trimming rule changes a `loopy` or `pearl` id.
`Loopgen.kt` ports `loopgen.c`, including the candidate order, the bias
calls, and the two flip passes. `LoopgenParityTest` compares the board
and the encoded RNG state with the `loopgen` oracle op, which offers
only the `none` and `count` biases. `pearl` is the first game consumer
of both libraries; its own bias is not available in the oracle, so
`PearlBiasTest` rebuilds the score from scratch on every call of a real
generation and compares it with the incremental context, while the
nine golden entries and the save corpus pin the result.
D-019 records the two upstream buffer overruns that pearl defines (the
drag path and the clue-removal list); the port sizes both buffers and
the oracle is not asked about either path. The generator keeps the C
cost and the C parameter bound: `13x13de` takes seconds and `14x14` or
larger does not finish in the measured budget, so the app must generate
off the UI thread; a size limit is an owner decision (D-019).
`Game`, `Midend`, and `Fifteen` are the first Phase 4 slice; see
`docs/adr/0007-kotlin-game-seam.md`. The semantic midend is in Kotlin;
the shell parts of the C midend stay in C until the app uses a Kotlin
game.

`engine/SgtpSave.kt` is a lossless container for the same text. It
keeps repeated moves and unknown records in order and writes canonical
bytes. `SaveParityTest` makes both implementations read the other
side's output across the corpus in `testdata/saves/`.

### Random

`engine/RandomState.kt` is a bit-exact port of upstream `random.c`.
The oracle exposes `randomBits` and `randomUpto`, and
`RandomParityTest` compares streams, every bit width, many limits, and
encoded-state transfer in both directions.

### puzzlesgen

`GameGenerator` starts `libpuzzlesgen.so` as a separate process so
generation does not block the UI. In Phase 0 the same binary becomes
the base of the host oracle.

## 5. Game launch flow

```
GameChooser
  -> GameLaunch (game, params, seed, saved state)
  -> GameGenerator (process: libpuzzlesgen.so)
  -> startPlayingIntGameID (JNI)
  -> midend_new / midend_game_id / midend_new_game
  -> GameView receives ViewCallbacks
  -> user input -> GameEngine.keyEvent -> midend_process_key
  -> draw -> android_draw_* -> ViewCallbacks -> BoardRecorder
  -> BoardCanvas -> Compose Canvas
```

## 6. Save flow

```
onPause
  -> GameEngine.serialise(ByteArrayOutputStream)
  -> midend_serialise
  -> SharedPreferences (SAVED_GAME_PREFIX + backend)
```

Old saves must load after any update. This is checked by the
`testdata/saves/` corpus and by cross-compatibility tests.

## 7. Parity flow

```
test case (game, params, seed, keys)
  -> oracle (C)  -> state trace + draw trace
  -> candidate   -> state trace + draw trace
  -> normalizer  -> compare at the declared level
```

The oracle is a normal process and speaks line-based JSON. This lets
the tests run on the JVM without an emulator and isolates crashes.

## 8. Code ownership

| Area | Owner | May change |
|---|---|---|
| `jni/*.c` except the Android frontend | upstream | only through sync/patch + deviation |
| `jni/android.c`, `android.h` | project | yes |
| `jni/executable/android-gen.c` | project | yes |
| `jni/cmake/platforms/android.cmake` | project | yes |
| `jni/icons/android_icons.sh` | project | yes |
| `jni/icons/{loopy_8x14,solo_4x4,tracks_15x10}.sav` | project | yes |
| `jni/UPSTREAM-FILES.txt`, `jni/UPSTREAM-PATCHES.txt` | generator | no; change the source only |
| `app/src/main/kotlin` | project | yes |
| `app/src/main/res` | project | yes |
| `BackendNames.kt` | generator | no; change the source only |
| `app/src/main/assets` | upstream docs | generated from `assets-sources` |

The authoritative machine-readable form of the first two rows is
`tools/vendored-project-files.txt`, used by
`tools/gen-vendored-manifest.sh`. The vendored files themselves are
pinned by sha256 in `app/src/main/jni/UPSTREAM-FILES.txt`; the files
that differ from upstream are listed in `UPSTREAM-PATCHES.txt`
(deviation D-001).

## 9. Constraints

- `minSdk 21`: use new APIs only with graceful degradation.
- One APK, offline, minimum permissions.
- `versionCode`/`versionName` live in `app/build.gradle.kts`.
- The night theme and the game palette are different things.
- The game board does not move to declarative Compose layout: it stays
  a canvas, even after the shell is modernized.
