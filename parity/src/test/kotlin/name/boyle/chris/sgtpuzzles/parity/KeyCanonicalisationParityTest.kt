package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Games: fifteen and flip.
 *
 * The C `midend_process_key` canonicalises a key before the game sees
 * it: Enter and Return become `CURSOR_SELECT`, Space becomes
 * `CURSOR_SELECT2`, `CTRL+ASCII` becomes a control character,
 * `CTRL+SHFT+Z` becomes redo, and a drag or a release belongs to the
 * held button. Each case is compared with the same request through the
 * oracle, and the Kotlin side must show the same real move or redo.
 */
class KeyCanonicalisationParityTest {

    @Test
    fun `fifteen canonicalises enter, space, and control keys like the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame(FIFTEEN, PARAMS, SEED)
            val sequence = listOf(
                Buttons.CURSOR_LEFT,
                '\n'.code,
                ' '.code,
                Buttons.MOD_CTRL or 'J'.code,
                Buttons.MOD_CTRL or 'M'.code,
                Buttons.MOD_CTRL or 'A'.code,
                Buttons.MOD_CTRL or Buttons.MOD_SHFT or 'Z'.code,
                Buttons.CURSOR_LEFT,
            )

            val expected = oracle.keys(start.state, sequence)
            val actual = Midend.newGame(Fifteen, PARAMS, SEED)
            for (key in sequence) {
                actual.processKey(0, 0, key, Fifteen.preferredTileSize)
            }
            assertSame("key sequence", expected, actual)
            assertTrue("the cursor keys must add history", actual.canUndo)

            // Fifteen moves only on a cursor key, so every canonicalised
            // key must leave the same save as the cursor keys alone.
            val cursorOnly = oracle.keys(start.state, sequence.filter { it == Buttons.CURSOR_LEFT })
            assertEquals(
                "the enter, space, and control keys must not move",
                cursorOnly.state,
                actual.serialise().serialise(),
            )
        }

    @Test
    fun `ctrl shift z redo matches the oracle for fifteen`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame(FIFTEEN, PARAMS, SEED)
            val solved = oracle.solve(start.state)
            val undone = oracle.undo(solved.state)
            assertTrue("the oracle keeps a redo branch", undone.canRedo)
            assertFalse(undone.canUndo)

            val redo = Buttons.MOD_CTRL or Buttons.MOD_SHFT or 'Z'.code
            val expected = oracle.key(undone.state, 0, 0, redo)
            assertEquals("the oracle redo returns to the solved state", 1, expected.status)
            assertTrue(expected.canUndo)
            assertFalse(expected.canRedo)

            val actual = Midend.newGame(Fifteen, PARAMS, SEED)
            assertTrue(actual.solve())
            assertTrue(actual.undo())
            assertEquals(
                KeyOutcome.SomeEffect,
                actual.processKey(0, 0, redo, Fifteen.preferredTileSize),
            )
            assertEquals(1, actual.status())
            assertSame("ctrl shift z", expected, actual)
        }

    @Test
    fun `fifteen press, drag, and release match the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame(FIFTEEN, PARAMS, SEED)
            val actual = Midend.newGame(Fifteen, PARAMS, SEED)
            val geometry = actual.geometryFor(192, 192)
            val state = actual.state as Fifteen.State
            val gapX = state.gapPos % state.width
            val gapY = state.gapPos / state.width
            val (column, row) = listOf(
                gapX + 1 to gapY,
                gapX - 1 to gapY,
                gapX to gapY + 1,
                gapX to gapY - 1,
            ).first { (x, y) -> x in 0 until state.width && y in 0 until state.height }

            fun viewX(x: Int) = geometry.originX + x * geometry.tileSize + geometry.tileSize / 2
            fun viewY(y: Int) = geometry.originY + y * geometry.tileSize + geometry.tileSize / 2

