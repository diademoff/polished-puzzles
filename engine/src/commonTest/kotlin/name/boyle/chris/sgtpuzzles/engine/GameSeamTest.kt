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

    @Test
    fun `an empty seed is a zero-length SEED record`() {
        val midend = Midend.newGame(Fifteen, "4x4", "")
        val text = midend.serialise().serialise()

        assertTrue(
            text.contains("SEED    :0:\n"),
            "an empty seed must be a zero-length SEED record: $text",
        )
        assertFalse(
            text.contains("HEXSEED"),
            "an empty seed is printable ASCII, so it is not HEXSEED: $text",
        )

        val reloaded = Midend.fromSave(Fifteen, SgtpSave.parse(text))
        assertEquals("", reloaded.seed)
        assertEquals(text, reloaded.serialise().serialise())
    }

    @Test
    fun `a non-printable seed is hex-encoded and round-trips`() {
        val seed = "\u0001\u0002"
        val save = Midend.newGame(Fifteen, "4x4", seed).serialise()

        assertEquals(null, save.value("SEED"))
        assertEquals("0102", save.value("HEXSEED"))

        val reloaded = Midend.fromSave(Fifteen, save)
        assertEquals(seed, reloaded.seed)
        assertEquals(save, reloaded.serialise())
    }

    private class StatusSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        val statuses = ArrayList<String>()
        var drew = false

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            drew = true
        }
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
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {}
        override fun status(text: String) {
            statuses += text
        }
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
