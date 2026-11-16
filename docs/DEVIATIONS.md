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
