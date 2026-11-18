package name.boyle.chris.sgtpuzzles.engine

/**
 * The description generator and the deduction solver of upstream
 * `filling.c`.
 *
 * Generation draws the whole board, then removes clues while the
 * deduction solver still finds a solution. The solver is also the Solve
 * feature, so a change here appears in every description and in every
 * `SOLVE` record. It is a deduction solver, not a backtracking one: it
 * applies four techniques in a fixed order and restarts at the first
 * technique after any success.
 *
 * The RNG order is part of the contract. `make_board` shuffles the cell
 * list once per attempt, the four directions once per cell per pass, and
 * draws `random_upto(10)` only when a neighbour is a candidate. The
 * clue minimisation shuffles one index list and draws nothing else.
 *
 * The solver also writes its best working board when it stalls. C
 * returns that partial board and sets an error, and the midend ignores
 * the error, so the port keeps the partial board too.
 */
internal object FillingSolver {

    private const val EMPTY = 0

    private val DX = intArrayOf(-1, 1, 0, 0)
    private val DY = intArrayOf(0, 0, -1, 1)

    /**
     * The solver's answer. [solved] is C's `!nempty`. [board] is the
     * working copy: a stalled solve leaves empty cells in it, and C
     * still writes the move.
     */
    class Result(val board: IntArray, val solved: Boolean) {
        /** `'s'` followed by `board[i] + '0'` for every cell. */
        fun solutionString(): String {
            val out = StringBuilder(board.size + 1)
            out.append('s')
            for (value in board) out.append(('0'.code + value).toChar())
            return out.toString()
        }
    }

    /** `new_game_desc`: a board, a clue minimisation, then a run-length encoding. */
    fun newDesc(
        w: Int,
        h: Int,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): String {
        val sz = w * h
        val board = IntArray(sz)
        makeBoard(board, w, h, random, cancel)
        minimizeClueSet(board, w, h, random)

        val out = StringBuilder()
        var run = 0
        for (i in 0 until sz) {
            val value = board[i]
            // The generator caps a region at 9, so the alphabet is
            // `1`..`9` and `a`..`z`.
            check(value in 1..9 || value == EMPTY) { "generated value out of range: $value" }
            if (value == EMPTY) {
                ++run
            } else {
                encodeRun(out, run)
                run = 0
                out.append(('0'.code + value).toChar())
            }
        }
        encodeRun(out, run)
        return out.toString()
    }

    private fun encodeRun(out: StringBuilder, start: Int) {
        var run = start
        while (run > 26) {
            out.append('z')
            run -= 26
        }
        if (run != 0) out.append(('a'.code - 1 + run).toChar())
    }

    // ------------------------------------------------------------------
    // Board generation
    // ------------------------------------------------------------------

    private fun makeBoard(
        board: IntArray,
        w: Int,
        h: Int,
        random: RandomState,
        cancel: Cancellation,
    ) {
        val sz = w * h
        // `w == h == 2` needs a region of 3, which is above `max(w, h)`.
        val maxsize = minOf(maxOf(maxOf(w, h), 3), 9)
        val sentinel = sz + 1

        // The board holds a shuffled list of 0..sz-1 while it is built.
        for (i in 0 until sz) board[i] = i

        val dsf = Dsf.new(sz)
        val directions = IntArray(4)

        attempt@ while (true) {
            cancel.check()
            dsf.reinit()
            shuffle(board, random)

            var change: Boolean
            do {
                change = false
                for (i in 0 until sz) {
                    val square = dsf.canonify(board[i])
                    val size = dsf.size(square)
                    var merge = sentinel
                    var min = maxsize - size + 1
                    var error = false

                    // A fresh direction order for every cell, every
                    // pass. This is three RNG draws per cell per pass.
                    for (j in 0..3) directions[j] = j
                    shuffle(directions, random)

                    for (j in 0..3) {
                        val x = (board[i] % w) + DX[directions[j]]
                        val y = (board[i] / w) + DY[directions[j]]
                        if (x < 0 || x >= w || y < 0 || y >= h) continue

                        val neighbour = dsf.canonify(w * y + x)
                        if (square == neighbour) continue

                        val neighbourSize = dsf.size(neighbour)
                        if (size == neighbourSize) error = true

                        // The draw is short-circuited: it happens only
                        // when the neighbour is smaller than `min`.
                        if (neighbourSize < min && random.upto(10) != 0L) {
                            min = neighbourSize
                            merge = neighbour
                        }
                    }

                    if (!error) continue

                    // No workable neighbour: start a whole new attempt.
                    // The RNG stream continues from where it was.
                    if (merge == sentinel) continue@attempt

                    dsf.merge(square, merge)
                    change = true
                }
            } while (change)
            break@attempt
        }

        for (i in 0 until sz) board[i] = dsf.size(i)
        mergeOnes(board, w, h)
    }

