package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.max
import kotlin.math.min

/**
 * The `solo` game (Sudoku), a port of `app/src/main/jni/solo.c`.
 *
 * The board is a `cr x cr` Latin square with `cr = c * r` symbols.
 * A standard board splits into `c` columns and `r` rows of sub-blocks;
 * a jigsaw board (`r == 1`) uses an arbitrary partition built by
 * `divvy_rectangle`; a killer board adds cages with digit sums; an
 * `x` board adds both main diagonals as regions.
 *
 * The generator builds a solved grid with a randomized backtracking
 * fill, keeps the requested symmetry classes and removes clues until
 * the deduction solver grades the puzzle at the requested difficulty.
 * The solver is used by generation, by the stored `AUXINFO` answer,
 * and by `Solve`, and it is a range of techniques enabled in a fixed
 * order by the difficulty level.
 *
 * The `AUXINFO` written by `new_game_desc` is the solved grid as an
 * `S` move, exactly as C writes it. `solve_game` returns that answer
 * unchanged when the save carries one; without it, the port runs the
 * solver on the initial clue grid, because C's `midend_solve` passes
 * `states[0]` and `solve_game` never reads the current board.
 *
 * The move grammar is `P<x>,<y>,<n>` for a pencil mark, `R<x>,<y>,<n>`
 * for a cell entry (also the clear form with `n == 0`), `M` for "mark
 * all", and `S<d,d,...>` for a solved board.
 *
 * A hand-made parameter set can leave the decoded ranges. `r <= 0`
 * makes C abort at `encode_solve_move` or in the allocation, a
 * difficulty or symmetry outside the decoded range can loop, and a
 * killer description with an unclued cage aborts the C solver and
 * drawing. The port defines those results; see D-024.
 *
 * Generation cost follows the difficulty. D-034 refuses a symbol count
 * above the measured band before the first draw: 25 symbols for Block,
 * Simple, and Intersect, 16 for Set and Extreme, and 9 for Recursive.
 */
object Solo : Game {

    // Palette, `solo.c:118-131`.
    private const val COL_BACKGROUND = 0
    private const val COL_XDIAGONALS = 1
    private const val COL_GRID = 2
    private const val COL_GRID_BLOCK = 3
    private const val COL_CLUE = 4
    private const val COL_USER = 5
    private const val COL_HIGHLIGHT = 6
    private const val COL_ERROR = 7
    private const val COL_PENCIL = 8
    private const val COL_KILLER = 9
    private const val NCOLOURS = 10

    // Symmetries, `solo.c:101`.
    private const val SYMM_NONE = 0
    private const val SYMM_ROT2 = 1
    private const val SYMM_ROT4 = 2
    private const val SYMM_REF2 = 3
    private const val SYMM_REF2D = 4
    private const val SYMM_REF4 = 5
    private const val SYMM_REF4D = 6
    private const val SYMM_REF8 = 7

    // Difficulties, `solo.c:104-110`.
    private const val DIFF_BLOCK = 0
    private const val DIFF_SIMPLE = 1
    private const val DIFF_INTERSECT = 2
    private const val DIFF_SET = 3
    private const val DIFF_EXTREME = 4
    private const val DIFF_RECURSIVE = 5
    private const val DIFF_AMBIGUOUS = 6
    private const val DIFF_IMPOSSIBLE = 7

    // Killer difficulties, `solo.c:112`.
    private const val DIFF_KSINGLE = 0
    private const val DIFF_KMINMAX = 1
    private const val DIFF_KSUMS = 2
    private const val DIFF_KINTERSECT = 3

    /** `ORDER_MAX`, `solo.c:93`. */
    private const val ORDER_MAX = 255

    /** D-034: the generation guard text and the three measured bands. */
    internal const val SYMBOLS_TOO_LARGE =
        "Board size and difficulty are too large to generate in this build"
    internal const val MAX_SYMBOLS_SIMPLE = 25
    internal const val MAX_SYMBOLS_SET = 16
    internal const val MAX_SYMBOLS_RECURSIVE = 9

    /**
     * The symbol cap for [difficulty]. Recursive is 9 symbols (3x3 is
     * 0.45 s on the host and 4x4 does not finish); Set and Extreme are
     * 16 (4x4 is 0.73 s and 5x5 does not finish); the rest are 25
     * (5x5 is 0.54 s on the host and 1.24 s on the phone, 6x5 does not
     * finish).
     */
    internal fun maxSymbols(difficulty: Int): Int = when (difficulty) {
        DIFF_RECURSIVE -> MAX_SYMBOLS_RECURSIVE
        DIFF_SET, DIFF_EXTREME -> MAX_SYMBOLS_SET
        else -> MAX_SYMBOLS_SIMPLE
    }

    /**
     * The generation guard of D-034, before any allocation or draw.
     * It is internal so a test can pin every band at its boundary
     * without generating a board of that size.
     */
    internal fun requireGeneratableSize(c: Int, r: Int, difficulty: Int) {
        require(c.toLong() * r <= maxSymbols(difficulty)) {
            SYMBOLS_TOO_LARGE
        }
    }

    /** `PREFERRED_TILE_SIZE`, `solo.c:95`. */
    private const val PREFERRED_TILE_SIZE = 48

    /** `MAX_2SUMS`, `MAX_3SUMS`, `MAX_4SUMS`, `solo.c:135-137`. */
    private const val MAX_2SUMS = 5
    private const val MAX_3SUMS = 8
    private const val MAX_4SUMS = 12

    private val sumBits2 = Array(18) { LongArray(MAX_2SUMS) }
    private val sumBits3 = Array(25) { LongArray(MAX_3SUMS) }
    private val sumBits4 = Array(31) { LongArray(MAX_4SUMS) }

    init {
        precomputeSumBits()
    }

    // ------------------------------------------------------------------
    // Parameters, `solo.c:227-535`.
    // ------------------------------------------------------------------

    /**
     * `struct game_params`, `solo.c:236-245`. `kdiff` is kept for the
     * killer solver; a parameter string never sets it, so `decodeParams`
     * leaves the default `DIFF_KINTERSECT`, as C's `default_params`
     * does. The non-killer presets store `DIFF_KMINMAX`, which has no
     * effect without killer cages.
     */
    data class Params(
        val c: Int,
        val r: Int,
        val symm: Int,
        val diff: Int,
        val xtype: Boolean,
        val killer: Boolean,
        val kdiff: Int = DIFF_KINTERSECT,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(c, r, symm, diff, xtype, killer, kdiff)
    }

    override val name: String = "Solo"
    override val backendName: String = "solo"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true
    override val canFormatAsText: Boolean = true
    override val isTimed: Boolean = false
    override val flags: Int = Game.REQUIRE_RBUTTON or Game.REQUIRE_NUMPAD

    private val presetTable = listOf(
        Preset("2x2 Trivial", "2x2"),
        Preset("2x3 Basic", "2x3db"),
        Preset("3x3 Trivial", "3x3"),
        Preset("3x3 Basic", "3x3db"),
        Preset("3x3 Basic X", "3x3xdb"),
        Preset("3x3 Intermediate", "3x3di"),
        Preset("3x3 Advanced", "3x3da"),
        Preset("3x3 Advanced X", "3x3xda"),
        Preset("3x3 Extreme", "3x3de"),
        Preset("3x3 Unreasonable", "3x3du"),
        Preset("3x3 Killer", "3x3ka"),
        Preset("9 Jigsaw Basic", "9jdb"),
        Preset("9 Jigsaw Basic X", "9jxdb"),
        Preset("9 Jigsaw Advanced", "9jda"),
        Preset("3x4 Basic", "3x4db"),
        Preset("4x4 Basic", "4x4db"),
    )

    override fun defaultParams(): Game.Params =
        Params(3, 3, SYMM_ROT2, DIFF_BLOCK, xtype = false, killer = false)

    override fun presets(): List<Preset> = presetTable

