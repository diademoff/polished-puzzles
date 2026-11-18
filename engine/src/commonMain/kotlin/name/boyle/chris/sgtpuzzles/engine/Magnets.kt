package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `magnets.c`, the magnet-domino deduction puzzle.
 *
 * Every square belongs to a 2x1 domino (or is the one singleton of an
 * odd-area board). Each domino is either a pair of magnets (`+` and
 * `-`) or a neutral pair. The clues around the edge give the number of
 * positive magnets in each column and row, and the same for negative
 * magnets. A clue may be missing, which is an unknown count.
 *
 * The rules, the seed, the description, and the save format are
 * upstream behavior. The generator lays the dominoes with
 * [LayDomino.layout], fills them with magnets and a few neutral pairs,
 * and accepts a board only when the deduction solver stalls at
 * `diff - 1` and solves at `diff`. The optional strip-clues pass
 * removes every clue that the solver no longer needs.
 *
 * The code keeps the C structure on purpose:
 * - the RNG order is `LayDomino.layout`, then one `shuffle` of the
 *   cells in `layDominoes`, then one `shuffle` of the edge clues in
 *   the strip pass, with whole-generator retries;
 * - `countsDone` is part of the state, so a `D` clue toggle is a real
 *   move and undo restores the shading;
 * - `completed` is sticky: `executeMove` starts from a copy and only
 *   ever sets the flag;
 * - `solve` first tries the current state, then the initial state that
 *   the seam no longer passes, so [Common] keeps the decoded initial
 *   grid;
 * - the solver is a direct transcription of `solve_state`, because it
 *   also gates the generator: a solver difference changes every
 *   description and every `SOLVE` string.
 *
 * C reaches `assert(ret != -1)` in the strip-clues loop. This port
 * defines the release rule instead: a clue is removed only when the
 * solver finishes (`ret == 1`) and the solved grid is unchanged;
 * otherwise the clue is restored. The C abort makes the release code
 * the only defined reference.
 *
 * Enablement debt: `validate_params` accepts an area up to
 * `INT_MAX`. Generation has unbounded retries, `16x14` Easy takes
 * several seconds, and `20x20` does not finish in a minute. The app
 * must generate off the UI thread; a size bound is an owner decision
 * and is not added here.
 */
object Magnets : Game {

    /** Cell values of `enum { EMPTY = 0, NEUTRAL = EMPTY, POSITIVE = 1, NEGATIVE = 2 }`. */
    internal const val EMPTY = 0
    internal const val NEUTRAL = EMPTY
    internal const val POSITIVE = 1
    internal const val NEGATIVE = 2

    /** Flag bits of `magnets.c:266-273`. */
    internal const val GS_ERROR = 1
    internal const val GS_SET = 2
    internal const val GS_NOTPOSITIVE = 4
    internal const val GS_NOTNEGATIVE = 8
    internal const val GS_NOTNEUTRAL = 16
    internal const val GS_MARK = 32
    internal const val GS_NOTMASK = GS_NOTPOSITIVE or GS_NOTNEGATIVE or GS_NOTNEUTRAL

    /** `enum { ROW, COLUMN }`. */
    internal const val ROW = 0
    internal const val COLUMN = 1

    /** The two difficulties of `DIFFLIST`. */
    internal const val DIFF_EASY = 0
    internal const val DIFF_TRICKY = 1
    internal const val DIFFCOUNT = 2
    private const val DIFF_CHARS = "et"
    private val DIFF_NAMES = arrayOf("Easy", "Tricky")

    /** The palette of `magnets.c:52-57`. */
    private const val COL_BACKGROUND = 0
    private const val COL_HIGHLIGHT = 1
    private const val COL_LOWLIGHT = 2
    private const val COL_TEXT = 3
    private const val COL_ERROR = 4
    private const val COL_CURSOR = 5
    private const val COL_DONE = 6
    private const val COL_NEUTRAL = 7
    private const val COL_NEGATIVE = 8
    private const val COL_POSITIVE = 9
    private const val COL_NOT = 10
    internal const val NCOLOURS = 11

    /** The drawstate cache word of `magnets.c:1802-1810`. */
    internal const val DS_WHICH_MASK = 0xf
    internal const val DS_ERROR = 0x10
    internal const val DS_CURSOR = 0x20
    internal const val DS_SET = 0x40
    internal const val DS_NOTPOS = 0x80
    internal const val DS_NOTNEG = 0x100
    internal const val DS_NOTNEU = 0x200
    internal const val DS_FLASH = 0x400

    /** The domino half types of `magnets.c:2105-2111`. */
    private const val TYPE_L = 0
    private const val TYPE_R = 1
    private const val TYPE_T = 2
    private const val TYPE_B = 3

