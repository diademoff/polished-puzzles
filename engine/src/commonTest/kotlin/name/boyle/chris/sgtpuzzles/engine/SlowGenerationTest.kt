package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The slow-generation hook. Level: semantic.
 *
 * Each game names the parameter sets whose generation is measurably long
 * or unbounded in C, and returns null below its own threshold. The hook
 * is a shell warning only: it validates nothing and draws no random
 * number, so these tests call it directly and never generate a board.
 */
class SlowGenerationTest {

    private fun warning(game: Game, params: String): String? =
        game.generationWarning(game.decodeParams(params))

    private fun assertBelowThenAbove(
        game: Game,
        below: String,
        above: String,
    ) {
        assertNull(warning(game, below), "$below must stay quiet")
        assertEquals(
            Game.SLOW_GENERATION_WARNING,
            warning(game, above),
            "$above must warn",
        )
    }

    @Test
    fun `magnets warns from the measured 16x14 board`() {
        assertBelowThenAbove(Magnets, "10x9dt", "16x14dt")
    }

    @Test
    fun `pattern warns from 50x50`() {
        assertBelowThenAbove(Pattern, "30x30", "50x50")
    }

    @Test
    fun `mosaic warns above the largest preset`() {
        assertBelowThenAbove(Mosaic, "50x50h1", "100x100h1")
    }

    @Test
    fun `signpost warns from 20x20`() {
        assertBelowThenAbove(Signpost, "7x7c", "20x20c")
    }

    @Test
    fun `untangle warns above 100 points`() {
        assertBelowThenAbove(Untangle, "25", "101")
    }

    @Test
    fun `galaxies warns above the largest preset`() {
        assertBelowThenAbove(Galaxies, "15x15dn", "16x16dn")
    }

    @Test
    fun `bridges warns above the largest preset`() {
        assertBelowThenAbove(Bridges, "15x15i30e10m2d1", "16x16i30e10m2d1")
    }

    @Test
    fun `unruly warns above the largest preset`() {
        assertBelowThenAbove(Unruly, "14x14dt", "16x16dt")
    }

    @Test
    fun `filling warns above the largest preset`() {
        assertBelowThenAbove(Filling, "17x13", "13x18")
    }

    @Test
    fun `mines warns above the largest preset`() {
        assertBelowThenAbove(Mines, "16x16n40", "17x16n40")
    }

    @Test
    fun `towers warns only on the 9x9 board`() {
        assertBelowThenAbove(Towers, "6xu", "9du")
    }

    /**
     * A game with no measured slow point keeps the default. `cube`
     * generation is one pass over the enumerated squares, so no honest
     * board is "slow" and the huge accepted area is a memory question,
     * not a warning.
     */
    @Test
    fun `cube and a game without a slow point keep the default`() {
        assertNull(warning(Cube, "c100x100"))
        assertNull(warning(Fifteen, "4x4"))
    }
}
