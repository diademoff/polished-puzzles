package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: dominosa.
 *
 * The solve payloads with and without `AUXINFO`, the text form, the move
 * grammar and its measured acceptance table, completion, undo and redo,
 * and the keys.
 */
class DominosaGameTest {

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Dominosa", Dominosa.name)
        assertEquals("dominosa", Dominosa.backendName)
        assertEquals(32, Dominosa.preferredTileSize)
        assertTrue(Dominosa.canSolve)
        assertFalse(Dominosa.wantsStatusBar)
        assertFalse(Dominosa.isTimed)
        assertEquals(0, Dominosa.flags)
        assertTrue(Dominosa.canFormatAsText)
    }

    @Test(timeout = 300_000)
    fun `request keys match the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in Dominosa.presets().map { it.params } + "") {
            val save = oracle.newGame("dominosa", params, SEED).state
            val expected = oracle.requestKeys(save)
            val midend = Midend.newGame(Dominosa, params, SEED)
            val kotlin = Dominosa.requestKeys(midend.currentParams)
            assertEquals("arrow mode for '$params'", 3, expected.arrowMode)
            assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, kotlin.arrowMode)
            assertEquals("label count for '$params'", expected.labels.size, kotlin.labels.size)
            for (i in expected.labels.indices) {
                assertEquals("label $i for '$params'", expected.labels[i].label, kotlin.labels[i].name)
                assertEquals("button $i for '$params'", expected.labels[i].button, kotlin.labels[i].button)
                assertFalse(kotlin.labels[i].needsArrows)
            }
        }

        // The full-cursor flag changes nothing for dominosa.
        val save = oracle.newGame("dominosa", "6db", SEED).state
        val full = oracle.requestKeys(save, fullCursor = true)
        assertEquals(3, full.arrowMode)
        assertEquals(
            ArrowMode.ARROWS_LEFT_RIGHT,
            Dominosa.requestKeys(Dominosa.decodeParams("6db"), fullCursor = true).arrowMode,
        )
    }

    @Test(timeout = 300_000)
    fun `the auxiliary solve matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Dominosa.presets().map { it.params } + "") {
                val save = oracle.newGame("dominosa", params, SEED).state
                val payload = SgtpSave.parse(oracle.solve(save).state).value("SOLVE")
                assertNotNull("oracle solve for '$params'", payload)
                assertTrue("the solve is an S move for '$params'", payload!!.startsWith("S"))

                val midend = Midend.newGame(Dominosa, params, SEED)
                assertEquals(
                    "payload for '$params'",
                    payload,
                    Dominosa.solve(midend.state, midend.auxInfo),
                )
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
    fun `the solver path matches the oracle without AUXINFO`() =
        OracleClient().use { oracle ->
            for (params in Dominosa.presets().map { it.params } + "") {
                val save = oracle.newGame("dominosa", params, SEED).state
                val noAux = stripAux(save)
                val payload = SgtpSave.parse(oracle.solve(noAux).state).value("SOLVE")
                assertNotNull("oracle solve for '$params'", payload)
                assertTrue("the solver emits an E pass for '$params'", payload!!.startsWith("S"))
                assertTrue(payload.contains(";D"))
                // At least one rule-out on every preset except the ones
                // that are already trivial to the last domino.
                val midend = Midend.newGame(Dominosa, params, SEED)
                assertEquals(
                    "solver payload for '$params'",
                    payload,
                    Dominosa.solve(midend.state),
                )
            }

            // The measured 651-character fixture.
            val save = oracle.newGame("dominosa", "6db", SEED).state
            val payload = SgtpSave.parse(oracle.solve(stripAux(save)).state).value("SOLVE")!!
            assertEquals(651, payload.length)
            assertEquals(
                payload,
                Dominosa.solve(Midend.newGame(Dominosa, "6db", SEED).state),
            )
            assertTrue(payload.startsWith("S;E3,11;E4,12;E8,16;"))
            assertTrue(payload.endsWith(";D51,52;D53,54"))
        }

    @Test(timeout = 120_000)
    fun `the move grammar matches the measured acceptance table`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            val base = midend.serialise().serialise()
            val initial = midend.state
            val valid = listOf(
                "",
                "D0,1",
                "D0,1;D0,1",
                "D0,1;",
                "S",
                "S;D0,5",
                "S;E0,1",
                "S;E4,5",
                "S;D4,5",
                "D 0,1",
                "D+0,1",
            )
            for (move in valid) {
                val next = Dominosa.executeMove(initial, move)
                assertNotNull("Kotlin should accept '$move'", next)
                val text = withMove(base, move)
                val loaded = oracle.id(text)
                assertEquals("status for '$move'", loaded.status, Dominosa.status(next!!))
                assertEquals(
                    "text for '$move'",
                    oracle.text(text),
                    Dominosa.textFormat(next),
                )
            }

            val invalid = listOf(
                "D1,0",
                "D0,2",
                "D19,18",
                "D3,3",
                "D0,1;E0,1",
                "D0,1;E1,0",
                "X",
                "D0,1X",
                "D0,1;;",
                "D",
                "D0",
                "D0,",
                "E",
                "0,1",
                "D0,0",
                "S;D0,1;X",
            )
            for (move in invalid) {
                assertNull("Kotlin should reject '$move'", Dominosa.executeMove(initial, move))
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
    fun `the text form matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Dominosa.presets().map { it.params } + "") {
                val save = oracle.newGame("dominosa", params, SEED).state
                val midend = Midend.newGame(Dominosa, params, SEED)
                assertEquals(
                    "text for '$params'",
                    oracle.text(save),
                    Dominosa.textFormat(midend.state),
                )
                assertEquals(
                    "midend text for '$params'",
                    oracle.text(save),
                    midend.textFormat(),
                )

                val solved = oracle.solve(save).state
                val solveMove = Dominosa.solve(midend.state, midend.auxInfo)!!
                val solvedState = Dominosa.executeMove(midend.state, solveMove)!!
                assertEquals(
                    "solved text for '$params'",
                    oracle.text(solved),
                    Dominosa.textFormat(solvedState),
                )
            }
        }

    @Test(timeout = 120_000)
    fun `the measured edge and two-digit text match the oracle`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val base = midend.serialise().serialise()
        for (move in listOf("S;E0,1", "S;E0,5", "S;E4,5", "S;D0,1", "S;D0,5", "S;D4,5")) {
            val state = Dominosa.executeMove(midend.state, move)!!
            assertEquals(
                "text for '$move'",
                oracle.text(withMove(base, move)),
                Dominosa.textFormat(state),
            )
        }
    }

    @Test(timeout = 120_000)
    fun `a non-cheated completion raises the event once`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val move = Dominosa.solve(midend.state, midend.auxInfo)!!
        val completed = Dominosa.executeMove(midend.state, move.removePrefix("S;")) as Dominosa.State
        assertTrue(completed.completed)
        assertFalse(completed.cheated)
        val state = midend.state as Dominosa.State

        assertFalse(midend.takeCompletedEvent())
        assertTrue(Dominosa.changedState(midend.ui, state, completed))
        assertFalse(
            "a solve never raises the event",
            Dominosa.changedState(
                midend.ui,
                state,
                Dominosa.executeMove(state, move) as Dominosa.State,
            ),
        )
    }

    @Test(timeout = 120_000)
    fun `undo redo and restart follow the C status`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        assertFalse(midend.canUndo)
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        val solvedSave = midend.serialise().serialise()
        assertEquals(1, oracle.id(solvedSave).status)
        assertTrue(midend.canUndo)
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertTrue(midend.canRedo)
        val undoneSave = midend.serialise().serialise()
        val undone = oracle.id(undoneSave)
        assertEquals(0, undone.status)
        assertFalse(undone.canUndo)
        assertTrue(undone.canRedo)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertEquals(1, oracle.id(midend.serialise().serialise()).status)

        assertTrue(midend.restart())
        assertEquals(0, midend.status())
        assertEquals(0, oracle.id(midend.serialise().serialise()).status)
    }

    @Test
    fun `current key labels are the C strings`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        assertEquals("", Dominosa.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32)
        assertEquals("Place", Dominosa.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Line", Dominosa.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, 32)
        assertEquals("Remove", Dominosa.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("", Dominosa.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))
        assertEquals("", Dominosa.currentKeyLabel(midend.ui, midend.state, 'p'.code))
    }

    @Test(timeout = 120_000)
    fun `a solve then undo and redo keeps the completed status`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Dominosa, "6db", SEED)
        assertTrue(midend.solve())
        val solved = midend.serialise().serialise()
        assertTrue(midend.undo())
        val undone = midend.serialise().serialise()
        assertTrue(midend.redo())
        val redone = midend.serialise().serialise()
        assertEquals(1, oracle.id(solved).status)
        assertEquals(0, oracle.id(undone).status)
        assertEquals(1, oracle.id(redone).status)
        assertEquals(oracle.text(solved), oracle.text(redone))
        assertEquals(Dominosa.textFormat(midend.state), oracle.text(redone))
    }

    @Test
    fun `an invalid move text is rejected without a state`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        assertNull(Dominosa.executeMove(midend.state, "Q"))
        assertEquals(1, midend.stateCount)
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, 'Q'.code, 32))
    }

    private fun stripAux(save: String): String =
        save.lines().filterNot { it.startsWith("AUXINFO") }.joinToString("\n") + "\n"

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
