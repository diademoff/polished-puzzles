package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Level: semantic. Seam operations that the ported games share. */
class GameSeamTest {

    @Test
    fun `the ported games can solve`() {
        assertTrue(Fifteen.canSolve)
        assertTrue(Slant.canSolve)
    }

    @Test
    fun `slant has no status text and no status bar`() {
        assertFalse(Slant.wantsStatusBar)
        val midend = Midend.newGame(Slant, "", "12345")
        assertEquals("", Slant.statusText(midend.state, midend.ui))
        assertEquals("", midend.statusText())

        val sink = StatusSink()
        midend.recordRedraw(sink)
        assertTrue(sink.statuses.isEmpty())
        assertTrue(sink.drew, "the midend must still draw the board")
    }

    private class StatusSink : DrawSink {
        val statuses = ArrayList<String>()
        var drew = false

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            drew = true
        }
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {}
        override fun status(text: String) {
            statuses += text
        }
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
    }
}
