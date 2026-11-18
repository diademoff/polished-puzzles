package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs

/**
 * A port of upstream `dominosa.c`.
 *
 * The board is a grid of numbers, `n+2` wide and `n+1` high, and the
 * player covers it with one domino of every value from 0-0 to n-n. The
 * game logic is the C code: the same generator retry loop, the same
 * solver that gates generation, and the same save format.
 *
 * The description is one token per square: a digit for 0..9 and
 * `[number]` above 9. The generator picks a random domino layout, assigns
 * the numbers with one of three allocators, and then accepts the board
 * only when the solver reaches the requested difficulty. Every
 * `random_upto` draw and every solver rule order is therefore part of
 * the contract. Do not reorder the techniques, and keep the
 * swap-with-last compaction in [ruleOutPlacement].
 *
 * There is no `UI` record, no preferences, and no blitter. The game has
 * a solver, a text form, and a params-dependent text bound: C offers no
 * text when `n >= 1000`, and [textFormat] returns the empty string in
 * that case because the seam has no params-aware text hook.
 *
 * D-030: [newDesc] refuses a maximum number above [MAX_N] (9) before
 * the first draw. Extreme at 9 is 6.0 s on the measured phone and 10 is
 * 12.5 s; Basic stays fast above 9, but a single cap is the measured
 * rule.
 */
object Dominosa : Game {

    // Difficulty, `dominosa.c:72-86`.
    private const val DIFF_TRIVIAL = 0
    private const val DIFF_BASIC = 1
    private const val DIFF_HARD = 2
    private const val DIFF_EXTREME = 3
    private const val DIFF_AMBIGUOUS = 4
    private const val DIFFCOUNT = 5
    private val DIFF_NAMES = arrayOf("Trivial", "Basic", "Hard", "Extreme", "Ambiguous")
    private const val DIFF_CHARS = "tbhea"

    private const val PREFERRED_TILE_SIZE = 32

    /** D-030: the measured generation cap on the maximum number. */
    internal const val MAX_N = 9

    // Edge bits, `dominosa.c:111-114`.
    private const val EDGE_L = 0x100
    private const val EDGE_R = 0x200
    private const val EDGE_T = 0x400
    private const val EDGE_B = 0x800

    // Palette order, `dominosa.c:88-99`.
    private const val COL_BACKGROUND = 0
    private const val COL_TEXT = 1
    private const val COL_DOMINO = 2
    private const val COL_DOMINOCLASH = 3
    private const val COL_DOMINOTEXT = 4
    private const val COL_EDGE = 5
    private const val COL_CURSOR = 6
    private const val COL_DOMINOCURSOR = 7
    private const val COL_HIGHLIGHT_1 = 8
    private const val COL_HIGHLIGHT_2 = 9
    private const val NCOLOURS = 10

    // Tile types and draw flags, `dominosa.c:3142-3166`.
    private const val TYPE_L = 0
    private const val TYPE_R = 1
    private const val TYPE_T = 2
    private const val TYPE_B = 3
    private const val TYPE_BLANK = 4
    private const val TYPE_MASK = 0x0F

    private const val DF_HIGHLIGHT_1 = 0x10
    private const val DF_HIGHLIGHT_2 = 0x20
    private const val DF_FLASH = 0x40
    private const val DF_CLASH = 0x80
    private const val DF_CURSOR = 0x01000
    private const val DF_CURSOR_USEFUL = 0x02000
    private const val DF_CURSOR_XBASE = 0x10000
    private const val DF_CURSOR_XMASK = 0x30000
    private const val DF_CURSOR_YBASE = 0x40000
    private const val DF_CURSOR_YMASK = 0xC0000

    /** `struct game_params`. */
    data class Params(val n: Int, val diff: Int) : Game.Params {
        override fun copy(): Game.Params = Params(n, diff)
    }

