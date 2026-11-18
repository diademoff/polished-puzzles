package name.boyle.chris.sgtpuzzles.engine

/**
 * The solver and the generator of upstream `mosaic.c`.
 *
 * Mosaic is Fill-a-Pix: every cell is black or white, and a shown clue
 * gives the number of black cells in the 3x3 block that contains the
 * clue and the clue itself. The solver marks cells from one clue and
 * repeats until a pass makes no progress. It is also the generator's
 * acceptance test and the producer of the `needed` list, so a different
 * mark order changes every description.
 *
 * Three details must not change:
 *
 * - `solve_check` shuffles the shown cells before the first pass
 *   (`mosaic.c:527-529`). The draw happens before the verdict, so a
 *   rejected attempt has already consumed it.
 * - `hide_clues` ignores the verdict of its own `solve_check` and hides
 *   every cell that is not `needed` without a second check
 *   (`mosaic.c:628-645`).
 * - The aggressive hide shuffles the `needed` list and calls the
 *   deterministic solver once per candidate (`mosaic.c:646-676`).
 */
internal object MosaicSolver {

    /** `STATE_UNMARKED`, `STATE_MARKED`, and `STATE_BLANK` from `mosaic.c:62-74`. */
    const val UNMARKED = 0
    const val MARKED = 1
    const val BLANK = 2
    const val OK_NUM = MARKED or BLANK

    /** `struct desc_cell` during generation. */
    class DescCell {
        var clue: Int = 0
        var shown: Boolean = false
        var value: Boolean = false
        var full: Boolean = false
        var empty: Boolean = false
    }

    /** `struct solution_cell`. */
    class SolutionCell {
        var cell: Int = UNMARKED
        var solved: Boolean = false
        var needed: Boolean = false
    }

    /** A generated description and the retry counts, for the tests only. */
    class Generated(
        val desc: String,
        val attempts: Int,
        val startPointRetries: Int,
        val acceptanceRetries: Int,
    )

    /**
     * `new_game_desc` from `mosaic.c:718-829`.
     *
     * The loop draws one bit per cell, populates every 3x3 clue,
     * requires a start point among the first `(h-1)*(w-1)` flat cells,
     * requires a full solve, then hides the clues. All draws come from
     * one [random] stream and a retry does not reset it.
     */
    fun generate(
        width: Int,
        height: Int,
        aggressive: Boolean,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        val n = width * height
        val image = BooleanArray(n)
        val desc = Array(n) { DescCell() }
        var attempts = 0
        var startPointRetries = 0
        var acceptanceRetries = 0

        while (true) {
            cancel.check()
            attempts++

            // `generate_image`: one `random_bits(rs, 1)` per cell.
            for (i in 0 until n) image[i] = random.bits(1) != 0L

            for (y in 0 until height) {
                for (x in 0 until width) {
                    populateCell(
                        width,
                        height,
                        image,
                        x,
                        y,
                        x * y == 0 || y == height - 1 || x == width - 1,
                        desc[y * width + x],
                    )
                }
            }

            if (!startPointCheck((height - 1) * (width - 1), desc)) {
                startPointRetries++
                continue
            }
            if (!solveCheck(width, height, desc, random).valid) {
                acceptanceRetries++
                continue
            }
            hideClues(width, height, desc, aggressive, random)
            return Generated(
                desc = encode(width, height, desc),
                attempts = attempts,
                startPointRetries = startPointRetries,
                acceptanceRetries = acceptanceRetries,
            )
        }
    }

