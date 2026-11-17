package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.LatinSolver
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.check as latinCheck
import name.boyle.chris.sgtpuzzles.engine.generate
import name.boyle.chris.sgtpuzzles.engine.generateRect
import name.boyle.chris.sgtpuzzles.engine.latinSolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. Differential test for `latin.c`.
 *
 * The oracle runs `latin_generate`, `latin_generate_rect`, the generic
 * `latin_solver`, and `latin_check`. Generation compares the grid and
 * the encoded generator state, so an extra or missing `random_upto` in
 * the row shuffle or in the matching subroutine fails even when the
 * square happens to be valid.
 *
 * The solve matrix covers a complete square, a unique square, a grid
 * that needs each extra strategy, an ambiguous grid, an invalid grid,
 * and a grid that no completion exists for, at every `maxdiff` from 0
 * to 4. The returned grid is compared too, because C writes partial and
 * first-solution states back into the caller's array.
 */
class LatinParityTest {

    private val seeds = listOf("1", "2", "12345", "353", "123456", "a longer seed")

    @Test
    fun `latin generation matches upstream`() = OracleClient().use { oracle ->
        for (order in 1..9) {
            for (seed in seeds) {
                val where = "order $order seed $seed"
                val expected = oracle.latinGenerate(order, seed = seed)

                val random = RandomState.fromSeed(seed)
                val grid = generate(order, random)

                assertEquals("order $where", order, expected.order)
                assertEquals("grid $where", expected.grid, grid.toList())
                assertEquals("state $where", expected.state, random.encodeState())
            }
        }

        val shapes = listOf(
            1 to 1,
            1 to 6,
            6 to 1,
            1 to 9,
            9 to 1,
            4 to 6,
            6 to 4,
            2 to 3,
            3 to 2,
            8 to 5,
            5 to 8,
        )
        for ((w, h) in shapes) {
            for (seed in seeds) {
                val where = "${w}x$h seed $seed"
                val expected = oracle.latinGenerateRect(w, h, seed = seed)

                val random = RandomState.fromSeed(seed)
                val grid = generateRect(w, h, random)

                assertEquals("order $where", maxOf(w, h), expected.order)
                assertEquals("grid $where", expected.grid, grid.toList())
                assertEquals("state $where", expected.state, random.encodeState())
            }
        }
    }

    @Test
    fun `latin generation continues from an encoded state`() = OracleClient().use { oracle ->
        // The state check is not tied to a seed: a test can resume the
        // stream at any draw.
        val first = oracle.latinGenerate(5, seed = "12345")
        val second = oracle.latinGenerate(5, state = first.state)

        val random = RandomState.decode(first.state)
        assertEquals("grid", second.grid, generate(5, random).toList())
        assertEquals("state", second.state, random.encodeState())
    }

