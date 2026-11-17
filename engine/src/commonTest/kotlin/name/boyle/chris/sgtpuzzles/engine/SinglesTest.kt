package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level tests for singles.
 *
 * This file covers the paths the oracle cannot be asked about, the
 * parameter and description grammar, the C undefined-behavior
 * decisions, the solver branches, and the generation counters. The
 * oracle comparison lives in the parity module.
 */
class SinglesTest {

    private fun params(text: String): Singles.Params =
        Singles.decodeParams(text) as Singles.Params

    private fun state(desc: String, paramsText: String = "2x2de"): Singles.State =
        Singles.newState(params(paramsText), desc) as Singles.State

    private fun midend(paramsText: String, seed: String = "12345"): Midend =
        Midend.newGame(Singles, paramsText, seed)

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `the default and the ten presets are the C ones`() {
        assertEquals(Singles.Params(5, 5, 0), Singles.defaultParams())
        assertEquals(
            listOf(
                "5x5 Easy" to "5x5de",
                "5x5 Tricky" to "5x5dk",
                "6x6 Easy" to "6x6de",
                "6x6 Tricky" to "6x6dk",
                "8x8 Easy" to "8x8de",
                "8x8 Tricky" to "8x8dk",
                "10x10 Easy" to "10x10de",
                "10x10 Tricky" to "10x10dk",
                "12x12 Easy" to "12x12de",
                "12x12 Tricky" to "12x12dk",
            ),
            Singles.presets().map { it.title to it.params },
        )
        assertEquals("Singles", Singles.name)
        assertEquals("singles", Singles.backendName)
        assertEquals(32, Singles.preferredTileSize)
        assertFalse(Singles.wantsStatusBar)
        assertTrue(Singles.canSolve)
        assertTrue(Singles.canFormatAsText)
        assertEquals(Game.REQUIRE_RBUTTON, Singles.flags)
    }

    @Test
    fun `decode keeps the C quirks and encode has a defined out-of-range form`() {
        assertEquals(Singles.Params(5, 5, 0), params("5"))
        assertEquals(Singles.Params(5, 5, 0), params("5x5de"))
        assertEquals(Singles.Params(5, 5, 1), params("5x5dk"))
        assertEquals(Singles.Params(5, 5, 0), params("5x5"))
        assertEquals(Singles.Params(5, 5, 1), params("5dk"))
        assertEquals(Singles.Params(5, 5, 1), params("5x5dkk"))
        assertEquals(Singles.Params(5, 5, 0), params("5x5dejunk"))
        assertEquals(Singles.Params(5, 5, 0), params("5x5k"))
        assertEquals(Singles.Params(5, 5, 0), params("5x5e"))
        assertEquals(Singles.Params(5, 5, 1), params("05x5dk"))
        assertEquals(Singles.Params(5, 5, 1), params("0005x5dk"))
        assertEquals(Singles.Params(5, 0, 0), params("5x"))
        assertEquals(Singles.Params(5, 5, 2), params("5x5d"))
        assertEquals(Singles.Params(5, 5, 2), params("5x5dK"))
        assertEquals(Singles.Params(5, 5, 2), params("5d"))
        // A leading sign or space stops the digit skip, so neither the
        // height nor the difficulty is read.
        assertEquals(Singles.Params(5, 5, 0), params("+5x5dk"))
        assertEquals(Singles.Params(5, 5, 0), params(" 5x5dk"))
        assertEquals(Singles.Params(0, 0, 0), params(""))

        assertEquals("5x5dk", Singles.encodeParams(params("5x5dk"), true))
        assertEquals("5x5", Singles.encodeParams(params("5x5dk"), false))
        assertEquals("5x5d", Singles.encodeParams(params("5x5d"), true))
        assertEquals(
            "5x5",
            Singles.encodeParams(Singles.Params(5, 5, 0), false),
        )
        assertEquals(
            "5x5d",
            Singles.encodeParams(Singles.Params(5, 5, 7), true),
        )
    }

