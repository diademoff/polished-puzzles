package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * The description generator and the rotation primitive from upstream
 * `twiddle.c`.
 *
 * The generator starts from a solved grid and applies a fixed number of
 * random rotations. Each attempt draws the region origin and the
 * direction in C order, even when the retry rule rejects the attempt. A
 * description that comes out complete is shuffled again with the grid
 * left as it was, and the move count is not drawn a second time.
 */
internal object TwiddleGeneration {

    /**
     * `new_game_desc`.
     *
     * The random stream is the upstream generator. The `interactive`
     * and `aux` arguments of C have no effect, so there is no AUXINFO.
     */
    fun newDesc(
        width: Int,
        height: Int,
        n: Int,
        rowsOnly: Boolean,
        orientable: Boolean,
        moveTarget: Int,
        random: RandomState,
    ): String {
        val wh = width * height
        val grid = IntArray(wh) {
            ((if (rowsOnly) it / width else it) + 1) * 4
        }

        var totalMoves = moveTarget
        if (totalMoves == 0) {
            totalMoves = wh * n * n * 2 + random.upto(2).toInt()
        }

        val rw = width - n + 1
        val rh = height - n + 1
        do {
            // C allocates and zeroes this array inside the outer loop, so
            // every retry starts with zeroed counters.
            val prevMoves = IntArray(rw * rh)

            repeat(totalMoves) {
                var x: Int
                var y: Int
                var r: Int
                var oldTotal: Int
                var newTotal: Int
                do {
                    x = random.upto(rw.toLong()).toInt()
                    y = random.upto(rh.toLong()).toInt()
                    r = 2 * random.upto(2).toInt() - 1

                    oldTotal = prevMoves[y * rw + x]
                    newTotal = oldTotal + r
                    // With a whole-board region there is no way to avoid
                    // undoing or repeating a move, so the test is skipped.
                } while (
                    (width != n || height != n) &&
                    (abs(newTotal) < abs(oldTotal) || abs(newTotal) > 2)
                )

                rotate(grid, width, n, orientable, x, y, r)

                prevMoves[y * rw + x] += r

                // Any region that overlaps this one is safe for a new move.
                for (dy in -n + 1..n - 1) {
                    if (y + dy < 0 || y + dy >= rh) continue
                    for (dx in -n + 1..n - 1) {
                        if (x + dx < 0 || x + dx >= rw) continue
                        if (dx == 0 && dy == 0) continue
                        prevMoves[(y + dy) * rw + (x + dx)] = 0
                    }
                }
            }
        } while (gridComplete(grid, orientable))

        val out = StringBuilder()
        for (i in 0 until wh) {
            out.append(grid[i] / 4)
            if (orientable) {
                out.append("uldr"[grid[i] and 3])
            } else if (i < wh - 1) {
                out.append(',')
            }
        }
        return out.toString()
    }

    /**
     * `do_rotate`. The region origin is the top-left cell. The four
     * coset cells are visited in a quarter of the region, so the rotation
     * is in place and needs no copy of the region.
     */
    fun rotate(
        grid: IntArray,
        width: Int,
        n: Int,
        orientable: Boolean,
        x: Int,
        y: Int,
        dir: Int,
    ) {
        val quarter = dir and 3
        if (quarter == 0) return

        val base = y * width + x
        for (i in 0 until (n + 1) / 2) {
            for (j in 0 until n / 2) {
                val p = IntArray(4)
                p[0] = base + j * width + i
                p[1] = base + i * width + (n - j - 1)
                p[2] = base + (n - j - 1) * width + (n - i - 1)
                p[3] = base + (n - i - 1) * width + j

                val g = IntArray(4) { grid[p[it]] }

                for (k in 0 until 4) {
                    var v = g[(k + quarter) and 3]
                    if (orientable) {
                        // `((v & 3) + dir) mod 4`, written as C writes it.
                        v = v xor (((v + quarter) xor v) and 3)
                    }
                    grid[p[k]] = v
                }
            }
        }

        if (orientable && (n and 1) != 0) {
            val centre = base + n / 2 * (width + 1)
            var v = grid[centre]
            v = v xor (((v + quarter) xor v) and 3)
            grid[centre] = v
        }
    }

    /**
     * `grid_complete`: the grid is non-decreasing and, when orientation
     * matters, every cell points up. Equal neighbours are allowed, which
     * is what makes `rowsonly` descriptions completable.
     */
    fun gridComplete(grid: IntArray, orientable: Boolean): Boolean {
        for (i in 1 until grid.size) {
            if (grid[i] < grid[i - 1]) return false
        }
        if (orientable) {
            for (value in grid) {
                if ((value and 3) != 0) return false
            }
        }
        return true
    }
}
