package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic. Game: fifteen.
 *
 * `midend_size` and `interpret_move` both depend on the tile size. The
 * ordinary oracle path uses the preferred size, which hid a bug where
 * the app drew at one size and interpreted input at another. This test
 * asks C to choose a size for several viewports, then compares layout
 * and every click at that size.
 */
class FifteenInputParityTest {

    @Test
    fun `layout and clicks match at every view size`() = OracleClient().use { oracle ->
        val cases = listOf(
            "4x4" to listOf(
                320 to 480,
                411 to 702,
                720 to 1280,
                1024 to 600,
                200 to 200,
                50 to 50,
            ),
            "5x5" to listOf(411 to 702, 720 to 1280),
            "6x6" to listOf(411 to 702),
            "3x2" to listOf(411 to 702, 320 to 480),
        )

        for ((params, viewports) in cases) {
            val start = oracle.newGame("fifteen", params, SEED)
            for ((viewWidth, viewHeight) in viewports) {
                val layout = oracle.layout(start.state, viewWidth, viewHeight)
                val midend = Midend.newGame(Fifteen, params, SEED)
                val tile = midend.largestTileFor(viewWidth, viewHeight)
                val size = Fifteen.computeSize(midend.currentParams, tile)

                val where = "$params in $viewWidth x $viewHeight (tile $tile)"
                assertEquals("width $where", layout.width, size.width)
                assertEquals("height $where", layout.height, size.height)

                val state = midend.state as Fifteen.State
                val gapX = state.gapPos % state.width
                val gapY = state.gapPos / state.width
                val cells = listOf(
                    gapX to gapY,
                    gapX + 1 to gapY,
                    gapX - 1 to gapY,
                    gapX to gapY + 1,
                    gapX to gapY - 1,
                    gapX + 1 to gapY + 1,
                )

                for ((column, row) in cells) {
                    if (column !in 0 until state.width || row !in 0 until state.height) continue
                    val x = column * tile + 2 * (tile / 2)
                    val y = row * tile + 2 * (tile / 2)
                    val expected = oracle.keySized(
                        start.state, x, y, Buttons.LEFT_BUTTON, viewWidth, viewHeight,
                    )
                    val actual = Midend.newGame(Fifteen, params, SEED)
                    actual.processKey(x, y, Buttons.LEFT_BUTTON, tile)

                    val click = "click ($column,$row) $where"
                    assertEquals("save $click", expected.state, actual.serialise().serialise())
                    assertEquals("id $click", expected.id, actual.id())
                    assertEquals("status $click", expected.status, actual.status())
                    assertEquals("undo $click", expected.canUndo, actual.canUndo)
                    assertEquals("redo $click", expected.canRedo, actual.canRedo)
                }
            }
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
