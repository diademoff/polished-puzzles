package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `guess.c`: the Mastermind clone.
 *
 * The player fills one row with colors and submits it. The answer marks
 * exact positions black and correct colors in the wrong position white.
 * The solution is a pure function of the seed, stored obfuscated in the
 * description.
 *
 * The game has a full `game_ui`: a half-finished row, holds, two
 * cursors, a drag sprite, and a cached hint. It writes that frontend
 * state to the save file's `UI` record and draws with a per-row cache
 * and one blitter. The palette, the geometry arithmetic, the loose move
 * and UI grammars, and the retry rule of the generator are parity
 * rules.
 *
 * The solution is hidden state. It is never part of `id()`, the status
 * text, the text format, or the `UI` record. It is drawn only when the
 * game is solved, as C does.
 *
 * `show_labels` is the upstream preference. The seam has no preference
 * hook yet, so it lives on [Ui], defaults to false, and the drawing and
 * the `l` key read it. Applying the stored preference is app-adapter
 * debt for the shell.
 */
object Guess : Game {

    /** `enum { COL_BACKGROUND, ... }`. `COL_EMPTY` must stay `COL_1 - 1`. */
    private const val COL_BACKGROUND = 0
    private const val COL_FRAME = 1
    private const val COL_CURSOR = 2
    private const val COL_FLASH = 3
    private const val COL_HOLD = 4
    private const val COL_EMPTY = 5
    private const val COL_1 = 6
    private const val COL_CORRECTPLACE = 16
    private const val COL_CORRECTCOLOUR = 17
    private const val NCOLOURS = 18

    /** `FEEDBACK_CORRECTPLACE` and `FEEDBACK_CORRECTCOLOUR`. */
    const val FEEDBACK_CORRECTPLACE = 1
    const val FEEDBACK_CORRECTCOLOUR = 2

    /** The drawstate cache flags, `PEG_CURSOR`, `PEG_HOLD`, `PEG_LABELLED`. */
    private const val PEG_CURSOR = 0x1000
    private const val PEG_HOLD = 0x2000
    private const val PEG_LABELLED = 0x4000
    private const val PEG_FLAGS = PEG_CURSOR or PEG_HOLD or PEG_LABELLED

    private const val PREFERRED_TILE_SIZE = 32

    /** `PEG_GAP`, `PEG_HINT`, `BORDER`: multipliers for one peg. */
    private const val PEG_GAP = 0.10
    private const val PEG_HINT = 0.35
    private const val BORDER = 0.5

    /** `struct game_params`: three sizes and two flags. */
    data class Params(
        val ncolours: Int,
        val npegs: Int,
        val nguesses: Int,
        val allowBlank: Boolean,
        val allowMultiple: Boolean,
    ) : Game.Params {

        val fullText: String get() = encodeParams(this, true)

        val shortText: String get() = encodeParams(this, false)

        override fun copy(): Game.Params =
            Params(ncolours, npegs, nguesses, allowBlank, allowMultiple)
    }

    /**
     * `struct pegrow`: `npegs` colors and the feedback for the row. A
     * color is 1-based and 0 is empty. Feedback is 0, [FEEDBACK_CORRECTPLACE],
     * or [FEEDBACK_CORRECTCOLOUR].
     */
    class PegRow(val pegs: IntArray, val feedback: IntArray) {

        val npegs: Int get() = pegs.size

        internal fun deepCopy(): PegRow = PegRow(pegs.copyOf(), feedback.copyOf())

        override fun equals(other: Any?): Boolean =
            other is PegRow &&
                pegs.contentEquals(other.pegs) &&
                feedback.contentEquals(other.feedback)

        override fun hashCode(): Int =
            31 * pegs.contentHashCode() + feedback.contentHashCode()
    }

    /**
     * `struct game_state`. [solution] is the hidden answer and is never
     * mutated after [newState], so a copy may share it. The guess rows
     * and the holds are mutable history and are copied.
     *
     * A win is `solved == 1`; a loss or a reveal is `solved == -1`.
     * [toString] deliberately omits the solution, so a log line cannot
     * spoil the puzzle.
     */
    class State(
        val params: Params,
        val guesses: Array<PegRow>,
        val holds: BooleanArray,
        val solution: IntArray,
        val nextGo: Int,
        val solved: Int,
    ) : Game.State {

        override fun copy(): Game.State = State(
            params = params,
            guesses = Array(guesses.size) { guesses[it].deepCopy() },
            holds = holds.copyOf(),
            solution = solution,
            nextGo = nextGo,
            solved = solved,
        )

        /**
         * A state that shares this state's arrays with a new status and
         * row. Only a fresh copy is passed here, so no history entry is
         * mutated.
         */
        internal fun withOutcome(solved: Int, nextGo: Int = this.nextGo): State =
            State(params, guesses, holds, solution, nextGo, solved)

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                guesses.contentEquals(other.guesses) &&
                holds.contentEquals(other.holds) &&
                solution.contentEquals(other.solution) &&
                nextGo == other.nextGo &&
                solved == other.solved

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + guesses.contentHashCode()
            result = 31 * result + holds.contentHashCode()
            result = 31 * result + solution.contentHashCode()
            result = 31 * result + nextGo
            result = 31 * result + solved
            return result
        }

