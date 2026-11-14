package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: fifteen. Seed: 12345.
 *
 * The smoke test proves that the oracle binary runs, that the protocol
 * works, and that a move, an undo, and a redo keep the state
 * consistent. It is not a golden test; see GoldenDescTest for that.
 */
class OracleSmokeTest {

    private val leftButton = 512 // LEFT_BUTTON in puzzles.h

    @Test
    fun `lists the games`() = OracleClient().use { oracle ->
        val games = oracle.games()
        assertEquals(40, games.size)
        assertTrue("fifteen" in games)
        assertTrue("loopy" in games)
        assertFalse("nullgame" in games)
    }

    @Test
    fun `new game produces a game id`() = OracleClient().use { oracle ->
        val state = oracle.newGame("fifteen", "4x4", "12345")
        assertTrue(state.id, state.id.startsWith("4x4:"))
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        assertEquals(0, state.status)
        assertTrue(state.width > 0 && state.height > 0)
    }

    @Test
    fun `move and undo keep the state`() = OracleClient().use { oracle ->
        val start = oracle.newGame("fifteen", "4x4", "12345")
        val startText = oracle.text(start.state)

        // Click the tile left of the gap: the gap starts at the bottom
        // left corner of a 4x4 board.
        val x = start.width * 3 / 8
        val y = start.height * 7 / 8
        val moved = oracle.key(start.state, x, y, leftButton)

        assertTrue(moved.canUndo)
        assertNotEquals(start.state, moved.state)
        assertEquals(start.id, moved.id) // the game description does not change
        assertNotEquals(startText, oracle.text(moved.state))

        val undone = oracle.undo(moved.state)
        assertFalse(undone.canUndo)
        assertTrue(undone.canRedo)
        assertEquals(startText, oracle.text(undone.state))

        val redone = oracle.redo(undone.state)
        assertEquals(moved.state, redone.state)
    }

    @Test
    fun `the solver wins fifteen`() = OracleClient().use { oracle ->
        val start = oracle.newGame("fifteen", "4x4", "12345")
        val solved = oracle.solve(start.state)
        assertEquals(1, solved.status)
    }

    @Test
    fun `an unknown game is an error`() = OracleClient().use { oracle ->
        val error = kotlin.runCatching { oracle.newGame("no-such-game", "", "1") }
        assertTrue(error.exceptionOrNull() is OracleException)
    }
}
