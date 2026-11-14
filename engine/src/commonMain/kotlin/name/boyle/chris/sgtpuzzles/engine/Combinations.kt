package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `combi.c`: combinations in lexicographic order.
 *
 * The first call to [next] returns `[0, 1, ..., r-1]`; the last one
 * returns the highest combination. The order is part of the contract
 * because games use it for deterministic search.
 */
class Combinations(val r: Int, val n: Int) {

    init {
        require(n >= 1) { "combi needs n >= 1" }
        require(r in 1..n) { "combi needs 1 <= r <= n" }
    }

    /** `C(n, r)`, as upstream computes it. */
    val total: Int = run {
        var numerator = 1L
        for (i in r + 1..n) numerator *= i
        var denominator = 1L
        for (i in 1..n - r) denominator *= i
        val value = numerator / denominator
        require(value in 1..Int.MAX_VALUE.toLong()) { "combi is too large: C($n, $r)" }
        value.toInt()
    }

    private val a = IntArray(r)
    private var nleft = total

    init {
        reset()
    }

    fun reset() {
        nleft = total
        for (i in 0 until r) a[i] = i
    }

    /** Returns the next combination, or `null` after [total] calls. */
    fun next(): List<Int>? {
        if (nleft != total) {
            if (nleft <= 0) return null

            var i = r - 1
            while (a[i] == n - r + i) i--
            a[i] += 1
            for (j in i + 1 until r) a[j] = a[i] + j - i
        }
        nleft--
        return a.toList()
    }
}
