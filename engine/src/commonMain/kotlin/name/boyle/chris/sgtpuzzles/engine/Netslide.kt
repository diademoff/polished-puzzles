package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * A port of upstream `netslide.c`: a cross between Net and Sixteen.
 *
 * The board is a network of wires that is complete before the shuffle.
 * A move slides one whole row or column of tiles.
 *
 * The description carries one hex digit per cell (bit 0 right, bit 1
 * up, bit 2 left, bit 3 down) followed by `v` and `h` barrier markers.
 * The auxiliary answer is the unshuffled board as an `S` move, so every
 * generated game can be solved in one step. The save file has an
 * `AUXINFO` record and no `UI` record.
 *
 * The game has no animation and no completion flash. `docs/PARITY.md`
 * allows animation differences, and the app redraws at once after a
 * move. The slide and flash times cannot be observed through the
 * oracle, so they are not ported.
 *
 * Two intentional deviations from C. A NaN barrier probability is
 * rejected by [validateParams]. The C `validate_params` accepts it and
 * then aborts at `netslide.c:629` on the `(int)(NaN * count)` cast. The
 * app compiles with assertions on, so the C build would crash on a
 * config dialog value of `nan`. D-005 records that. A board above
 * [MAX_AREA] cells is refused at generation, before any RNG draw,
 * with a fixed message. D-026 records that.
 */
object Netslide : Game {

    private const val PREFERRED_TILE_SIZE = 48

    /**
     * D-026: the measured generation cap, 300x300. Generation is linear
     * in the area and has no cliff, so this is a policy cap that keeps
     * a phone generation under five seconds.
     */
    internal const val MAX_AREA = 90_000

    // Direction and other bitfields.
    private const val R = 0x01
    private const val U = 0x02
    private const val L = 0x04
    private const val D = 0x08
    private const val FLASHING = 0x10
    private const val ACTIVE = 0x20

    // Corner flags live in the high nibble of the barriers array.
    private const val RU = 0x10
    private const val UL = 0x20
    private const val LD = 0x40
    private const val DR = 0x80

    private const val COL_BACKGROUND = 0
    private const val COL_FLASHING = 1
    private const val COL_BORDER = 2
    private const val COL_WIRE = 3
    private const val COL_ENDPOINT = 4
    private const val COL_POWERED = 5
    private const val COL_BARRIER = 6
    private const val COL_LOWLIGHT = 7
    private const val COL_TEXT = 8

    private val DIRECTIONS = intArrayOf(R, U, L, D)

    private const val HEX = "0123456789abcdef"

    /** `A(x)`: rotate the four direction bits anticlockwise. */
    private fun rotateAntiClockwise(x: Int): Int =
        ((x and 0x07) shl 1) or ((x and 0x08) ushr 3)

    /** `C(x)`: rotate the four direction bits clockwise. */
    private fun rotateClockwise(x: Int): Int =
        ((x and 0x0E) ushr 1) or ((x and 0x01) shl 3)

    /** `F(x)`: swap the right/left and up/down bit pairs. */
    private fun flip(x: Int): Int =
        ((x and 0x0C) ushr 2) or ((x and 0x03) shl 2)

    /** `X(dir)`: the horizontal displacement, or zero. */
    private fun dx(dir: Int): Int = when (dir) {
        R -> +1
        L -> -1
        else -> 0
    }

    /** `Y(dir)`: the vertical displacement, or zero. */
    private fun dy(dir: Int): Int = when (dir) {
        D -> +1
        U -> -1
        else -> 0
    }

