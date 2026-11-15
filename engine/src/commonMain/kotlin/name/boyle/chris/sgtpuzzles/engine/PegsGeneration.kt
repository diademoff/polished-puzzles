package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Board generation for upstream `pegs.c`, section "Beginning of code to
 * generate random Peg Solitaire boards".
 *
 * The Cross and Octagon shapes are pure functions of the size, except
 * for the Octagon hole, which draws from [RandomState]. The Random board
 * is a reverse-move filler: it starts with one peg and repeatedly picks
 * a random reverse move from a `tree234` until no affordable move is
 * left, then retries from scratch unless the shape touches all four
 * edges.
 *
 * The tree contents and the RNG call order are part of the description
 * contract. A different comparator, a different deduplication rule, a
 * missed cost correction, or a retry that reseeds changes every Random
 * and Octagon `DESC`.
 */
internal object PegsGeneration {

    /** The number of generator attempts and the description they produced. */
    class Result(val desc: String, val attempts: Int)

    /**
     * `new_game_desc` without the encoding: one character per cell,
     * `P` for a peg, `H` for a hole, and `O` for an obstacle.
     */
    fun generate(width: Int, height: Int, type: Pegs.Type, random: RandomState): Result {
        val grid = ByteArray(width * height)
        var attempts = 0
        when (type) {
            Pegs.Type.RANDOM -> {
                while (true) {
                    attempts++
                    generateRandom(grid, width, height, random)
                    if (extremes(grid, width, height) == 15) break
                }
            }

            Pegs.Type.CROSS, Pegs.Type.OCTAGON -> {
                attempts++
                generateShape(grid, width, height, type)
                if (type == Pegs.Type.OCTAGON) generateOctagonHole(grid, width, random)
            }
        }
        return Result(encode(grid), attempts)
    }

