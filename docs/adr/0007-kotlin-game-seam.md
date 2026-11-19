# ADR 0007: Kotlin game seam and the semantic midend

- **Status:** accepted
- **Date:** 2026-11-14
- **Superseded in part by:** `docs/adr/0008-run-fifteen-on-kotlin.md`
  and `docs/adr/0011-enable-games-in-waves.md`. Decision 4 says
  `fifteen` is `dual-run`; `fifteen` is `kotlin` since 2026-11-14 and
  every ported game is `kotlin` since the eighth wave, with C as the
  oracle only. The seam itself is unchanged.

## Context

Phase 3 ported the portable infrastructure that has a standalone
contract: the RNG, the save container, the deterministic helpers, the
solver utilities, and the drawing trace contract. Phase 4 starts with
`fifteen`, the first game. A game needs two seams that did not exist:

- a game contract that owns parameters, generation, state, moves,
  text, status, and drawing;
- a midend that owns history, undo and redo, solve, and the save file.

Upstream `midend.c` is 3,295 lines. Most of it is the shell: menus,
configuration dialogs, timers, preferences, size negotiation,
animations, and frontend callbacks. None of that is reachable from a
stateless parity request, and none of it changes a saved state. Porting
it before the shell needs it would repeat the unverified-translation
problem that ADR 0006 rejects.

The strict trace contract (ADR 0003) is the opposite case: `fifteen`
draws with a small, bounded set of primitives, and the Kotlin drawing
must be checked against the frozen traces, not just described as
"looks the same".

## Decision

1. **The game seam is `engine/Game.kt`.** It declares parameters,
   presets, the description generator, description validation, state
   creation, the solver hook, move execution, status, text, requested
   keys, geometry, colours, input interpretation, and drawing. A game
   defines nested `Params` and `State` types and implements the
   interface. The seam grows with each ported game; an operation that
   no game needs is not declared, so it cannot become a silent stub.

2. **The midend is a semantic subset.** `engine/Midend.kt` owns the
   parameters, the seed, the description, the state history, and the
   position in that history. It reproduces `midend_serialise` and
   `midend_deserialise` record for record, and it reproduces the
   history rules: a move truncates the redo branch, undo and redo move
   the position, and solve appends a `SOLVE` entry. Menus, timers,
   preferences, animations, and size negotiation stay in C until the
   Android shell uses a Kotlin game.

3. **Drawing goes through `DrawSink`.** The seam has the primitives a
   ported game uses. `Fifteen` records the same operations as the C
   `game_redraw`, and the parity test puts both through
   `TraceNormalizer`. A trace difference is a geometry bug, not a
   rendering preference.

4. **`fifteen` is `dual-run`, not `kotlin`.** Both implementations pass
   the same matrix, and C still decides in the app. The game becomes
   `kotlin` only after the feature flag has been on by default for two
   releases without P1 bugs, as `ROADMAP.md` requires.

5. **The app is wired in a separate step.** Until `GameEngineImpl` can
   choose a Kotlin backend, the app keeps using C for `fifteen`. The
   parity suite is the gate for that step; it is not replaced by it.

## Consequences

Positive:

- the first game fixes the seam that the rest of Phase 4 uses;
- the generator, moves, history, save file, solver, and drawing of
  `fifteen` are all checked against the oracle;
- the midend port stays small enough to review and does not guess at
  shell behavior that has no oracle;
- the same `DrawSink` will serve the next game without a second trace
  format.

Negative:

- `Game.Params` and `Game.State` are marker interfaces, so a game casts
  its own types; Kotlin has no sealed hierarchy across modules that
  would remove the casts;
- the semantic midend is not yet a drop-in replacement for the C
  midend in the app;
- `DrawSink` has only the primitives `fifteen` needs and grows with
  each game.

## Alternatives

1. **Port all of `midend.c` now.** Rejected: most of it is shell code
   with no oracle before the Kotlin shell exists.
2. **Skip the midend and test the game only.** Rejected: the parity
   matrix requires undo, redo, solve history, and `.sgtp` in both
   directions.
3. **Test the Kotlin drawing by comparing pixels.** Rejected: ADR 0003
   makes the primitive trace the contract; screenshots cannot gate CI
   while D-04 is open.
4. **Wire the app immediately.** Rejected: the feature flag and the
   chooser are a separate change, and the matrix must stay green
   without it.

## Amendment: changed state, UI status text, and can-solve

- **Date:** 2026-11-15

The samegame port needs three seam operations that fifteen and slant
did not have in the seam. They are added to the existing seam, not to a
new one.

1. **`Game.changedState(ui, oldState, newState): Boolean`** mirrors
   `game_changed_state`. `Midend` calls it where `midend.c` does:
   before `statePos--` in `undo` (old is the state being left, new is
   the state being entered), before `statePos++` in `redo`, after the
   append in `restart`, after the append in a real move in
   `processKey`, and after the append in `solve`. A UI-only key and a
   no-effect key do not call it. The default returns false, so a game
   with no UI consequence declares nothing.

2. **The completion signal is a consumable event, not a status
   comparison.** `Midend.takeCompletedEvent()` returns true once when
   `changedState` reported a completion, then clears the flag. The app
   uses it in place of the previous `status()` transition plus the
   fifteen-specific `usedSolve` field. A status comparison is wrong for
   three reasons. It cannot tell a solve from a normal move. It fires
   on any transition that lands in a completed state, so a redo into
   the completed state looks like a new completion and the check must
   remember whether it already fired. And it reads game state from the
   app, which forces a cast to one game's `State`; samegame has no
   `usedSolve`.
   The C midend asks the game, with both states, and calls
   `android_completed` once at a defined point. The event reproduces
   that contract and keeps game types out of the app. `midend_solve`
   calls `changed_state` for its UI effect but ignores the return
   value, so a solve never raises the event, as in C.

