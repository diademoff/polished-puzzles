package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Singles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and ui. Game: singles.
 *
 * The layout table, the letterbox band, the half-tile border, the last
 * cell, a lone release and a lone drag, and an overlapping right press.
 * Every "no change" case sits next to a positive control on the same
 * board.
 */
class SinglesInputParityTest {

    private fun assertSame(what: String, expected: GameState, midend: Midend) {
        assertEquals("$what save", expected.state, midend.serialise().serialise())
        assertEquals("$what id", expected.id, midend.id())
        assertEquals("$what status", expected.status, midend.status())
        assertEquals("$what undo", expected.canUndo, midend.canUndo)
        assertEquals("$what redo", expected.canRedo, midend.canRedo)
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tile: Int,
    ): List<KeyOutcome> = points.map { point ->
        midend.processKey(point.x, point.y, point.button, tile)
    }

    @Test(timeout = 600_000)
    fun `the layout matches every viewport`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702,
            192 to 192,
            200 to 200,
            720 to 1280,
            1080 to 1920,
        )
        for (params in oracle.presets("singles").map { it.params }) {
            val save = oracle.newGame("singles", params, SEED).state
            for ((viewWidth, viewHeight) in viewports) {
                val expected = oracle.layout(save, viewWidth, viewHeight)
                val midend = Midend.newGame(Singles, params, SEED)
                val geometry = midend.geometryFor(viewWidth, viewHeight)
                assertEquals(
                    "$params ${viewWidth}x$viewHeight width",
                    expected.width,
                    geometry.width,
                )
                assertEquals(
                    "$params ${viewWidth}x$viewHeight height",
                    expected.height,
                    geometry.height,
                )
                assertEquals(
                    "$params ${viewWidth}x$viewHeight origin x",
                    (viewWidth - expected.width) / 2,
                    geometry.originX,
                )
                assertEquals(
                    "$params ${viewWidth}x$viewHeight origin y",
                    (viewHeight - expected.height) / 2,
                    geometry.originY,
                )
            }
        }

        // The exact tile sizes of the input matrix and the odd-tile
        // truncation.
        for ((tile, board) in listOf(48 to 288, 63 to 377, 82 to 492, 100 to 600)) {
            assertEquals(
                "$tile computes $board",
                board,
                Singles.computeSize(Singles.decodeParams("5x5de"), tile).width,
            )
            val layout = oracle.layout(
                oracle.newGame("singles", "5x5de", SEED).state,
                board,
                board,
            )
            assertEquals("$tile layout", board, layout.width)
        }
        assertEquals(197, Singles.computeSize(Singles.decodeParams("5x5de"), 33).width)
        val odd = Midend.newGame(Singles, "5x5de", SEED).geometryFor(200, 200)
        assertEquals(33, odd.tileSize)
        assertEquals(197, odd.width)
        assertEquals(1, odd.originX)
    }

    @Test(timeout = 300_000)
    fun `the letterbox band toggles the preference and changes no state`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val midend = Midend.newGame(Singles, "5x5de", SEED)
            val geometry = midend.geometryFor(411, 702)
            assertEquals(68, geometry.tileSize)
            assertEquals(408, geometry.width)
            assertEquals(1, geometry.originX)
            assertEquals(147, geometry.originY)

            val band = listOf(
                OracleClient.Point(2, 148, Buttons.LEFT_BUTTON),
                OracleClient.Point(2, 148, Buttons.LEFT_RELEASE),
            )
            val expectedBand = oracle.pointsSized(save, band, 411, 702)
            val outcomes = apply(
                midend,
                listOf(
                    OracleClient.Point(2 - geometry.originX, 148 - geometry.originY, Buttons.LEFT_BUTTON),
                    OracleClient.Point(2 - geometry.originX, 148 - geometry.originY, Buttons.LEFT_RELEASE),
                ),
                geometry.tileSize,
            )
            assertSame("letterbox", expectedBand.state, midend)
            assertEquals(KeyOutcome.SomeEffect, outcomes.first())
            assertEquals(KeyOutcome.Unused, outcomes.last())
            assertTrue(
                "the band click toggles the preference",
                (midend.ui as Singles.Ui).showBlackNums,
            )
            assertEquals(1, midend.stateCount)

            // The positive control: one cell inside the board changes
            // the board and does not toggle the preference again.
            val inside = listOf(
                OracleClient.Point(
                    geometry.originX + geometry.tileSize / 2 + geometry.tileSize / 2,
                    geometry.originY + geometry.tileSize / 2 + geometry.tileSize / 2,
                    Buttons.LEFT_BUTTON,
                ),
                OracleClient.Point(
                    geometry.originX + geometry.tileSize / 2 + geometry.tileSize / 2,
                    geometry.originY + geometry.tileSize / 2 + geometry.tileSize / 2,
                    Buttons.LEFT_RELEASE,
                ),
            )
            val expectedInside = oracle.pointsSized(save, inside, 411, 702)
            val insideOutcomes = apply(
                midend,
                inside.map {
                    OracleClient.Point(
                        it.x - geometry.originX,
                        it.y - geometry.originY,
                        it.button,
                    )
                },
                geometry.tileSize,
            )
            assertSame("inside", expectedInside.state, midend)
            assertEquals(KeyOutcome.SomeEffect, insideOutcomes.first())
            assertEquals(2, midend.stateCount)
            assertTrue("the inside click does not toggle the preference", (midend.ui as Singles.Ui).showBlackNums)
            assertNotEquals(0, midend.serialise().values("MOVE").size)
        }

    @Test(timeout = 300_000)
    fun `the half-tile border, the last cell, and one pixel past it`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val board = Singles.computeSize(Singles.decodeParams("5x5de"), 48).width

            // The first cell starts at the half-tile border (24) and is
            // 48 wide. One pixel before it is the border band.
            for ((name, x) in listOf("border" to 24, "before" to 23)) {
                val points = listOf(
                    OracleClient.Point(x, 48, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, 48, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(save, points, board, board)
                val midend = Midend.newGame(Singles, "5x5de", SEED)
                apply(midend, points, 48)
                assertSame("$name press", expected.state, midend)
                if (name == "before") {
                    assertEquals("the band adds no move", 1, midend.stateCount)
                    assertTrue(
                        "the band toggles the preference",
                        (midend.ui as Singles.Ui).showBlackNums,
                    )
                } else {
                    assertEquals("the first cell adds a move", 2, midend.stateCount)
                    assertEquals("B0,0", midend.serialise().values("MOVE").last())
                }
            }

            // The last cell ends at 263; 264 is one pixel past it.
            for ((name, x) in listOf("last" to 240, "past" to 264)) {
                val points = listOf(
                    OracleClient.Point(x, 48, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, 48, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(save, points, board, board)
                val midend = Midend.newGame(Singles, "5x5de", SEED)
                apply(midend, points, 48)
                assertSame("$name press", expected.state, midend)
                if (name == "past") {
                    assertEquals("past the last cell adds no move", 1, midend.stateCount)
                    assertTrue(
                        "past the last cell toggles the preference",
                        (midend.ui as Singles.Ui).showBlackNums,
                    )
                } else {
                    assertEquals("the last cell adds a move", 2, midend.stateCount)
                    assertEquals("B4,0", midend.serialise().values("MOVE").last())
                }
            }
        }

    @Test(timeout = 300_000)
    fun `a lone release, a lone drag, and an overlapping right press`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val board = Singles.computeSize(Singles.decodeParams("5x5de"), 48).width

            for (button in listOf(Buttons.LEFT_RELEASE, Buttons.LEFT_DRAG)) {
                val points = listOf(OracleClient.Point(72, 72, button))
                val expected = oracle.pointsSized(save, points, board, board)
                val midend = Midend.newGame(Singles, "5x5de", SEED)
                val outcomes = apply(midend, points, 48)
                assertSame("lone $button", expected.state, midend)
                assertEquals(KeyOutcome.Unused, outcomes.single())
                assertEquals(1, midend.stateCount)
            }

            // A right press while the left button is held: the midend
            // invents the left release first, so the right press acts on
            // the cell the left press already marked black.
            val points = listOf(
                OracleClient.Point(72, 72, Buttons.LEFT_BUTTON),
                OracleClient.Point(72, 72, Buttons.RIGHT_BUTTON),
            )
            val expected = oracle.pointsSized(save, points, board, board)
            val midend = Midend.newGame(Singles, "5x5de", SEED)
            val outcomes = apply(midend, points, 48)
            assertSame("overlapping press", expected.state, midend)
            assertEquals(KeyOutcome.SomeEffect, outcomes.first())
            assertEquals(KeyOutcome.SomeEffect, outcomes.last())
            assertEquals(
                listOf("B1,1", "E1,1"),
                midend.serialise().values("MOVE"),
            )
            assertEquals(3, midend.stateCount)
        }

    @Test(timeout = 600_000)
    fun `requestKeys match the oracle on every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("singles").map { it.params } + "") {
                val save = oracle.newGame("singles", params, SEED).state
                val decoded = Singles.decodeParams(params)
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(save, fullCursor)
                    val actual = Singles.requestKeys(decoded, fullCursor)
                    assertEquals(
                        "$params/$fullCursor arrow mode",
                        if (fullCursor) 1 else 3,
                        expected.arrowMode,
                    )
                    assertEquals(
                        "$params/$fullCursor arrow enum",
                        if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
                        actual.arrowMode,
                    )
                    assertEquals(
                        "$params/$fullCursor label count",
                        expected.labels.size,
                        actual.labels.size,
                    )
                    assertEquals("$params/$fullCursor has no label", 0, expected.labels.size)
                    assertEquals("$params/$fullCursor has no label", 0, actual.labels.size)
                    for (i in expected.labels.indices) {
                        assertEquals(
                            "$params/$fullCursor name $i",
                            expected.labels[i].label,
                            actual.labels[i].name,
                        )
                        assertEquals(
                            "$params/$fullCursor button $i",
                            expected.labels[i].button,
                            actual.labels[i].button,
                        )
                        assertEquals(
                            "$params/$fullCursor arrows $i",
                            expected.labels[i].needsArrows,
                            actual.labels[i].needsArrows,
                        )
                    }
                    assertTrue(
                        "$params/$fullCursor no key needs arrows",
                        actual.labels.none { it.needsArrows },
                    )
                }
            }
        }

    @Test(timeout = 600_000)
    fun `the letterbox black and circle cycles match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val midend = Midend.newGame(Singles, "5x5de", SEED)
            val geometry = midend.geometryFor(411, 702)
            assertEquals(68, geometry.tileSize)
            assertEquals(1, geometry.originX)
            assertEquals(147, geometry.originY)

            val steps = listOf(
                Triple(2, 2, Buttons.LEFT_BUTTON) to "B2,2",
                Triple(2, 2, Buttons.RIGHT_BUTTON) to "E2,2",
                Triple(2, 2, Buttons.LEFT_BUTTON) to "B2,2",
                Triple(2, 2, Buttons.LEFT_BUTTON) to "C2,2",
                Triple(2, 2, Buttons.RIGHT_BUTTON) to "B2,2",
                Triple(2, 2, Buttons.RIGHT_BUTTON) to "E2,2",
                Triple(2, 2, Buttons.RIGHT_BUTTON) to "C2,2",
                Triple(2, 2, Buttons.LEFT_BUTTON) to "E2,2",
            )
            val cumulative = ArrayList<OracleClient.Point>()
            var previous = midend.textFormat()
            for ((index, entry) in steps.withIndex()) {
                val (cell, token) = entry
                val (cx, cy, button) = cell
                val tile = geometry.tileSize
                val x = geometry.originX + tile / 2 + cx * tile + tile / 2
                val y = geometry.originY + tile / 2 + cy * tile + tile / 2
                val release = when (button) {
                    Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
                    Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
                    else -> button
                }
                val points = listOf(
                    OracleClient.Point(x, y, button),
                    OracleClient.Point(x, y, release),
                )
                cumulative += points
                val expected = oracle.pointsSized(save, cumulative, 411, 702)
                val outcomes = apply(
                    midend,
                    points.map {
                        OracleClient.Point(
                            it.x - geometry.originX,
                            it.y - geometry.originY,
                            it.button,
                        )
                    },
                    tile,
                )
                assertSame("letterbox step $index", expected.state, midend)
                assertEquals(
                    "letterbox step $index press",
                    KeyOutcome.SomeEffect,
                    outcomes.first(),
                )
                assertEquals("letterbox step $index release", KeyOutcome.Unused, outcomes.last())
                assertEquals(
                    "letterbox step $index token",
                    token,
                    midend.serialise().values("MOVE").last(),
                )
                assertEquals("letterbox step $index count", index + 2, midend.stateCount)
                assertNotEquals(
                    "letterbox step $index alters the board",
                    previous,
                    midend.textFormat(),
                )
                assertEquals("letterbox step $index keeps the status", 0, midend.status())
                previous = midend.textFormat()
            }
        }

    private companion object {
        const val SEED = "12345"
    }
}
