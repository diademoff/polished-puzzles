package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `loopgen.c`: a generator of a single closed loop
 * through a [Grid].
 *
 * The loop is described by a colouring of the faces. White faces are
 * the inside of the loop, black faces are the outside, and the loop
 * itself runs along the boundary between the two. The infinite face is
 * always black. The algorithm starts with every finite face grey,
 * colours one random face white, and then grows both regions until no
 * grey face remains. A final pass flips faces to remove large clumps.
 *
 * The result must match the C code exactly, including the RNG draws and
 * the order of the candidate lists, because a loopy or pearl game id
 * encodes the generated board. `LoopgenParityTest` compares the board
 * and the encoded generator state with the oracle.
 */

/**
 * The colour of a face. The declaration order matches upstream
 * `enum face_colour`, so [ordinal] is the value stored in a board.
 */
enum class FaceColour {
    WHITE,
    GREY,
    BLACK,
}

/**
 * A user-provided rating of a half-finished board.
 *
 * [face] is the face that changed since the last call, so a caller can
 * work incrementally. The board may still contain [FaceColour.GREY].
 * The function must not modify the board and must return the same score
 * for the same board, because ties are broken by candidate list order.
 */
typealias LoopBias = (board: IntArray, face: Int) -> Int

/** One candidate face with its cached scores and its sort tie-breaker. */
private class FaceScore(
    val index: Int,
    var whiteScore: Int,
    var blackScore: Int,
    var random: Long,
)

/**
 * `generate_loop`: fills the first `grid.faces.size` entries of [board]
 * with [FaceColour.WHITE] or [FaceColour.BLACK]. [board] must be at
 * least that long; any extra entries are left alone.
 *
 * [bias] is called for every candidate face, as C does: once with the
 * candidate set to the proposed colour, once after it is put back to
 * grey, and once for the face that is finally coloured. A null bias
 * picks the first candidate in sorted order.
 */
