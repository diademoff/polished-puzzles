package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. Fixed vectors for the two deterministic helpers. */
class DeterministicHelpersTest {

    @Test
    fun `arraySort orders by the comparator`() {
        val values = mutableListOf(5L, 3L, 9L, 1L, 5L, -2L)
        Sort.arraySort(values) { a, b -> a.compareTo(b) }
        assertEquals(listOf(-2L, 1L, 3L, 5L, 5L, 9L), values)

        val descending = mutableListOf(5L, 3L, 9L, 1L, 5L, -2L)
        Sort.arraySort(descending) { a, b -> b.compareTo(a) }
        assertEquals(listOf(9L, 5L, 5L, 3L, 1L, -2L), descending)
    }

    @Test
    fun `arraySort handles empty and one-element lists`() {
        val empty = mutableListOf<Int>()
        Sort.arraySort(empty) { a, b -> a.compareTo(b) }
        assertEquals(emptyList(), empty)

        val one = mutableListOf(7)
        Sort.arraySort(one) { a, b -> a.compareTo(b) }
        assertEquals(listOf(7), one)
    }

    @Test
    fun `combinations follow the upstream order`() {
        val combinations = Combinations(r = 2, n = 4)
        assertEquals(6, combinations.total)
        assertEquals(
            listOf(
                listOf(0, 1),
                listOf(0, 2),
                listOf(0, 3),
                listOf(1, 2),
                listOf(1, 3),
                listOf(2, 3),
            ),
            generateSequence { combinations.next() }.toList(),
        )
        assertNull(combinations.next())
    }

    @Test
    fun `combinations reset and cover every subset`() {
        val combinations = Combinations(r = 3, n = 6)
        val first = generateSequence { combinations.next() }.toList()
        assertEquals(20, first.size)
        assertEquals(20, first.toSet().size)
        assertTrue(first.all { it.size == 3 && it == it.sorted() })

        combinations.reset()
        assertEquals(first, generateSequence { combinations.next() }.toList())
    }
}
