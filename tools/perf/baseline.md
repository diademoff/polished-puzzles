# Performance baseline: measurement procedure

Budgets and rules are in `docs/PERFORMANCE.md`. This file is the
procedure for the Phase 0 baseline and the place where the numbers are
recorded.

Status: **tier A recorded, tier B and tablet missing**. The tier A
numbers below come from a physical Pixel 4a 5G at 60 Hz. An emulator
baseline is kept as a relative reference. There is one phone only, so
the tier B and tablet parts of the Phase 0 exit criterion stay open.

## 1. Devices

| Tier | Device | Android | Screen |
|---|---|---|---|
| A | Pixel 4a 5G (the only physical device) | 16 | 60 Hz |
| B | 2 GB RAM phone (missing) | 8–9 | 60 Hz |
| Tablet | 10 inch (missing) | current | 60 Hz |

No 90 Hz tier A phone exists, so the 90 Hz case is not measured.

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

### Pixel 4a 5G (physical): tier A at 60 Hz, 2026-11-18

| Item | Value |
|---|---|
| Device | Pixel 4a (5G), `bramble` |
| Build fingerprint | `google/bramble/bramble:14/UP1A.231105.001.B2/11260668:user/release-keys` |
| ROM | LineageOS `23.2-20260822-NIGHTLY-bramble` |
| Android | 16, API 36, security patch 2026-08-01 |
| Screen | 1080x2340, 420 dpi, `supportedRefreshRates [60.000004]` |
| Battery | 98%, USB powered, 24.8 C, thermal status 0 |
| Build | release, R8 on, not debuggable, signed with the local debug key |
| Commit | `31e62d51`, versionName `2026-11-18-0910-790f5851` |
| Date | 2026-11-18 |

Build and install:

```sh
SDK=$HOME/android-sdk/build-tools/36.0.0
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleRelease --max-workers=2
$SDK/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out build/perf/app-release-debugkey.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r build/perf/app-release-debugkey.apk
```

The project has no release `signingConfig`, so Gradle writes
`app-release-unsigned.apk` and `apksigner` signs it with the debug key.
No build file was changed. The APK is still a release build: R8 runs,
`debuggable=false`, and the baseline profile is inside the APK.

After the install the device reported `status=run-from-apk`, so the app
was not compiled yet. One `adb shell cmd package bg-dexopt-job` run
brought it to `status=speed-profile` (reason `bg-dexopt`), and every
number below uses that state except the row marked as first install.
`cmd package bg-dexopt-job` is the standard background compile; it is
not a device setting.

Device state during the runs, changed with `adb shell` and restored
afterwards:

| Setting | Before | During | After |
|---|---|---|---|
| `global zen_mode` (DND) | 0 | 2 | 0 |
| `system screen_brightness` | 108 | 128 | 108 |
| `system screen_brightness_mode` | 0 | 0 | 0 |
| `global window_animation_scale` | 1.0 | 0 | 1.0 |
| `global transition_animation_scale` | 1.0 | 0 | 1.0 |
| `global animator_duration_scale` | null | 0 | null (deleted) |

The screen was on, the device was on USB power, and
`stay_on_while_plugged_in` was already 2 and was not touched.

Method: 10 runs per startup metric, `adb shell am start -W -S` (cold) or
`adb shell am start -W` (warm) after `KEYCODE_HOME`, `TotalTime` read
from the output. Frames come from `adb shell dumpsys gfxinfo <package>`
after a `reset`. Percentiles from `gfxinfo` are histogram bin edges, so
they are coarse (350 ms, 550 ms, 650 ms bins). Input was real touch
injection (`adb shell input tap`) at the board tile centers.

Startup, chooser and saved game:

| Metric | P50 | P90 | P99 | Worst | Budget | Verdict |
|---|---|---|---|---|---|---|
| Cold start, chooser, speed-profile | 373 | 424 | 470 | 470 | 1000 (A) | PASS |
| Cold start, chooser, first install (`run-from-apk`) | 606 | 617 | 624 | 624 | 1000 (A) | not steady state |
| Warm start, chooser, app in memory | 39 | 46 | 49 | 49 | 400 | PASS |
| Cold process + saved game, fifteen 4x4, resume | 270 | 277 | 277 | 277 | 300 (upper bound) | PASS as an upper bound |

The app has no trace section or log line at the first drawn frame, so
the in-app split of "first frame of a saved game" cannot be measured.
The row above is the whole path from a cold process to the drawn board,
which includes process start, so it is an upper bound for the 300 ms
budget. The resume path is real: `GamePlay` loads `savedBackend` on a
launch with no intent.

Frames, gameplay and shell, on the same device and build:

| Scene | Frames | Janky | P50 | P90 | P99 | Worst | Budget | Verdict |
|---|---:|---:|---:|---:|---:|---:|---|---|
| Chooser scroll, 27 s | 1078 | 0.0% | 7 | 9 | 11 | 11 | 16.7 ms, < 1% | PASS |
| Fifteen 4x4, new board, 20 moves 1.5 s apart | 20 | 100% | 73 | 109 | 113 | 113 | 16.7 ms, < 1% | MISS |
| Fifteen 4x4, 54 s session, 184 moves | 183 | 100% | 350 | 550 | 650 | 650 | 16.7 ms, < 1% | MISS |
| Fifteen 4x4, same game at about 200 moves, 10 moves 1.5 s apart | 10 | 100% | 650 | 700 | 700 | 700 | 16.7 ms, < 1% | MISS |

