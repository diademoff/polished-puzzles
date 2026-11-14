package name.boyle.chris.sgtpuzzles.engine

/**
 * The solver and the description generator of upstream `flood.c`.
 *
 * The greedy solver here defines two parts of the contract:
 *
 * 1. the move limit in `DESC`, which is the solver's own move count plus
 *    the leniency;
 * 2. the `S<colour>,...` move string that the midend stores in `SOLVE`.
 *
 * Both depend on the exact choice rule, so the port follows the C code
 * literally: the recursion depth, the two-queue breadth-first search,
 * and the three-level tie-break are unchanged. There is no randomness in
 * the solver, and every index loop keeps the C order.
 *
 * The C comments name the fill point `FILLX` and `FILLY`; it is always
 * the top-left cell.
 */
internal object FloodSolver {

    /** `RECURSION_DEPTH` from `flood.c`. */
    private const val RECURSION_DEPTH = 3

    /** `FILLX` and `FILLY`: the controlled region is anchored at (0,0). */
    private const val FILL_X = 0
    private const val FILL_Y = 0

    /** The working arrays of `struct solver_scratch`. */
    private class Scratch(w: Int, h: Int) {
        val wh: Int = w * h
        val queue0 = IntArray(wh)
        val queue1 = IntArray(wh)
        val dist = IntArray(wh)
        val grid = IntArray(wh)
        val grid2 = IntArray(wh)
    }

    /**
     * `search`: label every cell with the number of colour changes needed
     * to reach it from the fill point, and return the largest distance,
     * the number of cells at that distance, and the size of the
     * distance-zero region.
     *
     * The two queues hold the current and the next distance layer. A
     * cell is queued for the next layer when it joins the current layer
     * through a colour change. The literal port keeps the C queue
     * lengths and the switch order.
     */
    private fun search(
        w: Int,
        h: Int,
        grid: IntArray,
        x0: Int,
        y0: Int,
        scratch: Scratch,
        out: IntArray,
    ) {
        val wh = w * h
        for (i in 0 until wh) scratch.dist[i] = -1
        scratch.queue0[0] = y0 * w + x0
        scratch.queue1[0] = y0 * w + x0
        scratch.dist[y0 * w + x0] = 0

        var currdist = 0
        var qcurr = 0
        var qtail = 0
        var qhead = 1
        var qnext = 1
        var remaining = wh - 1
        var control = 0

        while (true) {
            if (qtail == qhead) {
                // Switch queues.
                if (currdist == 0) control = qhead
                currdist++
                qcurr = qcurr xor 1
                qhead = qnext
                qtail = 0
                qnext = 0
            } else if (remaining == 0 && qnext == 0) {
                break
            } else {
                val curr = if (qcurr == 0) scratch.queue0 else scratch.queue1
                val pos = curr[qtail++]
                val y = pos / w
                val x = pos % w
                for (dir in 0 until 4) {
                    val y1 = y + when (dir) {
                        1 -> 1
                        3 -> -1
                        else -> 0
                    }
                    val x1 = x + when (dir) {
                        0 -> 1
                        2 -> -1
                        else -> 0
                    }
                    if (x1 in 0 until w && y1 in 0 until h) {
                        val pos1 = y1 * w + x1
                        if (scratch.dist[pos1] == -1 &&
                            ((grid[pos1] == grid[pos] &&
                                scratch.dist[pos] == currdist) ||
                                (grid[pos1] != grid[pos] &&
                                    scratch.dist[pos] == currdist - 1))
                        ) {
                            val next = if (qcurr == 0) scratch.queue1 else scratch.queue0
                            next[qnext++] = pos1
                            curr[qhead++] = pos1
                            scratch.dist[pos1] = currdist
                            remaining--
                        }
                    }
                }
            }
        }

        out[0] = currdist
        out[1] = qhead
        out[2] = control
        if (currdist == 0) out[2] = qhead
    }

    /**
     * `fill`: recolour the 4-connected component of the fill point.
     * C asserts that the new colour differs; callers keep that true.
     */
    private fun fill(w: Int, h: Int, grid: IntArray, x0: Int, y0: Int, newcolour: Int, queue: IntArray) {
        val oldcolour = grid[y0 * w + x0]
        grid[y0 * w + x0] = newcolour
        queue[0] = y0 * w + x0
        var qtail = 0
        var qhead = 1

        while (qtail < qhead) {
            val pos = queue[qtail++]
            val y = pos / w
            val x = pos % w
            for (dir in 0 until 4) {
                val y1 = y + when (dir) {
                    1 -> 1
                    3 -> -1
                    else -> 0
                }
                val x1 = x + when (dir) {
                    0 -> 1
                    2 -> -1
                    else -> 0
                }
                if (x1 in 0 until w && y1 in 0 until h) {
                    val pos1 = y1 * w + x1
                    if (grid[pos1] == oldcolour) {
                        grid[pos1] = newcolour
                        queue[qhead++] = pos1
                    }
                }
            }
        }
    }

