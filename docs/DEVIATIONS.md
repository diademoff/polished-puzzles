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

### D-003: Black Box rejects a drawn ball count above the grid

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_params` never checks `maxballs`
  against the grid, so `w3h3m1M100` is accepted. A count draw above the
  nine arena cells then leaves the collision retry without a free cell,
  and `new_game_desc` loops forever. The Kotlin validator keeps the C
  messages and still accepts the parameter set. `newDesc` draws the
  count first, in the C order, and throws a clear
  `IllegalArgumentException` only when that drawn count is greater than
  `w*h`; the throw comes before any `x` or `y` draw. A drawn count that
  fits generates exactly as C, including the `x`/`y` draws and the
  whole-pair retry: seed `2` with `w3h3m1M100` draws eight, so nine
  balls fill the grid, and the `DESC` is the C one. Every parameter set
  whose drawn count fits keeps the C seed, `DESC`, and save text,
  whatever `maxBalls` is. A parameter set with `minBalls > w*h` is
  rejected by `validate_params` first with
  `Too many balls to fit in grid`, so it reaches the guard only through
  a direct `newDesc` call. With `maxBalls > minBalls` that call performs
  the one count draw and then throws; with `minBalls == maxBalls` no
  count draw happens.
- **Why:** a drawn count above the cell count can hang generation, and a
  hang in the UI or the generator is worse than a deterministic
  rejection. The guard depends only on the drawn count, so the same
  request always fails the same way, and it keeps the C description for
  every seed that C can generate.
- **Test:**
  `parity/src/test/kotlin/.../BlackBoxDescTest.kt::a drawn count inside the grid matches the oracle`
  (a fitting seed compared with the oracle) and
  `...::a drawn count above the grid is rejected instead of looping`
  (a five-second timeout); the engine tests are
  `engine/src/commonTest/kotlin/.../BlackBoxTest.kt::a drawn count above the grid is rejected without a hang`
  and
  `...::an over-large maximum keeps the C description when the count fits`.
- **Scope:** `blackbox`
- **Upstream:** none known
- **Date:** 2026-11-15

### D-004: Filling skips an overfull 1-region instead of aborting

- **Area:** behavior
- **Status:** active
- **What differs:** the C solver asserts `s->board[i] != 1` in
  `learn_critical_square` (`filling.c:796`). A board with two adjacent
  1 clues, for example `2x2:1a1a`, reaches the assert. The shipped C
  code keeps assertions: `app/src/main/jni/cmake/setup.cmake:17-25`
  removes `-DNDEBUG` from every release flag, so a release build aborts
  just as the oracle does. The Kotlin solver does not abort. It takes
  the path that the assert guards: no empty square is within a negative
  slack, so the distance loop ends at the head and every square of the
  region is skipped. With `2x2:1a1a` the solve returns `s1212`. The
  game is then marked as cheated. The two 1s of `s1212` are adjacent, so
  the completion check leaves the game open and the status stays 0.
- **Why:** a crash is not acceptable in the app, and the skip path is
  deterministic and keeps the best board. The board is reachable from a
  hand-written description or from two adjacent player-filled 1s. A
  generated description never puts two 1s into one 4-connected region,
  so no seed, `DESC`, or save text changes.
- **Test:**
  `engine/src/commonTest/kotlin/.../FillingTest.kt::a solve of an overfull one region skips it instead of aborting`
- **Scope:** `filling`
- **Upstream:** none known
- **Date:** 2026-11-15

### D-005: Netslide rejects a NaN barrier probability

- **Area:** behavior
- **Status:** active
- **What differs:** the parameter string `3x3bnan` decodes to
  `width = 3`, `height = 3`, `barrier_probability = NaN`, and
  `movetarget = 0`. C `validate_params` (`netslide.c:312-325`) accepts
  it, because both range tests compare false: `NaN < 0` and `NaN > 1`.
  `new_game_desc` then evaluates
  `nbarriers = (int)(params->barrier_probability * count234(barriertree))`
  at `netslide.c:628`. The cast of NaN to `int` is undefined behavior;
  on the shipped build it yields `INT_MIN`, so the assertion at
  `netslide.c:629` fails and C aborts. The Kotlin `validateParams`
  returns `Barrier probability must be a number` before any generation
  or allocation. The C `custom_params` field for "Barrier probability"
  uses `(float)atof` (`netslide.c:308`), which accepts `nan`, so a user
  can reach this path from the configuration dialog.
- **Why:** an abort is not acceptable in the app, and the check is
  deterministic: the same parameter text always gets the same error.
  No valid parameter set changes its seed, `DESC`, `AUXINFO`, or save
  text, because a finite rate is untouched.
- **Test:**
  `parity/src/test/kotlin/.../NetslideDescTest.kt::a NaN barrier rate is rejected instead of aborting`
  pins the message, the NaN field, and the `Midend.newGame` rejection.
  The C abort is not run, because it would kill the oracle process.
- **Review:** the owner should review this entry before `netslide` is
  enabled in the app.
- **Scope:** `netslide`
- **Upstream:** none known
- **Date:** 2026-11-15

### D-006: Light Up rejects two parameter sets that never terminate

- **Area:** behavior
- **Status:** active
- **What differs:** C accepts two parameter sets that do not terminate.
  First, `w = 46340` and `h = 46340` with any `blackpc`, `symm`, and
  `difficulty` passes `validate_params`, because C tests
  `w > INT_MAX / h` (`lightup.c:357-360`). `new_state` then allocates
  two arrays of `w*h = 2,147,395,600` elements (`lightup.c:386-390`),
  about 17 GB, and the generator runs the solver over the board.
  Second, `blackpc = 100` with `difficulty` 1 or 2 passes the percentage
  and difficulty checks (`lightup.c:362-363`, `372-373`). Every attempt
  produces an all-black board, which `puzzle_is_good` accepts at the
  requested difficulty and also at `difficulty - 1`, so the attempt is
  rejected at `lightup.c:1595-1602`, and the `blackpc < 90` bump at
  `1609` cannot fire, so the loop never ends. The Kotlin
  `validateParams` rejects both before any allocation or generation.
  An area above 2^20 cells returns
  `Width times height is too large for this build`. The exact 2^20
  boundary is accepted, and every preset is far below it. `blackpc = 100`
  with a positive difficulty returns
  `A board of 100% black squares cannot be generated at this difficulty`.
  `blackpc` from 5 to 99 at any difficulty is unchanged, and
  `blackpc = 100` at difficulty 0 still generates the all-`B`
  description that C generates. `newDesc` repeats both guards, so a
  direct caller cannot hang.
- **Why:** a hang in the app or in the generator is worse than a
  deterministic rejection. The guards depend only on the parameters, so
  the same request always fails the same way. They are not game rules:
  no seed, `DESC`, `AUXINFO`, or `.sgtp` text of a valid game changes,
  because every valid parameter set is untouched.
- **Test:**
  `engine/src/commonTest/kotlin/.../LightupTest.kt::validation follows the C order and the two guards`
  pins the two messages, the `2^20` boundary, and the accepted presets;
  `...::newDesc refuses the two non-terminating parameter sets` pins
  the `newDesc` rejection and the unchanged all-black difficulty-0
  description;
  `parity/src/test/kotlin/.../LightupDescTest.kt::validation messages match the oracle in order`
  pins the C validation order and the two guard messages. No test
  calls `newDesc` or `newState` on `46340x46340` or on `7x7b100d1`,
  because C does not terminate.
- **Scope:** `lightup`
- **Upstream:** none known
- **Date:** 2026-11-15

### D-007: Palisade refuses an area above 2^20 cells at generation

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_params` accepts every area up to
  `INT_MAX` cells, including `46340x46340` (2,147,395,600 cells).
  `new_game_desc` then allocates the clue, rim, scratch, winnow-order,
  and `4*wh` addable tables, which is tens of gigabytes, and the
  generator does not finish. The Kotlin `validateParams` keeps the C
  messages and still accepts `46340x46340`, the exact 2^20 boundary,
  and every normal preset. The Kotlin `newDesc` rejects an area above
  2^20 cells with `Width times height is too large for this build`
  before any RNG draw or allocation. `newState` is unchanged, so a
  hand-written game id with a huge area still allocates; only
  generation is guarded.