For the 54 s session, 46 four-tap cycles of real taps were injected in
54 s, and the counters show 183 frames for 184 moves, so the board
draws about one frame per move and the user waits that frame. The
commands:

```sh
adb shell dumpsys gfxinfo name.boyle.chris.sgtpuzzles reset
# taps at board tile centers, 4 cycles of taps per loop
adb shell dumpsys gfxinfo name.boyle.chris.sgtpuzzles
```

### Board frame fix, same phone, 2026-11-18

`BoardRecorder` used to keep every drawing pass of the session and
`BoardCanvas` replayed all of them on every Compose frame, so the frame
cost followed the move count. A pass that repaints the window now drops
the passes before it, and the shell forces one before every redraw:
`KotlinGameEngine.redraw` calls `Midend.forceRedraw`, the C shell calls
`GameEngine.forceFullRedraw` after key events, timer ticks, restart, and
solve. Release build, R8 on, `pm clear` before each scene, `cmd
package bg-dexopt-job` to `status=speed-profile`, twenty moves 1.2 s
apart, `dumpsys gfxinfo` after a `reset`:

| Scene | Frames | Janky (deadline / legacy) | P50 | P90 | P99 | Budget | Verdict |
|---|---:|---:|---:|---:|---:|---|---|
| Fifteen 4x4, new board, 20 moves | 20 | 80% / 0% | 17 | 17 | 18 | 16.7 ms, < 1% | MISS by 1–2 ms |
| Fifteen 4x4, about 100 moves | 101 | 1.0% / 1.0% | 12 | 13 | 15 | 16.7 ms, < 1% | PASS |
| Fifteen 4x4, about 200 moves | 100 | 0.0% / 0.0% | 12 | 13 | 14 | 16.7 ms, < 1% | PASS |

`framestats` for one move on the fixed build: input to `DrawStart`
1–2 ms, `DrawStart` to `SyncQueued` 2.9–4.1 ms (it was 202 ms at about
210 moves before the fix), `IssueDrawCommandsStart` to `SwapBuffers`
1.8 ms (it was 192 ms), `SwapBuffers` to `FrameCompleted` 9.7 ms. The
UI thread is now clean (`Slow UI thread` 0); the residual on a new
board is the 11 ms GPU composition, which the chooser shares.

PSS, same build, fifteen 4x4 after about 100 moves:
78,046 kB (76.2 MiB), against 77,333 kB (75.5 MiB) measured on the old
build the same day. The recorded baseline is 69,611 kB on an older
build, so this is +12% against that row and +0.9% against the build it
replaces. Two earlier attempts are recorded here because they were
rejected: keeping the pixels in a Compose image cost +30 MB of PSS
(Gfx dev and native heap) and did not meet the frame budget, and
resetting the scene at every pass without a full repaint broke every
game that draws only its changes.

Every scene above ran with the device settings from the tier A table
restored afterwards.

`dumpsys gfxinfo framestats` for one move on the 184-move game shows
where the time goes: draw start to `SyncQueued` 222 ms,
`IssueDrawCommandsStart` to `SwapBuffers` 184 ms, `SwapBuffers` to
`FrameCompleted` 291 ms, and 702 ms from the input event to the
completed frame. The cause is not investigated here.

Memory, size, battery:

| Item | Value | Budget | Verdict |
|---|---|---|---|
| PSS, fifteen 4x4, steady | 68.0 MiB (69,611 kB) | baseline + 10% | recorded |
| PSS, fifteen 4x4, right after generation | 97.1 MiB (99,460 kB) | baseline + 10% | recorded, transient |
| APK, release, all four ABIs, unsigned | 11,129,432 B (10.61 MiB) | baseline + 5% | recorded |
| APK, same APK signed with the debug key | 11,281,175 B (10.76 MiB) | baseline + 5% | recorded |
| Battery | not measured | no measurable regression | UNMEASURED |

Notes and limits:

- The frame budget is missed by a wide margin on the game board and
  passed by the shell. On the same device, the chooser scroll is 7 ms
  P50 with 0% janky frames, so the device and the Compose pipeline are
  not the limit; the board draw is.
- The board cost grew with the number of moves in the session: 73 ms
  P50 on a new board and 650 ms P50 at about 200 moves, with the same
  tap spacing. The cause was that the recorded scene kept every drawing
  pass and every frame replayed all of them. The "Board frame fix"
  section below records the fix and the numbers after it.
- The gameplay session is 54 s, not the 10 minutes the dropped-frame
  budget names. The 100% janky result does not depend on the length.
- There is one phone at 60 Hz. The 90 Hz tier A case, tier B, and the
  tablet are unmeasured, as are touch-to-visible, main-thread blocks,
  generation progress and cancel, save and load time, Help, and
  battery.
- APK size is compared across different commits (the emulator row is
  from 2026-11-14), so the +5% gate is not evaluated here.
- The saved-game budget is met only as an upper bound; a trace marker
  at the first frame of a loaded game would be needed for the exact
  number.

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
budget on this AVD. This row set is history only: the physical device
numbers above answer the absolute question, and there the Compose
renderer also misses the budget (73 ms P50 on a new fifteen board). The
old bitmap renderer was not rebuilt on the phone, so the P90 regression
against it is unmeasured on hardware.
