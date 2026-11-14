# Performance baseline: measurement procedure

Budgets and rules are in `docs/PERFORMANCE.md`. This file is the
procedure for the Phase 0 baseline and the place where the numbers are
recorded.

Status: **blocked**. No reference device is available in this
environment. The procedure below is ready; the numbers are not.

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

None. The Phase 0 exit criterion "the baseline is recorded" stays open
until a physical device is connected.
