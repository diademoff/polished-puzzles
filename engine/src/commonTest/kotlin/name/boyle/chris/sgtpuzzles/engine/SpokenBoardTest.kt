package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: all Kotlin games, with `tents` as the pilot.
 *
 * Pins the spoken board seam of D-01: the default is empty, so an
 * unported game keeps the shell's single board stop, and the pilot
 * board speaks the grammar of `work/board-a11y-spec.md` section 4 in
 * the game's own order: cells in row-major order, then the row clues,
 * then the column clues.
 *
 * The frozen description is the one in `TentsTest.GOLDEN_DESC`, so the
 * expected text below is the C board of seed 12345, not a made-up one.
 */
class SpokenBoardTest {

    private companion object {
        const val TILE = 32
        const val GOLDEN_DESC =
            "ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"

        /** 21x21 of trees, above the cap; the clues are all zero. */
        val CAP_DESC_441 = "z".repeat(17) + "p" + ",0".repeat(42)

        /** 20x20 of trees, exactly at the cap. */
        val CAP_DESC_400 = "z".repeat(15) + "y" + ",0".repeat(40)
    }

    private fun tentsState(desc: String = GOLDEN_DESC): Tents.State =
        Tents.newState(Tents.Params(8, 8, Tents.DIFF_EASY), desc) as Tents.State

    @Test
    fun `a game without the seam returns an empty board`() {
        val state = Cube.newState(Cube.defaultParams(), "")
        val board = Cube.spokenBoard(state, Cube.newUi(state), TILE)
        assertEquals(SpokenBoard(0, 0, "", emptyList()), board)
        assertTrue(board.units.isEmpty())
    }

    @Test
    fun `tents speaks every cell and every clue in board order`() {
        val state = tentsState()
        val ui = Tents.newUi(state) as Tents.Ui
        val board = Tents.spokenBoard(state, ui, TILE)

        assertEquals(8, board.rows)
        assertEquals(8, board.columns)
        assertEquals("", board.summary)
        // 64 cells, then 8 row clues, then 8 column clues.
        assertEquals(80, board.units.size)

        // A cell: position, then content, with no coordinate in the text.
        val tree = board.units[2 * 8 + 3]
        assertEquals(3, tree.row)
        assertEquals(4, tree.column)
        assertEquals(112, tree.left)
        assertEquals(80, tree.top)
        assertEquals(TILE, tree.width)
        assertEquals(TILE, tree.height)
        assertEquals("tree", tree.text)
        assertTrue(tree.actions.isEmpty())

        // Grass offers the game's two pointer buttons.
        assertEquals("empty", board.units[0].text)
        assertEquals(
            listOf(
                SpokenAction("Tent", Buttons.LEFT_BUTTON),
                SpokenAction("Green", Buttons.RIGHT_BUTTON),
            ),
            board.units[0].actions,
        )

        // The row clues come after the cells, in row order.
        val rowClue = board.units[64 + 2]
        assertEquals(0, rowClue.row)
        assertEquals(0, rowClue.column)
        assertEquals("Row 3, 2 tents", rowClue.text)
        assertEquals(273, rowClue.left)
        assertEquals(80, rowClue.top)
        assertEquals(47, rowClue.width)
        assertEquals(TILE, rowClue.height)
        assertEquals("Row 1, 2 tents", board.units[64].text)
        assertEquals("Row 8, 3 tents", board.units[64 + 7].text)

        // Then the column clues.
        assertEquals("Column 1, 3 tents", board.units[72].text)
        assertEquals("Column 2, 1 tent", board.units[72 + 1].text)
        assertEquals("Column 7, 2 tents", board.units[72 + 6].text)
        assertEquals(16, board.units[72].left)
        assertEquals(273, board.units[72].top)
        assertEquals(TILE, board.units[72].width)
        assertEquals(47, board.units[72].height)
    }

    @Test
    fun `tents speaks a move, a tent, the clear buttons and the cursor`() {
        val before = tentsState()
        val moved = Tents.executeMove(before, "T2,2") as Tents.State
        val ui = Tents.newUi(moved) as Tents.Ui
        ui.cx = 3
        ui.cy = 2
        ui.cdisp = true

        val board = Tents.spokenBoard(moved, ui, TILE)

        assertEquals("tent", board.units[2 * 8 + 2].text)
        assertEquals(
            listOf(SpokenAction("Clear", Buttons.LEFT_BUTTON)),
            board.units[2 * 8 + 2].actions,
        )
        assertEquals("tree, cursor", board.units[2 * 8 + 3].text)
        // A marked cell goes back to empty with the left button and on
        // to a tent with the right one.
        val marked = Tents.executeMove(moved, "N1,2") as Tents.State
        assertEquals(
            listOf(
                SpokenAction("Clear", Buttons.LEFT_BUTTON),
                SpokenAction("Tent", Buttons.RIGHT_BUTTON),
            ),
            Tents.spokenBoard(marked, ui, TILE).units[2 * 8 + 1].actions,
        )
    }

    @Test
    fun `tents keeps every unit at the cap and the cursor alone above it`() {
        val atCap = Tents.newState(
            Tents.Params(20, 20, Tents.DIFF_EASY),
            CAP_DESC_400,
        ) as Tents.State
        val atCapBoard = Tents.spokenBoard(atCap, Tents.newUi(atCap), TILE)
        assertEquals(20, atCapBoard.rows)
        assertEquals(400 + 40, atCapBoard.units.size)

        val above = Tents.newState(
            Tents.Params(21, 21, Tents.DIFF_EASY),
            CAP_DESC_441,
        ) as Tents.State
        val ui = Tents.newUi(above) as Tents.Ui
        ui.cx = 4
        ui.cy = 5
        ui.cdisp = true
        val aboveBoard = Tents.spokenBoard(above, ui, TILE)
        assertEquals(21, aboveBoard.rows)
        assertEquals(21, aboveBoard.columns)
        assertEquals(1, aboveBoard.units.size)
        assertEquals("empty, cursor", aboveBoard.units[0].text)
        // The cursor unit still carries the real cell rectangle, so a
        // double tap reaches the cell.
        assertEquals(16 + 4 * TILE, aboveBoard.units[0].left)
        assertEquals(16 + 5 * TILE, aboveBoard.units[0].top)
    }
}
