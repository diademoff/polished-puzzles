package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `keen.c`.
 *
 * Keen is a Latin-square puzzle. Each block of the grid carries one clue,
 * and a clue is an arithmetic expression over the digits in the block.
 * The Latin square generator, the clue solver, the retry loop, and the
 * drawing are all upstream behavior: this file must reproduce every
 * `random_upto` draw and every solver decision in the same order.
 *
 * The clue blocks are immutable and shared by every state of one game,
 * as the C `struct clues` is shared through its reference count. The
 * highlight is frontend state and is never serialised: Keen has no
 * `encode_ui` or `decode_ui`.
 */
object Keen : Game {

    // Difficulty, `keen.c:24-38`.
    private const val DIFF_EASY = 0
    private const val DIFF_NORMAL = 1
    private const val DIFF_HARD = 2
    private const val DIFF_EXTREME = 3
    private const val DIFF_UNREASONABLE = 4
    private const val DIFFCOUNT = 5
    private val DIFF_NAMES =
        arrayOf("Easy", "Normal", "Hard", "Extreme", "Unreasonable")
    private const val DIFF_CHARS = "enhxu"

    // Clue encoding, `keen.c:40-49`.
    private const val C_ADD = 0x00000000L
    private const val C_MUL = 0x20000000L
    private const val C_SUB = 0x40000000L
    private const val C_DIV = 0x60000000L
    private const val CMASK = 0x60000000L

    /** Maximum size of a clue block, `keen.c:57`. */
    private const val MAXBLK = 6

    // Palette, `keen.c:59-68`.
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_BLOCK_BORDER = 2
    private const val COL_USER = 3
    private const val COL_HIGHLIGHT = 4
    private const val COL_ERROR = 5
    private const val COL_PENCIL = 6
    private const val NCOLOURS = 7

    private const val PREFERRED_TILE_SIZE = 48

    // Clue-type flags used while choosing an operation, `keen.c:1059-1063`.
    private const val F_ADD = 0x01
    private const val F_SUB = 0x02
    private const val F_MUL = 0x04
    private const val F_DIV = 0x08
    private const val BAD_SHIFT = 4

    // Tile word, `keen.c:1639-1644`.
    private const val DF_PENCIL_SHIFT = 16
    private const val DF_ERR_LATIN = 0x8000
    private const val DF_ERR_CLUE = 0x4000
    private const val DF_HIGHLIGHT = 0x2000
    private const val DF_HIGHLIGHT_PENCIL = 0x1000
    private const val DF_DIGIT_MASK = 0x000F

    /** The glyphs `text_fallback` chooses for the oracle and Android. */
    private const val MINUS_SIGN = "\u2212"
    private const val TIMES_SIGN = "\u00d7"
    private const val DIVIDE_SIGN = "\u00f7"

