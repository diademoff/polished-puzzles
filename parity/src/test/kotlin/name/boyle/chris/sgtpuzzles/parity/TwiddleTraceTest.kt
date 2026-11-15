package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: twiddle.
 *
 * The drawing order, the coordinates, the palette roles, the orientation
 * triangles, the cursor edges, and the status line are compared with a
 * fresh oracle trace and with the frozen traces for every preset.
 *
 * Every trace is a first redraw: the seam has no drawstate, so the port
 * always draws the recessed frame and the whole board. The rotation
 * animation and the flash are not ported, so no trace may be taken after
 * a move in a live midend: the oracle trace would contain the animation
 * and the old status text. Moved states are loaded from a save with
 * `MOVE` records, which produces a settled first redraw.
 */
class TwiddleTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val defaultDesc = "2,5,6,7,3,1,4,9,8"

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("twiddle").map { it.params }.distinct().sorted()

        assertEquals("preset count", 8, paramsList.size)
        for (params in paramsList) {
            val midend = Midend.newGame(Twiddle, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("twiddle", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/twiddle/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    @Test
    fun `the default trace has the C operation counts`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val params = "3x3n2"
        val trace = TraceNormalizer.normalize(
            oracle.traceNew("twiddle", params, SEED),
            upstream,
        )
        val lines = trace.lines().filter { it.isNotEmpty() }

        // 2 frame polygons and 4 side triangles per cell.
        assertEquals(2 + 9 * 4, lines.count { it.startsWith("poly ") })
        // One window clear and one inner rectangle per cell.
        assertEquals(1 + 9, lines.count { it.startsWith("rect ") })
        assertEquals(9, lines.count { it.startsWith("text ") })
        // One update per tile and the midend's whole-window update.
        assertEquals(10, lines.count { it.startsWith("update ") })
        assertEquals(1, lines.count { it.startsWith("status ") })
        assertEquals(1, lines.count { it == "changed_state false false" })
        assertEquals(1, lines.count { it == "start_draw" })
        assertEquals(1, lines.count { it == "end_draw" })
        assertFalse("a settled redraw has no clip", lines.any { it.startsWith("clip ") })
        assertTrue(
            "the palette is part of the trace: $trace",
            trace.contains(
                "palette 800,800,800 0,0,0 967,967,967 880,880,880 " +
                    "633,633,633 720,720,720 800,400,400 480,240,240",
            ),
        )
    }

    @Test
    fun `moved, completed, solved, and target states match the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val cases = listOf(
                Case("3x3n2", defaultDesc, listOf("M0,0,1"), null),
                Case("3x3n2", defaultDesc, listOf("M0,0,1", "M1,1,1", "M0,1,-1"), null),
                Case("3x3n2", defaultDesc, completionMoves, null),
                Case("3x3n2", defaultDesc, emptyList(), "S"),
                Case("3x3n2o", "2r5l6r7l3d1l4l9l8r", listOf("M0,0,1"), null),
                Case("3x3n2m10", defaultDesc, listOf("M0,0,1"), null),
                Case("4x4n3", fourDesc, listOf("M1,1,1"), null),
                Case("5x5n3o", fiveDesc, listOf("M0,0,-1"), null),
            )

            for (case in cases) {
                val save = saveText(case.params, case.desc, case.moves, case.solve)
                val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
                val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
                val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
                val what = "${case.params} ${case.moves} solve=${case.solve}"
                assertLines("trace for $what", expected, actual)
                assertEquals(
                    "status for $what",
                    traceStatus(oracle, save),
                    traceStatusOf(actual),
                )
            }

            // A moved state is settled: no clips, no animation, and the
            // new move count. A trace taken after a move in a live midend
            // would have clips and the old status.
            val moved = saveText("3x3n2", defaultDesc, listOf("M0,0,1"))
            val movedTrace = TraceNormalizer.normalize(oracle.trace(moved), upstream)
            assertFalse("no clip after a move", movedTrace.lines().any { it.startsWith("clip ") })
            assertEquals("Moves: 1", traceStatus(oracle, moved))
            assertEquals("Moves: 1", traceStatusOf(movedTrace))

            assertEquals(
                "Moves since auto-solve: 0",
                traceStatus(oracle, saveText("3x3n2", defaultDesc, emptyList(), "S")),
            )
            assertEquals(
                "COMPLETED! Moves: 8",
                traceStatus(oracle, saveText("3x3n2", defaultDesc, completionMoves)),
            )
            assertEquals(
                "Moves: 1 (target 10)",
                traceStatus(oracle, saveText("3x3n2m10", defaultDesc, listOf("M0,0,1"))),
            )
        }

    @Test
    fun `cursor traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("3x3n2", defaultDesc)
        val sequences = listOf(
            listOf(Buttons.CURSOR_UP),
            listOf(Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_DOWN),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
            ),
        )

        for (sequence in sequences) {
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )
            val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            for (key in sequence) {
                midend.processKey(0, 0, key, Twiddle.preferredTileSize)
            }
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)

            // The oracle suppresses a repeated status line after forced
            // keys, so the status is compared separately.
            val statusLines = actual.lines().filter { it.startsWith("status ") }
            assertEquals("one Kotlin status operation", 1, statusLines.size)
            assertEquals("status " + Json.quote(midend.statusText()), statusLines.single())
            val actualWithoutStatus = actual.lines()
                .filterNot { it.startsWith("status ") }
                .joinToString("\n")
            assertLines(
                "cursor trace $sequence",
                expected.lines().filterNot { it.startsWith("status ") }.joinToString("\n"),
                actualWithoutStatus,
            )
        }

        // A visible cursor draws the cursor edges with the cursor palette.
        val cursor = TraceNormalizer.normalize(
            oracle.trace(save, sequence = listOf(Buttons.CURSOR_UP)),
            upstream,
        )
        // The left and top edges of the region use COL_HIGHCURSOR (6),
        // the right and bottom edges COL_LOWCURSOR (7), and the inner
        // rectangle stays COL_BACKGROUND (0).
        assertTrue("cursor edges: $cursor", cursor.contains("poly 6 6 3"))
        assertTrue("cursor edges: $cursor", cursor.contains("poly 7 7 3"))
        assertTrue("cursor inner rectangle: $cursor", cursor.contains("rect 26 26 44 44 0"))
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

        // An inert click adds no move and no UI change, so the recorded
        // redraw has a fresh drawstate and no animation. This is the only
        // way to compare the drawing at a non-preferred tile size, where
        // `2 * (t / 2)` is not `t` and the highlight width truncates.
        for (case in listOf(
            Sized(251, 251, 63),
            Sized(411, 702, 103),
            Sized(400, 400, 100),
        )) {
            val save = saveText("3x3n2", defaultDesc)
            val probe = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            val geometry = probe.geometryFor(case.viewWidth, case.viewHeight)
            assertEquals("tile for ${case.viewWidth} x ${case.viewHeight}",
                case.tile, geometry.tileSize)

            val expected = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
                case.viewWidth,
                case.viewHeight,
                recordTrace = true,
            )
            assertTrue(
                "the click adds no move at tile ${case.tile}",
                SgtpSave.parse(expected.state.state).values("MOVE").isEmpty(),
            )
            val expectedTrace = TraceNormalizer.normalize(
                requireNotNull(expected.trace),
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(probe, case.tile),
                upstream,
            )
            assertLines("sized trace at tile ${case.tile}", expectedTrace, actual)
        }
    }

    private data class Sized(val viewWidth: Int, val viewHeight: Int, val tile: Int)

    private data class Case(
        val params: String,
        val desc: String,
        val moves: List<String>,
        val solve: String?,
    )

    /** The last status operation of a normalized trace, or null. */
    private fun traceStatusOf(trace: String): String? {
        var status: String? = null
        for (line in trace.lines()) {
            if (line.startsWith("status ")) {
                status = line.removePrefix("status ").removeSurrounding("\"")
            }
        }
        return status
    }

    private fun traceStatus(oracle: OracleClient, save: String): String? {
        val ops = (oracle.trace(save).field("ops") as JsonValue.Arr).items
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
        solve: String? = null,
    ): String {
        val stateCount = 1 + moves.size + (if (solve != null) 1 else 0)
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Twiddle.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", stateCount.toString()),
            SaveRecord("STATEPOS", stateCount.toString()),
        )
        if (solve != null) records += SaveRecord("SOLVE", solve)
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
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

    private companion object {
        const val SEED = "12345"
        val completionMoves = listOf(
            "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
            "M0,0,-1", "M0,1,-1", "M1,0,-1", "M0,1,-1",
        )
        val fourDesc = "3,13,1,15,12,8,7,6,16,4,9,5,2,14,10,11"
        val fiveDesc =
            "15u4u11d18d1d16d17d6d3r22r19r12r5l10u13l24l9r20d7u14l21d8d23d2d25r"
    }
}
