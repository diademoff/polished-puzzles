package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `dsf.c`, the disjoint-set forest.
 *
 * The forest stores the class size in the canonical element, so the
 * shape of the tree is visible through [canonify] and [size]. Games
 * use the result for solver decisions, so the merge and path-compress
 * rules match upstream exactly.
 *
 * Three variants exist: the plain forest, the forest that tracks the
 * minimum element of each class, and the forest that tracks a flipped
 * sense along the parent chain.
 */
class Dsf private constructor(
    val elementCount: Int,
    private val trackFlip: Boolean,
    private val trackMin: Boolean,
) {
    private val parentOrSize = IntArray(elementCount) { FLAG_CANONICAL or 1 }
    private val flip = if (trackFlip) BooleanArray(elementCount) else null
    private val min = if (trackMin) IntArray(elementCount) { it } else null

    companion object {
        private const val INDEX_MASK = 0x7FFFFFFF
        private const val FLAG_CANONICAL = Int.MIN_VALUE

        fun new(elementCount: Int): Dsf {
            require(elementCount in 1..INDEX_MASK) { "bad dsf size: $elementCount" }
            return Dsf(elementCount, trackFlip = false, trackMin = false)
        }

        fun newFlip(elementCount: Int): Dsf {
            require(elementCount in 1..INDEX_MASK) { "bad dsf size: $elementCount" }
            return Dsf(elementCount, trackFlip = true, trackMin = false)
        }

        fun newMin(elementCount: Int): Dsf {
            require(elementCount in 1..INDEX_MASK) { "bad dsf size: $elementCount" }
            return Dsf(elementCount, trackFlip = false, trackMin = true)
        }
    }

    /** `dsf_copy`: copies the structure into another forest of the same kind. */
    fun copyInto(target: Dsf) {
        require(target.elementCount == elementCount) { "dsf size mismatch" }
        if (target.flip != null) require(flip != null) { "copying a non-flip dsf to a flip one" }
        if (target.min != null) require(min != null) { "copying a non-min dsf to a min one" }
        parentOrSize.copyInto(target.parentOrSize)
        if (target.flip != null && flip != null) flip.copyInto(target.flip)
        if (target.min != null && min != null) min.copyInto(target.min)
    }

    /** A copy of this forest. */
    fun copy(): Dsf = Dsf(elementCount, trackFlip, trackMin).also { copyInto(it) }

    fun reinit() {
        parentOrSize.fill(FLAG_CANONICAL or 1)
        min?.let { for (i in it.indices) it[i] = i }
    }

    fun canonify(n: Int): Int {
        require(n in 0 until elementCount) { "dsf index out of range: $n" }
        val root = findRoot(n)
        pathCompress(n, root)
        return root
    }

    fun equivalent(a: Int, b: Int): Boolean = canonify(a) == canonify(b)

    fun size(n: Int): Int {
        val root = canonify(n)
        return parentOrSize[root] and INDEX_MASK
    }

    fun merge(a: Int, b: Int) {
        require(!trackFlip) { "dsf_merge on a flip dsf" }
        require(a in 0 until elementCount && b in 0 until elementCount) { "dsf index out of range" }

        val r1 = findRoot(a)
        val r2 = findRoot(b)
        val root: Int
        if (r1 == r2) {
            root = r1
        } else {
            val s1 = parentOrSize[r1] and INDEX_MASK
            val s2 = parentOrSize[r2] and INDEX_MASK
            if (s1 > s2) {
                parentOrSize[r2] = r1
                root = r1
            } else {
                parentOrSize[r1] = r2
                root = r2
            }
            parentOrSize[root] = (s1 + s2) or FLAG_CANONICAL
            if (min != null) {
                val m1 = min[r1]
                val m2 = min[r2]
                min[root] = if (m1 < m2) m1 else m2
            }
        }
        pathCompress(a, root)
        pathCompress(b, root)
    }

    data class FlipRoot(val root: Int, val inverse: Boolean)

    fun canonifyFlip(n: Int): FlipRoot {
        require(trackFlip) { "dsf_canonify_flip on a non-flip dsf" }
        require(n in 0 until elementCount) { "dsf index out of range: $n" }

        var current = n
        var inverse = false
        while (!isCanonical(current)) {
            inverse = inverse xor flip!![current]
            current = parentOrSize[current]
        }
        pathCompressFlip(n, current, inverse)
        return FlipRoot(current, inverse)
    }

    fun mergeFlip(a: Int, b: Int, inverse: Boolean) {
        require(trackFlip) { "dsf_merge_flip on a non-flip dsf" }
        require(a in 0 until elementCount && b in 0 until elementCount) { "dsf index out of range" }

        val r1 = findRootFlip(a)
        val r2 = findRootFlip(b)
        var f1 = r1.inverse
        var f2 = r2.inverse
        val root: Int
        if (r1.root == r2.root) {
            check(!(f1 xor f2 xor inverse)) { "inconsistency in dsf_merge_flip" }
            root = r1.root
        } else {
            val s1 = parentOrSize[r1.root] and INDEX_MASK
            val s2 = parentOrSize[r2.root] and INDEX_MASK
            if (s1 > s2) {
                root = r1.root
                parentOrSize[r2.root] = root
                flip!![r2.root] = f1 xor f2 xor inverse
                f2 = f2 xor flip!![r2.root]
            } else {
                root = r2.root
                parentOrSize[r1.root] = root
                flip!![r1.root] = f1 xor f2 xor inverse
                f1 = f1 xor flip!![r1.root]
            }
            parentOrSize[root] = (s1 + s2) or FLAG_CANONICAL
            if (min != null) {
                val m1 = min[r1.root]
                val m2 = min[r2.root]
                min[root] = if (m1 < m2) m1 else m2
            }
        }
        pathCompressFlip(a, root, f1)
        pathCompressFlip(b, root, f2)
    }

    fun minimal(n: Int): Int {
        require(trackMin) { "dsf_minimal on a non-min dsf" }
        return min!![canonify(n)]
    }

    private fun isCanonical(n: Int): Boolean = parentOrSize[n] and FLAG_CANONICAL != 0

    private fun findRoot(start: Int): Int {
        var n = start
        while (!isCanonical(n)) n = parentOrSize[n]
        return n
    }

    private fun pathCompress(start: Int, root: Int) {
        var n = start
        while (!isCanonical(n)) {
            val previous = n
            n = parentOrSize[n]
            parentOrSize[previous] = root
        }
        check(n == root)
    }

    private fun findRootFlip(start: Int): FlipRoot {
        var n = start
        var inverse = false
        while (!isCanonical(n)) {
            inverse = inverse xor flip!![n]
            n = parentOrSize[n]
        }
        return FlipRoot(n, inverse)
    }

    private fun pathCompressFlip(start: Int, root: Int, startInverse: Boolean) {
        var n = start
        var inverse = startInverse
        while (!isCanonical(n)) {
            val previous = n
            val previousInverse = inverse
            n = parentOrSize[n]
            inverse = inverse xor flip!![previous]
            flip[previous] = previousInverse
            parentOrSize[previous] = root
        }
        check(n == root)
    }
}
