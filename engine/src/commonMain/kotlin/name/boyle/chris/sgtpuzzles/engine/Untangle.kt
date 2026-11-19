package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * A port of upstream `untangle.c` (the playable backend, without
 * `EDITOR`).
 *
 * The game is a planar graph: the state is a fixed edge set and `n`
 * free point positions, and the only move moves one point. The rules
 * are small. The parity traps are the generator geometry, the fixed
 * 60-pixel hit radius, and the fact that a click on a point is a real
 * move and not a selection.
 *
 * The default arrangement is a circle produced by double `sin`/`cos`
 * and truncated to a `long`. Those coordinates reach the description,
 * the auxiliary answer, every save, and every trace, so the arithmetic
 * is repeated exactly. Both the oracle and the JVM use the same libm
 * results on this host; the engine test pins the coordinates.
 *
 * The edge graph is shared between history states, as C shares it
 * through a refcount. The crossing map and the three flags are copied,
 * as C copies them.
 */
object Untangle : Game {

    /** `enum { COL_SYSBACKGROUND, ... }`. Colour 0 is not the board. */
    private const val COL_SYSBACKGROUND = 0
    private const val COL_BACKGROUND = 1
    private const val COL_LINE = 2
    private const val COL_CROSSEDLINE = 3
    private const val COL_OUTLINE = 4
    private const val COL_POINT = 5
    private const val COL_DRAGPOINT = 6
    private const val COL_CURSORPOINT = 7
    private const val COL_NEIGHBOUR = 8
    private const val COL_FLASH1 = 9
    private const val COL_FLASH2 = 10
    private const val NCOLOURS = 11

    /** `CIRCLE_RADIUS`, `DRAG_THRESHOLD` with `ANDROID`, and the tile size. */
    internal const val CIRCLE_RADIUS = 6
    internal const val DRAG_THRESHOLD = CIRCLE_RADIUS * 10
    private const val PREFERRED_TILE_SIZE = 64

    /** `POINTDENSITY` and `MAXDEGREE`. */
    private const val POINT_DENSITY = 3
    private const val MAX_DEGREE = 4

    /** `PI` from `puzzles.h`, a double literal. */
    private const val PI = 3.141592653589793238462643383279502884197169399

    /** The two solve error strings. The seam drops them after a check. */
    internal const val ERROR_NO_AUX = "Solution not known for this puzzle"
    internal const val ERROR_BAD_AUX = "Internal error: aux_info badly formatted"

    /** `struct game_params`: one integer. */
    data class Params(val n: Int) : Game.Params {
        override fun copy(): Game.Params = Params(n)
    }

    /** `struct point`: a rational `(x/d, y/d)`, with `d > 0` in play. */
    data class Point(val x: Long, val y: Long, val d: Long)

    /** `struct edge`, always stored with `a < b`. */
    data class Edge(val a: Int, val b: Int)

    /**
     * `struct graph`: the immutable edge set, ordered by `(a, b)` as the
     * C `tree234` is. History states share one instance.
     */
    class Graph internal constructor(edges: List<Edge>) {
        val edges: Array<Edge> = edges.sortedWith(compareBy({ it.a }, { it.b })).toTypedArray()

        private val present: HashSet<Long> = HashSet(edges.size * 2)

        init {
            for (e in this.edges) present += key(e.a, e.b)
        }

        val edgeCount: Int get() = edges.size

        /** `isedge`: membership by the stored `(min, max)` pair. */
        fun isEdge(a: Int, b: Int): Boolean {
            require(a != b) { "an edge needs two different points" }
            return key(minOf(a, b), maxOf(a, b)) in present
        }

        private fun key(a: Int, b: Int): Long =
            (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

        override fun equals(other: Any?): Boolean =
            other is Graph && edges.contentEquals(other.edges)

        override fun hashCode(): Int = edges.contentHashCode()
    }

    /**
     * `struct game_state`. [completed] is sticky: a crossing added later
     * never clears it. [cheated] and [justSolved] are both set by an
     * `S` move. The crossing map is indexed by edge, in tree order.
     */
    class State(
        val params: Params,
        val w: Int,
        val h: Int,
        val pts: Array<Point>,
        val graph: Graph,
        val crosses: BooleanArray,
        internal var completed: Boolean,
        internal var cheated: Boolean,
        internal var justSolved: Boolean,
    ) : Game.State {

        val n: Int get() = params.n

        val edgeCount: Int get() = graph.edgeCount

        fun point(index: Int): Point = pts[index]

        override fun copy(): Game.State = State(
            params = params,
            w = w,
            h = h,
            pts = pts.copyOf(),
            graph = graph,
            crosses = crosses.copyOf(),
            completed = completed,
            cheated = cheated,
            justSolved = justSolved,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                w == other.w &&
                h == other.h &&
                pts.contentEquals(other.pts) &&
                graph == other.graph &&
                crosses.contentEquals(other.crosses) &&
                completed == other.completed &&
                cheated == other.cheated &&
                justSolved == other.justSolved

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + w
            result = 31 * result + h
            result = 31 * result + pts.contentHashCode()
            result = 31 * result + graph.hashCode()
            result = 31 * result + crosses.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + justSolved.hashCode()
            return result
        }

        override fun toString(): String =
            "Untangle.State(n=$n, completed=$completed, cheated=$cheated)"
    }

