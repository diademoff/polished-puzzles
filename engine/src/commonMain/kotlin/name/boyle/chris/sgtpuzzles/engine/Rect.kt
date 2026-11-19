package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * The `rect` game: divide the grid into rectangles, one number each.
 *
 * The port follows `app/src/main/jni/rect.c`. The generator builds a
 * small random tiling, expands it to the requested size, runs the
 * rectangle solver to place the numbers, and stores the solution in
 * `AUXINFO`. The solver is shared with the `SOLVE` move.
 */
object Rect : Game {

    // Palette, `rect.c:37-46`.
    private const val COL_BACKGROUND = 0
    private const val COL_CORRECT = 1
    private const val COL_LINE = 2
    private const val COL_TEXT = 3
    private const val COL_GRID = 4
    private const val COL_DRAG = 5
    private const val COL_DRAGERASE = 6
    private const val COL_CURSOR = 7
    private const val NCOLOURS = 8

    /** `PREFERRED_TILE_SIZE`, `rect.c:68`. */
    private const val PREFERRED_TILE_SIZE = 24

    /**
     * `BORDER`, `rect.c:70-74`. The Android build defines `SMALL_SCREEN`,
     * so the border is the constant 2, not a tile fraction.
     */
    private const val BORDER = 2

    /** `CORNER_TOLERANCE` and `CENTRE_TOLERANCE`, `rect.c:76-77`. */
    private const val CORNER_TOLERANCE = 0.15f
    private const val CENTRE_TOLERANCE = 0.15f

    /** `FLASH_TIME`, `rect.c:79`; the seam has no flash clock. */
    private const val FLASH_TIME = 0.13f

    private const val CORRECT_BIT = 1L shl 16
    private const val CURSOR_BIT = 1L shl 17

