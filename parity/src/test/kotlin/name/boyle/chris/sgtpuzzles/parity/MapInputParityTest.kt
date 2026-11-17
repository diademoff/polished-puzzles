package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private data class ButtonPoint(val x: Int, val y: Int, val button: Int)
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
