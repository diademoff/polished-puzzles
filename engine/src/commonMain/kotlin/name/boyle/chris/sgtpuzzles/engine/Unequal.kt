package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `unequal.c`: Futoshiki and its Adjacent variant.
 *
 * The grid holds a Latin square. In Unequal mode some adjacent pairs
 * carry a greater-than clue. In Adjacent mode every pair of adjacent
 * values is marked, and the clues are fixed by the generated square.
 *
 * The generator is solver-gated, so every detail of the solver order,
 * the clue order, the shared solver context, and the flat cube indices
 * is part of the `DESC` contract. Two upstream bugs are reproduced
 * literally because they change every description:
 *
 * - `gg_best_clue` reads its possibility count from the transposed cube
 *   slice, `hints[loc*order + j]`, while `loc` is the row-major cell
 *   index. The `nclues` count uses the correct cell.
 * - `unequal_valid` skips the last row and the last column as sources in
 *   Adjacent mode, so a horizontal pair in the last row and a vertical
 *   pair in the last column are never checked.
 *
 * The state keeps two different array layouts, as C does. `nums` and
 * `flags` are row-major `y*order + x`; `hints` is the Latin cube layout
 * `(x*order + y)*order + digit`, so it can be copied from the solver's
 * cube. The drawing reads `hints` with that layout, so a transposed
 * write would show in every pencil mark.
 */
object Unequal : Game {

    // Difficulty, `unequal.c:112-127`. A generated `d` with no
    // recognised character decodes to `DIFF_INVALID`.
    private const val DIFF_TRIVIAL = 0
    private const val DIFF_EASY = 1
    private const val DIFF_SET = 2
    private const val DIFF_EXTREME = 3
    private const val DIFF_RECURSIVE = 4
    private const val DIFFCOUNT = 5
    private const val DIFF_INVALID = DIFFCOUNT + 1
    private val DIFF_NAMES = arrayOf("Trivial", "Easy", "Tricky", "Extreme", "Recursive")
    private const val DIFF_CHARS = "tekxr"

    /** `MODE_UNEQUAL`: the clues are greater-than signs. */
    const val MODE_UNEQUAL = 0

    /** `MODE_ADJACENT`: the clues mark adjacent numbers. */
    const val MODE_ADJACENT = 1

    // Palette, `unequal.c:54-60`.
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_TEXT = 2
    private const val COL_GUESS = 3
    private const val COL_ERROR = 4
    private const val COL_PENCIL = 5
    private const val COL_HIGHLIGHT = 6
    private const val COL_LOWLIGHT = 7
    private const val COL_SPENT = COL_LOWLIGHT
    private const val NCOLOURS = 8

    private const val PREFERRED_TILE_SIZE = 32

    // Flag bits, `unequal.c:78-96`.
    const val F_IMMUTABLE = 1
    const val F_ADJ_UP = 2
    const val F_ADJ_RIGHT = 4
    const val F_ADJ_DOWN = 8
    const val F_ADJ_LEFT = 16
    const val F_ERROR = 32
    const val F_ERROR_UP = 64
    const val F_ERROR_RIGHT = 128
    const val F_ERROR_DOWN = 256
    const val F_ERROR_LEFT = 512
    const val F_SPENT_UP = 1024
    const val F_SPENT_RIGHT = 2048
    const val F_SPENT_DOWN = 4096
    const val F_SPENT_LEFT = 8192

    /** `F_ERROR_MASK`: the duplicate mark and the four clue marks. */
    const val F_ERROR_MASK = F_ERROR or F_ERROR_UP or F_ERROR_RIGHT or F_ERROR_DOWN or F_ERROR_LEFT

    /** `F_SPENT_MASK`: the four spent toggles. */
    const val F_SPENT_MASK = F_SPENT_UP or F_SPENT_RIGHT or F_SPENT_DOWN or F_SPENT_LEFT

    /**
     * One direction of `adjthan[]`, `unequal.c:284-289`, in the C order
     * up, right, down, left.
     */
    private class Adjacent(
        val f: Int,
        val fo: Int,
        val fe: Int,
        val dx: Int,
        val dy: Int,
    )

    private val ADJ = arrayOf(
        Adjacent(F_ADJ_UP, F_ADJ_DOWN, F_ERROR_UP, 0, -1),
        Adjacent(F_ADJ_RIGHT, F_ADJ_LEFT, F_ERROR_RIGHT, 1, 0),
        Adjacent(F_ADJ_DOWN, F_ADJ_UP, F_ERROR_DOWN, 0, 1),
        Adjacent(F_ADJ_LEFT, F_ADJ_RIGHT, F_ERROR_LEFT, -1, 0),
    )

    /** The four clue bits with the word of their direction, for speech. */
    private val ADJ_DIRECTIONS = listOf(
        F_ADJ_UP to "up",
        F_ADJ_RIGHT to "right",
        F_ADJ_DOWN to "down",
        F_ADJ_LEFT to "left",
    )

    private const val MAXTRIES = 50

    /** `struct game_params`, `unequal.c:72-76`. */
    data class Params(val order: Int, val diff: Int, val mode: Int) : Game.Params {
        override fun copy(): Game.Params = Params(order, diff, mode)
    }

