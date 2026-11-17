package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Singles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: singles.
 *
 * The ten frozen first frames, four tile sizes and a letterbox
 * viewport, the cursor ring, the error colours, the two-digit text
 * size, the warm path, and the engine-only dirty cache after an undo.
 *
 * Singles has no blitter, no clip, no thick primitive, no flash, and no
 * status bar, so every frame must be free of those operations.
 */
class SinglesTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun center(x: Int, y: Int, tile: Int): Pair<Int, Int> {
        val border = tile / 2
        return Pair(
            border + x * tile + tile / 2,
            border + y * tile + tile / 2,
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
        assertTrue("$what has no thick line", trace.lines().none { it.startsWith("thick_line") })
        assertTrue("$what has no thick circle", trace.lines().none { it.startsWith("thick_circle") })
        assertTrue("$what has no thick poly", trace.lines().none { it.startsWith("thick_poly") })
        assertTrue("$what has no status", trace.lines().none { it.startsWith("status ") })
    }

    @Test(timeout = 600_000)
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("singles").map { it.params }) {
                val midend = Midend.newGame(Singles, params, SEED)
                val actual = normalize(KotlinTraceSink.trace(midend))
                val expected = normalize(oracle.traceNew("singles", params, SEED))
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/singles/$params.trace")
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

                val board = Singles.computeSize(Singles.decodeParams(params), 32).width
                assertEquals(
                    "one whole-window clear for '$params'",
                    1,
                    actual.lines().count { it == "rect 0 0 $board $board 0" },
                )
                assertEquals(
                    "two whole-window updates for '$params'",
                    2,
                    actual.lines().count { it == "update 0 0 $board $board" },
                )
                assertTrue(
                    "'$params' cell order is column-major",
                    actual.lines().indexOfFirst { it.startsWith("rect 16 16 ") } <
                        actual.lines().indexOfFirst { it.startsWith("rect 48 16 ") },
                )
            }

            // The palette is the frozen one.
            val five = normalize(KotlinTraceSink.trace(Midend.newGame(Singles, "5x5de", SEED)))
            assertTrue(
                "the palette",
                five.contains(
                    "palette 800,800,800 0,0,0 633,633,633 0,0,0 1000,1000,1000 " +
                        "400,400,400 633,633,633 200,800,0 1000,0,0",
                ),
            )
        }

    @Test(timeout = 600_000)
    fun `the sized loaded-save traces match the oracle`() = OracleClient().use { oracle ->
        for (params in oracle.presets("singles").map { it.params }) {
            val save = oracle.newGame("singles", params, SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Singles.computeSize(Singles.decodeParams(params), tile)
                val expected = oracle.pointsSized(
                    save,
                    emptyList(),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = Midend.newGame(Singles, params, SEED)
                val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
                assertLines("$params tile $tile", normalize(expected.trace!!), actual)
                assertTrue(
                    "$params tile $tile has the size",
                    actual.lines().contains("size ${board.width} ${board.height}"),
                )
                assertNoForbidden("$params tile $tile", actual)
            }
        }
    }

    @Test(timeout = 600_000)
    fun `the letterbox view traces at the found tile`() = OracleClient().use { oracle ->
        for (params in oracle.presets("singles").map { it.params }) {
            val save = oracle.newGame("singles", params, SEED).state
            val midend = Midend.newGame(Singles, params, SEED)
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
        // The odd tile truncation: 200x200 finds 197 at tile 33.
        assertEquals(197, Singles.computeSize(Singles.decodeParams("5x5de"), 33).width)
        val layout = Midend.newGame(Singles, "5x5de", SEED).geometryFor(200, 200)
        assertEquals(33, layout.tileSize)
        assertEquals(197, layout.width)
        assertEquals(1, layout.originX)
    }

    @Test(timeout = 600_000)
    fun `a cursor walk draws the eight corner lines`() = OracleClient().use { oracle ->
        val save = oracle.newGame("singles", "5x5de", SEED).state
        val sequence = listOf(Buttons.CURSOR_UP)
        val expected = oracle.trace(save, sequence)
        val midend = Midend.newGame(Singles, "5x5de", SEED)
        for (key in sequence) midend.processKey(0, 0, key, 32)
        val actual = normalize(KotlinTraceSink.stateTrace(midend))
        assertLines("cursor", normalize(expected), actual)
        assertEquals(
            "eight cursor lines",
            8,
            actual.lines().count { it.startsWith("line ") && it.endsWith(" 7") },
        )
    }

    @Test(timeout = 600_000)
    fun `the error marks use COL_ERROR and the black number uses COL_BLACKNUM`() =
        OracleClient().use { oracle ->
            // `1121`: two circled cells with the same number in one
            // column are both marked.
            val error = handSave("1121", params = "2x2de")
            val errorKeys = listOf(
                OracleClient.Point(32, 32, Buttons.RIGHT_BUTTON),
                OracleClient.Point(32, 32, Buttons.RIGHT_RELEASE),
                OracleClient.Point(64, 32, Buttons.RIGHT_BUTTON),
                OracleClient.Point(64, 32, Buttons.RIGHT_RELEASE),
            )
            val expected = oracle.pointsSized(error, errorKeys, 96, 96, recordTrace = true)
            val midend = Midend.fromSave(Singles, name.boyle.chris.sgtpuzzles.engine.SgtpSave.parse(error))
            apply(midend, errorKeys, 32)
            val actual = normalize(KotlinTraceSink.stateTrace(midend, 32))
            assertLines("error", normalize(expected.trace!!), actual)
            assertTrue(
                "a red white-cell number",
                actual.lines().any { it.startsWith("text ") && it.endsWith(" 8 \"1\"") },
            )

            // The `show-black-nums` preference draws the black number
            // in COL_BLACKNUM. C applies it before the first frame; the
            // port exposes the field on the UI.
            val black = handSave(
                "2111",
                params = "2x2de",
                history = listOf("MOVE" to "B0,0"),
            )
            val withPref = oracle.trace(
                black,
                prefs = "show-black-nums=true",
            )
            val prefMidend = Midend.fromSave(
                Singles,
                name.boyle.chris.sgtpuzzles.engine.SgtpSave.parse(black),
            )
            (prefMidend.ui as Singles.Ui).showBlackNums = true
            val prefActual = normalize(KotlinTraceSink.stateTrace(prefMidend))
            assertLines("black numbers", normalize(withPref), prefActual)
            assertTrue(
                "the black number uses COL_BLACKNUM",
                prefActual.lines().any { it.startsWith("text ") && it.endsWith(" 5 \"2\"") },
            )

            val noPrefMidend = Midend.fromSave(
                Singles,
                name.boyle.chris.sgtpuzzles.engine.SgtpSave.parse(black),
            )
            val noPref = normalize(KotlinTraceSink.stateTrace(noPrefMidend))
            assertTrue(
                "the default has no black number",
                noPref.lines().none { it.startsWith("text ") && it.endsWith(" 5 \"2\"") },
            )
            assertNotEquals(noPref, prefActual)
            assertEquals(
                "the preference changes no state",
                noPrefMidend.serialise().serialise(),
                prefMidend.serialise().serialise(),
            )
        }

    @Test(timeout = 600_000)
    fun `the two-digit text size follows the C formula`() = OracleClient().use { oracle ->
        // 12x12 at tile 32 has CRAD 15, TEXTSZ 20, and 14 for two
        // digits.
        val save = oracle.newGame("singles", "12x12de", SEED).state
        val expected = oracle.pointsSized(save, emptyList(), 416, 416, recordTrace = true)
        val midend = Midend.newGame(Singles, "12x12de", SEED)
        val actual = normalize(KotlinTraceSink.stateTrace(midend, 32))
        assertLines("12x12", normalize(expected.trace!!), actual)
        assertTrue(
            "the two-digit size",
            actual.lines().any { it.startsWith("text ") && it.endsWith(" 14 257 3 \"10\"") },
        )
        assertTrue(
            "the one-digit size",
            actual.lines().any { it.startsWith("text ") && it.endsWith(" 20 257 3 \"1\"") },
        )
    }

    @Test(timeout = 600_000)
    fun `every warm frame after an input is empty`() = OracleClient().use { oracle ->
        val save = oracle.newGame("singles", "5x5de", SEED).state
        val board = Singles.computeSize(Singles.decodeParams("5x5de"), 48).width
        val cases = listOf(
            "click" to click(0, 0, 48),
            "right click" to click(0, 0, 48, Buttons.RIGHT_BUTTON),
            "second click" to click(0, 0, 48) + click(0, 0, 48),
            "cursor up" to listOf(OracleClient.Point(0, 0, Buttons.CURSOR_UP)),
            "cursor select" to listOf(OracleClient.Point(0, 0, Buttons.CURSOR_SELECT)),
        )
        for ((name, points) in cases) {
            val expected = oracle.traceWarm(save, points, board, board)
            val midend = Midend.newGame(Singles, "5x5de", SEED)
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

    @Test(timeout = 300_000)
    fun `the engine warm frame after an undo repaints only the changed cell`() {
        val midend = Midend.newGame(Singles, "5x5de", SEED)
        val sink = KotlinTraceSink()
        sink.recording = false
        midend.recordRedraw(sink)

        val points = click(0, 0, 32)
        apply(midend, points, 32)
        midend.recordRedraw(sink)
        assertTrue("the click added a state", midend.canUndo)

        assertTrue("undo works", midend.undo())
        sink.ops.clear()
        sink.recording = true
        midend.recordRedraw(sink)

        assertEquals("one cell update", 1, sink.ops.count { it.string("op") == "update" })
        assertEquals(
            "the changed cell",
            "update 16 16 32 32",
            traceLine(sink.ops.single { it.string("op") == "update" }),
        )
        assertEquals("one start_draw", 1, sink.ops.count { it.string("op") == "start_draw" })
        assertEquals("one end_draw", 1, sink.ops.count { it.string("op") == "end_draw" })
    }

    @Test(timeout = 300_000)
    fun `the preference is read by redraw and is not in the drawstate`() {
        val midend = Midend.newGame(Singles, "5x5de", SEED)
        val sink = KotlinTraceSink()
        sink.recording = false
        midend.recordRedraw(sink)

        // The first click marks black. A later outside click toggles
        // the preference, and the redraw of that click shows the black
        // number without any state change.
        apply(midend, click(0, 0, 32), 32)
        midend.recordRedraw(sink)
        val before = midend.serialise().serialise()

        val outside = listOf(
            OracleClient.Point(2, 700, Buttons.LEFT_BUTTON),
            OracleClient.Point(2, 700, Buttons.LEFT_RELEASE),
        )
        apply(midend, outside, 32)
        assertEquals("the preference changes no state", before, midend.serialise().serialise())
        assertTrue("the preference is set", (midend.ui as Singles.Ui).showBlackNums)

        sink.ops.clear()
        sink.recording = true
        midend.recordRedraw(sink)
        assertTrue(
            "the black number is drawn",
            sink.ops.any {
                it.string("op") == "text" && it.string("text") == "1" &&
                    it.number("c").toInt() == 5
            },
        )
        assertTrue(
            "only black cells are repainted",
            sink.ops.any { it.string("op") == "update" } &&
                sink.ops.all { it.string("op") != "update" || it.number("x").toInt() == 16 },
        )
    }

    private fun traceLine(op: JsonValue.Obj): String {
        val name = op.string("op")
        return when (name) {
            "update" -> "update ${op.number("x").toInt()} ${op.number("y").toInt()} " +
                "${op.number("w").toInt()} ${op.number("h").toInt()}"

            else -> error("not a simple line: $name")
        }
    }

    private fun handSave(
        desc: String,
        params: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection"
        records += "VERSION" to "1"
        records += "GAME" to "Singles"
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

    private companion object {
        const val SEED = "12345"
    }
}
