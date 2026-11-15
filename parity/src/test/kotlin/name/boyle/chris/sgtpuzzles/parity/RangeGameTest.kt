package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: range.
 *
 * New games, text, status, the move grammar, completion, undo and redo,
 * the solver, the hint, and the requested keys are compared with the
 * oracle. `range.c:1574-1610` parses a move with
 * `sscanf("%c,%d,%d%n")`, so the accepted and rejected forms are pinned
 * one by one.
 */
class RangeGameTest {

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("", "9x6", "12x8", "13x9", "16x11")) {
            val expected = oracle.newGame("range", params, SEED)
            val midend = Midend.newGame(Range, params, SEED)
            assertEquals("id '$params'", expected.id, midend.id())
            assertEquals("state '$params'", expected.state, midend.serialise().serialise())
            assertEquals("status '$params'", expected.status, midend.status())
            assertEquals("text '$params'", oracle.text(expected.state), midend.textFormat())
            assertEquals("status text '$params'", "", midend.statusText())

            val state = midend.state as Range.State
            assertFalse("$params starts open", state.wasSolved)
            assertFalse("$params has not cheated", state.hasCheated)
            val save = SgtpSave.parse(expected.state)
            assertEquals("$params PARAMS is CPARAMS", save.value("PARAMS"), save.value("CPARAMS"))
            assertNull("$params AUXINFO", save.value("AUXINFO"))
            assertNull("$params UI", save.value("UI"))
        }
    }

    @Test
    fun `text formatting matches the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("range", "9x6", SEED)

        // A solved board prints ## and dots.
        val solved = oracle.solve(start.state)
        val solvedMidend = Midend.fromSave(Range, SgtpSave.parse(start.state))
        assertTrue(solvedMidend.solve())
        assertEquals("solved save", solved.state, solvedMidend.serialise().serialise())
        assertEquals("solved text", oracle.text(solved.state), solvedMidend.textFormat())
        assertTrue("a solved board has dots", solvedMidend.textFormat().contains(".."))
        assertTrue("a solved board has black cells", solvedMidend.textFormat().contains("##"))

        // A partially filled board and a clue overwritten by a move.
        val cases = listOf("B,0,2", "B,0,0", "W,1,2B,1,3", "E,0,2")
        for (move in cases) {
            val save = historySave("9x6", DESC, listOf("MOVE" to move), 2)
            val expected = oracle.id(save)
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            assertEquals("text after '$move'", oracle.text(expected.state), midend.textFormat())
        }

        // A board whose clues are all one digit has a narrower cell.
        val small = oracle.newGame("range", "3x3", "3")
        val smallMidend = Midend.newGame(Range, "3x3", "3")
        assertEquals("3x3 text", oracle.text(small.state), smallMidend.textFormat())
        assertEquals(
            "a single-digit board has a two-column cell",
            3 * 2 + 1,
            smallMidend.textFormat().lines().first().length,
        )

        // A two-digit clue widens every cell.
        assertEquals(
            "the 9x6 has a three-column cell",
            9 * 3 + 1,
            solvedMidend.textFormat().lines().first().length,
        )
    }

    /**
     * C's `midend_deserialise` validates long-term parameters with
     * `full = true`, so the oracle rejects a `1x3` save and cannot show
     * this text. The port accepts the game id form (`full = false`) and
     * the text is pinned here.
     */
    @Test
    fun `a 1x3 game id formats as text`() {
        val midend = Midend.fromGameId(Range, "1x3:a1a")
        assertEquals("1x3:a1a", midend.id())
        assertEquals(
            "+-+\n" +
                "| |\n" +
                "+-+\n" +
                "|1|\n" +
                "+-+\n" +
                "| |\n" +
                "+-+\n",
            midend.textFormat(),
        )
    }

    @Test
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "B,1,0",
            "B,1,0W,1,1",
            "W,1, 0",
            "B,1, 0",
            "B,+1,0",
            "B,01,0",
            "B,0,4294967296",
            "B,4294967297,0",
            "S",
            "SB,1,0",
            "",
        )
        for (move in accepted) {
            val save = historySave("9x6", DESC, listOf("MOVE" to move), 2)
            val expected = oracle.id(save)
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            assertEquals("id after '$move'", expected.id, midend.id())
            assertEquals("save after '$move'", expected.state, midend.serialise().serialise())
            assertEquals("status after '$move'", expected.status, midend.status())
            assertEquals("text after '$move'", oracle.text(expected.state), midend.textFormat())
        }

        val rejected = listOf(
            "B,1 ,0",
            "B,1,0 ",
            "B,1,0,",
            "B,1,0junk",
            "B,1,0B",
            "W, 1 , 0",
            "X,1,0",
            "b,1,0",
            "B,9,0",
            "B,-1,0",
            "Ws",
        )
        for (move in rejected) {
            val save = historySave("9x6", DESC, listOf("MOVE" to move), 2)
            val expected = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.fromSave(Range, SgtpSave.parse(save))
                null
            } catch (e: RuntimeException) {
                e.message
            }
            assertFalse("'$move' is rejected by C", expected == null)
            assertFalse("'$move' is rejected by the port", actual == null)
        }

        // The 32-bit wrap names (0,0) and (1,0).
        val wrap = Midend.fromSave(
            Range,
            SgtpSave.parse(historySave("9x6", DESC, listOf("MOVE" to "B,0,4294967296"), 2)),
        )
        assertEquals(Range.BLACK, (wrap.state as Range.State).grid[0])
        val wrap2 = Midend.fromSave(
            Range,
            SgtpSave.parse(historySave("9x6", DESC, listOf("MOVE" to "B,4294967297,0"), 2)),
        )
        assertEquals(Range.BLACK, (wrap2.state as Range.State).grid[9])
    }

    @Test
    fun `completion uses the black set only`() = OracleClient().use { oracle ->
        val blackSet = "B,0,5B,1,1B,1,6B,2,3B,2,5B,2,7B,3,0B,3,4B,4,7B,5,0"

        // The ten black cells of the answer win with undecided cells.
        val solved = oracle.id(historySave("9x6", DESC, listOf("MOVE" to blackSet), 2))
        assertEquals(1, solved.status)
        val solvedMidend = Midend.fromSave(
            Range,
            SgtpSave.parse(historySave("9x6", DESC, listOf("MOVE" to blackSet), 2)),
        )
        assertEquals(1, solvedMidend.status())
        assertEquals(
            "the port agrees on the black set",
            solved.state,
            solvedMidend.serialise().serialise(),
        )

        // Dots do not change the run lengths, so the same black set with
        // two extra dots wins as well, on a fresh save.
        val dotted = blackSet + "W,0,2W,1,0"
        assertEquals(1, oracle.id(historySave("9x6", DESC, listOf("MOVE" to dotted), 2)).status)
        assertEquals(
            1,
            Midend.fromSave(
                Range,
                SgtpSave.parse(historySave("9x6", DESC, listOf("MOVE" to dotted), 2)),
            ).status(),
        )

        // One black missing or one extra black is not a win.
        val missing = blackSet + "W,5,0"
        val extra = blackSet + "B,4,1"
        for (move in listOf(missing, extra)) {
            assertEquals(
                "C status for '$move'",
                0,
                oracle.id(historySave("9x6", DESC, listOf("MOVE" to move), 2)).status,
            )
            assertEquals(
                "port status for '$move'",
                0,
                Midend.fromSave(
                    Range,
                    SgtpSave.parse(historySave("9x6", DESC, listOf("MOVE" to move), 2)),
                ).status(),
            )
        }

        // The win is sticky: a dot after completion keeps status 1.
        val sticky = historySave("9x6", DESC, listOf("MOVE" to blackSet, "MOVE" to "W,0,2"), 3)
        assertEquals(1, oracle.id(sticky).status)
        assertEquals(1, Midend.fromSave(Range, SgtpSave.parse(sticky)).status())
    }

    @Test
    fun `changedState reports honest completions only`() {
        val midend = Midend.newGame(Range, "9x6", SEED)
        val ui = midend.ui
        val open = midend.state as Range.State
        val blackSet = "B,0,5B,1,1B,1,6B,2,3B,2,5B,2,7B,3,0B,3,4B,4,7B,5,0"
        val solved = Range.executeMove(open, blackSet) as Range.State
        assertTrue("an honest win raises the event", Range.changedState(ui, open, solved))

        val dotted = Range.executeMove(solved, "W,0,2") as Range.State
        assertFalse("a dot after the win raises nothing", Range.changedState(ui, solved, dotted))

        val cheated = Range.executeMove(open, "S" + blackSet) as Range.State
        assertTrue(cheated.wasSolved)
        assertTrue(cheated.hasCheated)
        assertFalse("a solve raises nothing", Range.changedState(ui, open, cheated))
    }

    @Test
    fun `undo and redo cross a completion`() = OracleClient().use { oracle ->
        val blackSet = "B,0,5B,1,1B,1,6B,2,3B,2,5B,2,7B,3,0B,3,4B,4,7B,5,0"
        val save = historySave("9x6", DESC, listOf("MOVE" to blackSet), 2)
        val midend = Midend.fromSave(Range, SgtpSave.parse(save))
        midend.takeCompletedEvent()

        assertTrue("undo", midend.undo())
        assertEquals(0, midend.status())
        assertFalse("an undo into an open state raises nothing", midend.takeCompletedEvent())
        assertEquals(oracle.undo(save).state, midend.serialise().serialise())

        assertTrue("redo", midend.redo())
        assertEquals(1, midend.status())
        assertTrue("a redo into the solved state raises the event", midend.takeCompletedEvent())
        assertEquals(oracle.redo(oracle.undo(save).state).state, midend.serialise().serialise())
    }

    @Test
    fun `solve matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in listOf("", "9x6", "12x8", "13x9", "16x11")) {
            val start = oracle.newGame("range", params, SEED)
            val expected = oracle.solve(start.state)
            val midend = Midend.fromSave(Range, SgtpSave.parse(start.state))
            midend.takeCompletedEvent()
            assertTrue("$params solves", midend.solve())
            assertEquals("$params solve save", expected.state, midend.serialise().serialise())
            assertEquals("$params solve id", expected.id, midend.id())
            assertEquals("$params solve status", 1, midend.status())
            assertFalse("$params solve raises no event", midend.takeCompletedEvent())
            assertEquals("$params solve text", oracle.text(expected.state), midend.textFormat())
            assertEquals(
                "$params solve is cheated",
                "SOLVE",
                SgtpSave.parse(midend.serialise().serialise()).records.last().key,
            )
        }
    }

    @Test
    fun `solve reads the first state after a move`() = OracleClient().use { oracle ->
        val start = oracle.newGame("range", "9x6", SEED)
        val moved = oracle.pointsSized(
            start.state,
            listOf(OracleClient.Point(96, 32, Buttons.LEFT_BUTTON)),
        )
        val expected = oracle.solve(moved.state.state)
        val midend = Midend.fromSave(Range, SgtpSave.parse(moved.state.state))
        assertTrue("a solve after a click", midend.solve())
        assertEquals(
            "the answer is the first state's answer",
            expected.state,
            midend.serialise().serialise(),
        )
        val solve = SgtpSave.parse(expected.state).value("SOLVE")!!
        assertTrue("the answer starts S", solve.startsWith("S"))
        assertTrue("the answer mentions moves", solve.length > 1)
    }

    @Test
    fun `an already solved first state gives the S record`() = OracleClient().use { oracle ->
        // Every cell is a clue whose run length equals its value, so the
        // first state has no empty cell and the solver returns no moves.
        val save = historySave("3x3", "5_5_5_5_5_5_5_5_5", emptyList(), 1)
        val expected = oracle.solve(save)
        assertEquals("S", SgtpSave.parse(expected.state).value("SOLVE"))
        val midend = Midend.fromSave(Range, SgtpSave.parse(save))
        midend.takeCompletedEvent()
        assertTrue(midend.solve())
        assertEquals(expected.state, midend.serialise().serialise())
        assertFalse(midend.takeCompletedEvent())
        assertEquals(1, midend.status())
    }

    @Test
    fun `hint returns the first solver move`() = OracleClient().use { oracle ->
        val start = oracle.newGame("range", "9x6", SEED)

        // A hint is an ordinary move: it counts in the history.
        assertEquals(
            oracle.keys(start.state, listOf('h'.code)).state,
            playKeys(start.state, listOf('h'.code)),
        )
        assertEquals(
            oracle.keys(start.state, listOf('H'.code)).state,
            playKeys(start.state, listOf('H'.code)),
        )
        val midend = Midend.fromSave(Range, SgtpSave.parse(start.state))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'h'.code, TILE))
        assertEquals(listOf("W,1,0"), SgtpSave.parse(midend.serialise().serialise()).values("MOVE"))

        // A hint after a move uses the current state.
        val afterClick = oracle.pointsSized(
            start.state,
            listOf(OracleClient.Point(96, 32, Buttons.LEFT_BUTTON)),
        )
        assertEquals(
            oracle.keys(afterClick.state.state, listOf('h'.code)).state,
            playKeys(afterClick.state.state, listOf('h'.code)),
        )

        // A full board has no hint.
        val allBlack = (0 until 9).joinToString("") { "B,${it / 3},${it % 3}" }
        val full = historySave("3x3", "i", listOf("MOVE" to allBlack), 2)
        assertEquals(full, oracle.keys(full, listOf('h'.code)).state)
        val fullMidend = Midend.fromSave(Range, SgtpSave.parse(full))
        assertEquals(
            KeyOutcome.Unused,
            fullMidend.processKey(0, 0, 'h'.code, TILE),
        )
        assertEquals("a hint on a full board adds no state", 2, fullMidend.stateCount)
    }

    @Test
    fun `requested keys and navigation match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("", "9x6", "12x8", "13x9", "16x11")) {
            val game = oracle.newGame("range", params, SEED)
            val expected = oracle.requestKeys(game.state)
            val actual = Range.requestKeys(Range.decodeParams(params))
            assertEquals("$params arrow mode", 3, expected.arrowMode)
            assertEquals("$params Kotlin arrow mode", ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
            assertEquals("$params no labels", emptyList<String>(), expected.labels.map { it.label })
            assertTrue("$params no labels", actual.labels.isEmpty())
        }

        // Arrow keys reveal the ring without moving the cursor, and a
        // click on a clue moves the cursor and hides it.
        val start = oracle.newGame("range", "9x6", SEED)
        assertEquals(
            oracle.keys(start.state, listOf(Buttons.CURSOR_DOWN)).state,
            playKeys(start.state, listOf(Buttons.CURSOR_DOWN)),
        )
        val midend = Midend.fromSave(Range, SgtpSave.parse(start.state))
        val ui = midend.ui as Range.Ui
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_DOWN, TILE))
        assertTrue("an arrow reveals the ring", ui.cursorShow)
        assertEquals("0,0", "${ui.r},${ui.c}")
        assertTrue(midend.cursorLocation(TILE) != null)
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, Buttons.CURSOR_DOWN or 0x1000, TILE))
    }

    private fun playKeys(state: String, sequence: List<Int>): String {
        val midend = Midend.fromSave(Range, SgtpSave.parse(state))
        for (key in sequence) midend.processKey(0, 0, key, TILE)
        return midend.serialise().serialise()
    }

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Range.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
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
        const val DESC = "7_5e4d5g8l7g9d11e8_13"
    }
}
