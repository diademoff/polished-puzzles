package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `bridges.c`.
 *
 * Bridges is a grid puzzle: each number is an island, and the player
 * joins pairs of islands with one to `maxb` straight bridges. Bridges
 * may not cross, and when loops are disabled the finished graph must be
 * a tree.
 *
 * The generator, the solver, and the description encoding are all part
 * of the contract. The generator places islands from the random stream
 * and gates every board on the solver at `difficulty-1` and
 * `difficulty`, so any change to the deduction order changes every
 * description at Medium and Hard. The island array order is creation
 * order during generation and row-major after `newState`; `AUXINFO` and
 * the hints are ordered by it.
 *
 * `solve_game` reads the first state, because `midend_solve` passes
 * `states[0]`, so this port keeps the decoded initial board in [Shared]
 * and executes `AUXINFO` on that board, not on the current one.
 *
 * There is no `UI` record, no blitter, no thick line, and no print path.
 * The completion flash is not ported: the seam has no flash time.
 */
object Bridges : Game {

    private const val MAX_BRIDGES = 4
    private const val PREFERRED_TILE_SIZE = 24
    private const val MAX_NEWISLAND_TRIES = 50
    private const val MIN_SENSIBLE_ISLANDS = 3

    private const val COL_BACKGROUND = 0
    private const val COL_FOREGROUND = 1
    private const val COL_HIGHLIGHT = 2
    private const val COL_LOWLIGHT = 3
    private const val COL_SELECTED = 4
    private const val COL_MARK = 5
    private const val COL_HINT = 6
    private const val COL_GRID = 7
    private const val COL_WARNING = 8
    private const val COL_CURSOR = 9
    private const val NCOLOURS = 10

    private const val G_ISLAND = 0x0001
    private const val G_LINEV = 0x0002
    private const val G_LINEH = 0x0004
    private const val G_LINE = G_LINEV or G_LINEH
    private const val G_MARKV = 0x0008
    private const val G_MARKH = 0x0010
    private const val G_MARK = G_MARKV or G_MARKH
    private const val G_NOLINEV = 0x0020
    private const val G_NOLINEH = 0x0040
    private const val G_NOLINE = G_NOLINEV or G_NOLINEH
    private const val G_WARN = 0x0080
    private const val G_SWEEP = 0x1000

