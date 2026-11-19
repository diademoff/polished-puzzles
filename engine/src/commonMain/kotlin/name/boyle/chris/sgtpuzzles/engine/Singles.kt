package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.max

/**
 * A port of upstream `singles.c`: Hitori.
 *
 * The board is a Latin rectangle. A cell is black or circled (white).
 * The puzzle is solved when no two black cells touch, every white row
 * and column has no repeated number, and the white cells form one
 * connected region.
 *
 * The generator uses the solver as a gate, so the technique order, the
 * retry loops, the Latin stream, and the number rewrite under black
 * cells are all part of the `DESC` contract.
 *
 * Two upstream edge cases are defined here instead of reproduced:
 *
 * - A side of 62 is accepted by `validate_params` but cannot be
 *   encoded: `n2c(62)` is `[`, and the game's own decoder rejects it.
 *   C aborts in `new_game`; this port rejects the generation with the
 *   parameter message before it draws from the generator. This is
 *   D-017.
 * - `best_black_col` ends in an assertion when no number has a row or
 *   column count. The port places the first shuffled number and pins
 *   that result in the engine test.
 *
 * Generation cost is not bounded by upstream. The retry loop can run
 * for a long time on some accepted sizes, so D-031 refuses an area
 * above [MAX_AREA] (a side of 20) before the first draw, and the app
 * must still generate off the UI thread.
 */
object Singles : Game {

    // Difficulty, `singles.c:122-134`. DIFF_ANY is the user-solver level.
    private const val DIFF_EASY = 0
    private const val DIFF_TRICKY = 1
    private const val DIFF_MAX = 2
    private const val DIFF_ANY = 3
    private const val DIFFCOUNT = 2
    private val DIFF_NAMES = arrayOf("Easy", "Tricky")
    private const val DIFF_CHARS = "ek"

    /** `PREFERRED_TILE_SIZE`, `singles.c:74`. */
    private const val PREFERRED_TILE_SIZE = 32

    /** D-031: the measured generation cap, an area of 20x20 cells. */
    internal const val MAX_AREA = 400

    // Palette, `singles.c:88-93`.
    private const val COL_BACKGROUND = 0
    private const val COL_UNUSED1 = 1
    private const val COL_LOWLIGHT = 2
    private const val COL_BLACK = 3
    private const val COL_WHITE = 4
    private const val COL_BLACKNUM = 5
    private const val COL_GRID = 6
    private const val COL_CURSOR = 7
    private const val COL_ERROR = 8
    private const val NCOLOURS = 9

    // Cell flags, `singles.c:104-107`.
    const val F_BLACK = 0x1
    const val F_CIRCLE = 0x2
    const val F_ERROR = 0x4
    const val F_SCRATCH = 0x8

    /** `check_complete` flags, `singles.c:461-462`. */
    internal const val CC_MARK_ERRORS = 1
    internal const val CC_MUST_FILL = 2

    // Drawstate cell flags, `singles.c:1527-1533`.
    private const val DS_BLACK = 0x1
    private const val DS_CIRCLE = 0x2
    private const val DS_CURSOR = 0x4
    private const val DS_BLACK_NUM = 0x8
    private const val DS_ERROR = 0x10
    private const val DS_FLASH = 0x20
    private const val DS_IMPOSSIBLE = 0x40

    /** `MAXTRIES`, `singles.c:1278`. */
    private const val MAXTRIES = 20

