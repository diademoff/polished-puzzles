package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: pearl.
 *
 * New game, the loose move grammar, the hint, completion, undo and
 * redo, and the solve payloads. The oracle replays the MOVE and SOLVE
 * records of a crafted save, so every accepted move is compared through
 * the C `execute_move`.
 */
class PearlGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = Pearl.presets().map { it.params }

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Pearl", Pearl.name)
        assertEquals("pearl", Pearl.backendName)
        assertEquals(31, Pearl.preferredTileSize)
        assertTrue(Pearl.canSolve)
        assertTrue(Pearl.canFormatAsText)
        assertFalse(Pearl.wantsStatusBar)
        assertFalse(Pearl.isTimed)
        assertEquals(0, Pearl.flags)
    }

    @Test
    fun `a new game has the C text and status`() = OracleClient().use { oracle ->
        for (params in presets + "") {
            val expected = oracle.newGame("pearl", params, SEED)
            val midend = Midend.newGame(Pearl, params, SEED)
            assertEquals("'$params' id", expected.id, midend.id())
            assertEquals("'$params' text", oracle.text(expected.state), midend.textFormat())
            assertEquals("'$params' status", expected.status, midend.status())
            assertFalse("'$params' canUndo", midend.canUndo)
            assertFalse("'$params' canRedo", midend.canRedo)
        }
    }

    @Test
    fun `request keys is the C null hook`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "8x8dt", SEED).state
        val normal = oracle.requestKeys(save)
        val kotlinNormal = Pearl.requestKeys(Pearl.decodeParams("8x8dt"))
        assertEquals(3, normal.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, kotlinNormal.arrowMode)
        assertTrue(kotlinNormal.labels.isEmpty())

        val full = oracle.requestKeys(save, fullCursor = true)
        val kotlinFull = Pearl.requestKeys(Pearl.decodeParams("8x8dt"), fullCursor = true)
        assertEquals(1, full.arrowMode)
        assertEquals(ArrowMode.ARROWS_ONLY, kotlinFull.arrowMode)
        assertTrue(kotlinFull.labels.isEmpty())
    }

    @Test
    fun `the accepted moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val moves = listOf(
            "",
            "F1,0,0;F4,1,0",
            "M1,0,0;M4,1,0",
            "L1,0,0;L4,1,0;L8,1,0;L2,1,1",
            "L1,0,0;L4,1,0;L8,1,0;L2,1,1;N8,1,0;N2,1,1",
            "M1,0,0;M4,1,0;R1,0,0;R4,1,0",
            "F1,0,0;F4,1,0;F8,1,0;F2,1,1",
            "F1,0,0;F4,1,0;F1,0,0;F4,1,0",
            "S",
            "S;F1,0,0;F4,1,0",
            "H",
            "S;H",
            "F 1, 0, 0;F 4, 1, 0",
            "F+1,+0,+0;F+4,+1,+0",
            "F01,00,00;F04,01,00",
            "F1,0,0;F4,1,0;",
        )
        for (move in moves) {
            val kotlin = Midend.fromSave(Pearl, SgtpSave.parse(base.state))
            val state = Pearl.executeMove(kotlin.state, move)
            assertNotNull("Kotlin must accept '$move'", state)
            val save = withMoves(base.state, listOf(move))
            val expected = oracle.id(save)
            assertEquals("'$move' id", base.id, expected.id)
            assertEquals("'$move' status", expected.status, Pearl.status(state!!))
            assertEquals(
                "'$move' text",
                oracle.text(save),
                Pearl.textFormat(state),
            )
            val loaded = Midend.fromSave(Pearl, SgtpSave.parse(save))
            assertEquals("'$move' reload", state, loaded.state)
        }

        // H is not followed by another token, but an empty move is a
        // state after check_completion.
        val empty = Midend.fromSave(Pearl, SgtpSave.parse(withMoves(base.state, listOf(""))))
        assertEquals(2, empty.stateCount)
        assertEquals("", empty.serialise().value("MOVE"))
    }

    @Test
    fun `the rejected moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val moves = listOf(
            "X",
            "F1,0",
            "F1,0,0x;F4,1,0",
            "F1,0,0;;F4,1,0",
            "F16,0,0;F4,1,0",
            "F-1,0,0;F4,1,0",
            "F1,6,0;F4,5,0",
            "F1,0,6;F4,1,6",
            "F1,0,0",
            "F1,0,0;M4,1,0",
            "F1,0,0;F4,1,0;M4,1,0",
            "M1,0,0;F1,0,0;F4,1,0",
            "H;F1,0,0;F4,1,0",
            "H;",
        )
        for (move in moves) {
            assertNull("Kotlin must reject '$move'", Pearl.executeMove(
                Midend.fromSave(Pearl, SgtpSave.parse(base.state)).state,
                move,
            ))
            val error = try {
                oracle.id(withMoves(base.state, listOf(move)))
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("'$move' error", "Save file contained an invalid move", error)
        }
    }

    @Test
    fun `the hint keeps undecided lines and the oracle agrees`() = OracleClient().use { oracle ->
        val base = withDesc(oracle.newGame("pearl", "8x8de", "1").state, "zzl")
        val line = "F1,0,0;F4,1,0"
        val save = withMoves(base, listOf(line, "H"))
        val expected = oracle.id(save)
        val text = oracle.text(save)

        // Apply the line through executeMove, then the hint.
        val blank = Midend.fromSave(Pearl, SgtpSave.parse(base))
        val afterLine = Pearl.executeMove(blank.state, line) as Pearl.State
        val afterHint = Pearl.executeMove(afterLine, "H") as Pearl.State
        val loaded = Midend.fromSave(Pearl, SgtpSave.parse(save)).state
        assertEquals("the ambiguous cells keep the player lines", afterHint, loaded)
        assertEquals(afterLine.lines.toList(), afterHint.lines.toList())
        assertEquals(expected.status, Pearl.status(afterHint))
        assertEquals(text, Pearl.textFormat(afterHint))
        assertFalse(afterHint.usedSolve)
    }

    @Test
    fun `completion is sticky and the hint raises the event`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pearl", "6x6de", SEED)
        val solved = oracle.solve(start.state)
        assertEquals(1, solved.status)

        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertFalse("a solve never raises the event", midend.takeCompletedEvent())

        val undone = oracle.undo(solved.state)
        assertEquals(0, undone.status)
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(
            "a redo of a solve still has used_solve",
            midend.redo() && midend.takeCompletedEvent(),
        )
        assertEquals(1, midend.status())

        // Break one edge pair of the loop. The status stays 1 because
        // `completed` is sticky.
        val solvedState = midend.state as Pearl.State
        val edge = firstEdge(solvedState)
        val breakMove = "N${edge[0]},${edge[1]},${edge[2]};" +
            "N${edge[3]},${edge[4]},${edge[5]}"
        val broken = Pearl.executeMove(solvedState, breakMove) as Pearl.State
        assertEquals(1, Pearl.status(broken))
        val brokenSave = appendMove(solved.state, breakMove)
        val expected = oracle.id(brokenSave)
        assertEquals(1, expected.status)
        assertEquals(oracle.text(brokenSave), Pearl.textFormat(broken))

        // A hint that completes a fresh board does raise the event, and
        // a redo into that completed state raises it again.
        val hinted = Midend.newGame(Pearl, "6x6de", SEED)
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            hinted.processKey(0, 0, 'H'.code, 31),
        )
        assertEquals(1, hinted.status())
        assertTrue(hinted.takeCompletedEvent())
        assertTrue(hinted.undo())
        assertFalse("leaving the completed state", hinted.takeCompletedEvent())
        assertTrue(hinted.redo())
        assertTrue("a redo into completed raises it", hinted.takeCompletedEvent())
    }

    @Test(timeout = 300_000)
    fun `the solve payload for every preset matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets + "") {
            val save = oracle.newGame("pearl", params, SEED).state
            val solved = oracle.solve(save)
            val payload = SgtpSave.parse(solved.state).value("SOLVE")
            assertNotNull("oracle solve for '$params'", payload)
            assertTrue("the solve is an S move for '$params'", payload!!.startsWith("S"))

            val midend = Midend.newGame(Pearl, params, SEED)
            val attempt = Pearl.solveAttempt(midend.state)
            assertEquals("payload for '$params'", payload, (attempt as Pearl.SolveResult.Move).text)
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

    @Test
    fun `the solve diffs the current board and the no aux path agrees`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        // Pick an edge that is not part of the solution, so the solve
        // has to erase it.
        val solution = solveLines(midend.auxInfo!!, 6 * 6)
        val line = missingEdge(solution, 6, 6)
        val played = withMoves(base.state, listOf(line))
        val solvedPlayed = oracle.solve(played)
        val payload = SgtpSave.parse(solvedPlayed.state).value("SOLVE")!!
        assertTrue("the manual cell is in the diff", payload.contains(";R"))

        val loaded = Midend.fromSave(Pearl, SgtpSave.parse(played))
        assertEquals(payload, (Pearl.solveAttempt(loaded.state) as Pearl.SolveResult.Move).text)
        val repaired = Pearl.executeMove(loaded.state, payload) as Pearl.State
        assertEquals(solution.toList(), repaired.lines.toList())

        val noAux = withoutAux(base.state)
        val solvedNoAux = oracle.solve(withMoves(noAux, listOf(line)))
        val noAuxPayload = SgtpSave.parse(solvedNoAux.state).value("SOLVE")
        assertEquals(payload, noAuxPayload)
        val kotlinNoAux = Midend.fromSave(Pearl, SgtpSave.parse(withMoves(noAux, listOf(line))))
        assertEquals(
            noAuxPayload,
            (Pearl.solveAttempt(kotlinNoAux.state) as Pearl.SolveResult.Move).text,
        )
    }

    @Test
    fun `an ambiguous board solves from the empty initial board`() = OracleClient().use { oracle ->
        val base = withoutAux(withDesc(oracle.newGame("pearl", "8x8de", "1").state, "zzl"))

        val blankSolved = oracle.solve(base)
        val blankPayload = SgtpSave.parse(blankSolved.state).value("SOLVE")
        val blankKotlin = Midend.fromSave(Pearl, SgtpSave.parse(base))
        assertEquals(
            blankPayload,
            (Pearl.solveAttempt(blankKotlin.state) as Pearl.SolveResult.Move).text,
        )

        val line = "F1,0,0;F4,1,0"
        val played = withMoves(base, listOf(line))
        val playedSolved = oracle.solve(played)
        val playedPayload = SgtpSave.parse(playedSolved.state).value("SOLVE")
        assertEquals("S;R0,0,0;R0,1,0", playedPayload)
        val playedKotlin = Midend.fromSave(Pearl, SgtpSave.parse(played))
        assertEquals(
            playedPayload,
            (Pearl.solveAttempt(playedKotlin.state) as Pearl.SolveResult.Move).text,
        )

        // A solved board solves to the single S, which only sets used_solve.
        val solvedAgain = oracle.solve(playedSolved.state)
        assertEquals("S", SgtpSave.parse(solvedAgain.state).values("SOLVE").last())
    }

    @Test
    fun `an impossible board returns the C error text and no solve`() = OracleClient().use { oracle ->
        val base = withoutAux(withDesc(oracle.newGame("pearl", "8x8de", "1").state, "B".repeat(64)))
        val error = try {
            oracle.solve(base)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Unable to find solution", error)
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(base))
        assertEquals(
            Pearl.SolveResult.Error("Unable to find solution"),
            Pearl.solveAttempt(midend.state),
        )
        assertNull(Pearl.solve(midend.state))
    }

    @Test
    fun `current key label covers start stop cancel and the default`() {
        val s = Midend.newGame(Pearl, "6x6de", SEED).state
        val ui = Pearl.newUi(s) as Pearl.Ui
        assertEquals("", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.cursorActive = true
        assertEquals("Start", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.ndragcoords = 0
        assertEquals("Stop", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("Cancel", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))
        assertEquals("", Pearl.currentKeyLabel(ui, s, 'a'.code))
    }

    /** Decodes an auxiliary loop into one line mask per cell. */
    private fun solveLines(aux: String, size: Int): IntArray = IntArray(size) {
        val c = aux[it]
        if (c in '0'..'9') c - '0' else c - 'A' + 10
    }

    /** A move that lays one edge that the solution does not contain. */
    private fun missingEdge(lines: IntArray, w: Int, h: Int): String {
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x + 1 < w && (lines[y * w + x] and 1) == 0) {
                    return "F1,$x,$y;F4,${x + 1},$y"
                }
                if (y + 1 < h && (lines[y * w + x] and 8) == 0) {
                    return "F8,$x,$y;F2,$x,${y + 1}"
                }
            }
        }
        error("the solution has no missing edge")
    }

    private fun firstEdge(s: Pearl.State): List<Int> {
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val line = s.lines[y * s.width + x]
                if ((line and 1) != 0) return listOf(1, x, y, 4, x + 1, y)
                if ((line and 8) != 0) return listOf(8, x, y, 2, x, y + 1)
            }
        }
        error("the board has no edge")
    }

    private fun withoutAux(save: String): String =
        SgtpSave.of(SgtpSave.parse(save).records.filter { it.key != "AUXINFO" }).serialise()

    private fun withDesc(save: String, desc: String): String {
        val records = SgtpSave.parse(save).records.map {
            when (it.key) {
                "DESC" -> SaveRecord("DESC", desc)
                "AUXINFO" -> null
                else -> it
            }
        }.filterNotNull()
        return SgtpSave.of(records).serialise()
    }

    private fun withMoves(save: String, moves: List<String>): String =
        moves.fold(save) { text, move -> appendMove(text, move) }

    /**
     * Appends one MOVE to a save that is at the end of its history. The
     * existing SOLVE and MOVE records stay, so a move after a solve is
     * replayed from the solved state.
     */
    private fun appendMove(save: String, move: String): String {
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
