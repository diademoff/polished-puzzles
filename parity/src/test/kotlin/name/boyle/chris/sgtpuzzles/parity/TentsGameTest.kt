package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: tents.
 *
 * New games, the text format, the status, the move grammar, real
 * press/drag/release batches, the keyboard select cycles, undo and
 * redo, completion (including the sticky flag), and the solver.
 *
 * A generated Kotlin save has no AUXINFO, so the oracle comparison of a
 * new game is the id, the text, and the status. The corpus saves keep
 * their AUXINFO and are compared byte for byte in `TentsSaveTest`.
 */
class TentsGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        const val GOLDEN_DESC =
            "ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"
        const val GOLDEN_GRID = "ae__ijcecgbcd"

        val ANSWER = listOf(
            0 to 0, 6 to 0, 2 to 1, 0 to 2, 6 to 2, 3 to 3,
            1 to 4, 5 to 5, 0 to 6, 2 to 7, 4 to 7, 7 to 7,
        )

        const val SOLVE =
            "S;T0,0;T6,0;T2,1;T0,2;T6,2;T3,3;T1,4;T5,5;T0,6;T2,7;T4,7;T7,7"
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `new games, text and status match the oracle`() = OracleClient().use { oracle ->
        for (params in Tents.presets().map { it.params }) {
            val expected = oracle.newGame("tents", params, SEED)
            val actual = Midend.newGame(Tents, params, SEED)
            assertEquals("'$params' id", expected.id, actual.id())
            assertEquals("'$params' status", expected.status, actual.status())
            assertEquals("'$params' text", oracle.text(expected.state), actual.textFormat())
            assertEquals("'$params' status text", "", actual.statusText())
            assertFalse("'$params' has no status bar", Tents.wantsStatusBar)
            assertTrue("'$params' can solve", Tents.canSolve)
        }
    }

    @Test
    fun `the text format matches the C rectangle`() = OracleClient().use { oracle ->
        val save = corpus("8x8de")
        val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
        val text = midend.textFormat()
        assertEquals(721, text.length)
        assertEquals(oracle.text(save), text)
        assertEquals(
            "+---+---+---+---+---+---+---+---+---+\n" +
                "| . | T | . | . | . | . | . | T | 2 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| T | T | . | . | . | . | . | . | 1 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| . | . | . | T | . | . | . | . | 2 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| . | . | . | . | . | . | T | . | 1 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| . | . | T | . | . | . | . | . | 1 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| T | . | . | . | T | . | . | . | 1 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| . | . | . | . | T | . | . | T | 1 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| . | . | . | T | . | . | . | . | 3 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| 3 | 1 | 2 | 1 | 1 | 1 | 2 | 1 |    \n" +
                "+---+---+---+---+---+---+---+---+   \n",
            text,
        )

        // Three-digit numbers are written; 1000 and above leave a blank.
        val desc = "_".repeat(65) + ",100,999,1000" + ",0".repeat(13)
        val wide = saveText("8x8de", desc)
        assertEquals(oracle.text(wide), Midend.fromSave(Tents, SgtpSave.parse(wide)).textFormat())
    }

    @Test
    fun `the move grammar accepts and rejects the C forms`() = OracleClient().use { oracle ->
        val cases = listOf(
            "T0,0",
            "T+0,0",
            "T00,00",
            "T0, 0",
            "T0,0;",
            "B0,0;T0,0",
            "S",
            "S;T0,0",
            "T1,0",
            "T-1,0",
            "T0,0junk",
            ";T0,0",
            "T0,0T1,1",
            "T0,0,",
            "T0,0;;T2,0",
        )
        for (move in cases) {
            val save = saveText("8x8de", GOLDEN_DESC, listOf("MOVE" to move))
            val expected = try {
                oracle.id(save)
            } catch (e: OracleException) {
                null
            }
            val actual = try {
                Midend.fromSave(Tents, SgtpSave.parse(save))
            } catch (e: RuntimeException) {
                null
            }
            if (expected == null) {
                assertNull("'$move' is rejected", actual)
            } else {
                assertSame("'$move'", expected, requireNotNull(actual))
            }
        }
    }

    @Test
    fun `real press drag and release batches match the oracle`() =
        OracleClient().use { oracle ->
            val save = corpus("8x8de")
            val cases = listOf(
                // Horizontal drag from (0,0) to (2,0).
                listOf(
                    OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                    OracleClient.Point(96, 32, Buttons.LEFT_DRAG),
                    OracleClient.Point(96, 32, Buttons.LEFT_RELEASE),
                ) to "N0,0;N2,0",
                // Vertical drag from (0,0) to (0,2).
                listOf(
                    OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                    OracleClient.Point(32, 96, Buttons.LEFT_DRAG),
                    OracleClient.Point(32, 96, Buttons.LEFT_RELEASE),
                ) to "N0,0;N0,2",
                // A diagonal drag with a tie locks to the row.
                listOf(
                    OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                    OracleClient.Point(96, 96, Buttons.LEFT_DRAG),
                    OracleClient.Point(96, 96, Buttons.LEFT_RELEASE),
                ) to "N0,0;N2,0",
                // A right drag marks blanks only.
                listOf(
                    OracleClient.Point(32, 32, Buttons.RIGHT_BUTTON),
                    OracleClient.Point(96, 32, Buttons.RIGHT_DRAG),
                    OracleClient.Point(96, 32, Buttons.RIGHT_RELEASE),
                ) to "N0,0;N2,0",
            )
            for ((points, move) in cases) {
                val expected = oracle.pointsSized(save, points).state
                val actual = Midend.fromSave(Tents, SgtpSave.parse(save))
                for (point in points) {
                    assertTrue(
                        "$move outcome",
                        actual.processKey(point.x, point.y, point.button, TILE) !=
                            KeyOutcome.Unused,
                    )
                }
                assertSame(move, expected, actual)
                assertEquals("$move record", listOf(move), actual.serialise().values("MOVE"))
                assertEquals("$move adds one state", 2, actual.stateCount)
            }
        }

    @Test
    fun `a real batch changes the grid and an inert click changes nothing`() =
        OracleClient().use { oracle ->
            val save = corpus("8x8de")
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            val before = (midend.state as Tents.State).grid.copyOf()
            val points = listOf(
                OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(96, 32, Buttons.LEFT_DRAG),
                OracleClient.Point(96, 32, Buttons.LEFT_RELEASE),
            )
            for (point in points) midend.processKey(point.x, point.y, point.button, TILE)
            assertFalse(
                "the batch changes the grid",
                before.contentEquals((midend.state as Tents.State).grid),
            )
            assertEquals(2, midend.stateCount)
            assertEquals(1, midend.serialise().values("MOVE").size)

            // A click on the tree at (1,0): press and release are UI-only.
            val inert = Midend.fromSave(Tents, SgtpSave.parse(save))
            val inertBefore = (inert.state as Tents.State).grid.copyOf()
            val treeX = 1 * TILE + TILE
            val treeY = 0 * TILE + TILE
            for (button in listOf(Buttons.LEFT_BUTTON, Buttons.LEFT_RELEASE)) {
                val point = OracleClient.Point(treeX, treeY, button)
                val expected = oracle.pointsSized(save, listOf(point))
                assertEquals(
                    "tree outcome",
                    KeyOutcome.SomeEffect,
                    inert.processKey(point.x, point.y, point.button, TILE),
                )
                assertSame("tree press", expected.state, inert)
            }
            assertTrue(
                "the inert click keeps the grid",
                inertBefore.contentEquals((inert.state as Tents.State).grid),
            )
            assertEquals(1, inert.stateCount)
            assertTrue(inert.serialise().values("MOVE").isEmpty())
        }

    @Test
    fun `the select cycles and the letter keys match the oracle`() =
        OracleClient().use { oracle ->
            val save = corpus("8x8de")
            val sequences = listOf(
                listOf(Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_SELECT, 'T'.code),
                listOf(Buttons.CURSOR_SELECT, 'N'.code),
                listOf(Buttons.CURSOR_SELECT, 'B'.code),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT),
                listOf(
                    Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
                    Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
                ),
                listOf(
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_SELECT,
                    Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                ),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val actual = Midend.fromSave(Tents, SgtpSave.parse(save))
                for (key in sequence) actual.processKey(0, 0, key, TILE)
                assertSame("keys $sequence", expected, actual)
            }

            // The three-state mouse cycle on a blank cell: left goes
            // BLANK -> TENT -> NONTENT -> BLANK.
            val click = listOf(32, 32)
            fun clickPoints(): List<OracleClient.Point> = listOf(
                OracleClient.Point(click[0], click[1], Buttons.LEFT_BUTTON),
                OracleClient.Point(click[0], click[1], Buttons.LEFT_RELEASE),
            )
            val expectedFirst = oracle.pointsSized(save, clickPoints()).state
            assertEquals(
                listOf("T0,0"),
                SgtpSave.parse(expectedFirst.state).values("MOVE"),
            )
            val cycle = Midend.fromSave(Tents, SgtpSave.parse(save))
            for (point in clickPoints()) {
                cycle.processKey(point.x, point.y, point.button, TILE)
            }
            assertSame("first click", expectedFirst, cycle)
            for (point in clickPoints()) {
                cycle.processKey(point.x, point.y, point.button, TILE)
            }
            assertEquals(
                listOf("T0,0", "N0,0"),
                cycle.serialise().values("MOVE"),
            )
            for (point in clickPoints()) {
                cycle.processKey(point.x, point.y, point.button, TILE)
            }
            assertEquals(
                listOf("T0,0", "N0,0", "B0,0"),
                cycle.serialise().values("MOVE"),
            )

            // A right click cycles the other way.
            val right = listOf(
                OracleClient.Point(32, 32, Buttons.RIGHT_BUTTON),
                OracleClient.Point(32, 32, Buttons.RIGHT_RELEASE),
            )
            val expectedRight = oracle.pointsSized(save, right).state
            val rightMidend = Midend.fromSave(Tents, SgtpSave.parse(save))
            for (point in right) rightMidend.processKey(point.x, point.y, point.button, TILE)
            assertSame("right click", expectedRight, rightMidend)
            assertEquals(listOf("N0,0"), rightMidend.serialise().values("MOVE"))
        }

    @Test
    fun `a real win completes once and undo redo move the status`() =
        OracleClient().use { oracle ->
            val save = corpus("8x8de")
            val points = ArrayList<OracleClient.Point>()
            for ((x, y) in ANSWER) {
                val px = x * TILE + TILE
                val py = y * TILE + TILE
                points += OracleClient.Point(px, py, Buttons.LEFT_BUTTON)
                points += OracleClient.Point(px, py, Buttons.LEFT_RELEASE)
            }
            val expected = oracle.pointsSized(save, points)
            assertEquals(1, expected.state.status)
            val expectedState = expected.state

            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            for (point in points) {
                assertEquals(
                    "win click",
                    KeyOutcome.SomeEffect,
                    midend.processKey(point.x, point.y, point.button, TILE),
                )
            }
            assertSame("win", expectedState, midend)
            assertEquals(1, midend.status())
            assertEquals(12, midend.serialise().values("MOVE").size)
            assertTrue("the win raises the event", midend.takeCompletedEvent())
            assertFalse(midend.takeCompletedEvent())

            // The flag is sticky: one more click turns a tent into a
            // non-tent and keeps status 1.
            val postWin = listOf(
                OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(32, 32, Buttons.LEFT_RELEASE),
            )
            val after = oracle.pointsSized(expected.state.state, postWin)
            assertEquals(1, after.state.status)
            val afterState = after.state
            for (point in postWin) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            assertSame("post-win move", afterState, midend)
            assertEquals(1, midend.status())
            assertFalse("a post-win move raises no event", midend.takeCompletedEvent())
            assertEquals(13, midend.serialise().values("MOVE").size)

            // Undo across the win clears the status; redo restores it
            // and raises the event again. The first undo lands on the
            // winning state, which is still completed by the sticky
            // flag; the second reaches the open state before the win.
            assertTrue(midend.undo())
            assertEquals(1, midend.status())
            val stillCompleted = oracle.undo(after.state.state)
            assertSame("undo post-win", stillCompleted, midend)
            assertTrue(midend.undo())
            assertEquals(0, midend.status())
            val undone = oracle.undo(stillCompleted.state)
            assertSame("undo win", undone, midend)
            assertTrue(midend.redo())
            assertEquals(1, midend.status())
            assertTrue(midend.takeCompletedEvent())
            val redone = oracle.redo(undone.state)
            assertSame("redo win", redone, midend)
            assertTrue(midend.redo())
            assertSame("redo post-win", afterState, midend)
        }

    @Test
    fun `the solver matches the oracle with and without AUXINFO`() =
        OracleClient().use { oracle ->
            // With the generated AUXINFO.
            val withAux = oracle.newGame("tents", "8x8de", SEED).state
            val expected = oracle.solve(withAux)
            val solvedText = SgtpSave.parse(expected.state).values("SOLVE").single()
            assertEquals(SOLVE, solvedText)
            assertEquals(1, expected.status)

            val midend = Midend.fromSave(Tents, SgtpSave.parse(withAux))
            assertTrue(midend.solve())
            assertSame("solve with aux", expected, midend)
            assertEquals(listOf(SOLVE), midend.serialise().values("SOLVE"))
            assertEquals(1, midend.status())
            assertFalse("a solve raises no event", midend.takeCompletedEvent())

            // Without AUXINFO the solver runs on the initial grid.
            val withoutAux = stripAux(withAux)
            val expectedSolver = oracle.solve(withoutAux)
            assertEquals(SOLVE, SgtpSave.parse(expectedSolver.state).values("SOLVE").single())
            val solverMidend = Midend.fromSave(Tents, SgtpSave.parse(withoutAux))
            assertTrue(solverMidend.solve())
            assertSame("solve without aux", expectedSolver, solverMidend)

            // A wrong user move does not change the answer.
            val wrong = saveText(
                "8x8de",
                GOLDEN_DESC,
                listOf("MOVE" to "T2,0"),
            )
            val expectedWrong = oracle.solve(wrong)
            assertEquals(SOLVE, SgtpSave.parse(expectedWrong.state).values("SOLVE").single())
            val wrongMidend = Midend.fromSave(Tents, SgtpSave.parse(wrong))
            assertTrue(wrongMidend.solve())
            assertSame("solve after a wrong move", expectedWrong, wrongMidend)
        }

    @Test
    fun `every preset solves with the oracle answer`() = OracleClient().use { oracle ->
        for (params in Tents.presets().map { it.params }) {
            val expected = oracle.solve(oracle.newGame("tents", params, SEED).state)
            val expectedSolve = SgtpSave.parse(expected.state).values("SOLVE").single()
            assertEquals("'$params' oracle status", 1, expected.status)

            val midend = Midend.newGame(Tents, params, SEED)
            assertTrue("'$params' solve", midend.solve())
            assertEquals(
                "'$params' SOLVE",
                expectedSolve,
                midend.serialise().values("SOLVE").single(),
            )
            assertEquals("'$params' status", 1, midend.status())
            assertEquals("'$params' text", oracle.text(expected.state), midend.textFormat())
        }
    }

    @Test
    fun `the two solver error strings are the C messages`() = OracleClient().use { oracle ->
        val cases = listOf(
            "8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0",
            "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,8",
            "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1",
        )
        for (numbers in cases) {
            val save = saveText("8x8de", "$GOLDEN_GRID,$numbers")
            val expected = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            assertFalse("'$numbers' fails", midend.solve())
            assertEquals("this save has no history", 1, midend.stateCount)
            assertTrue(
                "one of the two messages",
                expected == "This puzzle is not self-consistent" ||
                    expected == "Unable to find a unique solution for this puzzle",
            )
        }
    }

    @Test
    fun `a solve applies but is not a win with zero clues`() = OracleClient().use { oracle ->
        val zeros = "0".repeat(1) + ",0".repeat(15)
        val save = saveText(
            "8x8de",
            "$GOLDEN_GRID,$zeros",
            auxInfo = corpusValue("8x8de", "AUXINFO"),
        )
        val expected = oracle.solve(save)
        assertEquals(SOLVE, SgtpSave.parse(expected.state).values("SOLVE").single())
        assertEquals(0, expected.status)

        val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
        assertTrue(midend.solve())
        assertSame("zero-clue solve", expected, midend)
        assertEquals(0, midend.status())
        assertEquals(SOLVE, midend.serialise().values("SOLVE").single())
    }

    @Test
    fun `requestKeys is arrow mode 3 with no labels`() = OracleClient().use { oracle ->
        for (params in Tents.presets().map { it.params }) {
            val state = oracle.newGame("tents", params, SEED).state
            for (fullCursor in listOf(false, true)) {
                val expected = oracle.requestKeys(state, fullCursor)
                val actual = Tents.requestKeys(
                    Tents.decodeParams(params),
                    fullCursor,
                )
                assertEquals(
                    "'$params' $fullCursor arrow mode",
                    if (fullCursor) 1 else 3,
                    expected.arrowMode,
                )
                assertEquals(
                    "'$params' $fullCursor Kotlin",
                    if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
                    actual.arrowMode,
                )
                assertTrue("'$params' $fullCursor labels", expected.labels.isEmpty())
                assertTrue("'$params' $fullCursor", actual.labels.isEmpty())
            }
        }
    }

    @Test
    fun `the full and short encodings split the save parameters`() =
        OracleClient().use { oracle ->
            val p = Tents.Params(8, 8, 1)
            assertEquals("8x8", Tents.encodeParams(p, false))
            assertEquals("8x8dt", Tents.encodeParams(p, true))

            for ((params, longTerm) in listOf(
                "8x8dt" to "8x8de",
                "10x10dt" to "10x10de",
                "15x15dt" to "15x15de",
            )) {
                val midend = Midend.newGame(Tents, params, SEED)
                val save = midend.serialise()
                assertEquals("$params PARAMS", longTerm, save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                assertEquals("$params id prefix", longTerm.substringBefore('d'),
                    midend.id().substringBefore(':'))
                // C reads the Kotlin save and keeps the split.
                val loaded = oracle.id(save.serialise())
                assertEquals("$params C id", midend.id(), loaded.id)
                assertEquals("$params C PARAMS", longTerm,
                    SgtpSave.parse(loaded.state).value("PARAMS"))
                assertEquals("$params C CPARAMS", params,
                    SgtpSave.parse(loaded.state).value("CPARAMS"))
            }
        }

    private fun corpus(params: String): String =
        java.io.File(testdataRoot(), "saves/initial/tents/$params.sgtp").readText()

    private fun corpusValue(params: String, key: String): String =
        requireNotNull(
            SgtpSave.parse(corpus(params)).value(key),
        ) { "the corpus save for '$params' has no $key" }

    private fun testdataRoot(): java.io.File = java.io.File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    /** Drops SEED and AUXINFO, so C has to run the solver. */
    private fun stripAux(save: String): String =
        SgtpSave.of(
            SgtpSave.parse(save).records.filter {
                it.key != "AUXINFO" && it.key != "SEED"
            },
        ).serialise()

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>> = emptyList(),
        auxInfo: String? = null,
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Tents.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        if (auxInfo != null) records += "AUXINFO" to auxInfo
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
