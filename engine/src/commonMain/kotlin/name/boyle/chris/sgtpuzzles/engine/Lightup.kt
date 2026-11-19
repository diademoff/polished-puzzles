package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `lightup.c`.
 *
 * Light Up is Nikoli's puzzle: place lights on white cells so that every
 * white cell is lit, no light is lit by another light, and every numbered
 * black cell has exactly that many adjacent lights.
 *
 * A description is one character per cell, row-major, with runs of white
 * cells compressed into `a`..`z`. A black cell is `B` or, when it carries
 * a clue, `0`..`4`. A move is `L<x>,<y>` or `I<x>,<y>`, repeated with `;`
 * separators; `S` marks a solve.
 *
 * The generator is gated by the deduction solver twice, and the surviving
 * clues are exactly the ones the solver marked as used. The `SOLVE` string
 * is a diff of the solver's final board, including the `F_IMPOSSIBLE`
 * marks it leaves behind, in column-major order. A solver difference
 * therefore changes every description and every solve record, so the
 * solver below is a literal port.
 *
 * The `STYLUS_BASED` click cycle is compiled in both the app and the
 * oracle: the left button cycles empty, light, impossible; the right
 * button cycles the other way. `interpret_move` does not strip modifiers,
 * so a modified cursor key is unused.
 *
 * C `solve_game` tries the current board and then the decoded first
 * state, because `midend_solve` passes `states[0]`. The seam passes only
 * the current state, so [Shared] keeps an immutable snapshot of the
 * decoded board.
 *
 * There is no `AUXINFO` and no `UI` record, no blitter, and no flash.
 */
object Lightup : Game {

    private const val PREFERRED_TILE_SIZE = 32

    /** Colour roles from `lightup.c:85-94`. */
    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_BLACK = 2
    private const val COL_LIGHT = 3
    private const val COL_LIT = 4
    private const val COL_ERROR = 5
    private const val COL_CURSOR = 6
    private const val NCOLOURS = 7

    /** Symmetry modes from `lightup.c:96`. */
    private const val SYMM_NONE = 0
    private const val SYMM_REF2 = 1
    private const val SYMM_ROT2 = 2
    private const val SYMM_REF4 = 3
    private const val SYMM_ROT4 = 4
    private const val SYMM_MAX = 5

    /** `DIFFCOUNT` from `lightup.c:103`. */
    private const val DIFFCOUNT = 2

    /** Flag bits from `lightup.c:112-122`. */
    internal const val F_BLACK = 1
    internal const val F_NUMBERED = 2
    internal const val F_NUMBERUSED = 4
    internal const val F_IMPOSSIBLE = 8
    internal const val F_LIGHT = 16
    internal const val F_MARK = 32

    /** `flags_from_difficulty` from `lightup.c:1263-1270`. */
    private const val F_SOLVE_FORCEUNIQUE = 1
    private const val F_SOLVE_DISCOUNTSETS = 2
    private const val F_SOLVE_ALLOWRECURSE = 4

    /** `MAXRECURSE` from `lightup.c:1272`. */
    private const val MAXRECURSE = 5

    /** `MAX_GRIDGEN_TRIES` from `lightup.c:1539`. */
    private const val MAX_GRIDGEN_TRIES = 20

    /**
     * The largest board this port generates.
     *
     * C accepts any `w * h <= INT_MAX`, so `46340x46340` passes
     * `validate_params`; the two arrays alone would be about 17 GB. The
     * guard is a deviation, because it rejects parameters C accepts. It
     * must never reject a board the game can show, and no preset is
     * anywhere near the limit.
     */
    internal const val MAX_AREA = 1 shl 20