    /**
     * The C error strings of `solve_game`. The seam has no error
     * out-parameter, so the adapter uses these when `solve` returns null.
     */
    const val NO_SOLUTION_MESSAGE = "No solution exists for this puzzle"
    const val MULTIPLE_SOLUTIONS_MESSAGE = "Multiple solutions exist for this puzzle"

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val diff: Int,
        val multiplicationOnly: Boolean,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, diff, multiplicationOnly)
    }

    /**
     * `struct clues`: the block partition and one clue per block. The
     * object is immutable after construction and is shared by every
     * state of the game, as the C reference count does.
     *
     * The DSF path-compresses on `minimal`, so its parent array changes
     * during use. Equality therefore compares the class minimum of every
     * cell, which is stable, and not the parent array.
     */
    class Shared internal constructor(
        val w: Int,
        internal val dsf: Dsf,
        val clues: LongArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is Shared || w != other.w) return false
            if (!clues.contentEquals(other.clues)) return false
            for (i in 0 until w * w) {
                if (dsf.minimal(i) != other.dsf.minimal(i)) return false
            }
            return true
        }

        override fun hashCode(): Int {
            var result = w
            result = 31 * result + clues.contentHashCode()
            for (i in 0 until w * w) result = 31 * result + dsf.minimal(i)
            return result
        }
    }

    /** `struct game_state`. */
    class State internal constructor(
        val params: Params,
        val shared: Shared,
        val grid: IntArray,
        val pencil: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val w: Int get() = params.w
        val a: Int get() = w * w

        override fun copy(): Game.State = State(
            params,
            shared,
            grid.copyOf(),
            pencil.copyOf(),
            completed,
            cheated,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                shared == other.shared &&
                grid.contentEquals(other.grid) &&
                pencil.contentEquals(other.pencil) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + shared.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + pencil.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`, `keen.c:1515-1553`. */
    class Ui internal constructor() : Game.Ui {
        internal var hx: Int = 0
        internal var hy: Int = 0
        internal var hpencil: Boolean = false
        internal var hshow: Boolean = false
        internal var hcursor: Boolean = false

        /**
         * The `pencil-keep-highlight` preference. C defaults it to true
         * and reads it only in `interpret_move`, so it is not in the
         * state, the save file, or the drawstate.
         */
        var pencilKeepHighlight: Boolean = true
    }

    /** `struct game_drawstate`, `keen.c:1646-1652`. */
    class DrawState internal constructor(
        val tileSize: Int,
        val w: Int,
        internal val tiles: IntArray,
        internal val errors: IntArray,
    ) : Game.DrawState {
        internal var started: Boolean = false
    }

    /** Internal retry diagnostics. They change no description. */
    internal class GenerationStats {
        var attempts = 0
        var singletonRetries = 0
        var lowerGateRejects = 0
        var exactGateRejects = 0
    }

    internal data class Generated(
        val description: Description,
        val stats: GenerationStats,
    )

    private val PRESETS = listOf(
        Params(4, DIFF_EASY, false),
        Params(5, DIFF_EASY, false),
        Params(5, DIFF_EASY, true),
        Params(6, DIFF_EASY, false),
        Params(6, DIFF_NORMAL, false),
        Params(6, DIFF_NORMAL, true),
        Params(6, DIFF_HARD, false),
        Params(6, DIFF_EXTREME, false),
        Params(6, DIFF_UNREASONABLE, false),
        Params(9, DIFF_NORMAL, false),
    )

    override val name: String = "Keen"
    override val backendName: String = "keen"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = false
    override val flags: Int = Game.REQUIRE_RBUTTON or Game.REQUIRE_NUMPAD

    // ------------------------------------------------------------------
    // Parameters, `keen.c:95-232`.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = Params(6, DIFF_NORMAL, false)

    override fun presets(): List<Preset> = PRESETS.map { p ->
        val suffix = if (p.multiplicationOnly) ", multiplication only" else ""
        Preset("${p.w}x${p.w} ${DIFF_NAMES[p.diff]}$suffix", encodeParams(p, true))
    }

    /**
     * `decode_params`. The digit skip starts at the first character of
     * the text, not at the end of the number, so a leading sign or space
     * stops the skip and the `d`/`m` suffixes are then not recognised.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = cAtoi(text, 0)
        var index = 0
        while (index < text.length && text[index].isAsciiDigit()) index++

        var diff = DIFF_NORMAL
        if (index < text.length && text[index] == 'd') {
            index++
            diff = DIFFCOUNT + 1
            if (index < text.length) {
                val next = text[index]
                for (i in 0 until DIFFCOUNT) {
                    if (next == DIFF_CHARS[i]) diff = i
                }
                index++
            }
        }

        var multiplicationOnly = false
        if (index < text.length && text[index] == 'm') {
            multiplicationOnly = true
        }
        return Params(w, diff, multiplicationOnly)
    }

    /**
     * `encode_params`. The short form is the bare width; the full form
     * adds `d<difficulty>` and `m`.
     *
     * C indexes `keen_diffchars[diff]` with no bound. A difficulty
     * outside `0..4` reads outside the array, which is undefined
     * behavior. The port writes a plain `d` with no character for every
     * such value, the same defined result as the other ports, instead of
     * reading past the array. Validation runs before encoding in the
     * midend, so a validated parameter set never reaches it.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        if (!full) return p.w.toString()
        val out = StringBuilder()
        out.append(p.w).append('d')
        if (p.diff in 0 until DIFFCOUNT) out.append(DIFF_CHARS[p.diff])
        if (p.multiplicationOnly) out.append('m')
        return out.toString()
    }

    /** `validate_params`, in C order: the size first, then the difficulty. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 3 || p.w > 9) return "Grid size must be between 3 and 9"
        if (p.diff >= DIFFCOUNT) return "Unknown difficulty rating"
        return null
    }

    // ------------------------------------------------------------------
    // Description, `keen.c:753-1357`.
    // ------------------------------------------------------------------

    /**
     * `encode_block_structure`, `keen.c:753-826`.
     *
     * The internal lines are the vertical ones in reading order, then the
     * horizontal ones in transposed reading order, then one virtual
     * terminating edge. A run of non-lines is `_` for zero, `a` for one,
     * and so on; `z` is 25 with no following line. The result is then
     * run-length compressed with a decimal count for three or more.
     */
    private fun encodeBlockStructure(w: Int, dsf: Dsf): String {
        val raw = StringBuilder()
        var currrun = 0
        val lines = 2 * w * (w - 1)
        for (i in 0..lines) {
            val edge: Boolean
            if (i == lines) {
                edge = true
            } else {
                val p0: Int
                val p1: Int
                if (i < w * (w - 1)) {
                    val y = i / (w - 1)
                    val x = i % (w - 1)
                    p0 = y * w + x
                    p1 = y * w + x + 1
                } else {
                    val x = i / (w - 1) - w
                    val y = i % (w - 1)
                    p0 = y * w + x
                    p1 = (y + 1) * w + x
                }
                edge = !dsf.equivalent(p0, p1)
            }

            if (edge) {
                while (currrun > 25) {
                    raw.append('z')
                    currrun -= 25
                }
                if (currrun != 0) {
                    raw.append(('a'.code - 1 + currrun).toChar())
                } else {
                    raw.append('_')
                }
                currrun = 0
            } else {
                currrun++
            }
        }

        val out = StringBuilder()
        var r = 0
        while (r < raw.length) {
            val c = raw[r]
            var i = 0
            while (r + i < raw.length && raw[r + i] == c) i++
            out.append(c)
            r += i
            if (i == 2) {
                out.append(c)
            } else if (i > 2) {
                out.append(i)
            }
        }
        return out.toString()
    }

    private data class ParsedBlockStructure(val error: String?, val end: Int)

    /**
     * `parse_block_structure`, `keen.c:828-895`.
     *
     * Each letter is `c` non-lines followed by one line, except `z`,
     * which has no line. A decimal count after the letter repeats it.
     * The walk must consume exactly `2*w*(w-1)` non-lines plus the one
     * terminating line.
     */
    private fun parseBlockStructure(
        text: String,
        start: Int,
        w: Int,
        dsf: Dsf,
    ): ParsedBlockStructure {
        var pos = 0
        var repc = 0
        var repn = 0
        var index = start
        val bound = 2 * w * (w - 1)

        dsf.reinit()

        while (index < text.length && (repn > 0 || text[index] != ',')) {
            var c: Int
            if (repn > 0) {
                repn--
                c = repc
            } else if (text[index] == '_' ||
                (text[index] >= 'a' && text[index] <= 'z')
            ) {
                c = if (text[index] == '_') 0 else text[index] - 'a' + 1
                index++
                if (index < text.length && text[index].isAsciiDigit()) {
                    repc = c
                    repn = cAtoi(text, index) - 1
                    while (index < text.length && text[index].isAsciiDigit()) index++
                }
            } else {
                return ParsedBlockStructure(
                    "Invalid character in game description",
                    index,
                )
            }

            val adv = c != 25
            while (c-- > 0) {
                if (pos >= bound) {
                    return ParsedBlockStructure(
                        "Too much data in block structure specification",
                        index,
                    )
                }
                val p0: Int
                val p1: Int
                if (pos < w * (w - 1)) {
                    val y = pos / (w - 1)
                    val x = pos % (w - 1)
                    p0 = y * w + x
                    p1 = y * w + x + 1
                } else {
                    val x = pos / (w - 1) - w
                    val y = pos % (w - 1)
                    p0 = y * w + x
                    p1 = (y + 1) * w + x
                }
                dsf.merge(p0, p1)
                pos++
            }
            if (adv) {
                pos++
                if (pos > bound + 1) {
                    return ParsedBlockStructure(
                        "Too much data in block structure specification",
                        index,
                    )
                }
            }
        }

        if (pos != bound + 1) {
            return ParsedBlockStructure(
                "Not enough data in block structure specification",
                index,
            )
        }
        return ParsedBlockStructure(null, index)
    }

    /** `validate_desc`, `keen.c:1304-1357`, with the same message order. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val w = p.w
        val a = w * w
        val dsf = Dsf.newMin(a)

        val parsed = parseBlockStructure(desc, 0, w, dsf)
        if (parsed.error != null) return parsed.error

        var index = parsed.end
        if (index >= desc.length || desc[index] != ',') {
            return "Expected ',' after block structure description"
        }
        index++

        for (i in 0 until a) {
            if (dsf.minimal(i) == i) {
                val c = if (index < desc.length) desc[index] else '\u0000'
                if (c == 'a' || c == 'm') {
                    // These clues need no validation.
                } else if (c == 'd' || c == 's') {
                    if (dsf.size(i) != 2) {
                        return "Subtraction and division blocks must have area 2"
                    }
                } else if (c == '\u0000') {
                    return "Too few clues for block structure"
                } else {
                    return "Unrecognised clue type"
                }
                index++
                while (index < desc.length && desc[index].isAsciiDigit()) index++
            }
        }
        if (index < desc.length) return "Too many clues for block structure"
        return null
    }

    /** `game_configure` (`keen.c:187-212`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Grid size", p.w.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Normal:Hard:Extreme:Unreasonable",
                null,
                p.diff,
            ),
            ConfigItem.Bool("Multiplication only", p.multiplicationOnly),
        )
    }

    /** `custom_params` (`keen.c:214-223`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            (items[1] as ConfigItem.Choices).selected,
            (items[2] as ConfigItem.Bool).value,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`keen.c:1578-1594`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Keep cursor after changing numbers",
                true,
                "pencil-keep-highlight",
            ),
        )

    /** `set_prefs` (`keen.c:1596-1599`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).pencilKeepHighlight = prefs.bool("pencil-keep-highlight")
    }

    /**
     * `new_game_desc`, `keen.c:897-1298`.
     *
     * One attempt draws the Latin square, the first order shuffle, one
     * `random_upto(4)` per domino-capable singleton, and the second order
     * shuffle. The clue choice itself draws nothing. The attempt is
     * accepted only when the lower solver gate fails and the exact gate
     * returns the requested difficulty.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        generateWithStats(params as Params, random).description

    internal fun generateWithStats(params: Params, random: RandomState): Generated {
        val w = params.w
        val a = w * w
        var diff = params.diff
        if (w == 3 && diff > DIFF_NORMAL) diff = DIFF_NORMAL

        val stats = GenerationStats()
        val order = IntArray(a)
        val revorder = IntArray(a)
        val singletons = BooleanArray(a)
        val dsf = Dsf.newMin(a)
        val clues = LongArray(a)
        val cluevals = LongArray(a)
        val soln = IntArray(a)
        val flags = IntArray(a)

        var grid: IntArray
        while (true) {
            stats.attempts++

            grid = generate(w, random)

            for (i in 0 until a) order[i] = i
            shuffle(order, random)
            for (i in 0 until a) revorder[order[i]] = i

            singletons.fill(true)
            dsf.reinit()

            // Place dominoes, `keen.c:970-1003`.
            for (i in 0 until a) {
                if (!singletons[i]) continue
                var best = -1
                val x = i % w
                val y = i / w
                if (x > 0 && singletons[i - 1] &&
                    (best == -1 || revorder[i - 1] < revorder[best])
                ) {
                    best = i - 1
                }
                if (x + 1 < w && singletons[i + 1] &&
                    (best == -1 || revorder[i + 1] < revorder[best])
                ) {
                    best = i + 1
                }
                if (y > 0 && singletons[i - w] &&
                    (best == -1 || revorder[i - w] < revorder[best])
                ) {
                    best = i - w
                }
                if (y + 1 < w && singletons[i + w] &&
                    (best == -1 || revorder[i + w] < revorder[best])
                ) {
                    best = i + w
                }
                if (best >= 0 && random.upto(4) != 0L) {
                    singletons[i] = false
                    singletons[best] = false
                    dsf.merge(i, best)
                }
            }

            // Fold the remaining singletons into a neighbour, `keen.c:1005-1031`.
            for (i in 0 until a) {
                if (!singletons[i]) continue
                var best = -1
                val x = i % w
                val y = i / w
                if (x > 0 && dsf.size(i - 1) < MAXBLK &&
                    (best == -1 || revorder[i - 1] < revorder[best])
                ) {
                    best = i - 1
                }
                if (x + 1 < w && dsf.size(i + 1) < MAXBLK &&
                    (best == -1 || revorder[i + 1] < revorder[best])
                ) {
                    best = i + 1
                }
                if (y > 0 && dsf.size(i - w) < MAXBLK &&
                    (best == -1 || revorder[i - w] < revorder[best])
                ) {
                    best = i - w
                }
                if (y + 1 < w && dsf.size(i + w) < MAXBLK &&
                    (best == -1 || revorder[i + w] < revorder[best])
                ) {
                    best = i + w
                }
                if (best >= 0) {
                    singletons[i] = false
                    singletons[best] = false
                    dsf.merge(i, best)
                }
            }

            if (singletons.any { it }) {
                stats.singletonRetries++
                continue
            }

            // Mark the acceptable clue types, `keen.c:1059-1125`.
            for (i in 0 until a) {
                flags[i] = 0
                val j = dsf.minimal(i)
                val k = dsf.size(j)
                if (params.multiplicationOnly) {
                    flags[j] = F_MUL
                } else if (j == i && k > 2) {
                    flags[j] = flags[j] or (F_ADD or F_MUL)
                } else if (j != i && k == 2) {
                    var d1 = grid[j]
                    var d2 = grid[i]
                    if (d1 < d2) {
                        val t = d1
                        d1 = d2
                        d2 = t
                    }

                    val sum = d1 + d2
                    if (sum > 4 && sum < 2 * w - 2) {
                        flags[j] = flags[j] or F_ADD
                    } else {
                        flags[j] = flags[j] or (F_ADD shl BAD_SHIFT)
                    }

                    val product = d1 * d2
                    var options = 0
                    for (v in 1..w) {
                        if (product % v == 0 && product / v <= w && product / v != v) {
                            options++
                        }
                    }
                    if (options <= 2 && diff > DIFF_NORMAL) {
                        flags[j] = flags[j] or (F_MUL shl BAD_SHIFT)
                    } else {
                        flags[j] = flags[j] or F_MUL
                    }

                    if (d1 - d2 < w - 1) flags[j] = flags[j] or F_SUB

                    if (d1 % d2 == 0 && 2 * (d1 / d2) <= w) {
                        flags[j] = flags[j] or F_DIV
                    }
                }
            }

            // Choose one operation per block, `keen.c:1127-1179`.
            shuffle(order, random)
            clues.fill(0L)
            while (true) {
                var doneSomething = false
                for (k in 0 until 4) {
                    val clue: Long
                    val good: Int
                    when (k) {
                        0 -> {
                            clue = C_DIV
                            good = F_DIV
                        }
                        1 -> {
                            clue = C_SUB
                            good = F_SUB
                        }
                        2 -> {
                            clue = C_MUL
                            good = F_MUL
                        }
                        else -> {
                            clue = C_ADD
                            good = F_ADD
                        }
                    }

                    var found = false
                    for (i in 0 until a) {
                        val j = order[i]
                        if (flags[j] and good != 0) {
                            clues[j] = clue
                            flags[j] = 0
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        val bad = good shl BAD_SHIFT
                        for (i in 0 until a) {
                            val j = order[i]
                            if (flags[j] and bad != 0) {
                                clues[j] = clue
                                flags[j] = 0
                                found = true
                                break
                            }
                        }
                    }
                    if (found) doneSomething = true
                }
                if (!doneSomething) break
            }

            // Compute the clue values, `keen.c:1186-1222`.
            cluevals.fill(0L)
            for (i in 0 until a) {
                val j = dsf.minimal(i)
                if (j == i) {
                    cluevals[j] = grid[i].toLong()
                } else {
                    when (clues[j] and CMASK) {
                        C_ADD -> cluevals[j] += grid[i]
                        C_MUL -> cluevals[j] *= grid[i]
                        C_SUB -> cluevals[j] = abs(cluevals[j] - grid[i])
                        else -> {
                            val d1 = cluevals[j]
                            val d2 = grid[i].toLong()
                            cluevals[j] = if (d1 == 0L || d2 == 0L) {
                                0L
                            } else {
                                d2 / d1 + d1 / d2
                            }
                        }
                    }
                }
            }
            for (i in 0 until a) {
                if (dsf.minimal(i) == i) clues[i] = clues[i] or cluevals[i]
            }

            // The two difficulty gates, `keen.c:1224-1237`.
            if (diff > 0) {
                soln.fill(0)
                val lower = solver(w, dsf, clues, soln, diff - 1)
                if (lower <= diff - 1) {
                    stats.lowerGateRejects++
                    continue
                }
            }
            soln.fill(0)
            val ret = solver(w, dsf, clues, soln, diff)
            if (ret != diff) {
                stats.exactGateRejects++
                continue
            }

            break
        }

        val desc = encodeBlockStructure(w, dsf) + "," + encodeClues(w, dsf, clues)
        val aux = StringBuilder(a + 1)
        aux.append('S')
        for (i in 0 until a) {
            check(soln[i] == grid[i]) { "keen solution differs from the generated grid" }
            aux.append(('0'.code + soln[i]).toChar())
        }
        return Generated(Description(desc, aux.toString()), stats)
    }

    private fun encodeClues(w: Int, dsf: Dsf, clues: LongArray): String {
        val a = w * w
        val out = StringBuilder()
        for (i in 0 until a) {
            if (dsf.minimal(i) == i) {
                when (clues[i] and CMASK) {
                    C_ADD -> out.append('a')
                    C_SUB -> out.append('s')
                    C_MUL -> out.append('m')
                    C_DIV -> out.append('d')
                }
                out.append(clues[i] and CMASK.inv())
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Solver, `keen.c:238-747`.
    // ------------------------------------------------------------------

    /**
     * `struct solver_ctx`. The `boxlist` holds transposed indices
     * `x*w + y`, because the Latin cube is indexed `x` first. Cells
     * inside a box are in ascending original cell order.
     */
    private class SolverContext(
        val w: Int,
        val diff: Int,
        val nboxes: Int,
        val boxes: IntArray,
        val boxlist: IntArray,
        val whichbox: IntArray,
        val clues: LongArray,
        val dscratch: IntArray,
        val iscratch: IntArray,
    )

    private fun transpose(index: Int, w: Int): Int = (index % w) * w + (index / w)

    // The five `latin_solver` strategy slots, `keen.c:734-737`, in
    // argument order: simple, set-0, set-1, forcing, recursive.
    // `DIFF_HARD` (2) is the first set level, and `DIFF_EXTREME` (3) is
    // both the second set level and the forcing level. The same values
    // gate generation, so a renumbering changes every description.
    private const val SLOT_SIMPLE = DIFF_EASY
    private const val SLOT_SET_0 = DIFF_HARD
    private const val SLOT_SET_1 = DIFF_EXTREME
    private const val SLOT_FORCING = DIFF_EXTREME
    private const val SLOT_RECURSIVE = DIFF_UNREASONABLE

    /**
     * The five strategy slots, so a test can pin the literal values.
     * They are `latin_solver` arguments, not game state, and nothing
     * reads this value during play.
     */
    internal fun solverSlots(): IntArray =
        intArrayOf(SLOT_SIMPLE, SLOT_SET_0, SLOT_SET_1, SLOT_FORCING, SLOT_RECURSIVE)

    /**
     * `solver`, `keen.c:690-747`: builds the box context and calls the
     * Latin solver with Keen's difficulty slots. The solution is written
     * back into [soln].
     */
    private fun solver(
        w: Int,
        dsf: Dsf,
        clues: LongArray,
        soln: IntArray,
        maxDiff: Int,
    ): Int {
        val a = w * w

        var nboxes = 0
        for (i in 0 until a) {
            if (dsf.canonify(i) == i) nboxes++
        }

        val boxes = IntArray(nboxes + 1)
        val cluesByBox = LongArray(nboxes)
        val boxlist = IntArray(a)
        val whichbox = IntArray(a)
        var n = 0
        var m = 0
        for (i in 0 until a) {
            if (dsf.minimal(i) == i) {
                cluesByBox[n] = clues[i]
                boxes[n] = m
                for (j in 0 until a) {
                    if (dsf.minimal(j) == i) {
                        boxlist[m] = transpose(j, w)
                        whichbox[boxlist[m]] = n
                        m++
                    }
                }
                n++
            }
        }
        check(n == nboxes) { "keen solver box count changed" }
        check(m == a) { "keen solver lost a cell" }
        boxes[n] = m

        val ctx = SolverContext(
            w = w,
            diff = maxDiff,
            nboxes = nboxes,
            boxes = boxes,
            boxlist = boxlist,
            whichbox = whichbox,
            clues = cluesByBox,
            dscratch = IntArray(a + 1),
            iscratch = IntArray(max(a + 1, 4 * w)),
        )

        return latinSolve(
            soln,
            w,
            maxDiff,
            SLOT_SIMPLE,
            SLOT_SET_0,
            SLOT_SET_1,
            SLOT_FORCING,
            SLOT_RECURSIVE,
            KEEN_SOLVERS,
            KEEN_VALIDATOR,
            ctx,
            null,
            null,
        )
    }

    private val KEEN_SOLVERS: List<LatinUserSolver?> = listOf(
        { solver, ctx -> solverEasy(solver, ctx as SolverContext) },
        { solver, ctx -> solverCommon(solver, ctx as SolverContext, DIFF_NORMAL) },
        { solver, ctx -> solverCommon(solver, ctx as SolverContext, DIFF_HARD) },
        null,
        null,
    )

    private val KEEN_VALIDATOR: LatinValidator = { solver, ctx ->
        keenValid(solver, ctx as SolverContext)
    }

    /**
     * `solver_easy`: the easy strategy is skipped above Easy, because the
     * harder strategies are a superset of it. This is why generation
     * gates twice.
     */
    private fun solverEasy(solver: LatinSolver, ctx: SolverContext): Int {
        if (ctx.diff > DIFF_EASY) return 0
        return solverCommon(solver, ctx, DIFF_EASY)
    }

    /**
     * `solver_clue_candidate`, `keen.c:248-331`: records one candidate
     * layout of a clue box. The meaning of [SolverContext.iscratch]
     * depends on the difficulty.
     */
    private fun solverClueCandidate(
        solver: LatinSolver,
        ctx: SolverContext,
        diff: Int,
        box: Int,
    ) {
        val w = ctx.w
        val start = ctx.boxes[box]
        val n = ctx.boxes[box + 1] - start

        if (diff == DIFF_EASY) {
            var mask = 0
            for (j in 0 until n) mask = mask or (1 shl ctx.dscratch[j])
            for (j in 0 until n) ctx.iscratch[j] = ctx.iscratch[j] or mask
        } else if (diff == DIFF_NORMAL) {
            for (j in 0 until n) {
                ctx.iscratch[j] = ctx.iscratch[j] or (1 shl ctx.dscratch[j])
            }
        } else if (diff == DIFF_HARD) {
            val sq = start
            for (j in 0 until 2 * w) ctx.iscratch[2 * w + j] = 0
            for (j in 0 until n) {
                val index = ctx.boxlist[sq + j]
                val x = index / w
                val y = index % w
                ctx.iscratch[2 * w + x] =
                    ctx.iscratch[2 * w + x] or (1 shl ctx.dscratch[j])
                ctx.iscratch[3 * w + y] =
                    ctx.iscratch[3 * w + y] or (1 shl ctx.dscratch[j])
            }
            for (j in 0 until 2 * w) {
                ctx.iscratch[j] = ctx.iscratch[j] and ctx.iscratch[2 * w + j]
            }
        }
    }

    /**
     * `solver_common`, `keen.c:333-566`. Iterates the boxes in box order
     * and rules digits out. At Hard it returns as soon as one box makes a
     * cross-box deduction, so the next pass restarts with the easier
     * strategies; that early return changes the reported difficulty and
     * must stay.
     */
    private fun solverCommon(
        solver: LatinSolver,
        ctx: SolverContext,
        diff: Int,
    ): Int {
        val w = ctx.w
        var ret = 0

        for (box in 0 until ctx.nboxes) {
            val start = ctx.boxes[box]
            val n = ctx.boxes[box + 1] - start
            val value = ctx.clues[box] and CMASK.inv()
            val op = ctx.clues[box] and CMASK

            if (diff == DIFF_HARD) {
                for (i in 0 until 2 * w) {
                    ctx.iscratch[i] = (1 shl (w + 1)) - (1 shl 1)
                }
            } else {
                for (i in 0 until n) ctx.iscratch[i] = 0
            }

            when (op) {
                C_SUB, C_DIV -> {
                    // `validate_desc` makes a subtraction or division
                    // clue a domino. A clue value that carries an
                    // operation bit can still turn a larger block into
                    // subtraction or division after validation, so a
                    // non-domino here is an impossible board and not an
                    // abort. D-016 in `docs/DEVIATIONS.md`.
                    if (n != 2) return -1
                    for (i in 1..w) {
                        val j = if (op == C_SUB) i + value else i * value
                        if (j > w) break

                        val d0 = ctx.boxlist[start] * w
                        val d1 = ctx.boxlist[start + 1] * w
                        if (solver.cube[d0 + i - 1] && solver.cube[d1 + j.toInt() - 1]) {
                            ctx.dscratch[0] = i
                            ctx.dscratch[1] = j.toInt()
                            solverClueCandidate(solver, ctx, diff, box)
                        }
                        if (solver.cube[d0 + j.toInt() - 1] && solver.cube[d1 + i - 1]) {
                            ctx.dscratch[0] = j.toInt()
                            ctx.dscratch[1] = i
                            solverClueCandidate(solver, ctx, diff, box)
                        }
                    }
                }

                C_ADD, C_MUL -> {
                    var i = 0
                    ctx.dscratch[i] = 0
                    var total = value
                    while (true) {
                        if (i < n) {
                            var j = ctx.dscratch[i] + 1
                            while (j <= w) {
                                if (if (op == C_ADD) total < j else total % j != 0L) {
                                    j++
                                    continue
                                }
                                if (!solver.cube[ctx.boxlist[start + i] * w + j - 1]) {
                                    j++
                                    continue
                                }
                                var k = 0
                                while (k < i) {
                                    if (ctx.dscratch[k] == j &&
                                        (ctx.boxlist[start + k] % w ==
                                            ctx.boxlist[start + i] % w ||
                                            ctx.boxlist[start + k] / w ==
                                            ctx.boxlist[start + i] / w)
                                    ) {
                                        break
                                    }
                                    k++
                                }
                                if (k < i) {
                                    j++
                                    continue
                                }
                                break
                            }

                            if (j > w) {
                                i--
                                if (i < 0) break
                                if (op == C_ADD) {
                                    total += ctx.dscratch[i]
                                } else {
                                    total *= ctx.dscratch[i]
                                }
                            } else {
                                ctx.dscratch[i++] = j
                                if (op == C_ADD) total -= j else total /= j
                                ctx.dscratch[i] = 0
                            }
                        } else {
                            if (total == if (op == C_ADD) 0L else 1L) {
                                solverClueCandidate(solver, ctx, diff, box)
                            }
                            i--
                            if (op == C_ADD) {
                                total += ctx.dscratch[i]
                            } else {
                                total *= ctx.dscratch[i]
                            }
                        }
                    }
                }
            }

            if (diff < DIFF_HARD) {
                for (i in 0 until n) {
                    for (j in 1..w) {
                        val pos = ctx.boxlist[start + i] * w + j - 1
                        if (solver.cube[pos] && (ctx.iscratch[i] and (1 shl j)) == 0) {
                            solver.cube[pos] = false
                            ret = 1
                        }
                    }
                }
            } else {
                for (i in 0 until 2 * w) {
                    val lineStart = if (i < w) i * w else i - w
                    val step = if (i < w) 1 else w
                    for (j in 1..w) {
                        if (ctx.iscratch[i] and (1 shl j) == 0) continue
                        for (k in 0 until w) {
                            val pos = lineStart + k * step
                            if (ctx.whichbox[pos] != box &&
                                solver.cube[pos * w + j - 1]
                            ) {
                                solver.cube[pos * w + j - 1] = false
                                ret = 1
                            }
                        }
                    }
                }
                if (ret != 0) return ret
            }
        }

        return ret
    }

    /**
     * `keen_valid`, `keen.c:609-688`: checks every block against its
     * clue on the completed grid.
     *
     * C divides by a grid cell in the multiplication branch. The Latin
     * framework calls this only on a complete grid, so a zero is
     * unreachable through upstream. The port defines the unreachable
     * case as a failed clue instead of dividing by zero.
     */
    private fun keenValid(solver: LatinSolver, ctx: SolverContext): Boolean {
        val w = ctx.w
        for (box in 0 until ctx.nboxes) {
            val start = ctx.boxes[box]
            val n = ctx.boxes[box + 1] - start
            val value = ctx.clues[box] and CMASK.inv()
            val op = ctx.clues[box] and CMASK
            var fail = false

            when (op) {
                C_ADD -> {
                    var sum = 0L
                    for (i in 0 until n) {
                        sum += solver.grid[transpose(ctx.boxlist[start + i], w)]
                    }
                    fail = sum != value
                }

                C_MUL -> {
                    var remaining = value
                    for (i in 0 until n) {
                        val digit = solver.grid[transpose(ctx.boxlist[start + i], w)]
                        if (digit == 0) {
                            fail = true
                            break
                        }
                        if (remaining % digit != 0L) {
                            fail = true
                            break
                        }
                        remaining /= digit
                    }
                    if (remaining != 1L) fail = true
                }

                C_SUB -> {
                    if (n != 2) return false
                    val d0 = solver.grid[transpose(ctx.boxlist[start], w)]
                    val d1 = solver.grid[transpose(ctx.boxlist[start + 1], w)]
                    if (value != abs(d0 - d1).toLong()) fail = true
                }

                C_DIV -> {
                    if (n != 2) return false
                    val d0 = solver.grid[transpose(ctx.boxlist[start], w)]
                    val d1 = solver.grid[transpose(ctx.boxlist[start + 1], w)]
                    val num = max(d0, d1)
                    val den = min(d0, d1)
                    if (den.toLong() * value != num.toLong()) fail = true
                }
            }

            if (fail) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Game state, `keen.c:1386-1478`.
    // ------------------------------------------------------------------

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val a = w * w
        val dsf = Dsf.newMin(a)

        val parsed = parseBlockStructure(desc, 0, w, dsf)
        require(parsed.error == null) { parsed.error ?: "" }

        var index = parsed.end
        require(index < desc.length && desc[index] == ',') {
            "keen description has no comma after the block structure"
        }
        index++

        val clues = LongArray(a)
        for (i in 0 until a) {
            if (dsf.minimal(i) == i) {
                var clue: Long
                when (val c = desc.getOrNull(index)) {
                    'a' -> clue = C_ADD
                    'm' -> clue = C_MUL
                    's' -> {
                        check(dsf.size(i) == 2) {
                            "keen subtraction block is not a domino"
                        }
                        clue = C_SUB
                    }
                    'd' -> {
                        check(dsf.size(i) == 2) {
                            "keen division block is not a domino"
                        }
                        clue = C_DIV
                    }
                    else -> error("Bad description in newState: $c")
                }
                index++
                clue = clue or cAtol(desc, index)
                while (index < desc.length && desc[index].isAsciiDigit()) index++
                clues[i] = clue
            } else {
                clues[i] = 0L
            }
        }

        return State(
            params = p,
            shared = Shared(w, dsf, clues),
            grid = IntArray(a),
            pencil = IntArray(a),
            completed = false,
            cheated = false,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    override fun textFormat(state: Game.State): String = ""

    /**
     * `solve_game`, `keen.c:1480-1513`. The stored answer short-circuits
     * everything. Both solver errors return null, because the seam has no
     * error out-parameter.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveOutcome(state, auxInfo).move

    /** `solve_game` with the two C error strings (`keen.c:1480-1513`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        solveOutcome(state, auxInfo)

    private fun solveOutcome(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        if (auxInfo != null) return SolveOutcome(auxInfo, null)

        val a = s.a
        val soln = IntArray(a)
        val ret = solver(s.w, s.shared.dsf, s.shared.clues, soln, DIFFCOUNT - 1)
        if (ret == LatinSolver.DIFF_IMPOSSIBLE) {
            return SolveOutcome(null, "No solution exists for this puzzle")
        }
        if (ret == LatinSolver.DIFF_AMBIGUOUS) {
            return SolveOutcome(null, "Multiple solutions exist for this puzzle")
        }

        val out = StringBuilder(a + 1)
        out.append('S')
        for (i in 0 until a) out.append(('0'.code + soln[i]).toChar())
        return SolveOutcome(out.toString(), null)
    }

    /**
     * The difficulty of a state, as the standalone solver measures it:
     * the first level at which the solver reports at most that level.
     * Returns [DIFFCOUNT] when no level up to Unreasonable solves the
     * board, which covers both the ambiguous and the impossible case.
     * Diagnostics for tests only; it changes no game decision.
     */
    internal fun difficulty(state: Game.State): Int {
        val s = state as State
        val soln = IntArray(s.a)
        for (diff in 0 until DIFFCOUNT) {
            soln.fill(0)
            val ret = solver(s.w, s.shared.dsf, s.shared.clues, soln, diff)
            if (ret <= diff) return ret
        }
        return DIFFCOUNT
    }

    /**
     * The raw `latin_solver` result at [maxDiff]. Diagnostics for tests;
     * it distinguishes `diff_impossible` from `diff_ambiguous`, which
     * [difficulty] merges.
     */
    internal fun solveResult(state: Game.State, maxDiff: Int): Int {
        val s = state as State
        return solver(s.w, s.shared.dsf, s.shared.clues, IntArray(s.a), maxDiff)
    }

    /**
     * `check_errors`, `keen.c:1654-1760`. Fills [errors] when it is not
     * null and returns true when any check failed.
     *
     * The clue-value division here is the `d2/d1` form, not the
     * generator's `d2/d1 + d1/d2`. Both forms agree on a domino with a
     * non-zero quotient, and the generator's form is the one that also
     * handles its zero-initialised accumulator.
     */
    internal fun checkErrors(state: Game.State, errors: IntArray?): Boolean {
        val s = state as State
        val w = s.w
        val a = w * w
        val cluevals = LongArray(a)
        val full = BooleanArray(a)

        if (errors != null) {
            errors.fill(0)
            full.fill(true)
        }

        for (i in 0 until a) {
            val j = s.shared.dsf.minimal(i)
            if (j == i) {
                cluevals[i] = s.grid[i].toLong()
            } else {
                when (s.shared.clues[j] and CMASK) {
                    C_ADD -> cluevals[j] += s.grid[i]
                    C_MUL -> cluevals[j] *= s.grid[i]
                    C_SUB -> cluevals[j] = abs(cluevals[j] - s.grid[i])
                    else -> {
                        val d1 = min(cluevals[j], s.grid[i].toLong())
                        val d2 = max(cluevals[j], s.grid[i].toLong())
                        cluevals[j] = if (d1 == 0L || d2 % d1 != 0L) 0L else d2 / d1
                    }
                }
            }
            if (s.grid[i] == 0) full[j] = false
        }

        var errs = false
        for (i in 0 until a) {
            val j = s.shared.dsf.minimal(i)
            if (j == i) {
                if ((s.shared.clues[j] and CMASK.inv()) != cluevals[i]) {
                    errs = true
                    if (errors != null && full[j]) errors[j] = errors[j] or DF_ERR_CLUE
                }
            }
        }

        for (y in 0 until w) {
            var mask = 0
            var errmask = 0
            for (x in 0 until w) {
                val bit = 1 shl s.grid[y * w + x]
                errmask = errmask or (mask and bit)
                mask = mask or bit
            }
            if (mask != (1 shl (w + 1)) - (1 shl 1)) {
                errs = true
                errmask = errmask and 1.inv()
                if (errors != null) {
                    for (x in 0 until w) {
                        if (errmask and (1 shl s.grid[y * w + x]) != 0) {
                            errors[y * w + x] = errors[y * w + x] or DF_ERR_LATIN
                        }
                    }
                }
            }
        }

        for (x in 0 until w) {
            var mask = 0
            var errmask = 0
            for (y in 0 until w) {
                val bit = 1 shl s.grid[y * w + x]
                errmask = errmask or (mask and bit)
                mask = mask or bit
            }
            if (mask != (1 shl (w + 1)) - (1 shl 1)) {
                errs = true
                errmask = errmask and 1.inv()
                if (errors != null) {
                    for (y in 0 until w) {
                        if (errmask and (1 shl s.grid[y * w + x]) != 0) {
                            errors[y * w + x] = errors[y * w + x] or DF_ERR_LATIN
                        }
                    }
                }
            }
        }

        return errs
    }

    // ------------------------------------------------------------------
    // Moves, `keen.c:1762-1949`.
    // ------------------------------------------------------------------

    /**
     * `interpret_move`. Android clears `hpencil` when a highlight is
     * cancelled and keeps it when the highlight moves, which is part of
     * the compared behavior.
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
        val w = s.w
        val key = Buttons.stripModifiers(button)
        val border = tileSize / 2
        val tx = fromCoord(x, tileSize, border)
        val ty = fromCoord(y, tileSize, border)

        if (tx in 0 until w && ty in 0 until w) {
            if (key == Buttons.LEFT_BUTTON) {
                if (tx == u.hx && ty == u.hy && u.hshow) {
                    u.hpencil = !u.hpencil
                    u.hshow = true
                } else {
                    u.hx = tx
                    u.hy = ty
                    u.hshow = true
                }
                u.hcursor = false
                return MoveResult.UiUpdate
            }
            if (key == Buttons.RIGHT_BUTTON) {
                if (s.grid[ty * w + tx] == 0) {
                    if (tx == u.hx && ty == u.hy && u.hshow && u.hpencil) {
                        u.hshow = false
                        u.hpencil = false
                    } else {
                        u.hpencil = true
                        u.hx = tx
                        u.hy = ty
                        u.hshow = true
                    }
                } else {
                    u.hshow = false
                    u.hpencil = false
                }
                u.hcursor = false
                return MoveResult.UiUpdate
            }
        } else if (key == Buttons.LEFT_BUTTON || key == Buttons.RIGHT_BUTTON) {
            u.hshow = false
            u.hpencil = false
            return MoveResult.UiUpdate
        }

        if (Buttons.isCursorMove(key)) {
            u.hcursor = true
            return moveCursor(key, u, w)
        }

        if (u.hshow && key == Buttons.CURSOR_SELECT) {
            u.hpencil = !u.hpencil
            u.hcursor = true
            return MoveResult.UiUpdate
        }

        if (u.hshow &&
            ((key >= '0'.code && key <= '9'.code && key - '0'.code <= w) ||
                key == Buttons.CURSOR_SELECT2 || key == '\b'.code)
        ) {
            var n = key - '0'.code
            if (key == Buttons.CURSOR_SELECT2 || key == '\b'.code) n = 0

            val cell = u.hy * w + u.hx
            if (u.hpencil && s.grid[cell] != 0) return MoveResult.Unused

            if ((!u.hpencil || n == 0) &&
                s.grid[cell] == n && s.pencil[cell] == 0
            ) {
                if (!u.hcursor && !u.pencilKeepHighlight) {
                    u.hshow = false
                    return MoveResult.UiUpdate
                }
                return MoveResult.Unused
            }

            val move = "${if (u.hpencil && n > 0) 'P' else 'R'}${u.hx},${u.hy},$n"
            if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
            return MoveResult.Move(move)
        }

        if (key == 'M'.code || key == 'm'.code) return MoveResult.Move("M")

        return MoveResult.Unused
    }

    /**
     * `move_cursor` with `wrap = false`: the cursor clamps to the grid.
     * A hidden cursor becomes visible, and only a clamped move on an
     * already visible cursor is `MOVE_NO_EFFECT`.
     */
    private fun moveCursor(button: Int, ui: Ui, w: Int): MoveResult {
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
        ui.hx = min(max(ui.hx + dx, 0), w - 1)
        ui.hy = min(max(ui.hy + dy, 0), w - 1)

        if (!ui.hshow) {
            ui.hshow = true
            return MoveResult.UiUpdate
        }
        if (ui.hx != ox || ui.hy != oy) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the digit or the pencil marks, the block clue
     * where the block starts, then the cursor. `keen` has no givens,
     * so a value is always the player's.
     *
     * A cell click only moves the highlight and a digit comes from the
     * keypad, so no cell offers an action: digit and pencil entry stay on
     * the key path, as `singles` does in wave 2.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.w
        val t = tileSize
        val cells = gridSpeech(
            width = w,
            height = w,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val cell = y * w + x
                val digit = digitSpeech(
                    value = s.grid[cell],
                    given = false,
                    pencil = pencilDigits(s.pencil[cell], w),
                )
                val clue = if (s.shared.dsf.minimal(cell) == cell) {
                    ", " + clueSpeech(s, cell)
                } else {
                    ""
                }
                val cursor = if (u.hshow && u.hx == x && u.hy == y) ", cursor" else ""
                digit + clue + cursor
            },
        )
        return cells.capped(cells.units.getOrNull(u.hy * w + u.hx))
    }

    /** The clue of one block, in the words the board draws: `clue 12 times`. */
    private fun clueSpeech(s: State, cell: Int): String {
        val clue = s.shared.clues[cell]
        val value = clue and CMASK.inv()
        val op = when {
            s.shared.dsf.size(cell) == 1 || s.params.multiplicationOnly -> ""
            clue and CMASK == C_ADD -> " plus"
            clue and CMASK == C_SUB -> " minus"
            clue and CMASK == C_MUL -> " times"
            else -> " divided by"
        }
        return "clue $value$op"
    }

    /**
     * `execute_move`, `keen.c:1894-1949`. Completion is sticky: a later
     * move that breaks the board does not clear it.
     *
     * A `P` move with `n == 0` takes the `R` branch, so it clears the
     * cell. A `P` move on a filled cell is accepted, although
     * `interpret_move` never produces one.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.w
        val a = w * w

        if (move.isEmpty()) return null

        if (move[0] == 'S') {
            if (move.length != a + 1) return null
            for (i in 0 until a) {
                val c = move[i + 1]
                if (c < '1' || c > '0' + w) return null
            }
            val ret = s.copy() as State
            ret.completed = true
            ret.cheated = true
            for (i in 0 until a) {
                ret.grid[i] = move[i + 1] - '0'
                ret.pencil[i] = 0
            }
            return ret
        }

        if (move[0] == 'P' || move[0] == 'R') {
            val fields = sscanfThreeInts(move.substring(1)) ?: return null
            val x = fields[0]
            val y = fields[1]
            val n = fields[2]
            if (x < 0 || x >= w || y < 0 || y >= w || n < 0 || n > w) {
                return null
            }
            val ret = s.copy() as State
            if (move[0] == 'P' && n > 0) {
                ret.pencil[y * w + x] = ret.pencil[y * w + x] xor (1 shl n)
            } else {
                ret.grid[y * w + x] = n
                ret.pencil[y * w + x] = 0
                if (!ret.completed && !checkErrors(ret, null)) {
                    ret.completed = true
                }
            }
            return ret
        }

        if (move[0] == 'M') {
            val ret = s.copy() as State
            for (i in 0 until a) {
                if (ret.grid[i] == 0) {
                    ret.pencil[i] = (1 shl (w + 1)) - (1 shl 1)
                }
            }
            return ret
        }

        return null
    }

    /**
     * `sscanf(move+1, "%d,%d,%d", ...) == 3`.
     *
     * The parse follows C: leading whitespace is skipped for each `%d`,
     * a sign is accepted, a literal comma must follow, and trailing junk
     * is ignored. A number that overflows an `int` saturates, which is
     * the port's defined result for the C undefined behavior. A
     * fractional field fails because the comma is not where the format
     * expects it.
     */
    private fun sscanfThreeInts(text: String): IntArray? {
        var index = 0
        val values = IntArray(3)
        for (field in 0..2) {
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
     * `game_changed_state`, `keen.c:1601-1620`: cancels a pencil-mode
     * mouse highlight on a now-filled cell. On Android it also clears
     * `hpencil`. The completion event needs a transition from a
     * non-completed, non-cheated state.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val s = newState as State
        if (u.hshow && u.hpencil && !u.hcursor &&
            s.grid[u.hy * s.w + u.hx] != 0
        ) {
            u.hshow = false
            u.hpencil = false
        }
        return s.completed && !s.cheated && oldState != null &&
            !(oldState as State).completed
    }

    // ------------------------------------------------------------------
    // Frontend state and keys, `keen.c:1359-1628`.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `android_cursor_visibility`, `keen.c:1573-1576`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).hshow = visible
    }

    /** `game_get_cursor_location`, `keen.c:2307-2319`. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.hshow) return null
        val border = tileSize / 2
        val extra = max(tileSize / 32, 1)
        return Game.CursorRect(
            left = border + u.hx * tileSize + 1 + extra,
            top = border + u.hy * tileSize + 1 + extra,
            width = tileSize - 1 - 2 * extra,
            height = tileSize - 1 - 2 * extra,
        )
    }

    /**
     * `game_request_keys`, `keen.c:1359-1384`. The C labels are NULL and
     * the midend replaces them with `button2label`, so the digits become
     * their character and backspace becomes `Clear`.
     */
    override fun requestKeys(params: Game.Params): KeyRequest {
        val w = (params as Params).w
        val labels = ArrayList<KeyLabel>(w + 2)
        for (i in 0 until w) {
            labels += KeyLabel('1'.code + i, "${i + 1}")
        }
        labels += KeyLabel('\b'.code, "Clear")
        labels += KeyLabel('M'.code, "Mark")
        return KeyRequest(labels, ArrowMode.ARROWS_LEFT)
    }

    /**
     * `current_key_label`, `keen.c:1622-1628`. The seam has no operation
     * for this, so it is a public helper, as in the other ports.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if (u.hshow && button == Buttons.CURSOR_SELECT) {
            return if (u.hpencil) "Ink" else "Pencil"
        }
        return ""
    }

    // ------------------------------------------------------------------
    // Palette and drawing, `keen.c:1955-2474`.
    // ------------------------------------------------------------------

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) {
            ret[COL_GRID * 3 + i] = 0.0f
            ret[COL_BLOCK_BORDER * 3 + i] = 0.0f
        }
        ret[COL_USER * 3 + 0] = 0.0f
        ret[COL_USER * 3 + 1] = 0.6f * ret[COL_BACKGROUND * 3 + 1]
        ret[COL_USER * 3 + 2] = 0.0f

        for (i in 0 until 3) {
            ret[COL_HIGHLIGHT * 3 + i] = 0.78f * ret[COL_BACKGROUND * 3 + i]
        }

        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_PENCIL * 3 + 0] = 0.5f * ret[COL_BACKGROUND * 3 + 0]
        ret[COL_PENCIL * 3 + 1] = 0.5f * ret[COL_BACKGROUND * 3 + 1]
        ret[COL_PENCIL * 3 + 2] = ret[COL_BACKGROUND * 3 + 2]
        return ret
    }

    /** `game_compute_size`, `keen.c:1957-1965`: `w*T + 2*(T/2)`. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val side = p.w * tileSize + 2 * (tileSize / 2)
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
            w = s.w,
            tiles = IntArray(s.a) { -1 },
            errors = IntArray(s.a),
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
        val w = s.w
        val t = ds.tileSize
        val border = t / 2
        val extra = max(t / 32, 1)
        val size = w * t + 2 * border

        if (!ds.started) {
            sink.rect(
                coord(0, t, border) - extra,
                coord(0, t, border) - extra,
                w * t + 1 + extra * 2,
                w * t + 1 + extra * 2,
                COL_GRID,
            )
            sink.update(0, 0, size, size)
            ds.started = true
        }

        checkErrors(s, ds.errors)

        for (y in 0 until w) {
            for (x in 0 until w) {
                val cell = y * w + x
                var tile = if (s.grid[cell] != 0) {
                    s.grid[cell]
                } else {
                    s.pencil[cell] shl DF_PENCIL_SHIFT
                }

                if (u.hshow && u.hx == x && u.hy == y) {
                    tile = tile or if (u.hpencil) DF_HIGHLIGHT_PENCIL else DF_HIGHLIGHT
                }

                tile = tile or ds.errors[cell]

                if (ds.tiles[cell] != tile) {
                    ds.tiles[cell] = tile
                    drawTile(sink, ds, s.shared, x, y, tile, s.params.multiplicationOnly)
                }
            }
        }

        outlineBlockStructure(sink, w, s.shared.dsf, t, border, COL_BLOCK_BORDER)
        sink.update(0, 0, size, size)
    }

    private fun coord(value: Int, tileSize: Int, border: Int): Int =
        value * tileSize + border

    private fun fromCoord(value: Int, tileSize: Int, border: Int): Int =
        (value + (tileSize - border)) / tileSize - 1

    /**
     * `draw_tile`, `keen.c:2040-2234`. The clip includes the block
     * merge, the pencil triangle, the four corner squares, the clue
     * text, and either the digit or the pencil marks.
     */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        shared: Shared,
        x: Int,
        y: Int,
        tile: Int,
        onlyOneOp: Boolean,
    ) {
        val w = shared.w
        val t = ds.tileSize
        val border = t / 2
        val extra = max(t / 32, 1)
        val cell = y * w + x
        val drawClue = shared.dsf.minimal(cell) == cell

        val tx = border + x * t + 1 + extra
        val ty = border + y * t + 1 + extra

        var cx = tx
        var cy = ty
        var cw = t - 1 - 2 * extra
        var ch = t - 1 - 2 * extra

        if (x > 0 && shared.dsf.equivalent(cell, cell - 1)) {
            cx -= extra
            cw += extra
        }
        if (x + 1 < w && shared.dsf.equivalent(cell, cell + 1)) {
            cw += extra
        }
        if (y > 0 && shared.dsf.equivalent(cell, cell - w)) {
            cy -= extra
            ch += extra
        }
        if (y + 1 < w && shared.dsf.equivalent(cell, cell + w)) {
            ch += extra
        }

        sink.clip(cx, cy, cw, ch)

        sink.rect(
            cx,
            cy,
            cw,
            ch,
            if (tile and DF_HIGHLIGHT != 0) COL_HIGHLIGHT else COL_BACKGROUND,
        )

        if (tile and DF_HIGHLIGHT_PENCIL != 0) {
            val coords = intArrayOf(
                cx,
                cy,
                cx + cw / 2,
                cy,
                cx,
                cy + ch / 2,
            )
            sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)
        }

        if (x > 0 && y > 0 &&
            !shared.dsf.equivalent(cell, (y - 1) * w + x - 1)
        ) {
            sink.rect(tx - extra, ty - extra, extra, extra, COL_BLOCK_BORDER)
        }
        if (x + 1 < w && y > 0 &&
            !shared.dsf.equivalent(cell, (y - 1) * w + x + 1)
        ) {
            sink.rect(tx + t - 1 - 2 * extra, ty - extra, extra, extra, COL_BLOCK_BORDER)
        }
        if (x > 0 && y + 1 < w &&
            !shared.dsf.equivalent(cell, (y + 1) * w + x - 1)
        ) {
            sink.rect(tx - extra, ty + t - 1 - 2 * extra, extra, extra, COL_BLOCK_BORDER)
        }
        if (x + 1 < w && y + 1 < w &&
            !shared.dsf.equivalent(cell, (y + 1) * w + x + 1)
        ) {
            sink.rect(
                tx + t - 1 - 2 * extra,
                ty + t - 1 - 2 * extra,
                extra,
                extra,
                COL_BLOCK_BORDER,
            )
        }

        if (drawClue) {
            val clue = shared.clues[cell]
            val cluetype = clue and CMASK
            val clueval = clue and CMASK.inv()
            val size = shared.dsf.size(cell)
            val sign = when {
                size == 1 || onlyOneOp -> ""
                cluetype == C_ADD -> "+"
                cluetype == C_SUB -> MINUS_SIGN
                cluetype == C_MUL -> TIMES_SIGN
                else -> DIVIDE_SIGN
            }
            sink.text(
                tx + extra * 2,
                ty + extra * 2 + t / 4,
                Text.FONT_VARIABLE,
                t / 4,
                Text.ALIGN_VNORMAL or Text.ALIGN_HLEFT,
                if (tile and DF_ERR_CLUE != 0) COL_ERROR else COL_BLOCK_BORDER,
                "$clueval$sign",
            )
        }

        if (tile and DF_DIGIT_MASK != 0) {
            sink.text(
                tx + t / 2,
                ty + t / 2,
                Text.FONT_VARIABLE,
                t / 2,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                if (tile and DF_ERR_LATIN != 0) COL_ERROR else COL_USER,
                "${tile and DF_DIGIT_MASK}",
            )
        } else {
            var npencil = 0
            for (i in 1..w) {
                if (tile and (1 shl (i + DF_PENCIL_SHIFT)) != 0) npencil++
            }
            if (npencil != 0) {
                val minph = 2
                var pl = tx + extra
                val pr = pl + t - extra
                var pt = ty + extra
                val pb = pt + t - extra
                if (drawClue) pt += t / 4

                var bestsize = 0.0f
                var pbest = 1
                var pw = 3
                while (pw < max(npencil, 4)) {
                    var ph = (npencil + pw - 1) / pw
                    ph = max(ph, minph)
                    val fw = (pr - pl).toFloat() / pw
                    val fh = (pb - pt).toFloat() / ph
                    val fs = min(fw, fh)
                    if (fs > bestsize) {
                        bestsize = fs
                        pbest = pw
                    }
                    pw++
                }

                pw = pbest
                var ph = (npencil + pw - 1) / pw
                ph = max(ph, minph)

                val fontsize = min((pr - pl) / pw, (pb - pt) / ph)
                pl = tx + (t - fontsize * pw) / 2
                pt = ty + (t - fontsize * ph) / 2
                if (drawClue) pt = max(pt, ty + extra * 3 + t / 4)

                var j = 0
                for (i in 1..w) {
                    if (tile and (1 shl (i + DF_PENCIL_SHIFT)) == 0) continue
                    val dx = j % pw
                    val dy = j / pw
                    sink.text(
                        pl + fontsize * (2 * dx + 1) / 2,
                        pt + fontsize * (2 * dy + 1) / 2,
                        Text.FONT_VARIABLE,
                        fontsize,
                        Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                        COL_PENCIL,
                        "$i",
                    )
                    j++
                }
            }
        }

        sink.unclip()
        sink.update(cx, cy, cw, ch)
    }

    /**
     * `outline_block_structure`, `keen.c:2349-2474`: traces the clockwise
     * perimeter of every block and draws it as one thick polygon. The
     * Android build uses `draw_thick_polygon` with thickness 3, so the
     * port uses [DrawSink.thickPoly] and not [DrawSink.poly].
     */
    private fun outlineBlockStructure(
        sink: DrawSink,
        w: Int,
        dsf: Dsf,
        tileSize: Int,
        border: Int,
        ink: Int,
    ) {
        val a = w * w
        // C allocates `4*a` ints (2*a points) and asserts that the
        // vertex count fits. A connected polyomino of at most `a` cells
        // has at most `2*a+2` boundary edges, so the port reserves that
        // exact bound and cannot reproduce the C abort on an unusual but
        // valid block.
        val coords = IntArray(4 * a + 8)

        for (i in 0 until a) {
            if (dsf.minimal(i) != i) continue

            var x = i % w
            var y = i / w
            var dx = -1
            var dy = 0
            val sx = x
            val sy = y
            val sdx = dx
            val sdy = dy
            var n = 0

            do {
                var nin = 0
                var tx = x - dy + dx
                var ty = y + dx + dy
                if (tx >= 0 && tx < w && ty >= 0 && ty < w &&
                    dsf.minimal(ty * w + tx) == i
                ) {
                    nin++
                }
                tx = x - dy
                ty = y + dx
                if (tx >= 0 && tx < w && ty >= 0 && ty < w &&
                    dsf.minimal(ty * w + tx) == i
                ) {
                    nin++
                }

                if (nin == 0) {
                    val tmp = dx
                    dx = -dy
                    dy = tmp
                } else if (nin == 2) {
                    x += dx
                    y += dy
                    val tmp = dx
                    dx = dy
                    dy = -tmp
                    x -= dx
                    y -= dy
                } else {
                    x -= dy
                    y += dx
                }

                check(x in 0 until w && y in 0 until w && dsf.minimal(y * w + x) == i) {
                    "keen block perimeter left its block"
                }

                val cx = ((2 * x + 1) + dy + dx) / 2
                val cy = ((2 * y + 1) - dx + dy) / 2
                coords[2 * n] = border + cx * tileSize
                coords[2 * n + 1] = border + cy * tileSize
                n++
            } while (x != sx || y != sy || dx != sdx || dy != sdy)

            sink.thickPoly(coords.copyOf(2 * n), 3.0f, -1, ink)
        }
    }

    // ------------------------------------------------------------------
    // C parsing helpers.
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

    /** `atol`, with the same saturating port definition as [cAtoi]. */
    private fun cAtol(text: String, start: Int): Long {
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
        if (!any) return 0L
        return if (sign < 0) -value else value
    }

    /** `shuffle` from `misc.c`: one `random_upto` per element after the first. */
    private fun shuffle(array: IntArray, random: RandomState) {
        var i = array.size
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
            }
        }
    }
}
