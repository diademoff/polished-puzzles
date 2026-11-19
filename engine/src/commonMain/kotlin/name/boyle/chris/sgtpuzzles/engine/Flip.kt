package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `flip.c`.
 *
 * The rules are: a click at `(x,y)` toggles the lights in row
 * `(y*w+x)` of a click matrix over GF(2), and the goal is to turn every
 * light off. The description is the click matrix and the lit grid as two
 * hex bitmaps. One click is one `M<x>,<y>` move.
 *
 * The solver returns a hint mask, not a solution: an `S` move marks the
 * cells to click, sets `cheated` and `hintsActive`, and leaves the
 * lights alone. The player then clicks the hinted cells to complete the
 * game. The status text tells the two phases apart.
 *
 * The generator is in [FlipGeneration]. This game uses
 * [Game.NoDrawState], so [redraw] draws the grid and the whole board on
 * every call; C draws the grid once per drawstate. The diagonal-flip
 * animation and the completion flash are not ported: the seam has no
 * animation time, and `docs/PARITY.md` allows animation differences. The
 * state, the text format, and the save file do not depend on them.
 */
object Flip : Game {

    private const val PREFERRED_TILE_SIZE = 48

    private const val COL_BACKGROUND = 0
    private const val COL_WRONG = 1
    private const val COL_RIGHT = 2
    private const val COL_GRID = 3
    private const val COL_DIAG = 4
    private const val COL_HINT = 5
    private const val COL_CURSOR = 6
    private const val NCOLOURS = 7

