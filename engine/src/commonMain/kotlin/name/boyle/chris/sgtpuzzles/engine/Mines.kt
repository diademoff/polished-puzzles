package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * A port of upstream `mines.c`: Minesweeper with a deferred, gated
 * layout.
 *
 * The rules, the seed, the description, and the save format are upstream
 * behavior. Three parts dominate the port:
 *
 * - The interactive description is `r<N>,<u|a>,<rng state>` and holds no
 *   layout. [newState] keeps the generator, and the first uncover draws
 *   the mines and replaces the description through [publicDesc] and
 *   [privateDesc]; the midend writes them as `DESC` and `PRIVDESC`.
 * - The generator is gated by a deduction solver. The solver uses a
 *   `tree234` ordered by `(y, x, mask)` and perturbs the board with RNG
 *   draws when it stalls. The port keeps the tree order, the
 *   `random_bits(31)` draw for every candidate, the two upstream quirks
 *   (`nperturbs_since_last_new_open` incremented twice, and the dead
 *   `prevret`), and the row-major order everywhere.
 * - Mines is the timed game: [isTimed] is true, the clock runs while the
 *   board is live and unsolved, and the UI record is `D<deaths>[C]`.
 *
 * The `Layout` object is shared by every state of one game and is
 * mutated when the first uncover generates the board, as the C
 * reference count shares it. [State.copy] shares it on purpose, so undo
 * from the first click still shows the generated board and the start
 * cross.
 *
 * C undefined behavior and assertions:
 *
 * - The generator's candidate scan has no bounds test. Bits outside the
 *   grid are impossible in a reachable set, because every mask comes
 *   from an in-grid 3x3 window. A guard skips such a bit instead of
 *   reading a neighbouring row, as the C assertion intends.
 * - `mineperturb` asserts `ntoempty != 0` and `i > ntoempty`, and
 *   `minesolve` asserts that a perturbation has changes. A reachable
 *   case would abort C. The port returns no perturbation instead, so
 *   the solver gives up and the generator retries; [minegen] is
 *   unbounded, as in C, so a pathological parameter set can run for a
 *   long time. Tests carry timeouts.
 * - The bitmap decoder asserts that the text is long enough.
 *   [newState] throws `IllegalArgumentException` for a direct call with
 *   a short bitmap; `validateDesc` rejects it first.
 * - A direct `newGame` with an out-of-grid leading click, or with more
 *   mines than candidate squares, reads outside the board or calls
 *   `random_upto(0)` in C. The port returns without opening and rejects
 *   the draw with `IllegalArgumentException`; the midend validation
 *   rejects both first.
 * - `current_key_label` reads the cursor cell without a bounds test.
 *   The port returns an empty label for an out-of-range cursor, and a
 *   cursor select on such a cursor is a no-effect key.
 * - `atoi`, `sscanf("%d")`, and `decode_ui` overflow is undefined in C.
 *   The port saturates the two parsers at the `Int` limits. The
 *   `decode_ui` assignment matches the glibc result instead: the count
 *   is read as a `long` and its low 32 bits become the `int`, so
 *   `D2147483648` is `-2147483648`.
 */
object Mines : Game {

    /** The colour indices of `mines.c:24-32`. */
    private const val COL_BACKGROUND = 0
    private const val COL_BACKGROUND2 = 1
    private const val COL_1 = 2
    private const val COL_2 = 3
    private const val COL_3 = 4
    private const val COL_4 = 5
    private const val COL_5 = 6
    private const val COL_6 = 7
    private const val COL_7 = 8
    private const val COL_8 = 9
    private const val COL_MINE = 10
    private const val COL_BANG = 11
    private const val COL_CROSS = 12
    private const val COL_FLAG = 13
    private const val COL_FLAGBASE = 14
    private const val COL_QUERY = 15
    private const val COL_HIGHLIGHT = 16
    private const val COL_LOWLIGHT = 17
    private const val COL_WRONGNUMBER = 18
    private const val COL_CURSOR = 19
    internal const val NCOLOURS = 20

    private const val PREFERRED_TILE_SIZE = 20

    /** `SMALL_SCREEN`: the Android build uses the constant border. */
    private const val BORDER = 8

