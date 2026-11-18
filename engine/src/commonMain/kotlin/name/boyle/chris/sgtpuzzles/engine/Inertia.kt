package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.PI
import kotlin.math.atan2

/**
 * A port of upstream `inertia.c`: the gem-collecting sliding puzzle.
 *
 * The player slides across the grid until a stop cell or a wall blocks
 * the path. A gem on the way is collected; a mine kills the player. The
 * game ends when the last gem is collected. The rules, the seed, the
 * description, and the save format are upstream behavior.
 *
 * An input is a direction, not a drag: a click on a neighbouring cell
 * becomes the nearest of eight compass directions. The click has no
 * board bounds test, so a click in the letterbox is a real move.
 *
 * The solver is in `InertiaSolver.kt`. It has no RNG and reproduces the
 * upstream tour heuristic exactly, because the `SOLVE` record and the
 * hint sequence are part of the save text.
 *
 * C quirks that this port keeps, each pinned by a test:
 * - `dup_game` clears `dead`, so a hand-written `SOLVE` record can
 *   revive a dead state;
 * - an empty move string is direction 0, because `atoi("")` is zero;
 * - an `S` payload is not validated: a byte outside `0..7` becomes a
 *   move that `executeMove` rejects. The midend reports `NoEffect`
 *   where C aborts at its assert.
 *
 * D-008: upstream `validate_params` accepts an area up to `INT_MAX`
 * cells, and generation of `46340x46340` allocates over 100 GB and
 * never finishes. [newDesc] refuses an area above [MAX_AREA] cells
 * (200x200, where the measured phone generation is about 6.7 s) with a
 * fixed message before the first RNG draw. No preset is near the
 * limit, so every real description is unchanged.
 */
object Inertia : Game {

    /** The cell values, as in `inertia.c:23-37`. */
    internal const val BLANK = 'b'
    internal const val GEM = 'g'
    internal const val MINE = 'm'
    internal const val STOP = 's'
    internal const val WALL = 'w'
    internal const val START = 'S'
    internal const val POSSGEM = 'G'
    internal const val UNDRAWN = '?'

    internal const val DIRECTIONS = 8
    internal const val DP1 = DIRECTIONS + 1

    /** The generation guard of D-008: 200x200, the measured cap. */
    internal const val MAX_AREA = 40_000

    private const val COL_BACKGROUND = 0
    private const val COL_OUTLINE = 1
    private const val COL_HIGHLIGHT = 2
    private const val COL_LOWLIGHT = 3
    private const val COL_PLAYER = 4
    private const val COL_DEAD_PLAYER = 5
    private const val COL_MINE = 6
    private const val COL_GEM = 7
    private const val COL_WALL = 8
    private const val COL_HINT = 9
    private const val NCOLOURS = 10

    private const val FLASH_DEAD = 0x100
    private const val FLASH_WIN = 0x200
    private const val FLASH_MASK = 0x300

    private const val PREFERRED_TILE_SIZE = 32

    /** `struct game_params`. */
    data class Params(val w: Int, val h: Int) : Game.Params {
        override fun copy(): Game.Params = Params(w, h)
    }

    /**
     * `soln`: an immutable tour of direction codes.
     *
     * C reference-counts the list and never mutates it after a success,
     * so a Kotlin state can share one object. A listener must not
     * mutate [directions].
     */
    class Soln internal constructor(internal val directions: IntArray) {
        val len: Int get() = directions.size

        /** `soln->list[i]` as the unsigned byte C stores. */
        fun directionAt(index: Int): Int = directions[index]

        override fun equals(other: Any?): Boolean =
            other is Soln && directions.contentEquals(other.directions)

        override fun hashCode(): Int = directions.contentHashCode()
    }

