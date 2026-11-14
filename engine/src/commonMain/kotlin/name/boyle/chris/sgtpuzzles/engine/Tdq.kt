package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `tdq.c`: a de-duplicating to-do queue.
 *
 * Adding a value that is already present does nothing. Removal order
 * is the order of first insertion. Solvers use the queue for their
 * work list, so the order is observable.
 */
class Tdq(val size: Int) {
    init {
        require(size > 0) { "tdq needs a positive size" }
    }

    private val queue = IntArray(size)
    private val flags = BooleanArray(size)
    private var inPointer = 0
    private var outPointer = 0

    fun add(k: Int) {
        require(k in 0 until size) { "tdq index out of range: $k" }
        if (!flags[k]) {
            queue[inPointer] = k
            flags[k] = true
            inPointer++
            if (inPointer == size) inPointer = 0
        }
    }

    /** Removes the front of the queue, or returns -1 when it is empty. */
    fun remove(): Int {
        val result = queue[outPointer]
        if (!flags[result]) return -1

        flags[result] = false
        outPointer++
        if (outPointer == size) outPointer = 0
        return result
    }

    /** Adds every value from 0 to size-1 that is not already present. */
    fun fill() {
        for (i in 0 until size) add(i)
    }
}
