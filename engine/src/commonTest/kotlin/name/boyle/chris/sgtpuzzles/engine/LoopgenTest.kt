package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Level: semantic.
 *
 * Fixed vectors recorded from upstream `loopgen.c` through the oracle,
 * plus the loop invariants every generated board must satisfy. The
 * exhaustive differential comparison lives in `:parity`; this file
 * keeps the port honest without the oracle.
 *
 * A vector pins the face colours, the encoded generator state, and the
 * number of bias calls, so a missing or extra RNG draw fails here too.
 */
class LoopgenTest {

    private data class Vector(
        val type: GridType,
        val w: Int,
        val h: Int,
        val seed: String,
        val bias: Boolean,
        val faces: List<Int>,
        val state: String,
        val calls: Int,
    )

    private val vectors = listOf(
        Vector(
            type = GridType.SQUARE,
            w = 3,
            h = 3,
            seed = "12345",
            bias = false,
            faces = listOf(2, 2, 0, 0, 0, 0, 0, 2, 0),
            state = "8eb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e89089" +
                "80d443c29c69b60c9427352c0c6cbd03b883a8b90d90bbb5e8155e1e414",
            calls = 0,
        ),
        Vector(
            type = GridType.SQUARE,
            w = 3,
            h = 3,
            seed = "12345",
            bias = true,
            faces = listOf(2, 2, 2, 0, 0, 0, 0, 2, 0),
            state = "8eb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e89089" +
                "80d443c29c69b60c9427352c0c6cbd03b883a8b90d90bbb5e8155e1e413",
            calls = 62,
        ),
        Vector(
            type = GridType.HONEYCOMB,
            w = 3,
            h = 3,
            seed = "7",
            bias = false,
            faces = listOf(0, 2, 0, 0, 2, 0, 0, 0, 0),
            state = "922ba3cda1883801594b6e1b452790cc53948fda23e7a7428138939fbe2f69d" +
                "23e5b87383efd83c92577b366f20d10df45425fcb285ef63d582d1f1010",
            calls = 0,
        ),
        Vector(
            type = GridType.TRIANGULAR,
            w = 3,
            h = 3,
            seed = "1",
            bias = true,
            faces = listOf(
                2, 0, 0, 0, 2, 0, 2, 2, 0, 0, 0, 0, 0, 0, 2, 2, 0, 0, 0,
            ),
            state = "3b6a192b7913b04c54574d18c28d46e6395428abe6cc90b878b948c35e92b0" +
                "03c792c46c58c4af40ec10241d640b13f4bc54ecccafd74e3e1a4b674907",
            calls = 178,
        ),
        Vector(
            type = GridType.SNUBSQUARE,
            w = 3,
            h = 3,
            seed = "2",
            bias = true,
            faces = listOf(
                0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0,
            ),
            state = "e04b9237bacccdf19c0760cab7aec4a8359010b012033b78389744f3f39ac4" +
                "ce4ccfcad6960d8ea01dad65cddce0a83fedd421ca8a592155b4e991fd10",
            calls = 188,
        ),
        Vector(
            type = GridType.CAIRO,
            w = 3,
            h = 4,
            seed = "353",
            bias = false,
            faces = listOf(2, 0, 0, 0, 0, 0, 2, 2, 0, 2, 0, 0, 0, 0, 0, 0, 2),
            state = "8fda660e06b787f245667943dc948dafab997e2598a64b0c587d1f98be5b11" +
                "64a6760e5c273066fb382d7bf1a87468e87f8eb8de1b11baea1e547ad80c",
            calls = 0,
        ),
    )

    /** The same heuristic as `loopgen_count_bias` in the oracle. */
    private fun countBias(grid: Grid, calls: IntArray): LoopBias = { board, faceIndex ->
        calls[0]++
        val face = grid.faces[faceIndex]
        var white = 0
        for (i in 0 until face.order) {
            val edge = face.edge(i)
            val other = if (edge.face1 === face) edge.face2 else edge.face1
            if (colourOf(board, other) == FaceColour.WHITE) white++
        }
        7 * white + if (board[faceIndex] == FaceColour.WHITE.ordinal) 1 else 0
    }

    private fun colourOf(board: IntArray, face: GridFace?): FaceColour =
        if (face == null) FaceColour.BLACK else FaceColour.entries[board[face.index]]

