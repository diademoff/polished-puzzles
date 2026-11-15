# ADR 0010: drawstate lifecycle and blitter operations

- **Status:** accepted
- **Date:** 2026-11-15

## Context

The ported games draw the whole board on every redraw. `fifteen`,
`slant`, `samegame`, `flood`, `sixteen`, `flip`, and `twiddle` keep no
cache between draws, so the seam had no `game_drawstate` and no blitter
operations even though `docs/adr/0003-strict-draw-traces.md` already
treats blitter operations as part of the strict trace.

`pegs` is the first game that caches its tile backgrounds. Upstream
`pegs.c` allocates the drag background in `game_set_size` with
`blitter_new`, reuses it with `blitter_save` and `blitter_load` in
`game_redraw`, and releases it in `game_free_drawstate` with
`blitter_free`. A port without the seam cannot keep the cached drawing
behavior or the trace.

The oracle already records the four operations, `TraceNormalizer`
already accepts them, and `ViewCallbacks` already has
`blitterAlloc`, `blitterFree`, `blitterSave`, and `blitterLoad`. Only
the Kotlin seam and the midend lifecycle were missing.

## Decision

1. **`Game.DrawState` is the cache, and `NoDrawState` is the empty
   one.** `Game.newDrawState(state, tileSize, sink)` mirrors
   `game_new_drawstate`; `Game.freeDrawState(drawState, sink)` mirrors
   `game_free_drawstate`. Both have defaults that return
   `NoDrawState` and do nothing, so a game without a cache declares
   nothing. `Game.redraw` takes the drawstate after the UI and before
   the sink. Every current implementation accepts the parameter and
   ignores it.

2. **`DrawSink` carries the four blitter operations.**
   `blitterNew(width, height)` returns the id for the buffer,
   `blitterFree(id)` releases it, and `blitterSave(id, x, y)` and
   `blitterLoad(id, x, y)` capture and replay the area at a game
   coordinate. The game owns the id; the sink only maps it to a buffer.
   The app adds the board origin to `x` and `y` exactly as the other
   drawing calls do, and passes the size unchanged to `blitterAlloc`.

3. **The midend owns one drawstate at a time.** `Midend.drawState` is
   the counterpart of `me->drawstate`. It is created lazily by
   `Midend.ensureDrawState(tileSize, sink)` immediately before the
   first `start_draw` of that drawstate. A new C drawstate is also
   created before `midend_redraw`, so a game's `blitter_new` comes
   before `start_draw` in the trace. In a new-game trace the order is
   `changed_state`, `blitter_new`, `start_draw`: `midend_new_game`
   calls `changed_state` when the tile size is still 0, and
   `midend_size` allocates the first blitter afterwards. Every caching
   game allocates its blitters in `game_set_size`, not in
   `game_new_drawstate`. `recordNewGameTrace` follows that order.

4. **A replacement frees the old drawstate first, through its own
   sink.** `ensureDrawState` frees the old cache with the sink that
   created it, then creates the new one with the current sink. A
   replacement therefore never writes `blitter_free` into a trace that
   contains the new drawstate. This matches the oracle, which suppresses
   the old drawstate's frees and starts the trace with `blitter_new`.

5. **A tile size change recreates the drawstate.** A C drawstate cannot
   be sized twice, so `midend_size` frees it and creates a new one. The
   Kotlin midend does the same when the requested tile size differs from
   the size the cache was created for.

6. **A state change marks the cache dirty.** `restart`, a real move,
   `undo`, `redo`, and `solve` mark it dirty; the next draw frees and
   recreates it. Upstream recreates the drawstate in `midend_force_redraw`
   and after a load rather than after every move. The trace consequence
   is the same, because every oracle trace of a changed state comes from
   a fresh midend or from `midend_force_redraw`, and the oracle fixture
   for a post-input trace is therefore a fresh drawstate. Marking the
   cache dirty is conservative for a game whose cache depends on the
   state, and it never draws from a cache that belongs to another state.

7. **The blitter id is opaque outside the game.** The app's
   `ViewCallbacks.blitterAlloc` chooses its own id, the trace sink
   assigns ids 1, 2, ... in call order, and the oracle does the same per
   trace. The game stores the returned integer and never computes one.
   A positive id, one allocation per id, and a free before reuse are the
   normalizer's contract. The Kotlin trace sink therefore starts each
   trace at 1, as `trace.blitters = 0` does in the oracle.

8. **There is no close.** The engine has no `close` method, so
   `freeDrawState` is not called when the midend becomes unreachable.
   The Android shell owns process lifetime, and the view layer recycles
   its blitter bitmaps when the game view goes away. A future close
   method must call `freeDrawState` with the sink that created the
   cache.

9. **No deviation.** No game rule, RNG draw, seed, `DESC`, `.sgtp`,
   `AUXINFO`, palette, or frozen golden changes. The games that use
   `NoDrawState` emit exactly the same trace as before, and the parity
   suite pins that byte for byte.

## Consequences

Positive:

- a caching game can be ported without changing its drawing behavior;
- the trace order for `blitter_new`, `start_draw`, and the drawstate
  replacement is defined before the first caching game;
- the app and the trace sink share one blitter contract;
- the seven ported games are unaffected, and their goldens need no
  regeneration.

Negative:

- a post-input trace must start from a midend whose drawstate is due
  for replacement, or from a fresh midend. A trace sink records only the
  operations of its own drawstate, and the normalizer rejects a
  `blitter_save` for an id that the trace did not allocate;
- the cache is recreated after every move, so a caching game spends the
  allocation once per move that the C code would spend only on a forced
  redraw. This is a performance cost, not a parity difference. It can be
  reduced later by keeping the cache when the game declares that it
  does not depend on the state.

## Alternatives

1. **Give `DrawSink` default no-op blitter methods.** Rejected: a game
   that calls them would silently lose its cache, which is the
   unverified-stub problem that `docs/adr/0007-kotlin-game-seam.md`
   rejects.
2. **Let the game own the drawstate and create it in `redraw`.**
   Rejected: `blitter_new` would then come after `start_draw`, which
   does not match the oracle and changes the strict trace.
3. **Keep one drawstate forever.** Rejected: a C drawstate cannot be
   sized twice, so `midend_size` must recreate it; a size change without
   recreation would draw with stale geometry.
4. **Recreate only on `restart` and a load.** Rejected for now: the
   state-change invalidation is simple, safe for a state-dependent
   cache, and produces the same trace shape as `midend_force_redraw`.
