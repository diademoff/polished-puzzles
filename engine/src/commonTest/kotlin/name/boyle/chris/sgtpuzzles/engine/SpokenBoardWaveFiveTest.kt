package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. Games: the last two backends of D-01, `solo` and
 * `cube`.
 *
 * Each board comes from a frozen seed-12345 game ID, so no generation
 * runs and the text cannot drift with the generator. The test pins the
 * label shape, the pointer buttons the game really offers, and what
 * stays on the key path; `BoardSemanticsGoldenTest` pins the whole
 * ordered table.
 */
class SpokenBoardWaveFiveTest {

    private fun midend(game: Game, id: String): Midend = Midend.fromGameId(game, id)

    private fun board(game: Game, id: String): SpokenBoard {
        val midend = midend(game, id)
        return game.spokenBoard(midend.state, midend.ui, TILE)
    }

    private fun SpokenBoard.cell(x: Int, y: Int): SpokenUnit = units[y * columns + x]

    // ------------------------------------------------------------------
    // Solo: the digit and pencil grammar with the two pointer actions
    // ------------------------------------------------------------------

    @Test
    fun `solo speaks a given, a digit, and the pencil marks`() {
        val state = midend(Solo, SOLO)
        val board = Solo.spokenBoard(state.state, state.ui, TILE)
        assertEquals(81, board.units.size)
        // A given has no action: a click on it only clears the highlight.
        assertEquals("given 1", board.cell(0, 0).text)
        assertTrue(board.cell(0, 0).actions.isEmpty())
        assertEquals(
            listOf(
                SpokenAction("Select", Buttons.LEFT_BUTTON),
                SpokenAction("Mark", Buttons.RIGHT_BUTTON),
            ),
            board.cell(2, 0).actions,
        )

        val pencil = Solo.executeMove(state.state, "P2,0,4") as Solo.State
        assertEquals(
            "pencil 4",
            Solo.spokenBoard(pencil, state.ui, TILE).cell(2, 0).text,
        )
        val both = Solo.executeMove(pencil, "P2,0,7") as Solo.State
        assertEquals(
            "pencil 4 7",
            Solo.spokenBoard(both, state.ui, TILE).cell(2, 0).text,
        )
        val ink = Solo.executeMove(state.state, "R2,0,4") as Solo.State
        assertEquals("4", Solo.spokenBoard(ink, state.ui, TILE).cell(2, 0).text)
    }

    @Test
    fun `solo speaks the cursor and switches its cell between ink and pencil`() {
        val state = midend(Solo, SOLO)
        val ui = state.ui as Solo.Ui
        ui.hshow = true
        ui.hx = 2
        ui.hy = 0
        ui.hpencil = false
        val ink = Solo.spokenBoard(state.state, ui, TILE)
        assertEquals("empty, cursor", ink.cell(2, 0).text)
        assertEquals("empty", ink.cell(3, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Pencil", Buttons.LEFT_BUTTON),
                SpokenAction("Mark", Buttons.RIGHT_BUTTON),
            ),
            ink.cell(2, 0).actions,
        )

        ui.hpencil = true
        val pencil = Solo.spokenBoard(state.state, ui, TILE)
        assertEquals("empty, cursor, pencil", pencil.cell(2, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Ink", Buttons.LEFT_BUTTON),
                SpokenAction("Clear", Buttons.RIGHT_BUTTON),
            ),
            pencil.cell(2, 0).actions,
        )
    }

    @Test
    fun `solo speaks the killer cage sum where the board draws it`() {
        val board = board(Solo, SOLO_KILLER)
        assertEquals("empty, cage 17", board.cell(0, 0).text)
        assertEquals(
            listOf(
                SpokenAction("Select", Buttons.LEFT_BUTTON),
                SpokenAction("Mark", Buttons.RIGHT_BUTTON),
            ),
            board.cell(0, 0).actions,
        )
        // One clue per cage, and this board has 37 cages.
        assertEquals(37, board.units.count { it.text.contains(", cage ") })
    }

    // ------------------------------------------------------------------
    // Cube: the squares, the die, and the pointer rolls
    // ------------------------------------------------------------------

    @Test
    fun `cube speaks a square, the die, and the roll its cell makes`() {
        val state = midend(Cube, CUBE)
        val board = Cube.spokenBoard(state.state, state.ui, TILE)
        assertEquals(4, board.rows)
        assertEquals(4, board.columns)
        assertEquals("Moves: 0", board.summary)
        // The die starts on square 4, which is row 2, column 1.
        assertEquals("empty", board.cell(0, 0).text)
        assertEquals(
            listOf(SpokenAction("up", Buttons.LEFT_BUTTON)),
            board.cell(0, 0).actions,
        )
        assertEquals("empty, cursor", board.cell(0, 1).text)
        // The die's own square is the click centre, so a click is inert.
        assertTrue(board.cell(0, 1).actions.isEmpty())
        assertEquals("blue", board.cell(2, 0).text)
        assertEquals(
            listOf(SpokenAction("right", Buttons.LEFT_BUTTON)),
            board.cell(2, 0).actions,
        )

        val rolled = Cube.executeMove(state.state, "R") as Cube.State
        val after = Cube.spokenBoard(rolled, state.ui, TILE)
        assertEquals(5, rolled.current)
        assertEquals("empty, cursor", after.cell(1, 1).text)
        assertEquals("empty", after.cell(0, 1).text)
    }

