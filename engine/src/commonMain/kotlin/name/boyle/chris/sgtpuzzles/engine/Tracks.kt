package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A port of upstream `tracks.c`.
 *
 * Train Tracks is a loop puzzle: the player lays one path of track from
 * the entrance `A` on the left edge to the exit `B` on the bottom edge.
 * Each row and column must hold the number of track squares printed
 * beside it. The track cannot cross itself and cannot loop.
 *
 * The generator is solver-gated: it lays a random path, adds clues until
 * the requested difficulty can solve the board, then strips redundant
 * clues. Every `DESC` therefore depends on the exact random stream, on
 * the two `shuffle` passes, and on the solver's rule order. Do not
 * reorder the rules or change the `shuffle` calls.
 *
 * There is no `UI` record, no `AUXINFO`, no preferences, no request
 * keys, and no blitter. `solve_game` reads the first state of the
 * history, which the seam does not pass, so the decoded initial board is
 * captured in [Shared] and used for the second solve attempt.
 *
 * D-029: [newDesc] refuses an area above [MAX_AREA] (22x22) before the
 * first draw. 22x22 Hard is 2.7 s on the measured phone; 23x23 Hard is
 * 10 s on the host already.
 */
object Tracks : Game {

    // Difficulty, `tracks.c:38-51`.
    private const val DIFF_EASY = 0
    private const val DIFF_TRICKY = 1
    private const val DIFF_HARD = 2
    private const val DIFFCOUNT = 3
    private val DIFF_NAMES = arrayOf("Easy", "Tricky", "Hard")
    private const val DIFF_CHARS = "eth"

    private const val PREFERRED_TILE_SIZE = 33

    /** D-029: the measured generation cap, 22x22. */
    internal const val MAX_AREA = 484

    // Direction bits and their neighbours, `tracks.c:213-241`.
    private const val R = 1
    private const val U = 2
    private const val L = 4
    private const val D = 8
    private const val ALLDIR = 15

    private const val LR = L or R
    private const val UD = U or D
    private const val LU = L or U
    private const val LD = L or D
    private const val RU = R or U
    private const val RD = R or D

    private val NBITS = intArrayOf(0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4)

    // Square flags, `tracks.c:245-256`.
    private const val S_TRACK = 1
    private const val S_NOTRACK = 2
    private const val S_ERROR = 4
    private const val S_CLUE = 8
    private const val S_MARK = 16
    private const val S_FLASH_SHIFT = 8
    private const val S_FLASH_WIDTH = 8
    private const val S_FLASH_MASK = (1 shl S_FLASH_WIDTH) - 1
    private const val S_TRACK_SHIFT = 16
    private const val S_NOTRACK_SHIFT = 20

    // Edge flags, `tracks.c:258-260`.
    private const val E_TRACK = 1
    private const val E_NOTRACK = 2

    // Palette order, `tracks.c:2519-2526`.
    private const val COL_BACKGROUND = 0
    private const val COL_TRACK_BACKGROUND = 1
    private const val COL_GRID = 2
    private const val COL_CLUE = 3
    private const val COL_CURSOR = 4
    private const val COL_TRACK = 5
    private const val COL_TRACK_CLUE = 6
    private const val COL_SLEEPER = 7
    private const val COL_DRAGON = 8
    private const val COL_DRAGOFF = 9
    private const val COL_ERROR = 10
    private const val COL_FLASH = 11
    private const val COL_ERROR_BACKGROUND = 12
    private const val NCOLOURS = 13

