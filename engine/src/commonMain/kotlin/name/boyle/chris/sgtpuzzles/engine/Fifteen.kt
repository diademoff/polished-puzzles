package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * A port of upstream `fifteen.c`: the standard 15-puzzle.
 *
 * The generator, the move rules, the hint solver, the text format, the
 * status string, and the drawing primitives match the C game. The
 * description is a comma-separated permutation of `0 until w*h`; the
 * gap is the zero, and the solved board is `1,2,...,n-1,0`.
 */
object Fifteen : Game {

    private const val PREFERRED_TILE_SIZE = 48

    private const val COL_BACKGROUND = 0
    private const val COL_TEXT = 1
    private const val COL_HIGHLIGHT = 2
    private const val COL_LOWLIGHT = 3

    data class Params(val width: Int, val height: Int) : Game.Params {
        override fun copy(): Game.Params = Params(width, height)
    }

    class State(
        val width: Int,
        val height: Int,
        val tiles: IntArray,
        val gapPos: Int,
        val completed: Int,
        val moveCount: Int,
        val usedSolve: Boolean,
    ) : Game.State {
        val count: Int get() = width * height

        override fun copy(): Game.State = State(
            width,
            height,
            tiles.copyOf(),
            gapPos,
            completed,
            moveCount,
            usedSolve,
        )

        fun tileAt(x: Int, y: Int): Int = tiles[y * width + x]

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                tiles.contentEquals(other.tiles) &&
                gapPos == other.gapPos &&
                completed == other.completed &&
                moveCount == other.moveCount &&
                usedSolve == other.usedSolve

        override fun hashCode(): Int =
            31 * (31 * tiles.contentHashCode() + gapPos) + completed
    }

    /** `game_ui`: the arrow-key preference of this game. */
    data class Ui(
        /**
         * `arrow-semantics=gap`: the arrow key moves the gap instead of
         * a tile. It is the `invert_cursor` field of `fifteen.c:485`.
         */
        var invertCursor: Boolean = false,
    ) : Game.Ui

    override val name: String = "Fifteen"
    override val backendName: String = "fifteen"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE

    private val presetValues = listOf(Params(4, 4), Params(5, 5), Params(6, 6))

