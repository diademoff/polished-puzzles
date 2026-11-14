# ADR 0004: Compose display-list renderer

- **Status:** accepted
- **Date:** 2026-11-14

## Context

`PARITY.md` makes the C drawing primitives the contract. ADR 0003
records them as strict traces. The app still displayed the board with a
`Bitmap`/`Canvas` owned by `GameView`: the C engine drew into the
bitmap, and the view blitted it. That path is fast, but it is not the
Compose renderer that Phase 2 requires, and it makes the board a black
box for accessibility and for future Kotlin ports.

The C engine already calls the frontend through `ViewCallbacks`. That
seam is small: rectangles, lines, polygons, circles, text, clip, and
blitters. The engine owns the geometry; the frontend owns the pixels.

## Decision

1. **Record the C primitives in Kotlin.** `BoardRecorder` implements
   `ViewCallbacks` and produces a `BoardFrame`: an ordered list of
   `DrawOp` values. `BoardFrame` has no Android or Compose types, so the
   JVM tests can compare it with the oracle traces.
2. **Draw the frame on a Compose Canvas.** `BoardCanvas` maps each
   `DrawOp` to a Compose draw call. It uses the same compatibility
   details as the old renderer: half-pixel rectangles, square line caps,
   the game palette by index, and the same alignment flags for text.
3. **The C engine starts and ends each drawing pass.** `startDraw` and
   `endDraw` were added to `ViewCallbacks` and to `android.c`. The
   recorder clears a pass on start and publishes it on end. This is the
   boundary that the old bitmap did not need to name.
4. **The scene is retained.** The engine redraws incrementally after a
   move; a tile update does not redraw the whole board. The Compose
   renderer must therefore retain the previous operations. The recorder
   appends each pass to a scene and publishes the whole scene. A
   compaction threshold (20,000 operations or four full boards) resets
   the scene and asks the activity for a full redraw. Without it, a long
   game would redraw its entire history every frame.
5. **Blitters are cached images.** A blitter save is a copy of the
   current scene; a load replays it. Replaying the list directly is
   exponential, because the next save contains the previous load.
   Instead, the Compose layer renders each snapshot once into an
   `ImageBitmap` and caches it. A self-reference is cut by the
   in-progress set.
6. **The board palette is not themed.** The renderer uses the palette
   from `midend_colours`, with the night overrides that already existed.
   The shell theme does not change the board, as ADR 0002 and
   `PARITY.md` require.
7. **`GameView` keeps input, zoom, and geometry.** It still translates
   touches, runs pinch zoom, and holds the view transform. It publishes
   the transform matrix to the Compose Canvas. It no longer draws the
   board in production.
8. **The bitmap remains for the store screenshots.** `drawToBitmap` is
   off by default. The fastlane icon test turns it on because it crops
   the bitmap for a game icon. The visible board never uses it.

## Consequences

Positive:

- the displayed board is produced by a Compose Canvas;
- the renderer model is checked against all 288 oracle traces on the
  JVM, without a device;
- Text, clip, and blitter behaviour have one implementation, not two;
- the state and the primitive list are now available to the
  accessibility work (D-01).

Negative:

- the Compose draw of a retained scene is slower than a bitmap blit on
  a software GPU. On the emulator, P50 matches the old renderer, while
  P90/P99 are worse; the absolute check needs a physical device (D-02);
- the scene needs compaction, and the compaction triggers a full
  redraw;
- the bitmap path still exists for store screenshots and is dead code
  in production.

## Alternatives

1. **Keep the bitmap and wrap it in a Compose Canvas.** Rejected: the
   C engine would still own the raster; the renderer would not be
   implemented.
2. **Replay the whole engine draw on every frame.** Rejected: the engine
   draws incrementally; a full redraw per frame changes animations and
   is more work.
3. **Replay blitter operations directly.** Rejected: the snapshots
   contain earlier loads, so the replay is exponential. The hang was
   reproduced on Galaxies.
4. **Retained graphics layer instead of an operation list.** It would
   be closer to the old bitmap, but blitters and text would still need
   the same offscreen path. The operation list is simpler to test
   against the oracle.
