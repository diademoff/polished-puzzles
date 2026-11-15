package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: netslide.
 *
 * The parts of the port that do not need the oracle: the slide
 * arithmetic, the loose move grammar, the `S` move, reachability, the
 * corner flags, the status strings, the cursor ring, and the seam flags.
 * The parity tests compare the same behavior with the C oracle.
 */
class NetslideTest {

    @Test
    fun `copy is deep`() {
        val state = state("3x3", "21c4d5963")
        val copy = state.copy() as Netslide.State
        assertEquals(state, copy)
        copy.tiles[0] = 0x0F
        copy.barriers[0] = 0xFF
        assertNotEquals(state.tiles[0], copy.tiles[0])
        assertNotEquals(state.barriers[0], copy.barriers[0])
        assertEquals(2, state.tiles[0])
    }

    @Test
    fun `a row slide moves the contents opposite to the direction`() {
        // Row 0 of this description is 1,2,3; row 1 is 4,5,6; row 2 is
        // 7,8,9. The tiles are hex digits, so `a` is ten and so on.
        val base = state("3x3", "123456789")

        assertRow(execute(base, "R0,1"), intArrayOf(2, 3, 1))
        assertRow(execute(base, "R0,-1"), intArrayOf(3, 1, 2))
        assertRow(execute(base, "R0,3"), intArrayOf(1, 2, 3))
        assertRow(execute(base, "R0,-3"), intArrayOf(1, 2, 3))
        // A step of two is a scramble, not a rotation: the middle cell
        // is written before it is read.
        assertRow(execute(base, "R0,2"), intArrayOf(3, 1, 2))
        assertRow(execute(base, "R0,-2"), intArrayOf(2, 3, 1))
    }

    @Test
    fun `a column slide moves the contents opposite to the direction`() {
        val base = state("3x3", "123456789")

        assertColumn(execute(base, "C0,1"), intArrayOf(4, 7, 1))
        assertColumn(execute(base, "C0,-1"), intArrayOf(7, 1, 4))
        assertColumn(execute(base, "C0,3"), intArrayOf(1, 4, 7))
        assertColumn(execute(base, "C0,-3"), intArrayOf(1, 4, 7))
        assertColumn(execute(base, "C0,2"), intArrayOf(7, 1, 4))
        assertColumn(execute(base, "C0,-2"), intArrayOf(4, 7, 1))
    }

    @Test
    fun `the move grammar is the loose sscanf grammar`() {
        val state = state("3x3", "21c4d5963")
        for (move in listOf(
            "R0,1",
            "R0,1junk",
            "R0, 1",
            "R+0,+1",
            "R00,01",
            "R0,1 ",
            "C0,1",
            "C0, 1",
            "C+0,+1",
            "R0,3",
            "R0,-3",
            "C0,3",
            "C0,-3",
        )) {
            assertNotEquals(null, Netslide.executeMove(state, move), "accepts '$move'")
        }

        for (move in listOf(
            "R0,4",
            "R0,-4",
            "C0,4",
            "C0,-4",
            "R0,0",
            "R3,1",
            "C3,1",
            "R0",
            "R0,",
            "X0,1",
            "S",
            "",
            "R-1,1",
        )) {
            assertNull(Netslide.executeMove(state, move), "rejects '$move'")
        }
    }

    @Test
    fun `a full cycle counts a move and can complete the game`() {
        val solved = state("3x3", "15c9d6234")
        assertEquals(0, Netslide.status(solved))
        val noop = execute(solved, "R0,3")
        assertEquals(solved.tiles.toList(), noop.tiles.toList())
        assertEquals(1, noop.moveCount)
        assertEquals(1, noop.completed)
        assertEquals(1, Netslide.status(noop))
        assertEquals("COMPLETED! Moves: 1 Active: 9/9", statusText(noop))
    }

    @Test
    fun `the S move installs the given board and marks it solved`() {
        val state = state("3x3", "21c4d5963")
        val solved = execute(state, "S15c9d6234")
        assertEquals(1, solved.completed)
        assertEquals(1, solved.moveCount)
        assertTrue(solved.usedSolve)
        assertEquals(1, Netslide.status(solved))
        assertEquals("Moves since auto-solve: 0 Active: 9/9", statusText(solved))

        // No board validation: a blank board is accepted and counts 1/9.
        val blank = execute(state, "S000000000")
        assertEquals(1, Netslide.status(blank))
        assertEquals("Moves since auto-solve: 0 Active: 1/9", statusText(blank))

        // The length and every digit are checked.
        assertNull(Netslide.executeMove(state, "S15c9d623"))
        assertNull(Netslide.executeMove(state, "S15c9d62344"))
        assertNull(Netslide.executeMove(state, "S15c9d623g"))
        // Upper-case hex is accepted.
        assertNotEquals(null, Netslide.executeMove(state, "S15C9D6234"))
    }