    override fun defaultParams(): Game.Params = Params(4, 4)

    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.width}x${it.height}", encodeParams(it, false))
    }

    override fun decodeParams(text: String): Game.Params {
        var index = 0
        val width = atoi(text, index)
        while (index < text.length && text[index] in '0'..'9') index++
        var height = width
        if (index < text.length && text[index] == 'x') {
            height = atoi(text, index + 1)
        }
        return Params(width, height)
    }

    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.width}x${p.height}"
    }

    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width < 2 || p.height < 2) {
            return "Width and height must both be at least two"
        }
        if (p.width.toLong() * p.height > Int.MAX_VALUE) {
            return "Width times height must not be unreasonably large"
        }
        return null
    }

    /** `game_configure` (`fifteen.c:128-149`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
        )
    }

    /** `custom_params` (`fifteen.c:151-159`): `atoi` of both fields. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi(text(items, "Width"), 0),
            atoi(text(items, "Height"), 0),
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`fifteen.c:513-531`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                name = "Sense of arrow keys",
                choices = ":Move the tile:Move the gap",
                keywords = ":tile:gap",
                selected = 0,
                kw = "arrow-semantics",
            ),
        )

    /** `set_prefs` (`fifteen.c:533-536`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).invertCursor = prefs.choice("arrow-semantics") == 1
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val n = p.width * p.height
        val tiles = IntArray(n)
        val used = BooleanArray(n)

        do {
            for (i in 0 until n) {
                tiles[i] = -1
                used[i] = false
            }

            val gap = random.upto(n.toLong()).toInt()
            tiles[gap] = 0
            used[0] = true

            var x = 0
            for (i in n - 1 downTo 3) {
                var k = random.upto(i.toLong())
                var j = 0
                while (j < n) {
                    if (!used[j]) {
                        if (k == 0L) break
                        k--
                    }
                    j++
                }
                check(j < n && !used[j])
                used[j] = true
                while (tiles[x] >= 0) x++
                check(x < n)
                tiles[x] = j
            }

            while (tiles[x] >= 0) x++
            check(x < n)
            val x1 = x
            x++
            while (tiles[x] >= 0) x++
            check(x < n)
            val x2 = x

            var p1 = 0
            while (p1 < n && used[p1]) p1++
            var p2 = p1 + 1
            while (p2 < n && used[p2]) p2++

            val parity = parity(p, gap)
            tiles[x1] = p1
            tiles[x2] = p2
            if (permParity(tiles, n) != parity) {
                tiles[x1] = p2
                tiles[x2] = p1
                check(permParity(tiles, n) == parity)
            }
        } while (isCompleted(tiles, n))

        return Description(tiles.joinToString(","))
    }

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val area = p.width * p.height
        val used = BooleanArray(area)
        var index = 0

        for (i in 0 until area) {
            val start = index
            if (index >= desc.length || desc[index] !in '0'..'9') {
                return "Not enough numbers in string"
            }
            while (index < desc.length && desc[index] in '0'..'9') index++
            if (i < area - 1 && (index >= desc.length || desc[index] != ',')) {
                return "Expected comma after number"
            } else if (i == area - 1 && index < desc.length) {
                return "Excess junk at end of string"
            }
            val value = atoi(desc, start)
            if (value < 0 || value >= area) {
                return "Number out of range"
            }
            if (used[value]) {
                return "Number used twice"
            }
            used[value] = true
            if (index < desc.length) index++
        }
        return null
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val n = p.width * p.height
        val tiles = IntArray(n)
        var gap = 0
        var index = 0
        for (i in 0 until n) {
            tiles[i] = atoi(desc, index)
            if (tiles[i] == 0) gap = i
            while (index < desc.length && desc[index] != ',') index++
            if (index < desc.length) index++
        }
        return State(p.width, p.height, tiles, gap, 0, 0, false)
    }

    override fun solve(state: Game.State, auxInfo: String?): String = "S"

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        if (move == "S") {
            val solved = IntArray(from.count) { (it + 1) % from.count }
            return State(
                from.width,
                from.height,
                solved,
                from.count - 1,
                completed = 1,
                moveCount = 1,
                usedSolve = true,
            )
        }

        val match = MOVE_PATTERN.find(move) ?: return null
        val dx = match.groupValues[1].toIntOrNull() ?: return null
        val dy = match.groupValues[2].toIntOrNull() ?: return null

        val gx = from.gapPos % from.width
        val gy = from.gapPos / from.width
        if (dx == gx && dy == gy) return null
        if (dx != gx && dy != gy) return null
        if (dx < 0 || dx >= from.width || dy < 0 || dy >= from.height) return null

        val ux = when {
            dx < gx -> -1
            dx > gx -> 1
            else -> 0
        }
        val uy = when {
            dy < gy -> -1
            dy > gy -> 1
            else -> 0
        }
        val up = uy * from.width + ux

        val ret = from.copy() as State
        val newGap = dy * from.width + dx
        ret.tiles[newGap] = 0
        var position = from.gapPos
        while (position != newGap) {
            ret.tiles[position] = from.tiles[position + up]
            position += up
        }

        val moveCount = from.moveCount + abs(dx - gx) + abs(dy - gy)
        val completed = if (from.completed == 0 && isCompleted(ret.tiles, ret.count)) {
            moveCount
        } else {
            from.completed
        }
        return State(
            ret.width,
            ret.height,
            ret.tiles,
            newGap,
            completed,
            moveCount,
            ret.usedSolve,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed != 0) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        if (s.usedSolve) {
            return "Moves since auto-solve: ${s.moveCount - s.completed}"
        }
        val prefix = if (s.completed != 0) "COMPLETED! " else ""
        val moves = if (s.completed != 0) s.completed else s.moveCount
        return "${prefix}Moves: $moves"
    }

    /** `game_changed_state`: a real move completed the puzzle. */
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

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val width = (s.count - 1).toString().length
        val out = StringBuilder()
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val value = s.tiles[y * s.width + x]
                val cell = if (value == 0) "" else value.toString()
                out.append(cell.padStart(width))
                out.append(if (x + 1 == s.width) '\n' else ' ')
            }
        }
        return out.toString()
    }

    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_ONLY)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(tileSize * p.width + 2 * (tileSize / 2), tileSize * p.height + 2 * (tileSize / 2))
    }

    override fun colours(): FloatArray =
        colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`: the shell background with the generated highlight and shadow. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(4 * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) ret[COL_TEXT * 3 + i] = 0.0f
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)
        return ret
    }

    override fun redraw(
        state: Game.State,
        ui: Game.Ui,
        drawState: Game.DrawState,
        sink: DrawSink,
        tileSize: Int,
    ) {
        val s = state as State
        val border = tileSize / 2
        val highlight = tileSize / 20
        fun coord(value: Int): Int = value * tileSize + border

        // The recessed area around the tiles.
        val coords = IntArray(10)
        coords[0] = coord(s.width) + highlight - 1
        coords[1] = coord(s.height) + highlight - 1
        coords[2] = coord(s.width) + highlight - 1
        coords[3] = coord(0) - highlight
        coords[4] = coords[2] - tileSize
        coords[5] = coords[3] + tileSize
        coords[8] = coord(0) - highlight
        coords[9] = coord(s.height) + highlight - 1
        coords[6] = coords[8] + tileSize
        coords[7] = coords[9] - tileSize
        sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)

        coords[1] = coord(0) - highlight
        coords[0] = coord(0) - highlight
        sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)

        for (i in 0 until s.count) {
            drawTile(sink, coord(i % s.width), coord(i / s.width), s.tiles[i], tileSize)
        }
    }

    private fun drawTile(sink: DrawSink, x: Int, y: Int, tile: Int, tileSize: Int) {
        if (tile == 0) {
            sink.rect(x, y, tileSize, tileSize, COL_BACKGROUND)
        } else {
            val highlight = tileSize / 20
            val coords = IntArray(6)
            coords[0] = x + tileSize - 1
            coords[1] = y + tileSize - 1
            coords[2] = x + tileSize - 1
            coords[3] = y
            coords[4] = x
            coords[5] = y + tileSize - 1
            sink.poly(coords, COL_LOWLIGHT, COL_LOWLIGHT)

            coords[0] = x
            coords[1] = y
            sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)

            sink.rect(
                x + highlight,
                y + highlight,
                tileSize - 2 * highlight,
                tileSize - 2 * highlight,
                COL_BACKGROUND,
            )
            sink.text(
                x + tileSize / 2,
                y + tileSize / 2,
                Text.FONT_VARIABLE,
                tileSize / 3,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                COL_TEXT,
                tile.toString(),
            )
        }
        sink.update(x, y, tileSize, tileSize)
    }

    override fun cursorLocation(ui: Game.Ui, state: Game.State, tileSize: Int): Game.CursorRect {
        val s = state as State
        val border = tileSize / 2
        val x = s.gapPos % s.width
        val y = s.gapPos / s.width
        return Game.CursorRect(
            x * tileSize + border,
            y * tileSize + border,
            tileSize,
            tileSize,
        )
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
        var cursorX = s.gapPos % s.width
        var cursorY = s.gapPos / s.width
        var nx = cursorX
        var ny = cursorY
        // The midend keeps CTRL and SHFT on a cursor move, so the game
        // drops the modifiers it does not use.
        var key = Buttons.stripModifiers(button)

        when {
            key == Buttons.LEFT_BUTTON -> {
                nx = fromCoord(x, tileSize)
                ny = fromCoord(y, tileSize)
                if (nx < 0 || nx >= s.width || ny < 0 || ny >= s.height) {
                    return MoveResult.Unused
                }
            }

            Buttons.isCursorMove(key) -> {
                key = flipCursor(key)
                /*
                 * `fifteen.c:793-797`: the default flip makes the arrow
                 * move a tile, and the preference flips it back so the
                 * arrow moves the gap. A delegated test UI has no
                 * preference and keeps the default.
                 */
                if ((ui as? Ui)?.invertCursor == true) key = flipCursor(key)
                val dx = when (key) {
                    Buttons.CURSOR_UP -> 0
                    Buttons.CURSOR_DOWN -> 0
                    Buttons.CURSOR_LEFT -> -1
                    Buttons.CURSOR_RIGHT -> 1
                    else -> 0
                }
                val dy = when (key) {
                    Buttons.CURSOR_UP -> -1
                    Buttons.CURSOR_DOWN -> 1
                    else -> 0
                }
                nx = minOf(maxOf(nx + dx, 0), s.width - 1)
                ny = minOf(maxOf(ny + dy, 0), s.height - 1)
            }

            (key == 'h'.code || key == 'H'.code) && s.completed == 0 -> {
                val hint = computeHint(s) ?: return MoveResult.NoEffect
                nx = hint.first
                ny = hint.second
            }

            else -> return MoveResult.Unused
        }

        return if ((cursorX == nx) xor (cursorY == ny)) {
            MoveResult.Move("M$nx,$ny")
        } else {
            MoveResult.NoEffect
        }
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell. The gap is `empty`, and its position is the
     * game cursor, so no cell needs a cursor suffix: a move slides a
     * whole row or column into the gap and changes several cells at
     * once. A tile in the gap's row or column offers the pointer move
     * the game gives it, `Slide`; every other tile ignores a click.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val t = tileSize
        val cells = gridSpeech(
            width = s.width,
            height = s.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val tile = s.tiles[y * s.width + x]
                if (tile == 0) "empty" else "tile $tile"
            },
            actions = { x, y ->
                val gx = s.gapPos % s.width
                val gy = s.gapPos / s.width
                // `fifteen.c` moves a clicked tile only when the click
                // shares the gap's row or column.
                if ((x == gx) xor (y == gy)) {
                    listOf(SpokenAction("Slide", Buttons.LEFT_BUTTON))
                } else {
                    emptyList()
                }
            },
        )
        return cells.capped(cells.units[s.gapPos])
    }

    /**
     * The next move of the greedy solver, as `(x, y)` of the gap
     * destination. Null when the state is already solved.
     */
    fun computeHint(state: State): Pair<Int, Int>? {
        val w = state.width
        val h = state.height
        val n = w * h
        val gapX = state.gapPos % w
        val gapY = state.gapPos / w

        var nextPiece = 0
        var nextPiece2 = 0
        var solvedRow = 0
        var solvedColumn = 0
        var unsolvedRows = h
        var unsolvedColumns = w

        outer@ while (solvedRow < h && solvedColumn < w) {
            val start = solvedRow * w + solvedColumn
            val step: Int
            val stop: Int
            if (unsolvedColumns <= unsolvedRows) {
                step = 1
                stop = unsolvedColumns
            } else {
                step = w
                stop = unsolvedRows
            }
            var i = 0
            while (i < stop) {
                val j = start + i * step
                if (state.tiles[j] != j + 1) {
                    nextPiece = j + 1
                    nextPiece2 = nextPiece + step
                    break@outer
                }
                i++
            }
            if (unsolvedColumns <= unsolvedRows) {
                solvedRow++
                unsolvedRows--
            } else {
                solvedColumn++
                unsolvedColumns--
            }
        }

        if (nextPiece == n) return null

        val targetX = (nextPiece - 1) % w
        val targetY = (nextPiece - 1) / w

        var index = 0
        while (index < n && state.tiles[index] != nextPiece) index++
        val nextX = index % w
        val nextY = index / w

        index = 0
        while (index < n && state.tiles[index] != nextPiece2) index++
        val otherX = index % w
        val otherY = index / w

        val dx: Int
        val dy: Int
        if (unsolvedColumns <= unsolvedRows) {
            val move = nextMove(nextX, nextY, otherX, otherY, gapX, gapY, targetX, targetY, w)
            dx = move.first
            dy = move.second
        } else {
            val move = nextMove(nextY, nextX, otherY, otherX, gapY, gapX, targetY, targetX, h)
            dx = move.second
            dy = move.first
        }
        check(dx != 0 || dy != 0)
        return (gapX + dx) to (gapY + dy)
    }

    private fun nextMove(
        nx: Int,
        ny: Int,
        ox: Int,
        oy: Int,
        gx: Int,
        gy: Int,
        tx: Int,
        ty: Int,
        w: Int,
    ): Pair<Int, Int> {
        val toTileX = if (gx < nx) 1 else -1
        val toGoalX = if (gx < tx) 1 else -1
        val gapXOnGoalSide = (nx - tx) * (nx - gx) > 0

        check(nx != tx || ny != ty)
        check(nx != gx || ny != gy)
        check(ty <= ny)
        check(ty <= gy)

        var dx = 0
        var dy = 0

        if (tx == w - 2 &&
            ny <= ty + 2 && (nx == tx || nx == tx + 1) &&
            oy <= ty + 2 && (ox == tx || ox == tx + 1) &&
            gy <= ty + 2 && (gx == tx || gx == tx + 1)
        ) {
            val move = nextMove3x2(
                oy - ty, tx + 1 - ox,
                ny - ty, tx + 1 - nx,
                gy - ty, tx + 1 - gx,
            )
            dy = move.first
            dx = move.second
            dx *= -1
            return dx to dy
        }

        if (tx == w - 1) {
            when {
                ny <= ty + 2 && (nx == tx || nx == tx - 1) &&
                    gy <= ty + 2 && (gx == tx || gx == tx - 1) -> {
                    val move = nextMove3x2(ny - ty, tx - nx, 0, 1, gy - ty, tx - gx)
                    dy = move.first
                    dx = move.second
                    dx *= -1
                }

                gy == ty -> dy = 1
                nx != tx || ny != ty + 1 -> {
                    val move = nextMove(
                        (w - 1) - nx, ny, -1, -1, (w - 1) - gx, gy,
                        0, ty + 1, -1,
                    )
                    dx = move.first
                    dy = move.second
                    dx *= -1
                }

                gx == nx -> dy = -1
                else -> dx = 1
            }
            return dx to dy
        }

        if (gy < ny) {
            when {
                nx == gx || (gy == ty && gx == tx) -> dy = 1
                !gapXOnGoalSide -> dx = toTileX
                ny - ty > abs(nx - tx) -> dx = toTileX
                else -> dy = 1
            }
        } else if (gy == ny) {
            when {
                nx == tx ->
                    // ny > ty here, so this is safe.
                    dy = if (gx > nx || ny > ty + 1) -1 else 1

                gapXOnGoalSide -> dx = toTileX
                gy == ty || (gy == ty + 1 && gx < tx) -> dy = 1
                else -> dy = -1
            }
        } else if (nx == tx) {
            if (gx > nx) dy = -1 else dx = 1
        } else if (gx == nx) {
            dx = toGoalX
        } else if (gapXOnGoalSide) {
            if (gy == ty + 1 && gx < tx) dx = toTileX else dy = -1
        } else if (ny - ty > abs(nx - tx)) {
            dy = -1
        } else {
            dx = toTileX
        }
        return dx to dy
    }

    private fun nextMove3x2(
        ax: Int,
        ay: Int,
        bx: Int,
        by: Int,
        gx: Int,
        gy: Int,
    ): Pair<Int, Int> {
        val deltas = arrayOf(
            1 to 0,
            -1 to 0,
            0 to 1,
            0 to -1,
        )
        var ea = 3 * ay + ax
        var eb = 3 * by + bx
        var eg = 3 * gy + gx
        if (eb > ea) --eb
        if (eg > ea) --eg
        if (eg > eb) --eg
        val delta = deltas[MOVE_TABLE[ea + eb * 6 + eg * 30].toInt()]
        return delta
    }

    private fun parity(params: Params, gap: Int): Int {
        val x = gap % params.width - (params.width - 1)
        val y = gap / params.width - (params.height - 1)
        return (x xor y xor (params.width * params.height + 1)) and 1
    }

    private fun permParity(perm: IntArray, n: Int): Int {
        var ret = 0
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                if (perm[i] > perm[j]) ret = ret xor 1
            }
        }
        return ret
    }

    private fun isCompleted(tiles: IntArray, n: Int): Boolean {
        for (p in 0 until n) {
            if (tiles[p] != if (p < n - 1) p + 1 else 0) return false
        }
        return true
    }

    private fun flipCursor(button: Int): Int = when (button) {
        Buttons.CURSOR_UP -> Buttons.CURSOR_DOWN
        Buttons.CURSOR_DOWN -> Buttons.CURSOR_UP
        Buttons.CURSOR_LEFT -> Buttons.CURSOR_RIGHT
        Buttons.CURSOR_RIGHT -> Buttons.CURSOR_LEFT
        else -> 0
    }

    private fun fromCoord(value: Int, tileSize: Int): Int {
        val border = tileSize / 2
        return (value - border + tileSize) / tileSize - 1
    }

    /** The value of the named settings text item. */
    private fun text(items: List<ConfigItem>, name: String): String =
        (items.lastOrNull { it.name == name } as? ConfigItem.Text)?.value
            ?: error("no text setting named '$name'")

    /** C `atoi`: leading whitespace, an optional sign, then decimal digits. */
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
            if (value > Int.MAX_VALUE) return if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE
            i++
        }
        return sign * value.toInt()
    }

    private val MOVE_PATTERN = Regex("^M(-?\\d+),(-?\\d+)")

    private val MOVE_TABLE = intArrayOf(
        1, 2, 0, 1, 2, 2,
        2, 0, 0, 2, 0, 0,
        0, 0, 2, 0, 2, 0,
        0, 0, 0, 2, 0, 2,
        2, 0, 0, 0, 2, 0,

        0, 3, 0, 1, 1, 1,
        3, 0, 3, 2, 1, 2,
        2, 1, 1, 0, 1, 0,
        2, 1, 2, 1, 0, 1,
        1, 2, 0, 2, 1, 2,

        0, 1, 3, 1, 3, 0,
        1, 3, 1, 3, 0, 3,
        0, 0, 3, 3, 0, 0,
        0, 0, 0, 1, 2, 1,
        3, 0, 0, 1, 1, 1,

        3, 1, 1, 1, 3, 0,
        1, 1, 1, 1, 1, 1,
        1, 3, 1, 1, 3, 0,
        1, 1, 3, 3, 1, 3,
        1, 3, 0, 0, 0, 0,
    )
}
