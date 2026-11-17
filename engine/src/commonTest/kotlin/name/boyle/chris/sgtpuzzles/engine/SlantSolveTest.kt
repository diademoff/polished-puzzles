package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Level: semantic. The two `solve_game` errors of slant
 * (`slant.c:1578-1584`).
 *
 * A contradiction in the clues returns 0 and "This puzzle is not
 * self-consistent". A consistent board that the solver cannot finish
 * returns 2 and "Unable to find a unique solution for this puzzle".
 * The oracle comparison of the same states is in `SlantGameTest`.
 */
class SlantSolveTest {

    @Test
    fun `a contradictory clue is not self-consistent`() {
        val midend = Midend.fromGameId(Slant, "2x2:300000000")
        assertNull(Slant.solve(midend.state))
        assertEquals(
            "This puzzle is not self-consistent",
            Slant.solveGame(midend.state, null).error,
        )
    }

    @Test
    fun `an empty board has no unique solution`() {
        val midend = Midend.fromGameId(Slant, "2x2:i")
        assertNull(Slant.solve(midend.state))
        assertEquals(
            "Unable to find a unique solution for this puzzle",
            Slant.solveGame(midend.state, null).error,
        )
    }
}
