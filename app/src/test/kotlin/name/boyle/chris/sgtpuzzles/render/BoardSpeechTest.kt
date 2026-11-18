package name.boyle.chris.sgtpuzzles.render

import name.boyle.chris.sgtpuzzles.engine.SpokenBoard
import name.boyle.chris.sgtpuzzles.engine.SpokenUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: JVM. Pins the two pure halves of D-01 that the shell adds on
 * top of the engine seam: the unit cap fallback and the announcement
 * diff of section 6.
 *
 * The strings below are the engine grammar of
 * `work/board-a11y-spec.md` section 4, so a change to the rule shows up
 * here without a device.
 */
class BoardSpeechTest {

    private fun unit(index: Int, text: String = "Row 1, column ${index + 1}, empty") =
        SpokenUnit(
            row = 1,
            column = index + 1,
            left = index * 32,
            top = 0,
            width = 32,
            height = 32,
            text = text,
        )

    private fun grid(units: List<SpokenUnit>, rows: Int, columns: Int) =
        BoardSpeech(
            board = SpokenBoard(rows, columns, "", units),
            name = "Tents",
            status = "",
            canUndo = false,
            canRedo = false,
            canSolve = true,
        )

    private fun board(vararg texts: String) = grid(texts.mapIndexed { i, t -> unit(i, t) }, 8, 8)

    @Test
    fun `the cursor fallback keeps every unit at the cap and one above it`() {
        val atCap = grid(List(400) { unit(it) }, 20, 20)
        assertEquals(400, atCap.units().size)

        val above = grid(List(441) { unit(it) }, 21, 21)
        assertEquals(1, above.units().size)
        assertEquals("Row 1, column 1, empty", above.units()[0].text)
        assertEquals(above.units()[0], above.cursorUnit(16, 16))

        // A C engine has no board at all, so the shell exposes no unit.
        assertEquals(0, BoardSpeech(null, "Cube", "Solved", false, false, false).units().size)
        // A game without the seam also has no unit.
        assertEquals(0, grid(emptyList(), 0, 0).units().size)
    }

    @Test
    fun `an unchanged board says nothing`() {
        val before = board("Row 1, column 1, empty", "Row 1, column 2, tree")
        val after = board("Row 1, column 1, empty", "Row 1, column 2, tree")
        assertEquals(
            BoardUtterance.None,
            utteranceFor(before, before.units()[0], after, after.units()[0]),
        )
    }

    @Test
    fun `one changed unit is read on its own`() {
        val before = board("Row 1, column 1, empty", "Row 1, column 2, tree")
        val after = board("Row 1, column 1, tent", "Row 1, column 2, tree")
        assertEquals(
            BoardUtterance.Changed(listOf("Row 1, column 1, tent"), false),
            utteranceFor(before, null, after, null),
        )
    }

    @Test
    fun `two changed units are read in board order`() {
        val before = board("Row 1, column 1, empty", "Row 1, column 2, empty", "Row 1, column 3, tree")
        val after = board("Row 1, column 1, tent", "Row 1, column 2, empty", "Row 1, column 3, tent")
        assertEquals(
            BoardUtterance.Changed(
                listOf("Row 1, column 1, tent", "Row 1, column 3, tent"),
                false,
            ),
            utteranceFor(before, null, after, null),
        )
    }

    @Test
    fun `many changed units read the cursor and count the rest`() {
        val before = board("Row 1, column 1, empty", "Row 1, column 2, empty", "Row 1, column 3, empty")
        val after = board("Row 1, column 1, tent", "Row 1, column 2, wall", "Row 1, column 3, tent")
        assertEquals(
            BoardUtterance.Many("Row 1, column 2, wall", 2),
            utteranceFor(before, null, after, after.units()[1]),
        )
        // Without a cursor there is still one number to say.
        assertEquals(
            BoardUtterance.Many(null, 3),
            utteranceFor(before, null, after, null),
        )
    }

    @Test
    fun `a wrong unit gets the wrong prefix and a cursor move speaks the cursor`() {
        val before = board("Row 1, column 1, empty", "Row 1, column 2, empty")
        val after = board("Row 1, column 1, tent, wrong", "Row 1, column 2, empty")
        assertEquals(
            BoardUtterance.Changed(listOf("Row 1, column 1, tent, wrong"), true),
            utteranceFor(before, null, after, null),
        )

        // A cursor move changes no unit text, so the cursor alone speaks.
        val still = board("Row 1, column 1, empty", "Row 1, column 2, tree")
        assertEquals(
            BoardUtterance.Cursor("Row 1, column 2, tree"),
            utteranceFor(still, still.units()[0], still, still.units()[1]),
        )
    }

    @Test
    fun `a board of another shape is a new game and says nothing`() {
        val before = board("Row 1, column 1, empty")
        val after = grid(List(4) { unit(it) }, 2, 2)
        assertEquals(
            BoardUtterance.None,
            utteranceFor(before, null, after, null),
        )
    }
}
