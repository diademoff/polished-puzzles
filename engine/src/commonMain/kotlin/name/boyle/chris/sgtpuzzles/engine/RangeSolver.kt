package name.boyle.chris.sgtpuzzles.engine

/**
 * The solver and the generator of upstream `range.c`.
 *
 * The solver is the game's deduction engine, its generator's acceptance
 * test, and the answer to Solve and Hint. It runs four techniques in a
 * fixed order and repeats until a pass adds nothing:
 *
 * 1. run-length arithmetic on every clue,
 * 2. a white neighbour for every black cell,
 * 3. a white cut vertex of the non-black graph,
 * 4. recursion: assume a colour, run the techniques, accept a full
 *    board.
 *
 * Two C hazards are defined here instead of copied:
 *
 * - `solver_reasoning_connectedness` writes its DFS root outside the
 *   arrays when the whole board is black (`range.c:465-471`). The port
 *   returns no moves for that board.
 * - A recursive branch appends its moves to the shared buffer
 *   (`range.c:605-638`) and C repeats the outer pass because the buffer
 *   grew although the player's state did not change. The buffer is `n`
 *   entries, so C loops until it writes past the array. The port grows
 *   the list and repeats a pass only while the player's state changed,
 *   so a stalled board terminates after one recursion pass. The
 *   returned moves for a C-defined board are unchanged, because there
 *   the buffer grows only when the player's state grows.
 *
 * `solve` copies its input, as `solve_internal` duplicates the state, so
 * a caller's grid is never changed.
 */
internal object RangeSolver {

    /** `enum {BLACK = -2, WHITE, EMPTY}` from `range.c:285`. */
    const val BLACK = -2
    const val WHITE = -1
    const val EMPTY = 0

    /** `enum {DIFF_NOT_TOO_BIG, ...}` from `range.c:309-314`. */
    const val DIFF_NOT_TOO_BIG = 0
    const val DIFF_ADJACENCY = 1
    const val DIFF_CONNECTEDNESS = 2
    const val DIFF_RECURSION = 3

    /** `M_BLACK` and `M_WHITE` from `range.c:297`. */
    const val M_BLACK = 0
    const val M_WHITE = 1

    /** `struct move`: one cell and its colour. */
    class Move(val r: Int, val c: Int, val colour: Int)

    /** A generated description and the number of attempts it took. */
    data class Generated(val desc: String, val attempts: Int)

    private val DR = intArrayOf(1, 0, -1, 0)
    private val DC = intArrayOf(0, 1, 0, -1)

    /** `MASK(n)`: one bit per cell value, `range.c:391`. */
    private fun mask(value: Int): Int = 1 shl (value + 2)

    private const val CELL_MASK = 7 // BLACK, WHITE, and EMPTY

    /**
     * `solve_internal`: copy [grid], find the clues once, and run the
     * techniques at [difficulty]. The returned list is growable and in
     * C's move order. The input array is not changed.
     */
    fun solve(
        grid: IntArray,
        width: Int,
        height: Int,
        difficulty: Int,
    ): MutableList<Move> {
        val work = grid.copyOf()
        val clues = findClues(work, width, height)
        val moves = ArrayList<Move>()
        doSolve(work, clues, width, height, moves, difficulty)
        return moves
    }

    /**
     * `do_solve`: one pass over the four techniques, repeated while the
     * pass added a move to [state]. C compares the buffer end instead;
     * a recursive branch appends to the buffer without changing [state],
     * and that is the undefined case described above, so the state is
     * the progress signal here.
     */
    private fun doSolve(
        state: IntArray,
        clues: List<Int>,
        width: Int,
        height: Int,
        moves: MutableList<Move>,
        difficulty: Int,
    ) {
        while (true) {
            val oldSize = moves.size
            var progress = 0
            for (i in 0..DIFF_RECURSION) {
                if (i > difficulty) break
                // Only recurse if all else fails, as `range.c:382`.
                if (i == DIFF_RECURSION && moves.size > oldSize) continue
                progress += when (i) {
                    DIFF_NOT_TOO_BIG -> notTooBig(state, clues, width, height, moves)
                    DIFF_ADJACENCY -> adjacency(state, width, height, moves)
                    DIFF_CONNECTEDNESS -> connectedness(state, width, height, moves)
                    else -> {
                        recursion(state, clues, width, height, moves, difficulty)
                        0
                    }
                }
            }
            if (progress == 0) return
        }
    }

