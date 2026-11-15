package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Guess
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
 * Level: semantic. Game: guess.
 *
 * New games, the status, drafts, holds, drags, keyboard input, the
 * reveal, undo and redo, completion, the hint sequence, and the loose
 * move grammar. Each case compares the complete save text with the
 * oracle, so the draft and the holds are part of the assertion.
 */
class GuessGameTest {

    @Test
    fun `a new game matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("c6p4g10Bm", "c8p5g12Bm", "c3p2g1bm")) {
            val expected = oracle.newGame("guess", params, SEED)
            val midend = Midend.newGame(Guess, params, SEED)
            assertSame("new game '$params'", expected, midend)
            assertEquals(0, midend.status())
            assertEquals("", midend.statusText())
            assertEquals("", midend.textFormat())
            assertFalse(Guess.canFormatAsText)
            assertFalse(Guess.wantsStatusBar)
            assertFalse(midend.canUndo)
            assertFalse(midend.canRedo)
            assertEquals(1, midend.stateCount)
            // No AUXINFO: the solution is only in DESC.
            assertNull(midend.serialise().value("AUXINFO"))
            assertEquals("", oracle.text(expected.state))
        }
    }

    @Test
    fun `requestKeys match the oracle`() = OracleClient().use { oracle ->
        val state = oracle.newGame("guess", "", SEED)
        val expected = oracle.requestKeys(state.state)
        val actual = Guess.requestKeys(Guess.decodeParams("c6p4g10Bm"))
        assertEquals(3, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertEquals(expected.labels.size, actual.labels.size)
        for (i in expected.labels.indices) {
            assertEquals("label $i", expected.labels[i].label, actual.labels[i].name)
            assertEquals("button $i", expected.labels[i].button, actual.labels[i].button)
            assertEquals(
                "needsArrows $i",
                expected.labels[i].needsArrows,
                actual.labels[i].needsArrows,
            )
        }
        assertEquals("Labels", actual.labels[0].name)
        assertFalse(actual.labels[0].needsArrows)
        assertEquals("Clear", actual.labels[1].name)
        assertTrue(actual.labels[1].needsArrows)
    }

    @Test
    fun `a full draft and a winning guess match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("guess", "", "0")
        val sequence = listOf('3'.code, '3'.code, '4'.code, '1'.code, Buttons.CURSOR_SELECT)
        val expected = oracle.keys(start.state, sequence)
        val midend = Midend.newGame(Guess, "", "0")
        for (key in sequence) {
            assertEquals(
                "key $key",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, TILE),
            )
        }
        assertSame("the winning guess", expected, midend)
        assertEquals(1, midend.status())
        assertEquals("0,0,0,0", Guess.encodeUi(midend.ui))
        assertEquals(listOf("G3,3,4,1"), SgtpSave.parse(expected.state).values("MOVE"))
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
        assertEquals(2, midend.stateCount)
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // The status is the win, and no status bar is drawn.
        assertEquals(1, oracle.status(expected.state).status)
        assertEquals("", midend.statusText())
        assertEquals("", oracle.text(expected.state))
        assertEquals("", midend.textFormat())
    }

    @Test
    fun `a wrong guess and the hint after it match the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("guess", "", "0")
            // 1,1,1,1, Enter, then h. The hint exposes the stored
            // feedback, so a wrong mark_pegs would change the draft.
            val sequence = listOf(49, 49, 49, 49, 525, 104)
            val expected = oracle.keys(start.state, sequence)
            val midend = Midend.newGame(Guess, "", "0")
            for (key in sequence) {
                midend.processKey(0, 0, key, TILE)
            }
            assertSame("guess then hint", expected, midend)
            assertEquals("1,2,2,2", Guess.encodeUi(midend.ui))
            assertEquals(
                listOf("G1,1,1,1"),
                SgtpSave.parse(expected.state).values("MOVE"),
            )
            assertEquals(2, midend.stateCount)
        }

    @Test
    fun `holds, a drag, and the echo into the next draft match the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("guess", "", "0")
            // 2,2,2,2 by digits, then hold the first two pegs with the
            // right button, then submit with a click into the hint area.
            val digits = oracle.keys(start.state, listOf(50, 50, 50, 50))
            assertEquals("2,2,2,2", uiOf(digits.state))

            val holds = oracle.pointsSized(
                digits.state,
                listOf(
                    OracleClient.Point(pegX(0), rowY(0), Buttons.RIGHT_BUTTON),
                    OracleClient.Point(pegX(0), rowY(0), Buttons.RIGHT_RELEASE),
                    OracleClient.Point(pegX(1), rowY(0), Buttons.RIGHT_BUTTON),
                    OracleClient.Point(pegX(1), rowY(0), Buttons.RIGHT_RELEASE),
                    OracleClient.Point(hintX, rowY(0), Buttons.LEFT_BUTTON),
                    OracleClient.Point(hintX, rowY(0), Buttons.LEFT_RELEASE),
                ),
                width = VIEW_W,
                height = VIEW_H,
            )
            assertEquals("2_,2_,0,0", uiOf(holds.state.state))

            val midend = Midend.fromSave(Guess, SgtpSave.parse(digits.state))
            val ui = midend.ui as Guess.Ui
            assertEquals("2,2,2,2", Guess.encodeUi(ui))
            // A drag from the colour bar to a peg is a UI-only update.
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(colourX, colourY(2), Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(pegX(0), rowY(0), Buttons.LEFT_DRAG, TILE),
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(pegX(0), rowY(0), Buttons.LEFT_RELEASE, TILE),
            )
            assertEquals("2,2,2,2", Guess.encodeUi(ui))

            for (peer in listOf(0, 1)) {
                midend.processKey(pegX(peer), rowY(0), Buttons.RIGHT_BUTTON, TILE)
                midend.processKey(pegX(peer), rowY(0), Buttons.RIGHT_RELEASE, TILE)
            }
            assertEquals("2_,2_,2,2", Guess.encodeUi(ui))
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(hintX, rowY(0), Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(hintX, rowY(0), Buttons.LEFT_RELEASE, TILE),
            )
            assertSame("held guess", holds.state, midend)
            assertEquals("2_,2_,0,0", Guess.encodeUi(ui))
            assertEquals(listOf("G2_,2_,2,2"), SgtpSave.parse(holds.state.state).values("MOVE"))

            // Undo clears the draft; redo restores the held echo.
            assertTrue(midend.undo())
            assertEquals("0,0,0,0", Guess.encodeUi(ui))
            val undone = oracle.undo(holds.state.state)
            assertSame("undo", undone, midend)
            assertTrue(midend.redo())
            assertEquals("2_,2_,0,0", Guess.encodeUi(ui))
            val redone = oracle.redo(undone.state)
            assertSame("redo", redone, midend)
        }

    @Test
    fun `a drag out of a current peg clears it`() = OracleClient().use { oracle ->
        val start = oracle.newGame("guess", "", "0")
        val dragged = oracle.pointsSized(
            start.state,
            listOf(
                OracleClient.Point(colourX, colourY(3), Buttons.LEFT_BUTTON),
                OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_DRAG),
                OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_RELEASE),
            ),
            width = VIEW_W,
            height = VIEW_H,
        )
        assertEquals("3,0,0,0", uiOf(dragged.state.state))
        val cleared = oracle.pointsSized(
            dragged.state.state,
            listOf(
                OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_BUTTON),
                OracleClient.Point(10, 300, Buttons.LEFT_DRAG),
                OracleClient.Point(10, 300, Buttons.LEFT_RELEASE),
            ),
            width = VIEW_W,
            height = VIEW_H,
        )
        assertEquals("0,0,0,0", uiOf(cleared.state.state))

        val midend = Midend.fromSave(Guess, SgtpSave.parse(start.state))
        val ui = midend.ui as Guess.Ui
        midend.processKey(colourX, colourY(3), Buttons.LEFT_BUTTON, TILE)
        midend.processKey(pegX(0), rowY(0), Buttons.LEFT_DRAG, TILE)
        midend.processKey(pegX(0), rowY(0), Buttons.LEFT_RELEASE, TILE)
        assertEquals("3,0,0,0", Guess.encodeUi(ui))
        midend.processKey(pegX(0), rowY(0), Buttons.LEFT_BUTTON, TILE)
        midend.processKey(10, 300, Buttons.LEFT_DRAG, TILE)
        midend.processKey(10, 300, Buttons.LEFT_RELEASE, TILE)
        assertSame("cleared draft", cleared.state, midend)
        assertEquals("0,0,0,0", Guess.encodeUi(ui))
    }

    @Test
    fun `a drag from a past guess copies into the current row`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("guess", "", "0")
            val played = oracle.keys(start.state, listOf(49, 49, 49, 49, 525))
            val copied = oracle.pointsSized(
                played.state,
                listOf(
                    OracleClient.Point(pegX(0), rowY(0), Buttons.LEFT_BUTTON),
                    OracleClient.Point(pegX(1), rowY(1), Buttons.LEFT_DRAG),
                    OracleClient.Point(pegX(1), rowY(1), Buttons.LEFT_RELEASE),
                ),
                width = VIEW_W,
                height = VIEW_H,
            )
            assertEquals("0,1,0,0", uiOf(copied.state.state))

            val midend = Midend.fromSave(Guess, SgtpSave.parse(played.state))
            midend.processKey(pegX(0), rowY(0), Buttons.LEFT_BUTTON, TILE)
            midend.processKey(pegX(1), rowY(1), Buttons.LEFT_DRAG, TILE)
            midend.processKey(pegX(1), rowY(1), Buttons.LEFT_RELEASE, TILE)
            assertSame("copied peg", copied.state, midend)
            assertEquals("0,1,0,0", Guess.encodeUi(midend.ui))
        }

    @Test
    fun `keyboard draft, hint, backspace, and labels match the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("guess", "", "0")
            val sequences = listOf(
                listOf(49, 50, 51),
                listOf(104),
                listOf(104, 104),
                listOf(49, 49, 8),
                listOf(49, 49, 49, 49, 8),
                listOf(49, 49, 49, 49, 8, 8),
                listOf(525),
                listOf(526),
                listOf(108),
                listOf(108, 108),
                listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_RIGHT, 50),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(start.state, sequence)
                val midend = Midend.fromSave(Guess, SgtpSave.parse(start.state))
                for (key in sequence) {
                    midend.processKey(0, 0, key, TILE)
                }
                assertSame("keys $sequence", expected, midend)
            }

            // The label key never changes the UI record, and the label
            // preference is not in the save.
            val labels = oracle.keys(start.state, listOf(108))
            assertEquals("0,0,0,0", uiOf(labels.state))
            val midend = Midend.fromSave(Guess, SgtpSave.parse(start.state))
            midend.processKey(0, 0, 108, TILE)
            assertTrue((midend.ui as Guess.Ui).showLabels)
            assertEquals("0,0,0,0", Guess.encodeUi(midend.ui))
        }

    @Test
    fun `the reveal sets the loss status and undo returns to the game`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("guess", "", "0")
            val solved = oracle.solve(start.state)
            val midend = Midend.newGame(Guess, "", "0")
            assertTrue(midend.solve())
            assertSame("solve", solved, midend)
            assertEquals(-1, midend.status())
            assertEquals(-1, oracle.status(solved.state).status)
            assertFalse(midend.takeCompletedEvent())
            assertEquals(listOf("S"), SgtpSave.parse(solved.state).values("SOLVE"))
            assertEquals("", midend.statusText())
            assertEquals("", midend.textFormat())

            // A repeated solve adds another SOLVE record.
            val solvedAgain = oracle.solve(solved.state)
            assertTrue(midend.solve())
            assertSame("repeated solve", solvedAgain, midend)
            assertEquals(2, SgtpSave.parse(solvedAgain.state).values("SOLVE").size)

            // Undo across the two solves, and redo.
            assertTrue(midend.undo())
            val undone = oracle.undo(solvedAgain.state)
            assertSame("undo one solve", undone, midend)
            assertEquals(-1, midend.status())
            assertTrue(midend.undo())
            val undoneTwice = oracle.undo(undone.state)
            assertSame("undo both solves", undoneTwice, midend)
            assertEquals(0, midend.status())
            assertTrue(midend.redo())
            assertSame("redo one solve", undone, midend)
        }

    @Test
    fun `a last wrong guess loses and is not a completion`() =
        OracleClient().use { oracle ->
            // One guess, so the first wrong guess is a loss.
            val start = oracle.newGame("guess", "c3p2g1bm", "0")
            val lost = oracle.keys(start.state, listOf(49, 49, 525))
            val midend = Midend.newGame(Guess, "c3p2g1bm", "0")
            for (key in listOf(49, 49, 525)) {
                midend.processKey(0, 0, key, TILE)
            }
            assertSame("lost game", lost, midend)
            assertEquals(-1, midend.status())
            assertEquals(-1, oracle.status(lost.state).status)
            assertFalse(midend.takeCompletedEvent())

            // Solving a lost state keeps the loss status.
            val solved = oracle.solve(lost.state)
            assertTrue(midend.solve())
            assertSame("solve after loss", solved, midend)
            assertEquals(-1, midend.status())
            assertFalse(midend.takeCompletedEvent())
            assertTrue(midend.undo())
            assertSame("undo the solve", oracle.undo(solved.state), midend)
        }

    @Test
    fun `redo into a win raises the completion event again`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("guess", "", "0")
            val won = oracle.keys(start.state, listOf(51, 51, 52, 49, 525))
            val midend = Midend.newGame(Guess, "", "0")
            for (key in listOf(51, 51, 52, 49, 525)) {
                midend.processKey(0, 0, key, TILE)
            }
            assertSame("won game", won, midend)
            assertTrue(midend.takeCompletedEvent())
            assertFalse(midend.takeCompletedEvent())

            assertTrue(midend.undo())
            assertSame("undo the win", oracle.undo(won.state), midend)
            assertEquals(0, midend.status())
            assertFalse(midend.takeCompletedEvent())

            assertTrue(midend.redo())
            assertSame("redo the win", won, midend)
            assertTrue(midend.takeCompletedEvent())
            assertFalse(midend.takeCompletedEvent())
        }

    @Test
    fun `hints match the oracle on the no-multiple and blank paths`() =
        OracleClient().use { oracle ->
            // c3p3g2BM: 1,2,3 is a full markable draft without
            // duplicates. c4p4g10BM: 1,2,3,4. c3p2g1bm: 1 with a
            // blank, then the cursor moves to Submit.
            val cases = listOf(
                "c3p3g2BM" to listOf(49, 50, 51, 525, 104),
                "c4p4g10BM" to listOf(49, 50, 51, 52, 525, 104),
                "c3p2g1bm" to listOf(49, 524, 525, 104),
                "c3p2g1bm" to listOf(49, 49, 525, 104, 104),
            )
            for ((params, sequence) in cases) {
                val start = oracle.newGame("guess", params, "0")
                val expected = oracle.keys(start.state, sequence)
                val midend = Midend.newGame(Guess, params, "0")
                for (key in sequence) {
                    midend.processKey(0, 0, key, TILE)
                }
                assertSame("$params keys $sequence", expected, midend)
            }
        }

    @Test
    fun `the move grammar parses like C`() = OracleClient().use { oracle ->
        // Three guesses, so the first move leaves the game open and a
        // later hint exposes the parsed peg values. `G 1, 2` and
        // `G+1,+2` both parse as 1,1 and give the hint 2,2; `G1,2`
        // parses as 1,2 and gives 3,3.
        val accepted = listOf(
            "c3p2g3bm" to "G1,2",
            "c3p2g3bm" to "G 1, 2",
            "c3p2g3bm" to "G+1,+2",
            "c3p2g3bm" to "G1,2junk",
            "c3p2g3bm" to "G1,2,3",
            "c3p2g3bm" to "G1,",
            "c3p2g3bm" to "G1",
            "c3p2g3bm" to "G0,1",
        )
        for ((params, move) in accepted) {
            val text = historySave(params, "3ea7", listOf("MOVE" to move), 2)
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
            assertSame("move '$move'", expected, midend)
            assertEquals(2, midend.stateCount)

            // A hint reads the stored guess and its feedback, so the
            // parsed peg values are visible in the UI record.
            val hinted = oracle.keys(text, listOf(104))
            midend.processKey(0, 0, 104, TILE)
            assertSame("hint after '$move'", hinted, midend)
        }

        val rejected = listOf(
            "c3p2g3BM" to "G0,1",
            "c3p2g3BM" to "G1",
            "c3p2g3bm" to "G4,1",
            "c3p2g3bm" to "G-1,1",
            "c3p2g3bm" to "s",
            "c3p2g3bm" to "X1,2",
        )
        for ((params, move) in rejected) {
            val text = historySave(params, "3ea7", listOf("MOVE" to move), 2)
            val expected = try {
                oracle.id(text)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("oracle message for '$move'", INVALID_MOVE_ORACLE, expected)
            try {
                Midend.fromSave(Guess, SgtpSave.parse(text))
                throw AssertionError("Kotlin loaded the move '$move'")
            } catch (e: IllegalStateException) {
                assertEquals(
                    "Kotlin message for '$move'",
                    "$INVALID_MOVE_KOTLIN: $move",
                    e.message,
                )
            }
        }
    }

    @Test
    fun `the solution never appears in the save, the UI, or the status`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Guess, "", "0")
            val solution = ((midend.state as Guess.State).solution).joinToString(",")
            val save = midend.serialise()
            assertEquals("", midend.statusText())
            assertEquals("", midend.textFormat())
            assertFalse(save.serialise().contains(solution))
            assertEquals("0,0,0,0", save.value("UI"))
            assertNull(save.value("AUXINFO"))

            // A played game still hides it until solved.
            for (key in listOf(49, 49, 49, 49, 525)) {
                midend.processKey(0, 0, key, TILE)
            }
            assertEquals(0, midend.status())
            assertFalse(midend.serialise().serialise().contains(solution))
            assertFalse(midend.statusText().contains(solution))

            // C reveals it only through the solved status, not through
            // a new record.
            val solved = oracle.solve(midend.serialise().serialise())
            assertEquals(-1, oracle.status(solved.state).status)
            assertEquals("0,0,0,0", uiOf(solved.state))
        }

    private fun uiOf(state: String): String =
        requireNotNull(SgtpSave.parse(state).value("UI")) { "save has no UI record" }

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Guess.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("UI", "0,0"),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private val colourX = 32

    private fun colourY(colour: Int): Int = 106 + (colour - 1) * 35 + 16

    private fun pegX(peg: Int): Int = 80 + peg * 35 + 16

    private fun rowY(row: Int): Int = 33 + row * 35

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val VIEW_W = 263
        const val VIEW_H = 420
        const val hintX = 230
        const val INVALID_MOVE_ORACLE = "Save file contained an invalid move"
        const val INVALID_MOVE_KOTLIN = "save file contained an invalid move"
    }
}
