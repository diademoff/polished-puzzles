package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `penrose.c` and `penrose-legacy.c`: the two
 * Penrose tiling generators used by `grid.c`.
 *
 * The modern generator works in combinatorial coordinates and places
 * tiles by exact integer combinations of powers of exp(i*pi/5). The
 * legacy generator subdivides one big triangle with floating-point
 * vertices, and exists only to read old `loopy` game descriptions. Both
 * must reproduce upstream exactly: a different random draw or a
 * different tile order changes the board.
 *
 * `Penrose.kt` is internal to the engine. `grid.c` is the only
 * consumer, through [Grid].
 */
internal object PenroseTiling {

    const val P2 = 0
    const val P3 = 1

    const val NVERTICES = 4

    /** A patch of tiling, in combinatorial coordinates. */
    class PatchParams(
        var orientation: Int,
        var startVertex: Int,
        var coords: CharArray,
    )

    // ----------------------------------------------------------------
    // Point arithmetic: integer linear combinations of 1, t, t^2, t^3,
    // where t = exp(i*pi/5).
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

    /** Multiply by t, using t^4 - t^3 + t^2 - t + 1 = 0. */
    private fun pointMulByT(x: IntArray): IntArray = point(
        -x[3],
        x[0] + x[3],
        x[1] - x[3],
        x[2] + x[3],
    )

    private fun pointMul(a: IntArray, b: IntArray): IntArray {
        val r = IntArray(4)
        for (j in 0 until 4) r[j] = a[j] * b[3]
        for (i in 2 downTo 0) {
            val multiplied = pointMulByT(r)
            for (j in 0 until 4) multiplied[j] += a[j] * b[i]
            multiplied.copyInto(r)
        }
        return r
    }

    private fun pointRot(steps: Int): IntArray {
        var r = point(1, 0, 0, 0)
        var tPower = point(0, 1, 0, 0)
        var s = steps % 10
        if (s < 0) s += 10
        while (true) {
            if (s and 1 != 0) r = pointMul(r, tPower)
            s = s shr 1
            if (s == 0) break
            tPower = pointMul(tPower, tPower)
        }
        return r
    }

    /** A single coordinate: c1 + cr5 * sqrt(5). */
    private class Coord(val c1: Int, val cr5: Int)

    private fun pointX(p: IntArray): Coord = Coord(
        4 * p[0] + p[1] - p[2] + p[3],
        p[1] + p[2] - p[3],
    )

    private fun pointY(p: IntArray): Coord = Coord(
        2 * p[1] + p[2] + p[3],
        p[2] + p[3],
    )

    private fun coordSign(x: Coord): Int {
        if (x.c1 == 0 && x.cr5 == 0) return 0
        if (x.c1 >= 0 && x.cr5 >= 0) return 1
        if (x.c1 <= 0 && x.cr5 <= 0) return -1
        return if (x.c1 * x.c1 > 5 * x.cr5 * x.cr5) {
            if (x.c1 < 0) -1 else 1
        } else {
            if (x.cr5 < 0) -1 else 1
        }
    }

    private fun coordCmp(a: Coord, b: Coord): Int =
        coordSign(Coord(a.c1 - b.c1, a.cr5 - b.cr5))

    // ----------------------------------------------------------------
    // Combinatorial coordinates.
    // ----------------------------------------------------------------

    private class Coords {
        var c = CharArray(0)
        var nc = 0

        fun makeSpace(size: Int) {
            if (c.size < size) {
                var newSize = c.size * 5 / 4 + 16
                if (newSize < size) newSize = size
                c = c.copyOf(newSize)
            }
        }

        fun copy(): Coords {
            val out = Coords()
            out.makeSpace(nc)
            c.copyInto(out.c, 0, 0, nc)
            out.nc = nc
            return out
        }
    }

    private class Triangle {
        val vertices = Array(3) { IntArray(4) }
        lateinit var pc: Coords
        var reported = false
    }

