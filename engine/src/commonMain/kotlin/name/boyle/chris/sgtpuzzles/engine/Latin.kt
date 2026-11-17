package name.boyle.chris.sgtpuzzles.engine

/** `usersolver_t`: a game-specific strategy at one difficulty level. */
typealias LatinUserSolver = (LatinSolver, Any?) -> Int

/** `validator_t`: the final check of a completed grid. */
typealias LatinValidator = (LatinSolver, Any?) -> Boolean

/** `ctxnew_t`: copies the per-solve context for one recursive attempt. */
typealias LatinCtxNew = (Any?) -> Any?

/** `ctxfree_t`: frees a context that [LatinCtxNew] made. */
typealias LatinCtxFree = (Any?) -> Unit

/**
 * A port of upstream `latin.c`: the shared Latin square solver, its
 * generator, and its checker.
 *
 * The solver is a framework, not a game: a consumer supplies one
 * strategy per difficulty level, a final validator, and an optional
 * context that is copied for every recursive attempt. Games that build
 * on it (keen, towers, unequal, singles) keep their own difficulty
 * enumerations, so this file only ports the generic machinery.
 *
 * The cube is the C layout, `cube[(x * o + y) * o + n - 1]`, with x and
 * y in the same order as upstream. The port does not transpose anything:
 * a consumer's clue code sees the same coordinates as in C.
 *
 * The solver mutates [LatinSolver.grid] in place, exactly as C writes
 * back through the `digit *grid` pointer, so a caller can read the
 * result from its own array. [generate] consumes the generator in the
 * same order as C: one `shuffle` of the row order, then the matching
 * subroutine, whose own permutation draws are part of the contract.
 */
