package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `unruly.c`.
 *
 * Unruly is a binary puzzle: fill every cell with 0 or 1 so that no
 * three equal values are in a line and every row and column holds as
 * many zeros as ones. The optional `unique` flag also forbids two equal
 * full rows or two equal full columns.
 *
 * The description is a run-length form of the clue board: a letter is a
 * run of empty cells followed by one clue, `a`..`y`/`A`..`Y` carries
 * the run length, and `z`/`Z` is a run of 25 empty cells with no clue.
 * A move is `P<c>,<x>,<y>` or `S` followed by one digit per cell. The
 * deduction solver is in [UnrulySolver] and is also the generator's
 * completeness test, so the two files share one risk: a solver
 * difference changes every description and every `SOLVE` record.
 *
 * C reads the first state for `solve_game`, because `midend_solve`
 * passes `states[0]`, so this port keeps the decoded initial grid in
 * [Shared] and solves that, not the current board.
 *
 * There is no `AUXINFO` and no `UI` record, no preference dialog, and no
 * blitter. The completion flash is not ported: the seam has no flash
 * time, and the oracle's `allow_flash` stub returns 0, so no parity test
 * contains a flash frame.
 */
object Unruly : Game {

    private const val PREFERRED_TILE_SIZE = 32

    /** Cell values and flag bits from `unruly.c:114-140`. */
    private const val EMPTY = UnrulySolver.EMPTY
    private const val N_ONE = UnrulySolver.N_ONE
    private const val N_ZERO = UnrulySolver.N_ZERO

    internal const val FE_HOR_ROW_LEFT = 0x0001
    internal const val FE_HOR_ROW_MID = 0x0003
    internal const val FE_HOR_ROW_RIGHT = 0x0002

    internal const val FE_VER_ROW_TOP = 0x0004
    internal const val FE_VER_ROW_MID = 0x000C
    internal const val FE_VER_ROW_BOTTOM = 0x0008

    internal const val FE_COUNT = 0x0010

    internal const val FE_ROW_MATCH = 0x0020
    internal const val FE_COL_MATCH = 0x0040

    private const val FF_ONE = 0x0080
    private const val FF_ZERO = 0x0100
    private const val FF_CURSOR = 0x0200

    private const val FF_FLASH1 = 0x0400
    private const val FF_FLASH2 = 0x0800
    private const val FF_IMMUTABLE = 0x1000

    /** The colour enum from `unruly.c:62-80`. */
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_EMPTY = 2
    private const val COL_0 = 3
    private const val COL_0_HIGHLIGHT = 4
    private const val COL_0_LOWLIGHT = 5
    private const val COL_1 = 6
    private const val COL_1_HIGHLIGHT = 7
    private const val COL_1_LOWLIGHT = 8
    private const val COL_CURSOR = 9
    private const val COL_ERROR = 10
    private const val NCOLOURS = 11

    /** `unruly_diffchars` and `unruly_diffnames` from `unruly.c:87-100`. */
    private const val DIFF_CHARS = "ten"
    private val DIFF_TITLES = listOf("Trivial", "Easy", "Normal")

    /** `DIFF_TRIVIAL`, `DIFF_EASY`, `DIFF_NORMAL`, and `DIFFCOUNT`. */
    private const val DIFF_TRIVIAL = UnrulySolver.DIFF_TRIVIAL
    private const val DIFFCOUNT = UnrulySolver.DIFFCOUNT

    /**
     * `struct game_params`. `width` and `height` are upstream `w2` and
     * `h2`, the full grid size; both are even after validation.
     */
    data class Params(
        val width: Int,
        val height: Int,
        val unique: Boolean,
        val diff: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(width, height, unique, diff)
    }

