package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of the `slant.c` generator and solver.
 *
 * The generator is a direct translation: the same shuffle order, the
 * same disjoint-set rules, the same clue-removal passes, and the same
 * difficulty check. A difference here changes the description for a
 * seed, so `SlantDescTest` compares the result with the oracle.
 *
 * This file deliberately does not implement [Game] yet. The game seam,
 * drawing, and input are added with the rest of the port, and the game
 * stays on the C backend until the whole parity matrix is green.
 */
object SlantGeneration {

    const val EASY = 0
    const val HARD = 1

    data class Params(
        val width: Int,
        val height: Int,
        val difficulty: Int,
    ) : Game.Params {
        val fullText: String get() = encode(this, true)
        val shortText: String get() = encode(this, false)

        override fun copy(): Game.Params = Params(width, height, difficulty)
    }

    fun defaultParams(): Params = Params(8, 8, EASY)

    fun presets(): List<Preset> = listOf(
        Preset("5x5 Easy", encode(Params(5, 5, EASY), true)),
        Preset("5x5 Hard", encode(Params(5, 5, HARD), true)),
        Preset("8x8 Easy", encode(Params(8, 8, EASY), true)),
        Preset("8x8 Hard", encode(Params(8, 8, HARD), true)),
        Preset("12x10 Easy", encode(Params(12, 10, EASY), true)),
        Preset("12x10 Hard", encode(Params(12, 10, HARD), true)),
    )

    fun decode(text: String): Params {
        var position = 0
        fun number(): Int {
            val start = position
            while (position < text.length && text[position].isDigit()) position++
            return text.substring(start, position).toIntOrNull() ?: 0
        }

        val width = number()
        var height = width
        if (position < text.length && text[position] == 'x') {
            position++
            height = number()
        }
        var difficulty = EASY
        if (position < text.length && text[position] == 'd') {
            position++
            if (position < text.length) {
                when (text[position]) {
                    'e' -> difficulty = EASY
                    'h' -> difficulty = HARD
                }
                position++
            }
        }
        return Params(width, height, difficulty)
    }

    fun encode(params: Params, full: Boolean): String {
        val base = "${params.width}x${params.height}"
        return if (full) base + "d" + if (params.difficulty == HARD) "h" else "e" else base
    }

    fun validateParams(params: Params, full: Boolean): String? {
        if (params.width < 2 || params.height < 2) {
            return "Width and height must both be at least two"
        }
        if (params.width > Int.MAX_VALUE / params.height) {
            return "Width times height must not be unreasonably large"
        }
        return null
    }

    /** `validate_desc`: the description must hold exactly W*H clue cells. */
    fun validateDesc(params: Params, desc: String): String? {
        val area = (params.width + 1) * (params.height + 1)
        var squares = 0
        for (c in desc) {
            when (c) {
                in 'a'..'z' -> squares += c - 'a' + 1
                in '0'..'4' -> squares++
                else -> return "Invalid character in game description"
            }
        }
        if (squares < area) return "Not enough data to fill grid"
        if (squares > area) return "Too much data to fit in grid"
        return null
    }

    /** `new_game_desc`: the description and the auxiliary solution text. */
    fun newDesc(params: Params, random: RandomState): GeneratedDesc {
        val w = params.width
        val h = params.height
        val bigW = w + 1
        val bigH = h + 1
        val soln = IntArray(w * h)
        val tmpSoln = IntArray(w * h)
        val clues = IntArray(bigW * bigH)
        val clueIndices = IntArray(bigW * bigH)
        val scratch = Scratch(w, h)

        while (true) {
            generate(w, h, soln, random)

            for (y in 0 until bigH) {
                for (x in 0 until bigW) {
                    var value = 0
                    if (x > 0 && y > 0 && soln[(y - 1) * w + (x - 1)] == -1) value++
                    if (x > 0 && y < h && soln[y * w + (x - 1)] == 1) value++
                    if (x < w && y > 0 && soln[(y - 1) * w + x] == 1) value++
                    if (x < w && y < h && soln[y * w + x] == -1) value++
                    clues[y * bigW + x] = value
                }
            }

            check(solve(w, h, clues, tmpSoln, scratch, EASY) == 1)

            for (i in clueIndices.indices) clueIndices[i] = i
            shuffle(clueIndices, random)
            for (pass in 0 until 2) {
                for (index in clueIndices) {
                    val y = index / bigW
                    val x = index % bigW
                    val value = clues[y * bigW + x]
                    val xBorder = x == 0 || x == bigW - 1
                    val yBorder = y == 0 || y == bigH - 1
                    val thisPass = if (params.difficulty == EASY || value == 4 || value == 0 ||
                        (value == 2 && (xBorder || yBorder)) ||
                        (value == 1 && xBorder && yBorder)
                    ) {
                        0
                    } else {
                        1
                    }
                    if (thisPass == pass) {
                        clues[y * bigW + x] = -1
                        if (solve(w, h, clues, tmpSoln, scratch, params.difficulty) != 1) {
                            clues[y * bigW + x] = value
                        }
                    }
                }
            }

            if (params.difficulty <= 0 ||
                solve(w, h, clues, tmpSoln, scratch, params.difficulty - 1) > 1
            ) {
                break
            }
        }

        val desc = encodeDesc(clues, bigW * bigH)
        val aux = StringBuilder(w * h)
        for (i in 0 until w * h) aux.append(if (soln[i] < 0) '\\' else '/')
        return GeneratedDesc(desc, aux.toString())
    }

