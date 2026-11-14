package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. API edges of the first ported game and midend. */
class FifteenTest {

    @Test
    fun `parameters decode, encode, and validate`() {
        assertEquals(Fifteen.Params(4, 4), Fifteen.decodeParams("4x4"))
        assertEquals(Fifteen.Params(5, 3), Fifteen.decodeParams("5x3"))
        assertEquals(Fifteen.Params(4, 4), Fifteen.decodeParams("4"))
        assertEquals("6x2", Fifteen.encodeParams(Fifteen.Params(6, 2), true))

        assertEquals(null, Fifteen.validateParams(Fifteen.Params(2, 2), true))
        assertEquals(
            "Width and height must both be at least two",
            Fifteen.validateParams(Fifteen.Params(1, 4), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Fifteen.validateParams(Fifteen.Params(100000, 100000), true),
        )
    }

    @Test
    fun `descriptions are validated`() {
        val params = Fifteen.Params(2, 2)
        assertEquals(null, Fifteen.validateDesc(params, "1,2,3,0"))
        assertEquals(
            "Number used twice",
            Fifteen.validateDesc(params, "1,1,3,0"),
        )
        assertEquals(
            "Number out of range",
            Fifteen.validateDesc(params, "1,2,4,0"),
        )
        assertEquals(
            "Expected comma after number",
            Fifteen.validateDesc(params, "1 2,3,0"),
        )
        assertEquals(
            "Excess junk at end of string",
            Fifteen.validateDesc(params, "1,2,3,0x"),
        )
        assertEquals(
            "Expected comma after number",
            Fifteen.validateDesc(params, "1,2,3"),
        )
    }

    @Test
    fun `a solved save round-trips through the midend`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val save = midend.serialise()
        val loaded = Midend.fromSave(Fifteen, save)
        assertEquals(midend.id(), loaded.id())
        assertEquals(save.serialise(), loaded.serialise().serialise())
        assertEquals(midend.state, loaded.state)
    }

    @Test
    fun `moves solve undo and redo`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val start = midend.state
        assertEquals(0, midend.status())
        assertFalse(midend.canUndo)

        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
        assertTrue(midend.undo())
        assertEquals(start, midend.state)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertEquals("Moves since auto-solve: 0", midend.statusText())
    }

    @Test
    fun `the hint solver finishes a game`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        var steps = 0
        val limit = 5 * 16 * 16 * 16
        while (midend.status() == 0 && steps < limit) {
            midend.processKey(0, 0, 'h'.code)
            steps++
        }
        assertEquals(1, midend.status())
        val solved = Fifteen.newState(
            Fifteen.Params(4, 4),
            "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,0",
        )
        assertEquals(Fifteen.textFormat(solved), midend.textFormat())
    }

    @Test
    fun `the shell solve and history keys work`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_SOLVE))
        assertEquals(1, midend.status())
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_UNDO))
        assertEquals(0, midend.status())
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_REDO))
        assertEquals(1, midend.status())
    }

    @Test
    fun `invalid moves are rejected`() {
        val state = Fifteen.newState(Fifteen.Params(4, 4), "1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15")
        assertNull(Fifteen.executeMove(state, "M4,4"))
        assertNull(Fifteen.executeMove(state, "M1,1"))
        assertNull(Fifteen.executeMove(state, "not a move"))
        assertNotNull(Fifteen.executeMove(state, "M3,3"))
    }

    @Test
    fun `the text format is a right-aligned grid`() {
        val state = Fifteen.newState(Fifteen.Params(2, 2), "1,2,3,0")
        assertEquals("1 2\n3  \n", Fifteen.textFormat(state))
    }

    @Test
    fun `the palette matches the upstream grey board`() {
        val colours = Fifteen.colours()
        assertEquals(12, colours.size)
        assertEquals(0.8f, colours[0])
        assertEquals(0.0f, colours[3])
        assertEquals(967, (colours[6] * 1000.0f).roundToInt())
        assertEquals(633, (colours[9] * 1000.0f).roundToInt())
    }

    @Test
    fun `an invalid save is rejected`() {
        val records = listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Fifteen"),
            SaveRecord("PARAMS", "1x1"),
            SaveRecord("CPARAMS", "1x1"),
            SaveRecord("DESC", "0"),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        assertFailsWith<IllegalStateException> {
            Midend.fromSave(Fifteen, SgtpSave.of(records))
        }
    }
}
