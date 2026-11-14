# ADR 0003: strict draw traces

- **Status:** accepted
- **Date:** 2026-11-14

## Context

`PARITY.md` requires the renderer to reproduce the C drawing API
exactly. Pixels are not a stable contract: fonts, anti-aliasing, and
Skia versions differ between devices. The C code already produces a
primitive stream through `drawing_api`; that stream is the contract.

The oracle had no way to record it. `tools/oracle` replaced every
drawing callback with a no-op, so a strict trace could not be checked
at all.

## Decision

1. **The oracle records the primitive stream.** A `trace` request
   creates a fresh midend from a serialised state, calls
   `midend_redraw` once, and returns every callback in order. The
   request is stateless like the rest of the protocol.
2. **The trace is JSON with `traceSchema: 1`.** The header carries the
   game, the game id, the parameters, the upstream commit, the board
   size, and the palette. The body is a list of operations in call
   order. The schema number changes only with the normalizer and the
   goldens.
3. **Numbers are integers in milli-units.** A coordinate or a length is
   `round(value * 1000)`. This is the `1e-3` tolerance from
   `PARITY.md` expressed as an exact value: two producers agree when
   their milli-units agree. There is no float formatting and no locale
   dependence.
4. **Colours are palette indices, plus the parity palette.** The engine
   passes indices such as `COL_BACKGROUND`; the game maps them to RGB
   through `frontend_default_colour`. The oracle fixes that colour at
   0.8, so the recorded table is the parity palette. Comparing the index
   alone catches a game that asks for the wrong role; comparing the
   table catches a changed parity palette. A UI theme may map the same
   index to a different RGB, as `PARITY.md` allows; the theme is not
   part of the strict trace. `-1` means "no colour" for an empty fill or
   outline.
5. **Order is the trace.** `clip`/`unclip`, `start_draw`/`end_draw`,
   blitter operations, and `draw_update` are recorded and compared in
   order. `PARITY.md` already treats them as strict.
6. **Text is compared as text and anchor.** The operation records the
   string, the anchor, the font class, the size, and the alignment.
   Glyph metrics and font files are not part of the trace.
7. **The trace starts from a fresh midend.** The first redraw of a new
   midend clears the window with colour 0. That clear is part of the
   trace, so a renderer must do it too. Traces are therefore tied to a
   state, not to a device or to a repaint history. The `game_ui` is
   fresh as well: the cursor is hidden at its default position. Cursor
   position is frontend state and is not serialised, so it is not part
   of the trace.
8. **A Kotlin normalizer owns the canonical form.** It validates the
   schema, rejects unknown operations, and writes one operation per
   line. Goldens are stored in that form, so a change is reviewable in
   a diff. The future Compose renderer will produce operations and pass
   them through the same normalizer.
9. **Goldens are committed canonical text, not screenshots.** Traces
   are small, deterministic, and describe geometry; they are safe to
   commit. Screenshot goldens stay local.
10. **The first traced game is `fifteen`.** Every preset is frozen with
    the shared seed. Other games follow one at a time as their
    renderers arrive.

## Consequences

Positive:

- the renderer has an executable specification for every primitive;
- a geometry regression is a readable one-line diff;
- the same normalizer serves the oracle and the Compose renderer;
- no device and no font is needed to check the contract.

Negative:

- the trace says nothing about anti-aliasing, font rasterization, or
  theme colours, by design;
- a game that draws differently on a second redraw needs a separate
  trace scenario;
- the oracle and the normalizer must change together with the schema.

## Alternatives

1. **Pixel comparison.** Rejected in ADR 0001 and again here: unstable
   across devices.
2. **Record RGBA values directly.** Rejected: the renderer would be
   checked against one theme; palette indices keep the role contract.
3. **Floating-point numbers in JSON.** Rejected: formatting, rounding,
   and locale make the golden unstable; milli-units are exact.
4. **A stateful oracle session.** Not needed for traces, because one
   trace describes one state. A key sequence uses the `keys`
   operation, which replays the sequence inside one request.