    @Test
    fun `validation keeps the C order and the three exact messages`() {
        assertEquals(
            "Width and height must be at least two",
            Singles.validateParams(Singles.Params(1, 99, 0), true),
        )
        assertEquals(
            "Width and height must be at least two",
            Singles.validateParams(Singles.Params(99, 1, 0), true),
        )
        assertEquals(
            "Puzzle is too large",
            Singles.validateParams(Singles.Params(63, 63, 0), true),
        )
        assertEquals(
            "Puzzle is too large",
            Singles.validateParams(Singles.Params(2, 63, 0), true),
        )
        assertNull(Singles.validateParams(Singles.Params(2, 2, 1), true))
        assertNull(Singles.validateParams(Singles.Params(2, 62, 1), true))
        assertNull(Singles.validateParams(Singles.Params(62, 2, 1), true))
        assertNull(Singles.validateParams(Singles.Params(62, 62, 0), true))
        assertEquals(
            "Unknown difficulty rating",
            Singles.validateParams(Singles.Params(5, 5, 2), true),
        )
        assertEquals(
            "Unknown difficulty rating",
            Singles.validateParams(Singles.Params(5, 5, -1), true),
        )
        assertNull(
            Singles.validateParams(Singles.Params(5, 5, 2), false),
            "the short form does not validate the difficulty",
        )
    }

    // ------------------------------------------------------------------
    // Description.
    // ------------------------------------------------------------------

    @Test
    fun `n2c and c2n are the C alphabet and the 62 hole`() {
        assertEquals('0', Singles.n2c(0))
        assertEquals('9', Singles.n2c(9))
        assertEquals('a', Singles.n2c(10))
        assertEquals('z', Singles.n2c(35))
        assertEquals('A', Singles.n2c(36))
        assertEquals('Z', Singles.n2c(61))
        assertEquals('[', Singles.n2c(62))

        assertEquals(0, Singles.c2n('0'))
        assertEquals(9, Singles.c2n('9'))
        assertEquals(10, Singles.c2n('a'))
        assertEquals(35, Singles.c2n('z'))
        assertEquals(36, Singles.c2n('A'))
        assertEquals(61, Singles.c2n('Z'))
        assertEquals(-1, Singles.c2n('['))
        assertEquals(-1, Singles.c2n(' '))
        assertEquals(-1, Singles.c2n('@'))
    }

    @Test
    fun `validateDesc returns the C messages in the C order`() {
        assertEquals(
            "Game description is wrong length",
            Singles.validateDesc(params("5x5de"), "1".repeat(24)),
        )
        assertEquals(
            "Game description is wrong length",
            Singles.validateDesc(params("5x5de"), "1".repeat(26)),
        )
        assertEquals(
            "Invalid character in game description",
            Singles.validateDesc(params("5x5de"), "0" + "1".repeat(24)),
        )
        assertEquals(
            "Invalid character in game description",
            Singles.validateDesc(params("5x5de"), "6" + "1".repeat(24)),
        )
        assertEquals(
            "Invalid character in game description",
            Singles.validateDesc(params("5x5de"), "A" + "1".repeat(24)),
        )
        assertEquals(
            "Invalid character in game description",
            Singles.validateDesc(params("5x5de"), "[" + "1".repeat(24)),
        )
        assertNull(
            Singles.validateDesc(params("2x5de"), "5" + "1".repeat(9)),
            "a rectangle accepts up to max(w,h)",
        )
        assertEquals(
            "Invalid character in game description",
            Singles.validateDesc(params("2x5de"), "6" + "1".repeat(9)),
        )
        assertNull(
            Singles.validateDesc(params("62x2de"), "Z" + "1".repeat(123)),
            "62x2 accepts 61",
        )
        assertEquals(
            "Invalid character in game description",
            Singles.validateDesc(params("62x2de"), "[" + "1".repeat(123)),
        )
    }

