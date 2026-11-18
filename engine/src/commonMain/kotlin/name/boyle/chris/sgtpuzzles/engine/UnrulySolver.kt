package name.boyle.chris.sgtpuzzles.engine

/**
 * The deduction solver and the description generator of upstream
 * `unruly.c`.
 *
 * The solver is not a backtracking search: it applies five techniques in
 * a fixed order and restarts at the first technique after any placement.
 * The generator uses the solver for two decisions, so a difference here
 * changes every description and every `SOLVE` record:
 *
 * 1. the fill loop calls the solver at `DIFFCOUNT` after every placed
 *    cell, and its fills decide how many `random_upto(2)` draws happen;
 * 2. the clue winnow removes a clue only when the solver still balances
 *    every line, and the "too easy" retry runs one difficulty lower.
 *
 * The RNG order is part of the contract: one `shuffle` of the cell list
 * per fill attempt, one `random_upto(2)` per cell that is still empty,
 * and one `shuffle` of the cell list for the winnow. Retries do not
 * reset the generator.
 */
internal object UnrulySolver {

    /** `EMPTY` from `unruly.c:115`. */
    const val EMPTY = 0

    /** `N_ONE` from `unruly.c:116`. */
    const val N_ONE = 1

    /** `N_ZERO` from `unruly.c:117`. */
    const val N_ZERO = 2

    /**
     * `BOGUS` from `unruly.c:118`. It is a temporary marker that
     * `check_near_complete` writes and restores; it never leaves the
     * solver.
     */
    const val BOGUS = 3

    /** `DIFF_TRIVIAL`, `DIFF_EASY`, and `DIFF_NORMAL` from `unruly.c:87-96`. */
    const val DIFF_TRIVIAL = 0
    const val DIFF_EASY = 1
    const val DIFF_NORMAL = 2

    /** `DIFFCOUNT`. */
    const val DIFFCOUNT = 3

    /**
     * `struct unruly_scratch`: four counters per line. The solver
     * mutates them in the same step as the grid, and later passes read
     * them, so every placement must update them.
     */
    class Scratch internal constructor(width: Int, height: Int) {
        val onesRows = IntArray(height)
        val onesCols = IntArray(width)
        val zerosRows = IntArray(height)
        val zerosCols = IntArray(width)
    }

    /** `unruly_solver_update_remaining`: recount all four arrays. */
    fun updateRemaining(grid: IntArray, w: Int, h: Int, scratch: Scratch) {
        scratch.onesRows.fill(0)
        scratch.onesCols.fill(0)
        scratch.zerosRows.fill(0)
        scratch.zerosCols.fill(0)
        for (x in 0 until w) {
            for (y in 0 until h) {
                when (grid[y * w + x]) {
                    N_ONE -> {
                        scratch.onesRows[y]++
                        scratch.onesCols[x]++
                    }

                    N_ZERO -> {
                        scratch.zerosRows[y]++
                        scratch.zerosCols[x]++
                    }
                }
            }
        }
    }

    /** `unruly_new_scratch`. */
    fun newScratch(grid: IntArray, w: Int, h: Int): Scratch {
        val scratch = Scratch(w, h)
        updateRemaining(grid, w, h, scratch)
        return scratch
    }

    // ------------------------------------------------------------------
    // Techniques. Each pass mutates the grid and the counters.
    // ------------------------------------------------------------------

    /**
     * `unruly_solver_check_threes`: for every in-line triple with two
     * `check` cells and one `EMPTY`, write `block` at the empty cell.
     * The three tests are sequential `if`s, not `else if`s, so a write
     * from one test is visible to the next, as in C.
     */
    fun checkThrees(
        grid: IntArray,
        w: Int,
        h: Int,
        rowcount: IntArray,
        colcount: IntArray,
        horizontal: Boolean,
        check: Int,
        block: Int,
    ): Int {
        val dx = if (horizontal) 1 else 0
        val dy = 1 - dx
        val sx = dx
        val sy = dy
        val ex = w - dx
        val ey = h - dy
        var ret = 0

        for (y in sy until ey) {
            for (x in sx until ex) {
                val i1 = (y - dy) * w + (x - dx)
                val i2 = y * w + x
                val i3 = (y + dy) * w + (x + dx)

                if (grid[i1] == check && grid[i2] == check && grid[i3] == EMPTY) {
                    ret++
                    grid[i3] = block
                    rowcount[i3 / w]++
                    colcount[i3 % w]++
                }
                if (grid[i1] == check && grid[i2] == EMPTY && grid[i3] == check) {
                    ret++
                    grid[i2] = block
                    rowcount[i2 / w]++
                    colcount[i2 % w]++
                }
                if (grid[i1] == EMPTY && grid[i2] == check && grid[i3] == check) {
                    ret++
                    grid[i1] = block
                    rowcount[i1 / w]++
                    colcount[i1 % w]++
                }
            }
        }
        return ret
    }

