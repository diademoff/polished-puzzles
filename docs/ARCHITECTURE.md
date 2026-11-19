# Architecture

This document describes the current structure of the project and the
target structure that the roadmap moves toward. If the document and
the code disagree, the code is right, but the document must be updated
in the same commit.

## 1. Layers

```
┌────────────────────────────────────────────────────────────────┐
│ Android UI (Kotlin, Compose)                                   │
│ chooser, toolbar, dialogs, settings, help, buttons, board      │
├────────────────────────────────────────────────────────────────┤
│ GameEngine (Kotlin interface)                                  │
│ contract between the UI and one backend                        │
├───────────────────────────────┬────────────────────────────────┤
│ KotlinGameEngine              │ GameEngineImpl (JNI)           │
│ owns Midend, draws through    │ C engine and android.c; no     │
│ DrawSink -> ViewCallbacks     │ shipped backend selects it     │
├───────────────────────────────┴────────────────────────────────┤
│ :engine Kotlin Multiplatform core                              │
│ 40 games, midend, RNG, save container, grid, solver helpers    │
├────────────────────────────────────────────────────────────────┤
│ Vendored C (app/src/main/jni)                                  │
│ oracle, chooser metadata, backend identification, prefs text   │
└────────────────────────────────────────────────────────────────┘
```

The point of the split: **game semantics do not know about Android**,
and Android does not know about game rules. Everything that crosses
the border is described by a contract: `GameEngine`, `ViewCallbacks`,
`SAVEFILE`, and the oracle protocol.

`engine/` is a Kotlin Multiplatform module with `commonMain` code and a
JVM target for the parity tests. The app depends on it
(`implementation(project(":engine"))` in `app/build.gradle.kts`), and
`KotlinGames.handles` is true for all 40 ported games, so every shipped
backend runs on the Kotlin engine. The C engine is no longer a gameplay
backend. The C tree stays because the oracle is built from it, the
chooser metadata is generated from `CMakeLists.txt`, a saved game is
identified through `GameEngineImpl.identifyBackend`, and the preference
text is stored and read through the same JNI calls. The C engine is
reachable in production only if `KotlinGames.handles` returns false for
a backend, and no backend does. See
`docs/adr/0005-engine-infrastructure-port.md` and
`docs/adr/0011-enable-games-in-waves.md`.

## 2. Current layout

```
app/src/main/
├─ kotlin/name/boyle/chris/sgtpuzzles/  # UI and Android logic
│  ├─ GameChooser.kt                    # chooser activity and preferences
│  ├─ backend/KotlinGameEngine.kt       # Kotlin backend, KotlinGames registry
│  ├─ backend/GameEngineImpl.kt         # JNI implementation and C fallback
│  ├─ chooser/ChooserScreen.kt          # Compose chooser screen
│  ├─ ui/Theme.kt                       # Compose theme over the shell palette
│  ├─ GamePlay.kt                       # game screen
│  ├─ GameView.kt                       # input, gestures, board view
│  ├─ buttons/Buttons.kt                # Compose button bar
│  ├─ backend/GameEngine.kt             # engine contract
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
│  ├─ Game.kt                           # game seam and drawing sink
│  ├─ Midend.kt                         # history and save semantics
│  ├─ RandomState.kt                    # bit-exact random.c
│  ├─ SgtpSave.kt                       # SAVEFILE container
│  ├─ Sort.kt, Combinations.kt, Dsf.kt  # deterministic helpers
│  ├─ Tdq.kt, FindLoop.kt, Matching.kt, Tree234.kt, Divvy.kt
│  ├─ Grid.kt, Penrose.kt, Hat.kt, Spectre.kt
│  ├─ Loopgen.kt, Latin.kt              # generator and solver helpers
│  ├─ PortedGames.kt                    # the one list of ported games
│  ├─ 40 game files                     # BlackBox.kt ... Untangle.kt
│  ├─ SpokenBoard.kt, Cancellation.kt   # accessibility seam and cancel token
│  └─ Prefs.kt, ConfigItem.kt           # preference schema and dialogs
└─ commonTest/kotlin/.../engine/        # fixed vectors and format tests
```

`buildSrc/` generates `BackendNames.kt` from `CMakeLists.txt` and the
resources. `fastlane/` holds the store metadata.

## 3. Repository layout

```
app/            # Android UI; depends on :engine
engine/         # Kotlin Multiplatform core: 40 games and the helpers
parity/         # JVM tests and the oracle client
tools/oracle/   # host build of the C oracle
testdata/       # saves, goldens, traces, spoken tables
docs/           # contracts and decisions
```

Why this shape:

- **`app/` vs `engine/`** separates the shipped UI from the portable
  logic. `app/` depends on `:engine`, and `KotlinGames` selects the
  Kotlin backend for all 40 ported games. The C implementation stays
  behind the same `GameEngine` seam only for a backend that has no
  Kotlin port and for the D-01 fallback test; no shipped backend takes
  that path, and the C tree itself is the oracle.
