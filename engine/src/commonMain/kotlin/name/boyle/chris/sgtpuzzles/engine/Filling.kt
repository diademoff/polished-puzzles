package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `filling.c`.
 *
 * The rules are Fillomino: every cell holds a number, and the cells with
 * one number form a 4-connected region of exactly that size. The
 * description lists the given clues; a lower-case letter is a run of
 * empty cells. A move is `i,i,...,_value` and fills every listed cell
 * that is not a clue. A solve is `s` followed by one digit per cell.
 *
 * The description generator and the deduction solver are in
 * [FillingSolver]. They share one large risk: the solver decides which
 * clues are removed, so a solver difference changes every description
 * and every `SOLVE` record.
 *
 * This game has a real drawing cache, [DrawState], and no blitter. The
 * completion flash is not ported: the seam has no flash time, and the
 * oracle's `allow_flash` stub returns 0, so no parity test needs it.
 */
object Filling : Game {

    private const val PREFERRED_TILE_SIZE = 32

    /** `EMPTY` from `filling.c`. */
    private const val EMPTY = 0

    private val DX = intArrayOf(-1, 1, 0, 0)
    private val DY = intArrayOf(0, 0, -1, 1)

    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_HIGHLIGHT = 2
    private const val COL_CORRECT = 3
    private const val COL_ERROR = 4
    private const val COL_USER = 5
    private const val COL_CURSOR = 6
    private const val NCOLOURS = 7

    private const val BORDER_U = 0x001
    private const val BORDER_D = 0x002
    private const val BORDER_L = 0x004
    private const val BORDER_R = 0x008
    private const val BORDER_UR = 0x010
    private const val BORDER_DR = 0x020
    private const val BORDER_UL = 0x040
    private const val BORDER_DL = 0x080
    private const val HIGH_BG = 0x100
    private const val CORRECT_BG = 0x200
    private const val ERROR_BG = 0x400
    private const val USER_COL = 0x800
    private const val CURSOR_SQ = 0x1000

    /** `struct game_params`. */
    data class Params(val width: Int, val height: Int) : Game.Params {

        val text: String get() = encodeParams(this, false)

        override fun copy(): Game.Params = Params(width, height)
    }

    /**
     * `struct shared_state`: the parameters and the immutable clue
     * board. Every state of one game shares one instance, as C shares
     * one `shared_state` and counts its references.
     */
    class Shared internal constructor(
        val width: Int,
        val height: Int,
        /** The clue grid. Do not modify it. */
        val clues: IntArray,
    ) {
        val size: Int get() = width * height
    }

    /**
     * `struct game_state`. `board` holds the current values and is
     * copied per state, as C's `dup_game` copies it. The clue board is
     * in [shared] and never changes.
     */
    class State(
        val shared: Shared,
        val board: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val width: Int get() = shared.width

        val height: Int get() = shared.height

        fun valueAt(x: Int, y: Int): Int = board[y * width + x]

        override fun copy(): Game.State =
            State(shared, board.copyOf(), completed, cheated)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                board.contentEquals(other.board) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + board.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. [sel] is null for "no selection", which is not
     * the same as an all-false array: C tests `ui->sel == NULL` to
     * choose between `MOVE_NO_EFFECT` and `MOVE_UI_UPDATE`.
     */
    class Ui : Game.Ui {
        var sel: BooleanArray? = null
        var curX = 0
        var curY = 0
        var curVisible = false
        var keyDragging = false
    }

    /**
     * `struct game_drawstate`: the last drawn value and flag word per
     * cell, the `started` frame flag, and the two scratch arrays. There
     * is no blitter.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
        val tileSize: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val v: IntArray = IntArray(width * height) { -1 }
        val flags: IntArray = IntArray(width * height) { -1 }
        internal var dsfScratch: Dsf? = null
        internal val borderScratch: IntArray = IntArray(width * height)
    }

    override val name: String = "Filling"
    override val backendName: String = "filling"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    // `filling.c` has no `SMALL_SCREEN` or `SLOW_SYSTEM` conditional, so
    // the app and the oracle expose the same three presets.
    private val presetValues = listOf(Params(9, 7), Params(13, 9), Params(17, 13))

    override fun defaultParams(): Game.Params = Params(13, 9)

    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.width}x${it.height}", it.text)
    }

    /**
     * `decode_params`: `atoi`, then skip ASCII digits, then an optional
     * lower-case `x` and a fresh `atoi`. The height starts equal to the
     * width, so `13` is `13x13`, ` 13x9` is `13x13` (the digit loop
     * stops at the space), and `13X9` is `13x13`. The height `atoi`
     * skips white space and a sign, so `13x 9` is `13x9`.
     */
    override fun decodeParams(text: String): Game.Params {
        val width = atoi(text, 0)
        var height = width
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            height = atoi(text, position + 1)
        }
        return Params(width, height)
    }