    /**
     * `struct game_ui`. At most one of [dragPoint] and [cursorPoint] is
     * valid. The three preferences are frontend state: they are not
     * serialised and not part of `DrawState`.
     *
     * C leaves `newpoint`, `anim_length`, and `dragtype` unset in
     * `new_ui`; every read is guarded by a write, so the port
     * initializes them to a defined value.
     */
    class Ui(
        var dragPoint: Int = -1,
        var cursorPoint: Int = -1,
        var newPoint: Point = Point(0, 0, 1),
        var justDragged: Boolean = false,
        var justMoved: Boolean = false,
        var animLength: Float = 0f,
        var snapToGrid: Boolean = false,
        var showCrossedEdges: Boolean = false,
        var vertexNumbers: Boolean = false,
    ) : Game.Ui

    /**
     * `struct game_drawstate`: the last pixel of every point, the last
     * background, and the last drag and cursor points. There is no
     * blitter. `tilesize` is not stored because `redraw` receives it.
     */
    class DrawState internal constructor(n: Int) : Game.DrawState {
        val x: LongArray = LongArray(n) { -1L }
        val y: LongArray = LongArray(n) { -1L }
        var bg: Int = -1
        var dragPoint: Int = -1
        var cursorPoint: Int = -1
    }

    override val name: String = "Untangle"
    override val backendName: String = "untangle"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /** `can_format_as_text_ever` is false and `text_format` is NULL. */
    override val canFormatAsText: Boolean = false

    override fun defaultParams(): Game.Params = Params(10)

    /** `game_fetch_preset`: five sizes, titled `"%d points"`. */
    override fun presets(): List<Preset> =
        listOf(6, 10, 15, 20, 25).map { Preset("$it points", it.toString()) }

    /** `decode_params`: `atoi` and nothing else. */
    override fun decodeParams(text: String): Game.Params = Params(atoi(text, 0))

    /** `encode_params`: `"%d"`; `full` is ignored, so PARAMS == CPARAMS. */
    override fun encodeParams(params: Game.Params, full: Boolean): String =
        (params as Params).n.toString()

    /** `validate_params`, in the C order. `full` is ignored. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val n = (params as Params).n
        if (n < 4) return "Number of points must be at least four"
        if (n > Int.MAX_VALUE / 3) {
            return "Number of points must not be unreasonably large"
        }
        return null
    }

    /**
     * `validate_desc`: two `atoi` passes per pair with the C digit
     * scans. The digit scan starts where `atoi` was called, so leading
     * white space or a sign is accepted by `atoi` and then rejected by
     * the scan. A trailing comma is consumed and ends the string.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val n = (params as Params).n
        var i = 0
        while (i < desc.length) {
            val a = atoi(desc, i)
            if (a < 0 || a >= n) return "Number out of range in game description"
            while (i < desc.length && desc[i] in '0'..'9') i++
            if (i >= desc.length || desc[i] != '-') {
                return "Expected '-' after number in game description"
            }
            i++
            val b = atoi(desc, i)
            if (b < 0 || b >= n) return "Number out of range in game description"
            while (i < desc.length && desc[i] in '0'..'9') i++
            if (i < desc.length) {
                if (desc[i] != ',') {
                    return "Expected ',' after number in game description"
                }
                i++
            }
            if (a == b) return "Node linked to itself in game description"
        }
        return null
    }

    /** `COORDLIMIT(n) = squarert(n * POINTDENSITY)`. */
    internal fun coordLimit(n: Int): Int = squarert(n.toLong() * POINT_DENSITY).toInt()

    /**
     * `squarert` from `untangle.c`: an integer floor square root, bit
     * by bit. The values are below 2^32 for every accepted `n`.
     */
    internal fun squarert(n: Long): Long {
        var d = n
        var a = 0L
        var b = 1L shl 30
        do {
            a = a shr 1
            val di = 2 * a + b
            if (di <= d) {
                d -= di
                a += b
            }
            b = b shr 2
        } while (b != 0L)
        return a
    }

    /**
     * `cross`: true when the segment `a1-a2` and the segment `b1-b2`
     * intersect, with any endpoint on the other segment counted. The
     * dot products are 64-bit, as `dotprod64` is.
     */
    internal fun cross(a1: Point, a2: Point, b1: Point, b2: Point): Boolean {
        var b1x = b1.x * a1.d - a1.x * b1.d
        var b1y = b1.y * a1.d - a1.y * b1.d
        var b2x = b2.x * a1.d - a1.x * b2.d
        var b2y = b2.y * a1.d - a1.y * b2.d
        var px = a1.y * a2.d - a2.y * a1.d
        var py = a2.x * a1.d - a1.x * a2.d
        var d1 = b1x * px + b1y * py
        var d2 = b2x * px + b2y * py
        if ((sign64(d1) > 0 && sign64(d2) > 0) ||
            (sign64(d1) < 0 && sign64(d2) < 0)
        ) {
            return false
        }

        if (sign64(d1) == 0 && sign64(d2) == 0) {
            px = a2.x * a1.d - a1.x * a2.d
            py = a2.y * a1.d - a1.y * a2.d
            d1 = b1x * px + b1y * py
            d2 = b2x * px + b2y * py
            if (sign64(d1) < 0 && sign64(d2) < 0) return false
            val d3 = px * px + py * py
            if (d1 > d3 && d2 > d3) return false
        }

        b1x = a1.x * b1.d - b1.x * a1.d
        b1y = a1.y * b1.d - b1.y * a1.d
        b2x = a2.x * b1.d - b1.x * a2.d
        b2y = a2.y * b1.d - b1.y * a2.d
        px = b1.y * b2.d - b2.y * b1.d
        py = b2.x * b1.d - b1.x * b2.d
        d1 = b1x * px + b1y * py
        d2 = b2x * px + b2y * py
        if ((sign64(d1) > 0 && sign64(d2) > 0) ||
            (sign64(d1) < 0 && sign64(d2) < 0)
        ) {
            return false
        }
        return true
    }

