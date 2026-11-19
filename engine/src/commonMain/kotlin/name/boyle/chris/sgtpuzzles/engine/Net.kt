package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.math.pow

/**
 * A port of upstream `net.c`: rotate wires until one network is powered
 * by the source tile and no loop remains.
 *
 * The description, the auxiliary answer, and the save text are part of
 * the contract. Every Android preset asks for a unique solution, so
 * `net_solver` gates every generated description, and its FIFO work
 * list, `deadendmax` array, and `equiv` order decide the text. The
 * generator therefore ports the C deduction engine line by line.
 *
 * `new_game` never sets `completed`, so a solved description loads open.
 * `solve_game` reads the first state in C, so [Shared] keeps the decoded
 * initial board and [solve] runs `AUXINFO` and the solver on that board.
 *
 * The Jumble move draws from a second random state that C creates from
 * the frontend seed (`net.c:2074-2075`), not from the game seed. The
 * seam passes the platform seed to [newUi], and [decodeUi] keeps that
 * state while it restores the saved origin and source.
 *
 * C defines three undefined behaviours here. A NaN barrier rate reaches
 * an `int` cast (`net.c:1533`); the port rejects it in [validateParams]
 * (D-010). An `AUXINFO` that is not a rotation of the description trips
 * an assertion (`net.c:1862`); the port returns [SolveAttempt.move]
 * null with an error (D-011). A short `AUXINFO` is read past its end;
 * the port treats a missing character as zero.
 *
 * D-028: [newDesc] refuses an area above [MAX_AREA] (100x100) before
 * the first draw. Host generation at 100x100 is 0.87 s and the measured
 * phone extrapolation is 3.5 s; 125x125 is 10.1 s on the phone.
 */
object Net : Game {

    private const val PREFERRED_TILE_SIZE = 32
    private const val WINDOW_OFFSET = 4

    /** D-028: the measured generation cap, 100x100. */
    internal const val MAX_AREA = 10_000

    // Direction and other bitfields.
    private const val R = 0x01
    private const val U = 0x02
    private const val L = 0x04
    private const val D = 0x08
    private const val LOCKED = 0x10
    private const val ACTIVE = 0x20

    // Palette.
    private const val COL_BACKGROUND = 0
    private const val COL_LOCKED = 1
    private const val COL_BORDER = 2
    private const val COL_WIRE = 3
    private const val COL_ENDPOINT = 4
    private const val COL_POWERED = 5
    private const val COL_BARRIER = 6
    private const val COL_ERR = 7
    private const val NCOLOURS = 8

    // The packed draw word of `net.c:2682-2694`.
    private const val TILE_BARRIER_SHIFT = 0
    private const val TILE_BARRIER_CORNER_SHIFT = 4

    /** The four error bits of a tile, `err(dir)` in `net.c`. */
    private const val ERR_BITS = 0x3C0
    private const val TILE_KEYBOARD_CURSOR = 1 shl 8
    private const val TILE_WIRE_SHIFT = 9
    private const val TILE_ENDPOINT_SHIFT = 17
    private const val TILE_WIRE_ON_EDGE_SHIFT = 19
    private const val TILE_ROTATING = 1 shl 27
    private const val TILE_LOCKED = 1 shl 28

    private fun err(dir: Int): Int = dir shl 6

    private fun dx(dir: Int): Int = when (dir) {
        R -> +1
        L -> -1
        else -> 0
    }

    private fun dy(dir: Int): Int = when (dir) {
        D -> +1
        U -> -1
        else -> 0
    }

    private fun a(x: Int): Int = ((x and 0x07) shl 1) or ((x and 0x08) ushr 3)

    private fun c(x: Int): Int = ((x and 0x0E) ushr 1) or ((x and 0x01) shl 3)

    private fun f(x: Int): Int = ((x and 0x0C) ushr 2) or ((x and 0x03) shl 2)

    private fun rot(x: Int, n: Int): Int = when (n and 3) {
        0 -> x
        1 -> a(x)
        2 -> f(x)
        else -> c(x)
    }

    private fun countBits(x: Int): Int =
        ((x and 0x08) ushr 3) + ((x and 0x04) ushr 2) +
            ((x and 0x02) ushr 1) + (x and 0x01)

    private fun offsetX(x: Int, dir: Int, width: Int): Int = (x + width + dx(dir)) % width

    private fun offsetY(y: Int, dir: Int, height: Int): Int = (y + height + dy(dir)) % height

    private fun lineThick(tileSize: Int): Int = (tileSize + 47) / 48

    /** `struct game_params`. */
    data class Params(
        val width: Int,
        val height: Int,
        val wrapping: Boolean,
        val unique: Boolean,
        val barrierProbability: Float,
    ) : Game.Params {
        override fun copy(): Game.Params =
            Params(width, height, wrapping, unique, barrierProbability)
    }

    /** One `(x, y, direction)` key of the generator and the solver. */
    private data class Xyd(val x: Int, val y: Int, val direction: Int)

    private val XYD_COMPARATOR = Comparator<Xyd> { first, second ->
        when {
            first.x < second.x -> -1
            first.x > second.x -> +1
            first.y < second.y -> -1
            first.y > second.y -> +1
            first.direction < second.direction -> -1
            first.direction > second.direction -> +1
            else -> 0
        }
    }

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    override val name: String get() = "Net"
    override val backendName: String get() = "net"
    override val preferredTileSize: Int get() = PREFERRED_TILE_SIZE
    override val canFormatAsText: Boolean get() = false
    override val canSolve: Boolean get() = true
    override val wantsStatusBar: Boolean get() = true

    override fun defaultParams(): Game.Params =
        Params(5, 5, wrapping = false, unique = true, barrierProbability = 0.0f)

    /**
     * `net_presets` with `SMALL_SCREEN`, as the Android build and the
     * oracle define it. Every preset is unique and has no barriers.
     */
    private val presetValues: List<Preset> = listOf(
        Preset("5x5", "5x5"),
        Preset("7x7", "7x7"),
        Preset("9x9", "9x9"),
        Preset("11x11", "11x11"),
        Preset("5x5 wrapping", "5x5w"),
        Preset("7x7 wrapping", "7x7w"),
        Preset("9x9 wrapping", "9x9w"),
        Preset("11x11 wrapping", "11x11w"),
    )

    override fun presets(): List<Preset> = presetValues

    /**
     * `decode_params`. The width and height use `atoi`, and the digit
     * scan after each does not step over a sign, so `+5x5` is 5x5 and
     * `-5x5` is -5x-5. The barrier rate uses `strtod` and then skips
     * only `.` and digits. Unknown characters are skipped one by one,
     * so an `a` anywhere clears `unique` and a `w` anywhere enables
     * `wrapping`.
     */
    override fun decodeParams(text: String): Game.Params {
        var p = 0
        var width = atoi(text, p)
        while (p < text.length && text[p] in '0'..'9') p++
        var height: Int
        if (p < text.length && text[p] == 'x') {
            p++
            height = atoi(text, p)
            while (p < text.length && text[p] in '0'..'9') p++
        } else {
            height = width
        }

        var wrapping = false
        var unique = true
        var barrierProbability = 0.0f
        while (p < text.length) {
            when (text[p]) {
                'w' -> {
                    p++
                    wrapping = true
                }

                'b' -> {
                    p++
                    barrierProbability = strtod(text, p).toFloat()
                    while (p < text.length && (text[p] == '.' || text[p] in '0'..'9')) p++
                }

                'a' -> {
                    p++
                    unique = false
                }

                else -> p++
            }
        }
        return Params(width, height, wrapping, unique, barrierProbability)
    }

    /**
     * `encode_params`. The short form drops the barrier rate and the
     * uniqueness flag, so the game id of a `5x5b0.5a` board is `5x5`.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        val out = StringBuilder()
        out.append(p.width).append('x').append(p.height)
        if (p.wrapping) out.append('w')
        if (full && p.barrierProbability != 0.0f) {
            out.append('b').append(cFloatG(p.barrierProbability))
        }
        if (full && !p.unique) out.append('a')
        return out.toString()
    }

    /**
     * `validate_params`, in the C order. The area test stays
     * `width > INT_MAX / height`. A NaN barrier rate passes both C
     * range tests and later aborts on an `int` cast, so this port
     * rejects it here. That rejection needs a deviation entry.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.width <= 0 || p.height <= 0) {
            return "Width and height must both be greater than zero"
        }
        if (p.width <= 1 && p.height <= 1) {
            return "At least one of width and height must be greater than one"
        }
        if (p.width > Int.MAX_VALUE / p.height) {
            return "Width times height must not be unreasonably large"
        }
        if (p.barrierProbability.isNaN()) {
            return "Barrier probability must be a number"
        }
        if (p.barrierProbability < 0.0f) {
            return "Barrier probability may not be negative"
        }
        if (p.barrierProbability > 1.0f) {
            return "Barrier probability may not be greater than 1"
        }
        if (full && p.unique && p.wrapping && (p.width == 2 || p.height == 2)) {
            return "No wrapping puzzle with a width or height of 2 can have a unique solution"
        }
        return null
    }

    /** `game_configure` (`net.c:271-305`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Bool("Walls wrap around", p.wrapping),
            ConfigItem.Text("Barrier probability", cFloatG(p.barrierProbability)),
            ConfigItem.Bool("Ensure unique solution", p.unique),
        )
    }

    /** `custom_params` (`net.c:307-318`), with `atof` for the barrier. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            atoi((items[0] as ConfigItem.Text).value, 0),
            atoi((items[1] as ConfigItem.Text).value, 0),
            (items[2] as ConfigItem.Bool).value,
            (items[4] as ConfigItem.Bool).value,
            cAtof((items[3] as ConfigItem.Text).value),
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`net.c:2129-2145`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Bool(
                "Highlight loops involving unlocked squares",
                true,
                "unlocked-loops",
            ),
        )

    /** `set_prefs` (`net.c:2147-2150`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        (ui as Ui).unlockedLoops = prefs.bool("unlocked-loops")
    }

    // ------------------------------------------------------------------
    // Description.
    // ------------------------------------------------------------------

    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        var index = 0
        for (cell in 0 until p.width * p.height) {
            if (index >= desc.length) return "Game description shorter than expected"
            val ch = desc[index]
            if (!isHexDigit(ch)) return "Invalid character in game description"
            index++
            while (index < desc.length && (desc[index] == 'h' || desc[index] == 'v')) index++
        }
        if (index != desc.length) return "Game description longer than expected"
        return null
    }

    /**
     * `new_game_desc`.
     *
     * The `begin_generation` label is the outer loop: a failed
     * uniqueness attempt restarts the whole grid without rewinding the
     * random stream. The grid is built on a frontier of possible links,
     * gated on [netSolver] and repaired by [perturb], then shuffled
     * with a loop-repair pass, and finally the barrier candidates are
     * chosen from a shrinking tree. `AUXINFO` is the unshuffled grid,
     * taken before the shuffle, so it does not change when only the
     * barrier rate changes.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description =
        newDesc(params, random, Cancellation.NEVER)

    override fun newDesc(
        params: Game.Params,
        random: RandomState,
        cancel: Cancellation,
    ): Description {
        val p = params as Params
        require(p.width.toLong() * p.height <= MAX_AREA) {
            Game.AREA_TOO_LARGE
        }
        val w = p.width
        val h = p.height
        val cx = w / 2
        val cy = h / 2

        val tiles = IntArray(w * h)
        val barriers = IntArray(w * h)

        while (true) {
            cancel.check()
            tiles.fill(0)
            barriers.fill(0)

            /*
             * Construct the unshuffled grid as a spanning tree without
             * full crosses.
             */
            val possibilities = Tree234.sorted(XYD_COMPARATOR)
            if (cx + 1 < w) possibilities.add(Xyd(cx, cy, R))
            if (cy - 1 >= 0) possibilities.add(Xyd(cx, cy, U))
            if (cx - 1 >= 0) possibilities.add(Xyd(cx, cy, L))
            if (cy + 1 < h) possibilities.add(Xyd(cx, cy, D))

