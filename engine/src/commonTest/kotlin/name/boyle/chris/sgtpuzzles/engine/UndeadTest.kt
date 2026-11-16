package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: undead.
 *
 * Kotlin-only pins for the parameter quirks, the description decoder,
 * the state and the fixed cells, the text format, the move grammar, the
 * click and keyboard cycles, the count-row geometry, the error marking,
 * the solver outcomes, the key labels, the preferences, and the palette.
 *
 * The oracle comparisons live in `UndeadDescTest`, `UndeadGameTest`,
 * `UndeadSaveTest`, `UndeadTraceTest`, and `UndeadInputParityTest`.
 */
class UndeadTest {

    private companion object {
        const val TILE = 64
        const val SEED = "12345"

        const val DEFAULT_DESC =
            "1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"

        const val DEFAULT_SOLVE =
            "S;Z0;Z1;Z2;Z3;Z4;G5;V6;V7;V8;V9;Z10;V11"

        const val DEFAULT_TEXT =
            "G: 1 V: 5 Z: 6\n" +
                "\n" +
                "   2 4 2 4  \n" +
                " 2 . . / . 3\n" +
                " 3 . . . / 2\n" +
                " 0 \\ . . / 0\n" +
                " 4 . . . . 4\n" +
                "   1 4 3 1  \n"

        const val INCONSISTENT_DESC = "1,5,6,bRdRLbRd,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"
        const val UNSOLVABLE_DESC =
            "3,4,2,RdLaRRbRLRb,1,2,2,2,2,3,0,1,1,2,1,0,0,1,4,0"
    }

    private fun params(text: String): Undead.Params =
        Undead.decodeParams(text) as Undead.Params

    private fun stateOf(paramsText: String, desc: String): Undead.State =
        Undead.newState(params(paramsText), desc) as Undead.State

    private fun defaultState(): Undead.State = stateOf("4x4dn", DEFAULT_DESC)

    private fun ui(): Undead.Ui = Undead.Ui()

    private fun click(
        state: Undead.State,
        ui: Undead.Ui,
        x: Int,
        y: Int,
        button: Int = Buttons.LEFT_BUTTON,
        tile: Int = TILE,
    ): MoveResult = Undead.interpretMove(state, ui, x, y, button, tile)

    /** The safe centre of the drawn cell at (x, y). */
    private fun cellX(x: Int, tile: Int = TILE): Int = tile / 4 + x * tile + tile / 2
    private fun cellY(y: Int, tile: Int = TILE): Int = tile / 4 + y * tile + 3 * tile / 2

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `defaults and presets match the C tables`() {
        assertEquals(Undead.Params(4, 4, Undead.DIFF_NORMAL), Undead.defaultParams())
        val presets = Undead.presets()
        assertEquals(8, presets.size)
        assertEquals(
            listOf(
                "4x4de", "4x4dn", "4x4dt", "5x5de", "5x5dn", "5x5dt",
                "7x7de", "7x7dn",
            ),
            presets.map { it.params },
        )
        assertEquals(
            listOf(
                "4x4 Easy", "4x4 Normal", "4x4 Tricky", "5x5 Easy",
                "5x5 Normal", "5x5 Tricky", "7x7 Easy", "7x7 Normal",
            ),
            presets.map { it.title },
        )
        assertEquals(64, Undead.preferredTileSize)
        assertFalse(Undead.wantsStatusBar)
        assertTrue(Undead.canSolve)
        assertTrue(Undead.canFormatAsText)
    }

