package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `pegs.c`: the classic Peg Solitaire game.
 *
 * A move jumps one peg over a second peg into the hole directly beyond
 * both. The source and the middle peg are removed, so one move leaves
 * two holes. The goal is a single peg anywhere on the board; the game
 * does not require the centre.
 *
 * The description is `w*h` characters from `PHO`: peg, hole, obstacle.
 * A description needs at least two pegs and one hole, so a completed
 * board cannot be written as a description. A completed state exists
 * only as a `MOVE` history: start from a two-peg description and play
 * the last move.
 *
 * pegs has no solver, no preferences, no per-game UI record, and no
 * auxiliary information. The palette, the board geometry, the drag
 * length rule, and the Random generator are part of parity.
 *
 * The one difference from C is the completion flash. C alternates the
 * background colour for 0.26 s; the seam has no flash time, and
 * `docs/PARITY.md` allows animation differences, so the port always
 * draws `COL_BACKGROUND`. The state, the text format, and the save file
 * do not depend on the flash.
 */
object Pegs : Game {

    /** `GRID_HOLE`, `GRID_PEG`, `GRID_OBST`: the three grid values. */
    const val GRID_HOLE = 0
    const val GRID_PEG = 1
    const val GRID_OBST = 2

    /** `GRID_CURSOR` and `GRID_JUMPING`: the drawstate cache markers. */
    private const val GRID_CURSOR = 10
    private const val GRID_JUMPING = 20

    private const val COL_BACKGROUND = 0
    private const val COL_HIGHLIGHT = 1
    private const val COL_LOWLIGHT = 2
    private const val COL_PEG = 3
    private const val COL_CURSOR = 4
    private const val NCOLOURS = 5

    private const val PREFERRED_TILE_SIZE = 33

