package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. Games: `samegame` and `flood`, the two games whose
 * whole state is a colour.
 *
 * D-01 section 8 and DESIGN section 11: one shared vocabulary, so a
 * colour is spelled once; and a colour-blind path, so the name is in the
 * label and not only in the palette.
 */
class SpokenColourTest {

    private val vocabulary = BoardColour.entries.map { it.spokenName }.toSet()

    @Test
    fun `the shared vocabulary has one name per colour`() {
        assertEquals(
            BoardColour.entries.size,
            vocabulary.size,
            "two colours share a spoken name",
        )
        assertTrue(vocabulary.all { it.isNotBlank() })
        assertTrue(
            vocabulary.none { it.any { c -> c.isDigit() } || it.startsWith("#") },
            "a colour name must not be a value: $vocabulary",
        )
    }

    @Test
    fun `one game colour index always gives one name`() {
        for (colour in 1..9) {
            assertEquals(SameGame.colourName(colour), SameGame.colourName(colour))
            assertTrue(
                SameGame.colourName(colour) in vocabulary,
                "samegame colour $colour is not in the shared vocabulary",
            )
        }
        val samegame = (1..9).map { SameGame.colourName(it) }
        assertEquals(9, samegame.toSet().size, "samegame reuses a name: $samegame")

        val flood = (0..9).map { Flood.colourName(it) }
        assertEquals(10, flood.toSet().size, "flood reuses a name: $flood")
        assertTrue(
            flood.all { it in vocabulary },
            "flood uses a name outside the shared vocabulary: $flood",
        )
    }

    @Test
    fun `the same visual colour gets the same name in both games`() {
        // Both games draw these colours, so both must say the same word.
        val shared = listOf(
            BoardColour.RED,
            BoardColour.YELLOW,
            BoardColour.GREEN,
            BoardColour.BLUE,
            BoardColour.PINK,
            BoardColour.LIGHT_BLUE,
            BoardColour.LIGHT_GREEN,
        )
        for (colour in shared) {
            assertEquals(
                colour.spokenName,
                SameGame.colourName(samegameIndex(colour)),
                "samegame spells ${colour.spokenName} differently",
            )
            assertEquals(
                colour.spokenName,
                Flood.colourName(floodIndex(colour)),
                "flood spells ${colour.spokenName} differently",
            )
        }
    }

    @Test
    fun `the label carries the colour and not only the palette`() {
        // One tile per colour on a one-row board, no generation.
        val params = SameGame.Params(width = 9, height = 1, ncols = 9, scoresub = 2, soluble = false)
        val state = SameGame.State(params, 9, IntArray(9) { it + 1 }, 0, false, true)
        val board = SameGame.spokenBoard(state, SameGame.Ui(9), TILE)
        assertEquals(9, board.units.size)
        for ((index, unit) in board.units.withIndex()) {
            assertEquals(SameGame.colourName(index + 1), unit.text)
        }

        val floodState = Flood.State(
            width = 10,
            height = 1,
            grid = IntArray(10) { it },
            colours = 10,
            moves = 0,
            movelimit = 20,
            complete = false,
            cheated = false,
            soln = null,
            solnpos = 0,
        )
        val floodBoard = Flood.spokenBoard(floodState, Flood.Ui(), TILE)
        assertEquals(10, floodBoard.units.size)
        for ((index, unit) in floodBoard.units.withIndex()) {
            assertEquals(Flood.colourName(index), unit.text)
        }
    }

    /** The Same Game palette index of a shared colour. */
    private fun samegameIndex(colour: BoardColour): Int = when (colour) {
        BoardColour.BLUE -> 1
        BoardColour.GREEN -> 2
        BoardColour.RED -> 3
        BoardColour.YELLOW -> 4
        BoardColour.MAGENTA -> 5
        BoardColour.CYAN -> 6
        BoardColour.LIGHT_BLUE -> 7
        BoardColour.LIGHT_GREEN -> 8
        BoardColour.PINK -> 9
        else -> error("samegame does not draw $colour")
    }

    /** The Flood palette index of a shared colour. */
    private fun floodIndex(colour: BoardColour): Int = when (colour) {
        BoardColour.RED -> 0
        BoardColour.YELLOW -> 1
        BoardColour.GREEN -> 2
        BoardColour.BLUE -> 3
        BoardColour.ORANGE -> 4
        BoardColour.PURPLE -> 5
        BoardColour.BROWN -> 6
        BoardColour.LIGHT_BLUE -> 7
        BoardColour.LIGHT_GREEN -> 8
        BoardColour.PINK -> 9
        else -> error("flood does not draw $colour")
    }

    private companion object {
        const val TILE = 32
    }
}
