package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Level: semantic. Game: blackbox.
 *
 * New games, the status, rays, the two reveal modes, the solve quirks,
 * undo and redo, completion, the loose move grammar, the lock
 * thresholds, the UI decoder, the status text branches, the key labels,
 * and the rules that keep the hidden layout hidden.
 */
class BlackBoxGameTest {

    @Test
    fun `a new game matches the oracle`() = OracleClient().use { oracle ->
        for (params in BlackBox.presets().map { it.params }) {
            val expected = oracle.newGame("blackbox", params, SEED)
            val midend = Midend.newGame(BlackBox, params, SEED)
            assertSame("new game '$params'", expected, midend)
            assertEquals(0, midend.status())
            assertEquals("", midend.textFormat())
            assertFalse(BlackBox.canFormatAsText)
            assertTrue(BlackBox.wantsStatusBar)
            assertTrue(BlackBox.canSolve)
            assertFalse(midend.canUndo)
            assertFalse(midend.canRedo)
            assertEquals(1, midend.stateCount)
            assertNull(midend.serialise().value("AUXINFO"))
            assertEquals("E0", midend.serialise().value("UI"))
            assertEquals("", oracle.text(expected.state))

            val state = midend.state as BlackBox.State
            val p = midend.currentParams as BlackBox.Params
            assertEquals(p.w, state.w)
            assertEquals(p.h, state.h)
            assertEquals(p.minBalls, state.minBalls)
            assertEquals(p.maxBalls, state.maxBalls)
            assertEquals(0, state.nguesses)
            assertEquals(0, state.nright)
            assertEquals(0, state.nwrong)
            assertEquals(0, state.nmissed)
            assertEquals(1, state.laserNo)
            assertFalse(state.reveal)
            assertFalse(state.justWrong)
            assertFalse(state.done)
            assertTrue(state.exits.all { it == BlackBox.LASER_EMPTY })
            assertEquals(2 * (state.w + state.h), state.nLasers)
        }
    }