    fun validLetter(c: Char, which: Int): Boolean = when (c) {
        'A', 'B', 'U', 'V' -> which == P2
        'C', 'D', 'X', 'Y' -> which == P3
        else -> false
    }

    // ----------------------------------------------------------------
    // Transitions.
    // ----------------------------------------------------------------

    private const val INTERNAL = 0
    private const val EXTERNAL = 1
    private const val FAIL = 2

    private class Transition(
        val type: Int,
        val newChild: Char = ' ',
        val newEdge: Int = 0,
        val parentEdge: Int = 0,
        val end: Int = 0,
    )

    private fun internal(newChild: Char, newEdge: Int) =
        Transition(INTERNAL, newChild = newChild, newEdge = newEdge)

    private fun external(parentEdge: Int, end: Int) =
        Transition(EXTERNAL, parentEdge = parentEdge, end = end)

    private fun fail() = Transition(FAIL)

    private fun transition(parent: Char, child: Char, edge: Int): Transition =
        when (parent) {
            'A' -> when (child) {
                'A' -> when (edge) {
                    0 -> external(2, -1)
                    1 -> external(0, 0)
                    2 -> internal('B', 1)
                    else -> fail()
                }
                'B' -> when (edge) {
                    0 -> internal('U', 1)
                    1 -> internal('A', 2)
                    2 -> external(1, 1)
                    else -> fail()
                }
                'U' -> when (edge) {
                    0 -> external(2, 1)
                    1 -> internal('B', 0)
                    2 -> external(1, -1)
                    else -> fail()
                }
                else -> fail()
            }
            'B' -> when (child) {
                'A' -> when (edge) {
                    0 -> internal('V', 2)
                    1 -> external(2, -1)
                    2 -> internal('B', 1)
                    else -> fail()
                }
                'B' -> when (edge) {
                    0 -> external(1, 1)
                    1 -> internal('A', 2)
                    2 -> external(0, 0)
                    else -> fail()
                }
                'V' -> when (edge) {
                    0 -> external(1, -1)
                    1 -> external(2, 1)
                    2 -> internal('A', 0)
                    else -> fail()
                }
                else -> fail()
            }
            'U' -> when (child) {
                'B' -> when (edge) {
                    0 -> internal('U', 1)
                    1 -> external(2, 0)
                    2 -> external(0, 1)
                    else -> fail()
                }
                'U' -> when (edge) {
                    0 -> external(1, 0)
                    1 -> internal('B', 0)
                    2 -> external(0, -1)
                    else -> fail()
                }
                else -> fail()
            }
            'V' -> when (child) {
                'A' -> when (edge) {
                    0 -> internal('V', 2)
                    1 -> external(0, -1)
                    2 -> external(1, 0)
                    else -> fail()
                }
                'V' -> when (edge) {
                    0 -> external(2, 0)
                    1 -> external(0, 1)
                    2 -> internal('A', 0)
                    else -> fail()
                }
                else -> fail()
            }
            'C' -> when (child) {
                'C' -> when (edge) {
                    0 -> external(1, 1)
                    1 -> internal('Y', 1)
                    2 -> external(0, 0)
                    else -> fail()
                }
                'Y' -> when (edge) {
                    0 -> external(2, 0)
                    1 -> internal('C', 1)
                    2 -> external(1, -1)
                    else -> fail()
                }
                else -> fail()
            }
            'D' -> when (child) {
                'D' -> when (edge) {
                    0 -> external(2, -1)
                    1 -> external(0, 0)
                    2 -> internal('X', 2)
                    else -> fail()
                }
                'X' -> when (edge) {
                    0 -> external(1, 0)
                    1 -> external(2, 1)
                    2 -> internal('D', 2)
                    else -> fail()
                }
                else -> fail()
            }
            'X' -> when (child) {
                'C' -> when (edge) {
                    0 -> external(2, 1)
                    1 -> internal('Y', 1)
                    2 -> internal('X', 1)
                    else -> fail()
                }
                'X' -> when (edge) {
                    0 -> external(1, 0)
                    1 -> internal('C', 2)
                    2 -> external(0, -1)
                    else -> fail()
                }
                'Y' -> when (edge) {
                    0 -> external(0, 1)
                    1 -> internal('C', 1)
                    2 -> external(2, -1)
                    else -> fail()
                }
                else -> fail()
            }
            'Y' -> when (child) {
                'D' -> when (edge) {
                    0 -> external(1, -1)
                    1 -> internal('Y', 2)
                    2 -> internal('X', 2)
                    else -> fail()
                }
                'X' -> when (edge) {
                    0 -> external(0, -1)
                    1 -> external(1, 1)
                    2 -> internal('D', 2)
                    else -> fail()
                }
                'Y' -> when (edge) {
                    0 -> external(2, 0)
                    1 -> external(0, 1)
                    2 -> internal('D', 1)
                    else -> fail()
                }
                else -> fail()
            }
            else -> fail()
        }

