package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `mosaic.c`: Fill-a-Pix, also called Mosaico.
 *
 * Every cell is black or white. A shown clue gives the number of black
 * cells in the 3x3 block that contains the clue and the clue itself.
 * The player cycles cells between unmarked, black, and white until no
 * shown clue is unsatisfied.
 *
 * The generator is solver-gated twice and draws from the RNG inside the
 * solver, so [MosaicSolver] is part of the game-id format. The clue
 * board is immutable and shared by every state; only the player's cell
 * values and two counters are copied per state.
 *
 * `solve_game` reads the clue board, not the current cells, so the seam
 * needs no initial-grid copy. The answer is a bit-packed `s` move. A
 * load replays the history, which rebuilds the clue flags and the
 * counters; nothing of the state is serialised beyond the description.
 *
 * There is no `AUXINFO`, no `UI` record, no preference, and no blitter.
 * The completion flash is not ported: the seam has no flash time.
 */
object Mosaic : Game {

    private const val PREFERRED_TILE_SIZE = 32
    private const val DEFAULT_SIZE = 10
    private const val DEFAULT_AGGRESSIVE = true
    private const val MAX_TILES = 10000

    /** `enum cell_state` from `mosaic.c:62-74`. */
    const val STATE_UNMARKED = 0
    const val STATE_MARKED = 1
    const val STATE_BLANK = 2
    const val STATE_SOLVED = 4
    const val STATE_ERROR = 8
    const val STATE_BLANK_SOLVED = STATE_SOLVED or STATE_BLANK
    const val STATE_MARKED_SOLVED = STATE_SOLVED or STATE_MARKED
    const val STATE_OK_NUM = STATE_BLANK or STATE_MARKED

    /** The colour enum from `mosaic.c:48-60`. */
    private const val COL_BACKGROUND = 0
    private const val COL_UNMARKED = 1
    private const val COL_GRID = 2
    private const val COL_MARKED = 3
    private const val COL_BLANK = 4
    private const val COL_TEXT_SOLVED = 5
    private const val COL_ERROR = 6
    private const val COL_CURSOR = 7
    private const val NCOLOURS = 8
    private const val COL_TEXT_DARK = COL_MARKED
    private const val COL_TEXT_LIGHT = COL_BLANK

    /** Extra drawstate bits from `mosaic.c:1432-1438`. */
    private const val DRAWFLAG_CURSOR = 0x100
    private const val DRAWFLAG_CURSOR_U = 0x200
    private const val DRAWFLAG_CURSOR_L = 0x400
    private const val DRAWFLAG_CURSOR_UL = 0x800
    private const val DRAWFLAG_MARGIN_R = 0x1000
    private const val DRAWFLAG_MARGIN_D = 0x2000

    /** `struct game_params`: the two sizes and the aggressive flag. */
    data class Params(
        val width: Int,
        val height: Int,
        val aggressive: Boolean = DEFAULT_AGGRESSIVE,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(width, height, aggressive)
    }

    /**
     * The immutable clue board, the counterpart of `board_state`. It is
     * shared by every state and is a function of the description.
     */
    class Shared internal constructor(
        val width: Int,
        val height: Int,
        /** The clue of each cell, or `-1` for a hidden cell. Do not modify. */
        val clue: IntArray,
        /** True where [clue] is a shown clue. Do not modify. */
        val shown: BooleanArray,
    ) {
        val size: Int get() = width * height
    }