    private fun build(vector: Vector): Grid {
        val desc = Grid.newDesc(
            vector.type,
            vector.w,
            vector.h,
            RandomState.fromSeed(vector.seed),
        )
        return Grid.new(vector.type, vector.w, vector.h, desc)
    }

    private fun belongsToLoop(board: IntArray, edge: GridEdge): Boolean =
        colourOf(board, edge.face1) != colourOf(board, edge.face2)

    /**
     * A generated board must be a complete colouring whose white and
     * black regions meet along exactly one closed loop.
     *
     * Around every dot, the faces alternate between the two colours
     * exactly zero or two times. Zero transitions are the inside of a
     * region; two transitions give the dot two loop edges. Four or more
     * would mean the boundary touches the dot twice. The loop edges
     * must then form one cycle, and each colour must form one connected
     * region, so the loop has no islands.
     */
    private fun assertValidLoop(grid: Grid, board: IntArray, where: String) {
        assertEquals(grid.faces.size, board.size, "board size $where")
        for ((i, value) in board.withIndex()) {
            assertTrue(
                value == FaceColour.WHITE.ordinal || value == FaceColour.BLACK.ordinal,
                "face $i is not white or black at $where",
            )
        }

        val degree = IntArray(grid.dots.size)
        val neighbours = HashMap<Int, MutableList<Int>>()
        for (edge in grid.edges) {
            if (!belongsToLoop(board, edge)) continue
            val a = edge.dot1.index
            val b = edge.dot2.index
            degree[a]++
            degree[b]++
            neighbours.getOrPut(a) { mutableListOf() }.add(b)
            neighbours.getOrPut(b) { mutableListOf() }.add(a)
        }

        for (dot in grid.dots) {
            var transitions = 0
            for (j in 0 until dot.order) {
                val here = colourOf(board, dot.faces[j])
                val next = colourOf(board, dot.faces[(j + 1) % dot.order])
                if (here != next) transitions++
            }
            assertTrue(
                transitions == 0 || transitions == 2,
                "dot ${dot.index} has $transitions transitions at $where",
            )
            assertEquals(
                transitions,
                degree[dot.index],
                "dot ${dot.index} loop degree at $where",
            )
        }

        val vertices = neighbours.keys
        assertTrue(vertices.isNotEmpty(), "the loop has no edges at $where")
        for (vertex in vertices) {
            assertEquals(2, neighbours.getValue(vertex).size, "loop degree at $where")
        }

        // Walk the cycle. A single component returns to the start after
        // exactly as many steps as it has edges.
        val start = vertices.first()
        var previous = -1
        var current = start
        var steps = 0
        while (true) {
            val ends = neighbours.getValue(current)
            val next = if (ends[0] != previous) ends[0] else ends[1]
            previous = current
            current = next
            steps++
            if (current == start) break
            assertTrue(steps <= vertices.size, "the loop does not close at $where")
        }
        assertEquals(vertices.size, steps, "the loop is not a single cycle at $where")

        // Faces of one colour must be connected. The infinite face is
        // the extra node at index faces.size and is always black.
        val parent = IntArray(grid.faces.size + 1) { it }
        fun root(node: Int): Int {
            var n = node
            while (parent[n] != n) {
                parent[n] = parent[parent[n]]
                n = parent[n]
            }
            return n
        }
        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }
        val outside = grid.faces.size
        for (edge in grid.edges) {
            val a = edge.face1?.index ?: outside
            val b = edge.face2?.index ?: outside
            if (colourOf(board, edge.face1) == colourOf(board, edge.face2)) union(a, b)
        }
        val whiteRoot = board.indices.firstOrNull { board[it] == FaceColour.WHITE.ordinal }
            ?.let { root(it) }
        val blackRoot = root(outside)
        for (i in board.indices) {
            when (board[i]) {
                FaceColour.WHITE.ordinal ->
                    assertEquals(whiteRoot, root(i), "white face $i is a separate island at $where")

                FaceColour.BLACK.ordinal ->
                    assertEquals(blackRoot, root(i), "black face $i is a separate island at $where")
            }
        }
    }

    @Test
    fun `fixed vectors match upstream`() {
        for (vector in vectors) {
            val where = "${vector.type.backend} ${vector.w}x${vector.h} seed ${vector.seed} bias ${vector.bias}"
            val grid = build(vector)
            val random = RandomState.fromSeed(vector.seed)
            val board = IntArray(grid.faces.size)
            val calls = intArrayOf(0)
            val bias = if (vector.bias) countBias(grid, calls) else null

            generateLoop(grid, board, random, bias)

            assertEquals(vector.faces, board.toList(), "faces $where")
            assertEquals(vector.state, random.encodeState(), "state $where")
            assertEquals(vector.calls, calls[0], "bias calls $where")
        }
    }

    @Test
    fun `every generated board is a single valid loop`() {
        val cases = listOf(
            Vector(GridType.SQUARE, 5, 5, "12345", false, emptyList(), "", 0),
            Vector(GridType.SQUARE, 4, 7, "987654", true, emptyList(), "", 0),
            Vector(GridType.HONEYCOMB, 4, 3, "12345", true, emptyList(), "", 0),
            Vector(GridType.HONEYCOMB, 5, 5, "9", false, emptyList(), "", 0),
            Vector(GridType.TRIANGULAR, 4, 4, "12345", true, emptyList(), "", 0),
            Vector(GridType.TRIANGULAR, 2, 5, "9", false, emptyList(), "", 0),
            Vector(GridType.SNUBSQUARE, 4, 4, "12345", true, emptyList(), "", 0),
            Vector(GridType.CAIRO, 4, 3, "12345", false, emptyList(), "", 0),
            Vector(GridType.GREATHEXAGONAL, 3, 3, "12345", true, emptyList(), "", 0),
            Vector(GridType.KAGOME, 3, 3, "12345", false, emptyList(), "", 0),
            Vector(GridType.OCTAGONAL, 3, 3, "12345", true, emptyList(), "", 0),
            Vector(GridType.KITES, 3, 3, "12345", false, emptyList(), "", 0),
            Vector(GridType.FLORET, 2, 2, "12345", true, emptyList(), "", 0),
            Vector(GridType.DODECAGONAL, 2, 2, "12345", false, emptyList(), "", 0),
            Vector(GridType.GREATDODECAGONAL, 2, 2, "12345", true, emptyList(), "", 0),
            Vector(GridType.GREATGREATDODECAGONAL, 2, 2, "12345", false, emptyList(), "", 0),
            Vector(GridType.COMPASSDODECAGONAL, 2, 2, "12345", true, emptyList(), "", 0),
            Vector(GridType.PENROSE_P2, 5, 5, "12345", false, emptyList(), "", 0),
            Vector(GridType.PENROSE_P3, 6, 6, "12345", true, emptyList(), "", 0),
            Vector(GridType.HATS, 6, 6, "12345", false, emptyList(), "", 0),
            Vector(GridType.SPECTRES, 7, 7, "12345", true, emptyList(), "", 0),
        )

        for (case in cases) {
            val where = "${case.type.backend} ${case.w}x${case.h} seed ${case.seed}"
            val grid = build(case)
            val random = RandomState.fromSeed(case.seed)
            val board = IntArray(grid.faces.size)
            val calls = intArrayOf(0)
            generateLoop(grid, board, random, if (case.bias) countBias(grid, calls) else null)
            assertValidLoop(grid, board, where)
        }
    }

    @Test
    fun `a bias that always returns zero keeps the plain order`() {
        val grid = Grid.new(GridType.SQUARE, 5, 5, null)

        val plainRandom = RandomState.fromSeed("12345")
        val plain = IntArray(grid.faces.size)
        generateLoop(grid, plain, plainRandom)

        val biasedRandom = RandomState.fromSeed("12345")
        val biased = IntArray(grid.faces.size)
        generateLoop(grid, biased, biasedRandom) { _, _ -> 0 }

        assertEquals(plain.toList(), biased.toList(), "board")
        assertEquals(plainRandom.encodeState(), biasedRandom.encodeState(), "state")
    }

    @Test
    fun `the board must hold every face`() {
        val grid = Grid.new(GridType.SQUARE, 3, 3, null)
        assertFailsWith<IllegalArgumentException> {
            generateLoop(grid, IntArray(grid.faces.size - 1), RandomState.fromSeed("1"))
        }
    }

    @Test
    fun `a one-face grid is rejected instead of hanging`() {
        val grid = Grid.new(GridType.SQUARE, 1, 1, null)
        assertEquals(1, grid.faces.size, "faces")
        assertFailsWith<IllegalArgumentException> {
            generateLoop(grid, IntArray(1), RandomState.fromSeed("1"))
        }
    }
}