class LatinSolver private constructor(
    /** Order of the Latin square. */
    val order: Int,
    /** `o^2` digits, `grid[y*order + x]`, written back during solving. */
    val grid: IntArray,
) {

    companion object {
        /** A digit is an `unsigned char` upstream. */
        const val MAX_ORDER = 255

        /** `diff_impossible`: no solution. */
        const val DIFF_IMPOSSIBLE = 10

        /** `diff_ambiguous`: more than one solution. */
        const val DIFF_AMBIGUOUS = 11

        /** `diff_unfinished`: a solution is not complete and recursion is forbidden. */
        const val DIFF_UNFINISHED = 12

        /**
         * `latin_solver_alloc`: makes a solver over [grid] and places
         * every non-zero digit that is already there.
         *
         * Returns null when the given digits cannot be placed, which is
         * what C reports with a false return value. On that path [grid]
         * is unchanged, because a placement writes the same digit back
         * to its own cell.
         */
        fun alloc(grid: IntArray, order: Int): LatinSolver? {
            require(order in 1..MAX_ORDER) { "latin order must be 1..255, not $order" }
            require(grid.size >= order * order) {
                "latin grid needs ${order * order} entries, not ${grid.size}"
            }
            val solver = LatinSolver(order, grid)
            for (x in 0 until order) {
                for (y in 0 until order) {
                    val n = grid[y * order + x]
                    if (n != 0) {
                        require(n in 1..order) { "latin grid value $n is outside 1..$order" }
                        if (!solver.cubeAt(x, y, n)) return null
                        solver.place(x, y, n)
                    }
                }
            }
            return solver
        }
    }

    /** `cube`: `order^3` possibilities, indexed by [cubePos]. */
    val cube = BooleanArray(order * order * order) { true }

    /** `row`: `row[y*order + n - 1]` is true when n is already in row y. */
    val row = BooleanArray(order * order)

    /** `col`: `col[x*order + n - 1]` is true when n is already in column x. */
    val col = BooleanArray(order * order)

    /** `cubepos(x,y,n)`: the index of the possibility of n at (x,y). */
    fun cubePos(x: Int, y: Int, n: Int): Int = (x * order + y) * order + n - 1

    /** `cube(x,y,n)`: n is still possible at (x,y). */
    fun cubeAt(x: Int, y: Int, n: Int): Boolean = cube[cubePos(x, y, n)]

    /** `cube(x,y,n) = value`. */
    fun setCube(x: Int, y: Int, n: Int, value: Boolean) {
        cube[cubePos(x, y, n)] = value
    }

    /** `latin_solver_new_scratch`: buffers for set and forcing elimination. */
    fun newScratch(): LatinScratch = LatinScratch(order)

    /**
     * `latin_solver_place`: records n at (x,y) and rules it out from the
     * rest of the row, the column, and the square.
     */
    fun place(x: Int, y: Int, n: Int) {
        val o = order
        require(n in 1..o) { "latin digit $n is outside 1..$o" }
        require(cubeAt(x, y, n)) { "latin digit $n is not possible at ($x,$y)" }

        for (i in 1..o) {
            if (i != n) setCube(x, y, i, false)
        }
        for (i in 0 until o) {
            if (i != y) setCube(x, i, n, false)
        }
        for (i in 0 until o) {
            if (i != x) setCube(i, y, n, false)
        }

        grid[y * o + x] = n
        row[y * o + n - 1] = true
        col[x * o + n - 1] = true
    }

    /**
     * `latin_solver_elim`: positional elimination. Walks `order`
     * entries from [start] with [step] and places the digit when exactly
     * one possibility is left. Returns +1 on a placement, -1 when the
     * section is empty, and 0 otherwise.
     */
    fun elim(start: Int, step: Int): Int {
        val o = order
        var m = 0
        var fpos = -1
        for (i in 0 until o) {
            val pos = start + i * step
            if (cube[pos]) {
                fpos = pos
                m++
            }
        }

        if (m == 1) {
            check(fpos >= 0) { "latin elimination lost its position" }
            val n = 1 + fpos % o
            val rowPos = fpos / o
            val x = rowPos / o
            val y = rowPos % o
            if (grid[y * o + x] == 0) {
                place(x, y, n)
                return 1
            }
        } else if (m == 0) {
            return -1
        }
        return 0
    }

    /**
     * `latin_solver_set`: set elimination over the `order` by `order`
     * matrix at [start] with strides [step1] and [step2].
     *
     * The matrix is winnowed by the definite placements, then searched
     * for a rectangle of zeroes whose height and width add up to the
     * reduced size. Returns +1 when a possibility is removed, -1 on a
     * contradiction, and 0 when nothing changes.
     */
    fun setElim(scratch: LatinScratch, start: Int, step1: Int, step2: Int): Int {
        val o = order
        val grid = scratch.grid
        val rowIdx = scratch.rowIdx
        val colIdx = scratch.colIdx
        val set = scratch.set

        /*
         * Find the definite placements and discard their rows and the
         * columns of their single ones.
         */
        for (i in 0 until o) {
            rowIdx[i] = 1
            colIdx[i] = 1
        }
        for (i in 0 until o) {
            var count = 0
            var first = -1
            for (j in 0 until o) {
                if (cube[start + i * step1 + j * step2]) {
                    first = j
                    count++
                }
            }
            if (count == 0) return -1
            if (count == 1) {
                rowIdx[i] = 0
                colIdx[first] = 0
            }
        }

        /*
         * Turn the flags into lists of the surviving indices.
         */
        var i = 0
        var j = 0
        while (i < o) {
            if (rowIdx[i] != 0) rowIdx[j++] = i
            i++
        }
        val n = j
        i = 0
        j = 0
        while (i < o) {
            if (colIdx[i] != 0) colIdx[j++] = i
            i++
        }
        check(n == j) { "latin set elimination lost a column" }

        /*
         * Build the smaller matrix. Rows are indexed by the surviving
         * row index, columns by the surviving column index.
         */
        for (i in 0 until n) {
            for (j in 0 until n) {
                grid[i * o + j] =
                    if (cube[start + rowIdx[i] * step1 + colIdx[j] * step2]) 1 else 0
            }
        }

        /*
         * Search every subset of columns whose size is between 2 and
         * n-2. The binary counter is upstream's, including the order of
         * the increments, because the first deduction changes the cube.
         */
        for (i in 0 until n) set[i] = 0
        var count = 0
        while (true) {
            if (count > 1 && count < n - 1) {
                var rows = 0
                for (i in 0 until n) {
                    var ok = true
                    for (j in 0 until n) {
                        if (set[j] != 0 && grid[i * o + j] != 0) {
                            ok = false
                            break
                        }
                    }
                    if (ok) rows++
                }

                /*
                 * More suitable rows than columns left means a faulty
                 * deduction or a bogus clue.
                 */
                if (rows > n - count) return -1

                if (rows >= n - count) {
                    var progress = false
                    for (i in 0 until n) {
                        var ok = true
                        for (j in 0 until n) {
                            if (set[j] != 0 && grid[i * o + j] != 0) {
                                ok = false
                                break
                            }
                        }
                        if (!ok) {
                            for (j in 0 until n) {
                                if (set[j] == 0 && grid[i * o + j] != 0) {
                                    val fpos = start + rowIdx[i] * step1 + colIdx[j] * step2
                                    progress = true
                                    cube[fpos] = false
                                }
                            }
                        }
                    }
                    if (progress) return 1
                }
            }

            /*
             * Binary increment: the rightmost 0 becomes a 1 and every 1
             * to its right becomes a 0.
             */
            i = n
            while (i > 0 && set[i - 1] != 0) {
                i--
                set[i] = 0
                count--
            }
            if (i > 0) {
                i--
                set[i] = 1
                count++
            } else {
                break
            }
        }

        return 0
    }

    /**
     * `latin_solver_forcing`: forcing chains.
     *
     * Starts a breadth first search at every square with exactly two
     * candidates, once per candidate, and looks for the far end of the
     * chain that lets it rule the original digit out of a square in line
     * with the start. Returns 1 after one elimination.
     */
    fun forcing(scratch: LatinScratch): Int {
        val o = order
        val number = scratch.grid
        val bfsQueue = scratch.bfsQueue
        val neighbours = scratch.neighbours

        for (y in 0 until o) {
            for (x in 0 until o) {
                /*
                 * Only squares with exactly two candidates take part.
                 * The sum t of the candidate digits identifies `the
                 * other one` without a second scan.
                 */
                var count = 0
                var t = 0
                for (n in 1..o) {
                    if (cubeAt(x, y, n)) {
                        count++
                        t += n
                    }
                }
                if (count != 2) continue

                for (n in 1..o) {
                    if (!cubeAt(x, y, n)) continue
                    val orign = n

                    for (i in 0 until o * o) number[i] = o + 1
                    var head = 0
                    var tail = 0
                    bfsQueue[tail++] = y * o + x
                    number[y * o + x] = t - n

                    while (head < tail) {
                        var xx = bfsQueue[head++]
                        var yy = xx / o
                        xx %= o
                        val currn = number[yy * o + xx]

                        /*
                         * The neighbours of (xx,yy) are its column and
                         * its row.
                         */
                        var nneighbours = 0
                        for (yt in 0 until o) neighbours[nneighbours++] = yt * o + xx
                        for (xt in 0 until o) neighbours[nneighbours++] = yy * o + xt

                        for (i in 0 until nneighbours) {
                            var xt = neighbours[i] % o
                            var yt = neighbours[i] / o

                            if (number[yt * o + xt] <= o) continue
                            if (!cubeAt(xt, yt, currn)) continue
                            if (xt == xx && yt == yy) continue

                            /*
                             * To continue the chain, the neighbour must
                             * also have exactly two candidates.
                             */
                            var cc = 0
                            var tt = 0
                            for (nn in 1..o) {
                                if (cubeAt(xt, yt, nn)) {
                                    cc++
                                    tt += nn
                                }
                            }
                            if (cc == 2) {
                                bfsQueue[tail++] = yt * o + xt
                                number[yt * o + xt] = tt - currn
                            }

                            /*
                             * The end of a chain is a square in line
                             * with the start that carries the digit the
                             * chain started by ruling out.
                             */
                            if (currn == orign && (xt == x || yt == y)) {
                                setCube(xt, yt, orign, false)
                                return 1
                            }
                        }
                    }
                }
            }
        }

        return 0
    }

    /**
     * `latin_solver_diff_simple`: repeated positional elimination over
     * rows, then columns, then the remaining squares.
     */
    fun diffSimple(): Int {
        val o = order

        for (y in 0 until o) {
            for (n in 1..o) {
                if (!row[y * o + n - 1]) {
                    val ret = elim(cubePos(0, y, n), o * o)
                    if (ret != 0) return ret
                }
            }
        }

        for (x in 0 until o) {
            for (n in 1..o) {
                if (!col[x * o + n - 1]) {
                    val ret = elim(cubePos(x, 0, n), o)
                    if (ret != 0) return ret
                }
            }
        }

        for (x in 0 until o) {
            for (y in 0 until o) {
                if (grid[y * o + x] == 0) {
                    val ret = elim(cubePos(x, y, 1), 1)
                    if (ret != 0) return ret
                }
            }
        }

        return 0
    }

    /**
     * `latin_solver_diff_set`: set elimination over rows and columns, or
     * the harder single-digit row-versus-column form when [extreme].
     */
    fun diffSet(scratch: LatinScratch, extreme: Boolean): Int {
        val o = order
        if (!extreme) {
            for (y in 0 until o) {
                val ret = setElim(scratch, cubePos(0, y, 1), o * o, 1)
                if (ret != 0) return ret
            }
            for (x in 0 until o) {
                val ret = setElim(scratch, cubePos(x, 0, 1), o, 1)
                if (ret != 0) return ret
            }
        } else {
            for (n in 1..o) {
                val ret = setElim(scratch, cubePos(0, 0, n), o * o, o)
                if (ret != 0) return ret
            }
        }
        return 0
    }
}

