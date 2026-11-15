package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: twiddle.
 *
 * Every `twiddle` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes
 * a Kotlin-written save for every preset, plus a move target, and lets
 * C read it.
 *
 * One quirk gets its own test. `encode_params` ignores its `full`
 * argument, so `m` and the `r` and `o` flags stay in the short id and in
 * both `PARAMS` and `CPARAMS`. That is the same behavior as sixteen and
 * the opposite of flood and fifteen, whose short form drops the
 * non-geometric parameters.
 *
 * The history cases are built here, so the corpus stays frozen. A
 * played move, a solve, and a completion are produced by C as well as by
 * Kotlin. C has no restart operation, so the restart case is written by
 * Kotlin and the C direction loads the undo of it, as the formal and
 * sixteen save tests do.
 */
class TwiddleSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val defaultDesc = "2,5,6,7,3,1,4,9,8"

    private val completionMoves = listOf(
        "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
        "M0,0,-1", "M0,1,-1", "M1,0,-1", "M0,1,-1",
    )

    @Test
    fun `every twiddle save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == Twiddle.name
                }
                .sorted()
                .toList()
            assertTrue("no twiddle saves in the corpus", files.isNotEmpty())

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Twiddle, SgtpSave.parse(text))

                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
            }
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() = OracleClient().use { oracle ->
        // The presets cover `rowsonly` and `orientable`. The target string
        // is not a preset, so it is added explicitly: a game without one
        // never exercises the `m` branch of `encode_params`.
        val paramsList = Twiddle.presets().map { it.params }.distinct() + "3x3n2m10"
        assertEquals("one save per preset plus a target", 9, paramsList.size)

        for (params in paramsList) {
            val midend = Midend.newGame(Twiddle, params, SEED)
            val text = midend.serialise().serialise()
            val loaded = oracle.id(text)

            assertEquals("$params id", midend.id(), loaded.id)
            assertEquals("$params canonical text", text, loaded.state)
            assertEquals("$params text", midend.textFormat(), oracle.text(text))
            assertEquals("$params status", midend.status(), loaded.status)
            assertFalse("$params starts with no undo", loaded.canUndo)
            assertFalse("$params starts with no redo", loaded.canRedo)
        }
    }

    @Test
    fun `the full argument is ignored, so the id and both parameter records keep m and the flags`() =
        OracleClient().use { oracle ->
            // `encode_params` ignores `full`, so the short form and the
            // long form are the same string. Flood and fifteen lose their
            // non-geometric parameters in `PARAMS` and in the game id;
            // twiddle must not.
            val cases = listOf(
                "3x3n2",
                "3x3n2r",
                "3x3n2o",
                "3x3n2m10",
                "3x3n2rom5",
                "6x6n4",
            )

            for (params in cases) {
                val decoded = Twiddle.decodeParams(params)
                assertEquals(
                    "$params short form",
                    Twiddle.encodeParams(decoded, true),
                    Twiddle.encodeParams(decoded, false),
                )
                assertEquals("$params canonical form", params, Twiddle.encodeParams(decoded, false))

                val midend = Midend.newGame(Twiddle, params, SEED)
                val save = midend.serialise()
                assertEquals("$params PARAMS", params, save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                assertEquals("$params id", params, midend.id().substringBefore(':'))
                assertTrue(
                    "$params id keeps the parameters: ${midend.id()}",
                    midend.id().startsWith("$params:"),
                )

                // The id keeps `m`, and `r` and `o` stay in front of it.
                assertEquals(
                    "$params oracle id",
                    oracle.newGame("twiddle", params, SEED).id,
                    midend.id(),
                )
                assertEquals(
                    "$params oracle reads the Kotlin save",
                    midend.id(),
                    oracle.id(save.serialise()).id,
                )
                assertNull("$params UI record", save.value("UI"))
                assertNull("$params AUXINFO record", save.value("AUXINFO"))
            }

            // The parameters that are easy to lose are present by name
            // in the id and in the parameter records.
            assertTrue("the id keeps m", Midend.newGame(Twiddle, "3x3n2m10", SEED).id().startsWith("3x3n2m10:"))
            assertTrue("the id keeps r", Midend.newGame(Twiddle, "3x3n2r", SEED).id().startsWith("3x3n2r:"))
            assertTrue("the id keeps o", Midend.newGame(Twiddle, "3x3n2o", SEED).id().startsWith("3x3n2o:"))
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        // The corpus has initial saves only. Build the history cases
        // here: a played move, an undone position with a live redo
        // branch, a solve, a completed state, and a restart.
        val params = "3x3n2"
        val base = historySave(params, defaultDesc, emptyList(), 1)
        val baseText = Midend.fromSave(Twiddle, SgtpSave.parse(base)).textFormat()
        val undoneMoves = listOf(
            SaveRecord("MOVE", "M0,0,1"),
            SaveRecord("MOVE", "M1,0,1"),
            SaveRecord("MOVE", "M1,1,1"),
        )

        val cases = listOf(
            History("one move", listOf(SaveRecord("MOVE", "M0,0,1")), 2) { _ ->
                oracle.key(base, 72, 72, Buttons.LEFT_BUTTON).state
            },
            // The case is the state after the second of three moves, so
            // both the undo and the redo branch are live.
            History("undone position with a redo branch", undoneMoves, 2) {
                oracle.undo(historySave(params, defaultDesc, undoneMoves, 3)).state
            },
            History("solve", listOf(SaveRecord("SOLVE", "S")), 2) { _ ->
                oracle.solve(base).state
            },
            History(
                "completed",
                completionMoves.map { SaveRecord("MOVE", it) },
                9,
            ) { _ ->
                oracle.pointsSized(base, completionClicks()).state.state
            },
            // There is no restart operation in the oracle, so C reads the
            // restart save written here and the reverse direction loads
            // its undo.
            History(
                "restart",
                listOf(SaveRecord("MOVE", "M0,0,1"), SaveRecord("RESTART", defaultDesc)),
                3,
            ) { text -> oracle.undo(text).state },
        )

        for (case in cases) {
            val text = historySave(params, defaultDesc, case.history, case.statePos)
            val midend = Midend.fromSave(Twiddle, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())

            val expected = oracle.id(text)
            assertEquals("${case.label} C text", text, expected.state)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
            assertEquals(
                "${case.label} status text",
                traceStatus(oracle, text),
                midend.statusText(),
            )
            assertNull("${case.label} UI record", midend.serialise().value("UI"))
            assertNull("${case.label} AUXINFO record", midend.serialise().value("AUXINFO"))

            // Each case must show a real effect, not only matching flags.
            val state = midend.state as Twiddle.State
            when (case.label) {
                "one move" -> {
                    assertEquals(1, state.moveCount)
                    assertEquals("Moves: 1", midend.statusText())
                    assertTrue("the move changes the board", baseText != midend.textFormat())
                    assertTrue("${case.label} adds history", midend.canUndo)
                    assertFalse("${case.label} has no redo", midend.canRedo)
                    assertEquals(listOf("M0,0,1"), midend.serialise().values("MOVE"))
                }

                "undone position with a redo branch" -> {
                    assertEquals(1, state.moveCount)
                    assertEquals("Moves: 1", midend.statusText())
                    assertEquals(0, midend.status())
                    assertTrue("the undo target changes the board", baseText != midend.textFormat())
                    assertTrue("${case.label} can undo", midend.canUndo)
                    assertTrue("${case.label} can redo", midend.canRedo)
                    assertEquals(3, midend.serialise().values("MOVE").size)
                }

                "solve" -> {
                    assertTrue("the solve sets used_solve", state.usedSolve)
                    assertEquals(1, midend.status())
                    assertEquals("Moves since auto-solve: 0", midend.statusText())
                    assertEquals((1..9).toList(), state.grid.map { it / 4 })
                    assertTrue("the solve clears orientation", state.grid.all { it and 3 == 0 })
                    assertEquals(listOf("S"), midend.serialise().values("SOLVE"))
                }

                "completed" -> {
                    assertEquals(8, state.completed)
                    assertEquals(8, state.moveCount)
                    assertEquals(1, midend.status())
                    assertEquals("COMPLETED! Moves: 8", midend.statusText())
                    assertTrue("the completion changes the board", baseText != midend.textFormat())
                }

                "restart" -> {
                    assertEquals(0, state.moveCount)
                    assertEquals(0, state.completed)
                    assertEquals("Moves: 0", midend.statusText())
                    assertEquals(baseText, midend.textFormat())
                    assertEquals(defaultDesc, midend.serialise().value("RESTART"))
                    assertTrue("${case.label} adds history", midend.canUndo)
                    assertFalse("${case.label} is the last state", midend.canRedo)
                }
            }

            // C produces a save from the same history, and Kotlin reads it.
            val cText = case.fromC(text)
            val cState = oracle.id(cText)
            assertEquals("${case.label} C writes canonical text", cText, cState.state)
            val reloaded = Midend.fromSave(Twiddle, SgtpSave.parse(cText))
            assertEquals(
                "${case.label} reloaded canonical text",
                cText,
                reloaded.serialise().serialise(),
            )
            assertEquals("${case.label} reloaded id", cState.id, reloaded.id())
            assertEquals("${case.label} reloaded status", cState.status, reloaded.status())
            assertEquals("${case.label} reloaded can undo", cState.canUndo, reloaded.canUndo)
            assertEquals("${case.label} reloaded can redo", cState.canRedo, reloaded.canRedo)
            assertEquals("${case.label} reloaded text", oracle.text(cText), reloaded.textFormat())
            assertEquals(
                "${case.label} reloaded status text",
                traceStatus(oracle, cText),
                reloaded.statusText(),
            )
        }

        // Undo and redo across the SOLVE record are safe in C: the midend
        // clears the animation for every record type that is not `MOVE`,
        // and twiddle has no `SOLVE_ANIMATES` flag.
        val solvedText = oracle.solve(base).state
        assertEquals("SOLVE", SgtpSave.parse(solvedText).records.last().key)
        var solvedExpected = oracle.id(solvedText)
        val solved = Midend.fromSave(Twiddle, SgtpSave.parse(solvedText))
        assertEquals("the solved save", solvedExpected.state, solved.serialise().serialise())
        assertEquals(solvedExpected.id, solved.id())
        assertEquals(solvedExpected.status, solved.status())

        solvedExpected = oracle.undo(solvedText)
        assertTrue("undo from a solved state", solved.undo())
        assertEquals("undo writes the C text", solvedExpected.state, solved.serialise().serialise())
        assertEquals("undo id", solvedExpected.id, solved.id())
        assertEquals("undo flags", solvedExpected.canRedo, solved.canRedo)
        assertFalse("the undo leaves the solve", (solved.state as Twiddle.State).usedSolve)
        assertEquals("Moves: 0", solved.statusText())

        solvedExpected = oracle.redo(solvedExpected.state)
        assertTrue("redo into the solved state", solved.redo())
        assertEquals("redo writes the C text", solvedExpected.state, solved.serialise().serialise())
        assertEquals("redo id", solvedExpected.id, solved.id())
        assertTrue("the redo enters the solve", (solved.state as Twiddle.State).usedSolve)
        assertEquals("Moves since auto-solve: 0", solved.statusText())
    }

    private data class History(
        val label: String,
        val history: List<SaveRecord>,
        val statePos: Int,
        val fromC: (String) -> String,
    )

    /**
     * The eight clicks that complete the default seed 12345 board. The
     * sequence is the move list of `work/specs/twiddle.md`, section 4.
     */
    private fun completionClicks(): List<OracleClient.Point> = listOf(
        OracleClient.Point(48, 72, Buttons.LEFT_BUTTON),
        OracleClient.Point(48, 72, Buttons.LEFT_BUTTON),
        OracleClient.Point(96, 120, Buttons.LEFT_BUTTON),
        OracleClient.Point(48, 120, Buttons.LEFT_BUTTON),
        OracleClient.Point(48, 72, Buttons.RIGHT_BUTTON),
        OracleClient.Point(48, 120, Buttons.RIGHT_BUTTON),
        OracleClient.Point(96, 72, Buttons.RIGHT_BUTTON),
        OracleClient.Point(48, 120, Buttons.RIGHT_BUTTON),
    )

    private fun historySave(
        params: String,
        desc: String,
        history: List<SaveRecord>,
        statePos: Int,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Twiddle.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + history.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        records += history
        return SgtpSave.of(records).serialise()
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

    private companion object {
        const val SEED = "12345"
    }
}