    data class GeneratedDesc(val text: String, val auxInfo: String)

    private fun encodeDesc(clues: IntArray, area: Int): String {
        val out = StringBuilder(area + 1)
        var run = 0
        for (i in 0..area) {
            val value = if (i < area) clues[i] else -2
            if (value == -1) {
                run++
            } else {
                while (run > 0) {
                    var c = 'a' - 1 + run
                    if (run > 26) c = 'z'
                    out.append(c)
                    run -= c - ('a' - 1)
                }
                if (value >= 0) out.append('0' + value)
                run = 0
            }
        }
        return out.toString()
    }

    /** `slant_generate`: fills a complete grid, one square at a time. */
    private fun generate(w: Int, h: Int, soln: IntArray, random: RandomState) {
        val bigW = w + 1
        soln.fill(0)
        val connected = Dsf.new(bigW * (h + 1))
        val indices = IntArray(w * h) { it }
        shuffle(indices, random)

        for (index in indices) {
            val y = index / w
            val x = index % w
            val front = connected.canonify(y * bigW + x) ==
                connected.canonify((y + 1) * bigW + (x + 1))
            val back = connected.canonify((y + 1) * bigW + x) ==
                connected.canonify(y * bigW + (x + 1))
            check(!(front && back))
            val value = when {
                front -> 1
                back -> -1
                else -> if (random.upto(2) != 0L) 1 else -1
            }
            check(fillSquare(w, h, x, y, value, soln, connected, null))
        }
    }