    private fun edgeEnd(edge: Int, end: Int): Int = 3 * edge + 1 + end

    private fun transitionIn(parent: Char, edge: Int, end: Int): Transition {
        val key = edgeEnd(edge, end)
        return when (parent) {
            'A' -> when (key) {
                edgeEnd(0, 0) -> internal('A', 1)
                edgeEnd(1, -1) -> internal('B', 2)
                edgeEnd(1, 1) -> internal('U', 2)
                edgeEnd(2, -1) -> internal('U', 0)
                edgeEnd(2, 1) -> internal('A', 0)
                else -> fail()
            }
            'B' -> when (key) {
                edgeEnd(0, 0) -> internal('B', 2)
                edgeEnd(1, -1) -> internal('B', 0)
                edgeEnd(1, 1) -> internal('V', 0)
                edgeEnd(2, -1) -> internal('V', 1)
                edgeEnd(2, 1) -> internal('A', 1)
                else -> fail()
            }
            'U' -> when (key) {
                edgeEnd(0, -1) -> internal('B', 2)
                edgeEnd(0, 1) -> internal('U', 2)
                edgeEnd(1, 0) -> internal('U', 0)
                edgeEnd(2, 0) -> internal('B', 1)
                else -> fail()
            }
            'V' -> when (key) {
                edgeEnd(0, -1) -> internal('V', 1)
                edgeEnd(0, 1) -> internal('A', 1)
                edgeEnd(1, 0) -> internal('A', 2)
                edgeEnd(2, 0) -> internal('V', 0)
                else -> fail()
            }
            'C' -> when (key) {
                edgeEnd(0, 0) -> internal('C', 2)
                edgeEnd(1, -1) -> internal('C', 0)
                edgeEnd(1, 1) -> internal('Y', 2)
                edgeEnd(2, 0) -> internal('Y', 0)
                else -> fail()
            }
            'D' -> when (key) {
                edgeEnd(0, 0) -> internal('D', 1)
                edgeEnd(1, 0) -> internal('X', 0)
                edgeEnd(2, -1) -> internal('X', 1)
                edgeEnd(2, 1) -> internal('D', 0)
                else -> fail()
            }
            'X' -> when (key) {
                edgeEnd(0, -1) -> internal('Y', 0)
                edgeEnd(0, 1) -> internal('X', 2)
                edgeEnd(1, 0) -> internal('X', 0)
                edgeEnd(2, -1) -> internal('C', 0)
                edgeEnd(2, 1) -> internal('Y', 2)
                else -> fail()
            }
            'Y' -> when (key) {
                edgeEnd(0, 1) -> internal('X', 0)
                edgeEnd(0, -1) -> internal('Y', 1)
                edgeEnd(1, -1) -> internal('X', 1)
                edgeEnd(1, 1) -> internal('D', 0)
                edgeEnd(2, 0) -> internal('Y', 0)
                else -> fail()
            }
            else -> fail()
        }
    }