fun generateLoop(
    grid: Grid,
    board: IntArray,
    random: RandomState,
    bias: LoopBias? = null,
) {
    val numFaces = grid.faces.size
    require(board.size >= numFaces) {
        "board is ${board.size} entries, but the grid has $numFaces faces"
    }
    // A one-face grid makes upstream's can_colour_face loop forever: at
    // every dot the only other face is the infinite face, so the walk
    // can never return to its starting pair. The consumers reject such
    // grids before they call this function. The guard is D-020 in
    // `docs/DEVIATIONS.md`.
    require(numFaces >= 2) {
        "the grid has $numFaces faces; loop generation needs at least two"
    }

    board.fill(FaceColour.GREY.ordinal, 0, numFaces)

    // One random tie-breaker per face, drawn in index order. The scores
    // start at zero and are filled in when a face enters a candidate
    // list.
    val faceScores = Array(numFaces) { FaceScore(it, 0, 0, 0L) }
    for (score in faceScores) score.random = random.bits(31)

    // Colour a random finite face white. The infinite face is
    // implicitly black, so the two regions can grow from here.
    val seed = random.upto(numFaces.toLong()).toInt()
    board[seed] = FaceColour.WHITE.ordinal

    val lightable: Tree234<FaceScore> =
        Tree234.sorted(Comparator { a, b -> compareScores(a, b, true) })
    val darkable: Tree234<FaceScore> =
        Tree234.sorted(Comparator { a, b -> compareScores(a, b, false) })

    // The infinite face has no entry in g->faces, so every face is
    // tested directly rather than by walking the infinite face's
    // neighbours.
    for (i in 0 until numFaces) {
        if (board[i] != FaceColour.GREY.ordinal) continue
        val score = faceScores[i]
        if (canColourFace(grid, board, i, FaceColour.BLACK)) {
            score.blackScore = faceScore(board, grid.faces[i], FaceColour.BLACK)
            darkable.add(score)
        }
        if (canColourFace(grid, board, i, FaceColour.WHITE)) {
            score.whiteScore = faceScore(board, grid.faces[i], FaceColour.WHITE)
            lightable.add(score)
        }
    }

    while (true) {
        val lightableCount = lightable.count
        val darkableCount = darkable.count
        if (lightableCount == 0 && darkableCount == 0) break
        check(lightableCount != 0 && darkableCount != 0) {
            "loopgen cannot grow one of the two regions"
        }

        val colour = if (random.upto(2) != 0L) FaceColour.WHITE else FaceColour.BLACK
        val toPick = if (colour == FaceColour.WHITE) lightable else darkable
        val chosen: FaceScore
        if (bias != null) {
            // Walk the candidates in sorted order and keep the best
            // score. A tie keeps the earlier candidate, which is why
            // the comparison is strict.
            var best: FaceScore? = null
            var bestScore = 0
            var j = 0
            while (true) {
                val candidate = toPick.index(j) ?: break
                j++
                val k = candidate.index
                check(board[k] == FaceColour.GREY.ordinal)
                board[k] = colour.ordinal
                val score = bias(board, k)
                board[k] = FaceColour.GREY.ordinal
                bias(board, k) // let the bias know the face was put back
                if (best == null || score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            chosen = checkNotNull(best) { "loopgen has no candidate to pick" }
        } else {
            chosen = checkNotNull(toPick.index(0)) { "loopgen has no candidate to pick" }
        }

        val i = chosen.index
        check(board[i] == FaceColour.GREY.ordinal)
        board[i] = colour.ordinal
        bias?.invoke(board, i)

        // Both lists hold only grey faces, so the new face leaves both.
        lightable.delete(chosen)
        darkable.delete(chosen)

        // The new colour affects every face that touches this one at an
        // edge or at a corner. Re-score each grey face around every dot
        // of the coloured face.
        val curFace = grid.faces[i]
        for (d in 0 until curFace.order) {
            val dot = curFace.dot(d)
            for (e in 0 until dot.order) {
                val face = dot.faces[e] ?: continue
                if (face === curFace) continue
                if (faceColour(board, face) != FaceColour.GREY) continue

                val fi = face.index
                val score = faceScores[fi]

                // Remove and re-add even when the face is still
                // colourable: its score may have changed, and the list
                // order depends on the score.
                lightable.delete(score)
                if (canColourFace(grid, board, fi, FaceColour.WHITE)) {
                    score.whiteScore = faceScore(board, face, FaceColour.WHITE)
                    lightable.add(score)
                }
                darkable.delete(score)
                if (canColourFace(grid, board, fi, FaceColour.BLACK)) {
                    score.blackScore = faceScore(board, face, FaceColour.BLACK)
                    darkable.add(score)
                }
            }
        }
    }

    // The growth pass can leave large clumps of one colour. Walk a
    // shuffled face list and flip any face that has exactly one
    // neighbour of the opposite colour, until a full pass flips
    // nothing. Then make one last pass of random legal flips.
    val faceList = IntArray(numFaces) { it }
    Divvy.shuffle(faceList, numFaces, random)

    var doRandomPass = false
    while (true) {
        var flipped = false

        for (position in 0 until numFaces) {
            val j = faceList[position]
            val opposite =
                if (board[j] == FaceColour.WHITE.ordinal) FaceColour.BLACK else FaceColour.WHITE
            if (canColourFace(grid, board, j, opposite)) {
                if (doRandomPass) {
                    if (random.upto(10) == 0L) board[j] = opposite.ordinal
                } else {
                    if (faceNumNeighbours(board, grid.faces[j], opposite) == 1) {
                        board[j] = opposite.ordinal
                        flipped = true
                    }
                }
            }
        }

        if (doRandomPass) break
        if (!flipped) doRandomPass = true
    }
}

/** The face colour used by `FACE_COLOUR`: the infinite face is black. */
private fun faceColour(board: IntArray, face: GridFace?): FaceColour =
    if (face == null) FaceColour.BLACK else FaceColour.entries[board[face.index]]

/**
 * The score of a face for a proposed colour: the negative of the number
 * of neighbours that already have that colour. A higher score means
 * fewer same-coloured neighbours, which keeps the boundary twisty.
 */
private fun faceScore(board: IntArray, face: GridFace, colour: FaceColour): Int =
    -faceNumNeighbours(board, face, colour)

/** Counts the neighbours of [face] that have [colour]. */
private fun faceNumNeighbours(board: IntArray, face: GridFace, colour: FaceColour): Int {
    var count = 0
    for (i in 0 until face.order) {
        val edge = face.edge(i)
        val other = if (edge.face1 === face) edge.face2 else edge.face1
        if (faceColour(board, other) == colour) count++
    }
    return count
}

/**
 * `can_colour_face`: whether [colour] can be given to [faceIndex]
 * without breaking the topology of the two regions.
 *
 * The face must have a neighbour of the same colour. Then walk the
 * faces that touch it, in order, around its dots. Colouring is legal
 * only when the walk crosses between [colour] and the other colour
 * exactly twice. Zero transitions would start a new island, and four or
 * more would close a loop or meet the region at a corner.
 */
private fun canColourFace(
    grid: Grid,
    board: IntArray,
    faceIndex: Int,
    colour: FaceColour,
): Boolean {
    val testFace = grid.faces[faceIndex]
    check(board[faceIndex] != colour.ordinal)

    var sameNeighbour = false
    for (i in 0 until testFace.order) {
        val edge = testFace.edge(i)
        val other = if (edge.face1 === testFace) edge.face2 else edge.face1
        if (faceColour(board, other) == colour) {
            sameNeighbour = true
            break
        }
    }
    if (!sameNeighbour) return false

    // i is a dot of the test face, j a face around that dot. The walk
    // starts at the first face that is not the test face itself.
    var i = 0
    var j = 0
    var currentFace = testFace.dot(0).faces[0]
    if (currentFace === testFace) {
        j = 1
        currentFace = testFace.dot(0).faces[1]
    }

    var transitions = 0
    var currentState = faceColour(board, currentFace) == colour
    var startingDot: GridDot? = null
    var startingFace: GridFace? = null

    while (true) {
        // Advance to the next face. Several steps may be needed when
        // the test face touches itself at a dot, which happens most
        // often with the infinite face.
        while (true) {
            j++
            val dot = testFace.dot(i)
            if (j == dot.order) j = 0

            if (dot.faces[j] === testFace) {
                // Move to the next dot of the test face, find the
                // current face around it, and try again from there.
                i++
                if (i == testFace.order) i = 0
                val nextDot = testFace.dot(i)
                j = 0
                while (j < nextDot.order && nextDot.faces[j] !== currentFace) j++
                check(j != nextDot.order) { "grid is not consistent around a dot" }
            } else {
                break
            }
        }

        val dot = testFace.dot(i)
        currentFace = dot.faces[j]
        val state = faceColour(board, currentFace) == colour
        if (startingDot == null) {
            // Canonicalise the start after one step. The same (i, j)
            // pair can occur more than once around a face.
            startingDot = dot
            startingFace = currentFace
            currentState = state
        } else {
            if (state != currentState) {
                transitions++
                currentState = state
                if (transitions > 2) break
            }
            if (dot === startingDot && currentFace === startingFace) break
        }
    }

    return transitions == 2
}

/**
 * The candidate order: the larger score first, then the smaller random
 * value, then the earlier face. The last key is the pointer difference
 * `f1 - f2` in C, which is the index order because `face_scores` is one
 * contiguous array.
 */
private fun compareScores(a: FaceScore, b: FaceScore, white: Boolean): Int {
    val aScore = if (white) a.whiteScore else a.blackScore
    val bScore = if (white) b.whiteScore else b.blackScore
    if (aScore != bScore) return bScore - aScore
    if (a.random < b.random) return -1
    if (a.random > b.random) return 1
    return a.index - b.index
}
