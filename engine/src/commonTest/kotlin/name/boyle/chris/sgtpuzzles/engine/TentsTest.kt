package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: tents.
 *
 * Kotlin-only pins for the parameter quirks, the description decoder,
 * the text format, the move grammar, the click cycles, the cursor and
 * the key labels, the solver error strings, and the drawing shape.
 *
 * The generation attempt counts come from a C counter probe built from
 * a scratch copy of `tents.c` outside the repository, with one
 * increment at the top of the generator loop. They pin the retry
 * accounting that the oracle protocol does not expose.
 */
class TentsTest {

    private companion object {
        const val TILE = 32
        const val SEED = "12345"

        const val GOLDEN_DESC =
            "ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"

        const val SOLVE =
            "S;T0,0;T6,0;T2,1;T0,2;T6,2;T3,3;T1,4;T5,5;T0,6;T2,7;T4,7;T7,7"
    }

    private fun params(text: String): Tents.Params =
        Tents.decodeParams(text) as Tents.Params

    private fun stateOf(paramsText: String, desc: String): Tents.State =
        Tents.newState(params(paramsText), desc) as Tents.State

    private fun descOf(paramsText: String, seed: String): String =
        Tents.newDesc(params(paramsText), RandomState.fromSeed(seed)).text

    private fun ui(): Tents.Ui = Tents.Ui()

    private fun click(state: Tents.State, ui: Tents.Ui, x: Int, y: Int, button: Int): MoveResult =
        Tents.interpretMove(state, ui, x, y, button, TILE)

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `defaults and presets match the C tables`() {
        assertEquals(Tents.Params(8, 8, 0), Tents.defaultParams())
        val presets = Tents.presets()
        assertEquals(6, presets.size)
        assertEquals(
            listOf("8x8de", "8x8dt", "10x10de", "10x10dt", "15x15de", "15x15dt"),
            presets.map { it.params },
        )
        assertEquals(
            listOf("8x8 Easy", "8x8 Tricky", "10x10 Easy", "10x10 Tricky",
                "15x15 Easy", "15x15 Tricky"),
            presets.map { it.title },
        )
        assertEquals(32, Tents.preferredTileSize)
        assertFalse(Tents.wantsStatusBar)
        assertTrue(Tents.canSolve)
        assertTrue(Tents.canFormatAsText)
    }