    private fun sign64(value: Long): Int =
        if (value < 0) -1 else if (value == 0L) 0 else 1

    /**
     * `make_circle`: `n` points on a circle in the box `(0, 0)-(w, w)`,
     * denominator `PREFERRED_TILESIZE`. The double arithmetic is C's:
     * `i * 2 * PI / n`, `r * sin`, `-r * cos`, then a truncating cast
     * after `+ 0.5`.
     */
    internal fun makeCircle(n: Int, w: Int): Array<Point> {
        val d = PREFERRED_TILE_SIZE.toLong()
        val c = d * w / 2
        val r = d * w * 3 / 7
        return Array(n) { i ->
            val angle = i * 2 * PI / n
            val x = r * sin(angle)
            val y = -r * cos(angle)
            Point((c + x + 0.5).toLong(), (c + y + 0.5).toLong(), d)
        }
    }

    /** `game_configure` (`untangle.c:196-212`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Number of points", p.n.toString()),
        )
    }

    /** `custom_params` (`untangle.c:214-221`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(atoi((items[0] as ConfigItem.Text).value, 0))

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`untangle.c:1201-1228`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool("Snap points to a grid", false, "snap-to-grid"),
            ConfigItem.Bool(
                "Show edges that cross another edge",
                false,
                "show-crossed-edges",
            ),
            ConfigItem.Choices(
                "Display style for vertices",
                ":Circles:Numbers",
                ":circle:number",
                0,
                "vertex-style",
            ),
        )

    /** `set_prefs` (`untangle.c:1230-1235`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.snapToGrid = prefs.bool("snap-to-grid")
        u.showCrossedEdges = prefs.bool("show-crossed-edges")
        u.vertexNumbers = prefs.choice("vertex-style") == 1
    }

    /**
     * `new_game_desc`: one grid shuffle, the deterministic degree and
     * distance edge loop, then one or more label shuffles until the
     * circle crosses. The auxiliary answer is always written.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        val n = p.n
        val w = coordLimit(n)
        val h = w

        // Step A: choose n cells from the w*h grid.
        val cells = IntArray(w * h) { it }
        Divvy.shuffle(cells, random)
        val pts = Array(n) { i ->
            Point(
                (cells[i] % w).toLong(),
                (cells[i] / w).toLong(),
                1,
            )
        }

        // Step B: add edges by degree and then by distance.
        val degrees = IntArray(n)
        val vertices = ArrayList<Vertex>(n)
        for (i in 0 until n) vertices += Vertex(0, i)
        vertices.sortWith(VERTEX_ORDER)
        val edgeList = ArrayList<Edge>()
        val candidates = ArrayList<Vertex>()
        while (true) {
            var added = false
            for (rank in 0 until n) {
                val v = vertices[rank]
                val j = v.vindex
                if (v.param >= MAX_DEGREE) break

                candidates.clear()
                for (k in rank + 1 until n) {
                    val kv = vertices[k]
                    val ki = kv.vindex
                    if (kv.param >= MAX_DEGREE || isEdge(edgeList, ki, j)) continue
                    val dx = (pts[ki].x - pts[j].x).toInt()
                    val dy = (pts[ki].y - pts[j].y).toInt()
                    candidates += Vertex(dx * dx + dy * dy, ki)
                }
                candidates.sortWith(VERTEX_ORDER)

                var chosen = false
                for (candidate in candidates) {
                    val ki = candidate.vindex
                    var blocked = false
                    for (point in 0 until n) {
                        if (point != ki && point != j &&
                            cross(pts[ki], pts[j], pts[point], pts[point])
                        ) {
                            blocked = true
                            break
                        }
                    }
                    if (blocked) continue
                    for (e in edgeList) {
                        if (e.a != ki && e.a != j && e.b != ki && e.b != j &&
                            cross(pts[ki], pts[j], pts[e.a], pts[e.b])
                        ) {
                            blocked = true
                            break
                        }
                    }
                    if (blocked) continue

                    edgeList += Edge(minOf(j, ki), maxOf(j, ki))
                    added = true
                    chosen = true
                    degrees[j]++
                    degrees[ki]++
                    reinsert(vertices, Vertex(degrees[j], j))
                    reinsert(vertices, Vertex(degrees[ki], ki))
                    break
                }
                if (chosen) break
            }
            if (!added) break
        }
        edgeList.sortWith(EDGE_ORDER)

        // Step C: shuffle the labels until the circle has a crossing.
        val mapping = IntArray(n) { it }
        val circle = makeCircle(n, w)
        while (true) {
            cancel.check()
            Divvy.shuffle(mapping, random)
            var crossing = false
            outer@ for (i in edgeList.indices) {
                val e = edgeList[i]
                for (j in i + 1 until edgeList.size) {
                    val e2 = edgeList[j]
                    if (e2.a == e.a || e2.a == e.b ||
                        e2.b == e.a || e2.b == e.b
                    ) {
                        continue
                    }
                    if (cross(
                            circle[mapping[e2.a]],
                            circle[mapping[e2.b]],
                            circle[mapping[e.a]],
                            circle[mapping[e.b]],
                        )
                    ) {
                        crossing = true
                        break@outer
                    }
                }
            }
            if (crossing) break
        }

        // Step D: encode the graph with the label mapping.
        val desc = encodeGraph(edgeList, mapping)

        // Step E: the planar answer, with the same label mapping.
        val answer = arrayOfNulls<Point>(n)
        for (i in 0 until n) {
            val j = mapping[i]
            var point = pts[i]
            if (point.d and 1L != 0L) {
                point = Point(point.x * 2, point.y * 2, point.d * 2)
            }
            answer[j] = Point(point.x + point.d / 2, point.y + point.d / 2, point.d)
        }
        val aux = buildString {
            append('S')
            for (i in 0 until n) {
                val point = requireNotNull(answer[i])
                append(";P")
                append(i)
                append(':')
                append(point.x)
                append(',')
                append(point.y)
                append('/')
                append(point.d)
            }
        }

        return Description(desc, aux)
    }

    // `untangle.c` scans every candidate pair of the n points per pass and accepts n up to INT_MAX/3; 100 points is four times the largest 25-point preset.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.n > 100) Game.SLOW_GENERATION_WARNING else null
    }

    /** `encode_graph`: map, order each pair, then sort numerically. */
    internal fun encodeGraph(edges: List<Edge>, mapping: IntArray?): String {
        val mapped = ArrayList<Edge>(edges.size)
        for (e in edges) {
            val a = if (mapping != null) mapping[e.a] else e.a
            val b = if (mapping != null) mapping[e.b] else e.b
            mapped += Edge(minOf(a, b), maxOf(a, b))
        }
        mapped.sortWith(EDGE_ORDER)
        return mapped.joinToString(",") { "${it.a}-${it.b}" }
    }

