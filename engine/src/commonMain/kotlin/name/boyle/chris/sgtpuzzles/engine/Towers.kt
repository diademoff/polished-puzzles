package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `towers.c`: the puzzle also known as Skyscrapers.
 *
 * The grid holds a Latin square. The four bands around the grid carry
 * clues, and each clue counts the towers visible from that side.
 *
 * The generator, the solver, the moves, the text form, and the drawing
 * are upstream behavior. The generator is solver-gated, so the port must
 * keep the strategy order, the clue order, the shared `started` flag,
 * and the flat cube indices exactly, or every description changes.
 *
 * The clues and the immutable given cells are shared by every state of
 * one game, as the C `struct clues` is shared through its reference
 * count. The highlight and the two preferences are frontend state:
 * Towers has no `encode_ui` or `decode_ui`, so none of them is saved.
 */
object Towers : Game {

    // Difficulty, `towers.c:39-51`.
    private const val DIFF_EASY = 0
    private const val DIFF_HARD = 1
    private const val DIFF_EXTREME = 2
    private const val DIFF_UNREASONABLE = 3
    private const val DIFFCOUNT = 4
    private val DIFF_NAMES = arrayOf("Easy", "Hard", "Extreme", "Unreasonable")
    private const val DIFF_CHARS = "ehxu"

    // Palette, `towers.c:54-63`.
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_USER = 2
    private const val COL_HIGHLIGHT = 3
    private const val COL_ERROR = 4
    private const val COL_PENCIL = 5
    private const val COL_DONE = 6
    private const val NCOLOURS = 7

    private const val PREFERRED_TILE_SIZE = 48

    // Tile word, `towers.c:1317-1324`. `DF_CLUE_DONE` and
    // `DF_PENCIL_SHIFT` share bit 16; digit 0 is never marked, so the
    // overlap is harmless, as in C. Copy the constants, do not
    // re-derive the mask.
    private const val DF_PENCIL_SHIFT = 16
    private const val DF_CLUE_DONE = 0x10000L
    private const val DF_ERROR = 0x8000L
    private const val DF_HIGHLIGHT = 0x4000L
    private const val DF_HIGHLIGHT_PENCIL = 0x2000L
    private const val DF_IMMUTABLE = 0x1000L
    private const val DF_PLAYAREA = 0x0800L
    private const val DF_DIGIT_MASK = 0x00FFL

    /**
     * The C error strings of `solve_game`. The seam has no error
     * out-parameter, so `solve` returns null for both and the adapter
     * uses these messages.
     */
    const val NO_SOLUTION_MESSAGE = "No solution exists for this puzzle"
    const val MULTIPLE_SOLUTIONS_MESSAGE = "Multiple solutions exist for this puzzle"

    /** `struct game_params`, `towers.c:71-73`. The board is square. */
    data class Params(val w: Int, val diff: Int) : Game.Params {
        override fun copy(): Game.Params = Params(w, diff)
    }

    /**
     * `struct clues`: the `4w` clue values and the description's given
     * cells. The object is immutable after construction and is shared by
     * every state of the game, as the C reference count does.
     */
    class Shared internal constructor(
        val w: Int,
        val clues: IntArray,
        val immutable: IntArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Shared &&
                w == other.w &&
                clues.contentEquals(other.clues) &&
                immutable.contentEquals(other.immutable)

        override fun hashCode(): Int {
            var result = w
            result = 31 * result + clues.contentHashCode()
            result = 31 * result + immutable.contentHashCode()
            return result
        }
    }

