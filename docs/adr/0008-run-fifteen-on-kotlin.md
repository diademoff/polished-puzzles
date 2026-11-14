# ADR 0008: Run fifteen on the Kotlin backend

- **Status:** accepted
- **Date:** 2026-11-14

## Context

The JVM parity matrix for `fifteen` is green: the generator, moves,
history, solver, text, status, keys, save file, and strict draw trace
match C. The next step is the app: the shell must select the Kotlin
implementation, and the C implementation must stop being the runtime.

The shell already has a seam. `GameEngine` is the only interface the UI
sees, and `GameEngineImpl.fromLaunch` chooses the implementation behind
it. Generation normally runs `libpuzzlesgen.so` in a separate process;
drawing normally reaches `ViewCallbacks` from C.

## Decision

1. **Selection is a Kotlin function of the backend.** `KotlinGames`
   maps a `BackendName` to a `Game`. `KotlinGames.ENABLED` is the
   emergency switch; it is on by default now that the parity matrix is
   green. When the backend is `FIFTEEN`, `GameEngineImpl.fromLaunch`
   returns a `KotlinGameEngine` and never constructs the C engine.

2. **Generation for a Kotlin game does not start a process.**
   `GamePlay.startGameGeneration` asks `KotlinGames` for the save text,
   which is `Midend.newGame(...).serialise()`. The result is the same
   save file that `libpuzzlesgen.so` would produce. A missing seed is
   filled with a printable UUID; the RNG remains the ported
   `RandomState`.

3. **`KotlinGameEngine` implements `GameEngine` over `Midend`.** It
   owns the state history and the save file, and it uses the same
   `ViewCallbacks` seam as C. The drawing adapter adds the board origin
   and maps the primitives; the size negotiation repeats
   `midend_size(..., user_size = true)` with the same doubling and
   binary search, so the board occupies the same pixels as the C
   engine. The palette background comes from
   `ViewCallbacks.defaultBackgroundColour`, as it does in C.

4. **The configuration surface is the subset fifteen needs.** Width,
   height, game ID, seed, and the arrow-sense preference are Kotlin.
   The settings screen still uses the C preferences engine through
   `GameEngineImpl.forPreferencesOnly`, and the Kotlin engine reads and
   writes the same preference string. Moving the preferences editor to
   Kotlin is a separate change.

5. **The vendored C game stays.** It is still the oracle for the parity
   suite, the source of the chooser metadata, and the identifier for a
   saved game. It is not reachable as a gameplay backend. The trace,
   save, and golden corpora are unchanged.

6. **`kotlin` now means "no runtime C path".** The earlier definition
   also required two releases without P1 bugs before deleting the C
   source. The owner waived that wait on 2026-11-14. C is kept for the
   oracle and metadata, so it can still be compared, but the app cannot
   fall back to it for `fifteen` without changing `KotlinGames.ENABLED`.
   Future games keep the two-release rule unless the owner waives it
   again.

## Consequences

Positive:

- the app runs `fifteen` entirely on the Kotlin engine;
- the same `GameEngine`, `ViewCallbacks`, and save contracts serve both
  implementations;
- generation is instant and does not depend on the generator binary;
- the C implementation remains available for differential tests.

Negative:

- the game's settings editor is still the C preferences engine;
- the screenshot gate remains local (D-04), so the visual check is the
  instrumentation suite plus the manual comparison;
- a corrupt Kotlin save fails instead of falling back to C, by design;
- the backend choice is a compiled constant, not a user setting.

## Alternatives

1. **Keep `dual-run` and wait two releases.** Rejected by the owner:
   the Kotlin path is the requested runtime and the C path stays
   available in the repository.
2. **Remove `fifteen.c` from the app build.** Rejected: the chooser,
   saved-game identification, defaults, and the preferences editor use
   the C game table. Removing it needs a generated-metadata change, not
   a gameplay change.
3. **Run generation in the existing subprocess.** Rejected: the Kotlin
   generator is deterministic and fast, and the subprocess adds a
   failure mode that no longer exists.

### Verification of this ADR

- `KotlinGamesTest` pins the selection and the generated save.
- `GamePlayParameterizedTest` asserts that `fifteen` uses
  `KotlinGameEngine` and completes the 2x2 game.
- The full instrumentation suite, the parity suite, and the upstream
  checks are green (see `docs/TESTING.md`).
- On the reference emulator, the Kotlin and C renders of the same game
  were pixel-identical below the status bar (ImageMagick `compare`
  reported 0 different pixels). The status bar time was the only
  difference in the uncropped screenshots.
- Tap and swipe input are covered by the engine test at a resized tile
  size and by the swipe case in `GamePlayParameterizedTest`. The input
  tile size is the displayed size, not the preferred size used by the
  stateless oracle.
- `BoardGeometry` is the single value for the tile size, the board
  origin, and the board size. Redraw, input, the cursor rectangle, and
  size negotiation read it, so they cannot use different geometry.
- `FifteenInputParityTest` compares `DOWN`, `DRAG`, and `UP` against C
  at the size and letterbox origin the oracle chooses for each viewport.
- `GamePlayLandscapeInputTest` taps a letterboxed landscape board through
  the real shell. In portrait the horizontal origin is zero or one dp, so
  only a landscape run can catch an origin that is applied twice.
