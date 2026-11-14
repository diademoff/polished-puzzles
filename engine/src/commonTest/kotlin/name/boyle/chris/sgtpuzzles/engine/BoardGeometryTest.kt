package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: fifteen.
 *
 * The app draws, reads input, places the cursor, and negotiates the tile
 * size through one [BoardGeometry]. These tests pin the arithmetic in
 * that value so a view point cannot be interpreted with another tile
 * size or another origin.
 */
class BoardGeometryTest {

    private val viewports = listOf(
        411 to 702,
        320 to 480,
        720 to 1280,
        1024 to 600,
        200 to 200,
        50 to 50,
    )

    @Test
    fun `geometry fits and centres the board`() {
        for ((viewWidth, viewHeight) in viewports) {
            val midend = Midend.newGame(Fifteen, "4x4", "12345")
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            val where = "$viewWidth x $viewHeight (tile ${geometry.tileSize})"

            assertEquals(
                midend.largestTileFor(viewWidth, viewHeight),
                geometry.tileSize,
                "tile $where",
            )
            val size = Fifteen.computeSize(midend.currentParams, geometry.tileSize)
            assertEquals(size.width, geometry.width, "width $where")
            assertEquals(size.height, geometry.height, "height $where")
            assertTrue(geometry.width <= viewWidth, "board fits width $where")
            assertTrue(geometry.height <= viewHeight, "board fits height $where")
            assertEquals((viewWidth - geometry.width) / 2, geometry.originX, "origin x $where")
            assertEquals((viewHeight - geometry.height) / 2, geometry.originY, "origin y $where")
        }
    }

    @Test
    fun `view coordinates become game coordinates`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val geometry = midend.geometryFor(411, 702)

        assertEquals(0, geometry.toGameX(geometry.originX))
        assertEquals(0, geometry.toGameY(geometry.originY))
        assertEquals(
            geometry.width - 1,
            geometry.toGameX(geometry.originX + geometry.width - 1),
        )
        assertEquals(
            geometry.height - 1,
            geometry.toGameY(geometry.originY + geometry.height - 1),
        )
    }

    @Test
    fun `contains only accepts points on the board`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val geometry = midend.geometryFor(411, 702)

        assertTrue(geometry.contains(geometry.originX, geometry.originY))
        assertTrue(
            geometry.contains(
                geometry.originX + geometry.width - 1,
                geometry.originY + geometry.height - 1,
            ),
        )
        assertFalse(geometry.contains(geometry.originX - 1, geometry.originY))
        assertFalse(geometry.contains(geometry.originX, geometry.originY - 1))
        assertFalse(geometry.contains(geometry.originX + geometry.width, geometry.originY))
        assertFalse(geometry.contains(geometry.originX, geometry.originY + geometry.height))
    }

    @Test
    fun `input uses the geometry on screen`() {
        val desc = "1,2,3,4,5,0,7,8,9,10,11,12,13,14,6,15"
        val id = "4x4:$desc"
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val geometry = midend.geometryFor(411, 702)
        val tile = geometry.tileSize

        // The gap is at (1,1); the tile at (1,2) can move into it.
        val viewX = geometry.originX + 1 * tile + 2 * (tile / 2)
        val viewY = geometry.originY + 2 * tile + 2 * (tile / 2)
        val fresh = Midend.fromGameId(Fifteen, id)
        assertEquals(
            KeyOutcome.SomeEffect,
            fresh.processKey(
                geometry.toGameX(viewX),
                geometry.toGameY(viewY),
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )
        assertTrue(fresh.canUndo)
    }
}