- **Why:** a hang or an out-of-memory crash in the generator is worse
  than a deterministic rejection. The guard depends only on the
  parameters and the message is fixed, so the same request always fails
  the same way. No seed, `DESC`, `AUXINFO`, or `.sgtp` text of a valid
  game changes, because no preset is near the limit.
- **Test:**
  `engine/src/commonTest/kotlin/.../PalisadeTest.kt::the huge area is accepted by validation and guarded at generation`
  pins the accepted boundary, the `2^20` and `1025x1024n8` cases, and
  the message;
  `parity/src/test/kotlin/.../PalisadeDescTest.kt::the huge area is accepted by validation but refused at generation`
  pins that validation keeps the C behavior. No test calls the oracle
  on `46340x46340`; that probe was killed by the OS.
- **Review:** the owner should decide the final area limit before
  `palisade` is enabled in the app.
- **Scope:** `palisade`
- **Upstream:** none known
- **Date:** 2026-11-16

### D-008: Inertia refuses an area above 2^20 cells at generation

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_params` (`inertia.c:199-223`) accepts
  every area up to `INT_MAX` cells, including `46340x46340`
  (2,147,395,600 cells), because the only upper test is
  `w > INT_MAX / h`. `new_game_desc` then calls `gengrid`, which
  allocates a `w*h` grid and three `w*h*8` scratch arrays
  (`inertia.c:234-243`): two byte arrays of about 17 GB each and an
  int array of about 69 GB, plus the grid. The unbounded `while (1)`
  attempt loop does not finish. The Kotlin `validateParams`
  keeps the C messages and still accepts `46340x46340`, the exact 2^20
  boundary (`1024x1024`), and every preset. The Kotlin `newDesc`
  rejects an area above 2^20 cells with
  `Width times height is too large for this build` before any RNG draw
  or allocation. `newState` is unchanged, so a hand-written game id
  with a huge area still follows the C allocation; only generation is
  guarded.
- **Why:** a hang or an out-of-memory crash in the generator is worse
  than a deterministic rejection. The guard depends only on the
  parameters and the message is fixed, so the same request always fails
  the same way. No seed, `DESC`, `AUXINFO`, or `.sgtp` text of a valid
  game changes, because no preset is near the limit.
- **Test:**
  `engine/src/commonTest/kotlin/.../InertiaTest.kt::generation refuses a huge area before any RNG draw`
  pins the accepted `46340x46340` and `1024x1024` boundaries in
  validation, the rejected `1025x1024` area, and the message;
  `InertiaTest.kt::the area guard leaves the RNG state untouched`
  pins that the encoded `RandomState` is byte-identical before and
  after a refused `newDesc`; `InertiaTest.kt::the generation boundary
  is exactly 2^20 cells` pins `MAX_AREA == 1 shl 20` and the
  generation guard on both sides of the limit. That test calls the
  guard directly, because a full `1024x1024` generation is too large
  for a unit test.
  `parity/src/test/kotlin/.../InertiaDescTest.kt::the huge area is accepted by validation but refused at generation`
  pins that validation keeps the C boundary and that `Midend.newGame`
  throws the message. No test calls the oracle on `46340x46340`; that
  probe does not terminate.
- **Review:** the owner should decide the final area limit before
  `inertia` is enabled in the app.
- **Scope:** `inertia`
- **Upstream:** none known
- **Date:** 2026-11-16

### D-009: Tents rejects two descriptions that abort the C decoder

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_desc` accepts two descriptions that
  overrun the decoder, so `new_game` aborts the shipped build. Both are
  for `8x8de` and both are followed by sixteen `,0` numbers:
  - `8x8:ddddddddz,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0` fails the
    assertion `i >= 0 && i <= w*h` at `tents.c:1292`
    (`new_game: Assertion 'i >= 0 && i <= w*h' failed.`, SIGABRT). The
    final `z` run jumps from below `w*h` to exactly `w*h + 1`.
  - `8x8:dddddddcz_,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0` fails the
    assertion `type == TREE` at `tents.c:1294`
    (`new_game: Assertion 'type == TREE' failed.`, SIGABRT). The final
    `z` lands exactly on `w*h` with `type == BLANK`.

  The Kotlin `newState` throws `IllegalArgumentException` with
  `tents: the description runs past the grid` for the first case and
  `tents: the description ends on a non-tree cell` for the second.
  `validateDesc` keeps the C messages and still accepts both
  descriptions.
- **Why:** the C abort is a debug assertion, and the shipped app keeps
  assertions (`app/src/main/jni/cmake/setup.cmake:17-25` removes
  `-DNDEBUG`). In a release build without assertions the same path
  writes outside the grid, so it is undefined behavior. A crash in the
  app is not acceptable, and a deterministic exception is the intended
  safe behavior. The guard depends only on the decoded cells, so the
  same description always fails the same way. Every valid description,
  seed, `DESC`, `AUXINFO`, and `.sgtp` file is unchanged, and no
  generated description reaches either path.
