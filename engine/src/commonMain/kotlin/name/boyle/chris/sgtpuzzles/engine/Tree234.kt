package name.boyle.chris.sgtpuzzles.engine

/**
 * A behavioral port of upstream `tree234.c`.
 *
 * The C type is a 2-3-4 tree. This port keeps the same public
 * operations and the same ordering, but stores the elements in a
 * sorted list. Games observe the sorted order and the duplicate-key
 * rule (`add` returns the existing element), not the tree shape, so a
 * list is a faithful replacement for the current consumers. It can be
 * replaced by a balanced tree when a solver profile needs it.
 *
 * An unsorted tree has no comparator: only positional operations are
 * available, exactly as upstream documents.
 */
class Tree234<T> private constructor(private val comparator: Comparator<T>?) {

    private val items = ArrayList<T>()

    val count: Int get() = items.size

    val isSorted: Boolean get() = comparator != null

    /** The elements in index order. */
    fun elements(): List<T> = items.toList()

    /**
     * Adds a sorted element. Returns [element] on success, or the
     * existing element when the comparator reports equality.
     */
    fun add(element: T): T {
        val cmp = requireComparator()
        val index = lowerBound(element, cmp)
        if (index < items.size && cmp.compare(element, items[index]) == 0) {
            return items[index]
        }
        items.add(index, element)
        return element
    }

    /** Adds at a numeric index. Only for an unsorted tree. */
    fun addPos(index: Int, element: T): T? {
        require(!isSorted) { "addPos on a sorted tree" }
        if (index !in 0..items.size) return null
        items.add(index, element)
        return element
    }

    fun index(index: Int): T? = items.getOrNull(index)

    fun find(element: T, cmp: Comparator<T>? = null): T? =
        findRel(element, cmp, Relation.EQ)

    fun findRel(element: T?, cmp: Comparator<T>? = null, relation: Relation): T? =
        findRelPos(element, cmp, relation)?.first

    fun findPos(element: T, cmp: Comparator<T>? = null, relation: Relation = Relation.EQ): Pair<T, Int>? =
        findRelPos(element, cmp, relation)

    fun findRelPos(element: T?, cmp: Comparator<T>? = null, relation: Relation): Pair<T, Int>? {
        val compare = cmp ?: requireComparator()
        if (items.isEmpty()) return null

        return when (relation) {
            Relation.EQ -> {
                requireNotNull(element) { "EQ needs an element" }
                var low = 0
                var high = items.size - 1
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val result = compare.compare(element, items[mid])
                    when {
                        result < 0 -> high = mid - 1
                        result > 0 -> low = mid + 1
                        else -> return items[mid] to mid
                    }
                }
                null
            }

            Relation.LT -> {
                requireNotNull(element) { "LT needs an element" }
                var found: Pair<T, Int>? = null
                for (i in items.indices) {
                    if (compare.compare(element, items[i]) > 0) found = items[i] to i else break
                }
                found
            }

            Relation.LE -> {
                requireNotNull(element) { "LE needs an element" }
                var found: Pair<T, Int>? = null
                for (i in items.indices) {
                    if (compare.compare(element, items[i]) >= 0) found = items[i] to i else break
                }
                found
            }

            Relation.GT -> {
                if (element == null) return items.firstOrNull()?.let { it to 0 }
                for (i in items.indices) {
                    if (compare.compare(element, items[i]) < 0) return items[i] to i
                }
                null
            }

            Relation.GE -> {
                requireNotNull(element) { "GE needs an element" }
                for (i in items.indices) {
                    if (compare.compare(element, items[i]) <= 0) return items[i] to i
                }
                null
            }
        }
    }

    fun delete(element: T): T? {
        requireComparator()
        val found = findRelPos(element, null, Relation.EQ) ?: return null
        return items.removeAt(found.second)
    }

    fun deletePos(index: Int): T? {
        if (index !in items.indices) return null
        return items.removeAt(index)
    }

    /**
     * Splits at [index]. With `before`, the returned tree holds the
     * elements before the index and this tree keeps the rest; without
     * it, this tree keeps the earlier elements and the new tree gets
     * the rest.
     */
    fun splitPos(index: Int, before: Boolean): Tree234<T>? {
        if (index !in 0..items.size) return null
        val result = Tree234(comparator)
        if (before) {
            result.items.addAll(items.subList(0, index))
            items.subList(0, index).clear()
        } else {
            result.items.addAll(items.subList(index, items.size))
            items.subList(index, items.size).clear()
        }
        return result
    }

    /** Mirrors upstream `split234`, including its use of the relation. */
    fun split(element: T?, cmp: Comparator<T>? = null, relation: Relation): Tree234<T> {
        require(relation != Relation.EQ) { "split needs a non-EQ relation" }
        val before: Boolean
        val subRelation: Relation
        if (relation == Relation.GT || relation == Relation.GE) {
            before = true
            subRelation = if (relation == Relation.GT) Relation.LE else Relation.LT
        } else {
            before = false
            subRelation = relation
        }
        val index = findRelPos(element, cmp, subRelation)?.second ?: 0
        return splitPos(index + 1, before)!!
    }

    fun join(other: Tree234<T>): Tree234<T>? {
        if (other.count == 0) return this
        if (isSorted) {
            val first = other.items[0]
            if (findRel(first, null, Relation.GE) != null) return null
        }
        items.addAll(other.items)
        other.items.clear()
        return this
    }

    fun joinRight(other: Tree234<T>): Tree234<T>? {
        if (count == 0) return other
        if (other.isSorted) {
            val last = items[items.size - 1]
            if (other.findRel(last, null, Relation.LE) != null) return null
        }
        other.items.addAll(0, items)
        items.clear()
        return other
    }

    fun copy(copyFn: ((T) -> T)? = null): Tree234<T> {
        val result = Tree234(comparator)
        for (item in items) result.items.add(copyFn?.invoke(item) ?: item)
        return result
    }

    private fun lowerBound(element: T, cmp: Comparator<T>): Int {
        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cmp.compare(element, items[mid]) > 0) low = mid + 1 else high = mid
        }
        return low
    }

    private fun requireComparator(): Comparator<T> =
        comparator ?: error("this operation needs a sorted tree")

    companion object {
        fun <T> sorted(comparator: Comparator<T>): Tree234<T> = Tree234(comparator)

        fun <T> unsorted(): Tree234<T> = Tree234(null)
    }
}

enum class Relation { EQ, LT, LE, GT, GE }