    // ----------------------------------------------------------------
    // Geometry.
    // ----------------------------------------------------------------

    private fun postEdge(c: Char, edge: Int): IntArray {
        val acute = arrayOf(
            point(-1, 1, 0, 1),
            point(-1, 1, -1, 1),
            point(-1, 1, 0, 0),
        )
        val obtuse = arrayOf(
            point(0, -1, 1, 0),
            point(0, 0, 1, 0),
            point(-1, 0, 0, 1),
        )
        return when (c) {
            'A', 'B', 'C', 'D' -> acute[edge]
            else -> obtuse[edge]
        }
    }

    private fun place(tri: Triangle, u: IntArray, v: IntArray, indexOfU: Int) {
        var here = u
        var delta = pointSub(v, u)
        for (i in 0 until 3) {
            val edge = (indexOfU + i) % 3
            tri.vertices[edge] = here
            here = pointAdd(here, delta)
            delta = pointMul(delta, postEdge(tri.pc.c[0], edge))
        }
    }

    private fun relativeProbability(c: Char): Long = when (c) {
        'A', 'B', 'X', 'Y' -> 63_245_986L
        'C', 'D', 'U', 'V' -> 39_088_169L
        else -> 0L
    }

    private fun chooseRandom(possibilities: String, random: RandomState): Char {
        var limit = 0L
        for (c in possibilities) limit += relativeProbability(c)
        var value = random.upto(limit)
        for (c in possibilities) {
            val current = relativeProbability(c)
            if (value < current) return c
            value -= current
        }
        error("probability overflow")
    }

    private fun startingTiles(which: Int): String = if (which == P2) "ABUV" else "CDXY"

    private fun validParents(tile: Char): String? = when (tile) {
        'A' -> "ABV"
        'B' -> "ABU"
        'U' -> "AU"
        'V' -> "BV"
        'C' -> "CX"
        'D' -> "DY"
        'X' -> "DXY"
        'Y' -> "CXY"
        else -> null
    }

    private fun triangleEdge0Length(c: Char): IntArray {
        val one = point(1, 0, 0, 0)
        val phi = point(1, 0, 1, -1)
        val invphi = point(0, 0, 1, -1)
        return when (c) {
            'A', 'B' -> invphi
            'U', 'V' -> one
            'C', 'D' -> invphi
            else -> phi
        }
    }

    private fun siblingEdgeIndex(c: Char): Int = when (c) {
        'A', 'U' -> 2
        'B', 'V' -> 1
        else -> 0
    }

    // ----------------------------------------------------------------
    // Generation context.
    // ----------------------------------------------------------------

    private class Context(
        var random: RandomState?,
        val prototype: Coords,
        var startVertex: Int,
        var orientation: Int,
    )

    private fun extendCoords(ctx: Context, pc: Coords, n: Int) {
        if (ctx.prototype.nc < n) {
            ctx.prototype.makeSpace(n)
            while (ctx.prototype.nc < n) {
                if (ctx.random == null) {
                    // The C code responds to a missing generator the same way.
                    ctx.random = RandomState.fromSeed("dummy")
                }
                ctx.prototype.c[ctx.prototype.nc] = chooseRandom(
                    validParents(ctx.prototype.c[ctx.prototype.nc - 1])!!,
                    ctx.random!!,
                )
                ctx.prototype.nc++
            }
        }
        pc.makeSpace(n)
        while (pc.nc < n) {
            pc.c[pc.nc] = ctx.prototype.c[pc.nc]
            pc.nc++
        }
    }

    private fun stepRecurse(
        ctx: Context,
        pc: Coords,
        depth: Int,
        edge: Int,
        outEdge: IntArray,
    ) {
        extendCoords(ctx, pc, depth + 2)
        var tr = transition(pc.c[depth + 1], pc.c[depth], edge)
        if (tr.type == EXTERNAL) {
            val parentOutEdge = IntArray(1)
            stepRecurse(ctx, pc, depth + 1, tr.parentEdge, parentOutEdge)
            tr = transitionIn(pc.c[depth + 1], parentOutEdge[0], tr.end)
        }
        check(tr.type == INTERNAL) { "penrose transition failed" }
        pc.c[depth] = tr.newChild
        outEdge[0] = tr.newEdge
    }

