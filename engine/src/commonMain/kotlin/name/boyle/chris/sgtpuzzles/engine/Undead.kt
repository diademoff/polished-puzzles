package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `undead.c`: Haunted Mirror Mazes.
 *
 * The board is a grid of left and right mirrors and monster cells. The
 * border carries sighting clues. A ghost is seen only through a mirror,
 * a vampire only without one, and a zombie is always seen.
 *
 * The rules, the seed, the description, and the save format are upstream
 * behavior. The generator draws one of five values per cell, builds the
 * ray paths, fills some paths from a unique-view enumeration, fills the
 * rest randomly, and accepts a board only when the iterative or the
 * brute-force solver classifies it at the requested difficulty.
 *
 * The code keeps the C structure on purpose:
 * - the grid draw, the path sort, the unique-view enumeration order, and
 *   the per-monster `random_upto(3)` order decide the description;
 * - `solved` and `cheated` are sticky, as `execute_move` only sets them;
 * - `solve` starts from the initial fixed values, not from the current
 *   board;
 * - the cell loop is column-major, and the hints are drawn in the sorted
 *   path order;
 * - the ghost, zombie, and vampire colours use the red channel of the
 *   background for every channel, as C does.
 *
 * The path arrays are sized `4 * wh`, where `wh = (w+2)*(h+2)`: a ray
 * can visit a cell twice, through the other channel of a mirror, and C
 * writes past its `wh`-sized arrays for a long path. That is undefined
 * behavior upstream. The port keeps every entry, caps a walk at `4 * wh`
 * entries, and never changes an accepted description.
 */
object Undead : Game {

    /** The three difficulties of `DIFFLIST`. */
    internal const val DIFF_EASY = 0
    internal const val DIFF_NORMAL = 1
    internal const val DIFF_TRICKY = 2
    internal const val DIFFCOUNT = 3
    private val DIFF_CHARS = charArrayOf('e', 'n', 't')
    internal val DIFF_NAMES = arrayOf("Easy", "Normal", "Tricky")

    /** The cell codes of `enum { CELL_EMPTY, ..., CELL_UNDEF }`. */
    internal const val CELL_EMPTY = 0
    internal const val CELL_MIRROR_L = 1
    internal const val CELL_MIRROR_R = 2
    internal const val CELL_GHOST = 3
    internal const val CELL_VAMPIRE = 4
    internal const val CELL_ZOMBIE = 5
    internal const val CELL_UNDEF = 6

    /** The walk directions of `enum { DIRECTION_NONE, ..., DIRECTION_DOWN }`. */
    internal const val DIRECTION_NONE = 0
    internal const val DIRECTION_UP = 1
    internal const val DIRECTION_RIGHT = 2
    internal const val DIRECTION_LEFT = 3
    internal const val DIRECTION_DOWN = 4

    /** The twelve colours of `undead.c:46-60`. */
    internal const val COL_BACKGROUND = 0
    internal const val COL_GRID = 1
    internal const val COL_TEXT = 2
    internal const val COL_ERROR = 3
    internal const val COL_HIGHLIGHT = 4
    internal const val COL_FLASH = 5
    internal const val COL_MONSTER_OUTLINE = 6
    internal const val COL_MONSTER_EYES = 7
    internal const val COL_GHOST = 8
    internal const val COL_ZOMBIE = 9
    internal const val COL_VAMPIRE = 10
    internal const val COL_DONE = 11
    internal const val NCOLOURS = 12

    private const val PREFERRED_TILE_SIZE = 64

