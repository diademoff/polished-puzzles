package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * The description generator from upstream `flip.c`.
 *
 * The description is two hex bitmaps joined by a comma: the `wh` by `wh`
 * click matrix and the `wh`-bit grid of lit squares. The CROSSES matrix
 * needs no random draws. The RANDOM matrix is a greedy search over three
 * views of one candidate set, stored in `tree234`; every ordering,
 * delete, mutate, and insert is copied as written, because the matrix
 * looks valid even when the order is wrong.
 *
 * The grid draws one `random_upto(2)` per row. An all-zero grid is
 * rejected and redrawn, so the retry consumes another `wh` draws.
 */
internal object FlipGeneration {

    private const val HEX = "0123456789abcdef"

    /**
     * `new_game_desc` with a plain random state.
     */
    fun newDesc(width: Int, height: Int, randomMatrix: Boolean, random: RandomState): String =
        newDesc(width, height, randomMatrix) { random.upto(it) }

    /**
     * The same generator with a pluggable draw function, so a test can
     * count the `random_upto` calls. The production path passes the
     * upstream generator.
     */
    fun newDesc(
        width: Int,
        height: Int,
        randomMatrix: Boolean,
        upto: (Long) -> Long,
    ): String {
        val wh = width * height
        val matrix = if (randomMatrix) {
            randomMatrix(width, height, upto)
        } else {
            crossesMatrix(width, height)
        }
        val grid = grid(matrix, wh, upto)
        return encodeBitmap(matrix, wh * wh) + "," + encodeBitmap(grid, wh)
    }

    /** `case CROSSES`: the cell itself and its four orthogonal neighbours. */
    fun crossesMatrix(width: Int, height: Int): ByteArray {
        val wh = width * height
        val matrix = ByteArray(wh * wh)
        for (i in 0 until wh) {
            val ix = i % width
            val iy = i / width
            for (j in 0 until wh) {
                val jx = j % width
                val jy = j / width
                if (abs(jx - ix) + abs(jy - iy) <= 1) matrix[i * wh + j] = 1
            }
        }
        return matrix
    }

    /** `case RANDOM`: the greedy matrix search, including the row retry. */
    private fun randomMatrix(width: Int, height: Int, upto: (Long) -> Long): ByteArray {
        val wh = width * height
        val matrix = ByteArray(wh * wh)

        while (true) {
            val pick = Tree234.sorted(PICK_ORDER)
            val cov = Tree234.sorted(COVERAGE_ORDER)
            val osize = Tree234.sorted(OMINO_SIZE_ORDER)

            matrix.fill(0)
            for (i in 0 until wh) matrix[i * wh + i] = 1

            for (i in 0 until wh) {
                val ix = i % width
                val iy = i / width
                addNeighbours(pick, width, height, ix, iy, ix, iy, matrix)
                addNeighbours(cov, width, height, ix, iy, ix, iy, matrix)
                addNeighbours(osize, width, height, ix, iy, ix, iy, matrix)
            }

            var limit = 4 * wh - 2 * (width + height)
            while (limit-- > 0) {
                val first = pick.index(0) ?: error("the pick tree is empty")

                /*
                 * The sentinel keeps the picked coverage and omino size
                 * and puts every coordinate above every real value. The
                 * greatest element below it is the last member of the
                 * (coverage, ominosize) group, so `k` is its index.
                 */
                val sentinel = Sq(wh, wh, wh, wh, first.coverage, first.ominosize)
                val groupEnd = pick.findRelPos(sentinel, null, Relation.LT)
                    ?: error("the pick group is empty")
                val k = upto((groupEnd.second + 1).toLong()).toInt()

                val sq = pick.deletePos(k) ?: error("pick index $k is empty")
                cov.delete(sq)
                osize.delete(sq)

                matrix[(sq.cy * width + sq.cx) * wh + (sq.y * width + sq.x)] = 1

                /*
                 * Coverage repair: every candidate that points at this
                 * output square gains one. Delete from all three trees
                 * before the field changes.
                 */
                val covSentinel = Sq(-1, -1, sq.x, sq.y, sq.coverage, -1)
                while (true) {
                    val other = cov.findRel(covSentinel, null, Relation.GT) ?: break
                    if (other.coverage != sq.coverage ||
                        other.x != sq.x ||
                        other.y != sq.y
                    ) {
                        break
                    }
                    pick.delete(other)
                    cov.delete(other)
                    osize.delete(other)
                    other.coverage++
                    pick.add(other)
                    cov.add(other)
                    osize.add(other)
                }

                /*
                 * Omino repair: every candidate of this click square
                 * grows by one output square.
                 */
                val osizeSentinel = Sq(sq.cx, sq.cy, -1, -1, -1, sq.ominosize)
                while (true) {
                    val other = osize.findRel(osizeSentinel, null, Relation.GT) ?: break
                    if (other.ominosize != sq.ominosize ||
                        other.cx != sq.cx ||
                        other.cy != sq.cy
                    ) {
                        break
                    }
                    pick.delete(other)
                    cov.delete(other)
                    osize.delete(other)
                    other.ominosize++
                    pick.add(other)
                    cov.add(other)
                    osize.add(other)
                }

                addNeighbours(pick, width, height, sq.cx, sq.cy, sq.x, sq.y, matrix)
                addNeighbours(cov, width, height, sq.cx, sq.cy, sq.x, sq.y, matrix)
                addNeighbours(osize, width, height, sq.cx, sq.cy, sq.x, sq.y, matrix)
            }

            /*
             * Two identical click rows make the puzzle ambiguous. Throw
             * the whole matrix away and continue the same random stream.
             */
            var duplicateRows = false
            for (i in 0 until wh) {
                var j = 0
                while (j < wh) {
                    if (i != j && sameRow(matrix, i * wh, j * wh, wh)) break
                    j++
                }
                if (j < wh) {
                    duplicateRows = true
                    break
                }
            }
            if (!duplicateRows) break
        }

        return matrix
    }

