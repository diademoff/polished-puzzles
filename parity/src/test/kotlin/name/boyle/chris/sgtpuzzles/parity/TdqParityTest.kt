package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Tdq
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Level: strict. Differential test for `tdq.c`.
 *
 * The queue deduplicates on add and preserves the first insertion
 * order. Solvers depend on that order, so the script and every removal
 * are compared with upstream.
 */
class TdqParityTest {

    @Test
    fun `tdq matches upstream`() = OracleClient().use { oracle ->
        for (n in listOf(1, 2, 3, 5, 10, 17, 64)) {
            for (case in 0 until 6) {
                val random = Random(n * 100 + case)
                val ops = StringBuilder()
                repeat(n * 2 + 5) {
                    when (random.nextInt(3)) {
                        0 -> ops.append("r ")
                        1 -> ops.append("f ")
                        else -> ops.append("a ").append(random.nextInt(n)).append(' ')
                    }
                }

                val expected = oracle.tdq(n, ops.toString())
                val queue = Tdq(n)
                val actual = ArrayList<Int>()
                val tokens = ops.trim().split(' ').filter { it.isNotEmpty() }
                var i = 0
                while (i < tokens.size) {
                    when (tokens[i++]) {
                        "a" -> queue.add(tokens[i++].toInt())
                        "r" -> actual += queue.remove()
                        "f" -> queue.fill()
                        else -> error("bad tdq op")
                    }
                }
                while (true) {
                    val value = queue.remove()
                    if (value < 0) break
                    actual += value
                }

                assertEquals("n=$n case=$case ops=$ops", expected, actual)
            }
        }
    }
}
