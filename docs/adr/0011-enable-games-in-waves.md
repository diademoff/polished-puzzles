# ADR 0011: Enable the ported games in waves

- **Status:** accepted
- **Date:** 2026-11-18

## Context

`fifteen` runs on the Kotlin backend since 2026-11-14, when the owner
waived the two-release wait for that game only. Every other ported game
is `dual-run`: its engine passes the parity matrix, `KotlinGames.gameFor`
knows it, and `KotlinGames.handles` still keeps the app on C.

The two-release rule has no release to measure yet. The repository has
no Play release, and CI for the Android job has never run (D-06, D-07).
The owner chose on 2026-11-18 to enable the ported games in waves
instead of waiting for releases that do not exist (decision 1a). The
same decision keeps goldens local (4a) and keeps size limits
warning-only, with no new hard cap (2b).

A wave must not become a global switch. Some ported games still carry a
`Review:` deviation, a size or retry debt the owner must decide, or a
preference page whose Kotlin schema needs checking. An unaware
enablement would ship a game whose review is still open.

## Decision

1. **The owner waives the two-release wait for the ported games.** The
   waiver applies to the wait only. It does not waive a `Review:`
   deviation, a size or retry decision, a device test, or the parity
   matrix.

2. **Enablement is per game, in waves.** `KotlinGames.APP_GAMES` holds
   the enabled set. `KotlinGames.ENABLED` stays true and remains the
   emergency switch for all games. A game joins `APP_GAMES` only with an
   entry in `docs/ROADMAP.md` Phase 4, and its status in `docs/PARITY.md`
   and `work/port-queue.md` changes in the same change.

3. **A game is eligible only when all of these hold:**
   - its matrix in `docs/PARITY.md` section 6 is green;
   - `docs/DEVIATIONS.md` has no entry for it with a `Review:` line, or
     the owner has reviewed and accepted that entry;
   - `docs/PARITY.md` lists no open size or retry debt the owner must
     decide;
   - its preference schema is declared exactly when the C table has a
     non-NULL `get_prefs`;
   - its device test passes on the connected phone, including the
     engine type and a real gesture.

4. **The first wave is `samegame`, `flood`, and `pegs`.** The three have
   no deviation entry, no flagged size or retry debt, and a NULL C
   `get_prefs` with no Kotlin schema. The device test is
   `FirstWaveTileSizeInputTest`, which reads the live `BoardGeometry`,
   runs a real tap and a real swipe at two tile sizes, checks the move
   count, the save text, and the status line, and asserts
   `KotlinGameEngine`. `GamePlayParameterizedTest` asserts the running
   engine for every enabled backend and still completes a game through
   the shell.

5. **The rest stay `dual-run` on C.** A later wave repeats the same
   checks. No game moves between waves without an owner decision
   recorded in `work/orchestrator-state.md`.

6. **The C source stays.** It remains the oracle, the chooser metadata,
   and the identifier for a saved game. Removing it is a separate change
   with its own evidence.

## Consequences

Positive:

- the ported games reach users without depending on releases and CI
  that do not exist yet;
- the per-game conditions keep the review, size, retry, and preference
  decisions visible where the owner can act on them;
- the enabled set is a short, auditable constant.

Negative:

- an enabled game has no release history behind it. The device test and
  the parity matrix are the evidence, and a P1 bug now has no
  two-release buffer;
- a game remains on C until its wave, so the app runs two engines for
  the same backend at different times.

## Alternatives

1. **Wait for two releases.** Rejected by the owner: no release exists
   yet, and the wait would block games that already pass the matrix.
2. **Enable every ported game at once.** Rejected: several games carry
   `Review:` deviations (for example D-005, D-007, D-010, and D-012),
   and some carry an open size or retry decision.
3. **Enable by a build flag per game.** Rejected: `APP_GAMES` is already
   per game, and a second switch doubles the states the tests must pin.

### Verification of this ADR

- `KotlinGamesTest` pins the enabled set, checks `handles` for every
  backend, and reloads a generated save for each enabled game through
  `Midend`.
- `GamePlayParameterizedTest` asserts `KotlinGameEngine` for every
  enabled backend.
- `FirstWaveTileSizeInputTest` covers the first wave on the Pixel 4a 5G
  at two tile sizes.
- `docs/TESTING.md` records the device totals, the JVM totals, and the
  lint result for this wave.

Second wave (2026-11-18): `sixteen`, `flip`, `twiddle`, and `guess`
were enabled with the same checks. `KotlinGamesTest` now pins the eight
enabled games, and `SecondWaveTileSizeInputTest` covers the four new
ones on the Pixel 4a 5G at two tile sizes.