    @Test
    fun `decode follows atoi and the digit loop`() {
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4x4"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4x4dn"))
        assertEquals(Undead.Params(4, 4, 0), Undead.decodeParams("4x4de"))
        assertEquals(Undead.Params(4, 4, 2), Undead.decodeParams("4x4dt"))
        assertEquals(Undead.Params(4, 4, 2), Undead.decodeParams("4x4dte"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4x4d"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4x4dE"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4x4dnjunk"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4X5"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams(" 4x4"))
        assertEquals(Undead.Params(4, 4, 1), Undead.decodeParams("4x+4"))
        assertEquals(Undead.Params(4, 0, 1), Undead.decodeParams("4x"))
        assertEquals(Undead.Params(-4, -4, 1), Undead.decodeParams("-4x4"))
        assertEquals(Undead.Params(4, -4, 1), Undead.decodeParams("4x-4"))
        // C `atoi` overflow is undefined; the port saturates.
        assertEquals(
            Undead.Params(Int.MAX_VALUE, 4, 1),
            Undead.decodeParams("99999999999x4"),
        )
    }

    @Test
    fun `encode drops the difficulty in the short form`() {
        assertEquals("4x4", Undead.encodeParams(Undead.Params(4, 4, 1), false))
        assertEquals("4x4dn", Undead.encodeParams(Undead.Params(4, 4, 1), true))
        assertEquals("4x4de", Undead.encodeParams(Undead.Params(4, 4, 0), true))
        assertEquals("4x4dt", Undead.encodeParams(Undead.Params(4, 4, 2), true))
        // A hand-built out-of-range difficulty cannot change a validated
        // value; the port uses the Normal character.
        assertEquals("4x4dn", Undead.encodeParams(Undead.Params(4, 4, 9), true))
        assertEquals("4x4dn", Undead.encodeParams(Undead.Params(4, 4, -1), true))
    }

    @Test
    fun `validation follows the C order and messages`() {
        assertEquals(
            "Width must be at least 3",
            Undead.validateParams(Undead.Params(2, 4, 0), true),
        )
        assertEquals(
            "Height must be at least 3",
            Undead.validateParams(Undead.Params(4, 2, 0), true),
        )
        assertEquals(
            "Grid is too big",
            Undead.validateParams(Undead.Params(19, 3, 0), true),
        )
        assertEquals(
            "Grid is too big",
            Undead.validateParams(Undead.Params(3, 19, 0), true),
        )
        assertEquals(
            "Grid is too big",
            Undead.validateParams(Undead.Params(14, 4, 0), true),
        )
        assertEquals(
            "Unknown difficulty rating",
            Undead.validateParams(Undead.Params(4, 4, 3), true),
        )
        assertEquals(null, Undead.validateParams(Undead.Params(4, 4, -1), true))
        assertEquals(null, Undead.validateParams(Undead.Params(18, 3, 0), true))
        assertEquals(null, Undead.validateParams(Undead.Params(3, 18, 0), true))
        assertEquals(null, Undead.validateParams(Undead.Params(13, 4, 0), true))
        assertEquals(null, Undead.validateParams(Undead.Params(4, 13, 0), true))
    }

    // ------------------------------------------------------------------
    // Descriptions.
    // ------------------------------------------------------------------

    @Test
    fun `validateDesc uses the exact C messages`() {
        val p = params("4x4dn")
        fun check(desc: String) = Undead.validateDesc(p, desc)

        assertEquals("Faulty game description", check(""))
        assertEquals("Invalid character in number list", check("1,5"))
        assertEquals("Invalid character in number list", check("1,5,6x,bRdRLbRd"))
        assertEquals(
            "Invalid character in number list",
            check("-1,5,6,bRdRLbRd"),
        )
        assertEquals(
            "Invalid character in grid specification",
            check("1,5,6,bRdRLbRdA,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"),
        )
        assertEquals(
            "Not enough data to fill grid",
            check("1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2".replace("bRdRLbRd", "bRdRLbR")),
        )
        assertEquals(
            "Too much data to fill grid",
            check("1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2".replace("bRdRLbRd", "bRdRLbRdd")),
        )
        assertEquals(
            "Monster numbers do not match grid spaces",
            check("1,5,7,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"),
        )
        assertEquals(
            "Not enough numbers given after grid specification",
            check("1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3"),
        )
        assertEquals(
            "Invalid character in number list",
            check("1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,x,0"),
        )
        assertEquals(
            "Unexpected additional data at end of game description",
            check("$DEFAULT_DESC,1"),
        )
    }

    @Test
    fun `validateDesc accepts the lenient hand-written forms`() {
        val p = params("4x4dn")
        // Empty counts are 0; empty clues are 0; lower-case l and z are
        // run lengths, not tokens.
        assertEquals(null, Undead.validateDesc(p, ",5,6,kLLLLL,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"))
        assertEquals(null, Undead.validateDesc(p, "1,5,6,bRdRLbRd," + ",0".repeat(15)))
        assertEquals(
            null,
            Undead.validateDesc(p, "1,5,6,bRdRLbRd," + "0".repeat(3) + ",0".repeat(15)),
        )
    }

    @Test
    fun `newState parses the counts, the grid, the clues, and the corners`() {
        val s = defaultState()
        assertEquals(1, s.common.numGhosts)
        assertEquals(5, s.common.numVampires)
        assertEquals(6, s.common.numZombies)
        assertEquals(12, s.common.numTotal)
        assertEquals(8, s.common.numPaths)
        assertEquals(36, s.common.wh)
        assertEquals(8, s.common.paths.size)
        assertEquals(-2, s.common.xinfo[0])
        assertEquals(2, s.common.grid[1])
        assertEquals(4, s.common.grid[2])
        assertEquals(2, s.common.grid[3])
        assertEquals(8, s.common.paths.count { it.gridStart in 0..15 })
    }

    @Test
    fun `the text format shows the header, the clues, and the guesses`() {
        assertEquals(DEFAULT_TEXT, Undead.textFormat(defaultState()))

        // The interior shows the current guesses, not the solution.
        val s = defaultState()
        val g = Undead.executeMove(s, "G5") as Undead.State
        assertEquals(1, g.guess[5])
        assertTrue(Undead.textFormat(g).contains(" 3 . . G / 2"))

        // A pencil mark and a done clue do not change the text.
        val pencil = Undead.executeMove(s, "g5") as Undead.State
        assertEquals(DEFAULT_TEXT, Undead.textFormat(pencil))
        val done = Undead.executeMove(s, "D1,0") as Undead.State
        assertEquals(DEFAULT_TEXT, Undead.textFormat(done))
    }

    @Test
    fun `a clue wider than two digits prints in full`() {
        val s = stateOf(
            "4x4dn",
            "1,5,6,bRdRLbRd,123,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2",
        )
        val lines = Undead.textFormat(s).lines()
        assertEquals("  123 4 2 4  ", lines[2])
    }

    @Test
    fun `a fixed monster cell is drawn from the guess and is not clickable`() {
        val desc = "2,2,12,GVZaaaaaaaaaaaaa,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"
        val s = stateOf("4x4dn", desc)
        assertEquals(16, s.common.numTotal)
        assertTrue(s.common.fixed[0])
        assertTrue(s.common.fixed[1])
        assertTrue(s.common.fixed[2])
        val u = ui()
        assertEquals(MoveResult.Unused, click(s, u, cellX(1), cellY(1)))
        assertFalse(u.hshow)
        val lines = Undead.textFormat(s).lines()
        assertEquals("   0 0 0 0  ", lines[2])
        assertEquals(" 0 G V Z . 0", lines[3])
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `a click selects a floor cell and a second click enters pencil mode`() {
        val s = defaultState()
        val u = ui()
        assertEquals(MoveResult.UiUpdate, click(s, u, cellX(1), cellY(1)))
        assertTrue(u.hshow)
        assertFalse(u.hpencil)
        assertFalse(u.hcursor)
        assertEquals(1, u.hx)
        assertEquals(1, u.hy)
        assertEquals(MoveResult.UiUpdate, click(s, u, cellX(1), cellY(1)))
        assertTrue(u.hpencil)
        // The Android branch keeps the highlight on a different cell.
        assertEquals(MoveResult.UiUpdate, click(s, u, cellX(2), cellY(1)))
        assertTrue(u.hshow)
        assertTrue(u.hpencil)
        assertEquals(2, u.hx)
    }

    @Test
    fun `a right click on an unknown cell enters pencil mode`() {
        val s = defaultState()
        val u = ui()
        assertEquals(MoveResult.UiUpdate, click(s, u, cellX(1), cellY(1), Buttons.RIGHT_BUTTON))
        assertTrue(u.hshow)
        assertTrue(u.hpencil)
        assertEquals(MoveResult.UiUpdate, click(s, u, cellX(1), cellY(1), Buttons.RIGHT_BUTTON))
        assertFalse(u.hshow)
        assertFalse(u.hpencil)
        assertEquals(0, u.hx)
        assertEquals(0, u.hy)
    }

    @Test
    fun `a click on a mirror or the letterbox is inert`() {
        val s = defaultState()
        // (1,6) is a left mirror in the padded grid; find a real one.
        val mirror = firstMirrorCell(s)
        val u = ui()
        assertEquals(MoveResult.Unused, click(s, u, cellX(mirror.first), cellY(mirror.second)))
        assertFalse(u.hshow)

        u.hshow = true
        u.hx = 1
        u.hy = 1
        assertEquals(MoveResult.UiUpdate, click(s, u, 0, 0))
        assertFalse(u.hshow)
    }

    @Test
    fun `a clue click toggles the mark and a corner clears the highlight`() {
        val s = defaultState()
        val u = ui()
        assertEquals(MoveResult.Move("D1,0"), click(s, u, cellX(1), cellY(0)))
        assertEquals(MoveResult.Move("D0,1"), click(s, u, cellX(0), cellY(1), Buttons.RIGHT_BUTTON))
        u.hshow = true
        u.hx = 1
        u.hy = 1
        assertEquals(MoveResult.UiUpdate, click(s, u, cellX(0), cellY(0)))
        assertFalse(u.hshow)
    }

    @Test
    fun `the count row uses the drawing geometry`() {
        for (tile in listOf(48, 63, 82, 100)) {
            val s = defaultState()
            val u = ui()
            u.hshow = true
            u.hx = 1
            u.hy = 1
            val geom = Undead.countsGeometry(tile, 4)
            val cy = (geom.y1 + geom.y2) / 2
            assertEquals(
                MoveResult.Move("G0"),
                click(s, u, (geom.ghostX1 + geom.ghostX2) / 2, cy, tile = tile),
            )
            u.hshow = true
            assertEquals(
                MoveResult.Move("V0"),
                click(s, u, (geom.vampireX1 + geom.vampireX2) / 2, cy, tile = tile),
            )
            u.hshow = true
            assertEquals(
                MoveResult.Move("Z0"),
                click(s, u, (geom.zombieX1 + geom.zombieX2) / 2, cy, tile = tile),
            )
        }
    }

    @Test
    fun `the keyboard sets, toggles, and clears`() {
        val s = defaultState()
        val u = ui()
        assertEquals(MoveResult.UiUpdate, click(s, u, 0, 0, Buttons.CURSOR_DOWN))
        assertTrue(u.hshow)
        assertTrue(u.hcursor)
        assertEquals(1, u.hx)
        assertEquals(1, u.hy)
        assertEquals(MoveResult.Move("G0"), click(s, u, 0, 0, 'G'.code))
        assertEquals(MoveResult.Move("V0"), click(s, u, 0, 0, 'v'.code))
        assertEquals(MoveResult.Move("Z0"), click(s, u, 0, 0, '3'.code))
        // A second select enters pencil mode.
        assertEquals(MoveResult.UiUpdate, click(s, u, 0, 0, Buttons.CURSOR_SELECT))
        assertTrue(u.hpencil)
        assertEquals(MoveResult.Move("g0"), click(s, u, 0, 0, 'g'.code))
        assertEquals(MoveResult.Move("v0"), click(s, u, 0, 0, 'V'.code))
        assertEquals(MoveResult.Move("z0"), click(s, u, 0, 0, 'z'.code))
        // The clear key needs something to clear.
        val cleared = Undead.executeMove(s, "g0") as Undead.State
        assertEquals(MoveResult.Move("E0"), Undead.interpretMove(cleared, u, 0, 0, 'e'.code, TILE))
    }

    @Test
    fun `a repeated monster key is unused with a keyboard cursor`() {
        val s = defaultState()
        val u = ui()
        click(s, u, 0, 0, Buttons.CURSOR_DOWN)
        assertEquals(MoveResult.Move("G0"), click(s, u, 0, 0, 'G'.code))
        // The keyboard cursor returns NULL for a repeated key.
        val set = Undead.executeMove(s, "G0") as Undead.State
        assertEquals(MoveResult.Unused, Undead.interpretMove(set, u, 0, 0, 'G'.code, TILE))

        // A mouse highlight returns a UI update instead.
        val mouse = ui()
        click(s, mouse, cellX(1), cellY(1))
        assertEquals(MoveResult.UiUpdate, Undead.interpretMove(set, mouse, 0, 0, 'G'.code, TILE))
    }

    @Test
    fun `the count click is one move and changes the guess`() {
        val s = defaultState()
        val u = ui()
        click(s, u, cellX(1), cellY(1))
        val geom = Undead.countsGeometry(TILE, 4)
        val move = click(
            s,
            u,
            (geom.ghostX1 + geom.ghostX2) / 2,
            (geom.y1 + geom.y2) / 2,
        ) as MoveResult.Move
        val after = Undead.executeMove(s, move.text) as Undead.State
        assertEquals(1, after.guess[0])
    }

    @Test
    fun `a modified cursor key is not a cursor move`() {
        val s = defaultState()
        val u = ui()
        assertEquals(
            MoveResult.Unused,
            click(s, u, 0, 0, Buttons.CURSOR_DOWN or Buttons.MOD_SHFT),
        )
        assertFalse(u.hshow)
        assertFalse(u.hcursor)
    }

    @Test
    fun `the ascii key toggles only the UI`() {
        val s = defaultState()
        val u = ui()
        assertFalse(u.ascii)
        assertEquals(MoveResult.UiUpdate, click(s, u, 0, 0, 'a'.code))
        assertTrue(u.ascii)
        assertEquals(MoveResult.UiUpdate, click(s, u, 0, 0, 'A'.code))
        assertFalse(u.ascii)
    }

    @Test
    fun `executeMove accepts the token grammar`() {
        val s = defaultState()
        assertEquals(1, (Undead.executeMove(s, "G0") as Undead.State).guess[0])
        assertEquals(2, (Undead.executeMove(s, "V0") as Undead.State).guess[0])
        assertEquals(4, (Undead.executeMove(s, "Z0") as Undead.State).guess[0])
        assertEquals(7, (Undead.executeMove(s, "E0") as Undead.State).guess[0])
        assertEquals(1, (Undead.executeMove(s, "g0") as Undead.State).pencils[0])
        assertEquals(2, (Undead.executeMove(s, "v0") as Undead.State).pencils[0])
        assertEquals(4, (Undead.executeMove(s, "z0") as Undead.State).pencils[0])
        assertEquals(7, (Undead.executeMove(s, "M") as Undead.State).pencils[0])
        assertTrue((Undead.executeMove(s, "D1,0") as Undead.State).hintsDone[0])
        // The separator is optional and a leading separator is not.
        val two = Undead.executeMove(s, "G0V1") as Undead.State
        assertEquals(1, two.guess[0])
        assertEquals(2, two.guess[1])
        assertNull(Undead.executeMove(s, ";G0"))
        assertNull(Undead.executeMove(s, "G0;;V1"))
        assertNotNull(Undead.executeMove(s, ""))
        // `%d` skips white space and accepts a sign, and a trailing
        // character rejects the move.
        assertEquals(1, (Undead.executeMove(s, "G 3") as Undead.State).guess[3])
        assertEquals(1, (Undead.executeMove(s, "G+3") as Undead.State).guess[3])
        assertEquals(1, (Undead.executeMove(s, "G03") as Undead.State).guess[3])
        assertEquals(null, Undead.executeMove(s, "G-1"))
        assertEquals(null, Undead.executeMove(s, "G3x"))
        assertEquals(null, Undead.executeMove(s, "G99999999999"))
        assertEquals(null, Undead.executeMove(s, "D0,0"))
        assertEquals(null, Undead.executeMove(s, "D5,5"))
    }

    @Test
    fun `an S move is cheated and a real win is solved`() {
        val s = defaultState()
        val cheated = Undead.executeMove(s, DEFAULT_SOLVE) as Undead.State
        assertTrue(cheated.cheated)
        assertFalse(cheated.solved)
        assertEquals(0, Undead.status(cheated))

        val win = DEFAULT_SOLVE.removePrefix("S;")
        val won = Undead.executeMove(s, win) as Undead.State
        assertTrue(won.solved)
        assertFalse(won.cheated)
        assertEquals(1, Undead.status(won))

        // Solved and cheated are sticky.
        val after = Undead.executeMove(won, "E0") as Undead.State
        assertTrue(after.solved)
        assertFalse(after.cheated)
        val stillCheated = Undead.executeMove(cheated, "M") as Undead.State
        assertTrue(stillCheated.cheated)
    }

    @Test
    fun `the error markers follow the C tests`() {
        val s = defaultState()
        // Six ghosts already exceed the one the board allows.
        var wrong = s
        for (i in listOf(0, 1, 2, 3, 4, 5)) {
            wrong = Undead.executeMove(wrong, "G$i") as Undead.State
        }
        assertTrue(wrong.countErrors[0])
        assertFalse(wrong.countErrors[1])
        assertFalse(wrong.countErrors[2])
        assertTrue(wrong.cellErrors.any { it })

        // A wrong clue is a hint error and marks the path cells.
        val clueBad = Undead.executeMove(s, "G0") as Undead.State
        // Cell 0 is a zombie in the solution, so a ghost makes at least
        // one path impossible.
        assertTrue(clueBad.hintErrors.any { it })
        assertTrue(clueBad.cellErrors.any { it })

        val won = Undead.executeMove(s, DEFAULT_SOLVE.removePrefix("S;")) as Undead.State
        assertFalse(won.countErrors.any { it })
        assertFalse(won.hintErrors.any { it })
        assertFalse(won.cellErrors.any { it })
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    @Test
    fun `the solver payload is the C payload`() {
        assertEquals(DEFAULT_SOLVE, Undead.solve(defaultState(), null))
        val attempt = Undead.solveAttempt(defaultState(), null)
        assertEquals(DEFAULT_SOLVE, attempt.move)
        assertNull(attempt.error)
    }

    @Test
    fun `a wrong move does not change the solver payload`() {
        val wrong = Undead.executeMove(defaultState(), "V0") as Undead.State
        assertEquals(2, wrong.guess[0])
        assertEquals(DEFAULT_SOLVE, Undead.solve(wrong, null))
    }

    @Test
    fun `the two solve errors are the C strings`() {
        val inconsistent = stateOf("4x4dn", INCONSISTENT_DESC)
        assertEquals(
            "Puzzle is inconsistent",
            Undead.solveAttempt(inconsistent, null).error,
        )
        assertNull(Undead.solve(inconsistent, null))

        val unsolvable = stateOf("4x4dt", UNSOLVABLE_DESC)
        assertEquals(
            "Puzzle is unsolvable",
            Undead.solveAttempt(unsolvable, null).error,
        )
        assertNull(Undead.solve(unsolvable, null))
    }

    @Test
    fun `a solved board solves to the same payload and ignores AUXINFO`() {
        val won = Undead.executeMove(
            defaultState(),
            DEFAULT_SOLVE.removePrefix("S;"),
        ) as Undead.State
        assertEquals(DEFAULT_SOLVE, Undead.solve(won, null))
        assertEquals(DEFAULT_SOLVE, Undead.solve(won, "ignored"))
        assertEquals(DEFAULT_SOLVE, Undead.solve(defaultState(), "ignored"))
    }

    @Test
    fun `the path arrays hold a long path`() {
        val s = defaultState()
        for (path in s.common.paths) {
            assertTrue(path.length <= 4 * s.common.wh, "path $path exceeds the state space")
            assertEquals(4 * s.common.wh, path.p.size)
            assertEquals(4 * s.common.wh, path.xy.size)
            assertEquals(4 * s.common.wh, path.mapping.size)
        }
        // 22 cells is longer than `w*h`, and the unpadded C buffer of 16
        // entries would lose it. The padded C buffer and the port hold it.
        val long = stateOf(
            "4x4dn",
            "0,0,4,RaRLLLaRRaaLLRLR" + ",0".repeat(16),
        )
        assertEquals(22, long.common.paths.maxOf { it.length })
        assertEquals(16, long.common.params.w * long.common.params.h)
    }

    // ------------------------------------------------------------------
    // UI, keys, and preferences.
    // ------------------------------------------------------------------

    @Test
    fun `the requested keys are the five C labels`() {
        for (p in Undead.presets().map { it.params }) {
            val request = Undead.requestKeys(Undead.decodeParams(p))
            assertEquals(listOf(71, 86, 90, 8, 77), request.labels.map { it.button })
            assertEquals(
                listOf("Ghost", "Vampire", "Zombie", "Clear", "Mark"),
                request.labels.map { it.name },
            )
            assertTrue(request.labels.none { it.needsArrows })
            assertEquals(ArrowMode.ARROWS_LEFT, request.arrowMode)
        }
    }

    @Test
    fun `the current key label follows the C branches`() {
        val s = defaultState()
        val u = ui()
        assertEquals("", Undead.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        u.hshow = true
        u.hx = 1
        u.hy = 1
        assertEquals("Pencil", Undead.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        u.hpencil = true
        assertEquals("Ink", Undead.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("Clear", Undead.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))

        // The hidden-highlight quirk: C reads xinfo without hshow.
        u.hshow = false
        assertEquals("Clear", Undead.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        // A mirror and a fixed cell have no clear label.
        val mirror = firstMirrorCell(s)
        u.hx = mirror.first
        u.hy = mirror.second
        assertEquals("", Undead.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        val fixed = stateOf(
            "4x4dn",
            "2,2,12,GVZaaaaaaaaaaaaa,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0",
        )
        u.hx = 1
        u.hy = 1
        assertEquals("", Undead.currentKeyLabel(u, fixed, Buttons.CURSOR_SELECT2))
        assertEquals("", Undead.currentKeyLabel(u, s, 'G'.code))
    }

    @Test
    fun `setCursorVisible moves an unset cursor to the first cell`() {
        val u = ui()
        Undead.setCursorVisible(u, true)
        assertTrue(u.hshow)
        assertEquals(1, u.hx)
        assertEquals(1, u.hy)
        val rect = Undead.cursorLocation(u, defaultState(), TILE)
        assertEquals(Game.CursorRect(16 + 64, 16 + 2 * 64, 64, 64), rect)
        Undead.setCursorVisible(u, false)
        assertNull(Undead.cursorLocation(u, defaultState(), TILE))
    }

    @Test
    fun `changedState hides a filled pencil highlight and reports a win`() {
        val s = defaultState()
        val u = ui()
        u.hshow = true
        u.hpencil = true
        u.hcursor = false
        u.hx = 1
        u.hy = 1
        // Cell (1,1) is monster 0.
        val filled = Undead.executeMove(s, "G0") as Undead.State
        assertFalse(Undead.changedState(u, s, filled))
        assertFalse(u.hshow)

        // A move that does not fill the highlighted cell keeps it.
        val u2 = ui()
        u2.hshow = true
        u2.hpencil = true
        u2.hcursor = false
        u2.hx = 1
        u2.hy = 1
        val elsewhere = Undead.executeMove(s, "G1") as Undead.State
        assertFalse(Undead.changedState(u2, s, elsewhere))
        assertTrue(u2.hshow)

        // A keyboard highlight is not cancelled.
        val u3 = ui()
        u3.hshow = true
        u3.hpencil = true
        u3.hcursor = true
        u3.hx = 1
        u3.hy = 1
        Undead.changedState(u3, s, filled)
        assertTrue(u3.hshow)

        val won = Undead.executeMove(s, DEFAULT_SOLVE.removePrefix("S;")) as Undead.State
        val u4 = ui()
        assertTrue(Undead.changedState(u4, s, won))
        // A solve is cheated, so it is not a win.
        val cheated = Undead.executeMove(s, DEFAULT_SOLVE) as Undead.State
        assertFalse(Undead.changedState(ui(), s, cheated))
        // No previous state never completes.
        assertFalse(Undead.changedState(ui(), null, won))
    }

    @Test
    fun `the two preferences have the C defaults and are UI state`() {
        val u = ui()
        assertTrue(u.pencilKeepHighlight)
        assertFalse(u.ascii)

        // The preference changes the input: a mouse highlight is hidden
        // after a monster key only when it is false.
        val s = defaultState()
        val kept = ui()
        click(s, kept, cellX(1), cellY(1))
        assertEquals(MoveResult.Move("G0"), click(s, kept, 0, 0, 'G'.code))
        assertTrue(kept.hshow)

        val hidden = ui()
        hidden.pencilKeepHighlight = false
        click(s, hidden, cellX(1), cellY(1))
        assertEquals(MoveResult.Move("G0"), click(s, hidden, 0, 0, 'G'.code))
        assertFalse(hidden.hshow)
    }

    // ------------------------------------------------------------------
    // Palette, geometry, and drawstate.
    // ------------------------------------------------------------------

    @Test
    fun `the palette matches the measured oracle triples`() {
        assertEquals(
            listOf(
                800f, 800f, 800f,
                0f, 0f, 0f,
                0f, 0f, 0f,
                1000f, 0f, 0f,
                624f, 624f, 624f,
                1000f, 1000f, 1000f,
                0f, 0f, 0f,
                1000f, 1000f, 1000f,
                400f, 800f, 800f,
                400f, 800f, 400f,
                800f, 720f, 720f,
                533f, 533f, 533f,
            ),
            Undead.colours().map { (it * 1000f).roundToInt().toFloat() },
        )
    }

    @Test
    fun `the monster colours use the red background channel`() {
        val palette = Undead.colours(floatArrayOf(0.6f, 0.2f, 0.1f))
        fun colour(index: Int) = listOf(
            palette[index * 3],
            palette[index * 3 + 1],
            palette[index * 3 + 2],
        )
        assertEquals(listOf(0.3f, 0.6f, 0.6f), colour(Undead.COL_GHOST))
        assertEquals(listOf(0.3f, 0.6f, 0.3f), colour(Undead.COL_ZOMBIE))
        assertEquals(listOf(0.6f, 0.6f * 0.9f, 0.6f * 0.9f), colour(Undead.COL_VAMPIRE))
        assertEquals(
            listOf(0.78f * 0.6f, 0.78f * 0.2f, 0.78f * 0.1f),
            colour(Undead.COL_HIGHLIGHT),
        )
        assertEquals(
            listOf(0.6f / 1.5f, 0.2f / 1.5f, 0.1f / 1.5f),
            colour(Undead.COL_DONE),
        )
    }

    @Test
    fun `the sizes match the C formula`() {
        assertEquals(Size(416, 480), Undead.computeSize(Undead.Params(4, 4, 1), 64))
        for ((params, tile, size) in listOf(
            Triple("4x4", 48, Size(312, 360)),
            Triple("4x4", 63, Size(408, 471)),
            Triple("4x4", 82, Size(532, 614)),
            Triple("4x4", 100, Size(650, 750)),
            Triple("5x5", 48, Size(360, 408)),
            Triple("7x7", 100, Size(950, 1050)),
        )) {
            assertEquals(size, Undead.computeSize(Undead.decodeParams(params), tile))
        }
    }

    @Test
    fun `the drawstate starts with the C caches`() {
        val s = defaultState()
        val ds = Undead.newDrawState(s, TILE, NoSink) as Undead.DrawState
        assertFalse(ds.started)
        assertEquals(4, ds.w)
        assertEquals(4, ds.h)
        assertTrue(ds.monsters.all { it == 7 })
        assertTrue(ds.pencils.all { it == 0 })
        assertTrue(ds.countErrors.none { it })
        assertTrue(ds.cellErrors.none { it })
        assertTrue(ds.hintErrors.none { it })
        assertTrue(ds.hintsDone.none { it })
        assertFalse(ds.hshow)
        assertFalse(ds.hpencil)
        assertFalse(ds.ascii)
        assertNull(ds.counts)
    }

    @Test
    fun `a copy shares the common and copies the state arrays`() {
        val s = defaultState()
        val copy = s.copy() as Undead.State
        assertTrue(s == copy)
        assertEquals(s.hashCode(), copy.hashCode())
        assertNotEquals(0, s.common.paths.size)
        // The common is the same object; the arrays are separate.
        assertTrue(s.common === copy.common)
        assertFalse(s.guess === copy.guess)
        val moved = Undead.executeMove(s, "G0") as Undead.State
        assertEquals(7, copy.guess[0])
        assertEquals(1, moved.guess[0])
        assertNotEquals(moved, copy)
        assertEquals(s, Undead.executeMove(moved, "E0"))
    }

    private fun firstMirrorCell(state: Undead.State): Pair<Int, Int> {
        val w = state.common.params.w
        for (y in 1..state.common.params.h) {
            for (x in 1..w) {
                if (state.common.xinfo[x + y * (w + 2)] == -1) return x to y
            }
        }
        error("the board has no mirror")
    }

    /** A sink that draws nothing, for the pure drawstate tests. */
    private object NoSink : DrawSink {
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun thickLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            thickness: Float,
            colour: Int,
        ) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {}
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {}
        override fun status(text: String) {}
        override fun inertiaFollow(isSolved: Boolean) {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