3. **`Game.statusText(state, ui)`** takes the frontend state. The
   samegame status text contains `ui->nselected` and the selection
   preview, so a state-only signature cannot produce the C string. The
   midend still emits the text right after `game.redraw`, because that
   is where the C games call `status_bar`; no game draws its own status
   bar. Fifteen's text is unchanged and slant's stays empty while
   `wantsStatusBar` is false.

4. **`Game.canSolve`, default true.** The C `can_solve` flag is
   static, and a solver that returns null is only known after the user
   tries it, which is too late to hide the menu item. The shell sets
   the solve bit in `uiVisibility` from this property instead of
   hard-coding it. Fifteen and slant are true; samegame will be false.

The restart call site cannot raise the event for fifteen or slant,
because `newState` always starts with `completed == false`. It is
reachable only for a game whose initial state can be complete. The
tests therefore pin the move, solve, undo, and redo paths.

## Amendment: input canonicalisation and keyboard modifiers

- **Date:** 2026-11-15

`Midend.processKey` removed the modifier bits before it called
`game.interpretMove`. In C, `midend_process_key` filters the modifiers
it understands and sends the result to `interpret_move`; `fifteen.c`
and `sixteen.c` then call `STRIP_BUTTON_MODIFIERS` themselves, while
`sixteen.c` reads `MOD_SHFT`, `MOD_CTRL`, and `MOD_NUM_KEYPAD` before
it strips them. A seam that always strips cannot express `sixteen`, and
its shift-lock and pad behaviour is a game rule, not a shell
preference.

1. **`Buttons` carries the modifiers.** `MOD_CTRL` is `0x1000`,
   `MOD_SHFT` is `0x2000`, `MOD_NUM_KEYPAD` is `0x4000`, and
   `MOD_MASK` is `0x7000`, as in `puzzles.h`. `Buttons.stripModifiers`
   is `STRIP_BUTTON_MODIFIERS`.

2. **The midend canonicalises the key, then splits it.**
   `Midend.processKey` is the counterpart of `midend_process_key`, and
   `reallyProcessKey` is the counterpart of
   `midend_really_process_key`. The first canonicalises the button, the
   second recognises `UI_UNDO`, `UI_REDO`, and `UI_SOLVE` and calls
   `game.interpretMove`. The history, the `MoveResult` handling, and
   `changedState` are unchanged. The dispatch order is corrected in the
   last amendment.

3. **Each game still strips what it does not use.** A cursor move keeps
   `CTRL` and `SHFT`, and TAB keeps both; every other key reaches the
   game stripped. `Fifteen`, `Slant`, `SameGame`, `Flood`, and `Flip`
   call `Buttons.stripModifiers` at their entry, so a second strip
   changes nothing. `Sixteen` reads `MOD_CTRL`, `MOD_SHFT`, and
   `MOD_NUM_KEYPAD` before it strips them, so its shift-lock and pad
   behavior remains a game rule. The cursor rule of `Slant`, `SameGame`,
   and `Flood` is corrected in the last amendment.

4. **The C canonicalisation is ported in the C order.** `processKey`
   does this, before the game call:

   1. a drag or a release takes the button that is held
      (`LEFT_DRAG - LEFT_BUTTON` or `LEFT_RELEASE - LEFT_BUTTON`); an
      event with no held button is ignored;
   2. a press while another button is held first sends a release for the
      held button through `reallyProcessKey`, and the two outcomes are
      combined as C combines them with `min`;
   3. `CTRL`+ASCII in `0x40..0x7F` becomes the matching control
      character;
   4. `CTRL+SHFT+Z` becomes `UI_REDO`;
   5. `CTRL` on a key that is not a cursor move and has a code of at
      least `0x20` is rejected; otherwise `CTRL` and `SHFT` are stripped,
      except on TAB;
   6. `MOD_NUM_KEYPAD` is kept only on a digit;
   7. `'\n'` and `'\r'` become `CURSOR_SELECT`, and `' '` becomes
      `CURSOR_SELECT2`;
   8. `'\177'` becomes `'\b'`.

   A rejected `CTRL` key returns `Unused` and does not reach the game,
   so `CTRL+UI_UNDO` is not undo. `SHFT+UI_UNDO` still is, because
   `SHFT` is stripped. This matches C. An earlier version of this
   amendment claimed that a modified UI key is always a UI action, which
   is wrong for `CTRL`.

5. **`pressedMouseButton` is frontend state.** It records the button
   that is held, starts at 0, and is updated from the final canonical
   button: a release clears it, and a press stores it. It is not
   serialised, and `newGame`, `fromGameId`, and `fromSave` start at 0,
   as `midend_new`, `midend_new_game`, and `midend_deserialise` do in C.

6. **The `BUTTON_BEATS` priority is a seam hook, not a flag.** No ported
   game declares button priority. `Midend.buttonBeats` is the single
   place where the future `mines` flag is read; it returns false today.
   The rule and the flag formula are recorded there, so the `mines` port
   adds a flag and not new control flow.

The undo and redo aliases that `midend_really_process_key` handles for
control characters (`\x1A`, `\x1F`, `*`, `#`, `\x12`, `\x19`),
`UI_QUIT`/`UI_NEWGAME`, and the one-key shortcuts are not part of this
amendment. The next amendment ports them, except for new game and quit,
which need the menu path that lives in the shell.