    /** `get_pixel`: zero outside the board. */
    private fun pixel(width: Int, height: Int, image: BooleanArray, x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && image[y * width + x]

    private fun boolToInt(value: Boolean): Int = if (value) 1 else 0

    /**
     * `populate_cell` from `mosaic.c:262-333`. The clue is the 3x3 sum
     * including the cell itself. `empty` is a zero clue, and `full` is
     * a 9 clue or the largest clue an edge cell can reach.
     */
    private fun populateCell(
        width: Int,
        height: Int,
        image: BooleanArray,
        x: Int,
        y: Int,
        edge: Boolean,
        desc: DescCell,
    ) {
        var clue = 0
        var xEdge = false
        var yEdge = false
        if (edge) {
            if (x > 0) {
                clue += boolToInt(pixel(width, height, image, x - 1, y))
                if (y > 0) {
                    clue += boolToInt(pixel(width, height, image, x - 1, y - 1))
                }
                if (y < height - 1) {
                    clue += boolToInt(pixel(width, height, image, x - 1, y + 1))
                }
            } else {
                xEdge = true
            }

            if (y > 0) {
                clue += boolToInt(pixel(width, height, image, x, y - 1))
            } else {
                yEdge = true
            }
            if (x < width - 1) {
                clue += boolToInt(pixel(width, height, image, x + 1, y))
                if (y > 0) {
                    clue += boolToInt(pixel(width, height, image, x + 1, y - 1))
                }
                if (y < height - 1) {
                    clue += boolToInt(pixel(width, height, image, x + 1, y + 1))
                }
            } else {
                xEdge = true
            }
            if (y < height - 1) {
                clue += boolToInt(pixel(width, height, image, x, y + 1))
            } else {
                yEdge = true
            }
        } else {
            clue += boolToInt(pixel(width, height, image, x - 1, y - 1))
            clue += boolToInt(pixel(width, height, image, x - 1, y))
            clue += boolToInt(pixel(width, height, image, x - 1, y + 1))
            clue += boolToInt(pixel(width, height, image, x, y - 1))
            clue += boolToInt(pixel(width, height, image, x, y + 1))
            clue += boolToInt(pixel(width, height, image, x + 1, y - 1))
            clue += boolToInt(pixel(width, height, image, x + 1, y))
            clue += boolToInt(pixel(width, height, image, x + 1, y + 1))
        }

        desc.value = pixel(width, height, image, x, y)
        clue += boolToInt(desc.value)
        if (clue == 0) {
            desc.empty = true
            desc.full = false
        } else {
            desc.empty = false
            desc.full = false
            if (clue == 9) {
                desc.full = true
            } else if (edge && ((xEdge && yEdge && clue == 4) ||
                    ((xEdge || yEdge) && clue == 6))
            ) {
                desc.full = true
            }
        }
        desc.shown = true
        desc.clue = clue
    }

    /**
     * `start_point_check` from `mosaic.c:683-692`: a scan of the first
     * `(height-1)*(width-1)` flat cells, not of the last row and column.
     */
    private fun startPointCheck(size: Int, desc: Array<DescCell>): Boolean {
        for (i in 0 until size) {
            if (desc[i].empty || desc[i].full) return true
        }
        return false
    }

    /** The result of `solve_check`: the verdict and the `sol` array. */
    private class Check(val valid: Boolean, val sol: Array<SolutionCell>)

    /**
     * `solve_check` from `mosaic.c:493-574`.
     *
     * The shown cells are collected in row-major order, then reversed by
     * the prepend in C, then shuffled when [random] is not null. A pass
     * calls `solve_cell` on every entry until a pass makes no progress
     * or a contradiction appears. The verdict is "every cell holds a
     * value", and the scan that counts them runs only when the last pass
     * made progress.
     */
    private fun solveCheck(
        width: Int,
        height: Int,
        desc: Array<DescCell>,
        random: RandomState?,
    ): Check {
        val n = width * height
        val sol = Array(n) { SolutionCell() }

        var shown = 0
        for (i in 0 until n) if (desc[i].shown) shown++
        val order = IntArray(shown)
        var k = 0
        for (i in 0 until n) {
            if (desc[i].shown) order[k++] = i
        }
        // `head` is built by prepending, so the copied array is the
        // reverse of the row-major scan.
        var lo = 0
        var hi = shown - 1
        while (lo < hi) {
            val tmp = order[lo]
            order[lo] = order[hi]
            order[hi] = tmp
            lo++
            hi--
        }
        if (random != null) shuffle(order, random)

        var solved = 0
        var madeProgress = true
        var error = false
        while (solved < shown && madeProgress && !error) {
            madeProgress = false
            for (i in 0 until shown) {
                val index = order[i]
                val x = index % width
                val y = index / width
                val curr = solveCellBody(
                    width,
                    height,
                    sol,
                    x,
                    y,
                    index,
                    desc[index].shown,
                    desc[index].clue,
                    desc[index].full,
                    desc[index].empty,
                )
                if (curr < 0) {
                    error = true
                    break
                }
                if (curr > 0) {
                    solved++
                    madeProgress = true
                }
            }
        }

        var full = 0
        if (madeProgress) {
            for (i in 0 until n) {
                if (sol[i].cell and OK_NUM != 0) full++
            }
        }
        return Check(full == n, sol)
    }

    /**
     * `solve_cell` from `mosaic.c:424-491`, shared by the description and
     * the clue-board caller. The latter passes `full` and `empty` as
     * false, so a board that needs the edge shortcut can fail to solve.
     * The order of the comparisons is part of the result.
     */
    private fun solveCellBody(
        width: Int,
        height: Int,
        sol: Array<SolutionCell>,
        x: Int,
        y: Int,
        index: Int,
        shown: Boolean,
        clue: Int,
        full: Boolean,
        empty: Boolean,
    ): Int {
        val cell = sol[index]
        if (cell.solved) return 0

        val counts = IntArray(3)
        countAround(width, height, sol, x, y, counts)
        val marked = counts[0]
        val blank = counts[1]
        val total = counts[2]

        if (full && shown) {
            cell.solved = true
            if (marked + blank < total) cell.needed = true
            markAround(width, height, sol, x, y, MARKED)
            return 1
        }
        if (empty && shown) {
            cell.solved = true
            if (marked + blank < total) cell.needed = true
            markAround(width, height, sol, x, y, BLANK)
            return 1
        }
        if (shown) {
            if (marked == clue) {
                cell.solved = true
                if (total != marked + blank) cell.needed = true
                markAround(width, height, sol, x, y, BLANK)
            } else if (clue == total - blank) {
                cell.solved = true
                if (total != marked + blank) cell.needed = true
                markAround(width, height, sol, x, y, MARKED)
            } else if (total == marked + blank) {
                return -1
            } else {
                return 0
            }
            return 1
        } else if (total == marked + blank) {
            cell.solved = true
            return 1
        }
        return 0
    }

    /**
     * `count_around` from `mosaic.c:335-358`: the 3x3 block including
     * the centre. A cell with both value bits counts as blank.
     */
    private fun countAround(
        width: Int,
        height: Int,
        sol: Array<SolutionCell>,
        x: Int,
        y: Int,
        counts: IntArray,
    ) {
        var total = 0
        var blank = 0
        var marked = 0
        for (i in -1..1) {
            for (j in -1..1) {
                val xx = x + i
                val yy = y + j
                if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue
                total++
                val cell = sol[yy * width + xx].cell
                if (cell and BLANK != 0) blank++ else if (cell and MARKED != 0) marked++
            }
        }
        counts[0] = marked
        counts[1] = blank
        counts[2] = total
    }

    /** `mark_around` from `mosaic.c:406-422`: only an unmarked cell changes. */
    private fun markAround(
        width: Int,
        height: Int,
        sol: Array<SolutionCell>,
        x: Int,
        y: Int,
        mark: Int,
    ) {
        for (i in -1..1) {
            for (j in -1..1) {
                val xx = x + i
                val yy = y + j
                if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue
                val cell = sol[yy * width + xx]
                if (cell.cell == UNMARKED) cell.cell = mark
            }
        }
    }

    /**
     * `hide_clues` from `mosaic.c:616-681`. The first `solve_check` runs
     * with the generator's RNG and its verdict is ignored. Every cell
     * that is not needed is hidden at once; the aggressive path then
     * tries every needed cell in a shuffled order and restores it when
     * the deterministic solver fails.
     */
    private fun hideClues(
        width: Int,
        height: Int,
        desc: Array<DescCell>,
        aggressive: Boolean,
        random: RandomState,
    ) {
        val first = solveCheck(width, height, desc, random)

        val needed = ArrayList<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val cell = first.sol[index]
                if (cell.needed && aggressive) {
                    needed += index
                } else if (!cell.needed) {
                    desc[index].shown = false
                }
            }
        }

