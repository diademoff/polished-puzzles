package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Level: semantic.
 *
 * Fixed vectors recorded from upstream `divvy.c` through the oracle.
 * The exhaustive differential comparison lives in `:parity`.
 *
 * A vector pins the roots, the encoded generator state, and the attempt
 * count. The state proves that the shuffle and the per-iteration draws
 * consume the same stream; the roots prove that the output forest uses
 * the same merge order, not only the same partition.
 */
class DivvyTest {

    private data class Vector(
        val w: Int,
        val h: Int,
        val k: Int,
        val seed: String,
        val attempts: Int,
        val roots: List<Int>,
        val state: String,
    )

    private val vectors = listOf(
        // The palisade default at the frozen golden seed.
        Vector(
            w = 5,
            h = 5,
            k = 5,
            seed = "12345",
            attempts = 1,
            roots = listOf(
                15, 13, 9, 9, 9,
                15, 13, 13, 9, 9,
                15, 15, 13, 13, 24,
                15, 23, 24, 24, 24,
                23, 23, 23, 23, 24,
            ),
            state = "8eb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c9427352c0c6cbd03b883a8b90d90bbb5e8155e1e407",
        ),
        // The manual divvy-test default. The first attempt fails.
        Vector(
            w = 9,
            h = 4,
            k = 6,
            seed = "123456",
            attempts = 2,
            roots = listOf(
                28, 28, 14, 16, 16, 16, 16, 35, 35,
                28, 14, 14, 14, 14, 14, 16, 16, 35,
                28, 30, 30, 30, 30, 33, 33, 33, 35,
                28, 28, 30, 30, 33, 33, 33, 35, 35,
            ),
            state = "834a8d09ca3762af61e59520943dc26494f8941b6bb4837eb74329105ee4568d" +
                "da7dc67ed2ca2ad9ce18916d22a59d511491d62f085290edab23528f04",
        ),
        // Two failed attempts before the accepted one.
        Vector(
            w = 5,
            h = 5,
            k = 5,
            seed = "353",
            attempts = 3,
            roots = listOf(
                10, 8, 8, 8, 8,
                10, 10, 10, 8, 14,
                10, 14, 14, 14, 14,
                23, 24, 24, 24, 24,
                23, 23, 23, 23, 24,
            ),
            state = "91da660e06b787f245667943dc948dafab997e2598a64b0c587d1f98be5b1164" +
                "a6760e5c273066fb8c053e345472be5e9755be2a8bfc07cc75f3d89302",
        ),
        // A path of dominoes.
        Vector(
            w = 1,
            h = 4,
            k = 2,
            seed = "1",
            attempts = 1,
            roots = listOf(1, 1, 3, 3),
            state = "356a192b7913b04c54574d18c28d46e6395428abe6cc90b878b948c35e92b003" +
                "c792c46c58c4af40e9f745362d016b15c850b2a4282727bd0603436f05",
        ),
        // One omino covers the whole board.
        Vector(
            w = 3,
            h = 3,
            k = 9,
            seed = "1",
            attempts = 1,
            roots = listOf(8, 8, 8, 8, 8, 8, 8, 8, 8),
            state = "356a192b7913b04c54574d18c28d46e6395428abe6cc90b878b948c35e92b003" +
                "c792c46c58c4af40e9f745362d016b15c850b2a4282727bd0603436f10",
        ),
        // k = 1: every cell is its own omino.
        Vector(
            w = 5,
            h = 5,
            k = 1,
            seed = "7",
            attempts = 1,
            roots = List(25) { it },
            state = "912ba3cda1883801594b6e1b452790cc53948fda23e7a7428138939fbe2f69d2" +
                "3e5b87383efd83c976e55e3b11436c5bb1d5495eee29a7abfde4a58b05",
        ),
    )