    /** Display flags from `lightup.c:1935-1943`. */
    private const val DF_BLACK = 1
    private const val DF_NUMBERED = 2
    private const val DF_LIT = 4
    private const val DF_LIGHT = 8
    private const val DF_OVERLAP = 16
    private const val DF_CURSOR = 32
    private const val DF_NUMBERWRONG = 64
    private const val DF_IMPOSSIBLE = 256

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val blackpc: Int,
        val symm: Int,
        val diff: Int,
    ) : Game.Params {
        override fun copy(): Game.Params = Params(w, h, blackpc, symm, diff)
    }

    /**
     * The immutable part of a game: the decoded board. `solve_game` runs
     * on this board after the current board fails, so the port keeps it
     * here instead of growing the seam.
     */
    class Shared internal constructor(
        val width: Int,
        val height: Int,
        /** The decoded `lights` array. Do not modify it. */
        val initialLights: IntArray,
        /** The decoded `flags` array. Do not modify it. */
        val initialFlags: IntArray,
    ) {
        val size: Int get() = width * height
    }

    /**
     * `struct game_state`. Both arrays are owned by the state, as
     * `dup_game` copies them. [completed] is sticky; only an `S` move
     * sets [usedSolve].
     */
    class State internal constructor(
        val shared: Shared,
        val lights: IntArray,
        val flags: IntArray,
        var completed: Boolean,
        var usedSolve: Boolean,
        var nLights: Int,
    ) : Game.State {

        val width: Int get() = shared.width

        val height: Int get() = shared.height

        fun valueAt(x: Int, y: Int): Int = lights[y * width + x]

        fun flagsAt(x: Int, y: Int): Int = flags[y * width + x]

        override fun copy(): Game.State = duplicate(this)

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                lights.contentEquals(other.lights) &&
                flags.contentEquals(other.flags) &&
                completed == other.completed &&
                usedSolve == other.usedSolve &&
                nLights == other.nLights

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + lights.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + nLights
            return result
        }
    }

    /**
     * `struct game_ui`. The cursor is not serialised, so a load starts
     * with a fresh UI. [drawBlobsWhenLit] is the `show-lit-blobs`
     * preference; it is read while drawing, never cached per cell.
     */
    class Ui : Game.Ui {
        var curX: Int = 0
        var curY: Int = 0
        var curVisible: Boolean = false
        var drawBlobsWhenLit: Boolean = true
    }

    /**
     * `struct game_drawstate`: one flag word per cell and the `started`
     * frame flag. There is no blitter.
     */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
    ) : Game.DrawState {
        var started: Boolean = false
        val flags: IntArray = IntArray(width * height) { -1 }
    }

    override val name: String = "Light Up"
    override val backendName: String = "lightup"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    // `lightup.c` has no `SMALL_SCREEN` conditional, so the app and the
    // oracle expose the same nine presets.
    private val presetValues = listOf(
        Params(7, 7, 20, SYMM_REF4, 0),
        Params(7, 7, 20, SYMM_REF4, 1),
        Params(7, 7, 20, SYMM_REF4, 2),
        Params(10, 10, 20, SYMM_ROT2, 0),
        Params(10, 10, 20, SYMM_ROT2, 1),
        Params(10, 10, 20, SYMM_ROT2, 2),
        Params(14, 14, 20, SYMM_ROT2, 0),
        Params(14, 14, 20, SYMM_ROT2, 1),
        Params(14, 14, 20, SYMM_ROT2, 2),
    )

    override fun defaultParams(): Game.Params = Params(7, 7, 20, SYMM_REF4, 0)

    /** `game_fetch_preset`: `%dx%d %s` and the full parameter encoding. */
    override fun presets(): List<Preset> = presetValues.map {
        Preset("${it.w}x${it.h} ${difficultyTitle(it.diff)}", encodeParams(it, true))
    }

    private fun difficultyTitle(diff: Int): String = when (diff) {
        2 -> "Hard"
        1 -> "Tricky"
        else -> "Easy"
    }

    /**
     * `decode_params`: `atoi`, digits, an optional `x`, `b`, `s`, then
     * `difficulty = 0`, an optional legacy `r`, and an optional `d`.
     *
     * The base is the default preset, as `midend_game_id_int` decodes a
     * seed id over `default_params()`. `EATNUM` advances over digits
     * only, so a leading space, sign, or `X` stops the scan before the
     * next marker.
     */
    override fun decodeParams(text: String): Game.Params {
        val base = defaultParams() as Params
        var w = eAtNum(text, 0)
        var pos = skipDigits(text, 0)
        var h = base.h
        var blackpc = base.blackpc
        var symm = base.symm

        if (pos < text.length && text[pos] == 'x') {
            pos++
            h = eAtNum(text, pos)
            pos = skipDigits(text, pos)
        }
        if (pos < text.length && text[pos] == 'b') {
            pos++
            blackpc = eAtNum(text, pos)
            pos = skipDigits(text, pos)
        }
        if (pos < text.length && text[pos] == 's') {
            pos++
            symm = eAtNum(text, pos)
            pos = skipDigits(text, pos)
        } else if (symm == SYMM_ROT4 && w != h) {
            // C copes with user input such as `18x10` by falling back to
            // 2-way rotational symmetry, but only from `SYMM_ROT4`.
            symm = SYMM_ROT2
        }

        var diff = 0
        if (pos < text.length && text[pos] == 'r') {
            diff = 2
            pos++
        }
        if (pos < text.length && text[pos] == 'd') {
            pos++
            diff = eAtNum(text, pos)
        }

        return Params(w, h, blackpc, symm, diff)
    }

    /** `encode_params`: the full form has every field; the short form is `WxH`. */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return if (full) {
            "${p.w}x${p.h}b${p.blackpc}s${p.symm}d${p.diff}"
        } else {
            "${p.w}x${p.h}"
        }
    }

    /**
     * `validate_params`, in C order, then the two enablement guards.
     *
     * The overflow test stays `w > INT_MAX / h`, so the C boundary is
     * visible: `46341x46341` fails it and `46340x46340` passes it. The
     * port then rejects an area above [MAX_AREA], because C would try to
     * allocate the board. A board that is 100% black at difficulty 1 or
     * 2 also loops in C forever; the port rejects it with a message.
     * [newDesc] repeats both guards, so a direct caller cannot hang.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 2 || p.h < 2) {
            return "Width and height must be at least 2"
        }
        if (p.h != 0 && p.w > Int.MAX_VALUE / p.h) {
            return "Width times height must not be unreasonably large"
        }
        if (full) {
            if (p.blackpc < 5 || p.blackpc > 100) {
                return "Percentage of black squares must be between 5% and 100%"
            }
            if (p.w != p.h && p.symm == SYMM_ROT4) {
                return "4-fold symmetry is only available with square grids"
            }
            if ((p.symm == SYMM_ROT4 || p.symm == SYMM_REF4) &&
                p.w < 3 && p.h < 3
            ) {
                return "Width or height must be at least 3 for 4-way symmetry"
            }
            if (p.symm < 0 || p.symm >= SYMM_MAX) {
                return "Unknown symmetry type"
            }
            if (p.diff < 0 || p.diff > DIFFCOUNT) {
                return "Unknown difficulty level"
            }
        }
        if (p.h != 0 && p.w.toLong() * p.h > MAX_AREA) {
            return "Width times height is too large for this build"
        }
        if (full && p.blackpc == 100 && p.diff > 0) {
            return "A board of 100% black squares cannot be generated at this difficulty"
        }
        return null
    }

    /** The generation guards, so a direct [newDesc] call cannot hang. */
    private fun generationGuard(p: Params) {
        require(p.w.toLong() * p.h <= MAX_AREA) {
            "Width times height is too large for this build"
        }
        require(!(p.blackpc == 100 && p.diff > 0)) {
            "A board of 100% black squares cannot be generated at this difficulty"
        }
    }

    /**
     * `validate_desc`: count cells and characters. A run letter advances
     * its length cells; a clue value is not checked against the grid.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val total = p.w * p.h
        var cells = 0
        var pos = 0
        while (cells < total) {
            if (pos >= desc.length) return "Game description shorter than expected"
            val c = desc[pos]
            when {
                c in '0'..'4' -> Unit
                c == 'B' -> Unit
                c in 'a'..'z' -> cells += c - 'a'
                else -> return "Invalid character in game description"
            }
            pos++
            cells++
        }
        if (pos < desc.length || cells > total) {
            return "Game description longer than expected"
        }
        return null
    }

    /** `new_game`: decode the description and keep the initial board. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val w = p.w
        val h = p.h
        val size = w * h
        val lights = IntArray(size)
        val flags = IntArray(size)
        var run = 0
        var pos = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                var c = '\u0000'
                if (run == 0) {
                    require(pos < desc.length) { "description is shorter than the board" }
                    c = desc[pos++]
                    require(c != 'S') { "description contains an S" }
                    if (c in 'a'..'z') run = c - 'a' + 1
                }
                if (run > 0) {
                    c = 'S'
                    run--
                }
                when (c) {
                    in '0'..'4' -> {
                        flags[index] = flags[index] or F_NUMBERED
                        lights[index] = c - '0'
                        flags[index] = flags[index] or F_BLACK
                    }

                    'B' -> flags[index] = flags[index] or F_BLACK
                    'S' -> Unit
                    else -> error("malformed description")
                }
            }
        }
        require(pos == desc.length) { "description is longer than the board" }

        return State(
            shared = Shared(w, h, lights.copyOf(), flags.copyOf()),
            lights = lights,
            flags = flags,
            completed = false,
            usedSolve = false,
            nLights = 0,
        )
    }

    /** `game_configure` (`lightup.c:302-340`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Text("%age of black squares", p.blackpc.toString()),
            ConfigItem.Choices(
                "Symmetry",
                ":None:2-way mirror:2-way rotational:4-way mirror:4-way rotational",
                null,
                p.symm,
            ),
            ConfigItem.Choices(
                "Difficulty",
                ":Easy:Tricky:Hard",
                null,
                p.diff,
            ),
        )
    }

    /** `custom_params` (`lightup.c:342-353`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            eAtNum((items[0] as ConfigItem.Text).value, 0),
            eAtNum((items[1] as ConfigItem.Text).value, 0),
            eAtNum((items[2] as ConfigItem.Text).value, 0),
            (items[3] as ConfigItem.Choices).selected,
            (items[4] as ConfigItem.Choices).selected,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`lightup.c:1875-1890`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Draw non-light marks even when lit",
                true,
                "show-lit-blobs",
            ),
        )

    /** `set_prefs` (`lightup.c:1892-1895`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).drawBlobsWhenLit = prefs.bool("show-lit-blobs")
    }

    /**
     * `new_game_desc`. The RNG draws are: one shuffle of the cell list,
     * two draws per black square plus the odd-width `SYMM_ROT4` draw, and
     * one shuffle in `place_lights`; each attempt repeats the last three.
     * The solver decides the retry, so it is part of the stream.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val source = params as Params
        generationGuard(source)
        val w = source.w
        val h = source.h
        val wh = w * h

        // The comment in C is explicit: build this list once, because a
        // second shuffle would be over a different board layout.
        val numindices = IntArray(wh) { it }
        shuffle(numindices, random)

        var blackpc = source.blackpc
        var board = blankBoard(w, h)

        while (true) {
            for (attempt in 0 until MAX_GRIDGEN_TRIES) {
                setBlacks(board, source.symm, blackpc, random)
                placeLights(board, random)
                placeNumbers(board)

                if (!puzzleIsGood(board, source.diff)) continue

                // Remove the clues the solver did not need, but keep the
                // full board when the stripped one is not good enough.
                val copys = duplicate(board)
                stripUnusedNums(copys)
                val chosen = if (puzzleIsGood(copys, source.diff)) copys else board

                // Remove clues at random while the board stays good.
                for (j in 0 until wh) {
                    val y = numindices[j] / w
                    val x = numindices[j] % w
                    val index = y * w + x
                    if (chosen.flags[index] and F_NUMBERED == 0) continue
                    val number = chosen.lights[index]
                    chosen.lights[index] = 0
                    chosen.flags[index] = chosen.flags[index] and F_NUMBERED.inv()
                    if (!puzzleIsGood(chosen, source.diff)) {
                        chosen.lights[index] = number
                        chosen.flags[index] = chosen.flags[index] or F_NUMBERED
                    }
                }

                if (source.diff > 0 && puzzleIsGood(chosen, source.diff - 1)) {
                    continue
                }

                board = chosen
                return Description(encode(board))
            }

            // A 100% black board at difficulty 1 or 2 never gets here,
            // so the ramp cannot end the loop and the guard above rejects
            // it. Every other value either succeeds or ramps to 90.
            if (blackpc < 90) blackpc += 5
        }
    }

    private fun blankBoard(w: Int, h: Int): State = State(
        shared = Shared(w, h, IntArray(w * h), IntArray(w * h)),
        lights = IntArray(w * h),
        flags = IntArray(w * h),
        completed = false,
        usedSolve = false,
        nLights = 0,
    )

    private fun duplicate(state: State): State = State(
        shared = state.shared,
        lights = state.lights.copyOf(),
        flags = state.flags.copyOf(),
        completed = state.completed,
        usedSolve = state.usedSolve,
        nLights = state.nLights,
    )

    /** `set_blacks` from `lightup.c:584-665`. */
    private fun setBlacks(state: State, symm: Int, blackpc: Int, random: RandomState) {
        val w = state.width
        val h = state.height
        val degree: Int
        val rotate: Boolean
        when (symm) {
            SYMM_NONE -> {
                degree = 1
                rotate = false
            }

            SYMM_ROT2 -> {
                degree = 2
                rotate = true
            }

            SYMM_REF2 -> {
                degree = 2
                rotate = false
            }

            SYMM_ROT4 -> {
                degree = 4
                rotate = true
            }

            SYMM_REF4 -> {
                degree = 4
                rotate = false
            }

            else -> error("unknown symmetry type")
        }
        if (symm == SYMM_ROT4 && h != w) {
            error("4-fold symmetry unavailable without a square grid")
        }

        val wodd = if (w % 2 != 0) 1 else 0
        val hodd = if (h % 2 != 0) 1 else 0
        var rw: Int
        var rh: Int
        if (degree == 4) {
            rw = w / 2
            rh = h / 2
            if (!rotate) rw += wodd
            rh += hodd
        } else if (degree == 2) {
            rw = w
            rh = h / 2 + hodd
        } else {
            rw = w
            rh = h
        }

        cleanBoard(state, leaveBlacks = false)
        val nblack = (rw.toLong() * rh.toLong() * blackpc / 100).toInt()
        val xs = IntArray(4)
        val ys = IntArray(4)
        for (i in 0 until nblack) {
            var x: Int
            var y: Int
            do {
                x = random.upto(rw.toLong()).toInt()
                y = random.upto(rh.toLong()).toInt()
            } while (state.flags[y * w + x] and F_BLACK != 0)
            state.flags[y * w + x] = state.flags[y * w + x] or F_BLACK
        }

        if (symm == SYMM_NONE) return

        for (x in 0 until rw) {
            for (y in 0 until rh) {
                if (degree == 4) {
                    xs[0] = x
                    ys[0] = y
                    xs[1] = w - 1 - (if (rotate) y else x)
                    ys[1] = if (rotate) x else y
                    xs[2] = if (rotate) w - 1 - x else x
                    ys[2] = h - 1 - y
                    xs[3] = if (rotate) y else w - 1 - x
                    ys[3] = h - 1 - (if (rotate) x else y)
                } else {
                    xs[0] = x
                    ys[0] = y
                    xs[1] = if (rotate) w - 1 - x else x
                    ys[1] = h - 1 - y
                }
                for (i in 1 until degree) {
                    state.flags[ys[i] * w + xs[i]] = state.flags[ys[0] * w + xs[0]]
                }
            }
        }

        // `SYMM_ROT4` misses the middle square above.
        if (degree == 4 && rotate && wodd != 0 &&
            random.upto(100) <= blackpc.toLong()
        ) {
            val cx = w / 2 + wodd - 1
            val cy = h / 2 + hodd - 1
            state.flags[cy * w + cx] = state.flags[cy * w + cx] or F_BLACK
        }
    }

    private fun cleanBoard(state: State, leaveBlacks: Boolean) {
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = y * state.width + x
                state.flags[index] = if (leaveBlacks) state.flags[index] and F_BLACK else 0
                state.lights[index] = 0
            }
        }
        state.nLights = 0
    }

    /**
     * `place_lights`: start with a light on every white cell and remove
     * lights that overlap at random, without ever leaving a cell dark.
     *
     * C asserts that the loop resolves every overlap. The port returns a
     * board with the overlap instead; `puzzle_is_good` then rejects the
     * attempt, which is deterministic. The path was not reached in the
     * sweeps recorded in the specification.
     */
    private fun placeLights(state: State, random: RandomState) {
        val w = state.width
        val h = state.height
        val wh = w * h
        val numindices = IntArray(wh) { it }
        shuffle(numindices, random)

        for (x in 0 until w) {
            for (y in 0 until h) {
                val index = y * w + x
                state.flags[index] = state.flags[index] and F_MARK.inv()
                if (state.flags[index] and F_BLACK == 0) {
                    setLight(state, x, y, true)
                }
            }
        }

        for (i in 0 until wh) {
            val y = numindices[i] / w
            val x = numindices[i] % w
            val index = y * w + x
            if (state.flags[index] and F_LIGHT == 0) continue
            if (state.flags[index] and F_MARK != 0) continue

            var n = 0
            forEachLit(state, x, y, origin = false) { lx, ly ->
                if (state.flags[ly * w + lx] and F_LIGHT != 0) n++
            }
            if (n == 0) continue

            var dark = 0
            forEachLit(state, x, y, origin = false) { lx, ly ->
                if (state.flags[ly * w + lx] and F_LIGHT != 0 &&
                    checkDark(state, lx, ly)
                ) {
                    dark++
                }
            }
            if (dark == 0) {
                forEachLit(state, x, y, origin = false) { lx, ly ->
                    setLight(state, lx, ly, false)
                }
                state.flags[index] = state.flags[index] or F_MARK
            }

            if (!gridOverlap(state)) return
        }
    }

    /** `place_numbers`: fill every black cell with its adjacent light count. */
    private fun placeNumbers(state: State) {
        val w = state.width
        val h = state.height
        for (x in 0 until w) {
            for (y in 0 until h) {
                val index = y * w + x
                if (state.flags[index] and F_BLACK == 0) continue
                var n = 0
                forEachSurround(state, x, y) { sx, sy ->
                    if (state.flags[sy * w + sx] and F_LIGHT != 0) n++
                }
                state.flags[index] = state.flags[index] or F_NUMBERED
                state.lights[index] = n
            }
        }
    }

    private fun encode(state: State): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder(w * h)
        var run = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                if (state.flags[index] and F_BLACK != 0) {
                    if (run != 0) {
                        out.append(('a'.code - 1 + run).toChar())
                        run = 0
                    }
                    out.append(
                        if (state.flags[index] and F_NUMBERED != 0) {
                            ('0'.code + state.lights[index]).toChar()
                        } else {
                            'B'
                        },
                    )
                } else {
                    if (run == 26) {
                        out.append(('a'.code - 1 + run).toChar())
                        run = 0
                    }
                    run++
                }
            }
        }
        if (run != 0) out.append(('a'.code - 1 + run).toChar())
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Completion and clues
    // ------------------------------------------------------------------

    private fun gridLit(state: State): Boolean {
        val w = state.width
        for (x in 0 until w) {
            for (y in 0 until state.height) {
                val index = y * w + x
                if (state.flags[index] and F_BLACK != 0) continue
                if (state.lights[index] == 0) return false
            }
        }
        return true
    }

    private fun gridOverlap(state: State): Boolean {
        val w = state.width
        for (x in 0 until w) {
            for (y in 0 until state.height) {
                val index = y * w + x
                if (state.flags[index] and F_LIGHT == 0) continue
                if (state.lights[index] > 1) return true
            }
        }
        return false
    }

    private fun numberWrong(state: State, x: Int, y: Int): Boolean {
        val w = state.width
        val clue = state.lights[y * w + x]
        var n = 0
        var empty = 0
        forEachSurround(state, x, y) { sx, sy ->
            val index = sy * w + sx
            val flags = state.flags[index]
            if (flags and F_LIGHT != 0) {
                n++
                return@forEachSurround
            }
            if (flags and F_BLACK != 0) return@forEachSurround
            if (flags and F_IMPOSSIBLE != 0) return@forEachSurround
            if (state.lights[index] != 0) return@forEachSurround
            empty++
        }
        return n > clue || n + empty < clue
    }

    private fun numberCorrect(state: State, x: Int, y: Int): Boolean {
        val w = state.width
        var n = 0
        forEachSurround(state, x, y) { sx, sy ->
            if (state.flags[sy * w + sx] and F_LIGHT != 0) n++
        }
        return n == state.lights[y * w + x]
    }

    private fun gridAddsUp(state: State): Boolean {
        val w = state.width
        for (x in 0 until w) {
            for (y in 0 until state.height) {
                val index = y * w + x
                if (state.flags[index] and F_NUMBERED == 0) continue
                if (!numberCorrect(state, x, y)) return false
            }
        }
        return true
    }

    private fun gridCorrect(state: State): Boolean =
        gridLit(state) && !gridOverlap(state) && gridAddsUp(state)

    // ------------------------------------------------------------------
    // Lights and the cells they illuminate
    // ------------------------------------------------------------------

    /**
     * `list_lights` plus the `FOREACHLIT` order: the horizontal run first,
     * left to right, then the vertical run top to bottom, including the
     * origin in the vertical pass when [origin] is true.
     */
    private inline fun forEachLit(
        state: State,
        ox: Int,
        oy: Int,
        origin: Boolean,
        action: (Int, Int) -> Unit,
    ) {
        val w = state.width
        val h = state.height
        val flags = state.flags
        var minX = ox
        var maxX = ox
        var minY = oy
        var maxY = oy

        var x = ox - 1
        while (x >= 0) {
            if (flags[oy * w + x] and F_BLACK != 0) break
            minX = x
            x--
        }
        x = ox + 1
        while (x < w) {
            if (flags[oy * w + x] and F_BLACK != 0) break
            maxX = x
            x++
        }
        var y = oy - 1
        while (y >= 0) {
            if (flags[y * w + ox] and F_BLACK != 0) break
            minY = y
            y--
        }
        y = oy + 1
        while (y < h) {
            if (flags[y * w + ox] and F_BLACK != 0) break
            maxY = y
            y++
        }

        for (lx in minX..maxX) {
            if (lx == ox) continue
            action(lx, oy)
        }
        for (ly in minY..maxY) {
            if (!origin && ly == oy) continue
            action(ox, ly)
        }
    }

    /** `get_surrounds` order: left, right, up, down. */
    internal inline fun forEachSurround(
        state: State,
        ox: Int,
        oy: Int,
        action: (Int, Int) -> Unit,
    ) {
        if (ox > 0) action(ox - 1, oy)
        if (ox < state.width - 1) action(ox + 1, oy)
        if (oy > 0) action(ox, oy - 1)
        if (oy < state.height - 1) action(ox, oy + 1)
    }

    /** `set_light`: toggle a light and adjust the illumination counts. */
    internal fun setLight(state: State, x: Int, y: Int, on: Boolean) {
        val w = state.width
        val index = y * w + x
        val flags = state.flags
        var diff = 0
        if (!on && flags[index] and F_LIGHT != 0) {
            diff = -1
            flags[index] = flags[index] and F_LIGHT.inv()
            state.nLights--
        } else if (on && flags[index] and F_LIGHT == 0) {
            diff = 1
            flags[index] = flags[index] or F_LIGHT
            state.nLights++
        }
        if (diff != 0) {
            forEachLit(state, x, y, origin = true) { lx, ly ->
                val target = ly * w + lx
                state.lights[target] += diff
            }
        }
    }

    /** `check_dark`: true when removing a light would leave a cell dark. */
    private fun checkDark(state: State, x: Int, y: Int): Boolean {
        var dark = false
        forEachLit(state, x, y, origin = true) { lx, ly ->
            if (state.lights[ly * state.width + lx] == 1) dark = true
        }
        return dark
    }

    // ------------------------------------------------------------------
    // Solver
    // ------------------------------------------------------------------

    /** `sscratch` from `lightup.c:931-937`. */
    private class Scratch(size: Int) {
        val x = IntArray(size)
        val y = IntArray(size)
        val count = IntArray(size)

        fun clear() {
            x.fill(0)
            y.fill(0)
            count.fill(0)
        }
    }

    private fun couldPlaceLight(flags: Int, lights: Int): Boolean =
        flags and (F_BLACK or F_IMPOSSIBLE) == 0 && lights == 0

    private fun couldPlaceLightXy(state: State, x: Int, y: Int): Boolean {
        val index = y * state.width + x
        return couldPlaceLight(state.flags[index], state.lights[index])
    }

    /** `try_solve_light`: a light where an unlit cell has one source. */
    private fun trySolveLight(state: State, ox: Int, oy: Int, flags: Int, lights: Int): Boolean {
        if (lights > 0) return false
        if (flags and F_BLACK != 0) return false

        var sx = 0
        var sy = 0
        var n = 0
        forEachLit(state, ox, oy, origin = true) { lx, ly ->
            val index = ly * state.width + lx
            if (state.flags[index] and F_IMPOSSIBLE != 0) return@forEachLit
            if (state.lights[index] > 0) return@forEachLit
            sx = lx
            sy = ly
            n++
        }
        if (n == 1) {
            setLight(state, sx, sy, true)
            return true
        }
        return false
    }

    /** `try_solve_number`: a clue with one arrangement of its lights. */
    private fun trySolveNumber(state: State, nx: Int, ny: Int, nflags: Int, nlights: Int): Boolean {
        if (nflags and F_NUMBERED == 0) return false
        val w = state.width
        val clues = IntArray(4)
        val clueYs = IntArray(4)
        val marks = BooleanArray(4)
        var ns = 0
        forEachSurround(state, nx, ny) { sx, sy ->
            clues[ns] = sx
            clueYs[ns] = sy
            ns++
        }
        var nl = nlights
        for (i in 0 until ns) {
            val index = clueYs[i] * w + clues[i]
            val flags = state.flags[index]
            val lights = state.lights[index]
            if (flags and F_LIGHT != 0) {
                nl--
                marks[i] = true
            } else if (!couldPlaceLight(flags, lights)) {
                marks[i] = true
            }
        }
        var spaces = ns
        for (i in 0 until ns) if (marks[i]) spaces--
        if (spaces == 0) return false

        var ret = false
        if (nl == 0) {
            state.flags[ny * w + nx] = state.flags[ny * w + nx] or F_NUMBERUSED
            for (i in 0 until ns) {
                if (marks[i]) continue
                val index = clueYs[i] * w + clues[i]
                state.flags[index] = state.flags[index] or F_IMPOSSIBLE
                ret = true
            }
        } else if (nl == spaces) {
            state.flags[ny * w + nx] = state.flags[ny * w + nx] or F_NUMBERUSED
            for (i in 0 until ns) {
                if (marks[i]) continue
                setLight(state, clues[i], clueYs[i], true)
                ret = true
            }
        }
        return ret
    }

    /**
     * The squares that would rule out a light at `(x, y)`: the squares
     * that would light it, and the empty squares next to a neighbouring
     * clue that has one light left. The enumeration order is part of the
     * solver's result.
     */
    private inline fun forEachRuleOut(
        state: State,
        x: Int,
        y: Int,
        action: (Int, Int) -> Unit,
    ) {
        forEachLit(state, x, y, origin = false) { lx, ly ->
            if (couldPlaceLightXy(state, lx, ly)) action(lx, ly)
        }
        forEachSurround(state, x, y) { cxx, cyy ->
            if (state.flags[cyy * state.width + cxx] and F_NUMBERED == 0) {
                return@forEachSurround
            }
            var current = 0
            forEachSurround(state, cxx, cyy) { sx, sy ->
                if (state.flags[sy * state.width + sx] and F_LIGHT != 0) current++
            }
            val total = state.lights[cyy * state.width + cxx]
            if (current + 1 == total) {
                forEachSurround(state, cxx, cyy) { sx, sy ->
                    if (sx == x && sy == y) return@forEachSurround
                    if (couldPlaceLightXy(state, sx, sy)) action(sx, sy)
                }
            }
        }
    }

    /** `trl_callback_search`: mark every set member this light rules out. */
    private fun searchRuleOut(state: State, dx: Int, dy: Int, scratch: Scratch, n: Int) {
        forEachRuleOut(state, dx, dy) { lx, ly ->
            for (i in 0 until n) {
                if (scratch.x[i] == lx && scratch.y[i] == ly) scratch.count[i] = 1
            }
        }
    }

    /** `trl_callback_discount`: mark a light impossible when it rules out the set. */
    private fun discountRuleOut(state: State, x: Int, y: Int, scratch: Scratch, n: Int): Boolean {
        var didsth = false
        forEachRuleOut(state, x, y) { dx, dy ->
            val index = dy * state.width + dx
            if (state.flags[index] and F_IMPOSSIBLE != 0) return@forEachRuleOut
            for (i in 0 until n) scratch.count[i] = 0
            searchRuleOut(state, dx, dy, scratch, n)
            var covers = true
            for (i in 0 until n) {
                if (scratch.count[i] == 0) {
                    covers = false
                    break
                }
            }
            if (covers) {
                state.flags[index] = state.flags[index] or F_IMPOSSIBLE
                didsth = true
            }
        }
        return didsth
    }

    /** `discount_set`: pick the square with the fewest rule-outs, then try it. */
    private fun discountSet(state: State, scratch: Scratch, n: Int): Boolean {
        if (n == 0) return false

        for (i in 0 until n) {
            var count = 0
            forEachRuleOut(state, scratch.x[i], scratch.y[i]) { _, _ -> count++ }
            scratch.count[i] = count
        }

        var besti = -1
        var bestn = state.width + state.height
        for (i in 0 until n) {
            if (scratch.count[i] < bestn) {
                bestn = scratch.count[i]
                besti = i
            }
        }
        if (besti < 0) return false
        return discountRuleOut(state, scratch.x[besti], scratch.y[besti], scratch, n)
    }

    /** `discount_unlit`: build a MAKESLIGHT set from an unlit square. */
    private fun discountUnlit(state: State, x: Int, y: Int, scratch: Scratch): Boolean {
        scratch.clear()
        var n = 0
        forEachLit(state, x, y, origin = true) { lx, ly ->
            if (couldPlaceLightXy(state, lx, ly)) {
                scratch.x[n] = lx
                scratch.y[n] = ly
                n++
            }
        }
        return discountSet(state, scratch, n)
    }

    /**
     * `discount_clue`: every subset of `n - m + 1` of the `n` empty
     * squares next to a clue is a MAKESLIGHT set. `Combinations` is the
     * ported `combi` library, and its order is part of the result.
     */
    private fun discountClue(state: State, x: Int, y: Int, scratch: Scratch): Boolean {
        val w = state.width
        var m = state.lights[y * w + x]
        if (m == 0) return false

        val emptyX = IntArray(4)
        val emptyY = IntArray(4)
        var n = 0
        forEachSurround(state, x, y) { sx, sy ->
            val index = sy * w + sx
            val flags = state.flags[index]
            val lights = state.lights[index]
            if (flags and F_LIGHT != 0) m--
            if (couldPlaceLight(flags, lights)) {
                emptyX[n] = sx
                emptyY[n] = sy
                n++
            }
        }
        if (n == 0) return false
        if (m < 0 || m > n) return false

        var didsth = false
        val combi = Combinations(n - m + 1, n)
        while (true) {
            val combination = combi.next() ?: break
            scratch.clear()
            var slen = 0
            for (i in combination) {
                scratch.x[slen] = emptyX[i]
                scratch.y[slen] = emptyY[i]
                slen++
            }
            if (discountSet(state, scratch, slen)) didsth = true
        }
        return didsth
    }

    private fun flagsFromDifficulty(difficulty: Int): Int {
        var sflags = F_SOLVE_FORCEUNIQUE
        if (difficulty >= 1) sflags = sflags or F_SOLVE_DISCOUNTSETS
        if (difficulty >= 2) sflags = sflags or F_SOLVE_ALLOWRECURSE
        return sflags
    }

    /**
     * `solve_sub`. The branches are combined exactly as C does: a copy
     * that succeeds replaces the caller's lights and flags, which is how
     * the returned board keeps or loses `F_IMPOSSIBLE` marks.
     */
    private fun solveSub(
        state: State,
        solveFlags: Int,
        depth: Int,
        maxDepth: IntArray?,
    ): Int {
        if (maxDepth != null && maxDepth[0] < depth) maxDepth[0] = depth
        val maxRecurse = if (solveFlags and F_SOLVE_ALLOWRECURSE != 0) MAXRECURSE else 0
        val scratch = Scratch(state.width + state.height)
        val w = state.width
        val h = state.height

        while (true) {
            if (gridOverlap(state)) return 0
            if (gridCorrect(state)) return 1

            var canPlace = 0
            var didstuff = false
            for (x in 0 until w) {
                for (y in 0 until h) {
                    val index = y * w + x
                    val flags = state.flags[index]
                    val lights = state.lights[index]
                    if (couldPlaceLight(flags, lights)) canPlace++
                    if (trySolveLight(state, x, y, flags, lights)) didstuff = true
                    if (trySolveNumber(state, x, y, flags, lights)) didstuff = true
                }
            }
            if (didstuff) continue
            if (canPlace == 0) return 0

            if (solveFlags and F_SOLVE_DISCOUNTSETS != 0) {
                var reduced = false
                loop@ for (x in 0 until w) {
                    for (y in 0 until h) {
                        val index = y * w + x
                        val flags = state.flags[index]
                        val lights = state.lights[index]
                        if (flags and F_BLACK == 0 && lights == 0) {
                            if (discountUnlit(state, x, y, scratch)) {
                                reduced = true
                                break@loop
                            }
                        } else if (flags and F_NUMBERED != 0) {
                            if (discountClue(state, x, y, scratch)) {
                                reduced = true
                                break@loop
                            }
                        }
                    }
                }
                if (reduced) continue
            }

            if (depth >= maxRecurse) return -1

            var bestn = 0
            var bestx = -1
            var besty = -1
            for (x in 0 until w) {
                for (y in 0 until h) {
                    val index = y * w + x
                    if (!couldPlaceLight(state.flags[index], state.lights[index])) continue
                    var n = 0
                    forEachLit(state, x, y, origin = true) { lx, ly ->
                        if (state.lights[ly * w + lx] == 0) n++
                    }
                    if (n > bestn) {
                        bestn = n
                        bestx = x
                        besty = y
                    }
                }
            }
            if (bestx < 0) return 0

            val scopy = duplicate(state)
            state.flags[besty * w + bestx] =
                state.flags[besty * w + bestx] or F_IMPOSSIBLE
            val selfSoluble = solveSub(state, solveFlags, depth + 1, maxDepth)
            if (solveFlags and F_SOLVE_FORCEUNIQUE == 0 && selfSoluble > 0) {
                return selfSoluble
            }

            setLight(scopy, bestx, besty, true)
            val copySoluble = solveSub(scopy, solveFlags, depth + 1, maxDepth)

            return if (solveFlags and F_SOLVE_FORCEUNIQUE != 0 &&
                (copySoluble < 0 || selfSoluble < 0)
            ) {
                -1
            } else if (copySoluble <= 0) {
                selfSoluble
            } else if (selfSoluble <= 0) {
                scopy.lights.copyInto(state.lights)
                scopy.flags.copyInto(state.flags)
                copySoluble
            } else {
                copySoluble + selfSoluble
            }
        }
    }

    /** `dosolve`: clear `F_NUMBERUSED` and run the solver once at depth 0. */
    private fun dosolve(state: State, solveFlags: Int, maxDepth: IntArray?): Int {
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = y * state.width + x
                state.flags[index] = state.flags[index] and F_NUMBERUSED.inv()
            }
        }
        return solveSub(state, solveFlags, 0, maxDepth)
    }

    private fun stripUnusedNums(state: State) {
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = y * state.width + x
                if (state.flags[index] and F_NUMBERED != 0 &&
                    state.flags[index] and F_NUMBERUSED == 0
                ) {
                    state.flags[index] = state.flags[index] and F_NUMBERED.inv()
                    state.lights[index] = 0
                }
            }
        }
    }

    private fun unplaceLights(state: State) {
        for (x in 0 until state.width) {
            for (y in 0 until state.height) {
                val index = y * state.width + x
                if (state.flags[index] and F_LIGHT != 0) setLight(state, x, y, false)
                state.flags[index] = state.flags[index] and F_IMPOSSIBLE.inv()
                state.flags[index] = state.flags[index] and F_NUMBERUSED.inv()
            }
        }
    }

    private fun puzzleIsGood(state: State, difficulty: Int): Boolean {
        unplaceLights(state)
        val sflags = flagsFromDifficulty(difficulty)
        val maxDepth = IntArray(1)
        val nsol = dosolve(state, sflags, maxDepth)
        if (sflags and F_SOLVE_ALLOWRECURSE == 0 && maxDepth[0] > 0) return false
        return nsol == 1
    }

    /**
     * `solve_game`. The current board is tried first, then the decoded
     * first state. The diff walks the current board in column-major
     * order and names `L` or `I` by which flag differs, so the solver's
     * `F_IMPOSSIBLE` marks are part of the move. `aux` is not read.
     */
    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    /** `solve_game` (`lightup.c:1726-1747`): both boards failed to solve. */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val move = solveLightup(state as State)
        return SolveOutcome(
            move,
            if (move == null) "Unable to find a solution to this puzzle." else null,
        )
    }

    private fun solveLightup(s: State): String? {
        val w = s.width
        val h = s.height
        val sflags = F_SOLVE_ALLOWRECURSE or F_SOLVE_DISCOUNTSETS

        var solved = duplicate(s)
        if (dosolve(solved, sflags, null) <= 0) {
            solved = State(
                shared = s.shared,
                lights = s.shared.initialLights.copyOf(),
                flags = s.shared.initialFlags.copyOf(),
                completed = false,
                usedSolve = false,
                nLights = 0,
            )
            if (dosolve(solved, sflags, null) <= 0) return null
        }

        val out = StringBuilder("S")
        for (x in 0 until w) {
            for (y in 0 until h) {
                val index = y * w + x
                val old = s.flags[index]
                val now = solved.flags[index]
                if ((old and F_LIGHT) != (now and F_LIGHT)) {
                    out.append(";L").append(x).append(',').append(y)
                } else if ((old and F_IMPOSSIBLE) != (now and F_IMPOSSIBLE)) {
                    out.append(";I").append(x).append(',').append(y)
                }
            }
        }
        return out.toString()
    }

    /** `execute_move`: `S`, and `L`/`I` pairs separated by `;`. */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        if (move.isEmpty()) return null
        val w = s.width
        val h = s.height
        val next = duplicate(s)
        var pos = 0

        while (pos < move.length) {
            val c = move[pos]
            if (c == 'S') {
                next.usedSolve = true
                pos++
            } else if (c == 'L' || c == 'I') {
                pos++
                val x = scanInt(move, pos) ?: return null
                pos = x.end
                if (pos >= move.length || move[pos] != ',') return null
                pos++
                val y = scanInt(move, pos) ?: return null
                pos = y.end
                if (x.value < 0 || y.value < 0 || x.value >= w || y.value >= h) {
                    return null
                }
                val index = y.value * w + x.value
                if (next.flags[index] and F_BLACK != 0) return null
                if (c == 'L') {
                    next.flags[index] = next.flags[index] and F_IMPOSSIBLE.inv()
                    setLight(next, x.value, y.value, next.flags[index] and F_LIGHT == 0)
                } else {
                    setLight(next, x.value, y.value, false)
                    next.flags[index] = next.flags[index] xor F_IMPOSSIBLE
                }
            } else {
                return null
            }

            if (pos < move.length && move[pos] == ';') {
                pos++
            } else if (pos < move.length) {
                return null
            }
        }

        if (gridCorrect(next)) next.completed = true
        return next
    }

    /** `game_status`: 1 when complete, otherwise 0. */
    override fun status(state: Game.State): Int = if ((state as State).completed) 1 else 0

    /** `wants_statusbar` is false, so the status text is empty. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** `game_text_format`: a grid of `+`, `-`, and `|` with one cell per box. */
    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.width
        val h = s.height
        val out = StringBuilder((h + h + 1) * (w + w + 2) + 1)
        for (y in 0..h) {
            for (x in 0..w) {
                out.append('+')
                if (x < w) out.append('-')
            }
            out.append('\n')
            if (y < h) {
                for (x in 0..w) {
                    out.append('|')
                    if (x < w) {
                        val index = y * w + x
                        val flags = s.flags[index]
                        val lights = s.lights[index]
                        when {
                            flags and F_BLACK != 0 ->
                                if (flags and F_NUMBERED != 0) {
                                    out.append(('0'.code + lights).toChar())
                                } else {
                                    out.append('#')
                                }

                            flags and F_LIGHT != 0 -> out.append('L')
                            flags and F_IMPOSSIBLE != 0 -> out.append('x')
                            lights > 0 -> out.append('.')
                            else -> out.append(' ')
                        }
                    }
                }
                out.append('\n')
            }
        }
        return out.toString()
    }

    /**
     * `interpret_move`. The `STYLUS_BASED` branch is compiled in both
     * builds: the left cycle is empty, light, impossible; the right
     * cycle is empty, impossible, light. No modifier is stripped here, so
     * a modified cursor key never matches [Buttons.isCursorMove].
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
        var action = 0
        var cx = -1
        var cy = -1
        var nullret: MoveResult = MoveResult.NoEffect

        when {
            button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON -> {
                if (u.curVisible) nullret = MoveResult.UiUpdate
                u.curVisible = false
                cx = fromCoord(x, tileSize)
                cy = fromCoord(y, tileSize)
                action = if (button == Buttons.LEFT_BUTTON) FLIP_LIGHT else FLIP_IMPOSSIBLE
            }

            button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2 ||
                button == 'i'.code || button == 'I'.code -> {
                if (u.curVisible) {
                    cx = u.curX
                    cy = u.curY
                    action = if (button == 'i'.code || button == 'I'.code ||
                        button == Buttons.CURSOR_SELECT2
                    ) {
                        FLIP_IMPOSSIBLE
                    } else {
                        FLIP_LIGHT
                    }
                }
                u.curVisible = true
            }

            Buttons.isCursorMove(button) -> {
                nullret = moveCursor(button, u, s.width, s.height)
            }

            else -> return MoveResult.Unused
        }

        when (action) {
            FLIP_LIGHT, FLIP_IMPOSSIBLE -> {
                if (cx < 0 || cy < 0 || cx >= s.width || cy >= s.height) return nullret
                val flags = s.flags[cy * s.width + cx]
                if (flags and F_BLACK != 0) return nullret
                val c: Char
                if (action == FLIP_LIGHT) {
                    c = if (flags and (F_IMPOSSIBLE or F_LIGHT) != 0) 'I' else 'L'
                } else {
                    c = if (flags and (F_IMPOSSIBLE or F_LIGHT) != 0) 'L' else 'I'
                }
                return MoveResult.Move("$c$cx,$cy")
            }

            else -> return nullret
        }
    }

    private const val FLIP_LIGHT = 1
    private const val FLIP_IMPOSSIBLE = 2

    /** `move_cursor` with `wrap = false`. */
    private fun moveCursor(button: Int, ui: Ui, w: Int, h: Int): MoveResult {
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
        ui.curX = (ui.curX + dx).coerceIn(0, w - 1)
        ui.curY = (ui.curY + dy).coerceIn(0, h - 1)
        if (!ui.curVisible) {
            ui.curVisible = true
            return MoveResult.UiUpdate
        }
        return if (ui.curX != ox || ui.curY != oy) MoveResult.UiUpdate else MoveResult.NoEffect
    }

    /** `FROMCOORD`, with C truncation toward zero. */
    private fun fromCoord(value: Int, tileSize: Int): Int {
        val border = tileSize / 2
        return (value - border + tileSize) / tileSize - 1
    }

    /**
     * `game_changed_state`: hide the cursor when the new state is
     * complete, and report only an honest completion.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        if (next.completed) (ui as Ui).curVisible = false
        return next.completed && !next.usedSolve &&
            oldState != null && !(oldState as State).completed
    }

    /**
     * `game_request_keys` is NULL in C, so the midend reports no labels
     * and keeps the default arrow mode.
     */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /** `game_compute_size`: `ts * cells + 2 * (ts / 2)`, with integer division. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val border = tileSize / 2
        return Size(tileSize * p.w + 2 * border, tileSize * p.h + 2 * border)
    }

    /** C reads `PUZZLES_SHOW_CURSOR`; the seam starts hidden and unmarked. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `game_get_cursor_location`: a rectangle only for a visible cursor. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        val border = tileSize / 2
        return Game.CursorRect(
            u.curX * tileSize + border,
            u.curY * tileSize + border,
            tileSize,
            tileSize,
        )
    }

    /**
     * `current_key_label`. The C reads the grid before it tests the
     * cursor and without a bounds check; the port tests the cursor and
     * the bounds first and returns "" for either, which is the same
     * answer for every cursor the game itself can produce.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (!u.curVisible) return ""
        if (u.curX < 0 || u.curY < 0 || u.curX >= s.width || u.curY >= s.height) return ""
        val flags = s.flags[u.curY * s.width + u.curX]
        if (button == Buttons.CURSOR_SELECT) {
            if (flags and (F_BLACK or F_IMPOSSIBLE) != 0) return ""
            if (flags and F_LIGHT != 0) return "Clear"
            return "Light"
        }
        if (button == Buttons.CURSOR_SELECT2) {
            if (flags and (F_BLACK or F_LIGHT) != 0) return ""
            if (flags and F_IMPOSSIBLE != 0) return "Clear"
            return "Mark"
        }
        return ""
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per cell: `clue 2` on a numbered wall, `wall`, `light`,
     * `no light`, or `empty`. Both pointer buttons are real moves, so
     * each open cell offers `Light` and `No light`; a wall ignores both.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val t = tileSize
        val cells = gridSpeech(
            width = w,
            height = s.height,
            tileSize = t,
            left = t / 2,
            top = t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                val index = y * w + x
                val flags = s.flags[index]
                val content = when {
                    flags and F_BLACK != 0 && flags and F_NUMBERED != 0 ->
                        "clue ${s.lights[index]}"

                    flags and F_BLACK != 0 -> "wall"
                    flags and F_LIGHT != 0 -> "light"
                    flags and F_IMPOSSIBLE != 0 -> "no light"
                    else -> "empty"
                }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                content + cursor
            },
            actions = { x, y ->
                if (s.flags[y * w + x] and F_BLACK != 0) {
                    emptyList()
                } else {
                    listOf(
                        SpokenAction("Light", Buttons.LEFT_BUTTON),
                        SpokenAction("No light", Buttons.RIGHT_BUTTON),
                    )
                }
            },
        )
        return cells.capped(cells.units.getOrNull(u.curY * w + u.curX))
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`: only the background comes from the shell. The grid
     * is `background / 1.5` and the cursor is `background / 2`, so the
     * roles keep their meaning on a themed background.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) {
            val bg = background[i]
            ret[COL_BACKGROUND * 3 + i] = bg
            ret[COL_GRID * 3 + i] = bg / 1.5f
            ret[COL_BLACK * 3 + i] = 0.0f
            ret[COL_LIGHT * 3 + i] = 1.0f
            ret[COL_CURSOR * 3 + i] = bg / 2.0f
        }
        ret[COL_ERROR * 3] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.25f
        ret[COL_ERROR * 3 + 2] = 0.25f
        ret[COL_LIT * 3] = 1.0f
        ret[COL_LIT * 3 + 1] = 1.0f
        ret[COL_LIT * 3 + 2] = 0.0f
        return ret
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height)
    }

    /** `tile_flags`: the per-cell cache word. The preference is not in it. */
    private fun tileFlags(state: State, ui: Ui, x: Int, y: Int): Int {
        val w = state.width
        val index = y * w + x
        val flags = state.flags[index]
        val lights = state.lights[index]
        var ret = 0
        if (ui.curVisible && x == ui.curX && y == ui.curY) ret = ret or DF_CURSOR
        if (flags and F_BLACK != 0) {
            ret = ret or DF_BLACK
            if (flags and F_NUMBERED != 0) {
                if (numberWrong(state, x, y)) ret = ret or DF_NUMBERWRONG
                ret = ret or DF_NUMBERED
            }
        } else {
            if (lights > 0) ret = ret or DF_LIT
            if (flags and F_LIGHT != 0) {
                ret = ret or DF_LIGHT
                if (lights > 1) ret = ret or DF_OVERLAP
            }
            if (flags and F_IMPOSSIBLE != 0) ret = ret or DF_IMPOSSIBLE
        }
        return ret
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
        val w = s.width
        val h = s.height
        val border = tileSize / 2

        if (!ds.started) {
            drawRectOutline(
                sink,
                border - 1,
                border - 1,
                tileSize * w + 2,
                tileSize * h + 2,
                COL_GRID,
            )
            sink.update(0, 0, tileSize * w + 2 * border, tileSize * h + 2 * border)
            ds.started = true
        }

        // C draws column-major, and the raw trace sees that order.
        for (x in 0 until w) {
            for (y in 0 until h) {
                val index = y * w + x
                val flags = tileFlags(s, u, x, y)
                if (flags != ds.flags[index]) {
                    ds.flags[index] = flags
                    tileRedraw(sink, s, u, x, y, tileSize, flags)
                }
            }
        }
    }

    private fun tileRedraw(
        sink: DrawSink,
        state: State,
        ui: Ui,
        x: Int,
        y: Int,
        tileSize: Int,
        dsFlags: Int,
    ) {
        val w = state.width
        val index = y * w + x
        val border = tileSize / 2
        val dx = x * tileSize + border
        val dy = y * tileSize + border
        val crad = 3 * (tileSize - 1) / 8
        // No flash: the seam has no flash time and the oracle never
        // flashes, so DF_FLASH is never set.
        val lit = COL_LIT

        if (dsFlags and DF_BLACK != 0) {
            sink.rect(dx, dy, tileSize, tileSize, COL_BLACK)
            if (dsFlags and DF_NUMBERED != 0) {
                val ccol = if (dsFlags and DF_NUMBERWRONG != 0) COL_ERROR else COL_LIGHT
                sink.text(
                    dx + tileSize / 2,
                    dy + tileSize / 2,
                    Text.FONT_VARIABLE,
                    tileSize * 3 / 5,
                    Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                    ccol,
                    state.lights[index].toString(),
                )
            }
        } else {
            sink.rect(
                dx,
                dy,
                tileSize,
                tileSize,
                if (dsFlags and DF_LIT != 0) lit else COL_BACKGROUND,
            )
            drawRectOutline(sink, dx, dy, tileSize, tileSize, COL_GRID)
            if (dsFlags and DF_LIGHT != 0) {
                val lcol = if (dsFlags and DF_OVERLAP != 0) COL_ERROR else COL_LIGHT
                sink.circle(
                    dx + tileSize / 2,
                    dy + tileSize / 2,
                    crad,
                    lcol,
                    COL_BLACK,
                )
            } else if (dsFlags and DF_IMPOSSIBLE != 0) {
                if (dsFlags and DF_LIT == 0 || ui.drawBlobsWhenLit) {
                    val rlen = tileSize / 4
                    sink.rect(
                        dx + tileSize / 2 - rlen / 2,
                        dy + tileSize / 2 - rlen / 2,
                        rlen,
                        rlen,
                        COL_BLACK,
                    )
                }
            }
        }

        if (dsFlags and DF_CURSOR != 0) {
            val coff = tileSize / 8
            drawRectOutline(
                sink,
                dx + coff,
                dy + coff,
                tileSize - coff * 2,
                tileSize - coff * 2,
                COL_CURSOR,
            )
        }

        sink.update(dx, dy, tileSize, tileSize)
    }

    /** `draw_rect_outline` is a four-point polygon, not a rect. */
    private fun drawRectOutline(
        sink: DrawSink,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        colour: Int,
    ) {
        val x1 = x + w - 1
        val y1 = y + h - 1
        sink.poly(intArrayOf(x, y, x, y1, x1, y1, x1, y), -1, colour)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** `shuffle` from `misc.c`: `n - 1` draws with limits `n..2`. */
    private fun shuffle(array: IntArray, random: RandomState) {
        for (i in array.size - 1 downTo 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
            }
        }
    }

    /** The `atoi` of `EATNUM`: white space, an optional sign, then digits. */
    private fun eAtNum(text: String, start: Int): Int {
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

    /** `EATNUM` advances over digits only, not over white space or a sign. */
    private fun skipDigits(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    private class ScannedInt(val value: Int, val end: Int)

    /**
     * One `%d` conversion of `sscanf("%d,%d%n")`: white space, an
     * optional sign, then at least one digit. Overflow saturates and then
     * truncates to `int`, as glibc does.
     */
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

    private fun isCSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