    /**
     * `slant_solve`. Returns 0 for impossibility, 1 for success, 2 for
     * ambiguity or failure to converge.
     */
    fun solve(
        w: Int,
        h: Int,
        clues: IntArray,
        soln: IntArray,
        scratch: Scratch,
        difficulty: Int,
    ): Int {
        val bigW = w + 1
        val bigH = h + 1
        val connected = scratch.connected
        val equiv = scratch.equiv
        soln.fill(0)
        scratch.clues = clues
        connected.reinit()
        equiv.reinit()
        scratch.slashValue.fill(0)
        scratch.vbitmap.fill(0xF)

        for (y in 0 until bigH) {
            for (x in 0 until bigW) {
                val index = y * bigW + x
                scratch.border[index] =
                    y == 0 || y == bigH - 1 || x == 0 || x == bigW - 1
                scratch.exits[index] = if (clues[index] < 0) 4 else clues[index]
            }
        }

        while (true) {
            var doneSomething = false

            for (y in 0 until bigH) {
                for (x in 0 until bigW) {
                    val c = clues[y * bigW + x]
                    if (c < 0) continue

                    val positions = IntArray(4)
                    val slashes = IntArray(4)
                    var count = 0
                    if (x > 0 && y > 0) {
                        positions[count] = (y - 1) * w + (x - 1)
                        slashes[count++] = -1
                    }
                    if (x > 0 && y < h) {
                        positions[count] = y * w + (x - 1)
                        slashes[count++] = 1
                    }
                    if (x < w && y < h) {
                        positions[count] = y * w + x
                        slashes[count++] = -1
                    }
                    if (x < w && y > 0) {
                        positions[count] = (y - 1) * w + x
                        slashes[count++] = 1
                    }

                    var undecided = 0
                    var lines = c
                    var last = positions[count - 1]
                    var equivalence = if (soln[last] == 0) equiv.canonify(last) else -1
                    var markedEquivalence = -1
                    var marked1 = -1
                    var marked2 = -1
                    for (i in 0 until count) {
                        val j = positions[i]
                        val slash = slashes[i]
                        if (soln[j] == 0) {
                            undecided++
                            if (markedEquivalence < 0 && difficulty > EASY) {
                                val other = equiv.canonify(j)
                                if (equivalence == other && last != j) {
                                    markedEquivalence = equivalence
                                    marked1 = last
                                    marked2 = j
                                    lines--
                                    undecided -= 2
                                } else {
                                    equivalence = other
                                }
                            }
                        } else {
                            equivalence = -1
                            if (soln[j] == slash) lines--
                        }
                        last = j
                    }

                    if (lines < 0 || lines > undecided) return 0

                    if (undecided > 0 && (lines == 0 || lines == undecided)) {
                        for (i in 0 until count) {
                            val j = positions[i]
                            if (soln[j] == 0 && j != marked1 && j != marked2) {
                                val value = if (lines != 0) slashes[i] else -slashes[i]
                                if (!fillSquare(w, h, j % w, j / w, value, soln, connected, scratch)) {
                                    return 0
                                }
                            }
                        }
                        doneSomething = true
                    } else if (undecided == 2 && lines == 1 && difficulty > EASY) {
                        var first = -1
                        var i = 0
                        while (i < count) {
                            val j = positions[i]
                            if (soln[j] == 0 && j != marked1 && j != marked2) {
                                if (first < 0) {
                                    first = i
                                } else if (first == i - 1 || (first == 0 && i == 3)) {
                                    break
                                }
                            }
                            i++
                        }
                        if (i < count) {
                            check(first >= 0)
                            marked1 = equiv.canonify(positions[first])
                            val value1 = scratch.slashValue[marked1]
                            marked2 = equiv.canonify(positions[i])
                            val value2 = scratch.slashValue[marked2]
                            if (value1 != 0 && value2 != 0 && value1 != value2) return 0
                            val value = if (value1 != 0) value1 else value2
                            equiv.merge(marked1, marked2)
                            marked1 = equiv.canonify(marked1)
                            scratch.slashValue[marked1] = value
                        }
                    }
                }
            }

            if (doneSomething) continue

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val index = y * w + x
                    if (soln[index] != 0) continue

                    var front = false
                    var back = false
                    val known = if (difficulty > EASY) {
                        scratch.slashValue[equiv.canonify(index)]
                    } else {
                        0
                    }

                    var c1 = connected.canonify(y * bigW + x)
                    var c2 = connected.canonify((y + 1) * bigW + (x + 1))
                    if (c1 == c2) front = true
                    if (difficulty > EASY && !scratch.border[c1] && !scratch.border[c2] &&
                        scratch.exits[c1] <= 1 && scratch.exits[c2] <= 1
                    ) {
                        front = true
                    }
                    if (known == 1) front = true

                    c1 = connected.canonify(y * bigW + (x + 1))
                    c2 = connected.canonify((y + 1) * bigW + x)
                    if (c1 == c2) back = true
                    if (difficulty > EASY && !scratch.border[c1] && !scratch.border[c2] &&
                        scratch.exits[c1] <= 1 && scratch.exits[c2] <= 1
                    ) {
                        back = true
                    }
                    if (known == -1) back = true

                    if (front && back) return 0
                    if (front) {
                        if (!fillSquare(w, h, x, y, 1, soln, connected, scratch)) return 0
                        doneSomething = true
                    } else if (back) {
                        if (!fillSquare(w, h, x, y, -1, soln, connected, scratch)) return 0
                        doneSomething = true
                    }
                }
            }

            if (doneSomething) continue
            // The C do-while checks the condition here; doneSomething is
            // false, so Easy ends the iteration here.
            if (difficulty <= EASY) break

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val index = y * w + x
                    val slash = soln[index]
                    if (slash != 0) {
                        if (x > 0) {
                            doneSomething = vbitmapClear(w, scratch,
                                x - 1, y, if (slash < 0) 0x1 else 0x2,
                            ) || doneSomething
                        }
                        if (x + 1 < w) {
                            doneSomething = vbitmapClear(w, scratch,
                                x, y, if (slash < 0) 0x2 else 0x1,
                            ) || doneSomething
                        }
                        if (y > 0) {
                            doneSomething = vbitmapClear(w, scratch,
                                x, y - 1, if (slash < 0) 0x4 else 0x8,
                            ) || doneSomething
                        }
                        if (y + 1 < h) {
                            doneSomething = vbitmapClear(w, scratch,
                                x, y, if (slash < 0) 0x8 else 0x4,
                            ) || doneSomething
                        }
                    }

                    if (x + 1 < w && scratch.vbitmap[index] and 0x3 == 0) {
                        val other = y * w + (x + 1)
                        if (equiv.canonify(index) != equiv.canonify(other)) {
                            equiv.merge(index, other)
                            doneSomething = true
                        }
                    }
                    if (y + 1 < h && scratch.vbitmap[index] and 0xC == 0) {
                        val other = (y + 1) * w + x
                        if (equiv.canonify(index) != equiv.canonify(other)) {
                            equiv.merge(index, other)
                            doneSomething = true
                        }
                    }