- **Test:**
  `engine/src/commonTest/kotlin/.../TentsTest.kt::the two C aborts are defined exceptions`
  pins both messages; the parity tests
  `parity/src/test/kotlin/.../TentsDescTest.kt::the two C aborts are a defined port result`
  and
  `parity/src/test/kotlin/.../TentsSaveTest.kt::a tree move and the overshooting description are defined errors`
  pin the `newState` and `Midend.fromSave` results. No test sends either
  description to the oracle, because the abort would kill the process.
- **Review:** the owner should review this entry before `tents` is
  enabled in the app.
- **Scope:** `tents`
- **Upstream:** none known
- **Date:** 2026-11-16

### D-010: Net rejects a NaN barrier probability

- **Area:** behavior
- **Status:** active
- **What differs:** the parameter string `5x5bNaN` decodes to
  `width = 5`, `height = 5`, `wrapping = false`, `unique = true`, and
  `barrier_probability = NaN`. C `validate_params` (`net.c:328-331`)
  accepts it, because both range tests compare false: `NaN < 0` and
  `NaN > 1`. `new_game_desc` then evaluates
  `nbarriers = (int)(params->barrier_probability * count234(barriertree))`
  at `net.c:1533`. The cast of NaN to `int` is undefined behavior; on
  the shipped build the assertion at `net.c:1534` fails and the process
  aborts with
  `new_game_desc: Assertion 'nbarriers >= 0 && nbarriers <= count234(barriertree)' failed.`
  (SIGABRT, exit 134). The Kotlin `validateParams` returns
  `Barrier probability must be a number` before any allocation or
  generation. C `decode_params` reads the rate with `strtod`
  (`net.c:241-244`) and `custom_params` with `atof` (`net.c:311-315`),
  so a save, a game ID, or the configuration dialog can reach the path.
- **Why:** an abort is not acceptable in the app, and the check is
  deterministic: the same parameter text always gets the same error.
  No valid parameter set changes its seed, `DESC`, `AUXINFO`, or save
  text, because every finite rate is untouched and the presets have a
  zero rate.
- **Scope note:** the Kotlin check runs after the area test and before
  the negative, greater-than-1, and 2-wide wrapping tests
  (`Net.kt:245-256`), while C has no NaN test at all. A `Params` value
  that carries a NaN rate and `unique = true` on a 2-wide wrapping
  board therefore gets `Barrier probability must be a number` where C
  returns `No wrapping puzzle with a width or height of 2 can have a
  unique solution` (`net.c:376-378`). A decoded parameter string
  cannot reach that combination: every `strtod` NaN spelling contains
  an `a`, and `decode_params` clears `unique` on an `a`
  (`net.c:245-247`). `2x2wbNaN` is therefore `unique = false`, so C
  passes `validate_params` and aborts in generation like any other
  NaN (verified: SIGABRT, exit 134). Only a `Params` value built in
  code observes the order difference, and no valid input changes.
- **Test:**
  `parity/src/test/kotlin/.../NetDescTest.kt::a NaN barrier rate is rejected instead of aborting`
  pins the NaN field, the message, the `Midend.newGame` rejection, the
  unchanged valid rates, and the message order above. The test does
  not run the C abort, because it would kill the oracle process; a
  separate one-off oracle process aborts with the assertion above.
- **Review:** the owner should review this entry before `net` is
  enabled in the app.
- **Scope:** `net`
- **Upstream:** none known
- **Date:** 2026-11-16

### D-011: Net returns a solve error for a non-rotation AUXINFO

- **Area:** behavior
- **Status:** active
- **What differs:** C `solve_game` reads `AUXINFO` with no check that a
  cell is a rotation of the `DESC` cell. For the description
  `123456789` with the auxiliary text `000000000`, the first cell is
  `1` in the description and `0` in the answer, so the C code reaches
  `assert(tt == ft)` at `net.c:1862` and the shipped build aborts with
  `solve_game: Assertion 'tt == ft' failed.` (SIGABRT). The Kotlin
  `solveAttempt`
  returns no move and
  `The auxiliary answer is not a rotation of the description`; `solve`
  returns null and `Midend.solve` returns false. The
  game-id and save paths keep the C validation, so every generated
  board and every real hint is unchanged.
- **Scope note:** a non-hex `AUXINFO` character is a second path to the
  same comparison. C assigns the tile only for a hex digit and then ORs
  in `LOCKED` (`net.c:1814-1822`), so any other byte keeps the
  uninitialised `snew` value and `tt` becomes a random low nibble. The
  Kotlin port reads a non-hex character as zero (`Net.kt:1258-1262`),
  so the result is deterministic: a non-zero cell in the `DESC` gives
  the error above, and a blank cell gives a defined `A` rotation and
  lock. C cannot be pinned here: the same crafted save produced a
  finished `S` move in one oracle process and
  `solve_game: Assertion 'tt == ft' failed.` (SIGABRT) in another. The
  record is reachable through the `.sgtp` `AUXINFO` field, which is
  decoded from hex without a digit-class check, and the oracle loads
  such a save.
- **Why:** a crash is not acceptable in the app, and the error is
  deterministic and keeps the C message style. The assertion is a
  debug check, not a game rule: a description and an `AUXINFO` that C
  considers valid produce the same `SOLVE` string as C.
- **Test:**
  `engine/src/commonTest/kotlin/.../NetTest.kt::a non-rotation AUXINFO is defined, not an abort`
  pins the exact text, the null move, and the short-`AUXINFO` read.
  `engine/src/commonTest/kotlin/.../NetTest.kt::a non-hex AUXINFO character is defined as zero`
  pins the non-hex character paths through a loaded save. The parity
  test
  `NetSaveTest.kt::a non-hex AUXINFO byte is a defined solve refusal`
  pins a reachable save that the oracle loads. The tests do not call
  the oracle solve, because the C result is uninitialised memory; the
  one-off oracle runs above are the evidence for the abort.
- **Review:** the owner should review this entry before `net` is
  enabled in the app.
- **Scope:** `net`
- **Upstream:** none known
- **Date:** 2026-11-16

### D-012: Tracks defines an outer track edge that aborts the C solver

