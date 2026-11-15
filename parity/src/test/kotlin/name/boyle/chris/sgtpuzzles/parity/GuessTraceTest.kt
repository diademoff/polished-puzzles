package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: guess.
 *
 * Cold traces are compared with the oracle and with the frozen goldens.
 * A cold trace after a drag or a release is a fresh drawstate with the
 * frontend state already set, and a solved trace shows the reveal.
 *
 * The warm path has no oracle source in the ordinary `trace` call, so
 * `traceWarm` records one redraw on a drawstate that already drew the
 * board and handled the pointer sequence. That is the only path that
 * exercises the per-row caches, the current hint row, the marker, and
 * the blitter restore.
 */
class GuessTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val paramsList = oracle.presets("guess").map { it.params }.distinct().sorted()

            for (params in paramsList) {
                val midend = Midend.newGame(Guess, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("guess", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/guess/${GenTraces.fileName(params)}.trace",
                )
                assertEquals("frozen trace for '$params'", golden.readText(), actual)

                assertEquals(
                    "one blitter for '$params'",
                    1,
                    actual.lines().count { it.startsWith("blitter_new ") },
                )
                assertEquals(
                    "no blitter free for '$params'",
                    0,
                    actual.lines().count { it.startsWith("blitter_free ") },
                )
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
                    actual.lines().contains("rect 0 0 ${boardWidth(midend)} ${boardHeight(midend)} 0"),
                )
            }
        }

    @Test
    fun `a drag in progress matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("guess", "", "0").state
        val points = listOf(
            OracleClient.Point(colourX, colourY(3), Buttons.LEFT_BUTTON),
            OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_DRAG),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                points,
                width = VIEW_W,
                height = VIEW_H,
                recordTrace = true,
            ).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, TILE)
        }
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("drag frame", expected, actual)

        // The sprite is saved under the pointer with the one-pixel
        // margin and drawn with no background rectangle.
        assertTrue(
            "the sprite is saved at the pointer",
            actual.lines().contains("blitter_save 1 79 16"),
        )
        assertTrue(
            "the sprite draws the dragged colour",
            actual.lines().contains("circle 95 32 15 8 1"),
        )
        assertEquals(
            "one blitter in a cold frame",
            1,
            actual.lines().count { it.startsWith("blitter_new ") },
        )
    }

    @Test
    fun `a released drag matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("guess", "", "0").state
        val points = listOf(
            OracleClient.Point(colourX, colourY(3), Buttons.LEFT_BUTTON),
            OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_DRAG),
            OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                points,
                width = VIEW_W,
                height = VIEW_H,
                recordTrace = true,
            ).trace!!,
            upstream,
        )
        val result = oracle.pointsSized(
            save,
            points,
            width = VIEW_W,
            height = VIEW_H,
        )
        assertEquals("3,0,0,0", SgtpSave.parse(result.state.state).value("UI"))

        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, TILE)
        }
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("released frame", expected, actual)
        assertFalse(
            "a released drag has no sprite",
            actual.lines().any { it.startsWith("blitter_save ") },
        )
        // The placed colour is drawn without the cursor.
        assertTrue(
            "the released peg is red",
            actual.lines().contains("circle 95 32 15 8 1"),
        )
    }

    @Test
    fun `a solved game reveals the solution on both sides`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val won = historySave(
                "c6p4g10Bm",
                "2bebff56",
                listOf("MOVE" to "G3,3,4,1"),
                2,
            )
            val revealed = historySave(
                "c6p4g10Bm",
                "2bebff56",
                listOf("SOLVE" to "S"),
                2,
            )
            for (text in listOf(won, revealed)) {
                val expected = TraceNormalizer.normalize(oracle.trace(text), upstream)
                val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend),
                    upstream,
                )
                assertLines("solved frame", expected, actual)
                // The solution row is 3, 3, 4, 1. Its pegs use the
                // colour enum with the empty slot at five, so red is 8.
                assertTrue(
                    "the solution is drawn",
                    actual.lines().contains("circle 165 387 15 9 1"),
                )
                assertFalse(
                    "a solved game has no current marker",
                    actual.lines().contains("rect 71 368 2 32 4"),
                )
            }
        }

    @Test
    fun `a warm drag frame matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("guess", "", "0").state
        val points = listOf(
            OracleClient.Point(colourX, colourY(3), Buttons.LEFT_BUTTON),
            OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_DRAG),
        )
        val expected = normalizedWarm(
            oracle.traceWarm(save, points, width = VIEW_W, height = VIEW_H).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, points), upstream)
        assertLines("warm drag", expected, actual)

        assertFalse(
            "a warm frame allocates no blitter",
            actual.lines().any { it.startsWith("blitter_new ") },
        )
        assertTrue(
            "the hint row is redrawn every frame",
            actual.lines().any { it == "rect 220 17 31 31 0" },
        )
        assertTrue(
            "the current marker is redrawn every frame",
            actual.lines().contains("rect 71 17 2 32 4"),
        )
    }

    @Test
    fun `a warm release matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("guess", "", "0").state
        val points = listOf(
            OracleClient.Point(colourX, colourY(3), Buttons.LEFT_BUTTON),
            OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_DRAG),
            OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_RELEASE),
        )
        val expected = normalizedWarm(
            oracle.traceWarm(save, points, width = VIEW_W, height = VIEW_H).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, points), upstream)
        assertLines("warm release", expected, actual)
        assertFalse(
            "a warm release allocates no blitter",
            actual.lines().any { it.startsWith("blitter_new ") },
        )
        assertFalse(
            "a released sprite is not saved",
            actual.lines().any { it.startsWith("blitter_save ") },
        )
    }

    @Test
    fun `a warm frame after a move matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("guess", "", "0").state
        // Fill 1,1,1,1 by dragging the first colour to each peg, then
        // submit with a click into the hint area.
        val points = ArrayList<OracleClient.Point>()
        for (peg in 0..3) {
            points += OracleClient.Point(colourX, colourY(1), Buttons.LEFT_BUTTON)
            points += OracleClient.Point(pegX(peg), rowY(0), Buttons.LEFT_DRAG)
            points += OracleClient.Point(pegX(peg), rowY(0), Buttons.LEFT_RELEASE)
        }
        points += OracleClient.Point(hintX, rowY(0), Buttons.LEFT_BUTTON)
        points += OracleClient.Point(hintX, rowY(0), Buttons.LEFT_RELEASE)

        val expected = normalizedWarm(
            oracle.traceWarm(save, points, width = VIEW_W, height = VIEW_H).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, points), upstream)
        assertLines("warm after a move", expected, actual)
        assertEquals(
            "the move is in the history",
            oracle.keys(save, listOf(49, 49, 49, 49, 525)).state,
            midend.serialise().serialise(),
        )
        assertEquals(2, midend.stateCount)
    }

    @Test
    fun `a markable draft flashes the empty hint slots`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("guess", "", "0").state
            // Drag the first colour into all four pegs.
            val points = ArrayList<OracleClient.Point>()
            for (peg in 0..3) {
                points += OracleClient.Point(colourX, colourY(1), Buttons.LEFT_BUTTON)
                points += OracleClient.Point(pegX(peg), rowY(0), Buttons.LEFT_DRAG)
                points += OracleClient.Point(pegX(peg), rowY(0), Buttons.LEFT_RELEASE)
            }
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = VIEW_W,
                    height = VIEW_H,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            assertLines("markable draft frame", expected, actual)

            // An empty hint slot uses COL_FLASH (3) while the draft can
            // be submitted, not COL_EMPTY (5).
            assertTrue(
                "the hint slots flash",
                actual.lines().contains("circle 228 25 5 3 3"),
            )
            assertTrue(
                "the current marker is drawn",
                actual.lines().contains("rect 71 17 2 32 4"),
            )
        }

    @Test
    fun `a labels trace matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("guess", "", "0").state
        val expected = TraceNormalizer.normalize(
            oracle.trace(save, prefs = "show-labels=true\n"),
            upstream,
        )
        val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
        (midend.ui as Guess.Ui).showLabels = true
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("labels trace", expected, actual)

        val texts = actual.lines().filter { it.startsWith("text ") }
        // The six colour-bar pegs get a number.
        assertEquals(6, texts.size)
        assertTrue("the first colour is labelled 1", texts.any { it.endsWith("\"1\"") })
        assertTrue(
            "the first colour draws its number",
            actual.lines().contains("text 31 121 1 15 257 1 \"1\""),
        )
    }

    @Test
    fun `a warm redraw after a note-free key draws only the cached changes`() {
        val midend = Midend.newGame(Guess, "", "0")
        val sink = OpSink()
        midend.recordNewGameTrace(sink)
        val firstCount = sink.ops.size
        val drawState = midend.drawState

        // A cursor key changes only the cursor.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE),
        )
        assertSame("a cursor key keeps the drawstate", drawState, midend.drawState)
        sink.ops.clear()
        midend.recordRedraw(sink)

        assertEquals(
            "the warm cursor frame",
            listOf(
                "start_draw",
                // The colour-bar cursor moves to the first colour.
                "rect 15 105 34 34 0",
                "circle 31 121 15 6 1",
                "update 15 105 34 34",
                "circle 31 121 16 -1 2",
                "update 15 105 34 34",
                // The current-row cursor is on the second peg. The hold
                // strip below it is updated even though it has no hold.
                "rect 114 16 34 34 0",
                "circle 130 32 15 5 5",
                "update 114 16 34 34",
                "circle 130 32 16 -1 2",
                "update 114 16 34 34",
                "update 115 48 32 2",
                // The current hint row is always forced.
                "rect 220 17 31 31 0",
                "circle 228 25 5 5 5",
                "circle 242 25 5 5 5",
                "circle 228 39 5 5 5",
                "circle 242 39 5 5 5",
                "update 220 17 31 31",
                // The current-row marker.
                "rect 71 17 2 32 4",
                "update 71 17 2 32",
                "end_draw",
            ),
            sink.ops,
        )
        assertTrue("the warm frame is shorter", sink.ops.size < firstCount)
        assertTrue(
            "the warm frame allocates nothing",
            sink.ops.none { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
        )
    }

    @Test
    fun `a warm frame after the label key redraws the cached pegs`() {
        val midend = Midend.newGame(Guess, "", "0")
        val ui = midend.ui as Guess.Ui
        val sink = OpSink()
        midend.recordNewGameTrace(sink)
        assertTrue("the cold frame has no label", sink.ops.none { it.startsWith("text ") })
        val drawState = midend.drawState

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'l'.code, TILE))
        assertTrue(ui.showLabels)
        assertSame("the label key keeps the drawstate", drawState, midend.drawState)
        sink.ops.clear()
        midend.recordRedraw(sink)

        // `show_labels` adds the label flag to every cache entry, so the
        // warm frame redraws the six colour-bar pegs with their numbers
        // and all ten empty guess rows. A cache key without the flag
        // would leave the guess rows unchanged.
        val texts = sink.ops.filter { it.startsWith("text ") }
        assertEquals(
            (1..6).map { "text 31 ${121 + (it - 1) * 35} 1 15 257 1 $it" },
            texts,
        )
        for (colour in 1..6) {
            assertTrue(
                "colour $colour is redrawn",
                sink.ops.contains(
                    "circle 31 ${121 + (colour - 1) * 35} 15 ${5 + colour} 1",
                ),
            )
        }
        // Every guess peg is redrawn empty, with no number.
        assertEquals(
            40,
            sink.ops.count { it.startsWith("circle") && it.contains(" 15 5 5") },
        )
        assertTrue(
            "the warm frame allocates nothing",
            sink.ops.none { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
        )
    }

    private fun boardWidth(midend: Midend): Int = midend.size.width

    private fun boardHeight(midend: Midend): Int = midend.size.height

    private fun normalizedWarm(trace: JsonValue.Obj, upstream: String): String {
        val ops = (trace.field("ops") as JsonValue.Arr).items.toMutableList()
        val ids = ops.asSequence()
            .filterIsInstance<JsonValue.Obj>()
            .mapNotNull { (it.fields["id"] as? JsonValue.Num)?.value }
            .distinct()
            .toList()
        ops.addAll(
            0,
            ids.map { id ->
                JsonValue.Obj(
                    linkedMapOf(
                        "op" to JsonValue.Str("blitter_new"),
                        "id" to JsonValue.Num(id),
                        "w" to JsonValue.Num(1),
                        "h" to JsonValue.Num(1),
                    ),
                )
            },
        )
        val patched = JsonValue.Obj(
            LinkedHashMap(trace.fields).apply { put("ops", JsonValue.Arr(ops)) },
        )
        return TraceNormalizer.normalize(patched, upstream)
            .lines()
            .filterNot { it.startsWith("blitter_new ") }
            .joinToString("\n", postfix = "\n")
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

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Guess.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("UI", "0,0,0,0"),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private val colourX = 32

    private fun colourY(colour: Int): Int = 106 + (colour - 1) * 35 + 16

    private fun pegX(peg: Int): Int = 80 + peg * 35 + 16

    private fun rowY(row: Int): Int = 33 + row * 35

    /** Records the drawing operations as compact text. */
    private class OpSink : DrawSink {
        val ops = ArrayList<String>()
        private var nextBlitterId = 1

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

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle $cx $cy $radius $fillColour $outlineColour"
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly ${points.joinToString(" ")} $fillColour $outlineColour"
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

        override fun blitterNew(width: Int, height: Int): Int {
            val id = nextBlitterId++
            ops += "blitter_new $id $width $height"
            return id
        }

        override fun blitterFree(id: Int) {
            ops += "blitter_free $id"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save $id $x $y"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load $id $x $y"
        }
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val VIEW_W = 263
        const val VIEW_H = 420
        const val hintX = 230
    }
}