    @Test
    fun `cube speaks a triangle board without a rectangular grid`() {
        val state = midend(Cube, CUBE_TRIANGLES)
        val board = Cube.spokenBoard(state.state, state.ui, TILE)
        // 1*1 + 2*2 + 4*1*2 is 13 triangular cells in three rows.
        assertEquals(13, board.units.size)
        assertEquals(0, board.rows)
        assertEquals(0, board.columns)
        assertEquals("row 1, triangle 1, empty", board.units[0].text)
        assertTrue(
            board.units.all { it.row == 0 && it.column == 0 },
            "a triangle unit carries a rectangular position",
        )
        assertTrue(
            board.units.any { it.text == "row 1, triangle 2, blue" },
            "no triangle speaks the blue square: ${board.units.take(3).map { it.text }}",
        )
        // The die starts on cell 8, the first triangle of the third row.
        // A triangle's centre is not the die's centre, so its unit still
        // names the roll that a click on that rectangle makes.
        assertEquals("row 3, triangle 1, empty, cursor", board.units[8].text)
        assertEquals(
            listOf(SpokenAction("right", Buttons.LEFT_BUTTON)),
            board.units[8].actions,
        )
        val move = Cube.interpretMove(
            state.state,
            state.ui,
            board.units[8].left + board.units[8].width / 2,
            board.units[8].top + board.units[8].height / 2,
            Buttons.LEFT_BUTTON,
            TILE,
        )
        assertEquals(MoveResult.Move("R"), move)
    }

    @Test
    fun `a cube triangle board numbers its rows and triangles`() {
        val board = board(Cube, CUBE_ICOSAHEDRON)
        // 6 * 3 * 3 is 54 triangle cells in six rows.
        assertEquals(54, board.units.size)
        assertEquals(
            mapOf(1 to 7, 2 to 9, 3 to 11, 4 to 11, 5 to 9, 6 to 7),
            board.units.map { it.text.substringAfter("row ").substringBefore(",").toInt() }
                .groupingBy { it }
                .eachCount(),
        )
        for (row in 1..6) {
            val inRow = board.units.filter { it.text.startsWith("row $row, ") }
            assertEquals(
                (1..inRow.size).map { "triangle $it" },
                inRow.map { it.text.split(", ")[1] },
                "row $row is not in reading order",
            )
        }
    }

    @Test
    fun `a board above the unit cap speaks the cursor unit alone`() {
        // 21x20 is 420 cells: above the 400-unit cap, far below the
        // D-036 generation cap, and a legal hand-written save.
        val cubeId = "c21x20:" + "0".repeat((21 * 20 + 3) / 4) + ",0"
        val cube = board(Cube, cubeId)
        assertEquals(20, cube.rows)
        assertEquals(21, cube.columns)
        assertEquals(1, cube.units.size)
        assertEquals("empty, cursor", cube.units[0].text)
        assertEquals(1, cube.units[0].row)
        assertEquals(1, cube.units[0].column)
        // The die's own centre is inert, so the one unit has no action.
        assertTrue(cube.units[0].actions.isEmpty())

        // 9x9 is 486 triangle cells, so the triangle path caps as well.
        val triangleId = "i9x9:" + "0".repeat((486 + 3) / 4) + ",0"
        val triangle = board(Cube, triangleId)
        assertEquals(0, triangle.rows)
        assertEquals(1, triangle.units.size)
        assertTrue(
            triangle.units[0].text.startsWith("row "),
            "the capped triangle unit lost its row: ${triangle.units[0].text}",
        )
        assertTrue(triangle.units[0].text.endsWith(", empty, cursor"))
    }

    @Test
    fun `a cube unit names one pointer roll and the keys stay on the keys`() {
        val state = midend(Cube, CUBE)
        val board = Cube.spokenBoard(state.state, state.ui, TILE)
        // The eight keypad directions and the four arrow keys make the
        // same rolls on the key path. They are not pointer targets, so
        // no unit offers them as its own button; a unit names the one
        // roll its centre makes.
        val words = setOf(
            "left",
            "right",
            "up",
            "down",
            "up and left",
            "up and right",
            "down and left",
            "down and right",
        )
        assertTrue(
            board.units.flatMap { it.actions }.all {
                it.label in words && it.button == Buttons.LEFT_BUTTON
            },
            "a roll label is not a pointer roll in the game's words",
        )
        assertFalse(board.units.any { unit -> unit.actions.size > 1 })
    }

    private companion object {
        const val TILE = 32

        /** The frozen default 4x4 cube of seed 12345. */
        const val CUBE = "c4x4:205E,4"

        /** The tetrahedron preset, the smallest triangle board. */
        const val CUBE_TRIANGLES = "t1x2:9108,8"

        /** The icosahedron preset, the largest public triangle board. */
        const val CUBE_ICOSAHEDRON = "i3x3:981F3821227120,23"

        /** The frozen default 3x3 solo board of seed 12345, 81 cells. */
        const val SOLO =
            "3x3:1_4c3_9b2a6_7j6a5_2b3_2e6b3a7b8e9_7b5_8a1j2_8a9b7_6c3_5"

        /** The frozen 3x3 killer board of seed 12345, whose cages carry sums. */
        const val SOLO_KILLER =
            "3x3k:zzzc,a_____b_____a____________________aa______c____b___ba__ab_aa____aa__aaa_caabaaaaa_a_aa_a___aa____aa_a,17a8_19_10_10_7_7a9f10a5_9_15a13_4_13_9_9c8e10_9_8a16_13a20d6a13_13b18c3b6a7a6_12a15b25_14c9d"
    }
}
