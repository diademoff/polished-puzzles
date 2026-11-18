package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A port of upstream `map.c`.
 *
 * Map is a four-colouring puzzle. A `w` by `h` grid is divided into
 * `n` connected regions, and some cells are split by a diagonal. The
 * player colours each region with one of four colours, and adjacent
 * regions must differ.
 *
 * There are two independent random streams:
 *
 * - the game seed drives [newDesc]: the map generator, the
 *   four-colour backtracking, one shuffle before clue removal, and the
 *   difficulty gate;
 * - the description text seeds the second stream in [newState], which
 *   decides the diagonal smoothing and the canonical edge and region
 *   points. It changes drawing and input geometry, never `DESC`.
 *
 * Do not reorder the generator, the solver rules, the shuffles, or the
 * marker passes. Every `DESC`, `AUXINFO`, `SOLVE`, and trace depends on
 * that order.
 *
 * The C game has no text form, no status bar, no timer, no `UI` record,
 * and no animation. It has an auxiliary answer (`AUXINFO`), six presets,
 * three preferences, and one one-cell blitter.
 */
object Map : Game {

    // `map.c:52-65`.
    private const val DIFF_EASY = 0
    private const val DIFF_NORMAL = 1
    private const val DIFF_HARD = 2
    private const val DIFF_RECURSE = 3
    private const val DIFFCOUNT = 4
    private val DIFF_NAMES = arrayOf("Easy", "Normal", "Hard", "Unreasonable")
    private const val DIFF_CHARS = "enhu"

    // `FOUR` and `FIVE`, `map.c:35-38`.
    private const val FOUR = 4
    private const val FIVE = 5

    // Quadrants, `map.c:67`. The order is part of the save-free geometry.
    internal const val TE = 0
    internal const val BE = 1
    internal const val LE = 2
    internal const val RE = 3

    // Palette, `map.c:69-76`.
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_0 = 2
    private const val COL_1 = 3
    private const val COL_2 = 4
    private const val COL_3 = 5
    private const val COL_ERROR = 6
    private const val COL_ERRTEXT = 7
    private const val COL_TEXT_ON_FILLED = 8
    private const val NCOLOURS = 9

    // Labels, `map.c:78`.
    private const val NO_LABELS = 0
    private const val REGION_LABELS = 1
    private const val COLOUR_LABELS = 2

    // Flash types, `map.c:2318-2322`. The port stores the preference but
    // never draws a flash.
    private const val FLASH_CYCLIC = 0

    // Cell-word bits, `map.c:2432-2442`.
    private const val ERR_BASE = 0x00800000
    private const val ERR_MASK = -0x00800000 // 0xFF800000 as a 32-bit word
    private const val PENCIL_T_BASE = 0x00080000
    private const val PENCIL_B_BASE = 0x00008000
    private const val PENCIL_MASK = 0x007F8000
    private const val SHOW_REG_LAB = 0x00004000
    private const val SHOW_COL_LAB = 0x00002000
    private const val SHOW_LAB_MASK = 0x00006000

    private const val PREFERRED_TILE_SIZE = 20

    /**
     * The generation guard. C accepts an area up to `INT_MAX / 2` and
     * `n` up to `w*h`; `n*n` ints for the graph and `4*wh` ints for the
     * quadrants then overflow or exhaust the heap. The port accepts
     * every board below this limit and rejects a larger one with a fixed
     * message before any RNG draw or allocation. No preset is near the
     * limit. This is recorded in `docs/DEVIATIONS.md`.
     */
    internal const val MAX_AREA = 1 shl 20

    /**
     * The largest region count the port builds. A larger count would
     * allocate more than 2^24 graph entries. C accepts up to `w*h`.
     * This is the load-path limit: a hand-written save above it is
     * refused because the allocation cannot be built.
     */
    internal const val MAX_REGIONS = 4096

    /**
     * D-014: the generation region cap from the measurement. 60x60 with
     * 100 regions is 0.18 s on the measured phone; at 200 regions the
     * same seed did not finish in 30 s. The cap is generation-only, so
     * a save above it still loads while it is below [MAX_REGIONS].
     */
    internal const val MAX_GENERATION_REGIONS = 100

    internal const val AREA_TOO_LARGE = "Width times height is too large for this build"
    internal const val REGIONS_TOO_LARGE = "Too many regions for this build"