## Amendment: modified cursor keys and one-key shortcuts

- **Date:** 2026-11-15

Two input differences from `midend.c` are fixed here. Neither changes a
game rule or a save file.

1. **A modified cursor key is not a cursor move in every game.**
   `midend_process_key` keeps `CTRL` and `SHFT` on a cursor key
   (`midend.c:1239-1246`) and lets the game decide:
   `IS_CURSOR_MOVE` compares the exact button (`puzzles.h:71-72`).
   `fifteen` and `sixteen` call `STRIP_BUTTON_MODIFIERS` at their entry.
   `slant`, `samegame`, `flood`, and `flip` compare the exact button, so
   a modified cursor move returns `MOVE_UNUSED`. The ports of `slant`,
   `samegame`, and `flood` used to strip the modifiers at entry and moved
   the cursor. They now use the exact button. A non-cursor key still
   arrives canonicalised by the midend, so the distinction exists only
   where C makes it. `Fifteen` and `Flip` keep their current behavior.

2. **The game sees a key before the shortcuts do.** `reallyProcessKey`
   now follows C:
   - a UI fake key is not passed to `interpretMove`;
   - every other button goes to `interpretMove` first;
   - only `Unused` applies the shortcut chain, in C order: new game,
     undo, redo, solve, quit;
   - undo and redo return `NoEffect` when there is no history;
   - a game with a solver returns `SomeEffect` for solve even when the
     solver returns null, because C sets `PKR_SOME_EFFECT` before it
     tries `midend_solve`; a game without a solver returns `Unused` for
     `'\x13'` and `UI_SOLVE`;
   - the aliases are `'n'`, `'N'`, `'\x0E'`, `UI_NEWGAME`; `'u'`,
     `'U'`, `'*'`, `'\x1A'`, `'\x1F'`, `UI_UNDO`; `'r'`, `'R'`, `'#'`,
     `'\x12'`, `'\x19'`, `UI_REDO`; `'\x13'`, `UI_SOLVE`; `'q'`, `'Q'`,
     `'\x11'`, `UI_QUIT`.
   `CTRL+Z` canonicalises to `'\x1A'`, so it undoes. `CTRL+SHFT+Z`
   becomes `UI_REDO` in the midend, so it redoes. At the time of this
   amendment `one_key_shortcuts` was always on, the C default, and the
   seam had no preference store; the S8 amendment below adds the store
   and keeps `one-key-shortcuts` as its first value.

3. **New game and quit stay in the shell.** This seam owns no new-game
   or quit action, and `midend_new_game` needs the menu path that lives
   in the shell. `'n'`, `'N'`, `'\x0E'`, `UI_NEWGAME`, `'q'`, `'Q'`,
   `'\x11'`, and `UI_QUIT` return `Unused`. The shell performs those
   actions and must not rely on the engine for them. This is a seam
   limitation, not a game deviation, so `DEVIATIONS.md` has no entry.

## Amendment: arrow-only key labels and games with no text form

- **Date:** 2026-11-15

`guess` needs two seam facts that the ported games do not use. Both are
defaults, so no ported game changes. Porting `guess` itself is a later
change.

1. **`Game.KeyLabel.needsArrows`, default false,** mirrors
   `key_label.needs_arrows` (`puzzles.h:233-241`).
   `KotlinGameEngine.requestKeys` splits the requested labels the way
   `android.c:784-794` does: a label with the flag false goes into
   `KeysResult.keys`, and a label with the flag true goes into
   `KeysResult.keysIfArrows`. Each group keeps the request order, and
   each button becomes one character of its string, as C casts the
   button to `char`. A game that does not use the flag returns the
   labels and the arrow mode it returned before.

2. **`Game.canFormatAsText`, default true,** mirrors
   `can_format_as_text_ever`. `Midend.textFormat` calls the game only
   when the flag is true. `midend_text_format` returns NULL for a game
   without a text form (`midend.c:2087-2094`), and `guess.c:1574` is
   the first game with `can_format_as_text_ever = false` and a NULL
   `text_format`. The seam's `textFormat` returns a non-null `String`,
   so it returns an empty string in that case. The app and the tests
   then use the value without a null check or a crash. A caller that
   must tell "no text form" from "an empty board" reads
   `canFormatAsText` first. No parity test compares the text of a game
   whose flag is false, so the empty string cannot hide an oracle
   difference.

## Amendment: the Android full-cursor key flag

- **Date:** 2026-11-16

`palisade` is the only upstream game whose key labels depend on the
Android shell. `game_request_keys` (`palisade.c:742-771`) reads its
`arrow_mode` argument as an input flag. `midend_request_keys_by_game`
presets that argument to `ANDROID_ARROWS_ONLY` when the shell asks for
a full-grid cursor (`midend.c:1290-1294`). In that mode the game returns
four labels, `H`, `J`, `K`, and `L` with the text `Left`, `Bottom`,
`Top`, and `Right`, and keeps `ANDROID_ARROWS_ONLY`. In every other mode
it returns no labels and sets `ANDROID_ARROWS_LEFT_RIGHT`.

1. **`Game.requestKeys(params, fullCursor): KeyRequest`** carries the
   flag. The default calls `requestKeys(params)`, so no ported game
   changes. The flag is an input value for the game, not a request that
   the shell already resolved, because C lets the game read it and
   overwrite the arrow mode in either direction.

