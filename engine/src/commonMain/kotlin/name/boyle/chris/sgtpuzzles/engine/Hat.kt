package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `hat.c`: the aperiodic hat tiling generator.
 *
 * The algorithm walks a patch of kites in serpentine raster order and
 * translates each kite position into combinatorial coordinates. Random
 * choices are made when the prototype needs a higher-order metatile, so
 * the order of those draws is part of the seed contract.
 *
 * The constant tables live in `GridTilingsTables.kt`; they are
 * generated from the upstream headers.
 */
internal object HatTiling {

    private const val HAT_KITES = HatTables.HAT_KITES

    /** A point in the kites lattice: x + y * r, r = exp(i*pi/3). */
    class Point(val x: Int, val y: Int)

    private fun scale(s: Int, a: Point) = Point(s * a.x, s * a.y)
    private fun add(a: Point, b: Point) = Point(a.x + b.x, a.y + b.y)

    class Kite(
        var centre: Point,
        var left: Point,
        var right: Point,
        var outer: Point,
    )

    private fun kiteLeft(k: Kite): Kite = Kite(
        k.centre,
        add(add(k.centre, k.left), scale(-1, k.right)),
        k.left,
        add(scale(2, k.left), scale(-1, k.outer)),
    )

    private fun kiteRight(k: Kite): Kite = Kite(
        k.centre,
        k.right,
        add(add(k.centre, k.right), scale(-1, k.left)),
        add(scale(2, k.right), scale(-1, k.outer)),
    )

    private fun kiteForwardLeft(k: Kite): Kite = Kite(
        add(scale(2, k.left), scale(-1, k.centre)),
        add(add(k.right, k.left), scale(-1, k.centre)),
        k.left,
        k.outer,
    )

    private fun kiteForwardRight(k: Kite): Kite = Kite(
        add(scale(2, k.right), scale(-1, k.centre)),
        k.right,
        add(add(k.left, k.right), scale(-1, k.centre)),
        k.outer,
    )

    private const val KS_LEFT = 0
    private const val KS_RIGHT = 1
    private const val KS_F_LEFT = 2
    private const val KS_F_RIGHT = 3

    private fun kiteStep(k: Kite, step: Int): Kite = when (step) {
        KS_LEFT -> kiteLeft(k)
        KS_RIGHT -> kiteRight(k)
        KS_F_LEFT -> kiteForwardLeft(k)
        else -> kiteForwardRight(k)
    }

    private const val KE_NKEEP = 3

    /** The serpentine kite enumerator from `hat-internal.h`. */
    class KiteEnum {
        var state = 0
        var x = 0
        var y = 0
        var w = 0
        var h = 0
        var currIndex = 0
        var lastIndex = 0
        var lastStep = KS_RIGHT
        var recent = Array(KE_NKEEP) { Kite(Point(0, 0), Point(0, 0), Point(0, 0), Point(0, 0)) }
        var curr: Kite = recent[0]

        fun first(w: Int, h: Int) {
            val start = Kite(Point(0, 0), Point(0, 3), Point(3, 0), Point(2, 2))
            for (i in 0 until KE_NKEEP) {
                recent[i] = Kite(start.centre, start.left, start.right, start.outer)
            }
            currIndex = 0
            curr = recent[currIndex]
            state = 1
            this.w = w
            this.h = h
            x = 0
            y = 0
        }