    @Test
    fun `loadDesc builds the numbers and no flags`() {
        val s = state("123231312", "3x3de")
        assertEquals(3, s.w)
        assertEquals(3, s.h)
        assertEquals(9, s.n)
        assertEquals(3, s.o)
        assertEquals(listOf(1, 2, 3, 2, 3, 1, 3, 1, 2), s.nums.toList())
        assertTrue(s.flags.all { it == 0 })
        assertFalse(s.completed)
        assertFalse(s.usedSolve)
        assertFalse(s.impossible)
        assertNull(Singles.publicDesc(s))
        assertNull(Singles.privateDesc(s))
    }

    // ------------------------------------------------------------------
    // State, moves, and text.
    // ------------------------------------------------------------------

    @Test
    fun `the text form is the C text`() {
        assertEquals("2 1\n   \n1 1\n   \n", Singles.textFormat(state("2111")))
        assertEquals(
            "1 1\n~ ~\n2 1\n   \n",
            Singles.textFormat(
                Singles.executeMove(state("1121"), "C0,0;C1,0")!!,
            ),
        )
        assertEquals(
            "* 1\n   \n2 1\n   \n",
            Singles.textFormat(Singles.executeMove(state("1121"), "B0,0")!!),
        )
    }

    @Test
    fun `executeMove follows the C grammar and the empty move`() {
        val base = state("2111")
        val accepted = listOf(
            "B0,0", "B0,0;", "B0,0;C1,1", "", "S", "S;B0,0",
            "B0,0;S", "B+0,0", "B 0,0", "B0, 0", "E1,1",
            "C1,1;E0,0;B0,1",
        )
        for (move in accepted) {
            val next = Singles.executeMove(base, move)
            assertNotNull(next, "accepted '$move'")
            assertTrue((next as Singles.State).nums.contentEquals(base.nums))
            assertTrue(next.flags.all { it and Singles.F_SCRATCH == 0 })
        }
        val rejected = listOf(
            "B0,0;;", "X0,0", "B9,9", "B-1,0", "B 0 , 0", "B0,0  ",
            "B0", "B0,", "B0,0x", "B0,0;C1", "B99999999999999999999,0",
            ";", "B0,0;X", "BB0,0",
        )
        for (move in rejected) {
            assertNull(Singles.executeMove(base, move), "rejected '$move'")
        }

        // The do-nothing move still returns a new state and the S token
        // sets used_solve.
        val empty = Singles.executeMove(base, "") as Singles.State
        assertTrue(base !== empty)
        assertTrue(base.flags !== empty.flags)
        assertTrue(base.nums !== empty.nums)
        val s = Singles.executeMove(base, "S") as Singles.State
        assertTrue(s.usedSolve)

        // The copy is deep: a later write to the source cannot reach
        // the returned state.
        val copied = base.copy() as Singles.State
        base.flags[0] = Singles.F_BLACK
        base.nums[0] = 9
        assertEquals(0, copied.flags[0])
        assertEquals(2, copied.nums[0])
        assertNotEquals(base, copied)
    }

