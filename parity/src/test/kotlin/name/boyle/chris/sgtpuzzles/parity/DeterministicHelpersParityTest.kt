package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Combinations
import name.boyle.chris.sgtpuzzles.engine.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. Differential test for the deterministic helpers.
 *
 * `arraysort` is a heapsort, so equal keys do not keep their input
 * order. `combi` walks subsets in a fixed lexicographic order. Both
 * orders are part of game behavior and must match upstream exactly.
 */
class DeterministicHelpersParityTest {

    @Test
    fun `arraysort matches upstream for every mode`() = OracleClient().use { oracle ->
        val comparators: List<Pair<String, (Long, Long) -> Int>> = listOf(
            "asc" to { a: Long, b: Long -> a.compareTo(b) },
            "desc" to { a: Long, b: Long -> b.compareTo(a) },
            "lowbits" to { a: Long, b: Long -> lowbits(a, b) },
        )

        val arrays = ArrayList<List<Long>>()
        for (length in 0..64) {
            arrays += List(length) { ((it * 37 + 11) % 41 - 20).toLong() }
        }
        arrays += List(50) { 7L }
        arrays += listOf(0L, -1L, 7L, 8L, -8L, 6L, 15L, -15L)

        for (values in arrays) {
            for ((mode, comparator) in comparators) {
                val expected = oracle.sort(values, mode)
                val actual = values.toMutableList()
                Sort.arraySort(actual, comparator)
                assertEquals("sort mode=$mode values=$values", expected, actual)
            }
        }
    }

    @Test
    fun `combinations match upstream in order`() = OracleClient().use { oracle ->
        for (n in 1..9) {
            for (r in 1..n) {
                val expected = oracle.combinations(r, n)
                val combinations = Combinations(r, n)
                val actual = generateSequence { combinations.next() }.toList()

                assertEquals("combinations r=$r n=$n", expected, actual)
                assertEquals("total r=$r n=$n", combinations.total, actual.size)
                assertTrue(
                    "every combination is a sorted subset: r=$r n=$n",
                    actual.all { it.size == r && it == it.sorted() && it.toSet().size == r },
                )
            }
        }
    }

    /** The same tie-break as the oracle's `lowbits` mode. */
    private fun lowbits(a: Long, b: Long): Int {
        val aBits = (a and 7L).toInt()
        val bBits = (b and 7L).toInt()
        if (aBits != bBits) return aBits - bBits
        return a.compareTo(b)
    }
}
