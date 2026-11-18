package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `divvy.c`, the random rectangle divider.
 *
 * The result is a [Dsf] over `w*h` cells. Cells of one simply connected
 * k-omino share a class, and every class has size `k`. The class roots
 * are observable: `solo` uses the canonical element as an omino id, so
 * the merge order matters as much as the partition.
 *
 * The algorithm is not a pure function of the rectangle and `k`. Every
 * attempt shuffles the cell order, and every main-loop iteration draws
 * one `random_upto` to pick the omino to extend. A failed attempt
 * returns null and [rectangle] starts over with a new shuffle. See
 * `work/specs/palisade.md` part A for the exact draw order.
 */
object Divvy {

    /** One `divvy_rectangle` result and the number of attempts it took. */
    data class Partition(val dsf: Dsf, val attempts: Int)

    /**
     * `divvy_rectangle`: retries [attempt] until it succeeds, with no
     * attempt cap. A failed attempt consumes RNG draws, so the retry
     * order is part of the contract.
     */
    fun rectangle(
        w: Int,
        h: Int,
        k: Int,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Dsf =
        rectangleWithAttempts(w, h, k, random, cancel).dsf

    /** [rectangle] plus the attempt count, for retry fixtures. */
    internal fun rectangleWithAttempts(
        w: Int,
        h: Int,
        k: Int,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Partition {
        var attempts = 0
        while (true) {
            cancel.check()
            attempts++
            val dsf = attempt(w, h, k, random) ?: continue
            return Partition(dsf, attempts)
        }
    }

    /**
     * `divvy_rectangle_attempt`: one attempt, or null when the breadth
     * first search cannot expand any omino. The caller owns the result.
     *
     * The C version asserts `wh == k*n` and rejects nothing at runtime.
     * This port rejects the same inputs instead, so a bad call cannot
     * return a wrong partition.
     */
    fun attempt(w: Int, h: Int, k: Int, random: RandomState): Dsf? {
        require(w >= 1) { "divvy width must be at least one: $w" }
        require(h >= 1) { "divvy height must be at least one: $h" }
        require(k >= 1) { "divvy region size must be at least one: $k" }
        /*
         * The C code allocates four `int`s per cell and does not guard
         * the size. Reject a board that cannot hold the tables, so a bad
         * call fails with a clear message instead of an allocation
         * error. The palisade caller caps the area as well.
         */
        require(w.toLong() * h <= Int.MAX_VALUE / 4) { "divvy area is too large: $w x $h" }

        val wh = w * h
        val n = wh / k
        require(wh % k == 0) { "divvy region size must divide the area: $k into $wh" }

        val order = IntArray(wh) { it }
        shuffle(order, random)

        val own = IntArray(wh) { -1 }
        val sizes = IntArray(n) { 1 }
        for (i in 0 until n) own[order[i]] = i

        val tmp = IntArray(2 * n)
        val queue = IntArray(n)
        val addable = IntArray(wh * 4)
        val removable = BooleanArray(wh)
        val incomplete = IntArray(n)

        while (true) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val yx = y * w + x
                    val curr = own[yx]

                    removable[yx] = when {
                        curr < 0 -> false
                        sizes[curr] == 1 -> true
                        else -> addremcommon(w, h, x, y, own, curr)
                    }

                    for (dir in 0 until 4) {
                        val dx = when (dir) {
                            0 -> -1
                            1 -> 1
                            else -> 0
                        }
                        val dy = when (dir) {
                            2 -> -1
                            3 -> 1
                            else -> 0
                        }
                        val sx = x + dx
                        val sy = y + dy
                        val syx = sy * w + sx

                        addable[yx * 4 + dir] = -1

                        if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue
                        if (own[syx] < 0) continue
                        if (own[syx] == own[yx]) continue
                        if (!addremcommon(w, h, x, y, own, own[syx])) continue

                        addable[yx * 4 + dir] = own[syx]
                    }
                }
            }

            var count = 0
            for (i in 0 until n) {
                if (sizes[i] < k) incomplete[count++] = i
            }
            if (count == 0) break
            var j = incomplete[random.upto(count.toLong()).toInt()]

            /*
             * The breadth first search needs two entries per omino.
             * `k == 1` never reaches this point, so `wh >= 2*n` holds
             * whenever an omino is still incomplete.
             */
            check(wh >= 2 * n) { "divvy search needs k >= 2" }
            tmp.fill(-1)
            var qhead = 0
            var qtail = 0
            queue[qtail++] = j
            tmp[2 * j] = -2
            tmp[2 * j + 1] = -2

            while (qhead < qtail) {
                j = queue[qhead]

                /*
                 * The search may have reached this omino by stealing a
                 * square from it. Remove that square for the adjacency
                 * tests; restore it before the next omino is examined.
                 */
                val tmpsq = tmp[2 * j + 1]
                if (tmpsq >= 0) {
                    check(own[tmpsq] == j) { "divvy stole a square from the wrong omino" }
                    own[tmpsq] = -3
                }

                /*
                 * First try to expand into an unclaimed square. A splice
                 * from inside a `for` loop is not available, so the scan
                 * records the found cell and exits through `break`.
                 */
                var found = -1
                scan@ for (idx in 0 until wh) {
                    if (own[order[idx]] != -1) continue@scan

                    /*
                     * A size-one omino that lost its only square has
                     * size zero and may take any unclaimed square.
                     */
                    if (sizes[j] == 1 && tmpsq >= 0) {
                        found = order[idx]
                        break@scan
                    }

                    for (dir in 0 until 4) {
                        if (addable[order[idx] * 4 + dir] != j) continue
                        /*
                         * The table is from the top of the loop. Recheck
                         * the hole test with the square now missing.
                         */
                        if (!addremcommon(w, h, order[idx] % w, order[idx] / w, own, j)) continue
                        found = order[idx]
                        break@scan
                    }
                }

                if (found >= 0) {
                    var cell = found

                    /*
                     * Restore the borrowed square before ownerships
                     * move. Then walk the chain that the search built:
                     * each stolen square goes to its parent omino, and
                     * the start omino ends up one square larger.
                     */
                    if (tmpsq >= 0) own[tmpsq] = j
                    while (true) {
                        own[cell] = j
                        if (tmp[2 * j] == -2) break
                        cell = tmp[2 * j + 1]
                        j = tmp[2 * j]
                    }
                    sizes[j]++
                    break
                }

                /*
                 * No unclaimed square is reachable. Steal from an
                 * unvisited omino and continue the search from there.
                 */
                for (idx in 0 until wh) {
                    val nj = own[order[idx]]
                    if (nj < 0 || tmp[2 * nj] != -1) continue
                    if (!removable[order[idx]]) continue

                    for (dir in 0 until 4) {
                        if (addable[order[idx] * 4 + dir] != j) continue
                        if (!addremcommon(w, h, order[idx] % w, order[idx] / w, own, j)) continue

                        check(qtail < n) { "divvy search queue overflow" }
                        queue[qtail++] = nj
                        tmp[2 * nj] = j
                        tmp[2 * nj + 1] = order[idx]
                        /*
                         * Stop at the first usable direction: the same
                         * omino must not enter the queue twice.
                         */
                        break
                    }
                }

                if (tmpsq >= 0) own[tmpsq] = j
                qhead++
            }

            if (qhead == qtail) return null
        }

