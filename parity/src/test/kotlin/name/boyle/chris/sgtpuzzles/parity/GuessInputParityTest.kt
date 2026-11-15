package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: ui. Game: guess.
 *
 * The board geometry and one real pointer drag at four tile sizes. The
 * cell centres come from the C `game_set_size` table in
 * `work/specs/guess.md`, not from the Kotlin helper, so a geometry
 * mistake changes the hit test. The letterboxed cases check that the
 * view origin is subtracted once.
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
    fun `a letterboxed drag subtracts the origin once`() = OracleClient().use { oracle ->
        val save = oracle.newGame("guess", "c3p2g1bm", SEED).state
        // Tile 54, board 300x227, origin (0, 36).
        val viewW = 300
        val viewH = 300
        val tile = 54
        val gap = (tile * 0.10).toInt()
        val pegOff = tile + gap
        val originX = 0
        val originY = 36
        // Tile 54: border 27, coly = (227 - (59 * 3 - 5)) / 2 = 27,
        // guessx = 27 + 108 = 135, guessy = (227 - (59 + 5 + 54)) / 2 = 54.
        val colY = 27
        val guessX = 135
        val guessY = 54
        val from = OracleClient.Point(
            originX + 27 + tile / 2,
            originY + colY + (COLOUR - 1) * pegOff + tile / 2,
            Buttons.LEFT_BUTTON,
        )
        val to = OracleClient.Point(
            originX + guessX + PEG * pegOff + tile / 2,
            originY + guessY + tile / 2,
            Buttons.LEFT_DRAG,
        )
        val up = OracleClient.Point(to.x, to.y, Buttons.LEFT_RELEASE)
        val expected = oracle.pointsSized(
            save,
            listOf(from, to, up),
            width = viewW,
            height = viewH,
        )
        assertEquals("0,2", SgtpSave.parse(expected.state.state).value("UI"))

        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        // The shell passes game coordinates, so it subtracts the origin.
        for (point in listOf(from, to, up)) {
            midend.processKey(point.x - originX, point.y - originY, point.button, tile)
        }
        assertEquals("0,2", Guess.encodeUi(midend.ui))
        assertEquals(expected.state.state, midend.serialise().serialise())
    }

    private companion object {
        const val SEED = "12345"
        const val COLOUR = 2
        const val PEG = 1
    }
}
