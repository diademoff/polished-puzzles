# ADR 0007: Kotlin game seam and the semantic midend

- **Status:** accepted
- **Date:** 2026-11-14

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
   becomes `UI_REDO` in the midend, so it redoes. `one_key_shortcuts` is
   always on, the C default; the seam has no preference store.

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
