package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. The one-key shortcuts of `midend_really_process_key`.
 *
 * C calls the game's `interpret_move` first for every key that is not a
 * UI fake key. The shortcuts apply only when the game answers
 * `MOVE_UNUSED`, in this order: new game, undo, redo, solve, quit. A
 * UI fake key never reaches the game.
 *
 * The shell owns the new game and quit actions, so this seam has no
 * engine action for them; their keys return `Unused`.
 */
class KeyShortcutTest {

    /** A game that notes the buttons it sees and can consume one key. */
    private class RecordingGame(private val delegate: Game) : Game by delegate {
        val buttons = ArrayList<Int>()
        private var consumed: Int? = null

        fun consume(button: Int) {
            consumed = button
        }

        override fun interpretMove(
            state: Game.State,
            ui: Game.Ui,
            x: Int,
            y: Int,
            button: Int,
            tileSize: Int,
        ): MoveResult {
            buttons += button
            if (button == consumed) return MoveResult.NoEffect
            return delegate.interpretMove(state, ui, x, y, button, tileSize)
        }
    }

    @Test
    fun `every undo alias undoes exactly one move`() {
        val aliases = listOf(
            'u'.code,
            'U'.code,
            '*'.code,
            // CTRL+Z canonicalises to the control code 0x1A.
            Buttons.MOD_CTRL or 'Z'.code,
        )
        for (alias in aliases) {
            val midend = movedFifteen()
            assertEquals(2, midend.statePos, "the click must add a move")

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, alias, TILE),
                "outcome for $alias",
            )
            assertEquals(1, midend.statePos, "undo moves one step back for $alias")
            assertEquals(2, midend.stateCount, "undo adds no history for $alias")
            assertFalse(midend.canUndo, "nothing left to undo for $alias")
            assertTrue(midend.canRedo, "the redo branch exists for $alias")
        }
    }

    @Test
    fun `every redo alias redoes exactly one move`() {
        val aliases = listOf(
            'r'.code,
            'R'.code,
            '#'.code,
            Buttons.UI_REDO,
        )
        for (alias in aliases) {
            val midend = movedFifteen()
            assertTrue(midend.undo())
            assertEquals(1, midend.statePos)

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, alias, TILE),
                "outcome for $alias",
            )
            assertEquals(2, midend.statePos, "redo moves one step forward for $alias")
            assertEquals(2, midend.stateCount, "redo adds no history for $alias")
            assertTrue(midend.canUndo, "undo is available after the redo for $alias")
            assertFalse(midend.canRedo, "nothing left to redo for $alias")
        }
    }

    @Test
    fun `an undo alias with no history has no effect`() {
        val midend = Midend.fromGameId(Fifteen, FIFTEEN_ID)
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, 'u'.code, TILE),
        )
        assertEquals(1, midend.statePos)
    }

    @Test
    fun `a game key wins over the undo alias`() {
        val recorded = RecordingGame(Fifteen)
        recorded.consume('u'.code)
        val midend = Midend.newGame(recorded, "4x4", SEED)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(96, 192, Buttons.LEFT_BUTTON, TILE),
        )
        val afterMove = midend.serialise().serialise()

        // The game uses 'u', so the shortcut chain is not reached.
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, 'u'.code, TILE),
        )
        assertEquals(2, midend.statePos, "the consumed key must not undo")
        assertEquals(afterMove, midend.serialise().serialise())
        assertFalse(midend.canRedo)
        assertEquals(listOf(Buttons.LEFT_BUTTON, 'u'.code), recorded.buttons)

        // A UI fake key is not a game key, so it still undoes.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.UI_UNDO, TILE),
        )
        assertEquals(1, midend.statePos)
    }

    @Test
    fun `ui fake keys never reach the game`() {
        val uiKeys = listOf(
            Buttons.UI_NEWGAME,
            Buttons.UI_UNDO,
            Buttons.UI_REDO,
            Buttons.UI_SOLVE,
            Buttons.UI_QUIT,
        )
        for (key in uiKeys) {
            val recorded = RecordingGame(Fifteen)
            val midend = Midend.newGame(recorded, "4x4", SEED)
            midend.processKey(0, 0, key, TILE)
            assertTrue(recorded.buttons.isEmpty(), "UI key $key reached the game")
        }
    }

    @Test
    fun `ui solve on a game that cannot solve is unused`() {
        for (key in listOf(0x13, Buttons.UI_SOLVE)) {
            val recorded = RecordingGame(SameGame)
            val midend = Midend.newGame(recorded, SAME_GAME_PARAMS, SEED)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, key, SameGame.preferredTileSize),
                "outcome for $key on a game without a solver",
            )
            assertEquals(1, midend.stateCount, "a rejected solve adds no history")
            if (key == Buttons.UI_SOLVE) {
                assertTrue(recorded.buttons.isEmpty(), "UI_SOLVE reached the game")
            } else {
                assertEquals(
                    listOf(0x13),
                    recorded.buttons,
                    "the control code is a game key, as in C",
                )
            }
        }
    }

    @Test
    fun `the solve aliases solve a game that can solve`() {
        for (key in listOf(0x13, Buttons.UI_SOLVE)) {
            val midend = Midend.newGame(Fifteen, "4x4", SEED)
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, TILE),
            )
            assertEquals(1, midend.status())
            assertEquals(2, midend.statePos)
        }
    }

    @Test
    fun `the new game and quit aliases have no engine action`() {
        val keys = listOf(
            'n'.code,
            'N'.code,
            0x0E,
            Buttons.UI_NEWGAME,
            'q'.code,
            'Q'.code,
            0x11,
            Buttons.UI_QUIT,
        )
        for (key in keys) {
            val midend = movedFifteen()
            val before = midend.serialise().serialise()
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, key, TILE),
                "outcome for $key",
            )
            assertEquals(2, midend.statePos, "the key must not restart for $key")
            assertEquals(before, midend.serialise().serialise())
        }
    }

    /** Fifteen with one real move from a known game ID. */
    private fun movedFifteen(): Midend {
        val midend = Midend.fromGameId(Fifteen, FIFTEEN_ID)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(96, 192, Buttons.LEFT_BUTTON, TILE),
        )
        return midend
    }

    private companion object {
        const val SEED = "12345"
        const val SAME_GAME_PARAMS = "5x5c3s2"
        const val TILE = 48
        const val FIFTEEN_ID = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
    }
}