    /**
     * `struct unruly_common`: the immutable clue mask and the decoded
     * initial grid. Every state of one game shares one instance, as C
     * shares `unruly_common` and counts its references.
     *
     * [initialGrid] is a port shape, not a C field: `solve_game` runs on
     * `states[0]`, which the midend never mutates. Keeping that grid
     * here gives C's answer after the user has changed the board.
     */
    class Shared internal constructor(
        val width: Int,
        val height: Int,
        val unique: Boolean,
        /** The clue mask. Do not modify it. */
        val immutable: BooleanArray,
        /** The grid after `newState`. Do not modify it. */
        val initialGrid: IntArray,
    ) {
        val size: Int get() = width * height
    }

    /**
     * `struct game_state`. [grid] is copied per state, as `dup_game`
     * copies it. [completed] is sticky, and only an `S` move sets
     * [cheated].
     */
    class State(
        val shared: Shared,
        val grid: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val width: Int get() = shared.width

        val height: Int get() = shared.height

        fun valueAt(x: Int, y: Int): Int = grid[y * width + x]

        override fun copy(): Game.State = State(shared, grid.copyOf(), completed, cheated)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                shared.unique == other.shared.unique &&
                grid.contentEquals(other.grid) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + shared.unique.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. The cursor is inside the grid at all times:
     * `move_cursor` clamps it, and a click assigns it only in bounds.
     * A click hides the ring and moves the cursor to the clicked cell
     * (`ANDROID`), so the next arrow key starts from there.
     */
    class Ui : Game.Ui {
        var curX = 0
        var curY = 0
        var curVisible = false
    }

    /**
     * `struct game_drawstate`: a per-cell flag word, the `started`
     * frame flag, and the two scratch arrays. There is no blitter.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val gridfs: IntArray = IntArray(width * height)
        val rowfs: BooleanArray = BooleanArray(2 * (width + height))
        val grid: IntArray = IntArray(width * height) { -1 }
    }

    override val name: String = "Unruly"
    override val backendName: String = "unruly"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    // `unruly.c` has no `SMALL_SCREEN` conditional, so the app and the
    // oracle expose the same seven presets.
    private val presetValues = listOf(
        Params(8, 8, false, DIFF_TRIVIAL),
        Params(8, 8, false, 1),
        Params(8, 8, false, 2),
        Params(10, 10, false, 1),
        Params(10, 10, false, 2),
        Params(14, 14, false, 1),
        Params(14, 14, false, 2),
    )

    /**
     * `A177790` from `unruly.c:296-315`: the number of distinct Unruly
     * lines of length `2n`, for as long as the number fits in a signed
     * 32-bit `int`.
     */
    private val A177790 = longArrayOf(
        1L, 2L, 6L, 14L, 34L, 84L, 208L, 518L, 1296L, 3254L,
        8196L, 20700L, 52404L, 132942L, 337878L, 860142L,
        2192902L, 5598144L, 14308378L, 36610970L, 93770358L,
        240390602L, 616787116L, 1583765724L,
    )

    /** `default_params`: preset 0, `8x8` Trivial. */
    override fun defaultParams(): Game.Params = Params(8, 8, false, DIFF_TRIVIAL)

    /** `game_fetch_preset`: `%dx%d %s`, and the full parameter encoding. */
    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.width}x${it.height} ${DIFF_TITLES[it.diff]}", encodeParams(it, true))
    }

    /**
     * `decode_params`: `atoi`, skip digits, an optional lower-case `x`
     * and a second `atoi`, an optional `u`, then an optional `d` and one
     * difficulty character. `diff` starts at the value `default_params`
     * gives, because Kotlin decodes into a fresh struct.
     *
     * The quirks are part of the format: `8`, `8x8x8`, `8X8`, and a
     * trailing space all mean `8x8dt`; `8u8` means `8x8udt`; `8x8d` and
     * `8x8dx` decode to an invalid difficulty and fail validation.
     */
    override fun decodeParams(text: String): Game.Params {
        var unique = false
        val width = atoi(text, 0)
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++

        val height: Int
        if (position < text.length && text[position] == 'x') {
            height = atoi(text, position + 1)
            position++
            while (position < text.length && text[position] in '0'..'9') position++
        } else {
            height = width
        }

        if (position < text.length && text[position] == 'u') {
            position++
            unique = true
        }

        var diff = DIFF_TRIVIAL
        if (position < text.length && text[position] == 'd') {
            position++
            diff = DIFFCOUNT + 1
            if (position < text.length) {
                for (i in 0 until DIFFCOUNT) {
                    if (text[position] == DIFF_CHARS[i]) diff = i
                }
                position++
            }
        }

        return Params(width, height, unique, diff)
    }

    /**
     * `encode_params`: `WxH`, `u` when unique, and `d<c>` only when
     * [full] is true. The short form therefore drops the difficulty,
     * which is why the golden `PARAMS` record says `8x8dt` while
     * `CPARAMS` says `8x8dn`.
     *
     * C indexes `unruly_diffchars[diff]` with no bound. Index
     * `DIFFCOUNT` reads the array's NUL terminator, so the suffix is
     * plain `d`; a larger or negative index reads out of bounds. The
     * port writes the same `d` for every value outside `0..2` instead of
     * reading past the array. Validation runs before encoding in the
     * midend, so this guard is not reachable from a loaded save.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.width).append('x').append(p.height)
        if (p.unique) out.append('u')
        if (full) {
            out.append('d')
            if (p.diff in 0 until DIFFCOUNT) out.append(DIFF_CHARS[p.diff])
        }
        return out.toString()
    }

    /**
     * `validate_params`, in C order. The area test stays
     * `width > INT_MAX / height` so the boundary is the C one: an even
     * width and height product of `Int.MAX_VALUE` is impossible, and
     * `46340x46340` is accepted. No test generates that board.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if ((p.width and 1) != 0 || (p.height and 1) != 0) {
            return "Width and height must both be even"
        }
        if (p.width < 6 || p.height < 6) {
            return "Width and height must be at least 6"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.unique) {
            if (p.width < 2 * A177790.size && p.height > A177790[p.width / 2]) {
                return "Puzzle is too tall for unique-rows mode"
            }
            if (p.height < 2 * A177790.size && p.width > A177790[p.height / 2]) {
                return "Puzzle is too long for unique-rows mode"
            }
        }
        if (p.diff >= DIFFCOUNT) return "Unknown difficulty rating"
        return null
    }

    /** `game_configure` (`unruly.c:243-273`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Bool("Unique rows and columns", p.unique),
            ConfigItem.Choices(
                "Difficulty",
                ":Trivial:Easy:Normal",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`unruly.c:275-285`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Bool).value,
            (items[3] as ConfigItem.Choices).selected,
        )

    /** `new_game_desc`: fill, winnow, and retry while the puzzle is too easy. */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(
            UnrulySolver.generate(p.width, p.height, p.unique, p.diff, random),
        )
    }

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        return Description(
            UnrulySolver.generate(p.width, p.height, p.unique, p.diff, random, cancel),
        )
    }

    // `unruly.c` retries the clue placement with no retry bound and accepts a 46340x46340 area; the largest preset is 14x14.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.width.toLong() * p.height > 196) Game.SLOW_GENERATION_WARNING else null
    }

    /**
     * `validate_desc`: sum the run lengths and compare the total with
     * `s + 1`. The comparison is exact, so the last letter is a
     * terminator. Values are not checked, and `unique` is not checked.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val s = p.width * p.height
        var pos = 0
        for (ch in desc) {
            when {
                ch in 'a'..'y' -> pos += 1 + (ch - 'a')
                ch in 'A'..'Y' -> pos += 1 + (ch - 'A')
                ch == 'z' || ch == 'Z' -> pos += 25
                else -> return "Description contains invalid characters"
            }
        }
        if (pos < s + 1) return "Description too short"
        if (pos > s + 1) return "Description too long"
        return null
    }

    /**
     * `new_game`: decode the clues and mark them immutable. `completed`
     * is not computed, so a description that is already a valid board
     * starts open.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.width
        val h = p.height
        val s = w * h
        val grid = IntArray(s)
        val immutable = BooleanArray(s)
        var pos = 0

        for (ch in desc) {
            when {
                ch in 'a'..'y' -> {
                    pos += ch - 'a'
                    if (pos < s) {
                        grid[pos] = N_ZERO
                        immutable[pos] = true
                    }
                    pos++
                }

                ch in 'A'..'Y' -> {
                    pos += ch - 'A'
                    if (pos < s) {
                        grid[pos] = N_ONE
                        immutable[pos] = true
                    }
                    pos++
                }

                ch == 'z' || ch == 'Z' -> pos += 25
                else -> error("Description contains invalid characters")
            }
        }
        check(pos == s + 1) { "Description covers $pos cells, not ${s + 1}" }

        return State(
            shared = Shared(w, h, p.unique, immutable, grid.copyOf()),
            grid = grid,
            completed = false,
            cheated = false,
        )
    }

    /**
     * `solve_game`. C reads the first state, the decoded description, so
     * the port reads [Shared.initialGrid] rather than the current grid.
     * The difficulty is ignored: the solve always runs at `DIFFCOUNT`.
     * `aux` is never read.
     *
     * C sets one of two error strings and returns NULL; the seam has no
     * error channel, so a stall or an invalid board returns null. The
     * seven presets always solve, so the two messages are reachable only
     * through a hand-written description.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /**
     * `solve_game` (`unruly.c:1274-1300`). A result of 1 is a stalled
     * board and a result of -1 is an invalid board, with the two C
     * messages.
     */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        val w = s.width
        val h = s.height
        val unique = s.shared.unique
        val solved = s.shared.initialGrid.copyOf()
        val scratch = UnrulySolver.newScratch(solved, w, h)
        UnrulySolver.solveGame(solved, w, h, unique, scratch, DIFFCOUNT)

        var result = UnrulySolver.validateCounts(solved, w, h, scratch, null)
        if (UnrulySolver.validateAllRows(solved, w, h, unique, null) == -1) {
            result = -1
        }
        if (result != 0) {
            return SolveOutcome(
                null,
                if (result == 1) "No solution found." else "Puzzle is invalid.",
            )
        }

        val out = StringBuilder(solved.size + 1)
        out.append('S')
        for (value in solved) out.append(if (value == N_ONE) '1' else '0')
        return SolveOutcome(out.toString(), null)
    }

    /**
     * `execute_move`. The `S` form reads exactly `s` characters; later
     * characters are ignored, clues are overwritten, and the flags are
     * set without a completion test. The `P` form parses with the
     * `sscanf("%c,%d,%d")` rules: white space and a sign are accepted
     * for the coordinates, trailing junk is ignored, and a clue target
     * is rejected.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        if (move.isEmpty()) return null

        if (move[0] == 'S') {
            val size = s.grid.size
            if (move.length < size + 1) return null
            val next = s.copy() as State
            for (i in 0 until size) {
                val c = move[i + 1]
                if (c != '0' && c != '1') return null
                next.grid[i] = if (c == '1') N_ONE else N_ZERO
            }
            next.completed = true
            next.cheated = true
            return next
        }

        if (move[0] != 'P') return null
        val parsed = parsePlaceMove(move) ?: return null
        if (parsed.x < 0 || parsed.x >= s.width || parsed.y < 0 || parsed.y >= s.height) {
            return null
        }
        if (parsed.c != '-' && parsed.c != '0' && parsed.c != '1') return null

        val i = parsed.y * s.width + parsed.x
        if (s.shared.immutable[i]) return null

        val next = s.copy() as State
        next.grid[i] = when (parsed.c) {
            '1' -> N_ONE
            '0' -> N_ZERO
            else -> EMPTY
        }

        if (!next.completed &&
            UnrulySolver.validateCounts(next.grid, s.width, s.height, null, null) == 0 &&
            UnrulySolver.validateAllRows(next.grid, s.width, s.height, s.shared.unique, null) == 0
        ) {
            next.completed = true
        }
        return next
    }

    /** `game_status`: 1 when complete, otherwise 0. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /** `wants_statusbar` is false, so the app must show no status text. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /**
     * `game_text_format`: one character per cell, a space after each,
     * and a newline after each row. A clue and a user value look alike.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val out = StringBuilder((2 * w + 1) * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val value = s.grid[y * w + x]
                out.append(if (value == N_ONE) '1' else if (value == N_ZERO) '0' else '.')
                out.append(' ')
            }
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * `game_changed_state`: report an honest completion only. A solve
     * is cheated and raises nothing; a redo into the completed state
     * raises the event again, as C does.
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

    /**
     * `game_request_keys` is NULL in C, so the midend reports no labels
     * and keeps the default arrow mode `ANDROID_ARROWS_LEFT_RIGHT`.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /** `game_compute_size`: one tile larger than the grid on each axis. */
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
     * `game_get_cursor_location`: a rectangle only for a visible cursor.
     * C writes nothing otherwise, and the seam reports that with null.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        return Game.CursorRect(
            u.curX * tileSize + tileSize / 2,
            u.curY * tileSize + tileSize / 2,
            tileSize,
            tileSize,
        )
    }

    /**
     * `current_key_label`. C reads the grid and the mask before it tests
     * the cursor; the result is the same because it returns "" for a
     * hidden cursor. There is no seam hook yet (S9), so this is a public
     * helper, as in `Flood`.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        val index = u.curY * s.width + u.curX
        if (index < 0 || index >= s.grid.size) return ""
        val value = s.grid[index]
        if (u.curVisible &&
            (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2)
        ) {
            if (s.shared.immutable[index]) return ""
            when (value) {
                EMPTY -> return if (button == Buttons.CURSOR_SELECT) "Black" else "White"
                N_ONE -> return if (button == Buttons.CURSOR_SELECT) "White" else "Empty"
                N_ZERO -> return if (button == Buttons.CURSOR_SELECT) "Empty" else "Black"
            }
        }
        return ""
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: `empty`, `black`, or `white`, and `, given`
     * on a clue the player cannot change. The pointer buttons cycle the
     * cell the way `current_key_label` words it; a given cell takes no
     * pointer input.
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
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * s.width + x
                val colour = when (s.grid[index]) {
                    N_ONE -> "black"
                    N_ZERO -> "white"
                    else -> "empty"
                }
                val given = if (s.shared.immutable[index]) ", given" else ""
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) ", cursor" else ""
                colour + given + cursor
            },
            actions = { x, y ->
                val index = y * s.width + x
                if (s.shared.immutable[index]) {
                    emptyList()
                } else {
                    when (s.grid[index]) {
                        N_ONE -> listOf(
                            SpokenAction("White", Buttons.LEFT_BUTTON),
                            SpokenAction("Empty", Buttons.RIGHT_BUTTON),
                        )

                        N_ZERO -> listOf(
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
     * `game_colours`. `game_mkhighlight_specific` rewrites the base
     * colour when it is closer than `sqrt(3)/6` to white, so `COL_0`
     * becomes 0.8333 rather than 0.95 and `COL_1` keeps 0.23. The two
     * derived triples therefore come from [Colour.mkHighlight], not from
     * literals.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]

        for (i in 0..2) {
            ret[COL_1 * 3 + i] = 0.23f
            ret[COL_1_HIGHLIGHT * 3 + i] = 0.6f
            ret[COL_1_LOWLIGHT * 3 + i] = 0.0f
            ret[COL_0 * 3 + i] = 0.95f
            ret[COL_0_HIGHLIGHT * 3 + i] = 1.0f
            ret[COL_0_LOWLIGHT * 3 + i] = 0.9f
            ret[COL_EMPTY * 3 + i] = 0.5f
            ret[COL_GRID * 3 + i] = 0.3f
        }

        Colour.mkHighlight(ret, COL_0, COL_0_HIGHLIGHT, COL_0_LOWLIGHT)
        Colour.mkHighlight(ret, COL_1, COL_1_HIGHLIGHT, COL_1_LOWLIGHT)

        ret[COL_ERROR * 3] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_CURSOR * 3] = 0.0f
        ret[COL_CURSOR * 3 + 1] = 0.7f
        ret[COL_CURSOR * 3 + 2] = 0.0f

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

    /**
     * `game_redraw`. The error words are rebuilt from the whole grid on
     * every frame; the per-cell cache suppresses a tile whose word did
     * not change, so a warm frame emits nothing. The seam has no flash
     * time, so `flash` is always 0.
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
                coord(0, tileSize) - tileSize / 10,
                coord(0, tileSize) - tileSize / 10,
                tileSize * w + 2 * (tileSize / 10) - 1,
                tileSize * h + 2 * (tileSize / 10) - 1,
                COL_GRID,
            )
            sink.update(0, 0, tileSize * (w + 1), tileSize * (h + 1))
            ds.started = true
        }

        val flash = 0

        ds.gridfs.fill(0)
        UnrulySolver.validateAllRows(s.grid, w, h, s.shared.unique, ds.gridfs)
        ds.rowfs.fill(false)
        UnrulySolver.validateCounts(s.grid, w, h, null, ds.rowfs)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var tile = ds.gridfs[i]

                if (s.grid[i] == N_ONE) {
                    tile = tile or FF_ONE
                    if (ds.rowfs[y] || ds.rowfs[2 * h + x]) tile = tile or FE_COUNT
                } else if (s.grid[i] == N_ZERO) {
                    tile = tile or FF_ZERO
                    if (ds.rowfs[h + y] || ds.rowfs[2 * h + w + x]) {
                        tile = tile or FE_COUNT
                    }
                }

                tile = tile or flash

                if (s.shared.immutable[i]) tile = tile or FF_IMMUTABLE

                if (u.curVisible && u.curX == x && u.curY == y) tile = tile or FF_CURSOR

                if (ds.grid[i] != tile) {
                    ds.grid[i] = tile
                    drawTile(sink, coord(x, tileSize), coord(y, tileSize), tileSize, tile)
                }
            }
        }
    }

    /** `COORD(x)` from `unruly.c:1606`. */
    private fun coord(x: Int, tileSize: Int): Int = x * tileSize + tileSize / 2

    /** `FROMCOORD(x)` from `unruly.c:1607`, with C truncation. */
    private fun fromCoord(x: Int, tileSize: Int): Int = (x - tileSize / 2) / tileSize

    /** `unruly_draw_err_rectangle` from `unruly.c:1789-1799`. */
    private fun drawErrRectangle(
        sink: DrawSink,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        tileSize: Int,
    ) {
        val thick = tileSize / 10
        val margin = tileSize / 20

        sink.rect(x + margin, y + margin, w - 2 * margin, thick, COL_ERROR)
        sink.rect(x + margin, y + margin, thick, h - 2 * margin, COL_ERROR)
        sink.rect(x + margin, y + h - margin - thick, w - 2 * margin, thick, COL_ERROR)
        sink.rect(x + w - margin - thick, y + margin, thick, h - 2 * margin, COL_ERROR)
    }

    /** `unruly_draw_tile` from `unruly.c:1801-1878`, in C call order. */
    private fun drawTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        tileSize: Int,
        tile: Int,
    ) {
        sink.clip(x, y, tileSize, tileSize)

        // The grid edge goes first and the tile overwrites it.
        sink.rect(x, y, tileSize, tileSize, COL_GRID)

        var value = if (tile and FF_ZERO != 0) {
            COL_0
        } else if (tile and FF_ONE != 0) {
            COL_1
        } else {
            COL_EMPTY
        }

        if (tile and (FF_FLASH1 or FF_FLASH2) != 0 &&
            (value == COL_0 || value == COL_1)
        ) {
            value += if (tile and FF_FLASH1 != 0) 1 else 2
        }

        sink.rect(x, y, tileSize - 1, tileSize - 1, value)

        if ((value == COL_0 || value == COL_1) && tile and FF_IMMUTABLE != 0) {
            sink.rect(
                x + tileSize / 6,
                y + tileSize / 6,
                tileSize - 2 * (tileSize / 6) - 2,
                2,
                value + 2,
            )
            sink.rect(
                x + tileSize / 6,
                y + tileSize / 6,
                2,
                tileSize - 2 * (tileSize / 6) - 2,
                value + 2,
            )
            sink.rect(
                x + tileSize / 6 + 1,
                y + tileSize - tileSize / 6 - 2,
                tileSize - 2 * (tileSize / 6) - 2,
                2,
                value + 1,
            )
            sink.rect(
                x + tileSize - tileSize / 6 - 2,
                y + tileSize / 6 + 1,
                2,
                tileSize - 2 * (tileSize / 6) - 2,
                value + 1,
            )
        }

        if (tile and (FE_HOR_ROW_LEFT or FE_HOR_ROW_RIGHT) != 0) {
            var left = x
            var right = x + tileSize - 1
            if (tile and FE_HOR_ROW_LEFT != 0) right += tileSize / 2
            if (tile and FE_HOR_ROW_RIGHT != 0) left -= tileSize / 2
            drawErrRectangle(sink, left, y, right - left, tileSize - 1, tileSize)
        }
        if (tile and (FE_VER_ROW_TOP or FE_VER_ROW_BOTTOM) != 0) {
            var top = y
            var bottom = y + tileSize - 1
            if (tile and FE_VER_ROW_TOP != 0) bottom += tileSize / 2
            if (tile and FE_VER_ROW_BOTTOM != 0) top -= tileSize / 2
            drawErrRectangle(sink, x, top, tileSize - 1, bottom - top, tileSize)
        }

        if (tile and FE_COUNT != 0) {
            sink.text(
                x + tileSize / 2,
                y + tileSize / 2,
                Text.FONT_VARIABLE,
                tileSize / 2,
                Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                COL_ERROR,
                "!",
            )
        }

        if (tile and FE_ROW_MATCH != 0) {
            sink.rect(x, y + tileSize / 2 - tileSize / 12, tileSize, 2 * (tileSize / 12), COL_ERROR)
        }
        if (tile and FE_COL_MATCH != 0) {
            sink.rect(x + tileSize / 2 - tileSize / 12, y, 2 * (tileSize / 12), tileSize, COL_ERROR)
        }

        if (tile and FF_CURSOR != 0) {
            sink.rect(x, y, tileSize / 12, tileSize - 1, COL_CURSOR)
            sink.rect(x, y, tileSize - 1, tileSize / 12, COL_CURSOR)
            sink.rect(x + tileSize - 1 - tileSize / 12, y, tileSize / 12, tileSize - 1, COL_CURSOR)
            sink.rect(x, y + tileSize - 1 - tileSize / 12, tileSize - 1, tileSize / 12, COL_CURSOR)
        }

        sink.unclip()
        sink.update(x, y, tileSize, tileSize)
    }

    /**
     * `interpret_move`, in C branch order. The modifiers are stripped
     * first, so a modified arrow key still moves the cursor and a keypad
     * digit still places.
     *
     * A click places a value, hides the cursor, and (under `ANDROID`)
     * leaves the cursor on the clicked cell, so `MOVE_UI_UPDATE` versus
     * `MOVE_NO_EFFECT` depends on the old cursor visibility. C indexes
     * the grid and the mask with the cursor or the clicked cell; both
     * are in bounds here. The index guard below is a defined port guard
     * for a caller that sets [Ui] fields directly, and is unreachable
     * through the midend.
     */
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

        var hx = u.curX
        var hy = u.curY
        val gx = fromCoord(x, tileSize)
        val gy = fromCoord(y, tileSize)
        var nullret: MoveResult = MoveResult.NoEffect

        val button = Buttons.stripModifiers(button0)

        // Mouse press. A drag or a release never reaches this branch.
        if (button == Buttons.LEFT_BUTTON ||
            button == Buttons.RIGHT_BUTTON ||
            button == Buttons.MIDDLE_BUTTON
        ) {
            if (x >= tileSize / 2 && gx < w && y >= tileSize / 2 && gy < h) {
                hx = gx
                hy = gy
                // ANDROID moves the cursor to the clicked cell.
                u.curX = gx
                u.curY = gy
                if (u.curVisible) {
                    u.curVisible = false
                    nullret = MoveResult.UiUpdate
                }
            } else {
                return MoveResult.Unused
            }
        }

        if (Buttons.isCursorMove(button)) return moveCursor(button, u, w, h)

        val placeKey = u.curVisible &&
            (button == Buttons.CURSOR_SELECT ||
                button == Buttons.CURSOR_SELECT2 ||
                button == '\b'.code ||
                button == '0'.code ||
                button == '1'.code ||
                button == '2'.code)
        if (placeKey ||
            button == Buttons.LEFT_BUTTON ||
            button == Buttons.RIGHT_BUTTON ||
            button == Buttons.MIDDLE_BUTTON
        ) {
            val index = hy * w + hx
            if (index < 0 || index >= s.grid.size) return nullret
            if (s.shared.immutable[index]) return nullret

            val current = s.grid[index]
            var c = '-'
            if (button == '0'.code || button == '2'.code) {
                c = '0'
            } else if (button == '1'.code) {
                c = '1'
            } else if (button == Buttons.MIDDLE_BUTTON) {
                c = '-'
            } else if (button == Buttons.CURSOR_SELECT2 || button == Buttons.RIGHT_BUTTON) {
                c = when (current) {
                    EMPTY -> '0'
                    N_ZERO -> '1'
                    else -> '-'
                }
            } else if (button == Buttons.CURSOR_SELECT || button == Buttons.LEFT_BUTTON) {
                c = when (current) {
                    EMPTY -> '1'
                    N_ONE -> '0'
                    else -> '-'
                }
            }

            val target = when (c) {
                '0' -> N_ZERO
                '1' -> N_ONE
                else -> EMPTY
            }
            if (current == target) return nullret
            return MoveResult.Move("P$c,$hx,$hy")
        }
        return MoveResult.Unused
    }

    /**
     * `move_cursor` from `misc.c:366-396` with `wrap = false`: a hidden
     * cursor becomes visible and reports a UI update even when the clamp
     * did not move it; a visible cursor reports an update only when it
     * moved.
     */
    private fun moveCursor(button: Int, u: Ui, w: Int, h: Int): MoveResult {
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
        val ox = u.curX
        val oy = u.curY
        u.curX = (u.curX + dx).coerceIn(0, w - 1)
        u.curY = (u.curY + dy).coerceIn(0, h - 1)
        if (!u.curVisible) {
            u.curVisible = true
            return MoveResult.UiUpdate
        }
        if (u.curX != ox || u.curY != oy) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    private class PlaceMove(val c: Char, val x: Int, val y: Int)

    private class ScannedInt(val value: Int, val end: Int)

    /**
     * The `sscanf(move + 1, "%c,%d,%d")` parser of `unruly.c:1717`.
     * `%c` takes exactly one character, the two commas are literals, and
     * the tail after the third conversion is ignored.
     */
    private fun parsePlaceMove(move: String): PlaceMove? {
        var i = 1
        if (i >= move.length) return null
        val c = move[i]
        i++
        if (i >= move.length || move[i] != ',') return null
        i++
        val x = scanInt(move, i) ?: return null
        i = x.end
        if (i >= move.length || move[i] != ',') return null
        i++
        val y = scanInt(move, i) ?: return null
        return PlaceMove(c, x.value, y.value)
    }

    /**
     * One `%d` conversion: white space, an optional sign, then at least
     * one digit. A value outside the `long` range saturates, as glibc
     * does, and the assignment to `int` keeps the low 32 bits; that is
     * why `P1,4294967296,0` names x = 0 and is accepted, while
     * `P1,99999999999999999999,0` names x = -1 and is rejected.
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

    /**
     * C `atoi`: white space, an optional sign, then digits. Kotlin `Int`
     * arithmetic wraps where C is undefined; the port saturates instead,
     * so a decode never throws.
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
}