    /** The grid values of `mines.c:86-107`. */
    internal const val MARKED = -1
    internal const val UNKNOWN = -2
    internal const val QUESTION = -3
    internal const val START_HERE = -4
    private const val TO_DO = -10
    private const val REVEALED_MINE = 64
    private const val TRODDEN_MINE = 65
    private const val WRONG_MARK = 66

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val n: Int,
        val unique: Boolean,
        val firstClickX: Int,
        val firstClickY: Int,
    ) : Game.Params {
        override fun copy(): Game.Params =
            Params(w, h, n, unique, firstClickX, firstClickY)
    }

    /**
     * `struct mine_layout`: the shared, mutable mine data.
     *
     * C reference-counts it; the garbage collector replaces the count.
     * [mines] is null until the first uncover; [rs] is the generator
     * state for that uncover and is dropped afterwards. [publicDesc] and
     * [privateDesc] carry the description pair that C hands to
     * `midend_supersede_game_desc` from inside `open_square`.
     */
    class Layout internal constructor() {
        var mines: BooleanArray? = null
        var n: Int = 0
        var unique: Boolean = false
        var rs: RandomState? = null
        var startX: Int = -1
        var startY: Int = -1
        var publicDesc: String? = null
        var privateDesc: String? = null
    }

    /**
     * `struct game_state`. The layout is shared and mutable;
     * [equals] does not compare it, so a copied state keeps the C
     * reference semantics.
     */
    class State internal constructor(
        val width: Int,
        val height: Int,
        val n: Int,
        val layout: Layout,
        val grid: IntArray,
        var dead: Boolean,
        var won: Boolean,
        var usedSolve: Boolean,
    ) : Game.State {

        fun valueAt(x: Int, y: Int): Int = grid[y * width + x]

        fun isMine(x: Int, y: Int): Boolean =
            layout.mines?.get(y * width + x) == true

        override fun copy(): Game.State =
            State(width, height, n, layout, grid.copyOf(), dead, won, usedSolve)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                n == other.n &&
                dead == other.dead &&
                won == other.won &&
                usedSolve == other.usedSolve &&
                grid.contentEquals(other.grid)

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + n
            result = 31 * result + dead.hashCode()
            result = 31 * result + won.hashCode()
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + grid.contentHashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. The deaths counter and the sticky completion
     * flag are saved in the `UI` record; the rest is frontend state.
     * Mines has no preferences: `get_prefs` and `set_prefs` are NULL.
     * The one environment input, `PUZZLES_SHOW_CURSOR`, is not a
     * preference and starts false on Android.
     */
    class Ui : Game.Ui {
        var hx: Int = -1
        var hy: Int = -1
        var hradius: Int = 0
        var validRadius: Int = 0
        var flashIsDeath: Boolean = false
        var deaths: Int = 0
        var completed: Boolean = false
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false
    }

    /**
     * `struct game_drawstate`. [grid] holds the drawn value after the
     * highlight, wrong-number, and start-cross transforms, so the dirty
     * test is a plain comparison. The flash time is not part of the
     * seam, so [flashIsDeath] never changes the background.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
    ) : Game.DrawState {
        var bg: Int = -1
        var started: Boolean = false
        val grid: IntArray = IntArray(width * height) { -99 }
        var curX: Int = -1
        var curY: Int = -1
    }

    override val name: String = "Mines"
    override val backendName: String = "mines"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true
    override val isTimed: Boolean = true

    /** `BUTTON_BEATS(LEFT_BUTTON, RIGHT_BUTTON) | REQUIRE_RBUTTON`. */
    override val flags: Int =
        Game.buttonBeatsBit(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON) or
            Game.REQUIRE_RBUTTON

    /** `default_params`: 9x9 with 10 mines, unique, no click override. */
    override fun defaultParams(): Game.Params = Params(9, 9, 10, true, -1, -1)

    /** `mines_presets` under `SMALL_SCREEN`. */
    override fun presets(): List<Preset> = listOf(
        Params(9, 9, 10, true, -1, -1),
        Params(9, 9, 35, true, -1, -1),
        Params(16, 16, 40, true, -1, -1),
        Params(16, 16, 99, true, -1, -1),
    ).map { Preset("${it.w}x${it.h}, ${it.n} mines", encodeParams(it, true)) }

    /**
     * `decode_params`. The parser starts from the default parameters, as
     * `midend_game_id_int` does, and keeps C's sign rule: `atoi` reads a
     * sign but the following digit scan does not, so `+9x9` leaves the
     * rest for the tail scan.
     */
    override fun decodeParams(text: String): Game.Params {
        var w = atoi(text, 0)
        var h = w
        var index = 0
        while (index < text.length && text[index] in '0'..'9') index++
        if (charAt(text, index) == 'x') {
            index++
            h = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        var n = 10
        var unique = true
        var firstClickX = -1
        var firstClickY = -1
        if (charAt(text, index) == 'n') {
            index++
            n = atoi(text, index)
            while (index < text.length && (text[index] == '.' || text[index] in '0'..'9')) {
                index++
            }
        } else if (h > 0 && w > 0 && w <= Int.MAX_VALUE / h) {
            n = w * h / 10
        }
        while (index < text.length) {
            when (text[index]) {
                'a' -> {
                    index++
                    unique = false
                }

                'X' -> {
                    index++
                    firstClickX = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'Y' -> {
                    index++
                    firstClickY = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                else -> index++
            }
        }
        // C mutates the default parameters; the decoders above already
        // start from their values. The local variables mirror the C
        // fields exactly.
        return Params(w, h, n, unique, firstClickX, firstClickY)
    }

    /** `encode_params`: the short form keeps only `WxH`. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.w).append('x').append(p.h)
        if (full) {
            out.append('n').append(p.n)
            if (!p.unique) out.append('a')
            if (p.firstClickX >= 0) out.append('X').append(p.firstClickX)
            if (p.firstClickY >= 0) out.append('Y').append(p.firstClickY)
        }
        return out.toString()
    }

    /** `validate_params`, in the C order and with the C messages. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (full && p.unique && (p.w <= 2 || p.h <= 2)) {
            return "Width and height must both be greater than two"
        }
        if (p.w < 1 || p.h < 1) {
            return "Width and height must both be at least one"
        }
        if (p.w > Short.MAX_VALUE || p.h > Short.MAX_VALUE) {
            return "Neither width nor height may be unreasonably large"
        }
        if (p.w > ((1 shl 28) - 1) / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.n < 0) return "Mine count may not be negative"
        if (p.n < 1) return "Number of mines must be greater than zero"
        if (p.n > p.w * p.h - 9) return "Too many mines for grid size"
        if (p.firstClickX >= p.w) {
            return "First-click x coordinate must be inside the grid"
        }
        if (p.firstClickY >= p.h) {
            return "First-click y coordinate must be inside the grid"
        }
        return null
    }

    /** `game_configure` (`mines.c:227-257`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Text("Mines", p.n.toString()),
            ConfigItem.Bool("Ensure solubility", p.unique),
        )
    }

    /**
     * `custom_params` (`mines.c:259-272`). A `%` in the mines string
     * makes the number a percentage of the area, with C integer
     * division. The call resets the first-click fields.
     */
    override fun customParams(items: List<ConfigItem>): Game.Params {
        val mines = (items[2] as ConfigItem.Text).value
        val w = atoi((items[0] as ConfigItem.Text).value, 0)
        val h = atoi((items[1] as ConfigItem.Text).value, 0)
        var n = atoi(mines, 0)
        if (mines.contains('%')) n = n * (w * h) / 100
        return Params(w, h, n, (items[3] as ConfigItem.Bool).value, -1, -1)
    }

    /**
     * `new_game_desc` as the app sees it: the interactive form only. The
     * two `random_upto` draws happen first, so interactive and batch
     * generation consume the same prefix; the mine count and uniqueness
     * flag are encoded for the deferred generator.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        drawStart(p, random)
        return Description(
            "r${p.n},${if (p.unique) 'u' else 'a'},${random.encodeState()}",
            null,
        )
    }

    // `mines.c` minegen re-solves after every perturbation and retries without a bound; the largest preset is 16x16 and the accepted area is 2^28 - 1.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w.toLong() * p.h > 256) Game.SLOW_GENERATION_WARNING else null
    }

    /**
     * `new_game_desc` with `interactive = false`, the `x,y,m<mask>`
     * form. The seam always passes a drawing API, so [newDesc] never
     * uses this branch; the helper keeps the C order for tests and for
     * a future batch caller.
     */
    fun newBatchDesc(params: Params, random: RandomState): Description {
        val start = drawStart(params, random)
        val layout = newMineLayout(
            params.w,
            params.h,
            params.n,
            start[0],
            start[1],
            params.unique,
            random,
        )
        return Description(layout.desc, null)
    }

    /** The two prefix draws and the batch click override. */
    private fun drawStart(params: Params, random: RandomState): IntArray {
        var x = random.upto(params.w.toLong()).toInt()
        var y = random.upto(params.h.toLong()).toInt()
        if (params.firstClickX >= 0) x = params.firstClickX
        if (params.firstClickY >= 0) y = params.firstClickY
        return intArrayOf(x, y)
    }

    /**
     * `validate_desc`, in the C order. The `r` form checks only the
     * count and two commas and ignores the rest; the layout form checks
     * the optional click and the exact nibble length, but no character.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val wh = p.w * p.h
        var index = 0
        if (charAt(desc, 0) == 'r') {
            index = 1
            if (charAt(desc, index) == '\u0000' || charAt(desc, index) !in '0'..'9') {
                return "No initial mine count in game description"
            }
            if (atoi(desc, index) > wh - 9) return "Too many mines for grid size"
            while (charAt(desc, index) in '0'..'9') index++
            if (charAt(desc, index) != ',') {
                return "No ',' after initial x-coordinate in game description"
            }
            index++
            if (charAt(desc, index) != 'u' && charAt(desc, index) != 'a') {
                return "No uniqueness specifier in game description"
            }
            index++
            if (charAt(desc, index) != ',') {
                return "No ',' after uniqueness specifier in game description"
            }
        } else {
            if (charAt(desc, 0) in '0'..'9') {
                val x = atoi(desc, 0)
                if (x < 0 || x >= p.w) return "Initial x-coordinate was out of range"
                while (charAt(desc, index) in '0'..'9') index++
                if (charAt(desc, index) != ',') {
                    return "No ',' after initial x-coordinate in game description"
                }
                index++
                if (charAt(desc, index) !in '0'..'9') {
                    return "No initial y-coordinate in game description"
                }
                val y = atoi(desc, index)
                if (y < 0 || y >= p.h) {
                    return "Initial y-coordinate was out of range"
                }
                while (charAt(desc, index) in '0'..'9') index++
                if (charAt(desc, index) != ',') {
                    return "No ',' after initial y-coordinate in game description"
                }
                index++
            }
            if (charAt(desc, index) == 'm' || charAt(desc, index) == 'u') index++
            if (desc.length - index != (wh + 3) / 4) {
                return "Game description is wrong length"
            }
        }
        return null
    }

    /**
     * `new_game`. An `r` description leaves the layout pending and keeps
     * the generator state; a layout description decodes the bitmap and
     * opens a leading click. `layout.n` and `layout.unique` stay zero in
     * the layout branch and are never read once the bitmap exists.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.w * p.h
        val layout = Layout()
        val grid = IntArray(wh) { UNKNOWN }
        val state = State(p.w, p.h, p.n, layout, grid, false, false, false)
        var index = 0
        if (charAt(desc, 0) == 'r') {
            index = 1
            layout.n = atoi(desc, index)
            while (charAt(desc, index) in '0'..'9') index++
            if (charAt(desc, index) != '\u0000') index++
            layout.unique = charAt(desc, index) != 'a'
            index++
            if (charAt(desc, index) != '\u0000') index++
            layout.mines = null
            layout.rs = RandomState.decode(desc.substring(index))
        } else {
            layout.rs = null
            layout.mines = BooleanArray(wh)
            var x = -1
            var y = -1
            if (charAt(desc, index) in '0'..'9') {
                x = atoi(desc, index)
                while (charAt(desc, index) in '0'..'9') index++
                if (charAt(desc, index) != '\u0000') index++
                y = atoi(desc, index)
                while (charAt(desc, index) in '0'..'9') index++
                if (charAt(desc, index) != '\u0000') index++
            }
            val masked = when (charAt(desc, index)) {
                'm' -> {
                    index++
                    true
                }

                'u' -> {
                    index++
                    false
                }

                else -> false
            }
            val bitmap = decodeBitmap(desc.substring(index), wh, masked)
            val mines = checkNotNull(layout.mines)
            for (i in 0 until wh) {
                mines[i] = (bitmap[i / 8].toInt() and (0x80 shr (i % 8))) != 0
            }
            if (x >= 0 && y >= 0) openSquare(state, x, y)
        }
        return state
    }

    /**
     * `set_public_desc`: restores the first-click coordinate into the
     * shared layout after a load rebuilt state 0 from `PRIVDESC`. C
     * accepts only an in-grid pair and leaves the previous values
     * otherwise.
     */
    override fun setPublicDesc(state: Game.State, text: String): Game.State {
        val s = state as State
        var x = -1
        var y = -1
        var index = 0
        if (charAt(text, 0) in '0'..'9') {
            x = atoi(text, index)
            while (charAt(text, index) in '0'..'9') index++
            if (charAt(text, index) != '\u0000') index++
            y = atoi(text, index)
        }
        if (x >= 0 && y >= 0 && x < s.width && y < s.height) {
            s.layout.startX = x
            s.layout.startY = y
        }
        return s
    }

    /**
     * The public description of a state that generated its layout, or
     * null while the layout is pending. This is the game half of
     * `midend_supersede_game_desc`.
     */
    override fun publicDesc(state: Game.State): String? =
        (state as State).layout.publicDesc

    /** The matching `PRIVDESC`, or null while the layout is pending. */
    override fun privateDesc(state: Game.State): String? =
        (state as State).layout.privateDesc

    /** `solve_game`: the one-letter move, or null for a pending game. */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        if ((state as State).layout.mines == null) null else "S"

    /** The C error string of [solve], or null when a move is available. */
    fun solveError(state: Game.State): String? =
        if ((state as State).layout.mines == null) "Game has not been started yet" else null

    /** `solve_game` with the C error string (`mines.c:2415-2424`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        SolveOutcome(solve(state, auxInfo), solveError(state))

    /**
     * `execute_move`. `S` reveals the board, with the post-death
     * correction form when the player is already dead. Every other move
     * is a `;`-separated list of `F`, `O`, and `C` tokens; a token that
     * fails `sscanf` or its range test rejects the whole move, as C
     * does.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        if (move == "S") {
            if (from.layout.mines == null) return null
            val ret = from.copy() as State
            if (!ret.dead) {
                for (y in 0 until ret.height) {
                    for (x in 0 until ret.width) {
                        val pos = y * ret.width + x
                        ret.grid[pos] = if (ret.isMine(x, y)) {
                            MARKED
                        } else {
                            neighbourMines(ret, x, y)
                        }
                    }
                }
            } else {
                for (y in 0 until ret.height) {
                    for (x in 0 until ret.width) {
                        val pos = y * ret.width + x
                        if ((ret.grid[pos] == UNKNOWN || ret.grid[pos] == QUESTION) &&
                            ret.isMine(x, y)
                        ) {
                            ret.grid[pos] = REVEALED_MINE
                        } else if (ret.grid[pos] == MARKED && !ret.isMine(x, y)) {
                            ret.grid[pos] = WRONG_MARK
                        }
                    }
                }
            }
            ret.usedSolve = true
            return ret
        }

        if (from.dead) return null
        val ret = from.copy() as State
        var index = 0
        while (index < move.length) {
            val c = move[index]
            val pair = when (c) {
                'F', 'O', 'C' -> scanPair(move, index + 1)
                else -> null
            }
            if (pair == null) return null
            val (cx, cy) = pair
            when (c) {
                'F' -> {
                    if (cx < 0 || cx >= from.width || cy < 0 || cy >= from.height) {
                        return null
                    }
                    val pos = cy * from.width + cx
                    if (ret.grid[pos] != MARKED && ret.grid[pos] != UNKNOWN) return null
                    ret.grid[pos] = ret.grid[pos] xor (UNKNOWN xor MARKED)
                }

                'O' -> {
                    if (cx < 0 || cx >= from.width || cy < 0 || cy >= from.height) {
                        return null
                    }
                    openSquare(ret, cx, cy)
                }

                else -> {
                    if (cx < 0 || cx >= from.width || cy < 0 || cy >= from.height) {
                        return null
                    }
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx in 0 until ret.width && ny in 0 until ret.height) {
                                val pos = ny * ret.width + nx
                                if (ret.grid[pos] == UNKNOWN || ret.grid[pos] == QUESTION) {
                                    openSquare(ret, nx, ny)
                                }
                            }
                        }
                    }
                }
            }
            while (index < move.length && move[index] != ';') index++
            if (index < move.length) index++
        }
        return ret
    }

    /**
     * `open_square`: generates a pending layout and records the first
     * click, then opens the cell with a flood fill. A mine kills the
     * player and marks the cell with 65; otherwise the win test runs
     * when the player is still alive.
     */
    private fun openSquare(state: State, x: Int, y: Int): Int {
        val w = state.width
        val h = state.height
        // `validate_desc` bounds a leading click and the move grammar
        // bounds an `O` or `C` token, so this guard is unreachable
        // through the midend. A direct `newState` with an out-of-grid
        // click returns without opening instead of reading outside the
        // board, as C would.
        if (x !in 0 until w || y !in 0 until h) return 0
        val layout = state.layout
        if (layout.mines == null) {
            val mines = minegen(
                w,
                h,
                layout.n,
                x,
                y,
                layout.unique,
                checkNotNull(layout.rs),
            ).mines
            layout.mines = mines
            layout.startX = x
            layout.startY = y
            val desc = describeLayout(mines, w * h, x, y, true)
            layout.publicDesc = desc
            layout.privateDesc = privatePart(desc)
            layout.rs = null
        }
        val mines = checkNotNull(layout.mines)

        if (mines[y * w + x]) {
            state.dead = true
            state.grid[y * w + x] = TRODDEN_MINE
            return -1
        }

        state.grid[y * w + x] = TO_DO
        while (true) {
            var doneSomething = false
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    if (state.grid[yy * w + xx] != TO_DO) continue
                    check(!mines[yy * w + xx])
                    var v = 0
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            val nx = xx + dx
                            val ny = yy + dy
                            if (nx in 0 until w && ny in 0 until h &&
                                mines[ny * w + nx]
                            ) {
                                v++
                            }
                        }
                    }
                    state.grid[yy * w + xx] = v
                    if (v == 0) {
                        for (dx in -1..1) {
                            for (dy in -1..1) {
                                val nx = xx + dx
                                val ny = yy + dy
                                if (nx in 0 until w && ny in 0 until h &&
                                    state.grid[ny * w + nx] == UNKNOWN
                                ) {
                                    state.grid[ny * w + nx] = TO_DO
                                }
                            }
                        }
                    }
                    doneSomething = true
                }
            }
            if (!doneSomething) break
        }

        if (state.dead) return 0
        var nmines = 0
        var ncovered = 0
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                if (state.grid[yy * w + xx] < 0) ncovered++
                if (mines[yy * w + xx]) nmines++
            }
        }
        check(ncovered >= nmines)
        if (ncovered == nmines) {
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    if (state.grid[yy * w + xx] < 0) {
                        state.grid[yy * w + xx] = MARKED
                    }
                }
            }
            state.won = true
        }
        return 0
    }

    /** The mine count around an open cell. */
    private fun neighbourMines(state: State, x: Int, y: Int): Int {
        var v = 0
        for (dx in -1..1) {
            for (dy in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until state.width && ny in 0 until state.height &&
                    state.isMine(nx, ny)
                ) {
                    v++
                }
            }
        }
        return v
    }

    /**
     * `interpret_move`. A press records the highlight and the radius; a
     * release either opens a covered cell or chords an open one. The
     * radius asymmetry (a press sets `validradius`, a drag does not) is
     * part of the input contract.
     */
    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val from = state as State
        val u = ui as Ui
        val w = from.width
        val h = from.height
        if (from.dead || from.won) return MoveResult.Unused

        var cx = fromCoord(x, tileSize)
        var cy = fromCoord(y, tileSize)

        if (Buttons.isCursorMove(button)) {
            return moveCursor(button, u, w, h)
        }
        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            // `move_cursor` clamps the cursor, so this test is
            // unreachable; it keeps a hand-made `Ui` from reading
            // outside the grid.
            if (u.curX !in 0 until w || u.curY !in 0 until h) {
                return MoveResult.NoEffect
            }
            val v = from.grid[u.curY * w + u.curX]
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            if (button == Buttons.CURSOR_SELECT2) {
                if (v != UNKNOWN && v != MARKED) return MoveResult.NoEffect
                return MoveResult.Move("F${u.curX},${u.curY}")
            }
            if (v == UNKNOWN || v == QUESTION) {
                if (from.layout.mines != null && from.isMine(u.curX, u.curY)) {
                    u.deaths++
                }
                return MoveResult.Move("O${u.curX},${u.curY}")
            }
            cx = u.curX
            cy = u.curY
            u.validRadius = 1
            return uncover(from, u, cx, cy)
        }

        if (button == Buttons.LEFT_BUTTON || button == Buttons.LEFT_DRAG ||
            button == Buttons.MIDDLE_BUTTON || button == Buttons.MIDDLE_DRAG
        ) {
            if (cx < 0 || cx >= w || cy < 0 || cy >= h) return MoveResult.Unused
            u.hx = cx
            u.hy = cy
            u.hradius = if (from.grid[cy * w + cx] >= 0) 1 else 0
            if (button == Buttons.LEFT_BUTTON) {
                u.validRadius = u.hradius
            } else if (button == Buttons.MIDDLE_BUTTON) {
                u.validRadius = 1
            }
            u.curVisible = false
            return MoveResult.UiUpdate
        }

        if (button == Buttons.RIGHT_BUTTON) {
            if (cx < 0 || cx >= w || cy < 0 || cy >= h) return MoveResult.Unused
            u.curVisible = false
            if (from.grid[cy * w + cx] != UNKNOWN &&
                from.grid[cy * w + cx] != MARKED
            ) {
                u.validRadius = 1
                return uncover(from, u, cx, cy)
            }
            return MoveResult.Move("F$cx,$cy")
        }

        if (button == Buttons.LEFT_RELEASE || button == Buttons.MIDDLE_RELEASE) {
            u.hx = -1
            u.hy = -1
            u.hradius = 0
            if (cx < 0 || cx >= w || cy < 0 || cy >= h) {
                return MoveResult.UiUpdate
            }
            if (button == Buttons.LEFT_RELEASE &&
                (from.grid[cy * w + cx] == UNKNOWN ||
                    from.grid[cy * w + cx] == QUESTION) &&
                u.validRadius == 0
            ) {
                if (from.layout.mines != null && from.isMine(cx, cy)) u.deaths++
                return MoveResult.Move("O$cx,$cy")
            }
            return uncover(from, u, cx, cy)
        }
        return MoveResult.Unused
    }

    /** The shared `uncover:` label of `interpret_move`. */
    private fun uncover(from: State, u: Ui, cx: Int, cy: Int): MoveResult {
        val w = from.width
        val h = from.height
        if (from.grid[cy * w + cx] > 0 && u.validRadius == 1) {
            var n = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx in 0 until w && ny in 0 until h &&
                        from.grid[ny * w + nx] == MARKED
                    ) {
                        n++
                    }
                }
            }
            if (n == from.grid[cy * w + cx]) {
                val out = StringBuilder()
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx in 0 until w && ny in 0 until h &&
                            from.grid[ny * w + nx] != MARKED &&
                            from.layout.mines != null && from.isMine(nx, ny)
                        ) {
                            if (out.isNotEmpty()) out.append(';')
                            out.append('O').append(nx).append(',').append(ny)
                        }
                    }
                }
                if (out.isNotEmpty()) {
                    u.deaths++
                    return MoveResult.Move(out.toString())
                }
                return MoveResult.Move("C$cx,$cy")
            }
        }
        return MoveResult.UiUpdate
    }

    /** `move_cursor` with `wrap = false`, as `misc.c` implements it. */
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
        val ox = ui.curX
        val oy = ui.curY
        ui.curX = (ui.curX + dx).coerceIn(0, w - 1)
        ui.curY = (ui.curY + dy).coerceIn(0, h - 1)
        if (!ui.curVisible) {
            ui.curVisible = true
            return MoveResult.UiUpdate
        }
        return if (ui.curX != ox || ui.curY != oy) {
            MoveResult.UiUpdate
        } else {
            MoveResult.NoEffect
        }
    }

    /** `FROMCOORD` with C truncation toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - BORDER + tileSize) / tileSize - 1

    /** `COORD`. */
    private fun coord(value: Int, tileSize: Int): Int = value * tileSize + BORDER

    /**
     * `current_key_label`. C reads the cursor cell before it tests the
     * dead, won, and visible flags and without a bounds test; the port
     * returns an empty label for a cursor outside the grid.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (u.curX < 0 || u.curY < 0 || u.curX >= s.width || u.curY >= s.height) {
            return ""
        }
        val v = s.grid[u.curY * s.width + u.curX]
        if (s.dead || s.won || !u.curVisible) return ""
        if (button == Buttons.CURSOR_SELECT2) {
            if (v == UNKNOWN) return "Mark"
            if (v == MARKED) return "Unmark"
            return ""
        }
        if (button == Buttons.CURSOR_SELECT) {
            if (v == UNKNOWN || v == QUESTION) return "Uncover"
            if (v == 0) return ""
            var n = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = u.curX + dx
                    val ny = u.curY + dy
                    if (nx >= 0 && nx < s.width && ny >= 0 && ny < s.height &&
                        s.grid[ny * s.width + nx] == MARKED
                    ) {
                        n++
                    }
                }
            }
            if (n == v) return "Clear"
        }
        return ""
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell. A covered cell offers `Uncover` on the left
     * button and `Mark` on the right; a marked cell offers `Unmark`. A
     * revealed number offers `Clear` around it only when the flags
     * already match, which is the one case where the game's own
     * `current_key_label` names it, and a revealed zero offers nothing.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val t = tileSize
        val cells = gridSpeech(
            width = s.width,
            height = s.height,
            tileSize = t,
            left = BORDER,
            top = BORDER,
            summary = statusText(state, ui),
            text = { x, y ->
                val v = s.grid[y * s.width + x]
                val content = when (v) {
                    UNKNOWN -> "unopened"
                    MARKED -> "marked"
                    QUESTION -> "question"
                    REVEALED_MINE, TRODDEN_MINE -> "mine"
                    WRONG_MARK -> "marked$SPOKEN_WRONG"
                    0 -> "no mines nearby"
                    else -> "$v mines nearby"
                }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) ", cursor" else ""
                content + cursor
            },
            actions = { x, y -> actionsAt(s, x, y) },
        )
        val cursor = cells.units.getOrNull(u.curY * s.width + u.curX)
        return cells.capped(cursor)
    }

    /**
     * The pointer buttons of one cell, in the words of
     * `current_key_label`. The game refuses every button after a death
     * or a win.
     */
    private fun actionsAt(s: State, x: Int, y: Int): List<SpokenAction> {
        if (s.dead || s.won) return emptyList()
        return when (val v = s.grid[y * s.width + x]) {
            UNKNOWN -> listOf(
                SpokenAction("Uncover", Buttons.LEFT_BUTTON),
                SpokenAction("Mark", Buttons.RIGHT_BUTTON),
            )

            QUESTION -> listOf(SpokenAction("Uncover", Buttons.LEFT_BUTTON))
            MARKED -> listOf(SpokenAction("Unmark", Buttons.RIGHT_BUTTON))
            in 1..8 -> {
                if (surroundingMarks(s, x, y) == v) {
                    listOf(SpokenAction("Clear", Buttons.LEFT_BUTTON))
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    /** The marked neighbours of a revealed cell, the chord test of `uncover`. */
    private fun surroundingMarks(s: State, x: Int, y: Int): Int {
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until s.width && ny in 0 until s.height &&
                    s.grid[ny * s.width + nx] == MARKED
                ) {
                    n++
                }
            }
        }
        return n
    }

    /** `game_changed_state`: the sticky completion flag and the event. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val next = newState as State
        if (next.won) u.completed = true
        return next.won && !next.usedSolve && oldState != null &&
            !(oldState as State).won
    }

    /** `game_timing_state`. */
    override fun timingState(state: Game.State, ui: Game.Ui): Boolean {
        val s = state as State
        val u = ui as Ui
        return !(s.dead || s.won || u.completed || s.layout.mines == null)
    }

    /** `game_status`: -1 only for a solve of a won board. */
    override fun status(state: Game.State): Int {
        val s = state as State
        return if (s.won) {
            if (s.usedSolve) -1 else 1
        } else {
            0
        }
    }

    /**
     * The status line of `game_redraw`, without the midend's timer
     * prefix. The mine total comes from the bitmap when it exists and
     * from the pending count otherwise.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String =
        statusLine(state as State, ui as Ui)

    private fun statusLine(s: State, u: Ui): String {
        var closed = 0
        var markers = 0
        var mines = 0
        for (i in s.grid.indices) {
            val v = s.grid[i]
            if (v < 0) closed++
            if (v == MARKED) markers++
            if (s.layout.mines?.get(i) == true) mines++
        }
        if (s.layout.mines == null) mines = s.layout.n
        val out = StringBuilder()
        if (s.dead) {
            out.append("DEAD!")
        } else if (s.won) {
            out.append(if (s.usedSolve) "Auto-solved." else "COMPLETED!")
        } else {
            out.append("Marked: ").append(markers).append(" / ").append(mines)
            val safeClosed = closed - mines
            if (safeClosed in 1..9) {
                if (safeClosed == 1) {
                    out.append(" (1 safe square remains)")
                } else {
                    out.append(" (").append(safeClosed).append(" safe squares remain)")
                }
            }
        }
        if (u.deaths != 0) {
            out.append("  Deaths: ").append(u.deaths)
        }
        return out.toString()
    }

    /**
     * `game_text_format`: one row per line, `w` characters wide, with a
     * newline after every row.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val out = StringBuilder((s.width + 1) * s.height)
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val v = s.grid[y * s.width + x]
                out.append(
                    when {
                        v == 0 -> '-'
                        v in 1..8 -> '0' + v
                        v == MARKED -> '*'
                        v == UNKNOWN || v == QUESTION -> '?'
                        v >= 64 -> '!'
                        else -> '?'
                    },
                )
            }
            out.append('\n')
        }
        return out.toString()
    }

    /** `game_request_keys` is NULL: no labels and the default arrow mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /**
     * The Android full-cursor flag presets the arrow mode to
     * `ANDROID_ARROWS_ONLY` before a game with no `request_keys` sees
     * it, so the mode changes while the label list stays empty.
     */
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `new_ui`: the cursor starts hidden on Android. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `encode_ui`: `D<deaths>` and the sticky `C` flag. */
    override fun encodeUi(ui: Game.Ui): String? {
        val u = ui as Ui
        return "D${u.deaths}" + if (u.completed) "C" else ""
    }

    /**
     * `decode_ui`: `sscanf("D%d%n")` followed by a `C` test. A missing
     * or malformed count leaves the fresh value and tests character
     * zero, so `C` alone sets the completion flag. An overflowing count
     * keeps the low 32 bits, as glibc assigns them.
     */
    override fun decodeUi(state: Game.State, text: String): Game.Ui {
        val ui = Ui()
        parseUi(ui, text)
        return ui
    }

    /** C overlays the record on the UI that `new_ui` created. */
    override fun decodeUi(state: Game.State, text: String, base: Game.Ui): Game.Ui {
        val ui = base as Ui
        parseUi(ui, text)
        return ui
    }

    private fun parseUi(ui: Ui, text: String) {
        var p = 0
        if (text.startsWith("D")) {
            var i = 1
            while (i < text.length && isCSpace(text[i])) i++
            var negative = false
            if (i < text.length && (text[i] == '+' || text[i] == '-')) {
                negative = text[i] == '-'
                i++
            }
            if (i < text.length && text[i] in '0'..'9') {
                // glibc reads the number as a `long` and assigns the
                // low 32 bits to the `int`, so `D2147483648` is
                // `-2147483648` and `D-2147483649` is `2147483647`.
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
                val parsed = when {
                    overflow -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE
                    negative -> -value
                    else -> value
                }
                ui.deaths = parsed.toInt()
                p = i
            }
        }
        if (p < text.length && text[p] == 'C') ui.completed = true
    }

    /** `game_compute_size`: two constant borders around the tiles. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(BORDER * 2 + tileSize * p.w, BORDER * 2 + tileSize * p.h)
    }

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`: only the background comes from the frontend. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) {
            ret[COL_BACKGROUND2 * 3 + i] =
                ret[COL_BACKGROUND * 3 + i] * 19.0F / 20.0F
        }
        setColour(ret, COL_1, 0.0F, 0.0F, 1.0F)
        setColour(ret, COL_2, 0.0F, 0.5F, 0.0F)
        setColour(ret, COL_3, 1.0F, 0.0F, 0.0F)
        setColour(ret, COL_4, 0.0F, 0.0F, 0.5F)
        setColour(ret, COL_5, 0.5F, 0.0F, 0.0F)
        setColour(ret, COL_6, 0.0F, 0.5F, 0.5F)
        setColour(ret, COL_7, 0.0F, 0.0F, 0.0F)
        setColour(ret, COL_8, 0.5F, 0.5F, 0.5F)
        setColour(ret, COL_MINE, 0.0F, 0.0F, 0.0F)
        setColour(ret, COL_BANG, 1.0F, 0.0F, 0.0F)
        setColour(ret, COL_CROSS, 1.0F, 0.0F, 0.0F)
        setColour(ret, COL_FLAG, 1.0F, 0.0F, 0.0F)
        setColour(ret, COL_FLAGBASE, 0.0F, 0.0F, 0.0F)
        setColour(ret, COL_QUERY, 0.0F, 0.0F, 0.0F)
        setColour(ret, COL_HIGHLIGHT, 1.0F, 1.0F, 1.0F)
        for (i in 0 until 3) {
            ret[COL_LOWLIGHT * 3 + i] =
                ret[COL_BACKGROUND * 3 + i] * 2.0F / 3.0F
        }
        setColour(ret, COL_WRONGNUMBER, 1.0F, 0.6F, 0.6F)
        // C uses the red channel of COL_HIGHLIGHT for all three parts.
        ret[COL_CURSOR * 3] = ret[COL_HIGHLIGHT * 3]
        ret[COL_CURSOR * 3 + 1] = ret[COL_HIGHLIGHT * 3] / 2.0F
        ret[COL_CURSOR * 3 + 2] = ret[COL_HIGHLIGHT * 3] / 2.0F
        return ret
    }

    private fun setColour(ret: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
    }

    /** `game_new_drawstate`: a -99 cell cache and no background yet. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height)
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

    /** `game_get_cursor_location`: a tile rectangle for a visible cursor. */
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
     * `game_redraw` without the flash. The first frame draws the two
     * recessed-area polygons; every frame draws only the tiles whose
     * transformed value, the background, or the cursor changed. Mines
     * always uses the plain background because the seam has no flash
     * time.
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
        val t = tileSize
        val bg = COL_BACKGROUND

        if (!ds.started) {
            val coords = IntArray(10)
            coords[0] = coord(s.width, t) + OUTER_HIGHLIGHT_WIDTH - 1
            coords[1] = coord(s.height, t) + OUTER_HIGHLIGHT_WIDTH - 1
            coords[2] = coord(s.width, t) + OUTER_HIGHLIGHT_WIDTH - 1
            coords[3] = coord(0, t) - OUTER_HIGHLIGHT_WIDTH
            coords[4] = coords[2] - t
            coords[5] = coords[3] + t
            coords[8] = coord(0, t) - OUTER_HIGHLIGHT_WIDTH
            coords[9] = coord(s.height, t) + OUTER_HIGHLIGHT_WIDTH - 1
            coords[6] = coords[8] + t
            coords[7] = coords[9] - t
            sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)

            coords[1] = coord(0, t) - OUTER_HIGHLIGHT_WIDTH
            coords[0] = coord(0, t) - OUTER_HIGHLIGHT_WIDTH
            sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)
            ds.started = true
        }

        var cx = -1
        var cy = -1
        if (u.curVisible) {
            cx = u.curX
            cy = u.curY
        }
        val cmoved = cx != ds.curX || cy != ds.curY

        for (y in 0 until ds.height) {
            for (x in 0 until ds.width) {
                var v = s.grid[y * ds.width + x]
                var cc = false
                if (v in 0..8) {
                    var flags = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx >= 0 && nx < ds.width && ny >= 0 && ny < ds.height &&
                                s.grid[ny * ds.width + nx] == MARKED
                            ) {
                                flags++
                            }
                        }
                    }
                    if (flags > v) v = v or 32
                }
                if (v == UNKNOWN && x == s.layout.startX && y == s.layout.startY) {
                    v = START_HERE
                }
                if ((v == UNKNOWN || v == QUESTION || v == START_HERE) &&
                    abs(x - u.hx) <= u.hradius && abs(y - u.hy) <= u.hradius
                ) {
                    v -= 20
                }
                if (cmoved &&
                    ((x == cx && y == cy) || (x == ds.curX && y == ds.curY))
                ) {
                    cc = true
                }
                if (ds.grid[y * ds.width + x] != v || bg != ds.bg || cc) {
                    drawTile(
                        sink,
                        coord(x, t),
                        coord(y, t),
                        v,
                        if (x == cx && y == cy) COL_CURSOR else bg,
                        t,
                    )
                    ds.grid[y * ds.width + x] = v
                }
            }
        }
        ds.bg = bg
        ds.curX = cx
        ds.curY = cy

        // The midend emits `statusText()`, which is this line with the
        // `[m:ss] ` prefix. C's `status_bar` adds that prefix between
        // the game redraw and the frontend, so the game must not emit
        // the unprefixed text itself.
    }

    /** `draw_tile`, with the C operation order. */
    private fun drawTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        value: Int,
        background: Int,
        t: Int,
    ) {
        var v = value
        var bg = background
        sink.clip(x, y, t, t)
        if (v < 0) {
            if (v == -22 || v == -23 || v == -24) {
                v += 20
                sink.rect(x, y, t, t, if (bg == COL_BACKGROUND) COL_BACKGROUND2 else bg)
                sink.line(x, y, x + t - 1, y, COL_LOWLIGHT)
                sink.line(x, y, x, y + t - 1, COL_LOWLIGHT)
            } else {
                sink.poly(
                    intArrayOf(
                        x + t - 1, y + t - 1,
                        x + t - 1, y,
                        x, y + t - 1,
                    ),
                    COL_LOWLIGHT,
                    COL_LOWLIGHT,
                )
                sink.poly(
                    intArrayOf(
                        x, y,
                        x + t - 1, y,
                        x, y + t - 1,
                    ),
                    COL_HIGHLIGHT,
                    COL_HIGHLIGHT,
                )
                val hw = if (t / 10 != 0) t / 10 else 1
                sink.rect(x + hw, y + hw, t - 2 * hw, t - 2 * hw, bg)
            }

            if (v == MARKED) {
                sink.poly(
                    intArrayOf(
                        x + (t * 0.6F).toInt(), y + (t * 0.35F).toInt(),
                        x + (t * 0.6F).toInt(), y + (t * 0.7F).toInt(),
                        x + (t * 0.8F).toInt(), y + (t * 0.8F).toInt(),
                        x + (t * 0.25F).toInt(), y + (t * 0.8F).toInt(),
                        x + (t * 0.55F).toInt(), y + (t * 0.7F).toInt(),
                        x + (t * 0.55F).toInt(), y + (t * 0.35F).toInt(),
                    ),
                    COL_FLAGBASE,
                    COL_FLAGBASE,
                )
                sink.poly(
                    intArrayOf(
                        x + (t * 0.6F).toInt(), y + (t * 0.2F).toInt(),
                        x + (t * 0.6F).toInt(), y + (t * 0.5F).toInt(),
                        x + (t * 0.2F).toInt(), y + (t * 0.35F).toInt(),
                    ),
                    COL_FLAG,
                    COL_FLAG,
                )
            } else if (v == QUESTION) {
                sink.text(
                    x + t / 2,
                    y + t / 2,
                    Text.FONT_VARIABLE,
                    t * 6 / 8,
                    Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                    COL_QUERY,
                    "?",
                )
            } else if (v == START_HERE) {
                val c0 = t / 4
                val c1 = t - 1 - c0
                sink.line(x + c0, y + c0, x + c1, y + c1, COL_MINE)
                sink.line(x + c0, y + c1, x + c1, y + c0, COL_MINE)
            }
        } else {
            if (v and 32 != 0) {
                bg = COL_WRONGNUMBER
                v = v and 32.inv()
            }
            val clear = if (v == TRODDEN_MINE) {
                COL_BANG
            } else if (bg == COL_BACKGROUND) {
                COL_BACKGROUND2
            } else {
                bg
            }
            sink.rect(x, y, t, t, clear)
            sink.line(x, y, x + t - 1, y, COL_LOWLIGHT)
            sink.line(x, y, x, y + t - 1, COL_LOWLIGHT)

            if (v in 1..8) {
                sink.text(
                    x + t / 2,
                    y + t / 2,
                    Text.FONT_VARIABLE,
                    t * 7 / 8,
                    Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                    (COL_1 - 1) + v,
                    v.toString(),
                )
            } else if (v >= 64) {
                val mx = x + t / 2
                val my = y + t / 2
                val r = t / 2 - 3
                sink.circle(mx, my, 5 * r / 6, COL_MINE, COL_MINE)
                sink.rect(mx - r / 6, my - r, 2 * (r / 6) + 1, 2 * r + 1, COL_MINE)
                sink.rect(mx - r, my - r / 6, 2 * r + 1, 2 * (r / 6) + 1, COL_MINE)
                sink.rect(mx - r / 3, my - r / 3, r / 3, r / 4, COL_HIGHLIGHT)
                if (v == WRONG_MARK) {
                    for (dx in -1..1) {
                        sink.line(x + 3 + dx, y + 2, x + t - 3 + dx, y + t - 2, COL_CROSS)
                        sink.line(x + t - 3 + dx, y + 2, x + 3 + dx, y + t - 2, COL_CROSS)
                    }
                }
            }
        }
        sink.unclip()
        sink.update(x, y, t, t)
    }

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    private class MineContext(
        val grid: BooleanArray,
        val w: Int,
        val h: Int,
        val sx: Int,
        val sy: Int,
        val rs: RandomState,
    ) {
        val opened: BooleanArray = BooleanArray(w * h)
        var allowBigPerturbs: Boolean = false
        var nperturbsSinceLastNewOpen: Int = 0
    }

    private class Square(val x: Int, val y: Int, val type: Int, val random: Int)

    private class Perturbation(val x: Int, val y: Int, val delta: Int)

    private class MineLayout(val mines: BooleanArray, val desc: String)

    /** One generator run with its attempt count, for tests. */
    data class LayoutGeneration(val mines: BooleanArray, val desc: String, val attempts: Int)

    /**
     * The batch generator entry point for a fixed first click, with the
     * attempt count. [random] must already have consumed the two
     * prefix draws of `new_game_desc`; the interactive path reaches it
     * through the first uncover.
     */
    internal fun generateLayout(
        params: Params,
        x: Int,
        y: Int,
        random: RandomState,
    ): LayoutGeneration {
        val run = minegen(params.w, params.h, params.n, x, y, params.unique, random)
        return LayoutGeneration(
            run.mines,
            describeLayout(run.mines, params.w * params.h, x, y, true),
            run.attempts,
        )
    }

    /**
     * The full interactive sequence for a seed and a chosen first click:
     * the two prefix draws, then the gated generator. This is the shape
     * that `new_game_desc` plus the first uncover has.
     */
    fun generateLayoutForSeed(
        params: Params,
        seed: String,
        x: Int,
        y: Int,
    ): LayoutGeneration {
        val random = RandomState.fromSeed(seed)
        drawStart(params, random)
        return generateLayout(params, x, y, random)
    }

    private class Minegen(val mines: BooleanArray, val attempts: Int)

    /**
     * `minegen`: place `n` mines away from the start cell, then run the
     * uniqueness gate. Every retry redraws from the same stream, so a
     * wrong condition changes later seeds, not only this one.
     */
    private fun minegen(
        w: Int,
        h: Int,
        n: Int,
        x: Int,
        y: Int,
        unique: Boolean,
        rs: RandomState,
    ): Minegen {
        val wh = w * h
        val ret = BooleanArray(wh)
        val tmp = IntArray(wh)
        var ntries = 0
        while (true) {
            ntries++
            ret.fill(false)
            var k = 0
            for (i in 0 until h) {
                for (j in 0 until w) {
                    if (abs(i - y) > 1 || abs(j - x) > 1) tmp[k++] = i * w + j
                }
            }
            // `validate_desc` keeps n <= w*h - 9, which never exceeds
            // the candidate count. A direct caller with a larger count
            // would make `random_upto(0)` undefined in C; the port
            // rejects it before the draw.
            require(n <= k) {
                "mines: $n mines do not fit the ${k} candidate squares"
            }
            var nn = n
            while (nn-- > 0) {
                val i = rs.upto(k.toLong()).toInt()
                ret[tmp[i]] = true
                tmp[i] = tmp[--k]
            }

            var success: Boolean
            if (unique) {
                val ctx = MineContext(ret, w, h, x, y, rs)
                ctx.allowBigPerturbs = ntries > 100
                // `prevret` is never assigned in C. It stays dead here on
                // purpose. Assigning it makes every attempt that needs a
                // second solver pass retry, so generation does not finish
                // in the tested space.
                var prevret = -2
                while (true) {
                    val solvegrid = IntArray(wh) { UNKNOWN }
                    solvegrid[y * w + x] = mineopen(ctx, x, y)
                    check(solvegrid[y * w + x] == 0)
                    val solveret = minesolve(w, h, n, solvegrid, ctx, rs)
                    if (solveret < 0 || (prevret >= 0 && solveret >= prevret)) {
                        success = false
                        break
                    } else if (solveret == 0) {
                        success = true
                        break
                    }
                }
            } else {
                success = true
            }
            if (success) break
        }
        return Minegen(ret, ntries)
    }

    /** `mineopen`: -1 for a mine, otherwise the neighbour count. */
    private fun mineopen(ctx: MineContext, x: Int, y: Int): Int {
        check(x in 0 until ctx.w && y in 0 until ctx.h)
        val pos = y * ctx.w + x
        if (ctx.grid[pos]) return -1
        if (!ctx.opened[pos]) {
            ctx.opened[pos] = true
            ctx.nperturbsSinceLastNewOpen = 0
        }
        var n = 0
        for (i in -1..1) {
            if (x + i < 0 || x + i >= ctx.w) continue
            for (j in -1..1) {
                if (y + j < 0 || y + j >= ctx.h) continue
                if (i == 0 && j == 0) continue
                if (ctx.grid[(y + j) * ctx.w + (x + i)]) n++
            }
        }
        return n
    }

    /**
     * `minesolve`: the deduction solver that gates generation.
     *
     * Returns -1 when a square stays unknown, 0 on success, and the
     * number of perturbations otherwise. The set store and the square
     * to-do list follow the C structures, including the tree order
     * `(y, x, mask)` and the FIFO rule.
     */
    private fun minesolve(
        w: Int,
        h: Int,
        n: Int,
        grid: IntArray,
        ctx: MineContext,
        rs: RandomState,
    ): Int {
        val ss = SetStore()
        val std = SquareTodo(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (grid[i] != UNKNOWN) std.add(i)
            }
        }

        var nperturbs = 0
        while (true) {
            var doneSomething = false

            while (std.head != -1) {
                val i = std.head
                std.head = std.next[i]
                if (std.head == -1) std.tail = -1
                val x = i % w
                val y = i / w

                if (grid[i] >= 0) {
                    var mines = grid[i]
                    var bit = 1
                    var value = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (x + dx < 0 || x + dx >= w || y + dy < 0 || y + dy >= h) {
                                // This neighbour is outside the board.
                            } else if (grid[i + dy * w + dx] == MARKED) {
                                mines--
                            } else if (grid[i + dy * w + dx] == UNKNOWN) {
                                value = value or bit
                            }
                            bit = bit shl 1
                        }
                    }
                    if (value != 0) ss.add(x - 1, y - 1, value, mines)
                }

                val list = ss.overlap(x, y, 1)
                for (set in list) {
                    val newmask = setmunge(set.x, set.y, set.mask, x, y, 1, true)
                    val newmines = set.mines - (if (grid[i] == MARKED) 1 else 0)
                    if (newmask != 0) ss.add(set.x, set.y, newmask, newmines)
                    ss.remove(set)
                }
                doneSomething = true
            }

            val set = ss.todo()
            if (set != null) {
                if (set.mines == 0 || set.mines == bitcount16(set.mask)) {
                    knownSquares(
                        w, h, std, grid, ctx,
                        set.x, set.y, set.mask, set.mines != 0,
                    )
                    continue
                }

                val list = ss.overlap(set.x, set.y, set.mask)
                for (other in list) {
                    val swing = setmunge(
                        set.x, set.y, set.mask,
                        other.x, other.y, other.mask, true,
                    )
                    val otherWing = setmunge(
                        other.x, other.y, other.mask,
                        set.x, set.y, set.mask, true,
                    )
                    val swingCount = bitcount16(swing)
                    val otherCount = bitcount16(otherWing)

                    if (swingCount == set.mines - other.mines ||
                        otherCount == other.mines - set.mines
                    ) {
                        knownSquares(
                            w, h, std, grid, ctx,
                            set.x, set.y, swing,
                            swingCount == set.mines - other.mines,
                        )
                        knownSquares(
                            w, h, std, grid, ctx,
                            other.x, other.y, otherWing,
                            otherCount == other.mines - set.mines,
                        )
                        continue
                    }

                    if (swingCount == 0 && otherCount != 0) {
                        ss.add(other.x, other.y, otherWing, other.mines - set.mines)
                    } else if (otherCount == 0 && swingCount != 0) {
                        ss.add(set.x, set.y, swing, set.mines - other.mines)
                    }
                }
                doneSomething = true
            } else if (n >= 0) {
                var squaresLeft = 0
                var minesLeft = n
                for (i in 0 until w * h) {
                    if (grid[i] == MARKED) minesLeft--
                    else if (grid[i] == UNKNOWN) squaresLeft++
                }

                if (squaresLeft == 0) break
                if (minesLeft == 0 || minesLeft == squaresLeft) {
                    for (i in 0 until w * h) {
                        if (grid[i] == UNKNOWN) {
                            knownSquares(
                                w, h, std, grid, ctx,
                                i % w, i / w, 1, minesLeft != 0,
                            )
                        }
                    }
                    continue
                }

                val nsets = ss.count
                if (nsets <= 10) {
                    val sets = arrayOfNulls<MineSet>(10)
                    for (i in 0 until nsets) sets[i] = ss.index(i)
                    val setused = BooleanArray(10)
                    var cursor = 0
                    var unionMines = minesLeft
                    var unionSquares = squaresLeft
                    while (true) {
                        if (cursor < nsets) {
                            var ok = true
                            for (i in 0 until cursor) {
                                val a = sets[cursor]!!
                                val b = sets[i]!!
                                if (setused[i] && setmunge(
                                        a.x, a.y, a.mask,
                                        b.x, b.y, b.mask, false,
                                    ) != 0
                                ) {
                                    ok = false
                                    break
                                }
                            }
                            if (ok) {
                                unionMines -= sets[cursor]!!.mines
                                unionSquares -= bitcount16(sets[cursor]!!.mask)
                            }
                            setused[cursor++] = ok
                        } else {
                            if (unionSquares > 0 &&
                                (unionMines == 0 || unionMines == unionSquares)
                            ) {
                                for (i in 0 until w * h) {
                                    if (grid[i] != UNKNOWN) continue
                                    var outside = true
                                    val x = i % w
                                    val y = i / w
                                    for (j in 0 until nsets) {
                                        val s = sets[j]!!
                                        if (setused[j] && setmunge(
                                                s.x, s.y, s.mask,
                                                x, y, 1, false,
                                            ) != 0
                                        ) {
                                            outside = false
                                            break
                                        }
                                    }
                                    if (outside) {
                                        knownSquares(
                                            w, h, std, grid, ctx,
                                            x, y, 1, unionMines != 0,
                                        )
                                    }
                                }
                                doneSomething = true
                                break
                            }

                            cursor--
                            while (cursor >= 0 && !setused[cursor]) cursor--
                            if (cursor >= 0) {
                                unionMines += sets[cursor]!!.mines
                                unionSquares += bitcount16(sets[cursor]!!.mask)
                                setused[cursor++] = false
                            } else {
                                break
                            }
                        }
                    }
                }
            }

            if (doneSomething) continue

            nperturbs++
            val changes = if (ss.count == 0) {
                mineperturb(ctx, grid, 0, 0, 0)
            } else {
                val set = ss.index(rs.upto(ss.count.toLong()).toInt())
                mineperturb(ctx, grid, set.x, set.y, set.mask)
            }
            if (changes != null) {
                check(changes.isNotEmpty())
                for (change in changes) {
                    val pos = change.y * w + change.x
                    if (change.delta < 0 && grid[pos] != UNKNOWN) std.add(pos)
                    val list = ss.overlap(change.x, change.y, 1)
                    for (set in list) {
                        set.mines += change.delta
                        ss.addTodo(set)
                    }
                }
                continue
            }
            break
        }

        for (i in 0 until w * h) {
            if (grid[i] == UNKNOWN) return -1
        }
        return nperturbs
    }

    /**
     * `known_squares`: mark or open every unknown cell of a mask. The
     * C `open` callback is [mineopen]. A mask bit outside the grid is
     * impossible in a reachable set; the guard skips it instead of
     * reading a neighbouring row, as the C assertion intends.
     */
    private fun knownSquares(
        w: Int,
        h: Int,
        std: SquareTodo,
        grid: IntArray,
        ctx: MineContext,
        x: Int,
        y: Int,
        mask: Int,
        mine: Boolean,
    ) {
        var bit = 1
        for (yy in 0..2) {
            for (xx in 0..2) {
                if (mask and bit != 0) {
                    val nx = x + xx
                    val ny = y + yy
                    if (nx in 0 until w && ny in 0 until h) {
                        val i = ny * w + nx
                        if (grid[i] == UNKNOWN) {
                            if (mine) {
                                grid[i] = MARKED
                            } else {
                                grid[i] = mineopen(ctx, nx, ny)
                                check(grid[i] != -1)
                            }
                            std.add(i)
                        }
                    }
                }
                bit = bit shl 1
            }
        }
    }

    /** `setmunge`: intersect or difference two masks in one frame. */
    private fun setmunge(
        x1: Int,
        y1: Int,
        mask1: Int,
        x2: Int,
        y2: Int,
        mask2: Int,
        diff: Boolean,
    ): Int {
        var m2 = mask2
        var xx = x2
        var yy = y2
        if (abs(xx - x1) >= 3 || abs(yy - y1) >= 3) {
            m2 = 0
        } else {
            while (xx > x1) {
                m2 = m2 and (4 or 32 or 256).inv()
                m2 = m2 shl 1
                xx--
            }
            while (xx < x1) {
                m2 = m2 and (1 or 8 or 64).inv()
                m2 = m2 shr 1
                xx++
            }
            while (yy > y1) {
                m2 = m2 and (64 or 128 or 256).inv()
                m2 = m2 shl 3
                yy--
            }
            while (yy < y1) {
                m2 = m2 and (1 or 2 or 4).inv()
                m2 = m2 shr 3
                yy++
            }
        }
        if (diff) m2 = m2 xor 511
        return mask1 and m2
    }

    /** `bitcount16`, including the C shift-and-add form. */
    private fun bitcount16(value: Int): Int {
        var word = value and 0xFFFF
        word = ((word and 0xAAAA) ushr 1) + (word and 0x5555)
        word = ((word and 0xCCCC) ushr 2) + (word and 0x3333)
        word = ((word and 0xF0F0) ushr 4) + (word and 0x0F0F)
        word = ((word and 0xFF00) ushr 8) + (word and 0x00FF)
        return word
    }

    /** `struct set` plus the two linked lists of `setstore`. */
    private class MineSet(
        var x: Int,
        var y: Int,
        val mask: Int,
        var mines: Int,
    ) {
        var todo: Boolean = false
        var prev: MineSet? = null
        var next: MineSet? = null
    }

    private val SET_ORDER = Comparator<MineSet> { a, b ->
        when {
            a.y < b.y -> -1
            a.y > b.y -> 1
            a.x < b.x -> -1
            a.x > b.x -> 1
            a.mask < b.mask -> -1
            a.mask > b.mask -> 1
            else -> 0
        }
    }

    private class SetStore {
        private val sets: Tree234<MineSet> = Tree234.sorted(SET_ORDER)
        private var todoHead: MineSet? = null
        private var todoTail: MineSet? = null

        val count: Int get() = sets.count

        fun index(i: Int): MineSet = checkNotNull(sets.index(i))

        fun add(x: Int, y: Int, mask: Int, mines: Int) {
            if (mask == 0) return
            var m = mask
            var nx = x
            var ny = y
            while (m and (1 or 8 or 64) == 0) {
                m = m shr 1
                nx++
            }
            while (m and (1 or 2 or 4) == 0) {
                m = m shr 3
                ny++
            }
            val set = MineSet(nx, ny, m, mines)
            if (sets.add(set) !== set) return
            addTodo(set)
        }

        fun addTodo(set: MineSet) {
            if (set.todo) return
            set.prev = todoTail
            val tail = todoTail
            if (tail != null) tail.next = set else todoHead = set
            todoTail = set
            set.next = null
            set.todo = true
        }

        fun todo(): MineSet? {
            val ret = todoHead ?: return null
            todoHead = ret.next
            if (todoHead != null) {
                todoHead!!.prev = null
            } else {
                todoTail = null
            }
            ret.next = null
            ret.prev = null
            ret.todo = false
            return ret
        }

        fun remove(set: MineSet) {
            val next = set.next
            val prev = set.prev
            if (prev != null) {
                prev.next = next
            } else if (set === todoHead) {
                todoHead = next
            }
            if (next != null) {
                next.prev = prev
            } else if (set === todoTail) {
                todoTail = prev
            }
            set.todo = false
            sets.delete(set)
        }

        /**
         * `ss_overlap`. C scans `(x-3..x+2) x (y-3..y+2)` and, inside
         * each pair, the tree order by mask. The buckets keep that
         * order: the tree is sorted by `(y, x, mask)`, so each cell's
         * list is already in ascending mask order.
         */
        fun overlap(x: Int, y: Int, mask: Int): List<MineSet> {
            val buckets = HashMap<Long, ArrayList<MineSet>>()
            for (set in sets.elements()) {
                val key = (set.y.toLong() shl 32) or (set.x.toLong() and 0xFFFFFFFFL)
                buckets.getOrPut(key) { ArrayList() }.add(set)
            }
            val ret = ArrayList<MineSet>()
            for (xx in x - 3 until x + 3) {
                for (yy in y - 3 until y + 3) {
                    val key = (yy.toLong() shl 32) or (xx.toLong() and 0xFFFFFFFFL)
                    val list = buckets[key] ?: continue
                    for (set in list) {
                        if (setmunge(x, y, mask, set.x, set.y, set.mask, false) != 0) {
                            ret.add(set)
                        }
                    }
                }
            }
            return ret
        }
    }

    /** `struct squaretodo`, the FIFO of known squares. */
    private class SquareTodo(size: Int) {
        val next = IntArray(size)
        var head = -1
        var tail = -1

        fun add(i: Int) {
            if (tail >= 0) next[tail] = i else head = i
            tail = i
            next[i] = -1
        }
    }

    private val SQUARE_ORDER = Comparator<Square> { a, b ->
        when {
            a.type < b.type -> -1
            a.type > b.type -> 1
            a.random < b.random -> -1
            a.random > b.random -> 1
            a.y < b.y -> -1
            a.y > b.y -> 1
            a.x < b.x -> -1
            a.x > b.x -> 1
            else -> 0
        }
    }

    /**
     * `mineperturb`: pick a balanced replacement for a stalled set. The
     * draw order is the contract: one `random_bits(31)` per candidate,
     * then the partial-fill swaps.
     *
     * The three C assertions are defined here instead of aborting:
     * no empty outside cell or too few inside cells gives up (null),
     * and an out-of-grid mask bit is skipped.
     */
    private fun mineperturb(
        ctx: MineContext,
        grid: IntArray,
        setx: Int,
        sety: Int,
        mask: Int,
    ): List<Perturbation>? {
        val w = ctx.w
        val h = ctx.h
        if (mask == 0 && !ctx.allowBigPerturbs) return null

        // The double post-increment is literal: the failing call still
        // advances the counter, and the second test is skipped when the
        // first one fails.
        val first = ctx.nperturbsSinceLastNewOpen
        ctx.nperturbsSinceLastNewOpen = first + 1
        if (first > w) return null
        val second = ctx.nperturbsSinceLastNewOpen
        ctx.nperturbsSinceLastNewOpen = second + 1
        if (second > h) return null

        val sqlist = ArrayList<Square>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (abs(y - ctx.sy) <= 1 && abs(x - ctx.sx) <= 1) continue
                if (mask == 0 && grid[y * w + x] == UNKNOWN) continue
                if (x >= setx && x < setx + 3 && y >= sety && y < sety + 3 &&
                    mask and (1 shl ((y - sety) * 3 + (x - setx))) != 0
                ) {
                    continue
                }
                val type = if (grid[y * w + x] != UNKNOWN) {
                    3
                } else if (hasKnownNeighbour(w, h, grid, x, y)) {
                    1
                } else {
                    2
                }
                sqlist.add(Square(x, y, type, ctx.rs.bits(31).toInt()))
            }
        }
        sqlist.sortWith(SQUARE_ORDER)

        var nfull = 0
        var nempty = 0
        if (mask != 0) {
            for (dy in 0..2) {
                for (dx in 0..2) {
                    if (mask and (1 shl (dy * 3 + dx)) == 0) continue
                    val x = setx + dx
                    val y = sety + dy
                    if (x !in 0 until w || y !in 0 until h) continue
                    if (ctx.grid[y * w + x]) nfull++ else nempty++
                }
            }
        } else {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (grid[y * w + x] == UNKNOWN) {
                        if (ctx.grid[y * w + x]) nfull++ else nempty++
                    }
                }
            }
        }

        var ntofill = 0
        var ntoempty = 0
        val tofill = ArrayList<Square>()
        val toempty = ArrayList<Square>()
        for (square in sqlist) {
            if (ctx.grid[square.y * w + square.x]) {
                toempty.add(square)
            } else {
                tofill.add(square)
            }
            ntofill = tofill.size
            ntoempty = toempty.size
            if (ntofill == nfull || ntoempty == nempty) break
        }

        var setlist: IntArray? = null
        if (ntofill != nfull && ntoempty != nempty) {
            if (ntoempty == 0) return null
            val list = IntArray(w * h)
            var i = 0
            if (mask != 0) {
                for (dy in 0..2) {
                    for (dx in 0..2) {
                        if (mask and (1 shl (dy * 3 + dx)) == 0) continue
                        val x = setx + dx
                        val y = sety + dy
                        if (x !in 0 until w || y !in 0 until h) continue
                        if (!ctx.grid[y * w + x]) list[i++] = y * w + x
                    }
                }
            } else {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        if (grid[y * w + x] == UNKNOWN && !ctx.grid[y * w + x]) {
                            list[i++] = y * w + x
                        }
                    }
                }
            }
            if (i < ntoempty) return null
            for (k in 0 until ntoempty) {
                val index = k + ctx.rs.upto((i - k).toLong()).toInt()
                val tmp = list[k]
                list[k] = list[index]
                list[index] = tmp
            }
            setlist = list.copyOf(i)
        }

        val todo: List<Square>
        val ntodo: Int
        val dtodo: Int
        val dset: Int
        if (ntofill == nfull) {
            todo = tofill
            ntodo = ntofill
            dtodo = 1
            dset = -1
        } else {
            todo = toempty
            ntodo = ntoempty
            dtodo = -1
            dset = 1
        }

        val changes = ArrayList<Perturbation>(2 * ntodo)
        for (i in 0 until ntodo) {
            changes.add(Perturbation(todo[i].x, todo[i].y, dtodo))
        }
        val shuffled = setlist
        if (shuffled != null) {
            check(todo === toempty)
            for (j in 0 until ntoempty) {
                changes.add(Perturbation(shuffled[j] % w, shuffled[j] / w, dset))
            }
        } else if (mask != 0) {
            for (dy in 0..2) {
                for (dx in 0..2) {
                    if (mask and (1 shl (dy * 3 + dx)) == 0) continue
                    val x = setx + dx
                    val y = sety + dy
                    if (x !in 0 until w || y !in 0 until h) continue
                    val current = if (ctx.grid[y * w + x]) 1 else -1
                    if (dset == -current) changes.add(Perturbation(x, y, dset))
                }
            }
        } else {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (grid[y * w + x] != UNKNOWN) continue
                    val current = if (ctx.grid[y * w + x]) 1 else -1
                    if (dset == -current) changes.add(Perturbation(x, y, dset))
                }
            }
        }
        check(changes.size == 2 * ntodo)

        for (change in changes) {
            val pos = change.y * w + change.x
            ctx.grid[pos] = change.delta > 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val x = change.x + dx
                    val y = change.y + dy
                    if (x < 0 || x >= w || y < 0 || y >= h) continue
                    if (grid[y * w + x] == UNKNOWN) continue
                    if (dx == 0 && dy == 0) {
                        if (change.delta > 0) {
                            grid[pos] = MARKED
                        } else {
                            grid[pos] = neighbourMines(ctx, x, y)
                        }
                    } else if (grid[y * w + x] >= 0) {
                        grid[y * w + x] += change.delta
                    }
                }
            }
        }
        return changes
    }

    /** Whether any neighbour of a cell is outside the unknown area. */
    private fun hasKnownNeighbour(w: Int, h: Int, grid: IntArray, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h &&
                    grid[ny * w + nx] != UNKNOWN
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** The mine count around a cell of the generator grid. */
    private fun neighbourMines(ctx: MineContext, x: Int, y: Int): Int {
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until ctx.w && ny in 0 until ctx.h &&
                    ctx.grid[ny * ctx.w + nx]
                ) {
                    count++
                }
            }
        }
        return count
    }

    /** `new_mine_layout`: the bitmap and its masked description. */
    private fun newMineLayout(
        w: Int,
        h: Int,
        n: Int,
        x: Int,
        y: Int,
        unique: Boolean,
        rs: RandomState,
    ): MineLayout {
        val grid = minegen(w, h, n, x, y, unique, rs).mines
        return MineLayout(grid, describeLayout(grid, w * h, x, y, true))
    }

    /**
     * `describe_layout`: MSB-first bitmap, optional bit-length mask, and
     * `(area+3)/4` hex nibbles after `<x>,<y>,<m|u>`.
     */
    internal fun describeLayout(
        grid: BooleanArray,
        area: Int,
        x: Int,
        y: Int,
        obfuscate: Boolean,
    ): String {
        val bytes = ByteArray((area + 7) / 8)
        for (i in 0 until area) {
            if (grid[i]) {
                bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
            }
        }
        val hex = if (obfuscate) {
            AuxInfo.encodeMask(bytes.copyOf(), area)
        } else {
            hexNibbles(bytes, area)
        }
        return "$x,$y,${if (obfuscate) 'm' else 'u'}$hex"
    }

    /** The trailing `m<hex>` of a layout description, as C derives it. */
    private fun privatePart(desc: String): String {
        var index = 0
        while (index < desc.length && desc[index] in '0'..'9') index++
        if (index < desc.length && desc[index] == ',') index++
        while (index < desc.length && desc[index] in '0'..'9') index++
        if (index < desc.length && desc[index] == ',') index++
        check(desc.startsWith("m", index))
        return desc.substring(index)
    }

    /** The `(bits+3)/4` hex digits of a bitmap, high nibble first. */
    private fun hexNibbles(bytes: ByteArray, bits: Int): String {
        val out = StringBuilder((bits + 3) / 4)
        for (i in 0 until (bits + 3) / 4) {
            val value = bytes[i / 2].toInt() and 0xFF
            out.append(HEX[if (i % 2 == 0) value ushr 4 else value and 0xF])
        }
        return out.toString()
    }

    /**
     * The nibble decode of `new_game` plus the optional unmask. A
     * non-hex character is zero, so `zzzz...` is an empty board.
     */
    private fun decodeBitmap(text: String, bits: Int, masked: Boolean): ByteArray {
        val nibbles = (bits + 3) / 4
        require(text.length >= nibbles) {
            "mines: the layout bitmap has ${text.length} characters, need $nibbles"
        }
        if (masked) return AuxInfo.decodeMask(text, bits)
        val bytes = ByteArray((bits + 7) / 8)
        for (i in 0 until nibbles) {
            val value = hexNibble(text[i])
            bytes[i / 2] = (bytes[i / 2].toInt() or
                (value shl (4 * (1 - (i % 2))))).toByte()
        }
        return bytes
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    // ------------------------------------------------------------------
    // Text helpers.
    // ------------------------------------------------------------------

    /** `desc[index]`, or NUL past the end, as the C string terminator. */
    private fun charAt(text: String, index: Int): Char =
        if (index < text.length) text[index] else '\u0000'

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' ||
            c == '\r'

    /** C `atoi`: white space, an optional sign, then digits; 0 if none. */
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

    /** A `%d,%d` scan of the move tail, as `sscanf` reads it. */
    private fun scanPair(text: String, start: Int): Pair<Int, Int>? {
        val first = scanInt(text, start) ?: return null
        var index = first.second
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = scanInt(text, index) ?: return null
        return first.first to second.first
    }

    /**
     * One `%d`: white space, an optional sign, then digits. Returns the
     * value and the first position after the digits. Overflow saturates,
     * which C leaves undefined.
     */
    private fun scanInt(text: String, start: Int): Pair<Int, Int>? {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++
        var sign = 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            if (text[i] == '-') sign = -1
            i++
        }
        if (i >= text.length || text[i] !in '0'..'9') return null
        var value = 0L
        while (i < text.length && text[i] in '0'..'9') {
            value = value * 10 + (text[i] - '0')
            if (value > Int.MAX_VALUE) {
                return (if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE) to i
            }
            i++
        }
        return (sign * value.toInt()) to i
    }

    private const val OUTER_HIGHLIGHT_WIDTH = 1

    private const val HEX = "0123456789abcdef"
}
