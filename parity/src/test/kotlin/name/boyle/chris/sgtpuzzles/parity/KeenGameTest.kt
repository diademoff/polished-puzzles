package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and strict where a frame is compared. Game: keen.
 *
 * The new game, the status, the solve text, real clicks and keys, number
 * entry, pencil entry, undo and redo, and completion by input. The
 * highlight is frontend state, so a key sequence that only moves it is
 * compared through a draw trace.
 */
class KeenGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun save(params: String = "6dn", seed: String = SEED): String =
        Midend.newGame(Keen, params, seed).serialise().serialise()

    private fun assertSameState(what: String, expected: String, midend: Midend) {
        val loaded = SgtpSave.parse(expected)
        assertEquals("$what id", loaded.value("DESC"), midend.serialise().value("DESC"))
        assertEquals("$what save", expected, midend.serialise().serialise())
    }

    @Test(timeout = 600_000)
    fun `the solve move matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Keen.presets().map { it.params } + "") {
                val start = oracle.newGame("keen", params, SEED).state
                val expected = oracle.solve(start)
                val midend = Midend.fromSave(Keen, SgtpSave.parse(start))
                assertTrue("$params solve", midend.solve())
                assertEquals("$params status", 1, midend.status())
                assertTrue("$params can undo", midend.canUndo)
                assertEquals(
                    "$params save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                val solve = SgtpSave.parse(expected.state).value("SOLVE")!!
                assertTrue("$params solve prefix", solve.startsWith("S"))
                assertEquals(
                    "$params solve digits",
                    (midend.currentParams as Keen.Params).w.let { it * it } + 1,
                    solve.length,
                )
                assertEquals("$params solve record", solve, midend.serialise().value("SOLVE"))
            }
        }

    @Test(timeout = 300_000)
    fun `key sequences match the oracle in state and frame`() =
        OracleClient().use { oracle ->
            val start = save()
            val sequences = listOf(
                listOf(Buttons.CURSOR_UP, '6'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, '5'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '5'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '5'.code, '5'.code),
                listOf(Buttons.CURSOR_UP, '6'.code, '\b'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2),
                listOf('M'.code),
                listOf('m'.code),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_DOWN),
                listOf(Buttons.MOD_NUM_KEYPAD or '3'.code),
                listOf('\t'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_UP),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(start, sequence)
                val midend = Midend.fromSave(Keen, SgtpSave.parse(start))
                for (key in sequence) midend.processKey(0, 0, key, 48)
                assertEquals(
                    "state for $sequence",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("status for $sequence", expected.status, midend.status())
                assertEquals("undo for $sequence", expected.canUndo, midend.canUndo)
                assertEquals("redo for $sequence", expected.canRedo, midend.canRedo)
                assertEquals(
                    "frame for $sequence",
                    normalize(oracle.trace(start, sequence)),
                    normalize(KotlinTraceSink.stateTrace(midend)),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `undo and redo after a digit and after a solve match the oracle`() =
        OracleClient().use { oracle ->
            val start = save()
            val afterDigit = oracle.keys(start, listOf(Buttons.CURSOR_UP, '6'.code)).state

            val digit = Midend.fromSave(Keen, SgtpSave.parse(start))
            digit.processKey(0, 0, Buttons.CURSOR_UP, 48)
            digit.processKey(0, 0, '6'.code, 48)
            assertSameState("digit", afterDigit, digit)
            assertTrue(digit.undo())
            assertSameState("undo", oracle.undo(afterDigit).state, digit)
            assertTrue(digit.redo())
            assertSameState("redo", afterDigit, digit)

            val afterSolve = oracle.solve(start).state
            val solved = Midend.fromSave(Keen, SgtpSave.parse(start))
            assertTrue(solved.solve())
            assertSameState("solve", afterSolve, solved)
            assertTrue(solved.undo())
            assertSameState("solve undo", oracle.undo(afterSolve).state, solved)
            assertTrue(solved.redo())
            assertSameState("solve redo", afterSolve, solved)
        }

    @Test(timeout = 600_000)
    fun `completion by real clicks and digits matches the oracle`() =
        OracleClient().use { oracle ->
            val start = save()
            val aux = requireNotNull(Midend.fromSave(Keen, SgtpSave.parse(start)).auxInfo)
            val points = ArrayList<OracleClient.Point>()
            for (cell in 0 until 36) {
                val x = 24 + (cell % 6) * 48 + 2
                val y = 24 + (cell / 6) * 48 + 2
                points += OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
                points += OracleClient.Point(x, y, Buttons.LEFT_RELEASE)
                points += OracleClient.Point(0, 0, aux[cell + 1].code)
            }
            val expected = oracle.pointsSized(start, points, 336, 336)
            assertEquals("status", 1, expected.state.status)
            assertTrue("can undo", expected.state.canUndo)

            val midend = Midend.fromSave(Keen, SgtpSave.parse(start))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, 48)
            }
            assertEquals("save", expected.state.state, midend.serialise().serialise())
            assertEquals("status", 1, midend.status())
            assertTrue("completion event", midend.takeCompletedEvent())

            assertTrue(midend.undo())
            assertEquals("undo", oracle.undo(expected.state.state).state, midend.serialise().serialise())
            assertEquals(0, midend.status())
            assertTrue(midend.redo())
            assertEquals("redo", expected.state.state, midend.serialise().serialise())
            assertEquals(1, midend.status())
            assertTrue("redo completion", midend.takeCompletedEvent())
        }

    @Test(timeout = 300_000)
    fun `a play-time parameter is short on a new game and full on load`() =
        OracleClient().use { oracle ->
            // `multiplication_only` is in the full encoding and not in
            // the short one, and it changes the drawn operator. C builds
            // a new state from `me->params` (`midend.c:628`), which is
            // the short form decoded over the defaults on the game-id
            // path, and a load from `CPARAMS` (`midend.c:2634-2642`).
            val params = "5dem"
            val fresh = Midend.newGame(Keen, params, SEED)
            val freshState = fresh.state as Keen.State
            assertFalse("new state flag", freshState.params.multiplicationOnly)
            assertEquals("new state params", Keen.decodeParams("5"), freshState.params)
            assertEquals("PARAMS", "5dn", fresh.serialise().value("PARAMS"))
            assertEquals("CPARAMS", params, fresh.serialise().value("CPARAMS"))

            val cNew = oracle.newGame("keen", params, SEED)
            assertEquals("new game save", cNew.state, fresh.serialise().serialise())
            assertEquals("new game id", cNew.id, fresh.id())

            val loaded = Midend.fromSave(Keen, SgtpSave.parse(cNew.state))
            val loadedState = loaded.state as Keen.State
            assertTrue("loaded state flag", loadedState.params.multiplicationOnly)
            assertEquals("loaded state params", Keen.decodeParams(params), loadedState.params)
            assertEquals("load keeps the id", fresh.id(), loaded.id())

            // The flag is a drawing input only: the two states have the
            // same grid and blocks, and C draws the operator on the new
            // game and not on the load.
            assertTrue(
                "new game draws the operator",
                normalize(oracle.traceNew("keen", params, SEED)).lines().any {
                    it.startsWith("text ") && it.endsWith("\"5\u00d7\"")
                },
            )
            assertTrue(
                "loaded save draws no operator",
                normalize(oracle.trace(cNew.state)).lines().none {
                    it.startsWith("text ") && it.contains("\u00d7")
                },
            )
        }

    @Test(timeout = 300_000)
    fun `the request keys match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Keen.presets().map { it.params } + "") {
                val start = oracle.newGame("keen", params, SEED).state
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(start, fullCursor)
                    val actual = Keen.requestKeys(
                        Keen.decodeParams(if (params.isEmpty()) "6dn" else params),
                        fullCursor,
                    )
                    assertEquals(
                        "$params full=$fullCursor arrow mode",
                        2,
                        expected.arrowMode,
                    )
                    assertEquals(
                        "$params full=$fullCursor enum",
                        ArrowMode.ARROWS_LEFT,
                        actual.arrowMode,
                    )
                    assertEquals("$params full=$fullCursor count", expected.labels.size, actual.labels.size)
                    for (i in expected.labels.indices) {
                        assertEquals(
                            "$params full=$fullCursor label $i",
                            expected.labels[i].label,
                            actual.labels[i].name,
                        )
                        assertEquals(
                            "$params full=$fullCursor button $i",
                            expected.labels[i].button,
                            actual.labels[i].button,
                        )
                        assertEquals(
                            "$params full=$fullCursor arrows $i",
                            expected.labels[i].needsArrows,
                            actual.labels[i].needsArrows,
                        )
                    }
                }
            }
        }

    @Test(timeout = 120_000)
    fun `the text form is absent`() = OracleClient().use { oracle ->
        val start = save()
        assertEquals("", oracle.text(start))
        val midend = Midend.fromSave(Keen, SgtpSave.parse(start))
        assertFalse(Keen.canFormatAsText)
        assertEquals("", midend.textFormat())
    }

    private companion object {
        const val SEED = "12345"
    }
}