2. **The flag is the Android C hack, not UI state.** It mirrors the
   `cursor-mode` preference of the Android shell, which has no upstream
   counterpart. The engine does not store it in `Params`, `Ui`, the
   seed, or the save file. It reaches the game only through this
   argument, and `KotlinGameEngine.requestKeys` passes the
   `palisadeFullCursor` value it receives from the shell. As in C, the
   value is true only for `palisade`; `GamePlay.isPalisadeFullCursor`
   checks the backend before it reads the preference.

3. **The palisade port overrides the operation.** The port reads the
   `fullCursor` argument, as the C game reads the preset arrow mode.

`keysResultFor` gains the flag as a required argument. A default would
hide a dropped value: a call site that forgot it would compile and
silently request the wrong keys, which is the bug this amendment fixes.

## Amendment: the Android inertia follow callback

- **Date:** 2026-11-16

`inertia.c` calls `inertia_follow(dr, is_solved)` from `game_redraw`
(`inertia.c:2128`) under `#ifdef ANDROID`. It is the `inertia_follow`
entry of the Android `drawing_api` (`puzzles.h:867`), not a game rule:
`android.c:285-289` forwards the boolean to the activity, and
`GamePlay.inertiaFollow` only hides or shows the primary controls while
the auto-solver steers the player. The value is
`state->cheated && state->soln && state->solnpos < state->soln->len`.
It changes no state, RNG draw, seed, `DESC`, `.sgtp`, `AUXINFO`, or
palette, so it has no parity entry and no deviation.

1. **`DrawSink.inertiaFollow(isSolved)`** is the seam operation. The
   game calls it from `redraw` at the point where the C game calls the
   drawing API. It is not a `Game` property: the value depends on the
   current state and the solution position, and the shell must hear it
   on every redraw. A game that does not use the callback, which is
   every ported game today, does not call it.

2. **The trace records the oracle fields in the oracle order.**
   `KotlinTraceSink` writes `{"op":"inertia_follow","solved":...}`,
   with `solved` as a JSON boolean, as `tr_inertia_follow`
   (`oracle.c:707-715`) does. `TraceNormalizer` already accepts the
   line.

3. **`KotlinGameEngine` forwards it to the activity.** The sink calls
   `ActivityCallbacks.inertiaFollow`, the same callback that
   `android.c` invokes through JNI. The engine stores nothing and
   applies no shell behavior of its own.

4. **The operation stays inside the draw.** It appears after the
   operations the game emitted before the callback and before
   `end_draw`. In `inertia.c` the call is before `status_bar`, so the
   trace places it before the status line, as C does. The callback is
   never outside the `start_draw` and `end_draw` pair.

This change does not port `inertia`. It adds the callback that the port
will call, and the port remains a separate change with its own parity
matrix.

## Amendment: the thick line primitive

- **Date:** 2026-11-16

`undead` draws its mirrors with `draw_thick_line` (`undead.c:2633`).
The oracle already records the primitive, `TraceNormalizer` already
accepts `thick_line`, and the Android `drawing_api` already has the
callback. This change adds the seam operation, not the game.

1. **`DrawSink.thickLine(x1, y1, x2, y2, thickness, colour)`** keeps
   the endpoint order and the integer coordinates of
   `DrawSink.line`, so a game that has both primitives reads them the
   same way. The thickness follows the coordinates in the seam; the
   oracle and the Android callback take it first, so each sink places
   it there.

2. **The trace sink copies `tr_draw_thick_line`**
   (`oracle.c:695-706`) field for field:
   `{"op":"thick_line","t":round(thickness*1000),"x1":..,"y1":..,
   "x2":..,"y2":..,"c":..}`. The thickness and both endpoints are in
   thousandths, as `tc_milli` produces. The seam coordinates are
   already integers, so their milli fields are exact, and the thickness
   rounds to the nearest thousandth as `lroundf` does. The field order
   and the names are the oracle's; `TraceNormalizer` maps them to
   `thick_line t x1 y1 x2 y2 c`.

3. **The engine forwards the primitive unchanged.** It adds the board
   origin to both endpoints and calls
   `viewCallbacks.drawLine(thickness, x1+originX, y1+originY,
   x2+originX, y2+originY, colour)`, which is the call that
   `android.c:187-191` makes through JNI. The thickness is not scaled
   by the origin or by the zoom.

4. **No ported game calls it.** Every frozen trace stays byte for byte
   the same, and the parity suite pins that. The `undead` port remains a
   separate change with its own parity matrix.

## Amendment: fractional thick line endpoints

- **Date:** 2026-11-16

`tracks` draws `draw_thick_line` at fractional coordinates. The circle
sleepers are `cx + r*cos(th)` (`tracks.c:2614-2618`), and the straight
sleepers and spokes divide the tile by 8 (`tracks.c:2633`, `2643`,
`2669-2689`). The frozen trace already contains
`thick_line 3750 63000 73000 59250 73000 5`, which an `Int` endpoint
cannot express. This amendment supersedes the integer endpoints of the
thick line amendment above.

1. **`DrawSink.thickLine` takes `Float` endpoints.** The signature is
   `thickLine(x1: Float, y1: Float, x2: Float, y2: Float,
   thickness: Float, colour: Int)`, which matches the `float`
   coordinates of upstream `draw_thick_line`. The argument order is
   unchanged.

2. **The trace sink records every value with `tc_milli`.** Each
   endpoint and the thickness is `lroundf(value * 1000)`, which rounds
   a half away from zero. An integer coordinate converts exactly, so
   every frozen trace of a game that passes integers is unchanged.

