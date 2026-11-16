package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `laydomino.c`, the random 2x1 domino layout.
 *
 * The result has one entry per square: `grid[i] = j` means that `j` is
 * the other end of the domino on square `i`. A square with
 * `grid[i] == i` is a singleton, so an odd area leaves exactly one
 * square uncovered and an even area leaves none.
 *
 * The layout is a pure function of `(w, h, random state)`. It shuffles
 * the candidate placements once, then shuffles the in-grid neighbours
 * of every breadth-first pop. There is no retry: the consumers
 * (`magnets`, `dominosa`) retry their own generators. See
 * `work/specs/magnets.md` part A for the phase-by-phase contract.
 */
object LayDomino {

    /**
     * `domino_layout`: allocates the scratch arrays, runs
     * [layoutPrealloc], and returns a fresh `w*h` grid.
     */
    fun layout(w: Int, h: Int, random: RandomState): IntArray {
        val wh = checkedArea(w, h)
        val grid = IntArray(wh)
        val grid2 = IntArray(wh)
        val list = IntArray(2 * wh)
        layoutPrealloc(w, h, random, grid, grid2, list)
        return grid
    }

    /**
     * [layout] plus the number of singleton repair passes. The C code
     * has no counter, so this is a test-only helper; the repair passes
     * are implicit in the encoded generator state.
     */
    internal fun layoutWithRepairs(w: Int, h: Int, random: RandomState): Pair<IntArray, Int> {
        val wh = checkedArea(w, h)
        val grid = IntArray(wh)
        val grid2 = IntArray(wh)
        val list = IntArray(2 * wh)
        val repairs = prealloc(w, h, random, grid, grid2, list)
        return grid to repairs
    }

    /**
     * `domino_layout_prealloc`: writes the grid into caller-owned
     * arrays. [grid] and [grid2] need at least `w*h` entries, and
     * [list] needs at least `2*w*h` entries.
     *
     * The C version has no size checks and writes out of bounds when a
     * caller is short. This port rejects a short buffer instead, so a
     * bad call cannot corrupt memory. The two game callers always pass
     * the documented sizes.
     */
    fun layoutPrealloc(
        w: Int,
        h: Int,
        random: RandomState,
        grid: IntArray,
        grid2: IntArray,
        list: IntArray,
    ) {
        val wh = checkedArea(w, h)
        require(grid.size >= wh) { "laydomino grid needs $wh entries, not ${grid.size}" }
        require(grid2.size >= wh) { "laydomino grid2 needs $wh entries, not ${grid2.size}" }
        require(list.size >= 2 * wh) { "laydomino list needs ${2 * wh} entries, not ${list.size}" }
        prealloc(w, h, random, grid, grid2, list)
    }

    /**
     * The port guard. The C library trusts both dimensions; the games
     * never pass a value below one. The `2*w*h` candidate list is an
     * `int` count in C, so the area is capped where that product still
     * fits.
     */
    private fun checkedArea(w: Int, h: Int): Int {
        require(w >= 1) { "laydomino width must be at least one: $w" }
        require(h >= 1) { "laydomino height must be at least one: $h" }
        require(w.toLong() * h <= Int.MAX_VALUE / 2) {
            "laydomino area is too large: $w x $h"
        }
        return w * h
    }

    private fun prealloc(
        w: Int,
        h: Int,
        random: RandomState,
        grid: IntArray,
        grid2: IntArray,
        list: IntArray,
    ): Int {
        val wh = w * h

        /* Every square starts as a singleton. */
        for (i in 0 until wh) grid[i] = i

        /*
         * The candidate placements: `2*i` is a vertical domino with its
         * top on `i`, `2*i+1` a horizontal one with its left half on
         * `i`. The order matters because the shuffle decides the greedy
         * placement and the greedy placement decides the layout.
         */
        var k = 0
        for (j in 0 until h - 1) {
            for (i in 0 until w) list[k++] = 2 * (j * w + i)
        }
        for (j in 0 until h) {
            for (i in 0 until w - 1) list[k++] = 2 * (j * w + i) + 1
        }
        check(k == 2 * wh - h - w) { "laydomino candidate count is wrong: $k" }

        Divvy.shuffle(list, k, random)

        /* Place every candidate whose two squares are still singletons. */
        for (i in 0 until k) {
            val horiz = list[i] % 2
            val xy = list[i] / 2
            val xy2 = xy + if (horiz != 0) 1 else w
            if (grid[xy] == xy && grid[xy2] == xy2) {
                grid[xy] = xy2
                grid[xy2] = xy
            }
        }

        /*
         * Pair the remaining singletons. Each pass starts at the last
         * singleton in index order, finds another singleton by breadth
         * first search, and re-lays the dominoes along the parent trail.
         */
        val d = IntArray(4)
        var repairs = 0
        while (true) {
            var singletons = 0
            var start = 0
            for (j in 0 until wh) {
                if (grid[j] == j) {
                    singletons++
                    start = j
                }
            }
            if (singletons == wh % 2) break
            repairs++

            for (j in 0 until wh) grid2[j] = -1
            grid2[start] = 0

            var done = 0
            var todo = 1
            list[0] = start
            var i = start
            while (done < todo) {
                i = list[done]
                done++

                val x = i % w
                val y = i / w
                var nd = 0
                if (x > 0) d[nd++] = i - 1
                if (x + 1 < w) d[nd++] = i + 1
                if (y > 0) d[nd++] = i - w
                if (y + 1 < h) d[nd++] = i + w
                Divvy.shuffle(d, nd, random)

                /*
                 * The far end of a domino stores its distance from the
                 * start. The near end stores the square we came from, so
                 * the trail can be followed back.
                 */
                var j = 0
                while (j < nd) {
                    k = d[j]
                    if (grid[k] == k) {
                        grid2[k] = i
                        break
                    }
                    val m = grid[k]
                    if (grid2[m] < 0 || grid2[m] > grid2[i] + 1) {
                        grid2[m] = grid2[i] + 1
                        grid2[k] = i
                        check(todo < wh) { "laydomino search queue overflow" }
                        list[todo] = m
                        todo++
                    }
                    j++
                }

                if (j < nd) {
                    i = k
                    break
                }
                i = -1
            }

            check(i >= 0) { "laydomino search found no singleton" }

            /* Follow the trail back and shift every domino by one cell. */
            while (true) {
                val j = grid2[i]
                check(j in 0 until wh) { "laydomino search parent is out of range: $j" }
                k = grid[j]
                grid[i] = j
                grid[j] = i
                if (j == k) break
                i = k
            }
        }
        return repairs
    }
}