            while (possibilities.count > 0) {
                val index = random.upto(possibilities.count.toLong()).toInt()
                val xyd = possibilities.deletePos(index)!!
                val x1 = xyd.x
                val y1 = xyd.y
                val d1 = xyd.direction

                val x2 = offsetX(x1, d1, w)
                val y2 = offsetY(y1, d1, h)
                val d2 = f(d1)

                tiles[y1 * w + x1] = tiles[y1 * w + x1] or d1
                check(tiles[y2 * w + x2] == 0) { "net: the frontier entered a used tile" }
                tiles[y2 * w + x2] = tiles[y2 * w + x2] or d2

                if (countBits(tiles[y1 * w + x1]) == 3) {
                    findAndDelete(
                        possibilities,
                        Xyd(x1, y1, 0x0F xor tiles[y1 * w + x1]),
                    )
                }

                for (d in intArrayOf(R, U, L, D)) {
                    val x3 = offsetX(x2, d, w)
                    val y3 = offsetY(y2, d, h)
                    findAndDelete(possibilities, Xyd(x3, y3, f(d)))
                }

                for (d in intArrayOf(R, U, L, D)) {
                    if (d == d2) continue
                    if (!p.wrapping) {
                        if (d == U && y2 == 0) continue
                        if (d == D && y2 == h - 1) continue
                        if (d == L && x2 == 0) continue
                        if (d == R && x2 == w - 1) continue
                    }
                    val x3 = offsetX(x2, d, w)
                    val y3 = offsetY(y2, d, h)
                    if (tiles[y3 * w + x3] != 0) continue
                    possibilities.add(Xyd(x2, y2, d))
                }
            }
            check(possibilities.count == 0) { "net: the frontier is not empty" }

            var restart = false
            if (p.unique) {
                var prevn = -1
                while (true) {
                    if (netSolver(w, h, tiles, null, p.wrapping) == 1) break

                    var n = 0
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            val here = y * w + x
                            if (x + 1 < w &&
                                ((tiles[here] xor tiles[here + 1]) and LOCKED) != 0
                            ) {
                                n++
                                if ((tiles[here] and LOCKED) != 0) {
                                    perturb(w, h, tiles, p.wrapping, random, x + 1, y, L)
                                } else {
                                    perturb(w, h, tiles, p.wrapping, random, x, y, R)
                                }
                            }
                            if (y + 1 < h &&
                                ((tiles[here] xor tiles[here + w]) and LOCKED) != 0
                            ) {
                                n++
                                if ((tiles[here] and LOCKED) != 0) {
                                    perturb(w, h, tiles, p.wrapping, random, x, y + 1, U)
                                } else {
                                    perturb(w, h, tiles, p.wrapping, random, x, y, D)
                                }
                            }
                        }
                    }

