package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.FaceColour
import name.boyle.chris.sgtpuzzles.engine.Grid
import name.boyle.chris.sgtpuzzles.engine.GridFace
import name.boyle.chris.sgtpuzzles.engine.GridType
import name.boyle.chris.sgtpuzzles.engine.LoopBias
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.generateLoop
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: strict. Differential test for `loopgen.c`.
 *
 * The oracle runs `generate_loop` on the same grid and compares the
 * whole face colouring and the encoded generator state. A difference
 * in any RNG draw, in either candidate list, in the sort tie-breaker,
 * or in the flip passes fails the test even when both boards form a
 * valid loop.
 *
 * Two bias modes run over the same matrix. `none` uses the plain
 * ordering. `count` uses the deterministic oracle heuristic, which
 * makes every candidate's temporary colour observable and checks the
 * three calls C makes per coloured face.
 */
class LoopgenParityTest {

    private data class Case(val type: GridType, val w: Int, val h: Int)

    /** Sizes at or above the loopy limits; smaller degenerate grids hang C. */
    private val cases = listOf(
        Case(GridType.SQUARE, 3, 3),
        Case(GridType.SQUARE, 5, 5),
        Case(GridType.SQUARE, 7, 4),
        Case(GridType.HONEYCOMB, 3, 3),
        Case(GridType.HONEYCOMB, 4, 3),
        Case(GridType.HONEYCOMB, 5, 5),
        Case(GridType.TRIANGULAR, 3, 3),
        Case(GridType.TRIANGULAR, 4, 4),
        Case(GridType.TRIANGULAR, 2, 5),
        Case(GridType.SNUBSQUARE, 3, 3),
        Case(GridType.SNUBSQUARE, 4, 4),
        Case(GridType.CAIRO, 3, 4),
        Case(GridType.CAIRO, 4, 3),
        Case(GridType.GREATHEXAGONAL, 3, 3),
        Case(GridType.KAGOME, 3, 3),
        Case(GridType.KAGOME, 4, 2),
        Case(GridType.OCTAGONAL, 3, 3),
        Case(GridType.KITES, 3, 3),
        Case(GridType.FLORET, 1, 2),
        Case(GridType.FLORET, 2, 2),
        Case(GridType.DODECAGONAL, 2, 2),
        Case(GridType.GREATDODECAGONAL, 2, 2),
        Case(GridType.GREATGREATDODECAGONAL, 2, 2),
        Case(GridType.COMPASSDODECAGONAL, 2, 2),
        Case(GridType.PENROSE_P2, 5, 5),
        Case(GridType.PENROSE_P3, 6, 6),
        Case(GridType.HATS, 6, 6),
        Case(GridType.SPECTRES, 6, 6),
    )

    private val seeds = listOf("12345", "9", "a longer seed")

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

    @Test
    fun `loop generation matches upstream`() = OracleClient().use { oracle ->
        for (case in cases) {
            for (seed in seeds) {
                val desc = Grid.newDesc(
                    case.type,
                    case.w,
                    case.h,
                    RandomState.fromSeed(seed),
                )
                val grid = Grid.new(case.type, case.w, case.h, desc)

                for (biasName in listOf("none", "count")) {
                    val where = "${case.type.backend} ${case.w}x${case.h} seed $seed bias $biasName"
                    val expected = oracle.loopgen(
                        case.type.backend,
                        case.w,
                        case.h,
                        biasName,
                        desc = desc,
                        seed = seed,
                    )

                    val random = RandomState.fromSeed(seed)
                    val board = IntArray(grid.faces.size)
                    val calls = intArrayOf(0)
                    val bias = if (biasName == "count") countBias(grid, calls) else null
                    generateLoop(grid, board, random, bias)

                    assertEquals("faces $where", expected.faces, board.toList())
                    assertEquals("state $where", expected.state, random.encodeState())
                    assertEquals("bias calls $where", expected.biasCalls, calls[0])
                }
            }
        }
    }

    @Test
    fun `loop generation continues from an encoded state`() = OracleClient().use { oracle ->
        val first = oracle.loopgen("square", 5, 5, "count", seed = "12345")
        val second = oracle.loopgen("square", 5, 5, "count", state = first.state)

        val grid = Grid.new(GridType.SQUARE, 5, 5, null)
        val random = RandomState.decode(first.state)
        val board = IntArray(grid.faces.size)
        val calls = intArrayOf(0)
        generateLoop(grid, board, random, countBias(grid, calls))

        assertEquals("faces", second.faces, board.toList())
        assertEquals("state", second.state, random.encodeState())
        assertEquals("bias calls", second.biasCalls, calls[0])
    }
}
