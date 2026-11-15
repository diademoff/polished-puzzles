package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: lightup.
 *
 * The palette, the frame polygon, the column-major tile order, the
 * per-cell cache, the clue text, the two error colours, the cursor ring,
 * the `show-lit-blobs` preference, sized draws, the letterbox, and the
 * warm path are compared with the oracle and the frozen traces.
 *
 * The preference is read while drawing the blob, not in the cached flag
 * word, so a preference change alone does not dirty a tile. The trace
 * with the preference on has one more `rect` than the trace with it off.
 */
class LightupTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        for (preset in Lightup.presets()) {
            val midend = Midend.newGame(Lightup, preset.params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("lightup", preset.params, SEED),
                upstream,
            )
            assertLines("trace for '${preset.params}'", expected, actual)

            val golden = File(
                testdata,
                "trace/lightup/${GenTraces.fileName(preset.params)}.trace",
            )
            assertEquals("frozen trace for '${preset.params}'", golden.readText(), actual)

            assertTrue("no status line", actual.lines().none { it.startsWith("status ") })
            assertTrue("no clip", actual.lines().none { it.startsWith("clip") })
            assertTrue("no blitter", actual.lines().none { it.startsWith("blitter") })

            // The frame is one four-point polygon, and the first draw has
            // exactly one clear and one whole-window update. The cell
            // outlines use the same operation, so match the frame's
            // coordinates.
            val cells = preset.params.substringBefore('b').split('x').map { it.toInt() }
            val border = 32 / 2
            val frame = "poly -1 1 4 " +
                "${border - 1} ${border - 1} ${border - 1} ${border + 32 * cells[1]} " +
                "${border + 32 * cells[0]} ${border + 32 * cells[1]} " +
                "${border + 32 * cells[0]} ${border - 1}"
            assertEquals(
                "one frame polygon",
                1,
                actual.lines().count { it == frame },
            )
            val boardW = 32 * cells[0] + 32
            val boardH = 32 * cells[1] + 32
            assertEquals(
                "the game's first-draw update and the midend's",
                2,
                actual.lines().count { it == "update 0 0 $boardW $boardH" },
            )
            assertEquals(
                "one whole-window clear",
                1,
                actual.lines().count { it == "rect 0 0 $boardW $boardH 0" },
            )
            assertEquals(
                "one start_draw",
                1,
                actual.lines().count { it == "start_draw" },
            )
            assertEquals(
                "one end_draw",
                1,
                actual.lines().count { it == "end_draw" },
            )

            // The tile order is column-major: the first four tiles share
            // x = 16 and increase in y.
            val tileY = actual.lines()
                .filter { it.startsWith("rect 16 ") }
                .map { it.split(' ')[2].toInt() }
            assertEquals("column-major order", listOf(16, 48, 80, 112), tileY.take(4))