    /** `struct game_params`, `singles.c:100-102`. */
    data class Params(val w: Int, val h: Int, val diff: Int) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, diff)
    }

    /**
     * `struct game_state`, `singles.c:109-114`.
     *
     * [nums] and [flags] are row-major `y*w + x`. [impossible] is solver
     * scratch and is false in every playable state.
     */
    class State internal constructor(
        val w: Int,
        val h: Int,
        val nums: IntArray,
        val flags: IntArray,
        var completed: Boolean,
        var usedSolve: Boolean,
        var impossible: Boolean,
    ) : Game.State {

        /** `state->n`, the number of cells. */
        val n: Int get() = w * h

        /** `state->o`, the Latin order. */
        val o: Int get() = max(w, h)

        /** `INGRID(state, x, y)`. */
        fun inGrid(x: Int, y: Int): Boolean = x >= 0 && x < w && y >= 0 && y < h

        /** The row-major cell index. */
        fun index(x: Int, y: Int): Int = y * w + x

        /** The flag word of a cell. */
        fun flag(x: Int, y: Int): Int = flags[y * w + x]

        internal fun duplicate(): State = State(
            w = w,
            h = h,
            nums = nums.copyOf(),
            flags = flags.copyOf(),
            completed = completed,
            usedSolve = usedSolve,
            impossible = impossible,
        )

        /**
         * The state a save starts with: the same numbers, no flags, and
         * the three booleans false. `execute_move` never changes
         * [nums], so this is the initial state of the same description.
         * `solve_game` uses it as the fallback source.
         */
        internal fun initial(): State = State(
            w = w,
            h = h,
            nums = nums.copyOf(),
            flags = IntArray(n),
            completed = false,
            usedSolve = false,
            impossible = false,
        )

        override fun copy(): Game.State = duplicate()

        override fun equals(other: Any?): Boolean =
            other is State &&
                w == other.w &&
                h == other.h &&
                nums.contentEquals(other.nums) &&
                flags.contentEquals(other.flags) &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                impossible == other.impossible

        override fun hashCode(): Int {
            var result = w
            result = 31 * result + h
            result = 31 * result + nums.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + impossible.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`, `singles.c:1458-1461`.
     *
     * [showBlackNums] is the `show-black-nums` preference. C keeps it in
     * the UI and applies the frontend preference store after `new_ui`
     * and after a load; the engine exposes the field for the app
     * adapter. It is not saved and not part of `DrawState`.
     */
    class Ui internal constructor() : Game.Ui {
        internal var cx: Int = 0
        internal var cy: Int = 0
        internal var cshow: Boolean = false

        /** The `show-black-nums` preference, C default false. */
        var showBlackNums: Boolean = false

        /** The cursor cell, for tests and for the shell hint. */
        val cursorX: Int get() = cx

        /** The cursor cell, for tests and for the shell hint. */
        val cursorY: Int get() = cy

        /** True while the cursor is visible. */
        val cursorVisible: Boolean get() = cshow
    }

    /** `struct game_drawstate`, `singles.c:1535-1541`. */
    class DrawState internal constructor(
        val tileSize: Int,
        val w: Int,
        val h: Int,
    ) : Game.DrawState {
        internal var started: Boolean = false
        internal val flags: IntArray = IntArray(w * h)

        /** The per-cell dirty cache, exposed for the engine test. */
        internal fun cachedFlags(): IntArray = flags
    }

    /** Retry and loop diagnostics. They change no description. */
    internal class GenerationStats {
        /** Entries into the `generate` label. */
        var attempts = 0

        /** Entries after a latin regeneration. */
        var restarts = 0

        /** Entries into the `randomise` label. */
        var randomises = 0

        /** Failed solver gates, including the last one before a restart. */
        var gateFailures = 0
    }

    internal data class Generated(
        val description: Description,
        val stats: GenerationStats,
    )

    private val PRESETS = listOf(
        Params(5, 5, DIFF_EASY),
        Params(5, 5, DIFF_TRICKY),
        Params(6, 6, DIFF_EASY),
        Params(6, 6, DIFF_TRICKY),
        Params(8, 8, DIFF_EASY),
        Params(8, 8, DIFF_TRICKY),
        Params(10, 10, DIFF_EASY),
        Params(10, 10, DIFF_TRICKY),
        Params(12, 12, DIFF_EASY),
        Params(12, 12, DIFF_TRICKY),
    )

    override val name: String = "Singles"
    override val backendName: String = "singles"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val flags: Int = Game.REQUIRE_RBUTTON

    // ------------------------------------------------------------------
    // Parameters, `singles.c:138-276`.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = PRESETS[0]

    override fun presets(): List<Preset> = PRESETS.map { p ->
        Preset("${p.w}x${p.h} ${DIFF_NAMES[p.diff]}", encodeParams(p, true))
    }

    /**
     * `decode_params`, `singles.c:190-211`.
     *
     * The digit skip starts at the first character, so a leading sign or
     * space stops it and a later `x` or `d` is not read. The base
     * difficulty is Easy, the game default.
     */
    override fun decodeParams(text: String): Game.Params {
        var index = 0
        val w = cAtoi(text, index)
        var h = w
        while (index < text.length && text[index].isAsciiDigit()) index++

        if (index < text.length && text[index] == 'x') {
            index++
            h = cAtoi(text, index)
            while (index < text.length && text[index].isAsciiDigit()) index++
        }

        var diff = DIFF_EASY
        if (index < text.length && text[index] == 'd') {
            diff = DIFF_MAX
            index++
            if (index < text.length) {
                for (i in 0 until DIFFCOUNT) {
                    if (text[index] == DIFF_CHARS[i]) diff = i
                }
                index++
            }
        }
        return Params(w, h, diff)
    }

    /**
     * `encode_params`, `singles.c:213-223`: the short form drops the
     * difficulty.
     *
     * C indexes `singles_diffchars[diff]` with no bound. The port writes
     * a plain `d` for an out-of-range value, the defined result the
     * other ports use. Validation rejects such a set first.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.w).append('x').append(p.h)
        if (full) {
            out.append('d')
            if (p.diff in 0 until DIFFCOUNT) out.append(DIFF_CHARS[p.diff])
        }
        return out.toString()
    }

    /** `validate_params`, `singles.c:264-276`, in C order. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 2 || p.h < 2) return "Width and height must be at least two"
        if (p.w > 62 || p.h > 62) return "Puzzle is too large"
        if (full && (p.diff < 0 || p.diff >= DIFF_MAX)) {
            return "Unknown difficulty rating"
        }
        return null
    }

    // ------------------------------------------------------------------
    // Description, `singles.c:280-422`, `1317-1454`.
    // ------------------------------------------------------------------

    /** A description error from `unpick_desc`. */
    private class BadDescription(message: String) : Exception(message)

    private fun blankState(w: Int, h: Int): State = State(
        w = w,
        h = h,
        nums = IntArray(w * h),
        flags = IntArray(w * h),
        completed = false,
        usedSolve = false,
        impossible = false,
    )

    /** `n2c`, `singles.c:324-332`. `n2c(62)` is `[`, as in C. */
    internal fun n2c(num: Int): Char = when {
        num < 10 -> '0' + num
        num < 36 -> 'a' + num - 10
        else -> 'A' + num - 36
    }

    /** `c2n`, `singles.c:334-342`. */
    internal fun c2n(c: Char): Int = when {
        c.isAsciiDigit() -> c - '0'
        c in 'a'..'z' -> c - 'a' + 10
        c in 'A'..'Z' -> c - 'A' + 36
        else -> -1
    }

    /**
     * `unpick_desc`, `singles.c:344-372`: the length check, then the
     * `1..o` character range. Nothing else is checked.
     */
    private fun loadDesc(params: Params, desc: String): State {
        val state = blankState(params.w, params.h)
        if (desc.length != state.n) {
            throw BadDescription("Game description is wrong length")
        }
        for (i in 0 until state.n) {
            val num = c2n(desc[i])
            if (num <= 0 || num > state.o) {
                throw BadDescription("Invalid character in game description")
            }
            state.nums[i] = num
        }
        return state
    }

    /** `validate_desc`, `singles.c:1438-1444`. */
    override fun validateDesc(params: Game.Params, desc: String): String? =
        try {
            loadDesc(params as Params, desc)
            null
        } catch (e: BadDescription) {
            e.message
        }

    /**
     * `new_game`, `singles.c:1446-1454`. C asserts that a validated
     * description loads and aborts otherwise; the port throws, because
     * the midend always validates first.
     */
    override fun newState(params: Game.Params, desc: String): Game.State =
        try {
            loadDesc(params as Params, desc)
        } catch (e: BadDescription) {
            throw IllegalArgumentException(e.message)
        }

    /** `generate_desc`, `singles.c:374-385`, with `issolve = false`. */
    private fun generateDesc(state: State): String {
        val out = StringBuilder(state.n)
        for (i in 0 until state.n) out.append(n2c(state.nums[i]))
        return out.toString()
    }

    /** `game_text_format`, `singles.c:394-422`. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val out = StringBuilder(4 * s.n + 1)
        for (y in 0 until s.h) {
            for (x in 0 until s.w) {
                if (x > 0) out.append(' ')
                val i = y * s.w + x
                out.append(
                    if (s.flags[i] and F_BLACK != 0) '*' else n2c(s.nums[i]),
                )
            }
            out.append('\n')
            for (x in 0 until s.w) {
                if (x > 0) out.append(' ')
                out.append(
                    if (s.flags[y * s.w + x] and F_CIRCLE != 0) '~' else ' ',
                )
            }
            out.append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Completion and errors, `singles.c:430-573`.
    // ------------------------------------------------------------------

    /** `connect_if_same`, `singles.c:430-440`. */
    private fun connectIfSame(state: State, dsf: Dsf, i1: Int, i2: Int) {
        if ((state.flags[i1] and F_BLACK) != (state.flags[i2] and F_BLACK)) return
        dsf.merge(dsf.canonify(i1), dsf.canonify(i2))
    }

    /** `connect_dsf`, `singles.c:442-459`. */
    private fun connectDsf(state: State, dsf: Dsf) {
        dsf.reinit()
        for (x in 0 until state.w) {
            for (y in 0 until state.h) {
                val i = y * state.w + x
                if (x < state.w - 1) connectIfSame(state, dsf, i, i + 1)
                if (y < state.h - 1) connectIfSame(state, dsf, i, i + state.w)
            }
        }
    }

    /** `check_rowcol`, `singles.c:464-503`. */
    private fun checkRowCol(
        state: State,
        starti: Int,
        di: Int,
        sz: Int,
        flags: Int,
    ): Int {
        var nerr = 0
        var i = starti
        for (n in 0 until sz) {
            if (state.flags[i] and F_BLACK == 0) {
                var j = i + di
                for (m in n + 1 until sz) {
                    if (state.flags[j] and F_BLACK == 0 &&
                        state.nums[i] == state.nums[j]
                    ) {
                        nerr++
                        if (flags and CC_MARK_ERRORS != 0 &&
                            state.flags[i] and F_CIRCLE != 0 &&
                            state.flags[j] and F_CIRCLE != 0
                        ) {
                            state.flags[i] = state.flags[i] or F_ERROR
                            state.flags[j] = state.flags[j] or F_ERROR
                        }
                    }
                    j += di
                }
            }
            i += di
        }
        return nerr
    }

    /** `check_complete`, `singles.c:505-573`. */
    internal fun checkComplete(state: State, flags: Int): Boolean {
        val dsf = Dsf.new(state.n)
        var error = 0
        val w = state.w
        val h = state.h

        if (flags and CC_MARK_ERRORS != 0) {
            for (i in 0 until state.n) state.flags[i] = state.flags[i] and F_ERROR.inv()
        }
        connectDsf(state, dsf)

        if (flags and CC_MUST_FILL != 0) {
            for (i in 0 until state.n) {
                if (state.flags[i] and F_BLACK == 0 &&
                    state.flags[i] and F_CIRCLE == 0
                ) {
                    error++
                }
            }
        }

        var nwhite = 0
        for (i in 0 until state.n) {
            if (state.flags[i] and F_BLACK != 0) {
                if (dsf.size(i) > 1) {
                    error++
                    if (flags and CC_MARK_ERRORS != 0) {
                        state.flags[i] = state.flags[i] or F_ERROR
                    }
                }
            } else {
                nwhite++
            }
        }

        for (x in 0 until w) error += checkRowCol(state, x, w, h, flags)
        for (y in 0 until h) error += checkRowCol(state, y * w, 1, w, flags)

        var largest = 0
        var canonical = -1
        for (i in 0 until state.n) {
            if (state.flags[i] and F_BLACK == 0) {
                val size = dsf.size(i)
                if (largest < size) {
                    largest = size
                    canonical = dsf.canonify(i)
                }
            }
        }
        if (largest < nwhite) {
            for (i in 0 until state.n) {
                if (state.flags[i] and F_BLACK == 0 &&
                    dsf.canonify(i) != canonical
                ) {
                    error++
                    if (flags and CC_MARK_ERRORS != 0) {
                        state.flags[i] = state.flags[i] or F_ERROR
                    }
                }
            }
        }

        return !(error > 0)
    }

    /** `game_state_diff`, `singles.c:575-609`: column-major with `;`. */
    private fun gameStateDiff(src: State, dst: State, issolve: Boolean): String {
        require(src.n == dst.n) { "state sizes differ" }
        val fmask = F_BLACK or F_CIRCLE
        val out = StringBuilder()
        if (issolve) out.append("S;")
        for (x in 0 until dst.w) {
            for (y in 0 until dst.h) {
                val i = y * dst.w + x
                if (src.flags[i] and fmask != dst.flags[i] and fmask) {
                    check(dst.flags[i] and fmask != fmask) {
                        "a cell cannot be black and circled"
                    }
                    val c = when {
                        dst.flags[i] and F_BLACK != 0 -> 'B'
                        dst.flags[i] and F_CIRCLE != 0 -> 'C'
                        else -> 'E'
                    }
                    out.append(c).append(x).append(',').append(y).append(';')
                }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Solver, `singles.c:613-1217`.
    // ------------------------------------------------------------------

    private const val SOLVER_BLACK = 0
    private const val SOLVER_CIRCLE = 1

    private class SolverOp(val x: Int, val y: Int, val op: Int)

    private class SolverState(n: Int) {
        val ops = ArrayList<SolverOp>()
        val scratch = IntArray(n)
    }

    /** `solver_op_add`, `singles.c:644-656`; the `desc` is debug only. */
    private fun solverOpAdd(ss: SolverState, x: Int, y: Int, op: Int) {
        ss.ops += SolverOp(x, y, op)
    }

    /** `solver_op_circle`, `singles.c:658-673`. */
    private fun solverOpCircle(state: State, ss: SolverState, x: Int, y: Int) {
        if (!state.inGrid(x, y)) return
        val i = y * state.w + x
        if (state.flags[i] and F_BLACK != 0) {
            state.impossible = true
            return
        }
        if (state.flags[i] and F_CIRCLE == 0) {
            solverOpAdd(ss, x, y, SOLVER_CIRCLE)
        }
    }

    /** `solver_op_blacken`, `singles.c:675-691`. */
    private fun solverOpBlacken(
        state: State,
        ss: SolverState,
        x: Int,
        y: Int,
        num: Int,
    ) {
        if (!state.inGrid(x, y)) return
        val i = y * state.w + x
        if (state.nums[i] != num) return
        if (state.flags[i] and F_CIRCLE != 0) {
            state.impossible = true
            return
        }
        if (state.flags[i] and F_BLACK == 0) {
            solverOpAdd(ss, x, y, SOLVER_BLACK)
        }
    }

    /** `solver_ops_do`, `singles.c:693-753`. */
    private fun solverOpsDo(state: State, ss: SolverState): Int {
        var nextOp = 0
        var applied = 0
        while (nextOp < ss.ops.size) {
            val op = ss.ops[nextOp++]
            val i = op.y * state.w + op.x

            if (op.op == SOLVER_BLACK) {
                if (state.flags[i] and F_CIRCLE != 0) {
                    state.impossible = true
                    return applied
                }
                if (state.flags[i] and F_BLACK == 0) {
                    state.flags[i] = state.flags[i] or F_BLACK
                    applied++
                    solverOpCircle(state, ss, op.x - 1, op.y)
                    solverOpCircle(state, ss, op.x + 1, op.y)
                    solverOpCircle(state, ss, op.x, op.y - 1)
                    solverOpCircle(state, ss, op.x, op.y + 1)
                }
            } else {
                if (state.flags[i] and F_BLACK != 0) {
                    state.impossible = true
                    return applied
                }
                if (state.flags[i] and F_CIRCLE == 0) {
                    state.flags[i] = state.flags[i] or F_CIRCLE
                    applied++
                    for (x in 0 until state.w) {
                        if (x != op.x) {
                            solverOpBlacken(state, ss, x, op.y, state.nums[i])
                        }
                    }
                    for (y in 0 until state.h) {
                        if (y != op.y) {
                            solverOpBlacken(state, ss, op.x, y, state.nums[i])
                        }
                    }
                }
            }
        }
        ss.ops.clear()
        return applied
    }

    /** `solve_singlesep`, `singles.c:759-784`. */
    private fun solveSinglesep(state: State, ss: SolverState): Int {
        val before = ss.ops.size
        for (x in 0 until state.w) {
            for (y in 0 until state.h) {
                val i = y * state.w + x

                if (x < state.w - 2) {
                    val ir = i + 1
                    val irr = ir + 1
                    if (state.nums[i] == state.nums[irr] &&
                        state.flags[ir] and F_CIRCLE == 0
                    ) {
                        solverOpAdd(ss, x + 1, y, SOLVER_CIRCLE)
                    }
                }
                if (y < state.h - 2) {
                    val id = i + state.w
                    val idd = id + state.w
                    if (state.nums[i] == state.nums[idd] &&
                        state.flags[id] and F_CIRCLE == 0
                    ) {
                        solverOpAdd(ss, x, y + 1, SOLVER_CIRCLE)
                    }
                }
            }
        }
        return ss.ops.size - before
    }

    /** `solve_doubles`, `singles.c:788-823`. */
    private fun solveDoubles(state: State, ss: SolverState): Int {
        val before = ss.ops.size
        var i = 0
        for (y in 0 until state.h) {
            for (x in 0 until state.w) {
                if (state.flags[i] and F_BLACK == 0) {
                    val ii = i + 1
                    if (x < state.w - 1 &&
                        state.flags[ii] and F_BLACK == 0 &&
                        state.nums[i] == state.nums[ii]
                    ) {
                        for (xy in 0 until state.w) {
                            if (xy == x || xy == x + 1) continue
                            val j = y * state.w + xy
                            if (state.nums[j] == state.nums[i] &&
                                state.flags[j] and F_BLACK == 0
                            ) {
                                solverOpAdd(ss, xy, y, SOLVER_BLACK)
                            }
                        }
                    }

                    val id = i + state.w
                    if (y < state.h - 1 &&
                        state.flags[id] and F_BLACK == 0 &&
                        state.nums[i] == state.nums[id]
                    ) {
                        for (xy in 0 until state.h) {
                            if (xy == y || xy == y + 1) continue
                            val j = xy * state.w + x
                            if (state.nums[j] == state.nums[i] &&
                                state.flags[j] and F_BLACK == 0
                            ) {
                                solverOpAdd(ss, x, xy, SOLVER_BLACK)
                            }
                        }
                    }
                }
                i++
            }
        }
        return ss.ops.size - before
    }

    /** `solve_allblackbutone`, `singles.c:827-867`. */
    private fun solveAllblackbutone(state: State, ss: SolverState): Int {
        val before = ss.ops.size
        val dxs = intArrayOf(0, 1, 0, -1)
        val dys = intArrayOf(-1, 0, 1, 0)
        val dis = intArrayOf(-state.w, 1, state.w, -1)

        for (y in 0 until state.h) {
            for (x in 0 until state.w) {
                val i = y * state.w + x
                if (state.flags[i] and F_BLACK != 0) continue

                var ifree = -1
                var skip = false
                for (d in 0 until 4) {
                    val xd = x + dxs[d]
                    val yd = y + dys[d]
                    if (!state.inGrid(xd, yd)) continue
                    val id = i + dis[d]

                    if (state.flags[id] and F_CIRCLE != 0) {
                        skip = true
                        break
                    }
                    if (state.flags[id] and F_BLACK == 0) {
                        if (ifree != -1) {
                            skip = true
                            break
                        }
                        ifree = id
                    }
                }
                if (skip) continue
                if (ifree != -1) {
                    solverOpAdd(
                        ss,
                        ifree % state.w,
                        ifree / state.w,
                        SOLVER_CIRCLE,
                    )
                } else {
                    state.impossible = true
                    return 0
                }
            }
        }
        return ss.ops.size - before
    }

    /** `solve_corner`, `singles.c:875-903`. */
    private fun solveCorner(
        state: State,
        ss: SolverState,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
    ) {
        val is4 = IntArray(4)
        val ns = IntArray(4)
        val w = state.w
        for (yy in 0 until 2) {
            for (xx in 0 until 2) {
                val index = (y + dy * yy) * w + (x + dx * xx)
                is4[yy * 2 + xx] = index
                ns[yy * 2 + xx] = state.nums[index]
            }
        }

        when {
            ns[0] == ns[1] && ns[0] == ns[2] && ns[0] == ns[3] -> {
                solverOpAdd(ss, is4[0] % w, is4[0] / w, SOLVER_BLACK)
                solverOpAdd(ss, is4[3] % w, is4[3] / w, SOLVER_BLACK)
            }

            ns[0] == ns[1] && ns[0] == ns[2] ->
                solverOpAdd(ss, is4[0] % w, is4[0] / w, SOLVER_BLACK)

            ns[1] == ns[2] && ns[1] == ns[3] ->
                solverOpAdd(ss, is4[3] % w, is4[3] / w, SOLVER_BLACK)

            ns[0] == ns[1] || ns[1] == ns[3] ->
                solverOpAdd(ss, is4[2] % w, is4[2] / w, SOLVER_CIRCLE)

            ns[0] == ns[2] || ns[2] == ns[3] ->
                solverOpAdd(ss, is4[1] % w, is4[1] / w, SOLVER_CIRCLE)
        }
    }

    /** `solve_corners`, `singles.c:905-915`: the four corners in order. */
    private fun solveCorners(state: State, ss: SolverState): Int {
        val before = ss.ops.size
        solveCorner(state, ss, 0, 0, 1, 1)
        solveCorner(state, ss, state.w - 1, 0, -1, 1)
        solveCorner(state, ss, state.w - 1, state.h - 1, -1, -1)
        solveCorner(state, ss, 0, state.h - 1, 1, -1)
        return ss.ops.size - before
    }

    /** `solve_offsetpair_pair`, `singles.c:936-976`. */
    private fun solveOffsetpairPair(
        state: State,
        ss: SolverState,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ) {
        val w = state.w
        val ox: Int
        val oy: Int
        if (x1 == x2) {
            ox = 1
            oy = 0
        } else {
            require(y1 == y2) { "an offset pair must share a row or a column" }
            ox = 0
            oy = 1
        }

        val ax = x1 + ox
        val ay = y1 + oy
        require(state.inGrid(ax, ay)) { "offset pair neighbour is off the grid" }
        val an = state.nums[ay * w + ax]

        val dx = intArrayOf(x2 + ox + oy, x2 + ox - oy)
        val dy = intArrayOf(y2 + oy + ox, y2 + oy - ox)

        for (d in 0 until 2) {
            if (state.inGrid(dx[d], dy[d]) && (dx[d] != ax || dy[d] != ay)) {
                val dn = state.nums[dy[d] * w + dx[d]]
                if (an == dn) {
                    val xd = dx[d] - x2
                    val yd = dy[d] - y2
                    solverOpAdd(ss, x2 + xd, y2, SOLVER_CIRCLE)
                    solverOpAdd(ss, x2, y2 + yd, SOLVER_CIRCLE)
                }
            }
        }
    }

    /** `solve_offsetpair`, `singles.c:978-1007`. */
    private fun solveOffsetpair(state: State, ss: SolverState): Int {
        val before = ss.ops.size
        for (x in 0 until state.w - 1) {
            for (y in 0 until state.h) {
                val n1 = state.nums[y * state.w + x]
                for (yy in y + 1 until state.h) {
                    val n2 = state.nums[yy * state.w + x]
                    if (n1 == n2) {
                        solveOffsetpairPair(state, ss, x, y, x, yy)
                        solveOffsetpairPair(state, ss, x, yy, x, y)
                    }
                }
            }
        }
        for (y in 0 until state.h - 1) {
            for (x in 0 until state.w) {
                val n1 = state.nums[y * state.w + x]
                for (xx in x + 1 until state.w) {
                    val n2 = state.nums[y * state.w + xx]
                    if (n1 == n2) {
                        solveOffsetpairPair(state, ss, x, y, xx, y)
                        solveOffsetpairPair(state, ss, xx, y, x, y)
                    }
                }
            }
        }
        return ss.ops.size - before
    }

    /** `solve_hassinglewhiteregion`, `singles.c:1009-1051`. */
    private fun hasSingleWhiteRegion(state: State, ss: SolverState): Boolean {
        var nwhite = 0
        var lwhite = -1
        for (i in 0 until state.n) {
            if (state.flags[i] and F_BLACK == 0) {
                nwhite++
                lwhite = i
            }
            state.flags[i] = state.flags[i] and F_SCRATCH.inv()
        }
        if (lwhite == -1) {
            state.impossible = true
            return false
        }

        ss.scratch.fill(-1)
        ss.scratch[0] = lwhite
        state.flags[lwhite] = state.flags[lwhite] or F_SCRATCH
        var start = 0
        var end = 1
        var next = 1
        val dxs = intArrayOf(0, 1, 0, -1)
        val dys = intArrayOf(-1, 0, 1, 0)
        while (start < end) {
            for (a in start until end) {
                val i = ss.scratch[a]
                check(i != -1) { "white region scratch has an empty slot" }
                for (d in 0 until 4) {
                    val x = i % state.w + dxs[d]
                    val y = i / state.w + dys[d]
                    val j = y * state.w + x
                    if (!state.inGrid(x, y)) continue
                    if (state.flags[j] and (F_BLACK or F_SCRATCH) != 0) continue
                    ss.scratch[next++] = j
                    state.flags[j] = state.flags[j] or F_SCRATCH
                }
            }
            start = end
            end = next
        }
        return next == nwhite
    }

    /** `solve_removesplits_check`, `singles.c:1053-1071`. */
    private fun removesplitsCheck(
        state: State,
        ss: SolverState,
        x: Int,
        y: Int,
    ) {
        if (!state.inGrid(x, y)) return
        val i = y * state.w + x
        if (state.flags[i] and (F_CIRCLE or F_BLACK) != 0) return

        state.flags[i] = state.flags[i] or F_BLACK
        val issingle = hasSingleWhiteRegion(state, ss)
        state.flags[i] = state.flags[i] and F_BLACK.inv()

        if (!issingle) solverOpAdd(ss, x, y, SOLVER_CIRCLE)
    }

    /** `solve_removesplits`, `singles.c:1073-1097`. */
    private fun solveRemovesplits(state: State, ss: SolverState): Int {
        val before = ss.ops.size
        if (!hasSingleWhiteRegion(state, ss)) {
            state.impossible = true
            return 0
        }

        for (i in 0 until state.n) {
            if (state.flags[i] and F_BLACK == 0) continue
            val x = i % state.w
            val y = i / state.w
            removesplitsCheck(state, ss, x - 1, y - 1)
            removesplitsCheck(state, ss, x + 1, y - 1)
            removesplitsCheck(state, ss, x + 1, y + 1)
            removesplitsCheck(state, ss, x - 1, y + 1)
        }
        return ss.ops.size - before
    }

    /**
     * `solve_sneaky`, `singles.c:1113-1159`. A number unique in its row
     * and column is white. This is a generator and Easy-gate rule, not a
     * user rule.
     */
    private fun solveSneaky(state: State, ss: SolverState?): Int {
        var nunique = 0
        for (i in 0 until state.n) state.flags[i] = state.flags[i] and F_SCRATCH.inv()

        for (x in 0 until state.w) {
            for (y in 0 until state.h) {
                val i = y * state.w + x

                for (xx in x until state.w) {
                    val ii = y * state.w + xx
                    if (i == ii) continue
                    if (state.nums[i] == state.nums[ii]) {
                        state.flags[i] = state.flags[i] or F_SCRATCH
                        state.flags[ii] = state.flags[ii] or F_SCRATCH
                    }
                }

                for (yy in y until state.h) {
                    val ii = yy * state.w + x
                    if (i == ii) continue
                    if (state.nums[i] == state.nums[ii]) {
                        state.flags[i] = state.flags[i] or F_SCRATCH
                        state.flags[ii] = state.flags[ii] or F_SCRATCH
                    }
                }
            }
        }

        for (i in 0 until state.n) {
            if (state.flags[i] and F_SCRATCH == 0) {
                ss?.let { solverOpAdd(it, i % state.w, i / state.w, SOLVER_CIRCLE) }
                nunique++
            } else {
                state.flags[i] = state.flags[i] and F_SCRATCH.inv()
            }
        }
        return nunique
    }

    /**
     * `solve_specific`, `singles.c:1161-1195`. The technique order, the
     * `> 0` tests, and the `continue`/`break` structure decide both the
     * generator gate and the `SOLVE` text.
     */
    internal fun solveSpecific(state: State, diff: Int, sneaky: Boolean): Int {
        val ss = SolverState(state.n)

        if (sneaky) solveSneaky(state, ss)

        solveSinglesep(state, ss)
        solveDoubles(state, ss)
        solveCorners(state, ss)

        if (diff >= DIFF_TRICKY) solveOffsetpair(state, ss)

        while (true) {
            if (ss.ops.isNotEmpty()) solverOpsDo(state, ss)
            if (state.impossible) break

            if (solveAllblackbutone(state, ss) > 0) continue
            if (state.impossible) break

            if (diff >= DIFF_TRICKY) {
                if (solveRemovesplits(state, ss) > 0) continue
                if (state.impossible) break
            }

            break
        }

        return if (state.impossible) -1 else if (checkComplete(state, CC_MUST_FILL)) 1 else 0
    }

    /**
     * `solve_game`, `singles.c:1197-1217`. The seam passes only the
     * current state, so the fallback source is rebuilt from its numbers.
     * A move never changes `nums`, so that is the state a save starts
     * with.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /** `solve_game` (`singles.c:1197-1217`): both boards failed. */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State

        val solved = s.duplicate()
        if (solveSpecific(solved, DIFF_ANY, false) > 0) {
            return SolveOutcome(gameStateDiff(s, solved, true), null)
        }

        val initial = s.initial()
        if (solveSpecific(initial, DIFF_ANY, false) > 0) {
            return SolveOutcome(gameStateDiff(s, initial, true), null)
        }

        return SolveOutcome(null, "Unable to solve puzzle.")
    }

    // ------------------------------------------------------------------
    // Generation, `singles.c:1233-1436`.
    // ------------------------------------------------------------------

    /** `new_game_is_good`, `singles.c:1233-1276`. */
    private fun newGameIsGood(diff: Int, state: State, tosolve: State): Boolean {
        state.nums.copyInto(tosolve.nums)
        tosolve.flags.fill(0)
        tosolve.completed = false
        tosolve.impossible = false

        val sret = solveSpecific(tosolve, diff, false)
        var sretEasy = 0
        if (diff > DIFF_EASY) {
            tosolve.flags.fill(0)
            tosolve.completed = false
            tosolve.impossible = false
            sretEasy = solveSpecific(tosolve, diff - 1, true)
        }

        return !(sret <= 0 || sretEasy > 0)
    }

    /**
     * `best_black_col`, `singles.c:1280-1315`.
     *
     * The first pass prefers a number that occurs once in both the row
     * and the column. The second pass accepts a number that occurs in
     * either. The row and column counts are updated in place and are
     * not reset between `randomise` retries, as C does.
     *
     * The C assertion is replaced by a defined fallback: the first
     * shuffled number. The test pins it.
     */
    internal fun bestBlackCol(
        state: State,
        random: RandomState,
        scratch: IntArray,
        cell: Int,
        rownums: IntArray,
        colnums: IntArray,
    ): Int {
        val w = state.w
        val x = cell % w
        val y = cell / w
        val o = state.o

        for (i in 0 until o) scratch[i] = i
        shuffle(scratch, o, random)

        var chosen = 0
        for (i in 0 until o) {
            val j = scratch[i] + 1
            if (rownums[y * o + j - 1] == 1 && colnums[x * o + j - 1] == 1) {
                chosen = j
                break
            }
        }
        if (chosen == 0) {
            for (i in 0 until o) {
                val j = scratch[i] + 1
                if (rownums[y * o + j - 1] != 0 || colnums[x * o + j - 1] != 0) {
                    chosen = j
                    break
                }
            }
        }
        if (chosen == 0) chosen = scratch[0] + 1

        rownums[y * o + chosen - 1] += 1
        colnums[x * o + chosen - 1] += 1
        return chosen
    }

    /** `game_configure` (`singles.c:225-251`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Tricky",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`singles.c:253-262`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            cAtoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`singles.c:1474-1489`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Show numbers on black squares",
                false,
                "show-black-nums",
            ),
        )

    /** `set_prefs` (`singles.c:1491-1494`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).showBlackNums = prefs.bool("show-black-nums")
    }

    override fun newDesc(params: Game.Params, random: RandomState): Description =
        generateWithStats(params as Params, random).description

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description = generateWithStats(params as Params, random, cancel).description

    /**
     * `new_game_desc`, `singles.c:1317-1436`, with retry counters.
     *
     * A side of 62 is rejected before the first draw. The C generator
     * writes a description that contains the digit 62, its own decoder
     * rejects that `[`, and `new_game` aborts. This is D-017; every valid
     * size keeps the C stream and description. D-031 then rejects an
     * area above [MAX_AREA] before the first draw.
     */
    internal fun generateWithStats(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        require(params.w != 62 && params.h != 62) { "Puzzle is too large" }
        require(params.w.toLong() * params.h <= MAX_AREA) {
            Game.AREA_TOO_LARGE
        }

        val w = params.w
        val h = params.h
        val n = w * h
        val o = max(w, h)
        var diff = params.diff

        if ((w < 4 || h < 4) && diff > DIFF_EASY) diff = DIFF_EASY

        val state = blankState(w, h)
        val tosolve = blankState(w, h)
        val scratch = IntArray(n)
        val rownums = IntArray(h * o)
        val colnums = IntArray(w * o)
        val ss = SolverState(n)
        val stats = GenerationStats()

        while (true) {
            cancel.check()
            stats.attempts++
            ss.ops.clear()
            state.impossible = false
            state.flags.fill(0)

            val latin = generateRect(w, h, random)
            latin.copyInto(state.nums)

            for (i in 0 until n) scratch[i] = i
            shuffle(scratch, n, random)

            var restart = false
            for (j in 0 until n) {
                val i = scratch[j]
                if (state.flags[i] and (F_CIRCLE or F_BLACK) != 0) continue

                solverOpAdd(ss, i % w, i / w, SOLVER_BLACK)
                solverOpsDo(state, ss)

                solveAllblackbutone(state, ss)
                solverOpsDo(state, ss)

                solveRemovesplits(state, ss)
                solverOpsDo(state, ss)

                if (state.impossible) {
                    restart = true
                    break
                }
            }
            if (restart) {
                stats.restarts++
                continue
            }

            rownums.fill(0)
            colnums.fill(0)
            for (i in 0 until n) {
                if (state.flags[i] and F_BLACK != 0) continue
                val num = state.nums[i]
                val x = i % w
                val y = i / w
                rownums[y * o + num - 1] += 1
                colnums[x * o + num - 1] += 1
            }

            var ntries = 0
            while (true) {
                stats.randomises++
                for (i in 0 until n) {
                    if (state.flags[i] and F_BLACK == 0) continue
                    state.nums[i] = bestBlackCol(
                        state,
                        random,
                        scratch,
                        i,
                        rownums,
                        colnums,
                    )
                }

                if (diff == DIFF_ANY || newGameIsGood(diff, state, tosolve)) {
                    return Generated(Description(generateDesc(state), null), stats)
                }

                stats.gateFailures++
                ntries++
                if (ntries > MAXTRIES) {
                    stats.restarts++
                    break
                }
            }
        }
    }

    /** `shuffle` from `misc.c`, over the first [count] elements. */
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

    // ------------------------------------------------------------------
    // Moves, `singles.c:1543-1635`.
    // ------------------------------------------------------------------

    /** `move_cursor` with `wrap = true`, `misc.c:366-396`. */
    private fun moveCursor(button: Int, u: Ui, maxw: Int, maxh: Int): MoveResult {
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

        val oldX = u.cx
        val oldY = u.cy
        u.cx = (u.cx + dx + maxw) % maxw
        u.cy = (u.cy + dy + maxh) % maxh
        if (!u.cshow) {
            u.cshow = true
            return MoveResult.UiUpdate
        }
        return if (u.cx != oldX || u.cy != oldY) {
            MoveResult.UiUpdate
        } else {
            MoveResult.NoEffect
        }
    }

    /** `COORD`, `singles.c:81`. */
    private fun coord(value: Int, tileSize: Int): Int =
        value * tileSize + tileSize / 2

    /** `FROMCOORD`, `singles.c:82`; C division truncates toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 2 + tileSize) / tileSize - 1

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
        var cx = fromCoord(x, tileSize)
        var cy = fromCoord(y, tileSize)
        var action = ACTION_NONE

        if (Buttons.isCursorMove(button)) {
            return moveCursor(button, u, s.w, s.h)
        } else if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            cx = u.cx
            cy = u.cy
            if (!u.cshow) {
                action = ACTION_UI
                u.cshow = true
            }
            action = if (button == Buttons.CURSOR_SELECT) {
                ACTION_TOGGLE_BLACK
            } else {
                ACTION_TOGGLE_CIRCLE
            }
        } else if (Buttons.isMouseDown(button)) {
            if (u.cshow) {
                u.cshow = false
                action = ACTION_UI
            }
            if (!s.inGrid(cx, cy)) {
                u.showBlackNums = !u.showBlackNums
                action = ACTION_UI
            } else if (button == Buttons.LEFT_BUTTON) {
                action = ACTION_TOGGLE_BLACK
            } else if (button == Buttons.RIGHT_BUTTON) {
                action = ACTION_TOGGLE_CIRCLE
            }
        }

        if (action == ACTION_UI) return MoveResult.UiUpdate

        if (action == ACTION_TOGGLE_BLACK || action == ACTION_TOGGLE_CIRCLE) {
            val i = cy * s.w + cx
            val c = when {
                s.flags[i] and F_BLACK != 0 ->
                    if (action == ACTION_TOGGLE_BLACK) 'C' else 'E'

                s.flags[i] and F_CIRCLE != 0 ->
                    if (action == ACTION_TOGGLE_BLACK) 'E' else 'B'

                else -> if (action == ACTION_TOGGLE_BLACK) 'B' else 'C'
            }
            return MoveResult.Move("$c$cx,$cy")
        }

        return MoveResult.Unused
    }

    private const val ACTION_NONE = 0
    private const val ACTION_TOGGLE_BLACK = 1
    private const val ACTION_TOGGLE_CIRCLE = 2
    private const val ACTION_UI = 3

    /** The `%d,%d%n` scan of `execute_move`, with the C scan rules. */
    private data class ScannedCell(val count: Int, val x: Int, val y: Int, val end: Int)

    /**
     * `sscanf(text, "%d,%d%n", &x, &y, &n)`: leading whitespace, an
     * optional sign, digits, then a literal comma and the second number.
     * A number that overflows an `int` saturates, the defined result the
     * other ports use. [end] is the consumed length when [count] is 2.
     */
    private fun scanCell(text: String, start: Int): ScannedCell {
        var index = start
        while (index < text.length && isCWhitespace(text[index])) index++

        val first = scanInt(text, index)
        if (first.second == 0) return ScannedCell(0, 0, 0, start)
        index = first.second
        if (index >= text.length || text[index] != ',') {
            return ScannedCell(1, 0, 0, index)
        }
        index++
        while (index < text.length && isCWhitespace(text[index])) index++

        val second = scanInt(text, index)
        if (second.second == 0) return ScannedCell(1, 0, 0, index)
        return ScannedCell(2, first.first, second.first, second.second)
    }

    /** `%d` from [start]; the length is 0 when there is no number. */
    private fun scanInt(text: String, start: Int): Pair<Int, Int> {
        var index = start
        var sign = 1L
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            if (text[index] == '-') sign = -1L
            index++
        }
        val digitsStart = index
        var value = 0L
        while (index < text.length && text[index].isAsciiDigit()) {
            val digit = text[index] - '0'
            value = if (value > (Long.MAX_VALUE - digit) / 10) {
                Long.MAX_VALUE
            } else {
                value * 10 + digit
            }
            index++
        }
        if (index == digitsStart) return Pair(0, 0)
        val signed = sign * value
        val result = when {
            signed > Int.MAX_VALUE -> Int.MAX_VALUE
            signed < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> signed.toInt()
        }
        return Pair(result, index)
    }

    /** `execute_move`, `singles.c:1596-1635`. */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val ret = (state as State).duplicate()
        var index = 0

        while (index < move.length) {
            val c = move[index]
            if (c == 'B' || c == 'C' || c == 'E') {
                index++
                val scanned = scanCell(move, index)
                if (scanned.count != 2 || !ret.inGrid(scanned.x, scanned.y)) {
                    return null
                }
                val i = scanned.y * ret.w + scanned.x
                ret.flags[i] = ret.flags[i] and (F_CIRCLE or F_BLACK).inv()
                if (c == 'B') {
                    ret.flags[i] = ret.flags[i] or F_BLACK
                } else if (c == 'C') {
                    ret.flags[i] = ret.flags[i] or F_CIRCLE
                }
                index = scanned.end
            } else if (c == 'S') {
                index++
                ret.usedSolve = true
            } else {
                return null
            }

            if (index < move.length && move[index] == ';') {
                index++
            } else if (index < move.length) {
                return null
            }
        }

        if (checkComplete(ret, CC_MARK_ERRORS)) ret.completed = true
        return ret
    }

    // ------------------------------------------------------------------
    // UI, keys, and status, `singles.c:1458-1525`, `1813-1829`.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `android_cursor_visibility`, `singles.c:1501-1504`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cshow = visible
    }

    /**
     * `game_changed_state`, `singles.c:1506-1513`. The cursor hides on a
     * transition into a completed state. The return value is the
     * one-shot completion event and excludes a solve.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val n = newState as State
        if (oldState != null) {
            val o = oldState as State
            if (!o.completed && n.completed) u.cshow = false
        }
        return n.completed && !n.usedSolve && oldState != null &&
            !(oldState as State).completed
    }

    /**
     * `current_key_label`, `singles.c:1515-1525`. The seam has no
     * operation for this, so it is a public helper with a test.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.cshow
        ) {
            val f = s.flags[u.cy * s.w + u.cx]
            if (f and F_BLACK != 0) return "Restore"
            if (f and F_CIRCLE != 0) return "Remove"
            return if (button == Buttons.CURSOR_SELECT) "Black" else "Circle"
        }
        return ""
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the given digit, then the player's mark,
     * `black` or `circle`. The pointer path toggles those two marks and
     * cannot type a digit or a pencil note; entry stays on the key path.
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
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * s.w + x
                val flags = s.flags[index]
                val mark = when {
                    flags and F_BLACK != 0 -> ", black"
                    flags and F_CIRCLE != 0 -> ", circle"
                    else -> ""
                }
                val cursor = if (u.cshow && u.cx == x && u.cy == y) ", cursor" else ""
                val wrong = if (flags and F_ERROR != 0) SPOKEN_WRONG else ""
                "${s.nums[index]}$mark$cursor$wrong"
            },
            actions = { x, y ->
                when {
                    s.flags[y * s.w + x] and F_BLACK != 0 ->
                        listOf(SpokenAction("Restore", Buttons.LEFT_BUTTON))

                    s.flags[y * s.w + x] and F_CIRCLE != 0 ->
                        listOf(SpokenAction("Remove", Buttons.LEFT_BUTTON))

                    else -> listOf(
                        SpokenAction("Black", Buttons.LEFT_BUTTON),
                        SpokenAction("Circle", Buttons.RIGHT_BUTTON),
                    )
                }
            },
        )
        val cursor = cells.units.getOrNull(u.cy * s.w + u.cx)
        return cells.capped(cursor)
    }

    /**
     * `game_request_keys` is NULL, so the midend leaves the arrow mode
     * at the preset Left/Right and returns no labels. The Android
     * full-cursor hint forces `ANDROID_ARROWS_ONLY` before the C midend
     * calls a game with no `request_keys`
     * (`midend_request_keys_by_game`), so the two-argument form keeps
     * the empty label list and changes only the arrow mode.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `game_get_cursor_location`, `singles.c:1813-1824`. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cshow) return null
        return Game.CursorRect(
            left = coord(u.cx, tileSize),
            top = coord(u.cy, tileSize),
            width = tileSize,
            height = tileSize,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // Geometry and palette, `singles.c:1641-1681`.
    // ------------------------------------------------------------------

    /** `game_compute_size`, `singles.c:1641-1650`. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 2
        return Size(
            p.w * tileSize + 2 * border,
            p.h * tileSize + 2 * border,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`, `singles.c:1658-1681`. The background is the
     * shell's; the lowlight comes from `game_mkhighlight`, and the grid
     * colour is that lowlight. `COL_UNUSED1` exists only to satisfy the
     * upstream palette assertion.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, -1, COL_LOWLIGHT)

        for (i in 0 until 3) {
            ret[COL_BLACK * 3 + i] = 0.0f
            ret[COL_BLACKNUM * 3 + i] = 0.4f
            ret[COL_WHITE * 3 + i] = 1.0f
            ret[COL_GRID * 3 + i] = ret[COL_LOWLIGHT * 3 + i]
            ret[COL_UNUSED1 * 3 + i] = 0.0f
        }
        ret[COL_CURSOR * 3 + 0] = 0.2f
        ret[COL_CURSOR * 3 + 1] = 0.8f
        ret[COL_CURSOR * 3 + 2] = 0.0f

        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f
        return ret
    }

    // ------------------------------------------------------------------
    // Drawing, `singles.c:1683-1796`.
    // ------------------------------------------------------------------

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(tileSize = tileSize, w = s.w, h = s.h)
    }

    /** `draw_rect_outline`, `misc.c:326-341`: four points, no fill. */
    private fun rectOutline(
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

    /** `draw_rect_corners`, `misc.c:343-354`: eight short lines. */
    private fun rectCorners(
        sink: DrawSink,
        cx: Int,
        cy: Int,
        r: Int,
        colour: Int,
    ) {
        sink.line(cx - r, cy - r, cx - r, cy - r / 2, colour)
        sink.line(cx - r, cy - r, cx - r / 2, cy - r, colour)
        sink.line(cx - r, cy + r, cx - r, cy + r / 2, colour)
        sink.line(cx - r, cy + r, cx - r / 2, cy + r, colour)
        sink.line(cx + r, cy - r, cx + r, cy - r / 2, colour)
        sink.line(cx + r, cy - r, cx + r / 2, cy - r, colour)
        sink.line(cx + r, cy + r, cx + r, cy + r / 2, colour)
        sink.line(cx + r, cy + r, cx + r / 2, cy + r, colour)
    }

    /** `tile_redraw`, `singles.c:1707-1749`. */
    private fun tileRedraw(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        num: Int,
        f: Int,
    ) {
        val tile = ds.tileSize
        val crad = tile / 2 - 1
        val textsz = 14 * crad / 10 - 1
        val bg: Int
        val tcol: Int
        val dnum: Boolean

        if (f and DS_BLACK != 0) {
            bg = if (f and DS_ERROR != 0) COL_ERROR else COL_BLACK
            tcol = COL_BLACKNUM
            dnum = f and DS_BLACK_NUM != 0
        } else {
            bg = if (f and DS_FLASH != 0) COL_LOWLIGHT else COL_BACKGROUND
            tcol = if (f and DS_ERROR != 0) COL_ERROR else COL_BLACK
            dnum = true
        }

        val cx = x + tile / 2
        val cy = y + tile / 2

        sink.rect(x, y, tile, tile, bg)
        rectOutline(
            sink,
            x,
            y,
            tile,
            tile,
            if (f and DS_IMPOSSIBLE != 0) COL_ERROR else COL_GRID,
        )

        if (f and DS_CIRCLE != 0) {
            sink.circle(cx, cy, crad, tcol, tcol)
            sink.circle(cx, cy, crad - 1, bg, tcol)
        }

        if (dnum) {
            val buf = num.toString()
            val tsz = if (buf.length == 1) {
                textsz
            } else {
                (crad * 2 - 1) / buf.length
            }
            sink.text(
                cx,
                cy,
                Text.FONT_VARIABLE,
                tsz,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                tcol,
                buf,
            )
        }

        if (f and DS_CURSOR != 0) {
            rectCorners(sink, cx, cy, textsz / 2, COL_CURSOR)
        }

        sink.update(x, y, tile, tile)
    }

    /**
     * `game_redraw`, `singles.c:1751-1796`. The seam has no flash time,
     * so `DS_FLASH` is never set. The loop is column-major, as C is.
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
        val tile = ds.tileSize
        val border = tile / 2

        if (!ds.started) {
            val wsz = tile * s.w + 2 * border
            val hsz = tile * s.h + 2 * border
            rectOutline(
                sink,
                coord(0, tile) - 1,
                coord(0, tile) - 1,
                tile * s.w + 2,
                tile * s.h + 2,
                COL_GRID,
            )
            sink.update(0, 0, wsz, hsz)
        }

        for (x in 0 until s.w) {
            for (y in 0 until s.h) {
                val i = y * s.w + x
                var f = 0

                if (s.impossible) f = f or DS_IMPOSSIBLE

                if (u.cshow && x == u.cx && y == u.cy) f = f or DS_CURSOR
                if (s.flags[i] and F_BLACK != 0) {
                    f = f or DS_BLACK
                    if (u.showBlackNums) f = f or DS_BLACK_NUM
                }
                if (s.flags[i] and F_CIRCLE != 0) f = f or DS_CIRCLE
                if (s.flags[i] and F_ERROR != 0) f = f or DS_ERROR

                if (!ds.started || ds.flags[i] != f) {
                    tileRedraw(sink, ds, coord(x, tile), coord(y, tile), s.nums[i], f)
                    ds.flags[i] = f
                }
            }
        }
        ds.started = true
    }

    // ------------------------------------------------------------------
    // Small C helpers.
    // ------------------------------------------------------------------

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun isCWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r'

    /**
     * `atoi` from [start]. A number that overflows an `int` saturates,
     * which is the port's defined result for the C undefined behavior.
     */
    private fun cAtoi(text: String, start: Int): Int {
        var index = start
        while (index < text.length && isCWhitespace(text[index])) index++
        var sign = 1L
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            if (text[index] == '-') sign = -1L
            index++
        }
        val digitsStart = index
        var value = 0L
        while (index < text.length && text[index].isAsciiDigit()) {
            val digit = text[index] - '0'
            value = if (value > (Long.MAX_VALUE - digit) / 10) {
                Long.MAX_VALUE
            } else {
                value * 10 + digit
            }
            index++
        }
        if (index == digitsStart) return 0
        val signed = sign * value
        return when {
            signed > Int.MAX_VALUE -> Int.MAX_VALUE
            signed < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> signed.toInt()
        }
    }
}
