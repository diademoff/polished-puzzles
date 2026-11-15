package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: mosaic.
 *
 * New games, text, status, the loose move grammar, click cycles, right
 * clicks, drags, completion shapes, undo and redo, and the solver are
 * compared with the oracle.
 *
 * Every click is a press and a release pair. A press without a release
 * makes the midend invent a release for the held button, and mosaic
 * turns that release into an `e` fill, so a list of press-only points is
 * not a list of independent clicks.
 */
class MosaicGameTest {

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("", "3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0")) {
            val expected = oracle.newGame("mosaic", params, SEED)
            val midend = Midend.newGame(Mosaic, params, SEED)
            assertEquals("id '$params'", expected.id, midend.id())
            assertEquals("state '$params'", expected.state, midend.serialise().serialise())
            assertEquals("status '$params'", expected.status, midend.status())
            assertEquals("text '$params'", oracle.text(expected.state), midend.textFormat())

            val state = midend.state as Mosaic.State
            assertEquals("$params starts open", 0, Mosaic.status(state))
            assertFalse("$params has not cheated", state.cheating)
            assertEquals(
                "$params clue count",
                state.shared.shown.count { it },
                state.notCompletedClues,
            )
            assertEquals(
                "$params status text",
                "Clues left: ${state.notCompletedClues}",
                midend.statusText(),
            )

            val save = SgtpSave.parse(expected.state)
            assertNull("$params AUXINFO", save.value("AUXINFO"))
            assertNull("$params UI", save.value("UI"))
        }
    }

    @Test
    fun `text formatting follows the clue board`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mosaic", "3x3", SEED)
        val midend = Midend.newGame(Mosaic, "3x3", SEED)
        assertEquals("|4|| || |\n|4||7|| |\n| || || |\n", midend.textFormat())
        assertEquals("fresh text", oracle.text(start.state), midend.textFormat())

        // A played board has the same text: it uses the clues, not the
        // player's marks.
        click(midend, 32, 32, Buttons.LEFT_BUTTON)
        assertEquals("played text", oracle.text(start.state), midend.textFormat())

        for ((params, desc) in listOf(
            "3x3" to "i",
            "3x3" to "a4a4a6c",
            "3x3" to "444444444",
            "3x3" to "464476243",
            "5x5" to "c323f6546a6a4a4b4",
        )) {
            val save = historySave(params, desc, emptyList(), 1)
            val expected = oracle.id(save)
            val loaded = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals("'$desc' text", oracle.text(expected.state), loaded.textFormat())
            assertEquals("'$desc' id", expected.id, loaded.id())
            assertEquals("'$desc' status", expected.status, loaded.status())
        }

        assertEquals(
            "| ||4|| |\n|4|| ||6|\n| || || |\n",
            Midend.fromSave(Mosaic, SgtpSave.parse(historySave("3x3", "a4a4a6c", emptyList(), 1)))
                .textFormat(),
        )
    }

    @Test
    fun `initial flags follow the description`() = OracleClient().use { oracle ->
        // An inconsistent clue board loads with no error flags.
        val inconsistent = historySave("3x3", "444444444", emptyList(), 1)
        val state = (Midend.fromSave(Mosaic, SgtpSave.parse(inconsistent)).state) as Mosaic.State
        assertEquals(9, state.notCompletedClues)
        assertEquals(0, Mosaic.status(state))
        assertTrue("no error flags", state.cells.all { it and Mosaic.STATE_ERROR == 0 })
        assertEquals("Clues left: 9", Mosaic.statusText(state, Mosaic.newUi(state)))
        assertEquals(oracle.id(inconsistent).status, 0)

        // No clues means completed at load.
        val noClues = historySave("3x3", "i", emptyList(), 1)
        val done = Midend.fromSave(Mosaic, SgtpSave.parse(noClues))
        assertEquals(1, done.status())
        assertEquals("COMPLETED!", done.statusText())
        assertEquals(oracle.id(noClues).status, done.status())

        // A description that is already a full solution is open,
        // because new_game sets no solved flags.
        val full = historySave("3x3", "464476243", emptyList(), 1)
        val open = Midend.fromSave(Mosaic, SgtpSave.parse(full))
        assertEquals(0, open.status())
        assertEquals("Clues left: 9", open.statusText())
        assertEquals(oracle.id(full).status, open.status())
    }

    @Test
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "t1,0",
            "T1,0",
            "t01,00",
            "t1,0junk",
            "t1,0,3",
            "t1,0;",
            "t,0",
            "t,",
            "t1,",
            "t1,+0",
            "t1, 0",
            "d1,0,0,0,1",
            "d1,1,0,0,1",
            "e1,1,0,0,1",
            "d2,0,0,0,1",
            "d0,2,0,0,1",
            "d0,0,0,0,1",
            "t4294967295,0",
            "t4294967296,0",
            "X",
            "y1,0",
            "",
            "s",
            "s00",
            "sZZ",
            "s1234",
            "d1,0,0,0,7",
            "d1,0,0,0,4294967295",
        )
        for (move in accepted) {
            val save = historySave("3x3", DESC, listOf("MOVE" to move), 2)
            val expected = oracle.id(save)
            val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals("id after '$move'", expected.id, midend.id())
            assertEquals("save after '$move'", expected.state, midend.serialise().serialise())
            assertEquals("status after '$move'", expected.status, midend.status())
            assertEquals("text after '$move'", oracle.text(expected.state), midend.textFormat())
        }

        val rejected = listOf(
            "t 1,0",
            "t+1,0",
            "tt",
            "t-1,-1",
            "t9,9",
            "d9,9,0,0,1",
            "d1,0,9,0,1",
        )
        for (move in rejected) {
            val save = historySave("3x3", DESC, listOf("MOVE" to move), 2)
            val expected = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.fromSave(Mosaic, SgtpSave.parse(save))
                null
            } catch (e: RuntimeException) {
                e.message
            }
            assertFalse("'$move' is rejected by C", expected == null)
            assertFalse("'$move' is rejected by the port", actual == null)
        }

        // The wrap cases name (0,0) and (1,0).
        val wrap = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(historySave("3x3", DESC, listOf("MOVE" to "t4294967296,0"), 2)),
        )
        assertEquals(Mosaic.STATE_MARKED, (wrap.state as Mosaic.State).valueAt(0, 0))
        val minusOne = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(historySave("3x3", DESC, listOf("MOVE" to "t4294967295,0"), 2)),
        )
        assertEquals("the -1 early return changes nothing", DESC, minusOne.desc)
        assertEquals(0, (minusOne.state as Mosaic.State).valueAt(0, 0))

        // A raw last_state is stored unvalidated; -1 collides with the
        // drawstate sentinel and is invisible in a trace.
        val seven = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(historySave("3x3", DESC, listOf("MOVE" to "d1,0,0,0,7"), 2)),
        )
        assertEquals(7, (seven.state as Mosaic.State).valueAt(1, 0))
        val invisible = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(
                historySave("3x3", DESC, listOf("MOVE" to "d1,0,0,0,4294967295"), 2),
            ),
        )
        assertEquals(-1, (invisible.state as Mosaic.State).valueAt(1, 0))
    }

    @Test
    fun `a click cycles and a right click steps twice`() = OracleClient().use { oracle ->
        var save = historySave("3x3", DESC, emptyList(), 1)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))

        // (0,0) is a clue; a clue cell cycles like any other cell.
        for (expected in listOf(
            Mosaic.STATE_MARKED,
            Mosaic.STATE_BLANK,
            Mosaic.STATE_UNMARKED,
        )) {
            save = clickOracle(oracle, save, 32, 32, Buttons.LEFT_BUTTON)
            click(midend, 32, 32, Buttons.LEFT_BUTTON)
            assertEquals(
                "left click value",
                expected,
                (midend.state as Mosaic.State).valueAt(0, 0) and Mosaic.STATE_OK_NUM,
            )
            assertEquals("left click save", save, midend.serialise().serialise())
        }

        // A right click steps twice: unmarked to blank to marked.
        for (expected in listOf(Mosaic.STATE_BLANK, Mosaic.STATE_MARKED)) {
            save = clickOracle(oracle, save, 64, 32, Buttons.RIGHT_BUTTON)
            click(midend, 64, 32, Buttons.RIGHT_BUTTON)
            assertEquals(
                "right click value",
                expected,
                (midend.state as Mosaic.State).valueAt(1, 0) and Mosaic.STATE_OK_NUM,
            )
            assertEquals("right click save", save, midend.serialise().serialise())
        }
        assertEquals(
            "three left and two right clicks",
            5,
            SgtpSave.parse(midend.serialise().serialise()).values("MOVE").size,
        )
    }

    @Test
    fun `drags paint a straight line with the press value`() = OracleClient().use { oracle ->
        val save = historySave("3x3", DESC, emptyList(), 1)
        val points = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(32, 64, Buttons.LEFT_DRAG),
            OracleClient.Point(32, 96, Buttons.LEFT_DRAG),
            OracleClient.Point(32, 96, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, points, width = 128, height = 128)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, 32)
        }
        assertEquals("drag save", expected.state.state, midend.serialise().serialise())
        assertEquals(
            "the drag makes one move per drag",
            listOf("t0,0", "d0,1,0,0,1", "d0,2,0,1,1"),
            SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
        )
        val state = midend.state as Mosaic.State
        assertEquals(Mosaic.STATE_MARKED, state.valueAt(0, 0))
        assertEquals(Mosaic.STATE_MARKED, state.valueAt(0, 1))
        assertEquals(Mosaic.STATE_MARKED, state.valueAt(0, 2))

        // A diagonal drag is not in the UI grammar: it changes nothing
        // and resets the anchor, so the release also does nothing.
        val diagonal = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 64, Buttons.LEFT_DRAG),
            OracleClient.Point(64, 64, Buttons.LEFT_RELEASE),
        )
        val diagonalExpected = oracle.pointsSized(save, diagonal, width = 128, height = 128)
        val diagonalMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        for (point in diagonal) {
            diagonalMidend.processKey(point.x, point.y, point.button, 32)
        }
        assertEquals(
            "diagonal drag save",
            diagonalExpected.state.state,
            diagonalMidend.serialise().serialise(),
        )
        assertEquals(
            "only the press is a move",
            listOf("t0,0"),
            SgtpSave.parse(diagonalMidend.serialise().serialise()).values("MOVE"),
        )

        // A lone drag and a lone release are ignored by the midend.
        val lone = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.Unused,
            lone.processKey(32, 64, Buttons.LEFT_DRAG, 32),
        )
        assertEquals(
            KeyOutcome.Unused,
            lone.processKey(32, 64, Buttons.LEFT_RELEASE, 32),
        )
        assertEquals("a lone drag adds no state", 1, lone.stateCount)
    }

    @Test
    fun `a press without a release inserts a fabricated fill`() = OracleClient().use { oracle ->
        val save = historySave("3x3", DESC, emptyList(), 1)
        val points = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
        )
        val expected = oracle.pointsSized(save, points, width = 128, height = 128)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        for (point in points) midend.processKey(point.x, point.y, point.button, 32)
        assertEquals("fabricated release", expected.state.state, midend.serialise().serialise())
        assertEquals(
            "three records",
            listOf("t0,0", "e1,0,0,0,1", "t1,0"),
            SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
        )
        val state = midend.state as Mosaic.State
        assertEquals(Mosaic.STATE_MARKED, state.valueAt(0, 0) and Mosaic.STATE_OK_NUM)
        assertEquals(Mosaic.STATE_BLANK, state.valueAt(1, 0) and Mosaic.STATE_OK_NUM)
    }

    @Test
    fun `the winning sequence completes the board`() = OracleClient().use { oracle ->
        var save = historySave("3x3", DESC, emptyList(), 1)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        midend.takeCompletedEvent()

        val points = listOf(
            OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(96, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(32, 64, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 64, Buttons.LEFT_BUTTON),
            OracleClient.Point(96, 64, Buttons.LEFT_BUTTON),
            OracleClient.Point(96, 96, Buttons.LEFT_BUTTON),
            OracleClient.Point(32, 96, Buttons.RIGHT_BUTTON),
            OracleClient.Point(64, 96, Buttons.RIGHT_BUTTON),
        )
        for (point in points) {
            save = clickOracle(oracle, save, point.x, point.y, point.button)
            click(midend, point.x, point.y, point.button)
        }

        assertEquals("completed status", 1, midend.status())
        assertEquals("completed text", "COMPLETED!", midend.statusText())
        assertEquals("completed save", save, midend.serialise().serialise())
        assertTrue("the honest completion raises the event", midend.takeCompletedEvent())
        assertEquals("nine moves", 9, SgtpSave.parse(save).values("MOVE").size)

        val state = midend.state as Mosaic.State
        assertEquals("seven marked", 7, state.cells.count { it and Mosaic.STATE_MARKED != 0 })
        assertEquals("two blank", 2, state.cells.count { it and Mosaic.STATE_BLANK != 0 })
        assertEquals("all clues solved", 0, state.notCompletedClues)

        // The board is frozen after completion.
        assertEquals(KeyOutcome.Unused, click(midend, 32, 32, Buttons.LEFT_BUTTON))
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, Buttons.CURSOR_SELECT, 32))
        assertEquals("the freeze adds no state", 10, midend.stateCount)
        assertEquals("the freeze changes nothing", save, midend.serialise().serialise())

        // An arrow still moves and reveals the ring.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_DOWN, 32))
        assertEquals("cursor y", 1, (midend.ui as Mosaic.Ui).curY)
        assertTrue("cursor visible", (midend.ui as Mosaic.Ui).curVisible)
        assertEquals("the arrow adds no state", 10, midend.stateCount)
    }

    @Test
    fun `a solved state never raises the completion event`() {
        val open = Mosaic.newState(Mosaic.decodeParams("3x3"), DESC) as Mosaic.State
        val solved = Mosaic.executeMove(open, Mosaic.solve(open)!!) as Mosaic.State
        val cheated = solved.cheating
        assertTrue(cheated)
        assertFalse(
            "a solve raises nothing",
            Mosaic.changedState(Mosaic.newUi(open), open, solved),
        )

        val honest = Mosaic.newState(Mosaic.decodeParams("3x3"), "i") as Mosaic.State
        assertTrue(
            "an open to completed move raises the event",
            Mosaic.changedState(Mosaic.newUi(open), open, honest),
        )
    }

    @Test
    fun `no clues starts frozen and a full solution starts open`() =
        OracleClient().use { oracle ->
            val noClues = Midend.fromSave(
                Mosaic,
                SgtpSave.parse(historySave("3x3", "i", emptyList(), 1)),
            )
            assertEquals(1, noClues.status())
            assertEquals("COMPLETED!", noClues.statusText())
            assertEquals(KeyOutcome.Unused, click(noClues, 32, 32, Buttons.LEFT_BUTTON))
            assertEquals("a click adds no state", 1, noClues.stateCount)
            assertEquals(KeyOutcome.SomeEffect, noClues.processKey(0, 0, Buttons.CURSOR_RIGHT, 32))
            assertEquals(1, noClues.stateCount)

            // A board that is already a full solution is open, and one
            // move can change it.
            val full = Midend.fromSave(
                Mosaic,
                SgtpSave.parse(historySave("3x3", "464476243", emptyList(), 1)),
            )
            assertEquals(0, full.status())
            assertEquals(KeyOutcome.SomeEffect, click(full, 32, 32, Buttons.LEFT_BUTTON))
            assertEquals("the click adds a state", 2, full.stateCount)
            assertEquals(
                "the click changes the board",
                Mosaic.STATE_MARKED,
                (full.state as Mosaic.State).valueAt(0, 0),
            )
            assertEquals(oracle.id(historySave("3x3", "464476243", emptyList(), 1)).status, 0)
        }

    @Test
    fun `undo and redo cross a completion and a solve`() = OracleClient().use { oracle ->
        val winning = historySave(
            "3x3",
            DESC,
            listOf(
                "MOVE" to "t0,0", "MOVE" to "t1,0", "MOVE" to "t2,0",
                "MOVE" to "t0,1", "MOVE" to "t1,1", "MOVE" to "t2,1",
                "MOVE" to "t2,2", "MOVE" to "T0,2", "MOVE" to "T1,2",
            ),
            10,
        )
        assertEquals("the fixture wins", 1, oracle.id(winning).status)

        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(winning))
        midend.takeCompletedEvent()
        assertTrue("undo", midend.undo())
        assertEquals(0, midend.status())
        assertFalse("an undo into an open state raises nothing", midend.takeCompletedEvent())
        assertEquals(oracle.undo(winning).state, midend.serialise().serialise())

        assertTrue("redo", midend.redo())
        assertEquals(1, midend.status())
        assertTrue("a redo into the solved state raises the event again", midend.takeCompletedEvent())
        assertEquals(oracle.redo(oracle.undo(winning).state).state, midend.serialise().serialise())

        // A SOLVE record undoes to the open state and redoes to the
        // cheated solved state without an event.
        val solveText = SgtpSave.parse(oracle.solve(historySave("3x3", DESC, emptyList(), 1)).state)
            .value("SOLVE")!!
        val solvedSave = historySave("3x3", DESC, listOf("SOLVE" to solveText), 2)
        assertEquals("the solve save wins", 1, oracle.id(solvedSave).status)
        val solved = Midend.fromSave(Mosaic, SgtpSave.parse(solvedSave))
        assertFalse("a SOLVE load raises no event", solved.takeCompletedEvent())
        assertTrue("undo across a solve", solved.undo())
        assertEquals(0, solved.status())
        assertEquals("Clues left: 3", solved.statusText())
        assertFalse(solved.takeCompletedEvent())
        assertTrue("redo across a solve", solved.redo())
        assertEquals("Auto solved", solved.statusText())
        assertFalse("a redo into a cheated solve raises nothing", solved.takeCompletedEvent())
        assertEquals(oracle.redo(oracle.undo(solvedSave).state).state, solved.serialise().serialise())

        // A restart after a solve returns to the open description.
        assertTrue(solved.restart())
        assertEquals(0, solved.status())
        assertEquals("Clues left: 3", solved.statusText())
        assertFalse("a restart from a cheated solve raises nothing", solved.takeCompletedEvent())
        assertEquals(
            listOf("SOLVE", "RESTART"),
            SgtpSave.parse(solved.serialise().serialise()).records
                .filter { it.key == "MOVE" || it.key == "SOLVE" || it.key == "RESTART" }
                .map { it.key },
        )
        val restarted = solved.serialise().serialise()
        assertEquals(oracle.id(restarted).status, solved.status())
        assertEquals(oracle.id(restarted).id, solved.id())
    }

    @Test
    fun `solve matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in listOf("", "3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0")) {
            val start = oracle.newGame("mosaic", params, SEED)
            val expected = oracle.solve(start.state)
            val midend = Midend.fromSave(Mosaic, SgtpSave.parse(start.state))
            midend.takeCompletedEvent()
            assertTrue("$params solves", midend.solve())
            assertEquals("$params solve save", expected.state, midend.serialise().serialise())
            assertEquals("$params solve id", expected.id, midend.id())
            assertEquals("$params solve status", 1, midend.status())
            assertEquals("$params solve text", "Auto solved", midend.statusText())
            assertFalse("$params solve raises no event", midend.takeCompletedEvent())
            assertEquals("$params solve text form", oracle.text(expected.state), midend.textFormat())
            assertEquals(
                "$params SOLVE record",
                "SOLVE",
                SgtpSave.parse(midend.serialise().serialise()).records.last().key,
            )
        }
    }

    @Test
    fun `solve reads the shared clue board after a move`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mosaic", "5x5", SEED)
        val moved = clickOracle(oracle, start.state, 96, 32, Buttons.LEFT_BUTTON, view = 192)
        val expected = oracle.solve(moved)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(moved))
        assertTrue("a solve after a click", midend.solve())
        assertEquals(
            "the answer is the clue board's answer",
            expected.state,
            midend.serialise().serialise(),
        )
        assertEquals(
            "the 5x5 answer is stable",
            "s22f9bd80",
            SgtpSave.parse(expected.state).value("SOLVE"),
        )
    }

    @Test
    fun `an inconsistent board cannot be solved`() = OracleClient().use { oracle ->
        val save = historySave("3x3", "444444444", emptyList(), 1)
        assertEquals(
            "Could not solve this board",
            try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            },
        )
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        assertNull("no answer", Mosaic.solve(midend.state))
        assertFalse("the midend solve fails", midend.solve())
        assertEquals("a failed solve adds no state", 1, midend.stateCount)

        // A blank clue board is solvable: every clue 0 blanks its block.
        val blank = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(historySave("3x3", "000000000", emptyList(), 1)),
        )
        assertTrue(blank.solve())
        assertEquals("s0000", SgtpSave.parse(blank.serialise().serialise()).value("SOLVE"))
    }

    @Test
    fun `a solve then a move returns to an open status`() = OracleClient().use { oracle ->
        val solveText = SgtpSave.parse(oracle.solve(historySave("3x3", DESC, emptyList(), 1)).state)
            .value("SOLVE")!!
        val save = historySave(
            "3x3",
            DESC,
            listOf("SOLVE" to solveText, "MOVE" to "t1,0"),
            3,
        )
        val expected = oracle.id(save)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        assertEquals("id", expected.id, midend.id())
        assertEquals("status", expected.status, midend.status())
        assertEquals("save", expected.state, midend.serialise().serialise())
        assertEquals("text", oracle.text(expected.state), midend.textFormat())
        assertTrue(
            "the counter is open again: ${midend.statusText()}",
            midend.status() == 0 && midend.statusText().startsWith("Clues left: "),
        )
    }

    @Test
    fun `requested keys are the default arrow mode with no labels`() =
        OracleClient().use { oracle ->
            for (params in listOf("", "3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0")) {
                val game = oracle.newGame("mosaic", params, SEED)
                val expected = oracle.requestKeys(game.state)
                val actual = Mosaic.requestKeys(Mosaic.decodeParams(params))
                assertEquals("$params arrow mode", 3, expected.arrowMode)
                assertEquals("$params Kotlin arrow mode", ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
                assertEquals("$params no labels", emptyList<String>(), expected.labels.map { it.label })
                assertTrue("$params no labels", actual.labels.isEmpty())
            }
        }

    @Test
    fun `keyboard behavior and the completed gate match the oracle`() =
        OracleClient().use { oracle ->
            val save = historySave("3x3", DESC, emptyList(), 1)
            val sequence = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_UP,
                '\n'.code,
            )
            val expected = oracle.keys(save, sequence).state
            val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))

            // An arrow with a hidden cursor moves and reveals.
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_DOWN, 32))
            val ui = midend.ui as Mosaic.Ui
            assertTrue(ui.curVisible)
            assertEquals(0, ui.curX)
            assertEquals(1, ui.curY)

            // Two more arrows move, and an arrow at the edge has no
            // effect but still counts as used.
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_DOWN, 32))
            assertEquals(KeyOutcome.NoEffect, midend.processKey(0, 0, Buttons.CURSOR_DOWN, 32))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_UP, 32))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_UP, 32))

            // Enter selects the cursor cell.
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, '\n'.code, 32))
            assertEquals("Enter selects", expected, midend.serialise().serialise())
            assertEquals(
                "t0,0",
                SgtpSave.parse(expected).values("MOVE").last(),
            )

            // A modified arrow and a keypad digit are unused.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, 32),
            )
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, '1'.code or Buttons.MOD_NUM_KEYPAD, 32),
            )

            // u and r are shortcuts when the game returns Unused.
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'u'.code, 32))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'r'.code, 32))
            assertEquals(
                "the undo and redo restore the click",
                expected,
                midend.serialise().serialise(),
            )

            // The select with a hidden cursor reveals it and places
            // nothing.
            val fresh = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                fresh.processKey(0, 0, Buttons.CURSOR_SELECT, 32),
            )
            assertTrue((fresh.ui as Mosaic.Ui).curVisible)
            assertEquals("a reveal adds no state", 1, fresh.stateCount)
        }

    @Test
    fun `vacuity guards`() {
        // A real cycle changes the board and the move count.
        val midend = Midend.newGame(Mosaic, "3x3", SEED)
        val before = (midend.state as Mosaic.State).cells.copyOf()
        val countBefore = midend.stateCount
        assertEquals(KeyOutcome.SomeEffect, click(midend, 32, 32, Buttons.LEFT_BUTTON))
        assertFalse(
            "a real cycle changes the board",
            (midend.state as Mosaic.State).cells.contentEquals(before),
        )
        assertEquals("a real cycle adds one state", countBefore + 1, midend.stateCount)

        // An unknown or empty move copies the board and adds one history
        // entry through the midend, as C does.
        val state = midend.state as Mosaic.State
        for (move in listOf("X", "", "y1,0")) {
            val copy = Mosaic.executeMove(state, move) as Mosaic.State
            assertEquals("'$move' keeps the board", state.cells.toList(), copy.cells.toList())
            assertEquals("'$move' keeps the status", Mosaic.status(state), Mosaic.status(copy))
        }
        val loaded = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(historySave("3x3", DESC, listOf("MOVE" to "X"), 2)),
        )
        assertEquals("one history entry", 2, loaded.stateCount)
        assertEquals(
            "the board is the first state's",
            (Midend.fromSave(Mosaic, SgtpSave.parse(historySave("3x3", DESC, emptyList(), 1)))
                .state as Mosaic.State).cells.toList(),
            (loaded.state as Mosaic.State).cells.toList(),
        )
    }

    /** One press and one release at a view point; returns the press result. */
    private fun click(
        midend: Midend,
        x: Int,
        y: Int,
        button: Int,
    ): KeyOutcome {
        val outcome = midend.processKey(x, y, button, TILE)
        midend.processKey(x, y, button + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON), TILE)
        return outcome
    }

    /** One click on the oracle, returning the new save text. */
    private fun clickOracle(
        oracle: OracleClient,
        save: String,
        x: Int,
        y: Int,
        button: Int,
        view: Int = 128,
    ): String = oracle.pointsSized(
        save,
        listOf(
            OracleClient.Point(x, y, button),
            OracleClient.Point(x, y, button + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON)),
        ),
        width = view,
        height = view,
    ).state.state

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val currentParams = if (params == "50x50") "50x50h0" else params
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Mosaic.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", currentParams),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val DESC = "4b47d"
    }
}