    // Draw-word bits, `tracks.c:2063-2074`.
    private const val DS_ERROR = 1 shl 8
    private const val DS_CLUE = 1 shl 9
    private const val DS_NOTRACK = 1 shl 10
    private const val DS_FLASH = 1 shl 11
    private const val DS_CURSOR = 1 shl 12
    private const val DS_TRACK = 1 shl 13
    private const val DS_CLEARING = 1 shl 14
    private const val DS_NSHIFT = 16
    private const val DS_CSHIFT = 20

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val difficulty: Int,
        val singleOnes: Boolean,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, difficulty, singleOnes)
    }

    /** `struct numbers`, refcounted and shared by `dup_game`. */
    class Numbers(
        val values: IntArray,
        var rowS: Int,
        var colS: Int,
    ) {
        internal fun deepCopy(): Numbers = Numbers(values.copyOf(), rowS, colS)

        override fun equals(other: Any?): Boolean =
            other is Numbers &&
                values.contentEquals(other.values) &&
                rowS == other.rowS &&
                colS == other.colS

        override fun hashCode(): Int =
            31 * (31 * values.contentHashCode() + rowS) + colS
    }

    /**
     * The data shared by every state of one game. [capture] stores the
     * decoded initial board for the second `solve_game` attempt.
     */
    class Shared internal constructor(val params: Params) {
        private var initialSflags: IntArray? = null
        private var initialNumbers: Numbers? = null

        internal fun capture(state: State) {
            initialSflags = state.sflags.copyOf()
            initialNumbers = state.numbers.deepCopy()
        }

        internal fun buildInitial(): State {
            val sflags = checkNotNull(initialSflags) { "tracks: the initial board is not captured" }
            val numbers = checkNotNull(initialNumbers) { "tracks: the initial board is not captured" }
            return State(
                params = params,
                shared = this,
                sflags = sflags.copyOf(),
                numbers = numbers.deepCopy(),
                numErrors = IntArray(params.w + params.h),
                completed = false,
                usedSolve = false,
                impossible = false,
            )
        }
    }

    /** The solver scratch, shared between calls as `solver_scratch` is. */
    private class SolverScratch {
        var dsf: Dsf? = null
    }

    /** `struct game_state`. */
    class State internal constructor(
        val params: Params,
        internal val shared: Shared,
        val sflags: IntArray,
        val numbers: Numbers,
        val numErrors: IntArray,
        var completed: Boolean,
        var usedSolve: Boolean,
        var impossible: Boolean,
    ) : Game.State {

        val width: Int get() = params.w
        val height: Int get() = params.h

        override fun copy(): Game.State = State(
            params,
            shared,
            sflags.copyOf(),
            numbers,
            numErrors.copyOf(),
            completed,
            usedSolve,
            impossible,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                sflags.contentEquals(other.sflags) &&
                numbers == other.numbers &&
                numErrors.contentEquals(other.numErrors) &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                impossible == other.impossible

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + sflags.contentHashCode()
            result = 31 * result + numbers.hashCode()
            result = 31 * result + numErrors.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + impossible.hashCode()
            return result
        }
    }

    /** One solver run: the C return value and the `max_diff_out` value. */
    private data class SolveResult(val ret: Int, val maxDiff: Int)

    /** `solve_game`'s two outputs: the move and the C error string. */
    data class SolveAttempt(val move: String?, val error: String?)

    /** Internal retry diagnostics. They change no description. */
    internal data class GenerationStats(
        var attempts: Int = 0,
        var layPathRestarts: Int = 0,
        var boring: Int = 0,
        var singleOne: Int = 0,
        var addCluesRejected: Int = 0,
        var tooEasy: Int = 0,
        var alreadySolvable: Int = 0,
    )

    internal data class Generated(val description: Description, val stats: GenerationStats)

    /** `struct game_ui`, `tracks.c:2008-2015`. */
    class Ui internal constructor() : Game.Ui {
        internal var dragging: Boolean = false
        internal var clearing: Boolean = false
        internal var notrack: Boolean = false
        internal var dragSx: Int = -1
        internal var dragSy: Int = -1
        internal var dragEx: Int = -1
        internal var dragEy: Int = -1

        /**
         * The press position. C never initializes it, and an off-grid
         * press leaves it untouched (`tracks.c:2269-2273`) while the
         * release path reads it (`tracks.c:2324-2325`). The port defines
         * both as 0; every reachable release after an off-grid press
         * then fails the `FROMCOORD` test and is inert.
         */
        internal var clickx: Int = 0
        internal var clicky: Int = 0

        internal var curx: Int = 1
        internal var cury: Int = 1
        internal var cursorActive: Boolean = false
    }

    /** `struct game_drawstate`, `tracks.c:2076-2083`. */
    class DrawState internal constructor(
        val sz6: Int,
        val border: Int,
        val gridLineAll: Int,
        val gridLineTl: Int,
        val gridLineBr: Int,
        val w: Int,
        val h: Int,
        val flags: IntArray,
        val flagsDrag: IntArray,
        val numErrors: IntArray,
        var started: Boolean,
    ) : Game.DrawState

    private val PRESETS = listOf(
        Params(8, 8, DIFF_EASY, true),
        Params(8, 8, DIFF_TRICKY, true),
        Params(10, 8, DIFF_EASY, true),
        Params(10, 8, DIFF_TRICKY, true),
        Params(10, 10, DIFF_EASY, true),
        Params(10, 10, DIFF_TRICKY, true),
        Params(10, 10, DIFF_HARD, true),
        Params(15, 10, DIFF_EASY, true),
        Params(15, 10, DIFF_TRICKY, true),
        Params(15, 15, DIFF_EASY, true),
        Params(15, 15, DIFF_TRICKY, true),
        Params(15, 15, DIFF_HARD, true),
    )

    override val name: String = "Train Tracks"
    override val backendName: String = "tracks"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /**
     * The C game has a text form (`can_format_as_text_ever` is true),
     * and [textFormat] is the exact C text form. The text ends with a
     * newline after the final `B` line, as `game_text_format` does.
     */
    override val canFormatAsText: Boolean = true

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = Params(8, 8, DIFF_TRICKY, true)

    override fun presets(): List<Preset> = PRESETS.map { params ->
        Preset(
            "${params.w}x${params.h} ${DIFF_NAMES[params.difficulty]}",
            encodeParams(params, true),
        )
    }

    override fun decodeParams(text: String): Game.Params {
        var params = defaultParams() as Params
        var index = 0

        fun eatNum(): Int {
            val value = cAtoi(text, index)
            while (index < text.length && text[index].isAsciiDigit()) index++
            return value
        }

        params = params.copy(w = eatNum())
        params = params.copy(h = params.w)
        if (index < text.length && text[index] == 'x') {
            index++
            params = params.copy(h = eatNum())
        }
        if (index < text.length && text[index] == 'd') {
            index++
            params = params.copy(difficulty = DIFF_TRICKY)
            val c = if (index < text.length) text[index] else '\u0000'
            for (i in 0 until DIFFCOUNT) {
                if (DIFF_CHARS[i] == c) params = params.copy(difficulty = i)
            }
            // C consumes exactly one character, including a NUL.
            if (index < text.length) index++
        }
        params = params.copy(singleOnes = true)
        if (index < text.length && text[index] == 'o') {
            params = params.copy(singleOnes = false)
            index++
        }
        return params
    }

    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val base = "${p.w}x${p.h}"
        if (!full) return base
        return base + "d" + DIFF_CHARS[p.difficulty] + if (p.singleOnes) "" else "o"
    }

    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 4 || p.h < 4) {
            return "Width and height must both be at least four"
        }
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        return null
    }

    // ------------------------------------------------------------------
    // Shared flag arithmetic.
    // ------------------------------------------------------------------

    private fun dx(d: Int): Int =
        (if (d == R) 1 else 0) - (if (d == L) 1 else 0)

    private fun dy(d: Int): Int =
        (if (d == D) 1 else 0) - (if (d == U) 1 else 0)

    /** `F(d)`: the opposite direction. */
    private fun opposite(d: Int): Int = ((d shl 2) or (d ushr 2)) and 0xF

    private fun moveChar(d: Int): Char = when (d) {
        R -> 'R'
        U -> 'U'
        L -> 'L'
        D -> 'D'
        else -> '?'
    }

    private fun inGrid(state: State, x: Int, y: Int): Boolean =
        x >= 0 && x < state.params.w && y >= 0 && y < state.params.h

    /** `S_E_DIRS`: the four directions of an edge flag around a square. */
    private fun sEDirs(state: State, x: Int, y: Int, eflag: Int): Int {
        val shift = if (eflag == E_TRACK) S_TRACK_SHIFT else S_NOTRACK_SHIFT
        return (state.sflags[y * state.params.w + x] ushr shift) and ALLDIR
    }

    /** `S_E_COUNT`: the number of directions of an edge flag. */
    private fun sECount(state: State, x: Int, y: Int, eflag: Int): Int =
        NBITS[sEDirs(state, x, y, eflag)]

    /** `S_E_FLAGS`: the track and no-track flags on one edge. */
    private fun sEFlags(state: State, x: Int, y: Int, d: Int): Int {
        val f = state.sflags[y * state.params.w + x]
        val t = f and (d shl S_TRACK_SHIFT)
        val nt = f and (d shl S_NOTRACK_SHIFT)
        return (if (t != 0) E_TRACK else 0) or (if (nt != 0) E_NOTRACK else 0)
    }

    /** `S_E_ADJ`: the adjacent square and its opposite direction. */
    private fun sEAdj(state: State, x: Int, y: Int, d: Int): IntArray? = when {
        d == L && x > 0 -> intArrayOf(x - 1, y, R)
        d == R && x < state.params.w - 1 -> intArrayOf(x + 1, y, L)
        d == U && y > 0 -> intArrayOf(x, y - 1, D)
        d == D && y < state.params.h - 1 -> intArrayOf(x, y + 1, U)
        else -> null
    }

    /** `S_E_SET`: sets an edge flag on both squares of an edge. */
    private fun sESet(state: State, x: Int, y: Int, d: Int, eflag: Int) {
        val shift = if (eflag == E_TRACK) S_TRACK_SHIFT else S_NOTRACK_SHIFT
        state.sflags[y * state.params.w + x] =
            state.sflags[y * state.params.w + x] or (d shl shift)
        val adj = sEAdj(state, x, y, d)
        if (adj != null) {
            val index = adj[1] * state.params.w + adj[0]
            state.sflags[index] = state.sflags[index] or (adj[2] shl shift)
        }
    }

    /** `S_E_CLEAR`: clears an edge flag on both squares of an edge. */
    private fun sEClear(state: State, x: Int, y: Int, d: Int, eflag: Int) {
        val shift = if (eflag == E_TRACK) S_TRACK_SHIFT else S_NOTRACK_SHIFT
        state.sflags[y * state.params.w + x] =
            state.sflags[y * state.params.w + x] and (d shl shift).inv()
        val adj = sEAdj(state, x, y, d)
        if (adj != null) {
            val index = adj[1] * state.params.w + adj[0]
            state.sflags[index] = state.sflags[index] and (adj[2] shl shift).inv()
        }
    }

    // ------------------------------------------------------------------
    // State construction and the description.
    // ------------------------------------------------------------------

    private fun blankGame(params: Params): State {
        val w = params.w
        val h = params.h
        return State(
            params = params,
            shared = Shared(params),
            sflags = IntArray(w * h),
            numbers = Numbers(IntArray(w + h), -1, -1),
            numErrors = IntArray(w + h),
            completed = false,
            usedSolve = false,
            impossible = false,
        )
    }

    /** `clear_game`. */
    private fun clearGame(state: State) {
        state.sflags.fill(0)
        state.numbers.values.fill(0)
        state.numbers.colS = -1
        state.numbers.rowS = -1
        state.numErrors.fill(0)
        state.completed = false
        state.usedSolve = false
        state.impossible = false
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val h = p.h
        val wh = w * h
        val state = State(
            params = p,
            shared = Shared(p),
            sflags = IntArray(wh),
            numbers = Numbers(IntArray(w + h), -1, -1),
            numErrors = IntArray(w + h),
            completed = false,
            usedSolve = false,
            impossible = false,
        )

        var i = 0
        var index = 0
        while (index < desc.length) {
            var f = 0
            val c = desc[index]
            when {
                c in '0'..'9' -> f = c - '0'
                c in 'A'..'F' -> f = c - 'A' + 10
                c in 'a'..'z' -> i += c - 'a'
            }
            if (f != 0) {
                require(i in 0 until wh) { "tracks: the description runs past the grid" }
                val x = i % w
                val y = i / w
                require(f < 16 && NBITS[f] == 2) {
                    "tracks: a clue does not provide 2 direction flags"
                }
                state.sflags[i] = state.sflags[i] or (S_TRACK or S_CLUE)
                if (f and U != 0) sESet(state, x, y, U, E_TRACK)
                if (f and D != 0) sESet(state, x, y, D, E_TRACK)
                if (f and L != 0) sESet(state, x, y, L, E_TRACK)
                if (f and R != 0) sESet(state, x, y, R, E_TRACK)
            }
            i++
            index++
            if (i == wh) break
        }
        require(i == wh) { "tracks: the description is shorter than the grid" }

        for (k in 0 until w + h) {
            require(index < desc.length && desc[index] == ',') {
                "tracks: the description has no number list"
            }
            index++
            if (index < desc.length && desc[index] == 'S') {
                if (k < w) state.numbers.colS = k else state.numbers.rowS = k - w
                index++
            }
            val parsed = cAtoiAt(desc, index)
            state.numbers.values[k] = parsed.value
            index = parsed.end
        }
        require(index == desc.length) { "tracks: the description ends with extra characters" }
        require(state.numbers.colS >= 0 && state.numbers.rowS >= 0) {
            "tracks: the description has no entrance or exit"
        }

        state.shared.capture(state)
        return state
    }

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val w = p.w
        val h = p.h
        val wh = w * h
        var i = 0
        var index = 0
        var inCount = 0
        var outCount = 0

        while (index < desc.length) {
            var f = 0
            val c = desc[index]
            when {
                c in '0'..'9' -> f = c - '0'
                c in 'A'..'F' -> f = c - 'A' + 10
                c in 'a'..'z' -> i += c - 'a'
                else -> return "Game description contained unexpected characters"
            }
            if (f != 0 && NBITS[f] != 2) {
                return "Clue did not provide 2 direction flags"
            }
            i++
            index++
            if (i == wh) break
        }

        for (k in 0 until w + h) {
            if (index >= desc.length) {
                return "Not enough numbers given after grid specification"
            }
            if (desc[index] != ',') return "Invalid character in number list"
            index++
            if (index < desc.length && desc[index] == 'S') {
                if (k < w) outCount++ else inCount++
                index++
            }
            while (index < desc.length && desc[index].isAsciiDigit()) index++
        }

        if (inCount != 1 || outCount != 1) {
            return "Puzzle must have one entrance and one exit"
        }
        if (index < desc.length) {
            return "Unexpected additional character at end of game description"
        }
        return null
    }

    /** `game_configure` (`tracks.c:152-182`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Tricky:Hard",
                null,
                p.difficulty,
            ),
            ConfigItem.Bool("Disallow consecutive 1 clues", p.singleOnes),
        )
    }

    /** `custom_params` (`tracks.c:184-194`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            cAtoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
            (items[3] as ConfigItem.Bool).value,
        )

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDescWithStats(params, random).description

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description = newDescWithStats(params, random, cancel).description

    internal fun newDescWithStats(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        val requested = params as Params
        require(requested.w.toLong() * requested.h <= MAX_AREA) {
            Game.AREA_TOO_LARGE
        }
        val stats = GenerationStats()

        // `tracks.c:735-739`: 4x4 Tricky cannot be generated.
        val p = if (requested.w == 4 && requested.h == 4 &&
            requested.difficulty > DIFF_EASY
        ) {
            requested.copy(difficulty = DIFF_EASY) as Params
        } else {
            requested
        }

        val w = p.w
        val h = p.h
        val wh = w * h
        val state = blankGame(p)

        while (true) {
            cancel.check()
            stats.attempts++
            layPath(state, random, stats)

            for (x in 0 until w) {
                for (y in 0 until h) {
                    if (sECount(state, x, y, E_TRACK) > 0) {
                        state.sflags[y * w + x] = state.sflags[y * w + x] or S_TRACK
                    }
                    if ((x == 0 && y == state.numbers.rowS) ||
                        (y == h - 1 && x == state.numbers.colS)
                    ) {
                        state.sflags[y * w + x] = state.sflags[y * w + x] or S_CLUE
                    }
                }
            }

            for (x in 0 until w) {
                for (y in 0 until h) {
                    if (state.sflags[y * w + x] and S_TRACK != 0) {
                        state.numbers.values[x]++
                        state.numbers.values[y + w]++
                    }
                }
            }

            var retry = false
            for (k in 0 until w + h) {
                if (state.numbers.values[k] == 0) {
                    stats.boring++
                    retry = true
                    break
                }
            }
            if (retry) continue

            if (p.singleOnes) {
                var lastWasOne = true
                for (k in 0 until w + h) {
                    val isOne = state.numbers.values[k] == 1
                    if (isOne && lastWasOne) {
                        stats.singleOne++
                        retry = true
                        break
                    }
                    lastWasOne = isOne
                }
                if (!retry && state.numbers.values[w + h - 1] == 1) {
                    stats.singleOne++
                    retry = true
                }
            }
            if (retry) continue

            val added = addClues(state, random, p.difficulty, stats)
            if (added != 1) {
                stats.addCluesRejected++
                continue
            }

            val out = StringBuilder(wh + (w + h) * 5)
            var j = 0
            val desc = CharArray(wh + 1)
            for (k in 0 until wh) {
                if (state.sflags[k] and S_CLUE == 0) {
                    if (j > 0 && desc[j - 1] >= 'a' && desc[j - 1] < 'z') {
                        desc[j - 1]++
                    } else {
                        desc[j++] = 'a'
                    }
                } else {
                    val f = sEDirs(state, k % w, k / w, E_TRACK)
                    desc[j++] = if (f < 10) '0' + f else 'A' + (f - 10)
                }
            }
            out.append(desc, 0, j)
            for (x in 0 until w) {
                out.append(',')
                if (x == state.numbers.colS) out.append('S')
                out.append(state.numbers.values[x].toString())
            }
            for (y in 0 until h) {
                out.append(',')
                if (y == state.numbers.rowS) out.append('S')
                out.append(state.numbers.values[y + w].toString())
            }

            val solved = tracksSolve(state, DIFFCOUNT)
            check(solved.ret >= 0) { "tracks: the generator created an impossible puzzle" }
            return Generated(Description(out.toString(), null), stats)
        }
    }

    /** `lay_path`, including the internal restart on a dead end. */
    private fun layPath(state: State, random: RandomState, stats: GenerationStats) {
        val w = state.params.w
        val h = state.params.h
        while (true) {
            clearGame(state)

            val py0 = random.upto(h.toLong()).toInt()
            state.numbers.rowS = py0
            var px = 0
            var py = py0
            sESet(state, px, py, L, E_TRACK)

            var restart = false
            while (inGrid(state, px, py)) {
                val d = findDirection(state, random, px, py)
                if (d == 0) {
                    stats.layPathRestarts++
                    restart = true
                    break
                }
                sESet(state, px, py, d, E_TRACK)
                px += dx(d)
                py += dy(d)
            }
            if (restart) continue

            check(px >= 0 && px < w && py == h) {
                "tracks: the path left the board in the wrong place"
            }
            state.numbers.colS = px
            return
        }
    }

    /** `find_direction`. */
    private fun findDirection(
        state: State,
        random: RandomState,
        x: Int,
        y: Int,
    ): Int {
        val w = state.params.w
        val h = state.params.h
        val dirs = intArrayOf(U, D, L, R)
        shuffle(dirs, dirs.size, random)
        for (d in dirs) {
            val nx = x + dx(d)
            val ny = y + dy(d)
            if (nx >= 0 && nx < w && ny == h) {
                return d
            } else if (!inGrid(state, nx, ny)) {
                continue
            } else if (sECount(state, nx, ny, E_TRACK) > 0) {
                continue
            }
            return d
        }
        return 0
    }

    /**
     * `add_clues`: the solver gate. Returns 1 when the board is now
     * soluble at [diff], -1 when a new path is needed.
     */
    private fun addClues(
        state: State,
        random: RandomState,
        diff: Int,
        stats: GenerationStats,
    ): Int {
        val w = state.params.w
        val h = state.params.h
        val wh = w * h
        val positions = IntArray(wh)
        var npositions = 0
        val nedgesPreviousSolve = IntArray(wh)
        val scratch = state.copy() as State

        for (i in 0 until wh) {
            if (sEDirs(state, i % w, i / w, E_TRACK) != 0) {
                positions[npositions++] = i
            }
            nedgesPreviousSolve[i] = 0
        }

        copyAndStrip(state, scratch, -1)
        var solved = tracksSolve(scratch, diff)
        if (solved.maxDiff < diff) {
            stats.tooEasy++
            return -1
        }
        check(solved.ret >= 0) { "tracks: the generator created an impossible puzzle" }
        if (solved.ret > 0) {
            stats.alreadySolvable++
            return 1
        }

        var progress = solveProgress(scratch)

        shuffle(positions, npositions, random)
        for (pi in 0 until npositions) {
            val i = positions[pi]
            if (state.sflags[i] and S_CLUE != 0) continue
            if (nedgesPreviousSolve[i] == 2) continue

            copyAndStrip(state, scratch, i)
            if (checkPhantomMoves(scratch)) continue

            solved = tracksSolve(scratch, diff)
            if (solved.ret > 0) {
                if (solved.maxDiff < diff) continue
                state.sflags[i] = state.sflags[i] or S_CLUE
                return stripClues(state, random, positions, npositions, scratch, diff)
            }
            val newProgress = solveProgress(scratch)
            if (newProgress > progress) {
                progress = newProgress
                state.sflags[i] = state.sflags[i] or S_CLUE
                for (j in 0 until wh) {
                    nedgesPreviousSolve[j] = sECount(scratch, j % w, j / w, E_TRACK)
                }
            }
        }
        return -1
    }

    private fun stripClues(
        state: State,
        random: RandomState,
        positions: IntArray,
        npositions: Int,
        scratch: State,
        diff: Int,
    ): Int {
        val w = state.params.w
        val h = state.params.h
        shuffle(positions, npositions, random)
        for (pi in 0 until npositions) {
            val i = positions[pi]
            if (state.sflags[i] and S_CLUE == 0) continue
            if ((i % w == 0 && i / w == state.numbers.rowS) ||
                (i / w == h - 1 && i % w == state.numbers.colS)
            ) {
                continue
            }
            copyAndStrip(state, scratch, i)
            if (checkPhantomMoves(scratch)) continue
            if (tracksSolve(scratch, diff).ret > 0) {
                state.sflags[i] = state.sflags[i] and S_CLUE.inv()
            }
        }
        return 1
    }

    /** `copy_and_strip`, including the clue XOR before the strip. */
    private fun copyAndStrip(state: State, ret: State, flipClue: Int) {
        val w = state.params.w
        val h = state.params.h
        state.sflags.copyInto(ret.sflags)
        if (flipClue != -1) {
            ret.sflags[flipClue] = ret.sflags[flipClue] xor S_CLUE
        }
        for (i in 0 until w * h) {
            if (ret.sflags[i] and S_CLUE != 0) continue
            ret.sflags[i] = ret.sflags[i] and
                (S_TRACK or S_NOTRACK or S_ERROR or S_MARK).inv()
            for (j in 0 until 4) {
                val f = 1 shl j
                val xx = i % w + dx(f)
                val yy = i / w + dy(f)
                if (!inGrid(state, xx, yy) ||
                    ret.sflags[yy * w + xx] and S_CLUE == 0
                ) {
                    sEClear(ret, i % w, i / w, f, E_TRACK)
                    sEClear(ret, i % w, i / w, f, E_NOTRACK)
                }
            }
        }
    }

    /** `solve_progress`: how many flags the solver managed to set. */
    private fun solveProgress(state: State): Int {
        val w = state.params.w
        var progress = 0
        for (i in 0 until w * state.params.h) {
            if (state.sflags[i] and S_TRACK != 0) progress++
            if (state.sflags[i] and S_NOTRACK != 0) progress++
            progress += sECount(state, i % w, i / w, E_TRACK)
            progress += sECount(state, i % w, i / w, E_NOTRACK)
        }
        return progress
    }

    /** `check_phantom_moves`: a non-clue square with more than one edge. */
    private fun checkPhantomMoves(state: State): Boolean {
        val w = state.params.w
        for (x in 0 until w) {
            for (y in 0 until state.params.h) {
                val i = y * w + x
                if (state.sflags[i] and S_CLUE != 0) continue
                if (sECount(state, x, y, E_TRACK) > 1) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    private fun solveSetSFlag(
        state: State,
        x: Int,
        y: Int,
        f: Int,
    ): Int {
        val w = state.params.w
        val i = y * w + x
        if (state.sflags[i] and f != 0) return 0
        if (state.sflags[i] and (if (f == S_TRACK) S_NOTRACK else S_TRACK) != 0) {
            state.impossible = true
        } else {
            state.sflags[i] = state.sflags[i] or f
        }
        return 1
    }

    private fun solveSetEFlag(
        state: State,
        x: Int,
        y: Int,
        d: Int,
        f: Int,
    ): Int {
        val sf = sEFlags(state, x, y, d)
        if (sf and f != 0) return 0
        if (sf and (if (f == E_TRACK) E_NOTRACK else E_TRACK) != 0) {
            state.impossible = true
        } else {
            sESet(state, x, y, d, f)
        }
        return 1
    }

    /** `solve_update_flags`: five local implications. */
    private fun solveUpdateFlags(state: State): Int {
        val w = state.params.w
        val h = state.params.h
        var did = 0
        for (x in 0 until w) {
            for (y in 0 until h) {
                if (state.sflags[y * w + x] and S_NOTRACK != 0) {
                    for (i in 0 until 4) {
                        did += solveSetEFlag(state, x, y, 1 shl i, E_NOTRACK)
                    }
                }
                if (sECount(state, x, y, E_NOTRACK) >= 3) {
                    did += solveSetSFlag(state, x, y, S_NOTRACK)
                }
                if (sECount(state, x, y, E_TRACK) > 0) {
                    did += solveSetSFlag(state, x, y, S_TRACK)
                }
                if (state.sflags[y * w + x] and S_TRACK != 0 &&
                    sECount(state, x, y, E_NOTRACK) == 2 &&
                    sECount(state, x, y, E_TRACK) < 2
                ) {
                    for (i in 0 until 4) {
                        val d = 1 shl i
                        if (sEFlags(state, x, y, d) and (E_TRACK or E_NOTRACK) == 0) {
                            did += solveSetEFlag(state, x, y, d, E_TRACK)
                        }
                    }
                }
                if (state.sflags[y * w + x] and S_TRACK != 0 &&
                    sECount(state, x, y, E_TRACK) == 2 &&
                    sECount(state, x, y, E_NOTRACK) < 2
                ) {
                    for (i in 0 until 4) {
                        val d = 1 shl i
                        if (sEFlags(state, x, y, d) and (E_TRACK or E_NOTRACK) == 0) {
                            did += solveSetEFlag(state, x, y, d, E_NOTRACK)
                        }
                    }
                }
            }
        }
        return did
    }

    private fun solveCountCol(state: State, col: Int, f: Int): Int {
        val w = state.params.w
        val h = state.params.h
        var count = 0
        var i = col
        for (n in 0 until h) {
            if (state.sflags[i] and f != 0) count++
            i += w
        }
        return count
    }

    private fun solveCountRow(state: State, row: Int, f: Int): Int {
        val w = state.params.w
        var count = 0
        var i = w * row
        for (n in 0 until w) {
            if (state.sflags[i] and f != 0) count++
            i++
        }
        return count
    }

    private fun solveCountCluesSub(
        state: State,
        si: Int,
        id: Int,
        n: Int,
        target: Int,
    ): Int {
        val w = state.params.w
        var ctrack = 0
        var cnotrack = 0
        var did = 0
        var i = si
        for (j in 0 until n) {
            if (state.sflags[i] and S_TRACK != 0) ctrack++
            if (state.sflags[i] and S_NOTRACK != 0) cnotrack++
            i += id
        }
        if (ctrack == target) {
            i = si
            for (j in 0 until n) {
                if (state.sflags[i] and S_TRACK == 0) {
                    did += solveSetSFlag(state, i % w, i / w, S_NOTRACK)
                }
                i += id
            }
        }
        if (cnotrack == n - target) {
            i = si
            for (j in 0 until n) {
                if (state.sflags[i] and S_NOTRACK == 0) {
                    did += solveSetSFlag(state, i % w, i / w, S_TRACK)
                }
                i += id
            }
        }
        return did
    }

    private fun solveCountClues(state: State): Int {
        val w = state.params.w
        val h = state.params.h
        var did = 0
        for (x in 0 until w) {
            did += solveCountCluesSub(state, x, w, h, state.numbers.values[x])
        }
        for (y in 0 until h) {
            did += solveCountCluesSub(state, y * w, 1, w, state.numbers.values[w + y])
        }
        return did
    }

    private fun solveCheckSingleSub(
        state: State,
        si: Int,
        id: Int,
        n: Int,
        target: Int,
        perpf: Int,
    ): Int {
        val w = state.params.w
        var ctrack = 0
        var nperp = 0
        var did = 0
        var n1edge = 0
        var i1edge = 0
        var i = si
        for (j in 0 until n) {
            if (state.sflags[i] and S_TRACK != 0) ctrack++
            val impossible = sEDirs(state, i % w, i / w, E_NOTRACK)
            if (perpf and impossible == 0) nperp++
            if (sECount(state, i % w, i / w, E_TRACK) <= 1) {
                n1edge++
                i1edge = i
            }
            i += id
        }
        if (ctrack != target - 1) return 0
        if (nperp > 0 || n1edge != 1) return 0

        val ox = i1edge % w
        val oy = i1edge / w
        i = si
        for (j in 0 until n) {
            val x = i % w
            val y = i / w
            if (abs(ox - x) > 1 || abs(oy - y) > 1) {
                if (state.sflags[i] and S_TRACK == 0) {
                    did += solveSetSFlag(state, x, y, S_NOTRACK)
                }
            }
            i += id
        }
        return did
    }

    private fun solveCheckSingle(state: State): Int {
        val w = state.params.w
        val h = state.params.h
        var did = 0
        for (x in 0 until w) {
            did += solveCheckSingleSub(state, x, w, h, state.numbers.values[x], R or L)
        }
        for (y in 0 until h) {
            did += solveCheckSingleSub(state, y * w, 1, w, state.numbers.values[w + y], U or D)
        }
        return did
    }

    private fun solveCheckLooseSub(
        state: State,
        si: Int,
        id: Int,
        n: Int,
        target: Int,
        perpf: Int,
    ): Int {
        val w = state.params.w
        val parf = ALLDIR and perpf.inv()
        var nperp = 0
        var nloose = 0
        var e2count = 0
        var did = 0
        var i = si
        for (j in 0 until n) {
            val fcount = sECount(state, i % w, i / w, E_TRACK)
            if (fcount == 2) e2count++
            state.sflags[i] = state.sflags[i] and S_MARK.inv()
            if (fcount == 1 && parf and sEDirs(state, i % w, i / w, E_TRACK) != 0) {
                nloose++
                state.sflags[i] = state.sflags[i] or S_MARK
            }
            if (fcount != 2 &&
                perpf and sEDirs(state, i % w, i / w, E_NOTRACK) == 0
            ) {
                nperp++
            }
            i += id
        }

        if (nloose > target - e2count) {
            state.impossible = true
        }
        if (nloose > 0 && nloose == target - e2count) {
            i = si
            for (j in 0 until n) {
                if (state.sflags[i] and S_MARK == 0) {
                    i += id
                    continue
                }
                if (j > 0 && state.sflags[i - id] and S_MARK != 0) {
                    i += id
                    continue
                }
                if (j < n - 1 && state.sflags[i + id] and S_MARK != 0) {
                    i += id
                    continue
                }
                for (k in 0 until 4) {
                    if (parf and (1 shl k) != 0 &&
                        sEDirs(state, i % w, i / w, E_TRACK) and (1 shl k) == 0
                    ) {
                        did += solveSetEFlag(state, i % w, i / w, 1 shl k, E_NOTRACK)
                    }
                }
                i += id
            }
        }
        if (nloose == 1 && target - e2count == 2 && nperp == 0) {
            i = si
            for (j in 0 until n) {
                if (state.sflags[i] and S_MARK == 0) {
                    i += id
                    continue
                }
                for (k in 0 until 4) {
                    if (parf and (1 shl k) != 0) {
                        did += solveSetEFlag(state, i % w, i / w, 1 shl k, E_TRACK)
                    }
                }
                i += id
            }
        }
        return did
    }

    private fun solveCheckLooseEnds(state: State): Int {
        val w = state.params.w
        val h = state.params.h
        var did = 0
        for (x in 0 until w) {
            did += solveCheckLooseSub(state, x, w, h, state.numbers.values[x], R or L)
        }
        for (y in 0 until h) {
            did += solveCheckLooseSub(state, y * w, 1, w, state.numbers.values[w + y], U or D)
        }
        return did
    }

    private fun solveCheckNeighboursCount(
        state: State,
        start: Int,
        step: Int,
        n: Int,
        clueIndex: Int,
    ): Pair<Boolean, Boolean> {
        var toFill = state.numbers.values[clueIndex]
        var toEmpty = n - toFill
        for (i in 0 until n) {
            val p = start + i * step
            if (state.sflags[p] and S_TRACK != 0) toFill--
            if (state.sflags[p] and S_NOTRACK != 0) toEmpty--
        }
        return (toFill == 1) to (toEmpty == 1)
    }

    private fun solveCheckNeighboursTry(
        state: State,
        x: Int,
        y: Int,
        xx: Int,
        yy: Int,
        onefill: Boolean,
        oneempty: Boolean,
        dir: Int,
    ): Int {
        val w = state.params.w
        val p = y * w + x
        val q = yy * w + xx
        if ((state.sflags[p] or state.sflags[q]) and (S_TRACK or S_NOTRACK) != 0) {
            return 0
        }
        val possibleExitsExceptDir = NBITS[
            ALLDIR and dir.inv() and sEDirs(state, x, y, E_NOTRACK).inv(),
        ]
        if (possibleExitsExceptDir >= 2) return 0

        var did = 0
        if (onefill) {
            state.sflags[p] = state.sflags[p] or S_NOTRACK
            did++
        }
        if (oneempty) {
            state.sflags[q] = state.sflags[q] or S_TRACK
            did++
        }
        return did
    }

    private fun solveCheckNeighbours(state: State, bothWays: Boolean): Int {
        val w = state.params.w
        val h = state.params.h
        var did = 0
        for (x in 0 until w) {
            var (onefill, oneempty) = solveCheckNeighboursCount(state, x, w, h, x)
            if (!bothWays) oneempty = false
            if (!onefill && !oneempty) continue
            for (y in 0 until h - 1) {
                did += solveCheckNeighboursTry(state, x, y, x, y + 1, onefill, oneempty, D)
                did += solveCheckNeighboursTry(state, x, y + 1, x, y, onefill, oneempty, U)
            }
        }
        for (y in 0 until h) {
            var (onefill, oneempty) =
                solveCheckNeighboursCount(state, y * w, 1, w, w + y)
            if (!bothWays) oneempty = false
            if (!onefill && !oneempty) continue
            for (x in 0 until w - 1) {
                did += solveCheckNeighboursTry(state, x, y, x + 1, y, onefill, oneempty, R)
                did += solveCheckNeighboursTry(state, x + 1, y, x, y, onefill, oneempty, L)
            }
        }
        return did
    }

    private fun solveCheckLoopSub(
        state: State,
        x: Int,
        y: Int,
        dir: Int,
        dsf: Dsf,
        startc: Int,
        endc: Int,
    ): Int {
        val w = state.params.w
        val i = y * w + x
        val j = (y + dy(dir)) * w + (x + dx(dir))
        var satisfied = true

        if (state.sflags[i] and S_TRACK != 0 &&
            state.sflags[j] and S_TRACK != 0 &&
            sEDirs(state, x, y, E_TRACK) and dir == 0 &&
            sEDirs(state, x, y, E_NOTRACK) and dir == 0
        ) {
            val ic = dsf.canonify(i)
            val jc = dsf.canonify(j)
            if (ic == jc) {
                return solveSetEFlag(state, x, y, dir, E_NOTRACK)
            }
            if ((ic == startc && jc == endc) || (ic == endc && jc == startc)) {
                for (k in 0 until w * state.params.h) {
                    if (state.sflags[k] and S_TRACK != 0 &&
                        dsf.canonify(k) != startc && dsf.canonify(k) != endc
                    ) {
                        return solveSetEFlag(state, x, y, dir, E_NOTRACK)
                    }
                }
                for (k in 0 until w) {
                    if (solveCountCol(state, k, S_TRACK) < state.numbers.values[k]) {
                        satisfied = false
                    }
                }
                for (k in 0 until state.params.h) {
                    if (solveCountRow(state, k, S_TRACK) <
                        state.numbers.values[w + k]
                    ) {
                        satisfied = false
                    }
                }
                if (!satisfied) {
                    return solveSetEFlag(state, x, y, dir, E_NOTRACK)
                }
            }
        }
        return 0
    }

    /** `solve_check_loop`. */
    private fun solveCheckLoop(state: State): Int {
        val w = state.params.w
        val h = state.params.h
        var did = 0
        val dsf = Dsf.new(w * h)

        for (x in 0 until w) {
            for (y in 0 until h) {
                val i = y * w + x
                if (x < w - 1 && sEDirs(state, x, y, E_TRACK) and R != 0) {
                    dsf.merge(i, y * w + (x + 1))
                }
                if (y < h - 1 && sEDirs(state, x, y, E_TRACK) and D != 0) {
                    dsf.merge(i, (y + 1) * w + x)
                }
            }
        }

        val startc = dsf.canonify(state.numbers.rowS * w)
        val endc = dsf.canonify((h - 1) * w + state.numbers.colS)

        for (x in 0 until w) {
            for (y in 0 until h) {
                if (x < w - 1) did += solveCheckLoopSub(state, x, y, R, dsf, startc, endc)
                if (y < h - 1) did += solveCheckLoopSub(state, x, y, D, dsf, startc, endc)
            }
        }
        return did
    }

    /**
     * `solve_discount_edge`. The C code asserts that an outer track
     * edge belongs to a clue square. A hand-written `S` move can set
     * one, and release builds of C then accept it and return, so the
     * port returns without marking the edge and without the assert.
     */
    private fun solveDiscountEdge(state: State, x: Int, y: Int, d: Int) {
        if (sEDirs(state, x, y, E_TRACK) and d != 0) return
        solveSetEFlag(state, x, y, d, E_NOTRACK)
    }

    private fun solveBridgeSub(
        state: State,
        x: Int,
        y: Int,
        d: Int,
        scratch: SolverScratch,
    ): Int {
        val w = state.params.w
        val h = state.params.h
        val wh = w * h
        val xx = x + dx(d)
        val yy = y + dy(d)
        check(d == D || d == R) { "tracks: a bridge is not down or right" }

        var dsf = scratch.dsf
        if (dsf == null) {
            dsf = Dsf.new(wh)
            scratch.dsf = dsf
        }
        dsf.reinit()

        for (xi in 0 until w) {
            for (yi in 0 until h) {
                if (yi + 1 < h && sEFlags(state, xi, yi, D) == 0 &&
                    !(xi == x && yi == y && xi == xx && yi + 1 == yy)
                ) {
                    dsf.merge(yi * w + xi, (yi + 1) * w + xi)
                }
                if (xi + 1 < w && sEFlags(state, xi, yi, R) == 0 &&
                    !(xi == x && yi == y && xi + 1 == xx && yi == yy)
                ) {
                    dsf.merge(yi * w + xi, yi * w + (xi + 1))
                }
            }
        }

        val component = dsf.canonify(y * w + x)
        var parity = 0
        for (xi in 0 until w) {
            for (yi in 0 until h) {
                if (dsf.canonify(yi * w + xi) != component) continue
                for (di in intArrayOf(1, 2, 4, 8)) {
                    val xi2 = xi + dx(di)
                    val yi2 = yi + dy(di)
                    if ((xi2 < 0 || xi2 >= w || yi2 < 0 || yi2 >= h ||
                            dsf.canonify(yi2 * w + xi2) != component) &&
                        sEDirs(state, xi, yi, E_TRACK) and di != 0
                    ) {
                        parity = parity xor 1
                    }
                }
            }
        }

        solveSetEFlag(state, x, y, d, if (parity != 0) E_TRACK else E_NOTRACK)
        return 1
    }

    private fun solveCheckBridgeParity(
        state: State,
        scratch: SolverScratch,
    ): Int {
        val w = state.params.w
        val h = state.params.h
        val wh = w * h
        var did = 0
        val fls = FindLoopState(wh)
        val ctx = intArrayOf(0, 0, 0) // x, y, dirs

        fls.run(Neighbour { vertex ->
            if (vertex >= 0) {
                ctx[0] = vertex % w
                ctx[1] = vertex / w
                ctx[2] = ALLDIR and
                    sEDirs(state, ctx[0], ctx[1], E_TRACK).inv() and
                    sEDirs(state, ctx[0], ctx[1], E_NOTRACK).inv()
            }
            val dir = ctx[2] and (-ctx[2])
            if (dir == 0) {
                -1
            } else {
                ctx[2] = ctx[2] and dir.inv()
                val xr = ctx[0] + dx(dir)
                val yr = ctx[1] + dy(dir)
                check(xr in 0 until w && yr in 0 until h) {
                    "tracks: the bridge graph left the grid"
                }
                yr * w + xr
            }
        })

        for (x in 0 until w) {
            for (y in 0 until h) {
                if (y + 1 < h && !fls.isLoopEdge(y * w + x, (y + 1) * w + x)) {
                    did += solveBridgeSub(state, x, y, D, scratch)
                }
                if (x + 1 < w && !fls.isLoopEdge(y * w + x, y * w + x + 1)) {
                    did += solveBridgeSub(state, x, y, R, scratch)
                }
            }
        }
        return did
    }

    /** `tracks_solve`: the rule order and the `TRY` band gating are fixed. */
    private fun tracksSolve(state: State, diff: Int): SolveResult {
        val w = state.params.w
        val h = state.params.h
        val scratch = SolverScratch()
        var maxDiff = DIFF_EASY

        state.impossible = false
        for (x in 0 until w) {
            solveDiscountEdge(state, x, 0, U)
            solveDiscountEdge(state, x, h - 1, D)
        }
        for (y in 0 until h) {
            solveDiscountEdge(state, 0, y, L)
            solveDiscountEdge(state, w - 1, y, R)
        }

        loop@ while (!state.impossible) {
            if (diff >= DIFF_EASY && solveUpdateFlags(state) != 0) {
                maxDiff = max(maxDiff, DIFF_EASY)
                continue@loop
            }
            if (diff >= DIFF_EASY && solveCountClues(state) != 0) {
                maxDiff = max(maxDiff, DIFF_EASY)
                continue@loop
            }
            if (diff >= DIFF_EASY && solveCheckLoop(state) != 0) {
                maxDiff = max(maxDiff, DIFF_EASY)
                continue@loop
            }
            if (diff >= DIFF_TRICKY && solveCheckSingle(state) != 0) {
                maxDiff = max(maxDiff, DIFF_TRICKY)
                continue@loop
            }
            if (diff >= DIFF_TRICKY && solveCheckLooseEnds(state) != 0) {
                maxDiff = max(maxDiff, DIFF_TRICKY)
                continue@loop
            }
            if (diff >= DIFF_TRICKY && solveCheckNeighbours(state, false) != 0) {
                maxDiff = max(maxDiff, DIFF_TRICKY)
                continue@loop
            }
            if (diff >= DIFF_HARD && solveCheckNeighbours(state, true) != 0) {
                maxDiff = max(maxDiff, DIFF_HARD)
                continue@loop
            }
            if (diff >= DIFF_HARD && solveCheckBridgeParity(state, scratch) != 0) {
                maxDiff = max(maxDiff, DIFF_HARD)
                continue@loop
            }
            break@loop
        }

        val ret = if (state.impossible) {
            -1
        } else if (checkCompletion(state, false)) {
            1
        } else {
            0
        }
        return SolveResult(ret, maxDiff)
    }

    // ------------------------------------------------------------------
    // Completion and the move diff.
    // ------------------------------------------------------------------

    private class TracksNeighbourCtx(val state: State) {
        var index = 0
        var count = 0
        val neighbours = IntArray(4)
    }

    private fun tracksNeighbour(vertex: Int, ctx: TracksNeighbourCtx): Int {
        if (vertex >= 0) {
            val state = ctx.state
            val w = state.params.w
            val x = vertex % w
            val y = vertex / w
            val dirs = sEDirs(state, x, y, E_TRACK)
            ctx.index = 0
            ctx.count = 0
            for (j in 0 until 4) {
                val dir = 1 shl j
                if (dirs and dir != 0) {
                    val nx = x + dx(dir)
                    val ny = y + dy(dir)
                    if (inGrid(state, nx, ny)) {
                        ctx.neighbours[ctx.count++] = ny * w + nx
                    }
                }
            }
        }
        return if (ctx.index < ctx.count) ctx.neighbours[ctx.index++] else -1
    }

    /** `check_completion`: the only writer of `completed` and the marks. */
    private fun checkCompletion(state: State, mark: Boolean): Boolean {
        val w = state.params.w
        val h = state.params.h
        var ret = true
        var pathret: Boolean
        var ntrack: Int
        var nnotrack: Int
        var ntrackcomplete: Int

        if (mark) {
            state.numErrors.fill(0)
            for (i in 0 until w * h) {
                state.sflags[i] = state.sflags[i] and S_ERROR.inv()
                if (sECount(state, i % w, i / w, E_TRACK) > 0) {
                    if (sECount(state, i % w, i / w, E_TRACK) > 2) {
                        ret = false
                        state.sflags[i] = state.sflags[i] or S_ERROR
                    }
                }
            }
        }

        val dsf = Dsf.new(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                for (dir in intArrayOf(R, D)) {
                    if (sEDirs(state, x, y, E_TRACK) and dir == 0) continue
                    val bx = x + dx(dir)
                    val by = y + dy(dir)
                    if (!inGrid(state, bx, by)) continue
                    dsf.merge(y * w + x, by * w + bx)
                }
            }
        }

        val fls = FindLoopState(w * h)
        val ctx = TracksNeighbourCtx(state)
        if (fls.run(Neighbour { tracksNeighbour(it, ctx) })) {
            ret = false
            if (mark) {
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        val u = y * w + x
                        var v = tracksNeighbour(u, ctx)
                        while (v >= 0) {
                            if (fls.isLoopEdge(u, v)) {
                                state.sflags[y * w + x] =
                                    state.sflags[y * w + x] or S_ERROR
                            }
                            v = tracksNeighbour(-1, ctx)
                        }
                    }
                }
            }
        }

        if (mark) {
            val pathclass = dsf.canonify(state.numbers.rowS * w)
            if (pathclass == dsf.canonify((h - 1) * w + state.numbers.colS)) {
                for (i in 0 until w * h) {
                    if (dsf.canonify(i) != pathclass &&
                        (state.sflags[i] and S_TRACK != 0 ||
                            sECount(state, i % w, i / w, E_TRACK) > 0)
                    ) {
                        ret = false
                        state.sflags[i] = state.sflags[i] or S_ERROR
                    }
                }
            } else {
                ret = false
            }
        }

        pathret = ret
        for (x in 0 until w) {
            val target = state.numbers.values[x]
            ntrack = 0
            nnotrack = 0
            ntrackcomplete = 0
            for (y in 0 until h) {
                if (sECount(state, x, y, E_TRACK) > 0 ||
                    state.sflags[y * w + x] and S_TRACK != 0
                ) {
                    ntrack++
                }
                if (sECount(state, x, y, E_TRACK) == 2) ntrackcomplete++
                if (state.sflags[y * w + x] and S_NOTRACK != 0) nnotrack++
            }
            if (mark) {
                if (ntrack > target || nnotrack > h - target ||
                    (pathret && ntrackcomplete != target)
                ) {
                    state.numErrors[x] = 1
                    ret = false
                }
            }
            if (ntrackcomplete != target) ret = false
        }
        for (y in 0 until h) {
            val target = state.numbers.values[w + y]
            ntrack = 0
            nnotrack = 0
            ntrackcomplete = 0
            for (x in 0 until w) {
                if (sECount(state, x, y, E_TRACK) > 0 ||
                    state.sflags[y * w + x] and S_TRACK != 0
                ) {
                    ntrack++
                }
                if (sECount(state, x, y, E_TRACK) == 2) ntrackcomplete++
                if (state.sflags[y * w + x] and S_NOTRACK != 0) nnotrack++
            }
            if (mark) {
                if (ntrack > target || nnotrack > w - target ||
                    (pathret && ntrackcomplete != target)
                ) {
                    state.numErrors[w + y] = 1
                    ret = false
                }
            }
            if (ntrackcomplete != target) ret = false
        }

        if (mark) {
            state.completed = ret
            if (ret) setFlashData(state)
        }
        return ret
    }

    /** `set_flash_data`: label each tile with its position along the path. */
    private fun setFlashData(state: State) {
        val w = state.params.w
        val h = state.params.h
        var ntrack = 0
        for (x in 0 until w) ntrack += state.numbers.values[x]
        var n = 0
        var x = 0
        var y = state.numbers.rowS
        var d = R
        do {
            state.sflags[y * w + x] =
                state.sflags[y * w + x] and (S_FLASH_MASK shl S_FLASH_SHIFT).inv()
            state.sflags[y * w + x] = state.sflags[y * w + x] or
                (n++ * (S_FLASH_MASK / (ntrack - 1)) shl S_FLASH_SHIFT)
            d = opposite(d)
            d = sEDirs(state, x, y, E_TRACK) and d.inv()
            x += dx(d)
            y += dy(d)
        } while (inGrid(state, x, y))
    }

    /** `move_string_diff`: every changed flag, row-major. */
    private fun moveStringDiff(before: State, after: State, isSolve: Boolean): String {
        val w = after.params.w
        val h = after.params.h
        val out = StringBuilder()
        var sep = ""
        if (isSolve) {
            out.append('S')
            sep = ";"
        }
        for (i in 0 until w * h) {
            val x = i % w
            val y = i / w
            val otf = sEDirs(before, x, y, E_TRACK)
            val ntf = sEDirs(after, x, y, E_TRACK)
            val onf = sEDirs(before, x, y, E_NOTRACK)
            val nnf = sEDirs(after, x, y, E_NOTRACK)

            for (j in 0 until 4) {
                val df = 1 shl j
                if (otf and df != ntf and df) {
                    out.append(sep).append(if (ntf and df != 0) 'T' else 't')
                        .append(moveChar(df)).append(x).append(',').append(y)
                    sep = ";"
                }
                if (onf and df != nnf and df) {
                    out.append(sep).append(if (nnf and df != 0) 'N' else 'n')
                        .append(moveChar(df)).append(x).append(',').append(y)
                    sep = ";"
                }
            }

            if (before.sflags[i] and S_NOTRACK != after.sflags[i] and S_NOTRACK) {
                out.append(sep).append(if (after.sflags[i] and S_NOTRACK != 0) 'N' else 'n')
                    .append('S').append(x).append(',').append(y)
                sep = ";"
            }
            if (before.sflags[i] and S_TRACK != after.sflags[i] and S_TRACK) {
                out.append(sep).append(if (after.sflags[i] and S_TRACK != 0) 'T' else 't')
                    .append('S').append(x).append(',').append(y)
                sep = ";"
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    override fun executeMove(state: Game.State, move: String): Game.State? =
        executeMoveState(state as State, move)

    private fun executeMoveState(state: State, move: String): State? {
        val w = state.params.w
        val ret = state.copy() as State
        var moveIsSolve = false
        var index = 0

        while (index < move.length) {
            val c = move[index]
            when (c) {
                'S' -> {
                    ret.usedSolve = true
                    moveIsSolve = true
                    index++
                }

                'T', 't', 'N', 'n' -> {
                    val parsed = parseDirectionAndInts(move, index + 1) ?: return null
                    val x = parsed.x
                    val y = parsed.y
                    if (!inGrid(state, x, y)) return null
                    val f = if (c == 'T' || c == 't') S_TRACK else S_NOTRACK
                    when (parsed.direction) {
                        'S' -> {
                            if (!uiCanFlipSquare(ret, x, y, f == S_NOTRACK) &&
                                !moveIsSolve
                            ) {
                                return null
                            }
                            if (c == 'T' || c == 'N') {
                                ret.sflags[y * w + x] = ret.sflags[y * w + x] or f
                            } else {
                                ret.sflags[y * w + x] =
                                    ret.sflags[y * w + x] and f.inv()
                            }
                        }

                        'U', 'D', 'L', 'R' -> {
                            for (i in 0 until 4) {
                                val df = 1 shl i
                                if (moveChar(df) == parsed.direction) {
                                    if (!uiCanFlipEdge(
                                            ret,
                                            x,
                                            y,
                                            df,
                                            f == S_NOTRACK,
                                        ) && !moveIsSolve
                                    ) {
                                        return null
                                    }
                                    if (c == 'T' || c == 'N') {
                                        sESet(ret, x, y, df, f)
                                    } else {
                                        sEClear(ret, x, y, df, f)
                                    }
                                }
                            }
                        }

                        else -> return null
                    }
                    index = parsed.end
                }

                'H' -> {
                    tracksSolve(ret, DIFFCOUNT)
                    index++
                }

                else -> return null
            }
            if (index < move.length) {
                if (move[index] == ';') {
                    index++
                } else {
                    return null
                }
            }
        }

        checkCompletion(ret, true)
        return ret
    }

    private data class ParsedMove(
        val direction: Char,
        val x: Int,
        val y: Int,
        val end: Int,
    )

    /**
     * `sscanf(move, "%c%d,%d%n", ...)`: one character, then two C
     * decimal integers separated by a comma.
     */
    private fun parseDirectionAndInts(text: String, start: Int): ParsedMove? {
        var index = start
        if (index >= text.length) return null
        val direction = text[index]
        index++
        val first = cInt(text, index) ?: return null
        index = first.end
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = cInt(text, index) ?: return null
        index = second.end
        return ParsedMove(direction, first.value, second.value, index)
    }

    private data class CInt(val value: Int, val end: Int)

    /** A C `%d` conversion with the port's saturating overflow. */
    private fun cInt(text: String, start: Int): CInt? {
        var index = start
        while (index < text.length && text[index].isCWhitespace()) index++
        var sign = 1
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            if (text[index] == '-') sign = -1
            index++
        }
        val digitsStart = index
        var value = 0L
        while (index < text.length && text[index] in '0'..'9') {
            val digit = text[index] - '0'
            value = if (value > (Long.MAX_VALUE - digit) / 10) {
                Long.MAX_VALUE
            } else {
                value * 10 + digit
            }
            index++
        }
        if (index == digitsStart) return null
        val signed = sign * value
        val clamped = when {
            signed > Int.MAX_VALUE -> Int.MAX_VALUE
            signed < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> signed.toInt()
        }
        return CInt(clamped, index)
    }

    /** `edge_flip_str`. */
    internal fun edgeFlipStr(state: State, x: Int, y: Int, dir: Int, notrack: Boolean): String {
        val ef = sEFlags(state, x, y, dir)
        val c = if (notrack) {
            if (ef and E_NOTRACK != 0) 'n' else 'N'
        } else {
            if (ef and E_TRACK != 0) 't' else 'T'
        }
        return "$c${moveChar(dir)}$x,$y"
    }

    /**
     * `square_flip_str`. It reads the square word with the edge
     * constants, which works only because `S_TRACK == E_TRACK` and
     * `S_NOTRACK == E_NOTRACK`.
     */
    internal fun squareFlipStr(state: State, x: Int, y: Int, notrack: Boolean): String {
        val f = state.sflags[y * state.params.w + x]
        val c = if (notrack) {
            if (f and E_NOTRACK != 0) 'n' else 'N'
        } else {
            if (f and E_TRACK != 0) 't' else 'T'
        }
        return "${c}S$x,$y"
    }

    // ------------------------------------------------------------------
    // Input and the rule engine.
    // ------------------------------------------------------------------

    /** `ui_can_flip_edge`. */
    private fun uiCanFlipEdge(
        state: State,
        x: Int,
        y: Int,
        dir: Int,
        notrack: Boolean,
    ): Boolean {
        val w = state.params.w
        val x2 = x + dx(dir)
        val y2 = y + dy(dir)
        if (!inGrid(state, x, y) || !inGrid(state, x2, y2)) return false

        val sf1 = state.sflags[y * w + x]
        val sf2 = state.sflags[y2 * w + x2]
        if (!notrack && (sf1 and S_CLUE != 0 || sf2 and S_CLUE != 0)) {
            return false
        }

        val ef = sEFlags(state, x, y, dir)
        if (notrack) {
            if (ef and E_NOTRACK == 0 && ef and E_TRACK != 0) return false
        } else if (ef and E_TRACK == 0) {
            if (sf1 and S_NOTRACK != 0 || sf2 and S_NOTRACK != 0 ||
                ef and E_NOTRACK != 0
            ) {
                return false
            }
            if (sECount(state, x, y, E_TRACK) >= 2 ||
                sECount(state, x2, y2, E_TRACK) >= 2
            ) {
                return false
            }
        }
        return true
    }

    /** `ui_can_flip_square`. */
    private fun uiCanFlipSquare(state: State, x: Int, y: Int, notrack: Boolean): Boolean {
        val w = state.params.w
        if (!inGrid(state, x, y)) return false
        val sf = state.sflags[y * w + x]
        val trackc = sECount(state, x, y, E_TRACK)
        if (sf and S_CLUE != 0) return false
        if (notrack) {
            if (sf and S_NOTRACK == 0 && (sf and S_TRACK != 0 || trackc > 0)) {
                return false
            }
        } else {
            if (sf and S_TRACK == 0 && sf and S_NOTRACK != 0) return false
        }
        return true
    }

    /** `update_ui_drag`. */
    private fun updateUiDrag(state: State, ui: Ui, gx: Int, gy: Int) {
        val w = state.params.w
        val h = state.params.h
        val dxs = abs(ui.dragSx - gx)
        val dys = abs(ui.dragSy - gy)
        if (dys == 0) {
            ui.dragEx = if (gx < 0) 0 else if (gx >= w) w - 1 else gx
            ui.dragEy = ui.dragSy
            ui.dragging = true
        } else if (dxs == 0) {
            ui.dragEx = ui.dragSx
            ui.dragEy = if (gy < 0) 0 else if (gy >= h) h - 1 else gy
            ui.dragging = true
        } else {
            ui.dragEx = ui.dragSx
            ui.dragEy = ui.dragSy
            ui.dragging = false
        }
    }

    /** `copy_and_apply_drag`: the drag preview state. */
    private fun copyAndApplyDrag(state: State, ui: Ui): State {
        val after = state.copy() as State
        val w = state.params.w
        val f = if (ui.notrack) S_NOTRACK else S_TRACK
        val x1 = min(ui.dragSx, ui.dragEx)
        val x2 = max(ui.dragSx, ui.dragEx)
        val y1 = min(ui.dragSy, ui.dragEy)
        val y2 = max(ui.dragSy, ui.dragEy)
        for (x in x1..x2) {
            for (y in y1..y2) {
                if (!inGrid(state, x, y)) continue
                val ff = state.sflags[y * w + x]
                if (ui.clearing && ff and f == 0) continue
                if (!ui.clearing && ff and f != 0) continue
                if (uiCanFlipSquare(state, x, y, ui.notrack)) {
                    after.sflags[y * w + x] = after.sflags[y * w + x] xor f
                }
            }
        }
        return after
    }

    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult = interpretMoveState(state as State, ui as Ui, x, y, button, tileSize)

    private fun interpretMoveState(
        state: State,
        ui: Ui,
        x0: Int,
        y0: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val geom = geometry(tileSize)
        var x = x0
        var y = y0
        var gx = fromCoord(x, geom)
        var gy = fromCoord(y, geom)

        if (Buttons.isMouseDown(button)) {
            ui.cursorActive = false
            ui.dragging = false

            if (!inGrid(state, gx, gy)) {
                ui.dragSx = -1
                ui.dragSy = -1
                return MoveResult.Unused
            }

            if (button == Buttons.RIGHT_BUTTON) {
                ui.notrack = true
                ui.clearing = state.sflags[gy * state.params.w + gx] and S_NOTRACK != 0
            } else {
                ui.notrack = false
                ui.clearing = state.sflags[gy * state.params.w + gx] and S_TRACK != 0
            }

            ui.clickx = x
            ui.clicky = y
            ui.dragSx = gx
            ui.dragEx = gx
            ui.dragSy = gy
            ui.dragEy = gy
            return MoveResult.UiUpdate
        }

        if (Buttons.isMouseDrag(button)) {
            ui.cursorActive = false
            updateUiDrag(state, ui, gx, gy)
            return MoveResult.UiUpdate
        }

        if (Buttons.isMouseRelease(button)) {
            ui.cursorActive = false
            if (ui.dragging &&
                (ui.dragSx != ui.dragEx || ui.dragSy != ui.dragEy)
            ) {
                val dragged = copyAndApplyDrag(state, ui)
                val ret = moveStringDiff(state, dragged, false)
                ui.dragging = false
                return MoveResult.Move(ret)
            }

            // A one-square drag is a click. The press position decides
            // where, so the UI field is read even after an off-grid press.
            ui.dragging = false
            x = ui.clickx
            y = ui.clicky
            val cx = centeredCoord(gx, geom)
            val cy = centeredCoord(gy, geom)

            if (!inGrid(state, gx, gy) ||
                fromCoord(x, geom) != gx || fromCoord(y, geom) != gy
            ) {
                return MoveResult.UiUpdate
            }

            if (max(abs(x - cx), abs(y - cy)) < geom.tile / 4) {
                return if (uiCanFlipSquare(state, gx, gy, button == Buttons.RIGHT_RELEASE)) {
                    MoveResult.Move(squareFlipStr(state, gx, gy, button == Buttons.RIGHT_RELEASE))
                } else {
                    MoveResult.UiUpdate
                }
            }
            val direction = if (abs(x - cx) < abs(y - cy)) {
                if (y < cy) U else D
            } else {
                if (x < cx) L else R
            }
            return if (uiCanFlipEdge(
                    state,
                    gx,
                    gy,
                    direction,
                    button == Buttons.RIGHT_RELEASE,
                )
            ) {
                MoveResult.Move(
                    edgeFlipStr(
                        state,
                        gx,
                        gy,
                        direction,
                        button == Buttons.RIGHT_RELEASE,
                    ),
                )
            } else {
                MoveResult.UiUpdate
            }
        }

        if (Buttons.isCursorMove(button)) {
            val dxm = if (button == Buttons.CURSOR_LEFT) -1 else
                if (button == Buttons.CURSOR_RIGHT) 1 else 0
            val dym = if (button == Buttons.CURSOR_DOWN) 1 else
                if (button == Buttons.CURSOR_UP) -1 else 0

            if (!ui.cursorActive) {
                ui.cursorActive = true
                return MoveResult.UiUpdate
            }

            ui.curx += dxm
            ui.cury += dym
            if (ui.curx % 2 == 0 && ui.cury % 2 == 0) {
                ui.curx += dxm
                ui.cury += dym
            }
            ui.curx = min(max(ui.curx, 1), 2 * state.params.w - 1)
            ui.cury = min(max(ui.cury, 1), 2 * state.params.h - 1)
            return MoveResult.UiUpdate
        }

        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!ui.cursorActive) {
                ui.cursorActive = true
                return MoveResult.UiUpdate
            }
            if (ui.curx % 2 == 0 && ui.cury % 2 == 0) {
                return MoveResult.UiUpdate
            }

            gx = ui.curx / 2
            gy = ui.cury / 2
            val direction = if (ui.curx % 2 == 0) L else if (ui.cury % 2 == 0) U else 0
            val select2 = button == Buttons.CURSOR_SELECT2
            if (direction != 0 && uiCanFlipEdge(state, gx, gy, direction, select2)) {
                return MoveResult.Move(edgeFlipStr(state, gx, gy, direction, select2))
            }
            if (direction == 0 && uiCanFlipSquare(state, gx, gy, select2)) {
                return MoveResult.Move(squareFlipStr(state, gx, gy, select2))
            }
            return MoveResult.UiUpdate
        }

        return MoveResult.Unused
    }

    // ------------------------------------------------------------------
    // Solve, status, text, keys, UI.
    // ------------------------------------------------------------------

    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state).move

    /** `solve_game` with the C error string (`tracks.c:1664-1688`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state)
        return SolveOutcome(attempt.move, attempt.error)
    }

    /**
     * The solver with the C error string. The seam has no error
     * channel, so [solve] returns null and a caller that needs the
     * message uses this helper.
     */
    fun solveAttempt(state: Game.State): SolveAttempt {
        val current = state as State
        var solved = current.copy() as State
        var ret = tracksSolve(solved, DIFFCOUNT).ret
        if (ret < 1) {
            solved = current.shared.buildInitial()
            ret = tracksSolve(solved, DIFFCOUNT).ret
        }
        if (ret < 1) {
            return SolveAttempt(null, "Unable to find solution")
        }
        return SolveAttempt(moveStringDiff(current, solved, true), null)
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** No status bar text; C has `wants_statusbar` false. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return next.completed && !next.usedSolve && oldState != null &&
            !(oldState as State).completed
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.params.w
        val h = s.params.h
        val out = StringBuilder((2 * w + 4) * (2 * h + 4) + 2)

        out.append(' ').append(' ')
        for (x in 0 until w) {
            out.append(clueChar(s.numbers.values[x])).append(' ')
        }
        out.append('\n')

        out.append(' ').append('+')
        for (x in 0 until 2 * w - 1) out.append('-')
        out.append('+').append('\n')

        for (y in 0 until h) {
            out.append(if (y == s.numbers.rowS) 'A' else ' ')
            out.append(if (y == s.numbers.rowS) '-' else '|')
            for (x in 0 until w) {
                val f = sEDirs(s, x, y, E_TRACK)
                if (s.sflags[y * w + x] and S_CLUE != 0) {
                    out.append('C')
                } else if (f == LU || f == RD) {
                    out.append('/')
                } else if (f == LD || f == RU) {
                    out.append('\\')
                } else if (f == UD) {
                    out.append('|')
                } else if (f == LR) {
                    out.append('-')
                } else if (s.sflags[y * w + x] and S_NOTRACK != 0) {
                    out.append('x')
                } else {
                    out.append(' ')
                }
                if (x < w - 1) {
                    out.append(if (f and R != 0) '-' else ' ')
                } else {
                    out.append('|')
                }
            }
            out.append(clueChar(s.numbers.values[w + y])).append('\n')

            if (y == h - 1) continue
            out.append(' ').append('|')
            for (x in 0 until w) {
                val f = sEDirs(s, x, y, E_TRACK)
                out.append(if (f and D != 0) '|' else ' ')
                out.append(if (x < w - 1) ' ' else '|')
            }
            out.append('\n')
        }

        out.append(' ').append('+')
        for (x in 0 until 2 * w - 1) {
            out.append(if (x == s.numbers.colS * 2) '|' else '-')
        }
        out.append('+').append('\n')

        out.append(' ').append(' ')
        for (x in 0 until 2 * w - 1) {
            out.append(if (x == s.numbers.colS * 2) 'B' else ' ')
        }
        out.append('\n')
        return out.toString()
    }

    private fun clueChar(value: Int): Char =
        if (value < 10) ('0'.code + value).toChar() else ('A'.code + value - 10).toChar()

    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /** `request_keys` is NULL, so only the midend's arrow mode changes. */
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `current_key_label`; the seam has no operation for it. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.cursorActive
        ) {
            val gx = u.curx / 2
            val gy = u.cury / 2
            val w = s.params.w
            val direction = if (u.curx % 2 == 0) L else if (u.cury % 2 == 0) U else 0
            val select2 = button == Buttons.CURSOR_SELECT2
            if (direction != 0 && uiCanFlipEdge(s, gx, gy, direction, select2)) {
                val ef = sEFlags(s, gx, gy, direction)
                return if (button == Buttons.CURSOR_SELECT) {
                    if (ef and E_TRACK != 0) "Clear" else "Track"
                } else {
                    if (ef and E_NOTRACK != 0) "Clear" else "X"
                }
            }
            if (direction == 0 && uiCanFlipSquare(s, gx, gy, select2)) {
                val sf = s.sflags[gy * w + gx]
                return if (button == Buttons.CURSOR_SELECT) {
                    if (sf and S_TRACK != 0) "Clear" else "Track"
                } else {
                    if (sf and S_NOTRACK != 0) "Clear" else "X"
                }
            }
        }
        return ""
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorActive = visible
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorActive) return null
        val geom = geometry(tileSize)
        val off = geom.halsz / 4
        var cx = coord(u.curx / 2, geom) + off
        var cy = coord(u.cury / 2, geom) + off
        var cw = geom.tile - 2 * off + 1
        var ch = cw
        if (u.curx % 2 == 0) {
            cx -= off
            cw = 2 * off + 1
        }
        if (u.cury % 2 == 0) {
            cy -= off
            ch = 2 * off + 1
        }
        return Game.CursorRect(cx, cy, cw, ch)
    }

    // ------------------------------------------------------------------
    // Geometry and palette.
    // ------------------------------------------------------------------

    private class Geometry(
        val sz6: Int,
        val border: Int,
        val gridLineAll: Int,
        val gridLineTl: Int,
        val gridLineBr: Int,
    ) {
        val tile: Int get() = sz6 * 6
        val halsz: Int get() = sz6 * 3
    }

    private fun geometry(tileSize: Int): Geometry {
        val sz6 = tileSize / 6
        val tile = sz6 * 6
        val maxBorder = tile / 8
        var border = maxBorder
        if (border <= 5) border = min(tileSize % 6, maxBorder)
        val lineThick = tile / 16
        val gridLineAll = max(lineThick, 1)
        val gridLineBr = gridLineAll / 2
        val gridLineTl = gridLineAll - gridLineBr
        return Geometry(sz6, border, gridLineAll, gridLineTl, gridLineBr)
    }

    private fun coord(value: Int, geom: Geometry): Int =
        (value + 1) * geom.tile + geom.border

    private fun centeredCoord(value: Int, geom: Geometry): Int =
        coord(value, geom) + geom.tile / 2

    private fun fromCoord(value: Int, geom: Geometry): Int =
        if (value < geom.border) -1 else (value - geom.border) / geom.tile - 1

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val geom = geometry(tileSize)
        return Size(
            (p.w + 2) * geom.tile + 2 * geom.border,
            (p.h + 2) * geom.tile + 2 * geom.border,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, -1, COL_TRACK_BACKGROUND)

        // The C code also writes COL_GRID with colour_mix and COL_CURSOR
        // with 0.3, and then overwrites both. The final values are below.
        for (i in 0 until 3) {
            ret[COL_TRACK_CLUE * 3 + i] = 0.0f
            ret[COL_TRACK * 3 + i] = 0.5f
            ret[COL_CLUE * 3 + i] = 0.0f
            ret[COL_GRID * 3 + i] = 0.75f
            ret[COL_CURSOR * 3 + i] = 0.3f
            ret[COL_ERROR_BACKGROUND * 3 + i] = 1.0f
        }

        ret[COL_SLEEPER * 3 + 0] = 0.5f
        ret[COL_SLEEPER * 3 + 1] = 0.4f
        ret[COL_SLEEPER * 3 + 2] = 0.1f

        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_DRAGON * 3 + 0] = 0.0f
        ret[COL_DRAGON * 3 + 1] = 0.0f
        ret[COL_DRAGON * 3 + 2] = 1.0f

        ret[COL_DRAGOFF * 3 + 0] = 0.8f
        ret[COL_DRAGOFF * 3 + 1] = 0.8f
        ret[COL_DRAGOFF * 3 + 2] = 1.0f

        ret[COL_FLASH * 3 + 0] = 1.0f
        ret[COL_FLASH * 3 + 1] = 1.0f
        ret[COL_FLASH * 3 + 2] = 1.0f

        ret[COL_CURSOR * 3 + 0] = 1.0f
        ret[COL_CURSOR * 3 + 1] = 0.5f
        ret[COL_CURSOR * 3 + 2] = 0.5f
        return ret
    }

    // ------------------------------------------------------------------
    // Drawing.
    // ------------------------------------------------------------------

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val geom = geometry(tileSize)
        val wh = s.params.w * s.params.h
        return DrawState(
            sz6 = geom.sz6,
            border = geom.border,
            gridLineAll = geom.gridLineAll,
            gridLineTl = geom.gridLineTl,
            gridLineBr = geom.gridLineBr,
            w = s.params.w,
            h = s.params.h,
            flags = IntArray(wh),
            flagsDrag = IntArray(wh),
            numErrors = IntArray(s.params.w + s.params.h),
            started = false,
        )
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

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
        val w = ds.w
        val h = ds.h
        val geom = Geometry(ds.sz6, ds.border, ds.gridLineAll, ds.gridLineTl, ds.gridLineBr)
        var force = false

        if (!ds.started) {
            drawLoopEnds(s, sink, geom, COL_CLUE)

            sink.rect(
                coord(0, geom) - ds.gridLineBr,
                coord(0, geom) - ds.gridLineBr,
                w * geom.tile + ds.gridLineAll,
                h * geom.tile + ds.gridLineAll,
                COL_GRID,
            )
            sink.update(
                0,
                0,
                (w + 2) * geom.tile + 2 * ds.border,
                (h + 2) * geom.tile + 2 * ds.border,
            )
            ds.started = true
            force = true
        }

        for (i in 0 until w + h) {
            if (force || s.numErrors[i] != ds.numErrors[i]) {
                ds.numErrors[i] = s.numErrors[i]
                drawClue(
                    s,
                    sink,
                    geom,
                    s.numbers.values[i],
                    i,
                    if (ds.numErrors[i] != 0) COL_ERROR else COL_CLUE,
                    if (ds.numErrors[i] != 0) COL_ERROR_BACKGROUND else COL_BACKGROUND,
                )
            }
        }

        val dragState = if (u.dragging) copyAndApplyDrag(s, u) else null

        for (x in 0 until w) {
            for (y in 0 until h) {
                val f = s2dFlags(s, x, y, u)
                val fDrag = if (dragState != null) s2dFlags(dragState, x, y, u) else f
                if (f != ds.flags[y * w + x] ||
                    fDrag != ds.flagsDrag[y * w + x] || force
                ) {
                    ds.flags[y * w + x] = f
                    ds.flagsDrag[y * w + x] = fDrag
                    drawSquare(sink, ds, geom, x, y, f, fDrag)
                }
            }
        }
    }

    /** `s2d_flags`: the composite draw word. */
    private fun s2dFlags(state: State, x: Int, y: Int, ui: Ui): Int {
        val w = state.params.w
        var f = sEDirs(state, x, y, E_TRACK)
        f = f or (sEDirs(state, x, y, E_NOTRACK) shl DS_NSHIFT)

        if (state.sflags[y * w + x] and S_ERROR != 0) f = f or DS_ERROR
        if (state.sflags[y * w + x] and S_CLUE != 0) f = f or DS_CLUE
        if (state.sflags[y * w + x] and S_NOTRACK != 0) f = f or DS_NOTRACK
        if (state.sflags[y * w + x] and S_TRACK != 0 ||
            sECount(state, x, y, E_TRACK) > 0
        ) {
            f = f or DS_TRACK
        }

        if (ui.cursorActive) {
            if (ui.curx >= x * 2 && ui.curx <= (x + 1) * 2 &&
                ui.cury >= y * 2 && ui.cury <= (y + 1) * 2
            ) {
                f = f or DS_CURSOR
                if (ui.curx == x * 2) f = f or (L shl DS_CSHIFT)
                if (ui.curx == (x + 1) * 2) f = f or (R shl DS_CSHIFT)
                if (ui.cury == y * 2) f = f or (U shl DS_CSHIFT)
                if (ui.cury == (y + 1) * 2) f = f or (D shl DS_CSHIFT)
            }
        }
        return f
    }

    private class Best(val bits: Int, val colour: Int)

    /** `best_bits`: the drag preview overrides a colour when it differs. */
    private fun bestBits(flags: Int, flagsDrag: Int, colour: Int): Best {
        val nbOrig = NBITS[flags and ALLDIR]
        val nbDrag = NBITS[flagsDrag and ALLDIR]
        return when {
            nbOrig > nbDrag -> Best(flags and ALLDIR, COL_DRAGOFF)
            nbOrig < nbDrag -> Best(flagsDrag and ALLDIR, COL_DRAGON)
            else -> Best(flags and ALLDIR, colour)
        }
    }

    private fun drawRectOutline(
        sink: DrawSink,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colour: Int,
    ) {
        val x1 = x + width - 1
        val y1 = y + height - 1
        sink.poly(intArrayOf(x, y, x, y1, x1, y1, x1, y), -1, colour)
    }

    /** `draw_square`. */
    private fun drawSquare(
        sink: DrawSink,
        ds: DrawState,
        geom: Geometry,
        x: Int,
        y: Int,
        flags: Int,
        flagsDrag: Int,
    ) {
        val t2 = geom.halsz
        val t16 = geom.halsz / 4
        val ox = coord(x, geom)
        val oy = coord(y, geom)
        var cx = ox + t2
        var cy = oy + t2
        val tile = geom.tile
        val gridLineAll = ds.gridLineAll
        val gridLineTl = ds.gridLineTl

        sink.clip(ox, oy, tile, tile)

        var bg = if (flags and DS_TRACK != 0) COL_TRACK_BACKGROUND else COL_BACKGROUND
        bg = bestBits(
            if (flags and DS_TRACK == DS_TRACK) 1 else 0,
            if (flagsDrag and DS_TRACK == DS_TRACK) 1 else 0,
            bg,
        ).colour
        sink.rect(ox, oy, tile, tile, COL_GRID)
        sink.rect(
            ox + gridLineTl,
            oy + gridLineTl,
            tile - gridLineAll,
            tile - gridLineAll,
            bg,
        )

        if (flags and DS_CURSOR != 0) {
            var off = t16
            var curx = ox + off
            var cury = oy + off
            var curw = tile - 2 * off + 1
            var curh = curw

            if (flags and (U shl DS_CSHIFT) != 0) {
                cury = oy - off
                curh = 2 * off + 1
            } else if (flags and (D shl DS_CSHIFT) != 0) {
                cury = oy + tile - off
                curh = 2 * off + 1
            } else if (flags and (L shl DS_CSHIFT) != 0) {
                curx = ox - off
                curw = 2 * off + 1
            } else if (flags and (R shl DS_CSHIFT) != 0) {
                curx = ox + tile - off
                curw = 2 * off + 1
            }

            drawRectOutline(sink, curx, cury, curw, curh, COL_CURSOR)
            drawRectOutline(sink, curx + 1, cury + 1, curw - 2, curh - 2, COL_CURSOR)
        }

        var c = when {
            flags and DS_ERROR != 0 -> COL_ERROR
            flags and DS_FLASH != 0 -> COL_FLASH
            flags and DS_CLUE != 0 -> COL_TRACK_CLUE
            else -> COL_TRACK
        }
        var best = bestBits(flags, flagsDrag, c)
        c = best.colour
        drawTracksSpecific(sink, geom, x, y, best.bits, c, COL_SLEEPER)

        c = COL_TRACK
        best = bestBits(
            if (flags and DS_NOTRACK == DS_NOTRACK) 1 else 0,
            if (flagsDrag and DS_NOTRACK == DS_NOTRACK) 1 else 0,
            c,
        )
        c = best.colour
        if (best.bits != 0) {
            val off = geom.halsz / 2
            sink.thickLine(
                (cx - off).toFloat(),
                (cy - off).toFloat(),
                (cx + off).toFloat(),
                (cy + off).toFloat(),
                (tile / 16).toFloat(),
                c,
            )
            sink.thickLine(
                (cx - off).toFloat(),
                (cy + off).toFloat(),
                (cx + off).toFloat(),
                (cy - off).toFloat(),
                (tile / 16).toFloat(),
                c,
            )
        }

        c = COL_TRACK
        best = bestBits(flags ushr DS_NSHIFT, flagsDrag ushr DS_NSHIFT, c)
        c = best.colour
        for (d in intArrayOf(1, 2, 4, 8)) {
            val off = t16
            cx = ox + t2
            cy = oy + t2
            if (best.bits and d != 0) {
                cx += if (d == R) t2 else if (d == L) -t2 else 0
                cy += if (d == D) t2 else if (d == U) -t2 else 0
                sink.thickLine(
                    (cx - off).toFloat(),
                    (cy - off).toFloat(),
                    (cx + off).toFloat(),
                    (cy + off).toFloat(),
                    (tile / 16).toFloat(),
                    c,
                )
                sink.thickLine(
                    (cx - off).toFloat(),
                    (cy + off).toFloat(),
                    (cx + off).toFloat(),
                    (cy - off).toFloat(),
                    (tile / 16).toFloat(),
                    c,
                )
            }
        }

        sink.unclip()
        sink.update(ox, oy, tile, tile)
    }

    /** `draw_tracks_specific`. */
    private fun drawTracksSpecific(
        sink: DrawSink,
        geom: Geometry,
        x: Int,
        y: Int,
        flags: Int,
        ctrack: Int,
        csleeper: Int,
    ) {
        val tile = geom.tile
        val ox = coord(x, geom).toFloat()
        val oy = coord(y, geom).toFloat()
        val t1 = tile.toFloat()
        val t3 = tile / 3.0f
        val t6 = tile / 6.0f
        val thickTrack = tile / 8.0f
        val thickSleeper = tile / 12.0f

        if (flags == LR) {
            var i = 1
            while (i <= 7) {
                val cx = ox + tile / 8.0f * i
                sink.thickLine(
                    cx, oy + t6, cx, oy + t6 + 2 * t3, thickSleeper, csleeper,
                )
                i += 2
            }
            sink.thickLine(ox, oy + t3, ox + tile, oy + t3, thickTrack, ctrack)
            sink.thickLine(ox, oy + 2 * t3, ox + tile, oy + 2 * t3, thickTrack, ctrack)
            return
        }
        if (flags == UD) {
            var i = 1
            while (i <= 7) {
                val cy = oy + tile / 8.0f * i
                sink.thickLine(
                    ox + t6, cy, ox + t6 + 2 * t3, cy, thickSleeper, csleeper,
                )
                i += 2
            }
            sink.thickLine(ox + t3, oy, ox + t3, oy + tile, thickTrack, ctrack)
            sink.thickLine(ox + 2 * t3, oy, ox + 2 * t3, oy + tile, thickTrack, ctrack)
            return
        }
        if (flags == LU || flags == LD || flags == RU || flags == RD) {
            val cx = if (flags and L != 0) ox else ox + tile
            val cy = if (flags and U != 0) oy else oy + tile

            drawCircleSleepers(sink, geom, cx, cy, 5 * t6, thickSleeper, csleeper)

            sink.thickCircle(
                cx.toInt(), cy.toInt(), (2 * t3).toInt(), thickTrack, -1, ctrack,
            )
            sink.thickCircle(
                cx.toInt(), cy.toInt(), t3.toInt(), thickTrack, -1, ctrack,
            )
            return
        }

        for (d in intArrayOf(1, 2, 4, 8)) {
            if (flags and d == 0) continue
            var i = 1
            while (i <= 2) {
                val ox1: Float
                val ox2: Float
                val oy1: Float
                val oy2: Float
                when (d) {
                    L -> {
                        ox1 = 0f
                        ox2 = thickTrack
                        oy1 = i * t3
                        oy2 = oy1
                    }

                    R -> {
                        ox1 = t1
                        ox2 = t1 - thickTrack
                        oy1 = i * t3
                        oy2 = oy1
                    }

                    U -> {
                        ox1 = i * t3
                        ox2 = ox1
                        oy1 = 0f
                        oy2 = thickTrack
                    }

                    else -> {
                        ox1 = i * t3
                        ox2 = ox1
                        oy1 = t1
                        oy2 = t1 - thickTrack
                    }
                }
                sink.thickLine(
                    ox + ox1, oy + oy1, ox + ox2, oy + oy2, thickTrack, ctrack,
                )
                i++
            }
        }
    }

    /** `draw_circle_sleepers`: twelve radial sleepers. */
    private fun drawCircleSleepers(
        sink: DrawSink,
        geom: Geometry,
        cx: Float,
        cy: Float,
        r2: Float,
        thickness: Float,
        colour: Int,
    ) {
        val qr6 = PI.toFloat() / 12f
        val qr3 = PI.toFloat() / 6f
        val t6 = (geom.sz6 * 2) / 2.0f
        val r1 = t6
        for (i in 0 until 12) {
            val th = qr6 + i * qr3
            val x1 = r1 * cos(th)
            val x2 = r2 * cos(th)
            val y1 = r1 * sin(th)
            val y2 = r2 * sin(th)
            sink.thickLine(
                cx + x1, cy + y1, cx + x2, cy + y2, thickness, colour,
            )
        }
    }

    /** `draw_clue`. */
    private fun drawClue(
        state: State,
        sink: DrawSink,
        geom: Geometry,
        clue: Int,
        i: Int,
        colour: Int,
        background: Int,
    ) {
        val w = state.params.w
        val tsz = geom.tile / 2
        val cx: Int
        val cy: Int
        if (i < w) {
            cx = centeredCoord(i, geom)
            cy = centeredCoord(-1, geom)
        } else {
            cx = centeredCoord(w, geom)
            cy = centeredCoord(i - w, geom)
        }

        if (background >= 0) {
            sink.rect(
                cx - tsz + geom.gridLineTl,
                cy - tsz + geom.gridLineTl,
                geom.tile - geom.gridLineAll,
                geom.tile - geom.gridLineAll,
                background,
            )
        }
        sink.text(
            cx,
            cy,
            Text.FONT_VARIABLE,
            tsz,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            colour,
            clue.toString(),
        )
        sink.update(
            cx - tsz + geom.gridLineTl,
            cy - tsz + geom.gridLineTl,
            geom.tile - geom.gridLineAll,
            geom.tile - geom.gridLineAll,
        )
    }

    /** `draw_loop_ends`: the entrance and exit letters. */
    private fun drawLoopEnds(
        state: State,
        sink: DrawSink,
        geom: Geometry,
        colour: Int,
    ) {
        val tsz = geom.tile / 2
        sink.text(
            centeredCoord(-1, geom),
            centeredCoord(state.numbers.rowS, geom),
            Text.FONT_VARIABLE,
            tsz,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            colour,
            "A",
        )
        sink.text(
            centeredCoord(state.numbers.colS, geom),
            centeredCoord(state.params.h, geom),
            Text.FONT_VARIABLE,
            tsz,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            colour,
            "B",
        )
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isCWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\u000B' ||
            this == '\u000C' || this == '\r'

    /** `shuffle` from `misc.c`: `n - 1` draws with limits `n..2`. */
    private fun shuffle(array: IntArray, count: Int, random: RandomState) {
        require(count in 0..array.size) { "shuffle count is out of range: $count" }
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

    private data class AtoiResult(val value: Int, val end: Int)

    /**
     * C `atoi` from [start]. A number that overflows an `int` saturates,
     * which is the port's defined result for the C undefined behavior.
     */
    private fun cAtoiAt(text: String, start: Int): AtoiResult {
        var index = start
        while (index < text.length && text[index].isCWhitespace()) index++
        var sign = 1
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            if (text[index] == '-') sign = -1
            index++
        }
        var any = false
        var value = 0L
        while (index < text.length && text[index] in '0'..'9') {
            any = true
            val digit = text[index] - '0'
            value = if (value > (Long.MAX_VALUE - digit) / 10) {
                Long.MAX_VALUE
            } else {
                value * 10 + digit
            }
            index++
        }
        val result = if (!any) {
            0
        } else {
            val signed = sign * value
            when {
                signed > Int.MAX_VALUE -> Int.MAX_VALUE
                signed < Int.MIN_VALUE -> Int.MIN_VALUE
                else -> signed.toInt()
            }
        }
        return AtoiResult(result, index)
    }

    /** `atoi` from the start of [text]. */
    private fun cAtoi(text: String, start: Int): Int = cAtoiAt(text, start).value
}
