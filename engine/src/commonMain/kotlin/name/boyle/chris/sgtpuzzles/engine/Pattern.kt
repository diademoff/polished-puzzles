package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `pattern.c`: the nonogram puzzle called Pattern.
 *
 * The board is a grid of unknown, filled, and empty cells. The clue
 * lists around the top and the left give the run lengths of the filled
 * cells in each column and row.
 *
 * The rules, the seed, the description, and the save format are
 * upstream behavior. The generator draws one random value per cell,
 * averages every 3x3 neighbourhood once in single precision, and
 * thresholds the middle value. It accepts a board only when no row or
 * column is monochrome (for dimensions above two) and when the line
 * solver can finish it, so [LineSolver] is part of the generator and a
 * solver difference changes every description.
 *
 * The code keeps the C structure on purpose:
 * - the RNG order is one `random_upto(100000000)` per cell in row-major
 *   order, then one `random_upto(2)` only when both dimensions are odd,
 *   with whole-generator retries;
 * - [Common] is the shared, read-only clue data of one game, the
 *   counterpart of the refcounted `game_state_common`;
 * - `completed` is sticky and `cheated` is set only by an `S` move;
 * - a cursor select produces a move even on an immutable clue cell, so
 *   it adds a state without a visible change;
 * - a description whose suffix already fills the solution loads at
 *   status 0, and the first accepted move can win it.
 *
 * `solve` returns null when the line solver cannot finish a hand-written
 * board; `solveGame` carries the C message
 * `Solving algorithm cannot complete this puzzle` in
 * `SolveOutcome.error`.
 *
 * Enablement debt: `validate_params` accepts an area up to `INT_MAX`
 * cells. The five presets generate in milliseconds, `40x40` takes about
 * a third of a second, `50x50` about sixteen seconds, and `100x100`
 * does not finish in a minute. The app must generate off the UI thread;
 * a size bound is an owner decision and is not added here.
 */
object Pattern : Game {

    /** `GRID_EMPTY`, `GRID_FULL`, and `GRID_UNKNOWN` of `pattern.c:51-53`. */
    const val GRID_EMPTY = 0
    const val GRID_FULL = 1
    const val GRID_UNKNOWN = 2

    /** The solver marks of `pattern.c:367-370`. */
    private const val UNKNOWN = 0
    private const val BLOCK = 1
    private const val DOT = 2
    private const val STILL_UNKNOWN = 3

    /** `enum { FS_SMALL, FS_LARGE }`. */
    const val FS_SMALL = 0
    const val FS_LARGE = 1

    /** The palette of `pattern.c:19-30`. */
    private const val COL_BACKGROUND = 0
    private const val COL_EMPTY = 1
    private const val COL_FULL = 2
    private const val COL_TEXT = 3
    private const val COL_UNKNOWN = 4
    private const val COL_GRID = 5
    private const val COL_CURSOR = 6
    private const val COL_ERROR = 7
    private const val COL_CURSOR_GUIDE = 8
    const val NCOLOURS = 9

    private const val PREFERRED_TILE_SIZE = 24

    /** `struct game_params`: two positive integers, nothing else. */
    data class Params(val w: Int, val h: Int) : Game.Params {
        override fun copy(): Game.Params = Params(w, h)
    }

    /**
     * `game_state_common`: the clue lists, the immutable marks, and the
     * derived font size. C shares one refcounted instance between every
     * state of a game and never writes it after `new_game`. The port
     * shares this instance and treats it as immutable.
     *
     * `rowdata` holds the clue lists back to back, `rowsize` entries
     * each: the `w` columns first, then the `h` rows. `rowlen[i]` is the
     * clue count of entry `i`. `immutable` marks a cell filled in by the
     * description suffix. `initialGrid` is the grid that `new_game`
     * decoded, before any move: C solves `states[0]`, so the solver
     * reads the immutable cells from it and not from the played state.
     */
    class Common internal constructor(
        val w: Int,
        val h: Int,
        val rowsize: Int,
        val rowdata: IntArray,
        val rowlen: IntArray,
        val immutable: BooleanArray,
        /** `FS_SMALL` or `FS_LARGE`, derived from the clues at load. */
        val fontSize: Int,
        /**
         * The decoded initial grid, including the immutable clue cells
         * of the description suffix.
         */
        val initialGrid: IntArray,
    )

    /**
     * `struct game_state`: [grid] holds `GRID_EMPTY`, `GRID_FULL`, or
     * `GRID_UNKNOWN` per cell. [common] is shared; [completed] and
     * [cheated] are per state. A move copies the grid, and
     * [completed] is never cleared.
     */
    class State internal constructor(
        val common: Common,
        val grid: IntArray,
        val completed: Boolean,
        val cheated: Boolean,
    ) : Game.State {

        val width: Int get() = common.w

        val height: Int get() = common.h

        fun valueAt(x: Int, y: Int): Int = grid[y * width + x]

        override fun copy(): Game.State = State(common, grid.copyOf(), completed, cheated)

        override fun equals(other: Any?): Boolean =
            other is State &&
                grid.contentEquals(other.grid) &&
                completed == other.completed &&
                cheated == other.cheated &&
                common.rowdata.contentEquals(other.common.rowdata) &&
                common.rowlen.contentEquals(other.common.rowlen) &&
                common.immutable.contentEquals(other.common.immutable) &&
                common.fontSize == other.common.fontSize

        override fun hashCode(): Int {
            var result = grid.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + common.rowdata.contentHashCode()
            result = 31 * result + common.rowlen.contentHashCode()
            result = 31 * result + common.immutable.contentHashCode()
            result = 31 * result + common.fontSize
            return result
        }
    }

    /**
     * `struct game_ui`: the drag preview and the keyboard cursor. C
     * leaves the drag fields uninitialised until a press sets them and
     * reads them only while `dragging` is true, so the port zeroes them.
     * The cursor starts hidden: Android has no `PUZZLES_SHOW_CURSOR`.
     */
    class Ui : Game.Ui {
        var dragging: Boolean = false
        var dragStartX: Int = 0
        var dragStartY: Int = 0
        var dragEndX: Int = 0
        var dragEndY: Int = 0
        var drag: Int = 0
        var release: Int = 0
        var state: Int = GRID_UNKNOWN
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false
    }

    /**
     * `struct game_drawstate`: the per-cell effective value and the
     * per-line clue colour. Both caches start at 255, which no value
     * reaches, so the first frame draws everything. There is no blitter.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val visible: IntArray = IntArray(width * height) { 255 }
        val numColours: IntArray = IntArray(width + height) { 255 }
        var curX: Int = 0
        var curY: Int = 0
    }

    /** One generated description, the C aux answer, and the attempt count. */
    data class Generation(
        val desc: String,
        val auxInfo: String,
        val attempts: Int,
    )

