package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. The keyboard modifier contract of the game seam.
 *
 * The midend canonicalises the button before the game sees it, as
 * `midend_process_key` does. `CTRL` and `SHFT` stay only on a cursor
 * move and on TAB; each game still drops the modifiers it does not use.
 * A modified button that the midend keeps must therefore behave exactly
 * like the plain one, and a rejected modifier must not reach the game.
 */
class KeyModifierTest {

    private data class Step(val x: Int, val y: Int, val button: Int, val modified: Int)

    /** Fifteen with a note of the button that reaches the game seam. */
    private class RecordingGame : Game by Fifteen {
        var calls = 0
        var seen: Int? = null

        override fun interpretMove(
            state: Game.State,
            ui: Game.Ui,
            x: Int,
            y: Int,
            button: Int,
            tileSize: Int,
        ): MoveResult {
            calls++
            seen = button
            return Fifteen.interpretMove(state, ui, x, y, button, tileSize)
        }
    }

    /**
     * Sends the same sequence to two midends, one with plain buttons and
     * one with modified buttons, and compares the result of every step.
     * The modified buttons are ones that the midend strips or that the
     * game drops, so both sides behave alike.
     */
    private fun assertModifiersIgnored(
        game: Game,
        newMidend: () -> Midend,
        steps: List<Step>,
        tileSize: Int = game.preferredTileSize,
    ) {
        val plain = newMidend()
        val modified = newMidend()
        val before = modified.serialise().serialise()

        for ((index, step) in steps.withIndex()) {
            val expected = plain.processKey(step.x, step.y, step.button, tileSize)
            val actual = modified.processKey(step.x, step.y, step.modified, tileSize)
            assertEquals(expected, actual, "outcome of step $index")
            assertEquals(
                plain.serialise().serialise(),
                modified.serialise().serialise(),
                "save after step $index",
            )
            assertEquals(
                plain.statusText(),
                modified.statusText(),
                "status text after step $index",
            )
        }

        assertTrue(
            modified.serialise().serialise() != before,
            "$game: the modified input must change the state",
        )
        assertEquals(plain.id(), modified.id(), "id")
        assertEquals(plain.status(), modified.status(), "status")
        assertEquals(plain.canUndo, modified.canUndo, "undo")
        assertEquals(plain.canRedo, modified.canRedo, "redo")
    }

    @Test
    fun `fifteen ignores modifiers on a click and on a cursor move`() {
        val id = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        fun fresh() = Midend.fromGameId(Fifteen, id)

        // The click slides tile 15 into the gap at (2,3).
        assertModifiersIgnored(
            Fifteen,
            ::fresh,
            listOf(
                Step(
                    x = 96,
                    y = 192,
                    button = Buttons.LEFT_BUTTON,
                    modified = Buttons.LEFT_BUTTON or Buttons.MOD_SHFT,
                ),
            ),
        )

        // The cursor starts on the gap, so one left move is a real move.
        assertModifiersIgnored(
            Fifteen,
            ::fresh,
            listOf(
                Step(
                    x = 0,
                    y = 0,
                    button = Buttons.CURSOR_LEFT,
                    modified = Buttons.CURSOR_LEFT or Buttons.MOD_SHFT,
                ),
            ),
        )
    }

    @Test
    fun `slant ignores modifiers on a click and on a cursor select`() {
        val tile = Slant.preferredTileSize
        fun fresh() = Midend.newGame(Slant, "4x4", SEED)

        assertModifiersIgnored(
            Slant,
            ::fresh,
            listOf(
                Step(
                    x = 2 * tile,
                    y = 2 * tile,
                    button = Buttons.LEFT_BUTTON,
                    modified = Buttons.LEFT_BUTTON or Buttons.MOD_SHFT,
                ),
            ),
            tile,
        )

        // The first select shows the cursor, the second one turns a slash.
        assertModifiersIgnored(
            Slant,
            ::fresh,
            listOf(
                Step(
                    x = 0,
                    y = 0,
                    button = Buttons.CURSOR_SELECT,
                    modified = Buttons.CURSOR_SELECT,
                ),
                Step(
                    x = 0,
                    y = 0,
                    button = Buttons.CURSOR_SELECT,
                    modified = Buttons.CURSOR_SELECT or Buttons.MOD_SHFT,
                ),
            ),
            tile,
        )
    }

