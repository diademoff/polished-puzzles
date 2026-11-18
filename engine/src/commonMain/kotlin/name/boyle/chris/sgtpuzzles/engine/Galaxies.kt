package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The `galaxies` game (Tentai Show, also called Spiral Galaxies).
 *
 * The port follows `app/src/main/jni/galaxies.c`. The board is a
 * `(2w+1) x (2h+1)` array of spaces: tiles at odd/odd coordinates,
 * edges where exactly one coordinate is odd, and vertices at even/even
 * coordinates. Edges divide the tiles into regions; a region is correct
 * when it is rotationally symmetric around exactly one dot.
 *
 * The generator lays out dots and associations, measures the wiggliness
 * of ten attempts, and keeps the best one that the solver grades at the
 * requested difficulty. The solver, the `SOLVE` move, and the stored
 * `AUXINFO` answer share the same routines.
 */
object Galaxies : Game {

    // Palette, `galaxies.c:119-131`.
    private const val COL_BACKGROUND = 0
    private const val COL_WHITEBG = 1
    private const val COL_BLACKBG = 2
    private const val COL_WHITEDOT = 3
    private const val COL_BLACKDOT = 4
    private const val COL_GRID = 5
    private const val COL_EDGE = 6
    private const val COL_ARROW = 7
    private const val COL_CURSOR = 8
    private const val NCOLOURS = 9

    /** `DIFFLIST`, `galaxies.c:134-136`. */
    private const val DIFF_NORMAL = 0
    private const val DIFF_UNREASONABLE = 1

    // Solver grades, `galaxies.c:143-144`.
    private const val DIFF_IMPOSSIBLE = 2
    private const val DIFF_AMBIGUOUS = 3
    private const val DIFF_UNFINISHED = 4

    private val diffChars = charArrayOf('n', 'u')
    private val diffNames = arrayOf("Normal", "Unreasonable")

    // Space flags, `galaxies.c:157-168`.
    private const val F_DOT = 1
    private const val F_EDGE_SET = 2
    private const val F_TILE_ASSOC = 4
    private const val F_DOT_BLACK = 8
    private const val F_MARK = 16
    private const val F_REACHABLE = 32
    private const val F_MULTIPLE = 128
    private const val F_DOT_HOLD = 256

    // Space types, `galaxies.c:154`.
    private const val S_TILE = 0
    private const val S_EDGE = 1
    private const val S_VERTEX = 2

    /** `PREFERRED_TILE_SIZE`, `galaxies.c:2718`. */
    private const val PREFERRED_TILE_SIZE = 32

    /** `FLASH_TIME`; the seam has no flash clock. */
    private const val FLASH_TIME = 0.15f

    private const val MAX_TOADD = 4
    private const val MAX_OUTSIDE = 8
    private const val GENERATE_TRIES = 10
    private const val MAXRECURSE = 5

    private const val GP_DOTS = 1
    private const val IMPOSSIBLE_QUITS = 1


    // Draw flags, `galaxies.c:3540-3555`.
    private const val DRAW_EDGE_L = 0x0001
    private const val DRAW_EDGE_R = 0x0002
    private const val DRAW_EDGE_U = 0x0004
    private const val DRAW_EDGE_D = 0x0008
    private const val DRAW_CORNER_UL = 0x0010
    private const val DRAW_CORNER_UR = 0x0020
    private const val DRAW_CORNER_DL = 0x0040
    private const val DRAW_CORNER_DR = 0x0080
    private const val DRAW_WHITE = 0x0100
    private const val DRAW_BLACK = 0x0200
    private const val DRAW_ARROW = 0x0400
    private const val DRAW_CURSOR = 0x0800
    private const val DOT_SHIFT_C = 12
    private const val DOT_SHIFT_M = 2
    private const val DOT_WHITE = 1L
    private const val DOT_BLACK = 2L