            val points = listOf(
                OracleClient.Point(viewX(column), viewY(row), Buttons.LEFT_BUTTON),
                OracleClient.Point(viewX(gapX), viewY(gapY), Buttons.LEFT_DRAG),
                OracleClient.Point(viewX(gapX), viewY(gapY), Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(start.state, points, 192, 192)
            assertEquals("the press must move", 1, moves(expected.state.state).size)

            assertEquals(
                KeyOutcome.SomeEffect,
                actual.processKey(
                    geometry.toGameX(points[0].x),
                    geometry.toGameY(points[0].y),
                    points[0].button,
                    geometry.tileSize,
                ),
            )
            actual.processKey(
                geometry.toGameX(points[1].x),
                geometry.toGameY(points[1].y),
                points[1].button,
                geometry.tileSize,
            )
            actual.processKey(
                geometry.toGameX(points[2].x),
                geometry.toGameY(points[2].y),
                points[2].button,
                geometry.tileSize,
            )
            assertEquals(1, actual.serialise().values("MOVE").size)
            assertTrue(actual.canUndo)
            assertSame("press drag release", expected.state, actual)
        }

    @Test
    fun `flip canonicalises enter, space, and control keys like the oracle`() =
        OracleClient().use { oracle ->
            val save = flipSave(FLIP_DESC)

            // Enter alone is a select, and it is a real move.
            val single = oracle.keys(save, listOf('\n'.code))
            val actualSingle = Midend.fromSave(Flip, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                actualSingle.processKey(0, 0, '\n'.code, 48),
            )
            assertTrue(
                "enter must add a move",
                actualSingle.serialise().values("MOVE").isNotEmpty(),
            )
            assertSame("enter selects", single, actualSingle)

            val sequence = listOf(
                Buttons.CURSOR_UP,
                '\n'.code,
                ' '.code,
                '\r'.code,
                Buttons.MOD_CTRL or 'J'.code,
                Buttons.MOD_CTRL or 'M'.code,
                // A control letter that flip does not use.
                Buttons.MOD_CTRL or 'A'.code,
            )
            val expected = oracle.keys(save, sequence)
            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            for (key in sequence) actual.processKey(0, 0, key, 48)
            assertSame("select key sequence", expected, actual)
            assertTrue("the selects must add history", actual.canUndo)
        }

    @Test
    fun `flip press, drag, and release match the oracle`() =
        OracleClient().use { oracle ->
            val save = flipSave(FLIP_DESC)
            val geometry = Midend.fromSave(Flip, SgtpSave.parse(save)).geometryFor(192, 192)
            assertEquals(48, geometry.tileSize)

            // A drag and a release with no press are ignored. The first
            // press moves; the drag is not a press. The second press
            // overlaps the first, so C invents a release first.
            val points = listOf(
                OracleClient.Point(24, 24, Buttons.LEFT_DRAG),
                OracleClient.Point(24, 24, Buttons.LEFT_RELEASE),
                OracleClient.Point(24, 24, Buttons.LEFT_BUTTON),
                OracleClient.Point(72, 72, Buttons.LEFT_DRAG),
                OracleClient.Point(72, 72, Buttons.LEFT_BUTTON),
                OracleClient.Point(72, 72, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points, 192, 192)
            assertEquals("two presses must move", 2, moves(expected.state.state).size)

            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            for (point in points) {
                actual.processKey(
                    geometry.toGameX(point.x),
                    geometry.toGameY(point.y),
                    point.button,
                    geometry.tileSize,
                )
            }
            assertEquals(2, actual.serialise().values("MOVE").size)
            assertTrue(actual.canUndo)
            assertSame("flip press drag release", expected.state, actual)
        }

    private fun moves(save: String): List<String> = SgtpSave.parse(save).values("MOVE")

    private fun assertSame(label: String, expected: GameState, actual: Midend) {
        assertEquals("save $label", expected.state, actual.serialise().serialise())
        assertEquals("id $label", expected.id, actual.id())
        assertEquals("status $label", expected.status, actual.status())
        assertEquals("undo $label", expected.canUndo, actual.canUndo)
        assertEquals("redo $label", expected.canRedo, actual.canRedo)
    }

    private fun flipSave(desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Flip"),
            SaveRecord("PARAMS", "3x3c"),
            SaveRecord("CPARAMS", "3x3c"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "4x4"
        const val FIFTEEN = "fifteen"
        const val FLIP_DESC = "d074191345d1644c17058,c90"
    }
}
