package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `twiddle.c`: rotate `n` by `n` blocks of a grid.
 *
 * A move rotates one region by a quarter turn. The description is
 * `w*h` numbers in row-major order, separated by commas, or followed by
 * an orientation letter when the puzzle is orientable.
 *
 * The generator is in [TwiddleGeneration]. This game uses
 * [Game.NoDrawState], so [redraw] draws the recessed frame and the whole
 * board on every call. C draws the frame once per drawstate and skips
 * unchanged tiles. The rotation animation and the completion flash are
 * not ported: the seam has no animation time, and `docs/PARITY.md`
 * allows animation differences. The game state, the text, the moves, and
 * the save file are unaffected.
 */
object Twiddle : Game {

    private const val PREFERRED_TILE_SIZE = 48

    private const val COL_BACKGROUND = 0
    private const val COL_TEXT = 1
    private const val COL_HIGHLIGHT = 2
    private const val COL_HIGHLIGHT_GENTLE = 3
    private const val COL_LOWLIGHT = 4
    private const val COL_LOWLIGHT_GENTLE = 5
    private const val COL_HIGHCURSOR = 6
    private const val COL_LOWCURSOR = 7

    /** `CUR_TOP`, `CUR_RIGHT`, `CUR_BOTTOM`, and `CUR_LEFT`. */
    private const val CUR_TOP = 1
    private const val CUR_RIGHT = 2
    private const val CUR_BOTTOM = 4
    private const val CUR_LEFT = 8

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val n: Int,
        val rowsOnly: Boolean,
        val orientable: Boolean,
        val moveTarget: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(
            width,
            height,
            n,
            rowsOnly,
            orientable,
            moveTarget,
        )
    }

    /**
     * `struct game_state`. The grid stores `4 * number + orientation`,
     * where the number is one-based. `completed` doubles as the winning
     * move number. `lastX`, `lastY`, and `lastR` only feed the C
     * animation; the port keeps them for parity and for a later
     * animation seam.
     */
    class State(
        val width: Int,
        val height: Int,
        val n: Int,
        val orientable: Boolean,
        val grid: IntArray,
        val completed: Int,
        val usedSolve: Boolean,
        val moveCount: Int,
        val moveTarget: Int,
        val lastX: Int,
        val lastY: Int,
        val lastR: Int,
    ) : Game.State {

        val count: Int get() = width * height

        override fun copy(): Game.State = State(
            width,
            height,
            n,
            orientable,
            grid.copyOf(),
            completed,
            usedSolve,
            moveCount,
            moveTarget,
            lastX,
            lastY,
            lastR,
        )

        fun numberAt(x: Int, y: Int): Int = grid[y * width + x] / 4

        fun orientationAt(x: Int, y: Int): Int = grid[y * width + x] and 3

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                n == other.n &&
                orientable == other.orientable &&
                grid.contentEquals(other.grid) &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                moveCount == other.moveCount &&
                moveTarget == other.moveTarget &&
                lastX == other.lastX &&
                lastY == other.lastY &&
                lastR == other.lastR

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + n
            result = 31 * result + orientable.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + completed
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + moveCount
            result = 31 * result + moveTarget
            result = 31 * result + lastX
            result = 31 * result + lastY
            result = 31 * result + lastR
            return result
        }
    }

    /** `struct game_ui`. The cursor selects a whole rotation region. */
    class Ui : Game.Ui {
        var curX = 0
        var curY = 0
        var cursorVisible = false
    }

    override val name: String = "Twiddle"
    override val backendName: String = "twiddle"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    // `twiddle.c` has no `SMALL_SCREEN` branch, so the Android build and
    // the oracle expose the same eight presets.
    private val presetValues = listOf(
        Preset("3x3 rows only", "3x3n2r"),
        Preset("3x3 normal", "3x3n2"),
        Preset("3x3 orientable", "3x3n2o"),
        Preset("4x4 normal", "4x4n2"),
        Preset("4x4 orientable", "4x4n2o"),
        Preset("4x4, rotating 3x3 blocks", "4x4n3"),
        Preset("5x5, rotating 3x3 blocks", "5x5n3"),
        Preset("6x6, rotating 4x4 blocks", "6x6n4"),
    )

    override fun defaultParams(): Game.Params = Params(3, 3, 2, false, false, 0)

    override fun presets(): List<Preset> = presetValues

    /**
     * `decode_params`. The digit cursor starts at the beginning of the
     * string, so a leading sign or space stops it and the `x`, `n`, or
     * `m` that follows is not read at that point.
     */
    override fun decodeParams(text: String): Game.Params {
        var index = 0
        val width = atoi(text, index)
        while (index < text.length && text[index] in '0'..'9') index++

        var height = width
        var n = 2
        if (index < text.length && text[index] == 'x') {
            index++
            height = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] == 'n') {
            index++
            n = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }

        var rowsOnly = false
        var orientable = false
        var moveTarget = 0
        while (index < text.length) {
            when (text[index]) {
                'r' -> {
                    rowsOnly = true
                    index++
                }

                'o' -> {
                    orientable = true
                    index++
                }

                'm' -> {
                    index++
                    moveTarget = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                else -> index++
            }
        }

        return Params(width, height, n, rowsOnly, orientable, moveTarget)
    }

    /**
     * `encode_params`: `%dx%dn%d`, then `r`, `o`, and `m%d`. The `full`
     * argument is ignored, so the short parameters keep `m` and the game
     * id does too.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.width).append('x').append(p.height).append('n').append(p.n)
        if (p.rowsOnly) out.append('r')
        if (p.orientable) out.append('o')
        if (p.moveTarget != 0) out.append('m').append(p.moveTarget)
        return out.toString()
    }

    /**
     * `validate_params`, in C order. The integer division is safe
     * because the earlier checks force `height >= 2`.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.n < 2) {
            return "Rotating block size must be at least two"
        }
        if (p.width < p.n) {
            return "Width must be at least the rotating block size"
        }
        if (p.height < p.n) {
            return "Height must be at least the rotating block size"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.moveTarget < 0) {
            return "Number of shuffling moves may not be negative"
        }
        return null
    }

    /** `game_configure` (`twiddle.c:158-197`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Text("Rotating block size", p.n.toString()),
            ConfigItem.Bool("One number per row", p.rowsOnly),
            ConfigItem.Bool("Orientation matters", p.orientable),
            ConfigItem.Text("Number of shuffling moves", p.moveTarget.toString()),
        )
    }

    /** `custom_params` (`twiddle.c:199-211`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
            (items[3] as ConfigItem.Bool).value,
            (items[4] as ConfigItem.Bool).value,
            atoi((items[5] as ConfigItem.Text).value, 0),
        )

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(
            TwiddleGeneration.newDesc(
                p.width,
                p.height,
                p.n,
                p.rowsOnly,
                p.orientable,
                p.moveTarget,
                random,
            ),
        )
    }

    /**
     * `validate_desc`. For an orientable description the orientation
     * letter of the last cell is required, so the trailing-junk branch
     * below is unreachable and trailing junk is accepted. This is an
     * upstream defect and must stay.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val wh = p.width * p.height
        var index = 0

        for (i in 0 until wh) {
            if (index >= desc.length || desc[index] !in '0'..'9') {
                return "Not enough numbers in string"
            }
            while (index < desc.length && desc[index] in '0'..'9') index++

            val next = if (index < desc.length) desc[index] else '\u0000'
            if (!p.orientable && i < wh - 1) {
                if (next != ',') return "Expected comma after number"
            } else if (p.orientable && i < wh) {
                if (next != 'l' && next != 'r' && next != 'u' && next != 'd') {
                    return "Expected orientation letter after number"
                }
            } else if (i == wh - 1 && next != '\u0000') {
                return "Excess junk at end of string"
            }

            if (index < desc.length) index++
        }

        return null
    }

    /**
     * `new_game`. The parser is not checked against `validate_desc`; the
     * only quirks that matter are that the orientation letter is read
     * only for an orientable game and that one character after a number
     * is always consumed.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.width * p.height
        val grid = IntArray(wh)
        var index = 0

        for (i in 0 until wh) {
            grid[i] = 4 * atoi(desc, index)
            while (index < desc.length && desc[index] in '0'..'9') index++
            if (index < desc.length) {
                if (p.orientable) {
                    when (desc[index]) {
                        'l' -> grid[i] = grid[i] or 1
                        'd' -> grid[i] = grid[i] or 2
                        'r' -> grid[i] = grid[i] or 3
                    }
                }
                index++
            }
        }

        return State(
            width = p.width,
            height = p.height,
            n = p.n,
            orientable = p.orientable,
            grid = grid,
            completed = 0,
            usedSolve = false,
            moveCount = 0,
            moveTarget = p.moveTarget,
            lastX = -1,
            lastY = -1,
            lastR = -1,
        )
    }

    /** `solve_game`: always `S`, which installs a sorted board. */
    override fun solve(state: Game.State, auxInfo: String?): String = "S"

    /**
     * `execute_move`. `S` replaces the grid with a sorted one and clears
     * every orientation. A move counts even when its direction is a
     * multiple of four and the board does not change.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State

        if (move == "S") {
            val grid = from.grid.copyOf()
            grid.sort()
            for (i in grid.indices) grid[i] = grid[i] and 3.inv()
            return State(
                width = from.width,
                height = from.height,
                n = from.n,
                orientable = from.orientable,
                grid = grid,
                completed = 1,
                usedSolve = true,
                moveCount = 1,
                moveTarget = from.moveTarget,
                lastX = from.lastX,
                lastY = from.lastY,
                lastR = from.lastR,
            )
        }

        if (move.isEmpty() || move[0] != 'M') return null
        val triple = scanThreeInts(move, 1) ?: return null
        val (x, y, dir) = triple
        if (x < 0 || y < 0 || x > from.width - from.n || y > from.height - from.n) {
            return null
        }

        val grid = from.grid.copyOf()
        val moveCount = from.moveCount + 1
        TwiddleGeneration.rotate(grid, from.width, from.n, from.orientable, x, y, dir)

        var completed = from.completed
        if (completed == 0 && TwiddleGeneration.gridComplete(grid, from.orientable)) {
            completed = moveCount
        }

        return State(
            width = from.width,
            height = from.height,
            n = from.n,
            orientable = from.orientable,
            grid = grid,
            completed = completed,
            usedSolve = from.usedSolve,
            moveCount = moveCount,
            moveTarget = from.moveTarget,
            lastX = x,
            lastY = y,
            lastR = dir,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed != 0) 1 else 0

    /**
     * `game_status` in words, as `game_redraw` writes it. A solve has its
     * own line, and the target suffix is never appended to it.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        if (s.usedSolve) {
            return "Moves since auto-solve: ${s.moveCount - s.completed}"
        }
        val prefix = if (s.completed != 0) "COMPLETED! " else ""
        val moves = if (s.completed != 0) s.completed else s.moveCount
        val text = prefix + "Moves: $moves"
        return if (s.moveTarget != 0) "$text (target ${s.moveTarget})" else text
    }

    /**
     * `game_text_format`. The column width is the widest number, the
     * orientation letter follows immediately, and every row ends with a
     * newline.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        var column = 0
        for (value in s.grid) {
            val length = (value / 4).toString().length
            if (column < length) column = length
        }

        val out = StringBuilder()
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val value = s.grid[y * s.width + x]
                out.append((value / 4).toString().padStart(column))
                if (s.orientable) out.append("^<v>"[value and 3])
                out.append(if (x + 1 == s.width) '\n' else ' ')
            }
        }
        return out.toString()
    }

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val s = newState as State
        return s.completed != 0 &&
            !s.usedSolve &&
            oldState != null &&
            (oldState as State).completed == 0
    }

    /** `game_request_keys` is NULL: no labels and the default arrow mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(
            tileSize * p.width + 2 * (tileSize / 2),
            tileSize * p.height + 2 * (tileSize / 2),
        )
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorVisible = visible
    }

    /**
     * `game_get_cursor_location`. The rectangle covers the whole `n` by
     * `n` rotation region, and it has no board clamp because the cursor
     * is always inside the region grid.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorVisible) return null
        val s = state as State
        return Game.CursorRect(
            u.curX * tileSize + tileSize / 2,
            u.curY * tileSize + tileSize / 2,
            s.n * tileSize,
            s.n * tileSize,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(8 * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        // The cursor is the background with a red tinge. The two gentle
        // colours are the background scaled, not the highlight scaled.
        for (i in 0 until 3) {
            val bg = ret[COL_BACKGROUND * 3 + i]
            ret[COL_HIGHCURSOR * 3 + i] = bg * if (i == 0) 1.0f else 0.5f
        }
        for (i in 0 until 3) {
            val bg = ret[COL_BACKGROUND * 3 + i]
            ret[COL_HIGHLIGHT_GENTLE * 3 + i] = bg * 1.1f
            ret[COL_LOWLIGHT_GENTLE * 3 + i] = bg * 0.9f
            ret[COL_TEXT * 3 + i] = 0.0f
            ret[COL_LOWCURSOR * 3 + i] = ret[COL_HIGHCURSOR * 3 + i] * 0.6f
        }
        return ret
    }

    /**
     * `game_redraw` in the settled state. C draws the recessed frame once
     * per drawstate and redraws only changed tiles. This game uses
     * [Game.NoDrawState], so every call is a first redraw and draws
     * everything. There is no animation and no flash, so the background
     * colour is always `COL_BACKGROUND`.
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
        val border = tileSize / 2
        val highlight = tileSize / 20
        val bgcolour = COL_BACKGROUND

        fun coord(value: Int): Int = value * tileSize + border

        // A hidden cursor uses `-n`, which no cell can match.
        val cx = if (u.cursorVisible) u.curX else -s.n
        val cy = if (u.cursorVisible) u.curY else -s.n

        val coords = IntArray(10)
        coords[0] = coord(s.width) + highlight - 1
        coords[1] = coord(s.height) + highlight - 1
        coords[2] = coords[0]
        coords[3] = coord(0) - highlight
        coords[4] = coords[2] - tileSize
        coords[5] = coords[3] + tileSize
        coords[8] = coord(0) - highlight
        coords[9] = coord(s.height) + highlight - 1
        coords[6] = coords[8] + tileSize
        coords[7] = coords[9] - tileSize
        sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)

        coords[1] = coord(0) - highlight
        coords[0] = coords[1]
        sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)

        for (i in 0 until s.count) {
            val tx = i % s.width
            val ty = i / s.width
            val x = coord(tx)
            val y = coord(ty)

            var edges = 0
            if (tx == cx && ty >= cy && ty <= cy + s.n - 1) edges = edges or CUR_LEFT
            if (ty == cy && tx >= cx && tx <= cx + s.n - 1) edges = edges or CUR_TOP
            if (tx == cx + s.n - 1 && ty >= cy && ty <= cy + s.n - 1) {
                edges = edges or CUR_RIGHT
            }
            if (ty == cy + s.n - 1 && tx >= cx && tx <= cx + s.n - 1) {
                edges = edges or CUR_BOTTOM
            }

            drawTile(sink, s, x, y, s.grid[i], bgcolour, edges, tileSize)
        }
    }

    /** `draw_tile`: four side triangles, the inner area, then the number. */
    private fun drawTile(
        sink: DrawSink,
        state: State,
        x: Int,
        y: Int,
        tile: Int,
        flashColour: Int,
        edges: Int,
        tileSize: Int,
    ) {
        val highlight = tileSize / 20
        val tri = IntArray(6)
        tri[4] = x + tileSize / 2
        tri[5] = y + tileSize / 2

        // The right and bottom edges use the darker colour.
        tri[0] = x + tileSize - 1
        tri[1] = y + tileSize - 1
        tri[2] = x + tileSize - 1
        tri[3] = y
        val rightColour = if (edges and CUR_RIGHT != 0) COL_LOWCURSOR else COL_LOWLIGHT
        sink.poly(tri, rightColour, rightColour)

        tri[2] = x
        tri[3] = y + tileSize - 1
        val bottomColour = if (edges and CUR_BOTTOM != 0) COL_LOWCURSOR else COL_LOWLIGHT
        sink.poly(tri, bottomColour, bottomColour)

        // The left and top edges use the lighter colour.
        tri[0] = x
        tri[1] = y
        val leftColour = if (edges and CUR_LEFT != 0) COL_HIGHCURSOR else COL_HIGHLIGHT
        sink.poly(tri, leftColour, leftColour)

        tri[2] = x + tileSize - 1
        tri[3] = y
        val topColour = if (edges and CUR_TOP != 0) COL_HIGHCURSOR else COL_HIGHLIGHT
        sink.poly(tri, topColour, topColour)

        sink.rect(
            x + highlight,
            y + highlight,
            tileSize - 2 * highlight,
            tileSize - 2 * highlight,
            flashColour,
        )

        if (state.orientable) {
            var xdx: Int
            var xdy: Int
            var ydx: Int
            var ydy: Int
            when (tile and 3) {
                0 -> {
                    xdx = 1
                    xdy = 0
                    ydx = 0
                    ydy = 1
                }

                1 -> {
                    xdx = 0
                    xdy = -1
                    ydx = 1
                    ydy = 0
                }

                2 -> {
                    xdx = -1
                    xdy = 0
                    ydx = 0
                    ydy = -1
                }

                else -> {
                    xdx = 0
                    xdy = 1
                    ydx = -1
                    ydy = 0
                }
            }

            val cx = x + tileSize / 2
            val cy = y + tileSize / 2
            val displ = tileSize / 2 - highlight - 2
            val displ2 = tileSize / 3 - highlight

            tri[0] = cx - displ * xdx + displ2 * ydx
            tri[1] = cy - displ * xdy + displ2 * ydy
            tri[2] = cx + displ * xdx + displ2 * ydx
            tri[3] = cy + displ * xdy + displ2 * ydy
            tri[4] = cx - displ * ydx
            tri[5] = cy - displ * ydy
            sink.poly(tri, COL_LOWLIGHT_GENTLE, COL_LOWLIGHT_GENTLE)
        }

        sink.text(
            x + tileSize / 2,
            y + tileSize / 2,
            Text.FONT_VARIABLE,
            tileSize / 3,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            COL_TEXT,
            (tile / 4).toString(),
        )

        sink.update(x, y, tileSize, tileSize)
    }

    /**
     * `current_key_label`. C tests the raw button and returns a label
     * only for the two selections while the cursor is visible. The seam
     * has no call site for it yet, so it is a public helper.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if (!u.cursorVisible) return ""
        return when (button) {
            Buttons.CURSOR_SELECT -> "Turn left"
            Buttons.CURSOR_SELECT2 -> "Turn right"
            else -> ""
        }
    }

    /**
     * `interpret_move`. Entry strips `CTRL` and `SHFT` but keeps
     * `MOD_NUM_KEYPAD`, as C does. An inert click returns
     * [MoveResult.Unused] and does not hide the cursor.
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
        val key = button and (Buttons.MOD_MASK.inv() or Buttons.MOD_NUM_KEYPAD)

        if (Buttons.isCursorMove(key)) {
            return moveCursor(key, u, s)
        }

        var cx: Int
        var cy: Int
        var dir: Int
        if (key == Buttons.LEFT_BUTTON || key == Buttons.RIGHT_BUTTON) {
            cx = fromCoord(x - (s.n - 1) * tileSize / 2, tileSize)
            cy = fromCoord(y - (s.n - 1) * tileSize / 2, tileSize)
            dir = if (key == Buttons.LEFT_BUTTON) 1 else -1
            if (cx < 0 || cx > s.width - s.n || cy < 0 || cy > s.height - s.n) {
                return MoveResult.Unused
            }
            u.cursorVisible = false
        } else if (key == Buttons.CURSOR_SELECT || key == Buttons.CURSOR_SELECT2) {
            if (!u.cursorVisible) {
                u.cursorVisible = true
                return MoveResult.UiUpdate
            }
            cx = u.curX
            cy = u.curY
            dir = if (key == Buttons.CURSOR_SELECT2) -1 else +1
        } else {
            when (key) {
                'a'.code, 'A'.code, Buttons.MOD_NUM_KEYPAD + '7'.code -> {
                    cx = 0
                    cy = 0
                    dir = if (key == 'A'.code) -1 else +1
                }

                'b'.code, 'B'.code, Buttons.MOD_NUM_KEYPAD + '9'.code -> {
                    cx = s.width - s.n
                    cy = 0
                    dir = if (key == 'B'.code) -1 else +1
                }

                'c'.code, 'C'.code, Buttons.MOD_NUM_KEYPAD + '1'.code -> {
                    cx = 0
                    cy = s.height - s.n
                    dir = if (key == 'C'.code) -1 else +1
                }

                'd'.code, 'D'.code, Buttons.MOD_NUM_KEYPAD + '3'.code -> {
                    cx = s.width - s.n
                    cy = s.height - s.n
                    dir = if (key == 'D'.code) -1 else +1
                }

                Buttons.MOD_NUM_KEYPAD + '8'.code -> {
                    if ((s.width - s.n) % 2 != 0) return MoveResult.Unused
                    cx = (s.width - s.n) / 2
                    cy = 0
                    dir = +1
                }

                Buttons.MOD_NUM_KEYPAD + '2'.code -> {
                    if ((s.width - s.n) % 2 != 0) return MoveResult.Unused
                    cx = (s.width - s.n) / 2
                    cy = s.height - s.n
                    dir = +1
                }

                Buttons.MOD_NUM_KEYPAD + '4'.code -> {
                    if ((s.height - s.n) % 2 != 0) return MoveResult.Unused
                    cx = 0
                    cy = (s.height - s.n) / 2
                    dir = +1
                }

                Buttons.MOD_NUM_KEYPAD + '6'.code -> {
                    if ((s.height - s.n) % 2 != 0) return MoveResult.Unused
                    cx = s.width - s.n
                    cy = (s.height - s.n) / 2
                    dir = +1
                }

                Buttons.MOD_NUM_KEYPAD + '5'.code -> {
                    if ((s.width - s.n) % 2 != 0 || (s.height - s.n) % 2 != 0) {
                        return MoveResult.Unused
                    }
                    cx = (s.width - s.n) / 2
                    cy = (s.height - s.n) / 2
                    dir = +1
                }

                else -> return MoveResult.Unused
            }
        }

        return MoveResult.Move("M$cx,$cy,$dir")
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell. A twiddle click turns the `n` by `n` block
     * around that cell, so a cell offers `Turn left` and `Turn right`
     * only when that block lies inside the board; the cells of the
     * selected block carry `, cursor`.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val t = tileSize
        val facing = arrayOf("up", "left", "down", "right")
        val cells = gridSpeech(
            width = s.width,
            height = s.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val value = s.grid[y * s.width + x]
                val number = "tile ${value / 4}"
                val orientation = if (s.orientable) ", facing ${facing[value and 3]}" else ""
                val cursor = if (u.cursorVisible &&
                    x >= u.curX && x < u.curX + s.n &&
                    y >= u.curY && y < u.curY + s.n
                ) {
                    ", cursor"
                } else {
                    ""
                }
                number + orientation + cursor
            },
            actions = { x, y ->
                if (regionOrigin(s, x, y, t) != null) {
                    listOf(
                        SpokenAction("Turn left", Buttons.LEFT_BUTTON),
                        SpokenAction("Turn right", Buttons.RIGHT_BUTTON),
                    )
                } else {
                    emptyList()
                }
            },
        )
        val cursor = cells.units.getOrNull(u.curY * s.width + u.curX)
        return cells.capped(cursor)
    }

    /**
     * The top-left cell of the `n` by `n` block a click on cell
     * `(x, y)` turns, or null when the block would leave the board.
     * This mirrors the `fromCoord` arithmetic of `interpret_move`.
     */
    private fun regionOrigin(s: State, x: Int, y: Int, tileSize: Int): Pair<Int, Int>? {
        // The shell clicks the unit centre, `left + tileSize / 2`.
        val clickX = tileSize / 2 + x * tileSize + tileSize / 2
        val clickY = tileSize / 2 + y * tileSize + tileSize / 2
        val cx = fromCoord(clickX - (s.n - 1) * tileSize / 2, tileSize)
        val cy = fromCoord(clickY - (s.n - 1) * tileSize / 2, tileSize)
        return if (cx in 0..(s.width - s.n) && cy in 0..(s.height - s.n)) {
            cx to cy
        } else {
            null
        }
    }

    /**
     * `move_cursor` with `wrap = false`. A clamped move is a UI update
     * when the cursor moved or became visible, and a no-effect key when
     * it was already visible and at the edge.
     */
    private fun moveCursor(button: Int, ui: Ui, state: State): MoveResult {
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
        val oldX = ui.curX
        val oldY = ui.curY
        ui.curX = (ui.curX + dx).coerceIn(0, state.width - state.n)
        ui.curY = (ui.curY + dy).coerceIn(0, state.height - state.n)

        if (!ui.cursorVisible) {
            ui.cursorVisible = true
            return MoveResult.UiUpdate
        }
        if (ui.curX != oldX || ui.curY != oldY) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /** `FROMCOORD`: C integer division truncates toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

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
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            if (!overflow) {
                value = value * 10 + (text[i] - '0')
                if (value > Int.MAX_VALUE) overflow = true
            }
            i++
        }
        if (overflow) return if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE
        return sign * value.toInt()
    }

    /**
     * `sscanf(move, "%d,%d,%d")` after the leading `M`: each `%d` skips
     * white space, the comma must follow exactly, and trailing junk is
     * ignored. All three numbers must be present.
     */
    private fun scanThreeInts(text: String, start: Int): Triple<Int, Int, Int>? {
        var index = start
        val first = scanInt(text, index) ?: return null
        index = first.second
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = scanInt(text, index) ?: return null
        index = second.second
        if (index >= text.length || text[index] != ',') return null
        index++
        val third = scanInt(text, index) ?: return null
        return Triple(first.first, second.first, third.first)
    }

    /** One `%d`: the value and the index just after the last digit. */
    private fun scanInt(text: String, start: Int): Pair<Int, Int>? {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var sign = 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            if (text[i] == '-') sign = -1
            i++
        }
        val digitsStart = i
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            if (!overflow) {
                value = value * 10 + (text[i] - '0')
                if (value > Int.MAX_VALUE) overflow = true
            }
            i++
        }
        if (i == digitsStart) return null
        if (overflow) {
            return (if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE) to i
        }
        return (sign * value.toInt()) to i
    }
}
