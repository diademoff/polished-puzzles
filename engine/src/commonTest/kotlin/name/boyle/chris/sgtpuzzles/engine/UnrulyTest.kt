package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges, the solver, and the drawing cache of Unruly. */
class UnrulyTest {

    // ------------------------------------------------------------------
    // Parameters and descriptions
    // ------------------------------------------------------------------

    @Test
    fun `parameters decode, encode, and validate`() {
        val d = Unruly.defaultParams() as Unruly.Params
        assertEquals(Unruly.Params(8, 8, false, 0), d)
        assertEquals("Unruly", Unruly.name)
        assertEquals("unruly", Unruly.backendName)
        assertEquals(32, Unruly.preferredTileSize)
        assertFalse(Unruly.wantsStatusBar)
        assertTrue(Unruly.canSolve)
        assertTrue(Unruly.canFormatAsText)

        // `decode_params` quirks: a missing `x`, a junk tail, an upper
        // case `X`, a trailing space, and a sign or a space before the
        // width all give the same 8x8.
        for (text in listOf("8", "8x8x8", "8X8", "8x8 ", " 8x8", "+8x8", "8x+8", "8x 8")) {
            assertEquals(
                Unruly.Params(8, 8, false, 0),
                Unruly.decodeParams(text),
                "decode '$text'",
            )
        }
        assertEquals(Unruly.Params(8, 8, true, 0), Unruly.decodeParams("8u8"))
        assertEquals(Unruly.Params(8, 8, true, 0), Unruly.decodeParams("8x8u"))
        assertEquals(Unruly.Params(8, 8, true, 0), Unruly.decodeParams("8x8ut"))
        assertEquals(Unruly.Params(8, 8, false, 0), Unruly.decodeParams("8x8tu"))
        assertEquals(Unruly.Params(8, 8, true, 2), Unruly.decodeParams("8x8udn"))
        assertEquals(Unruly.Params(8, 8, false, 0), Unruly.decodeParams("8x8dte"))

        // `8x8d` and `8x8dx` leave the invalid `DIFFCOUNT + 1`. The port
        // does not reset the value to Trivial.
        assertEquals(4, (Unruly.decodeParams("8x8d") as Unruly.Params).diff)
        assertEquals(4, (Unruly.decodeParams("8x8dx") as Unruly.Params).diff)
        assertEquals(4, (Unruly.decodeParams("8x8du") as Unruly.Params).diff)

        // The short form keeps `u` and drops `d<c>`.
        assertEquals("8x8dt", Unruly.encodeParams(d, true))
        assertEquals("8x8", Unruly.encodeParams(d, false))
        assertEquals("8x8udn", Unruly.encodeParams(Unruly.Params(8, 8, true, 2), true))
        assertEquals("8x8u", Unruly.encodeParams(Unruly.Params(8, 8, true, 2), false))

        // C reads `unruly_diffchars[diff]` with no bound: index 3 is the
        // NUL terminator and gives plain `d`; a larger index or a
        // negative one is out of bounds. The port writes the same `d`.
        assertEquals("8x8d", Unruly.encodeParams(Unruly.Params(8, 8, false, 3), true))
        assertEquals("8x8d", Unruly.encodeParams(Unruly.Params(8, 8, false, 99), true))
        assertEquals("8x8d", Unruly.encodeParams(Unruly.Params(8, 8, false, -1), true))

        assertEquals(
            listOf(
                "8x8 Trivial", "8x8 Easy", "8x8 Normal",
                "10x10 Easy", "10x10 Normal",
                "14x14 Easy", "14x14 Normal",
            ),
            Unruly.presets().map { it.title },
        )
        assertEquals(
            listOf("8x8dt", "8x8de", "8x8dn", "10x10de", "10x10dn", "14x14de", "14x14dn"),
            Unruly.presets().map { it.params },
        )
    }

