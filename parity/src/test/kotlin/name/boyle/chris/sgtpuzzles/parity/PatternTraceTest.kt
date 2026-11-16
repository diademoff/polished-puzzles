package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: pattern.
 *
 * The fresh redraw of every preset is compared with the oracle and with
 * the frozen traces: the grid frame, 100 cells and 20 clue bands for
 * `10x10` (900 and 60 for `30x30`), the clue font sizes, and the palette.
 * Sized cold traces run at tiles 48, 63, 82, and 100 and in a letterboxed
 * 411x702 viewport. A cursor sentence shows the two cursor polygons and
 * the two guided clue colours. An error sentence shows the `COL_ERROR`
 * clue. The warm path after a press, a release, a cursor key, and a win
 * is exactly `start_draw`, `end_draw`, which is a cache-exactness guard.
 * There is no blitter and never a status line.
 */
class PatternTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24

        val PRESETS = listOf("10x10", "15x15", "20x20", "25x25", "30x30")

        const val PALETTE =
            "palette 800,800,800 1000,1000,1000 0,0,0 0,0,0 500,500,500 " +
                "300,300,300 1000,250,250 1000,0,0 500,500,500"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
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
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in PRESETS) {
                val midend = Midend.newGame(Pattern, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream())
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("pattern", params, SEED),
                    upstream(),
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/pattern/$params.trace").readText()
                assertLines("frozen trace for '$params'", golden, actual)

                val ops = actual.lines()
                assertFalse("'$params' has no status", ops.any { it.startsWith("status ") })
                assertFalse("'$params' has no blitter", ops.any { it.startsWith("blitter_") })
                assertEquals(
                    "'$params' changed_state",
                    1,
                    ops.count { it == "changed_state false false" },
                )
                assertEquals(
                    "'$params' palette",
                    PALETTE,
                    ops.first { it.startsWith("palette ") },
                )

                val p = Pattern.decodeParams(params) as Pattern.Params
                val cells = p.w * p.h
                val lines = p.w + p.h
                assertEquals(
                    "'$params' cell updates",
                    cells,
                    ops.count { it.matches(Regex("^update -?\\d+ -?\\d+ $TILE $TILE$")) },
                )
                assertEquals(
                    "'$params' clue bands",
                    lines,
                    ops.count { it.startsWith("clip ") },
                )
                // The grid frame under the cells.
                assertEquals(
                    "'$params' frame",
                    "rect ${toCoord(p, 0) - 1} ${toCoord(p, 0) - 1} " +
                        "${p.w * TILE + 3} ${p.h * TILE + 3} 5",
                    ops.first { it.startsWith("rect ") && it.endsWith(" 5") },
                )
                // The first cell: a grid rectangle and an unknown fill.
                assertTrue(
                    "'$params' cell 0",
                    ops.contains(
                        "rect ${toCoord(p, 0)} ${toCoord(p, 0)} $TILE $TILE 5",
                    ) && ops.contains(
                        "rect ${toCoord(p, 0) + 2} ${toCoord(p, 0) + 2} 22 22 4",
                    ),
                )
            }
        }

    @Test
    fun `sized cold traces match at four tiles`() = OracleClient().use { oracle ->
        for ((params, tile) in listOf(
            "10x10" to 48,
            "10x10" to 63,
            "10x10" to 82,
            "10x10" to 100,
            "15x15" to 48,
        )) {
            val save = oracle.newGame("pattern", params, SEED).state
            val p = Pattern.decodeParams(params)
            val size = Pattern.computeSize(p, tile)
            val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
            val geometry = midend.geometryFor(size.width, size.height)
            assertEquals("$params tile $tile", tile, geometry.tileSize)
            assertEquals("$params origin x at $tile", 0, geometry.originX)
            assertEquals("$params origin y at $tile", 0, geometry.originY)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = size.width,
                    height = size.height,
                    recordTrace = true,
                ).trace!!,
                upstream(),
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream(),
            )
            assertLines("$params cold trace at tile $tile", expected, actual)
            assertEquals(
                "$params size at tile $tile",
                "size ${size.width} ${size.height}",
                actual.lines().first { it.startsWith("size ") },
            )
        }
    }

    @Test
    fun `a letterboxed cold trace matches`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pattern", "10x10", SEED).state
        val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(25, geometry.tileSize)
        assertEquals(6, geometry.originX)
        assertEquals(152, geometry.originY)

        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                emptyList(),
                width = 411,
                height = 702,
                recordTrace = true,
            ).trace!!,
            upstream(),
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream(),
        )
        assertLines("letterbox cold", expected, actual)
    }

    @Test
    fun `a visible cursor draws the ring and the two guided clues`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            val sequence = listOf(Buttons.CURSOR_RIGHT)
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream(),
            )
            val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
            for (button in sequence) midend.processKey(0, 0, button, TILE)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream(),
            )
            assertLines("visible cursor", expected, actual)

            // Cell (1,0): the first left thick separator is absent, the
            // top one is present. Two unfilled outlines.
            assertTrue(
                "outer ring",
                actual.lines().contains("poly -1 6 4 151 128 151 149 173 149 173 128"),
            )
            assertTrue(
                "inner ring",
                actual.lines().contains("poly -1 6 4 152 129 152 148 172 148 172 129"),
            )
            assertEquals(
                "two cursor rings",
                2,
                actual.lines().count { it.startsWith("poly -1 6 ") },
            )
            // The column clue of x=1 and the row clue of y=0 are guided.
            assertTrue(
                "guided column clue",
                actual.lines().any { Regex("^text 162 \\d+ 1 20 257 8 \".*\"$").matches(it) },
            )
            assertTrue(
                "guided row clue",
                actual.lines().any { Regex("^text 114 138 1 20 258 8 \".*\"$").matches(it) },
            )
        }

    @Test
    fun `an error clue draws in COL_ERROR`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pattern", "2x2", SEED).state
        val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
        // Fill both cells of row 0; the row clue list is empty, so the
        // row band is an error. The two columns stay consistent.
        for (x in 0 until 2) {
            val points = listOf(
                OracleClient.Point(twoX(x), twoY(0), Buttons.LEFT_BUTTON),
                OracleClient.Point(twoX(x), twoY(0), Buttons.LEFT_RELEASE),
            )
            for (point in points) midend.processKey(point.x, point.y, point.button, TILE)
        }
        val afterSave = midend.serialise().serialise()
        val expected = TraceNormalizer.normalize(
            oracle.trace(afterSave),
            upstream(),
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, TILE),
            upstream(),
        )
        assertLines("error clue", expected, actual)
        assertTrue(
            "the row clue is COL_ERROR",
            actual.lines().contains("text 66 90 1 20 258 7 \"\""),
        )
        assertTrue("the error clue is not a status", actual.lines().none {
            it.startsWith("status ")
        })
    }

    @Test
    fun `the completed 2x2 frame has no status and no flash`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "2x2", SEED).state
            // Row 1 is the answer; row 0 is empty.
            val points = ArrayList<OracleClient.Point>()
            for (x in 0 until 2) {
                points += OracleClient.Point(twoX(x), twoY(1), Buttons.LEFT_BUTTON)
                points += OracleClient.Point(twoX(x), twoY(1), Buttons.LEFT_RELEASE)
            }
            for (x in 0 until 2) {
                points += OracleClient.Point(twoX(x), twoY(0), Buttons.RIGHT_BUTTON)
                points += OracleClient.Point(twoX(x), twoY(0), Buttons.RIGHT_RELEASE)
            }
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(save, points, recordTrace = true).trace!!,
                upstream(),
            )
            val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
            for (point in points) midend.processKey(point.x, point.y, point.button, TILE)
            assertEquals(1, midend.status())
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream(),
            )
            assertLines("completed frame", expected, actual)
            assertTrue("no status", actual.lines().none { it.startsWith("status ") })
            assertTrue(
                "the board is drawn as full and empty",
                actual.lines().any { it.startsWith("rect ") && it.endsWith(" 2") } &&
                    actual.lines().any { it.startsWith("rect ") && it.endsWith(" 1") },
            )
        }

    @Test
    fun `a warm frame after a press and a release is empty`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            val press = listOf(OracleClient.Point(gameX(0), gameY(0), Buttons.LEFT_BUTTON))
            val pressExpected = TraceNormalizer.normalize(
                oracle.traceWarm(save, press).trace!!,
                upstream(),
            )
            val pressMidend = Midend.fromSave(Pattern, SgtpSave.parse(save))
            val pressActual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(pressMidend, press),
                upstream(),
            )
            assertLines("warm press", pressExpected, pressActual)
            assertEquals(
                listOf("start_draw", "end_draw"),
                pressActual.lines().drop(7).filter { it.isNotEmpty() },
            )

            val click = listOf(
                OracleClient.Point(gameX(0), gameY(0), Buttons.LEFT_BUTTON),
                OracleClient.Point(gameX(0), gameY(0), Buttons.LEFT_RELEASE),
            )
            val clickExpected = TraceNormalizer.normalize(
                oracle.traceWarm(save, click).trace!!,
                upstream(),
            )
            val clickMidend = Midend.fromSave(Pattern, SgtpSave.parse(save))
            val clickActual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(clickMidend, click),
                upstream(),
            )
            assertLines("warm release", clickExpected, clickActual)
            assertEquals(
                listOf("start_draw", "end_draw"),
                clickActual.lines().drop(7).filter { it.isNotEmpty() },
            )
            assertEquals(2, clickMidend.stateCount)
        }

    @Test
    fun `a warm frame after a win is empty`() = OracleClient().use { oracle ->
        // The four clicks of the 2x2 win, read from row 1 and row 0.
        val save = oracle.newGame("pattern", "2x2", SEED).state
        val points = ArrayList<OracleClient.Point>()
        for (x in 0 until 2) {
            points += OracleClient.Point(twoX(x), twoY(1), Buttons.LEFT_BUTTON)
            points += OracleClient.Point(twoX(x), twoY(1), Buttons.LEFT_RELEASE)
        }
        for (x in 0 until 2) {
            points += OracleClient.Point(twoX(x), twoY(0), Buttons.RIGHT_BUTTON)
            points += OracleClient.Point(twoX(x), twoY(0), Buttons.RIGHT_RELEASE)
        }
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, points).trace!!,
            upstream(),
        )
        val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, points),
            upstream(),
        )
        assertLines("warm win", expected, actual)
        assertEquals(1, midend.status())
        assertEquals(
            listOf("start_draw", "end_draw"),
            actual.lines().drop(7).filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `a warm frame after a cursor key is empty`() {
        // The oracle's traceWarm takes points only, so this is a
        // Kotlin-only cache-exactness guard for a cursor key: the key's
        // own redraw updates the cache and the next frame is empty.
        val midend = Midend.newGame(Pattern, "10x10", SEED)
        val sink = KotlinTraceSink()
        sink.recording = false
        midend.recordRedraw(sink)
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE),
        )
        midend.recordRedraw(sink)

        sink.ops.clear()
        sink.recording = true
        midend.recordRedraw(sink)
        assertEquals(
            listOf("start_draw", "end_draw"),
            sink.ops.map { (it.fields["op"] as JsonValue.Str).value },
        )
    }

    @Test
    fun `a sized warm frame matches at tile 48 and in the letterbox`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state

            // An exact-fit tile 48 viewport.
            val size = Pattern.computeSize(Pattern.decodeParams("10x10"), 48)
            assertEquals(768, size.width)
            assertEquals(768, size.height)
            val points48 = listOf(
                OracleClient.Point(coord(48, 0), coord(48, 0), Buttons.LEFT_BUTTON),
                OracleClient.Point(coord(48, 0), coord(48, 0), Buttons.LEFT_RELEASE),
            )
            val expected48 = TraceNormalizer.normalize(
                oracle.traceWarm(
                    save,
                    points48,
                    width = size.width,
                    height = size.height,
                ).trace!!,
                upstream(),
            )
            val midend48 = Midend.fromSave(Pattern, SgtpSave.parse(save))
            val geometry48 = midend48.geometryFor(size.width, size.height)
            assertEquals(48, geometry48.tileSize)
            val actual48 = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend48, points48, geometry48.tileSize),
                upstream(),
            )
            assertLines("warm tile 48", expected48, actual48)
            assertEquals(
                listOf("start_draw", "end_draw"),
                actual48.lines().drop(7).filter { it.isNotEmpty() },
            )

            // A letterboxed 411x702 view: tile 25 and origin (6,152).
            val midend = Midend.fromSave(Pattern, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            assertEquals(25, geometry.tileSize)
            assertEquals(6, geometry.originX)
            assertEquals(152, geometry.originY)
            val points = listOf(
                OracleClient.Point(coord(25, 0), coord(25, 0), Buttons.LEFT_BUTTON),
                OracleClient.Point(coord(25, 0), coord(25, 0), Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points, width = 411, height = 702).trace!!,
                upstream(),
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, points, geometry.tileSize),
                upstream(),
            )
            assertLines("warm letterbox", expected, actual)
            assertEquals(
                listOf("start_draw", "end_draw"),
                actual.lines().drop(7).filter { it.isNotEmpty() },
            )
        }

    private fun toCoord(p: Pattern.Params, cell: Int): Int =
        3 * TILE / 4 + TILE / 2 + TILE * (p.w / 5 + 2 + cell)

    /** The centre of cell 0 of a 10x10 board at [tile]. */
    private fun coord(tile: Int, cell: Int): Int =
        3 * tile / 4 + tile / 2 + tile * (4 + cell) + tile / 2

    /** The centre of cell [x] of a 10x10 board at tile 24. */
    private fun gameX(x: Int): Int = 3 * TILE / 4 + TILE / 2 + TILE * (4 + x) + TILE / 2

    private fun gameY(y: Int): Int = 3 * TILE / 4 + TILE / 2 + TILE * (4 + y) + TILE / 2

    /** The centre of cell [x] of a 2x2 board at tile 24. */
    private fun twoX(x: Int): Int = 3 * TILE / 4 + TILE / 2 + TILE * (2 + x) + TILE / 2

    private fun twoY(y: Int): Int = 3 * TILE / 4 + TILE / 2 + TILE * (2 + y) + TILE / 2
}