    /** `struct game_state`, `towers.c:125-132`. */
    class State internal constructor(
        val params: Params,
        val shared: Shared,
        val grid: IntArray,
        val pencil: IntArray,
        val cluesDone: BooleanArray,
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
            cluesDone.copyOf(),
            completed,
            cheated,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                shared == other.shared &&
                grid.contentEquals(other.grid) &&
                pencil.contentEquals(other.pencil) &&
                cluesDone.contentEquals(other.cluesDone) &&
                completed == other.completed &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + shared.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + pencil.contentHashCode()
            result = 31 * result + cluesDone.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`, `towers.c:1151-1204`. */
    class Ui internal constructor() : Game.Ui {
        internal var hx: Int = 0
        internal var hy: Int = 0
        internal var hpencil: Boolean = false
        internal var hshow: Boolean = false
        internal var hcursor: Boolean = false

        /**
         * The `appearance` preference: true is the C default 3D. Drawing
         * and the click hit test read it. It is not saved and not in the
         * drawstate.
         */
        var threeD: Boolean = true

        /**
         * The `pencil-keep-highlight` preference: true is the C default,
         * read only by `interpretMove`.
         */
        var pencilKeepHighlight: Boolean = true
    }

    /** `struct game_drawstate`, `towers.c:1326-1331`. */
    class DrawState internal constructor(
        val tileSize: Int,
        val w: Int,
        internal val tiles: LongArray,
        internal val drawn: LongArray,
        internal val errtmp: BooleanArray,
    ) : Game.DrawState

    /** Internal retry diagnostics. They change no description. */
    internal class GenerationStats {
        var attempts = 0

        /** The Easy-only empty-grid pre-check rejected the square. */
        var emptyGridRejects = 0

        /** The final gate did not report exactly the requested level. */
        var exactGateRejects = 0
    }

    internal data class Generated(
        val description: Description,
        val stats: GenerationStats,
    )

    private val PRESETS = listOf(
        Params(4, DIFF_EASY),
        Params(5, DIFF_EASY),
        Params(5, DIFF_HARD),
        Params(6, DIFF_EASY),
        Params(6, DIFF_HARD),
        Params(6, DIFF_EXTREME),
        Params(6, DIFF_UNREASONABLE),
    )

    override val name: String = "Towers"
    override val backendName: String = "towers"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val flags: Int = Game.REQUIRE_RBUTTON or Game.REQUIRE_NUMPAD

    // ------------------------------------------------------------------
    // Parameters, `towers.c:134-256`.
    // ------------------------------------------------------------------

    override fun defaultParams(): Game.Params = Params(5, DIFF_EASY)

    override fun presets(): List<Preset> = PRESETS.map { p ->
        Preset("${p.w}x${p.w} ${DIFF_NAMES[p.diff]}", encodeParams(p, true))
    }

    /**
     * `decode_params`, `towers.c:184-203`.
     *
     * The digit skip starts at the first character of the text, not at
     * the end of the number, so a leading sign or space stops the skip
     * and no `d` suffix is recognised after it. The base difficulty is
     * the default, as the midend decodes over its defaults.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = cAtoi(text, 0)
        var index = 0
        while (index < text.length && text[index].isAsciiDigit()) index++

        var diff = DIFF_EASY
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
        return Params(w, diff)
    }

    /**
     * `encode_params`, `towers.c:205-214`: the short form is the width,
     * the full form adds `d` and the difficulty character.
     *
     * C indexes `towers_diffchars[diff]` with no bound. A difficulty
     * outside `0..3` reads outside the array, which is undefined
     * behavior. The port writes a plain `d` with no character for every
     * such value, the same defined result as the other ports.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        if (!full) return p.w.toString()
        val out = StringBuilder()
        out.append(p.w).append('d')
        if (p.diff in 0 until DIFFCOUNT) out.append(DIFF_CHARS[p.diff])
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
    // Clue geometry, `towers.c:96-117`.
    // ------------------------------------------------------------------

    /**
     * `STARTSTEP`: the first cell of a clue's line and the step along it.
     * The bands are top, bottom, left, then right, in that order.
     */
    private fun clueStart(index: Int, w: Int): Int = when {
        index < w -> index
        index < 2 * w -> (w - 1) * w + (index - w)
        index < 3 * w -> w * (index - 2 * w)
        else -> w * (index - 3 * w) + (w - 1)
    }

    private fun clueStep(index: Int, w: Int): Int = when {
        index < w -> w
        index < 2 * w -> -w
        index < 3 * w -> 1
        else -> -1
    }

    /** `CSTARTSTEP` is `STARTSTEP` on the clue two bands later. */
    private fun clueCStart(index: Int, w: Int): Int =
        clueStart((index + 2 * w) % (4 * w), w)

    private fun clueCStep(index: Int, w: Int): Int =
        clueStep((index + 2 * w) % (4 * w), w)

    /** `CLUEPOS`: the clue's drawn coordinates, outside the grid. */
    private fun clueX(index: Int, w: Int): Int = when {
        index < w -> index
        index < 2 * w -> index - w
        index < 3 * w -> -1
        else -> w
    }

    private fun clueY(index: Int, w: Int): Int = when {
        index < w -> -1
        index < 2 * w -> w
        index < 3 * w -> index - 2 * w
        else -> index - 3 * w
    }

    // ------------------------------------------------------------------
    // Description validation, `towers.c:845-909`.
    // ------------------------------------------------------------------

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val w = (params as Params).w
        val a = w * w
        var p = 0

        for (i in 0 until 4 * w) {
            if (p >= desc.length) return "Too few clues for grid size"
            if (i > 0) {
                if (desc[p] != '/') return "Expected commas between clues"
                p++
            }
            if (p < desc.length && desc[p].isAsciiDigit()) {
                val clue = cAtoi(desc, p)
                while (p < desc.length && desc[p].isAsciiDigit()) p++
                if (clue <= 0 || clue > w) return "Clue number out of range"
            }
        }

        if (p < desc.length && desc[p] == '/') return "Too many clues for grid size"

        if (p < desc.length && desc[p] == ',') {
            var squares = 0
            p++
            while (p < desc.length) {
                val c = desc[p++]
                when {
                    c >= 'a' && c <= 'z' -> squares += c - 'a' + 1
                    c == '_' -> Unit
                    c > '0' && c <= '9' -> {
                        val value = cAtoi(desc, p - 1)
                        if (value < 1 || value > w) {
                            return "Out-of-range number in grid description"
                        }
                        squares++
                        while (p < desc.length && desc[p].isAsciiDigit()) p++
                    }

                    else -> return "Invalid character in game description"
                }
            }

            if (squares < a) return "Not enough data to fill grid"
            if (squares > a) return "Too much data to fit in grid"
        }

        if (p < desc.length) return "Rubbish at end of game description"
        return null
    }

    /** `game_configure` (`towers.c:216-237`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Grid size", p.w.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Hard:Extreme:Unreasonable",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`towers.c:239-247`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            cAtoi((items[0] as ConfigItem.Text).value, 0),
            (items[1] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`towers.c:1245-1268`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Keep cursor after changing numbers",
                true,
                "pencil-keep-highlight",
            ),
            ConfigItem.Choices(
                "Puzzle appearance",
                ":2D:3D",
                ":2d:3d",
                1,
                "appearance",
            ),
        )

    /** `set_prefs` (`towers.c:1270-1274`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.pencilKeepHighlight = prefs.bool("pencil-keep-highlight")
        u.threeD = prefs.choice("appearance") == 1
    }

    // ------------------------------------------------------------------
    // Generation, `towers.c:647-839`.
    // ------------------------------------------------------------------

    override fun newDesc(params: Game.Params, random: RandomState): Description =
        generateWithStats(params as Params, random).description

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description = generateWithStats(params as Params, random, cancel).description

    // `towers.c` retries the solver-gated generator; PARITY records 9du (`9x9` Unreasonable) at seconds while the largest preset is 6x6.
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w >= 9) Game.SLOW_GENERATION_WARNING else null
    }

    internal fun generateWithStats(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        val w = params.w
        val a = w * w
        var diff = params.diff

        // `towers.c:679-680`: Extreme and Unreasonable on a 3x3 board are
        // silently downgraded to Hard. CPARAMS keeps the request.
        if (diff > DIFF_HARD && w <= 3) diff = DIFF_HARD

        val stats = GenerationStats()
        val clues = IntArray(4 * w)
        val soln = IntArray(a)
        val soln2 = IntArray(a)
        val order = IntArray(max(4 * w, a))

        var grid: IntArray
        while (true) {
            cancel.check()
            stats.attempts++

            // One Latin generation: the row shuffle and the matching
            // runs. Every draw is part of the RNG contract.
            grid = generate(w, random)

            // The 4w clues are the strictly increasing prefix counts.
            for (i in 0 until 4 * w) {
                val start = clueStart(i, w)
                val step = clueStep(i, w)
                var best = 0
                var k = 0
                for (j in 0 until w) {
                    val value = grid[start + j * step]
                    if (value > best) {
                        best = value
                        k++
                    }
                }
                clues[i] = k
            }

            // `soln` is the full solution and is never modified again.
            grid.copyInto(soln)

            // `towers.c:718-728`: the Easy-only empty-grid pre-check. A
            // reject continues the attempt loop before either shuffle,
            // so it consumes only the Latin draws of this attempt.
            if (diff == DIFF_EASY && w <= 5) {
                soln2.fill(0)
                val ret = solver(w, clues, soln2, diff)
                if (ret > diff) {
                    stats.emptyGridRejects++
                    continue
                }
            }

            // `towers.c:730-741`: the first order shuffle and the grid
            // removal. A cell stays removed when the board is still
            // solvable at the requested difficulty.
            for (i in 0 until a) order[i] = i
            shuffle(order, a, random)
            for (i in 0 until a) {
                val j = order[i]
                grid.copyInto(soln2)
                soln2[j] = 0
                val ret = solver(w, clues, soln2, diff)
                if (ret <= diff) grid[j] = 0
            }

            // `towers.c:743-757`: the clue removal, only above Easy. It
            // has its own `4w - 1` draws.
            if (diff > DIFF_EASY) {
                for (i in 0 until 4 * w) order[i] = i
                shuffle(order, 4 * w, random)
                for (i in 0 until 4 * w) {
                    val j = order[i]
                    val clue = clues[j]
                    grid.copyInto(soln2)
                    clues[j] = 0
                    val ret = solver(w, clues, soln2, diff)
                    if (ret > diff) clues[j] = clue
                }
            }

            // `towers.c:759-766`: the accepted attempt must measure
            // exactly the requested difficulty.
            grid.copyInto(soln2)
            val ret = solver(w, clues, soln2, diff)
            if (ret != diff) {
                stats.exactGateRejects++
                continue
            }

            break
        }

        val desc = encodeDesc(w, a, clues, grid)
        val aux = StringBuilder(a + 1)
        aux.append('S')
        for (i in 0 until a) aux.append(('0'.code + soln[i]).toChar())
        return Generated(Description(desc, aux.toString()), stats)
    }

    /** The description encoding of `towers.c:774-821`. */
    private fun encodeDesc(w: Int, a: Int, clues: IntArray, grid: IntArray): String {
        val out = StringBuilder()
        for (i in 0 until 4 * w) {
            if (i > 0) out.append('/')
            if (clues[i] != 0) out.append(clues[i])
        }

        var any = false
        for (i in 0 until a) {
            if (grid[i] != 0) {
                any = true
                break
            }
        }
        if (!any) return out.toString()

        out.append(',')
        var run = 0
        for (i in 0..a) {
            val n = if (i < a) grid[i] else -1
            if (n == 0) {
                run++
            } else {
                if (run != 0) {
                    while (run > 0) {
                        val thisRun = min(run, 26)
                        out.append(('a'.code + thisRun - 1).toChar())
                        run -= thisRun
                    }
                } else if (i > 0 && n > 0) {
                    // Between two numbers, or before one that is not
                    // first, a `_` keeps them from merging.
                    out.append('_')
                }
                if (n > 0) out.append(n)
                run = 0
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Solver, `towers.c:262-641`.
    // ------------------------------------------------------------------

    /**
     * `struct solver_ctx`, `towers.c:262-268`: the clue list, the two
     * scratch arrays, and the one-off flag.
     *
     * `ctxnew`/`ctxfree` are NULL in C, so one context is shared by every
     * recursive attempt: `started` persists for the whole solve.
     * `ctx.diff` is dead code in C, so it is not ported.
     */
    private class SolverContext(
        val w: Int,
        val clues: IntArray,
        val iscratch: LongArray = LongArray(w),
        val dscratch: IntArray = IntArray(w + 1),
    ) {
        var started: Boolean = false

        val starts = IntArray(4 * w) { clueStart(it, w) }
        val steps = IntArray(4 * w) { clueStep(it, w) }
        val cstarts = IntArray(4 * w) { clueCStart(it, w) }
        val csteps = IntArray(4 * w) { clueCStep(it, w) }
    }

    /**
     * The five `latin_solver` strategy slots, `towers.c:632-635`, in
     * argument order: simple, set-0, set-1, forcing, recursive.
     */
    private const val SLOT_SIMPLE = DIFF_EASY
    private const val SLOT_SET_0 = DIFF_HARD
    private const val SLOT_SET_1 = DIFF_EXTREME
    private const val SLOT_FORCING = DIFF_EXTREME
    private const val SLOT_RECURSIVE = DIFF_UNREASONABLE

    /**
     * The five strategy slots, so a test can pin the literal values.
     * They are `latin_solver` arguments, not game state.
     */
    internal fun solverSlots(): IntArray =
        intArrayOf(SLOT_SIMPLE, SLOT_SET_0, SLOT_SET_1, SLOT_FORCING, SLOT_RECURSIVE)

    /**
     * `solver`, `towers.c:620-641`: one fresh context per call and the
     * same five slots that also gate generation.
     */
    private fun solver(w: Int, clues: IntArray, soln: IntArray, maxDiff: Int): Int {
        val ctx = SolverContext(w, clues)
        return latinSolve(
            soln,
            w,
            maxDiff,
            SLOT_SIMPLE,
            SLOT_SET_0,
            SLOT_SET_1,
            SLOT_FORCING,
            SLOT_RECURSIVE,
            TOWERS_SOLVERS,
            TOWERS_VALIDATOR,
            ctx,
            null,
            null,
        )
    }

    private val TOWERS_SOLVERS: List<LatinUserSolver?> = listOf(
        { solver, ctx -> solverEasy(solver, ctx as SolverContext) },
        { solver, ctx -> solverHard(solver, ctx as SolverContext) },
        null,
        null,
    )

    private val TOWERS_VALIDATOR: LatinValidator = { solver, ctx ->
        towersValid(solver, ctx as SolverContext)
    }

    /**
     * `solver_easy`, `towers.c:270-450`.
     *
     * The one-off facing-clue deduction runs once per solve, guarded by
     * the shared `started` flag. The flat cube indices are copied
     * literally: `cube[cell*w + n - 1]` decodes as the transposed
     * `(cell/w, cell%w)`, not as the cell's own coordinates, while the
     * placement uses `(pos%w, pos/w)`. Do not tidy this.
     */
    private fun solverEasy(solver: LatinSolver, ctx: SolverContext): Int {
        val w = ctx.w
        var ret = 0

        if (!ctx.started) {
            ctx.started = true
            var c = 0
            while (c < 3 * w) {
                val c2 = c + w
                if (ctx.clues[c] != 0 && ctx.clues[c2] != 0 &&
                    ctx.clues[c] + ctx.clues[c2] == w + 1
                ) {
                    val start = ctx.starts[c]
                    val step = ctx.steps[c]
                    val cstart = ctx.cstarts[c]
                    val cstep = ctx.csteps[c]
                    val pos = start + (ctx.clues[c] - 1) * step
                    val cpos = cstart + (ctx.clues[c] - 1) * cstep
                    if (solver.cube[cpos * w + w - 1]) {
                        solver.place(pos % w, pos / w, w)
                        ret = 1
                    } else {
                        ret = -1
                    }
                }
                c = if (c == w - 1) 2 * w else c + 1
            }
            if (ret != 0) return ret
        }

        var c = 0
        while (c < 4 * w) {
            val clue = ctx.clues[c]
            if (clue != 0) {
                val start = ctx.starts[c]
                val step = ctx.steps[c]
                val cstart = ctx.cstarts[c]
                val cstep = ctx.csteps[c]

                // The position of each digit already on the line.
                for (i in 0 until w) ctx.dscratch[i] = w
                for (i in 0 until w) {
                    val value = solver.grid[start + i * step]
                    if (value != 0) ctx.dscratch[value - 1] = i
                }

                // The increasing run of the highest digits.
                var n = 0
                var furthest = w
                var i = w
                while (i >= 1) {
                    if (ctx.dscratch[i - 1] == w) {
                        break
                    } else if (ctx.dscratch[i - 1] < furthest) {
                        furthest = ctx.dscratch[i - 1]
                        n++
                    }
                    i--
                }
                if (clue == n + 1 && furthest > 1) {
                    var j = furthest - 1
                    i = 1
                    while (i <= w && j > 0) {
                        if (ctx.dscratch[i - 1] < w &&
                            ctx.dscratch[i - 1] >= furthest
                        ) {
                            i++
                            continue
                        }
                        j--
                        val index = cstart * w + i - 1
                        if (solver.cube[index]) {
                            solver.cube[index] = false
                            ret = 1
                        }
                        i++
                    }
                }

                if (ret != 0) return ret

                // Lower bounds for the largest digits, from the clue side.
                i = 0
                var num = w
                while (num > 0) {
                    var skip = false
                    if (ctx.dscratch[num - 1] < w) {
                        var m = num + 1
                        while (m < w) {
                            if (ctx.dscratch[m] < ctx.dscratch[num - 1]) break
                            m++
                        }
                        if (m < w) skip = true
                    }
                    if (!skip) {
                        var j = 0
                        while (j < clue - i - 1) {
                            val index = (cstart + j * cstep) * w + num - 1
                            if (solver.cube[index]) {
                                solver.cube[index] = false
                                ret = 1
                            }
                            j++
                        }
                        i++
                    }
                    num--
                }
            }
            if (ret != 0) return ret
            c++
        }

        return 0
    }

    /**
     * `solver_hard`, `towers.c:452-583`: enumerate every sequence of `w`
     * distinct allowed digits along the clue's line, keep the ones whose
     * visible count equals the clue, and rule out every digit that never
     * appears in one of them. The early return after the first ruled-out
     * cell lets the next pass restart at the easier strategies.
     */
    private fun solverHard(solver: LatinSolver, ctx: SolverContext): Int {
        val w = ctx.w

        for (c in 0 until 4 * w) {
            val clue = ctx.clues[c]
            if (clue == 0) continue
            val start = ctx.cstarts[c]
            val step = ctx.csteps[c]

            for (i in 0 until w) ctx.iscratch[i] = 0

            var i = 0
            ctx.dscratch[0] = 0
            var best = 0
            var n = 0
            var bitmap = 0L

            while (true) {
                if (i < w) {
                    val limit = if (n == clue) best else w
                    val pos = start + step * i
                    var j = ctx.dscratch[i] + 1
                    while (j <= limit) {
                        if (bitmap and (1L shl j) != 0L) {
                            j++
                            continue
                        }
                        if (!solver.cube[pos * w + j - 1]) {
                            j++
                            continue
                        }
                        break
                    }

                    if (j > limit) {
                        i--
                        if (i < 0) break
                        bitmap = bitmap and (1L shl ctx.dscratch[i]).inv()
                        if (ctx.dscratch[i] == best) {
                            n--
                            best = 0
                            for (k in 0 until i) {
                                if (best < ctx.dscratch[k]) best = ctx.dscratch[k]
                            }
                        }
                    } else {
                        bitmap = bitmap or (1L shl j)
                        ctx.dscratch[i] = j
                        i++
                        if (j > best) {
                            best = j
                            n++
                        }
                        ctx.dscratch[i] = 0
                    }
                } else {
                    if (n == clue) {
                        for (k in 0 until w) {
                            ctx.iscratch[k] = ctx.iscratch[k] or (1L shl ctx.dscratch[k])
                        }
                    }
                    i--
                    bitmap = bitmap and (1L shl ctx.dscratch[i]).inv()
                    if (ctx.dscratch[i] == best) {
                        n--
                        best = 0
                        for (k in 0 until i) {
                            if (best < ctx.dscratch[k]) best = ctx.dscratch[k]
                        }
                    }
                }
            }

            for (cell in 0 until w) {
                val pos = start + step * cell
                var ruled = false
                for (j in 1..w) {
                    if (solver.cube[pos * w + j - 1] &&
                        (ctx.iscratch[cell] and (1L shl j)) == 0L
                    ) {
                        // C removes every impossible digit of this cell
                        // before its early return. Stopping at the first
                        // one would change the next pass and every
                        // description that reaches this path.
                        solver.cube[pos * w + j - 1] = false
                        ruled = true
                    }
                }
                if (ruled) return 1
            }
        }

        return 0
    }

    /** `towers_valid`, `towers.c:588-618`. */
    private fun towersValid(solver: LatinSolver, ctx: SolverContext): Boolean {
        val w = ctx.w
        for (c in 0 until 4 * w) {
            val clue = ctx.clues[c]
            if (clue == 0) continue
            val start = ctx.starts[c]
            val step = ctx.steps[c]
            var best = 0
            var n = 0
            for (i in 0 until w) {
                val value = solver.grid[start + i * step]
                if (value > best) {
                    best = value
                    n++
                }
            }
            if (n != clue) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // State, `towers.c:937-1038`.
    // ------------------------------------------------------------------

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val a = w * w

        val clues = IntArray(4 * w)
        val immutable = IntArray(a)
        val grid = IntArray(a)

        var index = 0
        for (i in 0 until 4 * w) {
            if (i > 0) {
                require(index < desc.length && desc[index] == '/') {
                    "towers description has no separator at clue $i"
                }
                index++
            }
            if (index < desc.length && desc[index].isAsciiDigit()) {
                clues[i] = cAtoi(desc, index)
                while (index < desc.length && desc[index].isAsciiDigit()) index++
            } else {
                clues[i] = 0
            }
        }

        if (index < desc.length && desc[index] == ',') {
            var pos = 0
            index++
            while (index < desc.length) {
                val c = desc[index++]
                when {
                    c >= 'a' && c <= 'z' -> pos += c - 'a' + 1
                    c == '_' -> Unit
                    c > '0' && c <= '9' -> {
                        val value = cAtoi(desc, index - 1)
                        require(value in 1..w) {
                            "towers given cell $value is outside 1..$w"
                        }
                        require(pos < a) { "towers given cells overflow the grid" }
                        grid[pos] = value
                        immutable[pos] = value
                        pos++
                        while (index < desc.length && desc[index].isAsciiDigit()) index++
                    }

                    else -> error("corrupt towers description: '$c'")
                }
            }
            require(pos == a) { "towers given cells do not fill the grid" }
        }
        require(index == desc.length) { "towers description has trailing junk" }

        return State(
            params = p,
            shared = Shared(w, clues, immutable),
            grid = grid,
            pencil = IntArray(a),
            cluesDone = BooleanArray(4 * w),
            completed = false,
            cheated = false,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // Solver entry points, `towers.c:1040-1149`.
    // ------------------------------------------------------------------

    /**
     * `solve_game`. The stored answer short-circuits everything. Both
     * solver errors return null, because the seam has no error
     * out-parameter.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveOutcome(state, auxInfo).move

    /** `solve_game` with the two C error strings (`towers.c:1040-1072`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        solveOutcome(state, auxInfo)

    private fun solveOutcome(state: Game.State, auxInfo: String?): SolveOutcome {
        val s = state as State
        if (auxInfo != null) return SolveOutcome(auxInfo, null)

        val soln = s.shared.immutable.copyOf()
        val ret = solver(s.w, s.shared.clues, soln, DIFFCOUNT - 1)
        if (ret == LatinSolver.DIFF_IMPOSSIBLE) {
            return SolveOutcome(null, "No solution exists for this puzzle")
        }
        if (ret == LatinSolver.DIFF_AMBIGUOUS) {
            return SolveOutcome(null, "Multiple solutions exist for this puzzle")
        }

        val out = StringBuilder(s.a + 1)
        out.append('S')
        for (i in 0 until s.a) out.append(('0'.code + soln[i]).toChar())
        return SolveOutcome(out.toString(), null)
    }

    /**
     * The difficulty of a state, as the standalone solver measures it:
     * the first level whose result is at most that level. Returns
     * [DIFFCOUNT] when no level solves the board. Diagnostics only.
     */
    fun difficulty(state: Game.State): Int {
        val s = state as State
        val soln = IntArray(s.a)
        for (diff in 0 until DIFFCOUNT) {
            // The standalone solver starts from the description's given
            // cells, not from an empty grid.
            s.shared.immutable.copyInto(soln)
            val ret = solver(s.w, s.shared.clues, soln, diff)
            if (ret <= diff) return ret
        }
        return DIFFCOUNT
    }

    /** The raw `latin_solver` result at [maxDiff]. Diagnostics only. */
    internal fun solveResult(state: Game.State, maxDiff: Int): Int {
        val s = state as State
        return solver(s.w, s.shared.clues, IntArray(s.a), maxDiff)
    }

    /**
     * `game_text_format`, `towers.c:1079-1149`. The C buffer is
     * `2w*w + 10w + 9` bytes including the terminating NUL, so the
     * Kotlin string is one character shorter.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.w
        val out = StringBuilder(2 * w * w + 10 * w + 8)

        // Top clue row.
        out.append(' ').append(' ')
        for (x in 0 until w) {
            out.append(' ')
            out.append(if (s.shared.clues[x] != 0) ('0'.code + s.shared.clues[x]).toChar() else ' ')
        }
        out.append('\n')

        out.append('\n')

        // Main grid.
        for (y in 0 until w) {
            out.append(
                if (s.shared.clues[y + 2 * w] != 0) {
                    ('0'.code + s.shared.clues[y + 2 * w]).toChar()
                } else {
                    ' '
                },
            )
            out.append(' ')
            for (x in 0 until w) {
                out.append(' ')
                out.append(
                    if (s.grid[y * w + x] != 0) {
                        ('0'.code + s.grid[y * w + x]).toChar()
                    } else {
                        ' '
                    },
                )
            }
            out.append(' ').append(' ')
            out.append(
                if (s.shared.clues[y + 3 * w] != 0) {
                    ('0'.code + s.shared.clues[y + 3 * w]).toChar()
                } else {
                    ' '
                },
            )
            out.append('\n')
        }

        out.append('\n')

        // Bottom clue row.
        out.append(' ').append(' ')
        for (x in 0 until w) {
            out.append(' ')
            out.append(
                if (s.shared.clues[x + w] != 0) {
                    ('0'.code + s.shared.clues[x + w]).toChar()
                } else {
                    ' '
                },
            )
        }
        out.append('\n')

        check(out.length == 2 * w * w + 10 * w + 8) {
            "towers text length ${out.length} does not match the C total"
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Error marking, `towers.c:1333-1445`.
    // ------------------------------------------------------------------

    /**
     * `check_errors`. The array is `(w+2)^2` and clears every entry
     * first. A clue is marked at `(y+1)*(w+2) + (x+1)`.
     *
     * An incomplete row or column makes the result true, and a clue
     * whose visible prefix already matches its value is marked, so the
     * error colour appears during normal play, as in C.
     */
    internal fun checkErrors(state: Game.State, errors: BooleanArray?): Boolean {
        val s = state as State
        val w = s.w
        val W = w + 2
        val clues = s.shared.clues
        val grid = s.grid
        var errs = false

        if (errors != null) errors.fill(false)

        val fullRow = (1L shl (w + 1)) - (1L shl 1)

        for (y in 0 until w) {
            var mask = 0L
            var errmask = 0L
            for (x in 0 until w) {
                val bit = 1L shl grid[y * w + x]
                errmask = errmask or (mask and bit)
                mask = mask or bit
            }
            if (mask != fullRow) {
                errs = true
                errmask = errmask and 1L.inv()
                if (errors != null) {
                    for (x in 0 until w) {
                        if (errmask and (1L shl grid[y * w + x]) != 0L) {
                            errors[(y + 1) * W + (x + 1)] = true
                        }
                    }
                }
            }
        }

        for (x in 0 until w) {
            var mask = 0L
            var errmask = 0L
            for (y in 0 until w) {
                val bit = 1L shl grid[y * w + x]
                errmask = errmask or (mask and bit)
                mask = mask or bit
            }
            if (mask != fullRow) {
                errs = true
                errmask = errmask and 1L.inv()
                if (errors != null) {
                    for (y in 0 until w) {
                        if (errmask and (1L shl grid[y * w + x]) != 0L) {
                            errors[(y + 1) * W + (x + 1)] = true
                        }
                    }
                }
            }
        }

        for (i in 0 until 4 * w) {
            if (clues[i] == 0) continue
            val start = clueStart(i, w)
            val step = clueStep(i, w)
            var best = 0
            var n = 0
            for (j in 0 until w) {
                val number = grid[start + j * step]
                if (number == 0) break
                if (number > best) {
                    best = number
                    n++
                }
            }
            if (n > clues[i] || (best == w && n < clues[i]) ||
                (best < w && n == clues[i])
            ) {
                if (errors != null) {
                    errors[(clueY(i, w) + 1) * W + (clueX(i, w) + 1)] = true
                }
                errs = true
            }
        }

        return errs
    }

    /** `clue_index`, `towers.c:1421-1431`. */
    private fun clueIndex(w: Int, x: Int, y: Int): Int = when {
        x == -1 || x == w -> w * (if (x == -1) 2 else 3) + y
        y == -1 || y == w -> (if (y == -1) 0 else w) + x
        else -> -1
    }

    /** `is_clue`, `towers.c:1433-1445`: a drawn clue on one of the bands. */
    private fun isClue(state: State, x: Int, y: Int): Boolean {
        val w = state.w
        if (((x == -1 || x == w) && y >= 0 && y < w) ||
            ((y == -1 || y == w) && x >= 0 && x < w)
        ) {
            val index = clueIndex(w, x, y)
            if (state.shared.clues[index] and DF_DIGIT_MASK.toInt() != 0) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Moves, `towers.c:1447-1701`.
    // ------------------------------------------------------------------

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

        val shiftOrControl = button and (Buttons.MOD_SHFT or Buttons.MOD_CTRL) != 0
        val key = Buttons.stripModifiers(button)

        val border = tileSize * 9 / 8
        var tx = fromCoord(x, tileSize, border)
        var ty = fromCoord(y, tileSize, border)

        // The 3D hit test, `towers.c:1461-1494`. The order is (0,0),
        // (-1,0), (0,1), (-1,1), there is no break, and the last
        // matching candidate wins. The arithmetic is integer.
        if (u.threeD) {
            var dy = 0
            while (dy <= 1) {
                var dx = 0
                while (dx >= -1) {
                    val cx = tx + dx
                    val cy = ty + dy
                    if (cx >= 0 && cx < w && cy >= 0 && cy < w) {
                        val height = s.grid[cy * w + cx]
                        val bx = coord(cx, tileSize, border)
                        val by = coord(cy, tileSize, border)
                        val ox = bx + x3dDisp(height, w, tileSize)
                        val oy = by - y3dDisp(height, w, tileSize)
                        val onTop = x - ox >= 0 && x - ox < tileSize &&
                            y - oy >= 0 && y - oy < tileSize
                        val upperLeft = ox > bx && x >= bx && x <= ox && y <= by &&
                            (by - y) * (ox - bx) <= (by - oy) * (x - bx)
                        val lowerRight = ox > bx &&
                            x >= bx + tileSize && x <= ox + tileSize &&
                            y >= oy + tileSize &&
                            (by - y + tileSize) * (ox - bx) >=
                            (by - oy) * (x - bx - tileSize)
                        if (onTop || upperLeft || lowerRight) {
                            tx = cx
                            ty = cy
                        }
                    }
                    dx--
                }
                dy++
            }
        }

        if (tx >= 0 && tx < w && ty >= 0 && ty < w) {
            if (key == Buttons.LEFT_BUTTON) {
                // Android keeps `hpencil` when the highlight moves and
                // toggles it on a second click of the same cell.
                if (tx == u.hx && ty == u.hy && u.hshow) {
                    u.hpencil = !u.hpencil
                    u.hshow = true
                } else {
                    u.hx = tx
                    u.hy = ty
                    u.hshow = s.shared.immutable[ty * w + tx] == 0
                }
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
            if (isClue(s, tx, ty)) {
                return MoveResult.Move("D$tx,$ty")
            }
            u.hshow = false
            u.hpencil = false
            return MoveResult.UiUpdate
        }

        if (Buttons.isCursorMove(key)) {
            if (shiftOrControl) {
                var cx = u.hx
                var cy = u.hy
                when (key) {
                    Buttons.CURSOR_LEFT -> cx = -1
                    Buttons.CURSOR_RIGHT -> cx = w
                    Buttons.CURSOR_UP -> cy = -1
                    Buttons.CURSOR_DOWN -> cy = w
                }
                if (isClue(s, cx, cy)) return MoveResult.Move("D$cx,$cy")
                return MoveResult.Unused
            }
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
            // A pencil mark in a filled cell is unreachable by mouse.
            if (u.hpencil && s.grid[cell] != 0) return MoveResult.Unused
            // Nothing can change a given cell.
            if (s.shared.immutable[cell] != 0) return MoveResult.Unused

            if ((!u.hpencil || n == 0) && s.grid[cell] == n &&
                s.pencil[cell] == 0
            ) {
                // The Android preference applies to normal entry too.
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
     * `execute_move`, `towers.c:1642-1701`.
     *
     * Completion is sticky. `P` with `n == 0` takes the `R` branch. A
     * `P` on an immutable cell is rejected, which only a hand-written
     * save can reach.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.w
        val a = w * w

        if (move.isEmpty()) return null

        if (move[0] == 'S') {
            // C reads move[i+1] until a NUL proves a short string bad.
            // The port rejects a short string before it indexes.
            if (move.length != a + 1) return null
            for (i in 0 until a) {
                val c = move[i + 1]
                if (c < '1' || c > ('0'.code + w).toChar()) return null
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
            val fields = sscanfInts(move.substring(1), 3) ?: return null
            val x = fields[0]
            val y = fields[1]
            val n = fields[2]
            if (x < 0 || x >= w || y < 0 || y >= w || n < 0 || n > w) return null
            if (s.shared.immutable[y * w + x] != 0) return null

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

        if (move[0] == 'D') {
            val fields = sscanfInts(move.substring(1), 2) ?: return null
            val x = fields[0]
            val y = fields[1]
            if (!isClue(s, x, y)) return null
            val ret = s.copy() as State
            val index = clueIndex(w, x, y)
            ret.cluesDone[index] = !ret.cluesDone[index]
            return ret
        }

        return null
    }

    /**
     * `sscanf(text, "%d,%d,...")` for [count] fields.
     *
     * Leading whitespace is skipped for each field, a sign is accepted,
     * a literal comma must follow, and trailing junk is ignored. A
     * number that overflows an `int` saturates, which is the port's
     * defined result for the C undefined behavior.
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
     * `game_changed_state`, `towers.c:1276-1295`. The Android build also
     * clears `hpencil` when it cancels a pencil-mode mouse highlight.
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
    // Frontend state and keys, `towers.c:911-935, 1220-1303`.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `android_cursor_visibility`, `towers.c:1240-1243`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).hshow = visible
    }

    /** `game_get_cursor_location`, `towers.c:2071-2082`. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.hshow) return null
        val border = tileSize * 9 / 8
        return Game.CursorRect(
            left = coord(u.hx, tileSize, border),
            top = coord(u.hy, tileSize, border),
            width = tileSize,
            height = tileSize,
        )
    }

    /**
     * `game_request_keys`, `towers.c:911-935`. The C labels are NULL and
     * the midend replaces them with `button2label`, so a digit becomes
     * its character and backspace becomes `Clear`. `validate_params`
     * caps `w` at 9, so the `'a'` branch is unreachable for a validated
     * parameter set, but it is copied literally.
     */
    override fun requestKeys(params: Game.Params): KeyRequest {
        val w = (params as Params).w
        val labels = ArrayList<KeyLabel>(w + 2)
        for (i in 0 until w) {
            val button = if (i < 9) '1'.code + i else 'a'.code + i - 9
            // C stores a NULL label and the midend's `button2label` then
            // uses the button character: a digit stays a digit and a
            // letter stays a letter.
            labels += KeyLabel(button, button.toChar().toString())
        }
        labels += KeyLabel('\b'.code, "Clear")
        labels += KeyLabel('M'.code, "Mark")
        return KeyRequest(labels, ArrowMode.ARROWS_LEFT)
    }

    /**
     * `current_key_label`, `towers.c:1297-1303`. The seam has no
     * operation for this, so it is a public helper with a test.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if (u.hshow && button == Buttons.CURSOR_SELECT) {
            return if (u.hpencil) "Ink" else "Pencil"
        }
        return ""
    }

    // ------------------------------------------------------------------
    // Palette and drawing, `towers.c:1707-2054`.
    // ------------------------------------------------------------------

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) ret[COL_GRID * 3 + i] = 0.0f

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

        for (i in 0 until 3) {
            ret[COL_DONE * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 1.5f
        }
        return ret
    }

    /** `game_compute_size`: `SIZE(w) = w*T + 2*(T*9/8)`. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize * 9 / 8
        val side = p.w * tileSize + 2 * border
        return Size(side, side)
    }

    /** `COORD`: `x*T + T*9/8`. */
    private fun coord(x: Int, tileSize: Int, border: Int): Int = x * tileSize + border

    /** `FROMCOORD`: the C division truncates toward zero. */
    private fun fromCoord(value: Int, tileSize: Int, border: Int): Int =
        (value + (tileSize - border)) / tileSize - 1

    /** `X_3D_DISP(height, w) = height*T/(8*w)`; the positive offset. */
    private fun x3dDisp(height: Int, w: Int, tileSize: Int): Int =
        height * tileSize / (8 * w)

    /** `Y_3D_DISP(height, w) = height*T/(4*w)`; subtracted from y. */
    private fun y3dDisp(height: Int, w: Int, tileSize: Int): Int =
        height * tileSize / (4 * w)

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val cells = (s.w + 2) * (s.w + 2)
        return DrawState(
            tileSize = tileSize,
            w = s.w,
            tiles = LongArray(cells),
            drawn = LongArray(cells * 4) { -1L },
            errtmp = BooleanArray(cells),
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
        val W = w + 2
        val border = t * 9 / 8

        checkErrors(s, ds.errtmp)

        ds.tiles.fill(0L)

        for (i in 0 until 4 * w) {
            var tile = s.shared.clues[i].toLong()
            val x = clueX(i, w)
            val y = clueY(i, w)
            if (ds.errtmp[(y + 1) * W + (x + 1)]) {
                tile = tile or DF_ERROR
            } else if (s.cluesDone[i]) {
                tile = tile or DF_CLUE_DONE
            }
            ds.tiles[(y + 1) * W + (x + 1)] = tile
        }

        for (y in 0 until w) {
            for (x in 0 until w) {
                val cell = y * w + x
                var tile = DF_PLAYAREA
                tile = tile or if (s.grid[cell] != 0) {
                    s.grid[cell].toLong()
                } else {
                    s.pencil[cell].toLong() shl DF_PENCIL_SHIFT
                }
                if (u.hshow && u.hx == x && u.hy == y) {
                    tile = tile or if (u.hpencil) DF_HIGHLIGHT_PENCIL else DF_HIGHLIGHT
                }
                if (s.shared.immutable[cell] != 0) tile = tile or DF_IMMUTABLE
                if (ds.errtmp[(y + 1) * W + (x + 1)]) tile = tile or DF_ERROR
                ds.tiles[(y + 1) * W + (x + 1)] = tile
            }
        }

        // The four-tuple dirty cache: a change in any of the four tile
        // words around a cell repaints the cell's own T x T clip with up
        // to four overlapping tiles, in the order tr, tl, br, bl.
        for (y in 0 until w + 2) {
            for (x in 0 until w + 2) {
                val i = y * W + x
                val tr = ds.tiles[y * W + x]
                val tl = if (x == 0) 0L else ds.tiles[y * W + (x - 1)]
                val br = if (y == w + 1) 0L else ds.tiles[(y + 1) * W + x]
                val bl = if (x == 0 || y == w + 1) 0L else ds.tiles[(y + 1) * W + (x - 1)]

                if (ds.drawn[i * 4] != tl || ds.drawn[i * 4 + 1] != tr ||
                    ds.drawn[i * 4 + 2] != bl || ds.drawn[i * 4 + 3] != br
                ) {
                    sink.clip(
                        coord(x - 1, t, border),
                        coord(y - 1, t, border),
                        t,
                        t,
                    )
                    drawTile(sink, ds, u, s.shared, x - 1, y - 1, tr)
                    if (x > 0) drawTile(sink, ds, u, s.shared, x - 2, y - 1, tl)
                    if (y <= w) drawTile(sink, ds, u, s.shared, x - 1, y, br)
                    if (x > 0 && y <= w) {
                        drawTile(sink, ds, u, s.shared, x - 2, y, bl)
                    }
                    sink.unclip()
                    sink.update(
                        coord(x - 1, t, border),
                        coord(y - 1, t, border),
                        t,
                        t,
                    )

                    ds.drawn[i * 4] = tl
                    ds.drawn[i * 4 + 1] = tr
                    ds.drawn[i * 4 + 2] = bl
                    ds.drawn[i * 4 + 3] = br
                }
            }
        }
    }

    /**
     * `draw_tile`, `towers.c:1783-1961`.
     *
     * A tower's left and bottom faces are drawn first, then the erase
     * rectangle covers their lower and left edges, then the pencil
     * triangle and the box outline, and finally the digit or the pencil
     * marks. The 3D offset moves every later coordinate to the top face.
     */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        ui: Ui,
        shared: Shared,
        x: Int,
        y: Int,
        tile: Long,
    ) {
        val w = shared.w
        val t = ds.tileSize
        val border = t * 9 / 8

        var tx = coord(x, t, border)
        var ty = coord(y, t, border)
        val bg = if (tile and DF_HIGHLIGHT != 0L) COL_HIGHLIGHT else COL_BACKGROUND

        if (ui.threeD && tile and DF_PLAYAREA != 0L &&
            tile and DF_DIGIT_MASK != 0L
        ) {
            val height = (tile and DF_DIGIT_MASK).toInt()
            val xoff = x3dDisp(height, w, t)
            val yoff = y3dDisp(height, w, t)

            sink.poly(
                intArrayOf(
                    tx,
                    ty - 1,
                    tx,
                    ty + t - 1,
                    tx + xoff,
                    ty + t - 1 - yoff,
                    tx + xoff,
                    ty - 1 - yoff,
                ),
                bg,
                COL_GRID,
            )
            sink.poly(
                intArrayOf(
                    tx + t,
                    ty + t - 1,
                    tx,
                    ty + t - 1,
                    tx + xoff,
                    ty + t - 1 - yoff,
                    tx + t + xoff,
                    ty + t - 1 - yoff,
                ),
                bg,
                COL_GRID,
            )

            tx += xoff
            ty -= yoff
        }

        sink.rect(tx, ty, t, t, bg)

        if (tile and DF_HIGHLIGHT_PENCIL != 0L) {
            sink.poly(
                intArrayOf(tx, ty, tx + t / 2, ty, tx, ty + t / 2),
                COL_HIGHLIGHT,
                COL_HIGHLIGHT,
            )
        }

        if (tile and DF_PLAYAREA != 0L) {
            sink.poly(
                intArrayOf(
                    tx,
                    ty - 1,
                    tx + t,
                    ty - 1,
                    tx + t,
                    ty + t - 1,
                    tx,
                    ty + t - 1,
                ),
                -1,
                COL_GRID,
            )
        }

        if (tile and DF_DIGIT_MASK != 0L) {
            val colour = when {
                tile and DF_ERROR != 0L -> COL_ERROR
                tile and DF_CLUE_DONE != 0L -> COL_DONE
                x < 0 || y < 0 || x >= w || y >= w -> COL_GRID
                tile and DF_IMMUTABLE != 0L -> COL_GRID
                else -> COL_USER
            }
            val size = if (tile and DF_PLAYAREA != 0L) t / 2 else t * 2 / 5
            sink.text(
                tx + t / 2,
                ty + t / 2,
                Text.FONT_VARIABLE,
                size,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                colour,
                ((tile and DF_DIGIT_MASK).toInt()).toString(),
            )
        } else {
            var npencil = 0
            for (i in 1..w) {
                if (tile and (1L shl (i + DF_PENCIL_SHIFT)) != 0L) npencil++
            }
            if (npencil != 0) {
                val minph = 2

                // The whole square minus the space that impinging
                // towers take in 3D mode.
                var pl = tx + (if (ui.threeD) x3dDisp(w, w, t) else 0)
                val pr = tx + t
                var pt = ty
                val pb = ty + t - (if (ui.threeD) y3dDisp(w, w, t) else 0)

                var bestsize = 0.0f
                var pbest = 0
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
                // C asserts `pbest > 0`; the first pass always improves
                // a zero best size for a positive box.
                check(pbest > 0) { "towers pencil layout has no grid" }

                pw = pbest
                var ph = (npencil + pw - 1) / pw
                ph = max(ph, minph)

                val fontsize = min((pr - pl) / pw, (pb - pt) / ph)
                pl += (pr - pl - fontsize * pw) / 2
                pt += (pb - pt - fontsize * ph) / 2

                var j = 0
                for (i in 1..w) {
                    if (tile and (1L shl (i + DF_PENCIL_SHIFT)) == 0L) continue
                    val dx = j % pw
                    val dy = j / pw
                    sink.text(
                        pl + fontsize * (2 * dx + 1) / 2,
                        pt + fontsize * (2 * dy + 1) / 2,
                        Text.FONT_VARIABLE,
                        fontsize,
                        Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                        COL_PENCIL,
                        i.toString(),
                    )
                    j++
                }
            }
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
}