    private fun step(ctx: Context, pc: Coords, edge: Int, outEdge: IntArray) {
        stepRecurse(ctx, pc, 0, edge, outEdge)
    }

    private fun initial(ctx: Context): Triangle {
        val type = ctx.prototype.c[0]
        val origin = IntArray(4)
        var edge0 = triangleEdge0Length(type)
        val tri = Triangle()
        edge0 = pointMul(edge0, pointRot(ctx.orientation))
        tri.pc = ctx.prototype.copy()
        place(tri, origin, edge0, 0)
        val negOffset = tri.vertices[ctx.startVertex]
        for (i in 0 until 3) tri.vertices[i] = pointSub(tri.vertices[i], negOffset)
        return tri
    }

    private fun adjacent(
        ctx: Context,
        src: Triangle,
        srcEdge: Int,
        dstEdgeOut: IntArray?,
    ): Triangle {
        val dst = Triangle()
        dst.pc = src.pc.copy()
        val dstEdge = IntArray(1)
        step(ctx, dst.pc, srcEdge, dstEdge)
        place(
            dst,
            src.vertices[(srcEdge + 1) % 3],
            src.vertices[srcEdge],
            dstEdge[0],
        )
        if (dstEdgeOut != null) dstEdgeOut[0] = dstEdge[0]
        return dst
    }

    /** Exact key from the first two vertices, as `penrose_cmp` compares them. */
    private class TriangleKey(a: IntArray, b: IntArray) {
        private val values = intArrayOf(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])

        override fun equals(other: Any?): Boolean =
            other is TriangleKey && values.contentEquals(other.values)