    /**
     * `merge_ones`: join each isolated 1 to a neighbour when the merged
     * region stays within [maxsize] and does not clash with another
     * region of the new size. The C loop restores `board[i] = 1` in its
     * increment clause, so a failed direction is undone and a `break`
     * keeps the merge.
     */
    private fun mergeOnes(board: IntArray, w: Int, h: Int) {
        val sz = w * h
        val maxsize = minOf(maxOf(maxOf(w, h), 3), 9)
        var change: Boolean
        do {
            change = false
            for (i in 0 until sz) {
                if (board[i] != 1) continue
                var j = 0
                while (j < 4) {
                    val x = (i % w) + DX[j]
                    val y = (i / w) + DY[j]
                    if (x < 0 || x >= w || y < 0 || y >= h) {
                        board[i] = 1
                        ++j
                        continue
                    }
                    val ii = w * y + x
                    if (board[ii] == maxsize) {
                        board[i] = 1
                        ++j
                        continue
                    }

                    val oldsize = board[ii]
                    board[i] = oldsize
                    val newsize = regionSize(board, w, h, i)
                    if (newsize > maxsize) {
                        board[i] = 1
                        ++j
                        continue
                    }

                    val ok = markRegion(board, w, h, i, oldsize, newsize)
                    for (k in 0 until sz) {
                        if (board[k] == -1) board[k] = if (ok) newsize else oldsize
                    }
                    if (ok) break

                    board[i] = 1
                    ++j
                }
                if (j < 4) change = true
            }
        } while (change)
    }

    // ------------------------------------------------------------------
    // Small board helpers shared by generation and the solver
    // ------------------------------------------------------------------

    /**
     * `mark_region`: replace the 4-connected region of value [n] that
     * contains [i] with `-1`. Returns false as soon as a cell of value
     * [m] touches the region.
     */
    private fun markRegion(board: IntArray, w: Int, h: Int, i: Int, n: Int, m: Int): Boolean {
        board[i] = -1
        for (j in 0..3) {
            val x = (i % w) + DX[j]
            val y = (i / w) + DY[j]
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            val ii = w * y + x
            if (board[ii] == m) return false
            if (board[ii] != n) continue
            if (!markRegion(board, w, h, ii, n, m)) return false
        }
        return true
    }

    /** `region_size`: the size of the region at [i], with all markers restored. */
    private fun regionSize(board: IntArray, w: Int, h: Int, i: Int): Int {
        val sz = w * h
        if (board[i] == 0) return 0
        val copy = board[i]
        markRegion(board, w, h, i, board[i], sz + 1)
        var size = 0
        for (j in 0 until sz) {
            if (board[j] != -1) continue
            ++size
            board[j] = copy
        }
        return size
    }

    /** `make_dsf`: merge 4-neighbours with equal values, in index order. */
    internal fun makeDsf(existing: Dsf?, board: IntArray, w: Int, h: Int): Dsf {
        val sz = w * h
        val dsf = existing ?: Dsf.newMin(sz)
        if (existing != null) existing.reinit()
        for (i in 0 until sz) {
            for (j in 0..3) {
                val x = (i % w) + DX[j]
                val y = (i / w) + DY[j]
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val k = w * y + x
                if (board[i] == board[k]) dsf.merge(i, k)
            }
        }
        return dsf
    }