    /* The packed 28-bit draw word, as `bridges.c:2285-2320`. */
    private const val DL_COUNTMASK = 0x07L
    private const val DL_COUNT_CROSS = 0x06L
    private const val DL_COUNT_HINT = 0x07L
    private const val DL_COLMASK = 0x18L
    private const val DL_COL_NORMAL = 0x00L
    private const val DL_COL_WARNING = 0x08L
    private const val DL_COL_FLASH = 0x10L
    private const val DL_COL_SELECTED = 0x18L
    private const val DL_LOCK = 0x20L
    private const val DL_MASK = 0x3FL
    private const val DI_COLMASK = 0x03L
    private const val DI_COL_NORMAL = 0x00L
    private const val DI_COL_FLASH = 0x01L
    private const val DI_COL_WARNING = 0x02L
    private const val DI_COL_SELECTED = 0x03L
    private const val DI_BGMASK = 0x0CL
    private const val DI_BG_NO_ISLAND = 0x00L
    private const val DI_BG_NORMAL = 0x04L
    private const val DI_BG_MARK = 0x08L
    private const val DI_BG_CURSOR = 0x0CL
    private const val DI_MASK = 0x0FL
    private const val D_I_ISLAND_SHIFT = 0
    private const val D_I_LINE_SHIFT_L = 4
    private const val D_I_LINE_SHIFT_R = 10
    private const val D_I_LINE_SHIFT_U = 16
    private const val D_I_LINE_SHIFT_D = 24
    private const val D_L_ISLAND_SHIFT_L = 0
    private const val D_L_ISLAND_SHIFT_R = 4
    private const val D_L_ISLAND_SHIFT_U = 8
    private const val D_L_ISLAND_SHIFT_D = 12
    private const val D_L_LINE_SHIFT_H = 16
    private const val D_L_LINE_SHIFT_V = 22

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val islands: Int,
        val expansion: Int,
        val maxb: Int,
        val allowloops: Boolean,
        val difficulty: Int,
    ) : Game.Params {
        override fun copy(): Game.Params =
            Params(w, h, islands, expansion, maxb, allowloops, difficulty)
    }

    /** One direction from an island: the adjacent cell and the gap. */
    data class Point(
        val x: Int,
        val y: Int,
        val dx: Int,
        val dy: Int,
        var off: Int,
    )

    /** `struct island`: position, clue, and the four orthogonal directions. */
    class Island(
        var x: Int,
        var y: Int,
        var count: Int,
        val points: MutableList<Point>,
    ) {
        internal fun deepCopy(): Island =
            Island(x, y, count, points.mapTo(ArrayList()) { it.copy() })

        override fun equals(other: Any?): Boolean =
            other is Island &&
                x == other.x &&
                y == other.y &&
                count == other.count &&
                points == other.points

        override fun hashCode(): Int =
            31 * (31 * (31 * x + y) + count) + points.hashCode()
    }

    /**
     * The refcounted solver scratch. C shares one object between every
     * state of a game; every use reinitializes it, so the sharing is not
     * observable, but the port keeps the same shape.
     */
    class SolverScratch internal constructor(size: Int) {
        internal val dsf: Dsf = Dsf.new(size)
        internal val tmpDsf: Dsf = Dsf.new(size)
    }

    /**
     * The data shared by every state of one game. [capture] stores the
     * initial board for `solve_game`; the generator never captures.
     */
    class Shared internal constructor(val params: Params) {
        private var initialGrid: IntArray? = null
        private var initialIslands: List<Island>? = null

        internal fun capture(state: State) {
            initialGrid = state.grid.copyOf()
            initialIslands = state.islands.map { it.deepCopy() }
        }

        internal fun buildInitial(solver: SolverScratch): State {
            val grid = checkNotNull(initialGrid) { "the initial board is not captured" }
            val stored = checkNotNull(initialIslands) { "the initial board is not captured" }
            val w = params.w
            val h = params.h
            val wh = w * h
            val islands = stored.mapTo(ArrayList()) { it.deepCopy() }
            val gridi = IntArray(wh) { -1 }
            for ((index, island) in islands.withIndex()) {
                gridi[island.y * w + island.x] = index
            }
            val state = State(
                params = params,
                shared = this,
                grid = grid.copyOf(),
                islandList = islands,
                gridi = gridi,
                possv = IntArray(wh),
                possh = IntArray(wh),
                lines = IntArray(wh),
                maxv = IntArray(wh) { params.maxb },
                maxh = IntArray(wh) { params.maxb },
                completed = false,
                solved = false,
                solver = solver,
            )
            mapUpdatePossibles(state)
            return state
        }
    }

    /**
     * `struct game_state`. [grid] and the `wha` arrays are per state;
     * [solver] is shared. [completed] is sticky along a forward branch,
     * and [solved] records an `S` move.
     */
    class State internal constructor(
        val params: Params,
        internal val shared: Shared,
        val grid: IntArray,
        internal val islandList: MutableList<Island>,
        val gridi: IntArray,
        val possv: IntArray,
        val possh: IntArray,
        val lines: IntArray,
        val maxv: IntArray,
        val maxh: IntArray,
        var completed: Boolean,
        var solved: Boolean,
        internal val solver: SolverScratch,
    ) : Game.State {

        val width: Int get() = params.w
        val height: Int get() = params.h
        val maxb: Int get() = params.maxb
        val allowloops: Boolean get() = params.allowloops
        val islands: List<Island> get() = islandList

        fun islandAt(x: Int, y: Int): Island? {
            val index = gridi[y * width + x]
            return if (index < 0) null else islandList[index]
        }

        override fun copy(): Game.State = State(
            params,
            shared,
            grid.copyOf(),
            islandList.mapTo(ArrayList()) { it.deepCopy() },
            gridi.copyOf(),
            possv.copyOf(),
            possh.copyOf(),
            lines.copyOf(),
            maxv.copyOf(),
            maxh.copyOf(),
            completed,
            solved,
            solver,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                grid.contentEquals(other.grid) &&
                islandList == other.islandList &&
                possv.contentEquals(other.possv) &&
                possh.contentEquals(other.possh) &&
                lines.contentEquals(other.lines) &&
                maxv.contentEquals(other.maxv) &&
                maxh.contentEquals(other.maxh) &&
                completed == other.completed &&
                solved == other.solved

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + islandList.hashCode()
            result = 31 * result + maxv.contentHashCode()
            result = 31 * result + maxh.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + solved.hashCode()
            return result
        }
    }

    /** `struct game_ui`. The dead `todraw` field is omitted. */
    class Ui : Game.Ui {
        var dragxSrc: Int = -1
        var dragySrc: Int = -1
        var dragxDst: Int = -1
        var dragyDst: Int = -1
        var dragging: Boolean = false
        var dragIsNoline: Boolean = false
        var nlines: Int = 0

        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false

        /** `show_hints`: the one preference. It is not serialized. */
        var showHints: Boolean = false
    }

    /** The solver result with the two C error strings. */
    data class SolveAttempt(val move: String?, val error: String?)

    /**
     * Internal retry diagnostics. They do not change generation; they
     * only count the branches a test must name.
     */
    internal data class GenerationStats(
        var attempts: Int = 0,
        var oneIsland: Int = 0,
        var sides: Int = 0,
        var tooEasy: Int = 0,
        var notSolvable: Int = 0,
    )

    internal data class Generated(val description: Description, val stats: GenerationStats)

    /** `struct game_drawstate`. The dead `lv`, `lh`, and `dragging` are omitted. */
    class DrawState internal constructor(
        val tilesize: Int,
        val w: Int,
        val h: Int,
        val grid: LongArray,
        val newgrid: LongArray,
        var started: Boolean,
    ) : Game.DrawState

    private val PRESETS = listOf(
        Params(7, 7, 30, 10, 2, true, 0),
        Params(7, 7, 30, 10, 2, true, 1),
        Params(7, 7, 30, 10, 2, true, 2),
        Params(10, 10, 30, 10, 2, true, 0),
        Params(10, 10, 30, 10, 2, true, 1),
        Params(10, 10, 30, 10, 2, true, 2),
        Params(15, 15, 30, 10, 2, true, 0),
        Params(15, 15, 30, 10, 2, true, 1),
        Params(15, 15, 30, 10, 2, true, 2),
    )

    override val name: String = "Bridges"
    override val backendName: String = "bridges"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = PRESETS[0].copy() as Params

    override fun presets(): List<Preset> = PRESETS.mapIndexed { index, params ->
        val difficulty = when (params.difficulty) {
            0 -> "Easy"
            1 -> "Medium"
            else -> "Hard"
        }
        Preset("${params.w}x${params.h} $difficulty", encodeParams(params, true))
    }

    override fun decodeParams(text: String): Game.Params {
        var params = defaultParams() as Params
        var index = 0

        fun eatNum(): Int {
            val value = cAtoi(text, index)
            while (index < text.length && text[index].isAsciiDigit()) index++
            return value
        }

        params = params.copy(w = eatNum())
        params = params.copy(h = params.w)
        if (index < text.length && text[index] == 'x') {
            index++
            params = params.copy(h = eatNum())
        }
        if (index < text.length && text[index] == 'i') {
            index++
            params = params.copy(islands = eatNum())
        }
        if (index < text.length && text[index] == 'e') {
            index++
            params = params.copy(expansion = eatNum())
        }
        if (index < text.length && text[index] == 'm') {
            index++
            params = params.copy(maxb = eatNum())
        }
        params = params.copy(allowloops = true)
        if (index < text.length && text[index] == 'L') {
            index++
            params = params.copy(allowloops = false)
        }
        if (index < text.length && text[index] == 'd') {
            index++
            params = params.copy(difficulty = eatNum())
        }
        return params
    }

    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return if (full) {
            "${p.w}x${p.h}i${p.islands}e${p.expansion}m${p.maxb}" +
                (if (p.allowloops) "" else "L") + "d${p.difficulty}"
        } else {
            "${p.w}x${p.h}m${p.maxb}" + (if (p.allowloops) "" else "L")
        }
    }

    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 3 || p.h < 3) return "Width and height must be at least 3"
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.maxb < 1 || p.maxb > MAX_BRIDGES) return "Too many bridges."
        if (full) {
            if (p.islands <= 0 || p.islands > 30) {
                return "%age of island squares must be between 1% and 30%"
            }
            if (p.expansion < 0 || p.expansion > 100) {
                return "Expansion factor must be between 0 and 100"
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Description.
    // ------------------------------------------------------------------

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val w = p.w
        val wh = w * p.h
        val lastRow = BooleanArray(w)
        var i = 0
        var islandCount = 0
        var index = 0

        while (i < wh) {
            if (index >= desc.length) return "Game description shorter than expected"
            val c = desc[index]
            when {
                c in '1'..'9' || c in 'A'..'G' -> {
                    islandCount++
                    if ((i % w > 0 && lastRow[i % w - 1]) || lastRow[i % w]) {
                        return "Game description contains joined islands"
                    }
                    lastRow[i % w] = true
                    index++
                }

                c in 'a'..'z' -> {
                    val run = c - 'a' + 1
                    for (j in 0 until run) lastRow[(i + j) % w] = false
                    i += run - 1
                    index++
                }

                else -> return "Game description contains unexpected character"
            }
            i++
        }
        if (index != desc.length || i > wh) return "Game description longer than expected"
        if (islandCount < 2) return "Game description has too few islands"
        return null
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val state = newStateInternal(p)
        var run = 0
        var index = 0
        for (y in 0 until p.h) {
            for (x in 0 until p.w) {
                var c = '\u0000'
                if (run == 0) {
                    require(index < desc.length) { "bridges: the description is too short" }
                    c = desc[index++]
                    require(c != 'S') { "bridges: a description cell is 'S'" }
                    if (c in 'a'..'z') run = c - 'a' + 1
                }
                if (run > 0) {
                    c = 'S'
                    run--
                }
                when (c) {
                    in '1'..'9' -> islandAdd(state, x, y, c - '0')
                    in 'A'..'G' -> islandAdd(state, x, y, c - 'A' + 10)
                    'S' -> {}
                    else -> require(false) { "bridges: malformed description character '$c'" }
                }
            }
        }
        require(index == desc.length) { "bridges: the description is too long" }
        mapFindOrthogonal(state)
        mapUpdatePossibles(state)
        state.shared.capture(state)
        return state
    }

    private fun newStateInternal(params: Params): State {
        val wh = params.w * params.h
        return State(
            params = params,
            shared = Shared(params),
            grid = IntArray(wh),
            islandList = ArrayList(),
            gridi = IntArray(wh) { -1 },
            possv = IntArray(wh),
            possh = IntArray(wh),
            lines = IntArray(wh),
            maxv = IntArray(wh) { params.maxb },
            maxh = IntArray(wh) { params.maxb },
            completed = false,
            solved = false,
            solver = SolverScratch(wh),
        )
    }

    /** `encode_game`. */
    internal fun encodeGame(state: State): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder(w * h + 1)
        var run = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val island = state.islandAt(x, y)
                if (island != null) {
                    if (run > 0) {
                        out.append('a' - 1 + run)
                        run = 0
                    }
                    if (island.count < 10) {
                        out.append('0' + island.count)
                    } else {
                        out.append('A' + (island.count - 10))
                    }
                } else {
                    if (run == 26) {
                        out.append('a' - 1 + run)
                        run = 0
                    }
                    run++
                }
            }
        }
        if (run > 0) out.append('a' - 1 + run)
        return out.toString()
    }

    /** `game_state_diff`. */
    internal fun gameStateDiff(src: State, dest: State): String {
        val out = StringBuilder("S")
        check(src.islands.size == dest.islands.size)
        for (i in src.islands.indices) {
            val sourceIsland = src.islands[i]
            val destIsland = dest.islands[i]
            check(sourceIsland.x == destIsland.x && sourceIsland.y == destIsland.y)
            check(sourceIsland.points.size == destIsland.points.size)
            for (d in sourceIsland.points.indices) {
                val point = sourceIsland.points[d]
                if (point.dx == -1 || point.dy == -1) continue
                val x = point.x
                val y = point.y
                val gline = if (point.dx != 0) G_LINEH else G_LINEV
                val nline = if (point.dx != 0) G_NOLINEH else G_NOLINEV
                val destPoint = destIsland.points[d]
                val orthX = destIsland.x + destPoint.off * destPoint.dx
                val orthY = destIsland.y + destPoint.off * destPoint.dy
                val orthIndex = dest.gridi[orthY * dest.width + orthX]
                check(orthIndex >= 0)
                val destCount = gridCount(dest, x, y, gline)
                if (gridCount(src, x, y, gline) != destCount) {
                    val orth = dest.islands[orthIndex]
                    out.append(";L${sourceIsland.x},${sourceIsland.y},${orth.x},${orth.y},$destCount")
                }
                if ((src.grid[y * src.width + x] and nline) !=
                    (dest.grid[y * dest.width + x] and nline)
                ) {
                    val orth = dest.islands[orthIndex]
                    out.append(";N${sourceIsland.x},${sourceIsland.y},${orth.x},${orth.y}")
                }
            }
            if ((src.grid[sourceIsland.y * src.width + sourceIsland.x] and G_MARK) !=
                (dest.grid[destIsland.y * dest.width + destIsland.x] and G_MARK)
            ) {
                out.append(";M${sourceIsland.x},${sourceIsland.y}")
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Island and map helpers.
    // ------------------------------------------------------------------

    private fun gridIndex(state: State, x: Int, y: Int): Int = y * state.width + x

    private fun gridAt(state: State, x: Int, y: Int): Int =
        state.grid[y * state.width + x]

    private fun inGrid(state: State, x: Int, y: Int): Boolean =
        x >= 0 && x < state.width && y >= 0 && y < state.height

    private fun gridCount(state: State, x: Int, y: Int, flag: Int): Int {
        val index = gridIndex(state, x, y)
        return if (state.grid[index] and flag != 0) state.lines[index] else 0
    }

    private fun possibles(state: State, dx: Int, x: Int, y: Int): Int =
        if (dx != 0) state.possh[gridIndex(state, x, y)] else state.possv[gridIndex(state, x, y)]

    private fun maximum(state: State, dx: Int, x: Int, y: Int): Int =
        if (dx != 0) state.maxh[gridIndex(state, x, y)] else state.maxv[gridIndex(state, x, y)]

    private fun islandSetSurrounds(island: Island, state: State) {
        island.points.clear()
        fun add(condition: Boolean, dx: Int, dy: Int) {
            if (!condition) return
            island.points.add(Point(island.x + dx, island.y + dy, dx, dy, 0))
        }
        add(island.x > 0, -1, 0)
        add(island.x < state.width - 1, 1, 0)
        add(island.y > 0, 0, -1)
        add(island.y < state.height - 1, 0, 1)
    }

    private fun islandAdd(state: State, x: Int, y: Int, count: Int): Island {
        val index = gridIndex(state, x, y)
        check(state.grid[index] and G_ISLAND == 0)
        state.grid[index] = state.grid[index] or G_ISLAND
        val island = Island(x, y, count, ArrayList())
        islandSetSurrounds(island, state)
        state.islandList.add(island)
        state.gridi[index] = state.islandList.size - 1
        return island
    }

    private fun islandFindOrthogonal(state: State, island: Island) {
        for (point in island.points) {
            var x = island.x + point.dx
            var y = island.y + point.dy
            var off = 1
            point.off = 0
            while (inGrid(state, x, y)) {
                if (gridAt(state, x, y) and G_ISLAND != 0) {
                    point.off = off
                    break
                }
                off++
                x += point.dx
                y += point.dy
            }
        }
    }

    private fun islandHasBridge(state: State, island: Island, direction: Int): Boolean {
        val point = island.points[direction]
        val gline = if (point.dx != 0) G_LINEH else G_LINEV
        return gridAt(state, point.x, point.y) and gline != 0
    }

    private fun islandFindConnection(state: State, island: Island, direction: Int): Island? {
        val point = island.points[direction]
        if (point.off == 0) return null
        val gline = if (point.dx != 0) G_LINEH else G_LINEV
        if (gridAt(state, point.x, point.y) and gline == 0) return null
        val x = island.x + point.off * point.dx
        val y = island.y + point.off * point.dy
        val index = state.gridi[y * state.width + x]
        check(index >= 0)
        return state.islandList[index]
    }

    /** `island_join`; `n = -1` flips the no-line flags. */
    private fun islandJoin(state: State, i1: Island, i2: Island, n: Int, isMax: Boolean) {
        check(n >= -1 && n <= state.maxb)
        if (i1.x == i2.x) {
            val x = i1.x
            val start: Int
            val end: Int
            if (i1.y < i2.y) {
                start = i1.y + 1
                end = i2.y - 1
            } else {
                start = i2.y + 1
                end = i1.y - 1
            }
            for (y in start..end) {
                val index = gridIndex(state, x, y)
                if (isMax) {
                    state.maxv[index] = n
                } else {
                    when {
                        n < 0 -> state.grid[index] = state.grid[index] xor G_NOLINEV
                        n == 0 -> state.grid[index] = state.grid[index] and G_LINEV.inv()
                        else -> {
                            state.grid[index] = state.grid[index] or G_LINEV
                            state.lines[index] = n
                        }
                    }
                }
            }
        } else if (i1.y == i2.y) {
            val y = i1.y
            val start: Int
            val end: Int
            if (i1.x < i2.x) {
                start = i1.x + 1
                end = i2.x - 1
            } else {
                start = i2.x + 1
                end = i1.x - 1
            }
            for (x in start..end) {
                val index = gridIndex(state, x, y)
                if (isMax) {
                    state.maxh[index] = n
                } else {
                    when {
                        n < 0 -> state.grid[index] = state.grid[index] xor G_NOLINEH
                        n == 0 -> state.grid[index] = state.grid[index] and G_LINEH.inv()
                        else -> {
                            state.grid[index] = state.grid[index] or G_LINEH
                            state.lines[index] = n
                        }
                    }
                }
            }
        } else {
            error("island_join: islands not orthogonal")
        }
    }

    private fun islandCountBridges(state: State, island: Island): Int {
        var count = 0
        for (point in island.points) {
            val gline = if (point.dx != 0) G_LINEH else G_LINEV
            count += gridCount(state, point.x, point.y, gline)
        }
        return count
    }

    private fun islandAdjSpace(
        state: State,
        island: Island,
        marks: Boolean,
        missing: Int,
        direction: Int,
    ): Int {
        val point = island.points[direction]
        val gline = if (point.dx != 0) G_LINEH else G_LINEV
        if (marks) {
            val mline = if (point.dx != 0) G_MARKH else G_MARKV
            if (gridAt(state, point.x, point.y) and mline != 0) return 0
        }
        var poss = possibles(state, point.dx, point.x, point.y)
        poss = minOf(poss, missing)
        val curr = gridCount(state, point.x, point.y, gline)
        poss = minOf(poss, maximum(state, point.dx, point.x, point.y) - curr)
        return poss
    }

    private fun islandCountSpaces(state: State, island: Island, marks: Boolean): Int {
        val missing = island.count - islandCountBridges(state, island)
        if (missing < 0) return 0
        var count = 0
        for (i in island.points.indices) {
            count += islandAdjSpace(state, island, marks, missing, i)
        }
        return count
    }

    private fun islandIsAdj(state: State, island: Island, direction: Int): Int {
        val point = island.points[direction]
        val mline = if (point.dx != 0) G_MARKH else G_MARKV
        val gline = if (point.dx != 0) G_LINEH else G_LINEV
        return if (gridAt(state, point.x, point.y) and mline != 0) {
            gridCount(state, point.x, point.y, gline)
        } else {
            possibles(state, point.dx, point.x, point.y)
        }
    }

    private fun islandCountAdj(state: State, island: Island): Int {
        var count = 0
        for (i in island.points.indices) {
            if (islandIsAdj(state, island, i) != 0) count++
        }
        return count
    }

    private fun islandToggleMark(state: State, island: Island) {
        val index = gridIndex(state, island.x, island.y)
        state.grid[index] = state.grid[index] xor G_MARK

        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val cell = gridIndex(state, x, y)
                if (state.grid[cell] and G_ISLAND == 0) {
                    state.grid[cell] = state.grid[cell] and G_MARK.inv()
                }
            }
        }

        for (loop in state.islands) {
            if (gridAt(state, loop.x, loop.y) and G_MARK == 0) continue
            for (point in loop.points) {
                if (point.off == 0) continue
                check(point.off > 1)
                for (o in 1 until point.off) {
                    val cell = gridIndex(
                        state,
                        loop.x + point.dx * o,
                        loop.y + point.dy * o,
                    )
                    state.grid[cell] = state.grid[cell] or
                        (if (point.dy != 0) G_MARKV else G_MARKH)
                }
            }
        }
    }

    private fun islandImpossible(state: State, island: Island, strict: Boolean): Boolean {
        val curr = islandCountBridges(state, island)
        val nspc = island.count - curr
        if (nspc < 0) return true
        if (curr + islandCountSpaces(state, island, false) < island.count) return true
        if (strict && curr < island.count) return true

        var surroundingSpaces = 0
        for (i in island.points.indices) {
            val point = island.points[i]
            if (point.off == 0) continue
            val poss = possibles(state, point.dx, point.x, point.y)
            if (poss == 0) continue
            val orthX = island.x + point.off * point.dx
            val orthY = island.y + point.off * point.dy
            val orthIndex = state.gridi[orthY * state.width + orthX]
            check(orthIndex >= 0)
            val orth = state.islandList[orthIndex]
            val ifree = orth.count - islandCountBridges(state, orth)
            if (ifree > 0) {
                val bmax = maximum(state, point.dx, point.x, point.y)
                val bcurr = gridCount(
                    state,
                    point.x,
                    point.y,
                    if (point.dx != 0) G_LINEH else G_LINEV,
                )
                check(bcurr <= bmax)
                surroundingSpaces += minOf(ifree, bmax - bcurr)
            }
        }
        return surroundingSpaces < nspc
    }

    private fun mapUpdatePossibles(state: State) {
        val w = state.width
        val h = state.height

        for (x in 0 until w) {
            var index = x
            var start = -1
            var end = -1
            var blocked = false
            var maxb = state.params.maxb
            var y = 0
            while (y < h) {
                val islandIndex = state.gridi[index]
                if (islandIndex >= 0) {
                    maxb = state.islandList[islandIndex].count
                    break
                }
                state.possv[index] = 0
                index += w
                y++
            }
            while (y < h) {
                maxb = minOf(maxb, state.maxv[index])
                val islandIndex = state.gridi[index]
                if (islandIndex >= 0) {
                    val next = minOf(maxb, state.islandList[islandIndex].count)
                    if (start != -1) {
                        for (i in start..end) {
                            state.possv[i * w + x] = if (blocked) 0 else next
                        }
                    }
                    start = y + 1
                    blocked = false
                    maxb = state.islandList[islandIndex].count
                } else {
                    end = y
                    if (state.grid[index] and (G_LINEH or G_NOLINEV) != 0) blocked = true
                }
                index += w
                y++
            }
            if (start != -1) {
                for (i in start..end) state.possv[i * w + x] = 0
            }
        }

        for (y in 0 until h) {
            var index = y * w
            var start = -1
            var end = -1
            var blocked = false
            var maxb = state.params.maxb
            var x = 0
            while (x < w) {
                val islandIndex = state.gridi[index]
                if (islandIndex >= 0) {
                    maxb = state.islandList[islandIndex].count
                    break
                }
                state.possh[index] = 0
                index++
                x++
            }
            while (x < w) {
                maxb = minOf(maxb, state.maxh[index])
                val islandIndex = state.gridi[index]
                if (islandIndex >= 0) {
                    val next = minOf(maxb, state.islandList[islandIndex].count)
                    if (start != -1) {
                        for (i in start..end) {
                            state.possh[y * w + i] = if (blocked) 0 else next
                        }
                    }
                    start = x + 1
                    blocked = false
                    maxb = state.islandList[islandIndex].count
                } else {
                    end = x
                    if (state.grid[index] and (G_LINEV or G_NOLINEH) != 0) blocked = true
                }
                index++
                x++
            }
            if (start != -1) {
                for (i in start..end) state.possh[y * w + i] = 0
            }
        }
    }

    private fun mapCount(state: State) {
        for (island in state.islands) {
            island.count = 0
            for (point in island.points) {
                val flag = if (point.x == island.x) G_LINEV else G_LINEH
                if (gridAt(state, point.x, point.y) and flag != 0) {
                    island.count += state.lines[gridIndex(state, point.x, point.y)]
                }
            }
        }
    }

    private fun mapFindOrthogonal(state: State) {
        for (island in state.islands) islandFindOrthogonal(state, island)
    }

    private class BridgesNeighbour(private val state: State) : Neighbour {
        private val neighbours = IntArray(4)
        private var count = 0
        private var index = 0

        override fun next(vertex: Int): Int {
            if (vertex >= 0) {
                val w = state.width
                val x = vertex % w
                val y = vertex / w
                val grid = gridAt(state, x, y)
                count = 0
                index = 0
                val island = state.islandAt(x, y)
                if (island != null) {
                    for (point in island.points) {
                        val gline = if (point.dx != 0) G_LINEH else G_LINEV
                        if (gridAt(state, point.x, point.y) and gline != 0) {
                            neighbours[count++] = point.y * w + point.x
                        }
                    }
                } else {
                    val line = grid and G_LINE
                    if (line == 0) return if (index < count) neighbours[index++] else -1
                    val x1: Int
                    val y1: Int
                    val x2: Int
                    val y2: Int
                    if (line and G_LINEV != 0) {
                        x1 = x
                        x2 = x
                        y1 = y - 1
                        y2 = y + 1
                    } else {
                        x1 = x - 1
                        x2 = x + 1
                        y1 = y
                        y2 = y
                    }
                    check(inGrid(state, x1, y1))
                    check(inGrid(state, x2, y2))
                    val mask = line or G_ISLAND
                    if (gridAt(state, x1, y1) and mask != 0) {
                        neighbours[count++] = y1 * w + x1
                    }
                    if (gridAt(state, x2, y2) and mask != 0) {
                        neighbours[count++] = y2 * w + x2
                    }
                }
            }
            return if (index < count) neighbours[index++] else -1
        }
    }

    /**
     * Test hook: the neighbours of one grid vertex in the order that
     * [BridgesNeighbour] returns them. The order decides the `findloop`
     * DFS forest, so the engine test pins it.
     */
    internal fun neighbourOrder(state: State, x: Int, y: Int): List<Int> {
        val neighbour = BridgesNeighbour(state)
        val order = ArrayList<Int>(4)
        var v = neighbour.next(y * state.width + x)
        while (v >= 0) {
            order.add(v)
            v = neighbour.next(-1)
        }
        return order
    }

    private fun mapHasLoops(state: State, mark: Boolean): Boolean {
        val findLoop = FindLoopState(state.width * state.height)
        val neighbour = BridgesNeighbour(state)
        val found = findLoop.run(neighbour)
        if (mark) {
            for (y in 0 until state.height) {
                for (x in 0 until state.width) {
                    val u = y * state.width + x
                    var v = neighbour.next(u)
                    while (v >= 0) {
                        if (findLoop.isLoopEdge(u, v)) {
                            state.grid[gridIndex(state, x, y)] =
                                state.grid[gridIndex(state, x, y)] or G_WARN
                        }
                        v = neighbour.next(-1)
                    }
                }
            }
        }
        return found
    }

    private fun mapGroup(state: State) {
        val dsf = state.solver.dsf
        dsf.reinit()
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = gridIndex(state, x, y)
                state.grid[index] = state.grid[index] and (G_SWEEP or G_WARN).inv()
                val islandIndex = state.gridi[index]
                if (islandIndex < 0) continue
                val island = state.islandList[islandIndex]
                val d1 = index
                for (i in island.points.indices) {
                    val point = island.points[i]
                    if (point.dx == -1 || point.dy == -1) continue
                    val join = islandFindConnection(state, island, i) ?: continue
                    val d2 = join.y * state.width + join.x
                    /*
                     * A loop is not an error here. The merge of the side
                     * that makes it still happens, as in C.
                     */
                    dsf.equivalent(d1, d2)
                    for (x2 in x..join.x) {
                        for (y2 in y..join.y) {
                            val cell = y2 * state.width + x2
                            if (d1 != cell) dsf.merge(d1, cell)
                        }
                    }
                }
            }
        }
    }

    private fun mapGroupCheck(
        state: State,
        canon: Int,
        warn: Boolean,
        nislandsOut: IntArray? = null,
    ): Boolean {
        val dsf = state.solver.dsf
        var nislands = 0
        var allfull = true
        for (island in state.islands) {
            if (dsf.canonify(island.y * state.width + island.x) != canon) continue
            state.grid[gridIndex(state, island.x, island.y)] =
                state.grid[gridIndex(state, island.x, island.y)] or G_SWEEP
            nislands++
            if (islandCountBridges(state, island) != island.count) allfull = false
        }
        if (warn && allfull && nislands != state.islands.size) {
            for (x in 0 until state.width) {
                for (y in 0 until state.height) {
                    if (dsf.canonify(y * state.width + x) == canon) {
                        val index = gridIndex(state, x, y)
                        state.grid[index] = state.grid[index] or G_WARN
                    }
                }
            }
        }
        nislandsOut?.set(0, nislands)
        return allfull
    }

    private fun mapGroupFull(state: State, ngroupsOut: IntArray): Boolean {
        val dsf = state.solver.dsf
        var ngroups = 0
        var anyfull = false
        for (island in state.islands) {
            if (gridAt(state, island.x, island.y) and G_SWEEP != 0) continue
            ngroups++
            if (mapGroupCheck(state, dsf.canonify(island.y * state.width + island.x), true)) {
                anyfull = true
            }
        }
        ngroupsOut[0] = ngroups
        return anyfull
    }

    private fun mapCheck(state: State): Boolean {
        if (!state.allowloops) {
            if (mapHasLoops(state, true)) return false
        }
        mapGroup(state)
        val ngroups = IntArray(1)
        if (mapGroupFull(state, ngroups)) {
            if (ngroups[0] == 1) return true
        }
        return false
    }

    private fun mapClear(state: State) {
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = gridIndex(state, x, y)
                state.grid[index] = state.grid[index] and G_ISLAND
            }
        }
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    private fun solveJoin(state: State, island: Island, direction: Int, n: Int, isMax: Boolean) {
        val point = island.points[direction]
        val orthX = island.x + point.off * point.dx
        val orthY = island.y + point.off * point.dy
        val orthIndex = state.gridi[orthY * state.width + orthX]
        check(orthIndex >= 0)
        val orth = state.islandList[orthIndex]
        islandJoin(state, island, orth, n, isMax)
        if (n > 0 && !isMax) {
            val d1 = island.y * state.width + island.x
            val d2 = orth.y * state.width + orth.x
            if (!state.solver.dsf.equivalent(d1, d2)) state.solver.dsf.merge(d1, d2)
        }
    }

    private fun solveFillOne(state: State, island: Island): Int {
        var added = 0
        for (i in island.points.indices) {
            if (islandIsAdj(state, island, i) != 0) {
                if (!islandHasBridge(state, island, i)) {
                    solveJoin(state, island, i, 1, false)
                    added++
                }
            }
        }
        return added
    }

    private fun solveFill(state: State, island: Island): Int {
        var added = 0
        val missing = island.count - islandCountBridges(state, island)
        if (missing < 0) return 0
        for (i in island.points.indices) {
            val point = island.points[i]
            val nnew = islandAdjSpace(state, island, true, missing, i)
            if (nnew != 0) {
                val ncurr = gridCount(
                    state,
                    point.x,
                    point.y,
                    if (point.dx != 0) G_LINEH else G_LINEV,
                )
                solveJoin(state, island, i, nnew + ncurr, false)
                added += nnew
            }
        }
        return added
    }

    /** Returns -1 on failure, 1 when it changed something, else 0. */
    private fun solveIslandStage1(state: State, island: Island): Int {
        val bridges = islandCountBridges(state, island)
        val nspaces = islandCountSpaces(state, island, true)
        val nadj = islandCountAdj(state, island)
        var didsth = false

        if (bridges > island.count) {
            return -1
        } else if (bridges == island.count) {
            if (gridAt(state, island.x, island.y) and G_MARK == 0) {
                islandToggleMark(state, island)
                didsth = true
            }
        } else if (gridAt(state, island.x, island.y) and G_MARK != 0) {
            return -1
        } else {
            if (island.count == bridges + nspaces) {
                if (solveFill(state, island) > 0) didsth = true
            } else if (island.count > (nadj - 1) * state.maxb) {
                if (solveFillOne(state, island) > 0) didsth = true
            }
        }
        if (didsth) {
            mapUpdatePossibles(state)
            return 1
        }
        return 0
    }

    private fun solveIslandCheckLoop(state: State, island: Island, direction: Int): Boolean {
        if (state.allowloops) return false
        if (islandHasBridge(state, island, direction)) return false
        if (islandIsAdj(state, island, direction) == 0) return false
        val point = island.points[direction]
        val orthX = island.x + point.off * point.dx
        val orthY = island.y + point.off * point.dy
        if (!inGrid(state, orthX, orthY)) return false
        val orthIndex = state.gridi[orthY * state.width + orthX]
        if (orthIndex < 0) return false
        val orth = state.islandList[orthIndex]
        val d1 = island.y * state.width + island.x
        val d2 = orth.y * state.width + orth.x
        return state.solver.dsf.equivalent(d1, d2)
    }

    /** Returns -1 on failure, 1 when it changed something, else 0. */
    private fun solveIslandStage2(state: State, island: Island): Int {
        var navail = 0
        var added = false
        var removed = false

        for (i in island.points.indices) {
            if (solveIslandCheckLoop(state, island, i)) {
                solveJoin(state, island, i, -1, false)
                mapUpdatePossibles(state)
                removed = true
            } else {
                navail += islandIsAdj(state, island, i)
            }
        }

        for (i in island.points.indices) {
            if (!islandHasBridge(state, island, i)) {
                val nadj = islandIsAdj(state, island, i)
                if (nadj > 0 && (navail - nadj) < island.count) {
                    solveJoin(state, island, i, 1, false)
                    added = true
                }
            }
        }
        if (added) mapUpdatePossibles(state)
        return if (added || removed) 1 else 0
    }

    private fun solveIslandSubgroup(
        state: State,
        island: Island,
        direction: Int,
    ): Boolean {
        if (islandCountBridges(state, island) < island.count) return false
        if (direction >= 0) {
            val point = island.points[direction]
            val orthX = island.x + point.off * point.dx
            val orthY = island.y + point.off * point.dy
            val orthIndex = state.gridi[orthY * state.width + orthX]
            check(orthIndex >= 0)
            val orth = state.islandList[orthIndex]
            if (islandCountBridges(state, orth) < orth.count) return false
        }
        val nislands = IntArray(1)
        val canon = state.solver.dsf.canonify(island.y * state.width + island.x)
        if (mapGroupCheck(state, canon, false, nislands)) {
            if (nislands[0] < state.islands.size) return true
        }
        return false
    }

    private fun solveIslandImpossible(state: State): Boolean {
        for (island in state.islands) {
            if (islandImpossible(state, island, false)) return true
        }
        return false
    }

    /** Returns -1 on failure, 1 when it changed something, else 0. */
    private fun solveIslandStage3(state: State, island: Island): Int {
        var didsth = false
        val missing = island.count - islandCountBridges(state, island)
        if (missing <= 0) return 0

        val dsf = state.solver.dsf
        val tmpDsf = state.solver.tmpDsf

        for (i in island.points.indices) {
            val point = island.points[i]
            val spc = islandAdjSpace(state, island, true, missing, i)
            if (spc == 0) continue
            val curr = gridCount(
                state,
                point.x,
                point.y,
                if (point.dx != 0) G_LINEH else G_LINEV,
            )
            var maxb = -1
            dsf.copyInto(tmpDsf)
            var n = curr + 1
            while (n <= curr + spc) {
                solveJoin(state, island, i, n, false)
                mapUpdatePossibles(state)
                if (solveIslandSubgroup(state, island, i) ||
                    solveIslandImpossible(state)
                ) {
                    maxb = n - 1
                    break
                }
                n++
            }
            solveJoin(state, island, i, curr, false)
            tmpDsf.copyInto(dsf)

            if (maxb != -1) {
                if (maxb == 0) {
                    solveJoin(state, island, i, -1, false)
                } else {
                    solveJoin(state, island, i, maxb, true)
                }
                didsth = true
            }
            mapUpdatePossibles(state)
        }

        for (i in island.points.indices) {
            var got = false
            val before = IntArray(4)
            var spc = islandAdjSpace(state, island, true, missing, i)
            if (spc == 0) continue

            for (j in island.points.indices) {
                val point = island.points[j]
                before[j] = gridCount(
                    state,
                    point.x,
                    point.y,
                    if (point.dx != 0) G_LINEH else G_LINEV,
                )
            }
            if (before[i] != 0) continue

            dsf.copyInto(tmpDsf)
            for (j in island.points.indices) {
                spc = islandAdjSpace(state, island, true, missing, j)
                if (spc == 0) continue
                if (j == i) continue
                solveJoin(state, island, j, before[j] + spc, false)
            }
            mapUpdatePossibles(state)

            if (solveIslandSubgroup(state, island, -1)) got = true

            for (j in island.points.indices) {
                solveJoin(state, island, j, before[j], false)
            }
            tmpDsf.copyInto(dsf)

            if (got) {
                solveJoin(state, island, i, 1, false)
                didsth = true
            }
            mapUpdatePossibles(state)
        }

        return if (didsth) 1 else 0
    }

    @Suppress("UNUSED_PARAMETER")
    private fun solveSub(state: State, difficulty: Int, depth: Int): Int {
        while (true) {
            var didsth = false

            for (island in state.islands) {
                when (solveIslandStage1(state, island)) {
                    -1 -> return 0
                    1 -> didsth = true
                }
            }
            if (didsth) continue
            if (difficulty < 1) break

            for (island in state.islands) {
                if (gridAt(state, island.x, island.y) and G_MARK != 0) continue
                when (solveIslandStage2(state, island)) {
                    -1 -> return 0
                    1 -> didsth = true
                }
            }
            if (didsth) continue
            if (difficulty < 2) break

            for (island in state.islands) {
                when (solveIslandStage3(state, island)) {
                    -1 -> return 0
                    1 -> didsth = true
                }
            }
            if (didsth) continue
            if (difficulty < 3) break

            break
        }
        return if (mapCheck(state)) 1 else 0
    }

    private fun solveForHint(state: State) {
        mapGroup(state)
        solveSub(state, 10, 0)
    }

    private fun solveFromScratch(state: State, difficulty: Int): Int {
        mapClear(state)
        mapGroup(state)
        mapUpdatePossibles(state)
        return solveSub(state, difficulty, 0)
    }

    /** `game_configure` (`bridges.c:748-794`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Medium:Hard",
                null,
                p.difficulty,
            ),
            ConfigItem.Bool("Allow loops", p.allowloops),
            ConfigItem.Choices(
                "Max. bridges per direction",
                ":1:2:3:4",
                null,
                p.maxb - 1,
            ),
            ConfigItem.Choices(
                "%age of island squares",
                ":5%:10%:15%:20%:25%:30%",
                null,
                p.islands / 5 - 1,
            ),
            ConfigItem.Choices(
                "Expansion factor (%age)",
                ":0%:10%:20%:30%:40%:50%:60%:70%:80%:90%:100%",
                null,
                p.expansion / 10,
            ),
        )
    }

    /** `custom_params` (`bridges.c:796-809`), including the derived fields. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            cAtoi((items[1] as ConfigItem.Text).value, 0),
            ((items[5] as ConfigItem.Choices).selected + 1) * 5,
            (items[6] as ConfigItem.Choices).selected * 10,
            (items[4] as ConfigItem.Choices).selected + 1,
            (items[3] as ConfigItem.Bool).value,
            (items[2] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`bridges.c:2170-2185`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Show possible bridge locations",
                false,
                "show-hints",
            ),
        )

    /** `set_prefs` (`bridges.c:2187-2190`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).showHints = prefs.bool("show-hints")
    }

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDescWithStats(params, random).description

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description = newDescWithStats(params, random, cancel).description

    // `bridges.c` retries the island placement and gates every attempt on the solver with no retry bound; the largest preset is 15x15 and the accepted area is unbounded.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w.toLong() * p.h > 225) Game.SLOW_GENERATION_WARNING else null
    }

    internal fun newDescWithStats(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        val p = params as Params
        val wh = p.w * p.h
        val niReq = maxOf(p.islands * wh / 100, MIN_SENSIBLE_ISLANDS)
        val stats = GenerationStats()

        while (true) {
            cancel.check()
            stats.attempts++
            val tobuild = newStateInternal(p)
            var x = random.upto(p.w.toLong()).toInt()
            var y = random.upto(p.h.toLong()).toInt()
            islandAdd(tobuild, x, y, 0)
            var niCurr = 1
            var niBad = 0

            while (niCurr < niReq) {
                val i = random.upto(tobuild.islands.size.toLong()).toInt()
                var island = tobuild.islands[i]
                val j = random.upto(island.points.size.toLong()).toInt()
                val dx = island.points[j].x - island.x
                val dy = island.points[j].y - island.y

                var joinx = -1
                var joiny = -1
                val minx = island.x + 2 * dx
                val miny = island.y + 2 * dy
                var cx = island.x + dx
                var cy = island.y + dy
                var bad = gridAt(tobuild, cx, cy) and (G_LINEV or G_LINEH) != 0
                var maxx = 0
                var maxy = 0

                if (!bad) {
                    while (true) {
                        if (!inGrid(tobuild, cx, cy)) {
                            maxx = cx - dx
                            maxy = cy - dy
                            break
                        }
                        if (gridAt(tobuild, cx, cy) and G_ISLAND != 0) {
                            joinx = cx
                            joiny = cy
                            maxx = cx - 2 * dx
                            maxy = cy - 2 * dy
                            break
                        } else if (gridAt(tobuild, cx, cy) and (G_LINEV or G_LINEH) != 0) {
                            maxx = cx - dx
                            maxy = cy - dy
                            break
                        }
                        cx += dx
                        cy += dy
                    }
                }

                var joined = false
                if (!bad && p.allowloops && joinx != -1 && joiny != -1) {
                    if (random.upto(100) < p.expansion.toLong()) {
                        val joinIndex = tobuild.gridi[joiny * p.w + joinx]
                        val is2 = tobuild.islandList[joinIndex]
                        islandJoin(
                            tobuild,
                            island,
                            is2,
                            random.upto(p.maxb.toLong()).toInt() + 1,
                            false,
                        )
                        joined = true
                    }
                }

                if (!joined) {
                    val diffx = (maxx - minx) * dx
                    val diffy = (maxy - miny) * dy
                    if (!bad && diffx >= 0 && diffy >= 0) {
                        val newx: Int
                        val newy: Int
                        if (random.upto(100) < p.expansion.toLong()) {
                            newx = maxx
                            newy = maxy
                        } else {
                            newx = minx + random.upto(diffx + 1L).toInt() * dx
                            newy = miny + random.upto(diffy + 1L).toInt() * dy
                        }
                        val adjacent =
                            (inGrid(tobuild, newx + dy, newy + dx) &&
                                gridAt(tobuild, newx + dy, newy + dx) and G_ISLAND != 0) ||
                                (inGrid(tobuild, newx - dy, newy - dx) &&
                                    gridAt(tobuild, newx - dy, newy - dx) and G_ISLAND != 0)
                        if (!adjacent) {
                            val is2 = islandAdd(tobuild, newx, newy, 0)
                            island = tobuild.islands[i]
                            niCurr++
                            niBad = 0
                            islandJoin(
                                tobuild,
                                island,
                                is2,
                                random.upto(p.maxb.toLong()).toInt() + 1,
                                false,
                            )
                            joined = true
                        }
                    }
                }

                if (!joined) {
                    niBad++
                    if (niBad > MAX_NEWISLAND_TRIES) break
                }
            }

            if (niCurr == 1) {
                stats.oneIsland++
                continue
            }

            var echeck = 0
            for (cx in 0 until p.w) {
                if (tobuild.islandAt(cx, 0) != null) echeck = echeck or 1
                if (tobuild.islandAt(cx, p.h - 1) != null) echeck = echeck or 2
            }
            for (cy in 0 until p.h) {
                if (tobuild.islandAt(0, cy) != null) echeck = echeck or 4
                if (tobuild.islandAt(p.w - 1, cy) != null) echeck = echeck or 8
            }
            if (echeck != 15) {
                stats.sides++
                continue
            }

            mapCount(tobuild)
            mapFindOrthogonal(tobuild)

            if (p.difficulty > 0) {
                if (niCurr > MIN_SENSIBLE_ISLANDS &&
                    solveFromScratch(tobuild, p.difficulty - 1) > 0
                ) {
                    stats.tooEasy++
                    continue
                }
            }

            if (solveFromScratch(tobuild, p.difficulty) == 0) {
                stats.notSolvable++
                continue
            }

            val ret = encodeGame(tobuild)
            val clean = tobuild.copy() as State
            mapClear(clean)
            mapUpdatePossibles(clean)
            val aux = gameStateDiff(clean, tobuild)
            return Generated(Description(ret, aux), stats)
        }
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    override fun executeMove(state: Game.State, move: String): Game.State? =
        executeMoveState(state as State, move)

    private fun executeMoveState(state: State, move: String): State? {
        if (move.isEmpty()) return null
        val ret = state.copy() as State
        val parser = MoveParser(move)
        var ok = true

        while (ok && parser.index < move.length) {
            val c = move[parser.index++]
            when (c) {
                'S' -> ret.solved = true
                'L' -> {
                    val x1 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val y1 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val x2 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val y2 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val nl = parser.int() ?: return null
                    if (!inGrid(ret, x1, y1) || !inGrid(ret, x2, y2)) return null
                    if (((x1 != x2) && (y1 != y2)) || (x1 == x2 && y1 == y2)) return null
                    val is1 = ret.islandAt(x1, y1) ?: return null
                    val is2 = ret.islandAt(x2, y2) ?: return null
                    if (nl < 0 || nl > state.maxb) return null
                    islandJoin(ret, is1, is2, nl, false)
                }

                'N' -> {
                    val x1 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val y1 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val x2 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val y2 = parser.int() ?: return null
                    if (!inGrid(ret, x1, y1) || !inGrid(ret, x2, y2)) return null
                    if (((x1 != x2) && (y1 != y2)) || (x1 == x2 && y1 == y2)) return null
                    val is1 = ret.islandAt(x1, y1) ?: return null
                    val is2 = ret.islandAt(x2, y2) ?: return null
                    islandJoin(ret, is1, is2, -1, false)
                }

                'M' -> {
                    val x1 = parser.int() ?: return null
                    if (!parser.literal(',')) return null
                    val y1 = parser.int() ?: return null
                    if (!inGrid(ret, x1, y1)) return null
                    val is1 = ret.islandAt(x1, y1) ?: return null
                    islandToggleMark(ret, is1)
                }

                else -> ok = false
            }
            if (!ok) return null
            if (parser.index < move.length) {
                if (move[parser.index] == ';') {
                    parser.index++
                } else {
                    return null
                }
            }
        }

        mapUpdatePossibles(ret)
        if (mapCheck(ret)) ret.completed = true
        return ret
    }

    private class MoveParser(private val text: String) {
        var index = 0

        fun int(): Int? {
            while (index < text.length && text[index].isCWhitespace()) index++
            var sign = 1
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                if (text[index] == '-') sign = -1
                index++
            }
            val start = index
            var value = 0L
            while (index < text.length && text[index] in '0'..'9') {
                val digit = text[index] - '0'
                value = if (value > (Long.MAX_VALUE - digit) / 10) {
                    Long.MAX_VALUE
                } else {
                    value * 10 + digit
                }
                index++
            }
            if (index == start) return null
            val signed = sign * value
            return when {
                signed > Int.MAX_VALUE -> Int.MAX_VALUE
                signed < Int.MIN_VALUE -> Int.MIN_VALUE
                else -> signed.toInt()
            }
        }

        fun literal(c: Char): Boolean {
            if (index < text.length && text[index] == c) {
                index++
                return true
            }
            return false
        }
    }

    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult = interpretMoveState(state as State, ui as Ui, x, y, button, tileSize)

    private fun interpretMoveState(
        state: State,
        ui: Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val gx = fromCoord(x, tileSize)
        val gy = fromCoord(y, tileSize)
        val ggrid = if (inGrid(state, gx, gy)) gridAt(state, gx, gy) else 0
        val shift = button and Buttons.MOD_SHFT != 0
        val control = button and Buttons.MOD_CTRL != 0
        val bare = Buttons.stripModifiers(button)

        if (bare == Buttons.LEFT_BUTTON || bare == Buttons.RIGHT_BUTTON) {
            if (!inGrid(state, gx, gy)) return MoveResult.Unused
            ui.curVisible = false
            if (ggrid and G_ISLAND != 0) {
                ui.dragxSrc = gx
                ui.dragySrc = gy
                return MoveResult.UiUpdate
            }
            return uiCancelDrag(ui)
        } else if (bare == Buttons.LEFT_DRAG || bare == Buttons.RIGHT_DRAG) {
            if (inGrid(state, ui.dragxSrc, ui.dragySrc) &&
                (gx != ui.dragxSrc || gy != ui.dragySrc) &&
                gridAt(state, ui.dragxSrc, ui.dragySrc) and G_MARK == 0
            ) {
                ui.dragging = true
                ui.dragIsNoline = bare == Buttons.RIGHT_DRAG
                updateDragDst(state, ui, x, y, tileSize)
                return MoveResult.UiUpdate
            } else {
                ui.dragxDst = -1
                ui.dragyDst = -1
                return MoveResult.UiUpdate
            }
        } else if (bare == Buttons.LEFT_RELEASE || bare == Buttons.RIGHT_RELEASE) {
            if (ui.dragging) {
                return finishDrag(state, ui)
            } else {
                if (!inGrid(state, ui.dragxSrc, ui.dragySrc) ||
                    gx != ui.dragxSrc || gy != ui.dragySrc
                ) {
                    return uiCancelDrag(ui)
                }
                uiCancelDrag(ui)
                if (!inGrid(state, gx, gy)) return MoveResult.Unused
                if (gridAt(state, gx, gy) and G_ISLAND == 0) return MoveResult.NoEffect
                return MoveResult.Move("M$gx,$gy")
            }
        } else if (bare == 'h'.code || bare == 'H'.code) {
            val solved = state.copy() as State
            solveForHint(solved)
            return MoveResult.Move(gameStateDiff(state, solved))
        } else if (Buttons.isCursorMove(bare)) {
            ui.curVisible = true
            if (control || shift) {
                ui.dragxSrc = ui.curX
                ui.dragySrc = ui.curY
                ui.dragging = true
                ui.dragIsNoline = !control
            }
            if (ui.dragging) {
                var nx = ui.curX
                var ny = ui.curY
                when (bare) {
                    Buttons.CURSOR_UP -> ny = maxOf(ny - 1, 0)
                    Buttons.CURSOR_DOWN -> ny = minOf(ny + 1, state.height - 1)
                    Buttons.CURSOR_LEFT -> nx = maxOf(nx - 1, 0)
                    Buttons.CURSOR_RIGHT -> nx = minOf(nx + 1, state.width - 1)
                }
                if (nx == ui.curX && ny == ui.curY) return MoveResult.NoEffect
                updateDragDst(
                    state,
                    ui,
                    coord(nx, tileSize) + tileSize / 2,
                    coord(ny, tileSize) + tileSize / 2,
                    tileSize,
                )
                return finishDrag(state, ui)
            } else {
                val dx = when (bare) {
                    Buttons.CURSOR_RIGHT -> 1
                    Buttons.CURSOR_LEFT -> -1
                    else -> 0
                }
                val dy = when (bare) {
                    Buttons.CURSOR_DOWN -> 1
                    Buttons.CURSOR_UP -> -1
                    else -> 0
                }
                val dorthx = 1 - kotlin.math.abs(dx)
                val dorthy = 1 - kotlin.math.abs(dy)
                val orthorder = if (bare == Buttons.CURSOR_LEFT || bare == Buttons.CURSOR_UP) 1 else -1
                check(gridAt(state, ui.curX, ui.curY) and G_ISLAND != 0)

                var foundX = -1
                var foundY = -1
                var orth = 0
                outer@ while (true) {
                    var oingrid = false
                    var dir = 1
                    inner@ while (true) {
                        var dingrid = false
                        if (orth > dir) {
                            dir++
                            continue@inner
                        }
                        var nx = ui.curX + dir * dx + orth * dorthx * orthorder
                        var ny = ui.curY + dir * dy + orth * dorthy * orthorder
                        if (inGrid(state, nx, ny)) {
                            dingrid = true
                            oingrid = true
                            if (gridAt(state, nx, ny) and G_ISLAND != 0) {
                                foundX = nx
                                foundY = ny
                                break@outer
                            }
                        }
                        nx = ui.curX + dir * dx - orth * dorthx * orthorder
                        ny = ui.curY + dir * dy - orth * dorthy * orthorder
                        if (inGrid(state, nx, ny)) {
                            dingrid = true
                            oingrid = true
                            if (gridAt(state, nx, ny) and G_ISLAND != 0) {
                                foundX = nx
                                foundY = ny
                                break@outer
                            }
                        }
                        if (!dingrid) break@inner
                        dir++
                    }
                    if (!oingrid) return MoveResult.UiUpdate
                    orth++
                }
                ui.curX = foundX
                ui.curY = foundY
                return MoveResult.UiUpdate
            }
        } else if (bare == Buttons.CURSOR_SELECT || bare == Buttons.CURSOR_SELECT2) {
            if (!ui.curVisible) {
                ui.curVisible = true
                return MoveResult.UiUpdate
            }
            if (ui.dragging || bare == Buttons.CURSOR_SELECT2) {
                uiCancelDrag(ui)
                /*
                 * `ui_cancel_drag` has cleared both destination fields,
                 * so this branch is always taken. The `else` is the dead
                 * branch of `bridges.c:2558-2559`, kept literal.
                 */
                return if (ui.dragxDst == -1 && ui.dragyDst == -1) {
                    MoveResult.Move("M${ui.curX},${ui.curY}")
                } else {
                    MoveResult.UiUpdate
                }
            } else {
                val v = gridAt(state, ui.curX, ui.curY)
                if (v and G_ISLAND != 0) {
                    ui.dragging = true
                    ui.dragxSrc = ui.curX
                    ui.dragySrc = ui.curY
                    ui.dragxDst = -1
                    ui.dragyDst = -1
                    ui.dragIsNoline = bare == Buttons.CURSOR_SELECT2
                    return MoveResult.UiUpdate
                }
            }
        } else if (bare == 'l'.code || bare == 'L'.code) {
            if (!ui.curVisible) ui.curVisible = true
            if (ui.dragging) uiCancelDrag(ui)
            return MoveResult.Move("M${ui.curX},${ui.curY}")
        } else if ((bare in '0'.code..'9'.code) ||
            (bare in 'a'.code..'f'.code) ||
            (bare in 'A'.code..'F'.code)
        ) {
            val number = when {
                bare in '0'.code..'9'.code -> if (bare == '0'.code) 16 else bare - '0'.code
                bare in 'a'.code..'f'.code -> 10 + bare - 'a'.code
                else -> 10 + bare - 'A'.code
            }
            if (!ui.curVisible) {
                ui.curVisible = true
                return MoveResult.UiUpdate
            }
            var bestX = -1
            var bestY = -1
            var bestSqdist = -1
            for (island in state.islands) {
                val dx = island.x - ui.curX
                val dy = island.y - ui.curY
                val sqdist = dx * dx + dy * dy
                if (island.count != number) continue
                if (island.x == ui.curX && island.y == ui.curY) continue
                if (bestSqdist == -1 || sqdist < bestSqdist) {
                    bestX = island.x
                    bestY = island.y
                    bestSqdist = sqdist
                }
            }
            return if (bestX != -1 && bestY != -1) {
                ui.curX = bestX
                ui.curY = bestY
                MoveResult.UiUpdate
            } else {
                MoveResult.NoEffect
            }
        } else if (bare == 'g'.code || bare == 'G'.code) {
            ui.showHints = !ui.showHints
            return MoveResult.UiUpdate
        }

        return MoveResult.Unused
    }

    private fun updateDragDst(state: State, ui: Ui, nx: Int, ny: Int, tileSize: Int) {
        if (ui.dragxSrc == -1 || ui.dragySrc == -1) return
        ui.dragxDst = -1
        ui.dragyDst = -1

        val ox = coord(ui.dragxSrc, tileSize) + tileSize / 2
        val oy = coord(ui.dragySrc, tileSize) + tileSize / 2
        val dx: Int
        val dy: Int
        val gtype: Int
        val ntype: Int
        val mtype: Int
        val maxb: Int
        if (kotlin.math.abs(nx - ox) < kotlin.math.abs(ny - oy)) {
            dx = 0
            dy = if (ny - oy < 0) -1 else 1
            if (!inGrid(state, ui.dragxSrc + dx, ui.dragySrc + dy)) return
            gtype = G_LINEV
            ntype = G_NOLINEV
            mtype = G_MARKV
            maxb = state.maxv[gridIndex(state, ui.dragxSrc + dx, ui.dragySrc + dy)]
        } else {
            dy = 0
            dx = if (nx - ox < 0) -1 else 1
            if (!inGrid(state, ui.dragxSrc + dx, ui.dragySrc + dy)) return
            gtype = G_LINEH
            ntype = G_NOLINEH
            mtype = G_MARKH
            maxb = state.maxh[gridIndex(state, ui.dragxSrc + dx, ui.dragySrc + dy)]
        }
        if (ui.dragIsNoline) {
            // `todraw` is dead; only the destination search below matters.
        } else {
            val step = gridIndex(state, ui.dragxSrc + dx, ui.dragySrc + dy)
            val curr = state.grid[step]
            val currl = state.lines[step]
            if (curr and gtype != 0) {
                ui.nlines = if (currl == maxb) 0 else currl + 1
            } else {
                ui.nlines = 1
            }
        }

        val islandIndex = state.gridi[gridIndex(state, ui.dragxSrc, ui.dragySrc)]
        if (islandIndex < 0) return
        val island = state.islandList[islandIndex]
        for (i in island.points.indices) {
            val point = island.points[i]
            if (point.off == 0) continue
            val cell = gridIndex(state, island.x + dx, island.y + dy)
            val curr = state.grid[cell]
            if (curr and mtype != 0) continue
            if (ui.dragIsNoline) {
                if (curr and gtype != 0) continue
            } else {
                if (possibles(state, dx, island.x + dx, island.y + dy) == 0) continue
                if (curr and ntype != 0) continue
            }
            if (point.dx == dx && point.dy == dy) {
                ui.dragxDst = island.x + point.off * point.dx
                ui.dragyDst = island.y + point.off * point.dy
            }
        }
    }

    private fun finishDrag(state: State, ui: Ui): MoveResult {
        if (ui.dragxSrc == -1 || ui.dragySrc == -1) return MoveResult.NoEffect
        if (ui.dragxDst == -1 || ui.dragyDst == -1) return uiCancelDrag(ui)
        val move = if (ui.dragIsNoline) {
            "N${ui.dragxSrc},${ui.dragySrc},${ui.dragxDst},${ui.dragyDst}"
        } else {
            "L${ui.dragxSrc},${ui.dragySrc},${ui.dragxDst},${ui.dragyDst},${ui.nlines}"
        }
        uiCancelDrag(ui)
        return MoveResult.Move(move)
    }

    private fun uiCancelDrag(ui: Ui): MoveResult {
        ui.dragxSrc = -1
        ui.dragySrc = -1
        ui.dragxDst = -1
        ui.dragyDst = -1
        ui.dragging = false
        return MoveResult.UiUpdate
    }

    // ------------------------------------------------------------------
    // Solve.
    // ------------------------------------------------------------------

    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state, auxInfo).move

    /** `solve_game` with the C error strings (`bridges.c:2703-2729`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state, auxInfo)
        return SolveOutcome(attempt.move, attempt.error)
    }

    /**
     * The solver with the two C error strings. The seam has no error
     * channel, so [solve] returns null and a caller that needs the
     * message uses this helper.
     */
    fun solveAttempt(state: Game.State, auxInfo: String?): SolveAttempt {
        val s = state as State
        val initial = s.shared.buildInitial(s.solver)
        val solved: State
        if (auxInfo != null) {
            solved = executeMoveState(initial, auxInfo)
                ?: return SolveAttempt(
                    null,
                    "Generated aux string is not a valid move (!).",
                )
        } else {
            val candidate = initial.copy() as State
            if (solveFromScratch(candidate, 10) == 0) {
                return SolveAttempt(
                    null,
                    "Game does not have a (non-recursive) solution.",
                )
            }
            solved = candidate
        }
        return SolveAttempt(gameStateDiff(s, solved), null)
    }

    // ------------------------------------------------------------------
    // Status, text, keys, UI.
    // ------------------------------------------------------------------

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** Bridges has no status bar text, as `wants_statusbar` is false. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val s = newState as State
        return s.completed && !s.solved && oldState != null &&
            !(oldState as State).completed
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val out = StringBuilder(s.height * (s.width + 1) + 1)
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val index = gridIndex(s, x, y)
                val grid = s.grid[index]
                val nl = s.lines[index]
                val island = s.islandAt(x, y)
                if (island != null) {
                    out.append('0' + island.count)
                } else if (grid and G_LINEV != 0) {
                    out.append(if (nl > 1) '"' else if (nl == 1) '|' else '!')
                } else if (grid and G_LINEH != 0) {
                    out.append(if (nl > 1) '=' else if (nl == 1) '-' else '~')
                } else {
                    out.append('.')
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
        listOf(
            KeyLabel('G'.code, "Grid"),
            KeyLabel('H'.code, "Solve from here"),
            KeyLabel('L'.code, "Lock", needsArrows = true),
        ),
        ArrowMode.ARROWS_LEFT_RIGHT,
    )

    /** `current_key_label`; the seam has no operation for it. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) return ""
            if (u.dragging || button == Buttons.CURSOR_SELECT2) return "Finished"
            if (inGrid(s, u.curX, u.curY) &&
                gridAt(s, u.curX, u.curY) and G_ISLAND != 0
            ) {
                return "Select"
            }
        }
        return ""
    }

    override fun newUi(state: Game.State): Game.Ui {
        val s = state as State
        val ui = Ui()
        uiCancelDrag(ui)
        if (s.islands.isNotEmpty()) {
            ui.curX = s.islands[0].x
            ui.curY = s.islands[0].y
        }
        ui.curVisible = false
        ui.showHints = false
        return ui
    }

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        return Game.CursorRect(
            coord(u.curX, tileSize),
            coord(u.curY, tileSize),
            tileSize,
            tileSize,
        )
    }

    /**
     * One unit per cell, as `coord` draws it: an island cell names its
     * count and the bridges already on it, and a cell between two
     * islands names the bridge that crosses it.
     *
     * A click on an island is a real move: it toggles the island's mark
     * (`M` in `execute_move`), so the island offers `Mark` or `Unmark`.
     * A bridge is a drag from one island to another, which one unit
     * cannot express, so that move stays on the cursor and the arrow
     * keys: `Select` starts the drag and the next arrow finishes it.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val t = tileSize
        val cells = gridSpeech(
            width = w,
            height = s.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = "",
            text = { x, y ->
                val index = y * w + x
                val island = s.islandAt(x, y)
                val content = if (island != null) {
                    islandSpeech(s, island)
                } else {
                    lineSpeech(s.grid[index], s.lines[index])
                }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                content + cursor
            },
            actions = { x, y ->
                val island = s.islandAt(x, y)
                if (island == null) {
                    emptyList()
                } else {
                    val marked = gridAt(s, x, y) and G_MARK != 0
                    listOf(
                        SpokenAction(
                            if (marked) "Unmark" else "Mark",
                            Buttons.LEFT_BUTTON,
                        ),
                    )
                }
            },
        )
        return cells.capped(cells.units.getOrNull(u.curY * w + u.curX))
    }

    /** `island 3, 2 of 5 bridges` plus the directions that carry one. */
    private fun islandSpeech(s: State, island: Island): String {
        val count = islandCountBridges(s, island)
        val directions = buildList {
            for (point in island.points) {
                val dir = if (point.dx != 0) {
                    if (point.dx > 0) "right" else "left"
                } else {
                    if (point.dy > 0) "down" else "up"
                }
                val gline = if (point.dx != 0) G_LINEH else G_LINEV
                val n = gridCount(s, point.x, point.y, gline)
                if (n > 0) add("$n $dir")
            }
        }
        val bridgeWords = if (directions.isEmpty()) {
            ""
        } else {
            ", " + directions.joinToString(", ")
        }
        val mark = if (gridAt(s, island.x, island.y) and G_MARK != 0) ", marked" else ""
        return "island ${island.count}, $count ${if (count == 1) "bridge" else "bridges"}" +
            bridgeWords + mark
    }

    /** The bridge that crosses a cell, in the words `text_format` uses. */
    private fun lineSpeech(grid: Int, lines: Int): String = when {
        grid and G_LINEV != 0 && lines > 0 -> "vertical bridge $lines"
        grid and G_LINEV != 0 -> "no vertical bridge"
        grid and G_LINEH != 0 && lines > 0 -> "horizontal bridge $lines"
        grid and G_LINEH != 0 -> "no horizontal bridge"
        else -> "empty"
    }

    // ------------------------------------------------------------------
    // Geometry and palette.
    // ------------------------------------------------------------------

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(
            tileSize * p.w + 2 * (tileSize / 2),
            tileSize * p.h + 2 * (tileSize / 2),
        )
    }

    override fun colours(): FloatArray =
        colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        for (i in 0 until 3) {
            ret[COL_FOREGROUND * 3 + i] = 0.0f
            ret[COL_HINT * 3 + i] = ret[COL_LOWLIGHT * 3 + i]
            ret[COL_GRID * 3 + i] =
                (ret[COL_HINT * 3 + i] + ret[COL_BACKGROUND * 3 + i]) * 0.5f
            ret[COL_MARK * 3 + i] = ret[COL_HIGHLIGHT * 3 + i]
        }
        ret[COL_WARNING * 3 + 0] = 1.0f
        ret[COL_WARNING * 3 + 1] = 0.25f
        ret[COL_WARNING * 3 + 2] = 0.25f

        ret[COL_SELECTED * 3 + 0] = 0.25f
        ret[COL_SELECTED * 3 + 1] = 1.00f
        ret[COL_SELECTED * 3 + 2] = 0.25f

        ret[COL_CURSOR * 3 + 0] = minOf(ret[COL_BACKGROUND * 3 + 0] * 1.4f, 1.0f)
        ret[COL_CURSOR * 3 + 1] = ret[COL_BACKGROUND * 3 + 1] * 0.8f
        ret[COL_CURSOR * 3 + 2] = ret[COL_BACKGROUND * 3 + 2] * 0.8f
        return ret
    }

    private fun coord(value: Int, tileSize: Int): Int = value * tileSize + tileSize / 2

    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

    // ------------------------------------------------------------------
    // Drawing.
    // ------------------------------------------------------------------

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val wh = s.width * s.height
        return DrawState(
            tileSize,
            s.width,
            s.height,
            LongArray(wh) { -1L },
            LongArray(wh),
            false,
        )
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

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
        val w = s.width
        val h = s.height

        val dragSrc = if (u.dragxSrc != -1 && u.dragySrc != -1) {
            s.islandAt(u.dragxSrc, u.dragySrc)
        } else {
            null
        }
        val dragDst = if (dragSrc != null && u.dragxDst != -1 && u.dragyDst != -1) {
            s.islandAt(u.dragxDst, u.dragyDst)
        } else {
            null
        }

        if (!ds.started) {
            sink.update(0, 0, tileSize * w + 2 * (tileSize / 2),
                tileSize * h + 2 * (tileSize / 2))
            ds.started = true
        }

        ds.newgrid.fill(0L)
        for (x in 0 until w) {
            for (y in 0 until h) {
                val cellIndex = y * w + x
                val v = s.grid[cellIndex]
                val newIndex = y * ds.w + x
                if (v and G_ISLAND != 0) {
                    val island = s.islandAtIndex(cellIndex)
                    var idata = 0L
                    if (dragSrc != null && (island === dragSrc ||
                            (dragDst != null && island === dragDst))
                    ) {
                        idata = idata or DI_COL_SELECTED
                    } else if (islandImpossible(s, island, v and G_MARK != 0) ||
                        v and G_WARN != 0
                    ) {
                        idata = idata or DI_COL_WARNING
                    } else {
                        idata = idata or DI_COL_NORMAL
                    }
                    if (u.curVisible && u.curX == island.x && u.curY == island.y) {
                        idata = idata or DI_BG_CURSOR
                    } else if (v and G_MARK != 0) {
                        idata = idata or DI_BG_MARK
                    } else {
                        idata = idata or DI_BG_NORMAL
                    }

                    ds.newgrid[newIndex] = ds.newgrid[newIndex] or
                        (idata shl D_I_ISLAND_SHIFT)
                    if (x > 0 && gridAt(s, x - 1, y) and G_ISLAND == 0) {
                        ds.newgrid[y * ds.w + (x - 1)] =
                            ds.newgrid[y * ds.w + (x - 1)] or
                            (idata shl D_L_ISLAND_SHIFT_R)
                    }
                    if (x + 1 < w && gridAt(s, x + 1, y) and G_ISLAND == 0) {
                        ds.newgrid[y * ds.w + (x + 1)] =
                            ds.newgrid[y * ds.w + (x + 1)] or
                            (idata shl D_L_ISLAND_SHIFT_L)
                    }
                    if (y > 0 && gridAt(s, x, y - 1) and G_ISLAND == 0) {
                        ds.newgrid[(y - 1) * ds.w + x] =
                            ds.newgrid[(y - 1) * ds.w + x] or
                            (idata shl D_L_ISLAND_SHIFT_D)
                    }
                    if (y + 1 < h && gridAt(s, x, y + 1) and G_ISLAND == 0) {
                        ds.newgrid[(y + 1) * ds.w + x] =
                            ds.newgrid[(y + 1) * ds.w + x] or
                            (idata shl D_L_ISLAND_SHIFT_U)
                    }
                } else {
                    var selh = false
                    var selv = false
                    if (dragDst != null &&
                        within(x, dragSrc!!.x, dragDst.x) &&
                        within(y, dragSrc.y, dragDst.y)
                    ) {
                        if (dragSrc.x != dragDst.x) selh = true else selv = true
                    }
                    val lv: Int
                    val lh: Int
                    run {
                        var lhv = 0
                        var lvv = 0
                        if (v and G_LINEV != 0) lvv = s.lines[cellIndex]
                        if (v and G_LINEH != 0) lhv = s.lines[cellIndex]
                        if (u.showHints) {
                            if (betweenIsland(s, x, y, 0, 1) && lvv == 0) lvv = 1
                            if (betweenIsland(s, x, y, 1, 0) && lhv == 0) lhv = 1
                        }
                        lv = lvv
                        lh = lhv
                    }

                    var hdata = when {
                        v and G_NOLINEH != 0 -> DL_COUNT_CROSS
                        v and G_LINEH != 0 -> lh.toLong()
                        u.showHints && betweenIsland(s, x, y, 1, 0) -> DL_COUNT_HINT
                        else -> 0L
                    }
                    var vdata = when {
                        v and G_NOLINEV != 0 -> DL_COUNT_CROSS
                        v and G_LINEV != 0 -> lv.toLong()
                        u.showHints && betweenIsland(s, x, y, 0, 1) -> DL_COUNT_HINT
                        else -> 0L
                    }
                    hdata = hdata or when {
                        v and G_WARN != 0 -> DL_COL_WARNING
                        selh -> DL_COL_SELECTED
                        else -> DL_COL_NORMAL
                    }
                    vdata = vdata or when {
                        v and G_WARN != 0 -> DL_COL_WARNING
                        selv -> DL_COL_SELECTED
                        else -> DL_COL_NORMAL
                    }
                    if (v and G_MARKH != 0) hdata = hdata or DL_LOCK
                    if (v and G_MARKV != 0) vdata = vdata or DL_LOCK

                    ds.newgrid[newIndex] = ds.newgrid[newIndex] or
                        (hdata shl D_L_LINE_SHIFT_H)
                    ds.newgrid[newIndex] = ds.newgrid[newIndex] or
                        (vdata shl D_L_LINE_SHIFT_V)
                    if (x > 0 && gridAt(s, x - 1, y) and G_ISLAND != 0) {
                        ds.newgrid[y * ds.w + (x - 1)] =
                            ds.newgrid[y * ds.w + (x - 1)] or
                            (hdata shl D_I_LINE_SHIFT_R)
                    }
                    if (x + 1 < w && gridAt(s, x + 1, y) and G_ISLAND != 0) {
                        ds.newgrid[y * ds.w + (x + 1)] =
                            ds.newgrid[y * ds.w + (x + 1)] or
                            (hdata shl D_I_LINE_SHIFT_L)
                    }
                    if (y > 0 && gridAt(s, x, y - 1) and G_ISLAND != 0) {
                        ds.newgrid[(y - 1) * ds.w + x] =
                            ds.newgrid[(y - 1) * ds.w + x] or
                            (vdata shl D_I_LINE_SHIFT_D)
                    }
                    if (y + 1 < h && gridAt(s, x, y + 1) and G_ISLAND != 0) {
                        ds.newgrid[(y + 1) * ds.w + x] =
                            ds.newgrid[(y + 1) * ds.w + x] or
                            (vdata shl D_I_LINE_SHIFT_U)
                    }
                }
            }
        }

        for (x in 0 until w) {
            for (y in 0 until h) {
                val index = y * ds.w + x
                val newval = ds.newgrid[index]
                if (ds.grid[index] != newval) {
                    val v = s.grid[y * w + x]
                    if (v and G_ISLAND != 0) {
                        val island = s.islandAtIndex(y * w + x)
                        drawIslandTile(sink, x, y, island.count, newval, tileSize)
                        if (x == 0) drawEdgeTile(sink, x - 1, y, 1, 0, newval, tileSize)
                        if (y == 0) drawEdgeTile(sink, x, y - 1, 0, 1, newval, tileSize)
                        if (x == w - 1) drawEdgeTile(sink, x + 1, y, -1, 0, newval, tileSize)
                        if (y == h - 1) drawEdgeTile(sink, x, y + 1, 0, -1, newval, tileSize)
                    } else {
                        drawLineTile(sink, x, y, newval, tileSize)
                    }
                    ds.grid[index] = newval
                }
            }
        }
    }

    private fun State.islandAtIndex(index: Int): Island = islandList[gridi[index]]

    private fun within(value: Int, a: Int, b: Int): Boolean =
        if (a > b) value in b..a else value in a..b

    private fun betweenIsland(state: State, sx: Int, sy: Int, dx: Int, dy: Int): Boolean {
        var x = sx - dx
        var y = sy - dy
        var found = false
        while (inGrid(state, x, y)) {
            if (gridAt(state, x, y) and G_ISLAND != 0) {
                found = true
                break
            }
            x -= dx
            y -= dy
        }
        if (!found) return false
        x = sx + dx
        y = sy + dy
        while (inGrid(state, x, y)) {
            if (gridAt(state, x, y) and G_ISLAND != 0) return true
            x += dx
            y += dy
        }
        return false
    }

    private fun drawCross(sink: DrawSink, ox: Int, oy: Int, colour: Int, tileSize: Int) {
        val off = tileSize * 2 / 8
        sink.line(ox, oy, ox + off, oy + off, colour)
        sink.line(ox + off, oy, ox, oy + off, colour)
    }

    private fun drawGeneralLine(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        fx: Int,
        fy: Int,
        ax: Int,
        ay: Int,
        len: Int,
        ldata: Long,
        which: Int,
        tileSize: Int,
    ) {
        val fg = when {
            ldata and DL_COUNTMASK == DL_COUNT_HINT -> COL_HINT
            ldata and DL_COLMASK == DL_COL_SELECTED -> COL_SELECTED
            ldata and DL_COLMASK == DL_COL_FLASH -> COL_HIGHLIGHT
            ldata and DL_COLMASK == DL_COL_WARNING -> COL_WARNING
            else -> COL_FOREGROUND
        }

        val count = ldata and DL_COUNTMASK
        if (count == DL_COUNT_CROSS) {
            drawCross(
                sink,
                ox + tileSize * 1 / 8 * fx + tileSize * 3 / 8 * ax,
                oy + tileSize * 1 / 8 * fy + tileSize * 3 / 8 * ay,
                fg,
                tileSize,
            )
            drawCross(
                sink,
                ox + tileSize * 5 / 8 * fx + tileSize * 3 / 8 * ax,
                oy + tileSize * 5 / 8 * fy + tileSize * 3 / 8 * ay,
                fg,
                tileSize,
            )
        } else if (count != 0L) {
            var lh = count.toInt()
            if (count == DL_COUNT_HINT) lh = 1

            var lw = tileSize / 8
            var gw = lw
            var bw: Int
            while (true) {
                bw = lw * lh + gw * (lh + 1)
                if (bw <= tileSize) break
                gw--
            }
            var loff = tileSize / 2 - bw / 2

            if (which and 1 != 0) {
                if (ldata and DL_LOCK != 0L && fg != COL_HINT) {
                    sink.rect(
                        ox + loff * ax,
                        oy + loff * ay,
                        len * fx + bw * ax,
                        len * fy + bw * ay,
                        COL_MARK,
                    )
                }
            }
            if (which and 2 != 0) {
                for (i in 0 until lh) {
                    sink.rect(
                        ox + (loff + gw) * ax,
                        oy + (loff + gw) * ay,
                        len * fx + lw * ax,
                        len * fy + lw * ay,
                        fg,
                    )
                    loff += lw + gw
                }
            }
        }
    }

    private fun drawHLine(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        w: Int,
        data: Long,
        which: Int,
        tileSize: Int,
    ) {
        drawGeneralLine(sink, ox, oy, 1, 0, 0, 1, w, data, which, tileSize)
    }

    private fun drawVLine(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        h: Int,
        data: Long,
        which: Int,
        tileSize: Int,
    ) {
        drawGeneralLine(sink, ox, oy, 0, 1, 1, 0, h, data, which, tileSize)
    }

    private fun drawIsland(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        clue: Int,
        idata: Long,
        tileSize: Int,
    ) {
        if (idata and DI_BGMASK == DI_BG_NO_ISLAND) return

        val half = tileSize / 2
        val orad = tileSize * 12 / 20
        val irad = orad - tileSize / 8
        val fg = when {
            idata and DI_COLMASK == DI_COL_SELECTED -> COL_SELECTED
            idata and DI_COLMASK == DI_COL_WARNING -> COL_WARNING
            idata and DI_COLMASK == DI_COL_FLASH -> COL_HIGHLIGHT
            else -> COL_FOREGROUND
        }
        val bg = when {
            idata and DI_BGMASK == DI_BG_CURSOR -> COL_CURSOR
            idata and DI_BGMASK == DI_BG_MARK -> COL_MARK
            else -> COL_BACKGROUND
        }

        sink.circle(ox + half, oy + half, orad, fg, fg)
        sink.circle(ox + half, oy + half, irad, bg, bg)

        if (clue > 0) {
            val size = if (clue < 10) tileSize * 7 / 10 else tileSize * 5 / 10
            val textColour = if (fg == COL_SELECTED) COL_FOREGROUND else fg
            sink.text(
                ox + half,
                oy + half,
                Text.FONT_VARIABLE,
                size,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                textColour,
                clue.toString(),
            )
        }
    }

    private fun drawIslandTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        clue: Int,
        data: Long,
        tileSize: Int,
    ) {
        val ox = coord(x, tileSize)
        val oy = coord(y, tileSize)
        sink.clip(ox, oy, tileSize, tileSize)
        sink.rect(ox, oy, tileSize, tileSize, COL_BACKGROUND)

        var which = 1
        while (which <= 2) {
            drawHLine(
                sink, ox, oy, tileSize / 2,
                (data shr D_I_LINE_SHIFT_L) and DL_MASK, which, tileSize,
            )
            drawHLine(
                sink, ox + tileSize - tileSize / 2, oy, tileSize / 2,
                (data shr D_I_LINE_SHIFT_R) and DL_MASK, which, tileSize,
            )
            drawVLine(
                sink, ox, oy, tileSize / 2,
                (data shr D_I_LINE_SHIFT_U) and DL_MASK, which, tileSize,
            )
            drawVLine(
                sink, ox, oy + tileSize - tileSize / 2, tileSize / 2,
                (data shr D_I_LINE_SHIFT_D) and DL_MASK, which, tileSize,
            )
            which = which shl 1
        }
        drawIsland(sink, ox, oy, clue, (data shr D_I_ISLAND_SHIFT) and DI_MASK, tileSize)

        sink.unclip()
        sink.update(ox, oy, tileSize, tileSize)
    }

    private fun drawLineTile(sink: DrawSink, x: Int, y: Int, data: Long, tileSize: Int) {
        val ox = coord(x, tileSize)
        val oy = coord(y, tileSize)
        sink.clip(ox, oy, tileSize, tileSize)
        sink.rect(ox, oy, tileSize, tileSize, COL_BACKGROUND)

        val hdata = (data shr D_L_LINE_SHIFT_H) and DL_MASK
        val vdata = (data shr D_L_LINE_SHIFT_V) and DL_MASK
        if (hdata and DL_COUNTMASK > vdata and DL_COUNTMASK) {
            drawHLine(sink, ox, oy, tileSize, hdata, 3, tileSize)
            drawVLine(sink, ox, oy, tileSize, vdata, 3, tileSize)
        } else {
            drawVLine(sink, ox, oy, tileSize, vdata, 3, tileSize)
            drawHLine(sink, ox, oy, tileSize, hdata, 3, tileSize)
        }

        drawIsland(
            sink, ox - tileSize, oy, -1,
            (data shr D_L_ISLAND_SHIFT_L) and DI_MASK, tileSize,
        )
        drawIsland(
            sink, ox + tileSize, oy, -1,
            (data shr D_L_ISLAND_SHIFT_R) and DI_MASK, tileSize,
        )
        drawIsland(
            sink, ox, oy - tileSize, -1,
            (data shr D_L_ISLAND_SHIFT_U) and DI_MASK, tileSize,
        )
        drawIsland(
            sink, ox, oy + tileSize, -1,
            (data shr D_L_ISLAND_SHIFT_D) and DI_MASK, tileSize,
        )

        sink.unclip()
        sink.update(ox, oy, tileSize, tileSize)
    }

    private fun drawEdgeTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        data: Long,
        tileSize: Int,
    ) {
        val ox = coord(x, tileSize)
        val oy = coord(y, tileSize)
        var cx = ox
        var cy = oy
        var cw = tileSize
        var ch = tileSize

        if (dy != 0) {
            if (dy > 0) cy += tileSize / 2
            ch -= tileSize / 2
        } else {
            if (dx > 0) cx += tileSize / 2
            cw -= tileSize / 2
        }
        sink.clip(cx, cy, cw, ch)
        sink.rect(cx, cy, cw, ch, COL_BACKGROUND)
        drawIsland(
            sink,
            ox + tileSize * dx,
            oy + tileSize * dy,
            -1,
            (data shr D_I_ISLAND_SHIFT) and DI_MASK,
            tileSize,
        )
        sink.unclip()
        sink.update(cx, cy, cw, ch)
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isCWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\u000B' ||
            this == '\u000C' || this == '\r'

    /**
     * C `atoi` from the start of [text]. A number that overflows an
     * `int` saturates, which is the port's defined result for the C
     * undefined behavior.
     */
    private fun cAtoi(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isCWhitespace()) index++
        var sign = 1
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            if (text[index] == '-') sign = -1
            index++
        }
        var any = false
        var value = 0L
        while (index < text.length && text[index] in '0'..'9') {
            any = true
            val digit = text[index] - '0'
            if (value > (Long.MAX_VALUE - digit) / 10) {
                value = Long.MAX_VALUE
            } else {
                value = value * 10 + digit
            }
            index++
        }
        if (!any) return 0
        if (sign > 0) return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
        val negative = -value
        return if (negative < Int.MIN_VALUE) Int.MIN_VALUE else negative.toInt()
    }
}