3. **The engine adds the origin as a float.** `KotlinGameEngine`
   computes `x1 + originX.toFloat()`, and the same for the other three
   endpoints, before it calls `viewCallbacks.drawLine`. The thickness is
   not scaled by the origin or by the zoom.

4. **`undead` widens its integer endpoints at the call.** Upstream
   `undead.c:2633` already passes the integer endpoints as floats, so
   the values and the traces do not change.

`ThickLineTraceTest` pins the fractional rounding (`59.25` to `59250`,
`3.125` to `3125`, `0.0625` to `63`, and `-0.0625` to `-63`), the
`tracks`-shaped line, and the unchanged integer form.
`KotlinGameEngineThickLineTest` pins the origin offset.

The `tracks` port remains a separate change with its own parity matrix.

## Amendment: the platform random seed (S7)

- **Date:** 2026-11-16

`midend_new` asks the frontend for a random seed before it builds
anything (`midend.c:172-181`). `net.c` uses that seed in `new_ui` to
create `ui->rs`, and `ui->rs` drives the Jumble move
(`net.c:2074-2075`, `net.c:2433`). It is not the game seed: it is
frontend state that no record stores. The seam had no way to supply
it, so a net port could not reach the oracle.

1. **The platform seed is a midend argument, not a game seed.**
   `Midend.newGame`, `Midend.fromGameId`, and `Midend.fromSave` take
   `platformSeed: ByteArray`. The midend passes it to
   `Game.newUi(state, platformSeed)` and uses it for no other purpose.
   A save that has no `UI` record keeps that UI.

2. **The default is one NUL byte.** The oracle returns one NUL byte
   from `get_random_seed` (`tools/oracle/oracle.c:101-107`), so the
   default keeps every parity run and every existing test
   deterministic. A test that ignores the argument still compiles,
   because the two-argument `newUi` defaults to the one-argument
   `newUi`. `PlatformSeedTest` pins both the default and an explicit
   value.

3. **The app supplies a real seed.** `KotlinGameEngine` calls
   `platformSeed()`, which returns 16 bytes from `System.nanoTime()`
   and `System.currentTimeMillis()`. The helper lives in the app
   module, never in the engine common code, so the engine has no
   clock and the parity module cannot read one. `KotlinGames.generate`
   passes the same value to `newGame`, as the C frontend does.

4. **The seed stays out of the save file.** It does not enter
   `State`, the history, `id()`, `DESC`, `AUXINFO`, `UI`, or
   `serialise()`. A game that encodes random frontend state would be
   a deviation from the record contract and needs its own ADR; no
   ported game does. `PlatformSeedTest` pins that two platform seeds
   produce the same save text and the same game ID.

S7 is done with this change. The `net` port remains a separate change
and needs the game-level `newUi` and `decodeUi` overrides; the seam
only guarantees the bytes.

## Amendment: the timed game clock (S5)

- **Date:** 2026-11-16

`mines` is the only upstream game with `is_timed` set. `midend.c` keeps
an `elapsed` float and a `timing` flag, asks `timing_state` after every
state change, prefixes the status text with `[m:ss] `, and writes a
`TIME` record when it saves. The seam had none of this.

1. **`Game.isTimed` and `Game.timingState` are the game side.**
   `isTimed` mirrors the static `is_timed` flag and defaults to false.
   `timingState(state, ui)` mirrors `timing_state` and defaults to
   false, so an untimed game declares nothing. A timed game returns
   true only while the clock should run; `mines` stops it on a win, on
   a loss, and after a solve.

2. **`Midend` owns `elapsed` and `timing`.** `elapsed` is the float C
   stores, in seconds, with a private setter. `timing` is the last
   answer of `game.isTimed && game.timingState(state, ui)`.

3. **`Midend.timerCallback` is the shell switch.** It is the seam
   counterpart of `activate_timer` and `deactivate_timer`. The setter
   calls the callback with the current `timing` value, so a shell that
   attaches after a new game or a load starts in sync. `refreshTimer`
   calls it again only on a transition. The midend calls `refreshTimer`
   after construction, after `fromSave`, and after `processKey`,
   `undo`, `redo`, `solve`, and `restart`. The C midend calls
   `midend_set_timer` after the move, solve, restart, new game, load,
   and timer paths; the undo and redo calls are extra and safe because
   `timing_state` is a pure function of the state and the UI.

4. **`Midend.timerTick(tplus)` is `midend_timer` without the animation
   clocks.** While `timing` is true it adds `tplus` to `elapsed` and
   returns true when `(int)elapsed` changed, which is the condition
   under which C rewrites the status bar. It refreshes the timer state
   on every call, as C does. The seam does not port `anim_time`,
   `anim_pos`, `flash_time`, or `flash_pos`: no ported game uses them,
   and a game that needs them adds them with its own trace.

5. **`statusText` writes the C prefix.** A timed game is prefixed with
   `sprintf("[%d:%02d] ", min, sec)`, where `sec` is `(int)elapsed`, so
   the output is `[0:00] `, `[0:05] `, and `[1:05] ` for 0, 5, and 65
   seconds. The prefix is part of `midend_rewrite_statusbar` and not a
   game rule.

6. **The `TIME` record keeps its C position and format.** `serialise`
   writes `TIME` after `UI` and before `NSTATES`, and only for a timed
   game. The value comes from the shared `%g` helper `cFloatG`, which
   is the formatter that `net` already used for the `b` parameter
   field. A save without `TIME` loads at 0, as C leaves
   `data.elapsed` at its zero initializer.