        if (aggressive) {
            // C copies the prepended list to an array, so the order is
            // the reverse of the scan before the shuffle.
            val order = IntArray(needed.size)
            for (i in needed.indices) order[i] = needed[needed.size - 1 - i]
            shuffle(order, random)
            for (index in order) {
                desc[index].shown = false
                if (!solveCheck(width, height, desc, null).valid) {
                    desc[index].shown = true
                }
            }
        }
    }

    /**
     * `shuffle` from `misc.c:314-324`: `n-1` draws with limits
     * `n, n-1, ..., 2`, and no draw for 0 or 1 elements.
     */
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

    /**
     * The compressed description from `mosaic.c:794-824`, including the
     * upstream `<=` defect: a run of 27 hidden cells stores `'{'`, and a
     * longer run emits `'{'` and loses one cell. Generated boards never
     * reach it, but the arithmetic is reproduced so a reachable input
     * matches C. See the owner decision in the port report.
     */
    private fun encode(width: Int, height: Int, desc: Array<DescCell>): String {
        val out = StringBuilder()
        var spaceCount = 'a'.code - 1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cell = desc[y * width + x]
                if (cell.shown) {
                    if (spaceCount >= 'a'.code) {
                        out.append(spaceCount.toChar())
                        spaceCount = 'a'.code - 1
                    }
                    out.append('0' + cell.clue)
                } else {
                    if (spaceCount <= 'z'.code) {
                        spaceCount++
                    } else {
                        out.append(spaceCount.toChar())
                        spaceCount = 'a'.code - 1
                    }
                }
            }
        }
        if (spaceCount >= 'a'.code) out.append(spaceCount.toChar())
        return out.toString()
    }

    /**
     * `solve_game_actual` from `mosaic.c:576-614`. It runs the same
     * `solve_cell` with no description, so the full and empty shortcuts
     * are off. The return is the solution cell values, or null when the
     * board is not fully solved.
     */
    fun solve(width: Int, height: Int, clue: IntArray, shown: BooleanArray): IntArray? {
        val n = width * height
        val sol = Array(n) { SolutionCell() }
        var solved = 0
        var madeProgress = true
        var error = false
        while (solved < n && madeProgress && !error) {
            madeProgress = false
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val curr = solveCellBody(
                        width,
                        height,
                        sol,
                        x,
                        y,
                        index,
                        shown[index],
                        clue[index],
                        false,
                        false,
                    )
                    if (curr < 0) {
                        error = true
                        break
                    }
                    if (curr > 0) madeProgress = true
                    solved += curr
                }
            }
        }
        if (solved != n) return null
        return IntArray(n) { sol[it].cell }
    }

    /**
     * The encoder for a synthetic sequence of shown and hidden cells, so
     * a unit test can pin the run arithmetic without a generated board.
     * A shown cell with clue 0 is not a realistic clue; the test passes
     * clues of 0..9 only to read the run lengths.
     */
    fun encodeForTest(shown: BooleanArray, clues: IntArray): String {
        val desc = Array(shown.size) {
            DescCell().apply {
                this.shown = shown[it]
                this.clue = clues[it]
            }
        }
        return encode(shown.size, 1, desc)
    }
}
