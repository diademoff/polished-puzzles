package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: filling.
 *
 * New games, text, status, the move grammar, the solver, completion,
 * undo and redo, and the real pointer path are compared with the
 * oracle.
 */
class FillingGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        val paramsList = listOf("1x1", "1x2", "2x2", "9x7", "13x9", "2x3", "3x2")
        for (params in paramsList) {
            for (seed in listOf("12345", "1", "seed")) {
                val expected = oracle.newGame("filling", params, seed)
                val actual = Midend.newGame(Filling, params, seed)
                assertSame("filling '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text filling '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals("status text '$params'", "", actual.statusText())
                assertEquals(
                    "a status bar must not be drawn '$params'",
                    null,
                    statusLine(oracle, expected.state),
                )
            }
        }

        // The reference board from the source comment.
        val reference = "6002002030603030000010230420200000305010404003003"
        val state = Filling.newState(Filling.decodeParams("7x7"), reference)
        assertEquals(
            "+---+---+---+---+---+---+---+\n" +
                "| 6 |   |   | 2 |   |   | 2 |\n" +
                "+---+---+---+---+---+---+---+\n" +
                "|   | 3 |   | 6 |   | 3 |   |\n" +
                "+---+---+---+---+---+---+---+\n" +
                "| 3 |   |   |   |   |   | 1 |\n" +
                "+---+---+---+---+---+---+---+\n" +
                "|   | 2 | 3 |   | 4 | 2 |   |\n" +
                "+---+---+---+---+---+---+---+\n" +
                "| 2 |   |   |   |   |   | 3 |\n" +
                "+---+---+---+---+---+---+---+\n" +
                "|   | 5 |   | 1 |   | 4 |   |\n" +
                "+---+---+---+---+---+---+---+\n" +
                "| 4 |   |   | 3 |   |   | 3 |\n" +
                "+---+---+---+---+---+---+---+\n",
            Filling.textFormat(state),
        )

        // A clue above 9 is a valid description and the text keeps the
        // character C writes.
        assertEquals(
            "+---+---+---+---+---+---+---+---+---+---+\n" +
                "| : |   |   |   |   |   |   |   |   |   |\n" +
                "+---+---+---+---+---+---+---+---+---+---+\n",
            Filling.textFormat(Filling.newState(Filling.decodeParams("10x1"), ":i")),
        )
        assertEquals(
            '?',
            Filling.textFormat(Filling.newState(Filling.decodeParams("17x13"), "?zzzzzzzzl"))
                .lines()[1][2],
        )
    }

    @Test
    fun `requested keys match the oracle`() = OracleClient().use { oracle ->
        // `filling.c:1302-1325` returns the same eleven buttons for
        // every preset and does not read the parameters.
        for (params in listOf("9x7", "13x9", "17x13")) {
            val game = oracle.newGame("filling", params, SEED)
            val expected = oracle.requestKeys(game.state)
            val actual = Filling.requestKeys(Filling.decodeParams(params))

            assertEquals("arrow mode for '$params'", 3, expected.arrowMode)
            assertEquals(
                "Kotlin arrow mode for '$params'",
                ArrowMode.ARROWS_LEFT_RIGHT,
                actual.arrowMode,
            )
            assertEquals("key count for '$params'", 11, actual.labels.size)
            assertEquals(
                "key labels for '$params'",
                expected.labels.map { it.label },
                actual.labels.map { it.name },
            )
            assertEquals(
                "key buttons for '$params'",
                expected.labels.map { it.button },
                actual.labels.map { it.button },
            )
            assertEquals(
                "needs arrows for '$params'",
                expected.labels.map { it.needsArrows },
                actual.labels.map { it.needsArrows },
            )
            assertEquals(
                "no key needs arrows for '$params'",
                List(11) { false },
                actual.labels.map { it.needsArrows },
            )
            assertEquals(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "Clear"),
                actual.labels.map { it.name },
            )
            assertEquals(
                listOf(49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 8),
                actual.labels.map { it.button },
            )
        }
    }

    @Test
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val state = Filling.newState(Filling.decodeParams("9x7"), DESC_9X7)
        val accepted = listOf(
            "3_5",
            "3_05",
            "3_010",
            "3_0x9",
            "1,2_0x5",
            "62_9",
            "0_0",
            // C assigns the `long` to an `int`, so these wrap to 0.
            "3_4294967296",
            "4294967296_5",
            "3_0x100000000",
            "3_-99999999999999999999",
            // `strtol` skips white space and a sign.
            "0_ 5",
            "0_+5",
        )
        for (move in accepted) {
            val expected = oracle.id(saveText("9x7", DESC_9X7, listOf(move)))
            val actual = Filling.executeMove(state, move)
            assertTrue("'$move' must be accepted", actual != null)
            assertEquals("'$move' text", oracle.text(expected.state),
                Filling.textFormat(actual!!))
        }

        val rejected = listOf(
            "3_10",
            "63_9",
            "1,2_5x",
            "1,2,_5",
            "_5",
            "",
            "0",
            "0,_5",
            ",0_5",
            "0 1_5",
            "0_5_7",
            "3_99999999999999999999",
            "3_-1",
            "3_0x",
            "s",
            "s22",
        )
        for (move in rejected) {
            val oracleError = try {
                oracle.id(saveText("9x7", DESC_9X7, listOf(move)))
                null
            } catch (e: OracleException) {
                e.message
            }
            assertTrue("'$move' must be rejected by C", oracleError != null)
            assertNull("'$move' must be rejected", Filling.executeMove(state, move))
        }

        // `_010` is octal 8 and `_0x9` is hexadecimal 9.
        assertEquals(8, (Filling.executeMove(state, "3_010") as Filling.State).board[3])
        assertEquals(9, (Filling.executeMove(state, "3_0x9") as Filling.State).board[3])
        assertEquals(0, (Filling.executeMove(state, "3_4294967296") as Filling.State).board[3])
        assertEquals(5, (Filling.executeMove(state, "4294967296_5") as Filling.State).board[0])
        assertEquals(5, (Filling.executeMove(state, "0_ 5") as Filling.State).board[0])

        // Duplicate indices are harmless, and a repeated move adds a
        // state whose board equals the previous one.
        val twice = Filling.executeMove(state, "0_5") as Filling.State
        val again = Filling.executeMove(twice, "0_5") as Filling.State
        assertTrue(twice.board.contentEquals(again.board))
    }

    @Test
    fun `the solve move and the partial solve match the oracle`() = OracleClient().use { oracle ->
        // A full `s` move: exactly one digit per cell, no range check.
        val state = Filling.newState(Filling.decodeParams("2x2"), "3aaa")
        assertNull(Filling.executeMove(state, "sAAA"))
        val solved = Filling.executeMove(state, "sAAA0") as Filling.State
        assertEquals(listOf(17, 17, 17, 0), solved.board.toList())
        assertTrue(solved.cheated)
        val zeroed = Filling.executeMove(state, "s0000") as Filling.State
        assertEquals(listOf(0, 0, 0, 0), zeroed.board.toList())

        // Generated games solve to a full board of length sz + 1.
        for (params in listOf("9x7", "13x9", "17x13")) {
            val generated = oracle.newGame("filling", params, SEED)
            val expected = SgtpSave.parse(oracle.solve(generated.state).state).value("SOLVE")!!
            val midend = Midend.newGame(Filling, params, SEED)
            val sz = (midend.state as Filling.State).board.size
            assertEquals("solve '$params'", expected, Filling.solve(midend.state))
            assertEquals("solve length '$params'", sz + 1, expected.length)
            assertTrue(midend.solve())
            assertEquals(1, midend.status())
            assertTrue((midend.state as Filling.State).cheated)
        }

        // A stalled board: C returns the partial board and sets an
        // error, and the midend ignores the error.
        val stalled = saveText("5x5", "y", emptyList())
        val stalledSolve = SgtpSave.parse(oracle.solve(stalled).state).value("SOLVE")
        assertEquals("s" + "0".repeat(25), stalledSolve)
        val stalledMidend = Midend.fromSave(Filling, SgtpSave.parse(stalled))
        assertEquals(stalledSolve, Filling.solve(stalledMidend.state))
        assertTrue(stalledMidend.solve())
        assertEquals(0, stalledMidend.status())
        assertTrue((stalledMidend.state as Filling.State).cheated)

        // Small deduction cases, each checked against C.
        for ((params, desc) in listOf(
            "2x2" to "22aa",
            "2x2" to "3aaa",
            "1x2" to "2a",
            "1x2" to "22",
            "1x4" to "22a2",
            "3x3" to "333333333",
            "4x4" to "4444444444444444",
        )) {
            val save = saveText(params, desc, emptyList())
            val expected = SgtpSave.parse(oracle.solve(save).state).value("SOLVE")
            assertEquals(
                "solve '$params:$desc'",
                expected,
                Filling.solve(Filling.newState(Filling.decodeParams(params), desc)),
            )
        }

        // An `AUXINFO` solution is returned unchanged, as C does.
        val aux = "s" + "0".repeat(25)
        val auxMidend = Midend.fromSave(
            Filling,
            SgtpSave.parse(saveText("5x5", "y", emptyList(), aux)),
        )
        assertEquals(aux, Filling.solve(auxMidend.state, aux))
    }

    @Test
    fun `completion, undo, and redo match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("1x2", "2a", listOf("1_2"))
        val expected = oracle.id(save)
        val midend = Midend.fromSave(Filling, SgtpSave.parse(save))

        assertSame("completed game", expected, midend)
        assertEquals(1, midend.status())
        assertEquals("1x2:2a", midend.id())
        assertEquals(
            "+---+\n| 2 |\n+---+\n| 2 |\n+---+\n",
            midend.textFormat(),
        )
        assertEquals("", midend.statusText())

        // A fresh game with an already complete description is open,
        // because `new_game` does not compute the flag.
        assertEquals(0, Midend.fromGameId(Filling, "1x2:22").status())

        // Undo leaves the completed state; redo enters it again and
        // raises the completion event a second time, as C does.
        assertFalse(midend.takeCompletedEvent())
        val undone = oracle.undo(expected.state)
        assertTrue(midend.undo())
        assertSame("undo from complete", undone, midend)
        assertEquals(0, midend.status())
        assertFalse("undo leaves the completed state", midend.takeCompletedEvent())

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertSame("redo into complete", redone, midend)
        assertEquals(1, midend.status())
        assertTrue("redo enters the completed state", midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `a real click and a digit make one move on both sides`() =
        OracleClient().use { oracle ->
            val save = saveText("9x7", DESC_9X7, emptyList())
            val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
            val startText = midend.serialise().serialise()
            val startStates = midend.stateCount
            val tile = Filling.preferredTileSize

            val expected = oracle.pointsSized(
                save,
                listOf(
                    OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                    OracleClient.Point(32, 32, '5'.code),
                ),
                recordTrace = true,
            )

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(32, 32, Buttons.LEFT_BUTTON, tile),
            )
            // A click is UI only: no state, no move, no board change.
            assertEquals("a click must not add a state", startStates, midend.stateCount)
            assertEquals("a click must not change the save", startText,
                midend.serialise().serialise())

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(32, 32, '5'.code, tile),
            )
            assertEquals("one fill adds one state", startStates + 1, midend.stateCount)
            assertEquals(
                listOf("0_5"),
                SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
            )
            assertEquals("save", expected.state.state, midend.serialise().serialise())
            assertEquals("id", expected.state.id, midend.id())
            assertEquals("status", expected.state.status, midend.status())
            assertTrace(
                "click and digit drawing",
                expected.trace,
                KotlinTraceSink.stateTrace(midend, tile),
            )

            // A click on a clue cell selects nothing but still allocates
            // an empty selection, so a digit is a UI update, not a
            // no-effect key, and adds no move.
            val clueMidend = Midend.fromSave(Filling, SgtpSave.parse(save))
            val clueText = clueMidend.serialise().serialise()
            val clue = oracle.pointsSized(
                clueText,
                listOf(
                    OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
                    OracleClient.Point(64, 32, '5'.code),
                ),
            )
            assertEquals("C: a clue click adds no move", clueText, clue.state.state)
            assertEquals(
                KeyOutcome.SomeEffect,
                clueMidend.processKey(64, 32, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                clueMidend.processKey(64, 32, '5'.code, tile),
            )
            assertEquals("a clue click adds no state", startStates, clueMidend.stateCount)
            assertEquals("a clue click changes no save", clueText,
                clueMidend.serialise().serialise())
        }

    @Test
    fun `a warm click frame is empty on both sides`() = OracleClient().use { oracle ->
        val save = saveText("9x7", DESC_9X7, emptyList())
        val points = listOf(OracleClient.Point(32, 32, Buttons.LEFT_BUTTON))

        val expected = oracle.traceWarm(save, points)
        val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
        val actual = KotlinTraceSink.warmTrace(midend, points, Filling.preferredTileSize)
        assertTrace("warm click frame", expected.trace, actual)
        assertEquals(
            "a warm click adds no drawing",
            listOf("start_draw", "end_draw"),
            opNames(actual),
        )

        val digitPoints = points + OracleClient.Point(32, 32, '5'.code)
        val digitExpected = oracle.traceWarm(save, digitPoints)
        val digitMidend = Midend.fromSave(Filling, SgtpSave.parse(save))
        val digitActual = KotlinTraceSink.warmTrace(
            digitMidend,
            digitPoints,
            Filling.preferredTileSize,
        )
        assertTrace("warm click and digit frame", digitExpected.trace, digitActual)
        assertEquals(
            "the input already drew the fill",
            listOf("start_draw", "end_draw"),
            opNames(digitActual),
        )
        assertEquals("0_5", digitMidend.serialise().values("MOVE").single())
    }

    @Test
    fun `a fill changes the board and a selection does not`() {
        val midend = Midend.fromGameId(Filling, "9x7:$DESC_9X7")
        val tile = Filling.preferredTileSize
        val before = (midend.state as Filling.State).board.copyOf()
        val states = midend.stateCount

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(32, 32, Buttons.LEFT_BUTTON, tile),
        )
        assertTrue(
            "a click leaves the board alone",
            before.contentEquals((midend.state as Filling.State).board),
        )
        assertEquals(states, midend.stateCount)

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 32, '5'.code, tile))
        assertNotEquals(
            "a fill changes the board",
            0,
            (midend.state as Filling.State).board[0],
        )
        assertEquals(states + 1, midend.stateCount)

        // A digit that matches every selected cell clears the selection
        // and adds no state, but the UI changed.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(32, 32, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 32, '5'.code, tile))
        assertEquals(states + 1, midend.stateCount)

        // A digit with no selection and a hidden cursor does nothing.
        midend.setCursorVisible(false)
        (midend.ui as Filling.Ui).sel = null
        assertEquals(KeyOutcome.NoEffect, midend.processKey(0, 0, '5'.code, tile))
    }

    private fun opNames(trace: JsonValue.Obj): List<String> =
        (trace.field("ops") as JsonValue.Arr).items.map {
            ((it as JsonValue.Obj).fields["op"] as JsonValue.Str).value
        }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        val e = TraceNormalizer.normalize(expected, upstream).lines()
        val a = TraceNormalizer.normalize(actual, upstream).lines()
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

    private fun statusLine(oracle: OracleClient, save: String): String? {
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
        auxInfo: String? = null,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Filling.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
        )
        if (auxInfo != null) records += SaveRecord("AUXINFO", auxInfo)
        records += SaveRecord("NSTATES", (1 + moves.size).toString())
        records += SaveRecord("STATEPOS", (1 + moves.size).toString())
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_9X7 = "a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55"
    }
}
