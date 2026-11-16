package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. The `inertia_follow` operation of the trace.
 *
 * `inertia_follow` is the Android `drawing_api` follow callback, not a
 * game rule. The oracle records it as `{"op":"inertia_follow",
 * "solved":true|false}` (`oracle.c:707-715`). No ported game calls it
 * yet, so this test wraps `fifteen` and adds the callback to its draw.
 */
class InertiaFollowTraceTest {

    @Test
    fun `a redraw records the follow callback before end_draw`() {
        val midend = Midend.newGame(FollowingFifteen, "4x4", "12345")
        val normalized = TraceNormalizer.normalize(KotlinTraceSink.trace(midend))
        val lines = normalized.lines()

        val start = lines.indexOf("start_draw")
        val preceding = lines.indexOfFirst { it.startsWith("poly ") }
        val follow = lines.indexOf("inertia_follow true")
        val end = lines.indexOf("end_draw")

        assertTrue("the trace has a start", start >= 0)
        assertTrue("the game draws before the callback", preceding > start)
        assertTrue("the callback follows the preceding operations", follow > preceding)
        assertTrue("the callback comes before end_draw", end > follow)
        assertEquals(1, lines.count { it.startsWith("inertia_follow") })
    }

    @Test
    fun `the raw operation carries the oracle field names`() {
        val sink = KotlinTraceSink()
        sink.startDraw()
        sink.rect(0, 0, 24, 24, 0)
        sink.inertiaFollow(false)
        sink.endDraw()

        val names = sink.ops.map { (it.fields["op"] as JsonValue.Str).value }
        assertEquals(listOf("start_draw", "rect", "inertia_follow", "end_draw"), names)
        assertEquals(listOf("op", "solved"), sink.ops[2].fields.keys.toList())
        assertEquals(false, (sink.ops[2].fields["solved"] as JsonValue.Bool).value)
    }

    /** Delegates every rule to `fifteen` and calls the follow callback. */
    private object FollowingFifteen : Game by Fifteen {
        override fun redraw(
            state: Game.State,
            ui: Game.Ui,
            drawState: Game.DrawState,
            sink: DrawSink,
            tileSize: Int,
        ) {
            Fifteen.redraw(state, ui, drawState, sink, tileSize)
            sink.inertiaFollow(true)
        }
    }
}
