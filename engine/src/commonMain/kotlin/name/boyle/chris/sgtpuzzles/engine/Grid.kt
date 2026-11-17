package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `grid.c` and `grid.h`: planar grids made of
 * faces, edges and dots, plus the generators for all eighteen grid
 * types.
 *
 * The C code is the source of truth. Dot, edge and face indices,
 * coordinates, bounding boxes, the RNG draw order, and every
 * description string must match, because `loopy` and `pearl` game ids
 * encode them. Floating-point work is limited to `findIncentre` and the
 * legacy Penrose generator, the same places upstream uses it.
 *
 * A grid is immutable after [Grid.new]. The builders below mutate
 * their own lists and call [makeConsistent] once.
 */
enum class GridType(val backend: String) {
    SQUARE("square"),
    HONEYCOMB("honeycomb"),
    TRIANGULAR("triangular"),
    SNUBSQUARE("snubsquare"),
    CAIRO("cairo"),
    GREATHEXAGONAL("greathexagonal"),
    KAGOME("kagome"),
    OCTAGONAL("octagonal"),
    KITES("kites"),
    FLORET("floret"),
    DODECAGONAL("dodecagonal"),
    GREATDODECAGONAL("greatdodecagonal"),
    GREATGREATDODECAGONAL("greatgreatdodecagonal"),
    COMPASSDODECAGONAL("compassdodecagonal"),
    PENROSE_P2("penrose_p2_kite"),
    PENROSE_P3("penrose_p3_thick"),
    HATS("hats"),
    SPECTRES("spectres"),
    ;

    companion object {
        fun fromBackend(name: String): GridType? = entries.firstOrNull { it.backend == name }
    }
}

/** An integer point in grid coordinates. */
data class GridPoint(val x: Int, val y: Int)

/**
 * A face of the grid. [dots] and [edges] run clockwise around the face.
 * [incentre] is filled in by [Grid.findIncentre].
 */
class GridFace(
    var index: Int,
    val order: Int,
    var edges: Array<GridEdge?> = arrayOfNulls(order),
    var dots: Array<GridDot?> = arrayOfNulls(order),
    var incentre: GridPoint? = null,
) {
    val hasIncentre: Boolean get() = incentre != null

    fun dot(position: Int): GridDot = dots[position]!!
    fun edge(position: Int): GridEdge = edges[position]!!
}

/** An edge between two dots, with either face null for the infinite face. */
class GridEdge(
    var index: Int,
    val dot1: GridDot,
    val dot2: GridDot,
    var face1: GridFace?,
    var face2: GridFace?,
)

/** A dot: a vertex where edges and faces meet. */
class GridDot(
    var index: Int,
    var order: Int = 0,
    var edges: Array<GridEdge?> = emptyArray(),
    var faces: Array<GridFace?> = emptyArray(),
    var x: Int = 0,
    var y: Int = 0,
)

/**
 * A complete grid. Immutable once built.
 */
