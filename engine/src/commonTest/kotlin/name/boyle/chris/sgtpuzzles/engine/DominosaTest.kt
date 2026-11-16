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
 * Level: engine and semantic. Game: dominosa.
 *
 * The port of `dominosa.c`, driven directly and through [Midend]. The
 * generated descriptions, the retry counters, the solver technique
 * coverage, the move grammar, the text form, the palette, and the C
 * undefined-behavior definitions are pinned here.
 *
 * The oracle comparisons live in the `:parity` module. This file holds
 * fixtures measured from the oracle so the engine is testable alone.
 */
class DominosaTest {

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Dominosa", Dominosa.name)
        assertEquals("dominosa", Dominosa.backendName)
        assertEquals(32, Dominosa.preferredTileSize)
        assertTrue(Dominosa.canSolve)
        assertFalse(Dominosa.wantsStatusBar)
        assertFalse(Dominosa.isTimed)
        assertEquals(0, Dominosa.flags)
        assertTrue(Dominosa.canFormatAsText)

        val midend = Midend.newGame(Dominosa, "", SEED)
        assertTrue(Dominosa.textFormat(midend.state).isNotEmpty())
        assertEquals(Dominosa.textFormat(midend.state), midend.textFormat())
        assertEquals(0, Dominosa.status(midend.state))
        assertEquals("", Dominosa.statusText(midend.state, midend.ui))
    }

    @Test
    fun `the parameters and presets are the C table`() {
        val d = Dominosa.defaultParams() as Dominosa.Params
        assertEquals(Dominosa.Params(6, 1), d)
        assertEquals("6db", Dominosa.encodeParams(d, true))
        assertEquals("6", Dominosa.encodeParams(d, false))

        val presets = Dominosa.presets()
        assertEquals(12, presets.size)
        assertEquals(
            listOf(
                "Order 3, Trivial" to "3dt",
                "Order 4, Trivial" to "4dt",
                "Order 5, Trivial" to "5dt",
                "Order 6, Trivial" to "6dt",
                "Order 4, Basic" to "4db",
                "Order 5, Basic" to "5db",
                "Order 6, Basic" to "6db",
                "Order 7, Basic" to "7db",
                "Order 8, Basic" to "8db",
                "Order 9, Basic" to "9db",
                "Order 6, Hard" to "6dh",
                "Order 6, Extreme" to "6de",
            ),
            presets.map { it.title to it.params },
        )
        for (preset in presets) {
            assertEquals(
                preset.params,
                Dominosa.encodeParams(Dominosa.decodeParams(preset.params), true),
            )
        }
    }

    @Test
    fun `the decode quirks match C`() {
        fun p(text: String) = Dominosa.decodeParams(text) as Dominosa.Params

        assertEquals(Dominosa.Params(6, 1), p("6"))
        assertEquals(Dominosa.Params(6, 6), p("6d"))
        assertEquals(Dominosa.Params(6, 6), p("6dx"))
        assertEquals(Dominosa.Params(6, 6), p("6d0"))
        assertEquals(Dominosa.Params(6, 6), p("6d?"))
        assertEquals(Dominosa.Params(6, 2), p("6dh"))
        assertEquals(Dominosa.Params(6, 0), p("6dth"))
        assertEquals(Dominosa.Params(6, 0), p("6dte"))
        assertEquals(Dominosa.Params(6, 1), p("6dbt"))
        assertEquals(Dominosa.Params(6, 4), p("6a"))
        // The `d` scan accepts the `a` in `diffchars`, so `6da` is the
        // legacy ambiguous form too.
        assertEquals(Dominosa.Params(6, 4), p("6da"))
        assertEquals(Dominosa.Params(6, 6), p("6ad"))
        assertEquals(Dominosa.Params(6, 1), p("+6"))
        assertEquals(Dominosa.Params(6, 1), p(" 6"))
        assertEquals(Dominosa.Params(6, 1), p("06"))
        assertEquals(Dominosa.Params(6, 1), p("6x"))
        assertEquals(Dominosa.Params(12, 1), p("12"))
        assertEquals(Dominosa.Params(0, 4), p("a"))
        assertEquals(Dominosa.Params(0, 1), p(""))
        assertEquals(Dominosa.Params(-1, 1), p("-1"))
        // The digit skip starts at the string start, so a sign stops it
        // and the remaining characters are single "other" characters.
        // The `d` in "+6d" is therefore seen and invalidates the rating.
        assertEquals(Dominosa.Params(6, 6), p("+6d"))
    }

    @Test
    fun `the atoi scan saturates and an unknown difficulty encodes as d-question`() {
        assertEquals(
            Int.MAX_VALUE,
            (Dominosa.decodeParams("99999999999999999999") as Dominosa.Params).n,
        )
        assertEquals(
            Int.MIN_VALUE,
            (Dominosa.decodeParams("-99999999999999999999") as Dominosa.Params).n,
        )
        // `encode_params` indexes `dominosa_diffchars[diff]` without a
        // bound. The port defines an unknown value as '?'.
        assertEquals("6d?", Dominosa.encodeParams(Dominosa.Params(6, 5), true))
        assertEquals("6d?", Dominosa.encodeParams(Dominosa.Params(6, -1), true))
        assertEquals("6d?", Dominosa.encodeParams(Dominosa.Params(6, 6), true))
        assertEquals("6db", Dominosa.encodeParams(Dominosa.Params(6, 1), true))
    }

    @Test
    fun `validation uses the C order and the 46339 boundary`() {
        fun v(n: Int, diff: Int) =
            Dominosa.validateParams(Dominosa.Params(n, diff), full = true)

        assertEquals("Maximum face number must be at least one", v(0, 1))
        assertEquals("Maximum face number must be at least one", v(-1, 1))
        assertEquals(
            "Maximum face number must not be unreasonably large",
            v(Int.MAX_VALUE, 1),
        )
        assertEquals("Unknown difficulty rating", v(6, 5))
        assertEquals("Unknown difficulty rating", v(6, 6))

        // The order matters: the size test runs before the difficulty
        // test.
        assertEquals("Maximum face number must be at least one", v(0, 5))
        assertEquals(
            "Maximum face number must not be unreasonably large",
            v(46340, 5),
        )

        // `n + 2 <= INT_MAX / (n + 1)`.
        assertNull(v(46339, 1))
        assertEquals(
            "Maximum face number must not be unreasonably large",
            v(46340, 1),
        )

        // A negative difficulty is not rejected by C.
        assertNull(v(6, -1))
        assertEquals(
            "Unknown difficulty rating",
            Dominosa.validateParams(Dominosa.Params(6, 5), full = false),
        )
    }

    @Test
    fun `the description grammar and its errors`() {
        val params = Dominosa.decodeParams("3dt")
        val good = "01012311032222033310"
        assertNull(Dominosa.validateDesc(params, good))
        assertEquals(
            "Game description shorter than expected",
            Dominosa.validateDesc(params, good.dropLast(1)),
        )
        assertEquals(
            "Game description longer than expected",
            Dominosa.validateDesc(params, good + "0"),
        )
        assertEquals(
            "Incorrect number balance in game description",
            Dominosa.validateDesc(params, "0".repeat(20)),
        )
        assertEquals(
            "Invalid syntax in game description",
            Dominosa.validateDesc(params, "x" + good.drop(1)),
        )
        assertEquals(
            "Missing ']' in game description",
            Dominosa.validateDesc(params, "[x" + good.drop(2)),
        )
        assertEquals(
            "Number out of range in game description",
            Dominosa.validateDesc(params, "9" + good.drop(1)),
        )

        // `10` is two tokens, not the number ten: the parse then runs out
        // of characters instead of reporting an out-of-range number.
        assertEquals(
            "Game description shorter than expected",
            Dominosa.validateDesc(params, "10"),
        )
        // `atoi` reads the leading characters and the digit skip starts
        // where the text does.
        assertEquals(
            "Missing ']' in game description",
            Dominosa.validateDesc(params, "[ 11]" + good.drop(4)),
        )
        assertEquals(
            "Missing ']' in game description",
            Dominosa.validateDesc(params, "[+11]" + good.drop(4)),
        )
        assertEquals(
            "Missing ']' in game description",
            Dominosa.validateDesc(params, "[-1]" + good.drop(3)),
        )
        assertEquals(
            "Missing ']' in game description",
            Dominosa.validateDesc(params, "[11x]" + good.drop(4)),
        )
        // A saturated `atoi` is above `n`, so the range test reports it.
        assertEquals(
            "Number out of range in game description",
            Dominosa.validateDesc(params, "[99999999999999999999]"),
        )
    }

    @Test
    fun `bracket tokens above nine parse in newState`() {
        // `n = 11`: every number appears `n + 2 = 13` times.
        val tokens = ArrayList<String>()
        for (value in 0..11) {
            repeat(13) { tokens += if (value >= 10) "[$value]" else value.toString() }
        }
        val desc = tokens.joinToString("")
        val params = Dominosa.Params(11, 1)
        assertNull(Dominosa.validateDesc(params, desc))

        val state = Dominosa.newState(params, desc) as Dominosa.State
        assertEquals(0, state.numbers.values[0])
        assertEquals(11, state.numbers.values[state.wh - 1])

        // `[011]` is eleven: `atoi` and the digit skip both take it.
        val tokenList = tokens.toMutableList()
        val firstEleven = tokenList.indexOfFirst { it == "[11]" }
        tokenList[firstEleven] = "[011]"
        val quirky = tokenList.joinToString("")
        assertNull(Dominosa.validateDesc(params, quirky))
        val quirkyState = Dominosa.newState(params, quirky) as Dominosa.State
        assertEquals(11, quirkyState.numbers.values[firstEleven])
    }

    @Test
    fun `a malformed description is rejected by newState`() {
        val params = Dominosa.decodeParams("3dt")
        assertFailsWith<IllegalArgumentException> {
            Dominosa.newState(params, "0101231103222203331")
        }
        assertFailsWith<IllegalArgumentException> {
            Dominosa.newState(params, "01012311032222033310x")
        }
        assertFailsWith<IllegalArgumentException> {
            Dominosa.newState(params, "0101231103222203331]")
        }
        assertFailsWith<IllegalArgumentException> {
            Dominosa.newState(params, "91012311032222033310")
        }
        assertFailsWith<IllegalArgumentException> {
            Dominosa.newState(Dominosa.Params(11, 1), "[11")
        }
    }

    @Test
    fun `the frozen default description and auxiliary answer are pinned`() {
        val midend = Midend.newGame(Dominosa, "", SEED)
        assertEquals(
            "6:36035216262405121355164306536241046314351143260400522054",
            midend.id(),
        )
        assertEquals(
            "36035216262405121355164306536241046314351143260400522054",
            midend.desc,
        )
        assertEquals("6db", Dominosa.encodeParams(midend.params, true))
        assertEquals("6db", Dominosa.encodeParams(midend.currentParams, true))
        assertEquals("6", Dominosa.encodeParams(midend.currentParams, false))

        // The layout text, as the oracle's `AUXINFO` decodes it.
        assertEquals(
            "TTTLRTTTBBBLRBBBLRLRLRLRLRTLRLRTTTBTTTTBBBTBBBBTLRBLRLRB",
            midend.auxInfo,
        )
        val save = midend.serialise()
        assertEquals(
            "d7afe253df5041917c45656dab74d2b234ca87f0b3cd5cf996c14722d9454d28" +
                "f63bdaef51a9cf233bc17c9f64d9f05109eda931abaa76eb",
            save.value("AUXINFO"),
        )
        assertEquals(
            listOf(
                "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
            ),
            save.records.map { it.key },
        )
        assertNull(save.value("UI"))
        assertNull(save.value("TIME"))
    }

    @Test
    fun `the small-board difficulty cap keeps the requested CPARAMS`() {
        for (seed in listOf("1", "2", "3", "12345", "seed")) {
            val trivial = Midend.newGame(Dominosa, "1dt", seed)
            val basic = Midend.newGame(Dominosa, "1db", seed)
            val extreme = Midend.newGame(Dominosa, "1de", seed)
            val ambiguous = Midend.newGame(Dominosa, "1da", seed)
            assertEquals(trivial.desc, basic.desc, "seed $seed 1dt/1db")
            assertEquals(trivial.desc, extreme.desc, "seed $seed 1dt/1de")
            assertEquals(trivial.id(), extreme.id(), "seed $seed 1dt id")
            assertNotEquals(trivial.desc, ambiguous.desc, "seed $seed 1dt/1da")
            assertEquals("1db", trivial.serialise().value("PARAMS"))
            assertEquals("1dt", trivial.serialise().value("CPARAMS"))
            assertEquals("1de", extreme.serialise().value("CPARAMS"))

            val twoBasic = Midend.newGame(Dominosa, "2db", seed)
            val twoHard = Midend.newGame(Dominosa, "2dh", seed)
            val twoExtreme = Midend.newGame(Dominosa, "2de", seed)
            val twoAmbiguous = Midend.newGame(Dominosa, "2da", seed)
            assertEquals(twoBasic.desc, twoHard.desc, "seed $seed 2db/2dh")
            assertEquals(twoBasic.desc, twoExtreme.desc, "seed $seed 2db/2de")
            assertNotEquals(twoBasic.desc, twoAmbiguous.desc, "seed $seed 2db/2da")
            assertEquals("2db", twoBasic.serialise().value("PARAMS"))
            assertEquals("2dh", twoHard.serialise().value("CPARAMS"))
        }
        assertEquals("1:110001", Midend.newGame(Dominosa, "1dt", SEED).id())
        assertEquals("1:110001", Midend.newGame(Dominosa, "1de", SEED).id())
        assertEquals("1:011010", Midend.newGame(Dominosa, "1da", SEED).id())
        assertEquals("2:210002210211", Midend.newGame(Dominosa, "2dh", SEED).id())
        assertEquals("2:212120102010", Midend.newGame(Dominosa, "2da", SEED).id())
    }

    @Test
    fun `an ambiguous board takes the first attempt and skips the gate`() {
        for (seed in listOf("0", "1", "2", "12345", "another seed")) {
            val generated = Dominosa.newDescWithStats(
                Dominosa.decodeParams("6da"),
                RandomState.fromSeed(seed),
            )
            assertEquals(1, generated.stats.attempts, "seed $seed attempts")
            assertEquals(56, generated.description.text.length)
        }
    }

    @Test(timeout = 120_000)
    fun `the generator retry counters pin each allocator path`() {
        fun stats(params: String, seed: String) = Dominosa.newDescWithStats(
            Dominosa.decodeParams(params),
            RandomState.fromSeed(seed),
        ).stats

        val trivial = stats("6dt", "0")
        assertEquals(25, trivial.attempts)
        assertEquals(1, trivial.layoutFailures)
        assertEquals(23, trivial.ambiguousRejects)

        val basic = stats("6db", "1")
        assertEquals(220, basic.attempts)
        assertEquals(16, basic.layoutFailures)
        assertEquals(1, basic.tooEasyRejects)

        val hard = stats("6dh", "1")
        assertEquals(352, hard.attempts)
        assertEquals(139, hard.layoutFailures)
        assertEquals(2, hard.basicGateRejects)
        assertEquals(84, hard.easyOpeningRejects)
        assertEquals(126, hard.ambiguousRejects)

        val extreme = stats("6de", "1")
        assertEquals(377, extreme.attempts)
        assertEquals(145, extreme.layoutFailures)
        assertEquals(2, extreme.basicGateRejects)
        assertEquals(94, extreme.easyOpeningRejects)
        assertEquals(2, extreme.tooEasyRejects)
    }

    @Test(timeout = 120_000)
    fun `the solver reaches the hard and extreme techniques`() {
        val easy = Dominosa.analyse(Midend.newGame(Dominosa, "6db", "0").state)
        assertEquals(1, easy.ret)
        assertEquals(1, easy.maxDiffUsed)

        val hard = Dominosa.analyse(Midend.newGame(Dominosa, "6dh", "0").state)
        assertEquals(1, hard.ret)
        assertEquals(2, hard.maxDiffUsed)
        assertTrue(hard.stats.hardSet > 0)
        assertEquals(0, hard.stats.extremeSet)
        assertEquals(0, hard.stats.extremeForcingChain)

        val extremeSet = Dominosa.analyse(Midend.newGame(Dominosa, "6de", "0").state)
        assertEquals(1, extremeSet.ret)
        assertEquals(3, extremeSet.maxDiffUsed)
        assertTrue(extremeSet.stats.extremeSet > 0)

        val chain = Dominosa.analyse(Midend.newGame(Dominosa, "6de", "1").state)
        assertEquals(1, chain.ret)
        assertEquals(3, chain.maxDiffUsed)
        assertTrue(chain.stats.extremeForcingChain > 0)
    }

    @Test(timeout = 120_000)
    fun `every non-ambiguous preset solves at its own difficulty`() {
        for (preset in Dominosa.presets()) {
            val params = Dominosa.decodeParams(preset.params) as Dominosa.Params
            val midend = Midend.newGame(Dominosa, preset.params, SEED)
            val report = Dominosa.analyse(midend.state)
            assertEquals(1, report.ret, "ret for ${preset.params}")
            assertTrue(
                report.maxDiffUsed >= params.diff,
                "difficulty for ${preset.params}",
            )
        }
    }

    @Test
    fun `a state copy shares numbers and copies the arrays`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val state = midend.state as Dominosa.State
        assertEquals(5, state.w)
        assertEquals(4, state.h)
        for (i in 0 until state.wh) {
            assertEquals(i, state.grid[i])
            assertEquals(0, state.edges[i])
        }
        assertFalse(state.completed)
        assertFalse(state.cheated)

        val copy = state.copy() as Dominosa.State
        assertNotSame(state, copy)
        assertSame(state.numbers, copy.numbers)
        assertNotSame(state.grid, copy.grid)
        assertNotSame(state.edges, copy.edges)
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())

        val moved = Dominosa.executeMove(state, "D0,1") as Dominosa.State
        assertSame(state.numbers, moved.numbers)
        assertNotSame(state.grid, moved.grid)
        assertNotEquals(state, moved)
    }

    @Test
    fun `the move grammar matches the measured table`() {
        val state = Midend.newGame(Dominosa, "3dt", SEED).state
        val accepted = listOf(
            "",
            "D0,1",
            "D0,1;D0,1",
            "D0,1;",
            "S",
            "S;D0,5",
            "S;E0,1",
            "D 0,1",
            "D+0,1",
        )
        for (move in accepted) {
            assertTrue(Dominosa.executeMove(state, move) != null, "accept '$move'")
        }
        val invalid = listOf(
            "D1,0",
            "D0,2",
            "D19,18",
            "D3,3",
            "D0,1;E0,1",
            "D0,1;E1,0",
            "X",
            "D0,1X",
            "D0,1;;",
            "D",
            "D0",
            "D0,",
            "E",
            "0,1",
        )
        for (move in invalid) {
            assertNull(Dominosa.executeMove(state, move), "reject '$move'")
        }
    }

    @Test
    fun `the row-wrap grammar quirk is accepted`() {
        // `D` and `E` only require `d2 - d1 == 1 || d2 - d1 == w`; the
        // column is not checked, so 4 and 5 are a "domino" across rows.
        val state = Midend.newGame(Dominosa, "3dt", SEED).state
        val wrapped = Dominosa.executeMove(state, "D4,5") as Dominosa.State
        assertEquals(5, wrapped.grid[4])
        assertEquals(4, wrapped.grid[5])

        val edged = Dominosa.executeMove(state, "S;E4,5") as Dominosa.State
        assertEquals(0x200, edged.edges[4] and 0x200)
        assertEquals(0x100, edged.edges[5] and 0x100)
    }

    @Test
    fun `an edge is an echo pair and a domino destroys it`() {
        val state = Midend.newGame(Dominosa, "3dt", SEED).state
        val w = 5

        val horizontal = Dominosa.executeMove(state, "S;E0,1") as Dominosa.State
        assertEquals(0x200, horizontal.edges[0] and 0x200)
        assertEquals(0x100, horizontal.edges[1] and 0x100)

        val vertical = Dominosa.executeMove(state, "S;E0,$w") as Dominosa.State
        assertEquals(0x800, vertical.edges[0] and 0x800)
        assertEquals(0x400, vertical.edges[w] and 0x400)

        // Placing a domino clears the edges on both squares and the echo
        // bit on every neighbour.
        val cleared = Dominosa.executeMove(state, "S;E0,$w;E0,1;D0,1") as Dominosa.State
        for (i in 0 until cleared.wh) {
            assertEquals(0, cleared.edges[i], "edge $i")
        }

        // A different edge next to the second square is cleared too.
        val side = Dominosa.executeMove(state, "S;E1,2;D0,1") as Dominosa.State
        assertEquals(0, side.edges[1] and 0x200)
        assertEquals(0, side.edges[2] and 0x100)

        // Toggling a domino off does not restore an edge.
        val toggled = Dominosa.executeMove(state, "S;D0,1;D0,1") as Dominosa.State
        assertEquals(0, toggled.grid[0])
        assertEquals(1, toggled.grid[1])
    }

    @Test
    fun `S clears the grid and the edges and sets cheated`() {
        val state = Midend.newGame(Dominosa, "3dt", SEED).state
        val dirty = Dominosa.executeMove(state, "S;D0,1;E2,3") as Dominosa.State
        assertTrue(dirty.cheated)
        assertTrue(dirty.edges.any { it != 0 })

        val cleared = Dominosa.executeMove(dirty, "S") as Dominosa.State
        assertTrue(cleared.cheated)
        for (i in 0 until cleared.wh) {
            assertEquals(i, cleared.grid[i])
            assertEquals(0, cleared.edges[i])
        }
    }

    @Test
    fun `the completion count uses distinct domino values`() {
        val open = Midend.newGame(Dominosa, "3dt", SEED)
        val move = auxMove(open.auxInfo!!)
        val completed = Dominosa.executeMove(open.state, move.removePrefix("S;")) as Dominosa.State
        assertFalse(completed.cheated)
        assertTrue(completed.completed)
        assertEquals(1, Dominosa.status(completed))

        // Two copies of one domino do not count twice.
        val one = Dominosa.executeMove(open.state, "D0,1") as Dominosa.State
        assertFalse(one.completed)
        val duplicate = Dominosa.executeMove(open.state, "S;D0,1;D2,3") as Dominosa.State
        assertEquals(0, duplicate.edges.sum())
        assertFalse(duplicate.completed)
    }

    @Test
    fun `the completion flag is sticky across undo and redo`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertTrue(midend.canUndo)
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertTrue(midend.canRedo)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())

        // A restart goes back to the open board.
        assertTrue(midend.restart())
        assertEquals(0, midend.status())
    }

    @Test
    fun `changed state hides the cursor and never clears highlights`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val ui = midend.ui as Dominosa.Ui
        val open = midend.state as Dominosa.State
        val move = auxMove(midend.auxInfo!!)
        val completed = Dominosa.executeMove(open, move.removePrefix("S;")) as Dominosa.State
        assertTrue(completed.completed)
        assertFalse(completed.cheated)

        Dominosa.setCursorVisible(ui, true)
        ui.highlight1 = 3
        ui.highlight2 = 1
        assertTrue(Dominosa.changedState(ui, open, completed))
        assertFalse(ui.curVisible, "the cursor is hidden")
        assertEquals(3, ui.highlight1, "the highlights survive")
        assertEquals(1, ui.highlight2)

        // The solve transition is cheated, so it never reports a win, but
        // it still hides the cursor.
        val cheated = Dominosa.executeMove(open, move) as Dominosa.State
        assertTrue(cheated.cheated)
        Dominosa.setCursorVisible(ui, true)
        assertFalse(Dominosa.changedState(ui, open, cheated))
        assertFalse(ui.curVisible)

        // A null old state is not a completion.
        assertFalse(Dominosa.changedState(ui, null, completed))
    }

    @Test
    fun `current key labels are Place Remove and Line`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val ui = midend.ui
        assertEquals("", Dominosa.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))

        // The initial cursor is (0,0), even parity. One right press moves
        // to (1,0), a horizontal pair.
        assertEquals(
            MoveResult.UiUpdate,
            Dominosa.interpretMove(
                midend.state,
                ui,
                0,
                0,
                Buttons.CURSOR_RIGHT,
                32,
            ),
        )
        assertEquals("Place", Dominosa.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Line", Dominosa.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))
        assertEquals("", Dominosa.currentKeyLabel(ui, midend.state, '0'.code))

        val placed = Dominosa.executeMove(midend.state, "D0,1") as Dominosa.State
        assertEquals("Remove", Dominosa.currentKeyLabel(ui, placed, Buttons.CURSOR_SELECT))
        assertEquals(
            "",
            Dominosa.currentKeyLabel(ui, placed, Buttons.CURSOR_SELECT2),
        )

        // One down press: (1,1) is even parity, another down (1,2) is a
        // vertical pair.
        Dominosa.interpretMove(midend.state, ui, 0, 0, Buttons.CURSOR_DOWN, 32)
        Dominosa.interpretMove(midend.state, ui, 0, 0, Buttons.CURSOR_DOWN, 32)
        val edged = Dominosa.executeMove(midend.state, "S;E0,5") as Dominosa.State
        assertEquals("Line", Dominosa.currentKeyLabel(ui, edged, Buttons.CURSOR_SELECT2))
        val horizontalEdge = Dominosa.executeMove(midend.state, "S;E5,6") as Dominosa.State
        assertEquals(
            "Remove",
            Dominosa.currentKeyLabel(ui, horizontalEdge, Buttons.CURSOR_SELECT2),
        )

        // The label ignores the cursor visibility, as C does.
        Dominosa.setCursorVisible(ui, false)
        assertEquals("Place", Dominosa.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `request keys are the digits up to nine`() {
        for ((params, count) in listOf("3dt" to 4, "6db" to 7, "9db" to 10)) {
            val request = Dominosa.requestKeys(Dominosa.decodeParams(params))
            assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, request.arrowMode)
            assertEquals(count, request.labels.size)
            for (i in 0 until count) {
                assertEquals('0'.code + i, request.labels[i].button)
                assertEquals(i.toString(), request.labels[i].name)
                assertFalse(request.labels[i].needsArrows)
            }
        }
        assertEquals(1, Dominosa.requestKeys(Dominosa.Params(0, 1)).labels.size)
        assertEquals(
            ArrowMode.ARROWS_LEFT_RIGHT,
            Dominosa.requestKeys(Dominosa.decodeParams("3dt"), fullCursor = true).arrowMode,
        )
    }

    @Test
    fun `the text form matches the measured boards`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        assertEquals(
            "                     \n" +
                "  0   1   0   1   2  \n" +
                "                     \n" +
                "  3   1   1   0   3  \n" +
                "                     \n" +
                "  2   2   2   2   0  \n" +
                "                     \n" +
                "  3   3   3   1   0  \n" +
                "                     \n",
            Dominosa.textFormat(midend.state),
        )

        val solved = Dominosa.executeMove(midend.state, auxMove(midend.auxInfo!!)) as Dominosa.State
        assertEquals(
            "+---+---+-------+---+\n" +
                "| 0 | 1 | 0   1 | 2 |\n" +
                "|   |   +---+---+   |\n" +
                "| 3 | 1 | 1 | 0 | 3 |\n" +
                "+---+---+   |   +---+\n" +
                "| 2   2 | 2 | 2 | 0 |\n" +
                "+-------+---+---+   |\n" +
                "| 3   3 | 3   1 | 0 |\n" +
                "+-------+-------+---+\n",
            Dominosa.textFormat(solved),
        )

        // An edge tick and a two-digit number.
        val edged = Dominosa.executeMove(midend.state, "S;E0,1") as Dominosa.State
        val lines = Dominosa.textFormat(edged).lines()
        assertEquals('|', lines[1][4])
        assertTrue(lines[1].startsWith("  0 |"))
        val twoDigit = Dominosa.executeMove(edged, "") as Dominosa.State
        twoDigit.numbers.values[0] = 42
        assertTrue(Dominosa.textFormat(twoDigit).lines()[1].contains("42"))
        twoDigit.numbers.values[0] = 999
        assertTrue(Dominosa.textFormat(twoDigit).lines()[1].contains("999"))
    }

    @Test
    fun `the text form is empty above the C params bound`() {
        // `game_can_format_as_text_now` is `n < 1000`. The seam has no
        // params-aware hook, so textFormat defines the empty text.
        val state = Dominosa.State(
            Dominosa.Params(1000, 1),
            1002,
            1001,
            Dominosa.Numbers(IntArray(0)),
            IntArray(0),
            IntArray(0),
            false,
            false,
        )
        assertEquals("", Dominosa.textFormat(state))
        assertTrue(Dominosa.canFormatAsText)
    }

    @Test
    fun `the auxiliary solve and the solver agree with the measured strings`() {
        val midend = Midend.newGame(Dominosa, "6db", SEED)
        val aux = midend.auxInfo
        val auxMove = Dominosa.solve(midend.state, aux)!!
        assertEquals(
            "S;D0,8;D1,9;D2,10;D3,4;D5,13;D6,14;D7,15;D11,12;D16,17;" +
                "D18,19;D20,21;D22,23;D24,25;D26,34;D27,28;D29,30;D31,39;" +
                "D32,40;D33,41;D35,43;D36,44;D37,45;D38,46;D42,50;D47,55;" +
                "D48,49;D51,52;D53,54",
            auxMove,
        )

        val solverMove = Dominosa.solve(midend.state)!!
        assertEquals(651, solverMove.length)
        assertEquals(
            "S;E3,11;E4,12;E8,16;E9,17;E10,18;E11,19;E12,20;E13,21;" +
                "E14,22;E15,23;E16,24;E17,25;E18,26;E19,27;E20,28;E21,29;" +
                "E22,30;E23,31;E24,32;E25,33;E27,35;E28,36;E29,37;E30,38;" +
                "E34,42;E39,47;E40,48;E41,49;E43,51;E44,52;E45,53;E46,54;" +
                "E0,1;E1,2;E2,3;E4,5;E5,6;E6,7;E8,9;E9,10;E10,11;E12,13;" +
                "E13,14;E14,15;E17,18;E19,20;E21,22;E25,26;E26,27;E28,29;" +
                "E30,31;E32,33;E33,34;E34,35;E35,36;E36,37;E37,38;E38,39;" +
                "E40,41;E41,42;E42,43;E43,44;E44,45;E45,46;E46,47;E49,50;" +
                "E50,51;E52,53;E54,55;D0,8;D1,9;D2,10;D5,13;D6,14;D7,15;" +
                "D26,34;D31,39;D32,40;D33,41;D35,43;D36,44;D37,45;D38,46;" +
                "D42,50;D47,55;D3,4;D11,12;D16,17;D18,19;D20,21;D22,23;" +
                "D24,25;D27,28;D29,30;D48,49;D51,52;D53,54",
            solverMove,
        )
        assertEquals(auxMove, Dominosa.solve(midend.state, midend.auxInfo))

        // A short aux defines the missing characters as '.'.
        assertEquals("S", Dominosa.solve(midend.state, ""))
        assertEquals("S", Dominosa.solve(midend.state, "X"))
    }

    @Test
    fun `an already solved board solves to the same move`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val solved = Dominosa.executeMove(midend.state, auxMove(midend.auxInfo!!)) as Dominosa.State
        assertTrue(solved.completed)
        // The aux path reads the stored layout; the solver path rebuilds
        // the answer from the clue numbers and therefore returns the same
        // full E/D list as for the open board. Measured on the oracle.
        val auxAnswer = Dominosa.solve(solved, midend.auxInfo)!!
        val solverAnswer = Dominosa.solve(solved)!!
        assertTrue(auxAnswer.startsWith("S;D"))
        assertFalse(auxAnswer.contains(";E"))
        assertTrue(solverAnswer.startsWith("S;E"))
        assertEquals(Dominosa.solve(midend.state), solverAnswer)
        val reapplied = Dominosa.executeMove(solved, solverAnswer) as Dominosa.State
        assertTrue(reapplied.completed)
    }

    @Test
    fun `the geometry and the cursor rectangle are the C values`() {
        assertEquals(
            Size(304, 272),
            Dominosa.computeSize(Dominosa.decodeParams("6db"), 32),
        )
        assertEquals(
            Size(208, 176),
            Dominosa.computeSize(Dominosa.decodeParams("3dt"), 32),
        )
        assertEquals(
            Size(400, 368),
            Dominosa.computeSize(Dominosa.decodeParams("9db"), 32),
        )

        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val ui = midend.ui
        assertNull(Dominosa.cursorLocation(ui, midend.state, 32))
        Dominosa.setCursorVisible(ui, true)
        assertEquals(
            Game.CursorRect(32, 32, 18, 18),
            Dominosa.cursorLocation(ui, midend.state, 32),
        )
        Dominosa.interpretMove(midend.state, ui, 0, 0, Buttons.CURSOR_DOWN, 32)
        assertEquals(
            Game.CursorRect(32, 48, 18, 18),
            Dominosa.cursorLocation(ui, midend.state, 32),
        )
    }

    @Test
    fun `the palette is the measured table`() {
        val colours = Dominosa.colours()
        assertEquals(30, colours.size)
        val scaled = colours.map { (it * 1000f).roundToInt() }
        assertEquals(
            listOf(
                800, 800, 800,
                0, 0, 0,
                0, 0, 0,
                500, 0, 0,
                1000, 1000, 1000,
                533, 533, 533,
                0, 500, 0,
                250, 1000, 250,
                850, 200, 200,
                200, 650, 120,
            ),
            scaled,
        )
        val themed = Dominosa.colours(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertEquals(200, (themed[0] * 1000f).roundToInt())
        assertEquals(133, (themed[15] * 1000f).roundToInt())
        assertEquals(267, (themed[16] * 1000f).roundToInt())
        assertEquals(400, (themed[17] * 1000f).roundToInt())
    }

    @Test
    fun `the drawstate starts with every square invisible`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val sink = RecordingSink()
        val ds = Dominosa.newDrawState(midend.state, 32, sink) as Dominosa.DrawState
        assertEquals(5, ds.w)
        assertEquals(4, ds.h)
        assertEquals(20, ds.visible.size)
        assertTrue(ds.visible.all { it == 0xFFFF })

        Dominosa.redraw(midend.state, midend.ui, ds, sink, 32)
        assertFalse(ds.visible.any { it == 0xFFFF })
        // Every square was drawn: clip, rect, text, update, unclip.
        assertEquals(20, sink.ops.count { it == "clip" })
        assertEquals(20, sink.ops.count { it == "text" })
    }

    @Test
    fun `the cursor draw uses two rings on a useful node`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val sink = RecordingSink()
        val ds = Dominosa.newDrawState(midend.state, 32, sink)
        Dominosa.setCursorVisible(midend.ui, true)
        Dominosa.interpretMove(midend.state, midend.ui, 0, 0, Buttons.CURSOR_RIGHT, 32)
        Dominosa.redraw(midend.state, midend.ui, ds, sink, 32)
        // A node on the boundary of two tiles draws a ring on both.
        assertEquals(32, sink.ops.count { it == "line" })

        val plain = Midend.newGame(Dominosa, "3dt", SEED)
        val plainSink = RecordingSink()
        val plainDs = Dominosa.newDrawState(plain.state, 32, plainSink)
        Dominosa.setCursorVisible(plain.ui, true)
        // (0,0) has even parity, so it is not a useful node.
        Dominosa.redraw(plain.state, plain.ui, plainDs, plainSink, 32)
        assertEquals(8, plainSink.ops.count { it == "line" })
    }

    @Test
    fun `the rule-out compaction swaps with the last entry`() {
        // The real `3dt` board. Placement 3 is the first rule-out of the
        // measured solver run (`S;E3,11;...`).
        val state = Midend.newGame(Dominosa, "3dt", SEED).state as Dominosa.State
        val initial =
            Dominosa.solverPlacementLists(3, state.numbers.values, IntArray(0))
        assertEquals(listOf(2, 3, 15, 16, 17, 21, 30), initial.dominoes[1])
        assertEquals(listOf(3, 17, 18), initial.squares[3])
        assertEquals(listOf(3, 8, 21, 22), initial.squares[8])

        val compacted =
            Dominosa.solverPlacementLists(3, state.numbers.values, intArrayOf(3))
        // `dominosa.c:596-612`: the last entry replaces the removed one.
        // A stable shift would give [2, 15, 16, 17, 21, 30], [17, 18],
        // and [8, 21, 22] instead. The measured `E` list is in
        // placement-index order, so no public payload in this suite
        // sees the difference: this hook is the pin for the internal
        // order.
        assertEquals(listOf(2, 30, 15, 16, 17, 21), compacted.dominoes[1])
        assertEquals(listOf(18, 17), compacted.squares[3])
        assertEquals(listOf(22, 8, 21), compacted.squares[8])
    }

    @Test(timeout = 120_000)
    fun `the set technique defines a domino number above fifteen`() {
        // C stores `ds[othernum]` in a 16-entry array and shifts an
        // unsigned long by `othernum`. For a validated description the
        // group size is `n + 2`, so the technique only runs for
        // `n <= 14` and `othernum` stays in range. A hand-built state
        // below reaches number 16 anyway; the port keeps a 64-entry
        // table and a 64-bit mask, so the out-of-range write is defined
        // and no examined bit changes.
        val n = 16
        val w = n + 2
        val h = n + 1
        val wh = w * h
        val numbers = IntArray(wh) { 16 }
        for (i in 0 until 16) numbers[i] = 0
        val state = Dominosa.State(
            Dominosa.Params(n, 1),
            w,
            h,
            Dominosa.Numbers(numbers),
            IntArray(wh) { it },
            IntArray(wh),
            false,
            false,
        )
        val report = Dominosa.analyse(state)
        // The defined result for this hand-built board: most dominoes
        // have no placement, so the grade is inconsistent (0). The mask
        // and the table keep the `ds[16]` write defined. The bit above
        // the 16-entry table is never counted: `ndominoes` reads only
        // the `nsq <= 16` subset bits, so the extra bit changes no
        // examined value.
        assertEquals(0, report.ret)
        assertEquals(0, state.grid[0])
        assertEquals(0, state.edges.sum())
    }

    @Test(timeout = 120_000)
    fun `a validated description with no placement for a domino still solves`() {
        // Five 3s with no two adjacent: `validate_desc` accepts the board,
        // but the domino 3-3 has no placement.  The other numbers fill the
        // remaining fifteen squares five times each.  C aborts in the
        // forcing-chain lookup table with
        // `dominosa.c:1546: deduce_forcing_chain: Assertion 'di == sc->dc'
        // failed.` (SIGABRT, exit 134).  The port gives the missing highest
        // domino the start `pc`, which the lookup reads as an empty list.
        val params = Dominosa.decodeParams("3dt")
        val board = "00222301130313132021"
        assertNull(Dominosa.validateDesc(params, board))

        // The 3s are at squares 5, 9, 11, 13, and 15.
        val cells = board.map { it - '0' }
        assertEquals(5, cells.count { it == 3 })
        for (y in 0 until 4) {
            for (x in 0 until 5) {
                val i = y * 5 + x
                if (cells[i] != 3) continue
                if (x + 1 < 5) assertNotEquals(3, cells[i + 1])
                if (y + 1 < 4) assertNotEquals(3, cells[i + 5])
            }
        }

        val state = Dominosa.newState(params, board)
        val report = Dominosa.analyse(state)
        assertEquals(0, report.ret)
        val move = Dominosa.solve(state)
        assertEquals(
            "S;E0,5;E1,6;E2,7;E4,9;E6,11;E7,12;E8,13;E9,14;E10,15;" +
                "E11,16;E12,17;E13,18;E1,2;E2,3;E3,4;E5,6;E7,8;E8,9;" +
                "E10,11;E12,13;E13,14;E16,17;E18,19;D3,8;D5,10;D14,19;" +
                "D0,1;D6,7;D11,12;D15,16;D17,18",
            move,
        )
    }

    /** The D list from the auxiliary layout text, as `solve_game` builds it. */
    private fun auxMove(aux: String): String {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val state = midend.state as Dominosa.State
        val out = StringBuilder("S")
        for (i in 0 until state.wh) {
            when (aux[i]) {
                'L' -> out.append(";D").append(i).append(',').append(i + 1)
                'T' -> out.append(";D").append(i).append(',').append(i + state.w)
            }
        }
        return out.toString()
    }

    private class RecordingSink : DrawSink {
        val ops = ArrayList<String>()

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
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun thickLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            thickness: Float,
            colour: Int,
        ) {
            ops += "thick_line"
        }

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle"
        }

        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "thick_circle"
        }

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
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update"
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun inertiaFollow(isSolved: Boolean) {
            ops += "inertia_follow"
        }

        override fun blitterNew(width: Int, height: Int): Int = 1

        override fun blitterFree(id: Int) {}

        override fun blitterSave(id: Int, x: Int, y: Int) {}

        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }

    private companion object {
        const val SEED = "12345"
    }
}
