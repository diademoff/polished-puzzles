package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: strict. Game: rect, engine side.
 *
 * The parameter grammar, the validation order, the text form, the
 * correctness map, and the solver's stored answer. The oracle compares
 * the descriptions and the frames; these tests pin the internal rules.
 */
class RectTest {

    private val presets = Rect.presets().map { it.params }

    @Test
    fun `the preset table and defaults are the C game`() {
        assertEquals(listOf("7x7", "9x9", "11x11", "13x13", "15x15"), presets)
        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.defaultParams())
        assertEquals("7x7", Rect.encodeParams(Rect.defaultParams(), true))
        assertEquals("7x7", Rect.encodeParams(Rect.defaultParams(), false))
        assertEquals("7x7e0.5", Rect.encodeParams(Rect.Params(7, 7, 0.5f, true), true))
        assertEquals("7x7", Rect.encodeParams(Rect.Params(7, 7, 0.5f, true), false))
        assertEquals("7x7a", Rect.encodeParams(Rect.Params(7, 7, 0.0f, false), true))
        assertEquals("7x7e1a", Rect.encodeParams(Rect.Params(7, 7, 1.0f, false), true))
    }

    @Test
    fun `the parameter grammar matches the C quirks`() {
        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.decodeParams("7x7"))
        assertEquals(Rect.Params(7, 7, 0.0f, false), Rect.decodeParams("7x7a"))
        assertEquals(Rect.Params(7, 7, 0.5f, true), Rect.decodeParams("7x7e0.5"))
        assertEquals(Rect.Params(7, 7, 1.0f, true), Rect.decodeParams("7x7e1"))
        assertEquals(Rect.Params(7, 7, -1.0f, true), Rect.decodeParams("7x7e-1"))
        assertEquals(Rect.Params(7, 7, 1.0f, false), Rect.decodeParams("7x7e1a"))
        assertEquals(Rect.Params(7, 7, 0.5f, false), Rect.decodeParams("7x7e.5a"))
        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.decodeParams("007x7"))
        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.decodeParams("+7x+7"))
        assertEquals(Rect.Params(0, 0, 0.0f, true), Rect.decodeParams("zzz"))
        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.decodeParams("7X7"))
        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.decodeParams("7x7z"))
        // `strtod` reads an exponent but the C skip loop stops at `e`,
        // so the rest of the text is not part of the grammar.
        assertEquals(Rect.Params(7, 7, 100.0f, true), Rect.decodeParams("7x7e1e2"))
        // A huge exponent inside the number saturates to infinity.
        assertTrue((Rect.decodeParams("7x7e1e999") as Rect.Params).expandFactor.isInfinite())
        assertEquals("7x7einf", Rect.encodeParams(Rect.decodeParams("7x7e1e999"), true))
    }

    @Test
    fun `validation follows the C order`() {
        fun check(w: Int, h: Int, e: Float): String? =
            Rect.validateParams(Rect.Params(w, h, e, true), true)

        assertNull(check(7, 7, 0.0f))
        assertNull(check(2, 1, 0.0f))
        assertNull(check(1, 2, 10.0f))
        assertEquals(
            "Width and height must both be greater than zero",
            check(0, 7, 0.0f),
        )
        assertEquals(
            "Width and height must both be greater than zero",
            check(7, -1, 0.0f),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            check(46341, 46341, 0.0f),
        )
        assertEquals(
            "Grid area must be greater than one",
            check(1, 1, 0.0f),
        )
        assertEquals(
            "Expansion factor may not be negative",
            check(7, 7, -0.1f),
        )
    }

    @Test
    fun `the description grammar matches the C parser`() {
        val p = Rect.decodeParams("7x7")
        assertEquals(
            "Invalid character in game description",
            Rect.validateDesc(p, "!"),
        )
        assertEquals(
            "Not enough data to fill grid",
            Rect.validateDesc(p, "a"),
        )
        assertEquals(
            "Too much data to fit in grid",
            Rect.validateDesc(p, "zz"),
        )
        assertNull(Rect.validateDesc(p, "3_2a2_3g2f5_2a6a3_3b2_2d4b3_2c5f"))

        assertFailsWith<IllegalArgumentException> { Rect.newState(p, "!") }
        assertFailsWith<IllegalArgumentException> { Rect.newState(p, "a") }

        // The smallest board, and the text form with its two-column
        // numbers and its boundary corners.
        val tinyParams = Rect.decodeParams("2x1")
        val tiny = Rect.newState(tinyParams, "1a")
        assertEquals(1, (tiny as Rect.State).grid[0])
        assertEquals("+-----+\n| 1   |\n+-----+\n", Rect.textFormat(tiny))
    }

    @Test
    fun `the correctness map and the S move`() {
        val midend = Midend.newGame(Rect, "7x7", "12345")
        val state = midend.state as Rect.State
        assertFalse(state.completed)
        // An empty edge grid is one whole-board rectangle; it is correct
        // only if that rectangle holds exactly one number equal to 49.
        assertTrue(state.correct.all { it == 0 })

        val aux = assertNotNull(midend.auxInfo)
        val solved = Rect.executeMove(state, aux) as Rect.State
        assertTrue(solved.vedge.any { it != 0 }, "the answer sets every edge")
        assertTrue(solved.correct.all { it != 0 }, "every cell is correct")
        // C's S branch returns before its completion check.
        assertFalse(solved.completed, "the S move is not a completion")
        assertTrue(solved.cheated, "the S move is cheated")
        assertEquals(0, Rect.status(solved))
        assertEquals("Auto-solved.", Rect.statusText(solved, Rect.newUi(solved)))
    }

    @Test
    fun `the solver reproduces the stored answer`() {
        for (params in presets) {
            val midend = Midend.newGame(Rect, params, "12345")
            val aux = assertNotNull(midend.auxInfo)
            assertEquals(aux, Rect.solve(midend.state, null), "$params aux")
            assertEquals(aux, Rect.solve(midend.state, aux), "$params aux passthrough")
        }
    }

    @Test
    fun `an expansion that empties a base side is refused`() {
        // C accepts these parameters and then aborts in the generator
        // with "allocation too large"; the port defines the failure.
        for (params in listOf("1x7e5", "7x1e5", "2x1e0.5")) {
            val p = Rect.decodeParams(params) as Rect.Params
            assertNull(Rect.validateParams(p, true), "$params validates as C")
            assertFailsWith<IllegalArgumentException>("$params generation") {
                Rect.newDesc(p, RandomState.fromSeed("12345"))
            }
        }
        // The same sides with no expansion generate normally.
        for (params in listOf("1x7", "7x1")) {
            val p = Rect.decodeParams(params) as Rect.Params
            assertTrue(Rect.newDesc(p, RandomState.fromSeed("12345")).text.isNotEmpty())
        }
    }

    @Test
    fun `the UI defaults are reset`() {
        val midend = Midend.newGame(Rect, "7x7", "12345")
        val ui = midend.ui as Rect.Ui
        assertEquals(-1, ui.dragStartX)
        assertEquals(-1, ui.dragStartY)
        assertEquals(-1, ui.x1)
        assertEquals(-1, ui.y2)
        assertFalse(ui.dragged)
        assertFalse(ui.erasing)
        assertFalse(ui.curVisible)
        assertFalse(ui.curDragging)
        assertNull(Rect.cursorLocation(ui, midend.state, 24))
        ui.curVisible = true
        ui.curX = 2
        ui.curY = 3
        assertEquals(
            Game.CursorRect(2 + 2 * 24, 2 + 3 * 24, 24, 24),
            Rect.cursorLocation(ui, midend.state, 24),
        )
    }
}
