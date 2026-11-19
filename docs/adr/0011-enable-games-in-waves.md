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

Third wave (2026-11-18): `blackbox`, `lightup`, `undead`, and `rect`
were enabled with the same checks. `cube` was the fourth candidate and
is deferred: `docs/PARITY.md` records the open huge-area enumerate-and
-hang debt, and the note requires a guard before `CUBE` is enabled.
`rect` is the first reserve game and passed all five conditions:
D-022 has no `Review:` line, the C square bound is checked before
allocation, and the C `get_prefs` is NULL with no Kotlin schema.
`KotlinGamesTest` now pins the twelve enabled games, and
`ThirdWaveTileSizeInputTest` covers the four new ones on the Pixel 4a
5G at two tile sizes.

Fourth wave (2026-11-18): `slant`, `range`, `towers`, and `unequal`
were enabled with the same checks. `slant` is the original pilot; its
2026-11-14 waiver is superseded by the general waiver, not added to it.
The other three have no `Review:` deviation and no open hang or
unbounded-retry debt. `range` bounds the board at `64x64`, and all three
declare the C preference schema. `KotlinGamesTest` now pins the sixteen
enabled games, and `FourthWaveTileSizeInputTest` covers the four new
ones on the Pixel 4a 5G at two tile sizes.

Fifth wave (2026-11-18): `magnets`, `pattern`, `mosaic`, `signpost`,
and `untangle` were enabled with the same checks. All five were the
original candidate list, so no reserve game was needed. None has a
`Review:` deviation, each declares its `Game.configure` and the C
preference schema, and the only open item was a size bound, which the
owner made warning-only (decision 2b). The measured thresholds are 224
cells for magnets, 2500 for pattern, 2500 exclusive for mosaic, 400 for
signpost, and 100 points for untangle. `KotlinGamesTest` now pins the
21 enabled games, `FifthWaveTileSizeInputTest` covers the five new
ones on the Pixel 4a 5G at two tile sizes, and
`SlowGenerationDialogTest` covers the warning for the aggressive
`100x100` mosaic.

Sixth wave (2026-11-18): `galaxies`, `bridges`, `unruly`, `filling`,
and `mines` were enabled with the same checks. All five were the
candidate list, so no reserve game was needed and `cube` stays on C: its
enumeration hang is not solved by an existing warning, because its
generation is one pass over the accepted squares and a huge area is a
memory question. None of the five has a `Review:` deviation, each
declares its `Game.configure` and the C preference schema, and the only
open item was a size or retry bound, which the owner made warning-only
(decision 2b). The thresholds are 225 cells for galaxies and bridges,
196 for unruly, 221 for filling, and 256 for mines; the C retry loops in
all five stay unbounded in the port, so the Kotlin hang is the C hang.
`KotlinGamesTest` now pins the 26 enabled games,
`SixthWaveTileSizeInputTest` covers the five new ones on the Pixel 4a
5G at two tile sizes, and `SlowGenerationDialogTest` covers the warning
for a custom `16x16` galaxies board.

Slow-generation policy (2026-11-18): decision 2b was first implemented
as a warning, not a cap. `Game.generationWarning` is the new hook; the engine
names the parameter sets whose C generation is measurably long or
unbounded, and the shell shows that text before a fresh generation with
Continue and Cancel. It rejects no parameter set, draws no random
number, and writes no description. `SlowGenerationTest` pins the hook
for every declared game and the shell gate; `SlowGenerationDialogTest`
covers the dialog on the Pixel 4a 5G. No new size cap is added, and
D-007, D-008, and D-014 stay as they are.

Seventh wave (2026-11-18): `netslide`, `palisade`, `inertia`, `tents`,
`net`, `tracks`, `dominosa`, `map`, `keen`, `singles`, `pearl`, and
`loopy` were enabled. These are the thirteen games that a `Review:`
deviation blocked, minus `solo`. Earlier the same day the owner replaced
the warning-only policy for these games with a hard maximum in
`newDesc`, before the first draw: the direction to set the maxima and
finish the remaining waves is the owner's review point for the caps.
`work/generation-limits.md` is the measurement, D-007, D-008, and D-014
were tightened, and D-026 to D-034 were added. The maxima are 90,000
cells for `netslide`, 50 regions for `palisade`, 40,000 cells for
`inertia`, 484 for `tents`, 10,000 for `net`, 484 for `tracks`, a
maximum number of 9 for `dominosa`, 100 regions for `map`, 400 for
`singles`, 121 for `pearl`, and 400 for `loopy`; `keen` needs none
because validation caps the grid at 9x9. Late on 2026-11-18 the `pearl`
maximum was tightened from 144 to 121 cells after a six-seed sweep
(D-032); the direction itself is unchanged, and the other maxima are
unchanged. Each cap is generation-only,
so every save and game id that C accepts still loads. Each of the
twelve declares its `Game.configure` and the C preference schema, and
its `Review:` line now records the owner direction and the cap.
`KotlinGamesTest` now pins the 38 enabled games, and
`SeventhWaveTileSizeInputTest` covers the twelve new ones on the Pixel
4a 5G at two tile sizes; every case loads a frozen game id, so the
device suite generates no board above a cap. `solo` stays on C: D-025
waives its parity, so it needs a separate owner decision. `cube` stays
on C with its note unchanged.

Eighth wave (2026-11-19): `solo` and `cube` were enabled, and every
ported game now runs on the Kotlin engine. This is the owner decision
of 2026-11-19 to finish the roadmap as the documents describe, so no
game is left `dual-run`. `solo` needed the separate owner decision that
the seventh wave recorded: the owner reviewed D-025 and kept it as it
stands, so the app runs the Kotlin engine with no parity claim for the
killer seeds that entry lists, and D-024, D-034, and the load path are
unchanged. `cube` needed the guard its `docs/PARITY.md` note demanded.
The measurement in `work/generation-limits.md` showed that its
generation is not slow — the phone generates 250,000 cells in 0.059 s —
but that the per-square Kotlin objects exhaust the 256 MB Android heap
at 1,960,000 cells, where C still generates. D-036 therefore refuses an
area above 250,000 cells in `newDesc` before the first allocation or
draw, which needs 48 MB of heap; the cap is a deviation for this build,
and it is generation-only, so a larger save or game id still loads and
plays. Neither game needs a slow warning: both have a hard maximum.
`KotlinGamesTest` now pins the 40 enabled games and a frozen save for
each, `EighthWaveTileSizeInputTest` covers the two new ones on the
Pixel 4a 5G at two tile sizes with frozen game ids, and
`GamePlayParameterizedTest` completes both through the shell on the
Kotlin engine. The spoken board wave 5 (`SpokenBoardWaveFiveTest` and
the two new tables) closes D-01 for every backend.