    /** `enum { CROSSES, RANDOM }`: how a click matrix is made. */
    enum class MatrixType { CROSSES, RANDOM }

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val matrixType: MatrixType,
    ) : Game.Params {

        val fullText: String get() = encodeParams(this, true)

        val shortText: String get() = encodeParams(this, false)

        override fun copy(): Game.Params = Params(width, height, matrixType)
    }

    /**
     * `struct game_state`. The matrix is shared by every state of a game
     * and is never modified after construction, so the array can be
     * shared without a copy. The grid is private per state.
     */
    class State(
        val width: Int,
        val height: Int,
        val matrix: ByteArray,
        val grid: ByteArray,
        val moves: Int,
        val completed: Boolean,
        val cheated: Boolean,
        val hintsActive: Boolean,
    ) : Game.State {

        val count: Int get() = width * height

        /** True when row `(x,y)` of the click matrix has any set entry. */
        fun rowHasEffect(x: Int, y: Int): Boolean {
            val wh = count
            val row = (y * width + x) * wh
            for (j in 0 until wh) {
                if (matrix[row + j].toInt() != 0) return true
            }
            return false
        }

        override fun copy(): Game.State = State(
            width,
            height,
            matrix,
            grid.copyOf(),
            moves,
            completed,
            cheated,
            hintsActive,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                matrix.contentEquals(other.matrix) &&
                grid.contentEquals(other.grid) &&
                moves == other.moves &&
                completed == other.completed &&
                cheated == other.cheated &&
                hintsActive == other.hintsActive

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + matrix.contentHashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + moves
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + hintsActive.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. `cx` and `cy` stay inside the board. `cdraw`
     * decides whether the cursor is drawn and whether a select uses the
     * cursor cell.
     */
    class Ui : Game.Ui {
        var cx = 0
        var cy = 0
        var cdraw = false
    }

    override val name: String = "Flip"
    override val backendName: String = "flip"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    // `flip.c` has no SMALL_SCREEN or SLOW_SYSTEM conditional, so the
    // Android build and the oracle expose the same six presets.
    private val presetValues = listOf(
        Params(3, 3, MatrixType.CROSSES) to "3x3 Crosses",
        Params(4, 4, MatrixType.CROSSES) to "4x4 Crosses",
        Params(5, 5, MatrixType.CROSSES) to "5x5 Crosses",
        Params(3, 3, MatrixType.RANDOM) to "3x3 Random",
        Params(4, 4, MatrixType.RANDOM) to "4x4 Random",
        Params(5, 5, MatrixType.RANDOM) to "5x5 Random",
    )

    override fun defaultParams(): Game.Params = Params(5, 5, MatrixType.CROSSES)

    /**
     * The full parameters, because the short form drops the shape. A
     * preset that passed `3x3` would always generate a Crosses game.
     */
    override fun presets(): List<Preset> = presetValues.map { (params, title) ->
        Preset(title, params.fullText)
    }

    /**
     * `decode_params`. C writes into an existing structure; every caller
     * starts from the defaults, so the shape defaults to CROSSES. The
     * digit cursor starts at the beginning of the string, so a sign or a
     * space stops it before the `x`.
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
        var matrixType = MatrixType.CROSSES
        if (index < text.length && text[index] == 'r') {
            matrixType = MatrixType.RANDOM
        } else if (index < text.length && text[index] == 'c') {
            matrixType = MatrixType.CROSSES
        }
        return Params(width, height, matrixType)
    }

    /** `encode_params`: the shape suffix is part of the full form only. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val suffix = if (!full) {
            ""
        } else if (p.matrixType == MatrixType.CROSSES) {
            "c"
        } else {
            "r"
        }
        return "${p.width}x${p.height}$suffix"
    }

    /**
     * `validate_params`, in the C order. The third message can only be
     * reached when `w*h*w*h` would not fit in an `Int`, because the
     * second test already caps `w*h`.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width <= 0 || p.height <= 0) {
            return "Width and height must both be greater than zero"
        }
        if (p.width > (Int.MAX_VALUE - 3) / p.height) {
            return "Width times height must not be unreasonably large"
        }
        val wh = p.width * p.height
        if (wh > (Int.MAX_VALUE - 3) / wh) {
            return "Width times height is too large"
        }
        return null
    }

    /** `game_configure` (`flip.c:150-174`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Choices(
                name = "Shape type",
                choices = ":Crosses:Random",
                keywords = null,
                selected = p.matrixType.ordinal,
            ),
        )
    }

    /** `custom_params` (`flip.c:176-185`): `matrix_type = selected`. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            MatrixType.values()[(items[2] as ConfigItem.Choices).selected],
        )

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        return Description(
            FlipGeneration.newDesc(
                p.width,
                p.height,
                p.matrixType == MatrixType.RANDOM,
                random,
            ),
        )
    }

    /**
     * `validate_desc`: the matrix has `(wh*wh+3)/4` hex digits, then a
     * comma, then `(wh+3)/4` hex digits, then the end of the string.
     * Nothing checks the diagonal, symmetry, or solvability.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val wh = p.width * p.height
        val mlen = (wh * wh + 3) / 4
        val glen = (wh + 3) / 4

        if (hexRun(desc, 0) != mlen) return "Matrix description is wrong length"
        if (charAt(desc, mlen) != ',') return "Expected comma after matrix description"
        if (hexRun(desc, mlen + 1) != glen) return "Grid description is wrong length"
        if (charAt(desc, mlen + 1 + glen) != '\u0000') {
            return "Unexpected data after grid description"
        }
        return null
    }

    /** `new_game`: decode the matrix and the grid, and start at zero moves. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.width * p.height
        val mlen = (wh * wh + 3) / 4
        return State(
            width = p.width,
            height = p.height,
            matrix = FlipGeneration.decodeBitmap(desc, wh * wh),
            grid = FlipGeneration.decodeBitmap(
                if (desc.length > mlen + 1) desc.substring(mlen + 1) else "",
                wh,
            ),
            moves = 0,
            completed = false,
            cheated = false,
            hintsActive = false,
        )
    }

    /**
     * `solve_game`. The result is the `S` hint mask with the fewest set
     * bits, not a board transformation. The equations are the transpose
     * of the click matrix with the lit bit as the right-hand side; the
     * free columns are enumerated, and the shortest solution wins.
     *
     * The seam returns null for an insoluble hand-written position, as a
     * game without a solver would. [solveGame] carries the C message
     * "No solution exists for this position" (`flip.c:747`) in that
     * case. Generated games are always soluble.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /**
     * `solve_game` with the C error channel. The inconsistent-equation
     * branch is the only null result of the solver.
     */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val move = solveFlip(state as State)
        return SolveOutcome(
            move,
            if (move == null) "No solution exists for this position" else null,
        )
    }

    private fun solveFlip(s: State): String? {
        val wh = s.count
        val stride = wh + 1
        val equations = ByteArray(stride * wh)

        for (i in 0 until wh) {
            for (j in 0 until wh) {
                equations[i * stride + j] = s.matrix[j * wh + i]
            }
            equations[i * stride + wh] = (s.grid[i].toInt() and 1).toByte()
        }

        var rowsDone = 0
        var colsDone = 0
        var nund = 0
        val und = IntArray(wh)

        // Gaussian elimination over GF(2).
        do {
            var i = colsDone
            var j = -1
            while (i < wh) {
                j = rowsDone
                while (j < wh) {
                    if (equations[j * stride + i].toInt() != 0) break
                    j++
                }
                if (j < wh) break
                // This column has no controlling equation.
                und[nund++] = i
                i++
            }

            if (i == wh) {
                // Every remaining row must read 0 = 0.
                for (row in rowsDone until wh) {
                    if (equations[row * stride + wh].toInt() != 0) return null
                }
                break
            }

            if (j > rowsDone) rowXor(equations, rowsDone * stride, j * stride, stride)
            for (row in rowsDone + 1 until wh) {
                if (equations[row * stride + i].toInt() != 0) {
                    rowXor(equations, row * stride, rowsDone * stride, stride)
                }
            }

            rowsDone++
            colsDone = i + 1
        } while (rowsDone < wh)

        val solution = ByteArray(wh)
        var shortest = ByteArray(wh)
        var bestLength = wh + 1

        // Enumerate the free variables as a binary counter.
        while (true) {
            for (j in rowsDone - 1 downTo 0) {
                var i = 0
                while (i < wh && equations[j * stride + i].toInt() == 0) i++
                check(i < wh) { "an eliminated row has no pivot" }

                var value = equations[j * stride + wh].toInt()
                for (k in i + 1 until wh) {
                    if (equations[j * stride + k].toInt() != 0) {
                        value = value xor solution[k].toInt()
                    }
                }
                solution[i] = value.toByte()
            }

            var length = 0
            for (i in 0 until wh) {
                if (solution[i].toInt() != 0) length++
            }
            if (length < bestLength) {
                bestLength = length
                shortest = solution.copyOf()
            }

            var i = 0
            while (i < nund) {
                solution[und[i]] = (solution[und[i]].toInt() xor 1).toByte()
                if (solution[und[i]].toInt() != 0) break
                i++
            }
            if (i == nund) break
        }

        val out = StringBuilder(wh + 1)
        out.append('S')
        for (i in 0 until wh) out.append(if (shortest[i].toInt() != 0) '1' else '0')
        return out.toString()
    }

    /**
     * `execute_move`. An `S` move of exactly `wh+1` characters sets the
     * hint bits and the cheated flags; any non-`'0'` character is a
     * hint. An `M` move is a loose `sscanf("%d,%d")` parse with board
     * bounds. The completed flag is sticky, and `moves` is frozen once
     * the game is completed.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val wh = from.count

        if (move.isNotEmpty() && move[0] == 'S' && move.length == wh + 1) {
            val grid = from.grid.copyOf()
            for (i in 0 until wh) {
                grid[i] = (grid[i].toInt() and 2.inv()).toByte()
                if (move[i + 1] != '0') grid[i] = (grid[i].toInt() or 2).toByte()
            }
            return State(
                from.width,
                from.height,
                from.matrix,
                grid,
                from.moves,
                from.completed,
                cheated = true,
                hintsActive = true,
            )
        }

        if (move.isNotEmpty() && move[0] == 'M') {
            val first = scanInt(move, 1) ?: return null
            if (first.end >= move.length || move[first.end] != ',') return null
            val second = scanInt(move, first.end + 1) ?: return null
            val x = first.value
            val y = second.value
            if (x < 0 || x >= from.width || y < 0 || y >= from.height) return null

            val moves = if (from.completed) from.moves else from.moves + 1
            val i = y * from.width + x
            val row = i * wh
            val grid = from.grid.copyOf()
            var done = true
            for (j in 0 until wh) {
                grid[j] = (grid[j].toInt() xor from.matrix[row + j].toInt()).toByte()
                if (grid[j].toInt() and 1 != 0) done = false
            }
            grid[i] = (grid[i].toInt() xor 2).toByte()

            return State(
                from.width,
                from.height,
                from.matrix,
                grid,
                moves,
                completed = from.completed || done,
                cheated = from.cheated,
                hintsActive = if (done) false else from.hintsActive,
            )
        }

        return null
    }

    /** `game_status`: a win, or still open. Flip has no loss state. */
    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** `game_status` in words, with the C space after `COMPLETED!`. */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val prefix = when {
            s.completed && s.cheated -> "Auto-solved."
            s.completed -> "COMPLETED!"
            s.cheated -> "Auto-solver used."
            else -> ""
        }
        val moves = "Moves: ${s.moves}"
        return if (prefix.isEmpty()) moves else "$prefix $moves"
    }

    /**
     * `game_text_format`. Every cell is a four-by-four box; the clicked
     * cell's lit state is repeated at every position its matrix row
     * affects. There is a trailing newline and no text primitive in
     * [redraw].
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val wh = w * h
        val cw = 4
        val ch = 4
        val gw = w * cw + 2
        val gh = h * ch + 1
        val len = gw * gh
        val board = CharArray(len + 1) { ' ' }

        for (r in 0 until h) {
            for (c in 0 until w) {
                val cell = r * ch * gw + c * cw
                val center = cell + (ch / 2) * gw + cw / 2
                val flip = if (s.grid[r * w + c].toInt() and 1 != 0) '#' else '.'
                val dyFrom = -1 + (if (r == 0) 1 else 0)
                val dyTo = 1 - (if (r == h - 1) 1 else 0)
                val dxFrom = -1 + (if (c == 0) 1 else 0)
                val dxTo = 1 - (if (c == w - 1) 1 else 0)
                for (dy in dyFrom..dyTo) {
                    for (dx in dxFrom..dxTo) {
                        if (s.matrix[(r * w + c) * wh + (r + dy) * w + c + dx].toInt() != 0) {
                            board[center + dy * gw + dx] = flip
                        }
                    }
                }
                board[cell] = '+'
                for (dx in 1 until cw) board[cell + dx] = '-'
                for (dy in 1 until ch) board[cell + dy * gw] = '|'
            }
            board[r * ch * gw + gw - 2] = '+'
            board[r * ch * gw + gw - 1] = '\n'
            for (dy in 1 until ch) {
                board[r * ch * gw + gw - 2 + dy * gw] = '|'
                board[r * ch * gw + gw - 1 + dy * gw] = '\n'
            }
        }

        for (dx in 0 until gw - 2) board[len - gw + dx] = '-'
        for (c in 0..w) board[len - gw + cw * c] = '+'
        board[len - 1] = '\n'
        return String(board, 0, len)
    }

    /**
     * `game_changed_state`, including the vendored `!cheated` test: a
     * solve never raises a completion event.
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

    /** `game_request_keys`: no extra labels, Android arrow-left mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT)

    /** `game_compute_size`: the board is `T*w + 2*(T/2)` each way. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 2
        return Size(
            tileSize * p.width + 2 * border,
            tileSize * p.height + 2 * border,
        )
    }

    /** C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cdraw = visible
    }

    /** `game_get_cursor_location`: only a drawn cursor has a rectangle. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cdraw) return null
        val border = tileSize / 2
        return Game.CursorRect(
            u.cx * tileSize + border,
            u.cy * tileSize + border,
            tileSize,
            tileSize,
        )
    }

    /**
     * `game_colours`. The game does not use `game_mkhighlight`: the
     * wrong and grid colours are divisions of the background, and the
     * right colour is white. The float arithmetic is the C order.
     */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]

        for (i in 0 until 3) {
            ret[COL_WRONG * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 3
            ret[COL_RIGHT * 3 + i] = 1.0f
            ret[COL_GRID * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 1.5f
            ret[COL_DIAG * 3 + i] = ret[COL_GRID * 3 + i]
        }
        ret[COL_HINT * 3] = 1.0f
        ret[COL_HINT * 3 + 1] = 0.0f
        ret[COL_HINT * 3 + 2] = 0.0f
        ret[COL_CURSOR * 3] = 0.8f
        ret[COL_CURSOR * 3 + 1] = 0.0f
        ret[COL_CURSOR * 3 + 2] = 0.0f
        return ret
    }

    /**
     * `game_redraw` for the first redraw of a drawstate. This game uses
     * [Game.NoDrawState], so the grid lines, the board update, and every
     * tile are drawn on every call. The status text is written by the
     * midend, not here: writing it twice would duplicate the line.
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
        val w = s.width
        val h = s.height
        val wh = w * h
        val border = tileSize / 2

        for (i in 0..w) {
            sink.line(
                i * tileSize + border,
                border,
                i * tileSize + border,
                h * tileSize + border,
                COL_GRID,
            )
        }
        for (i in 0..h) {
            sink.line(
                border,
                i * tileSize + border,
                w * tileSize + border,
                i * tileSize + border,
                COL_GRID,
            )
        }
        sink.update(0, 0, tileSize * w + 2 * border, tileSize * h + 2 * border)

        for (i in 0 until wh) {
            val x = i % w
            val y = i / w
            var tile = s.grid[i].toInt()
            if (!s.hintsActive) tile = tile and 2.inv()
            if (u.cdraw && u.cx == x && u.cy == y) tile = tile or 4
            drawTile(sink, s, x, y, tile, tileSize)
        }
    }

    private fun drawTile(
        sink: DrawSink,
        state: State,
        x: Int,
        y: Int,
        tile: Int,
        tileSize: Int,
    ) {
        val w = state.width
        val h = state.height
        val wh = w * h
        val border = tileSize / 2
        val bx = x * tileSize + border
        val by = y * tileSize + border
        val dcol = if (tile and 4 != 0) COL_CURSOR else COL_DIAG

        sink.clip(bx + 1, by + 1, tileSize - 1, tileSize - 1)
        sink.rect(
            bx + 1,
            by + 1,
            tileSize - 1,
            tileSize - 1,
            if (tile and 1 != 0) COL_WRONG else COL_RIGHT,
        )

        // The diagram is scanned output row outer, output column inner.
        for (i in 0 until h) {
            for (j in 0 until w) {
                if (state.matrix[(y * w + x) * wh + i * w + j].toInt() == 0) continue
                val ox = j - x
                val oy = i - y
                val td = if (tileSize / 16 != 0) tileSize / 16 else 1
                val cx = (bx + tileSize / 2) + (2 * ox - 1) * td
                val cy = (by + tileSize / 2) + (2 * oy - 1) * td
                if (ox == 0 && oy == 0) {
                    sink.rect(cx, cy, 2 * td + 1, 2 * td + 1, dcol)
                } else {
                    sink.line(cx, cy, cx + 2 * td, cy, dcol)
                    sink.line(cx, cy + 2 * td, cx + 2 * td, cy + 2 * td, dcol)
                    sink.line(cx, cy, cx, cy + 2 * td, dcol)
                    sink.line(cx + 2 * td, cy, cx + 2 * td, cy + 2 * td, dcol)
                }
            }
        }

        if (tile and 2 != 0) {
            var x1 = bx + tileSize / 20
            var x2 = bx + tileSize - tileSize / 20
            var y1 = by + tileSize / 20
            var y2 = by + tileSize - tileSize / 20
            repeat(3) {
                sink.line(x1, y1, x2, y1, COL_HINT)
                sink.line(x1, y2, x2, y2, COL_HINT)
                sink.line(x1, y1, x1, y2, COL_HINT)
                sink.line(x2, y1, x2, y2, COL_HINT)
                x1++
                y1++
                x2--
                y2--
            }
        }

        sink.unclip()
        sink.update(bx + 1, by + 1, tileSize - 1, tileSize - 1)
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

        /*
         * The midend canonicalises CTRL and SHFT first: they stay only on
         * a cursor move and on TAB. The game strips them anyway, because
         * it does not use them.
         */
        val stripped = Buttons.stripModifiers(button)
        val key = if (Buttons.isCursorMove(stripped)) {
            // C keeps CTRL and SHFT on a cursor key but always strips
            // MOD_NUM_KEYPAD.
            button and Buttons.MOD_NUM_KEYPAD.inv()
        } else {
            if (button and Buttons.MOD_CTRL != 0 && stripped >= 0x20) {
                return MoveResult.Unused
            }
            stripped
        }

        var nullret: MoveResult = MoveResult.Unused

        if (key == Buttons.LEFT_BUTTON ||
            key == Buttons.CURSOR_SELECT ||
            key == Buttons.CURSOR_SELECT2
        ) {
            val tx: Int
            val ty: Int
            if (key == Buttons.LEFT_BUTTON) {
                tx = fromCoord(x, tileSize)
                ty = fromCoord(y, tileSize)
                u.cdraw = false
            } else {
                tx = u.cx
                ty = u.cy
                // The cursor becomes visible even when the row is empty
                // and the result is MOVE_NO_EFFECT.
                u.cdraw = true
            }
            nullret = MoveResult.UiUpdate

            if (tx in 0 until s.width && ty in 0 until s.height) {
                if (s.rowHasEffect(tx, ty)) {
                    return MoveResult.Move("M$tx,$ty")
                }
                return MoveResult.NoEffect
            }
        } else if (Buttons.isCursorMove(key)) {
            nullret = moveCursor(key, u, s.width, s.height)
        }

        return nullret
    }

    /**
     * `move_cursor` with `wrap = false`: the cursor clamps at the edge
     * and becomes visible on the first move.
     */
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

        if (!u.cdraw) {
            u.cdraw = true
            return MoveResult.UiUpdate
        }
        if (u.cx != oldX || u.cy != oldY) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /**
     * `current_key_label`. There is no hook for it in [Game] and no
     * Android call site, so this is a public helper rather than a seam
     * method. The test is exact equality, so a modified select has no
     * label.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String =
        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            "Flip"
        } else {
            ""
        }

    /** `FROMCOORD`: C division truncates toward zero; Kotlin `/` does too. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell, `light` or `dark` as the manual words it, with
     * `, hint` on a square the solver marked. Every square that has a
     * matrix row offers the game's `Flip` button; a row with no effect
     * would ignore a click, so it offers none.
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
                val i = y * s.width + x
                val value = s.grid[i].toInt()
                val light = if (value and 1 != 0) "dark" else "light"
                val hint = if (s.hintsActive && value and 2 != 0) ", hint" else ""
                val cursor = if (u.cdraw && u.cx == x && u.cy == y) ", cursor" else ""
                light + hint + cursor
            },
            actions = { x, y ->
                if (s.rowHasEffect(x, y)) {
                    listOf(SpokenAction("Flip", Buttons.LEFT_BUTTON))
                } else {
                    emptyList()
                }
            },
        )
        return cells.capped(cells.units.getOrNull(u.cy * s.width + u.cx))
    }

    /** `strspn(desc + start, hex)` without building a substring. */
    private fun hexRun(desc: String, start: Int): Int {
        var i = start
        while (i < desc.length && isHex(desc[i])) i++
        return i - start
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** `desc[index]`, or NUL past the end, as the C string terminator. */
    private fun charAt(desc: String, index: Int): Char =
        if (index < desc.length) desc[index] else '\u0000'

    /**
     * `sscanf("%d")` at [start]: white space, an optional sign, then
     * digits. Returns the value and the first position after the
     * digits.
     */
    private class Scanned(val value: Int, val end: Int)

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

    /** `rowxor`: XOR [source] into [target] over GF(2). */
    private fun rowXor(equations: ByteArray, target: Int, source: Int, length: Int) {
        for (i in 0 until length) {
            equations[target + i] =
                (equations[target + i].toInt() xor equations[source + i].toInt()).toByte()
        }
    }
}
