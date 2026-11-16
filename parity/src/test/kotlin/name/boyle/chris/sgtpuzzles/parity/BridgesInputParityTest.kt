package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: bridges.
 *
 * Layout at four exact tiles and a letterbox, a real lock click, a real
 * bridge drag cycle, a right-button no-line, cursor and select keys with
 * their modifiers, the select-cancel quirk, and the vacuity guards.
 */
class BridgesInputParityTest {

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "7x7i30e10m2d0"
        const val TILE = 24
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun midend(save: String): Midend =
        Midend.fromSave(Bridges, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    /** The exact board-size viewport for [tile]: 7 cells plus two borders. */
    private fun viewFor(tile: Int): Int = 8 * tile

    /** Press and release at the centre of island (0,0). */
    private fun clickPoints(x: Int, tile: Int, originX: Int, originY: Int):
        List<OracleClient.Point> {
        val px = originX + x * tile + tile
        val py = originY + tile
        return listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
    }

    /** Press at (0,0), drag to (6,0), and release there. */
    private fun dragPoints(tile: Int, originX: Int, originY: Int):
        List<OracleClient.Point> = listOf(
        OracleClient.Point(originX + tile, originY + tile, Buttons.LEFT_BUTTON),
        OracleClient.Point(originX + 7 * tile, originY + tile, Buttons.LEFT_DRAG),
        OracleClient.Point(originX + 7 * tile, originY + tile, Buttons.LEFT_RELEASE),
    )

    /** The same drag with the right button, which places a no-line. */
    private fun rightDragPoints(tile: Int, originX: Int, originY: Int):
        List<OracleClient.Point> = dragPoints(tile, originX, originY).map {
        it.copy(
            button = when (it.button) {
                Buttons.LEFT_BUTTON -> Buttons.RIGHT_BUTTON
                Buttons.LEFT_DRAG -> Buttons.RIGHT_DRAG
                else -> Buttons.RIGHT_RELEASE
            },
        )
    }

    private fun apply(midend: Midend, points: List<OracleClient.Point>, geometry: BoardGeometry) {
        for (point in points) {
            midend.processKey(
                point.x - geometry.originX,
                point.y - geometry.originY,
                point.button,
                geometry.tileSize,
            )
        }
    }

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        val e = TraceNormalizer.normalize(expected, upstream()).lines()
        val a = TraceNormalizer.normalize(actual, upstream()).lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError(
                    "$what: first diff at line ${i + 1}:\n" +
                        "expected: ${e[i]}\nactual:   ${a[i]}",
                )
            }
        }
        assertEquals("$what: line count", e.size, a.size)
    }

    @Test
    fun `layout picks the exact tiles and a letterbox origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", PARAMS, SEED).state
            for ((view, tile) in listOf(
                384 to 48,
                504 to 63,
                656 to 82,
                800 to 100,
            )) {
                val expected = oracle.layout(save, view, view)
                val geometry = midend(save).geometryFor(view, view)
                assertEquals("$view tile", tile, geometry.tileSize)
                assertEquals("$view width", expected.width, geometry.width)
                assertEquals("$view height", expected.height, geometry.height)
                assertEquals("$view origin x", 0, geometry.originX)
                assertEquals("$view origin y", 0, geometry.originY)
            }

            // 51 * 7 + 2 * 25 = 407, centred in 411 x 702.
            assertEquals(407, Bridges.computeSize(Bridges.decodeParams(PARAMS), 51).width)
            val geometry = midend(save).geometryFor(411, 702)
            val expected = oracle.layout(save, 411, 702)
            assertEquals(51, geometry.tileSize)
            assertEquals(407, geometry.width)
            assertEquals(407, geometry.height)
            assertEquals(expected.width, geometry.width)
            assertEquals(expected.height, geometry.height)
            assertEquals(2, geometry.originX)
            assertEquals(147, geometry.originY)
        }

    @Test
    fun `a real click toggles the lock at four tiles and in the letterbox`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", PARAMS, SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val view = viewFor(tile)
                val points = clickPoints(0, tile, 0, 0)
                val result = oracle.pointsSized(
                    save,
                    points,
                    view,
                    view,
                    recordTrace = true,
                )
                val midend = midend(save)
                for (point in points) {
                    assertEquals(
                        "tile $tile outcome",
                        KeyOutcome.SomeEffect,
                        midend.processKey(point.x, point.y, point.button, tile),
                    )
                }
                assertSame("tile $tile", result.state, midend)
                assertEquals(2, midend.stateCount)
                assertEquals(listOf("M0,0"), midend.serialise().values("MOVE"))
                assertTrace(
                    "tile $tile frame",
                    result.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }

            val geometry = midend(save).geometryFor(411, 702)
            val points = clickPoints(0, geometry.tileSize, geometry.originX, geometry.originY)
            val result = oracle.pointsSized(
                save,
                points,
                411,
                702,
                recordTrace = true,
            )
            val actual = midend(save)
            apply(actual, points, geometry)
            assertSame("letterbox click", result.state, actual)
            assertEquals(listOf("M0,0"), actual.serialise().values("MOVE"))
            assertTrace(
                "letterbox click frame",
                result.trace,
                KotlinTraceSink.stateTrace(actual, geometry.tileSize),
            )
        }

    @Test
    fun `a real drag cycles 1 2 0 at four tiles`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        for (tile in listOf(48, 63, 82, 100)) {
            val view = viewFor(tile)
            var expected = save
            val midend = midend(save)
            for (round in 1..3) {
                val points = dragPoints(tile, 0, 0)
                val result = oracle.pointsSized(
                    expected,
                    points,
                    view,
                    view,
                    recordTrace = true,
                )
                for (point in points) {
                    assertEquals(
                        "tile $tile round $round",
                        KeyOutcome.SomeEffect,
                        midend.processKey(point.x, point.y, point.button, tile),
                    )
                }
                assertSame("tile $tile round $round", result.state, midend)
                expected = result.state.state
                assertEquals(
                    "tile $tile round $round move",
                    "L0,0,6,0,${if (round == 3) 0 else round}",
                    midend.serialise().values("MOVE").last(),
                )
                assertTrace(
                    "tile $tile round $round frame",
                    result.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }
            assertEquals(4, midend.stateCount)
        }
    }

    @Test
    fun `a right drag toggles the no-line at four tiles`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        for (tile in listOf(48, 63, 82, 100)) {
            val view = viewFor(tile)
            var expected = save
            val midend = midend(save)
            for (round in 1..2) {
                val points = rightDragPoints(tile, 0, 0)
                val result = oracle.pointsSized(
                    expected,
                    points,
                    view,
                    view,
                    recordTrace = true,
                )
                for (point in points) {
                    assertEquals(
                        "tile $tile right round $round",
                        KeyOutcome.SomeEffect,
                        midend.processKey(point.x, point.y, point.button, tile),
                    )
                }
                assertSame("tile $tile right round $round", result.state, midend)
                expected = result.state.state
                assertTrace(
                    "tile $tile right round $round frame",
                    result.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }
            assertEquals(3, midend.stateCount)
            assertEquals(
                listOf("N0,0,6,0", "N0,0,6,0"),
                midend.serialise().values("MOVE"),
            )
        }
    }

    @Test
    fun `the letterboxed drag is one lazy move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        val geometry = midend(save).geometryFor(411, 702)
        val points = dragPoints(geometry.tileSize, geometry.originX, geometry.originY)
        val result = oracle.pointsSized(save, points, 411, 702, recordTrace = true)
        val actual = midend(save)
        apply(actual, points, geometry)
        assertSame("letterbox drag", result.state, actual)
        assertEquals(2, actual.stateCount)
        assertEquals(listOf("L0,0,6,0,1"), actual.serialise().values("MOVE"))
        assertTrace(
            "letterbox drag frame",
            result.trace,
            KotlinTraceSink.stateTrace(actual, geometry.tileSize),
        )
    }

    @Test
    fun `every real lock bridge and no-line changes the board and the count`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", PARAMS, SEED).state
            for ((what, points, move, changesText) in listOf(
                Quad(
                    "lock",
                    clickPoints(0, TILE, 0, 0),
                    "M0,0",
                    false,
                ),
                Quad(
                    "bridge",
                    dragPoints(TILE, 0, 0),
                    "L0,0,6,0,1",
                    true,
                ),
                Quad(
                    "no-line",
                    rightDragPoints(TILE, 0, 0),
                    "N0,0,6,0",
                    false,
                ),
            )) {
                val midend = midend(save)
                val before = (midend.state as Bridges.State).grid.copyOf()
                val beforeText = midend.textFormat()
                for (point in points) {
                    assertEquals(
                        "$what outcome",
                        KeyOutcome.SomeEffect,
                        midend.processKey(point.x, point.y, point.button, TILE),
                    )
                }
                val after = (midend.state as Bridges.State).grid
                assertFalse("$what changes the board", before.contentEquals(after))
                if (changesText) {
                    assertNotEquals("$what changes the text", beforeText, midend.textFormat())
                }
                assertEquals("$what adds one state", 2, midend.stateCount)
                assertEquals(
                    "$what adds one move",
                    listOf(move),
                    midend.serialise().values("MOVE"),
                )
                assertTrue("$what can undo", midend.canUndo)
            }
        }

    private data class Quad(
        val what: String,
        val points: List<OracleClient.Point>,
        val move: String,
        val changesText: Boolean,
    )

    @Test
    fun `inert clicks add no move and no state`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        val before = (midend(save).state as Bridges.State).grid.copyOf()

        // A press and release on an empty cell.
        val blank = midend(save)
        for (point in listOf(
            OracleClient.Point(2 * TILE, TILE, Buttons.LEFT_BUTTON),
            OracleClient.Point(2 * TILE, TILE, Buttons.LEFT_RELEASE),
        )) {
            blank.processKey(point.x, point.y, point.button, TILE)
        }
        assertEquals(1, blank.stateCount)
        assertTrue(blank.serialise().values("MOVE").isEmpty())
        assertTrue(before.contentEquals((blank.state as Bridges.State).grid))

        // A press outside the grid is Unused. The release still reaches
        // the game because the midend tracks the held button, and is a
        // UI-only cancel.
        val outside = midend(save)
        assertEquals(
            KeyOutcome.Unused,
            outside.processKey(1, 1, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            outside.processKey(1, 1, Buttons.LEFT_RELEASE, TILE),
        )
        assertEquals(1, outside.stateCount)
        assertTrue(outside.serialise().values("MOVE").isEmpty())

        // A release with no press at all is dropped by the midend.
        val releaseOnly = midend(save)
        assertEquals(
            KeyOutcome.Unused,
            releaseOnly.processKey(1, 1, Buttons.LEFT_RELEASE, TILE),
        )
        assertEquals(1, releaseOnly.stateCount)
        assertTrue(releaseOnly.serialise().values("MOVE").isEmpty())

        // A drag inside one cell without a release only moves the source.
        val inside = midend(save)
        assertEquals(
            KeyOutcome.SomeEffect,
            inside.processKey(TILE, TILE, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            inside.processKey(TILE + 4, TILE + 4, Buttons.LEFT_DRAG, TILE),
        )
        assertEquals(1, inside.stateCount)
        assertTrue(inside.serialise().values("MOVE").isEmpty())

        // A press on one island released on another without a drag.
        val cross = midend(save)
        cross.processKey(TILE, TILE, Buttons.LEFT_BUTTON, TILE)
        cross.processKey(7 * TILE, TILE, Buttons.LEFT_RELEASE, TILE)
        assertEquals(1, cross.stateCount)
        assertTrue(cross.serialise().values("MOVE").isEmpty())
        assertTrue(before.contentEquals((cross.state as Bridges.State).grid))

        // A click in the letterbox maps outside the grid and is Unused.
        val geometry = midend(save).geometryFor(411, 702)
        val letterbox = midend(save)
        assertEquals(
            KeyOutcome.Unused,
            letterbox.processKey(
                -geometry.originX,
                -geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            letterbox.processKey(
                -geometry.originX,
                -geometry.originY,
                Buttons.LEFT_RELEASE,
                geometry.tileSize,
            ),
        )
        assertEquals(1, letterbox.stateCount)
        assertTrue(letterbox.serialise().values("MOVE").isEmpty())
        assertTrue(before.contentEquals((letterbox.state as Bridges.State).grid))
    }

    @Test
    fun `a drag over a marked edge adds no move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        // Lock (6,0), then drag from (0,0) to it. The marked cells
        // between the islands block the destination.
        val points = clickPoints(6, TILE, 0, 0) + dragPoints(TILE, 0, 0)
        val expected = oracle.pointsSized(save, points).state
        val midend = midend(save)
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, TILE)
        }
        assertSame("marked edge", expected, midend)
        assertEquals(2, midend.stateCount)
        assertEquals(listOf("M6,0"), midend.serialise().values("MOVE"))
    }

    @Test
    fun `a drag over a blocked crossing adds no move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state

        // A no-line on the edge blocks a later bridge drag.
        val noLineFirst = midend(save)
        for (point in rightDragPoints(TILE, 0, 0)) {
            noLineFirst.processKey(point.x, point.y, point.button, TILE)
        }
        val afterNoLine = (noLineFirst.state as Bridges.State).grid.copyOf()
        for (point in dragPoints(TILE, 0, 0)) {
            assertEquals(
                "blocked bridge",
                KeyOutcome.SomeEffect,
                noLineFirst.processKey(point.x, point.y, point.button, TILE),
            )
        }
        assertEquals(2, noLineFirst.stateCount)
        assertEquals(listOf("N0,0,6,0"), noLineFirst.serialise().values("MOVE"))
        assertTrue(afterNoLine.contentEquals((noLineFirst.state as Bridges.State).grid))

        // A bridge on the edge blocks a later no-line drag.
        val bridgeFirst = midend(save)
        for (point in dragPoints(TILE, 0, 0)) {
            bridgeFirst.processKey(point.x, point.y, point.button, TILE)
        }
        val afterBridge = (bridgeFirst.state as Bridges.State).grid.copyOf()
        for (point in rightDragPoints(TILE, 0, 0)) {
            assertEquals(
                "blocked no-line",
                KeyOutcome.SomeEffect,
                bridgeFirst.processKey(point.x, point.y, point.button, TILE),
            )
        }
        assertEquals(2, bridgeFirst.stateCount)
        assertEquals(listOf("L0,0,6,0,1"), bridgeFirst.serialise().values("MOVE"))
        assertTrue(afterBridge.contentEquals((bridgeFirst.state as Bridges.State).grid))

        val expected = oracle.pointsSized(
            save,
            rightDragPoints(TILE, 0, 0) + dragPoints(TILE, 0, 0),
        ).state
        assertSame("blocked bridge save", expected, noLineFirst)
    }

    @Test
    fun `a drag back to the source adds no move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        val points = listOf(
            OracleClient.Point(TILE, TILE, Buttons.LEFT_BUTTON),
            OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_DRAG),
            OracleClient.Point(TILE, TILE, Buttons.LEFT_DRAG),
        )
        val midend = midend(save)
        for (point in points) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(point.x, point.y, point.button, TILE),
            )
        }
        assertEquals(1, midend.stateCount)
        assertTrue(midend.serialise().values("MOVE").isEmpty())
    }

    @Test
    fun `cursor and select keys match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        for (sequence in listOf(
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_LEFT),
            listOf(Buttons.CURSOR_UP),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT, Buttons.CURSOR_RIGHT),
            listOf('l'.code),
            listOf('g'.code, 'G'.code),
        )) {
            val expected = oracle.keys(save, sequence)
            val midend = midend(save)
            for (key in sequence) midend.processKey(0, 0, key, TILE)
            assertSame("keys $sequence", expected, midend)
        }

        // The cursor position itself is only visible in a draw.
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        for (sequence in listOf(
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_DOWN),
            listOf('l'.code),
        )) {
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )
            val midend = midend(save)
            for (key in sequence) midend.processKey(0, 0, key, TILE)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertEquals("cursor draw $sequence", expected, actual)
        }
    }

    @Test
    fun `modified cursor keys place one bridge or no-line`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", PARAMS, SEED).state
            for ((sequence, move) in listOf(
                // C sets `drag_is_noline = !control`, so SHFT is the
                // no-line variant and CTRL is the bridge variant.
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT) to "N0,0,6,0",
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT) to "L0,0,6,0,1",
            )) {
                val expected = oracle.keys(save, sequence)
                val midend = midend(save)
                val before = (midend.state as Bridges.State).grid.copyOf()
                assertEquals(
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, sequence.single(), TILE),
                )
                assertSame("modified $sequence", expected, midend)
                assertEquals(listOf(move), midend.serialise().values("MOVE"))
                assertEquals(2, midend.stateCount)
                assertFalse(
                    "modified $sequence changes the board",
                    before.contentEquals((midend.state as Bridges.State).grid),
                )
            }
        }
}
