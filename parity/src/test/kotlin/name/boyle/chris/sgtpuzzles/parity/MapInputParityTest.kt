package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: map.
 *
 * The layout, real left and right drags at four tile sizes and a
 * letterbox viewport, the four quadrants of a diagonally split cell,
 * and the vacuous cases. Every vacuous case sits next to a real drag
 * that does change the board and the state count.
 */
class MapInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun handSave(desc: String, params: String = "5x2n5dn"): String =
        "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :3:Map\n" +
            "PARAMS  :${params.length}:$params\n" +
            "CPARAMS :${params.length}:$params\n" +
            "SEED    :5:12345\n" +
            "DESC    :${desc.length}:$desc\n" +
            "NSTATES :1:1\n" +
            "STATEPOS:1:1\n"

    @Test(timeout = 120_000)
    fun `the layout matches the oracle at several viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("map", "20x15n30dn", SEED).state
            val midend = Midend.newGame(Map, "20x15n30dn", SEED)
            for (viewport in listOf(
                411 to 702,
                781 to 781,
                1080 to 1920,
                320 to 480,
                200 to 200,
                440 to 340,
            )) {
                val expected = oracle.layout(save, viewport.first, viewport.second)
                val geometry = midend.geometryFor(viewport.first, viewport.second)
                assertEquals(
                    "layout ${viewport.first}x${viewport.second}",
                    "${expected.width}x${expected.height}",
                    "${geometry.width}x${geometry.height}",
                )
                assertEquals(
                    "origin ${viewport.first}x${viewport.second}",
                    (viewport.first - geometry.width) / 2,
                    geometry.originX,
                )
                assertTrue(geometry.tileSize >= 1)
            }
        }

    @Test(timeout = 300_000)
    fun `a real left drag at several tiles matches the oracle`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            val p = Map.decodeParams("5x2n5")
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Map.computeSize(p, tile)
                val points = listOf(
                    OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.LEFT_BUTTON),
                    OracleClient.Point(
                        5 * tile + tile / 2,
                        tile + tile / 2,
                        Buttons.LEFT_RELEASE,
                    ),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                assertEquals("tile $tile move", "0:4", SgtpSave.parse(expected.state.state).value("MOVE"))
                assertEquals("tile $tile status", 1, expected.state.status)

                val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                for (point in points) {
                    midend.processKey(point.x, point.y, point.button, tile)
                }
                assertEquals("tile $tile id", expected.state.id, midend.id())
                assertEquals(
                    "tile $tile canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("tile $tile states", 2, midend.stateCount)
                assertEquals("tile $tile status", 1, midend.status())
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tile),
                    upstream,
                )
                assertLines(
                    "tile $tile trace",
                    TraceNormalizer.normalize(expected.trace!!, upstream),
                    actual,
                )
            }
        }

    @Test(timeout = 120_000)
    fun `a letterboxed drag subtracts the origin`() = OracleClient().use { oracle ->
        val save = handSave("fh,0123a")
        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(58, geometry.tileSize)
        assertEquals(2, geometry.originX)
        assertEquals(234, geometry.originY)

        val tile = geometry.tileSize
        val gamePoints = listOf(
            ButtonPoint(tile + tile / 2, tile + tile / 2, Buttons.LEFT_BUTTON),
            ButtonPoint(5 * tile + tile / 2, tile + tile / 2, Buttons.LEFT_RELEASE),
        )
        val viewPoints = gamePoints.map {
            OracleClient.Point(
                it.x + geometry.originX,
                it.y + geometry.originY,
                it.button,
            )
        }
        val expected = oracle.pointsSized(
            save,
            viewPoints,
            width = 411,
            height = 702,
            recordTrace = true,
        )
        assertEquals("0:4", SgtpSave.parse(expected.state.state).value("MOVE"))
        assertEquals(1, expected.state.status)
        for (point in gamePoints) {
            midend.processKey(point.x, point.y, point.button, tile)
        }
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertLines(
            "letterbox drag",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, tile), upstream),
        )
    }

    @Test(timeout = 120_000)
    fun `the four quadrants of a split cell select two regions`() =
        OracleClient().use { oracle ->
            // The frozen 20x15 board has a diagonal at cell (3,3). The
            // bottom region of cell (2,0) is colour 1. The four quarter
            // clicks pick the region on that side: the bottom and right
            // quarters are region 12, the left and top are region 8.
            val save = oracle.newGame("map", "20x15n30dn", SEED).state
            val sourceX = 20 + 2 * 20 + 10
            val sourceY = 20 + 0 * 20 + 10
            val cellX = 20 + 3 * 20
            val cellY = 20 + 3 * 20
            val quadrants = listOf(
                Quadrant("BE", 10, 10, 12),
                Quadrant("RE", 15, 10, 12),
                Quadrant("LE", 5, 10, 8),
                Quadrant("TE", 10, 5, 8),
            )
            for (quadrant in quadrants) {
                val name = quadrant.name
                val dx = quadrant.dx
                val dy = quadrant.dy
                val region = quadrant.region
                val points = listOf(
                    OracleClient.Point(sourceX, sourceY, Buttons.LEFT_BUTTON),
                    OracleClient.Point(cellX + dx, cellY + dy, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = 441,
                    height = 341,
                )
                assertEquals(
                    "$name quadrant move",
                    "1:$region",
                    SgtpSave.parse(expected.state.state).value("MOVE"),
                )
                val fresh = Midend.fromSave(Map, SgtpSave.parse(save))
                for (point in points) {
                    fresh.processKey(point.x, point.y, point.button, 20)
                }
                assertEquals(
                    "$name quadrant save",
                    expected.state.state,
                    fresh.serialise().serialise(),
                )
                assertEquals("$name quadrant states", 2, fresh.stateCount)
            }

            // Left and top land in different regions from bottom and
            // right, so the test is not vacuous.
            assertTrue(
                "the cell is split",
                quadrants.map { it.region }.distinct().size == 2,
            )
        }

    @Test(timeout = 120_000)
    fun `a right drag gives a pencil move and a left drag gives a colour`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            for ((button, expectedMove) in listOf(
                Buttons.LEFT_BUTTON to "0:4",
                Buttons.RIGHT_BUTTON to "p0:4",
            )) {
                val release = if (button == Buttons.LEFT_BUTTON) {
                    Buttons.LEFT_RELEASE
                } else {
                    Buttons.RIGHT_RELEASE
                }
                val points = listOf(
                    OracleClient.Point(30, 30, button),
                    OracleClient.Point(110, 30, release),
                )
                val expected = oracle.pointsSized(save, points, width = 141, height = 81)
                assertEquals(expectedMove, SgtpSave.parse(expected.state.state).value("MOVE"))

                val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                for (point in points) {
                    midend.processKey(point.x, point.y, point.button, 20)
                }
                assertEquals(expected.state.state, midend.serialise().serialise())
                assertEquals(2, midend.stateCount)
            }

            // The second right drag toggles the same pencil bit back.
            val first = Midend.fromSave(Map, SgtpSave.parse(save))
            val rightPoints = listOf(
                OracleClient.Point(30, 30, Buttons.RIGHT_BUTTON),
                OracleClient.Point(110, 30, Buttons.RIGHT_RELEASE),
            )
            for (point in rightPoints) first.processKey(point.x, point.y, point.button, 20)
            val expected = oracle.pointsSized(
                first.serialise().serialise(),
                rightPoints,
                width = 141,
                height = 81,
            )
            for (point in rightPoints) first.processKey(point.x, point.y, point.button, 20)
            assertEquals(expected.state.state, first.serialise().serialise())
            assertEquals(3, first.stateCount)
        }

    @Test(timeout = 120_000)
    fun `an inert click changes neither the board nor the count`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")

            // A border click, a same-region click, a lone release, and a
            // drag into the border.
            val inert = listOf(
                listOf(
                    OracleClient.Point(5, 5, Buttons.LEFT_BUTTON),
                    OracleClient.Point(5, 5, Buttons.LEFT_RELEASE),
                ),
                listOf(
                    OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                    OracleClient.Point(30, 30, Buttons.LEFT_RELEASE),
                ),
                listOf(
                    OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
                ),
                listOf(
                    OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                    OracleClient.Point(5, 5, Buttons.LEFT_RELEASE),
                ),
            )
            for (points in inert) {
                val expected = oracle.pointsSized(save, points, width = 141, height = 81)
                val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                for (point in points) {
                    midend.processKey(point.x, point.y, point.button, 20)
                }
                assertEquals("inert $points", save, midend.serialise().serialise())
                assertEquals("inert $points oracle", expected.state.state, midend.serialise().serialise())
                assertEquals("inert $points count", 1, midend.stateCount)
                assertEquals("inert $points status", 0, midend.status())
            }

            // The positive control next to every inert case.
            val real = listOf(
                OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
            )
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            for (point in real) {
                midend.processKey(point.x, point.y, point.button, 20)
            }
            assertEquals(2, midend.stateCount)
            assertEquals(1, midend.status())
            assertTrue(midend.serialise().serialise() != save)
        }

    @Test(timeout = 120_000)
    fun `a tap and a swipe with intermediate drags are the same move`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            val drags = listOf(
                listOf(
                    OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                    OracleClient.Point(50, 30, Buttons.LEFT_DRAG),
                    OracleClient.Point(70, 30, Buttons.LEFT_DRAG),
                    OracleClient.Point(90, 30, Buttons.LEFT_DRAG),
                    OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
                ),
            )
            for (points in drags) {
                val expected = oracle.pointsSized(save, points, width = 141, height = 81)
                val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                for (point in points) {
                    midend.processKey(point.x, point.y, point.button, 20)
                }
                assertEquals("0:4", midend.serialise().value("MOVE"))
                assertEquals(expected.state.state, midend.serialise().serialise())
                assertEquals(1, midend.status())
            }
        }

    @Test(timeout = 120_000)
    fun `unknown keys and modified arrows are unused`() {
        val save = handSave("fh,0123a")
        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        for (key in listOf(
            '1'.code,
            '\t'.code,
            '\b'.code,
            'z'.code,
            0x7F,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
            Buttons.CURSOR_DOWN or Buttons.MOD_SHFT,
        )) {
            assertEquals("key $key", KeyOutcome.Unused, midend.processKey(0, 0, key, 20))
            assertEquals(1, midend.stateCount)
        }
        // The positive control: a plain arrow is a UI update.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 20),
        )
        assertTrue(midend.stateCount == 1)
        assertFalse(midend.canUndo)
    }

    @Test(timeout = 300_000)
    fun `real recolours and pencil drags match at every tile and the letterbox`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            for (view in viewports(save)) {
                val tile = view.tile
                val name = "${view.width}x${view.height} tile $tile"
                for (drag in listOf(
                    Drag(
                        "recolour",
                        Buttons.LEFT_BUTTON,
                        Buttons.LEFT_RELEASE,
                        "0:4",
                        1,
                    ),
                    Drag(
                        "pencil",
                        Buttons.RIGHT_BUTTON,
                        Buttons.RIGHT_RELEASE,
                        "p0:4",
                        0,
                    ),
                )) {
                    val gamePoints = listOf(
                        ButtonPoint(tile + tile / 2, tile + tile / 2, drag.press),
                        ButtonPoint(5 * tile + tile / 2, tile + tile / 2, drag.release),
                    )
                    val viewPoints = gamePoints.map {
                        OracleClient.Point(
                            it.x + view.originX,
                            it.y + view.originY,
                            it.button,
                        )
                    }
                    val expected = oracle.pointsSized(
                        save,
                        viewPoints,
                        width = view.width,
                        height = view.height,
                        recordTrace = true,
                    )
                    assertEquals(
                        "$name ${drag.what} move",
                        drag.move,
                        SgtpSave.parse(expected.state.state).value("MOVE"),
                    )
                    assertEquals("$name ${drag.what} status", drag.status, expected.state.status)

                    val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                    for (point in gamePoints) {
                        midend.processKey(point.x, point.y, point.button, tile)
                    }
                    assertEquals("$name ${drag.what} id", expected.state.id, midend.id())
                    assertEquals(
                        "$name ${drag.what} canonical",
                        expected.state.state,
                        midend.serialise().serialise(),
                    )
                    assertEquals("$name ${drag.what} history", 2, midend.stateCount)
                    assertTrue("$name ${drag.what} can undo", midend.canUndo)
                    assertFalse("$name ${drag.what} can redo", midend.canRedo)
                    assertEquals("$name ${drag.what} status", drag.status, midend.status())
                    assertNotEquals(
                        "$name ${drag.what} changes the board",
                        save,
                        midend.serialise().serialise(),
                    )
                    assertLines(
                        "$name ${drag.what} trace",
                        TraceNormalizer.normalize(expected.trace!!, upstream),
                        TraceNormalizer.normalize(
                            KotlinTraceSink.stateTrace(midend, tile),
                            upstream,
                        ),
                    )
                }
            }
        }

    @Test(timeout = 300_000)
    fun `inert clicks change nothing at every tile and the letterbox`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            for (view in viewports(save)) {
                val tile = view.tile
                val border = tile / 2
                val center = tile + tile / 2
                val last = 5 * tile + tile / 2
                val name = "${view.width}x${view.height} tile $tile"
                val cases = listOf(
                    "border" to listOf(
                        ButtonPoint(border, border, Buttons.LEFT_BUTTON),
                        ButtonPoint(border, border, Buttons.LEFT_RELEASE),
                    ),
                    "same region" to listOf(
                        ButtonPoint(center, center, Buttons.LEFT_BUTTON),
                        ButtonPoint(center, center, Buttons.LEFT_RELEASE),
                    ),
                    "lone release" to listOf(
                        ButtonPoint(last, center, Buttons.LEFT_RELEASE),
                    ),
                    "drag into the border" to listOf(
                        ButtonPoint(center, center, Buttons.LEFT_BUTTON),
                        ButtonPoint(border, border, Buttons.LEFT_RELEASE),
                    ),
                )
                for ((what, gamePoints) in cases) {
                    val viewPoints = gamePoints.map {
                        OracleClient.Point(
                            it.x + view.originX,
                            it.y + view.originY,
                            it.button,
                        )
                    }
                    val expected = oracle.pointsSized(
                        save,
                        viewPoints,
                        width = view.width,
                        height = view.height,
                    )
                    val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                    for (point in gamePoints) {
                        midend.processKey(point.x, point.y, point.button, tile)
                    }
                    assertEquals("$name $what oracle", expected.state.state, save)
                    assertEquals("$name $what state", save, midend.serialise().serialise())
                    assertEquals("$name $what count", 1, midend.stateCount)
                    assertEquals("$name $what status", 0, midend.status())
                    assertEquals("$name $what id", expected.state.id, midend.id())
                }

                // A click in the letterbox band is outside the grid.
                if (view.originX > 0 || view.originY > 0) {
                    val viewPoint = OracleClient.Point(1, 1, Buttons.LEFT_BUTTON)
                    val bandPoints = listOf(
                        viewPoint,
                        viewPoint.copy(button = Buttons.LEFT_RELEASE),
                    )
                    val gamePoints = bandPoints.map {
                        ButtonPoint(
                            it.x - view.originX,
                            it.y - view.originY,
                            it.button,
                        )
                    }
                    val expected = oracle.pointsSized(
                        save,
                        bandPoints,
                        width = view.width,
                        height = view.height,
                    )
                    val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                    for (point in gamePoints) {
                        midend.processKey(point.x, point.y, point.button, tile)
                    }
                    assertEquals("$name letterbox oracle", expected.state.state, save)
                    assertEquals("$name letterbox state", save, midend.serialise().serialise())
                    assertEquals("$name letterbox count", 1, midend.stateCount)
                }
            }

            // The positive control: the same gesture on a real region.
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            midend.processKey(30, 30, Buttons.LEFT_BUTTON, 20)
            midend.processKey(110, 30, Buttons.LEFT_RELEASE, 20)
            assertEquals(2, midend.stateCount)
            assertEquals(1, midend.status())
            assertNotEquals(save, midend.serialise().serialise())
        }

    @Test(timeout = 120_000)
    fun `the requested keys and the cursor are UI updates`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("map", "", SEED).state
            val expected = oracle.requestKeys(save)
            val actual = Map.requestKeys(Map.defaultParams())
            assertEquals(3, expected.arrowMode)
            assertEquals(expected.labels.size, actual.labels.size)
            for (i in expected.labels.indices) {
                assertEquals(expected.labels[i].label, actual.labels[i].name)
                assertEquals(expected.labels[i].button, actual.labels[i].button)
                assertEquals(expected.labels[i].needsArrows, actual.labels[i].needsArrows)
            }
            assertEquals("Regions", actual.labels[0].name)
            assertEquals(76, actual.labels[0].button)
            assertEquals("Colours", actual.labels[1].name)
            assertEquals(67, actual.labels[1].button)
            assertEquals(expected, oracle.requestKeys(save, fullCursor = true))

            // The two requested keys toggle a drawing preference. The
            // C save text and the state count do not change.
            val sequence = listOf('l'.code, 'l'.code, 'c'.code, 'c'.code)
            val expectedState = oracle.keys(save, sequence).state
            assertEquals(save, expectedState)
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            for (key in sequence) {
                assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, key, 20))
            }
            assertEquals(save, midend.serialise().serialise())
            assertEquals(1, midend.stateCount)
            val ui = midend.ui as Map.Ui
            assertEquals(0, ui.showLabels)

            // A cursor move is also a UI update, and a real drag is the
            // positive control.
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 20),
            )
            assertEquals(save, midend.serialise().serialise())
            assertEquals(1, midend.stateCount)
            val control = Midend.fromSave(Map, SgtpSave.parse(handSave("fh,0123a")))
            control.processKey(30, 30, Buttons.LEFT_BUTTON, 20)
            control.processKey(110, 30, Buttons.LEFT_RELEASE, 20)
            assertEquals(2, control.stateCount)
            assertEquals(1, control.status())
        }

    private fun viewports(save: String): List<GameViewport> {
        val params = Map.decodeParams("5x2n5")
        val sizes = listOf(48, 63, 82, 100).map { tile ->
            val size = Map.computeSize(params, tile)
            tile to (size.width to size.height)
        } + listOf(null to (411 to 702))
        return sizes.map { (tile, viewport) ->
            val geometry = Midend.fromSave(Map, SgtpSave.parse(save))
                .geometryFor(viewport.first, viewport.second)
            if (tile != null) assertEquals(tile, geometry.tileSize)
            GameViewport(
                tile = geometry.tileSize,
                width = viewport.first,
                height = viewport.second,
                originX = geometry.originX,
                originY = geometry.originY,
            )
        }
    }

    private data class ButtonPoint(val x: Int, val y: Int, val button: Int)
    private data class Drag(
        val what: String,
        val press: Int,
        val release: Int,
        val move: String,
        val status: Int,
    )
    private data class GameViewport(
        val tile: Int,
        val width: Int,
        val height: Int,
        val originX: Int,
        val originY: Int,
    )
    private data class Quadrant(val name: String, val dx: Int, val dy: Int, val region: Int)

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
