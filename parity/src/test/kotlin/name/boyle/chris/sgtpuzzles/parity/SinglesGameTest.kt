package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Singles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: singles.
 *
 * The new game, the text form, the move grammar, both cycles, the
 * cursor and the selects, inert input, undo and redo, completion, the
 * error marks, the solver, `changedState`, and the vacuity guards.
 */
class SinglesGameTest {

    private fun handSave(
        desc: String,
        params: String = "2x2de",
        history: List<Pair<String, String>> = emptyList(),
        statePos: Int = history.size + 1,
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection"
        records += "VERSION" to "1"
        records += "GAME" to "Singles"
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "SEED" to "1"
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to statePos.toString()
        records += history
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun midend(save: String): Midend = Midend.fromSave(Singles, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, midend: Midend) {
        assertEquals("$what save", expected.state, midend.serialise().serialise())
        assertEquals("$what id", expected.id, midend.id())
        assertEquals("$what status", expected.status, midend.status())
        assertEquals("$what undo", expected.canUndo, midend.canUndo)
        assertEquals("$what redo", expected.canRedo, midend.canRedo)
    }

    private fun center(x: Int, y: Int, tile: Int): Pair<Int, Int> {
        val border = tile / 2
        return Pair(
            border + x * tile + tile / 2,
            border + y * tile + tile / 2,
        )
    }

    private fun click(x: Int, y: Int, tile: Int, button: Int = Buttons.LEFT_BUTTON):
        List<OracleClient.Point> {
        val (px, py) = center(x, y, tile)
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            Buttons.MIDDLE_BUTTON -> Buttons.MIDDLE_RELEASE
            else -> button
        }
        return listOf(
            OracleClient.Point(px, py, button),
            OracleClient.Point(px, py, release),
        )
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tile: Int,
    ): List<KeyOutcome> = points.map { point ->
        midend.processKey(point.x, point.y, point.button, tile)
    }

    private data class CycleStep(
        val token: String,
        val x: Int,
        val y: Int,
        val button: Int,
    )

    private fun cyclePoints(step: CycleStep, tile: Int): List<OracleClient.Point> {
        val (px, py) = center(step.x, step.y, tile)
        val release = when (step.button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> step.button
        }
        return listOf(
            OracleClient.Point(px, py, step.button),
            OracleClient.Point(px, py, release),
        )
    }