    /** `solver_makemove`: append and paint a cell that is still empty. */
    private fun makeMove(
        r: Int,
        c: Int,
        colour: Int,
        state: IntArray,
        width: Int,
        height: Int,
        moves: MutableList<Move>,
    ): Boolean {
        if (outOfBounds(r, c, width, height)) return false
        val cell = r * width + c
        if (state[cell] != EMPTY) return false
        moves += Move(r, c, colour)
        state[cell] = if (colour == M_BLACK) BLACK else WHITE
        return true
    }

    /** `runlength` from `range.c:393-411`. */
    private fun runLength(
        r0: Int,
        c0: Int,
        dr: Int,
        dc: Int,
        state: IntArray,
        width: Int,
        height: Int,
        colourMask: Int,
    ): Int {
        var r = r0
        var c = c0
        var size = 0
        while (true) {
            if (outOfBounds(r, c, width, height)) break
            val cell = state[r * width + c]
            if (cell > 0) {
                if ((colourMask and CELL_MASK.inv()) == 0) break
            } else if ((mask(cell) and colourMask) == 0) {
                break
            }
            size++
            r += dr
            c += dc
        }
        return size
    }

    /** `solver_reasoning_adjacency` from `range.c:426-440`. */
    private fun adjacency(
        state: IntArray,
        width: Int,
        height: Int,
        moves: MutableList<Move>,
    ): Int {
        var added = 0
        for (r in 0 until height) {
            for (c in 0 until width) {
                if (state[r * width + c] != BLACK) continue
                for (i in 0..3) {
                    if (makeMove(r + DR[i], c + DC[i], M_WHITE, state, width, height, moves)) {
                        added++
                    }
                }
            }
        }
        return added
    }

    /**
     * `solver_reasoning_connectedness` from `range.c:449-518`.
     *
     * C finds the first non-black cell as the DFS root and writes it
     * without a bounds test. An all-black board leaves no root; the
     * port then adds no moves. That board is reachable from the UI
     * because a move may paint over a clue, so the guard is live.
     */
    private fun connectedness(
        state: IntArray,
        width: Int,
        height: Int,
        moves: MutableList<Move>,
    ): Int {
        val n = width * height
        var root = 0
        while (root < n && state[root] == BLACK) root++
        if (root == n) return 0

        val parentR = IntArray(n) { NOT_VISITED }
        val parentC = IntArray(n)
        val depth = IntArray(n) { -n }
        parentR[root] = root / width
        parentC[root] = root % width
        depth[root] = 0

        val added = IntArray(1)
        dfsBiconnectVisit(
            root / width,
            root % width,
            state,
            parentR,
            parentC,
            depth,
            moves,
            added,
            width,
            height,
        )
        return added[0]
    }

    private const val NOT_VISITED = -1

    private fun dfsBiconnectVisit(
        r: Int,
        c: Int,
        state: IntArray,
        parentR: IntArray,
        parentC: IntArray,
        depth: IntArray,
        moves: MutableList<Move>,
        added: IntArray,
        width: Int,
        height: Int,
    ): Int {
        val i = r * width + c
        val myDepth = depth[i]
        var lowpoint = myDepth
        var children = 0

        for (j in 0..3) {
            val rr = r + DR[j]
            val cc = c + DC[j]
            if (outOfBounds(rr, cc, width, height)) continue
            val cell = rr * width + cc
            if (state[cell] == BLACK) continue

            if (parentR[cell] == NOT_VISITED) {
                parentR[cell] = r
                parentC[cell] = c
                depth[cell] = myDepth + 1
                val childLowpoint = dfsBiconnectVisit(
                    rr,
                    cc,
                    state,
                    parentR,
                    parentC,
                    depth,
                    moves,
                    added,
                    width,
                    height,
                )
                if (childLowpoint >= myDepth && myDepth > 0) {
                    if (makeMove(r, c, M_WHITE, state, width, height, moves)) added[0]++
                }
                lowpoint = minOf(lowpoint, childLowpoint)
                children++
            } else if (rr != parentR[i] || cc != parentC[i]) {
                lowpoint = minOf(lowpoint, depth[cell])
            }
        }

        if (myDepth == 0 && children >= 2) {
            if (makeMove(r, c, M_WHITE, state, width, height, moves)) added[0]++
        }
        return lowpoint
    }