- **Area:** behavior
- **Status:** active
- **What differs:** a hand-written save can set a track edge on the
  outer border of a non-clue cell. On `8x8dt` seed `12345`, the save
  with `NSTATES :1:2`, `STATEPOS:1:2`, and `MOVE    :7:S;TU0,0` sets
  the upper outer edge of cell `(0,0)`, which is not a clue. C
  `solve_discount_edge` (`tracks.c:1420-1427`) sees
  `S_E_DIRS(state, x, y, E_TRACK) & d` and runs
  `assert(state->sflags[y*state->p.w + x] & S_CLUE)` at
  `tracks.c:1423`. The shipped build keeps assertions
  (`app/src/main/jni/cmake/setup.cmake:17-25` removes `-DNDEBUG`), so
  the oracle aborts with
  `solve_discount_edge: Assertion 'state->sflags[y*state->p.w + x] & S_CLUE' failed.`
  (SIGABRT, exit 134). The Kotlin solver ignores the border rule for
  that edge and continues. For the save above `solveAttempt` returns no
  error and a solve move that starts `S;NR0,0;tU0,0;`; applying the move
  clears the stray outer edge, completes the board, and sets status
  `+1` with `used_solve` true.
- **Why:** an abort is not acceptable in the app. The input rule
  already keeps an outer edge on a non-clue cell out of normal play,
  and the C assertion is a debug check, not a game rule. The defined
  result is deterministic: the same save always produces the same
  solve move and the same completed board.
- **Scope note:** the save is reachable only through the `MOVE`
  record. `new_game` and `validate_desc` accept the same `DESC`, and a
  generated board sets an outer `E_TRACK` edge only on the entrance and
  exit clue cells. Every generated description, preset save, real move,
  and `.sgtp` file produced by the app is therefore unchanged.
- **Test:**
  `parity/src/test/kotlin/.../TracksGameTest.kt::an outer track edge is accepted by the solver`
  pins the exact move prefix, the completed result, and that C loads
  the save without a solve. The engine test
  `engine/src/commonTest/kotlin/.../TracksTest.kt::an outer track edge reaches the solver safely`
  pins the same result directly. The tests do not call the oracle
  solve, because it would kill the oracle process; the one-off oracle
  run above is the abort evidence.
- **Review:** the owner should review this entry before `tracks` is
  enabled in the app.
- **Scope:** `tracks`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-013: Dominosa defines a description whose highest domino has no placement

- **Area:** behavior
- **Status:** active
- **What differs:** the validated description
  `00222301130313132021` for `3dt` (`w = 5`, `h = 4`) holds five
  3s with no two adjacent. The decoder and `validate_desc` accept
  the board, and the dominoes `0-0` through `2-3` all have a
  placement, but the domino `3-3` has none. A solve without
  `AUXINFO` calls `run_solver(sc, DIFFCOUNT)`. The first
  forcing-chain pass finds no duplicate chain, so the second pass
  builds the per-domino start table at `dominosa.c:1540-1545`.
  The sorted placement list ends at domino `2-3` (index 8), so
  `di` stops at 9 while `sc->dc` is 10, and the shipped build
  aborts with
  `dominosa.c:1546: deduce_forcing_chain: Assertion 'di == sc->dc' failed.`
  (SIGABRT, exit 134). The Kotlin port gives the missing highest
  domino the start `pc` (`engine/Dominosa.kt:1463`), which
  the lookup reads as an empty list. `analyse` returns `0` and
  `solve` returns
  `S;E0,5;E1,6;E2,7;E4,9;E6,11;E7,12;E8,13;E9,14;E10,15;E11,16;E12,17;E13,18;E1,2;E2,3;E3,4;E5,6;E7,8;E8,9;E10,11;E12,13;E13,14;E16,17;E18,19;D3,8;D5,10;D14,19;D0,1;D6,7;D11,12;D15,16;D17,18`
  instead of aborting.
- **Why:** a crash is not acceptable in the app, and the result is
  deterministic: the same description always produces the same
  analyse result and the same solve text. No generated board
  reaches the path. The final generation gate accepts a board only
  when `run_solver(sc, diff)` returns 1, which means every domino
  has exactly one placement; the Ambiguous allocator places every
  domino once as well. Every seed, `DESC`, `AUXINFO`, golden, and
  `.sgtp` file of a generated game is therefore unchanged.
- **Scope note:** the path is reachable through a hand-written
  `DESC` in a `.sgtp` file or a hand-written game id. C loads the
  save and reports the same id before the solve; only the solve
  aborts. A generated save cannot contain the description, but the
  app can load such a hand-written save and write it back unchanged.
- **Test:**
  `engine/src/commonTest/kotlin/.../DominosaTest.kt::a validated description with no placement for a domino still solves`
  pins the description, the accepted validation, `analyse` returning
  `0`, and the exact solve text. The parity test
  `parity/src/test/kotlin/.../DominosaGameTest.kt::a validated description with no placement is reachable and solves`
  pins that C loads the crafted save with the same id and status and
  that Kotlin returns the same solve text. The tests do not call the
  C solve, because it would kill the oracle process; the one-off
  oracle run above is the abort evidence.
- **Review:** the owner should review this entry before `dominosa`
  is enabled in the app.
