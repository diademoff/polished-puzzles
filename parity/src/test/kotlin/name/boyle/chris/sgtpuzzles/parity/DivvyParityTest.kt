package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Divvy
import name.boyle.chris.sgtpuzzles.engine.Dsf
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: strict. Differential test for `divvy.c`.
 *
 * The oracle runs `divvy_rectangle` and returns the canonical element
 * and class size of every cell, the attempt count, and the encoded
 * generator state. The state is the strongest check: it catches an
 * extra or missing shuffle or `random_upto` draw even when the resulting
 * partition happens to look valid.
 *
 * The matrix covers rectangles, paths, `k = 1`, `k = 2`, `k = w*h`, and
 * seeds that need a retry.
 */
class DivvyParityTest {

    private data class Case(val w: Int, val h: Int, val k: Int)

    private val cases = listOf(
        // Palisade presets.
        Case(5, 5, 5),
        Case(8, 6, 6),
        Case(10, 8, 8),
        Case(15, 12, 10),
        // Rectangles with a failure in the first attempt.
        Case(9, 4, 6),
        Case(7, 7, 7),
        Case(10, 10, 5),
        // Paths: one row or one column.
        Case(1, 2, 1),
        Case(2, 1, 1),
        Case(1, 4, 2),
        Case(4, 1, 2),
        Case(1, 6, 2),
        Case(6, 1, 2),
        Case(1, 8, 2),
        Case(1, 10, 5),
        Case(3, 1, 3),
        // k = 1 and k = w*h.
        Case(5, 5, 1),
        Case(1, 1, 1),
        Case(2, 2, 4),
        Case(3, 3, 9),
        Case(5, 5, 25),
        // Wide, tall, and small rectangles.
        Case(11, 3, 3),
        Case(3, 11, 3),
        Case(2, 3, 6),
        Case(3, 2, 6),
        Case(4, 2, 4),
        Case(6, 2, 4),
        Case(3, 4, 6),
    )

    private val seeds = listOf("1", "2", "12345", "353", "123456")

    @Test
    fun `divvy matches upstream`() = OracleClient().use { oracle ->
        for (case in cases) {
            for (seed in seeds) {
                val expected = oracle.divvy(case.w, case.h, case.k, seed = seed)
                val where = "${case.w}x${case.h}n${case.k} seed $seed"

                val random = RandomState.fromSeed(seed)
                var attempts = 0
                var dsf: Dsf? = null
                while (dsf == null) {
                    attempts++
                    dsf = Divvy.attempt(case.w, case.h, case.k, random)
                }

                assertEquals("attempts $where", expected.attempts, attempts)
                assertEquals("roots $where", expected.roots, roots(dsf))
                assertEquals("sizes $where", expected.sizes, sizes(dsf))
                assertEquals("state $where", expected.state, random.encodeState())

                // The retry loop of the public entry point agrees with the
                // manual loop above.
                val publicRandom = RandomState.fromSeed(seed)
                assertEquals(
                    "rectangle roots $where",
                    expected.roots,
                    roots(Divvy.rectangle(case.w, case.h, case.k, publicRandom)),
                )
                assertEquals("rectangle state $where", expected.state, publicRandom.encodeState())
            }
        }
    }

    @Test
    fun `divvy continues from an encoded state`() = OracleClient().use { oracle ->
        // A test can resume the stream at any draw, so the state check is
        // not tied to a seed.
        val first = oracle.divvy(5, 5, 5, seed = "12345")
        val second = oracle.divvy(5, 5, 5, state = first.state)
        val random = RandomState.decode(first.state)
        val dsf = Divvy.rectangle(5, 5, 5, random)
        assertEquals("roots", second.roots, roots(dsf))
        assertEquals("sizes", second.sizes, sizes(dsf))
        assertEquals("state", second.state, random.encodeState())
    }

    private fun roots(dsf: Dsf): List<Int> = List(dsf.elementCount) { dsf.canonify(it) }

    private fun sizes(dsf: Dsf): List<Int> = List(dsf.elementCount) { dsf.size(it) }
}
