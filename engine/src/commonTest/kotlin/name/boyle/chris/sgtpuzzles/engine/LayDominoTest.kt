package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Level: semantic.
 *
 * Fixed vectors recorded from upstream `laydomino.c` through the oracle.
 * The exhaustive differential comparison lives in `:parity`.
 *
 * A vector pins the grid and the encoded generator state. The state is
 * the stronger fixture: it proves that the candidate shuffle and one
 * neighbour shuffle per breadth-first pop consume the same stream.
 */
class LayDominoTest {

    private data class Vector(
        val w: Int,
        val h: Int,
        val seed: String,
        val grid: List<Int>,
        val state: String,
    )

    private val vectors = listOf(
        // A magnets preset shape. The greedy phase leaves several
        // singletons, so this vector covers the repair loop.
        Vector(
            w = 6,
            h = 5,
            seed = "12345",
            grid = listOf(
                1, 0, 3, 2, 5, 4,
                12, 13, 9, 8, 11, 10,
                6, 7, 15, 14, 17, 16,
                24, 20, 19, 22, 21, 29,
                18, 26, 25, 28, 27, 23,
            ),
            state = "90b2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c92da1d68717de2055ed36f753cb018c07ca53974e08",
        ),
        // An odd area: one singleton remains at index 6.
        Vector(
            w = 3,
            h = 3,
            seed = "12345",
            grid = listOf(1, 0, 5, 4, 3, 2, 6, 8, 7),
            state = "8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b840b",
        ),
        // A one-row board: no vertical candidate, no repair pass.
        Vector(
            w = 1,
            h = 5,
            seed = "12345",
            grid = listOf(1, 0, 3, 2, 4),
            state = "8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b8403",
        ),
    )

    @Test(timeout = 120_000)
    fun `layout matches the recorded vectors`() {
        for (vector in vectors) {
            val random = RandomState.fromSeed(vector.seed)
            val grid = LayDomino.layout(vector.w, vector.h, random)
            val where = "${vector.w}x${vector.h} seed ${vector.seed}"
            assertEquals(vector.grid, grid.toList(), "grid $where")
            assertEquals(vector.state, random.encodeState(), "state $where")
        }
    }

    @Test(timeout = 120_000)
    fun `prealloc writes the same grid and consumes the same stream`() {
        for (vector in vectors) {
            val wh = vector.w * vector.h
            val random = RandomState.fromSeed(vector.seed)
            val grid = IntArray(wh)
            val grid2 = IntArray(wh)
            val list = IntArray(2 * wh)
            LayDomino.layoutPrealloc(vector.w, vector.h, random, grid, grid2, list)
            val where = "${vector.w}x${vector.h} seed ${vector.seed}"
            assertEquals(vector.grid, grid.toList(), "grid $where")
            assertEquals(vector.state, random.encodeState(), "state $where")

            // Larger scratch buffers must give the same output.
            val wideGrid = IntArray(wh + 3)
            val wideGrid2 = IntArray(wh + 3)
            val wideList = IntArray(2 * wh + 5)
            val wideRandom = RandomState.fromSeed(vector.seed)
            LayDomino.layoutPrealloc(
                vector.w,
                vector.h,
                wideRandom,
                wideGrid,
                wideGrid2,
                wideList,
            )
            assertContentEquals(grid, wideGrid.copyOfRange(0, wh), "wide grid buffer $where")
            assertEquals(vector.state, wideRandom.encodeState(), "wide state $where")
        }
    }

    @Test(timeout = 120_000)
    fun `the layout is a valid domino tiling`() {
        val seeds = listOf("0", "1", "12345", "99999")
        for (w in 1..8) {
            for (h in 1..8) {
                for (seed in seeds) {
                    assertValidTiling(w, h, RandomState.fromSeed(seed), "seed $seed")
                }
            }
        }
        val bytes = RandomState.fromSeed(byteArrayOf(97, 1, 98))
        for (w in 1..8) {
            for (h in 1..8) {
                assertValidTiling(w, h, bytes.copy(), "byte seed")
            }
        }
    }

    @Test(timeout = 120_000)
    fun `repair passes match the recorded shapes`() {
        // Shapes whose greedy placement leaves the expected number of
        // singletons straight away, so the search is not entered.
        for ((w, h) in listOf(2 to 2, 4 to 4, 1 to 5)) {
            val (_, repairs) = LayDomino.layoutWithRepairs(w, h, RandomState.fromSeed("12345"))
            assertEquals(0, repairs, "repairs ${w}x$h")
        }
        // Seeds that reach the breadth first search. The pass count is
        // not a property of the shape; it depends on the shuffle.
        assertEquals(
            1,
            LayDomino.layoutWithRepairs(3, 3, RandomState.fromSeed("3")).second,
            "repairs 3x3 seed 3",
        )
        assertEquals(
            2,
            LayDomino.layoutWithRepairs(6, 5, RandomState.fromSeed("12345")).second,
            "repairs 6x5 seed 12345",
        )
        assertEquals(
            6,
            LayDomino.layoutWithRepairs(10, 9, RandomState.fromSeed("2")).second,
            "repairs 10x9 seed 2",
        )
    }

    @Test
    fun `invalid sizes and short buffers are rejected`() {
        val random = RandomState.fromSeed("12345")
        assertFailsWith<IllegalArgumentException> { LayDomino.layout(0, 1, random) }
        assertFailsWith<IllegalArgumentException> { LayDomino.layout(1, 0, random) }
        assertFailsWith<IllegalArgumentException> { LayDomino.layout(-1, 2, random) }
        assertFailsWith<IllegalArgumentException> { LayDomino.layout(2, -1, random) }
        // The candidate list needs 2*w*h entries, which must fit an Int.
        assertFailsWith<IllegalArgumentException> {
            LayDomino.layout(Int.MAX_VALUE, 2, random)
        }

        val wh = 9
        assertFailsWith<IllegalArgumentException> {
            LayDomino.layoutPrealloc(3, 3, random, IntArray(wh - 1), IntArray(wh), IntArray(2 * wh))
        }
        assertFailsWith<IllegalArgumentException> {
            LayDomino.layoutPrealloc(3, 3, random, IntArray(wh), IntArray(wh - 1), IntArray(2 * wh))
        }
        assertFailsWith<IllegalArgumentException> {
            LayDomino.layoutPrealloc(3, 3, random, IntArray(wh), IntArray(wh), IntArray(2 * wh - 1))
        }
    }

    private fun assertValidTiling(w: Int, h: Int, random: RandomState, note: String) {
        val grid = LayDomino.layout(w, h, random)
        val where = "${w}x$h $note"
        assertEquals(w * h, grid.size, "size $where")

        var singletons = 0
        for (i in grid.indices) {
            val partner = grid[i]
            if (partner < 0 || partner >= w * h) {
                throw AssertionError("partner out of range at $i: $partner ($where)")
            }
            if (partner == i) {
                singletons++
                continue
            }
            assertEquals(i, grid[partner], "partner points back $i $where")
            val dx = kotlin.math.abs(i % w - partner % w)
            val dy = kotlin.math.abs(i / w - partner / w)
            assertEquals(1, dx + dy, "orthogonal neighbour $i $where")
        }
        assertEquals(w * h % 2, singletons, "singleton count $where")
    }
}