    /** `unruly_solver_check_all_threes`: four calls in C order. */
    fun checkAllThrees(grid: IntArray, w: Int, h: Int, scratch: Scratch): Int {
        var ret = 0
        ret += checkThrees(
            grid, w, h, scratch.zerosRows, scratch.zerosCols, true, N_ONE, N_ZERO,
        )
        ret += checkThrees(
            grid, w, h, scratch.onesRows, scratch.onesCols, true, N_ZERO, N_ONE,
        )
        ret += checkThrees(
            grid, w, h, scratch.zerosRows, scratch.zerosCols, false, N_ONE, N_ZERO,
        )
        ret += checkThrees(
            grid, w, h, scratch.onesRows, scratch.onesCols, false, N_ZERO, N_ONE,
        )
        return ret
    }

    /**
     * `unruly_solver_check_uniques`: find a line with `max` of `check`
     * and a second line with `max-1` that matches in all those
     * positions, and block the one position where they still agree.
     *
     * C asserts `nonmatch != -1` and `grid[i1] == EMPTY`. Both follow
     * from the counter invariant; the port skips the pair instead, so an
     * impossible state cannot index `-1` or overwrite a value. The
     * guards are unreachable through the public API, because the
     * counters are rebuilt from the grid and updated on every placement.
     */
    fun checkUniques(
        grid: IntArray,
        w: Int,
        h: Int,
        rowcount: IntArray,
        horizontal: Boolean,
        check: Int,
        block: Int,
        scratch: Scratch,
    ): Int {
        val rmult = if (horizontal) w else 1
        val cmult = if (horizontal) 1 else w
        val nr = if (horizontal) h else w
        val nc = if (horizontal) w else h
        val max = nc / 2
        var ret = 0

        for (r in 0 until nr) {
            if (rowcount[r] != max) continue
            for (r2 in 0 until nr) {
                if (rowcount[r2] != max - 1) continue
                var nmatch = 0
                var nonmatch = -1
                for (c in 0 until nc) {
                    if (grid[r * rmult + c * cmult] == check) {
                        if (grid[r2 * rmult + c * cmult] == check) {
                            nmatch++
                        } else {
                            nonmatch = c
                        }
                    }
                }
                if (nmatch == max - 1) {
                    if (nonmatch < 0) continue
                    val i1 = r2 * rmult + nonmatch * cmult
                    if (grid[i1] == block) continue
                    if (grid[i1] != EMPTY) continue
                    grid[i1] = block
                    if (block == N_ONE) {
                        scratch.onesRows[i1 / w]++
                        scratch.onesCols[i1 % w]++
                    } else {
                        scratch.zerosRows[i1 / w]++
                        scratch.zerosCols[i1 % w]++
                    }
                    ret++
                }
            }
        }
        return ret
    }

    /** `unruly_solver_check_all_uniques`: four calls in C order. */
    fun checkAllUniques(grid: IntArray, w: Int, h: Int, scratch: Scratch): Int {
        var ret = 0
        ret += checkUniques(
            grid, w, h, scratch.onesRows, true, N_ONE, N_ZERO, scratch,
        )
        ret += checkUniques(
            grid, w, h, scratch.zerosRows, true, N_ZERO, N_ONE, scratch,
        )
        ret += checkUniques(
            grid, w, h, scratch.onesCols, false, N_ONE, N_ZERO, scratch,
        )
        ret += checkUniques(
            grid, w, h, scratch.zerosCols, false, N_ZERO, N_ONE, scratch,
        )
        return ret
    }

    /** `unruly_solver_fill_row`: fill every empty cell of one line. */
    fun fillRow(
        grid: IntArray,
        w: Int,
        h: Int,
        i: Int,
        horizontal: Boolean,
        rowcount: IntArray,
        colcount: IntArray,
        fill: Int,
    ): Int {
        var ret = 0
        val count = if (horizontal) w else h
        for (j in 0 until count) {
            val p = if (horizontal) i * w + j else j * w + i
            if (grid[p] == EMPTY) {
                ret++
                grid[p] = fill
                rowcount[if (horizontal) i else j]++
                colcount[if (horizontal) j else i]++
            }
        }
        return ret
    }

