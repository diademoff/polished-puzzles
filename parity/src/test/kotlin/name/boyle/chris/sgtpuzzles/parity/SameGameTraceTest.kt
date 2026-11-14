package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: samegame.
 *
 * The Kotlin drawing order, coordinates, palette roles, status text, and
 * updates are compared with a fresh oracle trace and with the frozen
 * trace. A selection trace is compared without its status operation:
 * the oracle's drawing layer caches the last status text, so a redraw
 * after a key sequence never repeats it. The Kotlin sink emits every
 * status call. The missing operation is asserted, not removed from both
 * sides.
 */
class SameGameTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("samegame").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(SameGame, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("samegame", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/samegame/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    @Test
    fun `completed impossible and blank states match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val cases = listOf(
            Case("2x2c3s2", "1,1,1,1", listOf("M0,1,2,3")),
            Case("2x2c3s2", "1,2,3,1", listOf("M0")),
            Case("3x3c3s2", "1,2,3,0,3,0,0,0,0", listOf("M0")),
            Case("2x2c3s2", "0,0,0,0", emptyList()),
            Case("2x2c3s2", "1,2,3,1", emptyList()),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves)
            val midend = Midend.fromSave(SameGame, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val what = "${case.params} '${case.desc}' ${case.moves}"
            assertLines("trace for $what", expected, actual)
            assertEquals("status for $what", midend.statusText(), traceStatus(oracle, save))
        }
    }

    @Test
    fun `a selected group matches the oracle except for the cached status`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val params = "5x5c3s2"
            val tile = SameGame.preferredTileSize
            val expectedGame = oracle.newGame("samegame", params, SEED)
            val midend = Midend.newGame(SameGame, params, SEED)

            val sequence = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            )
            for (key in sequence) {
                assertEquals(
                    "key $key",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, tile),
                )
            }

            val ui = midend.ui as SameGame.Ui
            assertTrue("the cursor is visible", ui.displaysel)
            assertEquals("the group is selected", 4, ui.nselected)
            assertEquals("Score: 0  Selected: 4 (4)", midend.statusText())
            assertEquals("the save does not hold the selection", expectedGame.state,
                midend.serialise().serialise())

            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.trace(expectedGame.state, sequence = sequence),
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
            assertEquals("trace without the cached status", expectedLines, actualWithoutStatus)
            assertTrue(
                "the selected group must draw COL_SEL",
                actual.lines().any { it.startsWith("rect ") && it.endsWith(" 11") },
            )
        }

    private data class Case(val params: String, val desc: String, val moves: List<String>)

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

    private fun saveText(params: String, desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Same Game"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
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
