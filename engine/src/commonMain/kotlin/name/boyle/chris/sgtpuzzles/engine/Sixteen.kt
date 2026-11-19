package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * A port of upstream `sixteen.c`: the 16-puzzle.
 *
 * A move rotates one row or one column toroidally. The description is a
 * comma-separated permutation of `1..w*h` in row-major order, and the
 * solved board is `1,2,...,w*h`. The game has no AUXINFO and no UI
 * serialisation.
 *
 * The generator has two branches: a target game shuffles by making
 * single moves, and a plain game places pieces by rank. Both use the
 * upstream random stream in the same order as C.
 *
 * This game uses [Game.NoDrawState], so [redraw] draws the frame,
 * the arrows, and the whole board on every call. C draws them once per
 * drawstate. The slide animation and the completion flash are not
 * ported: the seam has no animation time, and `docs/PARITY.md` allows
 * animation differences. The game state, the text, the moves, and the
 * save file are unaffected.
 */
object Sixteen : Game {

    private const val PREFERRED_TILE_SIZE = 48

    private const val COL_BACKGROUND = 0
    private const val COL_TEXT = 1
    private const val COL_HIGHLIGHT = 2
    private const val COL_LOWLIGHT = 3

    /** `enum cursor_mode`: what a cursor select locks. */
    enum class CursorMode { UNLOCKED, LOCK_TILE, LOCK_POSITION }

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val moveTarget: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(width, height, moveTarget)
    }

    /**
     * `struct game_state`. `moveTarget` is a copy of the parameter and
     * only the status text reads it. `lastMovementSense` is the `dx+dy`
     * of the last move; it is kept for parity with C but the port has no
     * animation that would read it.
     */
    class State(
        val width: Int,
        val height: Int,
        val tiles: IntArray,
        val completed: Int,
        val moveCount: Int,
        val usedSolve: Boolean,
        val moveTarget: Int,
        val lastMovementSense: Int,
    ) : Game.State {

        val count: Int get() = width * height

        override fun copy(): Game.State = State(
            width,
            height,
            tiles.copyOf(),
            completed,
            moveCount,
            usedSolve,
            moveTarget,
            lastMovementSense,
        )

        fun tileAt(x: Int, y: Int): Int = tiles[y * width + x]

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                tiles.contentEquals(other.tiles) &&
                completed == other.completed &&
                moveCount == other.moveCount &&
                usedSolve == other.usedSolve &&
                moveTarget == other.moveTarget &&
                lastMovementSense == other.lastMovementSense

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + tiles.contentHashCode()
            result = 31 * result + completed
            result = 31 * result + moveCount
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + moveTarget
            result = 31 * result + lastMovementSense
            return result
        }
    }

    /** `struct game_ui`. */
    class Ui : Game.Ui {
        var curX = 0
        var curY = 0
        var cursorVisible = false
        var cursorMode = CursorMode.UNLOCKED
    }

    override val name: String = "Sixteen"
    override val backendName: String = "sixteen"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    // `sixteen.c` has no `SMALL_SCREEN` branch, so the Android build and
    // the oracle expose the same five presets.
    private val presetValues = listOf(
        Params(3, 3, 0),
        Params(4, 3, 0),
        Params(4, 4, 0),
        Params(5, 4, 0),
        Params(5, 5, 0),
    )

    override fun defaultParams(): Game.Params = Params(4, 4, 0)

    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.width}x${it.height}", encodeParams(it, false))
    }

    /**
     * `decode_params`. The digit cursor starts at the beginning of the
     * string, so a leading sign or space stops it and the `x` or `m`
     * that follows is not seen.
     */
    override fun decodeParams(text: String): Game.Params {
        var index = 0
        val width = atoi(text, index)
        while (index < text.length && text[index] in '0'..'9') index++
        var height = width
        if (index < text.length && text[index] == 'x') {
            index++
            height = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        var moveTarget = 0
        if (index < text.length && text[index] == 'm') {
            index++
            moveTarget = atoi(text, index)
        }
        return Params(width, height, moveTarget)
    }

    /**
     * `encode_params`: `%dx%d` and `m%d` when the target is not zero.
     * The `full` argument is ignored, as in C. The target is part of the
     * short parameters, because the player has to be told the target.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val base = "${p.width}x${p.height}"
        return if (p.moveTarget != 0) "${base}m${p.moveTarget}" else base
    }

    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width < 2 || p.height < 2) {
            return "Width and height must both be at least two"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.moveTarget < 0) {
            return "Number of shuffling moves may not be negative"
        }
        return null
    }

    /** `game_configure` (`sixteen.c:138-164`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Text("Number of shuffling moves", p.moveTarget.toString()),
        )
    }

    /** `custom_params` (`sixteen.c:166-175`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
        )

    /**
     * `new_game_desc`. The two branches consume the random stream in the
     * C order: one draw per placed piece, or two draws per shuffle
     * attempt including the attempts the retry rule rejects.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val n = p.width * p.height
        val tiles = IntArray(n)
        if (p.moveTarget != 0) {
            shuffleByMoves(p, n, tiles, random)
        } else {
            shuffleByRank(p, n, tiles, random)
        }
        return Description(tiles.joinToString(",") { (it + 1).toString() })
    }

    /**
     * The `movetarget` branch: start from the solved board and apply
     * exactly `moveTarget` single moves. A rejected attempt still costs
     * two draws, because the direction is drawn before the retry test.
     */
    private fun shuffleByMoves(p: Params, n: Int, tiles: IntArray, random: RandomState) {
        for (i in 0 until n) tiles[i] = i

        var prevOffset = -1
        val max = if (p.width > p.height) p.width else p.height
        val prevMoves = IntArray(max)

        for (move in 0 until p.moveTarget) {
            var start: Int
            var offset: Int
            var len: Int
            var direction: Int
            while (true) {
                val j = random.upto((p.width + p.height).toLong()).toInt()
                val index: Int
                if (j < p.width) {
                    // Column.
                    index = j
                    start = j
                    offset = p.width
                    len = p.height
                } else {
                    // Row.
                    index = j - p.width
                    start = index * p.width
                    offset = 1
                    len = p.width
                }

                direction = -1 + 2 * random.upto(2).toInt()

                // Do not undo the previous move, and do not repeat it
                // until it turns into fewer moves the other way. The
                // counters are per line and reset on a perpendicular
                // move.
                if (offset == prevOffset) {
                    val tmp = prevMoves[index] + direction
                    if (abs(2 * tmp) > len || abs(tmp) < abs(prevMoves[index])) continue
                }

                if (offset != prevOffset) {
                    for (i in 0 until max) prevMoves[i] = 0
                    prevOffset = offset
                }
                prevMoves[index] += direction
                break
            }

            if (direction < 0) {
                start += (len - 1) * offset
                offset = -offset
            }
            val first = tiles[start]
            var j = 0
            while (j + 1 < len) {
                tiles[start + j * offset] = tiles[start + (j + 1) * offset]
                j++
            }
            tiles[start + (len - 1) * offset] = first
        }
    }

    /**
     * The plain branch: place the pieces by rank. Each placed piece is
     * the `k`-th unused value in ascending order, and the cells are
     * filled in ascending index order, so only the number of draws and
     * the parity fix depend on the board shape.
     */
    private fun shuffleByRank(p: Params, n: Int, tiles: IntArray, random: RandomState) {
        val used = BooleanArray(n)
        for (i in 0 until n) {
            tiles[i] = -1
            used[i] = false
        }

        // Both dimensions odd: the last two pieces are fixed up for
        // parity. There is no reachability check in the game.
        val stop = if ((p.width and p.height and 1) != 0) 2 else 0

        var x = 0
        var i = n
        while (i > stop) {
            var k = if (i > 1) random.upto(i.toLong()).toInt() else 0
            var j = 0
            while (j < n) {
                if (!used[j]) {
                    if (k == 0) break
                    k--
                }
                j++
            }
            check(j < n && !used[j]) { "no unused piece to place" }
            used[j] = true

            while (tiles[x] >= 0) x++
            check(x < n) { "no empty cell to fill" }
            tiles[x] = j
            i--
        }

        if (stop != 0) {
            while (tiles[x] >= 0) x++
            check(x < n) { "no first free cell" }
            val x1 = x
            x++
            while (tiles[x] >= 0) x++
            check(x < n) { "no second free cell" }
            val x2 = x

            var p1 = 0
            while (used[p1]) p1++
            var p2 = p1 + 1
            while (used[p2]) p2++

            tiles[x1] = p1
            tiles[x2] = p2
            if (permParity(tiles, n) != 0) {
                tiles[x1] = p2
                tiles[x2] = p1
                check(permParity(tiles, n) == 0) { "the parity fix failed" }
            }
        }
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
            if (i < area - 1) {
                if (index >= desc.length || desc[index] != ',') {
                    return "Expected comma after number"
                }
            } else if (index < desc.length) {
                return "Excess junk at end of string"
            }
            val value = atoi(desc, start)
            if (value < 1 || value > area) {
                return "Number out of range"
            }
            if (used[value - 1]) {
                return "Number used twice"
            }
            used[value - 1] = true
            if (index < desc.length) index++
        }
        return null
    }

    /**
     * `new_game`: parse the one-based tile values into the state. C does
     * not subtract one here; the values are the piece numbers.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val n = p.width * p.height
        val tiles = IntArray(n)
        var index = 0
        for (i in 0 until n) {
            check(index < desc.length) { "description is shorter than the board" }
            tiles[i] = atoi(desc, index)
            while (index < desc.length && desc[index] != ',') index++
            if (index < desc.length) index++
        }
        check(index >= desc.length) { "description has trailing data" }
        return State(
            width = p.width,
            height = p.height,
            tiles = tiles,
            completed = 0,
            moveCount = 0,
            usedSolve = false,
            moveTarget = p.moveTarget,
            lastMovementSense = 0,
        )
    }

    /** `solve_game`: always the one-move `S`, which installs a clean board. */
    override fun solve(state: Game.State, auxInfo: String?): String = "S"

    /**
     * `execute_move`. The bounds are C's loose bounds: `R<row>,<dx>`
     * accepts `-h <= dx <= w`, and `C<col>,<dy>` accepts `-h <= dy <= h`.
     * A delta of `w` or `h` is a full cycle: the board is unchanged, but
     * the move count still rises by one.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State

        if (move == "S") {
            return State(
                width = from.width,
                height = from.height,
                tiles = IntArray(from.count) { it + 1 },
                completed = 1,
                moveCount = 1,
                usedSolve = true,
                moveTarget = from.moveTarget,
                lastMovementSense = from.lastMovementSense,
            )
        }

        var cx = 0
        var cy = 0
        var dx = 0
        var dy = 0
        var lineLength: Int
        when {
            move.isNotEmpty() && move[0] == 'R' -> {
                val pair = scanTwoInts(move, 1) ?: return null
                cy = pair.first
                dx = pair.second
                if (cy < 0 || cy >= from.height ||
                    dx < -from.height || dx > from.width
                ) {
                    return null
                }
                lineLength = from.width
            }

            move.isNotEmpty() && move[0] == 'C' -> {
                val pair = scanTwoInts(move, 1) ?: return null
                cx = pair.first
                dy = pair.second
                if (cx < 0 || cx >= from.width ||
                    dy < -from.height || dy > from.height
                ) {
                    return null
                }
                lineLength = from.height
            }

            else -> return null
        }

        val tiles = from.tiles.copyOf()
        var tx = cx
        var ty = cy
        var remaining = lineLength
        do {
            val sx = (tx - dx + from.width) % from.width
            val sy = (ty - dy + from.height) % from.height
            tiles[ty * from.width + tx] = from.tiles[sy * from.width + sx]
            tx = sx
            ty = sy
            remaining--
        } while (remaining > 0)

        var completed = from.completed
        val moveCount = from.moveCount + 1
        if (completed == 0) {
            completed = moveCount
            for (i in 0 until from.count) {
                if (tiles[i] != i + 1) {
                    completed = 0
                    break
                }
            }
        }

        return State(
            width = from.width,
            height = from.height,
            tiles = tiles,
            completed = completed,
            moveCount = moveCount,
            usedSolve = from.usedSolve,
            moveTarget = from.moveTarget,
            lastMovementSense = dx + dy,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed != 0) 1 else 0

    /**
     * `game_status` in words. The vendored patch layer removed the space
     * after `COMPLETED!`, so the port must not add one. The target suffix
     * is only present for a target game, and a solve has its own line.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        if (s.usedSolve) {
            return "Moves since auto-solve: ${s.moveCount - s.completed}"
        }
        val prefix = if (s.completed != 0) "COMPLETED!" else ""
        val moves = if (s.completed != 0) s.completed else s.moveCount
        val text = "${prefix}Moves: $moves"
        return if (s.moveTarget != 0) "$text (target ${s.moveTarget})" else text
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val column = s.count.toString().length
        val out = StringBuilder()
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                out.append(s.tiles[y * s.width + x].toString().padStart(column))
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

    /** `game_request_keys`: no labels, and the midend's default arrow mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(tileSize * (p.width + 2), tileSize * (p.height + 2))
    }

    /** C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorVisible = visible
    }

    /**
     * `game_get_cursor_location`. The coordinates have no board clamp, so
     * a cursor on the frame reports a rectangle on the border or outside
     * the board, as C does.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorVisible) return null
        return Game.CursorRect(
            u.curX * tileSize + tileSize,
            u.curY * tileSize + tileSize,
            tileSize,
            tileSize,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(4 * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) ret[COL_TEXT * 3 + i] = 0.0f
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)
        return ret
    }

    /**
     * `game_redraw`. C draws the frame and the static arrows once per
     * drawstate and skips unchanged tiles. This game uses
     * [Game.NoDrawState], so every redraw is a first redraw and emits the
     * whole board.
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
        val highlight = tileSize / 20

        fun coord(value: Int): Int = value * tileSize + tileSize

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

        for (i in 0 until s.width) {
            drawArrow(sink, coord(i), coord(0), +1, 0, false, tileSize)
            drawArrow(sink, coord(i + 1), coord(s.height), -1, 0, false, tileSize)
        }
        for (i in 0 until s.height) {
            drawArrow(sink, coord(s.width), coord(i), 0, +1, false, tileSize)
            drawArrow(sink, coord(0), coord(i + 1), 0, -1, false, tileSize)
        }

        val curX = if (u.cursorVisible) u.curX else -1
        val curY = if (u.cursorVisible) u.curY else -1
        // A fresh drawstate has no previous cursor, so only the current
        // arrow is drawn.
        drawArrowForCursor(sink, s.width, s.height, curX, curY, true, tileSize)

        sink.clip(coord(0), coord(0), tileSize * s.width, tileSize * s.height)
        for (i in 0 until s.count) {
            val x = coord(i % s.width)
            val y = coord(i / s.width)
            val flash = if (isTileCursor(i, s, curX, curY)) COL_LOWLIGHT else COL_BACKGROUND
            drawTile(sink, x, y, s.tiles[i], flash, tileSize)
        }
        sink.unclip()
    }

    /** `draw_tile`. A validated description never contains a zero tile. */
    private fun drawTile(
        sink: DrawSink,
        x: Int,
        y: Int,
        tile: Int,
        flashColour: Int,
        tileSize: Int,
    ) {
        if (tile == 0) {
            sink.rect(x, y, tileSize, tileSize, flashColour)
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
                flashColour,
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

    /** `draw_arrow`: one seven-point arrow, rotated by `(xdx, xdy)`. */
    private fun drawArrow(
        sink: DrawSink,
        x: Int,
        y: Int,
        xdx: Int,
        xdy: Int,
        cur: Boolean,
        tileSize: Int,
    ) {
        val ydy = -xdx
        val ydx = xdy
        val coords = IntArray(14)

        fun point(n: Int, xx: Int, yy: Int) {
            coords[2 * n] = x + xx * xdx + yy * ydx
            coords[2 * n + 1] = y + xx * xdy + yy * ydy
        }

        point(0, tileSize / 2, 3 * tileSize / 4)
        point(1, 3 * tileSize / 4, tileSize / 2)
        point(2, 5 * tileSize / 8, tileSize / 2)
        point(3, 5 * tileSize / 8, tileSize / 4)
        point(4, 3 * tileSize / 8, tileSize / 4)
        point(5, 3 * tileSize / 8, tileSize / 2)
        point(6, tileSize / 4, tileSize / 2)

        sink.poly(
            coords,
            if (cur) COL_HIGHLIGHT else COL_LOWLIGHT,
            COL_TEXT,
        )
    }

    /**
     * `draw_arrow_for_cursor`. The update rectangle is the cursor cell,
     * which does not always cover the arrow: on the bottom row and the
     * side columns C still uses the cell rectangle, so the port does too.
     */
    private fun drawArrowForCursor(
        sink: DrawSink,
        width: Int,
        height: Int,
        curX: Int,
        curY: Int,
        cur: Boolean,
        tileSize: Int,
    ) {
        fun coord(value: Int): Int = value * tileSize + tileSize

        if (curX == -1 && curY == -1) return
        if (curX == -1) {
            drawArrow(sink, coord(0), coord(curY + 1), 0, -1, cur, tileSize)
        } else if (curX == width) {
            drawArrow(sink, coord(width), coord(curY), 0, +1, cur, tileSize)
        } else if (curY == -1) {
            drawArrow(sink, coord(curX), coord(0), +1, 0, cur, tileSize)
        } else if (curY == height) {
            drawArrow(sink, coord(curX + 1), coord(height), -1, 0, cur, tileSize)
        } else {
            return
        }
        sink.update(coord(curX), coord(curY), tileSize, tileSize)
    }

    private fun isTileCursor(i: Int, state: State, x: Int, y: Int): Boolean =
        i == y * state.width + x &&
            0 <= x && x < state.width &&
            0 <= y && y < state.height

    /**
     * `current_key_label`. The seam has no hook for it, so this is a
     * public helper, as in flood and samegame.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        // C tests the raw button: `IS_CURSOR_SELECT` is false when a
        // modifier is set, so a modified select has no label.
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.cursorVisible
        ) {
            if (u.curX == -1 || u.curX == s.width || u.curY == -1 || u.curY == s.height) {
                return if (button == Buttons.CURSOR_SELECT2) "Back" else "Slide"
            }
            if (button == Buttons.CURSOR_SELECT) {
                return if (u.cursorMode == CursorMode.LOCK_TILE) "Unlock" else "Lock tile"
            }
            if (button == Buttons.CURSOR_SELECT2) {
                return if (u.cursorMode == CursorMode.LOCK_POSITION) "Unlock" else "Lock pos"
            }
        }
        return ""
    }

    /**
     * `interpret_move`. The order of the tests matters: the cursor keys
     * and the keypad come first, then the pointer, then the cursor
     * select. A click that lands on a cell or on a frame corner is a UI
     * update, never a no-effect key, because C returns `MOVE_UI_UPDATE`.
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
        val key = Buttons.stripModifiers(button)

        /*
         * The seam passes a canonical button, but keeps CTRL and SHFT on
         * a cursor move. The game reads both modifiers before it strips
         * them. Without these two checks a modified click would take the
         * cursor branch here, which C never allows.
         */
        val cursorKey = Buttons.isCursorMove(key)
        val rawControl = (button and Buttons.MOD_CTRL) != 0
        if (!cursorKey && rawControl && key >= 0x20) return MoveResult.Unused
        val shift = cursorKey && (button and Buttons.MOD_SHFT) != 0
        val control = cursorKey && rawControl
        val pad = if (key in '0'.code..'9'.code) button and Buttons.MOD_NUM_KEYPAD else 0

        if (Buttons.isCursorMove(key) || pad != 0) {
            if (!u.cursorVisible) {
                u.cursorVisible = true
                return MoveResult.UiUpdate
            }

            if (control || shift || u.cursorMode != CursorMode.UNLOCKED) {
                if (u.curX < 0 || u.curX >= s.width || u.curY < 0 || u.curY >= s.height) {
                    return MoveResult.Unused
                }
                val clamped = moveCursor(key or pad, u.curX, u.curY, s.width, s.height, false)
                val wrapped = moveCursor(key or pad, u.curX, u.curY, s.width, s.height, true)
                val text = when {
                    clamped.first != wrapped.first ->
                        "R${clamped.second},${if (clamped.first != 0) '+' else '-'}1"

                    clamped.second != wrapped.second ->
                        "C${clamped.first},${if (clamped.second != 0) '+' else '-'}1"

                    clamped.first == u.curX -> "C${clamped.first},${clamped.second - u.curY}"

                    else -> "R${clamped.second},${clamped.first - u.curX}"
                }
                if (control || (!shift && u.cursorMode == CursorMode.LOCK_TILE)) {
                    u.curX = wrapped.first
                    u.curY = wrapped.second
                }
                return MoveResult.Move(text)
            }

            // The unlocked cursor walks the ring of (w+2) x (h+2)
            // positions. The four corners of that ring map back onto the
            // frame with their own transforms.
            val moved = moveCursor(
                key or pad,
                u.curX + 1,
                u.curY + 1,
                s.width + 2,
                s.height + 2,
                false,
            )
            val rx = moved.first
            val ry = moved.second
            when {
                rx == 0 && ry == 0 -> {
                    val t = u.curX
                    u.curX = u.curY
                    u.curY = t
                }

                rx == 0 && ry == s.height + 1 -> {
                    val t = u.curX
                    u.curX = (s.height - 1) - u.curY
                    u.curY = (s.height - 1) - t
                }

                rx == s.width + 1 && ry == 0 -> {
                    val t = u.curX
                    u.curX = (s.width - 1) - u.curY
                    u.curY = (s.width - 1) - t
                }

                rx == s.width + 1 && ry == s.height + 1 -> {
                    val t = u.curX
                    u.curX = s.width - s.height + u.curY
                    u.curY = s.height - s.width + t
                }

                else -> {
                    u.curX = rx - 1
                    u.curY = ry - 1
                }
            }
            u.cursorVisible = true
            return MoveResult.UiUpdate
        }

        var cx: Int
        var cy: Int
        when (key) {
            Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON -> {
                cx = fromCoord(x, tileSize)
                cy = fromCoord(y, tileSize)
                u.cursorVisible = false
            }

            Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2 -> {
                if (!u.cursorVisible) {
                    u.cursorVisible = true
                    return MoveResult.UiUpdate
                }
                if (u.curX == -1 || u.curX == s.width ||
                    u.curY == -1 || u.curY == s.height
                ) {
                    cx = u.curX
                    cy = u.curY
                } else {
                    val mode = if (key == Buttons.CURSOR_SELECT2) {
                        CursorMode.LOCK_POSITION
                    } else {
                        CursorMode.LOCK_TILE
                    }
                    u.cursorMode = if (u.cursorMode == mode) {
                        CursorMode.UNLOCKED
                    } else {
                        mode
                    }
                    return MoveResult.UiUpdate
                }
            }

            else -> return MoveResult.Unused
        }

        val dx: Int
        val dy: Int
        when {
            cx == -1 && cy >= 0 && cy < s.height -> {
                dx = -1
                dy = 0
            }

            cx == s.width && cy >= 0 && cy < s.height -> {
                dx = +1
                dy = 0
            }

            cy == -1 && cx >= 0 && cx < s.width -> {
                dx = 0
                dy = -1
            }

            cy == s.height && cx >= 0 && cx < s.width -> {
                dx = 0
                dy = +1
            }

            else -> return MoveResult.UiUpdate
        }

        val reverse = key == Buttons.RIGHT_BUTTON || key == Buttons.CURSOR_SELECT2
        return if (dx != 0) {
            MoveResult.Move("R$cy,${if (reverse) -dx else dx}")
        } else {
            MoveResult.Move("C$cx,${if (reverse) -dy else dy}")
        }
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell, with the tile number and the cursor suffix. A
     * cell click is a UI update in `interpret_move`, never a move: the
     * pointer path turns the frame arrows around the grid, so a cell
     * offers no action and a move stays on the key path (arrows with
     * the cursor select). `board-a11y-spec.md` section 4 asks for a
     * cell, and the frame ring is a later own-model unit list.
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
            // `game_compute_size` puts one tile of frame on each side.
            left = t,
            top = t,
            summary = statusText(state, ui),
            text = { x, y ->
                val cursor = if (u.cursorVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                "tile ${s.tiles[y * s.width + x]}$cursor"
            },
        )
        val cursor = if (u.curX in 0 until s.width && u.curY in 0 until s.height) {
            cells.units[u.curY * s.width + u.curX]
        } else {
            null
        }
        return cells.capped(cursor)
    }

    /** `move_cursor`: a cursor key moves clamped or wrapped; anything else stays. */
    private fun moveCursor(
        button: Int,
        x: Int,
        y: Int,
        maxWidth: Int,
        maxHeight: Int,
        wrap: Boolean,
    ): Pair<Int, Int> {
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
        return if (wrap) {
            ((x + dx + maxWidth) % maxWidth) to ((y + dy + maxHeight) % maxHeight)
        } else {
            (x + dx).coerceIn(0, maxWidth - 1) to (y + dy).coerceIn(0, maxHeight - 1)
        }
    }

    /** `FROMCOORD`: C integer division truncates toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value + tileSize) / tileSize - 2

    private fun permParity(perm: IntArray, n: Int): Int {
        var ret = 0
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                if (perm[i] > perm[j]) ret = ret xor 1
            }
        }
        return ret
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
     * `sscanf(text + start, "%d,%d")`: white space before a number is
     * skipped, but the comma must follow the first number exactly. The
     * second number may carry trailing junk, as `sscanf` ignores it.
     */
    private fun scanTwoInts(text: String, start: Int): Pair<Int, Int>? {
        val first = scanInt(text, start) ?: return null
        var index = first.second
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = scanInt(text, index) ?: return null
        return first.first to second.first
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