                    if (y == 0 || x == 0) continue
                    val clue = clues[y * bigW + x]
                    if (clue < 0) continue

                    when (clue) {
                        1 -> {
                            doneSomething = vbitmapClear(w, scratch, x - 1, y - 1, 0x5) ||
                                doneSomething
                            doneSomething = vbitmapClear(w, scratch, x - 1, y, 0x2) ||
                                doneSomething
                            doneSomething = vbitmapClear(w, scratch, x, y - 1, 0x8) ||
                                doneSomething
                        }

                        3 -> {
                            doneSomething = vbitmapClear(w, scratch, x - 1, y - 1, 0xA) ||
                                doneSomething
                            doneSomething = vbitmapClear(w, scratch, x - 1, y, 0x1) ||
                                doneSomething
                            doneSomething = vbitmapClear(w, scratch, x, y - 1, 0x4) ||
                                doneSomething
                        }

                        2 -> {
                            doneSomething = vbitmapClear(w, scratch,
                                x - 1, y - 1,
                                (scratch.vbitmap[y * w + (x - 1)] and 0x3) xor 0x3,
                            ) || doneSomething
                            doneSomething = vbitmapClear(w, scratch,
                                x - 1, y - 1,
                                (scratch.vbitmap[(y - 1) * w + x] and 0xC) xor 0xC,
                            ) || doneSomething
                            doneSomething = vbitmapClear(w, scratch,
                                x - 1, y,
                                (scratch.vbitmap[(y - 1) * w + (x - 1)] and 0x3) xor 0x3,
                            ) || doneSomething
                            doneSomething = vbitmapClear(w, scratch,
                                x, y - 1,
                                (scratch.vbitmap[(y - 1) * w + (x - 1)] and 0xC) xor 0xC,
                            ) || doneSomething
                        }
                    }
                }
            }

            if (!doneSomething) break
        }

        for (value in soln) if (value == 0) return 2
        return 1
    }

    class Scratch(w: Int, h: Int) {
        val connected = Dsf.new((w + 1) * (h + 1))
        val exits = IntArray((w + 1) * (h + 1))
        val border = BooleanArray((w + 1) * (h + 1))
        val equiv = Dsf.new(w * h)
        val slashValue = IntArray(w * h)
        val vbitmap = IntArray(w * h)
        var clues: IntArray = IntArray(0)
    }

    private fun fillSquare(
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        value: Int,
        soln: IntArray,
        connected: Dsf,
        scratch: Scratch?,
    ): Boolean {
        val bigW = w + 1
        val index = y * w + x
        if (soln[index] == value) return true

        val connect1: Int
        val connect2: Int
        val disconnect1: Int
        val disconnect2: Int
        if (value < 0) {
            connect1 = y * bigW + x
            connect2 = (y + 1) * bigW + (x + 1)
            disconnect1 = y * bigW + (x + 1)
            disconnect2 = (y + 1) * bigW + x
        } else {
            connect1 = y * bigW + (x + 1)
            connect2 = (y + 1) * bigW + x
            disconnect1 = y * bigW + x
            disconnect2 = (y + 1) * bigW + (x + 1)
        }

        if (connected.equivalent(connect1, connect2)) return false

        soln[index] = value
        if (scratch != null) {
            val canonical = scratch.equiv.canonify(index)
            scratch.slashValue[canonical] = value
        }

        mergeVertices(connected, scratch, connect1, connect2)
        if (scratch != null) {
            decrExits(scratch, disconnect1)
            decrExits(scratch, disconnect2)
        }
        return true
    }

    private fun mergeVertices(connected: Dsf, scratch: Scratch?, first: Int, second: Int) {
        var exits = -1
        var border = false
        if (scratch != null) {
            val i = connected.canonify(first)
            val j = connected.canonify(second)
            exits = scratch.exits[i] + scratch.exits[j] - 2
            border = scratch.border[i] || scratch.border[j]
        }

        connected.merge(first, second)

        if (scratch != null) {
            val root = connected.canonify(first)
            scratch.exits[root] = exits
            scratch.border[root] = border
        }
    }

    private fun decrExits(scratch: Scratch, index: Int) {
        if (scratch.clues[index] < 0) {
            scratch.exits[scratch.connected.canonify(index)]--
        }
    }

    private fun vbitmapClear(w: Int, scratch: Scratch, x: Int, y: Int, bits: Int): Boolean {
        var done = false
        var bit = 1
        while (bit <= 8) {
            if (bits and scratch.vbitmap[y * w + x] and bit != 0) {
                done = true
                scratch.vbitmap[y * w + x] = scratch.vbitmap[y * w + x] and bit.inv()
            }
            bit = bit shl 1
        }
        return done
    }

    private fun shuffle(array: IntArray, random: RandomState) {
        var i = array.size
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