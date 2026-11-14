package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Relation
import name.boyle.chris.sgtpuzzles.engine.Tree234
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Level: strict. Differential test for `tree234.c`.
 *
 * `add` returns the existing element on a duplicate key; the find
 * relations choose the nearest element in sorted order. Both are
 * observable, so the operation results and the final index order are
 * compared with upstream.
 */
class Tree234ParityTest {

    private data class Item(val key: Int, val id: Int)

    @Test
    fun `sorted tree234 matches upstream`() = OracleClient().use { oracle ->
        for (case in 0 until 12) {
            val random = Random(case * 101 + 7)
            var nextId = 1
            val ops = StringBuilder()
            val expected = ArrayList<Int>()
            val tree = Tree234.sorted(compareBy<Item> { it.key })

            repeat(160) {
                val key = random.nextInt(21)
                when (random.nextInt(9)) {
                    0, 1 -> {
                        val item = Item(key, nextId++)
                        ops.append("a ").append(key).append(' ').append(item.id).append(' ')
                        expected += tree.add(item).id
                    }

                    2 -> {
                        ops.append("f ").append(key).append(' ')
                        expected += tree.find(Item(key, -1))?.id ?: -1
                    }

                    3 -> {
                        ops.append("d ").append(key).append(' ')
                        expected += tree.delete(Item(key, -1))?.id ?: -1
                    }

                    4 -> {
                        ops.append("t ").append(key).append(' ')
                        expected += tree.findRel(Item(key, -1), relation = Relation.LT)?.id ?: -1
                    }

                    5 -> {
                        ops.append("e ").append(key).append(' ')
                        expected += tree.findRel(Item(key, -1), relation = Relation.LE)?.id ?: -1
                    }

                    6 -> {
                        ops.append("g ").append(key).append(' ')
                        expected += tree.findRel(Item(key, -1), relation = Relation.GT)?.id ?: -1
                    }

                    7 -> {
                        ops.append("h ").append(key).append(' ')
                        expected += tree.findRel(Item(key, -1), relation = Relation.GE)?.id ?: -1
                    }

                    else -> {
                        val index = random.nextInt(12)
                        ops.append("i ").append(index).append(' ')
                        expected += tree.index(index)?.id ?: -1
                    }
                }
            }

            val actual = oracle.tree(ops.toString())
            assertEquals("results case=$case", expected, actual.results)
            assertEquals(
                "items case=$case",
                tree.elements().map { "${it.key}:${it.id}" },
                actual.items,
            )
        }
    }
}