- **Scope:** `dominosa`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-014: Map refuses a board it cannot build

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_params` accepts an area up to
  `INT_MAX / 2` cells and a region count up to `w*h`
  (`map.c:263-274`). `new_game_desc` then allocates `n*n` ints for
  the graph (`map.c:1430`) and `new_state` allocates `4*wh` ints for
  the four quadrants (`map.c:1887-1888`). Both products overflow
  `int` for a large accepted board, and the allocations exhaust the
  heap long before that. The Kotlin `validateParams` keeps every C
  message and the exact C boundary (`32767x32767` is accepted,
  `32768x32768` is rejected, neither is allocated). The Kotlin
  `newDesc`, `newState`, and `validateDesc` reject a board above
  2^20 cells with `Width times height is too large for this build`,
  and a board above 4096 regions with `Too many regions for this
  build`, before any RNG draw or allocation.
- **Why:** an integer overflow or an out-of-memory crash is worse
  than a deterministic rejection. The guards depend only on the
  parameters, so the same request always fails the same way. No
  preset is near either limit: the largest is 30x25 with 75
  regions. Every seed, `DESC`, `AUXINFO`, golden, and `.sgtp` file
  of a valid game is unchanged.
- **Test:**
  `engine/src/commonTest/kotlin/.../MapTest.kt::the generation guard rejects a board it cannot build`
  pins both boundaries and the unchanged C validation messages;
  `...::a refused generation draws no random number` pins that the
  two guards run before the first draw;
  `...::newState refuses a huge board before it allocates` pins the
  same two messages on the load path. No test allocates a board of
  that size.
- **Review:** the owner should decide the final area and region
  limits before `map` is enabled in the app.
- **Scope:** `map`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-015: Map defines the C undefined and assert paths

- **Area:** behavior
- **Status:** active
- **What differs:** four inputs reach C undefined behavior or an
  assertion in `map.c`. First, `atoi` in `decode_params` and
  `sscanf("%d")` in `execute_move` have undefined overflow. The port
  saturates to `Int.MAX_VALUE` or `Int.MIN_VALUE`, as the other
  ports do; the value then fails the range test and the move is
  rejected. Second, `encode_params` reads `map_diffchars[diff]`
  with no range test (`map.c:213`). The port writes a plain `d` when
  the difficulty is outside 0..3, as `unruly` does for its own
  case. Third, `execute_move` does not check `immutable`, so a
  hand-written move can overwrite a clue, and `solve_game` then
  asserts `!immutable[i]` (`map.c:2283`). The port starts the
  solve from the captured clues, includes the changed immutable
  region in the diff, and restores it; the C build aborts. Fourth,
  the error-marker code asserts that every graph edge has a
  canonical point inside the grid (`map.c:3126-3137`). The port
  skips an edge whose stored point is missing or outside the grid.
- **Why:** none of these paths is reachable from a generated game
  or from the pointer and keyboard grammar. A defined result is
  better than a crash for a hand-written save or an internal
  invariant failure, and the result depends only on the input.
  Every generated `DESC`, `AUXINFO`, and `.sgtp` file is unchanged.
- **Test:**
  `engine/src/commonTest/kotlin/.../MapTest.kt::encode writes a plain d for an out-of-range difficulty`,
  `...::the move grammar rejects the C NULL forms` (the saturating
  overflow), `...::a clue overwritten by a move is restored by the solve diff`,
  and `...::a missing canonical point is skipped instead of aborting`
  pin the four results. `parity/src/test/kotlin/.../MapGameTest.kt::a clue overwritten by a hand-written move is repaired by the solve`
  pins that the hand-written save still loads in the oracle.
- **Review:** the owner should review this entry before `map` is
  enabled in the app.
- **Scope:** `map`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-016: Keen defines the solver clue assert as an impossible board

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_desc` does not check a clue value
  (`keen.c:1332-1351`), so the 3x3 description
  `_13,a1a1a1a1a1a1a1a1a1073741824` is valid. Every cell is its own
  block. The last clue is the letter `a` and the value `2^30`
  (1073741824), which is the `C_SUB` operation bit, so the addition
  clue becomes a subtraction clue with value 0 on a one-cell block.
  When the solve runs, C reaches `assert(n == 2)` in `solver_common`
  (`keen.c:377`) and the shipped build aborts with
  `solver_common: Assertion 'n == 2' failed.` (SIGABRT, exit 134). The
  Kotlin `solverCommon` returns `-1` for the non-domino, which the Latin
  solver reads as `DIFF_IMPOSSIBLE`. `Keen.solve` returns null,
  `Midend.solve` returns false and adds no history, and the status stays
  0.
- **Why:** an abort is not acceptable in the app. C does not validate a
  clue value, so a hand-written description can reach the assertion,
  and a defined impossible result is deterministic: the same
  description always gives the same result. A generated clue value is a
  sum or a product of at most six digits, so it never sets an operation
  bit and never reaches the guard. Every seed, `DESC`, `AUXINFO`,
  golden, and `.sgtp` file of a generated game is unchanged.
- **Scope note:** the path is reachable through a hand-written `.sgtp`
  `DESC` or a game id. C accepts the description, reports the same id,
  and keeps status 0 until the solve aborts.
- **Test:**
  `engine/src/commonTest/kotlin/.../KeenTest.kt::the clue assert of the C solver is a defined impossible result`
  pins the accepted description, the one-cell block, the operation
  bits, `DIFF_IMPOSSIBLE`, and the null solve. The parity test
  `parity/src/test/kotlin/.../KeenGameTest.kt::the clue with the operation bit that aborts the C solver is defined`
  pins that C loads the same save with the same id and that
  `Midend.solve` returns false. The tests do not call the oracle solve,
  because it would kill the oracle process; a separate one-off oracle
  run is the abort evidence above.
- **Review:** the owner should review this entry before `keen` is
  enabled in the app.
- **Scope:** `keen`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-017: Singles rejects a side of 62 at generation

- **Area:** behavior
- **Status:** active
- **What differs:** C `validate_params` accepts a side of 62
  (`singles.c:268`). A generated board with a side of 62 has a full
  Latin row or column of the 62 digits, so the description contains the
  digit 62. `n2c(62)` returns `[` (`singles.c:324-332`), and the game's
  own `c2n` returns -1 for `[` (`singles.c:334-342`). `new_game` calls
  `unpick_desc` on that description, the decoder rejects it, and the
  shipped build aborts at `singles.c:1452` with
  `new_game: Assertion '!"new_game failed to unpick"' failed.`
  (SIGABRT, exit 134). Measured with the committed oracle: `62x2de`
  seed `5` aborts after 40.9 s, `62x2de` seed `19` aborts, and
  `2x62de` seed `20` aborts. The Kotlin `validateParams` keeps the C
  accept for every side in `2..62` and the three C messages. The Kotlin
  `newDesc` rejects a side of 62 with the documented parameter message
  `Puzzle is too large` before the first RNG draw, and
  `Midend.newGame` reports the same message.
- **Why:** a crash is not acceptable in the app. The guard depends only
  on the parameters, so the same request always fails the same way. No
  valid size changes: every side in `2..61`, every preset, every seed,
  `DESC`, and `.sgtp` text keeps the C stream and the C output. The
  largest accepted side (`61x2de`) is compared with the oracle in
  `SinglesDescTest`. The game writes no `AUXINFO`, so there is no
  auxiliary answer to change.
- **Scope note:** the rejection is at generation only. The validator
  and `validateDesc` keep the C behavior: a hand-written `62x2de` save
  whose description holds `Z` (61) loads, and one whose description
  holds `[` is rejected with `Invalid character in game description`
  before `new_game`. Only a generated description reaches the C abort.
  `SinglesDescTest` loads the `Z` save in both engines.
