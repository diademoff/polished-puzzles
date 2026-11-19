# ADR 0013: A spoken board seam for a screen reader

- **Status:** accepted
- **Date:** 2026-11-19
- **Status note (2026-11-19):** Decision 6 describes the state before
  the eighth enablement wave. `solo` and `cube` are `kotlin` now:
  `solo` gets the full unit model, and `cube` speaks each square with
  the roll its own rectangle makes. The C fallback remains for a
  backend with no Kotlin port, and no shipped backend takes it. The
  rest of the ADR is unchanged.

## Context

D-01 is the last P0 in the accessibility audit: the game board has no
screen-reader semantics. `GameView` is an opaque drawing surface, so
TalkBack reads the shell around the board and nothing of the game. A
blind player cannot find the board, read a cell, or make a move.

The app draws two kinds of board. A Kotlin game draws from its own state
through `DrawSink`; a C game (`solo`, `cube`) draws inside the vendored
engine. Only the Kotlin side can answer "what is in this cell" without
guessing.

The full design, including the utterance grammar, the coverage plan,
the effort, and the owner decisions, is `work/board-a11y-spec.md`. This
ADR records the decisions that outlive that working file.

## Decision

1. **One new game operation, not a new frontend.** `Game.spokenBoard`
   returns `SpokenBoard`: the grid shape, a board summary, and a list of
   `SpokenUnit`. A unit carries its 1-based position (0 when it is not in
   a grid), its rectangle in game coordinates, content-only text, and the
   pointer buttons it offers. The default is an empty board, so the seam
   lands before the games and an unported game keeps the shell's single
   board stop. `gridSpeech` builds the plain tile case and
   `SpokenBoard.capped` applies the cap, so a cell game is a text
   function and its extra units.

2. **The shell exposes Compose semantics, not a virtual view tree.** The
   board node carries `collectionInfo`, `contentDescription`, and the four
   actions (Undo, Redo, Restart, Solve). Each unit is a node placed at
   the unit rectangle through the same matrix that draws the board, so a
   spoken move, a finger tap, and the focus rectangle share one geometry.
   A double tap is the left button press and release at the unit centre
   through `GamePlay.sendKey`; a unit custom action sends its own button.
   An `AccessibilityNodeProvider` was rejected: the board is already
   Compose, one node source is simpler, and the Compose test rule can
   read it.

3. **The cap is 400 grid units.** At or below it there is one node per
   unit. Above it the game returns the cursor unit only and the player
   uses the arrow keys. The alternative is a row-level tree or virtual
   children on a View, which is more machinery than any current board
   needs. `shortcut: above 400 units only the cursor speaks, add row
   nodes when a real user with a large board asks.`

4. **Positions come from the node tree; the label is content only.**
   TalkBack reads `collectionItemInfo` itself, so a label that also
   contained `Row 3, column 4` would say it twice. A game names its
   content, and the shell speaks the coordinate.

5. **Announcements are a diff, not a state dump.** One hidden polite live
   region per game screen. One or two changed units are read in board
   order; three or more are summarised from the cursor; no change is
   silent; a completion is the completed dialog. At most one
   announcement per 300 ms, last text wins, so key auto-repeat speaks
   about three times a second. The status line stops being a live region
   while the board has units, so one event is one utterance.

6. **The C engine falls back.** `GameEngine.spokenBoard` is null there,
   so the shell exposes one board node labelled with the game name and
   the status text plus the four actions. `solo` reaches the full model
   when the app switches to its Kotlin engine; `cube` has no 2D unit
   model, so the fallback is final. The fallback and the seam are an
   accessibility deviation from upstream, recorded as D-035.

7. **No change to rules, RNG, seed, `DESC`, `AUXINFO`, `UI`, `TIME`, or
   `.sgtp`.** The seam reads state and the UI; it writes neither. The
   drawing, the palette, and the parity traces are untouched.

## Alternatives rejected

- **Decode the `DrawSink`.** Text operations give glyphs without meaning,
  the cursor is a one-pixel outline, and half the games redraw only
  their changed area, so the sink is a delta. It re-derives each game's
  layout in the wrong layer.
- **Parse `text_format`.** ASCII art with no cursor, selection, colour,
  or error flags; several games return an empty string; it is a golden
  parity output and stays a debug dump.
- **One generic cell grid from `computeSize`.** Only a plain tile grid
  fits; Black Box has a laser ring, Guess a palette, Tents off-grid
  clues, Loopy edges, and Untangle no cells.
- **Labels only, no nodes.** Explore-by-touch needs real nodes.
- **Self-voicing speech.** The platform screen reader already speaks;
  a second voice would fight it.

## Consequences

Positive:

- one seam gives every Kotlin game the same node tree, action shape, and
  announcement rule, so waves 2 to 4 are mechanical;
- the compiler holds the default, so no game changes behavior until it
  overrides the operation;
- the spoken text comes from state, so it cannot drift from a palette
  that flips with night mode;
- the pure halves (cap fallback, announcement diff) are JVM tests.

Negative:

- `spokenBoard` runs on every redraw, so a game pays a per-cell loop per
  frame; the cap bounds it, and the largest pilot is 22x22;
- a game has to describe itself twice, once for pixels and once for
  words; the tests pin the words;
- TalkBack versions differ in how they read a collection;
- the board's units are shorter than 48dp on a large board, and the
  platform expands the accessibility bounds to the touch-target minimum,
  so the focus highlight can sit a few pixels off the cell;
- wave 2 to 5 still owe the full sweep, the large-board decision, and the
  listening pass (D-03).

## References

- `work/board-a11y-spec.md` (owner-approved design; untracked);
- `docs/DESIGN.md` section 11;
- `docs/DEVIATIONS.md` D-035;
- `docs/adr/0007-kotlin-game-seam.md`, `docs/adr/0010-drawstate-and-blitters.md`.
