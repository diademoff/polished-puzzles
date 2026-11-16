package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: tents.
 *
 * Layout at the exact board sizes and at letterboxed viewports, a real
 * press/release on a blank cell at tiles 48, 63, 82, and 100 and in the
 * 411x702 view, the `FROMCOORD` bands, and the vacuity guards: a real
 * batch changes the grid and adds one state, an inert click changes
 * neither.
 */
class TentsInputParityTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }

    private fun midend(save: String): Midend =
        Midend.fromSave(Tents, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `layout picks the exact tiles and a letterbox origin`() =
        OracleClient().use { oracle ->
            val eight = oracle.newGame("tents", "8x8de", SEED).state
            for ((view, tile) in listOf(
                480 to 48,
                629 to 63,
                820 to 82,
                1000 to 100,
            )) {
                val expected = oracle.layout(eight, view, view)
                val geometry = midend(eight).geometryFor(view, view)
                assertEquals("$view tile", tile, geometry.tileSize)
                assertEquals("$view width", expected.width, geometry.width)
                assertEquals("$view height", expected.height, geometry.height)
                assertEquals("$view origin x", 0, geometry.originX)
                assertEquals("$view origin y", 0, geometry.originY)
            }

            // Odd tiles truncate the borders: 63 gives 629, 41 gives 409.
            assertEquals(629, Tents.computeSize(Tents.decodeParams("8x8de"), 63).width)
            assertEquals(409, Tents.computeSize(Tents.decodeParams("8x8de"), 41).width)

            for ((params, tile, board) in listOf(
                Triple("8x8de", 41, 409),
                Triple("10x10de", 34, 408),
                Triple("15x15de", 24, 408),
            )) {
                val save = oracle.newGame("tents", params, SEED).state
                val geometry = midend(save).geometryFor(411, 702)
                val expected = oracle.layout(save, 411, 702)
                assertEquals("$params tile", tile, geometry.tileSize)
                assertEquals("$params width", board, geometry.width)
                assertEquals("$params height", board, geometry.height)
                assertEquals("$params width agrees", expected.width, geometry.width)
                assertEquals("$params height agrees", expected.height, geometry.height)
                assertEquals("$params origin x", (411 - board) / 2, geometry.originX)
                assertEquals("$params origin y", (702 - board) / 2, geometry.originY)
            }

            val wide = oracle.newGame("tents", "8x8de", SEED).state
            assertEquals(72, midend(wide).geometryFor(720, 1280).tileSize)
            assertEquals(280, midend(wide).geometryFor(720, 1280).originY)
            assertEquals(60, midend(wide).geometryFor(1024, 600).tileSize)
            assertEquals(212, midend(wide).geometryFor(1024, 600).originX)
        }

    @Test
    fun `a real click at four tiles and in the letterbox matches the oracle`() =
        OracleClient().use { oracle ->
            for (params in Tents.presets().map { it.params }) {
                val save = oracle.newGame("tents", params, SEED).state
                val state = midend(save).state as Tents.State
                val blank = blankCell(state)

                for (tile in listOf(48, 63, 82, 100)) {
                    val size = state.width * tile + tile / 2 + tile * 3 / 2
                    val points = clickPoints(blank, tile, 0, 0)
                    val expected = oracle.pointsSized(
                        save,
                        points,
                        size,
                        size,
                    ).state
                    val actual = midend(save)
                    val before = (actual.state as Tents.State).grid.copyOf()
                    for (point in points) {
                        assertEquals(
                            "'$params' tile $tile",
                            KeyOutcome.SomeEffect,
                            actual.processKey(point.x, point.y, point.button, tile),
                        )
                    }
                    assertSame("'$params' tile $tile", expected, actual)
                    assertEquals(
                        "'$params' tile $tile adds one move",
                        1,
                        actual.serialise().values("MOVE").size,
                    )
                    assertEquals(2, actual.stateCount)
                    assertFalse(
                        "'$params' tile $tile changes the grid",
                        before.contentEquals((actual.state as Tents.State).grid),
                    )
                }

                // The 411x702 letterbox: tile 41, origin (1,146).
                val geometry = midend(save).geometryFor(411, 702)
                val viewThan = clickPoints(
                    blank,
                    geometry.tileSize,
                    geometry.originX,
                    geometry.originY,
                )
                val expected = oracle.pointsSized(
                    save,
                    viewThan,
                    411,
                    702,
                ).state
                val actual = midend(save)
                for (point in viewThan) {
                    val gameX = point.x - geometry.originX
                    val gameY = point.y - geometry.originY
                    assertEquals(
                        "'$params' letterbox",
                        KeyOutcome.SomeEffect,
                        actual.processKey(gameX, gameY, point.button, geometry.tileSize),
                    )
                }
                assertSame("'$params' letterbox", expected, actual)
                assertEquals(2, actual.stateCount)
            }
        }

    @Test
    fun `a real drag at four tiles and in the letterbox is one move`() =
        OracleClient().use { oracle ->
            for (params in Tents.presets().map { it.params }) {
                val save = oracle.newGame("tents", params, SEED).state
                val state = midend(save).state as Tents.State
                val (start, end) = blankRowRun(state)

                for ((viewWidth, viewHeight, tile) in viewsFor(state.width)) {
                    val geometry = midend(save).geometryFor(viewWidth, viewHeight)
                    val label = "'$params' $viewWidth x $viewHeight"
                    if (tile != null) {
                        assertEquals("$label tile", tile, geometry.tileSize)
                        assertEquals("$label origin x", 0, geometry.originX)
                        assertEquals("$label origin y", 0, geometry.originY)
                    }
                    val points = dragPoints(
                        start,
                        end,
                        geometry.tileSize,
                        geometry.originX,
                        geometry.originY,
                    )
                    val expected = oracle.pointsSized(
                        save,
                        points,
                        viewWidth,
                        viewHeight,
                    ).state
                    val actual = midend(save)
                    for (point in points) {
                        assertEquals(
                            "$label outcome",
                            KeyOutcome.SomeEffect,
                            actual.processKey(
                                point.x - geometry.originX,
                                point.y - geometry.originY,
                                point.button,
                                geometry.tileSize,
                            ),
                        )
                    }
                    assertSame(label, expected, actual)
                    assertEquals(
                        "$label is one move",
                        1,
                        actual.serialise().values("MOVE").size,
                    )
                    assertEquals("$label adds one state", 2, actual.stateCount)
                }
            }
        }

    @Test
    fun `the select cycles run at four tiles and in the letterbox`() =
        OracleClient().use { oracle ->
            for (params in Tents.presets().map { it.params }) {
                val save = oracle.newGame("tents", params, SEED).state
                val cell = blankCell(midend(save).state as Tents.State)

                for ((viewWidth, viewHeight, tile) in viewsFor(
                    (midend(save).state as Tents.State).width,
                )) {
                    val geometry = midend(save).geometryFor(viewWidth, viewHeight)
                    val label = "'$params' $viewWidth x $viewHeight"
                    if (tile != null) assertEquals("$label tile", tile, geometry.tileSize)

                    var expected = save
                    val actual = midend(save)
                    for (round in 1..3) {
                        val points = clickPoints(
                            cell,
                            geometry.tileSize,
                            geometry.originX,
                            geometry.originY,
                        )
                        expected = playCycleRound(
                            "$label left round $round",
                            oracle,
                            expected,
                            points,
                            viewWidth,
                            viewHeight,
                            geometry,
                            actual,
                        )
                        assertEquals(
                            "$label left round $round records",
                            SgtpSave.parse(expected).values("MOVE"),
                            actual.serialise().values("MOVE"),
                        )
                    }
                    assertEquals(
                        "$label left cycle",
                        3,
                        actual.serialise().values("MOVE").size,
                    )

                    // The right button cycles the other way.
                    var expectedRight = save
                    val rightMidend = midend(save)
                    for (round in 1..3) {
                        val points = clickPoints(
                            cell,
                            geometry.tileSize,
                            geometry.originX,
                            geometry.originY,
                        ).map { it.copy(button = if (it.button == Buttons.LEFT_BUTTON) {
                            Buttons.RIGHT_BUTTON
                        } else {
                            Buttons.RIGHT_RELEASE
                        }) }
                        expectedRight = playCycleRound(
                            "$label right round $round",
                            oracle,
                            expectedRight,
                            points,
                            viewWidth,
                            viewHeight,
                            geometry,
                            rightMidend,
                        )
                    }
                    assertEquals(
                        "$label right cycle",
                        3,
                        rightMidend.serialise().values("MOVE").size,
                    )
                }
            }
        }

    /** Applies one click round to both engines and returns the oracle save. */
    private fun playCycleRound(
        label: String,
        oracle: OracleClient,
        save: String,
        points: List<OracleClient.Point>,
        viewWidth: Int,
        viewHeight: Int,
        geometry: BoardGeometry,
        midend: Midend,
    ): String {
        val expected = oracle.pointsSized(save, points, viewWidth, viewHeight).state
        for (point in points) {
            assertEquals(
                label,
                KeyOutcome.SomeEffect,
                midend.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                ),
            )
        }
        assertSame(label, expected, midend)
        return expected.state
    }

    /**
     * The exact board-size viewports at tiles 48, 63, 82, and 100, then
     * the 411x702 letterbox, whose tile is chosen by the midend.
     */
    private fun viewsFor(width: Int): List<Triple<Int, Int, Int?>> =
        listOf(48, 63, 82, 100).map { tile ->
            val size = width * tile + tile / 2 + tile * 3 / 2
            Triple(size, size, tile)
        } + Triple(411, 702, null)

    @Test
    fun `the cell bands follow FROMCOORD`() = OracleClient().use { oracle ->
        // Row 3 is blank at columns 0 and 7 in the golden board. The
        // bands are x = 16..47 for cell 0 and 240..271 for cell 7.
        val save = oracle.newGame("tents", "8x8de", SEED).state
        val y = 3 * TILE + TILE
        for ((x, cell) in listOf(16 to "T0,3", 271 to "T7,3")) {
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points).state
            val actual = midend(save)
            for (point in points) actual.processKey(point.x, point.y, point.button, TILE)
            assertSame("x=$x", expected, actual)
            assertEquals("x=$x", listOf(cell), actual.serialise().values("MOVE"))
        }

        // x = 15 and x = 272 are outside the board and are Unused.
        for (x in listOf(15, 272)) {
            val actual = midend(save)
            assertEquals("x=$x press", KeyOutcome.Unused,
                actual.processKey(x, y, Buttons.LEFT_BUTTON, TILE))
            assertEquals("x=$x release", KeyOutcome.Unused,
                actual.processKey(x, y, Buttons.LEFT_RELEASE, TILE))
            assertEquals(1, actual.stateCount)
            assertTrue(actual.serialise().values("MOVE").isEmpty())
        }

        // The same for the row bands at x = 32: y = 15 is out and 272
        // is out; y = 16 and 271 are rows 0 and 7.
        val x = 0 * TILE + TILE
        for (y2 in listOf(15, 272)) {
            val actual = midend(save)
            assertEquals("y=$y2", KeyOutcome.Unused,
                actual.processKey(x, y2, Buttons.LEFT_BUTTON, TILE))
            assertEquals(1, actual.stateCount)
        }
    }

    @Test
    fun `a press with no release and a middle press add no move`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tents", "8x8de", SEED).state
            val midend = midend(save)
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(32, 32, Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(1, midend.stateCount)
            assertTrue(midend.serialise().values("MOVE").isEmpty())

            val middle = midend(save)
            assertEquals(
                KeyOutcome.Unused,
                middle.processKey(32, 32, Buttons.MIDDLE_BUTTON, TILE),
            )
            assertEquals(
                KeyOutcome.Unused,
                middle.processKey(32, 32, Buttons.MIDDLE_RELEASE, TILE),
            )
            assertEquals(1, middle.stateCount)
            assertFalse(middle.canUndo)
        }

    @Test
    fun `a drag is one move at the release`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tents", "8x8de", SEED).state
        val points = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(48, 32, Buttons.LEFT_DRAG),
            OracleClient.Point(64, 32, Buttons.LEFT_DRAG),
            OracleClient.Point(96, 32, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, points).state
        val actual = midend(save)
        for (point in points) actual.processKey(point.x, point.y, point.button, TILE)
        assertSame("drag", expected, actual)
        assertEquals(listOf("N0,0;N2,0"), actual.serialise().values("MOVE"))
        assertEquals(2, actual.stateCount)
    }

    @Test
    fun `a drag that leaves the board is cancelled`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tents", "8x8de", SEED).state
        val points = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(400, 32, Buttons.LEFT_DRAG),
            OracleClient.Point(400, 32, Buttons.LEFT_RELEASE),
        )
        // The release after leaving is a UI-only cancel.
        val actual = midend(save)
        for (point in points) actual.processKey(point.x, point.y, point.button, TILE)
        assertEquals(1, actual.stateCount)
        assertTrue(actual.serialise().values("MOVE").isEmpty())

        // The same sequence with no out-of-range sample is one move.
        val inside = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 32, Buttons.LEFT_DRAG),
            OracleClient.Point(96, 32, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, inside).state
        val moved = midend(save)
        for (point in inside) moved.processKey(point.x, point.y, point.button, TILE)
        assertSame("inside drag", expected, moved)
        assertEquals(2, moved.stateCount)
    }

    /** The first blank cell in row-major order. */
    private fun blankCell(state: Tents.State): Pair<Int, Int> {
        for (i in state.grid.indices) {
            if (state.grid[i] == 0) return i % state.width to i / state.width
        }
        error("the board has no blank cell")
    }

    /**
     * The first and the last blank cell of a line with at least two
     * blanks. A drag between them marks every blank in the rectangle.
     */
    private fun blankRowRun(
        state: Tents.State,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        for (y in 0 until state.height) {
            val blanks = (0 until state.width).filter {
                state.grid[y * state.width + it] == 0
            }
            if (blanks.size >= 2) return (blanks.first() to y) to (blanks.last() to y)
        }
        for (x in 0 until state.width) {
            val blanks = (0 until state.height).filter {
                state.grid[it * state.width + x] == 0
            }
            if (blanks.size >= 2) return (x to blanks.first()) to (x to blanks.last())
        }
        error("the board has no two blank cells in one line")
    }

    /** Press at [start], drag to [end], and release there. */
    private fun dragPoints(
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
        tile: Int,
        originX: Int,
        originY: Int,
    ): List<OracleClient.Point> {
        fun px(cell: Pair<Int, Int>) = originX + cell.first * tile + tile
        fun py(cell: Pair<Int, Int>) = originY + cell.second * tile + tile
        return listOf(
            OracleClient.Point(px(start), py(start), Buttons.LEFT_BUTTON),
            OracleClient.Point(px(end), py(end), Buttons.LEFT_DRAG),
            OracleClient.Point(px(end), py(end), Buttons.LEFT_RELEASE),
        )
    }

    /** Press and release at the centre of [cell], offset by a letterbox origin. */
    private fun clickPoints(
        cell: Pair<Int, Int>,
        tile: Int,
        originX: Int,
        originY: Int,
    ): List<OracleClient.Point> {
        val x = originX + cell.first * tile + tile / 2 + tile / 2
        val y = originY + cell.second * tile + tile / 2 + tile / 2
        return listOf(
            OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
            OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
        )
    }
}
