package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Towers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: towers.
 *
 * The seven frozen first frames, four tile sizes and a letterbox
 * viewport, the empty warm frames after every kind of input, the two 3D
 * tower faces and the digit, the pencil triangle and marks, the error
 * and done-clue colours, the `appearance` preference, and the
 * four-tuple dirty-cell cache.
 *
 * Towers has no blitter and no flash, so every frame must be
 * blitter-free and contain no flash colour change.
 */
class TowersTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun saveFor(params: String): String =
        File(testdata, "saves/initial/towers/${GenTraces.fileName(params)}.sgtp").readText()

    private fun center(x: Int, y: Int, tile: Int = 48): Pair<Int, Int> {
        val border = tile * 9 / 8
        return Pair(border + x * tile + tile / 2, border + y * tile + tile / 2)
    }

    private fun pressAndRelease(x: Int, y: Int, tile: Int = 48): List<OracleClient.Point> {
        val (px, py) = center(x, y, tile)
        return listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }

    @Test(timeout = 600_000)
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("towers").map { it.params }) {
                val midend = Midend.newGame(Towers, params, SEED)
                val actual = normalize(KotlinTraceSink.trace(midend))
                val expected = normalize(oracle.traceNew("towers", params, SEED))
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/towers/${GenTraces.fileName(params)}.trace",
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
                    "one changed_state for '$params'",
                    1,
                    actual.lines().count { it == "changed_state false false" },
                )
                assertTrue(
                    "'$params' is blitter-free",
                    actual.lines().none { it.startsWith("blitter_") },
                )
            }

            // The 5x5 first frame draws one clip per (w+2)^2 cell and one
            // more update, ending with the midend's whole-window update.
            val five = normalize(
                KotlinTraceSink.trace(Midend.newGame(Towers, "5de", SEED)),
            )
            assertEquals(49, five.lines().count { it.startsWith("clip ") })
            assertEquals(50, five.lines().count { it.startsWith("update ") })
            assertTrue(
                "the whole-window update",
                five.lines().contains("update 0 0 348 348"),
            )
            assertEquals("end_draw", five.trimEnd('\n').lines().last())
            assertTrue(
                "a clue cell",
                five.lines().contains("text 30 78 1 19 257 1 \"5\""),
            )
            assertTrue(
                "an empty cell outline",
                five.lines().contains("poly -1 1 4 54 53 102 53 102 101 54 101"),
            )
        }

    @Test(timeout = 600_000)
    fun `the sized loaded-save traces match the oracle`() = OracleClient().use { oracle ->
        val save = saveFor("5de")
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Towers.computeSize(Towers.decodeParams("5de"), tile)
            val expected = oracle.pointsSized(
                save,
                emptyList(),
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
            val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
            assertLines("tile $tile", normalize(expected.trace!!), actual)
            assertTrue(
                "tile $tile size",
                actual.lines().contains("size ${board.width} ${board.height}"),
            )
            assertTrue(
                "tile $tile has no blitter",
                actual.lines().none { it.startsWith("blitter_") },
            )

            // A tower at every tile size: the cold frame after a digit
            // has the two 3D faces, and the warm frame is empty.
            val digitPoints = pressAndRelease(0, 0, tile) +
                listOf(OracleClient.Point(0, 0, '3'.code))
            val coldExpected = oracle.pointsSized(
                save,
                digitPoints,
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val coldMidend = Midend.fromSave(Towers, SgtpSave.parse(save))
            for (point in digitPoints) coldMidend.processKey(point.x, point.y, point.button, tile)
            val coldActual = normalize(KotlinTraceSink.stateTrace(coldMidend, tile))
            assertLines("tile $tile digit", normalize(coldExpected.trace!!), coldActual)
            assertTrue(
                "tile $tile tower polygon",
                coldActual.lines().any { it.startsWith("poly 3 1 4 ") },
            )

            val warmExpected = oracle.traceWarm(save, digitPoints, board.width, board.height)
            val warmMidend = Midend.fromSave(Towers, SgtpSave.parse(save))
            val warmActual = normalize(KotlinTraceSink.warmTrace(warmMidend, digitPoints, tile))
            assertLines("tile $tile warm", normalize(warmExpected.trace!!), warmActual)
            assertEquals(
                "tile $tile warm is one empty frame",
                listOf("start_draw", "end_draw"),
                warmActual.trimEnd('\n').lines().takeLast(2),
            )
        }

        // The letterbox view finds tile 56 and origin (2, 148).
        val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(56, geometry.tileSize)
        assertEquals(2, geometry.originX)
        assertEquals(148, geometry.originY)
        val expected = oracle.pointsSized(save, emptyList(), 411, 702, recordTrace = true)
        val actual = normalize(KotlinTraceSink.stateTrace(midend, geometry.tileSize))
        assertLines("letterbox", normalize(expected.trace!!), actual)
        assertTrue(
            "letterbox size",
            actual.lines().contains("size ${geometry.width} ${geometry.height}"),
        )

        // A tower in the letterbox view draws its 3D faces at tile 56.
        // The oracle receives view coordinates and subtracts the origin.
        val digitPoints = pressAndRelease(0, 0, geometry.tileSize).map {
            OracleClient.Point(
                it.x + geometry.originX,
                it.y + geometry.originY,
                it.button,
            )
        } + OracleClient.Point(0, 0, '3'.code)
        val coldExpected = oracle.pointsSized(save, digitPoints, 411, 702, recordTrace = true)
        val coldMidend = Midend.fromSave(Towers, SgtpSave.parse(save))
        for (point in digitPoints) {
            coldMidend.processKey(
                point.x - geometry.originX,
                point.y - geometry.originY,
                point.button,
                geometry.tileSize,
            )
        }
        val coldActual = normalize(KotlinTraceSink.stateTrace(coldMidend, geometry.tileSize))
        assertLines("letterbox digit", normalize(coldExpected.trace!!), coldActual)
        assertTrue(
            "letterbox tower polygon",
            coldActual.lines().any { it.startsWith("poly 3 1 4 ") },
        )
    }

    @Test(timeout = 600_000)
    fun `a fresh game matches the oracle at every tile size`() =
        OracleClient().use { oracle ->
            val state = oracle.newGame("towers", "6dx", SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Towers.computeSize(Towers.decodeParams("6dx"), tile)
                val expected = oracle.pointsSized(
                    state,
                    emptyList(),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = Midend.newGame(Towers, "6dx", SEED)
                val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
                assertLines("fresh tile $tile", normalize(expected.trace!!), actual)
                assertEquals("fresh tile $tile state count", 1, midend.stateCount)
                assertTrue(
                    "fresh tile $tile has no blitter",
                    actual.lines().none { it.startsWith("blitter_") },
                )
            }
        }

    @Test(timeout = 600_000)
    fun `every warm frame after an input is empty`() = OracleClient().use { oracle ->
        val save = saveFor("5de")
        val cases = listOf(
            "click" to pressAndRelease(0, 0),
            "second click" to pressAndRelease(0, 0) + pressAndRelease(0, 0),
            "digit" to pressAndRelease(0, 0) + listOf(OracleClient.Point(0, 0, '3'.code)),
            "pencil" to pressAndRelease(0, 0) + pressAndRelease(0, 0) +
                listOf(OracleClient.Point(0, 0, '3'.code)),
            "M" to listOf(OracleClient.Point(0, 0, 'M'.code)),
            "D" to listOf(
                OracleClient.Point(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
            ),
            "clear" to pressAndRelease(0, 0) +
                listOf(OracleClient.Point(0, 0, '3'.code), OracleClient.Point(0, 0, '\b'.code)),
        )
        for ((name, points) in cases) {
            val expected = oracle.traceWarm(save, points, 348, 348)
            val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
            val actual = normalize(KotlinTraceSink.warmTrace(midend, points, 48))
            assertLines("warm $name", normalize(expected.trace!!), actual)
            assertEquals(
                "warm $name is one empty frame",
                listOf("start_draw", "end_draw"),
                actual.trimEnd('\n').lines().takeLast(2),
            )
            assertEquals(
                "warm $name has no operation",
                2,
                actual.lines().count { it.startsWith("start_draw") || it.startsWith("end_draw") },
            )
            assertEquals(
                "warm $name has no clip",
                0,
                actual.lines().count { it.startsWith("clip ") },
            )
        }
    }

    @Test(timeout = 600_000)
    fun `a cold frame after a digit draws the two tower faces and the digit`() =
        OracleClient().use { oracle ->
            val save = saveFor("5de")
            val points = pressAndRelease(0, 0) + listOf(OracleClient.Point(0, 0, '3'.code))
            val expected = oracle.pointsSized(save, points, 348, 348, recordTrace = true)
            val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
            for (point in points) midend.processKey(point.x, point.y, point.button, 48)
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("digit", normalize(expected.trace!!), actual)

            val lines = actual.lines()
            // The tower is at (0,0), height 3, so xoff 3 and yoff 7.
            assertTrue(
                "the left face",
                lines.contains("poly 3 1 4 54 53 54 101 57 94 57 46"),
            )
            assertTrue(
                "the bottom face",
                lines.contains("poly 3 1 4 102 101 54 101 57 94 105 94"),
            )
            assertTrue(
                "the digit moved to the tower top",
                lines.contains("text 81 71 1 24 257 2 \"3\""),
            )
            assertTrue(
                "the tower is inside a clip",
                lines.any { it.startsWith("clip 54 54 48 48") },
            )
        }

    @Test(timeout = 600_000)
    fun `a cold frame after M and after a D move shows the marks and the done clue`() =
        OracleClient().use { oracle ->
            val save = saveFor("5de")

            // M fills every empty cell with pencil marks.
            val mPoints = listOf(OracleClient.Point(0, 0, 'M'.code))
            val expectedM = oracle.pointsSized(save, mPoints, 348, 348, recordTrace = true)
            val mMidend = Midend.fromSave(Towers, SgtpSave.parse(save))
            mMidend.processKey(0, 0, 'M'.code, 48)
            val actualM = normalize(KotlinTraceSink.stateTrace(mMidend))
            assertLines("M", normalize(expectedM.trace!!), actualM)
            val marks = actualM.lines().filter {
                it.startsWith("text ") && it.endsWith(" 5 \"5\"") &&
                    it.contains(" 14 257 ")
            }
            assertTrue("five pencil marks of size 14", marks.isNotEmpty())
            assertTrue(
                "the pencil marks start at the measured spot",
                actualM.lines().contains("text 129 65 1 14 257 5 \"2\""),
            )

            // A clue click marks the clue done in COL_DONE.
            val cluePoints = listOf(
                OracleClient.Point(30, 78, Buttons.LEFT_BUTTON),
                OracleClient.Point(30, 78, Buttons.LEFT_RELEASE),
            )
            val expectedD = oracle.pointsSized(save, cluePoints, 348, 348, recordTrace = true)
            val dMidend = Midend.fromSave(Towers, SgtpSave.parse(save))
            for (point in cluePoints) dMidend.processKey(point.x, point.y, point.button, 48)
            val actualD = normalize(KotlinTraceSink.stateTrace(dMidend))
            assertLines("D", normalize(expectedD.trace!!), actualD)
            assertTrue(
                "the done clue uses COL_DONE",
                actualD.lines().any {
                    it.startsWith("text 30 78 1 19 257 6 ")
                },
            )
        }

    @Test(timeout = 600_000)
    fun `a cold frame after a duplicate marks both digits in COL_ERROR`() =
        OracleClient().use { oracle ->
            val save = saveFor("5de")
            val points = pressAndRelease(0, 0) + listOf(OracleClient.Point(0, 0, '1'.code)) +
                pressAndRelease(1, 0) + listOf(OracleClient.Point(0, 0, '1'.code))
            val expected = oracle.pointsSized(save, points, 348, 348, recordTrace = true)
            val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
            for (point in points) midend.processKey(point.x, point.y, point.button, 48)
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("error", normalize(expected.trace!!), actual)
            assertTrue(
                "a red digit",
                actual.lines().any { it.startsWith("text ") && it.endsWith(" 4 \"1\"") },
            )
        }

    @Test(timeout = 600_000)
    fun `a completed board matches the oracle and has no flash`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("towers", "3de", "1").state
            val aux = requireNotNull(Midend.fromSave(Towers, SgtpSave.parse(start)).auxInfo)
            val points = ArrayList<OracleClient.Point>()
            for (cell in 0 until 9) {
                val x = cell % 3
                val y = cell / 3
                points += pressAndRelease(x, y)
                points += OracleClient.Point(0, 0, aux[cell + 1].code)
            }
            val expected = oracle.pointsSized(start, points, 252, 252, recordTrace = true)
            assertEquals(1, expected.state.status)
            val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
            for (point in points) midend.processKey(point.x, point.y, point.button, 48)
            assertEquals(1, midend.status())
            val actual = normalize(KotlinTraceSink.stateTrace(midend, 48))
            assertLines("completed", normalize(expected.trace!!), actual)
            assertTrue(
                "the completed frame is blitter-free",
                actual.lines().none { it.startsWith("blitter_") },
            )
        }

    @Test(timeout = 600_000)
    fun `the appearance preference drops the tower polygons`() =
        OracleClient().use { oracle ->
            val save = saveFor("5de")
            val sequence = listOf(Buttons.CURSOR_UP, '3'.code)
            val expected = normalize(
                oracle.trace(save, sequence, prefs = "appearance=2d"),
            )
            val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
            (midend.ui as Towers.Ui).threeD = false
            for (key in sequence) midend.processKey(0, 0, key, 48)
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("2d", expected, actual)
            assertFalse(
                "no tower face polygon",
                actual.lines().any {
                    it.startsWith("poly 2 ") || it.startsWith("poly 3 ")
                },
            )
            assertTrue(
                "the digit is still drawn",
                actual.lines().any { it.startsWith("text ") && it.endsWith("\"3\"") },
            )

            // The same frame in 3D has the tower faces, so the
            // preference is the difference.
            val threeD = normalize(oracle.trace(save, sequence))
            assertTrue(
                "3D has a tower face",
                threeD.lines().any { it.startsWith("poly 3 1 4 ") },
            )
        }

    @Test(timeout = 300_000)
    fun `the pencil-keep-highlight preference is accepted in a cold frame`() =
        OracleClient().use { oracle ->
            val save = saveFor("5de")
            val sequence = listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code)
            val expected = normalize(
                oracle.trace(save, sequence, prefs = "pencil-keep-highlight=false"),
            )
            val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
            (midend.ui as Towers.Ui).pencilKeepHighlight = false
            for (key in sequence) midend.processKey(0, 0, key, 48)
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("keep-highlight", expected, actual)
            // A keyboard entry is a cursor highlight, so the preference
            // does not hide it; the pencil triangle is still drawn.
            assertTrue(
                "the pencil triangle",
                actual.lines().any { it.startsWith("poly 3 3 3 ") },
            )
        }

    private companion object {
        const val SEED = "12345"
    }
}
