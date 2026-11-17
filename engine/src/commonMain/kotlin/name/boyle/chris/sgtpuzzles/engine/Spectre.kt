package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `spectre.c`: the aperiodic spectre tiling
 * generator.
 *
 * The generator breadth-first searches the tiling, using the
 * combinatorial coordinates of each spectre to decide which tiles are
 * adjacent. The random draw order and the search order are observable
 * through the generated grid, so both match upstream.
 */
internal object SpectreTiling {

    private const val HEX_G = SpectreTables.HEX_G

    /** A patch of tiling, as stored in a game description. */
    class PatchParams(
        var orientation: Int,
        var coords: IntArray,
        var finalHex: Char,
    )

    // ----------------------------------------------------------------
    // Point arithmetic: integer combinations of 1, d, d^2, d^3, where
    // d = exp(i*pi/6).
    // ----------------------------------------------------------------

    private fun point(vararg c: Int): IntArray = c

    private fun pointAdd(a: IntArray, b: IntArray): IntArray {
        val r = IntArray(4)
        for (i in 0 until 4) r[i] = a[i] + b[i]
        return r
    }

    private fun pointSub(a: IntArray, b: IntArray): IntArray {
        val r = IntArray(4)
        for (i in 0 until 4) r[i] = a[i] - b[i]
        return r
    }

    /** Multiply by d, using d^4 - d^2 + 1 = 0. */
    private fun pointMulByD(x: IntArray): IntArray = point(
        -x[3],
        x[0],
        x[1] + x[3],
        x[2],
    )

    private fun pointMul(a: IntArray, b: IntArray): IntArray {
        val r = IntArray(4)
        for (j in 0 until 4) r[j] = a[j] * b[3]
        for (i in 2 downTo 0) {
            val multiplied = pointMulByD(r)
            for (j in 0 until 4) multiplied[j] += a[j] * b[i]
            multiplied.copyInto(r)
        }
        return r
    }

    private fun pointRot(steps: Int): IntArray {
        var r = point(1, 0, 0, 0)
        var dPower = point(0, 1, 0, 0)
        var s = steps % 12
        if (s < 0) s += 12
        while (true) {
            if (s and 1 != 0) r = pointMul(r, dPower)
            s = s shr 1
            if (s == 0) break
            dPower = pointMul(dPower, dPower)
        }
        return r
    }

    private class Coord(val c1: Int, val cr3: Int)

    private fun pointX(p: IntArray): Coord = Coord(2 * p[0] + p[2], p[1])

    private fun pointY(p: IntArray): Coord = Coord(2 * p[3] + p[1], p[2])

    private fun coordSign(x: Coord): Int {
        if (x.c1 == 0 && x.cr3 == 0) return 0
        if (x.c1 >= 0 && x.cr3 >= 0) return 1
        if (x.c1 <= 0 && x.cr3 <= 0) return -1
        return if (x.c1 * x.c1 > 3 * x.cr3 * x.cr3) {
            if (x.c1 < 0) -1 else 1
        } else {
            if (x.cr3 < 0) -1 else 1
        }
    }

    private fun coordCmp(a: Coord, b: Coord): Int =
        coordSign(Coord(a.c1 - b.c1, a.cr3 - b.cr3))

    // ----------------------------------------------------------------
    // Coordinates.
    // ----------------------------------------------------------------

    private class Coords {
        var index = -1
        var hexColour = 0
        var prevHexColour = 0
        var incomingHexEdge = 0
        var cIndex = IntArray(0)
        var cType = IntArray(0)
        var nc = 0

        fun makeSpace(size: Int) {
            if (cIndex.size < size) {
                var newSize = cIndex.size * 5 / 4 + 16
                if (newSize < size) newSize = size
                cIndex = cIndex.copyOf(newSize)
                cType = cType.copyOf(newSize)
            }
        }

        fun copy(): Coords {
            val out = Coords()
            out.makeSpace(nc)
            cIndex.copyInto(out.cIndex, 0, 0, nc)
            cType.copyInto(out.cType, 0, 0, nc)
            out.nc = nc
            out.index = index
            out.hexColour = hexColour
            out.prevHexColour = prevHexColour
            out.incomingHexEdge = incomingHexEdge
            return out
        }
    }

    private class Spectre {
        val vertices = Array(14) { IntArray(4) }
        lateinit var sc: Coords
    }

