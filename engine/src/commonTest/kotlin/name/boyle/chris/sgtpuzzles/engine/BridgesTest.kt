package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: bridges.
 *
 * Kotlin-only pins for the parameter quirks, the description decoder,
 * the state layout, the solver's error strings, the move grammar, the
 * key labels, and the drawing cache.
 */
class BridgesTest {

    private companion object {
        const val TILE = 24
        const val SEED = "12345"

        const val GOLDEN_DESC = "3e2g6a3c2i1b3h4d4a"
        const val GOLDEN_AUX =
            "S;M5,6;L5,4,5,6,2;M5,4;L0,6,5,6,2;M0,6;L0,2,2,2,2;" +
                "L0,2,0,6,2;M0,2;L2,4,5,4,1;M2,4;L0,0,6,0,1;L0,0,0,2,2;" +
                "M0,0;L2,2,6,2,1;M2,2;M6,2;L6,0,6,2,1;M6,0"

        const val HINT =
            "S;L0,0,6,0,1;L0,0,0,2,2;M0,0;L6,0,6,2,1;M6,0;" +
                "L0,2,2,2,2;L0,2,0,6,2;M0,2;L2,2,6,2,1;M2,2;M6,2;" +
                "L2,4,5,4,1;M2,4;L5,4,5,6,2;M5,4;L0,6,5,6,2;M0,6;M5,6"
    }

    private fun params(text: String): Bridges.Params =
        Bridges.decodeParams(text) as Bridges.Params

    private fun stateOf(paramsText: String, desc: String): Bridges.State =
        Bridges.newState(params(paramsText), desc) as Bridges.State

    private fun descOf(paramsText: String, seed: String): Description =
        Bridges.newDesc(params(paramsText), RandomState.fromSeed(seed))

    private fun ui(state: Bridges.State): Bridges.Ui =
        Bridges.newUi(state) as Bridges.Ui

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `defaults and presets match the C tables`() {
        assertEquals(Bridges.Params(7, 7, 30, 10, 2, true, 0), Bridges.defaultParams())
        val presets = Bridges.presets()
        assertEquals(9, presets.size)
        assertEquals(
            listOf(
                "7x7i30e10m2d0", "7x7i30e10m2d1", "7x7i30e10m2d2",
                "10x10i30e10m2d0", "10x10i30e10m2d1", "10x10i30e10m2d2",
                "15x15i30e10m2d0", "15x15i30e10m2d1", "15x15i30e10m2d2",
            ),
            presets.map { it.params },
        )
        assertEquals(
            listOf(
                "7x7 Easy", "7x7 Medium", "7x7 Hard",
                "10x10 Easy", "10x10 Medium", "10x10 Hard",
                "15x15 Easy", "15x15 Medium", "15x15 Hard",
            ),
            presets.map { it.title },
        )
        assertEquals(24, Bridges.preferredTileSize)
        assertFalse(Bridges.wantsStatusBar)
        assertTrue(Bridges.canSolve)
        assertTrue(Bridges.canFormatAsText)
    }

    @Test
    fun `decode follows atoi and the digit loop`() {
        assertEquals(params("7x7i30e10m2d0"), Bridges.decodeParams("7"))
        assertEquals(params("7x7i30e10m2d0"), Bridges.decodeParams("7x7"))
        assertEquals(params("7x7i30e10m3d0"), Bridges.decodeParams("7x7m3"))
        assertEquals(params("7x7i30e10m2d0").copy(allowloops = false),
            Bridges.decodeParams("7x7L"))
        // A leading space or sign is not consumed, so the rest is ignored.
        assertEquals(params("7x7i30e10m2d0"), Bridges.decodeParams(" 7x7m1"))
        assertEquals(params("7x7i30e10m2d0"), Bridges.decodeParams("+7x7m1"))
        // `0x10` reads a zero width and then the `x`, so height 10.
        assertEquals(params("0x10"), Bridges.decodeParams("0x10"))
        // A letter out of order ends decoding silently.
        assertEquals(params("7x7i30e10m2d0"), Bridges.decodeParams("7X7"))
        assertEquals(params("7x7i30e10m2d0"), Bridges.decodeParams("7x7junk"))
        // A missing difficulty keeps the default zero.
        assertEquals(0, (Bridges.decodeParams("7x7i30e10m2") as Bridges.Params).difficulty)
        // C `atoi` overflow is undefined; the port saturates.
        assertEquals(
            Bridges.Params(Int.MAX_VALUE, 7, 30, 10, 2, true, 0),
            Bridges.decodeParams("99999999999x7"),
        )
    }