    @Test
    fun `compute active finds the reachable cells`() {
        assertEquals(9, activeCount(state("3x3", "15c9d6234")))
        assertEquals(6, activeCount(state("3x3", "c159d6234")))
        assertEquals(4, activeCount(state("3x3", "21c4d5963")))

        // A cell in the moving line is never marked, so a row move drops
        // that whole row from the active set.
        val state = state("3x3", "15c9d6234")
        val moving = Netslide.computeActive(state, movingRow = 0, movingCol = -1)
        assertEquals(0, moving[0])
        assertEquals(0, moving[1])
        assertEquals(0, moving[2])
        assertEquals(6, moving.count { it != 0 })
    }

    @Test
    fun `the corner flags OR into the high nibble`() {
        // Only `v` after cell (0,0). The border barriers make the
        // corners meet, so several cells receive flags from neighbours.
        val state = state("3x3", "2v1c4d5963")
        val expected = intArrayOf(
            0x70, 0x30, 0xB0,
            0x60, 0x00, 0x90,
            0xE0, 0xC0, 0xD0,
        )
        for (i in expected.indices) {
            assertEquals(
                expected[i],
                state.barriers[i] and 0xF0,
                "high nibble of cell $i",
            )
        }

        // The marker also sets the reverse edge, including a wrap.
        assertEquals(0x07, state.barriers[0] and 0x0F)
        assertEquals(0x06, state.barriers[1] and 0x0F)
    }

    @Test
    fun `a marker on the last column is accepted and ORs a wrap edge`() {
        val plain = state("3x3", "21c4d5963")
        val marked = state("3x3", "21c4d5963v")
        // The right edge of the last cell already has the border bit.
        assertEquals(0x03, marked.barrierAt(2, 0) and 0x0F)
        assertEquals(plain.barriers.toList(), marked.barriers.toList())
    }

    @Test
    fun `status text has the five C patterns`() {
        assertEquals("Moves: 0 Active: 4/9", statusText(state("3x3", "21c4d5963")))
        assertEquals("Moves: 0 Active: 9/9", statusText(state("3x3", "15c9d6234")))

        val scrambled = execute(state("3x3", "15c9d6234"), "R0,2")
        assertEquals("Moves: 1 Active: 6/9", statusText(scrambled))

        val completing = execute(state("3x3", "5c19d6234"), "R0,-1")
        assertEquals(1, Netslide.status(completing))
        assertEquals("COMPLETED! Moves: 1 Active: 9/9", statusText(completing))

        val target = Netslide.Params(3, 3, wrapping = false, barrierProbability = 0f, moveTarget = 5)
        val targetState = Netslide.newState(target, "15c9d6234") as Netslide.State
        assertEquals("Moves: 0 (target 5) Active: 9/9", statusText(targetState))
        val targetCompleted = Netslide.executeMove(targetState, "R0,2") as Netslide.State
        assertEquals("Moves: 1 (target 5) Active: 6/9", statusText(targetCompleted))

        val solved = execute(state("3x3", "21c4d5963"), "S15c9d6234")
        assertEquals("Moves since auto-solve: 0 Active: 9/9", statusText(solved))
        val movedAfterSolve = execute(solved, "R0,2")
        assertEquals("Moves since auto-solve: 1 Active: 6/9", statusText(movedAfterSolve))
    }

    @Test
    fun `changed state ignores a solve and a null old state`() {
        val open = state("3x3", "21c4d5963")
        val ui = Netslide.newUi(open)

        val plain = execute(open, "R0,1")
        assertFalse(Netslide.changedState(ui, open, plain))

        val completing = execute(state("3x3", "5c19d6234"), "R0,-1")
        assertTrue(Netslide.changedState(ui, state("3x3", "5c19d6234"), completing))
        assertFalse(Netslide.changedState(ui, null, completing), "no old state")

        val solved = execute(open, "S15c9d6234")
        assertFalse(Netslide.changedState(ui, open, solved), "used_solve")
    }

