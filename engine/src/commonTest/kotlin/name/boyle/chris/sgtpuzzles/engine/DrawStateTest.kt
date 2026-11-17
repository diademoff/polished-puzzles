package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
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
    fun `a move keeps the drawstate and the trace has only redraw ops`() {
        val midend = Midend.fromGameId(
            BlitterGame(),
            "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15",
        )
        val sink = EventSink()

        val first = midend.ensureDrawState(48, sink)
        midend.recordRedraw(sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        sink.events.clear()

        midend.recordRedraw(sink)

        assertSame(first, midend.drawState, "an ordinary move keeps the cache, as C does")
        assertEquals(0, sink.events.count { it.startsWith("blitter_free") }, "no free after a move")
        assertEquals(0, sink.events.count { it.startsWith("blitter_new") }, "no new after a move")
        assertEquals("start_draw", sink.events.first())
        assertEquals("end_draw", sink.events.last())
        assertTrue(
            sink.events.contains("blitter_save:1:0:0") &&
                sink.events.contains("blitter_load:1:0:0"),
            "the redraw reuses blitter 1: ${sink.events}",
        )
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
        assertNotSame(first, second, "a tile size change replaces the cache")
        assertSame(second, midend.drawState)
    }

    @Test
    fun `a restart keeps the drawstate and the trace has no blitter ops`() {
        val midend = Midend.fromGameId(
            BlitterGame(),
            "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15",
        )
        val sink = EventSink()

        val first = midend.ensureDrawState(48, sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        midend.recordRedraw(sink)
        assertEquals(
            listOf("blitter_new:1:48:48"),
            sink.events.filter { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
            "a move keeps the cache",
        )

        assertTrue(midend.restart())
        sink.events.clear()
        midend.recordRedraw(sink)

        assertSame(
            first,
            midend.drawState,
            "a restart keeps the cache, as midend_restart_game reaches only midend_redraw",
        )
        assertEquals(
            emptyList<String>(),
            sink.events.filter { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
            "a restart frees nothing and allocates nothing",
        )
        assertEquals("start_draw", sink.events.first())
        assertEquals("end_draw", sink.events.last())
        assertTrue(
            sink.events.contains("blitter_save:1:0:0") &&
                sink.events.contains("blitter_load:1:0:0"),
            "the redraw reuses blitter 1: ${sink.events}",
        )
    }

    @Test
    fun `undo and redo keep the drawstate`() {
        val midend = Midend.fromGameId(
            BlitterGame(),
            "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15",
        )
        val sink = EventSink()

        val first = midend.ensureDrawState(48, sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        midend.recordRedraw(sink)
        sink.events.clear()

        assertTrue(midend.undo())
        midend.recordRedraw(sink)
        assertSame(first, midend.drawState, "an undo keeps the cache, as C does")

        assertTrue(midend.redo())
        midend.recordRedraw(sink)
        assertSame(first, midend.drawState, "a redo keeps the cache, as C does")

        assertEquals(
            emptyList<String>(),
            sink.events.filter { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
            "undo and redo neither free nor allocate: ${sink.events}",
        )
        assertEquals(2, sink.events.count { it == "start_draw" })
        assertTrue(sink.events.contains("blitter_load:1:0:0"))
    }

    @Test
    fun `solve keeps the drawstate`() {
        val midend = Midend.fromGameId(
            BlitterGame(),
            "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15",
        )
        val sink = EventSink()

        val first = midend.ensureDrawState(48, sink)
        midend.recordRedraw(sink)
        sink.events.clear()

        assertTrue(midend.solve())
        midend.recordRedraw(sink)

        assertSame(first, midend.drawState, "a solve enters a state and keeps the cache, as C does")
        assertEquals(
            emptyList<String>(),
            sink.events.filter { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
            "a solve neither frees nor allocates: ${sink.events}",
        )
        assertTrue(sink.events.contains("blitter_save:1:0:0"))
    }

    @Test
    fun `a different sink recreates the drawstate`() {
        val midend = Midend.newGame(BlitterGame(), "4x4", "12345")
        val firstSink = EventSink()
        val secondSink = EventSink()

        val first = midend.ensureDrawState(48, firstSink)
        assertEquals(listOf("blitter_new:1:48:48"), firstSink.events)

        val second = midend.ensureDrawState(48, secondSink)

        assertNotSame(first, second, "the cache belongs to the sink that allocated it")
        assertSame(second, midend.drawState)
        assertEquals(
            listOf("blitter_free:1"),
            firstSink.events.drop(1),
            "the old cache is freed through the sink that created it",
        )
        assertEquals(
            listOf("blitter_new:1:48:48"),
            secondSink.events,
            "a new trace starts with blitter_new and no stale free",
        )
    }

    @Test
    fun `forceRedraw recreates the drawstate at the same tile size`() {
        val midend = Midend.newGame(BlitterGame(), "4x4", "12345")
        val sink = EventSink()

        val first = midend.ensureDrawState(48, sink)
        assertEquals(listOf("blitter_new:1:48:48"), sink.events)

        midend.forceRedraw()
        val second = midend.ensureDrawState(48, sink)

        assertNotSame(first, second, "a forced redraw replaces the cache even at the same size")
        assertEquals(
            listOf(
                "blitter_new:1:48:48",
                "blitter_free:1",
                "blitter_new:2:48:48",
            ),
            sink.events,
            "the zoom size changed, so the old blitter cannot be reused",
        )
    }

    @Test
    fun `freeDrawState frees the blitter exactly once`() {
        val midend = Midend.newGame(BlitterGame(), "4x4", "12345")
        val sink = EventSink()

        midend.ensureDrawState(48, sink)
        midend.freeDrawState()
        midend.freeDrawState()

        assertEquals(
            listOf("blitter_new:1:48:48", "blitter_free:1"),
            sink.events,
            "the second free has no cache to release",
        )
        assertNull(midend.drawState)

        // A draw after the free creates a new cache.
        val again = midend.ensureDrawState(48, sink)
        assertSame(again, midend.drawState)
        assertEquals("blitter_new:2:48:48", sink.events.last())
    }

    @Test
    fun `freeDrawState does nothing when no drawstate was created`() {
        val midend = Midend.newGame(BlitterGame(), "4x4", "12345")
        val sink = EventSink()

        midend.freeDrawState()

        assertNull(midend.drawState)
        assertEquals(emptyList<String>(), sink.events)
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
        override fun inertiaFollow(isSolved: Boolean) {}

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
        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
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