    /** `struct game_state`. The grid is `width * height` cell characters. */
    class State(
        val width: Int,
        val height: Int,
        val px: Int,
        val py: Int,
        val gems: Int,
        val grid: CharArray,
        val distanceMoved: Int,
        val dead: Boolean,
        val cheated: Boolean,
        val solnPos: Int,
        val soln: Soln?,
    ) : Game.State {

        /** The cell value at `(x,y)`, or [WALL] outside the board (`AT`). */
        fun at(x: Int, y: Int): Char = Inertia.at(width, height, grid, x, y)

        override fun copy(): Game.State = State(
            width,
            height,
            px,
            py,
            gems,
            grid.copyOf(),
            distanceMoved,
            dead,
            cheated,
            solnPos,
            soln,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                px == other.px &&
                py == other.py &&
                gems == other.gems &&
                grid.contentEquals(other.grid) &&
                distanceMoved == other.distanceMoved &&
                dead == other.dead &&
                cheated == other.cheated &&
                solnPos == other.solnPos &&
                soln == other.soln

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + px
            result = 31 * result + py
            result = 31 * result + gems
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + distanceMoved
            result = 31 * result + dead.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + solnPos
            result = 31 * result + (soln?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * `struct game_ui`. Only the death counter is serialized. The two
     * animation fields of C have no meaning in this seam and are
     * omitted.
     */
    class Ui : Game.Ui {
        var deaths: Int = 0
        var justMadeMove: Boolean = false
        var justDied: Boolean = false
    }

    /**
     * `struct game_drawstate`. The cache stores the adjusted value that
     * the last frame drew, including the blank under a dead player.
     */
    class DrawState internal constructor(
        val tileSize: Int,
        val blitter: Int,
        width: Int,
        height: Int,
    ) : Game.DrawState {
        val grid: IntArray = IntArray(width * height) { UNDRAWN.code }
        var started: Boolean = false
        var playerBackgroundSaved: Boolean = false
        var pbgx: Int = -1
        var pbgy: Int = -1
    }

    /** One generated description and the number of generation attempts. */
    data class Generation(val desc: String, val attempts: Int)

    override val name: String = "Inertia"
    override val backendName: String = "inertia"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    /** `default_params`: no `PORTRAIT_SCREEN`, so `10x8`. */
    override fun defaultParams(): Game.Params = Params(10, 8)

    /** `game_fetch_preset`: the title is the parameter string. */
    override fun presets(): List<Preset> = listOf(
        Params(10, 8),
        Params(15, 12),
        Params(20, 16),
    ).map { Preset("${it.w}x${it.h}", encodeParams(it, true)) }

    /**
     * `decode_params`: `atoi`, a digit-only skip, and an optional
     * lower-case `x`. A leading space or sign hides the `x`, because
     * `atoi` skips it but the digit loop does not.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = atoi(text, 0)
        var h = w
        var index = 0
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == 'x') {
            index++
            h = atoi(text, index)
        }
        return Params(w, h)
    }

    /** `encode_params`: always `%dx%d`; `full` is ignored. */
    @Suppress("UNUSED_PARAMETER")
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.w}x${p.h}"
    }

    /**
     * `validate_params`, in the C order. The division is safe because a
     * height below two is rejected first.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 2 || p.h < 2) {
            return "Width and height must both be at least two"
        }
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.w * p.h < 6) {
            return "Grid area must be at least six squares"
        }
        return null
    }

    /** `game_configure` (`inertia.c:166-187`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
        )
    }

    /** `custom_params` (`inertia.c:189-197`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
        )

    /** `new_game_desc`: no `AUXINFO`. */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        Description(generate(params as Params, random).desc)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description = Description(generate(params as Params, random, cancel).desc)

    /** [newDesc] with the generation attempt count, for tests. */
    fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params as Params, random)

    /**
     * `validate_desc`, in the C order: the characters, the length, one
     * start, and one gem. Nothing checks the shape or solvability.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val wh = p.w * p.h
        var starts = 0
        var gems = 0
        for (i in 0 until wh) {
            if (i >= desc.length) return "Not enough data to fill grid"
            val c = desc[i]
            if (c != WALL && c != START && c != STOP && c != GEM &&
                c != MINE && c != BLANK
            ) {
                return "Unrecognised character in game description"
            }
            if (c == START) starts++
            if (c == GEM) gems++
        }
        if (desc.length > wh) return "Too much data to fit in grid"
        if (starts < 1) return "No starting square specified"
        if (starts > 1) return "More than one starting square specified"
        if (gems < 1) return "No gems specified"
        return null
    }

    /**
     * `new_game`: the start becomes a stop, and the gems are counted.
     * C asserts the length, the gem count, and the start; a direct
     * caller gets a clear exception instead of an abort.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.w * p.h
        require(desc.length == wh) {
            "description has ${desc.length} characters for $wh cells"
        }
        val grid = desc.toCharArray()
        var px = -1
        var py = -1
        var gems = 0
        for (i in 0 until wh) {
            if (grid[i] == START) {
                grid[i] = STOP
                px = i % p.w
                py = i / p.w
            } else if (grid[i] == GEM) {
                gems++
            }
        }
        require(gems > 0) { "description has no gems" }
        require(px >= 0 && py >= 0) { "description has no starting square" }
        return State(p.w, p.h, px, py, gems, grid, 0, false, false, 0, null)
    }

    /**
     * `dup_game`: a copy that clears `dead` unconditionally, as C does.
     * This is what `execute_move` duplicates, so an `S` move revives a
     * dead state. [State.copy] keeps `dead`.
     */
    internal fun dupGame(state: State): State = State(
        state.width,
        state.height,
        state.px,
        state.py,
        state.gems,
        state.grid.copyOf(),
        state.distanceMoved,
        dead = false,
        state.cheated,
        state.solnPos,
        state.soln,
    )

