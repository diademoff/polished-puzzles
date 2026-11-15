package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: flip.
 *
 * The grid lines, every cell's diagram in matrix scan order, the hint
 * rectangles, the cursor colour, and the status line are compared with
 * a fresh oracle trace and with the frozen traces.
 *
 * Every compared trace is a first redraw: the port uses `NoDrawState`, so
 * it draws the grid and the whole board on every call, and the
 * oracle fixture is `traceNew` or `trace` on a freshly loaded save. A
 * post-input trace in a live midend would show the animation's first
 * frame; flip has no animation, so cursor traces are compared without
 * the status line, as the sixteen test does.
 */
class FlipTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("flip").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Flip, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("flip", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/flip/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    @Test
    fun `moved, hinted, and completed states match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val base = "d074191345d1644c17058,c90"
        val cases = listOf(
            Case("3x3c", base, listOf("M0,0"), null),
            Case("3x3c", base, listOf("M0,1", "M1,1"), null),
            Case("3x3c", base, emptyList(), "S000110011"),
            Case("3x3c", base, listOf("M0,1", "M1,1", "M1,2", "M2,2"), null),
            Case(
                "3x3c",
                base,
                listOf("M0,1", "M1,1", "M1,2", "M2,2"),
                "S000110011",
            ),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves, case.solve)
            val midend = Midend.fromSave(Flip, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val what = "${case.moves} solve=${case.solve}"
            assertLines("trace for $what", expected, actual)
            assertEquals("status for $what", midend.statusText(), traceStatus(oracle, save))
        }

        // The moved state is a settled 155-operation trace.
        val moved = saveText("3x3c", base, listOf("M0,0"))
        val movedTrace = oracle.trace(moved)
        assertEquals(
            155,
            (movedTrace.field("ops") as JsonValue.Arr).items.size,
        )
        assertEquals("Moves: 1", traceStatus(oracle, moved))

        // A solve draws three nested hint rectangles of four lines per
        // hinted cell and keeps the status open: 48 extra lines and 203
        // operations for 3x3c.
        val hinted = saveText("3x3c", base, emptyList(), "S000110011")
        val hintedTrace = TraceNormalizer.normalize(oracle.trace(hinted), upstream)
        assertEquals(
            203,
            (oracle.trace(hinted).field("ops") as JsonValue.Arr).items.size,
        )
        assertEquals("Auto-solver used. Moves: 0", traceStatus(oracle, hinted))
        assertEquals(
            "48 hint lines",
            48,
            hintedTrace.lines().count { it.startsWith("line ") && it.endsWith(" 5") },
        )

        val completed = saveText(
            "3x3c",
            base,
            listOf("M0,1", "M1,1", "M1,2", "M2,2"),
        )
        val completedTrace = TraceNormalizer.normalize(oracle.trace(completed), upstream)
        assertEquals("COMPLETED! Moves: 4", traceStatus(oracle, completed))
        assertEquals(
            "no hint lines when the game is complete",
            0,
            completedTrace.lines().count { it.startsWith("line ") && it.endsWith(" 5") },
        )
    }

    @Test
    fun `a cursor changes only the diagram colour of one cell`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = saveText("3x3c", "d074191345d1644c17058,c90")
            val sequence = listOf(Buttons.CURSOR_UP)

            val midend = Midend.fromSave(Flip, SgtpSave.parse(save))
            assertEquals(
                "the cursor key must change something",
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_UP, 48),
            )
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )

            // The oracle suppresses the unchanged status line; compare
            // the operations and then the Kotlin status separately.
            assertTrue(
                "the oracle suppresses the repeated status",
                expected.lines().none { it.startsWith("status ") },
            )
            val statusLines = actual.lines().filter { it.startsWith("status ") }
            assertEquals("one Kotlin status operation", 1, statusLines.size)
            assertEquals("status " + Json.quote(midend.statusText()), statusLines.single())

            assertLines(
                "cursor trace",
                expected.lines().joinToString("\n"),
                actual.lines().filterNot { it.startsWith("status ") }.joinToString("\n"),
            )

            // Cell (0,0) of 3x3c has three matrix entries, so the cursor
            // changes nine operations: one self rectangle and two
            // four-line boxes.
            assertEquals(9, expected.lines().count { it.endsWith(" 6") })
            assertEquals(
                1,
                expected.lines().count { it.startsWith("rect 45 45 7 7 6") },
            )
        }

    private data class Case(
        val params: String,
        val desc: String,
        val moves: List<String>,
        val solve: String?,
    )

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
            SaveRecord("GAME", "Flip"),
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
