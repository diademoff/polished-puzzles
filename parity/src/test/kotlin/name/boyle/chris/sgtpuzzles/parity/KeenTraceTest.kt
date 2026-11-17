package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: keen.
 *
 * The ten frozen first frames, the loaded-save frames that omit the
 * operator for the multiplication-only presets, four tile sizes and a
 * letterbox viewport, the warm frames after an inert and a real input,
 * the cursor, the digit and pencil text, the two error colours, and the
 * block polygons.
 *
 * Keen has no blitter, so every frame must be blitter-free.
 */
class KeenTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun saveFor(params: String): String =
        File(testdata, "saves/initial/keen/${GenTraces.fileName(params)}.sgtp").readText()

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
            for (params in oracle.presets("keen").map { it.params }) {
                val midend = Midend.newGame(Keen, params, SEED)
                val actual = normalize(KotlinTraceSink.trace(midend))
                val expected = normalize(oracle.traceNew("keen", params, SEED))
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/keen/${GenTraces.fileName(params)}.trace",
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
                assertTrue(
                    "'$params' has the changed_state line",
                    actual.lines().contains("changed_state false false"),
                )
                assertTrue(
                    "'$params' is blitter-free",
                    actual.lines().none { it.startsWith("blitter_") },
                )
                assertTrue(
                    "'$params' draws the block borders",
                    actual.lines().any { it.startsWith("thick_poly 3000 -1 2 ") },
                )
            }
        }

    @Test(timeout = 300_000)
    fun `a loaded save omits the operator for the multiplication-only presets`() =
        OracleClient().use { oracle ->
            for ((params, operator) in listOf(
                "5dem" to "5\u00d7",
                "6dnm" to "12\u00d7",
            )) {
                val save = saveFor(params)
                val expected = normalize(oracle.trace(save))
                val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
                val actual = normalize(KotlinTraceSink.stateTrace(midend))
                assertLines("loaded '$params'", expected, actual)

                // The loaded state has the flag, so the clue is the
                // number alone and the operator is nowhere in the frame.
                assertTrue(
                    "'$params' loaded state flag",
                    (midend.state as Keen.State).params.multiplicationOnly,
                )
                assertTrue(
                    "'$params' draws its clue",
                    actual.lines().any { it.startsWith("text ") && it.endsWith("\"${operator.dropLast(1)}\"") },
                )
                assertFalse(
                    "'$params' has no operator",
                    actual.lines().any { it.startsWith("text ") && it.contains("\u00d7") },
                )

                // A fresh game has the short parameters, so the operator
                // is drawn. The frozen trace pins the same fact.
                val fresh = Midend.newGame(Keen, params, SEED)
                val freshTrace = normalize(KotlinTraceSink.trace(fresh))
                assertTrue(
                    "'$params' fresh operator",
                    freshTrace.lines().any { it.startsWith("text ") && it.endsWith("\"$operator\"") },
                )
            }
        }

    @Test(timeout = 600_000)
    fun `the sized fresh traces match the oracle`() = OracleClient().use { oracle ->
        val save = saveFor("6dn")
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Keen.computeSize(Keen.decodeParams("6dn"), tile)
            val expected = oracle.pointsSized(
                save,
                emptyList(),
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
            val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
            assertLines("tile $tile", normalize(expected.trace!!), actual)
            assertTrue(
                "tile $tile size",
                actual.lines().contains("size ${board.width} ${board.height}"),
            )
            assertTrue(
                "tile $tile origin",
                actual.lines().contains(
                    "rect ${tile / 2 - maxOf(tile / 32, 1)} " +
                        "${tile / 2 - maxOf(tile / 32, 1)} " +
                        "${6 * tile + 1 + 2 * maxOf(tile / 32, 1)} " +
                        "${6 * tile + 1 + 2 * maxOf(tile / 32, 1)} 1",
                ),
            )
            assertTrue(
                "tile $tile has no blitter",
                actual.lines().none { it.startsWith("blitter_") },
            )
        }

        // The letterbox view finds tile 58 and origin (2, 148).
        val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(58, geometry.tileSize)
        assertEquals(2, geometry.originX)
        assertEquals(148, geometry.originY)
        val expected = oracle.pointsSized(save, emptyList(), 411, 702, recordTrace = true)
        val actual = normalize(KotlinTraceSink.stateTrace(midend, geometry.tileSize))
        assertLines("letterbox", normalize(expected.trace!!), actual)
        assertTrue(
            "letterbox size",
            actual.lines().contains("size ${geometry.width} ${geometry.height}"),
        )
    }

    @Test(timeout = 300_000)
    fun `the warm frames keep the block polygons and the full update`() =
        OracleClient().use { oracle ->
            val save = saveFor("6dn")
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Keen.computeSize(Keen.decodeParams("6dn"), tile)
                val expected = oracle.traceWarm(save, emptyList(), board.width, board.height)
                val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
                val actual = normalize(KotlinTraceSink.warmTrace(midend, emptyList(), tile))
                assertLines("warm tile $tile", normalize(expected.trace!!), actual)

                val lines = actual.trimEnd('\n').lines()
                assertTrue("start for tile $tile", lines.contains("start_draw"))
                assertTrue("end for tile $tile", lines.contains("end_draw"))
                assertEquals(
                    "blocks at tile $tile",
                    15,
                    lines.count { it.startsWith("thick_poly ") },
                )
                assertEquals(
                    "one full update at tile $tile",
                    1,
                    lines.count { it == "update 0 0 ${board.width} ${board.height}" },
                )
                assertTrue(
                    "warm tile $tile has no cell",
                    lines.none { it.startsWith("clip ") },
                )
                assertTrue(
                    "warm tile $tile has no blitter",
                    lines.none { it.startsWith("blitter_") },
                )
            }

            // The letterbox warm frame.
            val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            val expected = oracle.traceWarm(save, emptyList(), 411, 702)
            val actual = normalize(
                KotlinTraceSink.warmTrace(midend, emptyList(), geometry.tileSize),
            )
            assertLines("letterbox warm", normalize(expected.trace!!), actual)
            assertEquals(15, actual.lines().count { it.startsWith("thick_poly ") })
        }

    @Test(timeout = 300_000)
    fun `a warm frame after a real move draws the changed cell and the blocks`() =
        OracleClient().use { oracle ->
            val save = saveFor("6dn")
            val points = listOf(
                OracleClient.Point(26, 26, Buttons.LEFT_BUTTON),
                OracleClient.Point(26, 26, Buttons.LEFT_RELEASE),
                OracleClient.Point(0, 0, '2'.code),
            )
            val expected = oracle.traceWarm(save, points, 336, 336)
            val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
            val actual = normalize(KotlinTraceSink.warmTrace(midend, points, 48))
            assertLines("warm move", normalize(expected.trace!!), actual)
            assertEquals("2", midend.serialise().value("MOVE")!!.takeLast(1))

            val lines = actual.trimEnd('\n').lines()
            assertEquals(15, lines.count { it.startsWith("thick_poly ") })
            // The recorded frame is after the input redraw, so the tile
            // cache already holds the digit and no cell is drawn again.
            // The input's own frame is the one that draws the cell; the
            // oracle's `traceWarm` cannot express it, so pin it here.
            assertTrue(
                "the settled frame has no cell",
                lines.none { it.startsWith("clip ") },
            )

            val frameMidend = Midend.fromSave(Keen, SgtpSave.parse(save))
            val frame = normalize(
                KotlinTraceSink.warmFrame(frameMidend, points, 48),
            )
            val frameLines = frame.trimEnd('\n').lines()
            assertTrue(
                "the input frame clips the changed cell",
                frameLines.any { it.startsWith("clip 26 26 ") },
            )
            assertTrue(
                "the input frame updates the changed cell",
                frameLines.any { it.startsWith("update 26 26 ") },
            )
            assertTrue(
                "the input frame draws the digit",
                frameLines.any { it.startsWith("text ") && it.endsWith("\"2\"") },
            )
            assertEquals(15, frameLines.count { it.startsWith("thick_poly ") })
        }

    @Test(timeout = 300_000)
    fun `an inert warm click draws only the blocks and the full update`() =
        OracleClient().use { oracle ->
            val save = saveFor("6dn")
            val points = listOf(
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.traceWarm(save, points, 336, 336)
            val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
            val actual = normalize(KotlinTraceSink.warmTrace(midend, points, 48))
            assertLines("inert warm", normalize(expected.trace!!), actual)
            assertEquals(1, midend.stateCount)

            val lines = actual.trimEnd('\n').lines()
            assertEquals(15, lines.count { it.startsWith("thick_poly ") })
            assertTrue(
                "an inert click draws no cell",
                lines.none {
                    it.startsWith("clip ") || it.startsWith("rect ") ||
                        it.startsWith("poly ") || it.startsWith("text ")
                },
            )
        }

    @Test(timeout = 300_000)
    fun `the cursor and the pencil triangle match the oracle`() =
        OracleClient().use { oracle ->
            val save = saveFor("6dn")
            for (sequence in listOf(
                listOf(Buttons.CURSOR_UP),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code),
                listOf(Buttons.CURSOR_LEFT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            )) {
                val expected = normalize(oracle.trace(save, sequence))
                val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
                for (key in sequence) midend.processKey(0, 0, key, 48)
                val actual = normalize(KotlinTraceSink.stateTrace(midend))
                assertLines("cursor $sequence", expected, actual)
                assertTrue(
                    "cursor $sequence is blitter-free",
                    actual.lines().none { it.startsWith("blitter_") },
                )
            }

            // The plain up arrow shows the highlight rect.
            val cursor = Midend.fromSave(Keen, SgtpSave.parse(save))
            cursor.processKey(0, 0, Buttons.CURSOR_UP, 48)
            val cursorTrace = normalize(KotlinTraceSink.stateTrace(cursor))
            assertEquals(15, cursorTrace.lines().count { it.startsWith("thick_poly ") })

            // Select turns it into a pencil triangle on the clue cell.
            cursor.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            val pencilTrace = normalize(KotlinTraceSink.stateTrace(cursor))
            assertTrue(
                "the pencil triangle",
                pencilTrace.lines().any { it.startsWith("poly 4 4 3 26 26 ") },
            )
            assertTrue(
                "the clue is still drawn",
                pencilTrace.lines().any { it.startsWith("text ") && it.endsWith("\"8+\"") },
            )
        }

    @Test(timeout = 300_000)
    fun `a digit, a pencil mark and the two errors match the oracle`() =
        OracleClient().use { oracle ->
            val save = saveFor("6dn")
            val aux = requireNotNull(Midend.fromSave(Keen, SgtpSave.parse(save)).auxInfo)

            // The solution digit at (0,0) is 2. It is drawn at size 24
            // and centred, on top of the clue text.
            val digit = listOf(
                OracleClient.Point(26, 26, Buttons.LEFT_BUTTON),
                OracleClient.Point(26, 26, Buttons.LEFT_RELEASE),
                OracleClient.Point(0, 0, '2'.code),
            )
            val expectedDigit = oracle.pointsSized(save, digit, 336, 336, recordTrace = true)
            val digitMidend = Midend.fromSave(Keen, SgtpSave.parse(save))
            for (point in digit) digitMidend.processKey(point.x, point.y, point.button, 48)
            val digitTrace = normalize(KotlinTraceSink.stateTrace(digitMidend))
            assertLines("digit", normalize(expectedDigit.trace!!), digitTrace)
            assertTrue(
                "the digit text",
                digitTrace.lines().any { it.startsWith("text 50 50 1 24 257 3 \"2\"") },
            )

            // A pencil mark on the clue cell goes into the small grid.
            val pencil = listOf(
                OracleClient.Point(26, 26, Buttons.RIGHT_BUTTON),
                OracleClient.Point(26, 26, Buttons.RIGHT_RELEASE),
                OracleClient.Point(0, 0, '3'.code),
            )
            val expectedPencil = oracle.pointsSized(save, pencil, 336, 336, recordTrace = true)
            val pencilMidend = Midend.fromSave(Keen, SgtpSave.parse(save))
            for (point in pencil) pencilMidend.processKey(point.x, point.y, point.button, 48)
            val pencilTrace = normalize(KotlinTraceSink.stateTrace(pencilMidend))
            assertLines("pencil", normalize(expectedPencil.trace!!), pencilTrace)
            assertTrue(
                "the pencil text",
                pencilTrace.lines().any { it.startsWith("text ") && it.endsWith("\"3\"") },
            )

            // Two equal digits in row 0: both are COL_ERROR.
            val duplicate = listOf(
                OracleClient.Point(26, 26, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '1'.code),
                OracleClient.Point(74, 26, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '1'.code),
            )
            val expectedError = oracle.pointsSized(save, duplicate, 336, 336, recordTrace = true)
            val errorMidend = Midend.fromSave(Keen, SgtpSave.parse(save))
            for (point in duplicate) errorMidend.processKey(point.x, point.y, point.button, 48)
            val errorTrace = normalize(KotlinTraceSink.stateTrace(errorMidend))
            assertLines("error", normalize(expectedError.trace!!), errorTrace)
            assertTrue(
                "a red digit",
                errorTrace.lines().any {
                    it.startsWith("text ") && it.endsWith(" 5 \"1\"")
                },
            )

            // A complete block with a wrong value: the clue is red. Play
            // the whole solution, then break one cell of the first block.
            val wrongPoints = ArrayList<OracleClient.Point>()
            for (cell in 0 until 36) {
                val x = 24 + (cell % 6) * 48 + 2
                val y = 24 + (cell / 6) * 48 + 2
                wrongPoints += OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
                wrongPoints += OracleClient.Point(x, y, Buttons.LEFT_RELEASE)
                wrongPoints += OracleClient.Point(0, 0, aux[cell + 1].code)
            }
            val replacement = if (aux[1] == '1') '2' else '1'
            wrongPoints += OracleClient.Point(26, 26, Buttons.LEFT_BUTTON)
            wrongPoints += OracleClient.Point(26, 26, Buttons.LEFT_RELEASE)
            wrongPoints += OracleClient.Point(0, 0, replacement.code)
            val expectedWrong = oracle.pointsSized(save, wrongPoints, 336, 336, recordTrace = true)
            val wrongMidend = Midend.fromSave(Keen, SgtpSave.parse(save))
            for (point in wrongPoints) wrongMidend.processKey(point.x, point.y, point.button, 48)
            val wrongTrace = normalize(KotlinTraceSink.stateTrace(wrongMidend))
            assertLines("clue error", normalize(expectedWrong.trace!!), wrongTrace)
            assertTrue(
                "the clue is red",
                wrongTrace.lines().any {
                    it.startsWith("text 28 ") && it.endsWith(" 5 \"8+\"")
                },
            )
        }

    @Test(timeout = 600_000)
    fun `a completed board draws every digit and no error colour`() =
        OracleClient().use { oracle ->
            val save = saveFor("6dn")
            val aux = requireNotNull(Midend.fromSave(Keen, SgtpSave.parse(save)).auxInfo)
            val points = ArrayList<OracleClient.Point>()
            for (cell in 0 until 36) {
                val x = 24 + (cell % 6) * 48 + 2
                val y = 24 + (cell / 6) * 48 + 2
                points += OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
                points += OracleClient.Point(x, y, Buttons.LEFT_RELEASE)
                points += OracleClient.Point(0, 0, aux[cell + 1].code)
            }
            val expected = oracle.pointsSized(save, points, 336, 336, recordTrace = true)
            assertEquals(1, expected.state.status)
            val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
            for (point in points) midend.processKey(point.x, point.y, point.button, 48)
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("completed", normalize(expected.trace!!), actual)
            assertTrue(
                "the completed frame is blitter-free",
                actual.lines().none { it.startsWith("blitter_") },
            )
        }

    private companion object {
        const val SEED = "12345"
    }
}