    /**
     * `struct game_state`, `unequal.c:98-105`.
     *
     * [nums] and [flags] are row-major `y*order + x`. [hints] is the
     * Latin cube layout `(x*order + y)*order + digit - 1`. [params] is
     * the short form; the state never reads its difficulty.
     */
    class State internal constructor(
        val params: Params,
        val nums: IntArray,
        val hints: BooleanArray,
        val flags: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val order: Int get() = params.order
        val mode: Int get() = params.mode
        val a: Int get() = order * order

        /** `GRID(state, nums, x, y)`, the row-major cell index. */
        fun num(x: Int, y: Int): Int = nums[y * order + x]

        /** `GRID(state, flags, x, y)`. */
        fun flag(x: Int, y: Int): Int = flags[y * order + x]

        /** `HINT(state, x, y, n)`, the Latin cube index. */
        fun hint(x: Int, y: Int, n: Int): Boolean = hints[(x * order + y) * order + n]

        internal fun setHint(x: Int, y: Int, n: Int, value: Boolean) {
            hints[(x * order + y) * order + n] = value
        }

        internal fun duplicate(): State = State(
            params = params,
            nums = nums.copyOf(),
            hints = hints.copyOf(),
            flags = flags.copyOf(),
            completed = completed,
            cheated = cheated,
        )

        override fun copy(): Game.State = duplicate()

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                nums.contentEquals(other.nums) &&
                hints.contentEquals(other.hints) &&
                flags.contentEquals(other.flags) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + nums.contentHashCode()
            result = 31 * result + hints.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`, `unequal.c:1453-1469`. */
    class Ui internal constructor() : Game.Ui {
        internal var hx: Int = 0
        internal var hy: Int = 0
        internal var hshow: Boolean = false
        internal var hpencil: Boolean = false
        internal var hcursor: Boolean = false

        /**
         * The `pencil-keep-highlight` preference, `true` is the C
         * default. Only `interpretMove` reads it. It is not saved and
         * not in the drawstate.
         */
        var pencilKeepHighlight: Boolean = true
    }

    /** `struct game_drawstate`, `unequal.c:1542-1553`. */
    class DrawState internal constructor(
        val tileSize: Int,
        val order: Int,
        val mode: Int,
        internal val nums: IntArray,
        internal val hints: BooleanArray,
        internal val flags: IntArray,
    ) : Game.DrawState {
        internal var started: Boolean = false
        internal var hx: Int = 0
        internal var hy: Int = 0
        internal var hshow: Boolean = false
        internal var hpencil: Boolean = false
        internal var hflash: Boolean = false
    }

    /** Retry and difficulty diagnostics. They change no description. */
    internal class GenerationStats {
        var attempts = 0

        /** The 50-try fallback accepted a too-easy board. */
        var usedFallback = false

        /** The difficulty the accepted board was generated for. */
        var effectiveDiff = -1
    }

    internal data class Generated(
        val description: Description,
        val stats: GenerationStats,
    )

    private val PRESETS = listOf(
        Params(4, DIFF_EASY, MODE_UNEQUAL),
        Params(5, DIFF_EASY, MODE_UNEQUAL),
        Params(5, DIFF_SET, MODE_UNEQUAL),
        Params(5, DIFF_SET, MODE_ADJACENT),
        Params(5, DIFF_EXTREME, MODE_UNEQUAL),
        Params(6, DIFF_EASY, MODE_UNEQUAL),
        Params(6, DIFF_SET, MODE_UNEQUAL),
        Params(6, DIFF_SET, MODE_ADJACENT),
        Params(6, DIFF_EXTREME, MODE_UNEQUAL),
        Params(7, DIFF_SET, MODE_UNEQUAL),
        Params(7, DIFF_SET, MODE_ADJACENT),
        Params(7, DIFF_EXTREME, MODE_UNEQUAL),
    )

    override val name: String = "Unequal"
    override val backendName: String = "unequal"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val flags: Int = Game.REQUIRE_RBUTTON or Game.REQUIRE_NUMPAD

    // ------------------------------------------------------------------
    // Parameters, `unequal.c:129-278`.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = PRESETS[0]

    override fun presets(): List<Preset> = PRESETS.map { p ->
        val modeName = if (p.mode == MODE_ADJACENT) "Adjacent" else "Unequal"
        Preset("$modeName: ${p.order}x${p.order} ${DIFF_NAMES[p.diff]}", encodeParams(p, true))
    }

    /**
     * `decode_params`, `unequal.c:189-214`.
     *
     * The digit skip starts at the first character, so a leading sign or
     * space stops it and a `d` after that is not recognised. The base
     * difficulty is the game default, Easy, as the midend decodes over
     * its defaults.
     */
    override fun decodeParams(text: String): Game.Params {
        val order = cAtoi(text, 0)
        var index = 0
        while (index < text.length && text[index].isAsciiDigit()) index++

        val mode = if (index < text.length && text[index] == 'a') {
            index++
            MODE_ADJACENT
        } else {
            MODE_UNEQUAL
        }

        var diff = DIFF_EASY
        if (index < text.length && text[index] == 'd') {
            index++
            diff = DIFF_INVALID
            if (index < text.length) {
                val next = text[index]
                for (i in 0 until DIFFCOUNT) {
                    if (next == DIFF_CHARS[i]) diff = i
                }
                index++
            }
        }
        return Params(order, diff, mode)
    }

    /**
     * `encode_params`, `unequal.c:216-227`: the short form is
     * `<order>[a]`; the full form adds `d` and the character.
     *
     * C indexes `unequal_diffchars[diff]` with no bound, which is
     * undefined behavior outside `0..4`. The port writes a plain `d`
     * with no character for every such value, the defined result the
     * other ports use. Validation rejects such a set first.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.order)
        if (p.mode == MODE_ADJACENT) out.append('a')
        if (full) {
            out.append('d')
            if (p.diff in 0 until DIFFCOUNT) out.append(DIFF_CHARS[p.diff])
        }
        return out.toString()
    }

    /** `validate_params`, `unequal.c:268-278`, in C order. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.order < 3 || p.order > 32) return "Order must be between 3 and 32"
        if (p.diff >= DIFFCOUNT) return "Unknown difficulty rating"
        if (p.order < 5 && p.mode == MODE_ADJACENT && p.diff >= DIFF_SET) {
            return "Order must be at least 5 for Adjacent puzzles of this difficulty."
        }
        return null
    }

    // ------------------------------------------------------------------
    // Description, `unequal.c:1290-1427`.
    // ------------------------------------------------------------------

    /** A description error from `load_game`. */
    private class BadDescription(message: String) : Exception(message)

    private fun blankState(order: Int, mode: Int): State = State(
        params = Params(order, DIFF_EASY, mode),
        nums = IntArray(order * order),
        hints = BooleanArray(order * order * order),
        flags = IntArray(order * order),
        completed = false,
        cheated = false,
    )

    /**
     * `load_game`, `unequal.c:1290-1374`: the parser, the run-length
     * form, and the flag scan. The first failure throws [BadDescription]
     * with the C message.
     *
     * The C loop checks `i >= order*order` after it reads the run-length
     * letters and before it reads a number, so a description with more
     * than `order^2` cells is rejected with "Too much data to fit in
     * grid" rather than writing past the array.
     */
    private fun loadGame(params: Params, desc: String): State {
        val o = params.order
        val o2 = o * o
        val state = blankState(o, params.mode)

        var index = 0
        var i = 0
        while (index < desc.length) {
            while (index < desc.length && desc[index] >= 'a' && desc[index] <= 'z') {
                i += desc[index] - 'a' + 1
                index++
            }
            if (i >= o2) throw BadDescription("Too much data to fit in grid")

            if (index >= desc.length || !desc[index].isAsciiDigit()) {
                throw BadDescription("Expecting number in game description")
            }
            val n = cAtoi(desc, index)
            if (n < 0 || n > o) {
                throw BadDescription("Number out of range in game description")
            }
            state.nums[i] = n
            while (index < desc.length && desc[index].isAsciiDigit()) index++

            if (n != 0) state.flags[i] = state.flags[i] or F_IMMUTABLE

            while (index < desc.length && desc[index] in "URDL") {
                when (desc[index]) {
                    'U' -> state.flags[i] = state.flags[i] or F_ADJ_UP
                    'R' -> state.flags[i] = state.flags[i] or F_ADJ_RIGHT
                    'D' -> state.flags[i] = state.flags[i] or F_ADJ_DOWN
                    'L' -> state.flags[i] = state.flags[i] or F_ADJ_LEFT
                }
                index++
            }
            i++
            if (i < o2 && (index >= desc.length || desc[index] != ',')) {
                throw BadDescription("Missing separator")
            }
            if (index < desc.length && desc[index] == ',') index++
        }
        if (i < o2) throw BadDescription("Not enough data to fill grid")

        for (y in 0 until o) {
            for (x in 0 until o) {
                for (n in 0 until 4) {
                    val bit = ADJ[n].f
                    if (state.flags[y * o + x] and bit == 0) continue
                    val nx = x + ADJ[n].dx
                    val ny = y + ADJ[n].dy
                    if (nx < 0 || ny < 0 || nx >= o || ny >= o) {
                        throw BadDescription("Flags go off grid")
                    }
                    val neighbour = state.flags[ny * o + nx]
                    if (params.mode == MODE_ADJACENT) {
                        if (neighbour and ADJ[n].fo == 0) {
                            throw BadDescription("Flags contradicting each other")
                        }
                    } else {
                        if (neighbour and ADJ[n].fo != 0) {
                            throw BadDescription("Flags contradicting each other")
                        }
                    }
                }
            }
        }

        return state
    }

    /** `validate_desc`, `unequal.c:1417-1427`: `load_game` and a free. */
    override fun validateDesc(params: Game.Params, desc: String): String? =
        try {
            loadGame(params as Params, desc)
            null
        } catch (e: BadDescription) {
            e.message
        }

    /**
     * `new_game`, `unequal.c:1406-1415`. C asserts that a validated
     * description loads and aborts otherwise; the port throws, because
     * the midend always validates first.
     */
    override fun newState(params: Game.Params, desc: String): Game.State =
        try {
            loadGame(params as Params, desc)
        } catch (e: BadDescription) {
            throw IllegalArgumentException(e.message)
        }

    /** `game_configure` (`unequal.c:229-255`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Choices(
                "Mode",
                ":Unequal:Adjacent",
                null,
                p.mode,
            ),
            ConfigItem.Text("Size (s*s)", p.order.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Trivial:Easy:Tricky:Extreme:Recursive",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`unequal.c:257-266`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
            (items[0] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`unequal.c:1494-1510`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Keep cursor after changing numbers",
                true,
                "pencil-keep-highlight",
            ),
        )

    /** `set_prefs` (`unequal.c:1512-1515`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).pencilKeepHighlight = prefs.bool("pencil-keep-highlight")
    }

    // ------------------------------------------------------------------
    // Generation, `unequal.c:949-1288`.
    // ------------------------------------------------------------------

    override fun newDesc(params: Game.Params, random: RandomState): Description =
        generateWithStats(params as Params, random).description

    /**
     * The generator with retry diagnostics. [maxTries] is the
     * `MAXTRIES` parameter; the C release build fixes it at 50, and
     * `STANDALONE_SOLVER` exposes it as `maxtries`. A caller must not
     * pass another value outside a test, because it changes which board
     * the fallback accepts, not the rules.
     */
    internal fun generateWithStats(
        params: Params,
        random: RandomState,
        maxTries: Int = MAXTRIES,
    ): Generated {
        val o = params.order
        val o2 = o * o
        val state = blankState(o, params.mode)
        val stats = GenerationStats()

        /*
         * The scratch list is built once, before the first attempt.
         * A retry shuffles the already shuffled list, not the initial
         * order, and the blocks are not re-initialised.
         */
        val scratch = IntArray(5 * o2) { (it % o2) * 5 + 4 - (it / o2) }

        var diff = params.diff
        var latin: IntArray
        while (true) {
            stats.attempts++

            latin = generate(o, random)

            // Separately shuffle the numeric and the inequality clues.
            shuffleRange(scratch, 0, o2, random)
            shuffleRange(scratch, o2, 4 * o2, random)

            state.nums.fill(0)
            state.flags.fill(0)
            // C does not zero `state->hints`; nothing reads them during
            // generation, because the solver writes the copy's cube.

            if (state.mode == MODE_ADJACENT) addAdjacentFlags(state, latin)

            gameAssemble(state, scratch, latin, diff)
            gameStrip(state, scratch, latin, diff)

            if (diff > 0) {
                val copy = state.duplicate()
                val nsol = solverState(copy, diff - 1)
                if (nsol > 0) {
                    if (stats.attempts < maxTries) continue
                    diff--
                    stats.usedFallback = true
                }
            }
            break
        }

        stats.effectiveDiff = diff
        return Generated(Description(encodeDesc(state), latinDesc(latin, o)), stats)
    }

    /** `latin_desc`, `unequal.c:949-960`: `S` and row-major digits. */
    private fun latinDesc(square: IntArray, order: Int): String {
        val out = StringBuilder(order * order + 1)
        out.append('S')
        for (i in 0 until order * order) out.append(n2c(square[i], order))
        return out.toString()
    }

    /**
     * `gg_place_clue`, `unequal.c:962-1008`. Returns true when the clue
     * was placed, or could have been placed when [checkOnly] is true.
     */
    private fun ggPlaceClue(
        state: State,
        code: Int,
        latin: IntArray,
        checkOnly: Boolean,
    ): Boolean {
        val loc = code / 5
        val which = code % 5
        val o = state.order
        val x = loc % o
        val y = loc / o

        if (which == 4) {
            if (state.nums[loc] != 0) {
                check(state.nums[loc] == latin[loc]) {
                    "unequal clue $loc is ${state.nums[loc]}, not ${latin[loc]}"
                }
                return false
            }
            if (!checkOnly) state.nums[loc] = latin[loc]
        } else {
            if (state.mode == MODE_ADJACENT) return false
            if (state.flags[loc] and ADJ[which].f != 0) return false

            val lx = x + ADJ[which].dx
            val ly = y + ADJ[which].dy
            if (lx < 0 || ly < 0 || lx >= o || ly >= o) return false

            val lloc = loc + ADJ[which].dx + ADJ[which].dy * o
            if (latin[loc] <= latin[lloc]) return false
            if (!checkOnly) state.flags[loc] = state.flags[loc] or ADJ[which].f
        }
        return true
    }

    /** `gg_remove_clue`, `unequal.c:1010-1045`. */
    private fun ggRemoveClue(state: State, code: Int, checkOnly: Boolean): Boolean {
        val loc = code / 5
        val which = code % 5

        if (which == 4) {
            if (state.nums[loc] == 0) return false
            if (!checkOnly) state.nums[loc] = 0
        } else {
            if (state.mode == MODE_ADJACENT) return false
            if (state.flags[loc] and ADJ[which].f == 0) return false
            if (!checkOnly) state.flags[loc] = state.flags[loc] and ADJ[which].f.inv()
        }
        return true
    }

    /**
     * `gg_best_clue`, `unequal.c:1047-1085`.
     *
     * The scan is downward over the scratch list, so the up clues come
     * first and the numbers last. `state.hints[loc*order + j]` reads the
     * cube slice of the transposed cell `(y,x)`, not of `(x,y)`; that is
     * the upstream bug and it changes every description when the cube is
     * asymmetric. [State.flag] is the correct cell for `nclues`.
     */
    private fun ggBestClue(state: State, scratch: IntArray, latin: IntArray): Int {
        val o = state.order
        val ls = o * o * 5
        var maxPoss = 0
        var minClues = 5
        var best = -1
        var index = ls
        while (index-- > 0) {
            if (!ggPlaceClue(state, scratch[index], latin, true)) continue

            val loc = scratch[index] / 5
            var nposs = 0
            for (j in 0 until o) {
                if (state.hints[loc * o + j]) nposs++
            }
            var nclues = 0
            for (j in 0 until 4) {
                if (state.flags[loc] and ADJ[j].f != 0) nclues++
            }
            if (nposs > maxPoss || (nposs == maxPoss && nclues < minClues)) {
                best = index
                maxPoss = nposs
                minClues = nclues
            }
        }
        check(best != -1) { "unequal generation found no clue to place" }
        return best
    }

    /**
     * `game_assemble`, `unequal.c:1095-1134`. A Recursive request is
     * clamped to Extreme for the assembly only, because a guessing solver
     * could place a clue that disagrees with the stored square.
     */
    private fun gameAssemble(state: State, scratch: IntArray, latin: IntArray, difficulty: Int) {
        var level = difficulty
        if (level >= DIFF_RECURSIVE) level = DIFF_RECURSIVE - 1

        val copy = state.duplicate()
        while (true) {
            if (solverState(copy, level) == 1) break
            val best = ggBestClue(copy, scratch, latin)
            ggPlaceClue(state, scratch[best], latin, false)
            ggPlaceClue(copy, scratch[best], latin, false)
        }
    }

    /**
     * `game_strip`, `unequal.c:1136-1170`: remove each clue in the
     * shuffled order and put it back when the board is no longer solved
     * at the requested level. In Adjacent mode a flag removal always
     * fails, so only the numbers are stripped.
     */
    private fun gameStrip(state: State, scratch: IntArray, latin: IntArray, difficulty: Int) {
        val o = state.order
        val copy = blankState(o, state.mode)
        for (i in 0 until o * o * 5) {
            if (!ggRemoveClue(state, scratch[i], false)) continue

            state.nums.copyInto(copy.nums)
            state.flags.copyInto(copy.flags)
            if (solverState(copy, difficulty) != 1) {
                check(ggPlaceClue(state, scratch[i], latin, false)) {
                    "unequal strip could not restore clue ${scratch[i]}"
                }
            }
        }
    }

    /** `add_adjacent_flags`, `unequal.c:1172-1192`. */
    private fun addAdjacentFlags(state: State, latin: IntArray) {
        val o = state.order
        for (y in 0 until o) {
            for (x in 0 until o) {
                if (x < o - 1 && abs(latin[y * o + x] - latin[y * o + x + 1]) == 1) {
                    state.flags[y * o + x] = state.flags[y * o + x] or F_ADJ_RIGHT
                    state.flags[y * o + x + 1] = state.flags[y * o + x + 1] or F_ADJ_LEFT
                }
                if (y < o - 1 && abs(latin[y * o + x] - latin[(y + 1) * o + x]) == 1) {
                    state.flags[y * o + x] = state.flags[y * o + x] or F_ADJ_DOWN
                    state.flags[(y + 1) * o + x] = state.flags[(y + 1) * o + x] or F_ADJ_UP
                }
            }
        }
    }

    /** The encoder of `unequal.c:1265-1280`, with the trailing comma. */
    private fun encodeDesc(state: State): String {
        val o = state.order
        val out = StringBuilder()
        for (y in 0 until o) {
            for (x in 0 until o) {
                val f = state.flags[y * o + x]
                out.append(state.nums[y * o + x])
                if (f and F_ADJ_UP != 0) out.append('U')
                if (f and F_ADJ_RIGHT != 0) out.append('R')
                if (f and F_ADJ_DOWN != 0) out.append('D')
                if (f and F_ADJ_LEFT != 0) out.append('L')
                out.append(',')
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Solver, `unequal.c:544-943`.
    // ------------------------------------------------------------------

    private class Link(val gx: Int, val gy: Int, val lx: Int, val ly: Int, val len: Int)

    /**
     * `struct solver_ctx`, `unequal.c:548-553`. [state] is the board
     * whose flags and mode the strategies read. The cube comes from the
     * solver, and a recursive attempt keeps the same state.
     */
    private class SolverContext(val state: State, val links: List<Link>)

    /** `new_ctx`, `unequal.c:573-598`: x outer, y inner, up/right/down/left. */
    private fun newCtx(state: State): SolverContext {
        val o = state.order
        if (state.mode == MODE_ADJACENT) return SolverContext(state, emptyList())

        val links = ArrayList<Link>()
        for (x in 0 until o) {
            for (y in 0 until o) {
                val f = state.flags[y * o + x]
                for (i in 0 until 4) {
                    if (f and ADJ[i].f != 0) {
                        links.add(Link(x, y, x + ADJ[i].dx, y + ADJ[i].dy, 1))
                    }
                }
            }
        }
        return SolverContext(state, links)
    }

    /**
     * `solver_nminmax`, `unequal.c:613-637`: the smallest and largest
     * candidate at a cell. A filled cell reports its own digit minus one,
     * which is the zero-based C convention. When no digit is possible,
     * the C initial values remain: min is `order` and max is 0.
     */
    private fun nminmax(solver: LatinSolver, x: Int, y: Int): Pair<Int, Int> {
        val o = solver.order
        val value = solver.grid[y * o + x]
        if (value > 0) return (value - 1) to (value - 1)

        var low = o
        var high = 0
        for (n in 0 until o) {
            if (solver.cubeAt(x, y, n + 1)) {
                if (n > high) high = n
                if (n < low) low = n
            }
        }
        return low to high
    }

    /**
     * `solver_links`, `unequal.c:639-691`. The cube is read live in the
     * inner loop, as C reads through its pointer, but the two bounds are
     * a snapshot taken before the loop.
     */
    private fun solverLinks(solver: LatinSolver, ctx: SolverContext): Int {
        val o = solver.order
        var changed = 0
        for (link in ctx.links) {
            val greater = nminmax(solver, link.gx, link.gy)
            val lesser = nminmax(solver, link.lx, link.ly)
            val gmax = greater.second
            val lmin = lesser.first
            for (j in 0 until o) {
                if (solver.cubeAt(link.gx, link.gy, j + 1) && j < lmin + link.len) {
                    solver.setCube(link.gx, link.gy, j + 1, false)
                    changed++
                }
                if (solver.cubeAt(link.lx, link.ly, j + 1) && j > gmax - link.len) {
                    solver.setCube(link.lx, link.ly, j + 1, false)
                    changed++
                }
            }
        }
        return changed
    }

    /** `solver_adjacent`, `unequal.c:693-742`. */
    private fun solverAdjacent(solver: LatinSolver, ctx: SolverContext): Int {
        val o = solver.order
        var changed = 0
        for (x in 0 until o) {
            for (y in 0 until o) {
                val value = solver.grid[y * o + x]
                if (value == 0) continue
                for (i in 0 until 4) {
                    val isAdjacent = ctx.state.flags[y * o + x] and ADJ[i].f != 0
                    val nx = x + ADJ[i].dx
                    val ny = y + ADJ[i].dy
                    if (nx < 0 || ny < 0 || nx >= o || ny >= o) continue
                    for (n in 0 until o) {
                        val gd = abs((n + 1) - value)
                        if (isAdjacent && gd == 1) continue
                        if (!isAdjacent && gd != 1) continue
                        if (!solver.cubeAt(nx, ny, n + 1)) continue
                        solver.setCube(nx, ny, n + 1, false)
                        changed++
                    }
                }
            }
        }
        return changed
    }

    /** `solver_adjacent_set`, `unequal.c:744-807`. */
    private fun solverAdjacentSet(solver: LatinSolver, ctx: SolverContext): Int {
        val o = solver.order
        val scratch = IntArray(o)
        var changed = 0
        for (x in 0 until o) {
            for (y in 0 until o) {
                for (i in 0 until 4) {
                    val isAdjacent = ctx.state.flags[y * o + x] and ADJ[i].f != 0
                    val nx = x + ADJ[i].dx
                    val ny = y + ADJ[i].dy
                    if (nx < 0 || ny < 0 || nx >= o || ny >= o) continue

                    scratch.fill(0)
                    for (n in 0 until o) {
                        if (!solver.cubeAt(x, y, n + 1)) continue
                        for (nn in 0 until o) {
                            if (n == nn) continue
                            val gd = abs(nn - n)
                            if (isAdjacent && gd != 1) continue
                            if (!isAdjacent && gd == 1) continue
                            scratch[nn] = 1
                        }
                    }
                    for (n in 0 until o) {
                        if (scratch[n] == 1) continue
                        if (!solver.cubeAt(nx, ny, n + 1)) continue
                        solver.setCube(nx, ny, n + 1, false)
                        changed++
                    }
                }
            }
        }
        return changed
    }

    /** `solver_easy`, `unequal.c:809-816`. */
    private fun solverEasy(solver: LatinSolver, ctx: SolverContext): Int =
        if (ctx.state.mode == MODE_ADJACENT) {
            solverAdjacent(solver, ctx)
        } else {
            solverLinks(solver, ctx)
        }

    /** `solver_set`, `unequal.c:818-825`: Tricky is a no-op in Unequal mode. */
    private fun solverSet(solver: LatinSolver, ctx: SolverContext): Int =
        if (ctx.state.mode == MODE_ADJACENT) solverAdjacentSet(solver, ctx) else 0

    private val UNEQUAL_SOLVERS: List<LatinUserSolver?> = listOf(
        null,
        { solver, ctx -> solverEasy(solver, ctx as SolverContext) },
        { solver, ctx -> solverSet(solver, ctx as SolverContext) },
        null,
        null,
    )

    /**
     * `unequal_valid`, `unequal.c:830-896`.
     *
     * The Adjacent loop bounds `x + 1 < order` and `y + 1 < order` are
     * copied literally. They skip the last row and the last column as
     * sources, so a wrong pair there is never checked. The generator's
     * accepted boards are valid, but a recursive guess can pass.
     */
    private val UNEQUAL_VALIDATOR: LatinValidator = { solver, ctx ->
        val context = ctx as SolverContext
        val state = context.state
        val o = solver.order
        if (state.mode == MODE_ADJACENT) {
            var ok = true
            var x = 0
            while (x + 1 < o && ok) {
                var y = 0
                while (y + 1 < o && ok) {
                    val v = solver.grid[y * o + x]
                    for (i in 0 until 4) {
                        val shouldBeAdjacent = state.flags[y * o + x] and ADJ[i].f != 0
                        val nx = x + ADJ[i].dx
                        val ny = y + ADJ[i].dy
                        if (nx < 0 || ny < 0 || nx >= o || ny >= o) continue
                        val nv = solver.grid[ny * o + nx]
                        val isAdjacent = abs(v - nv) == 1
                        if (isAdjacent && !shouldBeAdjacent) {
                            ok = false
                            break
                        }
                        if (!isAdjacent && shouldBeAdjacent) {
                            ok = false
                            break
                        }
                    }
                    y++
                }
                x++
            }
            ok
        } else {
            var ok = true
            for (link in context.links) {
                val gv = solver.grid[link.gy * o + link.gx]
                val lv = solver.grid[link.ly * o + link.lx]
                if (gv <= lv) {
                    ok = false
                    break
                }
            }
            ok
        }
    }

    /**
     * The five `latin_solver` slots, `unequal.c:904-909`: simple 0,
     * set-0 2, set-1 3, forcing 3, recursive 4.
     */
    internal fun solverSlots(): IntArray =
        intArrayOf(DIFF_TRIVIAL, DIFF_SET, DIFF_EXTREME, DIFF_EXTREME, DIFF_RECURSIVE)

    /**
     * `solver_state`, `unequal.c:898-926`.
     *
     * `LatinSolver.allocate` returns the partially built cube even when
     * a digit cannot be placed, so the cube is copied into [State.hints]
     * on every path, including the impossible one. That is the `H` hint,
     * and it is also what `gg_best_clue` reads during generation.
     */
    private fun solverState(state: State, maxDiff: Int): Int {
        val ctx = newCtx(state)
        val solver = LatinSolver.allocate(state.nums, state.order)
        val diff = if (solver.valid) {
            val slots = solverSlots()
            latinSolveMain(
                solver,
                maxDiff,
                slots[0],
                slots[1],
                slots[2],
                slots[3],
                slots[4],
                UNEQUAL_SOLVERS,
                UNEQUAL_VALIDATOR,
                ctx,
                { copy -> newCtx((copy as SolverContext).state) },
                { },
            )
        } else {
            LatinSolver.DIFF_IMPOSSIBLE
        }
        solver.cube.copyInto(state.hints)
        return when (diff) {
            LatinSolver.DIFF_IMPOSSIBLE -> -1
            LatinSolver.DIFF_UNFINISHED -> 0
            LatinSolver.DIFF_AMBIGUOUS -> 2
            else -> 1
        }
    }

    /** `solver_hint`, `unequal.c:928-943`: the first level that reports progress. */
    private fun solverHint(state: State, minDiff: Int, maxDiff: Int): State {
        val ret = state.duplicate()
        for (diff in minDiff..maxDiff) {
            if (solverState(ret, diff) != 0) break
        }
        return ret
    }

    /**
     * The smallest solver level that reports progress on [state].
     * Diagnostics only: it does not change the state. A generated board
     * measures its effective difficulty, because the retry gate rejects
     * a board that the previous level already solves.
     */
    fun difficulty(state: Game.State): Int {
        val s = state as State
        for (diff in 0 until DIFFCOUNT) {
            if (solverState(s.duplicate(), diff) > 0) return diff
        }
        return DIFFCOUNT
    }

    /**
     * `solve_game`, `unequal.c:1429-1447`. A stored answer is returned
     * unchanged. Otherwise every non-immutable digit is cleared and the
     * full solver runs; an ambiguous result still yields its first
     * solution, because the C test is `r > 0`.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? {
        val s = state as State
        if (auxInfo != null) return auxInfo

        val solved = s.duplicate()
        for (i in 0 until s.a) {
            if (solved.flags[i] and F_IMMUTABLE == 0) solved.nums[i] = 0
        }
        val result = solverState(solved, DIFFCOUNT - 1)
        return if (result > 0) latinDesc(solved.nums, solved.order) else null
    }

    // ------------------------------------------------------------------
    // Completion and errors, `unequal.c:334-437`.
    // ------------------------------------------------------------------

    /** `check_num_adj`, `unequal.c:334-380`. */
    private fun checkNumAdj(grid: IntArray, state: State, x: Int, y: Int): Boolean {
        val o = state.order
        val f = state.flags[y * o + x]
        var ret = true
        for (i in 0 until 4) {
            val nx = x + ADJ[i].dx
            val ny = y + ADJ[i].dy
            if (nx < 0 || ny < 0 || nx >= o || ny >= o) continue

            val n = grid[y * o + x]
            val dn = grid[ny * o + nx]
            if (dn == 0) continue

            if (state.mode == MODE_ADJACENT) {
                val gd = abs(n - dn)
                if (f and ADJ[i].f != 0 && gd != 1) {
                    state.flags[y * o + x] = state.flags[y * o + x] or ADJ[i].fe
                    ret = false
                }
                if (f and ADJ[i].f == 0 && gd == 1) {
                    state.flags[y * o + x] = state.flags[y * o + x] or ADJ[i].fe
                    ret = false
                }
            } else {
                if (f and ADJ[i].f != 0 && n <= dn) {
                    state.flags[y * o + x] = state.flags[y * o + x] or ADJ[i].fe
                    ret = false
                }
            }
        }
        return ret
    }

    /** `check_num_error`, `unequal.c:382-409`. */
    private fun checkNumError(
        grid: IntArray,
        state: State,
        x: Int,
        y: Int,
        markErrors: Boolean,
    ): Boolean {
        val o = state.order
        val value = grid[y * o + x]
        var ret = true
        for (yy in 0 until o) {
            if (yy == y) continue
            if (grid[yy * o + x] == value) ret = false
        }
        for (xx in 0 until o) {
            if (xx == x) continue
            if (grid[y * o + xx] == value) ret = false
        }
        if (!ret && markErrors) state.flags[y * o + x] = state.flags[y * o + x] or F_ERROR
        return ret
    }

    /**
     * `check_complete`, `unequal.c:415-437`: -1 wrong, 0 incomplete,
     * 1 complete and correct. The scan is x outer and y inner.
     */
    private fun checkComplete(grid: IntArray, state: State, markErrors: Boolean): Int {
        val o = state.order
        var ret = 1
        for (x in 0 until o) {
            for (y in 0 until o) {
                if (markErrors) {
                    state.flags[y * o + x] = state.flags[y * o + x] and F_ERROR_MASK.inv()
                }
                if (grid[y * o + x] == 0) {
                    ret = 0
                } else {
                    if (!checkNumError(grid, state, x, y, markErrors)) ret = -1
                    if (!checkNumAdj(grid, state, x, y)) ret = -1
                }
            }
        }
        if (ret == 1 && check(grid, o)) ret = -1
        return ret
    }

    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // Text form, `unequal.c:477-529`.
    // ------------------------------------------------------------------

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val o = s.order
        val out = StringBuilder((2 * o) * (2 * o - 1))
        for (y in 0 until o) {
            for (x in 0 until o) {
                val n = s.nums[y * o + x]
                out.append(if (n > 0) n2c(n, o) else '.')
                if (x < o - 1) {
                    if (s.mode == MODE_ADJACENT) {
                        out.append(if (s.flags[y * o + x] and F_ADJ_RIGHT != 0) '|' else ' ')
                    } else {
                        out.append(
                            when {
                                s.flags[y * o + x] and F_ADJ_RIGHT != 0 -> '>'
                                s.flags[y * o + x + 1] and F_ADJ_LEFT != 0 -> '<'
                                else -> ' '
                            },
                        )
                    }
                }
            }
            out.append('\n')

            if (y < o - 1) {
                for (x in 0 until o) {
                    if (s.mode == MODE_ADJACENT) {
                        out.append(if (s.flags[y * o + x] and F_ADJ_DOWN != 0) '-' else ' ')
                    } else {
                        out.append(
                            when {
                                s.flags[y * o + x] and F_ADJ_DOWN != 0 -> 'v'
                                s.flags[(y + 1) * o + x] and F_ADJ_UP != 0 -> '^'
                                else -> ' '
                            },
                        )
                    }
                    if (x < o - 1) out.append(' ')
                }
                out.append('\n')
            }
        }
        check(out.length == (2 * o) * (2 * o - 1)) {
            "unequal text length ${out.length} does not match the C total"
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Moves, `unequal.c:1754-1821`.
    // ------------------------------------------------------------------

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        if (move.isEmpty()) return null

        if (move[0] == 'P' || move[0] == 'R') {
            val fields = sscanfInts(move.substring(1), 3) ?: return null
            val x = fields[0]
            val y = fields[1]
            val n = fields[2]
            val o = s.order
            if (x < 0 || x >= o || y < 0 || y >= o || n < 0 || n > o) return null

            val ret = s.duplicate()
            if (move[0] == 'P' && n > 0) {
                ret.setHint(x, y, n - 1, !ret.hint(x, y, n - 1))
            } else {
                ret.nums[y * o + x] = n
                for (i in 0 until o) ret.setHint(x, y, i, false)
                if (!ret.completed && checkComplete(ret.nums, ret, true) > 0) {
                    ret.completed = true
                }
            }
            return ret
        }

        if (move[0] == 'S') {
            val o = s.order
            val a = o * o
            if (move.length != a + 1) return null
            val ret = s.duplicate()
            ret.cheated = true
            for (i in 0 until a) {
                val n = c2n(move[i + 1].code, o)
                if (n <= 0 || n > o) return null
                ret.nums[i] = n
            }
            if (!ret.completed && checkComplete(ret.nums, ret, true) > 0) {
                ret.completed = true
            }
            return ret
        }

        if (move[0] == 'M') {
            val o = s.order
            val ret = s.duplicate()
            for (x in 0 until o) {
                for (y in 0 until o) {
                    for (n in 0 until o) ret.setHint(x, y, n, true)
                }
            }
            return ret
        }

        if (move[0] == 'H') {
            val ret = solverHint(s, DIFF_EASY, DIFF_EASY)
            checkComplete(ret.nums, ret, true)
            return ret
        }

        if (move[0] == 'F') {
            val fields = sscanfInts(move.substring(1), 3) ?: return null
            val x = fields[0]
            val y = fields[1]
            val n = fields[2]
            val o = s.order
            if (x < 0 || x >= o || y < 0 || y >= o) return null
            if (n and F_SPENT_MASK.inv() != 0) return null
            val ret = s.duplicate()
            val cell = y * o + x
            ret.flags[cell] = ret.flags[cell] xor n
            return ret
        }

        return null
    }

    /**
     * `interpret_move`, `unequal.c:1555-1752`, with the Android input
     * state machine.
     *
     * The clue bands accept any mouse button: a middle press in a band is
     * a real `F` toggle, while a middle press on a cell is unused. The
     * bottom-right `GAP x GAP` corner is inert. A modified cursor move
     * toggles a clue without moving the cursor.
     *
     * The clue toggles in the gaps are the pointer moves; a cell click
     * only moves the highlight, and the value comes from the keypad.
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
        val o = s.order
        val t = tileSize
        val gap = t / 2
        val square = t + gap
        val border = gap

        val shiftOrControl = button and (Buttons.MOD_SHFT or Buttons.MOD_CTRL) != 0
        val key = Buttons.stripModifiers(button)

        val tx = (x - border + square) / square - 1
        val ty = (y - border + square) / square - 1

        if (tx in 0 until o && ty in 0 until o && Buttons.isMouseDown(key)) {
            val ox = tx * square + border
            val oy = ty * square + border
            val cell = ty * o + tx

            if (y - oy > t && x - ox > t) return MoveResult.Unused

            if (y - oy > t) {
                if (s.flags[cell] and F_ADJ_DOWN != 0) {
                    return MoveResult.Move("F$tx,$ty,$F_SPENT_DOWN")
                }
                if (ty + 1 < o && s.flags[(ty + 1) * o + tx] and F_ADJ_UP != 0) {
                    return MoveResult.Move("F$tx,${ty + 1},$F_SPENT_UP")
                }
                return MoveResult.Unused
            }

            if (x - ox > t) {
                if (s.flags[cell] and F_ADJ_RIGHT != 0) {
                    return MoveResult.Move("F$tx,$ty,$F_SPENT_RIGHT")
                }
                if (tx + 1 < o && s.flags[ty * o + tx + 1] and F_ADJ_LEFT != 0) {
                    return MoveResult.Move("F${tx + 1},$ty,$F_SPENT_LEFT")
                }
                return MoveResult.Unused
            }

            if (key == Buttons.LEFT_BUTTON) {
                if (s.flags[cell] and F_IMMUTABLE != 0) {
                    u.hshow = false
                    u.hpencil = false
                } else if (tx == u.hx && ty == u.hy && u.hshow) {
                    // Android toggles the pencil mode and keeps the
                    // highlight on a second click of the cell.
                    u.hpencil = !u.hpencil
                } else {
                    u.hx = tx
                    u.hy = ty
                    u.hshow = true
                }
                u.hcursor = false
                return MoveResult.UiUpdate
            }
            if (key == Buttons.RIGHT_BUTTON) {
                if (s.nums[cell] != 0) {
                    u.hshow = false
                    u.hpencil = false
                } else if (tx == u.hx && ty == u.hy && u.hshow && u.hpencil) {
                    u.hshow = false
                    u.hpencil = false
                } else {
                    u.hx = tx
                    u.hy = ty
                    u.hpencil = true
                    u.hshow = true
                }
                u.hcursor = false
                return MoveResult.UiUpdate
            }
        } else if (key == Buttons.LEFT_BUTTON || key == Buttons.RIGHT_BUTTON) {
            u.hshow = false
            u.hpencil = false
            return MoveResult.UiUpdate
        }

        // Only an uppercase H or M wins over data entry.
        if (key == 'H'.code) return MoveResult.Move("H")
        if (key == 'M'.code) return MoveResult.Move("M")

        if (Buttons.isCursorMove(key)) {
            if (shiftOrControl) {
                var nx = u.hx
                var ny = u.hy
                when (key) {
                    Buttons.CURSOR_UP -> ny = max(ny - 1, 0)
                    Buttons.CURSOR_DOWN -> ny = min(ny + 1, o - 1)
                    Buttons.CURSOR_LEFT -> nx = max(nx - 1, 0)
                    Buttons.CURSOR_RIGHT -> nx = min(nx + 1, o - 1)
                }
                u.hshow = true
                u.hcursor = true

                var i = 0
                while (i < 4 && (nx != u.hx + ADJ[i].dx || ny != u.hy + ADJ[i].dy)) i++
                if (i == 4) return MoveResult.UiUpdate

                val here = s.flags[u.hy * o + u.hx]
                val there = s.flags[ny * o + nx]
                if (here and ADJ[i].f == 0 && there and ADJ[i].fo == 0) {
                    return MoveResult.UiUpdate
                }

                val self = if (s.mode == MODE_ADJACENT) {
                    ADJ[i].dx >= 0 && ADJ[i].dy >= 0
                } else {
                    here and ADJ[i].f != 0
                }
                return if (self) {
                    MoveResult.Move("F${u.hx},${u.hy},${ADJ[i].f shl 9}")
                } else {
                    MoveResult.Move("F$nx,$ny,${ADJ[i].fo shl 9}")
                }
            } else {
                u.hcursor = true
                return moveCursor(key, u, o)
            }
        }

        if (u.hshow &&
            (key == Buttons.CURSOR_SELECT || key == Buttons.CURSOR_SELECT2)
        ) {
            u.hpencil = !u.hpencil
            u.hcursor = true
            return MoveResult.UiUpdate
        }

        val n = c2n(key, o)
        if (u.hshow && n in 0..o) {
            val cell = u.hy * o + u.hx
            if (s.flags[cell] and F_IMMUTABLE != 0) return MoveResult.Unused
            if (u.hpencil && s.nums[cell] > 0) return MoveResult.Unused

            if ((!u.hpencil || n == 0) && s.nums[cell] == n) {
                var anyPencil = false
                for (i in 0 until o) {
                    if (s.hint(u.hx, u.hy, i)) {
                        anyPencil = true
                        break
                    }
                }
                if (!anyPencil) {
                    if (!u.hcursor && !u.pencilKeepHighlight) {
                        u.hshow = false
                        return MoveResult.UiUpdate
                    }
                    return MoveResult.Unused
                }
            }

            val move = "${if (u.hpencil && n > 0) 'P' else 'R'}${u.hx},${u.hy},$n"
            if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
            return MoveResult.Move(move)
        }

        if (key == 'h'.code) return MoveResult.Move("H")
        if (key == 'm'.code) return MoveResult.Move("M")

        return MoveResult.Unused
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the digit or pencil marks with `given` as the
     * origin, the clues drawn in the gaps around it, the cursor, and
     * `wrong` on a duplicate. The clue words follow the drawing: the
     * arrow points at the smaller value, so a cell that owns a right clue
     * is greater than the cell on its right.
     *
     * A cell click only moves the highlight and the value comes from the
     * keypad, so no cell offers an action. The unit rectangle covers the
     * cell and its right and bottom gaps, which keeps the touch target a
     * whole cell; the spent marker of a clue is a cosmetic toggle in the
     * gap and stays on the pointer path.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val o = s.order
        val t = tileSize
        val gap = t / 2
        val square = t + gap
        val cells = gridSpeech(
            width = o,
            height = o,
            tileSize = square,
            left = gap,
            top = gap,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * o + x
                val cursor = if (u.hshow && u.hx == x && u.hy == y) ", cursor" else ""
                val wrong = if (s.flags[index] and F_ERROR != 0) SPOKEN_WRONG else ""
                digitSpeech(
                    value = s.nums[index],
                    given = s.flags[index] and F_IMMUTABLE != 0,
                    pencil = (1..o).filter { s.hint(x, y, it - 1) },
                ) + clueWords(s, index) + cursor + wrong
            },
        )
        return cells.capped(cells.units.getOrNull(u.hy * o + u.hx))
    }

    /** The greater-than or adjacency clues drawn around one cell. */
    private fun clueWords(s: State, index: Int): String {
        val flags = s.flags[index]
        val out = StringBuilder()
        if (s.mode == MODE_ADJACENT) {
            for ((flag, direction) in ADJ_DIRECTIONS) {
                if (flags and flag != 0) out.append(", adjacent $direction")
            }
        } else {
            if (flags and F_ADJ_UP != 0) out.append(", greater than up")
            if (flags and F_ADJ_RIGHT != 0) out.append(", greater than right")
            if (flags and F_ADJ_DOWN != 0) out.append(", greater than down")
            if (flags and F_ADJ_LEFT != 0) out.append(", less than left")
        }
        return out.toString()
    }

    /**
     * `move_cursor` with `wrap = false`: the cursor clamps to the grid.
     * A hidden cursor becomes visible and a clamped move on a visible
     * cursor is `MOVE_NO_EFFECT`.
     */
    private fun moveCursor(button: Int, ui: Ui, order: Int): MoveResult {
        var dx = 0
        var dy = 0
        when (button) {
            Buttons.CURSOR_UP -> dy = -1
            Buttons.CURSOR_DOWN -> dy = 1
            Buttons.CURSOR_LEFT -> dx = -1
            Buttons.CURSOR_RIGHT -> dx = 1
            else -> return MoveResult.Unused
        }
        val ox = ui.hx
        val oy = ui.hy
        ui.hx = min(max(ui.hx + dx, 0), order - 1)
        ui.hy = min(max(ui.hy + dy, 0), order - 1)

        if (!ui.hshow) {
            ui.hshow = true
            return MoveResult.UiUpdate
        }
        if (ui.hx != ox || ui.hy != oy) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /**
     * `game_changed_state`, `unequal.c:1517-1532`. Android clears
     * `hpencil` when it cancels a pencil-mode mouse highlight.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val s = newState as State
        if (u.hshow && u.hpencil && !u.hcursor && s.nums[u.hy * s.order + u.hx] != 0) {
            u.hshow = false
            u.hpencil = false
        }
        return s.completed && !s.cheated && oldState != null &&
            !(oldState as State).completed
    }

    // ------------------------------------------------------------------
    // Frontend state and keys, `unequal.c:1376-1540`.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `android_cursor_visibility`, `unequal.c:1489-1492`: only `hshow`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).hshow = visible
    }

    /** `game_get_cursor_location`, `unequal.c:2175-2186`: the whole tile. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.hshow) return null
        val gap = tileSize / 2
        val square = tileSize + gap
        return Game.CursorRect(
            left = u.hx * square + gap,
            top = u.hy * square + gap,
            width = tileSize,
            height = tileSize,
        )
    }

    /**
     * `game_request_keys`, `unequal.c:1376-1404`. The C labels are NULL
     * and the midend replaces them with `button2label`, so a digit keeps
     * its character and backspace becomes `Clear`.
     *
     * At order 10 and above the first button is `'0'`, whose label is
     * `0` while `c2n` maps it to digit 1. The whole digit row is then off
     * by one, and digit 1 has no key of its own. Reproduce it.
     */
    override fun requestKeys(params: Game.Params): KeyRequest {
        val order = (params as Params).order
        val labels = ArrayList<KeyLabel>(order + 3)
        var off = if (order > 9) '0'.code else '1'.code
        for (i in 0 until order) {
            if (i == 10) off = 'a'.code - 10
            val button = i + off
            labels += KeyLabel(button, button.toChar().toString())
        }
        labels += KeyLabel('\b'.code, "Clear")
        labels += KeyLabel('M'.code, "Mark")
        labels += KeyLabel('H'.code, "Mark/Hints")
        return KeyRequest(labels, ArrowMode.ARROWS_LEFT)
    }

    /**
     * `current_key_label`, `unequal.c:1534-1540`. The seam has no
     * operation for this, so it is a public helper with a test.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if (u.hshow && (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2)) {
            return if (u.hpencil) "Ink" else "Pencil"
        }
        return ""
    }

    // ------------------------------------------------------------------
    // Palette and drawing, `unequal.c:1827-2158`.
    // ------------------------------------------------------------------

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]

        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        for (i in 0 until 3) {
            ret[COL_TEXT * 3 + i] = 0.0f
            ret[COL_GRID * 3 + i] = 0.5f
        }

        ret[COL_GUESS * 3 + 0] = 0.0f
        ret[COL_GUESS * 3 + 1] = 0.6f * ret[COL_BACKGROUND * 3 + 1]
        ret[COL_GUESS * 3 + 2] = 0.0f

        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_PENCIL * 3 + 0] = 0.5f * ret[COL_BACKGROUND * 3 + 0]
        ret[COL_PENCIL * 3 + 1] = 0.5f * ret[COL_BACKGROUND * 3 + 1]
        ret[COL_PENCIL * 3 + 2] = ret[COL_BACKGROUND * 3 + 2]
        return ret
    }

    /**
     * `game_compute_size`, `unequal.c:1827-1838`:
     * `T*order + (T/2)*(order-1) + 2*(T/2)`.
     */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val o = (params as Params).order
        val gap = tileSize / 2
        val side = tileSize * o + gap * (o - 1) + 2 * (tileSize / 2)
        return Size(side, side)
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(
            tileSize = tileSize,
            order = s.order,
            mode = s.mode,
            nums = IntArray(s.a),
            hints = BooleanArray(s.a * s.order),
            flags = IntArray(s.a),
        )
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
        val o = ds.order
        val t = ds.tileSize
        val gap = t / 2
        val square = t + gap
        val border = gap
        val drawSize = t * o + gap * (o - 1) + 2 * border
        val hflash = false

        if (!ds.started) {
            sink.rect(0, 0, drawSize, drawSize, COL_BACKGROUND)
            sink.update(0, 0, drawSize, drawSize)
        }

        val hchanged = ds.hx != u.hx || ds.hy != u.hy ||
            ds.hshow != u.hshow || ds.hpencil != u.hpencil

        // The dirty scan is column-major, as in C.
        for (x in 0 until o) {
            for (y in 0 until o) {
                val cell = y * o + x
                var stale = !ds.started || hflash != ds.hflash

                if (hchanged) {
                    if ((x == u.hx && y == u.hy) || (x == ds.hx && y == ds.hy)) {
                        stale = true
                    }
                }

                if (s.nums[cell] != ds.nums[cell]) {
                    ds.nums[cell] = s.nums[cell]
                    stale = true
                }
                if (s.flags[cell] != ds.flags[cell]) {
                    ds.flags[cell] = s.flags[cell]
                    stale = true
                }
                if (ds.nums[cell] == 0) {
                    for (i in 0 until o) {
                        if (s.hint(x, y, i) != ds.hints[(x * o + y) * o + i]) {
                            ds.hints[(x * o + y) * o + i] = s.hint(x, y, i)
                            stale = true
                        }
                    }
                }

                if (stale) {
                    drawFurniture(sink, ds, s, u, x, y, t, square, border, hflash)
                    if (ds.nums[cell] > 0) {
                        drawNum(sink, ds, x, y, t, square, border)
                    } else {
                        drawHints(sink, ds, x, y, t, square, border)
                    }
                }
            }
        }

        ds.hx = u.hx
        ds.hy = u.hy
        ds.hshow = u.hshow
        ds.hpencil = u.hpencil
        ds.started = true
        ds.hflash = hflash
    }

    /** `draw_gt`, `unequal.c:1908-1926`: the filled six-point arrow. */
    private fun drawGt(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        dx1: Int,
        dy1: Int,
        dx2: Int,
        dy2: Int,
        colour: Int,
    ) {
        val xdx = if (dx1 + dx2 != 0) 0 else 1
        val xdy = if (dx1 + dx2 != 0) 1 else 0
        sink.poly(
            intArrayOf(
                ox + xdx, oy + xdy,
                ox + xdx + dx1, oy + xdy + dy1,
                ox + xdx + dx1 + dx2, oy + xdy + dy1 + dy2,
                ox - xdx + dx1 + dx2, oy - xdy + dy1 + dy2,
                ox - xdx + dx1, oy - xdy + dy1,
                ox - xdx, oy - xdy,
            ),
            colour,
            colour,
        )
    }

    /** The `COLOUR(direction)` precedence: error, then spent, then foreground. */
    private fun clueColour(f: Int, error: Int, spent: Int, foreground: Int): Int = when {
        f and error != 0 -> COL_ERROR
        f and spent != 0 -> COL_SPENT
        else -> foreground
    }

    /** `draw_rect_outline` from `misc.c:326-341`: a four-point polygon. */
    private fun rectOutline(sink: DrawSink, x: Int, y: Int, w: Int, h: Int, colour: Int) {
        sink.poly(
            intArrayOf(
                x, y,
                x, y + h - 1,
                x + w - 1, y + h - 1,
                x + w - 1, y,
            ),
            -1,
            colour,
        )
    }

    /** `draw_gts`, `unequal.c:1931-1958`. */
    private fun drawGts(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        f: Int,
        background: Int,
        foreground: Int,
        t: Int,
    ) {
        val g = t / 2
        val g2 = (g + 1) / 2
        val g4 = (g + 1) / 4

        if (f and F_ADJ_UP != 0) {
            if (background >= 0) sink.rect(ox, oy - g, t, g, background)
            drawGt(
                sink,
                ox + g2,
                oy - g4,
                g2,
                -g2,
                g2,
                g2,
                clueColour(f, F_ERROR_UP, F_SPENT_UP, foreground),
            )
            sink.update(ox, oy - g, t, g)
        }
        if (f and F_ADJ_RIGHT != 0) {
            if (background >= 0) sink.rect(ox + t, oy, g, t, background)
            drawGt(
                sink,
                ox + t + g4,
                oy + g2,
                g2,
                g2,
                -g2,
                g2,
                clueColour(f, F_ERROR_RIGHT, F_SPENT_RIGHT, foreground),
            )
            sink.update(ox + t, oy, g, t)
        }
        if (f and F_ADJ_DOWN != 0) {
            if (background >= 0) sink.rect(ox, oy + t, t, g, background)
            drawGt(
                sink,
                ox + g2,
                oy + t + g4,
                g2,
                g2,
                g2,
                -g2,
                clueColour(f, F_ERROR_DOWN, F_SPENT_DOWN, foreground),
            )
            sink.update(ox, oy + t, t, g)
        }
        if (f and F_ADJ_LEFT != 0) {
            if (background >= 0) sink.rect(ox - g, oy, g, t, background)
            drawGt(
                sink,
                ox - g4,
                oy + g2,
                -g2,
                g2,
                g2,
                g2,
                clueColour(f, F_ERROR_LEFT, F_SPENT_LEFT, foreground),
            )
            sink.update(ox - g, oy, g, t)
        }
    }

    /** `draw_adjs`, `unequal.c:1960-1993`: only the right and down bars. */
    private fun drawAdjs(
        sink: DrawSink,
        ox: Int,
        oy: Int,
        f: Int,
        background: Int,
        foreground: Int,
        t: Int,
    ) {
        val g = t / 2
        val g38 = 3 * (g + 1) / 8
        val g4 = (g + 1) / 4

        if (f and (F_ADJ_RIGHT or F_ERROR_RIGHT) != 0) {
            if (f and F_ADJ_RIGHT != 0) {
                sink.rect(
                    ox + t + g38,
                    oy,
                    g4,
                    t,
                    clueColour(f, F_ERROR_RIGHT, F_SPENT_RIGHT, foreground),
                )
            } else {
                rectOutline(sink, ox + t + g38, oy, g4, t, COL_ERROR)
            }
        } else if (background >= 0) {
            sink.rect(ox + t + g38, oy, g4, t, background)
        }
        sink.update(ox + t, oy, g, t)

        if (f and (F_ADJ_DOWN or F_ERROR_DOWN) != 0) {
            if (f and F_ADJ_DOWN != 0) {
                sink.rect(
                    ox,
                    oy + t + g38,
                    t,
                    g4,
                    clueColour(f, F_ERROR_DOWN, F_SPENT_DOWN, foreground),
                )
            } else {
                rectOutline(sink, ox, oy + t + g38, t, g4, COL_ERROR)
            }
        } else if (background >= 0) {
            sink.rect(ox, oy + t + g38, t, g4, background)
        }
        sink.update(ox, oy + t, t, g)
    }

    /** `draw_furniture`, `unequal.c:1995-2033`. */
    private fun drawFurniture(
        sink: DrawSink,
        ds: DrawState,
        state: State,
        ui: Ui,
        x: Int,
        y: Int,
        t: Int,
        square: Int,
        border: Int,
        hflash: Boolean,
    ) {
        val o = state.order
        val ox = x * square + border
        val oy = y * square + border
        val f = state.flags[y * o + x]
        val background = if (hflash) COL_HIGHLIGHT else COL_BACKGROUND
        val hon = ui.hshow && x == ui.hx && y == ui.hy

        sink.rect(
            ox,
            oy,
            t,
            t,
            if (hon && !ui.hpencil) COL_HIGHLIGHT else background,
        )

        if (hon && ui.hpencil) {
            sink.poly(
                intArrayOf(
                    ox, oy,
                    ox + t / 2, oy,
                    ox, oy + t / 2,
                ),
                COL_HIGHLIGHT,
                COL_HIGHLIGHT,
            )
        }

        rectOutline(sink, ox, oy, t, t, COL_GRID)
        sink.update(ox, oy, t, t)

        if (ds.mode == MODE_ADJACENT) {
            drawAdjs(sink, ox, oy, f, COL_BACKGROUND, COL_GRID, t)
        } else {
            drawGts(sink, ox, oy, f, COL_BACKGROUND, COL_TEXT, t)
        }
    }

    /** `draw_num`, `unequal.c:2035-2049`. */
    private fun drawNum(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        t: Int,
        square: Int,
        border: Int,
    ) {
        val o = ds.order
        val ox = x * square + border
        val oy = y * square + border
        val f = ds.flags[y * o + x]
        val colour = when {
            f and F_IMMUTABLE != 0 -> COL_TEXT
            f and F_ERROR != 0 -> COL_ERROR
            else -> COL_GUESS
        }
        sink.text(
            ox + t / 2,
            oy + t / 2,
            Text.FONT_VARIABLE,
            3 * t / 4,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            colour,
            n2c(ds.nums[y * o + x], o).toString(),
        )
    }

    /** `draw_hints`, `unequal.c:2051-2086`. */
    private fun drawHints(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        t: Int,
        square: Int,
        border: Int,
    ) {
        val o = ds.order
        val ox = x * square + border
        val oy = y * square + border

        var nhints = 0
        for (i in 0 until o) {
            if (ds.hints[(x * o + y) * o + i]) nhints++
        }

        var hw = 1
        while (hw * hw < nhints) hw++
        if (hw < 3) hw = 3
        var hh = (nhints + hw - 1) / hw
        if (hh < 2) hh = 2
        val hmax = max(hw, hh)
        val fontsz = t / (hmax * (11 - hmax) / 8)

        var j = 0
        for (i in 0 until o) {
            if (!ds.hints[(x * o + y) * o + i]) continue
            val hx = j % hw
            val hy = j / hw
            sink.text(
                ox + (4 * hx + 3) * t / (4 * hw + 2),
                oy + (4 * hy + 3) * t / (4 * hh + 2),
                Text.FONT_VARIABLE,
                fontsz,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                COL_PENCIL,
                n2c(i + 1, o).toString(),
            )
            j++
        }
    }

    // ------------------------------------------------------------------
    // C helpers.
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
        var any = false
        var value = 0L
        while (index < text.length && text[index].isAsciiDigit()) {
            any = true
            val digit = text[index] - '0'
            value = if (value > (Long.MAX_VALUE - digit) / 10) {
                Long.MAX_VALUE
            } else {
                value * 10 + digit
            }
            index++
        }
        if (!any) return 0
        val signed = sign * value
        return when {
            signed > Int.MAX_VALUE -> Int.MAX_VALUE
            signed < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> signed.toInt()
        }
    }

    /**
     * `sscanf(text, "%d,%d,...")` for [count] fields. Leading
     * whitespace is skipped for each field, a sign is accepted, a
     * literal comma must follow, and trailing junk is ignored. An
     * overflowing number saturates, the defined result for the C
     * undefined behavior.
     */
    private fun sscanfInts(text: String, count: Int): IntArray? {
        var index = 0
        val values = IntArray(count)
        for (field in 0 until count) {
            if (field > 0) {
                if (index >= text.length || text[index] != ',') return null
                index++
            }
            while (index < text.length && isCWhitespace(text[index])) index++
            if (index >= text.length) return null

            var sign = 1L
            if (text[index] == '+' || text[index] == '-') {
                if (text[index] == '-') sign = -1L
                index++
            }

            var any = false
            var value = 0L
            while (index < text.length && text[index].isAsciiDigit()) {
                any = true
                val digit = text[index] - '0'
                value = if (value > (Long.MAX_VALUE - digit) / 10) {
                    Long.MAX_VALUE
                } else {
                    value * 10 + digit
                }
                index++
            }
            if (!any) return null
            val signed = sign * value
            values[field] = when {
                signed > Int.MAX_VALUE -> Int.MAX_VALUE
                signed < Int.MIN_VALUE -> Int.MIN_VALUE
                else -> signed.toInt()
            }
        }
        return values
    }

    /**
     * `n2c`, `unequal.c:439-449`. Only a zero number becomes a space,
     * and the drawing never asks for that. The `?` branch is
     * unreachable for a validated order of at most 32.
     */
    private fun n2c(n: Int, order: Int): Char {
        if (n == 0) return ' '
        if (order < 10) {
            if (n < 10) return ('0'.code + n).toChar()
        } else {
            return if (n < 11) {
                ('0'.code + n - 1).toChar()
            } else {
                val shifted = n - 11
                if (shifted <= 26) ('A'.code + shifted).toChar() else '?'
            }
        }
        return '?'
    }

    /** `c2n`, `unequal.c:453-470`, including the two order regimes. */
    private fun c2n(c: Int, order: Int): Int {
        if (c < 0 || c > 0xff) return -1
        if (c == ' '.code || c == '\b'.code) return 0
        if (order < 10) {
            if (c >= '0'.code && c <= '9'.code) return c - '0'.code
        } else {
            if (c >= '0'.code && c <= '9'.code) return c - '0'.code + 1
            if (c >= 'A'.code && c <= 'Z'.code) return c - 'A'.code + 11
            if (c >= 'a'.code && c <= 'z'.code) return c - 'a'.code + 11
        }
        return -1
    }

    /** `shuffle` from `misc.c`, over [count] elements starting at [start]. */
    private fun shuffleRange(array: IntArray, start: Int, count: Int, random: RandomState) {
        var i = count
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[start + i]
                array[start + i] = array[start + j]
                array[start + j] = tmp
            }
        }
    }
}
