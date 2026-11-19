package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `loopy.c`: Nikoli's `Loop the loop` (Slitherlink).
 *
 * The player draws one closed loop so that every clue face is visited
 * the stated number of times. A state stores one clue per face and one
 * of `LINE_YES`, `LINE_UNKNOWN`, or `LINE_NO` per edge, over a shared
 * immutable [Grid].
 *
 * Three parts are coupled and must match C exactly:
 *
 * - the loopy grid-type index (`loopy.c:288-307`) is not the [GridType]
 *   order, so [kinds] translates explicitly;
 * - the generator draws the aperiodic grid description first, then runs
 *   [generateLoop] with a null bias, shuffles the face list once, and
 *   runs the solver once per face before the "too easy" retry. Every
 *   clue in a description depends on that order;
 * - the four solver stages are the generation gate and the Solve
 *   answer, so they are literal ports, including the dline index
 *   convention and the difficulty thresholds.
 *
 * The descendants of upstream `atoi` saturate on overflow. C leaves that
 * undefined; a saturated value is out of range for every edge index and
 * is rejected by validation for a size. See [atoi].
 *
 * Generation cost is the C cost: `remove_clues` runs the full solver
 * once per face and the retry loops are unbounded. [validateParams]
 * keeps the C bounds, so the app must generate off the UI thread. D-033
 * refuses an area above [MAX_AREA] (20x20) before the first draw.
 */
object Loopy : Game {

    // ------------------------------------------------------------------
    // Constants, `loopy.c:103-355`.
    // ------------------------------------------------------------------

    /** D-033: the measured generation cap, 20x20, for every grid type. */
    internal const val MAX_AREA = 400

    // Palette, `loopy.c:103-113`.
    private const val COL_BACKGROUND = 0
    private const val COL_FOREGROUND = 1
    private const val COL_LINEUNKNOWN = 2
    private const val COL_HIGHLIGHT = 3
    private const val COL_MISTAKE = 4
    private const val COL_SATISFIED = 5
    private const val COL_CURSOR = 6
    private const val COL_FAINT = 7
    private const val NCOLOURS = 8

    // Line states, `loopy.c:228`. The values are part of the contract:
    // a line array filled with `LINE_UNKNOWN` is filled with 1.
    private const val LINE_YES = 0
    private const val LINE_UNKNOWN = 1
    private const val LINE_NO = 2

    // Drawstate line values, `loopy.c:229-230`.
    private const val DS_LINE_YES = 0
    private const val DS_LINE_UNKNOWN = 1
    private const val DS_LINE_NO = 2
    private const val DS_LINE_ERROR = 3

    // Difficulties, `loopy.c:184-196`.
    private const val DIFF_EASY = 0
    private const val DIFF_NORMAL = 1
    private const val DIFF_TRICKY = 2
    private const val DIFF_HARD = 3
    private const val DIFF_MAX = 4

    private val DIFF_NAMES = arrayOf("Easy", "Normal", "Tricky", "Hard")
    private val DIFF_CHARS = charArrayOf('e', 'n', 't', 'h')

    // Solver difficulties, `loopy.c:206-217`, in call order.
    private val SOLVER_DIFFS = intArrayOf(DIFF_EASY, DIFF_NORMAL, DIFF_HARD, DIFF_EASY)
    private const val NUM_SOLVERS = 4

    /** `MAX_FACE_SIZE`, `loopy.c:2447`. */
    private const val MAX_FACE_SIZE = 14

    /** `REDRAW_OBJECTS_LIMIT`, `loopy.c:3562`. */
    private const val REDRAW_OBJECTS_LIMIT = 16

    /** The five redraw phases, `loopy.c:3448-3451`. */
    private val REDRAW_PHASES = intArrayOf(
        COL_FAINT,
        COL_LINEUNKNOWN,
        COL_FOREGROUND,
        COL_HIGHLIGHT,
        COL_MISTAKE,
    )

    private const val PREFERRED_TILE_SIZE = 32
    private const val GRID_DESC_SEP = '_'

    // Solid-forest component states, `loopy.c:1703`.
    private const val COMP_NONE = 0
    private const val COMP_LOOP = 1
    private const val COMP_PATH = 2
    private const val COMP_SILLY = 3
    private const val COMP_EMPTY = 4

    /**
     * `enum solver_status`, `loopy.c:142-147`. The declaration order is
     * the C order; no value is serialized.
     */
    enum class SolverStatus {
        SOLVED,
        MISTAKE,
        AMBIGUOUS,
        INCOMPLETE,
    }

    /**
     * `game_ui.autofollow`, `loopy.c:911-915`. [ordinal] is the
     * preference choice index and the `auto-follow` keyword order.
     */
    enum class AutoFollow {
        OFF,
        FIXED,
        ADAPTIVE,
    }

    /**
     * One row of the `GRIDLIST` macro, `loopy.c:288-307`. [type] is the
     * [GridType] value, which is *not* the loopy index: the position in
     * [kinds] is the loopy index used in parameters and descriptions.
     */
    private class GridKind(
        val title: String,
        val type: GridType,
        val amin: Int,
        val omin: Int,
    )

    private val kinds = listOf(
        GridKind("Squares", GridType.SQUARE, 3, 3),
        GridKind("Triangular", GridType.TRIANGULAR, 3, 3),
        GridKind("Honeycomb", GridType.HONEYCOMB, 3, 3),
        GridKind("Snub-Square", GridType.SNUBSQUARE, 3, 3),
        GridKind("Cairo", GridType.CAIRO, 3, 4),
        GridKind("Great-Hexagonal", GridType.GREATHEXAGONAL, 3, 3),
        GridKind("Octagonal", GridType.OCTAGONAL, 3, 3),
        GridKind("Kites", GridType.KITES, 3, 3),
        GridKind("Floret", GridType.FLORET, 1, 2),
        GridKind("Dodecagonal", GridType.DODECAGONAL, 2, 2),
        GridKind("Great-Dodecagonal", GridType.GREATDODECAGONAL, 2, 2),
        GridKind("Penrose (kite/dart)", GridType.PENROSE_P2, 3, 3),
        GridKind("Penrose (rhombs)", GridType.PENROSE_P3, 3, 3),
        GridKind("Great-Great-Dodecagonal", GridType.GREATGREATDODECAGONAL, 2, 2),
        GridKind("Kagome", GridType.KAGOME, 3, 3),
        GridKind("Compass-Dodecagonal", GridType.COMPASSDODECAGONAL, 2, 2),
        GridKind("Hats", GridType.HATS, 6, 6),
        GridKind("Spectres", GridType.SPECTRES, 6, 6),
    )

    /** `loopy_generate_grid`: the [GridType] behind a loopy grid index. */
    internal fun gridTypeFor(index: Int): GridType = kinds[index].type

    /** The number of loopy grid types, `NUM_GRID_TYPES`. */
    internal val gridTypeCount: Int get() = kinds.size

