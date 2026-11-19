package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `flood.c`.
 *
 * The rules are: each move floods the connected top-left region with one
 * colour, the goal is a uniform grid, and the move limit is the greedy
 * solver's own move count plus the leniency. A move is one `M<colour>`
 * string. A solve is one `S<colour>,<colour>,...` string that stores the
 * path and marks the game as cheated; it does not change the grid.
 *
 * The description generator and the solver are in [FloodSolver]. This
 * file holds the parameters, the state, the moves, the status text, the
 * palette, and the drawing.
 *
 * This game uses [Game.NoDrawState], so [redraw] draws the whole
 * board, including the two recessed polygons, on every call. C draws
 * them once per drawstate. The completion flash and the defeat flash are
 * not ported: they do not change the state or the save file, and
 * `docs/PARITY.md` allows animation differences.
 */
object Flood : Game {

    private const val PREFERRED_TILE_SIZE = 32

    /** `MAXCOLOURS` from `flood.c`: the number of tile colours. */
    private const val MAXCOLOURS = 10

    private const val COL_BACKGROUND = 0
    private const val COL_SEPARATOR = 1
    private const val COL_1 = 2
    private const val COL_10 = 11
    private const val COL_HIGHLIGHT = 12
    private const val COL_LOWLIGHT = 13
    private const val NCOLOURS = 14

    /**
     * The spoken name of tile colour 0..9, in the palette order of
     * `game_colours`. D-01 section 8: one vocabulary for every game.
     */
    private val SPOKEN_COLOURS = listOf(
        BoardColour.RED,
        BoardColour.YELLOW,
        BoardColour.GREEN,
        BoardColour.BLUE,
        BoardColour.ORANGE,
        BoardColour.PURPLE,
        BoardColour.BROWN,
        BoardColour.LIGHT_BLUE,
        BoardColour.LIGHT_GREEN,
        BoardColour.PINK,
    )