    /** `encode_params`: `%dx%d`, and `full` is not read. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.width}x${p.height}"
    }

    /**
     * `validate_params`. The overflow test stays `w > INT_MAX / h` so
     * the boundary is the C one: `46340x46340` is accepted and
     * `46341x46341` is rejected. Do not generate the accepted huge
     * area.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width < 1) return "Width must be at least one"
        if (p.height < 1) return "Height must be at least one"
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        return null
    }

    /** `game_configure` (`filling.c:153-174`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
        )
    }

    /** `custom_params` (`filling.c:176-184`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
        )

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(FillingSolver.newDesc(p.width, p.height, random))
    }

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        return Description(FillingSolver.newDesc(p.width, p.height, random, cancel))
    }

    // `filling.c` fills the grid and runs the deduction solver per attempt; the largest preset is 17x13 and an accepted 46340x46340 board needs about 8.6 GB.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.width.toLong() * p.height > 221) Game.SLOW_GENERATION_WARNING else null
    }

    /**
     * `validate_desc`: `a`..`z` are runs and `'0'..m` are one-cell
     * clues, where `m` is `'0' + max(max(w, h), 3)`. A wide board
     * therefore accepts clues above 9, as `:` is 10 for `13x9` and `A`
     * is 17 for `17x13`. On the three presets `m` stops at `'A'`, so
     * the upper-case `B`..`Z` are rejected; a wider board raises the
     * bound.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val sz = p.width * p.height
        val maximum = ('0'.code + maxOf(maxOf(p.width, p.height), 3)).toChar()
        var area = 0

        for (ch in desc) {
            when {
                ch in 'a'..'z' -> area += ch - 'a' + 1
                ch >= '0' && ch <= maximum -> ++area
                else -> return "Invalid character '$ch' in game description"
            }
            if (area > sz) return "Too much data to fit in grid"
        }
        return if (area < sz) "Not enough data to fill grid" else null
    }

    /**
     * `new_game`: decode the clues and copy them into the board.
     * `completed` is not computed, so an already complete description
     * starts open.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val sz = p.width * p.height
        val clues = IntArray(sz)
        var i = 0
        for (ch in desc) {
            if (ch in 'a'..'z') {
                var run = ch - 'a' + 1
                check(i + run <= sz) { "description is longer than the grid" }
                while (run-- > 0) clues[i++] = EMPTY
            } else {
                check(i < sz) { "description is longer than the grid" }
                // C stores `*desc - '0'` for anything that is not a run.
                clues[i++] = ch - '0'
            }
        }
        return State(
            shared = Shared(p.width, p.height, clues),
            board = clues.copyOf(),
            completed = false,
            cheated = false,
        )
    }

    /**
     * `solve_game`. C reads `states[0].state`, the clue board, so the
     * port reads [Shared.clues] rather than the current board. A
     * stalled solver still returns its partial board: C sets an error
     * and returns the move anyway, and the midend ignores the error.
     * The `AUXINFO` branch is kept for a save from another
     * implementation.
     */
    /**
     * `solve_game` (`filling.c:1379-1389`). C returns the partial answer
     * even when its solver fails, and sets the error that the midend
     * then ignores because the move is non-NULL. [solveGame] keeps both
     * halves so the seam reports the same state.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        if (auxInfo != null) return SolveOutcome(auxInfo, null)
        val s = state as State
        val result = FillingSolver.solve(s.shared.clues, s.width, s.height)
        return SolveOutcome(
            result.solutionString(),
            if (result.solved) null else "Sorry, I couldn't find a solution",
        )
    }

    /**
     * `execute_move`. The `strtol(..., 0)` base is part of the format:
     * `_010` is 8 and `_0x9` is 9. C also assigns the `long` to an
     * `int`, so a value outside the `int` range wraps; the port keeps
     * that by truncating the parsed `Long`.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val sz = s.board.size

        if (move.isNotEmpty() && move[0] == 's') {
            if (move.length != sz + 1) return null
            val next = s.copy() as State
            for (i in 0 until sz) next.board[i] = move[i + 1] - '0'
            next.cheated = true
            checkCompletion(next)
            return next
        }

        val delim = move.indexOf('_')
        if (delim < 0) return null
        val parsedValue = strtol(move, delim + 1)
        if (parsedValue.end != move.length || parsedValue.end == delim + 1) return null
        val value = parsedValue.value.toInt()
        if (value < 0 || value > 9) return null

        val next = s.copy() as State
        var position = 0
        while (position < move.length) {
            val parsed = strtol(move, position)
            if (parsed.end == position) return null
            val index = parsed.value.toInt()
            if (index < 0 || index >= sz) return null
            next.board[index] = value
            if (parsed.end < move.length && move[parsed.end] == '_') break
            if (parsed.end >= move.length || move[parsed.end] != ',') return null
            position = parsed.end + 1
        }

        checkCompletion(next)
        return next
    }

    /** The whole-board completion test of `execute_move`, skipped once true. */
    private fun checkCompletion(state: State) {
        if (state.completed) return
        val w = state.width
        val h = state.height
        val dsf = FillingSolver.makeDsf(null, state.board, w, h)
        for (i in state.board.indices) {
            if (state.board[i] != dsf.size(i)) return
        }
        state.completed = true
    }