- **Test:**
  `engine/src/commonTest/kotlin/.../SinglesTest.kt::the 62 boundary is rejected before the generator runs`
  pins the message, the untouched generator state for seeds `1` and
  `5`, and the accepted validator;
  `parity/src/test/kotlin/.../SinglesDescTest.kt::a side of 62 is rejected before any draw`
  pins the `Midend.newGame` message for seeds `1`, `5`, and `19` and
  the accepted `61x2de` and `2x61de` boundary;
  `...::the largest valid side generates the C description` compares
  `61x2de` seed `3` (the measured fast seed) with the oracle;
  `...::the description grammar accepts and rejects the C strings`
  loads the hand-written `62x2de` save with `Z`; and
  `...::a pasted id starts from the defaults, so CPARAMS is Easy`
  pins the shared descriptive-id difference. The tests do not call
  the oracle generation for the `62` shapes, because they either abort
  the process or do not finish in the measured budget; the one-off
  oracle runs above are the abort evidence.
- **Review:** the owner should review this entry before `singles` is
  enabled in the app.
- **Scope:** `singles`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-018: `grid` defines three upstream abort and overflow paths

- **Area:** behavior
- **Status:** active
- **What differs:** three inputs kill the C `grid` code:
  - an empty Penrose or spectre description. `grid_desc_to_penrose_params`
    returns before it sets `params->coords`, and the caller frees that
    uninitialised pointer. The committed oracle exits with SIGSEGV and
    `free(): invalid size` for `""` on both types.
  - a one-character Penrose or spectre description. `params->ncoords =
    strlen(desc) - 2` underflows `size_t`; the decoder allocates a huge
    buffer and reads past the string. The Kotlin validator returns
    `expected digit as second char of grid description` or
    `expected digit in grid description`.
  - an aperiodic patch that trims to nothing. `grid_new_penrose` calls
    `grid_trim_vigorous`, which calls `dsf_new(0)`; the assertion
    `0 < size` aborts the oracle. Measured with `penrose_p2_kite`
    `2x2` and description `81B` (SIGABRT). The Kotlin `gridNew`
    returns a grid with zero faces, edges and dots.
- **Why:** the app keeps assertions, so each path is a crash in the
  shipped build. The guards depend only on the input string and the
  generated patch, so the result is deterministic. Valid descriptions,
  generated descriptions, seeds, `DESC`, and `.sgtp` text keep the C
  behavior; every generator, description and encoded RNG state is
  compared with the oracle. A generated description can reach the empty
  patch only at very small sizes, where upstream has no working board.
- **Test:**
  `engine/src/commonTest/kotlin/.../GridTest.kt::theUpstreamAbortPathsAreDefined`
  pins the two empty-description messages, the two one-character
  messages, and the empty `81B` grid. The parity tests do not send these
  inputs to the oracle, because the abort would kill it; every other
  description is compared in `GridParityTest`.
- **Review:** the owner should review this entry before `loopy` or
  `pearl` is enabled in the app.
- **Scope:** `grid`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-019: Pearl defines three upstream buffer overruns

- **Area:** behavior
- **Status:** active
- **What differs:** three C writes can go past an allocation:
  - the drag path. `ui->dragcoords` holds `sz` ints
    (`pearl.c:1939`), and `update_ui_drag` writes
    `dragcoords[ndragcoords]` in the loop-drag branch
    (`pearl.c:2129`). A drag that visits every cell reaches
    `ndragcoords == sz`. The port allocates `sz + 1` entries. The
    extra entry holds the start cell during the degree check. The
    release emits the `ndragcoords - 1` ordinary path edges, so a
    serpentine drag over a `5x5` board commits a 24-edge path and adds
    one state; the extra entry is not part of the move text.
  - the drag extension. C appends every intermediate cell of a
    rook step without a visit test (`pearl.c:2162-2166`). A drag that
    crosses its own path can append a cell that is already on the path
    and grow past `sz + 1`; on a full `5x5` board the next append needs
    index 26 in an array of 26 entries. The port stops the extension at
    the first intermediate cell that is already on the path. The start
    cell is allowed only as the last cell, so the loop-closure rule
    (`pearl.c:2125-2146`) is unchanged. A drag that does not revisit a
    cell appends exactly the C cells.
  - the clue-removal buffer. `cluespace` holds `w*h` ints
    (`pearl.c:1351-1357`), and the second scan fills the corner list
    with the straight positions too (`pearl.c:1359-1361`). A board with
    more than `w*h/2` straight clues writes past the allocation. The
    port allocates `2*w*h` ints and checks the write. An all-straight
    `5x5` clue array needs 50 entries; the C capacity is 25.
- **Why:** an out-of-bounds write is undefined behavior and can corrupt
  the heap. The guards depend only on the board and the drag path, so
  the same input always gives the same result. The revisit stop keeps
  the path bounded: the entries after the start are distinct, and the
  only allowed repeat is the start as the closing cell. A self-crossing
  drag therefore stops at the first visit, and a later release emits
  the edges of the path prefix before the stop. C has no defined result
  for that gesture, so the port pins a deterministic one. No generated
  board reaches the clue-list overflow: a maximal clue array has at most
  one straight clue per cell, and the measured presets stay far below
  half. The drag overflow needs a path that visits every cell and
  returns to the start; the result is deterministic and pinned by the
  engine and input tests.
- **Cost note:** `validate_params` accepts any area up to
  `INT_MAX / h` (`pearl.c:290`). Generation is the C algorithm and has
  the C cost: `13x13de` takes 1.4 to 14.6 seconds at the measured
  seeds, and `14x14` and larger do not finish in 25 seconds. The Kotlin
  generator has the same bound and the same cost. The app must run
  generation off the UI thread; a size limit is an owner decision, not
  a port decision, so this entry adds none.
- **Test:**
  `engine/src/commonTest/kotlin/.../PearlTest.kt::a drag that visits every cell keeps the extra entry and stays defined`
  pins the extra entry, the 24-edge committed path, and the state
  count.
  `...::a drag that crosses itself stops at the visited cell` pins the
  exact 5x5 reproducer: a Hamiltonian path from `(0,0)` to `(2,0)`,
  then a drag into the start. The old guard threw when the extension
  needed index 26; the port stops at `(1,0)`, keeps `ndragcoords` at
  25, and commits the 24 path edges.
  `parity/src/test/kotlin/.../PearlInputParityTest.kt::a self-crossing drag is bounded at a real tile size`
  sends the same gesture through the real input path at requested tile
  48, so the rule is checked at a non-default geometry.
  `...::the clue removal list repeats the straights and the buffer
  covers it` pins the doubled capacity, the `w*h/2` overflow
  boundary, and the repeated straight list. The oracle is not asked
  about any of these paths, because the unmodified C build overruns its
  heap.
