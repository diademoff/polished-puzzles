package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A port of upstream `cube.c`: the rolling-blue-faces puzzle.
 *
 * The player rolls a polyhedron over a grid. Every square may be blue.
 * The face of the die that is down when it lands on a square takes that
 * square's colour, and the square takes the old colour of the bottom
 * face. The game is complete when every face of the die is blue.
 *
 * The solid never rotates in the state. [State.facecolours] is a
 * permutation over a fixed solid, and the bottom face is always the same
 * geometric face of that static solid. The drawing applies one rotation
 * to a copy of the solid, so a settled frame depends only on the current
 * square and the face colours.
 *
 * The seam has no animation clock. `game_redraw` in C draws an
 * intermediate frame while the die rolls, but the seam draws a settled
 * frame only. `previous`, `angle`, `spkey`, and `sgkey` are therefore not
 * part of [State].
 *
 * Float rules are part of parity. The geometry uses [Float] for every
 * coordinate and matrix product. The trigonometric calls are the double
 * functions, cast back to float, as C promotes a float to double for
 * `cos`, `sin`, `acos`, and `atan2`. Grid coordinates truncate toward
 * zero. Face coordinates use `floor`.
 *
 * Generation cost is the C cost: `validate_params` and `new_game_desc`
 * each enumerate every grid square, and [validateParams] keeps that
 * behavior. The port holds one Kotlin object per square, so a phone
 * process runs out of heap at an area that C still generates. D-036
 * refuses an area above [MAX_AREA] (500 by 500 cells) before the first
 * allocation or draw.
 */
object Cube : Game {

    /** `enum { COL_BACKGROUND, COL_BORDER, COL_BLUE, NCOLOURS }`. */
    private const val COL_BACKGROUND = 0
    private const val COL_BORDER = 1
    private const val COL_BLUE = 2
    private const val NCOLOURS = 3

    /** `enum { LEFT, RIGHT, UP, DOWN, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT }`. */
    private const val LEFT = 0
    private const val RIGHT = 1
    private const val UP = 2
    private const val DOWN = 3
    private const val UP_LEFT = 4
    private const val UP_RIGHT = 5
    private const val DOWN_LEFT = 6
    private const val DOWN_RIGHT = 7

    /** `enum { TETRAHEDRON, CUBE, OCTAHEDRON, ICOSAHEDRON }`. */
    const val TETRAHEDRON = 0
    const val CUBE = 1
    const val OCTAHEDRON = 2
    const val ICOSAHEDRON = 3

    private const val PREFERRED_GRID_SCALE = 48

    /**
     * D-036: the measured generation cap, 500 by 500 cells.
     *
     * The phone generates 250,000 cells in 0.059 s and needs 48 MB of
     * heap; 1,000,000 cells needs 160 MB and 1,960,000 cells runs out of
     * the 256 MB Android growth limit. Time is not the constraint here,
     * the heap is. The cap is the largest measured area that leaves a
     * warm app most of its heap, and every preset is far below it.
     */
    internal const val MAX_AREA = 250_000

    /** The D-036 message, in the style of the other generation guards. */
    const val AREA_TOO_LARGE = "Grid area is too large for this build"

    /** The word of each direction, in the order of the C `enum`. */
    private val DIRECTION_NAMES = listOf(
        "left",
        "right",
        "up",
        "down",
        "up and left",
        "up and right",
        "down and left",
        "down and right",
    )

    /** `SQ(x)`: a float square, not a double one. */
    private fun sq(x: Float): Float = x * x

    /** `APPROXEQ(x, y)`: `SQ(x - y) < 0.1F`. */
    private fun approxEq(x: Float, y: Float): Boolean = sq(x - y) < 0.1f

    /** `struct solid` with the tables from `cube.c`. Faces are immutable. */
    internal class Solid(
        val nvertices: Int,
        val vertices: FloatArray,
        val order: Int,
        val nfaces: Int,
        val faces: IntArray,
        val normals: FloatArray,
        val shear: Float,
        val border: Float,
    ) {
        /** A structure copy: the matrices rotate the vertices and normals. */
        fun copy(): Solid = Solid(
            nvertices,
            vertices.copyOf(),
            order,
            nfaces,
            faces,
            normals.copyOf(),
            shear,
            border,
        )
    }

    private val TETRAHEDRON_SOLID = Solid(
        nvertices = 4,
        vertices = floatArrayOf(
            0.0f, -0.57735026919f, -0.20412414523f,
            -0.5f, 0.28867513459f, -0.20412414523f,
            0.0f, -0.0f, 0.6123724357f,
            0.5f, 0.28867513459f, -0.20412414523f,
        ),
        order = 3,
        nfaces = 4,
        faces = intArrayOf(
            0, 2, 1, 3, 1, 2, 2, 0, 3, 1, 3, 0,
        ),
        normals = floatArrayOf(
            -0.816496580928f, -0.471404520791f, 0.333333333334f,
            0.0f, 0.942809041583f, 0.333333333333f,
            0.816496580928f, -0.471404520791f, 0.333333333334f,
            0.0f, 0.0f, -1.0f,
        ),
        shear = 0.0f,
        border = 0.3f,
    )

    private val CUBE_SOLID = Solid(
        nvertices = 8,
        vertices = floatArrayOf(
            -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, +0.5f,
            -0.5f, +0.5f, -0.5f, -0.5f, +0.5f, +0.5f,
            +0.5f, -0.5f, -0.5f, +0.5f, -0.5f, +0.5f,
            +0.5f, +0.5f, -0.5f, +0.5f, +0.5f, +0.5f,
        ),
        order = 4,
        nfaces = 6,
        faces = intArrayOf(
            0, 1, 3, 2, 1, 5, 7, 3, 5, 4, 6, 7,
            4, 0, 2, 6, 0, 4, 5, 1, 3, 7, 6, 2,
        ),
        normals = floatArrayOf(
            -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, +1.0f,
            +1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f,
            0.0f, -1.0f, 0.0f, 0.0f, +1.0f, 0.0f,
        ),
        shear = 0.3f,
        border = 0.5f,
    )

    private val OCTAHEDRON_SOLID = Solid(
        nvertices = 6,
        vertices = floatArrayOf(
            -0.5f, -0.28867513459472505f, 0.4082482904638664f,
            0.5f, 0.28867513459472505f, -0.4082482904638664f,
            -0.5f, 0.28867513459472505f, -0.4082482904638664f,
            0.5f, -0.28867513459472505f, 0.4082482904638664f,
            0.0f, -0.57735026918945009f, -0.4082482904638664f,
            0.0f, 0.57735026918945009f, 0.4082482904638664f,
        ),
        order = 3,
        nfaces = 8,
        faces = intArrayOf(
            4, 0, 2, 0, 5, 2, 0, 4, 3, 5, 0, 3,
            1, 4, 2, 5, 1, 2, 4, 1, 3, 1, 5, 3,
        ),
        normals = floatArrayOf(
            -0.816496580928f, -0.471404520791f, -0.333333333334f,
            -0.816496580928f, 0.471404520791f, 0.333333333334f,
            0.0f, -0.942809041583f, 0.333333333333f,
            0.0f, 0.0f, 1.0f,
            0.0f, 0.0f, -1.0f,
            0.0f, 0.942809041583f, -0.333333333333f,
            0.816496580928f, -0.471404520791f, -0.333333333334f,
            0.816496580928f, 0.471404520791f, 0.333333333334f,
        ),
        shear = 0.0f,
        border = 0.5f,
    )