        override fun hashCode(): Int = values.contentHashCode()
    }

    private fun keyOf(t: Triangle): TriangleKey = TriangleKey(t.vertices[0], t.vertices[1])

    private class Bounds(
        val xmin: Coord,
        val xmax: Coord,
        val ymin: Coord,
        val ymax: Coord,
    )

    private fun inBounds(bounds: Bounds, tri: Triangle): Boolean {
        for (i in 0 until 3) {
            val x = pointX(tri.vertices[i])
            val y = pointY(tri.vertices[i])
            if (coordCmp(x, bounds.xmin) < 0 || coordCmp(x, bounds.xmax) > 0 ||
                coordCmp(y, bounds.ymin) < 0 || coordCmp(y, bounds.ymax) > 0
            ) {
                return false
            }
        }
        return true
    }

    private fun setBounds(w: Int, h: Int): Bounds {
        val xoff = w / 2
        val yoff = h / 2
        return Bounds(
            Coord(-xoff, 0),
            Coord(-xoff + w, 0),
            Coord(yoff - h, 0),
            Coord(yoff, 0),
        )
    }

    private fun generateTriangles(
        ctx: Context,
        bounds: Bounds,
        tile: (Array<IntArray>) -> Unit,
    ) {
        val placed = HashMap<TriangleKey, Triangle>()
        val queue = ArrayDeque<Triangle>()

        val first = initial(ctx)
        placed[keyOf(first)] = first
        first.reported = false
        if (inBounds(bounds, first)) queue.addLast(first)

        while (queue.isNotEmpty()) {
            val tri = queue.removeFirst()
            val siblingEdge = siblingEdgeIndex(tri.pc.c[0])
            for (edge in 0 until 3) {
                val newTri = adjacent(ctx, tri, edge, null)
                if (!inBounds(bounds, newTri)) continue

                val found = placed[keyOf(newTri)]
                if (found != null) {
                    if (edge == siblingEdge && !tri.reported && !found.reported) {
                        val newSiblingEdge = siblingEdgeIndex(found.pc.c[0])
                        val tileVertices = arrayOf(
                            tri.vertices[(siblingEdge + 1) % 3],
                            tri.vertices[(siblingEdge + 2) % 3],
                            found.vertices[(newSiblingEdge + 1) % 3],
                            found.vertices[(newSiblingEdge + 2) % 3],
                        )
                        tile(tileVertices)
                        tri.reported = true
                        found.reported = true
                    }
                    continue
                }

                placed[keyOf(newTri)] = newTri
                queue.addLast(newTri)
                newTri.reported = false
            }
        }
    }

    // ----------------------------------------------------------------
    // Public entry points.
    // ----------------------------------------------------------------

    fun paramsInvalid(params: PatchParams, which: Int): String? {
        if (params.coords.isEmpty()) return "expected at least one coordinate"
        for (i in params.coords.indices) {
            if (!validLetter(params.coords[i], which)) return "invalid coordinate letter"
            if (i > 0) {
                val parents = validParents(params.coords[i - 1]) ?: return "invalid pair of consecutive coordinates"
                if (!parents.contains(params.coords[i])) {
                    return "invalid pair of consecutive coordinates"
                }
            }
        }
        return null
    }

    /** `penrose_tiling_randomise`. */
    fun randomise(which: Int, w: Int, h: Int, random: RandomState): PatchParams {
        val bounds = setBounds(w, h)
        val prototype = Coords()
        prototype.makeSpace(1)
        prototype.c[0] = chooseRandom(startingTiles(which), random)
        prototype.nc = 1
        val ctx = Context(
            random = random,
            prototype = prototype,
            startVertex = random.upto(3).toInt(),
            orientation = random.upto(10).toInt(),
        )
        generateTriangles(ctx, bounds) { }
        return PatchParams(
            orientation = ctx.orientation,
            startVertex = ctx.startVertex,
            coords = prototype.c.copyOf(prototype.nc),
        )
    }

    /** `penrose_tiling_generate`: one 16-integer vertex array per tile. */
    fun generate(params: PatchParams, w: Int, h: Int, tile: (IntArray) -> Unit) {
        val bounds = setBounds(w, h)
        val prototype = Coords()
        prototype.makeSpace(params.coords.size)
        params.coords.copyInto(prototype.c)
        prototype.nc = params.coords.size
        val ctx = Context(
            random = null,
            prototype = prototype,
            startVertex = params.startVertex,
            orientation = params.orientation,
        )

        // The C output callback adds the bounds offset and splits each
        // point into coefficients of 1 and sqrt(5).
        val xoff = w / 2
        val yoff = h / 2
        generateTriangles(ctx, bounds) { vertices ->
            val coords = IntArray(16)
            for (i in 0 until NVERTICES) {
                val x = pointX(vertices[i])
                val y = pointY(vertices[i])
                coords[4 * i + 0] = x.c1 + xoff
                coords[4 * i + 1] = x.cr5
                coords[4 * i + 2] = y.c1 + yoff
                coords[4 * i + 3] = y.cr5
            }
            tile(coords)
        }
    }
}

/**
 * A port of upstream `penrose-legacy.c`: the old Penrose generator.
 *
 * Only old game descriptions reach this code. It subdivides a large
 * triangle and clips the result to a rectangle, using the same
 * floating-point vertex formula as upstream.
 */
internal object PenroseLegacy {

    private const val PHI = 1.6180339887
    private const val INCIRCLE_RADIUS = 0.22426
    private const val COS54 = 0.5877852
    private const val SIN54 = 0.8090169
    private const val COS18 = 0.9510565
    private const val SIN18 = 0.3090169

    class Vector(var a: Int, var b: Int, var c: Int, var d: Int)

    private fun origin() = Vector(0, 0, 0, 0)

    private fun vx(v: Vector): Double =
        (v.a + v.d) * COS54 + (v.b + v.c) * COS18

