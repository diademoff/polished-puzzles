package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: flip.
 *
 * New games, text, status text, the loose move grammar, clicks, undo
 * and redo, and the solver. A solve produces an `S` hint mask; it does
 * not complete the game, so the status stays 0 and the player clicks
 * the hinted cells.
 */
class FlipGameTest {

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        for (params in Flip.presets().map { it.params } + listOf("3x3c")) {
            for (seed in listOf("1", "12345", "seed")) {
                val expected = oracle.newGame("flip", params, seed)
                val actual = Midend.newGame(Flip, params, seed)
                assertSame("flip '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text flip '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals(
                    "status text flip '$params' seed=$seed",
                    traceStatus(oracle, expected.state),
                    actual.statusText(),
                )
            }
        }
    }

    @Test
    fun `the text format and status text are exact`() = OracleClient().use { oracle ->
        val base = "d074191345d1644c17058,c90"
        val cases = listOf(
            Case("3x3c", base, emptyList(), null),
            Case("3x3c", base, listOf("M0,0"), null),
            Case("3x3c", base, listOf("M0,1", "M1,1", "M1,2", "M2,2"), null),
            Case("3x3c", base, listOf("M0,1"), null),
            // A hand-written all-zero matrix loads and has empty text.
            Case("3x3c", "000000000000000000000,001", emptyList(), null),
            // A hand-written empty row still accepts a click move.
            Case("3x3c", "000000000000000000000,001", listOf("M0,0"), null),
            // Uppercase hex names the same board.
            Case("3x3c", "D074191345D1644C17058,C90", emptyList(), null),
            // The solve phase and a solve followed by one click.
            Case("3x3c", base, emptyList(), "S000110011"),
            Case("3x3c", base, listOf("M0,1"), "S000110011"),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves, case.solve)
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            val what = "${case.desc} ${case.moves} solve=${case.solve}"

            assertSame(what, expected, actual)
            assertEquals("text $what", oracle.text(save), actual.textFormat())
            assertEquals("status text $what", traceStatus(oracle, save), actual.statusText())
        }

        // The four measured status strings.
        assertEquals("Moves: 0", traceStatus(oracle, saveText("3x3c", base)))
        assertEquals(
            "Moves: 1",
            traceStatus(oracle, saveText("3x3c", base, listOf("M0,1"))),
        )
        assertEquals(
            "COMPLETED! Moves: 4",
            traceStatus(oracle, saveText("3x3c", base, listOf("M0,1", "M1,1", "M1,2", "M2,2"))),
        )
        assertEquals(
            "Auto-solver used. Moves: 0",
            traceStatus(oracle, saveText("3x3c", base, emptyList(), "S000110011")),
        )
        assertEquals(
            "Auto-solved. Moves: 4",
            traceStatus(
                oracle,
                saveText(
                    "3x3c",
                    base,
                    listOf("M0,1", "M1,1", "M1,2", "M2,2"),
                    "S000110011",
                ),
            ),
        )
    }

    @Test
    fun `the loose move grammar matches the oracle`() = OracleClient().use { oracle ->
        val base = "d074191345d1644c17058,c90"
        val accepted = listOf(
            "M1,0",
            "M+1,0",
            "M01,0",
            "M 1,0",
            "M1, 0",
            "M1,0junk",
            "M1,0,0",
            "M0,0",
            "M2,2",
        )
        for (move in accepted) {
            val save = saveText("3x3c", base, listOf(move))
            assertSame(
                "move '$move'",
                oracle.id(save),
                Midend.fromSave(Flip, SgtpSave.parse(save)),
            )
        }

        val rejected = listOf(
            "M1",
            "M-1,0",
            "M3,0",
            "M0,3",
            "Mx,0",
            "M",
            "M1;0",
            "M1 ,0",
            "S",
            "S00000000000",
            "s000000000",
            "x",
            "",
        )
        for (move in rejected) {
            val save = saveText("3x3c", base, listOf(move))
            var oracleFailed = false
            try {
                oracle.id(save)
            } catch (e: OracleException) {
                oracleFailed = true
            }
            assertTrue("oracle must reject '$move'", oracleFailed)

            var kotlinFailed = false
            try {
                Midend.fromSave(Flip, SgtpSave.parse(save))
            } catch (e: RuntimeException) {
                kotlinFailed = true
            }
            assertTrue("Kotlin must reject '$move'", kotlinFailed)
        }

        // An empty `S` mask is a valid move that marks no hints.
        val emptyHints = saveText("3x3c", base, listOf("S000000000"))
        assertSame(
            "empty hint mask",
            oracle.id(emptyHints),
            Midend.fromSave(Flip, SgtpSave.parse(emptyHints)),
        )

        // A click on an empty matrix row is a valid record, because only
        // `interpret_move` suppresses it.
        val dark = "000000000000000000000,001"
        val darkClick = saveText("3x3c", dark, listOf("M0,0"))
        assertSame(
            "empty-row click",
            oracle.id(darkClick),
            Midend.fromSave(Flip, SgtpSave.parse(darkClick)),
        )
    }

    @Test
    fun `clicks, completion, undo, and redo match the oracle`() = OracleClient().use { oracle ->
        val base = "d074191345d1644c17058,c90"
        val save = saveText("3x3c", base)

        // Four real pointer clicks at view coordinates, tile 48. The
        // oracle subtracts the letterbox origin; 192x192 has origin 0.
        val points = listOf(3, 4, 7, 8).map {
            val x = it % 3
            val y = it / 3
            OracleClient.Point(x * 48 + 24, y * 48 + 24, Buttons.LEFT_BUTTON)
        }
        val expected = oracle.pointsSized(save, points, 192, 192)
        val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
        for (point in points) {
            assertEquals(
                "click at ${point.x},${point.y}",
                KeyOutcome.SomeEffect,
                actual.processKey(point.x, point.y, point.button, 48),
            )
        }
        assertSame("four clicks", expected.state, actual)
        assertEquals("COMPLETED! Moves: 4", actual.statusText())
        assertEquals(listOf("M0,1", "M1,1", "M1,2", "M2,2"), actual.serialise().values("MOVE"))
        assertTrue(actual.takeCompletedEvent())

        // A border click does not add a move.
        val border = oracle.pointsSized(
            expected.state.state,
            listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
            192,
            192,
        )
        val borderActual = Midend.fromSave(Flip, SgtpSave.parse(expected.state.state))
        assertEquals(
            KeyOutcome.SomeEffect,
            borderActual.processKey(0, 0, Buttons.LEFT_BUTTON, 48),
        )
        assertSame("border click", border.state, borderActual)
        assertEquals(
            expected.state.state,
            borderActual.serialise().serialise(),
        )

        // Undo and redo across the completing move.
        var expectedState = expected.state
        var actualState = Midend.fromSave(Flip, SgtpSave.parse(expected.state.state))
        expectedState = oracle.undo(expectedState.state)
        assertTrue(actualState.undo())
        assertSame("undo the completing move", expectedState, actualState)
        assertEquals("Moves: 3", actualState.statusText())
        assertEquals(0, actualState.status())

        expectedState = oracle.redo(expectedState.state)
        assertTrue(actualState.redo())
        assertSame("redo the completing move", expectedState, actualState)
        assertEquals("COMPLETED! Moves: 4", actualState.statusText())
        assertEquals(1, actualState.status())
        assertTrue("redo raises the completion event", actualState.takeCompletedEvent())

        // A click after completion changes the lights but not the count.
        val before = actualState.textFormat()
        val afterOracle = oracle.pointsSized(
            expectedState.state,
            listOf(OracleClient.Point(24, 24, Buttons.LEFT_BUTTON)),
            192,
            192,
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            actualState.processKey(24, 24, Buttons.LEFT_BUTTON, 48),
        )
        assertSame("click after completion", afterOracle.state, actualState)
        assertEquals("COMPLETED! Moves: 4", actualState.statusText())
        assertTrue(before != actualState.textFormat())
        assertFalse("a click after completion raises no event", actualState.takeCompletedEvent())
    }

    @Test
    fun `the solve path is a hint mask and the player finishes it`() =
        OracleClient().use { oracle ->
            val base = "d074191345d1644c17058,c90"
            val save = saveText("3x3c", base)

            // The solve string is the hint mask, not a completed board.
            val expected = oracle.solve(save)
            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            val textBefore = actual.textFormat()
            assertTrue(actual.solve())
            assertSame("solve", expected, actual)
            assertEquals(listOf("S000110011"), actual.serialise().values("SOLVE"))
            assertEquals("2", actual.serialise().value("STATEPOS"))
            assertEquals(0, actual.status())
            assertEquals(textBefore, actual.textFormat())
            assertEquals("Auto-solver used. Moves: 0", actual.statusText())
            assertEquals("Auto-solver used. Moves: 0", traceStatus(oracle, expected.state))
            assertEquals(0, expected.status)

            // The hint bits are set and the moved flag is not.
            val hinted = actual.state as Flip.State
            assertTrue(hinted.cheated)
            assertTrue(hinted.hintsActive)
            assertEquals(0, hinted.moves)
            assertEquals(
                listOf(3, 4, 7, 8),
                (0 until 9).filter { hinted.grid[it].toInt() and 2 != 0 },
            )

            // The player clicks the hinted cells. The status text changes
            // to the auto-solved form and the game completes.
            val points = listOf(3, 4, 7, 8).map {
                OracleClient.Point((it % 3) * 48 + 24, (it / 3) * 48 + 24, Buttons.LEFT_BUTTON)
            }
            val played = oracle.pointsSized(expected.state, points, 192, 192)
            for (point in points) {
                assertEquals(
                    "hint click at ${point.x},${point.y}",
                    KeyOutcome.SomeEffect,
                    actual.processKey(point.x, point.y, point.button, 48),
                )
            }
            assertSame("hint clicks", played.state, actual)
            assertEquals(1, actual.status())
            assertEquals("Auto-solved. Moves: 4", actual.statusText())
            assertEquals("Auto-solved. Moves: 4", traceStatus(oracle, played.state.state))
            // A cheated completion raises no completion event.
            assertFalse(actual.takeCompletedEvent())

            // A second solve on the completed cheated board adds another
            // SOLVE record and keeps status 1.
            val secondExpected = oracle.solve(played.state.state)
            assertTrue(actual.solve())
            assertSame("second solve", secondExpected, actual)
            assertEquals(1, actual.status())
            assertEquals("Auto-solved. Moves: 4", actual.statusText())

            // Solve on an honestly completed board also returns the empty
            // hint mask and keeps the completion.
            val completedSave = saveText(
                "3x3c",
                base,
                listOf("M0,1", "M1,1", "M1,2", "M2,2"),
            )
            val solvedCompleted = Midend.fromSave(Flip, SgtpSave.parse(completedSave))
            val expectedCompleted = oracle.solve(completedSave)
            assertTrue(solvedCompleted.solve())
            assertSame("solve a completed board", expectedCompleted, solvedCompleted)
            assertEquals(1, solvedCompleted.status())
            assertEquals("Auto-solved. Moves: 4", solvedCompleted.statusText())
            assertEquals(
                listOf("S000000000"),
                solvedCompleted.serialise().values("SOLVE"),
            )
            assertTrue((solvedCompleted.state as Flip.State).hintsActive)
            assertTrue(solvedCompleted.takeCompletedEvent().not())
        }

    @Test
    fun `an insoluble hand-written position cannot be solved`() =
        OracleClient().use { oracle ->
            val save = saveText("3x3c", "000000000000000000000,008")
            val error = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("No solution exists for this position", error)

            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            assertNull(Flip.solve(actual.state))
            assertFalse(actual.solve())
            assertEquals(0, actual.status())
        }

    @Test
    fun `a click changes the board and the hint path does not complete`() =
        OracleClient().use { oracle ->
            val base = "d074191345d1644c17058,c90"
            val save = saveText("3x3c", base)

            val expected = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(24, 24, Buttons.LEFT_BUTTON)),
                192,
                192,
            )
            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            val beforeText = actual.textFormat()
            val beforeMoves = (actual.state as Flip.State).moves
            actual.processKey(24, 24, Buttons.LEFT_BUTTON, 48)
            assertSame("one click", expected.state, actual)
            assertTrue("the click changes the board", beforeText != actual.textFormat())
            assertEquals(beforeMoves + 1, (actual.state as Flip.State).moves)
            assertEquals(listOf("M0,0"), actual.serialise().values("MOVE"))

            val solved = Midend.fromSave(Flip, SgtpSave.parse(save))
            assertTrue(solved.solve())
            val state = solved.state as Flip.State
            assertTrue("the hint path sets used_solve", state.cheated)
            assertTrue("the hint path sets the hints", state.hintsActive)
            assertEquals("the hint path does not complete", 0, solved.status())
            assertFalse(state.completed)
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

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
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
}