    /** `new_game`: circle, shared edge graph, crossing map, flags. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val n = p.n
        val w = coordLimit(n)
        val pts = makeCircle(n, w)
        val edges = ArrayList<Edge>()
        var i = 0
        while (i < desc.length) {
            val a = atoi(desc, i)
            require(a in 0 until n) { "point $a is out of range in '$desc'" }
            while (i < desc.length && desc[i] in '0'..'9') i++
            require(i < desc.length && desc[i] == '-') {
                "expected '-' in '$desc'"
            }
            i++
            val b = atoi(desc, i)
            require(b in 0 until n) { "point $b is out of range in '$desc'" }
            while (i < desc.length && desc[i] in '0'..'9') i++
            if (i < desc.length) {
                require(desc[i] == ',') { "expected ',' in '$desc'" }
                i++
            }
            require(a != b) { "point $a is linked to itself in '$desc'" }
            if (isEdge(edges, a, b)) continue
            edges += Edge(minOf(a, b), maxOf(a, b))
        }
        edges.sortWith(EDGE_ORDER)
        val graph = Graph(edges)
        val state = State(
            params = p,
            w = w,
            h = w,
            pts = pts,
            graph = graph,
            crosses = BooleanArray(graph.edgeCount),
            completed = false,
            cheated = false,
            justSolved = false,
        )
        markCrossings(state, graph)
        return state
    }

    /**
     * `mark_crossings`: set the crossing flags, and set [State.completed]
     * when no pair crosses. It never clears [State.completed].
     */
    internal fun markCrossings(state: State, graph: Graph = state.graph) {
        val edges = graph.edges
        state.crosses.fill(false)
        var ok = true
        for (i in edges.indices) {
            val e = edges[i]
            for (j in i + 1 until edges.size) {
                val e2 = edges[j]
                if (e2.a == e.a || e2.a == e.b ||
                    e2.b == e.a || e2.b == e.b
                ) {
                    continue
                }
                if (cross(state.pts[e2.a], state.pts[e2.b], state.pts[e.a], state.pts[e.b])) {
                    ok = false
                    state.crosses[i] = true
                    state.crosses[j] = true
                }
            }
        }
        if (ok) state.completed = true
    }

    /**
     * `solve_game`, including the error out-parameter that the seam has
     * no room for. The symmetry search is single precision and in the C
     * order; the output rounds with `+ 0.5F` and truncation.
     */
    internal fun solveWithError(state: Game.State, auxInfo: String?): Pair<String?, String?> {
        val current = state as State
        val n = current.params.n
        if (auxInfo == null) return null to ERROR_NO_AUX

        val aux = parseAux(auxInfo, n) ?: return null to ERROR_BAD_AUX

        var besti = -1
        var bestd = 0.0f
        for (i in 0 until 8) {
            val matrix = IntArray(4)
            matrix[i and 1] = if (i and 2 != 0) 1 else -1
            matrix[3 - (i and 1)] = if (i and 4 != 0) 1 else -1

            var d = 0.0f
            for (j in 0 until n) {
                var px = aux[j].x.toFloat() / aux[j].d.toFloat()
                var py = aux[j].y.toFloat() / aux[j].d.toFloat()
                val sx = current.pts[j].x.toFloat() / current.pts[j].d.toFloat()
                val sy = current.pts[j].y.toFloat() / current.pts[j].d.toFloat()
                val cx = current.w.toFloat() / 2
                val cy = current.h.toFloat() / 2

                px -= cx
                py -= cy

                var ox = matrix[0] * px + matrix[1] * py
                var oy = matrix[2] * px + matrix[3] * py

                ox += cx
                oy += cy

                val dx = ox - sx
                val dy = oy - sy
                d += dx * dx + dy * dy
            }

            if (besti < 0 || bestd > d) {
                besti = i
                bestd = d
            }
        }

        check(besti >= 0) { "the symmetry search has no answer" }
        val matrix = IntArray(4)
        matrix[besti and 1] = if (besti and 2 != 0) 1 else -1
        matrix[3 - (besti and 1)] = if (besti and 4 != 0) 1 else -1

        val out = StringBuilder("S")
        for (i in 0 until n) {
            var px = aux[i].x.toFloat() / aux[i].d.toFloat()
            var py = aux[i].y.toFloat() / aux[i].d.toFloat()
            val cx = current.w.toFloat() / 2
            val cy = current.h.toFloat() / 2

            px -= cx
            py -= cy

            var ox = matrix[0] * px + matrix[1] * py
            var oy = matrix[2] * px + matrix[3] * py

            ox += cx
            oy += cy

            val d = 2
            ox *= d
            oy *= d
            val x = (ox + 0.5f).toLong()
            val y = (oy + 0.5f).toLong()
            out.append(";P")
            out.append(i)
            out.append(':')
            out.append(x)
            out.append(',')
            out.append(y)
            out.append('/')
            out.append(d)
        }
        return out.toString() to null
    }

