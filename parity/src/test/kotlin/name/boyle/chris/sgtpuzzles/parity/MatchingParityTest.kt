package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Matching
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Level: strict. Differential test for `matching.c`.
 *
 * The matching must have the same size and the same pairing, and the
 * seeded run must permute the adjacency lists in the same way. A
 * different maximum matching may still be valid, but it is not parity.
 */
class MatchingParityTest {

    @Test
    fun `matching matches upstream`() = OracleClient().use { oracle ->
        for (nl in 0..6) {
            for (nr in 0..6) {
                for (case in 0 until 4) {
                    val random = Random(nl * 1000 + nr * 10 + case)
                    val adjacency = List(nl) {
                        (0 until nr).filter { random.nextInt(100) < 45 }
                    }
                    val text = adjacency.joinToString(";") { it.joinToString(" ") }

                    for (seed in listOf(null, "12345", "another seed")) {
                        val expected = oracle.matching(nl, nr, adjacency, seed)
                        val actualAdjacency = adjacency.map { it.toIntArray() }.toTypedArray()
                        val actual = Matching.match(
                            nl,
                            nr,
                            actualAdjacency,
                            seed?.let { RandomState.fromSeed(it) },
                        )

                        val where = "nl=$nl nr=$nr case=$case seed=$seed adj=$text"
                        assertEquals("count $where", expected.count, actual.count)
                        assertEquals("outL $where", expected.outL.toList(), actual.leftToRight.toList())
                        assertEquals("outR $where", expected.outR.toList(), actual.rightToLeft.toList())
                        assertEquals(
                            "permuted adjacency $where",
                            expected.adjacency.map { it.toList() },
                            actualAdjacency.map { it.toList() },
                        )
                    }
                }
            }
        }
    }
}