    /**
     * `struct game_numbers`: the immutable clue grid, shared by every
     * state of one game. The C refcount exists only to avoid copying
     * `wh` ints per history entry; a Kotlin state shares this object.
     */
    class Numbers(val values: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Numbers && values.contentEquals(other.values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    /** `struct game_state`. */
    class State internal constructor(
        val params: Params,
        val w: Int,
        val h: Int,
        val numbers: Numbers,
        val grid: IntArray,
        val edges: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val width: Int get() = w
        val height: Int get() = h
        val wh: Int get() = w * h

        override fun copy(): Game.State = State(
            params,
            w,
            h,
            numbers,
            grid.copyOf(),
            edges.copyOf(),
            completed,
            cheated,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                w == other.w &&
                h == other.h &&
                numbers == other.numbers &&
                grid.contentEquals(other.grid) &&
                edges.contentEquals(other.edges) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + w
            result = 31 * result + h
            result = 31 * result + numbers.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + edges.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`, `dominosa.c:2734-2737`. */
    class Ui internal constructor() : Game.Ui {
        internal var curX: Int = 0
        internal var curY: Int = 0
        internal var highlight1: Int = -1
        internal var highlight2: Int = -1
        internal var curVisible: Boolean = false
    }

    /** `struct game_drawstate`, `dominosa.c:2804-2807`. */
    class DrawState internal constructor(
        val w: Int,
        val h: Int,
        val tileSize: Int,
        val visible: IntArray,
    ) : Game.DrawState

    /** One solver run: the C return value, the mark, and the technique counts. */
    internal data class SolverReport(
        val ret: Int,
        val maxDiffUsed: Int,
        val stats: SolverStats,
    )

    /**
     * Which technique group made progress, in `run_solver` order. This
     * is test-only diagnostics; it changes no decision.
     */
    internal class SolverStats {
        var trivialDomino = 0
        var trivialSquare = 0
        var basicSquareSingleDomino = 0
        var basicDominoMustOverlap = 0
        var basicLocalDuplicate = 0
        var basicLocalDuplicate2 = 0
        var basicParity = 0
        var hardSet = 0
        var extremeSet = 0
        var extremeForcingChain = 0

        fun copy(): SolverStats = SolverStats().also {
            it.trivialDomino = trivialDomino
            it.trivialSquare = trivialSquare
            it.basicSquareSingleDomino = basicSquareSingleDomino
            it.basicDominoMustOverlap = basicDominoMustOverlap
            it.basicLocalDuplicate = basicLocalDuplicate
            it.basicLocalDuplicate2 = basicLocalDuplicate2
            it.basicParity = basicParity
            it.hardSet = hardSet
            it.extremeSet = extremeSet
            it.extremeForcingChain = extremeForcingChain
        }
    }

    /** Internal retry diagnostics. They change no description. */
    internal class GenerationStats {
        var attempts = 0
        var layoutFailures = 0
        var basicGateRejects = 0
        var easyOpeningRejects = 0
        var ambiguousRejects = 0
        var tooEasyRejects = 0
    }

    internal data class Generated(
        val description: Description,
        val stats: GenerationStats,
    )

    private val PRESETS = listOf(
        Params(3, DIFF_TRIVIAL),
        Params(4, DIFF_TRIVIAL),
        Params(5, DIFF_TRIVIAL),
        Params(6, DIFF_TRIVIAL),
        Params(4, DIFF_BASIC),
        Params(5, DIFF_BASIC),
        Params(6, DIFF_BASIC),
        Params(7, DIFF_BASIC),
        Params(8, DIFF_BASIC),
        Params(9, DIFF_BASIC),
        Params(6, DIFF_HARD),
        Params(6, DIFF_EXTREME),
    )

    override val name: String = "Dominosa"
    override val backendName: String = "dominosa"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = Params(6, DIFF_BASIC)

    override fun presets(): List<Preset> = PRESETS.map { params ->
        Preset(
            "Order ${params.n}, ${DIFF_NAMES[params.diff]}",
            encodeParams(params, true),
        )
    }

    /**
     * `decode_params`. `atoi` reads the leading number, then the digit
     * skip starts at the beginning of the string, not at the end of the
     * number: a leading space or a sign therefore stops the skip.
     */
    override fun decodeParams(text: String): Game.Params {
        var diff = DIFF_BASIC
        val n = cAtoi(text, 0)
        var index = 0
        while (index < text.length && text[index].isAsciiDigit()) index++

        while (index < text.length) {
            val c = text[index]
            index++
            if (c == 'a') {
                diff = DIFF_AMBIGUOUS
            } else if (c == 'd') {
                diff = DIFFCOUNT + 1
                if (index < text.length) {
                    val next = text[index]
                    for (i in 0 until DIFFCOUNT) {
                        if (next == DIFF_CHARS[i]) diff = i
                    }
                    index++
                }
            }
        }
        return Params(n, diff)
    }

    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        if (!full) return p.n.toString()
        val ch = if (p.diff in 0 until DIFFCOUNT) DIFF_CHARS[p.diff] else '?'
        return "${p.n}d$ch"
    }

    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.n < 1) {
            return "Maximum face number must be at least one"
        }
        if (p.n > Int.MAX_VALUE - 2 ||
            (p.n + 2).toLong() > Int.MAX_VALUE.toLong() / (p.n + 1).toLong()
        ) {
            return "Maximum face number must not be unreasonably large"
        }
        if (p.diff >= DIFFCOUNT) {
            return "Unknown difficulty rating"
        }
        return null
    }

    /** `game_configure` (`dominosa.c:215-236`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Maximum number on dominoes", p.n.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Trivial:Basic:Hard:Extreme:Ambiguous",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`dominosa.c:238-246`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            (items[1] as ConfigItem.Choices).selected,
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

    /** [newDesc] with the retry counters, for the engine tests. */
    internal fun newDescWithStats(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        val p = params as Params
        require(p.n <= MAX_N) { "Maximum number is too large for this build" }
        val n = p.n
        val w = n + 2
        val h = n + 1
        val wh = w * h
        var diff = p.diff

        // `dominosa.c:2236-2250`: the small-board difficulty cap. It
        // changes the local difficulty only, so `CPARAMS` keeps the
        // requested value.
        if (diff != DIFF_AMBIGUOUS) {
            if (n == 1 && diff > DIFF_TRIVIAL) diff = DIFF_TRIVIAL
            if (n == 2 && diff > DIFF_BASIC) diff = DIFF_BASIC
        }

        val sc = SolverScratch(n)
        val asScratch = AllocScratch(n, w, h, wh)
        val stats = GenerationStats()

        while (true) {
            cancel.check()
            stats.attempts++
            asScratch.makeLayout(random)

            if (diff == DIFF_AMBIGUOUS) {
                asScratch.allocTrivial(random)
            } else if (diff < DIFF_HARD) {
                if (!asScratch.allocTryUnique(random)) {
                    stats.layoutFailures++
                    continue
                }
            } else {
                if (!asScratch.allocTryHard(random)) {
                    stats.layoutFailures++
                    continue
                }
                // `dominosa.c:2323-2336`: the Basic gate. It must still
                // leave every domino with more than one placement.
                setupGrid(sc, asScratch.numbers)
                if (runSolver(sc, DIFF_BASIC) < 2) {
                    stats.basicGateRejects++
                    continue
                }
                var ok = true
                for (di in 0 until sc.dc) {
                    if (sc.dominoNPlacements[di] <= 1) {
                        ok = false
                        break
                    }
                }
                if (!ok) {
                    stats.easyOpeningRejects++
                    continue
                }
            }

            if (diff != DIFF_AMBIGUOUS) {
                setupGrid(sc, asScratch.numbers)
                val solverResult = runSolver(sc, diff)
                if (solverResult > 1) {
                    stats.ambiguousRejects++
                    continue
                }
                if (sc.maxDiffUsed < diff) {
                    stats.tooEasyRejects++
                    continue
                }
            }
            break
        }

        val numbers = asScratch.numbers
        val layout = asScratch.layout

        // `dominosa.c:2381-2403`: the exact encoded length.
        var len = n + 1L
        var i = 10L
        while (i <= n) {
            len += maxOf(n + 1L - i, 0L)
            i *= 10
        }
        len += 2 * maxOf(n + 1L - 10, 0L)
        len *= (n + 2).toLong()

        val out = StringBuilder(len.toInt())
        for (square in 0 until wh) {
            val k = numbers[square]
            if (k < 10) {
                out.append(('0'.code + k).toChar())
            } else {
                out.append('[').append(k).append(']')
            }
        }
        check(out.length.toLong() == len) {
            "dominosa description length is ${out.length}, expected $len"
        }

        // `dominosa.c:2408-2419`: the layout as the auxiliary answer.
        val aux = StringBuilder(wh)
        for (square in 0 until wh) {
            val v = layout[square]
            aux.append(
                when (v) {
                    square + 1 -> 'L'
                    square - 1 -> 'R'
                    square + w -> 'T'
                    square - w -> 'B'
                    else -> '.'
                },
            )
        }
        return Generated(Description(out.toString(), aux.toString()), stats)
    }

    /** `struct alloc_scratch`, `dominosa.c:1839-1887`. */
    private class AllocScratch(
        val n: Int,
        val w: Int,
        val h: Int,
        val wh: Int,
    ) {
        val dc: Int = dcount(n)
        val layout = IntArray(wh)
        val numbers = IntArray(wh)
        val valsLo = IntArray(dc)
        val valsHi = IntArray(dc)
        val locs = Array(dc) { IntArray(2) }
        val whScratch = IntArray(wh)
        val wh2Scratch = IntArray(2 * wh)

        init {
            for (hi in 0..n) {
                for (lo in 0..hi) {
                    val index = dindex(hi, lo)
                    valsLo[index] = lo
                    valsHi[index] = hi
                }
            }
        }

        /** `alloc_make_layout`, `dominosa.c:1910-1928`. */
        fun makeLayout(random: RandomState) {
            LayDomino.layoutPrealloc(w, h, random, layout, whScratch, wh2Scratch)
            var pos = 0
            for (i in 0 until wh) {
                if (layout[i] > i) {
                    check(pos < dc) { "dominosa layout has too many dominoes" }
                    locs[pos][0] = i
                    locs[pos][1] = layout[i]
                    pos++
                }
            }
            check(pos == dc) { "dominosa layout has $pos dominoes, expected $dc" }
        }

        /** `alloc_trivial`, `dominosa.c:1930-1944`. */
        fun allocTrivial(random: RandomState) {
            for (i in 0 until dc) whScratch[i] = i
            shuffle(whScratch, dc, random)
            for (i in 0 until dc) {
                val value = whScratch[i]
                val whichLo = random.upto(2).toInt()
                val whichHi = 1 - whichLo
                numbers[locs[i][whichLo]] = valsLo[value]
                numbers[locs[i][whichHi]] = valsHi[value]
            }
        }

        /** `alloc_find_neighbour`, `dominosa.c:1952-1972`. */
        fun findNeighbour(p0: Int, p1: Int, out: IntArray): Boolean {
            val x0 = p0 % w
            val y0 = p0 / w
            val x1 = p1 % w
            val y1 = p1 / w
            val dy = y1 - y0
            val dx = x1 - x0
            val nx0 = x0 + dy
            val ny0 = y0 - dx
            val nx1 = x1 + dy
            val ny1 = y1 - dx
            if (!(nx0 >= 0 && nx0 < w && ny0 >= 0 && ny0 < h &&
                    nx1 >= 1 && nx1 < w && ny1 >= 1 && ny1 < h)
            ) {
                return false
            }
            val np0 = ny0 * w + nx0
            val np1 = ny1 * w + nx1
            if (layout[np0] != np1) {
                return false
            }
            out[0] = np0
            out[1] = np1
            return true
        }

        /** `alloc_try_unique`, `dominosa.c:1974-2040`. */
        fun allocTryUnique(random: RandomState): Boolean {
            for (i in 0 until dc) whScratch[i] = i
            shuffle(whScratch, dc, random)
            for (i in 0 until dc) wh2Scratch[i] = i
            shuffle(wh2Scratch, dc, random)

            for (i in 0 until wh) numbers[i] = -1

            val neighbour = IntArray(2)
            for (i in 0 until dc) {
                val value = whScratch[i]
                val loc = locs[wh2Scratch[i]]
                var canLo0 = true
                var canLo1 = true

                if (findNeighbour(loc[0], loc[1], neighbour) &&
                    (numbers[neighbour[0]] == valsHi[value] ||
                        numbers[neighbour[1]] == valsLo[value])
                ) {
                    canLo0 = false
                }
                if (findNeighbour(loc[1], loc[0], neighbour) &&
                    (numbers[neighbour[0]] == valsHi[value] ||
                        numbers[neighbour[1]] == valsLo[value])
                ) {
                    canLo1 = false
                }

                val whichLo = when {
                    !canLo0 && !canLo1 -> return false
                    canLo0 && canLo1 -> random.upto(2).toInt()
                    canLo0 -> 0
                    else -> 1
                }
                numbers[loc[whichLo]] = valsLo[value]
                numbers[loc[1 - whichLo]] = valsHi[value]
            }
            return true
        }

        /** `alloc_try_hard`, `dominosa.c:2042-2225`. */
        fun allocTryHard(random: RandomState): Boolean {
            for (i in 0 until wh) numbers[i] = -1

            for (i in 0 until dc) wh2Scratch[i] = i
            shuffle(wh2Scratch, dc, random)

            for (i in 0..n) whScratch[i] = dindex(i, i)
            shuffle(whScratch, n + 1, random)
            for (i in 0..n) {
                val loc = locs[wh2Scratch[i]]
                numbers[loc[0]] = i
                numbers[loc[1]] = i
            }

            val confounder = BooleanArray(dc)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = y * w + x
                    if (numbers[p] == -1) continue
                    if (x + 1 < w) {
                        val p1 = y * w + (x + 1)
                        if (layout[p] != p1 && numbers[p1] != -1) {
                            confounder[dindex(numbers[p], numbers[p1])] = true
                        }
                    }
                    if (y + 1 < h) {
                        val p1 = (y + 1) * w + x
                        if (layout[p] != p1 && numbers[p1] != -1) {
                            confounder[dindex(numbers[p], numbers[p1])] = true
                        }
                    }
                }
            }

            var confoundersNeeded = 0
            for (i in 0 until dc) if (!confounder[i]) confoundersNeeded++

            var vals = 0
            for (hi in 0..n) {
                for (lo in 0 until hi) {
                    whScratch[vals++] = dindex(hi, lo)
                }
            }
            shuffle(whScratch, vals, random)

            val locsCount = dc

            val dxOf = intArrayOf(1, 0, -1, 0)
            val dyOf = intArrayOf(0, 1, 0, -1)

            while (vals > 0) {
                val oldVals = vals
                var valout = 0
                for (valpos in 0 until vals) {
                    val validx = whScratch[valpos]
                    val valLo = valsLo[validx]
                    val valHi = valsHi[validx]
                    var loc: IntArray? = null
                    var whichLo = 0
                    var placed = false

                    var locpos = 0
                    while (locpos < locsCount && !placed) {
                        val locidx = wh2Scratch[locpos]
                        val candidate = locs[locidx]
                        locpos++
                        if (numbers[candidate[0]] != -1) continue

                        // `dominosa.c:2131`: the flip is drawn before the
                        // orientation checks, whether or not they accept.
                        val flip = random.upto(2).toInt()

                        var wi = 0
                        while (wi < 2) {
                            val lo = wi xor flip
                            val hi = 1 - lo

                            if (findNeighbour(candidate[lo], candidate[hi], neighbourBuffer) &&
                                (numbers[neighbourBuffer[0]] == valHi ||
                                    numbers[neighbourBuffer[1]] == valLo)
                            ) {
                                break
                            }

                            if (confoundersNeeded == 0) {
                                loc = candidate
                                whichLo = lo
                                placed = true
                                break
                            }

                            var useful = false
                            var si = 0
                            while (si < 2 && !useful) {
                                val square = candidate[si]
                                val x = square % w
                                val y = square / w
                                val value = if (si == lo) valLo else valHi
                                var d = 0
                                while (d < 4) {
                                    val x1 = x + dxOf[d]
                                    val y1 = y + dyOf[d]
                                    val p1 = y1 * w + x1
                                    if (x1 >= 0 && x1 < w && y1 >= 0 && y1 < h &&
                                        numbers[p1] != -1 &&
                                        !confounder[dindex(value, numbers[p1])]
                                    ) {
                                        useful = true
                                        break
                                    }
                                    d++
                                }
                                si++
                            }
                            if (useful) {
                                loc = candidate
                                whichLo = lo
                                placed = true
                                break
                            }
                            wi++
                        }
                    }

                    if (!placed) {
                        whScratch[valout++] = validx
                        continue
                    }

                    val chosen = checkNotNull(loc)
                    numbers[chosen[whichLo]] = valLo
                    numbers[chosen[1 - whichLo]] = valHi

                    for (si in 0..1) {
                        val p = chosen[si]
                        val value = numbers[p]
                        val x = p % w
                        val y = p / w
                        for (d in 0 until 4) {
                            val x1 = x + dxOf[d]
                            val y1 = y + dyOf[d]
                            val p1 = y1 * w + x1
                            if (x1 >= 0 && x1 < w && y1 >= 0 && y1 < h &&
                                p1 != chosen[1 - si] && numbers[p1] != -1
                            ) {
                                val di = dindex(value, numbers[p1])
                                if (!confounder[di]) confoundersNeeded--
                                confounder[di] = true
                            }
                        }
                    }
                }

                vals = valout
                if (oldVals == vals) break
            }

            for (i in 0 until dc) if (!confounder[i]) return false
            for (i in 0 until wh) if (numbers[i] == -1) return false
            return true
        }

        private val neighbourBuffer = IntArray(2)
    }

    // ------------------------------------------------------------------
    // Description validation and state.
    // ------------------------------------------------------------------

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val n = p.n
        val w = n + 2
        val h = n + 1
        val wh = w * h
        val occurrences = IntArray(n + 1)
        var index = 0
        var ret: String? = null

        for (i in 0 until wh) {
            if (index >= desc.length) {
                if (ret == null) ret = "Game description shorter than expected"
            } else {
                var j: Int
                val c = desc[index]
                if (c in '0'..'9') {
                    j = c - '0'
                    index++
                } else if (c == '[') {
                    index++
                    j = cAtoi(desc, index)
                    while (index < desc.length && desc[index].isAsciiDigit()) index++
                    if (index >= desc.length || desc[index] != ']') {
                        if (ret == null) ret = "Missing ']' in game description"
                    } else {
                        index++
                    }
                } else {
                    j = -1
                    if (ret == null) ret = "Invalid syntax in game description"
                }
                if (j < 0 || j > n) {
                    if (ret == null) ret = "Number out of range in game description"
                } else {
                    occurrences[j]++
                }
            }
        }

        if (index < desc.length) {
            if (ret == null) ret = "Game description longer than expected"
        }

        if (ret == null) {
            for (i in 0..n) {
                if (occurrences[i] != n + 2) {
                    ret = "Incorrect number balance in game description"
                    break
                }
            }
        }
        return ret
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val n = p.n
        val w = n + 2
        val h = n + 1
        val wh = w * h
        val numbers = IntArray(wh)
        var index = 0

        for (i in 0 until wh) {
            require(index < desc.length) { "dominosa description is shorter than expected" }
            val c = desc[index]
            val j: Int
            if (c in '0'..'9') {
                j = c - '0'
                index++
            } else {
                require(c == '[') { "dominosa description has invalid syntax at $index" }
                index++
                j = cAtoi(desc, index)
                while (index < desc.length && desc[index].isAsciiDigit()) index++
                require(index < desc.length && desc[index] == ']') {
                    "dominosa description is missing ']' at $index"
                }
                index++
            }
            require(j in 0..n) { "dominosa description number $j is out of range" }
            numbers[i] = j
        }
        require(index == desc.length) { "dominosa description is longer than expected" }

        return State(
            params = p,
            w = w,
            h = h,
            numbers = Numbers(numbers),
            grid = IntArray(wh) { it },
            edges = IntArray(wh),
            completed = false,
            cheated = false,
        )
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    /** `struct solver_scratch`, `dominosa.c:344-356`. */
    private class SolverScratch(val n: Int) {
        val w: Int = n + 2
        val h: Int = n + 1
        val wh: Int = w * h
        val dc: Int = dcount(n)
        val pc: Int = (w - 1) * h + w * (h - 1)

        val dominoLo = IntArray(dc)
        val dominoHi = IntArray(dc)
        val dominoIndex = IntArray(dc)
        val dominoNPlacements = IntArray(dc)
        var dominoPlacements: Array<IntArray> = Array(dc) { IntArray(0) }

        val squareNumber = IntArray(wh)
        val squareNPlacements = IntArray(wh)
        val squarePlacements = Array(wh) { IntArray(4) }

        val placementSquare0 = IntArray(pc)
        val placementSquare1 = IntArray(pc)
        val placementDomino = IntArray(pc)
        val placementSpi0 = IntArray(pc)
        val placementSpi1 = IntArray(pc)
        val placementDpi = IntArray(pc)
        val placementActive = BooleanArray(pc)
        val overlaps: Array<IntArray>

        var maxDiffUsed = DIFF_TRIVIAL

        val fls = FindLoopState(wh)

        var squaresByNumber: IntArray? = null
        var squaresByNumberInitialised = false
        val whScratch = IntArray(wh)
        val pcScratch = IntArray(pc)
        val pcScratch2: MutableList<Int> = MutableList(pc) { it }
        val dcScratch = IntArray(dc)
        var dsf: Dsf? = null

        val stats = SolverStats()

        init {
            var di = 0
            for (hi in 0..n) {
                for (lo in 0..hi) {
                    check(di == dindex(hi, lo)) { "dominosa domino index mismatch" }
                    dominoHi[di] = hi
                    dominoLo[di] = lo
                    dominoIndex[di] = di
                    di++
                }
            }

            var pi = 0
            for (y in 0 until h - 1) {
                for (x in 0 until w) {
                    placementSquare0[pi] = y * w + x
                    placementSquare1[pi] = (y + 1) * w + x
                    pi++
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w - 1) {
                    placementSquare0[pi] = y * w + x
                    placementSquare1[pi] = y * w + (x + 1)
                    pi++
                }
            }
            check(pi == pc) { "dominosa placement count mismatch" }

            for (si in 0 until wh) squareNPlacements[si] = 0
            for (p in 0 until pc) {
                for (si in 0..1) {
                    val sq = if (si == 0) placementSquare0[p] else placementSquare1[p]
                    val spi = squareNPlacements[sq]
                    if (si == 0) placementSpi0[p] = spi else placementSpi1[p] = spi
                    squarePlacements[sq][squareNPlacements[sq]++] = p
                }
            }

            overlaps = Array(pc) { p ->
                var count = 0
                for (si in 0..1) {
                    val sq = if (si == 0) placementSquare0[p] else placementSquare1[p]
                    for (j in 0 until squareNPlacements[sq]) {
                        if (squarePlacements[sq][j] != p) count++
                    }
                }
                val list = IntArray(count)
                var k = 0
                for (si in 0..1) {
                    val sq = if (si == 0) placementSquare0[p] else placementSquare1[p]
                    for (j in 0 until squareNPlacements[sq]) {
                        val q = squarePlacements[sq][j]
                        if (q != p) list[k++] = q
                    }
                }
                list
            }
        }

        fun commonSquareIndex(p: Int, q: Int): Int =
            if (placementSquare0[p] == placementSquare0[q] ||
                placementSquare0[p] == placementSquare1[q]
            ) {
                0
            } else {
                1
            }
    }

    /** `solver_setup_grid`, `dominosa.c:519-563`. */
    private fun setupGrid(sc: SolverScratch, numbers: IntArray) {
        for (i in 0 until sc.wh) {
            sc.squareNPlacements[i] = 0
            sc.squareNumber[i] = numbers[i]
        }

        for (i in 0 until sc.pc) {
            val di = dindex(sc.squareNumber[sc.placementSquare0[i]],
                sc.squareNumber[sc.placementSquare1[i]])
            sc.placementDomino[i] = di
        }

        sc.dominoNPlacements.fill(0)
        for (i in 0 until sc.pc) sc.dominoNPlacements[sc.placementDomino[i]]++
        for (i in 0 until sc.dc) {
            val count = sc.dominoNPlacements[i]
            if (sc.dominoPlacements[i].size < count) {
                sc.dominoPlacements[i] = IntArray(count)
            }
        }
        sc.dominoNPlacements.fill(0)
        for (i in 0 until sc.pc) {
            val d = sc.placementDomino[i]
            sc.placementDpi[i] = sc.dominoNPlacements[d]
            sc.dominoPlacements[d][sc.dominoNPlacements[d]++] = i
            sc.placementActive[i] = true
        }

        for (i in 0 until sc.wh) sc.squareNPlacements[i] = 0
        for (i in 0 until sc.pc) {
            for (j in 0..1) {
                val sq = if (j == 0) sc.placementSquare0[i] else sc.placementSquare1[i]
                val spi = sc.squareNPlacements[sq]
                if (j == 0) sc.placementSpi0[i] = spi else sc.placementSpi1[i] = spi
                sc.squarePlacements[sq][sc.squareNPlacements[sq]++] = i
            }
        }

        sc.maxDiffUsed = DIFF_TRIVIAL
        sc.squaresByNumberInitialised = false
    }

    /**
     * `rule_out_placement`, `dominosa.c:583-613`. The lost entry is
     * replaced by the last one, for the domino and for both squares.
     * The later techniques read `placements[0]`, so this exact
     * compaction is part of the answer.
     */
    private fun ruleOutPlacement(sc: SolverScratch, p: Int) {
        val d = sc.placementDomino[p]
        sc.placementActive[p] = false

        var i = sc.placementDpi[p]
        check(sc.dominoPlacements[d][i] == p) { "dominosa domino list mismatch" }
        sc.dominoNPlacements[d]--
        if (sc.dominoNPlacements[d] != i) {
            sc.dominoPlacements[d][i] = sc.dominoPlacements[d][sc.dominoNPlacements[d]]
            sc.placementDpi[sc.dominoPlacements[d][i]] = i
        }

        for (si in 0..1) {
            val sq = if (si == 0) sc.placementSquare0[p] else sc.placementSquare1[p]
            i = if (si == 0) sc.placementSpi0[p] else sc.placementSpi1[p]
            check(sc.squarePlacements[sq][i] == p) { "dominosa square list mismatch" }
            sc.squareNPlacements[sq]--
            if (sc.squareNPlacements[sq] != i) {
                val moved = sc.squarePlacements[sq][sc.squareNPlacements[sq]]
                sc.squarePlacements[sq][i] = moved
                val j = if (sc.placementSquare0[moved] == sq) 0 else 1
                if (j == 0) sc.placementSpi0[moved] = i else sc.placementSpi1[moved] = i
            }
        }
    }

    /** `deduce_domino_single_placement`, `dominosa.c:619-646`. */
    private fun deduceDominoSinglePlacement(sc: SolverScratch, di: Int): Boolean {
        if (sc.dominoNPlacements[di] != 1) return false
        val p = sc.dominoPlacements[di][0]
        var doneSomething = false
        for (q in sc.overlaps[p]) {
            if (sc.placementActive[q]) {
                doneSomething = true
                ruleOutPlacement(sc, q)
            }
        }
        return doneSomething
    }

    /** `deduce_square_single_placement`, `dominosa.c:652-677`. */
    private fun deduceSquareSinglePlacement(sc: SolverScratch, si: Int): Boolean {
        if (sc.squareNPlacements[si] != 1) return false
        val p = sc.squarePlacements[si][0]
        val d = sc.placementDomino[p]
        if (sc.dominoNPlacements[d] <= 1) return false
        while (sc.dominoNPlacements[d] > 1) {
            val target = if (sc.dominoPlacements[d][0] == p) {
                sc.dominoPlacements[d][1]
            } else {
                sc.dominoPlacements[d][0]
            }
            ruleOutPlacement(sc, target)
        }
        return true
    }

    /** `deduce_square_single_domino`, `dominosa.c:683-719`. */
    private fun deduceSquareSingleDomino(sc: SolverScratch, si: Int): Boolean {
        if (sc.squareNPlacements[si] < 2) return false
        val d = sc.placementDomino[sc.squarePlacements[si][0]]
        for (i in 1 until sc.squareNPlacements[si]) {
            if (sc.placementDomino[sc.squarePlacements[si][i]] != d) return false
        }
        if (sc.dominoNPlacements[d] <= sc.squareNPlacements[si]) return false

        var i = sc.dominoNPlacements[d]
        while (i-- > 0) {
            val p = sc.dominoPlacements[d][i]
            if (sc.placementSquare0[p] != si && sc.placementSquare1[p] != si) {
                ruleOutPlacement(sc, p)
            }
        }
        return true
    }

    /** `deduce_domino_must_overlap`, `dominosa.c:725-786`. */
    private fun deduceDominoMustOverlap(sc: SolverScratch, di: Int): Boolean {
        if (sc.dominoNPlacements[di] < 2) return false

        val intersection = IntArray(6)
        var nintersection = 0

        var p = sc.dominoPlacements[di][0]
        for (q in sc.overlaps[p]) {
            if (sc.placementActive[q]) intersection[nintersection++] = q
        }

        for (j in 1 until sc.dominoNPlacements[di]) {
            p = sc.dominoPlacements[di][j]
            val oldN = nintersection
            nintersection = 0
            for (k in 0 until oldN) {
                var found = false
                for (q in sc.overlaps[p]) {
                    if (q == intersection[k]) {
                        found = true
                        break
                    }
                }
                if (found) intersection[nintersection++] = intersection[k]
            }
        }

        if (nintersection == 0) return false
        for (i in 0 until nintersection) ruleOutPlacement(sc, intersection[i])
        return true
    }

    /** `deduce_local_duplicate`, `dominosa.c:793-832`. */
    private fun deduceLocalDuplicate(sc: SolverScratch, pi: Int): Boolean {
        val p = pi
        if (!sc.placementActive[p]) return false
        val d = sc.placementDomino[p]

        for (q in sc.overlaps[p]) {
            if (!sc.placementActive[q]) continue
            val sq = if (sc.commonSquareIndex(q, p) == 0) {
                sc.placementSquare1[q]
            } else {
                sc.placementSquare0[q]
            }
            var bad = false
            for (j in 0 until sc.squareNPlacements[sq]) {
                val other = sc.squarePlacements[sq][j]
                if (other != q && sc.placementDomino[other] != d) {
                    bad = true
                    break
                }
            }
            if (!bad) {
                ruleOutPlacement(sc, p)
                return true
            }
        }
        return false
    }

    /** `deduce_local_duplicate_2`, `dominosa.c:841-933`. */
    private fun deduceLocalDuplicate2(sc: SolverScratch, pi: Int): Boolean {
        val p = pi
        if (!sc.placementActive[p]) return false

        for (i in sc.overlaps[p].indices) {
            val qi = sc.overlaps[p][i]
            if (!sc.placementActive[qi]) continue
            val sqi = if (sc.commonSquareIndex(qi, p) == 0) {
                sc.placementSquare1[qi]
            } else {
                sc.placementSquare0[qi]
            }

            var di = -1
            var doneQi = false
            for (k in 0 until sc.squareNPlacements[sqi]) {
                val pk = sc.squarePlacements[sqi][k]
                if (pk == qi) continue
                if (di == -1) {
                    di = sc.placementDomino[pk]
                } else if (di != sc.placementDomino[pk]) {
                    doneQi = true
                    break
                }
            }
            if (doneQi || di == -1) continue

            for (j in sc.overlaps[p].indices) {
                val qj = sc.overlaps[p][j]
                if (j == i || !sc.placementActive[qj]) continue
                val sqj = if (sc.commonSquareIndex(qj, p) == 0) {
                    sc.placementSquare1[qj]
                } else {
                    sc.placementSquare0[qj]
                }

                var foundDi = false
                var doneQj = false
                for (k in 0 until sc.squareNPlacements[sqj]) {
                    val pk = sc.squarePlacements[sqj][k]
                    if (pk == qj) continue
                    if (sc.placementDomino[pk] != di) {
                        doneQj = true
                        break
                    }
                    if (sc.placementSquare0[pk] == sqi || sc.placementSquare1[pk] == sqi) {
                        doneQj = true
                        break
                    }
                    foundDi = true
                }
                if (doneQj || !foundDi) continue

                ruleOutPlacement(sc, p)
                return true
            }
        }
        return false
    }

    /** The neighbour context of `parity_neighbour`, `dominosa.c:941-960`. */
    private class ParityContext(val sc: SolverScratch) {
        var square: Int = -1
        var index: Int = 0
    }

    private fun parityNeighbour(vertex: Int, ctx: ParityContext): Int {
        if (vertex >= 0) {
            ctx.square = vertex
            ctx.index = 0
        } else {
            check(ctx.square >= 0) { "dominosa parity iterator lost its square" }
        }
        val square = ctx.square
        if (ctx.index >= ctx.sc.squareNPlacements[square]) {
            ctx.square = -1
            return -1
        }
        val p = ctx.sc.squarePlacements[square][ctx.index++]
        return ctx.sc.placementSquare0[p] + ctx.sc.placementSquare1[p] - square
    }

    /** `deduce_parity`, `dominosa.c:968-1021`. */
    private fun deduceParity(sc: SolverScratch): Boolean {
        var doneSomething = false
        val ctx = ParityContext(sc)
        sc.fls.run { vertex -> parityNeighbour(vertex, ctx) }

        for (pi in 0 until sc.pc) {
            if (!sc.placementActive[pi]) continue
            val sides = sc.fls.isBridge(
                sc.placementSquare0[pi],
                sc.placementSquare1[pi],
            ) ?: continue
            if ((sides.uVertices or sides.vVertices) and 1 != 0) continue
            ruleOutPlacement(sc, pi)
            doneSomething = true
        }
        return doneSomething
    }

    /** `deduce_set`, `dominosa.c:1029-1383`. */
    private fun deduceSet(sc: SolverScratch, doubles: Boolean): Boolean {
        var doneSomething = false

        val squaresByNumber = sc.squaresByNumber
            ?: IntArray(sc.wh) { it }.also { sc.squaresByNumber = it }
        if (!sc.squaresByNumberInitialised) {
            for (i in 0 until sc.wh) squaresByNumber[i] = i
            // `squares_by_number_cmpfn` is a total order, so any correct
            // sort gives the same sequence.
            val sorted = (0 until sc.wh).sortedWith(
                compareBy<Int>({ sc.squareNumber[it] }, { it }),
            )
            for (i in 0 until sc.wh) squaresByNumber[i] = sorted[i]
            sc.squaresByNumberInitialised = true
        }

        val dominoSets = LongArray(16)
        val adjacent = LongArray(16)
        val ds = IntArray(64) { -1 }

        var sqp = 0
        for (num in 0..sc.n) {
            val sqs = sqp
            while (sqp < sc.wh && sc.squareNumber[squaresByNumber[sqp]] == num) sqp++
            val nsq = sqp - sqs
            if (nsq > 16) continue

            for (i in 0 until nsq) sc.whScratch[squaresByNumber[sqs + i]] = i

            adjacent.fill(0L)
            for (i in 0 until nsq) {
                val sq = squaresByNumber[sqs + i]
                var mask = 0L
                for (j in 0 until sc.squareNPlacements[sq]) {
                    val p = sc.squarePlacements[sq][j]
                    val d = sc.placementDomino[p]
                    val othernum = sc.dominoLo[d] + sc.dominoHi[d] - num
                    // C shifts an unsigned long and writes ds[othernum]
                    // into a 16-entry array. Both are undefined above 15
                    // on C. The port keeps a signed 64-bit mask and a
                    // 64-entry table; a number above 63 is "no such
                    // domino", and bit 63 is the sign bit, which still
                    // works for the bit tests.
                    if (othernum in 0..63) {
                        mask = mask or (1L shl othernum)
                        ds[othernum] = d
                    }
                    if (othernum == num) {
                        val other = sc.placementSquare0[p] + sc.placementSquare1[p] - sq
                        val i2 = sc.whScratch[other]
                        adjacent[i] = adjacent[i] or (1L shl i2)
                        adjacent[i2] = adjacent[i2] or (1L shl i)
                    }
                }
                dominoSets[i] = mask
            }

            var subsetDone = 0L
            val limit = 1L shl nsq
            var subset = 0L
            while (subset < limit) {
                if ((subset and subsetDone) != 0L) {
                    subset++
                    continue
                }

                var gotAdjacent = false
                var nsquares = 0
                var dominoes = 0L
                for (bitpos in 0 until nsq) {
                    if ((subset and (1L shl bitpos)) == 0L) continue
                    if ((adjacent[bitpos] and subset) != 0L) gotAdjacent = true
                    dominoes = dominoes or dominoSets[bitpos]
                    nsquares++
                }

                var ndominoes = 0
                for (bitpos in 0 until nsq) {
                    if ((dominoes and (1L shl bitpos)) != 0L) ndominoes++
                }

                val ruleOutNondoubles: Boolean
                val minNUsedForDouble: Int
                if (!gotAdjacent) {
                    if (ndominoes != nsquares) {
                        subset++
                        continue
                    }
                    ruleOutNondoubles = true
                    minNUsedForDouble = 1
                } else {
                    if (!doubles) {
                        subset++
                        continue
                    }
                    if (ndominoes == nsquares - 1) {
                        ruleOutNondoubles = true
                        minNUsedForDouble = 2
                    } else if (ndominoes == nsquares) {
                        ruleOutNondoubles = false
                        minNUsedForDouble = 1
                    } else {
                        subset++
                        continue
                    }
                }

                if (ndominoes <= 1 || ndominoes >= nsq - 1) {
                    subset++
                    continue
                }

                if (ruleOutNondoubles) subsetDone = subsetDone or subset

                var reported = false
                for (bitpos in 0 until nsq) {
                    if ((dominoes and (1L shl bitpos)) == 0L) continue
                    val d = ds[bitpos]
                    check(d >= 0) { "dominosa set has no domino for bit $bitpos" }

                    var i = sc.dominoNPlacements[d]
                    while (i-- > 0) {
                        val p = sc.dominoPlacements[d][i]
                        var nused = 0
                        for (si in 0..1) {
                            val sq2 = if (si == 0) {
                                sc.placementSquare0[p]
                            } else {
                                sc.placementSquare1[p]
                            }
                            if (sc.squareNumber[sq2] == num &&
                                (subset and (1L shl sc.whScratch[sq2])) != 0L
                            ) {
                                nused++
                            }
                        }

                        if (sc.dominoLo[d] == sc.dominoHi[d]) {
                            if (nused >= minNUsedForDouble) continue
                        } else {
                            if (nused > 0 || !ruleOutNondoubles) continue
                        }

                        if (!reported) {
                            reported = true
                            doneSomething = true
                            subsetDone = subsetDone or subset
                        }
                        ruleOutPlacement(sc, p)
                    }
                }
                subset++
            }
        }
        return doneSomething
    }

    /** `forcing_chain_dup_cmp`, `dominosa.c:1385-1400`. */
    private fun forcingChainDupCmp(sc: SolverScratch, a: Int, b: Int): Int {
        var ac = sc.pcScratch[a]
        var bc = sc.pcScratch[b]
        if (ac != bc) return if (ac > bc) 1 else -1
        ac = sc.dominoIndex[sc.placementDomino[a]]
        bc = sc.dominoIndex[sc.placementDomino[b]]
        if (ac != bc) return if (ac > bc) 1 else -1
        return 0
    }

    /** `forcing_chain_sq_cmp`, `dominosa.c:1402-1417`. */
    private fun forcingChainSqCmp(sc: SolverScratch, a: Int, b: Int): Int {
        var ac = sc.dominoIndex[sc.placementDomino[a]]
        var bc = sc.dominoIndex[sc.placementDomino[b]]
        if (ac != bc) return if (ac > bc) 1 else -1
        ac = sc.pcScratch[a]
        bc = sc.pcScratch[b]
        if (ac != bc) return if (ac > bc) 1 else -1
        return 0
    }

    /** `deduce_forcing_chain`, `dominosa.c:1419-1688`. */
    private fun deduceForcingChain(sc: SolverScratch): Boolean {
        var doneSomething = false

        val dsf = sc.dsf ?: Dsf.newFlip(sc.pc).also { sc.dsf = it }
        dsf.reinit()
        for (si in 0 until sc.wh) {
            if (sc.squareNPlacements[si] == 2) {
                dsf.mergeFlip(
                    sc.squarePlacements[si][0],
                    sc.squarePlacements[si][1],
                    true,
                )
            }
        }

        for (pi in 0 until sc.pc) {
            val root = dsf.canonifyFlip(pi)
            sc.pcScratch[pi] = root.root * 2 + if (root.inverse) 1 else 0
        }

        for (pi in 0 until sc.pc) sc.pcScratch2[pi] = pi
        Sort.arraySort(sc.pcScratch2) { a, b -> forcingChainDupCmp(sc, a, b) }

        var j = 0
        while (j < sc.pc) {
            var duplicatedDomino = -1
            val chain = sc.pcScratch[sc.pcScratch2[j]]
            val start = j
            while (j < sc.pc && sc.pcScratch[sc.pcScratch2[j]] == chain) j++
            val limit = j

            for (k in start until limit - 1) {
                val p = sc.placementDomino[sc.pcScratch2[k]]
                val q = sc.placementDomino[sc.pcScratch2[k + 1]]
                if (p == q) {
                    duplicatedDomino = p
                    break
                }
            }
            if (duplicatedDomino == -1) continue

            for (k in start until limit) {
                ruleOutPlacement(sc, sc.pcScratch2[k])
            }
            doneSomething = true
        }

        if (doneSomething) return true

        for (pi in 0 until sc.pc) sc.pcScratch2[pi] = pi
        Sort.arraySort(sc.pcScratch2) { a, b -> forcingChainSqCmp(sc, a, b) }

        var di = 0
        for (jj in 0 until sc.pc) {
            val domino = sc.dominoIndex[sc.placementDomino[sc.pcScratch2[jj]]]
            while (di <= domino) {
                sc.dcScratch[di++] = jj
            }
        }
        // A hand-written description can pass `validate_desc` and still
        // leave the highest-valued dominoes with no adjacent pair; C
        // aborts on its `di == dc` assert here. The port marks such a
        // domino with a start of `pc`, which the lookup below reads as
        // an empty list. No generated board reaches this path. This is
        // D-013 in `docs/DEVIATIONS.md`.
        while (di < sc.dc) sc.dcScratch[di++] = sc.pc

        for (si in 0 until sc.wh) {
            var listpos = 0
            var listsize = 0
            var listout = 0
            val exclude = IntArray(4)
            var nExclude: Int

            if (sc.squareNPlacements[si] < 2) continue

            nExclude = 0
            for (jj in 0 until sc.squareNPlacements[si]) {
                exclude[nExclude++] = sc.pcScratch[sc.squarePlacements[si][jj]]
            }

            for (jj in 0 until sc.squareNPlacements[si]) {
                val d = sc.placementDomino[sc.squarePlacements[si][jj]]
                listout = 0
                listpos = 0
                var k = sc.dcScratch[sc.dominoIndex[d]]
                while (k < sc.pc && sc.placementDomino[sc.pcScratch2[k]] == d) {
                    val p2 = sc.pcScratch2[k]
                    k++
                    if (!sc.placementActive[p2]) continue
                    val chain = sc.pcScratch[p2]
                    var keep: Boolean
                    if (jj == 0) {
                        keep = true
                    } else {
                        while (listpos < listsize && sc.whScratch[listpos] < chain) listpos++
                        keep = listpos < listsize && sc.whScratch[listpos] == chain
                    }
                    for (m in 0 until nExclude) {
                        if (chain == exclude[m]) keep = false
                    }
                    if (keep) sc.whScratch[listout++] = chain
                }
                listsize = listout
                if (listsize == 0) break
            }

            for (lp in 0 until listsize) {
                val chain = sc.whScratch[lp]
                for (pi in 0 until sc.pc) {
                    if (sc.pcScratch[pi] == chain) ruleOutPlacement(sc, pi)
                }
                doneSomething = true
            }
        }

        for (d in 0 until sc.dc) {
            if (sc.dominoNPlacements[d] <= 2) continue
            var doneThisDomino = false
            var jj = 0
            while (jj < sc.dominoNPlacements[d] - 1 && !doneThisDomino) {
                val cj = sc.pcScratch[sc.dominoPlacements[d][jj]]
                var k = jj + 1
                while (k < sc.dominoNPlacements[d]) {
                    val ck = sc.pcScratch[sc.dominoPlacements[d][k]]
                    if ((cj xor ck) == 1) {
                        var i = sc.dominoNPlacements[d]
                        while (i-- > 0) {
                            if (i != jj && i != k) {
                                ruleOutPlacement(sc, sc.dominoPlacements[d][i])
                            }
                        }
                        doneSomething = true
                        doneThisDomino = true
                        break
                    }
                    k++
                }
                jj++
            }
        }

        return doneSomething
    }

    /** `run_solver`, `dominosa.c:1699-1829`. */
    private fun runSolver(sc: SolverScratch, maxDiffAllowed: Int): Int {
        var doneSomething: Boolean
        do {
            doneSomething = false

            for (di in 0 until sc.dc) {
                if (deduceDominoSinglePlacement(sc, di)) doneSomething = true
            }
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_TRIVIAL)
                sc.stats.trivialDomino++
                continue
            }

            for (si in 0 until sc.wh) {
                if (deduceSquareSinglePlacement(sc, si)) doneSomething = true
            }
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_TRIVIAL)
                sc.stats.trivialSquare++
                continue
            }

            if (maxDiffAllowed <= DIFF_TRIVIAL) continue

            for (si in 0 until sc.wh) {
                if (deduceSquareSingleDomino(sc, si)) doneSomething = true
            }
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_BASIC)
                sc.stats.basicSquareSingleDomino++
                continue
            }

            for (di in 0 until sc.dc) {
                if (deduceDominoMustOverlap(sc, di)) doneSomething = true
            }
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_BASIC)
                sc.stats.basicDominoMustOverlap++
                continue
            }

            for (pi in 0 until sc.pc) {
                if (deduceLocalDuplicate(sc, pi)) doneSomething = true
            }
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_BASIC)
                sc.stats.basicLocalDuplicate++
                continue
            }

            for (pi in 0 until sc.pc) {
                if (deduceLocalDuplicate2(sc, pi)) doneSomething = true
            }
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_BASIC)
                sc.stats.basicLocalDuplicate2++
                continue
            }

            if (deduceParity(sc)) doneSomething = true
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_BASIC)
                sc.stats.basicParity++
                continue
            }

            if (maxDiffAllowed <= DIFF_BASIC) continue

            if (deduceSet(sc, false)) doneSomething = true
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_HARD)
                sc.stats.hardSet++
                continue
            }

            if (maxDiffAllowed <= DIFF_HARD) continue

            if (deduceSet(sc, true)) doneSomething = true
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_EXTREME)
                sc.stats.extremeSet++
                continue
            }

            if (deduceForcingChain(sc)) doneSomething = true
            if (doneSomething) {
                sc.maxDiffUsed = maxOf(sc.maxDiffUsed, DIFF_EXTREME)
                sc.stats.extremeForcingChain++
                continue
            }
        } while (doneSomething)

        for (di in 0 until sc.dc) {
            if (sc.dominoNPlacements[di] == 0) return 0
        }
        for (di in 0 until sc.dc) {
            if (sc.dominoNPlacements[di] > 1) return 2
        }
        return 1
    }

    /** `solution_move_string`, `dominosa.c:2571-2618`. */
    private fun solutionMoveString(sc: SolverScratch): String {
        val out = StringBuilder("S")
        for (pass in 0..1) {
            val type = if (pass == 0) 'E' else 'D'
            for (i in 0 until sc.pc) {
                if (pass == 0) {
                    if (sc.placementActive[i]) continue
                } else {
                    if (!sc.placementActive[i] ||
                        sc.dominoNPlacements[sc.placementDomino[i]] > 1
                    ) {
                        continue
                    }
                }
                out.append(';').append(type)
                    .append(sc.placementSquare0[i])
                    .append(',')
                    .append(sc.placementSquare1[i])
            }
        }
        return out.toString()
    }

    override fun solve(state: Game.State, auxInfo: String?): String? {
        val s = state as State
        val n = s.params.n
        val w = s.w
        val wh = s.wh

        if (auxInfo != null) {
            val out = StringBuilder("S")
            for (i in 0 until wh) {
                // C reads `aux[i]` without a length. The port defines a
                // character beyond the text as '.'.
                val c = if (i < auxInfo.length) auxInfo[i] else '.'
                if (c == 'L') {
                    out.append(";D").append(i).append(',').append(i + 1)
                } else if (c == 'T') {
                    out.append(";D").append(i).append(',').append(i + w)
                }
            }
            return out.toString()
        }

        val sc = SolverScratch(n)
        setupGrid(sc, s.numbers.values)
        runSolver(sc, DIFFCOUNT)
        return solutionMoveString(sc)
    }

    /** Runs the solver to completion and returns the grade, for tests. */
    internal fun analyse(state: Game.State): SolverReport {
        val s = state as State
        val sc = SolverScratch(s.params.n)
        setupGrid(sc, s.numbers.values)
        val ret = runSolver(sc, DIFFCOUNT)
        return SolverReport(ret, sc.maxDiffUsed, sc.stats.copy())
    }

    /** The per-domino and per-square placement lists in compaction order. */
    internal class PlacementLists(
        val dominoes: List<List<Int>>,
        val squares: List<List<Int>>,
    )

    /**
     * Test hook for [ruleOutPlacement]. It builds the same solver state
     * as [solve], applies [ruleOut] in order, and returns the lists that
     * the later techniques read.
     *
     * The emitted `E` list is built in placement-index order, so it
     * cannot show the internal order on its own. This hook exposes the
     * order directly, and the engine test pins the swap-with-last
     * compaction through it.
     */
    internal fun solverPlacementLists(
        n: Int,
        numbers: IntArray,
        ruleOut: IntArray,
    ): PlacementLists {
        val sc = SolverScratch(n)
        setupGrid(sc, numbers)
        for (p in ruleOut) ruleOutPlacement(sc, p)
        return PlacementLists(
            dominoes = (0 until sc.dc).map { d ->
                (0 until sc.dominoNPlacements[d]).map { sc.dominoPlacements[d][it] }
            },
            squares = (0 until sc.wh).map { square ->
                (0 until sc.squareNPlacements[square]).map { sc.squarePlacements[square][it] }
            },
        )
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val n = s.params.n
        val w = s.w
        val wh = s.wh
        val ret = s.copy() as State
        var index = 0

        while (index < move.length) {
            if (move[index] == 'S') {
                ret.cheated = true
                for (i in 0 until wh) {
                    ret.grid[i] = i
                    ret.edges[i] = 0
                }
                index++
            } else if (move[index] == 'D') {
                val parsed = parsePair(move, index + 1) ?: return null
                val d1 = parsed.first
                val d2 = parsed.second
                if (!(d1 >= 0 && d1 < wh && d2 >= 0 && d2 < wh && d1 < d2 &&
                        (d2 - d1 == 1 || d2 - d1 == w))
                ) {
                    return null
                }

                if (ret.grid[d1] == d2) {
                    check(ret.grid[d2] == d1) { "dominosa grid is not mutual" }
                    ret.grid[d1] = d1
                    ret.grid[d2] = d2
                } else {
                    var d3 = ret.grid[d1]
                    if (d3 != d1) ret.grid[d3] = d3
                    d3 = ret.grid[d2]
                    if (d3 != d2) ret.grid[d3] = d3
                    ret.grid[d1] = d2
                    ret.grid[d2] = d1

                    destroyEdges(ret, d1, w, wh)
                    destroyEdges(ret, d2, w, wh)
                }
                index = parsed.end
            } else if (move[index] == 'E') {
                val parsed = parsePair(move, index + 1) ?: return null
                val d1 = parsed.first
                val d2 = parsed.second
                if (!(d1 >= 0 && d1 < wh && d2 >= 0 && d2 < wh && d1 < d2 &&
                        ret.grid[d1] == d1 && ret.grid[d2] == d2 &&
                        (d2 - d1 == 1 || d2 - d1 == w))
                ) {
                    return null
                }

                if (d2 == d1 + 1) {
                    ret.edges[d1] = ret.edges[d1] xor EDGE_R
                    ret.edges[d2] = ret.edges[d2] xor EDGE_L
                } else {
                    ret.edges[d1] = ret.edges[d1] xor EDGE_B
                    ret.edges[d2] = ret.edges[d2] xor EDGE_T
                }
                index = parsed.end
            } else {
                return null
            }

            if (index < move.length) {
                if (move[index] != ';') return null
                index++
            }
        }

        if (!ret.completed) {
            val used = BooleanArray(dcount(n))
            var ok = 0
            for (i in 0 until wh) {
                if (ret.grid[i] > i) {
                    val n1 = ret.numbers.values[i]
                    val n2 = ret.numbers.values[ret.grid[i]]
                    val di = dindex(n1, n2)
                    if (!used[di]) {
                        used[di] = true
                        ok++
                    }
                }
            }
            if (ok == dcount(n)) ret.completed = true
        }

        return ret
    }

    /**
     * The four edge clears around one square of `D`, `dominosa.c:2949-2982`.
     * The neighbour index is guarded: the C asserts make each access
     * valid for a reachable state, and the guard only replaces a
     * hypothetical abort with no write.
     */
    private fun destroyEdges(state: State, square: Int, w: Int, wh: Int) {
        if (state.edges[square] and EDGE_L != 0) {
            val neighbour = square - 1
            if (neighbour in 0 until wh) state.edges[neighbour] =
                state.edges[neighbour] and EDGE_R.inv()
        }
        if (state.edges[square] and EDGE_R != 0) {
            val neighbour = square + 1
            if (neighbour in 0 until wh) state.edges[neighbour] =
                state.edges[neighbour] and EDGE_L.inv()
        }
        if (state.edges[square] and EDGE_T != 0) {
            val neighbour = square - w
            if (neighbour in 0 until wh) state.edges[neighbour] =
                state.edges[neighbour] and EDGE_B.inv()
        }
        if (state.edges[square] and EDGE_B != 0) {
            val neighbour = square + w
            if (neighbour in 0 until wh) state.edges[neighbour] =
                state.edges[neighbour] and EDGE_T.inv()
        }
        state.edges[square] = 0
    }

    /** `sscanf("%d,%d%n")`: two C integers separated by a comma. */
    private fun parsePair(text: String, start: Int): ParsedPair? {
        val first = cInt(text, start) ?: return null
        var index = first.end
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = cInt(text, index) ?: return null
        return ParsedPair(first.value, second.value, second.end)
    }

    private class ParsedPair(val first: Int, val second: Int, val end: Int)

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
        val h = s.h

        if (button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON) {
            val border = tileSize * 3 / 4
            val tx = fromCoord(x, border, tileSize)
            val ty = fromCoord(y, border, tileSize)
            if (tx < 0 || tx >= w || ty < 0 || ty >= h) return MoveResult.Unused
            val t = ty * w + tx

            val dx = 2 * (x - (tx * tileSize + border)) - tileSize
            val dy = 2 * (y - (ty * tileSize + border)) - tileSize

            val d1: Int
            val d2: Int
            if (abs(dx) > abs(dy) && dx < 0 && tx > 0) {
                d1 = t - 1
                d2 = t
            } else if (abs(dx) > abs(dy) && dx > 0 && tx + 1 < w) {
                d1 = t
                d2 = t + 1
            } else if (abs(dy) > abs(dx) && dy < 0 && ty > 0) {
                d1 = t - w
                d2 = t
            } else if (abs(dy) > abs(dx) && dy > 0 && ty + 1 < h) {
                d1 = t
                d2 = t + w
            } else {
                return MoveResult.NoEffect
            }

            if (button == Buttons.RIGHT_BUTTON &&
                (s.grid[d1] != d1 || s.grid[d2] != d2)
            ) {
                return MoveResult.NoEffect
            }

            u.curVisible = false
            val type = if (button == Buttons.RIGHT_BUTTON) 'E' else 'D'
            return MoveResult.Move("$type$d1,$d2")
        } else if (Buttons.isCursorMove(button)) {
            return moveCursor(button, u, 2 * w - 1, 2 * h - 1)
        } else if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (((u.curX xor u.curY) and 1) == 0) return MoveResult.NoEffect
            val d1 = (u.curY / 2) * w + (u.curX / 2)
            val d2 = ((u.curY + 1) / 2) * w + ((u.curX + 1) / 2)
            if (button == Buttons.CURSOR_SELECT2 &&
                (s.grid[d1] != d1 || s.grid[d2] != d2)
            ) {
                return MoveResult.NoEffect
            }
            val type = if (button == Buttons.CURSOR_SELECT2) 'E' else 'D'
            return MoveResult.Move("$type$d1,$d2")
        } else {
            val bare = button and Buttons.MOD_NUM_KEYPAD.inv()
            // `isdigit` above 255 is undefined in C. The port defines an
            // ASCII digit after the keypad mask, which is the only value
            // the midend can send here.
            if (bare in '0'.code..'9'.code) {
                val num = bare - '0'.code
                if (num > s.params.n) return MoveResult.Unused
                when {
                    u.highlight1 == num -> u.highlight1 = -1
                    u.highlight2 == num -> u.highlight2 = -1
                    u.highlight1 == -1 -> u.highlight1 = num
                    u.highlight2 == -1 -> u.highlight2 = num
                    else -> return MoveResult.NoEffect
                }
                return MoveResult.UiUpdate
            }
        }

        return MoveResult.Unused
    }

    /** `move_cursor` with `wrap = false`, `misc.c:366-396`. */
    private fun moveCursor(button: Int, ui: Ui, maxw: Int, maxh: Int): MoveResult {
        var dx = 0
        var dy = 0
        when (button) {
            Buttons.CURSOR_UP -> dy = -1
            Buttons.CURSOR_DOWN -> dy = 1
            Buttons.CURSOR_RIGHT -> dx = 1
            Buttons.CURSOR_LEFT -> dx = -1
            else -> return MoveResult.Unused
        }
        val ox = ui.curX
        val oy = ui.curY
        ui.curX = (ui.curX + dx).coerceIn(0, maxw - 1)
        ui.curY = (ui.curY + dy).coerceIn(0, maxh - 1)
        if (!ui.curVisible) {
            ui.curVisible = true
            return MoveResult.UiUpdate
        }
        return if (ui.curX != ox || ui.curY != oy) {
            MoveResult.UiUpdate
        } else {
            MoveResult.NoEffect
        }
    }

    // ------------------------------------------------------------------
    // Status, text, keys, UI.
    // ------------------------------------------------------------------

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** No status bar text; C has `wants_statusbar` false. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val next = newState as State
        val previous = oldState as State?
        if (previous != null && !previous.completed && next.completed) {
            u.curVisible = false
        }
        // The highlights are not cleared here: C clears them in
        // `game_flash_length`, which the oracle never calls.
        return next.completed && !next.cheated && previous != null && !previous.completed
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val n = s.params.n
        if (n >= 1000) {
            // `game_can_format_as_text_now` is `n < 1000`, and the seam
            // cannot pass params to the text hook.
            return ""
        }

        val w = s.w
        val h = s.h
        val cw = 4
        val ch = 2
        val gw = cw * w + 2
        val gh = ch * h + 1
        val len = gw * gh
        val board = CharArray(len) { ' ' }

        for (r in 0 until h) {
            for (c in 0 until w) {
                val cell = r * ch * gw + cw * c
                val center = cell + gw * ch / 2 + cw / 2
                val i = r * w + c
                val num = s.numbers.values[i]

                if (num < 100) {
                    board[center] = ('0'.code + num % 10).toChar()
                    if (num >= 10) board[center - 1] = ('0'.code + num / 10).toChar()
                } else {
                    board[center + 1] = ('0'.code + num % 10).toChar()
                    board[center] = ('0'.code + num / 10 % 10).toChar()
                    board[center - 1] = ('0'.code + num / 100).toChar()
                }

                if (s.edges[i] and EDGE_L != 0) board[center - cw / 2] = '|'
                if (s.edges[i] and EDGE_R != 0) board[center + cw / 2] = '|'
                if (s.edges[i] and EDGE_T != 0) board[center - gw] = '-'
                if (s.edges[i] and EDGE_B != 0) board[center + gw] = '-'

                if (s.grid[i] == i) continue
                if (s.grid[i] < i) continue
                check(s.grid[i] == i + 1 || s.grid[i] == i + w) {
                    "dominosa text form has a non-adjacent domino"
                }
                if (s.grid[i] == i + 1) {
                    drawDomino(board, cell, '+', gw, ch, '|', 1, 2 * cw, '-')
                } else {
                    drawDomino(board, cell, '+', 1, cw, '-', gw, 2 * ch, '|')
                }
            }
            board[r * ch * gw + gw - 1] = '\n'
            board[r * ch * gw + gw + gw - 1] = '\n'
        }
        board[len - 1] = '\n'
        return String(board)
    }

    /** `draw_domino`, `dominosa.c:2667-2689`. */
    private fun drawDomino(
        board: CharArray,
        start: Int,
        corner: Char,
        dshort: Int,
        nshort: Int,
        cshort: Char,
        dlong: Int,
        nlong: Int,
        clong: Char,
    ) {
        val goShort = nshort * dshort
        val goLong = nlong * dlong
        board[start] = corner
        board[start + goShort] = corner
        board[start + goLong] = corner
        board[start + goShort + goLong] = corner

        for (i in 1 until nshort) {
            val j = start + i * dshort
            val k = start + i * dshort + goLong
            if (board[j] != corner) board[j] = cshort
            if (board[k] != corner) board[k] = cshort
        }
        for (i in 1 until nlong) {
            val j = start + i * dlong
            val k = start + i * dlong + goShort
            if (board[j] != corner) board[j] = clong
            if (board[k] != corner) board[k] = clong
        }
    }

    override fun requestKeys(params: Game.Params): KeyRequest {
        val p = params as Params
        val nOr9 = minOf(p.n, 9)
        val labels = (0..nOr9).map { i -> KeyLabel('0'.code + i, i.toString()) }
        return KeyRequest(labels, ArrowMode.ARROWS_LEFT_RIGHT)
    }

    /** `current_key_label`, `dominosa.c:2766-2791`; no shell call site yet. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) return ""
        if (((u.curX xor u.curY) and 1) == 0) return ""

        val d1 = (u.curY / 2) * s.w + (u.curX / 2)
        val d2 = ((u.curY + 1) / 2) * s.w + ((u.curX + 1) / 2)
        // The cursor space makes both indices valid; the guard only
        // replaces a hypothetical C out-of-bounds read.
        if (d1 !in 0 until s.wh || d2 !in 0 until s.wh) return ""

        if (button == Buttons.CURSOR_SELECT2 &&
            (s.grid[d1] != d1 || s.grid[d2] != d2)
        ) {
            return ""
        }
        if (button == Buttons.CURSOR_SELECT) {
            return if (s.grid[d1] == d2) "Remove" else "Place"
        }
        val edge = if (d2 == d1 + 1) EDGE_R else EDGE_B
        return if (s.edges[d1] and edge != 0) "Remove" else "Line"
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        val border = tileSize * 3 / 4
        val x = border + ((2 * u.curX + 1) * tileSize) / 4
        val y = border + ((2 * u.curY + 1) * tileSize) / 4
        val size = tileSize / 2 + 2
        return Game.CursorRect(x, y, size, size)
    }

    // ------------------------------------------------------------------
    // Geometry, palette, and drawing.
    // ------------------------------------------------------------------

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val w = p.n + 2
        val h = p.n + 1
        val border = tileSize * 3 / 4
        return Size(w * tileSize + 2 * border, h * tileSize + 2 * border)
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]

        for (i in 0 until 3) {
            ret[COL_TEXT * 3 + i] = 0.0f
            ret[COL_DOMINO * 3 + i] = 0.0f
            ret[COL_DOMINOTEXT * 3 + i] = 1.0f
            ret[COL_EDGE * 3 + i] = background[i] * 2f / 3f
        }

        ret[COL_DOMINOCLASH * 3 + 0] = 0.5f
        ret[COL_DOMINOCLASH * 3 + 1] = 0.0f
        ret[COL_DOMINOCLASH * 3 + 2] = 0.0f

        ret[COL_CURSOR * 3 + 0] = 0.0f
        ret[COL_CURSOR * 3 + 1] = 0.5f
        ret[COL_CURSOR * 3 + 2] = 0.0f

        ret[COL_DOMINOCURSOR * 3 + 0] = 0.25f
        ret[COL_DOMINOCURSOR * 3 + 1] = 1.0f
        ret[COL_DOMINOCURSOR * 3 + 2] = 0.25f

        ret[COL_HIGHLIGHT_1 * 3 + 0] = 0.85f
        ret[COL_HIGHLIGHT_1 * 3 + 1] = 0.20f
        ret[COL_HIGHLIGHT_1 * 3 + 2] = 0.20f

        ret[COL_HIGHLIGHT_2 * 3 + 0] = 0.20f
        ret[COL_HIGHLIGHT_2 * 3 + 1] = 0.65f
        ret[COL_HIGHLIGHT_2 * 3 + 2] = 0.12f
        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val wh = s.wh
        return DrawState(s.w, s.h, tileSize, IntArray(wh) { 0xFFFF })
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
        val n = s.params.n
        val w = ds.w
        val h = ds.h
        val wh = w * h

        val used = IntArray(dcount(n))
        for (i in 0 until wh) {
            if (s.grid[i] > i) {
                val n1 = s.numbers.values[i]
                val n2 = s.numbers.values[s.grid[i]]
                val di = dindex(n1, n2)
                if (used[di] < 2) used[di]++
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                var c: Int
                when (s.grid[index]) {
                    index - 1 -> c = TYPE_R
                    index + 1 -> c = TYPE_L
                    index - w -> c = TYPE_B
                    index + w -> c = TYPE_T
                    else -> c = TYPE_BLANK
                }

                val n1 = s.numbers.values[index]
                if (c != TYPE_BLANK) {
                    val n2 = s.numbers.values[s.grid[index]]
                    val di = dindex(n1, n2)
                    if (used[di] > 1) c = c or DF_CLASH
                } else {
                    c = c or s.edges[index]
                }

                if (n1 == u.highlight1) c = c or DF_HIGHLIGHT_1
                if (n1 == u.highlight2) c = c or DF_HIGHLIGHT_2

                // `flashtime` is always 0 in the seam, so DF_FLASH is
                // never set.

                if (u.curVisible) {
                    val curx = u.curX - (2 * x - 1)
                    val cury = u.curY - (2 * y - 1)
                    if (curx in 0..2 && cury in 0..2) {
                        c = c or DF_CURSOR
                        c = c or (curx * DF_CURSOR_XBASE)
                        c = c or (cury * DF_CURSOR_YBASE)
                        if (((u.curX xor u.curY) and 1) != 0) {
                            c = c or DF_CURSOR_USEFUL
                        }
                    }
                }

                if (ds.visible[index] != c) {
                    drawTile(sink, s, x, y, c, tileSize)
                    ds.visible[index] = c
                }
            }
        }
    }

    /** `draw_tile`, `dominosa.c:3171-3281`. */
    private fun drawTile(
        sink: DrawSink,
        state: State,
        x: Int,
        y: Int,
        typeFlags: Int,
        tileSize: Int,
    ) {
        val flags = typeFlags and TYPE_MASK.inv()
        val type = typeFlags and TYPE_MASK
        val border = tileSize * 3 / 4
        val cx = x * tileSize + border
        val cy = y * tileSize + border
        val gutter = tileSize / 16
        val radius = tileSize / 8
        val coffset = gutter + radius
        val cursorRadius = tileSize / 4

        var nc: Int

        sink.clip(cx, cy, tileSize, tileSize)
        sink.rect(cx, cy, tileSize, tileSize, COL_BACKGROUND)

        if (type != TYPE_BLANK) {
            val bg = if (flags and DF_CLASH != 0) COL_DOMINOCLASH else COL_DOMINO
            nc = COL_DOMINOTEXT
            // `DF_FLASH` swaps bg and nc in C; the seam has no flash
            // time, so the flag is never set and the swap is not ported.

            if (type == TYPE_L || type == TYPE_T) {
                sink.circle(cx + coffset, cy + coffset, radius, bg, bg)
            }
            if (type == TYPE_R || type == TYPE_T) {
                sink.circle(cx + tileSize - 1 - coffset, cy + coffset, radius, bg, bg)
            }
            if (type == TYPE_L || type == TYPE_B) {
                sink.circle(cx + coffset, cy + tileSize - 1 - coffset, radius, bg, bg)
            }
            if (type == TYPE_R || type == TYPE_B) {
                sink.circle(
                    cx + tileSize - 1 - coffset,
                    cy + tileSize - 1 - coffset,
                    radius,
                    bg,
                    bg,
                )
            }

            for (i in 0..1) {
                var x1 = cx + if (i != 0) gutter else coffset
                var y1 = cy + if (i != 0) coffset else gutter
                var x2 = cx + tileSize - 1 - if (i != 0) gutter else coffset
                var y2 = cy + tileSize - 1 - if (i != 0) coffset else gutter
                when (type) {
                    TYPE_L -> x2 = cx + tileSize + tileSize / 16
                    TYPE_R -> x1 = cx - tileSize / 16
                    TYPE_T -> y2 = cy + tileSize + tileSize / 16
                    TYPE_B -> y1 = cy - tileSize / 16
                }
                sink.rect(x1, y1, x2 - x1 + 1, y2 - y1 + 1, bg)
            }
        } else {
            if (flags and EDGE_T != 0) {
                sink.rect(
                    cx + gutter,
                    cy,
                    tileSize - 2 * gutter,
                    1,
                    COL_EDGE,
                )
            }
            if (flags and EDGE_B != 0) {
                sink.rect(
                    cx + gutter,
                    cy + tileSize - 1,
                    tileSize - 2 * gutter,
                    1,
                    COL_EDGE,
                )
            }
            if (flags and EDGE_L != 0) {
                sink.rect(
                    cx,
                    cy + gutter,
                    1,
                    tileSize - 2 * gutter,
                    COL_EDGE,
                )
            }
            if (flags and EDGE_R != 0) {
                sink.rect(
                    cx + tileSize - 1,
                    cy + gutter,
                    1,
                    tileSize - 2 * gutter,
                    COL_EDGE,
                )
            }
            nc = COL_TEXT
        }

        if (flags and DF_CURSOR != 0) {
            val curx = ((flags and DF_CURSOR_XMASK) / DF_CURSOR_XBASE) and 3
            val cury = ((flags and DF_CURSOR_YMASK) / DF_CURSOR_YBASE) and 3
            val ox = cx + curx * tileSize / 2
            val oy = cy + cury * tileSize / 2
            drawRectCorners(sink, ox, oy, cursorRadius, nc)
            if (flags and DF_CURSOR_USEFUL != 0) {
                drawRectCorners(sink, ox, oy, cursorRadius + 1, nc)
            }
        }

        if (flags and DF_HIGHLIGHT_1 != 0) {
            nc = COL_HIGHLIGHT_1
        } else if (flags and DF_HIGHLIGHT_2 != 0) {
            nc = COL_HIGHLIGHT_2
        }

        sink.text(
            cx + tileSize / 2,
            cy + tileSize / 2,
            Text.FONT_VARIABLE,
            tileSize / 2,
            Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
            nc,
            state.numbers.values[y * state.w + x].toString(),
        )

        sink.update(cx, cy, tileSize, tileSize)
        sink.unclip()
    }

    /** `draw_rect_corners`, the same geometry as `Signpost.kt`. */
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

    /** `FROMCOORD`, with C truncation toward zero. */
    private fun fromCoord(value: Int, border: Int, tileSize: Int): Int =
        (value - border + tileSize) / tileSize - 1

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

    /**
     * C `atoi` from [start]. A number that overflows an `int` saturates,
     * which is the port's defined result for the C undefined behavior.
     */
    private fun cAtoi(text: String, start: Int): Int {
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
        if (!any) return 0
        val signed = sign * value
        return when {
            signed > Int.MAX_VALUE -> Int.MAX_VALUE
            signed < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> signed.toInt()
        }
    }

    /** `TRI(n)`, with the 64-bit intermediate the C expression lacks. */
    private fun tri(n: Int): Int = (n.toLong() * (n + 1) / 2).toInt()

    /** `DCOUNT(n) = TRI(n+1)`. */
    private fun dcount(n: Int): Int = tri(n + 1)

    /** `DINDEX(n1, n2) = TRI(max) + min`. */
    private fun dindex(n1: Int, n2: Int): Int =
        tri(maxOf(n1, n2)) + minOf(n1, n2)
}