    /** `Game.solve`: the move, or null when the aux is missing or bad. */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveWithError(state, auxInfo).first

    /** `solve_game` with the two C error strings (`untangle.c:992-1095`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val (move, error) = solveWithError(state, auxInfo)
        return SolveOutcome(move, error)
    }

    /**
     * The `sscanf(aux, ";P%d:%ld,%ld/%ld%n")` chain. C eats the first
     * character without checking that it is `S`; an empty aux is
     * treated as badly formatted instead of reading past the end.
     */
    private fun parseAux(aux: String, n: Int): Array<Point>? {
        if (aux.isEmpty()) return null
        val pts = arrayOfNulls<Point>(n)
        var index = 1
        for (i in 0 until n) {
            val entry = parseAuxEntry(aux, index) ?: return null
            if (entry.first != i) return null
            pts[i] = Point(entry.second.x, entry.second.y, entry.second.d)
            index = entry.third
        }
        return Array(n) { requireNotNull(pts[it]) }
    }

    /**
     * One `;P%d:%ld,%ld/%ld%n` conversion, starting at [start]. Returns
     * the point index, the point, and the position after the match, or
     * null when fewer than four conversions succeeded.
     */
    private fun parseAuxEntry(
        text: String,
        start: Int,
    ): Triple<Int, Point, Int>? {
        var i = start
        if (i >= text.length || text[i] != ';') return null
        i++
        if (i >= text.length || text[i] != 'P') return null
        i++
        val p = scanInt(text, i) ?: return null
        i = p.second
        if (i >= text.length || text[i] != ':') return null
        i++
        val x = scanLong(text, i) ?: return null
        i = x.second
        if (i >= text.length || text[i] != ',') return null
        i++
        val y = scanLong(text, i) ?: return null
        i = y.second
        if (i >= text.length || text[i] != '/') return null
        i++
        val d = scanLong(text, i) ?: return null
        i = d.second
        return Triple(p.first, Point(x.first, y.first, d.first), i)
    }

    /**
     * `execute_move`: an optional `S`, then one or more `P` segments.
     * The empty string is valid. A bare `S` is not. x and y are not
     * bounds-checked, only `p` and `d`.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val n = from.params.n
        val ret = from.copy() as State
        ret.justSolved = false

        var i = 0
        while (i < move.length) {
            if (move[i] == 'S') {
                i++
                if (i < move.length && move[i] == ';') i++
                ret.cheated = true
                ret.justSolved = true
            }
            if (i < move.length && move[i] == 'P') {
                val segment = scanMoveSegment(move, i + 1)
                if (segment == null) return null
                val p = segment.p
                if (p < 0 || p >= n || segment.d <= 0L) return null
                ret.pts[p] = Point(segment.x, segment.y, segment.d)
                i = i + 1 + segment.consumed
                if (i < move.length && move[i] == ';') i++
            } else {
                return null
            }
        }

        markCrossings(ret)
        return ret
    }

    private data class MoveSegment(
        val p: Int,
        val x: Long,
        val y: Long,
        val d: Long,
        val consumed: Int,
    )

    /**
     * `sscanf(move+1, "%d:%ld,%ld/%ld%n")`. Numeric conversions skip
     * leading white space; the three separators are literal. The
     * returned [MoveSegment.consumed] is `%n`.
     */
    private fun scanMoveSegment(text: String, start: Int): MoveSegment? {
        var i = start
        val p = scanInt(text, i) ?: return null
        i = p.second
        if (i >= text.length || text[i] != ':') return null
        i++
        val x = scanLong(text, i) ?: return null
        i = x.second
        if (i >= text.length || text[i] != ',') return null
        i++
        val y = scanLong(text, i) ?: return null
        i = y.second
        if (i >= text.length || text[i] != '/') return null
        i++
        val d = scanLong(text, i) ?: return null
        i = d.second
        return MoveSegment(p.first, x.first, y.first, d.first, i - start)
    }

    /** `game_status`: 1 when complete, otherwise 0. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /** No status bar and no status text. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** No text form. */
    override fun textFormat(state: Game.State): String = ""

    /**
     * `game_changed_state`: cancel a drag and report the transition
     * into a real completion. A solve sets `cheated`, so it never
     * raises the event.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val next = newState as State
        val previous = oldState as State?
        u.dragPoint = -1
        u.justMoved = u.justDragged
        u.justDragged = false
        return next.completed && !next.cheated && previous != null && !previous.completed
    }

    /** `game_request_keys` is NULL, so the C arrow mode stays 3. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /**
     * The C midend forces `ANDROID_ARROWS_ONLY` before it calls a game
     * with no `request_keys` when the Android full-cursor hint is set,
     * as `midend_request_keys_by_game` does. The game itself still has
     * no labels.
     */
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `game_compute_size`: a square board of `COORDLIMIT(n)` cells. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val side = coordLimit((params as Params).n) * tileSize
        return Size(side, side)
    }

