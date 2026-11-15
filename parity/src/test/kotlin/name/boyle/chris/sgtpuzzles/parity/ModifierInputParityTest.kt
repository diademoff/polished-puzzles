package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SameGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Games: fifteen and samegame.
 *
 * The oracle sends the modified integer to `midend_process_key`, which
 * filters modifiers before it calls the game. This test pins the same
 * input through the Kotlin seam: the midend passes the raw button to the
 * game, the game ignores the modifiers, and the result is the save the C
 * midend produces.
 *
 * `MOD_CTRL` is not used here. The C midend rejects `CTRL` on a key that
 * is not a cursor move before the game sees it, so it has no oracle
 * result to compare; the engine tests pin that case instead.
 */
class ModifierInputParityTest {

    @Test
    fun `a modified click matches the oracle at a resized view`() =
        OracleClient().use { oracle ->
            for ((viewWidth, viewHeight) in viewports) {
                val start = oracle.newGame("fifteen", PARAMS, SEED)
                val probe = Midend.newGame(Fifteen, PARAMS, SEED)
                val layout = oracle.layout(start.state, viewWidth, viewHeight)
                val geometry = probe.geometryFor(viewWidth, viewHeight)
                val state = probe.state as Fifteen.State
                val tile = geometry.tileSize
                val where = "$PARAMS in $viewWidth x $viewHeight (tile $tile)"

                assertEquals("width $where", layout.width, geometry.width)
                assertEquals("height $where", layout.height, geometry.height)

                // A cell in the same row or column as the gap slides.
                val gapX = state.gapPos % state.width
                val gapY = state.gapPos / state.width
                val neighbour = listOf(
                    gapX + 1 to gapY,
                    gapX - 1 to gapY,
                    gapX to gapY + 1,
                    gapX to gapY - 1,
                ).first { (column, row) ->
                    column in 0 until state.width && row in 0 until state.height
                }
                val viewX = geometry.originX + neighbour.first * tile + 2 * (tile / 2)
                val viewY = geometry.originY + neighbour.second * tile + 2 * (tile / 2)
                val gameX = geometry.toGameX(viewX)
                val gameY = geometry.toGameY(viewY)

                val plain = oracle.keySized(
                    start.state, viewX, viewY, Buttons.LEFT_BUTTON, viewWidth, viewHeight,
                )
                val modifiedButton = Buttons.LEFT_BUTTON or Buttons.MOD_SHFT
                val expected = oracle.keySized(
                    start.state, viewX, viewY, modifiedButton, viewWidth, viewHeight,
                )
                assertEquals(
                    "the oracle ignores MOD_SHFT on a click $where",
                    plain.state,
                    expected.state,
                )

                val actual = Midend.newGame(Fifteen, PARAMS, SEED)
                assertEquals(
                    "outcome $where",
                    KeyOutcome.SomeEffect,
                    actual.processKey(gameX, gameY, modifiedButton, tile),
                )
                assertSame("modified click $where", expected, actual)
                assertTrue("the click must add history $where", actual.canUndo)
                assertNotEquals(
                    "the click must change the save $where",
                    start.state,
                    actual.serialise().serialise(),
                )

                // MOD_NUM_KEYPAD is only kept on a digit, so C drops it
                // on a click as well.
                val padButton = Buttons.LEFT_BUTTON or Buttons.MOD_NUM_KEYPAD
                val padded = oracle.keySized(
                    start.state, viewX, viewY, padButton, viewWidth, viewHeight,
                )
                assertEquals(
                    "the oracle ignores MOD_NUM_KEYPAD on a click $where",
                    plain.state,
                    padded.state,
                )
                val padActual = Midend.newGame(Fifteen, PARAMS, SEED)
                assertEquals(
                    "pad outcome $where",
                    KeyOutcome.SomeEffect,
                    padActual.processKey(gameX, gameY, padButton, tile),
                )
                assertSame("pad click $where", padded, padActual)
            }
        }

    @Test
    fun `a modified cursor move matches the oracle`() = OracleClient().use { oracle ->
        val sequence = listOf(
            Buttons.CURSOR_LEFT or Buttons.MOD_SHFT,
            Buttons.CURSOR_LEFT or Buttons.MOD_SHFT,
            Buttons.CURSOR_UP or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_DOWN or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
        )
        var expected = oracle.newGame("fifteen", PARAMS, SEED)
        val actual = Midend.newGame(Fifteen, PARAMS, SEED)

        for ((index, key) in sequence.withIndex()) {
            expected = oracle.key(expected.state, 0, 0, key)
            actual.processKey(0, 0, key, Fifteen.preferredTileSize)
            assertSame("cursor key $index", expected, actual)
        }
        assertTrue("the cursor moves must add history", actual.canUndo)
    }

