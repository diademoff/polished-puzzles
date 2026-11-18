package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `pearl.c`: Nikoli's `Masyu`.
 *
 * The player draws one closed loop through the grid. The loop passes
 * straight through every white clue and turns at every black clue, and
 * it may not branch, form a sub-loop, or skip a clue.
 *
 * Three parts are coupled:
 *
 * - the loop generator is the square grid from `loopgen.c` with a
 *   stateful bias that counts black-clue sites on both boundaries of the
 *   half-coloured board;
 * - the maximal clue array is pruned by the deduction solver, and the
 *   upstream removal list is filled with straights twice. That bug and
 *   the solver decide every description;
 * - a state holds three byte arrays over a shared clue array, and the
 *   hint and solve moves both write the solver result into the lines.
 *
 * The C initial state always has empty lines, so the ambiguous solve
 * quirk (C diffs against `states[0].state`) is reproduced with a fresh
 * zero-lines state. The solver error text is exposed through
 * [solveAttempt]; the seam has no error channel.
 *
 * The clue-removal buffer is sized `2*w*h` and the drag path one entry
 * longer than the cell count. Both are upstream out-of-bounds writes.
 * The drag extension also stops at a cell that is already on the path,
 * because C appends every intermediate cell without a visit test. See
 * D-019 in `docs/DEVIATIONS.md` and the engine tests.
 *
 * Generation has the C cost: a `13x13` board takes seconds and `14x14`
 * or larger does not finish in the measured budget. `validateParams`
 * keeps the C bound, so the app must generate off the UI thread. D-032
 * refuses an area above [MAX_AREA] (11x11) before the first draw.
 */
object Pearl : Game {

    // ------------------------------------------------------------------
    // Constants and parameters, `pearl.c:56-190`.
    // ------------------------------------------------------------------

    private const val NOCLUE = 0
    private const val CORNER = 1
    private const val STRAIGHT = 2

    /** D-032: the measured generation cap, 11x11. */
    internal const val MAX_AREA = 121

    private const val R = 1
    private const val U = 2
    private const val L = 4
    private const val D = 8

    private const val DIFF_EASY = 0
    private const val DIFF_TRICKY = 1
    private const val DIFFCOUNT = 2

    private val DIFF_NAMES = arrayOf("Easy", "Tricky")
    private val DIFF_CHARS = arrayOf('e', 't')

    private const val DEFAULT_PRESET = 3
    private const val PREFERRED_TILE_SIZE = 31

    /** `BLANK`, the empty line mask. */
    private const val BLANK = 0

    private const val bLR = 1 shl (L or R)
    private const val bUD = 1 shl (U or D)
    private const val bLU = 1 shl (L or U)
    private const val bLD = 1 shl (L or D)
    private const val bRU = 1 shl (R or U)
    private const val bRD = 1 shl (R or D)
    private const val bBLANK = 1 shl BLANK

    private const val STRAIGHT_MASK = bLR or bUD
    private const val CORNER_MASK = bLU or bLD or bRU or bRD

    /** `ERROR_CLUE`, `pearl.c:1568`. */
    private const val ERROR_CLUE = 16

    // Palette, `pearl.c:101-108`.
    private const val COL_BACKGROUND = 0
    private const val COL_HIGHLIGHT = 1
    private const val COL_LOWLIGHT = 2
    private const val COL_CURSOR_BACKGROUND = COL_LOWLIGHT
    private const val COL_BLACK = 3
    private const val COL_WHITE = 4
    private const val COL_ERROR = 5
    private const val COL_GRID = 6
    private const val COL_FLASH = 7
    private const val COL_DRAGON = 8
    private const val COL_DRAGOFF = 9
    private const val NCOLOURS = 10

    /**
     * `game_ui.gui_style`, `pearl.c:1909`. The declaration order matches
     * the C enum, so [ordinal] is the preference choice index.
     */
    enum class Appearance {
        TRADITIONAL,
        LOOPY,
    }

    // Drawstate flag bits, `pearl.c:2016-2022`.
    private const val DS_ESHIFT = 4
    private const val DS_DSHIFT = 8
    private const val DS_MSHIFT = 12
    private const val DS_ERROR_CLUE = 1 shl 20
    private const val DS_FLASH = 1 shl 21
    private const val DS_CURSOR = 1 shl 22