    /**
     * `solver_reasoning_not_too_big` from `range.c:520-603`.
     *
     * `runlengths` is `[RUN_WHITE, RUN_EMPTY, RUN_BEYOND, RUN_SPACE]`
     * per direction, and `RUN_SPACE` accumulates the other three.
     */
    private fun notTooBig(
        state: IntArray,
        clues: List<Int>,
        width: Int,
        height: Int,
        moves: MutableList<Move>,
    ): Int {
        val runMasks = intArrayOf(
            (mask(BLACK) or mask(EMPTY)).inv(),
            mask(EMPTY),
            (mask(BLACK) or mask(EMPTY)).inv(),
            mask(BLACK).inv(),
        )
        val runWhite = 0
        val runEmpty = 1
        val runBeyond = 2
        val runSpace = 3
        val runLengths = Array(4) { IntArray(4) }

        var added = 0
        for (clueIndex in clues) {
            val row = clueIndex / width
            val col = clueIndex % width
            val clue = state[clueIndex]

            for (j in 0..3) {
                var r = row + DR[j]
                var c = col + DC[j]
                runLengths[runSpace][j] = 0
                for (k in 0..runSpace) {
                    val l = runLength(
                        r,
                        c,
                        DR[j],
                        DC[j],
                        state,
                        width,
                        height,
                        runMasks[k],
                    )
                    if (k < runSpace) {
                        runLengths[k][j] = l
                        r += DR[j] * l
                        c += DC[j] * l
                    }
                    runLengths[runSpace][j] += l
                }
            }

            var whites = 1
            for (j in 0..3) whites += runLengths[runWhite][j]

            for (j in 0..3) {
                val delta = 1 + runLengths[runWhite][j]
                val r = row + delta * DR[j]
                val c = col + delta * DC[j]

                if (whites == clue) {
                    if (makeMove(r, c, M_BLACK, state, width, height, moves)) added++
                    continue
                }

                if (runLengths[runEmpty][j] == 1 &&
                    whites + runLengths[runEmpty][j] + runLengths[runBeyond][j] > clue
                ) {
                    if (makeMove(r, c, M_BLACK, state, width, height, moves)) added++
                    continue
                }

                if (whites + runLengths[runEmpty][j] + runLengths[runBeyond][j] > clue) {
                    runLengths[runSpace][j] =
                        runLengths[runWhite][j] + runLengths[runEmpty][j] - 1
                    if (runLengths[runEmpty][j] == 1) {
                        if (makeMove(r, c, M_BLACK, state, width, height, moves)) added++
                    }
                }
            }

            var space = 1
            for (j in 0..3) space += runLengths[runSpace][j]
            for (j in 0..3) {
                var r = row + DR[j]
                var c = col + DC[j]
                var k = space - runLengths[runSpace][j]
                if (k >= clue) continue
                while (k < clue) {
                    if (makeMove(r, c, M_WHITE, state, width, height, moves)) added++
                    k++
                    r += DR[j]
                    c += DC[j]
                }
            }
        }
        return added
    }