    private fun vy(v: Vector): Double =
        (v.a - v.d) * SIN54 + (v.b - v.c) * SIN18

    private fun trans(v: Vector, t: Vector): Vector =
        Vector(v.a + t.a, v.b + t.b, v.c + t.c, v.d + t.d)

    private fun rotate36(v: Vector): Vector = Vector(
        -v.d,
        v.d + v.a,
        -v.d + v.b,
        v.d + v.c,
    )

    private fun rotate(v: Vector, angle: Int): Vector {
        var ang = angle
        var out = v
        while (ang < 0) ang += 360
        ang = 360 - ang
        repeat(ang / 36) { out = rotate36(out) }
        return out
    }

    private fun growPhi(v: Vector): Vector = Vector(
        v.a + v.b - v.d,
        v.c + v.d,
        v.a + v.b,
        v.c + v.d - v.a,
    )

    private fun shrinkPhi(v: Vector): Vector = Vector(
        v.b - v.d,
        v.c + v.d - v.b,
        v.a + v.b - v.c,
        v.c - v.a,
    )

    private fun xformCoord(vo: Vector, shrink: Int, vtrans: Vector, angle: Int): Vector {
        var v = vo
        if (shrink < 0) v = shrinkPhi(v) else if (shrink > 0) v = growPhi(v)
        v = rotate(v, angle)
        return trans(v, vtrans)
    }

    fun sideLength(startSize: Double, depth: Int): Double =
        startSize / Math.pow(PHI, depth.toDouble())

    /**
     * Round half away from zero, as `round_int_nearest_away` does.
     */
    private fun roundNearestAway(r: Double): Int =
        if (r > 0.0) Math.floor(r + 0.5).toInt() else Math.ceil(r - 0.5).toInt()

    /** Fills `xs` and `ys` with the rounded vertex coordinates. */
    private fun tileVertices(vs: Array<Vector>, n: Int, xs: IntArray, ys: IntArray) {
        for (i in 0 until n) {
            xs[i] = roundNearestAway(vx(vs[i]))
            ys[i] = roundNearestAway(vy(vs[i]))
        }
    }

    private fun p2Large(
        state: State,
        depth: Int,
        flip: Int,
        vOrig: Vector,
        vEdge: Vector,
    ) {
        if (flip > 0) {
            val vs = arrayOf(
                vOrig,
                xformCoord(vEdge, 0, vOrig, -36),
                xformCoord(vEdge, 0, vOrig, 0),
                xformCoord(vEdge, 0, vOrig, 36),
            )
            state.newTile(vs, 4, depth)
        }
        if (depth >= state.maxDepth) return

        val vvOrig = trans(vOrig, rotate(vEdge, -36 * flip))
        val vvEdge = rotate(vEdge, 108 * flip)

        p2Small(state, depth + 1, flip, vOrig, shrinkPhi(vEdge))
        p2Large(state, depth + 1, flip, vvOrig, shrinkPhi(vvEdge))
        p2Large(state, depth + 1, -flip, vvOrig, shrinkPhi(vvEdge))
    }

    private fun p2Small(
        state: State,
        depth: Int,
        flip: Int,
        vOrig: Vector,
        vEdge: Vector,
    ) {
        if (flip > 0) {
            val vs = arrayOf(
                vOrig,
                xformCoord(vEdge, 0, vOrig, -72),
                xformCoord(vEdge, -1, vOrig, -36),
                xformCoord(vEdge, 0, vOrig, 0),
            )
            state.newTile(vs, 4, depth)
        }
        if (depth >= state.maxDepth) return

        val vvOrig = trans(vOrig, vEdge)
        p2Large(state, depth + 1, -flip, vOrig, shrinkPhi(rotate(vEdge, -36 * flip)))
        p2Small(state, depth + 1, flip, vvOrig, shrinkPhi(rotate(vEdge, -144 * flip)))
    }

