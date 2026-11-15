package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic. Game: guess.
 *
 * The board geometry, real clicks, a colour drag at four tile sizes,
 * two letterboxed drags, the draft, hold, and clear keys, and the
 * submit path. The cell centres come from the C `game_set_size` table
 * in `work/specs/guess.md`, not from the Kotlin helper, so a geometry
 * mistake changes the hit test. The letterboxed cases check that the
 * view origin is subtracted once. A real guess must change the draft
 * and the move count; a UI-only action must change neither the state
 * nor the count.
 */
class GuessInputParityTest {

    private data class Cell(
        val tile: Int,
        val boardWidth: Int,
        val boardHeight: Int,
        val colX: Int,
        val colY: Int,
        val guessX: Int,
        val guessY: Int,
    )

    private val cells = listOf(
        Cell(48, 267, 202, 24, 25, 120, 49),
        Cell(63, 350, 265, 31, 32, 157, 63),
        Cell(82, 456, 345, 41, 41, 205, 82),
        Cell(100, 555, 420, 50, 50, 250, 100),
    )

    @Test
    fun `the board sizes match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
        for (cell in cells) {
            val board = oracle.layout(save, cell.boardWidth, cell.boardHeight)
            assertEquals("width at ${cell.tile}", cell.boardWidth, board.width)
            assertEquals("height at ${cell.tile}", cell.boardHeight, board.height)

            val midend = Midend.newGame(Guess, "c3p2g1bm", SEED)
            val geometry = midend.geometryFor(cell.boardWidth, cell.boardHeight)
            assertEquals("tile at ${cell.tile}", cell.tile, geometry.tileSize)
            assertEquals("Kotlin width at ${cell.tile}", cell.boardWidth, geometry.width)
            assertEquals("Kotlin height at ${cell.tile}", cell.boardHeight, geometry.height)
            assertEquals(0, geometry.originX)
            assertEquals(0, geometry.originY)
        }
    }

    @Test
    fun `letterboxed viewports pick the same board and origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
            val cases = listOf(
                Triple(300, 300, 54),
                Triple(411, 702, 74),
                Triple(720, 1280, 129),
                Triple(1024, 600, 142),
            )
            for ((viewW, viewH, tile) in cases) {
                val board = oracle.layout(save, viewW, viewH)
                val midend = Midend.newGame(Guess, "c3p2g1bm", SEED)
                val geometry = midend.geometryFor(viewW, viewH)
                assertEquals("tile for ${viewW}x$viewH", tile, geometry.tileSize)
                assertEquals("width for ${viewW}x$viewH", board.width, geometry.width)
                assertEquals("height for ${viewW}x$viewH", board.height, geometry.height)
                assertEquals(
                    "origin x for ${viewW}x$viewH",
                    (viewW - board.width) / 2,
                    geometry.originX,
                )
                assertEquals(
                    "origin y for ${viewW}x$viewH",
                    (viewH - board.height) / 2,
                    geometry.originY,
                )
            }
        }

    @Test
    fun `a colour drag is the same click at four tile sizes`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
            for (cell in cells) {
                val tile = cell.tile
                // The gap is the C PEG_GAP multiplier, truncated.
                val gap = (tile * 0.10).toInt()
                val pegOff = tile + gap
                val from = OracleClient.Point(
                    cell.colX + tile / 2,
                    cell.colY + (COLOUR - 1) * pegOff + tile / 2,
                    Buttons.LEFT_BUTTON,
                )
                val to = OracleClient.Point(
                    cell.guessX + PEG * pegOff + tile / 2,
                    cell.guessY + tile / 2,
                    Buttons.LEFT_DRAG,
                )
                val up = OracleClient.Point(to.x, to.y, Buttons.LEFT_RELEASE)
                val expected = oracle.pointsSized(
                    save,
                    listOf(from, to, up),
                    width = cell.boardWidth,
                    height = cell.boardHeight,
                )
                assertEquals(
                    "UI at tile $tile",
                    "0,$COLOUR",
                    SgtpSave.parse(expected.state.state).value("UI"),
                )

                val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
                for (point in listOf(from, to, up)) {
                    midend.processKey(point.x, point.y, point.button, tile)
                }
                assertEquals(
                    "Kotlin UI at tile $tile",
                    "0,$COLOUR",
                    Guess.encodeUi(midend.ui),
                )
                assertEquals(
                    "Kotlin save at tile $tile",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
            }
        }

    @Test
    fun `a colour click is UI only at four tile sizes`() = OracleClient().use { oracle ->
        val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
        for (cell in cells) {
            val pegOff = cell.tile + (cell.tile * 0.10).toInt()
            val press = OracleClient.Point(
                cell.colX + cell.tile / 2,
                cell.colY + (COLOUR - 1) * pegOff + cell.tile / 2,
                Buttons.LEFT_BUTTON,
            )
            val release = OracleClient.Point(press.x, press.y, Buttons.LEFT_RELEASE)
            val expected = oracle.pointsSized(
                save,
                listOf(press, release),
                width = cell.boardWidth,
                height = cell.boardHeight,
            )
            assertEquals("save at tile ${cell.tile}", save, expected.state.state)
            assertEquals("UI at tile ${cell.tile}", "0,0", uiOf(expected.state.state))
            assertEquals(
                "moves at tile ${cell.tile}",
                emptyList<String>(),
                SgtpSave.parse(expected.state.state).values("MOVE"),
            )

            val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
            midend.processKey(press.x, press.y, press.button, cell.tile)
            midend.processKey(release.x, release.y, release.button, cell.tile)
            assertEquals(
                "Kotlin draft at tile ${cell.tile}",
                "0,0",
                Guess.encodeUi(midend.ui),
            )
            assertEquals(
                "Kotlin move count at tile ${cell.tile}",
                1,
                midend.stateCount,
            )
            assertEquals(
                "Kotlin save at tile ${cell.tile}",
                save,
                midend.serialise().serialise(),
            )
        }
    }

    @Test
    fun `a hint click submits one guess at four tile sizes`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
            // The solution of this seed is 2,2, so the draft wins.
            val draft = oracle.keys(save, listOf(50, 50)).state
            assertEquals("2,2", uiOf(draft))
            assertEquals(emptyList<String>(), SgtpSave.parse(draft).values("MOVE"))

            for (cell in cells) {
                val gap = (cell.tile * 0.10).toInt()
                val pegOff = cell.tile + gap
                // The hint area starts at GUESS_X + GUESS_W + GAP.
                val x = cell.guessX + PEGS * pegOff + gap
                val y = cell.guessY + cell.tile / 2
                val press = OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
                val release = OracleClient.Point(x, y, Buttons.LEFT_RELEASE)
                val expected = oracle.pointsSized(
                    draft,
                    listOf(press, release),
                    width = cell.boardWidth,
                    height = cell.boardHeight,
                )
                assertEquals(
                    "moves at tile ${cell.tile}",
                    listOf("G2,2"),
                    SgtpSave.parse(expected.state.state).values("MOVE"),
                )
                assertEquals(
                    "UI at tile ${cell.tile}",
                    "0,0",
                    uiOf(expected.state.state),
                )
                assertEquals("status at tile ${cell.tile}", 1, expected.state.status)

                val midend = Midend.fromSave(Guess, SgtpSave.parse(draft))
                assertEquals(
                    "draft before tile ${cell.tile}",
                    "2,2",
                    Guess.encodeUi(midend.ui),
                )
                assertEquals(1, midend.stateCount)
                midend.processKey(press.x, press.y, press.button, cell.tile)
                midend.processKey(release.x, release.y, release.button, cell.tile)
                // A real guess changes the draft and adds one state.
                assertEquals(
                    "draft after tile ${cell.tile}",
                    "0,0",
                    Guess.encodeUi(midend.ui),
                )
                assertEquals(
                    "move count after tile ${cell.tile}",
                    2,
                    midend.stateCount,
                )
                assertEquals(
                    "Kotlin save at tile ${cell.tile}",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
            }
        }

    @Test
    fun `draft, hold, and clear keys are UI only`() = OracleClient().use { oracle ->
        val start = oracle.newGame("guess", SEED_PARAMS, SEED).state
        // Digits draft, the left key moves to an occupied peg, the
        // second select holds it, and backspace clears it.
        val cases = listOf(
            listOf(49, 50) to "1,2,0,0",
            listOf(49, 50, Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT2) to
                "1,2_,0,0",
            listOf(
                49, 50, Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT2, 8,
            ) to "1,0_,0,0",
        )
        for ((sequence, draft) in cases) {
            val expected = oracle.keys(start, sequence)
            val midend = Midend.fromSave(Guess, SgtpSave.parse(start))
            for (key in sequence) {
                midend.processKey(0, 0, key, TILE)
            }
            assertEquals("UI after $sequence", draft, uiOf(expected.state))
            assertEquals("Kotlin UI after $sequence", draft, Guess.encodeUi(midend.ui))
            assertEquals(
                "no move after $sequence",
                emptyList<String>(),
                SgtpSave.parse(expected.state).values("MOVE"),
            )
            assertEquals("move count after $sequence", 1, midend.stateCount)
            assertSame("keys $sequence", expected, midend)
        }

        // A full draft submits with the select key and adds one move.
        val full = listOf(49, 50, 51, 52, Buttons.CURSOR_SELECT)
        val expected = oracle.keys(start, full)
        val midend = Midend.fromSave(Guess, SgtpSave.parse(start))
        for (key in full.dropLast(1)) {
            midend.processKey(0, 0, key, TILE)
        }
        assertEquals("draft before the submit", "1,2,3,4", Guess.encodeUi(midend.ui))
        midend.processKey(0, 0, full.last(), TILE)
        assertEquals(
            "move after $full",
            listOf("G1,2,3,4"),
            SgtpSave.parse(expected.state).values("MOVE"),
        )
        assertEquals("draft after $full", "0,0,0,0", Guess.encodeUi(midend.ui))
        assertEquals("move count after $full", 2, midend.stateCount)
        assertSame("keys $full", expected, midend)
    }

    @Test
    fun `a letterboxed drag subtracts the origin once`() = OracleClient().use { oracle ->
        val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
        // Tile 54: board 300x227, origin (0, 36), colx 27, coly 27,
        // guessx 135, guessy 54.
        // Tile 74: board 411x311, origin (0, 195), colx 37, coly 37,
        // guessx 185, guessy 74.
        val views = listOf(
            View(300, 300, 54, 300, 227, 0, 36, 27, 27, 135, 54),
            View(411, 702, 74, 411, 311, 0, 195, 37, 37, 185, 74),
        )
        for (view in views) {
            val gap = (view.tile * 0.10).toInt()
            val pegOff = view.tile + gap
            val from = OracleClient.Point(
                view.originX + view.colX + view.tile / 2,
                view.originY + view.colY + (COLOUR - 1) * pegOff + view.tile / 2,
                Buttons.LEFT_BUTTON,
            )
            val to = OracleClient.Point(
                view.originX + view.guessX + PEG * pegOff + view.tile / 2,
                view.originY + view.guessY + view.tile / 2,
                Buttons.LEFT_DRAG,
            )
            val up = OracleClient.Point(to.x, to.y, Buttons.LEFT_RELEASE)
            val expected = oracle.pointsSized(
                save,
                listOf(from, to, up),
                width = view.viewW,
                height = view.viewH,
            )
            assertEquals(
                "UI at ${view.viewW}x${view.viewH}",
                "0,$COLOUR",
                uiOf(expected.state.state),
            )

            val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
            // The shell passes game coordinates, so it subtracts the origin.
            for (point in listOf(from, to, up)) {
                midend.processKey(
                    point.x - view.originX,
                    point.y - view.originY,
                    point.button,
                    view.tile,
                )
            }
            assertEquals(
                "Kotlin UI at ${view.viewW}x${view.viewH}",
                "0,$COLOUR",
                Guess.encodeUi(midend.ui),
            )
            assertEquals(
                "Kotlin save at ${view.viewW}x${view.viewH}",
                expected.state.state,
                midend.serialise().serialise(),
            )
        }
    }

    /** One viewport, the tile size C picks, and the C geometry cells. */
    private data class View(
        val viewW: Int,
        val viewH: Int,
        val tile: Int,
        val boardWidth: Int,
        val boardHeight: Int,
        val originX: Int,
        val originY: Int,
        val colX: Int,
        val colY: Int,
        val guessX: Int,
        val guessY: Int,
    )

    private fun uiOf(state: String): String =
        requireNotNull(SgtpSave.parse(state).value("UI")) { "save has no UI record" }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"
        const val SEED_PARAMS = "c6p4g10Bm"
        const val COLOUR = 2
        const val PEG = 1
        const val PEGS = 2
        const val TILE = 48
    }
}
