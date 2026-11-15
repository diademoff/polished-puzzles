package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: unruly.
 *
 * The drawing order, coordinates, palette, per-cell cache, and the
 * absence of a status line are compared with a fresh oracle trace and
 * with the frozen trace for every preset. The error frames pin the six
 * `FE_*_ROW_*` bits, `FE_COUNT`, and the two match bits; the sized
 * frames pin the outer rectangle and the letterbox.
 */
class UnrulyTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val presets = oracle.presets("unruly").map { it.params }.distinct().sorted()

        for (params in presets) {
            val midend = Midend.newGame(Unruly, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("unruly", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/unruly/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)

            assertTrue(
                "unruly draws no status line",
                actual.lines().none { it.startsWith("status ") },
            )
            assertNoBlitterOrCurve(actual)
        }
    }

    @Test
    fun `a horizontal three, a vertical three, and a count error match the oracle`() =
        OracleClient().use { oracle ->
            // Three 1s at cells 0, 1, and 2: the three-in-a-row bits.
            val horizontal = saveText("8x8dt", "AAAzzl")
            assertTrace(
                "horizontal three",
                oracle.trace(horizontal),
                Midend.fromSave(Unruly, SgtpSave.parse(horizontal)),
            )

            // Three 1s in column 0 at rows 0, 1, and 2: the vertical bits.
            val vertical = saveText("8x8dt", "Ahhzw")
            assertTrace(
                "vertical three",
                oracle.trace(vertical),
                Midend.fromSave(Unruly, SgtpSave.parse(vertical)),
            )

            // Five 1s in row 0: every cell of the row carries FE_COUNT and
            // draws one "!".
            val count = saveText("8x8dt", "AAAAAzzj")
            val countTrace = assertTrace(
                "count error",
                oracle.trace(count),
                Midend.fromSave(Unruly, SgtpSave.parse(count)),
            )
            assertEquals(
                "one bang per overfull cell",
                5,
                countTrace.lines().count { it.startsWith("text ") && it.endsWith("\"!\"") },
            )
        }

    @Test
    fun `the unique row match bars match the oracle`() = OracleClient().use { oracle ->
        // The repeating-row board is valid without `unique` and fails
        // only the match rule. It draws a bar across every cell of every
        // matching row.
        val save = saveText("8x8udt", DESC_ONE_EMPTY, listOf("P1,0,0"))
        val actual = assertTrace(
            "row match",
            oracle.trace(save),
            Midend.fromSave(Unruly, SgtpSave.parse(save)),
        )
        assertEquals(
            "one row bar per cell of the eight rows",
            64,
            actual.lines().count { it.startsWith("rect ") && it.endsWith(" 32 4 10") },
        )
    }

    @Test
    fun `a completed board draws without a flash`() = OracleClient().use { oracle ->
        val generated = oracle.newGame("unruly", "8x8dt", SEED)
        val completed = oracle.solve(generated.state)
        assertTrue("the solved save has status 1", completed.status == 1)

        val midend = Midend.fromSave(Unruly, SgtpSave.parse(completed.state))
        assertEquals(1, midend.status())
        val actual = assertTrace("completed board", oracle.trace(completed.state), midend)
        assertNoBlitterOrCurve(actual)
    }

    @Test
    fun `sized traces match the oracle at every tile and the letterbox`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                // 8x8: the board is 9 tiles per side.
                Case("8x8dt", 432, 432, 48),
                Case("8x8dt", 567, 567, 63),
                Case("8x8dt", 738, 738, 82),
                Case("8x8dt", 900, 900, 100),
                // 10x10: 11 tiles per side.
                Case("10x10de", 528, 528, 48),
                Case("10x10de", 693, 693, 63),
                Case("10x10de", 902, 902, 82),
                Case("10x10de", 1100, 1100, 100),
                // 14x14: 15 tiles per side.
                Case("14x14dn", 720, 720, 48),
                Case("14x14dn", 945, 945, 63),
                Case("14x14dn", 1230, 1230, 82),
                Case("14x14dn", 1500, 1500, 100),
                // The letterbox viewport: one tile larger than the board
                // in neither direction, so the origin is nonzero.
                Case("8x8dt", 411, 702, 45, 3, 148),
                Case("10x10de", 411, 702, 37, 2, 147),
                Case("14x14dn", 411, 702, 27, 3, 148),
                // A small square viewport.
                Case("8x8dt", 200, 200, 22, 1, 1),
                Case("10x10de", 200, 200, 18, 1, 1),
                Case("14x14dn", 200, 200, 13, 2, 2),
            )

            for (case in cases) {
                val save = saveText(case.params, descFor(case.params))
                val expected = oracle.pointsSized(
                    save,
                    emptyList(),
                    case.width,
                    case.height,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val geometry = midend.geometryFor(case.width, case.height)

                assertEquals("tile for ${case.params} in ${case.width}x${case.height}",
                    case.tile, geometry.tileSize)
                assertEquals("origin x", case.originX, geometry.originX)
                assertEquals("origin y", case.originY, geometry.originY)

                val actual = assertTrace(
                    "trace at ${case.width}x${case.height} for ${case.params}",
                    expected.trace,
                    midend,
                    geometry.tileSize,
                )
                assertNoBlitterOrCurve(actual)
            }
        }

    @Test
    fun `a warm frame is empty at every tile and in the letterbox`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_8X8)
        for (case in listOf(
            Case("8x8dt", 432, 432, 48, 0, 0),
            Case("8x8dt", 567, 567, 63, 0, 0),
            Case("8x8dt", 738, 738, 82, 0, 0),
            Case("8x8dt", 900, 900, 100, 0, 0),
            Case("8x8dt", 411, 702, 45, 3, 148),
        )) {
            // The oracle takes view coordinates, and the Kotlin sink takes
            // game coordinates, so subtract the letterbox origin.
            val viewPoint = OracleClient.Point(
                case.originX + case.tile / 2,
                case.originY + case.tile / 2,
                Buttons.LEFT_BUTTON,
            )
            val gamePoint = OracleClient.Point(
                case.tile / 2,
                case.tile / 2,
                Buttons.LEFT_BUTTON,
            )
            val expected = oracle.traceWarm(
                save,
                listOf(viewPoint),
                case.width,
                case.height,
            )
            val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
            val actual = KotlinTraceSink.warmTrace(midend, listOf(gamePoint), case.tile)
            assertTrace("warm at ${case.width}x${case.height}", expected.trace, actual)
            assertEquals(
                "the input already drew the frame at ${case.width}x${case.height}",
                listOf("start_draw", "end_draw"),
                (actual.field("ops") as JsonValue.Arr).items.map {
                    ((it as JsonValue.Obj).fields["op"] as JsonValue.Str).value
                },
            )
        }
    }

    @Test
    fun `a clicked frame and a cursor frame match the oracle`() = OracleClient().use { oracle ->
        val tile = 48
        val view = 9 * tile
        val save = saveText("8x8dt", DESC_8X8)

        // The click hides the cursor and places 1; the cold frame is a
        // fresh drawstate.
        val clicked = oracle.pointsSized(
            save,
            listOf(OracleClient.Point(24, 24, Buttons.LEFT_BUTTON)),
            width = view,
            height = view,
            recordTrace = true,
        )
        val clickMidend = Midend.fromSave(Unruly, SgtpSave.parse(save))
        clickMidend.processKey(24, 24, Buttons.LEFT_BUTTON, tile)
        assertTrace("clicked frame", clicked.trace, clickMidend, tile)

        // One right arrow shows the cursor ring at (1,0).
        val cursor = oracle.trace(save, listOf(Buttons.CURSOR_RIGHT))
        val cursorMidend = Midend.fromSave(Unruly, SgtpSave.parse(save))
        cursorMidend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32)
        val actual = assertTrace("cursor frame", cursor, cursorMidend, 32)
        assertTrue(
            "the cursor ring is four COL_CURSOR rects",
            actual.lines().count { it.startsWith("rect ") && it.endsWith(" 9") } == 4,
        )
    }

    private data class Case(
        val params: String,
        val width: Int,
        val height: Int,
        val tile: Int,
        val originX: Int = 0,
        val originY: Int = 0,
    )

    private fun descFor(params: String): String = when (params) {
        "8x8dt" -> DESC_8X8
        "10x10de" -> DESC_10X10
        "14x14dn" -> DESC_14X14
        else -> error("no description for $params")
    }

    private fun assertTrace(
        what: String,
        expected: JsonValue.Obj?,
        midend: Midend,
        tileSize: Int = Unruly.preferredTileSize,
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

    private fun assertNoBlitterOrCurve(trace: String) {
        assertTrue(
            "no blitter operation",
            trace.lines().none { it.startsWith("blitter") },
        )
        assertTrue(
            "no line or polygon operation",
            trace.lines().none { it.startsWith("line ") || it.startsWith("poly ") },
        )
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Unruly.name),
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
        const val DESC_8X8 = "bEACHcBdBDEalcbAbbBa"
        const val DESC_10X10 = "CcGDBgbCcbfbhdbBCdEcbkbABdd"
        const val DESC_14X14 = "BEdbFBgAAMeBccbcCddabFfHiCEAFBFejbbkfdAcgDDcec"
        const val DESC_ONE_EMPTY =
            "BaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAa"
    }
}