    /** `unruly_solver_check_single_gap`. */
    fun checkSingleGap(
        grid: IntArray,
        w: Int,
        h: Int,
        complete: IntArray,
        horizontal: Boolean,
        rowcount: IntArray,
        colcount: IntArray,
        fill: Int,
    ): Int {
        val count = if (horizontal) h else w
        val target = (if (horizontal) w else h) / 2
        val other = if (horizontal) rowcount else colcount
        var ret = 0
        for (i in 0 until count) {
            if (complete[i] == target && other[i] == target - 1) {
                ret += fillRow(grid, w, h, i, horizontal, rowcount, colcount, fill)
            }
        }
        return ret
    }

    /** `unruly_solver_check_all_single_gap`: four calls in C order. */
    fun checkAllSingleGap(grid: IntArray, w: Int, h: Int, scratch: Scratch): Int {
        var ret = 0
        ret += checkSingleGap(
            grid, w, h, scratch.onesRows, true,
            scratch.zerosRows, scratch.zerosCols, N_ZERO,
        )
        ret += checkSingleGap(
            grid, w, h, scratch.onesCols, false,
            scratch.zerosRows, scratch.zerosCols, N_ZERO,
        )
        ret += checkSingleGap(
            grid, w, h, scratch.zerosRows, true,
            scratch.onesRows, scratch.onesCols, N_ONE,
        )
        ret += checkSingleGap(
            grid, w, h, scratch.zerosCols, false,
            scratch.onesRows, scratch.onesCols, N_ONE,
        )
        return ret
    }

    /** `unruly_solver_check_complete_nums`. */
    fun checkCompleteNums(
        grid: IntArray,
        w: Int,
        h: Int,
        complete: IntArray,
        horizontal: Boolean,
        rowcount: IntArray,
        colcount: IntArray,
        fill: Int,
    ): Int {
        val count = if (horizontal) h else w
        val target = (if (horizontal) w else h) / 2
        val other = if (horizontal) rowcount else colcount
        var ret = 0
        for (i in 0 until count) {
            if (complete[i] == target && other[i] < target) {
                ret += fillRow(grid, w, h, i, horizontal, rowcount, colcount, fill)
            }
        }
        return ret
    }

    /** `unruly_solver_check_all_complete_nums`: four calls in C order. */
    fun checkAllCompleteNums(grid: IntArray, w: Int, h: Int, scratch: Scratch): Int {
        var ret = 0
        ret += checkCompleteNums(
            grid, w, h, scratch.onesRows, true,
            scratch.zerosRows, scratch.zerosCols, N_ZERO,
        )
        ret += checkCompleteNums(
            grid, w, h, scratch.onesCols, false,
            scratch.zerosRows, scratch.zerosCols, N_ZERO,
        )
        ret += checkCompleteNums(
            grid, w, h, scratch.zerosRows, true,
            scratch.onesRows, scratch.onesCols, N_ONE,
        )
        ret += checkCompleteNums(
            grid, w, h, scratch.zerosCols, false,
            scratch.onesRows, scratch.onesCols, N_ONE,
        )
        return ret
    }