    /**
     * `struct game_state`. [cells] holds the player's value plus the
     * `SOLVED` and `ERROR` flags. [cheating] is set by an `s` move and
     * never cleared. [notCompletedClues] is a cache of the shown clues
     * without the `SOLVED` bit.
     */
    class State(
        val shared: Shared,
        val cells: IntArray,
        var cheating: Boolean,
        var notCompletedClues: Int,
    ) : Game.State {

        val width: Int get() = shared.width

        val height: Int get() = shared.height

        fun valueAt(x: Int, y: Int): Int = cells[y * width + x]

        fun clueAt(x: Int, y: Int): Int = shared.clue[y * width + x]

        fun isShown(x: Int, y: Int): Boolean = shared.shown[y * width + x]

        override fun copy(): Game.State = State(shared, cells.copyOf(), cheating, notCompletedClues)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                shared.clue.contentEquals(other.shared.clue) &&
                shared.shown.contentEquals(other.shared.shown) &&
                cells.contentEquals(other.cells) &&
                cheating == other.cheating &&
                notCompletedClues == other.notCompletedClues

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + shared.clue.contentHashCode()
            result = 31 * result + shared.shown.contentHashCode()
            result = 31 * result + cells.contentHashCode()
            result = 31 * result + cheating.hashCode()
            result = 31 * result + notCompletedClues
            return result
        }
    }

    /**
     * `struct game_ui`. The dead `solved` and never-initialized
     * `in_progress` fields are dropped. A fresh UI is cursor-hidden, as
     * Android never sets `PUZZLES_SHOW_CURSOR`.
     */
    class Ui : Game.Ui {
        var lastX: Int = -1
        var lastY: Int = -1
        var lastState: Int = 0
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false
    }

    /**
     * `struct game_drawstate`: one word per cell over `(w+1) x (h+1)`
     * cells, including the margin. The first value is `-1`, which no
     * real word can equal, so a cell whose word is `-1` is never drawn.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
        val tileSize: Int,
    ) : Game.DrawState {
        val cells: IntArray = IntArray((width + 1) * (height + 1)) { -1 }
    }

    override val name: String = "Mosaic"
    override val backendName: String = "mosaic"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    // `mosaic.c` has no `SMALL_SCREEN` branch, so the app and the oracle
    // expose the same six presets.
    private val presetSizes = listOf(3, 5, 10, 15, 25, 50)

    /** `default_params`: the third preset, 10x10 aggressive. */
    override fun defaultParams(): Game.Params = Params(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_AGGRESSIVE)

    /** `game_fetch_preset`: `Size: %dx%d` and the full parameter encoding. */
    override fun presets(): List<Preset> = presetSizes.mapIndexed { index, size ->
        val params = Params(size, size, index != presetSizes.lastIndex)
        Preset("Size: ${size}x$size", encodeParams(params, true))
    }

    /**
     * `decode_params` from `mosaic.c:179-193`.
     *
     * `height` starts at `width`, and the digit skip starts at the
     * original string, so a sign or a leading space hides the `x`. Only
     * a lower-case `x` separates the sizes. The `h` suffix sets the
     * aggressive flag with `atoi`; with no suffix the flag keeps its
     * previous value, which is the default `true`.
     */
    override fun decodeParams(text: String): Game.Params {
        val width = atoi(text, 0)
        var height = width
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            height = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }
        var aggressive = DEFAULT_AGGRESSIVE
        if (position < text.length && text[position] == 'h') {
            position++
            aggressive = atoi(text, position) != 0
            while (position < text.length && text[position] in '0'..'9') position++
        }
        return Params(width, height, aggressive)
    }

    /**
     * `encode_params` from `mosaic.c:195-205`: `WxH`, and `h0` only in
     * the full form when the flag is not the default.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return buildString {
            append(p.width).append('x').append(p.height)
            if (full && !p.aggressive) append("h0")
        }
    }

    /**
     * `validate_params` from `mosaic.c:240-249`, in C order. `full` is
     * not read. The area is at most 10000 cells.
     *
     * Generation time is the one remaining app debt: an aggressive
     * `100x100` board takes 30-45 s in C, because `hide_clues` calls the
     * solver once per needed clue. The app must generate off the UI
     * thread, or the owner must accept a guard that C does not have (a
     * deviation with a test). Do not add a unit test for aggressive
     * `100x100`.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.height < 3 || p.width < 3) return "Minimal size is 3x3"
        if (p.height > MAX_TILES / p.width) return "Maximum size is 10000 tiles"
        return null
    }

    /** `game_configure` (`mosaic.c:207-229`): Height before Width. */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Bool("Aggressive generation (longer)", p.aggressive),
        )
    }

    /** `custom_params` (`mosaic.c:231-238`): `atol`, not `atoi`. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atol((items[1] as ConfigItem.Text).value, 0).toInt(),
            atol((items[0] as ConfigItem.Text).value, 0).toInt(),
            (items[2] as ConfigItem.Bool).value,
        )

    /**
     * C `atol`: white space, an optional sign, then digits. A value
     * outside `Long` saturates as glibc does; `custom_params` then
     * narrows it to `int`, which is the C assignment.
     */
    private fun atol(text: String, start: Int): Long {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            val digit = text[i] - '0'
            if (!overflow) {
                if (value > (Long.MAX_VALUE - digit) / 10) {
                    overflow = true
                } else {
                    value = value * 10 + digit
                }
            }
            i++
        }
        if (overflow) return if (negative) Long.MIN_VALUE else Long.MAX_VALUE
        return if (negative) -value else value
    }

    /** `new_game_desc`: generate and encode the clues. */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(
            MosaicSolver.generate(p.width, p.height, p.aggressive, random).desc,
        )
    }

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        return Description(
            MosaicSolver.generate(p.width, p.height, p.aggressive, random, cancel).desc,
        )
    }

    // `mosaic.c` grades every attempt with the solver after two shuffles; the largest preset is 50x50 and an aggressive 100x100 takes 30-45 s (PARITY).
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.width.toLong() * p.height > 2500) Game.SLOW_GENERATION_WARNING else null
    }

    /**
     * `new_game_desc` with the attempt counts, for the generator tests.
     * A retry is visible only here, not in the description.
     */
    internal fun newDescWithAttempts(params: Game.Params, random: RandomState): MosaicSolver.Generated {
        val p = params as Params
        return MosaicSolver.generate(p.width, p.height, p.aggressive, random)
    }

    /**
     * `validate_desc` from `mosaic.c:831-851`. A letter is a run of
     * `c - 'a' + 1` hidden cells and a digit is one shown clue. Any
     * other character is invalid, including `,`, space, and `{`.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val size = p.width * p.height
        var length = 0
        for (c in desc) {
            if (c in 'a'..'z') {
                length += c - 'a'
            } else if (c < '0' || c > '9') {
                return "Invalid character in game description"
            }
            length++
        }
        if (length != size) return "Desc size mismatch"
        return null
    }

    /**
     * `new_game` from `mosaic.c:853-901`. Every cell starts unmarked and
     * every clue starts unsolved, so a description that is already a
     * solution starts open and a description with no clues starts
     * completed.
     *
     * C writes past the arrays for a description that covers too many
     * cells and leaves holes for one that covers too few. Both are
     * unreachable after [validateDesc]; a direct call throws instead.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val n = p.width * p.height
        val clue = IntArray(n)
        val shown = BooleanArray(n)
        val cells = IntArray(n)
        var dest = 0
        var clues = 0

        for (c in desc) {
            if (c in '0'..'9') {
                if (dest >= n) throw IllegalArgumentException("description covers more than $n cells")
                clue[dest] = c - '0'
                shown[dest] = true
                clues++
            } else {
                val total = if (c != ' ') c - 'a' + 1 else 1
                var spaces = 0
                while (spaces < total) {
                    if (dest >= n) {
                        throw IllegalArgumentException("description covers more than $n cells")
                    }
                    clue[dest] = -1
                    shown[dest] = false
                    spaces++
                    if (spaces < total) dest++
                }
            }
            dest++
        }
        if (dest != n) throw IllegalArgumentException("description covers $dest cells, not $n")

        return State(
            shared = Shared(p.width, p.height, clue, shown),
            cells = cells,
            cheating = false,
            notCompletedClues = clues,
        )
    }

    /**
     * `solve_game` from `mosaic.c:934-975`. The clue board is shared and
     * immutable, so the current state and the first state have the same
     * board. The answer is `s` plus the marked bits packed MSB first,
     * eight cells per byte, as lower-case hex.
     *
     * A board the solver cannot finish returns null; C reports
     * "Could not solve this board".
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /** `solve_game` (`mosaic.c:925-953`): the solver failed. */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        val n = s.width * s.height
        val sol = MosaicSolver.solve(s.width, s.height, s.shared.clue, s.shared.shown)
            ?: return SolveOutcome(null, "Could not solve this board")
        val out = StringBuilder(1 + 2 * ((n + 7) / 8))
        out.append('s')
        var i = 0
        while (i < n) {
            var byte = 0
            var bits = 0
            while (bits < 8 && i < n) {
                byte = (byte shl 1) or (if (sol[i] == STATE_MARKED) 1 else 0)
                i++
                bits++
            }
            byte = byte shl (8 - bits)
            out.append(HEX[(byte shr 4) and 0x0F]).append(HEX[byte and 0x0F])
        }
        return SolveOutcome(out.toString(), null)
    }

    /**
     * `execute_move` from `mosaic.c:1251-1393`, including the loose
     * grammar: an unknown or empty move is accepted as a copy, only the
     * commas between parameters are checked, the last parameter keeps
     * unparsed trailing text, and a `t`/`T` coordinate of `-1` returns
     * the copy without a recount.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val ret = s.copy() as State
        val n = ret.width * ret.height

        var p = 0
        val moveType = if (move.isEmpty()) 0 else move[0].code
        if (move.isNotEmpty()) p = 1
        val nparams = when (moveType.toChar()) {
            't', 'T' -> 2
            'd', 'e' -> 5
            else -> 0
        }
        val params = IntArray(5)
        for (i in 0 until nparams) {
            params[i] = atoi(move, p)
            while (p < move.length && move[p] in '0'..'9') p++
            if (i + 1 < nparams) {
                if (p >= move.length || move[p] != ',') return null
                p++
            }
        }

        if (moveType == 't'.code || moveType == 'T'.code) {
            val steps = if (moveType == 'T'.code) 2 else 1
            val x = params[0]
            val y = params[1]
            if (x == -1 || y == -1) return ret
            val index = indexOrNull(ret, x, y) ?: return null
            var cell = ret.cells[index]
            if (cell >= STATE_OK_NUM) cell = cell and STATE_OK_NUM
            ret.cells[index] = (cell + steps) % STATE_OK_NUM
            updateBoardStateAround(ret, x, y)
        } else if (moveType == 's'.code) {
            ret.notCompletedClues = 0
            ret.cheating = true
            var solLocation = 0
            var bits = 0
            var i = 1
            while (i < move.length) {
                var solValue = 0
                while (bits < 8) {
                    solValue = solValue shl 4
                    val code = if (i < move.length) move[i].code else 0
                    val value = if (code in '0'.code..'9'.code) {
                        code - '0'.code
                    } else {
                        (code - 'a'.code) + 10
                    }
                    solValue = solValue or value
                    bits += 4
                    i++
                }
                while (bits > 0 && solLocation < n) {
                    ret.cells[solLocation] = if (solValue and 0x80 != 0) {
                        STATE_MARKED_SOLVED
                    } else {
                        STATE_BLANK_SOLVED
                    }
                    solValue = solValue shl 1
                    bits--
                    solLocation++
                }
            }
            return ret
        } else if (moveType == 'd'.code || moveType == 'e'.code) {
            val x = params[0]
            val y = params[1]
            val srcX = params[2]
            val srcY = params[3]
            val lastState = params[4]

            val dirX: Int
            val dirY: Int
            val diff: Int
            if (srcX == x && srcY != y) {
                dirX = 0
                var d = srcY - y
                if (d < 0) {
                    dirY = -1
                    d = -d
                } else {
                    dirY = 1
                }
                diff = d
            } else {
                var d = srcX - x
                dirY = 0
                if (d < 0) {
                    dirX = -1
                    d = -d
                } else {
                    dirX = 1
                }
                diff = d
            }

            for (i in 0 until diff) {
                val xx = x + dirX * i
                val yy = y + dirY * i
                val index = indexOrNull(ret, xx, yy) ?: return null
                if (ret.cells[index] and STATE_OK_NUM == 0) {
                    ret.cells[index] = lastState.toByte().toInt()
                    updateBoardStateAround(ret, xx, yy)
                }
            }
        }

        var cluesLeft = 0
        for (y in 0 until ret.height) {
            for (x in 0 until ret.width) {
                val index = y * ret.width + x
                if (ret.shared.shown[index] && ret.cells[index] and STATE_SOLVED == 0) {
                    cluesLeft++
                }
            }
        }
        ret.notCompletedClues = cluesLeft
        return ret
    }

    /**
     * `update_board_state_around` from `mosaic.c:1217-1249`. Only the
     * shown clues in the 3x3 block of the changed cell are refreshed. An
     * error overrides a solved clue. The clue outside the block keeps its
     * old flags, so the flags are history, not a function of the board.
     */
    private fun updateBoardStateAround(state: State, x: Int, y: Int) {
        val w = state.width
        val h = state.height
        for (i in -1..1) {
            for (j in -1..1) {
                val xx = x + i
                val yy = y + j
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val index = yy * w + xx
                if (!state.shared.shown[index]) continue

                val counts = IntArray(3)
                countAroundState(state, xx, yy, counts)
                val marked = counts[0]
                val blank = counts[1]
                val total = counts[2]
                val clue = state.shared.clue[index]

                var cell = state.cells[index] and STATE_OK_NUM
                if (clue == marked && total - marked - blank == 0) {
                    cell = cell or STATE_SOLVED
                } else if (clue < marked || clue > total - blank) {
                    cell = cell or STATE_ERROR
                }
                state.cells[index] = cell
            }
        }
    }

    /**
     * `count_around_state` from `mosaic.c:360-382`: the 3x3 block
     * including the clue cell. A cell with both value bits counts as
     * blank, as C tests `BLANK` first.
     */
    private fun countAroundState(state: State, x: Int, y: Int, counts: IntArray) {
        val w = state.width
        val h = state.height
        var total = 0
        var blank = 0
        var marked = 0
        for (i in -1..1) {
            for (j in -1..1) {
                val xx = x + i
                val yy = y + j
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                total++
                val cell = state.cells[yy * w + xx]
                if (cell and STATE_BLANK != 0) blank++ else if (cell and STATE_MARKED != 0) marked++
            }
        }
        counts[0] = marked
        counts[1] = blank
        counts[2] = total
    }

    /** `game_status`: 1 when no shown clue is unsolved. There is no -1. */
    override fun status(state: Game.State): Int =
        if ((state as State).notCompletedClues == 0) 1 else 0

    /**
     * `game_redraw`'s status text from `mosaic.c:1556-1562`. It uses the
     * cached counter, not a fresh recount.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        return when {
            s.notCompletedClues == 0 && !s.cheating -> "COMPLETED!"
            s.notCompletedClues == 0 && s.cheating -> "Auto solved"
            else -> "Clues left: ${s.notCompletedClues}"
        }
    }

    /**
     * `game_text_format` from `mosaic.c:982-1003`: `|d|` for a shown
     * clue and `| |` for a hidden cell, one row per line, using the
     * clues so the text never changes during play.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val out = StringBuilder(s.height * (s.width * 3 + 1))
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val index = y * s.width + x
                if (s.shared.shown[index]) {
                    out.append('|').append('0' + s.shared.clue[index]).append('|')
                } else {
                    out.append("| |")
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * `game_changed_state`: only an honest open-to-completed transition
     * raises the event. A solve is cheated, and an undo into an open
     * state is not a completion.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return !next.cheating && next.notCompletedClues == 0 &&
            oldState != null && (oldState as State).notCompletedClues > 0
    }

    /**
     * `game_request_keys` is NULL in C, so the midend keeps the default
     * arrow mode and reports no labels.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /** `game_compute_size`: one tile of margin on every axis. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size((p.width + 1) * tileSize, (p.height + 1) * tileSize)
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /**
     * `game_get_cursor_location` from `mosaic.c:694-705`: the same
     * `COORD_FROM_CELL` as the drawing, and nothing for a hidden cursor.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        return Game.CursorRect(
            u.curX * tileSize + tileSize / 2 - 1,
            u.curY * tileSize + tileSize / 2 - 1,
            tileSize,
            tileSize,
        )
    }

    /**
     * `current_key_label` from `mosaic.c:1033-1051`. There is no seam
     * hook, so this is a public helper with a unit test. A hidden cursor
     * and a completed board have no label. C dereferences a null cell
     * for a cursor outside the grid; the midend keeps the cursor inside,
     * and this guard returns the empty label for the unreachable case.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) return ""
        val u = ui as Ui
        val s = state as State
        if (!u.curVisible || s.notCompletedClues == 0) return ""
        val index = indexOrNull(s, u.curX, u.curY) ?: return ""
        return when (s.cells[index] and STATE_OK_NUM) {
            STATE_UNMARKED -> if (button == Buttons.CURSOR_SELECT) "Black" else "White"
            STATE_MARKED -> if (button == Buttons.CURSOR_SELECT) "White" else "Empty"
            STATE_BLANK -> if (button == Buttons.CURSOR_SELECT) "Empty" else "Black"
            else -> ""
        }
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell. A shown clue is followed by the cell's colour:
     * `clue 3, black`. The left and right buttons cycle the cell the
     * way `current_key_label` words it, `Black`, `White`, and `Empty`,
     * and a completed board takes no pointer input.
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
            // `COORD_FROM_CELL` starts one pixel up and left of the centre.
            left = t / 2 - 1,
            top = t / 2 - 1,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * s.width + x
                val cell = s.cells[index]
                val clue = if (s.isShown(x, y)) "clue ${s.clueAt(x, y)}, " else ""
                val colour = when (cell and STATE_OK_NUM) {
                    STATE_MARKED -> "black"
                    STATE_BLANK -> "white"
                    else -> "empty"
                }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) ", cursor" else ""
                val wrong = if (cell and STATE_ERROR != 0) SPOKEN_WRONG else ""
                "$clue$colour$cursor$wrong"
            },
            actions = { x, y ->
                if (s.notCompletedClues == 0) {
                    emptyList()
                } else {
                    when (s.cells[y * s.width + x] and STATE_OK_NUM) {
                        STATE_MARKED -> listOf(
                            SpokenAction("White", Buttons.LEFT_BUTTON),
                            SpokenAction("Empty", Buttons.RIGHT_BUTTON),
                        )

                        STATE_BLANK -> listOf(
                            SpokenAction("Empty", Buttons.LEFT_BUTTON),
                            SpokenAction("Black", Buttons.RIGHT_BUTTON),
                        )

                        else -> listOf(
                            SpokenAction("Black", Buttons.LEFT_BUTTON),
                            SpokenAction("White", Buttons.RIGHT_BUTTON),
                        )
                    }
                }
            },
        )
        val cursor = cells.units.getOrNull(u.curY * s.width + u.curX)
        return cells.capped(cursor)
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours` from `mosaic.c:1415-1430`. Only the background
     * comes from the shell; the other seven colors are literals and
     * there is no `game_mkhighlight`.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]
        setColour(ret, COL_UNMARKED, 148f / 255f, 196f / 255f, 190f / 255f)
        setColour(ret, COL_GRID, 0f, 102f / 255f, 99f / 255f)
        setColour(ret, COL_MARKED, 20f / 255f, 20f / 255f, 20f / 255f)
        setColour(ret, COL_BLANK, 236f / 255f, 236f / 255f, 236f / 255f)
        setColour(ret, COL_TEXT_SOLVED, 100f / 255f, 100f / 255f, 100f / 255f)
        setColour(ret, COL_ERROR, 1f, 0f, 0f)
        setColour(ret, COL_CURSOR, 255f / 255f, 200f / 255f, 200f / 255f)
        return ret
    }

    private fun setColour(ret: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
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
     * `game_redraw` from `mosaic.c:1508-1563`. The loop covers the
     * margin column and row. The cache word holds the cell value, the
     * four cursor bits, and the two margin bits, so a warm frame emits
     * only the cells whose word changed. The seam has no flash time, so
     * the flash XOR is not applied.
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

        for (y in 0..h) {
            for (x in 0..w) {
                val inbounds = x < w && y < h
                var cell = if (inbounds) s.cells[y * w + x] else 0
                if (x == w) cell = cell or DRAWFLAG_MARGIN_R
                if (y == h) cell = cell or DRAWFLAG_MARGIN_D
                if (u.curVisible) {
                    if (u.curX == x && u.curY == y) cell = cell or DRAWFLAG_CURSOR
                    if (u.curX == x - 1 && u.curY == y) cell = cell or DRAWFLAG_CURSOR_L
                    if (u.curX == x && u.curY == y - 1) cell = cell or DRAWFLAG_CURSOR_U
                    if (u.curX == x - 1 && u.curY == y - 1) cell = cell or DRAWFLAG_CURSOR_UL
                }

                val clue = if (inbounds && s.shared.shown[y * w + x]) {
                    s.shared.clue[y * w + x]
                } else {
                    -1
                }

                val cacheIndex = y * (w + 1) + x
                if (ds.cells[cacheIndex] != cell) {
                    drawCell(sink, ds, cell, clue, x, y)
                    ds.cells[cacheIndex] = cell
                }
            }
        }
    }

    /**
     * `draw_cell` from `mosaic.c:1460-1506`, in C call order. `MARKED`
     * is tested before `BLANK`, so a cell with both bits fills black.
     * An error text overrides a solved text.
     */
    private fun drawCell(
        sink: DrawSink,
        ds: DrawState,
        cell: Int,
        clueVal: Int,
        x: Int,
        y: Int,
    ) {
        val ts = ds.tileSize
        val startX = x * ts + ts / 2 - 1
        val startY = y * ts + ts / 2 - 1

        sink.clip(startX - 1, startY - 1, ts, ts)
        if (cell and DRAWFLAG_MARGIN_R == 0) {
            sink.rect(
                startX - 1,
                startY - 1,
                ts,
                1,
                if (cell and (DRAWFLAG_CURSOR or DRAWFLAG_CURSOR_U) != 0) COL_CURSOR else COL_GRID,
            )
        }
        if (cell and DRAWFLAG_MARGIN_D == 0) {
            sink.rect(
                startX - 1,
                startY - 1,
                1,
                ts,
                if (cell and (DRAWFLAG_CURSOR or DRAWFLAG_CURSOR_L) != 0) COL_CURSOR else COL_GRID,
            )
        }
        if (cell and DRAWFLAG_CURSOR_UL != 0) {
            sink.rect(startX - 1, startY - 1, 1, 1, COL_CURSOR)
        }

        if (cell and (DRAWFLAG_MARGIN_R or DRAWFLAG_MARGIN_D) == 0) {
            val color: Int
            var textColor = COL_TEXT_DARK
            if (cell and STATE_MARKED != 0) {
                color = COL_MARKED
                textColor = COL_TEXT_LIGHT
            } else if (cell and STATE_BLANK != 0) {
                textColor = COL_TEXT_DARK
                color = COL_BLANK
            } else {
                textColor = COL_TEXT_DARK
                color = COL_UNMARKED
            }
            if (cell and STATE_ERROR != 0) {
                textColor = COL_ERROR
            } else if (cell and STATE_SOLVED != 0) {
                textColor = COL_TEXT_SOLVED
            }

            sink.rect(startX, startY, ts - 1, ts - 1, color)
            if (clueVal >= 0) {
                sink.text(
                    startX + ts / 2,
                    startY + ts / 2,
                    Text.FONT_VARIABLE,
                    ts * 3 / 5,
                    Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                    textColor,
                    clueVal.toString(),
                )
            }
        }

        sink.unclip()
        sink.update(startX - 1, startY - 1, ts, ts)
    }

    /**
     * `interpret_move` from `mosaic.c:1053-1215`, in C branch order.
     * After completion every non-arrow key is rejected. A press cycles
     * the value, a drag and a release paint a straight line with the
     * press value, and an arrow moves the cursor.
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

        if (s.notCompletedClues == 0 && !Buttons.isCursorMove(button)) {
            return MoveResult.Unused
        }

        val offsetX = x - tileSize / 2
        val offsetY = y - tileSize / 2
        val gameX = offsetX / tileSize
        val gameY = offsetY / tileSize
        if ((Buttons.isMouseDown(button) || Buttons.isMouseDrag(button) ||
                Buttons.isMouseRelease(button)) &&
            (offsetX < 0 || offsetY < 0)
        ) {
            return MoveResult.Unused
        }

        if (button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON) {
            val index = indexOrNull(s, gameX, gameY)
            if (index != null) {
                u.lastState = s.cells[index] and (STATE_BLANK or STATE_MARKED)
                u.lastState = (u.lastState +
                    if (button == Buttons.RIGHT_BUTTON) 2 else 1) % (STATE_BLANK or STATE_MARKED)
            }
            val moveType = if (button == Buttons.RIGHT_BUTTON) 'T' else 't'
            var result: MoveResult = MoveResult.Unused
            if (gameX >= 0 && gameY >= 0 && gameX < s.width && gameY < s.height) {
                u.lastX = gameX
                u.lastY = gameY
                result = MoveResult.Move("$moveType$gameX,$gameY")
            } else {
                u.lastX = -1
                u.lastY = -1
            }
            u.curVisible = false
            return result
        }

        val srcX = u.lastX
        val srcY = u.lastY

        if (button == Buttons.LEFT_DRAG || button == Buttons.RIGHT_DRAG) {
            var result: MoveResult = MoveResult.Unused
            if (gameX >= 0 && gameY >= 0 && gameX < s.width && gameY < s.height &&
                u.lastX >= 0 && u.lastY >= 0 &&
                (gameY == u.lastY || gameX == u.lastX)
            ) {
                val moveDesc = "d$gameX,$gameY,${u.lastX},${u.lastY},${u.lastState}"
                val dir = direction(gameX, gameY, srcX, srcY)
                var changed = false
                for (i in 0 until dir.diff) {
                    val index = indexOrNull(
                        s,
                        gameX + dir.dirX * i,
                        gameY + dir.dirY * i,
                    )
                    if (index != null && s.cells[index] and STATE_OK_NUM == 0 &&
                        u.lastState > 0
                    ) {
                        changed = true
                        break
                    }
                }
                u.lastX = gameX
                u.lastY = gameY
                if (changed) result = MoveResult.Move(moveDesc)
            } else {
                u.lastX = -1
                u.lastY = -1
            }
            u.curVisible = false
            return result
        }

        if (button == Buttons.LEFT_RELEASE || button == Buttons.RIGHT_RELEASE) {
            var result: MoveResult = MoveResult.Unused
            if (gameX >= 0 && gameY >= 0 && gameX < s.width && gameY < s.height &&
                u.lastX >= 0 && u.lastY >= 0 &&
                (gameY == u.lastY || gameX == u.lastX)
            ) {
                val moveDesc = "e$gameX,$gameY,${u.lastX},${u.lastY},${u.lastState}"
                val dir = direction(gameX, gameY, srcX, srcY)
                var changed = false
                for (i in 0 until dir.diff) {
                    val index = indexOrNull(
                        s,
                        gameX + dir.dirX * i,
                        gameY + dir.dirY * i,
                    )
                    if (index != null && s.cells[index] and STATE_OK_NUM == 0 &&
                        u.lastState > 0
                    ) {
                        changed = true
                        break
                    }
                }
                if (changed) result = MoveResult.Move(moveDesc)
            } else {
                u.lastX = -1
                u.lastY = -1
            }
            u.curVisible = false
            return result
        }

        if (Buttons.isCursorMove(button)) {
            val oldX = u.curX
            val oldY = u.curY
            var dx = 0
            var dy = 0
            when (button) {
                Buttons.CURSOR_UP -> dy = -1
                Buttons.CURSOR_DOWN -> dy = 1
                Buttons.CURSOR_RIGHT -> dx = 1
                Buttons.CURSOR_LEFT -> dx = -1
            }
            u.curX = minOf(maxOf(u.curX + dx, 0), s.width - 1)
            u.curY = minOf(maxOf(u.curY + dy, 0), s.height - 1)
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            if (u.curX != oldX || u.curY != oldY) return MoveResult.UiUpdate
            return MoveResult.NoEffect
        }

        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curX = 0
                u.curY = 0
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            return if (button == Buttons.CURSOR_SELECT2) {
                MoveResult.Move("T${u.curX},${u.curY}")
            } else {
                MoveResult.Move("t${u.curX},${u.curY}")
            }
        }

        return MoveResult.Unused
    }

    private class Direction(val dirX: Int, val dirY: Int, val diff: Int)

    /** The direction formula shared by the UI and `execute_move`. */
    private fun direction(x: Int, y: Int, srcX: Int, srcY: Int): Direction {
        if (srcX == x && srcY != y) {
            var d = srcY - y
            val dirY: Int
            if (d < 0) {
                dirY = -1
                d = -d
            } else {
                dirY = 1
            }
            return Direction(0, dirY, d)
        }
        var d = srcX - x
        val dirX: Int
        if (d < 0) {
            dirX = -1
            d = -d
        } else {
            dirX = 1
        }
        return Direction(dirX, 0, d)
    }

    /** `get_coords`: the flat index, or null outside the board. */
    private fun indexOrNull(state: State, x: Int, y: Int): Int? {
        if (x < 0 || y < 0 || x >= state.width || y >= state.height) return null
        return y * state.width + x
    }

    private const val HEX = "0123456789abcdef"

    /**
     * C `atoi` with the `int` conversion. A value outside `long`
     * saturates, then the low 32 bits are kept, so `4294967295` is -1
     * and `4294967296` is 0.
     */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++

        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }

        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            val digit = text[i] - '0'
            if (!overflow) {
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
        return parsed.toInt()
    }

    /** The white space set of C `isspace` in the C locale. */
    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