/**
 * The buffers of `latin_solver_set` and `latin_solver_forcing`.
 *
 * Upstream reuses one two-dimensional byte array for the set matrix and
 * for the forcing-chain numbers, so this class does the same. The
 * buffers are scratch only and carry no state between calls.
 */
class LatinScratch(order: Int) {
    internal val grid = IntArray(order * order)
    internal val rowIdx = IntArray(order)
    internal val colIdx = IntArray(order)
    internal val set = IntArray(order)
    internal val neighbours = IntArray(3 * order)
    internal val bfsQueue = IntArray(order * order)
}

/**
 * `latin_solver`: allocates a solver, runs the main loop, and returns
 * the difficulty result.
 *
 * [grid] is `order*order` digits with 0 for an empty square. It is
 * modified in place, as in C: a single solution is written back, and a
 * partial grid is left as the deductions produced it.
 *
 * [userSolvers] is indexed by difficulty level; an entry may be null.
 * [ctx], [ctxNew], and [ctxFree] are the game context: [ctxNew] copies
 * it for a recursive attempt, and [ctxFree] frees that copy. A non-null
 * [ctxNew] needs a non-null [ctxFree], as in C.
 */
fun latinSolve(
    grid: IntArray,
    order: Int,
    maxDiff: Int,
    diffSimple: Int,
    diffSet0: Int,
    diffSet1: Int,
    diffForcing: Int,
    diffRecursive: Int,
    userSolvers: List<LatinUserSolver?> = emptyList(),
    valid: LatinValidator? = null,
    ctx: Any? = null,
    ctxNew: LatinCtxNew? = null,
    ctxFree: LatinCtxFree? = null,
): Int {
    val solver = LatinSolver.alloc(grid, order) ?: return LatinSolver.DIFF_IMPOSSIBLE
    return latinSolveMain(
        solver, maxDiff, diffSimple, diffSet0, diffSet1, diffForcing, diffRecursive,
        userSolvers, valid, ctx, ctxNew, ctxFree,
    )
}