    @Test
    fun `encode has the full and short forms`() {
        val p = params("10x10i25e30m3d2")
        assertEquals("10x10m3", Bridges.encodeParams(p, false))
        assertEquals("10x10i25e30m3d2", Bridges.encodeParams(p, true))
        val noLoops = p.copy(allowloops = false)
        assertEquals("10x10m3L", Bridges.encodeParams(noLoops, false))
        assertEquals("10x10i25e30m3Ld2", Bridges.encodeParams(noLoops, true))
        assertEquals("7x7m2", Bridges.encodeParams(Bridges.defaultParams(), false))
        assertEquals("7x7i30e10m2d0", Bridges.encodeParams(Bridges.defaultParams(), true))
    }

    @Test
    fun `validation follows the C order and messages`() {
        assertEquals(
            "Width and height must be at least 3",
            Bridges.validateParams(params("2x7"), true),
        )
        assertEquals(
            "Width and height must be at least 3",
            Bridges.validateParams(params("7x2"), true),
        )
        assertEquals(
            "Too many bridges.",
            Bridges.validateParams(params("7x7m0"), true),
        )
        assertEquals(
            "Too many bridges.",
            Bridges.validateParams(params("7x7m5"), true),
        )
        assertEquals(
            "%age of island squares must be between 1% and 30%",
            Bridges.validateParams(params("7x7i0"), true),
        )
        assertEquals(
            "%age of island squares must be between 1% and 30%",
            Bridges.validateParams(params("7x7i31"), true),
        )
        assertEquals(
            "Expansion factor must be between 0 and 100",
            Bridges.validateParams(params("7x7e101"), true),
        )
        // Short validation skips the two percentage checks.
        assertEquals(null, Bridges.validateParams(params("7x7i0e101"), false))
        // Two invalid fields at once: the C order picks the message.
        assertEquals(
            "Width times height must not be unreasonably large",
            Bridges.validateParams(
                Bridges.Params(46341, 46341, 0, 101, 0, true, 0),
                true,
            ),
        )
        assertEquals(
            "Too many bridges.",
            Bridges.validateParams(
                Bridges.Params(7, 7, 0, 101, 0, true, 0),
                true,
            ),
        )
        assertEquals(
            "%age of island squares must be between 1% and 30%",
            Bridges.validateParams(
                Bridges.Params(7, 7, 0, 101, 2, true, 0),
                true,
            ),
        )
        // The C boundary: 46340 * 46340 fits in INT_MAX.
        assertEquals(
            null,
            Bridges.validateParams(Bridges.Params(46340, 46340, 30, 10, 2, true, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Bridges.validateParams(Bridges.Params(46341, 46341, 30, 10, 2, true, 0), true),
        )
        // `difficulty` is not validated.
        assertEquals(null, Bridges.validateParams(params("7x7d9"), true))
        assertEquals(null, Bridges.validateParams(params("7x7d-1"), true))
    }

    // ------------------------------------------------------------------
    // Description and state.
    // ------------------------------------------------------------------

    @Test
    fun `validateDesc uses the exact messages in order`() {
        val p = params("3x3")
        fun check(desc: String) = Bridges.validateDesc(p, desc)

        // Two islands side by side are joined.
        assertEquals("Game description contains joined islands", check("11g"))
        // An island directly above another is joined.
        assertEquals("Game description contains joined islands", check("1b1f"))
        // A run that fills the board exactly is valid.
        assertEquals(null, check("4a1f"))
        // A run that overshoots is too long.
        assertEquals("Game description longer than expected", check("j"))
        // A short description.
        assertEquals("Game description shorter than expected", check("a1a"))
        assertEquals("Game description contains unexpected character", check("a0a1a"))
        assertEquals("Game description longer than expected", check("ii"))
        // `y` and `z` are valid runs of 25 and 26 cells, so they
        // overshoot the board instead of failing as unknown characters.
        assertEquals("Game description longer than expected", check("y"))
        assertEquals("Game description longer than expected", check("z"))
        assertEquals("Game description has too few islands", check("i"))
        // Diagonal islands are allowed.
        assertEquals(null, check("1ba1ab1"))
    }

    @Test
    fun `newState decodes row-major islands and the maxima`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        assertEquals(7, s.width)
        assertEquals(7, s.height)
        assertEquals(2, s.maxb)
        assertTrue(s.allowloops)
        assertEquals(9, s.islands.size)
        assertEquals(listOf(0 to 0, 6 to 0, 0 to 2, 2 to 2, 6 to 2,
            2 to 4, 5 to 4, 0 to 6, 5 to 6), s.islands.map { it.x to it.y })
        assertEquals(listOf(3, 2, 6, 3, 2, 1, 3, 4, 4), s.islands.map { it.count })
        // The four directions are left, right, up, down.
        assertEquals(listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1),
            s.islands[3].points.map { it.dx to it.dy })
        assertEquals(listOf(2, 4, 0, 2), s.islands[3].points.map { it.off })
        assertTrue(s.maxv.all { it == 2 })
        assertTrue(s.maxh.all { it == 2 })
        assertTrue(s.possv.isNotEmpty())
        assertFalse(s.completed)
        assertFalse(s.solved)
        assertEquals(GOLDEN_DESC, Bridges.encodeGame(s))
        assertEquals(
            """
            3.....2
            .......
            6.3...2
            .......
            ..1..3.
            .......
            4....4.
            """.trimIndent() + "\n",
            Bridges.textFormat(s),
        )
    }


