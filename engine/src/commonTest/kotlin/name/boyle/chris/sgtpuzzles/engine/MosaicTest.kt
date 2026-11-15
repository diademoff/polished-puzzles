package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine tests for the mosaic port.
 *
 * Most of the game is compared with the oracle in the `:parity` module.
 * This suite pins the parts that have no seam method: the generator
 * attempt counts, the encoder's unreachable 27-cell run, the guard for a
 * description outside the board, the current key label, and the raw
 * `last_state` value that a hand-written move can store.
 */
class MosaicTest {

    @Test
    fun `the default parameters and presets match C`() {
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.defaultParams())
        assertEquals(
            listOf("Size: 3x3", "Size: 5x5", "Size: 10x10", "Size: 15x15", "Size: 25x25", "Size: 50x50"),
            Mosaic.presets().map { it.title },
        )
        assertEquals(
            listOf("3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0"),
            Mosaic.presets().map { it.params },
        )
        assertTrue((Mosaic.presets()[0].params != Mosaic.presets()[5].params))
    }

    @Test
    fun `decode starts aggressive and reads only a lower case x and h`() {
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10x10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10X10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("+10x10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams(" 10x10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("010x010"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10x10x7"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10x10h1"))
        assertEquals(Mosaic.Params(10, 10, false), Mosaic.decodeParams("10x10h0"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10x10h2"))
        assertEquals(Mosaic.Params(10, 10, false), Mosaic.decodeParams("10x10h"))
        assertEquals(Mosaic.Params(10, 10, false), Mosaic.decodeParams("10x10habc"))
        assertEquals(Mosaic.Params(10, 0, true), Mosaic.decodeParams("10x"))
    }

    @Test
    fun `only a full encoding writes h0`() {
        val aggressive = Mosaic.Params(8, 9, true)
        val calm = Mosaic.Params(8, 9, false)
        assertEquals("8x9", Mosaic.encodeParams(aggressive, true))
        assertEquals("8x9", Mosaic.encodeParams(aggressive, false))
        assertEquals("8x9h0", Mosaic.encodeParams(calm, true))
        assertEquals("8x9", Mosaic.encodeParams(calm, false))
    }

    @Test
    fun `validation uses the C order and boundary`() {
        assertEquals("Minimal size is 3x3", Mosaic.validateParams(Mosaic.Params(2, 3, true), true))
        assertEquals("Minimal size is 3x3", Mosaic.validateParams(Mosaic.Params(3, 2, true), true))
        assertEquals("Minimal size is 3x3", Mosaic.validateParams(Mosaic.Params(0, 0, true), true))
        assertEquals("Minimal size is 3x3", Mosaic.validateParams(Mosaic.Params(-3, 5, true), true))
        assertEquals(
            "Maximum size is 10000 tiles",
            Mosaic.validateParams(Mosaic.Params(101, 100, true), true),
        )
        assertEquals(
            "Maximum size is 10000 tiles",
            Mosaic.validateParams(Mosaic.Params(46340, 4, true), true),
        )
        assertNull(Mosaic.validateParams(Mosaic.Params(100, 100, true), true))
        assertNull(Mosaic.validateParams(Mosaic.Params(3, 3333, true), true))
        assertEquals(
            "Maximum size is 10000 tiles",
            Mosaic.validateParams(Mosaic.Params(3, 3334, true), true),
        )
        assertEquals("Minimal size is 3x3", Mosaic.validateParams(Mosaic.Params(10000, 1, true), true))
    }

    @Test
    fun `validate desc counts letters as runs`() {
        val three = Mosaic.decodeParams("3x3")
        assertNull(Mosaic.validateDesc(three, "4b47d"))
        assertNull(Mosaic.validateDesc(three, "i"))
        assertEquals(
            "Invalid character in game description",
            Mosaic.validateDesc(three, "4b47 "),
        )
        assertEquals(
            "Invalid character in game description",
            Mosaic.validateDesc(three, "4b47{"),
        )
        assertEquals(
            "Invalid character in game description",
            Mosaic.validateDesc(three, "4B47d"),
        )
        assertEquals(
            "Invalid character in game description",
            Mosaic.validateDesc(three, "4b4,7d"),
        )
        assertEquals("Desc size mismatch", Mosaic.validateDesc(three, ""))
        assertEquals("Desc size mismatch", Mosaic.validateDesc(three, "i4"))
        assertEquals("Desc size mismatch", Mosaic.validateDesc(three, "4b47dd"))
        assertNull(Mosaic.validateDesc(Mosaic.decodeParams("5x5"), "x4"))
        assertEquals(
            "Desc size mismatch",
            Mosaic.validateDesc(three, "z"),
        )
        assertEquals(
            "Desc size mismatch",
            Mosaic.validateDesc(three, "ia"),
        )
    }

    @Test
    fun `new state starts open with every clue unsolved`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        assertEquals(3, state.notCompletedClues)
        assertFalse(state.cheating)
        assertEquals(0, Mosaic.status(state))
        assertEquals(0, state.cells.count { it != 0 })
        assertEquals(listOf(4, -1, -1, 4, 7, -1, -1, -1, -1), state.shared.clue.toList())
        assertEquals(
            listOf(true, false, false, true, true, false, false, false, false),
            state.shared.shown.toList(),
        )

        // A description with no clues is completed at load.
        val open = Mosaic.newState(Mosaic.decodeParams("3x3"), "i") as Mosaic.State
        assertEquals(0, open.notCompletedClues)
        assertEquals(1, Mosaic.status(open))
        assertEquals("COMPLETED!", Mosaic.statusText(open, Mosaic.newUi(open)))

        // A description that is already a full solution is still open.
        val solved = Mosaic.newState(Mosaic.decodeParams("3x3"), "444444444") as Mosaic.State
        assertEquals(9, solved.notCompletedClues)
        assertEquals(0, Mosaic.status(solved))
        assertTrue(solved.cells.all { it and Mosaic.STATE_ERROR == 0 })
    }

    @Test
    fun `an out of range description throws instead of writing past the board`() {
        val three = Mosaic.decodeParams("3x3")
        assertFailsWith<IllegalArgumentException> { Mosaic.newState(three, "i9") }
        assertFailsWith<IllegalArgumentException> { Mosaic.newState(three, "4b4") }
        assertFailsWith<IllegalArgumentException> { Mosaic.newState(three, "z9") }
        // A literal space is one hidden cell in C; it is only reachable
        // through a direct call because validate_desc rejects it.
        assertFailsWith<IllegalArgumentException> { Mosaic.newState(three, "4b47 ") }
    }

    @Test
    fun `the encoder matches the C run arithmetic including the 27 defect`() {
        for (run in 1..26) {
            val shown = BooleanArray(run + 2).also {
                it[0] = true
                it[run + 1] = true
            }
            val clues = IntArray(run + 2).also {
                it[0] = 1
                it[run + 1] = 2
            }
            assertEquals("1${'a' + run - 1}2", MosaicSolver.encodeForTest(shown, clues))
        }

        fun encodeRun(run: Int): String {
            val shown = BooleanArray(run + 2).also {
                it[0] = true
                it[run + 1] = true
            }
            val clues = IntArray(run + 2).also {
                it[0] = 1
                it[run + 1] = 2
            }
            return MosaicSolver.encodeForTest(shown, clues)
        }
        // `space_count <= 'z'` lets a 27th hidden cell store 123. Both
        // 27 and 28 produce "1{2"; the 28th cell is dropped, so the
        // description is invalid and does not cover the board.
        assertEquals("1{2", encodeRun(27))
        assertEquals("1{2", encodeRun(28))
        assertEquals("1{a2", encodeRun(29))
    }

    @Test
    fun `the retry fixtures take the C number of attempts`() {
        fun generated(params: String, seed: String): MosaicSolver.Generated =
            Mosaic.newDescWithAttempts(Mosaic.decodeParams(params), RandomState.fromSeed(seed))

        generated("3x3", "12345").let {
            assertEquals("4b47d", it.desc)
            assertEquals(21, it.startPointRetries)
            assertEquals(2, it.acceptanceRetries)
            assertEquals(24, it.attempts)
        }
        generated("3x3", "1").let {
            assertEquals("b202d", it.desc)
            assertEquals(0, it.startPointRetries)
            assertEquals(2, it.acceptanceRetries)
        }
        generated("3x3", "2").let {
            assertEquals("02b4c3", it.desc)
            assertEquals(7, it.startPointRetries)
            assertEquals(0, it.acceptanceRetries)
        }
        generated("5x5", "12345").let {
            assertEquals("c323f6546a6a4a4b4", it.desc)
            assertEquals(4, it.startPointRetries)
            assertEquals(0, it.acceptanceRetries)
        }
        generated("5x5", "2").let {
            assertEquals("a34a2h663a4e5a", it.desc)
            assertEquals(23, it.startPointRetries)
            assertEquals(7, it.acceptanceRetries)
        }
        generated("10x10", "12345").let {
            assertEquals(
                "2b5a3c3g4c664a2c4b4a53a4b2a55c33a1a4a5a3b2a3a5a2122a3c23a2a0c2d1a2c11b0a",
                it.desc,
            )
            assertEquals(2, it.startPointRetries)
            assertEquals(2, it.acceptanceRetries)
        }
        generated("10x10", "1").let {
            assertEquals(
                "2d6a2d4b6c31a2b42b5c312a3b3c3a2a6c433234b3a6a4b58c53a465e4a4d3a3e5a",
                it.desc,
            )
            assertEquals(18, it.startPointRetries)
            assertEquals(28, it.acceptanceRetries)
        }
        generated("10x10", "3").let {
            assertEquals(
                "a6d3d66b76a5a2a6a66a5b2a4a7a6c3a666c5a5a55b64a235a6a6b6a4a33b7a5f5a5a4a321f",
                it.desc,
            )
            assertEquals(0, it.startPointRetries)
            assertEquals(0, it.acceptanceRetries)
        }
    }

    @Test
    fun `the aggressive hide changes a board only when a clue is hidden`() {
        // 3x3 seed 12345 hides a needed clue; seeds 1-3 hide none.
        assertEquals("4b47d", Mosaic.newDescWithAttempts(
            Mosaic.decodeParams("3x3"), RandomState.fromSeed("12345"),
        ).desc)
        assertEquals("4a447d", Mosaic.newDescWithAttempts(
            Mosaic.decodeParams("3x3h0"), RandomState.fromSeed("12345"),
        ).desc)
        for (seed in listOf("1", "2", "3")) {
            assertEquals(
                Mosaic.newDescWithAttempts(Mosaic.decodeParams("3x3"), RandomState.fromSeed(seed)).desc,
                Mosaic.newDescWithAttempts(Mosaic.decodeParams("3x3h0"), RandomState.fromSeed(seed)).desc,
            )
        }
    }

    @Test
    fun `the solver packs the answer MSB first`() {
        assertEquals("sfc80", Mosaic.solve(Midend.fromGameId(Mosaic, "3x3:4b47d").state))
        assertEquals("s22f9bd80", Mosaic.solve(Midend.fromGameId(Mosaic, "5x5:c323f6546a6a4a4b4").state))
        // Nine blank cells pack into two bytes.
        assertEquals("s0000", Mosaic.solve(Midend.fromGameId(Mosaic, "3x3:000000000").state))
    }

    @Test
    fun `an inconsistent board returns no answer`() {
        val midend = Midend.fromGameId(Mosaic, "3x3:444444444")
        assertNull(Mosaic.solve(midend.state))
    }

    @Test
    fun `a solve move sets the counter without touching the clue flags`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        val move = Mosaic.solve(state)!!
        val solved = Mosaic.executeMove(state, move) as Mosaic.State
        assertEquals(0, solved.notCompletedClues)
        assertTrue(solved.cheating)
        assertEquals(1, Mosaic.status(solved))
        assertEquals("Auto solved", Mosaic.statusText(solved, Mosaic.newUi(solved)))
        // The `s` cell values are MARKED|SOLVED and BLANK|SOLVED; the
        // clue flags were never refreshed.
        assertTrue(solved.cells.all {
            it == Mosaic.STATE_MARKED_SOLVED || it == Mosaic.STATE_BLANK_SOLVED
        })
        assertFalse(Mosaic.changedState(Mosaic.newUi(solved), state, solved))

        // A later move recounts and reopens the status, as C does.
        val after = Mosaic.executeMove(solved, "t0,0") as Mosaic.State
        assertEquals(3, after.notCompletedClues)
        assertEquals("Clues left: 3", Mosaic.statusText(after, Mosaic.newUi(after)))
    }

    @Test
    fun `a raw last state is stored and truncated to a signed byte`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        val seven = Mosaic.executeMove(state, "d1,0,0,0,7") as Mosaic.State
        assertEquals(7, seven.cells[1])
        val minusOne = Mosaic.executeMove(state, "d1,0,0,0,4294967295") as Mosaic.State
        assertEquals(-1, minusOne.cells[1])
        // 4294967296 truncates to 0, and a zero value paints nothing but
        // the recount still runs.
        val zero = Mosaic.executeMove(state, "d1,0,0,0,4294967296") as Mosaic.State
        assertEquals(0, zero.cells[1])
    }

    @Test
    fun `a t or T coordinate of minus one returns an unchanged copy`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        for (move in listOf("t4294967295,0", "t0,4294967295", "T4294967295,0")) {
            val result = Mosaic.executeMove(state, move) as Mosaic.State
            assertEquals(state, result, "$move copies the state")
        }
    }

    @Test
    fun `an unknown or empty move is an accepted copy`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        for (move in listOf("", "X", "y1,0", "q9,9")) {
            assertEquals(state, Mosaic.executeMove(state, move), "'$move' copies the state")
        }
    }

    @Test
    fun `the reject list follows the loose parser`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        for (move in listOf("t 1,0", "t+1,0", "tt", "t-1,-1", "t9,9", "d9,9,0,0,1", "d1,0,9,0,1")) {
            assertNull(Mosaic.executeMove(state, move), move)
        }
        for (move in listOf("t1,0junk", "t1,0,3", "t1,0;", "t,0", "t,", "t1,", "t1,+0", "t1, 0", "d1,0,0,0,1")) {
            assertTrue(Mosaic.executeMove(state, move) != null, "'$move' is accepted")
        }
    }

    @Test
    fun `the current key label follows the value bits`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        val ui = Mosaic.newUi(state) as Mosaic.Ui
        assertEquals("", Mosaic.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Mosaic.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        assertEquals("", Mosaic.currentKeyLabel(ui, state, 'x'.code))

        ui.curVisible = true
        assertEquals("Black", Mosaic.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("White", Mosaic.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        var next = Mosaic.executeMove(state, "t0,0") as Mosaic.State
        assertEquals("White", Mosaic.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT))
        assertEquals("Empty", Mosaic.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT2))

        next = Mosaic.executeMove(next, "t0,0") as Mosaic.State
        assertEquals("Empty", Mosaic.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT))
        assertEquals("Black", Mosaic.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT2))

        // A flag does not change the label.
        assertEquals(
            "Empty",
            Mosaic.currentKeyLabel(ui, next.copy() as Mosaic.State, Buttons.CURSOR_SELECT),
        )

        // An out-of-grid cursor returns the empty label instead of
        // dereferencing null as C does.
        ui.curX = 7
        assertEquals("", Mosaic.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        // A completed board has no label.
        ui.curX = 0
        val done = Mosaic.newState(Mosaic.decodeParams("3x3"), "i") as Mosaic.State
        assertEquals("", Mosaic.currentKeyLabel(ui, done, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `a fresh ui and drawstate carry the C values`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        val ui = Mosaic.newUi(state) as Mosaic.Ui
        assertEquals(-1, ui.lastX)
        assertEquals(-1, ui.lastY)
        assertEquals(0, ui.lastState)
        assertEquals(0, ui.curX)
        assertEquals(0, ui.curY)
        assertFalse(ui.curVisible)

        val drawState = Mosaic.DrawState(3, 3, 32)
        assertEquals(16, drawState.cells.size)
        assertTrue(drawState.cells.all { it == -1 })
    }

    @Test
    fun `a copy carries the cells and the clues are shared`() {
        val state = Mosaic.newState(Mosaic.decodeParams("3x3"), "4b47d") as Mosaic.State
        val copy = state.copy() as Mosaic.State
        assertEquals(state, copy, "a copy is equal")
        assertTrue(state.shared === copy.shared, "a copy shares the board")
        val moved = Mosaic.executeMove(state, "t0,0") as Mosaic.State
        assertNotEquals(state, moved, "a move changes the state")
        assertEquals(Mosaic.STATE_MARKED, moved.cells[0] and Mosaic.STATE_OK_NUM)
        assertEquals(0, state.cells[0], "the original is not changed")
    }

    @Test
    fun `the palette has eight fixed roles and a shell background`() {
        val colours = Mosaic.colours()
        assertEquals(24, colours.size)
        assertEquals(0.8f, colours[0])
        assertEquals(148f / 255f, colours[3])
        assertEquals(196f / 255f, colours[4])
        assertEquals(190f / 255f, colours[5])
        assertEquals(1f, colours[18])
        assertEquals(255f / 255f, colours[21])
        assertEquals(200f / 255f, colours[22])
        assertEquals(200f / 255f, colours[23])

        val other = Mosaic.colours(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(0.1f, other[0])
        assertEquals(0.2f, other[1])
        assertEquals(0.3f, other[2])
        assertEquals(colours[3], other[3])
    }
}