    @Test
    fun `the cursor walks the ring and never rests on the centre line`() {
        val state = state("3x3", "21c4d5963")
        val ui = Netslide.newUi(state) as Netslide.Ui
        assertEquals(0, ui.curX)
        assertEquals(-1, ui.curY)
        assertFalse(ui.cursorVisible)

        // The ring mixes the four keys: RIGHT and LEFT walk the top and
        // bottom rows, DOWN and UP walk the side columns. RIGHT on the
        // right column does nothing.
        val ring = listOf(
            Buttons.CURSOR_RIGHT to (2 to -1),
            Buttons.CURSOR_RIGHT to (3 to 0),
            Buttons.CURSOR_DOWN to (3 to 2),
            Buttons.CURSOR_LEFT to (2 to 3),
            Buttons.CURSOR_LEFT to (0 to 3),
            Buttons.CURSOR_UP to (-1 to 2),
            Buttons.CURSOR_UP to (-1 to 0),
            Buttons.CURSOR_UP to (0 to -1),
        )
        for ((key, target) in ring) {
            val (x, y) = target
            val outcome = Netslide.interpretMove(
                state,
                ui,
                0,
                0,
                key,
                48,
            )
            assertEquals(MoveResult.UiUpdate, outcome)
            assertEquals(x, ui.curX)
            assertEquals(y, ui.curY)
            assertTrue(ui.cursorVisible)
            assertNotEquals(state.cx, ui.curX)
            assertNotEquals(state.cy, ui.curY)
        }

        // An UP on the top row turns the cursor on and moves nothing.
        ui.cursorVisible = false
        val up = Netslide.interpretMove(state, ui, 0, 0, Buttons.CURSOR_UP, 48)
        assertEquals(MoveResult.UiUpdate, up)
        assertTrue(ui.cursorVisible)
        assertEquals(0, ui.curX)
        assertEquals(-1, ui.curY)

        // DOWN at the top-left corner goes to the left column.
        Netslide.interpretMove(state, ui, 0, 0, Buttons.CURSOR_DOWN, 48)
        assertEquals(-1, ui.curX)
        assertEquals(0, ui.curY)

        // LEFT on the right column goes back along the top row.
        ui.curX = 3
        ui.curY = 0
        Netslide.interpretMove(state, ui, 0, 0, Buttons.CURSOR_LEFT, 48)
        assertEquals(2, ui.curX)
        assertEquals(-1, ui.curY)
    }

    @Test
    fun `cursor select makes the move named by the ring position`() {
        val state = state("3x3", "21c4d5963")

        // A select with the cursor hidden only turns the cursor on.
        val hidden = Netslide.newUi(state) as Netslide.Ui
        assertEquals(
            MoveResult.UiUpdate,
            Netslide.interpretMove(state, hidden, 0, 0, Buttons.CURSOR_SELECT, 48),
        )
        assertTrue(hidden.cursorVisible)

        // At (0,-1) the top arrow slides column 0 down.
        val down = Netslide.interpretMove(state, hidden, 0, 0, Buttons.CURSOR_SELECT2, 48)
        assertEquals(MoveResult.Move("C0,-1"), down)

        // After a RIGHT the cursor is at (2,-1): column 2, forward.
        Netslide.interpretMove(state, hidden, 0, 0, Buttons.CURSOR_RIGHT, 48)
        assertEquals(2, hidden.curX)
        assertEquals(-1, hidden.curY)
        assertEquals(
            MoveResult.Move("C2,1"),
            Netslide.interpretMove(state, hidden, 0, 0, Buttons.CURSOR_SELECT, 48),
        )
    }

    @Test
    fun `clicks hit the four arrow bands and miss everything else`() {
        val state = state("3x3", "21c4d5963")

        fun click(x: Int, y: Int, button: Int = Buttons.LEFT_BUTTON): MoveResult {
            val ui = Netslide.newUi(state)
            return Netslide.interpretMove(state, ui, x, y, button, 48)
        }

        assertEquals(MoveResult.Move("R0,1"), click(24, 72))
        assertEquals(MoveResult.Move("R2,1"), click(24, 168))
        assertEquals(MoveResult.Move("R0,-1"), click(216, 72))
        assertEquals(MoveResult.Move("R0,1"), click(216, 72, Buttons.RIGHT_BUTTON))
        assertEquals(MoveResult.Move("C0,1"), click(72, 24))
        assertEquals(MoveResult.Move("C2,1"), click(168, 24))
        assertEquals(MoveResult.Move("C0,-1"), click(72, 216))

        // A cell, a corner, the centre band, the letterbox, and a pixel
        // outside the board are unused, not a UI update.
        for ((x, y) in listOf(
            72 to 72,
            24 to 24,
            24 to 120,
            120 to 24,
            120 to 120,
            0 to 120,
            240 to 120,
            241 to 120,
        )) {
            assertEquals(MoveResult.Unused, click(x, y), "click ($x,$y)")
        }
    }

    @Test
    fun `a click hides the cursor even when it makes no move`() {
        val state = state("3x3", "21c4d5963")
        val ui = Netslide.newUi(state) as Netslide.Ui
        Netslide.interpretMove(state, ui, 0, 0, Buttons.CURSOR_DOWN, 48)
        assertTrue(ui.cursorVisible)
        assertEquals(
            MoveResult.Unused,
            Netslide.interpretMove(state, ui, 72, 72, Buttons.LEFT_BUTTON, 48),
        )
        assertFalse(ui.cursorVisible)
    }

