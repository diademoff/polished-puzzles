package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: blackbox.
 *
 * Cold traces compare the frame, the palette, the dirty-cell cache,
 * the always-redrawn reveal button, and one status line. The warm path
 * uses `traceWarm`, which keeps the drawstate and the frontend state
 * from the input frames, so it exercises the cache and the
 * `drawing.c` status de-duplication.
 */
class BlackBoxTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = upstream()
            val paramsList = oracle.presets("blackbox").map { it.params }.distinct().sorted()

            for (params in paramsList) {
                val midend = Midend.newGame(BlackBox, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("blackbox", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/blackbox/${GenTraces.fileName(params)}.trace",
                )
                assertEquals("frozen trace for '$params'", golden.readText(), actual)

                assertEquals(
                    "no blitter for '$params'",
                    0,
                    actual.lines().count { it.startsWith("blitter_") },
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
                assertTrue(
                    "the first draw clears the window",
                    actual.lines().contains(
                        "rect 0 0 ${midend.size.width} ${midend.size.height} 0",
                    ),
                )
                assertTrue(
                    "one status line",
                    actual.lines().count { it.startsWith("status ") } == 1,
                )
            }
        }

    @Test
    fun `a mouse down flashes the hit and the exit`() = OracleClient().use { oracle ->
        val save = oracle.newGame("blackbox", "w5h5m3M3", SEED).state
        // F0 is a hit at the top row; F5 is an exit on the right side.
        for (event in listOf(
            listOf(OracleClient.Point(64, 32, Buttons.LEFT_BUTTON)) to "hit down",
            listOf(
                OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(64, 32, Buttons.LEFT_RELEASE),
            ) to "hit released",
            listOf(OracleClient.Point(224, 64, Buttons.LEFT_BUTTON)) to "exit down",
            listOf(
                OracleClient.Point(224, 64, Buttons.LEFT_BUTTON),
                OracleClient.Point(224, 64, Buttons.LEFT_RELEASE),
            ) to "exit released",
        )) {
            val (points, label) = event
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = 256,
                    height = 256,
                    recordTrace = true,
                ).trace!!,
                upstream(),
            )
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream(),
            )
            assertLines("$label trace", expected, actual)

            val flashing = label.endsWith("down")
            assertTrue(
                "$label has flash text",
                actual.lines().any { it.startsWith("text ") && it.contains(" 4 ") } ==
                    flashing,
            )
        }
    }

    @Test
    fun `the reveal button is clipped when it is reachable`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", "w5h5m3M3", SEED).state
            val points = taps(
                64 to 64,
                96 to 64,
                128 to 64,
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = 256,
                    height = 256,
                    recordTrace = true,
                ).trace!!,
                upstream(),
            )
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream(),
            )
            assertLines("reveal button trace", expected, actual)

            for (line in listOf(
                "clip 15 15 33 33",
                "circle 30 30 14 8 8",
                "circle 30 30 12 11 11",
                "unclip",
                "update 16 16 32 32",
            )) {
                assertTrue("missing '$line'", actual.lines().contains(line))
            }
            assertTrue(
                "the status text is present",
                actual.lines().contains("status \"Click button to verify guesses.\""),
            )
        }

    @Test
    fun `a cursor move and a cursor on a guessed cell match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", "w5h5m3M3", SEED).state
            val cases = listOf(
                "cursor move" to listOf(
                    OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT),
                ),
                "cursor selects a ball" to listOf(
                    OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                    OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                ),
            )
            for ((label, points) in cases) {
                val expected = TraceNormalizer.normalize(
                    oracle.pointsSized(
                        save,
                        points,
                        width = 256,
                        height = 256,
                        recordTrace = true,
                    ).trace!!,
                    upstream(),
                )
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
                for (point in points) {
                    assertEquals(
                        KeyOutcome.SomeEffect,
                        midend.processKey(point.x, point.y, point.button, TILE),
                    )
                }
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend),
                    upstream(),
                )
                assertLines("$label trace", expected, actual)
                if (label == "cursor move") {
                    assertEquals(
                        "an empty cursor cell draws the square",
                        1,
                        actual.lines().count { it.startsWith("poly -1 12 4 ") },
                    )
                } else {
                    assertEquals(
                        "a cursor on a ball has no square",
                        0,
                        actual.lines().count { it.startsWith("poly -1 12 4 ") },
                    )
                    assertTrue(
                        "the cursor rings the ball",
                        actual.lines().contains("circle 64 64 14 12 12"),
                    )
                }
            }
        }

    @Test
    fun `revealed states draw the guessed, missed, and wrong cells`() =
        OracleClient().use { oracle ->
            val desc = "7f1b9a74d74b19f6"
            val correct = historySave(
                desc,
                listOf(
                    "MOVE" to "T1,4",
                    "MOVE" to "T4,2",
                    "MOVE" to "T2,4",
                    "MOVE" to "R",
                ),
                5,
            )
            val wrong = historySave(
                desc,
                listOf(
                    "MOVE" to "F0",
                    "MOVE" to "T1,1",
                    "MOVE" to "T2,1",
                    "MOVE" to "T3,1",
                    "MOVE" to "R",
                ),
                6,
            )
            val solved = historySave(desc, listOf("SOLVE" to "S"), 2)
            val solvedWrong = historySave(
                desc,
                listOf(
                    "MOVE" to "T1,1",
                    "MOVE" to "T2,1",
                    "MOVE" to "T3,1",
                    "SOLVE" to "S",
                ),
                5,
            )

            for ((label, text) in listOf(
                "correct" to correct,
                "wrong" to wrong,
                "solve" to solved,
                "solve with wrong guesses" to solvedWrong,
            )) {
                val expected = TraceNormalizer.normalize(oracle.trace(text), upstream())
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend),
                    upstream(),
                )
                assertLines("$label reveal trace", expected, actual)
            }

            // A correct guess is black, a missed ball is red, and a
            // wrong guess gets the two red cross strokes.
            val correctTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(
                    Midend.fromSave(BlackBox, SgtpSave.parse(correct)),
                ),
                upstream(),
            )
            assertTrue(
                "a guessed ball is black",
                correctTrace.lines().contains("circle 64 160 14 9 9") &&
                    correctTrace.lines().contains("circle 64 160 12 9 9"),
            )
            assertTrue(
                "a correct reveal has no cross",
                correctTrace.lines().none {
                    it.startsWith("poly 10 10 4 ") || it.startsWith("poly 10 10 8 ")
                },
            )

            val wrongTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(
                    Midend.fromSave(BlackBox, SgtpSave.parse(solvedWrong)),
                ),
                upstream(),
            )
            assertEquals(
                "three wrong guesses have six cross strokes",
                6,
                wrongTrace.lines().count { it.startsWith("poly 10 10 4 ") },
            )
        }

    @Test
    fun `a warm redraw has no frame, no blitter, and no repeated status`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", "w5h5m3M3", SEED).state
            val cases = listOf(
                "arena click" to taps(64 to 64),
                "range click" to taps(64 to 32),
                "reveal click" to taps(
                    64 to 64,
                    96 to 64,
                    128 to 64,
                    32 to 32,
                ),
            )
            for ((label, points) in cases) {
                val expected = normalizedWarm(
                    oracle.traceWarm(save, points, width = 256, height = 256).trace!!,
                    upstream(),
                )
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
                val actual = normalizedWarm(
                    KotlinTraceSink.warmTrace(midend, points),
                    upstream(),
                )
                assertLines("$label warm trace", expected, actual)
                assertFalse(
                    "$label has no frame",
                    actual.lines().any { it.startsWith("line ") },
                )
                assertFalse(
                    "$label has no blitter",
                    actual.lines().any { it.startsWith("blitter_") },
                )
                assertFalse(
                    "$label has no full update",
                    actual.lines().contains("update 0 0 256 256"),
                )
                assertEquals(
                    "$label redraws the reveal button",
                    1,
                    actual.lines().count { it == "update 16 16 32 32" },
                )
            }

            // The one-click case is exactly the four oracle operations,
            // including no status line.
            val oneClick = KotlinTraceSink.warmTrace(
                Midend.fromSave(BlackBox, SgtpSave.parse(save)),
                taps(64 to 64),
            )
            assertEquals(
                listOf(
                    "start_draw",
                    "rect 15 15 32 32 0",
                    "update 16 16 32 32",
                    "end_draw",
                ),
                opsOf(oneClick),
            )
        }

    @Test
    fun `a sized warm trace uses the viewport tile size`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", "w5h5m3M3", SEED).state
            // The 504x504 viewport gives tile 63 and no letterbox. The
            // arena cell (1,1) is at game (126,126), as in the oracle.
            val width = 504
            val height = 504
            val tile = 63
            val points = taps(2 * tile to 2 * tile)
            val expected = normalizedWarm(
                oracle.traceWarm(
                    save,
                    points,
                    width = width,
                    height = height,
                ).trace!!,
                upstream(),
            )
            val actualTrace = KotlinTraceSink.warmTrace(
                Midend.fromSave(BlackBox, SgtpSave.parse(save)),
                points,
                tileSize = tile,
            )
            val actual = normalizedWarm(actualTrace, upstream())
            assertLines("sized warm trace", expected, actual)
            assertFalse("no frame", actual.lines().any { it.startsWith("line ") })
            assertEquals(
                "one warmed arena cell and one reveal button",
                listOf(
                    "start_draw",
                    "rect 30 30 63 63 0",
                    "update 31 31 63 63",
                    "end_draw",
                ),
                opsOf(actualTrace),
            )
        }

    @Test
    fun `a changed status appears once in a warm frame`() {
        val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
        val sink = KotlinTraceSink()
        midend.recordNewGameTrace(sink)
        assertTrue(
            sink.ops.any {
                it.fields["op"] == JsonValue.Str("status") &&
                    it.fields["text"] == JsonValue.Str("Balls marked: 0 / 3")
            },
        )

        // A real move changes the status. The midend redraws on the
        // next frame, which emits the new text exactly once.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(64, 64, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(64, 64, Buttons.LEFT_RELEASE, TILE),
        )
        sink.ops.clear()
        midend.recordRedraw(sink)
        val statuses = sink.ops.filter {
            it.fields["op"] == JsonValue.Str("status")
        }
        assertEquals(1, statuses.size)
        assertEquals(
            "Balls marked: 1 / 3",
            (statuses[0].fields["text"] as JsonValue.Str).value,
        )
    }

    @Test
    fun `a sized trace uses the viewport tile size`() = OracleClient().use { oracle ->
        val save = oracle.newGame("blackbox", "w5h5m3M3", SEED).state
        val cases = listOf(
            Triple(411, 702, 51),
            Triple(800, 800, 100),
            Triple(720, 1280, 90),
        )
        for ((viewW, viewH, tile) in cases) {
            val board = BlackBox.computeSize(BlackBox.Params(5, 5, 3, 3), tile)
            val originX = (viewW - board.width) / 2
            val originY = (viewH - board.height) / 2
            val gameX = tile * 2
            val gameY = tile * 2
            val points = listOf(
                OracleClient.Point(originX + gameX, originY + gameY, Buttons.LEFT_BUTTON),
                OracleClient.Point(originX + gameX, originY + gameY, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = viewW,
                    height = viewH,
                    recordTrace = true,
                ).trace!!,
                upstream(),
            )
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(gameX, gameY, point.button, tile)
            }
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream(),
            )
            assertLines("${viewW}x$viewH trace", expected, actual)
        }
    }

    private fun normalizedWarm(trace: JsonValue.Obj, upstream: String): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun opsOf(trace: JsonValue.Obj): List<String> =
        ((trace.field("ops") as JsonValue.Arr).items)
            .filterIsInstance<JsonValue.Obj>()
            .map { op ->
                when (op.string("op")) {
                    "start_draw" -> "start_draw"
                    "end_draw" -> "end_draw"
                    "rect" -> "rect ${op.number("x").toInt()} ${op.number("y").toInt()} " +
                        "${op.number("w").toInt()} ${op.number("h").toInt()} " +
                        "${op.number("c").toInt()}"
                    "update" -> "update ${op.number("x").toInt()} " +
                        "${op.number("y").toInt()} ${op.number("w").toInt()} " +
                        "${op.number("h").toInt()}"
                    else -> op.string("op")
                }
            }

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

    private fun taps(vararg points: Pair<Int, Int>): List<OracleClient.Point> =
        points.flatMap {
            listOf(
                OracleClient.Point(it.first, it.second, Buttons.LEFT_BUTTON),
                OracleClient.Point(it.first, it.second, Buttons.LEFT_RELEASE),
            )
        }

    private fun historySave(
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", BlackBox.name),
            SaveRecord("PARAMS", "w5h5m3M3"),
            SaveRecord("CPARAMS", "w5h5m3M3"),
            SaveRecord("DESC", desc),
            SaveRecord("UI", "E0"),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }
}
