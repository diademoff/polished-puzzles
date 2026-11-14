# Performance baseline: measurement procedure

Budgets and rules are in `docs/PERFORMANCE.md`. This file is the
procedure for the Phase 0 baseline and the place where the numbers are
recorded.

Status: **partially recorded**. An emulator baseline exists and is
recorded below as a relative reference. The absolute baseline needs a
physical device; that part of the Phase 0 exit criterion is still open.

## 1. Devices

| Tier | Device | Android | Screen |
|---|---|---|---|
| A | Pixel 4a or Pixel 6a class | current | 90 Hz |
| B | 2 GB RAM phone | 8–9 | 60 Hz |
| Tablet | 10 inch | current | 60 Hz |

Record the exact model, build number, and battery state. An emulator
may be used for relative comparisons only, never for absolute numbers
(`docs/PERFORMANCE.md`, section 2).

## 2. Build

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release build, R8 on, no debugger attached, "do not disturb" on,
animations off, screen brightness fixed, device cool.

## 3. Runs

Every metric needs 10 runs. Report P50, P90, P99, and the worst value.
Discard nothing; note outliers.

## 4. Commands

Cold start, per run:

```sh
adb shell am force-stop name.boyle.chris.sgtpuzzles
adb shell am start -W -n name.boyle.chris.sgtpuzzles/.GameChooser
```

Record `TotalTime` and `WaitTime`. Repeat with the saved game path for
the "first frame of a saved game" metric.

Frame timing, a 10-minute session:

```sh
adb shell dumpsys gfxinfo name.boyle.chris.sgtpuzzles reset
# play for 10 minutes
adb shell dumpsys gfxinfo name.boyle.chris.sgtpuzzles
```

Record the frame-time percentiles and the janky-frame percentage.

Memory:

```sh
adb shell dumpsys meminfo name.boyle.chris.sgtpuzzles
```

APK size:

```sh
ls -l app/build/outputs/apk/release/app-release.apk
```

Build time:

```sh
./gradlew clean
time ./gradlew :app:assembleRelease
```

Generation, per game and per hard preset: time from the request to the
first frame after generation, and the time to react to Cancel. Record
the slowest preset per game.

## 5. Report format

Append one table per device:

| Metric | P50 | P90 | P99 | Worst | Budget | Verdict |
|---|---|---|---|---|---|---|

Then a short note: device, build, date, and anything that differs from
the standard scene.

## 6. Current results

### Emulator baseline (relative only)

| Item | Value |
|---|---|
| AVD | `puzzles_api36`, Android 16 (API 36), x86_64 |
| Screen | 1080x2400, 420 dpi, 60 Hz |
| RAM / cores | 2048 MB / 4 |
| GPU | `swiftshader_indirect` (software), `-no-window` |
| Build | release, signed with the debug key, R8 on |
| Date | 2026-11-14 |

The SDK and emulator live outside the repository:

```sh
SDK=$HOME/android-sdk
$SDK/emulator/emulator -avd puzzles_api36 -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -port 5554
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

| Metric | P50 | P90 | P99 | Worst | Budget | Verdict |
|---|---|---|---|---|---|---|
| Cold start, chooser | 733 | 758 | 776 | 776 | 1000 (A) | reference only |
| Warm start, chooser | 33 | 59 | 82 | 82 | 400 | reference only |
| Cold process + saved game, fifteen 4x4 | 1028 | 1072 | 1072 | 1072 | - | reference only |
| Frame time, chooser scroll | 19 ms | 29 ms | 31 ms | 31 ms | 16.7 ms | reference only |
| Janky frames, chooser scroll | 2.70% | - | - | - | < 1% | reference only |
| PSS, chooser | 33.4 MB | - | - | - | ref + 10% | reference |
| PSS, fifteen 4x4 | 67.5 MB | - | - | - | ref + 10% | reference |
| APK, release, all ABIs | 10.68 MB | - | - | - | - | reference |
| APK, debug, all ABIs | 25.8 MB | - | - | - | - | reference |
| Clean release build | 59 s | - | - | - | - | reference |

Notes:

- The emulator renders with SwiftShader on a shared host. Frame time and
  jank are therefore pessimistic and must not be compared with a device.
  They are a relative reference for the same AVD only.
- The cold start covers the Compose chooser and the whole app process.
  It is below the tier A budget on this emulator, but that does not
  prove anything for a real device.
- The saved-game number includes a cold process start, so it is not the
  "first frame of a saved game" budget (300 ms), which assumes a warm
  process.
- Debug PSS was 97.9 MB (chooser) and 134.7 MB (fifteen 4x4); the release
  numbers above are the ones to keep.

### Physical device baseline

Not recorded. The numbers must come from the tier A, tier B, and tablet
devices in section 1. Until then, the Phase 0 exit criterion "the
baseline is recorded" stays open.

### Compose renderer (relative only)

Scene: fifteen 4x4, release build signed with the debug key, ten taps
on the board, `adb shell dumpsys gfxinfo ... reset` before the taps.
The old row is commit `ec188da2` (Bitmap/Canvas renderer); the new row
is the Compose display-list renderer from ADR 0004. One run each, on the
same AVD; this is a warning, not an absolute result.

| Renderer | Frames | Janky | P50 | P90 | P99 |
|---|---:|---:|---:|---:|---:|
| Bitmap/Canvas (`ec188da2`) | 22 | 40.9% | 29 ms | 32 ms | 38 ms |
| Compose display list | 36 | 36.1% | 27 ms | 48 ms | 65 ms |

The emulator uses a software GPU. Both renderers exceed the 16.7 ms
budget on this AVD, so the absolute budget and the ten-run method in
`PERFORMANCE.md` still need a physical device.