    /** `struct game_params`, `loopy.c:219-223`. */
    data class Params(
        val w: Int,
        val h: Int,
        val difficulty: Int,
        /** The loopy grid-type index, not a [GridType] ordinal. */
        val type: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, difficulty, type)
    }

    /**
     * `struct game_state`, `loopy.c:121-140`. The grid is shared by
     * every state of the history; the arrays are copied per state.
     */
    class State internal constructor(
        val grid: Grid,
        /** One clue per face, `-1` when the face has none. */
        val clues: IntArray,
        /** One [LINE_YES], [LINE_UNKNOWN], or [LINE_NO] per edge. */
        val lines: IntArray,
        /** One error mark per edge, recomputed by completion. */
        val lineErrors: BooleanArray,
        var exactlyOneLoop: Boolean,
        var solved: Boolean,
        var cheated: Boolean,
        /** The loopy grid-type index, for the text form. */
        val gridType: Int,
    ) : Game.State {

        val numFaces: Int get() = grid.faces.size

        val numEdges: Int get() = grid.edges.size

        val numDots: Int get() = grid.dots.size

        /** `dup_game`: a deep copy of the arrays over the same grid. */
        override fun copy(): Game.State = State(
            grid,
            clues.copyOf(),
            lines.copyOf(),
            lineErrors.copyOf(),
            exactlyOneLoop,
            solved,
            cheated,
            gridType,
        )

        fun clueAt(face: Int): Int = clues[face]

        fun lineAt(edge: Int): Int = lines[edge]

        fun errorAt(edge: Int): Boolean = lineErrors[edge]

        override fun equals(other: Any?): Boolean =
            other is State &&
                grid.type == other.grid.type &&
                numFaces == other.numFaces &&
                numEdges == other.numEdges &&
                numDots == other.numDots &&
                clues.contentEquals(other.clues) &&
                lines.contentEquals(other.lines) &&
                lineErrors.contentEquals(other.lineErrors) &&
                exactlyOneLoop == other.exactlyOneLoop &&
                solved == other.solved &&
                cheated == other.cheated &&
                gridType == other.gridType

        override fun hashCode(): Int {
            var result = grid.type.hashCode()
            result = 31 * result + clues.contentHashCode()
            result = 31 * result + lines.contentHashCode()
            result = 31 * result + lineErrors.contentHashCode()
            result = 31 * result + exactlyOneLoop.hashCode()
            result = 31 * result + solved.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + gridType
            return result
        }
    }

    /**
     * `struct game_ui`, `loopy.c:894-916`. [drawFaintLines] and
     * [autoFollow] are preferences: they change drawing and input, are
     * not serialized, and are not part of a [DrawState].
     */
    class Ui internal constructor() : Game.Ui {
        /** Cursor position in grid coordinates. */
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false

        /** `draw-faint-lines`; default true. */
        var drawFaintLines: Boolean = true

        /** `auto-follow`; default [AutoFollow.OFF]. */
        var autoFollow: AutoFollow = AutoFollow.OFF
    }

    /**
     * `struct game_drawstate`, `loopy.c:240-255`. One blitter caches the
     * crosshair background; [curEdge] is kept because C keeps it, but it
     * changes no colour.
     */
    class DrawState internal constructor(numFaces: Int, numEdges: Int) : Game.DrawState {
        var started: Boolean = false
        var tileSize: Int = 0
        var flashing: Boolean = false
        val textX: IntArray = IntArray(numFaces) { -1 }
        val textY: IntArray = IntArray(numFaces) { -1 }
        val lines: IntArray = IntArray(numEdges) { DS_LINE_UNKNOWN }
        val clueError: BooleanArray = BooleanArray(numFaces)
        val clueSatisfied: BooleanArray = BooleanArray(numFaces)
        var curVisible: Boolean = false
        var curBlX: Int = 0
        var curBlY: Int = 0
        var curBl: Int? = null
        var curEdge: GridEdge? = null
    }

    /** One generated board plus the counts the retry fixtures pin. */
    internal data class Generated(
        val text: String,
        /** The grid description, or null for a grid type that has none. */
        val gridDescription: String?,
        /** Passes of the `newboard_please` loop, at least one. */
        val boardAttempts: Int,
        /** Calls of `add_full_clues`, at least one. */
        val fullClueAttempts: Int,
        /** Passes repeated because the board was solvable one level too low. */
        val tooEasyRetries: Int,
    )

    // ------------------------------------------------------------------
    // Identity, presets, and parameters.
    // ------------------------------------------------------------------

    override val name: String = "Loopy"
    override val backendName: String = "loopy"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val isTimed: Boolean = false
    override val flags: Int = 0

    /**
     * The SMALL_SCREEN presets, `loopy.c:543-552` and `570-581`, in menu
     * order: the top-level nine, then the eleven of `More...`.
     */
    private val presetList = listOf(
        Params(7, 7, DIFF_EASY, 0),
        Params(7, 7, DIFF_NORMAL, 0),
        Params(7, 7, DIFF_HARD, 0),
        Params(7, 7, DIFF_HARD, 1),
        Params(5, 5, DIFF_HARD, 3),
        Params(7, 7, DIFF_HARD, 4),
        Params(5, 5, DIFF_HARD, 7),
        Params(6, 6, DIFF_HARD, 11),
        Params(6, 6, DIFF_HARD, 12),
        Params(7, 7, DIFF_HARD, 2),
        Params(5, 4, DIFF_HARD, 5),
        Params(5, 4, DIFF_HARD, 14),
        Params(5, 5, DIFF_HARD, 6),
        Params(3, 3, DIFF_HARD, 8),
        Params(3, 3, DIFF_HARD, 9),
        Params(3, 3, DIFF_HARD, 10),
        Params(3, 2, DIFF_HARD, 13),
        Params(3, 3, DIFF_HARD, 15),
        Params(6, 6, DIFF_HARD, 16),
        Params(6, 6, DIFF_HARD, 17),
    )

    /** `default_params`: 7x7 Easy Squares under SMALL_SCREEN. */
    override fun defaultParams(): Game.Params = Params(7, 7, DIFF_EASY, 0)

    /**
     * `preset_menu_add_preset_with_title`, `loopy.c:597-610`: the title
     * prints the height first, then the width.
     */
    override fun presets(): List<Preset> = presetList.map { p ->
        Preset(
            "${p.h}x${p.w} ${kinds[p.type].title} - ${DIFF_NAMES[p.difficulty]}",
            encodeParams(p, true),
        )
    }

    /**
     * `decode_params`, `loopy.c:633-656`, with the literal control flow.
     * Only a digit run right after the first number hides the `x`; a
     * sign or a space stops the scan at its start, so `x` is not seen.
     * The type keeps the base value when there is no `t`. The
     * one-argument form has no caller-supplied base, so it starts from
     * the compile-time type 0.
     */
    override fun decodeParams(text: String): Game.Params = decodeFrom(text, 0)

    /**
     * `decode_params` over [base]: C keeps `params->type` when [text] has
     * no `t`, and the short encoding always writes `t`, so a
     * `params:desc` id validates against the base grid type
     * (`midend.c:1890-1921`). Width, height, and difficulty are always
     * written by the decoder, so only the type comes from [base].
     */
    override fun decodeParams(text: String, base: Game.Params): Game.Params =
        decodeFrom(text, (base as Params).type)

    private fun decodeFrom(text: String, defaultType: Int): Game.Params {
        var position = 0
        var w = atoi(text, 0)
        var h = w
        var type = defaultType
        var difficulty = DIFF_EASY
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            h = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && text[position] == 't') {
            position++
            type = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && text[position] == 'd') {
            position++
            for (i in 0 until DIFF_MAX) {
                if (position < text.length && text[position] == DIFF_CHARS[i]) {
                    difficulty = i
                }
            }
            if (position < text.length) position++
        }
        return Params(w, h, difficulty, type)
    }

    /**
     * `encode_params`, `loopy.c:658-665`: `%dx%dt%d`, then `d%c` in the
     * full form. At difficulty 4 C reads the NUL that ends `diffchars`
     * and writes a bare `d`, which decodes as Easy; the port writes the
     * same text. Above 4 C reads out of bounds, and the port writes the
     * bare `d` as its defined result.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val base = "${p.w}x${p.h}t${p.type}"
        if (!full) return base
        return if (p.difficulty in 0 until DIFF_MAX) {
            base + "d" + DIFF_CHARS[p.difficulty]
        } else {
            base + "d"
        }
    }

    /**
     * `validate_params`, `loopy.c:712-734`, in C order. The last check
     * is a C assert; the port returns a message instead, which is only
     * reachable from a hand-made [Params].
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.type !in kinds.indices) return "Illegal grid type"
        val kind = kinds[p.type]
        if (p.w < kind.amin || p.h < kind.amin) {
            return "Width and height for this grid type must both be at least ${kind.amin}"
        }
        if (p.w < kind.omin && p.h < kind.omin) {
            return "At least one of width and height for this grid type must be at least ${kind.omin}"
        }
        Grid.validateParams(kind.type, p.w, p.h)?.let { return it }
        if (p.difficulty < 0 || p.difficulty >= DIFF_MAX) {
            return "Unknown difficulty level"
        }
        return null
    }

    // ------------------------------------------------------------------
    // Description and generation, `loopy.c:737-1691`.
    // ------------------------------------------------------------------

    /** `extract_grid_desc`, `loopy.c:777-792`: split at the first `_`. */
    internal fun splitGridDesc(desc: String): Pair<String?, String> {
        val sep = desc.indexOf(GRID_DESC_SEP)
        return if (sep < 0) {
            null to desc
        } else {
            desc.substring(0, sep) to desc.substring(sep + 1)
        }
    }

    /** `state_to_text`, `loopy.c:737-770`. */
    internal fun encodeClueText(clues: IntArray): String {
        val out = StringBuilder()
        var emptyCount = 0
        for (clue in clues) {
            if (clue < 0) {
                if (emptyCount > 25) {
                    out.append((emptyCount + 'a'.code - 1).toChar())
                    emptyCount = 0
                }
                emptyCount++
            } else {
                if (emptyCount > 0) {
                    out.append((emptyCount + 'a'.code - 1).toChar())
                    emptyCount = 0
                }
                out.append(clueToChar(clue))
            }
        }
        if (emptyCount > 0) out.append((emptyCount + 'a'.code - 1).toChar())
        return out.toString()
    }

    /** `CLUE2CHAR`, `loopy.c:354-355`. */
    private fun clueToChar(clue: Int): Char = when {
        clue < 0 -> ' '
        clue < 10 -> '0' + clue
        else -> 'A' + clue - 10
    }

    /** `validate_desc`, `loopy.c:796-840`, in C order. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        if (p.type !in kinds.indices) return "Illegal grid type"
        val kind = kinds[p.type]
        val (gridDesc, clueText) = splitGridDesc(desc)
        Grid.validateDesc(kind.type, p.w, p.h, gridDesc)?.let { return it }
        val grid = Grid.new(kind.type, p.w, p.h, gridDesc)
        var count = 0
        for (c in clueText) {
            if (c in '0'..'9' || c in 'A'..'Z') {
                count++
                continue
            }
            if (c >= 'a') {
                count += c.code - 'a'.code + 1
                continue
            }
            return "Unknown character in description"
        }
        if (count < grid.faces.size) return "Description too short for board size"
        if (count > grid.faces.size) return "Description too long for board size"
        return null
    }

    /** `game_configure` (`loopy.c:667-698`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Grid type",
                ":Squares:Triangular:Honeycomb:Snub-Square:Cairo:" +
                    "Great-Hexagonal:Octagonal:Kites:Floret:Dodecagonal:" +
                    "Great-Dodecagonal:Penrose (kite/dart):Penrose (rhombs):" +
                    "Great-Great-Dodecagonal:Kagome:Compass-Dodecagonal:" +
                    "Hats:Spectres",
                null,
                p.type,
            ),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Normal:Tricky:Hard",
                null,
                p.difficulty,
            ),
        )
    }

    /** `custom_params` (`loopy.c:700-710`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[3] as ConfigItem.Choices).selected,
            (items[2] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`loopy.c:967-990`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Draw excluded grid lines faintly",
                true,
                "draw-faint-lines",
            ),
            ConfigItem.Choices(
                "Auto-follow unique paths of edges",
                ":No:Based on grid only:Based on grid and game state",
                ":off:fixed:adaptive",
                0,
                "auto-follow",
            ),
        )

    /** `set_prefs` (`loopy.c:992-996`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.drawFaintLines = prefs.bool("draw-faint-lines")
        u.autoFollow = AutoFollow.values()[prefs.choice("auto-follow")]
    }

    /**
     * `new_game_desc`, `loopy.c:1571-1633`. The grid description draw is
     * first, then the retry loop on the same grid: `add_full_clues`
     * until the solver reports a unique solution, `remove_clues`, and
     * the "too easy" retry.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description = Description(generate(params as Params, random, cancel).text)

    /** [newDesc] with the retry counts exposed for fixtures. */
    internal fun generate(
        p: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generated {
        require(p.w.toLong() * p.h <= MAX_AREA) {
            Game.AREA_TOO_LARGE
        }
        val kind = kinds[p.type]
        val gridDesc = Grid.newDesc(kind.type, p.w, p.h, random)
        val grid = Grid.new(kind.type, p.w, p.h, gridDesc)
        val clues = IntArray(grid.faces.size)
        val lines = IntArray(grid.edges.size) { LINE_UNKNOWN }
        val errors = BooleanArray(grid.edges.size)

        var boardAttempts = 0
        var fullClueAttempts = 0
        var tooEasyRetries = 0

        while (true) {
            cancel.check()
            boardAttempts++
            lines.fill(LINE_UNKNOWN)
            errors.fill(false)

            // `do { add_full_clues } while (!game_has_unique_soln)`.
            while (true) {
                cancel.check()
                fullClueAttempts++
                addFullClues(grid, clues, random)
                val state = State(
                    grid,
                    clues.copyOf(),
                    lines.copyOf(),
                    errors.copyOf(),
                    exactlyOneLoop = false,
                    solved = false,
                    cheated = false,
                    gridType = p.type,
                )
                if (hasUniqueSolution(state, p.difficulty)) break
            }

            var state = State(
                grid,
                clues.copyOf(),
                lines.copyOf(),
                errors.copyOf(),
                exactlyOneLoop = false,
                solved = false,
                cheated = false,
                gridType = p.type,
            )
            state = removeClues(state, random, p.difficulty)

            if (p.difficulty > 0 && hasUniqueSolution(state, p.difficulty - 1)) {
                tooEasyRetries++
                continue
            }

            val clueText = encodeClueText(state.clues)
            val text = if (gridDesc != null) "$gridDesc$GRID_DESC_SEP$clueText" else clueText
            return Generated(text, gridDesc, boardAttempts, fullClueAttempts, tooEasyRetries)
        }
    }

    /**
     * `add_full_clues`, `loopy.c:1485-1513`: one `generate_loop` with no
     * bias, then a clue on both sides of every edge between faces of
     * different colours. The infinite face is black, so its side adds
     * nothing.
     */
    private fun addFullClues(grid: Grid, clues: IntArray, random: RandomState) {
        val board = IntArray(grid.faces.size)
        generateLoop(grid, board, random, null)
        clues.fill(0)
        for (edge in grid.edges) {
            val c1 = faceColourOf(board, edge.face1)
            val c2 = faceColourOf(board, edge.face2)
            if (c1 != c2) {
                edge.face1?.let { clues[it.index]++ }
                edge.face2?.let { clues[it.index]++ }
            }
        }
    }

    /** `FACE_COLOUR`: the infinite face is black. */
    private fun faceColourOf(board: IntArray, face: GridFace?): FaceColour =
        if (face == null) FaceColour.BLACK else FaceColour.entries[board[face.index]]

    /**
     * `remove_clues`, `loopy.c:1535-1568`: one shuffle of the face list,
     * then one solver run per face, in that order.
     */
    private fun removeClues(state: State, random: RandomState, diff: Int): State {
        val numFaces = state.numFaces
        val faceList = IntArray(numFaces) { it }
        Divvy.shuffle(faceList, numFaces, random)

        var ret = state.copy() as State
        for (n in 0 until numFaces) {
            val saved = ret.copy() as State
            ret.clues[faceList[n]] = -1
            if (hasUniqueSolution(ret, diff)) {
                // Keep the board with this clue removed.
            } else {
                ret = saved
            }
        }
        return ret
    }

    /**
     * `game_has_unique_soln`, `loopy.c:1516-1531`. The C assert on a
     * mistake is unreachable from generation; it stays a `check` so a
     * bug is loud, not silent.
     */
    internal fun hasUniqueSolution(state: State, diff: Int): Boolean {
        val start = newSolverState(state, diff)
        val result = solveGameRec(start)
        check(result.status != SolverStatus.MISTAKE) {
            "the solver reports a contradiction on a generated board"
        }
        return result.status == SolverStatus.SOLVED
    }

    /**
     * `new_game`, `loopy.c:1635-1691`: split the grid description, build
     * the grid, and decode the run-length clue text.
     *
     * The C code asserts that the text is long enough. The port throws
     * instead, because [validateDesc] guarantees the count and a state
     * must never read past the clue array.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val kind = kinds[p.type]
        val (gridDesc, clueText) = splitGridDesc(desc)
        val grid = Grid.new(kind.type, p.w, p.h, gridDesc)
        val clues = IntArray(grid.faces.size) { -1 }
        var position = 0
        var emptiesToMake = 0
        for (i in grid.faces.indices) {
            if (emptiesToMake > 0) {
                emptiesToMake--
                clues[i] = -1
                continue
            }
            require(position < clueText.length) {
                "description is too short for ${grid.faces.size} faces"
            }
            val c = clueText[position]
            val n = c.code - '0'.code
            val n2 = c.code - 'A'.code + 10
            when {
                n in 0..9 -> clues[i] = n
                n2 in 10..35 -> clues[i] = n2
                else -> {
                    val run = c.code - 'a'.code + 1
                    require(run > 0) { "unknown character in description: '$c'" }
                    clues[i] = -1
                    emptiesToMake = run - 1
                }
            }
            position++
        }
        return State(
            grid,
            clues,
            IntArray(grid.edges.size) { LINE_UNKNOWN },
            BooleanArray(grid.edges.size),
            exactlyOneLoop = false,
            solved = false,
            cheated = false,
            gridType = p.type,
        )
    }

    // ------------------------------------------------------------------
    // Completion, `loopy.c:1695-1926`.
    // ------------------------------------------------------------------

    /**
     * `check_completion`: fills [State.lineErrors] and
     * [State.exactlyOneLoop] and returns true only when one loop
     * satisfies every clue.
     */
    internal fun checkCompletion(state: State): Boolean {
        val g = state.grid
        state.lineErrors.fill(false)

        val dsf = Dsf.new(g.dots.size)
        for (edge in g.edges) {
            if (state.lines[edge.index] == LINE_YES) {
                dsf.merge(edge.dot1.index, edge.dot2.index)
            }
        }

        val componentState = IntArray(g.dots.size) { i ->
            if (dsf.canonify(i) == i) COMP_LOOP else COMP_NONE
        }

        for (i in g.dots.indices) {
            val comp = dsf.canonify(i)
            val dot = g.dots[i]
            val yes = dotOrder(state, i, LINE_YES)
            val unknown = dotOrder(state, i, LINE_UNKNOWN)
            if ((yes == 1 && unknown == 0) || yes >= 3) {
                for (j in 0 until dot.order) {
                    val edge = dot.edges[j]!!
                    if (state.lines[edge.index] == LINE_YES) {
                        state.lineErrors[edge.index] = true
                    }
                }
                componentState[comp] = COMP_SILLY
            } else if (yes == 0) {
                componentState[comp] = COMP_EMPTY
            } else if (yes == 1) {
                if (componentState[comp] != COMP_SILLY) {
                    componentState[comp] = COMP_PATH
                }
            }
        }

        var sillyCount = 0
        var loopCount = 0
        var pathCount = 0
        var totalPathSize = 0
        var largestComp = -1
        var largestSize = -1
        for (i in g.dots.indices) {
            when (componentState[i]) {
                COMP_SILLY -> sillyCount++
                COMP_PATH -> {
                    totalPathSize += dsf.size(i)
                    pathCount = 1
                }
                COMP_LOOP -> {
                    loopCount++
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

        if (loopCount > 0 && loopCount + pathCount > 1) {
            for (edge in g.edges) {
                if (state.lines[edge.index] != LINE_YES) continue
                val comp = dsf.canonify(edge.dot1.index)
                if ((componentState[comp] == COMP_PATH && largestComp != -1) ||
                    (componentState[comp] == COMP_LOOP && comp != largestComp)
                ) {
                    state.lineErrors[edge.index] = true
                }
            }
        }

        val solved: Boolean
        if (loopCount == 1 && pathCount == 0 && sillyCount == 0) {
            var ret = true
            for (i in g.faces.indices) {
                val clue = state.clues[i]
                if (clue >= 0 && faceOrder(state, i, LINE_YES) != clue) {
                    ret = false
                    break
                }
            }
            solved = ret
            state.exactlyOneLoop = true
        } else {
            solved = false
            state.exactlyOneLoop = false
        }
        return solved
    }

    /** `dot_order`, `loopy.c:1394-1407`. */
    private fun dotOrder(state: State, dot: Int, lineType: Int): Int {
        val d = state.grid.dots[dot]
        var count = 0
        for (i in 0 until d.order) {
            val edge = d.edges[i]!!
            if (state.lines[edge.index] == lineType) count++
        }
        return count
    }

    /** `face_order`, `loopy.c:1411-1424`. */
    private fun faceOrder(state: State, face: Int, lineType: Int): Int {
        val f = state.grid.faces[face]
        var count = 0
        for (i in 0 until f.order) {
            val edge = f.edge(i)
            if (state.lines[edge.index] == lineType) count++
        }
        return count
    }

    // ------------------------------------------------------------------
    // Solver, `loopy.c:1264-3096`.
    // ------------------------------------------------------------------

    /**
     * `solver_state`, `loopy.c:150-177`. The caches are filled by
     * [newSolverState] and kept in step by [solverSetLine].
     */
    internal class SolverState(
        var state: State,
        var status: SolverStatus,
        val diff: Int,
        val loopLen: IntArray,
        val dotYesCount: IntArray,
        val dotNoCount: IntArray,
        val faceYesCount: IntArray,
        val faceNoCount: IntArray,
        val dotSolved: BooleanArray,
        val faceSolved: BooleanArray,
        val dotDsf: Dsf,
        val dlines: IntArray?,
        val lineDsf: Dsf?,
    )

    /** `new_solver_state`, `loopy.c:397-444`. */
    internal fun newSolverState(state: State, diff: Int): SolverState {
        val g = state.grid
        val numDots = g.dots.size
        val numFaces = g.faces.size
        val numEdges = g.edges.size
        return SolverState(
            state = state.copy() as State,
            status = SolverStatus.INCOMPLETE,
            diff = diff,
            loopLen = IntArray(numDots) { 1 },
            dotYesCount = IntArray(numDots),
            dotNoCount = IntArray(numDots),
            faceYesCount = IntArray(numFaces),
            faceNoCount = IntArray(numFaces),
            dotSolved = BooleanArray(numDots),
            faceSolved = BooleanArray(numFaces),
            dotDsf = Dsf.new(numDots),
            dlines = if (diff < DIFF_NORMAL) null else IntArray(2 * numEdges),
            lineDsf = if (diff < DIFF_HARD) null else Dsf.newFlip(numEdges),
        )
    }

    /** `dup_solver_state`, `loopy.c:466-515`. */
    private fun dupSolverState(s: SolverState): SolverState = SolverState(
        state = s.state.copy() as State,
        status = s.status,
        diff = s.diff,
        loopLen = s.loopLen.copyOf(),
        dotYesCount = s.dotYesCount.copyOf(),
        dotNoCount = s.dotNoCount.copyOf(),
        faceYesCount = s.faceYesCount.copyOf(),
        faceNoCount = s.faceNoCount.copyOf(),
        dotSolved = s.dotSolved.copyOf(),
        faceSolved = s.faceSolved.copyOf(),
        dotDsf = s.dotDsf.copy(),
        dlines = s.dlines?.copyOf(),
        lineDsf = s.lineDsf?.copy(),
    )

    /** `solver_set_line`, `loopy.c:1267-1319`. */
    private fun solverSetLine(s: SolverState, edge: Int, lineNew: Int): Boolean {
        check(lineNew != LINE_UNKNOWN) { "solver_set_line must set YES or NO" }
        val state = s.state
        if (state.lines[edge] == lineNew) return false
        state.lines[edge] = lineNew

        val e = state.grid.edges[edge]
        if (lineNew == LINE_YES) {
            s.dotYesCount[e.dot1.index]++
            s.dotYesCount[e.dot2.index]++
            e.face1?.let { s.faceYesCount[it.index]++ }
            e.face2?.let { s.faceYesCount[it.index]++ }
        } else {
            s.dotNoCount[e.dot1.index]++
            s.dotNoCount[e.dot2.index]++
            e.face1?.let { s.faceNoCount[it.index]++ }
            e.face2?.let { s.faceNoCount[it.index]++ }
        }
        return true
    }

    /** `merge_dots`, `loopy.c:1333-1354`. */
    private fun mergeDots(s: SolverState, edgeIndex: Int): Boolean {
        val e = s.state.grid.edges[edgeIndex]
        var i = s.dotDsf.canonify(e.dot1.index)
        var j = s.dotDsf.canonify(e.dot2.index)
        if (i == j) return true
        val len = s.loopLen[i] + s.loopLen[j]
        s.dotDsf.merge(i, j)
        i = s.dotDsf.canonify(i)
        s.loopLen[i] = len
        return false
    }

    /** `merge_lines`, `loopy.c:1359-1385`. */
    private fun mergeLines(s: SolverState, i: Int, j: Int, inverse: Boolean): Boolean {
        val dsf = s.lineDsf!!
        val first = dsf.canonifyFlip(i)
        var inverseCombined = inverse xor first.inverse
        val second = dsf.canonifyFlip(j)
        inverseCombined = inverseCombined xor second.inverse
        dsf.mergeFlip(first.root, second.root, inverseCombined)
        return first.root != second.root
    }

    /** `dot_setall`, `loopy.c:1428-1452`. */
    private fun dotSetAll(s: SolverState, dot: Int, oldType: Int, newType: Int): Boolean {
        if (oldType == newType) return false
        var retval = false
        val d = s.state.grid.dots[dot]
        for (i in 0 until d.order) {
            val lineIndex = d.edges[i]!!.index
            if (s.state.lines[lineIndex] == oldType) {
                solverSetLine(s, lineIndex, newType)
                retval = true
            }
        }
        return retval
    }

    /** `face_setall`, `loopy.c:1455-1479`. */
    private fun faceSetAll(s: SolverState, face: Int, oldType: Int, newType: Int): Boolean {
        if (oldType == newType) return false
        var retval = false
        val f = s.state.grid.faces[face]
        for (i in 0 until f.order) {
            val lineIndex = f.edge(i).index
            if (s.state.lines[lineIndex] == oldType) {
                solverSetLine(s, lineIndex, newType)
                retval = true
            }
        }
        return retval
    }

    /** `dline_index_from_dot`, `loopy.c:1972-1988`. */
    internal fun dlineIndexFromDot(dot: GridDot, i: Int): Int {
        val e = dot.edges[i]!!
        return 2 * e.index + if (e.dot1 === dot) 1 else 0
    }

    /** `dline_index_from_face`, `loopy.c:1993-2010`. */
    internal fun dlineIndexFromFace(face: GridFace, i: Int): Int {
        val e = face.edge(i)
        val d = face.dot(i)
        return 2 * e.index + if (e.dot1 === d) 1 else 0
    }

    private fun isAtleastone(dlines: IntArray, index: Int): Boolean =
        dlines[index] and 1 != 0

    private fun setAtleastone(dlines: IntArray, index: Int): Boolean =
        setBit(dlines, index, 0)

    private fun isAtmostone(dlines: IntArray, index: Int): Boolean =
        dlines[index] and 2 != 0

    private fun setAtmostone(dlines: IntArray, index: Int): Boolean =
        setBit(dlines, index, 1)

    /** `SET_BIT`: true when this call changed the bit. */
    private fun setBit(array: IntArray, index: Int, bit: Int): Boolean {
        val mask = 1 shl bit
        if (array[index] and mask != 0) return false
        array[index] = array[index] or mask
        return true
    }

    /** `array_setall`, `loopy.c:2028-2038`. */
    private fun arraySetAll(array: IntArray, from: Int, to: Int, length: Int) {
        for (i in 0 until length) {
            if (array[i] == from) array[i] = to
        }
    }

    /** `dline_set_opp_atleastone`, `loopy.c:2047-2074`. */
    private fun dlineSetOppAtleastone(s: SolverState, d: GridDot, edge: Int): Boolean {
        val n = d.order
        for (opp in 0 until n) {
            if (opp == edge || opp == edge + 1 || opp == edge - 1) continue
            if (opp == 0 && edge == n - 1) continue
            if (opp == n - 1 && edge == 0) continue
            var opp2 = opp + 1
            if (opp2 == n) opp2 = 0
            if (s.state.lines[d.edges[opp]!!.index] != LINE_UNKNOWN) continue
            if (s.state.lines[d.edges[opp2]!!.index] != LINE_UNKNOWN) continue
            return setAtleastone(s.dlines!!, dlineIndexFromDot(d, opp))
        }
        return false
    }

    /** `face_setall_identical`, `loopy.c:2079-2114`. */
    private fun faceSetAllIdentical(s: SolverState, faceIndex: Int, lineNew: Int): Boolean {
        var retval = false
        val f = s.state.grid.faces[faceIndex]
        val n = f.order
        for (i in 0 until n) {
            val line1 = f.edge(i).index
            if (s.state.lines[line1] != LINE_UNKNOWN) continue
            for (j in i + 1 until n) {
                val line2 = f.edge(j).index
                if (s.state.lines[line2] != LINE_UNKNOWN) continue
                val can1 = s.lineDsf!!.canonifyFlip(line1)
                val can2 = s.lineDsf!!.canonifyFlip(line2)
                if (can1.root == can2.root && can1.inverse == can2.inverse) {
                    solverSetLine(s, line1, lineNew)
                    solverSetLine(s, line2, lineNew)
                }
            }
        }
        return retval
    }

    /** `find_unknowns`, `loopy.c:2118-2132`. */
    private fun findUnknowns(
        state: State,
        edgeList: Array<GridEdge?>,
        expectedCount: Int,
    ): IntArray {
        val out = IntArray(expectedCount)
        var c = 0
        var position = 0
        while (c < expectedCount) {
            val lineIndex = edgeList[position]!!.index
            if (state.lines[lineIndex] == LINE_UNKNOWN) {
                out[c] = lineIndex
                c++
            }
            position++
        }
        return out
    }

    /** `parity_deductions`, `loopy.c:2139-2207`. */
    private fun parityDeductions(
        s: SolverState,
        edgeList: Array<GridEdge?>,
        totalParity: Int,
        unknownCount: Int,
    ): Int {
        val state = s.state
        var diff = DIFF_MAX
        val lineDsf = s.lineDsf!!
        val parity = totalParity != 0

        if (unknownCount == 2) {
            val e = findUnknowns(state, edgeList, 2)
            if (mergeLines(s, e[0], e[1], parity)) diff = min(diff, DIFF_HARD)
        } else if (unknownCount == 3) {
            val e = findUnknowns(state, edgeList, 3)
            val can = IntArray(3)
            val inv = BooleanArray(3)
            for (i in 0 until 3) {
                val root = lineDsf.canonifyFlip(e[i])
                can[i] = root.root
                inv[i] = root.inverse
            }
            if (can[0] == can[1]) {
                if (solverSetLine(
                        s,
                        e[2],
                        if (parity xor inv[0] xor inv[1]) LINE_YES else LINE_NO,
                    )
                ) {
                    diff = min(diff, DIFF_EASY)
                }
            }
            if (can[0] == can[2]) {
                if (solverSetLine(
                        s,
                        e[1],
                        if (parity xor inv[0] xor inv[2]) LINE_YES else LINE_NO,
                    )
                ) {
                    diff = min(diff, DIFF_EASY)
                }
            }
            if (can[1] == can[2]) {
                if (solverSetLine(
                        s,
                        e[0],
                        if (parity xor inv[1] xor inv[2]) LINE_YES else LINE_NO,
                    )
                ) {
                    diff = min(diff, DIFF_EASY)
                }
            }
        } else if (unknownCount == 4) {
            val e = findUnknowns(state, edgeList, 4)
            val can = IntArray(4)
            val inv = BooleanArray(4)
            for (i in 0 until 4) {
                val root = lineDsf.canonifyFlip(e[i])
                can[i] = root.root
                inv[i] = root.inverse
            }
            when {
                can[0] == can[1] -> {
                    if (mergeLines(s, e[2], e[3], parity xor inv[0] xor inv[1])) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
                can[0] == can[2] -> {
                    if (mergeLines(s, e[1], e[3], parity xor inv[0] xor inv[2])) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
                can[0] == can[3] -> {
                    if (mergeLines(s, e[1], e[2], parity xor inv[0] xor inv[3])) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
                can[1] == can[2] -> {
                    if (mergeLines(s, e[0], e[3], parity xor inv[1] xor inv[2])) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
                can[1] == can[3] -> {
                    if (mergeLines(s, e[0], e[2], parity xor inv[1] xor inv[3])) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
                can[2] == can[3] -> {
                    if (mergeLines(s, e[0], e[1], parity xor inv[2] xor inv[3])) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
            }
        }
        return diff
    }

    /** `trivial_deductions`, `loopy.c:2245-2396`. */
    internal fun trivialDeductions(s: SolverState): Int {
        val state = s.state
        val g = state.grid
        var diff = DIFF_MAX

        for (i in g.faces.indices) {
            val f = g.faces[i]
            if (s.faceSolved[i]) continue

            val currentYes = s.faceYesCount[i]
            val currentNo = s.faceNoCount[i]
            if (currentYes + currentNo == f.order) {
                s.faceSolved[i] = true
                continue
            }
            if (state.clues[i] < 0) continue

            if (state.clues[i] < currentYes) {
                s.status = SolverStatus.MISTAKE
                return DIFF_EASY
            }
            if (state.clues[i] == currentYes) {
                if (faceSetAll(s, i, LINE_UNKNOWN, LINE_NO)) diff = min(diff, DIFF_EASY)
                s.faceSolved[i] = true
                continue
            }
            if (f.order - state.clues[i] < currentNo) {
                s.status = SolverStatus.MISTAKE
                return DIFF_EASY
            }
            if (f.order - state.clues[i] == currentNo) {
                if (faceSetAll(s, i, LINE_UNKNOWN, LINE_YES)) diff = min(diff, DIFF_EASY)
                s.faceSolved[i] = true
                continue
            }

            if (f.order - state.clues[i] == currentNo + 1 &&
                f.order - currentYes - currentNo > 2
            ) {
                var foundE1 = -1
                var foundE2 = -1
                var found = false
                for (j in 0 until f.order) {
                    val e1 = f.edge(j).index
                    val e2 = f.edge(if (j + 1 < f.order) j + 1 else 0).index
                    val edge1 = g.edges[e1]
                    val edge2 = g.edges[e2]
                    val d = if (edge1.dot1 === edge2.dot1 || edge1.dot1 === edge2.dot2) {
                        edge1.dot1.index
                    } else {
                        edge1.dot2.index
                    }
                    if (state.lines[e1] == LINE_UNKNOWN && state.lines[e2] == LINE_UNKNOWN) {
                        val dot = g.dots[d]
                        for (k in 0 until dot.order) {
                            val e = dot.edges[k]!!.index
                            if (state.lines[e] == LINE_YES) {
                                foundE1 = e1
                                foundE2 = e2
                                found = true
                                break
                            }
                        }
                    }
                    if (found) break
                }
                if (found) {
                    for (j in 0 until f.order) {
                        val e = f.edge(j).index
                        if (state.lines[e] == LINE_UNKNOWN && e != foundE1 && e != foundE2) {
                            solverSetLine(s, e, LINE_YES)
                            diff = min(diff, DIFF_EASY)
                        }
                    }
                }
            }
        }

        for (i in g.dots.indices) {
            if (s.dotSolved[i]) continue
            val d = g.dots[i]
            val yes = s.dotYesCount[i]
            val no = s.dotNoCount[i]
            val unknown = d.order - yes - no
            when {
                yes == 0 -> {
                    if (unknown == 0) {
                        s.dotSolved[i] = true
                    } else if (unknown == 1) {
                        dotSetAll(s, i, LINE_UNKNOWN, LINE_NO)
                        diff = min(diff, DIFF_EASY)
                        s.dotSolved[i] = true
                    }
                }
                yes == 1 -> {
                    if (unknown == 0) {
                        s.status = SolverStatus.MISTAKE
                        return DIFF_EASY
                    } else if (unknown == 1) {
                        dotSetAll(s, i, LINE_UNKNOWN, LINE_YES)
                        diff = min(diff, DIFF_EASY)
                    }
                }
                yes == 2 -> {
                    if (unknown > 0) {
                        dotSetAll(s, i, LINE_UNKNOWN, LINE_NO)
                        diff = min(diff, DIFF_EASY)
                    }
                    s.dotSolved[i] = true
                }
                else -> {
                    s.status = SolverStatus.MISTAKE
                    return DIFF_EASY
                }
            }
        }
        return diff
    }

    /** `dline_deductions`, `loopy.c:2398-2723`. */
    internal fun dlineDeductions(s: SolverState): Int {
        val state = s.state
        val g = state.grid
        val dlines = s.dlines!!
        var diff = DIFF_MAX

        for (i in g.faces.indices) {
            val f = g.faces[i]
            val n = f.order
            val clue = state.clues[i]
            check(n <= MAX_FACE_SIZE) { "face $i has $n edges, above MAX_FACE_SIZE" }
            if (s.faceSolved[i]) continue
            if (clue < 0) continue

            val maxs = Array(MAX_FACE_SIZE) { IntArray(MAX_FACE_SIZE) }
            val mins = Array(MAX_FACE_SIZE) { IntArray(MAX_FACE_SIZE) }

            // The (j,j+1) and (j,j+2) entries.
            for (j in 0 until n) {
                var edgeIndex = f.edge(j).index
                val line1 = state.lines[edgeIndex]
                var k = j + 1
                if (k >= n) k = 0
                maxs[j][k] = if (line1 == LINE_NO) 0 else 1
                mins[j][k] = if (line1 == LINE_YES) 1 else 0

                val dlineIndex = dlineIndexFromFace(f, k)
                edgeIndex = f.edge(k).index
                val line2 = state.lines[edgeIndex]
                k++
                if (k >= n) k = 0

                var tmp = 2
                if (line1 == LINE_NO) tmp--
                if (line2 == LINE_NO) tmp--
                if (tmp == 2 && isAtmostone(dlines, dlineIndex)) tmp = 1
                maxs[j][k] = tmp

                tmp = 0
                if (line1 == LINE_YES) tmp++
                if (line2 == LINE_YES) tmp++
                if (tmp == 0 && isAtleastone(dlines, dlineIndex)) tmp = 1
                mins[j][k] = tmp
            }

            for (m in 3 until n) {
                for (j in 0 until n) {
                    var k = j + m
                    var u = j + 1
                    var v = j + 2
                    if (k >= n) k -= n
                    if (u >= n) u -= n
                    if (v >= n) v -= n
                    maxs[j][k] = maxs[j][u] + maxs[u][k]
                    mins[j][k] = mins[j][u] + mins[u][k]
                    var tmp = maxs[j][v] + maxs[v][k]
                    maxs[j][k] = min(maxs[j][k], tmp)
                    tmp = mins[j][v] + mins[v][k]
                    mins[j][k] = max(mins[j][k], tmp)
                }
            }

            for (j in 0 until n) {
                val lineIndex = f.edge(j).index
                if (state.lines[lineIndex] != LINE_UNKNOWN) continue
                var k = j + 1
                if (k >= n) k = 0

                if (mins[k][j] > clue) {
                    s.status = SolverStatus.MISTAKE
                    return DIFF_EASY
                }
                if (mins[k][j] == clue) {
                    solverSetLine(s, lineIndex, LINE_NO)
                    diff = min(diff, DIFF_EASY)
                }
                if (maxs[k][j] < clue - 1) {
                    s.status = SolverStatus.MISTAKE
                    return DIFF_EASY
                }
                if (maxs[k][j] == clue - 1) {
                    solverSetLine(s, lineIndex, LINE_YES)
                    diff = min(diff, DIFF_EASY)
                }

                if (s.diff >= DIFF_TRICKY) {
                    val nextEdge = f.edge(k)
                    if (state.lines[nextEdge.index] != LINE_UNKNOWN) continue
                    val dlineIndex = dlineIndexFromFace(f, k)
                    k++
                    if (k >= n) k = 0
                    if (mins[k][j] > clue - 2) {
                        if (setAtmostone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                    }
                    if (maxs[k][j] < clue) {
                        if (setAtleastone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                    }
                }
            }
        }

        if (diff < DIFF_NORMAL) return diff

        for (i in g.dots.indices) {
            if (s.dotSolved[i]) continue
            val d = g.dots[i]
            val n = d.order
            val yes = s.dotYesCount[i]
            val no = s.dotNoCount[i]
            val unknown = n - yes - no

            for (j in 0 until n) {
                var k = j + 1
                if (k >= n) k = 0
                val dlineIndex = dlineIndexFromDot(d, j)
                val line1Index = d.edges[j]!!.index
                val line2Index = d.edges[k]!!.index
                val line1 = state.lines[line1Index]
                val line2 = state.lines[line2Index]

                if (line1 == LINE_NO || line2 == LINE_NO) {
                    if (setAtmostone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                }
                if (line1 == LINE_YES || line2 == LINE_YES) {
                    if (setAtleastone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                }
                if (isAtmostone(dlines, dlineIndex)) {
                    if (line1 == LINE_YES && line2 == LINE_UNKNOWN) {
                        solverSetLine(s, line2Index, LINE_NO)
                        diff = min(diff, DIFF_EASY)
                    }
                    if (line2 == LINE_YES && line1 == LINE_UNKNOWN) {
                        solverSetLine(s, line1Index, LINE_NO)
                        diff = min(diff, DIFF_EASY)
                    }
                }
                if (isAtleastone(dlines, dlineIndex)) {
                    if (line1 == LINE_NO && line2 == LINE_UNKNOWN) {
                        solverSetLine(s, line2Index, LINE_YES)
                        diff = min(diff, DIFF_EASY)
                    }
                    if (line2 == LINE_NO && line1 == LINE_UNKNOWN) {
                        solverSetLine(s, line1Index, LINE_YES)
                        diff = min(diff, DIFF_EASY)
                    }
                }
                if (line1 != LINE_UNKNOWN || line2 != LINE_UNKNOWN) continue

                if (yes == 0 && unknown == 2) {
                    if (isAtmostone(dlines, dlineIndex)) {
                        solverSetLine(s, line1Index, LINE_NO)
                        solverSetLine(s, line2Index, LINE_NO)
                        diff = min(diff, DIFF_EASY)
                    }
                    if (isAtleastone(dlines, dlineIndex)) {
                        solverSetLine(s, line1Index, LINE_YES)
                        solverSetLine(s, line2Index, LINE_YES)
                        diff = min(diff, DIFF_EASY)
                    }
                }
                if (yes == 1) {
                    if (setAtmostone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                    if (unknown == 2) {
                        if (setAtleastone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                    }
                }

                if (s.diff >= DIFF_TRICKY) {
                    if (isAtleastone(dlines, dlineIndex)) {
                        for (opp in 0 until n) {
                            if (opp == j || opp == j + 1 || opp == j - 1) continue
                            if (j == 0 && opp == n - 1) continue
                            if (j == n - 1 && opp == 0) continue
                            if (setAtmostone(dlines, dlineIndexFromDot(d, opp))) {
                                diff = min(diff, DIFF_NORMAL)
                            }
                        }
                        if (yes == 0 && isAtmostone(dlines, dlineIndex)) {
                            if (unknown == 3) {
                                for (opp in 0 until n) {
                                    if (opp == j || opp == k) continue
                                    val oppIndex = d.edges[opp]!!.index
                                    if (state.lines[oppIndex] == LINE_UNKNOWN) {
                                        solverSetLine(s, oppIndex, LINE_YES)
                                        diff = min(diff, DIFF_EASY)
                                    }
                                }
                            } else if (unknown == 4) {
                                if (dlineSetOppAtleastone(s, d, j)) {
                                    diff = min(diff, DIFF_NORMAL)
                                }
                            }
                        }
                    }
                }
            }
        }
        return diff
    }

    /** `linedsf_deductions`, `loopy.c:2725-2853`. */
    internal fun linedsfDeductions(s: SolverState): Int {
        val state = s.state
        val g = state.grid
        val dlines = s.dlines!!
        var diff = DIFF_MAX

        for (i in g.faces.indices) {
            if (s.faceSolved[i]) continue
            val clue = state.clues[i]
            if (clue < 0) continue

            val n = g.faces[i].order
            var yes = s.faceYesCount[i]
            if (yes + 1 == clue) {
                if (faceSetAllIdentical(s, i, LINE_NO)) diff = min(diff, DIFF_EASY)
            }
            var no = s.faceNoCount[i]
            if (no + 1 == n - clue) {
                if (faceSetAllIdentical(s, i, LINE_YES)) diff = min(diff, DIFF_EASY)
            }

            yes = s.faceYesCount[i]
            val unknown = n - no - yes

            val diffTmp = parityDeductions(
                s,
                g.faces[i].edges,
                (clue - yes) % 2,
                unknown,
            )
            diff = min(diff, diffTmp)
        }

        for (i in g.dots.indices) {
            val d = g.dots[i]
            val n = d.order
            for (j in 0 until n) {
                val dlineIndex = dlineIndexFromDot(d, j)
                val line1Index = d.edges[j]!!.index
                if (state.lines[line1Index] != LINE_UNKNOWN) continue
                var j2 = j + 1
                if (j2 == n) j2 = 0
                val line2Index = d.edges[j2]!!.index
                if (state.lines[line2Index] != LINE_UNKNOWN) continue
                val can1 = s.lineDsf!!.canonifyFlip(line1Index)
                val can2 = s.lineDsf!!.canonifyFlip(line2Index)
                if (can1.root == can2.root && can1.inverse != can2.inverse) {
                    if (setAtmostone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                    if (setAtleastone(dlines, dlineIndex)) diff = min(diff, DIFF_NORMAL)
                    continue
                }
                if (isAtmostone(dlines, dlineIndex) && isAtleastone(dlines, dlineIndex)) {
                    if (mergeLines(s, line1Index, line2Index, true)) {
                        diff = min(diff, DIFF_HARD)
                    }
                }
            }

            val yes = s.dotYesCount[i]
            val no = s.dotNoCount[i]
            val unknown = n - yes - no
            val diffTmp = parityDeductions(s, d.edges, yes % 2, unknown)
            diff = min(diff, diffTmp)
        }

        for (i in g.edges.indices) {
            val can = s.lineDsf!!.canonifyFlip(i)
            if (can.root == i) continue
            var line = state.lines[can.root]
            if (line != LINE_UNKNOWN) {
                if (solverSetLine(s, i, if (can.inverse) opposite(line) else line)) {
                    diff = min(diff, DIFF_EASY)
                }
            } else {
                line = state.lines[i]
                if (line != LINE_UNKNOWN) {
                    if (solverSetLine(
                            s,
                            can.root,
                            if (can.inverse) opposite(line) else line,
                        )
                    ) {
                        diff = min(diff, DIFF_EASY)
                    }
                }
            }
        }
        return diff
    }

    /** `OPP`, `loopy.c:232-233`. */
    private fun opposite(line: Int): Int = 2 - line

    /** `loop_deductions`, `loopy.c:2855-3004`. */
    internal fun loopDeductions(s: SolverState): Int {
        var edgeCount = 0
        var clueCount = 0
        var satisfiedClues = 0
        var minusOneClues = 0
        val state = s.state
        val g = state.grid
        var shortestChainLen = g.dots.size
        var progress = false

        for (i in g.edges.indices) {
            if (state.lines[i] == LINE_YES) {
                mergeDots(s, i)
                edgeCount++
            }
        }

        for (i in g.faces.indices) {
            val c = state.clues[i]
            if (c >= 0) {
                val o = s.faceYesCount[i]
                if (o == c) {
                    satisfiedClues++
                } else if (o == c - 1) {
                    minusOneClues++
                }
                clueCount++
            }
        }

        for (i in g.dots.indices) {
            val dotsConnected = s.loopLen[s.dotDsf.canonify(i)]
            if (dotsConnected > 1) shortestChainLen = min(shortestChainLen, dotsConnected)
        }

        check(s.status == SolverStatus.INCOMPLETE) {
            "loop_deductions entered with status ${s.status}"
        }

        if (satisfiedClues == clueCount && shortestChainLen == edgeCount) {
            s.status = SolverStatus.SOLVED
            return DIFF_EASY
        }

        for (i in g.edges.indices) {
            val e = g.edges[i]
            if (state.lines[i] != LINE_UNKNOWN) continue
            val eqclass = s.dotDsf.canonify(e.dot1.index)
            if (eqclass != s.dotDsf.canonify(e.dot2.index)) continue

            var value = LINE_NO
            if (s.loopLen[eqclass] == edgeCount + 1) {
                var minusOneNearby = 0
                e.face1?.let { f ->
                    val c = state.clues[f.index]
                    if (c >= 0 && s.faceYesCount[f.index] == c - 1) minusOneNearby++
                }
                e.face2?.let { f ->
                    val c = state.clues[f.index]
                    if (c >= 0 && s.faceYesCount[f.index] == c - 1) minusOneNearby++
                }
                if (minusOneClues == minusOneNearby &&
                    minusOneClues + satisfiedClues == clueCount
                ) {
                    value = LINE_YES
                }
            }

            progress = solverSetLine(s, i, value)
            check(progress) { "loop_deductions found a settled edge" }
            if (value == LINE_YES) {
                s.status = SolverStatus.AMBIGUOUS
                return DIFF_EASY
            }
        }
        return if (progress) DIFF_EASY else DIFF_MAX
    }

    /**
     * `solve_game_rec`, `loopy.c:3008-3071`: the four stages with the
     * threshold gate, and the NO conversion after a success.
     */
    internal fun solveGameRec(start: SolverState): SolverState {
        val s = dupSolverState(start)
        val solverFns = arrayOf<(SolverState) -> Int>(
            { trivialDeductions(it) },
            { dlineDeductions(it) },
            { linedsfDeductions(it) },
            { loopDeductions(it) },
        )
        var i = 0
        var thresholdDiff = 0
        var thresholdIndex = 0

        while (i < NUM_SOLVERS) {
            if (s.status == SolverStatus.MISTAKE) return s
            if (s.status == SolverStatus.SOLVED || s.status == SolverStatus.AMBIGUOUS) break
            if ((SOLVER_DIFFS[i] >= thresholdDiff || i >= thresholdIndex) &&
                SOLVER_DIFFS[i] <= s.diff
            ) {
                val next = solverFns[i](s)
                if (next != DIFF_MAX) {
                    thresholdDiff = next
                    thresholdIndex = i
                    i = 0
                    continue
                }
            }
            i++
        }

        if (s.status == SolverStatus.SOLVED || s.status == SolverStatus.AMBIGUOUS) {
            arraySetAll(s.state.lines, LINE_UNKNOWN, LINE_NO, s.state.grid.edges.size)
        }
        return s
    }

    /**
     * `solve_game`, `loopy.c:3073-3096`: C receives `states[0]`, so the
     * player's lines never reach the solver. The seam passes the current
     * state, so the port rebuilds the initial lines first: the grid and
     * the clues never change, and a fresh line array is state 0. The
     * answer never sets an error and can be a partial board.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? {
        val initial = (state as State).copy() as State
        initial.lines.fill(LINE_UNKNOWN)
        initial.lineErrors.fill(false)
        initial.exactlyOneLoop = false
        initial.solved = false
        initial.cheated = false
        val start = newSolverState(initial, DIFF_MAX)
        val result = solveGameRec(start)
        return encodeSolveMove(result.state)
    }

    /** `encode_solve_move`, `loopy.c:856-892`. */
    internal fun encodeSolveMove(state: State): String {
        val out = StringBuilder("S")
        for (i in state.lines.indices) {
            when (state.lines[i]) {
                LINE_YES -> out.append(i).append('y')
                LINE_NO -> out.append(i).append('n')
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Text form, `loopy.c:1144-1227`.
    // ------------------------------------------------------------------

    /** `game_can_format_as_text_now`: true only for the square grid. */
    override fun canFormatAsTextNow(params: Game.Params): Boolean =
        (params as Params).type == 0

    /**
     * `game_text_format`, `loopy.c:1151-1227`: square grids only. The
     * midend gate keeps a non-square board out; a direct call with one
     * throws, because C asserts the square shape.
     */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val g = s.grid
        require(s.gridType == 0) { "the text form exists only for the square grid" }
        val first = g.faces[0]
        require(first.order == 4) { "the square grid has a four-sided first face" }
        val cellSize = abs(first.dot(0).x - first.dot(2).x)

        val w = (g.highestX - g.lowestX) / cellSize
        val h = (g.highestY - g.lowestY) / cellSize

        val width = 2 * w + 2
        val height = 2 * h + 1
        val canvas = CharArray(width * height) { ' ' }
        for (y in 0 until height) {
            canvas[y * width + width - 1] = '\n'
        }

        for (edge in g.edges) {
            val x1 = (edge.dot1.x - g.lowestX) / cellSize
            val x2 = (edge.dot2.x - g.lowestX) / cellSize
            val y1 = (edge.dot1.y - g.lowestY) / cellSize
            val y2 = (edge.dot2.y - g.lowestY) / cellSize
            val x = x1 + x2
            val y = y1 + y2
            when (s.lines[edge.index]) {
                LINE_YES -> canvas[y * width + x] = if (y1 == y2) '-' else '|'
                LINE_NO -> canvas[y * width + x] = 'x'
            }
        }

        for (face in g.faces) {
            require(face.order == 4) { "the square grid has four-sided faces" }
            val x1 = (face.dot(0).x - g.lowestX) / cellSize
            val x2 = (face.dot(2).x - g.lowestX) / cellSize
            val y1 = (face.dot(0).y - g.lowestY) / cellSize
            val y2 = (face.dot(2).y - g.lowestY) / cellSize
            val x = x1 + x2
            val y = y1 + y2
            canvas[y * width + x] = clueToChar(s.clues[face.index])
        }
        return String(canvas)
    }

    // ------------------------------------------------------------------
    // Moves, `loopy.c:3102-3326`.
    // ------------------------------------------------------------------

    /**
     * `interpret_move`. A cursor move walks in grid units to the next
     * edge; a select and a mouse press map to one `%d%c` and then run
     * the optional auto-follow walk. There is no drag.
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
        val g = s.grid

        if (Buttons.isCursorMove(button)) {
            var dx = 0
            var dy = 0
            when (button) {
                Buttons.CURSOR_UP -> dy = -1
                Buttons.CURSOR_DOWN -> dy = 1
                Buttons.CURSOR_RIGHT -> dx = 1
                Buttons.CURSOR_LEFT -> dx = -1
            }
            var cursorX = u.curX
            var cursorY = u.curY
            val e = Grid.nearestEdge(g, u.curX, u.curY)
            var newEdge = e
            while (newEdge === e || newEdge == null) {
                cursorX += dx
                cursorY += dy
                if (cursorX < g.lowestX || cursorX > g.highestX) break
                if (cursorY < g.lowestY || cursorY > g.highestY) break
                newEdge = Grid.nearestEdge(g, cursorX, cursorY)
                if (newEdge !== e && newEdge != null) {
                    u.curX = cursorX
                    u.curY = cursorY
                    break
                }
            }
            u.curVisible = true
            return MoveResult.UiUpdate
        }

        var edge: GridEdge
        var buttonChar: Char

        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            edge = Grid.nearestEdge(g, u.curX, u.curY) ?: return MoveResult.Unused
            val oldState = s.lines[edge.index]
            buttonChar = if (button == Buttons.CURSOR_SELECT2) {
                if (oldState == LINE_UNKNOWN) 'n' else 'u'
            } else {
                if (oldState == LINE_UNKNOWN) 'y' else 'u'
            }
            return makeMove(s, u, edge, buttonChar)
        }

        val stripped = Buttons.stripModifiers(button)
        var gridX = x - border(tileSize)
        var gridY = y - border(tileSize)
        gridX = gridX * g.tileSize / tileSize
        gridY = gridY * g.tileSize / tileSize
        gridX += g.lowestX
        gridY += g.lowestY

        edge = Grid.nearestEdge(g, gridX, gridY) ?: return MoveResult.Unused
        val oldState = s.lines[edge.index]
        buttonChar = when (stripped) {
            Buttons.LEFT_BUTTON -> when (oldState) {
                // STYLUS_BASED falls through from YES into NO.
                LINE_UNKNOWN -> 'y'
                LINE_YES -> 'n'
                else -> 'u'
            }
            Buttons.MIDDLE_BUTTON -> 'u'
            Buttons.RIGHT_BUTTON -> when (oldState) {
                // STYLUS_BASED falls through from NO into YES.
                LINE_UNKNOWN -> 'n'
                LINE_NO -> 'y'
                else -> 'u'
            }
            else -> return MoveResult.Unused
        }
        return makeMove(s, u, edge, buttonChar)
    }

    /**
     * `makemove` with the auto-follow walk, `loopy.c:3220-3282`. The
     * candidate test reads the state of the originally clicked edge
     * `i`, not of `e_this`; that is upstream's behaviour and it changes
     * the move text, so it stays.
     */
    private fun makeMove(
        state: State,
        ui: Ui,
        edge: GridEdge,
        buttonChar: Char,
    ): MoveResult {
        val g = state.grid
        val clicked = edge.index
        val out = StringBuilder()
        out.append(clicked).append(buttonChar)

        if (ui.autoFollow != AutoFollow.OFF) {
            for (dotId in 0..1) {
                var dot = if (dotId == 0) edge.dot1 else edge.dot2
                var eThis = edge
                while (true) {
                    var found = 0
                    var eNext: GridEdge? = null
                    for (j in 0 until dot.order) {
                        val candidate = dot.edges[j]!!
                        val candidateIndex = candidate.index
                        if (candidate !== eThis &&
                            (ui.autoFollow == AutoFollow.FIXED ||
                                state.lines[clicked] == LINE_NO ||
                                state.lines[candidateIndex] != LINE_NO)
                        ) {
                            eNext = candidate
                            found++
                        }
                    }
                    if (found != 1) break
                    val next = eNext!!
                    if (state.lines[next.index] != state.lines[clicked]) break
                    /*
                     * C jumps to `autofollow_done`, and that label is
                     * inside the `for` body (`loopy.c:3266`, `:3278`),
                     * so the jump ends only this direction's walk. The
                     * next direction still runs and emits the same loop
                     * in reverse, and those duplicate pairs are part of
                     * the saved move. Break only the inner loop.
                     */
                    if (next === edge) break
                    dot = if (next.dot1 !== dot) next.dot1 else next.dot2
                    eThis = next
                    out.append(next.index).append(buttonChar)
                }
            }
        }
        return MoveResult.Move(out.toString())
    }

    /**
     * `execute_move`, `loopy.c:3285-3326`. The `%d[ynu]` grammar accepts
     * an optional leading `S`, an empty move, and leading zeros; a sign
     * or a space is rejected because the C digit scan does not skip it.
     * An overflowing `atoi` saturates in this port, so it is out of
     * range and rejected.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val newState = state.copy() as State
        var position = 0
        if (move.isNotEmpty() && move[0] == 'S') {
            position++
            newState.cheated = true
        }
        while (position < move.length) {
            val i = atoi(move, position)
            if (i < 0 || i >= newState.numEdges) return null
            while (position < move.length && move[position] in '0'..'9') position++
            if (position >= move.length) return null
            val c = move[position]
            position++
            when (c) {
                'y' -> newState.lines[i] = LINE_YES
                'n' -> newState.lines[i] = LINE_NO
                'u' -> newState.lines[i] = LINE_UNKNOWN
                else -> return null
            }
        }
        if (checkCompletion(newState)) newState.solved = true
        return newState
    }

    // ------------------------------------------------------------------
    // UI, keys, and preferences, `loopy.c:894-1007`.
    // ------------------------------------------------------------------

    /** `new_ui`, `loopy.c:946-960`: centred cursor, faint lines on. */
    override fun newUi(state: Game.State): Game.Ui {
        val s = state as State
        val ui = Ui()
        ui.curX = (s.grid.lowestX + s.grid.highestX) / 2
        ui.curY = (s.grid.lowestY + s.grid.highestY) / 2
        ui.curVisible = false
        ui.drawFaintLines = true
        ui.autoFollow = AutoFollow.OFF
        return ui
    }

    /** `android_cursor_visibility`, `loopy.c:998-1001`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `game_changed_state`, `loopy.c:1003-1007`. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val n = newState as State
        return n.solved && !n.cheated && oldState != null && !(oldState as State).solved
    }

    /**
     * `game_request_keys` is NULL, so the midend presets the arrow mode
     * and no labels are returned. The full-cursor hint forces
     * `ANDROID_ARROWS_ONLY`, as for every game without the callback.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `game_status`, `loopy.c:3789-3792`: sticky, solved is 1. */
    override fun status(state: Game.State): Int =
        if ((state as State).solved) 1 else 0

    /** `wants_statusbar` is false, so the game writes no status text. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // Geometry and palette, `loopy.c:1009-1090`.
    // ------------------------------------------------------------------

    private fun border(tileSize: Int): Int = tileSize / 2

    /**
     * `game_compute_size`, `loopy.c:1009-1023`. The UI is ignored, so
     * the two-argument seam form is the C function. The trailing `+ 1`
     * is upstream's one spare pixel.
     */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val size = Grid.computeSize(kinds[p.type].type, p.w, p.h)
        val renderedWidth = size.xextent * tileSize / size.tileSize
        val renderedHeight = size.yextent * tileSize / size.tileSize
        return Size(
            renderedWidth + 2 * border(tileSize) + 1,
            renderedHeight + 2 * border(tileSize) + 1,
        )
    }

    /**
     * One unit per edge, the play unit of every Loopy grid, and one per
     * face that carries a clue. The square grid names an edge by the row
     * and column of its two endpoints, which is the grid the game draws
     * with one cell per unit. The other seventeen grids have no row and
     * column, so an edge names the one or two cells it separates and a
     * clue names its cell; both are the face indices the game keeps, so
     * a player can join a clue to the edges around it.
     *
     * The rectangle is the edge's bounding box with a quarter tile of
     * slack, the place `edge_bbox` repaints, and a clue is a square
     * around the incentre that `face_text_pos` uses.
     *
     * A left press turns an edge from no line to line to no line again,
     * and a right press cycles no line, cross, no line, so the two
     * buttons are the unit actions in the words of the three states.
     * A clue face has no pointer move at all and is read only.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val g = s.grid
        val t = tileSize
        val margin = t / 4
        val square = g.type == GridType.SQUARE

        fun screenX(x: Int): Int = (x - g.lowestX) * t / g.tileSize + border(t)
        fun screenY(y: Int): Int = (y - g.lowestY) * t / g.tileSize + border(t)

        fun pointName(dot: GridDot): String =
            "row ${(dot.y - g.lowestY) / g.tileSize + 1}, " +
                "column ${(dot.x - g.lowestX) / g.tileSize + 1}"

        fun edgeName(edge: GridEdge): String {
            if (square) {
                return "edge from ${pointName(edge.dot1)} to ${pointName(edge.dot2)}"
            }
            val first = edge.face1?.index
            val second = edge.face2?.index
            return when {
                first != null && second != null ->
                    "edge between cell ${first + 1} and cell ${second + 1}"

                first != null -> "edge on cell ${first + 1}"
                second != null -> "edge on cell ${second + 1}"
                else -> "edge"
            }
        }

        val cursorEdge = if (u.curVisible) Grid.nearestEdge(g, u.curX, u.curY) else null

        val edges = g.edges.map { edge ->
            val x1 = screenX(edge.dot1.x)
            val y1 = screenY(edge.dot1.y)
            val x2 = screenX(edge.dot2.x)
            val y2 = screenY(edge.dot2.y)
            val line = s.lines[edge.index]
            val content = when (line) {
                LINE_YES -> "line"
                LINE_NO -> "crossed out"
                else -> "no line"
            }
            val cursor = if (edge === cursorEdge) ", cursor" else ""
            val wrong = if (s.lineErrors[edge.index]) SPOKEN_WRONG else ""
            SpokenUnit(
                row = 0,
                column = 0,
                left = minOf(x1, x2) - margin,
                top = minOf(y1, y2) - margin,
                width = kotlin.math.abs(x2 - x1) + 2 * margin,
                height = kotlin.math.abs(y2 - y1) + 2 * margin,
                text = edgeName(edge) + ", " + content + cursor + wrong,
                actions = edgeButtons(line),
            )
        }

        val clues = buildList {
            for (face in g.faces) {
                val clue = s.clues[face.index]
                if (clue < 0) continue
                Grid.findIncentre(face)
                val centre = face.incentre!!
                val cx = screenX(centre.x)
                val cy = screenY(centre.y)
                val yes = faceOrder(s, face.index, LINE_YES)
                val no = if (s.exactlyOneLoop) {
                    face.order - yes
                } else {
                    faceOrder(s, face.index, LINE_NO)
                }
                val wrong = if (yes > clue || no > face.order - clue) SPOKEN_WRONG else ""
                val where = if (square) {
                    "at row ${(centre.y - g.lowestY) / g.tileSize + 1}, " +
                        "column ${(centre.x - g.lowestX) / g.tileSize + 1}"
                } else {
                    "at cell ${face.index + 1}"
                }
                add(
                    SpokenUnit(
                        row = 0,
                        column = 0,
                        left = cx - t / 3,
                        top = cy - t / 3,
                        width = 2 * t / 3 + 1,
                        height = 2 * t / 3 + 1,
                        text = "clue $clue $where$wrong",
                    ),
                )
            }
        }

        val board = SpokenBoard(0, 0, "", edges + clues)
        val cursor = cursorEdge?.let { edges.getOrNull(it.index) }
        return board.cappedUnits(cursor)
    }

    /**
     * The pointer buttons of an edge, the three-state cycle of
     * `interpret_move`: the left button goes no line to line to crossed
     * out, and the right button goes no line to crossed out to line.
     */
    private fun edgeButtons(line: Int): List<SpokenAction> = when (line) {
        LINE_YES -> listOf(
            SpokenAction("Cross", Buttons.LEFT_BUTTON),
            SpokenAction("Clear", Buttons.RIGHT_BUTTON),
        )

        LINE_NO -> listOf(
            SpokenAction("Clear", Buttons.LEFT_BUTTON),
            SpokenAction("Line", Buttons.RIGHT_BUTTON),
        )

        else -> listOf(
            SpokenAction("Line", Buttons.LEFT_BUTTON),
            SpokenAction("Cross", Buttons.RIGHT_BUTTON),
        )
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`, `loopy.c:1044-1090`. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(3 * NCOLOURS)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) ret[COL_FOREGROUND * 3 + i] = 0.0f
        for (i in 0 until 3) {
            ret[COL_LINEUNKNOWN * 3 + i] = if (i == 2) 0.0f else ret[COL_BACKGROUND * 3 + i] * 0.9f
        }
        for (i in 0 until 3) ret[COL_HIGHLIGHT * 3 + i] = 1.0f
        ret[COL_MISTAKE * 3 + 0] = 1.0f
        ret[COL_MISTAKE * 3 + 1] = 0.0f
        ret[COL_MISTAKE * 3 + 2] = 0.0f
        ret[COL_CURSOR * 3 + 0] = 0.5f
        ret[COL_CURSOR * 3 + 1] = 0.5f
        ret[COL_CURSOR * 3 + 2] = 1.0f
        for (i in 0 until 3) ret[COL_SATISFIED * 3 + i] = 0.0f
        for (i in 0 until 3) ret[COL_FAINT * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.9f
        return ret
    }

    // ------------------------------------------------------------------
    // Drawing, `loopy.c:3328-3768`.
    // ------------------------------------------------------------------

    private fun blitterHalfSize(tileSize: Int): Int = tileSize / 8 + 1

    private fun blitterSize(tileSize: Int): Int = 2 * blitterHalfSize(tileSize) + 1

    /**
     * `game_new_drawstate` plus `game_set_size`: the drawstate allocates
     * its one blitter before the first `start_draw`, as C does.
     */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val ds = DrawState(s.numFaces, s.numEdges)
        ds.tileSize = tileSize
        val size = blitterSize(tileSize)
        ds.curBl = sink.blitterNew(size, size)
        return ds
    }

    /** `game_free_drawstate`, `loopy.c:1124-1136`. */
    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        val ds = drawState as DrawState
        ds.curBl?.let { sink.blitterFree(it) }
        ds.curBl = null
    }

    /** A rectangle, filled in place so a redraw does not allocate per object. */
    private class Box {
        var x: Int = 0
        var y: Int = 0
        var w: Int = 0
        var h: Int = 0
        val first: Coords = Coords()
        val second: Coords = Coords()
    }

    /** Two screen coordinates, filled in place for the same reason. */
    private class Coords {
        var x: Int = 0
        var y: Int = 0
    }

    /** `grid_to_screen`, `loopy.c:3333-3342`. */
    private fun gridToScreen(ds: DrawState, g: Grid, gridX: Int, gridY: Int, out: Coords) {
        var x = gridX - g.lowestX
        var y = gridY - g.lowestY
        x = x * ds.tileSize / g.tileSize
        y = y * ds.tileSize / g.tileSize
        out.x = x + border(ds.tileSize)
        out.y = y + border(ds.tileSize)
    }

    /** `face_text_pos`, `loopy.c:3346-3371`, with the C cache. */
    private fun faceTextPos(ds: DrawState, g: Grid, face: GridFace, out: Coords) {
        val index = face.index
        if (ds.textX[index] >= 0) {
            out.x = ds.textX[index]
            out.y = ds.textY[index]
            return
        }
        Grid.findIncentre(face)
        val centre = face.incentre!!
        gridToScreen(ds, g, centre.x, centre.y, out)
        ds.textX[index] = out.x
        ds.textY[index] = out.y
    }

    /** `face_text_bbox`, `loopy.c:3373-3386`. */
    private fun faceTextBbox(ds: DrawState, g: Grid, face: GridFace, out: Box) {
        faceTextPos(ds, g, face, out.first)
        out.x = out.first.x - ds.tileSize * 5 / 4 - 1
        out.y = out.first.y - ds.tileSize / 4 - 3
        out.w = ds.tileSize * 5 / 2 + 2
        out.h = ds.tileSize / 2 + 5
    }

    /** `edge_bbox`, `loopy.c:3406-3427`. */
    private fun edgeBbox(ds: DrawState, g: Grid, edge: GridEdge, out: Box) {
        gridToScreen(ds, g, edge.dot1.x, edge.dot1.y, out.first)
        gridToScreen(ds, g, edge.dot2.x, edge.dot2.y, out.second)
        val margin = (ds.tileSize + 15) / 16
        val xmin = min(out.first.x, out.second.x) - margin
        val xmax = max(out.first.x, out.second.x) + margin
        val ymin = min(out.first.y, out.second.y) - margin
        val ymax = max(out.first.y, out.second.y) + margin
        out.x = xmin
        out.y = ymin
        out.w = xmax - xmin + 1
        out.h = ymax - ymin + 1
    }

    /** `dot_bbox`, `loopy.c:3429-3446`. */
    private fun dotBbox(ds: DrawState, g: Grid, dot: GridDot, out: Box) {
        gridToScreen(ds, g, dot.x, dot.y, out.first)
        val radius = (ds.tileSize * 5 + 63) / 64
        out.x = out.first.x - radius
        out.y = out.first.y - radius
        out.w = 2 * radius + 1
        out.h = 2 * radius + 1
    }

    /** `boxes_intersect`, `loopy.c:3504-3513`. */
    private fun boxesIntersect(
        x0: Int,
        y0: Int,
        w0: Int,
        h0: Int,
        x1: Int,
        y1: Int,
        w1: Int,
        h1: Int,
    ): Boolean = x0 < x1 + w1 && x1 < x0 + w0 && y0 < y1 + h1 && y1 < y0 + h0

    /** `game_redraw_clue`, `loopy.c:3388-3404`. */
    private fun drawClue(sink: DrawSink, ds: DrawState, state: State, face: Int, coords: Coords) {
        val g = state.grid
        faceTextPos(ds, g, g.faces[face], coords)
        val colour = when {
            ds.clueError[face] -> COL_MISTAKE
            ds.clueSatisfied[face] -> COL_SATISFIED
            else -> COL_FOREGROUND
        }
        sink.text(
            coords.x,
            coords.y,
            Text.FONT_VARIABLE,
            ds.tileSize / 2,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            colour,
            state.clues[face].toString(),
        )
    }

    /** `game_redraw_line`, `loopy.c:3453-3490`. */
    private fun drawLine(
        sink: DrawSink,
        ds: DrawState,
        ui: Ui,
        state: State,
        edge: Int,
        phase: Int,
        first: Coords,
        second: Coords,
    ) {
        val g = state.grid
        val e = g.edges[edge]
        val colour = when {
            state.lineErrors[edge] -> COL_MISTAKE
            state.lines[edge] == LINE_UNKNOWN -> COL_LINEUNKNOWN
            state.lines[edge] == LINE_NO -> COL_FAINT
            ds.flashing -> COL_HIGHLIGHT
            else -> COL_FOREGROUND
        }
        if (colour != REDRAW_PHASES[phase]) return

        gridToScreen(ds, g, e.dot1.x, e.dot1.y, first)
        gridToScreen(ds, g, e.dot2.x, e.dot2.y, second)
        if (colour == COL_FAINT) {
            if (ui.drawFaintLines) {
                // C divides by a double and passes the float to
                // draw_thick_line, whose sink clamps below one pixel.
                sink.thickLine(
                    (first.x + 0.5).toFloat(),
                    (first.y + 0.5).toFloat(),
                    (second.x + 0.5).toFloat(),
                    (second.y + 0.5).toFloat(),
                    (ds.tileSize / 24.0).toFloat(),
                    colour,
                )
            }
        } else {
            sink.thickLine(
                (first.x + 0.5).toFloat(),
                (first.y + 0.5).toFloat(),
                (second.x + 0.5).toFloat(),
                (second.y + 0.5).toFloat(),
                (ds.tileSize * 3 / 32.0).toFloat(),
                colour,
            )
        }
    }

    /**
     * `game_redraw_dot`, `loopy.c:3492-3502`. C computes a cursor colour
     * and never uses it; the circle is always the foreground.
     */
    private fun drawDot(sink: DrawSink, ds: DrawState, state: State, dot: Int, coords: Coords) {
        val d = state.grid.dots[dot]
        gridToScreen(ds, state.grid, d.x, d.y, coords)
        val radius = (ds.tileSize * 2.5 / 32.0).toInt()
        sink.circle(coords.x, coords.y, radius, COL_FOREGROUND, COL_FOREGROUND)
    }

    /** `game_redraw_in_rect`, `loopy.c:3515-3555`. */
    private fun redrawInRect(
        sink: DrawSink,
        ds: DrawState,
        ui: Ui,
        state: State,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val g = state.grid
        // C reads ds->cur_edge here, which the previous redraw stored.
        val cur1 = ds.curEdge?.dot1?.index ?: -1
        val cur2 = ds.curEdge?.dot2?.index ?: -1
        val box = Box()

        sink.clip(x, y, w, h)
        sink.rect(x, y, w, h, COL_BACKGROUND)

        for (i in g.faces.indices) {
            if (state.clues[i] < 0) continue
            faceTextBbox(ds, g, g.faces[i], box)
            if (boxesIntersect(x, y, w, h, box.x, box.y, box.w, box.h)) {
                drawClue(sink, ds, state, i, box.first)
            }
        }

        for (phase in REDRAW_PHASES.indices) {
            for (i in g.edges.indices) {
                edgeBbox(ds, g, g.edges[i], box)
                if (boxesIntersect(x, y, w, h, box.x, box.y, box.w, box.h)) {
                    drawLine(sink, ds, ui, state, i, phase, box.first, box.second)
                }
            }
        }

        for (i in g.dots.indices) {
            dotBbox(ds, g, g.dots[i], box)
            if (boxesIntersect(x, y, w, h, box.x, box.y, box.w, box.h)) {
                drawDot(sink, ds, state, i, box.first)
            }
        }

        sink.unclip()
        sink.update(x, y, w, h)
    }

    /**
     * `game_redraw`, `loopy.c:3557-3768`. The seam has no flash time,
     * so [DrawState.flashing] never turns on; the field and the
     * `flash_changed` rule stay for the trace contract.
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
        val g = s.grid
        val border = border(ds.tileSize)
        val bsz = blitterSize(ds.tileSize)

        if (ds.curVisible) {
            val bl = checkNotNull(ds.curBl) { "the cursor blitter is missing" }
            sink.blitterLoad(bl, ds.curBlX, ds.curBlY)
            sink.update(ds.curBlX, ds.curBlY, bsz, bsz)
        }

        val curEdge = if (u.curVisible) Grid.nearestEdge(g, u.curX, u.curY) else null

        var redrawEverything = !ds.started
        val dirtyFaces = IntArray(REDRAW_OBJECTS_LIMIT)
        var dirtyFaceCount = 0
        val dirtyEdges = IntArray(REDRAW_OBJECTS_LIMIT)
        var dirtyEdgeCount = 0

        for (i in g.faces.indices) {
            val f = g.faces[i]
            val sides = f.order
            val clue = s.clues[i]
            if (clue < 0) continue

            val yesOrder = faceOrder(s, i, LINE_YES)
            val noOrder = if (s.exactlyOneLoop) {
                sides - yesOrder
            } else {
                faceOrder(s, i, LINE_NO)
            }
            val clueMistake = yesOrder > clue || noOrder > sides - clue
            val clueSatisfied = yesOrder == clue && noOrder == sides - clue

            if (clueMistake != ds.clueError[i] || clueSatisfied != ds.clueSatisfied[i]) {
                ds.clueError[i] = clueMistake
                ds.clueSatisfied[i] = clueSatisfied
                if (dirtyFaceCount == REDRAW_OBJECTS_LIMIT) {
                    redrawEverything = true
                } else {
                    dirtyFaces[dirtyFaceCount++] = i
                }
            }
        }

        // No flash time reaches the seam, so this is the C "else" branch
        // with a zero flashtime.
        val flashChanged = ds.flashing
        ds.flashing = false

        for (i in g.edges.indices) {
            val newDs = if (s.lineErrors[i]) DS_LINE_ERROR else s.lines[i]
            if (newDs != ds.lines[i] || (flashChanged && s.lines[i] == LINE_YES)) {
                ds.lines[i] = newDs
                if (dirtyEdgeCount == REDRAW_OBJECTS_LIMIT) {
                    redrawEverything = true
                } else {
                    dirtyEdges[dirtyEdgeCount++] = i
                }
            }
        }

        if (redrawEverything) {
            // C uses the dot bounds here, not grid_compute_size.
            val gridWidth = g.highestX - g.lowestX
            val gridHeight = g.highestY - g.lowestY
            val w = gridWidth * ds.tileSize / g.tileSize
            val h = gridHeight * ds.tileSize / g.tileSize
            redrawInRect(sink, ds, u, s, 0, 0, w + 2 * border + 1, h + 2 * border + 1)
        } else {
            val box = Box()
            for (i in 0 until dirtyFaceCount) {
                faceTextBbox(ds, g, g.faces[dirtyFaces[i]], box)
                redrawInRect(sink, ds, u, s, box.x, box.y, box.w, box.h)
            }
            for (i in 0 until dirtyEdgeCount) {
                edgeBbox(ds, g, g.edges[dirtyEdges[i]], box)
                redrawInRect(sink, ds, u, s, box.x, box.y, box.w, box.h)
            }
        }

        ds.started = true

        if (u.curVisible) {
            val coords = Coords()
            gridToScreen(ds, g, u.curX, u.curY, coords)
            val hsz = blitterHalfSize(ds.tileSize)
            ds.curBlX = coords.x - hsz
            ds.curBlY = coords.y - hsz
            val bl = checkNotNull(ds.curBl) { "the cursor blitter is missing" }
            sink.blitterSave(bl, ds.curBlX, ds.curBlY)
            sink.rect(ds.curBlX + 1, coords.y - 1, bsz - 2, 3, COL_CURSOR)
            sink.rect(coords.x - 1, ds.curBlY + 1, 3, bsz - 2, COL_CURSOR)
            sink.update(ds.curBlX, ds.curBlY, bsz, bsz)
        }

        ds.curEdge = curEdge
        ds.curVisible = u.curVisible
    }

    // ------------------------------------------------------------------
    // C helpers.
    // ------------------------------------------------------------------

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /**
     * C `atoi` from [start]: leading C white space, an optional sign,
     * then a digit run. The result saturates at the `Int` bounds on
     * overflow. C leaves an overflow undefined; saturation is the port's
     * defined result, and every caller rejects an out-of-range value.
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
            val digit = (text[i] - '0').toLong()
            if (!overflow) {
                if (value > (Int.MAX_VALUE.toLong() + 1L - digit) / 10L) {
                    overflow = true
                } else {
                    value = value * 10L + digit
                }
            }
            i++
        }

        if (overflow) return if (negative) Int.MIN_VALUE else Int.MAX_VALUE
        val signed = if (negative) -value else value
        return signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
