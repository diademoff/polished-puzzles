package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: bridges.
 *
 * The fresh game, the text form, the status, the move grammar, the
 * solver, the initial-state solve source, the two C error strings, the
 * hint, undo and redo, completion and its event, and the key request.
 */
class BridgesGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24
        const val PARAMS = "7x7i30e10m2d0"
        const val DESC = "3e2g6a3c2i1b3h4d4a"
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun fresh(oracle: OracleClient): String =
        oracle.newGame("bridges", PARAMS, SEED).state

    /** The oracle save with [moves] as its whole history. */
    private fun withHistory(
        fresh: String,
        moves: List<Pair<String, String>>,
        statePos: Int = moves.size + 1,
    ): String {
        val save = SgtpSave.parse(fresh)
        val records = ArrayList(save.records.takeWhile { it.key != "NSTATES" })
        records += SaveRecord("NSTATES", (moves.size + 1).toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for ((key, value) in moves) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private fun withoutRecord(fresh: String, key: String): String {
        val save = SgtpSave.parse(fresh)
        return SgtpSave.of(save.records.filter { it.key != key }).serialise()
    }

    private fun withRecord(fresh: String, key: String, value: String): String {
        val save = SgtpSave.parse(fresh)
        return SgtpSave.of(
            save.records.map { if (it.key == key) SaveRecord(key, value) else it },
        ).serialise()
    }

    private fun auxTokens(): List<String> {
        val aux = requireNotNull(
            Bridges.newDesc(
                Bridges.decodeParams(PARAMS),
                RandomState.fromSeed(SEED),
            ).auxInfo,
        )
        return aux.split(';').drop(1)
    }

    @Test
    fun `a fresh game matches the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.newGame("bridges", PARAMS, SEED)
        val actual = Midend.newGame(Bridges, PARAMS, SEED)
        assertSame("fresh", expected, actual)
        assertEquals(DESC, expected.id.substringAfter(':'))
        assertEquals(0, actual.status())
        assertEquals("", actual.statusText())
        assertFalse(actual.canUndo)
        assertFalse(actual.canRedo)
        assertEquals(1, actual.stateCount)
        assertEquals(oracle.text(expected.state), actual.textFormat())
        assertFalse(actual.serialise().records.any { it.key == "UI" })
    }

    @Test
    fun `the move grammar round-trips through a save`() = OracleClient().use { oracle ->
        val save = fresh(oracle)
        val accepted = listOf(
            "S",
            "L0,0,6,0,1",
            "N0,0,6,0",
            "M0,0",
            "S;M0,0",
            "L 0,0,6,0,1",
            "L+0,0,6,0,1",
            "M 0,0",
            "S;",
        )
        for (move in accepted) {
            val text = withHistory(save, listOf("MOVE" to move))
            val loaded = oracle.id(text)
            val kotlin = Midend.fromSave(Bridges, SgtpSave.parse(text))
            assertSame("'$move'", loaded, kotlin)
        }

        val rejected = listOf(
            "",
            "L0,0,6,0",
            "L0,0,6,0,-1",
            "L0,0,6,0,3",
            "L0,0,6,6,1",
            "L0,0,2,0,1",
            "L0,0,6,0,1x",
            "M0,1",
            "M9,9",
            "X",
            "SS",
        )
        for (move in rejected) {
            val text = withHistory(save, listOf("MOVE" to move))
            val oracleFailed = try {
                oracle.id(text)
                false
            } catch (e: OracleException) {
                true
            }
            val kotlinFailed = try {
                Midend.fromSave(Bridges, SgtpSave.parse(text))
                false
            } catch (e: RuntimeException) {
                true
            }
            assertEquals("'$move' rejected", oracleFailed, kotlinFailed)
            assertTrue("'$move' must be rejected", oracleFailed)
        }

        // `L` and `N` on the same edge leave both flags set, as C does.
        val both = withHistory(save, listOf("MOVE" to "L0,0,6,0,1;N0,0,6,0"))
        assertSame("both flags", oracle.id(both), Midend.fromSave(Bridges, SgtpSave.parse(both)))
        val kotlinBoth = Midend.fromSave(Bridges, SgtpSave.parse(both))
        val state = kotlinBoth.state as Bridges.State
        assertEquals(0x44, state.grid[1] and 0x44)
    }

    @Test
    fun `the solver matches the oracle from the initial board`() =
        OracleClient().use { oracle ->
            val save = fresh(oracle)

            val freshSolve = Midend.newGame(Bridges, PARAMS, SEED)
            assertTrue(freshSolve.solve())
            val freshText = freshSolve.serialise().serialise()
            assertSame("fresh solve", oracle.solve(save), freshSolve)
            assertEquals(1, SgtpSave.parse(freshText).values("SOLVE").size)
            assertEquals(1, freshSolve.status())

            // A wrong bridge must be removed by the solve, because the
            // answer is executed on the initial board.
            val wrong = withHistory(save, listOf("MOVE" to "L0,0,6,0,2"))
            val wrongMidend = Midend.fromSave(Bridges, SgtpSave.parse(wrong))
            assertTrue(wrongMidend.solve())
            assertSame("wrong bridge solve", oracle.solve(wrong), wrongMidend)
            val wrongMove = SgtpSave.parse(wrongMidend.serialise().serialise())
                .values("SOLVE").single()
            assertTrue("the wrong bridge is rewritten", wrongMove.contains("L0,0,6,0,1"))

            // The right bridges but no locks still receive the `M`
            // tokens.
            val bridgeOnly = auxTokens().filter { it.startsWith("L") }
                .joinToString(";")
            val noLocks = withHistory(save, listOf("MOVE" to bridgeOnly))
            val noLocksMidend = Midend.fromSave(Bridges, SgtpSave.parse(noLocks))
            assertTrue(noLocksMidend.solve())
            assertSame("no locks solve", oracle.solve(noLocks), noLocksMidend)
            val lockMove = SgtpSave.parse(noLocksMidend.serialise().serialise())
                .values("SOLVE").single()
            assertTrue(lockMove.contains(";M0,0"))

            // A board identical to the solved state, locks included,
            // reduces to `S`.
            val solvedState = withHistory(
                save,
                listOf("MOVE" to "S;" + auxTokens().joinToString(";")),
            )
            val identical = Midend.fromSave(Bridges, SgtpSave.parse(solvedState))
            assertTrue(identical.solve())
            assertSame("identical solve", oracle.solve(solvedState), identical)
            assertEquals(
                "S",
                SgtpSave.parse(identical.serialise().serialise()).values("SOLVE").single(),
            )
        }

    @Test
    fun `the solver from scratch matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "7x7i30e10m2d0", "7x7i30e10m2d1", "7x7i30e10m2d2", "10x10i30e10m2d2",
        )) {
            val save = withoutRecord(
                oracle.newGame("bridges", params, SEED).state,
                "AUXINFO",
            )
            val expected = oracle.solve(save)
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            assertTrue("$params solves", midend.solve())
            assertSame("$params solve", expected, midend)
            assertEquals(
                "$params SOLVE",
                SgtpSave.parse(expected.state).values("SOLVE").single(),
                midend.serialise().values("SOLVE").single(),
            )
        }

        // A hand-written board that stage 1 can finish.
        val hand = SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Bridges.name),
                SaveRecord("PARAMS", "3x3i30e10m2d0"),
                SaveRecord("CPARAMS", "3x3m2"),
                SaveRecord("SEED", "1"),
                SaveRecord("DESC", "1e1b"),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()
        val expected = oracle.solve(hand)
        val midend = Midend.fromSave(Bridges, SgtpSave.parse(hand))
        assertTrue("hand-written board solves", midend.solve())
        assertSame("hand-written solve", expected, midend)
        assertEquals(
            "S;L0,0,0,2,1;M0,0;M0,2",
            midend.serialise().values("SOLVE").single(),
        )
    }

    @Test
    fun `the two C error strings match the oracle`() = OracleClient().use { oracle ->
        val save = fresh(oracle)
        val invalid = withRecord(save, "AUXINFO", "")
        val oracleError = try {
            oracle.solve(invalid)
            null
        } catch (e: OracleException) {
            e.message
        }
        val midend = Midend.fromSave(Bridges, SgtpSave.parse(invalid))
        val attempt = Bridges.solveAttempt(midend.state, "")
        assertEquals("Generated aux string is not a valid move (!).", oracleError)
        assertEquals(oracleError, attempt.error)
        assertNull(attempt.move)
        assertFalse(midend.solve())

        // An unsolvable hand-written board, with no auxiliary answer.
        val unsolvable = SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Bridges.name),
                SaveRecord("PARAMS", "3x3i30e10m4d0"),
                SaveRecord("CPARAMS", "3x3m4"),
                SaveRecord("SEED", "1"),
                SaveRecord("DESC", "4a1f"),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()
        val unsolvableError = try {
            oracle.solve(unsolvable)
            null
        } catch (e: OracleException) {
            e.message
        }
        val unsolvableMidend = Midend.fromSave(Bridges, SgtpSave.parse(unsolvable))
        val unsolvableAttempt = Bridges.solveAttempt(unsolvableMidend.state, null)
        assertEquals(
            "Game does not have a (non-recursive) solution.",
            unsolvableError,
        )
        assertEquals(unsolvableError, unsolvableAttempt.error)
        assertNull(unsolvableAttempt.move)
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Bridges, PARAMS, SEED)
        for (point in listOf(
            OracleClient.Point(TILE, TILE, Buttons.LEFT_BUTTON),
            OracleClient.Point(TILE, TILE, Buttons.LEFT_RELEASE),
        )) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(point.x, point.y, point.button, TILE),
            )
        }
        assertEquals(listOf("M0,0"), midend.serialise().values("MOVE"))
        val clicked = midend.serialise().serialise()
        assertSame("after click", oracle.id(clicked), midend)

        val undone = oracle.undo(clicked)
        assertTrue(midend.undo())
        assertSame("after undo", undone, midend)
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertSame("after redo", redone, midend)

        // A new move after undo truncates the redo branch.
        assertTrue(midend.undo())
        for (point in listOf(
            OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_BUTTON),
            OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_RELEASE),
        )) {
            midend.processKey(point.x, point.y, point.button, TILE)
        }
        assertFalse(midend.canRedo)
        assertEquals(2, midend.stateCount)
        assertEquals(listOf("M6,0"), midend.serialise().values("MOVE"))
        assertSame("truncated", oracle.id(midend.serialise().serialise()), midend)
    }

    @Test
    fun `completion and its event match the oracle`() = OracleClient().use { oracle ->
        val save = fresh(oracle)
        val bridgeOnly = auxTokens().filter { it.startsWith("L") }
            .joinToString(";")
        val completed = withHistory(save, listOf("MOVE" to bridgeOnly))
        val midend = Midend.fromSave(Bridges, SgtpSave.parse(completed))
        assertEquals(1, midend.status())
        assertEquals(1, oracle.status(completed).status)
        assertTrue((midend.state as Bridges.State).completed)
        assertFalse((midend.state as Bridges.State).solved)
        assertFalse(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue("a redo into completion raises the event", midend.takeCompletedEvent())

        // A solve completes the board but never raises the event.
        val solved = Midend.newGame(Bridges, PARAMS, SEED)
        assertTrue(solved.solve())
        assertEquals(1, solved.status())
        assertFalse(solved.takeCompletedEvent())
    }

    @Test
    fun `a hint returns the solver diff`() = OracleClient().use { oracle ->
        val save = fresh(oracle)
        val expected = oracle.key(save, 0, 0, 'h'.code)
        val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, 'h'.code, TILE),
        )
        assertSame("hint", expected, midend)
        val hint = midend.serialise().values("MOVE").single()
        assertTrue(hint.startsWith("S"))
        assertTrue(hint.contains("L"))
    }

    @Test
    fun `request keys match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Bridges.presets().map { it.params }) {
                val save = oracle.newGame("bridges", params, SEED).state
                val expected = oracle.requestKeys(save)
                val actual = Bridges.requestKeys(Bridges.decodeParams(params))
                assertEquals("$params arrow mode", expected.arrowMode,
                    actual.arrowMode.ordinal + 1)
                assertEquals("$params count", expected.labels.size, actual.labels.size)
                for (i in expected.labels.indices) {
                    assertEquals("$params label $i", expected.labels[i].label,
                        actual.labels[i].name)
                    assertEquals("$params button $i", expected.labels[i].button,
                        actual.labels[i].button)
                    assertEquals("$params arrows $i", expected.labels[i].needsArrows,
                        actual.labels[i].needsArrows)
                }
            }
        }

    @Test
    fun `keyboard keys match the oracle`() = OracleClient().use { oracle ->
        val save = fresh(oracle)
        for (sequence in listOf(
            listOf('l'.code),
            listOf('g'.code),
            listOf('g'.code, 'g'.code),
            listOf('1'.code),
            listOf('3'.code),
            listOf('0'.code),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
        )) {
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            for (key in sequence) {
                midend.processKey(0, 0, key, TILE)
            }
            assertSame("keys $sequence", expected, midend)
        }
    }
}
