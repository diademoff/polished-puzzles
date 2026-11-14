package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `matching.c`: Hopcroft-Karp maximum matching in a
 * bipartite graph.
 *
 * The algorithm mutates the adjacency lists when a generator is
 * supplied, and the resulting matching is observable. The port keeps
 * the same traversal and the same random choices, so a seeded run
 * produces the same pairing as C.
 */
object Matching {

    data class Result(
        val count: Int,
        val leftToRight: IntArray,
        val rightToLeft: IntArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Result &&
                count == other.count &&
                leftToRight.contentEquals(other.leftToRight) &&
                rightToLeft.contentEquals(other.rightToLeft)

        override fun hashCode(): Int =
            31 * (31 * count + leftToRight.contentHashCode()) + rightToLeft.contentHashCode()
    }

    /**
     * Finds a maximum matching. [adjacency] has one array of right
     * vertices per left vertex and is permuted in place when [random]
     * is not null, exactly as upstream does.
     */
    fun match(
        leftCount: Int,
        rightCount: Int,
        adjacency: Array<IntArray>,
        random: RandomState? = null,
    ): Result {
        require(leftCount >= 0 && rightCount >= 0)
        require(adjacency.size == leftCount)

        val leftToRight = IntArray(leftCount) { -1 }
        val rightToLeft = IntArray(rightCount) { -1 }
        val leftLayer = IntArray(leftCount)
        val rightLayer = IntArray(rightCount)
        val leftQueue = IntArray(leftCount)
        val rightQueue = IntArray(rightCount)
        val minCount = minOf(leftCount, rightCount)
        val augmentingPath = IntArray(2 * minCount)
        val dfsState = IntArray(minCount + 1)
        val leftOrder = IntArray(leftCount)

        while (true) {
            leftLayer.fill(-1)
            rightLayer.fill(-1)

            var leftQueueSize = 0
            for (left in 0 until leftCount) {
                if (leftToRight[left] == -1) {
                    leftLayer[left] = 0
                    leftQueue[leftQueueSize++] = left
                }
            }

            var layer = 0
            var targetLayer: Int
            while (true) {
                var foundFreeRight = false
                var rightQueueSize = 0

                for (i in 0 until leftQueueSize) {
                    val left = leftQueue[i]
                    for (right in adjacency[left]) {
                        if (right != leftToRight[left] && rightLayer[right] == -1) {
                            rightLayer[right] = layer + 1
                            rightQueue[rightQueueSize++] = right
                            if (rightToLeft[right] == -1) foundFreeRight = true
                        }
                    }
                }
                layer++

                if (foundFreeRight) break
                if (rightQueueSize == 0) return result(leftToRight, rightToLeft)

                leftQueueSize = 0
                for (j in 0 until rightQueueSize) {
                    val right = rightQueue[j]
                    val left = rightToLeft[right]
                    if (left != -1 && leftLayer[left] == -1) {
                        leftLayer[left] = layer + 1
                        leftQueue[leftQueueSize++] = left
                    }
                }
                layer++
                if (leftQueueSize == 0) return result(leftToRight, rightToLeft)
            }
            targetLayer = layer

            for (right in 0 until rightCount) {
                if (rightLayer[right] == targetLayer && rightToLeft[right] != -1) {
                    rightLayer[right] = -1
                }
            }

            for (left in 0 until leftCount) leftOrder[left] = left
            if (random != null) shuffle(leftOrder, random)

            dfsState[0] = 0
            var i = 0
            while (true) {
                var left: Int
                if (i == 0) {
                    if (dfsState[0] == leftCount) break
                    left = leftOrder[dfsState[0]++]
                    if (leftLayer[left] != 0) continue
                } else {
                    left = augmentingPath[2 * i - 2]
                    val j = dfsState[i]++
                    if (j == adjacency[left].size) {
                        i--
                        continue
                    }
                    if (random != null && adjacency[left].size - j > 1) {
                        val which = j + random.upto((adjacency[left].size - j).toLong()).toInt()
                        val tmp = adjacency[left][which]
                        adjacency[left][which] = adjacency[left][j]
                        adjacency[left][j] = tmp
                    }
                    val right = adjacency[left][j]
                    if (rightLayer[right] != 2 * i - 1) continue

                    augmentingPath[2 * i - 1] = right
                    rightLayer[right] = -1

                    if (2 * i - 1 == targetLayer) {
                        var k = 0
                        while (k < 2 * i) {
                            leftToRight[augmentingPath[k]] = augmentingPath[k + 1]
                            rightToLeft[augmentingPath[k + 1]] = augmentingPath[k]
                            k += 2
                        }
                        i = 0
                        continue
                    }

                    left = rightToLeft[right]
                    if (leftLayer[left] != 2 * i) continue
                }

                augmentingPath[2 * i] = left
                leftLayer[left] = -1
                i++
                dfsState[i] = 0
            }
        }
    }

    private fun result(leftToRight: IntArray, rightToLeft: IntArray): Result {
        var count = 0
        for (value in leftToRight) if (value != -1) count++
        return Result(count, leftToRight.copyOf(), rightToLeft.copyOf())
    }

    /** Upstream `shuffle` with a [RandomState]. */
    private fun shuffle(array: IntArray, random: RandomState) {
        var i = array.size
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val tmp = array[i]
                array[i] = array[j]
                array[j] = tmp
            }
        }
    }
}
