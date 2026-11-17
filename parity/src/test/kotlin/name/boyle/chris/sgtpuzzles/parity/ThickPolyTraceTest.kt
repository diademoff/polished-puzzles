package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. The `thick_poly` operation of the trace.
 *
 * `draw_thick_polygon` is an upstream `drawing_api` primitive. The
 * oracle records it as `{"op":"thick_poly","t":..,"pts":[..],"fill":..,
 * "outline":..}` (`oracle.c:497-511`) and expresses the thickness in
 * thousandths. Keen is the ported game that draws one; the live oracle
 * comparison for the operation is in `KeenTraceTest`, which compares
 * every frozen frame with `traceNew`. The tests here call the sink
 * directly and wrap `fifteen` for the in-draw order.
 */
class ThickPolyTraceTest {

    /** A thick polygon as the oracle writes it, in the oracle field order. */
    private val oracleOp =
        """{"op":"thick_poly","t":1500,"pts":[0,24,48,24,24,0],"fill":4,"outline":5}"""

    @Test
    fun `the sink records the oracle fields in the oracle order`() {
        val sink = KotlinTraceSink()
        sink.thickPoly(intArrayOf(0, 24, 48, 24, 24, 0), 1.5f, 4, 5)

        val op = sink.ops.single()
        assertEquals(
            listOf("op", "t", "pts", "fill", "outline"),
            op.fields.keys.toList(),
        )
        assertEquals("thick_poly", (op.fields["op"] as JsonValue.Str).value)
        assertEquals(1500L, (op.fields["t"] as JsonValue.Num).value)
        assertEquals(
            listOf(0L, 24L, 48L, 24L, 24L, 0L),
            (op.fields["pts"] as JsonValue.Arr).items.map { (it as JsonValue.Num).value },
        )
        assertEquals(4L, (op.fields["fill"] as JsonValue.Num).value)
        assertEquals(5L, (op.fields["outline"] as JsonValue.Num).value)
    }

    @Test
    fun `the thickness rounds to thousandths like the oracle`() {
        // `TILESIZE/16` is 3 at the upstream 48-pixel tile, and
        // `tc_milli` rounds a half away from zero.
        val sink = KotlinTraceSink()
        sink.thickPoly(intArrayOf(0, 0), 3.0f, 1, 1)
        sink.thickPoly(intArrayOf(0, 0), 0.0625f, 1, 1)

        assertEquals(3000L, (sink.ops[0].fields["t"] as JsonValue.Num).value)
        assertEquals(63L, (sink.ops[1].fields["t"] as JsonValue.Num).value)
    }

    @Test
    fun `the normalized operation matches the oracle line`() {
        val sink = KotlinTraceSink()
        sink.startDraw()
        sink.thickPoly(intArrayOf(0, 24, 48, 24, 24, 0), 1.5f, 4, 5)
        sink.endDraw()

        val ours = TraceNormalizer.normalize(trace(sink.ops))
        val oracle = TraceNormalizer.normalize(
            trace(oracleOps("""{"op":"start_draw"},$oracleOp,{"op":"end_draw"}""")),
        )

        assertEquals(oracle, ours)
        assertTrue(ours.lines().contains("thick_poly 1500 4 5 3 0 24 48 24 24 0"))
    }

    @Test
    fun `a thick polygon is drawn inside the draw pair and after the game`() {
        val midend = Midend.newGame(ThickPolyFifteen, "4x4", "12345")
        val lines = TraceNormalizer.normalize(KotlinTraceSink.trace(midend)).lines()

        val start = lines.indexOf("start_draw")
        val preceding = lines.indexOfLast { it.startsWith("poly ") }
        val thick = lines.indexOf("thick_poly 3000 1 2 3 8 16 24 16 16 32")
        val end = lines.indexOf("end_draw")

        assertTrue("the trace has a start", start >= 0)
        assertTrue("the game draws before the polygon", preceding > start)
        assertTrue("the polygon follows the game drawing", thick > preceding)
        assertTrue("the polygon comes before end_draw", end > thick)
        assertEquals(1, lines.count { it.startsWith("thick_poly") })
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

    /** Delegates every rule to `fifteen` and adds one thick polygon. */
    private object ThickPolyFifteen : Game by Fifteen {
        override fun redraw(
            state: Game.State,
            ui: Game.Ui,
            drawState: Game.DrawState,
            sink: DrawSink,
            tileSize: Int,
        ) {
            Fifteen.redraw(state, ui, drawState, sink, tileSize)
            sink.thickPoly(intArrayOf(8, 16, 24, 16, 16, 32), 3.0f, 1, 2)
        }
    }
}
