#!/bin/sh
# Runs the instrumentation suite on the CI emulator (D-07).
#
# Usage:
#   adb devices                 # one device or emulator is attached
#   sh tools/ci/run-connected-tests.sh
#
# The emulator job runs this file as the `script` of the emulator
# action. The action executes every line of that input in its own
# `sh -c`, so a failing line would stop it before the logcat and the
# screenshot are read. One file keeps the failure code instead.
#
# The display is the reference device of docs/TESTING.md: 1080x2400 at
# 420 dpi. No stock AVD profile has exactly those numbers, so the script
# sets them and checks them; a wrong display must fail here and not as an
# unrelated layout or capture failure.
#
# Run it with the JDK of the build, 17: `app/build.gradle.kts` declares
# the toolchain, and the workflow sets `JAVA_HOME` before this file runs.

set -u

ARTIFACTS=build/emulator-artifacts
SIZE=1080x2400
DENSITY=420

mkdir -p "$ARTIFACTS"

adb shell wm size "$SIZE"
adb shell wm density "$DENSITY"

if ! adb shell wm size | grep -q "$SIZE"; then
    echo "run-connected-tests.sh: the display is not $SIZE" >&2
    exit 1
fi
if ! adb shell wm density | grep -q "$DENSITY"; then
    echo "run-connected-tests.sh: the density is not $DENSITY dpi" >&2
    exit 1
fi

# The three pixel goldens are recorded on a physical phone and are not
# committed (D-04), so a runner cannot compare them. This `notClass`
# argument is the whole exclusion; every other class runs.
EXCLUDED=name.boyle.chris.sgtpuzzles.screenshot.ChooserScreenshotTest
./gradlew :app:connectedDebugAndroidTest --console=plain \
    -Pandroid.testInstrumentationRunnerArguments.notClass="$EXCLUDED"
status=$?

# Keep the log, the screen, and any recorded golden of a failed run. A
# stuck device must not hold the job: every read has a timeout.
timeout 60 adb logcat -d > "$ARTIFACTS/logcat.txt" || true
timeout 60 adb exec-out screencap -p > "$ARTIFACTS/failure-screen.png" || true
timeout 60 adb pull /sdcard/Android/data/name.boyle.chris.sgtpuzzles/files \
    "$ARTIFACTS/screens" || true

exit "$status"
