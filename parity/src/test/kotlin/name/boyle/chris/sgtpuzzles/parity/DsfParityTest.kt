package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Dsf
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Level: strict. Differential test for `dsf.c`.
 *
 * The canonical element, class size, tracked minimum, and flip sense
 * all affect solver decisions. The test runs the same merge script on
 * both sides and compares the state of every element.
 */
class DsfParityTest {

    private val kinds = listOf("normal", "flip", "min")
    private val sizes = listOf(1, 2, 3, 5, 17, 64)

    @Test
    fun `dsf matches upstream for every variant`() = OracleClient().use { oracle ->
        for (kind in kinds) {
            for (n in sizes) {
                for (case in 0 until 6) {
                    val random = Random(n * 1000 + case * 17 + kind[0].code)
                    val ops = buildOps(kind, n, random)

                    val expected = oracle.dsf(kind, n, ops)
                    val actual = replay(kind, n, ops)

                    // Read flip data before dsf_size: upstream's
                    // non-flip compression invalidates it.
                    val roots = IntArray(n)
                    val inverses = IntArray(n)
                    for (i in 0 until n) {
                        if (kind == "flip") {
                            val result = actual.canonifyFlip(i)
                            roots[i] = result.root
                            inverses[i] = if (result.inverse) 1 else 0
                        } else {
                            roots[i] = actual.canonify(i)
                        }
                    }

                    for (i in 0 until n) {
                        val where = "$kind n=$n case=$case element=$i"
                        assertEquals("root $where", expected.roots[i], roots[i])
                        assertEquals("size $where", expected.sizes[i], actual.size(i))
                        if (kind == "min") {
                            assertEquals("min $where", expected.mins[i], actual.minimal(i))
                        }
                        if (kind == "flip") {
                            assertEquals("inverse $where", expected.inverses[i], inverses[i])
                        }
                    }
                }
            }
        }
    }

    private fun buildOps(kind: String, n: Int, random: Random): String {
        val reference = newDsf(kind, n)
        val ops = StringBuilder()
        repeat(n * 3 + 10) {
            val a = random.nextInt(n)
            val b = random.nextInt(n)
            if (kind == "flip") {
                val fa = reference.canonifyFlip(a)
                val fb = reference.canonifyFlip(b)
                val inverse = if (fa.root == fb.root) {
                    fa.inverse xor fb.inverse
                } else {
                    random.nextBoolean()
                }
                ops.append("f ").append(a).append(' ').append(b).append(' ')
                    .append(if (inverse) 1 else 0).append(' ')
                reference.mergeFlip(a, b, inverse)
            } else {
                ops.append("m ").append(a).append(' ').append(b).append(' ')
                reference.merge(a, b)
            }
        }
        return ops.toString()
    }

    private fun replay(kind: String, n: Int, ops: String): Dsf {
        val dsf = newDsf(kind, n)
        val tokens = ops.trim().split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            when (tokens[i++]) {
                "m" -> {
                    val a = tokens[i++].toInt()
                    val b = tokens[i++].toInt()
                    dsf.merge(a, b)
                }

                "f" -> {
                    val a = tokens[i++].toInt()
                    val b = tokens[i++].toInt()
                    val inverse = tokens[i++].toInt() != 0
                    dsf.mergeFlip(a, b, inverse)
                }

                else -> error("bad dsf op in $ops")
            }
        }
        return dsf
    }

    private fun newDsf(kind: String, n: Int): Dsf = when (kind) {
        "normal" -> Dsf.new(n)
        "flip" -> Dsf.newFlip(n)
        "min" -> Dsf.newMin(n)
        else -> error("bad dsf kind: $kind")
    }
}