    private val ICOSAHEDRON_SOLID = Solid(
        nvertices = 12,
        vertices = floatArrayOf(
            0.0f, 0.57735026919f, 0.75576131408f,
            0.0f, -0.93417235896f, 0.17841104489f,
            0.0f, 0.93417235896f, -0.17841104489f,
            0.0f, -0.57735026919f, -0.75576131408f,
            -0.5f, -0.28867513459f, 0.75576131408f,
            -0.5f, 0.28867513459f, -0.75576131408f,
            0.5f, -0.28867513459f, 0.75576131408f,
            0.5f, 0.28867513459f, -0.75576131408f,
            -0.80901699437f, 0.46708617948f, 0.17841104489f,
            0.80901699437f, 0.46708617948f, 0.17841104489f,
            -0.80901699437f, -0.46708617948f, -0.17841104489f,
            0.80901699437f, -0.46708617948f, -0.17841104489f,
        ),
        order = 3,
        nfaces = 20,
        faces = intArrayOf(
            8, 0, 2, 0, 9, 2, 1, 10, 3, 11, 1, 3, 0, 4, 6,
            4, 1, 6, 5, 2, 7, 3, 5, 7, 4, 8, 10, 8, 5, 10,
            9, 6, 11, 7, 9, 11, 0, 8, 4, 9, 0, 6, 10, 1, 4,
            1, 11, 6, 8, 2, 5, 2, 9, 7, 3, 10, 5, 11, 3, 7,
        ),
        normals = floatArrayOf(
            -0.356822089773f, 0.87267799625f, 0.333333333333f,
            0.356822089773f, 0.87267799625f, 0.333333333333f,
            -0.356822089773f, -0.87267799625f, -0.333333333333f,
            0.356822089773f, -0.87267799625f, -0.333333333333f,
            -0.0f, 0.0f, 1.0f,
            0.0f, -0.666666666667f, 0.745355992501f,
            0.0f, 0.666666666667f, -0.745355992501f,
            0.0f, 0.0f, -1.0f,
            -0.934172358963f, -0.12732200375f, 0.333333333333f,
            -0.934172358963f, 0.12732200375f, -0.333333333333f,
            0.934172358963f, -0.12732200375f, 0.333333333333f,
            0.934172358963f, 0.12732200375f, -0.333333333333f,
            -0.57735026919f, 0.333333333334f, 0.745355992501f,
            0.57735026919f, 0.333333333334f, 0.745355992501f,
            -0.57735026919f, -0.745355992501f, 0.333333333334f,
            0.57735026919f, -0.745355992501f, 0.333333333334f,
            -0.57735026919f, 0.745355992501f, -0.333333333334f,
            0.57735026919f, 0.745355992501f, -0.333333333334f,
            -0.57735026919f, -0.333333333334f, -0.745355992501f,
            0.57735026919f, -0.333333333334f, -0.745355992501f,
        ),
        shear = 0.0f,
        border = 0.8f,
    )

    private val SOLIDS = arrayOf(
        TETRAHEDRON_SOLID,
        CUBE_SOLID,
        OCTAHEDRON_SOLID,
        ICOSAHEDRON_SOLID,
    )

    /** One `struct grid_square`. */
    internal class GridSquare(
        val x: Float,
        val y: Float,
        val npoints: Int,
        val points: FloatArray,
        val directions: IntArray,
        val flip: Boolean,
        val tetraClass: Int,
    )

    /** `game_grid`: immutable after construction, so states share it. */
    internal class Grid(val squares: List<GridSquare>)

    /** `struct game_params`. */
    data class Params(
        val solid: Int,
        val d1: Int,
        val d2: Int,
    ) : Game.Params {

        val text: String get() = encodeParams(this, true)

        override fun copy(): Game.Params = Params(solid, d1, d2)
    }

    /**
     * `struct game_state` without the animation fields. The grid is
     * immutable and shared. [facecolours] has one 0 or 1 entry per solid
     * face, and [bluemask] has one entry per grid square.
     */
    class State internal constructor(
        val params: Params,
        internal val solid: Solid,
        var facecolours: IntArray,
        internal val grid: Grid,
        var bluemask: BooleanArray,
        var current: Int,
        var completed: Int,
        var movecount: Int,
    ) : Game.State {

        /** `SET_SQUARE` with a boolean payload. */
        fun setSquare(index: Int, value: Boolean) {
            bluemask[index] = value
        }

        /** `GET_SQUARE`. */
        fun getSquare(index: Int): Boolean = bluemask[index]

        /** A deep copy of the mutable arrays; the grid and solid are shared. */
        override fun copy(): Game.State = State(
            params = params,
            solid = solid,
            facecolours = facecolours.copyOf(),
            grid = grid,
            bluemask = bluemask.copyOf(),
            current = current,
            completed = completed,
            movecount = movecount,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                facecolours.contentEquals(other.facecolours) &&
                bluemask.contentEquals(other.bluemask) &&
                current == other.current &&
                completed == other.completed &&
                movecount == other.movecount

        override fun hashCode(): Int {
            var result = params.hashCode()
            result = 31 * result + facecolours.contentHashCode()
            result = 31 * result + bluemask.contentHashCode()
            result = 31 * result + current
            result = 31 * result + completed
            result = 31 * result + movecount
            return result
        }

        override fun toString(): String =
            "Cube.State(current=$current, completed=$completed, " +
                "movecount=$movecount, facecolours=${facecolours.toList()})"
    }

    override val name: String = "Cube"
    override val backendName: String = "cube"
    override val preferredTileSize: Int = PREFERRED_GRID_SCALE
    override val wantsStatusBar: Boolean = true
    override val canSolve: Boolean = false
    override val canFormatAsText: Boolean = false

    /** `default_params`: a 4 by 4 cube. */
    override fun defaultParams(): Game.Params = Params(CUBE, 4, 4)

    /** `game_fetch_preset`; `cube.c` has no `SMALL_SCREEN` branch. */
    private val presetValues = listOf(
        "Cube" to Params(CUBE, 4, 4),
        "Tetrahedron" to Params(TETRAHEDRON, 1, 2),
        "Octahedron" to Params(OCTAHEDRON, 2, 2),
        "Icosahedron" to Params(ICOSAHEDRON, 3, 3),
    )

    override fun presets(): List<Preset> =
        presetValues.map { (title, params) -> Preset(title, params.text) }

    /**
     * `decode_params`, starting from the defaults as the Kotlin seam
     * does. The solid letter is case-sensitive and optional. `d1` and
     * `d2` start as one `atoi` value, and the `x` form replaces `d2`.
     * The rest of the string is ignored.
     */
    override fun decodeParams(text: String): Game.Params = decodeFrom(text, CUBE)

    /**
     * C decodes into the caller's parameters (`cube.c:294-302`), so an
     * absent solid letter keeps the base solid. `validate_desc` reads
     * `solid` (`cube.c:877`), and a descriptive id decodes over the
     * midend's parameters (`midend.c:1890-1897`), so the base decides
     * which description is valid.
     */
    override fun decodeParams(text: String, base: Game.Params): Game.Params =
        decodeFrom(text, (base as Params).solid)

    private fun decodeFrom(text: String, defaultSolid: Int): Game.Params {
        var solid = defaultSolid
        var index = 0
        if (text.isNotEmpty()) {
            when (text[0]) {
                't' -> {
                    solid = TETRAHEDRON
                    index = 1
                }

                'c' -> {
                    solid = CUBE
                    index = 1
                }

                'o' -> {
                    solid = OCTAHEDRON
                    index = 1
                }

                'i' -> {
                    solid = ICOSAHEDRON
                    index = 1
                }

                else -> Unit
            }
        }

        val d1 = atoi(text, index)
        var d2 = d1
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == 'x') {
            index++
            d2 = atoi(text, index)
        }
        return Params(solid, d1, d2)
    }

