package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: magnets.
 *
 * New games, text, status, the move grammar, real press/release clicks
 * on cells and clue bands, the magnet and neutral cycles, the `D` clue
 * toggle, cursor keys and the two select buttons, undo and redo, an
 * honest completion (including the sticky flag), the solver with and
 * without `AUXINFO`, the request-keys mode, and the current-key-label
 * helper. The vacuity guards are here too: a real change updates the
 * board and the move count, an inert click changes neither.
 */
class MagnetsGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        const val GOLDEN_6X5DE =
            "221211,12231,302112,11313,LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB"

        const val SOLVE_6X5DE =
            "S;-0,0;+0,1;-0,2;+0,3;-0,4;+1,0;.1,1;.1,2;.1,3;+1,4;" +
                ".2,0;-2,1;+2,2;.2,3;-2,4;.3,0;+3,1;-3,2;+3,3;.3,4;" +
                ".4,0;.4,1;+4,2;-4,3;.4,4;.5,0;.5,1;-5,2;+5,3;-5,4;"

        const val SOLVE_3X3DE =
            "S;+0,0;-0,1;-1,0;+1,1;-1,2;+2,0;-2,1;+2,2;"

    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun midend(save: String): Midend =
        Midend.fromSave(Magnets, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `new games, text and status match the oracle`() = OracleClient().use { oracle ->
        for (params in Magnets.presets().map { it.params }) {
            val expected = oracle.newGame("magnets", params, SEED)
            val actual = Midend.newGame(Magnets, params, SEED)
            assertEquals("'$params' id", expected.id, actual.id())
            assertEquals("'$params' status", 0, actual.status())
            assertEquals("'$params' text", oracle.text(expected.state), actual.textFormat())
            assertEquals("'$params' status text", "", actual.statusText())
            assertFalse("'$params' has no status bar", Magnets.wantsStatusBar)
            assertTrue("'$params' can solve", Magnets.canSolve)
            assertTrue("'$params' can format as text", Magnets.canFormatAsText)
            assertEquals("'$params' history", 1, actual.stateCount)
            assertTrue("'$params' AUXINFO", actual.serialise().value("AUXINFO") != null)
        }
    }

    @Test
    fun `the text format matches the C rectangle`() = OracleClient().use { oracle ->
        val save = corpus("6x5de")
        val midend = midend(save)
        val text = midend.textFormat()
        assertEquals(oracle.text(save), text)
        assertEquals(
            "+ 2 2 1 2 1 1\n" +
                " +-----------+\n" +
                "1|   |   |   |1\n" +
                " |-+-+-+-+-+-|\n" +
                "2| | |   |   |1\n" +
                " | + +-+-+-+-|\n" +
                "2| | |   |   |3\n" +
                " |-+-+-+-+-+-|\n" +
                "3| |   |   | |1\n" +
                " | +-+-+-+-+ |\n" +
                "1| |   |   | |3\n" +
                " +-----------+\n" +
                "  3 0 2 1 1 2 -\n",
            text,
        )

        // A strip-clues board has `.` in the count runs. C draws no
        // number for a missing clue, but the text still writes one.
        val stripped = corpus("6x5dtS")
        val stripText = midend(stripped).textFormat()
        assertEquals(oracle.text(stripped), stripText)
        assertTrue(stripText.contains(".| | | | |   |."))
        assertTrue(stripText.contains("+ . . 2 . . 2\n"))

        // A set neutral is `*`, a singleton `#`, and an empty cell a
        // space. The 3x3 board has one singleton at (0,2).
        val three = corpusOrNew(oracle, "3x3de")
        val threeText = midend(three).textFormat()
        assertEquals(oracle.text(three), threeText)
        assertTrue(threeText.contains("#"))
    }

    @Test
    fun `the move grammar accepts and rejects the C forms`() = OracleClient().use { oracle ->
        val cases = listOf(
            "+0,0",
            " 0,0",
            ".0,0",
            "?0,0",
            "S",
            "S;",
            "S;+0,0",
            "+0,0;",
            "D0,-1",
            "D-1,0",
            "D0,5",
            "D6,0",
            "+0,0;+0,1",
            "+ 0, 0",
            "+00,00",
            "D0,-1;D0,-1",
            "+0,0junk",
            ";+0,0",
            "D-1,-1",
            "D6,5",
            "+6,0",
            "+0",
            "+0,",
            "S;;",
            "S; ",
            "+0,0;;+0,1",
            "",
        )
        for (move in cases) {
            val save = saveText("6x5de", GOLDEN_6X5DE, listOf("MOVE" to move))
            val expected = try {
                oracle.id(save)
            } catch (e: OracleException) {
                null
            }
            val actual = try {
                Midend.fromSave(Magnets, SgtpSave.parse(save))
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
    fun `the left and right cycles and the clue toggle match the oracle`() =
        OracleClient().use { oracle ->
            val save = corpus("6x5dt")

            // The left cycle: empty -> positive -> negative -> empty.
            var expected = save
            val actual = midend(save)
            for ((round, move) in listOf("+0,0", "-0,0", " 0,0", "+0,0").withIndex()) {
                val points = click(0, 0, Buttons.LEFT_BUTTON)
                expected = play(oracle, "$round", expected, points, actual)
                assertEquals("round $round record", listOf(move), lastMove(expected))
            }
            assertEquals(4, actual.serialise().values("MOVE").size)

            // The right cycle: empty -> neutral -> not neutral -> empty.
            val right = midend(save)
            var expectedRight = save
            for ((round, move) in listOf(".0,0", "?0,0", " 0,0", ".0,0").withIndex()) {
                val points = click(0, 0, Buttons.RIGHT_BUTTON)
                expectedRight = play(oracle, "right $round", expectedRight, points, right)
                assertEquals("right round $round record", listOf(move), lastMove(expectedRight))
            }

            // A magnet cannot cycle through neutral, and a set neutral
            // cannot cycle through a magnet.
            val leftThenRight = midend(save)
            play(oracle, "left", save, click(0, 0, Buttons.LEFT_BUTTON), leftThenRight)
            for (point in click(0, 0, Buttons.RIGHT_BUTTON)) {
                assertEquals(
                    "right on a magnet",
                    KeyOutcome.Unused,
                    leftThenRight.processKey(point.x, point.y, point.button, TILE),
                )
            }
            assertEquals(1, leftThenRight.serialise().values("MOVE").size)

            // The four clue bands, at the measured coordinates.
            val clues = listOf(
                Triple(52, 20, "D0,-1"),
                Triple(20, 52, "D-1,0"),
                Triple(52, 212, "D0,5"),
                Triple(244, 52, "D6,0"),
            )
            for ((x, y, move) in clues) {
                val points = listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                )
                val clue = midend(save)
                val state = play(oracle, move, save, points, clue)
                assertEquals("$move record", listOf(move), lastMove(state))
                assertEquals("$move status", 0, clue.status())
                assertTrue("$move adds one state", clue.stateCount == 2)
            }

            // A corner is not a clue, and the middle button is unused.
            for (points in listOf(
                listOf(
                    OracleClient.Point(20, 20, Buttons.LEFT_BUTTON),
                    OracleClient.Point(20, 20, Buttons.LEFT_RELEASE),
                ),
                listOf(OracleClient.Point(52, 52, Buttons.MIDDLE_BUTTON)),
            )) {
                val inert = midend(save)
                for (point in points) {
                    assertEquals(
                        "inert outcome",
                        KeyOutcome.Unused,
                        inert.processKey(point.x, point.y, point.button, TILE),
                    )
                }
                assertEquals(1, inert.stateCount)
                assertTrue(inert.serialise().values("MOVE").isEmpty())
            }

            // A singleton has no cycle. The 3x3 board has one at (0,2).
            val three = corpusOrNew(oracle, "3x3de")
            for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON)) {
                val inert = midend(three)
                val before = (inert.state as Magnets.State).grid.copyOf()
                for (point in listOf(
                    OracleClient.Point(52, 116, button),
                    OracleClient.Point(52, 116, button + 6),
                )) {
                    assertEquals(
                        "singleton outcome",
                        KeyOutcome.Unused,
                        inert.processKey(point.x, point.y, point.button, TILE),
                    )
                }
                assertTrue("singleton grid", before.contentEquals((inert.state as Magnets.State).grid))
                assertEquals(1, inert.stateCount)
            }
        }

    @Test
    fun `a magnet and a clue toggle change the state and an inert click does not`() =
        OracleClient().use { oracle ->
            val save = corpus("6x5dt")

            // A real magnet click changes the grid and adds one move.
            val magnet = midend(save)
            val before = (magnet.state as Magnets.State).grid.copyOf()
            val flagsBefore = (magnet.state as Magnets.State).flags.copyOf()
            for ((index, point) in click(0, 0, Buttons.LEFT_BUTTON).withIndex()) {
                val outcome = magnet.processKey(point.x, point.y, point.button, TILE)
                if (index == 0) assertEquals("magnet outcome", KeyOutcome.SomeEffect, outcome)
            }
            val after = magnet.state as Magnets.State
            assertFalse("the grid changes", before.contentEquals(after.grid))
            assertFalse("the flags change", flagsBefore.contentEquals(after.flags))
            assertEquals(2, magnet.stateCount)
            assertEquals(1, magnet.serialise().values("MOVE").size)
            assertSame("magnet click", oracle.pointsSized(
                save,
                click(0, 0, Buttons.LEFT_BUTTON),
            ).state, magnet)

            // A clue toggle changes the shading and adds one move, but
            // not a grid cell.
            val clue = midend(save)
            val gridBefore = (clue.state as Magnets.State).grid.copyOf()
            val countsBefore = (clue.state as Magnets.State).countsDone.copyOf()
            val cluePoints = listOf(
                OracleClient.Point(52, 20, Buttons.LEFT_BUTTON),
                OracleClient.Point(52, 20, Buttons.LEFT_RELEASE),
            )
            for ((index, point) in cluePoints.withIndex()) {
                val outcome = clue.processKey(point.x, point.y, point.button, TILE)
                if (index == 0) assertEquals("clue outcome", KeyOutcome.SomeEffect, outcome)
            }
            val clueState = clue.state as Magnets.State
            assertTrue("the grid stays", gridBefore.contentEquals(clueState.grid))
            assertFalse("the shading changes", countsBefore.contentEquals(clueState.countsDone))
            assertEquals(2, clue.stateCount)
            assertEquals(listOf("D0,-1"), clue.serialise().values("MOVE"))

            // An inert click changes neither the state nor the count.
            val inert = midend(save)
            val inertGrid = (inert.state as Magnets.State).grid.copyOf()
            val inertCounts = (inert.state as Magnets.State).countsDone.copyOf()
            for (point in listOf(
                OracleClient.Point(20, 20, Buttons.LEFT_BUTTON),
                OracleClient.Point(20, 20, Buttons.LEFT_RELEASE),
            )) {
                assertEquals(
                    "inert outcome",
                    KeyOutcome.Unused,
                    inert.processKey(point.x, point.y, point.button, TILE),
                )
            }
            assertTrue((inert.state as Magnets.State).grid.contentEquals(inertGrid))
            assertTrue((inert.state as Magnets.State).countsDone.contentEquals(inertCounts))
            assertEquals(1, inert.stateCount)
            assertTrue(inert.serialise().values("MOVE").isEmpty())
        }

    @Test
    fun `cursor keys and the two select buttons match the oracle`() =
        OracleClient().use { oracle ->
            val save = corpus("6x5dt")
            val sequences = listOf(
                listOf(Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_SELECT, '1'.code),
                listOf(Buttons.CURSOR_SELECT, 'a'.code),
                listOf(Buttons.CURSOR_SELECT, '\b'.code),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val actual = midend(save)
                for (key in sequence) actual.processKey(0, 0, key, TILE)
                assertSame("keys $sequence", expected, actual)
            }

            // A cursor select hides on a board click and stays on a clue
            // click: the clue move does not touch `cur_visible`.
            val onCell = midend(save)
            for (key in listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT)) {
                onCell.processKey(0, 0, key, TILE)
            }
            assertEquals(1, onCell.serialise().values("MOVE").size)
            onCell.processKey(52, 52, Buttons.LEFT_BUTTON, TILE)
            assertEquals(2, onCell.serialise().values("MOVE").size)

            val onClue = midend(save)
            for (key in listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT)) {
                onClue.processKey(0, 0, key, TILE)
            }
            for (point in listOf(
                OracleClient.Point(52, 20, Buttons.LEFT_BUTTON),
                OracleClient.Point(52, 20, Buttons.LEFT_RELEASE),
            )) {
                onClue.processKey(point.x, point.y, point.button, TILE)
            }
            assertEquals(2, onClue.serialise().values("MOVE").size)
        }

    @Test
    fun `a real win completes once, undoes and redoes`() = OracleClient().use { oracle ->
        val save = corpus("6x5de")
        val start = midend(save)
        val points = winPoints(start.state as Magnets.State, SOLVE_6X5DE)
        val expected = oracle.pointsSized(save, points)
        assertEquals(1, expected.state.status)

        val midend = start
        for ((index, point) in points.withIndex()) {
            val outcome = midend.processKey(point.x, point.y, point.button, TILE)
            if (point.button == Buttons.LEFT_BUTTON || point.button == Buttons.RIGHT_BUTTON) {
                assertEquals("win click $index", KeyOutcome.SomeEffect, outcome)
            }
        }
        assertSame("win", expected.state, midend)
        assertEquals(1, midend.status())
        assertTrue("the win raises the event", midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())

        // The flag is sticky: one more click keeps status 1.
        val post = click(0, 0, Buttons.LEFT_BUTTON)
        val after = oracle.pointsSized(expected.state.state, post).state
        assertEquals(1, after.status)
        for (point in post) midend.processKey(point.x, point.y, point.button, TILE)
        assertSame("post-win move", after, midend)
        assertEquals(1, midend.status())
        assertFalse("a post-win move raises no event", midend.takeCompletedEvent())

        // Undo across the win clears the status; redo restores it and
        // raises the event again.
        assertTrue(midend.undo())
        assertEquals(1, midend.status())
        assertSame("undo post-win", oracle.undo(after.state), midend)
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        val undone = oracle.undo(oracle.undo(after.state).state)
        assertSame("undo win", undone, midend)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        val redone = oracle.redo(undone.state)
        assertSame("redo win", redone, midend)
    }

    @Test
    fun `the solver works with and without AUXINFO`() = OracleClient().use { oracle ->
        val withAux = oracle.newGame("magnets", "6x5de", SEED).state
        val expected = oracle.solve(withAux)
        assertEquals(SOLVE_6X5DE, SgtpSave.parse(expected.state).values("SOLVE").single())
        assertEquals(1, expected.status)

        val midend = midend(withAux)
        assertTrue(midend.solve())
        assertSame("solve with aux", expected, midend)
        assertFalse("a solve raises no event", midend.takeCompletedEvent())
        assertEquals(1, midend.status())

        // Without AUXINFO the first attempt runs on the current state.
        val withoutAux = stripAux(withAux)
        val expectedSolver = oracle.solve(withoutAux)
        assertEquals(
            SOLVE_6X5DE,
            SgtpSave.parse(expectedSolver.state).values("SOLVE").single(),
        )
        val solverMidend = midend(withoutAux)
        assertTrue(solverMidend.solve())
        assertSame("solve without aux", expectedSolver, solverMidend)

        // After a wrong move the fallback runs on the decoded initial
        // state and still produces the full answer.
        val wrong = saveText("6x5de", GOLDEN_6X5DE, listOf("MOVE" to "+0,0"))
        val expectedWrong = oracle.solve(wrong)
        assertEquals(
            SOLVE_6X5DE,
            SgtpSave.parse(expectedWrong.state).values("SOLVE").single(),
        )
        val wrongMidend = midend(wrong)
        assertTrue(wrongMidend.solve())
        assertSame("solve after a wrong move", expectedWrong, wrongMidend)

        // A move that already matches the answer shortens the diff.
        val matched = saveText("6x5de", GOLDEN_6X5DE, listOf("MOVE" to "+2,2"))
        val expectedMatched = oracle.solve(matched)
        val matchedMove = SgtpSave.parse(expectedMatched.state).values("SOLVE").single()
        assertTrue(matchedMove.length < SOLVE_6X5DE.length)
        val matchedMidend = midend(matched)
        assertTrue(matchedMidend.solve())
        assertEquals(matchedMove, matchedMidend.serialise().values("SOLVE").single())

        // A solve of an already solved board is `S;` and appends a
        // second SOLVE record.
        val twice = oracle.solve(expected.state)
        assertEquals("S;", SgtpSave.parse(twice.state).values("SOLVE").last())
        val twiceMidend = midend(withAux)
        assertTrue(twiceMidend.solve())
        assertTrue(twiceMidend.solve())
        assertEquals("S;", twiceMidend.serialise().values("SOLVE").last())

        // The singleton 3x3 board skips cell (0,2) in the diff.
        val three = oracle.newGame("magnets", "3x3de", SEED).state
        val expectedThree = oracle.solve(three)
        assertEquals(
            SOLVE_3X3DE,
            SgtpSave.parse(expectedThree.state).values("SOLVE").single(),
        )
        val threeMidend = midend(three)
        assertTrue(threeMidend.solve())
        assertSame("3x3 solve", expectedThree, threeMidend)
    }

    @Test
    fun `every preset solves with the oracle answer`() = OracleClient().use { oracle ->
        for (params in Magnets.presets().map { it.params }) {
            val expected = oracle.solve(oracle.newGame("magnets", params, SEED).state)
            val expectedSolve = SgtpSave.parse(expected.state).values("SOLVE").single()
            assertEquals("'$params' oracle status", 1, expected.status)

            val midend = Midend.newGame(Magnets, params, SEED)
            assertTrue("'$params' solve", midend.solve())
            assertEquals(
                "'$params' SOLVE",
                expectedSolve,
                midend.serialise().values("SOLVE").single(),
            )
            assertEquals("'$params' status", 1, midend.status())
        }
    }

    @Test
    fun `requestKeys is arrow mode 3 with no labels`() = OracleClient().use { oracle ->
        for (params in Magnets.presets().map { it.params }) {
            val state = oracle.newGame("magnets", params, SEED).state
            for (fullCursor in listOf(false, true)) {
                val expected = oracle.requestKeys(state, fullCursor)
                val actual = Magnets.requestKeys(
                    Magnets.decodeParams(params),
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
                assertTrue("'$params' $fullCursor Kotlin labels", actual.labels.isEmpty())
            }
        }
    }

    @Test
    fun `the current key labels follow the C table`() = OracleClient().use { oracle ->
        val save = corpus("6x5dt")
        val midend = midend(save)
        val state = midend.state as Magnets.State
        val ui = Magnets.Ui()
        ui.curX = 0
        ui.curY = 0
        ui.curVisible = true

        assertEquals("+", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("X", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // Left click: empty -> positive.
        apply(midend, click(0, 0, Buttons.LEFT_BUTTON))
        var next = midend.state as Magnets.State
        assertEquals("-", Magnets.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT))
        assertEquals("", Magnets.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT2))

        // A second left click: positive -> negative.
        apply(midend, click(0, 0, Buttons.LEFT_BUTTON))
        next = midend.state as Magnets.State
        assertEquals("Clear", Magnets.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT))
        assertEquals("", Magnets.currentKeyLabel(ui, next, Buttons.CURSOR_SELECT2))

        // A fresh board: right click -> set neutral.
        val neutral = midend(corpus("6x5dt"))
        apply(neutral, click(0, 0, Buttons.RIGHT_BUTTON))
        var neutralState = neutral.state as Magnets.State
        assertEquals("", Magnets.currentKeyLabel(ui, neutralState, Buttons.CURSOR_SELECT))
        assertEquals("?", Magnets.currentKeyLabel(ui, neutralState, Buttons.CURSOR_SELECT2))

        // A second right click: set neutral -> not neutral.
        apply(neutral, click(0, 0, Buttons.RIGHT_BUTTON))
        neutralState = neutral.state as Magnets.State
        assertEquals("+", Magnets.currentKeyLabel(ui, neutralState, Buttons.CURSOR_SELECT))
        assertEquals("Clear", Magnets.currentKeyLabel(ui, neutralState, Buttons.CURSOR_SELECT2))

        // A third right click: not neutral -> empty.
        apply(neutral, click(0, 0, Buttons.RIGHT_BUTTON))
        neutralState = neutral.state as Magnets.State
        assertEquals("X", Magnets.currentKeyLabel(ui, neutralState, Buttons.CURSOR_SELECT2))

        // A hidden cursor has no label.
        ui.curVisible = false
        assertEquals("", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // The singleton label is `?`, but the key is a no-op.
        val three = midend(corpusOrNew(oracle, "3x3de"))
        val threeState = three.state as Magnets.State
        val threeUi = Magnets.Ui()
        threeUi.curX = 0
        threeUi.curY = 2
        threeUi.curVisible = true
        assertEquals("", Magnets.currentKeyLabel(threeUi, threeState, Buttons.CURSOR_SELECT))
        assertEquals("?", Magnets.currentKeyLabel(threeUi, threeState, Buttons.CURSOR_SELECT2))
        assertEquals(KeyOutcome.SomeEffect, three.processKey(0, 0, Buttons.CURSOR_DOWN, TILE))
        assertEquals(KeyOutcome.SomeEffect, three.processKey(0, 0, Buttons.CURSOR_DOWN, TILE))
        assertEquals(
            KeyOutcome.Unused,
            three.processKey(0, 0, Buttons.CURSOR_SELECT2, TILE),
        )
        assertEquals(
            KeyOutcome.Unused,
            three.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertEquals(1, three.stateCount)

        // An out-of-grid cursor is a defined empty label.
        val outside = Magnets.Ui()
        outside.curX = 99
        outside.curVisible = true
        assertEquals("", Magnets.currentKeyLabel(outside, state, Buttons.CURSOR_SELECT))
    }

    private fun apply(midend: Midend, points: List<OracleClient.Point>) {
        for (point in points) midend.processKey(point.x, point.y, point.button, 32)
    }

    private fun click(x: Int, y: Int, button: Int): List<OracleClient.Point> {
        val px = (x + 1) * TILE + TILE / 8 + TILE / 2
        val py = (y + 1) * TILE + TILE / 8 + TILE / 2
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            else -> Buttons.RIGHT_RELEASE
        }
        return listOf(
            OracleClient.Point(px, py, button),
            OracleClient.Point(px, py, release),
        )
    }

    /** Applies [points] to the oracle and to [midend], and returns the new save. */
    private fun play(
        oracle: OracleClient,
        label: String,
        save: String,
        points: List<OracleClient.Point>,
        midend: Midend,
    ): String {
        val expected = oracle.pointsSized(save, points)
        for ((index, point) in points.withIndex()) {
            val outcome = midend.processKey(point.x, point.y, point.button, TILE)
            if (index == 0) assertEquals("$label outcome", KeyOutcome.SomeEffect, outcome)
        }
        assertSame(label, expected.state, midend)
        return expected.state.state
    }

    private fun lastMove(save: String): List<String> =
        SgtpSave.parse(save).values("MOVE").takeLast(1)

    /**
     * Clicks for the answer of [solve]: one end of every domino, with
     * the value the answer gives. A polar domino uses its positive end,
     * so one left click sets both ends; a neutral domino is one right
     * click.
     */
    private fun winPoints(state: Magnets.State, solve: String): List<OracleClient.Point> {
        val w = state.width
        val entries = solve.removePrefix("S;").split(';').filter { it.isNotEmpty() }.map {
            val c = it[0]
            val (x, y) = it.substring(1).split(',').map { n -> n.toInt() }
            Triple(x, y, c)
        }
        // The representative of a domino is its lower index.
        fun mark(index: Int): Int = minOf(index, state.dominoOf(index))

        val chosen = LinkedHashMap<Int, Pair<Char, Int>>()
        for (entry in entries) {
            if (entry.third != '+') continue
            val index = entry.second * w + entry.first
            chosen.getOrPut(mark(index)) { entry.third to index }
        }
        for (entry in entries) {
            val index = entry.second * w + entry.first
            chosen.getOrPut(mark(index)) { entry.third to index }
        }

        val points = ArrayList<OracleClient.Point>()
        for ((c, index) in chosen.values) {
            val px = ((index % w) + 1) * TILE + TILE / 8 + TILE / 2
            val py = ((index / w) + 1) * TILE + TILE / 8 + TILE / 2
            when (c) {
                '+' -> {
                    points += OracleClient.Point(px, py, Buttons.LEFT_BUTTON)
                    points += OracleClient.Point(px, py, Buttons.LEFT_RELEASE)
                }

                '-' -> {
                    repeat(2) {
                        points += OracleClient.Point(px, py, Buttons.LEFT_BUTTON)
                        points += OracleClient.Point(px, py, Buttons.LEFT_RELEASE)
                    }
                }

                '.' -> {
                    points += OracleClient.Point(px, py, Buttons.RIGHT_BUTTON)
                    points += OracleClient.Point(px, py, Buttons.RIGHT_RELEASE)
                }
            }
        }
        return points
    }

    private fun corpus(params: String): String =
        File(testdata, "saves/initial/magnets/$params.sgtp").readText()

    private fun corpusOrNew(oracle: OracleClient, params: String): String {
        val file = File(testdata, "saves/initial/magnets/$params.sgtp")
        return if (file.exists()) file.readText() else oracle.newGame("magnets", params, SEED).state
    }

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
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Magnets.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
