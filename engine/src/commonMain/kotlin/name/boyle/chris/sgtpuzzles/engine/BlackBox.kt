package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.max
import kotlin.math.min

/**
 * A port of upstream `blackbox.c`: the laser-scanning deduction game.
 *
 * The player hides balls on an arena and fires lasers from the range
 * around it. A laser that hits a ball shows `H`, one that is reflected
 * at the entry shows `R`, and one that leaves at another range tile
 * shows the shared laser number. A reveal checks the guesses against
 * every possible laser, not only the fired ones.
 *
 * Two generators are part of the contract:
 *
 * - the game seed generates the ball layout in the description;
 * - the reveal check seeds a temporary generator from the raw little
 *   endian bytes of the guess grid, so repeating the same marking shows
 *   the same distinguishing laser. The byte order is a convention of
 *   every Android ABI and of the oracle; it is stated here on purpose.
 *
 * The ball layout lives only in the obfuscated description. It is not
 * in the state text, the status text, or the `UI` record, and a move
 * never names a ball.
 */
object BlackBox : Game {

    /** `enum { COL_BACKGROUND, ... }`. The order is the palette contract. */
    private const val COL_BACKGROUND = 0
    private const val COL_COVER = 1
    private const val COL_LOCK = 2
    private const val COL_TEXT = 3
    private const val COL_FLASHTEXT = 4
    private const val COL_HIGHLIGHT = 5
    private const val COL_LOWLIGHT = 6
    private const val COL_GRID = 7
    private const val COL_BALL = 8
    private const val COL_BALL_REVEALED = 9
    private const val COL_WRONG = 10
    private const val COL_BUTTON = 11
    private const val COL_CURSOR = 12
    private const val NCOLOURS = 13

    /** The arena cell flags from `blackbox.c`. */
    const val BALL_CORRECT = 0x00001
    const val BALL_GUESS = 0x00002
    const val BALL_LOCK = 0x00004

    /** The range cell and `exits` flags from `blackbox.c`. */
    const val LASER_OMITTED = 0x00800
    const val LASER_REFLECT = 0x01000
    const val LASER_HIT = 0x02000
    const val LASER_WRONG = 0x04000

    /** A draw-only flag. It is never stored in [State]. */
    private const val LASER_FLASHED = 0x08000

    /** `LASER_FLAGMASK`: strips every flag from a laser value. */
    private const val LASER_FLAGMASK = 0x1f800

    /** `LASER_EMPTY`: no shot was fired from this range tile. */
    const val LASER_EMPTY = -1

    /** A draw-only flag. It is never stored in [State]. */
    private const val FLAG_CURSOR = 0x10000

    private const val PREFERRED_TILE_SIZE = 32

    /** Directions are clockwise, as the offsets table is. */
    private const val DIR_UP = 0
    private const val DIR_RIGHT = 1
    private const val DIR_DOWN = 2
    private const val DIR_LEFT = 3

    private const val LOOK_LEFT = 0
    private const val LOOK_FORWARD = 1
    private const val LOOK_RIGHT = 2

    private val OFFSET_X = intArrayOf(0, 1, 0, -1)
    private val OFFSET_Y = intArrayOf(-1, 0, 1, 0)

    /** `struct game_params`. */
    data class Params(
        val w: Int,
        val h: Int,
        val minBalls: Int,
        val maxBalls: Int,
    ) : Game.Params {

        val text: String get() = encodeParams(this, true)

        override fun copy(): Game.Params = Params(w, h, minBalls, maxBalls)
    }

    /**
     * `struct game_state`. [grid] is the arena with a one-cell firing
     * ring, and [exits] holds one value per range tile. [done] is dead in
     * C and is kept so that a structural comparison matches the struct.
     *
     * [toString] omits both arrays, so a log line cannot spoil the
     * hidden ball layout.
     */
    class State(
        val params: Params,
        val w: Int,
        val h: Int,
        val minBalls: Int,
        val maxBalls: Int,
        val nBalls: Int,
        val nLasers: Int,
        val grid: IntArray,
        val exits: IntArray,
        val done: Boolean,
        var laserNo: Int,
        var nguesses: Int,
        var nright: Int,
        var nwrong: Int,
        var nmissed: Int,
        var reveal: Boolean,
        var justWrong: Boolean,
    ) : Game.State {

        /** `GRID(state, x, y)`: one-based grid coordinates. */
        fun gridAt(x: Int, y: Int): Int = grid[y * (w + 2) + x]

        /** An arena copy; both arrays and every scalar are copied. */
        override fun copy(): Game.State = State(
            params = params,
            w = w,
            h = h,
            minBalls = minBalls,
            maxBalls = maxBalls,
            nBalls = nBalls,
            nLasers = nLasers,
            grid = grid.copyOf(),
            exits = exits.copyOf(),
            done = done,
            laserNo = laserNo,
            nguesses = nguesses,
            nright = nright,
            nwrong = nwrong,
            nmissed = nmissed,
            reveal = reveal,
            justWrong = justWrong,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                w == other.w &&
                h == other.h &&
                minBalls == other.minBalls &&
                maxBalls == other.maxBalls &&
                nBalls == other.nBalls &&
                nLasers == other.nLasers &&
                grid.contentEquals(other.grid) &&
                exits.contentEquals(other.exits) &&
                done == other.done &&
                laserNo == other.laserNo &&
                nguesses == other.nguesses &&
                nright == other.nright &&
                nwrong == other.nwrong &&
                nmissed == other.nmissed &&
                reveal == other.reveal &&
                justWrong == other.justWrong

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + w
            result = 31 * result + h
            result = 31 * result + minBalls
            result = 31 * result + maxBalls
            result = 31 * result + nBalls
            result = 31 * result + nLasers
            result = 31 * result + grid.contentHashCode()
            result = 31 * result + exits.contentHashCode()
            result = 31 * result + done.hashCode()
            result = 31 * result + laserNo
            result = 31 * result + nguesses
            result = 31 * result + nright
            result = 31 * result + nwrong
            result = 31 * result + nmissed
            result = 31 * result + reveal.hashCode()
            result = 31 * result + justWrong.hashCode()
            return result
        }

        override fun toString(): String =
            "BlackBox.State(w=$w, h=$h, nguesses=$nguesses, nright=$nright, " +
                "nwrong=$nwrong, nmissed=$nmissed, reveal=$reveal, " +
                "justWrong=$justWrong)"
    }

    /**
     * `struct game_ui`. `flashLaser` is 0 for never, 1 for always, and 2
     * only while an animation runs; the seam has no animation clock, so
     * only 1 can show a flash in a redraw.
     */
    class Ui(
        var flashLaserno: Int = LASER_EMPTY,
        var errors: Int = 0,
        var newmove: Boolean = false,
        var curX: Int = 1,
        var curY: Int = 1,
        var curVisible: Boolean = false,
        var flashLaser: Int = 0,
    ) : Game.Ui

    /**
     * `struct game_drawstate`: the cached composite value of every grid
     * cell, the frame flag, and the last drawn reveal and flash state.
     * There is no blitter.
     */
    class DrawState internal constructor(
        val tileSize: Int,
        val crad: Int,
        val rrad: Int,
        val w: Int,
        val h: Int,
        val grid: IntArray,
    ) : Game.DrawState {
        var started: Boolean = false
        var reveal: Boolean = false
        var isflash: Boolean = false
        var flashLaserno: Int = LASER_EMPTY
    }

    /** The geometry `game_set_size` derives from one tile size. */
    private class Geometry(val tileSize: Int, val w: Int, val h: Int) {
        val crad: Int = (tileSize - 1) / 2
        val rrad: Int = (3 * tileSize) / 8
        val cells: Int = (w + 2) * (h + 2)

        fun toDraw(x: Int): Int = tileSize * x + tileSize / 2

        fun fromDraw(value: Int): Int = (value + tileSize / 2) / tileSize - 1
    }

    /** One frame of a laser path: a grid cell and a direction. */
    private class LaserCell(val x: Int, val y: Int, val direction: Int)