    @Test
    fun `latin solve matches upstream`() = OracleClient().use { oracle ->
        val three = oracle.latinGenerate(3, seed = "12345").grid
        val four = oracle.latinGenerate(4, seed = "11").grid
        val five = oracle.latinGenerate(5, seed = "12345").grid

        val cases = listOf(
            // An empty grid needs recursion, and yields a first solution.
            Case("3 empty", 3, List(9) { 0 }),
            // A complete grid is already solved.
            Case("3 complete", 3, three),
            // A unique completion from one blank cell.
            Case("3 one blank", 3, three.mapIndexed { i, v -> if (i == 4) 0 else v }),
            // A duplicate digit in a row: alloc rejects it.
            Case(
                "3 duplicate",
                3,
                three.toMutableList().also { it[1] = it[0] },
            ),
            Case("4 complete", 4, four),
            // Three rows of a 3x3 square force a fourth 4 in the last
            // column, so no completion exists. Simple elimination finds
            // the contradiction after one placement.
            Case(
                "4 impossible",
                4,
                listOf(
                    1, 2, 3, 0,
                    2, 3, 1, 0,
                    3, 1, 2, 0,
                    0, 0, 0, 0,
                ),
            ),
            Case("4 empty", 4, List(16) { 0 }),
            Case(
                "4 sparse",
                4,
                listOf(
                    1, 2, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                ),
            ),
            // Each of these needs one strategy above simple elimination.
            Case(
                "7 set",
                7,
                listOf(
                    0, 4, 5, 0, 3, 7, 0,
                    1, 0, 0, 0, 0, 0, 0,
                    5, 3, 2, 0, 0, 6, 0,
                    0, 0, 0, 6, 7, 3, 5,
                    0, 6, 0, 0, 0, 0, 0,
                    0, 0, 3, 4, 6, 0, 2,
                    0, 7, 0, 0, 5, 4, 0,
                ),
            ),
            Case(
                "7 recursion",
                7,
                listOf(
                    6, 0, 0, 2, 3, 7, 1,
                    0, 0, 0, 3, 4, 0, 0,
                    0, 3, 0, 7, 0, 6, 0,
                    4, 2, 0, 6, 0, 0, 0,
                    3, 6, 0, 0, 2, 0, 7,
                    0, 0, 3, 0, 0, 0, 0,
                    2, 7, 0, 1, 0, 4, 0,
                ),
            ),
            Case(
                "9 extreme set",
                9,
                listOf(
                    5, 0, 9, 2, 3, 0, 0, 0, 0,
                    4, 5, 0, 3, 0, 0, 0, 0, 0,
                    0, 7, 0, 1, 8, 4, 0, 0, 0,
                    6, 8, 1, 0, 4, 3, 0, 9, 5,
                    2, 0, 3, 0, 0, 0, 9, 1, 6,
                    0, 0, 7, 4, 5, 0, 6, 2, 0,
                    0, 0, 4, 0, 0, 6, 0, 0, 1,
                    1, 0, 0, 0, 0, 0, 7, 0, 0,
                    3, 1, 0, 0, 0, 7, 0, 0, 8,
                ),
            ),
            Case(
                "6 forcing",
                6,
                listOf(
                    0, 0, 3, 0, 0, 0,
                    0, 6, 4, 0, 2, 5,
                    2, 0, 1, 0, 0, 0,
                    0, 1, 0, 5, 0, 0,
                    0, 2, 6, 4, 1, 0,
                    0, 0, 0, 2, 4, 1,
                ),
            ),
        )

        for (case in cases) {
            for (maxDiff in 0..4) {
                val where = "${case.name} maxdiff $maxDiff"
                val expected = oracle.latinSolve(case.order, case.grid, maxDiff)

                val grid = case.grid.toIntArray()
                val diff = latinSolve(grid, case.order, maxDiff, 0, 1, 2, 3, 4)

                assertEquals("order $where", case.order, expected.order)
                assertEquals("diff $where", expected.diff, diff)
                assertEquals("grid $where", expected.grid, grid.toList())

                /*
                 * A returned solution is a complete Latin square and it
                 * keeps every clue. The C code writes the first solution
                 * of an ambiguous grid into the caller's array too.
                 */
                if (diff != LatinSolver.DIFF_IMPOSSIBLE && diff != LatinSolver.DIFF_UNFINISHED) {
                    assertTrue(
                        "solution is not a Latin square $where",
                        !latinCheck(grid, case.order),
                    )
                    for (i in grid.indices) {
                        if (case.grid[i] != 0) {
                            assertEquals("clue $i changed $where", case.grid[i], grid[i])
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `latin check matches upstream`() = OracleClient().use { oracle ->
        val grids = listOf(
            1 to listOf(1),
            1 to listOf(0),
            2 to listOf(1, 2, 2, 1),
            2 to listOf(1, 1, 2, 2),
            3 to listOf(3, 2, 1, 1, 3, 2, 2, 1, 3),
            // Counting alone accepts these, although neither is a Latin
            // square in the row and column sense. Upstream does the same.
            3 to listOf(1, 1, 1, 2, 2, 2, 3, 3, 3),
            3 to listOf(1, 2, 3, 1, 2, 3, 1, 2, 3),
            3 to listOf(1, 2, 3, 0, 0, 0, 0, 0, 0),
            4 to listOf(1, 3, 4, 2, 4, 2, 3, 1, 2, 4, 1, 3, 3, 1, 2, 4),
            4 to listOf(1, 3, 4, 2, 4, 2, 3, 1, 2, 4, 1, 3, 3, 1, 2, 1),
            4 to listOf(1, 2, 3, 4, 2, 3, 4, 1, 3, 4, 1, 2, 4, 1, 2, 3),
        )
        for ((order, grid) in grids) {
            assertEquals(
                "check order $order grid $grid",
                oracle.latinCheck(order, grid),
                !latinCheck(grid.toIntArray(), order),
            )
        }
    }

    @Test
    fun `latin oracle is deterministic and rejects invalid input`() = OracleClient().use { oracle ->
        val first = oracle.latinGenerate(4, seed = "12345")
        val second = oracle.latinGenerate(4, seed = "12345")
        assertEquals("generated grid", first.grid, second.grid)
        assertEquals("generated state", first.state, second.state)

        val rejected = listOf(
            mapOf("op" to "latin"),
            mapOf("op" to "latin", "kind" to "unknown"),
            mapOf("op" to "latin", "kind" to "generate", "o" to 0, "seed" to "x"),
            mapOf("op" to "latin", "kind" to "generate", "o" to 65, "seed" to "x"),
            mapOf("op" to "latin", "kind" to "generate", "o" to 5),
            mapOf("op" to "latin", "kind" to "generateRect", "w" to 0, "h" to 5, "seed" to "x"),
            mapOf("op" to "latin", "kind" to "generateRect", "w" to 5, "h" to 0, "seed" to "x"),
            mapOf("op" to "latin", "kind" to "solve", "o" to 3, "grid" to "1 2", "maxdiff" to 4),
            mapOf(
                "op" to "latin",
                "kind" to "solve",
                "o" to 3,
                "grid" to "1 2 3 4 0 0 0 0 0",
                "maxdiff" to 4,
            ),
            mapOf("op" to "latin", "kind" to "solve", "o" to 3, "grid" to "1 2 3 0 0 0 0 0 0"),
            mapOf(
                "op" to "latin",
                "kind" to "solve",
                "o" to 3,
                "grid" to "1 2 3 0 0 0 0 0 0",
                "maxdiff" to 5,
            ),
            mapOf("op" to "latin", "kind" to "check", "o" to 3, "grid" to "1 2 3"),
        )
        for (request in rejected) {
            assertThrows("accepted $request", OracleException::class.java) {
                oracle.request(request)
            }
        }
    }

    private data class Case(val name: String, val order: Int, val grid: List<Int>)
}