    /**
     * `unruly_solver_check_near_complete`: mask the two or three empty
     * cells of a nearly satisfied line with `BOGUS`, fill the rest of
     * the line, then restore the masked cells. A `BOGUS` cell is not
     * `EMPTY`, so `fillRow` skips it.
     */
    fun checkNearComplete(
        grid: IntArray,
        w: Int,
        h: Int,
        complete: IntArray,
        horizontal: Boolean,
        rowcount: IntArray,
        colcount: IntArray,
        fill: Int,
    ): Int {
        val halfW = w / 2
        val halfH = h / 2
        val dx = if (horizontal) 1 else 0
        val dy = 1 - dx
        val sx = dx
        val sy = dy
        val ex = w - dx
        val ey = h - dy
        var ret = 0

        for (y in sy until ey) {
            if (horizontal && (complete[y] < halfW - 1 || rowcount[y] > halfW - 2)) {
                continue
            }
            for (x in sx until ex) {
                if (!horizontal && (complete[x] < halfH - 1 || colcount[x] > halfH - 2)) {
                    continue
                }
                val i = if (horizontal) y else x
                val i1 = (y - dy) * w + (x - dx)
                val i2 = y * w + x
                val i3 = (y + dy) * w + (x + dx)

                if (grid[i1] == fill && grid[i2] == EMPTY && grid[i3] == EMPTY) {
                    grid[i2] = BOGUS
                    grid[i3] = BOGUS
                    ret += fillRow(grid, w, h, i, horizontal, rowcount, colcount, fill)
                    grid[i2] = EMPTY
                    grid[i3] = EMPTY
                } else if (grid[i1] == EMPTY && grid[i2] == fill && grid[i3] == EMPTY) {
                    grid[i1] = BOGUS
                    grid[i3] = BOGUS
                    ret += fillRow(grid, w, h, i, horizontal, rowcount, colcount, fill)
                    grid[i1] = EMPTY
                    grid[i3] = EMPTY
                } else if (grid[i1] == EMPTY && grid[i2] == EMPTY && grid[i3] == fill) {
                    grid[i1] = BOGUS
                    grid[i2] = BOGUS
                    ret += fillRow(grid, w, h, i, horizontal, rowcount, colcount, fill)
                    grid[i1] = EMPTY
                    grid[i2] = EMPTY
                } else if (grid[i1] == EMPTY && grid[i2] == EMPTY && grid[i3] == EMPTY) {
                    grid[i1] = BOGUS
                    grid[i2] = BOGUS
                    grid[i3] = BOGUS
                    ret += fillRow(grid, w, h, i, horizontal, rowcount, colcount, fill)
                    grid[i1] = EMPTY
                    grid[i2] = EMPTY
                    grid[i3] = EMPTY
                }
            }
        }
        return ret
    }

    /** `unruly_solver_check_all_near_complete`: four calls in C order. */
    fun checkAllNearComplete(grid: IntArray, w: Int, h: Int, scratch: Scratch): Int {
        var ret = 0
        ret += checkNearComplete(
            grid, w, h, scratch.onesRows, true,
            scratch.zerosRows, scratch.zerosCols, N_ZERO,
        )
        ret += checkNearComplete(
            grid, w, h, scratch.onesCols, false,
            scratch.zerosRows, scratch.zerosCols, N_ZERO,
        )
        ret += checkNearComplete(
            grid, w, h, scratch.zerosRows, true,
            scratch.onesRows, scratch.onesCols, N_ONE,
        )
        ret += checkNearComplete(
            grid, w, h, scratch.zerosCols, false,
            scratch.onesRows, scratch.onesCols, N_ONE,
        )
        return ret
    }

    // ------------------------------------------------------------------
    // The driver
    // ------------------------------------------------------------------

    /**
     * `unruly_solve_game`: the technique order of `unruly.c:1195-1263`.
     * Every successful technique restarts at `check_all_threes`, so a
     * later technique can enable an earlier one. The returned value is
     * the highest technique used, or -1 when nothing was placed.
     */
    fun solveGame(
        grid: IntArray,
        w: Int,
        h: Int,
        unique: Boolean,
        scratch: Scratch,
        diff: Int,
    ): Int {
        var maxdiff = -1
        while (true) {
            var done = checkAllThrees(grid, w, h, scratch)
            if (done != 0) {
                if (maxdiff < DIFF_TRIVIAL) maxdiff = DIFF_TRIVIAL
                continue
            }

            done = checkAllSingleGap(grid, w, h, scratch)
            if (done != 0) {
                if (maxdiff < DIFF_TRIVIAL) maxdiff = DIFF_TRIVIAL
                continue
            }

            if (diff < DIFF_EASY) break

            done = checkAllCompleteNums(grid, w, h, scratch)
            if (done != 0) {
                if (maxdiff < DIFF_EASY) maxdiff = DIFF_EASY
                continue
            }

            if (unique) {
                done = checkAllUniques(grid, w, h, scratch)
                if (done != 0) {
                    if (maxdiff < DIFF_EASY) maxdiff = DIFF_EASY
                    continue
                }
            }

            if (diff < DIFF_NORMAL) break

            done = checkAllNearComplete(grid, w, h, scratch)
            if (done != 0) {
                if (maxdiff < DIFF_NORMAL) maxdiff = DIFF_NORMAL
                continue
            }

            break
        }
        return maxdiff
    }

    // ------------------------------------------------------------------
    // Validation, used by the solver, the completion test, and drawing
    // ------------------------------------------------------------------

