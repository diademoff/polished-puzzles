package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unequal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: unequal.
 *
 * The twelve frozen first frames, four tile sizes and a letterbox
 * viewport, the empty warm frames after every kind of input, the
 * six-point arrows, the adjacency bars, the four-point outlines, the
 * pencil triangle, the spent and error colours, the digit and hint text
 * sizes, and the column-major dirty-cell cache.
 *
 * Unequal has no blitter, no clip, no thick primitive, no flash, and no
 * status bar, so every frame must be free of those operations.
 */
class UnequalTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun saveFor(params: String): String =
        File(testdata, "saves/initial/unequal/${GenTraces.fileName(params)}.sgtp").readText()

    private fun handSave(
        desc: String,
        params: String = "4de",
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection"
        records += "VERSION" to "1"
        records += "GAME" to "Unequal"
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "SEED" to "1"
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        records += history
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun grid(
        order: Int,
        flags: Map<Int, String> = emptyMap(),
        numbers: Map<Int, Int> = emptyMap(),
    ): String {
        val out = StringBuilder()
        for (i in 0 until order * order) {
            out.append(numbers[i] ?: 0)
            out.append(flags[i] ?: "")
            out.append(',')
        }
        return out.toString()
    }

    private fun center(x: Int, y: Int, tile: Int): Pair<Int, Int> {
        val border = tile / 2
        val square = tile + border
        return Pair(
            border + x * square + tile / 2,
            border + y * square + tile / 2,
        )
    }

    private fun click(x: Int, y: Int, tile: Int, button: Int = Buttons.LEFT_BUTTON):
        List<OracleClient.Point> {
        val (px, py) = center(x, y, tile)
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> button
        }
        return listOf(
            OracleClient.Point(px, py, button),
            OracleClient.Point(px, py, release),
        )
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tile: Int,
    ) {
        for (point in points) midend.processKey(point.x, point.y, point.button, tile)
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }

    private fun assertNoForbidden(what: String, trace: String) {
        assertTrue(
            "$what has no blitter",
            trace.lines().none { it.startsWith("blitter_") },
        )
        assertTrue("$what has no clip", trace.lines().none { it.startsWith("clip ") })
        assertTrue("$what has no unclip", trace.lines().none { it == "unclip" })
        assertTrue("$what has no line", trace.lines().none { it.startsWith("line ") })
        assertTrue("$what has no thick line", trace.lines().none { it.startsWith("thick_line") })
        assertTrue("$what has no circle", trace.lines().none { it.startsWith("circle ") })
        assertTrue("$what has no thick circle", trace.lines().none { it.startsWith("thick_circle") })
        assertTrue("$what has no thick poly", trace.lines().none { it.startsWith("thick_poly") })
        assertTrue("$what has no status", trace.lines().none { it.startsWith("status ") })
    }

    @Test(timeout = 600_000)
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("unequal").map { it.params }) {
                val midend = Midend.newGame(Unequal, params, SEED)
                val actual = normalize(KotlinTraceSink.trace(midend))
                val expected = normalize(oracle.traceNew("unequal", params, SEED))
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/unequal/${GenTraces.fileName(params)}.trace",
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
                assertNoForbidden("'$params'", actual)

                val board = Unequal.computeSize(Unequal.decodeParams(params), 32).width
                assertEquals(
                    "two whole-window clears for '$params'",
                    2,
                    actual.lines().count { it == "rect 0 0 $board $board 0" },
                )
                assertEquals(
                    "two whole-window updates for '$params'",
                    2,
                    actual.lines().count { it == "update 0 0 $board $board" },
                )
            }

            // The 4x4 first frame has the six-point arrows, the
            // four-point cell outlines, and the immutable digit.
            val four = normalize(KotlinTraceSink.trace(Midend.newGame(Unequal, "4de", SEED)))
            assertTrue(
                "a six-point arrow",
                four.lines().any { it.startsWith("poly 2 2 6 ") },
            )
            assertTrue(
                "a four-point outline",
                four.lines().contains("poly -1 1 4 16 16 16 47 47 47 47 16"),
            )
            assertTrue(
                "the immutable digit",
                four.contains("text 128 128 1 24 257 2 \"3\""),
            )

            // Adjacent mode draws bars, not arrows.
            val adjacent = normalize(
                KotlinTraceSink.trace(Midend.newGame(Unequal, "5adk", SEED)),
            )
            assertTrue(
                "an adjacency bar",
                adjacent.lines().any { it.startsWith("rect ") && it.endsWith(" 1") },
            )
            assertTrue(
                "no six-point arrow",
                adjacent.lines().none { it.startsWith("poly 2 2 6 ") },
            )
        }

    @Test(timeout = 600_000)
    fun `the sized loaded-save traces match the oracle`() = OracleClient().use { oracle ->
        for (params in oracle.presets("unequal").map { it.params }) {
            val save = saveFor(params)
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Unequal.computeSize(Unequal.decodeParams(params), tile)
                val expected = oracle.pointsSized(
                    save,
                    emptyList(),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
                val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
                assertLines("$params tile $tile", normalize(expected.trace!!), actual)
                assertTrue(
                    "$params tile $tile has the size",
                    actual.lines().contains("size ${board.width} ${board.height}"),
                )
                assertNoForbidden("$params tile $tile", actual)
            }
        }

        // A cold frame after a digit: the number text uses the 3T/4
        // size and the guess colour.
        val save = saveFor("5de")
        for (tile in listOf(48, 82)) {
            val board = Unequal.computeSize(Unequal.decodeParams("5de"), tile).width
            val points = click(0, 0, tile) + OracleClient.Point(0, 0, '3'.code)
            val expected = oracle.pointsSized(save, points, board, board, recordTrace = true)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
            apply(midend, points, tile)
            val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
            assertLines("digit tile $tile", normalize(expected.trace!!), actual)
            assertTrue(
                "digit tile $tile uses 3T/4",
                actual.lines().any {
                    it.startsWith("text ") &&
                        it.split(' ')[4] == (3 * tile / 4).toString()
                },
            )
        }
    }

    @Test(timeout = 600_000)
    fun `the letterbox view traces at the found tile`() = OracleClient().use { oracle ->
        for (params in oracle.presets("unequal").map { it.params }) {
            val save = saveFor(params)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            val expected = oracle.pointsSized(
                save,
                emptyList(),
                411,
                702,
                recordTrace = true,
            )
            val actual = normalize(KotlinTraceSink.stateTrace(midend, geometry.tileSize))
            assertLines("$params letterbox", normalize(expected.trace!!), actual)
            assertTrue(
                "$params letterbox size",
                actual.lines().contains("size ${geometry.width} ${geometry.height}"),
            )
        }
        // The four preset orders find four tiles, so a hard-coded tile
        // would fail.
        val tiles = listOf("4de" to 63, "5de" to 51, "6de" to 43, "7dk" to 37)
        for ((params, tile) in tiles) {
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(saveFor(params)))
            assertEquals(params, tile, midend.geometryFor(411, 702).tileSize)
        }
    }

    @Test(timeout = 600_000)
    fun `every warm frame after an input is empty`() = OracleClient().use { oracle ->
        val save = saveFor("5de")
        val spent = List(2) { OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT) } +
            List(2) { OracleClient.Point(0, 0, Buttons.CURSOR_DOWN) } +
            OracleClient.Point(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
        val cases = listOf(
            "click" to click(0, 0, 48),
            "second click" to click(0, 0, 48) + click(0, 0, 48),
            "digit" to click(0, 0, 48) + OracleClient.Point(0, 0, '3'.code),
            "pencil" to click(0, 0, 48) + click(0, 0, 48) +
                OracleClient.Point(0, 0, '3'.code),
            "M" to listOf(OracleClient.Point(0, 0, 'M'.code)),
            "H" to listOf(OracleClient.Point(0, 0, 'H'.code)),
            "F" to spent,
            "clear" to click(0, 0, 48) +
                OracleClient.Point(0, 0, '3'.code) +
                OracleClient.Point(0, 0, '\b'.code),
        )
        for ((name, points) in cases) {
            val expected = oracle.traceWarm(save, points, 384, 384)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
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
                actual.lines().count {
                    it.startsWith("start_draw") || it.startsWith("end_draw")
                },
            )
        }
    }

    @Test(timeout = 600_000)
    fun `a cold frame shows the pencil triangle and the old and new highlight`() =
        OracleClient().use { oracle ->
            val save = saveFor("5de")

            // A cursor highlight in pencil mode: the three-point
            // triangle, filled and outlined in COL_HIGHLIGHT.
            val pencilSeq = listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT)
            val pencilExpected = oracle.trace(save, pencilSeq)
            val pencil = Midend.fromSave(Unequal, SgtpSave.parse(save))
            for (key in pencilSeq) pencil.processKey(0, 0, key, 48)
            val pencilActual = normalize(KotlinTraceSink.stateTrace(pencil))
            assertLines("pencil", normalize(pencilExpected), pencilActual)
            assertTrue(
                "the three-point triangle",
                pencilActual.lines().any { it.startsWith("poly 6 6 3 ") },
            )

            // A highlight move repaints the old and the new cell only.
            val first = click(0, 0, 48)
            val move = click(1, 0, 48)
            val moved = Midend.fromSave(Unequal, SgtpSave.parse(save))
            val frame = normalize(
                KotlinTraceSink.warmFrame(moved, first + move.dropLast(1), 48),
            )
            assertEquals(
                "two cell outlines in the input frame",
                2,
                frame.lines().count { it.startsWith("poly -1 1 4 ") },
            )
            assertTrue(
                "the old cell is redrawn",
                frame.lines().contains("poly -1 1 4 24 24 24 71 71 71 71 24"),
            )
            assertTrue(
                "the new cell is redrawn",
                frame.lines().contains("poly -1 1 4 96 24 96 71 143 71 143 24"),
            )

            // The same cell in pencil mode paints one cell and the
            // triangle.
            val same = Midend.fromSave(Unequal, SgtpSave.parse(save))
            val sameFrame = normalize(
                KotlinTraceSink.warmFrame(same, click(0, 0, 48) + click(0, 0, 48).take(1), 48),
            )
            assertEquals(
                "one cell outline in the pencil frame",
                1,
                sameFrame.lines().count { it.startsWith("poly -1 1 4 ") },
            )
            assertTrue(
                "the pencil triangle in the input frame",
                sameFrame.lines().any { it.startsWith("poly 6 6 3 ") },
            )
        }

    @Test(timeout = 600_000)
    fun `the spent and error clues use the C colours`() = OracleClient().use { oracle ->
        val save = saveFor("5de")
        val spent = List(2) { OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT) } +
            List(2) { OracleClient.Point(0, 0, Buttons.CURSOR_DOWN) } +
            OracleClient.Point(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
        val expected = oracle.trace(save, spent.map { it.button })
        val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
        for (point in spent) midend.processKey(0, 0, point.button, 48)
        val actual = normalize(KotlinTraceSink.stateTrace(midend))
        assertLines("spent", normalize(expected), actual)
        assertTrue(
            "the spent arrow uses COL_SPENT",
            actual.lines().any { it.startsWith("poly 7 7 6 ") },
        )

        // A violated greater-than clue is red.
        val errorStart = handSave(grid(3, flags = mapOf(0 to "D")), "3de")
        val errorPoints = click(0, 0, 48) + OracleClient.Point(0, 0, '1'.code) +
            click(0, 1, 48) + OracleClient.Point(0, 0, '2'.code)
        val errorExpected = oracle.pointsSized(errorStart, errorPoints, 240, 240, recordTrace = true)
        val errorMidend = Midend.fromSave(Unequal, SgtpSave.parse(errorStart))
        apply(errorMidend, errorPoints, 48)
        val errorActual = normalize(KotlinTraceSink.stateTrace(errorMidend, 48))
        assertLines("error clue", normalize(errorExpected.trace!!), errorActual)
        assertTrue(
            "the error arrow uses COL_ERROR",
            errorActual.lines().any { it.startsWith("poly 4 4 6 ") },
        )

        // A duplicate digit turns both numbers red.
        val duplicatePoints = click(0, 0, 48) + OracleClient.Point(0, 0, '1'.code) +
            click(1, 0, 48) + OracleClient.Point(0, 0, '1'.code)
        val zero = handSave(grid(3), "3de")
        val duplicateExpected = oracle.pointsSized(zero, duplicatePoints, 240, 240, recordTrace = true)
        val duplicate = Midend.fromSave(Unequal, SgtpSave.parse(zero))
        apply(duplicate, duplicatePoints, 48)
        val duplicateActual = normalize(KotlinTraceSink.stateTrace(duplicate, 48))
        assertLines("duplicate", normalize(duplicateExpected.trace!!), duplicateActual)
        assertEquals(
            "two red digits",
            2,
            duplicateActual.lines().count {
                it.startsWith("text ") && it.endsWith(" 4 \"1\"")
            },
        )

        // An unexpected Adjacent pair draws the outline bar.
        val adj = handSave(grid(5), "5adk")
        val adjPoints = click(0, 0, 48) + OracleClient.Point(0, 0, '1'.code) +
            click(1, 0, 48) + OracleClient.Point(0, 0, '2'.code)
        val adjExpected = oracle.pointsSized(adj, adjPoints, 384, 384, recordTrace = true)
        val adjMidend = Midend.fromSave(Unequal, SgtpSave.parse(adj))
        apply(adjMidend, adjPoints, 48)
        val adjActual = normalize(KotlinTraceSink.stateTrace(adjMidend, 48))
        assertLines("adjacent error", normalize(adjExpected.trace!!), adjActual)
        assertTrue(
            "the outline bar is a poly(-1)",
            adjActual.lines().any { it.startsWith("poly -1 4 4 ") },
        )
    }

    @Test(timeout = 600_000)
    fun `a completed board matches the oracle and has no flash`() =
        OracleClient().use { oracle ->
            val start = handSave("0D,0,0,0,0,0,0R,0,0U,", "3de")
            val sequence = listOf(
                Buttons.CURSOR_UP, '3'.code,
                Buttons.CURSOR_RIGHT, '2'.code,
                Buttons.CURSOR_RIGHT, '1'.code,
                Buttons.CURSOR_DOWN, '2'.code,
                Buttons.CURSOR_LEFT, '3'.code,
                Buttons.CURSOR_LEFT, '1'.code,
                Buttons.CURSOR_DOWN, '2'.code,
                Buttons.CURSOR_RIGHT, '1'.code,
                Buttons.CURSOR_RIGHT, '3'.code,
            )
            val points = sequence.map { OracleClient.Point(0, 0, it) }
            val expected = oracle.pointsSized(start, points, 240, 240, recordTrace = true)
            assertEquals(1, expected.state.status)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
            apply(midend, points, 48)
            assertEquals(1, midend.status())
            val actual = normalize(KotlinTraceSink.stateTrace(midend, 48))
            assertLines("completed", normalize(expected.trace!!), actual)
            assertNoForbidden("completed", actual)
            assertTrue(
                "a completed digit uses COL_GUESS",
                actual.lines().any { it.startsWith("text ") && it.endsWith(" 3 \"3\"") },
            )
        }

    @Test(timeout = 300_000)
    fun `the hint marks use the pencil colour and the computed size`() =
        OracleClient().use { oracle ->
            // An empty 5x5 has no clues, so the Easy solver leaves
            // every pencil mark and the frame shows the mark grid.
            val save = handSave(grid(5), "5de")
            val sequence = listOf('H'.code)
            val expected = oracle.trace(save, sequence)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
            midend.processKey(0, 0, 'H'.code, 48)
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("hints", normalize(expected), actual)

            // For one or two marks the size is T/3 at T=32 and the mark
            // is at (ox+6, oy+9) or its pair.
            val marks = actual.lines().filter {
                it.startsWith("text ") && it.contains(" 5 \"")
            }
            assertTrue("pencil marks", marks.isNotEmpty())
            assertTrue(
                "the mark size is T/3 for a small set",
                marks.all { it.split(' ')[4] == "10" },
            )
        }

    private companion object {
        const val SEED = "12345"
    }
}
