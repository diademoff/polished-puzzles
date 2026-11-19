package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. Games: the ten plain cell games of D-01 wave 2.
 *
 * Each board comes from a frozen seed-12345 game ID, so no generation
 * runs and the text cannot drift with the generator. The test pins the
 * content words, the cursor suffix, and the pointer buttons the game
 * offers; `BoardSemanticsGoldenTest` pins the whole ordered table.
 */
class SpokenBoardGamesTest {

    private companion object {
        const val TILE = 32

        /** Frozen seed-12345 IDs, the smallest preset of each game. */
        const val FIFTEEN = "4x4:14,13,3,10,12,5,6,7,1,8,2,11,0,9,4,15"
        const val SIXTEEN = "3x3:6,8,7,3,2,5,4,1,9"
        const val FLIP = "3x3:d074191345d1644c17058,c90"
        const val TWIDDLE = "3x3n2:2,5,6,7,3,1,4,9,8"
        const val MINES = "9x9:r10,u,8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e8908980d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b8402"
        const val MOSAIC = "3x3:4b47d"
        const val SIGNPOST = "4x4:16ag1ffcddachhfcaga"
        const val RECT = "7x7:3_2a2_3g2f5_2a6a3_3b2_2d4b3_2c5f"
        const val SINGLES = "5x5:1122434125331414135512313"
        const val UNRULY = "8x8:bEACHcBdBDEalcbAbbBa"
    }

    private fun board(game: Game, id: String): SpokenBoard {
        val midend = Midend.fromGameId(game, id)
        return game.spokenBoard(midend.state, midend.ui, TILE)
    }

    private fun SpokenBoard.cell(x: Int, y: Int): SpokenUnit = units[y * columns + x]

    @Test
    fun `fifteen speaks tiles around the gap and slides a row`() {
        val board = board(Fifteen, FIFTEEN)
        assertEquals(16, board.units.size)
        // The gap is at row 4, column 1 in this frozen board.
        assertEquals("empty", board.cell(0, 3).text)
        assertTrue(board.cell(0, 3).actions.isEmpty())
        // A tile that shares the gap's row or column slides into it.
        assertEquals("tile 9", board.cell(1, 3).text)
        assertEquals(
            listOf(SpokenAction("Slide", Buttons.LEFT_BUTTON)),
            board.cell(1, 3).actions,
        )
        // A tile that shares neither the row nor the column ignores a
        // click, and so does the gap itself.
        assertEquals("tile 14", board.cell(0, 0).text)
        assertEquals(
            listOf(SpokenAction("Slide", Buttons.LEFT_BUTTON)),
            board.cell(0, 0).actions,
        )
        assertEquals("tile 13", board.cell(1, 0).text)
        assertTrue(board.cell(1, 0).actions.isEmpty())
    }

    @Test
    fun `sixteen speaks tiles and leaves the frame moves on the key path`() {
        val board = board(Sixteen, SIXTEEN)
        assertEquals(9, board.units.size)
        assertEquals("tile 6", board.cell(0, 0).text)
        // A cell click is a UI update in `interpret_move`, not a move.
        assertTrue(board.units.all { it.actions.isEmpty() })
        assertEquals(TILE, board.cell(0, 0).left)
        assertEquals(TILE, board.cell(0, 0).top)
    }

    @Test
    fun `flip speaks light and dark and offers the flip button`() {
        val board = board(Flip, FLIP)
        assertEquals(9, board.units.size)
        assertTrue(board.units.all { it.text == "light" || it.text == "dark" })
        assertTrue(board.units.any { it.actions == listOf(SpokenAction("Flip", Buttons.LEFT_BUTTON)) })
    }

