package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. The `thick_circle` operation of the trace.
 *
 * `draw_thick_circle` is an upstream `drawing_api` primitive. The oracle
 * records it as `{"op":"thick_circle","t":..,"cx":..,"cy":..,"r":..,
 * "fill":..,"outline":..}` (`oracle.c:520-532`) and expresses the
 * thickness, the centre, and the radius in thousandths. No ported game
 * draws a thick circle yet, so the tests call the sink directly and wrap
 * `fifteen` for the in-draw order.
 */
class ThickCircleTraceTest {

    /** A thick circle as the oracle writes it, in the oracle field order. */
    private val oracleOp =
        """{"op":"thick_circle","t":1500,"cx":1000,"cy":2000,"r":3000,"fill":4,"outline":5}"""

    @Test
    fun `the sink records the oracle fields in the oracle order`() {
        val sink = KotlinTraceSink()
        sink.thickCircle(1, 2, 3, 1.5f, 4, 5)

        val op = sink.ops.single()
        assertEquals(
            listOf("op", "t", "cx", "cy", "r", "fill", "outline"),
            op.fields.keys.toList(),
        )
        assertEquals("thick_circle", (op.fields["op"] as JsonValue.Str).value)
        assertEquals(1500L, (op.fields["t"] as JsonValue.Num).value)
        assertEquals(1000L, (op.fields["cx"] as JsonValue.Num).value)
        assertEquals(2000L, (op.fields["cy"] as JsonValue.Num).value)
        assertEquals(3000L, (op.fields["r"] as JsonValue.Num).value)
        assertEquals(4L, (op.fields["fill"] as JsonValue.Num).value)
        assertEquals(5L, (op.fields["outline"] as JsonValue.Num).value)
    }

    @Test
    fun `the thickness rounds to thousandths like the oracle`() {
        // `TILESIZE/16` is 3 at the upstream 48-pixel tile, and
        // `tc_milli` rounds a half away from zero.
        val sink = KotlinTraceSink()
        sink.thickCircle(0, 0, 0, 3.0f, 1, 1)
        sink.thickCircle(0, 0, 0, 0.0625f, 1, 1)

        assertEquals(3000L, (sink.ops[0].fields["t"] as JsonValue.Num).value)
        assertEquals(63L, (sink.ops[1].fields["t"] as JsonValue.Num).value)
    }

    @Test
    fun `the normalized operation matches the oracle line`() {
        val sink = KotlinTraceSink()
        sink.startDraw()
        sink.thickCircle(1, 2, 3, 1.5f, 4, 5)
        sink.endDraw()

        val ours = TraceNormalizer.normalize(trace(sink.ops))
        val oracle = TraceNormalizer.normalize(
            trace(oracleOps("""{"op":"start_draw"},$oracleOp,{"op":"end_draw"}""")),
        )

        assertEquals(oracle, ours)
        assertTrue(ours.lines().contains("thick_circle 1500 1000 2000 3000 4 5"))
    }

    @Test
    fun `a thick circle is drawn inside the draw pair and after the game`() {
        val midend = Midend.newGame(ThickFifteen, "4x4", "12345")
        val lines = TraceNormalizer.normalize(KotlinTraceSink.trace(midend)).lines()

        val start = lines.indexOf("start_draw")
        val preceding = lines.indexOfLast { it.startsWith("poly ") }
        val thick = lines.indexOf("thick_circle 3000 8000 16000 24000 1 2")
        val end = lines.indexOf("end_draw")

        assertTrue("the trace has a start", start >= 0)
        assertTrue("the game draws before the circle", preceding > start)
        assertTrue("the circle follows the game drawing", thick > preceding)
        assertTrue("the circle comes before end_draw", end > thick)
        assertEquals(1, lines.count { it.startsWith("thick_circle") })
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

    /** Delegates every rule to `fifteen` and adds one thick circle. */
    private object ThickFifteen : Game by Fifteen {
        override fun redraw(
            state: Game.State,
            ui: Game.Ui,
            drawState: Game.DrawState,
            sink: DrawSink,
            tileSize: Int,
        ) {
            Fifteen.redraw(state, ui, drawState, sink, tileSize)
            sink.thickCircle(8, 16, 24, 3.0f, 1, 2)
        }
    }
}