    /** `struct game_params`, `map.c:87-89`. */
    data class Params(
        val w: Int,
        val h: Int,
        val n: Int,
        val difficulty: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, n, difficulty)
    }

    /**
     * The data shared by every state of one game, the counterpart of
     * C's refcounted `struct map`. Kotlin's garbage collector replaces
     * `dup_game`'s refcount: [State.copy] shares one [Shared] instance,
     * and nothing mutates it after [newState] returns.
     *
     * [initialColouring] is the decoded clue array, C's
     * `states[0].colouring`. The seam passes only the current state to
     * [solve], so the first solve attempt needs this copy.
     */
    class Shared internal constructor(
        val params: Params,
        val map: IntArray,
        val graph: IntArray,
        val ngraph: Int,
        val immutable: BooleanArray,
        val edgex: IntArray,
        val edgey: IntArray,
        val regionx: IntArray,
        val regiony: IntArray,
        val initialColouring: IntArray,
    ) {
        val n: Int get() = params.n
    }

    /** `struct game_state`, `map.c:102-107`. */
    class State internal constructor(
        val params: Params,
        internal val shared: Shared,
        val colouring: IntArray,
        val pencil: IntArray,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        val n: Int get() = params.n

        /** `dup_game`: the shared geometry is shared, the rest is copied. */
        override fun copy(): Game.State = State(
            params = params,
            shared = shared,
            colouring = colouring.copyOf(),
            pencil = pencil.copyOf(),
            completed = completed,
            cheated = cheated,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                colouring.contentEquals(other.colouring) &&
                pencil.contentEquals(other.pencil) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + colouring.contentHashCode()
            result = 31 * result + pencil.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`, `map.c:2301-2329`. */
    class Ui : Game.Ui {
        /** -2 no drag, -1 blank, 0..3 a solid colour. */
        var dragColour: Int = -2
        /** The 4-bit pencil mask of the dragged source. */
        var dragPencil: Int = 0
        var dragX: Int = -1
        var dragY: Int = -1
        /** 0 none, 1 region labels, 2 colour labels. */
        var showLabels: Int = NO_LABELS
        /** The `stipple-style` preference: radius `T/4` instead of `T/7`. */
        var largeStipples: Boolean = false
        var curX: Int = 0
        var curY: Int = 0
        /** The last cursor button, which supplies the quadrant epsilon. */
        var curLastMove: Int = 0
        var curVisible: Boolean = false
        var curMoved: Boolean = false
        /** 0 cyclic, 1 each to white, 2 all to white. */
        var flashType: Int = FLASH_CYCLIC
    }

    /**
     * `struct game_drawstate`, `map.c:2423-2447`. [drawn] and [todraw]
     * hold one cell word each; `blitter` is the id from
     * [DrawSink.blitterNew].
     */
    class DrawState internal constructor(
        val tileSize: Int,
        val w: Int,
        val h: Int,
        val drawn: IntArray,
        val todraw: IntArray,
        var started: Boolean,
        var dragX: Int,
        var dragY: Int,
        var dragVisible: Boolean,
        val blitter: Int,
    ) : Game.DrawState

    /** `solve_game`'s two outputs: the move and the C error string. */
    data class SolveAttempt(val move: String?, val error: String?)

    /** Internal retry diagnostics. They change no description. */
    internal data class GenerationStats(
        var attempts: Int = 0,
        var gateRejections: Int = 0,
        var difficultyDrops: Int = 0,
        var finalMindiff: Int = 0,
    )

    internal data class Generated(val description: Description, val stats: GenerationStats)

    /**
     * The six presets, `map.c:126-142`. `SMALL_SCREEN` has no `#ifdef`
     * in `map.c`, so the Android build keeps all six. `PORTRAIT_SCREEN`
     * is not defined either; its 16x18 list is not compiled.
     */
    private val PRESETS = listOf(
        Params(20, 15, 30, DIFF_EASY),
        Params(20, 15, 30, DIFF_NORMAL),
        Params(20, 15, 30, DIFF_HARD),
        Params(20, 15, 30, DIFF_RECURSE),
        Params(30, 25, 75, DIFF_NORMAL),
        Params(30, 25, 75, DIFF_HARD),
    )

    override val name: String = "Map"
    override val backendName: String = "map"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /** `can_format_as_text_ever` is false and `text_format` is NULL. */
    override val canFormatAsText: Boolean = false

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = Params(20, 15, 30, DIFF_NORMAL)

    override fun presets(): List<Preset> = PRESETS.map { params ->
        Preset(
            "${params.w}x${params.h}, ${params.n} regions, " +
                DIFF_NAMES[params.difficulty],
            encodeParams(params, true),
        )
    }

    /**
     * `decode_params`, `map.c:175-205`, including the quirks:
     *
     * - the digit skip does not skip a sign or whitespace, so `-4x5`
     *   decodes to `w = h = -4` and reads no later field;
     * - `20x15n3.5` decodes `n = 3` and consumes `3.5`;
     * - a missing `n` uses `w*h/8` when the product is positive;
     * - a bare `d`, or `d` followed by an unknown character, leaves the
     *   difficulty unchanged and consumes one character.
     */
    override fun decodeParams(text: String): Game.Params {
        var params = defaultParams() as Params
        var index = 0

        fun eatNum(): Int {
            val value = cAtoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
            return value
        }

        params = params.copy(w = eatNum())
        params = params.copy(h = params.w)
        if (index < text.length && text[index] == 'x') {
            index++
            params = params.copy(h = eatNum())
        }
        if (index < text.length && text[index] == 'n') {
            index++
            params = params.copy(n = eatNum())
            while (index < text.length &&
                (text[index] == '.' || text[index] in '0'..'9')
            ) {
                index++
            }
        } else if (params.h > 0 && params.w > 0 &&
            params.w <= Int.MAX_VALUE / params.h
        ) {
            params = params.copy(n = params.w * params.h / 8)
        }
        if (index < text.length && text[index] == 'd') {
            index++
            val c = if (index < text.length) text[index] else '\u0000'
            for (i in 0 until DIFFCOUNT) {
                if (c == DIFF_CHARS[i]) params = params.copy(difficulty = i)
            }
            // C consumes exactly one character, including a NUL.
            if (index < text.length) index++
        }
        return params
    }

    /**
     * `encode_params`, `map.c:207-216`. The full form appends `d` and
     * the difficulty character. C reads `map_diffchars[diff]` with no
     * range test; the port writes a plain `d` for an out-of-range
     * difficulty, as `unruly` does for its own case.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.w).append('x').append(p.h).append('n').append(p.n)
        if (full) {
            out.append('d')
            if (p.difficulty in 0 until DIFFCOUNT) {
                out.append(DIFF_CHARS[p.difficulty])
            }
        }
        return out.toString()
    }

    /** `validate_params`, `map.c:263-274`, in the C order and wording. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 2 || p.h < 2) {
            return "Width and height must be at least two"
        }
        if (p.w > Int.MAX_VALUE / 2 / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.n < 5) {
            return "Must have at least five regions"
        }
        if (p.n > p.w * p.h) {
            return "Too many regions to fit in grid"
        }
        return null
    }

    /** The port's build limit, or null when the board is portable. */
    private fun portableBoardError(w: Int, h: Int, n: Int): String? {
        if (w.toLong() * h > MAX_AREA) return AREA_TOO_LARGE
        if (n > MAX_REGIONS) return REGIONS_TOO_LARGE
        return null
    }

    /**
     * The generation guard, called before any RNG draw or allocation.
     * It is internal so a test can pin both sides of each boundary
     * without building a board that large. The region cap is tighter
     * than the load-path [MAX_REGIONS]: D-014 makes the 100-region
     * limit generation-only, so a save above it still loads.
     */
    internal fun requirePortableBoard(w: Int, h: Int, n: Int) {
        require(w.toLong() * h <= MAX_AREA) { AREA_TOO_LARGE }
        require(n <= MAX_REGIONS) { REGIONS_TOO_LARGE }
    }

    /** The generation-only region guard of D-014. */
    internal fun requireGeneratableRegions(n: Int) {
        require(n <= MAX_GENERATION_REGIONS) { REGIONS_TOO_LARGE }
    }

    /** `game_configure` (`map.c:218-249`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Text("Regions", p.n.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Normal:Hard:Unreasonable",
                null,
                p.difficulty,
            ),
        )
    }

    /** `custom_params` (`map.c:251-261`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            cAtoi((items[1] as ConfigItem.Text).value, 0),
            cAtoi((items[2] as ConfigItem.Text).value, 0),
            (items[3] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`map.c:2366-2398`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                "Victory flash effect",
                ":Cyclic:Each to white:All to white",
                ":cyclic:each-white:all-white",
                0,
                "flash-type",
            ),
            ConfigItem.Choices(
                "Label regions",
                ":None:Region labels:Colour labels",
                ":none:regions:colours",
                0,
                "show-labels",
            ),
            ConfigItem.Choices(
                "Display style for stipple marks",
                ":Small:Large",
                ":small:large",
                0,
                "stipple-style",
            ),
        )

    /** `set_prefs` (`map.c:2400-2405`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.flashType = prefs.choice("flash-type")
        u.showLabels = prefs.choice("show-labels")
        u.largeStipples = prefs.choice("stipple-style") == 1
    }

    // ------------------------------------------------------------------
    // Description.
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
        val p = params as Params
        val w = p.w
        val h = p.h
        val n = p.n
        val wh = w * h
        requirePortableBoard(w, h, n)
        requireGeneratableRegions(n)

        val stats = GenerationStats()
        val map = IntArray(wh)
        val graph = IntArray(n * n)
        var colouring = IntArray(n)
        val colouring2 = IntArray(n)
        val regions = IntArray(n)
        val cfreq = IntArray(FOUR)

        var mindiff = p.difficulty
        var tries = 50
        stats.finalMindiff = mindiff

        var aux: String
        while (true) {
            cancel.check()
            stats.attempts++

            genmap(w, h, n, map, random)
            val ngraph = gengraph(w, h, n, map, graph)
            fourcolour(graph, n, ngraph, colouring, random)

            aux = buildString {
                for (i in 0 until n) {
                    if (colouring[i] < 0) continue
                    if (i == 0) append("S;") else append(';')
                    append(colouring[i]).append(':').append(i)
                }
            }

            // `map.c:1533-1563`: remove clues while the solver keeps the
            // answer unique, and keep one region of every colour.
            cfreq.fill(0)
            for (i in 0 until n) {
                regions[i] = i
                cfreq[colouring[i]]++
            }
            shuffle(regions, n, random)

            val scratch = SolverScratch(graph, n, ngraph)
            for (i in 0 until n) {
                val j = regions[i]
                if (cfreq[colouring[j]] == 1) continue
                colouring.copyInto(colouring2)
                colouring2[j] = -1
                val solveret = mapSolver(scratch, colouring2, p.difficulty)
                check(solveret >= 0) { "map: clue removal made the puzzle impossible" }
                if (solveret == 1) {
                    cfreq[colouring[j]]--
                    colouring[j] = -1
                }
            }

            // `map.c:1580-1601`: reject a clue set that the level below
            // the request already solves.
            colouring.copyInto(colouring2)
            if (mapSolver(scratch, colouring2, mindiff - 1) == 1) {
                stats.gateRejections++
                if (mindiff > 0 && (n < 9 || n > 2 * wh / 3)) {
                    if (tries-- <= 0) {
                        mindiff = 0
                        stats.difficultyDrops++
                    }
                }
                stats.finalMindiff = mindiff
                continue
            }
            stats.finalMindiff = mindiff
            break
        }

        val desc = encodeDesc(w, h, n, map, colouring)
        return Generated(Description(desc, aux), stats)
    }

    private fun encodeDesc(w: Int, h: Int, n: Int, map: IntArray, colouring: IntArray): String {
        val out = StringBuilder()
        var run = 1
        var pv = false

        val edges = w * (h - 1) + (w - 1) * h
        for (i in 0 until edges) {
            val x: Int
            val y: Int
            val dx: Int
            val dy: Int
            if (i < w * (h - 1)) {
                y = i / w
                x = i % w
                dx = 0
                dy = 1
            } else {
                x = (i - w * (h - 1)) / h
                y = (i - w * (h - 1)) % h
                dx = 1
                dy = 0
            }

            val v = map[y * w + x] != map[(y + dy) * w + (x + dx)]
            if (pv != v) {
                out.append(('a'.code - 1 + run).toChar())
                run = 1
                pv = v
            } else {
                // 'z' in the edge list is a run of 25 with no switch.
                if (run == 25) {
                    out.append('z')
                    run = 0
                }
                run++
            }
        }
        out.append(('a'.code - 1 + run).toChar())
        out.append(',')

        // 'z' in the clue list is a run of 26, with no switch after it.
        run = 0
        for (i in 0 until n) {
            if (colouring[i] < 0) {
                if (run == 26) {
                    out.append('z')
                    run = 0
                }
                run++
            } else {
                if (run > 0) out.append(('a'.code - 1 + run).toChar())
                out.append(('0'.code + colouring[i]).toChar())
                run = 0
            }
        }
        if (run > 0) out.append(('a'.code - 1 + run).toChar())
        return out.toString()
    }

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        // The C validator allocates `wh` ints before it parses. The port
        // rejects a board it cannot build with a fixed message.
        portableBoardError(p.w, p.h, p.n)?.let { return it }

        val parsed = parseEdgeList(p, desc, 0)
        if (parsed.error != null) return parsed.error

        var index = parsed.next
        if (index >= desc.length || desc[index] != ',') {
            return "Expected comma before clue list"
        }
        index++

        var area = 0L
        while (index < desc.length) {
            val c = desc[index]
            if (c in '0'..'3') {
                area++
            } else if (c in 'a'..'z') {
                area += c - 'a' + 1
            } else {
                return "Unexpected character in clue list"
            }
            index++
        }
        if (area < p.n) return "Too little data in clue list"
        if (area > p.n) return "Too much data in clue list"
        return null
    }

    private class EdgeParseResult(
        val error: String?,
        val next: Int,
        val map: IntArray,
    )

    /** `parse_edge_list`, `map.c:1725-1812`. */
    private fun parseEdgeList(params: Params, desc: String, start: Int): EdgeParseResult {
        val w = params.w
        val h = params.h
        val wh = w * h
        val n = params.n
        val map = IntArray(wh)
        val dsf = Dsf.new(wh)

        var pos = -1
        var state = false
        var index = start

        while (index < desc.length && desc[index] != ',') {
            val c = desc[index]
            if (c < 'a' || c > 'z') {
                return EdgeParseResult("Unexpected character in edge list", index, map)
            }
            var k = if (c == 'z') 25 else c - 'a' + 1
            while (k-- > 0) {
                val x: Int
                val y: Int
                val dx: Int
                val dy: Int
                if (pos < 0) {
                    pos++
                    continue
                } else if (pos < w * (h - 1)) {
                    y = pos / w
                    x = pos % w
                    dx = 0
                    dy = 1
                } else if (pos < 2 * wh - w - h) {
                    x = (pos - w * (h - 1)) / h
                    y = (pos - w * (h - 1)) % h
                    dx = 1
                    dy = 0
                } else {
                    return EdgeParseResult("Too much data in edge list", index, map)
                }
                if (!state) {
                    dsf.merge(y * w + x, (y + dy) * w + (x + dx))
                }
                pos++
            }
            if (c != 'z') state = !state
            index++
        }
        if (pos < 2 * wh - w - h) {
            return EdgeParseResult("Too little data in edge list", index, map)
        }

        // Number the regions by first appearance in row-major order.
        map.fill(-1)
        var count = 0
        for (i in 0 until wh) {
            val k = dsf.canonify(i)
            if (map[k] < 0) map[k] = count++
            map[i] = map[k]
        }
        if (count != n) {
            return EdgeParseResult(
                "Edge list defines the wrong number of regions",
                index,
                map,
            )
        }
        return EdgeParseResult(null, index, map)
    }

    // ------------------------------------------------------------------
    // Map generation, `map.c:427-821`.
    // ------------------------------------------------------------------

    /**
     * `extend_options`, `map.c:427-515`. With `index < 0` it returns the
     * total weight of every feasible colour; otherwise it returns the
     * colour that contains weighted slot `index`.
     */
    private fun extendOptions(
        w: Int,
        h: Int,
        n: Int,
        map: IntArray,
        x: Int,
        y: Int,
        indexIn: Int,
    ): Int {
        if (map[y * w + x] >= 0) return 0

        val col = IntArray(8)
        for (dy in -1..1) {
            for (dx in -1..1) {
                val i = if (dy < 0) 6 - dx else if (dy > 0) 2 + dx else 2 * (1 + dx)
                col[i] = if (x + dx >= 0 && x + dx < w && y + dy >= 0 && y + dy < h) {
                    map[(y + dy) * w + (x + dx)]
                } else {
                    -1
                }
            }
        }

        var total = 0
        var index = indexIn
        for (c in 0 until n) {
            var neighbours = 0
            var i = 0
            while (i < 8) {
                if (col[i] == c) neighbours++
                i += 2
            }
            if (neighbours == 0) continue

            var runs = 0
            for (j in 0 until 8) {
                if (col[j] == c && col[(j + 1) and 7] != c) runs++
            }
            if (runs > 1) continue

            val count = when (neighbours) {
                1 -> 2 // WEIGHT_INCREASED
                2 -> 3 // WEIGHT_UNCHANGED
                else -> 4 // WEIGHT_DECREASED
            }
            total += count
            if (index >= 0 && index < count) return c
            index -= count
        }
        return total
    }

    /** `genmap`, `map.c:517-606`. */
    private fun genmap(w: Int, h: Int, n: Int, map: IntArray, random: RandomState) {
        val wh = w * h
        val tmp = IntArray(wh) { it }
        map.fill(-1)

        // Place the region seeds by selecting `n` members from `tmp`.
        var k = wh
        for (i in 0 until n) {
            val j = random.upto(k.toLong()).toInt()
            map[tmp[j]] = i
            tmp[j] = tmp[--k]
        }

        // Reuse `tmp` as a cumulative frequency table.
        for (i in 0 until wh) tmp[i] = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                cfAdd(tmp, wh, y * w + x, extendOptions(w, h, n, map, x, y, -1))
            }
        }

        while (tmp[0] > 0) {
            k = random.upto(tmp[0].toLong()).toInt()
            val sq = cfWhichSym(tmp, wh, k)
            k -= cfCLookup(tmp, wh, sq)
            val x = sq % w
            val y = sq / w
            val colour = extendOptions(w, h, n, map, x, y, k)
            map[sq] = colour

            // Re-scan the up to 3x3 cells around the new one.
            for (yy in max(y - 1, 0) until min(y + 2, h)) {
                for (xx in max(x - 1, 0) until min(x + 2, w)) {
                    cfAdd(
                        tmp,
                        wh,
                        yy * w + xx,
                        -cfSLookup(tmp, wh, yy * w + xx) +
                            extendOptions(w, h, n, map, xx, yy, -1),
                    )
                }
            }
        }

        // Relabel the regions by first appearance.
        for (i in 0 until n) tmp[i] = -1
        k = 0
        for (i in 0 until wh) {
            check(map[i] >= 0) { "map: the generator left a cell empty" }
            if (tmp[map[i]] < 0) tmp[map[i]] = k++
            map[i] = tmp[map[i]]
        }
    }

    /** `cf_add`, `map.c:285-305`. */
    private fun cfAdd(table: IntArray, n: Int, symIn: Int, count: Int) {
        var sym = symIn
        var bit = 1
        while (sym != 0) {
            if (sym and bit != 0) {
                table[sym] += count
                sym = sym and bit.inv()
            }
            bit = bit shl 1
        }
        table[0] += count
    }

    /** `cf_clookup`, `map.c:307-345`. */
    private fun cfCLookup(table: IntArray, n: Int, sym: Int): Int {
        if (sym == 0) return 0
        var count = table[0]
        var bit = 1
        while (bit < n) bit = bit shl 1
        var limit = n
        while (bit > 0) {
            val index = ((sym + bit - 1) and (bit * 2 - 1).inv()) + bit
            if (index < limit) {
                count -= table[index]
                limit = index
            }
            bit = bit shr 1
        }
        return count
    }

    /** `cf_slookup`, `map.c:347-364`. */
    private fun cfSLookup(table: IntArray, n: Int, sym: Int): Int {
        var count = table[sym]
        var bit = 1
        while (sym + bit < n && sym and bit == 0) {
            count -= table[sym + bit]
            bit = bit shl 1
        }
        return count
    }

    /** `cf_whichsym`, `map.c:366-396`. */
    private fun cfWhichSym(table: IntArray, n: Int, count: Int): Int {
        var bit = 1
        while (bit < n) bit = bit shl 1
        var sym = 0
        var top = table[0]
        while (bit > 0) {
            if (sym + bit < n) {
                if (count >= top - table[sym + bit]) {
                    sym += bit
                } else {
                    top -= table[sym + bit]
                }
            }
            bit = bit shr 1
        }
        return sym
    }

    /** `gengraph`, `map.c:616-648`. */
    private fun gengraph(w: Int, h: Int, n: Int, map: IntArray, graph: IntArray): Int {
        graph.fill(0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = map[y * w + x]
                if (x + 1 < w) {
                    val vx = map[y * w + (x + 1)]
                    if (vx != v) {
                        graph[v * n + vx] = 1
                        graph[vx * n + v] = 1
                    }
                }
                if (y + 1 < h) {
                    val vy = map[(y + 1) * w + x]
                    if (vy != v) {
                        graph[v * n + vy] = 1
                        graph[vy * n + v] = 1
                    }
                }
            }
        }
        var j = 0
        for (i in 0 until n * n) {
            if (graph[i] != 0) graph[j++] = i
        }
        return j
    }

    /** `graph_edge_index`, `map.c:650-669`. */
    private fun graphEdgeIndex(
        graph: IntArray,
        n: Int,
        ngraph: Int,
        i: Int,
        j: Int,
    ): Int {
        val v = i * n + j
        var bot = -1
        var top = ngraph
        while (top - bot > 1) {
            val mid = (top + bot) / 2
            if (graph[mid] == v) {
                return mid
            } else if (graph[mid] < v) {
                bot = mid
            } else {
                top = mid
            }
        }
        return -1
    }

    private fun graphAdjacent(
        graph: IntArray,
        n: Int,
        ngraph: Int,
        i: Int,
        j: Int,
    ): Boolean = graphEdgeIndex(graph, n, ngraph, i, j) >= 0

    /** `graph_vertex_start`, `map.c:673-687`. */
    private fun graphVertexStart(graph: IntArray, n: Int, ngraph: Int, i: Int): Int {
        val v = i * n
        var bot = -1
        var top = ngraph
        while (top - bot > 1) {
            val mid = (top + bot) / 2
            if (graph[mid] < v) bot = mid else top = mid
        }
        return top
    }

    /** `fourcolour`, `map.c:795-821`. */
    private fun fourcolour(
        graph: IntArray,
        n: Int,
        ngraph: Int,
        colouring: IntArray,
        random: RandomState,
    ) {
        val scratch = IntArray(n * FIVE)
        for (i in 0 until n * FIVE) {
            scratch[i] = if (i % FIVE == FOUR) FOUR else 0
        }
        colouring.fill(-1)
        val ret = fourcolourRecurse(graph, n, ngraph, colouring, scratch, random)
        check(ret) { "map: the four-colour search failed" }
    }

    /** `fourcolour_recurse`, `map.c:697-793`. */
    private fun fourcolourRecurse(
        graph: IntArray,
        n: Int,
        ngraph: Int,
        colouring: IntArray,
        scratch: IntArray,
        random: RandomState,
    ): Boolean {
        var nfree = FIVE
        var nvert = 0
        for (i in 0 until n) {
            if (colouring[i] < 0 && scratch[i * FIVE + FOUR] <= nfree) {
                if (nfree > scratch[i * FIVE + FOUR]) {
                    nfree = scratch[i * FIVE + FOUR]
                    nvert = 0
                }
                nvert++
            }
        }
        if (nvert == 0) return true

        var j = random.upto(nvert.toLong()).toInt()
        var startVertex = -1
        for (i in 0 until n) {
            if (colouring[i] < 0 && scratch[i * FIVE + FOUR] == nfree) {
                if (j-- == 0) {
                    startVertex = i
                    break
                }
            }
        }
        check(startVertex >= 0) { "map: the four-colour vertex choice failed" }
        val start = graphVertexStart(graph, n, ngraph, startVertex)

        val cs = IntArray(FOUR)
        var ci = 0
        for (c in 0 until FOUR) {
            if (scratch[startVertex * FIVE + c] == 0) cs[ci++] = c
        }
        shuffle(cs, ci, random)

        while (ci-- > 0) {
            val c = cs[ci]
            colouring[startVertex] = c
            var jj = start
            while (jj < ngraph && graph[jj] < n * (startVertex + 1)) {
                val k = graph[jj] - startVertex * n
                if (scratch[k * FIVE + c] == 0) scratch[k * FIVE + FOUR]--
                scratch[k * FIVE + c]++
                jj++
            }
            if (fourcolourRecurse(graph, n, ngraph, colouring, scratch, random)) {
                return true
            }
            jj = start
            while (jj < ngraph && graph[jj] < n * (startVertex + 1)) {
                val k = graph[jj] - startVertex * n
                scratch[k * FIVE + c]--
                if (scratch[k * FIVE + c] == 0) scratch[k * FIVE + FOUR]++
                jj++
            }
            colouring[startVertex] = -1
        }
        return false
    }

    // ------------------------------------------------------------------
    // Solver, `map.c:827-1402`.
    // ------------------------------------------------------------------

    private class SolverScratch(
        val graph: IntArray,
        val n: Int,
        val ngraph: Int,
        var depth: Int = 0,
    ) {
        val possible = IntArray(n)
        val bfsqueue = IntArray(n)
        val bfscolour = IntArray(n)
    }

    /** `bitcount`, `map.c:867-873`: the number of set bits in four bits. */
    private fun bitcount(word: Int): Int {
        var w = word
        w = ((w and 0xA) shr 1) + (w and 0x5)
        w = ((w and 0xC) shr 2) + (w and 0x3)
        return w
    }

    /** `place_colour`, `map.c:888-931`. */
    private fun placeColour(
        sc: SolverScratch,
        colouring: IntArray,
        index: Int,
        colour: Int,
    ): Boolean {
        if (sc.possible[index] and (1 shl colour) == 0) return false
        sc.possible[index] = 1 shl colour
        colouring[index] = colour

        var j = graphVertexStart(sc.graph, sc.n, sc.ngraph, index)
        while (j < sc.ngraph && sc.graph[j] < sc.n * (index + 1)) {
            val k = sc.graph[j] - index * sc.n
            sc.possible[k] = sc.possible[k] and (1 shl colour).inv()
            j++
        }
        return true
    }

    /**
     * `map_solver`, `map.c:955-1402`. Returns 0 for an inconsistent
     * board, 1 for a solution, and 2 when it cannot decide.
     */
    private fun mapSolver(
        sc: SolverScratch,
        colouring: IntArray,
        difficulty: Int,
    ): Int {
        val n = sc.n
        val ngraph = sc.ngraph
        val graph = sc.graph
        var i: Int

        if (sc.depth == 0) {
            for (k in 0 until n) sc.possible[k] = (1 shl FOUR) - 1
            for (k in 0 until n) {
                if (colouring[k] >= 0) {
                    if (!placeColour(sc, colouring, k, colouring[k])) return 0
                }
            }
        }

        while (true) {
            var doneSomething = false

            if (difficulty < DIFF_EASY) break

            // Singles: a region with one possible colour.
            i = 0
            while (i < n) {
                if (colouring[i] < 0) {
                    val p = sc.possible[i]
                    if (p == 0) return 0
                    if (p and (p - 1) == 0) {
                        var c = 0
                        while (c < FOUR) {
                            if (p == (1 shl c)) break
                            c++
                        }
                        check(c < FOUR) { "map: the single-colour mask is not a bit" }
                        check(placeColour(sc, colouring, i, c)) {
                            "map: the single-colour placement failed"
                        }
                        doneSomething = true
                    }
                }
                i++
            }
            if (doneSomething) continue
            if (difficulty < DIFF_NORMAL) break

            // Pairs: two adjacent regions with the same two colours.
            i = 0
            while (i < ngraph) {
                val j1 = graph[i] / n
                val j2 = graph[i] % n
                if (j1 > j2) {
                    i++
                    continue
                }
                if (colouring[j1] >= 0 || colouring[j2] >= 0) {
                    i++
                    continue
                }
                if (sc.possible[j1] != sc.possible[j2]) {
                    i++
                    continue
                }
                val v = sc.possible[j1]
                var v2 = v and -v
                v2 = v and v2.inv()
                if (v2 == 0 || (v2 and (v2 - 1)) != 0) {
                    i++
                    continue
                }

                var j = graphVertexStart(graph, n, ngraph, j1)
                while (j < ngraph && graph[j] < n * (j1 + 1)) {
                    val k = graph[j] - j1 * n
                    if (graphAdjacent(graph, n, ngraph, k, j2) &&
                        (sc.possible[k] and v) != 0
                    ) {
                        sc.possible[k] = sc.possible[k] and v.inv()
                        doneSomething = true
                    }
                    j++
                }
                i++
            }
            if (doneSomething) continue
            if (difficulty < DIFF_HARD) break

            // Forcing chains: a BFS from each two-colour region.
            for (start in 0 until n) {
                if (colouring[start] >= 0 || bitcount(sc.possible[start]) != 2) continue
                for (c in 0 until FOUR) {
                    if (sc.possible[start] and (1 shl c) == 0) continue
                    val origc = 1 shl c
                    for (k in 0 until n) sc.bfscolour[k] = -1
                    var head = 0
                    var tail = 0
                    sc.bfsqueue[tail++] = start
                    sc.bfscolour[start] = sc.possible[start] and origc.inv()

                    while (head < tail) {
                        val j = sc.bfsqueue[head++]
                        val currc = sc.bfscolour[j]
                        var gi = graphVertexStart(graph, n, ngraph, j)
                        while (gi < ngraph && graph[gi] < n * (j + 1)) {
                            val k = graph[gi] - j * n
                            if (sc.bfscolour[k] < 0 &&
                                colouring[k] < 0 &&
                                bitcount(sc.possible[k]) == 2 &&
                                (sc.possible[k] and currc) != 0
                            ) {
                                sc.bfsqueue[tail++] = k
                                sc.bfscolour[k] = sc.possible[k] and currc.inv()
                            }
                            if (currc == origc &&
                                graphAdjacent(graph, n, ngraph, k, start) &&
                                (sc.possible[k] and currc) != 0
                            ) {
                                sc.possible[k] = sc.possible[k] and origc.inv()
                                doneSomething = true
                            }
                            gi++
                        }
                        check(tail <= n) { "map: the forcing-chain queue overflowed" }
                    }
                }
            }
            if (!doneSomething) break
        }

        // A complete colouring is a solution.
        i = 0
        while (i < n) {
            if (colouring[i] < 0) break
            i++
        }
        if (i == n) return 1
        if (difficulty < DIFF_RECURSE) return 2

        // Recurse on the most constrained region.
        var best = -1
        var bestc = FIVE
        for (k in 0 until n) {
            if (colouring[k] < 0) {
                val c = bitcount(sc.possible[k])
                check(c > 1) { "map: a region with one colour was not placed" }
                if (c < bestc) {
                    best = k
                    bestc = c
                }
            }
        }
        check(best >= 0) { "map: the recursion found no region" }

        val rsc = SolverScratch(graph, n, ngraph, sc.depth + 1)
        val origcolouring = colouring.copyOf()
        val subcolouring = IntArray(n)
        var alreadyGotOne = false
        var ret = 0

        for (c in 0 until FOUR) {
            if (sc.possible[best] and (1 shl c) == 0) continue
            sc.possible.copyInto(rsc.possible)
            origcolouring.copyInto(subcolouring)
            placeColour(rsc, subcolouring, best, c)
            val subret = mapSolver(rsc, subcolouring, difficulty)
            if (subret == 2 || (subret == 1 && alreadyGotOne)) {
                ret = 2
                break
            }
            if (subret == 1) {
                subcolouring.copyInto(colouring)
                alreadyGotOne = true
                ret = 1
            }
        }
        return ret
    }

    // ------------------------------------------------------------------
    // State.
    // ------------------------------------------------------------------

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val h = p.h
        val wh = w * h
        val n = p.n
        requirePortableBoard(w, h, n)

        val colouring = IntArray(n) { -1 }
        val pencil = IntArray(n)
        val immutable = BooleanArray(n)
        val quadrants = IntArray(wh * 4)

        val parsed = parseEdgeList(p, desc, 0)
        check(parsed.error == null) { "map: ${parsed.error}" }
        parsed.map.copyInto(quadrants, 0, 0, wh)
        for (i in wh until 4 * wh) quadrants[i] = quadrants[i % wh]

        var index = parsed.next
        check(index < desc.length && desc[index] == ',') {
            "map: the description has no comma before the clue list"
        }
        index++
        var pos = 0
        while (index < desc.length) {
            val c = desc[index]
            if (c in '0'..'3') {
                require(pos in 0 until n) { "map: the clue list runs past the regions" }
                colouring[pos] = c - '0'
                immutable[pos] = true
                pos++
            } else {
                check(c in 'a'..'z') { "map: unexpected character in clue list" }
                pos += c - 'a' + 1
            }
            index++
        }
        check(pos == n) { "map: the clue list covers $pos regions, not $n" }

        val graphMatrix = IntArray(n * n)
        val ngraph = gengraph(w, h, n, quadrants, graphMatrix)

        // Smooth jagged outlines with diagonals. The stream is seeded
        // from the description text, not from the game seed.
        val squares = IntArray(wh) { it }
        shuffle(squares, wh, RandomState.fromSeed(desc))
        var doneSomething: Boolean
        do {
            doneSomething = false
            for (i in 0 until wh) {
                val y = squares[i] / w
                val x = squares[i] % w
                val c = quadrants[TE * wh + y * w + x]
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) continue
                if (quadrants[TE * wh + y * w + x] !=
                    quadrants[BE * wh + y * w + x]
                ) {
                    continue
                }
                val tc = quadrants[BE * wh + (y - 1) * w + x]
                val bc = quadrants[TE * wh + (y + 1) * w + x]
                val lc = quadrants[RE * wh + y * w + (x - 1)]
                val rc = quadrants[LE * wh + y * w + (x + 1)]
                if (tc != bc && (tc == c || bc == c)) {
                    if ((lc == tc && rc == bc) || (lc == bc && rc == tc)) {
                        quadrants[TE * wh + y * w + x] = tc
                        quadrants[BE * wh + y * w + x] = bc
                        quadrants[LE * wh + y * w + x] = lc
                        quadrants[RE * wh + y * w + x] = rc
                        doneSomething = true
                    }
                }
            }
        } while (doneSomething)

        val points = canonicalPoints(w, h, n, ngraph, graphMatrix, quadrants)
        val shared = Shared(
            params = p,
            map = quadrants,
            graph = graphMatrix.copyOf(ngraph),
            ngraph = ngraph,
            immutable = immutable,
            edgex = points.edgex,
            edgey = points.edgey,
            regionx = points.regionx,
            regiony = points.regiony,
            initialColouring = colouring.copyOf(),
        )
        return State(
            params = p,
            shared = shared,
            colouring = colouring,
            pencil = pencil,
            completed = false,
            cheated = false,
        )
    }

    private class CanonicalPoints(
        val edgex: IntArray,
        val edgey: IntArray,
        val regionx: IntArray,
        val regiony: IntArray,
    )

    /**
     * The canonical edge and region points, `map.c:1991-2202`. The
     * averages are C `float` values and the distance is a `double`
     * square root of the `float` sum, so a rewrite in double precision
     * can move a point when two candidates are equidistant.
     */
    private fun canonicalPoints(
        w: Int,
        h: Int,
        n: Int,
        ngraph: Int,
        graph: IntArray,
        quadrants: IntArray,
    ): CanonicalPoints {
        val wh = w * h
        val total = ngraph + n
        val ax = FloatArray(total)
        val ay = FloatArray(total)
        val an = IntArray(total)
        val bestx = IntArray(total) { -1 }
        val besty = IntArray(total) { -1 }
        val best = FloatArray(total) { (2 * (w + h) + 1).toFloat() }

        for (pass in 0..1) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val ex = IntArray(4)
                    val ey = IntArray(4)
                    val ea = IntArray(4)
                    val eb = IntArray(4)
                    var en = 0

                    if (x + 1 < w) {
                        ea[en] = quadrants[RE * wh + y * w + x]
                        eb[en] = quadrants[LE * wh + y * w + (x + 1)]
                        ex[en] = (x + 1) * 2
                        ey[en] = y * 2 + 1
                        en++
                    }
                    if (y + 1 < h) {
                        ea[en] = quadrants[BE * wh + y * w + x]
                        eb[en] = quadrants[TE * wh + (y + 1) * w + x]
                        ex[en] = x * 2 + 1
                        ey[en] = (y + 1) * 2
                        en++
                    }
                    // The diagonal edge, even when the cell is not split.
                    ea[en] = quadrants[TE * wh + y * w + x]
                    eb[en] = quadrants[BE * wh + y * w + x]
                    ex[en] = x * 2 + 1
                    ey[en] = y * 2 + 1
                    en++

                    if (x + 1 < w && y + 1 < h) {
                        val oct = IntArray(8)
                        oct[0] = quadrants[RE * wh + y * w + x]
                        oct[1] = quadrants[LE * wh + y * w + (x + 1)]
                        oct[2] = quadrants[BE * wh + y * w + (x + 1)]
                        oct[3] = quadrants[TE * wh + (y + 1) * w + (x + 1)]
                        oct[4] = quadrants[LE * wh + (y + 1) * w + (x + 1)]
                        oct[5] = quadrants[RE * wh + (y + 1) * w + x]
                        oct[6] = quadrants[TE * wh + (y + 1) * w + x]
                        oct[7] = quadrants[BE * wh + y * w + x]

                        var othercol = -1
                        var nchanges = 0
                        var i = 0
                        while (i < 8) {
                            if (oct[i] != oct[0]) {
                                if (othercol < 0) {
                                    othercol = oct[i]
                                } else if (othercol != oct[i]) {
                                    break // three colours at this point
                                }
                            }
                            if (oct[i] != oct[(i + 1) and 7]) nchanges++
                            i++
                        }

                        if (i == 8 && othercol >= 0 && nchanges == 2) {
                            ea[en] = oct[0]
                            eb[en] = othercol
                            ex[en] = (x + 1) * 2
                            ey[en] = (y + 1) * 2
                            en++
                        }
                        if (othercol < 0) {
                            ea[en] = oct[0]
                            eb[en] = oct[0]
                            ex[en] = (x + 1) * 2
                            ey[en] = (y + 1) * 2
                            en++
                        }
                    }

                    for (i in 0 until en) {
                        val emin = min(ea[i], eb[i])
                        val emax = max(ea[i], eb[i])
                        val gindex = if (emin != emax) {
                            graphEdgeIndex(graph, n, ngraph, emin, emax)
                        } else {
                            ngraph + emin
                        }
                        // The C build asserts that every candidate maps to
                        // a graph edge or a region. A defined port result
                        // skips a candidate that does not.
                        if (gindex < 0) continue

                        if (pass == 0) {
                            ax[gindex] += ex[i].toFloat()
                            ay[gindex] += ey[i].toFloat()
                            an[gindex]++
                        } else {
                            if (an[gindex] <= 0) continue
                            val dx = ex[i].toFloat() - ax[gindex]
                            val dy = ey[i].toFloat() - ay[gindex]
                            val d = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (d < best[gindex]) {
                                best[gindex] = d
                                bestx[gindex] = ex[i]
                                besty[gindex] = ey[i]
                            }
                        }
                    }
                }
            }
            if (pass == 0) {
                for (i in 0 until total) {
                    if (an[i] > 0) {
                        ax[i] /= an[i].toFloat()
                        ay[i] /= an[i].toFloat()
                    }
                }
            }
        }

        val edgex = IntArray(ngraph) { bestx[it] }
        val edgey = IntArray(ngraph) { besty[it] }
        val regionx = IntArray(n) { bestx[ngraph + it] }
        val regiony = IntArray(n) { besty[ngraph + it] }

        for (i in 0 until ngraph) {
            if (edgex[i] < 0) {
                val e = graph[i]
                val iprime = graphEdgeIndex(graph, n, ngraph, e % n, e / n)
                if (iprime >= 0 && edgex[iprime] >= 0) {
                    edgex[i] = edgex[iprime]
                    edgey[i] = edgey[iprime]
                }
            }
        }
        return CanonicalPoints(edgex, edgey, regionx, regiony)
    }

    // ------------------------------------------------------------------
    // Solve, `map.c:2241-2299`.
    // ------------------------------------------------------------------

    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state, auxInfo).move

    /** `solve_game` with the two C error strings (`map.c:2241-2299`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state, auxInfo)
        return SolveOutcome(attempt.move, attempt.error)
    }

    /**
     * `solve_game` with the C error string. With [auxInfo] the answer is
     * the auxiliary text itself; otherwise the solver runs at the
     * recursive level from C's `states[0].colouring` and the move is a
     * diff against the current colouring.
     */
    fun solveAttempt(state: Game.State, auxInfo: String? = null): SolveAttempt {
        val current = state as State
        if (auxInfo != null) return SolveAttempt(auxInfo, null)

        val shared = current.shared
        val colouring = shared.initialColouring.copyOf()
        val scratch = SolverScratch(shared.graph, shared.n, shared.ngraph)
        val sret = mapSolver(scratch, colouring, DIFFCOUNT - 1)
        if (sret != 1) {
            val error = if (sret == 0) {
                "Puzzle is inconsistent"
            } else {
                "Unable to find a unique solution for this puzzle"
            }
            return SolveAttempt(null, error)
        }

        val move = buildString {
            append('S')
            for (i in 0 until shared.n) {
                if (colouring[i] == current.colouring[i]) continue
                // C asserts that the region is coloured and not a clue.
                // A hand-written move can overwrite a clue, so the diff
                // includes it and restores the clue instead of aborting.
                append(';').append(colouring[i]).append(':').append(i)
            }
        }
        return SolveAttempt(move, null)
    }

    /** The C error string that [solve] cannot return, or null. */
    fun solveError(state: Game.State, auxInfo: String? = null): String? =
        solveAttempt(state, auxInfo).error

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    override fun textFormat(state: Game.State): String = ""

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return next.completed && !next.cheated && oldState != null &&
            !(oldState as State).completed
    }

    // ------------------------------------------------------------------
    // Moves, `map.c:2520-2735`.
    // ------------------------------------------------------------------

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val n = s.params.n
        val ret = s.copy() as State
        var index = 0

        while (index < move.length) {
            var pencil = false
            var c = move[index]
            if (c == 'p') {
                pencil = true
                index++
                if (index >= move.length) return null
                c = move[index]
            }

            if (c == 'C' || c in '0'..'3') {
                val parsed = scanMoveInt(move, index + 1)
                    ?: return null
                val k = parsed.value
                if (k < 0 || k >= n) return null
                index += 1 + parsed.consumed
                if (pencil) {
                    if (ret.colouring[k] >= 0) return null
                    if (c == 'C') {
                        ret.pencil[k] = 0
                    } else {
                        ret.pencil[k] = ret.pencil[k] xor (1 shl (c - '0'))
                    }
                } else {
                    ret.colouring[k] = if (c == 'C') -1 else c - '0'
                    ret.pencil[k] = 0
                }
            } else if (c == 'S') {
                index++
                ret.cheated = true
            } else {
                return null
            }

            if (index < move.length && move[index] != ';') return null
            if (index < move.length) index++
        }

        if (!ret.completed) {
            var ok = true
            for (i in 0 until n) {
                if (ret.colouring[i] < 0) {
                    ok = false
                    break
                }
            }
            if (ok) {
                for (i in 0 until ret.shared.ngraph) {
                    val j = ret.shared.graph[i] / n
                    val k = ret.shared.graph[i] % n
                    if (ret.colouring[j] == ret.colouring[k]) {
                        ok = false
                        break
                    }
                }
            }
            if (ok) ret.completed = true
        }
        return ret
    }

    private class ScannedInt(val value: Int, val consumed: Int)

    /**
     * The `sscanf(move+1, ":%d%n", &k, &adv)` part of `execute_move`.
     * [start] must point at the colon. Returns null when the conversion
     * fails. An overflowing number saturates, which is the port's
     * defined result for the C undefined behavior.
     */
    private fun scanMoveInt(text: String, start: Int): ScannedInt? {
        var index = start
        if (index >= text.length || text[index] != ':') return null
        index++
        while (index < text.length && isCWhitespace(text[index])) index++
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
        val result = when {
            signed > Int.MAX_VALUE -> Int.MAX_VALUE
            signed < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> signed.toInt()
        }
        return ScannedInt(result, index - start)
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

        if (button == 'l'.code || button == 'L'.code) {
            u.showLabels =
                if (u.showLabels == REGION_LABELS) NO_LABELS else REGION_LABELS
            return MoveResult.UiUpdate
        }
        if (button == 'c'.code || button == 'C'.code) {
            u.showLabels =
                if (u.showLabels == COLOUR_LABELS) NO_LABELS else COLOUR_LABELS
            return MoveResult.UiUpdate
        }

        if (isCursorMove(button)) {
            moveCursor(button, u, s.params.w, s.params.h)
            u.curVisible = true
            u.curMoved = true
            u.curLastMove = button
            return MoveResult.UiUpdate
        }

        if (isCursorSelect(button)) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            if (u.dragColour == -2) {
                val r = regionFromUiCursor(s, u)
                if (r >= 0) {
                    u.dragColour = s.colouring[r]
                    u.dragPencil =
                        if (u.dragColour >= 0) 0 else s.pencil[r]
                } else {
                    u.dragColour = -1
                    u.dragPencil = 0
                }
                u.curMoved = false
                return MoveResult.UiUpdate
            }
            val altButton = button == Buttons.CURSOR_SELECT2
            if (!u.curMoved) u.dragColour = -1
            val drop = regionFromUiCursor(s, u)
            return dropMove(s, u, drop, altButton)
        }

        if (button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON) {
            val r = regionFromCoords(s, tileSize, x, y)
            if (r >= 0) {
                u.dragColour = s.colouring[r]
                u.dragPencil = s.pencil[r]
                if (u.dragColour >= 0) u.dragPencil = 0
            } else {
                u.dragColour = -1
                u.dragPencil = 0
            }
            u.dragX = x
            u.dragY = y
            u.curVisible = false
            return MoveResult.UiUpdate
        }

        if ((button == Buttons.LEFT_DRAG || button == Buttons.RIGHT_DRAG) &&
            u.dragColour > -2
        ) {
            u.dragX = x
            u.dragY = y
            return MoveResult.UiUpdate
        }

        if ((button == Buttons.LEFT_RELEASE || button == Buttons.RIGHT_RELEASE) &&
            u.dragColour > -2
        ) {
            val altButton = button == Buttons.RIGHT_RELEASE
            val drop = regionFromCoords(s, tileSize, x, y)
            return dropMove(s, u, drop, altButton)
        }

        return MoveResult.Unused
    }

    /** The `drag_dropped` label of `interpret_move`. */
    private fun dropMove(
        state: State,
        ui: Ui,
        dropRegion: Int,
        altButton: Boolean,
    ): MoveResult {
        var c = ui.dragColour
        var p = ui.dragPencil
        ui.dragColour = -2

        if (dropRegion < 0) return MoveResult.UiUpdate
        if (state.shared.immutable[dropRegion]) return MoveResult.UiUpdate
        if (state.colouring[dropRegion] == c && state.pencil[dropRegion] == p) {
            return MoveResult.UiUpdate
        }

        if (altButton) {
            if (state.colouring[dropRegion] >= 0) {
                // No pencil mark on a coloured region.
                return MoveResult.UiUpdate
            } else if (c >= 0) {
                // A right drag from a colour to a blank toggles one bit.
                p = state.pencil[dropRegion] xor (1 shl c)
                c = -1
            }
            // A right drag from blank to blank behaves like a left drag.
        }

        val out = StringBuilder()
        var oldp = state.pencil[dropRegion]
        if (c != state.colouring[dropRegion]) {
            out.append(';').append(if (c < 0) 'C' else '0' + c)
                .append(':').append(dropRegion)
            if (c >= 0) oldp = 0
        }
        if (p != oldp) {
            for (i in 0 until FOUR) {
                if ((oldp xor p) and (1 shl i) != 0) {
                    out.append(";p").append('0' + i).append(':').append(dropRegion)
                }
            }
        }
        // C returns `buf+1` and assumes at least one clause. The equality
        // test above makes an empty string unreachable; an empty move is
        // the defined no-op if it ever happens.
        return MoveResult.Move(if (out.isEmpty()) "" else out.substring(1))
    }

    /** `move_cursor` from `misc.c` with `wrap` false and no visibility. */
    private fun moveCursor(button: Int, ui: Ui, maxw: Int, maxh: Int) {
        var dx = 0
        var dy = 0
        when (button) {
            Buttons.CURSOR_UP -> dy = -1
            Buttons.CURSOR_DOWN -> dy = 1
            Buttons.CURSOR_RIGHT -> dx = 1
            Buttons.CURSOR_LEFT -> dx = -1
            else -> return
        }
        ui.curX = min(max(ui.curX + dx, 0), maxw - 1)
        ui.curY = min(max(ui.curY + dy, 0), maxh - 1)
    }

    private fun isCursorMove(button: Int): Boolean =
        button == Buttons.CURSOR_UP || button == Buttons.CURSOR_DOWN ||
            button == Buttons.CURSOR_LEFT || button == Buttons.CURSOR_RIGHT

    private fun isCursorSelect(button: Int): Boolean =
        button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2

    // ------------------------------------------------------------------
    // Pointer geometry, `map.c:2449-2498`.
    // ------------------------------------------------------------------

    private fun coord(value: Int, tileSize: Int): Int = value * tileSize + tileSize

    /**
     * `FROMCOORD`. C's `(x - BORDER + TILESIZE) / TILESIZE - 1` reduces
     * to `x / TILESIZE - 1`, and both languages truncate a negative
     * division toward zero, so a border point maps to a negative cell.
     */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        value / tileSize - 1

    private fun regionFromLogicalCoords(
        state: State,
        tx: Int,
        ty: Int,
        xEps: Int,
        yEps: Int,
    ): Int {
        val w = state.params.w
        val h = state.params.h
        val wh = w * h
        if (tx < 0 || tx >= w || ty < 0 || ty >= h) return -1
        val quadrant = 2 * (if (xEps > yEps) 1 else 0) +
            (if (-xEps > yEps) 1 else 0)
        val q = when (quadrant) {
            0 -> BE
            1 -> LE
            2 -> RE
            else -> TE
        }
        return state.shared.map[q * wh + ty * w + tx]
    }

    private fun regionFromCoords(state: State, tileSize: Int, x: Int, y: Int): Int {
        val tx = fromCoord(x, tileSize)
        val ty = fromCoord(y, tileSize)
        return regionFromLogicalCoords(
            state,
            tx,
            ty,
            x - coord(tx, tileSize) - tileSize / 2,
            y - coord(ty, tileSize) - tileSize / 2,
        )
    }

    private fun regionFromUiCursor(state: State, ui: Ui): Int =
        regionFromLogicalCoords(
            state,
            ui.curX,
            ui.curY,
            epsilonX(ui.curLastMove),
            epsilonY(ui.curLastMove),
        )

    private fun epsilonX(button: Int): Int = when (button) {
        Buttons.CURSOR_RIGHT -> 1
        Buttons.CURSOR_LEFT -> -1
        else -> 0
    }

    private fun epsilonY(button: Int): Int = when (button) {
        Buttons.CURSOR_DOWN -> 1
        Buttons.CURSOR_UP -> -1
        else -> 0
    }

    // ------------------------------------------------------------------
    // Keys, UI, status, `map.c:1849-1864`, `2349-2364`, `2500-2518`.
    // ------------------------------------------------------------------

    override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
        listOf(
            KeyLabel('L'.code, "Regions", false),
            KeyLabel('C'.code, "Colours", false),
        ),
        ArrowMode.ARROWS_LEFT_RIGHT,
    )

    /**
     * `current_key_label`, `map.c:2500-2518`. The seam has no operation
     * for it, so this is a public helper. C indexes `immutable[r]`
     * without a test; the port returns "" for an out-of-grid cursor, as
     * `lightup` does.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (!isCursorSelect(button) || !u.curVisible) return ""
        if (u.dragColour == -2) return "Pick"
        val r = regionFromUiCursor(s, u)
        if (r < 0 || r >= s.params.n) return ""
        if (s.shared.immutable[r]) return "Cancel"
        if (!u.curMoved) return if (u.dragPencil != 0) "Cancel" else "Clear"
        if (button == Buttons.CURSOR_SELECT2) {
            if (s.colouring[r] >= 0) return "Cancel"
            if (u.dragColour >= 0) return "Stipple"
        }
        if (u.dragPencil != 0) return "Stipple"
        return if (u.dragColour >= 0) "Fill" else "Clear"
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
        return Game.CursorRect(
            coord(u.curX, tileSize),
            coord(u.curY, tileSize),
            tileSize,
            tileSize,
        )
    }

    /** `game_compute_size`, `map.c:2741-2750`. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size(
            p.w * tileSize + 2 * tileSize + 1,
            p.h * tileSize + 2 * tileSize + 1,
        )
    }

    // ------------------------------------------------------------------
    // Palette and drawing, `map.c:2760-3216`.
    // ------------------------------------------------------------------

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]

        // `VIVID_COLOURS` is defined by the Android and oracle builds.
        val board = arrayOf(
            floatArrayOf(0.627f, 0.157f, 0.133f),
            floatArrayOf(0.290f, 0.698f, 0.290f),
            floatArrayOf(0.322f, 0.690f, 0.788f),
            floatArrayOf(0.710f, 0.698f, 0.098f),
        )
        for (i in 0 until 3) {
            ret[COL_GRID * 3 + i] = 0.0f
            ret[COL_TEXT_ON_FILLED * 3 + i] = 0.0f
        }
        for (c in 0 until FOUR) {
            for (i in 0 until 3) ret[(COL_0 + c) * 3 + i] = board[c][i]
        }
        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f
        ret[COL_ERRTEXT * 3 + 0] = 1.0f
        ret[COL_ERRTEXT * 3 + 1] = 1.0f
        ret[COL_ERRTEXT * 3 + 2] = 1.0f
        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val wh = s.params.w * s.params.h
        return DrawState(
            tileSize = tileSize,
            w = s.params.w,
            h = s.params.h,
            drawn = IntArray(wh) { 0xFFFF },
            todraw = IntArray(wh),
            started = false,
            dragX = -1,
            dragY = -1,
            dragVisible = false,
            blitter = sink.blitterNew(tileSize + 3, tileSize + 3),
        )
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        sink.blitterFree((drawState as DrawState).blitter)
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
        val w = ds.w
        val h = ds.h
        val wh = w * h
        val n = s.params.n
        val map = s.shared.map
        val t = ds.tileSize

        if (ds.dragVisible) {
            sink.blitterLoad(ds.blitter, ds.dragX, ds.dragY)
            sink.update(ds.dragX, ds.dragY, t + 3, t + 3)
            ds.dragVisible = false
        }

        if (!ds.started) {
            sink.rect(coord(0, t), coord(0, t), w * t + 1, h * t + 1, COL_GRID)
            sink.update(coord(0, t), coord(0, t), w * t + 1, h * t + 1)
            ds.started = true
        }

        // The port does not draw the completion flash.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val te = map[TE * wh + y * w + x]
                val be = map[BE * wh + y * w + x]
                var tv = s.colouring[te]
                var bv = s.colouring[be]
                if (tv < 0) tv = FOUR
                if (bv < 0) bv = FOUR
                var v = tv * FIVE + bv

                for (i in 0 until FOUR) {
                    if (s.colouring[te] < 0 &&
                        (s.pencil[te] and (1 shl i)) != 0
                    ) {
                        v = v or (PENCIL_T_BASE shl i)
                    }
                    if (s.colouring[be] < 0 &&
                        (s.pencil[be] and (1 shl i)) != 0
                    ) {
                        v = v or (PENCIL_B_BASE shl i)
                    }
                }
                if (u.showLabels == REGION_LABELS) {
                    v = v or SHOW_REG_LAB
                } else if (u.showLabels == COLOUR_LABELS) {
                    v = v or SHOW_COL_LAB
                }
                ds.todraw[y * w + x] = v
            }
        }

        // Error markers: one per directed graph edge whose regions match.
        for (i in 0 until s.shared.ngraph) {
            val v1 = s.shared.graph[i] / n
            val v2 = s.shared.graph[i] % n
            if (s.colouring[v1] < 0 || s.colouring[v2] < 0) continue
            if (s.colouring[v1] != s.colouring[v2]) continue
            val ex = s.shared.edgex[i]
            val ey = s.shared.edgey[i]
            // A missing canonical point is a defined skip, not an abort.
            if (ex < 0 || ey < 0) continue
            var x = ex
            var y = ey
            val xo = x % 2
            x /= 2
            val yo = y % 2
            y /= 2
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            ds.todraw[y * w + x] = ds.todraw[y * w + x] or (ERR_BASE shl (yo * 3 + xo))
            if (xo == 0) {
                if (x > 0) {
                    ds.todraw[y * w + (x - 1)] =
                        ds.todraw[y * w + (x - 1)] or (ERR_BASE shl (yo * 3 + 2))
                }
            }
            if (yo == 0) {
                if (y > 0) {
                    ds.todraw[(y - 1) * w + x] =
                        ds.todraw[(y - 1) * w + x] or (ERR_BASE shl (6 + xo))
                }
            }
            if (xo == 0 && yo == 0 && x > 0 && y > 0) {
                ds.todraw[(y - 1) * w + (x - 1)] =
                    ds.todraw[(y - 1) * w + (x - 1)] or (ERR_BASE shl 8)
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = ds.todraw[y * w + x]
                if (ds.drawn[y * w + x] != v) {
                    drawSquare(ds, s.params, s.shared, x, y, v, u.largeStipples, sink)
                    ds.drawn[y * w + x] = v
                }
            }
        }

        if (u.dragColour > -2 || u.curVisible) {
            var bg: Int
            var cursorX: Int
            var cursorY: Int
            var iscur = false
            if (u.dragColour >= 0) {
                bg = COL_0 + u.dragColour
            } else if (u.dragColour == -1) {
                bg = COL_BACKGROUND
            } else {
                val r = regionFromUiCursor(s, u)
                val c = if (r < 0) -1 else s.colouring[r]
                bg = if (c < 0) COL_BACKGROUND else COL_0 + c
                iscur = true
            }

            if (u.curVisible) {
                cursorX = coord(u.curX, t) + t / 2 + epsilonX(u.curLastMove)
                cursorY = coord(u.curY, t) + t / 2 + epsilonY(u.curLastMove)
            } else {
                cursorX = u.dragX
                cursorY = u.dragY
            }
            ds.dragX = cursorX - t / 2 - 2
            ds.dragY = cursorY - t / 2 - 2
            sink.blitterSave(ds.blitter, ds.dragX, ds.dragY)
            sink.circle(
                cursorX,
                cursorY,
                if (iscur) t / 4 else t / 2,
                bg,
                COL_GRID,
            )
            for (i in 0 until FOUR) {
                if (u.dragPencil and (1 shl i) != 0) {
                    sink.circle(
                        cursorX + ((i * 4 + 2) % 10 - 3) * t / 10,
                        cursorY + (i * 2 - 3) * t / 10,
                        t / 8,
                        COL_0 + i,
                        COL_0 + i,
                    )
                }
            }
            sink.update(ds.dragX, ds.dragY, t + 3, t + 3)
            ds.dragVisible = true
        }
    }

    /** `draw_square`, `map.c:2871-3015`. */
    private fun drawSquare(
        ds: DrawState,
        params: Params,
        shared: Shared,
        x: Int,
        y: Int,
        word: Int,
        largeStipples: Boolean,
        sink: DrawSink,
    ) {
        val w = params.w
        val h = params.h
        val wh = w * h
        val t = ds.tileSize
        val map = shared.map

        var v = word
        val errs = v and ERR_MASK
        v = v and ERR_MASK.inv()
        val pencil = v and PENCIL_MASK
        v = v and PENCIL_MASK.inv()
        val showLabels = v and SHOW_LAB_MASK
        v = v and SHOW_LAB_MASK.inv()
        val tv = v / FIVE
        val bv = v % FIVE

        sink.clip(coord(x, t), coord(y, t), t, t)

        sink.rect(
            coord(x, t),
            coord(y, t),
            t,
            t,
            if (tv == FOUR) COL_BACKGROUND else COL_0 + tv,
        )

        if (map[TE * wh + y * w + x] != map[BE * wh + y * w + x]) {
            val coords = IntArray(6)
            coords[0] = coord(x, t) - 1
            coords[1] = coord(y + 1, t) + 1
            coords[2] = if (map[LE * wh + y * w + x] ==
                map[TE * wh + y * w + x]
            ) {
                coord(x + 1, t) + 1
            } else {
                coord(x, t) - 1
            }
            coords[3] = coord(y, t) - 1
            coords[4] = coord(x + 1, t) + 1
            coords[5] = coord(y + 1, t) + 1
            sink.poly(
                coords,
                if (bv == FOUR) COL_BACKGROUND else COL_0 + bv,
                COL_GRID,
            )
        }

        val te = map[TE * wh + y * w + x]
        for (yo in 0 until FOUR) {
            for (xo in 0 until FOUR) {
                val e = when {
                    yo < xo && yo < 3 - xo -> TE
                    yo > xo && yo > 3 - xo -> BE
                    xo < 2 -> LE
                    else -> RE
                }
                val ee = map[e * wh + y * w + x]
                if (xo != (yo * 2 + 1) % 5) continue
                val c = yo
                val base = if (ee == te) PENCIL_T_BASE else PENCIL_B_BASE
                if (pencil and (base shl c) == 0) continue
                if (yo == xo && map[TE * wh + y * w + x] !=
                    map[LE * wh + y * w + x]
                ) {
                    continue // avoid the TL-BR diagonal line
                }
                if (yo == 3 - xo && map[TE * wh + y * w + x] !=
                    map[RE * wh + y * w + x]
                ) {
                    continue // avoid the BL-TR diagonal line
                }
                sink.circle(
                    coord(x, t) + (xo + 1) * t / 5,
                    coord(y, t) + (yo + 1) * t / 5,
                    if (largeStipples) t / 4 else t / 7,
                    COL_0 + c,
                    COL_0 + c,
                )
            }
        }

        if (x <= 0 || map[RE * wh + y * w + (x - 1)] !=
            map[LE * wh + y * w + x]
        ) {
            sink.rect(coord(x, t), coord(y, t), 1, t, COL_GRID)
        }
        if (y <= 0 || map[BE * wh + (y - 1) * w + x] !=
            map[TE * wh + y * w + x]
        ) {
            sink.rect(coord(x, t), coord(y, t), t, 1, COL_GRID)
        }
        if (x <= 0 || y <= 0 ||
            map[RE * wh + (y - 1) * w + (x - 1)] !=
            map[TE * wh + y * w + x] ||
            map[BE * wh + (y - 1) * w + (x - 1)] !=
            map[LE * wh + y * w + x]
        ) {
            sink.rect(coord(x, t), coord(y, t), 1, 1, COL_GRID)
        }

        for (yo in 0 until 3) {
            for (xo in 0 until 3) {
                if (errs and (ERR_BASE shl (yo * 3 + xo)) != 0) {
                    drawError(
                        sink,
                        (coord(x, t) * 2 + t * xo) / 2,
                        (coord(y, t) * 2 + t * yo) / 2,
                        t,
                    )
                }
            }
        }

        if (showLabels != 0) {
            var oldj = -1
            for (i in 0..1) {
                val j = map[(if (i != 0) BE else TE) * wh + y * w + x]
                if (oldj == j) continue
                oldj = j
                val xo = shared.regionx[j] - 2 * x
                val yo = shared.regiony[j] - 2 * y
                if (xo in 0..2 && yo in 0..2) {
                    val text: String
                    if (showLabels == SHOW_COL_LAB) {
                        val pos = StringBuilder()
                        val mc = if (i != 0) bv else tv
                        for (c in 0 until FOUR) {
                            if (mc == c) {
                                pos.append(('A'.code + c).toChar())
                            } else if (pencil and
                                ((if (i != 0) PENCIL_B_BASE else PENCIL_T_BASE) shl c) != 0
                            ) {
                                pos.append(('a'.code + c).toChar())
                            }
                        }
                        text = pos.toString()
                    } else {
                        text = j.toString()
                    }
                    var sz = 3 * t / 5
                    if (text.length > 2) {
                        sz = floor(sz * 2.0 / text.length).toInt()
                    }
                    sink.text(
                        (coord(x, t) * 2 + t * xo) / 2,
                        (coord(y, t) * 2 + t * yo) / 2,
                        Text.FONT_VARIABLE,
                        sz,
                        Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                        if ((if (i != 0) bv else tv) == FOUR) {
                            COL_GRID
                        } else {
                            COL_TEXT_ON_FILLED
                        },
                        text,
                    )
                }
            }
        }

        sink.unclip()
        sink.update(coord(x, t), coord(y, t), t, t)
    }

    /** `draw_error`, `map.c:2840-2869`. */
    private fun drawError(sink: DrawSink, x: Int, y: Int, t: Int) {
        val coords = intArrayOf(
            x - t * 2 / 5, y,
            x, y - t * 2 / 5,
            x + t * 2 / 5, y,
            x, y + t * 2 / 5,
        )
        sink.poly(coords, COL_ERROR, COL_GRID)

        val xext = t / 16
        val yext = t * 2 / 5 - (xext * 2 + 2)
        sink.rect(
            x - xext,
            y - yext,
            xext * 2 + 1,
            yext * 2 + 1 - xext * 3,
            COL_ERRTEXT,
        )
        sink.rect(
            x - xext,
            y + yext - xext * 2 + 1,
            xext * 2 + 1,
            xext * 2,
            COL_ERRTEXT,
        )
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

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

    private fun isCWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r'

    private data class AtoiResult(val value: Int, val end: Int)

    /**
     * C `atoi` from [start]. A number that overflows an `int` saturates,
     * which is the port's defined result for the C undefined behavior.
     */
    private fun cAtoiAt(text: String, start: Int): AtoiResult {
        var index = start
        while (index < text.length && isCWhitespace(text[index])) index++
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

    /** `atoi` from [start]. */
    private fun cAtoi(text: String, start: Int): Int = cAtoiAt(text, start).value
}