The engine clock is not the shell clock. The engine never reads a
system clock, so the parity module stays deterministic. The app
measures each interval with `System.nanoTime()`, from the previous tick
or from `resetTimerBaseline`, and passes the delta to
`midend.timerTick`. The baseline is reset when the view regains focus,
so the time spent in the background does not enter the game time.
`KotlinGameEngine` redraws only when the displayed second changed.

`TimerTest` pins the two transitions, the accumulated time, the three
prefix strings, both save directions, the position of `TIME`, and the
untimed game.

## Amendment: the public and private description (S6)

- **Date:** 2026-11-16

`mines` is the first game whose description is only a promise of a
board until the first click. `open_square` generates the mine layout
inside `execute_move` and calls `midend_supersede_game_desc`
(`mines.c:2163`). The midend then replaces `DESC`, writes the masked
layout as `PRIVDESC`, and a load rebuilds state 0 from `PRIVDESC`
before it applies the public text with `set_public_desc`. The seam had
none of this, and no game could keep its own description across a save.

1. **A game describes a state.** `Game.publicDesc(state)` returns the
   new public text, or null when the description does not change.
   `Game.privateDesc(state)` returns the `PRIVDESC` value, or null.
   `Game.setPublicDesc(state, text)` applies the public text to a state
   that was rebuilt from `PRIVDESC` and returns the result. All three
   default to no change, so every ported game keeps its current save
   text and every `.sgtp` file without `PRIVDESC` loads as before.

2. **The midend supersedes after the move is appended.** The C game
   calls the midend from inside `execute_move`; the seam keeps a game
   pure and asks it after the move. `processKey` reads
   `game.publicDesc(newState)` after it appends the `MOVE` entry and
   calls the game's `changedState`. A null text, or the text the midend
   already holds, changes nothing. Otherwise the midend replaces
   `desc` and `privDesc` together, as `midend_supersede_game_desc`
   replaces both strings. `desc` and `privDesc` have private setters,
   so only the midend changes them. `id()` and `restart()` keep using
   the public `desc`.

3. **The record order is `DESC`, `PRIVDESC`, `AUXINFO`.** `serialise`
   writes `PRIVDESC` directly after `DESC`, and only when it is
   non-null, which is the C order (`midend.c:2297-2298`). The `UI` and
   `TIME` records keep their positions after `AUXINFO` (ADR 0009), so a
   game that writes all of them has the order `DESC`, `PRIVDESC`,
   `AUXINFO`, `UI`, `TIME`, `NSTATES`.

4. **A load rebuilds state 0 from the private description.**
   `fromSave` validates `PRIVDESC` with `validateDesc` when the record
   is present, then builds state 0 with
   `Game.newState(currentParams, privDesc)` and applies the public
   `DESC` with `Game.setPublicDesc`. Without the record it builds state
   0 from `DESC`, as before. The `MOVE`, `SOLVE`, and `RESTART` records
   replay on the rebuilt state, and the stored `DESC` and `PRIVDESC`
   are written back unchanged. The private text is not validated
   against the public one: `mines` masks the layout, so the two texts
   describe the same board in different forms.

5. **The `flags` word is a game property.** `Game.flags` mirrors
   `thegame.flags` and defaults to 0. The seam defines
   `SOLVE_ANIMATES` (bit 9), `REQUIRE_RBUTTON` (bit 10), and
   `REQUIRE_NUMPAD` (bit 11), and `Game.buttonBeatsBit(x, y)` is the
   `BUTTON_BEATS` macro. `Game.buttonBeats(x, y)` is the priority
   query, `(flags & BUTTON_BEATS(x, y)) != 0`. `Midend.buttonBeats`
   reads the game's `flags` word directly, so a test game that
   implements `Game` by delegation cannot hide the flags of its own
   declaration; `mines` declares
   `BUTTON_BEATS(LEFT_BUTTON, RIGHT_BUTTON) | REQUIRE_RBUTTON`, and an
   overlapping right press is ignored while the left button is held.
   The input canonicalisation amendment above recorded the hook when no
   game used it; this closes that item. The seam reads no other flag
   bit, because `SOLVE_ANIMATES`, `REQUIRE_RBUTTON`, and
   `REQUIRE_NUMPAD` change only shell behavior and the Android shell
   has no use for them yet.

`SupersedeDescTest` pins the supersede, the two records and their
order, the changed `id()`, the restart from the public text, the
state-0 rebuild with the public overlay, the unchanged save without
`PRIVDESC`, and the button priority against a flagless game. The test
game delegates to `fifteen`, so the seam is checked without a mines
port. `mines` still needs its own generator, layout decoder, drawing,
and parity matrix.

## Amendment: the thick circle primitive

- **Date:** 2026-11-16

`tracks` draws its sleepers with `draw_thick_circle`
(`tracks.c:2658-2661`). The oracle already records the primitive,
`TraceNormalizer` already accepts `thick_circle`, and the Android
`drawing_api` already has the callback. This change adds the seam
operation, not the game.

1. **`DrawSink.thickCircle(cx, cy, radius, thickness, fillColour,
   outlineColour)`** keeps the centre, the radius, and the two colours
   of `DrawSink.circle`, so a game that has both primitives reads them
   the same way. The thickness follows the radius in the seam; the
   oracle and the Android callback take it first, so each sink places it
   there. Upstream `draw_thick_circle` takes
   `(thickness, cx, cy, radius, fillcolour, outlinecolour)`.