    /** `game_status`: 1 when complete, otherwise 0. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /**
     * `wants_statusbar` is false, so C never draws a status line. The
     * app calls `setStatus` unconditionally, so the text is empty, as
     * `pegs` does.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** `board_to_string`: a `+---+` fence above every cell row. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val out = StringBuilder((4 * w + 2) * (2 * h + 1))
        val fence = "+---".repeat(w) + "+\n"
        for (y in 0 until h) {
            out.append(fence)
            for (x in 0 until w) {
                out.append('|')
                out.append(' ')
                val value = s.board[y * w + x]
                out.append(if (value == EMPTY) ' ' else ('0'.code + value).toChar())
                out.append(' ')
            }
            out.append("|\n")
        }
        out.append(fence)
        return out.toString()
    }

    /** `game_changed_state`: clear the selection, report an honest win. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        u.sel = null
        u.keyDragging = false
        val next = newState as State
        return next.completed && !next.cheated &&
            oldState != null && !(oldState as State).completed
    }

    /**
     * `game_request_keys`: `1`..`9`, `0`, and backspace, all with the
     * label the midend's `button2label` would give them. The seam has no
     * `button2label`, so the game supplies the names.
     */
    override fun requestKeys(params: Game.Params): KeyRequest {
        val labels = ArrayList<KeyLabel>(11)
        for (i in 1..9) labels += KeyLabel('0'.code + i, i.toString())
        labels += KeyLabel('0'.code, "0")
        labels += KeyLabel('\b'.code, "Clear")
        return KeyRequest(labels, ArrowMode.ARROWS_LEFT_RIGHT)
    }

