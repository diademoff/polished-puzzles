package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. `midend_redraw` clears the window with colour 0 and
 * updates the whole area only on the first draw of a drawstate.
 *
 * The front end does not guarantee the initial contents of the window,
 * so the first draw covers it. Every later draw covers it too, with the
 * game's own operations, and the midend adds neither operation.
 */
class FirstDrawTest {

    @Test
    fun `a new game clears and updates the whole window once`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val sink = OpSink()
        val size = midend.size
        val clear = "rect:0:0:${size.width}:${size.height}:0"
        val update = "update:0:0:${size.width}:${size.height}"

        midend.recordNewGameTrace(sink)

        assertEquals("changed_state", sink.ops.first(), "a new game reports the history first")
        assertEquals("start_draw", sink.ops[1])
        assertEquals(clear, sink.ops[2], "the first draw clears the window: ${sink.ops}")
        assertEquals(1, sink.ops.count { it == clear }, "one clear: ${sink.ops}")
        assertEquals(1, sink.ops.count { it == update }, "one whole-area update: ${sink.ops}")
        assertEquals(update, sink.ops[sink.ops.size - 2])
        assertEquals("end_draw", sink.ops.last())
        assertEquals("status", sink.ops[sink.ops.size - 3])
        assertTrue(
            sink.ops.subList(3, sink.ops.size - 3).isNotEmpty(),
            "the game draws between the clear and the status: ${sink.ops}",
        )
    }

    @Test
    fun `a move redraw has no whole-window clear and no whole-area update`() {
        val midend = Midend.fromGameId(Fifteen, GAME_ID)
        val sink = OpSink()
        val size = midend.size
        val clear = "rect:0:0:${size.width}:${size.height}:0"
        val update = "update:0:0:${size.width}:${size.height}"

        midend.recordNewGameTrace(sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        sink.ops.clear()

        midend.recordRedraw(sink)

        assertEquals("start_draw", sink.ops.first())
        assertEquals("end_draw", sink.ops.last())
        assertFalse(sink.ops.contains(clear), "a move keeps the window: ${sink.ops}")
        assertFalse(sink.ops.contains(update), "the game updates its own area: ${sink.ops}")
        assertTrue(sink.ops.size > 2, "the game must draw its operations: ${sink.ops}")
        assertTrue(
            sink.ops.any { it.startsWith("update:") },
            "fifteen updates each tile: ${sink.ops}",
        )
    }

    @Test
    fun `forceRedraw restores the first-draw clear`() {
        val midend = Midend.fromGameId(Fifteen, GAME_ID)
        val sink = OpSink()
        val size = midend.size
        val clear = "rect:0:0:${size.width}:${size.height}:0"
        val update = "update:0:0:${size.width}:${size.height}"

        midend.recordNewGameTrace(sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        midend.recordRedraw(sink)
        sink.ops.clear()

        midend.takeFirstDraw()
        midend.forceRedraw()
        assertTrue(midend.takeFirstDraw(), "forceRedraw sets the first-draw flag")
        midend.recordRedraw(sink)

        assertTrue(sink.ops.contains(clear), "a forced redraw clears the window: ${sink.ops}")
        assertTrue(sink.ops.contains(update), "a forced redraw updates the whole area: ${sink.ops}")
    }

    @Test
    fun `a drawstate kept across restart does not clear`() {
        val midend = Midend.fromGameId(Fifteen, GAME_ID)
        val sink = OpSink()
        val size = midend.size
        val clear = "rect:0:0:${size.width}:${size.height}:0"
        val update = "update:0:0:${size.width}:${size.height}"

        midend.recordNewGameTrace(sink)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48))
        midend.recordRedraw(sink)
        assertTrue(midend.restart())
        sink.ops.clear()

        midend.recordRedraw(sink)

        assertFalse(sink.ops.contains(clear), "a restart keeps the drawstate: ${sink.ops}")
        assertFalse(sink.ops.contains(update), "a restart keeps the drawstate: ${sink.ops}")
        assertEquals("start_draw", sink.ops.first())
        assertEquals("end_draw", sink.ops.last())
    }

    private companion object {
        const val GAME_ID = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
    }

    /** Records every operation with its geometry, in call order. */
    private class OpSink : DrawSink {
        val ops = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state"
        }

        override fun startDraw() {
            ops += "start_draw"
        }

        override fun endDraw() {
            ops += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect:$x:$y:$width:$height:$colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle"
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly"
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
            ops += "text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update:$x:$y:$width:$height"
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun blitterNew(width: Int, height: Int): Int = 1

        override fun blitterFree(id: Int) {}

        override fun blitterSave(id: Int, x: Int, y: Int) {}

        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
