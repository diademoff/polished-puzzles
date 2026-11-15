package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: sixteen.
 *
 * The drawing order, the coordinates, the palette roles, the cursor
 * arrow, and the status line are compared with a fresh oracle trace and
 * with the frozen traces for every preset.
 *
 * Every trace is a first redraw: the seam has no drawstate, so the port
 * draws the frame and the board on every call, and the oracle's frozen
 * traces come from `traceNew` (and from `trace` on a freshly loaded
 * save). A post-input trace in a live midend would show the animation's
 * first frame; the port has no animation, so those traces are compared
 * without the status line, as in the samegame test.
 */
class SixteenTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("sixteen").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Sixteen, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("sixteen", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/sixteen/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    @Test
    fun `moved, solved, and target states match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val cases = listOf(
            Case("4x4", base, listOf("R0,-1"), null),
            Case("4x4", base, listOf("R0,1", "C0,1"), null),
            Case("4x4", base, listOf("R0,0"), null),
            Case("4x4", base, emptyList(), "S"),
            Case("4x4m10", "4,8,2,16,3,10,6,12,15,7,14,5,13,1,11,9", emptyList(), null),
            Case("4x4m10", "4,8,2,16,3,10,6,12,15,7,14,5,13,1,11,9", listOf("R0,1"), null),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves, case.solve)
            val midend = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val what = "${case.params} ${case.moves} solve=${case.solve}"
            assertLines("trace for $what", expected, actual)
            assertEquals("status for $what", midend.statusText(), traceStatus(oracle, save))
        }

        // The settled trace is the first redraw: 105 operations, one
        // status line, and the new move count.
        val moved = saveText("4x4", base, listOf("R0,-1"), null)
        val movedTrace = TraceNormalizer.normalize(oracle.trace(moved), upstream)
        assertEquals(
            105,
            (oracle.trace(moved).field("ops") as JsonValue.Arr).items.size,
        )
        assertEquals(
            "status \"Moves: 1\"",
            movedTrace.lines().filter { it.isNotEmpty() }.takeLast(3).first(),
        )
        assertEquals("Moves: 1", traceStatus(oracle, moved))

        val solved = saveText("4x4", base, emptyList(), "S")
        assertEquals("Moves since auto-solve: 0", traceStatus(oracle, solved))

        // A target game shows the suffix in the status line.
        assertEquals("Moves: 0 (target 10)", traceStatus(oracle, saveText("4x4m10", base)))
        val targetMidend = Midend.newGame(Sixteen, "4x4m10", SEED)
        val targetTrace = TraceNormalizer.normalize(
            oracle.traceNew("sixteen", "4x4m10", SEED),
            upstream,
        )
        assertTrue(
            "the target suffix is in the trace: $targetTrace",
            targetTrace.contains("status \"Moves: 0 (target 10)\""),
        )
        val kotlinTarget = TraceNormalizer.normalize(KotlinTraceSink.trace(targetMidend), upstream)
        assertLines("target trace", targetTrace, kotlinTarget)
    }

    @Test
    fun `cursor traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("4x4", "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4")
        val sequences = listOf(
            // A visible cursor over cell (0,0): the inner rectangle uses
            // COL_LOWLIGHT and no arrow is drawn.
            listOf(Buttons.CURSOR_UP),
            // Top, left, right, and bottom arms of the ring.
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_LEFT),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
            ),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
            ),
        )

        for (sequence in sequences) {
            val midend = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            for (key in sequence) {
                assertEquals(
                    "key $key in $sequence must change something",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, Sixteen.preferredTileSize),
                )
            }
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )

            val expectedLines = expected.lines()
            assertFalse(
                "the oracle suppresses the repeated status",
                expectedLines.any { it.startsWith("status ") },
            )
            val statusLines = actual.lines().filter { it.startsWith("status ") }
            assertEquals("one Kotlin status operation", 1, statusLines.size)
            assertEquals("status " + Json.quote(midend.statusText()), statusLines.single())

            val actualWithoutStatus = actual.lines().filterNot { it.startsWith("status ") }
            assertLines("cursor trace $sequence", expectedLines.joinToString("\n"), actualWithoutStatus.joinToString("\n"))
        }

        // The top arm has the arrow poly and the C update rectangle.
        val top = TraceNormalizer.normalize(
            oracle.trace(
                save,
                sequence = listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP),
            ),
            upstream,
        )
        assertEquals(
            "sixteen static arrows and one highlighted arrow",
            17,
            top.lines().count {
                it.startsWith("poly ") && it.split(' ').getOrNull(2) == "1"
            },
        )
        assertTrue("the arrow update is the first update", top.contains("update 48 0 48 48"))
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("4x4", "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4")

        // A click in a cell is a UI update, so the recorded redraw is a
        // fresh drawstate with no animation and one status line.
        for ((view, tile, x, y) in listOf(
            Quad(200, 33, 34, 34),
            Quad(602, 100, 102, 102),
            Quad(380, 63, 65, 65),
        )) {
            val probe = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            val geometry = probe.geometryFor(view, view)
            assertEquals("tile for $view", tile, geometry.tileSize)

            val expected = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(x, y, Buttons.LEFT_BUTTON)),
                view,
                view,
                recordTrace = true,
            )
            val expectedTrace = TraceNormalizer.normalize(requireNotNull(expected.trace), upstream)
            assertEquals(
                "the click adds no move at $view",
                emptyList<String>(),
                SgtpSave.parse(expected.state.state).values("MOVE"),
            )

            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(probe, tile),
                upstream,
            )
            assertLines("sized trace $view", expectedTrace, actual)
            assertTrue(
                "one status line at $view",
                expectedTrace.lines().count { it.startsWith("status ") } == 1,
            )
        }
    }

    private data class Case(
        val params: String,
        val desc: String,
        val moves: List<String>,
        val solve: String?,
    )

    private data class Quad(val view: Int, val tile: Int, val x: Int, val y: Int)

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
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Sixteen"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size + (if (solve != null) 1 else 0)).toString()),
            SaveRecord("STATEPOS", (1 + moves.size + (if (solve != null) 1 else 0)).toString()),
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
    }
}