    private const val BORDER_L = 0x001
    private const val BORDER_R = 0x002
    private const val BORDER_U = 0x004
    private const val BORDER_D = 0x008
    private const val CORNER_UL = 0x010
    private const val CORNER_UR = 0x020
    private const val CORNER_DL = 0x040
    private const val CORNER_DR = 0x080
    private const val CURSOR = 0x100
    private const val SOLNNEXT = 0x400
    private const val COLOUR_SHIFT = 11

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val colours: Int,
        val leniency: Int,
    ) : Game.Params {

        val fullText: String get() = encodeParams(this, true)

        val shortText: String get() = encodeParams(this, false)

        override fun copy(): Game.Params = Params(width, height, colours, leniency)
    }

    /** `struct soln`. The move list is immutable and shared by copies. */
    data class Soln(val moves: List<Int>) {
        val nmoves: Int get() = moves.size
    }

    /**
     * `struct game_state`. `colours` is `max(grid) + 1` from the
     * description, not the parameter, because a description can use
     * fewer colours than the parameters allow.
     */
    class State(
        val width: Int,
        val height: Int,
        val grid: IntArray,
        val colours: Int,
        var moves: Int,
        val movelimit: Int,
        var complete: Boolean,
        var cheated: Boolean,
        var soln: Soln?,
        var solnpos: Int,
    ) : Game.State {

        val n: Int get() = width * height

        fun colourAt(x: Int, y: Int): Int = grid[y * width + x]

        override fun copy(): Game.State = State(
            width,
            height,
            grid.copyOf(),
            colours,
            moves,
            movelimit,
            complete,
            cheated,
            soln,
            solnpos,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                grid.contentEquals(other.grid) &&
                colours == other.colours &&
                moves == other.moves &&
                movelimit == other.movelimit &&
                complete == other.complete &&
                cheated == other.cheated &&
                soln == other.soln &&
                solnpos == other.solnpos

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + colours
            result = 31 * result + moves
            result = 31 * result + movelimit
            result = 31 * result + complete.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + (soln?.hashCode() ?: 0)
            result = 31 * result + solnpos
            return result
        }
    }

    /**
     * `struct game_ui`. C also has `flash_type`; it is only read while a
     * flash runs, and the seam has no flash time, so it is not ported.
     */
    class Ui : Game.Ui {
        var cursorVisible = false
        var cx = 0
        var cy = 0
    }

    override val name: String = "Flood"
    override val backendName: String = "flood"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    // `flood.c` has no `SMALL_SCREEN` or `SLOW_SYSTEM` conditional, so
    // the Android build and the oracle expose the same seven presets.
    private val presetValues = listOf(
        Params(12, 12, 6, 5) to "12x12 Easy",
        Params(12, 12, 6, 2) to "12x12 Medium",
        Params(12, 12, 6, 0) to "12x12 Hard",
        Params(16, 16, 6, 2) to "16x16 Medium",
        Params(16, 16, 6, 0) to "16x16 Hard",
        Params(12, 12, 3, 0) to "12x12, 3 colours",
        Params(12, 12, 4, 0) to "12x12, 4 colours",
    )

    override fun defaultParams(): Game.Params = Params(12, 12, 6, 5)

    override fun presets(): List<Preset> = presetValues.map { (params, title) ->
        Preset(title, params.fullText)
    }

    /**
     * `decode_params` writes into an existing structure. Every C caller
     * starts from the defaults, so the short form inherits colours 6 and
     * leniency 5. The digit cursor starts at the beginning of the string,
     * so a sign or space is then skipped one character at a time.
     */
    override fun decodeParams(text: String): Game.Params {
        var p = 0
        val width = atoi(text, p)
        var height = width
        while (p < text.length && text[p] in '0'..'9') p++
        if (p < text.length && text[p] == 'x') {
            p++
            height = atoi(text, p)
            while (p < text.length && text[p] in '0'..'9') p++
        }
        var colours = 6
        var leniency = 5
        while (p < text.length) {
            when (text[p]) {
                'c' -> {
                    p++
                    colours = atoi(text, p)
                    while (p < text.length && text[p] in '0'..'9') p++
                }

                'm' -> {
                    p++
                    leniency = atoi(text, p)
                    while (p < text.length && text[p] in '0'..'9') p++
                }

                else -> p++
            }
        }
        return Params(width, height, colours, leniency)
    }

    /** `encode_params`: `%dx%d`, and `c%dm%d` only when [full]. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val base = "${p.width}x${p.height}"
        return if (full) "${base}c${p.colours}m${p.leniency}" else base
    }

    /**
     * `validate_params`. The first test evaluates `w*h` before the
     * overflow guard, so the third message is reachable only through
     * wrapping multiplication. Kotlin `Int` wraps the same way, and the
     * test feeds the same case as the oracle.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width * p.height < 2) {
            return "Grid must contain at least two squares"
        }
        if (p.width < 1 || p.height < 1) {
            return "Width and height must be at least one"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.colours < 3 || p.colours > MAXCOLOURS) {
            return "Must have between 3 and $MAXCOLOURS colours"
        }
        if (p.leniency < 0) {
            return "Leniency must be non-negative"
        }
        return null
    }

    /** `game_configure` (`flood.c:173-204`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Text("Colours", p.colours.toString()),
            ConfigItem.Text("Extra moves permitted", p.leniency.toString()),
        )
    }

    /** `custom_params` (`flood.c:206-216`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
            atoi((items[3] as ConfigItem.Text).value, 0),
        )

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(
            FloodSolver.newDesc(p.width, p.height, p.colours, p.leniency, random),
        )
    }

    /** `validate_desc`: exactly `w*h` grid characters, a comma, then digits. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val wh = p.width * p.height
        var position = 0

        for (i in 0 until wh) {
            if (position >= desc.length || desc[position] == '\u0000') {
                return "Not enough data in grid description"
            }
            val raw = desc[position++]
            val value = when {
                raw in '0'..'9' -> raw - '0'
                raw in 'A'..'Z' -> 10 + (raw - 'A')
                else -> return "Bad character in grid description"
            }
            if (value !in 0 until MAXCOLOURS) {
                return "Colour out of range in grid description"
            }
        }

        if (position >= desc.length || desc[position] != ',') {
            return "Expected ',' after grid description"
        }
        position++
        var end = position
        while (end < desc.length && desc[end] in '0'..'9') end++
        if (end != desc.length) {
            return "Badly formatted move limit after grid description"
        }
        return null
    }

    /** `new_game`: the grid, the limit, and no computed `complete` flag. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.width * p.height
        val grid = IntArray(wh)
        var position = 0
        var colours = 0

        for (i in 0 until wh) {
            check(position < desc.length) { "description is shorter than the grid" }
            val raw = desc[position++]
            val value = when {
                raw in '0'..'9' -> raw - '0'
                raw in 'A'..'Z' -> 10 + (raw - 'A')
                else -> error("bad colour in description")
            }
            check(value in 0 until MAXCOLOURS) { "colour out of range in description" }
            grid[i] = value
            if (value >= colours) colours = value + 1
        }
        check(position < desc.length && desc[position] == ',') {
            "expected ',' after the grid"
        }
        position++

        return State(
            width = p.width,
            height = p.height,
            grid = grid,
            colours = colours,
            moves = 0,
            movelimit = atoi(desc, position),
            complete = false,
            cheated = false,
            soln = null,
            solnpos = 0,
        )
    }

    /**
     * `solve_game`. The alphabet is the state's colour count, not the
     * parameter, and a completed state has no solution. The result is
     * the move string only; executing it as an `S` move sets `cheated`.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /** `solve_game` (`flood.c:704-725`): a complete board is an error. */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        if (s.complete) return SolveOutcome(null, "Puzzle is already solved")
        return SolveOutcome(FloodSolver.solve(s.width, s.height, s.grid, s.colours), null)
    }

    /**
     * `execute_move`. An `M` move is accepted with a leading integer
     * only, so `M2x` and `M 2` are valid. An `S` move stores a solution
     * path without changing the grid, and the parser follows `atoi`
     * token by token, including its empty-token behaviour.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State

        if (move.isNotEmpty() && move[0] == 'M') {
            val colour = parseIntPrefix(move, 1) ?: return null
            if (colour < 0 || colour >= s.colours ||
                colour == s.grid[0] || s.complete
            ) {
                return null
            }
            val next = s.copy() as State
            fill(next.width, next.height, next.grid, colour, IntArray(s.n))
            next.moves++
            next.complete = completed(next.width, next.height, next.grid)

            val soln = next.soln
            if (soln != null) {
                if (colour == soln.moves[next.solnpos] && next.solnpos + 1 < soln.nmoves) {
                    next.solnpos++
                } else {
                    next.soln = null
                    next.solnpos = 0
                }
            }
            return next
        }

        if (move.isNotEmpty() && move[0] == 'S') {
            var nmoves = 1
            for (i in 1 until move.length) {
                if (move[i] == ',') nmoves++
            }
            val moves = ArrayList<Int>(nmoves)
            var position = 1
            for (i in 0 until nmoves) {
                if (position >= move.length) return null
                val colour = atoi(move, position)
                if (colour < 0 || colour >= s.colours ||
                    (if (i == 0) colour == s.grid[0] else colour == moves[i - 1])
                ) {
                    return null
                }
                moves.add(colour)
                while (position < move.length && move[position] in '0'..'9') position++
                if (position < move.length) {
                    if (move[position] != ',') return null
                    position++
                }
            }

            val next = s.copy() as State
            next.cheated = true
            next.soln = Soln(moves)
            next.solnpos = 0
            return next
        }

        return null
    }

    override fun status(state: Game.State): Int {
        val s = state as State
        return when {
            s.complete && s.moves <= s.movelimit -> 1
            s.moves >= s.movelimit -> -1
            else -> 0
        }
    }

    /**
     * `game_status` in words. The prefix order is the C order: a
     * completed cheated game reads "Auto-solved.", a completed honest
     * game reads "COMPLETED!", and the limit test comes before the
     * cheated-only text.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val prefix = when {
            s.complete && s.moves <= s.movelimit ->
                if (s.cheated) "Auto-solved. " else "COMPLETED! "

            s.moves >= s.movelimit -> "FAILED! "
            s.cheated -> "Auto-solver used. "
            else -> ""
        }
        return "$prefix${s.moves} / ${s.movelimit} moves"
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val out = StringBuilder(s.height * (s.width + 1))
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                out.append(colourDigit(s.grid[y * s.width + x]))
            }
            out.append('\n')
        }
        return out.toString()
    }

    /** `game_changed_state`: only an honest completion is reported. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return next.complete && !next.cheated &&
            oldState != null && !(oldState as State).complete
    }

    /** `game_request_keys`: no extra labels, Android arrow-left mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 2
        return Size(
            border * 2 + tileSize * p.width,
            border * 2 + tileSize * p.height,
        )
    }

    /** C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorVisible = visible
    }

    /** `game_get_cursor_location`: only a visible cursor has a rectangle. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorVisible) return null
        val border = tileSize / 2
        return Game.CursorRect(
            u.cx * tileSize + border,
            u.cy * tileSize + border,
            tileSize,
            tileSize,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        setColour(ret, COL_SEPARATOR, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_1, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_1 + 1, 1.0f, 1.0f, 0.0f)
        setColour(ret, COL_1 + 2, 0.0f, 1.0f, 0.0f)
        setColour(ret, COL_1 + 3, 0.2f, 0.3f, 1.0f)
        setColour(ret, COL_1 + 4, 1.0f, 0.5f, 0.0f)
        setColour(ret, COL_1 + 5, 0.5f, 0.0f, 0.7f)
        setColour(ret, COL_1 + 6, 0.5f, 0.3f, 0.3f)
        setColour(ret, COL_1 + 7, 0.4f, 0.8f, 1.0f)
        setColour(ret, COL_1 + 8, 0.7f, 1.0f, 0.7f)
        setColour(ret, COL_10, 1.0f, 0.6f, 1.0f)
        return ret
    }

    private fun setColour(out: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        out[index * 3] = r
        out[index * 3 + 1] = g
        out[index * 3 + 2] = b
    }

    override fun redraw(
        state: Game.State,
        ui: Game.Ui,
        drawState: Game.DrawState,
        sink: DrawSink,
        tileSize: Int,
    ) {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val h = s.height
        val wh = w * h
        val border = tileSize / 2
        val sepWidth = tileSize / 32
        val cursorInset = tileSize / 8
        val highlightWidth = tileSize / 10

        fun coord(value: Int): Int = value * tileSize + border

        /*
         * The recessed area. C draws it once per drawstate; this game
         * uses [Game.NoDrawState], so draw it on every redraw. The trace
         * is always a first redraw, so the order and the coordinates
         * match.
         */
        val coords = IntArray(10)
        coords[0] = coord(w) + highlightWidth - 1
        coords[1] = coord(h) + highlightWidth - 1
        coords[2] = coords[0]
        coords[3] = coord(0) - highlightWidth
        coords[4] = coords[2] - tileSize
        coords[5] = coords[3] + tileSize
        coords[8] = coord(0) - highlightWidth
        coords[9] = coord(h) + highlightWidth - 1
        coords[6] = coords[8] + tileSize
        coords[7] = coords[9] - tileSize
        sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)

        coords[1] = coord(0) - highlightWidth
        coords[0] = coord(0) - highlightWidth
        sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)

        sink.rect(
            coord(0) - sepWidth,
            coord(0) - sepWidth,
            tileSize * w + 2 * sepWidth,
            tileSize * h + 2 * sepWidth,
            COL_SEPARATOR,
        )

        /*
         * The solver preview. Enact the stored move, then flood with a
         * sentinel colour, then restore the sentinel cells that were not
         * the stored move. The remaining sentinel cells are the squares
         * the move would capture.
         */
        val grid = s.grid.copyOf()
        var solnmove = 0
        val soln = s.soln
        if (soln != null && s.solnpos < soln.nmoves) {
            solnmove = soln.moves[s.solnpos]
            val queue = IntArray(wh)
            fill(w, h, grid, solnmove, queue)
            fill(w, h, grid, s.colours, queue)
            for (i in 0 until wh) {
                if (grid[i] == s.colours && s.grid[i] != solnmove) {
                    grid[i] = s.grid[i]
                }
            }
        }

        // C loops x outer and y inner, so the trace order is column by
        // column.
        for (x in 0 until w) {
            for (y in 0 until h) {
                val pos = y * w + x
                var tile = if (grid[pos] == s.colours) {
                    (solnmove shl COLOUR_SHIFT) or SOLNNEXT
                } else {
                    grid[pos] shl COLOUR_SHIFT
                }

                if (x == 0 || grid[pos - 1] != grid[pos]) tile = tile or BORDER_L
                if (x == w - 1 || grid[pos + 1] != grid[pos]) tile = tile or BORDER_R
                if (y == 0 || grid[pos - w] != grid[pos]) tile = tile or BORDER_U
                if (y == h - 1 || grid[pos + w] != grid[pos]) tile = tile or BORDER_D
                if (x == 0 || y == 0 || grid[pos - w - 1] != grid[pos]) {
                    tile = tile or CORNER_UL
                }
                if (x == w - 1 || y == 0 || grid[pos - w + 1] != grid[pos]) {
                    tile = tile or CORNER_UR
                }
                if (x == 0 || y == h - 1 || grid[pos + w - 1] != grid[pos]) {
                    tile = tile or CORNER_DL
                }
                if (x == w - 1 || y == h - 1 || grid[pos + w + 1] != grid[pos]) {
                    tile = tile or CORNER_DR
                }
                if (u.cursorVisible && u.cx == x && u.cy == y) tile = tile or CURSOR

                drawTile(sink, coord(x), coord(y), tile, tileSize, sepWidth, cursorInset)
            }
        }
    }

    private fun drawTile(
        sink: DrawSink,
        tx: Int,
        ty: Int,
        tile: Int,
        tileSize: Int,
        sepWidth: Int,
        cursorInset: Int,
    ) {
        val colour = (tile shr COLOUR_SHIFT) + COL_1
        sink.rect(tx, ty, tileSize, tileSize, colour)

        if (sepWidth > 0) {
            if (tile and BORDER_L != 0) {
                sink.rect(tx, ty, sepWidth, tileSize, COL_SEPARATOR)
            }
            if (tile and BORDER_R != 0) {
                sink.rect(tx + tileSize - sepWidth, ty, sepWidth, tileSize, COL_SEPARATOR)
            }
            if (tile and BORDER_U != 0) {
                sink.rect(tx, ty, tileSize, sepWidth, COL_SEPARATOR)
            }
            if (tile and BORDER_D != 0) {
                sink.rect(tx, ty + tileSize - sepWidth, tileSize, sepWidth, COL_SEPARATOR)
            }

            if (tile and CORNER_UL != 0) {
                sink.rect(tx, ty, sepWidth, sepWidth, COL_SEPARATOR)
            }
            if (tile and CORNER_UR != 0) {
                sink.rect(tx + tileSize - sepWidth, ty, sepWidth, sepWidth, COL_SEPARATOR)
            }
            if (tile and CORNER_DL != 0) {
                sink.rect(tx, ty + tileSize - sepWidth, sepWidth, sepWidth, COL_SEPARATOR)
            }
            if (tile and CORNER_DR != 0) {
                sink.rect(
                    tx + tileSize - sepWidth,
                    ty + tileSize - sepWidth,
                    sepWidth,
                    sepWidth,
                    COL_SEPARATOR,
                )
            }
        }

        if (tile and CURSOR != 0) {
            // `draw_rect_outline`: a four-point polygon with no fill.
            val x0 = tx + cursorInset
            val x1 = x0 + (tileSize - 1 - cursorInset * 2) - 1
            val y0 = ty + cursorInset
            val y1 = y0 + (tileSize - 1 - cursorInset * 2) - 1
            sink.poly(
                intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0),
                -1,
                COL_SEPARATOR,
            )
        }

        if (tile and SOLNNEXT != 0) {
            sink.circle(
                tx + tileSize / 2,
                ty + tileSize / 2,
                tileSize / 6,
                COL_SEPARATOR,
                COL_SEPARATOR,
            )
        }

        sink.update(tx, ty, tileSize, tileSize)
    }

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
        var tx = -1
        var ty = -1
        var move = -1
        var nullret: MoveResult = MoveResult.NoEffect

        /*
         * C compares the exact button, so a modified cursor move is not
         * a cursor move. The midend keeps CTRL and SHFT on a cursor key,
         * so it falls through to `Unused` here, as in `flood.c`.
         */
        when (button) {
            Buttons.LEFT_BUTTON -> {
                tx = fromCoord(x, tileSize)
                ty = fromCoord(y, tileSize)
                if (u.cursorVisible) {
                    u.cursorVisible = false
                    nullret = MoveResult.UiUpdate
                }
            }

            Buttons.CURSOR_UP, Buttons.CURSOR_DOWN,
            Buttons.CURSOR_LEFT, Buttons.CURSOR_RIGHT,
            -> return moveCursor(button, u, s.width, s.height)

            Buttons.CURSOR_SELECT -> {
                tx = u.cx
                ty = u.cy
            }

            Buttons.CURSOR_SELECT2 -> {
                val soln = s.soln
                if (soln != null && s.solnpos < soln.nmoves) {
                    move = soln.moves[s.solnpos]
                }
            }

            else -> return MoveResult.Unused
        }

        if (tx in 0 until s.width && ty in 0 until s.height &&
            s.grid[0] != s.grid[ty * s.width + tx]
        ) {
            move = s.grid[ty * s.width + tx]
        }

        if (move >= 0 && !s.complete) return MoveResult.Move("M$move")
        return nullret
    }

    /** `move_cursor` with `wrap = false`: the cursor clamps at the edge. */
    private fun moveCursor(button: Int, u: Ui, width: Int, height: Int): MoveResult {
        val dx = when (button) {
            Buttons.CURSOR_LEFT -> -1
            Buttons.CURSOR_RIGHT -> 1
            else -> 0
        }
        val dy = when (button) {
            Buttons.CURSOR_UP -> -1
            Buttons.CURSOR_DOWN -> 1
            else -> 0
        }
        val oldX = u.cx
        val oldY = u.cy
        u.cx = (u.cx + dx).coerceIn(0, width - 1)
        u.cy = (u.cy + dy).coerceIn(0, height - 1)

        if (!u.cursorVisible) {
            u.cursorVisible = true
            return MoveResult.UiUpdate
        }
        if (u.cx != oldX || u.cy != oldY) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /** The spoken name of tile colour 0..9, in the shared colour order. */
    internal fun colourName(colour: Int): String =
        SPOKEN_COLOURS.getOrNull(colour)?.spokenName ?: "unknown"

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell. The state is a colour, so the shared colour name
     * is the cue (section 8), and the pointer path is one tap: any cell
     * whose colour differs from the flooded region fills the region with
     * its colour. A cell that already matches, or a completed board,
     * offers no action.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val t = tileSize
        val start = s.grid[0]
        val cells = gridSpeech(
            width = s.width,
            height = s.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val cursor = if (u.cursorVisible && u.cx == x && u.cy == y) {
                    ", cursor"
                } else {
                    ""
                }
                colourName(s.colourAt(x, y)) + cursor
            },
            actions = { x, y ->
                if (!s.complete && s.colourAt(x, y) != start) {
                    listOf(SpokenAction("Fill", Buttons.LEFT_BUTTON))
                } else {
                    emptyList()
                }
            },
        )
        return cells.capped(cells.units.getOrNull(u.cy * s.width + u.cx))
    }

    /**
     * `current_key_label`. There is no hook for it in [Game] yet, so this
     * is a public helper rather than a seam method, as in samegame. The
     * label ignores the cursor visibility and the completed flag.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button == Buttons.CURSOR_SELECT &&
            s.grid[0] != s.grid[u.cy * s.width + u.cx]
        ) {
            return "Fill"
        }
        val soln = s.soln
        if (button == Buttons.CURSOR_SELECT2 && soln != null && s.solnpos < soln.nmoves) {
            return "Advance"
        }
        return ""
    }

    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

    /** `fill`: recolour the 4-connected component of the fill point. */
    private fun fill(w: Int, h: Int, grid: IntArray, newcolour: Int, queue: IntArray) {
        val x0 = 0
        val y0 = 0
        val oldcolour = grid[y0 * w + x0]
        grid[y0 * w + x0] = newcolour
        queue[0] = y0 * w + x0
        var qtail = 0
        var qhead = 1

        while (qtail < qhead) {
            val pos = queue[qtail++]
            val y = pos / w
            val x = pos % w
            for (dir in 0 until 4) {
                val y1 = y + when (dir) {
                    1 -> 1
                    3 -> -1
                    else -> 0
                }
                val x1 = x + when (dir) {
                    0 -> 1
                    2 -> -1
                    else -> 0
                }
                if (x1 in 0 until w && y1 in 0 until h) {
                    val pos1 = y1 * w + x1
                    if (grid[pos1] == oldcolour) {
                        grid[pos1] = newcolour
                        queue[qhead++] = pos1
                    }
                }
            }
        }
    }

    /** `completed`: every cell equals cell 0. */
    private fun completed(w: Int, h: Int, grid: IntArray): Boolean {
        val wh = w * h
        for (i in 1 until wh) {
            if (grid[i] != grid[0]) return false
        }
        return true
    }

    private fun colourDigit(colour: Int): Char =
        if (colour > 9) ('A' + (colour - 10)) else ('0' + colour)

    /** C `atoi`: white space, an optional sign, then digits; 0 if none. */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
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

    /**
     * `sscanf(..., "%d")` on the rest of the string: like [atoi], but it
     * fails when there is no digit after the optional white space and
     * sign.
     */
    private fun parseIntPrefix(text: String, start: Int): Int? {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
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
                return if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE
            }
            i++
        }
        return sign * value.toInt()
    }
}
