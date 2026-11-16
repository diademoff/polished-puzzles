package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Undead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: undead.
 *
 * The frozen first frames, the oracle first frames, and the Kotlin trace
 * are compared line for line. The trace exercises the palette, the three
 * count icons, the eight path hints in the stable sort order, the
 * mirrors (`thick_line`), the monsters, the pencil icons, the cursor,
 * the error colours, and the ascii preference.
 *
 * The sized cases use the layout tile of the view, so the 5x5 tile 55
 * and the 7x7 tile 43 exercise the `T/4` truncation. The warm cases keep
 * one drawstate across the input, so a settled final frame is
 * `start_draw, end_draw`.
 */
class UndeadTraceTest {

    private companion object {
        const val SEED = "12345"

        const val FIXED_DESC =
            "1,5,6,ZZRaaaGRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"

        const val DEFAULT_DESC =
            "1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"

        const val DEFAULT_SOLVE =
            "S;Z0;Z1;Z2;Z3;Z4;G5;V6;V7;V8;V9;Z10;V11"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun midend(save: String): Midend =
        Midend.fromSave(Undead, SgtpSave.parse(save))

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

    private fun assertTrace(what: String, expected: JsonValue.Obj, actual: JsonValue.Obj) {
        assertLines(
            what,
            TraceNormalizer.normalize(expected, upstream()),
            TraceNormalizer.normalize(actual, upstream()),
        )
    }

    /** A save with one `MOVE` record. */
    private fun saveWithMove(move: String, params: String = "4x4dn"): String {
        val n = 2
        val out = StringBuilder()
        fun record(key: String, value: String) {
            out.append(key.padEnd(8)).append(':').append(value.length).append(':')
                .append(value).append('\n')
        }
        record("SAVEFILE", "Simon Tatham's Portable Puzzle Collection")
        record("VERSION", "1")
        record("GAME", "Undead")
        record("PARAMS", "4x4dn")
        record("CPARAMS", params)
        record("SEED", SEED)
        record("DESC", DEFAULT_DESC)
        record("NSTATES", n.toString())
        record("STATEPOS", n.toString())
        record("MOVE", move)
        return out.toString()
    }

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Undead.presets().map { it.params }) {
                val midend = Midend.newGame(Undead, params, SEED)
                val actual = KotlinTraceSink.trace(midend)
                assertTrace(
                    "oracle trace for '$params'",
                    oracle.traceNew("undead", params, SEED),
                    actual,
                )

                val normalized = TraceNormalizer.normalize(actual, upstream())
                val golden = File(testdata, "trace/undead/$params.trace").readText()
                assertLines("frozen trace for '$params'", golden, normalized)

                // No status line, no blitter, one changed_state.
                val ops = normalized.lines()
                assertFalse("'$params' has no status", ops.any { it.startsWith("status ") })
                assertFalse("'$params' has no blitter", ops.any { it.startsWith("blitter_") })
                assertEquals(
                    "'$params' changed_state",
                    1,
                    ops.count { it == "changed_state false false" },
                )
                assertEquals(
                    "'$params' palette",
                    "palette 800,800,800 0,0,0 0,0,0 1000,0,0 624,624,624 1000,1000,1000 " +
                        "0,0,0 1000,1000,1000 400,800,800 400,800,400 800,720,720 533,533,533",
                    ops.first { it.startsWith("palette ") },
                )
                // Each mirror is a thick_line in the text colour (2)
                // with integer endpoints and the tile's T/16 thickness.
                assertTrue(
                    "'$params' has a thick_line",
                    ops.any { it.startsWith("thick_line 4000 ") },
                )
            }
        }

    @Test
    fun `the 4x4 normal first frame has the measured detail`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(Midend.fromSave(Undead, SgtpSave.parse(save))), upstream())
            val lines = actual.lines()

            // Header.
            assertEquals("size 416 480", lines.first { it.startsWith("size ") })
            assertEquals(4, lines.count { it.startsWith("thick_line 4000 ") })
            assertTrue(lines.contains("thick_line 4000 96000 288000 128000 320000 2"))

            // The count texts.
            assertTrue(lines.contains("text 128 48 1 32 256 2 \"1\""))
            assertTrue(lines.contains("text 224 48 1 32 256 2 \"5\""))
            assertTrue(lines.contains("text 320 48 1 32 256 2 \"6\""))

            // The first hint drawn is the start of the first sorted path.
            assertTrue(lines.contains("rect 338 274 61 61 0"))
            assertTrue(lines.contains("text 368 304 1 32 257 2 \"0\""))
            // The last cell in column-major order.
            assertTrue(lines.contains("rect 273 337 63 63 0"))

            // The drawing order: midend clear, static grid, the game's
            // whole-window update, counts, hints, cells, the trailing
            // whole-window update, end_draw.
            val clear = lines.indexOf("rect 0 0 416 480 0")
            val staticGrid = lines.indexOf("rect 79 143 259 259 1")
            val firstStaticCell = lines.indexOf("rect 81 145 63 63 0")
            val gameUpdate = lines.indexOf("update 0 0 416 480")
            val count = lines.indexOf("rect 86 16 96 64 0")
            val hint = lines.indexOf("rect 338 274 61 61 0")
            val firstCell = lines.lastIndexOf("rect 81 145 63 63 0")
            val finalUpdate = lines.lastIndexOf("update 0 0 416 480")
            val end = lines.indexOf("end_draw")
            assertTrue("clear before the grid", clear < staticGrid)
            assertTrue("static grid before a cell", staticGrid < firstStaticCell)
            assertTrue("static cells before the game update", firstStaticCell < gameUpdate)
            assertTrue("game update before the counts", gameUpdate < count)
            assertTrue("counts before the hints", count < hint)
            assertTrue("hints before the cell loop", hint < firstCell)
            assertTrue("cell loop before the final update", firstCell < finalUpdate)
            assertTrue("final update before end_draw", finalUpdate < end)
        }

    @Test
    fun `sized cold traces at 48, 63, 82, and 100 match the oracle`() =
        OracleClient().use { oracle ->
            for (params in Undead.presets().map { it.params }) {
                val save = oracle.newGame("undead", params, SEED).state
                val p = Undead.decodeParams(params) as Undead.Params
                for (tile in listOf(48, 63, 82, 100)) {
                    val size = Undead.computeSize(p, tile)
                    val expected = oracle.pointsSized(
                        save,
                        emptyList(),
                        size.width,
                        size.height,
                        recordTrace = true,
                    )
                    assertEquals(
                        "'$params' tile $tile width",
                        size.width,
                        expected.state.width,
                    )
                    assertEquals(
                        "'$params' tile $tile height",
                        size.height,
                        expected.state.height,
                    )
                    assertTrace(
                        "'$params' tile $tile",
                        requireNotNull(expected.trace),
                        KotlinTraceSink.stateTrace(midend(save), tile),
                    )
                }
            }
        }

    @Test
    fun `the 411x702 letterbox matches the measured tiles and origins`() =
        OracleClient().use { oracle ->
            for ((params, tile, origin) in listOf(
                Triple("4x4dn", 63, 1 to 115),
                Triple("5x5dn", 55, 0 to 118),
                Triple("7x7dn", 43, 2 to 126),
            )) {
                val save = oracle.newGame("undead", params, SEED).state
                val geometry = midend(save).geometryFor(411, 702)
                assertEquals("'$params' tile", tile, geometry.tileSize)
                assertEquals("'$params' origin", origin, geometry.originX to geometry.originY)
                val layout = oracle.layout(save, 411, 702)
                assertEquals("'$params' layout width", layout.width, geometry.width)
                assertEquals("'$params' layout height", layout.height, geometry.height)

                val expected = oracle.pointsSized(
                    save,
                    emptyList(),
                    411,
                    702,
                    recordTrace = true,
                )
                assertTrace(
                    "'$params' letterbox",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(midend(save), tile),
                )
            }
        }

    @Test
    fun `the cursor, the pencil highlight, and the Android toggle match`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            for (sequence in listOf(
                listOf(Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
                listOf(
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_RIGHT,
                ),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT, 'G'.code),
            )) {
                val expected = oracle.trace(save, sequence)
                val midend = midend(save)
                for (key in sequence) midend.processKey(0, 0, key, 64)
                assertTrace(
                    "sequence $sequence",
                    expected,
                    KotlinTraceSink.stateTrace(midend),
                )
            }

            // A pointer click and a second pointer click on the same cell
            // toggle pencil mode (the Android branch).
            val points = listOf(
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                416,
                480,
                recordTrace = true,
            )
            val midend = midend(save)
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, 64)
            }
            assertTrace(
                "pencil toggle",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(midend),
            )
        }

    @Test
    fun `a done clue, a clue error, and a count error match`() =
        OracleClient().use { oracle ->
            for (save in listOf(
                saveWithMove("D1,0"),
                saveWithMove("G0"),
                saveWithMove("G0;G1;G2;G3;G4;G5"),
            )) {
                assertTrace(
                    "state trace",
                    oracle.trace(save),
                    KotlinTraceSink.stateTrace(midend(save)),
                )
            }
        }

    @Test
    fun `ascii mode draws the letters`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val expected = oracle.trace(save, prefs = "monsters=letters")
        val midend = midend(save)
        (midend.ui as Undead.Ui).ascii = true
        val actual = KotlinTraceSink.stateTrace(midend)
        assertTrace("ascii", expected, actual)
        val lines = TraceNormalizer.normalize(actual, upstream()).lines()
        assertTrue(lines.contains("text 107 48 1 32 257 2 \"G\""))
        assertTrue(lines.contains("text 203 48 1 32 257 2 \"V\""))
        assertTrue(lines.contains("text 299 48 1 32 257 2 \"Z\""))
    }

    @Test
    fun `a cheated solve and a real win match`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state

        // The solve move is a cheated state with status 0.
        val cheated = oracle.solve(save)
        assertEquals(0, cheated.status)
        assertTrace(
            "cheated",
            oracle.trace(cheated.state),
            KotlinTraceSink.stateTrace(midend(cheated.state)),
        )

        // A hand-written MOVE with the payload body is a real win.
        val won = saveWithMove(DEFAULT_SOLVE.removePrefix("S;"))
        assertEquals(1, oracle.id(won).status)
        assertTrace(
            "won",
            oracle.trace(won),
            KotlinTraceSink.stateTrace(midend(won)),
        )
    }

    @Test
    fun `a fixed hand-written board matches`() = OracleClient().use { oracle ->
        val out = StringBuilder()
        fun record(key: String, value: String) {
            out.append(key.padEnd(8)).append(':').append(value.length).append(':')
                .append(value).append('\n')
        }
        record("SAVEFILE", "Simon Tatham's Portable Puzzle Collection")
        record("VERSION", "1")
        record("GAME", "Undead")
        record("PARAMS", "4x4dn")
        record("CPARAMS", "4x4dn")
        record("SEED", SEED)
        record("DESC", FIXED_DESC)
        record("NSTATES", "1")
        record("STATEPOS", "1")
        val save = out.toString()
        assertTrace(
            "fixed board",
            oracle.trace(save),
            KotlinTraceSink.stateTrace(midend(save)),
        )
    }

    @Test
    fun `the warm path settles to an empty frame`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val cases = listOf(
            listOf(OracleClient.Point(112, 176, Buttons.LEFT_BUTTON)),
            listOf(
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
                OracleClient.Point(107, 48, Buttons.LEFT_BUTTON),
            ),
            listOf(
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
                OracleClient.Point(199, 48, Buttons.LEFT_BUTTON),
            ),
            listOf(
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
                OracleClient.Point(299, 48, Buttons.LEFT_BUTTON),
            ),
            listOf(OracleClient.Point(112, 48, Buttons.LEFT_BUTTON)),
            listOf(OracleClient.Point(112, 48, 'M'.code)),
        )
        for (points in cases) {
            val expected = oracle.traceWarm(save, points)
            val actual = KotlinTraceSink.warmTrace(midend(save), points)
            assertTrace("warm $points", requireNotNull(expected.trace), actual)
            val lines = TraceNormalizer.normalize(actual, upstream()).lines()
            assertEquals(
                "warm $points final frame",
                listOf("start_draw", "end_draw"),
                lines.drop(7).filter { it.isNotBlank() },
            )
        }
    }

    @Test
    fun `the thick line thickness follows the tile`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        for (tile in listOf(48, 63, 82, 100)) {
            val size = Undead.computeSize(Undead.decodeParams("4x4dn"), tile)
            val expected = oracle.pointsSized(
                save,
                emptyList(),
                size.width,
                size.height,
                recordTrace = true,
            )
            val actual = KotlinTraceSink.stateTrace(midend(save), tile)
            assertTrace("tile $tile", requireNotNull(expected.trace), actual)
            val wanted = "thick_line ${(tile / 16) * 1000} "
            val lines = TraceNormalizer.normalize(actual, upstream()).lines()
            assertEquals(
                "tile $tile thickness",
                4,
                lines.count { it.startsWith(wanted) && it.endsWith(" 2") },
            )
        }
    }

    @Test
    fun `the hints are drawn in the stable sort order`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Undead, "4x4dn", SEED)
        val state = midend.state as Undead.State
        val order = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream())
            .lines()
            .filter { it.startsWith("text ") && it.contains(" 257 2 \"") }
            .map { it.substringAfter(" 2 \"").substringBefore('"').toInt() }
        assertEquals(16, order.size)
        // The eight paths in the stable num_monsters order: the start and
        // then the end of each path.
        val expected = state.common.paths.flatMap { path ->
            listOf(path.sightingsStart, path.sightingsEnd)
        }
        assertEquals(expected, order)
        assertEquals(
            listOf(1, 1, 2, 4, 4, 4, 4, 4),
            state.common.paths.map { it.numMonsters },
        )
        assertEquals(
            listOf(6, 11, 2, 0, 1, 3, 4, 7),
            state.common.paths.map { it.gridStart },
        )
    }
}