2. **The trace sink copies `tr_draw_thick_circle`**
   (`oracle.c:520-532`) field for field:
   `{"op":"thick_circle","t":round(thickness*1000),"cx":..,"cy":..,
   "r":..,"fill":..,"outline":..}`. The thickness, the centre, and the
   radius are in thousandths, as `tc_milli` produces. The seam centre
   and radius are already integers, so their milli fields are exact, and
   the thickness rounds to the nearest thousandth as `lroundf` does. The
   field order and the names are the oracle's; `TraceNormalizer` maps
   them to `thick_circle t cx cy r fill outline`.

3. **The engine forwards the primitive unchanged.** It adds the board
   origin to the centre and calls
   `viewCallbacks.drawCircle(thickness, cx+originX, cy+originY, radius,
   outlineColour, fillColour)`, which is the call that `android.c:215-219`
   makes through JNI. The radius and the thickness are not scaled by the
   origin or by the zoom.

4. **No ported game calls it.** Every frozen trace stays byte for byte
   the same, and the parity suite pins that. The `tracks` port remains a
   separate change with its own parity matrix.

`ThickCircleTraceTest` pins the field order, the thousandths, the
rounding, and the position inside the draw pair, and
`KotlinGameEngineThickCircleTest` pins the origin offset and the
callback argument order.

## Amendment: the thick polygon primitive

- **Date:** 2026-11-17

`solo` draws a polygon with `draw_thick_polygon` (`solo.c:5622` and
`5624`). The oracle already records the primitive,
`TraceNormalizer` already accepts `thick_poly`, and the Android
`drawing_api` already has the callback. This change adds the seam
operation, not the game.

1. **`DrawSink.thickPoly(points, thickness, fillColour,
   outlineColour)`** keeps the point list and the two colours of
   `DrawSink.poly`, so a game that has both primitives reads them the
   same way. The points are the flat `x0, y0, x1, y1, ...` array. The
   thickness follows the points in the seam; upstream
   `draw_thick_polygon(thickness, coords, npoints, fill, outline)` and
   the Android callback take it first, so each sink places it there.

2. **The trace sink copies `tr_draw_thick_polygon`**
   (`oracle.c:497-511`) field for field:
   `{"op":"thick_poly","t":round(thickness*1000),"pts":[..],"fill":..,
   "outline":..}`. The thickness is in thousandths, as `tc_milli`
   produces, and it rounds to the nearest thousandth as `lroundf` does.
   Upstream passes integer coordinates, so the seam points are integers
   and their trace fields are exact. The field order and the names are
   the oracle's, which differ from `poly`: `pts` comes before the two
   colours. `TraceNormalizer` maps them to
   `thick_poly t fill outline npoints coords`.

3. **The engine forwards the primitive unchanged.** It calls
   `viewCallbacks.drawPoly(thickness, points, originX, originY,
   outlineColour, fillColour)`, which is the call that
   `android.c:198-207` makes through JNI. The origin reaches the
   callback as a separate argument, as C passes `fe->ox` and `fe->oy`,
   and the outline precedes the fill. The thickness and the points are
   not scaled by the origin or by the zoom.

4. **No ported game calls it.** Every frozen trace stays byte for byte
   the same, and the parity suite pins that. The `solo` port remains a
   separate change with its own parity matrix.

`ThickPolyTraceTest` pins the field order, the thousandths, the
rounding, the normalizer line, and the position inside the draw pair,
and `KotlinGameEngineThickPolyTest` pins the origin and the callback
argument order.

## Amendment: the UI-aware compute size

- **Date:** 2026-11-17

Upstream `game_compute_size` takes a `const game_ui *ui` argument
(`puzzles.h`). The seam declared only `computeSize(params, tileSize)`,
which assumes that the board size is a function of the parameters and
the tile size alone. That is false for `pearl`:
`game_compute_size` uses `BORDER` (`pearl.c:2446-2447`), and `BORDER`
is `TILE_SIZE/8` for the Loopy appearance and `TILE_SIZE/2` for the
Masyu appearance (`pearl.c:2008`). The preference lives in the UI
(`ui->gui_style`), so a game that reads it cannot express its size
through the old signature. A size computed with the wrong appearance
would also be wrong for the size search, the centred origin, and the
input mapping, because all of them read the same geometry.

1. **`Game.computeSize(params, ui, tileSize)`** is the seam operation.
   The two-argument form stays abstract, because every ported game
   already implements it, and the new three-argument form is a default
   that calls it. A game whose geometry reads the UI overrides the
   three-argument form; every other game declares nothing and keeps its
   arithmetic and its tests.

2. **The midend passes its current UI.** `Midend.size`,
   `Midend.largestTileFor`, and `Midend.geometryFor` call the
   three-argument form with `Midend.ui`. Their public signatures do not
   change, so the size search and the geometry keep one source of UI
   state. The C midend reads `me->ui` in the same place, because
   `midend_size` calls `game_compute_size` with it.

3. **`BoardGeometry.preferred` takes the UI.** The value is part of the
   geometry that drawing, input, the cursor, and size negotiation share.
   There is no two-argument overload: `Game.newUi` needs a state, and the
   preferred geometry is built before the caller has one, so a default
   UI would be a guess that can differ from the loaded UI. The app passes
   `midend.ui` both for the initial geometry field and for
   `suggestDensity`, and it does not create a second UI.

The UI stays frontend state. It does not enter `Params`, the seed,
`DESC`, `PRIVDESC`, `AUXINFO`, `UI`, or `.sgtp`, and a save created
under one appearance loads unchanged under another. The rule only says
which UI the size calculation reads.

