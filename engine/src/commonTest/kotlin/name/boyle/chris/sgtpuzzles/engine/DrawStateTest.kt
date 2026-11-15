package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. The midend owns one drawstate at a time, and the
 * seam offers the blitter operations that a caching game uses.
 *
 * The games that are ported today cache nothing, so they use
 * [Game.NoDrawState]. The wrapper below exercises the lifecycle with a
 * single blitter and does not change a game rule.
 */
class DrawStateTest {

    @Test
    fun `the first draw allocates the blitter before start_draw`() {
        val midend = Midend.newGame(BlitterGame(), "4x4", "12345")
        val sink = EventSink()

        midend.recordNewGameTrace(sink)

        val start = sink.events.indexOf("start_draw")
        assertTrue(start > 0, "start_draw must be in the trace: ${sink.events}")
        assertEquals(
            listOf("changed_state", "blitter_new:1:48:48"),
            sink.events.take(start),
            "changed_state precedes the blitter, as the oracle records it",
        )
        assertEquals(1, sink.events.count { it.startsWith("blitter_new") })
        assertTrue(sink.events.contains("blitter_save:1:0:0"))
        assertTrue(sink.events.contains("blitter_load:1:0:0"))

        // A second redraw of the same drawstate keeps it: no new
        // blitter and no free.
        midend.recordRedraw(sink)
        assertEquals(1, sink.events.count { it.startsWith("blitter_new") })
        assertEquals(0, sink.events.count { it.startsWith("blitter_free") })
    }

    @Test
    fun `a tile size change frees the old blitter and makes a new one`() {
        val midend = Midend.newGame(BlitterGame(), "4x4", "12345")
        val sink = EventSink()

        val first = midend.ensureDrawState(48, sink)
        assertEquals(listOf("blitter_new:1:48:48"), sink.events)
        assertSame(first, midend.ensureDrawState(48, sink), "the same size keeps the cache")
        assertEquals(1, sink.events.size)

        val second = midend.ensureDrawState(64, sink)
        assertEquals(
            listOf(
                "blitter_new:1:48:48",
                "blitter_free:1",
                "blitter_new:2:64:64",
            ),
            sink.events,
            "the old drawstate is freed before the new one is created",
        )
        assertSame(second, midend.drawState)
    }

    @Test
    fun `restart makes the next draw recreate the drawstate`() {
        val midend = Midend.fromGameId(
            BlitterGame(),
            "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15",
        )
        val sink = EventSink()

        midend.ensureDrawState(48, sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        midend.recordRedraw(sink)
        assertEquals(
            listOf("blitter_new:1:48:48", "blitter_free:1", "blitter_new:2:48:48"),
            sink.events.filter { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
            "a move marks the cache stale, as force_redraw does",
        )

        assertTrue(midend.restart())
        midend.recordRedraw(sink)
        assertEquals(
            listOf(
                "blitter_new:1:48:48",
                "blitter_free:1",
                "blitter_new:2:48:48",
                "blitter_free:2",
                "blitter_new:3:48:48",
            ),
            sink.events.filter { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
            "a restart marks the cache stale",
        )
    }

    @Test
    fun `a game without a drawstate adds no blitter operations`() {
        val sink = EventSink()
        val midend = Midend.newGame(Fifteen, "4x4", "12345")

        assertSame(Game.NoDrawState, midend.ensureDrawState(48, sink))
        midend.recordNewGameTrace(sink)

        assertEquals(0, sink.events.count { it.startsWith("blitter_") })
    }

    /**
     * A test-only game that caches one blitter. Every game rule and
     * every drawing primitive is [Fifteen]'s.
     */
    private class BlitterGame : Game by Fifteen {
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

    /** Records the blitter and draw boundaries, and the ids they use. */
    private class EventSink : DrawSink {
        val events = ArrayList<String>()
        private var nextBlitterId = 1

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            events += "changed_state"
        }

        override fun startDraw() {
            events += "start_draw"
        }

        override fun endDraw() {
            events += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {}
        override fun status(text: String) {
            events += "status"
        }

        override fun blitterNew(width: Int, height: Int): Int {
            val id = nextBlitterId++
            events += "blitter_new:$id:$width:$height"
            return id
        }

        override fun blitterFree(id: Int) {
            events += "blitter_free:$id"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            events += "blitter_save:$id:$x:$y"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            events += "blitter_load:$id:$x:$y"
        }
    }
}
