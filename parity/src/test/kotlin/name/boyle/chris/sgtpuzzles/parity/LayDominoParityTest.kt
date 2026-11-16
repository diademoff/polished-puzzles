package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.LayDomino
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Level: semantic. Differential test for `laydomino.c`.
 *
 * The oracle runs `domino_layout` and `domino_layout_prealloc` from the
 * same seed and returns both grids, the attempt count, and the encoded
 * generator state after each call. The state is the strongest check: it
 * catches a missing or extra candidate draw, a missing neighbour
 * shuffle, or a different neighbour order even when the layout happens
 * to look valid.
 *
 * laydomino has no retry, so the oracle always reports one attempt. The
 * extra seeds below are chosen to force several singleton repair passes,
 * which is where the per-pop neighbour shuffles are drawn.
 */
class LayDominoParityTest {

    private data class Case(val w: Int, val h: Int, val seed: String)

    // The required shapes at the frozen golden seed, then extra seeds
    // that need one or more repair passes. Measured pass counts:
    // 6x5 seed 3 -> 3, 10x9 seed 2 -> 6, 5x2 seed 1 -> 1.
    private val cases = listOf(
        Case(1, 1, "12345"),
        Case(1, 5, "12345"),
        Case(2, 2, "12345"),
        Case(3, 3, "12345"),
        Case(4, 4, "12345"),
        Case(6, 5, "12345"),
        Case(8, 7, "12345"),
        Case(10, 9, "12345"),
        Case(6, 5, "3"),
        Case(10, 9, "2"),
        Case(5, 2, "1"),
        Case(8, 7, "1"),
        Case(3, 3, "3"),
        // A column and a two-cell board.
        Case(1, 10, "99999"),
        Case(2, 1, "12345"),
    )

    @Test
    fun `laydomino matches upstream`() = OracleClient().use { oracle ->
        for (case in cases) {
            val expected = oracle.layDomino(case.w, case.h, seed = case.seed)
            val where = "${case.w}x${case.h} seed ${case.seed}"

            val random = RandomState.fromSeed(case.seed)
            val grid = LayDomino.layout(case.w, case.h, random)

            assertEquals("attempts $where", 1, expected.attempts)
            assertEquals("grid $where", expected.grid, grid.toList())
            assertEquals("state $where", expected.state, random.encodeState())

            // Both C entry points agree, and both Kotlin entry points
            // agree, on the grid and on the stream.
            assertEquals("C prealloc grid $where", expected.grid, expected.preallocGrid)
            assertEquals("C prealloc state $where", expected.state, expected.preallocState)

            val wh = case.w * case.h
            val preallocGrid = IntArray(wh)
            val grid2 = IntArray(wh)
            val list = IntArray(2 * wh)
            val preallocRandom = RandomState.fromSeed(case.seed)
            LayDomino.layoutPrealloc(
                case.w,
                case.h,
                preallocRandom,
                preallocGrid,
                grid2,
                list,
            )
            assertEquals("Kotlin prealloc grid $where", expected.preallocGrid, preallocGrid.toList())
            assertEquals(
                "Kotlin prealloc state $where",
                expected.preallocState,
                preallocRandom.encodeState(),
            )
        }
    }

    @Test
    fun `laydomino continues from an encoded state`() = OracleClient().use { oracle ->
        // The state check is not tied to a seed: a test can resume the
        // stream at any draw.
        val first = oracle.layDomino(6, 5, seed = "12345")
        val second = oracle.layDomino(6, 5, state = first.state)
        val random = RandomState.decode(first.state)
        val grid = LayDomino.layout(6, 5, random)
        assertEquals("grid", second.grid, grid.toList())
        assertEquals("state", second.state, random.encodeState())
    }

    @Test
    fun `laydomino is deterministic`() = OracleClient().use { oracle ->
        val first = oracle.layDomino(8, 7, seed = "12345")
        val second = oracle.layDomino(8, 7, seed = "12345")
        assertEquals("grid", first.grid, second.grid)
        assertEquals("attempts", first.attempts, second.attempts)
        assertEquals("state", first.state, second.state)
        assertEquals("prealloc grid", first.preallocGrid, second.preallocGrid)
        assertEquals("prealloc state", first.preallocState, second.preallocState)
    }

    @Test
    fun `laydomino rejects invalid input`() {
        OracleClient().use { oracle ->
            for (w in listOf(0, -1)) {
                assertThrows(OracleException::class.java) {
                    oracle.layDomino(w, 2, seed = "12345")
                }
            }
            for (h in listOf(0, -1)) {
                assertThrows(OracleException::class.java) {
                    oracle.layDomino(2, h, seed = "12345")
                }
            }
            // The area cap is 1000000 cells.
            assertThrows(OracleException::class.java) {
                oracle.layDomino(2000, 501, seed = "12345")
            }
            assertThrows(OracleException::class.java) {
                oracle.layDomino(6, 5)
            }
            // A missing dimension is not a zero dimension.
            assertThrows(OracleException::class.java) {
                oracle.request(mapOf("op" to "laydomino", "w" to 6, "seed" to "12345"))
            }
        }
    }
}
