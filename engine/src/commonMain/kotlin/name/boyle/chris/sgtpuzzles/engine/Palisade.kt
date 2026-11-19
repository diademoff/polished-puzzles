package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `palisade.c`: Nikoli's `Five Cells`.
 *
 * The player draws grid edges until the board divides into connected
 * regions of `k` cells, and every clue equals the number of drawn edges
 * around its cell.
 *
 * Three parts are coupled:
 *
 * - the clue generator is gated by the solver, and the winnow loop
 *   removes each clue only while the solver still finishes the board;
 * - the RNG order is: shuffle the winnow order, then divide the board,
 *   then retry only the division when the solver gate fails;
 * - each cell stores two bits per edge: the drawn (ON) bit and the
 *   crossed-out (OFF) bit. Completion and the solver read only the ON
 *   bits.
 *
 * `solve_game` returns the stored `AUXINFO` verbatim, or runs the solver
 * from the rim when there is none. A generated game always has an
 * `AUXINFO`. There is no `UI` record, no key label, and no timer. The
 * completion flash is not ported, because the drawing seam has no flash
 * time; it does not change the state or the save file.
 */
object Palisade : Game {

    private const val PREFERRED_TILE_SIZE = 48

    /** `EMPTY`: a cell with no clue. */
    private const val EMPTY = -1

    /**
     * The generation area guard from `docs/DEVIATIONS.md` D-007.
     * Upstream accepts an area up to `Int.MAX_VALUE`, and generation
     * then tries to allocate tens of gigabytes. No preset is near this
     * limit.
     */
    internal const val MAX_AREA = 1 shl 20

    /**
     * The generation region guard from D-007: cost follows the region
     * count `w*h/k`, not the area. 25x20n10 is 50 regions and finishes
     * in 7.2 s on the measured phone; 30x20n10 is 60 regions and takes
     * 27 s.
     */
    internal const val MAX_REGIONS = 50

    private const val BORDER_U = 1
    private const val BORDER_R = 2
    private const val BORDER_D = 4
    private const val BORDER_L = 8
    private const val BORDER_MASK = BORDER_U or BORDER_R or BORDER_D or BORDER_L

    private val DX = intArrayOf(0, 1, 0, -1)
    private val DY = intArrayOf(-1, 0, 1, 0)
    private val BITCOUNT = intArrayOf(0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4)

    /** `COMPUTE_J`: the neighbor is computed from the cell and direction. */
    private const val COMPUTE_J = -1

    /** `BORDER_ERROR(x)` shifts an edge bit above the ON and OFF bits. */
    private const val BORDER_ERROR_SHIFT = 8
    private const val F_ERROR_CLUE = 1 shl 12
    private const val CONTAINS_CURSOR_SHIFT = 14

    /** Palette indices from `palisade.c:1243-1254`. */
    private const val COL_BACKGROUND = 0
    private const val COL_FLASH = 1
    private const val COL_GRID = 2
    private const val COL_CLUE = COL_GRID
    private const val COL_LINE_YES = COL_GRID
    private const val COL_LINE_MAYBE = 3
    private const val COL_LINE_NO = 4
    private const val COL_ERROR = 5
    private const val NCOLOURS = 6