    @Test
    fun `decode follows atoi and the digit loop`() {
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8d"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8dz"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8x8"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("+8"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8X8"))
        assertEquals(Tents.Params(8, 9, 0), Tents.decodeParams("08x09"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8 "))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8d0"))
        assertEquals(Tents.Params(8, 8, 0), Tents.decodeParams("8x8q"))
        assertEquals(Tents.Params(8, 8, 1), Tents.decodeParams("8x8dt"))
        assertEquals(Tents.Params(8, 8, 1), Tents.decodeParams("8x8dtjunk"))
        assertEquals(Tents.Params(8, 0, 0), Tents.decodeParams("8x"))
        assertEquals(Tents.Params(0, 8, 0), Tents.decodeParams("0x8"))
        // C `atoi` overflow is undefined; the port saturates.
        assertEquals(
            Tents.Params(Int.MAX_VALUE, 8, 0),
            Tents.decodeParams("99999999999x8"),
        )
    }

    @Test
    fun `encode drops the difficulty in the short form`() {
        val p = Tents.Params(10, 10, 1)
        assertEquals("10x10", Tents.encodeParams(p, false))
        assertEquals("10x10dt", Tents.encodeParams(p, true))
        assertEquals("8x8de", Tents.encodeParams(Tents.defaultParams(), true))
    }

    @Test
    fun `validation follows the C order and messages`() {
        for (p in listOf(
            Tents.Params(3, 8, 0),
            Tents.Params(8, 3, 0),
            Tents.Params(0, 8, 0),
            Tents.Params(8, 0, 0),
        )) {
            assertEquals(
                "Width and height must both be at least four",
                Tents.validateParams(p, true),
            )
        }
        assertEquals(
            "Width times height must not be unreasonably large",
            Tents.validateParams(Tents.Params(65536, 65536, 0), true),
        )
        // The C boundary: 46340 <= (INT_MAX - 1) / 46340.
        assertEquals(null, Tents.validateParams(Tents.Params(46340, 46340, 0), true))
        assertEquals(
            "Width times height must not be unreasonably large",
            Tents.validateParams(Tents.Params(46341, 46341, 0), true),
        )
        assertEquals(null, Tents.validateParams(Tents.Params(4, 4, 1), false))
    }

    @Test
    fun `validateDesc uses the exact messages in order`() {
        val p = params("8x8")
        fun check(desc: String) = Tents.validateDesc(p, desc)
        assertEquals(
            "Tent or non-tent placed off the grid",
            check("!".repeat(1) + ",0".repeat(16)),
        )
        assertEquals(
            "Invalid character in grid specification",
            check("?")
        )
        assertEquals("Not enough data to fill grid", check("_"))
        assertEquals("Too much data to fit in grid", check("_".repeat(66)))
        assertEquals(
            "Not enough numbers given after grid specification",
            check("_".repeat(65)),
        )
        assertEquals(
            "Invalid character in number list",
            check("_".repeat(65) + ",0".repeat(14) + ",x"),
        )
        assertEquals(
            "Unexpected additional data at end of game description",
            check("_".repeat(65) + ",0".repeat(16) + "x"),
        )
        assertEquals(null, check("_".repeat(65) + ",".repeat(16)))
        // The `-` run needs a previous cell.
        assertEquals(
            "Tent or non-tent placed off the grid",
            check("-" + "_".repeat(64) + ",0".repeat(16)),
        )
    }

    @Test
    fun `newState decodes the grid and the clues`() {
        val s = stateOf("8x8de", GOLDEN_DESC)
        assertEquals(8, s.width)
        assertEquals(8, s.height)
        assertEquals(0, s.diff)
        assertFalse(s.completed)
        assertFalse(s.cheated)
        assertEquals(Tents.TREE, s.grid[1])
        assertEquals(Tents.BLANK, s.grid[0])
        assertEquals(Tents.BLANK, s.grid[16])
        assertEquals(
            listOf(3, 1, 2, 1, 1, 1, 2, 1, 2, 1, 2, 1, 1, 1, 1, 3),
            s.shared.numbers.toList(),
        )
        assertEquals(GOLDEN_DESC.substringBefore(','), encodeGrid(s.grid))

        // A number beyond the int range saturates, as the port defines;
        // C `atoi` is undefined there.
        val huge = stateOf(
            "8x8de",
            "_".repeat(65) + ",99999999999" + ",0".repeat(15),
        )
        assertEquals(Int.MAX_VALUE, huge.shared.numbers[0])
    }

    @Test
    fun `the two C aborts are defined exceptions`() {
        val p = params("8x8de")
        val zeros = ",0".repeat(16)
        for ((desc, message) in listOf(
            "ddddddddz$zeros" to "tents: the description runs past the grid",
            "dddddddcz_$zeros" to "tents: the description ends on a non-tree cell",
        )) {
            assertEquals(null, Tents.validateDesc(p, desc))
            val error = try {
                Tents.newState(p, desc)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals(message, error)
        }
    }

    @Test
    fun `copy shares the immutable data and equals the value`() {
        val s = stateOf("8x8de", GOLDEN_DESC)
        val copy = s.copy() as Tents.State
        assertSame(s.shared, copy.shared)
        assertEquals(s, copy)
        assertEquals(s.hashCode(), copy.hashCode())
        copy.grid[0] = Tents.TENT
        assertEquals(Tents.BLANK, s.grid[0])
        assertFalse(s == copy)
    }

    // ------------------------------------------------------------------
    // Text.
    // ------------------------------------------------------------------

    @Test
    fun `textFormat matches the C rectangle and the digit rules`() {
        val text = Tents.textFormat(stateOf("8x8de", GOLDEN_DESC))
        assertEquals(721, text.length)
        assertTrue(text.startsWith("+---+---+---+---+---+---+---+---+---+\n"))
        assertTrue(text.contains("| . | T | . | . | . | . | . | T | 2 |\n"))
        assertTrue(text.contains("| 3 | 1 | 2 | 1 | 1 | 1 | 2 | 1 |    \n"))
        assertTrue(text.endsWith("+---+---+---+---+---+---+---+---+   \n"))

        // A tent is the three-character glyph `//\`; a non-tent is blank.
        val state = stateOf("8x8de", GOLDEN_DESC)
        val tent = Tents.executeMove(state, "T0,0") as Tents.State
        assertTrue(Tents.textFormat(tent).lines()[1].startsWith("|//\\|"))
        val nonTent = Tents.executeMove(tent, "N0,0") as Tents.State
        assertTrue(Tents.textFormat(nonTent).lines()[1].startsWith("|   |"))

        // 100 and 999 are written; 1000 is left blank.
        val desc = "_".repeat(65) + ",100,999,1000" + ",0".repeat(13)
        val wide = stateOf("8x8de", desc)
        val row = Tents.textFormat(wide).lines()[17]
        assertTrue(row.contains("100"))
        assertTrue(row.contains("999"))
        assertFalse(row.contains("1000"))
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar accepts the C forms and rejects the rest`() {
        val start = stateOf("8x8de", GOLDEN_DESC)
        for (text in listOf("T0,0", "T+0,0", "T00,00", "T0, 0", "T0,0;", "B0,0;T0,0", "S", "S;T0,0")) {
            assertNotNull(Tents.executeMove(start, text), "'$text'")
        }
        for (text in listOf(
            "T1,0", "T-1,0", "T0,0junk", ";T0,0", "T0,0T1,1", "T0,0,", "T0,0;;T2,0",
        )) {
            assertNull(Tents.executeMove(start, text), "'$text'")
        }

        // The last write wins, and `S` clears only non-tree cells.
        val doubled = Tents.executeMove(start, "B0,0;T0,0") as Tents.State
        assertEquals(Tents.TENT, doubled.grid[0])
        val cleared = Tents.executeMove(doubled, "S") as Tents.State
        assertEquals(Tents.NONTENT, cleared.grid[0])
        assertEquals(Tents.TREE, cleared.grid[1])
        assertTrue(cleared.cheated)

        // `%d` overflow is defined: 2^32 wraps to 0 and is accepted;
        // a value beyond the long range saturates and is rejected.
        assertEquals(Tents.TENT, (Tents.executeMove(start, "T4294967296,0") as Tents.State).grid[0])
        assertNull(Tents.executeMove(start, "T99999999999999999999,0"))
        assertNull(Tents.executeMove(start, "T0,99999999999999999999"))
    }

    @Test
    fun `stylus clicks cycle in three states`() {
        var state = stateOf("8x8de", GOLDEN_DESC)
        var u = ui()

        fun cycle(button: Int): MoveResult {
            assertEquals(MoveResult.UiUpdate, click(state, u, 32, 32, button))
            val result = click(
                state,
                u,
                32,
                32,
                if (button == Buttons.LEFT_BUTTON) Buttons.LEFT_RELEASE else Buttons.RIGHT_RELEASE,
            )
            if (result is MoveResult.Move) {
                state = Tents.executeMove(state, result.text) as Tents.State
            }
            return result
        }

        // Left: BLANK -> TENT -> NONTENT -> BLANK.
        assertEquals(MoveResult.Move("T0,0"), cycle(Buttons.LEFT_BUTTON))
        assertEquals(MoveResult.Move("N0,0"), cycle(Buttons.LEFT_BUTTON))
        assertEquals(MoveResult.Move("B0,0"), cycle(Buttons.LEFT_BUTTON))

        // Right: BLANK -> NONTENT -> TENT -> BLANK.
        assertEquals(MoveResult.Move("N0,0"), cycle(Buttons.RIGHT_BUTTON))
        assertEquals(MoveResult.Move("T0,0"), cycle(Buttons.RIGHT_BUTTON))
        assertEquals(MoveResult.Move("B0,0"), cycle(Buttons.RIGHT_BUTTON))

        // A tree never changes.
        u = ui()
        click(state, u, 64, 32, Buttons.LEFT_BUTTON)
        assertEquals(MoveResult.UiUpdate, click(state, u, 64, 32, Buttons.LEFT_RELEASE))
    }

    @Test
    fun `a drag locks to the longer axis and is one move`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        val u = ui()
        assertEquals(MoveResult.UiUpdate, click(state, u, 32, 32, Buttons.LEFT_BUTTON))
        assertEquals(MoveResult.UiUpdate, click(state, u, 96, 96, Buttons.LEFT_DRAG))
        assertEquals(MoveResult.Move("N0,0;N2,0"), click(state, u, 96, 96, Buttons.LEFT_RELEASE))

        // A release outside cancels.
        val cancelled = ui()
        click(state, cancelled, 32, 32, Buttons.LEFT_BUTTON)
        assertEquals(MoveResult.UiUpdate, click(state, cancelled, 400, 32, Buttons.LEFT_DRAG))
        assertEquals(MoveResult.UiUpdate, click(state, cancelled, 400, 32, Buttons.LEFT_RELEASE))

        // An empty drag (a click on a tree) produces no move.
        val tree = ui()
        click(state, tree, 64, 32, Buttons.LEFT_BUTTON)
        assertEquals(MoveResult.UiUpdate, click(state, tree, 64, 32, Buttons.LEFT_RELEASE))
    }

    @Test
    fun `cursor keys and the select cycles`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        val u = ui()
        assertEquals(MoveResult.UiUpdate, click(state, u, 0, 0, Buttons.CURSOR_UP))
        assertTrue(u.cdisp)
        assertEquals(MoveResult.NoEffect, click(state, u, 0, 0, Buttons.CURSOR_UP))

        // Shift-right marks the old blank cell and skips the tree.
        val shifted = ui()
        assertEquals(MoveResult.Move("N0,0"), click(state, shifted, 0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT))
        assertEquals(1, shifted.cx)
        assertTrue(shifted.cdisp)

        // Ctrl-right marks the old tent, not the tree.
        val tent = Tents.executeMove(state, "T0,0") as Tents.State
        val control = ui()
        control.cdisp = true
        assertEquals(MoveResult.Move("N0,0"), click(tent, control, 0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT))

        // A hidden select only shows the cursor.
        val hidden = ui()
        assertEquals(MoveResult.Unused, click(state, hidden, 0, 0, 'T'.code))
        assertEquals(MoveResult.UiUpdate, click(state, hidden, 0, 0, Buttons.CURSOR_SELECT))
        assertTrue(hidden.cdisp)
        assertEquals(MoveResult.Move("T0,0"), click(state, hidden, 0, 0, Buttons.CURSOR_SELECT))
        assertEquals(MoveResult.Move("N0,0"), click(state, hidden, 0, 0, Buttons.CURSOR_SELECT2))
        assertEquals(MoveResult.Move("N0,0"), click(state, hidden, 0, 0, 'N'.code))
        assertEquals(MoveResult.Move("B0,0"), click(state, hidden, 0, 0, 'B'.code))
        assertEquals(MoveResult.Move("T0,0"), click(state, hidden, 0, 0, 'T'.code))
        assertEquals(MoveResult.Unused, click(state, hidden, 0, 0, Buttons.MIDDLE_BUTTON))
    }

    @Test
    fun `currentKeyLabel covers every cell and both select buttons`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        val u = ui()
        assertEquals("", Tents.currentKeyLabel(u, state, Buttons.CURSOR_SELECT))
        u.cdisp = true
        assertEquals("Tent", Tents.currentKeyLabel(u, state, Buttons.CURSOR_SELECT))
        assertEquals("Green", Tents.currentKeyLabel(u, state, Buttons.CURSOR_SELECT2))
        assertEquals("", Tents.currentKeyLabel(u, state, 'T'.code))

        val tent = Tents.executeMove(state, "T0,0") as Tents.State
        assertEquals("Clear", Tents.currentKeyLabel(u, tent, Buttons.CURSOR_SELECT))
        assertEquals("Clear", Tents.currentKeyLabel(u, tent, Buttons.CURSOR_SELECT2))
        assertEquals(
            "",
            Tents.currentKeyLabel(u, tent, Buttons.MOD_CTRL or Buttons.CURSOR_SELECT),
        )

        val nonTent = Tents.executeMove(state, "N0,0") as Tents.State
        assertEquals("Clear", Tents.currentKeyLabel(u, nonTent, Buttons.CURSOR_SELECT))

        // The tree at (1,0) has no label.
        u.cx = 1
        assertEquals("", Tents.currentKeyLabel(u, state, Buttons.CURSOR_SELECT))
        assertEquals("", Tents.currentKeyLabel(u, state, Buttons.CURSOR_SELECT2))
    }

    @Test
    fun `completion is sticky and a solve is not a win`() {
        val start = stateOf("8x8de", GOLDEN_DESC)
        var state = start
        for ((x, y) in listOf(
            0 to 0, 6 to 0, 2 to 1, 0 to 2, 6 to 2, 3 to 3,
            1 to 4, 5 to 5, 0 to 6, 2 to 7, 4 to 7, 7 to 7,
        )) {
            state = Tents.executeMove(state, "T$x,$y") as Tents.State
        }
        assertTrue(state.completed)
        assertEquals(1, Tents.status(state))
        assertFalse(state.cheated)

        // One more move keeps the flag.
        val after = Tents.executeMove(state, "B0,0") as Tents.State
        assertTrue(after.completed)
        assertEquals(1, Tents.status(after))

        // A solve sets completed and cheated; changedState ignores it.
        assertTrue(start.completed.not())
        val solved = Tents.executeMove(start, SOLVE) as Tents.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertEquals(1, Tents.status(solved))
        assertFalse(Tents.changedState(ui(), start, solved))
        assertTrue(Tents.changedState(ui(), start, state))
        assertFalse(Tents.changedState(ui(), null, state))
        assertFalse(Tents.changedState(ui(), state, after))
    }

    @Test
    fun `status and statusText are the C values`() {
        val start = stateOf("8x8de", GOLDEN_DESC)
        assertEquals(0, Tents.status(start))
        assertEquals("", Tents.statusText(start, ui()))
        assertEquals(1, Tents.status(Tents.executeMove(start, SOLVE) as Tents.State))
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    @Test
    fun `the solver reproduces the C solve string`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        assertEquals(SOLVE, Tents.solveAttempt(state, null).move)
        assertEquals(SOLVE, Tents.solve(state, null))
    }

    @Test
    fun `the solver reads the initial grid, not the current one`() {
        val start = stateOf("8x8de", GOLDEN_DESC)
        val wrong = Tents.executeMove(start, "T2,0") as Tents.State
        assertEquals(SOLVE, Tents.solveAttempt(wrong, null).move)
    }

    @Test
    fun `the two solver failures use the C messages`() {
        val p = params("8x8de")
        for ((numbers, message) in listOf(
            "8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0" to "This puzzle is not self-consistent",
            "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,8" to "This puzzle is not self-consistent",
            "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1" to
                "Unable to find a unique solution for this puzzle",
        )) {
            val state = Tents.newState(p, "ae__ijcecgbcd,$numbers") as Tents.State
            val attempt = Tents.solveAttempt(state, null)
            assertNull(attempt.move)
            assertEquals(message, attempt.error)
        }
    }

    @Test
    fun `an AUXINFO is returned verbatim`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        assertEquals("S;T0,0", Tents.solve(state, "S;T0,0"))
        assertTrue(Tents.executeMove(state, "S;T0,0") != null)
    }

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    @Test
    fun `generated fixtures and attempt counts match the C probe`() {
        assertEquals(
            "8x8:ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3",
            Midend.newGame(Tents, "8x8de", SEED).id(),
        )
        assertEquals(
            "8x8:_ecb_ijeafb_i,1,2,2,1,2,2,1,1,2,2,1,1,1,2,1,2",
            Midend.newGame(Tents, "8x8dt", "1").id(),
        )
        assertEquals(
            "8x8:_aaoadia_hjaa,2,0,3,0,0,4,0,3,1,2,2,1,0,2,2,2",
            Midend.newGame(Tents, "8x8de", "99999").id(),
        )
        assertEquals(
            "4x4:dde_,2,0,1,0,1,0,1,1",
            Midend.newGame(Tents, "4x4dt", SEED).id(),
        )

        fun attempts(paramsText: String, seed: String): Int =
            Tents.newDescWithAttempts(
                params(paramsText),
                RandomState.fromSeed(seed),
            ).attempts

        // Real C counters, from a scratch probe outside the repository.
        assertEquals(4, attempts("8x8de", SEED))
        assertEquals(2, attempts("8x8dt", SEED))
        assertEquals(2, attempts("10x10de", SEED))
        assertEquals(10, attempts("10x10dt", SEED))
        assertEquals(50, attempts("15x15de", SEED))
        assertEquals(8, attempts("15x15dt", SEED))
        assertEquals(2, attempts("8x8de", "1"))
        assertEquals(14, attempts("8x8dt", "1"))
        assertEquals(2, attempts("8x8de", "99999"))
        assertEquals(4, attempts("8x8dt", "99999"))
        assertEquals(1, attempts("8x8de", "2"))
        assertEquals(10, attempts("8x8dt", "2"))
        assertEquals(3, attempts("8x8de", "3"))
        assertEquals(22, attempts("8x8dt", "3"))
        assertEquals(2, attempts("8x8de", "seed"))
        assertEquals(110, attempts("8x8dt", "seed"))
    }

    @Test
    fun `the generated description carries the C aux answer`() {
        val description = Tents.newDesc(params("8x8de"), RandomState.fromSeed(SEED))
        assertEquals(GOLDEN_DESC, description.text)
        assertEquals(SOLVE, description.auxInfo)
        assertEquals(GOLDEN_DESC, descOf("8x8de", SEED))

        // The answer is the oracle's own `SOLVE` text for the same game.
        val tricky = Tents.newDesc(params("8x8dt"), RandomState.fromSeed(SEED))
        assertEquals(
            "S;T1,0;T4,0;T6,1;T0,2;T2,2;T5,3;T3,4;T7,4;T1,5;T5,6;T1,7;T7,7",
            tricky.auxInfo,
        )
        assertEquals(
            "S;T0,0;T6,0;T2,1;T0,2;T6,2;T3,3;T1,4;T5,5;T0,6;T2,7;T4,7;T7,7",
            Tents.solve(stateOf("8x8de", GOLDEN_DESC), null),
        )
    }

    // ------------------------------------------------------------------
    // Geometry, palette, keys, drawing.
    // ------------------------------------------------------------------

    @Test
    fun `computeSize truncates the two borders separately`() {
        val p = params("8x8de")
        assertEquals(Size(320, 320), Tents.computeSize(p, 32))
        assertEquals(Size(480, 480), Tents.computeSize(p, 48))
        assertEquals(Size(629, 629), Tents.computeSize(p, 63))
        assertEquals(Size(409, 409), Tents.computeSize(p, 41))
        assertEquals(Size(1000, 1000), Tents.computeSize(p, 100))
        assertEquals(Size(408, 408), Tents.computeSize(params("10x10de"), 34))
    }

    @Test
    fun `the palette matches the frozen colour table`() {
        val colours = Tents.colours()
        val expected = listOf(
            intArrayOf(800, 800, 800),
            intArrayOf(0, 0, 0),
            intArrayOf(700, 1000, 500),
            intArrayOf(600, 400, 0),
            intArrayOf(0, 700, 0),
            intArrayOf(800, 700, 0),
            intArrayOf(1000, 0, 0),
            intArrayOf(1000, 1000, 1000),
            intArrayOf(600, 0, 0),
        )
        for ((index, rgb) in expected.withIndex()) {
            for (channel in 0 until 3) {
                assertEquals(
                    rgb[channel],
                    (colours[index * 3 + channel] * 1000.0f).toInt(),
                    "colour $index channel $channel",
                )
            }
        }
        val dark = Tents.colours(floatArrayOf(0.2f, 0.2f, 0.2f))
        assertEquals(200, (dark[0] * 1000.0f).toInt())
        assertEquals(0, (dark[3] * 1000.0f).toInt())
    }

    @Test
    fun `requestKeys changes only the arrow mode with fullCursor`() {
        val p = params("8x8de")
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, Tents.requestKeys(p).arrowMode)
        assertEquals(ArrowMode.ARROWS_ONLY, Tents.requestKeys(p, true).arrowMode)
        assertTrue(Tents.requestKeys(p, false).labels.isEmpty())
        assertTrue(Tents.requestKeys(p, true).labels.isEmpty())
    }

    @Test
    fun `cursorLocation follows the visible cursor`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        val u = ui()
        assertNull(Tents.cursorLocation(u, state, TILE))
        u.cdisp = true
        u.cx = 3
        u.cy = 4
        assertEquals(
            Game.CursorRect(3 * TILE + TILE / 2, 4 * TILE + TILE / 2, TILE, TILE),
            Tents.cursorLocation(u, state, TILE),
        )
    }

    @Test
    fun `findErrors marks adjacent tents and impossible groups`() {
        val w = 8
        val h = 8
        val numbers = IntArray(16) { 1 }
        val grid = IntArray(w * h)
        grid[0] = Tents.TENT
        grid[1] = Tents.TENT
        grid[w + 1] = Tents.TREE
        val errors = Tents.findErrors(w, h, numbers, grid)
        assertTrue(errors[0] and (1 shl Tents.ERR_ADJ_RIGHT) != 0)
        assertTrue(errors[1] and (1 shl Tents.ERR_ADJ_LEFT) != 0)
        assertTrue(errors[0] and (1 shl Tents.ERR_OVERCOMMITTED) != 0)
    }

    @Test
    fun `the grid lines are drawn on every frame`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        val sink = RecordingSink()
        val ds = Tents.newDrawState(state, TILE, sink) as Tents.DrawState
        Tents.redraw(state, ui(), ds, sink, TILE)
        assertEquals(18, sink.ops.count { it == "line" })
        assertEquals(64, sink.ops.count { it == "clip" })
        assertEquals(64, sink.ops.count { it == "unclip" })
        // 64 cells plus 16 numbers.
        assertEquals(80, sink.ops.count { it == "update" })
        assertEquals(60, sink.ops.count { it == "circle" })
        assertEquals(16, sink.ops.count { it == "text" })
        assertFalse(sink.ops.contains("status"))
        assertFalse(ds.started)

        sink.ops.clear()
        Tents.redraw(state, ui(), ds, sink, TILE)
        assertEquals(18, sink.ops.count { it == "line" })
        assertEquals(0, sink.ops.count { it == "clip" })
        assertEquals(0, sink.ops.count { it == "update" })
        assertEquals(0, sink.ops.count { it == "text" })
        assertFalse(sink.ops.contains("status"))
    }

    @Test
    fun `a warm redraw updates a clue when its error flag changes`() {
        val start = stateOf("8x8de", GOLDEN_DESC)
        assertEquals(3, start.shared.numbers[0])
        val u = ui()
        val sink = RecordingSink()
        val ds = Tents.newDrawState(start, TILE, sink) as Tents.DrawState
        Tents.redraw(start, u, ds, sink, TILE)
        assertEquals(16, sink.texts.size)
        assertTrue(sink.texts.contains("32 304 1 16 1 1 3"))

        // Four tents in column 0 make 4 > 3, so the column clue turns
        // red. The cells changed without an intermediate frame, so this
        // warm redraw also repaints them; the clue cache transition is
        // the one number update.
        var state = start
        for (row in listOf(0, 2, 3, 4)) {
            state = Tents.executeMove(state, "T0,$row") as Tents.State
        }
        sink.ops.clear()
        sink.texts.clear()
        sink.rects.clear()
        sink.updates.clear()
        Tents.redraw(state, u, ds, sink, TILE)
        assertEquals(18, sink.ops.count { it == "line" })
        assertEquals("32 304 1 16 1 6 3", sink.texts.single())
        assertEquals(listOf("16 273 32 47 0"), sink.rects.filter { it == "16 273 32 47 0" })
        assertEquals(1, sink.updates.count { it == "16 273 32 47" })
        assertEquals(1, ds.numbersDrawn[0])

        // A third frame has no number transition left.
        sink.ops.clear()
        sink.texts.clear()
        Tents.redraw(state, u, ds, sink, TILE)
        assertEquals(18, sink.ops.count { it == "line" })
        assertEquals(0, sink.ops.count { it == "text" })
        assertEquals(0, sink.ops.count { it == "update" })
    }

    @Test
    fun `a cursor ring is a four-point outline`() {
        val state = stateOf("8x8de", GOLDEN_DESC)
        val u = ui()
        u.cdisp = true
        val sink = RecordingSink()
        val ds = Tents.newDrawState(state, TILE, sink) as Tents.DrawState
        Tents.redraw(state, u, ds, sink, TILE)
        assertTrue(sink.polys.any { it.contentEquals(intArrayOf(20, 20, 20, 44, 44, 44, 44, 20)) })
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /** The encoder's grid part, for the decode round trip. */
    private fun encodeGrid(grid: IntArray): String {
        val out = StringBuilder()
        var run = 0
        for (cell in 0..grid.size) {
            if (cell < grid.size && grid[cell] != Tents.TREE) {
                run++
                while (run > 25) {
                    out.append('z')
                    run -= 25
                }
            } else {
                out.append(if (run == 0) '_' else ('a' + run - 1))
                run = 0
            }
        }
        return out.toString()
    }

    /** A sink that records operation names and polygons. */
    private class RecordingSink : DrawSink {
        val ops = ArrayList<String>()
        val polys = ArrayList<IntArray>()
        val texts = ArrayList<String>()
        val rects = ArrayList<String>()
        val updates = ArrayList<String>()

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
            rects += "$x $y $width $height $colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}

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
            polys += points.copyOf()
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
            texts += "$x $y $font $size $align $colour $text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update"
            updates += "$x $y $width $height"
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun inertiaFollow(isSolved: Boolean) {
            ops += "inertia_follow"
        }

        override fun blitterNew(width: Int, height: Int): Int {
            ops += "blitter_new"
            return 1
        }

        override fun blitterFree(id: Int) {
            ops += "blitter_free"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load"
        }
    }
}
