# Intentional deviations

This file records differences from upstream that we accept on purpose.
Anything not listed here is a bug.

## Rules

1. A deviation is allowed only if it serves the project goals: modern
   UI/UX, accessibility, or correctness on Android.
2. Every deviation has:
   - an ID;
   - an area (UI, a11y, renderer, behavior);
   - what exactly differs;
   - why;
   - the test that pins it;
   - the scope (screen, game, all games);
   - a date and a status (`active`, `retired`).
3. The test must **pin** the deviation, not merely allow it. If the
   deviation disappears, the test must fail.
4. The parity suite stays green. A deviation does not disable a check;
   it describes the expected difference.
5. Do not add an entry to hide a bug. Classify first: our bug, upstream
   bug, or deviation.
6. A deviation in game semantics, seed, `DESC`, or `.sgtp` is
   forbidden.
7. A deviation in the game board palette is allowed only in UI mode.
   In strict mode the palette stays the reference palette.

## Entry format

```markdown
### D-001: Short title

- **Area:** UI / a11y / renderer / behavior
- **Status:** active
- **What differs:** ...
- **Why:** ...
- **Test:** `path/to/Test.kt::testName`
- **Scope:** all games / a specific game / a screen
- **Upstream:** link to an issue or discussion, if any
- **Date:** YYYY-MM-DD
```

## Register

### D-001: Android patch layer inside vendored C

- **Area:** behavior and build
- **Status:** active
- **What differs:** 53 files in `app/src/main/jni` differ from
  upstream commit `790f5851`. The differences are the Android port
  layer: translation macros, extra presets, Android input and cursor
  paths, and small build changes. The exact file set and line counts
  are in `app/src/main/jni/UPSTREAM-PATCHES.txt`; the content hashes
  are in `app/src/main/jni/UPSTREAM-FILES.txt`.
- **Why:** this is the existing baseline of the port, not a new
  change. It predates the rule "do not edit vendored C". Phase 0
  records it so that a new edit is visible. Separating the layer into
  `patches/` is a separate mechanical change.
- **Test:** `tools/check-upstream-diff.sh --strict` fails when a
  vendored file changes or when the patch layer gains or loses a file.
  The parity goldens (`GoldenDescTest`) pin the behavior of the patched
  engine.
- **Scope:** all games
- **Upstream:** none
- **Date:** 2026-11-14

### D-002: Android presets omit the largest boards

- **Area:** behavior
- **Status:** active
- **What differs:** the app compiles with `SMALL_SCREEN`
  (`app/src/main/jni/cmake/platforms/android.cmake`), so four games
  expose fewer presets than a desktop upstream build:
  `loopy` 20 instead of 23, `mines` 4 instead of 6, `net` 8 instead of
  10, and `rect` 5 instead of 7. The removed entries are the largest
  boards, for example the 30x16 mines presets.
- **Why:** the largest boards are not useful on a phone screen. This is
  the shipped behavior of the Android port.
- **Test:**
  `parity/src/test/kotlin/.../PresetDeviationTest.kt::android presets omit the desktop large presets`
- **Scope:** `loopy`, `mines`, `net`, `rect`
- **Upstream:** none
- **Date:** 2026-11-14

## Examples

Allowed:

- content descriptions and custom actions for TalkBack;
- shell theme that does not affect the board palette;
- an appearance animation that does not change game state;
- larger touch targets;
- predictive back.

Not allowed:

- a different initial state for the same seed;
- a different `DESC`;
- a different `.sgtp` format;
- a different solver result;
- different board geometry in strict mode;
- hiding a parity failure by updating a golden.
