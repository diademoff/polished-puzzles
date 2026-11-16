package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: tracks.
 *
 * The move grammar, the solve payloads and their fallback to the
 * captured initial board, the text form, completion, error marking,
 * undo and redo, and the two C undefined-behavior definitions.
 */
class TracksGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Train Tracks", Tracks.name)
        assertEquals("tracks", Tracks.backendName)
        assertEquals(33, Tracks.preferredTileSize)
        assertTrue(Tracks.canSolve)
        assertFalse(Tracks.wantsStatusBar)
        assertFalse(Tracks.isTimed)
        assertEquals(0, Tracks.flags)
        assertTrue(Tracks.canFormatAsText)
        assertTrue(Tracks.textFormat(Midend.newGame(Tracks, "8x8dt", SEED).state).isNotEmpty())
    }

    @Test
    fun `request keys is the C NULL hook`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        val normal = oracle.requestKeys(save)
        val kotlinNormal = Tracks.requestKeys(Tracks.decodeParams("8x8dt"))
        assertEquals(3, normal.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, kotlinNormal.arrowMode)
        assertTrue(kotlinNormal.labels.isEmpty())

        val full = oracle.requestKeys(save, fullCursor = true)
        val kotlinFull = Tracks.requestKeys(Tracks.decodeParams("8x8dt"), fullCursor = true)
        assertEquals(1, full.arrowMode)
        assertEquals(ArrowMode.ARROWS_ONLY, kotlinFull.arrowMode)
        assertTrue(kotlinFull.labels.isEmpty())
    }

    @Test(timeout = 300_000)
    fun `the solve payload for every preset matches the oracle`() =
        OracleClient().use { oracle ->
            for (params in Tracks.presets().map { it.params } + "") {
                val save = oracle.newGame("tracks", params, SEED).state
                val solved = oracle.solve(save)
                val payload = SgtpSave.parse(solved.state).value("SOLVE")
                assertNotNull("oracle solve for '$params'", payload)
                assertTrue("the solve is an S move for '$params'", payload!!.startsWith("S"))

                val midend = Midend.newGame(Tracks, params, SEED)
                val attempt = Tracks.solveAttempt(midend.state)
                assertNull("no error for '$params'", attempt.error)
                assertEquals("payload for '$params'", payload, attempt.move)
                assertEquals("status for '$params'", 1, solved.status)

                assertTrue("solve succeeds for '$params'", midend.solve())
                assertEquals("status after solve", 1, midend.status())
                assertEquals(
                    "SOLVE record for '$params'",
                    payload,
                    midend.serialise().value("SOLVE"),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the solve falls back to the initial board and diffs the current one`() =
        OracleClient().use { oracle ->
            val move = "S;TR0,0;NS0,0"
            val base = Midend.newGame(Tracks, "8x8dt", SEED)
            val broken = Tracks.executeMove(base.state, move) as Tracks.State
            // The current board has a track edge on a square that is
            // also marked no-track, so `solve_update_flags` marks the
            // opposite edge flags and the solver reports impossible.
            assertTrue(broken.sflags[0] and (1 shl 16) != 0)
            assertTrue(broken.sflags[0] and 2 != 0)

            val save = withMove(base.serialise().serialise(), move)
            val cSolved = oracle.solve(save)
            val payload = SgtpSave.parse(cSolved.state).value("SOLVE")

            val attempt = Tracks.solveAttempt(broken)
            assertNull(attempt.error)
            assertEquals(
                "the fallback diff is against the current board",
                payload,
                attempt.move,
            )
            assertEquals(1, cSolved.status)
            assertTrue(attempt.move!!.startsWith("S;"))
            assertTrue(
                "the diff repairs the current board",
                attempt.move!!.contains("tR0,0"),
            )
        }

    @Test
    fun `an insoluble board returns the C error string`() = OracleClient().use { oracle ->
        val desc = "a".repeat(64) + ",S99" + ",0".repeat(7) + ",S0" + ",0".repeat(7)
        val blank = Midend.fromGameId(Tracks, "8x8:$desc")
        val attempt = Tracks.solveAttempt(blank.state)
        assertNull(attempt.move)
        assertEquals("Unable to find solution", attempt.error)
        assertNull(Tracks.solve(blank.state))

        val error = try {
            oracle.solve(blank.serialise().serialise())
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Unable to find solution", error)
    }

    @Test(timeout = 120_000)
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val base = midend.serialise().serialise()
        val initial = midend.state
        val valid = listOf(
            "",
            "S",
            "TR0,0",
            "TR0,0;TR0,0",
            "TR0,0;TR0,0;TR0,0",
            "TR 0,0",
            "TR+0,0",
            "TS0,0",
            "NS0,0",
            "TR0,0;NS2,0",
            "S;TS0,3",
            "H",
            "H;H",
            "S;H",
        )
        for (move in valid) {
            val next = Tracks.executeMove(initial, move)
            assertNotNull("Kotlin should accept '$move'", next)
            val text = withMove(base, move)
            val loaded = oracle.id(text)
            assertEquals("status for '$move'", loaded.status, Tracks.status(next!!))
            assertEquals(
                "text for '$move'",
                oracle.text(text),
                Tracks.textFormat(next),
            )
        }

        val invalid = listOf(
            "T",
            "t",
            "N",
            "n",
            "TX0,0",
            "TR9,9",
            "TR-1,0",
            "TR0,0x",
            "TR0,0;//",
            "TR0,0;x",
            "TR0,0;S;TX0,0",
            "TS0,3",
            "NS0,3",
            "M0,0",
            "TR1",
            "TR1,",
            "TR,0",
        )
        for (move in invalid) {
            assertNull("Kotlin should reject '$move'", Tracks.executeMove(initial, move))
            val error = try {
                oracle.id(withMove(base, move))
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C should reject '$move'", error)
        }
    }

    @Test(timeout = 120_000)
    fun `a hand-written full path completes on the empty move`() =
        OracleClient().use { oracle ->
            val solved = solvedState("8x8dt", SEED)
            val desc = fullPathDesc(solved)
            val params = Tracks.decodeParams("8x8dt")
            assertNull(Tracks.validateDesc(params, desc))
            val initial = Tracks.newState(params, desc) as Tracks.State
            assertFalse("a new state is not complete", initial.completed)
            assertEquals(0, Tracks.status(initial))

            val completed = Tracks.executeMove(initial, "") as Tracks.State
            assertTrue("the full path completes", completed.completed)
            assertEquals(1, Tracks.status(completed))
            assertTrue(completed.numErrors.all { it == 0 })
            assertTrue(completed.sflags.none { it and 4 != 0 })

            val id = Midend.fromGameId(Tracks, "8x8:$desc")
            val openSave = id.serialise().serialise()
            val open = oracle.id(openSave)
            assertEquals("C status before the empty move", 0, open.status)
            assertEquals(
                "C text before the empty move",
                oracle.text(openSave),
                Tracks.textFormat(initial),
            )
            val save = withMove(openSave, "")
            val loaded = oracle.id(save)
            assertEquals("C status", 1, loaded.status)
            assertEquals("C text", oracle.text(save), Tracks.textFormat(completed))
            assertNotEquals(
                "the empty move sets the completion flag",
                open.status,
                loaded.status,
            )
        }

    @Test(timeout = 120_000)
    fun `a wrong clue marks only its row`() = OracleClient().use { oracle ->
        val solved = solvedState("8x8dt", SEED)
        val desc = fullPathDesc(solved, rowDelta = 1, rowIndex = 2)
        val params = Tracks.decodeParams("8x8dt")
        assertNull(Tracks.validateDesc(params, desc))
        val initial = Tracks.newState(params, desc) as Tracks.State
        val marked = Tracks.executeMove(initial, "") as Tracks.State

        assertFalse(marked.completed)
        assertEquals(0, Tracks.status(marked))
        assertEquals("row 2 is wrong", 1, marked.numErrors[8 + 2])
        for (i in 0 until 16) {
            if (i != 8 + 2) assertEquals("error $i", 0, marked.numErrors[i])
        }
        // A wrong count marks the clue, not the cells.
        assertTrue(marked.sflags.none { it and 4 != 0 })

        val id = Midend.fromGameId(Tracks, "8x8:$desc")
        val save = withMove(id.serialise().serialise(), "")
        assertEquals("C status", 0, oracle.id(save).status)
        assertEquals("C text", oracle.text(save), Tracks.textFormat(marked))
    }

    @Test(timeout = 120_000)
    fun `a loop and a stray track are errors`() {
        // A 2x2 loop at the top left, plus the required S markers. The
        // loop is disconnected from the entrance and exit.
        val grid = "9Caa36aaaaaaaaaa"
        val desc = grid + ",S2,2,0,0,S2,2,0,0"
        val params = Tracks.decodeParams("4x4dt")
        assertNull(Tracks.validateDesc(params, desc))
        val state = Tracks.newState(params, desc) as Tracks.State
        val marked = Tracks.executeMove(state, "") as Tracks.State
        assertFalse(marked.completed)
        assertEquals(0, Tracks.status(marked))
        // Every edge of the loop is marked.
        assertEquals(4, (0 until 16).count { marked.sflags[it] and 4 != 0 })

        // A stray square track outside the path: start from a complete
        // board, then set S_TRACK on a blank cell.
        val solved = solvedState("8x8dt", SEED)
        val fullDesc = fullPathDesc(solved)
        val full = Tracks.newState(Tracks.decodeParams("8x8dt"), fullDesc) as Tracks.State
        val blank = (0 until 64).first { full.sflags[it] and 1 == 0 }
        val x = blank % 8
        val y = blank / 8
        val stray = Tracks.executeMove(full, "TS$x,$y") as Tracks.State
        assertFalse(stray.completed)
        assertEquals(1, stray.sflags[blank] and 1)
        assertEquals(4, stray.sflags[blank] and 4)
    }

    @Test(timeout = 120_000)
    fun `a third edge on a clue is an error only after a move`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val state = midend.state as Tracks.State
        // (0,3) is the entrance clue with two track edges. The S latch
        // skips the input rule, so a third edge is reachable.
        val next = Tracks.executeMove(state, "S;TU0,3") as Tracks.State
        assertTrue(next.usedSolve)
        // The cell has three track edges and is marked.
        val i = 3 * 8
        assertEquals(3, Integer.bitCount((next.sflags[i] ushr 16) and 15))
        assertEquals(4, next.sflags[i] and 4)
        assertFalse(next.completed)
        assertEquals(0, Tracks.status(next))
    }

    @Test(timeout = 120_000)
    fun `undo redo and the completion event follow the midend`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertFalse(midend.canUndo)
        val before = midend.stateCount
        val outcome = midend.processKey(0, 0, Buttons.CURSOR_SELECT, 33)
        // The first select only shows the cursor.
        assertEquals(name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect, outcome)
        assertEquals(before, midend.stateCount)
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 33)
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, 33)
        assertTrue(midend.stateCount > before)
        assertTrue(midend.canUndo)
        assertTrue(midend.undo())
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)
        assertTrue(midend.redo())

        // A real completion raises the event once; a redo into the
        // completed state raises it again, as the midend contract says.
        val solved = solvedState("8x8dt", SEED)
        val desc = fullPathDesc(solved)
        val playing = Midend.fromGameId(Tracks, "8x8:$desc")
        assertFalse(playing.takeCompletedEvent())
        val completed = Tracks.executeMove(playing.state, "") as Tracks.State
        assertTrue(Tracks.changedState(playing.ui, playing.state, completed))
        val solvedFlag = (completed.copy() as Tracks.State).also { it.usedSolve = true }
        assertFalse(
            "a solve never raises the event",
            Tracks.changedState(playing.ui, playing.state, solvedFlag),
        )
    }

    @Test(timeout = 120_000)
    fun `the text form matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Tracks.presets().map { it.params } + "") {
                val save = oracle.newGame("tracks", params, SEED).state
                val midend = Midend.newGame(Tracks, params, SEED)
                assertEquals(
                    "text for '$params'",
                    oracle.text(save),
                    Tracks.textFormat(midend.state),
                )
                assertEquals(
                    "midend text for '$params'",
                    oracle.text(save),
                    midend.textFormat(),
                )
                assertTrue(
                    "the text ends with the B line",
                    Tracks.textFormat(midend.state).endsWith("\n"),
                )
            }

            val blankDesc = "a".repeat(64) + ",S0" + ",0".repeat(7) +
                ",S0" + ",0".repeat(7)
            val blank = Midend.fromGameId(Tracks, "8x8:$blankDesc")
            val marked = Tracks.executeMove(blank.state, "NS3,3") as Tracks.State
            val save = withMove(blank.serialise().serialise(), "NS3,3")
            assertEquals("x mark text", oracle.text(save), Tracks.textFormat(marked))
            assertTrue("the x mark is shown", Tracks.textFormat(marked).contains("x"))

            // One moved state, with the same trailing newline as C.
            val moved = Midend.newGame(Tracks, "8x8dt", SEED)
            val movedState = Tracks.executeMove(moved.state, "TS2,2") as Tracks.State
            val movedSave = withMove(moved.serialise().serialise(), "TS2,2")
            assertEquals("moved text", oracle.text(movedSave), Tracks.textFormat(movedState))
            assertTrue("the moved text ends with a newline", Tracks.textFormat(movedState).endsWith("\n"))
            assertEquals(1, movedState.sflags[2 * 8 + 2] and 1)
        }

    @Test(timeout = 120_000)
    fun `an outer track edge is accepted by the solver`() = OracleClient().use { oracle ->
        // C asserts that an outer track edge is on a clue square. A
        // save can set one with the S latch. C aborts at tracks.c:1423;
        // the port ignores the border rule and returns the normal solve,
        // which clears the stray outer edge first (D-012).
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val state = midend.state as Tracks.State
        val next = Tracks.executeMove(state, "S;TU0,0") as Tracks.State
        assertEquals(2, (next.sflags[0] ushr 16) and 2)
        val attempt = Tracks.solveAttempt(next)
        assertNull(attempt.error)
        val move = attempt.move!!
        assertTrue(move.startsWith("S;NR0,0;tU0,0;"))
        val after = Tracks.executeMove(next, move) as Tracks.State
        assertTrue(after.completed)
        assertTrue(after.usedSolve)
        assertEquals(1, Tracks.status(after))

        // The save itself loads in C without a solve; the abort is in
        // the solve path only.
        val save = withMove(midend.serialise().serialise(), "S;TU0,0")
        assertEquals(0, oracle.id(save).status)
        assertNull(
            Tracks.validateDesc(
                Tracks.decodeParams("8x8dt"),
                fullPathDesc(solvedState("8x8dt", SEED)),
            ),
        )
    }

    private fun solvedState(params: String, seed: String): Tracks.State {
        val midend = Midend.newGame(Tracks, params, seed)
        assertTrue(midend.solve())
        return midend.state as Tracks.State
    }

    /**
     * Encodes the solved board as a description where every track cell
     * is a clue. The empty move then runs `check_completion(mark=true)`.
     */
    private fun fullPathDesc(
        state: Tracks.State,
        rowDelta: Int = 0,
        rowIndex: Int = -1,
    ): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder()
        for (i in 0 until w * h) {
            val f = (state.sflags[i] ushr 16) and 15
            if (f != 0) {
                out.append(if (f < 10) '0' + f else 'A' + (f - 10))
            } else {
                out.append('a')
            }
        }
        for (x in 0 until w) {
            out.append(',')
            if (x == state.numbers.colS) out.append('S')
            out.append(state.numbers.values[x])
        }
        for (y in 0 until h) {
            out.append(',')
            if (y == state.numbers.rowS) out.append('S')
            val value = state.numbers.values[w + y] +
                if (y == rowIndex) rowDelta else 0
            out.append(value)
        }
        return out.toString()
    }

    private fun withMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private companion object {
        const val SEED = "12345"
    }
}