    /** `completed`: every cell equals cell 0. */
    private fun completed(w: Int, h: Int, grid: IntArray): Boolean {
        val wh = w * h
        for (i in 1 until wh) {
            if (grid[i] != grid[0]) return false
        }
        return true
    }

    /**
     * `choosemove_recurse`: try every colour, recurse up to
     * `RECURSION_DEPTH`, and keep the move with the smallest maximum
     * distance. [out] holds `dist`, `number`, and `control`.
     */
    private fun chooseMoveRecurse(
        w: Int,
        h: Int,
        grid: IntArray,
        maxmove: Int,
        scratch: Scratch,
        depth: Int,
        out: IntArray,
    ): Int {
        val wh = w * h
        val tmpgrid = IntArray(wh)

        var bestdist = wh + 1
        var bestnumber = 0
        var bestcontrol = 0
        var bestmove = -1
        val child = IntArray(3)

        for (move in 0 until maxmove) {
            if (grid[FILL_Y * w + FILL_X] == move) continue
            grid.copyInto(tmpgrid, 0, 0, wh)
            fill(w, h, tmpgrid, FILL_X, FILL_Y, move, scratch.queue0)
            if (completed(w, h, tmpgrid)) {
                // A winning move is immediately the best. Record the
                // depth so that a higher level prefers the sooner win.
                out[0] = -1
                out[1] = depth
                out[2] = wh
                return move
            }
            if (depth < RECURSION_DEPTH - 1) {
                chooseMoveRecurse(w, h, tmpgrid, maxmove, scratch, depth + 1, child)
            } else {
                search(w, h, tmpgrid, FILL_X, FILL_Y, scratch, child)
            }
            val dist = child[0]
            val number = child[1]
            val control = child[2]
            if (dist < bestdist ||
                (dist == bestdist &&
                    (number < bestnumber ||
                        (number == bestnumber && control > bestcontrol)))
            ) {
                bestdist = dist
                bestnumber = number
                bestcontrol = control
                bestmove = move
            }
        }

        out[0] = bestdist
        out[1] = bestnumber
        out[2] = bestcontrol
        return bestmove
    }

    private fun chooseMove(w: Int, h: Int, grid: IntArray, maxmove: Int, scratch: Scratch): Int {
        val out = IntArray(3)
        return chooseMoveRecurse(w, h, grid, maxmove, scratch, 0, out)
    }

    /**
     * `new_game_desc`: draw `w*h` colours in row-major order, retry while
     * the grid is uniform, count the solver moves, add the leniency, and
     * write the grid and the limit.
     */
    fun newDesc(
        width: Int,
        height: Int,
        colours: Int,
        leniency: Int,
        random: RandomState,
    ): String {
        val w = width
        val h = height
        val wh = w * h
        val scratch = Scratch(w, h)

        do {
            for (i in 0 until wh) scratch.grid[i] = random.upto(colours.toLong()).toInt()
        } while (completed(w, h, scratch.grid))

        scratch.grid.copyInto(scratch.grid2, 0, 0, wh)
        var moves = 0
        while (!completed(w, h, scratch.grid2)) {
            val move = chooseMove(w, h, scratch.grid2, colours, scratch)
            fill(w, h, scratch.grid2, FILL_X, FILL_Y, move, scratch.queue0)
            moves++
        }
        moves += leniency

        val out = StringBuilder(wh + 8)
        for (i in 0 until wh) out.append(colourDigit(scratch.grid[i]))
        out.append(',').append(moves)
        return out.toString()
    }

    /**
     * `solve_game`: the greedy path from [grid], with [colours] from the
     * state and not from the parameters. The result is the `S` move
     * string.
     *
     * A stored solution must not change the grid, so the caller applies
     * the string as an `S` move. A uniform grid is already complete for
     * `completed()`, so the loop does not run and the result is empty;
     * C returns an uninitialized buffer in that case. The `S-1` token
     * would need a choice of -1, which no valid state reaches.
     */
    fun solve(w: Int, h: Int, grid: IntArray, colours: Int): String {
        val wh = w * h
        val scratch = Scratch(w, h)
        grid.copyInto(scratch.grid2, 0, 0, wh)

        val moves = ArrayList<Int>(wh)
        while (!completed(w, h, scratch.grid2)) {
            val move = chooseMove(w, h, scratch.grid2, colours, scratch)
            fill(w, h, scratch.grid2, FILL_X, FILL_Y, move, scratch.queue0)
            moves.add(move)
        }

        val out = StringBuilder()
        for (i in moves.indices) {
            out.append(if (i == 0) 'S' else ',').append(moves[i])
        }
        return out.toString()
    }

    /** One grid character: `(colour > 9 ? 'A' : '0') + colour`. */
    private fun colourDigit(colour: Int): Char =
        if (colour > 9) ('A' + (colour - 10)) else ('0' + colour)
}