    /**
     * `encode_params`: the solid letter and both dimensions. The C
     * function ignores `full`, so both forms are the same string.
     */
    override fun encodeParams(params: Game.Params, full: Boolean): String {
        val p = params as Params
        require(p.solid in SOLIDS.indices) { "solid index ${p.solid} is out of range" }
        return "${"tcoi"[p.solid]}${p.d1}x${p.d2}"
    }

    /**
     * `validate_params`, in the C order. `full` is ignored.
     *
     * The class-count check enumerates every grid square. A huge
     * **accepted** area therefore takes a very long time, exactly as C
     * does; only the rejected side of the area check is fast. There is
     * no guard: a new limit would change validation.
     */
    override fun validateParams(params: Game.Params, full: Boolean): String? {
        val p = params as Params
        if (p.solid < 0 || p.solid >= SOLIDS.size) {
            return "Unrecognised solid type"
        }
        if (p.d1 < 0 || p.d2 < 0) {
            return "Grid dimensions may not be negative"
        }

        val solid = SOLIDS[p.solid]
        if (solid.order == 4) {
            if (p.d1 <= 1 || p.d2 <= 1) {
                return "Both grid dimensions must be greater than one"
            }
            if (p.d2 > Int.MAX_VALUE / p.d1) {
                return "Grid area must not be unreasonably large"
            }
        } else {
            if (p.d1 <= 0 && p.d2 <= 0) {
                return "At least one grid dimension must be greater than zero"
            }
            if ((p.d1 > 0 && p.d1 > Int.MAX_VALUE / p.d1) ||
                (p.d2 > 0 &&
                    p.d2 > (Int.MAX_VALUE - p.d1 * p.d1) / p.d2) ||
                (p.d2 > 0 &&
                    p.d1 * p.d2 >
                    (Int.MAX_VALUE - p.d1 * p.d1 - p.d2 * p.d2) / p.d2)
            ) {
                return "Grid area must not be unreasonably large"
            }
        }

        val nclasses = when (p.solid) {
            TETRAHEDRON -> 4
            OCTAHEDRON -> 2
            else -> 1
        }
        val classes = IntArray(4)
        for (square in enumerateGridSquares(p)) {
            val thisClass = when (nclasses) {
                4 -> square.tetraClass
                2 -> if (square.flip) 1 else 0
                else -> 0
            }
            classes[thisClass]++
        }

        val facesPerClass = solid.nfaces / nclasses
        for (i in 0 until nclasses) {
            if (classes[i] < facesPerClass) {
                return "Not enough grid space to place all blue faces"
            }
        }

        if (gridArea(p.d1, p.d2, solid.order) < solid.nfaces + 1) {
            return "Not enough space to place the solid on an empty square"
        }
        return null
    }

    /** `game_configure` (`cube.c:489-513`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as Params
        return listOf(
            ConfigItem.Choices(
                "Type of solid",
                ":Tetrahedron:Cube:Octahedron:Icosahedron",
                null,
                p.solid,
            ),
            ConfigItem.Text("Width / top", p.d1.toString()),
            ConfigItem.Text("Height / bottom", p.d2.toString()),
        )
    }

    /** `custom_params` (`cube.c:515-524`). */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        Params(
            (items[0] as ConfigItem.Choices).selected,
            atoi((items[1] as ConfigItem.Text).value, 0),
            atoi((items[2] as ConfigItem.Text).value, 0),
        )

    /**
     * `new_game_desc`: exactly `nfaces + 1` draws, in class order, from
     * shrinking class lists. There is no retry and no `AUXINFO`.
     *
     * `random.upto` still consumes its bits for a limit of one, so the
     * draw order must not be reduced or reordered.
     */
    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val p = params as Params
        val solid = SOLIDS[p.solid]
        // D-036 runs before the enumeration and before the first draw.
        require(areaOf(p.d1, p.d2, solid.order) <= MAX_AREA) {
            AREA_TOO_LARGE
        }
        val area = gridArea(p.d1, p.d2, solid.order)
        val nclasses = when (p.solid) {
            TETRAHEDRON -> 4
            OCTAHEDRON -> 2
            else -> 1
        }

        val classLists = Array(nclasses) { IntArray(area) }
        val nsquares = IntArray(nclasses)
        for ((index, square) in enumerateGridSquares(p).withIndex()) {
            val thisClass = when (nclasses) {
                4 -> square.tetraClass
                2 -> if (square.flip) 1 else 0
                else -> 0
            }
            classLists[thisClass][nsquares[thisClass]++] = index
        }

        val facesPerClass = solid.nfaces / nclasses
        for (i in 0 until nclasses) {
            check(nsquares[i] >= facesPerClass) {
                "class $i has ${nsquares[i]} squares for $facesPerClass faces"
            }
        }

        val flags = BooleanArray(area)
        for (i in 0 until nclasses) {
            val list = classLists[i]
            for (j in 0 until facesPerClass) {
                var n = random.upto(nsquares[i].toLong()).toInt()
                check(!flags[list[n]]) { "square ${list[n]} was chosen twice" }
                flags[list[n]] = true

                // Shift the tail left; only the chosen entry disappears.
                while (n < nsquares[i] - 1) {
                    list[n] = list[n + 1]
                    n++
                }
                nsquares[i]--
            }
        }

        val desc = StringBuilder(area / 4 + 8)
        val nonBlue = IntArray(area - solid.nfaces)
        var m = 0
        var j = 0
        var k = 8
        for (i in 0 until area) {
            if (flags[i]) {
                j = j or k
            } else {
                nonBlue[m++] = i
            }
            k = k shr 1
            if (k == 0) {
                desc.append("0123456789ABCDEF"[j])
                k = 8
                j = 0
            }
        }
        if (k != 8) {
            desc.append("0123456789ABCDEF"[j])
        }