    @Test
    fun `fixed vectors match upstream`() {
        for (vector in vectors) {
            val where = "${vector.w}x${vector.h}n${vector.k} seed ${vector.seed}"

            val random = RandomState.fromSeed(vector.seed)
            val partition = Divvy.rectangleWithAttempts(vector.w, vector.h, vector.k, random)
            assertEquals(vector.attempts, partition.attempts, "attempts $where")
            assertEquals(vector.roots, roots(partition.dsf), "roots $where")
            assertEquals(vector.state, random.encodeState(), "state $where")
            checkPartition(vector.w, vector.h, vector.k, partition.dsf)

            // The public entry point returns the same forest and consumes
            // the same stream as the counted one.
            val publicRandom = RandomState.fromSeed(vector.seed)
            assertEquals(
                vector.roots,
                roots(Divvy.rectangle(vector.w, vector.h, vector.k, publicRandom)),
                "rectangle roots $where",
            )
            assertEquals(vector.state, publicRandom.encodeState(), "rectangle state $where")
        }
    }

    @Test
    fun `every partition has k-cell connected regions`() {
        val cases = listOf(
            Triple(5, 5, 5),
            Triple(8, 6, 6),
            Triple(10, 8, 8),
            Triple(15, 12, 10),
            Triple(9, 4, 6),
            Triple(7, 7, 7),
            Triple(1, 2, 1),
            Triple(1, 4, 2),
            Triple(4, 1, 2),
            Triple(1, 6, 2),
            Triple(6, 1, 2),
            Triple(2, 2, 4),
            Triple(5, 5, 25),
            Triple(4, 2, 4),
            Triple(2, 3, 6),
            Triple(5, 5, 1),
            Triple(1, 1, 1),
        )
        for ((w, h, k) in cases) {
            for (seed in listOf("1", "2", "12345", "353", "123456")) {
                val dsf = Divvy.rectangle(w, h, k, RandomState.fromSeed(seed))
                checkPartition(w, h, k, dsf)
            }
        }
    }

    @Test
    fun `attempt rejects impossible sizes`() {
        assertFailsWith<IllegalArgumentException>("width") {
            Divvy.attempt(0, 5, 5, RandomState.fromSeed("1"))
        }
        assertFailsWith<IllegalArgumentException>("height") {
            Divvy.attempt(5, 0, 5, RandomState.fromSeed("1"))
        }
        assertFailsWith<IllegalArgumentException>("region size") {
            Divvy.attempt(5, 5, 0, RandomState.fromSeed("1"))
        }
        assertFailsWith<IllegalArgumentException>("non-divisor") {
            Divvy.attempt(5, 5, 2, RandomState.fromSeed("1"))
        }
        assertFailsWith<IllegalArgumentException>("area") {
            Divvy.attempt(23171, 23171, 1, RandomState.fromSeed("1"))
        }
    }

    private fun roots(dsf: Dsf): List<Int> = List(dsf.elementCount) { dsf.canonify(it) }

    /** Checks the count, the size, and the connectivity of every region. */
    private fun checkPartition(w: Int, h: Int, k: Int, dsf: Dsf) {
        val wh = w * h
        assertEquals(wh, dsf.elementCount, "elements")
        val roots = IntArray(wh) { dsf.canonify(it) }

        val counts = LinkedHashMap<Int, Int>()
        for (root in roots) counts[root] = (counts[root] ?: 0) + 1
        assertEquals(wh / k, counts.size, "region count")
        for ((root, count) in counts) assertEquals(k, count, "region $root size")
        for (i in 0 until wh) assertEquals(k, dsf.size(i), "dsf size of $i")

        val seen = BooleanArray(wh)
        for (start in 0 until wh) {
            if (seen[start]) continue
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            seen[start] = true
            var reached = 0
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                reached++
                val x = cell % w
                val y = cell / w
                for (dir in 0 until 4) {
                    val nx = x + (if (dir == 0) -1 else if (dir == 1) 1 else 0)
                    val ny = y + (if (dir == 2) -1 else if (dir == 3) 1 else 0)
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val next = ny * w + nx
                    if (seen[next] || roots[next] != roots[start]) continue
                    seen[next] = true
                    stack.addLast(next)
                }
            }
            assertEquals(k, reached, "region of $start is connected")
        }
    }
}