    @Test
    fun `samegame ignores modifiers on a left click and on a cursor select`() {
        val params = "5x5c3s2"
        val tile = SameGame.preferredTileSize
        val state = Midend.newGame(SameGame, params, SEED).state as SameGame.State
        val (column, row) = firstGroup(state, 2)
        val x = (column + 1) * tile
        val y = (row + 1) * tile
        fun fresh() = Midend.newGame(SameGame, params, SEED)

        // The first tap selects the group, the second one removes it.
        assertModifiersIgnored(
            SameGame,
            ::fresh,
            listOf(
                Step(
                    x,
                    y,
                    Buttons.LEFT_BUTTON,
                    Buttons.LEFT_BUTTON or Buttons.MOD_SHFT,
                ),
                Step(
                    x,
                    y,
                    Buttons.LEFT_BUTTON,
                    Buttons.LEFT_BUTTON or Buttons.MOD_SHFT,
                ),
            ),
            tile,
        )

        // Move the cursor from (0,0) to the group, select, then remove.
        val cursorSteps = ArrayList<Step>()
        repeat(column) {
            cursorSteps += Step(
                0,
                0,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT or Buttons.MOD_NUM_KEYPAD,
            )
        }
        repeat(row) {
            cursorSteps += Step(
                0,
                0,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN or Buttons.MOD_NUM_KEYPAD,
            )
        }
        cursorSteps += Step(0, 0, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT)
        cursorSteps += Step(
            0,
            0,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT or Buttons.MOD_SHFT,
        )
        assertModifiersIgnored(SameGame, ::fresh, cursorSteps, tile)
    }

    @Test
    fun `flood ignores modifiers on a click and on a cursor select`() {
        val params = "4x4c3m0"
        val tile = Flood.preferredTileSize
        val state = Midend.newGame(Flood, params, SEED).state as Flood.State
        val target = (0 until state.n).first { state.grid[it] != state.grid[0] }
        val column = target % state.width
        val row = target / state.width
        fun fresh() = Midend.newGame(Flood, params, SEED)

        assertModifiersIgnored(
            Flood,
            ::fresh,
            listOf(
                Step(
                    x = (column + 1) * tile,
                    y = (row + 1) * tile,
                    button = Buttons.LEFT_BUTTON,
                    modified = Buttons.LEFT_BUTTON or Buttons.MOD_SHFT,
                ),
            ),
            tile,
        )

        // Move the cursor from (0,0) to the first cell of another colour.
        val cursorSteps = ArrayList<Step>()
        repeat(column) {
            cursorSteps += Step(
                0,
                0,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT or Buttons.MOD_NUM_KEYPAD,
            )
        }
        repeat(row) {
            cursorSteps += Step(
                0,
                0,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN or Buttons.MOD_NUM_KEYPAD,
            )
        }
        cursorSteps += Step(
            0,
            0,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT or Buttons.MOD_SHFT,
        )
        assertModifiersIgnored(Flood, ::fresh, cursorSteps, tile)
    }

