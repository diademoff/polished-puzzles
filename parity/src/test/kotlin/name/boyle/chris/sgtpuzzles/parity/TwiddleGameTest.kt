package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: twiddle.
 *
 * The state, the text format, the status line, the move grammar, the
 * completion path, the history flags, the solver, and the key request are
 * compared with the oracle. Every state comes from a save file, because a
 * trace after a move in a live midend also contains the rotation
 * animation.
 */
class TwiddleGameTest {

    private val defaultDesc = "2,5,6,7,3,1,4,9,8"

    private val completionMoves = listOf(
        "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
        "M0,0,-1", "M0,1,-1", "M1,0,-1", "M0,1,-1",
    )

    @Test
    fun `new games match the oracle for every preset`() = OracleClient().use { oracle ->
        for (preset in Twiddle.presets()) {
            val expected = oracle.newGame("twiddle", preset.params, SEED)
            val actual = Midend.newGame(Twiddle, preset.params, SEED)
            assertEquals("id ${preset.params}", expected.id, actual.id())
            assertEquals("save ${preset.params}", expected.state, actual.serialise().serialise())
            assertEquals("status ${preset.params}", expected.status, actual.status())
            assertEquals("text ${preset.params}", oracle.text(expected.state), actual.textFormat())
            assertEquals("canundo ${preset.params}", expected.canUndo, actual.canUndo)
            assertEquals("canredo ${preset.params}", expected.canRedo, actual.canRedo)
            assertEquals(
                "status text ${preset.params}",
                traceStatus(oracle, expected.state),
                actual.statusText(),
            )
        }
    }

    @Test
    fun `the text format matches the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "3x3n2" to defaultDesc,
            "3x3n2o" to "2r5l6r7l3d1l4l9l8r",
            "3x3n2r" to "1,2,2,3,1,1,2,3,3",
            "5x5n3o" to
                "15u4u11d18d1d16d17d6d3r22r19r12r5l10u13l24l9r20d7u14l21d8d23d2d25r",
            "6x6n4" to
                "7,10,11,9,17,21,13,14,34,36,15,3,27,6,25,35,18,23,22,28,31,16,20,33,5,29,12,8,24,30,1,26,2,32,19,4",
        )

        for ((params, desc) in cases) {
            val save = saveText(params, desc)
            val expected = oracle.text(save)
            val actual = Midend.fromSave(Twiddle, SgtpSave.parse(save)).textFormat()
            assertEquals("text for '$params'", expected, actual)
            // The text is the DESCRIPTION mapped to numbers, so it has one
            // line per row and no trailing blank line.
            assertTrue("a trailing newline for '$params'", actual.endsWith("\n"))
            assertFalse("no blank line for '$params'", actual.endsWith("\n\n"))
        }

        // A sorted description still reports its moves from zero until a
        // move happens; this is the upstream quirk.
        assertEquals("Moves: 0", statusOf(oracle, saveText("3x3n2", "1,2,3,4,5,6,7,8,9")))
        assertEquals(
            "COMPLETED! Moves: 1",
            statusOf(oracle, saveText("3x3n2", "1,2,3,4,5,6,7,8,9", listOf("M0,0,4"))),
        )
    }

    @Test
    fun `the status line matches the oracle, including the target suffix`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                "3x3n2" to emptyList<String>(),
                "3x3n2" to listOf("M0,0,1"),
                "3x3n2m10" to emptyList<String>(),
                "3x3n2m10" to listOf("M0,0,1"),
                "3x3n2m1" to listOf("M0,0,1"),
                "3x3n2" to completionMoves,
            )

            for ((params, moves) in cases) {
                val save = saveText(params, defaultDesc, moves)
                val expected = oracle.status(save)
                val actual = Midend.fromSave(Twiddle, SgtpSave.parse(save))
                assertEquals("status for '$params' $moves", expected.status, actual.status())
                assertEquals("status text for '$params' $moves", statusOf(oracle, save), actual.statusText())
            }

            // One more move keeps the winning move number.
            val after = completionMoves + "M0,0,1"
            val save = saveText("3x3n2", defaultDesc, after)
            assertEquals("COMPLETED! Moves: 8", statusOf(oracle, save))
            assertEquals(8, (Midend.fromSave(Twiddle, SgtpSave.parse(save)).state as Twiddle.State).completed)
        }

    @Test
    fun `the solver matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("3x3n2", defaultDesc)
        val expected = oracle.solve(save)
        val actual = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        assertEquals("S", Twiddle.solve(actual.state))
        assertTrue(actual.solve())

        assertEquals("solved save", expected.state, actual.serialise().serialise())
        assertEquals("solved id", expected.id, actual.id())
        assertEquals("solved status", expected.status, actual.status())
        assertEquals("solved text", oracle.text(expected.state), actual.textFormat())
        assertEquals("Moves since auto-solve: 0", statusOf(oracle, expected.state))
        assertEquals("Moves since auto-solve: 0", actual.statusText())
        assertEquals((1..9).toList(), (actual.state as Twiddle.State).grid.map { it / 4 })
        assertTrue("a solve clears every orientation", (actual.state as Twiddle.State).grid.all { it and 3 == 0 })

