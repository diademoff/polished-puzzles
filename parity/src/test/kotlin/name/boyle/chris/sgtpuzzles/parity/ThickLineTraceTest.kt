package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. The `thick_line` operation of the trace.
 *
 * `draw_thick_line` is an upstream `drawing_api` primitive that takes
 * float coordinates. The oracle records it as
 * `{"op":"thick_line","t":..,"x1":..,"y1":..,"x2":..,"y2":..,"c":..}`
 * (`oracle.c:695-706`) and expresses the thickness and the endpoints in
 * thousandths with `lroundf(value * 1000)`. A game can pass a fractional
 * endpoint, as `tracks` does, so the tests use fractional input. No
 * ported game draws a thick line yet, so the tests call the sink
 * directly and wrap `fifteen` for the in-draw order.
 */
class ThickLineTraceTest {

    /** A thick line as the oracle writes it, in the oracle field order. */
    private val oracleOp =
        """{"op":"thick_line","t":1500,"x1":1250,"y1":2500,"x2":3750,"y2":4000,"c":5}"""

    @Test
    fun `the sink records the oracle fields in the oracle order`() {
        val sink = KotlinTraceSink()
        sink.thickLine(1.25f, 2.5f, 3.75f, 4f, 1.5f, 5)

        val op = sink.ops.single()
        assertEquals(
            listOf("op", "t", "x1", "y1", "x2", "y2", "c"),
            op.fields.keys.toList(),
        )
        assertEquals("thick_line", (op.fields["op"] as JsonValue.Str).value)
        assertEquals(1500L, (op.fields["t"] as JsonValue.Num).value)
        assertEquals(1250L, (op.fields["x1"] as JsonValue.Num).value)
        assertEquals(2500L, (op.fields["y1"] as JsonValue.Num).value)
        assertEquals(3750L, (op.fields["x2"] as JsonValue.Num).value)
        assertEquals(4000L, (op.fields["y2"] as JsonValue.Num).value)
        assertEquals(5L, (op.fields["c"] as JsonValue.Num).value)
    }

    @Test
    fun `the thousandths round like tc_milli`() {
        // The endpoints scale to 59250, 3125, 62.5, and -62.5, and
        // `tc_milli` rounds a half away from zero. A fractional endpoint
        // must be rounded, not truncated.
        val sink = KotlinTraceSink()
        sink.thickLine(59.25f, 3.125f, 0.0625f, -0.0625f, 3.125f, 1)

        assertEquals(3125L, (sink.ops[0].fields["t"] as JsonValue.Num).value)
        assertEquals(59250L, (sink.ops[0].fields["x1"] as JsonValue.Num).value)
        assertEquals(3125L, (sink.ops[0].fields["y1"] as JsonValue.Num).value)
        assertEquals(63L, (sink.ops[0].fields["x2"] as JsonValue.Num).value)
        assertEquals(-63L, (sink.ops[0].fields["y2"] as JsonValue.Num).value)
    }

    /**
     * `draw_thick_line` replaces a thickness below one pixel with `1.0F`
     * before it calls the drawing API (`drawing.c:117-118`), so the
     * oracle records `1000` and never the raw `0.0625`.
     */
    @Test
    fun `a thickness below one pixel is clamped before the trace`() {
        val sink = KotlinTraceSink()
        sink.startDraw()
        sink.thickLine(59.25f, 3.125f, 0.0625f, -0.0625f, 0.0625f, 1)
        sink.endDraw()

        assertEquals(1000L, (sink.ops[1].fields["t"] as JsonValue.Num).value)

        val lines = TraceNormalizer.normalize(trace(sink.ops)).lines()
        assertTrue(lines.contains("thick_line 1000 59250 3125 63 -63 1"))
    }

    @Test
    fun `the normalized operation matches the oracle line`() {
        val sink = KotlinTraceSink()
        sink.startDraw()
        sink.thickLine(1.25f, 2.5f, 3.75f, 4f, 1.5f, 5)
        sink.endDraw()

        val ours = TraceNormalizer.normalize(trace(sink.ops))
        val oracle = TraceNormalizer.normalize(
            trace(oracleOps("""{"op":"start_draw"},$oracleOp,{"op":"end_draw"}""")),
        )

        assertEquals(oracle, ours)
        assertTrue(ours.lines().contains("thick_line 1500 1250 2500 3750 4000 5"))
    }

    /**
     * The frozen `tracks` trace contains this line
     * (`testdata/trace/tracks/8x8dt.trace`): two integer endpoints and
     * one fractional endpoint. An `Int` seam would truncate `59.25` to
     * `59` and write `59000`.
     */
    @Test
    fun `the tracks-shaped fractional line matches the oracle format`() {
        val sink = KotlinTraceSink()
        sink.startDraw()
        sink.thickLine(63f, 73f, 59.25f, 73f, 3.75f, 5)
        sink.endDraw()

        val lines = TraceNormalizer.normalize(trace(sink.ops)).lines()
        assertTrue(lines.contains("thick_line 3750 63000 73000 59250 73000 5"))
    }

    @Test
    fun `a thick line is drawn inside the draw pair and after the game`() {
        val midend = Midend.newGame(ThickFifteen, "4x4", "12345")
        val lines = TraceNormalizer.normalize(KotlinTraceSink.trace(midend)).lines()

        val start = lines.indexOf("start_draw")
        val preceding = lines.indexOfLast { it.startsWith("poly ") }
        val thick = lines.indexOf("thick_line 3000 8000 16000 24250 32000 1")
        val end = lines.indexOf("end_draw")

        assertTrue("the trace has a start", start >= 0)
        assertTrue("the game draws before the line", preceding > start)
        assertTrue("the line follows the game drawing", thick > preceding)
        assertTrue("the line comes before end_draw", end > thick)
        assertEquals(1, lines.count { it.startsWith("thick_line") })
    }

    private fun oracleOps(ops: String): List<JsonValue> =
        (Json.parse("[$ops]") as JsonValue.Arr).items

    private fun trace(ops: List<JsonValue>): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "traceSchema" to JsonValue.Num(1),
            "game" to JsonValue.Str("Test"),
            "id" to JsonValue.Str("1x1:0"),
            "params" to JsonValue.Str("1x1"),
            "w" to JsonValue.Num(24),
            "h" to JsonValue.Num(24),
            "palette" to JsonValue.Arr(
                listOf(
                    JsonValue.Arr(
                        listOf(JsonValue.Num(0), JsonValue.Num(0), JsonValue.Num(0)),
                    ),
                ),
            ),
            "ops" to JsonValue.Arr(ops),
        ),
    )

    /** Delegates every rule to `fifteen` and adds one thick line. */
    private object ThickFifteen : Game by Fifteen {
        override fun redraw(
            state: Game.State,
            ui: Game.Ui,
            drawState: Game.DrawState,
            sink: DrawSink,
            tileSize: Int,
        ) {
            Fifteen.redraw(state, ui, drawState, sink, tileSize)
            sink.thickLine(8f, 16f, 24.25f, 32f, 3.0f, 1)
        }
    }
}
