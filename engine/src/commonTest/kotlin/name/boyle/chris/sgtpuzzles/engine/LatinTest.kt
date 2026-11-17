package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic.
 *
 * Fixed vectors recorded from upstream `latin.c` through the oracle,
 * plus the invariants a caller depends on: a generated grid is a Latin
 * square, a solved grid keeps its clues, and every placement stays
 * consistent with the cube.
 *
 * The exhaustive differential comparison lives in `:parity`. These
 * vectors pin the exact grid, the exact difficulty result, and the
 * encoded generator state, so a change in the RNG order or in a
 * strategy fails here even when the oracle is not available.
 */
class LatinTest {

    /** The five generic levels are 0..4, and 4 is recursion. */
    private val recursiveLevel = 4

    private data class GenerationVector(
        val order: Int,
        val seed: String,
        val grid: List<Int>,
        val state: String,
    )

    private val generationVectors = listOf(
        GenerationVector(
            order = 1,
            seed = "12345",
            grid = listOf(1),
            state = "8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b8400",
        ),
        GenerationVector(
            order = 3,
            seed = "12345",
            grid = listOf(
                1, 2, 3,
                3, 1, 2,
                2, 3, 1,
            ),
            state = "8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b840f",
        ),
        GenerationVector(
            order = 5,
            seed = "12345",
            grid = listOf(
                1, 5, 2, 3, 4,
                3, 4, 1, 2, 5,
                2, 3, 5, 4, 1,
                4, 1, 3, 5, 2,
                5, 2, 4, 1, 3,
            ),
            state = "8fb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9a27aa12c4f3b550720b3d53d6df146b68ad52a1205",
        ),
    )

    @Test
    fun generateMatchesRecordedVectors() {
        for (vector in generationVectors) {
            val random = RandomState.fromSeed(vector.seed)
            assertEquals(
                vector.grid,
                generate(vector.order, random).toList(),
                "grid order ${vector.order}",
            )
            assertEquals(vector.state, random.encodeState(), "state order ${vector.order}")
        }
    }

    @Test
    fun generateRectMatchesRecordedVectors() {
        val random = RandomState.fromSeed("12345")
        val grid = generateRect(4, 6, random)

        assertEquals(
            listOf(
                1, 3, 4, 6,
                6, 2, 5, 4,
                5, 4, 6, 3,
                4, 6, 1, 2,
                2, 1, 3, 5,
                3, 5, 2, 1,
            ),
            grid.toList(),
        )
        assertEquals(
            "91b2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9ccb6dd3ed6001d5c9f51010d170ada4d2598bf9102",
            random.encodeState(),
        )

        // A one-column rectangle is the first column of the square.
        val column = RandomState.fromSeed("12345")
        assertEquals(
            listOf(1, 6, 5, 4, 2, 3),
            generateRect(1, 6, column).toList(),
        )
    }

    @Test
    fun generateProducesLatinSquares() {
        for (order in 1..9) {
            for (seed in listOf("1", "12345", "353")) {
                val grid = generate(order, RandomState.fromSeed(seed))
                val where = "order $order seed $seed"

                assertEquals(order * order, grid.size, "size $where")
                for (i in grid.indices) {
                    assertTrue(grid[i] in 1..order, "digit $where at $i is ${grid[i]}")
                }
                for (r in 0 until order) {
                    val row = (0 until order).map { grid[r * order + it] }
                    assertEquals((1..order).toList(), row.sorted(), "row $r $where")
                }
                for (c in 0 until order) {
                    val column = (0 until order).map { grid[it * order + c] }
                    assertEquals((1..order).toList(), column.sorted(), "column $c $where")
                }
                assertFalse(check(grid, order), "check $where")
            }
        }
    }

