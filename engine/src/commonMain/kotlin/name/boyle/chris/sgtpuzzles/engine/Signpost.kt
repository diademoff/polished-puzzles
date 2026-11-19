package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A port of upstream `signpost.c`: link every cell into one arrow path
 * from 1 to `n`.
 *
 * Each cell has a fixed arrow. The player links one cell to the cell the
 * arrow points at, and the game numbers the linked chains. The rules,
 * the seed, the description, and the save format are upstream behavior.
 *
 * The code keeps the C structure on purpose:
 * - the generator fills a random arrow path, then strips numbers with a
 *   solver-driven loop. The only draws are the free-ends head/tail pair,
 *   one draw per filled cell, and one `shuffle` of the cell indices;
 * - [State] carries the initial state, because the seam's `solve`
 *   receives only the current state and C tries the current state first
 *   and the initial state second;
 * - `numsi` is cleared only up to `n-1` and is written by both the
 *   immutable index and the chain walk, exactly as C does;
 * - `completed` is sticky and `usedSolve` is set only by an `S` move;
 * - `dirs` never changes after a game is loaded.
 *
 * The text form reproduces the C column bound of `state->h`, so a board
 * wider than it is tall loses its last columns. C reads past the arrays
 * when `w < h`; this port defines those cells as direction N, number 0,
 * and no flags. See [textFormat].
 *
 * Enablement debt: `validateParams` accepts an area up to `INT_MAX`
 * cells, and generation is synchronous. The app must generate off the UI
 * thread; a size bound is an owner decision and is not added here.
 */
object Signpost : Game {

    /** The arrow directions of `signpost.c:56-60`. */
    private const val DIR_N = 0
    private const val DIR_NE = 1
    private const val DIR_E = 2
    private const val DIR_SE = 3
    private const val DIR_S = 4
    private const val DIR_SW = 5
    private const val DIR_W = 6
    private const val DIR_NW = 7
    private const val DIR_MAX = 8

    private val DIR_STRINGS = arrayOf("N ", "NE", "E ", "SE", "S ", "SW", "W ", "NW")
    private val DXS = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val DYS = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    /** `FLAG_IMMUTABLE` and `FLAG_ERROR` of `signpost.c:75-76`. */
    internal const val FLAG_IMMUTABLE = 1
    internal const val FLAG_ERROR = 2

    /** The palette of `signpost.c:33-43`. */
    private const val COL_BACKGROUND = 0
    private const val COL_HIGHLIGHT = 1
    private const val COL_LOWLIGHT = 2
    private const val COL_GRID = 3
    private const val COL_CURSOR = 4
    private const val COL_ERROR = 5
    private const val COL_DRAG_ORIGIN = 6
    private const val COL_ARROW = 7
    private const val COL_ARROW_BG_DIM = 8
    private const val COL_EMPTY = 9
    private const val COL_NUMBER0 = 10
    private const val COL_NUMBER = 11
    private const val COL_NUMBER_SET = 12
    private const val COL_NUMBER_SET_MID = 13
    private const val COL_B0 = 14
    private const val COL_M0 = COL_B0 + 16
    private const val COL_D0 = COL_B0 + 32
    private const val COL_X0 = COL_B0 + 48
    internal const val NCOLOURS = COL_B0 + 4 * 16

    /** The drawstate flags of `signpost.c:1958-1964`. */
    private const val F_CUR = 0x001
    private const val F_DRAG_SRC = 0x002
    private const val F_ERROR = 0x004
    private const val F_IMMUTABLE = 0x008
    private const val F_ARROW_POINT = 0x010
    private const val F_ARROW_INPOINT = 0x020
    private const val F_DIM = 0x040

