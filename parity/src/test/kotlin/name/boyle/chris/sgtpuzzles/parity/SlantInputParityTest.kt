package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: slant.
 *
 * `computeSize`, `fromCoord`, and the letterbox origin must agree. The
 * oracle chooses a board size for each viewport, then the test sends the
 * same view points to C and game points to Kotlin, as the app does.
 */
class SlantInputParityTest {

    @Test
    fun `layout and clicks match at every view size`() = OracleClient().use { oracle ->
        val cases = listOf(
            "5x5dh" to listOf(320 to 480, 411 to 702, 720 to 1280, 1024 to 600, 200 to 200),
            "8x8de" to listOf(411 to 702, 720 to 1280),
            "12x10de" to listOf(411 to 702, 1024 to 600),
        )

        for ((params, viewports) in cases) {
            val start = oracle.newGame("slant", params, SEED)
            for ((viewWidth, viewHeight) in viewports) {
                val layout = oracle.layout(start.state, viewWidth, viewHeight)
                val midend = Midend.newGame(Slant, params, SEED)
                val geometry = midend.geometryFor(viewWidth, viewHeight)
                val where = "$params in $viewWidth x $viewHeight (tile ${geometry.tileSize})"

                assertEquals("width $where", layout.width, geometry.width)
                assertEquals("height $where", layout.height, geometry.height)

                var moved = 0
                for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON)) {
                    for (row in 0 until 5) {
                        for (column in 0 until 5) {
                            val x = geometry.originX +
                                (column + 1) * geometry.tileSize + geometry.tileSize / 2
                            val y = geometry.originY +
                                (row + 1) * geometry.tileSize + geometry.tileSize / 2
                            val expected = oracle.keySized(
                                start.state, x, y, button, viewWidth, viewHeight,
                            )
                            val actual = Midend.newGame(Slant, params, SEED)
                            val outcome = actual.processKey(
                                geometry.toGameX(x),
                                geometry.toGameY(y),
                                button,
                                geometry.tileSize,
                            )
                            val what = "click $button ($column,$row) $where"
                            assertEquals("outcome $what", KeyOutcome.SomeEffect, outcome)
                            assertEquals("save $what", expected.state, actual.serialise().serialise())
                            assertEquals("id $what", expected.id, actual.id())
                            assertEquals("status $what", expected.status, actual.status())
                            moved++
                        }
                    }
                }
                assertTrue("no click moved $where", moved > 0)
            }
        }
    }

    @Test
    fun `a swipe changes only the start cell`() = OracleClient().use { oracle ->
        val start = oracle.newGame("slant", "5x5dh", SEED)
        val viewWidth = 1024
        val viewHeight = 600
        val midend = Midend.newGame(Slant, "5x5dh", SEED)
        val geometry = midend.geometryFor(viewWidth, viewHeight)
        val tile = geometry.tileSize
        val fromX = geometry.originX + tile + tile / 2
        val fromY = geometry.originY + tile + tile / 2
        val toX = geometry.originX + 3 * tile + tile / 2
        val toY = geometry.originY + 3 * tile + tile / 2

        val expected = oracle.gestureSized(
            start.state, fromX, fromY, toX, toY, 4, viewWidth, viewHeight,
        )
        val actual = Midend.newGame(Slant, "5x5dh", SEED)
        actual.processKey(
            geometry.toGameX(fromX), geometry.toGameY(fromY),
            Buttons.LEFT_BUTTON, tile,
        )
        for (i in 1..4) {
            actual.processKey(
                geometry.toGameX(fromX + (toX - fromX) * i / 4),
                geometry.toGameY(fromY + (toY - fromY) * i / 4),
                Buttons.LEFT_DRAG,
                tile,
            )
        }
        actual.processKey(
            geometry.toGameX(toX), geometry.toGameY(toY),
            Buttons.LEFT_RELEASE, tile,
        )

        assertEquals("save swipe", expected.state, actual.serialise().serialise())
        assertEquals("id swipe", expected.id, actual.id())
        assertTrue("the swipe did not move", actual.canUndo)
    }

    @Test
    fun `a modified cursor key does not move the cursor`() = OracleClient().use { oracle ->
        val start = oracle.newGame("slant", "4x4", SEED)
        val plain = listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT)
        val modified = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
            Buttons.CURSOR_SELECT,
        )

        // The oracle is the evidence: both sequences select the same
        // cell, so the two modified cursor keys are not cursor moves.
        val expectedPlain = oracle.keys(start.state, plain)
        val expectedModified = oracle.keys(start.state, modified)
        assertEquals(
            "a modified cursor key must not move the slant cursor",
            expectedPlain.state,
            expectedModified.state,
        )
        assertEquals(listOf("\\1,0"), moves(expectedModified.state))

        val tile = Slant.preferredTileSize
        val midend = Midend.newGame(Slant, "4x4", SEED)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile),
        )
        for (key in listOf(
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
        )) {
            assertEquals("outcome for $key", KeyOutcome.Unused, midend.processKey(0, 0, key, tile))
            assertEquals("cursor x for $key", 1, (midend.ui as Slant.Ui).curX)
            assertEquals("cursor y for $key", 0, (midend.ui as Slant.Ui).curY)
        }
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(listOf("\\1,0"), midend.serialise().values("MOVE"))
        assertSame("a modified cursor key", expectedModified, midend)
    }

    private fun moves(save: String): List<String> = SgtpSave.parse(save).values("MOVE")

    private fun assertSame(label: String, expected: GameState, actual: Midend) {
        assertEquals("save $label", expected.state, actual.serialise().serialise())
        assertEquals("id $label", expected.id, actual.id())
        assertEquals("status $label", expected.status, actual.status())
        assertEquals("undo $label", expected.canUndo, actual.canUndo)
        assertEquals("redo $label", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"
    }
}