    override val name: String = "Black Box"
    override val backendName: String = "blackbox"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = true

    /** `can_format_as_text_ever`: false, so `Midend` never calls [textFormat]. */
    override val canFormatAsText: Boolean = false

    /** `blackbox_presets`; `blackbox.c` has no `SMALL_SCREEN` branch. */
    private val presetValues = listOf(
        "5x5, 3 balls" to Params(5, 5, 3, 3),
        "8x8, 5 balls" to Params(8, 8, 5, 5),
        "8x8, 3-6 balls" to Params(8, 8, 3, 6),
        "10x10, 5 balls" to Params(10, 10, 5, 5),
        "10x10, 4-10 balls" to Params(10, 10, 4, 10),
    )

    override fun defaultParams(): Game.Params = Params(8, 8, 5, 5)

    /** The title format is `"%dx%d, %d balls"` for a single count. */
    override fun presets(): List<Preset> = presetValues.map { (title, params) ->
        Preset(title, params.text)
    }

    /**
     * `custom_params`: the three strings of the configuration dialog.
     * Width and height are `atoi`. The ball field accepts `a-b`; when
     * `sscanf("%d-%d")` makes fewer than two conversions, the single
     * `atoi` value sets both ends. The app adapter uses this; it is not
     * part of `decode_params`.
     */
    fun customParams(width: String, height: String, balls: String): Params {
        val w = atoi(width, 0)
        val h = atoi(height, 0)
        val range = scanTwo(balls, 0, '-')
        return if (range.conversions < 2) {
            val value = atoi(balls, 0)
            Params(w, h, value, value)
        } else {
            Params(w, h, range.first, range.second)
        }
    }

    /**
     * `decode_params`: `w`, `h`, `m`, and `M` read `atoi` and skip ASCII
     * digits; every other character is ignored. `m` is the minimum and
     * `M` the maximum, and the letters are case-sensitive. `atoi`
     * saturates where C is undefined.
     */
    override fun decodeParams(text: String): Game.Params {
        val defs = Params(8, 8, 5, 5)
        var w = defs.w
        var h = defs.h
        var minBalls = defs.minBalls
        var maxBalls = defs.maxBalls
        var index = 0
        while (index < text.length) {
            when (text[index++]) {
                'w' -> {
                    w = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'h' -> {
                    h = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'm' -> {
                    minBalls = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                'M' -> {
                    maxBalls = atoi(text, index)
                    while (index < text.length && text[index] in '0'..'9') index++
                }

                else -> Unit
            }
        }
        return Params(w, h, minBalls, maxBalls)
    }

    /**
     * `encode_params`: always all four fields. The C function ignores
     * `full`, so the short and the full form are byte-identical.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        return "w${p.w}h${p.h}m${p.minBalls}M${p.maxBalls}"
    }

    /**
     * `validate_params`, in the C order. `full` is ignored.
     *
     * C never checks `maxBalls` against the grid, so `w3h3m1M100` is a
     * valid parameter set that cannot always be generated. [newDesc]
     * rejects only a drawn count above the arena cells, which is D-003
     * in `docs/DEVIATIONS.md`; this validator keeps the C messages.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.w < 2 || p.h < 2) {
            return "Width and height must both be at least two"
        }
        if (p.w > 255 || p.h > 255) {
            return "Widths and heights greater than 255 are not supported"
        }
        if (p.minBalls < 0) return "Negative number of balls"
        if (p.minBalls < 1) return "Number of balls must be at least one"
        if (p.minBalls > p.maxBalls) {
            return "Minimum number of balls may not be greater than maximum"
        }
        if (p.minBalls >= p.w * p.h) return "Too many balls to fit in grid"
        return null
    }

    /** `game_configure` (`blackbox.c:145-174`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        val balls = if (p.minBalls == p.maxBalls) {
            p.minBalls.toString()
        } else {
            "${p.minBalls}-${p.maxBalls}"
        }
        return listOf(
            ConfigItem.Text("Width", p.w.toString()),
            ConfigItem.Text("Height", p.h.toString()),
            ConfigItem.Text("No. of balls", balls),
        )
    }

    /**
     * `custom_params` (`blackbox.c:176-189`): `sscanf("%d-%d")`, and a
     * single number when the scan reads fewer than two fields.
     */
    override fun customParams(items: List<ConfigItem>): Game.Params {
        val balls = (items[2] as ConfigItem.Text).value
        val range = scanRange(balls)
        val min: Int
        val max: Int
        if (range != null) {
            min = range.first
            max = range.second
        } else {
            min = atoi(balls, 0)
            max = min
        }
        return Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            min,
            max,
        )
    }

    /**
     * C `sscanf(text, "%d-%d")`, as a pair or null when it reads fewer
     * than two numbers. The literal `-` must follow the first number
     * with no space, while each `%d` skips leading white space.
     */
    private fun scanRange(text: String): Pair<Int, Int>? {
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++
        val firstStart = i
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        val firstDigits = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == firstDigits) return null
        val first = atoi(text, firstStart)
        if (i >= text.length || text[i] != '-') return null
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        val secondStart = i
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        val secondDigits = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == secondDigits) return null
        return first to atoi(text, secondStart)
    }

    /**
     * `new_game_desc`: the ball count draw, then `x` before `y` for every
     * ball, with a whole-pair retry on a collision. The bytes are
     * obfuscated and hex encoded; there is no `AUXINFO`.
     *
     * A drawn count above the number of arena cells cannot be placed: C
     * fills every cell and then retries forever for the next ball. The
     * guard throws after the count draw and before any ball draw. A
     * count that fits generates exactly as C does, including the `x`
     * and `y` draws and the whole-pair retry. The guard changes no
     * valid parameters, seed, `DESC`, or save text.
     *
     * `minBalls` above the cell count normally fails `validateParams`
     * first. A direct call with such a minimum and `maxBalls >
     * minBalls` still performs the one count draw before the guard
     * throws; with `minBalls == maxBalls` there is no count draw.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val cells = p.w * p.h

        var nballs = p.minBalls
        if (p.maxBalls > p.minBalls) {
            nballs += random.upto((p.maxBalls - p.minBalls + 1).toLong()).toInt()
        }
        if (nballs > cells) {
            throw IllegalArgumentException(
                "Black Box cannot place $nballs balls on a ${p.w}x${p.h} grid",
            )
        }

        val used = BooleanArray(cells)
        val bmp = ByteArray(nballs * 2 + 2)
        bmp[0] = p.w.toByte()
        bmp[1] = p.h.toByte()
        for (i in 0 until nballs) {
            var x: Int
            var y: Int
            do {
                x = random.upto(p.w.toLong()).toInt()
                y = random.upto(p.h.toLong()).toInt()
            } while (used[y * p.w + x])
            used[y * p.w + x] = true
            bmp[(i + 1) * 2] = x.toByte()
            bmp[(i + 1) * 2 + 1] = y.toByte()
        }
        return Description(AuxInfo.encodeBytes(bmp))
    }

    /**
     * `validate_desc`. The length and the ball count are checked before
     * any decode, so a too-short string cannot reach a negative array
     * size. Duplicate balls, uppercase hex, and trailing junk inside the
     * hex range are not rejected; a non-hex character is a zero nibble.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val dlen = desc.length
        val nballs = ((dlen / 2) - 2) / 2
        if (dlen < 4 || dlen % 4 != 0 ||
            nballs < p.minBalls || nballs > p.maxBalls
        ) {
            return "Game description is wrong length"
        }

        val bmp = AuxInfo.decodeBytes(desc, nballs * 2 + 2)
        if ((bmp[0].toInt() and 0xFF) != p.w || (bmp[1].toInt() and 0xFF) != p.h) {
            return "Game description is corrupted"
        }
        for (i in 0 until nballs) {
            val x = bmp[(i + 1) * 2].toInt() and 0xFF
            val y = bmp[(i + 1) * 2 + 1].toInt() and 0xFF
            if (x < 0 || y < 0 || x >= p.w || y >= p.h) {
                return "Game description is corrupted"
            }
        }
        return null
    }

    /** `new_game`: the description, not the parameters, sets the grid size. */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val nballs = ((desc.length / 2) - 2) / 2
        val bmp = AuxInfo.decodeBytes(desc, nballs * 2 + 2)
        val w = bmp[0].toInt() and 0xFF
        val h = bmp[1].toInt() and 0xFF
        val nlasers = 2 * (w + h)
        val grid = IntArray((w + 2) * (h + 2))
        val exits = IntArray(nlasers) { LASER_EMPTY }

