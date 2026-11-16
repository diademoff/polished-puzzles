package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.max
import kotlin.math.min

/**
 * The upstream `solve_game` of `inertia.c:732-1445`.
 *
 * The heuristic builds a graph of every stationary position plus every
 * gem crossed in motion, grows a circuit that visits every gem, and
 * then removes redundant sections. It has no RNG, so the exact edge
 * order, BFS order, and reduction order reproduce the C `SOLVE` text.
 *
 * Null is the seam form of the two C errors: `Game is already solved`
 * and `Unable to find a solution from this starting point`.
 */
internal fun solveInertia(state: Inertia.State): String? {
    val w = state.width
    val h = state.height
    val wh = w * h
    val grid = state.grid
    val dp1 = Inertia.DP1

    // Already collected every gem.
    if (grid.none { it == Inertia.GEM }) return null

    /*
     * Vertex codes are `(y*w+x)*9 + d`: `d` in 0..7 is a gem crossed in
     * that direction and 8 is a stationary position. BFS from the
     * player's stationary vertex finds every interesting node.
     */
    val nodeIndexOf = IntArray(dp1 * wh) { -1 }
    val nodes = IntArray(dp1 * wh)
    var head = 0
    var tail = 0
    nodes[tail] = (state.py * w + state.px) * dp1 + Inertia.DIRECTIONS
    nodeIndexOf[nodes[0]] = 0
    tail++

    while (head < tail) {
        val nc = nodes[head++]
        val d = nc % dp1
        for (dd in 0 until Inertia.DIRECTIONS) {
            var x = nc / dp1
            val y = x / w
            x %= w
            if (d < Inertia.DIRECTIONS && d != dd) continue
            val nnc = moveGoesTo(w, h, grid, x, y, dd)
            if (nnc >= 0 && nnc != nc && nodeIndexOf[nnc] < 0) {
                nodes[tail] = nnc
                nodeIndexOf[nnc] = tail
                tail++
            }
        }
    }
    val n = head

    // Forward edges in node order.
    val edges = IntArray(Inertia.DIRECTIONS * n)
    val edgei = IntArray(n + 1)
    var nedges = 0
    for (i in 0 until n) {
        val nc = nodes[i]
        edgei[i] = nedges
        val d = nc % dp1
        var x = nc / dp1
        val y = x / w
        x %= w
        for (dd in 0 until Inertia.DIRECTIONS) {
            if (d >= Inertia.DIRECTIONS || d == dd) {
                val nnc = moveGoesTo(w, h, grid, x, y, dd)
                if (nnc >= 0 && nnc != nc) edges[nedges++] = nodeIndexOf[nnc]
            }
        }
    }
    edgei[n] = nedges

    /*
     * The sorted back-edge index. Each entry encodes `edge * n + from`
     * before the sort, then the source is extracted and the entry
     * becomes the destination node index.
     */
    val backedges = IntArray(nedges)
    val backedgei = IntArray(n + 1)
    var j = 0
    for (i in 0 until nedges) {
        while (j + 1 < n && i >= edgei[j + 1]) j++
        backedges[i] = edges[i] * n + j
    }
    backedges.sort()
    backedgei[0] = 0
    j = 0
    for (i in 0 until nedges) {
        val k = backedges[i] / n
        backedges[i] %= n
        while (j < k) {
            j++
            backedgei[j] = i
        }
    }
    backedgei[n] = nedges

    var circuitsize = 256
    var circuit = IntArray(circuitsize)
    var circuitlen = 0
    circuit[circuitlen++] = 0

    val unvisited = IntArray(wh)
    for (i in 0 until wh) if (grid[i] == Inertia.GEM) unvisited[i] = 1

    val dist = IntArray(n)
    val dist2 = IntArray(n)
    val list = IntArray(n)

    /*
     * Extend the circuit until no unvisited gem is reachable from both
     * directions.
     */
    while (true) {
        for (pass in 0 until 2) {
            val ep = if (pass == 0) edges else backedges
            val ei = if (pass == 0) edgei else backedgei
            val dp = if (pass == 0) dist else dist2
            head = 0
            tail = 0
            for (i in 0 until n) dp[i] = -1
            for (i in 0 until circuitlen) {
                val ni = circuit[i]
                if (dp[ni] < 0) {
                    dp[ni] = 0
                    list[tail++] = ni
                }
            }
            while (head < tail) {
                val ni = list[head++]
                for (i in ei[ni] until ei[ni + 1]) {
                    val ti = ep[i]
                    if (ti >= 0 && dp[ti] < 0) {
                        dp[ti] = dp[ni] + 1
                        list[tail++] = ti
                    }
                }
            }
        }
        var bestdist = -1
        var target = -1
        for (i in 0 until n) {
            if (unvisited[nodes[i] / dp1] != 0 && dist[i] >= 0 && dist2[i] >= 0) {
                val thisdist = dist[i] + dist2[i]
                if (bestdist < 0 || bestdist > thisdist) {
                    bestdist = thisdist
                    target = i
                }
            }
        }
        if (target < 0) break

        // Distances to and from the chosen target.
        for (pass in 0 until 2) {
            val ep = if (pass == 0) edges else backedges
            val ei = if (pass == 0) edgei else backedgei
            val dp = if (pass == 0) dist else dist2
            for (i in 0 until n) dp[i] = -1
            head = 0
            tail = 0
            dp[target] = 0
            list[tail++] = target
            while (head < tail) {
                val ni = list[head++]
                for (i in ei[ni] until ei[ni + 1]) {
                    val ti = ep[i]
                    if (ti >= 0 && dp[ti] < 0) {
                        dp[ti] = dp[ni] + 1
                        list[tail++] = ti
                    }
                }
            }
        }

        // Cheapest insertion: a round trip at one vertex, or a detour
        // between two adjacent tour vertices.
        var n1 = -1
        var n2 = -1
        bestdist = -1
        for (i in 0 until circuitlen) {
            if (dist[circuit[i]] >= 0 && dist2[circuit[i]] >= 0) {
                val thisdist = dist[circuit[i]] + dist2[circuit[i]]
                if (bestdist < 0 || thisdist < bestdist) {
                    bestdist = thisdist
                    n1 = i
                    n2 = i
                }
            }
            if (i + 1 < circuitlen &&
                dist2[circuit[i]] >= 0 && dist[circuit[i + 1]] >= 0
            ) {
                val thisdist = dist2[circuit[i]] + dist[circuit[i + 1]]
                if (bestdist < 0 || thisdist < bestdist) {
                    bestdist = thisdist
                    n1 = i
                    n2 = i + 1
                }
            }
        }
        if (bestdist < 0) return null

        var extralen = dist2[circuit[n1]] + dist[circuit[n2]]
        if (n1 != n2) extralen--
        circuitlen += extralen
        if (circuitlen >= circuitsize) {
            circuitsize = circuitlen + 256
            circuit = circuit.copyOf(circuitsize)
        }
        circuit.copyInto(
            circuit,
            destinationOffset = n2 + extralen,
            startIndex = n2,
            endIndex = circuitlen - extralen,
        )
        n2 += extralen

        // Write the path from the target back to n1 through the
        // back-edge index, then forward to n2 through the edges.
        run {
            var dest = n2
            var ni = circuit[n2]
            while (true) {
                circuit[dest] = ni
                if (dist[ni] == 0) break
                dest--
                var ti = -1
                var i = backedgei[ni]
                while (i < backedgei[ni + 1]) {
                    ti = backedges[i]
                    if (ti >= 0 && dist[ti] == dist[ni] - 1) break
                    i++
                }
                check(i < backedgei[ni + 1] && ti >= 0)
                ni = ti
            }
        }
        run {
            var dest = n1
            var ni = circuit[n1]
            while (true) {
                circuit[dest] = ni
                if (dist2[ni] == 0) break
                dest++
                var ti = -1
                var i = edgei[ni]
                while (i < edgei[ni + 1]) {
                    ti = edges[i]
                    if (ti >= 0 && dist2[ti] == dist2[ni] - 1) break
                    i++
                }
                check(i < edgei[ni + 1] && ti >= 0)
                ni = ti
            }
        }

        for (i in n1..n2) {
            val pos = nodes[circuit[i]] / dp1
            check(pos in 0 until wh)
            unvisited[pos] = 0
        }
    }

    /*
     * Remove redundant sections: scan forward and then backward,
     * replacing each removable span with a BFS shortest path.
     */
    while (true) {
        val oldlen = circuitlen
        for (dir in intArrayOf(1, -1)) {
            for (i in 0 until wh) unvisited[i] = 0
            for (i in 0 until circuitlen) {
                val xy = nodes[circuit[i]] / dp1
                if (grid[xy] == Inertia.GEM) unvisited[xy]++
            }
            var missed = false
            for (i in 0 until wh) {
                if (grid[i] == Inertia.GEM && unvisited[i] == 0) {
                    missed = true
                    break
                }
            }
            if (missed) return null

            var i = if (dir > 0) 0 else circuitlen - 1
            var jj = i
            while (i < circuitlen && i >= 0) {
                val xy = nodes[circuit[i]] / dp1
                if (grid[xy] == Inertia.GEM && unvisited[xy] > 1) {
                    unvisited[xy]--
                } else if (grid[xy] == Inertia.GEM || i == circuitlen - 1) {
                    var p = min(i, jj)
                    var q = max(i, jj)

                    for (k in 0 until n) dist[k] = -1
                    head = 0
                    tail = 0
                    dist[circuit[p]] = 0
                    list[tail++] = circuit[p]
                    while (head < tail && dist[circuit[q]] < 0) {
                        val ni = list[head++]
                        for (k in edgei[ni] until edgei[ni + 1]) {
                            val ti = edges[k]
                            if (ti >= 0 && dist[ti] < 0) {
                                dist[ti] = dist[ni] + 1
                                list[tail++] = ti
                            }
                        }
                    }

                    val thisdist = dist[circuit[q]]
                    check(thisdist >= 0 && thisdist <= q - p)

                    circuit.copyInto(
                        circuit,
                        destinationOffset = p + thisdist,
                        startIndex = q,
                        endIndex = circuitlen,
                    )
                    circuitlen -= q - p
                    q = p + thisdist
                    circuitlen += q - p
                    if (dir > 0) i = q

                    var dest = q
                    var ni = circuit[q]
                    while (true) {
                        circuit[dest] = ni
                        if (dist[ni] == 0) break
                        dest--
                        var ti = -1
                        var k = backedgei[ni]
                        while (k < backedgei[ni + 1]) {
                            ti = backedges[k]
                            if (ti >= 0 && dist[ti] == dist[ni] - 1) break
                            k++
                        }
                        check(k < backedgei[ni + 1] && ti >= 0)
                        ni = ti
                    }

                    p++
                    while (p < q) {
                        val xy2 = nodes[circuit[p]] / dp1
                        if (grid[xy2] == Inertia.GEM) unvisited[xy2]++
                        p++
                    }
                    jj = i
                }
                i += dir
            }
        }
        if (circuitlen == oldlen) break
    }

    // Encode the stationary transitions as direction digits.
    val out = StringBuilder(circuitlen + 2)
    out.append('S')
    var x = nodes[circuit[0]] / dp1 % w
    var y = nodes[circuit[0]] / dp1 / w
    for (i in 1 until circuitlen) {
        if (nodes[circuit[i]] % dp1 != Inertia.DIRECTIONS) continue
        val x2 = nodes[circuit[i]] / dp1 % w
        val y2 = nodes[circuit[i]] / dp1 / w
        val ddx = if (x2 > x) 1 else if (x2 < x) -1 else 0
        val ddy = if (y2 > y) 1 else if (y2 < y) -1 else 0
        var d = 0
        while (d < Inertia.DIRECTIONS) {
            if (Inertia.dx(d) == ddx && Inertia.dy(d) == ddy) {
                out.append('0' + d)
                break
            }
            d++
        }
        check(d < Inertia.DIRECTIONS)
        x = x2
        y = y2
    }
    check(out.length <= circuitlen)
    return out.toString()
}

/**
 * `move_goes_to`: where a move in direction [d] ends.
 *
 * The return value is the next vertex code, or -1 when a mine is in the
 * way. A wall ahead or a stop ends the move stationary (`DIRECTIONS`);
 * a gem keeps the move in motion.
 */
private fun moveGoesTo(
    w: Int,
    h: Int,
    grid: CharArray,
    x0: Int,
    y0: Int,
    d: Int,
): Int {
    var x = x0
    var y = y0
    var dr = -1
    while (true) {
        if (Inertia.at(w, h, grid, x + Inertia.dx(d), y + Inertia.dy(d)) ==
            Inertia.WALL
        ) {
            dr = Inertia.DIRECTIONS
            break
        }
        x += Inertia.dx(d)
        y += Inertia.dy(d)
        val v = Inertia.at(w, h, grid, x, y)
        if (v == Inertia.STOP) {
            dr = Inertia.DIRECTIONS
            break
        }
        if (v == Inertia.GEM) {
            dr = d
            break
        }
        if (v == Inertia.MINE) return -1
    }
    check(dr >= 0)
    return (y * w + x) * Inertia.DP1 + dr
}