- **`parity/` as a separate JVM module** means the tests need no
  Android and no emulator. They run in seconds and exercise the same
  `:engine` module the app ships.
- **`tools/oracle/`** is a development tool. It does not ship in the APK.
- **`testdata/`** outlives the tests, is reviewed separately, and may
  be large.
- **`docs/`** holds contracts, not code. `AGENTS.md` stays short and
  links here.

`app/src/main/jni` keeps the upstream tree layout. `GenerateBackendsTask`
reads `jni/CMakeLists.txt` and `jni/<game>.c` there, so the tree is not
moved.

## 4. Key seams

### GameEngine

`app/src/main/kotlin/.../backend/GameEngine.kt` is the only contract
the UI sees. Behind it are `KotlinGameEngine` for every shipped
backend and `GameEngineImpl` for the C engine, which no shipped backend
selects. A game is ported when both implementations pass the parity
matrix; the app selects it only in an enablement wave.

### Kotlin backend

`KotlinGames` maps a `BackendName` to a Kotlin `Game` and is the single
selection point. `GameEngineImpl.fromLaunch` returns
`KotlinGameEngine` for a selected game; `GamePlay.startGameGeneration`
uses `KotlinGames.generate` on the generator worker instead of the
generator process. `KotlinGameEngine` implements `GameEngine`, owns a
`Midend`, and forwards drawing to the same `ViewCallbacks` seam as C.
The design and the `kotlin` status definition are in
`docs/adr/0008-run-fifteen-on-kotlin.md` and
`docs/adr/0011-enable-games-in-waves.md`.

### Kotlin game and midend

`engine/Game.kt` is the Kotlin counterpart of the upstream `struct
game`. A game owns its parameters, description generator, state, moves,
solver, text, status, keys, and drawing. `engine/Midend.kt` owns the
history and the `.sgtp` records. Forty game objects implement the seam;
`PortedGames` is the list the app and the parity suite share.

`Midend.newGame` and `Midend.fromGameId` build state 0 from the decoded
short (long-term) parameters, as `midend_new_game` does
(`midend.c:628`); `Midend.fromSave` builds it from `CPARAMS`. A game
may keep a play-time parameter out of the short encoding, which is why
Keen's new game draws its operator and a loaded save does not. The
rule is shared, so every game's tests and frozen traces cover it.

`DrawSink` is the Kotlin drawing seam. A game records the same
primitives as the C game, and a game's trace test compares the result
with the frozen traces through `TraceNormalizer`. In the app the sink
of `KotlinGameEngine` writes to `ViewCallbacks`, so the Kotlin game and
the C engine feed the same renderer.

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
only the `none` and `count` biases. A one-face grid is rejected before
the first draw, because upstream `can_colour_face` does not return
(D-020). `pearl` is the first game consumer
of both libraries; its own bias is not available in the oracle, so
`PearlBiasTest` rebuilds the score from scratch on every call of a real
generation and compares it with the incremental context, while the
nine golden entries and the save corpus pin the result.
D-019 records the three upstream buffer overruns that pearl defines
(the drag path, the self-crossing drag extension, and the clue-removal
list); the port sizes both buffers, stops the extension at a visited
cell, and the oracle is not asked about any of the three paths. The
generator keeps the C
cost and the C parameter bound: `13x13de` takes seconds and `14x14` or
larger does not finish in the measured budget, so the app must generate
off the UI thread; a size limit is an owner decision (D-019).
`Game`, `Midend`, and `Fifteen` were the first Phase 4 slice; every
ported game now uses them. See `docs/adr/0007-kotlin-game-seam.md`.
The semantic midend is in Kotlin and serves every shipped backend; the
shell parts of the C midend stay in C only for the fallback path.

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

`GameGenerator` still starts `libpuzzlesgen.so` as a separate process
for a backend that `KotlinGames.handles` returns false. No shipped
backend takes that path. A Kotlin game generates in-process on the same
worker (`GameGenerator.submit`), and cancel is a `Cancellation` token
that the generator checks between attempts. In Phase 0 the same C tree
became the base of the host oracle.

## 5. Game launch flow

```
GameChooser
  -> GameLaunch (game, params, seed, saved state)
  -> GamePlay.startGameGeneration
       KotlinGames.handles(backend) == true
         -> GameGenerator.submit (worker thread)
         -> KotlinGames.generate -> Midend.newGame(...).serialise()
       else (no shipped backend)
         -> GameGenerator.generate -> libpuzzlesgen.so process
  -> startGameConfirmed
  -> GameEngineImpl.fromLaunch
  -> KotlinGameEngine (owns Midend)
  -> user input -> KotlinGameEngine.keyEvent -> Midend.processKey
  -> DrawSink -> ViewCallbacks -> BoardRecorder
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
| `engine/src` | project | yes |
| `parity/src` | project | yes |
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
