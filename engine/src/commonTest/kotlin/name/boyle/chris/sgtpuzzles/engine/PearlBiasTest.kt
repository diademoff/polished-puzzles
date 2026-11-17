package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: pearl, the loopgen bias.
 *
 * The oracle's `loopgen` operation only knows the `none` and `count`
 * heuristics, so pearl's bias cannot be compared directly. This test
 * checks the incremental context against a from-scratch recomputation
 * for every call of a real generation. A mistake in the edge flags, the
 * vertex bits, the neighbour arrays, the to-do queues, or the score
 * update would show as a difference on some call.
 */
class PearlBiasTest {

    @Test
    fun `the incremental score equals the from scratch score on every call`() {
        val shapes = listOf(5 to 5, 6 to 6, 8 to 8, 10 to 10, 12 to 8)
        val seeds = listOf("12345", "1", "2", "seed", "another seed", "-7")
        for ((w, h) in shapes) {
            val grid = Grid.new(GridType.SQUARE, w - 1, h - 1, null)
            for (seed in seeds) {
                val board = IntArray(grid.faces.size) { FaceColour.GREY.ordinal }
                val bias = Pearl.PearlBias(grid)
                var calls = 0
                generateLoop(grid, board, RandomState.fromSeed(seed)) { current, face ->
                    val incremental = bias.score(current, face)
                    val expected = Pearl.pearlBiasFromScratch(grid, current)
                    assertEquals(
                        expected,
                        incremental,
                        "$w x $h seed '$seed' call $calls",
                    )
                    calls++
                    incremental
                }
                assertTrue(calls > 0, "$w x $h seed '$seed' made no bias calls")
            }
        }
    }

    @Test
    fun `a repeated call for the same board does not change the score`() {
        // One white face in a grey board is a clean loop, so the bias can
        // be called again without producing a four-way crossing. The
        // production bias is never called on the finished board, because
        // the tendril passes can create such a crossing.
        val grid = Grid.new(GridType.SQUARE, 4, 4, null)
        val board = IntArray(grid.faces.size) { FaceColour.GREY.ordinal }
        board[0] = FaceColour.WHITE.ordinal
        val bias = Pearl.PearlBias(grid)
        val expected = Pearl.pearlBiasFromScratch(grid, board)
        assertEquals(expected, bias.score(board, 1))
        assertEquals(expected, bias.score(board, 1), "a repeated call")
        assertEquals(expected, bias.score(board, 2), "another face")
    }

    @Test
    fun `the first call scans the whole grid`() {
        // A fresh context has every to-do queue filled, so the first call
        // must see the seed face that loopgen coloured before it.
        val grid = Grid.new(GridType.SQUARE, 4, 4, null)
        val board = IntArray(grid.faces.size) { FaceColour.GREY.ordinal }
        board[0] = FaceColour.WHITE.ordinal
        val bias = Pearl.PearlBias(grid)
        assertEquals(
            Pearl.pearlBiasFromScratch(grid, board),
            bias.score(board, 1),
        )
    }
}