/**
 * `latin_solver_main`: the same as [latinSolve] for a caller that owns
 * the solver and its grid.
 */
fun latinSolveMain(
    solver: LatinSolver,
    maxDiff: Int,
    diffSimple: Int,
    diffSet0: Int,
    diffSet1: Int,
    diffForcing: Int,
    diffRecursive: Int,
    userSolvers: List<LatinUserSolver?> = emptyList(),
    valid: LatinValidator? = null,
    ctx: Any? = null,
    ctxNew: LatinCtxNew? = null,
    ctxFree: LatinCtxFree? = null,
): Int = latinSolverTop(
    solver, maxDiff, diffSimple, diffSet0, diffSet1, diffForcing, diffRecursive,
    userSolvers, valid, ctx, ctxNew, ctxFree,
)

/**
 * `latin_solver_top`: repeated passes over the permitted strategies.
 *
 * The pass restarts at the lowest difficulty after every deduction,
 * because a deduction can enable a simpler one. When no strategy makes
 * progress, the loop ends and, when recursion is permitted, the solver
 * guesses at the most constrained square.
 */
private fun latinSolverTop(
    solver: LatinSolver,
    maxDiff: Int,
    diffSimple: Int,
    diffSet0: Int,
    diffSet1: Int,
    diffForcing: Int,
    diffRecursive: Int,
    userSolvers: List<LatinUserSolver?>,
    valid: LatinValidator?,
    ctx: Any?,
    ctxNew: LatinCtxNew?,
    ctxFree: LatinCtxFree?,
): Int {
    val scratch = solver.newScratch()
    var diff = diffSimple

    check(maxDiff <= diffRecursive) { "latin maxdiff $maxDiff is above recursion $diffRecursive" }

    var gotResult = false
    loop@ while (true) {
        var i = 0
        while (i <= maxDiff) {
            var ret = userSolvers.getOrNull(i)?.invoke(solver, ctx) ?: 0
            if (ret == 0 && i == diffSimple) ret = solver.diffSimple()
            if (ret == 0 && i == diffSet0) ret = solver.diffSet(scratch, false)
            if (ret == 0 && i == diffSet1) ret = solver.diffSet(scratch, true)
            if (ret == 0 && i == diffForcing) ret = solver.forcing(scratch)

            if (ret < 0) {
                diff = LatinSolver.DIFF_IMPOSSIBLE
                gotResult = true
                break@loop
            } else if (ret > 0) {
                diff = maxOf(diff, i)
                continue@loop
            }
            i++
        }

        /*
         * A full pass made no deduction, so the loop ends here.
         */
        break
    }

    if (!gotResult) {
        if (maxDiff == diffRecursive) {
            val nsol = latinSolverRecurse(
                solver, diffSimple, diffSet0, diffSet1, diffForcing, diffRecursive,
                userSolvers, valid, ctx, ctxNew, ctxFree,
            )
            if (nsol < 0) {
                diff = LatinSolver.DIFF_IMPOSSIBLE
            } else if (nsol == 1) {
                diff = diffRecursive
            } else if (nsol > 1) {
                diff = LatinSolver.DIFF_AMBIGUOUS
            }
            // nsol == 0 means the grid was complete, so diff is unchanged.
        } else {
            /*
             * Recursion is forbidden. A grid with an empty square is
             * unfinished rather than solved.
             */
            for (y in 0 until solver.order) {
                for (x in 0 until solver.order) {
                    if (solver.grid[y * solver.order + x] == 0) {
                        diff = LatinSolver.DIFF_UNFINISHED
                    }
                }
            }
        }
    }

    if (diff != LatinSolver.DIFF_IMPOSSIBLE &&
        diff != LatinSolver.DIFF_UNFINISHED &&
        diff != LatinSolver.DIFF_AMBIGUOUS &&
        valid != null && !valid.invoke(solver, ctx)
    ) {
        diff = LatinSolver.DIFF_IMPOSSIBLE
    }

    return diff
}

