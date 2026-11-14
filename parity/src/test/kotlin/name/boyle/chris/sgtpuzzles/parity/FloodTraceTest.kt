package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: flood.
 *
 * The Kotlin drawing order, coordinates, palette roles, status text, and
 * updates are compared with a fresh oracle trace and with the frozen
 * trace for every preset.
 *
 * The moved, unsolved, and cheated states exercise the solver preview
 * circles and the status prefix. The tile-15 trace exercises
 * `SEP_WIDTH == 0`, where C draws no border or corner rectangles.
 */
class FloodTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("flood").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Flood, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("flood", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/flood/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    @Test
    fun `moved and cheated states match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val base = "1112101220200112"
        val cases = listOf(
            Case("4x4c3m0", "$base,4", listOf("M2"), null),
            Case("4x4c3m0", "$base,4", emptyList(), "S2,0,1,2"),
            Case("4x4c3m0", "$base,4", listOf("M2", "M0", "M1", "M2"), "S2,0,1,2"),
            Case("4x4c3m0", "$base,2", listOf("M2", "M0", "M1", "M2"), null),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves, case.solve)
            val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val what = "${case.params} '${case.desc}' ${case.moves} solve=${case.solve}"
            assertLines("trace for $what", expected, actual)
            assertEquals("status for $what", midend.statusText(), traceStatus(oracle, save))
        }

        // A stored solution draws one circle for every square the next
        // move would capture.
        val solvedSave = saveText("4x4c3m0", "$base,4", emptyList(), "S2,0,1,2")
        val solvedTrace = TraceNormalizer.normalize(oracle.trace(solvedSave), upstream)
        assertEquals(
            "the first solution move marks four squares",
            4,
            solvedTrace.lines().count { it.startsWith("circle ") },
        )
        assertEquals(
            "Auto-solver used. 0 / 4 moves",
            traceStatus(oracle, solvedSave),
        )

        val playedSave = saveText(
            "4x4c3m0",
            "$base,4",
            listOf("M2", "M0", "M1", "M2"),
            "S2,0,1,2",
        )
        val played = TraceNormalizer.normalize(oracle.trace(playedSave), upstream)
        assertEquals("a finished solution has no preview", 0,
            played.lines().count { it.startsWith("circle ") })
        assertEquals("Auto-solved. 4 / 4 moves", traceStatus(oracle, playedSave))
    }

    @Test
    fun `a tile-15 trace omits the separator squares`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val params = "12x12c6m5"
        val midend = Midend.newGame(Flood, params, SEED)
        val save = midend.serialise().serialise()

        // A 200x200 viewport picks tile 15, where `SEP_WIDTH` is 0.
        val points = oracle.pointsSized(
            save,
            emptyList(),
            width = 200,
            height = 200,
            recordTrace = true,
        )
        val expected = TraceNormalizer.normalize(points.trace!!, upstream)
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 15), upstream)
        assertLines("tile-15 trace", expected, actual)
        assertEquals(
            "one clear, one separator, and one rectangle per tile",
            1 + 1 + 12 * 12,
            expected.lines().count { it.startsWith("rect ") },
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
        moves: List<String>,
        solve: String?,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Flood"),
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