    /**
     * `solver_reasoning_recursion` from `range.c:605-638`.
     *
     * A branch keeps the moves its techniques find only when it fills
     * the board. C keeps the moves of a rejected branch in the shared
     * buffer too (`range.c:625-631`); that is the undefined case: the
     * outer pass then repeats forever because the buffer grew although
     * the player's state did not, so C writes past its `n`-entry buffer.
     * The port rolls a rejected branch back, which keeps C's moves for
     * every board where C stays inside the buffer.
     *
     * The `recursive_result == NULL` arm of C is dead: no reasoning
     * returns NULL, so `do_solve` never does.
     */
    private fun recursion(
        state: IntArray,
        clues: List<Int>,
        width: Int,
        height: Int,
        moves: MutableList<Move>,
        difficulty: Int,
    ) {
        val n = width * height
        for (cell in 0 until n) {
            if (state[cell] != EMPTY) continue
            val r = cell / width
            val c = cell % width
            for (colour in M_BLACK..M_WHITE) {
                val mark = moves.size
                val branch = state.copyOf()
                branch[cell] = if (colour == M_BLACK) BLACK else WHITE
                doSolve(branch, clues, width, height, moves, difficulty)
                var i = 0
                while (i < n && branch[i] != EMPTY) i++
                if (i == n) return
                while (moves.size > mark) moves.removeAt(moves.size - 1)
            }
        }
    }

    /** `find_clues`: the indices of the clue cells, in scan order. */
    private fun findClues(state: IntArray, width: Int, height: Int): List<Int> {
        val clues = ArrayList<Int>()
        for (r in 0 until height) {
            for (c in 0 until width) {
                val i = r * width + c
                if (state[i] > 0) clues += i
            }
        }
        return clues
    }

    private fun outOfBounds(r: Int, c: Int, width: Int, height: Int): Boolean =
        r < 0 || r >= height || c < 0 || c >= width

    // ------------------------------------------------------------------
    // Generation, `range.c:697-892` and `range.c:1039-1080`.
    // ------------------------------------------------------------------

    /**
     * `new_game_desc`: one shuffle for the black squares and one for the
     * clue winnow per attempt, and another attempt when the winnow
     * rejects the board. [Generated.attempts] is the number of attempts,
     * which the generator tests use to pin the retry path.
     */
    fun generate(width: Int, height: Int, random: RandomState): Generated {
        val n = width * height
        val grid = IntArray(n)
        val shuffle = IntArray(n) { it }
        var attempts = 0

        while (true) {
            attempts++
            shuffle(shuffle, random)
            chooseBlackSquares(grid, width, height, shuffle)
            computeClues(grid, width, height)
            shuffle(shuffle, random)
            if (stripClues(grid, width, height, shuffle) >= 0) break
        }

        return Generated(encodeDescription(n, grid), attempts)
    }

    /** `newdesc_choose_black_squares` from `range.c:739-773`. */
    private fun chooseBlackSquares(
        grid: IntArray,
        width: Int,
        height: Int,
        shuffle: IntArray,
    ) {
        val n = width * height
        grid.fill(WHITE)
        val anyWhiteCell = shuffle[n - 1]
        var blackCells = 0

        for (k in 0 until n / 3) {
            val i = shuffle[k]
            val r = i / width
            val c = i % width

            var hasBlackNeighbour = false
            for (j in 0..3) {
                val rr = r + DR[j]
                val cc = c + DC[j]
                if (outOfBounds(rr, cc, width, height)) continue
                if (grid[rr * width + cc] == BLACK) {
                    hasBlackNeighbour = true
                    break
                }
            }
            if (hasBlackNeighbour) continue

            grid[i] = BLACK
            blackCells++

            if (countWhiteComponent(grid, anyWhiteCell, width, height) + blackCells < n) {
                grid[i] = WHITE
                blackCells--
            }
        }
    }

    /**
     * `dfs_count_white`: the size of the white component that contains
     * [cell]. The recursion marks visited whites empty, as C does, and
     * the caller restores them.
     */
    private fun countWhiteComponent(
        grid: IntArray,
        cell: Int,
        width: Int,
        height: Int,
    ): Int {
        val count = countWhiteRec(grid, cell / width, cell % width, width, height)
        for (i in grid.indices) if (grid[i] == EMPTY) grid[i] = WHITE
        return count
    }