    /** `COUNT(x)`: the number of set direction bits. */
    private fun countBits(x: Int): Int =
        ((x and 0x08) ushr 3) + ((x and 0x04) ushr 2) +
            ((x and 0x02) ushr 1) + (x and 0x01)

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val wrapping: Boolean,
        val barrierProbability: Float,
        val moveTarget: Int,
    ) : Game.Params {
        override fun copy(): Game.Params =
            Params(width, height, wrapping, barrierProbability, moveTarget)
    }

    /**
     * `struct game_state`. The twelve scalar fields and the two arrays
     * are all part of the state. `lastMoveRow` and `lastMoveCol` are the
     * 0-based line of the last move, and `lastMoveDir` is its `d`.
     */
    class State(
        val width: Int,
        val height: Int,
        val cx: Int,
        val cy: Int,
        val wrapping: Boolean,
        val completed: Int,
        val usedSolve: Boolean,
        val moveCount: Int,
        val moveTarget: Int,
        val lastMoveRow: Int,
        val lastMoveCol: Int,
        val lastMoveDir: Int,
        val tiles: IntArray,
        val barriers: IntArray,
    ) : Game.State {

        val count: Int get() = width * height

        fun tileAt(x: Int, y: Int): Int = tiles[y * width + x]

        fun barrierAt(x: Int, y: Int): Int = barriers[y * width + x]

        override fun copy(): Game.State = State(
            width = width,
            height = height,
            cx = cx,
            cy = cy,
            wrapping = wrapping,
            completed = completed,
            usedSolve = usedSolve,
            moveCount = moveCount,
            moveTarget = moveTarget,
            lastMoveRow = lastMoveRow,
            lastMoveCol = lastMoveCol,
            lastMoveDir = lastMoveDir,
            tiles = tiles.copyOf(),
            barriers = barriers.copyOf(),
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                cx == other.cx &&
                cy == other.cy &&
                wrapping == other.wrapping &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                moveCount == other.moveCount &&
                moveTarget == other.moveTarget &&
                lastMoveRow == other.lastMoveRow &&
                lastMoveCol == other.lastMoveCol &&
                lastMoveDir == other.lastMoveDir &&
                tiles.contentEquals(other.tiles) &&
                barriers.contentEquals(other.barriers)

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + cx
            result = 31 * result + cy
            result = 31 * result + wrapping.hashCode()
            result = 31 * result + completed
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + moveCount
            result = 31 * result + moveTarget
            result = 31 * result + lastMoveRow
            result = 31 * result + lastMoveCol
            result = 31 * result + lastMoveDir
            result = 31 * result + tiles.contentHashCode()
            result = 31 * result + barriers.contentHashCode()
            return result
        }
    }

    /** `struct game_ui`: the ring position and the cursor switch. */
    class Ui : Game.Ui {
        var curX = 0
        var curY = -1
        var cursorVisible = false
    }

    /**
     * `struct game_drawstate`: the cache of the last drawn tile byte per
     * cell, with `0xFF` for "never drawn", and the cursor the cache was
     * drawn with. There is no blitter.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
        val tileSize: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val visible: IntArray = IntArray(width * height) { 0xFF }
        var curX: Int = -1
        var curY: Int = -1
    }

    /** The sort key of `struct xyd`: x, then y, then direction. */
    private data class Xyd(val x: Int, val y: Int, val direction: Int)

    private val XYD_COMPARATOR = Comparator<Xyd> { a, b ->
        when {
            a.x < b.x -> -1
            a.x > b.x -> +1
            a.y < b.y -> -1
            a.y > b.y -> +1
            a.direction < b.direction -> -1
            a.direction > b.direction -> +1
            else -> 0
        }
    }

    override val name: String = "Netslide"
    override val backendName: String = "netslide"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = false

    // `netslide.c` has no `SMALL_SCREEN` branch, so the app and the
    // oracle expose the same nine presets. The parameter text is stored
    // verbatim because the short form drops `b`, which would turn the
    // "Easy" preset into the "Medium" one.
    private val presetValues = listOf(
        Preset("3x3 Easy", "3x3b1"),
        Preset("3x3 Medium", "3x3"),
        Preset("3x3 Hard", "3x3w"),
        Preset("4x4 Easy", "4x4b1"),
        Preset("4x4 Medium", "4x4"),
        Preset("4x4 Hard", "4x4w"),
        Preset("5x5 Easy", "5x5b1"),
        Preset("5x5 Medium", "5x5"),
        Preset("5x5 Hard", "5x5w"),
    )

    override fun defaultParams(): Game.Params =
        Params(3, 3, wrapping = false, barrierProbability = 1.0f, moveTarget = 0)

    override fun presets(): List<Preset> = presetValues

    /**
     * `decode_params`. Every field is overwritten. The width `atoi`
     * starts at the beginning of the string, and the digit skip also
     * starts there, so a leading space or sign stops the skip and the
     * `x` is not seen. The `w`, `b`, and `m` parses are inside the `x`
     * branch, so `3w` and `3m5` keep the default shape.
     */
    override fun decodeParams(text: String): Game.Params {
        var index = 0
        val width = atoi(text, index)
        while (index < text.length && text[index] in '0'..'9') index++
        var height = width
        var wrapping = false
        var barrierProbability = 0.0f
        var moveTarget = 0
        if (index < text.length && text[index] == 'x') {
            index++
            height = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
            wrapping = index < text.length && text[index] == 'w'
            if (wrapping) index++
            if (index < text.length && text[index] == 'b') {
                index++
                // C passes `p` to `strtod` and discards the end pointer,
                // so the digit/dot skip starts at the number, not after
                // it. This is why `3x3b1e-1m5` does not see a target.
                val start = index
                barrierProbability = strtod(text, index).toFloat()
                index = start
                while (index < text.length &&
                    (text[index] in '0'..'9' || text[index] == '.')
                ) {
                    index++
                }
            }
            if (index < text.length && text[index] == 'm') {
                index++
                moveTarget = atoi(text, index)
            }
        }
        return Params(width, height, wrapping, barrierProbability, moveTarget)
    }

    /**
     * `encode_params`: `%dx%d`, then `w`, then `b%g` only in the full
     * form, then `m%d`. The barrier rate is dropped from the short form,
     * so a game id of a `3x3b1` board is `3x3:...`.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.width).append('x').append(p.height)
        if (p.wrapping) out.append('w')
        if (full && p.barrierProbability != 0.0f) {
            out.append('b').append(formatG(p.barrierProbability))
        }
        if (p.moveTarget != 0) out.append('m').append(p.moveTarget)
        return out.toString()
    }

    /**
     * `validate_params`, in the C order. The overflow test stays
     * `w > INT_MAX / h`, so `46340x46340` is accepted. That board has
     * 2,147,395,600 cells: about 4.3 GiB of arrays in C and about 17 GB
     * with the two `IntArray`s the port uses, so [newDesc] refuses an
     * area above [MAX_AREA] (D-026). A NaN
     * rate passes the
     * two range tests in C and then aborts, so the port rejects it here
     * with this clear message (D-005).
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width <= 1 || p.height <= 1) {
            return "Width and height must both be greater than one"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.barrierProbability.isNaN()) {
            return "Barrier probability must be a number"
        }
        if (p.barrierProbability < 0.0f) {
            return "Barrier probability may not be negative"
        }
        if (p.barrierProbability > 1.0f) {
            return "Barrier probability may not be greater than 1"
        }
        if (p.moveTarget < 0) {
            return "Number of shuffling moves may not be negative"
        }
        return null
    }

    /** `game_configure` (`netslide.c:262-297`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Bool("Walls wrap around", p.wrapping),
            ConfigItem.Text("Barrier probability", cFloatG(p.barrierProbability)),
            ConfigItem.Text("Number of shuffling moves", p.moveTarget.toString()),
        )
    }

    /** `custom_params` (`netslide.c:299-310`), with `atof` for the barrier. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Bool).value,
            cAtof((items[3] as ConfigItem.Text).value),
            atoi((items[4] as ConfigItem.Text).value, 0),
        )

    /**
     * `new_game_desc`. Phase 1 builds the spanning tree from the centre
     * with one `random_upto(count)` draw per connected cell, so exactly
     * `w*h-1` draws. The auxiliary answer is the unshuffled board, taken
     * before the shuffle. Phase 3 shuffles with two draws per attempt,
     * including the attempts the retry rule rejects. Phase 4 draws the
     * barriers from the sorted list after the shuffle.
     *
     * D-026 rejects an area above [MAX_AREA] before the first draw.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        require(p.width.toLong() * p.height <= MAX_AREA) {
            Game.AREA_TOO_LARGE
        }
        val w = p.width
        val h = p.height
        val n = w * h
        val cx = w / 2
        val cy = h / 2
        val tiles = IntArray(n)
        val barriers = IntArray(n)

        // Phase 1: connect every cell to the centre without loops and
        // without full crosses.
        val possibilities = Tree234.sorted(XYD_COMPARATOR)
        if (cx + 1 < w) possibilities.add(Xyd(cx, cy, R))
        if (cy - 1 >= 0) possibilities.add(Xyd(cx, cy, U))
        if (cx - 1 >= 0) possibilities.add(Xyd(cx, cy, L))
        if (cy + 1 < h) possibilities.add(Xyd(cx, cy, D))

        while (possibilities.count > 0) {
            val index = random.upto(possibilities.count.toLong()).toInt()
            val xyd = possibilities.deletePos(index)!!
            val x1 = xyd.x
            val y1 = xyd.y
            val d1 = xyd.direction
            val x2 = offsetX(x1, d1, w)
            val y2 = offsetY(y1, d1, h)
            val d2 = flip(d1)

            tiles[y1 * w + x1] = tiles[y1 * w + x1] or d1
            check(tiles[y2 * w + x2] == 0) { "the tree reached a used cell" }
            tiles[y2 * w + x2] = tiles[y2 * w + x2] or d2

            // A T-piece loses its last possibility, so no full cross.
            if (countBits(tiles[y1 * w + x1]) == 3) {
                findAndDelete(possibilities, Xyd(x1, y1, 0x0F xor tiles[y1 * w + x1]))
            }

            // Remove every other possibility that points into the new
            // cell, which would make a loop.
            for (d in DIRECTIONS) {
                val x3 = offsetX(x2, d, w)
                val y3 = offsetY(y2, d, h)
                findAndDelete(possibilities, Xyd(x3, y3, flip(d)))
            }

            // Add the new frontier entries.
            for (d in DIRECTIONS) {
                if (d == d2) continue
                if (!p.wrapping) {
                    if (d == U && y2 == 0) continue
                    if (d == D && y2 == h - 1) continue
                    if (d == L && x2 == 0) continue
                    if (d == R && x2 == w - 1) continue
                }
                val x3 = offsetX(x2, d, w)
                val y3 = offsetY(y2, d, h)
                if (tiles[y3 * w + x3] != 0) continue
                possibilities.add(Xyd(x2, y2, d))
            }
        }

        // The barrier list is built from the *unshuffled* tiles, then
        // the barriers are chosen after the shuffle. Building the list
        // after the shuffle changes every barrier position.
        val barrierTree = Tree234.sorted(XYD_COMPARATOR)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((tiles[y * w + x] and R) == 0 && (p.wrapping || x < w - 1)) {
                    barrierTree.add(Xyd(x, y, R))
                }
                if ((tiles[y * w + x] and D) == 0 && (p.wrapping || y < h - 1)) {
                    barrierTree.add(Xyd(x, y, D))
                }
            }
        }

        // Phase 2: the unshuffled board, in the solve-move format.
        val aux = buildString(n + 1) {
            append('S')
            for (tile in tiles) append(HEX[tile and 0xF])
        }

        // Phase 3: shuffle. Every attempt costs two draws, including a
        // rejected attempt, because the direction is drawn first.
        val cols = w - 1
        val rows = h - 1
        var moves = p.moveTarget
        if (moves == 0) moves = cols * rows * 2
        var prevDir = -1
        var prevRowCol = -1
        var repeats = 0
        var i = 0
        while (i < moves) {
            cancel.check()
            val dir = random.upto(4).toInt()
            val rowCol: Int
            if (dir % 2 == 0) {
                var col = random.upto(cols.toLong()).toInt()
                if (col >= cx) col += 1
                if (col == prevRowCol) {
                    if (dir == 2 - prevDir) continue
                    if (dir == prevDir && (repeats + 1) * 2 > h) continue
                }
                slideColInt(w, h, tiles, 1 - dir, col)
                rowCol = col
            } else {
                var row = random.upto(rows.toLong()).toInt()
                if (row >= cy) row += 1
                if (row == prevRowCol) {
                    if (dir == 4 - prevDir) continue
                    if (dir == prevDir && (repeats + 1) * 2 > w) continue
                }
                slideRowInt(w, h, tiles, 2 - dir, row)
                rowCol = row
            }
            if (dir == prevDir && rowCol == prevRowCol) {
                repeats++
            } else {
                repeats = 1
            }
            prevDir = dir
            prevRowCol = rowCol
            i++
        }

        // Phase 4: choose barriers, after the shuffle, from the sorted
        // list built before it.
        var nbarriers = (p.barrierProbability * barrierTree.count).toInt()
        check(nbarriers >= 0 && nbarriers <= barrierTree.count) {
            "barrier count out of range"
        }
        while (nbarriers > 0) {
            val index = random.upto(barrierTree.count.toLong()).toInt()
            val xyd = barrierTree.deletePos(index)!!
            val x1 = xyd.x
            val y1 = xyd.y
            val d1 = xyd.direction
            val x2 = offsetX(x1, d1, w)
            val y2 = offsetY(y1, d1, h)
            barriers[y1 * w + x1] = barriers[y1 * w + x1] or d1
            barriers[y2 * w + x2] = barriers[y2 * w + x2] or flip(d1)
            nbarriers--
        }

        // Phase 5: encode.
        val desc = StringBuilder()
        for (y in 0 until h) {
            for (x in 0 until w) {
                desc.append(HEX[tiles[y * w + x]])
                if ((p.wrapping || x < w - 1) && (barriers[y * w + x] and R) != 0) {
                    desc.append('v')
                }
                if ((p.wrapping || y < h - 1) && (barriers[y * w + x] and D) != 0) {
                    desc.append('h')
                }
            }
        }
        return Description(desc.toString(), aux)
    }

    /** `validate_desc`: one hex digit per cell, then any `h`/`v` markers. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val area = p.width * p.height
        var index = 0
        for (i in 0 until area) {
            if (index >= desc.length) {
                return "Game description shorter than expected"
            }
            if (!isHexDigit(desc[index])) {
                return "Invalid character in game description"
            }
            index++
            while (index < desc.length && (desc[index] == 'h' || desc[index] == 'v')) {
                index++
            }
        }
        if (index < desc.length) {
            return "Game description longer than expected"
        }
        return null
    }

    /**
     * `new_game`. Multiple markers OR together and a wrapping marker
     * lands on the opposite edge too. A non-wrapping board gets its
     * border barriers, then the high-nibble corner flags are derived
     * from the low nibble.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.width
        val h = p.height
        val n = w * h
        val tiles = IntArray(n)
        val barriers = IntArray(n)

        var index = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                check(index < desc.length) { "description is shorter than the board" }
                tiles[y * w + x] = hexValue(desc[index])
                index++
                while (index < desc.length && (desc[index] == 'h' || desc[index] == 'v')) {
                    val d1 = if (desc[index] == 'v') R else D
                    val x2 = offsetX(x, d1, w)
                    val y2 = offsetY(y, d1, h)
                    barriers[y * w + x] = barriers[y * w + x] or d1
                    barriers[y2 * w + x2] = barriers[y2 * w + x2] or flip(d1)
                    index++
                }
            }
        }

        if (!p.wrapping) {
            for (x in 0 until w) {
                barriers[x] = barriers[x] or U
                barriers[(h - 1) * w + x] = barriers[(h - 1) * w + x] or D
            }
            for (y in 0 until h) {
                barriers[y * w] = barriers[y * w] or L
                barriers[y * w + (w - 1)] = barriers[y * w + (w - 1)] or R
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                for (dir in DIRECTIONS) {
                    val dir2 = rotateAntiClockwise(dir)
                    if ((barriers[y * w + x] and dir) == 0) continue

                    var corner = (barriers[y * w + x] and dir2) != 0
                    val x1 = x + dx(dir)
                    val y1 = y + dy(dir)
                    if (x1 in 0 until w && y1 in 0 until h &&
                        (barriers[y1 * w + x1] and dir2) != 0
                    ) {
                        corner = true
                    }
                    val x2 = x + dx(dir2)
                    val y2 = y + dy(dir2)
                    if (x2 in 0 until w && y2 in 0 until h &&
                        (barriers[y2 * w + x2] and dir) != 0
                    ) {
                        corner = true
                    }

                    if (corner) {
                        barriers[y * w + x] = barriers[y * w + x] or (dir shl 4)
                        if (x1 in 0 until w && y1 in 0 until h) {
                            barriers[y1 * w + x1] =
                                barriers[y1 * w + x1] or
                                (rotateAntiClockwise(dir) shl 4)
                        }
                        if (x2 in 0 until w && y2 in 0 until h) {
                            barriers[y2 * w + x2] =
                                barriers[y2 * w + x2] or
                                (rotateClockwise(dir) shl 4)
                        }
                        val x3 = x + dx(dir) + dx(dir2)
                        val y3 = y + dy(dir) + dy(dir2)
                        if (x3 in 0 until w && y3 in 0 until h) {
                            barriers[y3 * w + x3] =
                                barriers[y3 * w + x3] or (flip(dir) shl 4)
                        }
                    }
                }
            }
        }

        return State(
            width = w,
            height = h,
            cx = w / 2,
            cy = h / 2,
            wrapping = p.wrapping,
            completed = 0,
            usedSolve = false,
            moveCount = 0,
            moveTarget = p.moveTarget,
            lastMoveRow = -1,
            lastMoveCol = -1,
            lastMoveDir = 0,
            tiles = tiles,
            barriers = barriers,
        )
    }

    /**
     * `solve_game`: the stored auxiliary answer is the whole solution.
     * There is no search, and a game without the answer cannot solve.
     */
    /**
     * `solve_game` (`netslide.c:886-898`): the answer is the stored
     * `AUXINFO`, and a save without it has no known solution.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? = auxInfo

    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        if (auxInfo != null) {
            SolveOutcome(auxInfo, null)
        } else {
            SolveOutcome(null, "Solution not known for this puzzle")
        }

    /**
     * `execute_move`. `R<row>,<d>` accepts `-w <= d <= w` and
     * `C<col>,<d>` accepts `-h <= d <= h`, and `d = 0` is rejected. The
     * slide uses only the sign and the modulo step, so `|d| > 1` is a
     * scramble, not a rotation, and `|d| = w` is an identity that still
     * counts a move and tests completion.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State

        if (move.isEmpty()) return null

        if (move[0] == 'C' || move[0] == 'R') {
            val pair = scanTwoInts(move, 1) ?: return null
            val c = pair.first
            val d = pair.second
            val isCol = move[0] == 'C'
            val lineCount = if (isCol) from.width else from.height
            val limit = if (isCol) from.height else from.width
            if (c < 0 || c >= lineCount || d > limit || d < -limit || d == 0) {
                return null
            }

            val tiles = from.tiles.copyOf()
            if (isCol) {
                slideColInt(from.width, from.height, tiles, d, c)
            } else {
                slideRowInt(from.width, from.height, tiles, d, c)
            }
            var ret = from.withMove(
                tiles = tiles,
                completed = from.completed,
                moveCount = from.moveCount + 1,
                lastMoveRow = if (isCol) -1 else c,
                lastMoveCol = if (isCol) c else -1,
                lastMoveDir = d,
            )

            if (ret.completed == 0) {
                val active = computeActive(ret, -1, -1)
                if (active.all { it != 0 }) {
                    ret = ret.withMove(
                        tiles = ret.tiles,
                        completed = ret.moveCount,
                        moveCount = ret.moveCount,
                        lastMoveRow = ret.lastMoveRow,
                        lastMoveCol = ret.lastMoveCol,
                        lastMoveDir = ret.lastMoveDir,
                    )
                }
            }
            return ret
        }

        if (move[0] == 'S' && move.length == from.width * from.height + 1) {
            val tiles = from.tiles.copyOf()
            for (i in 0 until from.width * from.height) {
                val value = hexValueOrNull(move[i + 1]) ?: return null
                tiles[i] = value
            }
            return from.withMove(
                tiles = tiles,
                completed = 1,
                moveCount = 1,
                lastMoveRow = from.lastMoveRow,
                lastMoveCol = from.lastMoveCol,
                lastMoveDir = from.lastMoveDir,
                usedSolve = true,
            )
        }

        return null
    }

    /** A copy of this state with the given fields replaced. */
    private fun State.withMove(
        tiles: IntArray,
        completed: Int,
        moveCount: Int,
        lastMoveRow: Int,
        lastMoveCol: Int,
        lastMoveDir: Int,
        usedSolve: Boolean = this.usedSolve,
    ): State = State(
        width = width,
        height = height,
        cx = cx,
        cy = cy,
        wrapping = wrapping,
        completed = completed,
        usedSolve = usedSolve,
        moveCount = moveCount,
        moveTarget = moveTarget,
        lastMoveRow = lastMoveRow,
        lastMoveCol = lastMoveCol,
        lastMoveDir = lastMoveDir,
        tiles = tiles,
        barriers = barriers,
    )

    /** `game_status`: win or open, never a loss. */
    override fun status(state: Game.State): Int =
        if ((state as State).completed != 0) 1 else 0

    /**
     * The status bar text. A solve counts from the solve move, a
     * completed game freezes the count and adds the prefix, and a
     * target game adds its suffix.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val n = s.width * s.height
        val active = computeActive(s, -1, -1).count { it != 0 }

        val out = StringBuilder()
        if (s.usedSolve) {
            out.append("Moves since auto-solve: ").append(s.moveCount - s.completed)
        } else {
            if (s.completed != 0) out.append("COMPLETED! ")
            out.append("Moves: ").append(if (s.completed != 0) s.completed else s.moveCount)
        }
        if (s.moveTarget != 0) {
            out.append(" (target ").append(s.moveTarget).append(')')
        }
        out.append(" Active: ").append(active).append('/').append(n)
        return out.toString()
    }

    /** No text form exists; `can_format_as_text_ever` is false in C. */
    override fun textFormat(state: Game.State): String = ""

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

    /** `game_request_keys` is NULL, so the midend default applies. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(
            tileSize * (p.width + 2) + 1,
            tileSize * (p.height + 2) + 1,
        )
    }

    /** `new_ui`. C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorVisible = visible
    }

    /** `game_get_cursor_location`; no clamp, as C has none. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorVisible) return null
        return Game.CursorRect(
            tileSize * u.curX + tileSize,
            tileSize * u.curY + tileSize,
            tileSize,
            tileSize,
        )
    }

    /**
     * `compute_active`: a BFS from the centre over connected,
     * unbarriered edges. A cell in the moving row or column is never
     * marked, because [movingRow] and [movingCol] are the animated line.
     * The result holds [ACTIVE] for a reachable cell and 0 otherwise.
     */
    fun computeActive(state: State, movingRow: Int, movingCol: Int): IntArray {
        val w = state.width
        val h = state.height
        val active = IntArray(w * h)
        active[state.cy * w + state.cx] = ACTIVE
        val todo = Tree234.sorted(XYD_COMPARATOR)
        todo.add(Xyd(state.cx, state.cy, 0))

        while (todo.count > 0) {
            val item = todo.deletePos(0)!!
            val x1 = item.x
            val y1 = item.y
            for (d1 in DIRECTIONS) {
                val x2 = offsetX(x1, d1, w)
                val y2 = offsetY(y1, d1, h)
                val d2 = flip(d1)
                if ((x2 != movingCol && y2 != movingRow) &&
                    (state.tileAt(x1, y1) and d1) != 0 &&
                    (state.tileAt(x2, y2) and d2) != 0 &&
                    (state.barrierAt(x1, y1) and d1) == 0 &&
                    active[y2 * w + x2] == 0
                ) {
                    active[y2 * w + x2] = ACTIVE
                    todo.add(Xyd(x2, y2, 0))
                }
            }
        }
        return active
    }

    /**
     * `game_colours`: three plain multiplications of the background,
     * not `game_mkhighlight`.
     */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(9 * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) ret[COL_WIRE * 3 + i] = 0.0f
        for (i in 0 until 3) ret[COL_POWERED * 3 + i] = 0.0f
        ret[COL_POWERED * 3 + 1] = 1.0f
        ret[COL_POWERED * 3 + 2] = 1.0f
        for (i in 0 until 3) ret[COL_BARRIER * 3 + i] = 0.0f
        ret[COL_BARRIER * 3 + 0] = 1.0f
        for (i in 0 until 3) ret[COL_ENDPOINT * 3 + i] = 0.0f
        ret[COL_ENDPOINT * 3 + 2] = 1.0f
        for (i in 0 until 3) ret[COL_BORDER * 3 + i] = 0.5f * ret[COL_BACKGROUND * 3 + i]
        for (i in 0 until 3) ret[COL_FLASHING * 3 + i] = 0.75f * ret[COL_BACKGROUND * 3 + i]
        for (i in 0 until 3) ret[COL_LOWLIGHT * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.8f
        for (i in 0 until 3) ret[COL_TEXT * 3 + i] = 0.0f
        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height, tileSize)
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

    /**
     * `game_redraw`, without the animation and the completion flash.
     *
     * The first frame draws the exterior barriers and the static
     * arrows. Every frame redraws a changed tile, the cursor arrows when
     * the cursor moved, and all per-cell barriers. The tile cache keeps
     * the last drawn byte per cell, stays `0xFF` for a cell in the last
     * moved line, and is never bypassed for the barrier loop.
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
        val t = tileSize

        if (!ds.started) {
            ds.started = true
            for (phase in 0..1) {
                for (x in 0 until ds.width) {
                    if ((s.barrierAt(x, 0) and UL) != 0) {
                        drawBarrierCorner(sink, t, x, -1, LD, phase)
                    }
                    if ((s.barrierAt(x, 0) and RU) != 0) {
                        drawBarrierCorner(sink, t, x, -1, DR, phase)
                    }
                    if ((s.barrierAt(x, 0) and U) != 0) {
                        drawBarrier(sink, t, x, -1, D, phase)
                    }
                    if ((s.barrierAt(x, ds.height - 1) and DR) != 0) {
                        drawBarrierCorner(sink, t, x, ds.height, RU, phase)
                    }
                    if ((s.barrierAt(x, ds.height - 1) and LD) != 0) {
                        drawBarrierCorner(sink, t, x, ds.height, UL, phase)
                    }
                    if ((s.barrierAt(x, ds.height - 1) and D) != 0) {
                        drawBarrier(sink, t, x, ds.height, U, phase)
                    }
                }
                for (y in 0 until ds.height) {
                    if ((s.barrierAt(0, y) and UL) != 0) {
                        drawBarrierCorner(sink, t, -1, y, RU, phase)
                    }
                    if ((s.barrierAt(0, y) and LD) != 0) {
                        drawBarrierCorner(sink, t, -1, y, DR, phase)
                    }
                    if ((s.barrierAt(0, y) and L) != 0) {
                        drawBarrier(sink, t, -1, y, R, phase)
                    }
                    if ((s.barrierAt(ds.width - 1, y) and RU) != 0) {
                        drawBarrierCorner(sink, t, ds.width, y, UL, phase)
                    }
                    if ((s.barrierAt(ds.width - 1, y) and DR) != 0) {
                        drawBarrierCorner(sink, t, ds.width, y, LD, phase)
                    }
                    if ((s.barrierAt(ds.width - 1, y) and R) != 0) {
                        drawBarrier(sink, t, ds.width, y, L, phase)
                    }
                }
            }

            for (x in 0 until ds.width) {
                if (x == s.cx) continue
                drawArrow(sink, t, x, 0, +1, 0, false)
                drawArrow(sink, t, x + 1, ds.height, -1, 0, false)
            }
            for (y in 0 until ds.height) {
                if (y == s.cy) continue
                drawArrow(sink, t, ds.width, y, 0, +1, false)
                drawArrow(sink, t, 0, y + 1, 0, -1, false)
            }
        }

        val curX = if (u.cursorVisible) u.curX else -1
        val curY = if (u.cursorVisible) u.curY else -1
        if (curX != ds.curX || curY != ds.curY) {
            drawArrowForCursor(sink, t, ds.width, ds.height, curX, curY, true)
            drawArrowForCursor(sink, t, ds.width, ds.height, ds.curX, ds.curY, false)
            ds.curX = curX
            ds.curY = curY
        }

        val active = computeActive(s, -1, -1)

        sink.clip(t, t, t * s.width + 1, t * s.height + 1)

        for (x in 0 until ds.width) {
            for (y in 0 until ds.height) {
                var c = s.tileAt(x, y) or active[y * ds.width + x]
                if (ds.visible[y * ds.width + x] != c ||
                    ds.visible[y * ds.width + x] == 0xFF ||
                    x == s.lastMoveCol || y == s.lastMoveRow
                ) {
                    drawTile(sink, s, t, x, y, c, 0.0f, 0.0f)
                    if (x == s.lastMoveCol || y == s.lastMoveRow) {
                        ds.visible[y * ds.width + x] = 0xFF
                    } else {
                        ds.visible[y * ds.width + x] = c
                    }
                }
            }
        }

        for (x in 0 until ds.width) {
            for (y in 0 until ds.height) {
                drawTileBarriers(sink, s, t, x, y)
            }
        }

        sink.unclip()
    }

    /** `draw_filled_line`: four black outlines, then the colour line. */
    private fun drawFilledLine(
        sink: DrawSink,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        colour: Int,
    ) {
        sink.line(x1 - 1, y1, x2 - 1, y2, COL_WIRE)
        sink.line(x1 + 1, y1, x2 + 1, y2, COL_WIRE)
        sink.line(x1, y1 - 1, x2, y2 - 1, COL_WIRE)
        sink.line(x1, y1 + 1, x2, y2 + 1, COL_WIRE)
        sink.line(x1, y1, x2, y2, colour)
    }

    /** `draw_rect_coords`: normalise the corners, then add the inclusive extent. */
    private fun drawRectCoords(
        sink: DrawSink,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        colour: Int,
    ) {
        val mx = if (x1 < x2) x1 else x2
        val my = if (y1 < y2) y1 else y2
        val dx = x2 + x1 - 2 * mx + 1
        val dy = y2 + y1 - 2 * my + 1
        sink.rect(mx, my, dx, dy, colour)
    }

    /** `draw_barrier_corner`. */
    private fun drawBarrierCorner(
        sink: DrawSink,
        t: Int,
        x: Int,
        y: Int,
        dirIn: Int,
        phase: Int,
    ) {
        val dir = dirIn ushr 4
        val bx = t + t * x
        val by = t + t * y
        val dir2 = rotateAntiClockwise(dir)
        val ddx = dx(dir) + dx(dir2)
        val ddy = dy(dir) + dy(dir2)
        val x1 = if (ddx > 0) t + 1 - 1 else 0
        val y1 = if (ddy > 0) t + 1 - 1 else 0

        if (phase == 0) {
            drawRectCoords(
                sink,
                bx + x1,
                by + y1,
                bx + x1 - 1 * ddx,
                by + y1 - (1 - 1) * ddy,
                COL_WIRE,
            )
            drawRectCoords(
                sink,
                bx + x1,
                by + y1,
                bx + x1 - (1 - 1) * ddx,
                by + y1 - 1 * ddy,
                COL_WIRE,
            )
        } else {
            drawRectCoords(
                sink,
                bx + x1,
                by + y1,
                bx + x1 - (1 - 1) * ddx,
                by + y1 - (1 - 1) * ddy,
                COL_BARRIER,
            )
        }
    }

    /** `draw_barrier`. */
    private fun drawBarrier(
        sink: DrawSink,
        t: Int,
        x: Int,
        y: Int,
        dir: Int,
        phase: Int,
    ) {
        val bx = t + t * x
        val by = t + t * y
        val x1 = when {
            dx(dir) > 0 -> t
            dx(dir) == 0 -> 1
            else -> 0
        }
        val y1 = when {
            dy(dir) > 0 -> t
            dy(dir) == 0 -> 1
            else -> 0
        }
        val w = if (dx(dir) != 0) 1 else t - 1
        val h = if (dy(dir) != 0) 1 else t - 1

        if (phase == 0) {
            sink.rect(bx + x1 - dx(dir), by + y1 - dy(dir), w, h, COL_WIRE)
        } else {
            sink.rect(bx + x1, by + y1, w, h, COL_BARRIER)
        }
    }

    /**
     * `draw_tile`. The geometry is `float`, so the truncation of the
     * centre, the wire ends, and the centre box matches C exactly.
     */
    private fun drawTile(
        sink: DrawSink,
        state: State,
        t: Int,
        x: Int,
        y: Int,
        tile: Int,
        xshift: Float,
        yshift: Float,
    ) {
        val bx = t + t * x + (xshift * t).toInt()
        val by = t + t * y + (yshift * t).toInt()

        sink.rect(bx, by, t + 1, t + 1, COL_BORDER)
        sink.rect(
            bx + 1,
            by + 1,
            t - 1,
            t - 1,
            if ((tile and FLASHING) != 0) COL_FLASHING else COL_BACKGROUND,
        )

        val cx = 1.0f + (t - 1).toFloat() / 2.0f - 0.5f
        val cy = cx
        val col = if ((tile and ACTIVE) != 0) COL_POWERED else COL_WIRE
        for (dir in DIRECTIONS) {
            if ((tile and dir) != 0) {
                val ex = (t - 1 - 1.0f) / 2.0f * dx(dir)
                val ey = (t - 1 - 1.0f) / 2.0f * dy(dir)
                drawFilledLine(
                    sink,
                    bx + cx.toInt(),
                    by + cy.toInt(),
                    bx + (cx + ex).toInt(),
                    by + (cy + ey).toInt(),
                    COL_WIRE,
                )
            }
        }
        for (dir in DIRECTIONS) {
            if ((tile and dir) != 0) {
                val ex = (t - 1 - 1.0f) / 2.0f * dx(dir)
                val ey = (t - 1 - 1.0f) / 2.0f * dy(dir)
                sink.line(
                    bx + cx.toInt(),
                    by + cy.toInt(),
                    bx + (cx + ex).toInt(),
                    by + (cy + ey).toInt(),
                    col,
                )
            }
        }

        var boxColour = -1
        if (x == state.cx && y == state.cy) {
            boxColour = COL_WIRE
        } else if (countBits(tile) == 1) {
            boxColour = if ((tile and ACTIVE) != 0) COL_POWERED else COL_ENDPOINT
        }
        if (boxColour >= 0) {
            val points = IntArray(8)
            points[0] = +1
            points[1] = +1
            points[2] = +1
            points[3] = -1
            points[4] = -1
            points[5] = -1
            points[6] = -1
            points[7] = +1
            for (i in 0 until 8 step 2) {
                val ex = (t * 0.24f) * points[i]
                val ey = (t * 0.24f) * points[i + 1]
                points[i] = bx + (cx + ex).toInt()
                points[i + 1] = by + (cy + ey).toInt()
            }
            sink.poly(points, boxColour, COL_WIRE)
        }

        for (dir in DIRECTIONS) {
            val ox = x + dx(dir)
            val oy = y + dy(dir)
            if (ox < 0 || ox >= state.width || oy < 0 || oy >= state.height) continue
            if ((state.tileAt(ox, oy) and flip(dir)) == 0) continue

            val px = bx + when {
                dx(dir) > 0 -> t + 1 - 1
                dx(dir) < 0 -> 0
                else -> cx.toInt()
            }
            val py = by + when {
                dy(dir) > 0 -> t + 1 - 1
                dy(dir) < 0 -> 0
                else -> cy.toInt()
            }
            val lx = dx(dir) * (1 - 1)
            val ly = dy(dir) * (1 - 1)
            val vx = if (dy(dir) != 0) 1 else 0
            val vy = if (dx(dir) != 0) 1 else 0

            if (xshift == 0.0f && yshift == 0.0f && (tile and dir) != 0) {
                drawRectCoords(
                    sink,
                    px - vx,
                    py - vy,
                    px + lx + vx,
                    py + ly + vy,
                    COL_WIRE,
                )
                drawRectCoords(
                    sink,
                    px,
                    py,
                    px + lx,
                    py + ly,
                    if ((tile and ACTIVE) != 0) COL_POWERED else COL_WIRE,
                )
            } else {
                drawRectCoords(sink, px, py, px, py, COL_WIRE)
            }
        }

        sink.update(bx, by, t + 1, t + 1)
    }

    /** `draw_tile_barriers`: both phases, then one update. */
    private fun drawTileBarriers(
        sink: DrawSink,
        state: State,
        t: Int,
        x: Int,
        y: Int,
    ) {
        val bx = t + t * x
        val by = t + t * y
        for (phase in 0..1) {
            for (dir in DIRECTIONS) {
                if ((state.barrierAt(x, y) and (dir shl 4)) != 0) {
                    drawBarrierCorner(sink, t, x, y, dir shl 4, phase)
                }
            }
            for (dir in DIRECTIONS) {
                if ((state.barrierAt(x, y) and dir) != 0) {
                    drawBarrier(sink, t, x, y, dir, phase)
                }
            }
        }
        sink.update(bx, by, t + 1, t + 1)
    }

    /** `draw_arrow`: one seven-point arrow, rotated by `(xdx, xdy)`. */
    private fun drawArrow(
        sink: DrawSink,
        t: Int,
        x: Int,
        y: Int,
        xdx: Int,
        xdy: Int,
        cur: Boolean,
    ) {
        val coords = IntArray(14)
        val ydy = -xdx
        val ydx = xdy
        val px = x * t + t
        val py = y * t + t

        fun point(n: Int, xx: Int, yy: Int) {
            coords[2 * n] = px + xx * xdx + yy * ydx
            coords[2 * n + 1] = py + xx * xdy + yy * ydy
        }

        point(0, t / 2, 3 * t / 4)
        point(1, 3 * t / 4, t / 2)
        point(2, 5 * t / 8, t / 2)
        point(3, 5 * t / 8, t / 4)
        point(4, 3 * t / 8, t / 4)
        point(5, 3 * t / 8, t / 2)
        point(6, t / 4, t / 2)

        sink.poly(
            coords,
            if (cur) COL_POWERED else COL_LOWLIGHT,
            COL_TEXT,
        )
    }

    /**
     * `draw_arrow_for_cursor`. The update rectangle is the cursor cell,
     * which does not always cover the arrow on the bottom row and the
     * side columns; C keeps that rectangle, so the port does too.
     */
    private fun drawArrowForCursor(
        sink: DrawSink,
        t: Int,
        width: Int,
        height: Int,
        curX: Int,
        curY: Int,
        cur: Boolean,
    ) {
        if (curX == -1 && curY == -1) return
        if (curX == -1) {
            drawArrow(sink, t, 0, curY + 1, 0, -1, cur)
        } else if (curX == width) {
            drawArrow(sink, t, width, curY, 0, +1, cur)
        } else if (curY == -1) {
            drawArrow(sink, t, curX, 0, +1, 0, cur)
        } else if (curY == height) {
            drawArrow(sink, t, curX + 1, height, -1, 0, cur)
        } else {
            error("invalid cursor position")
        }
        sink.update(curX * t + t, curY * t + t, t, t)
    }

    /**
     * `current_key_label`. C has no oracle operation for it and the
     * Android shell does not call it, so this is a public helper. Both
     * select buttons say "Slide", even though the second one reverses
     * the slide. The test is on the raw button, so a modified select has
     * no label.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.cursorVisible
        ) {
            return "Slide"
        }
        return ""
    }

    /**
     * `interpret_move`. The order of the tests matters. The pointer
     * branch clears the cursor before the geometry test, so a click that
     * changes nothing still hides the cursor. A click in a cell, on a
     * corner, on the centre band, or outside the board returns
     * [MoveResult.Unused], not a UI update.
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

        if (Buttons.isCursorMove(key)) {
            var cpos = c2pos(s.width, s.height, u.curX, u.curY)
            val diff = c2diff(s.width, s.height, u.curX, u.curY, key)
            if (diff != 0) {
                do {
                    cpos += diff
                    val position = pos2c(s.width, s.height, cpos)
                    u.curX = position.first
                    u.curY = position.second
                } while (u.curX == s.cx || u.curY == s.cy)
            }
            u.cursorVisible = true
            return MoveResult.UiUpdate
        }

        var clickX = 0
        var clickY = 0
        if (key == Buttons.LEFT_BUTTON || key == Buttons.RIGHT_BUTTON) {
            clickX = (x + tileSize - 1) / tileSize - 2
            clickY = (y + tileSize - 1) / tileSize - 2
            u.cursorVisible = false
        } else if (key == Buttons.CURSOR_SELECT || key == Buttons.CURSOR_SELECT2) {
            if (u.cursorVisible) {
                clickX = u.curX
                clickY = u.curY
            } else {
                u.cursorVisible = true
                return MoveResult.UiUpdate
            }
        } else {
            return MoveResult.Unused
        }

        val ddx: Int
        val ddy: Int
        if (clickY in 0 until s.height && clickY != s.cy) {
            when (clickX) {
                -1 -> ddx = +1
                s.width -> ddx = -1
                else -> return MoveResult.Unused
            }
            ddy = 0
        } else if (clickX in 0 until s.width && clickX != s.cx) {
            when (clickY) {
                -1 -> ddy = +1
                s.height -> ddy = -1
                else -> return MoveResult.Unused
            }
            ddx = 0
        } else {
            return MoveResult.Unused
        }

        val sign = if (key == Buttons.RIGHT_BUTTON || key == Buttons.CURSOR_SELECT2) -1 else 1
        return if (ddx == 0) {
            MoveResult.Move("C$clickX,${sign * ddy}")
        } else {
            MoveResult.Move("R$clickY,${sign * ddx}")
        }
    }

    /** `c2pos`: a ring position to a one-dimensional index. */
    private fun c2pos(w: Int, h: Int, cx: Int, cy: Int): Int = when {
        cy == -1 -> cx
        cx == w -> w + cy
        cy == h -> w + h + (w - cx - 1)
        cx == -1 -> w + h + w + (h - cy - 1)
        else -> error("invalid cursor position")
    }

    /** `c2diff`: the step along the ring for a cursor key. */
    private fun c2diff(w: Int, h: Int, cx: Int, cy: Int, button: Int): Int {
        var diff = 0
        if (cy == -1) {
            diff = when (button) {
                Buttons.CURSOR_RIGHT -> +1
                Buttons.CURSOR_LEFT -> -1
                else -> diff
            }
        }
        if (cy == h) {
            diff = when (button) {
                Buttons.CURSOR_RIGHT -> -1
                Buttons.CURSOR_LEFT -> +1
                else -> diff
            }
        }
        if (cx == -1) {
            diff = when (button) {
                Buttons.CURSOR_UP -> +1
                Buttons.CURSOR_DOWN -> -1
                else -> diff
            }
        }
        if (cx == w) {
            diff = when (button) {
                Buttons.CURSOR_UP -> -1
                Buttons.CURSOR_DOWN -> +1
                else -> diff
            }
        }
        if (button == Buttons.CURSOR_LEFT && cx == w && (cy == 0 || cy == h - 1)) {
            diff = if (cy == 0) -1 else +1
        }
        if (button == Buttons.CURSOR_RIGHT && cx == -1 && (cy == 0 || cy == h - 1)) {
            diff = if (cy == 0) +1 else -1
        }
        if (button == Buttons.CURSOR_DOWN && cy == -1 && (cx == 0 || cx == w - 1)) {
            diff = if (cx == 0) -1 else +1
        }
        if (button == Buttons.CURSOR_UP && cy == h && (cx == 0 || cx == w - 1)) {
            diff = if (cx == 0) +1 else -1
        }
        return diff
    }

    /** `pos2c`: a one-dimensional index back to a ring position. */
    private fun pos2c(w: Int, h: Int, pos: Int): Pair<Int, Int> {
        val max = w + h + w + h
        var p = ((pos % max) + max) % max
        if (p < w) return p to -1
        p -= w
        if (p < h) return w to p
        p -= h
        if (p < w) return (w - p - 1) to h
        p -= w
        if (p < h) return -1 to (h - p - 1)
        error("invalid ring position")
    }

    /** `slide_row_int`. `dir` moves the contents toward lower indices. */
    private fun slideRowInt(w: Int, h: Int, tiles: IntArray, dir: Int, row: Int) {
        var x = if (dir > 0) -1 else w
        var tx = x + dir
        var n = w - 1
        check(tx in 0 until w) { "row slide starts out of range" }
        val endTile = tiles[row * w + tx]
        do {
            x = tx
            tx = (x + dir + w) % w
            tiles[row * w + x] = tiles[row * w + tx]
        } while (--n > 0)
        tiles[row * w + tx] = endTile
    }

    /** `slide_col_int`. The same arithmetic on a column. */
    private fun slideColInt(w: Int, h: Int, tiles: IntArray, dir: Int, col: Int) {
        var y = if (dir > 0) -1 else h
        var ty = y + dir
        var n = h - 1
        check(ty in 0 until h) { "column slide starts out of range" }
        val endTile = tiles[ty * w + col]
        do {
            y = ty
            ty = (y + dir + h) % h
            tiles[y * w + col] = tiles[ty * w + col]
        } while (--n > 0)
        tiles[ty * w + col] = endTile
    }

    /** `OFFSET` in the x direction, always modulo the width. */
    private fun offsetX(x: Int, dir: Int, w: Int): Int = (x + w + dx(dir)) % w

    /** `OFFSET` in the y direction, always modulo the height. */
    private fun offsetY(y: Int, dir: Int, h: Int): Int = (y + h + dy(dir)) % h

    /** `find234` then `del234`; a missing key is not an error. */
    private fun findAndDelete(tree: Tree234<Xyd>, key: Xyd) {
        val found = tree.find(key) ?: return
        tree.delete(found)
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> error("not a hex digit: $c")
    }

    private fun hexValueOrNull(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
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
     * C `strtod` for the barrier rate. It reads white space, a sign,
     * decimal digits with an optional fraction and exponent, and the
     * `inf`, `infinity`, and `nan` words. When there is no number the
     * value is 0, as C returns. The end pointer is not needed: C passes
     * NULL and the caller rescans from the start of the number.
     */
    private fun strtod(text: String, start: Int): Double {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }

        val rest = text.substring(i).lowercase()
        when {
            rest.startsWith("infinity") -> {
                val value = Double.POSITIVE_INFINITY
                return if (negative) -value else value
            }

            rest.startsWith("inf") -> {
                val value = Double.POSITIVE_INFINITY
                return if (negative) -value else value
            }

            rest.startsWith("nan") -> {
                return Double.NaN
            }
        }

        var mantissa = 0.0
        var digits = 0
        var exponent = 0
        while (i < text.length && text[i] in '0'..'9') {
            mantissa = mantissa * 10.0 + (text[i] - '0')
            digits++
            i++
        }
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i] in '0'..'9') {
                mantissa = mantissa * 10.0 + (text[i] - '0')
                digits++
                exponent--
                i++
            }
        }
        if (digits == 0) {
            return 0.0
        }

        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            var k = i + 1
            var expNegative = false
            if (k < text.length && (text[k] == '+' || text[k] == '-')) {
                expNegative = text[k] == '-'
                k++
            }
            val expStart = k
            var expValue = 0
            while (k < text.length && text[k] in '0'..'9') {
                expValue = expValue * 10 + (text[k] - '0')
                k++
            }
            if (k > expStart) {
                exponent += if (expNegative) -expValue else expValue
                i = k
            }
        }

        var value = mantissa * 10.0.pow(exponent)
        if (negative) value = -value
        return value
    }

    /**
     * `sscanf(text + start, "%d,%d")`: white space before a number is
     * skipped and the comma must follow the first number exactly; the
     * second number may carry trailing junk.
     */
    private fun scanTwoInts(text: String, start: Int): Pair<Int, Int>? {
        val first = scanInt(text, start) ?: return null
        var index = first.second
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = scanInt(text, index) ?: return null
        return first.first to second.first
    }

    /** One `%d`: the clamped value and the index after its last digit. */
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

    /**
     * C `%g` with the default precision of six significant digits. The
     * barrier rate text must match the oracle: `1` for one, `0.5`,
     * `0.9999`, and an exponent form such as `1e-05` for a small value.
     */
    internal fun formatG(value: Float): String {
        if (value == 0.0f) return "0"
        val negative = value < 0.0f
        val absolute = abs(value.toDouble())
        var exponent = floor(log10(absolute)).toInt()
        val scaled = absolute / 10.0.pow(exponent.toDouble())
        var mantissa = round(scaled * 100000.0).toLong()
        if (mantissa >= 1000000L) {
            mantissa /= 10L
            exponent++
        }
        val digits = mantissa.toString().padStart(6, '0')

        val body = if (exponent < -4 || exponent >= 6) {
            val fraction = digits.substring(1).trimEnd('0')
            val mantissaText = if (fraction.isEmpty()) {
                digits.substring(0, 1)
            } else {
                digits.substring(0, 1) + "." + fraction
            }
            val sign = if (exponent < 0) '-' else '+'
            val expText = abs(exponent).toString().padStart(2, '0')
            "${mantissaText}e$sign$expText"
        } else if (exponent >= 0) {
            val integerPart = digits.substring(0, exponent + 1)
            val fraction = digits.substring(exponent + 1).trimEnd('0')
            if (fraction.isEmpty()) integerPart else "$integerPart.$fraction"
        } else {
            val fraction = ("0".repeat(-exponent - 1) + digits).trimEnd('0')
            if (fraction.isEmpty()) "0" else "0.$fraction"
        }

        return if (negative) "-$body" else body
    }
}