    /** `struct game_params`, `pearl.c:128-132`. */
    data class Params(
        val w: Int,
        val h: Int,
        val difficulty: Int,
        val nosolve: Boolean,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, difficulty, nosolve)
    }

    /**
     * `struct shared_state`, `pearl.c:134-138`: the clue array and the
     * size, shared by every state of the history. Kotlin has no explicit
     * reference count; the shared object lives while any state reaches
     * it.
     */
    class Shared internal constructor(
        val w: Int,
        val h: Int,
        /** One [NOCLUE], [CORNER], or [STRAIGHT] per cell. Do not modify. */
        val clues: IntArray,
    ) {
        val size: Int get() = w * h
    }

    /**
     * `struct game_state`, `pearl.c:142-148`. [lines], [errors], and
     * [marks] are one byte per cell; [completed] is sticky and
     * [usedSolve] is set by an `S` move.
     */
    class State internal constructor(
        val shared: Shared,
        /** The line mask per cell. Do not modify. */
        val lines: IntArray,
        /** The error mask per cell. Do not modify. */
        val errors: IntArray,
        /** The "no line here" mask per cell. Do not modify. */
        val marks: IntArray,
        var completed: Boolean,
        var usedSolve: Boolean,
    ) : Game.State {

        val width: Int get() = shared.w

        val height: Int get() = shared.h

        fun clueAt(x: Int, y: Int): Int = shared.clues[y * width + x]

        fun lineAt(x: Int, y: Int): Int = lines[y * width + x]

        fun errorAt(x: Int, y: Int): Int = errors[y * width + x]

        fun markAt(x: Int, y: Int): Int = marks[y * width + x]

        override fun copy(): Game.State = State(
            shared,
            lines.copyOf(),
            errors.copyOf(),
            marks.copyOf(),
            completed,
            usedSolve,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                shared.w == other.shared.w &&
                shared.h == other.shared.h &&
                shared.clues.contentEquals(other.shared.clues) &&
                lines.contentEquals(other.lines) &&
                errors.contentEquals(other.errors) &&
                marks.contentEquals(other.marks) &&
                completed == other.completed &&
                usedSolve == other.usedSolve

        override fun hashCode(): Int {
            var result = shared.w
            result = 31 * result + shared.h
            result = 31 * result + shared.clues.contentHashCode()
            result = 31 * result + lines.contentHashCode()
            result = 31 * result + errors.contentHashCode()
            result = 31 * result + marks.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + usedSolve.hashCode()
            return result
        }
    }

    /**
     * `struct game_ui`, `pearl.c:1891-1910`. [dragcoords] holds one extra
     * entry: C writes `dragcoords[ndragcoords]` in the loop-drag branch
     * and can reach `ndragcoords == sz` (`pearl.c:2129`).
     *
     * [appearance] is the `Puzzle appearance` preference. It is not
     * serialized and it is not part of the drawstate cache.
     */
    class Ui internal constructor(size: Int) : Game.Ui {
        /** The drag path of cell indices, `sz + 1` entries. */
        val dragcoords: IntArray = IntArray(size + 1)
        var ndragcoords: Int = -1
        var clickx: Int = 0
        var clicky: Int = 0
        var curx: Int = 0
        var cury: Int = 0
        var cursorActive: Boolean = false
        var appearance: Appearance = Appearance.TRADITIONAL
    }

    /**
     * `struct game_drawstate`, `pearl.c:2024-2032`: the dirty-cell cache.
     * There is no blitter and no image.
     */
    class DrawState internal constructor(
        val w: Int,
        val h: Int,
        val halfsz: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val lflags: IntArray = IntArray(w * h)
        val draglines: IntArray = IntArray(w * h)
    }

    /** One generated board and the number of generator attempts. */
    internal data class GeneratedClues(
        val clues: IntArray,
        val grid: IntArray,
        val attempts: Int,
    )

    // ------------------------------------------------------------------
    // Identity, presets, and parameters.
    // ------------------------------------------------------------------

    override val name: String = "Pearl"
    override val backendName: String = "pearl"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val flags: Int = 0

    private val presetList = listOf(
        Params(6, 6, DIFF_EASY, false),
        Params(6, 6, DIFF_TRICKY, false),
        Params(8, 8, DIFF_EASY, false),
        Params(8, 8, DIFF_TRICKY, false),
        Params(10, 10, DIFF_EASY, false),
        Params(10, 10, DIFF_TRICKY, false),
        Params(12, 8, DIFF_EASY, false),
        Params(12, 8, DIFF_TRICKY, false),
    )

    /** `default_params`: preset 3, `8x8` Tricky. */
    override fun defaultParams(): Game.Params = presetList[DEFAULT_PRESET]

    /** `game_fetch_preset`: `%dx%d %s`. */
    override fun presets(): List<Preset> = presetList.map {
        Preset("${it.w}x${it.h} ${DIFF_NAMES[it.difficulty]}", encodeParams(it, true))
    }

    /**
     * `decode_params`, `pearl.c:204-229`, with the literal control flow:
     * only a digit run after the first `atoi` hides the `x`, only a
     * lower-case `d` is a difficulty, and the `n` test runs after the
     * single character that follows `d` was consumed.
     */
    override fun decodeParams(text: String): Game.Params {
        var position = 0
        val w = atoi(text, 0)
        var h = w
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            h = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }

        var difficulty = DIFF_EASY
        if (position < text.length && text[position] == 'd') {
            position++
            for (i in 0 until DIFFCOUNT) {
                if (position < text.length && text[position] == DIFF_CHARS[i]) {
                    difficulty = i
                }
            }
            if (position < text.length) position++
        }

        var nosolve = false
        if (position < text.length && text[position] == 'n') {
            nosolve = true
            position++
        }

        return Params(w, h, difficulty, nosolve)
    }

    /** `encode_params`, `pearl.c:231-240`: the short form drops `d` and `n`. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val base = "${p.w}x${p.h}"
        if (!full) return base
        return base + "d" + DIFF_CHARS[p.difficulty] + if (p.nosolve) "n" else ""
    }

    /** `validate_params`, `pearl.c:286-298`, in C order. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 5) return "Width must be at least five"
        if (p.h < 5) return "Height must be at least five"
        if (p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.difficulty < 0 || p.difficulty >= DIFFCOUNT) {
            return "Unknown difficulty level"
        }
        if (p.difficulty >= DIFF_TRICKY && p.w + p.h < 11) {
            return "Width or height must be at least six for Tricky"
        }
        return null
    }

    /** `game_configure` (`pearl.c:242-272`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Tricky",
                null,
                p.difficulty,
            ),
            ConfigItem.Bool("Allow unsoluble", p.nosolve),
        )
    }

    /** `custom_params` (`pearl.c:274-284`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
            (items[3] as ConfigItem.Bool).value,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`pearl.c:1960-1977`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                "Puzzle appearance",
                ":Traditional:Loopy-style",
                ":traditional:loopy",
                0,
                "appearance",
            ),
        )

    /** `set_prefs` (`pearl.c:1979-1982`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).appearance = Appearance.values()[prefs.choice("appearance")]
    }

    // ------------------------------------------------------------------
    // Description and generation, `pearl.c:995-1481`.
    // ------------------------------------------------------------------

    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        val generated = generateClues(p, random, cancel)
        return Description(encodeDesc(p.w, p.h, generated.clues), encodeAux(p.w * p.h, generated.grid))
    }

    /** `new_game_desc` with the attempt count exposed for fixtures. */
    internal fun generate(p: Params, random: RandomState): Pair<Description, Int> {
        val generated = generateClues(p, random)
        return Description(
            encodeDesc(p.w, p.h, generated.clues),
            encodeAux(p.w * p.h, generated.grid),
        ) to generated.attempts
    }

    /**
     * `new_clues`, `pearl.c:1224-1421`.
     *
     * The solver writes its answer into the same `grid` array that held
     * the generated loop, as C does. On an accepted puzzle the unique
     * answer is the loop, so the auxiliary text is the loop either way.
     */
    internal fun generateClues(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): GeneratedClues {
        require(params.w.toLong() * params.h <= MAX_AREA) {
            Game.AREA_TOO_LARGE
        }
        val w = params.w
        val h = params.h
        var diff = params.difficulty
        val grid = Grid.new(GridType.SQUARE, w - 1, h - 1, null)

        // The 5x5 Tricky fudge is unreachable through validation. Keep it
        // for fidelity with the standalone soak test.
        if (w == 5 && h == 5 && diff > DIFF_EASY) diff = DIFF_EASY

        val clues = IntArray(w * h)
        val gridOut = IntArray(w * h)
        var attempts = 0

        while (true) {
            cancel.check()
            attempts++
            pearlLoopgen(w, h, gridOut, random, grid)

            buildMaximalClues(w, h, gridOut, clues)

            if (!params.nosolve) {
                var ret = pearlSolve(w, h, clues, gridOut, diff, false)
                check(ret > 0) { "the maximal clue array is inconsistent" }
                if (ret != 1) continue

                if (diff > DIFF_EASY) {
                    ret = pearlSolve(w, h, clues, gridOut, diff - 1, false)
                    check(ret > 0) { "the maximal clue array is inconsistent" }
                    if (ret == 1) continue
                }

                val removal = buildClueRemovalOrder(w, h, clues, random)
                for (position in removal.positions) {
                    val clue = clues[position]
                    clues[position] = NOCLUE
                    ret = pearlSolve(w, h, clues, gridOut, diff, false)
                    check(ret > 0) { "a pruned clue array is inconsistent" }
                    if (ret != 1) clues[position] = clue
                }
            }
            break
        }
        return GeneratedClues(clues, gridOut, attempts)
    }

    /**
     * The maximal clue array, `pearl.c:1263-1299`.
     *
     * A straight cell is a clue when one neighbour is a corner; a corner
     * cell is a clue when every neighbour is a straight.
     */
    private fun buildMaximalClues(w: Int, h: Int, grid: IntArray, clues: IntArray) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val type = grid[y * w + x]
                val i = y * w + x
                clues[i] = NOCLUE
                if ((STRAIGHT_MASK and (1 shl type)) != 0) {
                    var d = 1
                    while (d <= 8) {
                        if ((type and d) != 0) {
                            val xx = x + dx(d)
                            val yy = y + dy(d)
                            check(xx in 0 until w && yy in 0 until h) { "a loop leaves the grid" }
                            if ((CORNER_MASK and (1 shl grid[yy * w + xx])) != 0) break
                        }
                        d *= 2
                    }
                    if (d <= 8) clues[i] = STRAIGHT
                } else if ((CORNER_MASK and (1 shl type)) != 0) {
                    var d = 1
                    while (d <= 8) {
                        if ((type and d) != 0) {
                            val xx = x + dx(d)
                            val yy = y + dy(d)
                            check(xx in 0 until w && yy in 0 until h) { "a loop leaves the grid" }
                            if ((STRAIGHT_MASK and (1 shl grid[yy * w + xx])) == 0) break
                        }
                        d *= 2
                    }
                    if (d > 8) clues[i] = CORNER
                }
            }
        }
    }

    /**
     * The clue-removal order, `pearl.c:1351-1400`, including the upstream
     * bug and the buffer overrun.
     *
     * The corner list is filled with the straight positions
     * (`pearl.c:1359-1361`), so `ncornerpos == nstraightpos`. The removal
     * loop then reads `straights` for both counters
     * (`pearl.c:1379-1386`), so it tries the shuffled straight list
     * twice and never reads the corner list. The second shuffle still
     * consumes the same RNG draws.
     *
     * C allocates `w*h` ints for both lists; the second fill writes up to
     * index `2*nstraightpos - 1`, so an all-straight clue array writes
     * past the allocation. The port allocates `2*w*h` and checks the
     * write. See [ClueRemovalOrder] and D-019.
     */
    internal fun buildClueRemovalOrder(
        w: Int,
        h: Int,
        clues: IntArray,
        random: RandomState,
    ): ClueRemovalOrder {
        val capacity = 2 * w * h
        val space = IntArray(capacity)

        var straightCount = 0
        for (i in 0 until w * h) {
            if (clues[i] == STRAIGHT) space[straightCount++] = i
        }
        val cornerBase = straightCount
        var cornerCount = 0
        for (i in 0 until w * h) {
            if (clues[i] == STRAIGHT) space[cornerBase + cornerCount++] = i
        }
        check(cornerBase + cornerCount <= capacity) {
            "the clue-removal lists need more than $capacity entries"
        }

        shuffleRange(space, 0, straightCount, random)
        shuffleRange(space, cornerBase, cornerCount, random)

        // The C read order: straights[--nstraightpos] while both counters
        // are positive, then straights[--ncornerpos] for the rest.
        val positions = IntArray(straightCount + cornerCount)
        var at = 0
        for (i in straightCount - 1 downTo 0) positions[at++] = space[i]
        for (i in cornerCount - 1 downTo 0) positions[at++] = space[i]

        return ClueRemovalOrder(
            positions = positions,
            straightCount = straightCount,
            cornerCount = cornerCount,
            cornerBase = cornerBase,
            capacity = capacity,
        )
    }

    /** One clue-removal order plus the counters and buffer geometry. */
    internal class ClueRemovalOrder(
        val positions: IntArray,
        val straightCount: Int,
        val cornerCount: Int,
        val cornerBase: Int,
        val capacity: Int,
    )

    /** `misc.c shuffle` over one segment of [array]. */
    private fun shuffleRange(array: IntArray, offset: Int, count: Int, random: RandomState) {
        var i = count
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[offset + i]
                array[offset + i] = array[offset + j]
                array[offset + j] = tmp
            }
        }
    }

    /** The `DESC` encoder, `pearl.c:1435-1447`. */
    internal fun encodeDesc(w: Int, h: Int, clues: IntArray): String {
        val out = StringBuilder(w * h)
        for (clue in clues) {
            when (clue) {
                NOCLUE -> {
                    val last = out.lastOrNull()
                    if (last != null && last >= 'a' && last < 'z') {
                        out.setCharAt(out.length - 1, last + 1)
                    } else {
                        out.append('a')
                    }
                }
                CORNER -> out.append('B')
                STRAIGHT -> out.append('W')
            }
        }
        return out.toString()
    }

    /** The `AUXINFO` encoder, `pearl.c:1449-1452`. */
    internal fun encodeAux(size: Int, grid: IntArray): String {
        val out = StringBuilder(size)
        for (i in 0 until size) {
            val value = grid[i]
            out.append(if (value < 10) ('0' + value) else ('A' + value - 10))
        }
        return out.toString()
    }

    /** `validate_desc`, `pearl.c:1460-1481`. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val total = p.w * p.h
        var sofar = 0
        for (c in desc) {
            when {
                c in 'a'..'z' -> sofar += c - 'a' + 1
                c == 'B' || c == 'W' -> sofar++
                else -> return "unrecognised character in string"
            }
        }
        if (sofar > total) return "string too long"
        if (sofar < total) return "string too short"
        return null
    }

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val size = p.w * p.h
        val clues = IntArray(size)
        var at = 0
        for (c in desc) {
            when {
                c in 'a'..'z' -> {
                    var n = c - 'a' + 1
                    check(at < size) { "the description is too long" }
                    check(at + n <= size) { "the description is too long" }
                    while (n-- > 0) clues[at++] = NOCLUE
                }
                c == 'B' -> {
                    check(at < size) { "the description is too long" }
                    clues[at++] = CORNER
                }
                c == 'W' -> {
                    check(at < size) { "the description is too long" }
                    clues[at++] = STRAIGHT
                }
            }
        }
        val shared = Shared(p.w, p.h, clues)
        return State(
            shared,
            IntArray(size),
            IntArray(size),
            IntArray(size),
            completed = false,
            usedSolve = false,
        )
    }

    // ------------------------------------------------------------------
    // The loop generator bias, `pearl.c:950-1222`.
    // ------------------------------------------------------------------

    private const val FACE_WHITE = 0
    private const val FACE_GREY = 1
    private const val FACE_BLACK = 2

    /**
     * `pearl_loopgen_bias`, `pearl.c:995-1120`. The eleven arrays and the
     * seven queues are updated incrementally, so a call visits only the
     * faces, edges, vertices, and clue sites that changed.
     */
    internal class PearlBias(private val g: Grid) {

        private inner class Boundary(val colour: Int) {
            val edges = BooleanArray(g.edges.size)
            val edgesTodo = Tdq(g.edges.size).apply { fill() }
            val vertexTypes = IntArray(g.dots.size)
            val neighbour0 = IntArray(g.dots.size)
            val neighbour1 = IntArray(g.dots.size)
            val vertexTypesTodo = Tdq(g.dots.size).apply { fill() }
            val blackClues = IntArray(g.dots.size)
            val blackCluesTodo = Tdq(g.dots.size).apply { fill() }
        }

        private val boundaries = arrayOf(Boundary(FACE_WHITE), Boundary(FACE_BLACK))
        private val faces = IntArray(g.faces.size) { FACE_GREY }
        private val facesTodo = Tdq(g.faces.size).apply { fill() }
        private var score = 0

        /** The current black-clue site count over both boundaries. */
        fun score(board: IntArray, face: Int): Int {
            facesTodo.add(face)
            while (true) {
                val j = facesTodo.remove()
                if (j < 0) break
                val oldFace = faces[j]
                val newFace = board[j]
                faces[j] = newFace
                for (b in boundaries) {
                    if (oldFace == b.colour || newFace == b.colour) {
                        // C reads `g->faces[face]`, the argument, not
                        // `g->faces[j]` (`pearl.c:1015`). The queue holds
                        // only the argument in practice, so the two agree.
                        val f = g.faces[face]
                        for (k in 0 until f.order) b.edgesTodo.add(f.edge(k).index)
                    }
                }
            }

            for (b in boundaries) {
                // Re-decide the edges of this boundary.
                while (true) {
                    val j = b.edgesTodo.remove()
                    if (j < 0) break
                    val e = g.edges[j]
                    val fc1 = e.face1?.let { board[it.index] } ?: FACE_BLACK
                    val fc2 = e.face2?.let { board[it.index] } ?: FACE_BLACK
                    val oldEdge = b.edges[j]
                    val newEdge = (fc1 == b.colour) xor (fc2 == b.colour)
                    if (oldEdge != newEdge) {
                        b.edges[j] = newEdge
                        b.vertexTypesTodo.add(e.dot1.index)
                        b.vertexTypesTodo.add(e.dot2.index)
                    }
                }

                // Refresh the vertex types and their loop neighbours.
                while (true) {
                    val j = b.vertexTypesTodo.remove()
                    if (j < 0) break
                    val d = g.dots[j]
                    var type = 0
                    var n = 0
                    val neighbours = IntArray(2)
                    for (k in 0 until d.order) {
                        val e = d.edges[k]!!
                        val d2 = if (e.dot1 === d) e.dot2 else e.dot1
                        // dir: D = 0, R = 1, U = 2, L = 3.
                        val dir = (if (d.y == d2.y) 1 else 0) +
                            2 * (if (d.x + d.y > d2.x + d2.y) 1 else 0)
                        if (b.edges[e.index]) {
                            type = type or (1 shl dir)
                            neighbours[n++] = d2.index
                        }
                    }
                    if (type != 0 && type != 0x5 && type != 0xA) type = type or 0x10

                    if (type != b.vertexTypes[j]) {
                        if (b.vertexTypes[j] != 0) {
                            b.blackCluesTodo.add(b.neighbour0[j])
                            b.blackCluesTodo.add(b.neighbour1[j])
                        }
                        b.blackCluesTodo.add(j)
                        b.vertexTypes[j] = type
                        if (type != 0) {
                            // The growth rules keep a boundary vertex at
                            // degree 2. The tendril passes can create a
                            // four-way crossing, but loopgen never calls
                            // the bias after them, so this is unreachable.
                            check(n == 2) { "a boundary vertex has degree $n" }
                            b.neighbour0[j] = neighbours[0]
                            b.neighbour1[j] = neighbours[1]
                            b.blackCluesTodo.add(b.neighbour0[j])
                            b.blackCluesTodo.add(b.neighbour1[j])
                        }
                    }
                }

                // Recount the black clue sites.
                while (true) {
                    val j = b.blackCluesTodo.remove()
                    if (j < 0) break
                    score -= b.blackClues[j]
                    b.blackClues[j] = if ((b.vertexTypes[j] and 0x10) != 0 &&
                        ((b.vertexTypes[b.neighbour0[j]] or
                            b.vertexTypes[b.neighbour1[j]]) and 0x10) == 0
                    ) {
                        1
                    } else {
                        0
                    }
                    score += b.blackClues[j]
                }
            }
            return score
        }
    }

    /**
     * The same score computed from scratch, without the incremental
     * arrays. It is the reference for the incremental-context tests.
     */
    internal fun pearlBiasFromScratch(g: Grid, board: IntArray): Int {
        fun faceColour(face: GridFace?): Int =
            if (face == null) FACE_BLACK else board[face.index]

        var score = 0
        for (colour in intArrayOf(FACE_WHITE, FACE_BLACK)) {
            val on = BooleanArray(g.edges.size)
            for (e in g.edges) {
                on[e.index] = (faceColour(e.face1) == colour) xor
                    (faceColour(e.face2) == colour)
            }

            val types = IntArray(g.dots.size)
            val neighbour0 = IntArray(g.dots.size)
            val neighbour1 = IntArray(g.dots.size)
            for (d in g.dots) {
                var type = 0
                var n = 0
                for (k in 0 until d.order) {
                    val e = d.edges[k]!!
                    val d2 = if (e.dot1 === d) e.dot2 else e.dot1
                    val dir = (if (d.y == d2.y) 1 else 0) +
                        2 * (if (d.x + d.y > d2.x + d2.y) 1 else 0)
                    if (on[e.index]) {
                        type = type or (1 shl dir)
                        if (n == 0) neighbour0[d.index] = d2.index
                        if (n == 1) neighbour1[d.index] = d2.index
                        n++
                    }
                }
                check(n == 0 || n == 2) { "a boundary vertex has degree $n" }
                if (type != 0 && type != 0x5 && type != 0xA) type = type or 0x10
                types[d.index] = type
            }

            for (d in g.dots) {
                if ((types[d.index] and 0x10) == 0) continue
                val neighbours = types[neighbour0[d.index]] or types[neighbour1[d.index]]
                if ((neighbours and 0x10) == 0) score++
            }
        }
        return score
    }

    /**
     * `pearl_loopgen`, `pearl.c:1122-1222`: run the loop generator, then
     * turn the face colouring into lines.
     */
    internal fun pearlLoopgen(w: Int, h: Int, lines: IntArray, random: RandomState, g: Grid) {
        val board = IntArray(g.faces.size) { FACE_GREY }
        lines.fill(0)

        val bias = PearlBias(g)
        generateLoop(g, board, random) { current, face -> bias.score(current, face) }

        val scale = g.tileSize
        for (e in g.edges) {
            fun colourOf(face: GridFace?): Int =
                if (face == null) FACE_BLACK else board[face.index]

            val c1 = colourOf(e.face1)
            val c2 = colourOf(e.face2)
            check(c1 != FACE_GREY) { "a face was left grey" }
            check(c2 != FACE_GREY) { "a face was left grey" }
            if (c1 == c2) continue

            var x1 = e.dot1.x / scale
            var y1 = e.dot1.y / scale
            var x2 = e.dot2.x / scale
            var y2 = e.dot2.y / scale
            if (x1 == x2) {
                if (y1 > y2) {
                    val t = y1
                    y1 = y2
                    y2 = t
                }
                check(y1 + 1 == y2) { "a non-adjacent vertical loop edge" }
                lines[y1 * w + x1] = lines[y1 * w + x1] or D
                lines[y2 * w + x1] = lines[y2 * w + x1] or U
            } else if (y1 == y2) {
                if (x1 > x2) {
                    val t = x1
                    x1 = x2
                    x2 = t
                }
                check(x1 + 1 == x2) { "a non-adjacent horizontal loop edge" }
                lines[y1 * w + x1] = lines[y1 * w + x1] or R
                lines[y1 * w + x2] = lines[y1 * w + x2] or L
            } else {
                error("a diagonal loop edge")
            }
        }
    }

    // ------------------------------------------------------------------
    // Solver, `pearl.c:304-931`.
    // ------------------------------------------------------------------

    /**
     * `pearl_solve`. Returns 0 for an inconsistent board or two loops, 1
     * for a single deduced loop, and 2 for an ambiguous board. With
     * [partial] the pinned cells are written for every return value; the
     * caller's other cells stay as they were.
     */
    internal fun pearlSolve(
        w: Int,
        h: Int,
        clues: IntArray,
        result: IntArray,
        difficulty: Int,
        partial: Boolean,
    ): Int {
        val W = 2 * w + 1
        val H = 2 * h + 1
        val workspace = IntArray(W * H)
        var x: Int
        var y: Int
        var b: Int
        var d: Int
        var ret = -1

        // Square states.
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                workspace[(2 * yy + 1) * W + (2 * xx + 1)] = when (clues[yy * w + xx]) {
                    CORNER -> bLU or bLD or bRU or bRD
                    STRAIGHT -> bLR or bUD
                    else -> bLR or bUD or bLU or bLD or bRU or bRD or bBLANK
                }
            }
        }
        // Horizontal edges.
        for (yy in 0..h) {
            for (xx in 0 until w) {
                workspace[(2 * yy) * W + (2 * xx + 1)] = if (yy == 0 || yy == h) 2 else 3
            }
        }
        // Vertical edges.
        for (yy in 0 until h) {
            for (xx in 0..w) {
                workspace[(2 * yy + 1) * W + (2 * xx)] = if (xx == 0 || xx == w) 2 else 3
            }
        }

        val dsf = Dsf.new(w * h)
        val dsfSize = IntArray(w * h)

        while (true) {
            var doneSomething = false

            // Discard a cell state that contradicts a known edge.
            y = 0
            while (y < h) {
                x = 0
                while (x < w) {
                    val square = (2 * y + 1) * W + (2 * x + 1)
                    b = 0
                    while (b < 0xD) {
                        if ((workspace[square] and (1 shl b)) != 0) {
                            d = 1
                            while (d <= 8) {
                                val ex = 2 * x + 1 + dx(d)
                                val ey = 2 * y + 1 + dy(d)
                                if (workspace[ey * W + ex] == (if ((b and d) != 0) 2 else 1)) {
                                    workspace[square] = workspace[square] and (1 shl b).inv()
                                    doneSomething = true
                                    break
                                }
                                d *= 2
                            }
                        }
                        b++
                    }
                    if (workspace[square] == 0) {
                        ret = 0
                        return finishSolve(
                            ret, partial, result, workspace, W, w, h,
                        )
                    }
                    x++
                }
                y++
            }

            // Nail an unknown edge from the possible cell states.
            y = 0
            while (y < h) {
                x = 0
                while (x < w) {
                    val square = (2 * y + 1) * W + (2 * x + 1)
                    var edgeOr = 0
                    var edgeAnd = 15
                    b = 0
                    while (b < 0xD) {
                        if ((workspace[square] and (1 shl b)) != 0) {
                            edgeOr = edgeOr or b
                            edgeAnd = edgeAnd and b
                        }
                        b++
                    }
                    if ((edgeAnd and edgeOr.inv()) != 0) {
                        ret = 0
                        return finishSolve(
                            ret, partial, result, workspace, W, w, h,
                        )
                    }
                    d = 1
                    while (d <= 8) {
                        val ex = 2 * x + 1 + dx(d)
                        val ey = 2 * y + 1 + dy(d)
                        if ((edgeOr and d) == 0 && workspace[ey * W + ex] == 3) {
                            workspace[ey * W + ex] = 2
                            doneSomething = true
                        } else if ((edgeAnd and d) != 0 && workspace[ey * W + ex] == 3) {
                            workspace[ey * W + ex] = 1
                            doneSomething = true
                        }
                        d *= 2
                    }
                    x++
                }
                y++
            }

            if (doneSomething) continue

            // Clue-based rules.
            y = 0
            while (y < h) {
                x = 0
                while (x < w) {
                    val square = (2 * y + 1) * W + (2 * x + 1)
                    when (clues[y * w + x]) {
                        CORNER -> {
                            d = 1
                            while (d <= 8) {
                                val ex = 2 * x + 1 + dx(d)
                                val ey = 2 * y + 1 + dy(d)
                                val fx = ex + dx(d)
                                val fy = ey + dy(d)
                                val type = d or f(d)
                                if (workspace[ey * W + ex] == 1) {
                                    if (workspace[fy * W + fx] != (1 shl type)) {
                                        workspace[fy * W + fx] = 1 shl type
                                        doneSomething = true
                                    }
                                } else if (workspace[ey * W + ex] == 3) {
                                    if ((workspace[fy * W + fx] and (1 shl type)) == 0) {
                                        workspace[ey * W + ex] = 2
                                        doneSomething = true
                                    }
                                }
                                d *= 2
                            }
                        }
                        STRAIGHT -> {
                            d = 1
                            while (d <= 2) {
                                val fx = 2 * x + 1 + 2 * dx(d)
                                val fy = 2 * y + 1 + 2 * dy(d)
                                val gx = 2 * x + 1 - 2 * dx(d)
                                val gy = 2 * y + 1 - 2 * dy(d)
                                val type = d or f(d)
                                if ((workspace[square] and (1 shl type)) != 0 &&
                                    (workspace[fy * W + fx] and
                                        ((1 shl (f(d) or a(d))) or (1 shl (f(d) or c(d))))) == 0 &&
                                    (workspace[gy * W + gx] and
                                        ((1 shl (d or a(d))) or (1 shl (d or c(d))))) == 0
                                ) {
                                    workspace[square] = workspace[square] and (1 shl type).inv()
                                    doneSomething = true
                                }
                                d *= 2
                            }
                            d = 1
                            while (d <= 8) {
                                val fx = 2 * x + 1 + 2 * dx(d)
                                val fy = 2 * y + 1 + 2 * dy(d)
                                val gx = 2 * x + 1 - 2 * dx(d)
                                val gy = 2 * y + 1 - 2 * dy(d)
                                val type = d or f(d)
                                if (workspace[square] == (1 shl type) &&
                                    (workspace[fy * W + fx] and (bLR or bUD).inv()) == 0 &&
                                    (workspace[gy * W + gx] and (bLU or bLD or bRU or bRD).inv()) != 0
                                ) {
                                    workspace[gy * W + gx] =
                                        workspace[gy * W + gx] and (bLU or bLD or bRU or bRD)
                                    doneSomething = true
                                }
                                d *= 2
                            }
                        }
                    }
                    x++
                }
                y++
            }

            if (doneSomething) continue

            // Detect loops and shortcut loops. `run` keeps the C block
            // structure and allows the early `return` to leave the solver.
            run {
                var nonblanks = 0
                var loopClass = -1

                dsf.reinit()
                dsfSize.fill(1)

                y = 1
                while (y < H - 1) {
                    x = 1
                    while (x < W - 1) {
                        if (((y xor x) and 1) != 0) {
                            val ax = (x - 1) / 2
                            val ay = (y - 1) / 2
                            val ac = ay * w + ax
                            val bx = x / 2
                            val by = y / 2
                            val bc = by * w + bx
                            if (workspace[y * W + x] == 1) {
                                var ae = dsf.canonify(ac)
                                val be = dsf.canonify(bc)
                                if (ae == be) {
                                    if (loopClass != -1) {
                                        ret = 0
                                        return finishSolve(
                                            ret, partial, result, workspace, W, w, h,
                                        )
                                    }
                                    loopClass = ae
                                } else {
                                    val size = dsfSize[ae] + dsfSize[be]
                                    dsf.merge(ac, bc)
                                    ae = dsf.canonify(ac)
                                    dsfSize[ae] = size
                                }
                            }
                        } else if (((y and x) and 1) != 0) {
                            if ((workspace[y * W + x] and bBLANK) == 0) nonblanks++
                        }
                        x++
                    }
                    y++
                }

                if (loopClass != -1) {
                    for (yy in 0 until h) {
                        for (xx in 0 until w) {
                            if (dsf.canonify(yy * w + xx) != loopClass) {
                                val square = (yy * 2 + 1) * W + (xx * 2 + 1)
                                if ((workspace[square] and bBLANK) != 0) {
                                    workspace[square] = bBLANK
                                } else {
                                    ret = 0
                                    return finishSolve(
                                        ret, partial, result, workspace, W, w, h,
                                    )
                                }
                            }
                        }
                    }
                    ret = 1
                    return finishSolve(ret, partial, result, workspace, W, w, h)
                }

                // Further deductions are tricky.
                if (difficulty != DIFF_EASY) {
                    y = 1
                    while (y < H - 1) {
                        x = 1
                        while (x < W - 1) {
                            if (((y xor x) and 1) != 0) {
                                val ax = (x - 1) / 2
                                val ay = (y - 1) / 2
                                val ac = ay * w + ax
                                val bx = x / 2
                                val by = y / 2
                                val bc = by * w + bx
                                if (workspace[y * W + x] == 3) {
                                    val ae = dsf.canonify(ac)
                                    val be = dsf.canonify(bc)
                                    if (ae == be && dsfSize[ae] < nonblanks) {
                                        workspace[y * W + x] = 2
                                        doneSomething = true
                                    }
                                }
                            } else if (((y and x) and 1) != 0) {
                                val ae = dsf.canonify((y / 2) * w + (x / 2))
                                b = 2
                                while (b < 0xD) {
                                    if ((workspace[y * W + x] and (1 shl b)) != 0) {
                                        var e = -1
                                        var connections = 0
                                        d = 1
                                        while (d <= 8) {
                                            if ((b and d) != 0) {
                                                val xx = x / 2 + dx(d)
                                                val yy = y / 2 + dy(d)
                                                val ee = dsf.canonify(yy * w + xx)
                                                if (e == -1) {
                                                    e = ee
                                                } else if (e != ee) {
                                                    e = -2
                                                }
                                                if (workspace[(y + dy(d)) * W + (x + dx(d))] == 1) {
                                                    connections++
                                                }
                                            }
                                            d *= 2
                                        }
                                        if (e >= 0 && connections < 2) {
                                            var loopSize = dsfSize[e]
                                            if (e != ae) loopSize++
                                            if (loopSize < nonblanks) {
                                                workspace[y * W + x] =
                                                    workspace[y * W + x] and (1 shl b).inv()
                                                doneSomething = true
                                            }
                                        }
                                    }
                                    b++
                                }
                            }
                            x++
                        }
                        y++
                    }
                }
            }

            if (doneSomething) continue

            ret = 2
            return finishSolve(ret, partial, result, workspace, W, w, h)
        }
    }

    /**
     * The `cleanup` block of `pearl_solve`, `pearl.c:875-931`: transcribe
     * the pinned cells and clear a direction that the neighbour does not
     * reciprocate.
     */
    private fun finishSolve(
        ret: Int,
        partial: Boolean,
        result: IntArray,
        workspace: IntArray,
        W: Int,
        w: Int,
        h: Int,
    ): Int {
        if (ret == 1 || partial) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val square = (2 * y + 1) * W + (2 * x + 1)
                    var found = false
                    for (b in 0 until 0xD) {
                        if (workspace[square] == (1 shl b)) {
                            result[y * w + x] = b
                            found = true
                            break
                        }
                    }
                    // `pearl.c:890`: `assert(b < 0xD)` pins that a
                    // solved board leaves exactly one state per cell.
                    if (ret == 1) {
                        check(found) { "the C solver left a cell without a single state" }
                    }
                }
            }

            for (y in 0 until h) {
                for (x in 0 until w) {
                    for (d in intArrayOf(1, 2, 4, 8)) {
                        val nx = x + dx(d)
                        val ny = y + dy(d)
                        val rlink = if (nx in 0 until w && ny in 0 until h) {
                            result[ny * w + nx] and f(d)
                        } else {
                            0
                        }
                        if (rlink == 0) result[y * w + x] = result[y * w + x] and d.inv()
                    }
                }
            }
        }
        return ret
    }

    // ------------------------------------------------------------------
    // Completion, `pearl.c:1562-1782`.
    // ------------------------------------------------------------------

    private val NBITS = intArrayOf(
        0, 1, 1, 2,
        1, 2, 2, 3,
        1, 2, 2, 3,
        2, 3, 3, 4,
    )

    private fun nbits(line: Int): Int =
        if (line < 0 || line > 15) 4 else NBITS[line]

    private fun inGrid(state: State, x: Int, y: Int): Boolean =
        x >= 0 && x < state.shared.w && y >= 0 && y < state.shared.h

    /**
     * `dsf_update_completion`, `pearl.c:1571-1590`. The second test in C
     * repeats the first and is dead code; the observable rule is that a
     * non-reciprocal link rejects the state.
     */
    private fun dsfUpdateCompletion(state: State, ax: Int, ay: Int, dir: Int, dsf: Dsf): Boolean {
        val w = state.shared.w
        val ac = ay * w + ax
        if ((state.lines[ac] and dir) == 0) return true
        val bx = ax + dx(dir)
        val by = ay + dy(dir)
        if (!inGrid(state, bx, by)) return false
        val bc = by * w + bx
        if ((state.lines[bc] and f(dir)) == 0) return false
        dsf.merge(ac, bc)
        return true
    }

    /**
     * `check_completion`. Returns false for an invalid line structure
     * (an edge off the grid or a non-reciprocal one). With [mark] it also
     * rewrites the error bits and can set the sticky [State.completed].
     */
    internal fun checkCompletion(state: State, mark: Boolean): Boolean {
        val w = state.shared.w
        val h = state.shared.h
        var hadError = false

        if (mark) state.errors.fill(0)

        fun error(x: Int, y: Int, e: Int) {
            hadError = true
            if (mark) state.errors[y * w + x] = state.errors[y * w + x] or e
        }

        val dsf = Dsf.new(w * h)

        for (x in 0 until w) {
            for (y in 0 until h) {
                if (!dsfUpdateCompletion(state, x, y, R, dsf) ||
                    !dsfUpdateCompletion(state, x, y, D, dsf)
                ) {
                    return false
                }
            }
        }

        val COMP_NONE = 0
        val COMP_LOOP = 1
        val COMP_PATH = 2
        val COMP_SILLY = 3
        val COMP_EMPTY = 4
        val componentState = IntArray(w * h)
        for (i in 0 until w * h) {
            componentState[i] = if (dsf.canonify(i) == i) COMP_LOOP else COMP_NONE
        }

        for (x in 0 until w) {
            for (y in 0 until h) {
                val type = state.lines[y * w + x]
                val degree = nbits(type)
                val comp = dsf.canonify(y * w + x)
                if (degree > 2) {
                    error(x, y, type)
                    componentState[comp] = COMP_SILLY
                } else if (degree == 0) {
                    componentState[comp] = COMP_EMPTY
                } else if (degree == 1) {
                    if (componentState[comp] != COMP_SILLY) {
                        componentState[comp] = COMP_PATH
                    }
                }
            }
        }

        var nsilly = 0
        var nloop = 0
        var npath = 0
        var totalPathSize = 0
        var largestComp = -1
        var largestSize = -1
        for (i in 0 until w * h) {
            when (componentState[i]) {
                COMP_SILLY -> nsilly++
                COMP_PATH -> {
                    totalPathSize += dsf.size(i)
                    npath = 1
                }
                COMP_LOOP -> {
                    nloop++
                    val thisSize = dsf.size(i)
                    if (thisSize > largestSize) {
                        largestComp = i
                        largestSize = thisSize
                    }
                }
            }
        }
        if (largestSize < totalPathSize) {
            largestComp = -1
            largestSize = totalPathSize
        }

        if (nloop > 0 && nloop + npath > 1) {
            for (i in 0 until w * h) {
                val comp = dsf.canonify(i)
                if ((componentState[comp] == COMP_PATH && largestComp != -1) ||
                    (componentState[comp] == COMP_LOOP && comp != largestComp)
                ) {
                    error(i % w, i / w, state.lines[i])
                }
            }
        }

        for (x in 0 until w) {
            for (y in 0 until h) {
                val type = state.lines[y * w + x]
                if (state.shared.clues[y * w + x] == CORNER) {
                    if ((STRAIGHT_MASK and (1 shl type)) != 0) {
                        error(x, y, ERROR_CLUE)
                    }
                    for (d in intArrayOf(1, 2, 4, 8)) {
                        if ((type and d) == 0) continue
                        val xx = x + dx(d)
                        val yy = y + dy(d)
                        if (!inGrid(state, xx, yy)) {
                            error(x, y, d)
                        } else if ((CORNER_MASK and (1 shl state.lines[yy * w + xx])) != 0) {
                            error(x, y, ERROR_CLUE)
                        }
                    }
                } else if (state.shared.clues[y * w + x] == STRAIGHT) {
                    if ((CORNER_MASK and (1 shl type)) != 0) {
                        error(x, y, ERROR_CLUE)
                    }
                    var straights = 0
                    for (d in intArrayOf(1, 2, 4, 8)) {
                        if ((type and d) == 0) continue
                        val xx = x + dx(d)
                        val yy = y + dy(d)
                        if (!inGrid(state, xx, yy)) {
                            error(x, y, d)
                        } else if ((STRAIGHT_MASK and (1 shl state.lines[yy * w + xx])) != 0) {
                            straights++
                        }
                    }
                    if (straights >= 2 && nbits(type) >= 2) {
                        error(x, y, ERROR_CLUE)
                    }
                }
            }
        }

        if (nloop == 1 && nsilly == 0 && npath == 0) {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    if (state.lines[y * w + x] == BLANK) {
                        if (state.shared.clues[y * w + x] != NOCLUE) {
                            error(x, y, ERROR_CLUE)
                        }
                    }
                }
            }
            if (!hadError) state.completed = true
        }
        return true
    }

    // ------------------------------------------------------------------
    // Text, solve, and moves, `pearl.c:1793-2431`.
    // ------------------------------------------------------------------

    /** `game_text_format`, `pearl.c:1860-1889`. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.shared.w
        val h = s.shared.h
        val cw = 4
        val ch = 2
        val gw = cw * (w - 1) + 2
        val gh = ch * (h - 1) + 1
        val board = CharArray(gw * gh) { ' ' }

        for (r in 0 until h) {
            for (c in 0 until w) {
                val i = r * w + c
                val cell = r * ch * gw + c * cw
                board[cell] = when (s.shared.clues[i]) {
                    CORNER -> 'B'
                    STRAIGHT -> 'W'
                    else -> '+'
                }
                if (c < w - 1 &&
                    ((s.lines[i] and R) != 0 || (s.lines[i + 1] and L) != 0)
                ) {
                    for (j in 1 until cw) board[cell + j] = '-'
                }
                if (r < h - 1 &&
                    ((s.lines[i] and D) != 0 || (s.lines[i + w] and U) != 0)
                ) {
                    for (j in 1 until ch) board[cell + j * gw] = '|'
                }
                if (c < w - 1 &&
                    ((s.marks[i] and R) != 0 || (s.marks[i + 1] and L) != 0)
                ) {
                    board[cell + cw / 2] = 'x'
                }
                if (r < h - 1 &&
                    ((s.marks[i] and D) != 0 || (s.marks[i + w] and U) != 0)
                ) {
                    board[cell + (ch / 2) * gw] = 'x'
                }
            }
            val lines = if (r == h - 1) 1 else ch
            for (j in 0 until lines) {
                board[r * ch * gw + (gw - 1) + j * gw] = '\n'
            }
        }
        return String(board)
    }

    /** A solve attempt: the move text, or the C error message. */
    sealed interface SolveResult {
        data class Move(val text: String) : SolveResult
        data class Error(val message: String) : SolveResult
    }

    /**
     * `solve_game`, `pearl.c:1810-1853`, with the error text kept.
     *
     * C copies `states[0].state`, whose lines are always empty, as the
     * result buffer. A cell that the solver does not pin down therefore
     * reads as empty, which is what makes an ambiguous board solve to a
     * diff from the player's lines. The copy here starts with empty
     * lines for the same reason.
     */
    fun solveAttempt(state: Game.State, auxInfo: String? = null): SolveResult {
        val s = state as State
        val size = s.shared.size
        val solvedLines = IntArray(size)
        val ret: Int

        if (auxInfo != null) {
            for (i in 0 until size) {
                val c = if (i < auxInfo.length) auxInfo[i] else '\u0000'
                solvedLines[i] = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return SolveResult.Error("invalid char in aux")
                }
            }
            ret = 1
        } else {
            // C tries the current state first and then the initial state.
            // The solver only reads the clues, so the two calls are the
            // same; the second keeps the C control flow.
            var r = pearlSolve(s.shared.w, s.shared.h, s.shared.clues, solvedLines, DIFFCOUNT, false)
            if (r < 1) {
                r = pearlSolve(s.shared.w, s.shared.h, s.shared.clues, solvedLines, DIFFCOUNT, false)
            }
            ret = r
        }

        if (ret < 1) return SolveResult.Error("Unable to find solution")
        return SolveResult.Move(solveForDiff(s, s.lines, solvedLines))
    }

    override fun solve(state: Game.State, auxInfo: String?): String? =
        when (val result = solveAttempt(state, auxInfo)) {
            is SolveResult.Move -> result.text
            is SolveResult.Error -> null
        }

    /** `solve_game` with the two C error strings (`pearl.c:1810-1853`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        when (val result = solveAttempt(state, auxInfo)) {
            is SolveResult.Move -> SolveOutcome(result.text, null)
            is SolveResult.Error -> SolveOutcome(null, result.message)
        }

    /** `solve_for_diff`, `pearl.c:1793-1808`. */
    private fun solveForDiff(state: State, oldLines: IntArray, newLines: IntArray): String {
        val w = state.shared.w
        val out = StringBuilder("S")
        for (i in oldLines.indices) {
            if (oldLines[i] != newLines[i]) {
                out.append(";R").append(newLines[i]).append(',')
                    .append(i % w).append(',').append(i / w)
            }
        }
        return out.toString()
    }

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.shared.w
        val ret = s.copy() as State
        var at = 0

        while (at < move.length) {
            val c = move[at]
            if (c == 'S') {
                ret.usedSolve = true
                at++
            } else if (c == 'L' || c == 'N' || c == 'R' || c == 'F' || c == 'M') {
                at++
                val token = scanTriple(move, at) ?: return null
                val l = token.l
                val x = token.x
                val y = token.y
                if (!inGrid(ret, x, y)) return null
                if (l < 0 || l > 15) return null
                val i = y * w + x
                when (c) {
                    'L' -> ret.lines[i] = ret.lines[i] or l
                    'N' -> ret.lines[i] = ret.lines[i] and l.inv()
                    'R' -> {
                        ret.lines[i] = l
                        ret.marks[i] = ret.marks[i] and l.inv()
                    }
                    'F' -> ret.lines[i] = ret.lines[i] xor l
                    'M' -> ret.marks[i] = ret.marks[i] xor l
                }
                // The test is per cell, so a line and a mark for the same
                // edge at different cells are accepted, as in C.
                if ((ret.lines[i] and l) != 0 && (ret.marks[i] and l) != 0) return null
                at = token.end
            } else if (c == 'H' && at + 1 == move.length) {
                pearlSolve(ret.shared.w, ret.shared.h, ret.shared.clues, ret.lines, DIFFCOUNT, true)
                for (n in 0 until w * ret.shared.h) {
                    ret.marks[n] = ret.marks[n] and ret.lines[n].inv()
                }
                at++
            } else {
                return null
            }

            if (at < move.length && move[at] == ';') {
                at++
            } else if (at < move.length) {
                return null
            }
        }

        if (!checkCompletion(ret, true)) return null
        return ret
    }

    private class ScannedTriple(val l: Int, val x: Int, val y: Int, val end: Int)

    /**
     * `sscanf(move, "%d,%d,%d%n")`: three C integers separated by exact
     * commas. White space and a sign are accepted before each number,
     * and overflow saturates. The `%n` position is the end of the third
     * number. Trailing junk is left for the caller.
     */
    private fun scanTriple(text: String, start: Int): ScannedTriple? {
        val first = scanInt(text, start) ?: return null
        var at = first.end
        if (at >= text.length || text[at] != ',') return null
        val second = scanInt(text, at + 1) ?: return null
        at = second.end
        if (at >= text.length || text[at] != ',') return null
        val third = scanInt(text, at + 1) ?: return null
        return ScannedTriple(first.value, second.value, third.value, third.end)
    }

    private class ScannedInt(val value: Int, val end: Int)

    /** One `%d` conversion. Returns null when no digit is present. */
    private fun scanInt(text: String, start: Int): ScannedInt? {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++

        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }

        val firstDigit = i
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
        if (i == firstDigit) return null

        val parsed = when {
            overflow -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            negative -> -value
            else -> value
        }
        return ScannedInt(parsed.toInt(), i)
    }

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    // ------------------------------------------------------------------
    // UI, keys, and preferences, `pearl.c:1891-2022`.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui =
        Ui((state as State).shared.size)

    /** `android_cursor_visibility`, `pearl.c:1955-1958`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).cursorActive = visible
    }

    /**
     * `game_changed_state`, `pearl.c:1984-1988`. A solve sets
     * `used_solve`, so it never raises the completion event; a hint that
     * completes does.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val n = newState as State
        return n.completed && !n.usedSolve && oldState != null && !(oldState as State).completed
    }

    /**
     * `current_key_label`, `pearl.c:1990-2002`. The seam has no
     * operation for it, so it is a public helper with a test.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if ((button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) &&
            u.cursorActive
        ) {
            if (button == Buttons.CURSOR_SELECT) {
                return if (u.ndragcoords == -1) "Start" else "Stop"
            }
            if (button == Buttons.CURSOR_SELECT2 && u.ndragcoords >= 0) return "Cancel"
        }
        return ""
    }

    /**
     * `game_request_keys` is NULL, so the midend returns no labels and
     * the default Left/Right arrow mode. The Android full-cursor hint
     * forces `ANDROID_ARROWS_ONLY`, as it does for every game without the
     * callback.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // Geometry, palette, and drawing, `pearl.c:2437-2722`.
    // ------------------------------------------------------------------

    private fun halfsz(tileSize: Int): Int = (tileSize - 1) / 2

    private fun tileSizeFor(ds: DrawState): Int = ds.halfsz * 2 + 1

    private fun borderFor(ui: Ui, tile: Int): Int =
        if (ui.appearance == Appearance.LOOPY) tile / 8 else tile / 2

    private fun borderWidthFor(tile: Int): Int = max(tile / 32, 1)

    private fun coord(index: Int, tile: Int, border: Int): Int = index * tile + border

    private fun centeredCoord(index: Int, tile: Int, border: Int): Int =
        coord(index, tile, border) + tile / 2

    private fun fromCoord(value: Int, tile: Int, border: Int): Int =
        if (value < border) -1 else (value - border) / tile

    /** `game_compute_size` in the traditional appearance. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val half = halfsz(tileSize)
        val tile = half * 2 + 1
        val border = tile / 2
        return Size(p.w * tile + 2 * border, p.h * tile + 2 * border)
    }

    /** `game_compute_size` with the appearance preference, `pearl.c:2439-2448`. */
    override fun computeSize(params: Game.Params, ui: Game.Ui, tileSize: Int): Size {
        val p = params as Params
        val half = halfsz(tileSize)
        val tile = half * 2 + 1
        val appearance = (ui as? Ui)?.appearance ?: Appearance.TRADITIONAL
        val border = if (appearance == Appearance.LOOPY) tile / 8 else tile / 2
        return Size(p.w * tile + 2 * border, p.h * tile + 2 * border)
    }

    /** `game_get_cursor_location`, `pearl.c:2711-2722`. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.cursorActive) return null
        val half = halfsz(tileSize)
        val tile = half * 2 + 1
        val border = borderFor(u, tile)
        return Game.CursorRect(
            left = coord(u.curx, tile, border),
            top = coord(u.cury, tile, border),
            width = tile,
            height = tile,
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`, `pearl.c:2456-2488`. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(3 * NCOLOURS)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)
        for (i in 0 until 3) {
            ret[COL_BLACK * 3 + i] = 0.0f
            ret[COL_WHITE * 3 + i] = 1.0f
            ret[COL_GRID * 3 + i] = 0.4f
        }
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
        return ret
    }

    override fun newDrawState(state: Game.State, tileSize: Int, sink: DrawSink): Game.DrawState {
        val s = state as State
        return DrawState(s.shared.w, s.shared.h, halfsz(tileSize))
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
        val w = s.shared.w
        val h = s.shared.h
        val sz = s.shared.size
        val tile = tileSizeFor(ds)
        val border = borderFor(u, tile)
        var force = false

        if (!ds.started) {
            if (u.appearance == Appearance.TRADITIONAL) {
                val borderWidth = borderWidthFor(tile)
                sink.rect(
                    border - borderWidth,
                    border - borderWidth,
                    w * tile + 2 * borderWidth + 1,
                    h * tile + 2 * borderWidth + 1,
                    COL_GRID,
                )
            }
            sink.update(0, 0, w * tile + 2 * border, h * tile + 2 * border)
            ds.started = true
            force = true
        }

        // The seam has no flash time, so DS_FLASH is never set. The
        // oracle's allow_flash is 0 and no golden trace uses it.
        val flashing = 0

        ds.draglines.fill(0)
        if (u.ndragcoords > 0) {
            var clearing = true
            for (i in 0 until u.ndragcoords - 1) {
                val drag = interpretUiDrag(s, u, clearing, i)
                clearing = drag.clearing
                ds.draglines[drag.sy * w + drag.sx] =
                    ds.draglines[drag.sy * w + drag.sx] xor (drag.oldState xor drag.newState)
                ds.draglines[drag.dy * w + drag.dx] =
                    ds.draglines[drag.dy * w + drag.dx] xor
                    (f(drag.oldState) xor f(drag.newState))
            }
        }

        for (x in 0 until w) {
            for (y in 0 until h) {
                val i = y * w + x
                var flags = s.lines[i]
                val errorLine = s.errors[i] and (R or U or L or D)
                flags = flags or (errorLine shl DS_ESHIFT)
                flags = flags or (ds.draglines[i] shl DS_DSHIFT)
                flags = flags or (s.marks[i] shl DS_MSHIFT)
                if ((s.errors[i] and ERROR_CLUE) != 0) flags = flags or DS_ERROR_CLUE
                flags = flags or flashing
                if (u.cursorActive && x == u.curx && y == u.cury) {
                    flags = flags or DS_CURSOR
                }
                if (flags != ds.lflags[i] || force) {
                    ds.lflags[i] = flags
                    drawSquare(sink, ds, u, x, y, flags, s.shared.clues[i], tile, border)
                }
            }
        }
    }

    private class DragEdge(
        val sx: Int,
        val sy: Int,
        val dx: Int,
        val dy: Int,
        val dir: Int,
        val oldState: Int,
        val newState: Int,
        val clearing: Boolean,
    )

    /**
     * `interpret_ui_drag`, `pearl.c:2052-2082`. The first edge that was
     * absent clears the `clearing` flag, so a drag that starts on a laid
     * edge removes edges until it meets an empty one.
     */
    private fun interpretUiDrag(state: State, ui: Ui, clearing: Boolean, i: Int): DragEdge {
        val w = state.shared.w
        val sp = ui.dragcoords[i]
        val dp = ui.dragcoords[i + 1]
        val sy = sp / w
        val sx = sp % w
        val dy = dp / w
        val dx = dp % w
        val dir = if (dy > sy) D else if (dy < sy) U else if (dx > sx) R else L
        val oldState = state.lines[sp] and dir
        var nextClearing = clearing
        val newState: Int
        if (oldState != 0) {
            newState = if (clearing) 0 else dir
        } else {
            newState = dir
            nextClearing = false
        }
        return DragEdge(sx, sy, dx, dy, dir, oldState, newState, nextClearing)
    }

    /**
     * Whether [pos] is already on the drag path. C appends every
     * intermediate cell of a rook step without a visit test, so a drag
     * that crosses its own path can grow past the path array. See D-019.
     */
    private fun onDragPath(ui: Ui, pos: Int): Boolean {
        for (i in 0 until ui.ndragcoords) {
            if (ui.dragcoords[i] == pos) return true
        }
        return false
    }

    /**
     * `update_ui_drag`, `pearl.c:2084-2178`.
     *
     * C writes `dragcoords[ndragcoords]` in the loop-drag branch, and a
     * drag that visits every cell reaches `ndragcoords == sz`. C also
     * appends the closing step at the same index. The port allocates one
     * extra entry, so both writes stay in bounds. See D-019.
     */
    private fun updateUiDrag(state: State, ui: Ui, gx: Int, gy: Int) {
        val w = state.shared.w

        if (!inGrid(state, gx, gy)) return
        if (ui.ndragcoords < 0) return

        val pos = gy * w + gx
        val lastpos = ui.dragcoords[if (ui.ndragcoords > 0) ui.ndragcoords - 1 else 0]
        if (pos == lastpos) return

        if (ui.ndragcoords == 0) ui.ndragcoords = 1

        for (i in 1 until ui.ndragcoords) {
            if (pos == ui.dragcoords[i]) {
                ui.ndragcoords = i + 1
                return
            }
        }

        if (pos == ui.dragcoords[0]) {
            // The path can hold `sz + 1` entries. `ndragcoords == sz` is
            // the last in-bounds slot for the closing cell. The revisit
            // rule below makes a longer path impossible, so this guard
            // only protects a broken invariant.
            if (ui.ndragcoords >= ui.dragcoords.size) return
            ui.dragcoords[ui.ndragcoords] = pos
            var clearing = true
            var lines = state.lines[pos] and (L or R or U or D)
            for (i in 0 until ui.ndragcoords) {
                val drag = interpretUiDrag(state, ui, clearing, i)
                clearing = drag.clearing
                if (drag.sx == gx && drag.sy == gy) {
                    lines = lines xor (drag.oldState xor drag.newState)
                }
                if (drag.dx == gx && drag.dy == gy) {
                    lines = lines xor (f(drag.oldState) xor f(drag.newState))
                }
            }
            if (nbits(lines) > 2) {
                ui.ndragcoords = 1
                return
            }
        }

        var oy = ui.dragcoords[ui.ndragcoords - 1] / w
        var ox = ui.dragcoords[ui.ndragcoords - 1] % w
        if (ox == gx || oy == gy) {
            val dx = if (gx < ox) -1 else if (gx > ox) 1 else 0
            val dy = if (gy < oy) -1 else if (gy > oy) 1 else 0
            val dir = if (dy > 0) D else if (dy < 0) U else if (dx > 0) R else L
            while (ox != gx || oy != gy) {
                if ((state.marks[oy * w + ox] and dir) != 0) break
                ox += dx
                oy += dy
                val next = oy * w + ox
                // C appends every intermediate cell of a rook step
                // without a visit test, so a drag that crosses its own
                // path writes past `dragcoords` (`pearl.c:2165`). The
                // port stops at the first cell that is already on the
                // path. The start cell is allowed only as the last step,
                // so a drag can still close the loop. See D-019.
                if (onDragPath(ui, next) &&
                    !(next == ui.dragcoords[0] && ox == gx && oy == gy)
                ) {
                    break
                }
                ui.dragcoords[ui.ndragcoords++] = next
            }
        }
    }

    /** `mark_in_direction`, `pearl.c:2180-2198`. */
    private fun markInDirection(
        state: State,
        x: Int,
        y: Int,
        dir: Int,
        primary: Boolean,
    ): MoveResult {
        val w = state.shared.w
        val x2 = x + dx(dir)
        val y2 = y + dy(dir)
        val dir2 = f(dir)
        val ch = if (primary) 'F' else 'M'

        if (!inGrid(state, x, y) || !inGrid(state, x2, y2)) return MoveResult.UiUpdate

        val other = if (primary) state.marks else state.lines
        if ((other[y * w + x] and dir) != 0 || (other[y2 * w + x2] and dir2) != 0) {
            return MoveResult.UiUpdate
        }
        return MoveResult.Move("$ch$dir,$x,$y;$ch$dir2,$x2,$y2")
    }

    /** `KEY_DIRECTION`, `pearl.c:2200-2202`. */
    private fun keyDirection(button: Int): Int = when (button) {
        Buttons.CURSOR_DOWN -> D
        Buttons.CURSOR_UP -> U
        Buttons.CURSOR_LEFT -> L
        else -> R
    }

    /** `move_cursor` for the four cursor keys. The return value is unused. */
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
        ui.curx = min(max(ui.curx + dx, 0), maxw - 1)
        ui.cury = min(max(ui.cury + dy, 0), maxh - 1)
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
        val w = s.shared.w
        val h = s.shared.h
        val half = halfsz(tileSize)
        val tile = half * 2 + 1
        val border = borderFor(u, tile)

        var px = x
        var py = y
        var gx = fromCoord(px, tile, border)
        var gy = fromCoord(py, tile, border)
        var release = false

        val shift = (button and Buttons.MOD_SHFT) != 0
        val control = (button and Buttons.MOD_CTRL) != 0
        var b = Buttons.stripModifiers(button)

        if (Buttons.isMouseDown(b)) {
            u.cursorActive = false
            if (!inGrid(s, gx, gy)) {
                u.ndragcoords = -1
                return MoveResult.UiUpdate
            }
            u.clickx = px
            u.clicky = py
            u.dragcoords[0] = gy * w + gx
            u.ndragcoords = 0
            return MoveResult.UiUpdate
        }

        if (b == Buttons.LEFT_DRAG && u.ndragcoords >= 0) {
            updateUiDrag(s, u, gx, gy)
            return MoveResult.UiUpdate
        }

        if (Buttons.isMouseRelease(b)) release = true

        if (Buttons.isCursorMove(b)) {
            if (!u.cursorActive) {
                u.cursorActive = true
            } else if (control || shift) {
                if (u.ndragcoords > 0) return MoveResult.NoEffect
                u.ndragcoords = -1
                val move = markInDirection(s, u.curx, u.cury, keyDirection(b), control)
                if (control && !shift && move is MoveResult.Move) {
                    moveCursor(b, u, w, h)
                }
                return move
            } else {
                moveCursor(b, u, w, h)
                if (u.ndragcoords >= 0) updateUiDrag(s, u, u.curx, u.cury)
            }
            return MoveResult.UiUpdate
        }

        if (b == Buttons.CURSOR_SELECT || b == Buttons.CURSOR_SELECT2) {
            if (!u.cursorActive) {
                u.cursorActive = true
                return MoveResult.UiUpdate
            } else if (b == Buttons.CURSOR_SELECT) {
                if (u.ndragcoords == -1) {
                    u.ndragcoords = 0
                    u.dragcoords[0] = u.cury * w + u.curx
                    u.clickx = centeredCoord(u.curx, tile, border)
                    u.clicky = centeredCoord(u.cury, tile, border)
                    return MoveResult.UiUpdate
                } else {
                    release = true
                }
            } else if (b == Buttons.CURSOR_SELECT2) {
                if (u.ndragcoords >= 0) {
                    u.ndragcoords = -1
                    return MoveResult.UiUpdate
                }
                return MoveResult.NoEffect
            }
        }

        if (b == 27 || b == '\b'.code) {
            if (u.ndragcoords >= 0) {
                u.ndragcoords = -1
                return MoveResult.UiUpdate
            }
            return MoveResult.NoEffect
        }

        if (release) {
            if (u.ndragcoords > 0) {
                var clearing = true
                val parts = ArrayList<String>()
                for (i in 0 until u.ndragcoords - 1) {
                    val drag = interpretUiDrag(s, u, clearing, i)
                    clearing = drag.clearing
                    if (drag.oldState != drag.newState) {
                        parts += "F${drag.dir},${drag.sx},${drag.sy}"
                        parts += "F${f(drag.dir)},${drag.dx},${drag.dy}"
                    }
                }
                u.ndragcoords = -1
                return if (parts.isEmpty()) MoveResult.UiUpdate else MoveResult.Move(parts.joinToString(";"))
            } else if (u.ndragcoords == 0) {
                u.ndragcoords = -1
                px = u.clickx
                py = u.clicky
                gx = fromCoord(px, tile, border)
                gy = fromCoord(py, tile, border)
                val cx = centeredCoord(gx, tile, border)
                val cy = centeredCoord(gy, tile, border)
                if (!inGrid(s, gx, gy)) return MoveResult.UiUpdate
                if (max(abs(px - cx), abs(py - cy)) < tile / 4) {
                    return MoveResult.UiUpdate
                }
                val direction = if (abs(px - cx) < abs(py - cy)) {
                    if (py < cy) U else D
                } else {
                    if (px < cx) L else R
                }
                return markInDirection(s, gx, gy, direction, b == Buttons.LEFT_RELEASE)
            }
        }

        if (b == 'H'.code || b == 'h'.code) return MoveResult.Move("H")
        return MoveResult.Unused
    }

    // ------------------------------------------------------------------
    // Drawing helpers, `pearl.c:2517-2693`.
    // ------------------------------------------------------------------

    /** `draw_lines_specific`, `pearl.c:2517-2547`. */
    private fun drawLinesSpecific(
        sink: DrawSink,
        ds: DrawState,
        ui: Ui,
        x: Int,
        y: Int,
        flags: Int,
        shift: Int,
        colour: Int,
    ) {
        val tile = tileSizeFor(ds)
        val border = borderFor(ui, tile)
        val ox = coord(x, tile, border)
        val oy = coord(y, tile, border)
        val t2 = ds.halfsz
        val t16 = ds.halfsz / 4
        val cx = ox + t2
        val cy = oy + t2

        for (d in intArrayOf(1, 2, 4, 8)) {
            val xoff = t2 * dx(d)
            val yoff = t2 * dy(d)
            val xnudge = abs(t16 * dx(c(d)))
            val ynudge = abs(t16 * dy(c(d)))

            if (((flags shr shift) and d) != 0) {
                val lx = cx + (if (xoff < 0) xoff else 0) - xnudge
                val ly = cy + (if (yoff < 0) yoff else 0) - ynudge

                if (colour == COL_DRAGOFF && (flags and d) == 0) continue
                if (colour == COL_DRAGON && (flags and d) != 0) continue

                sink.rect(lx, ly, abs(xoff) + 2 * xnudge + 1, abs(yoff) + 2 * ynudge + 1, colour)
                sink.rect(cx - t16, cy - t16, 2 * t16 + 1, 2 * t16 + 1, colour)
            }
        }
    }

    /** `draw_square`, `pearl.c:2549-2624`. */
    private fun drawSquare(
        sink: DrawSink,
        ds: DrawState,
        ui: Ui,
        x: Int,
        y: Int,
        flags: Int,
        clue: Int,
        tile: Int,
        border: Int,
    ) {
        val ox = coord(x, tile, border)
        val oy = coord(y, tile, border)
        val t2 = ds.halfsz
        val t16 = ds.halfsz / 4
        val cx = ox + t2
        val cy = oy + t2

        sink.clip(ox, oy, tile, tile)

        sink.rect(
            ox,
            oy,
            tile,
            tile,
            if ((flags and DS_CURSOR) != 0) COL_CURSOR_BACKGROUND else COL_BACKGROUND,
        )

        if (ui.appearance == Appearance.LOOPY) {
            sink.circle(cx, cy, t16, COL_GRID, COL_GRID)
        } else {
            sink.line(ox, oy, coord(x + 1, tile, border), oy, COL_GRID)
            sink.line(ox, oy, ox, coord(y + 1, tile, border), COL_GRID)
        }

        for (d in intArrayOf(1, 2, 4, 8)) {
            val xoff = t2 * dx(d)
            val yoff = t2 * dy(d)

            if ((x == 0 && d == L) ||
                (y == 0 && d == U) ||
                (x == ds.w - 1 && d == R) ||
                (y == ds.h - 1 && d == D)
            ) {
                continue
            }

            if (((flags shr DS_MSHIFT) and d) != 0) {
                val mx = cx + xoff
                val my = cy + yoff
                val msz = t16
                sink.line(mx - msz, my - msz, mx + msz, my + msz, COL_BLACK)
                sink.line(mx - msz, my + msz, mx + msz, my - msz, COL_BLACK)
            } else if (ui.appearance == Appearance.LOOPY) {
                sink.line(cx, cy, cx + xoff, cy + yoff, COL_GRID)
            }
        }

        drawLinesSpecific(
            sink, ds, ui, x, y, flags, 0,
            if ((flags and DS_FLASH) != 0) COL_FLASH else COL_BLACK,
        )
        drawLinesSpecific(sink, ds, ui, x, y, flags, DS_ESHIFT, COL_ERROR)
        drawLinesSpecific(sink, ds, ui, x, y, flags, DS_DSHIFT, COL_DRAGOFF)
        drawLinesSpecific(sink, ds, ui, x, y, flags, DS_DSHIFT, COL_DRAGON)

        if (clue != NOCLUE) {
            val colour = if ((flags and DS_FLASH) != 0) {
                COL_FLASH
            } else if (clue == STRAIGHT) {
                COL_WHITE
            } else {
                COL_BLACK
            }
            if ((flags and DS_ERROR_CLUE) != 0) {
                sink.circle(cx, cy, tile * 3 / 8, COL_ERROR, COL_ERROR)
            }
            sink.circle(cx, cy, tile / 4, colour, COL_BLACK)
        }

        sink.unclip()
        sink.update(ox, oy, tile, tile)
    }

    // ------------------------------------------------------------------
    // Bit and geometry helpers, `pearl.c:60-84`.
    // ------------------------------------------------------------------

    /** `DX`. */
    private fun dx(d: Int): Int = (if (d == R) 1 else 0) - (if (d == L) 1 else 0)

    /** `DY`. */
    private fun dy(d: Int): Int = (if (d == D) 1 else 0) - (if (d == U) 1 else 0)

    /** `F`: the reverse direction. */
    private fun f(d: Int): Int = ((d shl 2) or (d shr 2)) and 0xF

    /** `C`: the clockwise rotation. */
    private fun c(d: Int): Int = ((d shl 3) or (d shr 1)) and 0xF

    /** `A`: the anticlockwise rotation. */
    private fun a(d: Int): Int = ((d shl 1) or (d shr 3)) and 0xF

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
}