    @Test
    fun `completion, sticky completion, and the three error rules`() {
        // A complete board needs the black cell at 1,1.
        var s = state("2111")
        s = Singles.executeMove(s, "C0,0;C0,1;C1,0") as Singles.State
        assertFalse(s.completed)
        s = Singles.executeMove(s, "B1,1") as Singles.State
        assertTrue(s.completed)
        assertEquals(1, Singles.status(s))
        assertTrue(Singles.checkComplete(s, Singles.CC_MARK_ERRORS))

        // The bit is sticky even when a later move makes the board
        // invalid.
        val sticky = Singles.executeMove(s, "E1,1") as Singles.State
        assertTrue(sticky.completed)
        assertEquals(1, Singles.status(sticky))
        assertFalse(Singles.checkComplete(sticky, Singles.CC_MARK_ERRORS))

        // Two circled equal numbers are both marked.
        val pair = Singles.executeMove(state("1121"), "C0,0;C1,0") as Singles.State
        assertTrue(pair.flags[0] and Singles.F_ERROR != 0)
        assertTrue(pair.flags[1] and Singles.F_ERROR != 0)
        assertFalse(Singles.checkComplete(pair, Singles.CC_MARK_ERRORS))

        // A black group is marked.
        val group = Singles.executeMove(state("1121"), "B0,0;B1,0") as Singles.State
        assertTrue(group.flags[0] and Singles.F_ERROR != 0)
        assertTrue(group.flags[1] and Singles.F_ERROR != 0)

        // A black row splits the white cells; the non-canonical region
        // is marked.
        val split = Singles.executeMove(state("123231312", "3x3de"), "B0,1;B1,1;B2,1")!!
            as Singles.State
        assertTrue(split.flags[3] and Singles.F_ERROR != 0)
        assertTrue(split.flags[6] and Singles.F_ERROR != 0)
        assertFalse(Singles.checkComplete(split, Singles.CC_MARK_ERRORS))

        // CC_MARK_ERRORS clears a stale bit before it recomputes.
        val stale = state("2111")
        stale.flags[2] = Singles.F_ERROR
        Singles.checkComplete(stale, Singles.CC_MARK_ERRORS)
        assertEquals(0, stale.flags[2] and Singles.F_ERROR)

        // CC_MUST_FILL rejects a cell that is neither black nor circled.
        assertFalse(Singles.checkComplete(state("2111"), Singles.CC_MUST_FILL))
        val filled = state("2111")
        filled.flags.fill(Singles.F_CIRCLE)
        filled.flags[3] = Singles.F_BLACK
        assertTrue(Singles.checkComplete(filled, Singles.CC_MUST_FILL))
    }

    @Test
    fun `a fresh Latin description is only completed by a move`() {
        val s = state("1221")
        assertFalse(s.completed)
        assertEquals(0, Singles.status(s))
        val moved = Singles.executeMove(s, "") as Singles.State
        assertTrue(moved.completed)
        assertEquals(1, Singles.status(moved))
    }

    // ------------------------------------------------------------------
    // Solver and the C undefined behavior.
    // ------------------------------------------------------------------

    @Test
    fun `the solve diff is column-major and prefixes S`() {
        assertEquals(
            "S;C0,0;C0,1;C1,0;B1,1;",
            Singles.solve(state("2111")),
        )
        assertEquals(
            "S;C0,0;B0,1;C0,2;C1,0;C1,1;C1,2;C2,0;B2,1;C2,2;",
            Singles.solve(state("231222312", "3x3de")),
        )
        assertNull(Singles.solve(state("1111")))
        // The aux argument is ignored, as C ignores it.
        assertEquals(
            Singles.solve(state("2111")),
            Singles.solve(state("2111"), "not an answer"),
        )
        // The current state is tried first, then the initial state.
        val wrong = Singles.executeMove(state("2111"), "C1,1") as Singles.State
        assertEquals("S;C0,0;C0,1;C1,0;B1,1;", Singles.solve(wrong))
        // A solve uses DIFF_ANY, so the tricky techniques are on.
        val tricky = Singles.solve(state("231222312", "3x3de"))
        assertEquals(
            1,
            Singles.solveSpecific(
                Singles.newState(params("3x3de"), "231222312") as Singles.State,
                3,
                false,
            ),
        )
        assertEquals(tricky, Singles.solve(state("231222312", "3x3de")))
    }

    @Test
    fun `bestBlackCol falls back to the first shuffled number`() {
        // All counts are zero, so both passes fail and the C assertion
        // is replaced by the defined fallback.
        val random = RandomState.fromSeed("1")
        val first = random.upto(2).toInt()
        val expected = if (first == 0) 2 else 1

        val s = state("2111")
        val random2 = RandomState.fromSeed("1")
        val scratch = IntArray(s.n)
        val rownums = IntArray(s.h * s.o)
        val colnums = IntArray(s.w * s.o)
        val value = Singles.bestBlackCol(
            s,
            random2,
            scratch,
            0,
            rownums,
            colnums,
        )
        assertEquals(expected, value)
        assertEquals(1, rownums[value - 1])
        assertEquals(1, colnums[value - 1])

        // The same seed gives the same fallback, and the counts keep
        // only this placement.
        val random3 = RandomState.fromSeed("1")
        val rownums3 = IntArray(s.h * s.o)
        val colnums3 = IntArray(s.w * s.o)
        assertEquals(
            value,
            Singles.bestBlackCol(s, random3, IntArray(s.n), 0, rownums3, colnums3),
        )
    }

