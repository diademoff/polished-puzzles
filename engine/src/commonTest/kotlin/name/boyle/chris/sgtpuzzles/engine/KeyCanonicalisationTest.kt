package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. The canonicalisation that upstream
 * `midend_process_key` applies before a game sees a key.
 *
 * Every test uses a real game, so the assertion is about a move, a UI
 * change, or history, not only about the shape of an outcome value.
 * `RecordingGame` also shows the button that reaches `interpretMove`.
 */
class KeyCanonicalisationTest {

    /** A game that notes every button the midend passes to it. */
    private class RecordingGame(private val delegate: Game) : Game by delegate {
        val buttons = ArrayList<Int>()

        override fun interpretMove(
            state: Game.State,
            ui: Game.Ui,
            x: Int,
            y: Int,
            button: Int,
            tileSize: Int,
        ): MoveResult {
            buttons += button
            return delegate.interpretMove(state, ui, x, y, button, tileSize)
        }
    }

    @Test
    fun `enter, return, and their control codes select a flip row`() {
        val reference = Midend.fromGameId(Flip, FLIP_ID)
        assertEquals(
            KeyOutcome.SomeEffect,
            reference.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
        )

        val keys = listOf(
            '\n'.code,
            '\r'.code,
            Buttons.MOD_CTRL or 'J'.code,
            Buttons.MOD_CTRL or 'M'.code,
        )
        for (key in keys) {
            val midend = Midend.fromGameId(Flip, FLIP_ID)
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, TILE),
                "outcome for $key",
            )
            assertEquals(
                reference.serialise().serialise(),
                midend.serialise().serialise(),
                "save for $key",
            )
            assertEquals(listOf("M0,0"), midend.serialise().values("MOVE"), "move for $key")
            assertEquals(1, (midend.state as Flip.State).moves, "move count for $key")
            assertTrue(midend.canUndo, "history for $key")
        }
    }

    @Test
    fun `space is the second select button`() {
        fun ready(): Midend {
            val midend = Midend.newGame(Slant, "4x4", SEED)
            // The first select only makes the cursor visible.
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_SELECT, Slant.preferredTileSize),
            )
            assertEquals(emptyList<String>(), midend.serialise().values("MOVE"))
            return midend
        }

        val enter = ready()
        assertEquals(
            KeyOutcome.SomeEffect,
            enter.processKey(0, 0, '\n'.code, Slant.preferredTileSize),
        )
        assertEquals(listOf("\\0,0"), enter.serialise().values("MOVE"))

        val space = ready()
        assertEquals(
            KeyOutcome.SomeEffect,
            space.processKey(0, 0, ' '.code, Slant.preferredTileSize),
        )
        assertEquals(
            listOf("/0,0"),
            space.serialise().values("MOVE"),
            "space is CURSOR_SELECT2, which turns the other way",
        )
        assertNotEquals(enter.serialise().serialise(), space.serialise().serialise())
    }

    @Test
    fun `delete and ctrl+h clear a slant cell`() {
        fun prepared(): Midend {
            val midend = Midend.newGame(Slant, "4x4", SEED)
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, Slant.preferredTileSize)
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, Slant.preferredTileSize)
            assertEquals(listOf("\\0,0"), midend.serialise().values("MOVE"))
            return midend
        }

        val delete = prepared()
        val before = delete.textFormat()
        assertEquals(
            KeyOutcome.SomeEffect,
            delete.processKey(0, 0, 0x7F, Slant.preferredTileSize),
        )
        assertEquals(
            listOf("\\0,0", "C0,0"),
            delete.serialise().values("MOVE"),
        )
        assertNotEquals(before, delete.textFormat(), "the cell must be cleared")

        // Both backspace characters (8 and 127) are the same key, and
        // CTRL+H canonicalises to 8 before the game sees it.
        val backspace = prepared()
        assertEquals(
            KeyOutcome.SomeEffect,
            backspace.processKey(0, 0, '\b'.code, Slant.preferredTileSize),
        )
        val controlH = prepared()
        assertEquals(
            KeyOutcome.SomeEffect,
            controlH.processKey(
                0,
                0,
                Buttons.MOD_CTRL or 'H'.code,
                Slant.preferredTileSize,
            ),
        )
        assertEquals(
            backspace.serialise().serialise(),
            delete.serialise().serialise(),
            "127 and 8 are the same key",
        )
        assertEquals(
            backspace.serialise().serialise(),
            controlH.serialise().serialise(),
            "CTRL+H is 8",
        )
    }

    @Test
    fun `ctrl shift z is redo`() {
        val recorded = RecordingGame(Fifteen)
        val midend = Midend.newGame(recorded, "4x4", SEED)
        assertTrue(midend.solve())
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertTrue(midend.canRedo)

        val upper = Buttons.MOD_CTRL or Buttons.MOD_SHFT or 'Z'.code
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, upper, Fifteen.preferredTileSize),
        )
        assertEquals(1, midend.status(), "redo returns to the solved state")
        assertFalse(midend.canRedo)
        assertTrue(recorded.buttons.isEmpty(), "redo is not a game key")

        val lower = Midend.newGame(Fifteen, "4x4", SEED)
        assertTrue(lower.solve())
        assertTrue(lower.undo())
        assertEquals(
            KeyOutcome.SomeEffect,
            lower.processKey(
                0,
                0,
                Buttons.MOD_CTRL or Buttons.MOD_SHFT or 'z'.code,
                Fifteen.preferredTileSize,
            ),
        )
        assertEquals(1, lower.status())
        assertEquals(
            midend.serialise().serialise(),
            lower.serialise().serialise(),
            "the case of Z does not matter",
        )

        // CTRL+Z is not redo. It canonicalises to the control code 0x1A,
        // which fifteen does not use, so the redo branch stays.
        val ctrlZ = Midend.newGame(Fifteen, "4x4", SEED)
        assertTrue(ctrlZ.solve())
        assertTrue(ctrlZ.undo())
        assertEquals(
            KeyOutcome.Unused,
            ctrlZ.processKey(
                0,
                0,
                Buttons.MOD_CTRL or 'Z'.code,
                Fifteen.preferredTileSize,
            ),
        )
        assertEquals(0, ctrlZ.status())
        assertTrue(ctrlZ.canRedo)
    }

    @Test
    fun `ctrl on a click is rejected`() {
        val recorded = RecordingGame(Flip)
        val midend = Midend.fromGameId(recorded, FLIP_ID)
        val before = midend.serialise().serialise()

        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(
                24,
                24,
                Buttons.LEFT_BUTTON or Buttons.MOD_CTRL,
                TILE,
            ),
        )
        assertTrue(recorded.buttons.isEmpty(), "the game must not see the key")
        assertEquals(before, midend.serialise().serialise())
        assertFalse(midend.canUndo)

        // The same click without CTRL is a real move.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(24, 24, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(listOf("M0,0"), midend.serialise().values("MOVE"))
        assertTrue(midend.canUndo)
    }

    @Test
    fun `a keypad flag stays on a digit only`() {
        // Sixteen reads MOD_NUM_KEYPAD on a digit, so the flag must
        // survive and enable the keypad path.
        val recorded = RecordingGame(Sixteen)
        val sixteen = Midend.fromGameId(recorded, SIXTEEN_ID)
        assertEquals(
            KeyOutcome.SomeEffect,
            sixteen.processKey(
                0,
                0,
                Buttons.MOD_NUM_KEYPAD or '8'.code,
                TILE,
            ),
        )
        assertEquals(
            listOf(Buttons.MOD_NUM_KEYPAD or '8'.code),
            recorded.buttons,
        )
        assertEquals(1, sixteen.stateCount, "the keypad digit is a UI update")

        // On a cursor key the flag is dropped before the game.
        val cursorRecorded = RecordingGame(Fifteen)
        val fifteen = Midend.fromGameId(cursorRecorded, FIFTEEN_ID)
        assertEquals(
            KeyOutcome.SomeEffect,
            fifteen.processKey(
                0,
                0,
                Buttons.MOD_NUM_KEYPAD or Buttons.CURSOR_LEFT,
                TILE,
            ),
        )
        assertEquals(listOf(Buttons.CURSOR_LEFT), cursorRecorded.buttons)
        assertEquals(1, fifteen.serialise().values("MOVE").size)
        assertTrue(fifteen.canUndo)
    }

    @Test
    fun `mouse button ranges match the C macros`() {
        assertTrue(Buttons.isMouseDown(Buttons.LEFT_BUTTON))
        assertTrue(Buttons.isMouseDown(Buttons.MIDDLE_BUTTON))
        assertTrue(Buttons.isMouseDown(Buttons.RIGHT_BUTTON))
        assertFalse(Buttons.isMouseDown(Buttons.LEFT_DRAG))
        assertFalse(Buttons.isMouseDown(Buttons.LEFT_RELEASE))
        assertFalse(Buttons.isMouseDown(Buttons.CURSOR_UP))

        assertTrue(Buttons.isMouseDrag(Buttons.LEFT_DRAG))
        assertTrue(Buttons.isMouseDrag(Buttons.RIGHT_DRAG))
        assertFalse(Buttons.isMouseDrag(Buttons.LEFT_BUTTON))
        assertFalse(Buttons.isMouseDrag(Buttons.LEFT_RELEASE))

        assertTrue(Buttons.isMouseRelease(Buttons.LEFT_RELEASE))
        assertTrue(Buttons.isMouseRelease(Buttons.RIGHT_RELEASE))
        assertFalse(Buttons.isMouseRelease(Buttons.LEFT_DRAG))
        assertFalse(Buttons.isMouseRelease(Buttons.LEFT_BUTTON))

        // A modified button is not a press, as the unsigned C macro.
        assertFalse(Buttons.isMouseDown(Buttons.LEFT_BUTTON or Buttons.MOD_CTRL))
    }

    @Test
    fun `tab keeps ctrl and shift`() {
        val recorded = RecordingGame(Flip)
        val midend = Midend.fromGameId(recorded, FLIP_ID)
        val tab = Buttons.MOD_CTRL or Buttons.MOD_SHFT or '\t'.code
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, tab, TILE))
        assertEquals(listOf(tab), recorded.buttons, "TAB is the one non-cursor key")
    }

    @Test
    fun `a drag and a release belong to the held button`() {
        val recorded = RecordingGame(Fifteen)
        val midend = Midend.fromGameId(recorded, FIFTEEN_ID)

        // A press on a tile next to the gap is a real move.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 168, Buttons.LEFT_BUTTON, TILE))
        assertEquals(1, midend.serialise().values("MOVE").size)

        // The drag and the release use the held button, not their own.
        midend.processKey(120, 168, Buttons.LEFT_DRAG, TILE)
        midend.processKey(120, 168, Buttons.LEFT_RELEASE, TILE)
        assertEquals(
            listOf(Buttons.LEFT_BUTTON, Buttons.LEFT_DRAG, Buttons.LEFT_RELEASE),
            recorded.buttons,
        )

        // Fifteen ignores drag and release, so the history is unchanged.
        assertEquals(1, midend.serialise().values("MOVE").size)
        assertFalse(midend.canRedo)

        // A right press followed by a left drag and release is a right
        // drag and a right release.
        val right = RecordingGame(Fifteen)
        val other = Midend.fromGameId(right, FIFTEEN_ID)
        other.processKey(0, 0, Buttons.RIGHT_BUTTON, TILE)
        other.processKey(0, 0, Buttons.LEFT_DRAG, TILE)
        other.processKey(0, 0, Buttons.LEFT_RELEASE, TILE)
        assertEquals(
            listOf(Buttons.RIGHT_BUTTON, Buttons.RIGHT_DRAG, Buttons.RIGHT_RELEASE),
            right.buttons,
        )
    }

    @Test
    fun `a drag or a release with no press is ignored`() {
        val recorded = RecordingGame(Fifteen)
        val midend = Midend.fromGameId(recorded, FIFTEEN_ID)
        val before = midend.serialise().serialise()

        for (key in listOf(
            Buttons.LEFT_DRAG,
            Buttons.MIDDLE_DRAG,
            Buttons.RIGHT_DRAG,
            Buttons.LEFT_RELEASE,
            Buttons.MIDDLE_RELEASE,
            Buttons.RIGHT_RELEASE,
        )) {
            assertEquals(KeyOutcome.Unused, midend.processKey(24, 24, key, TILE), "key $key")
        }
        assertTrue(recorded.buttons.isEmpty(), "the game must not see the event")
        assertEquals(before, midend.serialise().serialise())

        // A press still works after the ignored events.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 168, Buttons.LEFT_BUTTON, TILE))
        assertTrue(midend.canUndo)
    }

    @Test
    fun `an overlapping press releases the previous button first`() {
        val recorded = RecordingGame(Fifteen)
        val midend = Midend.fromGameId(recorded, FIFTEEN_ID)

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 168, Buttons.LEFT_BUTTON, TILE))
        val history = midend.stateCount
        val moves = midend.serialise().values("MOVE")

        // The right press invents a left release before it is processed.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(120, 168, Buttons.RIGHT_BUTTON, TILE),
        )
        assertEquals(
            listOf(Buttons.LEFT_BUTTON, Buttons.LEFT_RELEASE, Buttons.RIGHT_BUTTON),
            recorded.buttons,
        )
        assertEquals(history, midend.stateCount, "the release adds no state")
        assertEquals(moves, midend.serialise().values("MOVE"))

        // The new button is the held one, so a left drag is a right drag.
        midend.processKey(120, 168, Buttons.LEFT_DRAG, TILE)
        assertEquals(Buttons.RIGHT_DRAG, recorded.buttons.last())
        midend.processKey(120, 168, Buttons.LEFT_RELEASE, TILE)
        assertEquals(Buttons.RIGHT_RELEASE, recorded.buttons.last())
    }

    private companion object {
        const val TILE = 48
        const val SEED = "12345"
        const val FLIP_ID = "3x3c:d074191345d1644c17058,c90"
        const val FIFTEEN_ID = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        const val SIXTEEN_ID = "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
    }
}
