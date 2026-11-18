package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: slant.
 *
 * The state, the save text, the text format, clicks, the cursor, undo,
 * redo, and solve are compared with the oracle. The generator and its
 * descriptions are covered by SlantDescTest.
 */
class SlantGameTest {

    @Test
    fun `new games and text match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("5x5de", "5x5dh", "8x8de", "8x8dh", "12x10de")) {
            for (seed in listOf("1", "12345", "seed")) {
                val expected = oracle.newGame("slant", params, seed)
                val actual = Midend.newGame(Slant, params, seed)
                assertSame("slant '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text '$params' seed=$seed",
                    oracle.text(expected.state),
                    Slant.textFormat(actual.state),
                )
            }
        }
    }

    @Test
    fun `presets and requested keys match the oracle`() = OracleClient().use { oracle ->
        val expectedPresets = oracle.presets("slant")
        val actualPresets = Slant.presets()
        assertEquals(expectedPresets.size, actualPresets.size)
        for (i in expectedPresets.indices) {
            assertEquals("preset $i title", expectedPresets[i].title, actualPresets[i].title)
            assertEquals("preset $i params", expectedPresets[i].params, actualPresets[i].params)
        }

        val state = oracle.newGame("slant", "5x5de", SEED)
        val expectedKeys = oracle.requestKeys(state.state)
        val actualKeys = Slant.requestKeys(Slant.decodeParams("5x5de"))
        assertEquals(arrowMode(expectedKeys.arrowMode), actualKeys.arrowMode)
        assertEquals(expectedKeys.labels.map { it.label }, actualKeys.labels.map { it.name })
    }

    @Test
    fun `clicks match the oracle`() = OracleClient().use { oracle ->
        val expectedStart = oracle.newGame("slant", "5x5dh", SEED)
        val actual = Midend.newGame(Slant, "5x5dh", SEED)
        val tile = Slant.preferredTileSize
        var expected = expectedStart
        var changed = 0

        for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON)) {
            for (row in 0 until 5) {
                for (column in 0 until 5) {
                    val x = (column + 1) * tile + tile / 2
                    val y = (row + 1) * tile + tile / 2
                    val before = expected.state
                    expected = oracle.key(expected.state, x, y, button)
                    actual.processKey(x, y, button, tile)
                    assertSame("click $button ($column,$row)", expected, actual)
                    if (expected.state != before) changed++
                }
            }
        }

        // A point outside the board is unused.
        val outside = oracle.key(expected.state, 0, 0, Buttons.LEFT_BUTTON)
        actual.processKey(0, 0, Buttons.LEFT_BUTTON, tile)
        assertSame("click outside", outside, actual)

        assertTrue("no click changed the state", changed > 0)
    }

    @Test
    fun `cursor and direct keys match the oracle`() = OracleClient().use { oracle ->
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT2,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            '\\'.code,
            '/'.code,
            '\b'.code,
            Buttons.CURSOR_UP,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_SELECT2,
            Buttons.CURSOR_SELECT,
        )
        val start = oracle.newGame("slant", "5x5dh", SEED)
        val expected = oracle.keys(start.state, sequence)
        val actual = Midend.newGame(Slant, "5x5dh", SEED)
        for ((index, key) in sequence.withIndex()) {
            actual.processKey(0, 0, key, Slant.preferredTileSize)
        }
        assertSame("cursor sequence", expected, actual)
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val tile = Slant.preferredTileSize
        val x = tile + tile / 2
        val y = tile + tile / 2

        var expected = oracle.newGame("slant", "5x5dh", SEED)
        val actual = Midend.newGame(Slant, "5x5dh", SEED)

        expected = oracle.key(expected.state, x, y, Buttons.LEFT_BUTTON)
        actual.processKey(x, y, Buttons.LEFT_BUTTON, tile)
        expected = oracle.key(expected.state, x, y, Buttons.LEFT_BUTTON)
        actual.processKey(x, y, Buttons.LEFT_BUTTON, tile)
        assertTrue("a move must be undoable", actual.canUndo)

        expected = oracle.undo(expected.state)
        actual.undo()
        assertSame("undo", expected, actual)

        expected = oracle.redo(expected.state)
        actual.redo()
        assertSame("redo", expected, actual)

        expected = oracle.undo(oracle.undo(expected.state).state)
        actual.undo()
        actual.undo()
        assertSame("undo twice", expected, actual)
    }

    @Test
    fun `saves pass in both directions`() = OracleClient().use { oracle ->
        val tile = Slant.preferredTileSize
        val x = tile + tile / 2
        val y = tile + tile / 2

        for (params in listOf("5x5de", "5x5dh", "8x8de")) {
            val start = oracle.newGame("slant", params, SEED)

            val loaded = Midend.fromSave(Slant, SgtpSave.parse(start.state))
            assertEquals("load '$params'", start.state, loaded.serialise().serialise())
            assertEquals("load id '$params'", start.id, loaded.id())
            assertEquals("load desc '$params'", start.state, oracle.id(start.state).state)

            // Make two moves in C, then load that history in Kotlin.
            val moved = oracle.key(
                oracle.key(start.state, x, y, Buttons.LEFT_BUTTON).state,
                x,
                y,
                Buttons.RIGHT_BUTTON,
            )
            val movedKotlin = Midend.fromSave(Slant, SgtpSave.parse(moved.state))
            assertEquals("load aware '$params'", moved.state, movedKotlin.serialise().serialise())
            assertEquals("load aware id '$params'", moved.id, movedKotlin.id())

            // A Kotlin save must be readable by C.
            assertEquals(
                "C reads Kotlin '$params'",
                start.id,
                oracle.id(loaded.serialise().serialise()).id,
            )
        }
    }

    @Test
    fun `solve matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("2x2de", "5x5de", "5x5dh", "8x8de")) {
            val expected = oracle.solve(oracle.newGame("slant", params, SEED).state)
            val actual = Midend.newGame(Slant, params, SEED)
            assertTrue("solve '$params'", actual.solve())
            assertSame("solve '$params'", expected, actual)
            assertEquals("solved status '$params'", 1, actual.status())
        }
    }

    @Test
    fun `solve after a move diffs against the first state`() = OracleClient().use { oracle ->
        // `solve_game` reads `state`, the first entry of the history, and
        // never `currstate` (`slant.c:1596-1601`, `midend.c:2109-2111`).
        // The fresh answer lists every cell, so the answer after a move
        // is the same text: the cell the move already set stays in it.
        val tile = Slant.preferredTileSize
        val start = oracle.newGame("slant", "5x5dh", SEED)
        val freshAnswer = SgtpSave.parse(oracle.solve(start.state).state).value("SOLVE")!!
        val cell = Regex(""";([\\/])(\d+),(\d+)""").find(freshAnswer)!!
        val column = cell.groupValues[2].toInt()
        val row = cell.groupValues[3].toInt()
        // A left click sets `\` on a blank square, a right click sets `/`.
        val button = if (cell.groupValues[1] == "\\") Buttons.LEFT_BUTTON else Buttons.RIGHT_BUTTON
        val x = (column + 1) * tile + tile / 2
        val y = (row + 1) * tile + tile / 2

        val moved = oracle.key(start.state, x, y, button)
        val movedAnswer = SgtpSave.parse(oracle.solve(moved.state).state).value("SOLVE")!!
        assertEquals("the oracle keeps the cell in the answer", freshAnswer, movedAnswer)

        val actual = Midend.newGame(Slant, "5x5dh", SEED)
        actual.processKey(x, y, button, tile)
        assertSame("move before solve", moved, actual)
        assertTrue("solve after a move", actual.solve())
        assertEquals("solve save", oracle.solve(moved.state).state, actual.serialise().serialise())

        // A fresh game is unchanged: its first state is the current one.
        val fresh = Midend.newGame(Slant, "5x5dh", SEED)
        assertTrue("solve a fresh game", fresh.solve())
        assertEquals(
            "fresh save",
            oracle.solve(start.state).state,
            fresh.serialise().serialise(),
        )
    }

    private fun arrowMode(oracleMode: Int): ArrowMode = when (oracleMode) {
        1 -> ArrowMode.ARROWS_ONLY
        2 -> ArrowMode.ARROWS_LEFT
        3 -> ArrowMode.ARROWS_LEFT_RIGHT
        else -> ArrowMode.NONE
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"
    }
}