        /*
         * Build the output forest as a star per omino. Each cell joins
         * the last row-major cell of its omino, which is what upstream
         * does, so the canonical elements match too.
         */
        val lastCell = IntArray(n)
        for (i in 0 until wh) {
            val owner = own[i]
            check(owner in 0 until n) { "divvy left a cell unclaimed" }
            lastCell[owner] = i
        }

        val retdsf = Dsf.new(wh)
        for (i in 0 until wh) retdsf.merge(i, lastCell[own[i]])

        /*
         * Verify the partition against an adjacency forest. Upstream
         * keeps this check as an assertion. A split omino would return
         * a partition that the rest of the engine cannot trust.
         */
        val checkDsf = Dsf.new(wh)
        for (y in 0 until h) {
            for (x in 0 until w - 1) {
                if (own[y * w + x] == own[y * w + x + 1]) checkDsf.merge(y * w + x, y * w + x + 1)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h - 1) {
                if (own[y * w + x] == own[(y + 1) * w + x]) checkDsf.merge(y * w + x, (y + 1) * w + x)
            }
        }
        for (i in 0 until wh) {
            val root = retdsf.canonify(i)
            check(checkDsf.equivalent(root, i)) { "divvy split an omino" }
        }

        return retdsf
    }

    /**
     * `addremcommon` from `divvy.c:215-250`: walk the eight neighbours
     * in cyclic order and count value boundaries. A count of two means
     * [value] touches the cell in one contiguous arc, so adding or
     * removing the cell keeps the omino simply connected.
     *
     * The cyclic order is W, NW, N, NE, E, SE, S, SW. It is not the
     * 4-neighbour order, and the outside marker `-1` takes part in the
     * count so rim cells need no separate branch.
     */
    private fun addremcommon(w: Int, h: Int, x: Int, y: Int, own: IntArray, value: Int): Boolean {
        val neighbours = IntArray(8)
        for (dir in 0 until 8) {
            val dx = if ((dir and 3) == 2) 0 else if (dir > 2 && dir < 6) 1 else -1
            val dy = if ((dir and 3) == 0) 0 else if (dir < 4) -1 else 1
            val sx = x + dx
            val sy = y + dy
            neighbours[dir] = if (sx < 0 || sx >= w || sy < 0 || sy >= h) {
                -1
            } else {
                own[sy * w + sx]
            }
        }

        if (neighbours[0] != value && neighbours[2] != value &&
            neighbours[4] != value && neighbours[6] != value
        ) {
            return false
        }

        var count = 0
        for (dir in 0 until 8) {
            val next = (dir + 1) and 7
            if ((neighbours[dir] == value) != (neighbours[next] == value)) count++
        }
        return count == 2
    }

    /**
     * `shuffle` from `misc.c:314-324`: `n-1` draws with limits
     * `n, n-1, ..., 2`. The draw happens even when the swap is a no-op.
     * The palisade generator uses the same helper for its winnow order.
     */
    internal fun shuffle(array: IntArray, random: RandomState) {
        shuffle(array, array.size, random)
    }

    /**
     * The same `shuffle`, but over the first [count] entries only. The C
     * caller passes the element count separately when the array is
     * larger than the list it shuffles, as `laydomino.c` does.
     */
    internal fun shuffle(array: IntArray, count: Int, random: RandomState) {
        require(count in 0..array.size) { "shuffle count is out of range: $count" }
        var i = count
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
            }
        }
    }
}
