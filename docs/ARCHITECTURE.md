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

## 2. Current layout

```
app/src/main/
├─ kotlin/name/boyle/chris/sgtpuzzles/  # UI and Android logic
│  ├─ GameChooser.kt                    # game chooser
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

`buildSrc/` generates `BackendNames.kt` from `CMakeLists.txt` and the
resources. `fastlane/` holds the store metadata.

## 3. Target layout

```
app/            # Android UI only; depends on :engine
engine/         # future Kotlin Multiplatform core
parity/         # JVM tests and the oracle client
tools/oracle/   # host build of the C oracle
testdata/       # saves, regressions, corpus, goldens
docs/           # contracts and decisions
```

Why this shape:

- **`app/` vs `engine/`** separates the shipped UI from the portable
  logic. While `engine/` does not exist, the C engine stays behind the
  `GameEngine` seam.
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

### ViewCallbacks

C draws with primitives: lines, polygons, circles, text, blitter, and
clip. `GameView` turns them into a Bitmap/Canvas. The target renderer
is a Compose Canvas that accepts the same stream. Geometry is checked
against the oracle, and the theme is applied on top.

### SAVEFILE

`midend_serialise` is the canonical state: parameters, seed, move
history, and the position in that history. It is both the exchange
format with other platforms and the main parity oracle.

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
  -> draw -> android_draw_* -> ViewCallbacks -> canvas
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
