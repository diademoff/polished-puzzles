package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: filling.
 *
 * The drawing order, coordinates, palette, per-cell cache, and the
 * absence of a status line are compared with a fresh oracle trace and
 * with the frozen trace for every preset. The selected, cursor, and
 * completed frames pin the three flag groups that only the value and
 * the UI can set.
 */
class FillingTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val paramsList = oracle.presets("filling").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Filling, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("filling", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/filling/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)

            assertTrue(
                "filling draws no status line",
                actual.lines().none { it.startsWith("status ") },
            )
        }
    }

    @Test
    fun `a selected cell frame matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("9x7", DESC_9X7, emptyList())
        val points = listOf(OracleClient.Point(32, 32, Buttons.LEFT_BUTTON))

        val expected = oracle.pointsSized(save, points, recordTrace = true)
        val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            midend.processKey(32, 32, Buttons.LEFT_BUTTON, Filling.preferredTileSize),
        )
        val actual = KotlinTraceSink.stateTrace(midend, Filling.preferredTileSize)
        assertTrace("selected frame", expected.trace, actual)

        val text = TraceNormalizer.normalize(actual, upstream)
        assertTrue(
            "the selected cell uses COL_HIGHLIGHT",
            text.contains("rect 16 16 32 32 2"),
        )
    }

    @Test
    fun `a cursor frame matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("9x7", DESC_9X7, emptyList())

        val expected = oracle.trace(save, listOf(Buttons.CURSOR_LEFT))
        val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_LEFT, Filling.preferredTileSize),
        )
        val actual = KotlinTraceSink.stateTrace(midend, Filling.preferredTileSize)
        assertTrace("cursor frame", expected, actual)

        val text = TraceNormalizer.normalize(actual, upstream)
        assertTrue(
            "the cursor ring is an unfilled polygon in COL_CURSOR",
            text.contains("poly -1 6 4 20 20 20 43 43 43 43 20"),
        )
    }

    @Test
    fun `a completed frame matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("1x2", "2a", listOf("1_2"))
        val expected = oracle.trace(save)
        val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
        assertEquals(1, midend.status())

        val actual = KotlinTraceSink.stateTrace(midend, Filling.preferredTileSize)
        assertTrace("completed frame", expected, actual)

        val text = TraceNormalizer.normalize(actual, upstream)
        assertEquals(
            "both cells use CORRECT_BG",
            2,
            text.lines().count { it == "rect 16 16 32 32 3" || it == "rect 16 48 32 32 3" },
        )
        assertTrue("the clue digit uses COL_CLUE", text.contains("text 32 32 1 16 257 1 \"2\""))
        assertTrue("the filled digit uses COL_USER", text.contains("text 32 64 1 16 257 5 \"2\""))
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("13x9", DESC_13X9, emptyList())

        // Each viewport gives exactly one tile size.
        for ((width, height, tile) in listOf(
            Triple(672, 480, 48),
            Triple(882, 630, 63),
            Triple(1148, 820, 82),
            Triple(1400, 1000, 100),
            // The letterbox case: tile 29 with the board centred.
            Triple(411, 702, 29),
        )) {
            val expected = oracle.pointsSized(
                save,
                emptyList(),
                width,
                height,
                recordTrace = true,
            )
            val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
            val geometry = midend.geometryFor(width, height)
            assertEquals("tile for ${width}x$height", tile, geometry.tileSize)

            val actual = KotlinTraceSink.stateTrace(midend, geometry.tileSize)
            assertTrace("trace at ${width}x$height", expected.trace, actual)

            val normalized = TraceNormalizer.normalize(actual, upstream)
            assertEquals(
                "board size at ${width}x$height",
                "size ${14 * tile} ${10 * tile}",
                normalized.lines().first { it.startsWith("size ") },
            )
        }
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        assertLines(what, TraceNormalizer.normalize(expected, upstream),
            TraceNormalizer.normalize(actual, upstream))
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

    private fun saveText(params: String, desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Filling.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_9X7 = "a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55"
        const val DESC_13X9 =
            "a4a7f3c4a7a4a49b3c7a6b5a9a5b8a5a6c9b566c3b2b6a66c2a4c4b869992a424e999a33a4a9c99b3e"
    }
}
