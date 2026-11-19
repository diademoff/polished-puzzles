package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. Games: the twelve wave 3 games of D-01.
 *
 * Each board comes from a frozen seed-12345 game ID, so no generation
 * runs and the text cannot drift with the generator. The test pins the
 * label shape, the pointer buttons the game really offers, and the moves
 * that stay on the key path; `BoardSemanticsGoldenTest` pins the whole
 * ordered table.
 */
class SpokenBoardWaveThreeTest {

    private fun board(game: Game, id: String): SpokenBoard {
        val midend = Midend.fromGameId(game, id)
        return game.spokenBoard(midend.state, midend.ui, TILE)
    }

    private fun SpokenBoard.cell(x: Int, y: Int): SpokenUnit = units[y * columns + x]

    // ------------------------------------------------------------------
    // Colour-state games
    // ------------------------------------------------------------------

    @Test
    fun `samegame speaks a colour name and its two-stage tap`() {
        val board = board(SameGame, SAMEGAME)
        assertEquals(25, board.units.size)
        // A lone tile has no group, so a tap on it is nothing.
        assertEquals("green", board.cell(0, 0).text)
        assertTrue(board.cell(0, 0).actions.isEmpty())
        // A tile in a group of two is selectable.
        assertEquals("red", board.cell(1, 0).text)
        assertEquals(
            listOf(SpokenAction("Select", Buttons.LEFT_BUTTON)),
            board.cell(1, 0).actions,
        )
        assertTrue(board.units.all { unit ->
            unit.text in COLOUR_NAMES || unit.text == "empty"
        })

        // The second tap removes the selected group.
        val midend = Midend.fromGameId(SameGame, SAMEGAME)
        (midend.ui as SameGame.Ui).selected[1] = true
        val selected = SameGame.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals("red, selected", selected.cell(1, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Remove", Buttons.LEFT_BUTTON),
                SpokenAction("Unselect", Buttons.RIGHT_BUTTON),
            ),
            selected.cell(1, 0).actions,
        )
    }

    @Test
    fun `flood speaks a colour name and fills from a different colour`() {
        val state = Midend.fromGameId(Flood, FLOOD)
        val board = Flood.spokenBoard(state.state, state.ui, TILE)
        assertEquals(144, board.units.size)
        // The top-left colour is the flooded region: no move there.
        assertEquals("yellow", board.cell(0, 0).text)
        assertTrue(board.cell(0, 0).actions.isEmpty())
        assertEquals(
            listOf(SpokenAction("Fill", Buttons.LEFT_BUTTON)),
            board.cell(1, 0).actions,
        )

        // After one fill the region matches the tapped colour.
        val next = Flood.executeMove(state.state, "M4") as Flood.State
        val after = Flood.spokenBoard(next, state.ui, TILE)
        assertEquals("orange", after.cell(0, 0).text)
        assertEquals("orange", after.cell(1, 0).text)
    }

    // ------------------------------------------------------------------
    // Digit and pencil games
    // ------------------------------------------------------------------

    @Test
    fun `keen speaks the block clue and leaves the digits on the keys`() {
        val state = Midend.fromGameId(Keen, KEEN)
        val board = Keen.spokenBoard(state.state, state.ui, TILE)
        assertEquals("empty, clue 2 divided by", board.cell(0, 0).text)
        assertEquals("empty, clue 18 times", board.cell(1, 0).text)
        assertEquals("empty", board.cell(3, 0).text)
        assertTrue(board.units.all { it.actions.isEmpty() })

        val pencil = Keen.executeMove(state.state, "P0,0,1") as Keen.State
        val marked = Keen
            .executeMove(pencil, "P0,0,3") as Keen.State
        val board2 = Keen.spokenBoard(marked, state.ui, TILE)
        assertEquals("pencil 1 3, clue 2 divided by", board2.cell(0, 0).text)

        val ink = Keen.executeMove(state.state, "R0,0,2") as Keen.State
        assertEquals(
            "2, clue 2 divided by",
            Keen.spokenBoard(ink, state.ui, TILE).cell(0, 0).text,
        )
    }

    @Test
    fun `towers speaks a sight clue with the done toggle`() {
        val state = Midend.fromGameId(Towers, TOWERS)
        val board = Towers.spokenBoard(state.state, state.ui, TILE)
        assertEquals(16 + 16, board.units.size)
        assertEquals("empty", board.cell(0, 0).text)
        assertTrue(board.units.take(16).all { it.actions.isEmpty() })
        val clue = board.units[16]
        assertEquals(0, clue.row)
        assertEquals("Column 1, 1 from the top", clue.text)
        assertEquals(
            listOf(SpokenAction("Done", Buttons.LEFT_BUTTON)),
            clue.actions,
        )

        val done = Towers.executeMove(state.state, "D0,-1") as Towers.State
        val after = Towers.spokenBoard(done, state.ui, TILE)
        assertEquals("Column 1, 1 from the top, done", after.units[16].text)
        assertEquals(
            listOf(SpokenAction("Not done", Buttons.LEFT_BUTTON)),
            after.units[16].actions,
        )

        val placed = Towers.executeMove(state.state, "R0,0,2") as Towers.State
        assertEquals("2", Towers.spokenBoard(placed, state.ui, TILE).cell(0, 0).text)
    }