    /** `TYPELIST`: the board types, their titles, and their param names. */
    enum class Type(val title: String, val lower: String) {
        CROSS("Cross", "cross"),
        OCTAGON("Octagon", "octagon"),
        RANDOM("Random", "random"),
    }

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val type: Type,
    ) : Game.Params {

        val fullText: String get() = encodeParams(this, true)

        val shortText: String get() = encodeParams(this, false)

        override fun copy(): Game.Params = Params(width, height, type)
    }

    /**
     * `struct game_state`. The grid is `width * height` bytes: hole,
     * peg, or obstacle. `completed` is set once and never cleared. Every
     * history entry owns its own grid, so [copy] copies it.
     */
    class State(
        val width: Int,
        val height: Int,
        val grid: ByteArray,
        val completed: Boolean,
    ) : Game.State {

        /** The value of cell `(x,y)`, as an `Int`. */
        fun value(x: Int, y: Int): Int = grid[y * width + x].toInt()

        override fun copy(): Game.State = State(width, height, grid.copyOf(), completed)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                grid.contentEquals(other.grid) &&
                completed == other.completed

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + completed.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`: the drag and the cursor. `sx,sy` are grid
     * coordinates; `dx,dy` are pixel coordinates of the pointer or of
     * the last drag. The midend does not serialise this: a loaded game
     * starts a fresh UI.
     */
    class Ui : Game.Ui {
        var dragging = false
        var sx = 0
        var sy = 0
        var dx = 0
        var dy = 0
        var curX = 0
        var curY = 0
        var curVisible = false
        var curJumping = false
    }

    /**
     * `struct game_drawstate`: the per-cell cache of the last drawn
     * value, the static-relief flag, the background of the last frame,
     * and the blitter that holds the dragged peg's background.
     *
     * The cache is `255` until a cell is drawn. The value stored is the
     * adjusted one, so the cursor and jumping markers are part of it.
     *
     * The midend creates this once per drawstate and keeps it across
     * moves, undo, redo, and restart. It replaces it on a new game, a
     * resize, a zoom change, or a new sink. A move must not allocate.
     */
    class DrawState internal constructor(
        val tileSize: Int,
        /** The id [DrawSink.blitterNew] returned. */
        val blitter: Int,
        width: Int,
        height: Int,
    ) : Game.DrawState {
        var dragging = false
        var dragX = 0
        var dragY = 0
        val width: Int = width
        val height: Int = height
        val grid: IntArray = IntArray(width * height) { 255 }
        var started = false
        var bgColour = -1
    }

    override val name: String = "Pegs"
    override val backendName: String = "pegs"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = false

    /**
     * `pegs_presets` and `game_fetch_preset`. The size is part of the
     * title only for Cross and Random. `pegs.c` has no `SMALL_SCREEN`
     * conditional, so all nine presets are offered, including the
     * `5x5random` game whose save C cannot reload.
     */
    private val presetValues = listOf(
        Triple(5, 7, Type.CROSS),
        Triple(7, 7, Type.CROSS),
        Triple(5, 9, Type.CROSS),
        Triple(7, 9, Type.CROSS),
        Triple(9, 9, Type.CROSS),
        Triple(7, 7, Type.OCTAGON),
        Triple(5, 5, Type.RANDOM),
        Triple(7, 7, Type.RANDOM),
        Triple(9, 9, Type.RANDOM),
    )

    override fun defaultParams(): Game.Params = Params(7, 7, Type.CROSS)

    /**
     * The presets use the full parameter string. A short form would drop
     * the board type and decode as Cross; `5x5` would then fail
     * validation, because Cross has no 5x5 shape.
     */
    override fun presets(): List<Preset> = presetValues.map { (w, h, type) ->
        val title = if (type == Type.OCTAGON) type.title else "${type.title} ${w}x$h"
        Preset(title, Params(w, h, type).fullText)
    }

    /**
     * `decode_params`. C writes into an existing structure and every
     * real caller starts from the defaults, so the type starts as Cross.
     *
     * `atoi` reads the width, then the digit loop skips ASCII digits
     * only, so a sign or a space stops it before the `x`. Without an
     * `x` the height equals the width. The type is a `strcmp` against
     * the whole remaining string: an unknown suffix is not an error, and
     * a trailing space or `junk` prevents a match.
     */
    override fun decodeParams(text: String): Game.Params {
        var index = 0
        val width = atoi(text, index)
        var height = width
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == 'x') {
            index++
            height = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        var type = Type.CROSS
        val suffix = text.substring(index)
        for (candidate in Type.values()) {
            if (suffix == candidate.lower) type = candidate
        }
        return Params(width, height, type)
    }

    /** `encode_params`: the lower-case type is part of the full form only. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.width}x${p.height}" + if (full) p.type.lower else ""
    }

    /**
     * `validate_params`, in the C order. `full` gates the shape rules,
     * which is why a save's `CPARAMS` accepts a short form that its
     * `PARAMS` rejects.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (full && (p.width <= 3 || p.height <= 3)) {
            return "Width and height must both be greater than three"
        }
        if (p.width < 1 || p.height < 1) {
            return "Width and height must both be at least one"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (full && p.type == Type.CROSS) {
            val accepted = (p.width == 9 && p.height == 5) ||
                (p.width == 5 && p.height == 9) ||
                (p.width == 9 && p.height == 9) ||
                (p.width == 7 && p.height == 5) ||
                (p.width == 5 && p.height == 7) ||
                (p.width == 9 && p.height == 7) ||
                (p.width == 7 && p.height == 9) ||
                (p.width == 7 && p.height == 7)
            // The message lists five sizes, but the code accepts eight.
            if (!accepted) {
                return "This board type is only supported at " +
                    "5x7, 5x9, 7x7, 7x9, and 9x9"
            }
        }
        if (full && p.type == Type.OCTAGON && (p.width != 7 || p.height != 7)) {
            return "This board type is only supported at 7x7"
        }
        return null
    }

    /** `game_configure` (`pegs.c:154-178`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Choices(
                name = "Board type",
                choices = ":Cross:Octagon:Random",
                keywords = null,
                selected = p.type.ordinal,
            ),
        )
    }

    /** `custom_params` (`pegs.c:180-189`): `type = selected`. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            Type.values()[(items[2] as ConfigItem.Choices).selected],
        )

    /** `new_game_desc`. There is no `AUXINFO`; the result has no aux text. */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val generated = PegsGeneration.generate(p.width, p.height, p.type, random)
        return Description(generated.desc)
    }

    /**
     * `validate_desc`: the length, the alphabet, at least two pegs, and
     * at least one hole. Nothing checks the shape or solvability.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val length = p.width * p.height
        if (desc.length != length) return "Game description is wrong length"
        for (c in desc) {
            if (c != 'P' && c != 'H' && c != 'O') {
                return "Invalid character in game description"
            }
        }
        var pegs = 0
        var holes = 0
        for (c in desc) {
            if (c == 'P') pegs++
            if (c == 'H') holes++
        }
        if (pegs < 2) return "Too few pegs in game description"
        if (holes < 1) return "Too few holes in game description"
        return null
    }

    /** `new_game`: read the characters; the type does not matter here. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val grid = ByteArray(p.width * p.height)
        for (i in grid.indices) {
            grid[i] = when (desc[i]) {
                'P' -> GRID_PEG.toByte()
                'H' -> GRID_HOLE.toByte()
                else -> GRID_OBST.toByte()
            }
        }
        return State(p.width, p.height, grid, completed = false)
    }

    /** pegs has no solver. */
    override fun solve(state: Game.State, auxInfo: String?): String? = null

    /**
     * `execute_move`. The text is `sx,sy-tx,ty` with a loose `sscanf`
     * parse: leading white space, an optional sign, and leading zeros
     * are accepted, and the fourth number stops at the first non-digit.
     * The move length must be exactly two cells in one direction.
     *
     * Completion is the lax rule: the first move that leaves one peg
     * sets the sticky flag.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val sx = scanInt(move, 0) ?: return null
        if (sx.end >= move.length || move[sx.end] != ',') return null
        val sy = scanInt(move, sx.end + 1) ?: return null
        if (sy.end >= move.length || move[sy.end] != '-') return null
        val tx = scanInt(move, sy.end + 1) ?: return null
        if (tx.end >= move.length || move[tx.end] != ',') return null
        val ty = scanInt(move, tx.end + 1) ?: return null

        val ax = sx.value
        val ay = sy.value
        val bx = tx.value
        val by = ty.value

        if (ax < 0 || ax >= from.width || ay < 0 || ay >= from.height) return null
        if (bx < 0 || bx >= from.width || by < 0 || by >= from.height) return null

        val dx = bx - ax
        val dy = by - ay
        if (max(abs(dx), abs(dy)) != 2 || min(abs(dx), abs(dy)) != 0) return null

        val mx = ax + dx / 2
        val my = ay + dy / 2
        if (from.grid[ay * from.width + ax].toInt() != GRID_PEG ||
            from.grid[my * from.width + mx].toInt() != GRID_PEG ||
            from.grid[by * from.width + bx].toInt() != GRID_HOLE
        ) {
            return null
        }

        val grid = from.grid.copyOf()
        grid[ay * from.width + ax] = GRID_HOLE.toByte()
        grid[my * from.width + mx] = GRID_HOLE.toByte()
        grid[by * from.width + bx] = GRID_PEG.toByte()

        var completed = from.completed
        if (!completed) {
            var count = 0
            for (value in grid) {
                if (value.toInt() == GRID_PEG) count++
            }
            if (count == 1) completed = true
        }
        return State(from.width, from.height, grid, completed)
    }

    /** `game_status`: a win or open; pegs never reports a loss. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /** pegs has no status bar, so the text is always empty. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /**
     * `game_text_format`: one row per line, a trailing newline after the
     * last row, `-` for a hole, `*` for a peg, and a space for an
     * obstacle (so rows keep their trailing spaces).
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val out = StringBuilder(s.height * (s.width + 1))
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                out.append(
                    when (s.grid[y * s.width + x].toInt()) {
                        GRID_HOLE -> '-'
                        GRID_PEG -> '*'
                        else -> ' '
                    },
                )
            }
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * `game_changed_state`: cancel a drag and a half-entered keyboard
     * jump, because the source cell may no longer hold a peg. The
     * return value is the completion transition.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        u.dragging = false
        u.curJumping = false
        return (newState as State).completed &&
            oldState != null && !(oldState as State).completed
    }

    /** `game_request_keys`: no labels, Android arrow-left mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT)

    /** `game_compute_size`: `T*w + 2*(T/2)` each way, with C division. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 2
        return Size(
            tileSize * p.width + 2 * border,
            tileSize * p.height + 2 * border,
        )
    }

    /**
     * `new_ui`: all flags off and the cursor at the first peg or hole in
     * column-major scan order (x outer, y inner). C reads
     * `PUZZLES_SHOW_CURSOR`; the seam has no environment, so the cursor
     * starts hidden.
     *
     * A validated description always has a peg, so the scan finds a
     * cell. If it does not, the cursor stays at (0,0) instead of
     * asserting as C does.
     */
    override fun newUi(state: Game.State): Game.Ui {
        val s = state as State
        val ui = Ui()
        for (x in 0 until s.width) {
            for (y in 0 until s.height) {
                val v = s.grid[y * s.width + x].toInt()
                if (v == GRID_PEG || v == GRID_HOLE) {
                    ui.curX = x
                    ui.curY = y
                    return ui
                }
            }
        }
        return ui
    }

    /** `android_cursor_visibility`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `game_get_cursor_location`: only a drawn cursor has a rectangle. */
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

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell of the cross or the octagon: a peg, a hole, or an
     * obstacle. A jump is a drag from one peg to a hole two cells away,
     * and one unit cannot name a drag (DESIGN section 11), so no unit
     * offers an action and the move stays on the pointer or the key path.
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
                val content = when (s.value(x, y)) {
                    GRID_PEG -> "peg"
                    GRID_HOLE -> "hole"
                    else -> "blocked"
                }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                content + cursor
            },
        )
        return cells.capped(cells.units.getOrNull(u.curY * s.width + u.curX))
    }

    /**
     * `current_key_label`. The seam has no hook and the Android shell
     * has no call site, so this is a public helper with a unit test. A
     * modified select has no label because the test is exact equality.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) return ""
            if (u.curJumping) return "Cancel"
            if (s.grid[u.curY * s.width + u.curX].toInt() == GRID_PEG) {
                return "Select"
            }
        }
        return ""
    }

    /**
     * `game_colours`. `game_mkhighlight` overwrites the background with
     * the shell colour and derives the highlight and the lowlight from
     * it; the peg and cursor colours are fixed.
     */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        ret[COL_PEG * 3] = 0.0f
        ret[COL_PEG * 3 + 1] = 0.0f
        ret[COL_PEG * 3 + 2] = 1.0f

        ret[COL_CURSOR * 3] = 0.5f
        ret[COL_CURSOR * 3 + 1] = 0.5f
        ret[COL_CURSOR * 3 + 2] = 1.0f
        return ret
    }

    /**
     * `game_new_drawstate` plus `game_set_size`. The seam collapses the
     * two because the tile size is known at creation, so the one
     * blitter is allocated here, before the first `start_draw`.
     */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState = DrawState(
        tileSize = tileSize,
        blitter = sink.blitterNew(tileSize, tileSize),
        width = (state as State).width,
        height = state.height,
    )

    /** `game_free_drawstate`. */
    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        sink.blitterFree((drawState as DrawState).blitter)
    }

    /**
     * `interpret_move`. A press starts a drag only on a peg; a drag
     * updates the pointer; a release moves only when the target is
     * exactly two cells away in one direction and the middle cell holds
     * a peg. A cursor key moves the cursor, and select arms a jump.
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

        if (button == Buttons.LEFT_BUTTON) {
            /*
             * C asserts that no drag is in progress. The midend
             * guarantees it: a press while another button is held first
             * synthesises a release. A violation is a midend bug, so the
             * port does not clamp the source cell to hide it.
             */
            val tx = fromCoord(x, tileSize)
            val ty = fromCoord(y, tileSize)
            if (tx in 0 until w && ty in 0 until h) {
                when (s.grid[ty * w + tx].toInt()) {
                    GRID_PEG -> {
                        u.dragging = true
                        u.sx = tx
                        u.sy = ty
                        u.dx = x
                        u.dy = y
                        u.curVisible = false
                        u.curJumping = false
                        return MoveResult.UiUpdate
                    }

                    GRID_HOLE -> return MoveResult.NoEffect
                    else -> return MoveResult.Unused
                }
            }
        } else if (button == Buttons.LEFT_DRAG && u.dragging) {
            u.dx = x
            u.dy = y
            return MoveResult.UiUpdate
        } else if (button == Buttons.LEFT_RELEASE && u.dragging) {
            // The drag is cancelled whatever the release position is.
            u.dragging = false
            val tx = fromCoord(x, tileSize)
            val ty = fromCoord(y, tileSize)
            if (tx < 0 || tx >= w || ty < 0 || ty >= h) return MoveResult.UiUpdate
            val dx = tx - u.sx
            val dy = ty - u.sy
            if (max(abs(dx), abs(dy)) != 2 || min(abs(dx), abs(dy)) != 0) {
                return MoveResult.UiUpdate
            }
            if (s.grid[ty * w + tx].toInt() != GRID_HOLE ||
                s.grid[(ty - dy / 2) * w + tx - dx / 2].toInt() != GRID_PEG ||
                s.grid[u.sy * w + u.sx].toInt() != GRID_PEG
            ) {
                return MoveResult.UiUpdate
            }
            return MoveResult.Move("${u.sx},${u.sy}-$tx,$ty")
        } else if (Buttons.isCursorMove(button)) {
            if (!u.curJumping) {
                var cx = u.curX
                var cy = u.curY
                when (button) {
                    Buttons.CURSOR_UP -> cy -= 1
                    Buttons.CURSOR_DOWN -> cy += 1
                    Buttons.CURSOR_LEFT -> cx -= 1
                    Buttons.CURSOR_RIGHT -> cx += 1
                }
                cx = min(max(cx, 0), w - 1)
                cy = min(max(cy, 0), h - 1)
                u.curVisible = true
                // An obstacle is refused, so the cursor stays where it
                // was while still becoming visible.
                val v = s.grid[cy * w + cx].toInt()
                if (v == GRID_HOLE || v == GRID_PEG) {
                    u.curX = cx
                    u.curY = cy
                }
                return MoveResult.UiUpdate
            }

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
            val mx = u.curX + dx
            val my = u.curY + dy
            val jx = mx + dx
            val jy = my + dy

            u.curJumping = false
            if (jx >= 0 && jy >= 0 && jx < w && jy < h &&
                s.grid[my * w + mx].toInt() == GRID_PEG &&
                s.grid[jy * w + jx].toInt() == GRID_HOLE
            ) {
                val text = "${u.curX},${u.curY}-$jx,$jy"
                u.curX = jx
                u.curY = jy
                return MoveResult.Move(text)
            }
            return MoveResult.UiUpdate
        } else if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            if (u.curJumping) {
                u.curJumping = false
                return MoveResult.UiUpdate
            }
            if (s.grid[u.curY * w + u.curX].toInt() == GRID_PEG) {
                u.curJumping = true
                return MoveResult.UiUpdate
            }
            return MoveResult.NoEffect
        }

        return MoveResult.Unused
    }

    /**
     * `game_redraw`. The cache survives a move: only cells whose
     * adjusted value differs from `drawState.grid` are drawn, and a
     * sprite from the previous frame is erased with `blitter_load`
     * first.
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
        val border = tileSize / 2
        val highlightWidth = tileSize / 16
        val bgcolour = COL_BACKGROUND

        // Erase the sprite that the previous frame left on screen.
        if (ds.dragging) {
            sink.blitterLoad(ds.blitter, ds.dragX, ds.dragY)
            sink.update(ds.dragX, ds.dragY, tileSize, tileSize)
            ds.dragging = false
        }

        if (!ds.started) {
            /*
             * The static relief: four sweeps in row-major order, each
             * skipping obstacles. A cell without an obstacle gets two
             * triangles, two rectangles, four trapeziums, and a
             * background rectangle.
             */
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (s.grid[y * w + x].toInt() == GRID_OBST) continue
                    val coords = intArrayOf(
                        coord(x + 1, tileSize, border) + highlightWidth - 1,
                        coord(y, tileSize, border) - highlightWidth,
                        coord(x, tileSize, border) - highlightWidth,
                        coord(y + 1, tileSize, border) + highlightWidth - 1,
                        coord(x, tileSize, border) - highlightWidth,
                        coord(y, tileSize, border) - highlightWidth,
                    )
                    sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)
                    coords[4] = coord(x + 1, tileSize, border) + highlightWidth - 1
                    coords[5] = coord(y + 1, tileSize, border) + highlightWidth - 1
                    sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (s.grid[y * w + x].toInt() == GRID_OBST) continue
                    sink.rect(
                        coord(x, tileSize, border) - highlightWidth,
                        coord(y, tileSize, border) - highlightWidth,
                        tileSize + highlightWidth,
                        tileSize + highlightWidth,
                        COL_HIGHLIGHT,
                    )
                    sink.rect(
                        coord(x, tileSize, border),
                        coord(y, tileSize, border),
                        tileSize + highlightWidth,
                        tileSize + highlightWidth,
                        COL_LOWLIGHT,
                    )
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (s.grid[y * w + x].toInt() == GRID_OBST) continue
                    for (dx in 0..1) {
                        val dy = 1 - dx
                        for (pass in 0..1) {
                            val sn = 2 * pass - 1
                            val colour = if (pass != 0) COL_LOWLIGHT else COL_HIGHLIGHT
                            val cx = coord(x, tileSize, border)
                            val cy = coord(y, tileSize, border)
                            val coords = intArrayOf(
                                cx + (pass * dx) * (tileSize - 1),
                                cy + (pass * dy) * (tileSize - 1),
                                cx + (pass * dx + dy) * (tileSize - 1),
                                cy + (pass * dy + dx) * (tileSize - 1),
                                0,
                                0,
                                0,
                                0,
                            )
                            coords[4] = coords[2] - highlightWidth * (dy - sn * dx)
                            coords[5] = coords[3] - highlightWidth * (dx - sn * dy)
                            coords[6] = coords[0] + highlightWidth * (dy + sn * dx)
                            coords[7] = coords[1] + highlightWidth * (dx + sn * dy)
                            sink.poly(coords, colour, colour)
                        }
                    }
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (s.grid[y * w + x].toInt() == GRID_OBST) continue
                    sink.rect(
                        coord(x, tileSize, border),
                        coord(y, tileSize, border),
                        tileSize,
                        tileSize,
                        COL_BACKGROUND,
                    )
                }
            }

            ds.started = true
            sink.update(0, 0, tileSize * w + 2 * border, tileSize * h + 2 * border)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = s.grid[y * w + x].toInt()
                // Blank the drag source, as if the peg were picked up.
                if (u.dragging && u.sx == x && u.sy == y && v == GRID_PEG) {
                    v = GRID_HOLE
                }
                if (u.curVisible && u.curX == x && u.curY == y) {
                    v += if (u.curJumping) GRID_JUMPING else GRID_CURSOR
                }
                if (v != GRID_OBST &&
                    (bgcolour != ds.bgColour || v != ds.grid[y * w + x])
                ) {
                    drawTile(
                        sink,
                        coord(x, tileSize, border),
                        coord(y, tileSize, border),
                        v,
                        bgcolour,
                        tileSize,
                    )
                    ds.grid[y * w + x] = v
                }
            }
        }

        // Draw the dragged peg at the pointer, with no background fill.
        if (u.dragging) {
            ds.dragging = true
            ds.dragX = u.dx - tileSize / 2
            ds.dragY = u.dy - tileSize / 2
            sink.blitterSave(ds.blitter, ds.dragX, ds.dragY)
            drawTile(sink, ds.dragX, ds.dragY, GRID_PEG, -1, tileSize)
        }

        ds.bgColour = bgcolour
    }

    /**
     * `draw_tile`. `v` may carry the cursor and jumping markers. A hole
     * draws one circle; a peg draws an outer and an inner circle. The
     * sprite is drawn with `bgcolour < 0`, so it adds no rectangle.
     */
    private fun drawTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        value: Int,
        bgcolour: Int,
        tileSize: Int,
    ) {
        var v = value
        if (bgcolour >= 0) sink.rect(x, y, tileSize, tileSize, bgcolour)

        var jumping = false
        var cursor = false
        if (v >= GRID_JUMPING) {
            jumping = true
            v -= GRID_JUMPING
        }
        if (v >= GRID_CURSOR) {
            cursor = true
            v -= GRID_CURSOR
        }

        if (v == GRID_HOLE) {
            val bg = if (cursor) COL_HIGHLIGHT else COL_LOWLIGHT
            check(!jumping) { "a hole cannot be jumping" }
            sink.circle(
                x + tileSize / 2,
                y + tileSize / 2,
                tileSize / 4,
                bg,
                bg,
            )
        } else if (v == GRID_PEG) {
            var bg = if (cursor || jumping) COL_CURSOR else COL_PEG
            sink.circle(x + tileSize / 2, y + tileSize / 2, tileSize / 3, bg, bg)
            bg = if (!cursor || jumping) COL_PEG else COL_CURSOR
            sink.circle(x + tileSize / 2, y + tileSize / 2, tileSize / 4, bg, bg)
        }

        sink.update(x, y, tileSize, tileSize)
    }

    /** `COORD(v)`: the top-left of a cell plus the border. */
    private fun coord(v: Int, tileSize: Int, border: Int): Int =
        border + v * tileSize

    /**
     * `FROMCOORD(v)`: C division truncates toward zero, and so does
     * Kotlin `/`, so a negative coordinate must not use `floorDiv`.
     */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value + tileSize - tileSize / 2) / tileSize - 1

    /** A number from `sscanf`, with the position after its digits. */
    private class Scanned(val value: Int, val end: Int)

    /**
     * `sscanf("%d")` at [start]: white space, an optional sign, then
     * digits. Returns null when no conversion happens. The value
     * saturates, where C overflow is undefined.
     */
    private fun scanInt(text: String, start: Int): Scanned? {
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
                return Scanned(if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE, i)
            }
            i++
        }
        return Scanned(sign * value.toInt(), i)
    }

    /**
     * C `atoi`: white space, an optional sign, then digits, and zero
     * when there is no digit. The value saturates instead of
     * overflowing.
     */
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
}