    @Test
    fun `the midend canonicalises the button before the game`() {
        val recorded = RecordingGame()
        val midend = Midend.newGame(recorded, "4x4", SEED)
        val tile = Fifteen.preferredTileSize

        // CTRL and SHFT stay on a cursor move.
        val modified = Buttons.CURSOR_LEFT or Buttons.MOD_SHFT
        midend.processKey(0, 0, modified, tile)
        assertEquals(modified, recorded.seen, "a cursor move keeps its modifiers")
        assertEquals(1, recorded.calls)

        // SHFT is stripped from a key that is not a cursor move.
        midend.processKey(0, 0, 'h'.code or Buttons.MOD_SHFT, tile)
        assertEquals('h'.code, recorded.seen, "a hint key loses SHFT")
        assertEquals(2, recorded.calls)

        // A UI action is dispatched before the game and never reaches it.
        midend.processKey(0, 0, Buttons.UI_UNDO or Buttons.MOD_SHFT, tile)
        assertEquals(2, recorded.calls, "a UI key is not a game key")

        // CTRL on a UI key is rejected, because its code is at least
        // 0x20 and it is not a cursor move.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, Buttons.UI_UNDO or Buttons.MOD_CTRL, tile),
        )
        assertEquals(2, recorded.calls, "a rejected key is not a game key")
    }

    @Test
    fun `a modified UI key is still a UI action`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED)
        val tile = Fifteen.preferredTileSize
        assertTrue(midend.solve())
        val states = midend.stateCount
        assertEquals(1, midend.status())

        // Fifteen does not use UI_UNDO as a game key, so the state below
        // can only change through the UI dispatch.
        assertEquals(
            MoveResult.Unused,
            Fifteen.interpretMove(midend.state, midend.ui, 0, 0, Buttons.UI_UNDO, tile),
        )

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.UI_UNDO or Buttons.MOD_SHFT, tile),
        )
        assertEquals(1, midend.statePos, "undo moves the position once")
        assertEquals(states, midend.stateCount, "undo adds no history")
        assertEquals(0, midend.status())
        assertTrue(midend.canRedo)

        // A second modified undo has nothing to undo.
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, Buttons.UI_UNDO or Buttons.MOD_SHFT, tile),
        )
        assertEquals(1, midend.statePos)

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.UI_REDO or Buttons.MOD_SHFT, tile),
        )
        assertEquals(2, midend.statePos, "redo moves the position once")
        assertEquals(states, midend.stateCount, "redo adds no history")
        assertEquals(1, midend.status())

        // CTRL on the same UI key is rejected by the C rules.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, Buttons.UI_REDO or Buttons.MOD_CTRL, tile),
        )
        assertEquals(2, midend.statePos, "a rejected redo changes nothing")
    }

    @Test
    fun `the modifier constants match puzzles h`() {
        assertEquals(0x1000, Buttons.MOD_CTRL)
        assertEquals(0x2000, Buttons.MOD_SHFT)
        assertEquals(0x4000, Buttons.MOD_NUM_KEYPAD)
        assertEquals(0x7000, Buttons.MOD_MASK)
        assertEquals(
            Buttons.LEFT_BUTTON,
            Buttons.stripModifiers(
                Buttons.LEFT_BUTTON or Buttons.MOD_CTRL or
                    Buttons.MOD_SHFT or Buttons.MOD_NUM_KEYPAD,
            ),
        )
        assertNotEquals(
            Buttons.LEFT_BUTTON,
            Buttons.LEFT_BUTTON or Buttons.MOD_CTRL,
        )
    }

    /** The first cell of a group of at least [minSize] tiles. */
    private fun firstGroup(state: SameGame.State, minSize: Int): Pair<Int, Int> {
        val w = state.params.width
        val h = state.params.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (state.col(x, y) == 0) continue
                if (componentSize(state, x, y) >= minSize) return x to y
            }
        }
        error("no group of $minSize tiles")
    }

    private fun componentSize(state: SameGame.State, sx: Int, sy: Int): Int {
        val w = state.params.width
        val h = state.params.height
        val colour = state.col(sx, sy)
        val seen = BooleanArray(state.n)
        val stack = ArrayList<Int>()
        stack.add(sy * w + sx)
        seen[sy * w + sx] = true
        var count = 0
        while (stack.isNotEmpty()) {
            val index = stack.removeAt(stack.size - 1)
            count++
            val x = index % w
            val y = index / w
            if (x > 0 && !seen[index - 1] && state.col(x - 1, y) == colour) {
                seen[index - 1] = true
                stack.add(index - 1)
            }
            if (x + 1 < w && !seen[index + 1] && state.col(x + 1, y) == colour) {
                seen[index + 1] = true
                stack.add(index + 1)
            }
            if (y > 0 && !seen[index - w] && state.col(x, y - 1) == colour) {
                seen[index - w] = true
                stack.add(index - w)
            }
            if (y + 1 < h && !seen[index + w] && state.col(x, y + 1) == colour) {
                seen[index + w] = true
                stack.add(index + w)
            }
        }
        return count
    }

    private companion object {
        const val SEED = "12345"
    }
}