        fun next(): Boolean {
            val lastBut1 = lastIndex
            lastIndex = currIndex
            currIndex = (currIndex + 1) % KE_NKEEP
            curr = recent[currIndex]

            when (state) {
                1 -> {
                    lastStep = KS_F_RIGHT
                    state = 2
                }
                2 -> {
                    if (x + 1 >= w) {
                        lastStep = KS_F_RIGHT
                        state = 4
                    } else {
                        lastStep = KS_RIGHT
                        state = 3
                        x++
                    }
                }
                3 -> {
                    lastStep = KS_RIGHT
                    state = 1
                }
                4 -> {
                    lastStep = KS_LEFT
                    state = 5
                }
                5 -> {
                    lastStep = KS_RIGHT
                    lastIndex = lastBut1
                    state = 6
                }
                6 -> {
                    if (x <= 0) {
                        if (y + 1 >= h) {
                            state = 0
                            return false
                        }
                        lastStep = KS_RIGHT
                        state = 9
                        y++
                    } else {
                        lastStep = KS_F_RIGHT
                        state = 7
                        x--
                    }
                }
                7 -> {
                    lastStep = KS_RIGHT
                    state = 8
                }
                8 -> {
                    lastStep = KS_RIGHT
                    state = 6
                }
                9 -> {
                    lastStep = KS_RIGHT
                    state = 10
                }
                10 -> {
                    lastStep = KS_RIGHT
                    state = 11
                }
                11 -> {
                    if (x + 1 >= w) {
                        lastStep = KS_F_RIGHT
                        lastIndex = lastBut1
                        state = 12
                    } else {
                        lastStep = KS_F_RIGHT
                        state = 9
                        x++
                    }
                }
                12 -> {
                    lastStep = KS_F_RIGHT
                    state = 13
                }
                13 -> {
                    if (x <= 0) {
                        if (y + 1 >= h) {
                            state = 0
                            return false
                        }
                        lastStep = KS_LEFT
                        state = 1
                        y++
                    } else {
                        lastStep = KS_RIGHT
                        state = 14
                        x--
                    }
                }
                14 -> {
                    lastStep = KS_RIGHT
                    state = 12
                }
                else -> return false
            }

            val stepped = kiteStep(recent[lastIndex], lastStep)
            recent[currIndex].centre = stepped.centre
            recent[currIndex].left = stepped.left
            recent[currIndex].right = stepped.right
            recent[currIndex].outer = stepped.outer
            return true
        }
    }

    // ----------------------------------------------------------------
    // Metatile parents and coordinates.
    // ----------------------------------------------------------------

    private class Mpp(val type: Int, val index: Int, val probability: Long)

    private const val PROB_H = 10_000_000L
    private const val PROB_T = 1_458_980L
    private const val PROB_P = 7_082_039L
    private const val PROB_F = 11_458_980L

    private val parentsH = arrayOf(
        Mpp(HatTables.TT_H, 0, PROB_H),
        Mpp(HatTables.TT_H, 1, PROB_H),
        Mpp(HatTables.TT_H, 2, PROB_H),
        Mpp(HatTables.TT_T, 0, PROB_T),
        Mpp(HatTables.TT_P, 0, PROB_P),
        Mpp(HatTables.TT_P, 1, PROB_P),
        Mpp(HatTables.TT_F, 0, PROB_F),
        Mpp(HatTables.TT_F, 1, PROB_F),
    )
    private val parentsT = arrayOf(Mpp(HatTables.TT_H, 3, PROB_H))
    private val parentsP = arrayOf(
        Mpp(HatTables.TT_H, 4, PROB_H),
        Mpp(HatTables.TT_H, 5, PROB_H),
        Mpp(HatTables.TT_H, 6, PROB_H),
        Mpp(HatTables.TT_P, 4, PROB_P),
        Mpp(HatTables.TT_F, 3, PROB_F),
    )
    private val parentsF = arrayOf(
        Mpp(HatTables.TT_H, 8, PROB_H),
        Mpp(HatTables.TT_H, 9, PROB_H),
        Mpp(HatTables.TT_H, 12, PROB_H),
        Mpp(HatTables.TT_P, 5, PROB_P),
        Mpp(HatTables.TT_P, 10, PROB_P),
        Mpp(HatTables.TT_F, 6, PROB_F),
        Mpp(HatTables.TT_F, 8, PROB_F),
        Mpp(HatTables.TT_F, 10, PROB_F),
    )

    private val possibleParents = arrayOf(parentsH, parentsT, parentsP, parentsF)