    @Test
    fun `validation messages are the C messages in the C order`() {
        assertNull(Unruly.validateParams(Unruly.Params(8, 8, false, 0), true))

        // Odd before small: `5x6` is odd and too small.
        assertEquals(
            "Width and height must both be even",
            Unruly.validateParams(Unruly.Params(5, 6, false, 0), true),
        )
        assertEquals(
            "Width and height must both be even",
            Unruly.validateParams(Unruly.Params(7, 7, false, 0), true),
        )
        assertEquals(
            "Width and height must be at least 6",
            Unruly.validateParams(Unruly.Params(4, 4, false, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Unruly.validateParams(Unruly.Params(65536, 65536, false, 0), true),
        )
        assertEquals(
            "Puzzle is too tall for unique-rows mode",
            Unruly.validateParams(Unruly.Params(8, 36, true, 0), true),
        )
        assertEquals(
            "Puzzle is too long for unique-rows mode",
            Unruly.validateParams(Unruly.Params(36, 8, true, 0), true),
        )
        assertEquals(
            "Unknown difficulty rating",
            Unruly.validateParams(Unruly.Params(8, 8, false, 3), true),
        )

        // `A177790[4] == 34` is the tallest 8-wide unique board; C also
        // accepts a long side of 48 or more, because the test only
        // bounds the short side. Do not generate either board.
        assertNull(Unruly.validateParams(Unruly.Params(8, 34, true, 0), true))
        assertNull(Unruly.validateParams(Unruly.Params(48, 48, true, 0), true))
        // The area boundary is the C one. Do not generate it.
        assertNull(Unruly.validateParams(Unruly.Params(46340, 46340, false, 0), true))
        // A negative `diff` passes C validation; only the encoder guard
        // sees it.
        assertNull(Unruly.validateParams(Unruly.Params(8, 8, false, -1), true))
    }

    @Test
    fun `validateDesc sums the runs and names the three errors`() {
        val eight = Unruly.decodeParams("8x8dt")
        assertNull(Unruly.validateDesc(eight, DESC_8X8))
        assertEquals(
            "Description too short",
            Unruly.validateDesc(eight, DESC_8X8.dropLast(1)),
        )
        assertEquals(
            "Description too long",
            Unruly.validateDesc(eight, DESC_8X8 + "aa"),
        )
        assertEquals(
            "Description contains invalid characters",
            Unruly.validateDesc(eight, DESC_8X8 + "1"),
        )
        assertEquals("Description too short", Unruly.validateDesc(eight, ""))
        assertEquals("Description too short", Unruly.validateDesc(eight, "a"))
        assertEquals("Description too short", Unruly.validateDesc(eight, "z"))
        assertEquals("Description too long", Unruly.validateDesc(eight, "zzz"))

        // `y`/`Y` weigh 25 cells and write a clue on the 25th; `z`/`Z`
        // weigh 25 and write nothing. `y` + `l` is 25 + 12 = 37 cells,
        // the 6x6 total.
        val six = Unruly.decodeParams("6x6dt")
        assertNull(Unruly.validateDesc(six, "yl"))
        assertNull(Unruly.validateDesc(six, "Yl"))
        assertNull(Unruly.validateDesc(six, "zl"))
        assertEquals(
            "Description too long",
            Unruly.validateDesc(six, "yl" + "a"),
        )
    }

    @Test
    fun `new state decodes the clues, the mask, and the initial grid`() {
        val state = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_8X8) as Unruly.State
        assertEquals(64, state.grid.size)
        assertEquals(64, state.shared.initialGrid.size)
        assertTrue(state.shared.initialGrid.contentEquals(state.grid))
        assertFalse(state.completed)
        assertFalse(state.cheated)
        assertEquals(0, Unruly.status(state))
        assertEquals("", Unruly.statusText(state, Unruly.newUi(state)))

        // The default board: (1,0) is a 0 clue, (6,0) and (7,0) are 1
        // clues, (0,0) is empty, and (2,1) is a 1 clue.
        assertEquals(UnrulySolver.N_ZERO, state.grid[1])
        assertTrue(state.shared.immutable[1])
        assertEquals(UnrulySolver.EMPTY, state.grid[0])
        assertFalse(state.shared.immutable[0])
        assertEquals(UnrulySolver.N_ONE, state.grid[6])
        assertTrue(state.shared.immutable[6])
        assertEquals(UnrulySolver.N_ONE, state.grid[7])
        assertTrue(state.shared.immutable[7])
        assertEquals(UnrulySolver.N_ONE, state.grid[8 + 2])
        assertTrue(state.shared.immutable[8 + 2])

        assertEquals(TEXT_8X8, Unruly.textFormat(state))

        // The 6x6 golden text.
        val six = Unruly.newState(Unruly.decodeParams("6x6dt"), DESC_6X6)
        assertEquals(TEXT_6X6, Unruly.textFormat(six))

        // A hand-written `yl` leaves cell 24 a 0 clue and cells 25..35
        // empty.
        val hand = Unruly.newState(Unruly.decodeParams("6x6dt"), "yl") as Unruly.State
        assertEquals(UnrulySolver.N_ZERO, hand.grid[24])
        assertTrue(hand.shared.immutable[24])
        assertTrue((25 until 36).all { hand.grid[it] == UnrulySolver.EMPTY })

        // A description that is already a valid full board starts open.
        val full = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_FULL) as Unruly.State
        assertFalse(full.completed)
        assertEquals(0, Unruly.status(full))
        assertTrue(full.shared.immutable.all { it })

        // `copy` keeps the shared mask and copies the grid, and equality
        // ignores the shared identity.
        val copy = state.copy() as Unruly.State
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())
        assertTrue(state.shared === copy.shared)
        copy.grid[0] = UnrulySolver.N_ONE
        assertNotEquals(state, copy)
    }

    @Test
    fun `the cursor location is reported only when the cursor is visible`() {
        val state = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_8X8)
        val ui = Unruly.newUi(state) as Unruly.Ui
        assertNull(Unruly.cursorLocation(ui, state, 32), "a hidden cursor has no rectangle")

        Unruly.setCursorVisible(ui, true)
        assertTrue(ui.curVisible)
        ui.curX = 4
        ui.curY = 3
        assertEquals(
            Game.CursorRect(4 * 32 + 16, 3 * 32 + 16, 32, 32),
            Unruly.cursorLocation(ui, state, 32),
        )
        assertEquals(
            Game.CursorRect(4 * 45 + 22, 3 * 45 + 22, 45, 45),
            Unruly.cursorLocation(ui, state, 45),
        )
        Unruly.setCursorVisible(ui, false)
        assertNull(Unruly.cursorLocation(ui, state, 32))
    }

    // ------------------------------------------------------------------
    // Moves and completion
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar follows sscanf and rejects a clue target`() {
        val state = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_8X8)
        fun after(move: String): Unruly.State {
            val next = Unruly.executeMove(state, move)
            assertNotNull(next, "'$move' must be accepted")
            return next as Unruly.State
        }

        assertEquals(UnrulySolver.N_ONE, after("P1,0,0").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,0,0junk").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,0,0x").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1, 0,0").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,+0,0").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,00,0").grid[0])
        assertEquals(UnrulySolver.N_ZERO, after("P0,0,0").grid[0])
        assertEquals(UnrulySolver.EMPTY, after("P-,0,0").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,2,7").grid[7 * 8 + 2])
        assertEquals(UnrulySolver.N_ONE, after("P1,2,7 ").grid[7 * 8 + 2])

        // `%d` overflows to a `long`, then keeps the low 32 bits: 2^32
        // names x = 0, and a huge negative number names 0 as well.
        assertEquals(UnrulySolver.N_ONE, after("P1,4294967296,0").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,0,4294967296").grid[0])
        assertEquals(UnrulySolver.N_ONE, after("P1,-4294967296,0").grid[0])
        assertEquals(
            UnrulySolver.N_ONE,
            after("P1,-99999999999999999999,0").grid[0],
        )

        val rejected = listOf(
            "P1,0,7", "P1,0,8", "P1,0,-1", "P1,-1,0",
            "P2,0,0", "Px,0,0", "P0,1,0", "P-,3,3",
            "P", "P1", "P1,", "P1,0", "P1,0,",
            "P 1,0,0", "P,0,0", "P1;0,0", "P+1,0,0",
            "P1,99999999999999999999,0",
            "", "X", "S", "SS" + "0".repeat(63),
        )
        for (move in rejected) {
            assertNull(Unruly.executeMove(state, move), "'$move' must be rejected")
        }
        assertNotNull(Unruly.executeMove(state, "P1,0,0;"), "trailing junk is ignored")

        // An `S` move needs exactly `s` digits. Later characters are not
        // checked, clues are overwritten, and both flags are set.
        val zeros = Unruly.executeMove(state, "S" + "0".repeat(64)) as Unruly.State
        assertTrue(zeros.grid.all { it == UnrulySolver.N_ZERO })
        assertTrue(zeros.cheated)
        assertTrue(zeros.completed)
        assertEquals(1, Unruly.status(zeros))
        val ones = Unruly.executeMove(state, "S" + "1".repeat(64)) as Unruly.State
        assertTrue(ones.grid.all { it == UnrulySolver.N_ONE })
        assertNotNull(Unruly.executeMove(state, "S" + "0".repeat(64) + "junk"))
        assertNotNull(
            Unruly.executeMove(state, "S" + "0".repeat(65)),
            "characters after the s-th digit are ignored",
        )
        assertNull(Unruly.executeMove(state, "S" + "0".repeat(63)))
        assertNull(Unruly.executeMove(state, "S" + "2".repeat(64)))
    }

    @Test
    fun `completion is sticky and the unique rule is checked`() {
        // `DESC_ONE_EMPTY` has one empty mutable cell at (0,0) and the
        // value 1 belongs there. The board is valid except that its
        // even rows repeat, so it fails only the unique rule.
        val open = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_ONE_EMPTY) as Unruly.State
        assertEquals(UnrulySolver.EMPTY, open.grid[0])
        assertFalse(open.shared.immutable[0])

        val completed = Unruly.executeMove(open, "P1,0,0") as Unruly.State
        assertTrue(completed.completed)
        assertEquals(1, Unruly.status(completed))

        // The wrong value leaves the game open.
        val wrong = Unruly.executeMove(open, "P0,0,0") as Unruly.State
        assertFalse(wrong.completed)
        assertEquals(0, Unruly.status(wrong))

        // With `unique`, the same full board fails because two rows are
        // equal.
        val uniqueOpen =
            Unruly.newState(Unruly.decodeParams("8x8udt"), DESC_ONE_EMPTY) as Unruly.State
        val uniqueDone = Unruly.executeMove(uniqueOpen, "P1,0,0") as Unruly.State
        assertFalse(uniqueDone.completed)

        // The flag is sticky: a later move keeps it, and the check is
        // skipped.
        val later = Unruly.executeMove(completed, "P0,0,0") as Unruly.State
        assertTrue(later.completed)
        assertEquals(1, Unruly.status(later))
    }

    @Test
    fun `changed state reports only an honest win`() {
        val ui = Unruly.newUi(Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_ONE_EMPTY))
        val open = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_ONE_EMPTY)
        val done = Unruly.executeMove(open, "P1,0,0") as Unruly.State
        val cheated = Unruly.executeMove(open, "S" + "0".repeat(64)) as Unruly.State

        assertTrue(Unruly.changedState(ui, open, done))
        assertFalse(Unruly.changedState(ui, done, done), "the old state was already complete")
        assertFalse(Unruly.changedState(ui, open, cheated), "a solve is cheated")
        assertFalse(Unruly.changedState(ui, null, done), "no old state")
        assertFalse(
            Unruly.changedState(ui, done, open),
            "leaving the completed state is not a win",
        )
    }

    @Test
    fun `undo and redo report the completion event and restart clears it`() {
        val midend = Midend.fromGameId(Unruly, "8x8:$DESC_ONE_EMPTY")
        val tile = Unruly.preferredTileSize
        assertFalse(midend.takeCompletedEvent())

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(16, 16, Buttons.LEFT_BUTTON, tile))
        assertTrue(midend.takeCompletedEvent())
        assertEquals(1, midend.status())
        assertEquals(listOf("P1,0,0"), midend.serialise().values("MOVE"))

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent(), "undo leaves the completed state")

        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent(), "redo enters the completed state")

        // A solve never raises the event, and a restart returns to
        // status 0.
        val solving = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        assertTrue(solving.solve())
        assertEquals(1, solving.status())
        assertFalse(solving.takeCompletedEvent(), "a solve never raises the completion event")

        val restarting = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        restarting.processKey(16, 16, Buttons.LEFT_BUTTON, tile)
        assertEquals(0, restarting.status())
        assertTrue(restarting.restart())
        assertEquals(0, restarting.status())
        assertFalse(restarting.takeCompletedEvent())
        assertTrue(restarting.serialise().values("RESTART").isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Solver and solve
    // ------------------------------------------------------------------

    @Test
    fun `the solve reads the initial grid, not the current one`() {
        assertEquals(SOLVE_8X8, Unruly.solve(Midend.newGame(Unruly, "8x8dt", "12345").state))
        assertEquals(SOLVE_6X6, Unruly.solve(Midend.newGame(Unruly, "6x6dt", "12345").state))
        assertEquals(65, SOLVE_8X8.length)
        assertEquals(37, SOLVE_6X6.length)

        // A hand-written contradictory click changes the current grid,
        // but `states[0]` still solves to the same answer. C does this.
        val midend = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        val tile = Unruly.preferredTileSize
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(16, 16, Buttons.RIGHT_BUTTON, tile))
        assertEquals(listOf("P0,0,0"), midend.serialise().values("MOVE"))
        assertEquals(UnrulySolver.N_ZERO, (midend.state as Unruly.State).grid[0])
        assertEquals(SOLVE_8X8, Unruly.solve(midend.state))

        assertTrue(midend.solve())
        assertEquals(listOf("P0,0,0"), midend.serialise().values("MOVE"))
        assertEquals(listOf(SOLVE_8X8), midend.serialise().values("SOLVE"))
        assertEquals(1, midend.status())
        assertTrue((midend.state as Unruly.State).cheated)

        // `aux` is accepted and ignored.
        assertEquals(SOLVE_8X8, Unruly.solve(midend.state, "S" + "0".repeat(64)))

        // A stalled board and an invalid board return null; C sets
        // "No solution found." and "Puzzle is invalid." instead.
        assertNull(Unruly.solve(Unruly.newState(Unruly.decodeParams("8x8dt"), "zzo")))
        assertNull(Unruly.solve(Unruly.newState(Unruly.decodeParams("8x8dt"), "AAAzzl")))
    }

    @Test
    fun `the five techniques and the restart rule are pinned`() {
        // threes, horizontal: two 1s force a 0.
        val threes = board(6, 6, 0 to 0 to UnrulySolver.N_ONE, 1 to 0 to UnrulySolver.N_ONE)
        val threesScratch = UnrulySolver.newScratch(threes, 6, 6)
        assertEquals(1, UnrulySolver.checkAllThrees(threes, 6, 6, threesScratch))
        assertEquals(UnrulySolver.N_ZERO, threes[2])
        assertEquals(1, threesScratch.zerosRows[0])
        assertEquals(1, threesScratch.zerosCols[2])

        // threes, vertical.
        val vertical = board(6, 6, 0 to 0 to UnrulySolver.N_ONE, 0 to 1 to UnrulySolver.N_ONE)
        val verticalScratch = UnrulySolver.newScratch(vertical, 6, 6)
        assertEquals(1, UnrulySolver.checkAllThrees(vertical, 6, 6, verticalScratch))
        assertEquals(UnrulySolver.N_ZERO, vertical[2 * 6])

        // single_gap: three 1s and two 0s leave one cell for a 0.
        val gap = board(
            6, 6,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ZERO,
            3 to 0 to UnrulySolver.N_ONE,
            4 to 0 to UnrulySolver.N_ZERO,
        )
        val gapScratch = UnrulySolver.newScratch(gap, 6, 6)
        assertEquals(1, UnrulySolver.checkAllSingleGap(gap, 6, 6, gapScratch))
        assertEquals(UnrulySolver.N_ZERO, gap[5])

        // complete_nums: all four 1s are placed, so the empties are 0s.
        // The block of four 1s has no empty third cell, so threes does
        // nothing, and the two 0s are one short of single_gap.
        val complete = board(
            8, 8,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ONE,
            3 to 0 to UnrulySolver.N_ONE,
            4 to 0 to UnrulySolver.N_ZERO,
            7 to 0 to UnrulySolver.N_ZERO,
        )
        val completeScratch = UnrulySolver.newScratch(complete, 8, 8)
        assertEquals(2, UnrulySolver.checkAllCompleteNums(complete, 8, 8, completeScratch))
        assertTrue((5..6).all { complete[it] == UnrulySolver.N_ZERO })

        // uniques: row 0 holds three 1s and row 2 matches two of them, so
        // the third match is blocked.
        val uniques = board(
            6, 6,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ONE,
            3 to 0 to UnrulySolver.N_ZERO,
            4 to 0 to UnrulySolver.N_ZERO,
            5 to 0 to UnrulySolver.N_ZERO,
            0 to 2 to UnrulySolver.N_ONE,
            2 to 2 to UnrulySolver.N_ONE,
        )
        val uniquesScratch = UnrulySolver.newScratch(uniques, 6, 6)
        assertEquals(1, UnrulySolver.checkAllUniques(uniques, 6, 6, uniquesScratch))
        assertEquals(UnrulySolver.N_ZERO, uniques[2 * 6 + 1])

        // near_complete: row 0 has two 1s and one 0. The triple of a 0
        // and two empties masks those two cells and fills the last cell
        // with a 0; the masked cells return to empty.
        val near = board(
            6, 6,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ZERO,
        )
        val nearScratch = UnrulySolver.newScratch(near, 6, 6)
        assertEquals(1, UnrulySolver.checkAllNearComplete(near, 6, 6, nearScratch))
        assertEquals(UnrulySolver.N_ZERO, near[5])
        assertEquals(UnrulySolver.EMPTY, near[3])
        assertEquals(UnrulySolver.EMPTY, near[4])

        // The driver restarts at threes and returns the highest
        // technique. The near_complete board reports Normal; the threes
        // board reports Trivial.
        val nearDriver = board(
            6, 6,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ZERO,
        )
        val nearDriverScratch = UnrulySolver.newScratch(nearDriver, 6, 6)
        assertEquals(
            UnrulySolver.DIFF_NORMAL,
            UnrulySolver.solveGame(
                nearDriver, 6, 6, false, nearDriverScratch, UnrulySolver.DIFFCOUNT,
            ),
        )

        // The difficulty gate: at Trivial, complete_nums never runs.
        val gated = board(
            8, 8,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ONE,
            3 to 0 to UnrulySolver.N_ONE,
            4 to 0 to UnrulySolver.N_ZERO,
            7 to 0 to UnrulySolver.N_ZERO,
        )
        val gatedScratch = UnrulySolver.newScratch(gated, 8, 8)
        assertEquals(
            -1,
            UnrulySolver.solveGame(
                gated, 8, 8, false, gatedScratch, UnrulySolver.DIFF_TRIVIAL,
            ),
        )
        assertEquals(UnrulySolver.EMPTY, gated[5])

        // The same board at Easy fills it.
        val easy = gated.copyOf()
        val easyScratch = UnrulySolver.newScratch(easy, 8, 8)
        assertEquals(
            UnrulySolver.DIFF_EASY,
            UnrulySolver.solveGame(easy, 8, 8, false, easyScratch, UnrulySolver.DIFFCOUNT),
        )
        assertTrue((5..6).all { easy[it] == UnrulySolver.N_ZERO })
    }

    @Test
    fun `the unique technique skips an impossible pair instead of the C asserts`() {
        // C continues when the blocked cell already holds `block`
        // (`unruly.c:674`). The port skips the same pair.
        val blocked = board(
            6, 6,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            2 to 0 to UnrulySolver.N_ONE,
            0 to 2 to UnrulySolver.N_ONE,
            1 to 2 to UnrulySolver.N_ONE,
            2 to 2 to UnrulySolver.N_ZERO,
        )
        val blockedBefore = blocked.copyOf()
        val blockedScratch = UnrulySolver.newScratch(blocked, 6, 6)
        assertEquals(0, UnrulySolver.checkAllUniques(blocked, 6, 6, blockedScratch))
        assertTrue(blockedBefore.contentEquals(blocked))

        // C names `-1` as a column when `nonmatch` is -1
        // (`unruly.c:671`). The counters are rebuilt from the grid on
        // every public path, so only a lying counter reaches it. The
        // port skips the pair instead of indexing `-1`.
        val lying = board(
            6, 6,
            0 to 0 to UnrulySolver.N_ONE,
            1 to 0 to UnrulySolver.N_ONE,
            0 to 2 to UnrulySolver.N_ONE,
            1 to 2 to UnrulySolver.N_ONE,
        )
        val lyingBefore = lying.copyOf()
        val ones = IntArray(6)
        ones[0] = 3
        ones[2] = 2
        assertEquals(
            0,
            UnrulySolver.checkUniques(
                lying, 6, 6, ones, true, UnrulySolver.N_ONE, UnrulySolver.N_ZERO,
                UnrulySolver.newScratch(lying, 6, 6),
            ),
        )
        assertTrue(lyingBefore.contentEquals(lying))
    }

    @Test
    fun `validation returns the three-way count result and the error bits`() {
        val solved = IntArray(64) { index -> if (SOLVE_8X8[index + 1] == '1') 1 else 2 }
        assertEquals(0, UnrulySolver.validateCounts(solved, 8, 8, null, null))
        assertEquals(0, UnrulySolver.validateAllRows(solved, 8, 8, false, null))

        val partial = IntArray(64)
        assertEquals(1, UnrulySolver.validateCounts(partial, 8, 8, null, null))

        // Five 1s in row 0 put the row above its target. The row flag is
        // `errors[0]`; the column flags start at `2 * h`.
        val overfullRow = IntArray(64) { if (it < 5) 1 else 0 }
        val rowErrors = BooleanArray(2 * (8 + 8))
        assertEquals(-1, UnrulySolver.validateCounts(overfullRow, 8, 8, null, rowErrors))
        assertTrue(rowErrors[0])
        assertFalse(rowErrors[8])

        // Five 1s in column 0 put the column above its target.
        val overfullCol = IntArray(64) { if (it % 8 == 0 && it < 40) 1 else 0 }
        val colErrors = BooleanArray(2 * (8 + 8))
        assertEquals(-1, UnrulySolver.validateCounts(overfullCol, 8, 8, null, colErrors))
        assertTrue(colErrors[2 * 8])

        // A three sets the left, middle, and right bits.
        val three = IntArray(64) { if (it < 3) 1 else 0 }
        val threeErrors = IntArray(64)
        assertEquals(-1, UnrulySolver.validateAllRows(three, 8, 8, false, threeErrors))
        assertEquals(Unruly.FE_HOR_ROW_LEFT, threeErrors[0] and Unruly.FE_HOR_ROW_LEFT)
        assertEquals(Unruly.FE_HOR_ROW_MID, threeErrors[1] and Unruly.FE_HOR_ROW_MID)
        assertEquals(Unruly.FE_HOR_ROW_RIGHT, threeErrors[2] and Unruly.FE_HOR_ROW_RIGHT)

        // A vertical three sets the top, middle, and bottom bits.
        val vertical = IntArray(64) { if (it % 8 == 0 && it < 24) 1 else 0 }
        val verticalErrors = IntArray(64)
        assertEquals(-1, UnrulySolver.validateAllRows(vertical, 8, 8, false, verticalErrors))
        assertEquals(Unruly.FE_VER_ROW_TOP, verticalErrors[0] and Unruly.FE_VER_ROW_TOP)
        assertEquals(Unruly.FE_VER_ROW_MID, verticalErrors[8] and Unruly.FE_VER_ROW_MID)
        assertEquals(Unruly.FE_VER_ROW_BOTTOM, verticalErrors[16] and Unruly.FE_VER_ROW_BOTTOM)

        // The repeating-row board is clean without `unique` and fails
        // with it.
        val repeating = Unruly.newState(
            Unruly.decodeParams("8x8dt"),
            DESC_ONE_EMPTY,
        ) as Unruly.State
        val full = Unruly.executeMove(repeating, "P1,0,0") as Unruly.State
        assertEquals(0, UnrulySolver.validateAllRows(full.grid, 8, 8, false, null))
        assertEquals(-1, UnrulySolver.validateAllRows(full.grid, 8, 8, true, null))
        val matchErrors = IntArray(64)
        UnrulySolver.validateAllRows(full.grid, 8, 8, true, matchErrors)
        assertTrue(matchErrors[0] and Unruly.FE_ROW_MATCH != 0)
    }

    @Test
    fun `the description encoder round-trips a partial board`() {
        val state = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_ONE_EMPTY) as Unruly.State
        assertEquals(DESC_ONE_EMPTY, UnrulySolver.encodeDescription(state.grid))

        val full = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_FULL) as Unruly.State
        assertEquals(DESC_FULL, UnrulySolver.encodeDescription(full.grid))
        assertEquals("A".repeat(64) + "a", UnrulySolver.encodeDescription(IntArray(64) { 1 }))
        assertEquals("aa", UnrulySolver.encodeDescription(IntArray(1) { UnrulySolver.N_ZERO }))
    }

    // ------------------------------------------------------------------
    // UI, keys, and size
    // ------------------------------------------------------------------

    @Test
    fun `a click places a value, hides the cursor, and moves it`() {
        val midend = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        val ui = midend.ui as Unruly.Ui
        val tile = Unruly.preferredTileSize
        assertFalse(ui.curVisible)
        assertEquals(0, ui.curX)
        assertEquals(0, ui.curY)

        // A click on the empty cell (4,3) places 1, hides the (already
        // hidden) cursor, and moves it to the clicked cell.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(144, 112, Buttons.LEFT_BUTTON, tile))
        assertEquals(4, ui.curX)
        assertEquals(3, ui.curY)
        assertFalse(ui.curVisible)
        assertEquals(listOf("P1,4,3"), midend.serialise().values("MOVE"))
        assertEquals(UnrulySolver.N_ONE, (midend.state as Unruly.State).grid[3 * 8 + 4])

        // The next right arrow makes the cursor visible at (5,3).
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
        assertTrue(ui.curVisible)
        assertEquals(5, ui.curX)
        assertEquals(3, ui.curY)

        // A click on a clue is no effect with a hidden cursor and a UI
        // update when the cursor was visible.
        val clueX = 1 * tile + tile / 2
        val clueY = tile / 2
        val fresh = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        val freshUi = fresh.ui as Unruly.Ui
        val freshState = fresh.state
        assertEquals(
            MoveResult.NoEffect,
            Unruly.interpretMove(freshState, freshUi, clueX, clueY, Buttons.LEFT_BUTTON, tile),
        )
        freshUi.curVisible = true
        assertEquals(
            MoveResult.UiUpdate,
            Unruly.interpretMove(freshState, freshUi, clueX, clueY, Buttons.LEFT_BUTTON, tile),
        )
        assertFalse(freshUi.curVisible)

        // A middle click on an empty cell is a no-op: the target is `-`
        // and the cell is already empty.
        val middle = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        val middleUi = middle.ui as Unruly.Ui
        assertEquals(
            MoveResult.NoEffect,
            Unruly.interpretMove(middle.state, middleUi, 16, 16, Buttons.MIDDLE_BUTTON, tile),
        )
        middleUi.curVisible = true
        assertEquals(
            MoveResult.UiUpdate,
            Unruly.interpretMove(middle.state, middleUi, 16, 16, Buttons.MIDDLE_BUTTON, tile),
        )

        // Digits and selects with a hidden cursor are unused.
        assertEquals(
            MoveResult.Unused,
            Unruly.interpretMove(freshState, Unruly.Ui(), 0, 0, '1'.code, tile),
        )
        assertEquals(
            MoveResult.Unused,
            Unruly.interpretMove(freshState, Unruly.Ui(), 0, 0, Buttons.CURSOR_SELECT, tile),
        )

        // With a visible cursor, the select places at the cursor.
        val keys = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        val keysUi = keys.ui as Unruly.Ui
        // The left arrow makes the cursor visible at (0,0).
        assertEquals(KeyOutcome.SomeEffect, keys.processKey(0, 0, Buttons.CURSOR_LEFT, tile))
        assertTrue(keysUi.curVisible)
        assertEquals(KeyOutcome.NoEffect, keys.processKey(0, 0, Buttons.CURSOR_LEFT, tile))
        assertEquals(KeyOutcome.SomeEffect, keys.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals(listOf("P1,0,0"), keys.serialise().values("MOVE"))
    }

    @Test
    fun `current key labels cover the six values and a clue`() {
        val state = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_8X8) as Unruly.State
        val ui = Unruly.newUi(state) as Unruly.Ui

        // Hidden cursor: no label.
        assertEquals("", Unruly.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Unruly.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.curVisible = true
        ui.curX = 0
        ui.curY = 0
        assertEquals("Black", Unruly.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("White", Unruly.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        val one = Unruly.executeMove(state, "P1,0,0") as Unruly.State
        assertEquals("White", Unruly.currentKeyLabel(ui, one, Buttons.CURSOR_SELECT))
        assertEquals("Empty", Unruly.currentKeyLabel(ui, one, Buttons.CURSOR_SELECT2))

        val zero = Unruly.executeMove(state, "P0,0,0") as Unruly.State
        assertEquals("Empty", Unruly.currentKeyLabel(ui, zero, Buttons.CURSOR_SELECT))
        assertEquals("Black", Unruly.currentKeyLabel(ui, zero, Buttons.CURSOR_SELECT2))

        // A clue cell has no label, and so has a key the game ignores.
        ui.curX = 1
        assertEquals("", Unruly.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.curX = 0
        assertEquals("", Unruly.currentKeyLabel(ui, state, '1'.code))
    }

    @Test
    fun `requested keys, size, and the derived palette match C`() {
        val keys = Unruly.requestKeys(Unruly.decodeParams("8x8dt"))
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, keys.arrowMode)
        assertTrue(keys.labels.isEmpty())

        assertEquals(Size(288, 288), Unruly.computeSize(Unruly.decodeParams("8x8dt"), 32))
        assertEquals(Size(432, 432), Unruly.computeSize(Unruly.decodeParams("8x8dt"), 48))
        assertEquals(Size(407, 407), Unruly.computeSize(Unruly.decodeParams("10x10de"), 37))
        assertEquals(Size(405, 405), Unruly.computeSize(Unruly.decodeParams("14x14dn"), 27))

        val colours = Unruly.colours()
        assertEquals(11 * 3, colours.size)
        assertEquals(
            listOf(
                800, 800, 800,
                300, 300, 300,
                500, 500, 500,
                833, 833, 833,
                1000, 1000, 1000,
                687, 687, 687,
                230, 230, 230,
                397, 397, 397,
                63, 63, 63,
                0, 700, 0,
                1000, 0, 0,
            ),
            colours.map { (it * 1000.0f).roundToInt() },
        )

        // A shell background flows into COL_BACKGROUND only.
        val tinted = Unruly.colours(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertEquals(200, (tinted[0] * 1000.0f).roundToInt())
        assertEquals(400, (tinted[1] * 1000.0f).roundToInt())
        assertEquals(600, (tinted[2] * 1000.0f).roundToInt())
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Test
    fun `the drawing cache draws once and then only changed cells`() {
        val state = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_8X8) as Unruly.State
        val ui = Unruly.newUi(state) as Unruly.Ui
        val sink = RecordingSink()
        val ds = Unruly.newDrawState(state, 32, sink)
        assertFalse((ds as Unruly.DrawState).started)
        assertTrue(ds.grid.all { it == -1 })

        val first = RecordingSink()
        Unruly.redraw(state, ui, ds, first, 32)
        assertTrue(ds.started)
        assertEquals(64, first.clips.size)
        assertEquals(65, first.updates.size)
        assertTrue(
            first.rects.any { it.x == 13 && it.y == 13 && it.width == 261 && it.height == 261 },
            "the outer rectangle is at COORD(0) - T/10",
        )

        // A warm redraw of the same state and UI emits nothing, not even
        // the whole-board update.
        val warm = RecordingSink()
        Unruly.redraw(state, ui, ds, warm, 32)
        assertTrue(warm.ops.isEmpty())

        // A visible cursor repaints one cell and draws the ring.
        ui.curVisible = true
        ui.curX = 4
        ui.curY = 3
        val cursor = RecordingSink()
        Unruly.redraw(state, ui, ds, cursor, 32)
        assertEquals(1, cursor.clips.size)
        assertEquals(1, cursor.updates.size)
        assertTrue(cursor.rects.any { it.colour == 9 })
        assertEquals(4, cursor.rects.count { it.colour == 9 })

        // Settle, then a move repaints only the changed cells and never
        // the whole board.
        Unruly.redraw(state, ui, ds, RecordingSink(), 32)
        ui.curVisible = false
        Unruly.redraw(state, ui, ds, RecordingSink(), 32)
        val moved = Unruly.executeMove(state, "P1,4,3") as Unruly.State
        val afterMove = RecordingSink()
        Unruly.redraw(moved, ui, ds, afterMove, 32)
        assertTrue(afterMove.clips.isNotEmpty())
        assertEquals(afterMove.clips.size, afterMove.updates.size)
        assertFalse(afterMove.updates.contains(Update(0, 0, 288, 288)))

        val settled = RecordingSink()
        Unruly.redraw(moved, ui, ds, settled, 32)
        assertTrue(settled.ops.isEmpty())
    }

    @Test
    fun `error frames draw the error rectangles, the bang, and the match bars`() {
        // Five 1s in row 0: every cell of the row carries FE_COUNT and
        // draws one "!".
        val count = Unruly.newState(Unruly.decodeParams("8x8dt"), "AAAAAzzj") as Unruly.State
        val sink = RecordingSink()
        Unruly.redraw(count, Unruly.newUi(count), Unruly.newDrawState(count, 32, sink), sink, 32)
        assertEquals(5, sink.texts.count { it == "!" })

        // Three 1s in a row draw the horizontal error rectangles.
        val three = Unruly.newState(Unruly.decodeParams("8x8dt"), "AAAzzl") as Unruly.State
        val threeSink = RecordingSink()
        Unruly.redraw(
            three,
            Unruly.newUi(three),
            Unruly.newDrawState(three, 32, threeSink),
            threeSink,
            32,
        )
        assertTrue(threeSink.rects.any { it.colour == 10 })
        assertTrue(threeSink.texts.none { it == "!" }, "no count error in this board")

        // The repeating rows in unique mode draw the row match bars.
        val repeating = Unruly.newState(
            Unruly.decodeParams("8x8udt"),
            DESC_ONE_EMPTY,
        ) as Unruly.State
        val match = Unruly.executeMove(repeating, "P1,0,0") as Unruly.State
        val matchSink = RecordingSink()
        Unruly.redraw(match, Unruly.newUi(match), Unruly.newDrawState(match, 32, matchSink), matchSink, 32)
        // A row bar is `T` wide and `2 * (T/12)` high.
        assertTrue(
            matchSink.rects.any { it.width == 32 && it.height == 4 && it.colour == 10 },
        )
    }

    // ------------------------------------------------------------------
    // Generation and vacuity
    // ------------------------------------------------------------------

    @Test(timeout = 120_000)
    fun `generation is deterministic and uses the run alphabet`() {
        assertEquals("bEACHcBdBDEalcbAbbBa", Midend.newGame(Unruly, "8x8dt", "12345").desc)
        assertEquals("bEACHcBdBDEaleAbbc", Midend.newGame(Unruly, "8x8de", "12345").desc)
        assertEquals("bEACHcBdFEaleAbbc", Midend.newGame(Unruly, "8x8dn", "12345").desc)
        assertEquals("bDaacAJAbjb", Midend.newGame(Unruly, "6x6dt", "12345").desc)
        assertEquals("aaeABjBjBAb", Midend.newGame(Unruly, "6x6udt", "12345").desc)

        // The difficulty gate changes the stream: Easy and Normal differ
        // for these seeds because the outer retry rejects a puzzle that
        // the lower difficulty can finish.
        assertNotEquals(
            Midend.newGame(Unruly, "8x8de", "1").desc,
            Midend.newGame(Unruly, "8x8dn", "1").desc,
        )
        assertNotEquals(
            Midend.newGame(Unruly, "8x8de", "2").desc,
            Midend.newGame(Unruly, "8x8dn", "2").desc,
        )
    }

    @Test
    fun `a real placement changes the grid and the move count, a ui action does not`() {
        val midend = Midend.fromGameId(Unruly, "8x8:$DESC_8X8")
        val tile = Unruly.preferredTileSize
        val before = (midend.state as Unruly.State).grid.copyOf()
        val startSave = midend.serialise().serialise()

        // A cursor key is a UI update: no state and no save change.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
        assertEquals(1, midend.stateCount)
        assertEquals(startSave, midend.serialise().serialise())
        assertTrue(before.contentEquals((midend.state as Unruly.State).grid))

        // A click on a clue hides the visible cursor and changes nothing
        // else: this is the UI-only path.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(48, 16, Buttons.LEFT_BUTTON, tile))
        assertEquals(1, midend.stateCount)
        assertEquals(startSave, midend.serialise().serialise())
        assertTrue(before.contentEquals((midend.state as Unruly.State).grid))

        // A real placement on the empty cell (4,3) changes the grid and
        // adds one state.
        assertEquals(UnrulySolver.EMPTY, before[3 * 8 + 4])
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(144, 112, Buttons.LEFT_BUTTON, tile))
        assertEquals(2, midend.stateCount)
        assertEquals(UnrulySolver.N_ONE, (midend.state as Unruly.State).grid[3 * 8 + 4])
        assertEquals(listOf("P1,4,3"), midend.serialise().values("MOVE"))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun board(w: Int, h: Int, vararg clues: Pair<Pair<Int, Int>, Int>): IntArray {
        val grid = IntArray(w * h)
        for ((cell, value) in clues) {
            grid[cell.second * w + cell.first] = value
        }
        return grid
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int, val colour: Int)
    private data class Update(val x: Int, val y: Int, val width: Int, val height: Int)

    private class RecordingSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        val ops = ArrayList<String>()
        val rects = ArrayList<Rect>()
        val clips = ArrayList<Update>()
        val updates = ArrayList<Update>()
        val texts = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state"
        }

        override fun startDraw() {
            ops += "start_draw"
        }

        override fun endDraw() {
            ops += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect"
            rects += Rect(x, y, width, height, colour)
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun thickLine(x1: Int, y1: Int, x2: Int, y2: Int, thickness: Float, colour: Int) {}

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
            ops += "circle"
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
            ops += "poly"
        }

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {
            ops += "text"
            texts += text
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
            clips += Update(x, y, width, height)
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update"
            updates += Update(x, y, width, height)
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun blitterNew(width: Int, height: Int): Int = 0

        override fun blitterFree(id: Int) {}

        override fun blitterSave(id: Int, x: Int, y: Int) {}

        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }

    private companion object {
        const val DESC_8X8 = "bEACHcBdBDEalcbAbbBa"
        const val DESC_6X6 = "bDaacAJAbjb"
        const val SOLVE_8X8 =
            "S1001001101101100001100111100110011010010001101011010110001001011"
        const val SOLVE_6X6 = "S001011001101110010100110011001110100"
        const val TEXT_8X8 =
            ". 0 . . . . 1 1 \n" +
                ". . 1 . . . . . \n" +
                ". . 1 . . 0 . 1 \n" +
                ". . . 0 . 1 . . \n" +
                ". 1 . . . . 1 0 \n" +
                ". . . . . . . . \n" +
                ". . . 0 . . 0 . \n" +
                "0 1 . 0 . 0 . 1 \n"
        const val TEXT_6X6 =
            ". 0 . . . 1 \n" +
                "0 0 . . 0 1 \n" +
                ". . . . . . \n" +
                ". . . 1 1 . \n" +
                "0 . . . . . \n" +
                ". . . . 0 . \n"
        const val DESC_ONE_EMPTY =
            "BaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAa"
        const val DESC_FULL =
            "AAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAa"
    }
}