        // A solve on an orientable board also clears the orientation.
        val orientedSave = saveText("3x3n2o", "2r5l6r7l3d1l4l9l8r")
        val oriented = Midend.fromSave(Twiddle, SgtpSave.parse(orientedSave))
        assertTrue(oriented.solve())
        assertEquals("Moves since auto-solve: 0", oriented.statusText())
        assertEquals(oracle.solve(orientedSave).state, oriented.serialise().serialise())
    }

    @Test
    fun `undo and redo match the oracle across a completion`() = OracleClient().use { oracle ->
        val save = saveText("3x3n2", defaultDesc, completionMoves)
        val expected = oracle.id(save)
        assertEquals(1, expected.status)
        assertTrue(expected.canUndo)
        assertFalse(expected.canRedo)

        val actual = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        assertTrue(actual.canUndo)
        assertFalse(actual.canRedo)

        val expectedUndo = oracle.undo(save)
        assertTrue(actual.undo())
        assertEquals("undone save", expectedUndo.state, actual.serialise().serialise())
        assertEquals("undone id", expectedUndo.id, actual.id())
        assertEquals("undone status", expectedUndo.status, actual.status())
        assertEquals("undone flags", expectedUndo.canUndo, actual.canUndo)
        assertEquals("undone redo", expectedUndo.canRedo, actual.canRedo)
        assertEquals("Moves: 7", statusOf(oracle, expectedUndo.state))
        assertEquals("Moves: 7", actual.statusText())

        val expectedRedo = oracle.redo(expectedUndo.state)
        assertTrue(actual.redo())
        assertEquals("redone save", expectedRedo.state, actual.serialise().serialise())
        assertEquals("redone id", expectedRedo.id, actual.id())
        assertEquals("redone status", expectedRedo.status, actual.status())
        assertEquals("COMPLETED! Moves: 8", actual.statusText())

        // An undo of a solve keeps the solve record and the solve line.
        val solved = oracle.solve(saveText("3x3n2", defaultDesc))
        val solvedUndo = oracle.undo(solved.state)
        val solveMidend = Midend.fromSave(Twiddle, SgtpSave.parse(solved.state))
        assertTrue(solveMidend.undo())
        assertEquals(solvedUndo.state, solveMidend.serialise().serialise())
        assertEquals("Moves: 0", solveMidend.statusText())
    }

    @Test
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "M0,0,1",
            "M 0,0,1",
            "M0, 0,1",
            "M+0,+0,+1",
            "M0,0,1junk",
            "M0,0,1,2",
            "M0,0,100",
            "M0,0,4",
        )
        for (move in accepted) {
            val save = saveText("3x3n2", defaultDesc, listOf(move))
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            assertEquals("save for '$move'", expected.state, actual.serialise().serialise())
            assertEquals("id for '$move'", expected.id, actual.id())
            assertEquals("status for '$move'", expected.status, actual.status())
            assertEquals("text for '$move'", oracle.text(expected.state), actual.textFormat())
        }

        val rejected = listOf(
            "M0 ,0,1",
            "M0,0 ,1",
            "M0,0",
            "M-1,0,1",
            "M3,0,1",
            "s",
            "S ",
        )
        for (move in rejected) {
            val save = saveText("3x3n2", defaultDesc, listOf(move))
            val expected = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertTrue("the oracle rejects '$move'", expected != null)
            assertNull("Kotlin rejects '$move'", Twiddle.executeMove(Twiddle.newState(Twiddle.decodeParams("3x3n2"), defaultDesc), move))
        }
    }

    @Test
    fun `the completion event is not observable in the save file`() = OracleClient().use { oracle ->
        // The last move completes the board on both sides. The event is a
        // midend hook, so only the resulting save, id, status, and text
        // are compared here; the engine test pins `changedState`.
        val lastMove = completionMoves.last()
        val before = saveText("3x3n2", defaultDesc, completionMoves.dropLast(1))
        val afterMerge = saveText("3x3n2", defaultDesc, completionMoves)

        val expectedBefore = oracle.id(before)
        val expectedAfter = oracle.id(afterMerge)
        assertEquals(0, expectedBefore.status)
        assertEquals(1, expectedAfter.status)

        val midend = Midend.fromSave(Twiddle, SgtpSave.parse(before))
        val oldState = midend.state as Twiddle.State
        assertEquals(0, midend.status())
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 120, Buttons.RIGHT_BUTTON, 48),
        )
        val newState = midend.state as Twiddle.State
        assertEquals("the click is $lastMove", expectedAfter.state, midend.serialise().serialise())
        assertEquals(1, midend.status())
        assertEquals(8, newState.moveCount)
        assertFalse("the completing click changes the board", oldState.grid.contentEquals(newState.grid))
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `keys and the palette metadata match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("twiddle", "3x3n2", SEED).state
        val expected = oracle.requestKeys(save)
        val actual = Twiddle.requestKeys(Twiddle.decodeParams("3x3n2"))
        assertEquals("arrow mode", 3, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertTrue("no labels", expected.labels.isEmpty())
        assertTrue("no labels", actual.labels.isEmpty())

        assertTrue(Twiddle.wantsStatusBar)
        assertTrue(Twiddle.canSolve)
    }

    private fun statusOf(oracle: OracleClient, save: String): String? =
        traceStatus(oracle, save)

    private fun traceStatus(oracle: OracleClient, save: String): String? {
        val ops = (oracle.trace(save).field("ops") as JsonValue.Arr).items
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
        solve: String? = null,
    ): String {
        val stateCount = 1 + moves.size + (if (solve != null) 1 else 0)
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Twiddle.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", stateCount.toString()),
            SaveRecord("STATEPOS", stateCount.toString()),
        )
        if (solve != null) records += SaveRecord("SOLVE", solve)
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