    /** `DARKER` from `palisade.c:1258`. */
    private const val DARKER = 0.9f

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val k: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, k)
    }

    /**
     * The immutable clue board, the counterpart of `shared_state`. It is
     * shared by every state of a game and is a function of the
     * description.
     */
    class Shared internal constructor(
        val w: Int,
        val h: Int,
        val k: Int,
        /** The clue of each cell, or -1 for a cell with no clue. Do not modify. */
        val clues: IntArray,
    ) {
        val size: Int get() = w * h
    }

    /**
     * `struct game_state`. [borders] holds one byte per cell: bits 0-3
     * are the ON edges, bits 4-7 the OFF edges. [completed] is sticky.
     * [cheated] is set by an `S` move.
     */
    class State internal constructor(
        val shared: Shared,
        /** The border bytes, row-major. Do not modify. */
        val borders: ByteArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val width: Int get() = shared.w

        val height: Int get() = shared.h

        /** The border byte of a cell as an unsigned value. */
        fun borderAt(x: Int, y: Int): Int = borders[y * width + x].toInt() and 0xFF

        override fun copy(): Game.State = State(shared, borders.copyOf(), completed, cheated)

        override fun equals(other: Any?): Boolean =
            other is State &&
                shared.w == other.shared.w &&
                shared.h == other.shared.h &&
                shared.k == other.shared.k &&
                shared.clues.contentEquals(other.shared.clues) &&
                borders.contentEquals(other.borders) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = shared.w
            result = 31 * result + shared.h
            result = 31 * result + shared.k
            result = 31 * result + shared.clues.contentHashCode()
            result = 31 * result + borders.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. [x] and [y] are half-grid coordinates: (1,1) is
     * the center of the top-left cell, and a zero offset is a corner or
     * a grid line. The two preferences are the `cursor-mode` keyword
     * (`false` is Half-grid) and `clear-complete-regions`. They are not
     * serialized and are not part of the per-cell cache word.
     */
    class Ui : Game.Ui {
        var x: Int = 1
        var y: Int = 1
        var show: Boolean = false
        var legacyCursor: Boolean = false
        var clearCompleteRegions: Boolean = false
    }

    /**
     * `struct game_drawstate`: one flag word per cell and the `started`
     * flag that stands in for the lazily allocated C grid. The first
     * value is -1, which no real flag word can equal, so the first draw
     * paints every cell.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
        val tileSize: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val grid: IntArray = IntArray(width * height) { -1 }
    }

    /** The geometry of the cursor ring, from `get_cursor_location`. */
    data class CursorGeometry(
        val centerX: Int,
        val centerY: Int,
        val width: Int,
        val height: Int,
        val corners: Boolean,
    )

    /** One generated description and the internal retry counts. */
    internal data class Generation(
        val desc: String,
        val auxInfo: String,
        /** Divvy attempts across every solver-gate retry. */
        val divvyAttempts: Int,
        /** Rejected full-clue boards before the accepted one. */
        val solverRetries: Int,
    )

    override val name: String = "Palisade"
    override val backendName: String = "palisade"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    /**
     * The four presets from `palisade.c:73-77`. `palisade.c` has no
     * `SMALL_SCREEN` branch, so the app and the oracle expose the same
     * list.
     */
    private val presetList = listOf(
        Params(5, 5, 5),
        Params(8, 6, 6),
        Params(10, 8, 8),
        Params(15, 12, 10),
    )

    /** `default_params`: preset 0, `{5, 5, 5}`. */
    override fun defaultParams(): Game.Params = Params(5, 5, 5)

    /** `game_fetch_preset`: `%d x %d, regions of size %d`. */
    override fun presets(): List<Preset> = presetList.map {
        Preset("${it.w} x ${it.h}, regions of size ${it.k}", encodeParams(it, true))
    }

    /**
     * `decode_params` from `palisade.c:105-114`. All three fields start
     * as the first `atoi`, so a sign or a leading space hides the `x`.
     * Only a lower-case `x` and `n` are delimiters, and there is no
     * digit skip after the `n`.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = atoi(text, 0)
        var h = w
        var k = w
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            h = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && text[position] == 'n') {
            position++
            k = atoi(text, position)
        }
        return Params(w, h, k)
    }

    /** `encode_params` from `palisade.c:116-119`: `WxHnK`, `full` ignored. */
    @Suppress("UNUSED_PARAMETER")
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "${p.w}x${p.h}n${p.k}"
    }

    /**
     * `validate_params` from `palisade.c:164-185`, in C order. The size
     * and region guards in [MAX_AREA] and [MAX_REGIONS] are deliberately
     * not here: upstream accepts a huge area and a huge region count,
     * and D-007 rejects them only at generation.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.k < 1) return "Region size must be at least one"
        if (p.w < 1) return "Width must be at least one"
        if (p.h < 1) return "Height must be at least one"
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        val wh = p.w * p.h
        if (wh % p.k != 0) return "Region size must divide grid area"
        if (!full) return null

        if (p.k == wh) return "Region size must be less than the grid area"
        check(p.k < wh)
        if (p.k == 2 && p.w != 1 && p.h != 1) {
            return "Region size can't be two unless width or height is one"
        }
        return null
    }

    /** `game_configure` (`palisade.c:124-144`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Text("Region size", p.k.toString()),
        )
    }

    /** `custom_params` (`palisade.c:146-155`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`palisade.c:928-952`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                "Cursor mode",
                ":Half-grid:Full-grid",
                ":half:full",
                0,
                "cursor-mode",
            ),
            ConfigItem.Bool(
                "Automatically clear edges in completed regions",
                false,
                "clear-complete-regions",
            ),
        )

    /** `set_prefs` (`palisade.c:954-959`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.legacyCursor = prefs.choice("cursor-mode") == 1
        u.clearCompleteRegions = prefs.bool("clear-complete-regions")
    }

    /** `new_game_desc`: generate and encode the clues, and the aux solution. */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val generated = generate(params, random)
        return Description(generated.desc, generated.auxInfo)
    }

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val generated = generate(params, random, cancel)
        return Description(generated.desc, generated.auxInfo)
    }

    /** [newDesc] with the attempt counts, for the generator tests. */
    internal fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params, random)

    /**
     * `new_game_desc` from `palisade.c:629-712`. The RNG order is the
     * contract: one shuffle of the winnow order, then one
     * `Divvy.rectangle` per solver-gate attempt. A retry does not
     * reshuffle the winnow order. The winnow loop draws nothing.
     *
     * D-007 rejects an area above [MAX_AREA] and a region count above
     * [MAX_REGIONS] before the shuffle.
     */
    private fun generate(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generation {
        val p = params as Params
        val w = p.w
        val h = p.h
        val k = p.k

        require(w.toLong() * h <= MAX_AREA) { Game.AREA_TOO_LARGE }
        require(k > 0) { "Region size must be at least one" }
        require(w.toLong() * h / k <= MAX_REGIONS) {
            "Too many regions to generate in this build"
        }
        val wh = w * h

        val numbers = IntArray(wh) { EMPTY }
        val rim = IntArray(wh)
        initBorders(w, h, rim)
        val scratch = IntArray(wh)
        val soln = IntArray(wh)
        val shuf = IntArray(wh) { it }
        Divvy.shuffle(shuf, random)

        var solverRetries = 0
        var divvyAttempts = 0
        var dsf: Dsf
        while (true) {
            cancel.check()
            soln.fill('@'.code)
            val partition = Divvy.rectangleWithAttempts(w, h, k, random, cancel)
            divvyAttempts += partition.attempts
            dsf = partition.dsf

            for (r in 0 until h) {
                for (c in 0 until w) {
                    val i = r * w + c
                    numbers[i] = 0
                    for (dir in 0 until 4) {
                        val rr = r + DY[dir]
                        val cc = c + DX[dir]
                        if (outOfBounds(cc, rr, w, h) || !dsf.equivalent(i, rr * w + cc)) {
                            numbers[i]++
                            soln[i] = soln[i] or borderBit(dir)
                        }
                    }
                }
            }

            rim.copyInto(scratch)
            if (solver(p, numbers, scratch)) break
            solverRetries++
        }

        /*
         * Winnow: try each clue once, in the shuffled order. A clue
         * stays removed only while the solver still finishes the board
         * from the rim. There is no RNG here.
         */
        for (i in 0 until wh) {
            val j = shuf[i]
            val copy = numbers[j]
            rim.copyInto(scratch)
            numbers[j] = EMPTY
            if (!solver(p, numbers, scratch)) numbers[j] = copy
        }

        val desc = encodeClues(numbers)
        val auxInfo = buildString(wh + 1) {
            append('S')
            for (value in soln) append(value.toChar())
        }
        return Generation(desc, auxInfo, divvyAttempts, solverRetries)
    }

    /**
     * The run-length clue encoder from `palisade.c:694-708`. A run of
     * exactly 26 is one `z`; a longer run repeats `z`. The final run is
     * dropped because the flush happens only before a clue.
     */
    internal fun encodeClues(clues: IntArray): String {
        val out = StringBuilder(clues.size)
        var run = 0
        for (clue in clues) {
            if (clue != EMPTY) {
                while (run != 0) {
                    while (run > 26) {
                        out.append('z')
                        run -= 26
                    }
                    out.append(('a'.code - 1 + run).toChar())
                    run = 0
                }
                out.append(('0'.code + clue).toChar())
            } else {
                run++
            }
        }
        return out.toString()
    }

    /**
     * `validate_desc` from `palisade.c:714-740`. A letter is a run of
     * `c - 'a' + 1` cells, a digit is one clue, and only `0` to `4` are
     * allowed. Fewer than `w*h` cells is valid.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val wh = p.w * p.h
        var squares = 0
        for (c in desc) {
            when {
                c in 'a'..'z' -> squares += c - 'a' + 1
                c in '0'..'9' -> {
                    if (c > '4') return "Invalid (too large) number: '$c'"
                    squares++
                }

                c.code in 32..126 -> return "Invalid character in data: '$c'"
                else -> return "Invalid (unprintable) character in data"
            }
        }
        if (squares > wh) return "Data describes too many squares"
        return null
    }

    /**
     * `new_game` from `palisade.c:773-797`. The unmentioned cells stay
     * empty, and `completed` is only the `k == w*h` rule: the board is
     * not tested, so a description that is already a solution starts
     * open. An upper-case letter is C undefined behavior (a negative
     * run); this port rejects it, and [validateDesc] does the same.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val wh = p.w * p.h
        val clues = IntArray(wh) { EMPTY }
        var i = 0
        for (c in desc) {
            when {
                c in '0'..'9' -> {
                    if (i >= wh) throw IllegalArgumentException("description covers more than $wh cells")
                    clues[i++] = c - '0'
                }

                c in 'a'..'z' -> {
                    i += c - 'a' + 1
                    if (i > wh) throw IllegalArgumentException("description covers more than $wh cells")
                }

                else -> throw IllegalArgumentException("invalid character in description: '$c'")
            }
        }

        val borders = ByteArray(wh)
        initBorders(p.w, p.h, borders)
        return State(
            shared = Shared(p.w, p.h, p.k, clues),
            borders = borders,
            completed = p.k == wh,
            cheated = false,
        )
    }

    /**
     * `solve_game` from `palisade.c:825-861`. An `AUXINFO` is returned
     * verbatim, without validation. Without one, the solver runs from
     * the rim and the ON bits become the `S` answer. A board the local
     * techniques cannot finish returns null; C reports
     * `Sorry, I can't solve this puzzle`.
     *
     * The clues are immutable, so the current state and the first state
     * solve the same, and no initial-grid copy is needed.
     */
    /**
     * `solve_game` (`palisade.c:824-850`). The aux answer is returned as
     * is; otherwise the local solver either finds the whole border set
     * or fails with the C message. C's text has no full stop.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        if (auxInfo != null) return SolveOutcome(auxInfo, null)
        val s = state as State
        val wh = s.shared.size
        val move = IntArray(wh)
        initBorders(s.shared.w, s.shared.h, move)
        if (!solver(s.shared, s.shared.clues, move)) {
            return SolveOutcome(null, "Sorry, I can't solve this puzzle")
        }
        val chars = CharArray(wh) { (move[it] or '@'.code).toChar() }
        return SolveOutcome("S" + String(chars), null)
    }

    /**
     * `execute_move` from `palisade.c:1184-1222`.
     *
     * `S` plus exactly `w*h` characters sets every border byte from the
     * low four bits of each character and marks the game cheated. An
     * `F` token is `F x,y,flag`, the literal `F` must be present, `%d`
     * accepts a sign and surrounding white space, and the ON bits must
     * not point out of the board. The byte keeps the low eight bits of
     * the flag. An empty move is a valid copy. The move text is kept
     * verbatim in the save file.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val ret = s.copy() as State
        val w = s.shared.w
        val h = s.shared.h
        val wh = w * h

        if (move.isNotEmpty() && move[0] == 'S') {
            var i = 0
            while (i < wh && i + 1 < move.length && move[i + 1] != '\u0000') {
                val code = move[i + 1].code
                ret.borders[i] =
                    ((code and BORDER_MASK) or ((code.inv() and BORDER_MASK) shl 4)).toByte()
                i++
            }
            if (i < wh) return null
            if (1 + wh < move.length && move[1 + wh] != '\u0000') return null
            ret.cheated = true
            ret.completed = true
            return ret
        }

        var position = 0
        while (true) {
            val token = parseMoveToken(move, position) ?: break
            if (outOfBounds(token.x, token.y, w, h)) break
            position = token.next
            for (dir in 0 until 4) {
                if (token.flag and borderBit(dir) == 0) continue
                val nx = token.x + DX[dir]
                val ny = token.y + DY[dir]
                if (outOfBounds(nx, ny, w, h)) return null
            }
            val index = token.y * w + token.x
            ret.borders[index] = (ret.borders[index].toInt() xor token.flag).toByte()
        }
        if (position != move.length) return null

        if (!ret.completed) ret.completed = isSolved(ret.shared, ret.borders)
        return ret
    }

    private class MoveToken(val x: Int, val y: Int, val flag: Int, val next: Int)

    /**
     * One `F%d,%d,%d%n` conversion, starting at [start]. The result is
     * null when the format does not match. Commas are literal and do not
     * skip white space; `%d` does, and it accepts a sign.
     */
    private fun parseMoveToken(text: String, start: Int): MoveToken? {
        var position = start
        if (position >= text.length || text[position] != 'F') return null
        position++

        val x = scanInt(text, position) ?: return null
        position = x.second
        if (position >= text.length || text[position] != ',') return null
        position++

        val y = scanInt(text, position) ?: return null
        position = y.second
        if (position >= text.length || text[position] != ',') return null
        position++

        val flag = scanInt(text, position) ?: return null
        return MoveToken(x.first, y.first, flag.first, flag.second)
    }

    /**
     * The `%d` part of the parser: C white space, an optional sign, and
     * at least one digit. A value outside a long saturates before the
     * `int` truncation, as the other ports do.
     */
    private fun scanInt(text: String, start: Int): Pair<Int, Int>? {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }
        var digits = 0
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            digits++
            val digit = text[i] - '0'
            if (!overflow) {
                if (value > (Long.MAX_VALUE - digit) / 10) {
                    overflow = true
                } else {
                    value = value * 10 + digit
                }
            }
            i++
        }
        if (digits == 0) return null
        val parsed = when {
            overflow -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            negative -> -value
            else -> value
        }
        return parsed.toInt() to i
    }

    /**
     * `is_solved` from `palisade.c:531-580`. Visible for the engine
     * tests, which pin the stray-border rule on a hand-made board.
     */
    internal fun isSolved(shared: Shared, borders: ByteArray): Boolean {
        val w = shared.w
        val h = shared.h
        val wh = w * h
        val k = shared.k
        val dsf = Dsf.new(wh)
        buildDsf(w, h, borders, dsf, black = true)

        for (i in 0 until wh) {
            if (dsf.size(i) != k) return false
            val clue = shared.clues[i]
            if (clue == EMPTY) continue
            if (clue != BITCOUNT[(borders[i].toInt() and 0xFF) and BORDER_MASK]) return false
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val border = borders[i].toInt() and 0xFF
                if (x + 1 < w && border and BORDER_R != 0 && dsf.equivalent(i, i + 1)) {
                    return false
                }
                if (y + 1 < h && border and BORDER_D != 0 && dsf.equivalent(i, i + w)) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * `build_dsf` from `palisade.c:514-529`: merge the right and down
     * neighbors. With [black] the merge happens when the ON bit is
     * clear (a region); without it, when the OFF bit is set (the
     * crossed-out graph).
     */
    private fun buildDsf(w: Int, h: Int, borders: ByteArray, dsf: Dsf, black: Boolean) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val border = borders[i].toInt() and 0xFF
                if (x + 1 < w) {
                    val joined = if (black) {
                        border and BORDER_R == 0
                    } else {
                        border and (BORDER_R shl 4) != 0
                    }
                    if (joined) dsf.merge(i, i + 1)
                }
                if (y + 1 < h) {
                    val joined = if (black) {
                        border and BORDER_D == 0
                    } else {
                        border and (BORDER_D shl 4) != 0
                    }
                    if (joined) dsf.merge(i, i + w)
                }
            }
        }
    }

    /**
     * `init_borders` from `palisade.c:610-622`: the ON bits on the rim.
     * The solver reads them as out-of-grid edges, so it needs no bounds
     * test.
     */
    private fun initBorders(w: Int, h: Int, borders: IntArray) {
        borders.fill(0)
        for (c in 0 until w) {
            borders[c] = borders[c] or BORDER_U
            borders[w * h - 1 - c] = borders[w * h - 1 - c] or BORDER_D
        }
        for (r in 0 until h) {
            borders[r * w] = borders[r * w] or BORDER_L
            borders[w * h - 1 - r * w] = borders[w * h - 1 - r * w] or BORDER_R
        }
    }

    private fun initBorders(w: Int, h: Int, borders: ByteArray) {
        val values = IntArray(w * h)
        initBorders(w, h, values)
        for (i in values.indices) borders[i] = values[i].toByte()
    }

    // --- Solver -------------------------------------------------------

    /**
     * The solver state from `palisade.c:191-196`. [borders] is mutable
     * and only gains ON bits.
     */
    private class SolverContext(
        val w: Int,
        val h: Int,
        val k: Int,
        val clues: IntArray,
        val borders: IntArray,
        val dsf: Dsf,
    ) {
        /** `connect`: merge two cells into one region. */
        fun connect(i: Int, j: Int) {
            dsf.merge(i, j)
        }

        /** `connected`: true when the two cells are in one solver region. */
        fun connected(i: Int, j: Int, dir: Int): Boolean {
            val target = if (j == COMPUTE_J) i + DX[dir] + w * DY[dir] else j
            return dsf.equivalent(i, target)
        }

        /** `disconnect`: set the ON bit on both sides of an edge. */
        fun disconnect(i: Int, j: Int, dir: Int) {
            val target = if (j == COMPUTE_J) i + DX[dir] + w * DY[dir] else j
            borders[i] = borders[i] or borderBit(dir)
            borders[target] = borders[target] or borderBit(flip(dir))
        }

        /** `disconnected`: true when the ON bit marks a border. */
        fun disconnected(i: Int, j: Int, dir: Int): Boolean =
            borders[i] and borderBit(dir) != 0

        /**
         * `maybe`: neither a border nor a known region edge. The order
         * matters: [disconnected] is safe out of bounds, [connected] is
         * not.
         */
        fun maybe(i: Int, j: Int, dir: Int): Boolean =
            !disconnected(i, j, dir) && !connected(i, j, dir)
    }

    /**
     * `solver` from `palisade.c:582-606`: five techniques to a fixed
     * point, then [isSolved]. The first technique runs once and the
     * other five run in C order on every pass.
     */
    private fun solver(params: Params, clues: IntArray, borders: IntArray): Boolean {
        val w = params.w
        val h = params.h
        val wh = w * h
        val ctx = SolverContext(w, h, params.k, clues, borders, Dsf.new(wh))

        solverConnectedCluesVersusRegionSize(ctx)
        var changed: Boolean
        do {
            changed = false
            changed = solverNumberExhausted(ctx) or changed
            changed = solverNotTooBig(ctx) or changed
            changed = solverNotTooSmall(ctx) or changed
            changed = solverNoDanglingEdges(ctx) or changed
            changed = solverEquivalentEdges(ctx) or changed
        } while (changed)

        return isSolved(Shared(w, h, params.k, clues), toBorders(borders))
    }

    /** One solver call on a [Shared] clue board, for the tests. */
    internal fun solver(
        shared: Shared,
        clues: IntArray,
        borders: IntArray,
    ): Boolean = solver(Params(shared.w, shared.h, shared.k), clues, borders)

    private fun toBorders(borders: IntArray): ByteArray {
        val out = ByteArray(borders.size)
        for (i in borders.indices) out[i] = borders[i].toByte()
        return out
    }

    /**
     * `solver_connected_clues_versus_region_size` from
     * `palisade.c:306-330`. A lower bound on the region size rules out a
     * shared edge; two adjacent 3 clues also need `k == 2`.
     */
    private fun solverConnectedCluesVersusRegionSize(ctx: SolverContext) {
        val w = ctx.w
        val h = ctx.h
        val wh = w * h
        for (i in 0 until wh) {
            if (ctx.clues[i] == EMPTY) continue
            for (dir in 0 until 4) {
                val j = i + DX[dir] + w * DY[dir]
                if (ctx.disconnected(i, j, dir)) continue
                if (ctx.clues[j] == EMPTY) continue
                if (8 - ctx.clues[i] - ctx.clues[j] > ctx.k ||
                    (ctx.clues[i] == 3 && ctx.clues[j] == 3 && ctx.k != 2)
                ) {
                    ctx.disconnect(i, j, dir)
                }
            }
        }
    }

    /**
     * `solver_number_exhausted` from `palisade.c:332-366`. When a clue
     * has all its edges, the remaining maybe edges join its region; when
     * a clue can only be reached by `4 - off` edges, the rest are
     * borders.
     */
    private fun solverNumberExhausted(ctx: SolverContext): Boolean {
        val w = ctx.w
        val h = ctx.h
        val wh = w * h
        var changed = false
        for (i in 0 until wh) {
            if (ctx.clues[i] == EMPTY) continue

            if (BITCOUNT[ctx.borders[i] and BORDER_MASK] == ctx.clues[i]) {
                for (dir in 0 until 4) {
                    val j = i + DX[dir] + w * DY[dir]
                    if (!ctx.maybe(i, j, dir)) continue
                    ctx.connect(i, j)
                    changed = true
                }
                continue
            }

            var off = 0
            for (dir in 0 until 4) {
                val j = i + DX[dir] + w * DY[dir]
                if (!ctx.disconnected(i, j, dir) && ctx.connected(i, j, dir)) off++
            }
            if (ctx.clues[i] == 4 - off) {
                for (dir in 0 until 4) {
                    val j = i + DX[dir] + w * DY[dir]
                    if (!ctx.maybe(i, j, dir)) continue
                    ctx.disconnect(i, j, dir)
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * `solver_not_too_big` from `palisade.c:368-385`: a maybe edge whose
     * two regions would exceed `k` is a border.
     */
    private fun solverNotTooBig(ctx: SolverContext): Boolean {
        val w = ctx.w
        val h = ctx.h
        val wh = w * h
        var changed = false
        for (i in 0 until wh) {
            val size = ctx.dsf.size(i)
            for (dir in 0 until 4) {
                val j = i + DX[dir] + w * DY[dir]
                if (!ctx.maybe(i, j, dir)) continue
                if (size + ctx.dsf.size(j) <= ctx.k) continue
                ctx.disconnect(i, j, dir)
                changed = true
            }
        }
        return changed
    }

    /**
     * `solver_not_too_small` from `palisade.c:387-417`: a region that is
     * not full and has only one maybe neighbor must grow into it.
     */
    private fun solverNotTooSmall(ctx: SolverContext): Boolean {
        val w = ctx.w
        val h = ctx.h
        val wh = w * h
        val k = ctx.k
        var changed = false
        val outs = IntArray(wh) { -1 }

        for (i in 0 until wh) {
            val ci = ctx.dsf.canonify(i)
            if (ctx.dsf.size(ci) == k) continue
            for (dir in 0 until 4) {
                val j = i + DX[dir] + w * DY[dir]
                if (!ctx.maybe(i, j, dir)) continue
                if (outs[ci] == -1) {
                    outs[ci] = ctx.dsf.canonify(j)
                } else if (outs[ci] != ctx.dsf.canonify(j)) {
                    outs[ci] = -2
                }
            }
        }

        for (i in 0 until wh) {
            val j = outs[i]
            if (i != ctx.dsf.canonify(i)) continue
            if (j < 0) continue
            ctx.connect(i, j)
            changed = true
        }
        return changed
    }

    /**
     * `solver_no_dangling_edges` from `palisade.c:419-468`. At an
     * interior vertex, a lone border is a border and one of two walls
     * must continue. The first and last non-connected edge are the two
     * candidates.
     */
    private fun solverNoDanglingEdges(ctx: SolverContext): Boolean {
        val w = ctx.w
        val h = ctx.h
        var changed = false

        for (r in 1 until h) {
            for (c in 1 until w) {
                val i = r * w + c
                val j = i - w - 1
                var noline = 0
                var e = -1
                var f = -1
                var de = -1
                var df = -1
                val squares = IntArray(4)
                squares[1] = j
                squares[2] = j
                squares[0] = i
                squares[3] = i

                for (dir in 0 until 4) {
                    if (!ctx.connected(squares[dir], COMPUTE_J, dir)) {
                        df = dir
                        f = squares[df]
                        if (e != -1) continue
                        e = f
                        de = df
                    } else {
                        noline++
                    }
                }

                if (4 - noline == 1) {
                    check(e != -1)
                    ctx.disconnect(e, COMPUTE_J, de)
                    changed = true
                    continue
                }

                if (4 - noline != 2) continue

                check(e != -1)
                check(f != -1)

                if (ctx.borders[e] and borderBit(de) != 0) {
                    if (ctx.borders[f] and borderBit(df) == 0) {
                        ctx.disconnect(f, COMPUTE_J, df)
                        changed = true
                    }
                } else if (ctx.borders[f] and borderBit(df) != 0) {
                    ctx.disconnect(e, COMPUTE_J, de)
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * `solver_equivalent_edges` from `palisade.c:470-512`. Two maybe
     * edges to one region must match; a clue 2 also counts the known
     * borders and joins.
     */
    private fun solverEquivalentEdges(ctx: SolverContext): Boolean {
        val w = ctx.w
        val h = ctx.h
        val wh = w * h
        var changed = false

        for (i in 0 until wh) {
            var nOn = 0
            var nOff = 0
            if (ctx.clues[i] < 1 || ctx.clues[i] > 3) continue

            if (ctx.clues[i] == 2) {
                for (dir in 0 until 4) {
                    val j = i + DX[dir] + w * DY[dir]
                    if (ctx.disconnected(i, j, dir)) {
                        nOn++
                    } else if (ctx.connected(i, j, dir)) {
                        nOff++
                    }
                }
            }

            for (dirj in 0 until 4) {
                val j = i + DX[dirj] + w * DY[dirj]
                if (!ctx.maybe(i, j, dirj)) continue

                for (dirk in dirj + 1 until 4) {
                    val k = i + DX[dirk] + w * DY[dirk]
                    if (!ctx.maybe(i, k, dirk)) continue
                    if (!ctx.connected(j, k, COMPUTE_J)) continue

                    if (nOn + 2 > ctx.clues[i]) {
                        ctx.connect(i, j)
                        ctx.connect(i, k)
                        changed = true
                    } else if (nOff + 2 > 4 - ctx.clues[i]) {
                        ctx.disconnect(i, j, dirj)
                        ctx.disconnect(i, k, dirk)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    // --- Moves --------------------------------------------------------

    /**
     * `interpret_move` from `palisade.c:993-1182`.
     *
     * A click selects the nearest edge of the clicked cell, moves the
     * cursor there, hides it, and toggles the edge on both sides. An
     * arrow moves the half-grid cursor by one half-step, or the
     * full-grid cursor by one cell; control and shift toggle an edge in
     * full mode. A select toggles the left or up edge of the cursor
     * cell.
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
        val w = s.shared.w
        val h = s.shared.h

        var key = button
        key = when (key) {
            'h'.code -> Buttons.MOD_CTRL or Buttons.CURSOR_LEFT
            'H'.code -> Buttons.MOD_SHFT or Buttons.CURSOR_LEFT
            'j'.code -> Buttons.MOD_CTRL or Buttons.CURSOR_DOWN
            'J'.code -> Buttons.MOD_SHFT or Buttons.CURSOR_DOWN
            'k'.code -> Buttons.MOD_CTRL or Buttons.CURSOR_UP
            'K'.code -> Buttons.MOD_SHFT or Buttons.CURSOR_UP
            'l'.code -> Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT
            'L'.code -> Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT
            else -> key
        }
        val control = key and Buttons.MOD_CTRL != 0
        val shift = key and Buttons.MOD_SHFT != 0
        key = Buttons.stripModifiers(key)

        val margin = tileSize / 2

        if (key == Buttons.LEFT_BUTTON || key == Buttons.RIGHT_BUTTON) {
            val gx = (x - margin) / tileSize
            val gy = (y - margin) / tileSize
            var px = (x - margin) % tileSize
            var py = (y - margin) % tileSize

            if (outOfBounds(gx, gy, w, h)) return MoveResult.Unused

            var possible = BORDER_MASK
            possible = possible and (if (2 * px < tileSize) BORDER_R.inv() else BORDER_L.inv())
            possible = possible and (if (2 * py < tileSize) BORDER_D.inv() else BORDER_U.inv())
            px = min(px, tileSize - px)
            py = min(py, tileSize - py)
            possible = possible and if (px < py) {
                (BORDER_U or BORDER_D).inv()
            } else {
                (BORDER_L or BORDER_R).inv()
            }

            var dir = 0
            while (dir < 4 && borderBit(dir) != possible) dir++
            if (dir == 4) return MoveResult.Unused

            u.x = min(max(2 * gx + 1 + DX[dir], 1), 2 * w - 1)
            u.y = min(max(2 * gy + 1 + DY[dir], 1), 2 * h - 1)

            val hx = gx + DX[dir]
            val hy = gy + DY[dir]
            if (outOfBounds(hx, hy, w, h)) return MoveResult.Unused

            u.show = false
            val i = gy * w + gx
            val border = s.borders[i].toInt() and 0xFF
            val value = (if (key == Buttons.RIGHT_BUTTON) 1 else 0) or
                (((border and borderBit(dir)) shr dir) shl 1) or
                (((border and (borderBit(dir) shl 4)) shr dir) shr 2)

            when (value) {
                0, 3 -> return MoveResult.Move(
                    fMove(gx, gy, borderBit(dir), hx, hy, borderBit(flip(dir))),
                )

                1, 4 -> return MoveResult.Move(
                    fMove(
                        gx, gy, borderBit(dir) shl 4,
                        hx, hy, borderBit(flip(dir)) shl 4,
                    ),
                )

                2, 5 -> return MoveResult.Move(
                    fMove(
                        gx, gy, borderBit(dir) or (borderBit(dir) shl 4),
                        hx, hy, borderBit(flip(dir)) or (borderBit(flip(dir)) shl 4),
                    ),
                )
            }
            return MoveResult.Unused
        }

        if (Buttons.isCursorMove(key)) {
            if (u.legacyCursor && (control || shift)) {
                val i = (u.y / 2) * w + (u.x / 2)
                u.show = true
                var cx = u.x / 2
                var cy = u.y / 2
                when (key) {
                    Buttons.CURSOR_LEFT -> cx = max(cx - 1, 0)
                    Buttons.CURSOR_RIGHT -> cx = min(cx + 1, w - 1)
                    Buttons.CURSOR_UP -> cy = max(cy - 1, 0)
                    Buttons.CURSOR_DOWN -> cy = min(cy + 1, h - 1)
                }
                if (outOfBounds(cx, cy, w, h)) return MoveResult.Unused

                var dir = 0
                while (dir < 4 && !(DX[dir] == cx - u.x / 2 && DY[dir] == cy - u.y / 2)) dir++
                if (dir == 4) return MoveResult.Unused

                var flag = 0
                var flipflag = 0
                val border = s.borders[i].toInt() and 0xFF
                val value = (if (shift) 1 else 0) or
                    (((border and borderBit(dir)) shr dir) shl 1) or
                    (((border and (borderBit(dir) shl 4)) shr dir) shr 2)

                when (value) {
                    0, 3 -> {
                        flag = flag or borderBit(dir)
                        flipflag = flipflag or borderBit(flip(dir))
                    }

                    1, 4 -> {
                        flag = flag or (borderBit(dir) shl 4)
                        flipflag = flipflag or (borderBit(flip(dir)) shl 4)
                    }

                    2, 5 -> {
                        flag = flag or borderBit(dir) or (borderBit(dir) shl 4)
                        flipflag = flipflag or borderBit(flip(dir)) or
                            (borderBit(flip(dir)) shl 4)
                    }
                }

                val newflag = border xor flag
                if (newflag and borderBit(dir) != 0 && newflag and (borderBit(dir) shl 4) != 0) {
                    return MoveResult.Unused
                }
                return MoveResult.Move(
                    fMove(u.x / 2, u.y / 2, flag, cx, cy, flipflag),
                )
            } else {
                var dx = when (key) {
                    Buttons.CURSOR_LEFT -> -1
                    Buttons.CURSOR_RIGHT -> 1
                    else -> 0
                }
                var dy = when (key) {
                    Buttons.CURSOR_DOWN -> 1
                    Buttons.CURSOR_UP -> -1
                    else -> 0
                }

                if (u.legacyCursor) {
                    dx *= 2
                    dy *= 2
                    u.x = u.x or 1
                    u.y = u.y or 1
                }

                if (!u.show) u.show = true

                u.x = min(max(u.x + dx, 1), 2 * w - 1)
                u.y = min(max(u.y + dy, 1), 2 * h - 1)
                return MoveResult.UiUpdate
            }
        }

        if (key == Buttons.CURSOR_SELECT || key == Buttons.CURSOR_SELECT2) {
            val px = u.x % 2
            val py = u.y % 2
            val gx = u.x / 2
            val gy = u.y / 2
            val dir = if (px == 0) 3 else 0
            val hx = gx + DX[dir]
            val hy = gy + DY[dir]
            val i = gy * w + gx

            if (!u.show) {
                u.show = true
                return MoveResult.UiUpdate
            }

            /* A click on a corner or a cell center does nothing. */
            if (px == py) return MoveResult.NoEffect

            var flag = 0
            var flipflag = 0
            val border = s.borders[i].toInt() and 0xFF
            val value = (if (key == Buttons.CURSOR_SELECT2) 1 else 0) or
                (((border and borderBit(dir)) shr dir) shl 1) or
                (((border and (borderBit(dir) shl 4)) shr dir) shr 2)

            when (value) {
                0, 3 -> {
                    flag = flag or borderBit(dir)
                    flipflag = flipflag or borderBit(flip(dir))
                }

                1, 4 -> {
                    flag = flag or (borderBit(dir) shl 4)
                    flipflag = flipflag or (borderBit(flip(dir)) shl 4)
                }

                2, 5 -> {
                    flag = flag or borderBit(dir) or (borderBit(dir) shl 4)
                    flipflag = flipflag or borderBit(flip(dir)) or
                        (borderBit(flip(dir)) shl 4)
                }
            }

            val newflag = border xor flag
            if (newflag and borderBit(dir) != 0 && newflag and (borderBit(dir) shl 4) != 0) {
                return MoveResult.Unused
            }
            return MoveResult.Move(fMove(gx, gy, flag, hx, hy, flipflag))
        }

        return MoveResult.Unused
    }

    private fun fMove(
        x: Int,
        y: Int,
        flag: Int,
        hx: Int,
        hy: Int,
        flipflag: Int,
    ): String = "F$x,$y,$flag" + "F$hx,$hy,$flipflag"

    // --- Status and text ---------------------------------------------

    /** `game_status`: 1 when completed, else 0. There is no lose state. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /** `status_bar` from the first redraw: always `Region size: k`. */
    override fun statusText(state: Game.State, ui: Game.Ui): String =
        "Region size: ${(state as State).shared.k}"

    /**
     * `game_text_format` from `palisade.c:868-905`. Every cell is four
     * characters wide and two lines high. Only the U and L edges are
     * printed, and the bottom line is a copy of the top frame, not the
     * actual D and R bits.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.shared.w
        val h = s.shared.h
        val cw = 4
        val ch = 2
        val gw = cw * w + 2
        val gh = ch * h + 1
        val len = gw * gh
        val board = CharArray(len + 1) { ' ' }

        for (r in 0 until h) {
            for (c in 0 until w) {
                val cell = r * ch * gw + cw * c
                val center = cell + gw * ch / 2 + cw / 2
                val i = r * w + c
                val clue = s.shared.clues[i]
                val border = s.borders[i].toInt() and 0xFF

                if (clue != EMPTY) board[center] = ('0'.code + clue).toChar()

                board[cell] = '+'

                if (border and BORDER_U != 0) {
                    for (k in 1 until cw) board[cell + k] = '-'
                } else if (border and (BORDER_U shl 4) != 0) {
                    board[cell + cw / 2] = 'x'
                }

                if (border and BORDER_L != 0) {
                    board[cell + gw] = '|'
                } else if (border and (BORDER_L shl 4) != 0) {
                    board[cell + gw] = 'x'
                }
            }

            for (c in 0 until ch) {
                board[(r * ch + c) * gw + gw - 2] = if (c != 0) '|' else '+'
                board[(r * ch + c) * gw + gw - 1] = '\n'
            }
        }

        for (k in 0 until gw) board[len - gw + k] = board[k]
        board[len] = '\u0000'
        return String(board, 0, len)
    }

    /**
     * `game_changed_state`: only an honest completion raises the event.
     * A solve is cheated, and no state clears `completed`.
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

    // --- Keys and UI --------------------------------------------------

    /**
     * `game_request_keys` from `palisade.c:742-771`. The Android C hack
     * passes the full-cursor preference through the arrow mode. Half-grid
     * mode asks for no labels and the left/right pair; full-grid mode
     * asks for the four arrow-only keys.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    @Suppress("UNUSED_PARAMETER")
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        if (fullCursor) {
            KeyRequest(
                listOf(
                    KeyLabel('H'.code, "Left", needsArrows = true),
                    KeyLabel('J'.code, "Bottom", needsArrows = true),
                    KeyLabel('K'.code, "Top", needsArrows = true),
                    KeyLabel('L'.code, "Right", needsArrows = true),
                ),
                ArrowMode.ARROWS_ONLY,
            )
        } else {
            KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)
        }

    /** `game_compute_size`: the board plus the half-tile margin and line. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val margin = tileSize / 2
        val lineWidth = max(3 * tileSize / 32, 1)
        return Size(
            p.w * tileSize + lineWidth + 2 * margin,
            p.h * tileSize + lineWidth + 2 * margin,
        )
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).show = visible
    }

    /**
     * `get_cursor_location` from `palisade.c:1348-1384`. A zero
     * half-grid offset is a corner or a grid line and gets a smaller
     * ring; the full-grid cursor draws the center as four corner marks.
     */
    internal fun cursorGeometry(
        tileSize: Int,
        curX: Int,
        curY: Int,
        legacyCursor: Boolean,
    ): CursorGeometry {
        val margin = tileSize / 2
        val lineWidth = max(3 * tileSize / 32, 1)
        val center = tileSize / 2 + lineWidth / 2
        val offX = curX % 2
        val offY = curY % 2
        val x = margin + tileSize * (curX / 2)
        val y = margin + tileSize * (curY / 2)
        val curType = (offX shl 1) + offY

        val ringWidth: Int
        val ringHeight: Int
        when (curType) {
            0 -> {
                ringWidth = tileSize / 3
                ringHeight = tileSize / 3
            }

            1 -> {
                ringWidth = tileSize / 3
                ringHeight = 2 * tileSize / 3
            }

            2 -> {
                ringWidth = 2 * tileSize / 3
                ringHeight = tileSize / 3
            }

            else -> {
                ringWidth = 2 * tileSize / 3
                ringHeight = 2 * tileSize / 3
            }
        }

        val centerX = x + if (offX == 0) lineWidth / 2 else center
        val centerY = y + if (offY == 0) lineWidth / 2 else center
        return CursorGeometry(
            centerX = centerX,
            centerY = centerY,
            width = ringWidth,
            height = ringHeight,
            corners = legacyCursor && curType == 3,
        )
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.show) return null
        val geometry = cursorGeometry(tileSize, u.x, u.y, u.legacyCursor)
        return Game.CursorRect(
            geometry.centerX - geometry.width / 2,
            geometry.centerY - geometry.height / 2,
            geometry.width,
            geometry.height,
        )
    }

    /**
     * One unit per cell for the clue, and one per interior border for
     * the fence. A border is where `interpret_move` puts it: the
     * top-left third of the cell below or to the right of the line, the
     * point whose press hits that line.
     *
     * A border is a real move: the left button draws a line or clears a
     * crossed one, and the right button crosses out a border or clears
     * a drawn one. The outer rim is not playable in C and gets no unit.
     * The clue cell only reports its clue and its error mark.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val h = s.height
        val t = tileSize
        val margin = t / 2
        val cells = gridSpeech(
            width = w,
            height = h,
            tileSize = t,
            left = margin,
            top = margin,
            summary = "",
            text = { x, y ->
                val index = y * w + x
                val clue = s.shared.clues[index]
                val words = if (clue == EMPTY) "empty" else "clue $clue"
                val wrong = if (clueError(s, u, x, y)) SPOKEN_WRONG else ""
                val cursor = if (u.show && u.x == 2 * x + 1 && u.y == 2 * y + 1) {
                    ", cursor"
                } else {
                    ""
                }
                words + wrong + cursor
            },
        )

        val borders = buildList {
            fun border(
                left: Int,
                top: Int,
                width: Int,
                height: Int,
                text: String,
                cursorX: Int,
                cursorY: Int,
                dir: Int,
                cell: Int,
            ) {
                val border = s.borders[cell].toInt() and 0xFF
                val on = border and borderBit(dir) != 0
                val off = border and (borderBit(dir) shl 4) != 0
                val state = when {
                    on -> "line"
                    off -> "cross"
                    else -> "empty"
                }
                val cursor = if (u.show && u.x == cursorX && u.y == cursorY) {
                    ", cursor"
                } else {
                    ""
                }
                add(
                    SpokenUnit(
                        row = 0,
                        column = 0,
                        left = left,
                        top = top,
                        width = width,
                        height = height,
                        text = "$text, $state$cursor",
                        actions = buildList {
                            when {
                                on -> add(SpokenAction("Clear", Buttons.LEFT_BUTTON))
                                off -> add(SpokenAction("Clear", Buttons.RIGHT_BUTTON))
                                else -> {
                                    add(SpokenAction("Line", Buttons.LEFT_BUTTON))
                                    add(SpokenAction("Cross", Buttons.RIGHT_BUTTON))
                                }
                            }
                        },
                    ),
                )
            }

            // A horizontal border between rows y and y + 1 is the top
            // third of the cell below it.
            for (y in 1 until h) {
                for (x in 0 until w) {
                    border(
                        left = margin + x * t,
                        top = margin + y * t,
                        width = t,
                        height = max(t / 3, 1),
                        text = "Border between row $y and row ${y + 1}, column ${x + 1}",
                        cursorX = 2 * x + 1,
                        cursorY = 2 * y,
                        dir = 0,
                        cell = y * w + x,
                    )
                }
            }
            // A vertical border between columns x and x + 1 is the left
            // third of the cell to its right.
            for (y in 0 until h) {
                for (x in 1 until w) {
                    border(
                        left = margin + x * t,
                        top = margin + y * t,
                        width = max(t / 3, 1),
                        height = t,
                        text = "Border between column $x and column ${x + 1}, row ${y + 1}",
                        cursorX = 2 * x,
                        cursorY = 2 * y + 1,
                        dir = 3,
                        cell = y * w + x,
                    )
                }
            }
        }

        val board = cells.copy(units = cells.units + borders)
        val cursor = cursorLocation(ui, state, t)?.let {
            board.units.unitAt(it.left + it.width / 2, it.top + it.height / 2)
        }
        return board.cappedUnits(cursor)
    }

    /**
     * True when the game marks this cell's clue as impossible, the rule
     * of `game_redraw` with the `clear-complete-regions` preference: a
     * full region counts its unsure borders as crossed out.
     */
    private fun clueError(s: State, u: Ui, x: Int, y: Int): Boolean {
        val clue = s.shared.clues[y * s.width + x]
        if (clue == EMPTY) return false
        val border = s.borderAt(x, y)
        val on = BITCOUNT[border and BORDER_MASK]
        val off = if (u.clearCompleteRegions) {
            4 - on
        } else {
            BITCOUNT[(border shr 4) and BORDER_MASK]
        }
        return on > clue || clue > 4 - off
    }

    // --- Palette and drawing -----------------------------------------

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours` from `palisade.c:1260-1281`. The flash is the
     * lowlight of the background; the maybe line is the background
     * darkened toward yellow, and the no line is the darkened
     * background.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]

        Colour.mkHighlight(ret, COL_BACKGROUND, -1, COL_FLASH)

        for (i in 0..2) {
            ret[COL_GRID * 3 + i] = 0.0f
        }
        ret[COL_ERROR * 3] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_LINE_MAYBE * 3] = ret[COL_BACKGROUND * 3] * DARKER
        ret[COL_LINE_MAYBE * 3 + 1] = ret[COL_BACKGROUND * 3 + 1] * DARKER
        ret[COL_LINE_MAYBE * 3 + 2] = 0.0f

        ret[COL_LINE_NO * 3] = ret[COL_BACKGROUND * 3] * DARKER
        ret[COL_LINE_NO * 3 + 1] = ret[COL_BACKGROUND * 3 + 1] * DARKER
        ret[COL_LINE_NO * 3 + 2] = ret[COL_BACKGROUND * 3 + 2] * DARKER
        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.shared.w, s.shared.h, tileSize)
    }

    /**
     * `game_redraw` from `palisade.c:1404-1510`.
     *
     * The first draw of a drawstate paints the corner dots, updates the
     * frame, emits the status text, and then paints every cell. A later
     * draw paints only the cells whose flag word changed and the cursor.
     * The 0.7 s flash is not emitted: the seam has no flash time and the
     * oracle never sets one.
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
        val w = s.shared.w
        val h = s.shared.h
        val wh = w * h
        val k = s.shared.k

        val margin = tileSize / 2
        val lineWidth = max(3 * tileSize / 32, 1)

        if (!ds.started) {
            val bgw = w * tileSize + lineWidth + 2 * margin
            val bgh = h * tileSize + lineWidth + 2 * margin
            for (r in 0..h) {
                for (c in 0..w) {
                    sink.rect(
                        margin + tileSize * c,
                        margin + tileSize * r,
                        lineWidth,
                        lineWidth,
                        COL_GRID,
                    )
                }
            }
            sink.update(0, 0, bgw, bgh)
            ds.started = true
            sink.status("Region size: $k")
        }

        val black = Dsf.new(wh)
        val yellow = Dsf.new(wh)
        buildDsf(w, h, s.borders, black, black = true)
        buildDsf(w, h, s.borders, yellow, black = false)

        for (r in 0 until h) {
            for (c in 0 until w) {
                val i = r * w + c
                val clue = s.shared.clues[i]
                val border = s.borders[i].toInt() and 0xFF
                val forceUncertainEdgesOff = u.clearCompleteRegions && black.size(i) == k
                val on = BITCOUNT[border and BORDER_MASK]
                val off = if (forceUncertainEdgesOff) {
                    4 - on
                } else {
                    BITCOUNT[(border shr 4) and BORDER_MASK]
                }
                var flags = border

                if (clue != EMPTY && (on > clue || clue > 4 - off)) {
                    flags = flags or F_ERROR_CLUE
                }

                if (u.show) {
                    for (cursorX in 0..2) {
                        for (cursorY in 0..2) {
                            if (u.x == 2 * c + cursorX && u.y == 2 * r + cursorY) {
                                flags = flags or
                                    ((1 shl (3 * cursorX + cursorY)) shl CONTAINS_CURSOR_SHIFT)
                            }
                        }
                    }
                }

                for (dir in 0 until 4) {
                    val rr = r + DY[dir]
                    val cc = c + DX[dir]
                    if (outOfBounds(cc, rr, w, h)) continue
                    val ii = rr * w + cc
                    val tooLarge = (yellow.size(i) > k || yellow.size(ii) > k) &&
                        yellow.canonify(i) != yellow.canonify(ii)
                    val tooSmall = (black.size(i) < k || black.size(ii) < k) &&
                        black.canonify(i) != black.canonify(ii)
                    val dangling = border and borderBit(dir) != 0 &&
                        (yellow.canonify(i) == yellow.canonify(ii) ||
                            (black.size(i) <= k && black.canonify(i) == black.canonify(ii)))
                    if (tooLarge || tooSmall || dangling) {
                        flags = flags or (borderBit(dir) shl BORDER_ERROR_SHIFT)
                    }
                }

                if (forceUncertainEdgesOff) flags = flags or (BORDER_MASK shl 4)

                if (flags == ds.grid[i]) continue
                ds.grid[i] = flags
                drawTile(sink, ds, r, c, flags, clue)
            }
        }

        if (u.show) drawCursor(sink, ds, u.x, u.y, u.legacyCursor)
    }

    /**
     * `draw_tile` from `palisade.c:1315-1346`, in C call order: clip,
     * background, clue, the four edge strips, unclip, update.
     */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        r: Int,
        c: Int,
        flags: Int,
        clue: Int,
    ) {
        val tileSize = ds.tileSize
        val margin = tileSize / 2
        val lineWidth = max(3 * tileSize / 32, 1)
        val center = tileSize / 2 + lineWidth / 2
        val x = margin + tileSize * c
        val y = margin + tileSize * r

        sink.clip(x, y, tileSize + lineWidth, tileSize + lineWidth)

        sink.rect(
            x + lineWidth,
            y + lineWidth,
            tileSize - lineWidth,
            tileSize - lineWidth,
            COL_BACKGROUND,
        )

        if (clue != EMPTY) {
            sink.text(
                x + center,
                y + center,
                Text.FONT_VARIABLE,
                tileSize / 2,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                if (flags and F_ERROR_CLUE != 0) COL_ERROR else COL_CLUE,
                clue.toString(),
            )
        }

        sink.rect(
            x + lineWidth,
            y,
            tileSize - lineWidth,
            lineWidth,
            borderColour(flags, BORDER_U),
        )
        sink.rect(
            x + tileSize,
            y + lineWidth,
            lineWidth,
            tileSize - lineWidth,
            borderColour(flags, BORDER_R),
        )
        sink.rect(
            x + lineWidth,
            y + tileSize,
            tileSize - lineWidth,
            lineWidth,
            borderColour(flags, BORDER_D),
        )
        sink.rect(
            x,
            y + lineWidth,
            lineWidth,
            tileSize - lineWidth,
            borderColour(flags, BORDER_L),
        )

        sink.unclip()
        sink.update(x, y, tileSize + lineWidth, tileSize + lineWidth)
    }

    /** `COLOUR(border)`: an error wins, then ON, then OFF, then maybe. */
    private fun borderColour(flags: Int, border: Int): Int = when {
        flags and (border shl BORDER_ERROR_SHIFT) != 0 -> COL_ERROR
        flags and border != 0 -> COL_LINE_YES
        flags and (border shl 4) != 0 -> COL_LINE_NO
        else -> COL_LINE_MAYBE
    }

    /**
     * `draw_cursor` from `palisade.c:1386-1400`. The ring is a polygon
     * outline, or eight short lines at the center in full-cursor mode.
     */
    private fun drawCursor(
        sink: DrawSink,
        ds: DrawState,
        curX: Int,
        curY: Int,
        legacyCursor: Boolean,
    ) {
        val tileSize = ds.tileSize
        val geometry = cursorGeometry(tileSize, curX, curY, legacyCursor)
        if (geometry.corners) {
            val radius = tileSize / 3
            val cx = geometry.centerX
            val cy = geometry.centerY
            val half = radius / 2
            sink.line(cx - radius, cy - radius, cx - radius, cy - half, COL_GRID)
            sink.line(cx - radius, cy - radius, cx - half, cy - radius, COL_GRID)
            sink.line(cx - radius, cy + radius, cx - radius, cy + half, COL_GRID)
            sink.line(cx - radius, cy + radius, cx - half, cy + radius, COL_GRID)
            sink.line(cx + radius, cy - radius, cx + radius, cy - half, COL_GRID)
            sink.line(cx + radius, cy - radius, cx + half, cy - radius, COL_GRID)
            sink.line(cx + radius, cy + radius, cx + radius, cy + half, COL_GRID)
            sink.line(cx + radius, cy + radius, cx + half, cy + radius, COL_GRID)
        } else {
            val left = geometry.centerX - geometry.width / 2
            val top = geometry.centerY - geometry.height / 2
            sink.poly(
                intArrayOf(
                    left,
                    top,
                    left,
                    top + geometry.height - 1,
                    left + geometry.width - 1,
                    top + geometry.height - 1,
                    left + geometry.width - 1,
                    top,
                ),
                -1,
                COL_GRID,
            )
        }

        sink.update(
            geometry.centerX - geometry.width / 2,
            geometry.centerY - geometry.height / 2,
            geometry.width,
            geometry.height,
        )
    }

    // --- Helpers ------------------------------------------------------

    /** `BORDER(i)`: one edge bit. */
    private fun borderBit(dir: Int): Int = 1 shl dir

    /** `FLIP(i)`: the opposite edge. */
    private fun flip(dir: Int): Int = dir xor 2

    /** `OUT_OF_BOUNDS` from `palisade.c:624-625`. */
    private fun outOfBounds(x: Int, y: Int, w: Int, h: Int): Boolean =
        x < 0 || x >= w || y < 0 || y >= h

    /**
     * C `atoi` with the `int` conversion. A value outside `long`
     * saturates, then the low 32 bits are kept, as the other ports do.
     */
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
            val digit = text[i] - '0'
            if (!overflow) {
                if (value > (Long.MAX_VALUE - digit) / 10) {
                    overflow = true
                } else {
                    value = value * 10 + digit
                }
            }
            i++
        }

        val parsed = when {
            overflow -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            negative -> -value
            else -> value
        }
        return parsed.toInt()
    }

    /** The white space set of C `isspace` in the C locale. */
    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