    override val name: String = "Pattern"
    override val backendName: String = "pattern"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true

    /** `default_params`: `15x15`. */
    override fun defaultParams(): Game.Params = Params(15, 15)

    /**
     * `pattern_presets` and `game_fetch_preset`. The two largest presets
     * are inside `#ifndef SLOW_SYSTEM`, which neither Android nor the
     * oracle defines, so all five exist.
     */
    override fun presets(): List<Preset> = listOf(
        Params(10, 10),
        Params(15, 15),
        Params(20, 20),
        Params(25, 25),
        Params(30, 30),
    ).map { Preset("${it.w}x${it.h}", encodeParams(it, true)) }

    /**
     * `decode_params`: one number sets both dimensions. The digit skip
     * starts at the beginning of the string, so a leading sign or space
     * hides the `x` and gives a square board. Only a lower-case `x`
     * separates the sizes, and the rest of the string is ignored.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = atoi(text, 0)
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        val h = if (position < text.length && text[position] == 'x') {
            atoi(text, position + 1)
        } else {
            w
        }
        return Params(w, h)
    }

    /** `encode_params`: always `WxH`, the short and the full form alike. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.w}x${p.h}"
    }

    /** `validate_params`, in the C order and with the C messages. */
    @Suppress("UNUSED_PARAMETER")
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w <= 0 || p.h <= 0) {
            return "Width and height must both be greater than zero"
        }
        if (p.w > Int.MAX_VALUE - 1 || p.h > Int.MAX_VALUE - 1 ||
            p.w > Int.MAX_VALUE / p.h
        ) {
            return "Puzzle must not be unreasonably large"
        }
        if (p.w * p.h < 2) return "Grid must contain at least two squares"
        return null
    }

    // ------------------------------------------------------------------
    // Description generation.
    // ------------------------------------------------------------------

    /** `game_configure` (`pattern.c:149-170`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
        )
    }

    /** `custom_params` (`pattern.c:172-180`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
        )

    /**
     * `new_game_desc`: generate a soluble board, keep the answer in aux,
     * and write the `w + h` clue lists. The writer ends every entry with
     * `/` and then drops the last character, so a non-empty last entry
     * ends with its digits and an empty one keeps the preceding slash.
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

    // `pattern.c` draws and averages every cell and runs the line solver per attempt; 50x50 takes about 16 s and 100x100 is past a minute (PARITY).
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w.toLong() * p.h >= 2500) Game.SLOW_GENERATION_WARNING else null
    }

    /** [newDesc] with the generator attempt count, for tests. */
    fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params as Params, random)

    private fun generate(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generation {
        val w = params.w
        val h = params.h
        val generated = generateSoluble(random, w, h, cancel)
        val grid = generated.grid
        val aux = StringBuilder(w * h + 1)
        aux.append('S')
        for (value in grid) aux.append(if (value != 0) '1' else '0')

        val clue = IntArray(maxOf(w, h))
        val desc = StringBuilder()
        for (i in 0 until w + h) {
            val rowlen = if (i < w) {
                computeRowdata(clue, grid, i, h, w)
            } else {
                computeRowdata(clue, grid, (i - w) * w, w, 1)
            }
            if (rowlen > 0) {
                for (j in 0 until rowlen) {
                    desc.append(clue[j])
                    desc.append(if (j + 1 < rowlen) '.' else '/')
                }
            } else {
                desc.append('/')
            }
        }
        check(desc.isNotEmpty() && desc[desc.length - 1] == '/') {
            "pattern: description does not end with a slash"
        }
        desc.setLength(desc.length - 1)

        return Generation(desc.toString(), aux.toString(), generated.attempts)
    }

    private class GeneratedGrid(val grid: IntArray, val attempts: Int)

    /**
     * `generate_soluble`: repeat `generate` until no row or column is
     * monochrome (for dimensions above two) and the line solver finishes.
     * Both rejects are data-dependent, so the retry count is part of the
     * random stream.
     */
    private fun generateSoluble(
        random: RandomState,
        w: Int,
        h: Int,
        cancel: Cancellation,
    ): GeneratedGrid {
        val grid = IntArray(w * h)
        val solver = LineSolver(w, h)
        var attempts = 0
        while (true) {
            cancel.check()
            attempts++
            generate(random, w, h, grid)

            var ok = true
            if (w > 2) {
                for (i in 0 until h) {
                    var colours = 0
                    for (j in 0 until w) {
                        colours = colours or if (grid[i * w + j] == GRID_FULL) 2 else 1
                    }
                    if (colours != 3) ok = false
                }
            }
            if (h > 2) {
                for (j in 0 until w) {
                    var colours = 0
                    for (i in 0 until h) {
                        colours = colours or if (grid[i * w + j] == GRID_FULL) 2 else 1
                    }
                    if (colours != 3) ok = false
                }
            }
            if (!ok) continue

            ok = solver.solve(grid)
            if (ok) return GeneratedGrid(grid, attempts)
        }
    }

    /**
     * `generate`: one random value per cell, one cellular-automaton pass
     * in single precision, then the median value as the threshold. The
     * extra draw at an odd axis pair chooses between just under and just
     * over half.
     */
    private fun generate(random: RandomState, w: Int, h: Int, retgrid: IntArray) {
        val wh = w * h
        var fgrid = FloatArray(wh)
        for (i in 0 until h) {
            for (j in 0 until w) {
                fgrid[i * w + j] = random.upto(100000000L).toFloat() / 100000000f
            }
        }

        // One step, as the C `for (step = 0; step < 1; step++)` loop.
        val fgrid2 = FloatArray(wh)
        for (i in 0 until h) {
            for (j in 0 until w) {
                var sx = 0f
                var n = 0
                for (p in -1..1) {
                    for (q in -1..1) {
                        if (i + p < 0 || i + p >= h || j + q < 0 || j + q >= w) continue
                        // Do not average across a dimension of length two.
                        if ((h == 2 && p != 0) || (w == 2 && q != 0)) continue
                        n++
                        sx += fgrid[(i + p) * w + (j + q)]
                    }
                }
                fgrid2[i * w + j] = sx / n.toFloat()
            }
        }
        fgrid = fgrid2

        val sorted = fgrid.copyOf()
        sorted.sort()
        var index = wh / 2
        if ((w and h and 1) != 0) index += random.upto(2).toInt()
        val threshold = if (index < wh) sorted[index] else sorted[wh - 1] + 1f

        for (i in 0 until wh) {
            retgrid[i] = if (fgrid[i] >= threshold) GRID_FULL else GRID_EMPTY
        }
    }

    // ------------------------------------------------------------------
    // Description validation and load.
    // ------------------------------------------------------------------

    /** `validate_desc`, including the optional clue-squares suffix. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val w = p.w
        val h = p.h
        val total = w + h
        val cursor = DescCursor(desc)

        for (i in 0 until total) {
            var rowspace = if (i < w) h + 1 else w + 1

            if (cursor.peek() != 0 && isAsciiDigit(cursor.peek())) {
                while (true) {
                    val start = cursor.pos
                    while (isAsciiDigit(cursor.peek())) cursor.pos++
                    val n = atoi(desc, start)
                    if (n <= 0) return "all clues must be positive"
                    if (n > Int.MAX_VALUE - 1) return "at least one clue is grossly excessive"
                    rowspace -= n + 1
                    if (rowspace < 0) {
                        return if (i < w) {
                            "at least one column contains more numbers than will fit"
                        } else {
                            "at least one row contains more numbers than will fit"
                        }
                    }
                    if (cursor.take() != '.'.code) break
                }
            } else {
                cursor.take()
            }

            val last = cursor.last()
            if (last == '/'.code) {
                if (i + 1 == total) return "too many row/column specifications"
            } else if (last == 0 || last == ','.code) {
                if (i + 1 < total) return "too few row/column specifications"
            } else {
                return "unrecognised character in game specification"
            }
        }

        if (cursor.last() == ','.code) {
            val area = w * h
            var i = 0
            while (i < area) {
                val c = cursor.take()
                if (isAsciiLetter(c)) {
                    val len = asciiLower(c) - 'a'.code
                    i += len
                    if (len < 25 && i < area) i++
                    if (i > area) return "too much data in clue-squares section"
                } else if (c == 0) {
                    return "too little data in clue-squares section"
                } else {
                    return "unrecognised character in clue-squares section"
                }
            }
            if (cursor.peek() != 0) return "too much data in clue-squares section"
        }

        return null
    }

    /**
     * `new_game`: decode a validated description. C trusts the validator
     * and does not test the result, and a description that is too short
     * makes it read past the terminator. The midend always validates
     * first, and the port relies on that contract as well.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val h = p.h
        val wh = w * h
        val rowsize = maxOf(w, h)
        val rowdata = IntArray(rowsize * (w + h))
        val rowlen = IntArray(w + h)
        val grid = IntArray(wh) { GRID_UNKNOWN }
        val immutable = BooleanArray(wh)
        val cursor = DescCursor(desc)

        for (i in 0 until w + h) {
            rowlen[i] = 0
            if (cursor.peek() != 0 && isAsciiDigit(cursor.peek())) {
                do {
                    val start = cursor.pos
                    while (isAsciiDigit(cursor.peek())) cursor.pos++
                    rowdata[rowsize * i + rowlen[i]++] = atoi(desc, start)
                } while (cursor.take() == '.'.code)
            } else {
                cursor.take()
            }
        }

        /*
         * Choose a font size from the clues: the small font when any
         * column clue is at least 10, or when a row has so many clues
         * that the normal spacing does not fit.
         */
        var fontSize = FS_LARGE
        for (i in 0 until w) {
            for (j in 0 until rowlen[i]) {
                if (rowdata[rowsize * i + j] >= 10) fontSize = FS_SMALL
            }
        }
        for (i in w until w + h) {
            if (rowlen[i] * 3 - 2 > tlborder(w) * 2) fontSize = FS_SMALL
        }

        if (cursor.last() == ','.code) {
            var i = 0
            while (i < wh) {
                val c = cursor.take()
                val full = c in 'A'.code..'Z'.code
                val len = asciiLower(c) - 'a'.code
                i += len
                if (len < 25 && i < wh) {
                    grid[i] = if (full) GRID_FULL else GRID_EMPTY
                    immutable[i] = true
                    i++
                }
            }
        }

        val common = Common(w, h, rowsize, rowdata, rowlen, immutable, fontSize, grid.copyOf())
        return State(common, grid, false, false)
    }

    /** A C-string reader: a read past the end sees the NUL terminator. */
    private class DescCursor(private val text: String) {
        var pos: Int = 0

        fun peek(): Int = if (pos < text.length) text[pos].code else 0

        fun take(): Int {
            val c = peek()
            pos++
            return c
        }

        /** `desc[-1]`: the character before the cursor. */
        fun last(): Int = if (pos - 1 in text.indices) text[pos - 1].code else 0
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    /**
     * `interpret_move`. A press starts a drag preview and hides the
     * cursor. A release produces the C rectangle `F`/`E`/`U` move when
     * any non-immutable cell would change, and nothing otherwise. A
     * cursor select always produces a 1x1 move, even on a clue cell.
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
        val control = button and Buttons.MOD_CTRL != 0
        val shift = button and Buttons.MOD_SHFT != 0
        val plain = Buttons.stripModifiers(button)

        val gx = fromCoord(s.width, x, tileSize)
        val gy = fromCoord(s.height, y, tileSize)

        if (gx in 0 until s.width && gy in 0 until s.height &&
            (plain == Buttons.LEFT_BUTTON || plain == Buttons.RIGHT_BUTTON ||
                plain == Buttons.MIDDLE_BUTTON)
        ) {
            val current = s.grid[gy * s.width + gx]
            u.dragging = true
            when (plain) {
                Buttons.LEFT_BUTTON -> {
                    u.drag = Buttons.LEFT_DRAG
                    u.release = Buttons.LEFT_RELEASE
                    // STYLUS_BASED: unknown -> full -> empty -> unknown.
                    u.state = (current + 2) % 3
                }

                Buttons.RIGHT_BUTTON -> {
                    u.drag = Buttons.RIGHT_DRAG
                    u.release = Buttons.RIGHT_RELEASE
                    // STYLUS_BASED: unknown -> empty -> full -> unknown.
                    u.state = (current + 1) % 3
                }

                else -> {
                    u.drag = Buttons.MIDDLE_DRAG
                    u.release = Buttons.MIDDLE_RELEASE
                    u.state = GRID_UNKNOWN
                }
            }
            u.dragStartX = gx
            u.dragEndX = gx
            u.dragStartY = gy
            u.dragEndY = gy
            u.curVisible = false
            return MoveResult.UiUpdate
        }

        if (u.dragging && plain == u.drag) {
            /*
             * Snap a fill drag to the dominant axis. A middle drag is
             * not snapped, so it can mark a rectangle unknown.
             */
            var ex = gx
            var ey = gy
            if (u.state != GRID_UNKNOWN) {
                if (abs(ex - u.dragStartX) > abs(ey - u.dragStartY)) {
                    ey = u.dragStartY
                } else {
                    ex = u.dragStartX
                }
            }
            if (ex < 0) ex = 0
            if (ey < 0) ey = 0
            if (ex >= s.width) ex = s.width - 1
            if (ey >= s.height) ey = s.height - 1
            u.dragEndX = ex
            u.dragEndY = ey
            return MoveResult.UiUpdate
        }

        if (u.dragging && plain == u.release) {
            val x1 = minOf(u.dragStartX, u.dragEndX)
            val x2 = maxOf(u.dragStartX, u.dragEndX)
            val y1 = minOf(u.dragStartY, u.dragEndY)
            val y2 = maxOf(u.dragStartY, u.dragEndY)
            var moveNeeded = false
            for (yy in y1..y2) {
                for (xx in x1..x2) {
                    val index = yy * s.width + xx
                    if (!s.common.immutable[index] && s.grid[index] != u.state) {
                        moveNeeded = true
                    }
                }
            }
            u.dragging = false
            if (moveNeeded) {
                val ch = when (u.state) {
                    GRID_FULL -> 'F'
                    GRID_EMPTY -> 'E'
                    else -> 'U'
                }
                return MoveResult.Move("$ch$x1,$y1,${x2 - x1 + 1},${y2 - y1 + 1}")
            }
            return MoveResult.UiUpdate
        }

        if (Buttons.isCursorMove(plain)) {
            val oldX = u.curX
            val oldY = u.curY
            val ret = moveCursor(plain, u, s.width, s.height)
            if (!control && !shift) return ret

            val newstate = when {
                control && shift -> GRID_UNKNOWN
                control -> GRID_FULL
                else -> GRID_EMPTY
            }
            if (s.grid[oldY * s.width + oldX] == newstate &&
                s.grid[u.curY * s.width + u.curX] == newstate
            ) {
                return ret
            }
            val ch = when (newstate) {
                GRID_FULL -> 'F'
                GRID_EMPTY -> 'E'
                else -> 'U'
            }
            return MoveResult.Move(
                "$ch${minOf(oldX, u.curX)},${minOf(oldY, u.curY)}," +
                    "${abs(oldX - u.curX) + 1},${abs(oldY - u.curY) + 1}",
            )
        }

        if (plain == Buttons.CURSOR_SELECT || plain == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            val current = s.grid[u.curY * s.width + u.curX]
            val newstate = if (plain == Buttons.CURSOR_SELECT2) {
                when (current) {
                    GRID_UNKNOWN -> GRID_EMPTY
                    GRID_EMPTY -> GRID_FULL
                    else -> GRID_UNKNOWN
                }
            } else {
                when (current) {
                    GRID_UNKNOWN -> GRID_FULL
                    GRID_FULL -> GRID_EMPTY
                    else -> GRID_UNKNOWN
                }
            }
            val ch = when (newstate) {
                GRID_FULL -> 'F'
                GRID_EMPTY -> 'E'
                else -> 'U'
            }
            return MoveResult.Move("$ch${u.curX},${u.curY},1,1")
        }

        return MoveResult.Unused
    }

    /** `move_cursor` without wrap, as the cursor moves of `pattern.c`. */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
        val dx = when (button) {
            Buttons.CURSOR_UP -> 0
            Buttons.CURSOR_DOWN -> 0
            Buttons.CURSOR_LEFT -> -1
            Buttons.CURSOR_RIGHT -> 1
            else -> return MoveResult.Unused
        }
        val dy = when (button) {
            Buttons.CURSOR_UP -> -1
            Buttons.CURSOR_DOWN -> 1
            else -> 0
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

    /**
     * `execute_move`. An `S` move of exactly `w*h + 1` characters
     * replaces the whole board and sets `completed` and `cheated`. A
     * rectangle move sets every non-immutable cell, then runs the
     * completion test when the state is not already complete. The
     * scanner follows `sscanf("%d,%d,%d,%d")`: leading white space and a
     * sign are accepted, and trailing junk is ignored.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.width
        val h = s.height
        val wh = w * h

        if (move.isEmpty()) return null

        if (move[0] == 'S' && move.length == wh + 1) {
            val grid = IntArray(wh)
            for (i in 0 until wh) {
                grid[i] = if (move[i + 1] == '1') GRID_FULL else GRID_EMPTY
            }
            return State(s.common, grid, true, true)
        }

        if (move[0] != 'F' && move[0] != 'E' && move[0] != 'U') return null
        val parsed = scanFour(move, 1) ?: return null
        var x1 = parsed[0]
        var y1 = parsed[1]
        var x2 = parsed[2]
        var y2 = parsed[3]
        if (x1 < 0 || x2 < 0 || x1 + x2 > w || y1 < 0 || y2 < 0 || y1 + y2 > h) {
            return null
        }
        x2 += x1
        y2 += y1
        val value = when (move[0]) {
            'F' -> GRID_FULL
            'E' -> GRID_EMPTY
            else -> GRID_UNKNOWN
        }

        val grid = s.grid.copyOf()
        for (yy in y1 until y2) {
            for (xx in x1 until x2) {
                val index = yy * w + xx
                if (!s.common.immutable[index]) grid[index] = value
            }
        }

        var completed = s.completed
        if (!completed) completed = isComplete(s.common, grid)

        return State(s.common, grid, completed, s.cheated)
    }

    /**
     * The completion test of `execute_move`: every column and every row
     * must have no unknown cell and must match its clue list. The C code
     * compares the entries with `memcmp`, so the port compares the first
     * `len` entries. A negative `len` (an unknown cell) never matches a
     * clue count.
     */
    private fun isComplete(common: Common, grid: IntArray): Boolean {
        val w = common.w
        val h = common.h
        val scratch = IntArray(common.rowsize)
        for (i in 0 until w) {
            val len = computeRowdata(scratch, grid, i, h, w)
            if (len != common.rowlen[i]) return false
            for (k in 0 until len) {
                if (common.rowdata[i * common.rowsize + k] != scratch[k]) return false
            }
        }
        for (i in 0 until h) {
            val len = computeRowdata(scratch, grid, i * w, w, 1)
            if (len != common.rowlen[i + w]) return false
            for (k in 0 until len) {
                if (common.rowdata[(i + w) * common.rowsize + k] != scratch[k]) return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Solver and text.
    // ------------------------------------------------------------------

    /**
     * `solve_game`. An `AUXINFO` answer is returned verbatim; otherwise
     * the line solver runs on the clue data and the immutable marks of
     * the decoded initial grid, which is C's `states[0]`. The played
     * grid does not change the answer. A board the solver cannot finish
     * returns null.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /**
     * `solve_game` (`pattern.c:1100-1126`). A stored answer is returned
     * unchanged; otherwise the line solver either finishes every row and
     * column or fails with the C message.
     */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        if (auxInfo != null) return SolveOutcome(auxInfo, null)
        val s = state as State
        val matrix = LineSolver(s.width, s.height).solveFromCommon(s.common)
            ?: return SolveOutcome(null, "Solving algorithm cannot complete this puzzle")
        val out = StringBuilder(s.grid.size + 1)
        out.append('S')
        for (value in matrix) out.append(if (value == BLOCK) '1' else '0')
        return SolveOutcome(out.toString(), null)
    }

    /**
     * `game_text_format`: a fixed-width ASCII board. The port follows the
     * C buffer arithmetic line by line, including the last line copied
     * from the first grid line.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val common = s.common
        var leftGap = 0
        var topGap = 0
        val ch = 2
        var cw = 1
        var limit = 1

        for (i in 0 until w) {
            topGap = maxOf(topGap, common.rowlen[i])
            for (j in 0 until common.rowlen[i]) {
                while (common.rowdata[i * common.rowsize + j] >= limit) {
                    ++cw
                    limit *= 10
                }
            }
        }
        for (i in 0 until h) {
            var digitCount = 0
            var predecessors = false
            for (j in 0 until common.rowlen[i + w]) {
                var copy = common.rowdata[(i + w) * common.rowsize + j]
                digitCount += if (predecessors) 1 else 0
                predecessors = true
                do {
                    ++digitCount
                    copy /= 10
                } while (copy != 0)
            }
            leftGap = maxOf(leftGap, digitCount)
        }

        cw = maxOf(cw, 3)

        val gw = w * cw + 2
        val gh = h * ch + 1
        val lw = gw + leftGap
        val lh = gh + topGap
        val len = lw * lh
        val topleft = lw * topGap + leftGap

        val board = CharArray(len) { ' ' }

        for (i in 0 until lh) {
            board[lw - 1 + i * lw] = '\n'
            if (i < topGap) continue
            board[lw - 2 + i * lw] = if ((i - topGap) % ch != 0) '|' else '+'
        }

        for (i in 0 until w) {
            for (j in 0 until common.rowlen[i]) {
                val cell = topleft + i * cw + 1 + lw * (j - common.rowlen[i])
                val text = common.rowdata[i * common.rowsize + j]
                    .toString()
                    .padStart(cw - 1)
                for (k in text.indices) board[cell + k] = text[k]
                board[cell + text.length] = ' ' // de-NUL-ify
            }
        }

        for (i in 0 until h) {
            val start = topGap * lw + leftGap + (i * ch + 1) * lw
            val text = StringBuilder()
            for (j in 0 until common.rowlen[i + w]) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(common.rowdata[(i + w) * common.rowsize + j])
            }
            for (k in text.indices) board[start - text.length + k] = text[k]
        }

        for (i in 0 until h) {
            for (j in 0 until w) {
                val cell = topleft + j * cw + i * ch * lw
                board[cell] = '+'
                for (dx in 1 until cw) board[cell + dx] = '-'
                for (dy in 1 until ch) board[cell + dy * lw] = '|'
                if (s.grid[i * w + j] == GRID_UNKNOWN) continue
                val fill = if (s.grid[i * w + j] == GRID_FULL) '#' else '.'
                for (dx in 1 until cw) {
                    for (dy in 1 until ch) board[cell + dx + dy * lw] = fill
                }
            }
        }

        for (k in 0 until gw - 1) {
            board[topleft + h * ch * lw + k] = board[topleft + k]
        }

        return String(board)
    }

    /** `compute_rowdata`: the run lengths, or -1 at an unknown cell. */
    private fun computeRowdata(
        ret: IntArray,
        grid: IntArray,
        start: Int,
        len: Int,
        step: Int,
    ): Int {
        var n = 0
        var i = 0
        while (i < len) {
            if (grid[start + i * step] == GRID_FULL) {
                var runlen = 1
                while (i + runlen < len && grid[start + (i + runlen) * step] == GRID_FULL) {
                    runlen++
                }
                ret[n++] = runlen
                i += runlen
            }
            if (i < len && grid[start + i * step] == GRID_UNKNOWN) return -1
            i++
        }
        return n
    }

    /**
     * The line solver of `solve_puzzle`, `do_row`, and `do_recurse`. The
     * workspace is reused between calls, as the C callers do, because
     * the generator calls it once per attempt.
     *
     * The recursion starts from `len - 1` for the first block and is
     * bounded by `10^ndone` placements; the `minpos`/`maxpos` marks skip
     * a tail whose result is already known. A change to the bounds, to
     * the `<=` tests, or to the order of the writes changes which boards
     * the generator accepts.
     */
    private class LineSolver(val w: Int, val h: Int) {
        private val wh = w * h
        private val max = maxOf(w, h)
        private val matrix = IntArray(wh)
        private val known = IntArray(max)
        private val deduced = IntArray(max)
        private val row = IntArray(max)
        private val minposDone = IntArray(max)
        private val maxposDone = IntArray(max)
        private val minposOk = IntArray(max)
        private val maxposOk = IntArray(max)
        private val clueScratch = IntArray(max + 1)
        private val changedH = IntArray(h)
        private val changedW = IntArray(w)

        /** `solve_puzzle(NULL, grid, ...)`: the generation gate. */
        fun solve(grid: IntArray): Boolean {
            matrix.fill(UNKNOWN)
            return solvePuzzle(null, grid)
        }

        /** `solve_puzzle(state, NULL, ...)`: the answer, or null. */
        fun solveFromCommon(common: Common): IntArray? {
            matrix.fill(UNKNOWN)
            for (i in 0 until wh) {
                if (common.immutable[i]) matrix[i] = common.initialGrid[i]
            }
            return if (solvePuzzle(common, null)) matrix else null
        }

        private fun solvePuzzle(common: Common?, grid: IntArray?): Boolean {
            var maxH = 0
            var maxW = 0

            // The initial change counts choose which line to examine first.
            for (i in 0 until h) {
                val rowlen: Int
                if (common != null) {
                    copyClues(common, w + i)
                    rowlen = common.rowlen[w + i]
                } else {
                    rowlen = Pattern.computeRowdata(clueScratch, grid!!, i * w, w, 1)
                }
                clueScratch[rowlen] = 0
                changedH[i] = if (rowlen == 0) {
                    w
                } else {
                    var freespace = w + 1
                    for (j in 0 until rowlen) freespace -= clueScratch[j] + 1
                    var changed = 0
                    for (j in 0 until rowlen) {
                        if (clueScratch[j] > freespace) changed += clueScratch[j] - freespace
                    }
                    changed
                }
                for (j in 0 until w) {
                    if (matrix[i * w + j] != UNKNOWN) changedH[i]++
                }
            }
            maxH = 0
            for (i in 0 until h) if (changedH[i] > maxH) maxH = changedH[i]

            for (i in 0 until w) {
                val rowlen: Int
                if (common != null) {
                    copyClues(common, i)
                    rowlen = common.rowlen[i]
                } else {
                    rowlen = Pattern.computeRowdata(clueScratch, grid!!, i, h, w)
                }
                clueScratch[rowlen] = 0
                changedW[i] = if (rowlen == 0) {
                    h
                } else {
                    var freespace = h + 1
                    for (j in 0 until rowlen) freespace -= clueScratch[j] + 1
                    var changed = 0
                    for (j in 0 until rowlen) {
                        if (clueScratch[j] > freespace) changed += clueScratch[j] - freespace
                    }
                    changed
                }
                for (j in 0 until h) {
                    if (matrix[j * w + i] != UNKNOWN) changedW[i]++
                }
            }
            maxW = 0
            for (i in 0 until w) if (changedW[i] > maxW) maxW = changedW[i]

            do {
                while (maxH != 0 && maxH >= maxW) {
                    for (i in 0 until h) {
                        if (changedH[i] >= maxH) {
                            if (common != null) {
                                copyClues(common, w + i)
                                clueScratch[common.rowlen[w + i]] = 0
                            } else {
                                clueScratch[Pattern.computeRowdata(clueScratch, grid!!, i * w, w, 1)] = 0
                            }
                            doRow(
                                matrix, i * w, w, 1, clueScratch, changedW,
                            )
                            changedH[i] = 0
                        }
                    }
                    maxW = 0
                    for (i in 0 until w) if (changedW[i] > maxW) maxW = changedW[i]
                    maxH--
                }
                while (maxW != 0 && maxW >= maxH) {
                    for (i in 0 until w) {
                        if (changedW[i] >= maxW) {
                            if (common != null) {
                                copyClues(common, i)
                                clueScratch[common.rowlen[i]] = 0
                            } else {
                                clueScratch[Pattern.computeRowdata(clueScratch, grid!!, i, h, w)] = 0
                            }
                            doRow(
                                matrix, i, h, w, clueScratch, changedH,
                            )
                            changedW[i] = 0
                        }
                    }
                    maxH = 0
                    for (i in 0 until h) if (changedH[i] > maxH) maxH = changedH[i]
                    maxW--
                }
            } while (maxH > 0 || maxW > 0)

            for (i in 0 until wh) if (matrix[i] == UNKNOWN) return false
            return true
        }

        /** Copies one clue list into [clueScratch], with its sentinel. */
        private fun copyClues(common: Common, i: Int) {
            for (j in 0 until common.rowlen[i]) {
                clueScratch[j] = common.rowdata[i * common.rowsize + j]
            }
        }

        /**
         * `do_row`: build the constraints of one line and write every
         * deduced cell back into the matrix. Only cells that were unknown
         * change, and each change bumps the caller's change counter.
         */
        private fun doRow(
            start: IntArray,
            offset: Int,
            len: Int,
            step: Int,
            data: IntArray,
            changed: IntArray,
        ) {
            var freespace = len + 1
            var rowlen = 0
            while (data[rowlen] != 0) {
                minposDone[rowlen] = len - 1
                minposOk[rowlen] = len - 1
                maxposDone[rowlen] = 0
                maxposOk[rowlen] = 0
                freespace -= data[rowlen] + 1
                rowlen++
            }

            for (i in 0 until len) {
                known[i] = start[offset + i * step]
                deduced[i] = 0
            }
            var i = len - 1
            while (i >= 0 && known[i] == DOT) {
                freespace--
                i--
            }

            if (rowlen == 0) {
                for (k in 0 until len) deduced[k] = DOT
            } else if (rowlen == 1 && data[0] == len) {
                for (k in 0 until len) deduced[k] = BLOCK
            } else {
                doRecurse(known, deduced, row, data, len, freespace, 0, 0)
            }

            for (k in 0 until len) {
                if (deduced[k] != 0 && deduced[k] != STILL_UNKNOWN && known[k] == UNKNOWN) {
                    start[offset + k * step] = deduced[k]
                    changed[k]++
                }
            }
        }

        /** `do_recurse`: enumerate every placement of the remaining blocks. */
        private fun doRecurse(
            known: IntArray,
            deduced: IntArray,
            row: IntArray,
            data: IntArray,
            len: Int,
            freespace: Int,
            ndone: Int,
            lowest: Int,
        ): Boolean {
            if (data[ndone] != 0) {
                if (lowest >= minposDone[ndone] && lowest <= maxposDone[ndone]) {
                    if (lowest >= minposOk[ndone] && lowest <= maxposOk[ndone]) {
                        for (k in 0 until lowest) deduced[k] = deduced[k] or row[k]
                    }
                    return lowest >= minposOk[ndone] && lowest <= maxposOk[ndone]
                } else {
                    if (lowest < minposDone[ndone]) minposDone[ndone] = lowest
                    if (lowest > maxposDone[ndone]) maxposDone[ndone] = lowest
                }
                for (placement in 0..freespace) {
                    var j = lowest
                    var failed = false
                    var k = 0
                    while (k < placement) {
                        if (known[j] == BLOCK) {
                            failed = true
                            break
                        }
                        row[j++] = DOT
                        k++
                    }
                    if (!failed) {
                        k = 0
                        while (k < data[ndone]) {
                            if (known[j] == DOT) {
                                failed = true
                                break
                            }
                            row[j++] = BLOCK
                            k++
                        }
                    }
                    if (!failed && j < len) {
                        if (known[j] == BLOCK) {
                            failed = true
                        } else {
                            row[j++] = DOT
                        }
                    }
                    if (!failed) {
                        if (doRecurse(
                                known,
                                deduced,
                                row,
                                data,
                                len,
                                freespace - placement,
                                ndone + 1,
                                j,
                            )
                        ) {
                            if (lowest < minposOk[ndone]) minposOk[ndone] = lowest
                            if (lowest + placement > maxposOk[ndone]) {
                                maxposOk[ndone] = lowest + placement
                            }
                            if (lowest + placement > maxposDone[ndone]) {
                                maxposDone[ndone] = lowest + placement
                            }
                        }
                    }
                }
                return lowest >= minposOk[ndone] && lowest <= maxposOk[ndone]
            } else {
                for (k in lowest until len) {
                    if (known[k] == BLOCK) return false
                    row[k] = DOT
                }
                for (k in 0 until len) deduced[k] = deduced[k] or row[k]
                return true
            }
        }
    }

    // ------------------------------------------------------------------
    // Status, keys, and UI.
    // ------------------------------------------------------------------

    /** `game_status`: completed or open. There is no losing status. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /** Pattern has no status bar, so the text is empty. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /**
     * `game_changed_state`: true exactly on a transition into an honest
     * completed state. A solve is cheated and raises no event; a redo
     * into the completed state raises it again.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return next.completed && !next.cheated &&
            oldState != null && !(oldState as State).completed
    }

    /** `game_request_keys` is NULL; the Android full-cursor hint only changes the arrows. */
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /**
     * `current_key_label`: the label names the next state of the cell
     * under a visible cursor. C reads the grid without a bounds test; the
     * cursor is always inside, and this guard returns the empty label for
     * the unreachable outside case.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) return ""
        val u = ui as Ui
        val s = state as State
        if (!u.curVisible) return ""
        if (u.curX < 0 || u.curY < 0 || u.curX >= s.width || u.curY >= s.height) return ""
        return when (s.grid[u.curY * s.width + u.curX]) {
            GRID_UNKNOWN -> if (button == Buttons.CURSOR_SELECT) "Black" else "White"
            GRID_FULL -> if (button == Buttons.CURSOR_SELECT) "White" else "Grey"
            GRID_EMPTY -> if (button == Buttons.CURSOR_SELECT) "Grey" else "Black"
            else -> ""
        }
    }

    /** `new_ui`: the environment variable is not read; the cursor starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `game_get_cursor_location`: the tile, only for a visible cursor. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        val s = state as State
        if (!u.curVisible) return null
        return Game.CursorRect(
            toCoord(s.width, u.curX, tileSize),
            toCoord(s.height, u.curY, tileSize),
            tileSize,
            tileSize,
        )
    }

    /** `game_compute_size`: the borders are truncated separately. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(size(p.w, tileSize), size(p.h, tileSize))
    }

    // ------------------------------------------------------------------
    // Palette and drawing.
    // ------------------------------------------------------------------

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`: only the background comes from the shell. There is
     * no `game_mkhighlight`, and `VIVID_COLOURS` is not read.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) {
            ret[COL_BACKGROUND * 3 + i] = background[i]
            ret[COL_GRID * 3 + i] = 0.3f
            ret[COL_UNKNOWN * 3 + i] = 0.5f
            ret[COL_TEXT * 3 + i] = 0.0f
            ret[COL_FULL * 3 + i] = 0.0f
            ret[COL_EMPTY * 3 + i] = 1.0f
            ret[COL_CURSOR_GUIDE * 3 + i] = 0.5f
        }
        ret[COL_CURSOR * 3] = 1.0f
        ret[COL_CURSOR * 3 + 1] = 0.25f
        ret[COL_CURSOR * 3 + 2] = 0.25f
        ret[COL_ERROR * 3] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f
        return ret
    }

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
     * `game_redraw` without the flash. The first frame draws the grid
     * frame and updates the whole window; later frames draw only the
     * cells whose effective value changed and the clue lists whose
     * colour changed. A cursor move forces its old and new cells.
     *
     * [ui]'s drag state paints a preview without a state change. The
     * error colour is recomputed from the grid on every frame, and the
     * cursor guide only replaces the normal text colour.
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

        if (!ds.started) {
            sink.rect(
                toCoord(w, 0, tileSize) - 1,
                toCoord(h, 0, tileSize) - 1,
                w * tileSize + 3,
                h * tileSize + 3,
                COL_GRID,
            )
            ds.started = true
            sink.update(0, 0, size(w, tileSize), size(h, tileSize))
        }

        val x1: Int
        val x2: Int
        val y1: Int
        val y2: Int
        if (u.dragging) {
            x1 = minOf(u.dragStartX, u.dragEndX)
            x2 = maxOf(u.dragStartX, u.dragEndX)
            y1 = minOf(u.dragStartY, u.dragEndY)
            y2 = maxOf(u.dragStartY, u.dragEndY)
        } else {
            x1 = -1
            x2 = -1
            y1 = -1
            y2 = -1
        }

        val cx: Int
        val cy: Int
        if (u.curVisible) {
            cx = u.curX
            cy = u.curY
        } else {
            cx = -1
            cy = -1
        }
        val cmoved = cx != ds.curX || cy != ds.curY

        for (i in 0 until h) {
            for (j in 0 until w) {
                val index = i * w + j
                var value = if (u.dragging && j in x1..x2 && i in y1..y2 &&
                    !s.common.immutable[index]
                ) {
                    u.state
                } else {
                    s.grid[index]
                }

                var cc = false
                if (cmoved) {
                    if (j == cx && i == cy) cc = true
                    if (j == ds.curX && i == ds.curY) cc = true
                }

                if (ds.visible[index] != value || cc) {
                    gridSquare(sink, ds, i, j, value, j == cx && i == cy, tileSize)
                    ds.visible[index] = value
                }
            }
        }
        ds.curX = cx
        ds.curY = cy

        for (i in 0 until w + h) {
            var colour = if (checkErrors(s, i)) COL_ERROR else COL_TEXT
            if (colour == COL_TEXT &&
                ((cx >= 0 && i == cx) || (cy >= 0 && i == cy + w))
            ) {
                colour = COL_CURSOR_GUIDE
            }
            if (ds.numColours[i] != colour) {
                drawNumbers(sink, ds, s, i, true, colour, tileSize)
                ds.numColours[i] = colour
            }
        }
    }

    /** `grid_square`: the tile, the thick separators, and the cursor ring. */
    private fun gridSquare(
        sink: DrawSink,
        ds: DrawState,
        y: Int,
        x: Int,
        value: Int,
        cur: Boolean,
        tileSize: Int,
    ) {
        val t = tileSize
        sink.rect(toCoord(ds.width, x, t), toCoord(ds.height, y, t), t, t, COL_GRID)

        val xl = if (x % 5 == 0) 1 else 0
        val yt = if (y % 5 == 0) 1 else 0
        val xr = if (x % 5 == 4 || x == ds.width - 1) 1 else 0
        val yb = if (y % 5 == 4 || y == ds.height - 1) 1 else 0

        val dx = toCoord(ds.width, x, t) + 1 + xl
        val dy = toCoord(ds.height, y, t) + 1 + yt
        val dw = t - xl - xr - 1
        val dh = t - yt - yb - 1

        sink.rect(
            dx,
            dy,
            dw,
            dh,
            when (value) {
                GRID_FULL -> COL_FULL
                GRID_EMPTY -> COL_EMPTY
                else -> COL_UNKNOWN
            },
        )
        if (cur) {
            outline(sink, dx, dy, dw, dh, COL_CURSOR)
            outline(sink, dx + 1, dy + 1, dw - 2, dh - 2, COL_CURSOR)
        }

        sink.update(toCoord(ds.width, x, t), toCoord(ds.height, y, t), t, t)
    }

    /** `draw_rect_outline`: a four-point polygon with no fill. */
    private fun outline(
        sink: DrawSink,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        colour: Int,
    ) {
        val x0 = x
        val x1 = x + w - 1
        val y0 = y
        val y1 = y + h - 1
        sink.poly(intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0), -1, colour)
    }

    /**
     * `draw_numbers`: one clue list in its band. The band is erased with
     * the background, and the font size and the clue positions follow
     * the C arithmetic exactly.
     */
    private fun drawNumbers(
        sink: DrawSink,
        ds: DrawState,
        state: State,
        i: Int,
        erase: Boolean,
        colour: Int,
        tileSize: Int,
    ) {
        val t = tileSize
        val rowlen = state.common.rowlen[i]
        val base = state.common.rowsize * i
        val rx: Int
        val ry: Int
        val rw: Int
        val rh: Int
        if (i < state.width) {
            rx = toCoord(state.width, i, t)
            ry = 0
            rw = t
            rh = border(t) + tlborder(state.height) * t
        } else {
            rx = 0
            ry = toCoord(state.height, i - state.width, t)
            rw = border(t) + tlborder(state.width) * t
            rh = t
        }

        sink.clip(rx, ry, rw, rh)
        if (erase) sink.rect(rx, ry, rw, rh, COL_BACKGROUND)

        val fontSize = ((t + 0.5f) /
            (if (state.common.fontSize == FS_LARGE) 1.2f else 1.8f)).toInt()

        var nfit = if (i < state.width) tlborder(state.height) else tlborder(state.width)
        nfit = maxOf(rowlen, nfit) - 1

        if (i < state.width) {
            for (j in 0 until rowlen) {
                val x = rx
                var y = border(t) + t * (tlborder(state.height) - 1)
                y -= ((rowlen - j - 1) * t) * (tlborder(state.height) - 1) / nfit
                sink.text(
                    x + t / 2,
                    y + t / 2,
                    Text.FONT_VARIABLE,
                    fontSize,
                    Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                    colour,
                    state.common.rowdata[base + j].toString(),
                )
            }
        } else {
            val text = StringBuilder()
            val spaces = if (rowlen > tlborder(state.width)) " " else "  "
            for (j in 0 until rowlen) {
                if (j > 0) text.append(spaces)
                text.append(state.common.rowdata[base + j])
            }
            val y = ry
            val x = border(t) + t * (tlborder(state.width) - 1)
            sink.text(
                x + t,
                y + t / 2,
                Text.FONT_VARIABLE,
                fontSize,
                Text.ALIGN_HRIGHT or Text.ALIGN_VCENTRE,
                colour,
                text.toString(),
            )
        }

        sink.unclip()
        sink.update(rx, ry, rw, rh)
    }

    /**
     * `check_errors`: true when a complete run of filled cells cannot be
     * found in the clue list. A run is complete when an empty cell or the
     * line end bounds it; an unknown cell breaks contiguity. This is a
     * hint on the clue colour, not a game rule.
     */
    private fun checkErrors(state: State, index: Int): Boolean {
        val common = state.common
        val w = common.w
        val h = common.h
        var ncontig = 1
        var rowpos = 0
        val rowlen = common.rowlen[index]
        val base = common.rowsize * index

        fun rowdataAt(k: Int): Int =
            if (k < 0 || k >= rowlen) 0 else common.rowdata[base + k]

        fun foundRun(r: Int): Boolean {
            for (newpos in rowpos..rowlen) {
                if (rowdataAt(newpos) != r) continue
                var matches = true
                for (i in 1..ncontig) {
                    if (rowdataAt(newpos - i) != rowdataAt(rowpos - i)) {
                        matches = false
                        break
                    }
                }
                if (!matches) continue
                rowpos = newpos + 1
                ncontig++
                return true
            }
            return false
        }

        val start: Int
        val step: Int
        val end: Int
        if (index < w) {
            start = index
            step = w
            end = start + step * h
        } else {
            start = (index - w) * w
            step = 1
            end = start + step * w
        }

        var runlen = -1
        var j = start - step
        while (j <= end) {
            val value = if (j < start || j == end) GRID_EMPTY else state.grid[j]
            if (value == GRID_UNKNOWN) {
                runlen = -1
                ncontig = 0
            } else if (value == GRID_FULL) {
                if (runlen >= 0) runlen++
            } else if (value == GRID_EMPTY) {
                if (runlen > 0) {
                    if (!foundRun(runlen)) return true
                }
                runlen = 0
            }
            j += step
        }

        if (!foundRun(0)) return true
        return false
    }

    // ------------------------------------------------------------------
    // Geometry.
    // ------------------------------------------------------------------

    /** `BORDER`, `GUTTER`, and `TLBORDER` of `pattern.c:33-45`. */
    private fun border(tileSize: Int): Int = 3 * tileSize / 4

    private fun tlborder(d: Int): Int = d / 5 + 2

    /** `TOCOORD(d, x)`. */
    private fun toCoord(d: Int, x: Int, tileSize: Int): Int =
        border(tileSize) + tileSize / 2 + tileSize * (tlborder(d) + x)

    /** `FROMCOORD(d, x)`, with C truncation toward zero. */
    private fun fromCoord(d: Int, x: Int, tileSize: Int): Int =
        (x - (border(tileSize) + tileSize / 2 + tileSize * (tlborder(d) - 1))) /
            tileSize - 1

    /** `SIZE(d)`. */
    private fun size(d: Int, tileSize: Int): Int =
        2 * border(tileSize) + tileSize / 2 + tileSize * (tlborder(d) + d)

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private fun isAsciiDigit(c: Int): Boolean = c in '0'.code..'9'.code

    private fun isAsciiLetter(c: Int): Boolean =
        c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    private fun asciiLower(c: Int): Int = if (c in 'A'.code..'Z'.code) c + 32 else c

    /**
     * `sscanf(move + start, "%d,%d,%d,%d", ...)`: four values separated
     * by literal commas, or null when any conversion fails. `%d` takes
     * leading white space, an optional sign, and digits; trailing text is
     * ignored by the caller. A value outside the `long` range saturates,
     * and the assignment to `int` keeps the low 32 bits, as in the other
     * ports.
     */
    private fun scanFour(text: String, start: Int): IntArray? {
        val result = IntArray(4)
        var position = start
        for (index in 0..3) {
            if (index > 0) {
                if (position >= text.length || text[position] != ',') return null
                position++
            }
            val scanned = scanInt(text, position) ?: return null
            result[index] = scanned.value
            position = scanned.end
        }
        return result
    }

    private class ScannedInt(val value: Int, val end: Int)

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

    /** C `atoi`: white space, an optional sign, then digits, saturating. */
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

    private fun abs(value: Int): Int = if (value < 0) -value else value
}