    // ------------------------------------------------------------------
    // Solver
    // ------------------------------------------------------------------

    /**
     * `struct solver_state`. `connected` holds cyclic singly linked
     * lists with the same partition as `dsf`, so a loop can visit a
     * whole region from any member.
     */
    private class SolverState(val w: Int, val h: Int) {
        val board: IntArray = IntArray(w * h)
        var dsf: Dsf = Dsf.new(w * h)
        val connected: IntArray = IntArray(w * h)
        var nempty: Int = 0
        val bm: IntArray = IntArray(w * h)
        val bmdsf: Dsf = Dsf.new(w * h)
        val bmminsize: IntArray = IntArray(w * h)
    }

    /**
     * `solver`: run the four techniques until none of them learns
     * anything. The result is the working board and `nempty == 0`.
     */
    fun solve(orig: IntArray, w: Int, h: Int): Result {
        val state = SolverState(w, h)
        orig.copyInto(state.board)
        initSolverState(state, w, h)

        do {
            if (learnBlockedExpansion(state, w, h)) continue
            if (learnExpandOrOne(state, w, h)) continue
            if (learnCriticalSquare(state, w, h)) continue
            if (learnBitmapDeductions(state, w, h)) continue
            break
        } while (state.nempty != 0)

        return Result(state.board, state.nempty == 0)
    }

    private fun initSolverState(s: SolverState, w: Int, h: Int) {
        val sz = w * h
        s.nempty = 0
        for (i in 0 until sz) s.connected[i] = i
        for (i in 0 until sz) {
            if (s.board[i] == EMPTY) {
                ++s.nempty
            } else {
                filledSquare(s, w, h, i)
            }
        }
    }

    /**
     * `merge`: merge the two classes and swap the two `connected` list
     * tails. The swap is what lets `learnBlockedExpansion` walk a merged
     * region, so it is not an optimization to drop.
     */
    private fun merge(dsf: Dsf, connected: IntArray, a0: Int, b0: Int) {
        val a = dsf.canonify(a0)
        val b = dsf.canonify(b0)
        if (a == b) return
        dsf.merge(a, b)
        val c = connected[a]
        connected[a] = connected[b]
        connected[b] = c
    }

    /** `expand`: write a value into an empty cell and merge equal neighbours. */
    private fun expand(s: SolverState, w: Int, h: Int, t: Int, f: Int) {
        check(s.board[t] == EMPTY) { "expand target is not empty" }
        check(s.board[f] != EMPTY) { "expand source is empty" }
        s.board[t] = s.board[f]
        for (j in 0..3) {
            val x = (t % w) + DX[j]
            val y = (t / w) + DY[j]
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            val idx = w * y + x
            if (s.board[idx] != s.board[t]) continue
            merge(s.dsf, s.connected, t, idx)
        }
        --s.nempty
    }

    /** `filled_square`: merge a filled cell with its equal neighbours. */
    private fun filledSquare(s: SolverState, w: Int, h: Int, i: Int) {
        for (j in 0..3) {
            val x = (i % w) + DX[j]
            val y = (i / w) + DY[j]
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            val idx = w * y + x
            if (s.board[i] == s.board[idx]) merge(s.dsf, s.connected, i, idx)
        }
    }

    /** `clear_count`: remove every temporary marker, restoring the board. */
    private fun clearCount(board: IntArray, sz: Int) {
        val sentinel = sz + 1
        for (i in 0 until sz) {
            if (board[i] >= 0) continue
            board[i] = if (board[i] == -sentinel) EMPTY else -board[i]
        }
    }