    /** The Cross and Octagon shape loops of `new_game_desc`. */
    private fun generateShape(grid: ByteArray, width: Int, height: Int, type: Pegs.Type) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cx = abs(x - width / 2)
                val cy = abs(y - height / 2)
                val v = when (type) {
                    Pegs.Type.CROSS -> when {
                        cx == 0 && cy == 0 -> Pegs.GRID_HOLE
                        cx > 1 && cy > 1 -> Pegs.GRID_OBST
                        else -> Pegs.GRID_PEG
                    }

                    Pegs.Type.OCTAGON ->
                        if (cx + cy > 1 + max(width, height) / 2) {
                            Pegs.GRID_OBST
                        } else {
                            Pegs.GRID_PEG
                        }

                    Pegs.Type.RANDOM -> error("the random board has its own generator")
                }
                grid[y * width + x] = v.toByte()
            }
        }
    }

    /**
     * The Octagon hole. The offsets are the literal `3` values from C:
     * the board size is restricted to 7x7 by `validate_params`, so the
     * centre is always (3,3).
     *
     * Six or seven `random_upto` calls in total: one class, then three
     * calls for a corner, and two for the other two classes.
     */
    private fun generateOctagonHole(grid: ByteArray, width: Int, random: RandomState) {
        when (random.upto(3).toInt()) {
            0 -> {
                var dx = random.upto(2).toInt() * 2 - 1
                var dy = random.upto(2).toInt() * 2 - 1
                if (random.upto(2) != 0L) dy *= 3 else dx *= 3
                grid[(3 + dy) * width + (3 + dx)] = Pegs.GRID_HOLE.toByte()
            }

            1 -> {
                var dx = 2 * (random.upto(2).toInt() * 2 - 1)
                val dy: Int
                if (random.upto(2) != 0L) {
                    dy = 0
                } else {
                    dy = dx
                    dx = 0
                }
                grid[(3 + dy) * width + (3 + dx)] = Pegs.GRID_HOLE.toByte()
            }

            else -> {
                var dx = random.upto(2).toInt() * 2 - 1
                val dy: Int
                if (random.upto(2) != 0L) {
                    dy = 0
                } else {
                    dy = dx
                    dx = 0
                }
                grid[(3 + dy) * width + (3 + dx)] = Pegs.GRID_HOLE.toByte()
            }
        }
    }

    /**
     * `pegs_generate`: retry until the shape touches all four edges.
     * Every retry continues the same RNG stream; do not reseed.
     */
    private fun generateRandom(grid: ByteArray, width: Int, height: Int, random: RandomState) {
        grid.fill(Pegs.GRID_OBST.toByte())
        grid[(height / 2) * width + (width / 2)] = Pegs.GRID_PEG.toByte()
        generateMoves(grid, width, height, random)
    }

    /** One bit per edge, set when any cell on that edge is not an obstacle. */
    private fun extremes(grid: ByteArray, width: Int, height: Int): Int {
        var extremes = 0
        for (y in 0 until height) {
            if (grid[y * width] != Pegs.GRID_OBST.toByte()) extremes = extremes or 1
            if (grid[y * width + width - 1] != Pegs.GRID_OBST.toByte()) extremes = extremes or 2
        }
        for (x in 0 until width) {
            if (grid[x] != Pegs.GRID_OBST.toByte()) extremes = extremes or 4
            if (grid[(height - 1) * width + x] != Pegs.GRID_OBST.toByte()) extremes = extremes or 8
        }
        return extremes
    }

    /**
     * `struct move`. `x,y` is the start of the move during generation
     * (the end during normal play) and `dx,dy` is its direction, with
     * absolute value one. `cost` is the number of obstacles in the
     * middle and end cells.
     */
    private class Move(
        val x: Int,
        val y: Int,
        val dx: Int,
        val dy: Int,
        var cost: Int,
    )

    /** `movecmp`: the `bymove` key order `(y, x, dy, dx)`. */
    private val byMoveOrder = compareBy<Move>({ it.y }, { it.x }, { it.dy }, { it.dx })

    /** `movecmpcost`: the `bycost` key order `(cost, y, x, dy, dx)`. */
    private val byCostOrder = compareBy<Move>(
        { it.cost },
        { it.y },
        { it.x },
        { it.dy },
        { it.dx },
    )

    /**
     * `update_moves`: examine the twelve moves that pass through
     * `(x,y)`, add the possible ones, correct a wrong cost, and delete
     * the impossible ones.
     *
     * A move is possible when the start is a peg and the middle and end
     * are not pegs. Both trees hold the same node, so a delete from one
     * must be followed by a delete from the other.
     */
    private fun updateMoves(
        grid: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        byMove: Tree234<Move>,
        byCost: Tree234<Move>,
    ) {
        for (dir in 0..3) {
            val dx = if (dir and 1 != 0) 0 else dir - 1
            val dy = if (dir and 1 != 0) dir - 2 else 0

            for (pos in 0..2) {
                val mx = x - pos * dx
                val my = y - pos * dy

                if (mx < 0 || mx >= width || my < 0 || my >= height) continue
                if (mx + 2 * dx < 0 || mx + 2 * dx >= width ||
                    my + 2 * dy < 0 || my + 2 * dy >= height
                ) {
                    continue
                }

                val v1 = grid[my * width + mx].toInt()
                val v2 = grid[(my + dy) * width + mx + dx].toInt()
                val v3 = grid[(my + 2 * dy) * width + mx + 2 * dx].toInt()

                val probe = Move(mx, my, dx, dy, 0)
                if (v1 == Pegs.GRID_PEG && v2 != Pegs.GRID_PEG && v3 != Pegs.GRID_PEG) {
                    probe.cost = (if (v2 == Pegs.GRID_OBST) 1 else 0) +
                        (if (v3 == Pegs.GRID_OBST) 1 else 0)

                    var existing = byMove.find(probe)
                    if (existing != null && existing.cost != probe.cost) {
                        // The move is listed with the wrong cost. Remove
                        // the old node from both trees.
                        byMove.delete(existing)
                        byCost.delete(existing)
                        existing = null
                    }
                    if (existing == null) {
                        val added = byMove.add(probe)
                        check(added === probe) { "bymove comparator is not a key" }
                        byCost.add(probe)
                    }
                } else {
                    // The move is impossible. Remove it if it is stored.
                    val removed = byMove.delete(probe)
                    if (removed != null) byCost.delete(removed)
                }
            }
        }
    }

    /**
     * `pegs_genmoves`: fill the board with reverse moves.
     *
     * The loop picks the smallest affordable cost, then one move of that
     * cost with one `random_upto` call, applies the reverse move, and
     * refreshes the moved cells. `maxcost` drops from two to one after
     * half of the cells have been filled, with C integer division.
     */
    private fun generateMoves(
        grid: ByteArray,
        width: Int,
        height: Int,
        random: RandomState,
    ) {
        val byMove = Tree234.sorted(byMoveOrder)
        val byCost = Tree234.sorted(byCostOrder)

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (grid[y * width + x].toInt() == Pegs.GRID_PEG) {
                    updateMoves(grid, width, height, x, y, byMove, byCost)
                }
            }
        }

        var nmoves = 0
        while (true) {
            // The probe sorts after every real move of its cost, so the
            // LT search returns the last move with that cost and its
            // index is one less than the count.
            val probe = Move(0, height + 1, 0, 0, 0)
            val maxCost = if (nmoves < width * height / 2) 2 else 1
            var chosen: Move? = null
            var limit = -1

            for (cost in 0..maxCost) {
                probe.cost = cost
                val found = byCost.findRelPos(probe, relation = Relation.LT)
                limit = found?.second ?: -1
                chosen = found?.first
                if (chosen != null) break
            }
            if (chosen == null) break

            val index = random.upto((limit + 1).toLong()).toInt()
            val move = checkNotNull(byCost.index(index)) { "selected move is missing" }

            grid[move.y * width + move.x] = Pegs.GRID_HOLE.toByte()
            grid[(move.y + move.dy) * width + move.x + move.dx] = Pegs.GRID_PEG.toByte()
            grid[(move.y + 2 * move.dy) * width + move.x + 2 * move.dx] =
                Pegs.GRID_PEG.toByte()

            for (i in 0..2) {
                updateMoves(
                    grid,
                    width,
                    height,
                    move.x + i * move.dx,
                    move.y + i * move.dy,
                    byMove,
                    byCost,
                )
            }
            nmoves++
        }

        // C deletes every node from both trees before it frees them.
        while (true) {
            val node = byMove.deletePos(0) ?: break
            byCost.delete(node)
        }
        check(byCost.count == 0) { "the cost tree still has nodes" }
    }

    /** `new_game_desc` encoding without the terminator. */
    private fun encode(grid: ByteArray): String {
        val out = CharArray(grid.size)
        for (i in grid.indices) {
            out[i] = when (grid[i].toInt()) {
                Pegs.GRID_PEG -> 'P'
                Pegs.GRID_HOLE -> 'H'
                else -> 'O'
            }
        }
        return String(out)
    }
}
