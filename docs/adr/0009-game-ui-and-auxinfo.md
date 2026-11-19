# ADR 0009: game UI state and auxiliary answers

- **Status:** accepted
- **Date:** 2026-11-14
- **Superseded in part by:** `docs/adr/0011-enable-games-in-waves.md`.
  The sentences that say `slant` is still on C describe the state at
  the decision date. `slant` is `kotlin` since the fourth enablement
  wave on 2026-11-18, so its cursor and fade now reach the Android
  shell. The seam and the `AUXINFO` rule are unchanged.

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

## Amendment: the UI decode receives the fresh UI

- **Date:** 2026-11-16

C creates the UI from the first state, then overlays the `UI` record
on that same object:

```
data.ui = me->ourgame->new_ui(data.states[0].state);
if (data.uistr && me->ourgame->decode_ui)
    me->ourgame->decode_ui(data.ui, data.uistr,
                           data.states[data.statepos-1].state);
```

(`midend.c:2669-2673`). The earlier seam folded both steps into
`decodeUi(state, text)`, so a game could not read what `newUi` created
from the platform seed. Decision 1 is therefore amended:

1. **The midend calls `Game.newUi(state, platformSeed)` first.** The
   base UI comes from the first state, as C does, with the frontend
   seed from S7 (ADR 0007). A save without a `UI` record keeps that
   base UI and does not call `decodeUi`.

2. **`Game.decodeUi(state, text, base)` receives the base.** [state] is
   the current state, `states[statepos-1].state`, and [base] is the UI
   that `newUi` returned. The game may keep any field that the record
   does not store, such as the frontend random state of `net`. The
   two-argument `decodeUi` remains, and the three-argument default
   calls it, so every existing game and every existing save is
   unchanged.

`PlatformSeedTest` pins the order: the base is created with the
supplied seed, the record overrides one field, and the other field
survives the load. The UI still does not enter the state, the history,
`id()`, or the `.sgtp` state payload, and the `UI` record keeps its
place after `AUXINFO` and before `NSTATES`.

A test double that implements `Game` by delegation forwards the new
default methods to its delegate, so it must override the overloads it
uses. `UiSaveTest.UiFifteen` does; a real game overrides `newUi` and
`decodeUi` directly and needs no extra method.

## Amendment: the `TIME` record follows `UI`

- **Date:** 2026-11-16

Decision 7 puts `UI` after `AUXINFO`. The timed-game clock of ADR 0007
(S5) adds a `TIME` record that `midend_serialise` writes between `UI`
and `NSTATES`. The save order is therefore `AUXINFO`, `UI`, `TIME`,
`NSTATES`. A game without a UI record writes no `UI` and still writes
`TIME`, and an untimed game writes neither record. The position is
fixed and not an ordering preference, because the record stream is
compared byte for byte against C. `Midend.fromSave` reads `TIME` into
`Midend.elapsed`; a save without the record loads at 0.

## Amendment: the nibble mask of a bitmap description (S6)

- **Date:** 2026-11-16

`mines` stores its layout as a bitmap of exactly the board area, not as
a whole number of bytes. `describe_layout` obfuscates the bitmap and
hex-encodes it at nibble granularity (`mines.c:1976-2023`); `new_game`
reads the same form. `AuxInfo` could only encode whole bytes, so the
seam had no way to produce or read such a description.

1. **`AuxInfo.encodeMask(bytes, bits)`** obfuscates a bitmap of exactly
   [bits] bits and writes `(bits + 3) / 4` hex digits, taking the high
   nibble first. **`AuxInfo.decodeMask(text, bits)`** allocates
   `(bits + 7) / 8` bytes, reads the same nibbles, and de-obfuscates
   them. A non-hex character is a zero nibble, as `hex2bin` reads it.
   The bytes are obfuscated in place, as C does, so the caller owns the
   array. When [bits] is a multiple of eight the text is identical to
   `encodeBytes`, and the two decoders agree.

2. **`obfuscate` clears the pad bits after each step, as C does.**
   `obfuscate_bitmap` masks the pad bits of the last byte after each of
   its two steps (`misc.c:137-139`), so the second step reads the
   cleared byte as its seed. The port cleared them once after both
   steps. That is the same result for a whole number of bytes, which is
   why no `AUXINFO` value changed, and it is wrong for a partial last
   byte: a 9-bit bitmap decoded to the wrong first byte. The fix
   clears the pad bits in both places, so a mines layout round-trips.

3. **The description form is a game rule, not a save rule.** The seam
   supplies the encoding only; the `m`,`u`, and `x,y` parts of the
   text, the layout generator, and the bit order belong to the `mines`
   port. `AuxInfoTest` pins the round trip at 1, 7, 8, 9, 63, 64, 65,
   and 81 bits, the byte-for-byte agreement at 8, 16, and 64 bits, and
   the zero value of a non-hex nibble.

The save record that carries a description is `DESC`; the private
counterpart is `PRIVDESC`, which ADR 0007 places directly after it.
This amendment changes no `AUXINFO` value for an existing game.