    /**
     * `addsq`: the candidate is in range, within one step of the click
     * in both axes, and not already set in the matrix.
     */
    private fun addSq(
        tree: Tree234<Sq>,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        x: Int,
        y: Int,
        matrix: ByteArray,
    ) {
        val wh = width * height
        if (x < 0 || x >= width || y < 0 || y >= height) return
        if (abs(x - cx) > 1 || abs(y - cy) > 1) return
        if (matrix[(cy * width + cx) * wh + y * width + x].toInt() != 0) return

        val sq = Sq(cx, cy, x, y, 0, 0)
        val output = y * width + x
        val clickRow = (cy * width + cx) * wh
        for (i in 0 until wh) {
            if (matrix[i * wh + output].toInt() != 0) sq.coverage++
            if (matrix[clickRow + i].toInt() != 0) sq.ominosize++
        }

        // `add234` returns the existing element on an equal key; the new
        // candidate is then dropped.
        tree.add(sq)
    }

    private fun addNeighbours(
        tree: Tree234<Sq>,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        x: Int,
        y: Int,
        matrix: ByteArray,
    ) {
        addSq(tree, width, height, cx, cy, x - 1, y, matrix)
        addSq(tree, width, height, cx, cy, x + 1, y, matrix)
        addSq(tree, width, height, cx, cy, x, y - 1, matrix)
        addSq(tree, width, height, cx, cy, x, y + 1, matrix)
    }

    /** The random initial lights, retried while the grid is all zero. */
    private fun grid(matrix: ByteArray, wh: Int, upto: (Long) -> Long): ByteArray {
        val grid = ByteArray(wh)
        while (true) {
            grid.fill(0)
            for (i in 0 until wh) {
                if (upto(2) != 0L) {
                    for (j in 0 until wh) {
                        grid[j] = (grid[j].toInt() xor matrix[i * wh + j].toInt()).toByte()
                    }
                }
            }
            if (grid.any { it.toInt() != 0 }) break
        }
        return grid
    }

    /** `encode_bitmap`: four cells per lowercase hex digit, MSB first. */
    fun encodeBitmap(bitmap: ByteArray, length: Int): String {
        val digits = (length + 3) / 4
        val out = StringBuilder(digits)
        for (i in 0 until digits) {
            var value = 0
            for (j in 0 until 4) {
                if (i * 4 + j < length && bitmap[i * 4 + j].toInt() != 0) {
                    value = value or (8 shr j)
                }
            }
            out.append(HEX[value])
        }
        return out.toString()
    }

    /**
     * `decode_bitmap`. A character outside the hexadecimal range
     * contributes zero, as in C; `validate_desc` rejects such a
     * description first.
     */
    fun decodeBitmap(hex: String, length: Int): ByteArray {
        val bitmap = ByteArray(length)
        val digits = (length + 3) / 4
        for (i in 0 until digits) {
            val value = if (i < hex.length) hexValue(hex[i]) else 0
            for (j in 0 until 4) {
                if (i * 4 + j < length) {
                    bitmap[i * 4 + j] = if (value and (8 shr j) != 0) 1 else 0
                }
            }
        }
        return bitmap
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> 0
    }

    private fun sameRow(matrix: ByteArray, first: Int, second: Int, length: Int): Boolean {
        for (i in 0 until length) {
            if (matrix[first + i] != matrix[second + i]) return false
        }
        return true
    }

    /**
     * One candidate. The three trees hold the same object, so every
     * mutation deletes it from all trees first, as C does.
     */
    private class Sq(
        var cx: Int,
        var cy: Int,
        var x: Int,
        var y: Int,
        var coverage: Int,
        var ominosize: Int,
    )

    /** `sqcmp_pick`: coverage, omino size, then the coordinates. */
    private val PICK_ORDER = Comparator<Sq> { a, b ->
        fieldCompare(a.coverage, b.coverage)
            ?: fieldCompare(a.ominosize, b.ominosize)
            ?: fieldCompare(a.cy, b.cy)
            ?: fieldCompare(a.cx, b.cx)
            ?: fieldCompare(a.y, b.y)
            ?: fieldCompare(a.x, b.x)
            ?: 0
    }

    /** `sqcmp_cov`: coverage and the output square first. */
    private val COVERAGE_ORDER = Comparator<Sq> { a, b ->
        fieldCompare(a.coverage, b.coverage)
            ?: fieldCompare(a.y, b.y)
            ?: fieldCompare(a.x, b.x)
            ?: fieldCompare(a.ominosize, b.ominosize)
            ?: fieldCompare(a.cy, b.cy)
            ?: fieldCompare(a.cx, b.cx)
            ?: 0
    }

    /** `sqcmp_osize`: omino size and the click square first. */
    private val OMINO_SIZE_ORDER = Comparator<Sq> { a, b ->
        fieldCompare(a.ominosize, b.ominosize)
            ?: fieldCompare(a.cy, b.cy)
            ?: fieldCompare(a.cx, b.cx)
            ?: fieldCompare(a.coverage, b.coverage)
            ?: fieldCompare(a.y, b.y)
            ?: fieldCompare(a.x, b.x)
            ?: 0
    }

    /** The `SORT` macro: the sign of the first difference, or null. */
    private fun fieldCompare(a: Int, b: Int): Int? = when {
        a < b -> -1
        a > b -> 1
        else -> null
    }
}
