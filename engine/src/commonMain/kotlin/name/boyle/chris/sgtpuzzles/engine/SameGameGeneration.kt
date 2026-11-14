package name.boyle.chris.sgtpuzzles.engine

/**
 * The two generators from upstream `samegame.c`.
 *
 * [genGrid] is the guaranteed-soluble generator. The RNG call order, the
 * candidate shuffle, the doubled `up` direction, the sub-area parity
 * test, and the retry loop are copied as written. A single changed or
 * skipped draw changes every later description, so do not reorder them.
 *
 * [genGridRandom] is the legacy generator for `soluble = false`.
 */
internal object SameGameGeneration {

    /** `new_game_desc`: the grid as comma-separated decimal colours. */
    fun newDesc(width: Int, height: Int, ncols: Int, soluble: Boolean, random: RandomState): String {
        val n = width * height
        val tiles = IntArray(n)
        if (soluble) {
            genGrid(width, height, ncols, tiles, random)
        } else {
            genGridRandom(width, height, ncols, tiles, random)
        }
        return tiles.joinToString(",")
    }

    private fun genGrid(w: Int, h: Int, nc: Int, grid: IntArray, random: RandomState) {
        val wh = w * h
        val tc = nc + 1
        val list = IntArray(wh + w)
        val grid2 = IntArray(wh)
        var ok: Boolean

        do {
            grid.fill(0)

            var j = 2 + (wh % 2)
            var c = 1 + random.upto(nc.toLong()).toInt()
            if (j <= w) {
                for (i in 0 until j) grid[(h - 1) * w + i] = c
            } else {
                check(j <= h) { "initial blob does not fit" }
                for (i in 0 until j) grid[(h - 1 - i) * w] = c
            }

            while (true) {
                var n = 0

                if (grid[wh - 1] == 0) {
                    for (i in 0 until w) {
                        list[n++] = wh + i
                        if (grid[(h - 1) * w + i] == 0) break
                    }
                }

                for (i in 0 until w) {
                    if (grid[(h - 1) * w + i] == 0) break
                    if (grid[i] != 0) continue
                    var jj = h
                    while (jj-- > 0) {
                        list[n++] = jj * w + i
                        if (grid[jj * w + i] == 0) break
                    }
                }

                if (n == 0) break

                // C keeps the countdown in `n`, so the body sees n-1.
                while (n-- > 0) {
                    val dirs = IntArray(4)
                    var ndirs: Int
                    var dir: Int

                    val pick = random.upto((n + 1).toLong()).toInt()
                    val pos = list[pick]
                    list[pick] = list[n]

                    var x = pos % w
                    var y = pos / w

                    grid.copyInto(grid2, 0, 0, wh)

                    if (y == h) {
                        for (i in w - 1 downTo x + 1) {
                            for (jj in 0 until h) {
                                grid2[jj * w + i] = grid2[jj * w + (i - 1)]
                            }
                        }
                        for (jj in 0 until h) grid2[jj * w + x] = 0
                        y--
                    }

                    for (i in 0 until y) grid2[i * w + x] = grid2[(i + 1) * w + x]

                    val wrongcol = IntArray(4)
                    var nwrong = 0
                    if (x > 0) wrongcol[nwrong++] = grid2[y * w + (x - 1)]
                    if (x + 1 < w) wrongcol[nwrong++] = grid2[y * w + (x + 1)]
                    if (y > 0) wrongcol[nwrong++] = grid2[(y - 1) * w + x]
                    if (y + 1 < h) wrongcol[nwrong++] = grid2[(y + 1) * w + x]

                    // Sort the distinct positive neighbour colours. A
                    // zero is dropped because the search starts at 0.
                    var i = 0
                    var jj = 0
                    while (true) {
                        var found = -1
                        var min = 0
                        if (jj > 0) min = wrongcol[jj - 1]
                        for (k in i until nwrong) {
                            if (wrongcol[k] > min &&
                                (found == -1 || wrongcol[k] < wrongcol[found])
                            ) {
                                found = k
                            }
                        }
                        if (found >= 0) {
                            val value = wrongcol[found]
                            wrongcol[found] = wrongcol[jj]
                            wrongcol[jj++] = value
                        } else {
                            break
                        }
                        i++
                    }
                    nwrong = jj

                    if (nwrong == nc) continue

                    c = 1 + random.upto((nc - nwrong).toLong()).toInt()
                    for (k in 0 until nwrong) {
                        if (c >= wrongcol[k]) c++ else break
                    }

                    grid2[y * w + x] = tc

                    ndirs = 0
                    if (x > 0 &&
                        grid2[y * w + (x - 1)] != c &&
                        grid2[x - 1] == 0 &&
                        (y + 1 >= h || grid2[(y + 1) * w + (x - 1)] != c) &&
                        (y + 1 >= h || grid2[(y + 1) * w + (x - 1)] != 0) &&
                        (x <= 1 || grid2[y * w + (x - 2)] != c)
                    ) {
                        dirs[ndirs++] = -1
                    }
                    if (x + 1 < w &&
                        grid2[y * w + (x + 1)] != c &&
                        grid2[x + 1] == 0 &&
                        (y + 1 >= h || grid2[(y + 1) * w + (x + 1)] != c) &&
                        (y + 1 >= h || grid2[(y + 1) * w + (x + 1)] != 0) &&
                        (x + 2 >= w || grid2[y * w + (x + 2)] != c)
                    ) {
                        dirs[ndirs++] = 1
                    }
                    if (y > 0 &&
                        grid2[x] == 0 &&
                        (x <= 0 || grid2[(y - 1) * w + (x - 1)] != c) &&
                        (x + 1 >= w || grid2[(y - 1) * w + (x + 1)] != c)
                    ) {
                        // Up is added twice on purpose: a vertical domino
                        // then has about the same probability as a
                        // horizontal one.
                        dirs[ndirs++] = 0
                        dirs[ndirs++] = 0
                    }

                    if (ndirs == 0) continue

                    dir = dirs[random.upto(ndirs.toLong()).toInt()]

                    for (i in 0 until y) {
                        grid2[i * w + x + dir] = grid2[(i + 1) * w + x + dir]
                    }
                    grid2[y * w + x + dir] = tc

                    /*
                     * The remaining empty squares must split into parts
                     * of even area. An odd height with a spare column can
                     * absorb one odd part.
                     */
                    var nerrs = 0
                    var nfix = 0
                    var k = 0
                    for (col in 0 until w) {
                        if (grid2[(h - 1) * w + col] == 0) {
                            if (h % 2 != 0) nfix++
                            continue
                        }
                        var top = 0
                        while (top < h && grid2[top * w + col] == 0) top++
                        check(top < h) { "column is not empty and has no tile" }
                        if (top == 0) {
                            if (k % 2 != 0) nerrs++
                            k = 0
                        } else {
                            k += top
                        }
                    }
                    if (k % 2 != 0) nerrs++
                    if (nerrs > nfix) continue

                    /*
                     * Removing the temporary colour must reproduce the
                     * previous grid, no temporary square may touch the
                     * group colour, and the temporary squares must be one
                     * connected part.
                     */
                    var valid = true
                    var fillstart = -1
                    var ntc = 0
                    var x1 = 0
                    var x2 = 0
                    while (x2 < w) {
                        var usedcol = false
                        var y1 = h - 1
                        var y2 = h - 1
                        while (y2 >= 0) {
                            if (grid2[y2 * w + x2] == tc) {
                                ntc++
                                if (fillstart == -1) fillstart = y2 * w + x2
                                if ((y2 + 1 < h && grid2[(y2 + 1) * w + x2] == c) ||
                                    (y2 - 1 >= 0 && grid2[(y2 - 1) * w + x2] == c) ||
                                    (x2 + 1 < w && grid2[y2 * w + x2 + 1] == c) ||
                                    (x2 - 1 >= 0 && grid2[y2 * w + x2 - 1] == c)
                                ) {
                                    valid = false
                                }
                                y2--
                                continue
                            }
                            if (grid2[y2 * w + x2] == 0) break
                            usedcol = true
                            if (grid2[y2 * w + x2] != grid[y1 * w + x1]) valid = false
                            y1--
                            y2--
                        }

                        if (usedcol) {
                            while (y1 >= 0) {
                                if (grid[y1 * w + x1] != 0) valid = false
                                y1--
                            }
                        }

                        if (!valid) break
                        if (usedcol) x1++
                        x2++
                    }

                    if (!valid) {
                        // Both builds keep assertions, so this is an
                        // upstream bug, not a placement to retry.
                        error("samegame generator made an invalid placement")
                    }

                    var head = 0
                    var tail = 0
                    check(fillstart >= 0)
                    list[head++] = fillstart
                    while (tail < head) {
                        k = list[tail]
                        val cx = k % w
                        val cy = k / w
                        tail++

                        check(grid2[k] == tc)
                        grid2[k] = c

                        if (cx > 0 && grid2[k - 1] == tc) list[head++] = k - 1
                        if (cx + 1 < w && grid2[k + 1] == tc) list[head++] = k + 1
                        if (cy > 0 && grid2[k - w] == tc) list[head++] = k - w
                        if (cy + 1 < h && grid2[k + w] == tc) list[head++] = k + w
                    }
                    check(tail == ntc) { "the new group is not connected" }

                    grid2.copyInto(grid, 0, 0, wh)
                    break
                }

                if (n < 0) break
            }

            ok = true
            for (i in 0 until wh) {
                if (grid[i] == 0) {
                    ok = false
                    break
                }
            }
        } while (!ok)
    }

    private fun genGridRandom(w: Int, h: Int, nc: Int, grid: IntArray, random: RandomState) {
        val n = w * h
        grid.fill(0)

        for (c in 1..nc) {
            for (j in 0 until 2) {
                var i: Int
                do {
                    i = random.upto(n.toLong()).toInt()
                } while (grid[i] != 0)
                grid[i] = c
            }
        }

        for (i in 0 until n) {
            if (grid[i] == 0) grid[i] = random.upto(nc.toLong()).toInt() + 1
        }
    }
}
