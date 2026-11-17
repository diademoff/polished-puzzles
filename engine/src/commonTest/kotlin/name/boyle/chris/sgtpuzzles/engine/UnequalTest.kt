package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Engine-level tests for unequal. Level: semantic where an oracle
 * exists; this file covers the paths the oracle cannot be asked about,
 * the parameter, description, and move grammar, the C undefined
 * behavior decisions, the partial-cube hint, and the generation
 * diagnostics.
 */
class UnequalTest {

    private fun params(text: String): Unequal.Params =
        Unequal.decodeParams(text) as Unequal.Params

    private fun state(desc: String, paramsText: String = "4de"): Unequal.State =
        Unequal.newState(params(paramsText), desc) as Unequal.State

    private fun grid(
        order: Int,
        flags: kotlin.collections.Map<Int, String> = emptyMap(),
        numbers: kotlin.collections.Map<Int, Int> = emptyMap(),
    ): String {
        val out = StringBuilder()
        for (i in 0 until order * order) {
            out.append(numbers[i] ?: 0)
            out.append(flags[i] ?: "")
            out.append(',')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `the default and the twelve presets are the C ones`() {
        assertEquals(Unequal.Params(4, 1, Unequal.MODE_UNEQUAL), Unequal.defaultParams())
        assertEquals(
            listOf(
                "Unequal: 4x4 Easy" to "4de",
                "Unequal: 5x5 Easy" to "5de",
                "Unequal: 5x5 Tricky" to "5dk",
                "Adjacent: 5x5 Tricky" to "5adk",
                "Unequal: 5x5 Extreme" to "5dx",
                "Unequal: 6x6 Easy" to "6de",
                "Unequal: 6x6 Tricky" to "6dk",
                "Adjacent: 6x6 Tricky" to "6adk",
                "Unequal: 6x6 Extreme" to "6dx",
                "Unequal: 7x7 Tricky" to "7dk",
                "Adjacent: 7x7 Tricky" to "7adk",
                "Unequal: 7x7 Extreme" to "7dx",
            ),
            Unequal.presets().map { it.title to it.params },
        )
    }

    @Test
    fun `decode keeps the C quirks and encode has a defined out-of-range form`() {
        // The normal forms.
        assertEquals(Unequal.Params(4, 1, 0), Unequal.decodeParams("4de"))
        assertEquals(Unequal.Params(4, 2, 1), Unequal.decodeParams("4adk"))
        assertEquals(Unequal.Params(5, 3, 0), Unequal.decodeParams("5dx"))

        // The digit skip starts at the first character.
        assertEquals(Unequal.Params(4, 1, 0), Unequal.decodeParams("4"))
        assertEquals(Unequal.Params(4, 1, 1), Unequal.decodeParams("4a"))
        assertEquals(Unequal.Params(4, 1, 0), Unequal.decodeParams("+4de"))
        assertEquals(Unequal.Params(4, 1, 0), Unequal.decodeParams(" 4de"))
        assertEquals(Unequal.Params(-4, 1, 0), Unequal.decodeParams("-4de"))
        assertEquals(Unequal.Params(4, 1, 0), Unequal.decodeParams("04de"))
        assertEquals(Unequal.Params(4, 1, 0), Unequal.decodeParams("4q"))
        assertEquals(Unequal.Params(4, 3, 0), Unequal.decodeParams("4dxx"))

        // A `d` with no recognised character is an invalid difficulty.
        assertEquals(Unequal.Params(4, 6, 0), Unequal.decodeParams("4d"))
        assertEquals(Unequal.Params(4, 6, 0), Unequal.decodeParams("4dq"))
        assertEquals(Unequal.Params(4, 6, 0), Unequal.decodeParams("4d5"))

        // The short form drops the difficulty but keeps the mode.
        assertEquals("4", Unequal.encodeParams(params("4dk"), false))
        assertEquals("5a", Unequal.encodeParams(params("5adk"), false))
        assertEquals("4dk", Unequal.encodeParams(params("4dk"), true))
        assertEquals("5adk", Unequal.encodeParams(params("5adk"), true))

        // C indexes `unequal_diffchars[diff]` without a bound, which is
        // undefined behavior. The port writes a plain `d`.
        assertEquals("4d", Unequal.encodeParams(Unequal.Params(4, 5, 0), true))
        assertEquals("4d", Unequal.encodeParams(Unequal.Params(4, 6, 0), true))
        assertEquals("4d", Unequal.encodeParams(Unequal.Params(4, -1, 0), true))
        assertEquals("4ad", Unequal.encodeParams(Unequal.Params(4, Int.MAX_VALUE, 1), true))

        // `atoi` overflow is undefined in C; the port saturates.
        assertEquals(Int.MAX_VALUE, params("99999999999999999999de").order)
        assertEquals(Int.MIN_VALUE, params("-99999999999999999999de").order)
    }

    @Test
    fun `validation order and the three exact messages`() {
        assertEquals(Unequal.validateParams(params("2de"), true), "Order must be between 3 and 32")
        assertEquals(Unequal.validateParams(params("33de"), true), "Order must be between 3 and 32")
        // The size error wins over the difficulty error.
        assertEquals(Unequal.validateParams(params("2d"), true), "Order must be between 3 and 32")
        assertEquals(Unequal.validateParams(params("33adk"), true), "Order must be between 3 and 32")
        assertEquals(Unequal.validateParams(params("4d"), true), "Unknown difficulty rating")
        assertEquals(Unequal.validateParams(params("4dq"), true), "Unknown difficulty rating")
        assertEquals(Unequal.validateParams(params("4adk"), true), "Order must be at least 5 for Adjacent puzzles of this difficulty.")
        assertEquals(Unequal.validateParams(params("4adx"), true), "Order must be at least 5 for Adjacent puzzles of this difficulty.")
        // The third message is the only one with a trailing period.
        assertTrue(
            Unequal.validateParams(params("4adk"), true)!!.endsWith('.'),
        )
        assertNull(Unequal.validateParams(params("4adt"), true))
        assertNull(Unequal.validateParams(params("4ae"), true))
        assertNull(Unequal.validateParams(params("4a"), true))
        assertNull(Unequal.validateParams(params("5adk"), true))
        assertNull(Unequal.validateParams(params("32adk"), true))
    }

    // ------------------------------------------------------------------
    // Description.
    // ------------------------------------------------------------------

    @Test
    fun `the parser builds the row-major arrays and the cube hints`() {
        val desc = grid(
            4,
            flags = mapOf(0 to "R", 5 to "D", 7 to "LU"),
            numbers = mapOf(2 to 3, 10 to 1),
        )
        val s = state(desc)
        assertEquals(3, s.nums[2])
        assertEquals(1, s.nums[10])
        assertEquals(Unequal.F_IMMUTABLE, s.flags[2] and Unequal.F_IMMUTABLE)
        assertEquals(Unequal.F_IMMUTABLE, s.flags[10] and Unequal.F_IMMUTABLE)
        assertEquals(Unequal.F_ADJ_RIGHT, s.flags[0] and Unequal.F_ADJ_RIGHT)
        assertEquals(Unequal.F_ADJ_DOWN, s.flags[5] and Unequal.F_ADJ_DOWN)
        assertEquals(Unequal.F_ADJ_LEFT or Unequal.F_ADJ_UP, s.flags[7] and (Unequal.F_ADJ_LEFT or Unequal.F_ADJ_UP))
        assertTrue(s.hints.all { !it })

        // The run-length form: a letter advances the cell index by
        // `c - 'a' + 1`.
        val run = state("a1," + "0,".repeat(14))
        assertEquals(1, run.nums[1])
        assertEquals(Unequal.F_IMMUTABLE, run.flags[1])
        assertEquals(0, run.nums[0])

        // A `P` move uses the Latin cube layout `(x*order + y)*order`.
        val p = Unequal.executeMove(run, "P1,2,3") as Unequal.State
        assertTrue(p.hint(1, 2, 2))
        assertFalse(p.hint(2, 1, 2), "the transposed cell is untouched")
        assertEquals(p.hints.indexOf(true), (1 * 4 + 2) * 4 + 2)
    }

    @Test
    fun `validateDesc returns the C messages in the C order`() {
        fun error(desc: String, paramsText: String = "4de"): String? =
            Unequal.validateDesc(params(paramsText), desc)

        assertEquals("Too much data to fit in grid", error(grid(4) + "0,"))
        assertEquals("Too much data to fit in grid", error(grid(4) + "aa0,"))
        assertEquals(error("R," + grid(4).substringAfter(',')), "Expecting number in game description")
        assertEquals(error("5," + grid(4).substringAfter(',')), "Number out of range in game description")
        assertEquals("Missing separator", error("0x" + grid(4).substringAfter(',')))
        assertEquals("Not enough data to fill grid", error("0,".repeat(15)))
        assertEquals("Not enough data to fill grid", error(""))
        assertEquals("Flags go off grid", error(grid(4, flags = mapOf(3 to "R"))))
        assertEquals("Flags go off grid", error(grid(4, flags = mapOf(12 to "D"))))
        assertEquals(error(grid(4, flags = mapOf(0 to "R", 1 to "L"))), "Flags contradicting each other")
        assertEquals(error(grid(5, flags = mapOf(0 to "R")), "5adk"), "Flags contradicting each other")
        assertNull(error(grid(5, flags = mapOf(0 to "R", 1 to "L")), "5adk"))
        assertNull(error(grid(4, flags = mapOf(0 to "R"))))
        // A number that contradicts a clue or the Latin rule loads.
        assertNull(error("1," + "1,".repeat(15)))

        // `new_state` throws rather than aborting like C.
        try {
            Unequal.newState(params("4de"), "0,")
            fail("a short description must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("Not enough data to fill grid", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar follows the C sscanf quirks`() {
        val s = state(grid(4))
        fun move(text: String): Unequal.State? = Unequal.executeMove(s, text) as Unequal.State?

        assertEquals(3, move("R1,2,3")!!.nums[2 * 4 + 1])
        assertEquals(3, move("R 1,2,3")!!.nums[2 * 4 + 1])
        assertEquals(3, move("R+1,2,3")!!.nums[2 * 4 + 1])
        assertEquals(3, move("R1,2,3junk")!!.nums[2 * 4 + 1])
        assertEquals(3, move("R1,2,3,4")!!.nums[2 * 4 + 1])
        assertNull(move("R1,2"))
        assertNull(move("R1,2,"))
        assertNull(move(""))
        assertNull(move("X"))
        assertNull(move("R4,0,1"))
        assertNull(move("R0,0,5"))
        assertNull(move("R-1,0,1"))
        // An overflowing field saturates and is then out of range.
        assertNull(move("R99999999999999999999,0,1"))
        assertNull(move("R0,0,99999999999999999999"))

        // `P0,0,0` takes the `R` branch and clears the cell and hints.
        val filled = move("R0,0,3")!!
        val hinted = Unequal.executeMove(filled, "P0,0,2") as Unequal.State
        assertTrue(hinted.hint(0, 0, 1))
        val cleared = Unequal.executeMove(hinted, "P0,0,0") as Unequal.State
        assertEquals(0, cleared.nums[0])
        assertTrue(cleared.hints.all { !it })

        // `P` on a filled cell is accepted by `execute_move`.
        val onFilled = Unequal.executeMove(filled, "P0,0,2") as Unequal.State
        assertEquals(3, onFilled.nums[0])
        assertTrue(onFilled.hint(0, 0, 1))

        // `R` on an immutable cell is accepted; only `interpret_move`
        // guards it.
        val fixed = state(grid(4, numbers = mapOf(10 to 3)))
        val overwritten = Unequal.executeMove(fixed, "R2,2,1") as Unequal.State
        assertEquals(1, overwritten.nums[10])
        assertEquals(Unequal.F_IMMUTABLE, overwritten.flags[10] and Unequal.F_IMMUTABLE)

        // `F` quirks.
        val f = move("F0,0,1024")!!
        assertEquals(Unequal.F_SPENT_UP, f.flags[0])
        val f2 = Unequal.executeMove(f, "F0,0,1024") as Unequal.State
        assertEquals(0, f2.flags[0])
        assertEquals(0, move("F0,0,0")!!.flags[0])
        val multi = move("F0,0,15360")!!
        assertEquals(Unequal.F_SPENT_MASK, multi.flags[0])
        assertNull(move("F0,0,16384"))
        assertNull(move("F0,0,-1"))
        // A spent bit without the clue bit is accepted and invisible.
        assertEquals(Unequal.F_SPENT_RIGHT, move("F0,0,2048")!!.flags[0])

        // `M` and `H` ignore their trailing text.
        assertTrue((move("Mxyz") as Unequal.State).hints.all { it })
        assertTrue((move("Hxyz") as Unequal.State).hints.any { it })

        // `S` ignores immutability and checks its length and digits.
        val s1 = Unequal.executeMove(fixed, "S" + "1".repeat(16)) as Unequal.State
        assertTrue(s1.cheated)
        assertEquals(1, s1.nums[10])
        assertNull(Unequal.executeMove(s, "S" + "1".repeat(15)))
        assertNull(Unequal.executeMove(s, "S" + "1".repeat(17)))
        assertNull(Unequal.executeMove(s, "S" + "0" + "1".repeat(15)))
        assertNull(Unequal.executeMove(s, "S" + "5" + "1".repeat(15)))
    }

    @Test
    fun `M and H never complete and H keeps the partial cube`() {
        // M fills every hint, including the filled cells, and does not
        // check completion.
        val partial = state(grid(4, numbers = mapOf(0 to 2)))
        val marked = Unequal.executeMove(partial, "M") as Unequal.State
        assertTrue(marked.hints.all { it })
        assertFalse(marked.completed)

        // H on a duplicate board copies the partial cube of
        // `latin_solver_alloc` into the hints.
        val duplicate = state(grid(4, numbers = mapOf(0 to 1, 4 to 1)))
        val expected = LatinSolver.allocate(duplicate.nums, 4)
        assertFalse(expected.valid, "the allocation fails")
        val hinted = Unequal.executeMove(duplicate, "H") as Unequal.State
        assertEquals(expected.cube.toList(), hinted.hints.toList())
        assertFalse(hinted.completed)
        assertEquals(0, Unequal.status(hinted))

        // H on an impossible board takes the same path and does not
        // abort.
        val impossible = state("1," + "1,".repeat(15))
        val impossibleHint = Unequal.executeMove(impossible, "H") as Unequal.State
        assertEquals(
            LatinSolver.allocate(impossible.nums, 4).cube.toList(),
            impossibleHint.hints.toList(),
        )
        assertFalse(impossibleHint.completed)

        // A one-cell-short board with H stays incomplete even when the
        // solver can fill the cell.
        val almost = state(
            grid(3, numbers = mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 2, 4 to 3)),
            "3de",
        )
        val almostHint = Unequal.executeMove(almost, "H") as Unequal.State
        assertFalse(almostHint.completed)
        assertEquals(0, Unequal.status(almostHint))
    }

    @Test
    fun `errors, completion, and the post-win freeze`() {
        // Two equal digits mark both cells F_ERROR.
        val s = state(grid(4))
        val a = Unequal.executeMove(s, "R0,0,1") as Unequal.State
        val b = Unequal.executeMove(a, "R1,0,1") as Unequal.State
        assertEquals(Unequal.F_ERROR, b.flags[0] and Unequal.F_ERROR)
        assertEquals(Unequal.F_ERROR, b.flags[1] and Unequal.F_ERROR)

        // A violated greater-than clue marks only the greater cell's
        // direction bit.
        val clue = state(grid(3, flags = mapOf(0 to "D")), "3de")
        val c1 = Unequal.executeMove(clue, "R0,0,1") as Unequal.State
        val c2 = Unequal.executeMove(c1, "R0,1,2") as Unequal.State
        assertEquals(Unequal.F_ERROR_DOWN, c2.flags[0] and Unequal.F_ERROR_DOWN)
        assertEquals(0, c2.flags[3] and Unequal.F_ERROR_MASK)

        // An unexpected adjacent pair in Adjacent mode marks both ends.
        val adj = state(grid(5), "5adk")
        val a1 = Unequal.executeMove(adj, "R0,0,1") as Unequal.State
        val a2 = Unequal.executeMove(a1, "R1,0,2") as Unequal.State
        assertEquals(Unequal.F_ERROR_RIGHT, a2.flags[0] and Unequal.F_ERROR_RIGHT)
        assertEquals(Unequal.F_ERROR_LEFT, a2.flags[1] and Unequal.F_ERROR_LEFT)

        // A complete 3x3 from the instrumentation recipe.
        val desc = "0D,0,0,0,0,0,0R,0,0U,"
        val moves = listOf(
            3, 2, 1,
            1, 3, 2,
            2, 1, 3,
        )
        var solved: Unequal.State = state(desc, "3de")
        for (i in moves.indices) {
            solved = Unequal.executeMove(
                solved,
                "R${i % 3},${i / 3},${moves[i]}",
            ) as Unequal.State
        }
        assertTrue(solved.completed)
        assertEquals(1, Unequal.status(solved))

        // After a win a breaking move keeps `completed` and does not
        // recompute the error marks.
        val broken = Unequal.executeMove(solved, "R0,0,1") as Unequal.State
        assertTrue(broken.completed)
        assertEquals(1, Unequal.status(broken))
        assertEquals(0, broken.flags[0] and Unequal.F_ERROR)

        // The win event needs a non-cheated transition from a
        // non-completed old state.
        val ui = Unequal.newUi(solved)
        assertTrue(Unequal.changedState(ui, state(desc, "3de"), solved))
        assertFalse(Unequal.changedState(ui, solved, solved))
        assertFalse(Unequal.changedState(ui, null, solved))
        val cheated = (solved.copy() as Unequal.State).also { it.cheated = true }
        assertFalse(Unequal.changedState(ui, state(desc, "3de"), cheated))
    }

    @Test
    fun `changedState cancels only a mouse pencil highlight`() {
        val filled = state(grid(3, numbers = mapOf(0 to 1)), "3de")
        val ui = Unequal.newUi(filled) as Unequal.Ui
        ui.hx = 0
        ui.hy = 0
        ui.hshow = true
        ui.hpencil = true
        ui.hcursor = false
        assertEquals(false, Unequal.changedState(ui, null, filled))
        assertFalse(ui.hshow)
        assertFalse(ui.hpencil)

        ui.hshow = true
        ui.hpencil = true
        ui.hcursor = true
        Unequal.changedState(ui, null, filled)
        assertTrue(ui.hshow, "a keyboard highlight stays")
        assertTrue(ui.hpencil)

        // An empty cell does not cancel anything.
        val empty = state(grid(3), "3de")
        ui.hx = 0
        ui.hy = 0
        ui.hshow = true
        ui.hpencil = true
        ui.hcursor = false
        Unequal.changedState(ui, null, empty)
        assertTrue(ui.hshow)
        assertTrue(ui.hpencil)
    }

    // ------------------------------------------------------------------
    // Frontend state and keys.
    // ------------------------------------------------------------------

    @Test
    fun `requestKeys is off by one at order 10 and h and m shadow digits`() {
        val ten = state(grid(10), "10de")
        val keys = Unequal.requestKeys(params("10de"))
        assertEquals(13, keys.labels.size)
        assertEquals('0'.code, keys.labels[0].button)
        assertEquals("0", keys.labels[0].name)
        assertEquals('9'.code, keys.labels[9].button)
        assertEquals("9", keys.labels[9].name)
        assertEquals('\b'.code, keys.labels[10].button)
        assertEquals("Clear", keys.labels[10].name)
        assertEquals("Mark", keys.labels[11].name)
        assertEquals("Mark/Hints", keys.labels[12].name)
        assertEquals(ArrowMode.ARROWS_LEFT, keys.arrowMode)

        val ui = Unequal.newUi(ten) as Unequal.Ui
        ui.hshow = true
        assertEquals((Unequal.interpretMove(ten, ui, 0, 0, '1'.code, 48) as MoveResult.Move).text, "R0,0,2")
        assertEquals((Unequal.interpretMove(ten, ui, 0, 0, '0'.code, 48) as MoveResult.Move).text, "R0,0,1")

        // At order 18 `h` is digit 18; at 17 it is the hint key.
        val eighteen = state(grid(18), "18de")
        val ui18 = Unequal.newUi(eighteen) as Unequal.Ui
        ui18.hshow = true
        assertEquals((Unequal.interpretMove(eighteen, ui18, 0, 0, 'h'.code, 48) as MoveResult.Move).text, "R0,0,18")
        assertEquals((Unequal.interpretMove(eighteen, ui18, 0, 0, 'H'.code, 48) as MoveResult.Move).text, "H")
        val seventeen = state(grid(17), "17de")
        val ui17 = Unequal.newUi(seventeen) as Unequal.Ui
        ui17.hshow = true
        assertEquals((Unequal.interpretMove(seventeen, ui17, 0, 0, 'h'.code, 48) as MoveResult.Move).text, "H")

        // At order 23 `m` is digit 23; at 22 it is the mark key.
        val twentyThree = state(grid(23), "23de")
        val ui23 = Unequal.newUi(twentyThree) as Unequal.Ui
        ui23.hshow = true
        assertEquals((Unequal.interpretMove(twentyThree, ui23, 0, 0, 'm'.code, 48) as MoveResult.Move).text, "R0,0,23")
        assertEquals((Unequal.interpretMove(twentyThree, ui23, 0, 0, 'M'.code, 48) as MoveResult.Move).text, "M")
        val twentyTwo = state(grid(22), "22de")
        val ui22 = Unequal.newUi(twentyTwo) as Unequal.Ui
        ui22.hshow = true
        assertEquals((Unequal.interpretMove(twentyTwo, ui22, 0, 0, 'm'.code, 48) as MoveResult.Move).text, "M")

        // Space and backspace both clear. A clear on an already empty
        // cell is a no-op, so the cell is filled first through a move,
        // which keeps it mutable.
        val emptyFour = state(grid(4))
        val four = Unequal.executeMove(emptyFour, "R0,0,2") as Unequal.State
        val ui4 = Unequal.newUi(four) as Unequal.Ui
        ui4.hshow = true
        ui4.hcursor = true
        assertEquals((Unequal.interpretMove(four, ui4, 0, 0, ' '.code, 48) as MoveResult.Move).text, "R0,0,0")
        assertEquals((Unequal.interpretMove(four, ui4, 0, 0, '\b'.code, 48) as MoveResult.Move).text, "R0,0,0")
    }

    @Test
    fun `currentKeyLabel and cursorLocation are the C values`() {
        val s = state(grid(4))
        val ui = Unequal.newUi(s) as Unequal.Ui
        assertEquals("", Unequal.currentKeyLabel(ui, s, '3'.code))
        assertEquals("", Unequal.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.hshow = true
        assertEquals("Pencil", Unequal.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("Pencil", Unequal.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))
        ui.hpencil = true
        assertEquals("Ink", Unequal.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("", Unequal.currentKeyLabel(ui, s, Buttons.CURSOR_UP))

        Unequal.setCursorVisible(ui, false)
        assertNull(Unequal.cursorLocation(ui, s, 32))
        Unequal.setCursorVisible(ui, true)
        assertEquals(
            Game.CursorRect(16, 16, 32, 32),
            Unequal.cursorLocation(ui, s, 32),
        )
        ui.hx = 3
        ui.hy = 2
        assertEquals(
            Game.CursorRect(3 * 48 + 16, 2 * 48 + 16, 32, 32),
            Unequal.cursorLocation(ui, s, 32),
        )
    }

    @Test
    fun `the preference defaults to true and is not serialised`() {
        val start = Midend.newGame(Unequal, "5de", "12345")
        assertTrue((start.ui as Unequal.Ui).pencilKeepHighlight)
        val save = start.serialise()

        (start.ui as Unequal.Ui).pencilKeepHighlight = false
        assertEquals(save.serialise(), start.serialise().serialise())
        assertNull(save.value("UI"))
        val loaded = Midend.fromSave(Unequal, save)
        assertTrue((loaded.ui as Unequal.Ui).pencilKeepHighlight, "a load keeps the C default until the adapter applies the stored value")
    }

    // ------------------------------------------------------------------
    // Palette and size.
    // ------------------------------------------------------------------

    @Test
    fun `the palette derives from the background in C order`() {
        val default = Unequal.colours()
        val expected = listOf(
            800, 800, 800,
            500, 500, 500,
            0, 0, 0,
            0, 480, 0,
            1000, 0, 0,
            400, 400, 800,
            967, 967, 967,
            633, 633, 633,
        )
        assertEquals(expected, default.map { (it * 1000.0f).roundToInt() })

        val custom = Unequal.colours(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertEquals(0.2f, custom[0])
        assertEquals(0.4f, custom[1])
        assertEquals(0.6f, custom[2])
        assertEquals(listOf(0.0f, 0.0f, 0.0f), custom.slice(6..8))
        assertEquals(listOf(0.5f, 0.5f, 0.5f), custom.slice(3..5))
        assertEquals(listOf(0.0f, 0.6f * 0.4f, 0.0f), custom.slice(9..11))
        assertEquals(listOf(1.0f, 0.0f, 0.0f), custom.slice(12..14))
        assertEquals(listOf(0.5f * 0.2f, 0.5f * 0.4f, 0.6f), custom.slice(15..17))
        assertNotEquals(default.slice(18..20), custom.slice(18..20))
        assertNotEquals(default.slice(21..23), custom.slice(21..23))
        assertTrue(custom.all { it in 0.0f..1.0f })
    }

    @Test
    fun `computeSize truncates T over 2`() {
        val cases = mapOf(
            4 to mapOf(48 to 312, 63 to 407, 82 to 533, 100 to 650, 37 to 238, 43 to 277),
            5 to mapOf(48 to 384, 63 to 501, 82 to 656, 100 to 800, 51 to 405),
            6 to mapOf(48 to 456, 63 to 595, 82 to 779, 100 to 950, 43 to 405),
            7 to mapOf(48 to 528, 63 to 689, 82 to 902, 100 to 1100, 37 to 403),
        )
        for ((order, table) in cases) {
            for ((tile, size) in table) {
                val computed = Unequal.computeSize(params("${order}de"), tile)
                assertEquals(size, computed.width, "$order/$tile width")
                assertEquals(size, computed.height, "$order/$tile height")
            }
        }
        // 4x4 at T=37: 37*4 + 18*3 + 2*18 = 148 + 54 + 36 = 238,
        // which is not 37*4 + 19*3 + 2*19. The C division is the guard.
        assertEquals(238, Unequal.computeSize(params("4de"), 37).width)
    }

    // ------------------------------------------------------------------
    // Generation diagnostics.
    // ------------------------------------------------------------------

    @Test
    fun `the retry fixtures pin the attempt counts`() {
        val fixtures = listOf(
            "4de" to "105" to 2,
            "5dk" to "21" to 30,
            "5adk" to "47" to 3,
            "5dx" to "27" to 28,
            "6dk" to "5" to 18,
            "6dx" to "3" to 15,
            "7dk" to "31" to 7,
            "7dx" to "1" to 5,
        )
        for ((fixture, attempts) in fixtures) {
            val (paramsText, seed) = fixture
            val generated = Unequal.generateWithStats(
                params(paramsText),
                RandomState.fromSeed(seed),
            )
            assertEquals(attempts, generated.stats.attempts, "$paramsText/$seed attempts")
            assertFalse(generated.stats.usedFallback, "$paramsText/$seed fallback")
        }
    }

    @Test
    fun `the 50-try fallback keeps the description and decrements the level`() {
        val generated = Unequal.generateWithStats(
            params("5dk"),
            RandomState.fromSeed("384"),
        )
        assertEquals(50, generated.stats.attempts)
        assertTrue(generated.stats.usedFallback)
        assertEquals(1, generated.stats.effectiveDiff)
        val s = Unequal.newState(params("5dk"), generated.description.text)
        assertTrue(Unequal.difficulty(s) <= 1, "the accepted board is at most Easy")

        // A reduced `maxTries` exercises the same branch on a board that
        // the normal gate would reject, and the accepted description is
        // the first attempt.
        val forced = Unequal.generateWithStats(
            params("5dk"),
            RandomState.fromSeed("1"),
            maxTries = 1,
        )
        assertEquals(1, forced.stats.attempts)
        assertTrue(forced.stats.usedFallback)
        assertEquals(1, forced.stats.effectiveDiff)
        val forcedState = Unequal.newState(
            params("5dk"),
            forced.description.text,
        )
        assertTrue(Unequal.difficulty(forcedState) <= 1, "the forced board is at most Easy")
        assertTrue(forced.description.text.isNotEmpty(), "the forced description differs")
    }

    // ------------------------------------------------------------------
    // Solver slots and drawstate.
    // ------------------------------------------------------------------

    @Test
    fun `the five solver slots and the drawstate arrays are the C sizes`() {
        // `latin_solver_main(solver, maxdiff, DIFF_LATIN, DIFF_SET,
        // DIFF_EXTREME, DIFF_EXTREME, DIFF_RECURSIVE, ...)`: simple 0,
        // set-0 2, set-1 3, forcing 3, recursive 4.
        assertEquals(listOf(0, 2, 3, 3, 4), Unequal.solverSlots().toList())

        // `blank_drawstate`: `nums` and `flags` hold `order^2` cells and
        // `hints` holds the `order^3` Latin cube. All three start zeroed.
        val s = state(grid(4))
        val ds = Unequal.newDrawState(s, 48, NoOpSink) as Unequal.DrawState
        assertEquals(48, ds.tileSize)
        assertEquals(4, ds.order)
        assertEquals(Unequal.MODE_UNEQUAL, ds.mode)
        assertEquals(16, ds.nums.size)
        assertEquals(16, ds.flags.size)
        assertEquals(64, ds.hints.size)
        assertTrue(ds.nums.all { it == 0 })
        assertTrue(ds.flags.all { it == 0 })
        assertTrue(ds.hints.all { !it })

        // A redraw caches the state arrays, and `hints` keeps the cube
        // layout `(x*order + y)*order + n`, not the row-major one.
        val hinted = Unequal.executeMove(s, "H") as Unequal.State
        assertTrue(hinted.hints.any { it }, "the hint cube has candidates")
        Unequal.redraw(hinted, Unequal.newUi(hinted), ds, NoOpSink, 48)
        assertTrue(ds.hints.any { it }, "the hint cube is cached")
        assertEquals(hinted.nums.toList(), ds.nums.toList())
        assertEquals(hinted.flags.toList(), ds.flags.toList())
        assertEquals(hinted.hints.toList(), ds.hints.toList())
    }

    /** A drawing sink that discards every operation. */
    private object NoOpSink : DrawSink {
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
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
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