    @Test
    fun `the findloop neighbour order follows the C callback`() {
        // A centre island at (2,2) with one neighbour in each direction
        // and one bridge each. `bridges_neighbour` walks the island
        // directions in `points` order (left, right, up, down) and names
        // the adjacent line cell, not the island at the far end.
        val s = stateOf("5x5i30e10m2d0", "b4be1a4a1eb1b")
        val joined = Bridges.executeMove(
            s,
            "L2,2,0,2,1;L2,2,4,2,1;L2,2,2,0,1;L2,2,2,4,1",
        ) as Bridges.State
        assertEquals(listOf(11, 13, 7, 17), Bridges.neighbourOrder(joined, 2, 2))
        // A line cell returns its two ends in `(x1,y1)` then `(x2,y2)`
        // order, that is left then right, or up then down.
        assertEquals(listOf(10, 12), Bridges.neighbourOrder(joined, 1, 2))
        assertEquals(listOf(2, 12), Bridges.neighbourOrder(joined, 2, 1))
        // A vertex with no bridge has no neighbours.
        assertEquals(emptyList(), Bridges.neighbourOrder(joined, 0, 0))
        // `findloop_is_loop_edge` is true exactly for the edges that are
        // not bridges, so the warning marks do not depend on this order.
        // The callback order still matches C, and only this check sees
        // a reversal.
    }

    @Test(timeout = 120_000)
    fun `retry fixtures pin the attempt counts`() {
        val params = params("7x7i30e10m2d1")

        // The first attempts are rejected by the "not solvable" gate.
        val notSolvable = Bridges.newDescWithStats(params, RandomState.fromSeed("4"))
        assertEquals(3, notSolvable.stats.attempts)
        assertEquals(2, notSolvable.stats.notSolvable)
        assertEquals(0, notSolvable.stats.tooEasy)
        assertEquals(0, notSolvable.stats.sides)
        assertEquals(0, notSolvable.stats.oneIsland)
        assertEquals(
            "a1a3b2g1b2a1b4d43g4a2c4a4c3",
            notSolvable.description.text,
        )

        // The first attempts are rejected by the "too easy" gate and by
        // the four-side coverage check.
        val tooEasy = Bridges.newDescWithStats(params, RandomState.fromSeed("31"))
        assertEquals(6, tooEasy.stats.attempts)
        assertEquals(2, tooEasy.stats.tooEasy)
        assertEquals(0, tooEasy.stats.notSolvable)
        assertEquals(3, tooEasy.stats.sides)
        assertEquals(0, tooEasy.stats.oneIsland)
        assertEquals("3b3b1a1t3a3j2e2", tooEasy.description.text)
    }