- **Review:** the owner should review this entry before `pearl` is
  enabled in the app.
- **Scope:** `pearl`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-020: `loopgen` rejects a one-face grid where C hangs

- **Area:** behavior
- **Status:** active
- **What differs:** upstream `generate_loop` never finishes for a grid
  with one face. The exact input is
  `Grid.new(GridType.SQUARE, 1, 1, null)` followed by
  `generateLoop(grid, IntArray(1), RandomState.fromSeed("1"))`. At every
  dot of the single finite face the only other face is the infinite
  face, so `can_colour_face` alternates between the test face and the
  infinite face and never reaches the canonical `(starting_dot,
  starting_face)` pair (`loopgen.c:174-207`). The C call does not
  return. The Kotlin guard throws
  `IllegalArgumentException` with the message
  `the grid has 1 faces; loop generation needs at least two`, before
  the first `random_bits` draw. The committed oracle also refuses the
  same request: its `loopgen` operation returns
  `loopgen needs a grid with at least two faces`
  (`tools/oracle/oracle.c:2160-2164`), so the oracle protocol cannot
  observe the hang.
- **Why:** a hang is not acceptable in the app. The guard depends only
  on the face count, so the same input always gives the same result,
  and it runs before the RNG state changes. Every grid with two or more
  faces keeps the C algorithm, the C draw order, and the C output;
  `LoopgenParityTest` and the pearl goldens compare those boards. The
  two consumers validate their board sizes before they generate, so the
  guard is reachable only through a direct library call or a
  hand-written description that bypasses the validator.
- **Scope note:** the guard is in `generateLoop`, not in
  `Grid` or a game validator. A caller that needs a one-face grid for
  drawing still gets it; only loop generation is refused.
- **Test:**
  `engine/src/commonTest/kotlin/.../LoopgenTest.kt::a one-face grid is rejected instead of hanging`
  builds the exact input, asserts the one face, and pins the exception
  type. The parity tests do not send the request to the oracle, because
  the vendored C loop does not return.
- **Review:** the owner should review this entry before `loopy` or
  `pearl` is enabled in the app.
- **Scope:** `loopgen`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-021: `loopy` hand-made params report instead of aborting

- **Area:** behavior
- **Status:** active
- **What differs:** two paths in `loopy` are reachable only from a
  hand-made `Loopy.Params`, never from a save or the chooser. First,
  `validate_params` ends with `assert(params->diff < DIFF_MAX)`
  (`loopy.c:731`), which aborts the C process for a difficulty of 4 or
  more. The port returns `Unknown difficulty level`. Second,
  `encode_params` reads `diffchars[params->diff]` (`loopy.c:663`);
  an index above 4 reads out of bounds in C, while the port writes a
  bare `d`, which `decode_params` reads as Easy. Index 4 is in bounds
  in C, because the array ends with a NUL, and both engines write the
  same `7x7t0d` there.
- **Why:** an abort is not acceptable in the app, and the app only
  builds parameters through `decode_params` or `custom_params`, which
  never produce a difficulty above 3. A hand-made value is therefore a
  library-call concern, not a user path. Returning a message keeps the
  validation contract total.
- **Test:**
  `engine/src/commonTest/kotlin/.../LoopyTest.kt::validation follows the C order and the area bound`
  pins the message and the accepted square bound;
  `engine/src/commonTest/kotlin/.../LoopyTest.kt::the parameter grammar matches the C quirks`
  pins the bare `d` for difficulty 4;
  `parity/src/test/kotlin/.../LoopyDescTest.kt::the decode quirks and validation match the oracle`
  pins the same messages through the public seam.
- **Scope:** `loopy`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-022: `rect` refuses an expansion that empties a base side

- **Area:** behavior
- **Status:** active
- **What differs:** `rect` with a side of 1 and a positive expansion
  factor passes `validate_params` in C (`rect.c:221-236`), then
  `new_game_desc` computes the base grid side as
  `(int)(1 / (1 + expandfactor)) == 0` (`rect.c:1160-1170`); the
  expansion then allocates `snewn(params2->h - 1, int)` with `-1`
  (`rect.c:1464`), which aborts the process with
  `allocation too large`. The port throws
  `IllegalArgumentException` with
  `Expansion factor leaves an empty base grid` before it allocates
  anything. The parameter validator still accepts the value, as C does,
  and a save with such parameters still loads because `new_game` never
  expands.
- **Why:** an abort is not acceptable in the app. Every other accepted
  parameter set, including a side of 1 without expansion, keeps the C
  algorithm and the C output.
- **Test:**
  `engine/src/commonTest/kotlin/.../RectTest.kt::an expansion that empties a base side is refused`
  pins the accepted validation, the exception for `1x7e5`, `7x1e5`,
  and `2x1e0.5`, and the normal generation of `1x7` and `7x1`.
- **Scope:** `rect`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-023: `galaxies` defines the hand-made difficulty assert and encoder

- **Area:** behavior
- **Status:** active
- **What differs:** `galaxies` has two paths that only a
  hand-made `Params` value reaches. First, `validate_params` ends with
  `assert(params->diff <= DIFF_UNREASONABLE)` (`galaxies.c:342`), which
  aborts the shipped C build for a difficulty above 1. A negative
  difficulty passes the assert; C then reads
  `galaxies_diffchars[params->diff]` with a negative index in
  `encode_params` (`galaxies.c:286`), which is undefined behavior. The
  port returns `Unknown difficulty level` for every value outside
  `0..1`, which is a defined result for both the abort and the
  undefined read. Second, the encoder has no range test. The array is
  `"nu"` followed by the terminator, so a difficulty of 2 writes a bare
  `d` (the NUL ends the string), and a difficulty above 2 reads past the
  array in C. The port writes a bare `d` for every value outside
  `0..1`, which reproduces the difficulty-2 text and defines the larger
  values.
- **Why:** an abort is not acceptable in the app, and the app only
  builds parameters through `decode_params` or `custom_params`, which
  never produce a difficulty outside `0..1`. The check and the encoder
  are therefore total library functions, not user paths. Every decoded
  or generated parameter set keeps the C seed, `DESC`, `AUXINFO`, and
  save text.