    private fun countWhiteRec(
        grid: IntArray,
        r: Int,
        c: Int,
        width: Int,
        height: Int,
    ): Int {
        if (outOfBounds(r, c, width, height)) return 0
        val cell = r * width + c
        if (grid[cell] != WHITE) return 0
        grid[cell] = EMPTY
        return 1 +
            countWhiteRec(grid, r, c + 1, width, height) +
            countWhiteRec(grid, r, c - 1, width, height) +
            countWhiteRec(grid, r + 1, c, width, height) +
            countWhiteRec(grid, r - 1, c, width, height)
    }

    /** `newdesc_compute_clues`: add the horizontal and vertical runs. */
    private fun computeClues(grid: IntArray, width: Int, height: Int) {
        for (r in 0 until height) {
            var runSize = 0
            for (c in 0..width) {
                if (c == width || grid[r * width + c] == BLACK) {
                    for (cc in c - runSize until c) grid[r * width + cc] += runSize
                    runSize = 0
                } else {
                    runSize++
                }
            }
        }

        for (c in 0 until width) {
            var runSize = 0
            for (r in 0..height) {
                if (r == height || grid[r * width + c] == BLACK) {
                    for (rr in r - runSize until r) grid[rr * width + c] += runSize
                    runSize = 0
                } else {
                    runSize++
                }
            }
        }
    }

    /**
     * `newdesc_strip_clues` from `range.c:805-892`. It partitions the
     * shuffle into three groups, removes the clues that face a black
     * cell, then tries to remove rotationally symmetric clue pairs while
     * the solver still fills the board. A negative result means the
     * generator must retry.
     */
    private fun stripClues(grid: IntArray, width: Int, height: Int, shuffle: IntArray): Int {
        val n = width * height
        var cluesRemoved: Int
        var k = 0
        var left = 0
        var right = n

        while (true) {
            while (k < right && grid[shuffle[k]] == BLACK) {
                right--
                swap(shuffle, right, k)
            }
            if (k >= right) break
            if (grid[rotate(shuffle[k], n)] == BLACK) {
                swap(shuffle, k, left)
                left++
            }
            k++
        }

        for (i in 0 until left) grid[shuffle[i]] = EMPTY
        for (i in right until n) grid[shuffle[i]] = EMPTY

        cluesRemoved = left + (n - right)

        if (solve(grid, width, height, DIFF_RECURSION - 1).size < cluesRemoved) {
            return -1
        }

        for (i in left until right) {
            val cell = shuffle[i]
            val rotated = rotate(cell, n)
            val clue = grid[cell]
            val clueRotated = grid[rotated]
            if (clue == BLACK) continue
            grid[cell] = EMPTY
            grid[rotated] = EMPTY
            cluesRemoved += 2 - if (cell == rotated) 1 else 0
            if (solve(grid, width, height, DIFF_RECURSION - 1).size != cluesRemoved) {
                grid[cell] = clue
                grid[rotated] = clueRotated
                cluesRemoved -= 2 - if (cell == rotated) 1 else 0
            }
        }

        return cluesRemoved
    }

    private fun rotate(i: Int, n: Int): Int = n - 1 - i

    /** `shuffle` from `misc.c`: `n-1` draws with limits `n, n-1, ..., 2`. */
    private fun shuffle(array: IntArray, random: RandomState) {
        var i = array.size
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) swap(array, i, j)
        }
    }

    private fun swap(array: IntArray, a: Int, b: Int) {
        val tmp = array[a]
        array[a] = array[b]
        array[b] = tmp
    }

    /**
     * `newdesc_encode_game_description` from `range.c:1039-1080`.
     * A run of empty cells is `a`..`z` per 26, a clue is a decimal
     * number, and `_` separates two adjacent clues.
     */
    private fun encodeDescription(area: Int, grid: IntArray): String {
        val out = StringBuilder()
        var run = 0
        for (i in 0..area) {
            val n = if (i < area) grid[i] else -1
            if (n == 0) {
                run++
                continue
            }
            if (run > 0) {
                while (run > 0) {
                    var c = 'a' - 1 + run
                    if (run > 26) c = 'z'
                    out.append(c)
                    run -= c - ('a' - 1)
                }
            } else if (out.isNotEmpty() && n > 0) {
                out.append('_')
            }
            if (n > 0) out.append(n)
            run = 0
        }
        return out.toString()
    }
}