    private class Context(
        var random: RandomState?,
        val prototype: Coords,
        val startVertices: Array<IntArray>,
        var orientation: Int,
    )

    private fun numSubhexes(hex: Int): Int = SpectreTables.numSubhexes(hex)
    private fun numSpectres(hex: Int): Int = SpectreTables.numSpectres(hex)

    private fun validHexLetter(c: Char): Boolean = SpectreTables.letters.contains(c)

    private fun hexFromLetter(c: Char): Int {
        val index = SpectreTables.letters.indexOf(c)
        check(index >= 0) { "invalid hex letter $c" }
        return index
    }

    private fun choosePoss(random: RandomState, table: IntArray): Int {
        var limit = 0L
        for (i in 0 until table.size / 3) limit += table[3 * i + 2]
        var value = random.upto(limit)
        for (i in 0 until table.size / 3 - 1) {
            val probability = table[3 * i + 2]
            if (value < probability) return 3 * i
            value -= probability
        }
        return 3 * (table.size / 3 - 1)
    }

    private fun mapEntry(table: IntArray, index: Int): IntArray {
        val base = 3 * index
        return intArrayOf(table[base], table[base + 1], table[base + 2])
    }

    private fun edgeEntry(table: IntArray, index: Int): IntArray {
        val base = 2 * index
        return intArrayOf(table[base], table[base + 1])
    }

    // ----------------------------------------------------------------
    // Stepping.
    // ----------------------------------------------------------------

    private fun startVertices(ctx: Context, orientation: Int) {
        val minusSqrt3 = pointAdd(pointRot(5), pointRot(-5))
        val basicEdge = pointMul(
            pointAdd(pointRot(0), pointRot(-3)),
            pointRot(orientation),
        )
        val diagonal = pointAdd(basicEdge, pointMul(basicEdge, pointRot(-3)))
        ctx.startVertices[0] = pointMul(diagonal, minusSqrt3)
        ctx.startVertices[1] = pointAdd(ctx.startVertices[0], basicEdge)
        ctx.orientation = orientation
    }

    private fun extendCoords(ctx: Context, sc: Coords, n: Int) {
        if (ctx.prototype.nc < n) {
            ctx.prototype.makeSpace(n)
            while (ctx.prototype.nc < n) {
                val type = ctx.prototype.cType[ctx.prototype.nc - 1]
                if (ctx.random == null) {
                    // The C code makes a deterministic generator here.
                    ctx.random = RandomState.fromSeed("dummy")
                }
                val entry = choosePoss(ctx.random!!, SpectreTables.poss[type])
                ctx.prototype.cIndex[ctx.prototype.nc - 1] = SpectreTables.poss[type][entry + 1]
                ctx.prototype.cType[ctx.prototype.nc] = SpectreTables.poss[type][entry]
                ctx.prototype.cIndex[ctx.prototype.nc] = -1
                ctx.prototype.nc++
            }
        }

        sc.makeSpace(n)
        while (sc.nc < n) {
            sc.cIndex[sc.nc - 1] = ctx.prototype.cIndex[sc.nc - 1]
            sc.cIndex[sc.nc] = -1
            sc.cType[sc.nc] = ctx.prototype.cType[sc.nc]
            sc.nc++
        }
    }

    private fun stepHex(
        ctx: Context,
        sc: Coords,
        depth: Int,
        edge: Int,
        outEdge: IntArray,
    ) {
        extendCoords(ctx, sc, depth + 2)
        check(sc.cIndex[depth] >= 0)
        check(sc.cIndex[depth] < numSubhexes(sc.cType[depth]))
        check(edge in 0 until 6)

        var type = sc.cType[depth + 1]
        var entry = mapEntry(
            SpectreTables.hexmap[type],
            6 * sc.cIndex[depth] + edge,
        )
        if (entry[0] == 0) {
            val recedge = IntArray(1)
            stepHex(ctx, sc, depth + 1, entry[1], recedge)
            type = sc.cType[depth + 1]
            val edgeData = edgeEntry(SpectreTables.hexedges[type], recedge[0])
            check(entry[2] < edgeData[1])
            entry = mapEntry(
                SpectreTables.hexin[type],
                edgeData[0] + edgeData[1] - 1 - entry[2],
            )
            check(entry[0] == 1)
        }
        sc.cIndex[depth] = entry[1]
        sc.cType[depth] = SpectreTables.subhexes[type][entry[1]]
        outEdge[0] = entry[2]

        if (depth == 0) {
            val newHexColour = if ((edge xor sc.incomingHexEdge) and 1 == 0) {
                sc.prevHexColour
            } else {
                0 + 1 + 2 - sc.hexColour - sc.prevHexColour
            }
            sc.prevHexColour = sc.hexColour
            sc.hexColour = newHexColour
            sc.incomingHexEdge = entry[2]
        }
    }