    /** `unruly_validate_rows`: mark or count every three-in-a-row. */
    fun validateRows(
        grid: IntArray,
        w: Int,
        h: Int,
        horizontal: Boolean,
        check: Int,
        errors: IntArray?,
    ): Int {
        val dx = if (horizontal) 1 else 0
        val dy = 1 - dx
        val sx = dx
        val sy = dy
        val ex = w - dx
        val ey = h - dy
        val err1 = if (horizontal) Unruly.FE_HOR_ROW_LEFT else Unruly.FE_VER_ROW_TOP
        val err2 = if (horizontal) Unruly.FE_HOR_ROW_MID else Unruly.FE_VER_ROW_MID
        val err3 = if (horizontal) Unruly.FE_HOR_ROW_RIGHT else Unruly.FE_VER_ROW_BOTTOM
        var ret = 0

        for (y in sy until ey) {
            for (x in sx until ex) {
                val i1 = (y - dy) * w + (x - dx)
                val i2 = y * w + x
                val i3 = (y + dy) * w + (x + dx)
                if (grid[i1] == check && grid[i2] == check && grid[i3] == check) {
                    ret++
                    if (errors != null) {
                        errors[i1] = errors[i1] or err1
                        errors[i2] = errors[i2] or err2
                        errors[i3] = errors[i3] or err3
                    }
                }
            }
        }
        return ret
    }

    /** `unruly_validate_unique`: mark every pair of equal full lines. */
    fun validateUnique(
        grid: IntArray,
        w: Int,
        h: Int,
        horizontal: Boolean,
        errors: IntArray?,
    ): Int {
        val rmult = if (horizontal) w else 1
        val cmult = if (horizontal) 1 else w
        val nr = if (horizontal) h else w
        val nc = if (horizontal) w else h
        val err = if (horizontal) Unruly.FE_ROW_MATCH else Unruly.FE_COL_MATCH
        var ret = 0

        for (r in 0 until nr) {
            var nfull = 0
            for (c in 0 until nc) {
                if (grid[r * rmult + c * cmult] != EMPTY) nfull++
            }
            if (nfull != nc) continue
            for (r2 in r + 1 until nr) {
                var match = true
                for (c in 0 until nc) {
                    if (grid[r * rmult + c * cmult] != grid[r2 * rmult + c * cmult]) {
                        match = false
                    }
                }
                if (match) {
                    if (errors != null) {
                        for (c in 0 until nc) {
                            errors[r * rmult + c * cmult] =
                                errors[r * rmult + c * cmult] or err
                            errors[r2 * rmult + c * cmult] =
                                errors[r2 * rmult + c * cmult] or err
                        }
                    }
                    ret++
                }
            }
        }
        return ret
    }

    /** `unruly_validate_all_rows`: 0 when clean, -1 on any error. */
    fun validateAllRows(
        grid: IntArray,
        w: Int,
        h: Int,
        unique: Boolean,
        errors: IntArray?,
    ): Int {
        var errcount = 0
        errcount += validateRows(grid, w, h, true, N_ONE, errors)
        errcount += validateRows(grid, w, h, false, N_ONE, errors)
        errcount += validateRows(grid, w, h, true, N_ZERO, errors)
        errcount += validateRows(grid, w, h, false, N_ZERO, errors)
        if (unique) {
            errcount += validateUnique(grid, w, h, true, errors)
            errcount += validateUnique(grid, w, h, false, errors)
        }
        return if (errcount != 0) -1 else 0
    }

    /**
     * `unruly_validate_counts`: 0 when every line has its target count,
     * 1 when a line is still below it, -1 when a line is above it.
     * `above` wins over `below`, as in C.
     *
     * `errors` is `ds->rowfs`: `[ones_rows, zeros_rows, ones_cols,
     * zeros_cols]`, indexed as in `unruly.c:1157-1186`.
     */
    fun validateCounts(
        grid: IntArray,
        w: Int,
        h: Int,
        scratch: Scratch?,
        errors: BooleanArray?,
    ): Int {
        val sc = scratch ?: newScratch(grid, w, h)
        val halfW = w / 2
        val halfH = h / 2
        var below = false
        var above = false

        for (i in 0 until w) {
            if (sc.onesCols[i] < halfH) below = true
            if (sc.zerosCols[i] < halfH) below = true

            if (sc.onesCols[i] > halfH) {
                above = true
                if (errors != null) errors[2 * h + i] = true
            } else if (errors != null) {
                errors[2 * h + i] = false
            }

            if (sc.zerosCols[i] > halfH) {
                above = true
                if (errors != null) errors[2 * h + w + i] = true
            } else if (errors != null) {
                errors[2 * h + w + i] = false
            }
        }
        for (i in 0 until h) {
            if (sc.onesRows[i] < halfW) below = true
            if (sc.zerosRows[i] < halfW) below = true

            if (sc.onesRows[i] > halfW) {
                above = true
                if (errors != null) errors[i] = true
            } else if (errors != null) {
                errors[i] = false
            }

            if (sc.zerosRows[i] > halfW) {
                above = true
                if (errors != null) errors[h + i] = true
            } else if (errors != null) {
                errors[h + i] = false
            }
        }

        return if (above) -1 else if (below) 1 else 0
    }