    /** `struct game_params`, `rect.c:48-53`. */
    data class Params(
        val w: Int,
        val h: Int,
        val expandFactor: Float,
        val unique: Boolean,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, expandFactor, unique)
    }

    /**
     * `struct game_state`, `rect.c:82-90`. There is one vertical edge
     * for each square except the right boundary and one horizontal edge
     * for each square except the bottom one; both arrays are `w * h`
     * and the out-of-range entries are unused, as in C.
     */
    class State internal constructor(
        val w: Int,
        val h: Int,
        val grid: IntArray,
        val vedge: IntArray,
        val hedge: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
        val correct: IntArray,
    ) : Game.State {
        val area: Int get() = w * h

        fun gridAt(x: Int, y: Int): Int = grid[y * w + x]

        fun vedgeAt(x: Int, y: Int): Int = vedge[y * w + x]

        fun hedgeAt(x: Int, y: Int): Int = hedge[y * w + x]

        fun hRange(x: Int, y: Int): Boolean = x in 0 until w && y in 1 until h

        fun vRange(x: Int, y: Int): Boolean = x in 1 until w && y in 0 until h

        override fun copy(): Game.State = State(
            w,
            h,
            grid.copyOf(),
            vedge.copyOf(),
            hedge.copyOf(),
            completed,
            cheated,
            correct.copyOf(),
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                w == other.w && h == other.h &&
                grid.contentEquals(other.grid) &&
                vedge.contentEquals(other.vedge) &&
                hedge.contentEquals(other.hedge) &&
                completed == other.completed &&
                cheated == other.cheated &&
                correct.contentEquals(other.correct)

        override fun hashCode(): Int {
            var result = w
            result = 31 * result + h
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + vedge.contentHashCode()
            result = 31 * result + hedge.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + correct.contentHashCode()
            return result
        }
    }

    /**
     * `struct game_ui`, `rect.c:2154-2194`. The drag and cursor
     * coordinates are doubled grid coordinates, so an odd value is an
     * edge midpoint and two odd values are a square centre.
     */
    class Ui internal constructor() : Game.Ui {
        var dragStartX: Int = -1
        var dragStartY: Int = -1
        var dragEndX: Int = -1
        var dragEndY: Int = -1
        var dragged: Boolean = false
        var erasing: Boolean = false
        var x1: Int = -1
        var y1: Int = -1
        var x2: Int = -1
        var y2: Int = -1
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false
        var curDragging: Boolean = false

        /** `reset_ui`, `rect.c:2197-2208`. */
        fun reset() {
            dragStartX = -1
            dragStartY = -1
            dragEndX = -1
            dragEndY = -1
            x1 = -1
            y1 = -1
            x2 = -1
            y2 = -1
            dragged = false
        }
    }

    /** `struct game_drawstate`, `rect.c:2377-2382`. */
    class DrawState internal constructor(w: Int, h: Int) : Game.DrawState {
        var started: Boolean = false
        var tileSize: Int = 0
        val visible: LongArray = LongArray(w * h) { 0xFFFFL }
    }

    /** A rectangle placement, `struct rect`, `rect.c:242-247`. */
    private data class Placement(val x: Int, val y: Int, val w: Int, val h: Int)

    /** `struct numberdata`, `rect.c:255-259`. Points are encoded `y*w+x`. */
    private class NumberData(val area: Int, val points: MutableList<Int>)

    // ------------------------------------------------------------------
    // Identity and parameters.
    // ------------------------------------------------------------------

    override val name: String = "Rectangles"
    override val backendName: String = "rect"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val isTimed: Boolean = false
    override val flags: Int = 0

    /** `game_fetch_preset` under `SMALL_SCREEN`, `rect.c:102-129`. */
    private val presetSizes = listOf(7, 9, 11, 13, 15)

    override fun defaultParams(): Game.Params = Params(7, 7, 0.0f, true)

    override fun presets(): List<Preset> = presetSizes.map {
        Preset("${it}x$it", "${it}x$it")
    }

    /**
     * `decode_params`, `rect.c:143-160`. `atoi` reads the width; the
     * following scan does not skip leading space, so a space stops the
     * grammar, as in C.
     */
    override fun decodeParams(text: String): Game.Params {
        var position = 0
        var w = atoi(text, 0)
        var h = w
        var expand = 0.0f
        var unique = true
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            h = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && text[position] == 'e') {
            position++
            expand = cAtof(text.substring(position))
            while (position < text.length && (text[position] == '.' || text[position] in '0'..'9')) {
                position++
            }
        }
        if (position < text.length && text[position] == 'a') {
            position++
            unique = false
        }
        return Params(w, h, expand, unique)
    }

    /**
     * `encode_params`, `rect.c:164-175`: `%dx%d`, then `e%g` when the
     * expansion is nonzero, then `a` when uniqueness is off.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder("${p.w}x${p.h}")
        if (full && p.expandFactor != 0.0f) out.append('e').append(cFloatG(p.expandFactor))
        if (full && !p.unique) out.append('a')
        return out.toString()
    }

    /** `validate_params`, `rect.c:221-236`, in C order. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w <= 0 || p.h <= 0) {
            return "Width and height must both be greater than zero"
        }
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.w * p.h < 2) return "Grid area must be greater than one"
        if (p.expandFactor < 0.0f) return "Expansion factor may not be negative"
        return null
    }

    /** `game_configure` (`rect.c:177-207`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Text("Expansion factor", cFloatG(p.expandFactor)),
            ConfigItem.Bool("Ensure unique solution", p.unique),
        )
    }

    /** `custom_params` (`rect.c:209-219`), with `atof` for the factor. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            cAtof((items[2] as ConfigItem.Text).value),
            (items[3] as ConfigItem.Bool).value,
        )

    // ------------------------------------------------------------------
    // Description validation and state creation, `rect.c:1785-1952`.
    // ------------------------------------------------------------------

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val area = p.w * p.h
        var squares = 0
        var i = 0
        while (i < desc.length) {
            val c = desc[i]
            i++
            when {
                c in 'a'..'z' -> squares += c - 'a' + 1
                c == '_' -> Unit
                c in '1'..'9' -> {
                    squares++
                    while (i < desc.length && desc[i] in '0'..'9') i++
                }
                else -> return "Invalid character in game description"
            }
        }
        if (squares < area) return "Not enough data to fill grid"
        if (squares > area) return "Too much data to fit in grid"
        return null
    }

    /** `get_correct`, `rect.c:1813-1905`. */
    internal fun getCorrect(state: State): IntArray {
        val w = state.w
        val h = state.h
        val ret = IntArray(w * h) { -1 }
        for (x in 0 until w) {
            for (y in 0 until h) {
                if (ret[y * w + x] != -1) continue
                var rw = 1
                while (x + rw < w && state.vedge[(y * w) + x + rw] == 0) rw++
                var rh = 1
                while (y + rh < h && state.hedge[((y + rh) * w) + x] == 0) rh++

                var valid = true
                for (xx in x until x + rw) {
                    for (yy in y..(y + rh)) {
                        val e = if (!state.hRange(xx, yy)) 1 else state.hedge[yy * w + xx]
                        val ec = if (yy == y || yy == y + rh) 1 else 0
                        if (e != ec) valid = false
                    }
                }
                for (yy in y until y + rh) {
                    for (xx in x..(x + rw)) {
                        val e = if (!state.vRange(xx, yy)) 1 else state.vedge[yy * w + xx]
                        val ec = if (xx == x || xx == x + rw) 1 else 0
                        if (e != ec) valid = false
                    }
                }

                if (!valid) {
                    ret[y * w + x] = 0
                    continue
                }

                var num = 0
                var area = 0
                for (xx in x until x + rw) {
                    for (yy in y until y + rh) {
                        area++
                        val v = state.grid[yy * w + xx]
                        if (v != 0) {
                            if (num > 0) valid = false
                            num = v
                        }
                    }
                }
                if (num != area) valid = false

                val value = if (valid) 1 else 0
                for (xx in x until x + rw) {
                    for (yy in y until y + rh) {
                        ret[yy * w + xx] = value
                    }
                }
            }
        }
        return ret
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val area = p.w * p.h
        val grid = IntArray(area)
        var i = 0
        var position = 0
        while (position < desc.length) {
            val c = desc[position]
            position++
            when {
                c in 'a'..'z' -> {
                    var run = c - 'a' + 1
                    require(i + run <= area) { "description is too long" }
                    while (run-- > 0) grid[i++] = 0
                }
                c == '_' -> Unit
                c in '1'..'9' -> {
                    require(i < area) { "description is too long" }
                    val start = position - 1
                    while (position < desc.length && desc[position] in '0'..'9') position++
                    grid[i++] = atoi(desc, start)
                }
                else -> throw IllegalArgumentException("invalid character in description")
            }
        }
        require(i == area) { "description is too short" }
        val state = State(
            p.w,
            p.h,
            grid,
            IntArray(area),
            IntArray(area),
            false,
            false,
            IntArray(area),
        )
        return State(
            p.w,
            p.h,
            grid,
            IntArray(area),
            IntArray(area),
            false,
            false,
            getCorrect(state),
        )
    }

    // ------------------------------------------------------------------
    // Text and solve, `rect.c:1987-2152`.
    // ------------------------------------------------------------------

    override fun canFormatAsTextNow(params: Game.Params): Boolean = true

    override fun textFormat(state: Game.State): String {
        val s = state as State
        var col = 2
        for (v in s.grid) {
            val len = v.toString().length
            if (col < len) col = len
        }
        val out = StringBuilder()
        for (y in 0..2 * s.h) {
            for (x in 0..2 * s.w) {
                when {
                    (x and y and 1) != 0 -> {
                        val v = s.grid[(y / 2) * s.w + x / 2]
                        val text = if (v != 0) v.toString() else ""
                        out.append(" ".repeat(col - text.length)).append(text)
                    }
                    (x and 1) != 0 -> {
                        val h = y == 0 || y == 2 * s.h ||
                            (s.hRange(x / 2, y / 2) && s.hedge[(y / 2) * s.w + x / 2] != 0)
                        out.append((if (h) "-" else " ").repeat(col))
                    }
                    (y and 1) != 0 -> {
                        val v = x == 0 || x == 2 * s.w ||
                            (s.vRange(x / 2, y / 2) && s.vedge[(y / 2) * s.w + x / 2] != 0)
                        out.append(if (v) '|' else ' ')
                    }
                    else -> {
                        val hl = y == 0 || y == 2 * s.h ||
                            (s.hRange((x - 1) / 2, y / 2) && s.hedge[(y / 2) * s.w + (x - 1) / 2] != 0)
                        val hr = y == 0 || y == 2 * s.h ||
                            (s.hRange((x + 1) / 2, y / 2) && s.hedge[(y / 2) * s.w + (x + 1) / 2] != 0)
                        val vu = x == 0 || x == 2 * s.w ||
                            (s.vRange(x / 2, (y - 1) / 2) && s.vedge[((y - 1) / 2) * s.w + x / 2] != 0)
                        val vd = x == 0 || x == 2 * s.w ||
                            (s.vRange(x / 2, (y + 1) / 2) && s.vedge[((y + 1) / 2) * s.w + x / 2] != 0)
                        out.append(
                            when {
                                !hl && !hr && !vu && !vd -> ' '
                                hl && hr && !vu && !vd -> '-'
                                !hl && !hr && vu && vd -> '|'
                                else -> '+'
                            },
                        )
                    }
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * `solve_game`, `rect.c:1987-2054`. The stored answer short-circuits
     * the solver; otherwise the solver gets one candidate per number and
     * the edges are encoded as `S` plus vertical then horizontal bits.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? {
        val s = state as State
        if (auxInfo != null) return auxInfo
        val numbers = ArrayList<NumberData>()
        for (i in 0 until s.area) {
            val v = s.grid[i]
            if (v != 0) numbers += NumberData(v, mutableListOf(i))
        }
        val vedge = IntArray(s.area)
        val hedge = IntArray(s.area)
        rectSolver(s.w, s.h, numbers.toTypedArray(), hedge, vedge, null)
        val out = StringBuilder("S")
        for (y in 0 until s.h) for (x in 1 until s.w) out.append(if (vedge[y * s.w + x] != 0) '1' else '0')
        for (y in 1 until s.h) for (x in 0 until s.w) out.append(if (hedge[y * s.w + x] != 0) '1' else '0')
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Solver, `rect.c:265-931`.
    // ------------------------------------------------------------------

    /** `remove_rect_placement`, `rect.c:265-309`. */
    private fun removeRectPlacement(
        w: Int,
        h: Int,
        positions: Array<MutableList<Placement>>,
        overlaps: IntArray,
        rectNum: Int,
        placement: Int,
    ) {
        val r = positions[rectNum][placement]
        for (yy in 0 until r.h) {
            val y = r.y + yy
            for (xx in 0 until r.w) {
                val x = r.x + xx
                val index = (rectNum * h + y) * w + x
                check(overlaps[index] != 0) { "overlap count underflow" }
                if (overlaps[index] > 0) overlaps[index]--
            }
        }
        val last = positions[rectNum].size - 1
        if (placement < last) {
            val t = positions[rectNum][placement]
            positions[rectNum][placement] = positions[rectNum][last]
            positions[rectNum][last] = t
        }
        positions[rectNum].removeAt(last)
    }

    /** `remove_number_placement`, `rect.c:311-336`. */
    private fun removeNumberPlacement(
        w: Int,
        number: NumberData,
        index: Int,
        rectByPlace: IntArray,
    ) {
        rectByPlace[number.points[index]] = -1
        val last = number.points.size - 1
        if (index < last) {
            val t = number.points[index]
            number.points[index] = number.points[last]
            number.points[last] = t
        }
        number.points.removeAt(last)
    }

    /**
     * `rect_solver`, `rect.c:338-931`. Returns 0 for an inconsistency,
     * 1 for a solved deduction, and 2 while a rectangle still has more
     * than one placement. A non-null [random] turns on the winnowing
     * step that removes a number placement and consumes RNG, which the
     * generator needs and the `SOLVE` path does not.
     */
    private fun rectSolver(
        w: Int,
        h: Int,
        numbers: Array<NumberData>,
        hedge: IntArray?,
        vedge: IntArray?,
        random: RandomState?,
    ): Int {
        val nrects = numbers.size
        val positions: Array<MutableList<Placement>> = Array(nrects) { mutableListOf() }

        for (i in 0 until nrects) {
            val area = numbers[i].area
            var minx = w
            var miny = h
            var maxx = -1
            var maxy = -1
            for (p in numbers[i].points) {
                val x = p % w
                val y = p / w
                if (minx > x) minx = x
                if (miny > y) miny = y
                if (maxx < x) maxx = x
                if (maxy < y) maxy = y
            }
            var rw = 1
            while (rw <= area && rw <= w) {
                if (area % rw == 0) {
                    val rh = area / rw
                    if (rh <= h) {
                        var y = miny - rh + 1
                        while (y <= maxy) {
                            if (y >= 0 && y + rh <= h) {
                                var x = minx - rw + 1
                                while (x <= maxx) {
                                    if (x >= 0 && x + rw <= w) {
                                        var contains = false
                                        for (p in numbers[i].points) {
                                            val px = p % w
                                            val py = p / w
                                            if (px >= x && px < x + rw &&
                                                py >= y && py < y + rh
                                            ) {
                                                contains = true
                                                break
                                            }
                                        }
                                        if (contains) positions[i].add(Placement(x, y, rw, rh))
                                    }
                                    x++
                                }
                            }
                            y++
                        }
                    }
                }
                rw++
            }
        }

        val overlaps = IntArray(nrects * w * h)
        for (i in 0 until nrects) {
            for (r in positions[i]) {
                for (yy in 0 until r.h) {
                    for (xx in 0 until r.w) {
                        overlaps[(i * h + r.y + yy) * w + r.x + xx]++
                    }
                }
            }
        }

        val rectByPlace = IntArray(w * h) { -1 }
        for (i in 0 until nrects) {
            for (p in numbers[i].points) {
                check(rectByPlace[p] == -1) { "two numbers share a square" }
                rectByPlace[p] = i
            }
        }
        val workspace = IntArray(nrects)

        deduction@ while (true) {
            var doneSomething = false

            // Sole remaining number position.
            for (i in 0 until nrects) {
                if (numbers[i].points.size == 1) {
                    val p = numbers[i].points[0]
                    val x = p % w
                    val y = p / w
                    val index = (i * h + y) * w + x
                    if (overlaps[index] >= -1) {
                        if (overlaps[index] <= 0) break@deduction
                        for (j in 0 until nrects) overlaps[(j * h + y) * w + x] = -1
                        overlaps[index] = -2
                    }
                }
            }

            // Intersection of all placements.
            for (i in 0 until nrects) {
                var minx = 0
                var miny = 0
                var maxx = w
                var maxy = h
                for (r in positions[i]) {
                    if (minx < r.x) minx = r.x
                    if (miny < r.y) miny = r.y
                    if (maxx > r.x + r.w) maxx = r.x + r.w
                    if (maxy > r.y + r.h) maxy = r.y + r.h
                }
                var yy = miny
                while (yy < maxy) {
                    var xx = minx
                    while (xx < maxx) {
                        val index = (i * h + yy) * w + xx
                        if (overlaps[index] >= -1) {
                            if (overlaps[index] <= 0) break@deduction
                            for (j in 0 until nrects) overlaps[(j * h + yy) * w + xx] = -1
                            overlaps[index] = -2
                        }
                        xx++
                    }
                    yy++
                }
            }

            // Rectangle-focused deduction.
            for (i in 0 until nrects) {
                var j = 0
                while (j < positions[i].size) {
                    val r = positions[i][j]
                    for (k in 0 until nrects) workspace[k] = 0
                    var del = false
                    for (yy in 0 until r.h) {
                        val y = r.y + yy
                        for (xx in 0 until r.w) {
                            val x = r.x + xx
                            if (overlaps[(i * h + y) * w + x] == -1) del = true
                            val other = rectByPlace[y * w + x]
                            if (other != -1) workspace[other]++
                        }
                    }
                    if (!del) {
                        for (k in 0 until nrects) {
                            if (k != i && workspace[k] == numbers[k].points.size) {
                                del = true
                                break
                            }
                        }
                        if (!del && workspace[i] == 0) del = true
                    }
                    if (del) {
                        removeRectPlacement(w, h, positions, overlaps, i, j)
                        doneSomething = true
                    } else {
                        j++
                    }
                }
            }

            // Square-focused deduction.
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (overlaps[y * w + x] < 0) continue
                    var n = 0
                    var index = -1
                    for (i in 0 until nrects) {
                        if (overlaps[(i * h + y) * w + x] > 0) {
                            n++
                            index = i
                        }
                    }
                    if (n == 1) {
                        var j = 0
                        while (j < positions[index].size) {
                            val r = positions[index][j]
                            val inside = x >= r.x && x < r.x + r.w &&
                                y >= r.y && y < r.y + r.h
                            if (inside) {
                                j++
                            } else {
                                removeRectPlacement(w, h, positions, overlaps, index, j)
                                doneSomething = true
                            }
                        }
                    }
                }
            }

            if (doneSomething) continue

            if (random != null) {
                val rpns = ArrayList<Int>()
                for (i in 0 until nrects) {
                    for (j in positions[i].indices) {
                        val r = positions[i][j]
                        for (yy in 0 until r.h) {
                            val y = r.y + yy
                            for (xx in 0 until r.w) {
                                val x = r.x + xx
                                val place = rectByPlace[y * w + x]
                                if (place >= 0 && place != i) {
                                    rpns += i
                                    rpns += j
                                    rpns += place
                                }
                            }
                        }
                    }
                }
                val nrpns = rpns.size / 3
                if (nrpns > 0) {
                    val chosen = random.upto(nrpns.toLong()).toInt()
                    val i = rpns[chosen * 3]
                    val j = rpns[chosen * 3 + 1]
                    val k = rpns[chosen * 3 + 2]
                    val r = positions[i][j]
                    var m = 0
                    while (m < numbers[k].points.size) {
                        val p = numbers[k].points[m]
                        val x = p % w
                        val y = p / w
                        if (x < r.x || x >= r.x + r.w || y < r.y || y >= r.y + r.h) {
                            removeNumberPlacement(w, numbers[k], m, rectByPlace)
                            doneSomething = true
                        } else {
                            m++
                        }
                    }
                }
            }

            if (!doneSomething) break
        }

        var ret = 1
        for (i in 0 until nrects) {
            val n = positions[i].size
            if (n <= 0) {
                ret = 0
            } else if (n > 1) {
                ret = 2
            } else if (hedge != null && vedge != null) {
                val r = positions[i][0]
                for (y in 0 until r.h) {
                    if (r.x > 0) vedge[(r.y + y) * w + r.x] = 1
                    if (r.x + r.w < w) vedge[(r.y + y) * w + r.x + r.w] = 1
                }
                for (x in 0 until r.w) {
                    if (r.y > 0) hedge[r.y * w + r.x + x] = 1
                    if (r.y + r.h < h) hedge[(r.y + r.h) * w + r.x + x] = 1
                }
            }
        }
        return ret
    }

    // ------------------------------------------------------------------
    // Generator, `rect.c:932-1783`.
    // ------------------------------------------------------------------

    /** `place_rect`, `rect.c:1043-1056`. */
    private fun placeRect(w: Int, grid: IntArray, r: Placement) {
        val idx = r.y * w + r.x
        for (x in r.x until r.x + r.w) {
            for (y in r.y until r.y + r.h) {
                grid[y * w + x] = idx
            }
        }
    }

    /** `find_rect`, `rect.c:1058-1095`. */
    private fun findRect(w: Int, h: Int, grid: IntArray, x0: Int, y0: Int): Placement {
        val idx = grid[y0 * w + x0]
        if (idx < 0) return Placement(x0, y0, 1, 1)
        val y = idx / w
        val x = idx % w
        var width = 1
        while (x + width < w && grid[y * w + x + width] == idx) width++
        var height = 1
        while (y + height < h && grid[(y + height) * w + x] == idx) height++
        return Placement(x, y, width, height)
    }

    /**
     * `enum_rects`, `rect.c:932-1041`. When [want] is negative the call
     * only counts; otherwise it returns the placement at that index.
     */
    private fun enumRects(
        w: Int,
        h: Int,
        grid: IntArray,
        want: Int,
        sx: Int,
        sy: Int,
        scratch: IntArray,
    ): Pair<Placement?, Int> {
        var maxArea = w * h / 6
        if (maxArea < 2) maxArea = 2

        for (dy in intArrayOf(-1, 1)) {
            val base = if (dy == -1) 0 else w
            for (dx in intArrayOf(-1, 1)) {
                var x = sx
                while (x in 0 until w) {
                    scratch[base + x] = -2 * h * dy
                    var y = sy
                    while (y in 0 until h) {
                        if (grid[y * w + x] == -1 &&
                            (x == sx || dy * y <= dy * scratch[base + x - dx])
                        ) {
                            scratch[base + x] = y
                        } else {
                            break
                        }
                        y += dy
                    }
                    x += dx
                }
            }
        }

        var realMaxArea = 0
        for (x in 0 until w) {
            val rh = scratch[w + x] - scratch[x] + 1
            if (rh <= 0) continue
            val dx = if (x > sx) -1 else 1
            var x2 = x
            while (x2 in 0 until w) {
                if (scratch[w + x2] < scratch[w + x] || scratch[x2] > scratch[x]) break
                x2 += dx
            }
            val rw = abs(x2 - x)
            if (realMaxArea < rw * rh) realMaxArea = rw * rh
        }
        if (realMaxArea > maxArea) realMaxArea = maxArea

        var mw = w - 1
        if (mw < 3) mw++
        var mh = h - 1
        if (mh < 3) mh++

        var index = 0
        for (rw in 1..mw) {
            for (rh in 1..mh) {
                if (rw * rh > realMaxArea) continue
                if (rw * rh == 1) continue
                val xFrom = maxOf(sx - rw + 1, 0)
                val xTo = minOf(sx, w - rw)
                for (x in xFrom..xTo) {
                    val yFrom = maxOf(sy - rh + 1, 0)
                    val yTo = minOf(sy, h - rh)
                    for (y in yFrom..yTo) {
                        if (scratch[x] <= y && scratch[x + rw - 1] <= y &&
                            scratch[w + x] >= y + rh - 1 &&
                            scratch[w + x + rw - 1] >= y + rh - 1
                        ) {
                            if (want >= 0 && index == want) {
                                return Placement(x, y, rw, rh) to index
                            }
                            index++
                        }
                    }
                }
            }
        }
        return null to index
    }

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        var pw = p.w
        var ph = p.h

        while (true) {
            var p2w = (pw / (1.0f + p.expandFactor)).toInt()
            if (p2w < 2 && pw >= 2) p2w = 2
            var p2h = (ph / (1.0f + p.expandFactor)).toInt()
            if (p2h < 2 && ph >= 2) p2h = 2

            /*
             * A side of 1 with any positive expansion scales to 0.
             * C then reaches `random_upto(rs, -1)` and aborts with
             * "allocation too large"; the port refuses the input
             * before it allocates (D-022).
             */
            if (p2w < 1 || p2h < 1) {
                throw IllegalArgumentException(
                    "Expansion factor leaves an empty base grid",
                )
            }

            var grid = IntArray(p2w * p2h) { -1 }
            var nsquares = p2w * p2h
            val scratch = IntArray(2 * p2w)

            while (nsquares > 0) {
                var square = random.upto(nsquares.toLong()).toInt()
                var sx = -1
                var sy = -1
                loop@ for (y in 0 until p2h) {
                    for (x in 0 until p2w) {
                        if (grid[y * p2w + x] == -1 && square-- == 0) {
                            sx = x
                            sy = y
                            break@loop
                        }
                    }
                }
                check(sx >= 0 && sy >= 0) { "the random square was not found" }

                val (_, count) = enumRects(p2w, p2h, grid, -1, sx, sy, scratch)
                if (count == 0) {
                    grid[sy * p2w + sx] = -2
                    nsquares--
                } else {
                    val pick = random.upto(count.toLong()).toInt()
                    val (rect, _) = enumRects(p2w, p2h, grid, pick, sx, sy, scratch)
                    val placement = requireNotNull(rect)
                    placeRect(p2w, grid, placement)
                    nsquares -= placement.w * placement.h
                }
            }

            // Remove singleton spaces, `rect.c:1259-1500`.
            for (x in 0 until p2w) {
                for (y in 0 until p2h) {
                    if (grid[y * p2w + x] >= 0) continue
                    val dirs = IntArray(4)
                    var ndirs = 0
                    if (x < p2w - 1) {
                        val r1 = findRect(p2w, p2h, grid, x + 1, y)
                        if ((r1.w * r1.h > 2 && (r1.y == y || r1.y + r1.h - 1 == y)) || r1.h == 1) {
                            dirs[ndirs++] = 1
                        }
                    }
                    if (y > 0) {
                        val r1 = findRect(p2w, p2h, grid, x, y - 1)
                        if ((r1.w * r1.h > 2 && (r1.x == x || r1.x + r1.w - 1 == x)) || r1.w == 1) {
                            dirs[ndirs++] = 2
                        }
                    }
                    if (x > 0) {
                        val r1 = findRect(p2w, p2h, grid, x - 1, y)
                        if ((r1.w * r1.h > 2 && (r1.y == y || r1.y + r1.h - 1 == y)) || r1.h == 1) {
                            dirs[ndirs++] = 4
                        }
                    }
                    if (y < p2h - 1) {
                        val r1 = findRect(p2w, p2h, grid, x, y + 1)
                        if ((r1.w * r1.h > 2 && (r1.x == x || r1.x + r1.w - 1 == x)) || r1.w == 1) {
                            dirs[ndirs++] = 8
                        }
                    }

                    if (ndirs > 0) {
                        val which = random.upto(ndirs.toLong()).toInt()
                        val dir = dirs[which]
                        var r1: Placement
                        val r2: Placement
                        when (dir) {
                            1 -> {
                                r1 = findRect(p2w, p2h, grid, x + 1, y)
                                r2 = Placement(x, y, 1 + r1.w, 1)
                                if (r1.y == y) r1 = Placement(r1.x, r1.y + 1, r1.w, r1.h - 1)
                                else r1 = Placement(r1.x, r1.y, r1.w, r1.h - 1)
                            }
                            2 -> {
                                r1 = findRect(p2w, p2h, grid, x, y - 1)
                                r2 = Placement(x, r1.y, 1, 1 + r1.h)
                                if (r1.x == x) r1 = Placement(r1.x + 1, r1.y, r1.w - 1, r1.h)
                                else r1 = Placement(r1.x, r1.y, r1.w - 1, r1.h)
                            }
                            4 -> {
                                r1 = findRect(p2w, p2h, grid, x - 1, y)
                                r2 = Placement(r1.x, y, 1 + r1.w, 1)
                                if (r1.y == y) r1 = Placement(r1.x, r1.y + 1, r1.w, r1.h - 1)
                                else r1 = Placement(r1.x, r1.y, r1.w, r1.h - 1)
                            }
                            else -> {
                                r1 = findRect(p2w, p2h, grid, x, y + 1)
                                r2 = Placement(x, y, 1, 1 + r1.h)
                                if (r1.x == x) r1 = Placement(r1.x + 1, r1.y, r1.w - 1, r1.h)
                                else r1 = Placement(r1.x, r1.y, r1.w - 1, r1.h)
                            }
                        }
                        if (r1.h > 0 && r1.w > 0) placeRect(p2w, grid, r1)
                        placeRect(p2w, grid, r2)
                    } else {
                        check(x > 0 && x < p2w - 1 && y > 0 && y < p2h - 1) {
                            "a singleton without an extension needs a 3x3 box"
                        }
                        for (xx in x - 1..x + 1) {
                            for (yy in y - 1..y + 1) {
                                val r1 = findRect(p2w, p2h, grid, xx, yy)
                                check(r1.x >= x - 1 && r1.y >= y - 1 &&
                                    r1.x + r1.w - 1 <= x + 1 && r1.y + r1.h - 1 <= y + 1) {
                                    "the surrounding rectangles meet the 3x3 box"
                                }
                            }
                        }
                        placeRect(p2w, grid, Placement(x - 1, y - 1, 3, 3))
                    }
                }
            }

            // Expand to the requested size, `rect.c:1444-1626`.
            repeat(2) {
                val p3w = p2w
                val p3h = ph
                val grid2 = IntArray(p2w * ph)
                val expand = IntArray(p2h - 1)
                val where = IntArray(p2w)
                for (y in 0 until p2h - 1) expand[y] = 0
                for (y in p2h until ph) {
                    val x = random.upto((p2h - 1).toLong()).toInt()
                    expand[x]++
                }

                var y2 = 0
                var y2last = 0
                for (y in 0 until p2h) {
                    for (x in 0 until p2w) {
                        val v = grid[y * p2w + x]
                        grid2[y2 * p3w + x] = if (v / p2w == y &&
                            (y2 == 0 || grid2[(y2 - 1) * p3w + x] / p3w < y2last)
                        ) {
                            (v % p2w) + y2 * p3w
                        } else {
                            grid2[(y2 - 1) * p3w + x]
                        }
                    }
                    y2++
                    if (y2 == p3h) break
                    y2last = y2

                    var yx = -1
                    for (x in 0 until p2w) {
                        if (grid[y * p2w + x] != grid[(y + 1) * p2w + x]) {
                            if (x == 0 ||
                                (grid[y * p2w + x - 1] != grid[y * p2w + x] &&
                                    grid[(y + 1) * p2w + x - 1] != grid[(y + 1) * p2w + x])
                            ) {
                                yx = random.upto((expand[y] + 1).toLong()).toInt()
                            }
                        } else {
                            yx = -1
                        }
                        where[x] = yx
                    }

                    for (extra in 0 until expand[y]) {
                        for (x in 0 until p2w) {
                            grid2[y2 * p3w + x] = if (extra == where[x]) {
                                var v = grid[(y + 1) * p2w + x]
                                v %= p2w
                                v + y2 * p3w
                            } else {
                                grid2[(y2 - 1) * p3w + x]
                            }
                        }
                        y2++
                    }
                }

                val newW = p3h
                val newH = p3w
                val transposed = IntArray(newW * newH)
                for (x in 0 until newW) {
                    for (y in 0 until newH) {
                        var v = grid2[x * p3w + y]
                        v = (v % p3w) * newW + (v / p3w)
                        transposed[y * newW + x] = v
                    }
                }
                grid = transposed
                p2w = newW
                p2h = newH
                val tmp = pw
                pw = ph
                ph = tmp
            }

            // Count the rectangles and prepare the number candidates.
            var nnumbers = 0
            for (y in 0 until ph) {
                for (x in 0 until pw) {
                    if (grid[y * pw + x] == y * pw + x) nnumbers++
                }
            }
            val numbers = ArrayList<NumberData>(nnumbers)
            for (y in 0 until ph) {
                for (x in 0 until pw) {
                    if (grid[y * pw + x] != y * pw + x) continue
                    val r = findRect(pw, ph, grid, x, y)
                    val points = ArrayList<Int>(r.w * r.h)
                    for (j in 0 until r.h) {
                        for (k in 0 until r.w) {
                            points += (r.y + j) * pw + (r.x + k)
                        }
                    }
                    numbers += NumberData(r.w * r.h, points)
                }
            }

            val ret = if (p.unique) {
                rectSolver(pw, ph, numbers.toTypedArray(), null, null, random)
            } else {
                1
            }

            if (ret == 1) {
                val placed = IntArray(pw * ph)
                for (nd in numbers) {
                    val idx = random.upto(nd.points.size.toLong()).toInt()
                    placed[nd.points[idx]] = nd.area
                }

                val aux = StringBuilder("S")
                for (y in 0 until ph) {
                    for (x in 1 until pw) {
                        aux.append(if (grid[y * pw + x] != grid[y * pw + x - 1]) '1' else '0')
                    }
                }
                for (y in 1 until ph) {
                    for (x in 0 until pw) {
                        aux.append(if (grid[y * pw + x] != grid[(y - 1) * pw + x]) '1' else '0')
                    }
                }

                val desc = StringBuilder()
                var run = 0
                for (i in 0..pw * ph) {
                    val n = if (i < pw * ph) placed[i] else -1
                    if (n == 0) {
                        run++
                    } else {
                        if (run > 0) {
                            while (run > 0) {
                                var c = 'a'.code - 1 + run
                                if (run > 26) c = 'z'.code
                                desc.append(c.toChar())
                                run -= c - ('a'.code - 1)
                            }
                        } else if (desc.isNotEmpty() && n > 0) {
                            desc.append('_')
                        }
                        if (n > 0) desc.append(n)
                        run = 0
                    }
                }
                return Description(desc.toString(), aux.toString())
            }
        }
    }

    // ------------------------------------------------------------------
    // UI and input, `rect.c:2197-2640`.
    // ------------------------------------------------------------------

    /** `from_coord` from a view coordinate, `rect.c:79`. */
    private fun fromCoord(value: Int, tileSize: Int): Float =
        (value - BORDER).toFloat() / tileSize

    /** `coord_round`, `rect.c:2231-2316`. */
    private fun coordRound(x: Float, y: Float, out: IntArray) {
        val xs = kotlin.math.floor(x) + 0.5f
        val ys = kotlin.math.floor(y) + 0.5f
        val xv = kotlin.math.floor(x + 0.5f)
        val yv = kotlin.math.floor(y + 0.5f)

        var dx = abs(x - xv)
        var dy = abs(y - yv)
        var dist = maxOf(dx, dy)
        if (dist < CORNER_TOLERANCE) {
            out[0] = 2 * xv.toInt()
            out[1] = 2 * yv.toInt()
            return
        }

        dx = abs(x - xs)
        dy = abs(y - ys)
        dist = maxOf(dx, dy)
        if (dist < CENTRE_TOLERANCE) {
            out[0] = 1 + 2 * xs.toInt()
            out[1] = 1 + 2 * ys.toInt()
            return
        }

        if (dx > dy) {
            out[0] = 2 * xv.toInt()
            out[1] = 1 + 2 * kotlin.math.floor(ys).toInt()
        } else {
            out[0] = 1 + 2 * kotlin.math.floor(xs).toInt()
            out[1] = 2 * yv.toInt()
        }
    }

    /** `move_cursor` with `wrap = false`, `misc.c:366-391`. */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
        val dx: Int
        val dy: Int
        when (button) {
            Buttons.CURSOR_UP -> { dx = 0; dy = -1 }
            Buttons.CURSOR_DOWN -> { dx = 0; dy = 1 }
            Buttons.CURSOR_LEFT -> { dx = -1; dy = 0 }
            Buttons.CURSOR_RIGHT -> { dx = 1; dy = 0 }
            else -> return MoveResult.Unused
        }
        val ox = ui.curX
        val oy = ui.curY
        ui.curX = minOf(maxOf(ui.curX + dx, 0), w - 1)
        ui.curY = minOf(maxOf(ui.curY + dy, 0), h - 1)
        if (!ui.curVisible) {
            ui.curVisible = true
            return MoveResult.UiUpdate
        }
        if (ui.curX != ox || ui.curY != oy) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /**
     * `grid_draw_rect`, `rect.c:2318-2361`: writes the rectangle's
     * outline or interior edges into a copy of the edge arrays.
     */
    private fun gridDrawRect(
        state: State,
        hedge: IntArray,
        vedge: IntArray,
        c: Int,
        really: Boolean,
        outline: Boolean,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): Boolean {
        var changed = false
        for (x in x1 until x2) {
            for (y in y1..y2) {
                if (!state.hRange(x, y)) continue
                var value = hedge[y * state.w + x]
                if (y == y1 || y == y2) {
                    if (!outline) continue
                    value = c
                } else if (c == 1) {
                    value = 0
                }
                changed = changed || hedge[y * state.w + x] != value
                if (really) hedge[y * state.w + x] = value
            }
        }
        for (y in y1 until y2) {
            for (x in x1..x2) {
                if (!state.vRange(x, y)) continue
                var value = vedge[y * state.w + x]
                if (x == x1 || x == x2) {
                    if (!outline) continue
                    value = c
                } else if (c == 1) {
                    value = 0
                }
                changed = changed || vedge[y * state.w + x] != value
                if (really) vedge[y * state.w + x] = value
            }
        }
        return changed
    }

    private fun uiDrawRect(
        state: State,
        ui: Ui,
        hedge: IntArray,
        vedge: IntArray,
        c: Int,
        really: Boolean,
        outline: Boolean,
    ): Boolean = gridDrawRect(
        state,
        hedge,
        vedge,
        c,
        really,
        outline,
        ui.x1,
        ui.y1,
        ui.x2,
        ui.y2,
    )

    override fun newUi(state: Game.State): Game.Ui {
        val ui = Ui()
        ui.reset()
        ui.erasing = false
        ui.curX = 0
        ui.curY = 0
        ui.curVisible = false
        ui.curDragging = false
        return ui
    }

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val n = newState as State
        return n.completed && !n.cheated && oldState != null && !(oldState as State).completed
    }

    /**
     * `game_request_keys` is NULL, so the midend presets the arrow mode
     * and no labels are returned. The full-cursor hint forces
     * `ANDROID_ARROWS_ONLY`, as for every game without the callback.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `current_key_label`, `rect.c:2383-2396`, as a tested helper. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.curVisible && !(u.dragStartX >= 0 && !u.curDragging)
        ) {
            if (u.curDragging) {
                if (!u.dragged) return "Cancel"
                if ((button == Buttons.CURSOR_SELECT2) == u.erasing) return "Done"
                return "Cancel"
            }
            return if (button == Buttons.CURSOR_SELECT) "Mark" else "Erase"
        }
        return ""
    }

    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val from = state as State
        val u = ui as Ui
        val xy = IntArray(2)
        var startDrag = false
        var endDrag = false
        var active = false
        var erasing = false

        val btn = Buttons.stripModifiers(button)
        coordRound(fromCoord(x, tileSize), fromCoord(y, tileSize), xy)

        if (btn == Buttons.LEFT_BUTTON || btn == Buttons.RIGHT_BUTTON) {
            if (u.dragStartX >= 0 && u.curDragging) u.reset()
            startDrag = true
            u.curVisible = false
            u.curDragging = false
            active = true
            erasing = btn == Buttons.RIGHT_BUTTON
        } else if (btn == Buttons.LEFT_RELEASE || btn == Buttons.RIGHT_RELEASE) {
            if (u.curVisible) {
                u.curVisible = false
                active = true
            }
            check(!u.curDragging) { "a mouse release during a keyboard drag" }
            endDrag = true
            erasing = btn == Buttons.RIGHT_RELEASE
        } else if (Buttons.isCursorMove(btn)) {
            val ret = moveCursor(btn, u, from.w, from.h)
            active = true
            if (!u.curDragging || ret != MoveResult.UiUpdate) return ret
            coordRound(u.curX + 0.5f, u.curY + 0.5f, xy)
        } else if (btn == Buttons.CURSOR_SELECT || btn == Buttons.CURSOR_SELECT2) {
            if (u.dragStartX >= 0 && !u.curDragging) return MoveResult.Unused
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            coordRound(u.curX + 0.5f, u.curY + 0.5f, xy)
            erasing = btn == Buttons.CURSOR_SELECT2
            if (u.curDragging) {
                u.curDragging = false
                endDrag = true
                active = true
            } else {
                u.curDragging = true
                startDrag = true
                active = true
            }
        } else if (btn == '\b'.code || btn == 27) {
            if (!u.curDragging) {
                u.curVisible = false
            } else {
                u.reset()
                u.curDragging = false
            }
            return MoveResult.UiUpdate
        } else if (btn != Buttons.LEFT_DRAG && btn != Buttons.RIGHT_DRAG) {
            return MoveResult.Unused
        }

        var xc = xy[0]
        var yc = xy[1]

        if (startDrag && xc >= 0 && xc <= 2 * from.w && yc >= 0 && yc <= 2 * from.h) {
            u.dragStartX = xc
            u.dragStartY = yc
            u.dragEndX = -1
            u.dragEndY = -1
            u.dragged = false
            u.erasing = erasing
            active = true
        }

        if (u.dragStartX >= 0 && (xc != u.dragEndX || yc != u.dragEndY)) {
            if (u.dragEndX != -1 && u.dragEndY != -1) u.dragged = true
            u.dragEndX = xc
            u.dragEndY = yc
            active = true

            if (xc >= 0 && xc <= 2 * from.w && yc >= 0 && yc <= 2 * from.h) {
                u.x1 = u.dragStartX
                u.x2 = u.dragEndX
                if (u.x2 < u.x1) {
                    val t = u.x1
                    u.x1 = u.x2
                    u.x2 = t
                }
                u.y1 = u.dragStartY
                u.y2 = u.dragEndY
                if (u.y2 < u.y1) {
                    val t = u.y1
                    u.y1 = u.y2
                    u.y2 = t
                }
                u.x1 /= 2
                u.x2 = (u.x2 + 1) / 2
                u.y1 /= 2
                u.y2 = (u.y2 + 1) / 2
            } else {
                u.x1 = -1
                u.y1 = -1
                u.x2 = -1
                u.y2 = -1
            }
        }

        var move: String? = null
        if (endDrag && u.dragStartX >= 0) {
            if (xc >= 0 && xc <= 2 * from.w && yc >= 0 && yc <= 2 * from.h &&
                erasing == u.erasing
            ) {
                if (u.dragged) {
                    if (uiDrawRect(from, u, from.hedge, from.vedge, 1, false, !u.erasing)) {
                        move = "${if (u.erasing) 'E' else 'R'}${u.x1},${u.y1}," +
                            "${u.x2 - u.x1},${u.y2 - u.y1}"
                    }
                } else {
                    if ((xc and 1) != 0 && (yc and 1) == 0 &&
                        from.hRange(xc / 2, yc / 2)
                    ) {
                        move = "H${xc / 2},${yc / 2}"
                    }
                    if ((yc and 1) != 0 && (xc and 1) == 0 &&
                        from.vRange(xc / 2, yc / 2)
                    ) {
                        move = "V${xc / 2},${yc / 2}"
                    }
                }
            }
            u.reset()
            active = true
        }

        return when {
            move != null -> MoveResult.Move(move)
            active -> MoveResult.UiUpdate
            else -> MoveResult.Unused
        }
    }

    /**
     * C `sscanf` for [count] comma-separated integers. A literal comma
     * does not skip whitespace, and trailing text is ignored.
     */
    private fun scanInts(text: String, count: Int): IntArray? {
        val out = IntArray(count)
        var i = 0
        for (k in 0 until count) {
            while (i < text.length && isCSpace(text[i])) i++
            var negative = false
            if (i < text.length && (text[i] == '+' || text[i] == '-')) {
                negative = text[i] == '-'
                i++
            }
            val start = i
            var value = 0L
            var overflow = false
            while (i < text.length && text[i] in '0'..'9') {
                val digit = (text[i] - '0').toLong()
                if (!overflow) {
                    if (value > (Int.MAX_VALUE.toLong() + 1L - digit) / 10L) overflow = true
                    else value = value * 10L + digit
                }
                i++
            }
            if (i == start) return null
            val signed = if (negative) -value else value
            out[k] = if (overflow) {
                if (negative) Int.MIN_VALUE else Int.MAX_VALUE
            } else {
                signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
            if (k < count - 1) {
                if (i >= text.length || text[i] != ',') return null
                i++
            }
        }
        return out
    }

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        if (move.startsWith("S")) {
            val ret = from.copy() as State
            ret.cheated = true
            var p = 1
            for (y in 0 until ret.h) {
                for (x in 1 until ret.w) {
                    ret.vedge[y * ret.w + x] = if (p < move.length && move[p] == '1') 1 else 0
                    if (p < move.length) p++
                }
            }
            for (y in 1 until ret.h) {
                for (x in 0 until ret.w) {
                    ret.hedge[y * ret.w + x] = if (p < move.length && move[p] == '1') 1 else 0
                    if (p < move.length) p++
                }
            }
            // `execute_move` returns from the S branch before its
            // completion check, so a cheated solve is never marked
            // completed; the status text shows `Auto-solved.` instead.
            return recomputeCorrect(ret)
        }

        if (move.isEmpty()) return null
        val mode = move[0]
        if ((mode == 'R' || mode == 'E')) {
            val values = scanInts(move.substring(1), 4) ?: return null
            val x1 = values[0]
            val y1 = values[1]
            var x2 = values[2]
            var y2 = values[3]
            if (x1 < 0 || x2 < 0 || x1 + x2 > from.w || y1 < 0 || y2 < 0 || y1 + y2 > from.h) {
                return null
            }
            x2 += x1
            y2 += y1
            val ret = from.copy() as State
            gridDrawRect(
                ret,
                ret.hedge,
                ret.vedge,
                1,
                true,
                mode == 'R',
                x1,
                y1,
                x2,
                y2,
            )
            return finishMove(ret)
        }
        if (mode == 'H' || mode == 'V') {
            val values = scanInts(move.substring(1), 2) ?: return null
            val x1 = values[0]
            val y1 = values[1]
            val valid = if (mode == 'H') from.hRange(x1, y1) else from.vRange(x1, y1)
            if (!valid) return null
            val ret = from.copy() as State
            if (mode == 'H') ret.hedge[y1 * ret.w + x1] = 1 - ret.hedge[y1 * ret.w + x1]
            else ret.vedge[y1 * ret.w + x1] = 1 - ret.vedge[y1 * ret.w + x1]
            return finishMove(ret)
        }
        return null
    }

    /** The S branch of `execute_move` only recomputes correctness. */
    private fun recomputeCorrect(state: State): State {
        getCorrect(state).copyInto(state.correct)
        return state
    }

    /** The tail of `execute_move`: recompute correctness and completion. */
    private fun finishMove(state: State): State {
        val ret = state
        val correct = getCorrect(ret)
        correct.copyInto(ret.correct)
        if (!ret.completed) {
            if (ret.correct.all { it != 0 }) ret.completed = true
        }
        return ret
    }

    // ------------------------------------------------------------------
    // Geometry and palette, `rect.c:2642-2695`.
    // ------------------------------------------------------------------

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(p.w * tileSize + 2 * BORDER + 1, p.h * tileSize + 2 * BORDER + 1)
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the clue number, then the interior lines drawn
     * on the cell's four sides. The pointer move of this game is an
     * edge toggle at an edge midpoint, not at a cell centre, so a cell
     * offers no action; the edge toggles stay on the key path, and the
     * own-model edge units are a later wave.
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
            width = s.w,
            height = s.h,
            tileSize = t,
            left = BORDER,
            top = BORDER,
            summary = statusText(state, ui),
            text = { x, y ->
                val clue = s.grid[y * s.w + x]
                val lines = buildList {
                    if (y > 0 && s.hedge[y * s.w + x] != 0) add("top")
                    if (y < s.h - 1 && s.hedge[(y + 1) * s.w + x] != 0) add("bottom")
                    if (x > 0 && s.vedge[y * s.w + x] != 0) add("left")
                    if (x < s.w - 1 && s.vedge[y * s.w + x + 1] != 0) add("right")
                }
                val content = buildList {
                    if (clue > 0) add("clue $clue")
                    if (lines.isNotEmpty()) add("lines ${lines.joinToString(", ")}")
                }.joinToString(", ").ifEmpty { "empty" }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) ", cursor" else ""
                content + cursor
            },
        )
        val cursor = cells.units.getOrNull(u.curY * s.w + u.curX)
        return cells.capped(cursor)
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(3 * NCOLOURS)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) ret[COL_GRID * 3 + i] = 0.5f * background[i]
        ret[COL_DRAG * 3] = 1.0f
        ret[COL_DRAG * 3 + 1] = 0.0f
        ret[COL_DRAG * 3 + 2] = 0.0f
        ret[COL_DRAGERASE * 3] = 0.2f
        ret[COL_DRAGERASE * 3 + 1] = 0.2f
        ret[COL_DRAGERASE * 3 + 2] = 1.0f
        for (i in 0 until 3) ret[COL_CORRECT * 3 + i] = 0.75f * background[i]
        for (i in 0 until 3) {
            ret[COL_LINE * 3 + i] = 0.0f
            ret[COL_TEXT * 3 + i] = 0.0f
        }
        ret[COL_CURSOR * 3] = 1.0f
        ret[COL_CURSOR * 3 + 1] = 0.5f
        ret[COL_CURSOR * 3 + 2] = 0.5f
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
        return ds
    }

    // ------------------------------------------------------------------
    // Drawing, `rect.c:2697-2886`.
    // ------------------------------------------------------------------

    private fun coord(value: Int, tileSize: Int): Int = value * tileSize + BORDER

    private fun colourOf(value: Int): Int = when (value) {
        1 -> COL_LINE
        2 -> COL_DRAG
        else -> COL_DRAGERASE
    }

    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        state: State,
        x: Int,
        y: Int,
        hedge: IntArray,
        vedge: IntArray,
        corners: IntArray,
        bgFlags: Long,
    ) {
        val tile = ds.tileSize
        val cx = coord(x, tile)
        val cy = coord(y, tile)

        sink.rect(cx, cy, tile + 1, tile + 1, COL_GRID)
        sink.rect(
            cx + 1,
            cy + 1,
            tile - 1,
            tile - 1,
            if ((bgFlags and CURSOR_BIT) != 0L) COL_CURSOR
            else if ((bgFlags and CORRECT_BIT) != 0L) COL_CORRECT
            else COL_BACKGROUND,
        )

        val number = state.grid[y * state.w + x]
        if (number != 0) {
            sink.text(
                cx + tile / 2,
                cy + tile / 2,
                Text.FONT_VARIABLE,
                tile / 2,
                Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                COL_TEXT,
                number.toString(),
            )
        }

        if (!state.hRange(x, y) || hedge[y * state.w + x] != 0) {
            sink.rect(
                cx,
                cy,
                tile + 1,
                2,
                if (state.hRange(x, y)) colourOf(hedge[y * state.w + x]) else COL_LINE,
            )
        }
        if (!state.hRange(x, y + 1) || hedge[(y + 1) * state.w + x] != 0) {
            sink.rect(
                cx,
                cy + tile - 1,
                tile + 1,
                2,
                if (state.hRange(x, y + 1)) colourOf(hedge[(y + 1) * state.w + x])
                else COL_LINE,
            )
        }
        if (!state.vRange(x, y) || vedge[y * state.w + x] != 0) {
            sink.rect(
                cx,
                cy,
                2,
                tile + 1,
                if (state.vRange(x, y)) colourOf(vedge[y * state.w + x]) else COL_LINE,
            )
        }
        if (!state.vRange(x + 1, y) || vedge[y * state.w + x + 1] != 0) {
            sink.rect(
                cx + tile - 1,
                cy,
                2,
                tile + 1,
                if (state.vRange(x + 1, y)) colourOf(vedge[y * state.w + x + 1])
                else COL_LINE,
            )
        }

        if (corners[y * state.w + x] != 0) {
            sink.rect(cx, cy, 2, 2, colourOf(corners[y * state.w + x]))
        }
        if (x + 1 < state.w && corners[y * state.w + x + 1] != 0) {
            sink.rect(cx + tile - 1, cy, 2, 2, colourOf(corners[y * state.w + x + 1]))
        }
        if (y + 1 < state.h && corners[(y + 1) * state.w + x] != 0) {
            sink.rect(cx, cy + tile - 1, 2, 2, colourOf(corners[(y + 1) * state.w + x]))
        }
        if (x + 1 < state.w && y + 1 < state.h &&
            corners[(y + 1) * state.w + x + 1] != 0
        ) {
            sink.rect(
                cx + tile - 1,
                cy + tile - 1,
                2,
                2,
                colourOf(corners[(y + 1) * state.w + x + 1]),
            )
        }

        sink.update(cx, cy, tile + 1, tile + 1)
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
        val w = s.w
        val h = s.h
        val tile = ds.tileSize

        val hedge: IntArray
        val vedge: IntArray
        if (u.dragged) {
            hedge = s.hedge.copyOf()
            vedge = s.vedge.copyOf()
            uiDrawRect(s, u, hedge, vedge, if (u.erasing) 3 else 2, true, true)
        } else {
            hedge = s.hedge
            vedge = s.vedge
        }

        val corners = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                if (x > 0) {
                    val e = vedge[y * w + x]
                    if (corners[y * w + x] < e) corners[y * w + x] = e
                    if (y + 1 < h && corners[(y + 1) * w + x] < e) {
                        corners[(y + 1) * w + x] = e
                    }
                }
                if (y > 0) {
                    val e = hedge[y * w + x]
                    if (corners[y * w + x] < e) corners[y * w + x] = e
                    if (x + 1 < w && corners[y * w + x + 1] < e) {
                        corners[y * w + x + 1] = e
                    }
                }
            }
        }

        if (!ds.started) {
            sink.rect(
                coord(0, tile) - 1,
                coord(0, tile) - 1,
                w * tile + 3,
                h * tile + 3,
                COL_LINE,
            )
            ds.started = true
            sink.update(0, 0, w * tile + 2 * BORDER + 1, h * tile + 2 * BORDER + 1)
        }

        for (x in 0 until w) {
            for (y in 0 until h) {
                var c = 0L
                if (s.hRange(x, y)) c = c or hedge[y * w + x].toLong()
                if (s.hRange(x, y + 1)) c = c or (hedge[(y + 1) * w + x].toLong() shl 2)
                if (s.vRange(x, y)) c = c or (vedge[y * w + x].toLong() shl 4)
                if (s.vRange(x + 1, y)) c = c or (vedge[y * w + x + 1].toLong() shl 6)
                c = c or (corners[y * w + x].toLong() shl 8)
                if (x + 1 < w) c = c or (corners[y * w + x + 1].toLong() shl 10)
                if (y + 1 < h) c = c or (corners[(y + 1) * w + x].toLong() shl 12)
                if (x + 1 < w && y + 1 < h) {
                    c = c or (corners[(y + 1) * w + x + 1].toLong() shl 14)
                }
                if (s.correct[y * w + x] != 0) c = c or CORRECT_BIT
                if (u.curVisible && u.curX == x && u.curY == y) c = c or CURSOR_BIT

                if (ds.visible[y * w + x] != c) {
                    drawTile(sink, ds, s, x, y, hedge, vedge, corners, c)
                    ds.visible[y * w + x] = c
                }
            }
        }

        sink.status(statusText(s, u))
    }

    /** `game_flash_length`, `rect.c:2893-2900`; the seam has no clock. */
    @Suppress("unused")
    private fun flashLength(oldState: State, newState: State): Float =
        if (!oldState.completed && newState.completed && !oldState.cheated && !newState.cheated) {
            FLASH_TIME
        } else {
            0.0f
        }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val u = ui as Ui
        val out = StringBuilder()
        if (u.dragged && u.x1 >= 0 && u.y1 >= 0 && u.x2 >= 0 && u.y2 >= 0) {
            out.append("${u.x2 - u.x1}x${u.y2 - u.y1} ")
        }
        if (s.cheated) out.append("Auto-solved.")
        else if (s.completed) out.append("COMPLETED!")
        return out.toString()
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        return Game.CursorRect(
            coord(u.curX, tileSize),
            coord(u.curY, tileSize),
            tileSize,
            tileSize,
        )
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
