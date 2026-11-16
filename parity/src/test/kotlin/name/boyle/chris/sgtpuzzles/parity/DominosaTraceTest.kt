package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: dominosa.
 *
 * Cold traces are compared with the oracle and with the frozen goldens.
 * A first frame draws changed_state, the frame rectangle, one tile block
 * per square, the full-window update, and end_draw. A warm frame draws
 * only the tiles whose flag word changed, with no whole-window
 * operations. The traces also pin the circle and rectangle geometry of
 * the domino ends, the edge ticks, the cursor rings, and the two
 * highlight colours.
 */
class DominosaTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 180_000)
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            for (params in oracle.presets("dominosa").map { it.params }) {
                val midend = Midend.newGame(Dominosa, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("dominosa", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/dominosa/${GenTraces.fileName(params)}.trace",
                )
                assertEquals("frozen trace for '$params'", golden.readText(), actual)
                assertEquals(
                    "one start_draw for '$params'",
                    1,
                    actual.lines().count { it == "start_draw" },
                )
                assertEquals(
                    "one end_draw for '$params'",
                    1,
                    actual.lines().count { it == "end_draw" },
                )
                assertEquals(
                    "no status bar for '$params'",
                    0,
                    actual.lines().count { it.startsWith("status ") },
                )
                assertEquals(
                    "no blitter for '$params'",
                    0,
                    actual.lines().count { it.startsWith("blitter_") },
                )
                assertEquals(
                    "ten palette entries for '$params'",
                    10,
                    paletteSize(actual),
                )
                assertFalse(actual.lines().any { it.contains("flash") })
            }
        }

    @Test
    fun `the first 3dt frame has the measured operation counts`() {
        val trace = File(testdata, "trace/dominosa/3dt.trace").readText().trimEnd('\n')
        assertEquals(112, trace.lines().size)
        assertEquals(20, trace.lines().count { it.startsWith("clip ") })
        assertEquals(20, trace.lines().count { it.startsWith("text ") })
        assertEquals(21, trace.lines().count { it.startsWith("update ") })
        assertEquals(21, trace.lines().count { it.startsWith("rect ") })
        assertEquals(0, trace.lines().count { it.startsWith("line ") })
        assertEquals(0, trace.lines().count { it.startsWith("circle ") })
        assertEquals("changed_state false false", trace.lines()[7])
        assertEquals("start_draw", trace.lines()[8])
        assertEquals("rect 0 0 208 176 0", trace.lines()[9])
        assertEquals("update 0 0 208 176", trace.lines()[110])
        assertEquals("end_draw", trace.lines()[111])
    }

    @Test(timeout = 300_000)
    fun `sized traces after a real click match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        for (tileSize in listOf(48, 63, 82, 100)) {
            val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
            val point = dominoPoint(tileSize)
            val points = listOf(
                OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON),
                OracleClient.Point(point.first, point.second, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            apply(midend, points, tileSize)
            assertEquals("tile $tileSize move", "D0,1", moveOf(midend))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tileSize),
                upstream,
            )
            assertLines("3dt click tile $tileSize", expected, actual)
            // Both halves are domino ends: TYPE_L draws two left
            // circles and TYPE_R two right ones.
            assertEquals(
                "circles at tile $tileSize",
                4,
                actual.lines().count { it.startsWith("circle ") },
            )
        }
    }

    @Test(timeout = 180_000)
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val geometry = Midend.newGame(Dominosa, "3dt", SEED).geometryFor(411, 702)
        assertEquals(63, geometry.tileSize)
        assertEquals(1, geometry.originX)
        assertEquals(178, geometry.originY)
        assertEquals(409, geometry.width)
        assertEquals(346, geometry.height)

        val point = dominoPoint(geometry.tileSize)
        val gx = point.first
        val gy = point.second
        val points = listOf(
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_BUTTON),
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(save, points, width = 411, height = 702, recordTrace = true).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
        midend.processKey(gx, gy, Buttons.LEFT_BUTTON, geometry.tileSize)
        midend.processKey(gx, gy, Buttons.LEFT_RELEASE, geometry.tileSize)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream,
        )
        assertLines("letterbox", expected, actual)
    }

    @Test(timeout = 300_000)
    fun `the warm trace is empty after real input`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        for (tileSize in listOf(32, 48, 63, 82, 100)) {
            val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
            val point = dominoPoint(tileSize)
            val points = listOf(
                OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON),
                OracleClient.Point(point.first, point.second, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points, board.width, board.height).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, points, tileSize = tileSize),
                upstream,
            )
            assertLines("warm frame at tile $tileSize", expected, actual)
            assertEquals(
                "a warm frame is empty at tile $tileSize",
                listOf("start_draw", "end_draw"),
                actual.lines().dropWhile { !it.startsWith("palette ") }
                    .drop(1).filter { it.isNotEmpty() },
            )
        }

        // The same warm path inside a 411x702 letterbox.
        val geometry = Midend.newGame(Dominosa, "3dt", SEED).geometryFor(411, 702)
        val gamePoint = dominoPoint(geometry.tileSize)
        val letterboxPoints = listOf(
            OracleClient.Point(gamePoint.first, gamePoint.second, Buttons.LEFT_BUTTON),
            OracleClient.Point(gamePoint.first, gamePoint.second, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, letterboxPoints, 411, 702).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, letterboxPoints, tileSize = geometry.tileSize),
            upstream,
        )
        assertLines("letterbox warm frame", expected, actual)
    }

    @Test(timeout = 120_000)
    fun `a domino and an edge redraw exactly two tiles`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val point = dominoPoint(32)
        val dominoFrame = TraceNormalizer.normalize(
            KotlinTraceSink.warmFrame(
                midend,
                listOf(OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON)),
            ),
        )
        assertEquals(2, dominoFrame.lines().count { it.startsWith("clip ") })
        assertEquals(2, dominoFrame.lines().count { it.startsWith("update ") })
        assertTrue(dominoFrame.lines().any { it.startsWith("circle ") })
        assertFalse(dominoFrame.lines().any { it.startsWith("rect 0 0 208 176") })

        val edge = Midend.newGame(Dominosa, "3dt", SEED)
        val edgeFrame = TraceNormalizer.normalize(
            KotlinTraceSink.warmFrame(
                edge,
                listOf(OracleClient.Point(point.first, point.second, Buttons.RIGHT_BUTTON)),
            ),
        )
        assertEquals(2, edgeFrame.lines().count { it.startsWith("clip ") })
        assertEquals(2, edgeFrame.lines().count { it.startsWith("update ") })
        assertEquals(2, edgeFrame.lines().count { it.startsWith("rect ") && it.endsWith("5") })
    }

    @Test(timeout = 180_000)
    fun `cursor frames match the oracle at every node parity`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("dominosa", "3dt", SEED).state
            val tileSize = Midend.newGame(Dominosa, "3dt", SEED).tileSize
            // (0,0) is even, (1,0) is a horizontal edge, (1,1) is even,
            // (1,2) a vertical edge, and (0,1) a vertical edge.
            val sequences = listOf(
                emptyList(),
                listOf(Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
                listOf(
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                ),
                listOf(Buttons.CURSOR_DOWN),
                // The clamps at 0 and at 2w-2 / 2h-2.
                (0 until 12).map { Buttons.CURSOR_RIGHT },
                (0 until 12).map { Buttons.CURSOR_DOWN },
            )
            for (sequence in sequences) {
                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = sequence),
                    upstream,
                )
                val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
                for (key in sequence) {
                    midend.processKey(0, 0, key, tileSize)
                }
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tileSize),
                    upstream,
                )
                assertLines("cursor $sequence", expected, actual)
            }
        }

    @Test(timeout = 120_000)
    fun `the useful cursor ring is drawn twice`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        // Move to (1,0): a useful node between two tiles, so each of the
        // two visible tiles draws both the r and r+1 corner ring.
        val frame = TraceNormalizer.normalize(
            KotlinTraceSink.warmFrame(
                midend,
                listOf(OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT)),
            ),
        )
        assertEquals(2, frame.lines().count { it.startsWith("clip ") })
        assertEquals(32, frame.lines().count { it.startsWith("line ") })
    }

    @Test(timeout = 180_000)
    fun `highlight frames match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val sequences = listOf(
            listOf('0'.code),
            listOf('0'.code, '1'.code),
            listOf('0'.code, '1'.code, '0'.code),
            listOf('1'.code, '2'.code, '3'.code),
        )
        for (sequence in sequences) {
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            for (key in sequence) midend.processKey(0, 0, key, 32)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, 32),
                upstream,
            )
            assertLines("highlights $sequence", expected, actual)
        }

        // The numbers with that value carry the highlight colour, and
        // the colour is the palette entry.
        val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
        midend.processKey(0, 0, '0'.code, 32)
        val frame = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 32))
        val texts = frame.lines().filter { it.startsWith("text ") }
        val zero = texts.filter { it.endsWith("\"0\"") }
        assertEquals(5, zero.size)
        assertTrue(zero.all { it.split(' ')[6] == "8" })
        val other = texts.filter { !it.endsWith("\"0\"") }
        assertTrue(other.all { it.split(' ')[6] == "1" })
    }

    private fun paletteSize(trace: String): Int {
        val line = trace.lines().first { it.startsWith("palette ") }
        return line.removePrefix("palette ").split(' ').size
    }

    /** The right half of cell (0,0), which is the horizontal pair 0,1. */
    private fun dominoPoint(tileSize: Int): Pair<Int, Int> {
        val border = tileSize * 3 / 4
        return Pair(border + tileSize / 2 + tileSize / 4, border + tileSize / 2)
    }

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tileSize: Int) {
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, tileSize)
        }
    }

    private fun moveOf(midend: Midend): String =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE").lastOrNull() ?: ""

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