/**
 * `latin_solver_recurse`: guesses at the square with the fewest
 * candidates and runs the full solver on every candidate until a second
 * solution appears.
 *
 * Returns 0 when the grid was already complete, -1 when no candidate
 * leads to a solution, 1 for exactly one solution, and 2 for more than
 * one. The first solution is copied into the caller's grid.
 */
private fun latinSolverRecurse(
    solver: LatinSolver,
    diffSimple: Int,
    diffSet0: Int,
    diffSet1: Int,
    diffForcing: Int,
    diffRecursive: Int,
    userSolvers: List<LatinUserSolver?>,
    valid: LatinValidator?,
    ctx: Any?,
    ctxNew: LatinCtxNew?,
    ctxFree: LatinCtxFree?,
): Int {
    val o = solver.order
    var best = -1
    var bestCount = o + 1

    for (y in 0 until o) {
        for (x in 0 until o) {
            if (solver.grid[y * o + x] != 0) continue

            var count = 0
            for (n in 1..o) {
                if (solver.cubeAt(x, y, n)) count++
            }

            /*
             * Any impossibility was found by the simple strategies, so
             * an empty square always has at least two candidates here.
             */
            check(count > 1) { "latin recursion found $count candidates at ($x,$y)" }

            if (count < bestCount) {
                bestCount = count
                best = y * o + x
            }
        }
    }

    if (best == -1) {
        /* The grid was complete already. */
        return 0
    }

    val y = best / o
    val x = best % o

    val list = IntArray(o)
    var listSize = 0
    for (n in 1..o) {
        if (solver.cubeAt(x, y, n)) list[listSize++] = n
    }

    val ingrid = solver.grid.copyOf(o * o)
    val outgrid = IntArray(o * o)
    var diff = LatinSolver.DIFF_IMPOSSIBLE

    for (i in 0 until listSize) {
        ingrid.copyInto(outgrid)
        outgrid[y * o + x] = list[i]

        val newCtx = if (ctxNew != null) ctxNew.invoke(ctx) else ctx
        val subsolver = LatinSolver.alloc(outgrid, o)
        val ret = if (subsolver != null) {
            latinSolverTop(
                subsolver, diffRecursive, diffSimple, diffSet0, diffSet1, diffForcing,
                diffRecursive, userSolvers, valid, newCtx, ctxNew, ctxFree,
            )
        } else {
            LatinSolver.DIFF_IMPOSSIBLE
        }
        if (ctxNew != null) {
            val free: LatinCtxFree = ctxFree ?: error("latin ctxNew needs ctxFree")
            free(newCtx)
        }

        /*
         * Recursion goes as deep as it can, so it never gives up on a
         * puzzle without declaring it impossible.
         */
        check(ret != LatinSolver.DIFF_UNFINISHED) { "latin recursion gave up" }

        /*
         * The first solution is the one that is returned.
         */
        if (diff == LatinSolver.DIFF_IMPOSSIBLE && ret != LatinSolver.DIFF_IMPOSSIBLE) {
            outgrid.copyInto(solver.grid, 0, 0, o * o)
        }

        if (ret == LatinSolver.DIFF_AMBIGUOUS) {
            diff = LatinSolver.DIFF_AMBIGUOUS
        } else if (ret == LatinSolver.DIFF_IMPOSSIBLE) {
            /* No solution here, so the return value does not change. */
        } else if (diff == LatinSolver.DIFF_IMPOSSIBLE) {
            diff = diffRecursive
        } else {
            diff = LatinSolver.DIFF_AMBIGUOUS
        }

        /*
         * A second solution is enough.
         */
        if (diff == LatinSolver.DIFF_AMBIGUOUS) break
    }

    return when (diff) {
        LatinSolver.DIFF_IMPOSSIBLE -> -1
        LatinSolver.DIFF_AMBIGUOUS -> 2
        else -> {
            check(diff == diffRecursive) { "latin recursion ended at $diff" }
            1
        }
    }
}