        for (i in 0 until nballs) {
            val x = bmp[(i + 1) * 2].toInt() and 0xFF
            val y = bmp[(i + 1) * 2 + 1].toInt() and 0xFF
            grid[(y + 1) * (w + 2) + (x + 1)] = BALL_CORRECT
        }

        return State(
            params = p,
            w = w,
            h = h,
            minBalls = p.minBalls,
            maxBalls = p.maxBalls,
            nBalls = nballs,
            nLasers = nlasers,
            grid = grid,
            exits = exits,
            done = false,
            laserNo = 1,
            nguesses = 0,
            nright = 0,
            nwrong = 0,
            nmissed = 0,
            reveal = false,
            justWrong = false,
        )
    }

    /** `solve_game`: always `S`, a reveal of the true answer. */
    override fun solve(state: Game.State, auxInfo: String?): String = "S"

    /**
     * `execute_move`.
     *
     * A `justwrong` state first clears its flags, even when the move is
     * invalid. `S` is accepted before the reveal check, so it works on a
     * revealed state. Every other move is rejected after a correct
     * reveal. `T` and `L` do not care about locks, and `R` needs the
     * guess count in range.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val ret = from.copy() as State

        if (ret.justWrong) {
            ret.justWrong = false
            for (i in 0 until ret.nLasers) {
                if (ret.exits[i] != LASER_EMPTY) {
                    ret.exits[i] = ret.exits[i] and
                        (LASER_OMITTED or LASER_WRONG).inv()
                }
            }
        }

        if (move == "S") {
            checkGuesses(ret, cagey = false)
            return ret
        }
        if (from.reveal) return null
        if (move.isEmpty()) return null

        when (move[0]) {
            'T' -> {
                val (gx, gy) = scanTwoInts(move, 1)
                if (gx < 1 || gy < 1 || gx > ret.w || gy > ret.h) return null
                val index = gy * (ret.w + 2) + gx
                if (ret.grid[index] and BALL_GUESS != 0) {
                    ret.nguesses--
                    ret.grid[index] = ret.grid[index] and BALL_GUESS.inv()
                } else {
                    ret.nguesses++
                    ret.grid[index] = ret.grid[index] or BALL_GUESS
                }
            }

            'F' -> {
                val rangeNo = scanInt(move, 1) ?: return null
                if (rangeNo < 0 || rangeNo >= ret.nLasers) return null
                if (ret.exits[rangeNo] != LASER_EMPTY) return null
                fireLaser(ret, rangeNo)
            }

            'R' -> {
                if (ret.nguesses < ret.minBalls ||
                    ret.nguesses > ret.maxBalls
                ) {
                    return null
                }
                checkGuesses(ret, cagey = true)
            }

            'L' -> {
                if (move.length < 2) return null
                when (move[1]) {
                    'B' -> {
                        val (gx, gy) = scanTwoInts(move, 2)
                        if (gx < 1 || gy < 1 || gx > ret.w || gy > ret.h) {
                            return null
                        }
                        ret.grid[gy * (ret.w + 2) + gx] =
                            ret.grid[gy * (ret.w + 2) + gx] xor BALL_LOCK
                    }

                    'C' -> {
                        val gx = scanInt(move, 2) ?: return null
                        if (gx < 1 || gx > ret.w) return null
                        var lcount = 0
                        for (gy in 1..ret.h) {
                            if (ret.grid[gy * (ret.w + 2) + gx] and BALL_LOCK != 0) {
                                lcount++
                            }
                        }
                        for (gy in 1..ret.h) {
                            setLockIf(ret, gx, gy, lcount, ret.h / 2)
                        }
                    }

                    'R' -> {
                        val gy = scanInt(move, 2) ?: return null
                        if (gy < 1 || gy > ret.h) return null
                        var lcount = 0
                        for (gx in 1..ret.w) {
                            if (ret.grid[gy * (ret.w + 2) + gx] and BALL_LOCK != 0) {
                                lcount++
                            }
                        }
                        for (gx in 1..ret.w) {
                            setLockIf(ret, gx, gy, lcount, ret.w / 2)
                        }
                    }

                    else -> return null
                }
            }

            else -> return null
        }

        return ret
    }

    /** `SETLOCKIF`: more than the threshold locks clears, otherwise sets. */
    private fun setLockIf(state: State, gx: Int, gy: Int, lcount: Int, threshold: Int) {
        val index = gy * (state.w + 2) + gx
        state.grid[index] = if (lcount > threshold) {
            state.grid[index] and BALL_LOCK.inv()
        } else {
            state.grid[index] or BALL_LOCK
        }
    }

    /** `game_status`: +1 for a correct reveal, -1 for any other reveal. */
    override fun status(state: Game.State): Int {
        val s = state as State
        if (!s.reveal) return 0
        return if (s.nwrong == 0 && s.nmissed == 0 && s.nright >= s.minBalls) {
            1
        } else {
            -1
        }
    }

    /**
     * The `status_bar` text from `game_redraw`, including the attempt
     * suffix from `ui.errors`. The counts come from the state; no
     * position is ever printed.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val u = ui as Ui
        val out = when {
            s.reveal -> {
                if (s.nwrong == 0 && s.nmissed == 0 && s.nright >= s.minBalls) {
                    "CORRECT!"
                } else {
                    "${s.nwrong} wrong and ${s.nmissed} missed balls."
                }
            }

            s.justWrong -> "Wrong! Guess again."
            s.nguesses > s.maxBalls -> {
                "${s.nguesses - s.maxBalls} too many balls marked."
            }

            s.nguesses <= s.maxBalls && s.nguesses >= s.minBalls ->
                "Click button to verify guesses."

            s.maxBalls == s.minBalls ->
                "Balls marked: ${s.nguesses} / ${s.minBalls}"

            else ->
                "Balls marked: ${s.nguesses} / ${s.minBalls}-${s.maxBalls}."
        }

        if (u.errors == 0) return out
        return if (u.errors == 1) {
            "$out (1 incorrect attempt)"
        } else {
            "$out (${u.errors} incorrect attempts)"
        }
    }

    /** `blackbox.c` has no text format; [Midend] checks [canFormatAsText] first. */
    override fun textFormat(state: Game.State): String = ""

    /**
     * `game_changed_state`: count a wrong reveal that a real move
     * produced, clear the one-shot flag, and report the completion
     * transition. A redo into a correct reveal reports it again.
     */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val u = ui as Ui
        val new = newState as State
        if (new.justWrong && u.newmove) u.errors++
        u.newmove = false
        val old = oldState as State?
        return new.reveal && old != null && !old.reveal &&
            new.nwrong == 0 && new.nmissed == 0 &&
            new.nright >= new.minBalls
    }

    /** `game_request_keys` is NULL, so only the arrow mode is set. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    /** `game_compute_size`: one half-tile border and two range rows. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        return Size((p.w + 3) * tileSize, (p.h + 3) * tileSize)
    }

    /**
     * The arena cells, the reveal button, and the laser sites of the
     * ring, all placed where `toDraw` and `draw_laser_tile` draw them.
     *
     * The two arena buttons are the game's own words from
     * `currentKeyLabel`: `Ball` or `Clear` for the left one and `Lock`
     * or `Unlock` for the right one, and a lock blocks the ball button
     * exactly as `interpret_move` does. A laser site fires with the left
     * button while it is unfired and locks its row or column with the
     * right one. The reveal button is a laser site of the corner in C,
     * so it is a unit with the `Reveal` action.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val t = tileSize
        val w = s.w
        val h = s.h
        val cells = gridSpeech(
            width = w,
            height = h,
            tileSize = t,
            left = t + t / 2,
            top = t + t / 2,
            summary = statusText(state, ui),
            text = { x, y ->
                arenaSpeech(s, x + 1, y + 1) +
                    if (u.curVisible && u.curX == x + 1 && u.curY == y + 1) {
                        ", cursor"
                    } else {
                        ""
                    }
            },
            actions = { x, y -> arenaButtons(s, x + 1, y + 1) },
        )

        val extras = buildList {
            add(
                SpokenUnit(
                    row = 0,
                    column = 0,
                    left = t / 2,
                    top = t / 2,
                    width = t,
                    height = t,
                    text = "Reveal" +
                        if (u.curVisible && u.curX == 0 && u.curY == 0) ", cursor" else "",
                    actions = if (canReveal(s)) {
                        listOf(SpokenAction("Reveal", Buttons.LEFT_BUTTON))
                    } else {
                        emptyList()
                    },
                ),
            )
            for (lno in 0 until s.nLasers) {
                val cell = range2Grid(s, lno) ?: continue
                add(
                    SpokenUnit(
                        row = 0,
                        column = 0,
                        left = t * cell.x + t / 2,
                        top = t * cell.y + t / 2,
                        width = t,
                        height = t,
                        text = rangeName(s, cell.x, cell.y) + ", " + rangeSpeech(s, lno) +
                            if (u.curVisible && u.curX == cell.x && u.curY == cell.y) {
                                ", cursor"
                            } else {
                                ""
                            },
                        actions = rangeButtons(s, cell.x, cell.y, lno),
                    ),
                )
            }
        }

        val board = cells.copy(units = cells.units + extras)
        val cursor = cursorLocation(ui, state, t)?.let {
            board.units.unitAt(it.left + it.width / 2, it.top + it.height / 2)
        }
        return board.cappedUnits(cursor)
    }

    /** One arena cell: empty or a ball, locked or revealed. */
    private fun arenaSpeech(s: State, gx: Int, gy: Int): String {
        val cell = s.gridAt(gx, gy)
        val ball = cell and BALL_GUESS != 0
        val correct = cell and BALL_CORRECT != 0
        val content = when {
            !s.reveal && ball -> "ball"
            !s.reveal -> "empty"
            ball && correct -> "ball, correct"
            ball -> "ball, wrong"
            correct -> "ball, missed"
            else -> "empty"
        }
        return content + if (cell and BALL_LOCK != 0) ", locked" else ""
    }

    /** `Ball`/`Clear` and `Lock`/`Unlock`, the words of `currentKeyLabel`. */
    private fun arenaButtons(s: State, gx: Int, gy: Int): List<SpokenAction> {
        if (s.reveal) return emptyList()
        val cell = s.gridAt(gx, gy)
        val actions = buildList {
            if (cell and BALL_LOCK == 0) {
                add(
                    SpokenAction(
                        if (cell and BALL_GUESS != 0) "Clear" else "Ball",
                        Buttons.LEFT_BUTTON,
                    ),
                )
            }
            add(
                SpokenAction(
                    if (cell and BALL_LOCK != 0) "Unlock" else "Lock",
                    Buttons.RIGHT_BUTTON,
                ),
            )
        }
        return actions
    }

    /** Where a laser site sits, in the words of the spec's example. */
    private fun rangeName(s: State, gx: Int, gy: Int): String = when {
        gy == 0 -> "Top edge, column $gx"
        gy == s.h + 1 -> "Bottom edge, column $gx"
        gx == 0 -> "Left edge, row $gy"
        else -> "Right edge, row $gy"
    }

    /** What a laser site shows: the number of the pair, or H, R, or none. */
    private fun rangeSpeech(s: State, lno: Int): String {
        val exit = s.exits[lno]
        if (exit == LASER_EMPTY) return "not fired"
        val wrong = if (exit and LASER_WRONG != 0) SPOKEN_WRONG else ""
        val omitted = if (exit and LASER_OMITTED != 0) ", left out" else ""
        val value = exit and LASER_FLAGMASK.inv()
        val cell = range2Grid(s, lno) ?: return "not fired"
        val drawn = s.grid[cell.y * (s.w + 2) + cell.x]
        val shown = when {
            drawn and LASER_REFLECT != 0 -> "reflected"
            drawn and LASER_HIT != 0 -> "hit"
            else -> "laser ${drawn and LASER_FLAGMASK.inv()}"
        }
        return shown + omitted + wrong
    }

    /** `Fire` on an unfired site, and the lock of its row or column. */
    private fun rangeButtons(s: State, gx: Int, gy: Int, lno: Int): List<SpokenAction> {
        val actions = buildList {
            if (s.exits[lno] == LASER_EMPTY) {
                add(SpokenAction("Fire", Buttons.LEFT_BUTTON))
            }
            if (s.reveal) return@buildList
            var locks = 0
            if (gy == 0 || gy > s.h) {
                for (y in 1..s.h) {
                    if (s.gridAt(gx, y) and BALL_LOCK != 0) locks++
                }
                add(
                    SpokenAction(
                        if (locks > s.h / 2) "Unlock" else "Lock",
                        Buttons.RIGHT_BUTTON,
                    ),
                )
            } else {
                for (x in 1..s.w) {
                    if (s.gridAt(x, gy) and BALL_LOCK != 0) locks++
                }
                add(
                    SpokenAction(
                        if (locks > s.w / 2) "Unlock" else "Lock",
                        Buttons.RIGHT_BUTTON,
                    ),
                )
            }
        }
        return actions
    }

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /**
     * `game_colours`: `game_mkhighlight` for the background, highlight,
     * and lowlight, five fixed colours, then the derived grid, lock,
     * cover, and text colours.
     */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_HIGHLIGHT, COL_LOWLIGHT)

        setColour(ret, COL_BALL, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_BALL_REVEALED, 0.0f, 0.0f, 0.0f)
        setColour(ret, COL_WRONG, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_BUTTON, 0.0f, 1.0f, 0.0f)
        setColour(ret, COL_CURSOR, 1.0f, 0.0f, 0.0f)
        setColour(ret, COL_FLASHTEXT, 0.0f, 1.0f, 0.0f)

        for (i in 0..2) {
            ret[COL_GRID * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.9f
            ret[COL_LOCK * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.7f
            ret[COL_COVER * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.5f
            ret[COL_TEXT * 3 + i] = 0.0f
        }
        return ret
    }

    private fun setColour(ret: FloatArray, index: Int, r: Float, g: Float, b: Float) {
        ret[index * 3] = r
        ret[index * 3 + 1] = g
        ret[index * 3 + 2] = b
    }

    /** `new_ui`: a hidden cursor on the first arena cell and no flash. */
    override fun newUi(state: Game.State): Game.Ui = Ui()

    /** `encode_ui`: only the attempt counter, as `E<int>`. */
    override fun encodeUi(ui: Game.Ui): String? = "E${(ui as Ui).errors}"

    /**
     * `decode_ui`: `sscanf("E%d")`. A failed match leaves the fresh
     * value of zero, so every legacy form loads quietly. The state is
     * unused, as in C.
     */
    override fun decodeUi(state: Game.State, text: String): Game.Ui {
        val ui = Ui()
        if (text.startsWith('E')) {
            scanInt(text, 1)?.let { ui.errors = it }
        }
        return ui
    }

    /** `android_cursor_visibility`. */
    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `game_get_cursor_location`: a whole tile, and only when visible. */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        val s = state as State
        val geometry = Geometry(tileSize, s.w, s.h)
        return Game.CursorRect(
            geometry.toDraw(u.curX),
            geometry.toDraw(u.curY),
            tileSize,
            tileSize,
        )
    }

    /**
     * `current_key_label`: `Check` on the reveal button, `Ball`/`Clear`
     * in the arena, `Lock`/`Unlock` for the second select, and `Fire` on
     * an unfired range tile. There is no Android call site, so this is a
     * public helper and not a `Game` method.
     */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if (button != Buttons.CURSOR_SELECT && button != Buttons.CURSOR_SELECT2) {
            return ""
        }
        if (!u.curVisible || s.reveal) return ""
        val gx = u.curX
        val gy = u.curY
        if (gx == 0 && gy == 0 && button == Buttons.CURSOR_SELECT) return "Check"

        if (gx in 1..s.w && gy in 1..s.h) {
            val cell = s.gridAt(gx, gy)
            if (button == Buttons.CURSOR_SELECT && cell and BALL_LOCK == 0) {
                return if (cell and BALL_GUESS != 0) "Clear" else "Ball"
            }
            if (button == Buttons.CURSOR_SELECT2) {
                return if (cell and BALL_LOCK != 0) "Unlock" else "Lock"
            }
        }

        val rangeNo = grid2Range(s, gx, gy)
        if (rangeNo >= 0) {
            if (button == Buttons.CURSOR_SELECT && s.exits[rangeNo] == LASER_EMPTY) {
                return "Fire"
            }
            if (button == Buttons.CURSOR_SELECT2) {
                var locks = 0
                if (gy == 0 || gy > s.h) {
                    for (y in 1..s.h) {
                        if (s.gridAt(gx, y) and BALL_LOCK != 0) locks++
                    }
                    return if (locks > s.h / 2) "Unlock" else "Lock"
                }
                for (x in 1..s.w) {
                    if (s.gridAt(x, gy) and BALL_LOCK != 0) locks++
                }
                return if (locks > s.w / 2) "Unlock" else "Lock"
            }
        }
        return ""
    }

    /** `game_new_drawstate` and `game_set_size`: a zeroed tile cache. */
    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(
            tileSize = tileSize,
            crad = (tileSize - 1) / 2,
            rrad = (3 * tileSize) / 8,
            w = s.w,
            h = s.h,
            grid = IntArray((s.w + 2) * (s.h + 2)),
        )
    }

    /** `game_free_drawstate`: the cache owns no sink resource. */
    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {
        // No blitter.
    }

    /**
     * `interpret_move`. The pointer paths act on the press; a drag and a
     * release other than `LEFT_RELEASE` change nothing. A left click on
     * a locked cell is unused but still hides the cursor, as C does.
     *
     * The seam has no animation time, so a keyboard fire sets the
     * animation-only flash mode 2 and draws no flash, which matches the
     * oracle traces. A mouse press sets mode 1 and flashes until the
     * release.
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
        val geometry = Geometry(tileSize, from.w, from.h)
        var gx = -1
        var gy = -1
        var wouldFlash = 0
        var action = Action.NONE
        var nullRet: MoveResult = MoveResult.Unused
        var effectiveButton = button

        if (Buttons.isCursorMove(button)) {
            var cx = u.curX
            var cy = u.curY
            when (button) {
                Buttons.CURSOR_UP -> cy--
                Buttons.CURSOR_DOWN -> cy++
                Buttons.CURSOR_LEFT -> cx--
                Buttons.CURSOR_RIGHT -> cx++
            }
            cx = min(max(cx, 0), from.w + 1)
            cy = min(max(cy, 0), from.h + 1)
            if ((cx == 0 && cy == 0 && !canReveal(from)) ||
                (cx == 0 && cy == from.h + 1) ||
                (cx == from.w + 1 && cy == 0) ||
                (cx == from.w + 1 && cy == from.h + 1)
            ) {
                return MoveResult.Unused
            }
            u.curX = cx
            u.curY = cy
            u.curVisible = true
            return MoveResult.UiUpdate
        }

        if (button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON) {
            gx = geometry.fromDraw(x)
            gy = geometry.fromDraw(y)
            u.curVisible = false
            wouldFlash = 1
        } else if (button == Buttons.LEFT_RELEASE) {
            u.flashLaser = 0
            return MoveResult.UiUpdate
        } else if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (u.curVisible) {
                gx = u.curX
                gy = u.curY
                u.flashLaser = 0
                wouldFlash = 2
            } else {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            effectiveButton = if (button == Buttons.CURSOR_SELECT2) {
                Buttons.RIGHT_BUTTON
            } else {
                Buttons.LEFT_BUTTON
            }
        }

        if (gx != -1 && gy != -1) {
            if (gx == 0 && gy == 0 && Buttons.isMouseDown(effectiveButton)) {
                action = Action.REVEAL
            }
            if (gx in 1..from.w && gy in 1..from.h) {
                if (effectiveButton == Buttons.LEFT_BUTTON) {
                    if (from.gridAt(gx, gy) and BALL_LOCK == 0) {
                        action = Action.TOGGLE_BALL
                    }
                } else {
                    action = Action.TOGGLE_LOCK
                }
            }
            if (grid2Range(from, gx, gy) >= 0) {
                if (effectiveButton == Buttons.LEFT_BUTTON) {
                    action = Action.FIRE
                } else if (gy == 0 || gy > from.h) {
                    action = Action.TOGGLE_COLUMN_LOCK
                } else {
                    action = Action.TOGGLE_ROW_LOCK
                }
            }
        }

        val text: String = when (action) {
            Action.TOGGLE_BALL -> "T$gx,$gy"
            Action.TOGGLE_LOCK -> "LB$gx,$gy"
            Action.TOGGLE_COLUMN_LOCK -> "LC$gx"
            Action.TOGGLE_ROW_LOCK -> "LR$gy"
            Action.FIRE -> {
                val rangeNo = grid2Range(from, gx, gy)
                if (from.reveal && from.exits[rangeNo] == LASER_EMPTY) {
                    return MoveResult.Unused
                }
                u.flashLaserno = rangeNo
                u.flashLaser = wouldFlash
                nullRet = MoveResult.UiUpdate
                if (from.exits[rangeNo] != LASER_EMPTY) return MoveResult.UiUpdate
                "F$rangeNo"
            }

            Action.REVEAL -> {
                if (!canReveal(from)) return MoveResult.Unused
                if (u.curVisible) {
                    u.curX = 1
                    u.curY = 1
                }
                "R"
            }

            Action.NONE -> return nullRet
        }

        if (from.reveal) return nullRet
        u.newmove = true
        return MoveResult.Move(text)
    }

    private enum class Action {
        NONE,
        TOGGLE_BALL,
        TOGGLE_LOCK,
        FIRE,
        REVEAL,
        TOGGLE_COLUMN_LOCK,
        TOGGLE_ROW_LOCK,
    }

    private fun canReveal(state: State): Boolean =
        state.nguesses >= state.minBalls &&
            state.nguesses <= state.maxBalls &&
            !state.reveal && !state.justWrong

    /**
     * `game_redraw`: the frame on the first draw of a drawstate, then
     * only the grid cells whose composite value changed, and always the
     * reveal button. There is no anim or flash time in the seam, so
     * only `flash_laser == 1` can be in a frame.
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
        val geometry = Geometry(tileSize, s.w, s.h)
        var force = false

        if (!ds.started) {
            val x0 = geometry.toDraw(0) - 1
            val y0 = geometry.toDraw(0) - 1
            val x1 = geometry.toDraw(s.w + 2)
            val y1 = geometry.toDraw(s.h + 2)
            val ts = tileSize

            sink.line(x0 + ts, y0 + ts, x0 + ts, y0, COL_HIGHLIGHT)
            sink.line(x0 + ts, y0, x1 - ts, y0, COL_HIGHLIGHT)
            sink.line(x1 - ts, y0, x1 - ts, y0 + ts, COL_LOWLIGHT)
            sink.line(x1 - ts, y0 + ts, x1, y0 + ts, COL_HIGHLIGHT)
            sink.line(x1, y0 + ts, x1, y1 - ts, COL_LOWLIGHT)
            sink.line(x1, y1 - ts, x1 - ts, y1 - ts, COL_LOWLIGHT)
            sink.line(x1 - ts, y1 - ts, x1 - ts, y1, COL_LOWLIGHT)
            sink.line(x1 - ts, y1, x0 + ts, y1, COL_LOWLIGHT)
            sink.line(x0 + ts, y1, x0 + ts, y1 - ts, COL_HIGHLIGHT)
            sink.line(x0 + ts, y1 - ts, x0, y1 - ts, COL_LOWLIGHT)
            sink.line(x0, y1 - ts, x0, y0 + ts, COL_HIGHLIGHT)
            sink.line(x0, y0 + ts, x0 + ts, y0 + ts, COL_HIGHLIGHT)

            sink.update(0, 0, ts * (s.w + 3), ts * (s.h + 3))
            force = true
            ds.started = true
        }

        for (x in 0 until s.w) {
            for (y in 0 until s.h) {
                drawArenaTile(sink, s, ds, u, x + 1, y + 1, force, tileSize)
            }
        }

        ds.flashLaserno = if (u.flashLaser == 1) u.flashLaserno else LASER_EMPTY
        for (i in 0 until s.nLasers) {
            drawLaserTile(sink, s, ds, u, i, force, tileSize)
        }

        if (canReveal(s)) {
            val outline = if (u.curVisible && u.curX == 0 && u.curY == 0) {
                COL_CURSOR
            } else {
                COL_BALL
            }
            val origin = geometry.toDraw(0)
            sink.clip(origin - 1, origin - 1, tileSize + 1, tileSize + 1)
            sink.circle(
                origin + ds.crad - 1,
                origin + ds.crad - 1,
                ds.crad - 1,
                outline,
                outline,
            )
            sink.circle(
                origin + ds.crad - 1,
                origin + ds.crad - 1,
                ds.crad - 3,
                COL_BUTTON,
                COL_BUTTON,
            )
            sink.unclip()
        } else {
            sink.rect(
                geometry.toDraw(0) - 1,
                geometry.toDraw(0) - 1,
                tileSize,
                tileSize,
                COL_BACKGROUND,
            )
        }
        sink.update(
            geometry.toDraw(0),
            geometry.toDraw(0),
            tileSize,
            tileSize,
        )

        ds.reveal = s.reveal
        ds.isflash = false
    }

    /**
     * `draw_arena_tile`. The cache holds the composite value including
     * the cursor flag, so a cursor move repaints the old and the new
     * cell. A revealed arena draws guessed balls black, missed balls
     * red, and a red cross over a wrong guess.
     */
    private fun drawArenaTile(
        sink: DrawSink,
        state: State,
        ds: DrawState,
        ui: Ui,
        gx: Int,
        gy: Int,
        force: Boolean,
        tileSize: Int,
    ) {
        val index = gy * (state.w + 2) + gx
        var gsTile = state.grid[index]
        val dsTile = ds.grid[index]
        val dx = tileSize * gx + tileSize / 2
        val dy = tileSize * gy + tileSize / 2

        if (ui.curVisible && ui.curX == gx && ui.curY == gy) {
            gsTile = gsTile or FLAG_CURSOR
        }

        if (gsTile != dsTile || state.reveal != ds.reveal || force) {
            val bg = when {
                state.reveal -> COL_BACKGROUND
                gsTile and BALL_LOCK != 0 -> COL_LOCK
                else -> COL_COVER
            }

            sink.rect(dx, dy, tileSize, tileSize, bg)
            drawRectOutline(sink, dx, dy, tileSize, tileSize, COL_GRID)

            val bcol = if (state.reveal) {
                if (gsTile and BALL_GUESS != 0) {
                    COL_BALL_REVEALED
                } else if (gsTile and BALL_CORRECT != 0) {
                    COL_WRONG
                } else {
                    bg
                }
            } else if (gsTile and BALL_GUESS != 0) {
                COL_BALL
            } else {
                bg
            }

            val ocol = if (gsTile and FLAG_CURSOR != 0 && bcol != bg) {
                COL_CURSOR
            } else {
                bcol
            }

            sink.circle(
                dx + tileSize / 2,
                dy + tileSize / 2,
                ds.crad - 1,
                ocol,
                ocol,
            )
            sink.circle(
                dx + tileSize / 2,
                dy + tileSize / 2,
                ds.crad - 3,
                bcol,
                bcol,
            )

            if (gsTile and FLAG_CURSOR != 0 && bcol == bg) {
                drawSquareCursor(sink, dx, dy, tileSize)
            }

            if (state.reveal && gsTile and BALL_GUESS != 0 &&
                gsTile and BALL_CORRECT == 0
            ) {
                val x1 = dx + 3
                val y1 = dy + 3
                val x2 = dx + tileSize - 3
                val y2 = dy + tileSize - 3
                sink.poly(
                    intArrayOf(
                        x1 - 1, y1 + 1,
                        x1 + 1, y1 - 1,
                        x2 + 1, y2 - 1,
                        x2 - 1, y2 + 1,
                    ),
                    COL_WRONG,
                    COL_WRONG,
                )
                sink.poly(
                    intArrayOf(
                        x2 + 1, y1 + 1,
                        x2 - 1, y1 - 1,
                        x1 - 1, y2 - 1,
                        x1 + 1, y2 + 1,
                    ),
                    COL_WRONG,
                    COL_WRONG,
                )
            }

            sink.update(dx, dy, tileSize, tileSize)
        }
        ds.grid[index] = gsTile
    }

    /**
     * `draw_laser_tile`. The composite value adds the wrong and omitted
     * flags from `exits` and the draw-only flash and cursor flags, so
     * the cache sees every change. A flashed tile uses the green flash
     * text color.
     */
    private fun drawLaserTile(
        sink: DrawSink,
        state: State,
        ds: DrawState,
        ui: Ui,
        lno: Int,
        force: Boolean,
        tileSize: Int,
    ) {
        val cell = range2Grid(state, lno)
            ?: error("laser $lno has no range cell")
        val index = cell.y * (state.w + 2) + cell.x
        var gsTile = state.grid[index]
        val dsTile = ds.grid[index]
        val dx = tileSize * cell.x + tileSize / 2
        val dy = tileSize * cell.y + tileSize / 2

        val wrong = state.exits[lno] and LASER_WRONG
        val omitted = state.exits[lno] and LASER_OMITTED
        val exitNo = state.exits[lno] and LASER_FLAGMASK.inv()
        val reflect = gsTile and LASER_REFLECT != 0
        val hit = gsTile and LASER_HIT != 0
        val laserValue = gsTile and LASER_FLAGMASK.inv()

        if (lno == ds.flashLaserno) {
            gsTile = gsTile or LASER_FLASHED
        } else if (state.exits[lno] and (LASER_HIT or LASER_REFLECT) == 0) {
            if (exitNo == ds.flashLaserno) gsTile = gsTile or LASER_FLASHED
        }
        val flash = gsTile and LASER_FLASHED != 0
        gsTile = gsTile or wrong or omitted

        if (ui.curVisible && ui.curX == cell.x && ui.curY == cell.y) {
            gsTile = gsTile or FLAG_CURSOR
        }

        if (gsTile != dsTile || force) {
            sink.rect(dx, dy, tileSize, tileSize, COL_BACKGROUND)
            drawRectOutline(sink, dx, dy, tileSize, tileSize, COL_GRID)

            if (gsTile and
                (LASER_WRONG or LASER_OMITTED or FLAG_CURSOR).inv() != 0
            ) {
                val tcol = when {
                    flash -> COL_FLASHTEXT
                    omitted != 0 -> COL_WRONG
                    else -> COL_TEXT
                }
                val text = when {
                    reflect -> "R"
                    hit -> "H"
                    else -> laserValue.toString()
                }

                if (wrong != 0) {
                    sink.circle(
                        dx + tileSize / 2,
                        dy + tileSize / 2,
                        ds.rrad,
                        COL_WRONG,
                        COL_WRONG,
                    )
                    sink.circle(
                        dx + tileSize / 2,
                        dy + tileSize / 2,
                        ds.rrad - tileSize / 16,
                        COL_BACKGROUND,
                        COL_WRONG,
                    )
                }

                sink.text(
                    dx + tileSize / 2,
                    dy + tileSize / 2,
                    Text.FONT_VARIABLE,
                    tileSize / 2,
                    Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
                    tcol,
                    text,
                )
            }
            if (gsTile and FLAG_CURSOR != 0) {
                drawSquareCursor(sink, dx, dy, tileSize)
            }
            sink.update(dx, dy, tileSize, tileSize)
        }
        ds.grid[index] = gsTile
    }

    /** `draw_rect_outline`: a four-point polygon with no fill. */
    private fun drawRectOutline(
        sink: DrawSink,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colour: Int,
    ) {
        val x0 = x
        val x1 = x + width - 1
        val y0 = y
        val y1 = y + height - 1
        sink.poly(intArrayOf(x0, y0, x0, y1, x1, y1, x1, y0), -1, colour)
    }

    /** `draw_square_cursor`: an inset one-pixel outline. */
    private fun drawSquareCursor(sink: DrawSink, dx: Int, dy: Int, tileSize: Int) {
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

    /**
     * `range2grid`: map a laser number to its entry cell and direction,
     * clockwise from the top-left. The four corners have no laser.
     */
    private fun range2Grid(state: State, rangeNo: Int): LaserCell? {
        if (rangeNo < 0) return null
        var remaining = rangeNo
        if (remaining < state.w) {
            return LaserCell(remaining + 1, 0, DIR_DOWN)
        }
        remaining -= state.w
        if (remaining < state.h) {
            return LaserCell(state.w + 1, remaining + 1, DIR_LEFT)
        }
        remaining -= state.h
        if (remaining < state.w) {
            return LaserCell(state.w - remaining, state.h + 1, DIR_UP)
        }
        remaining -= state.w
        if (remaining < state.h) {
            return LaserCell(0, state.h - remaining, DIR_RIGHT)
        }
        return null
    }

    /** `grid2range`: the inverse, or -1 for the arena and the corners. */
    private fun grid2Range(state: State, x: Int, y: Int): Int {
        val x1 = state.w + 1
        val y1 = state.h + 1
        if (x > 0 && x < x1 && y > 0 && y < y1) return -1
        if (x < 0 || x > x1 || y < 0 || y > y1) return -1
        if ((x == 0 || x == x1) && (y == 0 || y == y1)) return -1

        return when {
            y == 0 -> x - 1
            x == x1 -> y - 1 + state.w
            y == y1 -> (state.w - x) + state.w + state.h
            else -> (state.h - y) + state.w + state.w + state.h
        }
    }

    /** The x part of `OFFSET`; the direction is taken modulo four. */
    private fun offsetX(x: Int, direction: Int): Int =
        x + OFFSET_X[(4 + direction % 4) % 4]

    /** The y part of `OFFSET`. */
    private fun offsetY(y: Int, direction: Int): Int =
        y + OFFSET_Y[(4 + direction % 4) % 4]

    /**
     * `isball`: a ball directly ahead, or on the front-left or
     * front-right diagonal. The two offsets are composed; a single
     * diagonal offset would change every result. Off the arena there is
     * never a ball.
     */
    private fun isBall(
        state: State,
        gx: Int,
        gy: Int,
        direction: Int,
        lookWhere: Int,
    ): Boolean {
        var x = offsetX(gx, direction)
        var y = offsetY(gy, direction)
        if (lookWhere == LOOK_LEFT) {
            x = offsetX(x, direction - 1)
            y = offsetY(y, direction - 1)
        } else if (lookWhere == LOOK_RIGHT) {
            x = offsetX(x, direction + 1)
            y = offsetY(y, direction + 1)
        }
        if (x < 1 || y < 1 || x > state.w || y > state.h) return false
        return state.gridAt(x, y) and BALL_CORRECT != 0
    }

    /**
     * `fire_laser_internal`: the instant hit or reflection at the entry,
     * then a step loop that turns clockwise at a ball on the left and
     * anti-clockwise at a ball on the right.
     *
     * A malformed layout must not hang the UI. A ray that visits the
     * same cell and direction twice would be a cycle, and no valid
     * layout has one; the bound turns that into a clear error instead of
     * an infinite loop.
     */
    private fun fireLaserInternal(
        state: State,
        startX: Int,
        startY: Int,
        startDirection: Int,
    ): Int {
        val entryNo = grid2Range(state, startX, startY)
        require(entryNo >= 0) { "laser entry ($startX,$startY) is not in the range" }

        if (isBall(state, startX, startY, startDirection, LOOK_FORWARD)) {
            return LASER_HIT
        }
        if (isBall(state, startX, startY, startDirection, LOOK_LEFT) ||
            isBall(state, startX, startY, startDirection, LOOK_RIGHT)
        ) {
            return LASER_REFLECT
        }

        var x = offsetX(startX, startDirection)
        var y = offsetY(startY, startDirection)
        var direction = startDirection
        var steps = 0
        val limit = 4 * (state.w + 2) * (state.h + 2) + 16

        while (true) {
            val exitNo = grid2Range(state, x, y)
            if (exitNo >= 0) {
                return if (entryNo == exitNo) LASER_REFLECT else exitNo
            }
            if (++steps > limit) {
                throw IllegalStateException(
                    "laser $entryNo cycles on a ${state.w}x${state.h} grid",
                )
            }

            if (isBall(state, x, y, direction, LOOK_FORWARD)) {
                return LASER_HIT
            }
            if (isBall(state, x, y, direction, LOOK_LEFT)) {
                direction = (direction + 1) % 4
                continue
            }
            if (isBall(state, x, y, direction, LOOK_RIGHT)) {
                direction = (direction + 3) % 4
                continue
            }
            x = offsetX(x, direction)
            y = offsetY(y, direction)
        }
    }

    /** `laser_exit`: the result of a shot without storing it. */
    internal fun laserExit(state: State, entryNo: Int): Int {
        val cell = range2Grid(state, entryNo)
            ?: error("laser $entryNo has no range cell")
        return fireLaserInternal(state, cell.x, cell.y, cell.direction)
    }

    /**
     * `fire_laser`: store the result. A hit or a reflection paints the
     * entry only; an exit allocates one number and paints both ends.
     */
    private fun fireLaser(state: State, entryNo: Int) {
        val cell = range2Grid(state, entryNo)
            ?: error("laser $entryNo has no range cell")
        val exitNo = fireLaserInternal(state, cell.x, cell.y, cell.direction)

        if (exitNo == LASER_HIT || exitNo == LASER_REFLECT) {
            state.grid[cell.y * (state.w + 2) + cell.x] = exitNo
            state.exits[entryNo] = exitNo
        } else {
            val newNo = state.laserNo++
            val endCell = range2Grid(state, exitNo)
                ?: error("laser exit $exitNo has no range cell")
            state.grid[cell.y * (state.w + 2) + cell.x] = newNo
            state.grid[endCell.y * (state.w + 2) + endCell.x] = newNo
            state.exits[entryNo] = exitNo
            state.exits[exitNo] = entryNo
        }
    }

    /**
     * `check_guesses`. The cagey pass demonstrates one contradiction and
     * returns status 0. The normal pass fires every laser on both the
     * true and the guessed layout, copies the differences, and then
     * computes the counters and sets `reveal`.
     *
     * The temporary generator is seeded from the raw little-endian
     * `Int` bytes of the guess grid, exactly as C seeds it from an
     * `unsigned int` array.
     */
    private fun checkGuesses(state: State, cagey: Boolean): Int {
        if (cagey) {
            val guesses = state.copy() as State
            for (x in 1..state.w) {
                for (y in 1..state.h) {
                    val index = y * (state.w + 2) + x
                    guesses.grid[index] = guesses.grid[index] and BALL_CORRECT.inv()
                    if (guesses.grid[index] and BALL_GUESS != 0) {
                        guesses.grid[index] = guesses.grid[index] or BALL_CORRECT
                    }
                }
            }

            var n = 0
            for (i in 0 until guesses.nLasers) {
                if (guesses.exits[i] != LASER_EMPTY &&
                    guesses.exits[i] != laserExit(guesses, i)
                ) {
                    n++
                }
            }
            if (n > 0) {
                n = gridRandomUpto(guesses, n)
                for (i in 0 until guesses.nLasers) {
                    if (guesses.exits[i] != LASER_EMPTY &&
                        guesses.exits[i] != laserExit(guesses, i) &&
                        n-- == 0
                    ) {
                        state.exits[i] = state.exits[i] or LASER_WRONG
                        val tmp = laserExit(state, i)
                        if (tmp >= 0 && tmp < state.nLasers) {
                            state.exits[tmp] = state.exits[tmp] or LASER_WRONG
                        }
                        state.justWrong = true
                        return 0
                    }
                }
            }

            n = 0
            for (i in 0 until guesses.nLasers) {
                if (guesses.exits[i] == LASER_EMPTY &&
                    laserExit(state, i) != laserExit(guesses, i)
                ) {
                    n++
                }
            }
            if (n > 0) {
                n = gridRandomUpto(guesses, n)
                for (i in 0 until guesses.nLasers) {
                    if (guesses.exits[i] == LASER_EMPTY &&
                        laserExit(state, i) != laserExit(guesses, i) &&
                        n-- == 0
                    ) {
                        fireLaser(state, i)
                        state.exits[i] = state.exits[i] or LASER_OMITTED
                        val tmp = laserExit(state, i)
                        if (tmp >= 0 && tmp < state.nLasers) {
                            state.exits[tmp] = state.exits[tmp] or LASER_OMITTED
                        }
                        state.justWrong = true
                        return 0
                    }
                }
            }
        }

        val solution = state.copy() as State
        for (i in 0 until solution.nLasers) {
            val cell = range2Grid(solution, i)
                ?: error("laser $i has no range cell")
            solution.grid[cell.y * (solution.w + 2) + cell.x] = 0
            solution.exits[i] = LASER_EMPTY
        }

        val guesses = solution.copy() as State
        for (x in 1..state.w) {
            for (y in 1..state.h) {
                val index = y * (state.w + 2) + x
                guesses.grid[index] = guesses.grid[index] and BALL_CORRECT.inv()
                if (guesses.grid[index] and BALL_GUESS != 0) {
                    guesses.grid[index] = guesses.grid[index] or BALL_CORRECT
                }
            }
        }

        for (i in 0 until solution.nLasers) {
            if (solution.exits[i] == LASER_EMPTY) fireLaser(solution, i)
            if (guesses.exits[i] == LASER_EMPTY) fireLaser(guesses, i)
        }

        var ret = 1
        for (i in 0 until solution.nLasers) {
            if (solution.exits[i] == guesses.exits[i]) continue
            val cell = range2Grid(solution, i)
                ?: error("laser $i has no range cell")
            if (state.exits[i] == LASER_EMPTY) {
                state.exits[i] = solution.exits[i]
                if (state.exits[i] == LASER_REFLECT ||
                    state.exits[i] == LASER_HIT
                ) {
                    state.grid[cell.y * (state.w + 2) + cell.x] = state.exits[i]
                } else {
                    val newNo = state.laserNo++
                    val endCell = range2Grid(state, state.exits[i])
                        ?: error("laser exit ${state.exits[i]} has no range cell")
                    state.grid[cell.y * (state.w + 2) + cell.x] = newNo
                    state.grid[endCell.y * (state.w + 2) + endCell.x] = newNo
                }
                state.exits[i] = state.exits[i] or LASER_OMITTED
            } else {
                state.exits[i] = state.exits[i] or LASER_WRONG
            }
            ret = 0
        }

        if (ret != 0 &&
            state.nguesses >= state.minBalls &&
            state.nguesses <= state.maxBalls
        ) {
            for (x in 1..state.w) {
                for (y in 1..state.h) {
                    val index = y * (state.w + 2) + x
                    state.grid[index] = if (state.grid[index] and BALL_GUESS != 0) {
                        state.grid[index] or BALL_CORRECT
                    } else {
                        state.grid[index] and BALL_CORRECT.inv()
                    }
                }
            }
        }

        state.nright = 0
        state.nwrong = 0
        state.nmissed = 0
        for (x in 1..state.w) {
            for (y in 1..state.h) {
                val bs = state.gridAt(x, y) and (BALL_GUESS or BALL_CORRECT)
                when (bs) {
                    BALL_GUESS or BALL_CORRECT -> state.nright++
                    BALL_GUESS -> state.nwrong++
                    BALL_CORRECT -> state.nmissed++
                }
            }
        }
        state.reveal = true
        return ret
    }

    /**
     * `random_new((char *)grid, cells * sizeof(unsigned int))` followed by
     * one `random_upto`. The grid is serialized as four little-endian
     * bytes per cell, which is the oracle's host byte order.
     */
    internal fun gridRandomUpto(state: State, limit: Int): Int {
        val bytes = ByteArray(state.grid.size * 4)
        for (i in state.grid.indices) {
            val value = state.grid[i]
            bytes[i * 4] = value.toByte()
            bytes[i * 4 + 1] = (value ushr 8).toByte()
            bytes[i * 4 + 2] = (value ushr 16).toByte()
            bytes[i * 4 + 3] = (value ushr 24).toByte()
        }
        val rs = RandomState.fromSeed(bytes)
        return rs.upto(limit.toLong()).toInt()
    }

    /**
     * `sscanf("%d,%d")`: the first conversion, then a literal comma,
     * then the second. A missing or failed conversion leaves `-1`, as
     * the C locals start at `-1`.
     */
    private fun scanTwoInts(text: String, start: Int): Pair<Int, Int> {
        val scan = scanTwo(text, start, ',')
        return scan.first to scan.second
    }

    /** A two-value `sscanf` with a literal separator, and its conversion count. */
    private class PairScan(val first: Int, val second: Int, val conversions: Int)

    private fun scanTwo(text: String, start: Int, separator: Char): PairScan {
        val first = scanIntEnd(text, start)
            ?: return PairScan(-1, -1, 0)
        if (first.end >= text.length || text[first.end] != separator) {
            return PairScan(first.value, -1, 1)
        }
        val second = scanIntEnd(text, first.end + 1)
            ?: return PairScan(first.value, -1, 1)
        return PairScan(first.value, second.value, 2)
    }

    /** `sscanf("%d")` on the rest: a value, or null when no conversion. */
    private fun scanInt(text: String, start: Int): Int? =
        scanIntEnd(text, start)?.value

    private class Scanned(val value: Int, val end: Int)

    /** One `%d`: white space, an optional sign, then decimal digits. */
    private fun scanIntEnd(text: String, start: Int): Scanned? {
        var i = start
        while (i < text.length && isAsciiSpace(text[i])) i++
        var sign = 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            if (text[i] == '-') sign = -1
            i++
        }
        val digitStart = i
        var value = 0L
        while (i < text.length && text[i] in '0'..'9') {
            if (value <= Int.MAX_VALUE) {
                value = value * 10 + (text[i] - '0')
                if (value > Int.MAX_VALUE) {
                    value = Int.MAX_VALUE.toLong() + 1
                }
            }
            i++
        }
        if (i == digitStart) return null
        val signed = sign.toLong() * value
        return Scanned(
            when {
                signed > Int.MAX_VALUE -> Int.MAX_VALUE
                signed < Int.MIN_VALUE -> Int.MIN_VALUE
                else -> signed.toInt()
            },
            i,
        )
    }

    /** C `atoi`: white space, an optional sign, digits; 0 without a digit. */
    private fun atoi(text: String, start: Int): Int =
        scanIntEnd(text, start)?.value ?: 0

    /** `isspace` in the C locale. */
    private fun isAsciiSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' ||
            c == '\u000C' || c == '\r'
}