    private val startingHats = arrayOf(
        Mpp(HatTables.TT_H, 0, PROB_H),
        Mpp(HatTables.TT_H, 1, PROB_H),
        Mpp(HatTables.TT_H, 2, PROB_H),
        Mpp(HatTables.TT_H, 3, PROB_H),
        Mpp(HatTables.TT_T, 0, PROB_P),
        Mpp(HatTables.TT_P, 0, PROB_P),
        Mpp(HatTables.TT_P, 1, PROB_P),
        Mpp(HatTables.TT_F, 0, PROB_F),
        Mpp(HatTables.TT_F, 1, PROB_F),
    )

    private fun chooseMpp(random: RandomState, parents: Array<Mpp>): Mpp {
        var limit = 0L
        for (parent in parents) limit += parent.probability
        var value = random.upto(limit)
        for (i in 0 until parents.size - 1) {
            if (value < parents[i].probability) return parents[i]
            value -= parents[i].probability
        }
        return parents.last()
    }

    class HatCoords {
        var index = IntArray(0)
        var type = IntArray(0)
        var nc = 0

        fun makeSpace(size: Int) {
            if (index.size < size) {
                var newSize = index.size * 5 / 4 + 16
                if (newSize < size) newSize = size
                index = index.copyOf(newSize)
                type = type.copyOf(newSize)
            }
        }

        fun copy(): HatCoords {
            val out = HatCoords()
            out.makeSpace(nc)
            index.copyInto(out.index, 0, 0, nc)
            type.copyInto(out.type, 0, 0, nc)
            out.nc = nc
            return out
        }
    }

    /** A patch of tiling, as stored in a game description. */
    class PatchParams(
        var coords: IntArray,
        var finalMetatile: Char,
    )

    private class Context(var random: RandomState?, val prototype: HatCoords)

    private fun metatileCharToEnum(c: Char): Int = when (c) {
        'H' -> HatTables.TT_H
        'T' -> HatTables.TT_T
        'P' -> HatTables.TT_P
        'F' -> HatTables.TT_F
        else -> -1
    }

    private fun initialCoords(ctx: Context): HatCoords = ctx.prototype.copy()

    private fun extendCoords(ctx: Context, hc: HatCoords, n: Int) {
        if (ctx.prototype.nc < n) {
            ctx.prototype.makeSpace(n)
            while (ctx.prototype.nc < n) {
                val type = ctx.prototype.type[ctx.prototype.nc - 1]
                val parent = if (ctx.random != null) {
                    chooseMpp(ctx.random!!, possibleParents[type])
                } else {
                    possibleParents[type][0]
                }
                ctx.prototype.index[ctx.prototype.nc - 1] = parent.index
                ctx.prototype.index[ctx.prototype.nc] = -1
                ctx.prototype.type[ctx.prototype.nc] = parent.type
                ctx.prototype.nc++
            }
        }

        hc.makeSpace(n)
        while (hc.nc < n) {
            hc.index[hc.nc - 1] = ctx.prototype.index[hc.nc - 1]
            hc.index[hc.nc] = -1
            hc.type[hc.nc] = ctx.prototype.type[hc.nc]
            hc.nc++
        }
    }

    private fun tryStepCoordsKitemap(
        ctx: Context,
        hcIn: HatCoords,
        step: Int,
    ): HatCoords? {
        extendCoords(ctx, hcIn, 4)
        val kite = hcIn.index[0]
        val hat = hcIn.index[1]
        val meta = hcIn.index[2]
        val meta2Type = hcIn.type[3]
        val entries = HatTables.kitemap[meta2Type]
        val base = 3 * HatTables.kitemapIndex(step, kite, hat, meta)
        val keKite = entries[base]
        if (keKite >= 0) {
            val hcOut = hcIn.copy()
            val keHat = entries[base + 1]
            val keMeta = entries[base + 2]
            hcOut.index[2] = keMeta
            hcOut.type[2] = HatTables.children[meta2Type][keMeta]
            hcOut.index[1] = keHat
            hcOut.type[1] = HatTables.TT_HAT
            hcOut.index[0] = keKite
            hcOut.type[0] = HatTables.TT_KITE
            return hcOut
        }
        return null
    }