/**
 * `latin_generate`: a random Latin square of [order], row by row.
 *
 * Any r by n Latin rectangle with r < n extends to an r+1 by n one, so
 * a row can be written as any matching that does not conflict with the
 * rows above. The matching subroutine makes the random choices, and the
 * row order is shuffled first, so the whole square is uniform enough for
 * upstream's purpose. Every generator draw is part of the contract.
 */
fun generate(order: Int, random: RandomState): IntArray {
    require(order in 1..LatinSolver.MAX_ORDER) { "latin order must be 1..255, not $order" }
    val o = order
    val sq = IntArray(o * o)

    val rowOrder = IntArray(o) { it }
    shuffle(rowOrder, random)

    for (i in 0 until o) {
        /*
         * Each column is joined to every digit it does not hold yet.
         */
        val adjacency = Array(o) { IntArray(o) }

        for (j in 0 until o) {
            val adj = IntArray(o) { 1 }
            for (k in 0 until i) {
                adj[sq[rowOrder[k] * o + j] - 1] = 0
            }
            var p = 0
            for (k in 0 until o) {
                if (adj[k] != 0) adjacency[j][p++] = k
            }
            adjacency[j] = adjacency[j].copyOf(p)
        }

        val matching = Matching.match(o, o, adjacency, random)

        /*
         * The theorem above guarantees a full matching, so a short one
         * is a bug in the adjacency lists rather than a bad draw.
         */
        check(matching.count == o) { "latin generation matching covered ${matching.count} of $o" }

        for (j in 0 until o) {
            sq[rowOrder[i] * o + j] = matching.leftToRight[j] + 1
        }
    }

    return sq
}

