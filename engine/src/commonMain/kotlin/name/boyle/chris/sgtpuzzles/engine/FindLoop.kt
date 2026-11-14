package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `findloop.c`: Tarjan bridge finding.
 *
 * The caller supplies a [Neighbour] iterator. `next(v)` with a
 * non-negative `v` starts enumerating the neighbours of `v`; `next(-1)`
 * continues the current enumeration and returns -1 at the end. The
 * order of neighbours determines the DFS forest, so the iterator must
 * return them in a stable order.
 */
class FindLoopState(val vertexCount: Int) {

    init {
        require(vertexCount >= 0) { "negative vertex count: $vertexCount" }
    }

    private val depth = IntArray(vertexCount)
    private val shallowestReachable = IntArray(vertexCount)
    private val subtreeSize = IntArray(vertexCount)
    private val parent = IntArray(vertexCount)
    private val componentRoot = IntArray(vertexCount)
    private val previous = IntArray(vertexCount)
    private val next = IntArray(vertexCount)

    /** True when the graph contains at least one loop. */
    fun run(neighbour: Neighbour): Boolean {
        for (u in 0 until vertexCount) {
            depth[u] = -1
            shallowestReachable[u] = vertexCount
            subtreeSize[u] = 1
            parent[u] = -1
            componentRoot[u] = u
            previous[u] = u - 1
            next[u] = if (u == vertexCount - 1) -1 else u + 1
        }

        var anyLoop = false
        var v = 0
        while (v != -1) {
            val u = v
            if (depth[u] < 0) {
                if (parent[u] < 0) {
                    depth[u] = 0
                    componentRoot[u] = u
                } else {
                    depth[u] = depth[parent[u]] + 1
                    componentRoot[u] = componentRoot[parent[u]]
                }

                v = u
                var w = neighbour.next(u)
                while (w >= 0) {
                    if (w != parent[u]) {
                        if (depth[w] < 0) {
                            parent[w] = u
                            if (previous[w] >= 0) next[previous[w]] = next[w]
                            if (next[w] >= 0) previous[next[w]] = previous[w]

                            previous[w] = previous[v]
                            next[w] = v
                            if (previous[v] >= 0) next[previous[v]] = w
                            previous[v] = w

                            v = w
                        } else {
                            shallowestReachable[u] =
                                minOf(shallowestReachable[u], depth[w])
                            anyLoop = true
                        }
                    }
                    w = neighbour.next(-1)
                }
            } else {
                if (parent[u] >= 0) {
                    subtreeSize[parent[u]] += subtreeSize[u]
                    shallowestReachable[parent[u]] =
                        minOf(shallowestReachable[parent[u]], shallowestReachable[u])
                }
                v = next[u]
            }
        }
        return anyLoop
    }

    fun isLoopEdge(u: Int, v: Int): Boolean {
        if (parent[u] == v && shallowestReachable[u] >= depth[u]) return false
        if (parent[v] == u && shallowestReachable[v] >= depth[v]) return false
        return true
    }

    data class BridgeSides(val uVertices: Int, val vVertices: Int)

    /** Returns the component sizes when `u-v` is a bridge, or null. */
    fun isBridge(u: Int, v: Int): BridgeSides? {
        oneWay(u, v)?.let { return it }
        oneWay(v, u)?.let { return BridgeSides(it.vVertices, it.uVertices) }
        return null
    }

    private fun oneWay(u: Int, v: Int): BridgeSides? {
        if (parent[u] != v) return null
        if (shallowestReachable[u] < depth[u]) return null

        val uVertices = subtreeSize[u]
        val root = componentRoot[u]
        val vVertices = subtreeSize[root] - subtreeSize[u]
        return BridgeSides(uVertices, vVertices)
    }
}

fun interface Neighbour {
    fun next(vertex: Int): Int
}