    /** `game_compute_size`: a half-tile border on the left and top. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size((p.width + 1) * tileSize, (p.height + 1) * tileSize)
    }

    /** C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /**
     * `game_get_cursor_location`: C writes a rectangle only for a
     * visible cursor, and does not clamp a cursor that a letterbox tap
     * left outside the grid. The seam reports that with null.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        val border = tileSize / 2
        return Game.CursorRect(
            border + u.curX * tileSize,
            border + u.curY * tileSize,
            tileSize,
            tileSize,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`. Every derived colour is a per-channel product of
     * the background; unlike `flood`, this game does not call
     * `game_mkhighlight`.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]

        setColour(ret, COL_GRID, 0.0f, 0.0f, 0.0f)
        for (i in 0..2) {
            ret[COL_HIGHLIGHT * 3 + i] = 0.7f * ret[COL_BACKGROUND * 3 + i]
            ret[COL_CORRECT * 3 + i] = 0.9f * ret[COL_BACKGROUND * 3 + i]
            ret[COL_CURSOR * 3 + i] = 0.5f * ret[COL_BACKGROUND * 3 + i]
        }
        setColour(ret, COL_ERROR, 1.0f, 0.85f * ret[COL_BACKGROUND * 3 + 1],
            0.85f * ret[COL_BACKGROUND * 3 + 2])
        setColour(ret, COL_USER, 0.0f, 0.6f * ret[COL_BACKGROUND * 3 + 1], 0.0f)
        return ret
    }

    private fun setColour(out: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        out[index * 3] = r
        out[index * 3 + 1] = g
        out[index * 3 + 2] = b
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height, tileSize)
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
        val ds = drawState as DrawState
        val w = s.width
        val h = s.height
        val border = tileSize / 2
        val borderWidth = maxOf(tileSize / 32, 1)

        // The seam has no flash time, so `flashy` is always false.
        if (!ds.started) {
            sink.rect(
                border - borderWidth,
                border - borderWidth,
                w * tileSize + 2 * borderWidth + 1,
                h * tileSize + 2 * borderWidth + 1,
                COL_GRID,
            )
            sink.update(
                0,
                0,
                w * tileSize + 2 * border,
                h * tileSize + 2 * border,
            )
            ds.started = true
        }

        drawGrid(s, u, ds, sink, tileSize, shading = true)
    }

    private fun drawGrid(
        s: State,
        u: Ui,
        ds: DrawState,
        sink: DrawSink,
        tileSize: Int,
        shading: Boolean,
    ) {
        val w = s.width
        val h = s.height
        val sz = w * h
        val board = s.board
        val clues = s.shared.clues
        ds.dsfScratch = FillingSolver.makeDsf(ds.dsfScratch, board, w, h)
        val dsf = ds.dsfScratch!!
        val scratch = ds.borderScratch

        scratch.fill(0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (dx in 0..1) {
                    val dy = 1 - dx
                    if (x + dx >= w || y + dy >= h) continue
                    val v1 = board[y * w + x]
                    val v2 = board[(y + dy) * w + x + dx]
                    val s1 = dsf.size(y * w + x)
                    val s2 = dsf.size((y + dy) * w + x + dx)
                    var border = false
                    if (v1 != v2) {
                        if (v1 != EMPTY && v2 != EMPTY) border = true
                        if (v1 != EMPTY && s1 >= v1) border = true
                        if (v2 != EMPTY && s2 >= v2) border = true
                    }
                    if (border) scratch[y * w + x] = scratch[y * w + x] or (if (dx == 1) 1 else 2)
                }
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val value = board[i]
                var flags = 0

                if (shading) {
                    val sel = u.sel
                    if (sel != null && sel[i]) {
                        flags = flags or HIGH_BG
                    } else if (value != EMPTY) {
                        val size = dsf.size(i)
                        if (size == value) {
                            flags = flags or CORRECT_BG
                        } else if (size > value) {
                            flags = flags or ERROR_BG
                        } else {
                            // "Too small and sealed": the region has no
                            // adjacent empty cell. This walks the whole
                            // region for every cell, as C does.
                            val root = dsf.canonify(i)
                            var sealed = true
                            var j = 0
                            while (j < sz && sealed) {
                                if (dsf.canonify(j) == root) {
                                    for (k in 0..3) {
                                        val xx = (j % w) + DX[k]
                                        val yy = (j / w) + DY[k]
                                        if (xx >= 0 && xx < w && yy >= 0 && yy < h &&
                                            board[yy * w + xx] == EMPTY
                                        ) {
                                            sealed = false
                                            break
                                        }
                                    }
                                }
                                ++j
                            }
                            if (sealed) flags = flags or ERROR_BG
                        }
                    }
                }

                if (u.curVisible && x == u.curX && y == u.curY) flags = flags or CURSOR_SQ

                if (x == 0) flags = flags or BORDER_L
                if (y == 0) flags = flags or BORDER_U
                if (x == w - 1) flags = flags or BORDER_R
                if (y == h - 1) flags = flags or BORDER_D

                if (x == 0 || scratch[y * w + x - 1] and 1 != 0) flags = flags or BORDER_L
                if (y == 0 || scratch[(y - 1) * w + x] and 2 != 0) flags = flags or BORDER_U
                if (x == w - 1 || scratch[y * w + x] and 1 != 0) flags = flags or BORDER_R
                if (y == h - 1 || scratch[y * w + x] and 2 != 0) flags = flags or BORDER_D

                if (y > 0 && x > 0 && scratch[(y - 1) * w + (x - 1)] != 0) {
                    flags = flags or BORDER_UL
                }
                if (y > 0 && x < w - 1 &&
                    (scratch[(y - 1) * w + x] and 1 != 0 ||
                        scratch[(y - 1) * w + (x + 1)] and 2 != 0)
                ) {
                    flags = flags or BORDER_UR
                }
                if (y < h - 1 && x > 0 &&
                    (scratch[y * w + (x - 1)] and 2 != 0 ||
                        scratch[(y + 1) * w + (x - 1)] and 1 != 0)
                ) {
                    flags = flags or BORDER_DL
                }
                if (y < h - 1 && x < w - 1 &&
                    (scratch[y * w + (x + 1)] and 2 != 0 ||
                        scratch[(y + 1) * w + x] and 1 != 0)
                ) {
                    flags = flags or BORDER_DR
                }

                if (clues[i] == 0) flags = flags or USER_COL

                if (ds.v[i] != value || ds.flags[i] != flags) {
                    drawSquare(sink, ds, tileSize, x, y, value, flags)
                    ds.v[i] = value
                    ds.flags[i] = flags
                }
            }
        }
    }

    /** `draw_square`, in the C call order. */
    private fun drawSquare(
        sink: DrawSink,
        ds: DrawState,
        tileSize: Int,
        x: Int,
        y: Int,
        n: Int,
        flags: Int,
    ) {
        val border = tileSize / 2
        val borderWidth = maxOf(tileSize / 32, 1)
        val px = border + x * tileSize
        val py = border + y * tileSize

        sink.clip(px, py, tileSize, tileSize)
        sink.rect(
            px,
            py,
            tileSize,
            tileSize,
            when {
                flags and HIGH_BG != 0 -> COL_HIGHLIGHT
                flags and ERROR_BG != 0 -> COL_ERROR
                flags and CORRECT_BG != 0 -> COL_CORRECT
                else -> COL_BACKGROUND
            },
        )

        sink.line(px, py, px + tileSize, py, COL_GRID)
        sink.line(px, py, px, py + tileSize, COL_GRID)

        if (n != 0) {
            sink.text(
                (x + 1) * tileSize,
                (y + 1) * tileSize,
                Text.FONT_VARIABLE,
                tileSize / 2,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                if (flags and USER_COL != 0) COL_USER else COL_GRID,
                ('0'.code + n).toChar().toString(),
            )
        }

        if (flags and BORDER_L != 0) {
            sink.rect(px + 1, py + 1, borderWidth, tileSize - 1, COL_GRID)
        }
        if (flags and BORDER_U != 0) {
            sink.rect(px + 1, py + 1, tileSize - 1, borderWidth, COL_GRID)
        }
        if (flags and BORDER_R != 0) {
            sink.rect(px + tileSize - borderWidth, py + 1, borderWidth, tileSize - 1, COL_GRID)
        }
        if (flags and BORDER_D != 0) {
            sink.rect(px + 1, py + tileSize - borderWidth, tileSize - 1, borderWidth, COL_GRID)
        }
        if (flags and BORDER_UL != 0) {
            sink.rect(px + 1, py + 1, borderWidth, borderWidth, COL_GRID)
        }
        if (flags and BORDER_UR != 0) {
            sink.rect(px + tileSize - borderWidth, py + 1, borderWidth, borderWidth, COL_GRID)
        }
        if (flags and BORDER_DL != 0) {
            sink.rect(px + 1, py + tileSize - borderWidth, borderWidth, borderWidth, COL_GRID)
        }
        if (flags and BORDER_DR != 0) {
            sink.rect(
                px + tileSize - borderWidth,
                py + tileSize - borderWidth,
                borderWidth,
                borderWidth,
                COL_GRID,
            )
        }

        if (flags and CURSOR_SQ != 0) {
            val inset = tileSize / 8
            val x0 = px + inset
            val y0 = py + inset
            val x1 = x0 + (tileSize - inset * 2) - 1
            val y1 = y0 + (tileSize - inset * 2) - 1
            sink.poly(intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0), -1, COL_CURSOR)
        }