    private fun p3Large(
        state: State,
        depth: Int,
        flip: Int,
        vOrig: Vector,
        vEdge: Vector,
    ) {
        if (flip > 0) {
            val vs = arrayOf(
                vOrig,
                xformCoord(vEdge, 0, vOrig, -36),
                xformCoord(vEdge, 1, vOrig, 0),
                xformCoord(vEdge, 0, vOrig, 36),
            )
            state.newTile(vs, 4, depth)
        }
        if (depth >= state.maxDepth) return

        var vvOrig = trans(vOrig, vEdge)
        p3Large(state, depth + 1, -flip, vvOrig, shrinkPhi(rotate(vEdge, 180)))
        p3Small(state, depth + 1, flip, vvOrig, shrinkPhi(rotate(vEdge, -108 * flip)))

        vvOrig = trans(vOrig, growPhi(vEdge))
        p3Large(state, depth + 1, flip, vvOrig, shrinkPhi(rotate(vEdge, -144 * flip)))
    }

    private fun p3Small(
        state: State,
        depth: Int,
        flip: Int,
        vOrig: Vector,
        vEdge: Vector,
    ) {
        if (flip > 0) {
            val vs = arrayOf(
                vOrig,
                xformCoord(vEdge, 0, vOrig, -36),
                Vector(0, 0, 0, 0),
                xformCoord(vEdge, 0, vOrig, 0),
            )
            vs[2] = xformCoord(vEdge, 0, vs[3], -36)
            state.newTile(vs, 4, depth)
        }
        if (depth >= state.maxDepth) return

        val vvOrig = trans(vOrig, vEdge)
        p3Large(state, depth + 1, -flip, vvOrig, shrinkPhi(rotate(vEdge, 180)))
        p3Small(state, depth + 1, flip, vvOrig, shrinkPhi(rotate(vEdge, -108 * flip)))
    }

    /** The tile callback receives rounded vertices and the recursion depth. */
    class State(
        var startSize: Int = 0,
        var maxDepth: Int = 0,
        val newTile: (vs: Array<Vector>, n: Int, depth: Int) -> Unit,
    )

    /** `penrose_legacy_calculate_size`. */
    fun calculateSize(
        which: Int,
        tilesizeIn: Int,
        width: Int,
        height: Int,
    ): Triple<Double, Int, Int> {
        var tilesize = tilesizeIn
        var n = 0
        if (which == PenroseTiling.P2) tilesize = tilesize * 3 / 2
        else tilesize = tilesize * 5 / 4

        val requiredRadius = tilesize * 3.11 * Math.sqrt((width * width + height * height).toDouble())
        var size = tilesize.toDouble()
        while (size * INCIRCLE_RADIUS < requiredRadius) {
            n++
            size *= PHI
        }
        return Triple(requiredRadius, size.toInt(), n)
    }

    /**
     * Runs the legacy generator. [newTile] receives each candidate tile
     * with its depth; the caller decides whether to keep it.
     */
    fun generate(
        which: Int,
        startSize: Int,
        maxDepth: Int,
        angle: Int,
        newTile: (vs: Array<Vector>, n: Int, depth: Int) -> Unit,
    ) {
        val state = State(startSize = startSize, maxDepth = maxDepth, newTile = newTile)
        var vo = origin()
        var vb = origin()
        vo.b = -startSize
        vo.c = -startSize
        vo = shrinkPhi(shrinkPhi(vo))
        vb.b = startSize
        vo = rotate(vo, angle)
        vb = rotate(vb, angle)
        if (which == PenroseTiling.P2) {
            p2Large(state, 0, 1, vo, vb)
        } else {
            p3Small(state, 0, 1, vo, vb)
        }
    }

    /** Rounded vertices of one tile, for the caller that clips to a rectangle. */
    fun roundedVertices(vs: Array<Vector>, n: Int, xs: IntArray, ys: IntArray) {
        tileVertices(vs, n, xs, ys)
    }
}