    /** `struct game_params`, `galaxies.c:148-152`. */
    data class Params(
        val w: Int,
        val h: Int,
        val diff: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, diff)
    }

    /**
     * The space array, `galaxies.c:167-181`. The arrays are indexed
     * `y * sx + x`, which is the C `SPACE` macro.
     */
    class Board internal constructor(val sx: Int, val sy: Int) {
        val type = IntArray(sx * sy)
        val flags = IntArray(sx * sy)
        val dotX = IntArray(sx * sy)
        val dotY = IntArray(sx * sy)
        val nassoc = IntArray(sx * sy)

        fun index(x: Int, y: Int): Int = y * sx + x

        fun copy(): Board {
            val ret = Board(sx, sy)
            type.copyInto(ret.type)
            flags.copyInto(ret.flags)
            dotX.copyInto(ret.dotX)
            dotY.copyInto(ret.dotY)
            nassoc.copyInto(ret.nassoc)
            return ret
        }

        fun copyFrom(other: Board) {
            require(other.sx == sx && other.sy == sy) { "board size mismatch" }
            other.type.copyInto(type)
            other.flags.copyInto(flags)
            other.dotX.copyInto(dotX)
            other.dotY.copyInto(dotY)
            other.nassoc.copyInto(nassoc)
        }

        override fun equals(other: Any?): Boolean =
            other is Board &&
                sx == other.sx && sy == other.sy &&
                type.contentEquals(other.type) &&
                flags.contentEquals(other.flags) &&
                dotX.contentEquals(other.dotX) &&
                dotY.contentEquals(other.dotY) &&
                nassoc.contentEquals(other.nassoc)

        override fun hashCode(): Int {
            var result = sx
            result = 31 * result + sy
            result = 31 * result + type.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + dotX.contentHashCode()
            result = 31 * result + dotY.contentHashCode()
            result = 31 * result + nassoc.contentHashCode()
            return result
        }
    }

    /** `struct game_state`, `galaxies.c:183-190`. */
    class State internal constructor(
        val w: Int,
        val h: Int,
        internal val board: Board,
        var completed: Boolean,
        var usedSolve: Boolean,
        internal var dots: IntArray,
    ) : Game.State {
        val sx: Int get() = board.sx
        val sy: Int get() = board.sy

        val dotCount: Int get() = dots.size

        fun typeAt(x: Int, y: Int): Int = board.type[board.index(x, y)]

        fun flagsAt(x: Int, y: Int): Int = board.flags[board.index(x, y)]

        fun isAssociated(x: Int, y: Int): Boolean =
            (flagsAt(x, y) and F_TILE_ASSOC) != 0

        fun hasDot(x: Int, y: Int): Boolean = (flagsAt(x, y) and F_DOT) != 0

        fun isEdgeSet(x: Int, y: Int): Boolean =
            (flagsAt(x, y) and F_EDGE_SET) != 0

        /** The dot coordinates of an associated tile, or null. */
        fun associatedDot(x: Int, y: Int): Pair<Int, Int>? {
            val index = board.index(x, y)
            if (board.flags[index] and F_TILE_ASSOC == 0) return null
            return board.dotX[index] to board.dotY[index]
        }

        fun dotPositions(): List<Pair<Int, Int>> = dots.map { it % sx to it / sx }

        override fun copy(): Game.State = State(
            w,
            h,
            board.copy(),
            completed,
            usedSolve,
            dots.copyOf(),
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                w == other.w && h == other.h &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                board == other.board &&
                dots.contentEquals(other.dots)

        override fun hashCode(): Int {
            var result = w
            result = 31 * result + h
            result = 31 * result + completed.hashCode()
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + board.hashCode()
            result = 31 * result + dots.contentHashCode()
            return result
        }
    }

    /** `struct game_ui`, `galaxies.c:2682-2716`. */
    class Ui internal constructor() : Game.Ui {
        var dragging: Boolean = false
        var dragX: Int = 0
        var dragY: Int = 0
        var dotX: Int = 0
        var dotY: Int = 0
        var srcX: Int = 0
        var srcY: Int = 0
        var curX: Int = 1
        var curY: Int = 1
        var curVisible: Boolean = false
    }

    /** `struct game_drawstate`, `galaxies.c:2733-2757`. */
    class DrawState internal constructor(val w: Int, val h: Int) : Game.DrawState {
        var started: Boolean = false
        var tileSize: Int = 0
        val grid: LongArray = LongArray(w * h) { 0xFFFFFFFFL }
        val dx: IntArray = IntArray(w * h)
        val dy: IntArray = IntArray(w * h)
        var blitter: Int = 0
        var mirrorBlitter: Int = 0
        var cursorBlitter: Int = 0
        var dragging: Boolean = false
        var dragX: Int = 0
        var dragY: Int = 0
        var oppX: Int = 0
        var oppY: Int = 0
        val colourScratch: IntArray = IntArray(w * h)
        var cx: Int = 0
        var cy: Int = 0
        var curVisible: Boolean = false
    }

    // ------------------------------------------------------------------
    // Identity and parameters, `galaxies.c:220-345`.
    // ------------------------------------------------------------------

    override val name: String = "Galaxies"
    override val backendName: String = "galaxies"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val isTimed: Boolean = false
    override val flags: Int = Game.REQUIRE_RBUTTON

    private val presetTable = listOf(
        Params(7, 7, DIFF_NORMAL),
        Params(7, 7, DIFF_UNREASONABLE),
        Params(10, 10, DIFF_NORMAL),
        Params(10, 10, DIFF_UNREASONABLE),
        Params(15, 15, DIFF_NORMAL),
        Params(15, 15, DIFF_UNREASONABLE),
    )

    override fun defaultParams(): Game.Params = Params(7, 7, DIFF_NORMAL)

    override fun presets(): List<Preset> = presetTable.map {
        Preset("${it.w}x${it.h} ${diffNames[it.diff]}", encodeParams(it, true))
    }

    /**
     * `decode_params`, `galaxies.c:261-280`. The difficulty letter is
     * matched against `galaxies_diffchars`, and one character after a
     * `d` is always consumed.
     */
    override fun decodeParams(text: String): Game.Params {
        var position = 0
        var w = atoi(text, 0)
        var h = w
        var diff = DIFF_NORMAL
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            h = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && text[position] == 'd') {
            position++
            if (position < text.length) {
                for (i in 0..DIFF_UNREASONABLE) {
                    if (text[position] == diffChars[i]) diff = i
                }
                position++
            }
        }
        return Params(w, h, diff)
    }

    /** `encode_params`, `galaxies.c:281-288`. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder("${p.w}x${p.h}")
        if (full) {
            out.append('d')
            if (p.diff in 0..DIFF_UNREASONABLE) out.append(diffChars[p.diff])
        }
        return out.toString()
    }

    /** `validate_params`, `galaxies.c:329-345`, in C order. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 3 || p.h < 3) {
            return "Width and height must both be at least 3"
        }
        if (p.w > Int.MAX_VALUE / 2 || p.h > Int.MAX_VALUE / 2 ||
            p.w > (Int.MAX_VALUE - p.w * 2 - p.h * 2 - 1) / 4 / p.h
        ) {
            return "Width times height must not be unreasonably large"
        }
        /*
         * C asserts `diff <= DIFF_UNREASONABLE`. Only a hand-made
         * `Params` can leave the two decoded difficulties, so the port
         * returns a total message instead of aborting (D-023).
         */
        if (p.diff !in 0..DIFF_UNREASONABLE) return "Unknown difficulty level"
        return null
    }

    // ------------------------------------------------------------------
    // Board helpers, `galaxies.c:351-760`.
    // ------------------------------------------------------------------

    private fun inGrid(state: State, x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < state.sx && y < state.sy

    private fun inUi(state: State, x: Int, y: Int): Boolean =
        x > 0 && y > 0 && x < state.sx - 1 && y < state.sy - 1

    private fun isVerticalEdge(x: Int): Boolean = x % 2 == 0

    private fun addDot(state: State, index: Int) {
        val b = state.board
        check(b.flags[index] and F_DOT == 0) { "space already has a dot" }
        b.flags[index] = b.flags[index] or F_DOT
        b.nassoc[index] = 0
    }

    private fun removeDot(state: State, index: Int) {
        val b = state.board
        check(b.flags[index] and F_DOT != 0) { "space has no dot" }
        b.flags[index] = b.flags[index] and F_DOT.inv()
    }

    private fun removeAssoc(state: State, tile: Int) {
        val b = state.board
        if (b.flags[tile] and F_TILE_ASSOC != 0) {
            val dot = b.index(b.dotX[tile], b.dotY[tile])
            b.nassoc[dot]--
            b.flags[tile] = b.flags[tile] and F_TILE_ASSOC.inv()
            b.dotX[tile] = -1
            b.dotY[tile] = -1
        }
    }

    private fun removeAssocWithOpposite(state: State, tile: Int) {
        val b = state.board
        if (b.flags[tile] and F_TILE_ASSOC == 0) return
        val opposite = tileOpposite(state, tile)
        removeAssoc(state, tile)
        if (opposite >= 0 && opposite != tile) removeAssoc(state, opposite)
    }

    private fun addAssoc(state: State, tile: Int, dot: Int) {
        val b = state.board
        removeAssoc(state, tile)
        b.flags[tile] = b.flags[tile] or F_TILE_ASSOC
        b.dotX[tile] = dot % b.sx
        b.dotY[tile] = dot / b.sx
        b.nassoc[dot]++
    }

    private fun okToAddAssocWithOppositeInternal(
        state: State,
        tile: Int,
        opposite: Int,
    ): Boolean {
        val b = state.board
        if (b.type[tile] != S_TILE) return false
        if (b.flags[tile] and F_DOT != 0) return false
        if (opposite < 0) return false
        if (b.flags[opposite] and F_DOT != 0) return false

        val colours = IntArray(state.w * state.h)
        checkComplete(state, null, colours)
        // C reads the space coordinates, not the associated dot
        // (`galaxies.c:421-424`). The index encodes them.
        val tileX = (tile % b.sx - 1) / 2
        val tileY = (tile / b.sx - 1) / 2
        val oppositeX = (opposite % b.sx - 1) / 2
        val oppositeY = (opposite / b.sx - 1) / 2
        if (colours[tileY * state.w + tileX] != 0) return false
        if (colours[oppositeY * state.w + oppositeX] != 0) return false
        return true
    }

    private fun okToAddAssocWithOpposite(state: State, tile: Int, dot: Int): Boolean {
        val opposite = spaceOppositeDot(state, tile, dot)
        return okToAddAssocWithOppositeInternal(state, tile, opposite)
    }

    private fun addAssocWithOpposite(state: State, tile: Int, dot: Int) {
        val opposite = spaceOppositeDot(state, tile, dot)
        if (opposite >= 0 &&
            okToAddAssocWithOppositeInternal(state, tile, opposite)
        ) {
            removeAssocWithOpposite(state, tile)
            addAssoc(state, tile, dot)
            removeAssocWithOpposite(state, opposite)
            addAssoc(state, opposite, dot)
        }
    }

    private fun sp2dot(state: State, x: Int, y: Int): Int {
        val b = state.board
        val index = b.index(x, y)
        if (b.flags[index] and F_TILE_ASSOC == 0) return -1
        return b.index(b.dotX[index], b.dotY[index])
    }

    /** `space_opposite_dot`, `galaxies.c:648-664`. Returns -1 for off grid. */
    private fun spaceOppositeDot(state: State, space: Int, dot: Int): Int {
        val b = state.board
        val sx = b.sx
        val x = space % sx
        val y = space / sx
        val dotx = dot % sx
        val doty = dot / sx
        val dx = x - dotx
        val dy = y - doty
        val tx = dotx - dx
        val ty = doty - dy
        if (!inGrid(state, tx, ty)) return -1
        val ret = b.index(tx, ty)
        check(b.type[ret] == b.type[space]) { "opposite space has a different type" }
        return ret
    }

    private fun tileOpposite(state: State, space: Int): Int {
        val b = state.board
        check(b.flags[space] and F_TILE_ASSOC != 0) { "tile has no dot" }
        val dot = b.index(b.dotX[space], b.dotY[space])
        return spaceOppositeDot(state, space, dot)
    }

    private fun dotfortile(state: State, tile: Int, dot: Int): Boolean {
        val b = state.board
        val opposite = spaceOppositeDot(state, tile, dot)
        if (opposite < 0) return false
        if (b.flags[opposite] and F_TILE_ASSOC != 0 &&
            (b.dotX[opposite] != dot % b.sx || b.dotY[opposite] != dot / b.sx)
        ) {
            return false
        }
        return true
    }

    /**
     * `adjacencies`, `galaxies.c:685-710`. Writes the four edges and the
     * four opposite tiles; -1 means off grid.
     */
    private fun adjacencies(
        state: State,
        space: Int,
        edges: IntArray,
        tiles: IntArray,
    ) {
        val b = state.board
        val sx = b.sx
        val x = space % sx
        val y = space / sx
        val dxs = intArrayOf(-1, 1, 0, 0)
        val dys = intArrayOf(0, 0, -1, 1)
        for (n in 0 until 4) {
            val x1 = x + dxs[n]
            val y1 = y + dys[n]
            if (inGrid(state, x1, y1)) {
                edges[n] = b.index(x1, y1)
                val x2 = x1 + dxs[n]
                val y2 = y1 + dys[n]
                tiles[n] = if (inGrid(state, x2, y2)) b.index(x2, y2) else -1
            } else {
                edges[n] = -1
                tiles[n] = -1
            }
        }
    }

    private fun tilesFromEdge(state: State, space: Int): IntArray {
        val b = state.board
        val sx = b.sx
        val x = space % sx
        val y = space / sx
        return if (isVerticalEdge(x)) {
            intArrayOf(
                if (inGrid(state, x - 1, y)) b.index(x - 1, y) else -1,
                if (inGrid(state, x + 1, y)) b.index(x + 1, y) else -1,
            )
        } else {
            intArrayOf(
                if (inGrid(state, x, y - 1)) b.index(x, y - 1) else -1,
                if (inGrid(state, x, y + 1)) b.index(x, y + 1) else -1,
            )
        }
    }

    /** `outline_tile_fordot`, `galaxies.c:711-744`. */
    private fun outlineTileFordot(state: State, tile: Int, mark: Boolean): Boolean {
        val b = state.board
        check(b.type[tile] == S_TILE) { "not a tile" }
        val edges = IntArray(4)
        val tiles = IntArray(4)
        adjacencies(state, tile, edges, tiles)
        var didSomething = false
        for (i in 0 until 4) {
            if (edges[i] < 0) continue
            val edge = b.flags[edges[i]] and F_EDGE_SET != 0
            val same = if (tiles[i] >= 0) {
                if (b.flags[tile] and F_TILE_ASSOC == 0) {
                    b.flags[tiles[i]] and F_TILE_ASSOC == 0
                } else {
                    b.flags[tiles[i]] and F_TILE_ASSOC != 0 &&
                        b.dotX[tile] == b.dotX[tiles[i]] &&
                        b.dotY[tile] == b.dotY[tiles[i]]
                }
            } else {
                false
            }
            if (!edge && !same) {
                if (mark) b.flags[edges[i]] = b.flags[edges[i]] or F_EDGE_SET
                didSomething = true
            } else if (edge && same) {
                if (mark) b.flags[edges[i]] = b.flags[edges[i]] and F_EDGE_SET.inv()
                didSomething = true
            }
        }
        return didSomething
    }

    private fun foreachSub(
        state: State,
        startX: Int,
        startY: Int,
        quitsOnImpossible: Boolean,
        cb: (Int) -> Int,
    ): Int {
        var progress = false
        var impossible = false
        var y = startY
        while (y < state.sy) {
            var x = startX
            while (x < state.sx) {
                val ret = cb(state.board.index(x, y))
                if (ret == -1) {
                    if (quitsOnImpossible) return -1
                    impossible = true
                } else if (ret == 1) {
                    progress = true
                }
                x += 2
            }
            y += 2
        }
        return if (impossible) -1 else if (progress) 1 else 0
    }

    private fun foreachTile(
        state: State,
        quitsOnImpossible: Boolean,
        cb: (Int) -> Int,
    ): Int = foreachSub(state, 1, 1, quitsOnImpossible, cb)

    private fun foreachEdge(
        state: State,
        quitsOnImpossible: Boolean,
        cb: (Int) -> Int,
    ): Int {
        val ret1 = foreachSub(state, 0, 1, quitsOnImpossible, cb)
        val ret2 = foreachSub(state, 1, 0, quitsOnImpossible, cb)
        if (ret1 == -1 || ret2 == -1) return -1
        return if (ret1 != 0 || ret2 != 0) 1 else 0
    }

    // ------------------------------------------------------------------
    // State creation and descriptions, `galaxies.c:913-1065`.
    // ------------------------------------------------------------------

    /** `blank_game`, `galaxies.c:913-952`. */
    private fun blankGame(w: Int, h: Int): State {
        val sx = 2 * w + 1
        val sy = 2 * h + 1
        val state = State(w, h, Board(sx, sy), false, false, IntArray(0))
        val b = state.board
        for (x in 0 until sx) {
            for (y in 0 until sy) {
                val index = b.index(x, y)
                b.dotX[index] = 0
                b.dotY[index] = 0
                when {
                    x % 2 == 0 && y % 2 == 0 -> b.type[index] = S_VERTEX
                    x % 2 == 0 || y % 2 == 0 -> {
                        b.type[index] = S_EDGE
                        if (x == 0 || y == 0 || x == sx - 1 || y == sy - 1) {
                            b.flags[index] = b.flags[index] or F_EDGE_SET
                        }
                    }
                    else -> b.type[index] = S_TILE
                }
            }
        }
        return state
    }

    /** `game_update_dots`, `galaxies.c:953-970`. */
    private fun updateDots(state: State) {
        val b = state.board
        var count = 0
        for (i in 0 until state.sx * state.sy) {
            if (b.flags[i] and F_DOT != 0) count++
        }
        val dots = IntArray(count)
        var n = 0
        for (i in 0 until state.sx * state.sy) {
            if (b.flags[i] and F_DOT != 0) dots[n++] = i
        }
        state.dots = dots
    }

    /** `clear_game`, `galaxies.c:971-986`. */
    private fun clearGame(state: State, cleardots: Boolean) {
        val b = state.board
        for (x in 1 until state.sx - 1) {
            for (y in 1 until state.sy - 1) {
                val index = b.index(x, y)
                if (cleardots) {
                    b.flags[index] = 0
                } else {
                    b.flags[index] = b.flags[index] and (F_DOT or F_DOT_BLACK)
                }
            }
        }
        if (cleardots) updateDots(state)
    }

    /** `dup_game`, `galaxies.c:987-1004`. */
    private fun dupGame(state: State): State {
        val ret = State(
            state.w,
            state.h,
            state.board.copy(),
            state.completed,
            state.usedSolve,
            IntArray(0),
        )
        updateDots(ret)
        return ret
    }

    /** `encode_game`, `galaxies.c:1020-1065`. */
    private fun encodeGame(state: State): String {
        val b = state.board
        val out = StringBuilder()
        var run = 0
        for (y in 1 until state.sy - 1) {
            for (x in 1 until state.sx - 1) {
                val index = b.index(x, y)
                val f = b.flags[index]
                if (f and F_DOT == 0) {
                    run++
                } else {
                    while (run > 24) {
                        out.append('z')
                        run -= 25
                    }
                    out.append(((if (f and F_DOT_BLACK != 0) 'A' else 'a').code + run).toChar())
                    run = 0
                }
            }
        }
        return out.toString()
    }

    /** `dot_is_possible`, `galaxies.c:845-912`. */
    private fun dotIsPossible(state: State, space: Int, allowAssoc: Boolean): Boolean {
        val b = state.board
        val sx = b.sx
        val x = space % sx
        val y = space / sx
        var bx = 0
        var by = 0
        when (b.type[space]) {
            S_TILE -> {
                bx = 1
                by = 1
            }
            S_EDGE -> {
                if (isVerticalEdge(x)) {
                    bx = 2
                    by = 1
                } else {
                    bx = 1
                    by = 2
                }
            }
            else -> {
                bx = 2
                by = 2
            }
        }
        for (dx in -bx..bx) {
            for (dy in -by..by) {
                if (!inGrid(state, x + dx, y + dy)) continue
                val adj = b.index(x + dx, y + dy)
                if (!allowAssoc && b.flags[adj] and F_TILE_ASSOC != 0) return false
                if (dx != 0 || dy != 0) {
                    if (b.flags[adj] and F_DOT != 0) return false
                }
                if (abs(dx) < bx && abs(dy) < by && b.flags[adj] and F_EDGE_SET != 0) {
                    return false
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Generation, `galaxies.c:1066-1715`.
    // ------------------------------------------------------------------

    private fun shuffle(array: IntArray, count: Int, random: RandomState) {
        var i = count
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
            }
        }
    }

    /** `movedot_cb`, `galaxies.c:1066-1123`. */
    private fun movedotCb(
        state: State,
        tile: Int,
        op: Int,
        olddot: Int,
        newdot: Int,
    ): Int {
        val b = state.board
        check(b.type[tile] == S_TILE) { "not a tile" }
        if (b.flags[tile] and F_TILE_ASSOC == 0) return 0
        if (b.dotX[tile] != olddot % b.sx || b.dotY[tile] != olddot / b.sx) return 0

        val newopp = spaceOppositeDot(state, tile, newdot)
        if (op == 0) {
            if (newopp < 0) return -1
            if (b.flags[newopp] and F_TILE_ASSOC != 0 &&
                (b.dotX[newopp] != olddot % b.sx || b.dotY[newopp] != olddot / b.sx)
            ) {
                return -1
            }
            return 0
        }

        check(newopp >= 0) { "the new dot has no opposite tile" }
        addAssoc(state, tile, newdot)
        addAssoc(state, newopp, newdot)
        return 1
    }

    /**
     * `dot_expand_or_move`, `galaxies.c:1124-1270`. Expands the dot to
     * cover the tiles, or moves it to their centre of gravity.
     */
    private fun dotExpandOrMove(state: State, dot: Int, toadd: IntArray, nadd: Int): Boolean {
        val b = state.board
        check(b.flags[dot] and F_DOT != 0) { "not a dot" }

        var canExpand = true
        for (i in 0 until nadd) {
            val tileopp = spaceOppositeDot(state, toadd[i], dot)
            if (tileopp < 0) {
                canExpand = false
                break
            }
            if (b.flags[tileopp] and F_TILE_ASSOC != 0) {
                canExpand = false
                break
            }
        }
        if (canExpand) {
            for (i in 0 until nadd) {
                val tileopp = spaceOppositeDot(state, toadd[i], dot)
                addAssoc(state, toadd[i], dot)
                addAssoc(state, tileopp, dot)
            }
            return true
        }

        // Try to move the dot so that it encompasses the spaces.
        var nnew = b.nassoc[dot] + nadd
        var cx = (dot % b.sx) * b.nassoc[dot]
        var cy = (dot / b.sx) * b.nassoc[dot]
        for (i in 0 until nadd) {
            cx += toadd[i] % b.sx
            cy += toadd[i] / b.sx
        }
        if (cx % nnew != 0 || cy % nnew != 0) return false
        cx /= nnew
        cy /= nnew

        val newdot = b.index(cx, cy)
        var ret = foreachTile(state, true) { tile ->
            movedotCb(state, tile, 0, dot, newdot)
        }
        if (ret == -1) return false

        for (i in 0 until nadd) {
            var tileopp = spaceOppositeDot(state, toadd[i], newdot)
            if (tileopp >= 0 && b.flags[tileopp] and F_TILE_ASSOC != 0 &&
                (b.dotX[tileopp] != dot % b.sx || b.dotY[tileopp] != dot / b.sx)
            ) {
                tileopp = -1
            }
            if (tileopp < 0) return false
        }

        for (i in 0 until nadd) addAssoc(state, toadd[i], dot)

        removeDot(state, dot)
        addDot(state, newdot)

        ret = foreachTile(state, false) { tile ->
            movedotCb(state, tile, 1, dot, newdot)
        }
        check(ret == 1) { "the moved dot changed no tile" }
        return true
    }

    /** `generate_try_block`, `galaxies.c:1271-1332`. */
    private fun generateTryBlock(
        state: State,
        random: RandomState,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): Boolean {
        val b = state.board
        if (!inGrid(state, x1, y1) || !inGrid(state, x2, y2)) return false

        val maxsz = sqrt((state.w * state.h).toDouble()).toInt() * 2

        val toadd = IntArray(MAX_TOADD)
        var nadd = 0
        var x = x1
        while (x <= x2) {
            var y = y1
            while (y <= y2) {
                check(nadd < MAX_TOADD) { "too many tiles in the block" }
                val sp = b.index(x, y)
                check(b.type[sp] == S_TILE) { "the block is not all tiles" }
                if (b.flags[sp] and F_TILE_ASSOC != 0) return false
                toadd[nadd++] = sp
                y += 2
            }
            x += 2
        }

        val outside = ArrayList<Int>(MAX_OUTSIDE)
        fun addOutside(ox: Int, oy: Int) {
            if (inGrid(state, ox, oy)) {
                check(outside.size < MAX_OUTSIDE) { "too many outside spaces" }
                outside += b.index(ox, oy)
            }
        }
        x = x1
        while (x <= x2) {
            addOutside(x, y1 - 2)
            addOutside(x, y2 + 2)
            x += 2
        }
        var y = y1
        while (y <= y2) {
            addOutside(x1 - 2, y)
            addOutside(x2 + 2, y)
            y += 2
        }
        val order = IntArray(outside.size) { it }
        shuffle(order, order.size, random)

        for (i in 0 until order.size) {
            val sp = outside[order[i]]
            if (b.flags[sp] and F_TILE_ASSOC == 0) continue
            val dot = b.index(b.dotX[sp], b.dotY[sp])
            if (b.nassoc[dot] >= maxsz) continue
            if (dotExpandOrMove(state, dot, toadd, nadd)) return true
        }
        return false
    }

    /** `generate_pass`, `galaxies.c:1333-1409`. */
    private fun generatePass(
        state: State,
        random: RandomState,
        scratch: IntArray,
        perc: Int,
        flags: Int,
    ) {
        val sz = state.sx * state.sy
        for (i in 0 until sz) scratch[i] = i
        shuffle(scratch, sz, random)

        val nspc = (perc.toLong() * sz.toLong() / 100L).toInt()
        val b = state.board
        for (i in 0 until nspc) {
            val sp = scratch[i]
            var x1 = sp % b.sx
            var y1 = sp / b.sx
            var x2 = x1
            var y2 = y1

            if (b.type[sp] == S_EDGE) {
                if (isVerticalEdge(x1)) {
                    x1--
                    x2++
                } else {
                    y1--
                    y2++
                }
            }
            if (b.type[sp] != S_VERTEX) {
                if (generateTryBlock(state, random, x1, y1, x2, y2)) continue
            }

            if (flags and GP_DOTS == 0) continue

            if (b.type[sp] == S_EDGE && i % 2 != 0) continue

            if (dotIsPossible(state, sp, false)) {
                addDot(state, sp)
                val ret = solverObviousDot(state, sp)
                /*
                 * C asserts that the obvious associations succeed. The
                 * distance test above rules out an associated tile in
                 * the box, so the port stops the dot instead of
                 * aborting if a hand-made case ever reaches it.
                 */
                if (ret < 0) continue
            }
        }
    }

    /** `is_wiggle` and `measure_wiggliness`, `galaxies.c:1410-1445`. */
    private fun isWiggle(state: State, x: Int, y: Int, dx: Int, dy: Int): Boolean {
        val b = state.board
        val x1 = x + 2 * dx
        val y1 = y + 2 * dy
        val x2 = x - 2 * dy
        val y2 = y + 2 * dx
        if (!inGrid(state, x1, y1) || !inGrid(state, x2, y2)) return false
        val t = b.index(x, y)
        val t1 = b.index(x1, y1)
        val t2 = b.index(x2, y2)
        return b.dotX[t1] == b.dotX[t2] && b.dotY[t1] == b.dotY[t2] &&
            !(b.dotX[t1] == b.dotX[t] && b.dotY[t1] == b.dotY[t])
    }

    private fun measureWiggliness(state: State): Int {
        var nwiggles = 0
        var y = 1
        while (y < state.sy) {
            var x = 1
            while (x < state.sx) {
                if (y + 2 < state.sy) {
                    if (isWiggle(state, x, y, 0, 1)) nwiggles++
                    if (isWiggle(state, x, y, 0, -1)) nwiggles++
                    if (isWiggle(state, x, y, 1, 0)) nwiggles++
                    if (isWiggle(state, x, y, -1, 0)) nwiggles++
                }
                x += 2
            }
            y += 2
        }
        return nwiggles
    }

    /** `game_configure` (`galaxies.c:290-316`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Normal:Unreasonable",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`galaxies.c:318-327`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
        )

    /** `new_game_desc`, `galaxies.c:1446-1703`. */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        var state = blankGame(p.w, p.h)
        val scratch = IntArray(state.sx * state.sy)

        while (true) {
            cancel.check()
            var bestWiggliness = -1
            var best: State? = null
            for (i in 0 until GENERATE_TRIES) {
                cancel.check()
                do {
                    clearGame(state, true)
                    generatePass(state, random, scratch, 100, GP_DOTS)
                    updateDots(state)
                } while (state.dots.size == 1)

                val thisWiggliness = measureWiggliness(state)
                if (thisWiggliness > bestWiggliness) {
                    bestWiggliness = thisWiggliness
                    best = dupGame(state)
                }
            }
            state = requireNotNull(best) { "the generator kept no attempt" }

            val b = state.board
            for (i in 0 until state.sx * state.sy) {
                if (b.type[i] == S_TILE) outlineTileFordot(state, i, true)
            }
            check(checkComplete(state, null, null)) { "the outlined board is not complete" }

            val copy = dupGame(state)
            clearGame(copy, false)
            val diff = solverState(copy, p.diff)
            check(diff != DIFF_IMPOSSIBLE) { "the generator made an impossible puzzle" }
            if (diff != p.diff) continue

            val desc = encodeGame(state)
            val blank = blankGame(p.w, p.h)
            val aux = diffGame(blank, state, true)
            return Description(desc, aux)
        }
    }

    // `galaxies.c` keeps the best of ten attempts over a (2w+1)(2h+1) space array and grades each with the solver; the largest preset is 15x15 and the accepted area is unbounded.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w.toLong() * p.h > 225) Game.SLOW_GENERATION_WARNING else null
    }

    /** `dots_too_close`, `galaxies.c:1704-1715`. */
    private fun dotsTooClose(state: State): Boolean {
        val tmp = dupGame(state)
        return solverObvious(tmp) == -1
    }

    /** `load_game`, `galaxies.c:1716-1763`. */
    private fun loadGame(p: Params, desc: String): State {
        val state = blankGame(p.w, p.h)
        val b = state.board

        fun fail(message: String): Nothing = throw IllegalArgumentException(message)

        var i = 0
        for (c in desc) {
            val df: Int
            when (c) {
                'z' -> {
                    i += 25
                    continue
                }
                in 'a'..'y' -> {
                    i += c - 'a'
                    df = 0
                }
                in 'A'..'Y' -> {
                    i += c - 'A'
                    df = F_DOT_BLACK
                }
                else -> fail("Invalid character in game description")
            }
            val y = (i / (state.sx - 2)) + 1
            val x = (i % (state.sx - 2)) + 1
            if (!inUi(state, x, y)) fail("Too much data to fit in grid")
            val index = b.index(x, y)
            addDot(state, index)
            b.flags[index] = b.flags[index] or df
            i++
        }
        updateDots(state)

        if (dotsTooClose(state)) fail("Dots too close together")
        return state
    }

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        return try {
            loadGame(p, desc)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    override fun newState(params: Game.Params, desc: String): Game.State =
        loadGame(params as Params, desc)

    // ------------------------------------------------------------------
    // Solver, `galaxies.c:1807-2633`.
    // ------------------------------------------------------------------

    private class SolverContext(state: State) {
        val sz: Int = state.sx * state.sy
        val scratch: IntArray = IntArray(sz)
        val dsf: Dsf = Dsf.new(sz)
        val iscratch: IntArray = IntArray(sz)
    }

    private fun solverAddAssoc(state: State, tile: Int, dx: Int, dy: Int): Int {
        val b = state.board
        val dot = b.index(dx, dy)
        val tileOpp = spaceOppositeDot(state, tile, dot)
        check(b.type[tile] == S_TILE) { "not a tile" }
        if (b.flags[tile] and F_TILE_ASSOC != 0) {
            if (b.dotX[tile] != dx || b.dotY[tile] != dy) return -1
            return 0
        }
        if (tileOpp < 0) return -1
        if (b.flags[tileOpp] and F_TILE_ASSOC != 0 &&
            (b.dotX[tileOpp] != dx || b.dotY[tileOpp] != dy)
        ) {
            return -1
        }
        addAssoc(state, tile, dot)
        addAssoc(state, tileOpp, dot)
        return 1
    }

    private fun solverObviousDot(state: State, dot: Int): Int {
        val b = state.board
        check(b.flags[dot] and F_DOT != 0) { "not a dot" }
        var didSomething = 0
        val dx0 = dot % b.sx
        val dy0 = dot / b.sx
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (!inGrid(state, dx0 + dx, dy0 + dy)) continue
                val tile = b.index(dx0 + dx, dy0 + dy)
                if (b.type[tile] == S_TILE) {
                    val ret = solverAddAssoc(state, tile, dx0, dy0)
                    if (ret < 0) return -1
                    if (ret > 0) didSomething = 1
                }
            }
        }
        return didSomething
    }

    private fun solverObvious(state: State): Int {
        var didSomething = 0
        for (dot in state.dots) {
            val ret = solverObviousDot(state, dot)
            if (ret < 0) return -1
            if (ret > 0) didSomething = 1
        }
        return didSomething
    }

    private fun solverLinesOppositeCb(state: State, edge: Int): Int {
        val b = state.board
        check(b.type[edge] == S_EDGE) { "not an edge" }
        val tiles = tilesFromEdge(state, edge)
        var didSomething = 0

        if (b.flags[edge] and F_EDGE_SET == 0 &&
            tiles[0] >= 0 && tiles[1] >= 0 &&
            b.flags[tiles[0]] and F_TILE_ASSOC != 0 &&
            b.flags[tiles[1]] and F_TILE_ASSOC != 0 &&
            (b.dotX[tiles[0]] != b.dotX[tiles[1]] ||
                b.dotY[tiles[0]] != b.dotY[tiles[1]])
        ) {
            b.flags[edge] = b.flags[edge] or F_EDGE_SET
            didSomething = 1
        }

        if (b.flags[edge] and F_EDGE_SET == 0) return didSomething
        for (n in 0 until 2) {
            if (tiles[n] < 0) continue
            check(b.type[tiles[n]] == S_TILE) { "the neighbour is not a tile" }
            if (b.flags[tiles[n]] and F_TILE_ASSOC == 0) continue

            val tileOpp = tileOpposite(state, tiles[n])
            if (tileOpp < 0) return -1

            val dx = tiles[n] % b.sx - edge % b.sx
            val dy = tiles[n] / b.sx - edge / b.sx
            check(inGrid(state, tileOpp % b.sx + dx, tileOpp / b.sx + dy)) {
                "the opposite edge is off grid"
            }
            val edgeOpp = b.index(tileOpp % b.sx + dx, tileOpp / b.sx + dy)
            if (b.flags[edgeOpp] and F_EDGE_SET == 0) {
                b.flags[edgeOpp] = b.flags[edgeOpp] or F_EDGE_SET
                didSomething = 1
            }
        }
        return didSomething
    }

    private fun solverSpacesOnepossCb(state: State, tile: Int): Int {
        val b = state.board
        check(b.type[tile] == S_TILE) { "not a tile" }
        if (b.flags[tile] and F_TILE_ASSOC != 0) return 0

        val edgeAdj = IntArray(4)
        val tileAdj = IntArray(4)
        adjacencies(state, tile, edgeAdj, tileAdj)

        var eset = 0
        var dotx = -1
        var doty = -1
        for (n in 0 until 4) {
            check(edgeAdj[n] >= 0) { "a tile has no edge" }
            if (b.flags[edgeAdj[n]] and F_EDGE_SET != 0) {
                eset++
            } else {
                if (tileAdj[n] < 0) return 0
                check(b.type[tileAdj[n]] == S_TILE) { "the neighbour is not a tile" }
                if (b.flags[tileAdj[n]] and F_TILE_ASSOC == 0) return 0
                if (dotx != -1 && doty != -1 &&
                    (b.dotX[tileAdj[n]] != dotx || b.dotY[tileAdj[n]] != doty)
                ) {
                    return 0
                }
                dotx = b.dotX[tileAdj[n]]
                doty = b.dotY[tileAdj[n]]
            }
        }
        if (eset == 4) return -1
        check(dotx != -1 && doty != -1) { "an empty tile has no dot" }

        val ret = solverAddAssoc(state, tile, dotx, doty)
        if (ret == -1) return -1
        check(ret != 0) { "the deduction changed nothing" }
        return 1
    }

    private fun solverExpandCheckdot(state: State, tile: Int, dot: Int): Boolean {
        val b = state.board
        if (b.flags[tile] and F_TILE_ASSOC == 0) return true
        return b.dotX[tile] == dot % b.sx && b.dotY[tile] == dot / b.sx
    }

    private fun solverExpandFromdot(state: State, dot: Int, sctx: SolverContext) {
        val b = state.board
        val sx = b.sx

        for (y in 1 until state.sy step 2) {
            for (x in 1 until state.sx step 2) {
                val index = b.index(x, y)
                b.flags[index] = b.flags[index] and F_MARK.inv()
            }
        }

        val dotx = dot % sx
        val doty = dot / sx
        when (b.type[dot]) {
            S_TILE -> {
                sctx.scratch[0] = dot
                sctx.scratch[1] = dot
            }
            S_EDGE -> {
                val tiles = tilesFromEdge(state, dot)
                sctx.scratch[0] = tiles[0]
                sctx.scratch[1] = tiles[1]
            }
            else -> {
                sctx.scratch[0] = b.index(dotx - 1, doty - 1)
                sctx.scratch[1] = b.index(dotx + 1, doty + 1)
            }
        }
        check(sctx.scratch[0] >= 0 && sctx.scratch[1] >= 0) { "the seed is off grid" }
        check(b.flags[sctx.scratch[0]] and F_TILE_ASSOC != 0) { "the seed is not associated" }
        check(b.flags[sctx.scratch[1]] and F_TILE_ASSOC != 0) { "the seed is not associated" }
        b.flags[sctx.scratch[0]] = b.flags[sctx.scratch[0]] or F_MARK
        b.flags[sctx.scratch[1]] = b.flags[sctx.scratch[1]] or F_MARK

        var start = 0
        var end = 2
        var next = 2

        while (true) {
            var i = start
            while (i < end) {
                val t1 = sctx.scratch[i]
                val edges = IntArray(4)
                val tileAdj = IntArray(4)
                adjacencies(state, t1, edges, tileAdj)
                for (j in 0 until 4) {
                    check(edges[j] >= 0) { "a tile has no edge" }
                    if (b.flags[edges[j]] and F_EDGE_SET != 0) continue
                    if (tileAdj[j] < 0) continue
                    if (b.flags[tileAdj[j]] and F_MARK != 0) continue

                    val tileAdj2 = spaceOppositeDot(state, tileAdj[j], dot)
                    if (tileAdj2 < 0) {
                        b.flags[tileAdj[j]] = b.flags[tileAdj[j]] or F_MARK
                        continue
                    }
                    check(b.flags[tileAdj2] and F_MARK == 0) { "the opposite was already marked" }

                    if (solverExpandCheckdot(state, tileAdj[j], dot) &&
                        solverExpandCheckdot(state, tileAdj2, dot)
                    ) {
                        sctx.scratch[next++] = tileAdj[j]
                        sctx.scratch[next++] = tileAdj2
                    }
                    b.flags[tileAdj[j]] = b.flags[tileAdj[j]] or F_MARK
                    b.flags[tileAdj2] = b.flags[tileAdj2] or F_MARK
                }
                i += 2
            }
            if (next > end) {
                start = end
                end = next
                continue
            }
            break
        }

        for (i in 0 until end) {
            val tile = sctx.scratch[i]
            if (b.flags[tile] and F_TILE_ASSOC != 0) continue
            if (b.flags[tile] and F_REACHABLE != 0) {
                b.flags[tile] = b.flags[tile] or F_MULTIPLE
            } else {
                b.flags[tile] = b.flags[tile] or F_REACHABLE
                b.dotX[tile] = dotx
                b.dotY[tile] = doty
            }
        }
    }

    private fun solverExpandPostcb(state: State, tile: Int): Int {
        val b = state.board
        check(b.type[tile] == S_TILE) { "not a tile" }
        if (b.flags[tile] and F_TILE_ASSOC != 0) return 0
        if (b.flags[tile] and F_REACHABLE == 0) return -1
        if (b.flags[tile] and F_MULTIPLE != 0) return 0
        return solverAddAssoc(state, tile, b.dotX[tile], b.dotY[tile])
    }

    private fun solverExpandDots(state: State, sctx: SolverContext): Int {
        val b = state.board
        for (i in 0 until sctx.sz) {
            b.flags[i] = b.flags[i] and (F_REACHABLE or F_MULTIPLE).inv()
        }
        for (dot in state.dots) solverExpandFromdot(state, dot, sctx)
        return foreachTile(state, true) { tile -> solverExpandPostcb(state, tile) }
    }

    private fun solverExtendExclaves(state: State, sctx: SolverContext): Int {
        val b = state.board
        val sx = b.sx
        var doneSomething = 0

        sctx.dsf.reinit()
        var x = 1
        while (x < state.sx) {
            var y = 1
            while (y < state.sy) {
                val tile = b.index(x, y)
                if (b.flags[tile] and F_TILE_ASSOC == 0) {
                    y += 2
                    continue
                }
                val dotx = b.dotX[tile]
                val doty = b.dotY[tile]

                if (inGrid(state, x + 2, y)) {
                    val other = b.index(x + 2, y)
                    if (b.flags[other] and F_TILE_ASSOC != 0 &&
                        b.dotX[other] == dotx && b.dotY[other] == doty
                    ) {
                        sctx.dsf.merge(y * sx + x, y * sx + (x + 2))
                    }
                }
                if (inGrid(state, x, y + 2)) {
                    val other = b.index(x, y + 2)
                    if (b.flags[other] and F_TILE_ASSOC != 0 &&
                        b.dotX[other] == dotx && b.dotY[other] == doty
                    ) {
                        sctx.dsf.merge(y * sx + x, (y + 2) * sx + x)
                    }
                }
                y += 2
            }
            x += 2
        }

        x = 1
        while (x < state.sx) {
            var y = 1
            while (y < state.sy) {
                val index = y * sx + x
                if (b.flags[b.index(x, y)] and F_TILE_ASSOC == 0 ||
                    sctx.dsf.canonify(index) != index
                ) {
                    sctx.iscratch[index] = -1
                } else {
                    sctx.iscratch[index] = 0
                    sctx.iscratch[index - 1] = 0
                }
                y += 2
            }
            x += 2
        }

        x = 1
        while (x < state.sx) {
            var y = 1
            while (y < state.sy) {
                if (b.flags[b.index(x, y)] and F_TILE_ASSOC != 0) {
                    y += 2
                    continue
                }
                val ni = IntArray(4)
                var nn = 0
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (dx != 0 && dy != 0) continue
                        if (dx == 0 && dy == 0) continue
                        if (inGrid(state, x + 2 * dx, y + 2 * dy) &&
                            b.flags[b.index(x + 2 * dx, y + 2 * dy)] and F_TILE_ASSOC != 0
                        ) {
                            var nindex = sctx.dsf.canonify(
                                (y + 2 * dy) * sx + (x + 2 * dx),
                            )
                            var i = 0
                            while (i < nn) {
                                if (ni[i] == nindex) break
                                i++
                            }
                            if (i == nn) {
                                sctx.iscratch[nindex]++
                                check(nindex > 0) { "a component index of zero" }
                                sctx.iscratch[nindex - 1] = y * sx + x
                                ni[nn++] = nindex
                            }
                        }
                    }
                }
                y += 2
            }
            x += 2
        }

        x = 1
        while (x < state.sx) {
            var y = 1
            while (y < state.sy) {
                val index = y * sx + x
                if (sctx.iscratch[index] == -1) {
                    y += 2
                    continue
                }
                val tile = b.index(x, y)
                if (b.flags[tile] and F_TILE_ASSOC == 0) {
                    y += 2
                    continue
                }
                val dotx = b.dotX[tile]
                val doty = b.dotY[tile]

                if (index == sctx.dsf.canonify(
                        (doty or 1) * sx + (dotx or 1),
                    )
                ) {
                    y += 2
                    continue
                }
                if (sctx.iscratch[index] == 0) return -1
                if (sctx.iscratch[index] != 1) {
                    y += 2
                    continue
                }
                check(sctx.iscratch[index - 1] != 0) { "a liberty index of zero" }
                val ex = sctx.iscratch[index - 1] % sx
                val ey = sctx.iscratch[index - 1] / sx
                val liberty = b.index(ex, ey)
                if (b.flags[liberty] and F_TILE_ASSOC != 0) {
                    y += 2
                    continue
                }
                val added = solverAddAssoc(state, liberty, dotx, doty)
                if (added < 0) return -1
                if (added > 0) doneSomething = 1
                y += 2
            }
            x += 2
        }

        return doneSomething
    }

    private class RecurseContext {
        var best: Int = -1
        var bestn: Int = 0
    }

    private fun solverRecurseCb(state: State, tile: Int, rctx: RecurseContext): Int {
        val b = state.board
        check(b.type[tile] == S_TILE) { "not a tile" }
        if (b.flags[tile] and F_TILE_ASSOC != 0) return 0
        var n = 0
        for (dot in state.dots) {
            if (dotfortile(state, tile, dot)) n++
        }
        if (n > rctx.bestn) {
            rctx.bestn = n
            rctx.best = tile
        }
        return 0
    }

    private fun solverRecurse(state: State, maxdiff: Int, depth: Int): Int {
        if (depth >= MAXRECURSE) return DIFF_UNFINISHED

        val rctx = RecurseContext()
        foreachTile(state, false) { tile -> solverRecurseCb(state, tile, rctx) }
        if (rctx.bestn == 0) return DIFF_IMPOSSIBLE

        val best = rctx.best
        val b = state.board
        val ingrid = b.copy()
        var outgrid: Board? = null
        var diff = DIFF_IMPOSSIBLE

        for (n in 0 until state.dots.size) {
            b.copyFrom(ingrid)
            val dot = state.dots[n]
            if (!dotfortile(state, best, dot)) continue

            solverAddAssoc(state, best, dot % b.sx, dot / b.sx)
            val ret = solverStateInner(state, maxdiff, depth + 1)

            if (diff == DIFF_IMPOSSIBLE && ret != DIFF_IMPOSSIBLE) {
                outgrid = b.copy()
            }

            val bestopp = tileOpposite(state, best)
            check(bestopp >= 0) { "the recursion tile has no opposite" }
            check(b.flags[bestopp] and F_TILE_ASSOC != 0) { "the opposite is not associated" }
            removeAssoc(state, best)
            removeAssoc(state, bestopp)

            when {
                ret == DIFF_AMBIGUOUS || ret == DIFF_UNFINISHED -> diff = ret
                ret == DIFF_IMPOSSIBLE -> Unit
                diff == DIFF_IMPOSSIBLE -> diff = DIFF_UNREASONABLE
                else -> diff = DIFF_AMBIGUOUS
            }
            if (diff == DIFF_AMBIGUOUS || diff == DIFF_UNFINISHED) break
        }

        outgrid?.let { b.copyFrom(it) }
        return diff
    }

    private fun solverStateInner(state: State, maxdiff: Int, depth: Int): Int {
        val sctx = SolverContext(state)
        var diff = DIFF_NORMAL

        val ret0 = solverObvious(state)
        if (ret0 < 0) return DIFF_IMPOSSIBLE

        loop@ while (true) {
            var ret = foreachEdge(state, true) { edge -> solverLinesOppositeCb(state, edge) }
            if (ret < 0) return DIFF_IMPOSSIBLE
            if (ret > 0) {
                diff = maxOf(diff, DIFF_NORMAL)
                continue@loop
            }

            ret = foreachTile(state, true) { tile -> solverSpacesOnepossCb(state, tile) }
            if (ret < 0) return DIFF_IMPOSSIBLE
            if (ret > 0) {
                diff = maxOf(diff, DIFF_NORMAL)
                continue@loop
            }

            ret = solverExpandDots(state, sctx)
            if (ret < 0) return DIFF_IMPOSSIBLE
            if (ret > 0) {
                diff = maxOf(diff, DIFF_NORMAL)
                continue@loop
            }

            ret = solverExtendExclaves(state, sctx)
            if (ret < 0) return DIFF_IMPOSSIBLE
            if (ret > 0) {
                diff = maxOf(diff, DIFF_NORMAL)
                continue@loop
            }

            if (maxdiff <= DIFF_NORMAL) break@loop
            break@loop
        }

        if (checkComplete(state, null, null)) return diff

        return if (maxdiff >= DIFF_UNREASONABLE) {
            solverRecurse(state, maxdiff, depth)
        } else {
            DIFF_UNFINISHED
        }
    }

    private fun solverState(state: State, maxdiff: Int): Int =
        solverStateInner(state, maxdiff, 0)

    // ------------------------------------------------------------------
    // Moves and solve, `galaxies.c:2634-3426`.
    // ------------------------------------------------------------------

    /** `diff_game`, `galaxies.c:761-844`. */
    private fun diffGame(src: State, dest: State, issolve: Boolean): String {
        val out = StringBuilder()
        if (issolve) out.append('S')
        val sb = src.board
        val db = dest.board
        val achar = if (issolve) 'a' else 'A'
        for (x in 0 until sb.sx) {
            for (y in 0 until sb.sy) {
                val si = sb.index(x, y)
                val di = db.index(x, y)
                var token: String? = null
                if (sb.type[si] == S_TILE) {
                    val srcAssoc = sb.flags[si] and F_TILE_ASSOC != 0
                    val destAssoc = db.flags[di] and F_TILE_ASSOC != 0
                    if (srcAssoc && destAssoc) {
                        if (sb.dotX[si] != db.dotX[di] || sb.dotY[si] != db.dotY[di]) {
                            token = "$achar$x,$y,${db.dotX[di]},${db.dotY[di]}"
                        }
                    } else if (srcAssoc) {
                        token = "U$x,$y"
                    } else if (destAssoc) {
                        token = "$achar$x,$y,${db.dotX[di]},${db.dotY[di]}"
                    }
                } else if (sb.type[si] == S_EDGE) {
                    if ((sb.flags[si] and F_EDGE_SET) != (db.flags[di] and F_EDGE_SET)) {
                        token = "E$x,$y"
                    }
                }
                if (token != null) {
                    if (out.isNotEmpty()) out.append(';')
                    out.append(token)
                }
            }
        }
        return out.toString()
    }

    /**
     * The initial state of the current history entry: the loaded board
     * with every player edge and association cleared. The seam passes
     * only the current state to [solve], while C passes `states[0]`
     * separately (`midend.c:2105-2109`); the clues and dots never change
     * during play, so this rebuild is the same board.
     */
    private fun initialState(state: State): State {
        val ret = dupGame(state)
        val b = ret.board
        for (i in 0 until b.sx * b.sy) {
            b.flags[i] = b.flags[i] and
                (F_EDGE_SET or F_TILE_ASSOC or F_DOT_HOLD).inv()
            b.dotX[i] = 0
            b.dotY[i] = 0
            b.nassoc[i] = 0
        }
        for (x in 0 until b.sx) {
            for (y in 0 until b.sy) {
                if ((x % 2 == 0) != (y % 2 == 0) &&
                    (x == 0 || y == 0 || x == b.sx - 1 || y == b.sy - 1)
                ) {
                    b.flags[b.index(x, y)] = b.flags[b.index(x, y)] or F_EDGE_SET
                }
            }
        }
        ret.completed = false
        ret.usedSolve = false
        updateDots(ret)
        return ret
    }

    /**
     * `solve_game`, `galaxies.c:2634-2675`. The stored answer is applied
     * to the initial state first; otherwise the current state and then
     * the initial state are offered to the solver. Associations are
     * cleared from the result, so the move only carries the edges.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? {
        val s = state as State
        val initial = initialState(s)
        val tosolve: State
        if (auxInfo != null) {
            tosolve = executeMove(initial, auxInfo) as State? ?: return null
        } else {
            var attempt = dupGame(s)
            var diff = solverState(attempt, DIFF_UNREASONABLE)
            if (diff != DIFF_UNFINISHED && diff != DIFF_IMPOSSIBLE) {
                tosolve = attempt
            } else {
                attempt = dupGame(initial)
                diff = solverState(attempt, DIFF_UNREASONABLE)
                if (diff == DIFF_UNFINISHED || diff == DIFF_IMPOSSIBLE) return null
                tosolve = attempt
            }
        }

        val b = tosolve.board
        for (i in 0 until tosolve.sx * tosolve.sy) {
            b.flags[i] = b.flags[i] and F_TILE_ASSOC.inv()
        }
        return diffGame(s, tosolve, true)
    }

    /**
     * One `sscanf` conversion group: [count] integers separated by
     * commas, with C whitespace rules and saturating overflow.
     */
    private class Scan(val values: IntArray, val next: Int)

    private fun scanInts(text: String, start: Int, count: Int): Scan? {
        var i = start
        val out = IntArray(count)
        for (k in 0 until count) {
            while (i < text.length && isCSpace(text[i])) i++
            var negative = false
            if (i < text.length && (text[i] == '+' || text[i] == '-')) {
                negative = text[i] == '-'
                i++
            }
            val digits = i
            var value = 0L
            var overflow = false
            while (i < text.length && text[i] in '0'..'9') {
                val digit = (text[i] - '0').toLong()
                if (!overflow) {
                    if (value > (Int.MAX_VALUE.toLong() + 1L - digit) / 10L) {
                        overflow = true
                    } else {
                        value = value * 10L + digit
                    }
                }
                i++
            }
            if (i == digits) return null
            out[k] = if (overflow) {
                if (negative) Int.MIN_VALUE else Int.MAX_VALUE
            } else {
                val signed = if (negative) -value else value
                signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
            if (k < count - 1) {
                if (i >= text.length || text[i] != ',') return null
                i++
            }
        }
        return Scan(out, i)
    }

    /** `execute_move`, `galaxies.c:3256-3426`. */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val ret = dupGame(state as State)
        val b = ret.board
        val sx = b.sx
        var currentlySolving = false
        var p = 0

        while (p < move.length) {
            val c = move[p]
            when (c) {
                'E', 'U', 'M' -> {
                    val scan = scanInts(move, p + 1, 2) ?: return null
                    val x = scan.values[0]
                    val y = scan.values[1]
                    if (!inUi(ret, x, y)) return null
                    val sp = b.index(x, y)
                    when (c) {
                        'E' -> {
                            if (b.type[sp] != S_EDGE) return null
                            b.flags[sp] = b.flags[sp] xor F_EDGE_SET
                        }
                        'U' -> {
                            if (b.type[sp] != S_TILE ||
                                b.flags[sp] and F_TILE_ASSOC == 0
                            ) {
                                return null
                            }
                            if (currentlySolving) {
                                removeAssoc(ret, sp)
                            } else {
                                removeAssocWithOpposite(ret, sp)
                            }
                        }
                        else -> {
                            if (b.flags[sp] and F_DOT == 0) return null
                            b.flags[sp] = b.flags[sp] xor F_DOT_HOLD
                        }
                    }
                    p = scan.next
                }
                'A', 'a' -> {
                    val scan = scanInts(move, p + 1, 4) ?: return null
                    val x = scan.values[0]
                    val y = scan.values[1]
                    val ax = scan.values[2]
                    val ay = scan.values[3]
                    if (x < 1 || y < 1 || x >= sx - 1 || y >= ret.sy - 1 ||
                        ax < 1 || ay < 1 || ax >= sx - 1 || ay >= ret.sy - 1
                    ) {
                        return null
                    }
                    val dot = b.index(ax, ay)
                    if (b.flags[dot] and F_DOT == 0) return null
                    if (b.flags[dot] and F_DOT_HOLD != 0) return null

                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            val sp = b.index(x + dx, y + dy)
                            if (b.type[sp] != S_TILE) continue
                            if (b.flags[sp] and F_TILE_ASSOC != 0) {
                                val other = b.index(b.dotX[sp], b.dotY[sp])
                                if (b.flags[other] and F_DOT_HOLD != 0) continue
                            }
                            if (currentlySolving) {
                                addAssoc(ret, sp, dot)
                            } else {
                                addAssocWithOpposite(ret, sp, dot)
                            }
                        }
                    }
                    p = scan.next
                }
                'S' -> {
                    p++
                    ret.usedSolve = true
                    currentlySolving = true
                }
                else -> return null
            }
            if (p < move.length) {
                if (move[p] == ';') {
                    p++
                } else {
                    return null
                }
            }
        }

        if (checkComplete(ret, null, null)) ret.completed = true
        return ret
    }

    // ------------------------------------------------------------------
    // Completion check, `galaxies.c:3077-3255`.
    // ------------------------------------------------------------------

    private fun checkComplete(state: State, dsfIn: Dsf?, colours: IntArray?): Boolean {
        val w = state.w
        val h = state.h
        val b = state.board
        val dsf = dsfIn ?: Dsf.new(w * h)
        if (dsfIn != null) dsfIn.reinit()

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y + 1 < h &&
                    b.flags[b.index(2 * x + 1, 2 * y + 2)] and F_EDGE_SET == 0
                ) {
                    dsf.merge(y * w + x, (y + 1) * w + x)
                }
                if (x + 1 < w &&
                    b.flags[b.index(2 * x + 2, 2 * y + 1)] and F_EDGE_SET == 0
                ) {
                    dsf.merge(y * w + x, y * w + (x + 1))
                }
            }
        }

        val minx = IntArray(w * h) { w + 1 }
        val miny = IntArray(w * h) { h + 1 }
        val maxx = IntArray(w * h) { -1 }
        val maxy = IntArray(w * h) { -1 }
        val valid = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = dsf.canonify(y * w + x)
                if (minx[i] > x) minx[i] = x
                if (maxx[i] < x) maxx[i] = x
                if (miny[i] > y) miny[i] = y
                if (maxy[i] < y) maxy[i] = y
                valid[i] = true
            }
        }

        val cxArr = IntArray(w * h)
        val cyArr = IntArray(w * h)
        val colour = IntArray(w * h)
        for (i in 0 until w * h) {
            if (!valid[i]) continue
            val cx = minx[i] + maxx[i] + 1
            val cy = miny[i] + maxy[i] + 1
            cxArr[i] = cx
            cyArr[i] = cy
            val centre = b.index(cx, cy)
            if (b.flags[centre] and F_DOT == 0) valid[i] = false
            if (dsf.canonify(((cy - 1) / 2) * w + (cx - 1) / 2) != i ||
                dsf.canonify((cy / 2) * w + (cx - 1) / 2) != i ||
                dsf.canonify(((cy - 1) / 2) * w + (cx / 2)) != i ||
                dsf.canonify((cy / 2) * w + (cx / 2)) != i
            ) {
                valid[i] = false
            }
            colour[i] = if (b.flags[centre] and F_DOT_BLACK != 0) 2 else 1
        }

        for (y in 1 until state.sy - 1) {
            for (x in 1 until state.sx - 1) {
                val sp = b.index(x, y)
                if (b.flags[sp] and F_DOT != 0) {
                    for (cy in ((y - 1) shr 1)..(y shr 1)) {
                        for (cx in ((x - 1) shr 1)..(x shr 1)) {
                            val i = dsf.canonify(cy * w + cx)
                            if (x != cxArr[i] || y != cyArr[i]) valid[i] = false
                        }
                    }
                }
                if (b.flags[sp] and F_EDGE_SET != 0) {
                    val cx1 = (x - 1) shr 1
                    val cx2 = x shr 1
                    val cy1 = (y - 1) shr 1
                    val cy2 = y shr 1
                    check((cx1 == cx2) != (cy1 == cy2)) { "an edge is not on a grid line" }
                    val i = dsf.canonify(cy1 * w + cx1)
                    if (i == dsf.canonify(cy2 * w + cx2)) valid[i] = false
                }
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = dsf.canonify(y * w + x)
                val x2 = cxArr[i] - 1 - x
                val y2 = cyArr[i] - 1 - y
                if (i != dsf.canonify(y2 * w + x2)) valid[i] = false
            }
        }

        var ret = true
        for (i in 0 until w * h) {
            val ci = dsf.canonify(i)
            val thisOk = valid[ci]
            if (colours != null) colours[i] = if (thisOk) colour[ci] else 0
            ret = ret && thisOk
        }
        return ret
    }

    // ------------------------------------------------------------------
    // Text, `galaxies.c:470-540`.
    // ------------------------------------------------------------------

    override fun canFormatAsTextNow(params: Game.Params): Boolean = true

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val b = s.board
        val out = StringBuilder()
        for (y in 0 until s.sy) {
            for (x in 0 until s.sx) {
                val sp = b.index(x, y)
                when {
                    b.flags[sp] and F_DOT != 0 -> out.append('o')
                    b.type[sp] == S_TILE -> {
                        if (b.flags[sp] and F_TILE_ASSOC != 0) {
                            val dot = sp2dot(s, x, y)
                            if (dot >= 0 && b.flags[dot] and F_DOT != 0) {
                                out.append(if (b.flags[dot] and F_DOT_BLACK != 0) 'B' else 'W')
                            } else {
                                out.append('?')
                            }
                        } else {
                            out.append(' ')
                        }
                    }
                    b.type[sp] == S_VERTEX -> out.append('+')
                    else -> {
                        if (b.flags[sp] and F_EDGE_SET != 0) {
                            out.append(if (isVerticalEdge(x)) '|' else '-')
                        } else {
                            out.append(' ')
                        }
                    }
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // UI and input, `galaxies.c:2682-3076`.
    // ------------------------------------------------------------------

    private fun coordRoundToEdge(x: Float, y: Float, out: IntArray) {
        val xs = floor(x) + 0.5f
        val ys = floor(y) + 0.5f
        val xv = floor(x + 0.5f)
        val yv = floor(y + 0.5f)
        val dx = abs(x - xs)
        val dy = abs(y - ys)
        if (dx > dy) {
            out[0] = 2 * xv.toInt()
            out[1] = 1 + 2 * floor(ys).toInt()
        } else {
            out[0] = 1 + 2 * floor(xs).toInt()
            out[1] = 2 * yv.toInt()
        }
    }

    private fun edgePlacementLegal(state: State, x: Int, y: Int): Boolean {
        val b = state.board
        val sp = b.index(x, y)
        if (b.type[sp] != S_EDGE) return false
        val flags = b.flags[sp] or
            b.flags[b.index(x and 1.inv(), y and 1.inv())] or
            b.flags[b.index((x + 1) and 1.inv(), (y + 1) and 1.inv())]
        return flags and F_DOT == 0
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val n = newState as State
        return n.completed && !n.usedSolve && oldState != null &&
            !(oldState as State).completed
    }

    /** `current_key_label`, `galaxies.c:2846-2872`, as a tested helper. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.curVisible
        ) {
            val sp = s.board.index(u.curX, u.curY)
            if (u.dragging) {
                if (u.curX == u.srcX && u.curY == u.srcY) return "Cancel"
                if (okToAddAssocWithOpposite(s, sp, s.board.index(u.dotX, u.dotY))) {
                    return "Place"
                }
                return if (u.srcX == u.dotX && u.srcY == u.dotY) "Cancel" else "Remove"
            }
            if (s.board.flags[sp] and F_DOT != 0) return "New arrow"
            if (s.board.flags[sp] and F_TILE_ASSOC != 0) return "Move arrow"
            if (s.board.type[sp] == S_EDGE && edgePlacementLegal(s, u.curX, u.curY)) {
                return if (s.board.flags[sp] and F_EDGE_SET != 0) "Clear" else "Edge"
            }
        }
        return ""
    }

    /** `move_cursor` with `wrap = false`, `misc.c:366-391`. */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
        val dx: Int
        val dy: Int
        when (button) {
            Buttons.CURSOR_UP -> {
                dx = 0
                dy = -1
            }
            Buttons.CURSOR_DOWN -> {
                dx = 0
                dy = 1
            }
            Buttons.CURSOR_LEFT -> {
                dx = -1
                dy = 0
            }
            Buttons.CURSOR_RIGHT -> {
                dx = 1
                dy = 0
            }
            else -> return MoveResult.Unused
        }
        val ox = ui.curX - 1
        val oy = ui.curY - 1
        var cx = ox + dx
        var cy = oy + dy
        cx = minOf(maxOf(cx, 0), w - 1)
        cy = minOf(maxOf(cy, 0), h - 1)
        ui.curX = cx + 1
        ui.curY = cy + 1
        if (!ui.curVisible) {
            ui.curVisible = true
            return MoveResult.UiUpdate
        }
        if (cx != ox || cy != oy) return MoveResult.UiUpdate
        return MoveResult.NoEffect
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
        val b = s.board
        val buffer = StringBuilder()

        fun fromCoord(value: Int): Float = (value - tileSize).toFloat() / tileSize
        fun sCoord(value: Int): Int = value * tileSize / 2 + tileSize

        val btn = Buttons.stripModifiers(button)

        if (btn == 'H'.code || btn == 'h'.code) {
            val tmp = dupGame(s)
            solverObvious(tmp)
            return MoveResult.Move(diffGame(s, tmp, false))
        }

        if (btn == Buttons.LEFT_BUTTON) {
            u.curVisible = false
            val xy = IntArray(2)
            coordRoundToEdge(fromCoord(x), fromCoord(y), xy)
            val px = xy[0]
            val py = xy[1]
            if (!inUi(s, px, py)) return MoveResult.Unused
            if (!edgePlacementLegal(s, px, py)) return MoveResult.NoEffect
            return MoveResult.Move("E$px,$py")
        } else if (btn == Buttons.RIGHT_BUTTON) {
            u.curVisible = false
            var px = (2 * fromCoord(x) + 0.5f).toInt()
            var py = (2 * fromCoord(y) + 0.5f).toInt()
            var dot = -1

            var found = false
            for (py1 in py - 1..py + 1) {
                for (px1 in px - 1..px + 1) {
                    if (px1 >= 0 && px1 < s.sx && py1 >= 0 && py1 < s.sy &&
                        x >= sCoord(px1 - 1) && x < sCoord(px1 + 1) &&
                        y >= sCoord(py1 - 1) && y < sCoord(py1 + 1) &&
                        b.flags[b.index(px1, py1)] and F_DOT != 0
                    ) {
                        dot = b.index(px1, py1)
                        u.srcX = px1
                        u.srcY = py1
                        found = true
                        break
                    }
                }
                if (found) break
            }

            if (dot < 0) {
                px = 2 * fromCoordInt(x + tileSize, tileSize) - 1
                py = 2 * fromCoordInt(y + tileSize, tileSize) - 1
                if (px >= 0 && px < s.sx && py >= 0 && py < s.sy) {
                    val sp = b.index(px, py)
                    if (b.flags[sp] and F_TILE_ASSOC != 0) {
                        dot = b.index(b.dotX[sp], b.dotY[sp])
                        u.srcX = px
                        u.srcY = py
                    }
                }
            }

            if (dot >= 0) {
                u.dragging = true
                u.dragX = x
                u.dragY = y
                u.dotX = dot % b.sx
                u.dotY = dot / b.sx
                return MoveResult.UiUpdate
            }
            return MoveResult.NoEffect
        } else if (btn == Buttons.RIGHT_DRAG && u.dragging) {
            u.dragX = x
            u.dragY = y
            return MoveResult.UiUpdate
        } else if (btn == Buttons.RIGHT_RELEASE && u.dragging) {
            val px = 2 * fromCoordInt(x + tileSize, tileSize) - 1
            val py = 2 * fromCoordInt(y + tileSize, tileSize) - 1
            return dropArrow(s, u, px, py, buffer)
        } else if (Buttons.isCursorMove(btn)) {
            return moveCursor(btn, u, s.sx - 2, s.sy - 2).also {
                if (u.dragging) {
                    u.dragX = sCoord(u.curX)
                    u.dragY = sCoord(u.curY)
                }
            }
        } else if (btn == Buttons.CURSOR_SELECT || btn == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            val sp = b.index(u.curX, u.curY)
            if (u.dragging) {
                return dropArrow(s, u, u.curX, u.curY, buffer)
            } else if (b.flags[sp] and F_DOT != 0) {
                u.dragging = true
                u.dragX = sCoord(u.curX)
                u.dragY = sCoord(u.curY)
                u.dotX = u.curX
                u.srcX = u.curX
                u.dotY = u.curY
                u.srcY = u.curY
                return MoveResult.UiUpdate
            } else if (b.flags[sp] and F_TILE_ASSOC != 0) {
                check(b.type[sp] == S_TILE) { "an associated space is not a tile" }
                u.dragging = true
                u.dragX = sCoord(u.curX)
                u.dragY = sCoord(u.curY)
                u.dotX = b.dotX[sp]
                u.dotY = b.dotY[sp]
                u.srcX = u.curX
                u.srcY = u.curY
                return MoveResult.UiUpdate
            } else if (b.type[sp] == S_EDGE && edgePlacementLegal(s, u.curX, u.curY)) {
                return MoveResult.Move("E${u.curX},${u.curY}")
            }
        }

        return MoveResult.Unused
    }

    private fun fromCoordInt(value: Int, tileSize: Int): Int =
        (value - tileSize) / tileSize

    /**
     * The `dropped` label of `interpret_move`, `galaxies.c:2873-3076`.
     * A release and a keyboard select share it.
     */
    private fun dropArrow(
        state: State,
        ui: Ui,
        px: Int,
        py: Int,
        buffer: StringBuilder,
    ): MoveResult {
        val b = state.board
        ui.dragging = false
        if (px == ui.srcX && py == ui.srcY) return MoveResult.UiUpdate

        if ((ui.srcX != ui.dotX || ui.srcY != ui.dotY) &&
            b.flags[b.index(ui.srcX, ui.srcY)] and F_TILE_ASSOC != 0
        ) {
            buffer.append("U${ui.srcX},${ui.srcY}")
        }
        if (inUi(state, px, py)) {
            val sp = b.index(px, py)
            val dot = b.index(ui.dotX, ui.dotY)
            if (okToAddAssocWithOpposite(state, sp, dot)) {
                if (buffer.isNotEmpty()) buffer.append(';')
                buffer.append("A$px,$py,${ui.dotX},${ui.dotY}")
            }
        }
        return if (buffer.isNotEmpty()) MoveResult.Move(buffer.toString()) else MoveResult.UiUpdate
    }

    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    // ------------------------------------------------------------------
    // Geometry, palette, and drawing, `galaxies.c:3415-4120`.
    // ------------------------------------------------------------------

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size((p.w + 2) * tileSize, (p.h + 2) * tileSize)
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(3 * NCOLOURS)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_WHITEBG, COL_BLACKBG)
        for (i in 0 until 3) {
            ret[COL_WHITEDOT * 3 + i] = 1.0f
            ret[COL_WHITEBG * 3 + i] = 1.0f
            ret[COL_BLACKDOT * 3 + i] = 0.0f
            ret[COL_BLACKBG * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.3f
            ret[COL_GRID * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.8f
            ret[COL_EDGE * 3 + i] = 0.0f
            ret[COL_ARROW * 3 + i] = 0.0f
        }
        ret[COL_CURSOR * 3] = minOf(ret[COL_BACKGROUND * 3] * 1.4f, 1.0f)
        ret[COL_CURSOR * 3 + 1] = ret[COL_BACKGROUND * 3] * 0.8f
        ret[COL_CURSOR * 3 + 2] = ret[COL_BACKGROUND * 3] * 0.8f
        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val ds = DrawState(s.w, s.h)
        ds.tileSize = tileSize
        ds.blitter = sink.blitterNew(tileSize, tileSize)
        ds.mirrorBlitter = sink.blitterNew(tileSize, tileSize)
        ds.cursorBlitter = sink.blitterNew(tileSize, tileSize)
        return ds
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        val ds = drawState as DrawState
        sink.blitterFree(ds.cursorBlitter)
        sink.blitterFree(ds.mirrorBlitter)
        sink.blitterFree(ds.blitter)
    }

    private fun dotSize(tileSize: Int): Int = tileSize / 4

    private fun cursorSize(tileSize: Int): Int = dotSize(tileSize)

    private fun edgeThickness(tileSize: Int): Int = maxOf(tileSize / 16, 2)

    private fun coord(value: Int, tileSize: Int): Int = value * tileSize + tileSize

    private fun sCoord(value: Int, tileSize: Int): Int = value * tileSize / 2 + tileSize

    private fun drawArrow(
        sink: DrawSink,
        tileSize: Int,
        cx: Int,
        cy: Int,
        ddx: Int,
        ddy: Int,
        colour: Int,
    ) {
        val sqdist = ddx * ddx + ddy * ddy
        if (sqdist == 0) return
        val vlen = sqrt(sqdist.toDouble()).toFloat()
        val xdx = ddx / vlen
        val xdy = ddy / vlen
        val ydx = -xdy
        val ydy = xdx
        val e1x = cx + (xdx * tileSize / 3).toInt()
        val e1y = cy + (xdy * tileSize / 3).toInt()
        val e2x = cx - (xdx * tileSize / 3).toInt()
        val e2y = cy - (xdy * tileSize / 3).toInt()
        val adx = ((ydx - xdx) * tileSize / 8).toInt()
        val ady = ((ydy - xdy) * tileSize / 8).toInt()
        val adx2 = ((-ydx - xdx) * tileSize / 8).toInt()
        val ady2 = ((-ydy - xdy) * tileSize / 8).toInt()

        sink.line(e1x, e1y, e2x, e2y, colour)
        sink.line(e1x, e1y, e1x + adx, e1y + ady, colour)
        sink.line(e1x, e1y, e1x + adx2, e1y + ady2, colour)
    }

    private fun drawRectOutline(
        sink: DrawSink,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        colour: Int,
    ) {
        val x0 = x
        val x1 = x + w - 1
        val y0 = y
        val y1 = y + h - 1
        sink.poly(
            intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0),
            -1,
            colour,
        )
    }

    private fun drawSquare(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        flags: Long,
        ddx: Int,
        ddy: Int,
    ) {
        val tile = ds.tileSize
        val lx = coord(x, tile)
        val ly = coord(y, tile)
        val dot = dotSize(tile)
        val edge = edgeThickness(tile)
        val cursor = cursorSize(tile)

        sink.clip(lx, ly, tile, tile)

        sink.rect(
            lx,
            ly,
            tile,
            tile,
            when {
                flags and DRAW_WHITE.toLong() != 0L -> COL_WHITEBG
                flags and DRAW_BLACK.toLong() != 0L -> COL_BLACKBG
                else -> COL_BACKGROUND
            },
        )

        val gridcol = if (flags and DRAW_BLACK.toLong() != 0L) COL_BLACKDOT else COL_GRID
        sink.rect(lx, ly, 1, tile, gridcol)
        sink.rect(lx, ly, tile, 1, gridcol)

        if (flags and DRAW_ARROW.toLong() != 0L) {
            drawArrow(
                sink,
                tile,
                lx + tile / 2,
                ly + tile / 2,
                ddx,
                ddy,
                if (flags and DRAW_CURSOR.toLong() != 0L) COL_CURSOR else COL_ARROW,
            )
        } else if (flags and DRAW_CURSOR.toLong() != 0L) {
            drawRectOutline(
                sink,
                lx + tile / 2 - cursor,
                ly + tile / 2 - cursor,
                2 * cursor + 1,
                2 * cursor + 1,
                COL_CURSOR,
            )
        }

        if (flags and DRAW_EDGE_L.toLong() != 0L) {
            sink.rect(lx, ly, edge, tile, COL_EDGE)
        }
        if (flags and DRAW_EDGE_R.toLong() != 0L) {
            sink.rect(lx + tile - edge + 1, ly, edge - 1, tile, COL_EDGE)
        }
        if (flags and DRAW_EDGE_U.toLong() != 0L) {
            sink.rect(lx, ly, tile, edge, COL_EDGE)
        }
        if (flags and DRAW_EDGE_D.toLong() != 0L) {
            sink.rect(lx, ly + tile - edge + 1, tile, edge - 1, COL_EDGE)
        }
        if (flags and DRAW_CORNER_UL.toLong() != 0L) {
            sink.rect(lx, ly, edge, edge, COL_EDGE)
        }
        if (flags and DRAW_CORNER_UR.toLong() != 0L) {
            sink.rect(lx + tile - edge + 1, ly, edge - 1, edge, COL_EDGE)
        }
        if (flags and DRAW_CORNER_DL.toLong() != 0L) {
            sink.rect(lx, ly + tile - edge + 1, edge, edge - 1, COL_EDGE)
        }
        if (flags and DRAW_CORNER_DR.toLong() != 0L) {
            sink.rect(
                lx + tile - edge + 1,
                ly + tile - edge + 1,
                edge - 1,
                edge - 1,
                COL_EDGE,
            )
        }

        for (dy in 0 until 3) {
            for (dx in 0 until 3) {
                val dotval = ((flags shr (DOT_SHIFT_C + DOT_SHIFT_M * (dy * 3 + dx))) and
                    ((1L shl DOT_SHIFT_M) - 1L)).toInt()
                if (dotval != 0) {
                    sink.circle(
                        lx + dx * tile / 2,
                        ly + dy * tile / 2,
                        dot,
                        if (dotval == 1) COL_WHITEDOT else COL_BLACKDOT,
                        COL_BLACKDOT,
                    )
                }
            }
        }

        sink.unclip()
        sink.update(lx, ly, tile, tile)
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
        val b = s.board
        val w = ds.w
        val h = ds.h
        val tile = ds.tileSize
        val cursor = cursorSize(tile)

        if (ds.dragging) {
            sink.blitterLoad(ds.mirrorBlitter, ds.oppX, ds.oppY)
            sink.update(ds.oppX, ds.oppY, tile, tile)
            sink.blitterLoad(ds.blitter, ds.dragX, ds.dragY)
            sink.update(ds.dragX, ds.dragY, tile, tile)
            ds.dragging = false
        }
        if (ds.curVisible) {
            sink.blitterLoad(ds.cursorBlitter, ds.cx, ds.cy)
            sink.update(ds.cx, ds.cy, cursor * 2 + 1, cursor * 2 + 1)
            ds.curVisible = false
        }

        if (!ds.started) {
            val edge = edgeThickness(tile)
            sink.rect(
                tile - edge + 1,
                tile - edge + 1,
                w * tile + edge * 2 - 1,
                h * tile + edge * 2 - 1,
                COL_EDGE,
            )
            sink.update(0, 0, (w + 2) * tile, (h + 2) * tile)
            ds.started = true
        }

        checkComplete(s, null, ds.colourScratch)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var flags = 0L
                var ddx = 0
                var ddy = 0

                if (b.flags[b.index(x * 2, y * 2 + 1)] and F_EDGE_SET != 0) {
                    flags = flags or DRAW_EDGE_L.toLong()
                }
                if (b.flags[b.index(x * 2 + 2, y * 2 + 1)] and F_EDGE_SET != 0) {
                    flags = flags or DRAW_EDGE_R.toLong()
                }
                if (b.flags[b.index(x * 2 + 1, y * 2)] and F_EDGE_SET != 0) {
                    flags = flags or DRAW_EDGE_U.toLong()
                }
                if (b.flags[b.index(x * 2 + 1, y * 2 + 2)] and F_EDGE_SET != 0) {
                    flags = flags or DRAW_EDGE_D.toLong()
                }

                if ((x > 0 && b.flags[b.index(x * 2 - 1, y * 2)] and F_EDGE_SET != 0) ||
                    (y > 0 && b.flags[b.index(x * 2, y * 2 - 1)] and F_EDGE_SET != 0)
                ) {
                    flags = flags or DRAW_CORNER_UL.toLong()
                }
                if ((x + 1 < w &&
                        b.flags[b.index(x * 2 + 3, y * 2)] and F_EDGE_SET != 0) ||
                    (y > 0 &&
                        b.flags[b.index(x * 2 + 2, y * 2 - 1)] and F_EDGE_SET != 0)
                ) {
                    flags = flags or DRAW_CORNER_UR.toLong()
                }
                if ((x > 0 &&
                        b.flags[b.index(x * 2 - 1, y * 2 + 2)] and F_EDGE_SET != 0) ||
                    (y + 1 < h &&
                        b.flags[b.index(x * 2, y * 2 + 3)] and F_EDGE_SET != 0)
                ) {
                    flags = flags or DRAW_CORNER_DL.toLong()
                }
                if ((x + 1 < w &&
                        b.flags[b.index(x * 2 + 3, y * 2 + 2)] and F_EDGE_SET != 0) ||
                    (y + 1 < h &&
                        b.flags[b.index(x * 2 + 2, y * 2 + 3)] and F_EDGE_SET != 0)
                ) {
                    flags = flags or DRAW_CORNER_DR.toLong()
                }

                val sp = b.index(x * 2 + 1, y * 2 + 1)
                val opposite = if (b.flags[sp] and F_TILE_ASSOC != 0) {
                    tileOpposite(s, sp)
                } else {
                    -1
                }
                val colour = ds.colourScratch[y * w + x]
                if (colour != 0) {
                    flags = flags or
                        (if (colour == 2) DRAW_BLACK.toLong() else DRAW_WHITE.toLong())
                }

                if (b.flags[sp] and F_TILE_ASSOC != 0 && colour == 0) {
                    if (u.dragging && u.srcX == x * 2 + 1 && u.srcY == y * 2 + 1) {
                        // The tile is the source of the drag.
                    } else if (u.dragging && opposite >= 0 &&
                        u.srcX == opposite % b.sx && u.srcY == opposite / b.sx
                    ) {
                        // The opposite tile is the source of the drag.
                    } else if (b.dotY[sp] != y * 2 + 1 || b.dotX[sp] != x * 2 + 1) {
                        flags = flags or DRAW_ARROW.toLong()
                        ddy = b.dotY[sp] - (y * 2 + 1)
                        ddx = b.dotX[sp] - (x * 2 + 1)
                    }
                }

                for (dy in 0 until 3) {
                    for (dx in 0 until 3) {
                        val dotSpace = b.index(x * 2 + dx, y * 2 + dy)
                        if (b.flags[dotSpace] and F_DOT != 0) {
                            val dotval = if (b.flags[dotSpace] and F_DOT_BLACK != 0) {
                                DOT_BLACK
                            } else {
                                DOT_WHITE
                            }
                            flags = flags or
                                (dotval shl (DOT_SHIFT_C + DOT_SHIFT_M * (dy * 3 + dx)))
                        }
                    }
                }

                if (u.curVisible &&
                    u.curX == x * 2 + 1 && u.curY == y * 2 + 1 &&
                    b.flags[b.index(x * 2 + 1, y * 2 + 1)] and F_DOT == 0
                ) {
                    flags = flags or DRAW_CURSOR.toLong()
                }

                if (ds.grid[y * w + x] != flags ||
                    ds.dx[y * w + x] != ddx ||
                    ds.dy[y * w + x] != ddy
                ) {
                    drawSquare(sink, ds, x, y, flags, ddx, ddy)
                    ds.grid[y * w + x] = flags
                    ds.dx[y * w + x] = ddx
                    ds.dy[y * w + x] = ddy
                }
            }
        }

        if (u.curVisible) {
            val sp = b.index(u.curX, u.curY)
            ds.curVisible = true
            ds.cx = sCoord(u.curX, tile) - cursor
            ds.cy = sCoord(u.curY, tile) - cursor
            sink.blitterSave(ds.cursorBlitter, ds.cx, ds.cy)
            if (b.flags[sp] and F_DOT != 0) {
                sink.circle(
                    sCoord(u.curX, tile),
                    sCoord(u.curY, tile),
                    dotSize(tile),
                    COL_CURSOR,
                    COL_BLACKDOT,
                )
            } else if (b.type[sp] != S_TILE) {
                val dx = if (u.curX % 2 != 0) cursor else cursor / 3
                val dy = if (u.curY % 2 != 0) cursor else cursor / 3
                val x1 = sCoord(u.curX, tile) - dx
                val y1 = sCoord(u.curY, tile) - dy
                sink.rect(x1, y1, dx * 2 + 1, dy * 2 + 1, COL_CURSOR)
            }
            sink.update(ds.cx, ds.cy, cursor * 2 + 1, cursor * 2 + 1)
        }

        if (u.dragging) {
            ds.dragging = true
            ds.dragX = u.dragX - tile / 2
            ds.dragY = u.dragY - tile / 2
            val oppX = 2 * sCoord(u.dotX, tile) - u.dragX
            val oppY = 2 * sCoord(u.dotY, tile) - u.dragY
            ds.oppX = oppX - tile / 2
            ds.oppY = oppY - tile / 2

            sink.blitterSave(ds.blitter, ds.dragX, ds.dragY)
            sink.clip(ds.dragX, ds.dragY, tile, tile)
            drawArrow(
                sink,
                tile,
                u.dragX,
                u.dragY,
                sCoord(u.dotX, tile) - u.dragX,
                sCoord(u.dotY, tile) - u.dragY,
                COL_ARROW,
            )
            sink.unclip()
            sink.update(ds.dragX, ds.dragY, tile, tile)

            sink.blitterSave(ds.mirrorBlitter, ds.oppX, ds.oppY)
            sink.clip(ds.oppX, ds.oppY, tile, tile)
            drawArrow(
                sink,
                tile,
                oppX,
                oppY,
                sCoord(u.dotX, tile) - oppX,
                sCoord(u.dotY, tile) - oppY,
                COL_ARROW,
            )
            sink.unclip()
            sink.update(ds.oppX, ds.oppY, tile, tile)
        }
    }

    /** `game_flash_length`, `galaxies.c:3890-3899`; the seam has no clock. */
    @Suppress("unused")
    private fun flashLength(oldState: State, newState: State): Float =
        if (!oldState.completed && newState.completed && !newState.usedSolve) {
            3 * FLASH_TIME
        } else {
            0.0f
        }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        val s = state as State
        if (!u.curVisible) return null
        val b = s.board
        val sp = b.index(u.curX, u.curY)
        val cursor = cursorSize(tileSize)
        val dot = dotSize(tileSize)
        return when {
            b.flags[sp] and F_DOT != 0 -> Game.CursorRect(
                sCoord(u.curX, tileSize) - dot,
                sCoord(u.curY, tileSize) - dot,
                2 * dot + 1,
                2 * dot + 1,
            )
            b.type[sp] != S_TILE -> {
                val dx = if (u.curX % 2 != 0) cursor else cursor / 3
                val dy = if (u.curY % 2 != 0) cursor else cursor / 3
                Game.CursorRect(
                    sCoord(u.curX, tileSize) - dx,
                    sCoord(u.curY, tileSize) - dy,
                    dx * 2 + 1,
                    dy * 2 + 1,
                )
            }
            else -> Game.CursorRect(
                sCoord(u.curX, tileSize) - cursor,
                sCoord(u.curY, tileSize) - cursor,
                2 * cursor + 1,
                2 * cursor + 1,
            )
        }
    }

    // ------------------------------------------------------------------
    // C helpers.
    // ------------------------------------------------------------------

    /** C `atoi` from [start], saturating on overflow. */
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
            val digit = (text[i] - '0').toLong()
            if (!overflow) {
                if (value > (Int.MAX_VALUE.toLong() + 1L - digit) / 10L) {
                    overflow = true
                } else {
                    value = value * 10L + digit
                }
            }
            i++
        }
        if (overflow) return if (negative) Int.MIN_VALUE else Int.MAX_VALUE
        val signed = if (negative) -value else value
        return signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