    @Test(timeout = 300_000)
    fun `the new game, status, text and keys are the C values`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("singles").map { it.params } + "") {
                val expected = oracle.newGame("singles", params, SEED)
                val midend = Midend.newGame(Singles, params, SEED)
                assertSame("'$params'", expected, midend)
                assertEquals("'$params' text", oracle.text(expected.state), midend.textFormat())
                assertEquals("'$params' status text", "", midend.statusText())
                assertFalse("'$params' wants no status bar", Singles.wantsStatusBar)
                assertTrue("'$params' can solve", Singles.canSolve)
                assertTrue("'$params' can format as text", Singles.canFormatAsText)
                assertNull("'$params' has no cursor", midend.cursorLocation(32))
                assertNull("'$params' has no AUXINFO", midend.auxInfo)

                val expectedKeys = oracle.requestKeys(expected.state)
                val actualKeys = Singles.requestKeys(Singles.decodeParams(params), false)
                assertEquals("'$params' arrow mode", 3, expectedKeys.arrowMode)
                assertEquals("'$params' labels", expectedKeys.labels.size, actualKeys.labels.size)
                assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actualKeys.arrowMode)
                assertEquals(
                    "'$params' full cursor is ignored",
                    actualKeys,
                    Singles.requestKeys(Singles.decodeParams(params), true),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the text form is the C text`() = OracleClient().use { oracle ->
        val cases = listOf(
            "2111" to emptyList<Pair<String, String>>(),
            "1121" to listOf("MOVE" to "C0,0", "MOVE" to "C1,0"),
            "2111" to listOf("MOVE" to "B0,0"),
            "2111" to listOf("MOVE" to "C0,0"),
            "2111" to listOf("MOVE" to "B0,0", "MOVE" to "C0,1"),
        )
        for ((desc, history) in cases) {
            val save = handSave(desc, history = history)
            val expected = oracle.status(save)
            val midend = midend(save)
            assertEquals("text for $history", oracle.text(save), midend.textFormat())
            assertSame("state for $history", expected, midend)
        }

        assertEquals(
            "2 1\n   \n1 1\n   \n",
            Singles.textFormat(Singles.newState(Singles.Params(2, 2, 0), "2111")),
        )
        val marked = Singles.newState(Singles.Params(2, 2, 0), "1121")
        val circled = Singles.executeMove(marked, "C0,0;C1,0")!!
        assertEquals("1 1\n~ ~\n2 1\n   \n", Singles.textFormat(circled))
        val black = Singles.executeMove(marked, "B0,0")!!
        assertEquals("* 1\n   \n2 1\n   \n", Singles.textFormat(black))
    }

    @Test(timeout = 300_000)
    fun `the move grammar accepts and rejects the C strings`() =
        OracleClient().use { oracle ->
            val accepted = listOf(
                "B0,0", "B0,0;", "B0,0;C1,1", "", "S", "S;B0,0",
                "B0,0;S", "B+0,0", "B 0,0", "B0, 0", "E1,1",
                "C1,1;E0,0;B0,1",
            )
            val rejected = listOf(
                "B0,0;;", "X0,0", "B9,9", "B-1,0", "B 0 , 0", "B0,0  ",
                "B0", "B0,", "B0,0x", "B0,0;C1", "B99999999999999999999,0",
                ";", "B0,0;X", "BB0,0",
            )

            for (move in accepted) {
                val save = handSave("2111", history = listOf("MOVE" to move))
                val expected = oracle.status(save)
                val midend = midend(save)
                assertSame("accepted '$move'", expected, midend)
                assertEquals(
                    "accepted '$move' text",
                    oracle.text(save),
                    midend.textFormat(),
                )
            }

            for (move in rejected) {
                val save = handSave("2111", history = listOf("MOVE" to move))
                val oracleRejected = try {
                    oracle.status(save)
                    false
                } catch (e: OracleException) {
                    true
                }
                assertTrue("C rejects '$move'", oracleRejected)
                val kotlinRejected = try {
                    midend(save)
                    false
                } catch (e: RuntimeException) {
                    true
                }
                assertTrue("Kotlin rejects '$move'", kotlinRejected)
            }
        }

    @Test(timeout = 600_000)
    fun `real left and right cycles match the oracle at every tile`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val steps = listOf(
                CycleStep("B2,2", 2, 2, Buttons.LEFT_BUTTON),
                CycleStep("E2,2", 2, 2, Buttons.RIGHT_BUTTON),
                CycleStep("B2,2", 2, 2, Buttons.LEFT_BUTTON),
                CycleStep("C2,2", 2, 2, Buttons.LEFT_BUTTON),
                CycleStep("B2,2", 2, 2, Buttons.RIGHT_BUTTON),
                CycleStep("E2,2", 2, 2, Buttons.RIGHT_BUTTON),
                CycleStep("C2,2", 2, 2, Buttons.RIGHT_BUTTON),
                CycleStep("E2,2", 2, 2, Buttons.LEFT_BUTTON),
            )
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Singles.computeSize(Singles.decodeParams("5x5de"), tile).width
                val midend = Midend.newGame(Singles, "5x5de", SEED)
                val cumulative = ArrayList<OracleClient.Point>()
                for ((index, step) in steps.withIndex()) {
                    val points = cyclePoints(step, tile)
                    cumulative += points
                    val expected = oracle.pointsSized(save, cumulative, board, board)
                    val outcomes = apply(midend, points, tile)
                    assertSame("tile $tile step $index", expected.state, midend)
                    assertEquals("tile $tile step $index press", KeyOutcome.SomeEffect, outcomes.first())
                    assertEquals("tile $tile step $index release", KeyOutcome.Unused, outcomes.last())
                    assertEquals(
                        "tile $tile step $index token",
                        step.token,
                        midend.serialise().values("MOVE").last(),
                    )
                    assertEquals(
                        "tile $tile step $index states",
                        index + 2,
                        midend.stateCount,
                    )
                }
            }
        }

    @Test(timeout = 300_000)
    fun `the cursor wraps and both selects follow the C path`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val sequences = listOf(
                listOf(Buttons.CURSOR_UP),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT2),
                listOf('\n'.code),
                listOf(' '.code),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val midend = Midend.newGame(Singles, "5x5de", SEED)
                for (key in sequence) midend.processKey(0, 0, key, 32)
                assertSame("sequence $sequence", expected, midend)
            }

            // A modified cursor key is unused, and the select then acts
            // on the unmoved cursor.
            val modified = listOf(Buttons.MOD_SHFT or Buttons.CURSOR_UP)
            val m = Midend.newGame(Singles, "5x5de", SEED)
            for (key in modified) {
                assertEquals(KeyOutcome.Unused, m.processKey(0, 0, key, 32))
            }
            assertEquals(1, m.stateCount)
            val ctrl = Midend.newGame(Singles, "5x5de", SEED)
            assertEquals(
                KeyOutcome.Unused,
                ctrl.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_UP, 32),
            )
            assertEquals(1, ctrl.stateCount)

            val shiftThenSelect = listOf(
                Buttons.MOD_SHFT or Buttons.CURSOR_UP,
                Buttons.CURSOR_SELECT,
            )
            val shiftExpected = oracle.keys(save, shiftThenSelect)
            val shiftMidend = Midend.newGame(Singles, "5x5de", SEED)
            for (key in shiftThenSelect) shiftMidend.processKey(0, 0, key, 32)
            assertSame("shift then select", shiftExpected, shiftMidend)
            assertEquals("B0,0", shiftMidend.serialise().values("MOVE").last())

            val upThenShift = listOf(
                Buttons.CURSOR_UP,
                Buttons.MOD_SHFT or Buttons.CURSOR_UP,
                Buttons.CURSOR_SELECT,
            )
            val upExpected = oracle.keys(save, upThenShift)
            val upMidend = Midend.newGame(Singles, "5x5de", SEED)
            for (key in upThenShift) upMidend.processKey(0, 0, key, 32)
            assertSame("up, shift up, select", upExpected, upMidend)
            assertEquals("B0,4", upMidend.serialise().values("MOVE").last())
        }

    @Test(timeout = 300_000)
    fun `a middle press, a drag, and a release are inert`() = OracleClient().use { oracle ->
        val save = oracle.newGame("singles", "5x5de", SEED).state
        val board = Singles.computeSize(Singles.decodeParams("5x5de"), 48).width

        val middle = click(1, 2, 48, Buttons.MIDDLE_BUTTON)
        val expectedMiddle = oracle.pointsSized(save, middle, board, board)
        val m = Midend.newGame(Singles, "5x5de", SEED)
        val middleOutcomes = apply(m, middle, 48)
        assertSame("middle", expectedMiddle.state, m)
        assertEquals("the middle press is unused", KeyOutcome.Unused, middleOutcomes.first())
        assertEquals("the middle release is unused", KeyOutcome.Unused, middleOutcomes.last())
        assertEquals("the middle click adds no state", 1, m.stateCount)

        // The positive control: a left press on the same cell moves.
        val left = click(1, 2, 48)
        val expectedLeft = oracle.pointsSized(save, left, board, board)
        val l = Midend.newGame(Singles, "5x5de", SEED)
        val leftOutcomes = apply(l, left, 48)
        assertSame("left", expectedLeft.state, l)
        assertEquals("the left press is a move", KeyOutcome.SomeEffect, leftOutcomes.first())
        assertEquals("the left press adds one state", 2, l.stateCount)
        assertEquals("B1,2", l.serialise().values("MOVE").last())

        // A drag and a release after the press add nothing.
        val (px, py) = center(1, 2, 48)
        val (qx, qy) = center(3, 3, 48)
        val drag = listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px + 1, py + 1, Buttons.LEFT_DRAG),
            OracleClient.Point(qx, qy, Buttons.LEFT_DRAG),
            OracleClient.Point(qx, qy, Buttons.LEFT_RELEASE),
        )
        val expectedDrag = oracle.pointsSized(save, drag, board, board)
        val d = Midend.newGame(Singles, "5x5de", SEED)
        val dragOutcomes = apply(d, drag, 48)
        assertSame("drag", expectedDrag.state, d)
        assertEquals("the press is the only move", KeyOutcome.SomeEffect, dragOutcomes.first())
        assertTrue(
            "the drags and the release are unused",
            dragOutcomes.drop(1).all { it == KeyOutcome.Unused },
        )
        assertEquals("the drag adds one state", 2, d.stateCount)

        // The shell gesture form is the same sequence.
        val gesture = oracle.gestureSized(save, px, py, qx, qy, 4, board, board)
        val g = Midend.newGame(Singles, "5x5de", SEED)
        apply(g, drag, 48)
        assertSame("gesture", gesture, g)
    }

    @Test(timeout = 300_000)
    fun `undo, redo, and the redo branch match the oracle`() =
        OracleClient().use { oracle ->
            val actualSave = handSave(
                "1234512345123451234512345",
                params = "5x5de",
                history = listOf("MOVE" to "B2,2", "MOVE" to "C2,2"),
            )
            val expected = oracle.status(actualSave)
            val midend = midend(actualSave)
            assertSame("loaded", expected, midend)
            assertTrue(midend.canUndo)
            assertFalse(midend.canRedo)

            val undo1 = oracle.undo(actualSave)
            assertTrue(midend.undo())
            assertSame("undo once", undo1, midend)
            assertTrue(midend.canRedo)

            val undo2 = oracle.undo(undo1.state)
            assertTrue(midend.undo())
            assertSame("undo twice", undo2, midend)
            assertFalse(midend.canUndo)

            val redo1 = oracle.redo(undo2.state)
            assertTrue(midend.redo())
            assertSame("redo once", redo1, midend)

            val redo2 = oracle.redo(redo1.state)
            assertTrue(midend.redo())
            assertSame("redo twice", redo2, midend)
            assertFalse(midend.canRedo)

            // A move after an undo drops the redo branch.
            assertTrue(midend.undo())
            val clicked = click(0, 0, 32)
            apply(midend, clicked, 32)
            assertFalse(midend.canRedo)
            assertEquals(3, midend.stateCount)
            val dropped = midend.serialise()
            assertEquals(
                "the dropped branch save loads in C",
                midend.id(),
                oracle.status(dropped.serialise()).id,
            )

            // The empty move is accepted and still replayed from a
            // save; it adds a state with the recomputed errors.
            val emptyMove = handSave(
                "1221",
                history = listOf("MOVE" to ""),
            )
            val emptyExpected = oracle.status(emptyMove)
            val empty = midend(emptyMove)
            assertSame("empty move", emptyExpected, empty)
            assertEquals(1, empty.status())
            assertEquals(2, empty.stateCount)
        }

    @Test(timeout = 300_000)
    fun `restart appends a RESTART record`() = OracleClient().use { oracle ->
        val save = handSave(
            "1234512345123451234512345",
            params = "5x5de",
            history = listOf("MOVE" to "B2,2"),
        )
        val midend = midend(save)
        val before = midend.serialise().serialise()

        assertTrue(midend.restart())
        assertEquals(3, midend.stateCount)
        assertEquals("RESTART", midend.serialise().records.last().key)
        assertEquals(0, midend.status())
        val restarted = midend.serialise()
        assertEquals("the restart save loads in C", midend.id(), oracle.status(restarted.serialise()).id)
        assertEquals(oracle.text(restarted.serialise()), midend.textFormat())
        assertNotEquals(before, restarted.serialise())

        // At the first state there is nothing to restart.
        val fresh = Midend.newGame(Singles, "5x5de", SEED)
        assertFalse(fresh.restart())
        assertEquals(1, fresh.stateCount)
    }

    @Test(timeout = 300_000)
    fun `completion is sticky and the error marks follow the C rules`() =
        OracleClient().use { oracle ->
            // A generated board starts unmarked, so even a complete
            // Latin description is only completed by a move.
            val latin = handSave("1221")
            assertEquals(0, midend(latin).status())
            val empty = handSave("1221", history = listOf("MOVE" to ""))
            val emptyExpected = oracle.status(empty)
            val emptyMidend = midend(empty)
            assertSame("empty move on a Latin square", emptyExpected, emptyMidend)
            assertEquals(1, emptyMidend.status())

            // 1121 with two circles on the same number: both are red.
            val errorSave = handSave(
                "1121",
                history = listOf("MOVE" to "C0,0", "MOVE" to "C1,0"),
            )
            val errorExpected = oracle.status(errorSave)
            val error = midend(errorSave)
            assertSame("error pair", errorExpected, error)
            val errorState = error.state as Singles.State
            assertTrue("0,0 is marked", errorState.flags[0] and Singles.F_ERROR != 0)
            assertTrue("1,0 is marked", errorState.flags[1] and Singles.F_ERROR != 0)
            assertEquals(
                "the error pair text",
                oracle.text(errorSave),
                error.textFormat(),
            )

            // A black group of two is an error and completes nothing.
            val groupSave = handSave(
                "1121",
                history = listOf("MOVE" to "B0,0", "MOVE" to "B1,0"),
            )
            val groupExpected = oracle.status(groupSave)
            val group = midend(groupSave)
            assertSame("black group", groupExpected, group)
            val groupState = group.state as Singles.State
            assertTrue(groupState.flags[0] and Singles.F_ERROR != 0)
            assertTrue(groupState.flags[1] and Singles.F_ERROR != 0)
            assertEquals(0, group.status())

            // Two white regions: a black row in the middle separates
            // the top and the bottom. A black group is also an error,
            // so the board is incomplete for two reasons.
            val splitSave = handSave(
                "123231312",
                params = "3x3de",
                history = listOf("MOVE" to "B0,1;B1,1;B2,1"),
            )
            val splitExpected = oracle.status(splitSave)
            val split = midend(splitSave)
            assertSame("split board", splitExpected, split)
            assertEquals(0, split.status())
            val splitState = split.state as Singles.State
            assertTrue("the lower region is marked", splitState.flags[6] and Singles.F_ERROR != 0)
            assertTrue("the black group is marked", splitState.flags[3] and Singles.F_ERROR != 0)

            // The completion through real input, and the sticky bit.
            val start = handSave(
                "2111",
                history = listOf("MOVE" to "C0,0", "MOVE" to "C0,1", "MOVE" to "C1,0"),
            )
            val complete = midend(start)
            val ui = complete.ui as Singles.Ui
            Singles.setCursorVisible(ui, true)
            val finish = click(1, 1, 32)
            val finishOutcomes = apply(complete, finish, 32)
            assertEquals(KeyOutcome.SomeEffect, finishOutcomes.first())
            assertEquals(1, complete.status())
            assertTrue("the win event is raised", complete.takeCompletedEvent())
            assertFalse("the cursor hides", ui.cursorVisible)
            val completedText = complete.textFormat()

            // A later wrong move keeps status 1 and raises no event.
            val clear = click(0, 0, 32)
            apply(complete, clear, 32)
            assertEquals(1, complete.status())
            assertFalse("a sticky win raises no second event", complete.takeCompletedEvent())
            assertNotEquals("the board changed", completedText, complete.textFormat())
        }

    @Test(timeout = 600_000)
    fun `the solve move matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("singles").map { it.params } + "") {
                val expected = oracle.newGame("singles", params, SEED)
                val solved = oracle.solve(expected.state)
                val expectedSolve = SgtpSave.parse(solved.state).value("SOLVE")
                assertNotNull("'$params' has a solution", expectedSolve)
                assertTrue("'$params' starts with S;", expectedSolve!!.startsWith("S;"))
                assertTrue("'$params' ends with ;", expectedSolve.endsWith(";"))

                val midend = Midend.newGame(Singles, params, SEED)
                assertTrue("'$params' solves", midend.solve())
                assertEquals(
                    "'$params' SOLVE",
                    expectedSolve,
                    midend.serialise().value("SOLVE"),
                )
                assertEquals("'$params' solved save", solved.state, midend.serialise().serialise())
                assertEquals("'$params' status", 1, midend.status())
                assertTrue("'$params' can undo the solve", midend.canUndo)
                assertFalse("'$params' can redo after the solve", midend.canRedo)
                assertFalse("a solve is not a win event", midend.takeCompletedEvent())
                assertTrue(
                    "'$params' sets used_solve",
                    (midend.state as Singles.State).usedSolve,
                )
            }

            // A clean 2x2 and a 3x3 pin the column-major diff.
            assertEquals(
                "S;C0,0;C0,1;C1,0;B1,1;",
                SgtpSave.parse(oracle.solve(handSave("2111")).state).value("SOLVE"),
            )
            assertEquals(
                "S;C0,0;B0,1;C0,2;C1,0;C1,1;C1,2;C2,0;B2,1;C2,2;",
                SgtpSave.parse(oracle.solve(handSave("231222312", params = "3x3de")).state)
                    .value("SOLVE"),
            )
        }

    @Test(timeout = 300_000)
    fun `the solve falls back to the initial state after a wrong mark`() =
        OracleClient().use { oracle ->
            // C1,1 circles the cell that must be black, so the current
            // state is insoluble; C then tries the state a save starts
            // with. The seam passes one state, so the port rebuilds it
            // from the unchanged numbers.
            val wrong = handSave("2111", history = listOf("MOVE" to "C1,1"))
            val expected = oracle.solve(wrong)
            val expectedSolve = SgtpSave.parse(expected.state).value("SOLVE")
            val midend = midend(wrong)
            assertTrue(midend.solve())
            assertEquals("fallback SOLVE", expectedSolve, midend.serialise().value("SOLVE"))
            assertEquals("fallback save", expected.state, midend.serialise().serialise())
            assertEquals("S;C0,0;C0,1;C1,0;B1,1;", expectedSolve)

            // 1111 has no solution.
            val bad = handSave("1111")
            val oracleError = try {
                oracle.solve(bad)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("Unable to solve puzzle.", oracleError)
            val badMidend = midend(bad)
            assertNull(Singles.solve(badMidend.state))
            assertFalse(badMidend.solve())
            assertEquals(1, badMidend.stateCount)
        }

    @Test(timeout = 300_000)
    fun `changedState follows the C condition`() {
        val initial = Singles.newState(Singles.Params(2, 2, 0), "2111") as Singles.State
        val complete = initial.copy() as Singles.State
        complete.completed = true
        val ui = Singles.newUi(initial) as Singles.Ui
        Singles.setCursorVisible(ui, true)

        assertTrue("a normal completion", Singles.changedState(ui, initial, complete))
        assertFalse("the cursor hides", ui.cursorVisible)

        Singles.setCursorVisible(ui, true)
        val solved = complete.copy() as Singles.State
        solved.usedSolve = true
        assertFalse("a solve is not a win event", Singles.changedState(ui, initial, solved))
        assertFalse("the solve still hides the cursor", ui.cursorVisible)

        val oldCompleted = complete.copy() as Singles.State
        assertFalse(
            "an old completed state is not a new win",
            Singles.changedState(ui, oldCompleted, complete),
        )
        assertFalse(
            "a null old state is not a win",
            Singles.changedState(ui, null, complete),
        )
    }

    @Test(timeout = 300_000)
    fun `currentKeyLabel, cursorLocation and the preference are the C values`() {
        val save = handSave(
            "2111",
            history = listOf("MOVE" to "B0,0"),
        )
        val midend = midend(save)
        val ui = midend.ui as Singles.Ui

        assertEquals("", Singles.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("", Singles.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))
        assertEquals("", Singles.currentKeyLabel(ui, midend.state, 'x'.code))
        assertNull(midend.cursorLocation(32))

        Singles.setCursorVisible(ui, true)
        assertEquals("Restore", Singles.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Restore", Singles.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))
        assertEquals(
            "",
            Singles.currentKeyLabel(
                ui,
                midend.state,
                Buttons.MOD_SHFT or Buttons.CURSOR_SELECT,
            ),
        )
        val at = midend.cursorLocation(32)
        assertNotNull(at)
        assertEquals(16, at!!.left)
        assertEquals(16, at.top)
        assertEquals(32, at.width)
        assertEquals(32, at.height)

        val circle = Singles.executeMove(midend.state, "C0,0")!!
        assertEquals("Remove", Singles.currentKeyLabel(ui, circle, Buttons.CURSOR_SELECT))
        assertEquals("Remove", Singles.currentKeyLabel(ui, circle, Buttons.CURSOR_SELECT2))

        val empty = Singles.executeMove(midend.state, "E0,0")!!
        assertEquals("Black", Singles.currentKeyLabel(ui, empty, Buttons.CURSOR_SELECT))
        assertEquals("Circle", Singles.currentKeyLabel(ui, empty, Buttons.CURSOR_SELECT2))

        // The preference lives on the UI with the C default, is not in
        // the drawstate, and is not serialised.
        val fresh = Midend.newGame(Singles, "5x5de", SEED)
        assertFalse((fresh.ui as Singles.Ui).showBlackNums)
        (fresh.ui as Singles.Ui).showBlackNums = true
        assertEquals(
            "the preference is not in the save",
            false,
            fresh.serialise().records.any { it.key == "UI" },
        )
        assertEquals(
            "the preference is not in the save text",
            false,
            fresh.serialise().serialise().contains("show-black-nums"),
        )
    }

    @Test(timeout = 300_000)
    fun `a real black or circle change alters the board and adds a move`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("singles", "5x5de", SEED).state
            val midend = Midend.newGame(Singles, "5x5de", SEED)
            val before = midend.textFormat()

            val left = click(0, 0, 48)
            apply(midend, left, 48)
            val afterLeft = midend.textFormat()
            assertNotEquals("a black change alters the board", before, afterLeft)
            assertEquals(2, midend.stateCount)
            assertEquals("B0,0", midend.serialise().values("MOVE").last())
            val stateAfterLeft = midend.state as Singles.State
            assertTrue(stateAfterLeft.flags[0] and Singles.F_BLACK != 0)
            assertEquals(0, midend.status())

            val right = click(1, 0, 48, Buttons.RIGHT_BUTTON)
            apply(midend, right, 48)
            val afterRight = midend.textFormat()
            assertNotEquals("a circle change alters the board", afterLeft, afterRight)
            assertEquals(3, midend.stateCount)
            assertEquals("C1,0", midend.serialise().values("MOVE").last())
            val stateAfterRight = midend.state as Singles.State
            assertTrue(stateAfterRight.flags[1] and Singles.F_CIRCLE != 0)

            // The inert middle press changes neither the board nor the
            // move count.
            val middle = click(2, 2, 48, Buttons.MIDDLE_BUTTON)
            val countBefore = midend.stateCount
            val textBefore = midend.textFormat()
            apply(midend, middle, 48)
            assertEquals("an inert click adds no move", countBefore, midend.stateCount)
            assertEquals("an inert click changes no board", textBefore, midend.textFormat())
        }

    private companion object {
        const val SEED = "12345"
    }
}
