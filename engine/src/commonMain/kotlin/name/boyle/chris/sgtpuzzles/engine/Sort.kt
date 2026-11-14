package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `arraysort()`, the heapsort in `sort.c`.
 *
 * Games use it for deterministic iteration where the input order is
 * not already meaningful. The comparator decides ties, and the heap
 * order decides where equal elements land. Do not replace this with
 * `sortedWith`: the result would be stable and therefore different.
 *
 * `DeterministicHelpersParityTest` compares the order with the oracle.
 */
object Sort {

    fun <T> arraySort(array: MutableList<T>, compare: (T, T) -> Int) {
        val size = array.size
        if (size < 2) return

        var i = parent(size - 1) + 1
        while (i-- > 0) {
            downheap(array, size, compare, i)
        }
        i = size - 1
        while (i > 0) {
            swap(array, 0, i)
            downheap(array, i, compare, 0)
            i--
        }
    }

    private fun <T> downheap(array: MutableList<T>, size: Int, compare: (T, T) -> Int, start: Int) {
        var i = start
        while (leftChild(i) < size) {
            var j = i
            if (compare(array[j], array[leftChild(i)]) < 0) j = leftChild(i)
            if (rightChild(i) < size && compare(array[j], array[rightChild(i)]) < 0) j = rightChild(i)

            if (j == i) return
            swap(array, j, i)
            i = j
        }
    }

    private fun <T> swap(array: MutableList<T>, a: Int, b: Int) {
        val tmp = array[a]
        array[a] = array[b]
        array[b] = tmp
    }

    private fun parent(i: Int): Int = (i - 1) / 2
    private fun leftChild(i: Int): Int = 2 * i + 1
    private fun rightChild(i: Int): Int = 2 * i + 2
}
