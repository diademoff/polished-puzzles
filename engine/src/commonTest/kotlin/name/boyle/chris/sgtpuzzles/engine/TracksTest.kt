package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: engine and semantic. Game: tracks.
 *
 * The port of `tracks.c`, driven directly and through [Midend]. The
 * descriptions, the retry counters, the move grammar, the solver
 * attempts, the text form, the palette, and the two C undefined-behavior
 * definitions are pinned here.
 */
class TracksTest {

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Train Tracks", Tracks.name)
        assertEquals("tracks", Tracks.backendName)
        assertEquals(33, Tracks.preferredTileSize)
        assertTrue(Tracks.canSolve)
        assertFalse(Tracks.wantsStatusBar)
        assertFalse(Tracks.isTimed)
        assertEquals(0, Tracks.flags)
        assertTrue(Tracks.canFormatAsText)
        val midend = Midend.newGame(Tracks, "", SEED)
        assertTrue(Tracks.textFormat(midend.state).isNotEmpty())
        assertEquals(Tracks.textFormat(midend.state), midend.textFormat())
        assertTrue(midend.textFormat().endsWith("\n"))
    }

    @Test
    fun `the parameters and presets are the C table`() {
        val d = Tracks.defaultParams() as Tracks.Params
        assertEquals(Tracks.Params(8, 8, 1, true), d)
        assertEquals("8x8dt", Tracks.encodeParams(d, true))
        assertEquals("8x8", Tracks.encodeParams(d, false))

        val presets = Tracks.presets()
        assertEquals(12, presets.size)
        assertEquals(
            listOf(
                "8x8 Easy" to "8x8de",
                "8x8 Tricky" to "8x8dt",
                "10x8 Easy" to "10x8de",
                "10x8 Tricky" to "10x8dt",
                "10x10 Easy" to "10x10de",
                "10x10 Tricky" to "10x10dt",
                "10x10 Hard" to "10x10dh",
                "15x10 Easy" to "15x10de",
                "15x10 Tricky" to "15x10dt",
                "15x15 Easy" to "15x15de",
                "15x15 Tricky" to "15x15dt",
                "15x15 Hard" to "15x15dh",
            ),
            presets.map { it.title to it.params },
        )
        for (preset in presets) {
            assertEquals(preset.params, Tracks.encodeParams(Tracks.decodeParams(preset.params), true))
        }
    }

    @Test
    fun `the decode quirks match C`() {
        fun p(text: String) = Tracks.decodeParams(text) as Tracks.Params
        assertEquals(Tracks.Params(8, 8, 1, true), p("8x8"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("8x8d"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("8x8dq"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("8x8e"))
        assertEquals(Tracks.Params(8, 8, 1, false), p("8x8odt"))
        assertEquals(Tracks.Params(8, 8, 2, false), p("8x8dho"))
        assertEquals(Tracks.Params(8, 8, 0, true), p("8x8de"))
        assertEquals(Tracks.Params(8, 8, 0, false), p("8x8deo"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("+8x8"))
        // The `+` stops the digit skip, so the `x` and the second
        // number are never read.
        assertEquals(Tracks.Params(8, 8, 1, true), p("+8x10"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("08x08"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("8x8junk"))
        assertEquals(Tracks.Params(8, 8, 1, true), p("8x8dte"))
    }

    @Test
    fun `the overflowing atoi saturates`() {
        assertEquals(Int.MAX_VALUE, (Tracks.decodeParams("999999999999x4") as Tracks.Params).w)
        assertEquals(Int.MIN_VALUE, (Tracks.decodeParams("-999999999999x4") as Tracks.Params).w)
        assertEquals(4, (Tracks.decodeParams("999999999999x4") as Tracks.Params).h)
    }

    @Test
    fun `validation uses the C order and messages`() {
        val cases = listOf(
            "3x8dt" to "Width and height must both be at least four",
            "8x3dt" to "Width and height must both be at least four",
            "-4x8dt" to "Width and height must both be at least four",
            "999999x999999dt" to "Width times height must not be unreasonably large",
        )
        for ((params, message) in cases) {
            assertEquals(message, Tracks.validateParams(Tracks.decodeParams(params), true))
            assertEquals(message, Tracks.validateParams(Tracks.decodeParams(params), false))
        }
        assertNull(Tracks.validateParams(Tracks.decodeParams("4x4dt"), true))
        assertNull(Tracks.validateParams(Tracks.decodeParams("8x8dt"), true))
    }

    @Test(timeout = 120_000)
    fun `the default description is the frozen id`() {
        val midend = Midend.newGame(Tracks, "", SEED)
        assertEquals(
            "8x8:i5c5CiCu9pA,4,4,4,3,4,7,5,S3,3,6,6,S5,4,4,3,3",
            midend.id(),
        )
        assertEquals("8x8dt", Tracks.encodeParams(midend.params, true))
        assertEquals("8x8dt", Tracks.encodeParams(midend.currentParams, true))
        assertEquals("8x8", Tracks.encodeParams(midend.currentParams, false))
        assertEquals(midend.desc, midend.serialise().value("DESC"))
        assertNull(midend.serialise().value("AUXINFO"))
        assertNull(midend.serialise().value("UI"))
    }

    @Test(timeout = 120_000)
    fun `the RNG state after generation is pinned`() {
        val random = RandomState.fromSeed(SEED)
        Tracks.newDesc(Tracks.decodeParams("8x8dt"), random)
        assertEquals(
            "50b4237d0679ca88db6464eac60da96345513964" +
                "00a51f3f48415c7d4e8908980d443c29c69b60c9" +
                "ed78e604619145e73e9af632a4692e9eec852ceb07",
            random.encodeState(),
        )
        // The stream continues from the same point; the description
        // tests in :parity pin the draw count through the oracle.
        assertEquals(listOf(6L, 3L, 1L, 7L, 3L, 2L), (0 until 6).map { random.upto(8L) })
    }

    @Test(timeout = 300_000)
    fun `the retry fixtures reach the two retry paths`() {
        fun stats(params: String, seed: String) =
            Tracks.newDescWithStats(
                Tracks.decodeParams(params),
                RandomState.fromSeed(seed),
            ).stats

        val internalRestart = stats("8x8dt", "0")
        assertEquals(7, internalRestart.attempts)
        assertTrue(internalRestart.layPathRestarts > 0)

        val newPath = stats("8x8dt", "1")
        assertEquals(45, newPath.attempts)
        assertTrue(newPath.boring > 0)

        val rejected = stats("10x10dt", "5")
        assertEquals(146, rejected.attempts)
        assertTrue(rejected.addCluesRejected > 0)
        assertTrue(rejected.tooEasy > 0)

        val already = stats("8x8de", "26")
        assertEquals(4, already.attempts)
        assertEquals(1, already.alreadySolvable)
    }

    @Test(timeout = 120_000)
    fun `a state copy shares the clue block and copies the flags`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val state = midend.state as Tracks.State
        val copy = state.copy() as Tracks.State
        assertNotSame(state, copy)
        assertSame(state.numbers, copy.numbers)
        assertNotSame(state.sflags, copy.sflags)
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())

        copy.sflags[0] = copy.sflags[0] or 1
        assertNotEquals(state, copy)
        state.sflags[0] = state.sflags[0] or 1
        assertEquals(state, copy)

        val deep = Tracks.executeMove(state, "TR0,0") as Tracks.State
        assertNotSame(state.sflags, deep.sflags)
        assertSame(state.numbers, deep.numbers)
    }

    @Test(timeout = 120_000)
    fun `the description grammar and its errors`() {
        val params = Tracks.decodeParams("8x8dt")
        assertEquals(
            "Game description contained unexpected characters",
            Tracks.validateDesc(params, "M"),
        )
        assertEquals(
            "Clue did not provide 2 direction flags",
            Tracks.validateDesc(params, "1"),
        )
        assertEquals(
            "Clue did not provide 2 direction flags",
            Tracks.validateDesc(
                params,
                "7" + "a".repeat(63) + ",S0" + ",0".repeat(7) +
                    ",S0" + ",0".repeat(7),
            ),
        )
        assertEquals(
            "Not enough numbers given after grid specification",
            Tracks.validateDesc(params, "a".repeat(64)),
        )
        assertEquals(
            "Invalid character in number list",
            Tracks.validateDesc(params, "a".repeat(64) + "x"),
        )
        assertEquals(
            "Puzzle must have one entrance and one exit",
            Tracks.validateDesc(params, "a".repeat(64) + ",0".repeat(16)),
        )
        assertEquals(
            "Unexpected additional character at end of game description",
            Tracks.validateDesc(
                params,
                "a".repeat(64) + ",S0" + ",0".repeat(7) + ",S0" + ",0".repeat(7) + "x",
            ),
        )

        // Quirks that pass validation: a zero clue and an absent
        // number (`atoi("") == 0`).
        assertNull(
            Tracks.validateDesc(
                params,
                "a".repeat(64) + ",0,0,0,0,0,0,S0,0" + ",S0,0,0,0,0,0,0,0",
            ),
        )
        val zero = Tracks.newState(
            params,
            "a".repeat(64) + ",0,0,0,0,0,0,S0,0" + ",S0,0,0,0,0,0,0,0",
        ) as Tracks.State
        assertEquals(0, zero.numbers.values[0])
        assertEquals(6, zero.numbers.colS)
        assertEquals(0, zero.numbers.rowS)

        assertFailsWith<IllegalArgumentException> {
            Tracks.newState(params, "a".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            Tracks.newState(params, "1" + "a".repeat(63) + ",S0" + ",0".repeat(7) +
                ",S0" + ",0".repeat(7))
        }

        // A clue number that overflows `atoi` saturates. C leaves this
        // undefined; the port defines it like the other ports.
        val hugeDesc = "a".repeat(64) + ",S999999999999" + ",0".repeat(7) +
            ",S0" + ",0".repeat(7)
        assertNull(Tracks.validateDesc(params, hugeDesc))
        val huge = Tracks.newState(params, hugeDesc) as Tracks.State
        assertEquals(Int.MAX_VALUE, huge.numbers.values[0])
    }

    @Test(timeout = 120_000)
    fun `the move grammar and the flip strings`() {
        val state = Midend.newGame(Tracks, "8x8dt", SEED).state
        for (move in listOf("", "S", "TR0,0", "TR0,0;TR0,0", "TR 0,0", "TS0,0",
            "NS0,0", "S;TS0,3", "H", "H;H", "S;H", "TR0,0;")
        ) {
            assertTrue(Tracks.executeMove(state, move) != null, "accept '$move'")
        }
        for (move in listOf("T", "TX0,0", "TR9,9", "TR0,0x", "TR0,0;//", "HH",
            "TS0,3", "NS0,3", "TR0,0;S;TX0,0")
        ) {
            assertNull(Tracks.executeMove(state, move), "reject '$move'")
        }

        assertEquals("TR0,0", Tracks.edgeFlipStr(state as Tracks.State, 0, 0, 1, false))
        assertEquals("NR0,0", Tracks.edgeFlipStr(state, 0, 0, 1, true))
        val withEdge = Tracks.executeMove(state, "TR0,0") as Tracks.State
        assertEquals("tR0,0", Tracks.edgeFlipStr(withEdge, 0, 0, 1, false))

        assertEquals("TS0,0", Tracks.squareFlipStr(state, 0, 0, false))
        assertEquals("NS0,0", Tracks.squareFlipStr(state, 0, 0, true))
        val withSquare = Tracks.executeMove(state, "TS0,0") as Tracks.State
        assertEquals("tS0,0", Tracks.squareFlipStr(withSquare, 0, 0, false))
        // The accidental edge-constant read in `square_flip_str`:
        // S_TRACK is E_TRACK, so the notrack branch sees no E_NOTRACK
        // and says N even though the square is track.
        assertEquals("NS0,0", Tracks.squareFlipStr(withSquare, 0, 0, true))
        val withNotrack = Tracks.executeMove(state, "NS0,0") as Tracks.State
        assertEquals("nS0,0", Tracks.squareFlipStr(withNotrack, 0, 0, true))
        assertEquals("TS0,0", Tracks.squareFlipStr(withNotrack, 0, 0, false))
    }

    @Test(timeout = 120_000)
    fun `the empty move completes a hand-written full path`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertTrue(midend.solve())
        val desc = fullPathDesc(midend.state as Tracks.State)
        val initial = Tracks.newState(Tracks.decodeParams("8x8dt"), desc) as Tracks.State
        assertFalse(initial.completed)
        val completed = Tracks.executeMove(initial, "") as Tracks.State
        assertTrue(completed.completed)
        assertEquals(1, Tracks.status(completed))
        assertEquals(0, completed.numErrors.sum())
        assertTrue(completed.sflags.none { it and 4 != 0 })
    }

    @Test(timeout = 120_000)
    fun `a wrong clue row is marked only by a move`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertTrue(midend.solve())
        val desc = fullPathDesc(midend.state as Tracks.State, rowIndex = 2, rowDelta = 1)
        val initial = Tracks.newState(Tracks.decodeParams("8x8dt"), desc) as Tracks.State
        assertEquals(0, initial.numErrors.sum())
        val marked = Tracks.executeMove(initial, "") as Tracks.State
        assertFalse(marked.completed)
        assertEquals(1, marked.numErrors[8 + 2])
        assertEquals(1, marked.numErrors.sum())
    }

    @Test(timeout = 120_000)
    fun `a loop a third edge and a stray track are errors`() {
        val loopDesc = "9Caa36aaaaaaaaaa,S2,2,0,0,S2,2,0,0"
        val loop = Tracks.newState(Tracks.decodeParams("4x4dt"), loopDesc) as Tracks.State
        val loopMarked = Tracks.executeMove(loop, "") as Tracks.State
        assertFalse(loopMarked.completed)
        assertEquals(4, (0 until 16).count { loopMarked.sflags[it] and 4 != 0 })

        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val entrance = 3 * 8
        val third = Tracks.executeMove(midend.state, "S;TU0,3") as Tracks.State
        assertEquals(3, Integer.bitCount((third.sflags[entrance] ushr 16) and 15))
        assertEquals(4, third.sflags[entrance] and 4)

        val solved = Midend.newGame(Tracks, "8x8dt", SEED)
        assertTrue(solved.solve())
        val full = Tracks.newState(
            Tracks.decodeParams("8x8dt"),
            fullPathDesc(solved.state as Tracks.State),
        ) as Tracks.State
        val blank = (0 until 64).first { full.sflags[it] and 1 == 0 }
        val stray = Tracks.executeMove(full, "TS${blank % 8},${blank / 8}") as Tracks.State
        assertFalse(stray.completed)
        assertEquals(4, stray.sflags[blank] and 4)
    }

    @Test(timeout = 120_000)
    fun `the solve attempts and the error string`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val solved = Tracks.solveAttempt(midend.state)
        assertNull(solved.error)
        assertTrue(solved.move!!.startsWith("S;"))

        val broken = Tracks.executeMove(midend.state, "S;TR0,0;NS0,0") as Tracks.State
        val fallback = Tracks.solveAttempt(broken)
        assertNull(fallback.error)
        assertTrue(fallback.move!!.startsWith("S;"))
        assertTrue(fallback.move!!.contains("tR0,0"))

        val impossible = Tracks.newState(
            Tracks.decodeParams("8x8dt"),
            "a".repeat(64) + ",S99" + ",0".repeat(7) + ",S0" + ",0".repeat(7),
        ) as Tracks.State
        val failed = Tracks.solveAttempt(impossible)
        assertNull(failed.move)
        assertEquals("Unable to find solution", failed.error)
        assertNull(Tracks.solve(impossible))
    }

    @Test(timeout = 120_000)
    fun `the H move solves but does not set used_solve`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val state = midend.state
        val solved = Tracks.executeMove(state, "H") as Tracks.State
        assertTrue(solved.completed)
        // C's H item calls the solver only; used_solve is set by an S
        // item, so a loaded H move completes the board but would raise
        // the completion event on a normal history entry.
        assertFalse(solved.usedSolve)
        assertEquals(1, Tracks.status(solved))
    }

    @Test(timeout = 120_000)
    fun `the text form is the C form`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertEquals(
            "  4 4 4 3 4 7 5 3 \n" +
                " +---------------+\n" +
                " |               |3\n" +
                " |               |\n" +
                " | -C-     -C-C  |6\n" +
                " |            |  |\n" +
                " |               |6\n" +
                " |               |\n" +
                "A-C              |5\n" +
                " ||              |\n" +
                " |               |4\n" +
                " |               |\n" +
                " |            C- |4\n" +
                " |            |  |\n" +
                " |               |3\n" +
                " |              ||\n" +
                " |              C|3\n" +
                " +--------------|+\n" +
                "                B\n",
            Tracks.textFormat(midend.state),
        )
    }

    @Test(timeout = 120_000)
    fun `the palette and the sizes match the C arithmetic`() {
        val colours = Tracks.colours()
        assertEquals(13 * 3, colours.size)
        val expected = listOf(
            800, 800, 800,
            633, 633, 633,
            750, 750, 750,
            0, 0, 0,
            1000, 500, 500,
            500, 500, 500,
            0, 0, 0,
            500, 400, 100,
            0, 0, 1000,
            800, 800, 1000,
            1000, 0, 0,
            1000, 1000, 1000,
            1000, 1000, 1000,
        )
        assertEquals(expected, colours.map { (it * 1000f).roundToInt() })

        val params = Tracks.decodeParams("8x8dt")
        for ((tile, size) in listOf(33 to 306, 48 to 492, 63 to 614, 82 to 798,
            100 to 984, 24 to 240)
        ) {
            assertEquals(Size(size, size), Tracks.computeSize(params, tile), "tile $tile")
        }
    }

    @Test
    fun `the cursor location is the C rectangle`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertNull(midend.cursorLocation(33))
        midend.setCursorVisible(true)
        assertEquals(
            Game.CursorRect(36, 36, 25, 25),
            midend.cursorLocation(33),
        )
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 33)
        assertEquals(
            Game.CursorRect(63, 36, 7, 25),
            midend.cursorLocation(33),
        )
    }

    @Test
    fun `key requests and labels are the C NULL hook`() {
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT),
            Tracks.requestKeys(Tracks.decodeParams("8x8dt")),
        )
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_ONLY),
            Tracks.requestKeys(Tracks.decodeParams("8x8dt"), fullCursor = true),
        )

        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertEquals(
            "",
            Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT),
        )
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 33)
        assertEquals(
            "Track",
            Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT),
        )
        assertEquals(
            "X",
            Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2),
        )
    }

    @Test
    fun `the off-grid press leaves the click fields inert`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val ui = midend.ui as Tracks.Ui
        assertEquals(0, ui.clickx)
        assertEquals(0, ui.clicky)
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, Buttons.LEFT_BUTTON, 33),
        )
        assertEquals(0, ui.clickx)
        assertEquals(0, ui.clicky)
        assertEquals(-1, ui.dragSx)
        assertEquals(-1, ui.dragSy)

        val geometry = Tracks.computeSize(Tracks.decodeParams("8x8dt"), 33)
        val release = midend.processKey(
            geometry.width / 2,
            geometry.height / 2,
            Buttons.LEFT_RELEASE,
            33,
        )
        assertEquals(KeyOutcome.SomeEffect, release)
        assertEquals(1, midend.stateCount)
    }

    @Test(timeout = 120_000)
    fun `an outer track edge reaches the solver safely`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val state = Tracks.executeMove(midend.state, "S;TU0,0") as Tracks.State
        assertEquals(2, (state.sflags[0] ushr 16) and 15)
        val attempt = Tracks.solveAttempt(state)
        assertNull(attempt.error)
        val move = attempt.move!!
        // D-012: C asserts that an outer track edge is on a clue and
        // aborts. The port ignores the border rule and returns the normal
        // solve, which clears the stray outer edge first.
        assertTrue(move.startsWith("S;NR0,0;tU0,0;"))
        val after = Tracks.executeMove(state, move) as Tracks.State
        assertTrue(after.completed)
        assertTrue(after.usedSolve)
        assertEquals(1, Tracks.status(after))
        // The same board is a valid C save: the edge is inside the
        // board, so validation accepts the description.
        assertEquals(3, state.numbers.rowS)
        assertEquals(7, state.numbers.colS)
    }

    @Test(timeout = 120_000)
    fun `a solve never raises the completion event`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val solved = Tracks.solveAttempt(midend.state).move!!
        val after = Tracks.executeMove(midend.state, solved) as Tracks.State
        assertTrue(after.completed)
        assertTrue(after.usedSolve)
        assertFalse(Tracks.changedState(midend.ui, midend.state, after))
        assertEquals(1, Tracks.status(after))
    }

    private fun fullPathDesc(
        state: Tracks.State,
        rowIndex: Int = -1,
        rowDelta: Int = 0,
    ): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder()
        for (i in 0 until w * h) {
            val f = (state.sflags[i] ushr 16) and 15
            if (f != 0) {
                out.append(if (f < 10) '0' + f else 'A' + (f - 10))
            } else {
                out.append('a')
            }
        }
        for (x in 0 until w) {
            out.append(',')
            if (x == state.numbers.colS) out.append('S')
            out.append(state.numbers.values[x])
        }
        for (y in 0 until h) {
            out.append(',')
            if (y == state.numbers.rowS) out.append('S')
            out.append(state.numbers.values[w + y] + if (y == rowIndex) rowDelta else 0)
        }
        return out.toString()
    }

    private companion object {
        const val SEED = "12345"
    }
}
