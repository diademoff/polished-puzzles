package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. API edges that the oracle scripts do not drive. */
class SolverUtilitiesTest {

    @Test
    fun `dsf copy, reinit, and equivalence`() {
        val dsf = Dsf.new(5)
        dsf.merge(0, 1)
        dsf.merge(1, 2)
        assertEquals(3, dsf.size(0))
        assertTrue(dsf.equivalent(0, 2))

        val copy = dsf.copy()
        assertEquals(3, copy.size(0))
        copy.merge(3, 4)
        assertEquals(1, dsf.size(3))
        assertEquals(2, copy.size(3))

        copy.reinit()
        assertEquals(1, copy.size(0))
        assertEquals(0, copy.canonify(0))
    }

    @Test
    fun `tdq deduplicates and fills`() {
        val queue = Tdq(4)
        queue.add(2)
        queue.add(0)
        queue.add(2)
        assertEquals(2, queue.remove())
        assertEquals(0, queue.remove())
        assertEquals(-1, queue.remove())

        queue.fill()
        assertEquals(listOf(0, 1, 2, 3), List(4) { queue.remove() })
        assertEquals(-1, queue.remove())
    }

    @Test
    fun `findloop detects a square and its bridges`() {
        val adjacency = arrayOf(
            intArrayOf(1, 3),
            intArrayOf(0, 2),
            intArrayOf(1, 3),
            intArrayOf(0, 2),
        )
        val state = FindLoopState(4)
        assertTrue(state.run(NoteNeighbour(adjacency)))
        assertTrue(state.isLoopEdge(0, 1))
        assertNull(state.isBridge(0, 1))
    }

    private class NoteNeighbour(private val adjacency: Array<IntArray>) : Neighbour {
        private var current = -1
        private var position = 0

        override fun next(vertex: Int): Int {
            if (vertex >= 0) {
                current = vertex
                position = 0
            }
            if (current < 0) return -1
            return if (position < adjacency[current].size) adjacency[current][position++] else -1
        }
    }

    @Test
    fun `matching pairs a simple graph`() {
        val result = Matching.match(
            3,
            2,
            arrayOf(intArrayOf(0, 1), intArrayOf(0), intArrayOf(1)),
        )
        assertEquals(2, result.count)
        assertEquals(listOf(0, -1, 1), result.leftToRight.toList())
        assertEquals(listOf(0, 2), result.rightToLeft.toList())
    }

    @Test
    fun `unsorted tree234 uses positions`() {
        val tree = Tree234.unsorted<String>()
        tree.addPos(0, "a")
        tree.addPos(1, "c")
        tree.addPos(1, "b")
        assertEquals(listOf("a", "b", "c"), tree.elements())
        assertEquals("b", tree.deletePos(1))
        assertEquals(listOf("a", "c"), tree.elements())
        assertNull(tree.deletePos(9))
    }

    @Test
    fun `sorted tree234 splits and joins`() {
        val tree = Tree234.sorted(compareBy<Int> { it })
        for (value in listOf(1, 2, 3, 4, 5)) tree.add(value)

        val before = tree.splitPos(3, before = true)
        assertEquals(listOf(1, 2, 3), before!!.elements())
        assertEquals(listOf(4, 5), tree.elements())

        assertNull(tree.join(Tree234.sorted(compareBy<Int> { it }).also { it.add(0) }))
        val joined = before.join(tree)
        assertEquals(listOf(1, 2, 3, 4, 5), joined!!.elements())
    }
}