    /**
     * `flood_count`: count up to [c] cells reachable from [i] through
     * empty cells or cells of value [n], marking them on the way. The
     * count is capped, so the walk stops as soon as the region is known
     * to be big enough.
     */
    private fun floodCount(board: IntArray, w: Int, h: Int, i: Int, n: Int, c0: Int): Int {
        var c = c0
        if (board[i] == EMPTY) {
            board[i] = -(w * h + 1)
        } else if (board[i] == n) {
            board[i] = -board[i]
        } else {
            return c
        }
        if (--c == 0) return c
        for (k in 0..3) {
            val x = (i % w) + DX[k]
            val y = (i / w) + DY[k]
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            c = floodCount(board, w, h, w * y + x, n, c)
            if (c == 0) return c
        }
        return c
    }

    /**
     * `check_capacity`: false when the region at [i] cannot reach its
     * value. The caller places a `-SENTINEL` marker on the candidate
     * cell first, so a region cut off from it is detected.
     */
    private fun checkCapacity(board: IntArray, w: Int, h: Int, i: Int): Boolean {
        val n = floodCount(board, w, h, i, board[i], board[i])
        clearCount(board, w * h)
        return n == 0
    }

    /**
     * `expandsize`: the size the region at [i] would have if it were
     * extended to value [n], counting each neighbouring root once.
     */
    private fun expandsize(board: IntArray, dsf: Dsf, w: Int, h: Int, i: Int, n: Int): Int {
        val hits = IntArray(4)
        var nhits = 0
        var size = 1
        for (j in 0..3) {
            val x = (i % w) + DX[j]
            val y = (i / w) + DY[j]
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            val idx = w * y + x
            if (board[idx] != n) continue
            val root = dsf.canonify(idx)
            var m = 0
            while (m < nhits && root != hits[m]) ++m
            if (m < nhits) continue
            size += dsf.size(root)
            check(dsf.size(root) >= 1)
            hits[nhits++] = root
        }
        return size
    }

    /**
     * `learn_blocked_expansion`: a region that is not complete and has
     * exactly one square it can grow into must grow there.
     */
    private fun learnBlockedExpansion(s: SolverState, w: Int, h: Int): Boolean {
        val sz = w * h
        val sentinel = sz + 1
        var learn = false

        for (i in 0 until sz) {
            var exp = sentinel
            if (s.board[i] == EMPTY) continue
            var j = s.dsf.canonify(i)
            if (i != j) continue
            if (s.dsf.size(j) == s.board[j]) continue

            var skip = false
            do {
                for (k in 0..3) {
                    val x = (j % w) + DX[k]
                    val y = (j / w) + DY[k]
                    if (x < 0 || x >= w || y < 0 || y >= h) continue
                    val idx = w * y + x
                    if (s.board[idx] != EMPTY) continue
                    if (exp == idx) continue

                    val size = expandsize(s.board, s.dsf, w, h, idx, s.board[j])
                    if (size > s.board[j]) continue
                    if (exp != sentinel) {
                        // A second candidate: this region is not forced.
                        // C jumps out of both loops here.
                        skip = true
                        break
                    }
                    exp = idx
                }
                if (skip) break
                j = s.connected[j]
                check(s.board[i] == s.board[j])
            } while (j != i)

            if (skip) continue
            if (exp == sentinel) continue
            expand(s, w, h, exp, i)
            learn = true
        }
        return learn
    }

    /**
     * `learn_expand_or_one`: extend a region that cannot reach its size
     * without a square, or drop a 1 into a square that cannot take any
     * other value.
     */
    private fun learnExpandOrOne(s: SolverState, w: Int, h: Int): Boolean {
        val sz = w * h
        val sentinel = sz + 1
        var learn = false

        for (i in 0 until sz) {
            var one = true
            if (s.board[i] != EMPTY) continue

            var j = 0
            while (j < 4) {
                val x = (i % w) + DX[j]
                val y = (i / w) + DY[j]
                if (x < 0 || x >= w || y < 0 || y >= h) {
                    ++j
                    continue
                }
                val idx = w * y + x
                if (s.board[idx] == EMPTY) {
                    one = false
                    ++j
                    continue
                }
                if (one &&
                    (s.board[idx] == 1 ||
                        s.board[idx] >= expandsize(s.board, s.dsf, w, h, i, s.board[idx]))
                ) {
                    one = false
                }
                if (s.dsf.size(idx) == s.board[idx]) {
                    ++j
                    continue
                }

                check(s.board[i] == EMPTY) { "expand target is not empty" }
                s.board[i] = -sentinel
                if (checkCapacity(s.board, w, h, idx)) {
                    ++j
                    continue
                }
                check(s.board[i] == EMPTY) { "capacity check did not restore the board" }
                expand(s, w, h, i, idx)
                learn = true
                break
            }

            if (j == 4 && one) {
                check(s.board[i] == EMPTY) { "the one square is not empty" }
                s.board[i] = 1
                check(s.nempty > 0) { "nempty underflow" }
                --s.nempty
                learn = true
            }
        }
        return learn
    }

