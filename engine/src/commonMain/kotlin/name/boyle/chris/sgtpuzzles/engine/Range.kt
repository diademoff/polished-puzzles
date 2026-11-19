package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `range.c`: Kurodoko, also called Kuromasu.
 *
 * The player paints some cells black. A clue cell is never black, no two
 * black cells touch, the remaining cells form one connected region, and
 * a clue equals its horizontal plus its vertical white run length minus
 * one. Only the black cells decide a win, so a correct black set wins
 * with undecided or dotted cells still on the board.
 *
 * The parameters are two `signed char` fields upstream, and the
 * truncation and the `atoi` quirks are part of the game-id format:
 * `300x6` is `44x6`, and `+9x6` is `9x9`. `encode_params` ignores
 * `full`, so `PARAMS` and `CPARAMS` are always equal.
 *
 * The description is a run-length form of the clue board. The solver
 * lives in [RangeSolver] and is also the generator's acceptance test, so
 * a solver difference changes every description.
 *
 * C reads the first state for `solve_game`, because `midend_solve`
 * passes `states[0]`, so this port keeps the decoded initial grid in
 * [Shared] and solves that, not the current board.
 *
 * There is no `AUXINFO`, no `UI` record, and no blitter. The completion
 * flash is not ported: the seam has no flash time.
 */
object Range : Game {

    private const val PREFERRED_TILE_SIZE = 32

    /** Cell values from `range.c:285`. */
    const val BLACK = RangeSolver.BLACK
    const val WHITE = RangeSolver.WHITE
    const val EMPTY = RangeSolver.EMPTY

    /** The colour enum from `range.c:1658-1668`. */
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_BLACK = COL_GRID
    private const val COL_TEXT = COL_GRID
    private const val COL_USER = COL_GRID
    private const val COL_ERROR = 2
    private const val COL_LOWLIGHT = 3
    private const val COL_CURSOR = COL_LOWLIGHT
    private const val NCOLOURS = 4

    /** The drawstate word: the value shifted, then two flag bits. */
    private const val FLAG_ERROR = 1
    private const val FLAG_CURSOR = 2
    private const val VALUE_SHIFT = 2

    /** `MASK(BLACK)` and `MASK(EMPTY)` as `find_errors` uses them. */
    private const val MASK_BLACK = 1
    private const val MASK_EMPTY = 4

    /** `struct game_params`: two `puzzle_size` fields. */
    data class Params(val width: Int, val height: Int) : Game.Params {
        override fun copy(): Game.Params = Params(width, height)
    }

    /**
     * The immutable part of a game: the decoded clue board and its mask.
     * C keeps the description in the state's grid only; the initial grid
     * is a port shape, because `solve_game` reads `states[0]` and the
     * seam passes the current state.
     */
    class Shared internal constructor(
        val width: Int,
        val height: Int,
        /** The decoded clues; `0` is not a clue. Do not modify. */
        val clues: IntArray,
        /** True where [clues] holds a clue value. */
        val clueMask: BooleanArray,
    ) {
        val size: Int get() = width * height

        /** The grid after `newState`. Do not modify. */
        val initialGrid: IntArray get() = clues
    }