    private const val PREFERRED_TILE_SIZE = 32

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val diff: Int,
        val stripClues: Boolean,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, diff, stripClues)
    }

    /**
     * `struct game_common`: the domino layout and the four count runs.
     *
     * C shares one refcounted instance between every state of a game
     * and mutates it only during generation. Here a generated game gets
     * a fresh instance, and a state never writes these arrays.
     *
     * [initialGrid] is a port shape, not a C field: `solve_game` falls
     * back to `states[0]`, which the seam no longer passes. The decoded
     * initial grid is the only part of that state the solver can read
     * after `solve_clearflags` clears the flags of every non-singleton.
     * Do not modify any of these arrays after [newState] returns.
     */
    class Common internal constructor(
        val dominoes: IntArray,
        val rowcount: IntArray,
        val colcount: IntArray,
        internal val initialGrid: IntArray,
    )

    /**
     * `struct game_state`. [grid], [flags], and [countsDone] are copied
     * per state, as `dup_game` copies them; [common] is shared.
     * [completed] is sticky and [solved] is set only by an `S` move.
     */
    class State internal constructor(
        val width: Int,
        val height: Int,
        val grid: IntArray,
        val flags: IntArray,
        /** `counts_done`: (h + w) * 2 entries, indexed by `clueIndex`. */
        val countsDone: BooleanArray,
        val solved: Boolean,
        val completed: Boolean,
        /** True once the counts are known. Only `solve_checkfull` asserts it in C. */
        var numbered: Boolean,
        val common: Common,
    ) : Game.State {

        val wh: Int get() = width * height

        /**
         * The C `solved` flag under the name the other ports use for a
         * cheated win. An `S` move (a solve) sets it, and a cheated win
         * does not raise the completion event.
         */
        val cheated: Boolean get() = solved

        fun valueAt(x: Int, y: Int): Int = grid[y * width + x]

        fun flagAt(x: Int, y: Int): Int = flags[y * width + x]

        /** `dominoes[i]`: the other end of the domino, or `i` for a singleton. */
        fun dominoOf(index: Int): Int = common.dominoes[index]

        override fun copy(): Game.State = State(
            width,
            height,
            grid.copyOf(),
            flags.copyOf(),
            countsDone.copyOf(),
            solved,
            completed,
            numbered,
            common,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                grid.contentEquals(other.grid) &&
                flags.contentEquals(other.flags) &&
                countsDone.contentEquals(other.countsDone) &&
                solved == other.solved &&
                completed == other.completed &&
                numbered == other.numbered &&
                common.dominoes.contentEquals(other.common.dominoes) &&
                common.rowcount.contentEquals(other.common.rowcount) &&
                common.colcount.contentEquals(other.common.colcount)

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + countsDone.contentHashCode()
            result = 31 * result + solved.hashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + numbered.hashCode()
            result = 31 * result + common.dominoes.contentHashCode()
            result = 31 * result + common.rowcount.contentHashCode()
            result = 31 * result + common.colcount.contentHashCode()
            return result
        }
    }

    /** `struct game_ui`: the cursor and its visibility. */
    class Ui : Game.Ui {
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false
    }

    /**
     * `struct game_drawstate`: the per-cell cache word, the per-clue
     * cached colour, and the `started` frame flag. There is no blitter.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val what: IntArray = IntArray(width * height)
        val colwhat: IntArray = IntArray(3 * width)
        val rowwhat: IntArray = IntArray(3 * height)
    }

    /** One generated description, the C aux answer, and the attempt counts. */
    data class Generation(
        val desc: String,
        val auxInfo: String,
        val attempts: Int,
        val layDominoRetries: Int,
    )

    override val name: String = "Magnets"
    override val backendName: String = "magnets"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /** `default_params`: `magnets_presets[2]`, `6x5 Tricky, strip clues`. */
    override fun defaultParams(): Game.Params = Params(6, 5, DIFF_TRICKY, true)

    /** `magnets_presets` with the `game_fetch_preset` title format. */
    override fun presets(): List<Preset> = listOf(
        Params(6, 5, DIFF_EASY, false),
        Params(6, 5, DIFF_TRICKY, false),
        Params(6, 5, DIFF_TRICKY, true),
        Params(8, 7, DIFF_EASY, false),
        Params(8, 7, DIFF_TRICKY, false),
        Params(8, 7, DIFF_TRICKY, true),
        Params(10, 9, DIFF_TRICKY, false),
        Params(10, 9, DIFF_TRICKY, true),
    ).map {
        val name = "${it.w}x${it.h} ${DIFF_NAMES[it.diff]}" +
            if (it.stripClues) ", strip clues" else ""
        Preset(name, encodeParams(it, true))
    }

    /**
     * `decode_params`. The difficulty and the strip flag are reset
     * explicitly, so a short string always means Easy without strip
     * clues. Only lower-case `x`/`d` and upper-case `S` are
     * recognized, and the character after `d` is consumed whether or
     * not it is a difficulty letter.
     */
    override fun decodeParams(text: String): Game.Params {
        var w = atoi(text, 0)
        var h = w
        var position = 0
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == 'x') {
            position++
            h = atoi(text, position)
            while (position < text.length && text[position] in '0'..'9') position++
        }

        var diff = DIFF_EASY
        if (position < text.length && text[position] == 'd') {
            position++
            if (position < text.length) {
                for (i in 0 until DIFFCOUNT) {
                    if (text[position] == DIFF_CHARS[i]) diff = i
                }
                position++
            }
        }

        var stripClues = false
        if (position < text.length && text[position] == 'S') {
            position++
            stripClues = true
        }

        return Params(w, h, diff, stripClues)
    }

    /** `encode_params`: the short form drops the difficulty and the strip flag. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.w).append('x').append(p.h)
        if (full) {
            out.append('d').append(DIFF_CHARS[p.diff])
            if (p.stripClues) out.append('S')
        }
        return out.toString()
    }

    /** `validate_params`, in the C order and with the C messages. */
    @Suppress("UNUSED_PARAMETER")
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 2) return "Width must be at least two"
        if (p.h < 2) return "Height must be at least two"
        if (p.h != 0 && p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (p.diff >= DIFF_TRICKY) {
            if (p.w < 5 && p.h < 5) {
                return "Either width or height must be at least five for Tricky"
            }
        } else {
            if (p.w < 3 && p.h < 3) {
                return "Either width or height must be at least three"
            }
        }
        if (p.diff < 0 || p.diff >= DIFFCOUNT) return "Unknown difficulty level"
        return null
    }

    /** `game_configure` (`magnets.c:193-223`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Tricky",
                null,
                p.diff,
            ),
            ConfigItem.Bool("Strip clues", p.stripClues),
        )
    }

    /** `custom_params` (`magnets.c:225-235`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Choices).selected,
            (items[3] as ConfigItem.Bool).value,
        )

    /**
     * `new_game_desc`. The order of the draws is the contract: a retry
     * redraws the domino layout, the lay passes, and the strip shuffle,
     * so it changes every later seed too.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val generation = generate(params as Params, random, cancel)
        return Description(generation.desc, generation.auxInfo)
    }

    // `magnets.c` retries the whole fill and solver; 16x14 takes 5.9 s and 20x20 does not finish in 90 s (PARITY).
    override fun generationWarning(params: Game.Params): String? {
        val p = params as Params
        return if (p.w.toLong() * p.h >= 224) Game.SLOW_GENERATION_WARNING else null
    }

    /** [newDesc] with the outer and `lay_dominoes` attempt counts, for tests. */
    fun newDescWithAttempts(params: Game.Params, random: RandomState): Generation =
        generate(params as Params, random)

    /** `validate_desc`: `new_game_int` plus `free_game`. */
    override fun validateDesc(params: Game.Params, desc: String): String? =
        decode(params as Params, desc).error

    /**
     * `new_game`. C asserts the decoded state; the midend always
     * validates first, so a direct caller with a bad description gets a
     * defined exception instead of an abort.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val decoded = decode(params as Params, desc)
        return decoded.state ?: throw IllegalArgumentException(
            "magnets: ${decoded.error}",
        )
    }

    // ------------------------------------------------------------------
    // Description decode and encode.
    // ------------------------------------------------------------------

    private class Decoded(val state: State?, val error: String?)

    private class RowReader(
        val text: String,
        var position: Int,
    ) {
        /** True for the NUL that C reads past the end of the string. */
        fun atEnd(): Boolean = position >= text.length
    }

    /**
     * `readrow`: exactly `n` count characters, then a comma. A missing
     * character or terminator is "too short"; any other bad character
     * or separator is "invalid character".
     */
    private fun readRow(
        reader: RowReader,
        n: Int,
        array: IntArray,
        offset: Int,
    ): String? {
        for (i in 0 until n) {
            if (reader.atEnd()) return "Game description too short"
            val c = reader.text[reader.position++]
            if (c == '.') {
                array[i * 3 + offset] = -1
            } else {
                val num = c2n(c)
                if (num < 0) return "Invalid character in game description"
                array[i * 3 + offset] = num
            }
        }
        if (reader.atEnd()) return "Game description too short"
        if (reader.text[reader.position++] != ',') {
            return "Invalid character in game description"
        }
        return null
    }

    /** `new_game_int`. */
    private fun decode(params: Params, desc: String): Decoded {
        val w = params.w
        val h = params.h
        val wh = w * h
        val dominoes = IntArray(wh) { it }
        val colcount = IntArray(3 * w)
        val rowcount = IntArray(3 * h)
        val grid = IntArray(wh)
        val flags = IntArray(wh)
        val countsDone = BooleanArray(2 * (w + h))
        val reader = RowReader(desc, 0)

        readRow(reader, w, colcount, POSITIVE)?.let { return Decoded(null, it) }
        readRow(reader, h, rowcount, POSITIVE)?.let { return Decoded(null, it) }
        readRow(reader, w, colcount, NEGATIVE)?.let { return Decoded(null, it) }
        readRow(reader, h, rowcount, NEGATIVE)?.let { return Decoded(null, it) }

        /*
         * A missing positive or negative count makes the neutral count
         * missing too. C computes the neutral count from the area and
         * rejects a negative result.
         */
        for (x in 0 until w) {
            if (colcount[x * 3 + POSITIVE] < 0 || colcount[x * 3 + NEGATIVE] < 0) {
                colcount[x * 3 + NEUTRAL] = -1
            } else {
                colcount[x * 3 + NEUTRAL] =
                    h - colcount[x * 3 + POSITIVE] - colcount[x * 3 + NEGATIVE]
                if (colcount[x * 3 + NEUTRAL] < 0) {
                    return Decoded(null, "Column counts inconsistent")
                }
            }
        }
        for (y in 0 until h) {
            if (rowcount[y * 3 + POSITIVE] < 0 || rowcount[y * 3 + NEGATIVE] < 0) {
                rowcount[y * 3 + NEUTRAL] = -1
            } else {
                rowcount[y * 3 + NEUTRAL] =
                    w - rowcount[y * 3 + POSITIVE] - rowcount[y * 3 + NEGATIVE]
                if (rowcount[y * 3 + NEUTRAL] < 0) {
                    return Decoded(null, "Row counts inconsistent")
                }
            }
        }

        /*
         * The domino part: exactly w*h characters, and a comma between
         * them is skipped. C ignores any text after the last character.
         */
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                while (true) {
                    if (reader.atEnd()) return Decoded(null, "Game description too short")
                    val c = reader.text[reader.position++]
                    when (c) {
                        'L' -> dominoes[index] = index + 1
                        'R' -> dominoes[index] = index - 1
                        'T' -> dominoes[index] = index + w
                        'B' -> dominoes[index] = index - w
                        '*' -> dominoes[index] = index
                        ',' -> continue
                        else -> return Decoded(null, "Invalid character in game description")
                    }
                    break
                }
            }
        }

        /* Every partner must be in the grid, orthogonal, and point back. */
        for (index in 0 until wh) {
            val other = dominoes[index]
            if (other < 0 || other >= wh ||
                (other % w != index % w && other / w != index / w) ||
                dominoes[other] != index
            ) {
                return Decoded(null, "Domino descriptions inconsistent")
            }
            if (other == index) {
                grid[index] = NEUTRAL
                flags[index] = flags[index] or GS_SET
            }
        }

        val common = Common(dominoes, rowcount, colcount, grid.copyOf())
        return Decoded(
            State(w, h, grid, flags, countsDone, false, false, true, common),
            null,
        )
    }

    /** `n2c`. A count above 61 writes a character after `Z`, as C does. */
    private fun n2c(num: Int): Char = when {
        num == -1 -> '.'
        num < 10 -> '0' + num
        num < 36 -> 'a' + num - 10
        else -> 'A' + num - 36
    }

    /** `c2n`. */
    private fun c2n(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 36
        else -> -1
    }

    /** `GRID2CHAR`. */
    private fun grid2char(value: Int): Char = when (value) {
        POSITIVE -> '+'
        NEGATIVE -> '-'
        else -> '.'
    }

    /** `CHAR2GRID`; every character except `+` and `-` is neutral. */
    private fun char2grid(c: Char): Int = when (c) {
        '+' -> POSITIVE
        '-' -> NEGATIVE
        else -> NEUTRAL
    }

    /** `OPPOSITE(x)`: 0 -> 0, 1 -> 2, 2 -> 1. */
    private fun opposite(which: Int): Int = (which * 2) % 3

    /** `NOTFLAG(w)`. */
    private fun notFlag(which: Int): Int = when (which) {
        NEUTRAL -> GS_NOTNEUTRAL
        POSITIVE -> GS_NOTPOSITIVE
        NEGATIVE -> GS_NOTNEGATIVE
        else -> 0
    }

    /** `POSSIBLE(f, w)`. */
    private fun possible(flags: IntArray, index: Int, which: Int): Boolean =
        flags[index] and notFlag(which) == 0

    /** `generate_desc`. */
    private fun generateDesc(state: State): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder(2 * w + 2 * h + state.wh + 4)
        for (x in 0 until w) out.append(n2c(state.common.colcount[x * 3 + POSITIVE]))
        out.append(',')
        for (y in 0 until h) out.append(n2c(state.common.rowcount[y * 3 + POSITIVE]))
        out.append(',')
        for (x in 0 until w) out.append(n2c(state.common.colcount[x * 3 + NEGATIVE]))
        out.append(',')
        for (y in 0 until h) out.append(n2c(state.common.rowcount[y * 3 + NEGATIVE]))
        out.append(',')
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                val other = state.common.dominoes[index]
                out.append(
                    when (other) {
                        index -> '*'
                        index + 1 -> 'L'
                        index - 1 -> 'R'
                        index + w -> 'T'
                        index - w -> 'B'
                        else -> error("magnets: mad domino orientation")
                    },
                )
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    /**
     * `new_game_desc` with an attempt counter. Each outer attempt draws
     * a fresh [LayDomino.layout] because `clear_state` resets the
     * dominoes in C.
     */
    internal fun generate(
        params: Params,
        random: RandomState,
        cancel: Cancellation = Cancellation.NEVER,
    ): Generation {
        val w = params.w
        val h = params.h
        val wh = w * h
        val scratch = IntArray(wh)
        var attempts = 0
        var layDominoRetries = 0

        while (true) {
            cancel.check()
            attempts++
            val common = Common(
                IntArray(wh) { it },
                IntArray(3 * h),
                IntArray(3 * w),
                IntArray(wh),
            )
            val state = State(
                w,
                h,
                IntArray(wh),
                IntArray(wh),
                BooleanArray(2 * (w + h)),
                false,
                false,
                false,
                common,
            )
            layDominoRetries += genGame(state, random, scratch)
            val aux = generateAux(state)
            if (checkDifficulty(params, state, random) >= 0) {
                return Generation(generateDesc(state), aux, attempts, layDominoRetries)
            }
        }
    }

    /** `clear_state`: reset every mutable part, as `gen_game` starts. */
    private fun clearState(state: State) {
        state.common.rowcount.fill(0)
        state.common.colcount.fill(0)
        state.countsDone.fill(false)
        state.grid.fill(EMPTY)
        state.flags.fill(0)
        for (i in state.common.dominoes.indices) state.common.dominoes[i] = i
    }

    /**
     * `gen_game`, minus the outer retry. Returns the number of extra
     * `lay_dominoes` passes, which C repeats while the pass reports a
     * contradiction.
     */
    private fun genGame(state: State, random: RandomState, scratch: IntArray): Int {
        clearState(state)
        val layout = LayDomino.layout(state.width, state.height, random)
        layout.copyInto(state.common.dominoes)

        var retries = 0
        while (true) {
            val ret = layDominoes(state, random, scratch)
            if (ret != -1) break
            retries++
        }

        state.common.colcount.fill(0)
        state.common.rowcount.fill(0)
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val value = state.grid[y * state.width + x]
                state.common.colcount[x * 3 + value]++
                state.common.rowcount[y * 3 + value]++
            }
        }
        state.numbered = true
        return retries
    }

    /**
     * `lay_dominoes`. A few squares are laid neutral first, then the
     * rest prefer a magnet and fall back to neutral. The pass also runs
     * `solve_unnumbered`, which draws no random numbers and stops the
     * pass at a complete or impossible board.
     */
    private fun layDominoes(
        state: State,
        random: RandomState,
        scratch: IntArray,
    ): Int {
        val wh = state.wh
        for (i in 0 until wh) {
            scratch[i] = i
            state.grid[i] = EMPTY
            state.flags[i] = if (state.common.dominoes[i] == i) GS_SET else 0
        }
        Divvy.shuffle(scratch, wh, random)

        val initialNeutral = if (wh > 100) 5 else wh / 10
        var ret = 0
        for (n in 0 until wh) {
            val i = scratch[n]
            if (state.flags[i] and GS_SET != 0) continue

            check(state.flags[i] and GS_NOTNEUTRAL == 0) {
                "magnets: a neutral can always be laid"
            }

            ret = if (n < initialNeutral) {
                solveSet(state, i, NEUTRAL)
            } else if (state.flags[i] and GS_NOTPOSITIVE == 0) {
                solveSet(state, i, POSITIVE)
            } else if (state.flags[i] and GS_NOTNEGATIVE == 0) {
                solveSet(state, i, NEGATIVE)
            } else {
                solveSet(state, i, NEUTRAL)
            }
            if (ret == 0) {
                ret = -1
                break
            }

            ret = solveUnnumbered(state)
            if (ret != 0) break
        }
        return ret
    }

    /** `generate_aux`: one `.`, `+`, or `-` per cell in row-major order. */
    private fun generateAux(state: State): String {
        val out = StringBuilder(state.wh)
        for (value in state.grid) out.append(grid2char(value))
        return out.toString()
    }

    /**
     * `check_difficulty`: the solver gates, then the optional clue
     * strip. A stripped clue is kept removed only when the solver still
     * finishes with the same grid; see the class comment for the C
     * assertion this replaces.
     */
    private fun checkDifficulty(
        params: Params,
        state: State,
        random: RandomState,
    ): Int {
        state.grid.fill(EMPTY)

        if (params.diff > DIFF_EASY) {
            if (solveState(state, params.diff - 1) > 0) return -1
        }
        if (solveState(state, params.diff) <= 0) return -1
        if (!params.stripClues) return 0

        val correct = state.grid.copyOf()

        val slen = state.width * 2 + state.height * 2
        val scratch = IntArray(slen) { it }
        Divvy.shuffle(scratch, slen, random)

        for (k in 0 until slen) {
            var num = scratch[k]
            val which: Int
            val rowOrCol: Int
            if (num < state.width + state.height) {
                which = POSITIVE
            } else {
                which = NEGATIVE
                num -= state.width + state.height
            }
            if (num < state.width) {
                rowOrCol = COLUMN
            } else {
                rowOrCol = ROW
                num -= state.width
            }

            val rc = mkRowCol(state, num, rowOrCol)
            val target = rc.target(which)
            val neutralTarget = rc.target(NEUTRAL)
            rc.setTarget(which, -1)
            rc.setTarget(NEUTRAL, -1)

            state.grid.fill(EMPTY)
            val ret = solveState(state, params.diff)
            if (!(ret == 1 && state.grid.contentEquals(correct))) {
                rc.setTarget(which, target)
                rc.setTarget(NEUTRAL, neutralTarget)
            }
        }
        return 0
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    /**
     * `interpret_move`. A cursor move is an exact button, so a modified
     * arrow key falls through to `Unused`. A click hides a visible
     * cursor; a clue click does not. The returned move is the C text:
     * `+`, `-`, `.`, `?`, or a space, then `x,y`; a clue toggle is
     * `Dx,y`.
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
        var gx = fromCoord(x, tileSize)
        var gy = fromCoord(y, tileSize)
        var nullret: MoveResult = MoveResult.Unused
        val action: Int

        if (Buttons.isCursorMove(button)) return moveCursor(button, u, s.width, s.height)

        if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            action = if (button == Buttons.CURSOR_SELECT) CYCLE_MAGNET else CYCLE_NEUTRAL
            gx = u.curX
            gy = u.curY
        } else if (gx in 0 until s.width && gy in 0 until s.height &&
            (button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON)
        ) {
            if (u.curVisible) {
                u.curVisible = false
                nullret = MoveResult.UiUpdate
            }
            action = if (button == Buttons.LEFT_BUTTON) CYCLE_MAGNET else CYCLE_NEUTRAL
        } else if ((button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON) &&
            isClue(s, gx, gy)
        ) {
            return MoveResult.Move("D$gx,$gy")
        } else {
            return MoveResult.Unused
        }

        val index = gy * s.width + gx
        if (s.common.dominoes[index] == index) return nullret
        val current = s.grid[index]

        val movech: Char = if (action == CYCLE_MAGNET) {
            if (s.grid[index] == NEUTRAL && s.flags[index] and GS_SET != 0) {
                return nullret
            }
            when (current) {
                EMPTY -> '+'
                POSITIVE -> '-'
                else -> ' '
            }
        } else {
            if (s.grid[index] != NEUTRAL) return nullret
            when {
                s.flags[index] and GS_SET != 0 -> '?'
                s.flags[index] and GS_NOTNEUTRAL != 0 -> ' '
                else -> '.'
            }
        }

        return MoveResult.Move("$movech$gx,$gy")
    }

    /**
     * `move_cursor` with `wrap = false`: the first call reveals a hidden
     * cursor, a later call reports whether the cursor moved.
     */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
        val dx = when (button) {
            Buttons.CURSOR_UP -> 0
            Buttons.CURSOR_DOWN -> 0
            Buttons.CURSOR_LEFT -> -1
            Buttons.CURSOR_RIGHT -> 1
            else -> return MoveResult.Unused
        }
        val dy = when (button) {
            Buttons.CURSOR_UP -> -1
            Buttons.CURSOR_DOWN -> 1
            else -> 0
        }
        val ox = ui.curX
        val oy = ui.curY
        ui.curX = (ui.curX + dx).coerceIn(0, w - 1)
        ui.curY = (ui.curY + dy).coerceIn(0, h - 1)
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

    /**
     * `execute_move`. The state is copied first, so a rejected move
     * changes nothing. `S` sets `solved`; a bad entry rejects the whole
     * move. The `%d` conversion saturates; see the other ports.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val grid = s.grid.copyOf()
        val flags = s.flags.copyOf()
        val countsDone = s.countsDone.copyOf()
        var solved = s.solved
        var position = 0

        if (move.isEmpty()) return null
        while (position < move.length) {
            val c = move[position]
            if (c == 'S') {
                solved = true
                position++
            } else if (c == '+' || c == '-' || c == '.' || c == ' ' || c == '?') {
                val parsed = scanPair(move, position + 1) ?: return null
                position = parsed.end
                val x = parsed.x
                val y = parsed.y
                if (x < 0 || x >= s.width || y < 0 || y >= s.height) return null

                val index = y * s.width + x
                val index2 = s.common.dominoes[index]
                if (index == index2) return null

                flags[index] = flags[index] and GS_NOTMASK.inv()
                flags[index2] = flags[index2] and GS_NOTMASK.inv()

                if (c == ' ' || c == '?') {
                    grid[index] = EMPTY
                    grid[index2] = EMPTY
                    flags[index] = flags[index] and GS_SET.inv()
                    flags[index2] = flags[index2] and GS_SET.inv()
                    if (c == '?') {
                        flags[index] = flags[index] or GS_NOTNEUTRAL
                        flags[index2] = flags[index2] or GS_NOTNEUTRAL
                    }
                } else {
                    grid[index] = char2grid(c)
                    grid[index2] = opposite(char2grid(c))
                    flags[index] = flags[index] or GS_SET
                    flags[index2] = flags[index2] or GS_SET
                }
            } else if (c == 'D') {
                val parsed = scanPair(move, position + 1) ?: return null
                if (!isClue(s, parsed.x, parsed.y)) return null
                position = parsed.end
                val clue = clueIndex(s, parsed.x, parsed.y)
                countsDone[clue] = !countsDone[clue]
            } else {
                return null
            }

            if (position < move.length) {
                if (move[position] == ';') {
                    position++
                } else {
                    return null
                }
            }
        }

        val next = State(
            s.width,
            s.height,
            grid,
            flags,
            countsDone,
            solved,
            s.completed,
            s.numbered,
            s.common,
        )
        if (checkCompletion(next) == 1 && !next.completed) {
            return State(
                s.width,
                s.height,
                grid,
                flags,
                countsDone,
                solved,
                true,
                s.numbered,
                s.common,
            )
        }
        return next
    }

    // The two cycle actions of `interpret_move`.
    private const val CYCLE_MAGNET = 0
    private const val CYCLE_NEUTRAL = 1

    /** `is_clue`: the four side bands, not the corners. */
    private fun isClue(state: State, x: Int, y: Int): Boolean {
        val w = state.width
        val h = state.height
        return ((x == -1 || x == w) && y >= 0 && y < h) ||
            ((y == -1 || y == h) && x >= 0 && x < w)
    }

    /** `clue_index`: the index into `counts_done`, or -1. */
    private fun clueIndex(state: State, x: Int, y: Int): Int {
        val w = state.width
        val h = state.height
        return when {
            y == -1 -> x
            x == w -> w + y
            y == h -> 2 * w + h - x - 1
            x == -1 -> 2 * (w + h) - y - 1
            else -> -1
        }
    }

    /** `count_rowcol`; `which < 0` counts empty cells that are not set. */
    private fun countRowCol(state: State, num: Int, rowOrCol: Int, which: Int): Int {
        val rc = mkRowCol(state, num, rowOrCol)
        var count = 0
        var index = rc.i0
        for (j in 0 until rc.n) {
            if (which < 0) {
                if (state.grid[index] == EMPTY && state.flags[index] and GS_SET == 0) {
                    count++
                }
            } else if (state.grid[index] == which) {
                count++
            }
            index += rc.di
        }
        return count
    }

    /** `check_rowcol`. */
    private fun checkRowCol(
        state: State,
        num: Int,
        rowOrCol: Int,
        which: Int,
        wrong: BooleanArray,
        incomplete: BooleanArray,
    ) {
        val target = mkRowCol(state, num, rowOrCol).target(which)
        if (target == -1) return
        val count = countRowCol(state, num, rowOrCol, which)
        if (count < target) incomplete[0] = true
        if (count > target) wrong[0] = true
    }

    /**
     * `check_completion`: -1 wrong, 0 incomplete, 1 complete. The error
     * bits are per state and are cleared and set here, as C does.
     */
    private fun checkCompletion(state: State): Int {
        val w = state.width
        val h = state.height
        val wrong = BooleanArray(1)
        val incomplete = BooleanArray(1)

        var which = POSITIVE
        for (j in 0 until 2) {
            for (x in 0 until w) {
                checkRowCol(state, x, COLUMN, which, wrong, incomplete)
            }
            for (y in 0 until h) {
                checkRowCol(state, y, ROW, which, wrong, incomplete)
            }
            which = opposite(which)
        }

        for (i in 0 until state.wh) state.flags[i] = state.flags[i] and GS_ERROR.inv()

        for (x in 0 until w) {
            for (y in 0 until h) {
                val index = y * w + x
                if (state.common.dominoes[index] == index) continue

                if (state.flags[index] and GS_SET == 0) incomplete[0] = true

                which = state.grid[index]
                if (which != NEUTRAL) {
                    if (checkNeighbour(state, x, y - 1, which)) wrong[0] = true
                    if (checkNeighbour(state, x, y + 1, which)) wrong[0] = true
                    if (checkNeighbour(state, x - 1, y, which)) wrong[0] = true
                    if (checkNeighbour(state, x + 1, y, which)) wrong[0] = true
                }
            }
        }
        return if (wrong[0]) -1 else if (incomplete[0]) 0 else 1
    }

    /** The `CHECK` macro: an equal in-grid neighbour is an error for both. */
    private fun checkNeighbour(state: State, x: Int, y: Int, which: Int): Boolean {
        if (x < 0 || x >= state.width || y < 0 || y >= state.height) return false
        val index = y * state.width + x
        if (state.grid[index] != which) return false
        state.flags[index] = state.flags[index] or GS_ERROR
        return true
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    /** One row or column, with a pointer to its three targets, as `mkrowcol`. */
    private class RowCol(
        val i0: Int,
        val di: Int,
        val n: Int,
        val rowOrCol: Int,
        val num: Int,
        val targets: IntArray,
        val base: Int,
    ) {
        fun target(which: Int): Int = targets[base + which]
        fun setTarget(which: Int, value: Int) {
            targets[base + which] = value
        }
    }

    /** `mkrowcol`. */
    private fun mkRowCol(state: State, num: Int, rowOrCol: Int): RowCol =
        if (rowOrCol == ROW) {
            RowCol(num * state.width, 1, state.width, ROW, num, state.common.rowcount, num * 3)
        } else {
            RowCol(num, state.width, state.height, COLUMN, num, state.common.colcount, num * 3)
        }

    /** `solve_clearflags`. A user-set neutral is lost, as in C. */
    private fun solveClearFlags(state: State) {
        for (i in 0 until state.wh) {
            state.flags[i] = state.flags[i] and GS_NOTMASK.inv()
            if (state.common.dominoes[i] != i) {
                state.flags[i] = state.flags[i] and GS_SET.inv()
            }
        }
    }

    /**
     * `solve_unflag`: mark one square and its partner as not this value.
     * Returns the number of new marks, or -1 on a contradiction.
     */
    private fun solveUnflag(state: State, index: Int, which: Int): Int {
        val other = state.common.dominoes[index]
        if (other == index) return 0

        if (state.flags[index] and GS_SET != 0 && state.grid[index] == which) return -1
        if (state.flags[other] and GS_SET != 0 && state.grid[other] == opposite(which)) {
            return -1
        }

        var ret = 0
        if (possible(state.flags, index, which)) {
            state.flags[index] = state.flags[index] or notFlag(which)
            ret++
        }
        if (possible(state.flags, other, opposite(which))) {
            state.flags[other] = state.flags[other] or notFlag(opposite(which))
            ret++
        }
        return ret
    }

    /** `solve_unflag_surrounds`: the four orthogonal neighbours. */
    private fun solveUnflagSurrounds(state: State, index: Int, which: Int): Int {
        val x = index % state.width
        val y = index / state.width
        for (j in 0 until 4) {
            val xx = x + DX[j]
            val yy = y + DY[j]
            if (xx < 0 || xx >= state.width || yy < 0 || yy >= state.height) continue
            if (solveUnflag(state, yy * state.width + xx, which) < 0) return -1
        }
        return 0
    }

    /** `solve_set`: set a square and its partner, or report a contradiction. */
    private fun solveSet(state: State, index: Int, which: Int): Int {
        val other = state.common.dominoes[index]

        if (state.flags[index] and GS_SET != 0) {
            return if (state.grid[index] == which) 0 else -1
        }
        if (state.flags[other] and GS_SET != 0 && state.grid[other] != opposite(which)) {
            return -1
        }
        if (!possible(state.flags, index, which)) return -1
        if (!possible(state.flags, other, opposite(which))) return -1

        if (which != NEUTRAL) {
            if (solveUnflagSurrounds(state, index, which) < 0) return -1
            if (solveUnflagSurrounds(state, other, opposite(which)) < 0) return -1
        }

        state.grid[index] = which
        state.grid[other] = opposite(which)
        state.flags[index] = state.flags[index] or GS_SET
        state.flags[other] = state.flags[other] or GS_SET
        return 1
    }

    /** `solve_checkfull`. */
    private fun solveCheckFull(state: State, rc: RowCol, counts: IntArray): Int {
        val unset = IntArray(4)
        solveCountsFor(state, rc, counts, unset)
        var didSomething = 0

        for (which in 0..2) {
            val target = rc.target(which)
            if (target == -1) continue

            if (target < counts[which]) {
                return -1
            }
            if (target == counts[which]) {
                var index = rc.i0
                for (j in 0 until rc.n) {
                    if (state.flags[index] and GS_SET == 0 &&
                        possible(state.flags, index, which)
                    ) {
                        if (solveUnflag(state, index, which) < 0) return -1
                        didSomething = 1
                    }
                    index += rc.di
                }
            } else if (target - counts[which] == unset[which]) {
                var index = rc.i0
                for (j in 0 until rc.n) {
                    if (state.flags[index] and GS_SET == 0 &&
                        possible(state.flags, index, which)
                    ) {
                        if (solveSet(state, index, which) < 0) return -1
                        didSomething = 1
                    }
                    index += rc.di
                }
            }
        }
        return didSomething
    }

    /** `solve_counts` with the state arrays. */
    private fun solveCountsFor(
        state: State,
        rc: RowCol,
        counts: IntArray,
        unset: IntArray?,
    ) {
        for (i in 0..3) {
            counts[i] = 0
            if (unset != null) unset[i] = 0
        }
        var index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET != 0) {
                counts[state.grid[index]]++
            } else if (unset != null) {
                for (which in 0..2) {
                    if (possible(state.flags, index, which)) unset[which]++
                }
            }
            index += rc.di
        }
    }

    /** `solve_startflags`: the initial set-and-hold, after the clear. */
    private fun solveStartFlags(state: State): Int {
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = y * state.width + x
                if (state.common.dominoes[index] == index) continue
                if (state.grid[index] != NEUTRAL || state.flags[index] and GS_SET != 0) {
                    if (solveSet(state, index, state.grid[index]) < 0) return -1
                }
            }
        }
        return 0
    }

    /** `solve_rowcols`: every column, then every row. */
    private fun solveRowCols(
        state: State,
        fn: (State, RowCol, IntArray) -> Int,
    ): Int {
        var didSomething = 0
        val counts = IntArray(4)
        for (x in 0 until state.width) {
            val rc = mkRowCol(state, x, COLUMN)
            solveCountsFor(state, rc, counts, null)
            val ret = fn(state, rc, counts)
            if (ret < 0) return ret
            didSomething += ret
        }
        for (y in 0 until state.height) {
            val rc = mkRowCol(state, y, ROW)
            solveCountsFor(state, rc, counts, null)
            val ret = fn(state, rc, counts)
            if (ret < 0) return ret
            didSomething += ret
        }
        return didSomething
    }

    /** `solve_force`: two of the three impossible marks give the third value. */
    private fun solveForce(state: State): Int {
        var didSomething = 0
        for (i in 0 until state.wh) {
            if (state.flags[i] and GS_SET != 0) continue
            if (state.common.dominoes[i] == i) continue

            var which = -1
            when (state.flags[i] and GS_NOTMASK) {
                GS_NOTPOSITIVE or GS_NOTNEGATIVE -> which = NEUTRAL
                GS_NOTPOSITIVE or GS_NOTNEUTRAL -> which = NEGATIVE
                GS_NOTNEGATIVE or GS_NOTNEUTRAL -> which = POSITIVE
            }
            if (which != -1) {
                if (solveSet(state, i, which) < 0) return -1
                didSomething = 1
            }
        }
        return didSomething
    }

    /** `solve_neither`: both ends cannot hold a colour, so the domino is neutral. */
    private fun solveNeither(state: State): Int {
        var didSomething = 0
        for (i in 0 until state.wh) {
            if (state.flags[i] and GS_SET != 0) continue
            val j = state.common.dominoes[i]
            if (i == j) continue

            if ((state.flags[i] and GS_NOTPOSITIVE != 0 &&
                    state.flags[j] and GS_NOTPOSITIVE != 0) ||
                (state.flags[i] and GS_NOTNEGATIVE != 0 &&
                    state.flags[j] and GS_NOTNEGATIVE != 0)
            ) {
                if (solveSet(state, i, NEUTRAL) < 0) return -1
                didSomething = 1
            }
        }
        return didSomething
    }

    /** `solve_advancedfull`: an in-line domino that can only be polar. */
    private fun solveAdvancedFull(state: State, rc: RowCol, counts: IntArray): Int {
        if (rc.target(POSITIVE) == -1 && rc.target(NEGATIVE) == -1) return 0
        if (rc.target(POSITIVE) >= 0 && counts[POSITIVE] == rc.target(POSITIVE) &&
            rc.target(NEGATIVE) >= 0 && counts[NEGATIVE] == rc.target(NEGATIVE)
        ) {
            return 0
        }

        var index = rc.i0
        for (j in 0 until rc.n) {
            state.flags[index] = state.flags[index] and GS_MARK.inv()
            index += rc.di
        }

        var nfound = 0
        index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET != 0) {
                index += rc.di
                continue
            }
            if (state.common.dominoes[index] != index + rc.di) {
                index += rc.di
                continue
            }
            val partner = index + rc.di
            if (state.flags[index] and GS_NOTMASK != GS_NOTNEUTRAL ||
                state.flags[partner] and GS_NOTMASK != GS_NOTNEUTRAL
            ) {
                index += rc.di
                continue
            }
            state.flags[index] = state.flags[index] or GS_MARK
            state.flags[partner] = state.flags[partner] or GS_MARK
            nfound++
            index += rc.di
        }
        if (nfound == 0) return 0

        counts[POSITIVE] += nfound
        counts[NEGATIVE] += nfound

        val clearPos = rc.target(POSITIVE) >= 0 && counts[POSITIVE] == rc.target(POSITIVE)
        val clearNeg = rc.target(NEGATIVE) >= 0 && counts[NEGATIVE] == rc.target(NEGATIVE)
        if (!clearPos && !clearNeg) return 0

        var ret = 0
        index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET == 0 &&
                state.flags[index] and GS_MARK == 0
            ) {
                if (clearPos && state.flags[index] and GS_NOTPOSITIVE == 0) {
                    if (solveUnflag(state, index, POSITIVE) < 0) return -1
                    ret++
                }
                if (clearNeg && state.flags[index] and GS_NOTNEGATIVE == 0) {
                    if (solveUnflag(state, index, NEGATIVE) < 0) return -1
                    ret++
                }
            }
            index += rc.di
        }
        return ret
    }

    /** `solve_nonneutral`: the last neutral cannot be an in-line domino. */
    private fun solveNonNeutral(state: State, rc: RowCol, counts: IntArray): Int {
        if (rc.target(NEUTRAL) != counts[NEUTRAL] + 1) return 0
        var ret = 0
        var index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET == 0 &&
                state.common.dominoes[index] == index + rc.di &&
                state.flags[index] and GS_NOTNEUTRAL == 0
            ) {
                if (solveUnflag(state, index, NEUTRAL) < 0) return -1
                ret++
            }
            index += rc.di
        }
        return ret
    }

    /** `solve_oddlength`: the extra colour goes at the start of the odd free section. */
    private fun solveOddLength(state: State, rc: RowCol, counts: IntArray): Int {
        if (rc.target(NEUTRAL) != counts[NEUTRAL]) return 0

        val tpos = rc.target(POSITIVE) - counts[POSITIVE]
        val tneg = rc.target(NEGATIVE) - counts[NEGATIVE]
        val extra = when {
            tpos == tneg + 1 -> POSITIVE
            tneg == tpos + 1 -> NEGATIVE
            else -> return 0
        }

        var start = -1
        var length = 0
        var startOdd = -1
        var inEmpty = false
        var index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET != 0) {
                if (inEmpty) {
                    if (length % 2 != 0) {
                        if (startOdd != -1) return 0
                        startOdd = start
                    }
                    inEmpty = false
                }
            } else {
                if (inEmpty) {
                    length++
                } else {
                    start = index
                    length = 1
                    inEmpty = true
                }
            }
            index += rc.di
        }
        if (inEmpty && length % 2 != 0) {
            if (startOdd != -1) return 0
            startOdd = start
        }
        return if (startOdd != -1) solveSet(state, startOdd, extra) else 0
    }

    /** `solve_countdominoes_neutral`: all remaining dominoes must be polar. */
    private fun solveCountDominoesNeutral(
        state: State,
        rc: RowCol,
        counts: IntArray,
    ): Int {
        if (rc.target(POSITIVE) == -1 && rc.target(NEGATIVE) == -1) return 0

        var ndom = 0
        var index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET == 0 &&
                state.common.dominoes[index] != index &&
                state.common.dominoes[index] != index - rc.di
            ) {
                ndom++
            }
            index += rc.di
        }

        var nonNeutral = false
        if (rc.target(POSITIVE) != -1 && rc.target(POSITIVE) - counts[POSITIVE] == ndom) {
            nonNeutral = true
        }
        if (rc.target(NEGATIVE) != -1 && rc.target(NEGATIVE) - counts[NEGATIVE] == ndom) {
            nonNeutral = true
        }
        if (!nonNeutral) return 0

        var ret = 0
        index = rc.i0
        for (j in 0 until rc.n) {
            if (state.flags[index] and GS_SET == 0 &&
                state.flags[index] and GS_NOTNEUTRAL == 0
            ) {
                if (solveUnflag(state, index, NEUTRAL) < 0) return -1
                ret++
            }
            index += rc.di
        }
        return ret
    }

    /** `solve_domino_count`: how many squares of one domino in the line can hold a value. */
    private fun solveDominoCount(
        state: State,
        rc: RowCol,
        index: Int,
        which: Int,
    ): Int {
        if (state.common.dominoes[index] == index ||
            state.common.dominoes[index] == index - rc.di ||
            state.flags[index] and GS_SET != 0
        ) {
            return 0
        }
        var nposs = 0
        if (possible(state.flags, index, which)) nposs++
        if (state.common.dominoes[index] == index + rc.di &&
            possible(state.flags, index + rc.di, which)
        ) {
            nposs++
        }
        return nposs
    }

    /** `solve_countdominoes_nonneutral`: a forced cell of a counted domino. */
    private fun solveCountDominoesNonNeutral(
        state: State,
        rc: RowCol,
        counts: IntArray,
    ): Int {
        var didSomething = 0
        /*
         * C declares `ndom` once, outside the `which` loop, and never
         * resets it. The count therefore accumulates across the
         * positive and negative passes. This is an upstream quirk and
         * part of the solver contract.
         */
        var ndom = 0
        var which = POSITIVE
        for (w in 0 until 2) {
            if (rc.target(which) != -1) {
                var index = rc.i0
                for (j in 0 until rc.n) {
                    if (solveDominoCount(state, rc, index, which) > 0) ndom++
                    index += rc.di
                }

                if (rc.target(which) - counts[which] == ndom) {
                    index = rc.i0
                    for (j in 0 until rc.n) {
                        if (solveDominoCount(state, rc, index, which) == 1) {
                            val toSet = if (possible(state.flags, index, which)) {
                                index
                            } else {
                                check(state.common.dominoes[index] == index + rc.di)
                                check(possible(state.flags, index + rc.di, which))
                                index + rc.di
                            }
                            if (solveSet(state, toSet, which) < 0) return -1
                            didSomething++
                        }
                        index += rc.di
                    }
                }
            }
            which = opposite(which)
        }
        return didSomething
    }

    /** `solve_state`. The return value is `check_completion`'s. */
    private fun solveState(state: State, diff: Int): Int {
        solveClearFlags(state)
        if (solveStartFlags(state) < 0) {
            return -1
        }

        while (true) {
            var ret = solveForce(state)
            if (ret > 0) continue
            if (ret < 0) {
                    return -1
            }

            ret = solveNeither(state)
            if (ret > 0) continue
            if (ret < 0) {
                    return -1
            }

            ret = solveRowCols(state, ::solveCheckFull)
            if (ret < 0) {
                    return -1
            }
            if (ret > 0) continue

            ret = solveRowCols(state, ::solveOddLength)
            if (ret < 0) {
                    return -1
            }
            if (ret > 0) continue

            if (diff < DIFF_TRICKY) break

            ret = solveRowCols(state, ::solveAdvancedFull)
            if (ret < 0) {
                    return -1
            }
            if (ret > 0) continue

            ret = solveRowCols(state, ::solveNonNeutral)
            if (ret < 0) {
                    return -1
            }
            if (ret > 0) continue

            ret = solveRowCols(state, ::solveCountDominoesNeutral)
            if (ret < 0) {
                    return -1
            }
            if (ret > 0) continue

            ret = solveRowCols(state, ::solveCountDominoesNonNeutral)
            if (ret < 0) {
                    return -1
            }
            if (ret > 0) continue

            break
        }
        return checkCompletion(state)
    }

    /** `solve_unnumbered`: force and neither only, then the completeness test. */
    private fun solveUnnumbered(state: State): Int {
        while (true) {
            var ret = solveForce(state)
            if (ret > 0) continue
            if (ret < 0) return -1

            ret = solveNeither(state)
            if (ret > 0) continue
            if (ret < 0) return -1

            break
        }
        for (i in 0 until state.wh) {
            if (state.flags[i] and GS_SET == 0) return 0
        }
        return 1
    }

    // ------------------------------------------------------------------
    // solve_game.
    // ------------------------------------------------------------------

    /**
     * `solve_game`. An `AUXINFO` of length `w*h` wins and is copied over
     * a copy of the current state; otherwise the solver runs on the
     * current state, then on the decoded initial state. The move is
     * always the column-major diff from the current state.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /**
     * `solve_game` (`magnets.c:1468-1500`). The current board is tried
     * first, then the decoded first state. A negative result from the
     * second solve means the clues contradict each other; zero means
     * this solver could not finish an otherwise consistent board.
     */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val current = state as State
        val solved = current.copy() as State

        if (auxInfo != null && auxInfo.length == current.wh) {
            solveFromAux(solved, auxInfo)
            return SolveOutcome(stateDiff(current, solved), null)
        }

        if (solveState(solved, DIFFCOUNT) > 0) {
            return SolveOutcome(stateDiff(current, solved), null)
        }

        val initial = initialStateForSolve(current)
        val ret = solveState(initial, DIFFCOUNT)
        if (ret > 0) {
            return SolveOutcome(stateDiff(current, initial), null)
        }
        return SolveOutcome(
            null,
            if (ret < 0) "Puzzle is impossible." else "Unable to solve puzzle.",
        )
    }

    /** `solve_from_aux`: every cell is set, including the singletons. */
    private fun solveFromAux(state: State, aux: String) {
        check(aux.length == state.wh) { "magnets: aux length is not w*h" }
        for (i in 0 until state.wh) {
            state.grid[i] = char2grid(aux[i])
            state.flags[i] = state.flags[i] or GS_SET
        }
    }

    /**
     * The decoded initial state that `solve_game` reads as `states[0]`.
     * Only the grid and the singleton set bits survive `solve_clearflags`.
     */
    private fun initialStateForSolve(current: State): State {
        val flags = IntArray(current.wh) { i ->
            if (current.common.dominoes[i] == i) GS_SET else 0
        }
        return State(
            current.width,
            current.height,
            current.common.initialGrid.copyOf(),
            flags,
            BooleanArray(2 * (current.width + current.height)),
            false,
            false,
            true,
            current.common,
        )
    }

    /** `game_state_diff`: `S;` then a column-major entry per changed domino. */
    private fun stateDiff(source: State, destination: State): String {
        val out = StringBuilder("S;")
        for (x in 0 until destination.width) {
            for (y in 0 until destination.height) {
                val index = y * destination.width + x
                if (source.common.dominoes[index] == index) continue

                if (source.grid[index] != destination.grid[index] ||
                    (source.flags[index] and GS_SET) !=
                    (destination.flags[index] and GS_SET)
                ) {
                    val c = if (destination.grid[index] == EMPTY &&
                        destination.flags[index] and GS_SET == 0
                    ) {
                        ' '
                    } else {
                        grid2char(destination.grid[index])
                    }
                    out.append(c).append(x).append(',').append(y).append(';')
                }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Text, status, UI, keys.
    // ------------------------------------------------------------------

    /** `game_text_format`: a fixed rectangle; every line ends in `\n`. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val out = StringBuilder()

        out.append('+')
        for (x in 0 until w) {
            out.append(' ').append(n2c(s.common.colcount[x * 3 + POSITIVE]))
        }
        out.append('\n')

        textHBorder(out, w)

        for (y in 0 until h) {
            out.append(n2c(s.common.rowcount[y * 3 + POSITIVE])).append('|')
            for (x in 0 until w) {
                val index = y * w + x
                out.append(
                    when {
                        s.common.dominoes[index] == index -> '#'
                        s.grid[index] == POSITIVE -> '+'
                        s.grid[index] == NEGATIVE -> '-'
                        s.flags[index] and GS_SET != 0 -> '*'
                        else -> ' '
                    },
                )
                if (x < w - 1) {
                    out.append(if (s.common.dominoes[index] == index + 1) ' ' else '|')
                }
            }
            out.append('|').append(n2c(s.common.rowcount[y * 3 + NEGATIVE])).append('\n')

            if (y < h - 1) {
                out.append(' ').append('|')
                for (x in 0 until w) {
                    val index = y * w + x
                    out.append(if (s.common.dominoes[index] == index + w) ' ' else '-')
                    if (x < w - 1) out.append('+')
                }
                out.append('|').append('\n')
            }
        }

        textHBorder(out, w)

        out.append(' ')
        for (x in 0 until w) {
            out.append(' ').append(n2c(s.common.colcount[x * 3 + NEGATIVE]))
        }
        out.append(' ').append('-').append('\n')
        return out.toString()
    }

    /** `game_text_hborder`: ` +` then `2*w-1` dashes, `+`, and a newline. */
    private fun textHBorder(out: StringBuilder, w: Int) {
        out.append(' ').append('+')
        repeat(2 * w - 1) { out.append('-') }
        out.append('+').append('\n')
    }

    /** `game_status`: completed or open. */
    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** Magnets has no status bar, so the text is empty. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** `game_changed_state`: hide the cursor on a completion, report an honest one. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        val previous = oldState as State?
        if (previous != null && !previous.completed && next.completed) {
            (ui as Ui).curVisible = false
        }
        return next.completed && !next.solved &&
            previous != null && !previous.completed
    }

    /** `game_request_keys` is NULL; the Android full-cursor hint only changes the arrows. */
    override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
        KeyRequest(
            emptyList(),
            if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
        )

    /** `game_request_keys` is NULL: no labels and the default arrow mode. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /**
     * `current_key_label`. The returned text is `+`, `-`, `Clear`, `?`,
     * `X`, or empty. C reads the grid before it tests the cursor and
     * without a bounds test; the port returns "" for a cursor outside
     * the grid, which the game itself cannot produce. The singleton
     * label `?` is a C inconsistency and is reproduced: the key itself
     * does nothing.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (u.curX < 0 || u.curY < 0 || u.curX >= s.width || u.curY >= s.height) return ""
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) return ""
        if (!u.curVisible) return ""

        val index = u.curY * s.width + u.curX
        if (button == Buttons.CURSOR_SELECT) {
            if (s.grid[index] == NEUTRAL && s.flags[index] and GS_SET != 0) return ""
            return when (s.grid[index]) {
                EMPTY -> "+"
                POSITIVE -> "-"
                NEGATIVE -> "Clear"
                else -> ""
            }
        }
        if (s.grid[index] != NEUTRAL) return ""
        return when {
            s.flags[index] and GS_SET != 0 -> "?"
            s.flags[index] and GS_NOTNEUTRAL != 0 -> "Clear"
            else -> "X"
        }
    }

    /** `new_ui`: the environment variable is not read; the cursor starts hidden. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `game_get_cursor_location`: `COORD`, only for a visible cursor. */
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

    /** `game_compute_size`: the borders are truncated separately. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 8
        return Size(
            tileSize * (p.w + 2) + 2 * border,
            tileSize * (p.h + 2) + 2 * border,
        )
    }

    // ------------------------------------------------------------------
    // Palette and drawing.
    // ------------------------------------------------------------------

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`: only the background comes from the shell. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]

        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        for (i in 0..2) {
            ret[COL_TEXT * 3 + i] = 0.0f
            ret[COL_NEGATIVE * 3 + i] = 0.0f
            ret[COL_CURSOR * 3 + i] = 0.9f
            ret[COL_DONE * 3 + i] = ret[COL_BACKGROUND * 3 + i] / 1.5f
        }

        ret[COL_POSITIVE * 3] = 0.8f
        ret[COL_POSITIVE * 3 + 1] = 0.0f
        ret[COL_POSITIVE * 3 + 2] = 0.0f

        ret[COL_NEUTRAL * 3] = 0.10f
        ret[COL_NEUTRAL * 3 + 1] = 0.60f
        ret[COL_NEUTRAL * 3 + 2] = 0.10f

        ret[COL_ERROR * 3] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f

        ret[COL_NOT * 3] = 0.2f
        ret[COL_NOT * 3 + 1] = 0.2f
        ret[COL_NOT * 3 + 2] = 1.0f

        return ret
    }

    /** `game_new_drawstate`: zeroed cache words and a fresh `started` flag. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height)
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

    /**
     * `game_redraw` without a flash. The first frame draws the two
     * corner symbols and updates the whole window; later frames draw
     * only the cells and clues whose cache word or colour changed.
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
        val w = s.width
        val h = s.height
        val t = tileSize
        val border = t / 8

        if (!ds.started) {
            drawSym(sink, ds, -1, -1, POSITIVE, COL_TEXT, t)
            drawSym(sink, ds, w, h, NEGATIVE, COL_TEXT, t)
            sink.update(0, 0, t * (w + 2) + 2 * border, t * (h + 2) + 2 * border)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                var c = s.grid[index]

                if (s.flags[index] and GS_ERROR != 0) c = c or DS_ERROR
                if (s.flags[index] and GS_SET != 0) c = c or DS_SET
                if (u.curVisible && x == u.curX && y == u.curY) c = c or DS_CURSOR
                if (s.flags[index] and GS_NOTPOSITIVE != 0) c = c or DS_NOTPOS
                if (s.flags[index] and GS_NOTNEGATIVE != 0) c = c or DS_NOTNEG
                if (s.flags[index] and GS_NOTNEUTRAL != 0) c = c or DS_NOTNEU

                if (ds.what[index] != c || !ds.started) {
                    drawTile(sink, ds, s.common.dominoes, x, y, c, t)
                    ds.what[index] = c
                }
            }
        }

        var which = POSITIVE
        for (j in 0 until 2) {
            for (i in 0 until w) {
                val index = i * 3 + which
                val target = s.common.colcount[index]
                val color = getCountColor(s, COLUMN, which, i, target)
                if (color != ds.colwhat[index] || !ds.started) {
                    drawNum(sink, ds, COLUMN, which, i, COL_BACKGROUND, color, target, t)
                    ds.colwhat[index] = color
                }
            }
            for (i in 0 until h) {
                val index = i * 3 + which
                val target = s.common.rowcount[index]
                val color = getCountColor(s, ROW, which, i, target)
                if (color != ds.rowwhat[index] || !ds.started) {
                    drawNum(sink, ds, ROW, which, i, COL_BACKGROUND, color, target, t)
                    ds.rowwhat[index] = color
                }
            }
            which = opposite(which)
        }

        ds.started = true
    }

    /** `COORD(x)`. */
    private fun coord(value: Int, tileSize: Int): Int =
        (value + 1) * tileSize + tileSize / 8

    /** `FROMCOORD(x)`, with C truncation toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize / 8) / tileSize - 1

    /** `draw_num`: a clue in one of the four side bands. */
    private fun drawNum(
        sink: DrawSink,
        ds: DrawState,
        rowOrCol: Int,
        which: Int,
        idx: Int,
        colbg: Int,
        col: Int,
        num: Int,
        tileSize: Int,
    ) {
        if (num < 0) return

        val t = tileSize
        val text = num.toString()
        val tsz = if (text.length == 1) 7 * t / 10 else (9 * t / 10) / text.length
        val border = t / 8

        val cx: Int
        val cy: Int
        if (rowOrCol == ROW) {
            cx = border + if (which == NEGATIVE) t * (ds.width + 1) else 0
            cy = border + t * (idx + 1)
        } else {
            cx = border + t * (idx + 1)
            cy = border + if (which == NEGATIVE) t * (ds.height + 1) else 0
        }

        sink.rect(cx, cy, t, t, colbg)
        sink.text(
            cx + t / 2,
            cy + t / 2,
            Text.FONT_VARIABLE,
            tsz,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            col,
            text,
        )
        sink.update(cx, cy, t, t)
    }

    /** `draw_sym`: a plus, a minus, a blue question mark, or an X. */
    private fun drawSym(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        which: Int,
        col: Int,
        tileSize: Int,
    ) {
        val t = tileSize
        val cx = coord(x, t)
        val cy = coord(y, t)
        val ccx = cx + t / 2
        val ccy = cy + t / 2
        val roff = t / 4
        val rsz = 2 * roff + 1
        val soff = t / 16
        val ssz = 2 * soff + 1

        if (which == POSITIVE || which == NEGATIVE) {
            sink.rect(ccx - roff, ccy - soff, rsz, ssz, col)
            if (which == POSITIVE) {
                sink.rect(ccx - soff, ccy - roff, ssz, rsz, col)
            }
        } else if (col == COL_NOT) {
            sink.text(
                ccx,
                ccy,
                Text.FONT_VARIABLE,
                7 * t / 10,
                Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                col,
                "?",
            )
        } else {
            sink.line(ccx - roff, ccy - roff, ccx + roff, ccy + roff, col)
            sink.line(ccx + roff, ccy - roff, ccx - roff, ccy + roff, col)
        }
    }

    /** `draw_tile_col`: the rounded domino halves. */
    private fun drawTileCol(
        sink: DrawSink,
        ds: DrawState,
        dominoes: IntArray,
        x: Int,
        y: Int,
        which: Int,
        bg: Int,
        fg: Int,
        perc: Int,
        tileSize: Int,
    ) {
        val t = tileSize
        val cx = coord(x, t)
        val cy = coord(y, t)
        val gutter = t / 16 + ((100 - perc) * (7 * t / 16)) / 100
        val radius = (perc * (t / 8)) / 100
        val coffset = gutter + radius

        val index = y * ds.width + x
        val other = dominoes[index]
        if (other == index) return

        val type = when (other) {
            index + 1 -> TYPE_L
            index - 1 -> TYPE_R
            index + ds.width -> TYPE_T
            index - ds.width -> TYPE_B
            else -> error("magnets: mad domino orientation")
        }

        if (type == TYPE_L || type == TYPE_T) {
            sink.circle(cx + coffset, cy + coffset, radius, bg, bg)
        }
        if (type == TYPE_R || type == TYPE_T) {
            sink.circle(cx + t - 1 - coffset, cy + coffset, radius, bg, bg)
        }
        if (type == TYPE_L || type == TYPE_B) {
            sink.circle(cx + coffset, cy + t - 1 - coffset, radius, bg, bg)
        }
        if (type == TYPE_R || type == TYPE_B) {
            sink.circle(cx + t - 1 - coffset, cy + t - 1 - coffset, radius, bg, bg)
        }

        for (i in 0 until 2) {
            var x1 = cx + if (i != 0) gutter else coffset
            var y1 = cy + if (i != 0) coffset else gutter
            var x2 = cx + t - 1 - if (i != 0) gutter else coffset
            var y2 = cy + t - 1 - if (i != 0) coffset else gutter
            when (type) {
                TYPE_L -> x2 = cx + t
                TYPE_R -> x1 = cx
                TYPE_T -> y2 = cy + t
                TYPE_B -> y1 = cy
            }
            sink.rect(x1, y1, x2 - x1 + 1, y2 - y1 + 1, bg)
        }

        if (fg != -1) drawSym(sink, ds, x, y, which, fg, t)
    }

    /** The domino partner for a cell; `draw_tile_col` reads the state's layout. */
    /** `draw_tile`: the background, the cached symbol, and the domino shape. */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        dominoes: IntArray,
        x: Int,
        y: Int,
        word: Int,
        tileSize: Int,
    ) {
        val t = tileSize
        val cx = coord(x, t)
        val cy = coord(y, t)
        val perc = 100
        var flags = word
        var which = flags and DS_WHICH_MASK
        flags = flags and DS_WHICH_MASK.inv()

        sink.rect(cx, cy, t, t, COL_BACKGROUND)

        val bg = when {
            flags and DS_CURSOR != 0 -> COL_CURSOR
            which == POSITIVE -> COL_POSITIVE
            which == NEGATIVE -> COL_NEGATIVE
            flags and DS_SET != 0 -> COL_NEUTRAL
            else -> COL_LOWLIGHT
        }

        var fg: Int
        if (which == EMPTY && flags and DS_SET == 0) {
            var notWhich = -1
            fg = -1
            if (flags and DS_NOTPOS != 0) notWhich = POSITIVE
            if (flags and DS_NOTNEG != 0) notWhich = NEGATIVE
            if (flags and DS_NOTNEU != 0) notWhich = NEUTRAL
            if (notWhich != -1) {
                which = notWhich
                fg = COL_NOT
            }
        } else {
            fg = if (flags and DS_ERROR != 0) {
                COL_ERROR
            } else if (flags and DS_CURSOR != 0) {
                COL_TEXT
            } else {
                COL_BACKGROUND
            }
        }

        sink.rect(cx, cy, t, t, COL_BACKGROUND)

        if (flags and DS_FLASH != 0) {
            drawTileCol(sink, ds, dominoes, x, y, which, COL_HIGHLIGHT, -1, perc, t)
        }
        drawTileCol(sink, ds, dominoes, x, y, which, bg, fg, perc, t)

        sink.update(cx, cy, t, t)
    }

    /** `get_count_color`: error, shaded, or normal text. */
    private fun getCountColor(
        state: State,
        rowOrCol: Int,
        which: Int,
        index: Int,
        target: Int,
    ): Int {
        val count = countRowCol(state, index, rowOrCol, which)
        if (count > target ||
            (count < target && countRowCol(state, index, rowOrCol, -1) == 0)
        ) {
            return COL_ERROR
        }
        val clue = if (rowOrCol == COLUMN) {
            clueIndex(state, index, if (which == POSITIVE) -1 else state.height)
        } else {
            clueIndex(state, if (which == POSITIVE) -1 else state.width, index)
        }
        if (state.countsDone[clue]) return COL_DONE
        return COL_TEXT
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private class ScannedInt(val value: Int, val end: Int)

    private class ScannedPair(val x: Int, val y: Int, val end: Int)

    /**
     * `sscanf(move + start, "%d,%d%n")`. `%d` takes white space, an
     * optional sign, and digits; the comma is a literal. The returned
     * end is the `%n` count. A value outside the `long` range
     * saturates, and the assignment to `int` keeps the low 32 bits.
     */
    private fun scanPair(text: String, start: Int): ScannedPair? {
        val x = scanInt(text, start) ?: return null
        if (x.end >= text.length || text[x.end] != ',') return null
        val y = scanInt(text, x.end + 1) ?: return null
        return ScannedPair(x.value, y.value, y.end)
    }

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

    /** C `atoi`: white space, an optional sign, then digits, saturating. */
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

    /** The white space set of C `isspace` in the C locale. */
    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private val DX = intArrayOf(-1, 1, 0, 0)
    private val DY = intArrayOf(0, 0, -1, 1)
}
