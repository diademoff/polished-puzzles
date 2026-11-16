package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: tracks.
 *
 * Cold traces are compared with the oracle and with the frozen goldens.
 * The first frame draws the entrance and exit letters, the frame
 * rectangle, the full-window update, then every clue and every cell in
 * the C order. Tracks is the first on-screen consumer of
 * `draw_thick_circle`, and its thick lines are fractional at the
 * straight-sleeper and inward-spoke sites, so the sized traces pin the
 * float endpoints.
 */
class TracksTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 120_000)
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            for (params in oracle.presets("tracks").map { it.params }) {
                val midend = Midend.newGame(Tracks, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("tracks", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/tracks/${GenTraces.fileName(params)}.trace",
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
                assertTrue(
                    "the frame rectangle is after the A and B letters",
                    actual.lines().indexOfFirst { it.startsWith("rect 33 33") } >
                        actual.lines().indexOfFirst { it.startsWith("text ") },
                )
            }
        }

    @Test(timeout = 120_000)
    fun `the thick primitive counts and one curved sleeper are pinned`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val midend = Midend.newGame(Tracks, "8x8dt", SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            assertEquals("70 thick lines", 70, actual.lines().count { it.startsWith("thick_line ") })
            assertEquals(
                "6 thick circles",
                6,
                actual.lines().count { it.startsWith("thick_circle ") },
            )
            // A curved sleeper with a fractional endpoint; the oracle
            // rounds it with `lroundf(v * 1000)`.
            assertTrue(
                actual,
                actual.lines().contains("thick_line 3750 63000 73000 59250 73000 5"),
            )
            // The two thick circles of one curved tile: radius 20 and 10
            // at tile 30, track colour 6.
            assertTrue(
                actual,
                actual.lines().contains("thick_circle 3750 33000 153000 20000 -1 6"),
            )
            assertTrue(
                actual,
                actual.lines().contains("thick_circle 3750 33000 153000 10000 -1 6"),
            )
        }

    @Test(timeout = 120_000)
    fun `sized traces after a click match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        for (tileSize in listOf(48, 63, 82, 100)) {
            val board = Tracks.computeSize(Tracks.decodeParams("8x8dt"), tileSize)
            val points = clickPoints(2, 2, tileSize)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    oracle.newGame("tracks", "8x8dt", SEED).state,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )

            val midend = Midend.newGame(Tracks, "8x8dt", SEED)
            apply(midend, points, tileSize)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tileSize),
                upstream,
            )
            assertLines("8x8dt 2,2 tile $tileSize", expected, actual)

            val thick = actual.lines().filter { it.startsWith("thick_line ") }
            assertTrue("no thick lines at tile $tileSize", thick.isNotEmpty())
            if (tileSize == 63 || tileSize == 82) {
                // TILE/8 is 7.5 at 63 and 9.75 at 82, so the straight
                // sleeper centers are fractional thousandths.
                assertTrue(
                    "fractional sleeper at tile $tileSize",
                    thick.any { line ->
                        val parts = line.split(' ')
                        parts[2].toInt() % 1000 != 0 || parts[4].toInt() % 1000 != 0
                    },
                )
            }
            assertTrue(
                "the trace has a thick circle at tile $tileSize",
                actual.lines().any { it.startsWith("thick_circle ") },
            )
        }
    }

    @Test(timeout = 120_000)
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        val geometry = Midend.newGame(Tracks, "8x8dt", SEED).geometryFor(411, 702)
        val gamePoint = clickPoints(2, 3, geometry.tileSize)
        val gx = gamePoint[0].x
        val gy = gamePoint[0].y
        val points = listOf(
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_BUTTON),
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(save, points, width = 411, height = 702, recordTrace = true).trace!!,
            upstream,
        )
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        midend.processKey(gx, gy, Buttons.LEFT_BUTTON, geometry.tileSize)
        midend.processKey(gx, gy, Buttons.LEFT_RELEASE, geometry.tileSize)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream,
        )
        assertLines("letterbox", expected, actual)
        assertFalse(actual.lines().any { it.startsWith("blitter_") })
    }

    @Test(timeout = 120_000)
    fun `a warm frame after a real click is empty`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        for (tileSize in listOf(33, 48, 63, 82, 100)) {
            val board = Tracks.computeSize(Tracks.decodeParams("8x8dt"), tileSize)
            val points = clickPoints(3, 3, tileSize)
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points, board.width, board.height).trace!!,
                upstream,
            )
            val midend = Midend.newGame(Tracks, "8x8dt", SEED)
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
    }

    @Test(timeout = 120_000)
    fun `a cursor frame matches the oracle at every cursor position`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("tracks", "8x8dt", SEED).state
            val tileSize = Midend.newGame(Tracks, "8x8dt", SEED).tileSize
            // The cursor starts hidden at (1,1). The first right press
            // only shows it; the second moves it to (2,1), a vertical
            // edge. One down press skips the corner (2,2) and reaches
            // (2,3), another vertical edge. A left press from there is
            // (1,3), a cell centre.
            val sequences = listOf(
                emptyList(),
                listOf(Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
                listOf(
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_DOWN,
                ),
                listOf(
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_LEFT,
                ),
            )
            for (sequence in sequences) {
                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = sequence),
                    upstream,
                )
                val midend = Midend.newGame(Tracks, "8x8dt", SEED)
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

    private fun clickPoints(x: Int, y: Int, tileSize: Int): List<OracleClient.Point> {
        val geom = tileSize / 6
        val tile = geom * 6
        val maxBorder = tile / 8
        var border = maxBorder
        if (border <= 5) border = minOf(tileSize % 6, maxBorder)
        val px = (x + 1) * tile + border + tile / 2
        val py = (y + 1) * tile + border + tile / 2
        return listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
    }

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tileSize: Int) {
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, tileSize)
        }
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            val left = expectedLines.getOrNull(i)
            val right = actualLines.getOrNull(i)
            assertEquals("$what line ${i + 1}", left, right)
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