/**
 * `latin_generate_rect`: the top left w by h rectangle of a Latin
 * square of order max(w,h).
 */
fun generateRect(w: Int, h: Int, random: RandomState): IntArray {
    require(w >= 1 && h >= 1) { "latin rectangle must be at least 1x1, not $w x $h" }
    val o = maxOf(w, h)
    val latin = generate(o, random)
    val rect = IntArray(w * h)
    for (x in 0 until w) {
        for (y in 0 until h) {
            rect[y * w + x] = latin[y * o + x]
        }
    }
    return rect
}

/**
 * `latin_check`: true when [sq] is not a Latin square of [order].
 *
 * Upstream counts the digits instead of checking rows and columns: the
 * square is accepted when exactly `order` distinct digits occur and each
 * occurs exactly `order` times. That is weaker than a Latin square, but
 * it is what callers rely on, so the port keeps it.
 */
fun check(sq: IntArray, order: Int): Boolean {
    require(order >= 0) { "latin order must not be negative: $order" }
    require(sq.size >= order * order) {
        "latin check needs ${order * order} entries, not ${sq.size}"
    }

    val counts = IntArray(LatinSolver.MAX_ORDER + 1)
    var distinct = 0
    for (c in 0 until order) {
        for (r in 0 until order) {
            val elt = sq[r * order + c]
            require(elt in 0..LatinSolver.MAX_ORDER) { "latin check value $elt is not a digit" }
            if (counts[elt] == 0) distinct++
            counts[elt]++
        }
    }

    if (distinct != order) return true
    for (c in counts.indices) {
        if (counts[c] != 0 && counts[c] != order) return true
    }
    return false
}

/** `shuffle` from `misc.c`, with `nelts` draws of `random_upto`. */
private fun shuffle(array: IntArray, random: RandomState) {
    var i = array.size
    while (i-- > 1) {
        val j = random.upto((i + 1).toLong()).toInt()
        if (j != i) {
            val tmp = array[i]
            array[i] = array[j]
            array[j] = tmp
        }
    }
}
