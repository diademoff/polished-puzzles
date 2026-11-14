package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: fifteen.
 *
 * Drives the Kotlin game and the C oracle with the same inputs and
 * compares the serialised state after every step. The save file is the
 * normalised state: it carries the parameters, the description, the
 * move history, and the position in it.
 */
class FifteenGameTest {

    @Test
    fun `new games match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("", "4x4", "5x5", "6x6", "3x2", "2x5")) {
            val expected = oracle.newGame("fifteen", params, SEED)
            val actual = Midend.newGame(Fifteen, params, SEED)

            assertEquals("save '$params'", expected.state, actual.serialise().serialise())
            assertEquals("id '$params'", expected.id, actual.id())
            assertEquals("status '$params'", expected.status, actual.status())
            assertEquals("undo '$params'", expected.canUndo, actual.canUndo)
            assertEquals("redo '$params'", expected.canRedo, actual.canRedo)
            assertEquals("text '$params'", oracle.text(expected.state), actual.textFormat())
            assertEquals("width '$params'", expected.width, actual.size.width)
            assertEquals("height '$params'", expected.height, actual.size.height)
        }
    }

    @Test
    fun `presets and requested keys match the oracle`() = OracleClient().use { oracle ->
        val expectedPresets = oracle.presets("fifteen")
        val actualPresets = Fifteen.presets()
        assertEquals(expectedPresets.size, actualPresets.size)
        for (i in expectedPresets.indices) {
            assertEquals("preset $i title", expectedPresets[i].title, actualPresets[i].title)
            assertEquals("preset $i params", expectedPresets[i].params, actualPresets[i].params)
        }

        val state = oracle.newGame("fifteen", "4x4", SEED)
        val expectedKeys = oracle.requestKeys(state.state)
        val actualKeys = Fifteen.requestKeys(Fifteen.defaultParams())
        assertEquals("arrow mode", expectedKeys.arrowMode, actualKeys.arrowMode.ordinal)
        assertEquals("key labels", expectedKeys.labels.map { it.label }, actualKeys.labels.map { it.name })
    }

    @Test
    fun `cursor moves match the oracle`() = OracleClient().use { oracle ->
        var expected = oracle.newGame("fifteen", "4x4", SEED)
        var actual = Midend.newGame(Fifteen, "4x4", SEED)

        val keys = listOf(
            Buttons.CURSOR_LEFT, Buttons.CURSOR_LEFT, Buttons.CURSOR_UP,
            Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN, Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT,
            Buttons.CURSOR_DOWN, Buttons.CURSOR_DOWN, Buttons.CURSOR_RIGHT,
        )
        for ((index, key) in keys.withIndex()) {
            expected = oracle.key(expected.state, 0, 0, key)
            actual.processKey(0, 0, key, Fifteen.preferredTileSize)
            assertSame("cursor key $index", expected, actual)
        }
        assertTrue("moves must create history", actual.canUndo)
    }

    @Test
    fun `a key sequence matches the oracle`() = OracleClient().use { oracle ->
        val sequence = listOf(
            Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT,
            Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT,
            Buttons.CURSOR_UP, Buttons.CURSOR_SELECT,
            Buttons.CURSOR_LEFT, Buttons.CURSOR_RIGHT,
        )
        val start = oracle.newGame("fifteen", "4x4", SEED)
        val expected = oracle.keys(start.state, sequence)

        val actual = Midend.newGame(Fifteen, "4x4", SEED)
        for (key in sequence) actual.processKey(0, 0, key, Fifteen.preferredTileSize)
        assertSame("key sequence", expected, actual)
    }

    @Test
    fun `clicks and slides match the oracle`() = OracleClient().use { oracle ->
        var expected = oracle.newGame("fifteen", "4x4", SEED)
        var actual = Midend.newGame(Fifteen, "4x4", SEED)
        val width = expected.width
        val tile = 48
        val border = 24

        // Click every cell in turn. Cells in the same row or column as
        // the gap slide more than one tile; other cells do nothing.
        var checked = 0
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                val x = border + column * tile + tile / 2
                val y = border + row * tile + tile / 2
                val before = expected.state
                expected = oracle.key(expected.state, x, y, Buttons.LEFT_BUTTON)
                actual.processKey(x, y, Buttons.LEFT_BUTTON, Fifteen.preferredTileSize)
                assertSame("click ($column,$row)", expected, actual)
                if (expected.state != before) checked++
            }
        }
        assertTrue("no click changed the state", checked > 0)
        assertEquals(width, expected.width)
    }

    @Test
    fun `undo and redo keep the history`() = OracleClient().use { oracle ->
        var expected = oracle.newGame("fifteen", "4x4", SEED)
        var actual = Midend.newGame(Fifteen, "4x4", SEED)
        repeat(6) {
            expected = oracle.key(expected.state, 0, 0, Buttons.CURSOR_LEFT)
            actual.processKey(0, 0, Buttons.CURSOR_LEFT, Fifteen.preferredTileSize)
        }
        repeat(3) {
            expected = oracle.undo(expected.state)
            actual.undo()
            assertSame("undo $it", expected, actual)
            assertTrue(actual.canRedo)
        }
        repeat(2) {
            expected = oracle.redo(expected.state)
            actual.redo()
            assertSame("redo $it", expected, actual)
        }

        // A new move after an undo truncates the redo branch. Press
        // the hint key: it always returns a real move for an unsolved
        // state, unlike an arrow at the board edge.
        expected = oracle.key(expected.state, 0, 0, 'h'.code)
        actual.processKey(0, 0, 'h'.code, Fifteen.preferredTileSize)
        assertSame("move after undo", expected, actual)
        assertFalse(actual.canRedo)
    }

    @Test
    fun `the hint solver matches the oracle`() = OracleClient().use { oracle ->
        var expected = oracle.newGame("fifteen", "4x4", SEED)
        var actual = Midend.newGame(Fifteen, "4x4", SEED)

        // The greedy solver must finish within the upstream limit.
        val limit = 5 * 16 * 16 * 16
        var steps = 0
        while (expected.status == 0) {
            expected = oracle.key(expected.state, 0, 0, 'h'.code)
            actual.processKey(0, 0, 'h'.code, Fifteen.preferredTileSize)
            assertSame("hint $steps", expected, actual)
            steps++
            check(steps <= limit) { "the hint solver did not finish in $limit moves" }
        }
        assertEquals(1, expected.status)
        assertEquals("COMPLETED! Moves: $steps", actual.statusText())
    }

    @Test
    fun `solve matches the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.solve(oracle.newGame("fifteen", "4x4", SEED).state)
        val actual = Midend.newGame(Fifteen, "4x4", SEED)
        assertTrue(actual.solve())
        assertSame("solve", expected, actual)
        assertEquals(1, actual.status())
        assertEquals("Moves since auto-solve: 0", actual.statusText())
    }

    @Test
    fun `a completed move matches the oracle`() = OracleClient().use { oracle ->
        // Gap at index 14 and tile 15 at index 15: one click wins.
        val desc = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        val save = SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Fifteen.name),
                SaveRecord("PARAMS", "4x4"),
                SaveRecord("CPARAMS", "4x4"),
                SaveRecord("SEED", SEED),
                SaveRecord("DESC", desc),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        )

        val loaded = oracle.id(save.serialise())
        val actual = Midend.fromSave(Fifteen, save)
        assertEquals(loaded.state, actual.serialise().serialise())
        assertEquals(0, actual.status())

        val x = 24 + 3 * 48 + 24
        val y = 24 + 3 * 48 + 24
        val expected = oracle.key(loaded.state, x, y, Buttons.LEFT_BUTTON)
        actual.processKey(x, y, Buttons.LEFT_BUTTON, Fifteen.preferredTileSize)
        assertSame("winning move", expected, actual)
        assertEquals(1, actual.status())
        assertEquals("COMPLETED! Moves: 1", actual.statusText())
        assertEquals(oracle.text(expected.state), actual.textFormat())
    }

    private fun assertSame(label: String, expected: GameState, actual: Midend) {
        val save = actual.serialise().serialise()
        assertEquals("save $label", expected.state, save)
        assertEquals("id $label", expected.id, actual.id())
        assertEquals("status $label", expected.status, actual.status())
        assertEquals("undo $label", expected.canUndo, actual.canUndo)
        assertEquals("redo $label", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"
    }
}