    /**
     * `game_colours`: `COL_BACKGROUND` is the shell colour and
     * `COL_SYSBACKGROUND` is its lowlight. Colour 0 is the dead space,
     * not the board.
     */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, -1, COL_SYSBACKGROUND)

        setColour(ret, COL_LINE, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_CROSSEDLINE, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_OUTLINE, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_POINT, 0.0f, 0.0f, 1.0f)
        setColour(ret, COL_DRAGPOINT, 1.0f, 1.0f, 1.0f)
        setColour(ret, COL_CURSORPOINT, 0.5f, 0.5f, 0.5f)
        setColour(ret, COL_NEIGHBOUR, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_FLASH1, 0.5f, 0.5f, 0.5f)
        setColour(ret, COL_FLASH2, 1.0f, 1.0f, 1.0f)
        return ret
    }

    private fun setColour(ret: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
    }

    /**
     * `new_ui`: no drag, no cursor, and the three preferences off. The
     * unset C fields get a defined initial value.
     */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `game_new_drawstate`: every cached pixel is -1 and the flags -1. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState = DrawState((state as State).params.n)

    /** `game_get_cursor_location`: a 13 by 13 pixel box around the point. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        val s = state as State
        val pt = when {
            u.dragPoint >= 0 -> u.newPoint
            u.cursorPoint >= 0 -> s.pts[u.cursorPoint]
            else -> return null
        }
        val cx = (tileSize.toLong() * pt.x / pt.d).toInt()
        val cy = (tileSize.toLong() * pt.y / pt.d).toInt()
        return Game.CursorRect(
            cx - CIRCLE_RADIUS,
            cy - CIRCLE_RADIUS,
            2 * CIRCLE_RADIUS + 1,
            2 * CIRCLE_RADIUS + 1,
        )
    }

    /**
     * One unit per point, at the place `game_redraw` draws it, in a
     * square big enough to touch. A point names the points it is joined
     * to and whether one of its lines is crossed.
     *
     * A press on a point is not a move on its own: `interpret_move`
     * takes the point on the press and commits wherever the release
     * happens, so a press and release on the same point only writes the
     * point back where it already is. No unit therefore offers an
     * action: dragging a point, and the keyboard drag with `Enter` and
     * the arrows, stay on the pointer and the cursor.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val n = s.params.n
        val size = computeSize(s.params, tileSize)
        val side = maxOf(2 * CIRCLE_RADIUS + 1, tileSize / 2)

        val units = (0 until n).map { i ->
            val pt = s.pts[i]
            val px = (tileSize.toLong() * pt.x / pt.d).toInt()
            val py = (tileSize.toLong() * pt.y / pt.d).toInt()
            val joins = buildList {
                for (e in s.graph.edges.indices) {
                    val edge = s.graph.edges[e]
                    val other = when (i) {
                        edge.a -> edge.b
                        edge.b -> edge.a
                        else -> continue
                    }
                    add(other + 1)
                }
            }
            val crossed = (s.graph.edges.indices).any { e ->
                s.crosses[e] &&
                    (s.graph.edges[e].a == i || s.graph.edges[e].b == i)
            }
            val words = buildList {
                add("point ${i + 1}")
                if (joins.isNotEmpty()) add("joined to " + sideSpeech(joins.map { "$it" }))
                if (crossed) add("crossed line")
                if (u.dragPoint == i) add("held")
                if (u.cursorPoint == i) add("cursor")
            }
            SpokenUnit(
                row = 0,
                column = 0,
                left = (px - side / 2).coerceIn(0, maxOf(size.width - side, 0)),
                top = (py - side / 2).coerceIn(0, maxOf(size.height - side, 0)),
                width = side,
                height = side,
                text = words.joinToString(", "),
            )
        }

        val board = SpokenBoard(0, 0, "", units)
        val cursor = when {
            u.dragPoint >= 0 -> board.units.getOrNull(u.dragPoint)
            u.cursorPoint >= 0 -> board.units.getOrNull(u.cursorPoint)
            else -> null
        }
        return board.cappedUnits(cursor)
    }

    /**
     * `place_dragged_point`: the exact or the snapped rational for the
     * drag. The division truncates toward zero in both branches, so a
     * small negative pixel with snap on commits at grid 0.
     */
    internal fun placeDraggedPoint(
        state: State,
        ui: Ui,
        tileSize: Int,
        x: Int,
        y: Int,
    ) {
        if (ui.snapToGrid) {
            val d = state.params.n - 1
            val gx = d.toLong() * x / (state.w.toLong() * tileSize)
            val gy = d.toLong() * y / (state.h.toLong() * tileSize)
            ui.newPoint = Point(
                (gx * 2 + 1) * state.w,
                (gy * 2 + 1) * state.h,
                d * 2L,
            )
        } else {
            ui.newPoint = Point(x.toLong(), y.toLong(), tileSize.toLong())
        }
    }

    /** `normsq`: an integer division, truncated, then converted to float. */
    internal fun normsq(pt: Point): Float =
        ((pt.x * pt.x + pt.y * pt.y) / (pt.d * pt.d)).toFloat()

    /** `point_under_mouse`: the nearest point within the fixed radius. */
    internal fun pointUnderMouse(
        state: State,
        tileSize: Int,
        x: Int,
        y: Int,
    ): Int {
        var best = -1
        var bestd = 0L
        for (i in 0 until state.params.n) {
            val px = state.pts[i].x * tileSize / state.pts[i].d
            val py = state.pts[i].y * tileSize / state.pts[i].d
            val dx = px - x
            val dy = py - y
            val d = dx * dx + dy * dy
            if (best == -1 || bestd > d) {
                best = i
                bestd = d
            }
        }
        if (bestd <= DRAG_THRESHOLD.toLong() * DRAG_THRESHOLD) return best
        return -1
    }

    /**
     * `interpret_move`. A press on a point starts a drag, a release
     * commits the stored pixel as a rational with the tile size as the
     * denominator, and a click is therefore a real move. A press away
     * from every point is `NoEffect`.
     */
    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val s = state as State
        val u = ui as Ui
        val n = s.params.n

        if (Buttons.isMouseDown(button)) {
            val p = pointUnderMouse(s, tileSize, x, y)
            if (p >= 0) {
                u.dragPoint = p
                u.cursorPoint = -1
                placeDraggedPoint(s, u, tileSize, x, y)
                return MoveResult.UiUpdate
            }
            return MoveResult.NoEffect
        } else if (Buttons.isMouseDrag(button) && u.dragPoint >= 0) {
            placeDraggedPoint(s, u, tileSize, x, y)
            return MoveResult.UiUpdate
        } else if (Buttons.isMouseRelease(button) && u.dragPoint >= 0) {
            val p = u.dragPoint
            u.dragPoint = -1
            u.cursorPoint = -1
            if (u.newPoint.x < 0 ||
                u.newPoint.x >= s.w.toLong() * u.newPoint.d ||
                u.newPoint.y < 0 ||
                u.newPoint.y >= s.h.toLong() * u.newPoint.d
            ) {
                return MoveResult.UiUpdate
            }
            u.justDragged = true
            return MoveResult.Move(moveText(p, u.newPoint))
        } else if (Buttons.isMouseDrag(button)) {
            return MoveResult.NoEffect
        } else if (Buttons.isMouseRelease(button)) {
            check(u.dragPoint == -1) { "a release without a press" }
            return MoveResult.NoEffect
        } else if (Buttons.isCursorMove(button)) {
            if (u.dragPoint < 0) {
                var best = -1
                var bestd = 0.0f
                if (u.cursorPoint < 0) u.cursorPoint = 0
                val cur = s.pts[u.cursorPoint]
                for (i in 0 until n) {
                    if (i == u.cursorPoint) continue
                    val p = s.pts[i]
                    val delta = Point(
                        p.x * cur.d - cur.x * p.d,
                        p.y * cur.d - cur.y * p.d,
                        cur.d * p.d,
                    )
                    if (delta.x == 0L && delta.y == 0L) continue

                    val rightDirection = when (button) {
                        Buttons.CURSOR_UP ->
                            delta.y <= -delta.x && delta.y <= delta.x
                        Buttons.CURSOR_DOWN ->
                            delta.y >= -delta.x && delta.y >= delta.x
                        Buttons.CURSOR_LEFT ->
                            delta.y >= delta.x && delta.y <= -delta.x
                        Buttons.CURSOR_RIGHT ->
                            delta.y <= delta.x && delta.y >= -delta.x
                        else -> false
                    }
                    if (!rightDirection) continue

                    val distsq = normsq(delta)
                    if (best == -1 || distsq < bestd) {
                        best = i
                        bestd = distsq
                    }
                }
                if (best >= 0) {
                    u.cursorPoint = best
                    return MoveResult.UiUpdate
                }
            } else {
                val increment = tileSize / 2
                var dx = 0
                var dy = 0
                when (button) {
                    Buttons.CURSOR_UP -> dy = -increment
                    Buttons.CURSOR_DOWN -> dy = increment
                    Buttons.CURSOR_LEFT -> dx = -increment
                    Buttons.CURSOR_RIGHT -> dx = increment
                }
                placeDraggedPoint(
                    s,
                    u,
                    tileSize,
                    (u.newPoint.x * tileSize / u.newPoint.d).toInt() + dx,
                    (u.newPoint.y * tileSize / u.newPoint.d).toInt() + dy,
                )
                return MoveResult.UiUpdate
            }
        } else if (button == Buttons.CURSOR_SELECT) {
            if (u.dragPoint < 0 && u.cursorPoint >= 0) {
                val p = u.cursorPoint
                u.dragPoint = p
                u.cursorPoint = -1
                u.newPoint = Point(
                    s.pts[p].x * tileSize / s.pts[p].d,
                    s.pts[p].y * tileSize / s.pts[p].d,
                    tileSize.toLong(),
                )
                return MoveResult.UiUpdate
            } else if (u.dragPoint >= 0) {
                val p = u.dragPoint
                u.cursorPoint = u.dragPoint
                u.dragPoint = -1
                if (u.newPoint.x < 0 ||
                    u.newPoint.x >= s.w.toLong() * u.newPoint.d ||
                    u.newPoint.y < 0 ||
                    u.newPoint.y >= s.h.toLong() * u.newPoint.d
                ) {
                    return MoveResult.UiUpdate
                }
                u.justDragged = true
                return MoveResult.Move(moveText(p, u.newPoint))
            } else if (u.cursorPoint < 0) {
                u.cursorPoint = 0
                return MoveResult.UiUpdate
            }
        } else if (Buttons.stripModifiers(button) == Buttons.CURSOR_SELECT2 ||
            Buttons.stripModifiers(button) == '\t'.code
        ) {
            if (u.dragPoint >= 0) return MoveResult.NoEffect
            if (u.cursorPoint < 0) {
                u.cursorPoint = 0
                return MoveResult.UiUpdate
            }
            check(u.cursorPoint >= 0)
            val direction = if ((button and Buttons.MOD_SHFT) != 0) -1 else 1
            u.cursorPoint = (u.cursorPoint + direction + n) % n
            return MoveResult.UiUpdate
        }
        return MoveResult.Unused
    }

    private fun moveText(p: Int, point: Point): String = "P$p:${point.x},${point.y}/${point.d}"

    /**
     * `game_redraw`, settled: no flash and no animation blend. The
     * whole board is redrawn whenever the cache guard fails; a frame
     * with nothing new is exactly `start_draw`, `end_draw`.
     */
    override fun redraw(
        state: Game.State,
        ui: Game.Ui,
        drawState: Game.DrawState,
        sink: DrawSink,
        tileSize: Int,
    ) {
        val s = state as State
        val u = ui as Ui
        val ds = drawState as DrawState
        val bg = COL_BACKGROUND

        var pointsMoved = false
        for (i in 0 until s.params.n) {
            var p = s.pts[i]
            if (u.dragPoint == i) p = u.newPoint
            val x = p.x * tileSize / p.d
            val y = p.y * tileSize / p.d
            if (ds.x[i] != x || ds.y[i] != y) pointsMoved = true
            ds.x[i] = x
            ds.y[i] = y
        }

        if (ds.bg == bg &&
            ds.dragPoint == u.dragPoint &&
            ds.cursorPoint == u.cursorPoint &&
            !pointsMoved
        ) {
            return
        }
        ds.dragPoint = u.dragPoint
        ds.cursorPoint = u.cursorPoint
        ds.bg = bg

        val size = computeSize(s.params, tileSize)
        sink.rect(0, 0, size.width, size.height, bg)

        for (i in s.graph.edges.indices) {
            val e = s.graph.edges[i]
            val colour = if (u.showCrossedEdges && s.crosses[i]) {
                COL_CROSSEDLINE
            } else {
                COL_LINE
            }
            sink.line(
                ds.x[e.a].toInt(), ds.y[e.a].toInt(),
                ds.x[e.b].toInt(), ds.y[e.b].toInt(),
                colour,
            )
        }

        val drawOrder = intArrayOf(
            COL_POINT,
            COL_NEIGHBOUR,
            COL_CURSORPOINT,
            COL_DRAGPOINT,
        )
        for (pass in drawOrder) {
            for (i in 0 until s.params.n) {
                val c = when {
                    u.dragPoint == i -> COL_DRAGPOINT
                    u.cursorPoint == i -> COL_CURSORPOINT
                    u.dragPoint >= 0 && s.graph.isEdge(u.dragPoint, i) -> COL_NEIGHBOUR
                    else -> COL_POINT
                }
                if (c != pass) continue
                val x = ds.x[i].toInt()
                val y = ds.y[i].toInt()
                if (u.vertexNumbers) {
                    sink.circle(x, y, CIRCLE_RADIUS * 2, bg, bg)
                    sink.text(
                        x, y, Text.FONT_VARIABLE, CIRCLE_RADIUS * 3,
                        Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE, c, i.toString(),
                    )
                } else {
                    sink.circle(x, y, CIRCLE_RADIUS, c, COL_OUTLINE)
                }
            }
        }

        sink.update(0, 0, size.width, size.height)
    }

    private data class Vertex(val param: Int, val vindex: Int)

    private val VERTEX_ORDER = compareBy<Vertex>({ it.param }, { it.vindex })

    private val EDGE_ORDER = compareBy<Edge>({ it.a }, { it.b })

    private fun isEdge(edges: List<Edge>, a: Int, b: Int): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return edges.any { it.a == lo && it.b == hi }
    }

    /** Replace the vertex's tree entry after its degree changed. */
    private fun reinsert(vertices: MutableList<Vertex>, updated: Vertex) {
        val index = vertices.indexOfFirst { it.vindex == updated.vindex }
        check(index >= 0) { "vertex ${updated.vindex} is not in the tree" }
        vertices.removeAt(index)
        var at = 0
        while (at < vertices.size && VERTEX_ORDER.compare(vertices[at], updated) < 0) at++
        vertices.add(at, updated)
    }

    /** `atoi` is the same scanner as `%ld`, truncated at the end. */
    private fun atoi(text: String, start: Int): Int =
        (scanSigned(text, start)?.first ?: 0L).toInt()

    /**
     * `%d`: white space, a sign, then digits. glibc reads the number as
     * a `long`, saturating at its range, and assigns the low 32 bits to
     * the `int`. So `4294967296` becomes 0, not `INT_MAX`, and
     * `-2147483649` becomes `INT_MAX`, not `INT_MIN`.
     */
    private fun scanInt(text: String, start: Int): Pair<Int, Int>? {
        val scanned = scanSigned(text, start) ?: return null
        return scanned.first.toInt() to scanned.second
    }

    /** `%ld`: white space, a sign, then digits, saturated to `long`. */
    private fun scanLong(text: String, start: Int): Pair<Long, Int>? =
        scanSigned(text, start)

    /**
     * The shared `strtol` body. Returns the saturated value and the
     * position after the digits, or null when no digit follows the
     * optional white space and sign.
     */
    private fun scanSigned(text: String, start: Int): Pair<Long, Int>? {
        var i = start
        while (i < text.length && isCWhiteSpace(text[i])) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }
        val digitsStart = i
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            val digit = (text[i] - '0').toLong()
            if (!overflow) {
                if (value > (Long.MAX_VALUE - digit) / 10) {
                    overflow = true
                } else {
                    value = value * 10 + digit
                }
            }
            i++
        }
        if (i == digitsStart) return null
        if (overflow) {
            value = if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            return value to i
        }
        return (if (negative) -value else value) to i
    }

    private fun isCWhiteSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r'
}
