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
   first `start_draw` of that drawstate. It is built from the first
   state, as `midend_size` and `midend_force_redraw` build it from
   `states[0].state` in C, and as `midend_new_game` does. A new C
   drawstate is also created before `midend_redraw` in
   `midend_force_redraw`, so a game's `blitter_new` comes before
   `start_draw` in the trace. In a new-game trace the order is
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

6. **A forced redraw recreates the drawstate at an unchanged tile
   size.** `midend_size` frees and recreates the drawstate on every
   call that has one, even when the tile size it computes is the same
   as before. The shell calls the public `Midend.forceRedraw()` from
   `KotlinGameEngine.resizeEvent`, and a pinch zoom reaches that method
   through `gameViewResized`. The view allocates its blitter bitmaps
   from the zoom factor, so a cache that survived a zoom change would
   keep the old bitmap size. The tile-size check stays for the ordinary
   call; a forced redraw skips it.

7. **A different sink recreates the drawstate.** A live midend that
   gets a second `KotlinTraceSink` still holds a cache whose blitters
   belong to the first sink. A `blitter_save` for that id would name
   an id the new trace never allocated, and the normalizer rejects it.
   `ensureDrawState` therefore compares the sink by identity and
   recreates the cache when it changed. The free reaches the old sink,
   so the new trace starts with `blitter_new`, exactly like the
   oracle's `seq` path. In the app the sink is one instance for the
   life of the engine, so the app never recreates on a sink change.

8. **Ordinary moves and a restart keep the cache.** A move, `undo`,
   `redo`, `solve`, and `restart` call `midend_redraw` in C and do not
   touch `me->drawstate`, so the Kotlin midend keeps the cache too. The
   next draw reuses the same instance and its blitters, and the trace
   contains the redraw operations and no `blitter_free` or
   `blitter_new` from the midend. A restart is not a reason to
   recreate: `midend_restart_game` keeps the drawstate and a caching
   game refreshes the parts that the new state invalidates in
   `game_redraw`. A new game and a load recreate the cache in C
   (`midend_new_game`; `midend_load` builds it from the loaded current
   state), and a tile-size change recreates it in `midend_size`.

   A trace that reuses a live midend with a new sink is the exception:
   the recreation in item 7 writes `blitter_new` before `start_draw`,
   which is what the oracle records for its `seq` traces.

9. **The blitter id is opaque outside the game.** The app's
   `ViewCallbacks.blitterAlloc` chooses its own id, the trace sink
   assigns ids 1, 2, ... in call order, and the oracle does the same per
   trace. The game stores the returned integer and never computes one.
   A positive id, one allocation per id, and a free before reuse are the
   normalizer's contract. The Kotlin trace sink therefore starts each
   trace at 1, as `trace.blitters = 0` does in the oracle.

10. **The shell frees the last cache on close.** `Midend.freeDrawState()`
    frees the cache through the sink that created it, as
    `midend_free_game` frees `me->drawstate` from `midend_free`, and
    clears the reference. A second call frees nothing, and a midend
    that never drew has no cache to free. `KotlinGameEngine.onDestroy`
    calls it, as `GameEngineImpl.onDestroy` frees its midend, so the
    view callbacks recycle their blitter bitmaps before the game view
    goes away.

11. **The midend clears the window only on the first draw of a
    drawstate.** C keeps `me->first_draw`: it is set when a drawstate is
    created or replaced (`midend_new`, `midend_size`,
    `midend_force_redraw`, `midend_new_game`, `midend_load`) and cleared
    by the first `midend_redraw` that uses it. That redraw covers the
    window with colour 0 before the game draws and calls a whole-window
    `draw_update` after it, because the front end does not guarantee the
    initial contents of the window. A move, undo, redo, solve, or
    restart keeps the drawstate, so its redraw emits neither operation
    and the trace contains only the game's own operations.
    `Midend.takeFirstDraw` exposes the read-and-clear for
    `KotlinGameEngine.redraw`, which draws the game itself; the app
    clears on a resize and a forced redraw, not after every move.

12. **No deviation.** No game rule, RNG draw, seed, `DESC`, `.sgtp`,
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

- a trace must keep one sink for its whole sequence: a second sink on
  the same midend recreates the cache, so its trace starts with
  `blitter_new` and must not expect the previous blitters;
- a game whose cache depends on the state must refresh the parts that a
  new state invalidates, as the C games do in `game_redraw`. The midend
  keeps the cache across a move and a restart, so it does not hide a
  stale cache from the game;
- the shell must call `forceRedraw` on a resize and `freeDrawState` on
  close. A new entry point that draws while bypassing `resizeEvent`
  would keep a zoom-sized bitmap.

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
   recreation would draw with stale geometry. A zoom change changes the
   blitter bitmap size while the tile size stays the same, so the forced
   redraw has to recreate the cache too.
4. **Recreate the drawstate after every state change.** Rejected: it
   allocates the blitter once per move where C allocates it only on a
   forced redraw, and it makes a post-move trace differ from C.
5. **Free a stale cache through the trace sink that replaced it.**
   Rejected: that trace did not allocate the id, so the free would name
   an id the trace does not own and the normalizer rejects it. The free
   goes to the sink that created the cache.

## Amendment: the inertia follow callback

- **Date:** 2026-11-16

`DrawSink.inertiaFollow(isSolved)` is the Android `drawing_api` follow
callback (`puzzles.h:867`), not a drawstate operation and not a game
rule. The game calls it from `redraw`, and the sink records
`{"op":"inertia_follow","solved":true|false}`. It neither reads nor
changes the drawstate, allocates no blitter, and does not affect the
first-draw clear. The lifecycle in the decision above is unchanged. The
seam and the shell behavior are defined in
`docs/adr/0007-kotlin-game-seam.md`.

## Amendment: the thick line primitive

- **Date:** 2026-11-16

`DrawSink.thickLine(...)` is a drawing primitive, not a drawstate or
blitter operation. It neither reads nor changes the drawstate, allocates
no blitter, and does not affect the first-draw clear. A game may call it
inside any draw between `start_draw` and `end_draw`, and the cache rules
in the decision above apply unchanged. The seam definition and the trace
format are in `docs/adr/0007-kotlin-game-seam.md`.

## Amendment: the thick circle primitive

- **Date:** 2026-11-16

`DrawSink.thickCircle(...)` is a drawing primitive, not a drawstate or
blitter operation. It neither reads nor changes the drawstate, allocates
no blitter, and does not affect the first-draw clear. A game may call it
inside any draw between `start_draw` and `end_draw`, and the cache rules
in the decision above apply unchanged. The seam definition and the trace
format are in `docs/adr/0007-kotlin-game-seam.md`.

## Amendment: fractional thick line endpoints

- **Date:** 2026-11-16

`DrawSink.thickLine` takes `Float` endpoints. This changes only the
argument type of the primitive, because upstream `draw_thick_line`
takes float coordinates. It remains a drawing operation, not a
drawstate or blitter operation, so the lifecycle rules in the decision
above are unchanged. The rounding rule and the callers are in
`docs/adr/0007-kotlin-game-seam.md`.

## Amendment: the thick polygon primitive

- **Date:** 2026-11-17

`DrawSink.thickPoly(...)` is a drawing primitive, not a drawstate or
blitter operation. It neither reads nor changes the drawstate, allocates
no blitter, and does not affect the first-draw clear. A game may call it
inside any draw between `start_draw` and `end_draw`, and the cache rules
in the decision above apply unchanged. The seam definition, the trace
format, and the callback order are in
`docs/adr/0007-kotlin-game-seam.md`.