    /** `struct game_params`. */
    data class Params(val w: Int, val h: Int, val diff: Int) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, diff)
    }

    /**
     * One ray path. C allocates `p`, `xy`, and `mapping` with `wh`
     * entries; the port allocates `4 * wh` and caps a walk there, because
     * a long path can exceed `wh` upstream. The arrays are written while
     * the path is built and then only read.
     */
    class Path internal constructor(capacity: Int) {
        var length: Int = 0
        val p: IntArray = IntArray(capacity)
        var gridStart: Int = -1
        var gridEnd: Int = -1
        var numMonsters: Int = 0
        val mapping: IntArray = IntArray(capacity)
        var sightingsStart: Int = 0
        var sightingsEnd: Int = 0
        val xy: IntArray = IntArray(capacity)
    }

    /**
     * The data shared by every state of one game, the counterpart of
     * `struct game_common`. Nothing writes it after `newState`.
     *
     * [initialGuess] is a port field. C's `solve_game` reads the first
     * state's `guess` for the fixed cells; the seam hands `solve` only
     * the current state, so the initial values live here.
     */
    class Common internal constructor(
        val params: Params,
        val wh: Int,
        val numGhosts: Int,
        val numVampires: Int,
        val numZombies: Int,
        val numTotal: Int,
        val numPaths: Int,
        val paths: List<Path>,
        val grid: IntArray,
        val xinfo: IntArray,
        val fixed: BooleanArray,
        val initialGuess: IntArray,
    ) {
        internal fun contentEquals(other: Common): Boolean =
            params == other.params &&
                wh == other.wh &&
                numGhosts == other.numGhosts &&
                numVampires == other.numVampires &&
                numZombies == other.numZombies &&
                numTotal == other.numTotal &&
                numPaths == other.numPaths &&
                grid.contentEquals(other.grid) &&
                xinfo.contentEquals(other.xinfo) &&
                fixed.contentEquals(other.fixed) &&
                initialGuess.contentEquals(other.initialGuess) &&
                paths.size == other.paths.size &&
                paths.indices.all { paths[it].contentEquals(other.paths[it]) }
    }

    private fun Path.contentEquals(other: Path): Boolean =
        length == other.length &&
            gridStart == other.gridStart &&
            gridEnd == other.gridEnd &&
            numMonsters == other.numMonsters &&
            sightingsStart == other.sightingsStart &&
            sightingsEnd == other.sightingsEnd &&
            p.contentEquals(other.p) &&
            xy.contentEquals(other.xy) &&
            mapping.contentEquals(other.mapping)

    /** `struct game_state`. */
    class State internal constructor(
        val common: Common,
        val guess: IntArray,
        val pencils: IntArray,
        val cellErrors: BooleanArray,
        val hintErrors: BooleanArray,
        val hintsDone: BooleanArray,
        val countErrors: BooleanArray,
        val solved: Boolean,
        val cheated: Boolean,
    ) : Game.State {

        val w: Int get() = common.params.w
        val h: Int get() = common.params.h

        override fun copy(): Game.State = State(
            common,
            guess.copyOf(),
            pencils.copyOf(),
            cellErrors.copyOf(),
            hintErrors.copyOf(),
            hintsDone.copyOf(),
            countErrors.copyOf(),
            solved,
            cheated,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                common.contentEquals(other.common) &&
                guess.contentEquals(other.guess) &&
                pencils.contentEquals(other.pencils) &&
                cellErrors.contentEquals(other.cellErrors) &&
                hintErrors.contentEquals(other.hintErrors) &&
                hintsDone.contentEquals(other.hintsDone) &&
                countErrors.contentEquals(other.countErrors) &&
                solved == other.solved &&
                cheated == other.cheated

        override fun hashCode(): Int {
            var result = common.params.hashCode()
            result = 31 * result + common.wh
            result = 31 * result + common.grid.contentHashCode()
            result = 31 * result + common.xinfo.contentHashCode()
            result = 31 * result + guess.contentHashCode()
            result = 31 * result + pencils.contentHashCode()
            result = 31 * result + cellErrors.contentHashCode()
            result = 31 * result + hintsDone.contentHashCode()
            result = 31 * result + solved.hashCode()
            result = 31 * result + cheated.hashCode()
            return result
        }
    }

    /** `struct game_ui`. */
    class Ui : Game.Ui {
        var hx: Int = 0
        var hy: Int = 0
        var hshow: Boolean = false
        var hpencil: Boolean = false
        var hcursor: Boolean = false
        var ascii: Boolean = false
        var pencilKeepHighlight: Boolean = true
    }

    /** The count-row hit boxes of `draw_monster_count`. */
    data class CountsGeometry(
        val y1: Int,
        val y2: Int,
        val ghostX1: Int,
        val ghostX2: Int,
        val vampireX1: Int,
        val vampireX2: Int,
        val zombieX1: Int,
        val zombieX2: Int,
    )

    /** The count-row geometry that both drawing and input use. */
    fun countsGeometry(tileSize: Int, w: Int): CountsGeometry {
        val t = tileSize
        val border = t / 4
        val y1 = 3 * t / 4 - 2 * t / 5
        val y2 = 3 * t / 4 + 2 * t / 5
        val mid = border + (w + 2) * t / 2 + t / 4
        val ghostX1 = mid - 3 * t / 2 - t / 3 - 2 * t / 5
        val ghostX2 = ghostX1 + 4 * t / 5
        val vampireX1 = mid - 4 * t / 5
        val vampireX2 = mid
        val zombieX1 = mid + 3 * t / 2 - t / 3 - 2 * t / 5
        val zombieX2 = zombieX1 + 4 * t / 5
        return CountsGeometry(
            y1,
            y2,
            ghostX1,
            ghostX2,
            vampireX1,
            vampireX2,
            zombieX1,
            zombieX2,
        )
    }

    /** `struct game_drawstate`. There are no blitters. */
    class DrawState internal constructor(
        val w: Int,
        val h: Int,
        numTotal: Int,
        numPaths: Int,
        wh: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        var solved: Boolean = false
        val monsters: IntArray = IntArray(numTotal) { 7 }
        val pencils: IntArray = IntArray(numTotal)
        val countErrors: BooleanArray = BooleanArray(3)
        val cellErrors: BooleanArray = BooleanArray(wh)
        val hintErrors: BooleanArray = BooleanArray(2 * numPaths)
        val hintsDone: BooleanArray = BooleanArray(2 * numPaths)
        var hx: Int = 0
        var hy: Int = 0
        var hshow: Boolean = false
        var hpencil: Boolean = false
        var hflash: Boolean = false
        var ascii: Boolean = false
        var counts: CountsGeometry? = null
    }

    /** One generated description and the number of attempts it took. */
    data class Generation(val desc: String, val attempts: Int)

    /** The solver result: a move, or one of the two C error strings. */
    data class SolveAttempt(val move: String?, val error: String?)

    override val name: String = "Undead"
    override val backendName: String = "undead"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /** `undead_presets` and `DEFAULT_PRESET`. */
    private val PRESETS = listOf(
        Params(4, 4, DIFF_EASY),
        Params(4, 4, DIFF_NORMAL),
        Params(4, 4, DIFF_TRICKY),
        Params(5, 5, DIFF_EASY),
        Params(5, 5, DIFF_NORMAL),
        Params(5, 5, DIFF_TRICKY),
        Params(7, 7, DIFF_EASY),
        Params(7, 7, DIFF_NORMAL),
    )

    override fun defaultParams(): Game.Params = Params(4, 4, DIFF_NORMAL)

    /** `game_fetch_preset`: `"%dx%d %s"` with the difficulty title. */
    override fun presets(): List<Preset> = PRESETS.map {
        Preset("${it.w}x${it.h} ${DIFF_NAMES[it.diff]}", encodeParams(it, true))
    }

    /**
     * `decode_params`: `atoi` for the width, a digit-only skip, an
     * optional lower-case `x` and height, then an optional `d` and one
     * consumed character compared with `e`, `n`, and `t`.
     */
    override fun decodeParams(text: String): Game.Params {
        val w = atoi(text, 0)
        var h = w
        var index = 0
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == 'x') {
            index++
            h = atoi(text, index)
            while (index < text.length && text[index] in '0'..'9') index++
        }
        var diff = DIFF_NORMAL
        if (index < text.length && text[index] == 'd') {
            index++
            for (i in 0 until DIFFCOUNT) {
                if (index < text.length && text[index] == DIFF_CHARS[i]) diff = i
            }
            if (index < text.length) index++
        }
        return Params(w, h, diff)
    }

    /**
     * `encode_params`: the short form drops the difficulty. C indexes the
     * character table without a bounds test; a hand-built params with an
     * out-of-range difficulty uses `n`, which no validated value reaches.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.w).append('x').append(p.h)
        if (full) {
            val diff = if (p.diff in 0 until DIFFCOUNT) p.diff else DIFF_NORMAL
            out.append('d').append(DIFF_CHARS[diff])
        }
        return out.toString()
    }

    /**
     * `validate_params`, in the C order. There is no lower bound on the
     * difficulty, and the area check is the integer division `54 / h`.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 3) return "Width must be at least 3"
        if (p.h < 3) return "Height must be at least 3"
        if (p.w > 54 / p.h) return "Grid is too big"
        if (p.diff >= DIFFCOUNT) return "Unknown difficulty rating"
        return null
    }

    /** `game_configure` (`undead.c:168-194`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Normal:Tricky",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`undead.c:196-204`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`undead.c:1692-1715`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Keep cursor after changing monsters",
                true,
                "pencil-keep-highlight",
            ),
            ConfigItem.Choices(
                "Monster representation",
                ":Pictures:Letters",
                ":pictures:letters",
                0,
                "monsters",
            ),
        )

    /** `set_prefs` (`undead.c:1717-1721`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.pencilKeepHighlight = prefs.bool("pencil-keep-highlight")
        u.ascii = prefs.choice("monsters") == 1
    }

    /** `new_game_desc`. `AUXINFO` is never set. */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val generation = generate(params as Params, random)
        return Description(generation.desc, null)
    }

    /** [newDesc] with the generation attempt count, for the tests. */
    fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params as Params, random)

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    /**
     * `new_game_desc`. One attempt draws and filters a grid; a reject
     * restarts the whole attempt, including the RNG stream. The accept
     * condition is the same difficulty classifier as C.
     */
    private fun generate(params: Params, random: RandomState): Generation {
        val w = params.w
        val h = params.h
        val wh = (w + 2) * (h + 2)
        val numPaths = w + h
        val capacity = 4 * wh
        var attempts = 0

        while (true) {
            attempts++
            val grid = IntArray(wh)
            val xinfo = IntArray(wh)
            val paths = Array(numPaths) { Path(capacity) }
            var total = 0

            // Fill the grid with random mirrors and empty monster cells.
            for (y in 1..h) {
                for (x in 1..w) {
                    val c = random.upto(5).toInt()
                    val index = x + y * (w + 2)
                    when {
                        c >= 2 -> {
                            grid[index] = CELL_EMPTY
                            xinfo[index] = total++
                        }

                        c == 0 -> {
                            grid[index] = CELL_MIRROR_L
                            xinfo[index] = -1
                        }

                        else -> {
                            grid[index] = CELL_MIRROR_R
                            xinfo[index] = -1
                        }
                    }
                }
            }

            if (total <= 4) continue
            val ratio = total.toFloat() / (w * h).toFloat()
            if (ratio < 0.48f || ratio > 0.78f) continue

            // Assign clue identifiers. C stores the direction value in the
            // border cell; the sightings later overwrite it.
            for (r in 0 until 2 * (w + h)) {
                val cell = range2grid(r, w, h)
                val index = cell.x + cell.y * (w + 2)
                grid[index] = cell.direction
                xinfo[index] = 0
            }
            grid[0] = 0
            xinfo[0] = 0
            grid[w + 1] = 0
            xinfo[w + 1] = 0
            grid[w + 1 + (h + 1) * (w + 2)] = 0
            xinfo[w + 1 + (h + 1) * (w + 2)] = 0
            grid[(h + 1) * (w + 2)] = 0
            xinfo[(h + 1) * (w + 2)] = 0

            val guess = IntArray(total) { 7 }
            val fixed = BooleanArray(total)

            makePaths(grid, xinfo, w, h, total, paths)

            val maxLength = when (params.diff) {
                DIFF_EASY -> minOf(w, h) + 1
                DIFF_NORMAL -> maxOf(w, h) * 3 / 2
                else -> 9
            }
            if (paths.any { it.numMonsters > maxLength }) continue

            val sorted = paths.sortedBy { it.numMonsters }.toMutableList()

            val filling = when (params.diff) {
                DIFF_EASY -> 2
                DIFF_NORMAL -> minOf(w + h, total / 2)
                DIFF_TRICKY -> maxOf(w + h, total / 2)
                else -> 0
            }

            var count = 0
            while (countMonsters(guess, total).none > filling) {
                if (count >= numPaths) break
                if (sorted[count].numMonsters == 0) {
                    count++
                    continue
                }
                getUnique(guess, sorted[count], random)
                count++
            }

            // Fill every remaining ambiguous entry with a random monster.
            for (g in 0 until total) {
                if (guess[g] == 7) {
                    guess[g] = when (random.upto(3).toInt()) {
                        0 -> 1
                        1 -> 2
                        else -> 4
                    }
                }
            }

            val counts = countMonsters(guess, total)
            val numGhosts = counts.ghosts
            val numVampires = counts.vampires
            val numZombies = counts.zombies

            if ((numGhosts == 0 && numVampires == 0) ||
                (numGhosts == 0 && numZombies == 0) ||
                (numVampires == 0 && numZombies == 0)
            ) {
                continue
            }
            if (params.diff == DIFF_TRICKY &&
                (numGhosts <= 1 || numVampires <= 1 || numZombies <= 1)
            ) {
                continue
            }

            // Copy the answer into the grid.
            for (x in 1..w) {
                for (y in 1..h) {
                    val index = x + y * (w + 2)
                    val c = xinfo[index]
                    if (c >= 0) {
                        grid[index] = when (guess[c]) {
                            1 -> CELL_GHOST
                            2 -> CELL_VAMPIRE
                            4 -> CELL_ZOMBIE
                            else -> grid[index]
                        }
                    }
                }
            }

            // Recompute the sightings and write them into the border.
            for (path in sorted) {
                path.sightingsStart = 0
                path.sightingsEnd = 0
                var mirror = false
                for (g in 0 until path.length) {
                    if (path.p[g] == -1) mirror = true
                    else {
                        when (guess[path.p[g]]) {
                            1 -> if (mirror) path.sightingsStart++
                            2 -> if (!mirror) path.sightingsStart++
                            4 -> path.sightingsStart++
                        }
                    }
                }
                mirror = false
                for (g in path.length - 1 downTo 0) {
                    if (path.p[g] == -1) mirror = true
                    else {
                        when (guess[path.p[g]]) {
                            1 -> if (mirror) path.sightingsEnd++
                            2 -> if (!mirror) path.sightingsEnd++
                            4 -> path.sightingsEnd++
                        }
                    }
                }
                val start = range2grid(path.gridStart, w, h)
                grid[start.x + start.y * (w + 2)] = path.sightingsStart
                val end = range2grid(path.gridEnd, w, h)
                grid[end.x + end.y * (w + 2)] = path.sightingsEnd
            }

            // Run the difficulty classifier.
            val oldGuess = IntArray(total) { 7 }
            guess.fill(7)
            var iterativeDepth = 0
            var solvedIterative: Boolean
            var containsInconsistency = false
            var countAmbiguous = 0

            while (true) {
                var noChange = true
                solvedIterative = solveIterative(
                    guess,
                    total,
                    sorted,
                    numGhosts,
                    numVampires,
                    numZombies,
                )
                iterativeDepth++
                for (p in 0 until total) {
                    if (guess[p] != oldGuess[p]) noChange = false
                    oldGuess[p] = guess[p]
                    if (guess[p] == 0) containsInconsistency = true
                }
                if (solvedIterative || noChange) break
            }

            var solvedBruteforce = false
            if (params.diff != DIFF_EASY && !solvedIterative &&
                !containsInconsistency
            ) {
                for (p in 0 until total) {
                    if (guess[p] != 1 && guess[p] != 2 && guess[p] != 4) {
                        countAmbiguous++
                    }
                }
                solvedBruteforce = solveBruteforce(
                    guess,
                    total,
                    sorted,
                    numGhosts,
                    numVampires,
                    numZombies,
                )
            }

            val accepted = when (params.diff) {
                DIFF_EASY -> solvedIterative && iterativeDepth <= 3 &&
                    !containsInconsistency

                DIFF_NORMAL -> ((solvedIterative && iterativeDepth > 3) ||
                    (solvedBruteforce && countAmbiguous < 4)) &&
                    !containsInconsistency

                DIFF_TRICKY -> solvedBruteforce && iterativeDepth > 0 &&
                    countAmbiguous >= 4 && !containsInconsistency

                else -> false
            }
            if (!accepted) continue

            // Encode the description.
            val out = StringBuilder()
            out.append(numGhosts).append(',')
            out.append(numVampires).append(',')
            out.append(numZombies).append(',')
            var run = 0
            for (y in 1..h) {
                for (x in 1..w) {
                    val c = grid[x + y * (w + 2)]
                    if (run > 25) {
                        out.append('z')
                        run -= 26
                    }
                    if (c != CELL_MIRROR_L && c != CELL_MIRROR_R) {
                        run++
                    } else if (c == CELL_MIRROR_L) {
                        if (run > 0) out.append(('a'.code + run - 1).toChar())
                        out.append('L')
                        run = 0
                    } else {
                        if (run > 0) out.append(('a'.code + run - 1).toChar())
                        out.append('R')
                        run = 0
                    }
                }
            }
            if (run > 0) out.append(('a'.code + run - 1).toChar())
            for (p in 0 until 2 * (w + h)) {
                val cell = range2grid(p, w, h)
                out.append(',').append(grid[cell.x + cell.y * (w + 2)])
            }
            return Generation(out.toString(), attempts)
        }
    }

    // ------------------------------------------------------------------
    // Paths and grid coordinates.
    // ------------------------------------------------------------------

    private class RangeCell(val x: Int, val y: Int, val direction: Int)

    /** `range2grid`: the border index to a padded cell and a direction. */
    private fun range2grid(rangeno: Int, width: Int, height: Int): RangeCell {
        if (rangeno < 0) return RangeCell(0, 0, DIRECTION_NONE)
        if (rangeno < width) return RangeCell(rangeno + 1, 0, DIRECTION_DOWN)
        var r = rangeno - width
        if (r < height) return RangeCell(width + 1, r + 1, DIRECTION_LEFT)
        r -= height
        if (r < width) return RangeCell(width - r, height + 1, DIRECTION_UP)
        r -= width
        if (r < height) return RangeCell(0, height - r, DIRECTION_RIGHT)
        return RangeCell(0, 0, DIRECTION_NONE)
    }

    /** `grid2range`: the border index of a padded cell, or -1. */
    private fun grid2range(x: Int, y: Int, w: Int, h: Int): Int {
        if (x > 0 && x < w + 1 && y > 0 && y < h + 1) return -1
        if (x < 0 || x > w + 1 || y < 0 || y > h + 1) return -1
        if ((x == 0 || x == w + 1) && (y == 0 || y == h + 1)) return -1
        if (y == 0) return x - 1
        if (x == w + 1) return y - 1 + w
        if (y == h + 1) return 2 * w + h - x
        return 2 * (w + h) - y
    }

    /** `num2grid`: a non-mirror cell index to a padded cell. */
    private fun num2grid(num: Int, width: Int): Pair<Int, Int> =
        (1 + num % width) to (1 + num / width)

    /**
     * `make_paths`. The inverse-path check uses every path already built,
     * so the start order and the resulting sort order are part of the
     * description. A walk is capped at the `4 * wh` array size; a closed
     * mirror loop is undefined behavior in C, and the port stops it with
     * the same defined end as a walk that returns to its start.
     */
    private fun makePaths(
        grid: IntArray,
        xinfo: IntArray,
        w: Int,
        h: Int,
        numTotal: Int,
        paths: Array<Path>,
    ) {
        val capacity = paths.firstOrNull()?.p?.size ?: 0
        var count = 0
        for (i in 0 until 2 * (w + h)) {
            var found = false
            for (j in 0 until count) {
                if (i == paths[j].gridEnd) {
                    found = true
                    break
                }
            }
            if (found) continue

            val path = paths[count]
            path.gridStart = i
            var cell = range2grid(i, w, h)
            var x = cell.x
            var y = cell.y
            var dir = cell.direction
            path.sightingsStart = grid[x + y * (w + 2)]

            var capped = false
            while (true) {
                when (dir) {
                    DIRECTION_DOWN -> y++
                    DIRECTION_LEFT -> x--
                    DIRECTION_UP -> y--
                    DIRECTION_RIGHT -> x++
                }
                val r = grid2range(x, y, w, h)
                if (r != -1) {
                    path.gridEnd = r
                    path.sightingsEnd = grid[x + y * (w + 2)]
                    break
                }
                if (path.length >= capacity) {
                    capped = true
                    break
                }
                val index = x + y * (w + 2)
                path.xy[path.length] = index
                val c = grid[index]
                if (c == CELL_MIRROR_L) {
                    path.p[path.length] = -1
                    dir = when (dir) {
                        DIRECTION_DOWN -> DIRECTION_RIGHT
                        DIRECTION_LEFT -> DIRECTION_UP
                        DIRECTION_UP -> DIRECTION_LEFT
                        else -> DIRECTION_DOWN
                    }
                } else if (c == CELL_MIRROR_R) {
                    path.p[path.length] = -1
                    dir = when (dir) {
                        DIRECTION_DOWN -> DIRECTION_LEFT
                        DIRECTION_LEFT -> DIRECTION_DOWN
                        DIRECTION_UP -> DIRECTION_RIGHT
                        else -> DIRECTION_UP
                    }
                } else {
                    path.p[path.length] = xinfo[index]
                }
                path.length++
            }
            if (capped) {
                // Closing the path here is a definition of the C hang; the
                // walk is deterministic and the 4*wh state space is
                // exhausted only when the ray is in a closed loop.
                path.gridEnd = path.gridStart
                path.sightingsEnd = path.sightingsStart
            }

            // Count the distinct monster ids on the path.
            path.numMonsters = 0
            for (j in 0 until numTotal) {
                var occurrences = 0
                for (k in 0 until path.length) {
                    if (path.p[k] == j) occurrences++
                }
                if (occurrences > 0) path.numMonsters++
            }

            // Build the distinct-id mapping in first-appearance order.
            var c = 0
            for (p in 0 until path.length) {
                val m = path.p[p]
                if (m == -1) continue
                var seen = false
                for (j in 0 until c) {
                    if (path.mapping[j] == m) seen = true
                }
                if (!seen) path.mapping[c++] = m
            }

            count++
        }
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    private class GuessList(var length: Int, val guess: IntArray, val possible: IntArray)

    private data class MonsterCounts(
        val ghosts: Int,
        val vampires: Int,
        val zombies: Int,
        val none: Int,
    )

    private fun countMonsters(guess: IntArray, numTotal: Int): MonsterCounts {
        var ghosts = 0
        var vampires = 0
        var zombies = 0
        var none = 0
        for (i in 0 until numTotal) {
            when (guess[i]) {
                1 -> ghosts++
                2 -> vampires++
                4 -> zombies++
                else -> none++
            }
        }
        return MonsterCounts(ghosts, vampires, zombies, none)
    }

    /**
     * `next_list`. The hand-written state machine is ported literally,
     * including its `pos == 0` special cases. A mask of 0 has no allowed
     * type: the caller initializes that slot to 0, and the machine
     * returns false without a read of an uninitialized value.
     */
    private fun nextList(g: GuessList, pos: Int): Boolean {
        if (pos < 0) return false
        val guess = g.guess
        val possible = g.possible

        if (pos == 0) {
            if ((guess[pos] == 1 && possible[pos] == 1) ||
                (guess[pos] == 2 && (possible[pos] == 3 || possible[pos] == 2)) ||
                guess[pos] == 4
            ) {
                return false
            }
            if (guess[pos] == 1 && (possible[pos] == 3 || possible[pos] == 7)) {
                guess[pos] = 2
                return true
            }
            if (guess[pos] == 1 && possible[pos] == 5) {
                guess[pos] = 4
                return true
            }
            if (guess[pos] == 2 && (possible[pos] == 6 || possible[pos] == 7)) {
                guess[pos] = 4
                return true
            }
        }

        if (guess[pos] == 1) {
            if (possible[pos] == 1) return nextList(g, pos - 1)
            if (possible[pos] == 3 || possible[pos] == 7) {
                guess[pos] = 2
                return true
            }
            if (possible[pos] == 5) {
                guess[pos] = 4
                return true
            }
        }

        if (guess[pos] == 2) {
            if (possible[pos] == 2) return nextList(g, pos - 1)
            if (possible[pos] == 3) {
                guess[pos] = 1
                return nextList(g, pos - 1)
            }
            if (possible[pos] == 6 || possible[pos] == 7) {
                guess[pos] = 4
                return true
            }
        }

        if (guess[pos] == 4) {
            if (possible[pos] == 5 || possible[pos] == 7) {
                guess[pos] = 1
                return nextList(g, pos - 1)
            }
            if (possible[pos] == 6) {
                guess[pos] = 2
                return nextList(g, pos - 1)
            }
            if (possible[pos] == 4) return nextList(g, pos - 1)
        }
        return false
    }

    private class ViewEntry(
        val guess: IntArray,
        val startView: Int,
        val endView: Int,
    )

    /**
     * `get_unique`: enumerate every allowed assignment, keep the first
     * assignment of each `(start view, end view)` pair, keep the pairs
     * that occurred once, and draw one of them. The C lists reverse twice,
     * so the unique pairs are in first-seen order and the draw picks the
     * c-th of them. A mask of 0 leaves the enumeration's first slot at 0,
     * as the port defines it.
     */
    private fun getUnique(guess: IntArray, path: Path, random: RandomState) {
        val n = path.numMonsters
        if (n == 0) return
        val pg = IntArray(n)
        val pp = IntArray(n)
        for (p in 0 until n) {
            val v = guess[path.mapping[p]]
            pp[p] = v
            pg[p] = when (v) {
                1, 3, 5, 7 -> 1
                2, 6 -> 2
                4 -> 4
                else -> 0
            }
        }

        val pathlimit = path.length + 1
        val viewCount = IntArray(pathlimit * pathlimit)
        val firstSeen = ArrayList<ViewEntry>()
        val loop = GuessList(n, pg, pp)

        do {
            var mirror = false
            var startView = 0
            for (p in 0 until path.length) {
                if (path.p[p] == -1) {
                    mirror = true
                } else {
                    for (i in 0 until n) {
                        if (path.p[p] == path.mapping[i]) {
                            if (pg[i] == 1 && mirror) startView++
                            if (pg[i] == 2 && !mirror) startView++
                            if (pg[i] == 4) startView++
                            break
                        }
                    }
                }
            }
            mirror = false
            var endView = 0
            for (p in path.length - 1 downTo 0) {
                if (path.p[p] == -1) {
                    mirror = true
                } else {
                    for (i in 0 until n) {
                        if (path.p[p] == path.mapping[i]) {
                            if (pg[i] == 1 && mirror) endView++
                            if (pg[i] == 2 && !mirror) endView++
                            if (pg[i] == 4) endView++
                            break
                        }
                    }
                }
            }
            val index = startView * pathlimit + endView
            viewCount[index]++
            if (viewCount[index] == 1) {
                firstSeen.add(ViewEntry(pg.copyOf(), startView, endView))
            }
        } while (nextList(loop, n - 1))

        val unique = firstSeen.filter {
            viewCount[it.startView * pathlimit + it.endView] == 1
        }
        if (unique.isNotEmpty()) {
            val choice = random.upto(unique.size.toLong()).toInt()
            val chosen = unique[choice]
            for (i in 0 until n) {
                guess[path.mapping[i]] = chosen.guess[i]
            }
        }
    }

    private fun checkNumbers(
        guess: IntArray,
        numTotal: Int,
        numGhosts: Int,
        numVampires: Int,
        numZombies: Int,
    ): Boolean {
        var ghosts = 0
        var vampires = 0
        var zombies = 0
        for (i in 0 until numTotal) {
            if (guess[i] == 1) ghosts++
            if (guess[i] == 2) vampires++
            if (guess[i] == 4) zombies++
        }
        return ghosts <= numGhosts && vampires <= numVampires &&
            zombies <= numZombies
    }

    private fun checkSolution(guess: IntArray, path: Path): Boolean {
        var count = 0
        var mirror = false
        for (i in 0 until path.length) {
            if (path.p[i] == -1) {
                mirror = true
            } else {
                val value = guess[path.p[i]]
                if (value == 1 && mirror) count++
                else if (value == 2 && !mirror) count++
                else if (value == 4) count++
            }
        }
        if (count != path.sightingsStart) return false

        count = 0
        mirror = false
        for (i in path.length - 1 downTo 0) {
            if (path.p[i] == -1) {
                mirror = true
            } else {
                val value = guess[path.p[i]]
                if (value == 1 && mirror) count++
                else if (value == 2 && !mirror) count++
                else if (value == 4) count++
            }
        }
        return count == path.sightingsEnd
    }

    /**
     * `solve_iterative`. The two results are separate: the boolean is the
     * "all cells decided" test, and the caller reads a 0 as an
     * inconsistency through the guess array.
     */
    private fun solveIterative(
        guess: IntArray,
        numTotal: Int,
        paths: List<Path>,
        numGhosts: Int,
        numVampires: Int,
        numZombies: Int,
    ): Boolean {
        var solved = true
        val tmpGuess = IntArray(numTotal)
        val possible = IntArray(numTotal)
        for (i in 0 until numTotal) possible[i] = 0

        for (path in paths) {
            if (path.numMonsters > 0) {
                val loopGuess = IntArray(path.numMonsters)
                val loopPossible = IntArray(path.numMonsters)
                for (i in 0 until path.numMonsters) {
                    val v = guess[path.mapping[i]]
                    loopGuess[i] = when (v) {
                        1, 3, 5, 7 -> 1
                        2, 6 -> 2
                        4 -> 4
                        else -> 0
                    }
                    loopPossible[i] = v
                    possible[path.mapping[i]] = 0
                }
                val loop = GuessList(path.numMonsters, loopGuess, loopPossible)
                while (true) {
                    for (i in 0 until numTotal) tmpGuess[i] = guess[i]
                    var count = 0
                    for (i in 0 until path.numMonsters) {
                        tmpGuess[path.mapping[i]] = loop.guess[count++]
                    }
                    if (checkNumbers(
                            tmpGuess,
                            numTotal,
                            numGhosts,
                            numVampires,
                            numZombies,
                        ) && checkSolution(tmpGuess, path)
                    ) {
                        for (j in 0 until path.numMonsters) {
                            val m = path.mapping[j]
                            possible[m] = possible[m] or loop.guess[j]
                        }
                    }
                    if (!nextList(loop, loop.length - 1)) break
                }
                for (i in 0 until path.numMonsters) {
                    val m = path.mapping[i]
                    guess[m] = guess[m] and possible[m]
                }
            }
        }

        for (i in 0 until numTotal) {
            if (guess[i] == 3 || guess[i] == 5 ||
                guess[i] == 6 || guess[i] == 7
            ) {
                solved = false
                break
            }
        }
        return solved
    }

    /** `solve_bruteforce`: stop at the second solution. */
    private fun solveBruteforce(
        guess: IntArray,
        numTotal: Int,
        paths: List<Path>,
        numGhosts: Int,
        numVampires: Int,
        numZombies: Int,
    ): Boolean {
        val loopGuess = IntArray(numTotal)
        val loopPossible = IntArray(numTotal)
        for (i in 0 until numTotal) {
            loopPossible[i] = guess[i]
            loopGuess[i] = when (guess[i]) {
                1, 3, 5, 7 -> 1
                2, 6 -> 2
                4 -> 4
                else -> 0
            }
        }
        val loop = GuessList(numTotal, loopGuess, loopPossible)

        var solved = false
        var numberSolutions = 0
        while (true) {
            var correct = true
            if (!checkNumbers(
                    loopGuess,
                    numTotal,
                    numGhosts,
                    numVampires,
                    numZombies,
                )
            ) {
                correct = false
            } else {
                for (path in paths) {
                    if (!checkSolution(loopGuess, path)) {
                        correct = false
                        break
                    }
                }
            }
            if (correct) {
                numberSolutions++
                solved = true
                if (numberSolutions > 1) {
                    solved = false
                    break
                }
                for (i in 0 until numTotal) guess[i] = loopGuess[i]
            }
            if (!nextList(loop, numTotal - 1)) break
        }
        return solved
    }

    // ------------------------------------------------------------------
    // Description parsing.
    // ------------------------------------------------------------------

    /**
     * `validate_desc`, in the C order. It checks only the number list, the
     * runs, the area, the total monster count, the clue count, and the
     * tail. A sign, a space, or a letter before a comma fails the first
     * loop, so a count cannot be negative or signed. An empty count or
     * clue is accepted as 0.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val target = p.w * p.h
        var index = 0

        for (i in 0 until 3) {
            if (index >= desc.length) return "Faulty game description"
            while (index < desc.length && desc[index] in '0'..'9') index++
            if (index >= desc.length || desc[index] != ',') {
                return "Invalid character in number list"
            }
            index++
        }

        index = 0
        var monsterCount = 0
        for (i in 0 until 3) {
            monsterCount += atoi(desc, index)
            while (index < desc.length && desc[index] in '0'..'9') index++
            index++
        }

        var area = 0
        var monsters = 0
        while (index < desc.length && desc[index] != ',') {
            val c = desc[index]
            when {
                c in 'a'..'z' -> {
                    area += c - 'a' + 1
                    monsters += c - 'a' + 1
                }

                c == 'G' || c == 'V' || c == 'Z' -> {
                    area++
                    monsters++
                }

                c == 'L' || c == 'R' -> area++
                else -> return "Invalid character in grid specification"
            }
            index++
        }
        if (area < target) return "Not enough data to fill grid"
        if (area > target) return "Too much data to fill grid"
        if (monsters != monsterCount) {
            return "Monster numbers do not match grid spaces"
        }

        for (i in 0 until 2 * (p.w + p.h)) {
            if (index >= desc.length) {
                return "Not enough numbers given after grid specification"
            }
            if (desc[index] != ',') return "Invalid character in number list"
            index++
            while (index < desc.length && desc[index] in '0'..'9') index++
        }

        if (index < desc.length) {
            return "Unexpected additional data at end of game description"
        }
        return null
    }

    /**
     * `new_game`. C assumes a validated description; the port stops at the
     * end of a shorter one instead of reading past it.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val h = p.h
        val wh = (w + 2) * (h + 2)
        val numPaths = w + h
        val capacity = 4 * wh
        val grid = IntArray(wh)
        val xinfo = IntArray(wh)

        var index = 0
        val numGhosts = atoi(desc, index)
        while (index < desc.length && desc[index] in '0'..'9') index++
        index++
        val numVampires = atoi(desc, index)
        while (index < desc.length && desc[index] in '0'..'9') index++
        index++
        val numZombies = atoi(desc, index)
        while (index < desc.length && desc[index] in '0'..'9') index++
        index++
        val numTotal = numGhosts + numVampires + numZombies

        val guess = IntArray(numTotal) { 7 }
        val pencils = IntArray(numTotal)
        val fixed = BooleanArray(numTotal)

        var count = 0
        var n = 0
        while (index < desc.length && desc[index] != ',') {
            when (val c = desc[index]) {
                'L' -> {
                    val (x, y) = num2grid(n, w)
                    val at = x + y * (w + 2)
                    if (at in grid.indices) grid[at] = CELL_MIRROR_L
                    if (at in xinfo.indices) xinfo[at] = -1
                    n++
                }

                'R' -> {
                    val (x, y) = num2grid(n, w)
                    val at = x + y * (w + 2)
                    if (at in grid.indices) grid[at] = CELL_MIRROR_R
                    if (at in xinfo.indices) xinfo[at] = -1
                    n++
                }

                'G', 'V', 'Z' -> {
                    val (x, y) = num2grid(n, w)
                    val at = x + y * (w + 2)
                    if (at in grid.indices) {
                        grid[at] = when (c) {
                            'G' -> CELL_GHOST
                            'V' -> CELL_VAMPIRE
                            else -> CELL_ZOMBIE
                        }
                    }
                    if (at in xinfo.indices) xinfo[at] = count
                    if (count < numTotal) {
                        guess[count] = when (c) {
                            'G' -> 1
                            'V' -> 2
                            else -> 4
                        }
                        fixed[count] = true
                    }
                    count++
                    n++
                }

                else -> {
                    var run = c - ('a' - 1)
                    while (run-- > 0) {
                        val (x, y) = num2grid(n, w)
                        val at = x + y * (w + 2)
                        if (at in grid.indices) grid[at] = CELL_EMPTY
                        if (at in xinfo.indices) xinfo[at] = count
                        if (count < numTotal) {
                            guess[count] = 7
                            fixed[count] = false
                        }
                        count++
                        n++
                    }
                }
            }
            index++
        }
        index++

        for (i in 0 until 2 * (w + h)) {
            val sights = atoi(desc, index)
            while (index < desc.length && desc[index] in '0'..'9') index++
            index++
            val cell = range2grid(i, w, h)
            val at = cell.x + cell.y * (w + 2)
            if (at in grid.indices) {
                grid[at] = sights
                xinfo[at] = -2
            }
        }

        grid[0] = 0
        xinfo[0] = -2
        grid[w + 1] = 0
        xinfo[w + 1] = -2
        grid[w + 1 + (h + 1) * (w + 2)] = 0
        xinfo[w + 1 + (h + 1) * (w + 2)] = -2
        grid[(h + 1) * (w + 2)] = 0
        xinfo[(h + 1) * (w + 2)] = -2

        val paths = Array(numPaths) { Path(capacity) }
        makePaths(grid, xinfo, w, h, numTotal, paths)
        val sorted = paths.sortedBy { it.numMonsters }
        val common = Common(
            p,
            wh,
            numGhosts,
            numVampires,
            numZombies,
            numTotal,
            numPaths,
            sorted,
            grid,
            xinfo,
            fixed,
            guess.copyOf(),
        )
        return State(
            common,
            guess,
            pencils,
            BooleanArray(wh),
            BooleanArray(2 * numPaths),
            BooleanArray(2 * numPaths),
            BooleanArray(3),
            solved = false,
            cheated = false,
        )
    }

    // ------------------------------------------------------------------
    // Solver seam and text.
    // ------------------------------------------------------------------

    /** `solve_game`: null on either C failure. */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state, auxInfo).move

    /** `solve_game` with the two C error strings (`undead.c:1529-1613`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state, auxInfo)
        return SolveOutcome(attempt.move, attempt.error)
    }

    /**
     * The solver result with the two C error strings. The seam has no
     * error channel, so a caller that needs the message uses this helper.
     */
    fun solveAttempt(state: Game.State, auxInfo: String?): SolveAttempt {
        val s = state as State
        val common = s.common
        val total = common.numTotal
        val guess = s.guess.copyOf()
        val oldGuess = IntArray(total)
        for (p in 0 until total) {
            if (common.fixed[p]) {
                oldGuess[p] = common.initialGuess[p]
                guess[p] = common.initialGuess[p]
            } else {
                oldGuess[p] = 7
                guess[p] = 7
            }
        }

        var iterativeDepth = 0
        var solvedIterative: Boolean
        var containsInconsistency = false
        var countAmbiguous = 0

        while (true) {
            var noChange = true
            solvedIterative = solveIterative(
                guess,
                total,
                common.paths,
                common.numGhosts,
                common.numVampires,
                common.numZombies,
            )
            iterativeDepth++
            for (p in 0 until total) {
                if (guess[p] != oldGuess[p]) noChange = false
                oldGuess[p] = guess[p]
                if (guess[p] == 0) containsInconsistency = true
            }
            if (solvedIterative || noChange || containsInconsistency) break
        }

        if (containsInconsistency) {
            return SolveAttempt(null, "Puzzle is inconsistent")
        }

        var solvedBruteforce = false
        if (!solvedIterative) {
            for (p in 0 until total) {
                if (guess[p] != 1 && guess[p] != 2 && guess[p] != 4) {
                    countAmbiguous++
                }
            }
            solvedBruteforce = solveBruteforce(
                guess,
                total,
                common.paths,
                common.numGhosts,
                common.numVampires,
                common.numZombies,
            )
        }

        if (!solvedIterative && !solvedBruteforce) {
            return SolveAttempt(null, "Puzzle is unsolvable")
        }

        val out = StringBuilder("S")
        for (i in 0 until total) {
            when (guess[i]) {
                1 -> out.append(";G").append(i)
                2 -> out.append(";V").append(i)
                4 -> out.append(";Z").append(i)
            }
        }
        return SolveAttempt(out.toString(), null)
    }

    /** `game_text_format`: the two counts line and the padded board. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val common = s.common
        val w = common.params.w
        val h = common.params.h
        val out = StringBuilder()
        out.append("G: ").append(common.numGhosts)
        out.append(" V: ").append(common.numVampires)
        out.append(" Z: ").append(common.numZombies).append("\n\n")

        for (y in 0 until h + 2) {
            for (x in 0 until w + 2) {
                val at = x + y * (w + 2)
                val c = common.grid[at]
                val xi = common.xinfo[at]
                val r = grid2range(x, y, w, h)
                when {
                    r != -1 -> out.append(c.toString().padStart(2, ' '))
                    c == CELL_MIRROR_L -> out.append(" \\")
                    c == CELL_MIRROR_R -> out.append(" /")
                    xi >= 0 -> {
                        when (s.guess[xi]) {
                            1 -> out.append(" G")
                            2 -> out.append(" V")
                            4 -> out.append(" Z")
                            else -> out.append(" .")
                        }
                    }

                    else -> out.append("  ")
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per play cell, then one unit per sighting clue on the four
     * border bands, then the three count markers above the board. A cell
     * speaks its monster or `empty`, with the pencil marks and `given` on
     * a fixed monster; a clue speaks its number and offers the `Done`
     * toggle, which is the real pointer move on a clue.
     *
     * A play cell offers no action: a click only moves the highlight and
     * the ghost, vampire, and zombie keys mark the highlighted cell, so
     * marking stays on the key path (DESIGN section 11).
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val common = s.common
        val w = common.params.w
        val h = common.params.h
        val t = tileSize
        val border = t / 4
        val cells = gridSpeech(
            width = w,
            height = h,
            tileSize = t,
            left = border + t,
            top = border + 2 * t,
            summary = statusText(state, ui),
            text = { x, y ->
                val at = (x + 1) + (y + 1) * (w + 2)
                val content = when (common.grid[at]) {
                    CELL_MIRROR_L -> "mirror left"
                    CELL_MIRROR_R -> "mirror right"
                    else -> monsterSpeech(s, common.xinfo[at])
                }
                val cursor = if (u.hshow && u.hx == x + 1 && u.hy == y + 1) {
                    ", cursor"
                } else {
                    ""
                }
                val wrong = if (s.cellErrors[at]) SPOKEN_WRONG else ""
                content + cursor + wrong
            },
        )

        val clues = buildList {
            for (x in 1..w) add(borderClue(s, x, 0, border, t, w, h))
            for (x in 1..w) add(borderClue(s, x, h + 1, border, t, w, h))
            for (y in 1..h) add(borderClue(s, 0, y, border, t, w, h))
            for (y in 1..h) add(borderClue(s, w + 1, y, border, t, w, h))
            addAll(countMarkers(s, t))
        }
        return cells.copy(units = cells.units + clues).capped(
            cells.units.getOrNull((u.hy - 1) * w + (u.hx - 1)),
        )
    }

    /** The monster in one play cell, or `empty`, in the game's words. */
    private fun monsterSpeech(s: State, monster: Int): String {
        if (monster < 0) return "empty"
        val content = when (s.guess[monster]) {
            1 -> "ghost"
            2 -> "vampire"
            4 -> "zombie"
            else -> "empty"
        }
        val given = if (s.common.fixed[monster]) ", given" else ""
        val marks = buildString {
            if (s.pencils[monster] and 1 != 0) append(" ghost")
            if (s.pencils[monster] and 2 != 0) append(" vampire")
            if (s.pencils[monster] and 4 != 0) append(" zombie")
        }
        val pencil = if (marks.isEmpty()) "" else ", pencil$marks"
        return content + given + pencil
    }

    /** One sighting clue on a border band, with the `Done` toggle. */
    private fun borderClue(
        s: State,
        x: Int,
        y: Int,
        border: Int,
        t: Int,
        w: Int,
        h: Int,
    ): SpokenUnit {
        val index = clueIndex(w, h, x, y)
        val done = s.hintsDone[index]
        val wrong = if (s.hintErrors[index]) SPOKEN_WRONG else ""
        val text = "clue ${s.common.grid[x + y * (w + 2)]}" +
            (if (done) ", done" else "") + wrong
        return SpokenUnit(
            row = 0,
            column = 0,
            left = border + x * t,
            top = border + (y + 1) * t,
            width = t,
            height = t,
            text = text,
            actions = listOf(
                SpokenAction(if (done) "Not done" else "Done", Buttons.LEFT_BUTTON),
            ),
        )
    }

    /**
     * The three count markers above the board: how many of each monster
     * the board has, and how many the player has placed.
     */
    private fun countMarkers(s: State, t: Int): List<SpokenUnit> {
        val geometry = countsGeometry(t, s.common.params.w)
        var ghosts = 0
        var vampires = 0
        var zombies = 0
        for (i in 0 until s.common.numTotal) {
            if (s.guess[i] == 1) ghosts++
            if (s.guess[i] == 2) vampires++
            if (s.guess[i] == 4) zombies++
        }
        val height = geometry.y2 - geometry.y1
        return listOf(
            SpokenUnit(
                0, 0,
                geometry.ghostX1, geometry.y1,
                geometry.ghostX2 - geometry.ghostX1, height,
                "ghosts, $ghosts of ${s.common.numGhosts}",
            ),
            SpokenUnit(
                0, 0,
                geometry.vampireX1, geometry.y1,
                geometry.vampireX2 - geometry.vampireX1, height,
                "vampires, $vampires of ${s.common.numVampires}",
            ),
            SpokenUnit(
                0, 0,
                geometry.zombieX1, geometry.y1,
                geometry.zombieX2 - geometry.zombieX1, height,
                "zombies, $zombies of ${s.common.numZombies}",
            ),
        )
    }

    /** `is_clue`: a border cell that is not a corner. */    private fun isClue(w: Int, h: Int, x: Int, y: Int): Boolean =
        ((x == 0 || x == w + 1) && y > 0 && y <= h) ||
            ((y == 0 || y == h + 1) && x > 0 && x <= w)

    /** `clue_index`: the border index of a clue cell. */
    private fun clueIndex(w: Int, h: Int, x: Int, y: Int): Int = when {
        y == 0 -> x - 1
        x == w + 1 -> w + y - 1
        y == h + 1 -> 2 * w + h - x
        x == 0 -> 2 * (w + h) - y
        else -> -1
    }

    /**
     * `interpret_move`. The count-row hit boxes come from
     * [countsGeometry], the same function the drawing uses: the seam's
     * `interpretMove` has no drawstate.
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
        val common = s.common
        val w = common.params.w
        val h = common.params.h
        val t = tileSize
        val border = t / 4

        val gx = (x - border - 1) / t
        val gy = (y - border - 2) / t - 1

        if (button == 'a'.code || button == 'A'.code) {
            u.ascii = !u.ascii
            return MoveResult.UiUpdate
        }
        if (button == 'm'.code || button == 'M'.code) {
            return MoveResult.Move("M")
        }

        val geometry = countsGeometry(t, w)
        val inRow = y > geometry.y1 && y < geometry.y2
        val onGhost = inRow && x > geometry.ghostX1 && x < geometry.ghostX2
        val onVampire = inRow && x > geometry.vampireX1 && x < geometry.vampireX2
        val onZombie = inRow && x > geometry.zombieX1 && x < geometry.zombieX2

        if (u.hshow && !u.hpencil) {
            val xi = common.xinfo[u.hx + u.hy * (w + 2)]
            if (xi >= 0 && !common.fixed[xi]) {
                if (button == 'g'.code || button == 'G'.code ||
                    button == '1'.code ||
                    (button == Buttons.LEFT_BUTTON && onGhost)
                ) {
                    if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
                    if (s.guess[xi] == 1) {
                        return if (u.hcursor) MoveResult.Unused else MoveResult.UiUpdate
                    }
                    return MoveResult.Move("G$xi")
                }
                if (button == 'v'.code || button == 'V'.code ||
                    button == '2'.code ||
                    (button == Buttons.LEFT_BUTTON && onVampire)
                ) {
                    if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
                    if (s.guess[xi] == 2) {
                        return if (u.hcursor) MoveResult.Unused else MoveResult.UiUpdate
                    }
                    return MoveResult.Move("V$xi")
                }
                if (button == 'z'.code || button == 'Z'.code ||
                    button == '3'.code ||
                    (button == Buttons.LEFT_BUTTON && onZombie)
                ) {
                    if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
                    if (s.guess[xi] == 4) {
                        return if (u.hcursor) MoveResult.Unused else MoveResult.UiUpdate
                    }
                    return MoveResult.Move("Z$xi")
                }
                if (button == 'e'.code || button == 'E'.code ||
                    button == Buttons.CURSOR_SELECT2 || button == '0'.code ||
                    button == '\b'.code
                ) {
                    if (!u.hcursor && !u.pencilKeepHighlight) u.hshow = false
                    if (s.guess[xi] == 7 && s.pencils[xi] == 0) {
                        return if (u.hcursor) MoveResult.Unused else MoveResult.UiUpdate
                    }
                    return MoveResult.Move("E$xi")
                }
            }
        }

        if (Buttons.isCursorMove(button)) {
            if (u.hx == 0 && u.hy == 0) {
                u.hx = 1
                u.hy = 1
            } else {
                when (button) {
                    Buttons.CURSOR_UP -> u.hy -= if (u.hy > 1) 1 else 0
                    Buttons.CURSOR_DOWN -> u.hy += if (u.hy < h) 1 else 0
                    Buttons.CURSOR_RIGHT -> u.hx += if (u.hx < w) 1 else 0
                    Buttons.CURSOR_LEFT -> u.hx -= if (u.hx > 1) 1 else 0
                }
            }
            u.hshow = true
            u.hcursor = true
            return MoveResult.UiUpdate
        }
        if (u.hshow && button == Buttons.CURSOR_SELECT) {
            u.hpencil = !u.hpencil
            u.hcursor = true
            return MoveResult.UiUpdate
        }

        if (u.hshow && u.hpencil) {
            val xi = common.xinfo[u.hx + u.hy * (w + 2)]
            if (xi >= 0 && !common.fixed[xi]) {
                var move: String? = null
                when {
                    button == 'g'.code || button == 'G'.code ||
                        button == '1'.code ||
                        (button == Buttons.LEFT_BUTTON && onGhost) -> move = "g$xi"

                    button == 'v'.code || button == 'V'.code ||
                        button == '2'.code ||
                        (button == Buttons.LEFT_BUTTON && onVampire) -> move = "v$xi"

                    button == 'z'.code || button == 'Z'.code ||
                        button == '3'.code ||
                        (button == Buttons.LEFT_BUTTON && onZombie) -> move = "z$xi"

                    button == 'e'.code || button == 'E'.code ||
                        button == Buttons.CURSOR_SELECT2 || button == '0'.code ||
                        button == '\b'.code -> {
                        if (s.pencils[xi] == 0) {
                            return if (u.hcursor) {
                                MoveResult.Unused
                            } else {
                                MoveResult.UiUpdate
                            }
                        }
                        move = "E$xi"
                    }
                }
                if (move != null) {
                    if (!u.hcursor && !u.pencilKeepHighlight) {
                        u.hpencil = false
                        u.hshow = false
                    }
                    return MoveResult.Move(move)
                }
            }
        }

        if (gx > 0 && gx < w + 1 && gy > 0 && gy < h + 1) {
            val xi = common.xinfo[gx + gy * (w + 2)]
            if (xi >= 0 && !common.fixed[xi]) {
                val g = s.guess[xi]
                if (!u.hshow) {
                    if (button == Buttons.LEFT_BUTTON) {
                        u.hshow = true
                        u.hpencil = false
                        u.hcursor = false
                        u.hx = gx
                        u.hy = gy
                        return MoveResult.UiUpdate
                    } else if (button == Buttons.RIGHT_BUTTON && g == 7) {
                        u.hshow = true
                        u.hpencil = true
                        u.hcursor = false
                        u.hx = gx
                        u.hy = gy
                        return MoveResult.UiUpdate
                    }
                } else {
                    if (button == Buttons.LEFT_BUTTON) {
                        if (!u.hpencil) {
                            if (gx == u.hx && gy == u.hy) {
                                // The Android branch toggles pencil mode.
                                u.hpencil = true
                                return MoveResult.UiUpdate
                            }
                            u.hshow = true
                            u.hpencil = false
                            u.hcursor = false
                            u.hx = gx
                            u.hy = gy
                            return MoveResult.UiUpdate
                        }
                        u.hshow = true
                        if (gx == u.hx && gy == u.hy) u.hpencil = false
                        u.hx = gx
                        u.hy = gy
                        return MoveResult.UiUpdate
                    } else if (button == Buttons.RIGHT_BUTTON) {
                        if (!u.hpencil && g == 7) {
                            u.hshow = true
                            u.hpencil = true
                            u.hcursor = false
                            u.hx = gx
                            u.hy = gy
                            return MoveResult.UiUpdate
                        }
                        if (gx == u.hx && gy == u.hy) {
                            u.hshow = false
                            u.hpencil = false
                            u.hcursor = false
                            u.hx = 0
                            u.hy = 0
                            return MoveResult.UiUpdate
                        }
                        if (g == 7) {
                            u.hshow = true
                            u.hpencil = true
                            u.hcursor = false
                            u.hx = gx
                            u.hy = gy
                            return MoveResult.UiUpdate
                        }
                    }
                }
            }
        } else if (button == Buttons.LEFT_BUTTON ||
            button == Buttons.RIGHT_BUTTON
        ) {
            if (isClue(w, h, gx, gy)) {
                return MoveResult.Move("D$gx,$gy")
            }
            u.hshow = false
            u.hpencil = false
            return MoveResult.UiUpdate
        }

        return MoveResult.Unused
    }

    /** `check_numbers_draw`: the count errors and the per-cell marks. */
    private fun checkNumbersDraw(state: State, guess: IntArray): Boolean {
        val common = state.common
        val w = common.params.w
        val h = common.params.h
        var ghosts = 0
        var vampires = 0
        var zombies = 0
        for (i in 0 until common.numTotal) {
            if (guess[i] == 1) ghosts++
            if (guess[i] == 2) vampires++
            if (guess[i] == 4) zombies++
        }
        var valid = true
        val filled = ghosts + vampires + zombies >= common.numTotal

        fun mark(type: Int, colour: Int) {
            state.countErrors[type] = true
            for (x in 1..w) {
                for (y in 1..h) {
                    val xy = x + y * (w + 2)
                    if (common.xinfo[xy] >= 0 && guess[common.xinfo[xy]] == colour) {
                        state.cellErrors[xy] = true
                    }
                }
            }
        }

        if (ghosts > common.numGhosts || (filled && ghosts != common.numGhosts)) {
            valid = false
            mark(0, 1)
        }
        if (vampires > common.numVampires ||
            (filled && vampires != common.numVampires)
        ) {
            valid = false
            mark(1, 2)
        }
        if (zombies > common.numZombies ||
            (filled && zombies != common.numZombies)
        ) {
            valid = false
            mark(2, 4)
        }
        return valid
    }

    /** `check_path_solution`: the two hint errors and the path cells. */
    private fun checkPathSolution(state: State, path: Path): Boolean {
        var count = 0
        var mirror = false
        var correct = true
        var unfilled = 0

        for (i in 0 until path.length) {
            if (path.p[i] == -1) {
                mirror = true
            } else {
                val v = state.guess[path.p[i]]
                if (v == 1 && mirror) count++
                else if (v == 2 && !mirror) count++
                else if (v == 4) count++
                else if (v == 7) unfilled++
            }
        }
        if (count > path.sightingsStart || count + unfilled < path.sightingsStart) {
            correct = false
            state.hintErrors[path.gridStart] = true
        }

        count = 0
        mirror = false
        unfilled = 0
        for (i in path.length - 1 downTo 0) {
            if (path.p[i] == -1) {
                mirror = true
            } else {
                val v = state.guess[path.p[i]]
                if (v == 1 && mirror) count++
                else if (v == 2 && !mirror) count++
                else if (v == 4) count++
                else if (v == 7) unfilled++
            }
        }
        if (count > path.sightingsEnd || count + unfilled < path.sightingsEnd) {
            correct = false
            state.hintErrors[path.gridEnd] = true
        }

        if (!correct) {
            for (i in 0 until path.length) {
                state.cellErrors[path.xy[i]] = true
            }
        }
        return correct
    }

    /**
     * `execute_move`. The separator is optional between tokens, a `D`
     * that does not name a clue is a bad move, and `S` marks the result
     * cheated. An empty move is a no-op copy with recomputed errors.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val common = s.common
        val total = common.numTotal
        val guess = s.guess.copyOf()
        val pencils = s.pencils.copyOf()
        val hintsDone = s.hintsDone.copyOf()
        var solver = false
        var index = 0

        while (index < move.length) {
            when (val c = move[index]) {
                'S' -> {
                    index++
                    solver = true
                }

                'G', 'V', 'Z', 'E', 'g', 'v', 'z' -> {
                    val letter = c
                    index++
                    val scanned = scanInt(move, index) ?: return null
                    index = scanned.end
                    val x = scanned.value
                    if (x < 0 || x >= total) return null
                    when (letter) {
                        'G' -> guess[x] = 1
                        'V' -> guess[x] = 2
                        'Z' -> guess[x] = 4
                        'E' -> {
                            guess[x] = 7
                            pencils[x] = 0
                        }

                        'g' -> pencils[x] = pencils[x] xor 1
                        'v' -> pencils[x] = pencils[x] xor 2
                        'z' -> pencils[x] = pencils[x] xor 4
                    }
                }

                'D' -> {
                    val x = scanInt(move, index + 1)
                    if (x == null || x.end >= move.length || move[x.end] != ',') {
                        return null
                    }
                    val y = scanInt(move, x.end + 1)
                    if (y == null) return null
                    if (!isClue(
                            common.params.w,
                            common.params.h,
                            x.value,
                            y.value,
                        )
                    ) {
                        return null
                    }
                    hintsDone[clueIndex(
                        common.params.w,
                        common.params.h,
                        x.value,
                        y.value,
                    )] = hintsDone[clueIndex(
                        common.params.w,
                        common.params.h,
                        x.value,
                        y.value,
                    )].not()
                    index = y.end
                }

                'M' -> {
                    for (i in 0 until total) {
                        if (guess[i] == 7) pencils[i] = 7
                    }
                    index++
                }

                else -> return null
            }
            if (index < move.length && move[index] == ';') index++
        }

        var correct = true
        val cellErrors = BooleanArray(common.wh)
        val hintErrors = BooleanArray(2 * common.numPaths)
        val countErrors = BooleanArray(3)
        val result = State(
            common,
            guess,
            pencils,
            cellErrors,
            hintErrors,
            hintsDone,
            countErrors,
            s.solved,
            s.cheated,
        )

        if (!checkNumbersDraw(result, result.guess)) correct = false
        for (path in common.paths) {
            if (!checkPathSolution(result, path)) correct = false
        }
        for (i in 0 until total) {
            if (guess[i] != 1 && guess[i] != 2 && guess[i] != 4) correct = false
        }
        if (correct && !solver) {
            return State(
                common,
                guess,
                pencils,
                cellErrors,
                hintErrors,
                hintsDone,
                countErrors,
                solved = true,
                cheated = result.cheated,
            )
        }
        if (solver) {
            return State(
                common,
                guess,
                pencils,
                cellErrors,
                hintErrors,
                hintsDone,
                countErrors,
                result.solved,
                cheated = true,
            )
        }
        return result
    }

    /** `game_status`: 1 when solved, 0 otherwise. */
    override fun status(state: Game.State): Int =
        if ((state as State).solved) 1 else 0

    /** `wants_statusbar` is false, so the status line is always empty. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    // ------------------------------------------------------------------
    // UI, keys, and preferences.
    // ------------------------------------------------------------------

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        val u = ui as Ui
        u.hshow = visible
        if (visible && u.hx == 0 && u.hy == 0) {
            u.hx = 1
            u.hy = 1
        }
    }

    /** `game_get_cursor_location`: the top clue row is part of the rect. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.hshow) return null
        val border = tileSize / 4
        return Game.CursorRect(
            border + u.hx * tileSize,
            border + (u.hy + 1) * tileSize,
            tileSize,
            tileSize,
        )
    }

    /** `game_request_keys`: five labels and `ANDROID_ARROWS_LEFT`. */
    override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
        listOf(
            KeyLabel('G'.code, "Ghost"),
            KeyLabel('V'.code, "Vampire"),
            KeyLabel('Z'.code, "Zombie"),
            KeyLabel('\b'.code, "Clear"),
            KeyLabel('M'.code, "Mark"),
        ),
        ArrowMode.ARROWS_LEFT,
    )

    /**
     * `current_key_label`. C reads `xinfo` for `CURSOR_SELECT2` without
     * requiring a visible highlight; the port keeps that quirk and bounds
     * the index.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (u.hshow && button == Buttons.CURSOR_SELECT) {
            return if (u.hpencil) "Ink" else "Pencil"
        }
        if (button == Buttons.CURSOR_SELECT2) {
            val at = u.hx + u.hy * (s.common.params.w + 2)
            if (at in s.common.xinfo.indices) {
                val xi = s.common.xinfo[at]
                if (xi >= 0 && !s.common.fixed[xi]) return "Clear"
            }
        }
        return ""
    }

    /** `game_changed_state`: cancel pencil mode and report a real win. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val next = newState as State
        if (u.hshow && u.hpencil && !u.hcursor) {
            val at = u.hx + u.hy * (next.common.params.w + 2)
            if (at in next.common.xinfo.indices) {
                val xi = next.common.xinfo[at]
                if (xi >= 0 && xi < next.common.numTotal) {
                    val g = next.guess[xi]
                    if (g == 1 || g == 2 || g == 4) u.hshow = false
                }
            }
        }
        val previous = oldState as State?
        return next.solved && !next.cheated &&
            previous != null && !previous.solved
    }

    // ------------------------------------------------------------------
    // Geometry and colours.
    // ------------------------------------------------------------------

    /** `game_compute_size`: the sides are truncated separately. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 4
        return Size(
            2 * border + (p.w + 2) * tileSize,
            2 * border + (p.h + 3) * tileSize,
        )
    }

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`. The ghost, zombie, and vampire formulas use the red
     * channel of the background for every channel, as C does; the
     * highlight and the done colour are per channel.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        setColour(ret, COL_GRID, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_TEXT, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_ERROR, 1.0f, 0.0f, 0.0f)
        for (i in 0 until 3) {
            ret[COL_HIGHLIGHT * 3 + i] = 0.78f * ret[COL_BACKGROUND * 3 + i]
        }
        setColour(ret, COL_FLASH, 1.0f, 1.0f, 1.0f)
        setColour(ret, COL_MONSTER_OUTLINE, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_MONSTER_EYES, 1.0f, 1.0f, 1.0f)
        val red = ret[COL_BACKGROUND * 3]
        setColour(ret, COL_GHOST, red * 0.5f, red, red)
        setColour(ret, COL_ZOMBIE, red * 0.5f, red, red * 0.5f)
        setColour(ret, COL_VAMPIRE, red, red * 0.9f, red * 0.9f)
        for (i in 0 until 3) {
            ret[COL_DONE * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 1.5f
        }
        return ret
    }

    private fun setColour(
        ret: FloatArray,
        index: Int,
        r: Float,
        g: Float,
        b: Float,
    ) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(
            s.common.params.w,
            s.common.params.h,
            s.common.numTotal,
            s.common.numPaths,
            s.common.wh,
        )
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

    // ------------------------------------------------------------------
    // Drawing.
    // ------------------------------------------------------------------

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
        val common = s.common
        val w = common.params.w
        val h = common.params.h
        val t = tileSize
        val border = t / 4
        val hflash = false

        if (!ds.started) {
            sink.rect(
                border + t - 1,
                border + 2 * t - 1,
                w * t + 3,
                h * t + 3,
                COL_GRID,
            )
            for (i in 0 until w) {
                for (j in 0 until h) {
                    sink.rect(
                        border + t * (i + 1) + 1,
                        border + t * (j + 2) + 1,
                        t - 1,
                        t - 1,
                        COL_BACKGROUND,
                    )
                }
            }
            sink.update(
                0,
                0,
                2 * border + (w + 2) * t,
                2 * border + (h + 3) * t,
            )
        }

        val hchanged = ds.hx != u.hx || ds.hy != u.hy ||
            ds.hshow != u.hshow || ds.hpencil != u.hpencil

        val changedAscii = ds.ascii != u.ascii
        ds.ascii = u.ascii

        for (i in 0 until 3) {
            var stale = !ds.started
            if (ds.hflash != hflash) stale = true
            if (changedAscii) stale = true
            if (ds.countErrors[i] != s.countErrors[i]) {
                stale = true
                ds.countErrors[i] = s.countErrors[i]
            }
            if (stale) drawMonsterCount(sink, ds, s, i, hflash, t)
        }

        for (path in common.paths) {
            if (isHintStale(ds, hflash, s, path.gridStart)) {
                drawPathHint(sink, ds, common, path.gridStart, hflash, path.sightingsStart, t)
            }
            if (isHintStale(ds, hflash, s, path.gridEnd)) {
                drawPathHint(sink, ds, common, path.gridEnd, hflash, path.sightingsEnd, t)
            }
        }

        for (x in 1..w) {
            for (y in 1..h) {
                var stale = false
                val xy = x + y * (w + 2)
                val xi = common.xinfo[xy]
                val c = common.grid[xy]

                if (!ds.started) stale = true
                if (ds.hflash != hflash) stale = true
                if (changedAscii) stale = true
                if (hchanged) {
                    if ((x == u.hx && y == u.hy) || (x == ds.hx && y == ds.hy)) {
                        stale = true
                    }
                }
                if (xi >= 0 && s.guess[xi] != ds.monsters[xi]) {
                    stale = true
                    ds.monsters[xi] = s.guess[xi]
                }
                if (xi >= 0 && s.pencils[xi] != ds.pencils[xi]) {
                    stale = true
                    ds.pencils[xi] = s.pencils[xi]
                }
                if (s.cellErrors[xy] != ds.cellErrors[xy]) {
                    stale = true
                    ds.cellErrors[xy] = s.cellErrors[xy]
                }

                if (stale) {
                    drawCellBackground(sink, u, x, y, t)
                    if (xi < 0) {
                        drawMirror(sink, x, y, hflash, c, t)
                    } else if (s.guess[xi] == 1 || s.guess[xi] == 2 ||
                        s.guess[xi] == 4
                    ) {
                        drawBigMonster(sink, ds, x, y, hflash, s.guess[xi], t)
                    } else {
                        drawPencils(sink, ds, x, y, s.pencils[xi], t)
                    }
                }
            }
        }

        ds.hx = u.hx
        ds.hy = u.hy
        ds.hshow = u.hshow
        ds.hpencil = u.hpencil
        ds.hflash = hflash
        ds.started = true
    }

    private fun isHintStale(
        ds: DrawState,
        hflash: Boolean,
        state: State,
        index: Int,
    ): Boolean {
        var ret = false
        if (!ds.started) ret = true
        if (ds.hflash != hflash) ret = true
        if (ds.hintErrors[index] != state.hintErrors[index]) {
            ds.hintErrors[index] = state.hintErrors[index]
            ret = true
        }
        if (ds.hintsDone[index] != state.hintsDone[index]) {
            ds.hintsDone[index] = state.hintsDone[index]
            ret = true
        }
        return ret
    }

    private fun drawCellBackground(sink: DrawSink, ui: Ui, x: Int, y: Int, t: Int) {
        val border = t / 4
        val dx = border + x * t + t / 2
        val dy = border + y * t + t / 2 + t
        val hon = ui.hshow && x == ui.hx && y == ui.hy
        sink.rect(
            dx - t / 2 + 1,
            dy - t / 2 + 1,
            t - 1,
            t - 1,
            if (hon && !ui.hpencil) COL_HIGHLIGHT else COL_BACKGROUND,
        )
        if (hon && ui.hpencil) {
            val poly = intArrayOf(
                dx - t / 2 + 1,
                dy - t / 2 + 1,
                dx - t / 2 + 1 + t / 2,
                dy - t / 2 + 1,
                dx - t / 2 + 1,
                dy - t / 2 + 1 + t / 2,
            )
            sink.poly(poly, COL_HIGHLIGHT, COL_HIGHLIGHT)
        }
        sink.update(dx - t / 2 + 1, dy - t / 2 + 1, t - 1, t - 1)
    }

    private fun drawCircleOrPoint(
        sink: DrawSink,
        cx: Int,
        cy: Int,
        radius: Int,
        colour: Int,
    ) {
        sink.circle(cx, cy, radius, colour, colour)
    }

    private fun drawMonster(
        sink: DrawSink,
        x: Int,
        y: Int,
        t: Int,
        hflash: Boolean,
        monster: Int,
    ) {
        val black = if (hflash) COL_FLASH else COL_MONSTER_OUTLINE
        when (monster) {
            1 -> {
                sink.clip(x - t / 2 + 2, y - t / 2 + 2, t - 3, t / 2 + 1)
                sink.circle(x, y, 2 * t / 5, COL_GHOST, black)
                sink.unclip()

                val poly = IntArray(80)
                var i = 0
                poly[i++] = x - 2 * t / 5
                poly[i++] = y - 2
                poly[i++] = x - 2 * t / 5
                poly[i++] = y + 2 * t / 5
                for (j in 0..2) {
                    val total = (2 * t / 5) * 2
                    val before = total * j / 3
                    val after = total * (j + 1) / 3
                    val mid = (before + after) / 2
                    poly[i++] = x - 2 * t / 5 + mid
                    poly[i++] = y + 2 * t / 5 - total / 6
                    poly[i++] = x - 2 * t / 5 + after
                    poly[i++] = y + 2 * t / 5
                }
                poly[i++] = x + 2 * t / 5
                poly[i++] = y - 2

                sink.clip(x - t / 2 + 2, y, t - 3, t - t / 2 - 1)
                sink.poly(poly.copyOf(i), COL_GHOST, black)
                sink.unclip()

                sink.circle(x - t / 6, y - t / 12, t / 10, COL_MONSTER_EYES, black)
                sink.circle(x + t / 6, y - t / 12, t / 10, COL_MONSTER_EYES, black)
                drawCircleOrPoint(
                    sink,
                    x - t / 6 + 1 + t / 48,
                    y - t / 12,
                    t / 48,
                    black,
                )
                drawCircleOrPoint(
                    sink,
                    x + t / 6 + 1 + t / 48,
                    y - t / 12,
                    t / 48,
                    black,
                )
            }

            2 -> {
                sink.clip(x - t / 2 + 2, y - t / 2 + 2, t - 3, t / 2)
                sink.circle(x, y, 2 * t / 5, black, black)
                sink.unclip()

                sink.clip(x - t / 2 + 2, y - t / 2 + 2, t / 2 + 1, t / 2)
                sink.circle(x - t / 7, y, 2 * t / 5 - t / 7, COL_VAMPIRE, black)
                sink.unclip()
                sink.clip(x, y - t / 2 + 2, t / 2 + 1, t / 2)
                sink.circle(x + t / 7, y, 2 * t / 5 - t / 7, COL_VAMPIRE, black)
                sink.unclip()

                sink.clip(x - t / 2 + 2, y, t - 3, t / 2)
                sink.circle(x, y, 2 * t / 5, COL_VAMPIRE, black)
                sink.unclip()

                sink.circle(x - t / 7, y - t / 16, t / 16, COL_MONSTER_EYES, black)
                sink.circle(x + t / 7, y - t / 16, t / 16, COL_MONSTER_EYES, black)
                drawCircleOrPoint(sink, x - t / 7, y - t / 16, t / 48, black)
                drawCircleOrPoint(sink, x + t / 7, y - t / 16, t / 48, black)

                sink.clip(x - t / 2 + 2, y + t / 8, t - 3, t / 4)

                var poly = intArrayOf(
                    x - 3 * t / 16,
                    y + 1 * t / 8,
                    x - 2 * t / 16,
                    y + 7 * t / 24,
                    x - 1 * t / 16,
                    y + 1 * t / 8,
                )
                sink.poly(poly, COL_BACKGROUND, black)
                poly = intArrayOf(
                    x + 3 * t / 16,
                    y + 1 * t / 8,
                    x + 2 * t / 16,
                    y + 7 * t / 24,
                    x + 1 * t / 16,
                    y + 1 * t / 8,
                )
                sink.poly(poly, COL_BACKGROUND, black)

                sink.circle(x, y - t / 5, 2 * t / 5, COL_VAMPIRE, black)
                sink.unclip()
            }

            4 -> {
                sink.circle(x, y, 2 * t / 5, COL_ZOMBIE, black)

                sink.line(
                    x - t / 7 - t / 16,
                    y - t / 12 - t / 16,
                    x - t / 7 + t / 16,
                    y - t / 12 + t / 16,
                    black,
                )
                sink.line(
                    x - t / 7 + t / 16,
                    y - t / 12 - t / 16,
                    x - t / 7 - t / 16,
                    y - t / 12 + t / 16,
                    black,
                )
                sink.line(
                    x + t / 7 - t / 16,
                    y - t / 12 - t / 16,
                    x + t / 7 + t / 16,
                    y - t / 12 + t / 16,
                    black,
                )
                sink.line(
                    x + t / 7 + t / 16,
                    y - t / 12 - t / 16,
                    x + t / 7 - t / 16,
                    y - t / 12 + t / 16,
                    black,
                )

                sink.clip(x - t / 5, y + t / 6, 2 * t / 5 + 1, t / 2)
                sink.circle(x - t / 15, y + t / 6, t / 12, COL_BACKGROUND, black)
                sink.unclip()

                sink.line(
                    x - t / 5,
                    y + t / 6,
                    x + t / 5,
                    y + t / 6,
                    black,
                )
            }
        }

        sink.update(x - t / 2 + 2, y - t / 2 + 2, t - 3, t - 3)
    }

    private fun drawMonsterCount(
        sink: DrawSink,
        ds: DrawState,
        state: State,
        c: Int,
        hflash: Boolean,
        t: Int,
    ) {
        val border = t / 4
        val dy = t / 4
        var dx = border + (ds.w + 2) * t / 2 + t / 4
        ds.counts = countsGeometry(t, ds.w)

        val count = when (c) {
            0 -> {
                dx -= 3 * t / 2
                state.common.numGhosts
            }

            1 -> state.common.numVampires
            else -> {
                dx += 3 * t / 2
                state.common.numZombies
            }
        }
        val letter = when (c) {
            0 -> "G"
            1 -> "V"
            else -> "Z"
        }

        sink.rect(dx - 2 * t / 3, dy, 3 * t / 2, t, COL_BACKGROUND)
        if (!ds.ascii) {
            drawMonster(sink, dx - t / 3, dy + t / 2, 2 * t / 3, hflash, 1 shl c)
        } else {
            sink.text(
                dx - t / 3,
                dy + t / 2,
                Text.FONT_VARIABLE,
                t / 2,
                Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                if (hflash) COL_FLASH else COL_TEXT,
                letter,
            )
        }
        sink.text(
            dx,
            dy + t / 2,
            Text.FONT_VARIABLE,
            t / 2,
            Text.ALIGN_HLEFT or Text.ALIGN_VCENTRE,
            if (state.countErrors[c]) COL_ERROR else if (hflash) COL_FLASH else COL_TEXT,
            count.toString(),
        )
        sink.update(dx - 2 * t / 3, dy, 3 * t / 2, t)
    }

    private fun drawPathHint(
        sink: DrawSink,
        ds: DrawState,
        common: Common,
        hintIndex: Int,
        hflash: Boolean,
        hint: Int,
        t: Int,
    ) {
        val color = when {
            ds.hintErrors[hintIndex] -> COL_ERROR
            hflash -> COL_FLASH
            ds.hintsDone[hintIndex] -> COL_DONE
            else -> COL_TEXT
        }
        val cell = range2grid(hintIndex, common.params.w, common.params.h)
        val border = t / 4
        var dx = border + cell.x * t
        var dy = border + cell.y * t + t
        val textDx = dx + t / 2
        val textDy = dy + t / 2
        dx += 2
        dy += 2
        val textSize = t - 3

        sink.rect(dx, dy, textSize, textSize, COL_BACKGROUND)
        sink.text(
            textDx,
            textDy,
            Text.FONT_VARIABLE,
            t / 2,
            Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
            color,
            hint.toString(),
        )
        sink.update(dx, dy, textSize, textSize)
    }

    private fun drawMirror(
        sink: DrawSink,
        x: Int,
        y: Int,
        hflash: Boolean,
        mirror: Int,
        t: Int,
    ) {
        val border = t / 4
        val dx = border + x * t + t / 2
        val dy = border + y * t + t / 2 + t
        val mx1: Int
        val my1: Int
        val mx2: Int
        val my2: Int
        if (mirror == CELL_MIRROR_L) {
            mx1 = dx - t / 4
            my1 = dy - t / 4
            mx2 = dx + t / 4
            my2 = dy + t / 4
        } else {
            mx1 = dx - t / 4
            my1 = dy + t / 4
            mx2 = dx + t / 4
            my2 = dy - t / 4
        }
        sink.thickLine(
            mx1.toFloat(),
            my1.toFloat(),
            mx2.toFloat(),
            my2.toFloat(),
            (t / 16).toFloat(),
            if (hflash) COL_FLASH else COL_TEXT,
        )
        sink.update(dx - t / 2 + 1, dy - t / 2 + 1, t - 1, t - 1)
    }

    private fun drawBigMonster(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        hflash: Boolean,
        monster: Int,
        t: Int,
    ) {
        val border = t / 4
        val dx = border + x * t + t / 2
        val dy = border + y * t + t / 2 + t
        if (ds.ascii) {
            val buf = when (monster) {
                1 -> "G"
                2 -> "V"
                4 -> "Z"
                else -> " "
            }
            sink.text(
                dx,
                dy,
                Text.FONT_VARIABLE,
                t / 2,
                Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                if (hflash) COL_FLASH else COL_TEXT,
                buf,
            )
            sink.update(dx - t / 2 + 2, dy - t / 2 + 2, t - 3, t - 3)
        } else {
            drawMonster(sink, dx, dy, 3 * t / 4, hflash, monster)
        }
    }

    private fun drawPencils(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        pencil: Int,
        t: Int,
    ) {
        val border = t / 4
        val dx = border + x * t + t / 4
        val dy = border + y * t + t / 4 + t
        val monsters = IntArray(4)
        var i = 0
        var j = 1
        while (j < 8) {
            if (pencil and j != 0) monsters[i++] = j
            j *= 2
        }
        while (i < 4) monsters[i++] = 0

        for (py in 0..1) {
            for (px in 0..1) {
                val m = monsters[py * 2 + px]
                if (m == 0) continue
                if (!ds.ascii) {
                    drawMonster(
                        sink,
                        dx + t / 2 * px,
                        dy + t / 2 * py,
                        t / 2,
                        false,
                        m,
                    )
                } else {
                    val buf = when (m) {
                        1 -> "G"
                        2 -> "V"
                        else -> "Z"
                    }
                    sink.text(
                        dx + t / 2 * px,
                        dy + t / 2 * py,
                        Text.FONT_VARIABLE,
                        t / 4,
                        Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE,
                        COL_TEXT,
                        buf,
                    )
                }
            }
        }
        sink.update(dx - t / 4 + 2, dy - t / 4 + 2, t / 2 - 3, t / 2 - 3)
    }

    // ------------------------------------------------------------------
    // Parsers.
    // ------------------------------------------------------------------

    private class ScannedInt(val value: Int, val end: Int)

    /** One `%d`: white space, an optional sign, then digits. */
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
            if (!overflow) {
                val digit = text[i] - '0'
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

    /** C `atoi`: white space, an optional sign, then digits. */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && isCSpace(text[i])) i++
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

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' ||
            c == '\u000C'
}