        sink.unclip()
        sink.update(px, py, tileSize, tileSize)
    }

    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button0: Int,
        tileSize: Int,
    ): MoveResult {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val h = s.height
        val sz = w * h
        val border = tileSize / 2

        val tx = (x + tileSize - border) / tileSize - 1
        val ty = (y + tileSize - border) / tileSize - 1
        val button = Buttons.stripModifiers(button0)

        /*
         * C indexes `clues[w*cur_y+cur_x]` and `sel[w*cur_y+cur_x]` in
         * the cursor-select paths without a bounds test, and an Android
         * click leaves the cursor outside the grid. The port defines an
         * out-of-grid cursor as addressing no cell: nothing is selected,
         * toggled, or filled, and no key label is offered. No read or
         * write goes out of bounds.
         */
        fun cursorIndex(): Int =
            if (u.curX in 0 until w && u.curY in 0 until h) u.curY * w + u.curX else -1

        if (Buttons.isMouseDown(button) || Buttons.isMouseDrag(button)) {
            if (Buttons.isMouseDown(button)) u.sel = null
            if (tx >= 0 && tx < w && ty >= 0 && ty < h) {
                if (u.sel == null) u.sel = BooleanArray(sz)
                if (s.shared.clues[ty * w + tx] == 0) u.sel!![ty * w + tx] = true
            }
            // ANDROID keeps the raw cell, even outside the board.
            u.curX = tx
            u.curY = ty
            return MoveResult.UiUpdate
        }

        if (Buttons.isCursorMove(button)) {
            u.curVisible = true
            val dx = when (button) {
                Buttons.CURSOR_RIGHT -> 1
                Buttons.CURSOR_LEFT -> -1
                else -> 0
            }
            val dy = when (button) {
                Buttons.CURSOR_DOWN -> 1
                Buttons.CURSOR_UP -> -1
                else -> 0
            }
            // `move_cursor` with `wrap = false`: the cursor clamps.
            u.curX = (u.curX + dx).coerceIn(0, w - 1)
            u.curY = (u.curY + dy).coerceIn(0, h - 1)
            if (!u.keyDragging) return MoveResult.UiUpdate
            selectSquare(u, s, cursorIndex())
            return MoveResult.UiUpdate
        }

        if (button == Buttons.CURSOR_SELECT) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            u.keyDragging = !u.keyDragging
            if (!u.keyDragging) return MoveResult.UiUpdate
            selectSquare(u, s, cursorIndex())
            return MoveResult.UiUpdate
        }

        if (button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            if (u.sel == null) u.sel = BooleanArray(sz)
            u.keyDragging = false
            val cursor = cursorIndex()
            if (cursor >= 0 && s.shared.clues[cursor] == 0) u.sel!![cursor] = !u.sel!![cursor]
            if (u.sel!!.none { it }) u.sel = null
            return MoveResult.UiUpdate
        }

        if (button == 27) {
            u.sel = null
            u.keyDragging = false
            return MoveResult.UiUpdate
        }

        var key = button
        if (key == '\b'.code) key = '0'.code
        if (key < '0'.code || key > '9'.code) return MoveResult.Unused
        val value = key - '0'.code
        if (value > (if (w == 2 && h == 2) 3 else maxOf(w, h))) return MoveResult.Unused
        u.keyDragging = false

        val indices = ArrayList<Int>()
        val sel = u.sel
        val cursor = cursorIndex()
        for (i in 0 until sz) {
            val selected = if (sel != null) sel[i] else u.curVisible && cursor == i
            if (!selected) continue
            if (s.shared.clues[i] != 0) continue
            if (s.board[i] != value) indices += i
        }
        val move = if (indices.isEmpty()) null else indices.joinToString(",") + "_$value"

        if (sel == null) return if (move != null) MoveResult.Move(move) else MoveResult.NoEffect
        u.sel = null
        // The selection was cleared, so the UI must redraw even when the
        // selected cells already hold the value.
        return if (move != null) MoveResult.Move(move) else MoveResult.UiUpdate
    }

    /** `select_square`: allocate the selection and set the cursor cell. */
    private fun selectSquare(u: Ui, s: State, cursor: Int) {
        if (u.sel == null) u.sel = BooleanArray(s.width * s.height)
        if (cursor >= 0 && s.shared.clues[cursor] == 0) u.sel!![cursor] = true
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the value with its origin (`given 4`, `3`,
     * `empty`), the region state the drawing shades (`correct` or
     * `wrong`), the selection, and the cursor. The region is in the text
     * because the shade is the only other cue and a colour-blind player
     * must get the same hint.
     *
     * A click selects one cell and nothing else, so the value itself
     * stays on the keypad: the game has no pointer digit entry (D-01
     * section 4). A clue cannot be selected, so it offers no action.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val h = s.height
        val t = tileSize
        val regions = regionStates(s)
        val cells = gridSpeech(
            width = w,
            height = h,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * w + x
                val selected = if (u.sel?.get(index) == true) SPOKEN_SELECTED else ""
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                digitSpeech(s.board[index], s.shared.clues[index] != 0, emptyList()) +
                    regions[index] + selected + cursor
            },
            actions = { x, y ->
                if (s.shared.clues[y * w + x] == 0) {
                    listOf(SpokenAction("Select", Buttons.LEFT_BUTTON))
                } else {
                    emptyList()
                }
            },
        )
        return cells.capped(cells.units.getOrNull(u.curY * w + u.curX))
    }

    /**
     * The shade of every cell, as words: `, correct` when the region is
     * exactly its value, and `, wrong` when it is larger, or smaller and
     * sealed (no cell of it touches an empty cell). An empty cell and a
     * region that can still grow say nothing.
     *
     * This is `draw_grid`'s shading computed from the state, so the hint
     * a sighted player sees and the text a screen reader hears agree.
     */
    private fun regionStates(s: State): Array<String> {
        val w = s.width
        val h = s.height
        val board = s.board
        val out = Array(w * h) { "" }
        val dsf = FillingSolver.makeDsf(null, board, w, h)
        // A region is open while one of its cells touches an empty cell.
        val open = HashSet<Int>()
        for (i in 0 until w * h) {
            if (board[i] != EMPTY) continue
            for (j in 0..3) {
                val x = (i % w) + DX[j]
                val y = (i / w) + DY[j]
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val k = y * w + x
                if (board[k] != EMPTY) open.add(dsf.canonify(k))
            }
        }
        for (i in 0 until w * h) {
            val value = board[i]
            if (value == EMPTY) continue
            val size = dsf.size(i)
            out[i] = when {
                size == value -> ", correct"
                size > value -> SPOKEN_WRONG
                dsf.canonify(i) in open -> ""
                else -> SPOKEN_WRONG
            }
        }
        return out
    }

    /**
     * `current_key_label`. There is no seam hook yet, so this is a
     * public helper, as in `flood`. C reads `ui->sel` without a NULL
     * test; the port treats a null selection as "not selected", and an
     * out-of-grid cursor as "no cell", so neither path can crash.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.curVisible
        ) {
            if (button == Buttons.CURSOR_SELECT) {
                return if (u.keyDragging) "Stop" else "Multiselect"
            }
            val cursor = if (u.curX in 0 until s.width && u.curY in 0 until s.height) {
                u.curY * s.width + u.curX
            } else {
                -1
            }
            if (cursor >= 0 && s.shared.clues[cursor] == 0) {
                return if (u.sel?.get(cursor) == true) "Deselect" else "Select"
            }
        }
        return ""
    }

    /**
     * C `atoi`: white space, an optional sign, then digits. Kotlin
     * `Int` arithmetic wraps where C is undefined; the port saturates
     * instead, so a decode never throws.
     */
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

    private class ParsedLong(val value: Long, val end: Int)

    /**
     * C `strtol(text, &end, 0)`: white space, a sign, then a decimal,
     * octal (`0`), or hexadecimal (`0x`) number. A value outside the
     * `long` range saturates, as the C library does. The caller
     * truncates to `Int`, which reproduces the C `long` to `int`
     * assignment.
     */
    private fun strtol(text: String, start: Int): ParsedLong {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++

        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }

        var base = 10
        if (i < text.length && text[i] == '0') {
            if (i + 2 < text.length &&
                (text[i + 1] == 'x' || text[i + 1] == 'X') &&
                digitValue(text[i + 2]) in 0..15
            ) {
                base = 16
                i += 2
            } else {
                base = 8
            }
        }

        val firstDigit = i
        var value = 0L
        var overflow = false
        while (i < text.length) {
            val digit = digitValue(text[i])
            if (digit < 0 || digit >= base) break
            if (!overflow) {
                if (value > (Long.MAX_VALUE - digit) / base) {
                    overflow = true
                } else {
                    value = value * base + digit
                }
            }
            i++
        }

        if (i == firstDigit) {
            // No conversion: C sets endptr to the starting pointer.
            return ParsedLong(0L, start)
        }
        if (overflow) {
            return ParsedLong(if (negative) Long.MIN_VALUE else Long.MAX_VALUE, i)
        }
        return ParsedLong(if (negative) -value else value, i)
    }

    private fun digitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