    private const val PREFERRED_TILE_SIZE = 48

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val forceCornerStart: Boolean,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, forceCornerStart)
    }

    /**
     * `struct game_state`. The arrays are the C arrays; [dsf] is derived
     * from [next] and [prev] by [updateNumbers], but it lives here so
     * `isvalidmove` and the solver see the same classes and sizes.
     *
     * [initial] is a port shape for the two-state solve: C's `solve_game`
     * reads `states[0].state` as its fallback, and the seam hands the
     * game only the current state. A state that is the initial state has
     * `initial == null` (its own state is the fallback). Every other
     * state points at the initial state object. Nothing mutates a state
     * after it is returned, so a shared reference is safe.
     */
    class State internal constructor(
        val w: Int,
        val h: Int,
        val dirs: IntArray,
        val nums: IntArray,
        val flags: IntArray,
        val next: IntArray,
        val prev: IntArray,
        val dsf: Dsf,
        val numsi: IntArray,
        val completed: Boolean,
        val usedSolve: Boolean,
        impossible: Boolean,
        val initial: State?,
    ) : Game.State {

        /** The cell count, `w * h`. */
        val n: Int get() = w * h

        /**
         * A chain or region contradicts itself. C sets it in
         * `head_number` and `connect_numbers`; it is never cleared.
         */
        internal var impossible: Boolean = impossible

        fun dirAt(x: Int, y: Int): Int = dirs[y * w + x]

        fun numAt(x: Int, y: Int): Int = nums[y * w + x]

        fun isImmutable(x: Int, y: Int): Boolean = flags[y * w + x] and FLAG_IMMUTABLE != 0

        fun isError(x: Int, y: Int): Boolean = flags[y * w + x] and FLAG_ERROR != 0

        /** A deep copy of the arrays, as `dup_game`. */
        override fun copy(): Game.State = duplicate()

        internal fun duplicate(): State = State(
            w,
            h,
            dirs.copyOf(),
            nums.copyOf(),
            flags.copyOf(),
            next.copyOf(),
            prev.copyOf(),
            dsf.copy(),
            numsi.copyOf(),
            completed,
            usedSolve,
            impossible,
            initial,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                w == other.w &&
                h == other.h &&
                dirs.contentEquals(other.dirs) &&
                nums.contentEquals(other.nums) &&
                flags.contentEquals(other.flags) &&
                next.contentEquals(other.next) &&
                prev.contentEquals(other.prev) &&
                numsi.contentEquals(other.numsi) &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                impossible == other.impossible

        override fun hashCode(): Int {
            var result = w
            result = 31 * result + h
            result = 31 * result + dirs.contentHashCode()
            result = 31 * result + nums.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + next.contentHashCode()
            result = 31 * result + prev.contentHashCode()
            result = 31 * result + numsi.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + impossible.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`. C reads `PUZZLES_SHOW_CURSOR` and the legacy
     * `SIGNPOST_GEARS` variable; the port has no environment, so the
     * cursor and the gear preference start off.
     *
     * [gearMode] is the `flash-type` preference. It only selects the
     * sign of the victory flash rotation, and the flash is not ported, so
     * it changes no state, no palette, and no trace.
     */
    class Ui : Game.Ui {
        var cx: Int = 0
        var cy: Int = 0
        var cshow: Boolean = false
        var dragging: Boolean = false
        var dragIsFrom: Boolean = false
        var sx: Int = 0
        var sy: Int = 0
        var dx: Int = 0
        var dy: Int = 0
        var gearMode: Boolean = false

        internal fun duplicate(): Ui = Ui().also {
            it.cx = cx
            it.cy = cy
            it.cshow = cshow
            it.dragging = dragging
            it.dragIsFrom = dragIsFrom
            it.sx = sx
            it.sy = sy
            it.dx = dx
            it.dy = dy
            it.gearMode = gearMode
        }
    }

    /**
     * `struct game_drawstate`. One `T x T` blitter holds the dragged
     * arrow preview. C has a dead `solved` field; the port omits it.
     */
    class DrawState internal constructor(
        val tileSize: Int,
        val width: Int,
        val height: Int,
        val blitter: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val nums: IntArray = IntArray(width * height)
        val dirp: IntArray = IntArray(width * height) { -1 }
        val f: IntArray = IntArray(width * height)
        var angleOffset: Double = 0.0
        var dragging: Boolean = false
        var dragX: Int = 0
        var dragY: Int = 0

        val n: Int get() = width * height
    }

    /** One generated description and the two retry counts. */
    data class Generation(
        val desc: String,
        val fillRetries: Int,
        val stripRetries: Int,
    )

    /** The solver result: a move, or one of the two C error strings. */
    data class SolveAttempt(val move: String?, val error: String?)

    override val name: String = "Signpost"
    override val backendName: String = "signpost"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true

    /** `default_params`: `4x4` with both ends in corners. */
    override fun defaultParams(): Game.Params = Params(4, 4, true)

    /**
     * `signpost_presets` and `game_fetch_preset`: six entries. The title
     * is `WxH` for a corner game and `WxH, free ends` otherwise, and the
     * params string is the full encoding.
     */
    override fun presets(): List<Preset> = listOf(
        Preset("4x4", "4x4c"),
        Preset("4x4, free ends", "4x4"),
        Preset("5x5", "5x5c"),
        Preset("5x5, free ends", "5x5"),
        Preset("6x6", "6x6c"),
        Preset("7x7", "7x7c"),
    )

    /**
     * `decode_params`. One number sets both dimensions, the digit skip
     * starts at the beginning of the string, only a lower-case `x`
     * separates the sizes, and a following `c` requests corner ends.
     * Everything after that is ignored.
     */
    override fun decodeParams(text: String): Game.Params {
        var w = atoi(text, 0)
        var h = w
        var index = 0
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == 'x') {
            index++
            h = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        var corner = false
        if (index < text.length && text[index] == 'c') {
            index++
            corner = true
        }
        return Params(w, h, corner)
    }

    /** `encode_params`: the short form always drops the corner flag. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val base = "${p.w}x${p.h}"
        return if (full && p.forceCornerStart) base + "c" else base
    }

    /** `validate_params`, in the C order and with the C messages. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 1) return "Width must be at least one"
        if (p.h < 1) return "Height must be at least one"
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (full && p.w == 1 && p.h == 1) {
            return "Width and height cannot both be one"
        }
        return null
    }

    // ------------------------------------------------------------------
    // Description decoder and text generator.
    // ------------------------------------------------------------------

    /**
     * `unpick_desc`: one `(digits? letter)` group per cell in row-major
     * order. A letter of `a` to `h` is the arrow, the digits before it
     * are the number, and a non-zero number is immutable.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? =
        unpickDesc(params as Params, desc).error

    private class Unpicked(val state: State?, val error: String?)

    private fun unpickDesc(params: Params, desc: String): Unpicked {
        val state = blankGame(params.w, params.h)
        val n = state.n
        var num = 0
        var i = 0
        var index = 0
        while (index < desc.length) {
            if (i >= n) return Unpicked(null, "Game description longer than expected")
            val c = desc[index]
            if (c in '0'..'9') {
                // C accumulates in an int and is undefined on overflow.
                // The port saturates, so an overlong run reports the
                // number range error instead of wrapping into range.
                num = if (num > (Int.MAX_VALUE - (c - '0')) / 10) {
                    Int.MAX_VALUE
                } else {
                    num * 10 + (c - '0')
                }
                if (num > n) return Unpicked(null, "Number out of range")
            } else if (c >= 'a' && c <= 'h') {
                state.nums[i] = num
                state.flags[i] = if (num != 0) FLAG_IMMUTABLE else 0
                num = 0
                state.dirs[i] = c - 'a'
                i++
            } else {
                return Unpicked(null, "Invalid character in game description")
            }
            index++
        }
        if (i < n) return Unpicked(null, "Game description shorter than expected")
        return Unpicked(state, null)
    }

    /**
     * `generate_desc`: one `%d%c` or `%c` per cell. The solver payload
     * has a leading `S`.
     */
    private fun generateDesc(state: State, isSolve: Boolean): String {
        val out = StringBuilder()
        if (isSolve) out.append('S')
        for (i in 0 until state.n) {
            if (state.nums[i] != 0) out.append(state.nums[i])
            out.append('a' + state.dirs[i])
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // State construction.
    // ------------------------------------------------------------------

    /**
     * `new_game`: decode, number the chains, and auto-link consecutive
     * immutable numbers. It does not set `completed`, so a description
     * that is already a full solution loads at status 0.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val unpicked = unpickDesc(p, desc)
        unpicked.error?.let { error("signpost: $it") }
        val state = checkNotNull(unpicked.state)
        updateNumbers(state)
        checkCompletion(state, true)
        return state
    }

    private fun blankGame(w: Int, h: Int): State = State(
        w = w,
        h = h,
        dirs = IntArray(w * h),
        nums = IntArray(w * h),
        flags = IntArray(w * h),
        next = IntArray(w * h) { -1 },
        prev = IntArray(w * h) { -1 },
        dsf = Dsf.new(w * h),
        numsi = IntArray(w * h + 1) { -1 },
        completed = false,
        usedSolve = false,
        impossible = false,
        initial = null,
    )

    private fun blankGameInto(state: State) {
        state.dirs.fill(0)
        state.nums.fill(0)
        state.flags.fill(0)
        state.next.fill(-1)
        state.prev.fill(-1)
        state.numsi.fill(-1)
    }

    /** `dup_game_to`: copies the flags, the arrays, and the DSF. */
    private fun copyFrom(target: State, source: State) {
        source.dirs.copyInto(target.dirs)
        source.nums.copyInto(target.nums)
        source.flags.copyInto(target.flags)
        source.next.copyInto(target.next)
        source.prev.copyInto(target.prev)
        source.dsf.copyInto(target.dsf)
        source.numsi.copyInto(target.numsi)
        target.impossible = source.impossible
    }

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    /** `game_configure` (`signpost.c:396-421`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Bool("Start and end in corners", p.forceCornerStart),
        )
    }

    /** `custom_params` (`signpost.c:423-432`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Bool).value,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`signpost.c:1472-1490`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                "Victory rotation effect",
                ":Unidirectional:Meshing gears",
                ":unidirectional:gears",
                0,
                "flash-type",
            ),
        )

    /** `set_prefs` (`signpost.c:1492-1495`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).gearMode = prefs.choice("flash-type") == 1
    }

    /**
     * `new_game_desc`. The result depends only on the parameters and the
     * random stream: the free-ends pair, one draw per filled cell, the
     * `shuffle`, and the whole-generator retries all draw in order.
     * The description has no `AUXINFO`.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val generation = generate(params as Params, random)
        return Description(generation.desc, null)
    }

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val generation = generate(params as Params, random, cancel)
        return Description(generation.desc, null)
    }

    // `signpost.c` refills and re-strips through the solver until an attempt passes, with no retry bound; 20x20 is eight times the largest 7x7 preset.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w.toLong() * p.h >= 400) Game.SLOW_GENERATION_WARNING else null
    }

    /** [newDesc] with the two retry counts, for the engine tests. */
    fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params as Params, random)

    private fun generate(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generation {
        val state = blankGame(params.w, params.h)

        // `validate_params` refuses both dimensions of one, but C guards
        // the generator as well and draws nothing.
        if (params.w == 1 && params.h == 1) {
            return Generation("1a", 0, 0)
        }

        val n = state.n
        var fillRetries = 0
        var stripRetries = 0
        var headi: Int
        var taili: Int
        while (true) {
            cancel.check()
            blankGameInto(state)
            do {
                if (params.forceCornerStart) {
                    headi = 0
                    taili = n - 1
                } else {
                    do {
                        headi = random.upto(n.toLong()).toInt()
                        taili = random.upto(n.toLong()).toInt()
                    } while (headi == taili)
                }
            } while (!newGameFill(state, random, headi, taili).also {
                    if (!it) fillRetries++
                })

            state.flags[headi] = state.flags[headi] or FLAG_IMMUTABLE
            state.flags[taili] = state.flags[taili] or FLAG_IMMUTABLE

            if (newGameStrip(state, random)) break
            stripRetries++
        }

        stripNums(state)
        return Generation(generateDesc(state, false), fillRetries, stripRetries)
    }

    /** `cell_adj`: non-numbered cells along every ray, in direction order. */
    private fun cellAdj(state: State, i: Int, ai: IntArray, ad: IntArray): Int {
        var count = 0
        val w = state.w
        val h = state.h
        val sx = i % w
        val sy = i / w
        for (a in 0 until DIR_MAX) {
            var x = sx
            var y = sy
            while (true) {
                x += DXS[a]
                y += DYS[a]
                if (x < 0 || y < 0 || x >= w || y >= h) break
                val newi = y * w + x
                if (state.nums[newi] == 0) {
                    ai[count] = newi
                    ad[count] = a
                    count++
                }
            }
        }
        return count
    }

    /**
     * `new_game_fill`: fill one path from `headi` to `taili`. It draws
     * once per added cell, even when only one cell is available. On
     * failure it returns false after the numbers were zeroed; C retries
     * on the same stream without clearing the arrows.
     */
    private fun newGameFill(
        state: State,
        random: RandomState,
        headStart: Int,
        tailStart: Int,
    ): Boolean {
        var headi = headStart
        var taili = tailStart
        val n = state.n
        val aidx = IntArray(n)
        val adir = IntArray(n)

        state.nums.fill(0)
        state.nums[headi] = 1
        state.nums[taili] = n
        state.dirs[taili] = DIR_N
        var nfilled = 2

        while (nfilled < n) {
            var an = cellAdj(state, headi, aidx, adir)
            do {
                if (an == 0) return false
                val j = random.upto(an.toLong()).toInt()
                state.dirs[headi] = adir[j]
                state.nums[aidx[j]] = state.nums[headi] + 1
                nfilled++
                headi = aidx[j]
                an = cellAdj(state, headi, aidx, adir)
            } while (an == 1)

            if (nfilled == n) break

            an = cellAdj(state, taili, aidx, adir)
            do {
                if (an == 0) return false
                val j = random.upto(an.toLong()).toInt()
                state.dirs[aidx[j]] = opposite(adir[j])
                state.nums[aidx[j]] = state.nums[taili] - 1
                nfilled++
                taili = aidx[j]
                an = cellAdj(state, taili, aidx, adir)
            } while (an == 1)
        }

        state.dirs[headi] = whichdiri(state, headi, taili)
        return state.dirs[headi] != -1
    }

    /**
     * `new_game_strip`: keep only the immutable numbers the solver
     * needs. When the stripped board solves at once, C returns before it
     * shuffles the scratch list, so the port does the same.
     */
    private fun newGameStrip(state: State, random: RandomState): Boolean {
        val n = state.n
        val copy = state.duplicate()

        stripNums(copy)
        if (solveState(copy) > 0) return true

        val scratch = IntArray(n) { it }
        Divvy.shuffle(scratch, n, random)

        for (i in 0 until n) {
            val j = scratch[i]
            if (copy.nums[j] > 0 && copy.nums[j] <= n) continue
            copy.nums[j] = state.nums[j]
            copy.flags[j] = copy.flags[j] or FLAG_IMMUTABLE
            state.flags[j] = state.flags[j] or FLAG_IMMUTABLE
            stripNums(copy)
            if (solveState(copy) > 0) {
                for (k in 0 until n) {
                    val jj = scratch[k]
                    if ((state.flags[jj] and FLAG_IMMUTABLE) != 0 &&
                        state.nums[jj] != 1 && state.nums[jj] != n
                    ) {
                        state.flags[jj] = state.flags[jj] and FLAG_IMMUTABLE.inv()
                        copyFrom(copy, state)
                        stripNums(copy)
                        if (solveState(copy) <= 0) {
                            copy.nums[jj] = state.nums[jj]
                            state.flags[jj] = state.flags[jj] or FLAG_IMMUTABLE
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * `strip_nums`: zero every non-immutable number, drop the links,
     * clear `numsi`, and re-initialise the DSF. The arrows are kept.
     */
    private fun stripNums(state: State) {
        for (i in 0 until state.n) {
            if (state.flags[i] and FLAG_IMMUTABLE == 0) state.nums[i] = 0
        }
        state.next.fill(-1)
        state.prev.fill(-1)
        state.numsi.fill(-1)
        state.dsf.reinit()
    }

    // ------------------------------------------------------------------
    // Linked-chain numbering.
    // ------------------------------------------------------------------

    private fun colour(state: State, value: Int): Int = value / (state.n + 1)

    private fun start(state: State, colour: Int): Int = colour * (state.n + 1)

    private class HeadMeta(
        val i: Int,
        val sz: Int,
        var startValue: Int,
        var preference: Int,
    )

    /**
     * `head_number`: find the start number and the colour preference of
     * one chain head. A chain with disagreeing immutable numbers marks
     * the state impossible.
     */
    private fun headNumber(state: State, i: Int): HeadMeta {
        check(state.prev[i] == -1 && state.next[i] != -1) { "not a chain head" }
        val n = state.n
        val head = HeadMeta(i, state.dsf.size(i), 0, 0)
        var off = 0
        var j = i
        while (j != -1) {
            if (state.flags[j] and FLAG_IMMUTABLE != 0) {
                val ss = state.nums[j] - off
                if (head.preference == 0) {
                    head.startValue = ss
                    head.preference = 1
                } else if (head.startValue != ss) {
                    state.impossible = true
                }
            }
            off++
            j = state.next[j]
        }
        if (head.preference != 0) return head

        if (state.nums[i] == 0 && state.nums[state.next[i]] > n) {
            head.startValue = start(state, colour(state, state.nums[state.next[i]]))
            head.preference = 1
        } else if (state.nums[i] <= n) {
            head.startValue = 0
            head.preference = 0
        } else {
            val c = colour(state, state.nums[i])
            var count = 1
            val size = state.dsf.size(i)
            j = i
            while (state.next[j] != -1) {
                j = state.next[j]
                if (state.nums[j] == 0 && state.next[j] == -1) {
                    head.startValue = start(state, c)
                    head.preference = 1
                    return head
                }
                if (colour(state, state.nums[j]) == c) {
                    count++
                } else {
                    val alternate = start(state, colour(state, state.nums[j]))
                    head.startValue = if (count < size - count) alternate else start(state, c)
                    head.preference = 1
                    return head
                }
            }
            if (c == 0) {
                head.startValue = 0
                head.preference = 0
            } else {
                head.startValue = start(state, c)
                head.preference = 1
            }
        }
        return head
    }

    private fun connectNumbers(state: State) {
        state.dsf.reinit()
        for (i in 0 until state.n) {
            if (state.next[i] != -1) {
                val di = state.dsf.canonify(i)
                val dni = state.dsf.canonify(state.next[i])
                if (di == dni) state.impossible = true
                state.dsf.merge(di, dni)
            }
        }
    }

    /** The total order of `compare_heads`. */
    private val HEAD_ORDER = Comparator<HeadMeta> { a, b ->
        when {
            a.preference != 0 && b.preference == 0 -> -1
            b.preference != 0 && a.preference == 0 -> 1
            a.startValue != b.startValue -> a.startValue.compareTo(b.startValue)
            a.sz != b.sz -> b.sz.compareTo(a.sz)
            a.i != b.i -> b.i.compareTo(a.i)
            else -> 0
        }
    }

    /** `lowest_start`: the lowest colour from 1 that no head uses. */
    private fun lowestStart(state: State, heads: List<HeadMeta>): Int {
        for (c in 1 until state.n) {
            var used = false
            for (head in heads) {
                if (colour(state, head.startValue) == c) {
                    used = true
                    break
                }
            }
            if (!used) return c
        }
        error("signpost: no available colours")
    }

    /**
     * `update_numbers`: rebuild `numsi`, the DSF, the chain numbering, and
     * the region colours. `numsi` is cleared only up to `n-1`, and the
     * chain walk writes `numsi[n]`, exactly as C does.
     */
    private fun updateNumbers(state: State) {
        val n = state.n
        for (i in 0 until n) state.numsi[i] = -1
        for (i in 0 until n) {
            if (state.flags[i] and FLAG_IMMUTABLE != 0) {
                check(state.nums[i] > 0 && state.nums[i] <= n) {
                    "signpost: immutable number out of range"
                }
                state.numsi[state.nums[i]] = i
            } else if (state.prev[i] == -1 && state.next[i] == -1) {
                state.nums[i] = 0
            }
        }
        connectNumbers(state)

        val heads = ArrayList<HeadMeta>(n)
        for (i in 0 until n) {
            if (state.prev[i] != -1 || state.next[i] == -1) continue
            heads += headNumber(state, i)
        }
        heads.sortWith(HEAD_ORDER)

        for (index in heads.indices.reversed()) {
            if (index != 0 && heads[index].startValue == heads[index - 1].startValue) {
                heads[index].startValue = start(state, lowestStart(state, heads))
                heads[index].preference = -1
            } else if (heads[index].preference == 0) {
                heads[index].startValue = start(state, lowestStart(state, heads))
            }
        }

        for (head in heads) {
            var number = head.startValue
            var j = head.i
            while (j != -1) {
                if (state.flags[j] and FLAG_IMMUTABLE == 0) {
                    if (number > 0 && number <= n) state.numsi[number] = j
                    state.nums[j] = number
                }
                number++
                j = state.next[j]
            }
        }
    }

    /**
     * `check_completion`: mark repeated numbers, mark consecutive pairs
     * that do not point, make the missing links, then mark negative
     * numbers and linked zeroes. The last loop starts at index 1, so
     * index 0 is never inspected.
     */
    private fun checkCompletion(state: State, markErrors: Boolean): Boolean {
        val n = state.n
        if (markErrors) {
            for (j in 0 until n) state.flags[j] = state.flags[j] and FLAG_ERROR.inv()
        }

        var error = false
        for (j in 0 until n) {
            val value = state.nums[j]
            if (value > 0 && value <= n) {
                for (k in j + 1 until n) {
                    if (state.nums[k] == value) {
                        if (markErrors) {
                            state.flags[j] = state.flags[j] or FLAG_ERROR
                            state.flags[k] = state.flags[k] or FLAG_ERROR
                        }
                        error = true
                    }
                }
            }
        }

        var complete = true
        for (value in 1 until n) {
            val from = state.numsi[value]
            val to = state.numsi[value + 1]
            if (from == -1 || to == -1) {
                complete = false
            } else if (!isPointingIndex(state, from, to)) {
                if (markErrors) {
                    state.flags[from] = state.flags[from] or FLAG_ERROR
                    state.flags[to] = state.flags[to] or FLAG_ERROR
                }
                error = true
            } else if (markErrors) {
                makeLink(state, from, to)
            }
        }

        for (index in 1 until n) {
            if (state.nums[index] < 0 ||
                (state.nums[index] == 0 && (state.next[index] != -1 || state.prev[index] != -1))
            ) {
                error = true
                if (markErrors) state.flags[index] = state.flags[index] or FLAG_ERROR
            }
        }

        return if (error) false else complete
    }

    // ------------------------------------------------------------------
    // Link rules.
    // ------------------------------------------------------------------

    private fun isRealNum(state: State, value: Int): Boolean =
        value > 0 && value <= state.n

    private fun ingrid(state: State, x: Int, y: Int): Boolean =
        x >= 0 && x < state.w && y >= 0 && y < state.h

    private fun whichdir(fromx: Int, fromy: Int, tox: Int, toy: Int): Int {
        var dx = tox - fromx
        var dy = toy - fromy
        if (dx != 0 && dy != 0 && abs(dx) != abs(dy)) return -1
        if (dx != 0) dx /= abs(dx)
        if (dy != 0) dy /= abs(dy)
        for (i in 0 until DIR_MAX) {
            if (dx == DXS[i] && dy == DYS[i]) return i
        }
        return -1
    }

    private fun whichdiri(state: State, fromi: Int, toi: Int): Int =
        whichdir(fromi % state.w, fromi / state.w, toi % state.w, toi / state.w)

    private fun isPointing(
        state: State,
        fromxStart: Int,
        fromyStart: Int,
        tox: Int,
        toy: Int,
    ): Boolean {
        var fromx = fromxStart
        var fromy = fromyStart
        val dir = state.dirs[fromy * state.w + fromx]
        if (fromx == tox && fromy == toy) return false
        if (state.nums[fromy * state.w + fromx] == state.n) return false
        while (true) {
            if (!ingrid(state, fromx, fromy)) return false
            if (fromx == tox && fromy == toy) return true
            fromx += DXS[dir]
            fromy += DYS[dir]
        }
    }

    private fun isPointingIndex(state: State, fromi: Int, toi: Int): Boolean =
        isPointing(state, fromi % state.w, fromi / state.w, toi % state.w, toi / state.w)

    /**
     * `move_couldfit`: the gap between a number and the next present
     * number up or down, compared with the size of the region at the
     * candidate cell.
     */
    private fun moveCouldFit(state: State, num: Int, d: Int, x: Int, y: Int): Boolean {
        val i = y * state.w + x
        var gap = 0
        var value = num + d
        while (isRealNum(state, value) && state.numsi[value] == -1) {
            value += d
            gap++
        }
        if (gap == 0) {
            return state.nums[i] != num + d
        }
        if (state.prev[i] == -1 && state.next[i] == -1) return true
        return state.dsf.size(i) <= gap
    }

    /** `isvalidmove`, in the C order. Only the solver passes `clever`. */
    private fun isValidMove(
        state: State,
        clever: Boolean,
        fromx: Int,
        fromy: Int,
        tox: Int,
        toy: Int,
    ): Boolean {
        if (!ingrid(state, fromx, fromy) || !ingrid(state, tox, toy)) return false
        if (!isPointing(state, fromx, fromy, tox, toy)) return false

        val from = fromy * state.w + fromx
        val to = toy * state.w + tox
        val nfrom = state.nums[from]
        val nto = state.nums[to]

        if ((nfrom == state.n && state.flags[from] and FLAG_IMMUTABLE != 0) ||
            (nto == 1 && state.flags[to] and FLAG_IMMUTABLE != 0)
        ) {
            return false
        }

        if (state.dsf.equivalent(from, to)) return false

        if (isRealNum(state, nfrom) && isRealNum(state, nto)) {
            if (nfrom != nto - 1) return false
        } else if (clever && isRealNum(state, nfrom)) {
            if (!moveCouldFit(state, nfrom, +1, tox, toy)) return false
        } else if (clever && isRealNum(state, nto)) {
            if (!moveCouldFit(state, nto, -1, fromx, fromy)) return false
        }
        return true
    }

    /** `makelink`: replaces the outgoing link of `from` and the incoming link of `to`. */
    private fun makeLink(state: State, from: Int, to: Int) {
        if (state.next[from] != -1) state.prev[state.next[from]] = -1
        state.next[from] = to
        if (state.prev[to] != -1) state.next[state.prev[to]] = -1
        state.prev[to] = from
    }

    /** `unlink_cell`: drops both partners of a cell. */
    private fun unlinkCell(state: State, si: Int) {
        if (state.prev[si] != -1) {
            state.next[state.prev[si]] = -1
            state.prev[si] = -1
        }
        if (state.next[si] != -1) {
            state.prev[state.next[si]] = -1
            state.next[si] = -1
        }
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    /**
     * `solve_state`: in-place deduction rounds. It returns -1 for an
     * impossible state, 0 for a stall, and 1 for a solved state.
     */
    private fun solveState(state: State): Int {
        val copy = state.duplicate()
        val scratch = IntArray(state.n)
        while (true) {
            updateNumbers(state)
            if (solveSingle(state, copy, scratch) != 0) {
                copyFrom(state, copy)
                if (state.impossible) break else continue
            }
            break
        }
        updateNumbers(state)
        return when {
            state.impossible -> -1
            checkCompletion(state, false) -> 1
            else -> 0
        }
    }

    /**
     * `solve_single`: one deduction pass. The forward scan reads
     * [state] and writes links to [copy]; the backward scan uses the
     * `from` array the forward scan filled.
     */
    private fun solveSingle(state: State, copy: State, from: IntArray): Int {
        val w = state.w
        val n = state.n
        from.fill(-1)
        var nlinks = 0

        for (i in 0 until n) {
            if (state.next[i] != -1) continue
            if (state.nums[i] == n) continue

            val d = state.dirs[i]
            var poss = -1
            val sx = i % w
            val sy = i / w
            var x = sx
            var y = sy
            while (true) {
                x += DXS[d]
                y += DYS[d]
                if (!ingrid(state, x, y)) break
                if (!isValidMove(state, true, sx, sy, x, y)) continue

                val j = y * w + x
                if (state.prev[j] != -1) continue

                if (state.nums[i] > 0 && state.nums[j] > 0 &&
                    state.nums[i] <= n && state.nums[j] <= n &&
                    state.nums[j] == state.nums[i] + 1
                ) {
                    poss = j
                    from[j] = i
                    break
                }

                poss = if (poss == -1) j else -2
                from[j] = if (from[j] == -1) i else -2
            }

            if (poss == -1) {
                copy.impossible = true
                return -1
            } else if (poss != -2) {
                makeLink(copy, i, poss)
                nlinks++
            }
        }

        for (i in 0 until n) {
            if (state.prev[i] != -1) continue
            if (state.nums[i] == 1) continue
            if (from[i] == -1) {
                copy.impossible = true
                return -1
            } else if (from[i] != -2) {
                makeLink(copy, from[i], i)
                nlinks++
            }
        }
        return nlinks
    }

    /**
     * `solve_game`: try the current state, then the initial state. The
     * two failures produce the C error strings, which the seam has no
     * channel for, so [solve] drops them and this helper keeps them.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state, auxInfo).move

    /** `solve_state` on a copy, for tests that need to see the fallback. */
    internal fun solveResult(state: Game.State): Int = solveState((state as State).duplicate())

    /** `solve_game` with the C error strings (`signpost.c:1379-1405`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state, auxInfo)
        return SolveOutcome(attempt.move, attempt.error)
    }

    fun solveAttempt(state: Game.State, auxInfo: String?): SolveAttempt {
        val s = state as State

        val current = s.duplicate()
        if (solveState(current) > 0) return SolveAttempt(generateDesc(current, true), null)

        val initial = (s.initial ?: s).duplicate()
        val result = solveState(initial)
        return when {
            result < 0 -> SolveAttempt(null, "Puzzle is impossible.")
            result == 0 -> SolveAttempt(null, "Unable to solve puzzle.")
            else -> SolveAttempt(generateDesc(initial, true), null)
        }
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    /**
     * `execute_move`: `S` payload, `L from-to`, `C`/`X cell`, or `H`.
     * The parsers keep the `sscanf` shape: leading white space and a sign
     * on a number, trailing junk ignored, and a literal that must match
     * at once.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.w
        val n = s.n
        var ret: State? = null

        if (move.isNotEmpty() && move[0] == 'S') {
            val payload = move.substring(1)
            val params = Params(s.w, s.h, false)
            if (validateDesc(params, payload) != null) return null
            val check = newState(params, payload) as State
            ret = s.duplicate()
            check.next.copyInto(ret.next)
            check.prev.copyInto(ret.prev)
            ret = ret.withUsedSolve()
        } else {
            val link = parseLink(move)
            if (link != null) {
                val (sx, sy, ex, ey) = link
                if (!isValidMove(s, false, sx, sy, ex, ey)) return null
                ret = s.duplicate()
                makeLink(ret, sy * w + sx, ey * w + ex)
            } else {
                val cellMove = parseCell(move)
                if (cellMove != null) {
                    val (c, sx, sy) = cellMove
                    if (c != 'C' && c != 'X') return null
                    if (!ingrid(s, sx, sy)) return null
                    val si = sy * w + sx
                    if (s.prev[si] == -1 && s.next[si] == -1) return null
                    ret = s.duplicate()
                    val sset = s.nums[si] / (n + 1)
                    if (c == 'C' || (c == 'X' && sset == 0)) {
                        unlinkCell(ret, si)
                    } else {
                        for (i in 0 until n) {
                            if (s.nums[i] == 0) continue
                            if (s.nums[i] / (n + 1) != sset) continue
                            unlinkCell(ret, i)
                        }
                    }
                } else if (move == "H") {
                    ret = s.duplicate()
                    solveState(ret)
                }
            }
        }

        val result = (ret ?: return null).withInitial(s.initial ?: s)
        updateNumbers(result)
        if (checkCompletion(result, true)) {
            return result.withCompleted()
        }
        return result
    }

    /**
     * Attaches the initial state that the two-state solve falls back to.
     * A state created from the initial state must remember that object,
     * not keep its own null marker.
     */
    private fun State.withInitial(value: State): State = State(
        w, h, dirs, nums, flags, next, prev, dsf, numsi,
        completed = completed, usedSolve = usedSolve, impossible = impossible, initial = value,
    )

    /** Rebuilds a state with the sticky flags changed. */
    private fun State.withCompleted(): State = State(
        w, h, dirs, nums, flags, next, prev, dsf, numsi,
        completed = true, usedSolve = usedSolve, impossible = impossible, initial = initial,
    )

    private fun State.withUsedSolve(): State = State(
        w, h, dirs, nums, flags, next, prev, dsf, numsi,
        completed = completed, usedSolve = true, impossible = impossible, initial = initial,
    )

    private data class LinkMove(val sx: Int, val sy: Int, val ex: Int, val ey: Int)

    private data class CellMove(val c: Char, val x: Int, val y: Int)

    /** `sscanf(move, "L%d,%d-%d,%d") == 4`. */
    private fun parseLink(move: String): LinkMove? {
        if (move.isEmpty() || move[0] != 'L') return null
        var index = 1
        val sx = scanInt(move, index) ?: return null
        index = sx.end
        if (index >= move.length || move[index] != ',') return null
        index++
        val sy = scanInt(move, index) ?: return null
        index = sy.end
        if (index >= move.length || move[index] != '-') return null
        index++
        val ex = scanInt(move, index) ?: return null
        index = ex.end
        if (index >= move.length || move[index] != ',') return null
        index++
        val ey = scanInt(move, index) ?: return null
        return LinkMove(sx.value, sy.value, ex.value, ey.value)
    }

    /** `sscanf(move, "%c%d,%d") == 3`; `%c` does not skip white space. */
    private fun parseCell(move: String): CellMove? {
        if (move.isEmpty()) return null
        val c = move[0]
        val x = scanInt(move, 1) ?: return null
        var index = x.end
        if (index >= move.length || move[index] != ',') return null
        index++
        val y = scanInt(move, index) ?: return null
        return CellMove(c, x.value, y.value)
    }

    // ------------------------------------------------------------------
    // Frontend state.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cshow = visible
    }

    /** `game_get_cursor_location`: a rectangle only for a visible cursor. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cshow) return null
        return Game.CursorRect(
            u.cx * tileSize + tileSize / 2,
            u.cy * tileSize + tileSize / 2,
            tileSize,
            tileSize,
        )
    }

    /** `game_request_keys`: one `Clear` key and the left/right arrow mode. */
    override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
        listOf(KeyLabel('\b'.code, "Clear")),
        ArrowMode.ARROWS_LEFT_RIGHT,
    )

    /**
     * `current_key_label`. The seam has no hook and the Android shell has
     * no call site, so this is a tested helper.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) return ""
        if (!u.cshow) return ""
        if (u.dragging) {
            if (u.dragIsFrom) {
                return if (isValidMove(s, false, u.sx, u.sy, u.cx, u.cy)) {
                    "To here"
                } else {
                    "Cancel"
                }
            }
            return if (isValidMove(s, false, u.cx, u.cy, u.sx, u.sy)) {
                "From here"
            } else {
                "Cancel"
            }
        }
        return if (button == Buttons.CURSOR_SELECT) "From here" else "To here"
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the fixed arrow, then the number or the
     * algebraic label the cell shows. The only pointer move is a drag
     * from one cell to another, which a single unit cannot name, so a
     * cell offers no action; a link stays on the key path (move the
     * cursor, then select twice). This is reported as a wave 2 limit
     * rather than a made-up move.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val t = tileSize
        val directions = arrayOf(
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west",
        )
        val cells = gridSpeech(
            width = s.w,
            height = s.h,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * s.w + x
                val number = s.nums[index]
                val value = when {
                    number == 0 -> ""
                    number <= s.n -> ", number $number"
                    else -> {
                        val set = number / (s.n + 1)
                        val offset = number % (s.n + 1)
                        val label = prefixFor(set) + if (offset != 0) "+$offset" else ""
                        ", label $label"
                    }
                }
                val cursor = if (u.cshow && u.cx == x && u.cy == y) ", cursor" else ""
                val wrong = if (s.isError(x, y)) SPOKEN_WRONG else ""
                "arrow ${directions[s.dirs[index]]}$value$cursor$wrong"
            },
        )
        val cursor = cells.units.getOrNull(u.cy * s.w + u.cx)
        return cells.capped(cursor)
    }

    /**
     * `game_changed_state`. C dereferences `oldstate` in its first test;
     * every midend call site passes two states. The seam allows null, so
     * the port treats null as "not completed", as the return expression
     * already did.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val next = newState as State
        val previous = oldState as State?
        if (previous != null && !previous.completed && next.completed) {
            u.cshow = false
            u.dragging = false
        }
        return next.completed && !next.usedSolve && previous != null && !previous.completed
    }

    /**
     * `interpret_move`. A drag in the arrow direction links; a drag from
     * an end away from the board cuts. A press outside the board clears
     * the cursor and returns unused, so a stale ring stays on screen
     * until the next redraw, as in C.
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
        val cx = fromCoord(x, tileSize)
        val cy = fromCoord(y, tileSize)
        val w = s.w

        if (Buttons.isCursorMove(button)) {
            val result = moveCursor(button, u, s.w, s.h)
            if (u.dragging) {
                u.dx = coord(u.cx, tileSize) + tileSize / 2
                u.dy = coord(u.cy, tileSize) + tileSize / 2
            }
            return result
        }

        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.cshow) {
                u.cshow = true
            } else if (u.dragging) {
                u.dragging = false
                if (u.sx == u.cx && u.sy == u.cy) return MoveResult.UiUpdate
                if (u.dragIsFrom) {
                    if (!isValidMove(s, false, u.sx, u.sy, u.cx, u.cy)) {
                        return MoveResult.UiUpdate
                    }
                    return MoveResult.Move("L${u.sx},${u.sy}-${u.cx},${u.cy}")
                }
                if (!isValidMove(s, false, u.cx, u.cy, u.sx, u.sy)) {
                    return MoveResult.UiUpdate
                }
                return MoveResult.Move("L${u.cx},${u.cy}-${u.sx},${u.sy}")
            } else {
                u.dragging = true
                u.sx = u.cx
                u.sy = u.cy
                u.dx = coord(u.cx, tileSize) + tileSize / 2
                u.dy = coord(u.cy, tileSize) + tileSize / 2
                u.dragIsFrom = button == Buttons.CURSOR_SELECT
            }
            return MoveResult.UiUpdate
        }

        if (Buttons.isMouseDown(button)) {
            if (u.cshow) {
                u.cshow = false
                u.dragging = false
            }
            if (!ingrid(s, cx, cy)) return MoveResult.Unused

            if (button == Buttons.LEFT_BUTTON) {
                if (s.nums[cy * w + cx] == s.n &&
                    s.flags[cy * w + cx] and FLAG_IMMUTABLE != 0
                ) {
                    return MoveResult.Unused
                }
            } else if (button == Buttons.RIGHT_BUTTON) {
                if (s.nums[cy * w + cx] == 1 &&
                    s.flags[cy * w + cx] and FLAG_IMMUTABLE != 0
                ) {
                    return MoveResult.Unused
                }
            }

            u.dragging = true
            u.dragIsFrom = button == Buttons.LEFT_BUTTON
            u.sx = cx
            u.sy = cy
            u.dx = x
            u.dy = y
            u.cshow = false
            return MoveResult.UiUpdate
        } else if (Buttons.isMouseDrag(button) && u.dragging) {
            u.dx = x
            u.dy = y
            return MoveResult.UiUpdate
        } else if (Buttons.isMouseRelease(button) && u.dragging) {
            u.dragging = false
            if (u.sx == cx && u.sy == cy) return MoveResult.UiUpdate

            if (!ingrid(s, cx, cy)) {
                val si = u.sy * w + u.sx
                if (s.prev[si] == -1 && s.next[si] == -1) return MoveResult.UiUpdate
                val c = if (u.dragIsFrom) 'C' else 'X'
                return MoveResult.Move("$c${u.sx},${u.sy}")
            }

            if (u.dragIsFrom) {
                if (!isValidMove(s, false, u.sx, u.sy, cx, cy)) {
                    return MoveResult.UiUpdate
                }
                return MoveResult.Move("L${u.sx},${u.sy}-$cx,$cy")
            }
            if (!isValidMove(s, false, cx, cy, u.sx, u.sy)) {
                return MoveResult.UiUpdate
            }
            return MoveResult.Move("L$cx,$cy-${u.sx},${u.sy}")
        } else if ((button == 'x'.code || button == 'X'.code || button == '\b'.code) &&
            u.cshow
        ) {
            val si = u.cy * w + u.cx
            if (s.prev[si] == -1 && s.next[si] == -1) return MoveResult.UiUpdate
            val c = if (button == 'x'.code || button == '\b'.code) 'C' else 'X'
            return MoveResult.Move("$c${u.cx},${u.cy}")
        }

        return MoveResult.Unused
    }

    /** `move_cursor` with `wrap = false`. */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
        var dx = 0
        var dy = 0
        when (button) {
            Buttons.CURSOR_UP -> dy = -1
            Buttons.CURSOR_DOWN -> dy = 1
            Buttons.CURSOR_RIGHT -> dx = 1
            Buttons.CURSOR_LEFT -> dx = -1
            else -> return MoveResult.Unused
        }
        val ox = ui.cx
        val oy = ui.cy
        ui.cx = (ui.cx + dx).coerceIn(0, w - 1)
        ui.cy = (ui.cy + dy).coerceIn(0, h - 1)
        if (!ui.cshow) {
            ui.cshow = true
            return MoveResult.UiUpdate
        }
        return if (ui.cx != ox || ui.cy != oy) MoveResult.UiUpdate else MoveResult.NoEffect
    }

    /** `FROMCOORD`, with C truncation toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        ((value - tileSize / 2 + tileSize) / tileSize) - 1

    /** `COORD(v)`: the top-left of a cell plus the border. */
    private fun coord(v: Int, tileSize: Int): Int =
        v * tileSize + tileSize / 2

    // ------------------------------------------------------------------
    // Status and text.
    // ------------------------------------------------------------------

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** Signpost has no status bar, so the move count is not shown. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /**
     * `game_text_format`.
     *
     * C uses `state->h` as the column bound in both rows, so a board
     * wider than it is tall loses its last columns; that is reproduced.
     * A board taller than it is wide makes C read past `dirs`, `nums`,
     * and `flags` and write past its buffer. The port defines such a
     * read as direction N, number 0, and no flags. The `w > h` case is
     * deterministic in C and is kept.
     *
     * `can_format_as_text_now` is false from 100 cells, and
     * `midend_text_format` returns NULL there, which the oracle prints
     * as an empty string. The seam has no params-dependent hook, so this
     * method returns the empty string itself.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val n = s.n
        if (n >= 100) return ""
        val out = StringBuilder()
        for (y in 0 until s.h) {
            for (x in 0 until s.h) {
                val i = y * s.w + x
                if (i < n) {
                    val dir = s.dirs[i]
                    out.append(DIR_STRINGS[dir][0]).append(DIR_STRINGS[dir][1])
                    out.append(if (s.flags[i] and FLAG_IMMUTABLE != 0) 'I' else ' ')
                } else {
                    // A defined value for C's out-of-bounds read.
                    out.append(DIR_STRINGS[DIR_N][0]).append(DIR_STRINGS[DIR_N][1])
                    out.append(' ')
                }
                out.append(' ')
            }
            out.append('\n')
            for (x in 0 until s.h) {
                val i = y * s.w + x
                val num = if (i < n) s.nums[i] else 0
                if (num == 0) {
                    out.append("   ")
                } else {
                    val value = num % (n + 1)
                    val set = num / (n + 1)
                    if (set != 0) out.append((set + 'a'.code - 1).toChar())
                    out.append(if (value >= 10) '0' + value / 10 else ' ')
                    out.append('0' + value % 10)
                    if (set == 0) out.append(' ')
                }
                out.append(' ')
            }
            out.append('\n')
            out.append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Geometry and palette.
    // ------------------------------------------------------------------

    /** `game_compute_size`: `T*w + 2*(T/2)` each way, with C division. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 2
        return Size(
            tileSize * p.w + 2 * border,
            tileSize * p.h + 2 * border,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`. `game_mkhighlight` moves the background, the
     * highlight, and the lowlight together; the numbered-cell backgrounds
     * and the arrow and number colours are fixed. The palette is parity
     * data.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        for (i in 0 until 3) {
            ret[COL_EMPTY * 3 + i] = ret[COL_BACKGROUND * 3 + i]
        }
        for (i in 0 until 3) {
            ret[COL_NUMBER * 3 + i] = 0.0f
            ret[COL_NUMBER0 * 3 + i] = 0.0f
            ret[COL_ARROW * 3 + i] = 0.0f
            ret[COL_CURSOR * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 2.0f
            ret[COL_GRID * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 1.3f
        }
        ret[COL_NUMBER_SET * 3 + 0] = 0.0f
        ret[COL_NUMBER_SET * 3 + 1] = 0.0f
        ret[COL_NUMBER_SET * 3 + 2] = 0.9f

        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_DRAG_ORIGIN * 3 + 0] = 0.2f
        ret[COL_DRAG_ORIGIN * 3 + 1] = 1.0f
        ret[COL_DRAG_ORIGIN * 3 + 2] = 0.2f

        for (c in 0 until 8) {
            ret[(COL_B0 + c) * 3 + 0] = ((BGCOLS[c] and 0xff0000) shr 16).toFloat() / 256.0f
            ret[(COL_B0 + c) * 3 + 1] = ((BGCOLS[c] and 0xff00) shr 8).toFloat() / 256.0f
            ret[(COL_B0 + c) * 3 + 2] = (BGCOLS[c] and 0xff).toFloat() / 256.0f
        }
        for (c in 0 until 8) {
            for (i in 0 until 3) {
                ret[(COL_B0 + 8 + c) * 3 + i] =
                    (ret[(COL_B0 + c) * 3 + i] + ret[(COL_B0 + c + 1) * 3 + i]) / 2.0f
            }
        }

        average(ret, COL_ARROW_BG_DIM, COL_BACKGROUND, COL_ARROW, 0.1f)
        average(ret, COL_NUMBER_SET_MID, COL_B0, COL_NUMBER_SET, 0.3f)
        for (c in 0 until 16) {
            average(ret, COL_M0 + c, COL_B0 + c, COL_NUMBER, 0.3f)
            average(ret, COL_D0 + c, COL_B0 + c, COL_NUMBER, 0.1f)
            average(ret, COL_X0 + c, COL_BACKGROUND, COL_B0 + c, 0.5f)
        }
        return ret
    }

    private fun average(ret: FloatArray, target: Int, a: Int, b: Int, weight: Float) {
        for (i in 0 until 3) {
            ret[target * 3 + i] =
                ret[a * 3 + i] + weight * (ret[b * 3 + i] - ret[a * 3 + i])
        }
    }

    // ------------------------------------------------------------------
    // Drawing.
    // ------------------------------------------------------------------

    /**
     * `game_new_drawstate` plus `game_set_size`: the seam knows the tile
     * size at creation, so the one blitter is allocated here, before the
     * first `start_draw`.
     */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(
            tileSize,
            s.w,
            s.h,
            sink.blitterNew(tileSize, tileSize),
        )
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        sink.blitterFree((drawState as DrawState).blitter)
    }

    /**
     * `game_redraw`. The first frame draws the frame and every tile; a
     * warm frame draws only the cells whose number, flags, or incoming
     * direction changed. A live drag previews through `interpret_move`,
     * which also previews a cut outside the board, and leaves one blitter
     * sprite on screen.
     */
    override fun redraw(
        state: Game.State,
        ui: Game.Ui,
        drawState: Game.DrawState,
        sink: DrawSink,
        tileSize: Int,
    ) {
        val original = state as State
        val u = ui as Ui
        val ds = drawState as DrawState
        val border = tileSize / 2

        if (ds.dragging) {
            sink.blitterLoad(ds.blitter, ds.dragX, ds.dragY)
            sink.update(ds.dragX, ds.dragY, tileSize, tileSize)
            ds.dragging = false
        }

        var drawn = original
        var previewValid = false
        if (u.dragging) {
            val uicopy = u.duplicate()
            val result = interpretMove(original, uicopy, u.dx, u.dy, Buttons.LEFT_RELEASE, tileSize)
            if (result is MoveResult.Move && result.text.isNotEmpty()) {
                val postdrop = executeMove(original, result.text) as State?
                if (postdrop != null) {
                    drawn = postdrop
                    previewValid = true
                }
            }
        }

        val w = ds.width
        if (!ds.started) {
            val aw = tileSize * drawn.w
            val ah = tileSize * drawn.h
            drawRectOutline(sink, border - 1, border - 1, aw + 2, ah + 2, COL_GRID)
            sink.update(0, 0, aw + 2 * border, ah + 2 * border)
        }

        for (x in 0 until drawn.w) {
            for (y in 0 until drawn.h) {
                val i = y * w + x
                var f = 0
                var dirp = -1

                if (u.cshow && x == u.cx && y == u.cy) f = f or F_CUR

                if (u.dragging) {
                    if (x == u.sx && y == u.sy) {
                        f = f or F_DRAG_SRC
                    } else if (u.dragIsFrom) {
                        if (!isPointing(drawn, u.sx, u.sy, x, y)) f = f or F_DIM
                    } else {
                        if (!isPointing(drawn, x, y, u.sx, u.sy)) f = f or F_DIM
                    }
                }

                if (drawn.impossible || drawn.nums[i] < 0 ||
                    drawn.flags[i] and FLAG_ERROR != 0
                ) {
                    f = f or F_ERROR
                }
                if (drawn.flags[i] and FLAG_IMMUTABLE != 0) f = f or F_IMMUTABLE
                if (drawn.next[i] != -1) f = f or F_ARROW_POINT
                if (drawn.prev[i] != -1) {
                    f = f or F_ARROW_INPOINT
                    dirp = whichdir(
                        x,
                        y,
                        drawn.prev[i] % w,
                        drawn.prev[i] / w,
                    )
                }

                if (drawn.nums[i] != ds.nums[i] ||
                    f != ds.f[i] ||
                    dirp != ds.dirp[i] ||
                    !ds.started
                ) {
                    tileRedraw(
                        sink,
                        ds,
                        border + x * tileSize,
                        border + y * tileSize,
                        drawn.dirs[i],
                        drawn.nums[i],
                        f,
                    )
                    ds.nums[i] = drawn.nums[i]
                    ds.f[i] = f
                    ds.dirp[i] = dirp
                }
            }
        }

        if (u.dragging) {
            ds.dragging = true
            ds.dragX = u.dx - tileSize / 2
            ds.dragY = u.dy - tileSize / 2
            sink.blitterSave(ds.blitter, ds.dragX, ds.dragY)
            drawDragIndicator(sink, ds, drawn, u, u.dx, u.dy, previewValid)
        }
        ds.started = true
    }

    /** `num2col`: colour 0 for a real number, a pastel for a set. */
    private fun num2col(ds: DrawState, num: Int): Int {
        val set = num / (ds.n + 1)
        if (num <= 0 || set == 0) return COL_B0
        return COL_B0 + 1 + ((set - 1) % 15)
    }

    /**
     * `tile_redraw`. The order is background, arrow or star, cursor,
     * dot, text, grid outline, and update.
     */
    private fun tileRedraw(
        sink: DrawSink,
        ds: DrawState,
        tx: Int,
        ty: Int,
        dir: Int,
        num: Int,
        f: Int,
    ) {
        val tile = ds.tileSize
        val cb = tile / 16
        val asz = 7 * tile / 32
        val empty = num == 0 && f and F_ARROW_POINT == 0 && f and F_ARROW_INPOINT == 0

        val setcol = if (empty) COL_EMPTY else num2col(ds, num)

        fun dim(bg: Int): Int =
            if (bg == COL_EMPTY) COL_ARROW_BG_DIM else bg + COL_D0 - COL_B0

        fun mid(fg: Int, bg: Int): Int =
            if (fg == COL_NUMBER_SET) COL_NUMBER_SET_MID else bg + COL_M0 - COL_B0

        fun dimbg(bg: Int): Int =
            if (bg == COL_EMPTY) COL_EMPTY else bg + COL_X0 - COL_B0

        val arrowcol = when {
            f and F_DRAG_SRC != 0 -> COL_DRAG_ORIGIN
            f and F_DIM != 0 -> dim(setcol)
            f and F_ARROW_POINT != 0 -> mid(COL_ARROW, setcol)
            else -> COL_ARROW
        }

        var textcol: Int
        if (f and F_ERROR != 0 && f and F_IMMUTABLE == 0) {
            textcol = COL_ERROR
        } else {
            textcol = if (f and F_IMMUTABLE != 0) COL_NUMBER_SET else COL_NUMBER
            if (f and F_DIM != 0) {
                textcol = dim(setcol)
            } else if ((f and F_ARROW_POINT != 0 || num == ds.n) &&
                (f and F_ARROW_INPOINT != 0 || num == 1)
            ) {
                textcol = mid(textcol, setcol)
            }
            if (setcol == COL_B0 && textcol == COL_NUMBER) textcol = COL_NUMBER0
        }

        val sarrowcol = if (f and F_DIM != 0) dim(setcol) else COL_ARROW

        sink.rect(tx, ty, tile, tile, if (f and F_DIM != 0) dimbg(setcol) else setcol)

        val acx = tx + tile / 2 + asz
        val acy = ty + tile / 2 + asz

        if (num == ds.n && f and F_IMMUTABLE != 0) {
            drawStar(sink, acx, acy, asz, 5, arrowcol, arrowcol, 0.0)
        } else {
            drawArrowDir(sink, acx, acy, asz, dir, arrowcol, arrowcol, 0.0)
        }

        if (f and F_CUR != 0) drawRectCorners(sink, acx, acy, asz + 1, COL_CURSOR)

        if (f and F_ARROW_INPOINT == 0 && num != 1) {
            sink.circle(
                tx + tile / 2 - asz,
                ty + tile / 2 + asz,
                asz / 4,
                sarrowcol,
                sarrowcol,
            )
        }

        if (!empty) {
            val set = if (num <= 0) 0 else num / (ds.n + 1)
            val offset = if (set == 0) 0 else num % (ds.n + 1)
            val text = if (set == 0 || num <= 0) {
                "$num"
            } else {
                prefixFor(set) + if (offset != 0) "+$offset" else ""
            }
            val textsz = min(2 * asz, (tile - 2 * cb) / text.length)
            sink.text(
                tx + cb,
                ty + tile / 4,
                Text.FONT_VARIABLE,
                textsz,
                Text.ALIGN_VCENTRE or Text.ALIGN_HLEFT,
                textcol,
                text,
            )
        }

        drawRectOutline(sink, tx, ty, tile, tile, COL_GRID)
        sink.update(tx, ty, tile, tile)
    }

    /** `set` as the base-26 prefix that C writes: 1 is `a`, 27 is `aa`. */
    private fun prefixFor(setValue: Int): String {
        var set = setValue
        val out = StringBuilder()
        do {
            set--
            out.append(('a'.code + set % 26).toChar())
            set /= 26
        } while (set != 0)
        return out.reverse().toString()
    }

    /**
     * `draw_drag_indicator`: a locked arrow when the drop would be valid
     * and on the grid, otherwise an arrow from the origin toward the
     * pointer.
     */
    private fun drawDragIndicator(
        sink: DrawSink,
        ds: DrawState,
        state: State,
        ui: Ui,
        dx: Int,
        dy: Int,
        validdrag: Boolean,
    ) {
        val fx = fromCoord(dx, ds.tileSize)
        val fy = fromCoord(dy, ds.tileSize)
        val ang: Double
        if (validdrag && ingrid(state, fx, fy)) {
            val dir = if (ui.dragIsFrom) {
                state.dirs[ui.sy * ds.width + ui.sx]
            } else {
                state.dirs[fy * ds.width + fx]
            }
            ang = 2.0 * PI * dir / 8.0
        } else {
            val ox = coord(ui.sx, ds.tileSize) + ds.tileSize / 2
            val oy = coord(ui.sy, ds.tileSize) + ds.tileSize / 2
            val xdiff = abs(ox - dx)
            val ydiff = abs(oy - dy)
            var angle: Double
            if (xdiff == 0) {
                angle = if (oy > dy) 0.0 else PI
            } else if (ydiff == 0) {
                angle = if (ox > dx) 3.0 * PI / 2.0 else PI / 2.0
            } else {
                val tana: Double
                val offset: Double
                if (dx > ox && dy < oy) {
                    tana = xdiff.toDouble() / ydiff
                    offset = 0.0
                } else if (dx > ox && dy > oy) {
                    tana = ydiff.toDouble() / xdiff
                    offset = PI / 2.0
                } else if (dx < ox && dy > oy) {
                    tana = xdiff.toDouble() / ydiff
                    offset = PI
                } else {
                    tana = ydiff.toDouble() / xdiff
                    offset = 3.0 * PI / 2.0
                }
                angle = atan(tana) + offset
            }
            if (!ui.dragIsFrom) angle += PI
            ang = angle
        }
        drawArrow(sink, dx, dy, 7 * ds.tileSize / 32, ang, COL_ARROW, COL_ARROW)
    }

    /** `draw_arrow`: the seven-point arrow polygon. */
    private fun drawArrow(
        sink: DrawSink,
        cx: Int,
        cy: Int,
        sz: Int,
        ang: Double,
        fill: Int,
        outline: Int,
    ) {
        val s = sin(ang)
        val c = cos(ang)
        val xdx3 = (sz * (c / 3 + 1) + 0.5).toInt() - sz
        val xdy3 = (sz * (s / 3 + 1) + 0.5).toInt() - sz
        val xdx = (sz * (c + 1) + 0.5).toInt() - sz
        val xdy = (sz * (s + 1) + 0.5).toInt() - sz
        val ydx = -xdy
        val ydy = xdx
        sink.poly(
            intArrayOf(
                cx - ydx, cy - ydy,
                cx + xdx, cy + xdy,
                cx + xdx3, cy + xdy3,
                cx + xdx3 + ydx, cy + xdy3 + ydy,
                cx - xdx3 + ydx, cy - xdy3 + ydy,
                cx - xdx3, cy - xdy3,
                cx - xdx, cy - xdy,
            ),
            fill,
            outline,
        )
    }

    private fun drawArrowDir(
        sink: DrawSink,
        cx: Int,
        cy: Int,
        sz: Int,
        dir: Int,
        fill: Int,
        outline: Int,
        angleOffset: Double,
    ) {
        drawArrow(sink, cx, cy, sz, 2.0 * PI * dir / 8.0 + angleOffset, fill, outline)
    }

    /** `draw_star`: alternating outer and inner radii. */
    private fun drawStar(
        sink: DrawSink,
        cx: Int,
        cy: Int,
        rad: Int,
        npoints: Int,
        fill: Int,
        outline: Int,
        angleOffset: Double,
    ) {
        val coords = IntArray(npoints * 4)
        for (n in 0 until npoints * 2) {
            val a = 2.0 * PI * (n.toDouble() / (npoints * 2.0)) + angleOffset
            val r = if (n % 2 != 0) rad / 2.0 else rad.toDouble()
            coords[2 * n] = cx + (r * sin(a)).toInt()
            coords[2 * n + 1] = cy + (-r * cos(a)).toInt()
        }
        sink.poly(coords, fill, outline)
    }

    /** `draw_rect_outline`: a four-point polygon with no fill. */
    private fun drawRectOutline(
        sink: DrawSink,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colour: Int,
    ) {
        val x0 = x
        val x1 = x + width - 1
        val y0 = y
        val y1 = y + height - 1
        sink.poly(intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0), -1, colour)
    }

    /** `draw_rect_corners`: eight short lines at the four corners. */
    private fun drawRectCorners(
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

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private fun opposite(d: Int): Int = (d + 4) % 8

    /** A number from `sscanf`, with the position after its digits. */
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

    /** C `atoi`: white space, an optional sign, digits, and zero otherwise. */
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

    private val BGCOLS = longArrayOf(
        0xffffff,
        0xffa07a,
        0x98fb98,
        0x7fffd4,
        0x9370db,
        0xffa500,
        0x87cefa,
        0xffff00,
    )
}
