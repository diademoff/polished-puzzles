package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. The blitter operations of the trace.
 *
 * No ported game caches a background yet, so this test uses a wrapper
 * that delegates every rule and every primitive to `fifteen` and adds
 * one blitter. The normalizer must accept the sequence, and the ids and
 * field names must match the oracle's recording API.
 */
class BlitterTraceTest {

    @Test
    fun `a drawstate blitter comes before start_draw and uses the oracle fields`() {
        val midend = Midend.newGame(CachingFifteen, "4x4", "12345")
        val normalized = TraceNormalizer.normalize(KotlinTraceSink.trace(midend))
        val lines = normalized.lines()

        val changed = lines.indexOf("changed_state false false")
        val created = lines.indexOf("blitter_new 1 48 48")
        val start = lines.indexOf("start_draw")
        assertTrue("changed_state is recorded", changed >= 0)
        assertTrue("the first blitter comes after changed_state", created > changed)
        assertTrue("the first blitter comes before start_draw", start > created)
        assertEquals(1, lines.count { it.startsWith("blitter_new") })
        assertEquals(0, lines.count { it.startsWith("blitter_free") })

        val save = lines.indexOf("blitter_save 1 0 0")
        val load = lines.indexOf("blitter_load 1 0 0")
        assertTrue("the redraw saves the blitter", save >= 0)
        assertTrue("the redraw loads the blitter after the save", load > save)
        assertTrue("the save is inside the draw", save > start)
        assertTrue("the load is inside the draw", load < lines.indexOf("end_draw"))
    }

    @Test
    fun `the raw operations carry the oracle field names`() {
        val sink = KotlinTraceSink()
        val id = sink.blitterNew(20, 30)
        sink.blitterSave(id, 4, 5)
        sink.blitterLoad(id, 6, 7)
        sink.blitterFree(id)

        assertEquals(1, id)
        assertEquals(listOf("op", "id", "w", "h"), sink.ops[0].fields.keys.toList())
        assertEquals(listOf("op", "id", "x", "y"), sink.ops[1].fields.keys.toList())
        assertEquals(listOf("op", "id", "x", "y"), sink.ops[2].fields.keys.toList())
        assertEquals(listOf("op", "id"), sink.ops[3].fields.keys.toList())

        val names = sink.ops.map { (it.fields["op"] as JsonValue.Str).value }
        assertEquals(
            listOf("blitter_new", "blitter_save", "blitter_load", "blitter_free"),
            names,
        )
        assertEquals(1L, (sink.ops[0].fields["id"] as JsonValue.Num).value)
        assertEquals(20L, (sink.ops[0].fields["w"] as JsonValue.Num).value)
        assertEquals(30L, (sink.ops[0].fields["h"] as JsonValue.Num).value)
        assertEquals(4L, (sink.ops[1].fields["x"] as JsonValue.Num).value)
        assertEquals(5L, (sink.ops[1].fields["y"] as JsonValue.Num).value)
        assertEquals(6L, (sink.ops[2].fields["x"] as JsonValue.Num).value)
        assertEquals(7L, (sink.ops[2].fields["y"] as JsonValue.Num).value)
    }

    /** Delegates every game rule to `fifteen` and caches one blitter. */
    private object CachingFifteen : Game by Fifteen {
        private class Cache(val blitter: Int) : Game.DrawState

        override fun newDrawState(
            state: Game.State,
            tileSize: Int,
            sink: DrawSink,
        ): Game.DrawState = Cache(sink.blitterNew(tileSize, tileSize))

        override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
            sink.blitterFree((drawState as Cache).blitter)
        }

        override fun redraw(
            state: Game.State,
            ui: Game.Ui,
            drawState: Game.DrawState,
            sink: DrawSink,
            tileSize: Int,
        ) {
            val blitter = (drawState as Cache).blitter
            sink.blitterSave(blitter, 0, 0)
            Fifteen.redraw(state, ui, drawState, sink, tileSize)
            sink.blitterLoad(blitter, 0, 0)
        }
    }
}
