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

## Amendment: keyboard modifiers reach the game

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

2. **The midend splits the key.** `processKey` recognises `UI_UNDO`,
   `UI_REDO`, and `UI_SOLVE` on the stripped button, so a modified UI
   key still means the UI action and never reaches the game as a key.
   `game.interpretMove` then receives the raw button, modifiers
   included. The history, the `MoveResult` handling, and
   `changedState` are unchanged.

3. **Each game strips what it does not use.** Our `processKey` does not
   reproduce the C filter yet, so every game must drop what it does not
   use. `Fifteen`, `Slant`, `SameGame`, and `Flood` call
   `Buttons.stripModifiers` at the entry of `interpretMove`. Their
   behaviour for unmodified input does not change, and a modified
   button behaves like the plain button. The seam method documents this
   contract, and `KeyModifierTest` pins it.

4. **The C canonicalisation is not ported yet.**
   `midend_process_key` also canonicalises input before the game:
   `CTRL`+ASCII becomes a control character, `CTRL+SHFT+Z` becomes
   `UI_REDO`, `CTRL` on a key that is not a cursor move is rejected,
   and `MOD_NUM_KEYPAD` is kept only on a digit. None of that is in
   `processKey` today, and the app does not send `CTRL` on a game key.
   The parity tests use `MOD_SHFT` and `MOD_NUM_KEYPAD` on clicks and
   cursor keys, which the C midend filters before the backend, so both
   sides reach the game with the same button. When the shell needs the
   rest, it belongs in `processKey` before the split.