    private fun tryStepCoordsMetamap(
        ctx: Context,
        hcIn: HatCoords,
        step: Int,
        depth: Int,
    ): HatCoords? {
        extendCoords(ctx, hcIn, depth + 3)
        val metaOrig = hcIn.index[depth]
        val meta2Orig = hcIn.index[depth + 1]
        val meta3Type = hcIn.type[depth + 2]
        var meta = metaOrig
        var meta2 = meta2Orig
        var hcTmp: HatCoords? = null

        while (true) {
            val hcCurr = hcTmp ?: hcIn
            val hcOut = if (depth > 2) {
                tryStepCoordsMetamap(ctx, hcCurr, step, depth - 1)
            } else {
                tryStepCoordsKitemap(ctx, hcCurr, step)
            }
            if (hcOut != null) return hcOut

            val table = HatTables.metamap[meta3Type]
            val base = 2 * HatTables.metamapIndex(meta, meta2)
            val meMeta = table[base]
            val meMeta2 = table[base + 1]
            check(meMeta != -1) { "hat metamap is missing an entry" }
            if (meMeta == metaOrig && meMeta2 == meta2Orig) return null

            meta = meMeta
            meta2 = meMeta2

            if (hcTmp == null) hcTmp = hcIn.copy()
            val tmp = hcTmp!!
            tmp.index[depth + 1] = meta2
            tmp.type[depth + 1] = HatTables.children[meta3Type][meta2]
            tmp.index[depth] = meta
            tmp.type[depth] = HatTables.children[tmp.type[depth + 1]][meta]
        }
    }

    private fun step(ctx: Context, hcIn: HatCoords, step: Int): HatCoords {
        tryStepCoordsKitemap(ctx, hcIn, step)?.let { return it }
        var depth = 2
        while (true) {
            tryStepCoordsMetamap(ctx, hcIn, step, depth)?.let { return it }
            depth++
        }
    }

    // ----------------------------------------------------------------
    // Public entry points.
    // ----------------------------------------------------------------

    private fun initRandom(random: RandomState): Context {
        val startingHat = chooseMpp(random, startingHats)
        val prototype = HatCoords()
        prototype.makeSpace(3)
        prototype.type[2] = startingHat.type
        prototype.index[2] = -1
        prototype.type[1] = HatTables.TT_HAT
        prototype.index[1] = startingHat.index
        prototype.type[0] = HatTables.TT_KITE
        prototype.index[0] = random.upto(HAT_KITES.toLong()).toInt()
        prototype.nc = 3
        return Context(random, prototype)
    }

    private fun initFromParams(params: PatchParams): Context {
        val prototype = HatCoords()
        prototype.makeSpace(params.coords.size + 1)
        prototype.nc = params.coords.size + 1
        for (i in params.coords.indices) prototype.index[i] = params.coords[i]
        prototype.type[params.coords.size] = metatileCharToEnum(params.finalMetatile)
        prototype.index[params.coords.size] = -1
        prototype.type[0] = HatTables.TT_KITE
        prototype.type[1] = HatTables.TT_HAT
        for (i in params.coords.size - 1 downTo 2) {
            val metatile = prototype.type[i + 1]
            prototype.type[i] = HatTables.children[metatile][prototype.index[i]]
        }
        return Context(null, prototype)
    }

    /** `hat_tiling_randomise`: choose a coordinate set for the area. */
    fun randomise(width: Int, height: Int, random: RandomState): PatchParams {
        val ctx = initRandom(random)
        val coords = arrayOfNulls<HatCoords>(KE_NKEEP)
        val enumerator = KiteEnum()
        enumerator.first(width, height)
        coords[enumerator.currIndex] = initialCoords(ctx)

        while (enumerator.next()) {
            coords[enumerator.currIndex] = step(
                ctx,
                coords[enumerator.lastIndex]!!,
                enumerator.lastStep,
            )
        }

        val ncoords = ctx.prototype.nc - 1
        val result = IntArray(ncoords)
        for (i in 0 until ncoords) result[i] = ctx.prototype.index[i]
        return PatchParams(result, HatTables.tileChars[ctx.prototype.type[ncoords]])
    }