    // ------------------------------------------------------------------
    // Generator
    // ------------------------------------------------------------------

    /**
     * `unruly_fill_game`: shuffle the cell list, then place a random
     * value in each cell that is still empty and run the solver at
     * `DIFFCOUNT`. The solver may fill many cells, which is why later
     * cells in the shuffled order skip their draw.
     */
    fun fillGame(
        grid: IntArray,
        w: Int,
        h: Int,
        unique: Boolean,
        scratch: Scratch,
        random: RandomState,
    ): Boolean {
        val s = w * h
        val spaces = IntArray(s) { it }
        shuffle(spaces, random)

        for (j in 0 until s) {
            val i = spaces[j]
            if (grid[i] != EMPTY) continue
            if (random.upto(2) != 0L) {
                grid[i] = N_ONE
                scratch.onesRows[i / w]++
                scratch.onesCols[i % w]++
            } else {
                grid[i] = N_ZERO
                scratch.zerosRows[i / w]++
                scratch.zerosCols[i % w]++
            }
            solveGame(grid, w, h, unique, scratch, DIFFCOUNT)
        }

        return validateAllRows(grid, w, h, unique, null) == 0 &&
            validateCounts(grid, w, h, scratch, null) == 0
    }

    /**
     * `new_game_desc`. The outer loop repeats when the puzzle came out
     * too easy: the difficulty-1 solve must leave a line below its
     * target, which means the deduction solver at the requested
     * difficulty is needed. The retry restarts at the fill and does not
     * reset the generator.
     */
    fun generate(
        w: Int,
        h: Int,
        unique: Boolean,
        diff: Int,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): String {
        val s = w * h
        var grid: IntArray
        var scratch: Scratch

        outer@ while (true) {
            cancel.check()
            while (true) {
                cancel.check()
                grid = IntArray(s)
                scratch = newScratch(grid, w, h)
                if (fillGame(grid, w, h, unique, scratch, random)) break
            }

            val spaces = IntArray(s) { it }
            shuffle(spaces, random)

            for (j in 0 until s) {
                val i = spaces[j]
                val c = grid[i]
                grid[i] = EMPTY
                val solver = grid.copyOf()
                val solverScratch = newScratch(grid, w, h)
                solveGame(solver, w, h, unique, solverScratch, diff)
                if (validateCounts(solver, w, h, solverScratch, null) != 0) {
                    grid[i] = c
                }
            }

            if (diff > DIFF_TRIVIAL) {
                val solver = grid.copyOf()
                val solverScratch = newScratch(solver, w, h)
                solveGame(solver, w, h, unique, solverScratch, diff - 1)
                if (validateCounts(solver, w, h, solverScratch, null) > 0) {
                    break@outer
                }
            } else {
                break@outer
            }
        }

        return encodeDescription(grid)
    }

    /**
     * The encoder of `unruly.c:1478-1501`: a run of empty cells followed
     * by a clue is one letter, a 25-run flushes as `z` or `Z`, and the
     * trailing flush writes the terminator. Kotlin builds a string, so
     * the C one-byte overflow at `s + 2` bytes cannot happen here.
     */
    fun encodeDescription(grid: IntArray): String {
        val out = StringBuilder()
        var run = 0
        for (i in 0..grid.size) {
            if (i == grid.size || grid[i] == N_ZERO) {
                while (run > 24) {
                    out.append('z')
                    run -= 25
                }
                out.append(('a'.code + run).toChar())
                run = 0
            } else if (grid[i] == N_ONE) {
                while (run > 24) {
                    out.append('Z')
                    run -= 25
                }
                out.append(('A'.code + run).toChar())
                run = 0
            } else {
                run++
            }
        }
        return out.toString()
    }

    /**
     * `shuffle` from `misc.c:314-324`: `nelts - 1` draws with limits
     * `nelts, nelts-1, ..., 2`. A limit of 1 is never drawn, and a draw
     * that names the current position does not swap.
     */
    private fun shuffle(array: IntArray, random: RandomState) {
        for (i in array.size - 1 downTo 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
            }
        }
    }
}