    private fun step(ctx: Context, sc: Coords, edge: Int, outEdge: IntArray) {
        check(sc.index >= 0)
        check(sc.index < numSpectres(sc.cType[0]))
        check(edge in 0 until 14)

        var type = sc.cType[0]
        var entry = mapEntry(
            SpectreTables.specmap[type],
            14 * sc.index + edge,
        )
        while (entry[0] == 0) {
            val recedge = IntArray(1)
            stepHex(ctx, sc, 0, entry[1], recedge)
            type = sc.cType[0]
            val edgeData = edgeEntry(SpectreTables.specedges[type], recedge[0])
            check(entry[2] < edgeData[1])
            entry = mapEntry(
                SpectreTables.specin[type],
                edgeData[0] + edgeData[1] - 1 - entry[2],
            )
        }
        sc.index = entry[1]
        outEdge[0] = entry[2]
    }

    // ----------------------------------------------------------------
    // Geometry.
    // ----------------------------------------------------------------

    private fun place(spec: Spectre, uIn: IntArray, v: IntArray, indexOfU: Int) {
        var u = uIn
        var disp = pointSub(v, u)
        for (i in 0 until 14) {
            spec.vertices[(i + indexOfU) % 14] = u
            u = pointAdd(u, disp)
            disp = pointMul(
                disp,
                pointRot(SpectreTables.angles[(i + 1 + indexOfU) % 14]),
            )
        }
    }

    private fun initial(ctx: Context): Spectre {
        val spec = Spectre()
        place(spec, ctx.startVertices[0], ctx.startVertices[1], 0)
        spec.sc = ctx.prototype.copy()
        return spec
    }

    private fun adjacent(
        ctx: Context,
        src: Spectre,
        srcEdge: Int,
        dstEdgeOut: IntArray?,
    ): Spectre {
        val dst = Spectre()
        dst.sc = src.sc.copy()
        val dstEdge = IntArray(1)
        step(ctx, dst.sc, srcEdge, dstEdge)
        place(
            dst,
            src.vertices[(srcEdge + 1) % 14],
            src.vertices[srcEdge],
            dstEdge[0],
        )
        if (dstEdgeOut != null) dstEdgeOut[0] = dstEdge[0]
        return dst
    }

    private class SpectreKey(a: IntArray, b: IntArray) {
        private val values = intArrayOf(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])