    @Test
    fun `twiddle speaks a tile and turns the block around it`() {
        val board = board(Twiddle, TWIDDLE)
        assertEquals(9, board.units.size)
        assertEquals("tile 2", board.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Turn left", Buttons.LEFT_BUTTON),
                SpokenAction("Turn right", Buttons.RIGHT_BUTTON),
            ),
            board.cell(0, 0).actions,
        )
        // A 2x2 block cannot start in the last row or column.
        assertTrue(board.cell(2, 2).actions.isEmpty())
    }

    @Test
    fun `mines speaks a covered cell and marks it`() {
        val state = Midend.fromGameId(Mines, MINES)
        val before = Mines.spokenBoard(state.state, state.ui, TILE)
        assertEquals(81, before.units.size)
        assertEquals("unopened", before.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Uncover", Buttons.LEFT_BUTTON),
                SpokenAction("Mark", Buttons.RIGHT_BUTTON),
            ),
            before.cell(0, 0).actions,
        )

        val marked = Mines.executeMove(state.state, "F0,0") as Mines.State
        val board = Mines.spokenBoard(marked, state.ui, TILE)
        assertEquals("marked", board.cell(0, 0).text)
        assertEquals(
            listOf(SpokenAction("Unmark", Buttons.RIGHT_BUTTON)),
            board.cell(0, 0).actions,
        )
    }

    @Test
    fun `mosaic speaks the clue and the colour cycle`() {
        val state = Midend.fromGameId(Mosaic, MOSAIC)
        val before = Mosaic.spokenBoard(state.state, state.ui, TILE)
        assertEquals("clue 4, empty", before.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Black", Buttons.LEFT_BUTTON),
                SpokenAction("White", Buttons.RIGHT_BUTTON),
            ),
            before.cell(0, 0).actions,
        )

        val black = Mosaic.executeMove(state.state, "t0,0") as Mosaic.State
        val board = Mosaic.spokenBoard(black, state.ui, TILE)
        assertEquals("clue 4, black", board.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("White", Buttons.LEFT_BUTTON),
                SpokenAction("Empty", Buttons.RIGHT_BUTTON),
            ),
            board.cell(0, 0).actions,
        )
    }

    @Test
    fun `signpost speaks the arrow and leaves the drag on the key path`() {
        val board = board(Signpost, SIGNPOST)
        assertEquals(16, board.units.size)
        assertTrue(board.units.all { it.text.startsWith("arrow ") })
        // A link is a drag between two cells, which one unit cannot name.
        assertTrue(board.units.all { it.actions.isEmpty() })
    }

    @Test
    fun `rectangles speaks the clue and the lines around it`() {
        val board = board(Rect, RECT)
        assertEquals(49, board.units.size)
        assertTrue(board.units.any { it.text.startsWith("clue ") })
        assertTrue(board.units.any { it.text == "empty" })
        // An edge toggle needs an edge midpoint, not a cell centre.
        assertTrue(board.units.all { it.actions.isEmpty() })
    }

    @Test
    fun `singles speaks the digit and the mark cycle`() {
        val state = Midend.fromGameId(Singles, SINGLES)
        val before = Singles.spokenBoard(state.state, state.ui, TILE)
        assertEquals("1", before.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Black", Buttons.LEFT_BUTTON),
                SpokenAction("Circle", Buttons.RIGHT_BUTTON),
            ),
            before.cell(0, 0).actions,
        )

        val black = Singles.executeMove(state.state, "B0,0") as Singles.State
        val board = Singles.spokenBoard(black, state.ui, TILE)
        assertEquals("1, black", board.cell(0, 0).text)
        assertEquals(
            listOf(SpokenAction("Restore", Buttons.LEFT_BUTTON)),
            board.cell(0, 0).actions,
        )
    }

    @Test
    fun `unruly speaks a given clue without a button`() {
        val board = board(Unruly, UNRULY)
        assertEquals(64, board.units.size)
        val given = board.units.filter { it.text.endsWith(", given") }
        assertTrue(given.isNotEmpty())
        assertTrue(given.all { it.actions.isEmpty() })
        val empty = board.units.first { it.text == "empty" }
        assertEquals(
            listOf(
                SpokenAction("Black", Buttons.LEFT_BUTTON),
                SpokenAction("White", Buttons.RIGHT_BUTTON),
            ),
            empty.actions,
        )
    }

    @Test
    fun `a cursor is spoken on one cell only`() {
        val midend = Midend.fromGameId(Unruly, UNRULY)
        val ui = midend.ui as Unruly.Ui
        ui.curX = 2
        ui.curY = 1
        ui.curVisible = true
        val board = Unruly.spokenBoard(midend.state, ui, TILE)
        val cursors = board.units.filter { it.text.contains(", cursor") }
        assertEquals(1, cursors.size)
        assertEquals(3, cursors[0].column)
        assertEquals(2, cursors[0].row)
        assertFalse(cursors[0].text.startsWith("Row "))
    }
}
