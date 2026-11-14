package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.FindLoopState
import name.boyle.chris.sgtpuzzles.engine.Neighbour
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Level: strict. Differential test for `findloop.c`.
 *
 * The DFS forest depends on neighbour order, so the test builds the
 * same adjacency order on both sides and compares the loop-edge set,
 * the bridge list, and the component sizes.
 */
class FindLoopParityTest {

    @Test
    fun `findloop matches upstream`() = OracleClient().use { oracle ->
        val graphs = ArrayList<Pair<Int, List<Pair<Int, Int>>>>()
        graphs += 1 to emptyList()
        graphs += 5 to listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)
        graphs += 4 to listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
        graphs += 5 to completeGraph(5)
        graphs += 6 to listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4)
        graphs += 8 to listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7)

        for (n in 2..18) {
            for (case in 0 until 6) {
                val random = Random(n * 100 + case)
                val edges = ArrayList<Pair<Int, Int>>()
                for (u in 0 until n) {
                    for (v in u + 1 until n) {
                        if (random.nextInt(100) < 28 + case) edges += u to v
                    }
                }
                graphs += n to edges
            }
        }

        for ((n, edges) in graphs) {
            val adjacency = Array(n) { ArrayList<Int>() }
            for ((u, v) in edges) {
                adjacency[u] += v
                adjacency[v] += u
            }
            val text = edges.joinToString(" ") { (u, v) -> "$u-$v" }
            val expected = oracle.findLoop(n, text)

            val state = FindLoopState(n)
            val anyLoop = state.run(adjacencyNeighbour(adjacency))
            assertEquals("anyLoop n=$n edges=$text", expected.anyLoop, anyLoop)

            val loopEdges = ArrayList<Pair<Int, Int>>()
            val bridges = ArrayList<OracleClient.Bridge>()
            for (u in 0 until n) {
                for (v in u + 1 until n) {
                    if (state.isLoopEdge(u, v)) loopEdges += u to v
                    state.isBridge(u, v)?.let { bridges += OracleClient.Bridge(u, v, it.uVertices, it.vVertices) }
                }
            }

            assertEquals("loop edges n=$n edges=$text", expected.loopEdges, loopEdges)
            assertEquals("bridges n=$n edges=$text", expected.bridges, bridges)
        }
    }

    private fun completeGraph(n: Int): List<Pair<Int, Int>> =
        (0 until n).flatMap { u -> (u + 1 until n).map { v -> u to v } }

    private fun adjacencyNeighbour(adjacency: Array<ArrayList<Int>>): Neighbour {
        var current = -1
        var position = 0
        return Neighbour { vertex ->
            if (vertex >= 0) {
                current = vertex
                position = 0
            }
            if (current < 0) return@Neighbour -1
            if (position < adjacency[current].size) {
                adjacency[current][position++]
            } else {
                -1
            }
        }
    }
}
