package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `tents.c`: place one tent next to each tree, with
 * the row and column counts on the edge.
 *
 * The rules, the seed, the description, and the save format are
 * upstream behavior. The generator places the tents first, finds a
 * distinct adjacent tree for each tent with `matching.c`, and accepts
 * a board only when the `diff - 1` solver stalls and the `diff` solver
 * solves it.
 *
 * The code keeps the C structure on purpose:
 * - the tent neighbours are collected while the tents are placed, and
 *   each adjacency list is built in the order `U, L, R, D`, because
 *   that order decides the matching and therefore the description;
 * - `completed` is sticky: `executeMove` starts from a copy and only
 *   ever sets the flag;
 * - `solve` runs on the initial grid, not on the current one;
 * - the solver combines row placements with the C loop's side effects.
 *
 * The generator writes the answer as `AUXINFO`, exactly as C's
 * `new_game_desc` does: a leading `S` and one `;Tx,y` per tent in
 * row-major order. A save that already carries `AUXINFO` (the frozen
 * corpus does) keeps it and `solve` returns it unchanged, as
 * `solve_game` does.
 *
 * C reaches two assertions for descriptions that `validate_desc`
 * accepts: `8x8:ddddddddz,...` overruns the grid, and
 * `8x8:dddddddcz_,...` lands on the virtual cell with a non-tree.
 * Those are undefined behavior in a release build. This port throws a
 * defined `IllegalArgumentException` instead; see D-009.
 *
 * Enablement debt: upstream validation accepts `46340x46340`, and
 * generation allocates over 100 GB. Easy on a large board is also
 * slow: 20x20 takes about 0.6 s and 25x25 about 39 s in C.
 *
 * D-027: [newDesc] refuses an area above [MAX_AREA] (22x22) before the
 * first draw. 22x22 Easy takes 6.9 s on the measured phone and 23x23
 * Easy does not finish in 12 s on the host.
 */
object Tents : Game {

    /** The cell values of `enum { BLANK, TREE, TENT, NONTENT, MAGIC }`. */
    internal const val BLANK = 0
    internal const val TREE = 1
    internal const val TENT = 2
    internal const val NONTENT = 3
    internal const val MAGIC = 4

    /** The solver link directions: `enum { N, U, L, R, D, MAXDIR }`. */
    private const val N = 0
    private const val U = 1
    private const val L = 2
    private const val R = 3
    private const val D = 4
    private const val MAXDIR = 5

    /** The two difficulties of `DIFFLIST`. */
    internal const val DIFF_EASY = 0
    internal const val DIFF_TRICKY = 1
    internal const val DIFFCOUNT = 2
    private val DIFF_CHARS = charArrayOf('e', 't')
    private val DIFF_NAMES = arrayOf("Easy", "Tricky")

    /** D-027: the measured generation cap, 22x22. */
    internal const val MAX_AREA = 484

    /** The palette of `tents.c:259-270`. */
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_GRASS = 2
    private const val COL_TREETRUNK = 3
    private const val COL_TREELEAF = 4
    private const val COL_TENT = 5
    private const val COL_ERROR = 6
    private const val COL_ERRTEXT = 7
    private const val COL_ERRTRUNK = 8
    internal const val NCOLOURS = 9

    /** The error bits of `tents.c:2002-2012`. */
    internal const val ERR_ADJ_TOPLEFT = 4
    internal const val ERR_ADJ_TOP = 5
    internal const val ERR_ADJ_TOPRIGHT = 6
    internal const val ERR_ADJ_LEFT = 7
    internal const val ERR_ADJ_RIGHT = 8
    internal const val ERR_ADJ_BOTLEFT = 9
    internal const val ERR_ADJ_BOT = 10
    internal const val ERR_ADJ_BOTRIGHT = 11
    internal const val ERR_OVERCOMMITTED = 12

    private const val PREFERRED_TILE_SIZE = 32