    @Test
    fun `unequal speaks the sign words and the given`() {
        val state = Midend.fromGameId(Unequal, UNEQUAL)
        val board = Unequal.spokenBoard(state.state, state.ui, TILE)
        assertEquals(16, board.units.size)
        assertEquals("empty, greater than right", board.cell(1, 0).text)
        assertEquals("empty, less than left", board.cell(3, 0).text)
        assertEquals("given 3", board.cell(2, 2).text)
        assertTrue(board.units.all { it.actions.isEmpty() })

        val pencil = Unequal.executeMove(state.state, "P0,0,2") as Unequal.State
        assertEquals(
            "pencil 2",
            Unequal.spokenBoard(pencil, state.ui, TILE).cell(0, 0).text,
        )
    }

    @Test
    fun `filling speaks the value origin and the selection`() {
        val state = Midend.fromGameId(Filling, FILLING)
        val board = Filling.spokenBoard(state.state, state.ui, TILE)
        assertEquals(63, board.units.size)
        assertEquals("given 8", board.cell(1, 0).text)
        assertTrue(board.cell(1, 0).actions.isEmpty())
        assertEquals("empty", board.cell(0, 0).text)
        // A click selects one cell; the value comes from the keypad.
        assertEquals(
            listOf(SpokenAction("Select", Buttons.LEFT_BUTTON)),
            board.cell(0, 0).actions,
        )

        val filled = Filling.executeMove(state.state, "0_5") as Filling.State
        // The single 5 is sealed: no cell of it touches an empty cell.
        assertEquals("5, wrong", Filling.spokenBoard(filled, state.ui, TILE).cell(0, 0).text)
    }

    // ------------------------------------------------------------------
    // Other cell games
    // ------------------------------------------------------------------

    @Test
    fun `pegs speaks a peg and leaves the jump on the drag`() {
        val board = board(Pegs, PEGS)
        assertEquals(25, board.units.size)
        assertEquals("blocked", board.cell(0, 0).text)
        assertEquals("hole", board.cell(2, 2).text)
        // A jump is a drag between two cells, which one unit cannot name.
        assertTrue(board.units.all { it.actions.isEmpty() })
    }

    @Test
    fun `light up speaks a wall, a clue, and both light buttons`() {
        val state = Midend.fromGameId(Lightup, LIGHTUP)
        val board = Lightup.spokenBoard(state.state, state.ui, TILE)
        assertEquals(49, board.units.size)
        assertEquals("wall", board.cell(0, 0).text)
        assertTrue(board.cell(0, 0).actions.isEmpty())
        assertEquals(
            listOf(
                SpokenAction("Light", Buttons.LEFT_BUTTON),
                SpokenAction("No light", Buttons.RIGHT_BUTTON),
            ),
            board.cell(1, 0).actions,
        )
        assertTrue(board.units.any { it.text == "clue 1" })

        val lit = Lightup.executeMove(state.state, "L1,0") as Lightup.State
        assertEquals("light", Lightup.spokenBoard(lit, state.ui, TILE).cell(1, 0).text)
    }

    @Test
    fun `magnets speaks a domino and its count clues`() {
        val state = Midend.fromGameId(Magnets, MAGNETS)
        val board = Magnets.spokenBoard(state.state, state.ui, TILE)
        assertEquals(30 + 22, board.units.size)
        assertEquals("empty", board.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Plus", Buttons.LEFT_BUTTON),
                SpokenAction("Neutral", Buttons.RIGHT_BUTTON),
            ),
            board.cell(0, 0).actions,
        )
        assertEquals("Column 1, 2 plus poles", board.units[30].text)
        assertEquals(
            listOf(SpokenAction("Done", Buttons.LEFT_BUTTON)),
            board.units[30].actions,
        )

        val plus = Magnets.executeMove(state.state, "+0,0") as Magnets.State
        val after = Magnets.spokenBoard(plus, state.ui, TILE)
        assertEquals("plus", after.cell(0, 0).text)
        assertEquals("minus", after.cell(1, 0).text)
        assertEquals(
            listOf(SpokenAction("Minus", Buttons.LEFT_BUTTON)),
            after.cell(0, 0).actions,
        )

