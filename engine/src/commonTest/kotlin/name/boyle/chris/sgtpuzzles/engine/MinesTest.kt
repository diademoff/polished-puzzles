package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: engine and semantic. Game: mines.
 *
 * The port of `mines.c`, driven through [Midend]. The descriptions,
 * first-click layouts, move grammar, timer rule, UI record, and text
 * form are pinned here; `MinesDescTest`, `MinesSaveTest`,
 * `MinesTraceTest`, and `MinesInputParityTest` in `:parity` compare the
 * same behavior with the C oracle.
 */
class MinesTest {

    private val tile = 20

    private fun click(midend: Midend, x: Int, y: Int, t: Int = tile) {
        val px = x * t + 8 + t / 2
        val py = y * t + 8 + t / 2
        midend.processKey(px, py, Buttons.LEFT_BUTTON, t)
        midend.processKey(px, py, Buttons.LEFT_RELEASE, t)
    }

    private fun opened(params: String = "9x9n10", seed: String = "12345"): Midend =
        Midend.newGame(Mines, params, seed).also { click(it, 4, 4) }

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Mines", Mines.name)
        assertEquals("mines", Mines.backendName)
        assertEquals(20, Mines.preferredTileSize)
        assertTrue(Mines.canSolve)
        assertTrue(Mines.canFormatAsText)
        assertTrue(Mines.wantsStatusBar)
        assertTrue(Mines.isTimed)
        assertEquals(
            Game.buttonBeatsBit(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON) or
                Game.REQUIRE_RBUTTON,
            Mines.flags,
        )
        assertEquals(1028, Mines.flags)
        assertTrue(Mines.buttonBeats(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON))
        assertFalse(Mines.buttonBeats(Buttons.RIGHT_BUTTON, Buttons.LEFT_BUTTON))
        assertEquals(4, Game.buttonBeatsBit(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON))
    }

    @Test
    fun `the default game is the pending interactive description`() {
        val midend = Midend.newGame(Mines, "", "12345")
        assertEquals(
            "9x9:r8,u,8cb2237d0679ca88db6464eac60da96345513964" +
                "00a51f3f48415c7d4e8908980d443c29c69b60c9caefef" +
                "1451662ab6b8c65888654a119724096b8402",
            midend.id(),
        )
        assertEquals("9x9n8", Mines.encodeParams(midend.params, true))
        assertEquals("9x9n8", Mines.encodeParams(midend.currentParams, true))
        assertEquals("Marked: 0 / 8", Mines.statusText(midend.state, midend.ui))
        assertEquals(
            (0 until 9).joinToString("") { "?????????\n" },
            midend.textFormat(),
        )
        assertFalse(midend.timing)
        assertNull(midend.privDesc)
        assertEquals("D0", midend.serialise().value("UI"))
        assertEquals("0", midend.serialise().value("TIME"))
    }

    @Test(timeout = 120_000)
    fun `the first uncover generates the layout and supersedes the description`() {
        val midend = opened()

        assertEquals("9x9:4,4,m9eed07198e89048f2e4d0", midend.id())
        assertEquals("4,4,m9eed07198e89048f2e4d0", midend.desc)
        assertEquals("m9eed07198e89048f2e4d0", midend.privDesc)
        assertEquals(2, midend.stateCount)
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        assertEquals(
            "?2--1????\n?2-12????\n?2-1?????\n?1-11112?\n" +
                "11-----1?\n---111-11\n-112?1---\n-1?211-11\n-1?1---1?\n",
            midend.textFormat(),
        )
        assertEquals("Marked: 0 / 10", Mines.statusText(midend.state, midend.ui))
        assertEquals("[0:00] Marked: 0 / 10", midend.statusText())
        assertTrue(midend.timing, "the clock starts when the layout exists")

        val state = midend.state as Mines.State
        assertEquals(4, state.layout.startX)
        assertEquals(4, state.layout.startY)
        assertNull(state.layout.rs, "the generator state is freed at generation")
        assertTrue(state.layout.unique)
        assertTrue(state.layout.mines != null)

        val save = midend.serialise()
        assertEquals(
            listOf("SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS", "SEED",
                "DESC", "PRIVDESC", "UI", "TIME", "NSTATES", "STATEPOS", "MOVE"),
            save.records.map { it.key },
        )
        assertEquals("9x9n8", save.value("PARAMS"))
        assertEquals("9x9n10", save.value("CPARAMS"))
        assertEquals("O4,4", save.value("MOVE"))
    }

    @Test(timeout = 120_000)
    fun `a first click on a mine-free start opens the same area`() {
        val midend = Midend.newGame(Mines, "9x9n10", "12345")
        click(midend, 0, 0)
        assertEquals("0,0,ma9e60c66c0d74ee103518", midend.desc)
        assertEquals(
            "-1???????\n-1???????\n11???????\n?????????\n?????????\n" +
                "?????????\n?????????\n?????????\n?????????\n",
            midend.textFormat(),
        )
    }

    @Test(timeout = 120_000)
    fun `the other presets generate their oracle layouts`() {
        val cases = listOf(
            Triple("9x9n35", 4 to 4, "4,4,m8e698d55d9185abbcea78"),
            Triple(
                "16x16n40",
                0 to 0,
                "0,0,m2dfff8c4aec6f2a38aa8725c9e7edce4562df35d27cabe63a164e84df7a9ac5e",
            ),
            Triple(
                "16x16n40",
                8 to 8,
                "8,8,m563e34fe17651a5eed7e5cc02705669a9e423944797a1146af148e8207a59a16",
            ),
            Triple(
                "16x16n99",
                0 to 0,
                "0,0,m2d8e651cd8b8e2ca135e6234b3c88aeec2813535115558354c36895c1b2af511",
            ),
        )
        for ((params, cell, desc) in cases) {
            val midend = Midend.newGame(Mines, params, "12345")
            click(midend, cell.first, cell.second)
            assertEquals(desc, midend.desc, "$params ${cell.first},${cell.second}")
        }
    }

    @Test(timeout = 120_000)
    fun `a high-density layout pins the perturbation counter`() {
        // 9x9n35 seed 18 needs 103 attempts, so the big-perturbation
        // branch (`ntries > 100`) runs. This layout is the oracle one.
        // Removing the second post-increment of
        // `nperturbs_since_last_new_open` gives
        // `0,0,m45540a73c0b7fbb896938` after 101 attempts. Assigning
        // the dead `prevret` never reaches a layout: every attempt that
        // needs a second solver pass retries, without end.
        val midend = Midend.newGame(Mines, "9x9n35", "18")
        click(midend, 0, 0)
        assertEquals("0,0,mf4ac480d0763fb8209c80", midend.desc)
        assertEquals("mf4ac480d0763fb8209c80", midend.privDesc)

        val run = Mines.generateLayoutForSeed(
            Mines.Params(9, 9, 35, true, -1, -1),
            "18",
            0,
            0,
        )
        assertEquals("0,0,mf4ac480d0763fb8209c80", run.desc)
        assertEquals(103, run.attempts)
        assertEquals(35, run.mines.count { it })
    }

    @Test
    fun `the description parser keeps the C sign and tail quirks`() {
        val stats = listOf(
            "9x9n10" to "9x9n10",
            "9" to "9x9n8",
            "+9x9" to "9x9n8",
            "9x9n1.5" to "9x9n1",
            "9x9a" to "9x9n8a",
            "9x9X3Y5" to "9x9n8X3Y5",
            "9x9X-1" to "9x9n8",
            "9x9XX3" to "9x9n8X3",
            "9x9junk" to "9x9n8",
            "300x6" to "300x6n180",
        )
        for ((input, encoded) in stats) {
            assertEquals(encoded, Mines.encodeParams(Mines.decodeParams(input), true), input)
        }
        assertEquals("9x9", Mines.encodeParams(Mines.decodeParams("9x9n10"), false))
        assertEquals(
            "Width and height must both be greater than two",
            Mines.validateParams(Mines.decodeParams("-9x9"), true),
        )
        assertEquals(
            "Mine count may not be negative",
            Mines.validateParams(Mines.decodeParams("9x9n-5"), true),
        )
        assertEquals(
            "First-click x coordinate must be inside the grid",
            Mines.validateParams(Mines.decodeParams("9x9X9"), true),
        )
        assertEquals(
            "Number of mines must be greater than zero",
            Mines.validateParams(Mines.decodeParams("2x2"), false),
        )
        assertEquals(
            "Width and height must both be greater than two",
            Mines.validateParams(Mines.decodeParams("2x2"), true),
        )
    }

    @Test(timeout = 120_000)
    fun `the parameter and move parsers saturate at the Int limits`() {
        // C leaves an overflowing `atoi` and `sscanf("%d")` undefined.
        // The port defines both as a saturation at the Int limits.
        assertEquals(
            Mines.Params(Int.MAX_VALUE, 9, 10, true, -1, -1),
            Mines.decodeParams("2147483648x9"),
        )
        assertEquals(
            "Neither width nor height may be unreasonably large",
            Mines.validateParams(Mines.decodeParams("2147483648x9"), true),
        )
        assertEquals(
            Mines.Params(9, 9, Int.MAX_VALUE, true, -1, -1),
            Mines.decodeParams("9x9n2147483648"),
        )
        assertEquals(
            "Too many mines for grid size",
            Mines.validateParams(Mines.decodeParams("9x9n2147483648"), true),
        )
        assertEquals(
            Mines.Params(9, 9, Int.MIN_VALUE, true, -1, -1),
            Mines.decodeParams("9x9n-2147483649"),
        )
        assertEquals(
            "Mine count may not be negative",
            Mines.validateParams(Mines.decodeParams("9x9n-2147483649"), true),
        )
        assertEquals(
            Mines.Params(9, 9, 8, true, Int.MAX_VALUE, -1),
            Mines.decodeParams("9x9X2147483648"),
        )
        assertEquals(
            "First-click x coordinate must be inside the grid",
            Mines.validateParams(Mines.decodeParams("9x9X2147483648"), true),
        )
        // The 64-bit overflow saturates to the same limit.
        assertEquals(
            Mines.Params(9, 9, Int.MAX_VALUE, true, -1, -1),
            Mines.decodeParams("9x9n99999999999999999999999"),
        )

        // The move `%d` scan saturates, and the range test rejects the
        // saturated coordinates instead of wrapping them onto the board.
        val state = opened().state
        for (move in listOf(
            "O2147483648,0",
            "F-2147483649,0",
            "C99999999999999999999999,0",
            // Without saturation, the low 32 bits are zero, so these
            // would act on (0,4) instead of being rejected.
            "O4294967296,4",
            "F-4294967296,4",
        )) {
            assertNull(Mines.executeMove(state, move), move)
        }
    }

    @Test
    fun `the layout description decodes and the bitmap round-trips`() {
        val params = Mines.Params(4, 4, 1, true, -1, -1)
        val state = Mines.newState(params, "0,0,m9690") as Mines.State
        val mines = checkNotNull(state.layout.mines)
        assertEquals(1, mines.count { it })
        assertTrue(mines[3 * 4 + 1], "the mine is the second covered cell")
        assertEquals("----\n----\n111-\n??1-\n", Mines.textFormat(state))

        val unmasked = Mines.describeLayout(mines, 16, 0, 0, false)
        assertEquals("0,0,u0004", unmasked)
        val fromUnmasked = Mines.newState(params, unmasked) as Mines.State
        assertTrue(checkNotNull(fromUnmasked.layout.mines).contentEquals(mines))

        val masked = Mines.describeLayout(mines, 16, 0, 0, true)
        assertEquals("0,0,m9690", masked)
        val fromMasked = Mines.newState(params, masked) as Mines.State
        assertTrue(checkNotNull(fromMasked.layout.mines).contentEquals(mines))
    }

    @Test
    fun `a non-hex bitmap is an empty board and a missing flag is unmasked`() {
        val params = Mines.Params(3, 3, 0, true, -1, -1)
        val zz = Mines.newState(params, "0,0,u" + "z".repeat(3)) as Mines.State
        assertTrue(checkNotNull(zz.layout.mines).none { it })
        assertTrue(zz.won, "a board with no mines wins on the first click")

        // The same text without the mask flag decodes the same way.
        val plain = Mines.newState(params, "0,0," + "z".repeat(3)) as Mines.State
        assertTrue(checkNotNull(plain.layout.mines).none { it })

        // A short bitmap is rejected by validation and by a direct call.
        assertEquals(
            "Game description is wrong length",
            Mines.validateDesc(params, "0,0,uz"),
        )
        assertFailsWith<IllegalArgumentException> {
            Mines.newState(params, "0,0,uz")
        }

        // A direct newState with more mines than candidate squares
        // would call random_upto(0) in C; the port rejects the draw.
        val tooMany = Mines.newState(
            Mines.Params(9, 9, 300, true, -1, -1),
            "r300,u,",
        )
        assertFailsWith<IllegalArgumentException> {
            Mines.executeMove(tooMany, "O4,4")
        }

        // A direct newState with an out-of-grid leading click does not
        // read outside the board.
        val outside = Mines.newState(
            Mines.Params(9, 9, 10, true, -1, -1),
            "99,99,m9eed07198e89048f2e4d0",
        ) as Mines.State
        assertTrue(outside.grid.all { it == Mines.UNKNOWN })
    }

    @Test
    fun `an r description ignores the generator text`() {
        val params = Mines.decodeParams("9x9n10") as Mines.Params
        assertNull(Mines.validateDesc(params, "r10,u,"))
        assertNull(Mines.validateDesc(params, "r10,u,zzzz"))
        val state = Mines.newState(params, "r10,u,") as Mines.State
        assertTrue(state.layout.mines == null)
        assertTrue(state.layout.unique)
        assertEquals(10, state.layout.n)
    }

    @Test(timeout = 120_000)
    fun `the move grammar matches the C conditions`() {
        val state = opened().state as Mines.State

        val flagged = Mines.executeMove(state, "F0,1") as Mines.State
        assertEquals(Mines.MARKED, flagged.grid[1 * 9 + 0])
        assertEquals(
            Mines.UNKNOWN,
            (Mines.executeMove(flagged, "F0,1") as Mines.State).grid[1 * 9 + 0],
        )
        for (form in listOf("F 0,1", "F+0,1", "F00,01", "F0,1junk")) {
            val flaggedForm = Mines.executeMove(state, form) as Mines.State
            assertEquals(Mines.MARKED, flaggedForm.grid[1 * 9 + 0], form)
        }

        // A malformed, out-of-range, or open-cell token rejects the
        // whole move. This is the C `else` branch, not a no-op.
        assertNull(Mines.executeMove(state, "F99,0"))
        assertNull(Mines.executeMove(state, "O99,0"))
        assertNull(Mines.executeMove(state, "X1,2"))
        assertNull(Mines.executeMove(state, ";F1,2"))
        assertNull(Mines.executeMove(state, "F2,2"))

        // An empty move is a copy.
        val empty = Mines.executeMove(state, "") as Mines.State
        assertEquals(state, empty)
        assertNotSame(state, empty)
        assertSameLayout(state, empty)

        // A copy shares the layout, and equality ignores it: two states
        // with the same grid and flags are equal even when one was built
        // from a layout description and the other from a pending one.
        val copy = state.copy() as Mines.State
        assertSameLayout(state, copy)
        assertEquals(state, copy)
        copy.grid[0] = Mines.MARKED
        assertNotEquals(state, copy)

        // A solve on a pending game fails.
        val pending = Midend.newGame(Mines, "9x9n10", "12345")
        assertNull(Mines.solve(pending.state))
        assertEquals("Game has not been started yet", Mines.solveError(pending.state))
    }

    @Test(timeout = 120_000)
    fun `a correct chord opens the neighbours and a wrong flag dies`() {
        val midend = Midend.newGame(Mines, "9x9n10", "12345")
        click(midend, 4, 4)
        rightClick(midend, 0, 0)
        rightClick(midend, 0, 1)
        middleClick(midend, 1, 0)

        val state = midend.state as Mines.State
        assertEquals("C1,0", midend.serialise().values("MOVE").last())
        assertEquals(0, (midend.ui as Mines.Ui).deaths)
        assertFalse(state.dead)
        assertFalse(state.won)
        assertEquals(5, midend.stateCount)

        // The second board: two flags, one of which is wrong, then the
        // same chord opens exactly the unflagged mine.
        val wrong = Midend.newGame(Mines, "9x9n10", "12345")
        click(wrong, 4, 4)
        rightClick(wrong, 0, 0)
        rightClick(wrong, 0, 2)
        middleClick(wrong, 1, 1)

        val dead = wrong.state as Mines.State
        assertTrue(dead.dead)
        assertEquals(1, (wrong.ui as Mines.Ui).deaths)
        assertEquals("O0,1", wrong.serialise().values("MOVE").last())
        assertEquals(65, dead.grid[9])
        assertEquals("DEAD!  Deaths: 1", Mines.statusText(wrong.state, wrong.ui))
    }

    @Test(timeout = 120_000)
    fun `death win completion and the sticky UI flag`() {
        val midend = Midend.newGame(Mines, "4x4n1", "7")
        click(midend, 0, 0)
        click(midend, 0, 3)

        assertEquals(1, midend.status())
        assertEquals("COMPLETED!", Mines.statusText(midend.state, midend.ui))
        assertEquals("D0C", midend.serialise().value("UI"))
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
        assertFalse(midend.timing, "a win stops the clock")

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertEquals("D0C", Mines.encodeUi(midend.ui))
        assertFalse(midend.timing, "the completion flag is sticky after undo")
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())

        val deadMidend = opened()
        click(deadMidend, 0, 0)
        val dead = deadMidend.state as Mines.State
        assertTrue(dead.dead)
        assertEquals(0, deadMidend.status(), "a death is not the solve loss status")
        assertEquals("DEAD!  Deaths: 1", Mines.statusText(deadMidend.state, deadMidend.ui))
        assertEquals("D1", deadMidend.serialise().value("UI"))
        assertNull(Mines.executeMove(dead, "F1,2"))
        assertTrue(Mines.executeMove(dead, "S") != null)
    }

    @Test(timeout = 120_000)
    fun `solve reveals the board and keeps the C status rule`() {
        val midend = opened()
        assertTrue(midend.solve())
        assertEquals(0, midend.status(), "a solve on a live board is still open")
        assertEquals(
            "*2--111--\n*2-12*211\n22-1*22*1\n*1-111122\n11-----1*\n" +
                "---111-11\n-112*1---\n-1*211-11\n-111---1*\n",
            midend.textFormat(),
        )
        assertEquals("Marked: 10 / 10", Mines.statusText(midend.state, midend.ui))
        assertTrue(midend.timing, "a solve does not stop the clock")

        // Opening a safe cell after the solve leaves the board won with
        // `used_solve`, which is the C -1 status.
        val afterSolve = Mines.executeMove(midend.state, "O4,4") as Mines.State
        assertTrue(afterSolve.won)
        assertEquals(-1, Mines.status(afterSolve))
        assertEquals("Auto-solved.", Mines.statusText(afterSolve, midend.ui))

        // Solve once more: the state is not won, so the second solve is
        // the same reveal. Opening a safe cell then sets won.
        val won = Midend.newGame(Mines, "4x4n1", "7")
        click(won, 0, 0)
        click(won, 0, 3)
        assertEquals(1, won.status())
        assertEquals("COMPLETED!", Mines.statusText(won.state, won.ui))
        assertTrue(won.solve())
        assertEquals(-1, won.status())
        assertEquals("Auto-solved.", Mines.statusText(won.state, won.ui))

        // The post-death form: covered mines become 64 and wrong flags
        // become 66.
        val dead = opened()
        click(dead, 0, 0)
        assertTrue(dead.solve())
        assertEquals(
            "!2--1????\n!2-12!???\n?2-1!??!?\n!1-11112?\n11-----1!\n" +
                "---111-11\n-112!1---\n-1!211-11\n-1?1---1!\n",
            dead.textFormat(),
        )
    }

    @Test(timeout = 120_000)
    fun `undo keeps the generated layout and restart starts a new one`() {
        val midend = opened()
        val generated = midend.state as Mines.State
        val layout = generated.layout
        assertTrue(midend.undo())

        val undone = midend.state as Mines.State
        assertEquals((0 until 81).map { Mines.UNKNOWN }, undone.grid.toList())
        assertTrue(undone.layout === layout, "the layout object is shared")
        assertEquals(4, undone.layout.startX)
        assertEquals(4, undone.layout.startY)
        assertTrue(undone.layout.mines != null)
        assertEquals("Marked: 0 / 10", Mines.statusText(midend.state, midend.ui))
        assertTrue(midend.timing, "the clock keeps running after undo")
        assertEquals(
            "9x9:4,4,m9eed07198e89048f2e4d0",
            midend.id(),
            "undo keeps the public id",
        )

        assertTrue(midend.redo())
        assertTrue(midend.restart())
        val restarted = midend.state as Mines.State
        assertNotSame(layout, restarted.layout)
        assertEquals("4,4,m9eed07198e89048f2e4d0", midend.desc)
        assertEquals(-1, restarted.layout.startX, "a restarted layout has no start cross")
        assertEquals(-1, restarted.layout.startY)
        assertEquals("4,4,m9eed07198e89048f2e4d0", midend.serialise().value("RESTART"))
        assertEquals(
            "?2--1????\n?2-12????\n?2-1?????\n?1-11112?\n" +
                "11-----1?\n---111-11\n-112?1---\n-1?211-11\n-1?1---1?\n",
            midend.textFormat(),
        )
    }

    @Test(timeout = 120_000)
    fun `the UI record is the C encoder and the C parser`() {
        assertEquals("D0", Mines.encodeUi(Mines.newUi(opened().state)))
        val vectors = listOf(
            "D3" to "D3",
            "D3C" to "D3C",
            "C" to "D0C",
            "D3x" to "D3",
            "D-3" to "D-3",
            "D3Cjunk" to "D3C",
            "D+3C" to "D3C",
            "D 3C" to "D3C",
            "D3 C" to "D3",
            " 3" to "D0",
            "D" to "D0",
            "DD3" to "D0",
            "" to "D0",
        )
        val state = opened().state
        for ((text, encoded) in vectors) {
            assertEquals(encoded, Mines.encodeUi(Mines.decodeUi(state, text)), text)
        }
        // `%d` overflow is undefined in C. glibc reads the count as a
        // `long` and assigns its low 32 bits, so the port does the same.
        val overflowVectors = listOf(
            "D2147483647" to Int.MAX_VALUE,
            "D2147483648" to Int.MIN_VALUE,
            "D4294967296" to 0,
            "D99999999999" to 1215752191,
            "D-2147483649" to Int.MAX_VALUE,
            "D-4294967297" to -1,
            "D99999999999999999999999" to -1,
            "D-99999999999999999999999" to 0,
        )
        for ((text, expected) in overflowVectors) {
            assertEquals(expected, (Mines.decodeUi(state, text) as Mines.Ui).deaths, text)
        }
        val base = Mines.Ui().apply {
            curX = 4
            curY = 4
            curVisible = true
            deaths = 9
            completed = true
        }
        val decoded = Mines.decodeUi(state, "D2", base) as Mines.Ui
        assertTrue(decoded === base, "C overlays the record on the fresh UI")
        assertEquals(2, decoded.deaths)
        assertTrue(decoded.completed, "a decoder never clears the flag")
        assertEquals(4, decoded.curX, "the cursor is not in the record")
    }

    @Test(timeout = 120_000)
    fun `the timer follows the C formula and the midend prefix`() {
        val midend = Midend.newGame(Mines, "9x9n10", "12345")
        assertFalse(Mines.timingState(midend.state, midend.ui))
        assertFalse(midend.timing)
        click(midend, 4, 4)
        assertTrue(Mines.timingState(midend.state, midend.ui))
        assertTrue(midend.timing)

        assertFalse(midend.timerTick(0.5f))
        assertTrue(midend.timerTick(1.0f))
        assertEquals(1.5f, midend.elapsed)
        assertEquals("[0:01] Marked: 0 / 10", midend.statusText())
        midend.timerTick(60f)
        assertEquals("[1:01] Marked: 0 / 10", midend.statusText())

        // Death stops the clock; the elapsed value is kept.
        click(midend, 0, 0)
        assertTrue((midend.state as Mines.State).dead)
        assertFalse(midend.timing)
        val stopped = midend.elapsed
        midend.timerTick(10f)
        assertEquals(stopped, midend.elapsed)

        // Undo out of the death restarts the clock.
        assertTrue(midend.undo())
        assertTrue(midend.timing)
        assertFalse(midend.timerTick(0.25f), "the elapsed value is not reset by undo")
        assertTrue(midend.timerTick(0.5f))

        // A live solve does not stop it; a win does.
        assertTrue(midend.solve())
        assertTrue(midend.timing)
        val win = Midend.newGame(Mines, "4x4n1", "7")
        click(win, 0, 0)
        click(win, 0, 3)
        assertFalse(win.timing)
    }

    @Test(timeout = 120_000)
    fun `TIME round-trips through a save in both directions`() {
        val midend = opened()
        midend.timerTick(12.5f)
        val text = midend.serialise().serialise()
        assertTrue(text.contains("TIME    :4:12.5\n"), text)

        val reloaded = Midend.fromSave(Mines, SgtpSave.parse(text))
        assertEquals(12.5f, reloaded.elapsed)
        assertEquals(text, reloaded.serialise().serialise())
        assertTrue(reloaded.timing)

        val withTime = text.replace("TIME    :4:12.5", "TIME    :5:61.25")
        val reloadedTime = Midend.fromSave(Mines, SgtpSave.parse(withTime))
        assertEquals(61.25f, reloadedTime.elapsed)
        assertEquals(withTime, reloadedTime.serialise().serialise())
    }

    @Test(timeout = 120_000)
    fun `the TIME parser takes the C numeric prefix`() {
        val vectors = listOf(
            "0" to 0f,
            "3.5" to 3.5f,
            "61.25" to 61.25f,
            "3.5junk" to 3.5f,
            "61.25x" to 61.25f,
            " 3.5" to 3.5f,
            "abc" to 0f,
            "" to 0f,
            "1e2junk" to 100f,
            "1e" to 1f,
            ".5junk" to 0.5f,
            "+.5" to 0.5f,
            "-2.5junk" to -2.5f,
            "0x10" to 16f,
            "0x1.8p1" to 3f,
            "0x1p3" to 8f,
            "0x" to 0f,
            "1e999" to Float.POSITIVE_INFINITY,
            "-1e999" to Float.NEGATIVE_INFINITY,
            "inf" to Float.POSITIVE_INFINITY,
            "-infinity" to Float.NEGATIVE_INFINITY,
        )
        for ((text, expected) in vectors) {
            assertEquals(expected, cAtof(text), text)
        }
        assertTrue(cAtof("nan").isNaN())
        assertTrue(cAtof("NaN").isNaN())

        // The prefix reaches `elapsed`, and the writer canonicalizes it.
        val opened = opened()
        val text = opened.serialise().serialise()
        val withJunk = text.replace("TIME    :1:0", "TIME    :7:3.5junk")
        val reloaded = Midend.fromSave(Mines, SgtpSave.parse(withJunk))
        assertEquals(3.5f, reloaded.elapsed)
        assertEquals(
            text.replace("TIME    :1:0", "TIME    :3:3.5"),
            reloaded.serialise().serialise(),
        )
    }

    @Test
    fun `the key labels and request keys are the C answers`() {
        val midend = Midend.newGame(Mines, "9x9n10", "12345")
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT),
            Mines.requestKeys(midend.params),
        )
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_ONLY),
            Mines.requestKeys(midend.params, fullCursor = true),
        )

        val ui = midend.ui as Mines.Ui
        assertEquals("", Mines.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
        assertTrue(ui.curVisible)
        assertEquals("Uncover", Mines.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Mark", Mines.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))

        midend.processKey(0, 0, Buttons.CURSOR_SELECT2, tile)
        assertEquals(Mines.MARKED, (midend.state as Mines.State).grid[0])
        assertEquals(
            "Unmark",
            Mines.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2),
        )
        assertEquals("", Mines.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
    }

    @Test(timeout = 120_000)
    fun `the palette geometry and draw cache match the C constants`() {
        val colours = Mines.colours()
        assertEquals(20, colours.size / 3)
        assertEquals(0.8f, colours[0])
        assertEquals(0.8f * 19.0F / 20.0F, colours[3])
        assertEquals(0.0f, colours[6])
        assertEquals(1.0f, colours[8])
        assertEquals(0.8f * 2.0F / 3.0F, colours[17 * 3])
        assertEquals(1.0f, colours[19 * 3])
        assertEquals(0.5f, colours[19 * 3 + 1])
        assertEquals(0.5f, colours[19 * 3 + 2])

        val params = Mines.decodeParams("9x9n10")
        assertEquals(Size(196, 196), Mines.computeSize(params, 20))
        assertEquals(Size(403, 403), Mines.computeSize(params, 43))
        assertEquals(Size(718, 718), Mines.computeSize(params, 78))
        assertEquals(Size(592, 592), Mines.computeSize(params, 64))
        assertEquals(Size(601, 601), Mines.computeSize(params, 65))
        assertEquals(Size(916, 916), Mines.computeSize(params, 100))

        val midend = opened()
        val ds = Mines.newDrawState(midend.state, tile, RecordingSink()) as Mines.DrawState
        assertEquals(81, ds.grid.size)
        assertTrue(ds.grid.all { it == -99 })
        assertEquals(-1, ds.bg)
        assertFalse(ds.started)
        assertEquals(-1, ds.curX)
        assertEquals(-1, ds.curY)
    }

    @Test(timeout = 120_000)
    fun `a vacuous click changes nothing and a real one changes the board`() {
        val midend = Midend.newGame(Mines, "9x9n10", "12345")
        val px = 4 * tile + 8 + tile / 2
        val py = 4 * tile + 8 + tile / 2

        // A press alone is UI-only.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(
            px, py, Buttons.LEFT_BUTTON, tile,
        ))
        assertEquals(1, midend.stateCount)
        assertNull(midend.privDesc)
        assertTrue((0 until 81).all { (midend.state as Mines.State).grid[it] == Mines.UNKNOWN })

        // The positive control: the release opens the cell.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(
            px, py, Buttons.LEFT_RELEASE, tile,
        ))
        assertEquals(2, midend.stateCount)
        assertTrue(midend.privDesc != null)

        // A press outside the grid is unused.
        assertEquals(KeyOutcome.Unused, midend.processKey(
            0, 0, Buttons.LEFT_BUTTON, tile,
        ))
        assertEquals(2, midend.stateCount)
        // C keeps the outside press held, so the release must clear it
        // before the next button. An outside release is UI-only.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(
            0, 0, Buttons.LEFT_RELEASE, tile,
        ))
        assertEquals(2, midend.stateCount)

        // A right press on a covered cell adds one flag move.
        val flagX = 0 * tile + 8 + tile / 2
        val flagY = 0 * tile + 8 + tile / 2
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(
            flagX, flagY, Buttons.RIGHT_BUTTON, tile,
        ))
        assertEquals(3, midend.stateCount)
        assertEquals(Mines.MARKED, (midend.state as Mines.State).grid[0])
        // The release is not a game key.
        assertEquals(KeyOutcome.Unused, midend.processKey(
            flagX, flagY, Buttons.RIGHT_RELEASE, tile,
        ))
        assertEquals(3, midend.stateCount)
    }

    @Test(timeout = 120_000)
    fun `the left button beats an overlapping right press`() {
        val midend = Midend.newGame(Mines, "9x9n10", "12345")
        val x = 4 * tile + 8 + tile / 2
        val y = 4 * tile + 8 + tile / 2
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(
            x, y, Buttons.LEFT_BUTTON, tile,
        ))
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(x, y, Buttons.RIGHT_BUTTON, tile),
        )
        assertEquals(1, midend.stateCount)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(x, y, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals(2, midend.stateCount)
    }

    @Test(timeout = 120_000)
    fun `the generated layout can be read as an unmasked bitmap`() {
        val run = Mines.generateLayoutForSeed(
            Mines.Params(9, 9, 10, true, -1, -1),
            "12345",
            4,
            4,
        )
        assertEquals("4,4,m9eed07198e89048f2e4d0", run.desc)
        assertEquals(1, run.attempts)
        assertTrue(run.mines.count { it } == 10)

        val unmasked = Mines.describeLayout(run.mines, 81, 4, 4, false)
        assertTrue(unmasked.startsWith("4,4,u"))
        val state = Mines.newState(
            Mines.Params(9, 9, 10, true, -1, -1),
            unmasked,
        ) as Mines.State
        assertTrue(checkNotNull(state.layout.mines).contentEquals(run.mines))
    }

    private fun assertSameLayout(a: Mines.State, b: Mines.State) {
        assertTrue(a.layout === b.layout, "the layout object is shared")
    }

    private fun rightClick(midend: Midend, x: Int, y: Int, t: Int = tile) {
        val px = x * t + 8 + t / 2
        val py = y * t + 8 + t / 2
        midend.processKey(px, py, Buttons.RIGHT_BUTTON, t)
        midend.processKey(px, py, Buttons.RIGHT_RELEASE, t)
    }

    private fun middleClick(midend: Midend, x: Int, y: Int, t: Int = tile) {
        val px = x * t + 8 + t / 2
        val py = y * t + 8 + t / 2
        midend.processKey(px, py, Buttons.MIDDLE_BUTTON, t)
        midend.processKey(px, py, Buttons.MIDDLE_RELEASE, t)
    }

    /** A sink that records nothing; the input path never draws. */
    private class RecordingSink : DrawSink {
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun thickLine(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
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
        override fun blitterNew(width: Int, height: Int): Int = 1
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
