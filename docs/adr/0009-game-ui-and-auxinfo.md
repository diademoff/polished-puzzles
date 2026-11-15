# ADR 0009: game UI state and auxiliary answers

- **Status:** accepted
- **Date:** 2026-11-14

## Context

The fifteen port needed no frontend state beyond the board, and its
save file has no auxiliary answer. Slant is the first game that needs
both:

- a cursor position and two preferences, which live in upstream
  `game_ui` and are not part of the save file;
- an `AUXINFO` record, which stores the answer in an obfuscated form so
  a saved game does not spoil the puzzle.

The fifteen `Game` seam had no place for either. `interpretMove` saw
only the state, and `newDesc` returned only the description.

## Decision

1. **The midend owns one `Game.Ui` object.** `Game.newUi(state)` creates
   it; `Midend` passes it to `interpretMove`, `setCursorVisible`,
   `cursorLocation`, and `redraw`. A load creates a fresh UI, then a
   game that provides `decodeUi` restores its own UI from the `UI`
   record. The UI never enters the state, the history, equality, or the
   `.sgtp` state payload.
2. **A cursor rectangle is a game-coordinate value.** `Game.CursorRect`
   carries left, top, width, and height. The shell adds the board
   origin; the game does not know about the viewport.
3. **A description may carry auxiliary data.** `Game.newDesc` returns
   `Description(text, auxInfo)`. `Midend` stores the auxiliary text and
   passes it to `Game.solve`, so a solve uses the generated answer when
   one exists and computes one otherwise.
4. **`AUXINFO` is written exactly as upstream writes it.** `AuxInfo`
   ports `obfuscate_bitmap`, `bin2hex`, and `hex2bin`. The save text is
   therefore byte-identical, and `SlantSaveTest` pins both directions.
5. **App enablement is per game, not global.** `KotlinGames.gameFor`
   can know a complete Kotlin game while `KotlinGames.handles` keeps the
   app on C. `slant` is in this state: the engine passes the parity
   matrix, but its configuration dialog is not wired into the shell,
   and the two-release wait is not over.
6. **`Game.redraw` receives the UI.** `Midend` passes its `Game.Ui` to
   `redraw`, so a game can draw frontend state that is not in
   `Game.State`, such as slant's cursor cell and its optional grounded
   fade. A game without such state ignores the parameter. The UI stays
   out of the state and the history.
7. **A game may write a `UI` record.** `Game.encodeUi(ui)` returns the
   record value, or null when the game has none, as upstream
   `encode_ui` does. `Midend.serialise` writes `UI` after `AUXINFO` and
   before `NSTATES`, and only when the value is non-null, so the record
   order matches `midend_serialise`. `Game.decodeUi(state, text)`
   restores the UI on load; `state` is the current state, as C passes
   `states[statepos-1].state` to `decode_ui`. A save without `UI`, and
   a game without `decodeUi`, use `newUi`. The value must be printable
   ASCII: `SaveRecord` rejects anything else, so a bad value fails the
   save instead of writing a corrupt file. The UI record does not
   change `id()`, `status()`, or `textFormat()`, and no current game
   writes it, so existing saves are unchanged.

## Consequences

Positive:

- slant passes the full matrix without changing fifteen's save or
  input path;
- the seam now fits cursor games and games with an auxiliary answer;
- the Kotlin renderer can draw slant's cursor and grounded fade, and
  `SlantTraceTest` pins both against the oracle;
- the app can validate a Kotlin game in dual-run without a feature flag
  in the user-facing settings;
- a game that upstream saves a UI, such as guess or blackbox, can
  round-trip its save when it is ported.

Negative:

- `Game.redraw` has no flash or animation time, so the Kotlin renderer
  does not draw the completion flash or the solve animation. Slant is
  still on C in the app, and no strict trace enables either.
- the app still uses C for slant, so the new cursor and fade drawing is
  checked by parity traces but is not yet visible through the Android
  shell;
- the UI is frontend state that a ported game may write to the save
  file, so the tests must keep it out of `Game.State`, the history, and
  the game ID. `UiSaveTest` pins both the record and that separation.

## Alternatives

1. **Store the cursor in `Game.State`.** Rejected: it would leak into
   save text, history, and equality, and undo would move the cursor.
2. **Skip `AUXINFO` and always solve.** Rejected: the save must be
   byte-identical, and an easy puzzle can have more than one valid
   answer, so the recomputed solution is not always the stored one.
3. **Enable slant globally with `KotlinGames.ENABLED`.** Rejected: that
   switches fifteen and slant together and bypasses the two-release
   rule.