                    if (prevn != -1 && prevn <= n) {
                        restart = true
                        break
                    }
                    prevn = n
                }
                if (restart) continue
                for (i in tiles.indices) tiles[i] = tiles[i] and LOCKED.inv()
            }

            /*
             * The barrier candidates: every unconnected edge.
             */
            val barrierTree = Tree234.sorted(XYD_COMPARATOR)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val index = y * w + x
                    if ((tiles[index] and R) == 0 && (p.wrapping || x < w - 1)) {
                        barrierTree.add(Xyd(x, y, R))
                    }
                    if ((tiles[index] and D) == 0 && (p.wrapping || y < h - 1)) {
                        barrierTree.add(Xyd(x, y, D))
                    }
                }
            }

            /*
             * The auxiliary answer is the unshuffled grid, before any
             * barrier is chosen.
             */
            val aux = buildString(w * h) {
                for (index in 0 until w * h) {
                    append("0123456789abcdef"[tiles[index] and 0xF])
                }
            }

            /*
             * Shuffle, repair loops, and reshuffle when the result is
             * already solved.
             */
            shuffle@ while (true) {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val index = y * w + x
                        tiles[index] = rot(tiles[index], random.upto(4).toInt())
                    }
                }

                var prevLoops = w * h + 1
                while (true) {
                    val loops = computeLoopsInner(w, h, p.wrapping, tiles, null, true)
                    var thisLoops = 0
                    for (index in 0 until w * h) {
                        if (loops[index] != 0) {
                            tiles[index] = rot(tiles[index], random.upto(4).toInt())
                            thisLoops++
                        }
                    }
                    if (thisLoops > prevLoops) continue@shuffle
                    if (thisLoops == 0) break
                    prevLoops = thisLoops
                }

                var mismatches = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val index = y * w + x
                        if (x + 1 < w &&
                            ((rot(tiles[index], 2) xor tiles[index + 1]) and L) != 0
                        ) {
                            mismatches++
                        }
                        if (y + 1 < h &&
                            ((rot(tiles[index], 2) xor tiles[index + w]) and U) != 0
                        ) {
                            mismatches++
                        }
                    }
                }
                if (mismatches == 0) continue@shuffle
                break
            }

            /*
             * Choose barriers. The float multiply and the truncating
             * cast are C's.
             */
            var nbarriers = (p.barrierProbability * barrierTree.count).toInt()
            check(nbarriers in 0..barrierTree.count) { "net: bad barrier count" }
            while (nbarriers > 0) {
                val index = random.upto(barrierTree.count.toLong()).toInt()
                val xyd = barrierTree.deletePos(index)!!
                val x1 = xyd.x
                val y1 = xyd.y
                val d1 = xyd.direction
                val x2 = offsetX(x1, d1, w)
                val y2 = offsetY(y1, d1, h)
                barriers[y1 * w + x1] = barriers[y1 * w + x1] or d1
                barriers[y2 * w + x2] = barriers[y2 * w + x2] or f(d1)
                nbarriers--
            }

            val desc = buildString {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val index = y * w + x
                        append("0123456789abcdef"[tiles[index] and 0xF])
                        if ((p.wrapping || x < w - 1) &&
                            (barriers[index] and R) != 0
                        ) {
                            append('v')
                        }
                        if ((p.wrapping || y < h - 1) &&
                            (barriers[index] and D) != 0
                        ) {
                            append('h')
                        }
                    }
                }
            }
            return Description(desc, aux)
        }
    }

    /** `find234` then `del234`; a missing key is not an error. */
    private fun findAndDelete(tree: Tree234<Xyd>, key: Xyd) {
        val found = tree.find(key) ?: return
        tree.delete(found)
    }

    /**
     * `perturb`: trace the ambiguous area, shuffle its perimeter,
     * make one new link, cut the loop that link closes, and lock the
     * whole area. Every random draw is in C order.
     */
    private fun perturb(
        w: Int,
        h: Int,
        tiles: IntArray,
        wrapping: Boolean,
        random: RandomState,
        startx: Int,
        starty: Int,
        startd: Int,
    ) {
        val perimeter = ArrayList<Xyd>()

        var x = startx
        var y = starty
        var d = startd
        do {
            perimeter.add(Xyd(x, y, d))

            val d2 = a(d)
            var x2 = offsetX(x, d2, w)
            var y2 = offsetY(y, d2, h)
            if ((!wrapping && (abs(x2 - x) > 1 || abs(y2 - y) > 1)) ||
                (tiles[y2 * w + x2] and LOCKED) != 0
            ) {
                d = d2
            } else {
                x = x2
                y = y2
                x2 = offsetX(x, d, w)
                y2 = offsetY(y, d, h)
                if ((wrapping || (abs(x2 - x) <= 1 && abs(y2 - y) <= 1)) &&
                    (tiles[y2 * w + x2] and LOCKED) == 0
                ) {
                    x = x2
                    y = y2
                    d = c(d)
                }
            }
        } while (x != startx || y != starty || d != startd)

        val perim2 = perimeter.toMutableList()
        shuffleXyd(perim2, random)

        var linked = false
        for (item in perim2) {
            x = item.x
            y = item.y
            d = item.direction
            val x2 = offsetX(x, d, w)
            val y2 = offsetY(y, d, h)
            if (!wrapping && (abs(x2 - x) > 1 || abs(y2 - y) > 1)) continue
            if ((tiles[y * w + x] and d) != 0) continue
            if (((tiles[y * w + x] or d) and 15) == 15) continue
            if (((tiles[y2 * w + x2] or f(d)) and 15) == 15) continue

            tiles[y * w + x] = tiles[y * w + x] or d
            tiles[y2 * w + x2] = tiles[y2 * w + x2] or f(d)
            linked = true
            break
        }
        if (!linked) return

        val loop = arrayOf(ArrayList<Xyd>(), ArrayList<Xyd>())
        val loopPos = arrayOf(Xyd(x, y, d), Xyd(x, y, d))
        var finished = false
        while (!finished) {
            for (i in 0..1) {
                x = loopPos[i].x
                y = loopPos[i].y
                d = loopPos[i].direction
                val x2 = offsetX(x, d, w)
                val y2 = offsetY(y, d, h)

                val last = loop[i].lastOrNull()
                if (last != null && last.x == x2 && last.y == y2 &&
                    last.direction == f(d)
                ) {
                    loop[i].removeAt(loop[i].size - 1)
                } else {
                    loop[i].add(loopPos[i])
                }

                d = f(d)
                var found = false
                for (j in 0 until 4) {
                    d = if (i == 0) a(d) else c(d)
                    if ((tiles[y2 * w + x2] and d) != 0) {
                        loopPos[i] = Xyd(x2, y2, d)
                        found = true
                        break
                    }
                }
                check(found) { "net: perturb cannot continue the loop" }
                check(loop[i].isNotEmpty()) { "net: perturb found an empty loop" }

                val first = loop[i][0]
                if (loopPos[i].x == first.x &&
                    loopPos[i].y == first.y &&
                    loopPos[i].direction == first.direction
                ) {
                    val j = random.upto((loop[i].size - 1).toLong()).toInt() + 1
                    val cut = loop[i][j]
                    val cx = cut.x
                    val cy = cut.y
                    val cd = cut.direction
                    val x3 = offsetX(cx, cd, w)
                    val y3 = offsetY(cy, cd, h)
                    tiles[cy * w + cx] = tiles[cy * w + cx] and cd.inv()
                    tiles[y3 * w + x3] = tiles[y3 * w + x3] and f(cd).inv()
                    finished = true
                    break
                }
            }
        }

        /*
         * Sort the perimeter into columns and lock every cell between
         * an up edge and a down edge.
         */
        perimeter.sortWith(XYD_COMPARATOR)
        var fillx = -1
        var filly = -1
        for (i in 0..perimeter.size) {
            if (i == perimeter.size || perimeter[i].x > fillx) {
                if (fillx != -1) {
                    while (filly < h) {
                        tiles[filly * w + fillx] = tiles[filly * w + fillx] or LOCKED
                        filly++
                    }
                    fillx = -1
                    filly = -1
                }
                if (i == perimeter.size) break
                fillx = perimeter[i].x
                filly = 0
            }

            if (perimeter[i].direction == U) {
                fillx = perimeter[i].x
                filly = perimeter[i].y
            } else if (perimeter[i].direction == D) {
                check(fillx == perimeter[i].x && filly <= perimeter[i].y) {
                    "net: perturb perimeter is not in columns"
                }
                while (filly <= perimeter[i].y) {
                    tiles[filly * w + fillx] = tiles[filly * w + fillx] or LOCKED
                    filly++
                }
                fillx = -1
                filly = -1
            }
        }
    }

    /** Upstream `shuffle`: descending Fisher-Yates, one draw per element. */
    private fun shuffleXyd(values: MutableList<Xyd>, random: RandomState) {
        var i = values.size
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = values[i]
                values[i] = values[j]
                values[j] = tmp
            }
        }
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    /** The FIFO work list of `net.c:417-456`. */
    private class Todo(maxSize: Int) {
        private val marked = BooleanArray(maxSize)
        private val buffer = IntArray(maxSize + 1)
        private val bufferLength = maxSize + 1
        private var head = 0
        private var tail = 0

        fun add(index: Int) {
            if (marked[index]) return
            marked[index] = true
            buffer[tail++] = index
            if (tail == bufferLength) tail = 0
        }

        fun get(): Int {
            if (head == tail) return -1
            val result = buffer[head++]
            if (head == bufferLength) head = 0
            marked[result] = false
            return result
        }
    }

    /**
     * `net_solver`, the uniqueness gate and the fallback solve.
     *
     * The work list is a FIFO ring buffer. A different queue discipline,
     * a different `deadendmax` initialisation, or a different `equiv`
     * order changes which orientations survive and therefore which
     * tiles end up `LOCKED`. Every preset is unique, so that would
     * change every description.
     *
     * Returns -1 for an inconsistency, 0 for "not uniquely solved", and
     * +1 when every tile is determined. Mutates [tiles] in place.
     */
    private fun netSolver(
        w: Int,
        h: Int,
        tiles: IntArray,
        barriers: IntArray?,
        wrapping: Boolean,
    ): Int {
        val wh = w * h
        val tilestate = IntArray(wh * 4)
        var area = 0
        for (i in 0 until wh) {
            tilestate[i * 4] = tiles[i] and 0xF
            for (j in 1 until 4) {
                if (tilestate[i * 4 + j - 1] == 255 ||
                    a(tilestate[i * 4 + j - 1]) == tilestate[i * 4]
                ) {
                    tilestate[i * 4 + j] = 255
                } else {
                    tilestate[i * 4 + j] = a(tilestate[i * 4 + j - 1])
                }
            }
            if (tiles[i] != 0) area++
        }

        val edgeSize = (wh - 1) * 5 + 9
        val edgestate = IntArray(edgeSize)
        val deadends = IntArray(edgeSize) { area + 1 }
        val equivalence = Dsf.new(wh)

        if (!wrapping) {
            for (i in 0 until w) {
                edgestate[i * 5 + 2] = 2
                edgestate[((h - 1) * w + i) * 5 + 8] = 2
            }
            for (i in 0 until h) {
                edgestate[(i * w + w - 1) * 5 + 1] = 2
                edgestate[(i * w) * 5 + 4] = 2
            }
        }

        if (barriers != null) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var d = 1
                    while (d <= 8) {
                        if ((barriers[y * w + x] and d) != 0) {
                            val x2 = offsetX(x, d, w)
                            val y2 = offsetY(y, d, h)
                            edgestate[(y * w + x) * 5 + d] = 2
                            edgestate[(y2 * w + x2) * 5 + f(d)] = 2
                        }
                        d += d
                    }
                }
            }
        }

        val todo = Todo(wh)
        var doneSomething = true

        while (true) {
            var index = todo.get()
            if (index == -1) {
                if (!doneSomething) break
                for (i in 0 until wh) todo.add(i)
                doneSomething = false
                index = todo.get()
            }

            val y = index / w
            val x = index % w
            val cell = y * w + x
            val ourclass = equivalence.canonify(cell)
            val deadendmax = IntArray(9)

            var i = 0
            var j = 0
            while (i < 4 && tilestate[cell * 4 + i] != 255) {
                var valid = true
                var nondeadendsCount = 0
                val nondeadends = IntArray(4)
                var deadendtotal = 0
                val equiv = IntArray(5)
                var nequiv = 1
                equiv[0] = ourclass
                val value = tilestate[cell * 4 + i]

                var d = 1
                while (d <= 8) {
                    if ((edgestate[cell * 5 + d] == 1 && (value and d) == 0) ||
                        (edgestate[cell * 5 + d] == 2 && (value and d) != 0)
                    ) {
                        valid = false
                    }

                    if ((value and d) != 0) {
                        if (deadends[cell * 5 + d] <= area) {
                            deadendtotal += deadends[cell * 5 + d]
                        } else {
                            nondeadends[nondeadendsCount++] = d
                        }

                        if (edgestate[cell * 5 + d] == 0) {
                            val x2 = offsetX(x, d, w)
                            val y2 = offsetY(y, d, h)
                            val class2 = equivalence.canonify(y2 * w + x2)
                            var k = 0
                            while (k < nequiv) {
                                if (class2 == equiv[k]) break
                                k++
                            }
                            if (k == nequiv) {
                                equiv[nequiv++] = class2
                            } else {
                                valid = false
                            }
                        }
                    }
                    d += d
                }

                if (nondeadendsCount == 0) {
                    if (deadendtotal > 0 && deadendtotal + 1 < area) {
                        valid = false
                    }
                } else if (nondeadendsCount == 1) {
                    deadendtotal++
                    val direction = nondeadends[0]
                    if (deadendmax[direction] < deadendtotal) {
                        deadendmax[direction] = deadendtotal
                    }
                } else {
                    for (k in 0 until nondeadendsCount) {
                        deadendmax[nondeadends[k]] = area + 1
                    }
                }

                if (valid) tilestate[cell * 4 + j++] = value
                i++
            }

            if (j == 0) return -1

            if (j < i) {
                doneSomething = true
                while (j < 4) tilestate[cell * 4 + j++] = 255
            }

            /*
             * Look for edges that are open or closed in every surviving
             * orientation.
             */
            var all = 0xF
            var any = 0
            i = 0
            while (i < 4 && tilestate[cell * 4 + i] != 255) {
                all = all and tilestate[cell * 4 + i]
                any = any or tilestate[cell * 4 + i]
                i++
            }
            var d = 1
            while (d <= 8) {
                if (edgestate[cell * 5 + d] == 0) {
                    val x2 = offsetX(x, d, w)
                    val y2 = offsetY(y, d, h)
                    val d2 = f(d)
                    if ((all and d) != 0) {
                        edgestate[cell * 5 + d] = 1
                        edgestate[(y2 * w + x2) * 5 + d2] = 1
                        equivalence.merge(cell, y2 * w + x2)
                        doneSomething = true
                        todo.add(y2 * w + x2)
                    } else if ((any and d) == 0) {
                        edgestate[cell * 5 + d] = 2
                        edgestate[(y2 * w + x2) * 5 + d2] = 2
                        doneSomething = true
                        todo.add(y2 * w + x2)
                    }
                }
                d += d
            }

            /*
             * Lower the dead-end markers.
             */
            d = 1
            while (d <= 8) {
                val x2 = offsetX(x, d, w)
                val y2 = offsetY(y, d, h)
                val d2 = f(d)
                if (deadendmax[d] > 0 &&
                    deadends[(y2 * w + x2) * 5 + d2] > deadendmax[d]
                ) {
                    deadends[(y2 * w + x2) * 5 + d2] = deadendmax[d]
                    doneSomething = true
                    todo.add(y2 * w + x2)
                }
                d += d
            }
        }

        /*
         * Mark every completely determined tile as locked.
         */
        var result = 1
        for (index in 0 until wh) {
            if (tilestate[index * 4 + 1] == 255) {
                check(tilestate[index * 4] != 255) {
                    "net: the solver left an empty tile state"
                }
                tiles[index] = tilestate[index * 4] or LOCKED
            } else {
                tiles[index] = tiles[index] and LOCKED.inv()
                result = 0
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // State.
    // ------------------------------------------------------------------

    /**
     * The data shared by every state of one game: the immutable
     * barriers and the decoded initial board that `solve_game` reads as
     * `states[0]`. C refcounts this block; Kotlin lets the GC own it.
     */
    class Shared internal constructor(
        val width: Int,
        val height: Int,
        val wrapping: Boolean,
        val barriers: IntArray,
    ) {
        private var initialTiles: IntArray? = null

        internal fun capture(state: State) {
            initialTiles = state.tiles.copyOf()
        }

        internal fun initialTiles(): IntArray =
            checkNotNull(initialTiles) { "the initial board is not captured" }.copyOf()
    }

    /**
     * `struct game_state`. [tiles] is per state; [shared] holds the
     * barriers. `completed` is sticky along a forward branch, and
     * `usedSolve` records an `S` move. The three `lastRotate` fields
     * exist for C state equality; the seam has no animation.
     */
    class State internal constructor(
        val width: Int,
        val height: Int,
        val wrapping: Boolean,
        var completed: Boolean,
        var lastRotateX: Int,
        var lastRotateY: Int,
        var lastRotateDir: Int,
        var usedSolve: Boolean,
        val tiles: IntArray,
        val shared: Shared,
    ) : Game.State {

        fun tileAt(x: Int, y: Int): Int = tiles[y * width + x]

        fun barrierAt(x: Int, y: Int): Int = shared.barriers[y * width + x]

        override fun copy(): Game.State = State(
            width,
            height,
            wrapping,
            completed,
            lastRotateX,
            lastRotateY,
            lastRotateDir,
            usedSolve,
            tiles.copyOf(),
            shared,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                width == other.width &&
                height == other.height &&
                wrapping == other.wrapping &&
                completed == other.completed &&
                lastRotateX == other.lastRotateX &&
                lastRotateY == other.lastRotateY &&
                lastRotateDir == other.lastRotateDir &&
                usedSolve == other.usedSolve &&
                tiles.contentEquals(other.tiles) &&
                shared.barriers.contentEquals(other.shared.barriers)

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + wrapping.hashCode()
            result = 31 * result + completed.hashCode()
            result = 31 * result + lastRotateX
            result = 31 * result + lastRotateY
            result = 31 * result + lastRotateDir
            result = 31 * result + usedSolve.hashCode()
            result = 31 * result + tiles.contentHashCode()
            result = 31 * result + shared.barriers.contentHashCode()
            return result
        }
    }

    /**
     * `new_game`. The barriers are parsed from `v` and `h`, a
     * non-wrapping board gets its border barriers, and a wrapping
     * parameter is re-derived from the barriers so a fully barriered
     * border behaves as non-wrapping. `completed` stays false even for
     * an already solved description, as in C.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        require(p.width > 0 && p.height > 0) { "net: the board is empty" }
        require(p.width > 1 || p.height > 1) { "net: the board has one cell" }

        val w = p.width
        val h = p.height
        val tiles = IntArray(w * h)
        val barriers = IntArray(w * h)

        var index = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (index < desc.length) {
                    val ch = desc[index]
                    if (ch in '0'..'9') {
                        tiles[y * w + x] = ch - '0'
                    } else if (ch in 'a'..'f') {
                        tiles[y * w + x] = ch - 'a' + 10
                    } else if (ch in 'A'..'F') {
                        tiles[y * w + x] = ch - 'A' + 10
                    }
                    index++
                }
                while (index < desc.length &&
                    (desc[index] == 'h' || desc[index] == 'v')
                ) {
                    val d1 = if (desc[index] == 'v') R else D
                    val x2 = offsetX(x, d1, w)
                    val y2 = offsetY(y, d1, h)
                    barriers[y * w + x] = barriers[y * w + x] or d1
                    barriers[y2 * w + x2] = barriers[y2 * w + x2] or f(d1)
                    index++
                }
            }
        }

        var wrapping = p.wrapping
        if (!wrapping) {
            for (x in 0 until w) {
                barriers[0 * w + x] = barriers[0 * w + x] or U
                barriers[(h - 1) * w + x] = barriers[(h - 1) * w + x] or D
            }
            for (y in 0 until h) {
                barriers[y * w + 0] = barriers[y * w + 0] or L
                barriers[y * w + (w - 1)] = barriers[y * w + (w - 1)] or R
            }
        } else {
            wrapping = false
            for (x in 0 until w) {
                if ((barriers[0 * w + x] and U) == 0 ||
                    (barriers[(h - 1) * w + x] and D) == 0
                ) {
                    wrapping = true
                }
            }
            for (y in 0 until h) {
                if ((barriers[y * w + 0] and L) == 0 ||
                    (barriers[y * w + (w - 1)] and R) == 0
                ) {
                    wrapping = true
                }
            }
        }

        val shared = Shared(w, h, wrapping, barriers)
        val state = State(
            width = w,
            height = h,
            wrapping = wrapping,
            completed = false,
            lastRotateX = 0,
            lastRotateY = 0,
            lastRotateDir = 0,
            usedSolve = false,
            tiles = tiles,
            shared = shared,
        )
        shared.capture(state)
        return state
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val ret = from.copy() as State
        var index = 0
        var tx = -1
        var ty = -1
        val noanim: Boolean

        if (move.isNotEmpty() && (move[0] == 'J' || move[0] == 'S')) {
            if (move[0] == 'S') ret.usedSolve = true
            index = 1
            if (index < move.length && move[index] == ';') index++
            noanim = true
        } else {
            noanim = false
        }

        ret.lastRotateDir = 0
        ret.lastRotateX = 0
        ret.lastRotateY = 0

        while (index < move.length) {
            val op = move[index]
            val scanned = if (op == 'A' || op == 'C' || op == 'F' || op == 'L') {
                scanTwoInts(move, index + 1)
            } else {
                null
            }
            if (scanned == null) return null
            tx = scanned.first
            ty = scanned.second
            if (tx < 0 || tx >= from.width || ty < 0 || ty >= from.height) return null

            val cell = ty * from.width + tx
            val original = ret.tiles[cell]
            when (op) {
                'A' -> {
                    ret.tiles[cell] = a(original)
                    if (!noanim) ret.lastRotateDir = +1
                }

                'F' -> {
                    ret.tiles[cell] = f(original)
                    if (!noanim) ret.lastRotateDir = +2
                }

                'C' -> {
                    ret.tiles[cell] = c(original)
                    if (!noanim) ret.lastRotateDir = -1
                }

                else -> {
                    check(op == 'L')
                    ret.tiles[cell] = ret.tiles[cell] xor LOCKED
                }
            }

            index += 1 + scanned.consumed
            if (index < move.length && move[index] == ';') index++
        }

        if (!noanim) {
            if (tx == -1 || ty == -1) return null
            ret.lastRotateX = tx
            ret.lastRotateY = ty
        }

        /*
         * Completion is sticky and is computed only here, never in
         * newState. An all-blank board counts as complete.
         */
        var pos = 0
        while (pos < ret.tiles.size && (ret.tiles[pos] and 0xF) == 0) pos++
        var complete = true
        if (pos < ret.tiles.size) {
            val active = computeActive(ret, pos % ret.width, pos / ret.width)
            for (i in ret.tiles.indices) {
                if ((ret.tiles[i] and 0xF) != 0 && active[i] == 0) {
                    complete = false
                    break
                }
            }
        }
        if (complete) ret.completed = true
        return ret
    }

    /**
     * The result of `solve_game` with the C error text when the solver
     * finds no solution. The seam has no error channel, so [solve]
     * returns only the move.
     */
    data class SolveAttempt(val move: String?, val error: String?)

    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveAttempt(state, auxInfo).move

    /** `solve_game` with the C error string (`net.c:1787-1876`). */
    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome {
        val attempt = solveAttempt(state, auxInfo)
        return SolveOutcome(attempt.move, attempt.error)
    }

    /**
     * `solve_game`. C reads the first state as `orig` and the current
     * state as `currstate`; the seam passes only the current state, so
     * the initial board comes from [Shared]. With `AUXINFO` the answer
     * is applied to the initial board and every cell is marked locked.
     * Without it the solver runs on the initial board.
     *
     * C asserts when an `AUXINFO` low nibble is not a rotation of the
     * description cell. This port returns no move and a clear error
     * instead. A short `AUXINFO` is read as zero characters rather than
     * past its end.
     */
    fun solveAttempt(state: Game.State, auxInfo: String?): SolveAttempt {
        val current = state as State
        val shared = current.shared
        val w = shared.width
        val h = shared.height
        val wh = w * h
        val tiles = IntArray(wh)

        if (auxInfo == null) {
            shared.initialTiles().copyInto(tiles)
            val result = netSolver(w, h, tiles, shared.barriers, shared.wrapping)
            if (result < 0) {
                return SolveAttempt(null, "No solution exists for this puzzle")
            }
        } else {
            for (i in 0 until wh) {
                val ch = if (i < auxInfo.length) auxInfo[i] else '\u0000'
                tiles[i] = hexValueOrZero(ch)
                tiles[i] = tiles[i] or LOCKED
            }
        }

        val out = StringBuilder("S")
        for (i in 0 until wh) {
            val from = current.tiles[i]
            val to = tiles[i]
            val ft = from and 0xF
            val tt = to and 0xF
            val x = i % w
            val y = i / w

            if (from == to) continue

            if ((from and LOCKED) != 0) out.append(";L").append(x).append(',').append(y)

            when (tt) {
                a(ft) -> out.append(";A").append(x).append(',').append(y)
                c(ft) -> out.append(";C").append(x).append(',').append(y)
                f(ft) -> out.append(";F").append(x).append(',').append(y)
                ft -> Unit
                else -> return SolveAttempt(
                    null,
                    "The auxiliary answer is not a rotation of the description",
                )
            }

            if ((to and LOCKED) != 0) out.append(";L").append(x).append(',').append(y)
        }
        return SolveAttempt(out.toString(), null)
    }

    private class ScanResult(val first: Int, val second: Int, val consumed: Int)

    /**
     * `sscanf(text + start, "%d,%d%n", ...)`. `%d` skips leading white
     * space and accepts a sign; the comma is a literal and must follow
     * the first number exactly. Returns null when fewer than two
     * conversions succeed. [consumed] counts the characters of the two
     * numbers and the comma, which is what `%n` reports.
     */
    private fun scanTwoInts(text: String, start: Int): ScanResult? {
        val first = scanInt(text, start) ?: return null
        var index = first.second
        if (index >= text.length || text[index] != ',') return null
        index++
        val second = scanInt(text, index) ?: return null
        return ScanResult(first.first, second.first, second.second - start)
    }

    /** One `%d`: the clamped value and the index after its last digit. */
    private fun scanInt(text: String, start: Int): Pair<Int, Int>? {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var sign = 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            if (text[i] == '-') sign = -1
            i++
        }
        val digitsStart = i
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            if (!overflow) {
                value = value * 10 + (text[i] - '0')
                if (value > Int.MAX_VALUE) overflow = true
            }
            i++
        }
        if (i == digitsStart) return null
        if (overflow) {
            return (if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE) to i
        }
        return (sign * value.toInt()) to i
    }

    /**
     * `interpret_move`. A click is a press plus a release: the press
     * records the drag start and produces no move, and the release
     * rotates the tile when no quarter turn happened. A rotating drag
     * emits one move per quarter turn.
     *
     * A locked tile is inert for a rotation, but the keyboard branch
     * still makes the cursor visible before it returns, as C does.
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
        var tx = -1
        var ty = -1
        var dir = 0
        val shift = (button and Buttons.MOD_SHFT) != 0
        val ctrl = (button and Buttons.MOD_CTRL) != 0
        var key = Buttons.stripModifiers(button)
        var nullret: MoveResult = MoveResult.Unused
        var action = Action.NONE
        val lt = lineThick(tileSize)

        if (key == Buttons.LEFT_BUTTON ||
            key == Buttons.MIDDLE_BUTTON ||
            key == Buttons.LEFT_DRAG ||
            key == Buttons.LEFT_RELEASE ||
            key == Buttons.RIGHT_DRAG ||
            key == Buttons.RIGHT_RELEASE ||
            key == Buttons.RIGHT_BUTTON
        ) {
            if (u.curVisible) {
                u.curVisible = false
                nullret = MoveResult.UiUpdate
            }

            var px = x - (WINDOW_OFFSET + lt)
            var py = y - (WINDOW_OFFSET + lt)
            tx = px / tileSize
            ty = py / tileSize
            if (px < 0 || py < 0 || tx >= s.width || ty >= s.height) {
                if (Buttons.isMouseDown(key)) {
                    u.dragStartX = -1
                    u.dragStartY = -1
                    u.dragTileX = -1
                    u.dragTileY = -1
                    return nullret
                }
                /*
                 * A drag or a release that moved off the grid still
                 * manipulates the tile the gesture started on.
                 */
            }
            tx = (tx + u.orgX) % s.width
            ty = (ty + u.orgY) % s.height
            if (px % tileSize >= tileSize - lt || py % tileSize >= tileSize - lt) {
                return nullret
            }

            if (key == Buttons.MIDDLE_BUTTON || key == Buttons.RIGHT_BUTTON) {
                action = Action.TOGGLE_LOCK
            } else if (key == Buttons.LEFT_BUTTON) {
                u.dragTileX = tx
                u.dragTileY = ty
                u.dragStartX = px % tileSize
                u.dragStartY = py % tileSize
                u.dragged = false
                return nullret
            } else if (key == Buttons.LEFT_DRAG) {
                if (u.dragTileX < 0) return nullret

                tx = u.dragTileX
                ty = u.dragTileY
                val mx = px - u.dragTileX * tileSize
                val my = py - u.dragTileY * tileSize

                val x0 = u.dragStartX
                val y0 = u.dragStartY
                val xA = u.dragStartY
                val yA = tileSize - 1 - u.dragStartX
                val xF = tileSize - 1 - u.dragStartX
                val yF = tileSize - 1 - u.dragStartY
                val xC = tileSize - 1 - u.dragStartY
                val yC = u.dragStartX

                val d0 = (mx - x0) * (mx - x0) + (my - y0) * (my - y0)
                val dA = (mx - xA) * (mx - xA) + (my - yA) * (my - yA)
                val dF = (mx - xF) * (mx - xF) + (my - yF) * (my - yF)
                val dC = (mx - xC) * (mx - xC) + (my - yC) * (my - yC)
                val dmin = minOf(minOf(d0, dA), minOf(dF, dC))

                if (d0 == dmin) {
                    return nullret
                } else if (dF == dmin) {
                    action = Action.ROTATE_180
                    u.dragStartX = xF
                    u.dragStartY = yF
                    u.dragged = true
                } else if (dA == dmin) {
                    action = Action.ROTATE_LEFT
                    u.dragStartX = xA
                    u.dragStartY = yA
                    u.dragged = true
                } else {
                    action = Action.ROTATE_RIGHT
                    u.dragStartX = xC
                    u.dragStartY = yC
                    u.dragged = true
                }
            } else if (key == Buttons.LEFT_RELEASE) {
                if (!u.dragged && u.dragTileX >= 0) {
                    tx = u.dragTileX
                    ty = u.dragTileY
                    action = Action.ROTATE_LEFT
                } else {
                    return nullret
                }
            }
        } else if (Buttons.isCursorMove(key)) {
            dir = when (key) {
                Buttons.CURSOR_UP -> U
                Buttons.CURSOR_DOWN -> D
                Buttons.CURSOR_LEFT -> L
                Buttons.CURSOR_RIGHT -> R
                else -> return nullret
            }
            action = when {
                shift && ctrl -> Action.MOVE_ORIGIN_AND_SOURCE
                shift -> Action.MOVE_ORIGIN
                ctrl -> Action.MOVE_SOURCE
                else -> Action.MOVE_CURSOR
            }
        } else if (key == 'a'.code || key == 's'.code || key == 'd'.code ||
            key == 'A'.code || key == 'S'.code || key == 'D'.code ||
            key == 'f'.code || key == 'F'.code ||
            key == Buttons.CURSOR_SELECT || key == Buttons.CURSOR_SELECT2
        ) {
            tx = u.curX
            ty = u.curY
            action = when (key) {
                'a'.code, 'A'.code, Buttons.CURSOR_SELECT -> Action.ROTATE_LEFT
                's'.code, 'S'.code, Buttons.CURSOR_SELECT2 -> Action.TOGGLE_LOCK
                'd'.code, 'D'.code -> Action.ROTATE_RIGHT
                else -> Action.ROTATE_180
            }
            u.curVisible = true
        } else if (key == 'j'.code || key == 'J'.code) {
            action = Action.JUMBLE
        } else {
            return nullret
        }

        when (action) {
            Action.TOGGLE_LOCK -> return MoveResult.Move("L$tx,$ty")

            Action.ROTATE_LEFT, Action.ROTATE_RIGHT, Action.ROTATE_180 -> {
                if ((s.tileAt(tx, ty) and LOCKED) != 0) return nullret
                val op = when (action) {
                    Action.ROTATE_LEFT -> 'A'
                    Action.ROTATE_RIGHT -> 'C'
                    else -> 'F'
                }
                return MoveResult.Move("$op$tx,$ty")
            }

            Action.JUMBLE -> {
                val out = StringBuilder("J")
                for (jy in 0 until s.height) {
                    for (jx in 0 until s.width) {
                        if ((s.tileAt(jx, jy) and LOCKED) == 0) {
                            val rotation = u.rs.upto(4).toInt()
                            if (rotation != 0) {
                                out.append(';')
                                    .append("AFC"[rotation - 1])
                                    .append(jx).append(',').append(jy)
                            }
                        }
                    }
                }
                return MoveResult.Move(out.toString())
            }

            Action.MOVE_ORIGIN, Action.MOVE_SOURCE,
            Action.MOVE_ORIGIN_AND_SOURCE, Action.MOVE_CURSOR,
            -> {
                check(dir != 0)
                if (action == Action.MOVE_ORIGIN ||
                    action == Action.MOVE_ORIGIN_AND_SOURCE
                ) {
                    if (s.wrapping) {
                        u.orgX = offsetX(u.orgX, dir, s.width)
                        u.orgY = offsetY(u.orgY, dir, s.height)
                    } else {
                        return nullret
                    }
                }
                if (action == Action.MOVE_SOURCE ||
                    action == Action.MOVE_ORIGIN_AND_SOURCE
                ) {
                    u.cx = offsetX(u.cx, dir, s.width)
                    u.cy = offsetY(u.cy, dir, s.height)
                }
                if (action == Action.MOVE_CURSOR) {
                    u.curX = offsetX(u.curX, dir, s.width)
                    u.curY = offsetY(u.curY, dir, s.height)
                    u.curVisible = true
                }
                return MoveResult.UiUpdate
            }

            Action.NONE -> return MoveResult.Unused
        }
    }

    private enum class Action {
        NONE,
        ROTATE_LEFT,
        ROTATE_180,
        ROTATE_RIGHT,
        TOGGLE_LOCK,
        JUMBLE,
        MOVE_ORIGIN,
        MOVE_SOURCE,
        MOVE_ORIGIN_AND_SOURCE,
        MOVE_CURSOR,
    }

    // ------------------------------------------------------------------
    // Board utilities.
    // ------------------------------------------------------------------

    /**
     * `compute_active`: the cells reachable from the source through
     * connected wires without a barrier. C uses a sorted tree as the
     * work list; the result is the same set of marked cells.
     */
    internal fun computeActive(state: State, cx: Int, cy: Int): IntArray {
        val w = state.width
        val h = state.height
        val active = IntArray(w * h)
        val todo = Tree234.sorted(XYD_COMPARATOR)

        check(cx in 0 until w && cy in 0 until h) { "net: source is outside the board" }
        active[cy * w + cx] = ACTIVE
        todo.add(Xyd(cx, cy, 0))

        while (true) {
            val xyd = todo.deletePos(0) ?: break
            val x1 = xyd.x
            val y1 = xyd.y
            for (d1 in intArrayOf(R, U, L, D)) {
                val x2 = offsetX(x1, d1, w)
                val y2 = offsetY(y1, d1, h)
                val d2 = f(d1)
                if ((state.tiles[y1 * w + x1] and d1) != 0 &&
                    (state.tiles[y2 * w + x2] and d2) != 0 &&
                    (state.shared.barriers[y1 * w + x1] and d1) == 0 &&
                    active[y2 * w + x2] == 0
                ) {
                    active[y2 * w + x2] = ACTIVE
                    todo.add(Xyd(x2, y2, 0))
                }
            }
        }
        check(todo.count == 0) { "net: the active queue is not empty" }
        return active
    }

    private fun computeLoopsInner(
        w: Int,
        h: Int,
        wrapping: Boolean,
        tiles: IntArray,
        barriers: IntArray?,
        includeUnlockedSquares: Boolean,
    ): IntArray {
        val fls = FindLoopState(w * h)
        val neighbours = IntArray(4)
        var neighbourCount = 0
        var neighbourIndex = 0

        fls.run { vertex ->
            if (vertex >= 0) {
                val x = vertex % w
                val y = vertex / w
                neighbourIndex = 0
                neighbourCount = 0
                var tile = tiles[vertex]
                if (barriers != null) tile = tile and barriers[vertex].inv()
                for (dir in intArrayOf(R, U, L, D)) {
                    if ((tile and dir) == 0) continue
                    val x1 = offsetX(x, dir, w)
                    val y1 = offsetY(y, dir, h)
                    val v1 = y1 * w + x1
                    if (!includeUnlockedSquares &&
                        (tile and tiles[v1] and LOCKED) == 0
                    ) {
                        continue
                    }
                    if ((tiles[v1] and f(dir)) != 0) {
                        neighbours[neighbourCount++] = v1
                    }
                }
            }
            if (neighbourIndex < neighbourCount) {
                neighbours[neighbourIndex++]
            } else {
                -1
            }
        }

        val loops = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = y * w + x
                var flags = 0
                for (dir in intArrayOf(R, U, L, D)) {
                    if ((tiles[v] and dir) != 0 &&
                        !(barriers != null && (barriers[y * w + x] and dir) != 0)
                    ) {
                        val x1 = offsetX(x, dir, w)
                        val y1 = offsetY(y, dir, h)
                        val v1 = y1 * w + x1
                        if (!includeUnlockedSquares &&
                            (tiles[v] and tiles[v1] and LOCKED) == 0
                        ) {
                            continue
                        }
                        if ((tiles[v1] and f(dir)) != 0 &&
                            fls.isLoopEdge(v, v1)
                        ) {
                            flags = flags or err(dir)
                        }
                    }
                }
                loops[v] = flags
            }
        }
        return loops
    }

    internal fun computeLoops(state: State, includeUnlockedSquares: Boolean): IntArray =
        computeLoopsInner(
            state.width,
            state.height,
            state.wrapping,
            state.tiles,
            state.shared.barriers,
            includeUnlockedSquares,
        )

    // ------------------------------------------------------------------
    // Status, keys, UI.
    // ------------------------------------------------------------------

    override fun status(state: Game.State): Int =
        if ((state as State).completed) +1 else 0

    /**
     * The status bar, built from the state and `ui.cx/cy`. The counter
     * is omitted when the source tile is blank, and when a completion
     * banner is shown and every cell is active. The trailing space
     * after a banner is part of the text.
     */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        val u = ui as Ui
        val out = StringBuilder()
        var complete = false

        if (s.usedSolve) {
            out.append("Auto-solved. ")
            complete = true
        } else if (s.completed) {
            out.append("COMPLETED! ")
            complete = true
        }

        if ((s.tileAt(u.cx, u.cy) and 0xF) != 0) {
            val active = computeActive(s, u.cx, u.cy)
            var a = 0
            var n2 = 0
            for (i in s.tiles.indices) {
                if (active[i] != 0) a++
                if ((s.tiles[i] and 0xF) != 0) n2++
            }
            if (!complete || a < n2) out.append("Active: ").append(a).append('/').append(n2)
        }
        return out.toString()
    }

    override fun textFormat(state: Game.State): String = ""

    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val next = newState as State
        return next.completed && !next.usedSolve && oldState != null &&
            !(oldState as State).completed
    }

    override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
        listOf(
            KeyLabel('J'.code, "Jumble"),
            KeyLabel('A'.code, "Anticlockwise", needsArrows = true),
            KeyLabel('S'.code, "Lock", needsArrows = true),
            KeyLabel('D'.code, "Clockwise", needsArrows = true),
            KeyLabel('F'.code, "Flip", needsArrows = true),
        ),
        ArrowMode.ARROWS_ONLY,
    )

    /** `current_key_label`; the seam has no operation for it yet. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String {
        val u = ui as Ui
        val s = state as State
        if ((s.tileAt(u.curX, u.curY) and LOCKED) != 0) {
            if (button == Buttons.CURSOR_SELECT2) return "Unlock"
        } else {
            if (button == Buttons.CURSOR_SELECT) return "Rotate"
            if (button == Buttons.CURSOR_SELECT2) return "Lock"
        }
        return ""
    }

    /**
     * `struct game_ui`: the display origin, the source tile, the
     * keyboard cursor, the drag state, the Jumble random state, and the
     * one preference. The random state is the only field that comes
     * from the platform seed.
     */
    class Ui(
        var orgX: Int = 0,
        var orgY: Int = 0,
        var cx: Int = 0,
        var cy: Int = 0,
        var curX: Int = 0,
        var curY: Int = 0,
        var curVisible: Boolean = false,
        var rs: RandomState = RandomState.fromSeed(byteArrayOf(0)),
        var dragTileX: Int = -1,
        var dragTileY: Int = -1,
        var dragStartX: Int = -1,
        var dragStartY: Int = -1,
        var dragged: Boolean = false,
        var unlockedLoops: Boolean = true,
    ) : Game.Ui {
        /** The base UI plus the four saved fields, for `decode_ui`. */
        internal fun copyBase(): Ui = Ui(
            orgX,
            orgY,
            cx,
            cy,
            curX,
            curY,
            curVisible,
            rs,
            dragTileX,
            dragTileY,
            dragStartX,
            dragStartY,
            dragged,
            unlockedLoops,
        )
    }

    /**
     * `new_ui`: the cursor and source start in the centre, the origin
     * is zero, the cursor is hidden, and the Jumble state comes from
     * the platform seed. C creates the random state even when the game
     * never jumbles.
     */
    override fun newUi(state: Game.State, platformSeed: ByteArray): Game.Ui {
        val s = state as State
        return Ui(
            orgX = 0,
            orgY = 0,
            cx = s.width / 2,
            cy = s.height / 2,
            curX = s.width / 2,
            curY = s.height / 2,
            curVisible = false,
            rs = RandomState.fromSeed(platformSeed),
            dragTileX = -1,
            dragTileY = -1,
            dragStartX = -1,
            dragStartY = -1,
            dragged = false,
            unlockedLoops = true,
        )
    }

    override fun newUi(state: Game.State): Game.Ui =
        newUi(state, byteArrayOf(0))

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    /** `encode_ui`: the origin and the source tile only. */
    override fun encodeUi(ui: Game.Ui): String? {
        val u = ui as Ui
        return "O${u.orgX},${u.orgY};C${u.cx},${u.cy}"
    }

    /**
     * `decode_ui`: parse four numbers with `sscanf` rules and apply
     * each pair only when it is in range. A partial match applies
     * nothing. The record cannot carry the random state, the cursor,
     * the drag, or the preference, so [base] supplies them.
     */
    override fun decodeUi(state: Game.State, text: String, base: Game.Ui): Game.Ui {
        val s = state as State
        val ui = (base as Ui).copyBase()
        val values = scanUiRecord(text) ?: return ui

        val orgX = values[0]
        val orgY = values[1]
        val cx = values[2]
        val cy = values[3]
        if (orgX in 0 until s.width && orgY in 0 until s.height) {
            ui.orgX = orgX
            ui.orgY = orgY
        }
        if (cx in 0 until s.width && cy in 0 until s.height) {
            ui.cx = cx
            ui.cy = cy
        }
        return ui
    }

    /** `sscanf(text, "O%d,%d;C%d,%d")`; null unless all four match. */
    private fun scanUiRecord(text: String): IntArray? {
        var index = 0
        if (index >= text.length || text[index] != 'O') return null
        index++
        val values = IntArray(4)
        for (i in 0 until 4) {
            val scanned = scanInt(text, index) ?: return null
            values[i] = scanned.first
            index = scanned.second
            when (i) {
                0, 2 -> {
                    if (index >= text.length || text[index] != ',') return null
                    index++
                }
                1 -> {
                    if (index >= text.length || text[index] != ';') return null
                    index++
                    if (index >= text.length || text[index] != 'C') return null
                    index++
                }
            }
        }
        return values
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        return Game.CursorRect(
            WINDOW_OFFSET + tileSize * u.curX,
            WINDOW_OFFSET + tileSize * u.curY,
            tileSize,
            tileSize,
        )
    }

    // ------------------------------------------------------------------
    // Geometry and palette.
    // ------------------------------------------------------------------

    /**
     * `game_compute_size`. The tile grid is `WINDOW_OFFSET` from the
     * board edge, and one line thickness is the spare pixel.
     */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val lt = lineThick(tileSize)
        return Size(
            WINDOW_OFFSET * 2 + tileSize * p.width + lt,
            WINDOW_OFFSET * 2 + tileSize * p.height + lt,
        )
    }

    /**
     * One unit per tile, as `WINDOW_OFFSET` and `game_redraw` place it.
     * A tile names its wires, its barriers, and its lock, and the two
     * pointer buttons of `interpret_move` are its actions: a left click
     * turns the tile anticlockwise and the right or middle button
     * toggles the lock. `current_key_label` calls those `Rotate` and
     * `Lock` or `Unlock`.
     *
     * The pointer also offers a drag that turns a tile one way or half a
     * turn; a unit action cannot express a drag and the keys already
     * carry it (`D` clockwise, `F` flip), so it stays on the key path.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.width
        val locked = { x: Int, y: Int -> s.tileAt(x, y) and LOCKED != 0 }
        val cells = gridSpeech(
            width = w,
            height = s.height,
            tileSize = tileSize,
            left = WINDOW_OFFSET,
            top = WINDOW_OFFSET,
            summary = "",
            text = { x, y ->
                val index = y * w + x
                val words = buildList {
                    val dirs = s.tiles[index] and 0x0F
                    if (dirs != 0) add("wires " + sideSpeech(sides(dirs)))
                    if (s.tiles[index] and ACTIVE != 0) add("powered")
                    if (s.tiles[index] and ERR_BITS != 0) add("wrong")
                    val barriers = s.barrierAt(x, y) and 0x0F
                    if (barriers != 0) add("barrier " + sideSpeech(sides(barriers)))
                    if (s.tiles[index] and LOCKED != 0) add("locked")
                }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                (if (words.isEmpty()) "empty" else words.joinToString(", ")) + cursor
            },
            actions = { x, y ->
                if (locked(x, y)) {
                    listOf(SpokenAction("Unlock", Buttons.RIGHT_BUTTON))
                } else {
                    listOf(
                        SpokenAction("Rotate", Buttons.LEFT_BUTTON),
                        SpokenAction("Lock", Buttons.RIGHT_BUTTON),
                    )
                }
            },
        )
        return cells.capped(cells.units.getOrNull(u.curY * w + u.curX))
    }

    /** The four sides of a direction mask, in the order they are drawn. */
    private fun sides(mask: Int): List<String> = buildList {
        if (mask and U != 0) add("up")
        if (mask and R != 0) add("right")
        if (mask and D != 0) add("down")
        if (mask and L != 0) add("left")
    }


    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]

        for (i in 0 until 3) {
            ret[COL_WIRE * 3 + i] = 0.0f
            ret[COL_POWERED * 3 + i] = if (i == 0) 0.0f else 1.0f
            ret[COL_BARRIER * 3 + i] = if (i == 0) 1.0f else 0.0f
            ret[COL_ERR * 3 + i] = ret[COL_BARRIER * 3 + i]
            ret[COL_ENDPOINT * 3 + i] = if (i == 2) 1.0f else 0.0f
            ret[COL_BORDER * 3 + i] = 0.5f * ret[COL_BACKGROUND * 3 + i]
            ret[COL_LOCKED * 3 + i] = 0.75f * ret[COL_BACKGROUND * 3 + i]
        }
        return ret
    }

    // ------------------------------------------------------------------
    // Drawing.
    // ------------------------------------------------------------------

    /** `game_drawstate`: the per-cell packed word cache. */
    class DrawState internal constructor(
        val width: Int,
        val height: Int,
        internal val tilesize: Int,
    ) : Game.DrawState {
        internal val visible = IntArray((width + 2) * (height + 2)) { -1 }
        internal val toDraw = IntArray((width + 2) * (height + 2))
    }

    override fun newDrawState(
        state: Game.State,
        tileSize: Int,
        sink: DrawSink,
    ): Game.DrawState {
        val s = state as State
        return DrawState(s.width, s.height, tileSize)
    }

    override fun freeDrawState(drawState: Game.DrawState, sink: DrawSink) {}

    private fun dsIndex(ds: DrawState, x: Int, y: Int): Int =
        (y + 1) * (ds.width + 2) + (x + 1)

    private fun visible(ds: DrawState, x: Int, y: Int): Int = ds.visible[dsIndex(ds, x, y)]

    private fun todraw(ds: DrawState, x: Int, y: Int): Int = ds.toDraw[dsIndex(ds, x, y)]

    private fun setTodraw(ds: DrawState, x: Int, y: Int, value: Int) {
        ds.toDraw[dsIndex(ds, x, y)] = value
    }

    private fun orTodraw(ds: DrawState, x: Int, y: Int, value: Int) {
        ds.toDraw[dsIndex(ds, x, y)] = ds.toDraw[dsIndex(ds, x, y)] or value
    }

    /**
     * `game_redraw`. The seam has no animation or flash time, so the
     * rotating tile and the flash frame are inert, exactly as a C
     * `midend_redraw` with `oldstate = NULL` and `t = ft = 0`.
     *
     * `active` and `loops` are whole-board, so one rotation can change
     * many cells. Every cell is compared with the previous word, and
     * only a changed cell is drawn.
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

        val active = computeActive(s, u.cx, u.cy)
        val loops = computeLoops(s, u.unlockedLoops)

        for (dy in -1..ds.height) {
            for (dx in -1..ds.width) setTodraw(ds, dx, dy, 0)
        }

        for (dy in 0 until ds.height) {
            val gy = (dy + u.orgY) % ds.height
            for (dx in 0 until ds.width) {
                val gx = (dx + u.orgX) % ds.width
                var t = s.tileAt(gx, gy) or
                    loops[gy * ds.width + gx] or
                    active[gy * ds.width + gx]

                var dsh = 0
                var d = 1
                while (d < 16) {
                    if ((s.barrierAt(gx, gy) and d) != 0) {
                        orTodraw(ds, dx, dy, d shl TILE_BARRIER_SHIFT)
                        orTodraw(ds, dx + dx(d), dy + dy(d), f(d) shl TILE_BARRIER_SHIFT)
                        orTodraw(
                            ds,
                            dx + dx(a(d)), dy + dy(a(d)),
                            c(d) shl TILE_BARRIER_CORNER_SHIFT,
                        )
                        orTodraw(
                            ds,
                            dx + dx(a(d)) + dx(d), dy + dy(a(d)) + dy(d),
                            f(d) shl TILE_BARRIER_CORNER_SHIFT,
                        )
                        orTodraw(
                            ds,
                            dx + dx(c(d)), dy + dy(c(d)),
                            d shl TILE_BARRIER_CORNER_SHIFT,
                        )
                        orTodraw(
                            ds,
                            dx + dx(c(d)) + dx(d), dy + dy(c(d)) + dy(d),
                            a(d) shl TILE_BARRIER_CORNER_SHIFT,
                        )
                    }

                    if ((t and d) != 0) {
                        if ((t and LOCKED) != 0) {
                            if ((s.barrierAt(gx, gy) and d) != 0) {
                                t = t or err(d)
                            } else {
                                val ox = offsetX(gx, d, s.width)
                                val oy = offsetY(gy, d, s.height)
                                val t2 = s.tileAt(ox, oy)
                                if ((t2 and LOCKED) != 0 && (t2 and f(d)) == 0) {
                                    t = t or err(d)
                                }
                            }
                        }

                        val edgeval = when {
                            (t and err(d)) != 0 -> 3
                            (t and ACTIVE) != 0 -> 2
                            else -> 1
                        }
                        orTodraw(ds, dx, dy, edgeval shl (TILE_WIRE_SHIFT + dsh * 2))
                        orTodraw(
                            ds,
                            dx + dx(d), dy + dy(d),
                            edgeval shl (TILE_WIRE_ON_EDGE_SHIFT + (dsh xor 2) * 2),
                        )
                    }
                    d += d
                    dsh++
                }

                if (u.curVisible && gx == u.curX && gy == u.curY) {
                    orTodraw(ds, dx, dy, TILE_KEYBOARD_CURSOR)
                }

                if (gx == u.cx && gy == u.cy) {
                    orTodraw(ds, dx, dy, 3 shl TILE_ENDPOINT_SHIFT)
                } else if ((t and 0xF) == R || (t and 0xF) == U ||
                    (t and 0xF) == L || (t and 0xF) == D
                ) {
                    if ((t and ACTIVE) != 0) {
                        orTodraw(ds, dx, dy, 2 shl TILE_ENDPOINT_SHIFT)
                    } else {
                        orTodraw(ds, dx, dy, 1 shl TILE_ENDPOINT_SHIFT)
                    }
                }

                if ((t and LOCKED) != 0) orTodraw(ds, dx, dy, TILE_LOCKED)
            }
        }

        for (dy in -1..ds.height) {
            for (dx in -1..ds.width) {
                val previous = visible(ds, dx, dy)
                val current = todraw(ds, dx, dy)
                if (previous != current ||
                    ((previous or current) and TILE_ROTATING) != 0
                ) {
                    drawTile(sink, ds, dx, dy, current)
                    ds.visible[dsIndex(ds, dx, dy)] = current
                }
            }
        }
    }

    /**
     * `draw_tile`. The clip, the four grid lines, the protrusions, the
     * three wire polygons, the float endpoint box, and the barriers are
     * emitted in C order, including zero-size rectangles.
     */
    private fun drawTile(
        sink: DrawSink,
        ds: DrawState,
        x: Int,
        y: Int,
        tile: Int,
    ) {
        val tileSize = ds.tilesize
        val lt = lineThick(tileSize)
        val br = lt / 2
        val tl = lt - br
        val bo = (lt + 1) / 2

        val tx = WINDOW_OFFSET + tileSize * x + br
        val ty = WINDOW_OFFSET + tileSize * y + br

        var clipx = tx
        var clipX = tx + tileSize
        var clipy = ty
        var clipY = ty + tileSize
        if (x == -1) {
            clipx = clipX - br - bo
        } else if (x == ds.width) {
            clipX = clipx + tl + bo
        }
        if (y == -1) {
            clipy = clipY - br - bo
        } else if (y == ds.height) {
            clipY = clipy + tl + bo
        }
        val clipw = clipX - clipx
        val cliph = clipY - clipy
        sink.clip(clipx, clipy, clipw, cliph)

        val bg = if ((tile and TILE_LOCKED) != 0) COL_LOCKED else COL_BACKGROUND
        sink.rect(clipx, clipy, clipw, cliph, bg)

        val gridl = if (x == -1) tx + tileSize - br else tx
        val gridr = if (x == ds.width) tx + tl else tx + tileSize
        val gridu = if (y == -1) ty + tileSize - br else ty
        val gridd = if (y == ds.height) ty + tl else ty + tileSize
        if (x >= 0) sink.rect(tx, gridu, tl, gridd - gridu, COL_BORDER)
        if (y >= 0) sink.rect(gridl, ty, gridr - gridl, tl, COL_BORDER)
        if (x < ds.width) {
            sink.rect(tx + tileSize - br, gridu, br, gridd - gridu, COL_BORDER)
        }
        if (y < ds.height) {
            sink.rect(gridl, ty + tileSize - br, gridr - gridl, br, COL_BORDER)
        }

        if ((tile and TILE_KEYBOARD_CURSOR) != 0) {
            val cursorCol = if ((tile and TILE_LOCKED) != 0) COL_BACKGROUND else COL_LOCKED
            val insetOuter = tileSize / 8
            val insetInner = insetOuter + lt
            sink.rect(
                tx + insetOuter, ty + insetOuter,
                tileSize - 2 * insetOuter, tileSize - 2 * insetOuter,
                cursorCol,
            )
            sink.rect(
                tx + insetInner, ty + insetInner,
                tileSize - 2 * insetInner, tileSize - 2 * insetInner,
                bg,
            )
        }

        var radius = (tileSize + 1) / 2
        val cx = tx + radius
        val cy = ty + radius
        radius++

        var dsh = 0
        var d = 1
        while (d < 16) {
            val edgetype = (tile ushr (TILE_WIRE_ON_EDGE_SHIFT + 2 * dsh)) and 3
            if (edgetype == 0) {
                d += d
                dsh++
                continue
            }
            if ((tile and TILE_ROTATING) == 0 &&
                ((tile ushr (TILE_WIRE_SHIFT + 2 * dsh)) and 3) != 0
            ) {
                d += d
                dsh++
                continue
            }

            for (pass in 0..1) {
                val col = when {
                    pass == 0 || edgetype == 1 -> COL_WIRE
                    edgetype == 2 -> COL_POWERED
                    else -> COL_ERR
                }
                val halfwidth = if (pass == 0) 2 * lt - 1 else lt - 1

                val rx: Int
                val rw: Int
                if (dx(d) < 0) {
                    rx = tx
                    rw = tl
                } else if (dx(d) > 0) {
                    rx = tx + tileSize - br
                    rw = br
                } else {
                    rx = cx - halfwidth
                    rw = 2 * halfwidth + 1
                }

                val ry: Int
                val rh: Int
                if (dy(d) < 0) {
                    ry = ty
                    rh = tl
                } else if (dy(d) > 0) {
                    ry = ty + tileSize - br
                    rh = br
                } else {
                    ry = cy - halfwidth
                    rh = 2 * halfwidth + 1
                }

                sink.rect(rx, ry, rw, rh, col)
            }
            d += d
            dsh++
        }

        /*
         * The seam never draws a rotated tile, so the matrix is the
         * identity. Keep the same float arithmetic as C for the points.
         */
        val m0 = 1.0f
        val m1 = 0.0f
        val m2 = 0.0f
        val m3 = m0

        drawWires(sink, cx, cy, radius, tile, 0xE, COL_WIRE, 2 * lt - 1, m0, m1, m2, m3)
        drawWires(sink, cx, cy, radius, tile, 0x4, COL_POWERED, lt - 1, m0, m1, m2, m3)
        drawWires(sink, cx, cy, radius, tile, 0x8, COL_ERR, lt - 1, m0, m1, m2, m3)

        for (pass in 0..1) {
            val endtype = (tile ushr TILE_ENDPOINT_SHIFT) and 3
            if (endtype != 0) {
                val boxr = tileSize * 0.24f + (if (pass == 0) lt - 1 else 0)
                val col = when {
                    pass == 0 || endtype == 3 -> COL_WIRE
                    endtype == 2 -> COL_POWERED
                    else -> COL_ENDPOINT
                }
                val px = intArrayOf(+1, +1, +1, -1, -1, -1, -1, +1)
                val points = IntArray(8)
                for (i in 0 until 8 step 2) {
                    val ix = boxr * px[i].toFloat()
                    val iy = boxr * px[i + 1].toFloat()
                    val xf = m0 * ix + m2 * iy + cx.toFloat()
                    val yf = m1 * ix + m3 * iy + cy.toFloat()
                    points[i] = (xf + 0.5f).toInt()
                    points[i + 1] = (yf + 0.5f).toInt()
                }
                sink.poly(points, col, COL_WIRE)
            }
        }

        for (pass in 0..1) {
            var btl = tl
            var bbr = br
            var col = COL_BARRIER
            if (pass == 0) {
                btl += bo
                bbr += bo
                col = COL_WIRE
            }

            if ((tile and (L shl TILE_BARRIER_SHIFT)) != 0) {
                sink.rect(tx, ty, btl, tileSize, col)
            }
            if ((tile and (R shl TILE_BARRIER_SHIFT)) != 0) {
                sink.rect(tx + tileSize - bbr, ty, bbr, tileSize, col)
            }
            if ((tile and (U shl TILE_BARRIER_SHIFT)) != 0) {
                sink.rect(tx, ty, tileSize, btl, col)
            }
            if ((tile and (D shl TILE_BARRIER_SHIFT)) != 0) {
                sink.rect(tx, ty + tileSize - bbr, tileSize, bbr, col)
            }

            if ((tile and (R shl TILE_BARRIER_CORNER_SHIFT)) != 0) {
                sink.rect(tx + tileSize - bbr, ty, bbr, btl, col)
            }
            if ((tile and (U shl TILE_BARRIER_CORNER_SHIFT)) != 0) {
                sink.rect(tx, ty, btl, btl, col)
            }
            if ((tile and (L shl TILE_BARRIER_CORNER_SHIFT)) != 0) {
                sink.rect(tx, ty + tileSize - bbr, btl, bbr, col)
            }
            if ((tile and (D shl TILE_BARRIER_CORNER_SHIFT)) != 0) {
                sink.rect(tx + tileSize - bbr, ty + tileSize - bbr, bbr, bbr, col)
            }
        }

        sink.unclip()
        sink.update(clipx, clipy, clipw, cliph)
    }

    /**
     * `draw_wires`: build the polygon for one of the three wire colours
     * and draw it only when that colour is present. The first point of
     * every direction is always added, as C does, even when no polygon
     * is drawn.
     */
    private fun drawWires(
        sink: DrawSink,
        cx: Int,
        cy: Int,
        radius: Int,
        tile: Int,
        bitmap: Int,
        colour: Int,
        halfwidth: Int,
        m0: Float,
        m1: Float,
        m2: Float,
        m3: Float,
    ) {
        val fpoints = FloatArray(24)
        var npoints = 0
        var anyWire = false

        var d = 1
        var dsh = 0
        while (d < 16) {
            val wiretype = (tile ushr (TILE_WIRE_SHIFT + 2 * dsh)) and 3

            fpoints[2 * npoints] = (halfwidth * (dx(d) + dx(c(d)))).toFloat()
            fpoints[2 * npoints + 1] = (halfwidth * (dy(d) + dy(c(d)))).toFloat()
            npoints++

            if ((bitmap and (1 shl wiretype)) != 0) {
                fpoints[2 * npoints] =
                    (radius * dx(d) + halfwidth * dx(c(d))).toFloat()
                fpoints[2 * npoints + 1] =
                    (radius * dy(d) + halfwidth * dy(c(d))).toFloat()
                npoints++
                fpoints[2 * npoints] =
                    (radius * dx(d) + halfwidth * dx(a(d))).toFloat()
                fpoints[2 * npoints + 1] =
                    (radius * dy(d) + halfwidth * dy(a(d))).toFloat()
                npoints++
                anyWire = true
            }
            d += d
            dsh++
        }

        if (!anyWire) return

        val points = IntArray(24)
        for (i in 0 until npoints) {
            val ix = fpoints[2 * i]
            val iy = fpoints[2 * i + 1]
            val xf = m0 * ix + m2 * iy + cx.toFloat()
            val yf = m1 * ix + m3 * iy + cy.toFloat()
            points[2 * i] = (xf + 0.5f).toInt()
            points[2 * i + 1] = (yf + 0.5f).toInt()
        }
        sink.poly(points.copyOf(npoints * 2), colour, colour)
    }

    // ------------------------------------------------------------------
    // Text helpers.
    // ------------------------------------------------------------------

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexValueOrZero(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    /** C `atoi`: white space, an optional sign, then digits; 0 if none. */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var sign = 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            if (text[i] == '-') sign = -1
            i++
        }
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            if (!overflow) {
                value = value * 10 + (text[i] - '0')
                if (value > Int.MAX_VALUE) overflow = true
            }
            i++
        }
        if (overflow) return if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE
        return sign * value.toInt()
    }

    /**
     * C `strtod` for the barrier rate: white space, a sign, the
     * `inf`/`infinity`/`nan` words, a decimal number, or a C99 hex
     * float such as `0x10`. The end pointer is not needed because the
     * caller rescans from the same start.
     */
    private fun strtod(text: String, start: Int): Double {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }

        val rest = text.substring(i).lowercase()
        when {
            rest.startsWith("infinity") -> {
                val value = Double.POSITIVE_INFINITY
                return if (negative) -value else value
            }

            rest.startsWith("inf") -> {
                val value = Double.POSITIVE_INFINITY
                return if (negative) -value else value
            }

            rest.startsWith("nan") -> {
                return Double.NaN
            }
        }

        if (i + 1 < text.length && text[i] == '0' &&
            (text[i + 1] == 'x' || text[i + 1] == 'X')
        ) {
            var k = i + 2
            var mantissa = 0.0
            var digits = 0
            while (k < text.length && hexDigit(text[k]) >= 0) {
                mantissa = mantissa * 16.0 + hexDigit(text[k])
                digits++
                k++
            }
            if (k < text.length && text[k] == '.') {
                k++
                var scale = 1.0 / 16.0
                while (k < text.length && hexDigit(text[k]) >= 0) {
                    mantissa += hexDigit(text[k]) * scale
                    scale /= 16.0
                    digits++
                    k++
                }
            }
            if (digits > 0) {
                if (k < text.length && (text[k] == 'p' || text[k] == 'P')) {
                    var e = k + 1
                    var exponentNegative = false
                    if (e < text.length && (text[e] == '+' || text[e] == '-')) {
                        exponentNegative = text[e] == '-'
                        e++
                    }
                    val exponentStart = e
                    var exponent = 0
                    while (e < text.length && text[e] in '0'..'9') {
                        exponent = exponent * 10 + (text[e] - '0')
                        e++
                    }
                    if (e > exponentStart) {
                        mantissa *= 2.0.pow(if (exponentNegative) -exponent else exponent)
                    }
                }
                return if (negative) -mantissa else mantissa
            }
        }

        var mantissa = 0.0
        var digits = 0
        var exponent = 0
        while (i < text.length && text[i] in '0'..'9') {
            mantissa = mantissa * 10.0 + (text[i] - '0')
            digits++
            i++
        }
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i] in '0'..'9') {
                mantissa = mantissa * 10.0 + (text[i] - '0')
                digits++
                exponent--
                i++
            }
        }
        if (digits == 0) return 0.0

        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            var k = i + 1
            var exponentNegative = false
            if (k < text.length && (text[k] == '+' || text[k] == '-')) {
                exponentNegative = text[k] == '-'
                k++
            }
            val exponentStart = k
            var exponentValue = 0
            while (k < text.length && text[k] in '0'..'9') {
                exponentValue = exponentValue * 10 + (text[k] - '0')
                k++
            }
            if (k > exponentStart) {
                exponent += if (exponentNegative) -exponentValue else exponentValue
                i = k
            }
        }

        var value = mantissa * 10.0.pow(exponent)
        if (negative) value = -value
        return value
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