    @Test
    fun `requestKeys is arrows only in left-right mode`() = OracleClient().use { oracle ->
        val state = oracle.newGame("blackbox", "", SEED)
        val expected = oracle.requestKeys(state.state)
        val actual = BlackBox.requestKeys(BlackBox.decodeParams("w8h8m5M5"))
        assertEquals(3, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertTrue(expected.labels.isEmpty())
        assertTrue(actual.labels.isEmpty())
    }

    @Test
    fun `a ray shot paints both ends and matches the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("blackbox", "w5h5m3M3", SEED)
            // F0 is the top row (1,0); F5 is the right side (6,2).
            for ((point, rangeNo) in listOf(64 to 32 to 0, 224 to 64 to 5)) {
                val points = listOf(
                    OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON),
                    OracleClient.Point(point.first, point.second, Buttons.RIGHT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    start.state,
                    points,
                    width = 256,
                    height = 256,
                )
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(start.state))
                midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, TILE)
                midend.processKey(point.first, point.second, Buttons.LEFT_RELEASE, TILE)
                assertSame("F$rangeNo", expected, midend)
                assertEquals(2, midend.stateCount)
                assertEquals(
                    listOf("F$rangeNo"),
                    SgtpSave.parse(expected.state.state).values("MOVE"),
                )
                val state = midend.state as BlackBox.State
                assertNotEquals(
                    "F$rangeNo fired",
                    BlackBox.LASER_EMPTY,
                    state.exits[rangeNo],
                )
            }
        }

    @Test
    fun `a wrong cagey reveal pins the grid-seeded laser`() =
        OracleClient().use { oracle ->
            // w5h5m3M3 seed 12345 has balls at arena (1,4), (4,2),
            // (2,4). F0 is a hit. The guesses (1,1), (2,1), (3,1) are
            // wrong, and the second cagey pass demonstrates the pair
            // exits[9] (RHS (6,5)) and exits[12] (bottom (3,6)).
            val points = taps(64 to 32, 64 to 64, 96 to 64, 128 to 64, 32 to 32)
            val expected = oracle.pointsSized(
                oracle.newGame("blackbox", "w5h5m3M3", SEED).state,
                points,
                width = 256,
                height = 256,
            )
            val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
            for (click in points) {
                midend.processKey(click.x, click.y, click.button, TILE)
            }
            assertSame("wrong reveal", expected, midend)
            assertEquals(0, midend.status())
            assertEquals("Wrong! Guess again. (1 incorrect attempt)", midend.statusText())
            assertEquals("E1", midend.serialise().value("UI"))
            assertEquals(
                listOf("F0", "T1,1", "T2,1", "T3,1", "R"),
                SgtpSave.parse(expected.state.state).values("MOVE"),
            )

            val state = midend.state as BlackBox.State
            assertFalse(state.reveal)
            assertTrue(state.justWrong)
            assertEquals(BlackBox.LASER_HIT, state.exits[0])
            assertEquals(3, state.nguesses)
            for (end in listOf(9, 12)) {
                assertTrue(
                    "exit $end is omitted",
                    state.exits[end] and BlackBox.LASER_OMITTED != 0,
                )
            }
            // The demonstration shot numbered 1 is drawn on both grid
            // cells, and each exit names the other range tile.
            assertEquals(2, state.laserNo)
            assertEquals(12, state.exits[9] and BlackBox.LASER_OMITTED.inv())
            assertEquals(9, state.exits[12] and BlackBox.LASER_OMITTED.inv())
            assertEquals(1, state.gridAt(6, 5))
            assertEquals(1, state.gridAt(3, 6))

            // Undo and redo keep the attempt counter, which is UI, not
            // state.
            assertTrue(midend.undo())
            assertSame("undo the wrong reveal", oracle.undo(expected.state.state), midend)
            assertEquals(0, midend.status())
            assertEquals("E1", midend.serialise().value("UI"))
            assertEquals(
                "Click button to verify guesses. (1 incorrect attempt)",
                midend.statusText(),
            )
            assertTrue(midend.redo())
            assertSame("redo the wrong reveal", expected, midend)
            assertEquals("E1", midend.serialise().value("UI"))
        }

    @Test
    fun `a correct reveal completes and redo completes again`() =
        OracleClient().use { oracle ->
            val points = taps(64 to 160, 160 to 96, 96 to 160, 32 to 32)
            val expected = oracle.pointsSized(
                oracle.newGame("blackbox", "w5h5m3M3", SEED).state,
                points,
                width = 256,
                height = 256,
            )
            val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
            for (click in points) {
                midend.processKey(click.x, click.y, click.button, TILE)
            }
            assertSame("correct reveal", expected, midend)
            assertEquals(1, midend.status())
            assertEquals("CORRECT!", midend.statusText())
            assertEquals("E0", midend.serialise().value("UI"))
            assertTrue(midend.takeCompletedEvent())
            assertFalse(midend.takeCompletedEvent())

            assertTrue(midend.undo())
            assertSame("undo the win", oracle.undo(expected.state.state), midend)
            assertEquals(0, midend.status())
            assertFalse(midend.takeCompletedEvent())
            assertTrue(midend.redo())
            assertSame("redo the win", expected, midend)
            assertTrue(midend.takeCompletedEvent())
            assertFalse(midend.takeCompletedEvent())
        }

    @Test
    fun `a solve reveals the answer and a second solve marks every laser`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("blackbox", "w5h5m3M3", SEED)
            val solved = oracle.solve(start.state)
            val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
            assertTrue(midend.solve())
            assertSame("solve", solved, midend)
            assertEquals(-1, midend.status())
            assertEquals("0 wrong and 3 missed balls.", midend.statusText())
            assertEquals(listOf("S"), SgtpSave.parse(solved.state).values("SOLVE"))
            assertFalse(midend.takeCompletedEvent())
            val state = midend.state as BlackBox.State
            assertTrue(state.reveal)
            assertEquals(3, state.nmissed)
            assertEquals(0, state.nwrong)

            val solvedTwice = oracle.solve(solved.state)
            assertTrue(midend.solve())
            assertSame("second solve", solvedTwice, midend)
            assertEquals(-1, midend.status())
            assertEquals("0 wrong and 3 missed balls.", midend.statusText())
            assertEquals(2, SgtpSave.parse(solvedTwice.state).values("SOLVE").size)
            assertFalse(midend.takeCompletedEvent())
            assertEquals(
                "the id never changes",
                start.id,
                midend.id(),
            )
            val stateTwice = midend.state as BlackBox.State
            assertTrue(
                "every fired laser is wrong after the second solve",
                (0 until stateTwice.nLasers).all {
                    stateTwice.exits[it] and BlackBox.LASER_WRONG != 0
                },
            )

            // A solve on a correct reveal keeps the win and adds a
            // solved state that does not complete. Play the win in the
            // midend first, because a load does not raise the completion
            // event.
            val winningMidend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
            for (click in taps(64 to 160, 160 to 96, 96 to 160, 32 to 32)) {
                winningMidend.processKey(click.x, click.y, click.button, TILE)
            }
            assertEquals(1, winningMidend.status())
            assertTrue(winningMidend.takeCompletedEvent())
            val winSave = winningMidend.serialise().serialise()
            assertTrue(winningMidend.solve())
            assertSame("solve after a win", oracle.solve(winSave), winningMidend)
            assertEquals(1, winningMidend.status())
            assertEquals("CORRECT!", winningMidend.statusText())
            assertFalse(winningMidend.takeCompletedEvent())
        }

    @Test
    fun `a solve after a wrong reveal keeps the attempt counter`() =
        OracleClient().use { oracle ->
            val wrong = oracle.pointsSized(
                oracle.newGame("blackbox", "w5h5m3M3", SEED).state,
                taps(64 to 32, 64 to 64, 96 to 64, 128 to 64, 32 to 32),
                width = 256,
                height = 256,
            )
            val expected = oracle.solve(wrong.state.state)
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(wrong.state.state))
            assertTrue(midend.solve())
            assertSame("solve after a wrong reveal", expected, midend)
            assertEquals(-1, midend.status())
            assertEquals(
                "3 wrong and 3 missed balls. (1 incorrect attempt)",
                midend.statusText(),
            )
            assertEquals("E1", midend.serialise().value("UI"))
            val state = midend.state as BlackBox.State
            assertTrue(state.reveal)
            assertEquals(3, state.nwrong)
            assertEquals(3, state.nmissed)
            assertEquals(
                "the two demonstrated ends stay wrong",
                listOf(9, 12),
                (0 until state.nLasers).filter {
                    state.exits[it] != BlackBox.LASER_EMPTY &&
                        state.exits[it] and BlackBox.LASER_WRONG != 0
                },
            )
        }

    @Test
    fun `the move grammar parses like C`() = OracleClient().use { oracle ->
        val accepted = listOf(
            listOf("MOVE" to "T1,1"),
            listOf("MOVE" to "T 1, 1"),
            listOf("MOVE" to "T+1,+1"),
            listOf("MOVE" to "T1,1junk"),
            listOf("MOVE" to "T1,1,2"),
            listOf("MOVE" to "LB1,1"),
            listOf("MOVE" to "LB 1,1"),
            listOf("MOVE" to "LB1,1junk"),
            listOf("MOVE" to "LC1"),
            listOf("MOVE" to "LC1junk"),
            listOf("MOVE" to "LR1"),
            listOf("MOVE" to "F0"),
            listOf("MOVE" to "F 5"),
            listOf("MOVE" to "F19junk"),
            listOf("MOVE" to "T1,4", "MOVE" to "T4,2", "MOVE" to "T2,4", "MOVE" to "R"),
            listOf("SOLVE" to "S"),
        )
        for (records in accepted) {
            val text = historySave(records, 1 + records.size)
            val expected = oracle.id(text)
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
            assertSame("move $records", expected, midend)
            assertEquals("move $records text", text, midend.serialise().serialise())
        }

        val rejected = listOf(
            listOf("MOVE" to "T1"),
            listOf("MOVE" to "T0,1"),
            listOf("MOVE" to "T1,0"),
            listOf("MOVE" to "T6,1"),
            listOf("MOVE" to "F-1"),
            listOf("MOVE" to "F20"),
            listOf("MOVE" to "LC"),
            listOf("MOVE" to "L B1,1"),
            listOf("MOVE" to "LQ1"),
            listOf("MOVE" to "R"),
            listOf("MOVE" to "s"),
            listOf("MOVE" to "X"),
            listOf("MOVE" to ""),
        )
        for (records in rejected) {
            val text = historySave(records, 1 + records.size)
            val expected = try {
                oracle.id(text)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "oracle message for $records",
                "Save file contained an invalid move",
                expected,
            )
            try {
                Midend.fromSave(BlackBox, SgtpSave.parse(text))
                fail("Kotlin loaded $records")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "Kotlin message for $records: ${e.message}",
                    e.message!!.startsWith("save file contained an invalid move: "),
                )
            }
        }

        // A move after a correct reveal is rejected; S is accepted.
        val win = listOf(
            "MOVE" to "T1,4",
            "MOVE" to "T4,2",
            "MOVE" to "T2,4",
            "MOVE" to "R",
        )
        val afterWin = historySave(win + ("MOVE" to "T1,1"), 6)
        assertEquals(
            "Save file contained an invalid move",
            try {
                oracle.id(afterWin)
                null
            } catch (e: OracleException) {
                e.message
            },
        )
        try {
            Midend.fromSave(BlackBox, SgtpSave.parse(afterWin))
            fail("Kotlin loaded a move after the reveal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("save file contained an invalid move: "))
        }
        val afterWinSolve = historySave(win + ("SOLVE" to "S"), 6)
        assertEquals(
            oracle.id(afterWinSolve).status,
            Midend.fromSave(BlackBox, SgtpSave.parse(afterWinSolve)).status(),
        )
    }

    @Test
    fun `a toggle on a locked cell is a real move`() =
        OracleClient().use { oracle ->
            val save = oracle.id(historySave(listOf("MOVE" to "LB1,1"), 2))
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save.state))
            val before = midend.state as BlackBox.State
            assertTrue(before.gridAt(1, 1) and BlackBox.BALL_LOCK != 0)

            val toggled = BlackBox.executeMove(midend.state, "T1,1")
                ?: error("a T on a locked cell must be accepted")
            assertEquals(1, (toggled as BlackBox.State).nguesses)
            assertEquals(
                "a T after an LB is a real state",
                oracle.id(
                    historySave(
                        listOf("MOVE" to "LB1,1", "MOVE" to "T1,1"),
                        3,
                    ),
                ).id,
                Midend.fromSave(
                    BlackBox,
                    SgtpSave.parse(
                        historySave(
                            listOf("MOVE" to "LB1,1", "MOVE" to "T1,1"),
                            3,
                        ),
                    ),
                ).id(),
            )

            // A left click on the locked cell is unused, but it still
            // hides the cursor, as C does.
            val midend2 = Midend.fromSave(BlackBox, SgtpSave.parse(save.state))
            (midend2.ui as BlackBox.Ui).curVisible = true
            assertEquals(
                KeyOutcome.Unused,
                midend2.processKey(64, 64, Buttons.LEFT_BUTTON, TILE),
            )
            assertFalse((midend2.ui as BlackBox.Ui).curVisible)
            assertEquals(2, midend2.stateCount)
        }

    @Test
    fun `column and row lock thresholds match the oracle`() =
        OracleClient().use { oracle ->
            // 5x5: h/2 == w/2 == 2, so three locks in a column are
            // cleared and two are set. The engine test pins the even
            // dimension boundary as well.
            val cases = listOf(
                listOf("MOVE" to "LB1,1", "MOVE" to "LB1,2", "MOVE" to "LB1,3", "MOVE" to "LC1"),
                listOf("MOVE" to "LB1,1", "MOVE" to "LB1,2", "MOVE" to "LC1"),
                listOf("MOVE" to "LC1"),
                listOf("MOVE" to "LB1,1", "MOVE" to "LB2,1", "MOVE" to "LB3,1", "MOVE" to "LR1"),
                listOf("MOVE" to "LB1,1", "MOVE" to "LB2,1", "MOVE" to "LR1"),
            )
            for (records in cases) {
                val text = historySave(records, 1 + records.size, params = "w5h5m3M3")
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
                val upstream = UpstreamLock.commit(
                    java.io.File(System.getProperty("testdata")!!)
                        .parentFile.resolve("UPSTREAM.lock"),
                )
                val expected = TraceNormalizer.normalize(oracle.trace(text), upstream)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend),
                    upstream,
                )
                assertEquals("locks $records", expected, actual)
            }
        }

    @Test
    fun `status text covers every branch`() = OracleClient().use { oracle ->
        val start = oracle.newGame("blackbox", "w5h5m3M3", SEED)
        val range = oracle.newGame("blackbox", "w8h8m3M6", SEED)

        val fresh = Midend.fromSave(BlackBox, SgtpSave.parse(start.state))
        assertEquals("Balls marked: 0 / 3", fresh.statusText())
        assertEquals(
            "Balls marked: 0 / 3-6.",
            Midend.fromSave(BlackBox, SgtpSave.parse(range.state)).statusText(),
        )

        val inRange = Midend.fromSave(BlackBox, SgtpSave.parse(start.state))
        for (click in taps(64 to 64, 96 to 64, 128 to 64)) {
            inRange.processKey(click.x, click.y, click.button, TILE)
        }
        assertEquals("Click button to verify guesses.", inRange.statusText())

        val over = Midend.fromSave(BlackBox, SgtpSave.parse(start.state))
        for (click in taps(64 to 64, 96 to 64, 128 to 64, 64 to 96)) {
            over.processKey(click.x, click.y, click.button, TILE)
        }
        assertEquals("1 too many balls marked.", over.statusText())

        val wrong = Midend.fromSave(BlackBox, SgtpSave.parse(start.state))
        for (click in taps(64 to 32, 64 to 64, 96 to 64, 128 to 64, 32 to 32)) {
            wrong.processKey(click.x, click.y, click.button, TILE)
        }
        assertEquals(
            "Wrong! Guess again. (1 incorrect attempt)",
            wrong.statusText(),
        )

        // The UI record is the only source of an attempt suffix.
        for (ui in listOf("E0", "E1", "E2", "E7", "E-2")) {
            val text = saveText("w5h5m3M3", "7f1b9a74d74b19f6", ui)
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
            assertEquals(
                "status for $ui",
                oracleStatus(oracle, text),
                midend.statusText(),
            )
            assertEquals(
                "canonical UI for $ui",
                "E" + ui.removePrefix("E").toInt(),
                midend.serialise().value("UI"),
            )
        }
    }

    @Test
    fun `the UI decoder is the sscanf model`() = OracleClient().use { oracle ->
        val vectors = listOf(
            "E0", "E7", "E-2", "E 3", "E+4", "E007", "E5junk", "E3.5",
            "X3", "", "E", "e5", "EE3", " E4", "E  +4junk",
        )
        for (vector in vectors) {
            val text = saveText("w5h5m3M3", "7f1b9a74d74b19f6", vector)
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
            assertEquals(
                "canonical UI for '$vector'",
                oracle.id(text).state,
                midend.serialise().serialise(),
            )
            assertEquals(
                "status for '$vector'",
                oracleStatus(oracle, text),
                midend.statusText(),
            )
        }
    }

    @Test
    fun `currentKeyLabel covers the table`() {
        val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
        val ui = midend.ui as BlackBox.Ui
        ui.curVisible = true

        ui.curX = 0
        ui.curY = 0
        assertEquals("Check", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))

        ui.curX = 1
        ui.curY = 1
        assertEquals("Ball", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Lock", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))

        // A guessed cell offers Clear, a locked cell offers Unlock, and
        // a left select on a locked cell is blank.
        val guessed = BlackBox.executeMove(midend.state, "T1,1") as BlackBox.State
        ui.curVisible = true
        assertEquals("Clear", BlackBox.currentKeyLabel(ui, guessed, Buttons.CURSOR_SELECT))
        val locked = BlackBox.executeMove(midend.state, "LB1,1") as BlackBox.State
        assertEquals("", BlackBox.currentKeyLabel(ui, locked, Buttons.CURSOR_SELECT))
        assertEquals("Unlock", BlackBox.currentKeyLabel(ui, locked, Buttons.CURSOR_SELECT2))

        // A range cell fires, and the second select is a lock.
        ui.curX = 1
        ui.curY = 0
        assertEquals("Fire", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Lock", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))
        val fired = BlackBox.executeMove(midend.state, "F0") as BlackBox.State
        assertEquals("", BlackBox.currentKeyLabel(ui, fired, Buttons.CURSOR_SELECT))

        // The second select on a range line locks or unlocks the line.
        ui.curX = 1
        ui.curY = 0
        assertEquals("Lock", BlackBox.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))
        var manyLocks = midend.state
        for (y in 1..3) manyLocks = BlackBox.executeMove(manyLocks, "LB1,$y")!!
        assertEquals(
            "Unlock",
            BlackBox.currentKeyLabel(ui, manyLocks, Buttons.CURSOR_SELECT2),
        )

        // A revealed game has no label at all.
        val revealed = BlackBox.executeMove(midend.state, "S") as BlackBox.State
        ui.curX = 1
        ui.curY = 1
        assertEquals("", BlackBox.currentKeyLabel(ui, revealed, Buttons.CURSOR_SELECT))
        assertEquals("", BlackBox.currentKeyLabel(ui, revealed, Buttons.CURSOR_SELECT2))
    }

    @Test
    fun `the hidden layout never reaches the id, status, text, UI, or drawing`() =
        OracleClient().use { oracle ->
            val first = Midend.newGame(BlackBox, "w5h5m3M3", "1")
            val second = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
            assertNotEquals(first.desc, second.desc)

            // A fresh board hides the balls, so two descriptions draw
            // exactly the same operations.
            val upstream = UpstreamLock.commit(
                java.io.File(System.getProperty("testdata")!!)
                    .parentFile.resolve("UPSTREAM.lock"),
            )
            val firstTrace = TraceNormalizer.normalize(KotlinTraceSink.trace(first), upstream)
            val secondTrace = TraceNormalizer.normalize(KotlinTraceSink.trace(second), upstream)
            assertEquals(
                "a fresh trace does not depend on the hidden balls",
                opsOf(firstTrace),
                opsOf(secondTrace),
            )

            val state = second.state as BlackBox.State
            val balls = ArrayList<String>()
            for (y in 1..state.h) {
                for (x in 1..state.w) {
                    if (state.gridAt(x, y) and BlackBox.BALL_CORRECT != 0) {
                        balls += "$x,$y"
                    }
                }
            }
            assertEquals(3, balls.size)
            for (ball in balls) {
                assertFalse("id contains '$ball'", second.id().contains(ball))
                assertFalse("status contains '$ball'", second.statusText().contains(ball))
                assertFalse("text contains '$ball'", second.textFormat().contains(ball))
                assertFalse("UI contains '$ball'", second.serialise().value("UI")!!.contains(ball))
            }
            assertFalse(secondTrace.contains(balls.joinToString(" ")))
            assertTrue(
                "no text operation names a ball",
                (secondTrace.lineSequence().filter { it.startsWith("text ") })
                    .none { line -> balls.any { ball -> line.contains(ball) } },
            )

            // The status text is only counts, never positions.
            val solved = oracle.solve(second.serialise().serialise())
            assertEquals(-1, solved.status)
            assertEquals(
                "0 wrong and 3 missed balls.",
                Midend.fromSave(BlackBox, SgtpSave.parse(solved.state)).statusText(),
            )
        }

    @Test
    fun `a real laser changes the board and a UI-only flash does not`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
            val before = TraceNormalizer.normalize(
                KotlinTraceSink.trace(Midend.newGame(BlackBox, "w5h5m3M3", SEED)),
                null,
            )
            val id = midend.id()
            val status = midend.status()

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(64, 32, Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(2, midend.stateCount)
            assertTrue(midend.canUndo)
            assertEquals(id, midend.id())
            assertEquals(status, midend.status())
            val after = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), null)
            assertNotEquals(
                "a fired laser changes the drawing",
                opsOf(before),
                opsOf(after),
            )

            // A second press on the same fired laser is a flash only:
            // it adds no state and no status change.
            val count = midend.stateCount
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(64, 32, Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(count, midend.stateCount)
            assertEquals(status, midend.status())
            assertEquals(id, midend.id())
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(64, 32, Buttons.LEFT_RELEASE, TILE),
            )
            assertEquals(count, midend.stateCount)

            // A reveal click below the minimum is unused and adds no
            // state.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(32, 32, Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(count, midend.stateCount)
        }

    private fun taps(vararg points: Pair<Int, Int>): List<OracleClient.Point> =
        points.flatMap { point(it.first, it.second) }

    private fun point(x: Int, y: Int): List<OracleClient.Point> = listOf(
        OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
        OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
    )

    private fun oracleStatus(oracle: OracleClient, state: String): String =
        (oracle.trace(state).field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .first { it.string("op") == "status" }
            .string("text")

    private fun opsOf(text: String): String =
        text.lineSequence()
            .dropWhile { !it.startsWith("changed_state ") && it != "start_draw" }
            .joinToString("\n")

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private fun assertSame(
        what: String,
        expected: OracleClient.PointResult,
        actual: Midend,
    ) = assertSame(what, expected.state, actual)

    private fun saveText(params: String, desc: String, ui: String): String =
        SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", BlackBox.name),
                SaveRecord("PARAMS", params),
                SaveRecord("CPARAMS", params),
                SaveRecord("DESC", desc),
                SaveRecord("UI", ui),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()

    private fun historySave(
        records: List<Pair<String, String>>,
        statePos: Int,
        params: String = "w5h5m3M3",
        desc: String = "7f1b9a74d74b19f6",
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", BlackBox.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("UI", "E0"),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }
}