            // The palette is the seven-role list of `lightup.c:2097-2122`.
            assertTrue(
                "palette",
                actual.lines().any {
                    it == "palette 800,800,800 533,533,533 0,0,0 1000,1000,1000 " +
                        "1000,1000,0 1000,250,250 400,400,400"
                },
            )
        }
    }

    @Test
    fun `the clue text and its error colour match the oracle`() = OracleClient().use { oracle ->
        // The default board's first clue is the `1` at (6,0). It draws
        // in COL_LIGHT at font 1, size 19, align 257.
        val save = saveText("7x7b20s3d0", DESC_7X7)
        val expected = traceOf(oracle, save)
        val actual = assertTrace("clue text", expected, Midend.fromSave(Lightup, SgtpSave.parse(save)))
        assertTrue(
            "clue text",
            actual.lines().any {
                it == "text 224 32 1 19 257 3 \"1\""
            },
        )

        // Lights at (5,0) and (6,1) overfill the `1` at (6,0), so the
        // clue draws in COL_ERROR.
        val wrong = saveText("7x7b20s3d0", DESC_7X7, listOf("L5,0", "L6,1"))
        val wrongExpected = traceOf(oracle, wrong)
        val wrongTrace = assertTrace(
            "wrong clue",
            wrongExpected,
            Midend.fromSave(Lightup, SgtpSave.parse(wrong)),
        )
        assertTrue(
            "red clue",
            wrongTrace.lines().any {
                it == "text 224 32 1 19 257 5 \"1\""
            },
        )
    }

    @Test
    fun `lights, overlaps, marks, and the cursor match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("7x7b20s3d0", DESC_7X7)
        // A light is a COL_LIGHT circle with a COL_BLACK outline.
        val light = saveText("7x7b20s3d0", DESC_7X7, listOf("L1,0"))
        val lightTrace = assertTrace(
            "light",
            traceOf(oracle, light),
            Midend.fromSave(Lightup, SgtpSave.parse(light)),
        )
        assertTrue("a light circle", lightTrace.lines().any { it.startsWith("circle ") && it.endsWith(" 3 2") })

        // Two adjacent lights light each other, so both circles draw in
        // COL_ERROR.
        val overlap = saveText("7x7b20s3d0", DESC_7X7, listOf("L1,0", "L1,1"))
        val overlapTrace = assertTrace(
            "overlap",
            traceOf(oracle, overlap),
            Midend.fromSave(Lightup, SgtpSave.parse(overlap)),
        )
        assertEquals(
            "two overlap circles",
            2,
            overlapTrace.lines().count { it.startsWith("circle ") && it.endsWith(" 5 2") },
        )

        // A mark in an unlit cell is the small black rect.
        val mark = saveText("7x7b20s3d0", DESC_7X7, listOf("I0,4"))
        val markTrace = assertTrace(
            "mark",
            traceOf(oracle, mark),
            Midend.fromSave(Lightup, SgtpSave.parse(mark)),
        )
        assertTrue("the blob", markTrace.lines().any { it == "rect 28 156 8 8 2" })

        // A visible cursor adds four COL_CURSOR outlines.
        val cursor = oracle.trace(save, listOf(Buttons.CURSOR_RIGHT))
        val midend = Midend.fromSave(Lightup, SgtpSave.parse(save))
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32)
        val cursorTrace = assertTrace(
            "cursor",
            cursor,
            KotlinTraceSink.stateTrace(midend, 32),
        )
        assertEquals(
            "one cursor outline",
            1,
            cursorTrace.lines().count { it.startsWith("poly -1 6 4 ") },
        )

        // A click hides the cursor and redraws the old ring cell.
        val click = oracle.pointsSized(
            save,
            listOf(OracleClient.Point(112, 48, Buttons.LEFT_BUTTON)),
            width = 256,
            height = 256,
            recordTrace = true,
        )
        val clickMidend = Midend.fromSave(Lightup, SgtpSave.parse(save))
        clickMidend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32)
        clickMidend.processKey(112, 48, Buttons.LEFT_BUTTON, 32)
        val clickTrace = assertTrace(
            "click hides the cursor",
            click.trace,
            KotlinTraceSink.stateTrace(clickMidend, 32),
        )
        assertEquals(
            "no cursor outline after the click",
            0,
            clickTrace.lines().count { it.startsWith("poly -1 6 4 ") },
        )
    }

    @Test
    fun `show-lit-blobs changes exactly one blob rect`() = OracleClient().use { oracle ->
        // A light at (1,0) lights (1,1), where an impossible mark is. The
        // blob at (1,1) is drawn only when the preference is true.
        val save = saveText("7x7b20s3d0", DESC_7X7, listOf("L1,0", "I1,1"))
        val on = oracle.trace(save, prefs = "show-lit-blobs=true\n")
        val off = oracle.trace(save, prefs = "show-lit-blobs=false\n")

        val onText = TraceNormalizer.normalize(on, upstream)
        val offText = TraceNormalizer.normalize(off, upstream)
        val onOps = operations(onText)
        val offOps = operations(offText)

        assertEquals("pref on operations", 152, onOps.size)
        assertEquals("pref off operations", 151, offOps.size)
        assertEquals(
            "the only difference is the blob",
            listOf("rect 60 60 8 8 2"),
            onOps.filter { it !in offOps.toSet() },
        )

        // The Kotlin UI is the only switch: the drawstate flags do not
        // include the preference.
        val onMidend = Midend.fromSave(Lightup, SgtpSave.parse(save))
        (onMidend.ui as Lightup.Ui).drawBlobsWhenLit = true
        val onActual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(onMidend, 32),
            upstream,
        )
        assertLines("pref on", onText, onActual)

        val offMidend = Midend.fromSave(Lightup, SgtpSave.parse(save))
        (offMidend.ui as Lightup.Ui).drawBlobsWhenLit = false
        val offActual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(offMidend, 32),
            upstream,
        )
        assertLines("pref off", offText, offActual)

        assertTrue(
            "the blob is the only new operation",
            onActual.lines().contains("rect 60 60 8 8 2"),
        )
        assertTrue(
            "the blob is absent when the preference is off",
            offActual.lines().none { it == "rect 60 60 8 8 2" },
        )
    }

    @Test
    fun `sized traces match the oracle at 48 63 82 100 and in the letterbox`() =
        OracleClient().use { oracle ->
            for (preset in Lightup.presets()) {
                val start = oracle.newGame("lightup", preset.params, SEED)
                for ((tile, view) in sizedViews(preset.params)) {
                    val expected = oracle.pointsSized(
                        start.state,
                        emptyList(),
                        width = view,
                        height = view,
                        recordTrace = true,
                    )
                    val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
                    assertEquals(
                        "tile for ${preset.params} at $view",
                        tile,
                        midend.geometryFor(view, view).tileSize,
                    )
                    val actual = TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, tile),
                        upstream,
                    )
                    assertLines(
                        "trace ${preset.params} at $view",
                        TraceNormalizer.normalize(requireNotNull(expected.trace), upstream),
                        actual,
                    )
                }
            }

            // The letterboxed 411x702 view: the origin is nonzero, and
            // the input and the drawing use the same tile size.
            for (preset in listOf("7x7b20s3d0", "10x10b20s2d1", "14x14b20s2d2")) {
                val start = oracle.newGame("lightup", preset, SEED)
                val expected = oracle.pointsSized(
                    start.state,
                    emptyList(),
                    width = 411,
                    height = 702,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
                val geometry = midend.geometryFor(411, 702)
                assertTrue("letterbox origin x for $preset", geometry.originX > 0)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                    upstream,
                )
                assertLines(
                    "letterbox $preset",
                    TraceNormalizer.normalize(requireNotNull(expected.trace), upstream),
                    actual,
                )
            }
        }

    @Test
    fun `the warm path is an empty frame for a click an arrow and a press release`() =
        OracleClient().use { oracle ->
            val save = saveText("7x7b20s3d0", DESC_7X7)
            // Exact-fit views for 7x7, plus the letterboxed 411x702 view.
            val views = listOf(
                32 to (256 to 256),
                48 to (384 to 384),
                63 to (503 to 503),
                82 to (656 to 656),
                100 to (800 to 800),
                51 to (411 to 702),
            )
            for ((tile, viewport) in views) {
                val (viewW, viewH) = viewport
                val origin = Midend.fromSave(Lightup, SgtpSave.parse(save))
                    .geometryFor(viewW, viewH)
                val gameX = tile + tile / 2
                val gameY = tile / 2
                for ((label, button) in listOf(
                    "click" to Buttons.LEFT_BUTTON,
                    "arrow" to Buttons.CURSOR_RIGHT,
                    "press and release" to Buttons.LEFT_BUTTON,
                )) {
                    val viewPoint = OracleClient.Point(
                        origin.originX + gameX,
                        origin.originY + gameY,
                        button,
                    )
                    val gamePoint = OracleClient.Point(gameX, gameY, button)
                    val points = if (label == "press and release") {
                        listOf(
                            viewPoint,
                            OracleClient.Point(viewPoint.x, viewPoint.y, Buttons.LEFT_RELEASE),
                        )
                    } else {
                        listOf(viewPoint)
                    }
                    val gamePoints = if (label == "press and release") {
                        listOf(
                            gamePoint,
                            OracleClient.Point(gameX, gameY, Buttons.LEFT_RELEASE),
                        )
                    } else {
                        listOf(gamePoint)
                    }
                    val expected = oracle.traceWarm(
                        save,
                        points,
                        width = viewW,
                        height = viewH,
                    )
                    val midend = Midend.fromSave(Lightup, SgtpSave.parse(save))
                    val actual = KotlinTraceSink.warmTrace(midend, gamePoints, tile)
                    assertEquals(
                        "$label warm trace at tile $tile",
                        expected.trace,
                        actual,
                    )
                    assertEquals(
                        "$label warm frame is empty at tile $tile",
                        listOf("start_draw", "end_draw"),
                        (actual.field("ops") as JsonValue.Arr).items.map {
                            ((it as JsonValue.Obj).fields["op"] as JsonValue.Str).value
                        },
                    )
                }
            }
        }

    /** The viewport that gives each exact tile: `ts * cells + 2 * (ts / 2)`. */
    private fun sizedViews(params: String): List<Pair<Int, Int>> {
        val cells = params.substringBefore('x').toInt()
        return listOf(48, 63, 82, 100).map { tile -> tile to (tile * cells + 2 * (tile / 2)) }
    }

    /** The operation lines, after the trace header and the palette. */
    private fun operations(trace: String): List<String> =
        trace.lines()
            .dropWhile { !it.startsWith("palette ") }
            .drop(1)
            .filter { it.isNotEmpty() }

    private fun traceOf(oracle: OracleClient, save: String): JsonValue.Obj = oracle.trace(save)

    private fun assertTrace(
        what: String,
        expected: JsonValue.Obj?,
        midend: Midend,
        tileSize: Int = Lightup.preferredTileSize,
    ): String {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        return assertTrace(what, expected, KotlinTraceSink.stateTrace(midend, tileSize))
    }

    private fun assertTrace(
        what: String,
        expected: JsonValue.Obj?,
        actual: JsonValue.Obj,
    ): String {
        val expectedText = TraceNormalizer.normalize(requireNotNull(expected), upstream)
        val actualText = TraceNormalizer.normalize(actual, upstream)
        assertLines(what, expectedText, actualText)
        return actualText
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

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Lightup.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_7X7 = "Be1j4e3aBeBj1e1"
    }
}
