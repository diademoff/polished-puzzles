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
   it; `Midend` passes it to `interpretMove`, `setCursorVisible`, and
   `cursorLocation`. A load creates a fresh UI, as upstream does. The
   UI is never serialised.
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
   matrix, but its configuration dialog and cursor drawing are not
   wired into the shell, and the two-release wait is not over.

## Consequences

Positive:

- slant passes the full matrix without changing fifteen's save or
  input path;
- the seam now fits cursor games and games with an auxiliary answer;
- the app can validate a Kotlin game in dual-run without a feature flag
  in the user-facing settings.

Negative:

- `Game.redraw` still has no UI parameter, so the Kotlin renderer does
  not draw slant's cursor cell or the optional grounded fade. The app
  uses C for slant, so no user sees the gap. Lifting this needs a
  redraw signature change and a new trace axis.
- the midend now has two pieces of state that are not in the save file
  (the UI and the auxiliary text); tests must keep them out of
  serialisation.

## Alternatives

1. **Store the cursor in `Game.State`.** Rejected: it would leak into
   save text, history, and equality, and undo would move the cursor.
2. **Skip `AUXINFO` and always solve.** Rejected: the save must be
   byte-identical, and an easy puzzle can have more than one valid
   answer, so the recomputed solution is not always the stored one.
3. **Enable slant globally with `KotlinGames.ENABLED`.** Rejected: that
   switches fifteen and slant together and bypasses the two-release
   rule.