    /**
     * `learn_critical_square`: a square that a region must include,
     * because the region cannot reach its size without it.
     */
    private fun learnCriticalSquare(s: SolverState, w: Int, h: Int): Boolean {
        val sz = w * h
        val sentinel = sz + 1
        var learn = false

        for (i in 0 until sz) {
            if (s.board[i] == EMPTY) continue
            if (i != s.dsf.canonify(i)) continue
            val slack = s.board[i] - s.dsf.size(i)
            if (slack == 0) continue

            /*
             * C asserts `s->board[i] != 1` here. An overfull region of
             * 1s (two adjacent 1 clues, or two 1s the player filled)
             * makes `slack` negative. The shipped C code keeps
             * assertions (`app/src/main/jni/cmake/setup.cmake` removes
             * `-DNDEBUG` from every release flag), so C aborts and
             * returns no board. The port takes the guarded path
             * instead: no square is within a negative slack, so the
             * distance loop below ends at the head and every square is
             * skipped. The result is deterministic and the Solve menu
             * cannot crash the app on such a board. This is deviation
             * D-004.
             */

            for (j in 0 until sz) {
                if (s.board[j] != EMPTY) continue

                // Walk the region's linked list. C breaks on distance
                // and then treats `k == i` as "not within range", so a
                // square close to the head is skipped as well.
                var k = i
                val jx = j % w
                val jy = j / w
                do {
                    val kx = k % w
                    val ky = k / w
                    if (abs(kx - jx) + abs(ky - jy) <= slack) break
                    k = s.connected[k]
                } while (i != k)
                if (i == k) continue

                s.board[j] = -sentinel
                if (checkCapacity(s.board, w, h, i)) continue

                check(s.nempty > 0) { "nempty underflow" }
                --s.nempty
                s.board[j] = s.board[i]
                filledSquare(s, w, h, j)
                learn = true
            }
        }
        return learn
    }

