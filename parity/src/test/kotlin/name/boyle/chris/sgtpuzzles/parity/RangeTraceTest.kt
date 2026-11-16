package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Level: strict. Game: range.
 *
 * Cold traces are compared with the oracle and with the frozen goldens.
 * The warm path, the cursor cache, the error marks, the four-point
 * outline, the sized traces, and the letterboxed view are compared one
 * by one. `range.c` has no blitter, so no trace may name one.
 */
class RangeTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val paramsList = oracle.presets("range").map { it.params }.distinct()
            assertEquals(listOf("9x6", "12x8", "13x9", "16x11"), paramsList)

            for (params in paramsList) {
                val midend = Midend.newGame(Range, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("range", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/range/${GenTraces.fileName(params)}.trace")
                assertEquals("frozen trace for '$params'", golden.readText(), actual)

                assertNoBlitter("'$params'", actual)
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
                assertFalse(
                    "no status for '$params'",
                    actual.lines().any { it.startsWith("status ") },
                )
                assertTrue(
                    "the first draw clears the window",
                    actual.lines().contains(
                        "rect 0 0 ${midend.size.width} ${midend.size.height} 0",
                    ),
                )
            }

            // The frozen palette is the four-role palette.
            val palette = actual9x6Palette()
            assertEquals("800,800,800 0,0,0 1000,0,0 633,633,633", palette)
        }

    @Test
    fun `every cell is drawn with a four-point outline and an update`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("range", "9x6", SEED).state
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            val sink = RecordingSink()
            midend.recordNewGameTrace(sink)

            val lines = sink.ops
            // Cell (0,2): the empty cell at x=80, y=16 in game coordinates.
            assertTrue(
                "the corner point list",
                lines.contains("poly -1 1 4 80 16 80 48 112 48 112 16"),
            )
            assertTrue("the fill", lines.contains("rect 81 17 31 31 0"))
            assertTrue("the update", lines.contains("update 80 16 33 33"))

            // Cell (0,0) is the clue 7: text at the cell centre.
            assertTrue(
                "the clue text",
                lines.contains("text 32 32 1 19 257 1 7"),
            )
            assertTrue(
                "the grid outline",
                lines.contains("poly -1 1 4 16 16 16 48 48 48 48 16"),
            )
        }

    @Test
    fun `a cursor trace reveals the ring on the whole board`() = OracleClient().use { oracle ->
        val save = oracle.newGame("range", "9x6", SEED).state
        val expected = TraceNormalizer.normalize(
            oracle.trace(save, sequence = listOf(Buttons.CURSOR_DOWN)),
            upstream,
        )
        val midend = Midend.fromSave(Range, SgtpSave.parse(save))
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_DOWN, TILE),
        )
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("cursor trace", expected, actual)
        // The cursor cell (0,0) is a clue, so its fill is the lowlight.
        assertTrue("the cursor cell is lowlight", actual.lines().contains("rect 17 17 31 31 3"))
        assertNoBlitter("cursor trace", actual)
    }

    @Test
    fun `a moved board draws the new state`() = OracleClient().use { oracle ->
        val save = oracle.newGame("range", "9x6", SEED).state
        val view = 480
        val viewHeight = 336
        val click = OracleClient.Point(120, 24, Buttons.LEFT_BUTTON)
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                listOf(click),
                width = view,
                height = viewHeight,
                recordTrace = true,
            ).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Range, SgtpSave.parse(save))
        val outcome = midend.processKey(click.x, click.y, click.button, 48)
        assertEquals(name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect, outcome)
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 48), upstream)
        assertLines("moved board", expected, actual)
        // The click painted (0,2) black.
        assertTrue("the black fill", actual.lines().contains("rect 121 25 47 47 1"))
        assertNoBlitter("moved board", actual)
    }

    @Test
    fun `the error marks match the oracle`() = OracleClient().use { oracle ->
        // A pair of adjacent black cells: both are marked.
        val pair = historySave("9x6", DESC, listOf("MOVE" to "B,0,2B,0,3"))
        val pairTrace = traceOf(oracle, pair)
        assertTrue("an adjacent black pair is marked", pairTrace.contains(" 2\n"))
        assertTrue(
            "the black pair uses the error fill",
            pairTrace.lines().contains("rect 81 17 31 31 2"),
        )

        // A clue whose runs became too short: black cells cut its two
        // runs, and the clue text turns red.
        val tooSmall = historySave("9x6", DESC, listOf("MOVE" to "B,0,3B,2,0"))
        val smallTrace = traceOf(oracle, tooSmall)
        assertTrue(
            "the clue 7 is red",
            smallTrace.lines().contains("text 32 32 1 19 257 2 \"7\""),
        )

        // A clue whose white-only count became too big: dots only.
        val tooBig = historySave(
            "9x6",
            DESC,
            listOf("MOVE" to "W,0,6W,0,8W,1,7W,2,7W,3,7W,4,7"),
        )
        val bigTrace = traceOf(oracle, tooBig)
        assertTrue(
            "the clue 4 is red",
            bigTrace.lines().contains("text 256 32 1 19 257 2 \"4\""),
        )

        // A split board: two diagonal black cells isolate the corner
        // (0,0) from the rest of the 3x3 board.
        val split = historySave("3x3", "i", listOf("MOVE" to "B,0,1B,1,0"))
        val splitTrace = traceOf(oracle, split)
        // An error-marked empty cell keeps the background fill and gets
        // the red outline only (`range.c:1783-1791`).
        assertTrue(
            "the split component is outlined in red",
            splitTrace.lines().contains("poly -1 2 4 17 17 17 47 47 47 47 17"),
        )

        // Every error trace is identical to the oracle's.
        for (save in listOf(pair, tooSmall, tooBig, split)) {
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            assertLines("error trace", expected, actual)
            assertNoBlitter("error trace", actual)
        }
    }

    @Test
    fun `the warm path emits nothing after a click`() = OracleClient().use { oracle ->
        val save = oracle.newGame("range", "9x6", SEED).state
        for (point in listOf(
            OracleClient.Point(120, 24, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
        )) {
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, listOf(point), width = 480, height = 336).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, listOf(point), tileSize = 48),
                upstream,
            )
            assertLines("warm frame at (${point.x},${point.y})", expected, actual)
            assertEquals(
                "a warm frame is empty at (${point.x},${point.y})",
                listOf("start_draw", "end_draw"),
                actual.lines().filter { it == "start_draw" || it == "end_draw" },
            )
            assertNoBlitter("warm frame", actual)
        }
    }

    @Test
    fun `the cursor on a black cell redraws the cached cell`() {
        val save = historySave("9x6", DESC, listOf("MOVE" to "B,0,2"))
        val midend = Midend.fromSave(Range, SgtpSave.parse(save))
        val sink = RecordingSink()
        midend.recordNewGameTrace(sink)

        // Reveal the ring and walk from the clue (0,0) to the black
        // (0,2). The ring is hidden throughout the walks; only the last
        // two redraws move it.
        midend.processKey(0, 0, Buttons.CURSOR_DOWN, TILE)
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE)
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE)
        sink.ops.clear()
        midend.recordRedraw(sink)

        // The word changed because the cursor flag joined the black
        // value, so the cell is drawn again even though its fill colour
        // is still COL_BLACK.
        assertEquals(
            listOf(
                "start_draw",
                "poly -1 1 4 80 16 80 48 112 48 112 16",
                "rect 81 17 31 31 1",
                "update 80 16 33 33",
                "end_draw",
            ),
            sink.ops,
        )
        assertFalse(
            "a black cursor is not lowlight",
            sink.ops.contains("rect 81 17 31 31 3"),
        )
    }

    @Test
    fun `sized traces match the oracle at every tile size`() = OracleClient().use { oracle ->
        val start = oracle.newGame("range", "9x6", SEED)
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Range.computeSize(Range.decodeParams("9x6"), tile)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    start.state,
                    listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.newGame(Range, "9x6", SEED)
            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.Unused,
                midend.processKey(0, 0, Buttons.LEFT_BUTTON, tile),
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream,
            )
            assertLines("tile $tile", expected, actual)
            assertEquals(
                "tile $tile size",
                board,
                Range.computeSize(Range.decodeParams("9x6"), tile),
            )
            assertNoBlitter("tile $tile", actual)
        }
    }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("range", "9x6", SEED)
        val midend = Midend.newGame(Range, "9x6", SEED)
        val geometry = midend.geometryFor(411, 702)
        val tile = geometry.tileSize
        assertEquals("the letterbox tile", 41, tile)

        // A real click on cell (0,2) through the view origin.
        val viewX = geometry.originX + 3 * tile
        val viewY = geometry.originY + tile
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
                411,
                702,
                recordTrace = true,
            ).trace!!,
            upstream,
        )
        val clickMidend = Midend.fromSave(Range, SgtpSave.parse(start.state))
        val outcome = clickMidend.processKey(
            geometry.toGameX(viewX),
            geometry.toGameY(viewY),
            Buttons.LEFT_BUTTON,
            tile,
        )
        assertEquals(name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect, outcome)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(clickMidend, tile),
            upstream,
        )
        assertLines("letterbox trace", expected, actual)
        assertNoBlitter("letterbox trace", actual)
    }

    @Test
    fun `the lowlight is derived from the shell background`() {
        val default = Range.colours()
        assertEquals("the default background", 0.8f, default[0])
        assertNear("the default lowlight", 0.6333f, default[3 * 3])

        // A mid-grey background at 0.5 gives a lowlight at one third.
        val background = floatArrayOf(0.5f, 0.5f, 0.5f)
        val colours = Range.colours(background)
        assertNear("the background role", 0.5f, colours[0])
        assertNear("the grid role", 0.0f, colours[3])
        assertNear("the error red", 1.0f, colours[6])
        assertNear("the derived lowlight", 1.0f / 3.0f, colours[9])
        assertNear("the derived lowlight green", 1.0f / 3.0f, colours[10])
        assertNear("the derived lowlight blue", 1.0f / 3.0f, colours[11])
    }

    private fun traceOf(oracle: OracleClient, save: String): String =
        TraceNormalizer.normalize(oracle.trace(save), upstream)

    private fun actual9x6Palette(): String {
        val midend = Midend.newGame(Range, "9x6", SEED)
        val trace = KotlinTraceSink.trace(midend)
        val palette = (trace.field("palette") as JsonValue.Arr).items
        return palette.joinToString(" ") { entry ->
            (entry as JsonValue.Arr).items.joinToString(",") { (it as JsonValue.Num).value.toString() }
        }
    }

    private fun assertNoBlitter(what: String, trace: String) {
        assertFalse(
            "$what names a blitter",
            trace.lines().any {
                it.startsWith("blitter_new ") || it.startsWith("blitter_free ") ||
                    it.startsWith("blitter_save ") || it.startsWith("blitter_load ")
            },
        )
    }

    private fun assertNear(what: String, expected: Float, actual: Float) {
        assertTrue("$what: $actual is not $expected", abs(expected - actual) <= 0.001f)
    }

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

    private fun historySave(params: String, desc: String, records: List<Pair<String, String>>): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Range.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", (1 + records.size).toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    /** Records the drawing operations as compact text at the preferred tile. */
    private class RecordingSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        val ops = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state"
        }

        override fun startDraw() {
            ops += "start_draw"
        }

        override fun endDraw() {
            ops += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect $x $y $width $height $colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line $x1 $y1 $x2 $y2 $colour"
        }

        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle $cx $cy $radius $fillColour $outlineColour"
        }
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly $fillColour $outlineColour ${points.size / 2} " +
                points.joinToString(" ")
        }

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {
            ops += "text $x $y $font $size $align $colour $text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip $x $y $width $height"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update $x $y $width $height"
        }

        override fun status(text: String) {
            ops += "status $text"
        }

        override fun blitterNew(width: Int, height: Int): Int = 1

        override fun blitterFree(id: Int) {}

        override fun blitterSave(id: Int, x: Int, y: Int) {}

        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val DESC = "7_5e4d5g8l7g9d11e8_13"
    }
}
