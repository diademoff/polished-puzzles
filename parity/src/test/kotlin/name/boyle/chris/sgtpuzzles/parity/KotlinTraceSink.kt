package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
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

    override fun changedState(canUndo: Boolean, canRedo: Boolean) {
        ops += obj(
            "op" to JsonValue.Str("changed_state"),
            "canundo" to JsonValue.Bool(canUndo),
            "canredo" to JsonValue.Bool(canRedo),
        )
    }

    override fun startDraw() {
        ops += obj("op" to JsonValue.Str("start_draw"))
    }

    override fun endDraw() {
        ops += obj("op" to JsonValue.Str("end_draw"))
    }

    override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
        ops += obj(
            "op" to JsonValue.Str("rect"),
            "x" to num(x),
            "y" to num(y),
            "w" to num(width),
            "h" to num(height),
            "c" to num(colour),
        )
    }

    override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
        ops += obj(
            "op" to JsonValue.Str("line"),
            "x1" to num(x1),
            "y1" to num(y1),
            "x2" to num(x2),
            "y2" to num(y2),
            "c" to num(colour),
        )
    }

    override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
        ops += obj(
            "op" to JsonValue.Str("circle"),
            "cx" to num(cx),
            "cy" to num(cy),
            "r" to num(radius),
            "fill" to num(fillColour),
            "outline" to num(outlineColour),
        )
    }

    override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
        ops += obj(
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
        ops += obj(
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
        ops += obj(
            "op" to JsonValue.Str("clip"),
            "x" to num(x),
            "y" to num(y),
            "w" to num(width),
            "h" to num(height),
        )
    }

    override fun unclip() {
        ops += obj("op" to JsonValue.Str("unclip"))
    }

    override fun update(x: Int, y: Int, width: Int, height: Int) {
        ops += obj(
            "op" to JsonValue.Str("update"),
            "x" to num(x),
            "y" to num(y),
            "w" to num(width),
            "h" to num(height),
        )
    }

    override fun status(text: String) {
        ops += obj(
            "op" to JsonValue.Str("status"),
            "text" to JsonValue.Str(text),
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
         */
        fun stateTrace(midend: Midend): JsonValue.Obj {
            val sink = KotlinTraceSink()
            midend.recordRedraw(sink)
            return objectFor(midend, sink)
        }

        /**
         * The same redraw as [stateTrace], at an explicit [tileSize].
         * A sized oracle trace draws at the tile size its viewport
         * chose, which is not always the preferred size. The draw
         * sequence follows [Midend.recordRedraw].
         */
        fun stateTrace(midend: Midend, tileSize: Int): JsonValue.Obj {
            val size = midend.game.computeSize(midend.params, tileSize)
            val sink = KotlinTraceSink()
            sink.startDraw()
            sink.rect(0, 0, size.width, size.height, 0)
            midend.game.redraw(midend.state, midend.ui, sink, tileSize)
            if (midend.game.wantsStatusBar) sink.status(midend.statusText())
            sink.update(0, 0, size.width, size.height)
            sink.endDraw()
            return objectFor(midend, sink, size)
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
                "w" to num(midend.size.width),
                "h" to num(midend.size.height),
                "palette" to palette,
                "ops" to JsonValue.Arr(sink.ops),
            )
        }

        private fun num(value: Int): JsonValue.Num = JsonValue.Num(value.toLong())

        private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
            JsonValue.Obj(linkedMapOf(*fields))
    }
}