        override fun equals(other: Any?): Boolean =
            other is SpectreKey && values.contentEquals(other.values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    private fun keyOf(spec: Spectre): SpectreKey =
        SpectreKey(spec.vertices[0], spec.vertices[1])

    private class Bounds(
        val xoff: Int,
        val yoff: Int,
        val xmin: Coord,
        val xmax: Coord,
        val ymin: Coord,
        val ymax: Coord,
    )

    private fun setBounds(w: Int, h: Int): Bounds {
        val xoff = w / 2
        val yoff = h / 2
        return Bounds(
            xoff,
            yoff,
            Coord(-xoff, 0),
            Coord(-xoff + w, 0),
            Coord(yoff - h, 0),
            Coord(yoff, 0),
        )
    }

    /**
     * Runs the breadth-first search. [callback] returns false to reject
     * a tile and stop exploring from it.
     */
    private fun generateSearch(
        ctx: Context,
        bounds: Bounds,
        callback: (Spectre) -> Boolean,
    ) {
        val placed = HashMap<SpectreKey, Spectre>()
        val queue = ArrayDeque<Spectre>()

        val first = initial(ctx)
        placed[keyOf(first)] = first
        if (callback(first)) queue.addLast(first)

        while (queue.isNotEmpty()) {
            val spec = queue.removeFirst()
            for (edge in 0 until 14) {
                val next = adjacent(ctx, spec, edge, null)
                if (placed.containsKey(keyOf(next))) continue
                if (!callback(next)) continue
                placed[keyOf(next)] = next
                queue.addLast(next)
            }
        }
    }

    // ----------------------------------------------------------------
    // Public entry points.
    // ----------------------------------------------------------------

    fun paramsInvalid(params: PatchParams): String? {
        if (params.coords.isEmpty()) return "expected at least one numeric coordinate"
        if (!validHexLetter(params.finalHex)) return "invalid final hexagon type"

        var hex = hexFromLetter(params.finalHex)
        for (i in params.coords.size - 1 downTo 0) {
            val limit = if (i == 0) numSpectres(hex) else numSubhexes(hex)
            if (params.coords[i] >= limit) return "coordinate out of range"
            if (i > 0) hex = SpectreTables.subhexes[hex][params.coords[i]]
        }
        return null
    }

    private fun initRandom(random: RandomState): Context {
        val prototype = Coords()
        prototype.makeSpace(1)
        val entry = choosePoss(random, SpectreTables.possSpectre)
        prototype.index = SpectreTables.possSpectre[entry + 1]
        prototype.cType[0] = SpectreTables.possSpectre[entry]
        prototype.cIndex[0] = -1
        prototype.nc = 1

        val startVertices = Array(2) { IntArray(4) }
        val ctx = Context(random, prototype, startVertices, 0)
        startVertices(ctx, random.upto(6).toInt() * 2 + prototype.index)

        prototype.hexColour = 0
        prototype.prevHexColour = 0
        prototype.incomingHexEdge = 0
        return ctx
    }

    private fun initFromParams(params: PatchParams): Context {
        val prototype = Coords()
        prototype.makeSpace(params.coords.size)
        prototype.index = params.coords[0]
        for (i in 1 until params.coords.size) {
            prototype.cIndex[i - 1] = params.coords[i]
        }
        prototype.cIndex[params.coords.size - 1] = -1
        prototype.nc = params.coords.size
        prototype.cType[params.coords.size - 1] = hexFromLetter(params.finalHex)
        for (i in params.coords.size - 2 downTo 0) {
            prototype.cType[i] =
                SpectreTables.subhexes[prototype.cType[i + 1]][prototype.cIndex[i]]
        }

        val startVertices = Array(2) { IntArray(4) }
        val ctx = Context(null, prototype, startVertices, 0)
        startVertices(ctx, params.orientation)

        prototype.hexColour = 0
        prototype.prevHexColour = 0
        prototype.incomingHexEdge = 0
        return ctx
    }

    /** `spectre_tiling_randomise`. */
    fun randomise(w: Int, h: Int, random: RandomState): PatchParams {
        val bounds = setBounds(w, h)
        val ctx = initRandom(random)
        generateSearch(ctx, bounds) { spec -> inBounds(spec, bounds) }

        val prototype = ctx.prototype
        val coords = IntArray(prototype.nc)
        coords[0] = prototype.index
        for (i in 1 until prototype.nc) coords[i] = prototype.cIndex[i - 1]
        return PatchParams(
            orientation = ctx.orientation,
            coords = coords,
            finalHex = SpectreTables.letters[prototype.cType[prototype.nc - 1]],
        )
    }

    private fun inBounds(spec: Spectre, bounds: Bounds): Boolean {
        for (i in 0 until 14) {
            val x = pointX(spec.vertices[i])
            val y = pointY(spec.vertices[i])
            if (coordCmp(x, bounds.xmin) < 0 || coordCmp(x, bounds.xmax) > 0 ||
                coordCmp(y, bounds.ymin) < 0 || coordCmp(y, bounds.ymax) > 0
            ) {
                return false
            }
        }
        return true
    }

    /** `spectre_tiling_generate`: one polygon per spectre, as 56 ints. */
    fun generate(params: PatchParams, w: Int, h: Int, tile: (IntArray) -> Unit) {
        val bounds = setBounds(w, h)
        val ctx = initFromParams(params)
        generateSearch(ctx, bounds) { spec ->
            if (!inBounds(spec, bounds)) {
                false
            } else {
                val output = IntArray(4 * 14)
                for (i in 0 until 14) {
                    val x = pointX(spec.vertices[i])
                    val y = pointY(spec.vertices[i])
                    output[4 * i + 0] = bounds.xoff + x.c1
                    output[4 * i + 1] = x.cr3
                    output[4 * i + 2] = bounds.yoff - y.c1
                    output[4 * i + 3] = -y.cr3
                }
                tile(output)
                true
            }
        }
    }
}
