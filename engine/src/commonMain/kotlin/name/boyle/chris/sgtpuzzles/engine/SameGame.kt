package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `samegame.c`.
 *
 * The rules are: select a 4-connected group of one colour, remove it,
 * let the tiles fall down and the columns move left, and add
 * `max(0, n - scoresub)^2` points. A removal is one `M` move with every
 * removed index. The first activation only selects the group, so a
 * pointer removal needs two taps.
 *
 * The description generator is in [SameGameGeneration]. The state, the
 * moves, the score, the selection, the status text, the palette, and
 * the drawing order match C.
 *
 * This game uses [Game.NoDrawState], so [redraw] draws the whole
 * board and both recessed polygons on every call. The completion flash
 * is not ported: it does not change the state or the save file, and
 * `docs/PARITY.md` allows animation differences.
 */
object SameGame : Game {

    private const val PREFERRED_TILE_SIZE = 32

    /** `HIGHLIGHT_WIDTH` from `samegame.c`. */
    private const val HIGHLIGHT_WIDTH = 2

    private const val COL_BACKGROUND = 0
    private const val COL_1 = 1
    private const val COL_2 = 2
    private const val COL_3 = 3
    private const val COL_4 = 4
    private const val COL_5 = 5
    private const val COL_6 = 6
    private const val COL_7 = 7
    private const val COL_8 = 8
    private const val COL_9 = 9
    private const val COL_IMPOSSIBLE = 10
    private const val COL_SEL = 11
    private const val COL_HIGHLIGHT = 12
    private const val COL_LOWLIGHT = 13
    private const val NCOLOURS = 14

    /**
     * The spoken name of tile colour 1..9, in the palette order of
     * `game_colours`. D-01 section 8: one vocabulary for every game.
     */
    private val SPOKEN_COLOURS = listOf(
        BoardColour.BLUE,
        BoardColour.GREEN,
        BoardColour.RED,
        BoardColour.YELLOW,
        BoardColour.MAGENTA,
        BoardColour.CYAN,
        BoardColour.LIGHT_BLUE,
        BoardColour.LIGHT_GREEN,
        BoardColour.PINK,
    )

    /*
     * The flags are unique across the state, the UI, and the drawstate
     * in C. Only the UI and drawstate bits are used here.
     */
    private const val TILE_COLMASK = 0x00ff
    private const val TILE_SELECTED = 0x0100
    private const val TILE_JOINRIGHT = 0x0200
    private const val TILE_JOINDOWN = 0x0400
    private const val TILE_JOINDIAG = 0x0800
    private const val TILE_HASSEL = 0x1000
    private const val TILE_IMPOSSIBLE = 0x2000

    /**
     * `struct game_params`. `ncols` is the number of tile colours, and
     * `scoresub` is 1 for `(n-1)^2` or 2 for `(n-2)^2`.
     */
    data class Params(
        val width: Int,
        val height: Int,
        val ncols: Int,
        val scoresub: Int,
        val soluble: Boolean,
    ) : Game.Params {

        val fullText: String get() = encodeParams(this, true)

        val shortText: String get() = encodeParams(this, false)

        override fun copy(): Game.Params =
            Params(width, height, ncols, scoresub, soluble)
    }