    @Test
    fun `the 62 boundary is rejected before the generator runs`() {
        for (paramsText in listOf("62x2de", "2x62de", "62x62de")) {
            val random = RandomState.fromSeed("1")
            val before = random.encodeState()
            val error = assertFailsWith<IllegalArgumentException> {
                Singles.newDesc(params(paramsText), random)
            }
            assertEquals("Puzzle is too large", error.message)
            assertEquals(before, random.encodeState(), "$paramsText touches no draw")
            // The parameter validator still accepts the size, as C does.
            assertNull(Singles.validateParams(params(paramsText), true))
        }
    }

    @Test
    fun `the palette derives the lowlight from the background`() {
        val background = floatArrayOf(0.2f, 0.4f, 0.6f)
        val actual = Singles.colours(background)
        val expected = FloatArray(9 * 3)
        for (i in 0 until 3) expected[i] = background[i]
        Colour.mkHighlight(expected, 0, -1, 2)
        for (i in 0 until 3) expected[3 * 3 + i] = 0.0f
        for (i in 0 until 3) expected[5 * 3 + i] = 0.4f
        for (i in 0 until 3) expected[4 * 3 + i] = 1.0f
        for (i in 0 until 3) expected[6 * 3 + i] = expected[2 * 3 + i]
        for (i in 0 until 3) expected[1 * 3 + i] = 0.0f
        expected[7 * 3] = 0.2f
        expected[7 * 3 + 1] = 0.8f
        expected[7 * 3 + 2] = 0.0f
        expected[8 * 3] = 1.0f
        expected[8 * 3 + 1] = 0.0f
        expected[8 * 3 + 2] = 0.0f
        for (i in actual.indices) {
            assertTrue(
                abs(actual[i] - expected[i]) < 1e-6f,
                "colour $i",
            )
        }
        assertEquals(
            true,
            Singles.colours().contentEquals(
                Singles.colours(floatArrayOf(0.8f, 0.8f, 0.8f)),
            ),
        )
        // The frozen trace palette: the lowlight of 0.8 is 0.633.
        assertTrue(abs(Singles.colours()[2 * 3] - 0.633f) < 0.001f)
    }

    @Test
    fun `computeSize truncates the half tile`() {
        assertEquals(Size(192, 192), Singles.computeSize(params("5x5de"), 32))
        assertEquals(Size(197, 197), Singles.computeSize(params("5x5de"), 33))
        assertEquals(Size(600, 600), Singles.computeSize(params("5x5de"), 100))
        assertEquals(Size(96, 96), Singles.computeSize(params("2x2de"), 32))
        assertEquals(Size(2 * 47 + 2 * 23, 2 * 47 + 2 * 23), Singles.computeSize(params("2x2de"), 47))
    }

    // ------------------------------------------------------------------
    // UI and preferences.
    // ------------------------------------------------------------------

    @Test
    fun `the UI, keys, and the preference are the C values`() {
        val s = state("2111")
        val ui = Singles.newUi(s) as Singles.Ui
        assertEquals(0, ui.cursorX)
        assertEquals(0, ui.cursorY)
        assertFalse(ui.cursorVisible)
        assertFalse(ui.showBlackNums)
        assertEquals("", Singles.statusText(s, ui))
        assertNull(Singles.cursorLocation(ui, s, 32))

        Singles.setCursorVisible(ui, true)
        assertEquals(Game.CursorRect(16, 16, 32, 32), Singles.cursorLocation(ui, s, 32))

        val keys = Singles.requestKeys(params("5x5de"))
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, keys.arrowMode)
        assertTrue(keys.labels.isEmpty())
        assertEquals(keys, Singles.requestKeys(params("5x5de"), true))

