package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Size
import kotlin.math.roundToInt

/**
 * Turns the Kotlin drawing seam into the same JSON trace the oracle
 * records. A test then runs both through [TraceNormalizer], so the
 * contract is exactly the one in `docs/adr/0003-strict-draw-traces.md`.
 */
class KotlinTraceSink : DrawSink {

    val ops = ArrayList<JsonValue.Obj>()

    /**
     * When false, drawing still reaches the game and the blitter ids
     * still advance, but no operation is appended. [companion object]
     * uses it for the first draw and the input of a warm trace.
     */
    var recording: Boolean = true

    private fun record(vararg fields: Pair<String, JsonValue>) {
        if (recording) ops += obj(*fields)
    }

    override fun changedState(canUndo: Boolean, canRedo: Boolean) {
        record(
            "op" to JsonValue.Str("changed_state"),
            "canundo" to JsonValue.Bool(canUndo),
            "canredo" to JsonValue.Bool(canRedo),
        )
    }

    override fun startDraw() {
        record("op" to JsonValue.Str("start_draw"))
    }

    override fun endDraw() {
        record("op" to JsonValue.Str("end_draw"))
    }

    override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
        record(
            "op" to JsonValue.Str("rect"),
            "x" to num(x),
            "y" to num(y),
            "w" to num(width),
            "h" to num(height),
            "c" to num(colour),
        )
    }

    override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
        record(
            "op" to JsonValue.Str("line"),
            "x1" to num(x1),
            "y1" to num(y1),
            "x2" to num(x2),
            "y2" to num(y2),
            "c" to num(colour),
        )
    }

    /**
     * `tr_draw_thick_line` (`oracle.c:695-706`) records `t` first with
     * the thickness in thousandths, then the endpoints, then the colour.
     * The oracle converts the thickness and both endpoints with
     * `tc_milli`; the seam's endpoints are integers in game coordinates,
     * so their milli fields are exact.
     */
    override fun thickLine(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        thickness: Float,
        colour: Int,
    ) {
        record(
            "op" to JsonValue.Str("thick_line"),
            "t" to num((thickness * 1000.0f).roundToInt()),
            "x1" to num(x1 * 1000),
            "y1" to num(y1 * 1000),
            "x2" to num(x2 * 1000),
            "y2" to num(y2 * 1000),
            "c" to num(colour),
        )
    }

    override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
        record(
            "op" to JsonValue.Str("circle"),
            "cx" to num(cx),
            "cy" to num(cy),
            "r" to num(radius),
            "fill" to num(fillColour),
            "outline" to num(outlineColour),
        )
    }

    override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
        record(
            "op" to JsonValue.Str("poly"),
            "fill" to num(fillColour),
            "outline" to num(outlineColour),
            "pts" to JsonValue.Arr(points.map { num(it) }),
        )
    }

    override fun text(
        x: Int,
        y: Int,
        font: Int,
        size: Int,
        align: Int,
        colour: Int,
        text: String,
    ) {
        record(
            "op" to JsonValue.Str("text"),
            "x" to num(x),
            "y" to num(y),
            "font" to num(font),
            "size" to num(size),
            "align" to num(align),
            "c" to num(colour),
            "text" to JsonValue.Str(text),
        )
    }

    override fun clip(x: Int, y: Int, width: Int, height: Int) {
        record(
            "op" to JsonValue.Str("clip"),
            "x" to num(x),
            "y" to num(y),
            "w" to num(width),
            "h" to num(height),
        )
    }

    override fun unclip() {
        record("op" to JsonValue.Str("unclip"))
    }

    override fun update(x: Int, y: Int, width: Int, height: Int) {
        record(
            "op" to JsonValue.Str("update"),
            "x" to num(x),
            "y" to num(y),
            "w" to num(width),
            "h" to num(height),
        )
    }

    /**
     * The last status text this drawing emitted. Upstream `drawing.c`
     * keeps `laststatus` per drawing and skips an unchanged text, so a
     * warm frame has no status line when the input already drew the same
     * text. A cold trace still has its one status line.
     */
    private var lastStatus: String? = null

    override fun status(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        record(
            "op" to JsonValue.Str("status"),
            "text" to JsonValue.Str(text),
        )
    }

    /** Blitter ids start at 1 in every trace, as the oracle's do. */
    private var nextBlitterId = 1

    override fun inertiaFollow(isSolved: Boolean) {
        record(
            "op" to JsonValue.Str("inertia_follow"),
            "solved" to JsonValue.Bool(isSolved),
        )
    }

    override fun blitterNew(width: Int, height: Int): Int {
        val id = nextBlitterId++
        record(
            "op" to JsonValue.Str("blitter_new"),
            "id" to num(id),
            "w" to num(width),
            "h" to num(height),
        )
        return id
    }

    override fun blitterFree(id: Int) {
        record(
            "op" to JsonValue.Str("blitter_free"),
            "id" to num(id),
        )
    }

    override fun blitterSave(id: Int, x: Int, y: Int) {
        record(
            "op" to JsonValue.Str("blitter_save"),
            "id" to num(id),
            "x" to num(x),
            "y" to num(y),
        )
    }

    override fun blitterLoad(id: Int, x: Int, y: Int) {
        record(
            "op" to JsonValue.Str("blitter_load"),
            "id" to num(id),
            "x" to num(x),
            "y" to num(y),
        )
    }

    companion object {
        /** Builds the trace object for the first redraw of [midend]. */
        fun trace(midend: Midend): JsonValue.Obj {
            val sink = KotlinTraceSink()
            midend.recordNewGameTrace(sink)
            return objectFor(midend, sink)
        }

        /**
         * Builds the trace object for the current state of [midend],
         * without the `changed_state` operation the oracle emits for a
         * new game. Use this after keys or preferences changed the UI.
         *
         * [statusAlreadyDrawn] primes the sink's status cache without
         * recording the line. The oracle's `trace` op replays its key
         * sequence with recording on, clears those operations, and keeps
         * the drawing cache, so a game with a status bar does not emit
         * the same text in the traced redraw. Pass true for that shape.
         */
        fun stateTrace(midend: Midend): JsonValue.Obj =
            stateTrace(midend, midend.tileSize, statusAlreadyDrawn = false)

        /**
         * The same redraw as [stateTrace], at an explicit [tileSize].
         * A sized oracle trace draws at the tile size its viewport
         * chose, which is not always the preferred size. The draw
         * sequence follows [Midend.recordRedraw], and the trace header
         * reports the size the draw uses.
         */
        fun stateTrace(
            midend: Midend,
            tileSize: Int,
            statusAlreadyDrawn: Boolean = false,
        ): JsonValue.Obj {
            val size = midend.game.computeSize(midend.params, tileSize)
            val sink = KotlinTraceSink()
            if (statusAlreadyDrawn) {
                sink.recording = false
                sink.status(midend.statusText())
                sink.recording = true
            }
            val drawState = midend.ensureDrawState(tileSize, sink)
            // A new sink replaces the drawstate, so this is its first
            // draw. Keep the helper in step with `Midend.recordRedraw`.
            val first = midend.takeFirstDraw()
            sink.startDraw()
            if (first) sink.rect(0, 0, size.width, size.height, 0)
            midend.game.redraw(midend.state, midend.ui, drawState, sink, tileSize)
            if (midend.game.wantsStatusBar) sink.status(midend.statusText())
            if (first) sink.update(0, 0, size.width, size.height)
            sink.endDraw()
            return objectFor(midend, sink, size)
        }

        /**
         * The warm path: one sink and one drawstate. Recording is off for
         * the first full draw and for the frames that [points] cause, so
         * the cache and the blitter reach the state the app would have.
         * It is then on for one redraw, which is the trace. The blitter
         * id counter advanced while recording was off, so the trace has
         * no `blitter_new` but its `blitter_load` and `blitter_save`
         * name the live blitter.
         *
         * [tileSize] defaults to the preferred tile. A sized warm trace
         * passes the tile size of the viewport, and [points] are then in
         * game coordinates, as the oracle's `traceWarm` receives them
         * after it subtracts the letterbox origin.
         */
        fun warmTrace(
            midend: Midend,
            points: List<OracleClient.Point>,
            tileSize: Int = midend.tileSize,
        ): JsonValue.Obj {
            val sink = KotlinTraceSink()
            sink.recording = false
            redrawSized(midend, sink, tileSize)
            for (point in points) {
                val outcome = midend.processKey(
                    point.x,
                    point.y,
                    point.button,
                    tileSize,
                )
                if (outcome == KeyOutcome.SomeEffect) {
                    redrawSized(midend, sink, tileSize)
                }
            }
            sink.ops.clear()
            sink.recording = true
            redrawSized(midend, sink, tileSize)
            return objectFor(
                midend,
                sink,
                midend.game.computeSize(midend.params, tileSize),
            )
        }

        /**
         * One [Midend.recordRedraw] at an explicit [tileSize]. The
         * sequence is the same as the midend's own redraw, so a sized
         * warm frame sees the same first-draw clear and cache lifecycle.
         */
        private fun redrawSized(
            midend: Midend,
            sink: KotlinTraceSink,
            tileSize: Int,
        ) {
            val size = midend.game.computeSize(midend.params, tileSize)
            val drawState = midend.ensureDrawState(tileSize, sink)
            val first = midend.takeFirstDraw()
            sink.startDraw()
            if (first) sink.rect(0, 0, size.width, size.height, 0)
            midend.game.redraw(midend.state, midend.ui, drawState, sink, tileSize)
            if (midend.game.wantsStatusBar) sink.status(midend.statusText())
            if (first) sink.update(0, 0, size.width, size.height)
            sink.endDraw()
        }

        private fun objectFor(midend: Midend, sink: KotlinTraceSink): JsonValue.Obj =
            objectFor(midend, sink, midend.size)

        private fun objectFor(
            midend: Midend,
            sink: KotlinTraceSink,
            size: Size,
        ): JsonValue.Obj {
            val colours = midend.game.colours()
            val palette = JsonValue.Arr(
                (0 until colours.size / 3).map { index ->
                    JsonValue.Arr(
                        (0 until 3).map { channel ->
                            num((colours[index * 3 + channel] * 1000.0f).roundToInt())
                        },
                    )
                },
            )

            return obj(
                "traceSchema" to num(1),
                "game" to JsonValue.Str(midend.game.name),
                "id" to JsonValue.Str(midend.id()),
                "params" to JsonValue.Str(
                    midend.game.encodeParams(midend.currentParams, true),
                ),
                "w" to num(size.width),
                "h" to num(size.height),
                "palette" to palette,
                "ops" to JsonValue.Arr(sink.ops),
            )
        }

        private fun num(value: Int): JsonValue.Num = JsonValue.Num(value.toLong())

        private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
            JsonValue.Obj(linkedMapOf(*fields))
    }
}