    /** The C solver; null means "no solution from this position". */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /**
     * `solve_game` with the two C messages (`inertia.c:749-755` and
     * `:1093`, `:1239`): a board with no gem is already solved, and any
     * other null from the heuristic means no tour was found from this
     * starting point.
     */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        val move = solveInertia(s)
        if (move != null) return SolveOutcome(move, null)
        return SolveOutcome(
            null,
            if (s.grid.none { it == GEM }) {
                "Game is already solved"
            } else {
                "Unable to find a solution from this starting point"
            },
        )
    }

    /**
     * `execute_move`. The `S` path installs a tour and does not touch
     * the grid. The direction path slides the player, collects gems,
     * and stops on a stop, a wall ahead, or a mine.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State

        if (move.startsWith("S")) {
            // An `S` with no payload is invalid. Any other payload is
            // accepted without a range check, as C does.
            if (move.length == 1) return null
            return installNewSolution(dupGame(s), move)
        }

        val dir = atoi(move)
        if (dir < 0 || dir >= DIRECTIONS) return null
        if (s.dead) return null

        val w = s.width
        val h = s.height
        if (at(w, h, s.grid, s.px + dx(dir), s.py + dy(dir)) == WALL) return null

        val grid = s.grid.copyOf()
        var px = s.px
        var py = s.py
        var gems = s.gems
        var dead = false
        var distance = 0

        while (true) {
            px += dx(dir)
            py += dy(dir)
            distance++
            if (at(w, h, grid, px, py) == GEM) {
                grid[py * w + px] = BLANK
                gems--
            }
            if (at(w, h, grid, px, py) == MINE) {
                dead = true
                break
            }
            if (at(w, h, grid, px, py) == STOP ||
                at(w, h, grid, px + dx(dir), py + dy(dir)) == WALL
            ) {
                break
            }
        }

        var ret = State(
            w,
            h,
            px,
            py,
            gems,
            grid,
            distance,
            dead,
            s.cheated,
            s.solnPos,
            s.soln,
        )

        if (ret.soln != null) {
            if (ret.dead || ret.gems == 0) {
                ret = discardSolution(ret)
            } else if (ret.soln!!.directionAt(ret.solnPos) == dir &&
                ret.solnPos + 1 < ret.soln!!.len
            ) {
                ret = State(
                    ret.width, ret.height, ret.px, ret.py, ret.gems, ret.grid,
                    ret.distanceMoved, ret.dead, ret.cheated, ret.solnPos + 1, ret.soln,
                )
            } else {
                val recomputed = solveInertia(ret)
                ret = if (recomputed != null) {
                    installNewSolution(ret, recomputed)
                } else {
                    discardSolution(ret)
                }
            }
        }

        return ret
    }

    /** `install_new_solution`: store the payload bytes and mark `cheated`. */
    private fun installNewSolution(state: State, move: String): State {
        check(move.isNotEmpty() && move[0] == 'S') { "a solution starts with S" }
        val payload = IntArray(move.length - 1) { index ->
            // C stores `unsigned char`, so a byte outside `0..7` must
            // stay non-negative and never match a direction.
            (move[index + 1] - '0') and 0xFF
        }
        return State(
            state.width,
            state.height,
            state.px,
            state.py,
            state.gems,
            state.grid,
            state.distanceMoved,
            state.dead,
            cheated = true,
            solnPos = 0,
            soln = Soln(payload),
        )
    }

    /** `discard_solution`: drop the tour but keep `cheated`. */
    private fun discardSolution(state: State): State = State(
        state.width,
        state.height,
        state.px,
        state.py,
        state.gems,
        state.grid,
        state.distanceMoved,
        state.dead,
        state.cheated,
        solnPos = 0,
        soln = null,
    )

    /** `game_status`: a win or open; inertia never reports a loss. */
    override fun status(state: Game.State): Int {
        val s = state as State
        return if (s.dead) 0 else if (s.gems == 0) 1 else 0
    }

    /**
     * `game_text_format`. Every line ends with `\n`, including the last,
     * and there is no trailing padding beyond the right border.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val cw = 4
        val ch = 2
        val gw = cw * w + 2
        val gh = ch * h + 1
        val len = gw * gh
        val board = CharArray(len + 1) { ' ' }
        board[len - 2] = '+'
        board[len - 1] = '\n'
        board[len] = '\u0000'

        for (r in 0 until h) {
            for (c in 0 until w) {
                val cell = r * ch * gw + cw * c
                val center = cell + gw * ch / 2 + cw / 2
                val index = r * w + c
                when (s.grid[index]) {
                    BLANK -> Unit
                    GEM -> board[center] = 'o'
                    MINE -> board[center] = 'M'
                    STOP -> {
                        board[center - 1] = '('
                        board[center + 1] = ')'
                    }

                    WALL -> {
                        board[center - 1] = 'X'
                        board[center] = 'X'
                        board[center + 1] = 'X'
                    }
                }
                if (r == s.py && c == s.px) {
                    if (!s.dead) {
                        board[center] = '@'
                    } else {
                        board[center - 1] = ':'
                        board[center] = '-'
                        board[center + 1] = '('
                    }
                }
                board[cell] = '+'
                board[cell + 1] = '-'
                board[cell + 2] = '-'
                board[cell + 3] = '-'
                for (i in 1 until ch) board[cell + i * gw] = '|'
            }
            for (c in 0 until ch) {
                board[(r * ch + c) * gw + gw - 2] = if (c == 0) '+' else '|'
                board[(r * ch + c) * gw + gw - 1] = '\n'
            }
        }
        for (i in 0 until gw - 2) board[len - gw + i] = '-'
        for (c in 0 until w) board[len - gw + cw * c] = '+'
        // C returns a NUL-terminated buffer; the text is the first
        // `len` characters.
        return board.concatToString(0, len)
    }

    /**
     * `game_changed_state`: increment the death counter for a live
     * suicide move, and report the completion transition. C never sees
     * a null old state; the port guards it.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val old = oldState as State?
        val new = newState as State
        if (old != null && !old.dead && new.dead && u.justMadeMove && old.gems != 0) {
            u.deaths++
            u.justDied = true
        } else {
            u.justDied = false
        }
        u.justMadeMove = false
        return new.gems == 0 && !new.cheated && !new.dead &&
            old != null && old.gems != 0
    }

    /** `game_request_keys`: the diagonal arrow layout and no labels. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_DIAGONALS)

    /**
     * `current_key_label`. The seam has no hook and the Android shell
     * has no call site, so this is a public helper with a unit test.
     * `IS_CURSOR_SELECT` is an exact compare, so a modified select has
     * no label.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val s = state as State
        val select = button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2
        if (select && s.soln != null && s.solnPos < s.soln.len) return "Advance"
        return ""
    }

    /**
     * `game_compute_size`: `2*(T/4) + 1 + w*T` each way, with C
     * truncating division for the border.
     */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 4
        return Size(
            2 * border + 1 + p.w * tileSize,
            2 * border + 1 + p.h * tileSize,
        )
    }

    /** `new_ui`: only `deaths` can be non-zero, and only after a load. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `encode_ui`: the death counter, always written. */
    override fun encodeUi(ui: Game.Ui): String = "D${(ui as Ui).deaths}"

    /**
     * `decode_ui`: a prefix `sscanf("D%d")`. The literal `D` must be
     * first; the number follows the `sscanf` rules and keeps the fresh
     * zero when there is no conversion.
     */
    override fun decodeUi(state: Game.State, text: String): Game.Ui {
        val ui = Ui()
        if (text.isEmpty() || text[0] != 'D') return ui
        scanInt(text, 1)?.let { ui.deaths = it }
        return ui
    }

    /**
     * `game_colours` with the default background. The trace sink uses
     * this zero-argument call.
     */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`: the wall is a mix of the background and highlight. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        ret[COL_OUTLINE * 3] = 0.0f
        ret[COL_OUTLINE * 3 + 1] = 0.0f
        ret[COL_OUTLINE * 3 + 2] = 0.0f

        ret[COL_PLAYER * 3] = 0.0f
        ret[COL_PLAYER * 3 + 1] = 1.0f
        ret[COL_PLAYER * 3 + 2] = 0.0f

        ret[COL_DEAD_PLAYER * 3] = 1.0f
        ret[COL_DEAD_PLAYER * 3 + 1] = 0.3f
        ret[COL_DEAD_PLAYER * 3 + 2] = 0.0f

        ret[COL_MINE * 3] = 0.0f
        ret[COL_MINE * 3 + 1] = 0.0f
        ret[COL_MINE * 3 + 2] = 0.0f

        ret[COL_GEM * 3] = 0.6f
        ret[COL_GEM * 3 + 1] = 1.0f
        ret[COL_GEM * 3 + 2] = 1.0f

        for (i in 0 until 3) {
            ret[COL_WALL * 3 + i] =
                (3.0f * ret[COL_BACKGROUND * 3 + i] + ret[COL_HIGHLIGHT * 3 + i]) / 4.0f
        }

        ret[COL_HINT * 3] = 1.0f
        ret[COL_HINT * 3 + 1] = 1.0f
        ret[COL_HINT * 3 + 2] = 0.0f
        return ret
    }

    /** `game_get_cursor_location`: the settled sprite rectangle. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect {
        val s = state as State
        val border = tileSize / 4
        return Game.CursorRect(
            border + s.px * tileSize,
            border + s.py * tileSize,
            tileSize,
            tileSize,
        )
    }

    /**
     * `game_new_drawstate` plus `game_set_size`. The seam knows the tile
     * size at creation, so the one blitter is allocated here, before the
     * first `start_draw`.
     */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(
            tileSize = tileSize,
            blitter = sink.blitterNew(tileSize, tileSize),
            width = s.width,
            height = s.height,
        )
    }

    /** `game_free_drawstate`. */
    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        sink.blitterFree((drawState as DrawState).blitter)
    }

    /**
     * `interpret_move`. A click is the nearest compass octant to the
     * clicked cell, with no bounds test. The keys are exact compares,
     * and a select follows the stored tour.
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
        var dir = -1

        if (button == Buttons.LEFT_BUTTON) {
            val tx = fromCoord(x, tileSize)
            val ty = fromCoord(y, tileSize)
            if (tx != s.px || ty != s.py) {
                val ddx = tx - s.px
                val ddy = ty - s.py
                // `atan2` takes the exact double conversion of the int
                // deltas; the result and the sector arithmetic are
                // float, as in C.
                var angle = atan2(ddx.toDouble(), (-ddy).toDouble()).toFloat()
                angle = (angle + (PI / 8).toFloat()) / (PI / 4).toFloat()
                dir = (angle + 16.0f).toInt() and 7
            }
        } else if (button == Buttons.CURSOR_UP ||
            button == (Buttons.MOD_NUM_KEYPAD or '8'.code)
        ) {
            dir = 0
        } else if (button == Buttons.CURSOR_DOWN ||
            button == (Buttons.MOD_NUM_KEYPAD or '2'.code)
        ) {
            dir = 4
        } else if (button == Buttons.CURSOR_LEFT ||
            button == (Buttons.MOD_NUM_KEYPAD or '4'.code)
        ) {
            dir = 6
        } else if (button == Buttons.CURSOR_RIGHT ||
            button == (Buttons.MOD_NUM_KEYPAD or '6'.code)
        ) {
            dir = 2
        } else if (button == (Buttons.MOD_NUM_KEYPAD or '7'.code)) {
            dir = 7
        } else if (button == (Buttons.MOD_NUM_KEYPAD or '1'.code)) {
            dir = 5
        } else if (button == (Buttons.MOD_NUM_KEYPAD or '9'.code)) {
            dir = 1
        } else if (button == (Buttons.MOD_NUM_KEYPAD or '3'.code)) {
            dir = 3
        } else if ((button == Buttons.CURSOR_SELECT ||
                button == Buttons.CURSOR_SELECT2) &&
            s.soln != null && s.solnPos < s.soln.len
        ) {
            dir = s.soln.directionAt(s.solnPos)
        }

        if (dir < 0) return MoveResult.Unused

        if (at(w, h, s.grid, s.px + dx(dir), s.py + dy(dir)) == WALL) {
            return MoveResult.NoEffect
        }
        if (s.dead) return MoveResult.NoEffect

        u.justMadeMove = true
        return MoveResult.Move(dir.toString())
    }

    /**
     * `game_redraw` for a settled frame (`oldstate == NULL`, no flash).
     * The order is the contract: the blitter erase, the grid lines on
     * the first draw, the dirty cells, `inertia_follow`, the status,
     * then the sprite.
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
        val border = tileSize / 4

        if (ds.playerBackgroundSaved) {
            sink.blitterLoad(ds.blitter, ds.pbgx, ds.pbgy)
            sink.update(ds.pbgx, ds.pbgy, tileSize, tileSize)
            ds.playerBackgroundSaved = false
        }

        if (!ds.started) {
            for (y in 0..h) {
                sink.line(
                    coord(0, tileSize, border),
                    coord(y, tileSize, border),
                    coord(w, tileSize, border),
                    coord(y, tileSize, border),
                    COL_LOWLIGHT,
                )
            }
            for (x in 0..w) {
                sink.line(
                    coord(x, tileSize, border),
                    coord(0, tileSize, border),
                    coord(x, tileSize, border),
                    coord(h, tileSize, border),
                    COL_LOWLIGHT,
                )
            }
            ds.started = true
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = s.grid[i].code
                // Erase the mine under the dead player at the end of the
                // move. The cache stores the substituted value.
                if (v == MINE.code && s.dead && x == s.px && y == s.py) {
                    v = BLANK.code
                }
                if (ds.grid[i] != v) {
                    drawTile(sink, ds, x, y, v, tileSize)
                    ds.grid[i] = v
                }
            }
        }

        sink.inertiaFollow(
            s.cheated && s.soln != null && s.solnPos < s.soln.len,
        )

        sink.status(statusText(s, u))

        val pbgx = coord(s.px, tileSize, border)
        val pbgy = coord(s.py, tileSize, border)
        ds.pbgx = pbgx
        ds.pbgy = pbgy
        sink.blitterSave(ds.blitter, pbgx, pbgy)
        val hintdir = if (s.soln != null && s.solnPos < s.soln.len) {
            s.soln.directionAt(s.solnPos)
        } else {
            -1
        }
        drawPlayer(sink, ds, pbgx, pbgy, s.dead, hintdir)
        ds.playerBackgroundSaved = true
    }

    /**
     * The status line. In a settled frame the counted gems equal
     * `state.gems`, so [statusText] and the in-draw text are the same.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val u = ui as Ui
        val out = StringBuilder()
        if (s.dead) {
            out.append("DEAD!")
        } else if (s.gems != 0) {
            if (s.cheated) out.append("Auto-solver used. ")
            out.append("Gems: ").append(s.gems)
        } else if (s.cheated) {
            out.append("Auto-solved.")
        } else {
            out.append("COMPLETED!")
        }
        if (u.deaths != 0) {
            out.append("   Deaths: ").append(u.deaths)
        }
        return out.toString()
    }

    /** `draw_player`: a star when dead, a circle when alive, and a hint. */
    private fun drawPlayer(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        dead: Boolean,
        hintdir: Int,
    ) {
        val t = ds.tileSize
        if (dead) {
            val coords = IntArray(DIRECTIONS * 4)
            for (d in 0 until DIRECTIONS) {
                var x1 = dx(d).toFloat()
                var y1 = dy(d).toFloat()
                var len = kotlin.math.sqrt((x1 * x1 + y1 * y1).toDouble()).toFloat()
                x1 /= len
                y1 /= len

                var x3 = dx(d + 1).toFloat()
                var y3 = dy(d + 1).toFloat()
                len = kotlin.math.sqrt((x3 * x3 + y3 * y3).toDouble()).toFloat()
                x3 /= len
                y3 /= len

                val x2 = (x1 + x3) / 4.0f
                val y2 = (y1 + y3) / 4.0f

                coords[d * 4] = x + t / 2 + ((t * 3 / 7) * x1).toInt()
                coords[d * 4 + 1] = y + t / 2 + ((t * 3 / 7) * y1).toInt()
                coords[d * 4 + 2] = x + t / 2 + ((t * 3 / 7) * x2).toInt()
                coords[d * 4 + 3] = y + t / 2 + ((t * 3 / 7) * y2).toInt()
            }
            sink.poly(coords, COL_DEAD_PLAYER, COL_OUTLINE)
        } else {
            sink.circle(x + t / 2, y + t / 2, t / 3, COL_PLAYER, COL_OUTLINE)
        }

        if (!dead && hintdir >= 0) {
            val scale = if (dx(hintdir) != 0 && dy(hintdir) != 0) 0.8f else 1.0f
            val ax = ((t * 2 / 5) * scale * dx(hintdir)).toInt()
            val ay = ((t * 2 / 5) * scale * dy(hintdir)).toInt()
            val px = -ay
            val py = ax
            val ox = x + t / 2
            val oy = y + t / 2
            val coords = intArrayOf(
                ox + px / 9,
                oy + py / 9,
                ox + px / 9 + ax * 2 / 3,
                oy + py / 9 + ay * 2 / 3,
                ox + px / 3 + ax * 2 / 3,
                oy + py / 3 + ay * 2 / 3,
                ox + ax,
                oy + ay,
                ox - px / 3 + ax * 2 / 3,
                oy - py / 3 + ay * 2 / 3,
                ox - px / 9 + ax * 2 / 3,
                oy - py / 9 + ay * 2 / 3,
                ox - px / 9,
                oy - py / 9,
            )
            sink.poly(coords, COL_HINT, COL_OUTLINE)
        }

        sink.update(x, y, t, t)
    }

    /** `draw_tile`. The flash bits are kept for completeness. */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        value: Int,
        tileSize: Int,
    ) {
        val t = tileSize
        val border = t / 4
        val highlightWidth = t / 10
        val tx = coord(x, t, border)
        val ty = coord(y, t, border)
        val bg = when {
            value and FLASH_DEAD != 0 -> COL_DEAD_PLAYER
            value and FLASH_WIN != 0 -> COL_HIGHLIGHT
            else -> COL_BACKGROUND
        }
        val v = value and FLASH_MASK.inv()

        sink.clip(tx + 1, ty + 1, t - 1, t - 1)
        sink.rect(tx + 1, ty + 1, t - 1, t - 1, bg)

        if (v == WALL.code) {
            sink.poly(
                intArrayOf(
                    tx + t - 1, ty + t - 1,
                    tx + t - 1, ty + 1,
                    tx + 1, ty + t - 1,
                ),
                COL_LOWLIGHT,
                COL_LOWLIGHT,
            )
            sink.poly(
                intArrayOf(
                    tx + 1, ty + 1,
                    tx + t - 1, ty + 1,
                    tx + 1, ty + t - 1,
                ),
                COL_HIGHLIGHT,
                COL_HIGHLIGHT,
            )
            sink.rect(
                tx + 1 + highlightWidth,
                ty + 1 + highlightWidth,
                t - 2 * highlightWidth,
                t - 2 * highlightWidth,
                COL_WALL,
            )
        } else if (v == MINE.code) {
            val cx = tx + t / 2
            val cy = ty + t / 2
            val r = t / 2 - 3
            sink.circle(cx, cy, 5 * r / 6, COL_MINE, COL_MINE)
            sink.rect(cx - r / 6, cy - r, 2 * (r / 6) + 1, 2 * r + 1, COL_MINE)
            sink.rect(cx - r, cy - r / 6, 2 * r + 1, 2 * (r / 6) + 1, COL_MINE)
            sink.rect(cx - r / 3, cy - r / 3, r / 3, r / 4, COL_HIGHLIGHT)
        } else if (v == STOP.code) {
            sink.circle(tx + t / 2, ty + t / 2, t * 3 / 7, -1, COL_OUTLINE)
            sink.rect(
                tx + t * 3 / 7,
                ty + 1,
                t - 2 * (t * 3 / 7) + 1,
                t - 1,
                bg,
            )
            sink.rect(
                tx + 1,
                ty + t * 3 / 7,
                t - 1,
                t - 2 * (t * 3 / 7) + 1,
                bg,
            )
        } else if (v == GEM.code) {
            val cx = tx + t / 2
            val cy = ty + t / 2
            sink.poly(
                intArrayOf(
                    cx, cy - t * 5 / 14,
                    cx - t * 5 / 14, cy,
                    cx, cy + t * 5 / 14,
                    cx + t * 5 / 14, cy,
                ),
                COL_GEM,
                COL_OUTLINE,
            )
        }

        sink.unclip()
        sink.update(tx, ty, t, t)
    }

    // ------------------------------------------------------------------
    // Grid generation.
    // ------------------------------------------------------------------

    /**
     * The D-008 guard: rejects an area above [MAX_AREA] before any RNG
     * draw or allocation. [generate] calls it first, and it is internal
     * so a test can pin both sides of the boundary without generating a
     * board of that size.
     */
    internal fun requireGeneratableArea(w: Int, h: Int) {
        require(w.toLong() * h <= MAX_AREA) { Game.AREA_TOO_LARGE }
    }

    /**
     * `gengrid`. One shuffle per attempt of the flat wall/stop/mine/
     * start/blank fill, the direction-aware candidate search, the
     * Chebyshev distance gate, and one shuffle of the candidates on
     * success.
     */
    private fun generate(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generation {
        val w = params.w
        val h = params.h
        requireGeneratableArea(w, h)
        val wh = w * h
        val grid = CharArray(wh)
        val reachableFrom = BooleanArray(wh * DIRECTIONS)
        val reachableTo = BooleanArray(wh * DIRECTIONS)
        val positions = IntArray(wh * DIRECTIONS)
        var maxdistThreshold = 2
        var tries = 0
        var attempts = 0

        while (true) {
            cancel.check()
            attempts++
            var i = 0
            val block = wh / 5
            for (j in 0 until block) grid[i++] = WALL
            for (j in 0 until block) grid[i++] = STOP
            for (j in 0 until block) grid[i++] = MINE
            check(i < wh)
            grid[i++] = START
            while (i < wh) grid[i++] = BLANK
            shuffle(grid, wh, random)

            val possgems = findGemCandidates(
                w, h, grid, reachableFrom, reachableTo, positions,
            )
            if (possgems < wh / 5) continue

            // The distance gate is purely geometric: eight-neighbour
            // BFS over cells, ignoring walls and mines.
            val dist = IntArray(wh) { -1 }
            val list = IntArray(wh)
            var head = 0
            var tail = 0
            for (j in 0 until wh) {
                if (grid[j] == POSSGEM) {
                    dist[j] = 0
                    list[tail++] = j
                }
            }
            var maxdist = 0
            while (head < tail) {
                val pos = list[head++]
                if (maxdist < dist[pos]) maxdist = dist[pos]
                val x = pos % w
                val y = pos / w
                for (d in 0 until DIRECTIONS) {
                    val x2 = x + dx(d)
                    val y2 = y + dy(d)
                    if (x2 >= 0 && x2 < w && y2 >= 0 && y2 < h) {
                        val p2 = y2 * w + x2
                        if (dist[p2] < 0) {
                            dist[p2] = dist[pos] + 1
                            list[tail++] = p2
                        }
                    }
                }
            }
            check(head == wh && tail == wh)

            if (maxdist > maxdistThreshold) {
                tries++
                if (tries == 50) {
                    maxdistThreshold++
                    tries = 0
                }
                continue
            }

            var j = 0
            for (k in 0 until wh) if (grid[k] == POSSGEM) list[j++] = k
            shuffle(list, j, random)
            for (k in 0 until j) {
                grid[list[k]] = if (k < wh / 5) GEM else BLANK
            }
            return Generation(grid.concatToString(), attempts)
        }
    }

    /**
     * `find_gem_candidates`: a two-pass BFS over `(cell, direction)`.
     * A blank cell is a candidate when some direction is reachable both
     * from and to the start. The grid is marked with `POSSGEM`.
     */
    private fun findGemCandidates(
        w: Int,
        h: Int,
        grid: CharArray,
        reachableFrom: BooleanArray,
        reachableTo: BooleanArray,
        positions: IntArray,
    ): Int {
        val wh = w * h
        reachableFrom.fill(false)
        reachableTo.fill(false)

        var sx = -1
        var sy = -1
        loop@ for (y in 0 until h) {
            for (x in 0 until w) {
                if (at(w, h, grid, x, y) == START) {
                    sx = x
                    sy = y
                    break@loop
                }
            }
        }
        check(sx >= 0 && sy >= 0) { "the generator grid has no start" }

        for (pass in 0 until 2) {
            val reachable = if (pass == 0) reachableFrom else reachableTo
            val sign = if (pass == 0) 1 else -1
            var head = 0
            var tail = 0
            for (dir in 0 until DIRECTIONS) {
                val index = (sy * w + sx) * DIRECTIONS + dir
                positions[tail++] = index
                reachable[index] = true
            }
            while (head < tail) {
                val index = positions[head++]
                val dir = index % DIRECTIONS
                val x = (index / DIRECTIONS) % w
                val y = index / (w * DIRECTIONS)
                for (n in -1 until DIRECTIONS) {
                    val x2: Int
                    val y2: Int
                    val d2: Int
                    if (n < 0) {
                        x2 = x + sign * dx(dir)
                        y2 = y + sign * dy(dir)
                        d2 = dir
                    } else {
                        x2 = x
                        y2 = y
                        d2 = n
                    }
                    val i2 = (y2 * w + x2) * DIRECTIONS + d2
                    if (x2 >= 0 && x2 < w && y2 >= 0 && y2 < h && !reachable[i2]) {
                        val ok = if (pass == 0) {
                            canGo(w, h, grid, x, y, dir, x2, y2, d2)
                        } else {
                            canGo(w, h, grid, x2, y2, d2, x, y, dir)
                        }
                        if (ok) {
                            positions[tail++] = i2
                            reachable[i2] = true
                        }
                    }
                }
            }
        }

        var possgems = 0
        for (gy in 0 until h) {
            for (gx in 0 until w) {
                if (at(w, h, grid, gx, gy) != BLANK) continue
                for (gd in 0 until DIRECTIONS) {
                    val index = (gy * w + gx) * DIRECTIONS + gd
                    if (reachableFrom[index] && reachableTo[index]) {
                        grid[gy * w + gx] = POSSGEM
                        possgems++
                        break
                    }
                }
            }
        }
        return possgems
    }

    /** `can_go`: one direction-aware transition of the candidate BFS. */
    private fun canGo(
        w: Int,
        h: Int,
        grid: CharArray,
        x1: Int,
        y1: Int,
        dir1: Int,
        x2: Int,
        y2: Int,
        dir2: Int,
    ): Boolean {
        if (at(w, h, grid, x1, y1) == WALL || at(w, h, grid, x1, y1) == MINE) {
            return false
        }
        if (x2 == x1 && y2 == y1 &&
            (at(w, h, grid, x1, y1) == STOP ||
                at(w, h, grid, x1, y1) == START ||
                at(w, h, grid, x1 + dx(dir1), y1 + dy(dir1)) == WALL)
        ) {
            return true
        }
        if (x2 == x1 + dx(dir1) && y2 == y1 + dy(dir1) && dir1 == dir2) {
            val v = at(w, h, grid, x2, y2)
            if (v == BLANK || v == GEM || v == STOP || v == START) return true
        }
        return false
    }

    /** `misc.c shuffle`: the C draw order and limits. */
    private fun shuffle(array: CharArray, nelts: Int, random: RandomState) {
        var i = nelts
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val swap = array[i]
                array[i] = array[j]
                array[j] = swap
            }
        }
    }

    /** `misc.c shuffle` for an index list. */
    private fun shuffle(array: IntArray, nelts: Int, random: RandomState) {
        var i = nelts
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val swap = array[i]
                array[i] = array[j]
                array[j] = swap
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers used by the solver.
    // ------------------------------------------------------------------

    /** `DX(dir)`: `0`, `+1`, or `-1`, with C's bit arithmetic. */
    internal fun dx(dir: Int): Int =
        if ((dir and 3) != 0) {
            if ((dir and 7) > 4) -1 else 1
        } else {
            0
        }

    /** `DY(dir)`: `DX(dir+6)`, so `0..7` is up to up-left. */
    internal fun dy(dir: Int): Int = dx(dir + 6)

    /** `AT`: the cell value, or [WALL] outside the board. */
    internal fun at(w: Int, h: Int, grid: CharArray, x: Int, y: Int): Char =
        if (x < 0 || x >= w || y < 0 || y >= h) WALL else grid[y * w + x]

    /** `COORD(v)`. */
    private fun coord(v: Int, tileSize: Int, border: Int): Int =
        v * tileSize + border

    /**
     * `FROMCOORD(v)`: C integer division truncates toward zero, and so
     * does Kotlin `/`. Do not use `floorDiv`: the negative bands differ.
     */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 4 + tileSize) / tileSize - 1

    /**
     * C `atoi`: white space, an optional sign, then digits, and zero
     * when there is no digit. The value saturates where C is undefined.
     */
    private fun atoi(text: String): Int = atoi(text, 0)

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

    /** `sscanf("%d")` at [start]; null when no conversion happens. */
    private fun scanInt(text: String, start: Int): Int? {
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