- **Test:**
  `engine/src/commonTest/kotlin/.../GalaxiesTest.kt::validation follows the C order and the area bound`
  pins the message for `-1` and `2`;
  `...::the encoder defines an out-of-range difficulty` pins `7x7d`
  for `2`, `3`, and `-1`;
  `parity/src/test/kotlin/.../GalaxiesDescTest.kt::the validation messages match the oracle in C order`
  pins the decoded messages and the same encoder result.
- **Scope:** `galaxies`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-024: `solo` defines its empty grid, hand-made ranges, and unclued killer cages

- **Area:** behavior
- **Status:** active
- **What differs:** four hand-made inputs reach a C abort, a C hang, or
  C undefined behavior in `solo.c`.

  First, `validate_params` checks only `c < 2`, so `r == 0` and `r < 0`
  are accepted for a non-jigsaw board. `new_game_desc` then aborts for
  `3x0` at `encode_solve_move` (`solo.c:3234`,
  `Assertion 'p - ret == len' failed`) and for `3x-1` in the
  `alloc_block_structure` allocation (`allocation too large`). A
  hand-written `3x0` save loads, reports a 49x49 board, and aborts in
  the text form on the `(cr - 1) / vmod` division by zero. The Kotlin
  `newDesc` and `newState` throw
  `IllegalArgumentException("solo: the grid has no cells")` before any
  RNG draw or allocation. `validateParams` keeps the C acceptance and
  the C messages.

  Second, a hand-made `Params` can carry a difficulty outside
  `0..5`, a killer difficulty outside `0..3`, or a symmetry outside
  `0..7`. `decode_params` and `custom_params` never produce one, so no
  save or chooser value reaches it. C `validate_params` has no such
  check; the generator's final gate `dlev.diff == dlev.maxdiff` (or the
  killer pair) can then never hold, and the outer `while (1)` does not
  terminate. An unknown symmetry falls through `symmetries` to the
  single-square case but `encode_params` writes nothing, so the
  re-encoded game is not the one that was generated. The Kotlin
  `newDesc` throws `solo: unknown difficulty`, `solo: unknown killer
  difficulty`, or `solo: unknown symmetry`, in that order, before the
  first `random_bits` draw.

  Third, `validate_desc` does not require a clue in every killer cage.
  A hand-written `kgrid` with an unclued cage loads and reports the same
  id, but the C solver asserts `usage->kclues[i] > 0`
  (`solo.c:1757`) and the drawing path asserts `clue != 0`
  (`solo.c:3025`). The Kotlin solver reads the missing clue as 0: a
  single-square cage of value 0 is impossible, the minmax and sums
  analyses have no information for that cage, and a complete grid with
  that cage never satisfies `check_killer_cage_sum`. Drawing paints the
  cage with `COL_ERROR` instead of aborting. The missing clue makes the
  puzzle ambiguous, so `solve` without `AUXINFO` returns null; a stored
  answer is still returned unchanged, as `solve_game` does.
- **Why:** a crash, a hang, or an infinite generation loop is not
  acceptable in the app, and each guard depends only on the input, so
  the same request always gives the same result. Every decodable
  parameter string is inside the ranges, so no seed, `DESC`, `AUXINFO`,
  or `.sgtp` file of a real game changes. The killer cage results are
  deterministic and keep the C save format.
- **Test:**
  `engine/src/commonTest/kotlin/.../SoloTest.kt::hand made parameters are defined before any random draw`
  pins the four messages, the accepted validator, the untouched RNG
  state, and the `newState` guard;
  `engine/src/commonTest/kotlin/.../SoloTest.kt::a killer description without a clue is defined`
  pins the ambiguous solve, the stored-answer path, the open status, and
  the failed completion;
  `parity/src/test/kotlin/.../SoloSaveTest.kt::a killer save with an unclued cage is a defined port result`
  pins the C id, the loaded state, the empty killer text form, the null
  solve, and the stored answer;
  `parity/src/test/kotlin/.../SoloTraceTest.kt::a killer save with an unclued cage still draws`
  pins the error-colored cage and the four block outlines. The tests do
  not ask the C oracle to generate the empty grid or solve or draw the
  unclued killer save, because that kills the oracle process; the
  one-off oracle runs above are the abort evidence.
- **Review:** the owner should decide the final empty-grid and
  hand-made-range behavior before `solo` is enabled in the app.
- **Scope:** `solo`
- **Upstream:** none known
- **Date:** 2026-11-17

### D-025: `solo` retries a killer singleton merge that overflows the C row

- **Area:** behavior
- **Status:** active
- **What differs:** `gen_killer_cages` removes single-square cages
  with `merge_blocks` (`solo.c:3611-3621`) and never checks the combined
  size against the cage row capacity `b->max_nr_squares`, which is
  `cr`. When the randomly selected neighbour is already a full
  `cr`-square cage, `merge_blocks` writes
  `b->blocks[n][b->max_nr_squares]` and increments `nr_squares[n]` to
  `cr + 1`. In C that slot is the first entry of the next block row in
  `blocks_data`, so the write is out of bounds and corrupts the cage
  rows; the generated description is produced from whatever the
  corrupted rows contain. For `3jka` seed `1` and `2x2xka` seed `1` the
  C build still returns a description that reloads, but the write is
  undefined behavior and depends on the allocator layout. The Kotlin
  `genKillerCages` detects the overflow before the write, abandons that
  generation attempt, and retries the outer loop from the advanced RNG
  state. The result is a valid description with a different killer cage
  layout. The `3x3ka` preset at the tested seeds and every other preset
  are unchanged.
- **Why:** an out-of-bounds write is undefined behavior and can corrupt
  the heap. The overflow depends only on the current cage sizes and the
  selected neighbour, so the retry is deterministic. Every valid
  generated description, save, and `.sgtp` file is accepted; only the
  corrupt path is replaced by a valid puzzle.
- **Test:**
  `parity/src/test/kotlin/.../SoloDescTest.kt::a killer singleton merge that overflows the C row is retried`
  pins the C save reload, the different port description, the port
  validation, and the C reload of the port save;
  `engine/src/commonTest/kotlin/.../SoloTest.kt::a killer singleton overflow is retried`
  pins the valid description, determinism for the same seed, and the
  engine reload. The tests do not reproduce the C write; they ask the
  oracle only for the output it happens to return.
- **Review:** the owner should review this entry before `solo` is
  enabled in the app. The guard runs for every killer generation; the
  tested `3x3ka` seeds and every other preset do not change. A killer
  board with a small `cr` can reach it, so the retry is the intended
  app behavior.
- **Scope:** `solo`
- **Upstream:** none known
- **Date:** 2026-11-17

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