    @Test
    fun `current key label is Slide for both selects`() {
        val state = state("3x3", "21c4d5963")
        val ui = Netslide.newUi(state) as Netslide.Ui

        assertEquals("", Netslide.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.cursorVisible = true
        assertEquals("Slide", Netslide.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Slide", Netslide.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        assertEquals(
            "",
            Netslide.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT or Buttons.MOD_SHFT),
        )
        assertEquals("", Netslide.currentKeyLabel(ui, state, Buttons.CURSOR_UP))
    }

    @Test
    fun `cursor location is the ring cell without a clamp`() {
        val state = state("3x3", "21c4d5963")
        val ui = Netslide.newUi(state) as Netslide.Ui
        assertNull(Netslide.cursorLocation(ui, state, 48))

        ui.cursorVisible = true
        ui.curX = 0
        ui.curY = -1
        assertEquals(
            Game.CursorRect(48, 0, 48, 48),
            Netslide.cursorLocation(ui, state, 48),
        )

        ui.curX = 3
        ui.curY = 2
        assertEquals(
            Game.CursorRect(192, 144, 48, 48),
            Netslide.cursorLocation(ui, state, 48),
        )
    }

    @Test
    fun `the game has no text form and the default arrow mode`() {
        val state = state("3x3", "21c4d5963")
        assertFalse(Netslide.canFormatAsText)
        assertEquals("", Netslide.textFormat(state))
        assertTrue(Netslide.wantsStatusBar)
        assertTrue(Netslide.canSolve)

        val keys = Netslide.requestKeys(Netslide.decodeParams("3x3"))
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, keys.arrowMode)
        assertTrue(keys.labels.isEmpty())
    }

    @Test
    fun `compute size adds the two-tile border and the one-pixel edge`() {
        assertEquals(Size(241, 241), Netslide.computeSize(Netslide.decodeParams("3x3"), 48))
        assertEquals(Size(193, 289), Netslide.computeSize(Netslide.decodeParams("2x4"), 48))
    }

    @Test
    fun `the palette is plain background multipliers`() {
        val colours = Netslide.colours(floatArrayOf(0.8f, 0.8f, 0.8f))
        assertEquals(27, colours.size)
        assertTriple(0, 0.8f, 0.8f, 0.8f, colours)
        assertTriple(1, 0.6f, 0.6f, 0.6f, colours)
        assertTriple(2, 0.4f, 0.4f, 0.4f, colours)
        assertTriple(3, 0f, 0f, 0f, colours)
        assertTriple(4, 0f, 0f, 1f, colours)
        assertTriple(5, 0f, 1f, 1f, colours)
        assertTriple(6, 1f, 0f, 0f, colours)
        assertTriple(7, 0.64f, 0.64f, 0.64f, colours)
        assertTriple(8, 0f, 0f, 0f, colours)
    }

    @Test
    fun `format g matches the C percent g text`() {
        assertEquals("1", Netslide.formatG(1.0f))
        assertEquals("0.5", Netslide.formatG(0.5f))
        assertEquals("0.1", Netslide.formatG(0.1f))
        assertEquals("0.9999", Netslide.formatG(0.9999f))
        assertEquals("0.25", Netslide.formatG(0.25f))
        assertEquals("1e-05", Netslide.formatG(1.0e-5f))
        assertEquals("1e+06", Netslide.formatG(1000000.0f))
    }

    private fun state(params: String, desc: String): Netslide.State {
        val decoded = Netslide.decodeParams(params)
        assertNull(Netslide.validateDesc(decoded, desc))
        return Netslide.newState(decoded, desc) as Netslide.State
    }

    private fun execute(state: Netslide.State, move: String): Netslide.State =
        Netslide.executeMove(state, move) as Netslide.State

    private fun statusText(state: Netslide.State): String =
        Netslide.statusText(state, Netslide.newUi(state))

    private fun activeCount(state: Netslide.State): Int =
        Netslide.computeActive(state, -1, -1).count { it != 0 }

    private fun assertRow(state: Netslide.State, expected: IntArray) {
        val row = IntArray(state.width) { state.tileAt(it, 0) }
        assertTrue(expected.contentEquals(row), "row ${row.toList()} != ${expected.toList()}")
    }

    private fun assertColumn(state: Netslide.State, expected: IntArray) {
        val column = IntArray(state.height) { state.tileAt(0, it) }
        assertTrue(
            expected.contentEquals(column),
            "column ${column.toList()} != ${expected.toList()}",
        )
    }

    private fun assertTriple(index: Int, r: Float, g: Float, b: Float, colours: FloatArray) {
        assertEquals(r, colours[index * 3 + 0], 1.0e-6f)
        assertEquals(g, colours[index * 3 + 1], 1.0e-6f)
        assertEquals(b, colours[index * 3 + 2], 1.0e-6f)
    }
}