    fun paramsInvalid(params: PatchParams): String? {
        if (params.coords.size < 3) {
            return "Grid parameters require at least three coordinates"
        }
        if (metatileCharToEnum(params.finalMetatile) < 0) {
            return "Grid parameters contain an invalid final metatile"
        }
        if (params.coords[0] >= 8) {
            return "Grid parameters contain an invalid kite index"
        }

        var metatile = metatileCharToEnum(params.finalMetatile)
        for (i in params.coords.size - 1 downTo 2) {
            if (params.coords[i] >= HatTables.nchildren[metatile]) {
                return "Grid parameters contain an invalid metatile index"
            }
            metatile = HatTables.children[metatile][params.coords[i]]
        }

        if (params.coords[1] >= HatTables.hatsInMetatile[metatile]) {
            return "Grid parameters contain an invalid hat index"
        }

        return null
    }

    /** `hat_tiling_generate`: one polygon per hat, as 28 interleaved ints. */
    fun generate(params: PatchParams, width: Int, height: Int, tile: (IntArray) -> Unit) {
        val ctx = initFromParams(params)
        val coords = arrayOfNulls<HatCoords>(KE_NKEEP)
        val enumerator = KiteEnum()
        enumerator.first(width, height)
        coords[enumerator.currIndex] = initialCoords(ctx)

        maybeReportHat(
            width = width,
            height = height,
            kiteIn = enumerator.curr,
            hc = coords[enumerator.currIndex]!!,
            tile = tile,
        )

        while (enumerator.next()) {
            coords[enumerator.currIndex] = step(
                ctx,
                coords[enumerator.lastIndex]!!,
                enumerator.lastStep,
            )
            maybeReportHat(
                width = width,
                height = height,
                kiteIn = enumerator.curr,
                hc = coords[enumerator.currIndex]!!,
                tile = tile,
            )
        }
    }

    private fun maybeReportHat(
        width: Int,
        height: Int,
        kiteIn: Kite,
        hc: HatCoords,
        tile: (IntArray) -> Unit,
    ) {
        if (hc.index[0] != 0) return
        var kite = Kite(kiteIn.centre, kiteIn.left, kiteIn.right, kiteIn.outer)

        var reversed = false
        if (hc.type[2] == HatTables.TT_H && hc.index[1] == 3) {
            reversed = true
            val tmp = kite.left
            kite.left = kite.right
            kite.right = tmp
        }

        val vertices = arrayOfNulls<Point>(14)
        vertices[0] = kite.centre
        vertices[1] = kite.right
        vertices[2] = kite.outer
        vertices[3] = kite.left
        kite = kiteLeft(kite)
        kite = kiteForwardRight(kite)
        vertices[4] = kite.centre
        kite = kiteRight(kite)
        vertices[5] = kite.right
        vertices[6] = kite.outer
        kite = kiteForwardLeft(kite)
        vertices[7] = kite.left
        vertices[8] = kite.centre
        kite = kiteRight(kite)
        kite = kiteRight(kite)
        kite = kiteRight(kite)
        vertices[9] = kite.right
        vertices[10] = kite.outer
        vertices[11] = kite.left
        kite = kiteLeft(kite)
        vertices[12] = kite.outer
        vertices[13] = kite.left

        if (reversed) {
            var i = 0
            var j = 13
            while (i < j) {
                val tmp = vertices[i]
                vertices[i] = vertices[j]
                vertices[j] = tmp
                i++
                j--
            }
        }

        val coords = IntArray(28)
        for (i in 0 until 14) {
            val v = vertices[i]!!
            val x = (v.x * 2 + v.y) / 3
            val y = v.y
            if (x < 0 || x > 4 * width || y < 0 || y > 6 * height) return
            coords[2 * i] = x
            coords[2 * i + 1] = y
        }

        tile(coords)
    }
}