        desc.append(',').append(nonBlue[random.upto(m.toLong()).toInt()])
        return Description(desc.toString(), null)
    }

    /**
     * `validate_desc`. The exact C strings are kept: the midend wraps
     * them for a save file, so only an engine test can see them. There
     * is no check of the blue count, the classes, or the index range.
     */
    override fun validateDesc(params: Game.Params, desc: String): String? {
        val p = params as Params
        val area = gridArea(p.d1, p.d2, SOLIDS[p.solid].order)
        val hexDigits = (area + 3) / 4
        for (j in 0 until hexDigits) {
            val c = charAtOrNul(desc, j)
            if (c in '0'..'9') continue
            if (c in 'A'..'F') continue
            if (c in 'a'..'f') continue
            return "Not enough hex digits at start of string"
        }

        if (charAtOrNul(desc, hexDigits) != ',') {
            return "Expected ',' after hex digits"
        }

        var i = hexDigits + 1
        do {
            if (charAtOrNul(desc, i) !in '0'..'9') {
                return "Expected decimal integer after ','"
            }
            i++
        } while (charAtOrNul(desc, i) != '\u0000')
        return null
    }

    /**
     * `new_game`: the description sets the blue squares and the starting
     * square. The initial solid is aligned to the current square, but
     * the key indices are recomputed where they are needed, so they are
     * not stored.
     */
    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as Params
        val solid = SOLIDS[p.solid]
        val squares = enumerateGridSquares(p)
        val area = gridArea(p.d1, p.d2, solid.order)
        check(squares.size == area) { "enumeration produced ${squares.size} squares for $area" }
        val grid = Grid(squares)

        val facecolours = IntArray(solid.nfaces)
        val bluemask = BooleanArray(area)

        var pIndex = 0
        var j = 8
        var v = 0
        var i = 0
        while (i < area) {
            if (j == 8) {
                if (pIndex >= desc.length) break
                val c = desc[pIndex++]
                v = when {
                    c in '0'..'9' -> c - '0'
                    c in 'A'..'F' -> c - 'A' + 10
                    c in 'a'..'f' -> c - 'a' + 10
                    else -> break
                }
            }
            if (v and j != 0) bluemask[i] = true
            j = j shr 1
            if (j == 0) j = 8
            i++
        }

        if (pIndex < desc.length && desc[pIndex] == ',') pIndex++
        var current = atoi(desc, pIndex)
        if (current < 0 || current >= area) current = 0

        val pkey = IntArray(4)
        check(alignPoly(solid, grid.squares[current], pkey)) {
            "the solid does not align with square $current"
        }

        return State(
            params = p,
            solid = solid,
            facecolours = facecolours,
            grid = grid,
            bluemask = bluemask,
            current = current,
            completed = 0,
            movecount = 0,
        )
    }

    /** `cube.c` has no solver. [Midend] checks [canSolve] first. */
    override fun solve(state: Game.State, auxInfo: String?): String? = null

    /** `status`: +1 after completion, otherwise 0. */
    override fun status(state: Game.State): Int =
        if ((state as State).completed != 0) 1 else 0

    /** `status_bar` text. The completion count is frozen. */
    override fun statusText(state: Game.State, ui: Game.Ui): String {
        val s = state as State
        return if (s.completed != 0) {
            "COMPLETED! Moves: ${s.completed}"
        } else {
            "Moves: ${s.movecount}"
        }
    }

    /** `cube.c` has no text format. */
    override fun textFormat(state: Game.State): String = ""

    /** `game_changed_state`: only the transition into completion reports. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val new = newState as State
        val old = oldState as State?
        return new.completed != 0 && old != null && old.completed == 0
    }

    /** `game_request_keys`: no extra keys, arrows only. */
    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_ONLY)

    /** `current_key_label` is NULL in C, so no key has a label. */
    fun currentKeyLabel(ui: Game.Ui, state: Game.State, button: Int): String = ""

    /** `game_compute_size`: the bounding box plus the solid border. */
    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as Params
        val solid = SOLIDS[p.solid]
        val bb = findBbox(p)
        return Size(
            ((bb.r - bb.l + 2 * solid.border) * tileSize).toInt(),
            ((bb.d - bb.u + 2 * solid.border) * tileSize).toInt(),
        )
    }

    /** `game_colours` with the oracle's default background. */
    override fun colours(): FloatArray = colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    /** `game_colours`: the background, black, and blue. No highlight. */
    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0..2) ret[COL_BACKGROUND * 3 + i] = background[i]
        ret[COL_BORDER * 3 + 0] = 0.0f
        ret[COL_BORDER * 3 + 1] = 0.0f
        ret[COL_BORDER * 3 + 2] = 0.0f
        ret[COL_BLUE * 3 + 0] = 0.0f
        ret[COL_BLUE * 3 + 1] = 0.0f
        ret[COL_BLUE * 3 + 2] = 1.0f
        return ret
    }

    /**
     * `game_get_cursor_location`: the bounding box of the current square
     * only. It always returns a rectangle; C never returns -1 here.
     */
    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect {
        val s = state as State
        val geometry = Geometry(s.params, tileSize)
        val bb = Bbox(s.params)
        findBboxCallback(bb, s.grid.squares[s.current])
        return Game.CursorRect(
            left = (bb.l * geometry.gridScale).toInt() + geometry.ox,
            top = (bb.u * geometry.gridScale).toInt() + geometry.oy,
            width = ((bb.r - bb.l) * geometry.gridScale).toInt(),
            height = ((bb.d - bb.u) * geometry.gridScale).toInt(),
        )
    }

    /**
     * The board as a screen reader reads it (D-01).
     *
     * One unit per grid square, in reading order. A square is `blue` or
     * `empty`, and the square the die stands on adds `cursor`.
     *
     * The pointer rolls the die in the bearing of the click, so a square
     * carries the roll its own centre produces when that roll is legal.
     * The shell clicks the unit centre, and the label comes from the
     * same direction check, so the action and the click cannot name
     * different rolls. The current square is a no-op and carries no
     * action. The four arrow keys and the eight keypad directions make
     * the same rolls on the key path; they are not pointer targets, so
     * they are not units.
     *
     * The cube solid is a rectangle, so its units carry grid positions.
     * The other three solids are rows of triangles, which have no
     * rectangular position: their text names the row and the triangle in
     * it, and their rectangles are bounding boxes, which overlap by
     * construction.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val t = tileSize
        val geometry = Geometry(s.params, t)
        val squares = s.grid.squares
        val summary = statusText(state, ui)
        val die = squares[s.current]
        val cx = (die.x * geometry.gridScale).toInt() + geometry.ox
        val cy = (die.y * geometry.gridScale).toInt() + geometry.oy

        // The eight bearings resolved once, so one unit costs one
        // comparison instead of a scan of every square.
        val rolls = IntArray(DIRECTION_NAMES.size) { direction ->
            (resolveDirection(s, direction) ?: -1) + 1
        }

        fun rollActions(x: Int, y: Int): List<SpokenAction> {
            val bearing = bearingOf(s, cx, cy, x, y) ?: return emptyList()
            val roll = rolls[bearing]
            return if (roll == 0) {
                emptyList()
            } else {
                listOf(SpokenAction(DIRECTION_NAMES[roll - 1], Buttons.LEFT_BUTTON))
            }
        }

        fun squareUnit(
            index: Int,
            row: Int,
            column: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ) = SpokenUnit(
            row = row,
            column = column,
            left = left,
            top = top,
            width = width,
            height = height,
            text = squareText(s, index),
            actions = rollActions(left + width / 2, top + height / 2),
        )

        if (s.solid.order == 4) {
            val p = s.params
            // Cell (0,0) starts half a square left of and above the grid
            // origin, exactly as `redraw` draws it.
            val left = geometry.ox - t / 2
            val top = geometry.oy - t / 2
            // D-01 S2: above the cap the shell speaks the cursor alone,
            // and building every unit of a huge saved board would spend
            // the memory the cap protects.
            if (p.d1.toLong() * p.d2 > BOARD_UNIT_CAP) {
                val x = s.current % p.d1
                val y = s.current / p.d1
                return SpokenBoard(
                    p.d2,
                    p.d1,
                    summary,
                    listOf(
                        squareUnit(
                            s.current, y + 1, x + 1,
                            left + x * t, top + y * t, t, t,
                        ),
                    ),
                )
            }
            val cells = gridSpeech(
                width = p.d1,
                height = p.d2,
                tileSize = t,
                left = left,
                top = top,
                summary = summary,
                text = { x, y -> squareText(s, y * p.d1 + x) },
                actions = { x, y -> rollActions(left + x * t + t / 2, top + y * t + t / 2) },
            )
            return cells.capped(cells.units.getOrNull(s.current))
        }

        // The triangle solids: rows of cells that are not a rectangle.
        val d1 = s.params.d1
        val d2 = s.params.d2
        val cellRows = IntArray(squares.size)
        val cellPositions = IntArray(squares.size)
        var first = 0
        for (row in 0 until d1 + d2) {
            val rowLength = if (row < d2) row + d1 else 2 * d2 + d1 - row
            val upper = if (row < d2) 1 else -1
            val rowIndexes = (first until first + 2 * rowLength + upper)
                .sortedBy { squares[it].x }
            for ((position, index) in rowIndexes.withIndex()) {
                cellRows[index] = row
                cellPositions[index] = position
            }
            first += 2 * rowLength + upper
        }

        fun triangleUnit(index: Int): SpokenUnit {
            val square = squares[index]
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until square.npoints) {
                minX = minOf(minX, square.points[i * 2])
                maxX = maxOf(maxX, square.points[i * 2])
                minY = minOf(minY, square.points[i * 2 + 1])
                maxY = maxOf(maxY, square.points[i * 2 + 1])
            }
            val left = (minX * geometry.gridScale).toInt() + geometry.ox
            val top = (minY * geometry.gridScale).toInt() + geometry.oy
            val width = ((maxX - minX) * geometry.gridScale).toInt()
            val height = ((maxY - minY) * geometry.gridScale).toInt()
            val unit = squareUnit(index, 0, 0, left, top, width, height)
            return unit.copy(
                text = "row ${cellRows[index] + 1}, triangle ${cellPositions[index] + 1}, " +
                    unit.text,
            )
        }

        val units = if (squares.size > BOARD_UNIT_CAP) {
            listOf(triangleUnit(s.current))
        } else {
            // Reading order: row by row, left to right.
            val order = squares.indices.sortedWith(
                compareBy({ cellRows[it] }, { cellPositions[it] }),
            )
            order.map { triangleUnit(it) }
        }
        return SpokenBoard(0, 0, summary, units)
    }

    /** The content of one square: `blue` or `empty`, and the die's square. */
    private fun squareText(s: State, index: Int): String {
        val colour = if (s.bluemask[index]) "blue" else "empty"
        return if (index == s.current) "$colour, cursor" else colour
    }

    /** `game_redraw`: the whole board, then the settled polyhedron. */
    override fun redraw(
        state: Game.State,
        ui: Game.Ui,
        drawState: Game.DrawState,
        sink: DrawSink,
        tileSize: Int,
    ) {
        val s = state as State
        val solid = s.solid
        val geometry = Geometry(s.params, tileSize)
        val width = geometry.width
        val height = geometry.height

        sink.rect(0, 0, width, height, COL_BACKGROUND)

        for (i in s.grid.squares.indices) {
            val square = s.grid.squares[i]
            val coords = IntArray(square.npoints * 2)
            for (j in 0 until square.npoints) {
                coords[j * 2] =
                    (square.points[j * 2] * geometry.gridScale).toInt() + geometry.ox
                coords[j * 2 + 1] =
                    (square.points[j * 2 + 1] * geometry.gridScale).toInt() + geometry.oy
            }
            sink.poly(
                coords,
                if (s.bluemask[i]) COL_BLUE else COL_BACKGROUND,
                COL_BORDER,
            )
        }

        val square = s.grid.squares[s.current]
        val pkey = IntArray(4)
        check(alignPoly(solid, square, pkey)) {
            "the solid does not align with square ${s.current}"
        }
        val gkey = intArrayOf(0, 1)

        val poly = transformPoly(solid, square.flip, pkey[0], pkey[1], 0.0f)

        // Put the two key vertices where the grid edge is, with z = 0.
        val translation = FloatArray(3)
        for (i in 0..2) {
            var tc = 0.0f
            for (j in 0..1) {
                val gridCoord = if (i < 2) square.points[gkey[j] * 2 + i] else 0.0f
                tc += gridCoord - poly.vertices[pkey[j] * 3 + i]
            }
            translation[i] = tc / 2
        }
        for (i in 0 until poly.nvertices) {
            for (j in 0..2) {
                poly.vertices[i * 3 + j] += translation[j]
            }
        }

        for (i in 0 until poly.nfaces) {
            val points = FloatArray(poly.order * 2)
            for (j in 0 until poly.order) {
                val f = poly.faces[i * poly.order + j]
                points[j * 2] =
                    poly.vertices[f * 3 + 0] - poly.vertices[f * 3 + 2] * poly.shear
                points[j * 2 + 1] =
                    poly.vertices[f * 3 + 1] - poly.vertices[f * 3 + 2] * poly.shear
            }

            val coords = IntArray(poly.order * 2)
            for (j in 0 until poly.order) {
                coords[j * 2] =
                    floor((points[j * 2] * geometry.gridScale).toDouble()).toInt() +
                        geometry.ox
                coords[j * 2 + 1] =
                    floor((points[j * 2 + 1] * geometry.gridScale).toDouble()).toInt() +
                        geometry.oy
            }

            // Back-face culling uses the winding of the sheared 2-D
            // points, not the 3-D normals.
            val v1x = points[2] - points[0]
            val v1y = points[3] - points[1]
            val v2x = points[4] - points[2]
            val v2y = points[5] - points[3]
            val dp = v1x * v2y - v1y * v2x
            if (dp <= 0) continue

            sink.poly(
                coords,
                if (s.facecolours[i] != 0) COL_BLUE else COL_BACKGROUND,
                COL_BORDER,
            )
        }

        sink.update(0, 0, width, height)
    }

    /**
     * `interpret_move`. Modifiers other than the keypad bit are dropped
     * at the entry. A press chooses a bearing from the current square's
     * centre; a cursor key or a keypad digit is a direction. Every other
     * button is unused.
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
        val masked = button and (Buttons.MOD_MASK.inv() or Buttons.MOD_NUM_KEYPAD)
        val direction: Int = when (masked) {
            Buttons.CURSOR_UP, Buttons.MOD_NUM_KEYPAD or '8'.code -> UP
            Buttons.CURSOR_DOWN, Buttons.MOD_NUM_KEYPAD or '2'.code -> DOWN
            Buttons.CURSOR_LEFT, Buttons.MOD_NUM_KEYPAD or '4'.code -> LEFT
            Buttons.CURSOR_RIGHT, Buttons.MOD_NUM_KEYPAD or '6'.code -> RIGHT
            Buttons.MOD_NUM_KEYPAD or '7'.code -> UP_LEFT
            Buttons.MOD_NUM_KEYPAD or '1'.code -> DOWN_LEFT
            Buttons.MOD_NUM_KEYPAD or '9'.code -> UP_RIGHT
            Buttons.MOD_NUM_KEYPAD or '3'.code -> DOWN_RIGHT
            Buttons.LEFT_BUTTON -> return clickDirection(s, x, y, tileSize)
            else -> return MoveResult.Unused
        }

        return directionMove(s, direction)
    }

    /** The `LEFT_BUTTON` branch: a bearing, then the same direction check. */
    private fun clickDirection(
        s: State,
        x: Int,
        y: Int,
        tileSize: Int,
    ): MoveResult {
        val geometry = Geometry(s.params, tileSize)
        val square = s.grid.squares[s.current]
        val cx = (square.x * geometry.gridScale).toInt() + geometry.ox
        val cy = (square.y * geometry.gridScale).toInt() + geometry.oy
        val bearing = bearingOf(s, cx, cy, x, y) ?: return MoveResult.NoEffect
        return directionMove(s, bearing)
    }

    /**
     * The direction a click at (`x`, `y`) bears from the current
     * square's centre (`cx`, `cy`), or null on that centre.
     *
     * `interpret_move` and [spokenBoard] share this, so a spoken action
     * and a finger click cannot name different directions. The caller
     * passes the centre because [spokenBoard] computes it once for the
     * whole board.
     */
    private fun bearingOf(
        s: State,
        cx: Int,
        cy: Int,
        x: Int,
        y: Int,
    ): Int? {
        if (x == cx && y == cy) return null
        val square = s.grid.squares[s.current]
        val angle = atan2((y - cy).toDouble(), (x - cx).toDouble())
        return if (square.npoints == 4) {
            when {
                abs(angle) > 3 * PI / 4 -> LEFT
                abs(angle) < PI / 4 -> RIGHT
                angle > 0 -> DOWN
                else -> UP
            }
        } else if (square.directions[UP] == 0) {
            // Up-pointing triangle.
            when {
                angle < -PI / 2 || angle > 5 * PI / 6 -> LEFT
                angle > PI / 6 -> DOWN
                else -> RIGHT
            }
        } else {
            // Down-pointing triangle.
            check(square.directions[DOWN] == 0) { "triangle has both up and down" }
            when {
                angle > PI / 2 || angle < -5 * PI / 6 -> LEFT
                angle < -PI / 6 -> UP
                else -> RIGHT
            }
        }
    }

    /**
     * The orthogonal direction a bearing resolves to, or null when the
     * roll is inert: no edge that way, or no square beyond it.
     *
     * The mask check, the diagonal alias, and the destination check of
     * C `interpret_move`.
     */
    private fun resolveDirection(s: State, initialDirection: Int): Int? {
        val directions = s.grid.squares[s.current].directions
        var direction = initialDirection
        val mask = directions[direction]
        if (mask == 0) return null

        // A diagonal is an alias of an orthogonal direction.
        if (direction > DOWN) {
            for (i in LEFT..DOWN) {
                if (directions[i] == mask) {
                    direction = i
                    break
                }
            }
            check(direction <= DOWN) { "diagonal $initialDirection has no alias" }
        }

        if (findMoveDest(s, direction) == null) return null
        return direction
    }

    /** The shared direction mask, diagonal alias, and move string. */
    private fun directionMove(s: State, initialDirection: Int): MoveResult {
        val direction = resolveDirection(s, initialDirection) ?: return MoveResult.NoEffect
        return MoveResult.Move(
            when (direction) {
                LEFT -> "L"
                RIGHT -> "R"
                UP -> "U"
                DOWN -> "D"
                else -> error("direction $direction is not orthogonal")
            },
        )
    }

    /** The result of `find_move_dest`: the destination and both key arrays. */
    private class MoveDest(val dest: Int, val skey: IntArray, val dkey: IntArray)

    /**
     * `find_move_dest`: the destination square that shares the two edge
     * points of this direction, and the point indices on both squares.
     */
    private fun findMoveDest(from: State, direction: Int): MoveDest? {
        val source = from.grid.squares[from.current]
        val mask = source.directions[direction]
        if (mask == 0) return null

        val edge = FloatArray(4)
        val skey = IntArray(2)
        var j = 0
        for (i in 0 until source.npoints) {
            if (mask and (1 shl i) != 0) {
                edge[j * 2] = source.points[i * 2]
                edge[j * 2 + 1] = source.points[i * 2 + 1]
                skey[j] = i
                j++
            }
        }
        check(j == 2) { "direction $direction has $j edge points" }

        for (i in from.grid.squares.indices) {
            if (i == from.current) continue
            val square = from.grid.squares[i]
            val dkey = IntArray(2)
            var match = 0
            for (p in 0 until square.npoints) {
                var dist = sq(square.points[p * 2] - edge[0]) +
                    sq(square.points[p * 2 + 1] - edge[1])
                if (dist < 0.1f) dkey[match++] = p
                dist = sq(square.points[p * 2] - edge[2]) +
                    sq(square.points[p * 2 + 1] - edge[3])
                if (dist < 0.1f) dkey[match++] = p
            }
            if (match == 2) return MoveDest(i, skey, dkey)
        }
        return null
    }

    /**
     * `execute_move`: the first character is the whole grammar. The solid
     * is rolled to the destination, the faces are permuted, the bottom
     * face swaps with the landing square, and the move count advances.
     */
    override fun executeMove(state: Game.State, move: String): Game.State? {
        val from = state as State
        val direction = when (move.firstOrNull()) {
            'L' -> LEFT
            'R' -> RIGHT
            'U' -> UP
            'D' -> DOWN
            else -> return null
        }

        val found = findMoveDest(from, direction) ?: return null
        val ret = from.copy() as State
        ret.current = found.dest

        val allPkey = IntArray(4)
        alignPoly(from.solid, from.grid.squares[from.current], allPkey)
        val pkey0 = allPkey[found.skey[0]]
        val pkey1 = allPkey[found.skey[1]]

        // The two faces that contain both key vertices set the angle.
        val faces = IntArray(2)
        var nf = 0
        for (i in 0 until from.solid.nfaces) {
            var match = 0
            for (j in 0 until from.solid.order) {
                if (from.solid.faces[i * from.solid.order + j] == pkey0 ||
                    from.solid.faces[i * from.solid.order + j] == pkey1
                ) {
                    match++
                }
            }
            if (match == 2) {
                check(nf < 2) { "more than two faces share vertices $pkey0 and $pkey1" }
                faces[nf++] = i
            }
        }
        check(nf == 2) { "$nf faces share vertices $pkey0 and $pkey1" }

        var dp = 0.0f
        for (i in 0..2) {
            dp += from.solid.normals[faces[0] * 3 + i] *
                from.solid.normals[faces[1] * 3 + i]
        }
        var angle = acos(dp.toDouble()).toFloat()

        if (from.solid.order == 4 && direction == UP) {
            // The cube-only "gross hack" from C.
            angle = -angle
        }

        var poly = transformPoly(
            from.solid,
            from.grid.squares[from.current].flip,
            pkey0,
            pkey1,
            angle,
        )
        flipPoly(poly, from.grid.squares[ret.current].flip)
        var success = alignPoly(poly, from.grid.squares[ret.current], allPkey)
        if (!success) {
            angle = -angle
            poly = transformPoly(
                from.solid,
                from.grid.squares[from.current].flip,
                pkey0,
                pkey1,
                angle,
            )
            flipPoly(poly, from.grid.squares[ret.current].flip)
            success = alignPoly(poly, from.grid.squares[ret.current], allPkey)
        }
        check(success) { "the rolled solid does not align with square ${ret.current}" }

        val newcolours = IntArray(from.solid.nfaces) { -1 }
        for (i in 0 until from.solid.nfaces) {
            var nmatch = 0
            for (j in 0 until poly.nfaces) {
                var dist = 0.0f
                for (k in 0..2) {
                    dist += sq(
                        poly.normals[j * 3 + k] - from.solid.normals[i * 3 + k],
                    )
                }
                if (approxEq(dist, 0.0f)) {
                    nmatch++
                    newcolours[i] = ret.facecolours[j]
                }
            }
            check(nmatch == 1) { "face $i has $nmatch matching normals" }
        }
        for (i in 0 until from.solid.nfaces) {
            check(newcolours[i] != -1) { "face $i has no colour" }
        }
        ret.facecolours = newcolours
        ret.movecount++

        if (ret.completed == 0) {
            val i = lowestFace(from.solid)
            val j = ret.facecolours[i]
            ret.facecolours[i] = if (ret.bluemask[ret.current]) 1 else 0
            ret.bluemask[ret.current] = j != 0

            var count = 0
            for (face in 0 until ret.solid.nfaces) {
                if (ret.facecolours[face] != 0) count++
            }
            if (count == ret.solid.nfaces) ret.completed = ret.movecount
        }

        return ret
    }

    /** `lowest_face` for the solid of [params]. The cube's value is 3. */
    internal fun lowestFace(params: Params): Int = lowestFace(SOLIDS[params.solid])

    /** `lowest_face`: the first face with the strictly smallest z sum. */
    internal fun lowestFace(solid: Solid): Int {
        var best = 0
        var zmin = 0.0f
        for (i in 0 until solid.nfaces) {
            var z = 0.0f
            for (j in 0 until solid.order) {
                val f = solid.faces[i * solid.order + j]
                z += solid.vertices[f * 3 + 2]
            }
            if (i == 0 || zmin > z) {
                zmin = z
                best = i
            }
        }
        return best
    }

    /**
     * `align_poly`: find the vertex of the solid at every grid point of
     * a square. `pkey[j]` is the vertex index at point `j`.
     */
    private fun alignPoly(
        solid: Solid,
        square: GridSquare,
        pkey: IntArray,
    ): Boolean {
        var zmin = 0.0f
        for (i in 0 until solid.nvertices) {
            if (zmin > solid.vertices[i * 3 + 2]) zmin = solid.vertices[i * 3 + 2]
        }

        val flip = if (square.flip) -1 else 1
        for (j in 0 until square.npoints) {
            var matches = 0
            var index = -1
            for (i in 0 until solid.nvertices) {
                var dist = 0.0f
                dist += sq(
                    solid.vertices[i * 3 + 0] * flip -
                        square.points[j * 2 + 0] + square.x,
                )
                dist += sq(
                    solid.vertices[i * 3 + 1] * flip -
                        square.points[j * 2 + 1] + square.y,
                )
                dist += sq(solid.vertices[i * 3 + 2] - zmin)
                if (dist < 0.1f) {
                    matches++
                    index = i
                }
            }
            if (matches != 1 || index < 0) return false
            pkey[j] = index
        }
        return true
    }

    /** `flip_poly`: negate x and y, leaving z alone. */
    private fun flipPoly(solid: Solid, flip: Boolean) {
        if (!flip) return
        for (i in 0 until solid.nvertices) {
            solid.vertices[i * 3 + 0] *= -1
            solid.vertices[i * 3 + 1] *= -1
        }
        for (i in 0 until solid.nfaces) {
            solid.normals[i * 3 + 0] *= -1
            solid.normals[i * 3 + 1] *= -1
        }
    }

    /**
     * `transform_poly`: flip, rotate about z to put the key edge on the
     * x-axis, rotate about x by `angle`, then rotate back.
     */
    private fun transformPoly(
        solid: Solid,
        flip: Boolean,
        key0: Int,
        key1: Int,
        angle: Float,
    ): Solid {
        val ret = solid.copy()
        flipPoly(ret, flip)

        val vx = ret.vertices[key1 * 3 + 0] - ret.vertices[key0 * 3 + 0]
        val vy = ret.vertices[key1 * 3 + 1] - ret.vertices[key0 * 3 + 1]
        check(approxEq(vx * vx + vy * vy, 1.0f)) {
            "the key edge has length squared ${vx * vx + vy * vy}"
        }

        val vmatrix = FloatArray(9)
        vmatrix[0] = vx
        vmatrix[3] = vy
        vmatrix[6] = 0.0f
        vmatrix[1] = -vy
        vmatrix[4] = vx
        vmatrix[7] = 0.0f
        vmatrix[2] = 0.0f
        vmatrix[5] = 0.0f
        vmatrix[8] = 1.0f

        val ax = cos(angle.toDouble()).toFloat()
        val ay = sin(angle.toDouble()).toFloat()

        val amatrix = FloatArray(9)
        amatrix[0] = 1.0f
        amatrix[3] = 0.0f
        amatrix[6] = 0.0f
        amatrix[1] = 0.0f
        amatrix[4] = ax
        amatrix[7] = ay
        amatrix[2] = 0.0f
        amatrix[5] = -ay
        amatrix[8] = ax

        val vmatrix2 = vmatrix.copyOf()
        vmatrix2[1] = vy
        vmatrix2[3] = -vy

        for (i in 0 until ret.nvertices) {
            matmul(ret.vertices, i * 3, vmatrix)
            matmul(ret.vertices, i * 3, amatrix)
            matmul(ret.vertices, i * 3, vmatrix2)
        }
        for (i in 0 until ret.nfaces) {
            matmul(ret.normals, i * 3, vmatrix)
            matmul(ret.normals, i * 3, amatrix)
            matmul(ret.normals, i * 3, vmatrix2)
        }
        return ret
    }

    /** `MATMUL` with the C matrix indexing, applied in place. */
    private fun matmul(values: FloatArray, offset: Int, matrix: FloatArray) {
        val x = values[offset]
        val y = values[offset + 1]
        val z = values[offset + 2]
        values[offset] = matrix[0] * x + matrix[3] * y + matrix[6] * z
        values[offset + 1] = matrix[1] * x + matrix[4] * y + matrix[7] * z
        values[offset + 2] = matrix[2] * x + matrix[5] * y + matrix[8] * z
    }

    /** `grid_area`: `d1*d2`, or `d1^2 + d2^2 + 4*d1*d2`. */
    internal fun gridArea(d1: Int, d2: Int, order: Int): Int =
        if (order == 4) d1 * d2 else d1 * d1 + d2 * d2 + 4 * d1 * d2

    /**
     * [gridArea] in [Long], for the D-036 guard.
     *
     * A guard must not overflow: the parameters reach `newDesc` without
     * `validateParams` on a direct call, and an accepted area can be as
     * large as `Int.MAX_VALUE`.
     */
    internal fun areaOf(d1: Int, d2: Int, order: Int): Long =
        if (order == 4) {
            d1.toLong() * d2
        } else {
            d1.toLong() * d1 + d2.toLong() * d2 + 4L * d1 * d2
        }

    /** `struct bbox`, in grid units. */
    private class Bbox(params: Params) {
        var l: Float = 2.0f * (params.d1 + params.d2)
        var r: Float = -2.0f * (params.d1 + params.d2)
        var u: Float = 2.0f * (params.d1 + params.d2)
        var d: Float = -2.0f * (params.d1 + params.d2)
    }

    private fun findBbox(params: Params): Bbox {
        val bb = Bbox(params)
        for (square in enumerateGridSquares(params)) {
            findBboxCallback(bb, square)
        }
        return bb
    }

    private fun findBboxCallback(bb: Bbox, square: GridSquare) {
        for (i in 0 until square.npoints) {
            if (bb.l > square.points[i * 2]) bb.l = square.points[i * 2]
            if (bb.r < square.points[i * 2]) bb.r = square.points[i * 2]
            if (bb.u > square.points[i * 2 + 1]) bb.u = square.points[i * 2 + 1]
            if (bb.d < square.points[i * 2 + 1]) bb.d = square.points[i * 2 + 1]
        }
    }

    /**
     * `game_set_size` and `XSIZE`/`YSIZE`, recomputed from the same
     * params because the seam has no set-size call.
     */
    private class Geometry(params: Params, tileSize: Int) {
        val gridScale: Float = tileSize.toFloat()
        private val solid = SOLIDS[params.solid]
        private val bb: Bbox = findBbox(params)
        val ox: Int = (-(bb.l - solid.border) * gridScale).toInt()
        val oy: Int = (-(bb.u - solid.border) * gridScale).toInt()
        val width: Int = ((bb.r - bb.l + 2 * solid.border) * gridScale).toInt()
        val height: Int = ((bb.d - bb.u + 2 * solid.border) * gridScale).toInt()
    }

    /**
     * `enum_grid_squares`: squares in row-major order, or triangles in
     * the row order of `cube.c`, down triangles before up triangles.
     *
     * The `firstix` value is set once for the whole enumeration. It is
     * subtracted from every triangle index before `tetra_class` is
     * computed, so it changes the class of every triangle and therefore
     * the description.
     */
    private fun enumerateGridSquares(params: Params): List<GridSquare> {
        val solid = SOLIDS[params.solid]
        val squares = ArrayList<GridSquare>(
            if (solid.order == 4) params.d1 * params.d2 else
                params.d1 * params.d1 + params.d2 * params.d2 + 4 * params.d1 * params.d2,
        )

        if (solid.order == 4) {
            for (y in 0 until params.d2) {
                for (x in 0 until params.d1) {
                    squares += GridSquare(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        npoints = 4,
                        points = floatArrayOf(
                            x - 0.5f, y - 0.5f,
                            x - 0.5f, y + 0.5f,
                            x + 0.5f, y + 0.5f,
                            x + 0.5f, y - 0.5f,
                        ),
                        directions = intArrayOf(
                            0x03, 0x0C, 0x09, 0x06, 0, 0, 0, 0,
                        ),
                        flip = false,
                        tetraClass = 0,
                    )
                }
            }
            return squares
        }

        val height = (sqrt(3.0) / 2.0).toFloat()
        var firstix = -1
        for (row in 0 until params.d1 + params.d2) {
            val other: Int
            val rowlen: Int
            if (row < params.d2) {
                other = +1
                rowlen = row + params.d1
            } else {
                other = -1
                rowlen = 2 * params.d2 + params.d1 - row
            }

            // The down-pointing triangles of this row.
            for (i in 0 until rowlen) {
                var ix = 2 * i - (rowlen - 1)
                val x = ix * 0.5f
                val y = height * row
                if (firstix < 0) firstix = ix and 3
                ix -= firstix
                squares += GridSquare(
                    x = x,
                    y = y + height / 3,
                    npoints = 3,
                    points = floatArrayOf(
                        x - 0.5f, y,
                        x, y + height,
                        x + 0.5f, y,
                    ),
                    directions = intArrayOf(
                        0x03,
                        0x06,
                        0x05,
                        0,
                        0x05,
                        0x05,
                        0x03,
                        0x06,
                    ),
                    flip = true,
                    tetraClass = ((row + (ix and 1)) and 2) xor (ix and 3),
                )
            }

            // The up-pointing triangles of this row.
            for (i in 0 until rowlen + other) {
                var ix = 2 * i - (rowlen + other - 1)
                val x = ix * 0.5f
                val y = height * row
                if (firstix < 0) firstix = (ix - 1) and 3
                ix -= firstix
                squares += GridSquare(
                    x = x,
                    y = y + 2 * height / 3,
                    npoints = 3,
                    points = floatArrayOf(
                        x + 0.5f, y + height,
                        x, y,
                        x - 0.5f, y + height,
                    ),
                    directions = intArrayOf(
                        0x06,
                        0x03,
                        0,
                        0x05,
                        0x06,
                        0x03,
                        0x05,
                        0x05,
                    ),
                    flip = false,
                    tetraClass = ((row + (ix and 1)) and 2) xor (ix and 3),
                )
            }
        }
        return squares
    }

    /**
     * C `atoi`: white space, an optional sign, digits; 0 without a digit.
     *
     * The C library parses with `strtol` and casts the `long` to `int`.
     * The digits therefore accumulate as a `long` that saturates at
     * `LONG_MIN`/`LONG_MAX`, and the final cast keeps the low 32 bits.
     * A value such as `4294967300` wraps to 4 and is accepted, and a
     * value such as `2147483648` wraps to `Int.MIN_VALUE` and fails as
     * negative. Do not clamp to `Int.MAX_VALUE`: that changes which
     * validation message the oracle returns.
     */
    private fun atoi(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }
        var value = 0L
        var overflow = false
        while (i < text.length && text[i] in '0'..'9') {
            val digit = text[i] - '0'
            if (value > (Long.MAX_VALUE - digit) / 10) {
                overflow = true
            } else if (!overflow) {
                value = value * 10 + digit
            }
            i++
        }
        if (overflow) {
            // `strtol` saturates; the `int` cast keeps the low 32 bits.
            return (if (negative) Long.MIN_VALUE else Long.MAX_VALUE).toInt()
        }
        return (if (negative) -value else value).toInt()
    }

    /** The C string byte at [index], or NUL past the end. */
    private fun charAtOrNul(text: String, index: Int): Char =
        if (index < text.length) text[index] else '\u0000'
}
