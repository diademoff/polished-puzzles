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
                val geometry = midend.geometryFor(viewWidth, viewHeight)
                val tile = geometry.tileSize

                val where = "$params in $viewWidth x $viewHeight (tile $tile)"
                assertEquals("width $where", layout.width, geometry.width)
                assertEquals("height $where", layout.height, geometry.height)

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
                    val x = geometry.originX + column * tile + 2 * (tile / 2)
                    val y = geometry.originY + row * tile + 2 * (tile / 2)
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

    @Test
    fun `swipes match at every view size`() = OracleClient().use { oracle ->
        val cases = listOf(
            "4x4" to listOf(320 to 480, 411 to 702, 720 to 1280, 1024 to 600, 200 to 200),
            "5x5" to listOf(411 to 702, 720 to 1280),
            "3x2" to listOf(411 to 702),
        )

        for ((params, viewports) in cases) {
            val start = oracle.newGame("fifteen", params, SEED)
            for ((viewWidth, viewHeight) in viewports) {
                val midend = Midend.newGame(Fifteen, params, SEED)
                val geometry = midend.geometryFor(viewWidth, viewHeight)
                val state = midend.state as Fifteen.State
                val tile = geometry.tileSize
                val gapX = state.gapPos % state.width
                val gapY = state.gapPos / state.width

                fun centre(column: Int, row: Int) = Pair(
                    geometry.originX + column * tile + 2 * (tile / 2),
                    geometry.originY + row * tile + 2 * (tile / 2),
                )

                val neighbour = listOf(
                    gapX + 1 to gapY,
                    gapX - 1 to gapY,
                    gapX to gapY + 1,
                    gapX to gapY - 1,
                ).first { (column, row) ->
                    column in 0 until state.width && row in 0 until state.height
                }
                val (fromX, fromY) = centre(neighbour.first, neighbour.second)
                val (toX, toY) = centre(gapX, gapY)
                val where = "$params in $viewWidth x $viewHeight (tile $tile)"

                val expected = oracle.gestureSized(
                    start.state, fromX, fromY, toX, toY, 4, viewWidth, viewHeight,
                )
                val actual = Midend.newGame(Fifteen, params, SEED)
                actual.gesture(fromX, fromY, toX, toY, 4, tile)

                assertEquals("save swipe $where", expected.state, actual.serialise().serialise())
                assertEquals("id swipe $where", expected.id, actual.id())
                assertEquals("status swipe $where", expected.status, actual.status())
                assertEquals("undo swipe $where", expected.canUndo, actual.canUndo)
                assertEquals("redo swipe $where", expected.canRedo, actual.canRedo)

                // A swipe that starts on the gap must not move anything.
                val fromGap = oracle.gestureSized(
                    start.state, toX, toY, fromX, fromY, 4, viewWidth, viewHeight,
                )
                val untouched = Midend.newGame(Fifteen, params, SEED)
                untouched.gesture(toX, toY, fromX, fromY, 4, tile)

                assertEquals(
                    "save gap swipe $where",
                    fromGap.state,
                    untouched.serialise().serialise(),
                )
                assertEquals("id gap swipe $where", fromGap.id, untouched.id())
                assertEquals("undo gap swipe $where", fromGap.canUndo, untouched.canUndo)
            }
        }
    }

    /** The Android shell sends DOWN, DRAG events, then UP. */
    private fun Midend.gesture(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        steps: Int,
        tile: Int,
    ) {
        processKey(fromX, fromY, Buttons.LEFT_BUTTON, tile)
        for (i in 1..steps) {
            processKey(
                fromX + (toX - fromX) * i / steps,
                fromY + (toY - fromY) * i / steps,
                Buttons.LEFT_DRAG,
                tile,
            )
        }
        processKey(toX, toY, Buttons.LEFT_RELEASE, tile)
    }

    private companion object {
        const val SEED = "12345"
    }
}