    @Test
    fun generateRectIsTheCornerOfTheSquare() {
        val shapes = listOf(1 to 1, 1 to 6, 6 to 1, 4 to 6, 6 to 4, 2 to 3, 3 to 2)
        for ((w, h) in shapes) {
            for (seed in listOf("12345", "another seed")) {
                val order = maxOf(w, h)
                val square = generate(order, RandomState.fromSeed(seed))
                val rect = generateRect(w, h, RandomState.fromSeed(seed))
                val where = "${w}x$h seed $seed"

                assertEquals(w * h, rect.size, "size $where")
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        assertEquals(
                            square[y * order + x],
                            rect[y * w + x],
                            "cell $x,$y $where",
                        )
                    }
                }
            }
        }
    }

    private data class SolveVector(
        val name: String,
        val order: Int,
        val maxDiff: Int,
        /** 0 marks a blank cell; a non-zero clue must survive the solve. */
        val input: List<Int>,
        val diff: Int,
        val output: List<Int>,
    )

    private val solveVectors = listOf(
        // No recursion: the grid stays unfinished and unchanged.
        SolveVector(
            name = "order 3 empty, no recursion",
            order = 3,
            maxDiff = 3,
            input = List(9) { 0 },
            diff = LatinSolver.DIFF_UNFINISHED,
            output = List(9) { 0 },
        ),
        // Recursion finds more than one solution and writes the first.
        SolveVector(
            name = "order 3 empty, recursion",
            order = 3,
            maxDiff = 4,
            input = List(9) { 0 },
            diff = LatinSolver.DIFF_AMBIGUOUS,
            output = listOf(
                1, 2, 3,
                2, 3, 1,
                3, 1, 2,
            ),
        ),
        // A complete grid is already solved at the lowest level.
        SolveVector(
            name = "order 3 complete",
            order = 3,
            maxDiff = 0,
            input = listOf(
                1, 2, 3,
                3, 1, 2,
                2, 3, 1,
            ),
            diff = 0,
            output = listOf(
                1, 2, 3,
                3, 1, 2,
                2, 3, 1,
            ),
        ),
        // Three rows of a 3x3 square cannot extend to a 4x4 one. The
        // part of the grid that was deduced stays in the caller's array.
        SolveVector(
            name = "order 4 impossible",
            order = 4,
            maxDiff = 4,
            input = listOf(
                1, 2, 3, 0,
                2, 3, 1, 0,
                3, 1, 2, 0,
                0, 0, 0, 0,
            ),
            diff = LatinSolver.DIFF_IMPOSSIBLE,
            output = listOf(
                1, 2, 3, 4,
                2, 3, 1, 0,
                3, 1, 2, 0,
                0, 0, 0, 0,
            ),
        ),
        // A grid that needs each strategy above simple elimination.
        SolveVector(
            name = "order 7 set elimination",
            order = 7,
            maxDiff = 4,
            input = listOf(
                0, 4, 5, 0, 3, 7, 0,
                1, 0, 0, 0, 0, 0, 0,
                5, 3, 2, 0, 0, 6, 0,
                0, 0, 0, 6, 7, 3, 5,
                0, 6, 0, 0, 0, 0, 0,
                0, 0, 3, 4, 6, 0, 2,
                0, 7, 0, 0, 5, 4, 0,
            ),
            diff = 1,
            output = listOf(
                6, 4, 5, 2, 3, 7, 1,
                1, 5, 7, 3, 4, 2, 6,
                5, 3, 2, 7, 1, 6, 4,
                4, 2, 1, 6, 7, 3, 5,
                3, 6, 4, 5, 2, 1, 7,
                7, 1, 3, 4, 6, 5, 2,
                2, 7, 6, 1, 5, 4, 3,
            ),
        ),
        SolveVector(
            name = "order 9 extreme set elimination",
            order = 9,
            maxDiff = 4,
            input = listOf(
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
            diff = 2,
            output = listOf(
                5, 6, 9, 2, 3, 8, 1, 4, 7,
                4, 5, 6, 3, 1, 2, 8, 7, 9,
                9, 7, 5, 1, 8, 4, 3, 6, 2,
                6, 8, 1, 7, 4, 3, 2, 9, 5,
                2, 4, 3, 8, 7, 5, 9, 1, 6,
                8, 9, 7, 4, 5, 1, 6, 2, 3,
                7, 3, 4, 9, 2, 6, 5, 8, 1,
                1, 2, 8, 5, 6, 9, 7, 3, 4,
                3, 1, 2, 6, 9, 7, 4, 5, 8,
            ),
        ),
        SolveVector(
            name = "order 6 forcing chains",
            order = 6,
            maxDiff = 4,
            input = listOf(
                0, 0, 3, 0, 0, 0,
                0, 6, 4, 0, 2, 5,
                2, 0, 1, 0, 0, 0,
                0, 1, 0, 5, 0, 0,
                0, 2, 6, 4, 1, 0,
                0, 0, 0, 2, 4, 1,
            ),
            diff = 3,
            output = listOf(
                1, 4, 3, 6, 5, 2,
                3, 6, 4, 1, 2, 5,
                2, 5, 1, 3, 6, 4,
                4, 1, 2, 5, 3, 6,
                5, 2, 6, 4, 1, 3,
                6, 3, 5, 2, 4, 1,
            ),
        ),
    )

    @Test
    fun solveMatchesRecordedVectors() {
        for (vector in solveVectors) {
            val grid = vector.input.toIntArray()
            val diff = latinSolve(grid, vector.order, vector.maxDiff, 0, 1, 2, 3, recursiveLevel)

            assertEquals(vector.diff, diff, "diff ${vector.name}")
            assertEquals(vector.output, grid.toList(), "grid ${vector.name}")
        }
    }

    @Test
    fun solveWritesBackToTheCallerGrid() {
        // A complete grid needs no change, but the call still returns
        // the same array content.
        val grid = solveVectors[2].input.toIntArray()
        assertEquals(0, latinSolve(grid, 3, 0, 0, 1, 2, 3, recursiveLevel))
        assertEquals(solveVectors[2].output, grid.toList())
    }

    @Test
    fun solverKeepsEveryPlacementConsistent() {
        for (vector in solveVectors) {
            checkPlacementsConsistent(
                vector.name,
                vector.order,
                vector.input.toIntArray(),
                vector.maxDiff,
            )
        }

        // Without recursion the order 7 set grid stays unfinished, and
        // it has placed digits to check.
        val unfinished = solveVectors[4]
        val grid = unfinished.input.toIntArray()
        assertEquals(
            LatinSolver.DIFF_UNFINISHED,
            latinSolveMain(
                LatinSolver.alloc(grid, unfinished.order)!!,
                0, 0, 1, 2, 3, recursiveLevel,
            ),
        )
        assertTrue(grid.any { it != 0 }, "the unfinished grid has a placement")
        checkPlacementsConsistent(
            unfinished.name + " unfinished",
            unfinished.order,
            grid,
            0,
        )
    }

    private fun checkPlacementsConsistent(
        name: String,
        order: Int,
        grid: IntArray,
        maxDiff: Int,
    ) {
        val solver = LatinSolver.alloc(grid, order)
            ?: error("$name: the recorded input is not allocatable")
        val diff = latinSolveMain(solver, maxDiff, 0, 1, 2, 3, recursiveLevel)

        /*
         * Recursion copies the sub-solver's grid into the parent, but
         * not the sub-solver's cube. So the cube invariant describes a
         * grid that this solver deduced, not a copied solution.
         */
        val copiedSolution = diff == recursiveLevel || diff == LatinSolver.DIFF_AMBIGUOUS

        if (!copiedSolution) {
            for (y in 0 until order) {
                for (x in 0 until order) {
                    val n = grid[y * order + x]
                    if (n == 0) continue

                    assertTrue(
                        solver.cubeAt(x, y, n),
                        "placed digit $name at ($x,$y) is not possible",
                    )
                    for (i in 1..order) {
                        if (i != n) {
                            assertFalse(
                                solver.cubeAt(x, y, i),
                                "placed digit $name left $i possible at ($x,$y)",
                            )
                        }
                    }
                    assertTrue(
                        solver.row[y * order + n - 1],
                        "row $name at $y does not know $n",
                    )
                    assertTrue(
                        solver.col[x * order + n - 1],
                        "column $name at $x does not know $n",
                    )
                }
            }
        }

        // A returned solution is a complete Latin square, so the
        // invariant above describes every cell.
        if (diff != LatinSolver.DIFF_IMPOSSIBLE && diff != LatinSolver.DIFF_UNFINISHED) {
            assertFalse(check(grid, order), "solution $name is not a Latin square")
            for (y in 0 until order) {
                val row = (0 until order).map { grid[y * order + it] }
                assertEquals((1..order).toList(), row.sorted(), "solution row $y $name")
            }
        }
    }

    @Test
    fun solverAllocRejectsAnInconsistentGrid() {
        val grid = intArrayOf(
            1, 2, 3,
            1, 0, 0,
            0, 0, 0,
        )
        val before = grid.copyOf()
        assertNull(LatinSolver.alloc(grid, 3))
        assertEquals(before.toList(), grid.toList(), "a rejected allocation leaves the grid alone")

        // A rejected grid is also an impossible solve.
        assertEquals(
            LatinSolver.DIFF_IMPOSSIBLE,
            latinSolve(grid, 3, 4, 0, 1, 2, 3, recursiveLevel),
        )

        // A solver writes through the array it was given.
        val valid = intArrayOf(0, 0, 0, 0)
        val solver = LatinSolver.alloc(valid, 2) ?: error("2x2 grid must allocate")
        assertTrue(valid === solver.grid)
    }

    /** The digits still possible at (x,y), in ascending order. */
    private fun candidates(solver: LatinSolver, x: Int, y: Int): List<Int> =
        (1..solver.order).filter { solver.cubeAt(x, y, it) }

    @Test
    fun allocateReportsAConsistentGridAsValid() {
        val grid = intArrayOf(
            1, 2, 0,
            0, 3, 0,
            0, 0, 2,
        )
        val solver = LatinSolver.allocate(grid, 3)

        assertTrue(solver.valid, "every digit of a consistent grid is placed")
        assertTrue(grid === solver.grid, "the solver writes through the caller grid")
        assertEquals(
            listOf(
                1, 2, 0,
                0, 3, 0,
                0, 0, 2,
            ),
            grid.toList(),
            "a placement stores the digit that is already in the cell",
        )

        for (y in 0 until 3) {
            for (x in 0 until 3) {
                val n = grid[y * 3 + x]
                if (n == 0) continue
                assertEquals(listOf(n), candidates(solver, x, y), "cell $x,$y")
                assertTrue(solver.row[y * 3 + n - 1], "row $y does not know $n")
                assertTrue(solver.col[x * 3 + n - 1], "column $x does not know $n")
            }
        }

        // A valid allocation is also what `alloc` hands out.
        val alsoValid = LatinSolver.alloc(grid.copyOf(), 3)
        assertTrue(alsoValid != null && alsoValid.valid, "a consistent grid also allocates")
    }

    @Test
    fun allocateKeepsThePartialCubeOfAnInconsistentGrid() {
        /*
         * `latin_solver_alloc` walks the cells with x outer and y inner
         * and returns false at the first digit it cannot place. The cube
         * that it built before that digit is still returned to the
         * caller, and `unequal` copies it into `hints`.
         *
         * This grid was run through upstream `latin.c` directly. The
         * placements are (0,0)=1, (0,1)=2 and (1,0)=2. The duplicate is
         * (1,1)=2, in the same row as (0,1)=2. The clue 4 at (3,3)
         * comes after the duplicate in the walk, so upstream never
         * places it.
         */
        val grid = intArrayOf(
            1, 2, 0, 0,
            2, 2, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 4,
        )
        val before = grid.copyOf()

        val solver = LatinSolver.allocate(grid, 4)
        assertFalse(solver.valid, "a duplicate digit makes the allocation invalid")
        assertNull(LatinSolver.alloc(grid, 4), "alloc hides the partial cube")

        assertEquals(before.toList(), grid.toList(), "the grid keeps its clues")

        // The exact cube, cell by cell in cube index order.
        val expected = listOf(
            listOf(1), listOf(2), listOf(3, 4), listOf(3, 4),
            listOf(2), listOf(1, 3, 4), listOf(1, 3, 4), listOf(1, 3, 4),
            listOf(3, 4), listOf(1, 3, 4), listOf(1, 2, 3, 4), listOf(1, 2, 3, 4),
            listOf(3, 4), listOf(1, 3, 4), listOf(1, 2, 3, 4), listOf(1, 2, 3, 4),
        )
        val actual = mutableListOf<List<Int>>()
        for (x in 0 until 4) {
            for (y in 0 until 4) {
                actual.add(candidates(solver, x, y))
            }
        }
        assertEquals(expected, actual, "the partial cube")

        // The same state in the row and column flags.
        val expectedRow = BooleanArray(16)
        val expectedCol = BooleanArray(16)
        // (0,0)=1: row 0 and column 0 know the digit 1.
        expectedRow[0 * 4 + 1 - 1] = true
        expectedCol[0 * 4 + 1 - 1] = true
        // (0,1)=2: row 1 knows 2, column 0 knows 2.
        expectedRow[1 * 4 + 2 - 1] = true
        expectedCol[0 * 4 + 2 - 1] = true
        // (1,0)=2: row 0 knows 2, column 1 knows 2.
        expectedRow[0 * 4 + 2 - 1] = true
        expectedCol[1 * 4 + 2 - 1] = true
        assertEquals(expectedRow.toList(), solver.row.toList(), "the row flags")
        assertEquals(expectedCol.toList(), solver.col.toList(), "the column flags")

        // The clue after the duplicate is still in the grid but was
        // never placed, so the cube still allows it there.
        assertEquals(4, grid[3 * 4 + 3])
        assertTrue(solver.cubeAt(3, 3, 4), "the digit after the duplicate must not be placed")
    }

    @Test
    fun contextIsCopiedAndFreedForEveryAttempt() {
        val copies = mutableListOf<Int>()
        val freed = mutableListOf<Int>()
        val seen = mutableListOf<Int>()
        var next = 0

        // The empty order 3 grid recurses, so the context is copied and
        // freed once per guess at every level.
        val diff = latinSolve(
            IntArray(9),
            3,
            4,
            0,
            1,
            2,
            3,
            recursiveLevel,
            valid = { _, ctx ->
                seen.add(ctx as Int)
                true
            },
            ctx = 0,
            ctxNew = {
                copies.add(++next)
                next
            },
            ctxFree = { freed.add(it as Int) },
        )

        assertEquals(LatinSolver.DIFF_AMBIGUOUS, diff)
        assertTrue(copies.isNotEmpty(), "recursion must copy the context")
        assertEquals(copies.sorted(), freed.sorted(), "every copy is freed once")
        assertTrue(seen.isNotEmpty(), "the validator sees the copied context")
    }

    @Test
    fun userSolversAndValidatorAreCalledByLevel() {
        val called = mutableListOf<Int>()
        val solvers = listOf<LatinUserSolver?>(
            { _, _ -> called.add(0); 0 },
            { _, _ -> called.add(1); 0 },
            null,
            { _, _ -> called.add(3); 0 },
            { _, _ -> called.add(4); 0 },
        )

        // maxDiff 1 permits levels 0 and 1 only.
        latinSolve(IntArray(4), 2, 1, 0, 1, 2, 3, recursiveLevel, userSolvers = solvers)
        assertTrue(called.isNotEmpty(), "the level 0 solver runs")
        assertTrue(called.all { it <= 1 }, "no solver above maxdiff runs: $called")
        assertTrue(0 in called && 1 in called, "both permitted levels run: $called")

        // A user solver can declare the grid impossible.
        assertEquals(
            LatinSolver.DIFF_IMPOSSIBLE,
            latinSolve(
                IntArray(9),
                3,
                0,
                0,
                1,
                2,
                3,
                recursiveLevel,
                userSolvers = listOf { _, _ -> -1 },
            ),
        )

        // The validator can reject a complete solution.
        val solved = intArrayOf(
            1, 2, 3,
            3, 1, 2,
            2, 3, 1,
        )
        assertEquals(
            LatinSolver.DIFF_IMPOSSIBLE,
            latinSolve(solved, 3, 0, 0, 1, 2, 3, recursiveLevel, valid = { _, _ -> false }),
        )
        assertEquals(
            0,
            latinSolve(
                solved.copyOf(),
                3,
                0,
                0,
                1,
                2,
                3,
                recursiveLevel,
                valid = { _, _ -> true },
            ),
        )
    }

    @Test
    fun checkFollowsUpstreamCounting() {
        // A real Latin square.
        assertFalse(
            check(
                intArrayOf(
                    3, 2, 1,
                    1, 3, 2,
                    2, 1, 3,
                ),
                3,
            ),
        )
        // Counting only: every digit occurs order times, so upstream
        // accepts a grid whose rows are identical.
        assertFalse(check(intArrayOf(1, 1, 1, 2, 2, 2, 3, 3, 3), 3))
        assertFalse(check(intArrayOf(1, 2, 3, 1, 2, 3, 1, 2, 3), 3))
        // A blank cell adds a zero digit, so the count is wrong.
        assertTrue(check(intArrayOf(1, 2, 3, 0, 0, 0, 0, 0, 0), 3))
        // The wrong number of occurrences.
        assertTrue(
            check(
                intArrayOf(
                    1, 3, 4, 2,
                    4, 2, 3, 1,
                    2, 4, 1, 3,
                    3, 1, 2, 1,
                ),
                4,
            ),
        )
    }
}