    /**
     * `struct game_state`. `tiles` holds colours only; the selection is
     * frontend state. `n` is the cached `width * height`.
     */
    class State(
        val params: Params,
        val n: Int,
        val tiles: IntArray,
        var score: Int,
        var complete: Boolean,
        var impossible: Boolean,
    ) : Game.State {

        fun col(x: Int, y: Int): Int = tiles[y * params.width + x] and TILE_COLMASK

        override fun copy(): Game.State =
            State(params, n, tiles.copyOf(), score, complete, impossible)

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                n == other.n &&
                tiles.contentEquals(other.tiles) &&
                score == other.score &&
                complete == other.complete &&
                impossible == other.impossible

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + n
            result = 31 * result + tiles.contentHashCode()
            result = 31 * result + score
            result = 31 * result + complete.hashCode()
            result = 31 * result + impossible.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. The selection is a per-tile flag, and
     * [nselected] feeds the status text. Nothing here is saved.
     */
    class Ui(n: Int) : Game.Ui {
        val selected = BooleanArray(n)
        var nselected = 0
        var xsel = 0
        var ysel = 0
        var displaysel = false
    }

    override val name: String = "Same Game"
    override val backendName: String = "samegame"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = false

    private val presetValues = listOf(
        Params(5, 5, 3, 2, true),
        Params(10, 5, 3, 2, true),
        // `SMALL_SCREEN` does not change this game: `samegame.c` tests
        // only `SLOW_SYSTEM`, which neither the app nor the oracle
        // defines.
        Params(15, 10, 3, 2, true),
        Params(15, 10, 4, 2, true),
        Params(20, 15, 4, 2, true),
    )

    override fun defaultParams(): Game.Params = Params(5, 5, 3, 2, true)

    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.width}x${it.height}, ${it.ncols} colours", it.fullText)
    }

    override fun decodeParams(text: String): Game.Params {
        var p = 0

        var width = atoi(text, p)
        while (p < text.length && text[p] in '0'..'9') p++
        var height = width
        if (p < text.length && text[p] == 'x') {
            p++
            height = atoi(text, p)
            while (p < text.length && text[p] in '0'..'9') p++
        }
        var ncols = 3
        if (p < text.length && text[p] == 'c') {
            p++
            ncols = atoi(text, p)
            while (p < text.length && text[p] in '0'..'9') p++
        }
        var scoresub = 2
        if (p < text.length && text[p] == 's') {
            p++
            scoresub = atoi(text, p)
            while (p < text.length && text[p] in '0'..'9') p++
        }
        var soluble = true
        if (p < text.length && text[p] == 'r') {
            p++
            soluble = false
        }

        return Params(width, height, ncols, scoresub, soluble)
    }

    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.width}x${p.height}c${p.ncols}s${p.scoresub}" +
            if (full && !p.soluble) "r" else ""
    }

    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width < 1 || p.height < 1) {
            return "Width and height must both be positive"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.ncols > 9) {
            return "Maximum of 9 colours"
        }
        if (p.soluble) {
            if (p.ncols < 3) {
                return "Number of colours must be at least three"
            }
            if (p.width * p.height <= 1) {
                return "Grid area must be greater than one"
            }
        } else {
            if (p.ncols < 2) {
                // The C message says "three" even though the test is
                // for two colours. Keep the text.
                return "Number of colours must be at least three"
            }
            if (p.width * p.height < p.ncols * 2) {
                return "Too many colours makes given grid size impossible"
            }
        }
        if (p.scoresub < 1 || p.scoresub > 2) {
            return "Scoring system not recognised"
        }
        return null
    }

    /** `game_configure` (`samegame.c:239-274`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Text("No. of colours", p.ncols.toString()),
            ConfigItem.Choices(
                name = "Scoring system",
                choices = ":(n-1)^2:(n-2)^2",
                keywords = null,
                selected = p.scoresub - 1,
            ),
            ConfigItem.Bool("Ensure solubility", p.soluble),
        )
    }

    /** `custom_params` (`samegame.c:276-287`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
            (items[3] as ConfigItem.Choices).selected + 1,
            (items[4] as ConfigItem.Bool).value,
        )

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(
            SameGameGeneration.newDesc(
                p.width,
                p.height,
                p.ncols,
                p.soluble,
                random,
            ),
        )
    }

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val area = p.width * p.height
        var position = 0

        for (i in 0 until area) {
            val start = position
            if (position >= desc.length || desc[position] !in '0'..'9') {
                return "Not enough numbers in string"
            }
            while (position < desc.length && desc[position] in '0'..'9') position++

            if (i < area - 1 && (position >= desc.length || desc[position] != ',')) {
                return "Expected comma after number"
            } else if (i == area - 1 && position < desc.length) {
                return "Excess junk at end of string"
            }

            val value = atoi(desc, start)
            if (value < 0 || value > p.ncols) {
                return "Colour out of range"
            }

            if (position < desc.length) position++
        }
        return null
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val n = p.width * p.height
        val tiles = IntArray(n)
        var position = 0

        for (i in 0 until n) {
            check(position < desc.length) { "description is shorter than the grid" }
            tiles[i] = atoi(desc, position)
            while (position < desc.length && desc[position] != ',') position++
            if (position < desc.length) position++
        }

        // `new_game` does not call `sg_check`.
        return State(p, n, tiles, score = 0, complete = false, impossible = false)
    }

    /** Same Game has no solver. */
    override fun solve(state: Game.State, auxInfo: String?): String? = null

    override fun executeMove(state: Game.State, move: String): Game.State? {
        if (move.isEmpty() || move[0] != 'M') return null

        val from = state as State
        val next = from.copy() as State
        var tokenCount = 0
        var position = 1

        while (position < move.length) {
            if (move[position] !in '0'..'9') return null
            val index = atoi(move, position)
            if (index < 0 || index >= next.n) return null
            tokenCount++
            next.tiles[index] = 0

            while (position < move.length && move[position] in '0'..'9') position++
            if (position < move.length && move[position] == ',') position++
        }

        // The score uses the token count, not the number of distinct
        // tiles. Duplicate indices are accepted, as in C.
        next.score += npoints(next.params, tokenCount)

        snuggle(next)
        checkState(next)

        return next
    }

    override fun status(state: Game.State): Int =
        if ((state as State).complete) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val u = ui as Ui
        val score = "Score: ${s.score}"
        return when {
            s.complete -> "COMPLETED! $score"
            s.impossible -> "Cannot move! $score"
            u.nselected != 0 ->
                "$score  Selected: ${u.nselected} (${npoints(s.params, u.nselected)})"

            else -> score
        }
    }

    /**
     * `game_changed_state`: every state change clears the selection.
     * The return value says that this transition completed the game.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        selClear(ui as Ui)
        val s = newState as State
        return s.complete && oldState != null && !(oldState as State).complete
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.params.width
        val h = s.params.height
        val out = StringBuilder(h * (w + 1))
        for (y in 0 until h) {
            for (x in 0 until w) {
                // The text format reads the raw value, as C's TILE
                // macro does. A validated description is a colour.
                val t = s.tiles[y * w + x]
                out.append(
                    when {
                        t <= 0 -> ' '
                        t < 10 -> '0' + t
                        else -> 'a' + (t - 10)
                    },
                )
            }
            out.append('\n')
        }
        return out.toString()
    }

    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val tilegap = (tileSize + 8) / 16
        val border = tileSize / 2
        return Size(
            tileSize * p.width + 2 * border - tilegap,
            tileSize * p.height + 2 * border - tilegap,
        )
    }

    /**
     * C reads `PUZZLES_SHOW_CURSOR` here. The seam starts hidden and
     * lets the shell call [setCursorVisible] instead.
     */
    override fun newUi(state: Game.State): Game.Ui = Ui((state as State).n)

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).displaysel = visible
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.displaysel) return null
        val border = tileSize / 2
        return Game.CursorRect(
            u.xsel * tileSize + border,
            u.ysel * tileSize + border,
            tileSize,
            tileSize,
        )
    }

    override fun colours(): FloatArray =
        colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        setColour(ret, COL_1, 0.0f, 0.0f, 1.0f)
        setColour(ret, COL_2, 0.0f, 0.5f, 0.0f)
        setColour(ret, COL_3, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_4, 0.7f, 0.7f, 0.0f)
        setColour(ret, COL_5, 1.0f, 0.0f, 1.0f)
        setColour(ret, COL_6, 0.0f, 0.8f, 0.8f)
        setColour(ret, COL_7, 0.5f, 0.5f, 1.0f)
        setColour(ret, COL_8, 0.2f, 0.8f, 0.2f)
        setColour(ret, COL_9, 1.0f, 0.5f, 0.5f)
        setColour(ret, COL_IMPOSSIBLE, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_SEL, 1.0f, 1.0f, 1.0f)
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
        val w = s.params.width
        val h = s.params.height
        val tilegap = (tileSize + 8) / 16
        val tileinner = tileSize - tilegap
        val border = tileSize / 2

        fun coord(value: Int): Int = value * tileSize + border

        /*
         * The recessed area. C draws it only on the first redraw of a
         * drawstate, but this game uses [Game.NoDrawState], so draw it
         * every time. The result is the same for the first draw.
         */
        val coords = IntArray(10)
        coords[0] = coord(w) + HIGHLIGHT_WIDTH - 1 - tilegap
        coords[1] = coord(h) + HIGHLIGHT_WIDTH - 1 - tilegap
        coords[2] = coords[0]
        coords[3] = coord(0) - HIGHLIGHT_WIDTH
        coords[4] = coords[2] - tileSize
        coords[5] = coords[3] + tileSize
        coords[8] = coord(0) - HIGHLIGHT_WIDTH
        coords[9] = coord(h) + HIGHLIGHT_WIDTH - 1 - tilegap
        coords[6] = coords[8] + tileSize
        coords[7] = coords[9] - tileSize
        sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)

        coords[1] = coord(0) - HIGHLIGHT_WIDTH
        coords[0] = coord(0) - HIGHLIGHT_WIDTH
        sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)

        for (x in 0 until w) {
            for (y in 0 until h) {
                val col = s.col(x, y)
                var tile = col
                val dright = x + 1 < w
                val dbelow = y + 1 < h

                if (u.selected[y * w + x]) tile = tile or TILE_SELECTED
                if (s.impossible) tile = tile or TILE_IMPOSSIBLE
                if (dright && s.col(x + 1, y) == col) tile = tile or TILE_JOINRIGHT
                if (dbelow && s.col(x, y + 1) == col) tile = tile or TILE_JOINDOWN
                if ((tile and TILE_JOINRIGHT) != 0 &&
                    (tile and TILE_JOINDOWN) != 0 &&
                    s.col(x + 1, y + 1) == col
                ) {
                    tile = tile or TILE_JOINDIAG
                }
                if (u.displaysel && u.xsel == x && u.ysel == y &&
                    !(s.complete || s.impossible)
                ) {
                    tile = tile or TILE_HASSEL
                }

                drawTile(
                    sink,
                    coord(x),
                    coord(y),
                    tile,
                    dright,
                    dbelow,
                    tileSize,
                    tileinner,
                    tilegap,
                )
            }
        }
    }

    private fun drawTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        tile: Int,
        dright: Boolean,
        dbelow: Boolean,
        tileSize: Int,
        tileinner: Int,
        tilegap: Int,
    ) {
        var outer = COL_BACKGROUND
        var inner = outer
        val col = tile and TILE_COLMASK

        if (col != 0) {
            if (tile and TILE_IMPOSSIBLE != 0) {
                outer = col
                inner = COL_IMPOSSIBLE
            } else if (tile and TILE_SELECTED != 0) {
                outer = COL_SEL
                inner = col
            } else {
                outer = col
                inner = col
            }
        }

        val tileW = if (dright) tileSize else tileinner
        val tileH = if (dbelow) tileSize else tileinner
        val outerW = if (tile and TILE_JOINRIGHT != 0) tileW else tileinner
        val outerH = if (tile and TILE_JOINDOWN != 0) tileH else tileinner

        if (outerW != tileW || outerH != tileH || outer == COL_BACKGROUND) {
            sink.rect(x, y, tileW, tileH, COL_BACKGROUND)
        }
        if (outer != COL_BACKGROUND) {
            sink.rect(x, y, outerW, outerH, outer)
        }
        if (inner != outer) {
            sink.rect(
                x + tileinner / 4,
                y + tileinner / 4,
                tileinner / 2,
                tileinner / 2,
                inner,
            )
        }

        // Restore the tiny joint square when only the diagonal joins.
        if ((tile and (TILE_JOINRIGHT or TILE_JOINDOWN or TILE_JOINDIAG)) ==
            (TILE_JOINRIGHT or TILE_JOINDOWN) &&
            outer != COL_BACKGROUND &&
            tilegap != 0
        ) {
            sink.rect(x + tileinner, y + tileinner, tilegap, tilegap, COL_BACKGROUND)
        }

        if (tile and TILE_HASSEL != 0) {
            val sx = x + 2
            val sy = y + 2
            val size = tileinner - 5
            val colour = if (outer == COL_SEL) COL_LOWLIGHT else COL_HIGHLIGHT
            sink.line(sx, sy, sx + size, sy, colour)
            sink.line(sx + size, sy, sx + size, sy + size, colour)
            sink.line(sx + size, sy + size, sx, sy + size, colour)
            sink.line(sx, sy + size, sx, sy, colour)
        }

        sink.update(x, y, tileSize, tileSize)
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

        var targetX: Int
        var targetY: Int

        /*
         * C compares the exact button, so a modified cursor move is not
         * a cursor move. The midend keeps CTRL and SHFT on a cursor key,
         * so it falls through to `Unused` here, as in `samegame.c`.
         */
        when (button) {
            Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON -> {
                u.displaysel = false
                targetX = fromCoord(x, tileSize)
                targetY = fromCoord(y, tileSize)
            }

            Buttons.CURSOR_UP, Buttons.CURSOR_DOWN,
            Buttons.CURSOR_LEFT, Buttons.CURSOR_RIGHT,
            -> return moveCursor(button, u, s.params.width, s.params.height)

            Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2 -> {
                u.displaysel = true
                targetX = u.xsel
                targetY = u.ysel
            }

            else -> return MoveResult.Unused
        }

        if (targetX < 0 || targetX >= s.params.width ||
            targetY < 0 || targetY >= s.params.height
        ) {
            return MoveResult.Unused
        }
        if (s.col(targetX, targetY) == 0) return MoveResult.NoEffect

        val index = targetY * s.params.width + targetX
        if (u.selected[index]) {
            if (button == Buttons.RIGHT_BUTTON || button == Buttons.CURSOR_SELECT2) {
                selClear(u)
                return MoveResult.UiUpdate
            }
            return MoveResult.Move(selMoveDesc(u, s))
        }

        selClear(u)
        selExpand(u, s, targetX, targetY)
        return MoveResult.UiUpdate
    }

    private fun moveCursor(button: Int, u: Ui, maxw: Int, maxh: Int): MoveResult {
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

        val oldX = u.xsel
        val oldY = u.ysel
        u.xsel = (u.xsel + dx + maxw) % maxw
        u.ysel = (u.ysel + dy + maxh) % maxh

        if (!u.displaysel) {
            u.displaysel = true
            return MoveResult.UiUpdate
        }
        if (u.xsel != oldX || u.ysel != oldY) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    private fun selClear(u: Ui) {
        u.selected.fill(false)
        u.nselected = 0
    }

    /**
     * `sel_expand`: a repeated full scan, not a BFS. The resulting set is
     * the same, but the scan order matches C.
     */
    private fun selExpand(u: Ui, state: State, tx: Int, ty: Int) {
        val w = state.params.width
        val h = state.params.height
        var ns = 1
        var nadded: Int

        u.selected[ty * w + tx] = true
        do {
            nadded = 0
            for (x in 0 until w) {
                for (y in 0 until h) {
                    if (x == tx && y == ty) continue
                    if (u.selected[y * w + x]) continue

                    val c = state.col(x, y)
                    if (x > 0 && u.selected[y * w + (x - 1)] && state.col(x - 1, y) == c) {
                        u.selected[y * w + x] = true
                        nadded++
                        continue
                    }
                    if (x + 1 < w && u.selected[y * w + (x + 1)] && state.col(x + 1, y) == c) {
                        u.selected[y * w + x] = true
                        nadded++
                        continue
                    }
                    if (y > 0 && u.selected[(y - 1) * w + x] && state.col(x, y - 1) == c) {
                        u.selected[y * w + x] = true
                        nadded++
                        continue
                    }
                    if (y + 1 < h && u.selected[(y + 1) * w + x] && state.col(x, y + 1) == c) {
                        u.selected[y * w + x] = true
                        nadded++
                        continue
                    }
                }
            }
            ns += nadded
        } while (nadded > 0)

        if (ns > 1) {
            u.nselected = ns
        } else {
            selClear(u)
        }
    }

    /** `sel_movedesc`: indices go into the string in ascending order. */
    private fun selMoveDesc(u: Ui, state: State): String {
        val out = StringBuilder("M")
        var first = true
        for (i in 0 until state.n) {
            if (u.selected[i]) {
                if (!first) out.append(',')
                out.append(i)
                first = false
                u.selected[i] = false
            }
        }
        u.nselected = 0
        return out.toString()
    }

    private fun npoints(params: Params, nsel: Int): Int {
        val sdiff = nsel - params.scoresub
        return if (sdiff > 0) sdiff * sdiff else 0
    }

    private fun emptyColumn(state: State, x: Int): Boolean {
        for (y in 0 until state.params.height) {
            if (state.col(x, y) != 0) return false
        }
        return true
    }

    /**
     * `sg_snuggle`: bottom-up gravity in every column, then leftward
     * column compaction. Each pass repeats until nothing moves.
     */
    private fun snuggle(state: State) {
        val w = state.params.width
        val h = state.params.height
        var moved: Int

        do {
            moved = 0
            for (x in 0 until w) {
                for (y in h - 1 downTo 1) {
                    if (state.col(x, y) != 0) continue
                    if (state.col(x, y - 1) != 0) {
                        swap(state, x, y, x, y - 1)
                        moved++
                    }
                }
            }
        } while (moved != 0)

        do {
            moved = 0
            for (x in 0 until w - 1) {
                if (emptyColumn(state, x) && !emptyColumn(state, x + 1)) {
                    moved++
                    for (y in 0 until h) swap(state, x, y, x + 1, y)
                }
            }
        } while (moved != 0)
    }

    private fun swap(state: State, x1: Int, y1: Int, x2: Int, y2: Int) {
        val w = state.params.width
        val first = y1 * w + x1
        val second = y2 * w + x2
        val tmp = state.tiles[first]
        state.tiles[first] = state.tiles[second]
        state.tiles[second] = tmp
    }

    /**
     * `sg_check`: `impossible` means no two adjacent tiles share a
     * colour. A blank board is both complete and impossible.
     */
    private fun checkState(state: State) {
        val w = state.params.width
        val h = state.params.height
        var complete = true
        var impossible = true

        for (x in 0 until w) {
            for (y in 0 until h) {
                if (state.col(x, y) == 0) continue
                complete = false
                if (x + 1 < w && state.col(x, y) == state.col(x + 1, y)) {
                    impossible = false
                }
                if (y + 1 < h && state.col(x, y) == state.col(x, y + 1)) {
                    impossible = false
                }
            }
        }

        state.complete = complete
        state.impossible = impossible
    }

    /** The spoken name of tile colour 1..9, in the shared colour order. */
    internal fun colourName(colour: Int): String =
        SPOKEN_COLOURS.getOrNull(colour - COL_1)?.spokenName ?: "unknown"

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per tile. The tile colour is the only content this game
     * has, so the shared colour name is the cue (section 8), and the
     * pointer path is the game's own two-stage tap: the first tap of a
     * tile selects its 4-connected group, and a tap on a selected tile
     * removes it.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.params.width
        val t = tileSize
        val cells = gridSpeech(
            width = w,
            height = s.params.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val colour = s.col(x, y)
                val content = if (colour == 0) "empty" else colourName(colour)
                val selected = if (u.selected[y * w + x]) SPOKEN_SELECTED else ""
                val cursor = if (u.displaysel && u.xsel == x && u.ysel == y) {
                    ", cursor"
                } else {
                    ""
                }
                "$content$selected$cursor"
            },
            actions = { x, y -> pointer(s, u, x, y) },
        )
        return cells.capped(cells.units.getOrNull(u.ysel * w + u.xsel))
    }

    /**
     * The pointer buttons of one tile, in the words of the game's own
     * cycle. A tap selects a group, a second tap removes it, and the
     * right button clears a selection. A lone tile has no group, so a
     * tap on it does nothing and it offers no action.
     */
    private fun pointer(s: State, u: Ui, x: Int, y: Int): List<SpokenAction> {
        val colour = s.col(x, y)
        if (colour == 0) return emptyList()
        if (u.selected[y * s.params.width + x]) {
            return listOf(
                SpokenAction("Remove", Buttons.LEFT_BUTTON),
                SpokenAction("Unselect", Buttons.RIGHT_BUTTON),
            )
        }
        val hasNeighbour =
            (x > 0 && s.col(x - 1, y) == colour) ||
                (x + 1 < s.params.width && s.col(x + 1, y) == colour) ||
                (y > 0 && s.col(x, y - 1) == colour) ||
                (y + 1 < s.params.height && s.col(x, y + 1) == colour)
        return if (hasNeighbour) {
            listOf(SpokenAction("Select", Buttons.LEFT_BUTTON))
        } else {
            emptyList()
        }
    }

    /**
     * `current_key_label`. There is no hook for it in [Game] yet and no
     * Android call site, so this is a public helper rather than a seam
     * method. The strings are the upstream ones.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {        val s = state as State
        val u = ui as Ui
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) {
            return ""
        }
        val x = u.xsel
        val y = u.ysel
        val c = s.col(x, y)
        if (c == 0) return ""
        if (u.selected[y * s.params.width + x]) {
            return if (button == Buttons.CURSOR_SELECT2) "Unselect" else "Remove"
        }
        if ((x > 0 && s.col(x - 1, y) == c) ||
            (x + 1 < s.params.width && s.col(x + 1, y) == c) ||
            (y > 0 && s.col(x, y - 1) == c) ||
            (y + 1 < s.params.height && s.col(x, y + 1) == c)
        ) {
            return "Select"
        }
        // A lone square cannot be selected.
        if (u.nselected != 0) return "Unselect"
        return ""
    }

    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

    /** C `atoi`: leading whitespace, an optional sign, then digits. */
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