    /** `struct game_params`. */
    data class Params(val w: Int, val h: Int, val diff: Int) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, diff)
    }

    /**
     * The immutable data shared by every state of one game: the clue
     * numbers and the initial grid.
     *
     * C shares a refcounted `struct numbers`; nothing writes it after
     * `new_game`. The initial grid is a port shape, not a C field:
     * `solve_game` reads the first state's grid, while the seam hands
     * `solve` the current state. Do not modify either array.
     */
    class Shared internal constructor(
        val numbers: IntArray,
        val initialGrid: IntArray,
    )

    /**
     * `struct game_state`. [completed] is sticky and [cheated] is
     * cleared only by a new game.
     */
    class State internal constructor(
        val width: Int,
        val height: Int,
        val diff: Int,
        val grid: IntArray,
        val shared: Shared,
        val completed: Boolean,
        val cheated: Boolean,
    ) : Game.State {

        fun valueAt(x: Int, y: Int): Int = grid[y * width + x]

        fun clue(index: Int): Int = shared.numbers[index]

        override fun copy(): Game.State = State(
            width,
            height,
            diff,
            grid.copyOf(),
            shared,
            completed,
            cheated,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                diff == other.diff &&
                grid.contentEquals(other.grid) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + diff
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`. The drag fields keep a batch one move. */
    class Ui : Game.Ui {
        var dsx: Int = -1
        var dsy: Int = -1
        var dex: Int = -1
        var dey: Int = -1
        var dragButton: Int = -1
        var dragOk: Boolean = false
        var cx: Int = 0
        var cy: Int = 0
        var cdisp: Boolean = false
    }

    /**
     * `struct game_drawstate`. There is no blitter. [started] is never
     * set true, as in C, so the grid lines are drawn on every frame.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
    ) : Game.DrawState {
        val drawn: IntArray = IntArray(width * height) { MAGIC }
        val numbersDrawn: IntArray = IntArray(width + height) { 2 }
        var started: Boolean = false
        var cx: Int = -1
        var cy: Int = -1
    }

    /** One generated description, the C aux answer, and the attempt count. */
    data class Generation(val desc: String, val auxInfo: String, val attempts: Int)

    /** The solver result: a move, or one of the two C error strings. */
    data class SolveAttempt(val move: String?, val error: String?)

    override val name: String = "Tents"
    override val backendName: String = "tents"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /** `default_params`: 8x8 Easy. */
    override fun defaultParams(): Game.Params = Params(8, 8, DIFF_EASY)

    /** `tents_presets` and `"%dx%d %s"`. */
    override fun presets(): List<Preset> = listOf(
        Params(8, 8, DIFF_EASY),
        Params(8, 8, DIFF_TRICKY),
        Params(10, 10, DIFF_EASY),
        Params(10, 10, DIFF_TRICKY),
        Params(15, 15, DIFF_EASY),
        Params(15, 15, DIFF_TRICKY),
    ).map {
        Preset("${it.w}x${it.h} ${DIFF_NAMES[it.diff]}", encodeParams(it, true))
    }

    /**
     * `decode_params`: `atoi` for the width, a digit-only skip, an
     * optional lower-case `x` and height, then an optional `d` and
     * exactly one consumed difficulty character.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = atoi(text, 0)
        var h = w
        var index = 0
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == 'x') {
            index++
            h = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        var diff = DIFF_EASY
        if (index < text.length && text[index] == 'd') {
            index++
            if (index < text.length) {
                for (i in 0 until DIFFCOUNT) {
                    if (text[index] == DIFF_CHARS[i]) diff = i
                }
                index++
            }
        }
        return Params(w, h, diff)
    }

    /** `encode_params`: the short form drops the difficulty. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.w).append('x').append(p.h)
        if (full) out.append('d').append(DIFF_CHARS[p.diff])
        return out.toString()
    }

    /** `validate_params`, in the C order and with the C messages. */
    @Suppress("UNUSED_PARAMETER")
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 4 || p.h < 4) {
            return "Width and height must both be at least four"
        }
        if (p.h != 0 && p.w > (Int.MAX_VALUE - 1) / p.h) {
            return "Width times height must not be unreasonably large"
        }
        return null
    }

    /** `game_configure` (`tents.c:370-396`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Tricky",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`tents.c:398-407`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
        )

    /**
     * `new_game_desc`. The caller owns [random]; a retry draws from the
     * same stream, as C does. The aux answer is the C solution string.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val generation = generate(params as Params, random, cancel)
        return Description(generation.desc, generation.auxInfo)
    }

    /** [newDesc] with the generation attempt count, for tests. */
    fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params as Params, random)

    /**
     * `validate_desc`, in the C order. It checks only the runs, the
     * total area, the count and shape of the number list, and the tail.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val target = p.w.toLong() * p.h + 1
        var area = 0L
        var index = 0
        while (index < desc.length && desc[index] != ',') {
            val c = desc[index]
            when {
                c == '_' -> area++
                c in 'a' until 'z' -> area += c - 'a' + 2
                c == 'z' -> area += 25
                c == '!' || c == '-' -> {
                    if (area == 0L || area > p.w.toLong() * p.h) {
                        return "Tent or non-tent placed off the grid"
                    }
                }

                else -> return "Invalid character in grid specification"
            }
            index++
        }
        if (area < target) return "Not enough data to fill grid"
        if (area > target) return "Too much data to fit in grid"

        for (i in 0 until p.w + p.h) {
            if (index >= desc.length) {
                return "Not enough numbers given after grid specification"
            }
            if (desc[index] != ',') return "Invalid character in number list"
            index++
            while (index < desc.length && desc[index] in '0'..'9') index++
        }
        if (index < desc.length) {
            return "Unexpected additional data at end of game description"
        }
        return null
    }

    /**
     * `new_game`. C asserts the decode invariants; a direct caller gets
     * a clear exception instead of an abort. Two such descriptions pass
     * `validateDesc` and abort C; the messages below are the D-009 port
     * definition.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.w * p.h
        val grid = IntArray(wh) { BLANK }
        val numbers = IntArray(p.w + p.h)

        var i = 0
        var index = 0
        while (index < desc.length) {
            val c = desc[index]
            val run: Int
            val type: Int
            when {
                c == '_' -> {
                    run = 0
                    type = TREE
                }

                c in 'a' until 'z' -> {
                    run = c - 'a' + 1
                    type = TREE
                }

                c == 'z' -> {
                    run = 25
                    type = BLANK
                }

                c == '!' || c == '-' -> {
                    run = -1
                    type = if (c == '!') TENT else NONTENT
                }

                else -> throw IllegalArgumentException(
                    "tents: invalid character '$c' in the grid specification",
                )
            }
            index++
            i += run
            if (i < 0 || i > wh) {
                throw IllegalArgumentException("tents: the description runs past the grid")
            }
            if (i == wh) {
                if (type != TREE) {
                    throw IllegalArgumentException(
                        "tents: the description ends on a non-tree cell",
                    )
                }
                break
            }
            if (type != BLANK) grid[i++] = type
        }

        for (k in 0 until p.w + p.h) {
            if (index >= desc.length || desc[index] != ',') {
                throw IllegalArgumentException("tents: the number list is incomplete")
            }
            index++
            numbers[k] = atoi(desc, index)
            while (index < desc.length && desc[index] in '0'..'9') index++
        }
        if (index != desc.length) {
            throw IllegalArgumentException("tents: unexpected data after the number list")
        }

        val shared = Shared(numbers, grid.copyOf())
        return State(p.w, p.h, p.diff, grid, shared, completed = false, cheated = false)
    }

    /**
     * `solve_game`: an existing `AUXINFO` is returned verbatim; otherwise
     * the solver runs on the initial grid, as C reads `state->grid`.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state, auxInfo).move

    /** `solve_game` with the two C error strings (`tents.c:1340-1388`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state, auxInfo)
        return SolveOutcome(attempt.move, attempt.error)
    }

    /**
     * The solver result with the C error strings. The seam has no error
     * channel, so a caller that needs the message uses this helper and
     * [solve] keeps the seam shape.
     */
    fun solveAttempt(state: Game.State, auxInfo: String?): SolveAttempt {
        if (auxInfo != null) return SolveAttempt(auxInfo, null)
        val s = state as State
        val soln = IntArray(s.width * s.height)
        val max = maxOf(s.width, s.height)
        val ret = tentsSolve(
            s.width,
            s.height,
            s.shared.initialGrid,
            s.shared.numbers,
            soln,
            IntArray(max),
            IntArray(max),
            IntArray(3 * max),
            IntArray(3 * max),
            DIFF_TRICKY,
        )
        if (ret != 1) {
            val error = if (ret == 0) {
                "This puzzle is not self-consistent"
            } else {
                "Unable to find a unique solution for this puzzle"
            }
            return SolveAttempt(null, error)
        }
        val out = StringBuilder("S")
        for (i in soln.indices) {
            if (soln[i] == TENT) {
                out.append(";T").append(i % s.width).append(',').append(i / s.width)
            }
        }
        return SolveAttempt(out.toString(), null)
    }

    /**
     * `execute_move`. The `S` prefix clears every non-tree cell and the
     * `B`, `T`, and `N` entries overwrite cells. `%d` overflow is
     * defined here by saturation and a low-32-bit wrap, as in the other
     * ports. A tree cell or an out-of-range coordinate rejects the whole
     * move.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.width
        val h = s.height
        val grid = s.grid.copyOf()
        var cheated = s.cheated
        var index = 0

        while (index < move.length) {
            when (val c = move[index]) {
                'S' -> {
                    cheated = true
                    for (i in grid.indices) {
                        if (grid[i] != TREE) grid[i] = NONTENT
                    }
                    index++
                }

                'B', 'T', 'N' -> {
                    index++
                    val x = scanInt(move, index) ?: return null
                    index = x.end
                    if (index >= move.length || move[index] != ',') return null
                    index++
                    val y = scanInt(move, index) ?: return null
                    index = y.end
                    if (x.value < 0 || y.value < 0 || x.value >= w || y.value >= h) {
                        return null
                    }
                    val cell = y.value * w + x.value
                    if (grid[cell] == TREE) return null
                    grid[cell] = when (c) {
                        'B' -> BLANK
                        'T' -> TENT
                        else -> NONTENT
                    }
                }

                else -> return null
            }
            if (index < move.length && move[index] == ';') {
                index++
            } else if (index < move.length) {
                return null
            }
        }

        var completed = s.completed
        if (!completed && isCompleted(w, h, grid, s.shared)) completed = true

        return State(
            w,
            h,
            s.diff,
            grid,
            s.shared,
            completed,
            cheated,
        )
    }

    /** The completion check of `execute_move`, after the move is applied. */
    private fun isCompleted(
        w: Int,
        h: Int,
        grid: IntArray,
        shared: Shared,
    ): Boolean {
        var tents = 0
        var trees = 0
        for (value in grid) {
            if (value == TENT) tents++
            else if (value == TREE) trees++
        }
        if (tents != trees) return false

        for (x in 0 until w) {
            var count = 0
            for (y in 0 until h) if (grid[y * w + x] == TENT) count++
            if (shared.numbers[x] != count) return false
        }
        for (y in 0 until h) {
            var count = 0
            for (x in 0 until w) if (grid[y * w + x] == TENT) count++
            if (shared.numbers[w + y] != count) return false
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x + 1 < w &&
                    grid[y * w + x] == TENT && grid[y * w + x + 1] == TENT
                ) {
                    return false
                }
                if (y + 1 < h &&
                    grid[y * w + x] == TENT && grid[(y + 1) * w + x] == TENT
                ) {
                    return false
                }
                if (x + 1 < w && y + 1 < h) {
                    if (grid[y * w + x] == TENT &&
                        grid[(y + 1) * w + (x + 1)] == TENT
                    ) {
                        return false
                    }
                    if (grid[(y + 1) * w + x] == TENT &&
                        grid[y * w + (x + 1)] == TENT
                    ) {
                        return false
                    }
                }
            }
        }

        // The tree and tent vertex ids are assigned in row-major order,
        // each class separately, as C does.
        val gridIds = IntArray(w * h)
        var id = 0
        for (i in grid.indices) if (grid[i] == TENT) gridIds[i] = id++
        val m = id
        id = 0
        for (i in grid.indices) if (grid[i] == TREE) gridIds[i] = id++
        check(id == m) { "the tent and tree counts differ" }

        val adjacency = Array(m) { IntArray(0) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val cell = y * w + x
                if (grid[cell] != TREE) continue
                val treeId = gridIds[cell]
                val list = ArrayList<Int>(4)
                for (d in 1 until MAXDIR) {
                    val x2 = x + dx(d)
                    val y2 = y + dy(d)
                    if (x2 in 0 until w && y2 in 0 until h &&
                        grid[y2 * w + x2] == TENT
                    ) {
                        list.add(gridIds[y2 * w + x2])
                    }
                }
                adjacency[treeId] = list.toIntArray()
            }
        }
        return Matching.match(m, m, adjacency, null).count == m
    }

    /**
     * `interpret_move`. A press records the drag start and hides the
     * cursor; a drag previews; a release emits one batched move. The
     * `STYLUS_BASED` click cycles are the Android build's cycles.
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
        val w = s.width
        val h = s.height
        val shift = button and Buttons.MOD_SHFT != 0
        val control = button and Buttons.MOD_CTRL != 0
        val stripped = Buttons.stripModifiers(button)

        if (stripped == Buttons.LEFT_BUTTON || stripped == Buttons.RIGHT_BUTTON) {
            val cx = fromCoord(x, tileSize)
            val cy = fromCoord(y, tileSize)
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) return MoveResult.Unused
            u.dragButton = stripped
            u.dsx = cx
            u.dex = cx
            u.dsy = cy
            u.dey = cy
            u.dragOk = true
            u.cdisp = false
            return MoveResult.UiUpdate
        }

        if ((Buttons.isMouseDrag(stripped) || Buttons.isMouseRelease(stripped)) &&
            u.dragButton > 0
        ) {
            var cx = fromCoord(x, tileSize)
            var cy = fromCoord(y, tileSize)
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) {
                u.dragOk = false
            } else {
                if (kotlin.math.abs(cx - u.dsx) < kotlin.math.abs(cy - u.dsy)) {
                    cx = u.dsx
                } else {
                    cy = u.dsy
                }
                u.dex = cx
                u.dey = cy
                u.dragOk = true
            }

            if (Buttons.isMouseDrag(stripped)) return MoveResult.UiUpdate

            if (!u.dragOk) {
                u.dragButton = -1
                return MoveResult.UiUpdate
            }

            val xmin = minOf(u.dsx, u.dex)
            val xmax = maxOf(u.dsx, u.dex)
            val ymin = minOf(u.dsy, u.dey)
            val ymax = maxOf(u.dsy, u.dey)
            val out = StringBuilder()
            for (yy in ymin..ymax) {
                for (xx in xmin..xmax) {
                    val v = dragXform(u, xx, yy, s.grid[yy * w + xx])
                    if (s.grid[yy * w + xx] != v) {
                        if (out.isNotEmpty()) out.append(';')
                        out.append(
                            when (v) {
                                BLANK -> 'B'
                                TENT -> 'T'
                                else -> 'N'
                            },
                        ).append(xx).append(',').append(yy)
                    }
                }
            }
            u.dragButton = -1
            return if (out.isEmpty()) MoveResult.UiUpdate else MoveResult.Move(out.toString())
        }

        if (Buttons.isCursorMove(stripped)) {
            if (shift || control) {
                val indices = IntArray(2)
                indices[0] = u.cx + w * u.cy
                val ret = moveCursor(stripped, u, w, h)
                indices[1] = u.cx + w * u.cy
                val out = StringBuilder()
                for (i in 0..(if (indices[0] != indices[1]) 1 else 0)) {
                    val cell = indices[i]
                    if (s.grid[cell] == BLANK ||
                        (control && s.grid[cell] == TENT)
                    ) {
                        if (out.isNotEmpty()) out.append(';')
                        out.append('N').append(cell % w).append(',').append(cell / w)
                    }
                }
                if (out.isNotEmpty()) return MoveResult.Move(out.toString())
                return ret
            }
            return moveCursor(stripped, u, w, h)
        }

        if (u.cdisp) {
            var rep = 0.toChar()
            val cell = u.cy * w + u.cx
            val v = s.grid[cell]
            if (v != TREE) {
                if (stripped == Buttons.CURSOR_SELECT) {
                    rep = if (v == BLANK) 'T' else 'B'
                } else if (stripped == Buttons.CURSOR_SELECT2) {
                    rep = if (v == BLANK) 'N' else 'B'
                } else if (stripped == 'T'.code || stripped == 'N'.code ||
                    stripped == 'B'.code
                ) {
                    rep = stripped.toChar()
                }
            }
            if (rep != 0.toChar()) {
                return MoveResult.Move("$rep${u.cx},${u.cy}")
            }
        } else if (stripped == Buttons.CURSOR_SELECT ||
            stripped == Buttons.CURSOR_SELECT2
        ) {
            u.cdisp = true
            return MoveResult.UiUpdate
        }

        return MoveResult.Unused
    }

    /** `drag_xform` with `STYLUS_BASED`. */
    private fun dragXform(ui: Ui, x: Int, y: Int, value: Int): Int {
        val xmin = minOf(ui.dsx, ui.dex)
        val xmax = maxOf(ui.dsx, ui.dex)
        val ymin = minOf(ui.dsy, ui.dey)
        val ymax = maxOf(ui.dsy, ui.dey)

        if (x < xmin || x > xmax || y < ymin || y > ymax) return value
        if (value == TREE) return value

        if (xmin == xmax && ymin == ymax) {
            return if (ui.dragButton == Buttons.LEFT_BUTTON) {
                when (value) {
                    BLANK -> TENT
                    TENT -> NONTENT
                    else -> BLANK
                }
            } else {
                when (value) {
                    BLANK -> NONTENT
                    NONTENT -> TENT
                    else -> BLANK
                }
            }
        }
        return if (value == BLANK) NONTENT else value
    }

    /** `move_cursor` with `wrap = false`. */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
        var dx = 0
        var dy = 0
        when (button) {
            Buttons.CURSOR_UP -> dy = -1
            Buttons.CURSOR_DOWN -> dy = 1
            Buttons.CURSOR_RIGHT -> dx = 1
            Buttons.CURSOR_LEFT -> dx = -1
            else -> return MoveResult.Unused
        }
        val ox = ui.cx
        val oy = ui.cy
        ui.cx = (ui.cx + dx).coerceIn(0, w - 1)
        ui.cy = (ui.cy + dy).coerceIn(0, h - 1)
        if (!ui.cdisp) {
            ui.cdisp = true
            return MoveResult.UiUpdate
        }
        return if (ui.cx != ox || ui.cy != oy) MoveResult.UiUpdate else MoveResult.NoEffect
    }

    /** `FROMCOORD`, with C truncation toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

    /** `game_changed_state`: an honest completion and nothing else. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        val previous = oldState as State?
        return next.completed && !next.cheated &&
            previous != null && !previous.completed
    }

    /** `game_request_keys` is NULL, so the Android full-cursor mode only changes the arrow mode. */
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `game_request_keys` is NULL: no labels and the default arrow mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /**
     * `current_key_label`. C reads the grid before it tests the cursor
     * and without a bounds test; the port returns "" for a cursor
     * outside the grid, which the game itself cannot produce.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (u.cx < 0 || u.cy < 0 || u.cx >= s.width || u.cy >= s.height) return ""
        val v = s.grid[u.cy * s.width + u.cx]
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.cdisp
        ) {
            when (v) {
                BLANK -> return if (button == Buttons.CURSOR_SELECT) "Tent" else "Green"
                TENT, NONTENT -> return "Clear"
            }
        }
        return ""
    }

    /** `game_compute_size`: the borders are truncated separately. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val leftAndRight = tileSize / 2 + tileSize * 3 / 2
        return Size(p.w * tileSize + leftAndRight, p.h * tileSize + leftAndRight)
    }

    /** `new_ui`: the environment variable is not read; the cursor starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cdisp = visible
    }

    /** `game_get_cursor_location`: a rectangle only for a visible cursor. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cdisp) return null
        return Game.CursorRect(
            u.cx * tileSize + tileSize / 2,
            u.cy * tileSize + tileSize / 2,
            tileSize,
            tileSize,
        )
    }

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`: only the background comes from the shell. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        setColour(ret, COL_GRID, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_GRASS, 0.7f, 1.0f, 0.5f)
        setColour(ret, COL_TREETRUNK, 0.6f, 0.4f, 0.0f)
        setColour(ret, COL_TREELEAF, 0.0f, 0.7f, 0.0f)
        setColour(ret, COL_TENT, 0.8f, 0.7f, 0.0f)
        setColour(ret, COL_ERROR, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_ERRTEXT, 1.0f, 1.0f, 1.0f)
        setColour(ret, COL_ERRTRUNK, 0.6f, 0.0f, 0.0f)
        return ret
    }

    private fun setColour(ret: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
    }

    /** `game_new_drawstate`: a MAGIC cell cache and a number cache at 2. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height)
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

    /**
     * `int_redraw` without printing and without a flash. The grid lines
     * are never cached because [DrawState.started] stays false.
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
        val w = s.width
        val h = s.height
        val t = tileSize

        var cx = -1
        var cy = -1
        if (u.cdisp) {
            cx = u.cx
            cy = u.cy
        }
        val cmoved = cx != ds.cx || cy != ds.cy

        if (!ds.started) {
            for (y in 0..h) {
                sink.line(
                    coord(0, t),
                    coord(y, t),
                    coord(w, t),
                    coord(y, t),
                    COL_GRID,
                )
            }
            for (x in 0..w) {
                sink.line(
                    coord(x, t),
                    coord(0, t),
                    coord(x, t),
                    coord(h, t),
                    COL_GRID,
                )
            }
        }

        val errors = if (u.dragButton >= 0) {
            val tmp = s.grid.copyOf()
            val cell = u.dsy * w + u.dsx
            tmp[cell] = dragXform(u, u.dsx, u.dsy, tmp[cell])
            findErrors(w, h, s.shared.numbers, tmp)
        } else {
            findErrors(w, h, s.shared.numbers, s.grid)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val cell = y * w + x
                var v = s.grid[cell]
                var credraw = false
                if (u.dragButton >= 0) v = dragXform(u, x, y, v)
                if (cmoved && ((x == cx && y == cy) || (x == ds.cx && y == ds.cy))) {
                    credraw = true
                }
                v = v or errors[cell]
                if (ds.drawn[cell] != v || credraw) {
                    drawTile(sink, ds, x, y, v, x == cx && y == cy, t)
                    ds.drawn[cell] = v
                }
            }
        }

        for (x in 0 until w) {
            if (ds.numbersDrawn[x] != errors[w * h + x]) {
                sink.rect(
                    coord(x, t),
                    coord(h, t) + 1,
                    t,
                    t * 3 / 2 - 1,
                    COL_BACKGROUND,
                )
                sink.text(
                    coord(x, t) + t / 2,
                    coord(h + 1, t),
                    Text.FONT_VARIABLE,
                    t / 2,
                    Text.ALIGN_HCENTRE or Text.ALIGN_VNORMAL,
                    if (errors[w * h + x] != 0) COL_ERROR else COL_GRID,
                    s.shared.numbers[x].toString(),
                )
                sink.update(coord(x, t), coord(h, t) + 1, t, t * 3 / 2 - 1)
                ds.numbersDrawn[x] = errors[w * h + x]
            }
        }
        for (y in 0 until h) {
            if (ds.numbersDrawn[w + y] != errors[w * h + w + y]) {
                sink.rect(
                    coord(w, t) + 1,
                    coord(y, t),
                    t * 3 / 2 - 1,
                    t,
                    COL_BACKGROUND,
                )
                sink.text(
                    coord(w + 1, t),
                    coord(y, t) + t / 2,
                    Text.FONT_VARIABLE,
                    t / 2,
                    Text.ALIGN_HRIGHT or Text.ALIGN_VCENTRE,
                    if (errors[w * h + w + y] != 0) COL_ERROR else COL_GRID,
                    s.shared.numbers[w + y].toString(),
                )
                sink.update(coord(w, t) + 1, coord(y, t), t * 3 / 2 - 1, t)
                ds.numbersDrawn[w + y] = errors[w * h + w + y]
            }
        }

        if (cmoved) {
            ds.cx = cx
            ds.cy = cy
        }
    }

    /** `COORD(x)`: the tile origin. */
    private fun coord(value: Int, tileSize: Int): Int =
        value * tileSize + tileSize / 2

    /** `draw_tile`, after the error bits are split off. */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        value: Int,
        cur: Boolean,
        tileSize: Int,
    ) {
        val t = tileSize
        val err = value and 15.inv()
        val v = value and 15
        val tx = coord(x, t)
        val ty = coord(y, t)
        val cx = tx + t / 2
        val cy = ty + t / 2

        sink.clip(tx, ty, t, t)
        sink.rect(tx, ty, t, t, COL_GRID)
        sink.rect(
            tx + 1,
            ty + 1,
            t - 1,
            t - 1,
            if (v == BLANK) COL_BACKGROUND else COL_GRASS,
        )

        if (v == TREE) {
            sink.rect(
                cx - t / 15,
                ty + t * 3 / 10,
                2 * (t / 15) + 1,
                t * 9 / 10 - t * 3 / 10,
                if (err and (1 shl ERR_OVERCOMMITTED) != 0) COL_ERRTRUNK else COL_TREETRUNK,
            )
            val col = if (err and (1 shl ERR_OVERCOMMITTED) != 0) COL_ERROR else COL_TREELEAF
            sink.circle(cx, ty + t * 4 / 10, t / 4, col, col)
            sink.circle(cx + t / 5, ty + t / 4, t / 8, col, col)
            sink.circle(cx - t / 5, ty + t / 4, t / 8, col, col)
            sink.circle(cx + t / 4, ty + t * 6 / 13, t / 8, col, col)
            sink.circle(cx - t / 4, ty + t * 6 / 13, t / 8, col, col)
        } else if (v == TENT) {
            val col = if (err and (1 shl ERR_OVERCOMMITTED) != 0) COL_ERROR else COL_TENT
            sink.poly(
                intArrayOf(
                    cx - t / 3,
                    cy + t / 3,
                    cx + t / 3,
                    cy + t / 3,
                    cx,
                    cy - t / 3,
                ),
                col,
                col,
            )
        }

        if (err and (1 shl ERR_ADJ_TOPLEFT) != 0) drawErrAdj(sink, tx, ty, t)
        if (err and (1 shl ERR_ADJ_TOP) != 0) drawErrAdj(sink, tx + t / 2, ty, t)
        if (err and (1 shl ERR_ADJ_TOPRIGHT) != 0) drawErrAdj(sink, tx + t, ty, t)
        if (err and (1 shl ERR_ADJ_LEFT) != 0) drawErrAdj(sink, tx, ty + t / 2, t)
        if (err and (1 shl ERR_ADJ_RIGHT) != 0) drawErrAdj(sink, tx + t, ty + t / 2, t)
        if (err and (1 shl ERR_ADJ_BOTLEFT) != 0) drawErrAdj(sink, tx, ty + t, t)
        if (err and (1 shl ERR_ADJ_BOT) != 0) drawErrAdj(sink, tx + t / 2, ty + t, t)
        if (err and (1 shl ERR_ADJ_BOTRIGHT) != 0) drawErrAdj(sink, tx + t, ty + t, t)

        if (cur) {
            val coff = t / 8
            val x0 = tx + coff
            val y0 = ty + coff
            val x1 = tx + t - coff
            val y1 = ty + t - coff
            sink.poly(
                intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0),
                -1,
                COL_GRID,
            )
        }

        sink.unclip()
        sink.update(tx + 1, ty + 1, t - 1, t - 1)
    }

    /** `draw_err_adj`: a red diamond with a white exclamation mark. */
    private fun drawErrAdj(sink: DrawSink, x: Int, y: Int, tileSize: Int) {
        val t = tileSize
        sink.poly(
            intArrayOf(
                x - t * 2 / 5,
                y,
                x,
                y - t * 2 / 5,
                x + t * 2 / 5,
                y,
                x,
                y + t * 2 / 5,
            ),
            COL_ERROR,
            COL_GRID,
        )
        val xext = t / 16
        val yext = t * 2 / 5 - (xext * 2 + 2)
        sink.rect(x - xext, y - yext, xext * 2 + 1, yext * 2 + 1 - xext * 3, COL_ERRTEXT)
        sink.rect(
            x - xext,
            y + yext - xext * 2 + 1,
            xext * 2 + 1,
            xext * 2,
            COL_ERRTEXT,
        )
    }

    /** `game_text_format`: a fixed rectangle whose rows end in `\n`. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val cw = 4
        val ch = 2
        val gw = (w + 1) * cw + 2
        val gh = (h + 1) * ch + 1
        val len = gw * gh
        val board = CharArray(len + 1) { ' ' }
        board[len - 2] = '\n'
        board[len - 1] = '\u0000'

        for (r in 0..h) {
            for (c in 0..w) {
                val cell = r * ch * gw + cw * c
                val center = cell + gw * ch / 2 + cw / 2
                val index = r * w + c
                var n = 1000

                if (r == h && c == w) {
                    // The corner is blank.
                } else if (c == w) {
                    n = s.shared.numbers[w + r]
                } else if (r == h) {
                    n = s.shared.numbers[c]
                } else {
                    when (s.grid[index]) {
                        BLANK -> board[center] = '.'
                        TREE -> board[center] = 'T'
                        TENT -> {
                            board[center - 1] = '/'
                            board[center] = '/'
                            board[center + 1] = '\\'
                        }

                        NONTENT -> Unit
                        else -> {
                            board[center - 1] = 'w'
                            board[center] = 't'
                            board[center + 1] = 'f'
                        }
                    }
                }

                if (n < 100) {
                    board[center] = '0' + n % 10
                    if (n >= 10) board[center - 1] = '0' + n / 10
                } else if (n < 1000) {
                    board[center + 1] = '0' + n % 10
                    board[center] = '0' + n / 10 % 10
                    board[center - 1] = '0' + n / 100
                }

                board[cell] = '+'
                for (j in 1 until cw) board[cell + j] = '-'
                for (i in 1 until ch) board[cell + i * gw] = '|'
            }

            for (c in 0 until ch) {
                board[(r * ch + c) * gw + gw - 2] =
                    if (c == 0) '+' else if (r < h) '|' else ' '
                board[(r * ch + c) * gw + gw - 1] = '\n'
            }
        }

        for (i in 0 until gw - 2 - cw) board[len - gw + i] = '-'
        for (c in 0..w) board[len - gw + cw * c] = '+'

        return board.concatToString(0, len - 1)
    }

    /** `game_status`: completed or open. */
    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** Tents has no status bar; the text is empty. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    /**
     * `new_game_desc` with an attempt counter. The order of the draws is
     * the contract: a retry redraws everything, so a wrong condition
     * changes later seeds, not only the current one.
     */
    internal fun generate(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generation {
        val w = params.w
        val h = params.h
        require(w.toLong() * h <= MAX_AREA) { Game.AREA_TOO_LARGE }
        val ntrees = w * h / 5
        val diff = if (params.diff > DIFF_EASY && w <= 4 && h <= 4) {
            DIFF_EASY
        } else {
            params.diff
        }
        val wh = w * h

        val grid = IntArray(wh)
        val puzzle = IntArray(wh)
        val numbers = IntArray(w + h)
        val soln = IntArray(wh)
        val order = IntArray(wh)
        val treemap = IntArray(wh)
        val max = maxOf(w, h)
        val locs = IntArray(max)
        val place = IntArray(max)
        val mrows = IntArray(3 * max)
        val trows = IntArray(3 * max)

        var attempts = 0
        while (true) {
            cancel.check()
            attempts++

            for (i in 0 until wh) {
                order[i] = i
                treemap[i] = -1
            }
            grid.fill(BLANK)
            var j = ntrees
            var nr = 0
            var i = 0
            while (j > 0 && i + j <= wh) {
                val which = i + random.upto((wh - i).toLong()).toInt()
                val tmp = order[which]
                order[which] = order[i]
                order[i] = tmp

                val x = order[i] % w
                val y = order[i] / w
                var ok = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (x + dx in 0 until w && y + dy in 0 until h &&
                            grid[(y + dy) * w + (x + dx)] == TENT
                        ) {
                            ok = false
                        }
                    }
                }

                if (ok) {
                    grid[order[i]] = TENT
                    for (d in 1 until MAXDIR) {
                        val x2 = x + dx(d)
                        val y2 = y + dy(d)
                        if (x2 in 0 until w && y2 in 0 until h &&
                            treemap[y2 * w + x2] == -1
                        ) {
                            treemap[y2 * w + x2] = nr++
                        }
                    }
                    j--
                }
                i++
            }
            if (j > 0) continue

            // The left vertices are the tents in row-major order; each
            // list holds the adjacent tree-candidate ids in U, L, R, D.
            var nl = 0
            val adjacency = Array(ntrees) { IntArray(0) }
            for (cell in 0 until wh) {
                if (grid[cell] != TENT) continue
                val x = cell % w
                val y = cell / w
                val list = IntArray(4)
                var size = 0
                for (d in 1 until MAXDIR) {
                    val x2 = x + dx(d)
                    val y2 = y + dy(d)
                    if (x2 in 0 until w && y2 in 0 until h) {
                        val id = treemap[y2 * w + x2]
                        check(id != -1) { "a tent neighbour has no tree-candidate id" }
                        list[size++] = id
                    }
                }
                adjacency[nl++] = list.copyOf(size)
            }
            check(nl == ntrees) { "tent count disagrees with ntrees" }

            val matching = Matching.match(ntrees, nr, adjacency, random)
            if (matching.count < ntrees) continue

            for (cell in 0 until wh) {
                val id = treemap[cell]
                if (id != -1 && matching.rightToLeft[id] != -1) {
                    grid[cell] = TREE
                }
            }

            var emptyColumn = false
            for (x in 0 until w) {
                var nonBlank = false
                for (y in 0 until h) {
                    if (grid[y * w + x] != BLANK) {
                        nonBlank = true
                        break
                    }
                }
                if (!nonBlank) {
                    emptyColumn = true
                    break
                }
            }
            if (emptyColumn) continue

            var emptyRow = false
            for (y in 0 until h) {
                var nonBlank = false
                for (x in 0 until w) {
                    if (grid[y * w + x] != BLANK) {
                        nonBlank = true
                        break
                    }
                }
                if (!nonBlank) {
                    emptyRow = true
                    break
                }
            }
            if (emptyRow) continue

            for (x in 0 until w) {
                var count = 0
                for (y in 0 until h) if (grid[y * w + x] == TENT) count++
                numbers[x] = count
            }
            for (y in 0 until h) {
                var count = 0
                for (x in 0 until w) if (grid[y * w + x] == TENT) count++
                numbers[w + y] = count
            }

            for (cell in 0 until wh) {
                puzzle[cell] = if (grid[cell] == TREE) TREE else BLANK
            }
            val easy = tentsSolve(
                w,
                h,
                puzzle,
                numbers,
                soln,
                locs,
                place,
                mrows,
                trows,
                diff - 1,
            )
            val hard = tentsSolve(
                w,
                h,
                puzzle,
                numbers,
                soln,
                locs,
                place,
                mrows,
                trows,
                diff,
            )
            if (easy == 2 && hard == 1) break
        }

        val desc = StringBuilder()
        var run = 0
        for (cell in 0..wh) {
            val tree = cell < wh && grid[cell] == TREE || cell == wh
            if (tree) {
                desc.append(if (run == 0) '_' else ('a' + run - 1))
                run = 0
            } else {
                run++
                while (run > 25) {
                    desc.append('z')
                    run -= 25
                }
            }
        }
        for (value in numbers) desc.append(',').append(value)

        // The C aux answer: `S` then one `;Tx,y` per tent in row-major
        // order (`tents.c:1175-1182`).
        val aux = StringBuilder("S")
        for (cell in 0 until wh) {
            if (grid[cell] == TENT) {
                aux.append(";T").append(cell % w).append(',').append(cell / w)
            }
        }
        return Generation(desc.toString(), aux.toString(), attempts)
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    private fun dx(d: Int): Int = (if (d == R) 1 else 0) - (if (d == L) 1 else 0)

    private fun dy(d: Int): Int = (if (d == D) 1 else 0) - (if (d == U) 1 else 0)

    private fun opposite(d: Int): Int = U + D - d

    /**
     * `tents_solve`. Returns 0 for impossibility, 1 for success, and 2
     * for a stall or ambiguity. [links] is not passed in because the C
     * scratch is fresh per call.
     */
    private fun tentsSolve(
        w: Int,
        h: Int,
        grid: IntArray,
        numbers: IntArray,
        soln: IntArray,
        locs: IntArray,
        place: IntArray,
        mrows: IntArray,
        trows: IntArray,
        diff: Int,
    ): Int {
        val wh = w * h
        val links = IntArray(wh) { N }
        grid.copyInto(soln)

        while (true) {
            var doneSomething = false

            // A tent with exactly one adjacent unlinked tree links to it.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val cell = y * w + x
                    if (soln[cell] != TENT || links[cell] != N) continue
                    var linkd = 0
                    var d = 1
                    while (d < MAXDIR) {
                        val x2 = x + dx(d)
                        val y2 = y + dy(d)
                        if (x2 in 0 until w && y2 in 0 until h &&
                            soln[y2 * w + x2] == TREE &&
                            links[y2 * w + x2] == N
                        ) {
                            if (linkd != 0) break
                            linkd = d
                        }
                        d++
                    }
                    if (d == MAXDIR && linkd == 0) return 0
                    if (d == MAXDIR) {
                        val x2 = x + dx(linkd)
                        val y2 = y + dy(linkd)
                        links[cell] = linkd
                        links[y2 * w + x2] = opposite(linkd)
                        doneSomething = true
                    }
                }
            }

            if (doneSomething) continue
            if (diff < 0) break

            // A blank with no adjacent unlinked tree cannot be a tent.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val cell = y * w + x
                    if (soln[cell] != BLANK) continue
                    var canBeTent = false
                    for (d in 1 until MAXDIR) {
                        val x2 = x + dx(d)
                        val y2 = y + dy(d)
                        if (x2 in 0 until w && y2 in 0 until h &&
                            soln[y2 * w + x2] == TREE &&
                            links[y2 * w + x2] == N
                        ) {
                            canBeTent = true
                        }
                    }
                    if (!canBeTent) {
                        soln[cell] = NONTENT
                        doneSomething = true
                    }
                }
            }

            if (doneSomething) continue

            // A blank next to any tent (including diagonally) is a non-tent.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val cell = y * w + x
                    if (soln[cell] != BLANK) continue
                    var impossible = false
                    for (yy in -1..1) {
                        for (xx in -1..1) {
                            if (yy == 0 && xx == 0) continue
                            val x2 = x + xx
                            val y2 = y + yy
                            if (x2 in 0 until w && y2 in 0 until h &&
                                soln[y2 * w + x2] == TENT
                            ) {
                                impossible = true
                            }
                        }
                    }
                    if (impossible) {
                        soln[cell] = NONTENT
                        doneSomething = true
                    }
                }
            }

            if (doneSomething) continue

            // A tree with one candidate forces its tent. At Tricky, two
            // diagonal candidates rule out the square next to both.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val cell = y * w + x
                    if (soln[cell] != TREE || links[cell] != N) continue
                    var linkd = 0
                    var linkd2 = 0
                    var nd = 0
                    for (d in 1 until MAXDIR) {
                        val x2 = x + dx(d)
                        val y2 = y + dy(d)
                        if (x2 < 0 || x2 >= w || y2 < 0 || y2 >= h) continue
                        val v = soln[y2 * w + x2]
                        if (v == BLANK || (v == TENT && links[y2 * w + x2] == N)) {
                            if (linkd != 0) linkd2 = d else linkd = d
                            nd++
                        }
                    }

                    if (nd == 0) return 0
                    if (nd == 1) {
                        val x2 = x + dx(linkd)
                        val y2 = y + dy(linkd)
                        soln[y2 * w + x2] = TENT
                        links[cell] = linkd
                        links[y2 * w + x2] = opposite(linkd)
                        doneSomething = true
                    } else if (nd == 2 && (dx(linkd) == 0) != (dx(linkd2) == 0) &&
                        diff >= DIFF_TRICKY
                    ) {
                        val x2 = x + dx(linkd) + dx(linkd2)
                        val y2 = y + dy(linkd) + dy(linkd2)
                        check(x2 in 0 until w && y2 in 0 until h) {
                            "the two candidates of a tree are not diagonal"
                        }
                        if (soln[y2 * w + x2] == BLANK) {
                            soln[y2 * w + x2] = NONTENT
                            doneSomething = true
                        }
                    }
                }
            }

            if (doneSomething) continue

            // The row and column combination pass.
            for (i in 0 until w + h) {
                val start: Int
                val step: Int
                val len: Int
                var start1: Int
                var start2: Int
                if (i < w) {
                    start = i
                    step = w
                    len = h
                    start1 = if (i > 0) start - 1 else -1
                    start2 = if (i + 1 < w) start + 1 else -1
                } else {
                    start = (i - w) * w
                    step = 1
                    len = w
                    start1 = if (i > w) start - w else -1
                    start2 = if (i + 1 < w + h) start + w else -1
                }
                if (diff < DIFF_TRICKY) {
                    start1 = -1
                    start2 = -1
                }

                var k = numbers[i]
                var n = 0
                for (j in 0 until len) {
                    when (soln[start + j * step]) {
                        TENT -> k--
                        BLANK -> locs[n++] = j
                    }
                }
                if (n == 0) continue

                for (j in 0 until n) place[j] = if (j < k) TENT else NONTENT

                mrows.fill(MAGIC, 0, 3 * len)

                while (true) {
                    var valid = true
                    for (j in 0 until n - 1) {
                        if (place[j] == TENT && place[j + 1] == TENT &&
                            locs[j + 1] == locs[j] + 1
                        ) {
                            valid = false
                            break
                        }
                    }

                    if (valid) {
                        for (j in 0 until len) trows[j] = MAGIC
                        for (j in len until 3 * len) trows[j] = BLANK
                        for (j in 0 until n) {
                            trows[locs[j]] = place[j]
                            if (place[j] == TENT) {
                                for (jj in locs[j] - 1..locs[j] + 1) {
                                    if (jj in 0 until len) {
                                        trows[len + jj] = NONTENT
                                        trows[2 * len + jj] = NONTENT
                                    }
                                }
                            }
                        }
                        for (j in 0 until 3 * len) {
                            if (trows[j] == MAGIC) continue
                            mrows[j] = if (mrows[j] == MAGIC || mrows[j] == trows[j]) {
                                trows[j]
                            } else {
                                BLANK
                            }
                        }
                    }

                    // The C loop's side effects decide the exit. The
                    // rightmost movable tent moves right and the tents
                    // after it shunt left.
                    var p = 0
                    var j = n - 1
                    while (j > 0) {
                        if (place[j] == TENT) p++
                        if (place[j] == NONTENT && place[j - 1] == TENT) {
                            place[j - 1] = NONTENT
                            place[j] = TENT
                            while (p > 0) {
                                p--
                                j++
                                place[j] = TENT
                            }
                            j++
                            while (j < n) {
                                place[j] = NONTENT
                                j++
                            }
                            break
                        }
                        j--
                    }
                    if (j <= 0) break
                }

                if (mrows[locs[0]] == MAGIC) return 0

                for (j in 0 until len) {
                    for (whichrow in 0..2) {
                        val offset = whichrow * len
                        val tstart = when (whichrow) {
                            0 -> start
                            1 -> start1
                            else -> start2
                        }
                        if (tstart >= 0 &&
                            mrows[offset + j] != MAGIC &&
                            mrows[offset + j] != BLANK &&
                            soln[tstart + j * step] == BLANK
                        ) {
                            soln[tstart + j * step] = mrows[offset + j]
                            doneSomething = true
                        }
                    }
                }
            }

            if (!doneSomething) break
        }

        for (cell in 0 until wh) {
            if (soln[cell] == BLANK) return 2
            if (soln[cell] != NONTENT && links[cell] == N) return 2
        }
        return 1
    }

    // ------------------------------------------------------------------
    // Errors.
    // ------------------------------------------------------------------

    /** `find_errors`: one cell bitmask per cell, then columns, then rows. */
    internal fun findErrors(
        w: Int,
        h: Int,
        numbers: IntArray,
        grid: IntArray,
    ): IntArray {
        val ret = IntArray(w * h + w + h)
        val tmp = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val cell = y * w + x
                if (y + 1 < h && x + 1 < w &&
                    ((grid[cell] == TENT && grid[(y + 1) * w + (x + 1)] == TENT) ||
                        (grid[(y + 1) * w + x] == TENT && grid[y * w + (x + 1)] == TENT))
                ) {
                    ret[cell] = ret[cell] or (1 shl ERR_ADJ_BOTRIGHT)
                    ret[(y + 1) * w + x] = ret[(y + 1) * w + x] or
                        (1 shl ERR_ADJ_TOPRIGHT)
                    ret[y * w + (x + 1)] = ret[y * w + (x + 1)] or
                        (1 shl ERR_ADJ_BOTLEFT)
                    ret[(y + 1) * w + (x + 1)] = ret[(y + 1) * w + (x + 1)] or
                        (1 shl ERR_ADJ_TOPLEFT)
                }
                if (y + 1 < h &&
                    grid[cell] == TENT && grid[(y + 1) * w + x] == TENT
                ) {
                    ret[cell] = ret[cell] or (1 shl ERR_ADJ_BOT)
                    ret[(y + 1) * w + x] = ret[(y + 1) * w + x] or (1 shl ERR_ADJ_TOP)
                }
                if (x + 1 < w &&
                    grid[cell] == TENT && grid[y * w + (x + 1)] == TENT
                ) {
                    ret[cell] = ret[cell] or (1 shl ERR_ADJ_RIGHT)
                    ret[y * w + (x + 1)] = ret[y * w + (x + 1)] or (1 shl ERR_ADJ_LEFT)
                }
            }
        }

        for (x in 0 until w) {
            var tents = 0
            var maybe = 0
            for (y in 0 until h) {
                when (grid[y * w + x]) {
                    TENT -> tents++
                    BLANK -> maybe++
                }
            }
            ret[w * h + x] = if (tents > numbers[x] || tents + maybe < numbers[x]) 1 else 0
        }
        for (y in 0 until h) {
            var tents = 0
            var maybe = 0
            for (x in 0 until w) {
                when (grid[y * w + x]) {
                    TENT -> tents++
                    BLANK -> maybe++
                }
            }
            ret[w * h + w + y] =
                if (tents > numbers[w + y] || tents + maybe < numbers[w + y]) 1 else 0
        }

        val dsf = Dsf.new(w * h)

        for (y in 0 until h) {
            for (x in 0 until w - 1) {
                val a = y * w + x
                if ((grid[a] == TREE && grid[a + 1] == TENT) ||
                    (grid[a] == TENT && grid[a + 1] == TREE)
                ) {
                    dsf.merge(a, a + 1)
                }
            }
        }
        for (y in 0 until h - 1) {
            for (x in 0 until w) {
                val a = y * w + x
                if ((grid[a] == TREE && grid[a + w] == TENT) ||
                    (grid[a] == TENT && grid[a + w] == TREE)
                ) {
                    dsf.merge(a, a + w)
                }
            }
        }
        tmp.fill(0)
        for (x in 0 until w * h) {
            val root = dsf.canonify(x)
            if (grid[x] == TREE) tmp[root]++
            else if (grid[x] == TENT) tmp[root]--
        }
        for (x in 0 until w * h) {
            val root = dsf.canonify(x)
            if (grid[x] == TENT && tmp[root] < 0) {
                ret[x] = ret[x] or (1 shl ERR_OVERCOMMITTED)
            }
        }

        dsf.reinit()
        for (y in 0 until h) {
            for (x in 0 until w - 1) {
                val a = y * w + x
                if ((grid[a] == TREE && isTentOrBlank(grid[a + 1])) ||
                    (isTentOrBlank(grid[a]) && grid[a + 1] == TREE)
                ) {
                    dsf.merge(a, a + 1)
                }
            }
        }
        for (y in 0 until h - 1) {
            for (x in 0 until w) {
                val a = y * w + x
                if ((grid[a] == TREE && isTentOrBlank(grid[a + w])) ||
                    (isTentOrBlank(grid[a]) && grid[a + w] == TREE)
                ) {
                    dsf.merge(a, a + w)
                }
            }
        }
        tmp.fill(0)
        for (x in 0 until w * h) {
            val root = dsf.canonify(x)
            if (grid[x] == TREE) tmp[root]++
            else if (isTentOrBlank(grid[x])) tmp[root]--
        }
        for (x in 0 until w * h) {
            val root = dsf.canonify(x)
            if (grid[x] == TREE && tmp[root] > 0) {
                ret[x] = ret[x] or (1 shl ERR_OVERCOMMITTED)
            }
        }

        return ret
    }

    private fun isTentOrBlank(value: Int): Boolean = value == TENT || value == BLANK

    // ------------------------------------------------------------------
    // Parsers.
    // ------------------------------------------------------------------

    private class ScannedInt(val value: Int, val end: Int)

    /**
     * One `%d` conversion: white space, an optional sign, then digits.
     * A value outside the `long` range saturates, and the assignment to
     * `int` keeps the low 32 bits, as the other ports do.
     */
    private fun scanInt(text: String, start: Int): ScannedInt? {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++

        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }

        val firstDigit = i
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            if (!overflow) {
                val digit = text[i] - '0'
                if (value > (Long.MAX_VALUE - digit) / 10) {
                    overflow = true
                } else {
                    value = value * 10 + digit
                }
            }
            i++
        }
        if (i == firstDigit) return null

        val parsed = when {
            overflow -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            negative -> -value
            else -> value
        }
        return ScannedInt(parsed.toInt(), i)
    }

    /** C `atoi`: white space, an optional sign, then digits. */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++

        var sign = 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            if (text[i] == '-') sign = -1
            i++
        }

        var value = 0L
        while (i < text.length && text[i] in '0'..'9') {
            value = value * 10 + (text[i] - '0')
            if (value > Int.MAX_VALUE) {
                return if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE
            }
            i++
        }
        return sign * value.toInt()
    }

    /** The white space set of C `isspace` in the C locale. */
    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