    /**
     * `learn_bitmap_deductions`: build the set of possible values for
     * every empty square, then fill a square whose set has one value.
     * The three phases are: rule out brand-new regions that are too
     * small, reinstate values reachable by extending an existing
     * region, and fill the single-value squares.
     */
    private fun learnBitmapDeductions(s: SolverState, w: Int, h: Int): Boolean {
        val sz = w * h
        val bm = s.bm
        val dsf = s.bmdsf
        val minsize = s.bmminsize
        var learn = false

        // Bits 1..9 are possible in every square.
        for (i in 0 until sz) bm[i] = (1 shl 10) - (1 shl 1)

        // A filled square has no possibilities, and no neighbour can be
        // part of a new region of that value.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val n = s.board[i]
                if (n == EMPTY) continue
                bm[i] = 0
                if (x > 0) bm[i - 1] = bm[i - 1] and (1 shl n).inv()
                if (x + 1 < w) bm[i + 1] = bm[i + 1] and (1 shl n).inv()
                if (y > 0) bm[i - w] = bm[i - w] and (1 shl n).inv()
                if (y + 1 < h) bm[i + w] = bm[i + w] and (1 shl n).inv()
            }
        }

        // Rule out a value where its connected component is too small to
        // hold a brand-new region of that size.
        for (n in 1..9) {
            dsf.reinit()
            for (y in 0 until h) {
                for (x in 0 until w - 1) {
                    if (bm[y * w + x] and bm[y * w + x + 1] and (1 shl n) != 0) {
                        dsf.merge(y * w + x, y * w + x + 1)
                    }
                }
            }
            for (y in 0 until h - 1) {
                for (x in 0 until w) {
                    if (bm[y * w + x] and bm[(y + 1) * w + x] and (1 shl n) != 0) {
                        dsf.merge(y * w + x, (y + 1) * w + x)
                    }
                }
            }
            for (i in 0 until sz) {
                if (bm[i] and (1 shl n) != 0 && dsf.size(i) < n) {
                    bm[i] = bm[i] and (1 shl n).inv()
                }
            }
        }

        // Reinstate a value where an existing region of that value could
        // grow far enough to reach the square.
        for (n in 1..9) {
            for (i in 0 until sz) {
                minsize[i] = if (s.board[i] == n) s.dsf.size(i) else n + 1
            }
            for (j in 1 until n) {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (minsize[i] != j) continue
                        if (x > 0 && minsize[i - 1] > j + 1) minsize[i - 1] = j + 1
                        if (x + 1 < w && minsize[i + 1] > j + 1) minsize[i + 1] = j + 1
                        if (y > 0 && minsize[i - w] > j + 1) minsize[i - w] = j + 1
                        if (y + 1 < h && minsize[i + w] > j + 1) minsize[i + w] = j + 1
                    }
                }
            }
            for (i in 0 until sz) {
                if (minsize[i] <= n) bm[i] = bm[i] or (1 shl n)
            }
        }

        // A square with one possible value can be filled.
        for (i in 0 until sz) {
            if (bm[i] == 0 || bm[i] and (bm[i] - 1) != 0) continue
            var value = bm[i]
            var n = 0
            if (value shr 8 != 0) {
                value = value shr 8
                n += 8
            }
            if (value shr 4 != 0) {
                value = value shr 4
                n += 4
            }
            if (value shr 2 != 0) {
                value = value shr 2
                n += 2
            }
            if (value shr 1 != 0) {
                value = value shr 1
                n += 1
            }
            check(n in 1..9) { "bitmap value is out of range: $n" }
            check(bm[i] == 1 shl n) { "bitmap value is not a power of two" }

            if (s.board[i] == EMPTY) {
                s.board[i] = n
                filledSquare(s, w, h, i)
                check(s.nempty > 0) { "nempty underflow" }
                --s.nempty
                learn = true
            }
        }

        return learn
    }

    /** `minimize_clue_set`: remove whole regions, then single cells. */
    private fun minimizeClueSet(board: IntArray, w: Int, h: Int, random: RandomState) {
        val sz = w * h
        val shuf = IntArray(sz) { it }
        shuffle(shuf, random)

        val dsf = makeDsf(null, board, w, h)
        val next = IntArray(sz)
        for (i in 0 until sz) {
            val j = dsf.minimal(i)
            if (i == j) {
                next[i] = -1
            } else {
                // The minimal element always comes first, so `next[j]`
                // is already the head of a list when it is read.
                check(j < i) { "region head is not the first cell" }
                next[i] = next[j]
                next[j] = i
            }
        }

        for (i in 0 until sz) {
            val j = dsf.minimal(shuf[i])
            if (next[j] != -2) {
                val tmp = board[j]
                var k = j
                while (k >= 0) {
                    board[k] = EMPTY
                    k = next[k]
                }
                if (!solve(board, w, h).solved) {
                    k = j
                    while (k >= 0) {
                        board[k] = tmp
                        k = next[k]
                    }
                }
                next[j] = -2
            }
        }

        for (i in 0 until sz) {
            val tmp = board[shuf[i]]
            board[shuf[i]] = EMPTY
            if (!solve(board, w, h).solved) board[shuf[i]] = tmp
        }
    }

    /** `shuffle` from `misc.c`: `n-1` draws with limits `n, n-1, ..., 2`. */
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

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