        override fun toString(): String =
            "Guess.State(params=$params, nextGo=$nextGo, solved=$solved)"
    }

    /**
     * `struct game_ui`. The draft is [currPegs]; [holds] is the set of
     * pegs carried into the next guess. [pegCur] is 0-based and `npegs`
     * is the Submit slot. [displayCur] is true when the cursor is drawn.
     * [markable] is the derived "this draft can be submitted" flag.
     * [hint] is the cached candidate, freed only when the history moves
     * back.
     */
    class Ui(
        val params: Params,
        val currPegs: IntArray = IntArray(params.npegs),
        val holds: BooleanArray = BooleanArray(params.npegs),
        var colourCur: Int = 0,
        var pegCur: Int = 0,
        var displayCur: Boolean = false,
        var markable: Boolean = false,
        var dragCol: Int = 0,
        var dragX: Int = 0,
        var dragY: Int = 0,
        var dragOpeg: Int = -1,
        var showLabels: Boolean = false,
        var hint: IntArray? = null,
    ) : Game.Ui

    /** The last drawn value of one row, or -1 before the first draw. */
    class PegRowCache internal constructor(val npegs: Int) {
        val pegs: IntArray = IntArray(npegs) { -1 }
        val feedback: IntArray = IntArray(npegs) { -1 }
    }

    /**
     * `struct game_drawstate`: the per-row caches, the three flags of
     * the last frame, and the one blitter for the dragged peg.
     */
    class DrawState internal constructor(
        internal val geometry: Geometry,
        /** The id [DrawSink.blitterNew] returned. */
        val blitter: Int,
        val guesses: Array<PegRowCache>,
        val solution: PegRowCache,
        val colours: PegRowCache,
    ) : Game.DrawState {
        val tileSize: Int get() = geometry.pegsz
        var started: Boolean = false
        var solved: Int = 0
        var nextGo: Int = 0
        var dragCol: Int = 0
        var blitOx: Int = 0
        var blitOy: Int = 0
    }

    /**
     * Every coordinate and size from `game_set_size` and the macros.
     * One value is used by drawing, input, and the cursor, so the three
     * cannot disagree. The `(int)` truncation and the `ceil` are part of
     * parity.
     */
    internal class Geometry(private val params: Params, tilesize: Int) {
        val pegsz: Int = tilesize
        val hintsz: Int = (pegsz * PEG_HINT).toInt()
        val gapsz: Int = (pegsz * PEG_GAP).toInt()
        val border: Int = (pegsz * BORDER).toInt()
        val pegrad: Int = (pegsz - 1) / 2
        val hintrad: Int = (hintsz - 1) / 2
        val hintw: Int = (params.npegs + 1) / 2
        val w: Int
        val h: Int

        init {
            val hmul = BORDER * 2.0 +
                1.0 * 2.0 +
                1.0 * params.npegs +
                PEG_GAP * params.npegs +
                PEG_HINT * hintw +
                PEG_GAP * (hintw - 1)
            val vmulC = BORDER * 2.0 +
                1.0 * params.ncolours +
                PEG_GAP * (params.ncolours - 1)
            val vmulG = BORDER * 2.0 +
                1.0 * (params.nguesses + 1) +
                PEG_GAP * (params.nguesses + 1)
            val vmul = max(vmulC, vmulG)
            w = ceil(tilesize * hmul).toInt()
            h = ceil(tilesize * vmul).toInt()
        }

        private val colh: Int = ((pegsz + gapsz) * params.ncolours) - gapsz
        private val guessh: Int =
            ((pegsz + gapsz) * params.nguesses) + gapsz + pegsz

        val colx: Int = border
        val coly: Int = (h - colh) / 2
        val guessx: Int = border + pegsz * 2
        val solnx: Int = guessx
        val guessy: Int = (h - guessh) / 2
        val solny: Int = guessy + ((pegsz + gapsz) * params.nguesses) + gapsz

        val pegOff: Int get() = pegsz + gapsz
        val hintOff: Int get() = hintsz + gapsz
        val cgap: Int get() = max(gapsz / 2, 1)

        fun colY(colour: Int): Int = coly + colour * pegOff

        fun guessX(guess: Int, peg: Int): Int = guessx + peg * pegOff

        fun guessY(guess: Int, peg: Int): Int = guessy + guess * pegOff

        val guessW: Int get() = params.npegs * pegOff
        val guessH: Int get() = params.nguesses * pegOff
        val hintOx: Int get() = guessx + guessW + gapsz
        val hintOy: Int get() = guessy + (pegsz - hintOff - hintsz) / 2
        fun hintY(guess: Int): Int = hintOy + guess * pegOff
        val hintW: Int get() = hintw * hintOff - gapsz
        val solnOy: Int get() = guessy + guessH + gapsz + 2
        val solnW: Int get() = guessW
        val solnH: Int get() = pegOff
    }

    override val name: String = "Guess"
    override val backendName: String = "guess"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false
    override val canSolve: Boolean = true

    /** `can_format_as_text_ever`: false, so `Midend` never calls [textFormat]. */
    override val canFormatAsText: Boolean = false

    /** `guess_presets`. `guess.c` has no `SMALL_SCREEN` branch. */
    private val presetValues = listOf(
        "Standard" to Params(6, 4, 10, allowBlank = false, allowMultiple = true),
        "Super" to Params(8, 5, 12, allowBlank = false, allowMultiple = true),
    )

    override fun defaultParams(): Game.Params =
        Params(6, 4, 10, allowBlank = false, allowMultiple = true)

    /** The presets use the full form; `encodeParams` ignores `full`. */
    override fun presets(): List<Preset> = presetValues.map { (title, params) ->
        Preset(title, params.fullText)
    }

    /**
     * `decode_params`: walk the string one character at a time. `c`,
     * `p`, and `g` read `atoi` and skip ASCII digits; `b`/`B` and
     * `m`/`M` set the flags; every other character is ignored. `atoi`
     * saturates where C is undefined.
     */
    override fun decodeParams(text: String): Game.Params {
        var ncolours = 6
        var npegs = 4
        var nguesses = 10
        var allowBlank = false
        var allowMultiple = true
        var index = 0
        while (index < text.length) {
            when (text[index++]) {
                'c' -> {
                    ncolours = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'p' -> {
                    npegs = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'g' -> {
                    nguesses = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'b' -> allowBlank = true
                'B' -> allowBlank = false
                'm' -> allowMultiple = true
                'M' -> allowMultiple = false
                else -> Unit
            }
        }
        return Params(ncolours, npegs, nguesses, allowBlank, allowMultiple)
    }

    /**
     * `encode_params`: the short and the full form are identical,
     * because the C function ignores `full`. The two flag letters are
     * therefore part of the game ID.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "c${p.ncolours}p${p.npegs}g${p.nguesses}" +
            (if (p.allowBlank) "b" else "B") +
            (if (p.allowMultiple) "m" else "M")
    }

    /** `validate_params`, in the C order. `full` is ignored. */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.ncolours < 2 || p.npegs < 2) {
            return "Trivial solutions are uninteresting"
        }
        if (p.ncolours > 10) return "Too many colours"
        if (p.nguesses < 1) return "Must have at least one guess"
        if (!p.allowMultiple && p.ncolours < p.npegs) {
            return "Disallowing multiple colours requires at least " +
                "as many colours as pegs"
        }
        return null
    }

    /** `game_configure` (`guess.c:167-201`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Colours", p.ncolours.toString()),
            ConfigItem.Text("Pegs per guess", p.npegs.toString()),
            ConfigItem.Text("Guesses", p.nguesses.toString()),
            ConfigItem.Bool("Allow blanks", p.allowBlank),
            ConfigItem.Bool("Allow duplicates", p.allowMultiple),
        )
    }

    /** `custom_params` (`guess.c:203-215`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
            (items[3] as ConfigItem.Bool).value,
            (items[4] as ConfigItem.Bool).value,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`guess.c:452-467`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Label colours with numbers",
                false,
                "show-labels",
            ),
        )

    /** `set_prefs` (`guess.c:469-472`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).showLabels = prefs.bool("show-labels")
    }

    /**
     * `new_game_desc`: one `random_upto(ncolours)` per attempt, in peg
     * order. A duplicate is discarded and the same peg is drawn again,
     * with no extra RNG call and no stored value. There is no
     * `AUXINFO`.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val bitmap = ByteArray(p.npegs)
        val colcount = IntArray(p.ncolours)
        var i = 0
        while (i < p.npegs) {
            val c = random.upto(p.ncolours.toLong()).toInt()
            if (!p.allowMultiple && colcount[c] != 0) continue
            colcount[c]++
            bitmap[i] = (c + 1).toByte()
            i++
        }
        return Description(AuxInfo.encodeBytes(bitmap))
    }

    /**
     * `validate_desc`: the length, then the deobfuscated bytes. The hex
     * case and duplicate colors are not checked. A non-hex character is
     * a zero byte, not a parse error.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        if (desc.length != p.npegs * 2) return "Game description is wrong length"
        val bitmap = AuxInfo.decodeBytes(desc, p.npegs)
        for (b in bitmap) {
            val value = b.toInt() and 0xFF
            if (value < 1 || value > p.ncolours) {
                return "Game description is corrupted"
            }
        }
        return null
    }

    /** `new_game`: deobfuscate the solution and zero the history. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val bitmap = AuxInfo.decodeBytes(desc, p.npegs)
        val solution = IntArray(p.npegs) { bitmap[it].toInt() and 0xFF }
        return State(
            params = p,
            guesses = Array(p.nguesses) { PegRow(IntArray(p.npegs), IntArray(p.npegs)) },
            holds = BooleanArray(p.npegs),
            solution = solution,
            nextGo = 0,
            solved = 0,
        )
    }

    /**
     * `solve_game`: `S`. This is a reveal, not an auto-solve: the move
     * sets the loss status and draws the solution. It works even on an
     * already solved state, as C does.
     */
    override fun solve(state: Game.State, auxInfo: String?): String = "S"

    /**
     * `execute_move`. `S` always succeeds. A `G` move parses one color
     * per peg with `atoi` and the same character advance as C: a digit
     * run, an optional `_`, and an optional `,`. Junk, a sign, and
     * white space are not rejected.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        if (move == "S") {
            return (from.copy() as State).withOutcome(solved = -1)
        }
        if (move.isEmpty() || move[0] != 'G') return null
        if (from.solved != 0) return null

        val ret = from.copy() as State
        val row = ret.guesses[from.nextGo]
        val minColour = if (from.params.allowBlank) 0 else 1
        var index = 1
        for (i in 0 until from.params.npegs) {
            val value = atoi(move, index)
            if (value < minColour || value > from.params.ncolours) return null
            row.pegs[i] = value
            while (index < move.length && move[index] in '0'..'9') index++
            if (index < move.length && move[index] == '_') {
                ret.holds[i] = true
                index++
            } else {
                ret.holds[i] = false
            }
            if (index < move.length && move[index] == ',') index++
        }

        val ncPlace = markPegs(
            row.pegs,
            ret.solution,
            ret.params.ncolours,
            row.feedback,
        )
        if (ncPlace == ret.solution.size) {
            return ret.withOutcome(solved = 1)
        }
        val nextGo = from.nextGo + 1
        return ret.withOutcome(
            solved = if (nextGo >= ret.params.nguesses) -1 else 0,
            nextGo = nextGo,
        )
    }

    /** `game_status`: the `solved` field, with -1 for a loss or a reveal. */
    override fun status(state: Game.State): Int = (state as State).solved

    /** Guess has no status bar and no status text. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** Guess has no text format; [Midend] checks [canFormatAsText] first. */
    override fun textFormat(state: Game.State): String = ""

    /**
     * `mark_pegs`: the exact-position count, then the color count from
     * `sum(min(guess, solution)) - nc_place`. The feedback is packed
     * place hints first, then color hints.
     */
    internal fun markPegs(
        guess: IntArray,
        solution: IntArray,
        ncols: Int,
        feedback: IntArray,
    ): Int {
        var ncPlace = 0
        for (i in guess.indices) {
            if (guess[i] == solution[i]) ncPlace++
        }
        var ncColour = 0
        for (color in 1..ncols) {
            var nGuess = 0
            var nSolution = 0
            for (j in guess.indices) {
                if (guess[j] == color) nGuess++
                if (solution[j] == color) nSolution++
            }
            ncColour += min(nGuess, nSolution)
        }
        ncColour -= ncPlace

        feedback.fill(0)
        var j = 0
        for (i in 0 until ncPlace) feedback[j++] = FEEDBACK_CORRECTPLACE
        for (i in 0 until ncColour) feedback[j++] = FEEDBACK_CORRECTCOLOUR
        return ncPlace
    }

    /** `is_markable`. A color above `ncolours` is not counted. */
    internal fun isMarkable(params: Params, pegs: IntArray): Boolean {
        var nset = 0
        val colcount = IntArray(params.ncolours)
        for (i in 0 until params.npegs) {
            val c = pegs[i]
            if (c > 0) {
                if (c <= params.ncolours) colcount[c - 1]++
                nset++
            }
        }
        if (nset < (if (params.allowBlank) 1 else params.npegs)) return false
        if (!params.allowMultiple) {
            for (count in colcount) if (count > 1) return false
        }
        return true
    }

    /** `set_peg`: store one draft color and recompute the markable flag. */
    private fun setPeg(params: Params, ui: Ui, peg: Int, colour: Int) {
        ui.currPegs[peg] = colour
        ui.markable = isMarkable(params, ui.currPegs)
    }

    /** `encode_move`: `G` and one color per peg, with `_` on a held peg. */
    private fun encodeMove(from: State, ui: Ui): String {
        val out = StringBuilder("G")
        for (i in 0 until from.params.npegs) {
            if (i > 0) out.append(',')
            out.append(ui.currPegs[i])
            if (ui.holds[i]) out.append('_')
        }
        return out.toString()
    }

    /**
     * `compute_hint`: the lexicographically first row consistent with
     * every placed guess. The search limits and the cache are C's. A
     * failed search changes the UI only, never `markable`.
     */
    private fun computeHint(state: State, ui: Ui) {
        val params = state.params
        var mincolour = 1
        var maxcolour = 0
        for (i in 0 until state.nextGo) {
            for (j in 0 until params.npegs) {
                if (state.guesses[i].pegs[j] > maxcolour) {
                    maxcolour = state.guesses[i].pegs[j]
                }
            }
        }
        maxcolour = if (params.allowMultiple) {
            min(maxcolour + 1, params.ncolours)
        } else {
            min(maxcolour + params.npegs, params.ncolours)
        }

        // Raise mincolour while every placed guess is that single color
        // and has no exact-position hint; then restart the scan.
        outer@ while (true) {
            for (i in 0 until state.nextGo) {
                val row = state.guesses[i]
                if (row.feedback[0] != 0) continue
                var all = true
                for (j in 0 until params.npegs) {
                    if (row.pegs[j] != mincolour) {
                        all = false
                        break
                    }
                }
                if (all) {
                    mincolour++
                    continue@outer
                }
            }
            break
        }

        val hint = ui.hint ?: IntArray(params.npegs) { 1 }.also { ui.hint = it }
        val hintFeedback = IntArray(params.npegs)
        while (hint[0] <= params.ncolours) {
            var valid = isMarkable(params, hint)
            if (valid) {
                for (i in 0 until state.nextGo) {
                    markPegs(hint, state.guesses[i].pegs, maxcolour, hintFeedback)
                    for (j in 0 until params.npegs) {
                        if (hintFeedback[j] != state.guesses[i].feedback[j]) {
                            valid = false
                            break
                        }
                    }
                    if (!valid) break
                }
            }
            if (valid) {
                for (i in 0 until params.npegs) ui.currPegs[i] = hint[i]
                ui.markable = true
                ui.pegCur = params.npegs
                ui.displayCur = true
                return
            }

            // Increment the candidate as a mixed-radix counter.
            var i = params.npegs - 1
            while (true) {
                hint[i]++
                if (i == 0 || hint[i] <= maxcolour) break
                hint[i] = mincolour
                i--
            }
        }

        // No candidate fits. C mutates the cursor to signal failure.
        if (!ui.displayCur) {
            ui.displayCur = true
        } else if (params.npegs == 1) {
            ui.displayCur = false
        } else {
            ui.pegCur = (ui.pegCur + 1) % params.npegs
        }
    }

    /**
     * `game_changed_state`: free the hint when the history moves back,
     * echo the held pegs into the next draft, and report the winning
     * transition.
     *
     * C reads `oldstate->next_go` unconditionally at the top. The seam
     * allows a null old state, so the read is guarded; no seam call site
     * passes null today.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val previous = oldState as State?
        val next = newState as State

        if (previous != null && next.nextGo < previous.nextGo) {
            u.hint = null
        }

        for (i in 0 until next.solution.size) {
            u.holds[i] = if (next.solved != 0) false else next.holds[i]
            if (next.solved != 0 || next.nextGo == 0 || !u.holds[i]) {
                u.currPegs[i] = 0
            } else {
                u.currPegs[i] = next.guesses[next.nextGo - 1].pegs[i]
            }
        }
        u.markable = isMarkable(next.params, u.currPegs)
        if (!u.markable && u.pegCur == next.solution.size) {
            u.pegCur = 0
        }

        return next.solved > 0 &&
            previous != null &&
            previous.solved == 0 &&
            next.nextGo < next.params.nguesses
    }

    /** `game_request_keys`: Labels, and Clear which needs the arrows. */
    override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
        labels = listOf(
            KeyLabel('L'.code, "Labels", needsArrows = false),
            KeyLabel('\b'.code, "Clear", needsArrows = true),
        ),
        arrowMode = ArrowMode.ARROWS_LEFT_RIGHT,
    )

    /** `game_compute_size`, in the same `double` arithmetic as C. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val geometry = Geometry(params as Params, tileSize)
        return Size(geometry.w, geometry.h)
    }

    /**
     * `game_colours`: the fixed peg colors, the dimmed background, and
     * `COL_EMPTY` as two thirds of it. `Colour.mkHighlight` is not used.
     */
    override fun colours(): FloatArray =
        colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]

        setColour(ret, COL_1, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_1 + 1, 1.0f, 1.0f, 0.0f)
        setColour(ret, COL_1 + 2, 0.0f, 1.0f, 0.0f)
        setColour(ret, COL_1 + 3, 0.2f, 0.3f, 1.0f)
        setColour(ret, COL_1 + 4, 1.0f, 0.5f, 0.0f)
        setColour(ret, COL_1 + 5, 0.5f, 0.0f, 0.7f)
        setColour(ret, COL_1 + 6, 0.5f, 0.3f, 0.3f)
        setColour(ret, COL_1 + 7, 0.4f, 0.8f, 1.0f)
        setColour(ret, COL_1 + 8, 0.7f, 1.0f, 0.7f)
        setColour(ret, COL_1 + 9, 1.0f, 0.6f, 1.0f)

        setColour(ret, COL_FRAME, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_CURSOR, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_FLASH, 0.5f, 1.0f, 1.0f)
        setColour(ret, COL_HOLD, 1.0f, 0.5f, 0.5f)
        setColour(ret, COL_CORRECTPLACE, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_CORRECTCOLOUR, 1.0f, 1.0f, 1.0f)

        var max = ret[COL_BACKGROUND * 3]
        for (i in 1..2) {
            if (ret[COL_BACKGROUND * 3 + i] > max) max = ret[COL_BACKGROUND * 3 + i]
        }
        if (max * 1.2f > 1.0f) {
            val divisor = max * 1.2f
            for (i in 0..2) ret[COL_BACKGROUND * 3 + i] /= divisor
        }
        for (i in 0..2) {
            ret[COL_EMPTY * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 2.0f / 3.0f
        }
        return ret
    }

    private fun setColour(ret: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
    }

    /**
     * `new_ui`: an empty draft and holds, the cursors hidden, and
     * `showLabels` false. C reads `PUZZLES_SHOW_CURSOR`; the seam has no
     * environment, so the cursor starts hidden.
     */
    override fun newUi(state: Game.State): Game.Ui {
        val s = state as State
        return Ui(params = s.params)
    }

    /**
     * `encode_ui`: the draft and the holds, as `n` or `n_` separated by
     * commas. Nothing else is stored.
     */
    override fun encodeUi(ui: Game.Ui): String? {
        val u = ui as Ui
        val out = StringBuilder()
        for (i in 0 until u.params.npegs) {
            if (i > 0) out.append(',')
            out.append(u.currPegs[i])
            if (u.holds[i]) out.append('_')
        }
        return out.toString()
    }

    /**
     * `decode_ui`: a character-stream parser, not a split. `atoi`
     * re-reads a component that has no digit run, so `' 3 , 1 '` gives
     * `3,3` and `'1junk,2'` gives `1,0`. A missing component is 0. The
     * legacy form without `_` loads with no holds.
     */
    override fun decodeUi(state: Game.State, text: String): Game.Ui {
        val s = state as State
        val ui = Ui(params = s.params)
        var p = 0
        for (i in 0 until s.params.npegs) {
            val value = atoi(text, p)
            ui.currPegs[i] =
                if (value < 0 || value > s.params.ncolours) 0 else value
            while (p < text.length && text[p] in '0'..'9') p++
            if (p < text.length && text[p] == '_') {
                ui.holds[i] = true
                p++
            } else {
                ui.holds[i] = false
            }
            if (p < text.length && text[p] == ',') p++
        }
        ui.markable = isMarkable(s.params, ui.currPegs)
        return ui
    }

    /** `android_cursor_visibility`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).displayCur = visible
    }

    /**
     * `game_get_cursor_location`: a visible cursor on an open game. The
     * rectangle is one pixel wider than the peg, and the Submit slot is
     * not clamped.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        val s = state as State
        if (!u.displayCur || s.solved != 0) return null
        val g = Geometry(s.params, tileSize)
        val x = g.guessX(s.nextGo, u.pegCur) - g.cgap
        val y = g.guessY(s.nextGo, u.pegCur) - g.cgap
        val size = 2 * (g.pegrad + g.cgap) + 1
        return Game.CursorRect(x, y, size, size)
    }

    /**
     * `current_key_label`: `Submit` at the Submit slot, `Place` on a
     * peg, `Hold` for the second select on a peg, and `""` when solved.
     * The seam has no hook yet, so this is a public helper.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (s.solved != 0) return ""
        if (button == Buttons.CURSOR_SELECT) {
            return if (u.pegCur == s.params.npegs) "Submit" else "Place"
        }
        if (button == Buttons.CURSOR_SELECT2 && u.pegCur != s.params.npegs) {
            return "Hold"
        }
        return ""
    }

    /**
     * The palette, one unit per peg of every guess row, one feedback
     * unit per row, and the solution when it is shown. Every rectangle
     * comes from [Geometry], so a unit lands on the peg the drawing
     * puts there.
     *
     * The palette is a drag source, not a button: `interpret_move`
     * reads a colour on the press and writes it on the release, so one
     * point sets nothing and the swatch is read only. A peg of the row
     * being built offers `Hold` or `Unhold` (the right button, the word
     * `currentKeyLabel` uses), and the feedback unit of that row offers
     * `Submit` when the draft is complete, because a release there is
     * the game's submit.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val p = s.params
        val g = Geometry(p, tileSize)
        val units = buildList {
            for (i in 0 until p.ncolours) {
                add(
                    SpokenUnit(
                        row = 0,
                        column = 0,
                        left = g.colx,
                        top = g.colY(i),
                        width = g.pegsz,
                        height = g.pegsz,
                        text = colourName(i + 1) +
                            if (u.displayCur && u.colourCur == i) ", cursor" else "",
                    ),
                )
            }
            for (guess in 0 until p.nguesses) {
                val row = s.guesses[guess]
                val current = guess == s.nextGo && s.solved == 0
                for (peg in 0 until p.npegs) {
                    val held = current && u.holds[peg]
                    val text = when {
                        current && peg < u.currPegs.size && u.currPegs[peg] != 0 ->
                            colourName(u.currPegs[peg])

                        else -> colourName(row.pegs[peg])
                    }
                    val cursor = if (current && u.displayCur && u.pegCur == peg) {
                        ", cursor"
                    } else {
                        ""
                    }
                    add(
                        SpokenUnit(
                            row = 0,
                            column = 0,
                            left = g.guessX(guess, peg),
                            top = g.guessY(guess, 0),
                            width = g.pegsz,
                            height = g.pegsz,
                            text = text + (if (held) ", held" else "") + cursor,
                            actions = if (current) {
                                listOf(
                                    SpokenAction(
                                        if (held) "Unhold" else "Hold",
                                        Buttons.RIGHT_BUTTON,
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        ),
                    )
                }
            }
            for (guess in 0 until p.nguesses) {
                val row = s.guesses[guess]
                val current = guess == s.nextGo && s.solved == 0
                val answer = when {
                    current -> "current row"
                    guess < s.nextGo || s.solved != 0 -> feedbackSpeech(row)
                    else -> "empty"
                }
                add(
                    SpokenUnit(
                        row = 0,
                        column = 0,
                        left = g.hintOx,
                        top = g.hintY(guess),
                        width = g.hintW,
                        height = g.pegsz,
                        text = answer,
                        actions = if (current && u.markable) {
                            listOf(SpokenAction("Submit", Buttons.LEFT_BUTTON))
                        } else {
                            emptyList()
                        },
                    ),
                )
            }
            if (s.solved != 0) {
                val colours = (0 until p.npegs).joinToString(", ") {
                    colourName(s.solution[it])
                }
                add(
                    SpokenUnit(
                        row = 0,
                        column = 0,
                        left = g.solnx,
                        top = g.solny,
                        width = g.solnW,
                        height = g.solnH,
                        text = "solution, $colours",
                    ),
                )
            }
        }

        val board = SpokenBoard(0, 0, statusText(state, ui), units)
        val cursor = when {
            u.displayCur && u.pegCur < p.npegs ->
                board.units.getOrNull(p.ncolours + s.nextGo * p.npegs + u.pegCur)

            u.displayCur -> board.units.getOrNull(u.colourCur)
            else -> null
        }
        return board.cappedUnits(cursor)
    }

    /** The shared colour name of one peg value, `0` being no peg. */
    private fun colourName(colour: Int): String =
        if (colour in 1..BoardColour.entries.size) {
            BoardColour.entries[colour - 1].spokenName
        } else {
            "empty"
        }

    /** One row's answer: how many exact and how many wrong place. */
    private fun feedbackSpeech(row: PegRow): String {
        var exact = 0
        var colour = 0
        for (value in row.feedback) {
            when (value) {
                FEEDBACK_CORRECTPLACE -> exact++
                FEEDBACK_CORRECTCOLOUR -> colour++
            }
        }
        return "$exact exact, $colour wrong place"
    }

    /** `game_new_drawstate` and `game_set_size`: allocate the one blitter. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val p = (state as State).params
        val geometry = Geometry(p, tileSize)
        return DrawState(
            geometry = geometry,
            blitter = sink.blitterNew(geometry.pegsz + 2, geometry.pegsz + 2),
            guesses = Array(p.nguesses) { PegRowCache(p.npegs) },
            solution = PegRowCache(p.npegs),
            colours = PegRowCache(p.ncolours),
        )
    }

    /** `game_free_drawstate`. */
    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        sink.blitterFree((drawState as DrawState).blitter)
    }

    /**
     * `interpret_move`. Label toggling works even when solved; every
     * other key and click is unused then. The pointer paths start and
     * finish a drag, and the `G` move is made only by a release on the
     * hint area with a markable draft.
     *
     * C reads and writes `curr_pegs->pegs[npegs]` for a backspace at the
     * Submit slot, which is undefined behavior. The port treats that
     * slot as empty: it returns [MoveResult.NoEffect] when the cursor is
     * visible and only makes the cursor visible otherwise, so a crash is
     * not reproduced.
     *
     * At the Submit slot C also builds a move even when `markable` is
     * false (for example after a drag cleared one peg). The midend maps
     * the rejected move to `NoEffect`, which is C's result in a
     * release build; the assertion build aborts instead. The port keeps
     * C's move text and does not abort.
     */
    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val from = state as State
        val u = ui as Ui
        val p = from.params
        val g = Geometry(p, tileSize)

        if (button == 'l'.code || button == 'L'.code) {
            u.showLabels = !u.showLabels
            return MoveResult.UiUpdate
        }
        if (from.solved != 0) return MoveResult.Unused

        var overCol = 0
        var overGuess = -1
        var overPastGuessY = -1
        var overPastGuessX = -1
        var overHint = false
        val guessOx = g.guessX(from.nextGo, 0)
        val guessOy = g.guessY(from.nextGo, 0)

        if (x >= g.colx && x < g.colx + g.pegOff &&
            y >= g.coly && y < g.coly + p.ncolours * g.pegOff
        ) {
            overCol = (y - g.coly) / g.pegOff + 1
        } else if (x >= guessOx && y >= guessOy &&
            y < guessOy + g.guessH
        ) {
            if (x < guessOx + g.guessW) {
                overGuess = (x - guessOx) / g.pegOff
            } else {
                overHint = true
            }
        } else if (x >= guessOx && x < guessOx + g.guessW &&
            y >= g.guessy && y < guessOy
        ) {
            overPastGuessY = (y - g.guessy) / g.pegOff
            overPastGuessX = (x - guessOx) / g.pegOff
        }

        if (button == Buttons.LEFT_BUTTON) {
            if (overCol > 0) {
                u.dragCol = overCol
                u.dragOpeg = -1
            } else if (overGuess > -1) {
                val colour = u.currPegs[overGuess]
                if (colour != 0) {
                    u.dragCol = colour
                    u.dragOpeg = overGuess
                }
            } else if (overPastGuessY > -1) {
                val colour = from.guesses[overPastGuessY].pegs[overPastGuessX]
                if (colour != 0) {
                    u.dragCol = colour
                    u.dragOpeg = -1
                }
            }
            if (u.dragCol != 0) {
                u.dragX = x
                u.dragY = y
                return MoveResult.UiUpdate
            }
        } else if (button == Buttons.LEFT_DRAG && u.dragCol != 0) {
            u.dragX = x
            u.dragY = y
            return MoveResult.UiUpdate
        } else if (button == Buttons.LEFT_RELEASE && u.dragCol != 0) {
            if (overGuess > -1) {
                setPeg(p, u, overGuess, u.dragCol)
            } else if (u.dragOpeg > -1) {
                setPeg(p, u, u.dragOpeg, 0)
            }
            u.dragCol = 0
            u.dragOpeg = -1
            u.displayCur = false
            return MoveResult.UiUpdate
        } else if (button == Buttons.RIGHT_BUTTON) {
            if (overGuess > -1) {
                u.holds[overGuess] = !u.holds[overGuess]
                return MoveResult.UiUpdate
            }
        } else if (button == Buttons.LEFT_RELEASE && overHint && u.markable) {
            return MoveResult.Move(encodeMove(from, u))
        }

        if (Buttons.isCursorMove(button)) {
            val maxcur = p.npegs + if (u.markable) 1 else 0
            return moveCursor(button, u, maxcur, p.ncolours)
        } else if (button == 'h'.code || button == 'H'.code || button == '?'.code) {
            computeHint(from, u)
            return MoveResult.UiUpdate
        } else if (button == Buttons.CURSOR_SELECT) {
            u.displayCur = true
            if (u.pegCur == p.npegs) {
                return MoveResult.Move(encodeMove(from, u))
            }
            setPeg(p, u, u.pegCur, u.colourCur + 1)
            return MoveResult.UiUpdate
        } else if ((button >= '1'.code && button <= '0'.code + p.ncolours ||
                button == '0'.code && p.ncolours == 10) &&
            u.pegCur < p.npegs
        ) {
            u.displayCur = true
            val colour = if (button == '0'.code) 10 else button - '0'.code
            setPeg(p, u, u.pegCur, colour)
            if (u.pegCur + 1 < p.npegs + (if (u.markable) 1 else 0)) {
                u.pegCur++
            }
            return MoveResult.UiUpdate
        } else if (button == 'D'.code || button == 'd'.code || button == '\b'.code) {
            if (u.pegCur >= p.npegs) {
                // The C read and write are out of bounds. Treat the
                // Submit slot as empty and never write it.
                if (!u.displayCur) {
                    u.displayCur = true
                    return MoveResult.UiUpdate
                }
                return MoveResult.NoEffect
            }
            if (!u.displayCur || u.currPegs[u.pegCur] != 0) {
                u.displayCur = true
                setPeg(p, u, u.pegCur, 0)
                return MoveResult.UiUpdate
            }
            return MoveResult.NoEffect
        } else if (button == Buttons.CURSOR_SELECT2) {
            if (u.pegCur == p.npegs) return MoveResult.Unused
            u.displayCur = true
            u.holds[u.pegCur] = !u.holds[u.pegCur]
            return MoveResult.UiUpdate
        }
        return MoveResult.Unused
    }

    /** `move_cursor` from `misc.c`, without wrap, for the keyboard. */
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
        val oldX = ui.pegCur
        val oldY = ui.colourCur
        ui.pegCur = min(max(ui.pegCur + dx, 0), maxw - 1)
        ui.colourCur = min(max(ui.colourCur + dy, 0), maxh - 1)
        if (!ui.displayCur) {
            ui.displayCur = true
            return MoveResult.UiUpdate
        }
        if (ui.pegCur != oldX || ui.colourCur != oldY) {
            return MoveResult.UiUpdate
        }
        return MoveResult.NoEffect
    }

    /**
     * `game_redraw`. The drawstate survives a move, so a warm frame
     * draws only changed pegs and hints. The current hint row and the
     * current-row marker are redrawn on every frame, as C does.
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
        val g = ds.geometry
        val newMove = s.nextGo != ds.nextGo || !ds.started

        if (!ds.started) {
            sink.rect(g.solnx, g.solnOy - g.gapsz - 1, g.solnW, 2, COL_FRAME)
            sink.update(0, 0, g.w, g.h)
        }

        if (ds.dragCol != 0) {
            sink.blitterLoad(ds.blitter, ds.blitOx, ds.blitOy)
            sink.update(ds.blitOx, ds.blitOy, g.pegsz, g.pegsz)
        }

        for (i in 0 until s.params.ncolours) {
            var value = i + 1
            if (u.displayCur && u.colourCur == i) value = value or PEG_CURSOR
            if (u.showLabels) value = value or PEG_HOLD
            if (ds.colours.pegs[i] != value) {
                drawPeg(sink, g, g.colx, g.colY(i), false, u.showLabels, i + 1)
                if (value and PEG_CURSOR != 0) {
                    drawCursor(sink, g, g.colx, g.colY(i))
                }
                ds.colours.pegs[i] = value
            }
        }

        for (i in s.params.nguesses - 1 downTo 0) {
            if (i < s.nextGo || s.solved != 0) {
                guessRedraw(
                    sink, g, ds, i, s.guesses[i].pegs, null, -1, false,
                    u.showLabels,
                )
                hintRedraw(
                    sink, g, ds, i, s.guesses[i].feedback,
                    force = i == s.nextGo - 1, cursor = false, markable = false,
                )
            } else if (i > s.nextGo) {
                guessRedraw(sink, g, ds, i, null, null, -1, false, u.showLabels)
                hintRedraw(sink, g, ds, i, null, false, false, false)
            }
        }

        if (s.solved == 0) {
            guessRedraw(
                sink, g, ds, s.nextGo, u.currPegs, u.holds,
                if (u.displayCur) u.pegCur else -1, false, u.showLabels,
            )
            hintRedraw(
                sink, g, ds, s.nextGo, null, force = true,
                cursor = u.displayCur && u.pegCur == s.params.npegs,
                markable = u.markable,
            )
        }

        if (newMove) currmoveRedraw(sink, g, ds.nextGo, COL_BACKGROUND)
        if (s.solved == 0) currmoveRedraw(sink, g, s.nextGo, COL_HOLD)

        if ((s.solved == 0) != (ds.solved == 0) || !ds.started) {
            sink.rect(
                g.solnx, g.solnOy, g.solnW, g.solnH,
                if (s.solved != 0) COL_BACKGROUND else COL_EMPTY,
            )
            sink.update(g.solnx, g.solnOy, g.solnW, g.solnH)
        }
        if (s.solved != 0) {
            guessRedraw(
                sink, g, ds, -1, s.solution, null, -1, ds.solved == 0,
                u.showLabels,
            )
        }
        ds.solved = s.solved
        ds.nextGo = s.nextGo

        if (u.dragCol != 0) {
            val ox = u.dragX - g.pegsz / 2
            val oy = u.dragY - g.pegsz / 2
            ds.blitOx = ox - 1
            ds.blitOy = oy - 1
            sink.blitterSave(ds.blitter, ds.blitOx, ds.blitOy)
            drawPeg(sink, g, ox, oy, true, u.showLabels, u.dragCol)
        }
        ds.dragCol = u.dragCol
        ds.started = true
    }

    /** One peg: background, circle or square, optional label, update. */
    private fun drawPeg(
        sink: DrawSink,
        g: Geometry,
        cx: Int,
        cy: Int,
        moving: Boolean,
        labelled: Boolean,
        col: Int,
    ) {
        if (!moving) {
            sink.rect(
                cx - g.cgap, cy - g.cgap,
                g.pegsz + g.cgap * 2, g.pegsz + g.cgap * 2,
                COL_BACKGROUND,
            )
        }
        if (g.pegrad > 0) {
            sink.circle(
                cx + g.pegrad, cy + g.pegrad, g.pegrad,
                COL_EMPTY + col,
                if (col != 0) COL_FRAME else COL_EMPTY,
            )
        } else {
            sink.rect(cx, cy, g.pegsz, g.pegsz, COL_EMPTY + col)
        }
        if (labelled && col != 0) {
            val text = ('0'.code + col % 10).toChar().toString()
            sink.text(
                cx + g.pegrad, cy + g.pegrad, Text.FONT_VARIABLE, g.pegrad,
                Text.ALIGN_HCENTRE or Text.ALIGN_VCENTRE, COL_FRAME, text,
            )
        }
        sink.update(
            cx - g.cgap, cy - g.cgap,
            g.pegsz + g.cgap * 2, g.pegsz + g.cgap * 2,
        )
    }

    /** The cursor ring around one peg. */
    private fun drawCursor(sink: DrawSink, g: Geometry, x: Int, y: Int) {
        sink.circle(
            x + g.pegrad, y + g.pegrad, g.pegrad + g.cgap,
            -1, COL_CURSOR,
        )
        sink.update(
            x - g.cgap, y - g.cgap,
            g.pegsz + g.cgap * 2, g.pegsz + g.cgap * 2,
        )
    }

    /**
     * `guess_redraw`. `guess == -1` is the solution row. The cache holds
     * the color together with the cursor, hold, and label flags, so a
     * flag change redraws the peg. The two-pixel hold strip under each
     * changed peg is updated even when the peg has no hold.
     */
    private fun guessRedraw(
        sink: DrawSink,
        g: Geometry,
        ds: DrawState,
        guess: Int,
        src: IntArray?,
        holds: BooleanArray?,
        curCol: Int,
        force: Boolean,
        labelled: Boolean,
    ) {
        val dest: PegRowCache
        val rowx: Int
        val rowy: Int
        if (guess == -1) {
            dest = ds.solution
            rowx = g.solnx
            rowy = g.solnOy
        } else {
            dest = ds.guesses[guess]
            rowx = g.guessX(guess, 0)
            rowy = g.guessY(guess, 0)
        }

        for (i in 0 until dest.npegs) {
            var scol = src?.get(i) ?: 0
            if (i == curCol) scol = scol or PEG_CURSOR
            if (holds != null && holds[i]) scol = scol or PEG_HOLD
            if (labelled) scol = scol or PEG_LABELLED
            if (dest.pegs[i] != scol || force) {
                drawPeg(
                    sink, g, rowx + g.pegOff * i, rowy, false, labelled,
                    scol and PEG_FLAGS.inv(),
                )
                if (scol and PEG_CURSOR != 0) {
                    drawCursor(sink, g, rowx + g.pegOff * i, rowy)
                }
                if (scol and PEG_HOLD != 0) {
                    sink.rect(
                        rowx + g.pegOff * i,
                        rowy + g.pegsz + g.gapsz / 2 - 2,
                        g.pegsz, 2, COL_HOLD,
                    )
                }
                sink.update(
                    rowx + g.pegOff * i,
                    rowy + g.pegsz + g.gapsz / 2 - 2,
                    g.pegsz, 2,
                )
            }
            dest.pegs[i] = scol
        }
    }

    /**
     * `hint_redraw`. The whole hint box is redrawn or not at all,
     * because a cursor can ring all of it. The empty color is
     * `COL_FLASH` when the draft is markable. The cursor is four lines
     * around the box.
     */
    private fun hintRedraw(
        sink: DrawSink,
        g: Geometry,
        ds: DrawState,
        guess: Int,
        src: IntArray?,
        force: Boolean,
        cursor: Boolean,
        markable: Boolean,
    ) {
        val dest = ds.guesses[guess]
        val emptycol = if (markable) COL_FLASH else COL_EMPTY
        val hintlen = (dest.npegs + 1) / 2
        var needRedraw = false

        for (i in 0 until dest.npegs) {
            var scol = src?.get(i) ?: 0
            if (i == 0 && cursor) scol = scol or PEG_CURSOR
            if (i == 0 && markable) scol = scol or PEG_HOLD
            if (scol != dest.feedback[i] || force) needRedraw = true
            dest.feedback[i] = scol
        }

        if (!needRedraw) return
        val hinth = g.hintsz + g.gapsz + g.hintsz
        val hx = g.hintOx - g.gapsz
        val hy = g.hintY(guess) - g.gapsz
        val hw = g.hintW + g.gapsz * 2
        val hh = hinth + g.gapsz * 2

        sink.rect(hx, hy, hw, hh, COL_BACKGROUND)
        for (i in 0 until dest.npegs) {
            val scol = src?.get(i) ?: 0
            val col = when (scol) {
                FEEDBACK_CORRECTPLACE -> COL_CORRECTPLACE
                FEEDBACK_CORRECTCOLOUR -> COL_CORRECTCOLOUR
                else -> emptycol
            }
            var rowx = g.hintOx
            var rowy = g.hintY(guess)
            if (i < hintlen) {
                rowx += g.hintOff * i
            } else {
                rowx += g.hintOff * (i - hintlen)
                rowy += g.hintOff
            }
            if (g.hintrad > 0) {
                sink.circle(
                    rowx + g.hintrad, rowy + g.hintrad, g.hintrad, col,
                    if (col == emptycol) emptycol else COL_FRAME,
                )
            } else {
                sink.rect(rowx, rowy, g.hintsz, g.hintsz, col)
            }
        }
        if (cursor) {
            val x1 = hx + g.cgap
            val y1 = hy + g.cgap
            val x2 = hx + hw - g.cgap
            val y2 = hy + hh - g.cgap
            sink.line(x1, y1, x2, y1, COL_CURSOR)
            sink.line(x2, y1, x2, y2, COL_CURSOR)
            sink.line(x2, y2, x1, y2, COL_CURSOR)
            sink.line(x1, y2, x1, y1, COL_CURSOR)
        }
        sink.update(hx, hy, hw, hh)
    }

    /** The two-pixel marker to the left of the current row. */
    private fun currmoveRedraw(
        sink: DrawSink,
        g: Geometry,
        guess: Int,
        colour: Int,
    ) {
        val ox = g.guessX(guess, 0)
        val oy = g.guessY(guess, 0)
        val off = g.pegsz / 4
        sink.rect(ox - off - 1, oy, 2, g.pegsz, colour)
        sink.update(ox - off - 1, oy, 2, g.pegsz)
    }

    /** C `atoi`: white space, an optional sign, digits; 0 without a digit. */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && isAsciiSpace(text[i])) i++
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

    /** `isspace` in the C locale, which `atoi` uses. */
    private fun isAsciiSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r'
}
