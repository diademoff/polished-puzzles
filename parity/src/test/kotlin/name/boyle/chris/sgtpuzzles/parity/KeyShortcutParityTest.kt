package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: fifteen.
 *
 * C applies the one-key shortcuts only when the game returns
 * `MOVE_UNUSED`, so a plain `u` or `r` is an undo or a redo in fifteen.
 * `CTRL+Z` canonicalises to `'\x1A'`, which is the same undo alias, and
 * `CTRL+SHFT+Z` becomes `UI_REDO`. The same sequence goes through the
 * oracle `keys` operation, so the history flags and the save text show
 * that each alias moved the position exactly once.
 */
class KeyShortcutParityTest {

    @Test
    fun `fifteen undo and redo aliases match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("fifteen", PARAMS, SEED)
        val sequence = listOf(
            Buttons.CURSOR_LEFT,
            'u'.code,
            'r'.code,
            Buttons.MOD_CTRL or 'Z'.code,
            Buttons.MOD_CTRL or Buttons.MOD_SHFT or 'Z'.code,
        )

        val expected = oracle.keys(start.state, sequence)
        assertEquals("C keeps one move", 1, moves(expected.state).size)
        assertTrue("C can undo", expected.canUndo)
        assertFalse("C cannot redo", expected.canRedo)

        val midend = Midend.newGame(Fifteen, PARAMS, SEED)
        for (key in sequence) {
            assertEquals(
                "outcome for $key",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, Fifteen.preferredTileSize),
            )
        }
        assertEquals("one move", 1, midend.serialise().values("MOVE").size)
        assertEquals("state position", 2, midend.statePos)
        assertEquals("save", expected.state, midend.serialise().serialise())
        assertEquals("id", expected.id, midend.id())
        assertEquals("status", expected.status, midend.status())
        assertEquals("undo", expected.canUndo, midend.canUndo)
        assertEquals("redo", expected.canRedo, midend.canRedo)
    }

    @Test
    fun `fifteen uppercase and symbol aliases match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("fifteen", PARAMS, SEED)
        val sequence = listOf(
            Buttons.CURSOR_LEFT,
            'U'.code,
            'R'.code,
            '*'.code,
            '#'.code,
        )

        val expected = oracle.keys(start.state, sequence)
        assertEquals("C keeps one move", 1, moves(expected.state).size)

        val midend = Midend.newGame(Fifteen, PARAMS, SEED)
        for (key in sequence) {
            assertEquals(
                "outcome for $key",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, Fifteen.preferredTileSize),
            )
        }
        assertEquals("save", expected.state, midend.serialise().serialise())
        assertEquals("undo", expected.canUndo, midend.canUndo)
        assertEquals("redo", expected.canRedo, midend.canRedo)
    }

    private fun moves(save: String): List<String> =
        SgtpSave.parse(save).values("MOVE")

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "4x4"
    }
}