    @Test
    fun `two modified taps match the oracle`() = OracleClient().use { oracle ->
        for (case in sameGameViewports) {
            val start = oracle.newGame("samegame", SAME_GAME_PARAMS, SEED)
            val probe = Midend.newGame(SameGame, SAME_GAME_PARAMS, SEED)
            val state = probe.state as SameGame.State
            val target = adjacentPair(state) ?: error("no adjacent pair of tiles")
            val geometry = probe.geometryFor(case.first, case.second)
            val tile = geometry.tileSize
            val viewX = geometry.originX + (target.first + 1) * tile
            val viewY = geometry.originY + (target.second + 1) * tile
            val where = "${case.first} x ${case.second} at ($viewX,$viewY) tile $tile"

            val button = Buttons.LEFT_BUTTON or Buttons.MOD_SHFT
            val expected = oracle.pointsSized(
                start.state,
                listOf(
                    OracleClient.Point(viewX, viewY, button),
                    OracleClient.Point(viewX, viewY, button),
                ),
                case.first,
                case.second,
            )
            val plain = oracle.pointsSized(
                start.state,
                listOf(
                    OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                    OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                ),
                case.first,
                case.second,
            )
            assertEquals(
                "the oracle ignores MOD_SHFT on a tap $where",
                plain.state.state,
                expected.state.state,
            )
            assertTrue("the oracle removes a group $where", expected.state.canUndo)

            val actual = Midend.newGame(SameGame, SAME_GAME_PARAMS, SEED)
            val gameX = geometry.toGameX(viewX)
            val gameY = geometry.toGameY(viewY)
            assertEquals(
                "select outcome $where",
                KeyOutcome.SomeEffect,
                actual.processKey(gameX, gameY, button, tile),
            )
            assertEquals(
                "remove outcome $where",
                KeyOutcome.SomeEffect,
                actual.processKey(gameX, gameY, button, tile),
            )
            assertSame("two modified taps $where", expected.state, actual)
            assertTrue("the taps must add history $where", actual.canUndo)
        }
    }

    @Test
    fun `a modified cursor select matches the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("samegame", SAME_GAME_PARAMS, SEED)
        val probe = Midend.newGame(SameGame, SAME_GAME_PARAMS, SEED)
        val state = probe.state as SameGame.State
        val (column, row) = adjacentPair(state) ?: error("no adjacent pair of tiles")
        val tile = SameGame.preferredTileSize

        val sequence = ArrayList<Int>()
        repeat(column) { sequence += Buttons.CURSOR_RIGHT }
        repeat(row) { sequence += Buttons.CURSOR_DOWN }
        sequence += Buttons.CURSOR_SELECT
        sequence += Buttons.CURSOR_SELECT or Buttons.MOD_SHFT

        val expected = oracle.keys(start.state, sequence)
        val actual = Midend.newGame(SameGame, SAME_GAME_PARAMS, SEED)
        for ((index, key) in sequence.withIndex()) {
            assertEquals(
                "outcome of cursor key $index",
                KeyOutcome.SomeEffect,
                actual.processKey(0, 0, key, tile),
            )
        }
        assertSame("modified cursor select", expected, actual)
        assertTrue("the cursor select must add history", actual.canUndo)
    }

    /** A cell whose right or lower neighbour has the same colour. */
    private fun adjacentPair(state: SameGame.State): Pair<Int, Int>? {
        val w = state.params.width
        val h = state.params.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val colour = state.col(x, y)
                if (colour == 0) continue
                if (x + 1 < w && state.col(x + 1, y) == colour) return x to y
                if (y + 1 < h && state.col(x, y + 1) == colour) return x to y
            }
        }
        return null
    }

    private fun assertSame(label: String, expected: GameState, actual: Midend) {
        assertEquals("save $label", expected.state, actual.serialise().serialise())
        assertEquals("id $label", expected.id, actual.id())
        assertEquals("status $label", expected.status, actual.status())
        assertEquals("undo $label", expected.canUndo, actual.canUndo)
        assertEquals("redo $label", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "4x4"
        const val SAME_GAME_PARAMS = "5x5c3s2"
        val viewports = listOf(320 to 480, 411 to 702, 1024 to 600)
        val sameGameViewports = listOf(411 to 702, 1024 to 600)
    }
}