class Grid(
    val type: GridType,
    val faces: List<GridFace>,
    val edges: List<GridEdge>,
    val dots: List<GridDot>,
    val lowestX: Int,
    val lowestY: Int,
    val highestX: Int,
    val highestY: Int,
    val tileSize: Int,
) {

    companion object {

        // ------------------------------------------------------------
        // Public API.
        // ------------------------------------------------------------

        /**
         * `grid_new_desc`. Returns null for the types without a
         * description string.
         */
        fun newDesc(
            type: GridType,
            width: Int,
            height: Int,
            random: RandomState,
        ): String? = when (type) {
            GridType.PENROSE_P2, GridType.PENROSE_P3 -> {
                val which = if (type == GridType.PENROSE_P2) PenroseTiling.P2 else PenroseTiling.P3
                val size = apiSizePenrose(width, height, which)
                val params = PenroseTiling.randomise(which, size.h, size.w, random)
                buildString {
                    append('0' + params.orientation)
                    append('0' + params.startVertex)
                    append(params.coords)
                }
            }
            GridType.HATS -> {
                val params = HatTiling.randomise(width, height, random)
                buildString {
                    for (coordinate in params.coords) append(coordinate).append(',')
                    append(params.finalMetatile)
                }
            }
            GridType.SPECTRES -> {
                val params = SpectreTiling.randomise(
                    width * SPECTRE_SQUARELEN,
                    height * SPECTRE_SQUARELEN,
                    random,
                )
                buildString {
                    append(
                        if (params.orientation < 10) {
                            '0' + params.orientation
                        } else {
                            'A' + (params.orientation - 10)
                        },
                    )
                    for (coordinate in params.coords) append('0' + coordinate)
                    append(params.finalHex)
                }
            }
            GridType.TRIANGULAR -> "0"
            else -> null
        }

        /** `grid_validate_params`. Returns an error message, or null. */
        fun validateParams(type: GridType, width: Int, height: Int): String? {
            if (width <= 0 || height <= 0) {
                return "Width and height must both be positive"
            }
            return when (type) {
                GridType.SQUARE -> validateSquare(width, height)
                GridType.HONEYCOMB -> validateHoneycomb(width, height)
                GridType.TRIANGULAR -> validateTriangular(width, height)
                GridType.SNUBSQUARE -> validateSnubsquare(width, height)
                GridType.CAIRO -> validateCairo(width, height)
                GridType.GREATHEXAGONAL -> validateGreathexagonal(width, height)
                GridType.KAGOME -> validateKagome(width, height)
                GridType.OCTAGONAL -> validateOctagonal(width, height)
                GridType.KITES -> validateKites(width, height)
                GridType.FLORET -> validateFloret(width, height)
                GridType.DODECAGONAL -> validateDodecagonal(width, height)
                GridType.GREATDODECAGONAL -> validateGreatdodecagonal(width, height)
                GridType.GREATGREATDODECAGONAL ->
                    validateGreatgreatdodecagonal(width, height)
                GridType.COMPASSDODECAGONAL -> validateCompassdodecagonal(width, height)
                GridType.PENROSE_P2, GridType.PENROSE_P3 -> validatePenrose(width, height)
                GridType.HATS -> validateHats(width, height)
                GridType.SPECTRES -> validateSpectres(width, height)
            }
        }

        /** `grid_validate_desc`. Returns an error message, or null. */
        fun validateDesc(
            type: GridType,
            width: Int,
            height: Int,
            desc: String?,
        ): String? = when (type) {
            GridType.PENROSE_P2, GridType.PENROSE_P3 ->
                validateDescPenrose(type, width, height, desc)
            GridType.HATS -> validateDescHats(desc)
            GridType.SPECTRES -> validateDescSpectres(desc)
            GridType.TRIANGULAR -> validateDescTriangular(desc)
            else -> if (desc != null) {
                "Grid description strings not used with this grid type"
            } else {
                null
            }
        }

        /** `grid_new`. Fails loudly when the description is invalid. */
        fun new(type: GridType, width: Int, height: Int, desc: String?): Grid {
            val error = validateDesc(type, width, height, desc)
            require(error == null) { "invalid grid description: $error" }
            return when (type) {
                GridType.SQUARE -> newSquare(width, height)
                GridType.HONEYCOMB -> newHoneycomb(width, height)
                GridType.TRIANGULAR -> newTriangular(width, height, desc)
                GridType.SNUBSQUARE -> newSnubsquare(width, height)
                GridType.CAIRO -> newCairo(width, height)
                GridType.GREATHEXAGONAL -> newGreathexagonal(width, height)
                GridType.KAGOME -> newKagome(width, height)
                GridType.OCTAGONAL -> newOctagonal(width, height)
                GridType.KITES -> newKites(width, height)
                GridType.FLORET -> newFloret(width, height)
                GridType.DODECAGONAL -> newDodecagonal(width, height)
                GridType.GREATDODECAGONAL -> newGreatdodecagonal(width, height)
                GridType.GREATGREATDODECAGONAL -> newGreatgreatdodecagonal(width, height)
                GridType.COMPASSDODECAGONAL -> newCompassdodecagonal(width, height)
                GridType.PENROSE_P2 -> newPenroseP2(width, height, desc)
                GridType.PENROSE_P3 -> newPenroseP3(width, height, desc)
                GridType.HATS -> newHats(width, height, desc)
                GridType.SPECTRES -> newSpectres(width, height, desc)
            }
        }

        /** `grid_compute_size`. */
        fun computeSize(type: GridType, width: Int, height: Int): GridSize = when (type) {
            GridType.SQUARE -> GridSize(SQUARE_TILESIZE, width * SQUARE_TILESIZE, height * SQUARE_TILESIZE)
            GridType.HONEYCOMB -> GridSize(
                HONEY_TILESIZE,
                3 * HONEY_A * (width - 1) + 4 * HONEY_A,
                2 * HONEY_B * (height - 1) + 3 * HONEY_B,
            )
            GridType.TRIANGULAR -> GridSize(
                TRIANGLE_TILESIZE,
                (width + 1) * 2 * TRIANGLE_VEC_X,
                height * TRIANGLE_VEC_Y,
            )
            GridType.SNUBSQUARE -> GridSize(
                SNUBSQUARE_TILESIZE,
                (SNUBSQUARE_A + SNUBSQUARE_B) * (width - 1) + SNUBSQUARE_A + SNUBSQUARE_B,
                (SNUBSQUARE_A + SNUBSQUARE_B) * (height - 1) + SNUBSQUARE_A + SNUBSQUARE_B,
            )
            GridType.CAIRO -> GridSize(
                CAIRO_TILESIZE,
                2 * CAIRO_B * (width - 1) + 2 * CAIRO_B,
                2 * CAIRO_B * (height - 1) + 2 * CAIRO_B,
            )
            GridType.GREATHEXAGONAL -> GridSize(
                GREATHEX_TILESIZE,
                (3 * GREATHEX_A + GREATHEX_B) * (width - 1) + 4 * GREATHEX_A,
                (2 * GREATHEX_A + 2 * GREATHEX_B) * (height - 1) + 3 * GREATHEX_B + GREATHEX_A,
            )
            GridType.KAGOME -> GridSize(
                KAGOME_TILESIZE,
                4 * KAGOME_A * (width - 1) + 6 * KAGOME_A,
                2 * KAGOME_B * (height - 1) + 2 * KAGOME_B,
            )
            GridType.OCTAGONAL -> GridSize(
                OCTAGONAL_TILESIZE,
                (2 * OCTAGONAL_A + OCTAGONAL_B) * width,
                (2 * OCTAGONAL_A + OCTAGONAL_B) * height,
            )
            GridType.KITES -> GridSize(
                KITE_TILESIZE,
                4 * KITE_B * width + 2 * KITE_B,
                6 * KITE_A * (height - 1) + 8 * KITE_A,
            )
            GridType.FLORET -> {
                val px = FLORET_PX
                val py = FLORET_PY
                val qx = 4 * px / 5
                val qy = -py * 2
                val ry = qy - py
                var yextent = (5 * qy - 4 * py) * (height - 1) + 4 * ry + 2 * qy
                if (height == 1) yextent += (5 * qy - 4 * py) / 2
                GridSize(
                    FLORET_TILESIZE,
                    (6 * px + 3 * qx) / 2 * (width - 1) + 4 * px + 2 * qx,
                    yextent,
                )
            }
            GridType.DODECAGONAL -> GridSize(
                DODEC_TILESIZE,
                (4 * DODEC_A + 2 * DODEC_B) * (width - 1) + 3 * (2 * DODEC_A + DODEC_B),
                (3 * DODEC_A + 2 * DODEC_B) * (height - 1) + 2 * (2 * DODEC_A + DODEC_B),
            )
            GridType.GREATDODECAGONAL -> GridSize(
                DODEC_TILESIZE,
                (6 * DODEC_A + 2 * DODEC_B) * (width - 1) + 2 * (2 * DODEC_A + DODEC_B) + 3 * DODEC_A + DODEC_B,
                (3 * DODEC_A + 3 * DODEC_B) * (height - 1) + 2 * (2 * DODEC_A + DODEC_B),
            )
            GridType.GREATGREATDODECAGONAL -> GridSize(
                DODEC_TILESIZE,
                (4 * DODEC_A + 4 * DODEC_B) * (width - 1) + 2 * (2 * DODEC_A + DODEC_B) + 2 * DODEC_A + 2 * DODEC_B,
                (6 * DODEC_A + 2 * DODEC_B) * (height - 1) + 2 * (2 * DODEC_A + DODEC_B),
            )
            GridType.COMPASSDODECAGONAL -> GridSize(
                DODEC_TILESIZE,
                (4 * DODEC_A + 2 * DODEC_B) * width,
                (4 * DODEC_A + 2 * DODEC_B) * height,
            )
            GridType.PENROSE_P2, GridType.PENROSE_P3, GridType.HATS, GridType.SPECTRES ->
                computeSizeAperiodic(type, width, height)
        }

        /**
         * `grid_nearest_edge`: the edge nearest to a click, or null when
         * no edge is close enough.
         */
        fun nearestEdge(grid: Grid, x: Int, y: Int): GridEdge? {
            var bestEdge: GridEdge? = null
            var bestDistance = 0.0

            for (edge in grid.edges) {
                val e2 = sq(edge.dot1.x.toLong() - edge.dot2.x) +
                    sq(edge.dot1.y.toLong() - edge.dot2.y)
                val a2 = sq(edge.dot1.x.toLong() - x) + sq(edge.dot1.y.toLong() - y)
                val b2 = sq(edge.dot2.x.toLong() - x) + sq(edge.dot2.y.toLong() - y)
                if (a2 >= e2 + b2) continue
                if (b2 >= e2 + a2) continue

                val dist = pointLineDistance(
                    x.toLong(),
                    y.toLong(),
                    edge.dot1.x.toLong(),
                    edge.dot1.y.toLong(),
                    edge.dot2.x.toLong(),
                    edge.dot2.y.toLong(),
                )
                if (4 * sq(dist) > e2.toDouble()) continue

                if (bestEdge == null || dist < bestDistance) {
                    bestEdge = edge
                    bestDistance = dist
                }
            }
            return bestEdge
        }

        /**
         * `grid_find_incentre`: the centre of the largest circle inside
         * the face. The result is cached in the face.
         */
        fun findIncentre(face: GridFace) {
            if (face.hasIncentre) return

            val edgedot1 = arrayOfNulls<GridDot>(3)
            val edgedot2 = arrayOfNulls<GridDot>(3)
            val dots = arrayOfNulls<GridDot>(3)
            var nedges = 0
            var ndots = 0
            var bestdist = 0.0
            var xbest = 0.0
            var ybest = 0.0
            val order = face.order

            var i = 0
            while (i + 2 < 2 * order) {
                if (i < order) {
                    edgedot1[nedges] = face.dots[i]
                    edgedot2[nedges] = face.dots[(i + 1) % order]
                    nedges++
                } else {
                    dots[ndots++] = face.dots[i - order]
                }

                var j = i + 1
                while (j + 1 < 2 * order) {
                    if (j < order) {
                        edgedot1[nedges] = face.dots[j]
                        edgedot2[nedges] = face.dots[(j + 1) % order]
                        nedges++
                    } else {
                        dots[ndots++] = face.dots[j - order]
                    }

                    var k = j + 1
                    while (k < 2 * order) {
                        val cx = DoubleArray(2)
                        val cy = DoubleArray(2)
                        var cn = 0

                        if (k < order) {
                            edgedot1[nedges] = face.dots[k]
                            edgedot2[nedges] = face.dots[(k + 1) % order]
                            nedges++
                        } else {
                            dots[ndots++] = face.dots[k - order]
                        }

                        when (nedges) {
                            3 -> {
                                val matrix = DoubleArray(9)
                                val vector = DoubleArray(3)
                                val vector2 = DoubleArray(3)
                                for (m in 0 until 3) {
                                    val x1 = edgedot1[m]!!.x
                                    val x2 = edgedot2[m]!!.x
                                    val y1 = edgedot1[m]!!.y
                                    val y2 = edgedot2[m]!!.y
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    matrix[3 * m + 0] = dy.toDouble()
                                    matrix[3 * m + 1] = -dx.toDouble()
                                    matrix[3 * m + 2] = -Math.sqrt((dx * dx + dy * dy).toDouble())
                                    vector[m] = x1.toDouble() * dy - y1.toDouble() * dx
                                }
                                if (solve3x3Matrix(matrix, vector, vector2)) {
                                    cx[cn] = vector2[0]
                                    cy[cn] = vector2[1]
                                    cn++
                                }
                            }
                            2 -> {
                                val eqs = Array(2) { DoubleArray(4) }
                                val eq = DoubleArray(3)
                                val xt = DoubleArray(2)
                                val yt = DoubleArray(2)
                                val rt = DoubleArray(2)
                                val q = DoubleArray(3)

                                for (m in 0 until 2) {
                                    val x1 = edgedot1[m]!!.x
                                    val x2 = edgedot2[m]!!.x
                                    val y1 = edgedot1[m]!!.y
                                    val y2 = edgedot2[m]!!.y
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    eqs[m][0] = dy.toDouble()
                                    eqs[m][1] = -dx.toDouble()
                                    eqs[m][2] = -Math.sqrt((dx * dx + dy * dy).toDouble())
                                    eqs[m][3] = x1.toDouble() * dy - y1.toDouble() * dx
                                }

                                eq[0] = eqs[0][0] * eqs[1][2] - eqs[1][0] * eqs[0][2]
                                eq[1] = eqs[0][1] * eqs[1][2] - eqs[1][1] * eqs[0][2]
                                eq[2] = eqs[0][3] * eqs[1][2] - eqs[1][3] * eqs[0][2]

                                if (Math.abs(eq[0]) < Math.abs(eq[1])) {
                                    xt[0] = 1.0
                                    xt[1] = 0.0
                                    yt[0] = -eq[0] / eq[1]
                                    yt[1] = eq[2] / eq[1]
                                } else {
                                    yt[0] = 1.0
                                    yt[1] = 0.0
                                    xt[0] = -eq[1] / eq[0]
                                    xt[1] = eq[2] / eq[0]
                                }

                                rt[0] = -(eqs[0][0] * xt[0] + eqs[0][1] * yt[0]) / eqs[0][2]
                                rt[1] = (eqs[0][3] - eqs[0][0] * xt[1] -
                                    eqs[0][1] * yt[1]) / eqs[0][2]

                                q[0] = -rt[0] * rt[0]
                                q[1] = -2 * rt[0] * rt[1]
                                q[2] = -rt[1] * rt[1]
                                q[0] += xt[0] * xt[0]
                                q[1] += 2 * xt[0] * (xt[1] - dots[0]!!.x)
                                q[2] += (xt[1] - dots[0]!!.x) * (xt[1] - dots[0]!!.x)
                                q[0] += yt[0] * yt[0]
                                q[1] += 2 * yt[0] * (yt[1] - dots[0]!!.y)
                                q[2] += (yt[1] - dots[0]!!.y) * (yt[1] - dots[0]!!.y)

                                var disc = q[1] * q[1] - 4 * q[0] * q[2]
                                if (disc >= 0) {
                                    disc = Math.sqrt(disc)
                                    var t = (-q[1] + disc) / (2 * q[0])
                                    cx[cn] = xt[0] * t + xt[1]
                                    cy[cn] = yt[0] * t + yt[1]
                                    cn++
                                    t = (-q[1] - disc) / (2 * q[0])
                                    cx[cn] = xt[0] * t + xt[1]
                                    cy[cn] = yt[0] * t + yt[1]
                                    cn++
                                }
                            }
                            1 -> {
                                val xt = DoubleArray(2)
                                val yt = DoubleArray(2)
                                val rt = DoubleArray(2)
                                val q = DoubleArray(3)
                                var halfsep: Double

                                run {
                                    val x1 = dots[0]!!.x
                                    val x2 = dots[1]!!.x
                                    val y1 = dots[0]!!.y
                                    val y2 = dots[1]!!.y
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    val d = Math.sqrt((dx.toDouble() * dx + dy.toDouble() * dy))
                                    xt[1] = (x1 + x2) / 2.0
                                    yt[1] = (y1 + y2) / 2.0
                                    xt[0] = -dy / d
                                    yt[0] = dx / d
                                    halfsep = 0.5 * d
                                }

                                run {
                                    val x1 = edgedot1[0]!!.x
                                    val x2 = edgedot2[0]!!.x
                                    val y1 = edgedot1[0]!!.y
                                    val y2 = edgedot2[0]!!.y
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    val d = Math.sqrt((dx.toDouble() * dx + dy.toDouble() * dy))
                                    rt[0] = (xt[0] * dy - yt[0] * dx) / d
                                    rt[1] = ((xt[1] - x1) * dy - (yt[1] - y1) * dx) / d
                                }

                                q[0] = rt[0] * rt[0]
                                q[1] = 2 * rt[0] * rt[1]
                                q[2] = rt[1] * rt[1]
                                q[0] -= 1.0
                                q[2] -= halfsep * halfsep

                                var disc = q[1] * q[1] - 4 * q[0] * q[2]
                                if (disc >= 0) {
                                    disc = Math.sqrt(disc)
                                    var t = (-q[1] + disc) / (2 * q[0])
                                    cx[cn] = xt[0] * t + xt[1]
                                    cy[cn] = yt[0] * t + yt[1]
                                    cn++
                                    t = (-q[1] - disc) / (2 * q[0])
                                    cx[cn] = xt[0] * t + xt[1]
                                    cy[cn] = yt[0] * t + yt[1]
                                    cn++
                                }
                            }
                            else -> {
                                val matrix = DoubleArray(4)
                                val vector = DoubleArray(2)
                                val vector2 = DoubleArray(2)
                                for (m in 0 until 2) {
                                    val x1 = dots[m]!!.x
                                    val x2 = dots[m + 1]!!.x
                                    val y1 = dots[m]!!.y
                                    val y2 = dots[m + 1]!!.y
                                    val dx = x2 - x1
                                    val dy = y2 - y1
                                    matrix[2 * m + 0] = (2 * dx).toDouble()
                                    matrix[2 * m + 1] = (2 * dy).toDouble()
                                    vector[m] = (dx * dx + dy * dy).toDouble() +
                                        2.0 * x1 * dx + 2.0 * y1 * dy
                                }
                                if (solve2x2Matrix(matrix, vector, vector2)) {
                                    cx[cn] = vector2[0]
                                    cy[cn] = vector2[1]
                                    cn++
                                }
                            }
                        }

                        for (m in 0 until cn) {
                            val x = cx[m]
                            val y = cy[m]

                            var inside = false
                            for (e in 0 until order) {
                                val xs = face.edges[e]!!.dot1.x
                                val xe = face.edges[e]!!.dot2.x
                                val ys = face.edges[e]!!.dot1.y
                                val ye = face.edges[e]!!.dot2.y
                                if ((y >= ys && y < ye) || (y >= ye && y < ys)) {
                                    var num = xe - xs
                                    var denom = ye - ys
                                    if (denom < 0) {
                                        num = -num
                                        denom = -denom
                                    }
                                    if ((x - xs) * denom >= (y - ys) * num) inside = !inside
                                }
                            }

                            if (inside) {
                                var mindist = Double.MAX_VALUE

                                for (d in 0 until order) {
                                    val xp = face.dots[d]!!.x
                                    val yp = face.dots[d]!!.y
                                    val dx = x - xp
                                    val dy = y - yp
                                    val dist = dx * dx + dy * dy
                                    if (mindist > dist) mindist = dist
                                }

                                for (e in 0 until order) {
                                    val xs = face.edges[e]!!.dot1.x
                                    val xe = face.edges[e]!!.dot2.x
                                    val ys = face.edges[e]!!.dot1.y
                                    val ye = face.edges[e]!!.dot2.y

                                    val edx = xe - xs
                                    val edy = ye - ys
                                    val pdx = x - xs
                                    val pdy = y - ys
                                    val pde = pdx * edx + pdy * edy
                                    val ede = (edx.toLong() * edx + edy.toLong() * edy).toDouble()
                                    if (0.0 < pde && pde < ede) {
                                        val pdre = pdx * edy - pdy * edx
                                        val sqlen = pdre * pdre / ede
                                        if (mindist > sqlen) mindist = sqlen
                                    }
                                }

                                if (bestdist < mindist) {
                                    bestdist = mindist
                                    xbest = x
                                    ybest = y
                                }
                            }
                        }

                        if (k < order) nedges-- else ndots--
                        k++
                    }
                    if (j < order) nedges-- else ndots--
                    j++
                }
                if (i < order) nedges-- else ndots--
                i++
            }

            check(bestdist > 0) { "grid face has no incentre" }
            face.incentre = GridPoint((xbest + 0.5).toInt(), (ybest + 0.5).toInt())
        }

        // ------------------------------------------------------------
        // Helpers and constants.
        // ------------------------------------------------------------

        internal const val SQUARE_TILESIZE = 20
        internal const val HONEY_TILESIZE = 45
        internal const val HONEY_A = 15
        internal const val HONEY_B = 26
        internal const val TRIANGLE_TILESIZE = 18
        internal const val TRIANGLE_VEC_X = 15
        internal const val TRIANGLE_VEC_Y = 26
        internal const val SNUBSQUARE_TILESIZE = 18
        internal const val SNUBSQUARE_A = 15
        internal const val SNUBSQUARE_B = 26
        internal const val CAIRO_TILESIZE = 40
        internal const val CAIRO_A = 14
        internal const val CAIRO_B = 31
        internal const val GREATHEX_TILESIZE = 18
        internal const val GREATHEX_A = 15
        internal const val GREATHEX_B = 26
        internal const val KAGOME_TILESIZE = 18
        internal const val KAGOME_A = 15
        internal const val KAGOME_B = 26
        internal const val OCTAGONAL_TILESIZE = 40
        internal const val OCTAGONAL_A = 29
        internal const val OCTAGONAL_B = 41
        internal const val KITE_TILESIZE = 40
        internal const val KITE_A = 15
        internal const val KITE_B = 26
        internal const val FLORET_TILESIZE = 150
        internal const val FLORET_PX = 75
        internal const val FLORET_PY = -26
        internal const val DODEC_TILESIZE = 26
        internal const val DODEC_A = 15
        internal const val DODEC_B = 26
        internal const val PENROSE_TILESIZE = 100
        internal const val PENROSE_XUNIT_P2 = 37
        internal const val PENROSE_YUNIT_P2 = 44
        internal const val PENROSE_XUNIT_P3 = 30
        internal const val PENROSE_YUNIT_P3 = 35
        internal const val HATS_TILESIZE = 32
        internal const val HATS_XSQUARELEN = 4
        internal const val HATS_YSQUARELEN = 6
        internal const val HATS_XUNIT = 14
        internal const val HATS_YUNIT = 8
        internal const val SPECTRE_TILESIZE = 32
        internal const val SPECTRE_SQUARELEN = 7
        internal const val SPECTRE_UNIT = 8

        private fun sq(x: Long): Long = x * x
        private fun sq(x: Double): Double = x * x

        private fun pointLineDistance(
            px: Long,
            py: Long,
            ax: Long,
            ay: Long,
            bx: Long,
            by: Long,
        ): Double {
            var det = ax * by - bx * ay + bx * py - px * by + px * ay - ax * py
            if (det < 0) det = -det
            val len = Math.sqrt((sq(ax - bx) + sq(ay - by)).toDouble())
            return det / len
        }

        private fun solve2x2Matrix(
            mx: DoubleArray,
            vin: DoubleArray,
            vout: DoubleArray,
        ): Boolean {
            val inv = DoubleArray(4)
            val det = mx[0] * mx[3] - mx[1] * mx[2]
            if (det == 0.0) return false
            inv[0] = mx[3] / det
            inv[1] = -mx[1] / det
            inv[2] = -mx[2] / det
            inv[3] = mx[0] / det
            vout[0] = inv[0] * vin[0] + inv[1] * vin[1]
            vout[1] = inv[2] * vin[0] + inv[3] * vin[1]
            return true
        }

        private fun solve3x3Matrix(
            mx: DoubleArray,
            vin: DoubleArray,
            vout: DoubleArray,
        ): Boolean {
            val inv = DoubleArray(9)
            val det = mx[0] * mx[4] * mx[8] + mx[1] * mx[5] * mx[6] +
                mx[2] * mx[3] * mx[7] - mx[0] * mx[5] * mx[7] -
                mx[1] * mx[3] * mx[8] - mx[2] * mx[4] * mx[6]
            if (det == 0.0) return false
            inv[0] = (mx[4] * mx[8] - mx[5] * mx[7]) / det
            inv[1] = (mx[2] * mx[7] - mx[1] * mx[8]) / det
            inv[2] = (mx[1] * mx[5] - mx[2] * mx[4]) / det
            inv[3] = (mx[5] * mx[6] - mx[3] * mx[8]) / det
            inv[4] = (mx[0] * mx[8] - mx[2] * mx[6]) / det
            inv[5] = (mx[2] * mx[3] - mx[0] * mx[5]) / det
            inv[6] = (mx[3] * mx[7] - mx[4] * mx[6]) / det
            inv[7] = (mx[1] * mx[6] - mx[0] * mx[7]) / det
            inv[8] = (mx[0] * mx[4] - mx[1] * mx[3]) / det
            vout[0] = inv[0] * vin[0] + inv[1] * vin[1] + inv[2] * vin[2]
            vout[1] = inv[3] * vin[0] + inv[4] * vin[1] + inv[5] * vin[2]
            vout[2] = inv[6] * vin[0] + inv[7] * vin[1] + inv[8] * vin[2]
            return true
        }

        /** `n_times_root_k`: round(n * sqrt(k)), with unsigned wrapping. */
        internal fun nTimesRootK(nSigned: Int, k: Int): Int {
            val sign = if (nSigned < 0) -1 else 1
            val n = (nSigned * sign).toUInt()
            val kk = k.toUInt()
            var x = 0u
            var r = 0u
            var m = 0u
            var bitpos = 0x80000000u
            while (bitpos != 0u) {
                val b = if (n and bitpos != 0u) 1u else 0u
                var a = 0u
                while (true) {
                    val pos = 4u * r + kk * b * (4u * m + 1u)
                    val neg = 4u * a * x + a * a
                    if (pos < neg) break
                    a++
                }
                a--
                r = 4u * r + b * kk * (4u * m + 1u) - (4u * a * x + a * a)
                m = 2u * m + b
                x = 2u * x + a
                bitpos = bitpos shr 1
            }
            if (r > x) x++
            return if (sign == 1) {
                x.toInt()
            } else {
                Int.MIN_VALUE + (0u - x - Int.MIN_VALUE.toUInt()).toInt()
            }
        }

        // ------------------------------------------------------------
        // Builder.
        // ------------------------------------------------------------

        private data class PointKey(val x: Int, val y: Int)

        private class Builder(val type: GridType) {
            val faces = ArrayList<GridFace>()
            val edges = ArrayList<GridEdge>()
            val dots = ArrayList<GridDot>()
            var tileSize = 0
            var lowestX = 0
            var lowestY = 0
            var highestX = 0
            var highestY = 0
        }

        private fun beginFace(b: Builder, order: Int): GridFace {
            val face = GridFace(b.faces.size, order)
            b.faces.add(face)
            return face
        }

        private fun getDot(
            b: Builder,
            map: HashMap<PointKey, GridDot>,
            x: Int,
            y: Int,
        ): GridDot {
            val key = PointKey(x, y)
            map[key]?.let { return it }
            val dot = GridDot(index = b.dots.size, x = x, y = y)
            b.dots.add(dot)
            map[key] = dot
            return dot
        }

        private fun edgeKey(a: Int, b: Int): Long {
            val lo = if (a < b) a else b
            val hi = if (a < b) b else a
            return (lo.toLong() shl 32) or hi.toLong()
        }

        /** `grid_make_consistent`: derive edges, dot order and bounding box. */
        private fun makeConsistent(b: Builder) {
            // Stage 1: generate edges, joining two faces when the same
            // edge is met a second time.
            val incomplete = HashMap<Long, GridEdge>()
            for (face in b.faces) {
                for (j in 0 until face.order) {
                    val dot1 = face.dots[j]!!
                    val dot2 = face.dots[(j + 1) % face.order]!!
                    val found = incomplete.remove(edgeKey(dot1.index, dot2.index))
                    if (found != null) {
                        found.face2 = face
                    } else {
                        val edge = GridEdge(b.edges.size, dot1, dot2, face, null)
                        incomplete[edgeKey(dot1.index, dot2.index)] = edge
                        b.edges.add(edge)
                    }
                }
            }

            // Stage 2: build each face's edge list.
            for (face in b.faces) {
                face.edges = arrayOfNulls(face.order)
            }
            for (edge in b.edges) {
                for (j in 0 until 2) {
                    val face = if (j == 0) edge.face1 else edge.face2
                    if (face == null) continue
                    var k = 0
                    while (k < face.order && face.dots[k] !== edge.dot1) k++
                    check(k != face.order) { "edge dot is not on its face" }

                    var k2 = k + 1
                    if (k2 == face.order) k2 = 0
                    if (face.dots[k2] === edge.dot2) {
                        check(face.edges[k] == null)
                        face.edges[k] = edge
                        continue
                    }
                    k2 = k - 1
                    if (k2 == -1) k2 = face.order - 1
                    if (face.dots[k2] === edge.dot2) {
                        check(face.edges[k2] == null)
                        face.edges[k2] = edge
                        continue
                    }
                    error("grid broken: bad edge-face relationship")
                }
            }

            // Stage 3: build each dot's edge and face lists.
            for (dot in b.dots) dot.order = 0
            for (edge in b.edges) {
                edge.dot1.order++
                edge.dot2.order++
            }
            for (dot in b.dots) {
                check(dot.order >= 2) { "dot has fewer than two edges" }
                dot.edges = arrayOfNulls(dot.order)
                dot.faces = arrayOfNulls(dot.order)
            }
            for (face in b.faces) {
                for (j in 0 until face.order) {
                    face.dots[j]!!.faces[0] = face
                }
            }
            for (dot in b.dots) {
                var currentFace1 = 0
                var currentFace2 = 0

                while (true) {
                    val face = dot.faces[currentFace1]!!
                    var j = 0
                    while (j < face.order && face.dots[j] !== dot) j++
                    check(j != face.order) { "dot is not on its face" }
                    j--
                    if (j == -1) j = face.order - 1
                    val edge = face.edges[j]!!
                    dot.edges[currentFace1] = edge
                    currentFace1++
                    if (currentFace1 == dot.order) break
                    dot.faces[currentFace1] =
                        if (edge.face1 === face) edge.face2 else edge.face1
                    if (dot.faces[currentFace1] == null) break
                }
                if (currentFace1 == dot.order) continue

                while (true) {
                    val face = dot.faces[currentFace2]!!
                    var j = 0
                    while (j < face.order && face.dots[j] !== dot) j++
                    check(j != face.order) { "dot is not on its face" }
                    val edge = face.edges[j]!!
                    currentFace2--
                    if (currentFace2 == -1) currentFace2 = dot.order - 1
                    dot.edges[currentFace2] = edge
                    if (currentFace2 == currentFace1) break
                    dot.faces[currentFace2] =
                        if (edge.face1 === face) edge.face2 else edge.face1
                    check(dot.faces[currentFace2] != null)
                }
            }

            // Stage 4: bounding rectangle.
            for ((i, dot) in b.dots.withIndex()) {
                if (i == 0) {
                    b.lowestX = dot.x
                    b.highestX = dot.x
                    b.lowestY = dot.y
                    b.highestY = dot.y
                } else {
                    if (dot.x < b.lowestX) b.lowestX = dot.x
                    if (dot.x > b.highestX) b.highestX = dot.x
                    if (dot.y < b.lowestY) b.lowestY = dot.y
                    if (dot.y > b.highestY) b.highestY = dot.y
                }
            }
        }

        /** `grid_trim_vigorously`: drop faces away from the main body. */
        private fun trimVigorously(b: Builder) {
            val n = b.dots.size
            if (n == 0) return
            val dotpairs = IntArray(n * n) { -1 }
            var i = 0
            while (i < b.faces.size) {
                val face = b.faces[i]
                var dot0 = face.dots[face.order - 1]!!.index
                for (j in 0 until face.order) {
                    val dot1 = face.dots[j]!!.index
                    dotpairs[dot0 * n + dot1] = i
                    dot0 = dot1
                }
                i++
            }

            val dots = IntArray(n) { 1 }
            for (a in 0 until n) {
                for (c in 0 until n) {
                    if ((dotpairs[a * n + c] >= 0) != (dotpairs[c * n + a] >= 0)) {
                        dots[a] = 0
                    }
                }
            }

            val dsf = Dsf.new(n)
            for (a in 0 until n) {
                for (c in 0 until a) {
                    if (dots[a] != 0 && dots[c] != 0 &&
                        dotpairs[a * n + c] >= 0 && dotpairs[c * n + a] >= 0
                    ) {
                        dsf.merge(a, c)
                    }
                }
            }

            var size = 0
            var chosen = -1
            for (a in 0 until n) {
                if (dots[a] != 0 && dsf.canonify(a) == a) {
                    val newSize = dsf.size(a)
                    if (newSize > size) {
                        chosen = a
                        size = newSize
                    }
                }
            }

            val keepFace = IntArray(b.faces.size)
            val keepDot = IntArray(n)
            for (a in 0 until b.faces.size) {
                val face = b.faces[a]
                var keep = false
                for (k in 0 until face.order) {
                    if (dsf.canonify(face.dots[k]!!.index) == chosen) keep = true
                }
                if (keep) {
                    keepFace[a] = 1
                    for (k in 0 until face.order) keepDot[face.dots[k]!!.index] = 1
                }
            }

            val newFaces = ArrayList<GridFace>()
            for (face in b.faces) {
                if (keepFace[face.index] != 0) {
                    face.index = newFaces.size
                    newFaces.add(face)
                }
            }
            b.faces.clear()
            b.faces.addAll(newFaces)

            val newDots = ArrayList<GridDot>()
            for (dot in b.dots) {
                if (keepDot[dot.index] != 0) {
                    dot.index = newDots.size
                    newDots.add(dot)
                }
            }
            b.dots.clear()
            b.dots.addAll(newDots)
        }

        private fun finish(b: Builder, type: GridType): Grid {
            makeConsistent(b)
            return Grid(
                type = type,
                faces = b.faces,
                edges = b.edges,
                dots = b.dots,
                lowestX = b.lowestX,
                lowestY = b.lowestY,
                highestX = b.highestX,
                highestY = b.highestY,
                tileSize = b.tileSize,
            )
        }

        // ------------------------------------------------------------
        // Validation.
        // ------------------------------------------------------------

        private const val TOO_LARGE = "Grid size must not be unreasonably large"
        private const val TOO_LARGE_GRID = "Grid must not be unreasonably large"

        private fun validateSquare(width: Int, height: Int): String? {
            if (width > Int.MAX_VALUE / SQUARE_TILESIZE ||
                height > Int.MAX_VALUE / SQUARE_TILESIZE ||
                width + 1 > Int.MAX_VALUE / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateHoneycomb(width: Int, height: Int): String? {
            val a = HONEY_A
            val b = HONEY_B
            if (width - 1 > (Int.MAX_VALUE - 4 * a) / (3 * a) ||
                height - 1 > (Int.MAX_VALUE - 3 * b) / (2 * b) ||
                width + 1 > Int.MAX_VALUE / 2 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateTriangular(width: Int, height: Int): String? {
            val vecX = TRIANGLE_VEC_X
            val vecY = TRIANGLE_VEC_Y
            if (width > Int.MAX_VALUE / (2 * vecX) - 1 ||
                height > Int.MAX_VALUE / vecY ||
                width + 1 > Int.MAX_VALUE / 4 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateSnubsquare(width: Int, height: Int): String? {
            val c = SNUBSQUARE_A + SNUBSQUARE_B
            if (width - 1 > (Int.MAX_VALUE - c) / c ||
                height > (Int.MAX_VALUE - c) / c ||
                width > Int.MAX_VALUE / 3 / height ||
                width + 1 > Int.MAX_VALUE / 2 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateCairo(width: Int, height: Int): String? {
            val b = CAIRO_B
            if (width - 1 > (Int.MAX_VALUE - 2 * b) / (2 * b) ||
                height - 1 > (Int.MAX_VALUE - 2 * b) / (2 * b) ||
                width > Int.MAX_VALUE / 2 / height ||
                width + 1 > Int.MAX_VALUE / 3 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateGreathexagonal(width: Int, height: Int): String? {
            val a = GREATHEX_A
            val b = GREATHEX_B
            if (width - 1 > (Int.MAX_VALUE - 4 * a) / (3 * a + b) ||
                height - 1 > (Int.MAX_VALUE - (3 * b + a)) / (2 * a + 2 * b) ||
                width + 1 > Int.MAX_VALUE / 6 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateKagome(width: Int, height: Int): String? {
            val a = KAGOME_A
            val b = KAGOME_B
            if (width - 1 > (Int.MAX_VALUE - 6 * a) / (4 * a) ||
                height - 1 > (Int.MAX_VALUE - 2 * b) / (2 * b) ||
                width + 1 > Int.MAX_VALUE / 6 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateOctagonal(width: Int, height: Int): String? {
            val step = 2 * OCTAGONAL_A + OCTAGONAL_B
            if (width > Int.MAX_VALUE / step ||
                height > Int.MAX_VALUE / step ||
                height + 1 > Int.MAX_VALUE / 4 / (width + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateKites(width: Int, height: Int): String? {
            val a = KITE_A
            val b = KITE_B
            if (width > (Int.MAX_VALUE - 2 * b) / (4 * b) ||
                height - 1 > (Int.MAX_VALUE - 8 * a) / (6 * a) ||
                width + 1 > Int.MAX_VALUE / 6 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateFloret(width: Int, height: Int): String? {
            val px = FLORET_PX
            val py = FLORET_PY
            val qx = 4 * px / 5
            val qy = -py * 2
            val ry = qy - py
            if (width - 1 > (Int.MAX_VALUE - (4 * px + 2 * qx)) / ((6 * px + 3 * qx) / 2) ||
                height - 1 > (Int.MAX_VALUE - (4 * ry - 2 * qy)) / (5 * qy - 4 * py) ||
                width + 1 > Int.MAX_VALUE / 9 / (height + 1)
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateDodecagonal(width: Int, height: Int): String? {
            val a = DODEC_A
            val b = DODEC_B
            if (width - 1 > (Int.MAX_VALUE - 3 * (2 * a + b)) / (4 * a + 2 * b) ||
                height - 1 > (Int.MAX_VALUE - 2 * (2 * a + b)) / (3 * a + 2 * b) ||
                width > Int.MAX_VALUE / 14 / height
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateGreatdodecagonal(width: Int, height: Int): String? {
            val a = DODEC_A
            val b = DODEC_B
            if (width - 1 > (Int.MAX_VALUE - (2 * (2 * a + b) + 3 * a + b)) / (6 * a + 2 * b) ||
                height - 1 > (Int.MAX_VALUE - 2 * (2 * a + b)) / (3 * a + 3 * b) ||
                width > Int.MAX_VALUE / 200 / height
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateGreatgreatdodecagonal(width: Int, height: Int): String? {
            val a = DODEC_A
            val b = DODEC_B
            if (width - 1 > (Int.MAX_VALUE - (2 * (2 * a + b) + 2 * a + 2 * b)) / (4 * a + 4 * b) ||
                height - 1 > (Int.MAX_VALUE - 2 * (2 * a + b)) / (6 * a + 2 * b) ||
                width > Int.MAX_VALUE / 300 / height
            ) {
                return TOO_LARGE
            }
            return null
        }

        private fun validateCompassdodecagonal(width: Int, height: Int): String? {
            val step = 4 * DODEC_A + 2 * DODEC_B
            if (width > Int.MAX_VALUE / step ||
                height > Int.MAX_VALUE / step ||
                width > Int.MAX_VALUE / 18 / height
            ) {
                return TOO_LARGE_GRID
            }
            return null
        }

        private fun validatePenrose(width: Int, height: Int): String? {
            val l = PENROSE_TILESIZE
            if (width > Int.MAX_VALUE / l ||
                height > Int.MAX_VALUE / l ||
                width > Int.MAX_VALUE / (3 * 3 * 4 * height)
            ) {
                return TOO_LARGE_GRID
            }
            return null
        }

        private fun validateHats(width: Int, height: Int): String? {
            val l = HATS_TILESIZE
            if (width > Int.MAX_VALUE / l ||
                height > Int.MAX_VALUE / l ||
                width > Int.MAX_VALUE / (6 * height)
            ) {
                return TOO_LARGE_GRID
            }
            return null
        }

        private fun validateSpectres(width: Int, height: Int): String? {
            val l = SPECTRE_UNIT * SPECTRE_SQUARELEN
            if (width > Int.MAX_VALUE / l ||
                height > Int.MAX_VALUE / l ||
                width > Int.MAX_VALUE / SPECTRE_SQUARELEN /
                SPECTRE_SQUARELEN / height
            ) {
                return TOO_LARGE_GRID
            }
            return null
        }

        private fun validateDescTriangular(desc: String?): String? {
            if (desc == null || desc == "0") return null
            return "Unrecognised grid description."
        }

        // ------------------------------------------------------------
        // Penrose descriptions and sizing.
        // ------------------------------------------------------------

        private class Size(val w: Int, val h: Int)

        private fun apiSizePenrose(width: Int, height: Int, which: Int): Size {
            val xunit = if (which == PenroseTiling.P2) PENROSE_XUNIT_P2 else PENROSE_XUNIT_P3
            val yunit = if (which == PenroseTiling.P2) PENROSE_YUNIT_P2 else PENROSE_YUNIT_P3
            return Size(
                width * PENROSE_TILESIZE / yunit,
                height * PENROSE_TILESIZE / xunit,
            )
        }

        private class PenroseDecoded(
            val params: PenroseTiling.PatchParams?,
            val error: String?,
        )

        private fun decodePenrose(desc: String, which: Int): PenroseDecoded {
            if (desc.isEmpty()) return PenroseDecoded(null, "empty grid description")
            // Upstream reads desc[1] without checking the length, which is
            // undefined for a one-character string. Report the same error
            // that a short second character would produce.
            if (desc.length < 2) {
                return PenroseDecoded(null, "expected digit as second char of grid description")
            }
            if (desc[0] !in '0'..'9') {
                return PenroseDecoded(null, "expected digit at start of grid description")
            }
            val orientation = desc[0] - '0'
            if (desc[1] !in '0'..'2') {
                return PenroseDecoded(null, "expected digit as second char of grid description")
            }
            val startVertex = desc[1] - '0'
            val ncoords = desc.length - 2
            val coords = CharArray(ncoords)
            for (i in 0 until ncoords) {
                val c = desc[i + 2]
                if (!PenroseTiling.validLetter(c, which)) {
                    return PenroseDecoded(null, "expected tile letter in grid description")
                }
                coords[i] = c
            }
            val params = PenroseTiling.PatchParams(orientation, startVertex, coords)
            return PenroseDecoded(params, PenroseTiling.paramsInvalid(params, which))
        }

        private fun validateDescPenrose(
            type: GridType,
            width: Int,
            height: Int,
            desc: String?,
        ): String? {
            if (desc == null) return "Missing grid description string."
            if (desc.startsWith("G")) {
                return validateDescPenroseLegacy(type, width, height, desc)
            }
            val which = if (type == GridType.PENROSE_P2) PenroseTiling.P2 else PenroseTiling.P3
            return decodePenrose(desc, which).error
        }

        private class LegacyNumber(val value: Int, val next: Int)

        /** `sscanf(desc, "G%d,%d,%d", ...) != 3`. */
        private fun scanLegacy(desc: String): Triple<Int, Int, Int>? {
            var p = 0
            while (p < desc.length && desc[p].isWhitespace()) p++
            if (p >= desc.length || desc[p] != 'G') return null
            p++
            val numbers = IntArray(3)
            for (i in 0 until 3) {
                while (p < desc.length && desc[p].isWhitespace()) p++
                val start = p
                if (p < desc.length && (desc[p] == '+' || desc[p] == '-')) p++
                val digitsStart = p
                while (p < desc.length && desc[p] in '0'..'9') p++
                if (p == digitsStart) return null
                numbers[i] = desc.substring(start, p).toIntOrNull() ?: return null
                if (i < 2) {
                    if (p >= desc.length || desc[p] != ',') return null
                    p++
                }
            }
            return Triple(numbers[0], numbers[1], numbers[2])
        }

        private fun validateDescPenroseLegacy(
            type: GridType,
            width: Int,
            height: Int,
            desc: String,
        ): String? {
            val which = if (type == GridType.PENROSE_P2) PenroseTiling.P2 else PenroseTiling.P3
            val (radius, _, _) = PenroseLegacy.calculateSize(
                which,
                PENROSE_TILESIZE,
                width,
                height,
            )
            val innerRadius = (radius - Math.sqrt((width * width + height * height).toDouble())).toInt()
            val numbers = scanLegacy(desc)
                ?: return "Invalid format grid description string."
            val (xoff, yoff, aoff) = numbers
            // Upstream multiplies the offsets as ints before taking the
            // square root, so a very large offset wraps and is not
            // reported as out of bounds. Match that arithmetic.
            if (Math.sqrt((xoff * xoff + yoff * yoff).toDouble()) > innerRadius) {
                return "Patch offset out of bounds."
            }
            if (aoff % 36 != 0 || aoff < 0 || aoff >= 360) {
                return "Angle offset out of bounds."
            }

            // Test-generate: the patch must contain at least one face.
            val probe = newPenroseLegacy(width, height, which, desc)
            if (probe == null) {
                return "Patch coordinates do not identify a usable grid fragment"
            }
            return null
        }

        // ------------------------------------------------------------
        // Hats and spectres descriptions.
        // ------------------------------------------------------------

        private class HatDecoded(
            val params: HatTiling.PatchParams?,
            val error: String?,
        )

        private fun decodeHat(desc: String): HatDecoded {
            var p = 0
            val coords = ArrayList<Int>()
            while (p < desc.length && desc[p] in '0'..'9') {
                val start = p
                while (p < desc.length && desc[p] in '0'..'9') p++
                val n = desc.substring(start, p).toLong()
                if (p >= desc.length || desc[p] != ',') {
                    return HatDecoded(null, "expected ',' in grid description")
                }
                if (p - start > 2 || n > 0xFF) {
                    return HatDecoded(null, "too-large coordinate in grid description")
                }
                p++
                coords.add(n.toInt())
            }
            if (p >= desc.length || desc[p] !in "HTPF") {
                return HatDecoded(null, "invalid character in grid description")
            }
            val params = HatTiling.PatchParams(coords.toIntArray(), desc[p])
            return HatDecoded(params, HatTiling.paramsInvalid(params))
        }

        private fun validateDescHats(desc: String?): String? {
            if (desc == null) return "Missing grid description string."
            return decodeHat(desc).error
        }

        private class SpectreDecoded(
            val params: SpectreTiling.PatchParams?,
            val error: String?,
        )

        private fun decodeSpectre(desc: String): SpectreDecoded {
            if (desc.isEmpty()) return SpectreDecoded(null, "empty grid description")
            if (desc.length < 2) {
                // Upstream underflows the coordinate count here; a short
                // description cannot contain a final hexagon letter.
                return SpectreDecoded(null, "expected digit in grid description")
            }
            val orientation = when (desc[0]) {
                in '0'..'9' -> desc[0] - '0'
                'A', 'B' -> 10 + (desc[0] - 'A')
                else -> return SpectreDecoded(
                    null,
                    "expected digit or A,B at start of grid description",
                )
            }
            val ncoords = desc.length - 2
            val coords = IntArray(ncoords)
            for (i in 0 until ncoords) {
                val c = desc[i + 1]
                if (c !in '0'..'9') {
                    return SpectreDecoded(null, "expected digit in grid description")
                }
                coords[i] = c - '0'
            }
            val params = SpectreTiling.PatchParams(orientation, coords, desc[ncoords + 1])
            return SpectreDecoded(params, SpectreTiling.paramsInvalid(params))
        }

        private fun validateDescSpectres(desc: String?): String? {
            if (desc == null) return "Missing grid description string."
            return decodeSpectre(desc).error
        }

        // ------------------------------------------------------------
        // Generators.
        // ------------------------------------------------------------

        private fun finishSimple(b: Builder): Grid = finish(b, b.type)

        private fun newSquare(width: Int, height: Int): Grid {
            val b = Builder(GridType.SQUARE)
            val a = SQUARE_TILESIZE
            b.tileSize = a
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = a * x
                    val py = a * y
                    val face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px + a, py)
                    face.dots[2] = getDot(b, points, px + a, py + a)
                    face.dots[3] = getDot(b, points, px, py + a)
                }
            }
            return finishSimple(b)
        }

        private fun newHoneycomb(width: Int, height: Int): Grid {
            val b = Builder(GridType.HONEYCOMB)
            val a = HONEY_A
            val c = HONEY_B
            b.tileSize = HONEY_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val cx = 3 * a * x
                    var cy = 2 * c * y
                    if (x % 2 != 0) cy += c
                    val face = beginFace(b, 6)
                    face.dots[0] = getDot(b, points, cx - a, cy - c)
                    face.dots[1] = getDot(b, points, cx + a, cy - c)
                    face.dots[2] = getDot(b, points, cx + 2 * a, cy)
                    face.dots[3] = getDot(b, points, cx + a, cy + c)
                    face.dots[4] = getDot(b, points, cx - a, cy + c)
                    face.dots[5] = getDot(b, points, cx - 2 * a, cy)
                }
            }
            return finishSimple(b)
        }

        private fun newTriangular(width: Int, height: Int, desc: String?): Grid {
            val b = Builder(GridType.TRIANGULAR)
            val vecX = TRIANGLE_VEC_X
            val vecY = TRIANGLE_VEC_Y
            b.tileSize = TRIANGLE_TILESIZE
            val version = if (desc == null) -1 else atoi(desc)
            val w = width + 1

            if (version == -1) {
                for (y in 0..height) {
                    for (x in 0..width) {
                        val dot = GridDot(index = b.dots.size, x = x * 2 * vecX + (if (y % 2 != 0) vecX else 0), y = y * vecY)
                        b.dots.add(dot)
                    }
                }
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val f1 = beginFace(b, 3)
                        val f2 = beginFace(b, 3)
                        if (y % 2 != 0) {
                            f1.dots[0] = b.dots[y * w + x]
                            f1.dots[1] = b.dots[(y + 1) * w + x + 1]
                            f1.dots[2] = b.dots[(y + 1) * w + x]
                            f2.dots[0] = b.dots[y * w + x]
                            f2.dots[1] = b.dots[y * w + x + 1]
                            f2.dots[2] = b.dots[(y + 1) * w + x + 1]
                        } else {
                            f1.dots[0] = b.dots[y * w + x]
                            f1.dots[1] = b.dots[y * w + x + 1]
                            f1.dots[2] = b.dots[(y + 1) * w + x]
                            f2.dots[0] = b.dots[y * w + x + 1]
                            f2.dots[1] = b.dots[(y + 1) * w + x + 1]
                            f2.dots[2] = b.dots[(y + 1) * w + x]
                        }
                    }
                }
            } else {
                val points = HashMap<PointKey, GridDot>()
                for (y in 0 until height) {
                    var y0 = y * vecY
                    var y1 = y * vecY
                    val n1: Int
                    val n2: Int
                    if (y % 2 != 0) {
                        y1 += vecY
                        n1 = 2
                        n2 = 1
                    } else {
                        y0 += vecY
                        n1 = 1
                        n2 = 2
                    }

                    for (x in 0..width) {
                        val x0 = 2 * x * vecX
                        val x1 = x0 + vecX
                        val x2 = x1 + vecX
                        if (height % 2 == 1 && y == height - 1 && (x == 0 || x == width)) {
                            continue
                        }
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, x0, y0)
                        face.dots[n1] = getDot(b, points, x1, y1)
                        face.dots[n2] = getDot(b, points, x2, y0)
                    }

                    for (x in 0 until width) {
                        val x0 = (2 * x + 1) * vecX
                        val x1 = x0 + vecX
                        val x2 = x1 + vecX
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, x0, y1)
                        face.dots[n2] = getDot(b, points, x1, y0)
                        face.dots[n1] = getDot(b, points, x2, y1)
                    }
                }
            }
            return finish(b, GridType.TRIANGULAR)
        }

        /** `atoi`: optional whitespace, sign, digits; zero on no digits. */
        private fun atoi(text: String): Int {
            var p = 0
            while (p < text.length && text[p].isWhitespace()) p++
            var sign = 1
            if (p < text.length && (text[p] == '+' || text[p] == '-')) {
                if (text[p] == '-') sign = -1
                p++
            }
            val start = p
            while (p < text.length && text[p] in '0'..'9') p++
            if (p == start) return 0
            return sign * (text.substring(start, p).toIntOrNull() ?: 0)
        }

        private fun newSnubsquare(width: Int, height: Int): Grid {
            val b = Builder(GridType.SNUBSQUARE)
            val a = SNUBSQUARE_A
            val c = SNUBSQUARE_B
            b.tileSize = SNUBSQUARE_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = (a + c) * x
                    val py = (a + c) * y
                    val face = beginFace(b, 4)
                    if ((x + y) % 2 != 0) {
                        face.dots[0] = getDot(b, points, px + a, py)
                        face.dots[1] = getDot(b, points, px + a + c, py + a)
                        face.dots[2] = getDot(b, points, px + c, py + a + c)
                        face.dots[3] = getDot(b, points, px, py + c)
                    } else {
                        face.dots[0] = getDot(b, points, px + c, py)
                        face.dots[1] = getDot(b, points, px + a + c, py + c)
                        face.dots[2] = getDot(b, points, px + a, py + a + c)
                        face.dots[3] = getDot(b, points, px, py + a)
                    }

                    if (x > 0) {
                        val tri = beginFace(b, 3)
                        if ((x + y) % 2 != 0) {
                            tri.dots[0] = getDot(b, points, px + a, py)
                            tri.dots[1] = getDot(b, points, px, py + c)
                            tri.dots[2] = getDot(b, points, px - a, py)
                        } else {
                            tri.dots[0] = getDot(b, points, px, py + a)
                            tri.dots[1] = getDot(b, points, px + a, py + a + c)
                            tri.dots[2] = getDot(b, points, px - a, py + a + c)
                        }
                    }

                    if (y > 0) {
                        val tri = beginFace(b, 3)
                        if ((x + y) % 2 != 0) {
                            tri.dots[0] = getDot(b, points, px + a, py)
                            tri.dots[1] = getDot(b, points, px + a + c, py - a)
                            tri.dots[2] = getDot(b, points, px + a + c, py + a)
                        } else {
                            tri.dots[0] = getDot(b, points, px, py - a)
                            tri.dots[1] = getDot(b, points, px + c, py)
                            tri.dots[2] = getDot(b, points, px, py + a)
                        }
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newCairo(width: Int, height: Int): Grid {
            val b = Builder(GridType.CAIRO)
            val a = CAIRO_A
            val c = CAIRO_B
            b.tileSize = CAIRO_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = 2 * c * x
                    val py = 2 * c * y

                    if (y > 0) {
                        val face = beginFace(b, 5)
                        if ((x + y) % 2 != 0) {
                            face.dots[0] = getDot(b, points, px + a, py - c)
                            face.dots[1] = getDot(b, points, px + 2 * c - a, py - c)
                            face.dots[2] = getDot(b, points, px + 2 * c, py)
                            face.dots[3] = getDot(b, points, px + c, py + a)
                            face.dots[4] = getDot(b, points, px, py)
                        } else {
                            face.dots[0] = getDot(b, points, px, py)
                            face.dots[1] = getDot(b, points, px + c, py - a)
                            face.dots[2] = getDot(b, points, px + 2 * c, py)
                            face.dots[3] = getDot(b, points, px + 2 * c - a, py + c)
                            face.dots[4] = getDot(b, points, px + a, py + c)
                        }
                    }

                    if (x > 0) {
                        val face = beginFace(b, 5)
                        if ((x + y) % 2 != 0) {
                            face.dots[0] = getDot(b, points, px, py)
                            face.dots[1] = getDot(b, points, px + c, py + a)
                            face.dots[2] = getDot(b, points, px + c, py + 2 * c - a)
                            face.dots[3] = getDot(b, points, px, py + 2 * c)
                            face.dots[4] = getDot(b, points, px - a, py + c)
                        } else {
                            face.dots[0] = getDot(b, points, px, py)
                            face.dots[1] = getDot(b, points, px + a, py + c)
                            face.dots[2] = getDot(b, points, px, py + 2 * c)
                            face.dots[3] = getDot(b, points, px - c, py + 2 * c - a)
                            face.dots[4] = getDot(b, points, px - c, py + a)
                        }
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newGreathexagonal(width: Int, height: Int): Grid {
            val b = Builder(GridType.GREATHEXAGONAL)
            val a = GREATHEX_A
            val c = GREATHEX_B
            b.tileSize = GREATHEX_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = (3 * a + c) * x
                    var py = (2 * a + 2 * c) * y
                    if (x % 2 != 0) py += a + c

                    val hex = beginFace(b, 6)
                    hex.dots[0] = getDot(b, points, px - a, py - c)
                    hex.dots[1] = getDot(b, points, px + a, py - c)
                    hex.dots[2] = getDot(b, points, px + 2 * a, py)
                    hex.dots[3] = getDot(b, points, px + a, py + c)
                    hex.dots[4] = getDot(b, points, px - a, py + c)
                    hex.dots[5] = getDot(b, points, px - 2 * a, py)

                    if (y < height - 1) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px - a, py + c)
                        face.dots[1] = getDot(b, points, px + a, py + c)
                        face.dots[2] = getDot(b, points, px + a, py + 2 * a + c)
                        face.dots[3] = getDot(b, points, px - a, py + 2 * a + c)
                    }

                    if (x < width - 1 && (x % 2 == 0 || y < height - 1)) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + 2 * a, py)
                        face.dots[1] = getDot(b, points, px + 2 * a + c, py + a)
                        face.dots[2] = getDot(b, points, px + a + c, py + a + c)
                        face.dots[3] = getDot(b, points, px + a, py + c)
                    }

                    if (x > 0 && (x % 2 == 0 || y < height - 1)) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px - 2 * a, py)
                        face.dots[1] = getDot(b, points, px - a, py + c)
                        face.dots[2] = getDot(b, points, px - a - c, py + a + c)
                        face.dots[3] = getDot(b, points, px - 2 * a - c, py + a)
                    }

                    if (x < width - 1 && y < height - 1) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + a, py + c)
                        face.dots[1] = getDot(b, points, px + a + c, py + a + c)
                        face.dots[2] = getDot(b, points, px + a, py + 2 * a + c)
                    }

                    if (x > 0 && y < height - 1) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px - a, py + c)
                        face.dots[1] = getDot(b, points, px - a, py + 2 * a + c)
                        face.dots[2] = getDot(b, points, px - a - c, py + a + c)
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newKagome(width: Int, height: Int): Grid {
            val b = Builder(GridType.KAGOME)
            val a = KAGOME_A
            val c = KAGOME_B
            b.tileSize = KAGOME_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var px = 4 * a * x
                    val py = 2 * c * y
                    if (y % 2 != 0) px += 2 * a

                    val hex = beginFace(b, 6)
                    hex.dots[0] = getDot(b, points, px + a, py - c)
                    hex.dots[1] = getDot(b, points, px + 2 * a, py)
                    hex.dots[2] = getDot(b, points, px + a, py + c)
                    hex.dots[3] = getDot(b, points, px - a, py + c)
                    hex.dots[4] = getDot(b, points, px - 2 * a, py)
                    hex.dots[5] = getDot(b, points, px - a, py - c)

                    if (x < width - 1 || (y % 2 == 0 && y > 0)) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + 3 * a, py - c)
                        face.dots[1] = getDot(b, points, px + 2 * a, py)
                        face.dots[2] = getDot(b, points, px + a, py - c)
                    }

                    if (x < width - 1 || (y % 2 == 0 && y < height - 1)) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + 3 * a, py + c)
                        face.dots[1] = getDot(b, points, px + a, py + c)
                        face.dots[2] = getDot(b, points, px + 2 * a, py)
                    }

                    if (x == 0 && y % 2 != 0) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px - a, py - c)
                        face.dots[1] = getDot(b, points, px - 2 * a, py)
                        face.dots[2] = getDot(b, points, px - 3 * a, py - c)

                        if (y < height - 1) {
                            val lower = beginFace(b, 3)
                            lower.dots[0] = getDot(b, points, px - a, py + c)
                            lower.dots[1] = getDot(b, points, px - 3 * a, py + c)
                            lower.dots[2] = getDot(b, points, px - 2 * a, py)
                        }
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newOctagonal(width: Int, height: Int): Grid {
            val b = Builder(GridType.OCTAGONAL)
            val a = OCTAGONAL_A
            val c = OCTAGONAL_B
            b.tileSize = OCTAGONAL_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = (2 * a + c) * x
                    val py = (2 * a + c) * y

                    val oct = beginFace(b, 8)
                    oct.dots[0] = getDot(b, points, px + a, py)
                    oct.dots[1] = getDot(b, points, px + a + c, py)
                    oct.dots[2] = getDot(b, points, px + 2 * a + c, py + a)
                    oct.dots[3] = getDot(b, points, px + 2 * a + c, py + a + c)
                    oct.dots[4] = getDot(b, points, px + a + c, py + 2 * a + c)
                    oct.dots[5] = getDot(b, points, px + a, py + 2 * a + c)
                    oct.dots[6] = getDot(b, points, px, py + a + c)
                    oct.dots[7] = getDot(b, points, px, py + a)

                    if (x > 0 && y > 0) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px, py - a)
                        face.dots[1] = getDot(b, points, px + a, py)
                        face.dots[2] = getDot(b, points, px, py + a)
                        face.dots[3] = getDot(b, points, px - a, py)
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newKites(width: Int, height: Int): Grid {
            val b = Builder(GridType.KITES)
            val a = KITE_A
            val c = KITE_B
            b.tileSize = KITE_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var px = 4 * c * x
                    val py = 6 * a * y
                    if (y % 2 != 0) px += 2 * c

                    var face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px + 2 * c, py)
                    face.dots[2] = getDot(b, points, px + 2 * c, py + 2 * a)
                    face.dots[3] = getDot(b, points, px + c, py + 3 * a)

                    face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px + c, py + 3 * a)
                    face.dots[2] = getDot(b, points, px, py + 4 * a)
                    face.dots[3] = getDot(b, points, px - c, py + 3 * a)

                    face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px - c, py + 3 * a)
                    face.dots[2] = getDot(b, points, px - 2 * c, py + 2 * a)
                    face.dots[3] = getDot(b, points, px - 2 * c, py)

                    face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px - 2 * c, py)
                    face.dots[2] = getDot(b, points, px - 2 * c, py - 2 * a)
                    face.dots[3] = getDot(b, points, px - c, py - 3 * a)

                    face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px - c, py - 3 * a)
                    face.dots[2] = getDot(b, points, px, py - 4 * a)
                    face.dots[3] = getDot(b, points, px + c, py - 3 * a)

                    face = beginFace(b, 4)
                    face.dots[0] = getDot(b, points, px, py)
                    face.dots[1] = getDot(b, points, px + c, py - 3 * a)
                    face.dots[2] = getDot(b, points, px + 2 * c, py - 2 * a)
                    face.dots[3] = getDot(b, points, px + 2 * c, py)
                }
            }
            return finishSimple(b)
        }

        private fun newFloret(width: Int, height: Int): Grid {
            val b = Builder(GridType.FLORET)
            val px = FLORET_PX
            val py = FLORET_PY
            val qx = 4 * px / 5
            val qy = -py * 2
            val rx = qx - px
            val ry = qy - py
            b.tileSize = FLORET_TILESIZE
            val points = HashMap<PointKey, GridDot>()

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val cx = (6 * px + 3 * qx) / 2 * x
                    var cy = (4 * py - 5 * qy) * y
                    if (x % 2 != 0) {
                        cy -= (4 * py - 5 * qy) / 2
                    } else if (y != 0 && y == height - 1 && width > 1) {
                        continue
                    }

                    var face = beginFace(b, 5)
                    face.dots[0] = getDot(b, points, cx, cy)
                    face.dots[1] = getDot(b, points, cx + 2 * rx, cy + 2 * ry)
                    face.dots[2] = getDot(b, points, cx + 2 * rx + qx, cy + 2 * ry + qy)
                    face.dots[3] = getDot(b, points, cx + 2 * qx + rx, cy + 2 * qy + ry)
                    face.dots[4] = getDot(b, points, cx + 2 * qx, cy + 2 * qy)

                    face = beginFace(b, 5)
                    face.dots[0] = getDot(b, points, cx, cy)
                    face.dots[1] = getDot(b, points, cx + 2 * qx, cy + 2 * qy)
                    face.dots[2] = getDot(b, points, cx + 2 * qx + px, cy + 2 * qy + py)
                    face.dots[3] = getDot(b, points, cx + 2 * px + qx, cy + 2 * py + qy)
                    face.dots[4] = getDot(b, points, cx + 2 * px, cy + 2 * py)

                    face = beginFace(b, 5)
                    face.dots[0] = getDot(b, points, cx, cy)
                    face.dots[1] = getDot(b, points, cx + 2 * px, cy + 2 * py)
                    face.dots[2] = getDot(b, points, cx + 2 * px - rx, cy + 2 * py - ry)
                    face.dots[3] = getDot(b, points, cx - 2 * rx + px, cy - 2 * ry + py)
                    face.dots[4] = getDot(b, points, cx - 2 * rx, cy - 2 * ry)

                    face = beginFace(b, 5)
                    face.dots[0] = getDot(b, points, cx, cy)
                    face.dots[1] = getDot(b, points, cx - 2 * rx, cy - 2 * ry)
                    face.dots[2] = getDot(b, points, cx - 2 * rx - qx, cy - 2 * ry - qy)
                    face.dots[3] = getDot(b, points, cx - 2 * qx - rx, cy - 2 * qy - ry)
                    face.dots[4] = getDot(b, points, cx - 2 * qx, cy - 2 * qy)

                    face = beginFace(b, 5)
                    face.dots[0] = getDot(b, points, cx, cy)
                    face.dots[1] = getDot(b, points, cx - 2 * qx, cy - 2 * qy)
                    face.dots[2] = getDot(b, points, cx - 2 * qx - px, cy - 2 * qy - py)
                    face.dots[3] = getDot(b, points, cx - 2 * px - qx, cy - 2 * py - qy)
                    face.dots[4] = getDot(b, points, cx - 2 * px, cy - 2 * py)

                    face = beginFace(b, 5)
                    face.dots[0] = getDot(b, points, cx, cy)
                    face.dots[1] = getDot(b, points, cx - 2 * px, cy - 2 * py)
                    face.dots[2] = getDot(b, points, cx - 2 * px + rx, cy - 2 * py + ry)
                    face.dots[3] = getDot(b, points, cx + 2 * rx - px, cy + 2 * ry - py)
                    face.dots[4] = getDot(b, points, cx + 2 * rx, cy + 2 * ry)
                }
            }
            return finishSimple(b)
        }

        private fun newDodecagonal(width: Int, height: Int): Grid {
            val b = Builder(GridType.DODECAGONAL)
            val a = DODEC_A
            val c = DODEC_B
            b.tileSize = DODEC_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var px = (4 * a + 2 * c) * x
                    val py = (3 * a + 2 * c) * y
                    if (y % 2 != 0) px += 2 * a + c

                    val dodec = beginFace(b, 12)
                    dodec.dots[0] = getDot(b, points, px + a, py - (2 * a + c))
                    dodec.dots[1] = getDot(b, points, px + a + c, py - (a + c))
                    dodec.dots[2] = getDot(b, points, px + (2 * a + c), py - a)
                    dodec.dots[3] = getDot(b, points, px + (2 * a + c), py + a)
                    dodec.dots[4] = getDot(b, points, px + a + c, py + (a + c))
                    dodec.dots[5] = getDot(b, points, px + a, py + (2 * a + c))
                    dodec.dots[6] = getDot(b, points, px - a, py + (2 * a + c))
                    dodec.dots[7] = getDot(b, points, px - (a + c), py + (a + c))
                    dodec.dots[8] = getDot(b, points, px - (2 * a + c), py + a)
                    dodec.dots[9] = getDot(b, points, px - (2 * a + c), py - a)
                    dodec.dots[10] = getDot(b, points, px - (a + c), py - (a + c))
                    dodec.dots[11] = getDot(b, points, px - a, py - (2 * a + c))

                    if (y < height - 1 && (x < width - 1 || y % 2 == 0) &&
                        (x > 0 || y % 2 != 0)
                    ) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + a, py + (2 * a + c))
                        face.dots[1] = getDot(b, points, px, py + (2 * a + 2 * c))
                        face.dots[2] = getDot(b, points, px - a, py + (2 * a + c))
                    }

                    if (y != 0 && (x < width - 1 || y % 2 == 0) &&
                        (x > 0 || y % 2 != 0)
                    ) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px - a, py - (2 * a + c))
                        face.dots[1] = getDot(b, points, px, py - (2 * a + 2 * c))
                        face.dots[2] = getDot(b, points, px + a, py - (2 * a + c))
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newGreatdodecagonal(width: Int, height: Int): Grid {
            val b = Builder(GridType.GREATDODECAGONAL)
            val a = DODEC_A
            val c = DODEC_B
            b.tileSize = DODEC_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var px = (6 * a + 2 * c) * x
                    val py = (3 * a + 3 * c) * y
                    if (y % 2 != 0) px += 3 * a + c

                    val dodec = beginFace(b, 12)
                    dodec.dots[0] = getDot(b, points, px + a, py - (2 * a + c))
                    dodec.dots[1] = getDot(b, points, px + a + c, py - (a + c))
                    dodec.dots[2] = getDot(b, points, px + (2 * a + c), py - a)
                    dodec.dots[3] = getDot(b, points, px + (2 * a + c), py + a)
                    dodec.dots[4] = getDot(b, points, px + a + c, py + (a + c))
                    dodec.dots[5] = getDot(b, points, px + a, py + (2 * a + c))
                    dodec.dots[6] = getDot(b, points, px - a, py + (2 * a + c))
                    dodec.dots[7] = getDot(b, points, px - (a + c), py + (a + c))
                    dodec.dots[8] = getDot(b, points, px - (2 * a + c), py + a)
                    dodec.dots[9] = getDot(b, points, px - (2 * a + c), py - a)
                    dodec.dots[10] = getDot(b, points, px - (a + c), py - (a + c))
                    dodec.dots[11] = getDot(b, points, px - a, py - (2 * a + c))

                    if (y < height - 1 && (x < width - 1 || y % 2 == 0) &&
                        (x > 0 || y % 2 != 0)
                    ) {
                        val face = beginFace(b, 6)
                        face.dots[0] = getDot(b, points, px + a, py + (2 * a + c))
                        face.dots[1] = getDot(b, points, px + 2 * a, py + (2 * a + 2 * c))
                        face.dots[2] = getDot(b, points, px + a, py + (2 * a + 3 * c))
                        face.dots[3] = getDot(b, points, px - a, py + (2 * a + 3 * c))
                        face.dots[4] = getDot(b, points, px - 2 * a, py + (2 * a + 2 * c))
                        face.dots[5] = getDot(b, points, px - a, py + (2 * a + c))
                    }

                    if (y != 0 && (x < width - 1 || y % 2 == 0) &&
                        (x > 0 || y % 2 != 0)
                    ) {
                        val face = beginFace(b, 6)
                        face.dots[0] = getDot(b, points, px - a, py - (2 * a + c))
                        face.dots[1] = getDot(b, points, px - 2 * a, py - (2 * a + 2 * c))
                        face.dots[2] = getDot(b, points, px - a, py - (2 * a + 3 * c))
                        face.dots[3] = getDot(b, points, px + a, py - (2 * a + 3 * c))
                        face.dots[4] = getDot(b, points, px + 2 * a, py - (2 * a + 2 * c))
                        face.dots[5] = getDot(b, points, px + a, py - (2 * a + c))
                    }

                    if (x < width - 1) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + 2 * a + c, py - a)
                        face.dots[1] = getDot(b, points, px + 4 * a + c, py - a)
                        face.dots[2] = getDot(b, points, px + 4 * a + c, py + a)
                        face.dots[3] = getDot(b, points, px + 2 * a + c, py + a)
                    }

                    if (y != 0 && (x < width - 1 || y % 2 == 0)) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + a, py - (2 * a + c))
                        face.dots[1] = getDot(b, points, px + 2 * a, py - (2 * a + 2 * c))
                        face.dots[2] = getDot(b, points, px + 2 * a + c, py - (a + 2 * c))
                        face.dots[3] = getDot(b, points, px + a + c, py - (a + c))
                    }

                    if (y != 0 && (x != 0 || y % 2 != 0)) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px - (a + c), py - (a + c))
                        face.dots[1] = getDot(b, points, px - (2 * a + c), py - (a + 2 * c))
                        face.dots[2] = getDot(b, points, px - 2 * a, py - (2 * a + 2 * c))
                        face.dots[3] = getDot(b, points, px - a, py - (2 * a + c))
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newGreatgreatdodecagonal(width: Int, height: Int): Grid {
            val b = Builder(GridType.GREATGREATDODECAGONAL)
            val a = DODEC_A
            val c = DODEC_B
            b.tileSize = DODEC_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var px = (4 * a + 4 * c) * x
                    val py = (6 * a + 2 * c) * y
                    if (y % 2 != 0) px += 2 * a + 2 * c

                    val dodec = beginFace(b, 12)
                    dodec.dots[0] = getDot(b, points, px + a, py - (2 * a + c))
                    dodec.dots[1] = getDot(b, points, px + a + c, py - (a + c))
                    dodec.dots[2] = getDot(b, points, px + (2 * a + c), py - a)
                    dodec.dots[3] = getDot(b, points, px + (2 * a + c), py + a)
                    dodec.dots[4] = getDot(b, points, px + a + c, py + (a + c))
                    dodec.dots[5] = getDot(b, points, px + a, py + (2 * a + c))
                    dodec.dots[6] = getDot(b, points, px - a, py + (2 * a + c))
                    dodec.dots[7] = getDot(b, points, px - (a + c), py + (a + c))
                    dodec.dots[8] = getDot(b, points, px - (2 * a + c), py + a)
                    dodec.dots[9] = getDot(b, points, px - (2 * a + c), py - a)
                    dodec.dots[10] = getDot(b, points, px - (a + c), py - (a + c))
                    dodec.dots[11] = getDot(b, points, px - a, py - (2 * a + c))

                    if (y != 0 && (x < width - 1 || y % 2 == 0)) {
                        val face = beginFace(b, 6)
                        face.dots[0] = getDot(b, points, px + a + 2 * c, py - (4 * a + c))
                        face.dots[1] = getDot(b, points, px + a + 2 * c, py - (2 * a + c))
                        face.dots[2] = getDot(b, points, px + a + c, py - (a + c))
                        face.dots[3] = getDot(b, points, px + a, py - (2 * a + c))
                        face.dots[4] = getDot(b, points, px + a, py - (4 * a + c))
                        face.dots[5] = getDot(b, points, px + a + c, py - (5 * a + c))
                    }

                    if (x < width - 1) {
                        val face = beginFace(b, 6)
                        face.dots[0] = getDot(b, points, px + 2 * a + 3 * c, py - a)
                        face.dots[1] = getDot(b, points, px + 2 * a + 3 * c, py + a)
                        face.dots[2] = getDot(b, points, px + 2 * a + 2 * c, py + 2 * a)
                        face.dots[3] = getDot(b, points, px + 2 * a + c, py + a)
                        face.dots[4] = getDot(b, points, px + 2 * a + c, py - a)
                        face.dots[5] = getDot(b, points, px + 2 * a + 2 * c, py - 2 * a)
                    }

                    if (y < height - 1 && (x < width - 1 || y % 2 == 0)) {
                        val face = beginFace(b, 6)
                        face.dots[0] = getDot(b, points, px + a + 2 * c, py + (2 * a + c))
                        face.dots[1] = getDot(b, points, px + a + 2 * c, py + (4 * a + c))
                        face.dots[2] = getDot(b, points, px + a + c, py + (5 * a + c))
                        face.dots[3] = getDot(b, points, px + a, py + (4 * a + c))
                        face.dots[4] = getDot(b, points, px + a, py + (2 * a + c))
                        face.dots[5] = getDot(b, points, px + a + c, py + (a + c))
                    }

                    if (y != 0 && x < width - 1) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + a + 2 * c, py - (2 * a + c))
                        face.dots[1] = getDot(b, points, px + 2 * a + 2 * c, py - 2 * a)
                        face.dots[2] = getDot(b, points, px + 2 * a + c, py - a)
                        face.dots[3] = getDot(b, points, px + a + c, py - (a + c))
                    }

                    if (y < height - 1 && x < width - 1) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + 2 * a + 2 * c, py + 2 * a)
                        face.dots[1] = getDot(b, points, px + a + 2 * c, py + (2 * a + c))
                        face.dots[2] = getDot(b, points, px + a + c, py + (a + c))
                        face.dots[3] = getDot(b, points, px + 2 * a + c, py + a)
                    }

                    if (y < height - 1 && (x < width - 1 || y % 2 == 0) &&
                        (x > 0 || y % 2 != 0)
                    ) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + a, py + (2 * a + c))
                        face.dots[1] = getDot(b, points, px + a, py + (4 * a + c))
                        face.dots[2] = getDot(b, points, px - a, py + (4 * a + c))
                        face.dots[3] = getDot(b, points, px - a, py + (2 * a + c))
                    }

                    if (x != 0 && y < height - 1) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px - (2 * a + c), py + a)
                        face.dots[1] = getDot(b, points, px - (a + c), py + (a + c))
                        face.dots[2] = getDot(b, points, px - (a + 2 * c), py + (2 * a + c))
                        face.dots[3] = getDot(b, points, px - (2 * a + 2 * c), py + 2 * a)
                    }

                    if (x != 0 && y != 0) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px - (a + c), py - (a + c))
                        face.dots[1] = getDot(b, points, px - (2 * a + c), py - a)
                        face.dots[2] = getDot(b, points, px - (2 * a + 2 * c), py - 2 * a)
                        face.dots[3] = getDot(b, points, px - (a + 2 * c), py - (2 * a + c))
                    }

                    if (y != 0 && (x < width - 1 || y % 2 == 0) &&
                        (x > 0 || y % 2 != 0)
                    ) {
                        val face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + a, py - (4 * a + c))
                        face.dots[1] = getDot(b, points, px + a, py - (2 * a + c))
                        face.dots[2] = getDot(b, points, px - a, py - (2 * a + c))
                        face.dots[3] = getDot(b, points, px - a, py - (4 * a + c))
                    }

                    if (y != 0 && x < width - 1) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + 3 * a + 2 * c, py - (2 * a + c))
                        face.dots[1] = getDot(b, points, px + 2 * a + 2 * c, py - 2 * a)
                        face.dots[2] = getDot(b, points, px + a + 2 * c, py - (2 * a + c))
                    }

                    if (y < height - 1 && x < width - 1) {
                        val face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + 3 * a + 2 * c, py + (2 * a + c))
                        face.dots[1] = getDot(b, points, px + a + 2 * c, py + (2 * a + c))
                        face.dots[2] = getDot(b, points, px + 2 * a + 2 * c, py + 2 * a)
                    }
                }
            }
            return finishSimple(b)
        }

        private fun newCompassdodecagonal(width: Int, height: Int): Grid {
            val b = Builder(GridType.COMPASSDODECAGONAL)
            val a = DODEC_A
            val c = DODEC_B
            b.tileSize = DODEC_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val px = (4 * a + 2 * c) * x
                    val py = (4 * a + 2 * c) * y

                    val dodec = beginFace(b, 12)
                    dodec.dots[0] = getDot(b, points, px + a, py - (2 * a + c))
                    dodec.dots[1] = getDot(b, points, px + a + c, py - (a + c))
                    dodec.dots[2] = getDot(b, points, px + (2 * a + c), py - a)
                    dodec.dots[3] = getDot(b, points, px + (2 * a + c), py + a)
                    dodec.dots[4] = getDot(b, points, px + a + c, py + (a + c))
                    dodec.dots[5] = getDot(b, points, px + a, py + (2 * a + c))
                    dodec.dots[6] = getDot(b, points, px - a, py + (2 * a + c))
                    dodec.dots[7] = getDot(b, points, px - (a + c), py + (a + c))
                    dodec.dots[8] = getDot(b, points, px - (2 * a + c), py + a)
                    dodec.dots[9] = getDot(b, points, px - (2 * a + c), py - a)
                    dodec.dots[10] = getDot(b, points, px - (a + c), py - (a + c))
                    dodec.dots[11] = getDot(b, points, px - a, py - (2 * a + c))

                    if (x < width - 1 && y < height - 1) {
                        var face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + (2 * a + c), py + a)
                        face.dots[1] = getDot(b, points, px + (3 * a + c), py + (a + c))
                        face.dots[2] = getDot(b, points, px + (a + c), py + (a + c))

                        face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + (3 * a + 2 * c), py + (2 * a + c))
                        face.dots[1] = getDot(b, points, px + (3 * a + c), py + (3 * a + c))
                        face.dots[2] = getDot(b, points, px + (3 * a + c), py + (a + c))

                        face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + (3 * a + c), py + (3 * a + c))
                        face.dots[1] = getDot(b, points, px + (2 * a + c), py + (3 * a + 2 * c))
                        face.dots[2] = getDot(b, points, px + (a + c), py + (3 * a + c))

                        face = beginFace(b, 3)
                        face.dots[0] = getDot(b, points, px + (a + c), py + (a + c))
                        face.dots[1] = getDot(b, points, px + (a + c), py + (3 * a + c))
                        face.dots[2] = getDot(b, points, px + a, py + (2 * a + c))

                        face = beginFace(b, 4)
                        face.dots[0] = getDot(b, points, px + (3 * a + c), py + (a + c))
                        face.dots[1] = getDot(b, points, px + (3 * a + c), py + (3 * a + c))
                        face.dots[2] = getDot(b, points, px + (a + c), py + (3 * a + c))
                        face.dots[3] = getDot(b, points, px + (a + c), py + (a + c))
                    }
                }
            }
            return finishSimple(b)
        }

        // ------------------------------------------------------------
        // Aperiodic generators.
        // ------------------------------------------------------------

        private fun newPenroseP2(width: Int, height: Int, desc: String?): Grid =
            newPenrose(width, height, PenroseTiling.P2, GridType.PENROSE_P2, desc)

        private fun newPenroseP3(width: Int, height: Int, desc: String?): Grid =
            newPenrose(width, height, PenroseTiling.P3, GridType.PENROSE_P3, desc)

        private fun newPenrose(
            width: Int,
            height: Int,
            which: Int,
            type: GridType,
            desc: String?,
        ): Grid {
            if (desc != null && desc.startsWith("G")) {
                return newPenroseLegacy(width, height, which, desc)!!
            }
            val params = decodePenrose(desc!!, which).params!!
            val size = apiSizePenrose(width, height, which)
            val xunit = if (which == PenroseTiling.P2) PENROSE_XUNIT_P2 else PENROSE_XUNIT_P3
            val yunit = if (which == PenroseTiling.P2) PENROSE_YUNIT_P2 else PENROSE_YUNIT_P3
            val b = Builder(type)
            b.tileSize = PENROSE_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            PenroseTiling.generate(params, size.h, size.w) { coords ->
                val face = beginFace(b, 4)
                for (i in 0 until 4) {
                    val x = coords[4 * i + 2] * yunit +
                        nTimesRootK(coords[4 * i + 3] * yunit, 5)
                    val y = coords[4 * i + 0] * xunit +
                        nTimesRootK(coords[4 * i + 1] * xunit, 5)
                    face.dots[i] = getDot(b, points, x, y)
                }
            }
            trimVigorously(b)
            makeConsistent(b)

            val w = width * PENROSE_TILESIZE
            val h = height * PENROSE_TILESIZE
            b.lowestX -= (w - (b.highestX - b.lowestX)) / 2
            b.lowestY -= (h - (b.highestY - b.lowestY)) / 2
            b.highestX = b.lowestX + w
            b.highestY = b.lowestY + h

            return Grid(
                type,
                b.faces,
                b.edges,
                b.dots,
                b.lowestX,
                b.lowestY,
                b.highestX,
                b.highestY,
                b.tileSize,
            )
        }

        /** Legacy Penrose generation. Returns null for an empty patch. */
        private fun newPenroseLegacy(
            width: Int,
            height: Int,
            which: Int,
            desc: String?,
        ): Grid? {
            val (radius, startSize, maxDepth) = PenroseLegacy.calculateSize(
                which,
                PENROSE_TILESIZE,
                width,
                height,
            )
            val b = Builder(if (which == PenroseTiling.P2) GridType.PENROSE_P2 else GridType.PENROSE_P3)
            b.tileSize = PENROSE_TILESIZE
            val points = HashMap<PointKey, GridDot>()

            val numbers = if (desc != null) scanLegacy(desc) else Triple(0, 0, 0)
            check(numbers != null) { "invalid legacy penrose description" }
            val xoff = numbers!!.first
            val yoff = numbers.second
            val aoff = numbers.third

            val xsz = width * PENROSE_TILESIZE
            val ysz = height * PENROSE_TILESIZE
            val xmin = xoff - xsz / 2
            val xmax = xoff + xsz / 2
            val ymin = yoff - ysz / 2
            val ymax = yoff + ysz / 2

            val xs = IntArray(4)
            val ys = IntArray(4)
            PenroseLegacy.generate(which, startSize, maxDepth, aoff) { vs, n, depth ->
                if (depth >= maxDepth) {
                    PenroseLegacy.roundedVertices(vs, n, xs, ys)
                    var inside = true
                    for (i in 0 until n) {
                        if (xs[i] < xmin || xs[i] > xmax || ys[i] < ymin || ys[i] > ymax) {
                            inside = false
                        }
                    }
                    if (inside) {
                        val face = beginFace(b, n)
                        for (i in 0 until n) {
                            face.dots[i] = getDot(b, points, xs[i], ys[i])
                        }
                    }
                }
            }

            if (b.faces.isEmpty() || b.dots.isEmpty()) return null
            trimVigorously(b)
            if (b.faces.isEmpty() || b.dots.isEmpty()) return null
            makeConsistent(b)

            b.lowestX -= ((xmax - xmin) - (b.highestX - b.lowestX)) / 2
            b.highestX = b.lowestX + (xmax - xmin)
            b.lowestY -= ((ymax - ymin) - (b.highestY - b.lowestY)) / 2
            b.highestY = b.lowestY + (ymax - ymin)

            return Grid(
                b.type,
                b.faces,
                b.edges,
                b.dots,
                b.lowestX,
                b.lowestY,
                b.highestX,
                b.highestY,
                b.tileSize,
            )
        }

        private fun newHats(width: Int, height: Int, desc: String?): Grid {
            val params = decodeHat(desc!!).params!!
            val b = Builder(GridType.HATS)
            b.tileSize = HATS_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            HatTiling.generate(params, width, height) { coords ->
                val face = beginFace(b, 14)
                for (i in 0 until 14) {
                    face.dots[i] = getDot(
                        b,
                        points,
                        coords[2 * i] * HATS_XUNIT,
                        coords[2 * i + 1] * HATS_YUNIT,
                    )
                }
            }
            trimVigorously(b)
            makeConsistent(b)
            return Grid(
                GridType.HATS,
                b.faces,
                b.edges,
                b.dots,
                b.lowestX,
                b.lowestY,
                b.highestX,
                b.highestY,
                b.tileSize,
            )
        }

        private fun newSpectres(width: Int, height: Int, desc: String?): Grid {
            val params = decodeSpectre(desc!!).params!!
            val width2 = width * SPECTRE_SQUARELEN
            val height2 = height * SPECTRE_SQUARELEN
            val b = Builder(GridType.SPECTRES)
            b.tileSize = SPECTRE_TILESIZE
            val points = HashMap<PointKey, GridDot>()
            SpectreTiling.generate(params, width2, height2) { coords ->
                val face = beginFace(b, 14)
                for (i in 0 until 14) {
                    val x = coords[4 * i + 0] * SPECTRE_UNIT +
                        nTimesRootK(coords[4 * i + 1] * SPECTRE_UNIT, 3)
                    val y = coords[4 * i + 2] * SPECTRE_UNIT +
                        nTimesRootK(coords[4 * i + 3] * SPECTRE_UNIT, 3)
                    face.dots[i] = getDot(b, points, x, y)
                }
            }
            trimVigorously(b)
            makeConsistent(b)

            val w = width2 * SPECTRE_UNIT
            val h = height2 * SPECTRE_UNIT
            b.lowestX -= (w - (b.highestX - b.lowestX)) / 2
            b.lowestY -= (h - (b.highestY - b.lowestY)) / 2
            b.highestX = b.lowestX + w
            b.highestY = b.lowestY + h

            return Grid(
                GridType.SPECTRES,
                b.faces,
                b.edges,
                b.dots,
                b.lowestX,
                b.lowestY,
                b.highestX,
                b.highestY,
                b.tileSize,
            )
        }

        private fun computeSizeAperiodic(
            type: GridType,
            width: Int,
            height: Int,
        ): GridSize = when (type) {
            GridType.PENROSE_P2, GridType.PENROSE_P3 -> GridSize(
                PENROSE_TILESIZE,
                PENROSE_TILESIZE * width,
                PENROSE_TILESIZE * height,
            )
            GridType.HATS -> GridSize(
                HATS_TILESIZE,
                width * HATS_XUNIT * HATS_XSQUARELEN,
                height * HATS_YUNIT * HATS_YSQUARELEN,
            )
            else -> GridSize(
                SPECTRE_TILESIZE,
                width * SPECTRE_UNIT * SPECTRE_SQUARELEN,
                height * SPECTRE_UNIT * SPECTRE_SQUARELEN,
            )
        }
    }
}

/** The result of `grid_compute_size`. */
data class GridSize(val tileSize: Int, val xextent: Int, val yextent: Int)