    /**
     * `struct game_state`. [grid] is copied per state, as `dup_game`
     * copies it. [wasSolved] is sticky, and an `S` move sets both
     * [wasSolved] and [hasCheated].
     */
    class State(
        val shared: Shared,
        val grid: IntArray,
        var wasSolved: Boolean,
        var hasCheated: Boolean,
    ) : Game.State {

        val width: Int get() = shared.width

        val height: Int get() = shared.height

        fun valueAt(x: Int, y: Int): Int = grid[y * width + x]

        override fun copy(): Game.State = State(shared, grid.copyOf(), wasSolved, hasCheated)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                grid.contentEquals(other.grid) &&
                wasSolved == other.wasSolved &&
                hasCheated == other.hasCheated

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + wasSolved.hashCode()
            result = 31 * result + hasCheated.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`: the cursor cell, the ring flag, and the
     * `Mouse button order` preference.
     */
    class Ui : Game.Ui {
        var r = 0
        var c = 0
        var cursorShow = false

        /**
         * `swap_buttons`: true selects `left-mouse-button=dot`, so a
         * left press cycles forwards and a right press backwards.
         * [Range.setPrefs] copies the preference here.
         */
        var swapButtons = false
    }

    /**
     * `struct game_drawstate`: one word per cell with the value and the
     * error, cursor, and flash flags. The first value is `w+h`, which no
     * real cell can hold.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
        val tileSize: Int,
    ) : Game.DrawState {
        val grid: IntArray = IntArray(width * height) { (width + height) shl VALUE_SHIFT }
    }

    override val name: String = "Range"
    override val backendName: String = "range"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    // `range.c` has no `SMALL_SCREEN` branch, so the app and the oracle
    // expose the same four presets.
    private val presetValues = listOf(
        Params(9, 6),
        Params(12, 8),
        Params(13, 9),
        Params(16, 11),
    )

    /** `default_params`: preset 0, `9x6`. */
    override fun defaultParams(): Game.Params = Params(9, 6)

    /** `game_fetch_preset`: `%d x %d` and the full parameter encoding. */
    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.width} x ${it.height}", encodeParams(it, true))
    }

    /**
     * `decode_params` from `range.c:154-164`.
     *
     * The first `atoi` value goes into a `signed char` and is the height
     * as well. The digit skip starts at the original string, so a sign
     * or a leading space hides the `x`. Only a lower-case `x` separates
     * the numbers; a second value is also truncated to a `signed char`.
     */
    override fun decodeParams(text: String): Game.Params {
        val width = atoi(text, 0).toByte().toInt()
        var height = width
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            height = atoi(text, position + 1).toByte().toInt()
        }
        return Params(width, height)
    }

    /** `encode_params`: always `WxH`; [full] is not read. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.width}x${p.height}"
    }

    /**
     * `validate_params` from `range.c:918-930`, in C order. `SCHAR_MAX`
     * bounds `width + height` at 128, so the largest accepted board is
     * `64x64`. The last message has no `Error: ` prefix.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        val w = p.width
        val h = p.height
        if (w < 1) return "Error: width is less than 1"
        if (h < 1) return "Error: height is less than 1"
        if (w > SCHAR_MAX - (h - 1)) return "Error: w + h is too big"
        if (w * h < 1) return "Error: size is less than 1"
        if (full && (w < 3 || h < 3)) {
            return "Width and height must both be at least 3"
        }
        return null
    }

    /** `game_configure` (`range.c:173-191`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
        )
    }

    /** `custom_params` (`range.c:193-199`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`range.c:1276-1288`): the mouse button order. */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                name = "Mouse button order",
                choices = ":Left to fill, right to dot:Left to dot, right to fill",
                keywords = ":fill:dot",
                selected = 0,
                kw = "left-mouse-button",
            ),
        )

    /** `set_prefs` (`range.c:1296-1299`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.swapButtons = prefs.choice("left-mouse-button") == 1
    }

    /** `new_game_desc`: fill, winnow, and retry while the winnow refuses. */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(RangeSolver.generate(p.width, p.height, random).desc)
    }

    /**
     * `new_game_desc` with the attempt count, for the generator tests.
     * A retry is visible only here, not in the description.
     */
    internal fun newDescWithAttempts(params: Game.Params, random: RandomState): RangeSolver.Generated {
        val p = params as Params
        return RangeSolver.generate(p.width, p.height, random)
    }

    /**
     * `validate_desc` from `range.c:1082-1112`. The scan stops at the
     * first comma, so a `,zzz` tail is not read and the stored
     * description keeps it. A number is checked against
     * `width + height - 1` at the point it is read.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val n = p.width * p.height
        val range = p.width + p.height - 1
        var squares = 0
        var position = 0

        while (position < desc.length && desc[position] != ',') {
            val c = desc[position++]
            when {
                c in 'a'..'z' -> squares += c - 'a' + 1
                c == '_' -> Unit
                c > '0' && c <= '9' -> {
                    val value = atoi(desc, position - 1)
                    if (value < 1 || value > range) {
                        return "Number out of range in game description"
                    }
                    squares++
                    while (position < desc.length && desc[position] in '0'..'9') position++
                }

                else -> return "Invalid character in game description"
            }
        }

        if (squares < n) return "Not enough data to fill grid"
        if (squares > n) return "Too much data to fit in grid"
        return null
    }

    /**
     * `new_game`: decode the clues. A character that is not a run, `_`,
     * or a digit is skipped. Both flags start false, so a description
     * that is already a solution starts open.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.width
        val h = p.height
        val n = w * h
        val grid = IntArray(n)
        var position = 0
        var i = 0

        while (i < n && position < desc.length) {
            val c = desc[position++]
            when {
                c in 'a'..'z' -> {
                    var squares = c - 'a' + 1
                    while (squares-- > 0) {
                        if (i < n) grid[i] = 0
                        i++
                    }
                }

                c == '_' -> Unit
                c > '0' && c <= '9' -> {
                    val value = atoi(desc, position - 1)
                    if (i < n) grid[i] = value
                    i++
                    while (position < desc.length && desc[position] in '0'..'9') position++
                }
            }
        }
        check(i == n) { "description covers $i cells, not $n" }

        val clues = grid.copyOf()
        val mask = BooleanArray(n) { clues[it] > 0 }
        return State(
            shared = Shared(w, h, clues, mask),
            grid = grid,
            wasSolved = false,
            hasCheated = false,
        )
    }

    /**
     * `solve_game` from `range.c:318-341`. C reads the first state, so
     * the port solves [Shared.initialGrid]. `auxInfo` is ignored, and a
     * valid board always yields a move string: the C error path needs a
     * reasoning that returns NULL, and none of the four can. The null
     * result is the seam's "cannot solve" contract; only a shared grid
     * that does not match its parameters reaches it, and no midend path
     * builds one.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /**
     * `solve_game` with the C error text. `Puzzle is inconsistent`
     * (`range.c:337`) belongs to the `moves == NULL` branch, which the
     * four reasonings cannot produce; the only way here is the port's
     * guard against a grid that does not match its parameters.
     */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        if (s.shared.initialGrid.size != s.width * s.height) {
            return SolveOutcome(null, "Puzzle is inconsistent")
        }
        val moves = RangeSolver.solve(
            s.shared.initialGrid,
            s.width,
            s.height,
            RangeSolver.DIFF_RECURSION,
        )
        val out = StringBuilder(1 + moves.size * 8)
        out.append('S')
        for (move in moves) {
            out.append(if (move.colour == RangeSolver.M_BLACK) 'B' else 'W')
            out.append(',').append(move.r).append(',').append(move.c)
        }
        return SolveOutcome(out.toString(), null)
    }

    /**
     * `execute_move` from `range.c:1574-1610`.
     *
     * An `S` prefix sets both flags and the rest is parsed as
     * `sscanf("%c,%d,%d%n")` tokens. A clue may be overwritten. The
     * completion test runs only when the move was not a solve.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val ret = s.copy() as State
        var position = 0
        if (position < move.length && move[position] == 'S') {
            position++
            ret.hasCheated = true
            ret.wasSolved = true
        }

        while (position < move.length) {
            val token = scanMove(move, position) ?: return null
            val value = when (token.what) {
                'W' -> WHITE
                'E' -> EMPTY
                'B' -> BLACK
                else -> return null
            }
            if (token.r < 0 || token.r >= s.height || token.c < 0 || token.c >= s.width) {
                return null
            }
            ret.grid[token.r * s.width + token.c] = value
            position = token.end
        }

        if (!ret.wasSolved) ret.wasSolved = !findErrors(ret, null)
        return ret
    }

    /** `game_status`: 1 when the game was solved, otherwise 0. */
    override fun status(state: Game.State): Int = if ((state as State).wasSolved) 1 else 0

    /** `wants_statusbar` is false, so the app shows no status text. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /**
     * `game_text_format` from `range.c:1162-1221`. `cellsize` is one
     * more than the largest digit count over the signed cell values, so
     * `BLACK` and `WHITE` count as one digit.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height

        var cellsize = 0
        for (c in 0 until w) {
            for (r in 0 until h) {
                var k = s.grid[r * w + c]
                var digits = 0
                while (k != 0) {
                    k /= 10
                    digits++
                }
                if (digits > cellsize) cellsize = digits
            }
        }
        cellsize++

        val wString = w * cellsize + 2
        val out = StringBuilder(wString * (2 * h + 1))
        val gridline = CharArray(wString) { '-' }
        for (c in 0..w) gridline[c * cellsize] = '+'
        gridline[wString - 1] = '\n'

        for (r in 0 until h) {
            out.append(gridline)
            for (c in 0 until w) {
                val value = s.grid[r * w + c]
                out.append('|')
                when (value) {
                    BLACK -> repeatTo(out, '#', cellsize - 1)
                    WHITE -> repeatTo(out, '.', cellsize - 1)
                    EMPTY -> repeatTo(out, ' ', cellsize - 1)
                    else -> out.append(value.toString().padStart(cellsize - 1))
                }
            }
            out.append("|\n")
        }
        out.append(gridline)
        return out.toString()
    }

    /**
     * `game_changed_state`: an honest completion raises the event. A
     * solve is cheated and raises nothing; an undo into an open state
     * raises nothing. A redo into the solved state raises it again.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return next.wasSolved && !next.hasCheated &&
            oldState != null && !(oldState as State).wasSolved
    }

    /**
     * `game_request_keys` is NULL in C, so the midend reports no labels
     * and keeps the default arrow mode `ARROWS_LEFT_RIGHT`.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /** `game_compute_size`: one tile of margin on every axis. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size((1 + p.width) * tileSize, (1 + p.height) * tileSize)
    }

    /** C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorShow = visible
    }

    /** `game_get_cursor_location`: a rectangle only for a visible cursor. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorShow) return null
        return Game.CursorRect(
            tileSize / 2 + tileSize * u.c,
            tileSize / 2 + tileSize * u.r,
            tileSize,
            tileSize,
        )
    }

    /**
     * `current_key_label` from `range.c:1311-1330`. The grid is read
     * before the hidden-cursor test, as C does; the bounds guard covers
     * a caller that sets [Ui] fields directly and is unreachable through
     * the midend. `swap_buttons` is not consulted, so the labels do not
     * change with the preference.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) return ""
        val index = u.r * s.width + u.c
        if (index < 0 || index >= s.grid.size) return ""
        val cell = s.grid[index]
        if (!u.cursorShow || cell > 0) return ""
        return when (cell) {
            EMPTY -> if (button == Buttons.CURSOR_SELECT) "Fill" else "Dot"
            WHITE -> if (button == Buttons.CURSOR_SELECT) "Empty" else "Fill"
            BLACK -> if (button == Buttons.CURSOR_SELECT) "Dot" else "Empty"
            else -> ""
        }
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours` from `range.c:1686-1696`. The lowlight comes from
     * `game_mkhighlight` with no highlight, so it is derived from the
     * shell's background and must not be a literal.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]

        Colour.mkHighlight(ret, COL_BACKGROUND, -1, COL_LOWLIGHT)

        for (i in 0..2) {
            ret[COL_GRID * 3 + i] = 0.0f
            ret[COL_ERROR * 3 + i] = 0.0f
        }
        ret[COL_ERROR * 3] = 1.0f

        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height, tileSize)
    }

    /**
     * `game_redraw` from `range.c:1744-1773`. The error flags are rebuilt
     * from the whole grid on every frame, and the per-cell word cache
     * suppresses a cell whose value and flags did not change, so a warm
     * frame emits nothing. The seam has no flash time, so `flash` is 0.
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

        val errors = BooleanArray(w * h)
        findErrors(s, errors)

        var i = 0
        for (r in 0 until h) {
            for (c in 0 until w) {
                var word = s.grid[i] shl VALUE_SHIFT
                if (errors[i]) word = word or FLAG_ERROR
                if (r == u.r && c == u.c && u.cursorShow) word = word or FLAG_CURSOR
                if (ds.grid[i] != word) {
                    ds.grid[i] = word
                    drawCell(sink, ds, r, c, word)
                }
                i++
            }
        }
    }

    /** `draw_cell` from `range.c:1775-1808`, in C call order. */
    private fun drawCell(sink: DrawSink, ds: DrawState, r: Int, c: Int, word: Int) {
        val ts = ds.tileSize
        val value = word shr VALUE_SHIFT
        val error = word and FLAG_ERROR != 0
        val cursor = word and FLAG_CURSOR != 0
        val flash = false

        val y = ts / 2 + ts * r
        val x = ts / 2 + ts * c
        val tx = x + ts / 2
        val ty = y + ts / 2
        val dotsz = (ts + 9) / 10

        val colour = when {
            value == BLACK -> if (error) COL_ERROR else COL_BLACK
            flash || cursor -> COL_LOWLIGHT
            else -> COL_BACKGROUND
        }

        drawRectOutline(sink, x, y, ts + 1, ts + 1, COL_GRID)
        sink.rect(x + 1, y + 1, ts - 1, ts - 1, colour)
        if (error) drawRectOutline(sink, x + 1, y + 1, ts - 1, ts - 1, COL_ERROR)

        when (value) {
            WHITE -> sink.rect(
                tx - dotsz / 2,
                ty - dotsz / 2,
                dotsz,
                dotsz,
                if (error) COL_ERROR else COL_USER,
            )

            BLACK, EMPTY -> Unit
            else -> sink.text(
                tx,
                ty,
                Text.FONT_VARIABLE,
                ts * 3 / 5,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                if (error) COL_ERROR else COL_GRID,
                value.toString(),
            )
        }

        sink.update(x, y, ts + 1, ts + 1)
    }

    /** `draw_rect_outline`: a four-point polygon with no fill. */
    private fun drawRectOutline(
        sink: DrawSink,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colour: Int,
    ) {
        val x0 = x
        val x1 = x + width - 1
        val y0 = y
        val y1 = y + height - 1
        sink.poly(intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0), -1, colour)
    }

    /**
     * `interpret_move` from `range.c:1347-1463`, in C branch order.
     *
     * Only `SHFT` is stripped. A press moves the cursor and hides the
     * ring before any clue test, so a clue, border, or middle click
     * returns [MoveResult.Unused] with the cursor already moved.
     *
     * C has no `MOVE_NO_EFFECT`; the mapping is a move string to
     * [MoveResult.Move], `MOVE_UI_UPDATE` to [MoveResult.UiUpdate], and
     * `NULL` to [MoveResult.Unused].
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
        var key = button and Buttons.MOD_SHFT.inv()

        if (isCursorSelect(key) && !u.cursorShow) return MoveResult.Unused

        var r = u.r
        var c = u.c

        // Mouse press. A drag or a release never reaches this branch.
        if (Buttons.isMouseDown(key)) {
            r = fromCoord(y + tileSize, tileSize) - 1
            c = fromCoord(x + tileSize, tileSize) - 1
            if (r < 0 || r >= h || c < 0 || c >= w) return MoveResult.Unused
            u.r = r
            u.c = c
            u.cursorShow = false
        }

        if (key == Buttons.LEFT_BUTTON || key == Buttons.RIGHT_BUTTON) {
            if (u.swapButtons) {
                key = if (key == Buttons.LEFT_BUTTON) {
                    Buttons.RIGHT_BUTTON
                } else {
                    Buttons.LEFT_BUTTON
                }
            }
        }

        var action = ACTION_NONE
        when (key) {
            Buttons.CURSOR_SELECT, Buttons.LEFT_BUTTON -> action = ACTION_BACKWARDS
            Buttons.CURSOR_SELECT2, Buttons.RIGHT_BUTTON -> action = ACTION_FORWARDS
            'h'.code, 'H'.code -> action = ACTION_HINT
            Buttons.CURSOR_UP, Buttons.CURSOR_DOWN,
            Buttons.CURSOR_LEFT, Buttons.CURSOR_RIGHT,
            -> {
                if (u.cursorShow) {
                    val i = cursorIndex(key)
                    if (shift) {
                        val preR = r
                        val preC = c
                        val cell = cellOrNull(s, r, c)
                        val doPre = cell == EMPTY
                        if (r + DR[i] < 0 || r + DR[i] >= h ||
                            c + DC[i] < 0 || c + DC[i] >= w
                        ) {
                            return if (doPre) {
                                MoveResult.Move("W,$preR,$preC")
                            } else {
                                MoveResult.Unused
                            }
                        }
                        u.r += DR[i]
                        u.c += DC[i]

                        val post = cellOrNull(s, u.r, u.c)
                        val doPost = post == EMPTY
                        return when {
                            doPre && doPost ->
                                MoveResult.Move("W,$preR,${preC}W,${u.r},${u.c}")

                            doPre -> MoveResult.Move("W,$preR,$preC")
                            doPost -> MoveResult.Move("W,${u.r},${u.c}")
                            else -> MoveResult.UiUpdate
                        }
                    } else if (r + DR[i] >= 0 && r + DR[i] < h &&
                        c + DC[i] >= 0 && c + DC[i] < w
                    ) {
                        u.r += DR[i]
                        u.c += DC[i]
                    }
                } else {
                    u.cursorShow = true
                }
                return MoveResult.UiUpdate
            }
        }

        if (action == ACTION_HINT) {
            val moves = RangeSolver.solve(
                s.grid,
                w,
                h,
                RangeSolver.DIFF_RECURSION,
            )
            if (moves.isNotEmpty()) {
                val move = moves[0]
                val colour = if (move.colour == RangeSolver.M_BLACK) 'B' else 'W'
                return MoveResult.Move("$colour,${move.r},${move.c}")
            }
            return MoveResult.Unused
        }

        val cell = cellOrNull(s, r, c) ?: return MoveResult.Unused
        if (cell > 0) return MoveResult.Unused

        if (action == ACTION_FORWARDS) {
            when (cell) {
                EMPTY -> return MoveResult.Move("W,$r,$c")
                WHITE -> return MoveResult.Move("B,$r,$c")
                BLACK -> return MoveResult.Move("E,$r,$c")
            }
        } else if (action == ACTION_BACKWARDS) {
            when (cell) {
                BLACK -> return MoveResult.Move("W,$r,$c")
                WHITE -> return MoveResult.Move("E,$r,$c")
                EMPTY -> return MoveResult.Move("B,$r,$c")
            }
        }

        return MoveResult.Unused
    }

    private const val ACTION_NONE = 0
    private const val ACTION_FORWARDS = 1
    private const val ACTION_BACKWARDS = 2
    private const val ACTION_HINT = 3

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell. A clue cell speaks its number; a play cell
     * speaks `black`, `white`, or `empty` and offers both pointer
     * buttons, which are the game's own two cycles: the left button
     * cycles backwards, the right button forwards, and the `Mouse button
     * order` preference swaps them. A clue ignores both buttons.
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
        val errors = BooleanArray(w * s.height)
        findErrors(s, errors)
        val cells = gridSpeech(
            width = w,
            height = s.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * w + x
                val clue = s.shared.clues[index]
                val content = when {
                    clue > 0 -> "clue $clue"
                    s.grid[index] == BLACK -> "black"
                    s.grid[index] == WHITE -> "white"
                    else -> "empty"
                }
                val cursor = if (u.cursorShow && u.c == x && u.r == y) ", cursor" else ""
                val wrong = if (errors[index]) SPOKEN_WRONG else ""
                content + cursor + wrong
            },
            actions = { x, y -> pointer(s, u, x, y) },
        )
        return cells.capped(cells.units.getOrNull(u.r * w + u.c))
    }

    /**
     * The two pointer buttons of one play cell, in the words of the
     * direction each one cycles. `swapButtons` is the game's own
     * preference, so the labels follow it.
     */
    private fun pointer(s: State, u: Ui, x: Int, y: Int): List<SpokenAction> {
        val index = y * s.width + x
        if (s.shared.clues[index] > 0) return emptyList()
        val back = backwards(s.grid[index])
        val forward = forwards(s.grid[index])
        return if (u.swapButtons) {
            listOf(
                SpokenAction(forward, Buttons.LEFT_BUTTON),
                SpokenAction(back, Buttons.RIGHT_BUTTON),
            )
        } else {
            listOf(
                SpokenAction(back, Buttons.LEFT_BUTTON),
                SpokenAction(forward, Buttons.RIGHT_BUTTON),
            )
        }
    }

    /** The word of the cell a backwards click produces. */
    private fun backwards(value: Int): String = when (value) {
        BLACK -> "White"
        WHITE -> "Empty"
        else -> "Black"
    }

    /** The word of the cell a forwards click produces. */
    private fun forwards(value: Int): String = when (value) {
        BLACK -> "Empty"
        WHITE -> "Black"
        else -> "White"
    }

    private val DR = intArrayOf(1, 0, -1, 0)
    private val DC = intArrayOf(0, 1, 0, -1)

    /** `cursors[4]` from `range.c:290-291`, in the same order as DR/DC. */
    private val CURSORS = intArrayOf(
        Buttons.CURSOR_DOWN,
        Buttons.CURSOR_RIGHT,
        Buttons.CURSOR_UP,
        Buttons.CURSOR_LEFT,
    )

    private fun cursorIndex(button: Int): Int {
        for (i in 0..3) if (CURSORS[i] == button) return i
        return 0
    }

    private fun isCursorSelect(button: Int): Boolean =
        button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2

    /** `FROMCOORD`, with C's truncation toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2) / tileSize

    private fun cellOrNull(state: State, r: Int, c: Int): Int? {
        if (r < 0 || r >= state.height || c < 0 || c >= state.width) return null
        return state.grid[r * state.width + c]
    }

    /**
     * `find_errors` from `range.c:1465-1572`. With [report] null it
     * answers "is this board complete"; otherwise it marks the cells
     * that show an error. The `dup_game` in C is allocated and never
     * read, so the port drops it.
     */
    private fun findErrors(state: State, report: BooleanArray?): Boolean {
        val w = state.width
        val h = state.height
        val n = w * h
        var blackCells = 0
        var anyWhiteCell = -1

        for (r in 0 until h) {
            for (c in 0 until w) {
                val i = r * w + c
                val cell = state.grid[i]
                if (cell == BLACK) {
                    blackCells++
                    for (j in 0..3) {
                        val rr = r + DR[j]
                        val cc = c + DC[j]
                        if (rr < 0 || rr >= h || cc < 0 || cc >= w) continue
                        if (state.grid[rr * w + cc] != BLACK) continue
                        if (report == null) return true
                        report[i] = true
                        break
                    }
                } else if (cell == EMPTY || cell == WHITE) {
                    anyWhiteCell = i
                } else {
                    var runs = 1
                    for (j in 0..3) {
                        runs += runLength(
                            r + DR[j],
                            c + DC[j],
                            DR[j],
                            DC[j],
                            state,
                            MASK_BLACK.inv(),
                        )
                    }
                    if (report == null) {
                        if (runs != cell) return true
                    } else if (runs < cell) {
                        report[i] = true
                    } else {
                        runs = 1
                        for (j in 0..3) {
                            runs += runLength(
                                r + DR[j],
                                c + DC[j],
                                DR[j],
                                DC[j],
                                state,
                                (MASK_BLACK or MASK_EMPTY).inv(),
                            )
                        }
                        if (runs > cell) report[i] = true
                    }
                    anyWhiteCell = i
                }
            }
        }

        val dsf = Dsf.new(n)
        for (r in 0 until h - 1) {
            for (c in 0 until w) {
                if (state.grid[r * w + c] != BLACK &&
                    state.grid[(r + 1) * w + c] != BLACK
                ) {
                    dsf.merge(r * w + c, (r + 1) * w + c)
                }
            }
        }
        for (r in 0 until h) {
            for (c in 0 until w - 1) {
                if (state.grid[r * w + c] != BLACK &&
                    state.grid[r * w + c + 1] != BLACK
                ) {
                    dsf.merge(r * w + c, r * w + c + 1)
                }
            }
        }

        if (anyWhiteCell != -1 && blackCells + dsf.size(anyWhiteCell) < n) {
            if (report == null) return true

            var canonical = -1
            var biggest = 0
            for (i in 0 until n) {
                if (state.grid[i] != BLACK) {
                    val size = dsf.size(i)
                    if (size > biggest) {
                        biggest = size
                        canonical = dsf.canonify(i)
                    }
                }
            }
            for (i in 0 until n) {
                if (state.grid[i] != BLACK && dsf.canonify(i) != canonical) {
                    report[i] = true
                }
            }
        }

        return false
    }

    /**
     * `runlength` from `range.c:393-411`, also used by `find_errors`.
     * A clue cell counts as white unless the mask excludes the cell
     * values.
     */
    private fun runLength(
        r0: Int,
        c0: Int,
        dr: Int,
        dc: Int,
        state: State,
        colourMask: Int,
    ): Int {
        var r = r0
        var c = c0
        var size = 0
        while (true) {
            if (r < 0 || r >= state.height || c < 0 || c >= state.width) break
            val cell = state.grid[r * state.width + c]
            if (cell > 0) {
                if ((colourMask and 7.inv()) == 0) break
            } else if (((1 shl (cell + 2)) and colourMask) == 0) {
                break
            }
            size++
            r += dr
            c += dc
        }
        return size
    }

    private fun repeatTo(out: StringBuilder, c: Char, count: Int) {
        for (i in 0 until count) out.append(c)
    }

    private const val SCHAR_MAX = 127

    private class ScannedMove(
        val what: Char,
        val r: Int,
        val c: Int,
        val end: Int,
    )

    /**
     * One `sscanf("%c,%d,%d%n")` conversion. `%c` takes one character,
     * the commas are literal, and `%d` reads C white space, a sign, and
     * digits. A value that does not fit in a `long` saturates and the
     * assignment keeps the low 32 bits, as glibc does.
     */
    private fun scanMove(text: String, start: Int): ScannedMove? {
        var i = start
        if (i >= text.length) return null
        val what = text[i]
        i++
        if (i >= text.length || text[i] != ',') return null
        i++
        val r = scanInt(text, i) ?: return null
        i = r.end
        if (i >= text.length || text[i] != ',') return null
        i++
        val c = scanInt(text, i) ?: return null
        return ScannedMove(what, r.value, c.value, c.end)
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