    @Test(timeout = 120_000)
    fun `the generator matches the golden description and aux`() {
        val generated = descOf("7x7i30e10m2d0", SEED)
        assertEquals(GOLDEN_DESC, generated.text)
        assertEquals(GOLDEN_AUX, generated.auxInfo)
    }

    @Test(timeout = 120_000)
    fun `the generated aux solves the initial board`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val solved = Bridges.executeMove(s, GOLDEN_AUX) as Bridges.State
        assertTrue(solved.completed)
        assertTrue(solved.solved)
        assertEquals(1, Bridges.status(solved))
        assertTrue(solved.islands.all { island ->
            solved.grid[island.y * solved.width + island.x] and 0x18 != 0
        })
    }

    @Test(timeout = 120_000)
    fun `the two C error strings are defined port results`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        assertEquals(
            "Generated aux string is not a valid move (!).",
            Bridges.solveAttempt(s, "Q").error,
        )
        assertEquals(
            "Generated aux string is not a valid move (!).",
            Bridges.solveAttempt(s, "").error,
        )
        assertNull(Bridges.solveAttempt(s, "Q").move)

        val unsolvable = stateOf("3x3m4", "4a1f")
        assertEquals(
            "Game does not have a (non-recursive) solution.",
            Bridges.solveAttempt(unsolvable, null).error,
        )
        assertNull(Bridges.solveAttempt(unsolvable, null).move)
    }

    @Test(timeout = 120_000)
    fun `the solver maxima survive the restart`() {
        // Stage 3 lowers `maxv`/`maxh`, and the lowering must reach the
        // next `solve_sub` pass. A reset per restart changes this answer:
        // the extra `N3,0,5,0` is the maximum that a reset loses. The
        // timeout turns a solver that loops instead of finishing into a
        // failure with a clear message.
        val s = stateOf("6x6i30e10m2d2", "a2a2a1f2f1c2f4b4a2")
        val attempt = Bridges.solveAttempt(s, null)
        assertNull(attempt.error)
        assertEquals(
            "S;L1,0,3,0,1;L1,0,1,3,1;M1,0;L3,0,3,5,1;M3,0;L5,0,5,3,1;M5,0;" +
                "L0,2,0,5,2;M0,2;N1,3,5,3;M1,3;L5,3,5,5,1;M5,3;L0,5,3,5,2;M0,5;" +
                "L3,5,5,5,1;M3,5;M5,5",
            attempt.move,
        )
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar accepts and rejects the C forms`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        for (move in listOf("S", "S;", "L0,0,6,0,1", "N0,0,6,0", "M0,0",
            "L 0,0,6,0,1", "L+0,0,6,0,1", "M 0,0", "S;M0,0;")) {
            assertNotNull(Bridges.executeMove(s, move), move)
        }
        for (move in listOf("", ";", "L0,0,6,0", "L0,0,6,0,-1", "L0,0,6,0,3",
            "L0,0,6,6,1", "L0,0,1,0,1", "L0,0,6,0,1x", "L0,0,6,0,1,2",
            "M0,1", "M9,9", "M-1,0", "M99999999999999999999,0",
            "M-99999999999999999999,0", "X", "SS")) {
            assertNull(Bridges.executeMove(s, move), move)
        }
    }

    @Test
    fun `L N and M have the C semantics`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val one = Bridges.executeMove(s, "L0,0,6,0,1") as Bridges.State
        assertEquals(1, one.lines[1])
        assertEquals(0x4, one.grid[1] and 0x4)
        val two = Bridges.executeMove(one, "L0,0,6,0,2") as Bridges.State
        assertEquals(2, two.lines[1])
        val zero = Bridges.executeMove(two, "L0,0,6,0,0") as Bridges.State
        assertEquals(0, zero.grid[1] and 0x4)

        val noLine = Bridges.executeMove(s, "N0,0,6,0") as Bridges.State
        assertEquals(0x40, noLine.grid[1] and 0x40)
        val noLineTwice = Bridges.executeMove(noLine, "N0,0,6,0") as Bridges.State
        assertEquals(0, noLineTwice.grid[1] and 0x40)

        // A bridge and a no-line can coexist, because `execute_move`
        // does not forbid it, as in C.
        val both = Bridges.executeMove(one, "N0,0,6,0") as Bridges.State
        assertEquals(0x44, both.grid[1] and 0x44)

        val marked = Bridges.executeMove(s, "M0,0") as Bridges.State
        assertEquals(0x18, marked.grid[0] and 0x18)
        // The right neighbour marks the cells between with MARKH.
        assertEquals(0x10, marked.grid[1] and 0x10)
        // The down neighbour at (0,2) marks the cell between with MARKV.
        assertEquals(0x8, marked.grid[7] and 0x8)
        val markedTwice = Bridges.executeMove(marked, "M0,0") as Bridges.State
        assertEquals(0, markedTwice.grid[0] and 0x18)
        assertEquals(0, markedTwice.grid[1] and 0x10)
    }

    @Test
    fun `completion is sticky and S only sets solved`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val solved = Bridges.executeMove(s, GOLDEN_AUX) as Bridges.State
        assertTrue(solved.completed)
        assertTrue(solved.solved)
        // Breaking a completed board keeps `completed`.
        val broken = Bridges.executeMove(solved, "M0,0") as Bridges.State
        assertTrue(broken.completed)

        val marked = Bridges.executeMove(s, "S") as Bridges.State
        assertTrue(marked.solved)
        assertFalse(marked.completed)
        assertEquals(0, Bridges.status(marked))
    }

    @Test
    fun `completion needs one group and obeys the loop rule`() {
        val loop = "L0,0,2,0,1;L2,0,2,2,1;L2,2,0,2,1;L0,2,0,0,1"
        val disallowed = stateOf("3x3i30e10m2Ld0", "2a2aaa2a2")
        val blocked = Bridges.executeMove(disallowed, loop) as Bridges.State
        assertFalse(blocked.completed)
        assertEquals(0, Bridges.status(blocked))
        // The no-loops check marks the loop cells with G_WARN.
        assertTrue(blocked.grid.any { it and 0x80 != 0 })

        val allowed = stateOf("3x3i30e10m2d0", "2a2aaa2a2")
        val completed = Bridges.executeMove(allowed, loop) as Bridges.State
        assertTrue(completed.completed)
        assertEquals(1, Bridges.status(completed))
        assertFalse(completed.grid.any { it and 0x80 != 0 })

        // Two separate full groups are not a win.
        val twoGroups = stateOf("5x3i30e10m2d0", "1c1e1c1")
        val notYet = Bridges.executeMove(
            twoGroups,
            "L0,0,0,2,1;L4,0,4,2,1",
        ) as Bridges.State
        assertFalse(notYet.completed)
        assertEquals(0, Bridges.status(notYet))
    }

    @Test
    fun `changed state fires only for a real completion`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        val bridgeOnly = GOLDEN_AUX.split(';').drop(1)
            .filter { it.startsWith("L") }.joinToString(";")
        val completed = Bridges.executeMove(s, bridgeOnly) as Bridges.State
        assertTrue(completed.completed)
        assertFalse(completed.solved)
        assertTrue(Bridges.changedState(u, s, completed))

        val solved = Bridges.executeMove(s, GOLDEN_AUX) as Bridges.State
        assertTrue(solved.completed)
        assertTrue(solved.solved)
        assertFalse(Bridges.changedState(u, s, solved))
        assertFalse(Bridges.changedState(u, null, completed))
        assertFalse(Bridges.changedState(u, completed, completed))
    }

    @Test
    fun `the cursor helpers follow C`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        assertEquals(0, u.curX)
        assertEquals(0, u.curY)
        assertFalse(u.curVisible)
        assertNull(Bridges.cursorLocation(u, s, TILE))
        Bridges.setCursorVisible(u, true)
        assertEquals(
            Game.CursorRect(TILE / 2, TILE / 2, TILE, TILE),
            Bridges.cursorLocation(u, s, TILE),
        )
        Bridges.setCursorVisible(u, false)
        assertNull(Bridges.cursorLocation(u, s, TILE))
    }

    @Test
    fun `the cursor select branch cancels a drag first`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        fun key(button: Int) = Bridges.interpretMove(s, u, 0, 0, button, TILE)

        assertEquals(MoveResult.UiUpdate, key(Buttons.CURSOR_SELECT))
        assertTrue(u.curVisible)
        assertEquals(MoveResult.UiUpdate, key(Buttons.CURSOR_SELECT))
        assertTrue(u.dragging)
        // The second select cancels the drag, so the destination is
        // clear and the dead `else` is never taken.
        assertEquals(MoveResult.Move("M0,0"), key(Buttons.CURSOR_SELECT2))
        assertFalse(u.dragging)
    }

    @Test
    fun `modified cursor keys follow the C drag flag`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val shift = ui(s)
        assertEquals(
            MoveResult.Move("N0,0,6,0"),
            Bridges.interpretMove(
                s, shift, 0, 0,
                Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT, TILE,
            ),
        )
        assertTrue(shift.curVisible)

        val control = ui(s)
        assertEquals(
            MoveResult.Move("L0,0,6,0,1"),
            Bridges.interpretMove(
                s, control, 0, 0,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, TILE,
            ),
        )
    }

    @Test
    fun `the cursor cone follows C`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        u.curVisible = true
        assertEquals(
            MoveResult.UiUpdate,
            Bridges.interpretMove(s, u, 0, 0, Buttons.CURSOR_RIGHT, TILE),
        )
        assertEquals(6, u.curX)
        assertEquals(0, u.curY)
        assertEquals(
            MoveResult.UiUpdate,
            Bridges.interpretMove(s, u, 0, 0, Buttons.CURSOR_DOWN, TILE),
        )
        assertEquals(6, u.curX)
        assertEquals(2, u.curY)
    }

    @Test
    fun `the digit jump uses row-major ties`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        // A hidden cursor is only shown.
        assertEquals(
            MoveResult.UiUpdate,
            Bridges.interpretMove(s, u, 0, 0, '3'.code, TILE),
        )
        assertTrue(u.curVisible)
        assertEquals(0, u.curX)
        assertEquals(0, u.curY)

        // From (0,0) the nearest 3-island is (2,2).
        assertEquals(
            MoveResult.UiUpdate,
            Bridges.interpretMove(s, u, 0, 0, '3'.code, TILE),
        )
        assertEquals(2, u.curX)
        assertEquals(2, u.curY)

        // From (2,2) it goes back to (0,0), not to (5,4).
        Bridges.interpretMove(s, u, 0, 0, '3'.code, TILE)
        assertEquals(0, u.curX)
        assertEquals(0, u.curY)

        // `0` means 16 and finds nothing.
        assertEquals(
            MoveResult.NoEffect,
            Bridges.interpretMove(s, u, 0, 0, '0'.code, TILE),
        )
    }

    @Test
    fun `the text form prints a clue of ten as a colon`() {
        val s = stateOf("3x3", "Aa1f")
        assertEquals(10, s.islands[0].count)
        assertEquals(1, s.islands[1].count)
        assertEquals(":.1\n...\n...\n", Bridges.textFormat(s))
    }

    @Test
    fun `the hint move is the solver diff and never empty`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        val result = Bridges.interpretMove(s, u, 0, 0, 'h'.code, TILE)
        assertTrue(result is MoveResult.Move)
        assertEquals(HINT, (result as MoveResult.Move).text)
    }

    // ------------------------------------------------------------------
    // UI, keys, and drawing.
    // ------------------------------------------------------------------

    @Test
    fun `a press and release on an island toggles the lock`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        fun click(button: Int) = Bridges.interpretMove(s, u, TILE, TILE, button, TILE)

        assertEquals(MoveResult.UiUpdate, click(Buttons.LEFT_BUTTON))
        val release = click(Buttons.LEFT_RELEASE)
        assertEquals(MoveResult.Move("M0,0"), release)
        assertFalse(u.curVisible)
    }

    @Test
    fun `requestKeys and currentKeyLabel follow C`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val request = Bridges.requestKeys(params("7x7i30e10m2d0"))
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, request.arrowMode)
        assertEquals(listOf('G'.code, 'H'.code, 'L'.code), request.labels.map { it.button })
        assertEquals(listOf("Grid", "Solve from here", "Lock"), request.labels.map { it.name })
        assertEquals(listOf(false, false, true), request.labels.map { it.needsArrows })

        val u = ui(s)
        assertEquals("", Bridges.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        u.curVisible = true
        assertEquals("Select", Bridges.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("Finished", Bridges.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        u.dragging = true
        assertEquals("Finished", Bridges.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("", Bridges.currentKeyLabel(u, s, 'x'.code))
    }

    @Test
    fun `computeSize truncates an odd border`() {
        assertEquals(Size(7 * 24 + 24, 7 * 24 + 24),
            Bridges.computeSize(params("7x7"), 24))
        assertEquals(Size(10 * 63 + 62, 10 * 63 + 62),
            Bridges.computeSize(params("10x10"), 63))
        assertEquals(Size(15 * 41 + 40, 15 * 41 + 40),
            Bridges.computeSize(params("15x15"), 41))
    }

    @Test
    fun `the palette is the ten frozen roles`() {
        val colours = Bridges.colours()
        val palette = (0 until colours.size / 3).joinToString(" ") { index ->
            (0 until 3).joinToString(",") { channel ->
                (colours[index * 3 + channel] * 1000.0f + 0.5f).toInt().toString()
            }
        }
        assertEquals(
            "800,800,800 0,0,0 967,967,967 633,633,633 250,1000,250 " +
                "967,967,967 633,633,633 717,717,717 1000,250,250 1000,640,640",
            palette,
        )
    }

    @Test
    fun `the first draw emits the full update and every cell`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        val sink = RecordingSink()
        val ds = Bridges.newDrawState(s, TILE, sink)
        Bridges.redraw(s, u, ds, sink, TILE)
        assertEquals("update 0 0 192 192", sink.ops.first())
        assertEquals(1, sink.ops.count { it == "update 0 0 192 192" })
        assertTrue(sink.ops.any { it.startsWith("clip 12 12 24 24") })

        // A warm redraw with no UI or state change is empty.
        sink.ops.clear()
        Bridges.redraw(s, u, ds, sink, TILE)
        assertTrue(sink.ops.isEmpty())
    }

    @Test
    fun `show hints is UI state and changes the frame`() {
        val s = stateOf("7x7i30e10m2d0", GOLDEN_DESC)
        val u = ui(s)
        assertFalse(u.showHints)
        val sink = RecordingSink()
        var ds = Bridges.newDrawState(s, TILE, sink)
        Bridges.redraw(s, u, ds, sink, TILE)
        val without = sink.ops.toList()
        sink.ops.clear()
        u.showHints = true
        ds = Bridges.newDrawState(s, TILE, sink)
        Bridges.redraw(s, u, ds, sink, TILE)
        assertTrue(sink.ops != without)
        assertEquals(s, stateOf("7x7i30e10m2d0", GOLDEN_DESC))
    }

    private class RecordingSink : DrawSink {
        val ops = ArrayList<String>()
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect $x $y $width $height $colour"
        }
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line $x1 $y1 $x2 $y2 $colour"
        }
        override fun thickLine(
            x1: Int, y1: Int, x2: Int, y2: Int, thickness: Float, colour: Int,
        ) {
            ops += "thick_line $x1 $y1 $x2 $y2 $colour"
        }
        override fun circle(
            cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int,
        ) {
            ops += "circle $cx $cy $radius $fillColour $outlineColour"
        }
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly ${points.joinToString(" ")} $fillColour $outlineColour"
        }
        override fun text(
            x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String,
        ) {
            ops += "text $x $y $font $size $align $colour \"$text\""
        }
        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip $x $y $width $height"
        }
        override fun unclip() {
            ops += "unclip"
        }
        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update $x $y $width $height"
        }
        override fun status(text: String) {}
        override fun inertiaFollow(isSolved: Boolean) {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
