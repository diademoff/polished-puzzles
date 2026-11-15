package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine tests for the range port.
 *
 * Most of the game is compared with the oracle in the `:parity` module.
 * This suite pins the two C undefined-behavior spots (`range.c:465-471`
 * and `range.c:605-638`), which the oracle cannot be asked about because
 * its build keeps assertions, and the parts that have no seam method
 * yet: the current key label and the generator attempt count.
 */
class RangeTest {

    @Test
    fun `the solver reproduces the frozen 9x6 answer`() {
        val midend = Midend.newGame(Range, "9x6", "12345")
        assertEquals(
            "SW,1,0W,5,2W,5,3W,5,4W,5,5W,4,1W,3,1W,5,6W,4,8W,3,8W,2,8W,1,8W,0,2" +
                "W,0,3B,4,7B,5,0W,0,8W,3,7W,4,6W,4,0W,0,4B,1,1B,0,5W,1,7W,0,6W,3" +
                ",5W,4,4W,4,3W,4,2W,2,1W,1,5W,1,2W,2,0B,2,7W,1,4B,2,5W,2,6W,2,4B" +
                ",3,0B,2,3B,1,6W,3,2B,3,4W,3,3",
            Range.solve(midend.state),
        )
    }

    /**
     * `range.c:465-471` finds the DFS root with
     * `for (i = 0; i < n && grid[i] == BLACK; ++i);` and then writes
     * `dfs_parent[i]` and `dfs_depth[i]`. On an all-black board `i == n`
     * and both writes are outside the arrays. A click may paint every
     * cell black, and `h` then solves that board, so this is reachable.
     * The port definition is "no root, no moves".
     */
    @Test
    fun `an all black board adds no solver moves`() {
        val state = Range.newState(Range.decodeParams("3x3"), "i") as Range.State
        val allBlack = Range.State(
            state.shared,
            IntArray(9) { Range.BLACK },
            wasSolved = false,
            hasCheated = false,
        )

        assertTrue(RangeSolver.solve(allBlack.grid, 3, 3, RangeSolver.DIFF_RECURSION).isEmpty())

        val ui = Range.newUi(allBlack) as Range.Ui
        assertEquals(
            MoveResult.Unused,
            Range.interpretMove(allBlack, ui, 0, 0, 'h'.code, 32),
        )
    }

    /**
     * A clue that leaves the board undecided makes C's recursion append
     * moves from a branch that does not fill the board, repeat the outer
     * pass because the buffer grew, and write past its `n`-move buffer
     * (`range.c:322`, `605-638`). The oracle hangs on this board, so the
     * answer is a port definition: a rejected branch is rolled back, and
     * the pass loop stops when the player's state did not change.
     */
    @Test
    fun `the recursive branch stays bounded on a stalled board`() {
        val midend = Midend.fromGameId(Range, "4x4:1o")
        assertEquals(
            "SB,1,0B,0,1W,1,1W,0,2W,2,0",
            Range.solve(midend.state),
        )
    }

    @Test
    fun `current key labels`() {
        val state = Range.newState(Range.decodeParams("9x6"), DESC) as Range.State
        val ui = Range.newUi(state) as Range.Ui

        // A hidden cursor has no label.
        ui.r = 0
        ui.c = 2
        assertEquals("", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // An empty cell: fill or dot.
        ui.cursorShow = true
        assertEquals("Fill", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Dot", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A dot: empty or fill.
        ui.r = 3
        ui.c = 3
        state.grid[3 * state.width + 3] = Range.WHITE
        assertEquals("Empty", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Fill", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A black cell: dot or empty.
        state.grid[3 * state.width + 3] = Range.BLACK
        assertEquals("Dot", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Empty", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A clue has no label.
        ui.r = 0
        ui.c = 0
        assertEquals("", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // Another button is not a cursor select.
        assertEquals("", Range.currentKeyLabel(ui, state, 'h'.code))

        // `swap_buttons` is not read, so the labels do not change.
        ui.r = 0
        ui.c = 2
        ui.swapButtons = true
        assertEquals("Fill", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Dot", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // An out-of-grid cursor is a defined port guard.
        ui.r = 99
        assertEquals("", Range.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `was solved is sticky and a solve sets has cheated`() {
        val midend = Midend.newGame(Range, "9x6", "12345")
        assertTrue(midend.solve())
        val solved = midend.state as Range.State
        assertTrue(solved.wasSolved)
        assertTrue(solved.hasCheated)
        assertEquals(1, Range.status(solved))

        val dotted = Range.executeMove(solved, "W,0,2") as Range.State
        assertTrue(dotted.wasSolved, "a dot keeps the win")
        assertEquals(1, Range.status(dotted))
    }

    @Test
    fun `copy and equality follow grid and flags`() {
        val midend = Midend.newGame(Range, "9x6", "12345")
        val state = midend.state as Range.State
        val copy = state.copy() as Range.State
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())

        val moved = Range.executeMove(state, "B,0,2") as Range.State
        assertNotEquals(state, moved)
        assertFalse(state.grid.contentEquals(moved.grid))
        assertEquals(state.grid[2], 0)
        assertEquals(Range.BLACK, moved.grid[2])
    }

    @Test
    fun `the generator exposes its retry count`() {
        // The parity suite compares these descriptions with the oracle.
        val cases = listOf(
            Triple("3x3", 3, "3_5e5_5"),
            Triple("3x5", 2, "c3b5a7b7c"),
            Triple("4x4", 103, "e5_6_6_5_6_7e"),
        )
        for ((params, seed, desc) in cases) {
            val generated = Range.newDescWithAttempts(
                Range.decodeParams(params),
                RandomState.fromSeed(seed.toString()),
            )
            assertTrue(generated.attempts > 1, "$params seed=$seed retries")
            assertEquals(desc, generated.desc, "$params seed=$seed")
        }
    }

    @Test
    fun `solve returns null for a grid that does not match its params`() {
        val shared = Range.Shared(3, 3, IntArray(4), BooleanArray(4))
        val state = Range.State(shared, IntArray(4), wasSolved = false, hasCheated = false)
        assertNull(Range.solve(state))
    }

    @Test
    fun `the description decoder keeps the stored tail`() {
        val state = Range.newState(
            Range.decodeParams("9x6"),
            "$DESC,zzz",
        ) as Range.State
        assertEquals(7, state.grid[0])
        assertEquals(13, state.grid[5 * 9 + 8])

        // The stored description is the id, comma and tail included.
        val midend = Midend.fromGameId(Range, "9x6:$DESC,zzz")
        assertEquals("9x6:$DESC,zzz", midend.id())
        assertNull(midend.serialise().value("AUXINFO"))
    }

    private companion object {
        const val DESC = "7_5e4d5g8l7g9d11e8_13"
    }
}