        // The preference is not in the save and is read only by
        // drawing and by the outside click.
        val m = midend("5x5de")
        val mUi = m.ui as Singles.Ui
        assertFalse(mUi.showBlackNums)
        mUi.showBlackNums = true
        assertNull(Singles.encodeUi(mUi))
        assertEquals(
            false,
            m.serialise().records.any { it.key == "UI" },
        )
    }

    @Test
    fun `the drawstate allocates the dirty cache and nothing else`() {
        val s = state("1234512345123451234512345", "5x5de")
        val ds = Singles.newDrawState(s, 32, KotlinSink()) as Singles.DrawState
        assertEquals(32, ds.tileSize)
        assertEquals(5, ds.w)
        assertEquals(5, ds.h)
        assertEquals(25, ds.cachedFlags().size)
        assertTrue(ds.cachedFlags().all { it == 0 })
        assertFalse(ds.started)
        Singles.freeDrawState(ds, KotlinSink())
    }

    // ------------------------------------------------------------------
    // Generation diagnostics.
    // ------------------------------------------------------------------

    @Test
    fun `the retry counters prove both loops`() {
        // The preset seed reaches `goto generate` four times and
        // `goto randomise` many times.
        val preset = Singles.generateWithStats(
            params("5x5dk"),
            RandomState.fromSeed("12345"),
        )
        assertEquals(5, preset.stats.attempts)
        assertEquals(4, preset.stats.restarts)
        assertEquals(97, preset.stats.randomises)
        assertEquals(96, preset.stats.gateFailures)
        assertTrue(preset.stats.restarts > 0, "the generate loop ran")
        assertTrue(
            preset.stats.gateFailures > preset.stats.restarts,
            "the randomise loop ran",
        )
        assertEquals(25, preset.description.text.length)
        assertNull(Singles.validateDesc(params("5x5dk"), preset.description.text))

        // A thin board is retry-heavy. The counts are deterministic and
        // pin the 21-failure restart.
        val thin = Singles.generateWithStats(
            params("2x20dk"),
            RandomState.fromSeed("1"),
        )
        assertEquals(93, thin.stats.attempts)
        assertEquals(92, thin.stats.restarts)
        assertEquals(1934, thin.stats.randomises)
        assertEquals(1933, thin.stats.gateFailures)
        assertEquals(40, thin.description.text.length)
    }

    @Test
    fun `the tiny-board downgrade changes the generated board only`() {
        // 2x2, 3x3, and 2x3 Tricky are generated at Easy, so they are
        // byte-identical to the Easy game with the same seed. CPARAMS
        // keeps the request, which the parity save test pins.
        for ((tricky, easy) in listOf(
            "2x2dk" to "2x2de",
            "3x3dk" to "3x3de",
            "2x3dk" to "2x3de",
            "3x2dk" to "3x2de",
        )) {
            val a = Singles.generateWithStats(params(tricky), RandomState.fromSeed("1"))
            val b = Singles.generateWithStats(params(easy), RandomState.fromSeed("1"))
            assertEquals(b.description.text, a.description.text, "$tricky board")
            assertNull(Singles.validateDesc(params(tricky), a.description.text))
        }
        // A 5x5 board is not downgraded, so the two levels give
        // different boards for the same seed.
        val fiveTricky = Singles.generateWithStats(
            params("5x5dk"),
            RandomState.fromSeed("12345"),
        )
        val fiveEasy = Singles.generateWithStats(
            params("5x5de"),
            RandomState.fromSeed("12345"),
        )
        assertEquals(25, fiveTricky.description.text.length)
        assertEquals(25, fiveEasy.description.text.length)
        assertNotEquals(fiveEasy.description.text, fiveTricky.description.text)
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private class KotlinSink : DrawSink {
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
        ) {
        }

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
        }

        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
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
        }

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