    /**
     * `decode_params`, `solo.c:345-412`. The initial `atoi` and the
     * following digit skip both start at the first character, so a
     * leading space or sign is not skipped consistently. Every
     * unrecognized character is eaten one at a time.
     */
    override fun decodeParams(text: String): Game.Params {
        var c = atoi(text, 0)
        var r = c
        var symm = SYMM_ROT2
        var diff = DIFF_BLOCK
        var xtype = false
        var killer = false
        var seenR = false
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            r = atoi(text, position)
            seenR = true
            while (position < text.length && text[position] in '0'..'9') position++
        }
        while (position < text.length) {
            when (text[position]) {
                'j' -> {
                    position++
                    if (seenR) c *= r
                    r = 1
                }

                'x' -> {
                    position++
                    xtype = true
                }

                'k' -> {
                    position++
                    killer = true
                }

                'r', 'm', 'a' -> {
                    val sc = text[position]
                    position++
                    var sd = false
                    if (sc == 'm' && position < text.length && text[position] == 'd') {
                        sd = true
                        position++
                    }
                    val sn = atoi(text, position)
                    while (position < text.length && text[position] in '0'..'9') position++
                    if (sc == 'm' && sn == 8) symm = SYMM_REF8
                    if (sc == 'm' && sn == 4) symm = if (sd) SYMM_REF4D else SYMM_REF4
                    if (sc == 'm' && sn == 2) symm = if (sd) SYMM_REF2D else SYMM_REF2
                    if (sc == 'r' && sn == 4) symm = SYMM_ROT4
                    if (sc == 'r' && sn == 2) symm = SYMM_ROT2
                    if (sc == 'a') symm = SYMM_NONE
                }

                'd' -> {
                    position++
                    if (position < text.length) {
                        when (text[position]) {
                            't' -> {
                                position++
                                diff = DIFF_BLOCK
                            }

                            'b' -> {
                                position++
                                diff = DIFF_SIMPLE
                            }

                            'i' -> {
                                position++
                                diff = DIFF_INTERSECT
                            }

                            'a' -> {
                                position++
                                diff = DIFF_SET
                            }

                            'e' -> {
                                position++
                                diff = DIFF_EXTREME
                            }

                            'u' -> {
                                position++
                                diff = DIFF_RECURSIVE
                            }
                        }
                    }
                }

                else -> position++
            }
        }
        return Params(c, r, symm, diff, xtype, killer)
    }

    /** `encode_params`, `solo.c:414-448`. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        if (p.r > 1) {
            out.append(p.c).append('x').append(p.r)
        } else {
            out.append(p.c).append('j')
        }
        if (p.xtype) out.append('x')
        if (p.killer) out.append('k')
        if (full) {
            when (p.symm) {
                SYMM_REF8 -> out.append("m8")
                SYMM_REF4 -> out.append("m4")
                SYMM_REF4D -> out.append("md4")
                SYMM_REF2 -> out.append("m2")
                SYMM_REF2D -> out.append("md2")
                SYMM_ROT4 -> out.append("r4")
                SYMM_NONE -> out.append('a')
                // SYMM_ROT2 is the default and writes nothing.
            }
            when (p.diff) {
                DIFF_SIMPLE -> out.append("db")
                DIFF_INTERSECT -> out.append("di")
                DIFF_SET -> out.append("da")
                DIFF_EXTREME -> out.append("de")
                DIFF_RECURSIVE -> out.append("du")
                // DIFF_BLOCK is the default and writes nothing.
            }
        }
        return out.toString()
    }

    /** `validate_params`, `solo.c:516-535`, in C order. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.c < 2) return "Both dimensions must be at least 2"
        if (p.c > ORDER_MAX || p.r > ORDER_MAX) {
            return "Dimensions greater than $ORDER_MAX are not supported"
        }
        if (p.c * p.r > 31) {
            return "Unable to support more than 31 distinct symbols in a puzzle"
        }
        if (p.killer && p.c * p.r > 9) {
            return "Killer puzzle dimensions must be smaller than 10"
        }
        if (p.xtype && p.c * p.r < 4) {
            return "X-type puzzle dimensions must be larger than 3"
        }
        return null
    }

    // ------------------------------------------------------------------
    // Block structures, `solo.c:537-660`.
    // ------------------------------------------------------------------

    /**
     * `struct block_structure`, `solo.c:247-283`. Only the first
     * [nrSquares] entries of each block are valid. [splitBlock] and
     * [mergeBlocks] reproduce the C array shuffling, so the block
     * numbering and the square order are observable in the solver.
     */
    internal class BlockStructure(
        val c: Int,
        val r: Int,
        val area: Int,
        val maxNrSquares: Int,
        nrBlocks: Int,
    ) {
        var nrBlocks: Int = nrBlocks
        val whichBlock: IntArray = IntArray(area)
        var blocks: Array<IntArray> = Array(nrBlocks) { IntArray(maxNrSquares) }
        var nrSquares: IntArray = IntArray(nrBlocks)

        fun dup(): BlockStructure {
            val nb = BlockStructure(c, r, area, maxNrSquares, nrBlocks)
            nrSquares.copyInto(nb.nrSquares, 0, 0, nrBlocks)
            whichBlock.copyInto(nb.whichBlock)
            for (i in 0 until nrBlocks) blocks[i].copyInto(nb.blocks[i])
            return nb
        }

        private fun grow() {
            if (nrBlocks <= blocks.size) return
            val old = blocks
            blocks = Array(nrBlocks) { i ->
                if (i < old.size) old[i] else IntArray(maxNrSquares)
            }
            nrSquares = nrSquares.copyOf(nrBlocks)
        }

        /** `split_block`, `solo.c:606-639`. */
        fun splitBlock(squares: IntArray, count: Int) {
            val previousBlock = whichBlock[squares[0]]
            val newBlock = nrBlocks
            nrBlocks++
            grow()
            blocks[newBlock] = IntArray(maxNrSquares)
            for (i in 0 until count) {
                check(whichBlock[squares[i]] == previousBlock) { "square is in another block" }
                whichBlock[squares[i]] = newBlock
                blocks[newBlock][i] = squares[i]
            }
            var j = 0
            val previousCount = nrSquares[previousBlock]
            for (i in 0 until previousCount) {
                val square = blocks[previousBlock][i]
                var found = false
                for (k in 0 until count) {
                    if (squares[k] == square) {
                        found = true
                        break
                    }
                }
                if (!found) blocks[previousBlock][j++] = square
            }
            nrSquares[previousBlock] -= count
            nrSquares[newBlock] = count
        }

        /** `remove_from_block`, `solo.c:641-651`. */
        fun removeFromBlock(block: Int, square: Int) {
            whichBlock[square] = -1
            var j = 0
            val count = nrSquares[block]
            for (i in 0 until count) {
                val value = blocks[block][i]
                if (value != square) blocks[block][j++] = value
            }
            nrSquares[block]--
        }
    }

    /** `make_blocks_from_whichblock`, `solo.c:3257-3272`. */
    private fun makeBlocksFromWhichBlock(blocks: BlockStructure) {
        for (i in 0 until blocks.nrBlocks) blocks.nrSquares[i] = 0
        for (i in 0 until blocks.area) {
            val block = blocks.whichBlock[i]
            val slot = blocks.nrSquares[block]++
            blocks.blocks[block][slot] = i
        }
    }

    /** `dsf_to_blocks`, `solo.c:3239-3255`. */
    private fun dsfToBlocks(dsf: Dsf, blocks: BlockStructure) {
        for (i in 0 until blocks.area) blocks.whichBlock[i] = -1
        var nrBlocks = 0
        for (i in 0 until blocks.area) {
            val root = dsf.canonify(i)
            if (blocks.whichBlock[root] < 0) blocks.whichBlock[root] = nrBlocks++
            blocks.whichBlock[i] = blocks.whichBlock[root]
        }
        blocks.nrBlocks = nrBlocks
    }

    /** `merge_blocks`, `solo.c:3424-3450`. */
    private fun mergeBlocks(blocks: BlockStructure, first: Int, second: Int) {
        var n1 = first
        var n2 = second
        if (n2 < n1) {
            val tmp = n2
            n2 = n1
            n1 = tmp
        }
        for (i in 0 until blocks.nrSquares[n2]) {
            blocks.whichBlock[blocks.blocks[n2][i]] = n1
        }
        for (i in 0 until blocks.nrSquares[n2]) {
            blocks.blocks[n1][blocks.nrSquares[n1] + i] = blocks.blocks[n2][i]
        }
        blocks.nrSquares[n1] += blocks.nrSquares[n2]

        val last = blocks.nrBlocks - 1
        if (n2 != last) {
            for (i in 0 until blocks.nrSquares[last]) {
                blocks.blocks[n2][i] = blocks.blocks[last][i]
            }
            for (i in 0 until blocks.nrSquares[last]) {
                blocks.whichBlock[blocks.blocks[last][i]] = n2
            }
            blocks.nrSquares[n2] = blocks.nrSquares[last]
        }
        blocks.nrBlocks = last
    }

    // ------------------------------------------------------------------
    // State, `solo.c:286-306`.
    // ------------------------------------------------------------------

    /**
     * The parts of a game state that no move modifies: the initial clue
     * grid. C shares the block structures between duplicates and never
     * writes them after `new_game`, so this port shares them too.
     */
    class Shared internal constructor(val initialGrid: IntArray)

    class State internal constructor(
        val cr: Int,
        val xtype: Boolean,
        val killer: Boolean,
        val grid: IntArray,
        val kgrid: IntArray?,
        val pencil: BooleanArray,
        val immutable: BooleanArray,
        internal val blocks: BlockStructure,
        internal val kblocks: BlockStructure?,
        internal val shared: Shared,
        var completed: Boolean,
        var cheated: Boolean,
    ) : Game.State {

        fun valueAt(x: Int, y: Int): Int = grid[y * cr + x]

        fun isImmutable(x: Int, y: Int): Boolean = immutable[y * cr + x]

        fun pencilAt(x: Int, y: Int, n: Int): Boolean = pencil[(y * cr + x) * cr + n - 1]

        fun blockOf(x: Int, y: Int): Int = blocks.whichBlock[y * cr + x]

        fun hasKillerCages(): Boolean = kblocks != null

        fun killerClueAt(x: Int, y: Int): Int = kgrid?.get(y * cr + x) ?: 0

        fun killerBlockOf(x: Int, y: Int): Int? = kblocks?.whichBlock?.get(y * cr + x)

        override fun copy(): Game.State = State(
            cr,
            xtype,
            killer,
            grid.copyOf(),
            kgrid?.copyOf(),
            pencil.copyOf(),
            immutable.copyOf(),
            blocks,
            kblocks,
            shared,
            completed,
            cheated,
        )

        override fun equals(other: Any?): Boolean {
            if (other !is State) return false
            val kgridEqual = if (kgrid == null) {
                other.kgrid == null
            } else {
                other.kgrid != null && kgrid.contentEquals(other.kgrid)
            }
            return cr == other.cr &&
                xtype == other.xtype &&
                killer == other.killer &&
                completed == other.completed &&
                cheated == other.cheated &&
                grid.contentEquals(other.grid) &&
                kgridEqual &&
                pencil.contentEquals(other.pencil) &&
                immutable.contentEquals(other.immutable)
        }

        override fun hashCode(): Int {
            var result = cr
            result = 31 * result + xtype.hashCode()
            result = 31 * result + killer.hashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + cheated.hashCode()
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + (kgrid?.contentHashCode() ?: 0)
            result = 31 * result + pencil.contentHashCode()
            result = 31 * result + immutable.contentHashCode()
            return result
        }
    }

    /** `struct game_ui`, `solo.c:4588-4614`. */
    class Ui internal constructor() : Game.Ui {
        var hx: Int = 0
        var hy: Int = 0
        var hpencil: Boolean = false
        var hshow: Boolean = false
        var hcursor: Boolean = false
        var pencilKeepHighlight: Boolean = true
    }

    /** `struct game_drawstate`, `solo.c:4657-4667`. */
    class DrawState internal constructor(val cr: Int, val xtype: Boolean) : Game.DrawState {
        var started: Boolean = false
        var tileSize: Int = 0
        val grid: IntArray = IntArray(cr * cr) { cr + 2 }
        val pencil: BooleanArray = BooleanArray(cr * cr * cr)
        val hl: IntArray = IntArray(cr * cr)
        var nregions: Int = 0
        var enteredItems: IntArray = IntArray(0)
    }

    // ------------------------------------------------------------------
    // Description parsing and validation, `solo.c:3898-4148`.
    // ------------------------------------------------------------------

    /** `spec_to_grid`, `solo.c:3898-3926`. */
    private fun specToGrid(text: String, start: Int, grid: IntArray, area: Int): Int {
        var i = start
        var out = 0
        while (i < text.length && text[i] != ',') {
            val ch = text[i]
            i++
            when {
                ch in 'a'..'z' -> {
                    val run = ch.code - 'a'.code + 1
                    require(out + run <= area) { "solo: the description runs past the grid" }
                    repeat(run) { grid[out++] = 0 }
                }

                ch == '_' -> Unit
                ch in '1'..'9' -> {
                    require(out < area) { "solo: the description runs past the grid" }
                    grid[out++] = atoi(text, i - 1)
                    while (i < text.length && text[i] in '0'..'9') i++
                }

                else -> throw IllegalArgumentException("solo: invalid character in the description")
            }
        }
        require(out == area) { "solo: the description does not fill the grid" }
        return i
    }

    private class DsfResult(val dsf: Dsf?, val next: Int, val error: String?)

    /** `spec_to_dsf`, `solo.c:3928-3997`. */
    private fun specToDsf(text: String, start: Int, cr: Int, area: Int): DsfResult {
        val dsf = Dsf.new(area)
        var pos = 0
        var i = start
        val total = 2 * cr * (cr - 1)
        while (i < text.length && text[i] != ',') {
            val ch = text[i]
            val run = when {
                ch == '_' -> 0
                ch in 'a'..'z' -> ch.code - 'a'.code + 1
                else -> return DsfResult(null, i, "Invalid character in game description")
            }
            i++
            val advance = run != 26
            var left = run
            while (left-- > 0) {
                if (pos >= total) {
                    return DsfResult(
                        null,
                        i,
                        "Too much data in block structure specification",
                    )
                }
                val p0: Int
                val p1: Int
                if (pos < cr * (cr - 1)) {
                    val y = pos / (cr - 1)
                    val x = pos % (cr - 1)
                    p0 = y * cr + x
                    p1 = p0 + 1
                } else {
                    val x = pos / (cr - 1) - cr
                    val y = pos % (cr - 1)
                    p0 = y * cr + x
                    p1 = p0 + cr
                }
                dsf.merge(p0, p1)
                pos++
            }
            if (advance) pos++
        }
        if (pos != total + 1) {
            return DsfResult(null, i, "Not enough data in block structure specification")
        }
        return DsfResult(dsf, i, null)
    }

    private class GridResult(val next: Int, val error: String?)

    /** `validate_grid_desc`, `solo.c:3998-4026`. */
    private fun validateGridDesc(
        text: String,
        start: Int,
        range: Int,
        area: Int,
    ): GridResult {
        var i = start
        var squares = 0
        while (i < text.length && text[i] != ',') {
            val ch = text[i]
            i++
            when {
                ch in 'a'..'z' -> squares += ch.code - 'a'.code + 1
                ch == '_' -> Unit
                ch in '1'..'9' -> {
                    val value = atoi(text, i - 1)
                    if (value < 1 || value > range) {
                        return GridResult(i, "Number out of range in game description")
                    }
                    squares++
                    while (i < text.length && text[i] in '0'..'9') i++
                }

                else -> return GridResult(i, "Invalid character in game description")
            }
        }
        if (squares < area) return GridResult(i, "Not enough data to fill grid")
        if (squares > area) return GridResult(i, "Too much data to fit in grid")
        return GridResult(i, null)
    }

    private class BlockResult(val next: Int, val error: String?)

    /** `validate_block_desc`, `solo.c:4028-4103`. */
    private fun validateBlockDesc(
        text: String,
        start: Int,
        cr: Int,
        area: Int,
        minNrBlocks: Int,
        maxNrBlocks: Int,
        minNrSquares: Int,
        maxNrSquares: Int,
    ): BlockResult {
        val parsed = specToDsf(text, start, cr, area)
        if (parsed.error != null) return BlockResult(parsed.next, parsed.error)
        val dsf = parsed.dsf!!
        val canons = IntArray(maxNrBlocks)
        val counts = IntArray(maxNrBlocks)
        var ncanons = 0
        for (i in 0 until area) {
            val canon = dsf.canonify(i)
            var c = 0
            while (c < ncanons && canons[c] != canon) c++
            if (c < ncanons) {
                counts[c]++
                if (counts[c] > maxNrSquares) {
                    return BlockResult(parsed.next, "A jigsaw block is too big")
                }
            } else {
                if (ncanons >= maxNrBlocks) {
                    return BlockResult(parsed.next, "Too many distinct jigsaw blocks")
                }
                canons[ncanons] = canon
                counts[ncanons] = 1
                ncanons++
            }
        }
        if (ncanons < minNrBlocks) {
            return BlockResult(parsed.next, "Not enough distinct jigsaw blocks")
        }
        for (c in 0 until ncanons) {
            if (counts[c] < minNrSquares) {
                return BlockResult(parsed.next, "A jigsaw block is too small")
            }
        }
        return BlockResult(parsed.next, null)
    }

    /** `validate_desc`, `solo.c:4105-4147`. */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val cr = p.c * p.r
        val area = cr * cr
        var pos = 0
        var grid = validateGridDesc(desc, pos, cr, area)
        if (grid.error != null) return grid.error
        pos = grid.next

        if (p.r == 1) {
            if (pos >= desc.length || desc[pos] != ',') {
                return "Expected jigsaw block structure in game description"
            }
            pos++
            val blocks = validateBlockDesc(desc, pos, cr, area, cr, cr, cr, cr)
            if (blocks.error != null) return blocks.error
            pos = blocks.next
        }
        if (p.killer) {
            if (pos >= desc.length || desc[pos] != ',') {
                return "Expected killer block structure in game description"
            }
            pos++
            val cages = validateBlockDesc(desc, pos, cr, area, cr, area, 2, cr)
            if (cages.error != null) return cages.error
            pos = cages.next
            if (pos >= desc.length || desc[pos] != ',') {
                return "Expected killer clue grid in game description"
            }
            pos++
            grid = validateGridDesc(desc, pos, cr * area, area)
            if (grid.error != null) return grid.error
            pos = grid.next
        }
        if (pos < desc.length) return "Unexpected data at end of game description"
        return null
    }

    /** `new_game`, `solo.c:4149-4249`. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val c = p.c
        val r = p.r
        val cr = c * r
        val area = cr * cr
        if (r <= 0) throw IllegalArgumentException("solo: the grid has no cells")
        val grid = IntArray(area)
        var pos = specToGrid(desc, 0, grid, area)
        val immutable = BooleanArray(area) { grid[it] != 0 }
        val blocks = BlockStructure(c, r, area, cr, cr)
        if (r == 1) {
            require(pos < desc.length && desc[pos] == ',') {
                "solo: expected a jigsaw block structure"
            }
            pos++
            val parsed = specToDsf(desc, pos, cr, area)
            require(parsed.error == null && parsed.dsf != null) {
                "solo: ${parsed.error ?: "invalid jigsaw block structure"}"
            }
            dsfToBlocks(parsed.dsf, blocks)
            pos = parsed.next
        } else {
            for (y in 0 until cr) {
                for (x in 0 until cr) {
                    blocks.whichBlock[y * cr + x] = (y / c) * c + (x / r)
                }
            }
        }
        makeBlocksFromWhichBlock(blocks)

        var kblocks: BlockStructure? = null
        var kgrid: IntArray? = null
        if (p.killer) {
            kblocks = BlockStructure(c, r, area, cr, area)
            require(pos < desc.length && desc[pos] == ',') {
                "solo: expected a killer block structure"
            }
            pos++
            val parsed = specToDsf(desc, pos, cr, area)
            require(parsed.error == null && parsed.dsf != null) {
                "solo: ${parsed.error ?: "invalid killer block structure"}"
            }
            dsfToBlocks(parsed.dsf, kblocks)
            makeBlocksFromWhichBlock(kblocks)
            pos = parsed.next
            require(pos < desc.length && desc[pos] == ',') {
                "solo: expected a killer clue grid"
            }
            pos++
            kgrid = IntArray(area)
            pos = specToGrid(desc, pos, kgrid, area)
        }
        require(pos == desc.length) { "solo: unexpected data at end of the description" }
        return State(
            cr = cr,
            xtype = p.xtype,
            killer = p.killer,
            grid = grid,
            kgrid = kgrid,
            pencil = BooleanArray(area * cr),
            immutable = immutable,
            blocks = blocks,
            kblocks = kblocks,
            shared = Shared(grid.copyOf()),
            completed = false,
            cheated = false,
        )
    }

    // ------------------------------------------------------------------
    // Solver, `solo.c:661-2680`.
    // ------------------------------------------------------------------

    private class Difficulty(
        var maxdiff: Int,
        var maxkdiff: Int,
        var diff: Int = DIFF_BLOCK,
        var kdiff: Int = DIFF_KSINGLE,
    )

    private class Usage(val cr: Int, val blocks: BlockStructure) {
        var kblocks: BlockStructure? = null
        var extraCages: BlockStructure? = null
        var extraClues: IntArray? = null
        lateinit var cube: BooleanArray
        lateinit var grid: IntArray
        var kclues: IntArray? = null
        lateinit var row: BooleanArray
        lateinit var col: BooleanArray
        lateinit var blk: BooleanArray
        var diag: BooleanArray? = null
        var regions: IntArray = IntArray(0)
        var nrRegions: Int = 0

        fun cube(x: Int, y: Int, n: Int): Boolean = cube[(y * cr + x) * cr + (n - 1)]

        fun setCube(x: Int, y: Int, n: Int, value: Boolean) {
            cube[(y * cr + x) * cr + (n - 1)] = value
        }

        fun cube2(xy: Int, n: Int): Boolean = cube[xy * cr + (n - 1)]

        fun setCube2(xy: Int, n: Int, value: Boolean) {
            cube[xy * cr + (n - 1)] = value
        }
    }

    private class Scratch(cr: Int) {
        val grid = IntArray(cr * cr)
        val rowidx = IntArray(cr)
        val colidx = IntArray(cr)
        val set = IntArray(cr)
        val neighbours = IntArray(5 * cr)
        val bfsqueue = IntArray(cr * cr)
        val indexlist = IntArray(cr * cr)
        val indexlist2 = IntArray(cr)
    }

    /** `find_sum_bits`, `solo.c:163-187`. */
    private fun findSumBits(
        array: LongArray,
        start: Int,
        valueLeft: Int,
        addendsLeft: Int,
        minAddend: Int,
        bitmaskSoFar: Long,
    ): Int {
        var idx = start
        for (i in minAddend until valueLeft) {
            val newBitmask = bitmaskSoFar or (1L shl i)
            if (addendsLeft == 2) {
                val j = valueLeft - i
                if (j <= i) break
                if (j > 9) continue
                array[idx++] = newBitmask or (1L shl j)
            } else {
                idx = findSumBits(
                    array,
                    idx,
                    valueLeft - i,
                    addendsLeft - 1,
                    i + 1,
                    newBitmask,
                )
            }
        }
        return idx
    }

    /** `precompute_sum_bits`, `solo.c:189-221`. */
    private fun precomputeSumBits() {
        for (i in 3..30) {
            if (i < 18) findSumBits(sumBits2[i], 0, i, 2, 1, 0)
            if (i < 25) findSumBits(sumBits3[i], 0, i, 3, 1, 0)
            findSumBits(sumBits4[i], 0, i, 4, 1, 0)
        }
    }

    /** `solver_place`, `solo.c:774-846`. */
    private fun solverPlace(usage: Usage, x: Int, y: Int, n: Int) {
        val cr = usage.cr
        val sqindex = y * cr + x
        for (i in 1..cr) if (i != n) usage.setCube(x, y, i, false)
        for (i in 0 until cr) if (i != y) usage.setCube(x, i, n, false)
        for (i in 0 until cr) if (i != x) usage.setCube(i, y, n, false)
        val bi = usage.blocks.whichBlock[sqindex]
        for (i in 0 until cr) {
            val bp = usage.blocks.blocks[bi][i]
            if (bp != sqindex) usage.setCube2(bp, n, false)
        }
        usage.grid[sqindex] = n
        usage.row[y * cr + n - 1] = true
        usage.col[x * cr + n - 1] = true
        usage.blk[bi * cr + n - 1] = true
        usage.diag?.let { diag ->
            if (ondiag0(sqindex, cr)) {
                for (i in 0 until cr) {
                    if (diag0(i, cr) != sqindex) usage.setCube2(diag0(i, cr), n, false)
                }
                diag[n - 1] = true
            }
            if (ondiag1(sqindex, cr)) {
                for (i in 0 until cr) {
                    if (diag1(i, cr) != sqindex) usage.setCube2(diag1(i, cr), n, false)
                }
                diag[cr + n - 1] = true
            }
        }
    }

    private fun ondiag0(xy: Int, cr: Int): Boolean = xy % (cr + 1) == 0
    private fun ondiag1(xy: Int, cr: Int): Boolean =
        xy % (cr - 1) == 0 && xy > 0 && xy < cr * cr - 1
    private fun diag0(i: Int, cr: Int): Int = i * (cr + 1)
    private fun diag1(i: Int, cr: Int): Int = (i + 1) * (cr - 1)

    /** `solver_elim`, `solo.c:860-921`. */
    private fun solverElim(usage: Usage, indices: IntArray): Int {
        val cr = usage.cr
        var m = 0
        var fpos = -1
        for (i in 0 until cr) {
            if (usage.cube[indices[i]]) {
                fpos = indices[i]
                m++
            }
        }
        if (m == 1) {
            val n = 1 + fpos % cr
            val x = (fpos / cr) % cr
            val y = (fpos / cr) / cr
            if (usage.grid[y * cr + x] == 0) {
                solverPlace(usage, x, y, n)
                return 1
            }
        } else if (m == 0) {
            return -1
        }
        return 0
    }

    /** `solver_intersect`, `solo.c:923-998`. */
    private fun solverIntersect(usage: Usage, indices1: IntArray, indices2: IntArray): Int {
        val cr = usage.cr
        var j = 0
        for (i in 0 until cr) {
            val p = indices1[i]
            while (j < cr && indices2[j] < p) j++
            if (usage.cube[p]) {
                if (j < cr && indices2[j] == p) continue
                return 0
            }
        }
        var ret = 0
        j = 0
        for (i in 0 until cr) {
            val p = indices2[i]
            while (j < cr && indices1[j] < p) j++
            if (usage.cube[p] && (j >= cr || indices1[j] != p)) {
                ret = 1
                usage.cube[p] = false
            }
        }
        return ret
    }

    /** `solver_set`, `solo.c:1000-1240`. */
    private fun solverSet(usage: Usage, scratch: Scratch, indices: IntArray): Int {
        val cr = usage.cr
        val grid = scratch.grid
        val rowidx = scratch.rowidx
        val colidx = scratch.colidx
        val set = scratch.set

        rowidx.fill(1, 0, cr)
        colidx.fill(1, 0, cr)
        for (i in 0 until cr) {
            var count = 0
            var first = -1
            for (j in 0 until cr) {
                if (usage.cube[indices[i * cr + j]]) {
                    first = j
                    count++
                }
            }
            if (count == 0) return -1
            if (count == 1) {
                rowidx[i] = 0
                colidx[first] = 0
            }
        }

        var j = 0
        for (i in 0 until cr) if (rowidx[i] != 0) rowidx[j++] = i
        val n = j
        j = 0
        for (i in 0 until cr) if (colidx[i] != 0) colidx[j++] = i
        check(n == j) { "solver_set column count" }

        for (i in 0 until n) {
            for (k in 0 until n) {
                grid[i * cr + k] = if (usage.cube[indices[rowidx[i] * cr + colidx[k]]]) 1 else 0
            }
        }

        set.fill(0, 0, n)
        var count = 0
        while (true) {
            if (count > 1 && count < n - 1) {
                var rows = 0
                for (i in 0 until n) {
                    var ok = true
                    for (k in 0 until n) {
                        if (set[k] != 0 && grid[i * cr + k] != 0) {
                            ok = false
                            break
                        }
                    }
                    if (ok) rows++
                }
                if (rows > n - count) return -1
                if (rows >= n - count) {
                    var progress = false
                    for (i in 0 until n) {
                        var ok = true
                        for (k in 0 until n) {
                            if (set[k] != 0 && grid[i * cr + k] != 0) {
                                ok = false
                                break
                            }
                        }
                        if (!ok) {
                            for (k in 0 until n) {
                                if (set[k] == 0 && grid[i * cr + k] != 0) {
                                    val fpos = indices[rowidx[i] * cr + colidx[k]]
                                    progress = true
                                    usage.cube[fpos] = false
                                }
                            }
                        }
                    }
                    if (progress) return 1
                }
            }

            var i = n
            while (i > 0 && set[i - 1] != 0) {
                i--
                set[i] = 0
                count--
            }
            if (i > 0) {
                i--
                set[i] = 1
                count++
            } else {
                break
            }
        }
        return 0
    }

    /** `solver_forcing`, `solo.c:1242-1411`. */
    private fun solverForcing(usage: Usage, scratch: Scratch): Int {
        val cr = usage.cr
        val bfsqueue = scratch.bfsqueue
        val number = scratch.grid
        val neighbours = scratch.neighbours

        for (y in 0 until cr) {
            for (x in 0 until cr) {
                var count = 0
                var total = 0
                for (n in 1..cr) {
                    if (usage.cube(x, y, n)) {
                        count++
                        total += n
                    }
                }
                if (count != 2) continue
                for (n in 1..cr) {
                    if (!usage.cube(x, y, n)) continue
                    val orign = n
                    number.fill(cr + 1)
                    var head = 0
                    var tail = 0
                    bfsqueue[tail++] = y * cr + x
                    number[y * cr + x] = total - n

                    while (head < tail) {
                        var xx = bfsqueue[head++]
                        val yy = xx / cr
                        xx %= cr
                        val currn = number[yy * cr + xx]

                        var nneighbours = 0
                        for (yt in 0 until cr) neighbours[nneighbours++] = yt * cr + xx
                        for (xt in 0 until cr) neighbours[nneighbours++] = yy * cr + xt
                        val block = usage.blocks.whichBlock[yy * cr + xx]
                        for (yt in 0 until cr) neighbours[nneighbours++] = usage.blocks.blocks[block][yt]
                        usage.diag?.let {
                            val sqindex = yy * cr + xx
                            if (ondiag0(sqindex, cr)) {
                                for (i in 0 until cr) neighbours[nneighbours++] = diag0(i, cr)
                            }
                            if (ondiag1(sqindex, cr)) {
                                for (i in 0 until cr) neighbours[nneighbours++] = diag1(i, cr)
                            }
                        }

                        for (i in 0 until nneighbours) {
                            val xt = neighbours[i] % cr
                            val yt = neighbours[i] / cr
                            if (number[yt * cr + xt] <= cr) continue
                            if (!usage.cube(xt, yt, currn)) continue
                            if (xt == xx && yt == yy) continue

                            var cc = 0
                            var tt = 0
                            for (nn in 1..cr) {
                                if (usage.cube(xt, yt, nn)) {
                                    cc++
                                    tt += nn
                                }
                            }
                            if (cc == 2) {
                                bfsqueue[tail++] = yt * cr + xt
                                number[yt * cr + xt] = tt - currn
                            }

                            if (currn == orign &&
                                (xt == x || yt == y ||
                                    usage.blocks.whichBlock[yt * cr + xt] ==
                                    usage.blocks.whichBlock[y * cr + x] ||
                                    (usage.diag != null &&
                                        ((ondiag0(yt * cr + xt, cr) && ondiag0(y * cr + x, cr)) ||
                                            (ondiag1(yt * cr + xt, cr) && ondiag1(y * cr + x, cr)))))
                            ) {
                                usage.setCube(xt, yt, orign, false)
                                return 1
                            }
                        }
                    }
                }
            }
        }
        return 0
    }

    /** `solver_killer_minmax`, `solo.c:1413-1475`. */
    private fun solverKillerMinmax(
        usage: Usage,
        cages: BlockStructure,
        clues: IntArray,
        b: Int,
    ): Int {
        val cr = usage.cr
        var ret = 0
        val nsquares = cages.nrSquares[b]
        if (clues[b] == 0) return 0
        for (i in 0 until nsquares) {
            val x = cages.blocks[b][i]
            for (n in 1..cr) {
                if (!usage.cube2(x, n)) continue
                var maxval = 0
                var minval = 0
                for (j in 0 until nsquares) {
                    if (i == j) continue
                    val y = cages.blocks[b][j]
                    for (m in 1..cr) {
                        if (usage.cube2(y, m)) {
                            minval += m
                            break
                        }
                    }
                    for (m in cr downTo 1) {
                        if (usage.cube2(y, m)) {
                            maxval += m
                            break
                        }
                    }
                }
                if (maxval + n < clues[b]) {
                    usage.setCube2(x, n, false)
                    ret = 1
                }
                if (minval + n > clues[b]) {
                    usage.setCube2(x, n, false)
                    ret = 1
                }
            }
        }
        return ret
    }

    /** `solver_killer_sums`, `solo.c:1477-1612`. */
    private fun solverKillerSums(
        usage: Usage,
        b: Int,
        cages: BlockStructure,
        clue: Int,
        cageIsRegion: Boolean,
    ): Int {
        val cr = usage.cr
        val nsquares = cages.nrSquares[b]
        if (clue == 0) {
            /*
             * C asserts that an unclued cage has no squares. A
             * hand-written killer description can break that, so the
             * port treats the cage as having no sum information
             * (D-024).
             */
            return 0
        }
        if (nsquares == 0) return -1
        if (nsquares < 2 || nsquares > 4) return 0

        if (!cageIsRegion) {
            var knownRow = -1
            var knownCol = -1
            var knownBlock = -1
            for (i in 0 until nsquares) {
                val x = cages.blocks[b][i]
                check(usage.grid[x] == 0) { "killer cage square is filled" }
                if (i == 0) {
                    knownRow = x / cr
                    knownCol = x % cr
                    knownBlock = usage.blocks.whichBlock[x]
                } else {
                    if (knownRow != x / cr) knownRow = -1
                    if (knownCol != x % cr) knownCol = -1
                    if (knownBlock != usage.blocks.whichBlock[x]) knownBlock = -1
                }
            }
            if (knownBlock == -1 && knownCol == -1 && knownRow == -1) return 0
        }

        val sumbits: LongArray
        val maxSums: Int
        if (nsquares == 2) {
            if (clue < 3 || clue > 17) return -1
            sumbits = sumBits2[clue]
            maxSums = MAX_2SUMS
        } else if (nsquares == 3) {
            if (clue < 6 || clue > 24) return -1
            sumbits = sumBits3[clue]
            maxSums = MAX_3SUMS
        } else {
            if (clue < 10 || clue > 30) return -1
            sumbits = sumBits4[clue]
            maxSums = MAX_4SUMS
        }

        var possibleAddends = 0L
        for (i in 0 until maxSums) {
            val bits = sumbits[i]
            if (bits == 0L) break
            var j = 0
            while (j < nsquares) {
                var squareBits = bits
                val x = cages.blocks[b][j]
                for (n in 1..cr) {
                    if (!usage.cube2(x, n)) squareBits = squareBits and (1L shl n).inv()
                }
                if (squareBits == 0L) break
                j++
            }
            if (j == nsquares) possibleAddends = possibleAddends or bits
        }
        if (possibleAddends == 0L) return -1

        var ret = 0
        for (i in 0 until nsquares) {
            val x = cages.blocks[b][i]
            for (n in 1..cr) {
                if (!usage.cube2(x, n)) continue
                if ((possibleAddends and (1L shl n)) == 0L) {
                    usage.setCube2(x, n, false)
                    ret = 1
                }
            }
        }
        return ret
    }

    /** `filter_whole_cages`, `solo.c:1614-1668`. */
    private fun filterWholeCages(
        usage: Usage,
        squares: IntArray,
        count: Int,
        filteredOut: IntArray,
    ): Int {
        val kblocks = usage.kblocks!!
        var n = count
        var filtered = 0
        var j = 0
        for (i in 0 until n) {
            val square = squares[i]
            if (usage.grid[square] != 0) {
                filtered += usage.grid[square]
            } else {
                squares[j++] = square
            }
        }
        n = j
        filteredOut[0] = 0

        var off = 0
        for (b in 0 until kblocks.nrBlocks) {
            if (off >= n) break
            val bSquares = kblocks.nrSquares[b]
            if (bSquares == 0) continue
            var matched = 0
            for (i in 0 until bSquares) {
                for (jj in off until n) {
                    if (squares[jj] == kblocks.blocks[b][i]) {
                        val t = squares[off + matched]
                        squares[off + matched] = squares[jj]
                        squares[jj] = t
                        matched++
                        break
                    }
                }
            }
            if (matched != kblocks.nrSquares[b]) {
                off += matched
                continue
            }
            for (k in off until n - matched) squares[k] = squares[k + matched]
            n -= matched
            filtered += usage.kclues!![b]
        }
        check(off == n) { "filter_whole_cages offset" }
        filteredOut[0] = filtered
        return off
    }

    /**
     * `solver`, `solo.c:1715-2678`. The deduction order is the
     * difficulty ladder: block positional elimination, killer cage
     * analysis, rows, columns, diagonals, numeric elimination,
     * intersection, set elimination, positional set elimination, forcing
     * chains, then recursion.
     */
    private fun solver(
        cr: Int,
        blocks: BlockStructure,
        kblocks: BlockStructure?,
        xtype: Boolean,
        grid: IntArray,
        kgrid: IntArray?,
        dlev: Difficulty,
    ) {
        var diff = DIFF_BLOCK
        var kdiff = DIFF_KSINGLE

        val usage = Usage(cr, blocks)
        if (kblocks != null) {
            usage.kblocks = kblocks.dup()
            usage.extraCages = BlockStructure(kblocks.c, kblocks.r, cr * cr, cr, cr * cr)
            usage.extraClues = IntArray(cr * cr)
        }
        usage.cube = BooleanArray(cr * cr * cr) { true }
        usage.grid = grid
        if (kgrid != null) {
            check(kblocks != null) { "killer grid without cages" }
            val nclues = kblocks!!.nrBlocks
            usage.kclues = IntArray(cr * cr)
            for (i in 0 until nclues) {
                for (n in 0 until kblocks.nrSquares[i]) {
                    val value = kgrid[kblocks.blocks[i][n]]
                    if (value != 0) usage.kclues!![i] = value
                }
                /*
                 * C asserts a clue exists. A hand-written description
                 * can have an unclued cage; the port reads it as 0
                 * (D-024).
                 */
            }
        }
        usage.row = BooleanArray(cr * cr)
        usage.col = BooleanArray(cr * cr)
        usage.blk = BooleanArray(cr * cr)
        if (xtype) usage.diag = BooleanArray(cr * 2)

        usage.nrRegions = cr * 3 + if (xtype) 2 else 0
        usage.regions = IntArray(cr * usage.nrRegions)
        for (n in 0 until cr) {
            for (i in 0 until cr) {
                val x = n * cr + i
                val y = i * cr + n
                val b = blocks.blocks[n][i]
                usage.regions[cr * n * 3 + i] = x
                usage.regions[cr * n * 3 + cr + i] = y
                usage.regions[cr * n * 3 + 2 * cr + i] = b
            }
        }

        val scratch = Scratch(cr)

        // Place the given clues.
        for (x in 0 until cr) {
            for (y in 0 until cr) {
                val n = grid[y * cr + x]
                if (n != 0) {
                    if (!usage.cube(x, y, n)) {
                        diff = DIFF_IMPOSSIBLE
                        dlev.diff = diff
                        dlev.kdiff = kdiff
                        return
                    }
                    solverPlace(usage, x, y, n)
                }
            }
        }

        var result = false
        while (!result) {
            var restart = false
            // Blockwise positional elimination.
            for (b in 0 until cr) {
                for (n in 1..cr) {
                    if (!usage.blk[b * cr + n - 1]) {
                        for (i in 0 until cr) {
                            scratch.indexlist[i] = usage.blocks.blocks[b][i] * cr + (n - 1)
                        }
                        val ret = solverElim(usage, scratch.indexlist)
                        if (ret < 0) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        } else if (ret > 0) {
                            diff = max(diff, DIFF_BLOCK)
                            restart = true
                            break
                        }
                    }
                }
                if (result || restart) break
            }
            if (result) break
            if (restart) continue

            if (usage.kclues != null) {
                var changed = false
                for (b in 0 until usage.kblocks!!.nrBlocks) {
                    for (i in usage.kblocks!!.nrSquares[b] - 1 downTo 0) {
                        val x = usage.kblocks!!.blocks[b][i]
                        val t = usage.grid[x]
                        if (t == 0) continue
                        usage.kblocks!!.removeFromBlock(b, x)
                        if (t > usage.kclues!![b]) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        }
                        usage.kclues!![b] -= t
                        for (n in 0 until usage.kblocks!!.nrSquares[b]) {
                            usage.setCube2(usage.kblocks!!.blocks[b][n], t, false)
                        }
                    }
                    if (result) break
                }
                if (result) break

                for (b in 0 until usage.kblocks!!.nrBlocks) {
                    if (usage.kblocks!!.nrSquares[b] == 1) {
                        val v = usage.kclues!![b]
                        if (v < 1 || v > cr) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        }
                        val x = usage.kblocks!!.blocks[b][0] % cr
                        val y = usage.kblocks!!.blocks[b][0] / cr
                        if (!usage.cube(x, y, v)) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        }
                        solverPlace(usage, x, y, v)
                        changed = true
                    }
                }
                if (result) break
                if (changed) {
                    kdiff = max(kdiff, DIFF_KSINGLE)
                    continue
                }
            }

            if (dlev.maxkdiff >= DIFF_KINTERSECT && usage.kclues != null) {
                var changed = false
                usage.extraCages!!.nrBlocks = 0
                for (i in 0 until 3) {
                    for (n in 0 until cr) {
                        val base = cr * n * 3 + i * cr
                        val sum = cr * (cr + 1) / 2
                        var nsquares = cr
                        val nExtra = usage.extraCages!!.nrBlocks
                        val extraList = usage.extraCages!!.blocks[nExtra]
                        for (k in 0 until cr) extraList[k] = usage.regions[base + k]
                        val filtered = IntArray(1)
                        nsquares = filterWholeCages(usage, extraList, nsquares, filtered)
                        var remaining = sum - filtered[0]
                        if (nsquares == cr || nsquares == 0) continue
                        if (dlev.maxdiff >= DIFF_RECURSIVE) {
                            if (remaining <= 0) {
                                /*
                                 * C assigns DIFF_IMPOSSIBLE here and
                                 * then overwrites it at `got_result`.
                                 * The local `diff` is the one that
                                 * survives, so this leaves the loop
                                 * with the current value.
                                 */
                                result = true
                                break
                            }
                        }
                        check(remaining > 0) { "killer extra cage sum" }

                        if (nsquares == 1) {
                            if (remaining > cr) {
                                diff = DIFF_IMPOSSIBLE
                                result = true
                                break
                            }
                            val x = extraList[0] % cr
                            val y = extraList[0] / cr
                            if (!usage.cube(x, y, remaining)) {
                                diff = DIFF_IMPOSSIBLE
                                result = true
                                break
                            }
                            solverPlace(usage, x, y, remaining)
                            changed = true
                        }

                        val b = usage.kblocks!!.whichBlock[extraList[0]]
                        var x = 1
                        while (x < nsquares) {
                            if (usage.kblocks!!.whichBlock[extraList[x]] != b) break
                            x++
                        }
                        if (x == nsquares) {
                            if (!(usage.kblocks!!.nrSquares[b] > nsquares)) {
                                diff = DIFF_IMPOSSIBLE
                                result = true
                                break
                            }
                            usage.kblocks!!.splitBlock(extraList, nsquares)
                            usage.kclues!![usage.kblocks!!.nrBlocks - 1] = remaining
                            usage.kclues!![b] -= remaining
                        } else {
                            usage.extraCages!!.nrSquares[nExtra] = nsquares
                            usage.extraCages!!.nrBlocks++
                            usage.extraClues!![nExtra] = remaining
                        }
                        remaining = 0
                    }
                    if (result) break
                }
                if (result) break
                if (changed) {
                    kdiff = max(kdiff, DIFF_KINTERSECT)
                    continue
                }
            }

            if (dlev.maxkdiff >= DIFF_KMINMAX && usage.kclues != null) {
                var changed = false
                for (b in 0 until usage.kblocks!!.nrBlocks) {
                    if (solverKillerMinmax(usage, usage.kblocks!!, usage.kclues!!, b) > 0) {
                        changed = true
                    }
                }
                for (b in 0 until usage.extraCages!!.nrBlocks) {
                    if (solverKillerMinmax(
                            usage,
                            usage.extraCages!!,
                            usage.extraClues!!,
                            b,
                        ) > 0
                    ) {
                        changed = true
                    }
                }
                if (changed) {
                    kdiff = max(kdiff, DIFF_KMINMAX)
                    continue
                }
            }

            if (dlev.maxkdiff >= DIFF_KSUMS && usage.kclues != null) {
                var changed = false
                for (b in 0 until usage.kblocks!!.nrBlocks) {
                    val ret = solverKillerSums(
                        usage,
                        b,
                        usage.kblocks!!,
                        usage.kclues!![b],
                        true,
                    )
                    if (ret > 0) {
                        changed = true
                        kdiff = max(kdiff, DIFF_KSUMS)
                    } else if (ret < 0) {
                        diff = DIFF_IMPOSSIBLE
                        result = true
                        break
                    }
                }
                if (result) break
                for (b in 0 until usage.extraCages!!.nrBlocks) {
                    val ret = solverKillerSums(
                        usage,
                        b,
                        usage.extraCages!!,
                        usage.extraClues!![b],
                        false,
                    )
                    if (ret > 0) {
                        changed = true
                        kdiff = max(kdiff, DIFF_KSUMS)
                    } else if (ret < 0) {
                        diff = DIFF_IMPOSSIBLE
                        result = true
                        break
                    }
                }
                if (result) break
                if (changed) continue
            }

            if (dlev.maxdiff <= DIFF_BLOCK) break

            // Row-wise positional elimination.
            for (y in 0 until cr) {
                for (n in 1..cr) {
                    if (!usage.row[y * cr + n - 1]) {
                        for (x in 0 until cr) {
                            scratch.indexlist[x] = (y * cr + x) * cr + (n - 1)
                        }
                        val ret = solverElim(usage, scratch.indexlist)
                        if (ret < 0) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        } else if (ret > 0) {
                            diff = max(diff, DIFF_SIMPLE)
                            restart = true
                            break
                        }
                    }
                }
                if (result || restart) break
            }
            if (result) break
            if (restart) continue

            // Column-wise positional elimination.
            for (x in 0 until cr) {
                for (n in 1..cr) {
                    if (!usage.col[x * cr + n - 1]) {
                        for (y in 0 until cr) {
                            scratch.indexlist[y] = (y * cr + x) * cr + (n - 1)
                        }
                        val ret = solverElim(usage, scratch.indexlist)
                        if (ret < 0) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        } else if (ret > 0) {
                            diff = max(diff, DIFF_SIMPLE)
                            restart = true
                            break
                        }
                    }
                }
                if (result || restart) break
            }
            if (result) break
            if (restart) continue

            val diag = usage.diag
            if (diag != null) {
                for (n in 1..cr) {
                    if (!diag[n - 1]) {
                        for (i in 0 until cr) {
                            scratch.indexlist[i] = diag0(i, cr) * cr + (n - 1)
                        }
                        val ret = solverElim(usage, scratch.indexlist)
                        if (ret < 0) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        } else if (ret > 0) {
                            diff = max(diff, DIFF_SIMPLE)
                            restart = true
                            break
                        }
                    }
                }
                if (!result && !restart) {
                    for (n in 1..cr) {
                        if (!diag[cr + n - 1]) {
                            for (i in 0 until cr) {
                                scratch.indexlist[i] = diag1(i, cr) * cr + (n - 1)
                            }
                            val ret = solverElim(usage, scratch.indexlist)
                            if (ret < 0) {
                                diff = DIFF_IMPOSSIBLE
                                result = true
                                break
                            } else if (ret > 0) {
                                diff = max(diff, DIFF_SIMPLE)
                                restart = true
                                break
                            }
                        }
                    }
                }
            }
            if (result) break
            if (restart) continue

            // Numeric elimination.
            for (x in 0 until cr) {
                for (y in 0 until cr) {
                    if (usage.grid[y * cr + x] == 0) {
                        for (n in 1..cr) {
                            scratch.indexlist[n - 1] = (y * cr + x) * cr + (n - 1)
                        }
                        val ret = solverElim(usage, scratch.indexlist)
                        if (ret < 0) {
                            diff = DIFF_IMPOSSIBLE
                            result = true
                            break
                        } else if (ret > 0) {
                            diff = max(diff, DIFF_SIMPLE)
                            restart = true
                            break
                        }
                    }
                }
                if (result || restart) break
            }
            if (result) break
            if (restart) continue

            if (dlev.maxdiff <= DIFF_SIMPLE) break

            // Intersectional analysis, rows vs blocks.
            for (y in 0 until cr) {
                for (b in 0 until cr) {
                    for (n in 1..cr) {
                        if (usage.row[y * cr + n - 1] || usage.blk[b * cr + n - 1]) continue
                        for (i in 0 until cr) {
                            scratch.indexlist[i] = (y * cr + i) * cr + (n - 1)
                            scratch.indexlist2[i] =
                                usage.blocks.blocks[b][i] * cr + (n - 1)
                        }
                        if (solverIntersect(usage, scratch.indexlist, scratch.indexlist2) != 0 ||
                            solverIntersect(usage, scratch.indexlist2, scratch.indexlist) != 0
                        ) {
                            diff = max(diff, DIFF_INTERSECT)
                            restart = true
                            break
                        }
                    }
                    if (restart) break
                }
                if (restart) break
            }
            if (restart) continue

            // Intersectional analysis, columns vs blocks.
            for (x in 0 until cr) {
                for (b in 0 until cr) {
                    for (n in 1..cr) {
                        if (usage.col[x * cr + n - 1] || usage.blk[b * cr + n - 1]) continue
                        for (i in 0 until cr) {
                            scratch.indexlist[i] = (i * cr + x) * cr + (n - 1)
                            scratch.indexlist2[i] =
                                usage.blocks.blocks[b][i] * cr + (n - 1)
                        }
                        if (solverIntersect(usage, scratch.indexlist, scratch.indexlist2) != 0 ||
                            solverIntersect(usage, scratch.indexlist2, scratch.indexlist) != 0
                        ) {
                            diff = max(diff, DIFF_INTERSECT)
                            restart = true
                            break
                        }
                    }
                    if (restart) break
                }
                if (restart) break
            }
            if (restart) continue

            val diagIntersect = usage.diag
            if (diagIntersect != null) {
                for (b in 0 until cr) {
                    for (n in 1..cr) {
                        if (diagIntersect[n - 1] || usage.blk[b * cr + n - 1]) continue
                        for (i in 0 until cr) {
                            scratch.indexlist[i] = diag0(i, cr) * cr + (n - 1)
                            scratch.indexlist2[i] =
                                usage.blocks.blocks[b][i] * cr + (n - 1)
                        }
                        if (solverIntersect(usage, scratch.indexlist, scratch.indexlist2) != 0 ||
                            solverIntersect(usage, scratch.indexlist2, scratch.indexlist) != 0
                        ) {
                            diff = max(diff, DIFF_INTERSECT)
                            restart = true
                            break
                        }
                    }
                    if (restart) break
                }
            }
            if (restart) continue

            if (diagIntersect != null) {
                for (b in 0 until cr) {
                    for (n in 1..cr) {
                        if (diagIntersect[cr + n - 1] || usage.blk[b * cr + n - 1]) continue
                        for (i in 0 until cr) {
                            scratch.indexlist[i] = diag1(i, cr) * cr + (n - 1)
                            scratch.indexlist2[i] =
                                usage.blocks.blocks[b][i] * cr + (n - 1)
                        }
                        if (solverIntersect(usage, scratch.indexlist, scratch.indexlist2) != 0 ||
                            solverIntersect(usage, scratch.indexlist2, scratch.indexlist) != 0
                        ) {
                            diff = max(diff, DIFF_INTERSECT)
                            restart = true
                            break
                        }
                    }
                    if (restart) break
                }
            }
            if (restart) continue

            if (dlev.maxdiff <= DIFF_INTERSECT) break

            // Blockwise set elimination.
            for (b in 0 until cr) {
                for (i in 0 until cr) {
                    for (n in 1..cr) {
                        scratch.indexlist[i * cr + n - 1] =
                            usage.blocks.blocks[b][i] * cr + (n - 1)
                    }
                }
                val ret = solverSet(usage, scratch, scratch.indexlist)
                if (ret < 0) {
                    diff = DIFF_IMPOSSIBLE
                    result = true
                    break
                } else if (ret > 0) {
                    diff = max(diff, DIFF_SET)
                    restart = true
                    break
                }
            }
            if (result) break
            if (restart) continue

            // Row-wise set elimination.
            for (y in 0 until cr) {
                for (x in 0 until cr) {
                    for (n in 1..cr) {
                        scratch.indexlist[x * cr + n - 1] = (y * cr + x) * cr + (n - 1)
                    }
                }
                val ret = solverSet(usage, scratch, scratch.indexlist)
                if (ret < 0) {
                    diff = DIFF_IMPOSSIBLE
                    result = true
                    break
                } else if (ret > 0) {
                    diff = max(diff, DIFF_SET)
                    restart = true
                    break
                }
            }
            if (result) break
            if (restart) continue

            // Column-wise set elimination.
            for (x in 0 until cr) {
                for (y in 0 until cr) {
                    for (n in 1..cr) {
                        scratch.indexlist[y * cr + n - 1] = (y * cr + x) * cr + (n - 1)
                    }
                }
                val ret = solverSet(usage, scratch, scratch.indexlist)
                if (ret < 0) {
                    diff = DIFF_IMPOSSIBLE
                    result = true
                    break
                } else if (ret > 0) {
                    diff = max(diff, DIFF_SET)
                    restart = true
                    break
                }
            }
            if (result) break
            if (restart) continue

            val diagSet = usage.diag
            if (diagSet != null) {
                for (i in 0 until cr) {
                    for (n in 1..cr) {
                        scratch.indexlist[i * cr + n - 1] = diag0(i, cr) * cr + (n - 1)
                    }
                }
                val ret = solverSet(usage, scratch, scratch.indexlist)
                if (ret < 0) {
                    diff = DIFF_IMPOSSIBLE
                    result = true
                } else if (ret > 0) {
                    diff = max(diff, DIFF_SET)
                    restart = true
                }
            }
            if (result) break
            if (restart) continue

            if (diagSet != null) {
                for (i in 0 until cr) {
                    for (n in 1..cr) {
                        scratch.indexlist[i * cr + n - 1] = diag1(i, cr) * cr + (n - 1)
                    }
                }
                val ret = solverSet(usage, scratch, scratch.indexlist)
                if (ret < 0) {
                    diff = DIFF_IMPOSSIBLE
                    result = true
                } else if (ret > 0) {
                    diff = max(diff, DIFF_SET)
                    restart = true
                }
            }
            if (result) break
            if (restart) continue

            if (dlev.maxdiff <= DIFF_SET) break

            // Row-vs-column set elimination on a single number.
            for (n in 1..cr) {
                for (y in 0 until cr) {
                    for (x in 0 until cr) {
                        scratch.indexlist[y * cr + x] = (y * cr + x) * cr + (n - 1)
                    }
                }
                val ret = solverSet(usage, scratch, scratch.indexlist)
                if (ret < 0) {
                    diff = DIFF_IMPOSSIBLE
                    result = true
                    break
                } else if (ret > 0) {
                    diff = max(diff, DIFF_EXTREME)
                    restart = true
                    break
                }
            }
            if (result) break
            if (restart) continue

            if (solverForcing(usage, scratch) != 0) {
                diff = max(diff, DIFF_EXTREME)
                continue
            }

            break
        }

        /*
         * Recursion, `solo.c:2535-2630`. A square with at most one
         * candidate cannot reach this point after numeric elimination;
         * C asserts it. The port treats it as an impossible board
         * (D-024).
         */
        if (!result && dlev.maxdiff >= DIFF_RECURSIVE) {
            var best = -1
            var bestcount = cr + 1
            for (y in 0 until cr) {
                for (x in 0 until cr) {
                    if (grid[y * cr + x] != 0) continue
                    var count = 0
                    for (n in 1..cr) if (usage.cube(x, y, n)) count++
                    if (count <= 1) {
                        diff = DIFF_IMPOSSIBLE
                        best = -1
                        bestcount = 0
                        break
                    }
                    if (count < bestcount) {
                        bestcount = count
                        best = y * cr + x
                    }
                }
                if (bestcount == 0) break
            }

            if (bestcount != 0 && best != -1) {
                val y = best / cr
                val x = best % cr
                val list = IntArray(cr)
                var j = 0
                for (n in 1..cr) if (usage.cube(x, y, n)) list[j++] = n
                val ingrid = grid.copyOf()
                val outgrid = IntArray(cr * cr)
                diff = DIFF_IMPOSSIBLE
                for (i in 0 until j) {
                    ingrid.copyInto(outgrid)
                    outgrid[y * cr + x] = list[i]
                    solver(cr, blocks, kblocks, xtype, outgrid, kgrid, dlev)
                    if (diff == DIFF_IMPOSSIBLE && dlev.diff != DIFF_IMPOSSIBLE) {
                        outgrid.copyInto(grid)
                    }
                    when {
                        dlev.diff == DIFF_AMBIGUOUS -> diff = DIFF_AMBIGUOUS
                        dlev.diff == DIFF_IMPOSSIBLE -> Unit
                        else -> diff = if (diff == DIFF_IMPOSSIBLE) DIFF_RECURSIVE else DIFF_AMBIGUOUS
                    }
                    if (diff == DIFF_AMBIGUOUS) break
                }
            }
        } else if (!result && dlev.maxdiff < DIFF_RECURSIVE) {
            for (y in 0 until cr) {
                for (x in 0 until cr) {
                    if (grid[y * cr + x] == 0) diff = DIFF_IMPOSSIBLE
                }
            }
        }

        dlev.diff = diff
        dlev.kdiff = kdiff
    }

    // ------------------------------------------------------------------
    // Solved-grid generation, `solo.c:2682-3140`.
    // ------------------------------------------------------------------

    private class Gridgen(
        val cr: Int,
        val blocks: BlockStructure,
        val kblocks: BlockStructure?,
        val grid: IntArray,
        val random: RandomState,
    ) {
        val row = IntArray(cr)
        val col = IntArray(cr)
        val blk = IntArray(cr)
        val cge: IntArray? = if (kblocks != null) IntArray(kblocks.nrBlocks) else null
        var diag: IntArray? = null
        val sx = IntArray(max(cr * cr, 1))
        val sy = IntArray(max(cr * cr, 1))
        val sr = IntArray(max(cr * cr, 1))
        var nspaces = 0
        var steps = 0
    }

    /** `gridgen_place`, `solo.c:2728-2744`. */
    private fun gridgenPlace(usage: Gridgen, x: Int, y: Int, n: Int): Unit {
        val bit = 1 shl n
        val cr = usage.cr
        usage.row[y] = usage.row[y] or bit
        usage.col[x] = usage.col[x] or bit
        usage.blk[usage.blocks.whichBlock[y * cr + x]] =
            usage.blk[usage.blocks.whichBlock[y * cr + x]] or bit
        usage.cge?.let {
            it[usage.kblocks!!.whichBlock[y * cr + x]] =
                it[usage.kblocks!!.whichBlock[y * cr + x]] or bit
        }
        usage.diag?.let {
            if (ondiag0(y * cr + x, cr)) it[0] = it[0] or bit
            if (ondiag1(y * cr + x, cr)) it[1] = it[1] or bit
        }
        usage.grid[y * cr + x] = n
    }

    /** `gridgen_remove`, `solo.c:2746-2770`. */
    private fun gridgenRemove(usage: Gridgen, x: Int, y: Int, n: Int): Unit {
        val mask = (1 shl n).inv()
        val cr = usage.cr
        usage.row[y] = usage.row[y] and mask
        usage.col[x] = usage.col[x] and mask
        usage.blk[usage.blocks.whichBlock[y * cr + x]] =
            usage.blk[usage.blocks.whichBlock[y * cr + x]] and mask
        usage.cge?.let {
            it[usage.kblocks!!.whichBlock[y * cr + x]] =
                it[usage.kblocks!!.whichBlock[y * cr + x]] and mask
        }
        usage.diag?.let {
            if (ondiag0(y * cr + x, cr)) it[0] = it[0] and mask
            if (ondiag1(y * cr + x, cr)) it[1] = it[1] and mask
        }
        usage.grid[y * cr + x] = 0
    }

    /** `gridgen_real`, `solo.c:2772-2894`. */
    private fun gridgenReal(usage: Gridgen): Boolean {
        val cr = usage.cr
        if (usage.nspaces == 0) return true
        if (usage.steps <= 0) return false
        usage.steps--

        var bestm = cr + 1
        var bestr = 0
        var used = -1
        var sx = -1
        var sy = -1
        var bestIndex = -1
        for (j in 0 until usage.nspaces) {
            val x = usage.sx[j]
            val y = usage.sy[j]
            var usedXy = usage.row[y] or usage.col[x] or
                usage.blk[usage.blocks.whichBlock[y * cr + x]]
            usage.cge?.let {
                usedXy = usedXy or it[usage.kblocks!!.whichBlock[y * cr + x]]
                usedXy = usedXy or it[usage.kblocks!!.whichBlock[y * cr + x]]
            }
            usage.diag?.let {
                if (ondiag0(y * cr + x, cr)) usedXy = usedXy or it[0]
                if (ondiag1(y * cr + x, cr)) usedXy = usedXy or it[1]
            }
            var m = 0
            for (n in 1..cr) {
                val bit = 1 shl n
                if ((usedXy and bit) == 0) m++
            }
            if (m < bestm || (m == bestm && usage.sr[j] < bestr)) {
                bestm = m
                bestr = usage.sr[j]
                sx = x
                sy = y
                bestIndex = j
                used = usedXy
            }
        }

        if (bestIndex != usage.nspaces - 1) {
            val last = usage.nspaces - 1
            val tx = usage.sx[last]
            val ty = usage.sy[last]
            val tr = usage.sr[last]
            usage.sx[last] = usage.sx[bestIndex]
            usage.sy[last] = usage.sy[bestIndex]
            usage.sr[last] = usage.sr[bestIndex]
            usage.sx[bestIndex] = tx
            usage.sy[bestIndex] = ty
            usage.sr[bestIndex] = tr
        }

        val digits = IntArray(bestm)
        var j = 0
        for (n in 1..cr) {
            val bit = 1 shl n
            if ((used and bit) == 0) digits[j++] = n
        }
        Divvy.shuffle(digits, j, usage.random)

        for (i in 0 until j) {
            val n = digits[i]
            gridgenPlace(usage, sx, sy, n)
            usage.nspaces--
            if (gridgenReal(usage)) return true
            gridgenRemove(usage, sx, sy, n)
            usage.nspaces++
        }
        return false
    }

    /** `gridgen`, `solo.c:2896-2993`. */
    private fun gridgen(
        cr: Int,
        blocks: BlockStructure,
        kblocks: BlockStructure?,
        xtype: Boolean,
        grid: IntArray,
        random: RandomState,
        maxSteps: Int,
    ): Boolean {
        grid.fill(0)
        val usage = Gridgen(cr, blocks, kblocks, grid, random)
        if (xtype) usage.diag = IntArray(2)

        for (x in 0 until cr) grid[x] = x + 1
        Divvy.shuffle(grid, cr, random)
        for (x in 0 until cr) gridgenPlace(usage, x, 0, grid[x])

        usage.nspaces = 0
        for (y in 1 until cr) {
            for (x in 0 until cr) {
                usage.sx[usage.nspaces] = x
                usage.sy[usage.nspaces] = y
                usage.sr[usage.nspaces] = random.bits(31).toInt()
                usage.nspaces++
            }
        }
        usage.steps = maxSteps
        return gridgenReal(usage)
    }

    /** `check_valid`, `solo.c:3032-3140`. */
    private fun checkValid(
        cr: Int,
        blocks: BlockStructure,
        kblocks: BlockStructure?,
        kgrid: IntArray?,
        xtype: Boolean,
        grid: IntArray,
    ): Boolean {
        val used = BooleanArray(cr)
        for (y in 0 until cr) {
            used.fill(false)
            for (x in 0 until cr) {
                val value = grid[y * cr + x]
                if (value > 0 && value <= cr) used[value - 1] = true
            }
            for (n in 0 until cr) if (!used[n]) return false
        }
        for (x in 0 until cr) {
            used.fill(false)
            for (y in 0 until cr) {
                val value = grid[y * cr + x]
                if (value > 0 && value <= cr) used[value - 1] = true
            }
            for (n in 0 until cr) if (!used[n]) return false
        }
        for (i in 0 until cr) {
            used.fill(false)
            for (j in 0 until cr) {
                val value = grid[blocks.blocks[i][j]]
                if (value > 0 && value <= cr) used[value - 1] = true
            }
            for (n in 0 until cr) if (!used[n]) return false
        }
        if (kblocks != null) {
            for (i in 0 until kblocks.nrBlocks) {
                used.fill(false)
                for (j in 0 until kblocks.nrSquares[i]) {
                    val value = grid[kblocks.blocks[i][j]]
                    if (value > 0 && value <= cr) {
                        if (used[value - 1]) return false
                        used[value - 1] = true
                    }
                }
                if (kgrid != null && checkKillerCageSum(kblocks, kgrid, grid, i) != 1) {
                    return false
                }
            }
        }
        if (xtype) {
            used.fill(false)
            for (i in 0 until cr) {
                val value = grid[diag0(i, cr)]
                if (value > 0 && value <= cr) used[value - 1] = true
            }
            for (n in 0 until cr) if (!used[n]) return false
            used.fill(false)
            for (i in 0 until cr) {
                val value = grid[diag1(i, cr)]
                if (value > 0 && value <= cr) used[value - 1] = true
            }
            for (n in 0 until cr) if (!used[n]) return false
        }
        return true
    }

    /** `check_killer_cage_sum`, `solo.c:2995-3030`. */
    private fun checkKillerCageSum(
        kblocks: BlockStructure,
        kgrid: IntArray,
        grid: IntArray,
        block: Int,
    ): Int {
        var sum = 0
        var clue = 0
        for (i in 0 until kblocks.nrSquares[block]) {
            val xy = kblocks.blocks[block][i]
            if (grid[xy] == 0) return -1
            sum += grid[xy]
            if (kgrid[xy] != 0) clue = kgrid[xy]
        }
        /*
         * C asserts that a clue exists. A hand-written killer
         * description can have an unclued cage; the port uses the
         * release-build comparison `sum == clue` with clue 0 (D-024).
         */
        return if (sum == clue) 1 else 0
    }

    // ------------------------------------------------------------------
    // Description generation, `solo.c:3142-3896`.
    // ------------------------------------------------------------------

    /** `symmetries`, `solo.c:3142-3193`. */
    private fun symmetries(
        params: Params,
        x: Int,
        y: Int,
        output: IntArray,
        symmetry: Int,
    ): Int {
        val cr = params.c * params.r
        var i = 0
        fun add(ax: Int, ay: Int) {
            output[2 * i] = ax
            output[2 * i + 1] = ay
            i++
        }
        add(x, y)
        when (symmetry) {
            SYMM_NONE -> Unit
            SYMM_ROT2 -> add(cr - 1 - x, cr - 1 - y)
            SYMM_ROT4 -> {
                add(cr - 1 - y, x)
                add(y, cr - 1 - x)
                add(cr - 1 - x, cr - 1 - y)
            }

            SYMM_REF2 -> add(cr - 1 - x, y)
            SYMM_REF2D -> add(y, x)
            SYMM_REF4 -> {
                add(cr - 1 - x, y)
                add(x, cr - 1 - y)
                add(cr - 1 - x, cr - 1 - y)
            }

            SYMM_REF4D -> {
                add(y, x)
                add(cr - 1 - x, cr - 1 - y)
                add(cr - 1 - y, cr - 1 - x)
            }

            SYMM_REF8 -> {
                add(cr - 1 - x, y)
                add(x, cr - 1 - y)
                add(cr - 1 - x, cr - 1 - y)
                add(y, x)
                add(y, cr - 1 - x)
                add(cr - 1 - y, x)
                add(cr - 1 - y, cr - 1 - x)
            }
        }
        return i
    }

    /** `encode_solve_move`, `solo.c:3195-3237`. */
    private fun encodeSolveMove(cr: Int, grid: IntArray): String {
        val out = StringBuilder("S")
        for (i in 0 until cr * cr) {
            if (i > 0) out.append(',')
            out.append(grid[i])
        }
        return out.toString()
    }

    /** `encode_block_structure_desc`, `solo.c:3274-3326`. */
    private fun encodeBlockStructureDesc(out: StringBuilder, blocks: BlockStructure) {
        val cr = blocks.c * blocks.r
        var currrun = 0
        for (i in 0..2 * cr * (cr - 1)) {
            val edge: Boolean
            if (i == 2 * cr * (cr - 1)) {
                edge = true
            } else {
                val p0: Int
                val p1: Int
                if (i < cr * (cr - 1)) {
                    val y = i / (cr - 1)
                    val x = i % (cr - 1)
                    p0 = y * cr + x
                    p1 = p0 + 1
                } else {
                    val x = i / (cr - 1) - cr
                    val y = i % (cr - 1)
                    p0 = y * cr + x
                    p1 = p0 + cr
                }
                edge = blocks.whichBlock[p0] != blocks.whichBlock[p1]
            }
            if (edge) {
                while (currrun > 25) {
                    out.append('z')
                    currrun -= 25
                }
                if (currrun > 0) {
                    out.append(('a'.code - 1 + currrun).toChar())
                } else {
                    out.append('_')
                }
                currrun = 0
            } else {
                currrun++
            }
        }
    }

    /** `encode_grid`, `solo.c:3328-3367`. */
    private fun encodeGrid(out: StringBuilder, grid: IntArray, area: Int, start: Int) {
        var run = 0
        for (i in 0..area) {
            val n = if (i < area) grid[i] else -1
            if (n == 0) {
                run++
            } else {
                if (run > 0) {
                    while (run > 0) {
                        var ch = ('a'.code - 1 + run)
                        if (run > 26) ch = 'z'.code
                        out.append(ch.toChar())
                        run -= ch - ('a'.code - 1)
                    }
                } else {
                    if (out.length > start && n > 0) out.append('_')
                }
                if (n > 0) out.append(n)
                run = 0
            }
        }
    }

    /** `encode_puzzle_desc`, `solo.c:3387-3422`. */
    private fun encodePuzzleDesc(
        params: Params,
        grid: IntArray,
        blocks: BlockStructure,
        kgrid: IntArray?,
        kblocks: BlockStructure?,
    ): String {
        val cr = params.c * params.r
        val area = cr * cr
        val out = StringBuilder()
        encodeGrid(out, grid, area, 0)
        if (params.r == 1) {
            out.append(',')
            encodeBlockStructureDesc(out, blocks)
        }
        if (params.killer) {
            out.append(',')
            encodeBlockStructureDesc(out, kblocks!!)
            out.append(',')
            encodeGrid(out, kgrid!!, area, out.length)
        }
        return out.toString()
    }

    /** `merge_some_cages`, `solo.c:3452-3537`. */
    private fun mergeSomeCages(
        blocks: BlockStructure,
        cr: Int,
        area: Int,
        grid: IntArray,
        random: RandomState,
    ): Boolean {
        val pairs = IntArray(blocks.nrBlocks * blocks.nrBlocks * 2)
        var npairs = 0
        for (i in 0 until blocks.nrBlocks) {
            for (j in i + 1 until blocks.nrBlocks) {
                if (blocks.nrSquares[i] + blocks.nrSquares[j] > blocks.maxNrSquares) continue
                for (k in 0 until blocks.nrSquares[i]) {
                    val xy = blocks.blocks[i][k]
                    val y = xy / cr
                    val x = xy % cr
                    if ((y > 0 && blocks.whichBlock[xy - cr] == j) ||
                        (y + 1 < cr && blocks.whichBlock[xy + cr] == j) ||
                        (x > 0 && blocks.whichBlock[xy - 1] == j) ||
                        (x + 1 < cr && blocks.whichBlock[xy + 1] == j)
                    ) {
                        /*
                         * The vendored C writes the pair but never
                         * increments `npairs`, so this list stays empty
                         * and `merge_some_cages` always returns false.
                         * The port reproduces that: a generated killer
                         * puzzle keeps its initial random cages (D-024
                         * notes the behavior, and the DESC goldens pin
                         * it).
                         */
                        pairs[2 * npairs] = i
                        pairs[2 * npairs + 1] = j
                        break
                    }
                }
            }
        }

        while (npairs > 0) {
            val picked = random.upto(npairs.toLong()).toInt()
            val n1 = pairs[2 * picked]
            val n2 = pairs[2 * picked + 1]
            if (picked != npairs - 1) {
                pairs[2 * picked] = pairs[2 * (npairs - 1)]
                pairs[2 * picked + 1] = pairs[2 * (npairs - 1) + 1]
            }
            npairs--

            var digitsFound = 0
            for (i in 0 until blocks.nrSquares[n1]) {
                digitsFound = digitsFound or (1 shl grid[blocks.blocks[n1][i]])
            }
            var j = 0
            while (j < blocks.nrSquares[n2]) {
                if (digitsFound and (1 shl grid[blocks.blocks[n2][j]]) != 0) break
                j++
            }
            if (j != blocks.nrSquares[n2]) continue

            mergeBlocks(blocks, n1, n2)
            return true
        }
        return false
    }

    /** `compute_kclues`, `solo.c:3539-3555`. */
    private fun computeKclues(
        cages: BlockStructure,
        kclues: IntArray,
        grid: IntArray,
        area: Int,
    ) {
        kclues.fill(0)
        for (i in 0 until cages.nrBlocks) {
            var sum = 0
            for (j in 0 until area) {
                if (cages.whichBlock[j] == i) sum += grid[j]
            }
            var first = 0
            while (first < area && cages.whichBlock[first] != i) first++
            check(first != area) { "killer cage has no square" }
            kclues[first] = sum
        }
    }

    /** `gen_killer_cages`, `solo.c:3557-3628`. */
    private fun genKillerCages(
        cr: Int,
        random: RandomState,
        removeSingletons: Boolean,
    ): BlockStructure {
        val area = cr * cr
        val blocks = BlockStructure(1, cr, area, cr, area)
        for (i in 0 until area) blocks.whichBlock[i] = -1
        var nr = 0
        var nSingletons = 0
        for (y in 0 until cr) {
            for (x in 0 until cr) {
                val xy = y * cr + x
                if (blocks.whichBlock[xy] != -1) continue
                blocks.whichBlock[xy] = nr
                val rnd = random.bits(4).toInt()
                if (xy + 1 < area && (rnd >= 4 || (!removeSingletons && rnd >= 1))) {
                    var xy2 = xy + 1
                    if (x + 1 == cr || blocks.whichBlock[xy2] != -1 ||
                        (xy + cr < area && random.bits(1).toInt() == 0)
                    ) {
                        xy2 = xy + cr
                    }
                    if (xy2 >= area) {
                        nSingletons++
                    } else {
                        blocks.whichBlock[xy2] = nr
                    }
                } else {
                    nSingletons++
                }
                nr++
            }
        }
        blocks.nrBlocks = nr
        makeBlocksFromWhichBlock(blocks)
        var counted = 0
        for (i in 0 until blocks.nrBlocks) {
            if (blocks.nrSquares[i] == 1) counted++
        }
        check(counted == nSingletons) { "killer singleton count" }

        if (nSingletons > 0 && removeSingletons) {
            var n = 0
            while (n < blocks.nrBlocks) {
                if (blocks.nrSquares[n] > 1) {
                    n++
                    continue
                }
                val xy = blocks.blocks[n][0]
                val x = xy % cr
                val y = xy / cr
                val xy2: Int = when {
                    xy + 1 == area -> xy - 1
                    x + 1 < cr && (y + 1 == cr || random.bits(1).toInt() == 0) -> xy + 1
                    else -> xy + cr
                }
                val other = blocks.whichBlock[xy2]
                if (blocks.nrSquares[other] == 1) nSingletons--
                nSingletons--
                /*
                 * C has no capacity test here. When the selected
                 * neighbour is already a full cr-square cage, C writes
                 * `blocks[n][max_nr_squares]`, which is the first slot
                 * of the next block row, and the generated description
                 * can no longer be reloaded. The port abandons this
                 * attempt and retries from the advanced RNG state
                 * (D-025).
                 */
                if (blocks.nrSquares[n] + blocks.nrSquares[other] >
                    blocks.maxNrSquares
                ) {
                    throw KillerCageOverflow()
                }
                mergeBlocks(blocks, n, other)
                if (n < other) n++
            }
            check(nSingletons == 0) { "killer singletons remain" }
        }
        return blocks
    }

    /** The C singleton merge would write past a killer cage row. */
    private class KillerCageOverflow : RuntimeException()

    /** `game_configure` (`solo.c:450-495`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Columns of sub-blocks", p.c.toString()),
            ConfigItem.Text("Rows of sub-blocks", p.r.toString()),
            ConfigItem.Bool(
                "\"X\" (require every number in each main diagonal)",
                p.xtype,
            ),
            ConfigItem.Bool(
                "Jigsaw (irregularly shaped sub-blocks)",
                p.r == 1,
            ),
            ConfigItem.Bool("Killer (digit sums)", p.killer),
            ConfigItem.Choices(
                "Symmetry",
                ":None:2-way rotation:4-way rotation:2-way mirror:" +
                    "2-way diagonal mirror:4-way mirror:4-way diagonal mirror:" +
                    "8-way mirror",
                null,
                p.symm,
            ),
            ConfigItem.Choices(
                "Difficulty",
                ":Trivial:Basic:Intermediate:Advanced:Extreme:Unreasonable",
                null,
                p.diff,
            ),
        )
    }

    /**
     * `custom_params` (`solo.c:497-514`). Ticking Jigsaw folds the rows
     * into the columns and sets the rows to one; `kdiff` is always
     * `DIFF_KINTERSECT` and has no dialog field.
     */
    override fun customParams(items: List<ConfigItem>): Game.Params {
        var c = atoi((items[0] as ConfigItem.Text).value, 0)
        var r = atoi((items[1] as ConfigItem.Text).value, 0)
        val xtype = (items[2] as ConfigItem.Bool).value
        if ((items[3] as ConfigItem.Bool).value) {
            c *= r
            r = 1
        }
        return Params(
            c,
            r,
            (items[5] as ConfigItem.Choices).selected,
            (items[6] as ConfigItem.Choices).selected,
            xtype,
            (items[4] as ConfigItem.Bool).value,
        )
    }

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`solo.c:4613-4629`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Keep cursor after changing numbers",
                true,
                "pencil-keep-highlight",
            ),
        )

    /** `set_prefs` (`solo.c:4631-4634`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).pencilKeepHighlight = prefs.bool("pencil-keep-highlight")
    }

    /**
     * `new_game_desc`, `solo.c:3656-3896`.
     *
     * The generator retries the whole solved grid until the deduction
     * solver grades the puzzle at exactly the requested difficulty.
     * Killer puzzles merge cages greedily and keep the last grid that
     * reached the requested difficulty; the RNG order is therefore
     * observable in every description. D-034 refuses a symbol count
     * above the measured band for the difficulty before the first draw.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val c = p.c
        val r = p.r
        val cr = c * r
        val area = cr * cr
        if (r <= 0) throw IllegalArgumentException("solo: the grid has no cells")
        if (p.diff !in DIFF_BLOCK..DIFF_RECURSIVE) {
            throw IllegalArgumentException("solo: unknown difficulty")
        }
        if (p.kdiff !in DIFF_KSINGLE..DIFF_KINTERSECT) {
            throw IllegalArgumentException("solo: unknown killer difficulty")
        }
        if (p.symm !in SYMM_NONE..SYMM_REF8) {
            throw IllegalArgumentException("solo: unknown symmetry")
        }
        requireGeneratableSize(c, r, p.diff)

        val dlev = Difficulty(p.diff, p.kdiff)
        if ((c == 2 && r == 2) || (r == 1 && c < 4)) dlev.maxdiff = DIFF_BLOCK

        val grid = IntArray(area)
        val blocks = BlockStructure(c, r, area, cr, cr)
        var kblocks: BlockStructure? = null
        var kgrid: IntArray? = if (p.killer) IntArray(area) else null
        var aux: String? = null
        val grid2 = IntArray(area)
        val locsX = IntArray(area)
        val locsY = IntArray(area)

        while (true) {
            if (r == 1) {
                val dsf = Divvy.rectangle(cr, cr, cr, random)
                dsfToBlocks(dsf, blocks)
            } else {
                for (y in 0 until cr) {
                    for (x in 0 until cr) {
                        blocks.whichBlock[y * cr + x] = (y / c) * c + (x / r)
                    }
                }
            }
            makeBlocksFromWhichBlock(blocks)

            if (p.killer) {
                kblocks = try {
                    genKillerCages(cr, random, p.kdiff > DIFF_KSINGLE)
                } catch (e: KillerCageOverflow) {
                    continue
                }
            }

            if (!gridgen(cr, blocks, kblocks, p.xtype, grid, random, area * area)) {
                continue
            }
            check(checkValid(cr, blocks, kblocks, null, p.xtype, grid)) {
                "solo: the generator made an invalid grid"
            }

            aux = encodeSolveMove(cr, grid)

            if (p.killer) {
                var goodCages: BlockStructure? = null
                var lastCages: BlockStructure? = null
                var ntries = 0
                grid.copyInto(grid2)

                while (true) {
                    computeKclues(kblocks!!, kgrid!!, grid2, area)
                    grid.fill(0)
                    solver(cr, blocks, kblocks, p.xtype, grid, kgrid, dlev)
                    if (dlev.diff == dlev.maxdiff && dlev.kdiff == dlev.maxkdiff) {
                        goodCages = kblocks!!.dup()
                        ntries = 0
                        if (!mergeSomeCages(kblocks!!, cr, area, grid2, random)) break
                    } else if (dlev.diff > dlev.maxdiff || dlev.kdiff > dlev.maxkdiff) {
                        ntries++
                        if (ntries > 50) break
                        if (goodCages != null) {
                            kblocks = goodCages.dup()
                            if (!mergeSomeCages(kblocks!!, cr, area, grid2, random)) break
                        } else {
                            if (lastCages == null) break
                            kblocks = lastCages
                            lastCages = null
                        }
                    } else {
                        lastCages = kblocks!!.dup()
                        if (!mergeSomeCages(kblocks!!, cr, area, grid2, random)) break
                    }
                }

                if (goodCages != null) {
                    kblocks = goodCages
                    computeKclues(kblocks!!, kgrid!!, grid2, area)
                    grid.fill(0)
                    val desc = encodePuzzleDesc(p, grid, blocks, kgrid, kblocks)
                    return Description(desc, aux)
                }
                continue
            }

            var nlocs = 0
            val coords = IntArray(16)
            for (y in 0 until cr) {
                for (x in 0 until cr) {
                    val i = y * cr + x
                    val ncoords = symmetries(p, x, y, coords, p.symm)
                    var j = 0
                    while (j < ncoords) {
                        if (coords[2 * j + 1] * cr + coords[2 * j] < i) break
                        j++
                    }
                    if (j == ncoords) {
                        locsX[nlocs] = x
                        locsY[nlocs] = y
                        nlocs++
                    }
                }
            }

            // `shuffle(locs, nlocs, sizeof *locs, rs)`: one draw per
            // element after the first, swapping whole pairs.
            var i = nlocs
            while (i-- > 1) {
                val j = random.upto((i + 1).toLong()).toInt()
                if (j != i) {
                    val tx = locsX[i]
                    val ty = locsY[i]
                    locsX[i] = locsX[j]
                    locsY[i] = locsY[j]
                    locsX[j] = tx
                    locsY[j] = ty
                }
            }

            for (k in 0 until nlocs) {
                val x = locsX[k]
                val y = locsY[k]
                grid.copyInto(grid2)
                val ncoords = symmetries(p, x, y, coords, p.symm)
                for (j in 0 until ncoords) {
                    grid2[coords[2 * j + 1] * cr + coords[2 * j]] = 0
                }
                solver(cr, blocks, kblocks, p.xtype, grid2, kgrid, dlev)
                if (dlev.diff <= dlev.maxdiff &&
                    (!p.killer || dlev.kdiff <= dlev.maxkdiff)
                ) {
                    for (j in 0 until ncoords) {
                        grid[coords[2 * j + 1] * cr + coords[2 * j]] = 0
                    }
                }
            }

            grid.copyInto(grid2)
            solver(cr, blocks, kblocks, p.xtype, grid2, kgrid, dlev)
            if (dlev.diff == dlev.maxdiff &&
                (!p.killer || dlev.kdiff == dlev.maxkdiff)
            ) {
                break
            }
        }

        val desc = encodePuzzleDesc(p, grid, blocks, kgrid, kblocks)
        return Description(desc, aux)
    }

    // ------------------------------------------------------------------
    // Moves, `solo.c:4676-4905`.
    // ------------------------------------------------------------------

    private fun dupGame(from: State): State = State(
        from.cr,
        from.xtype,
        from.killer,
        from.grid.copyOf(),
        from.kgrid?.copyOf(),
        from.pencil.copyOf(),
        from.immutable.copyOf(),
        from.blocks,
        from.kblocks,
        from.shared,
        from.completed,
        from.cheated,
    )

    /** `execute_move`, `solo.c:4830-4905`. */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val cr = from.cr
        if (move.isEmpty()) return null

        if (move[0] == 'S') {
            val ret = dupGame(from)
            ret.completed = true
            ret.cheated = true
            var p = 1
            for (n in 0 until cr * cr) {
                val value = atoi(move, p)
                if (p >= move.length || value < 1 || value > cr) return null
                ret.grid[n] = value
                while (p < move.length && move[p] in '0'..'9') p++
                if (p < move.length && move[p] == ',') p++
            }
            return ret
        }

        if ((move[0] == 'P' || move[0] == 'R')) {
            val scan = scanThreeInts(move, 1)
            if (scan != null) {
                val x = scan[0]
                val y = scan[1]
                val n = scan[2]
                if (x in 0 until cr && y in 0 until cr && n in 0..cr) {
                    val ret = dupGame(from)
                    if (move[0] == 'P' && n > 0) {
                        val index = (y * cr + x) * cr + (n - 1)
                        ret.pencil[index] = !ret.pencil[index]
                    } else {
                        ret.grid[y * cr + x] = n
                        ret.pencil.fill(false, (y * cr + x) * cr, (y * cr + x) * cr + cr)
                        if (!ret.completed &&
                            checkValid(
                                cr,
                                ret.blocks,
                                ret.kblocks,
                                ret.kgrid,
                                ret.xtype,
                                ret.grid,
                            )
                        ) {
                            ret.completed = true
                        }
                    }
                    return ret
                }
            }
        }

        if (move[0] == 'M') {
            val ret = dupGame(from)
            for (y in 0 until cr) {
                for (x in 0 until cr) {
                    if (ret.grid[y * cr + x] == 0) {
                        for (i in 0 until cr) {
                            ret.pencil[(y * cr + x) * cr + i] = true
                        }
                    }
                }
            }
            return ret
        }
        return null
    }

    /** `solve_game`, `solo.c:4302-4341`. */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveOutcome(state, auxInfo).move

    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        solveOutcome(state, auxInfo)

    /**
     * `solve_game` (`solo.c:4301-4340`). C reads only `state`, the first
     * state of the history, and never `currstate` (`midend.c:2109`), so
     * a restart with a different description solves the first one. A
     * stored answer is returned as is. The solver reports
     * `DIFF_IMPOSSIBLE` and `DIFF_AMBIGUOUS`, with the two C messages.
     */
    override fun solveGame(
        state: Game.State,
        auxInfo: String?,
        initialState: Game.State,
    ): SolveOutcome = solveOutcome(initialState, auxInfo)

    private fun solveOutcome(state: Game.State, auxInfo: String?): SolveOutcome {
        if (auxInfo != null) return SolveOutcome(auxInfo, null)
        val s = state as State
        val cr = s.cr
        val grid = s.shared.initialGrid.copyOf()
        val dlev = Difficulty(DIFF_RECURSIVE, DIFF_KINTERSECT)
        solver(cr, s.blocks, s.kblocks, s.xtype, grid, s.kgrid, dlev)
        return when (dlev.diff) {
            DIFF_IMPOSSIBLE -> SolveOutcome(null, "No solution exists for this puzzle")
            DIFF_AMBIGUOUS -> SolveOutcome(null, "Multiple solutions exist for this puzzle")
            else -> SolveOutcome(encodeSolveMove(cr, grid), null)
        }
    }

    /** `grid_text_format`, `solo.c:4343-4528`. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        if (s.kblocks != null) return ""
        val cr = s.cr
        val blocks = s.blocks
        val xtype = s.xtype
        val grid = s.grid

        val vmod = if (blocks.r != 1) blocks.r else 1
        val hmod = if (blocks.r != 1) blocks.c else 1

        val linelen = 2 * (cr + (cr - 1) / vmod)
        val nlines = cr + (cr - 1) / hmod
        val total = linelen * nlines
        val out = StringBuilder(total + 1)

        for (y in 0 until cr) {
            for (x in 0 until cr) {
                val d = grid[y * cr + x]
                val ch = when {
                    d == 0 -> if (xtype && (ondiag0(y * cr + x, cr) || ondiag1(y * cr + x, cr))) {
                        '_'
                    } else {
                        '.'
                    }

                    d <= 9 -> '0' + d
                    else -> 'a' + d - 10
                }
                out.append(ch)
                if (x == cr - 1) {
                    out.append('\n')
                    continue
                }
                out.append(' ')
                if ((x + 1) % vmod != 0) continue
                out.append(
                    if (blocks.whichBlock[y * cr + x] != blocks.whichBlock[y * cr + x + 1]) '|' else ' ',
                )
                out.append(' ')
            }
            if (y == cr - 1 || (y + 1) % hmod != 0) continue

            for (x in 0 until cr) {
                var dwid = 2
                if (x == cr - 1) dwid--
                if (x > 0 && x % vmod == 0) dwid++
                val ch = if (blocks.whichBlock[y * cr + x] !=
                    blocks.whichBlock[(y + 1) * cr + x]
                ) {
                    '-'
                } else {
                    ' '
                }
                repeat(dwid) { out.append(ch) }
                if (x == cr - 1) {
                    out.append('\n')
                    break
                }
                if ((x + 1) % vmod != 0) continue
                val tl = blocks.whichBlock[y * cr + x]
                val tr = blocks.whichBlock[y * cr + x + 1]
                val bl = blocks.whichBlock[(y + 1) * cr + x]
                val br = blocks.whichBlock[(y + 1) * cr + x + 1]
                out.append(
                    when {
                        tl == tr && tr == bl && bl == br -> ' '
                        tl == bl && tr == br -> '|'
                        tl == tr && bl == br -> '-'
                        else -> '+'
                    },
                )
            }
        }
        check(out.length == total) { "solo: text length ${out.length} != $total" }
        return out.toString()
    }

    /** `game_can_format_as_text_now`, `solo.c:4530-4541`. */
    override fun canFormatAsTextNow(params: Game.Params): Boolean =
        !(params as Params).killer

    // ------------------------------------------------------------------
    // Frontend state, `solo.c:4590-4675`.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).hshow = visible
    }

    /** `game_changed_state`, `solo.c:4636-4655`. */
    override fun changedState(ui: Game.Ui, oldState: Game.State?, newState: Game.State): Boolean {
        val u = ui as Ui
        val s = newState as State
        if (u.hshow && u.hpencil && !u.hcursor &&
            s.grid[u.hy * s.cr + u.hx] != 0
        ) {
            u.hshow = false
            u.hpencil = false
        }
        return s.completed && !s.cheated && oldState != null &&
            !(oldState as State).completed
    }

    /** `game_status`, `solo.c:5436-5439`. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** `current_key_label`, `solo.c:4657-4663`. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        if (u.hshow && button == Buttons.CURSOR_SELECT) {
            return if (u.hpencil) "Ink" else "Pencil"
        }
        return ""
    }

    /** `game_request_keys`, `solo.c:3630-3654`. */
    override fun requestKeys(params: Game.Params): KeyRequest {
        val cr = (params as Params).c * params.r
        val labels = ArrayList<KeyLabel>(cr + 2)
        for (i in 0 until cr) {
            val button = if (i < 9) '1'.code + i else 'a'.code + i - 9
            // C stores a NULL label; `button2label` then uses the
            // button character.
            labels += KeyLabel(button, button.toChar().toString())
        }
        labels += KeyLabel('\b'.code, "Clear")
        labels += KeyLabel('M'.code, "Mark")
        return KeyRequest(labels, ArrowMode.ARROWS_LEFT)
    }

    // ------------------------------------------------------------------
    // Input, `solo.c:4676-4828`.
    // ------------------------------------------------------------------

    private fun moveCursor(ui: Ui, button: Int, cr: Int): MoveResult {
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
        ui.hx = min(max(ui.hx + dx, 0), cr - 1)
        ui.hy = min(max(ui.hy + dy, 0), cr - 1)
        if (!ui.hshow) {
            ui.hshow = true
            return MoveResult.UiUpdate
        }
        if (ui.hx != ox || ui.hy != oy) return MoveResult.UiUpdate
        return MoveResult.NoEffect
    }

    /** `interpret_move`, `solo.c:4676-4828`, with the `ANDROID` branch. */
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
        val cr = s.cr
        val key = Buttons.stripModifiers(button)
        val border = tileSize / 2
        val tx = (x + tileSize - border) / tileSize - 1
        val ty = (y + tileSize - border) / tileSize - 1

        if (tx in 0 until cr && ty in 0 until cr) {
            if (key == Buttons.LEFT_BUTTON) {
                if (s.immutable[ty * cr + tx]) {
                    u.hshow = false
                    u.hpencil = false
                } else if (tx == u.hx && ty == u.hy && u.hshow) {
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
                if (s.grid[ty * cr + tx] == 0) {
                    if (tx == u.hx && ty == u.hy && u.hshow && u.hpencil) {
                        u.hshow = false
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
            return moveCursor(u, key, cr)
        }

        if (u.hshow && key == Buttons.CURSOR_SELECT) {
            u.hpencil = !u.hpencil
            u.hcursor = true
            return MoveResult.UiUpdate
        }

        if (u.hshow) {
            var n = -1
            when {
                key in '0'.code..'9'.code && key - '0'.code <= cr -> n = key - '0'.code
                key in 'a'.code..'z'.code && key - 'a'.code + 10 <= cr ->
                    n = key - 'a'.code + 10

                key in 'A'.code..'Z'.code && key - 'A'.code + 10 <= cr ->
                    n = key - 'A'.code + 10

                key == Buttons.CURSOR_SELECT2 || key == '\b'.code -> n = 0
            }
            if (n >= 0) {
                if (s.immutable[u.hy * cr + u.hx]) return MoveResult.Unused
                if (u.hpencil && s.grid[u.hy * cr + u.hx] != 0) {
                    return MoveResult.Unused
                }
                if ((!u.hpencil || n == 0) &&
                    s.grid[u.hy * cr + u.hx] == n
                ) {
                    var anyPencil = false
                    for (i in 0 until cr) {
                        anyPencil = anyPencil ||
                            s.pencil[(u.hy * cr + u.hx) * cr + i]
                    }
                    if (!anyPencil) {
                        if (!u.hcursor && !u.pencilKeepHighlight) {
                            u.hshow = false
                            return MoveResult.UiUpdate
                        }
                        return MoveResult.NoEffect
                    }
                }
                val move = buildString {
                    append(if (u.hpencil && n > 0) 'P' else 'R')
                    append(u.hx).append(',').append(u.hy).append(',').append(n)
                }
                if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
                return MoveResult.Move(move)
            }
        }

        if (key == 'M'.code || key == 'm'.code) return MoveResult.Move("M")
        return MoveResult.Unused
    }

    // ------------------------------------------------------------------
    // Palette, layout, and drawing, `solo.c:4907-5439`.
    // ------------------------------------------------------------------

    private fun gridExtra(tileSize: Int): Int = max(tileSize / 32, 1)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val cr = (params as Params).c * params.r
        val side = cr * tileSize + 2 * (tileSize / 2) + 1
        return Size(side, side)
    }

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(3 * NCOLOURS)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        for (i in 0 until 3) {
            ret[COL_XDIAGONALS * 3 + i] = 0.9f * ret[COL_BACKGROUND * 3 + i]
            ret[COL_GRID * 3 + i] = 0.0f
            ret[COL_GRID_BLOCK * 3 + i] = 0.0f
            ret[COL_CLUE * 3 + i] = 0.0f
            ret[COL_HIGHLIGHT * 3 + i] = 0.78f * ret[COL_BACKGROUND * 3 + i]
            ret[COL_ERROR * 3 + i] = if (i == 0) 1.0f else 0.0f
            ret[COL_PENCIL * 3 + i] = 0.5f * ret[COL_BACKGROUND * 3 + i]
            ret[COL_KILLER * 3 + i] = 0.5f * ret[COL_BACKGROUND * 3 + i]
        }
        ret[COL_USER * 3 + 0] = 0.0f
        ret[COL_USER * 3 + 1] = 0.6f * ret[COL_BACKGROUND * 3 + 1]
        ret[COL_USER * 3 + 2] = 0.0f
        // The pencil blue channel keeps the full background; the killer
        // blue channel is one tenth.
        ret[COL_PENCIL * 3 + 2] = ret[COL_BACKGROUND * 3 + 2]
        ret[COL_KILLER * 3 + 2] = 0.1f * ret[COL_BACKGROUND * 3 + 2]
        return ret
    }

    /** `game_new_drawstate`, `solo.c:4970-4995`. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        val ds = DrawState(s.cr, s.xtype)
        ds.tileSize = tileSize
        ds.nregions = s.cr * 3 + 2 + (s.kblocks?.nrBlocks ?: 0)
        ds.enteredItems = IntArray(s.cr * ds.nregions)
        return ds
    }

    /** `draw_number`, `solo.c:5006-5287`. */
    private fun drawNumber(
        state: State,
        ds: DrawState,
        sink: DrawSink,
        x: Int,
        y: Int,
        hl: Int,
        tileSize: Int,
    ) {
        val cr = state.cr
        val border = tileSize / 2
        val g = gridExtra(tileSize)
        val crIndex = y * cr + x

        var unchanged = ds.grid[crIndex] == state.grid[crIndex] && ds.hl[crIndex] == hl
        if (unchanged) {
            for (i in 0 until cr) {
                if (ds.pencil[crIndex * cr + i] != state.pencil[crIndex * cr + i]) {
                    unchanged = false
                    break
                }
            }
        }
        if (unchanged) return

        val colKiller = if (hl and 32 != 0) COL_ERROR else COL_KILLER
        val tx = border + x * tileSize + 1 + g
        val ty = border + y * tileSize + 1 + g
        var cx = tx
        var cy = ty
        var tw = tileSize - 1 - 2 * g
        var th = tw
        var cw = tw
        var ch = th
        if (x > 0 && state.blocks.whichBlock[crIndex] ==
            state.blocks.whichBlock[y * cr + x - 1]
        ) {
            cx -= g
            cw += g
        }
        if (x + 1 < cr && state.blocks.whichBlock[crIndex] ==
            state.blocks.whichBlock[y * cr + x + 1]
        ) {
            cw += g
        }
        if (y > 0 && state.blocks.whichBlock[crIndex] ==
            state.blocks.whichBlock[(y - 1) * cr + x]
        ) {
            cy -= g
            ch += g
        }
        if (y + 1 < cr && state.blocks.whichBlock[crIndex] ==
            state.blocks.whichBlock[(y + 1) * cr + x]
        ) {
            ch += g
        }

        sink.clip(cx, cy, cw, ch)
        sink.rect(
            cx,
            cy,
            cw,
            ch,
            if (hl and 15 == 1) {
                COL_HIGHLIGHT
            } else if (state.xtype &&
                (ondiag0(crIndex, cr) || ondiag1(crIndex, cr))
            ) {
                COL_XDIAGONALS
            } else {
                COL_BACKGROUND
            },
        )

        if (hl and 15 == 2) {
            val coords = intArrayOf(cx, cy, cx + cw / 2, cy, cx, cy + ch / 2)
            sink.poly(coords, COL_HIGHLIGHT, COL_HIGHLIGHT)
        }

        if (x > 0 && y > 0 &&
            state.blocks.whichBlock[crIndex] !=
            state.blocks.whichBlock[(y - 1) * cr + x - 1]
        ) {
            sink.rect(tx - g, ty - g, g, g, COL_GRID_BLOCK)
        }
        if (x + 1 < cr && y > 0 &&
            state.blocks.whichBlock[crIndex] !=
            state.blocks.whichBlock[(y - 1) * cr + x + 1]
        ) {
            sink.rect(tx + tileSize - 1 - 2 * g, ty - g, g, g, COL_GRID_BLOCK)
        }
        if (x > 0 && y + 1 < cr &&
            state.blocks.whichBlock[crIndex] !=
            state.blocks.whichBlock[(y + 1) * cr + x - 1]
        ) {
            sink.rect(tx - g, ty + tileSize - 1 - 2 * g, g, g, COL_GRID_BLOCK)
        }
        if (x + 1 < cr && y + 1 < cr &&
            state.blocks.whichBlock[crIndex] !=
            state.blocks.whichBlock[(y + 1) * cr + x + 1]
        ) {
            sink.rect(
                tx + tileSize - 1 - 2 * g,
                ty + tileSize - 1 - 2 * g,
                g,
                g,
                COL_GRID_BLOCK,
            )
        }

        val kblocks = state.kblocks
        if (kblocks != null) {
            val t = g * 3
            val kcx: Int
            val kcy: Int
            val kcw: Int
            val kch: Int
            if (state.blocks.r == 1) {
                kcx = tx
                kcy = ty
                kcw = tw
                kch = th
            } else {
                kcx = cx
                kcy = cy
                kcw = cw
                kch = ch
            }
            var kl = kcx - 1
            var kt = kcy - 1
            var kr = kcx + kcw
            var kb = kcy + kch
            var hasLeft = false
            var hasRight = false
            var hasTop = false
            var hasBottom = false
            if (x == 0 ||
                kblocks.whichBlock[crIndex] != kblocks.whichBlock[y * cr + x - 1]
            ) {
                hasLeft = true
                kl += t
            }
            if (x + 1 >= cr ||
                kblocks.whichBlock[crIndex] != kblocks.whichBlock[y * cr + x + 1]
            ) {
                hasRight = true
                kr -= t
            }
            if (y == 0 ||
                kblocks.whichBlock[crIndex] != kblocks.whichBlock[(y - 1) * cr + x]
            ) {
                hasTop = true
                kt += t
            }
            if (y + 1 >= cr ||
                kblocks.whichBlock[crIndex] != kblocks.whichBlock[(y + 1) * cr + x]
            ) {
                hasBottom = true
                kb -= t
            }
            if (hasTop) sink.line(kl, kt, kr, kt, colKiller)
            if (hasBottom) sink.line(kl, kb, kr, kb, colKiller)
            if (hasLeft) sink.line(kl, kt, kl, kb, colKiller)
            if (hasRight) sink.line(kr, kt, kr, kb, colKiller)

            if (x > 0 && y > 0 && !hasLeft && !hasTop &&
                kblocks.whichBlock[crIndex] !=
                kblocks.whichBlock[(y - 1) * cr + x - 1]
            ) {
                sink.line(kl, kt + t, kl + t, kt + t, colKiller)
                sink.line(kl + t, kt, kl + t, kt + t, colKiller)
            }
            if (x + 1 < cr && y > 0 && !hasRight && !hasTop &&
                kblocks.whichBlock[crIndex] !=
                kblocks.whichBlock[(y - 1) * cr + x + 1]
            ) {
                sink.line(kcx + kcw - t, kt + t, kcx + kcw, kt + t, colKiller)
                sink.line(kcx + kcw - t, kt, kcx + kcw - t, kt + t, colKiller)
            }
            if (x > 0 && y + 1 < cr && !hasLeft && !hasBottom &&
                kblocks.whichBlock[crIndex] !=
                kblocks.whichBlock[(y + 1) * cr + x - 1]
            ) {
                sink.line(kl, kcy + kch - t, kl + t, kcy + kch - t, colKiller)
                sink.line(kl + t, kcy + kch - t, kl + t, kcy + kch, colKiller)
            }
            if (x + 1 < cr && y + 1 < cr && !hasRight && !hasBottom &&
                kblocks.whichBlock[crIndex] !=
                kblocks.whichBlock[(y + 1) * cr + x + 1]
            ) {
                sink.line(
                    kcx + kcw - t,
                    kcy + kch - t,
                    kcx + kcw - t,
                    kcy + kch,
                    colKiller,
                )
                sink.line(
                    kcx + kcw - t,
                    kcy + kch - t,
                    kcx + kcw,
                    kcy + kch - t,
                    colKiller,
                )
            }
        }

        val kgrid = state.kgrid
        if (state.killer && kgrid != null && kgrid[crIndex] != 0) {
            sink.text(
                tx + g * 4,
                ty + g * 4 + tileSize / 4,
                Text.FONT_VARIABLE,
                tileSize / 4,
                Text.ALIGN_VNORMAL or Text.ALIGN_HLEFT,
                colKiller,
                kgrid[crIndex].toString(),
            )
        }

        if (state.grid[crIndex] != 0) {
            val value = state.grid[crIndex]
            val text = if (value <= 9) {
                ('0' + value).toString()
            } else {
                ('a' + value - 10).toString()
            }
            sink.text(
                tx + tileSize / 2,
                ty + tileSize / 2,
                Text.FONT_VARIABLE,
                tileSize / 2,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                if (state.immutable[crIndex]) {
                    COL_CLUE
                } else if (hl and 16 != 0) {
                    COL_ERROR
                } else {
                    COL_USER
                },
                text,
            )
        } else {
            var npencil = 0
            for (i in 0 until cr) if (state.pencil[crIndex * cr + i]) npencil++
            if (npencil != 0) {
                val minph = 2
                var pl = tx + g
                var pr = pl + tileSize - g
                var pt = ty + g
                var pb = pt + tileSize - g
                if (state.killer) {
                    pl += g * 3
                    pr -= g * 3
                    pt += g * 3
                    pb -= g * 3
                    if (kgrid != null && kgrid[crIndex] != 0) pt += tileSize / 4
                }

                var bestsize = 0.0f
                var pbest = 1
                var pw = 3
                while (pw < max(npencil, 4)) {
                    var ph = (npencil + pw - 1) / pw
                    ph = max(ph, minph)
                    val fw = (pr - pl).toFloat() / pw
                    val fh = (pb - pt).toFloat() / ph
                    val fs = min(fw, fh)
                    if (fs >= bestsize) {
                        bestsize = fs
                        pbest = pw
                    }
                    pw++
                }
                pw = pbest
                var ph = (npencil + pw - 1) / pw
                ph = max(ph, minph)

                val fontsize = min((pr - pl) / pw, (pb - pt) / ph)
                pl = tx + (tileSize - fontsize * pw) / 2
                pt = ty + (tileSize - fontsize * ph) / 2
                if (state.killer && kgrid != null && kgrid[crIndex] != 0) {
                    pt = max(pt, ty + g * 3 + tileSize / 4)
                }

                var j = 0
                for (i in 0 until cr) {
                    if (!state.pencil[crIndex * cr + i]) continue
                    val dx = j % pw
                    val dy = j / pw
                    val text = (('1' + i)).toString()
                    val shown = if (text[0] > '9') {
                        ((text[0].code + 'a'.code - ('9'.code + 1)).toChar()).toString()
                    } else {
                        text
                    }
                    sink.text(
                        pl + fontsize * (2 * dx + 1) / 2,
                        pt + fontsize * (2 * dy + 1) / 2,
                        Text.FONT_VARIABLE,
                        fontsize,
                        Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                        COL_PENCIL,
                        shown,
                    )
                    j++
                }
            }
        }

        sink.unclip()
        sink.update(cx, cy, cw, ch)

        ds.grid[crIndex] = state.grid[crIndex]
        for (i in 0 until cr) {
            ds.pencil[crIndex * cr + i] = state.pencil[crIndex * cr + i]
        }
        ds.hl[crIndex] = hl
    }

    /**
     * `outline_block_structure`, `solo.c:5470-5628`, called on every
     * Android redraw with colour `COL_GRID_BLOCK` and no inset.
     */
    private fun outlineBlockStructure(
        state: State,
        sink: DrawSink,
        blocks: BlockStructure,
        ink: Int,
        inset: Int,
        tileSize: Int,
    ) {
        val cr = state.cr
        val border = tileSize / 2
        val coords = IntArray(4 * cr + 4)
        for (bi in 0 until blocks.nrBlocks) {
            if (blocks.nrSquares[bi] == 0) continue
            var start = -1
            for (i in 0 until blocks.nrSquares[bi]) {
                val j = blocks.blocks[bi][i]
                if (j % cr == 0 || blocks.whichBlock[j - 1] != bi) {
                    start = j
                    break
                }
            }
            check(start >= 0) { "block has no leftmost square" }
            var x = start % cr
            var y = start / cr
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
                if (tx in 0 until cr && ty in 0 until cr &&
                    blocks.whichBlock[ty * cr + tx] == bi
                ) {
                    nin++
                }
                tx = x - dy
                ty = y + dx
                if (tx in 0 until cr && ty in 0 until cr &&
                    blocks.whichBlock[ty * cr + tx] == bi
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
                val cx = ((2 * x + 1) + dy + dx) / 2
                val cy = ((2 * y + 1) - dx + dy) / 2
                coords[2 * n] = border + cx * tileSize - dx * inset
                coords[2 * n + 1] = border + cy * tileSize - dy * inset
                if (nin == 0) {
                    coords[2 * n] -= dy * inset
                    coords[2 * n + 1] += dx * inset
                } else if (nin == 2) {
                    coords[2 * n] += dy * inset
                    coords[2 * n + 1] -= dx * inset
                }
                n++
            } while (!(x == sx && y == sy && dx == sdx && dy == sdy))
            sink.thickPoly(coords.copyOf(2 * n), 3.0f, -1, ink)
        }
    }

    /** `game_redraw`, `solo.c:5294-5406`. */
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
        val cr = s.cr
        val g = gridExtra(tileSize)
        val border = tileSize / 2
        ds.tileSize = tileSize

        if (!ds.started) {
            sink.rect(
                border - g,
                border - g,
                cr * tileSize + 1 + 2 * g,
                cr * tileSize + 1 + 2 * g,
                COL_GRID,
            )
        }

        outlineBlockStructure(s, sink, s.blocks, COL_GRID_BLOCK, 0, tileSize)

        ds.enteredItems.fill(0, 0, cr * ds.nregions)
        for (x in 0 until cr) {
            for (y in 0 until cr) {
                val d = s.grid[y * cr + x]
                if (d != 0) {
                    ds.enteredItems[x * cr + d - 1]++
                    ds.enteredItems[(y + cr) * cr + d - 1]++
                    val box = s.blocks.whichBlock[y * cr + x]
                    ds.enteredItems[(box + 2 * cr) * cr + d - 1]++
                    if (ds.xtype) {
                        if (ondiag0(y * cr + x, cr)) {
                            ds.enteredItems[(3 * cr) * cr + d - 1]++
                        }
                        if (ondiag1(y * cr + x, cr)) {
                            ds.enteredItems[(3 * cr + 1) * cr + d - 1]++
                        }
                    }
                    s.kblocks?.let { kblocks ->
                        val kbox = kblocks.whichBlock[y * cr + x]
                        ds.enteredItems[(kbox + 3 * cr + 2) * cr + d - 1]++
                    }
                }
            }
        }

        for (x in 0 until cr) {
            for (y in 0 until cr) {
                var highlight = 0
                val d = s.grid[y * cr + x]
                if (x == u.hx && y == u.hy && u.hshow) {
                    highlight = if (u.hpencil) 2 else 1
                }
                if (d != 0) {
                    if (ds.enteredItems[x * cr + d - 1] > 1 ||
                        ds.enteredItems[(y + cr) * cr + d - 1] > 1 ||
                        ds.enteredItems[(s.blocks.whichBlock[y * cr + x] + 2 * cr) * cr + d - 1] > 1 ||
                        (ds.xtype &&
                            ((ondiag0(y * cr + x, cr) &&
                                ds.enteredItems[(3 * cr) * cr + d - 1] > 1) ||
                                (ondiag1(y * cr + x, cr) &&
                                    ds.enteredItems[(3 * cr + 1) * cr + d - 1] > 1))) ||
                        (s.kblocks != null &&
                            ds.enteredItems[(s.kblocks!!.whichBlock[y * cr + x] + 3 * cr + 2) * cr + d - 1] > 1)
                    ) {
                        highlight = highlight or 16
                    }
                }
                if (d != 0 && s.kblocks != null && s.kgrid != null) {
                    if (checkKillerCageSum(
                            s.kblocks!!,
                            s.kgrid,
                            s.grid,
                            s.kblocks!!.whichBlock[y * cr + x],
                        ) == 0
                    ) {
                        highlight = highlight or 32
                    }
                }
                drawNumber(s, ds, sink, x, y, highlight, tileSize)
            }
        }

        if (!ds.started) {
            val side = cr * tileSize + 2 * border + 1
            sink.update(0, 0, side, side)
            ds.started = true
        }
    }

    /** `game_get_cursor_location`, `solo.c:5423-5434`. */
    override fun cursorLocation(ui: Game.Ui, state: Game.State, tileSize: Int): Game.CursorRect? {
        val u = ui as Ui
        if (!u.hshow) return null
        val g = gridExtra(tileSize)
        val border = tileSize / 2
        return Game.CursorRect(
            border + u.hx * tileSize + 1 + g,
            border + u.hy * tileSize + 1 + g,
            tileSize,
            tileSize,
        )
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: the digit, the given, or the pencil marks, with
     * the killer cage sum where the board draws it, and the cursor with
     * its pencil mode. The numerals come from the keypad, so no unit
     * types a digit; the pointer actions are the two the game has. A
     * left click selects a free cell and switches the selected cell
     * between ink and pencil, and a right click starts or clears a
     * pencil selection. A given offers no action, because a click on it
     * only clears the highlight.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val cr = s.cr
        val t = tileSize
        val cells = gridSpeech(
            width = cr,
            height = cr,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val cell = y * cr + x
                val marks = (1..cr).filter { s.pencilAt(x, y, it) }
                var text = digitSpeech(s.grid[cell], s.immutable[cell], marks)
                val clue = s.killerClueAt(x, y)
                if (clue != 0) text += ", cage $clue"
                if (u.hshow && u.hx == x && u.hy == y) {
                    text += if (u.hpencil) ", cursor, pencil" else ", cursor"
                }
                text
            },
            actions = { x, y -> cellActions(s, u, x, y) },
        )
        return cells.capped(cells.units.getOrNull(u.hy * cr + u.hx))
    }

    /** The pointer buttons of one cell, as `interpret_move` reads them. */
    private fun cellActions(s: State, u: Ui, x: Int, y: Int): List<SpokenAction> {
        val cell = y * s.cr + x
        if (s.immutable[cell]) return emptyList()
        val selected = u.hshow && u.hx == x && u.hy == y
        val empty = s.grid[cell] == 0
        val actions = ArrayList<SpokenAction>(2)
        actions += SpokenAction(
            when {
                !selected -> "Select"
                u.hpencil -> "Ink"
                else -> "Pencil"
            },
            Buttons.LEFT_BUTTON,
        )
        // A right click marks an empty cell in pencil, and clears the
        // highlight of the selected pencil cell or of a filled cell.
        val right = when {
            !empty -> if (u.hshow) "Clear" else null
            selected && u.hpencil -> "Clear"
            else -> "Mark"
        }
        if (right != null) actions += SpokenAction(right, Buttons.RIGHT_BUTTON)
        return actions
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    /** C `atoi`: C whitespace, an optional sign, and saturating digits. */
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

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /** `sscanf(move, "%d,%d,%d")`: three C integers or null. */
    private fun scanThreeInts(text: String, start: Int): IntArray? {
        val out = IntArray(3)
        var i = start
        for (k in 0 until 3) {
            while (i < text.length && isCSpace(text[i])) i++
            var negative = false
            if (i < text.length && (text[i] == '+' || text[i] == '-')) {
                negative = text[i] == '-'
                i++
            }
            val digits = i
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
            if (i == digits) return null
            out[k] = if (overflow) {
                if (negative) Int.MIN_VALUE else Int.MAX_VALUE
            } else {
                val signed = if (negative) -value else value
                signed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
            if (k < 2) {
                if (i >= text.length || text[i] != ',') return null
                i++
            }
        }
        return out
    }
}