`UiComputeSizeTest` pins a test-only game whose three-argument
`computeSize` returns a different shape per UI flag, and checks
`Midend.size`, `largestTileFor`, `geometryFor`, and
`BoardGeometry.preferred` against it. It also pins that a game which
does not override the three-argument form answers exactly as before, so
the existing game size and geometry tests stay unchanged.

## Amendment: the params-aware text gate and the thick-line floor

- **Date:** 2026-11-17

Two operations that `loopy` needs were missing from the seam. Neither
changes a game rule, a save record, or a frozen trace.

1. **`Game.canFormatAsTextNow(params)`, default
   `canFormatAsText`.** It mirrors `can_format_as_text_now`, the
   params-aware half of `midend_can_format_as_text_now`
   (`midend.c:2079-2085`). `Midend.textFormat` calls the game only when
   both `canFormatAsText` and the params gate are true, because
   `midend_text_format` requires
   `can_format_as_text_ever && can_format_as_text_now(me->params)`
   (`midend.c:2087-2094`). `loopy` is the first upstream game whose
   answer depends on the parameters: `game_can_format_as_text_now`
   rejects a grid whose type is not the square type
   (`loopy.c:1144-1149`). A rejected parameter set yields the empty
   string, so the app and the tests need no null check, and a caller
   that must tell "no text form" from "an empty board" asks the gate
   with the current parameters. Every ported game keeps its behavior,
   because the default answers the static flag, which is what the
   midend checked before. `TextFormatGateTest` pins a test-only game
   whose gate rejects `5x5` and accepts `4x4`, and asserts that the
   formatter is not called for the rejected set.

2. **`DrawSink.clampThickness(thickness)` is the `draw_thick_line`
   floor.** C replaces a thickness below `1.0F` with `1.0F` before it
   calls the drawing API (`drawing.c:117-118`). Both sinks apply the
   same helper: `KotlinTraceSink` records the clamped value with
   `tc_milli`, so the trace matches the oracle, and `KotlinGameEngine`
   passes the clamped value to `ViewCallbacks.drawLine`, so the view
   draws the same width. The seam signature does not change. A game
   passes the thickness it computes, as a C game does, and the floor
   stays in the delivery path where upstream puts it. A value of
   `1.0` or more is unchanged, so every current caller, which passes at
   least `1.0`, keeps its trace byte for byte. `ThickLineTraceTest`
   pins `0.0625` to `1000` and the rounding of `3.125` to `3125`;
   `KotlinGameEngineThickLineTest` pins the forwarded `1.0`.

## Amendment: the configuration and preference seam (S8)

- **Date:** 2026-11-17

The earlier amendments said the seam had no preference store and that
`one_key_shortcuts` was always on. S8 adds the settings and preference
operations that the app adapter needs. The change is the seam for the
39 games that are not enabled yet; it does not enable a second game.

1. **`ConfigItem` is the C `config_item`.** The three types are
   `Text`, `Bool`, and `Choices`. `kw` defaults to `name`, because
   `android.c` passes the display name as the keyword for a settings
   item; only a preference item carries a real keyword. The `choices`
   and `keywords` strings are kept verbatim, including the leading
   separator, so the Android builders split the same bytes as C.

2. **`Game.configure(params)` returns the settings schema or null.**
   Null is the "not declared yet" marker and the app must not enable
   the game. `Game.customParams` converts an edited list back to
   parameters; a game that declares a schema implements it, and the
   default throws so a missing implementation cannot hide behind a
   dialog crash.

3. **`Game.prefs` is a static schema.** `midend_get_prefs` with no live
   UI calls `new_ui(NULL)` and reads only the compile-time fields
   (`midend.c:2986-3003`), so a static list equals
   `get_prefs(new_ui(NULL))` and the preferences page needs no midend
   and no generation. `Game.setPrefs(ui, prefs)` applies typed values to
   a live UI; a game reads `Prefs.bool` and `Prefs.choice` and never
   parses text.

4. **`Prefs` copies the C text rules.** The encoding is one
   `kw=value` line in schema order, `true`/`false` for a boolean, a
   `choicekws` keyword for a choice, and the newline space stuffing.
   The loader skips comments and blank lines, accepts a missing final
   newline, ignores an unknown keyword, and returns the C error string
   for a bad value. No upstream game has a string preference; the
   parser and the encoder still handle one so they match C.

5. **`Midend` owns the preference store.** It keeps
   `one-key-shortcuts` first, then the game values, applies them after
   `new_ui`, and writes them back with `serialisePrefs`. The
   `one-key-shortcuts` value gates only the bare letters `n`, `u`, `r`,
   and `q`; the `*`, `#`, control-character, and `UI_*` aliases stay
   active, as `midend.c:1006-1034` does.

6. **`Game.solveGame` is the solve error channel.** `Midend.solveAttempt`
   returns the three `midend_solve` messages and `solve()` is
   `solveAttempt() == null`, so every existing caller keeps its result.
   A game that has a solver message overrides `solveGame`; the default
   wraps `solve` and has no message.

One order differs from C by design. C applies the stored preferences
to the fresh UI before `decode_ui` (`midend.c:2669-2673`); the seam
applies the factory `prefs` after `decode_ui`, so the preferences land
on the final UI. No upstream game writes a preference field in
`decode_ui`: `guess` restores pegs and holds, and `net` restores the
cursor. The two orders therefore produce the same UI for every game
that can be enabled today. A future game whose `decode_ui` writes a
preference field must revisit this rule.