        val done = Magnets.executeMove(state.state, "D0,-1") as Magnets.State
        assertEquals(
            "Column 1, 2 plus poles, done",
            Magnets.spokenBoard(done, state.ui, TILE).units[30].text,
        )
    }

    @Test
    fun `range speaks a clue, a colour cycle, and the error`() {
        val state = Midend.fromGameId(Range, RANGE)
        val board = Range.spokenBoard(state.state, state.ui, TILE)
        assertEquals(54, board.units.size)
        assertEquals("clue 7", board.cell(0, 0).text)
        assertTrue(board.cell(0, 0).actions.isEmpty())
        assertEquals(
            listOf(
                SpokenAction("Black", Buttons.LEFT_BUTTON),
                SpokenAction("White", Buttons.RIGHT_BUTTON),
            ),
            board.cell(2, 0).actions,
        )

        val black = Range.executeMove(state.state, "B,0,2") as Range.State
        val after = Range.spokenBoard(black, state.ui, TILE)
        assertEquals("black", after.cell(2, 0).text)
        assertEquals(
            listOf(
                SpokenAction("White", Buttons.LEFT_BUTTON),
                SpokenAction("Empty", Buttons.RIGHT_BUTTON),
            ),
            after.cell(2, 0).actions,
        )
    }

    @Test
    fun `undead speaks a mirror, a clue, and the counts`() {
        val state = Midend.fromGameId(Undead, UNDEAD)
        val board = Undead.spokenBoard(state.state, state.ui, TILE)
        assertEquals(16 + 16 + 3, board.units.size)
        assertEquals("mirror left", board.cell(0, 0).text)
        assertEquals("empty", board.cell(1, 0).text)
        assertTrue(board.units.take(16).all { it.actions.isEmpty() })
        assertEquals("clue 0", board.units[16].text)
        assertEquals(
            listOf(SpokenAction("Done", Buttons.LEFT_BUTTON)),
            board.units[16].actions,
        )
        assertEquals("ghosts, 0 of 2", board.units[32].text)
        assertEquals("vampires, 0 of 4", board.units[33].text)
        assertEquals("zombies, 0 of 2", board.units[34].text)

        val done = Undead.executeMove(state.state, "D1,0") as Undead.State
        assertEquals(
            "clue 0, done",
            Undead.spokenBoard(done, state.ui, TILE).units[16].text,
        )
    }

    @Test
    fun `dominosa speaks the number and leaves the edge on the pointer`() {
        val board = board(Dominosa, DOMINOSA)
        assertEquals(20, board.units.size)
        assertEquals("0", board.cell(0, 0).text)
        assertEquals("2", board.cell(4, 0).text)
        // A placement is a click on the half nearest one edge.
        assertTrue(board.units.all { it.actions.isEmpty() })
    }

    // ------------------------------------------------------------------
    // The shared grammar
    // ------------------------------------------------------------------

    @Test
    fun `the digit grammar is one sentence per cell`() {
        assertEquals("given 5", digitSpeech(5, given = true, pencil = emptyList()))
        assertEquals("5", digitSpeech(5, given = false, pencil = emptyList()))
        assertEquals("pencil 1 4 7", digitSpeech(0, given = false, pencil = listOf(1, 4, 7)))
        assertEquals("empty", digitSpeech(0, given = false, pencil = emptyList()))
        // A typed value wins over a stale mark; every game clears it.
        assertEquals("5", digitSpeech(5, given = false, pencil = listOf(1)))
        assertEquals(listOf(1, 3), pencilDigits(0b1010, 4))
        assertEquals(emptyList(), pencilDigits(0, 9))
    }

    @Test
    fun `a duplicate digit is spoken as wrong`() {
        val state = Midend.fromGameId(Unequal, UNEQUAL)
        val one = Unequal.executeMove(state.state, "R0,1,1") as Unequal.State
        val two = Unequal.executeMove(one, "R0,2,1") as Unequal.State
        val board = Unequal.spokenBoard(two, state.ui, TILE)
        assertTrue(
            board.units.any { it.text.contains(SPOKEN_WRONG) },
            "a duplicate row digit is not spoken as wrong: ${board.units.map { it.text }}",
        )
    }

    private companion object {
        const val TILE = 32

        /** The nine signed names of Same Game, see `BoardColour`. */
        val COLOUR_NAMES = setOf(
            "blue",
            "green",
            "red",
            "yellow",
            "magenta",
            "cyan",
            "light blue",
            "light green",
            "pink",
        )

        /** Frozen seed-12345 IDs, the smallest preset of each game. */
        const val SAMEGAME = "5x5c3s2:2,3,1,3,1,1,3,1,3,1,1,2,2,2,2,3,3,1,3,3,2,2,1,2,2"
        const val FLOOD =
            "12x12:14421345502031123040255354232000010205100115051121530233353415203524405124" +
            "1432023324551101011211522022310121213214532552231503344231334355401013,24"
        const val FILLING = "9x7:a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55"
        const val PEGS = "5x5:OPPOOPPPPOPPHPPOPPPPOOPPO"
        const val LIGHTUP = "7x7:Be1j4e3aBeBj1e1"
        const val MAGNETS =
            "6x5:221211,12231,302112,11313,LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB"
        const val RANGE = "9x6:7_5e4d5g8l7g9d11e8_13"
        const val TOWERS = "4:1/2/2/3/3/2/2/1/1/2/3/3/3/3/2/1"
        const val UNEQUAL = "4:0,0R,0D,0L,0,0L,0,0U,0,0,3,0D,0,0,0,0,"
        const val KEEN = "4:__aa_4cc__aa_,d2m18d2m4s2a13"
        const val UNDEAD = "4x4:2,4,2,LbRRdLaLaLRR,0,1,4,0,0,4,0,0,0,1,1,3,0,2,0,0"
        const val DOMINOSA = "3:01012311032222033310"
    }
}
