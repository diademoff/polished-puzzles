package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: strict. Game: solo, engine side.
 *
 * The parameter grammar, the validation order, the description parser,
 * the text form, the move grammar, the completion check, the solver,
 * the key labels, the cursor rectangles, and the hand-made parameter
 * guards. The oracle compares the descriptions, the moves, the saves,
 * the frames, and the pointer input; these tests pin the internal
 * rules.
 */
class SoloTest {

    private val presets = Solo.presets().map { it.params }

    @Test
    fun `the preset table and defaults are the C game`() {
        assertEquals(
            listOf(
                "2x2", "2x3db", "3x3", "3x3db", "3x3xdb", "3x3di", "3x3da",
                "3x3xda", "3x3de", "3x3du", "3x3ka", "9jdb", "9jxdb", "9jda",
                "3x4db", "4x4db",
            ),
            presets,
        )
        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.defaultParams())
        assertEquals("3x3", Solo.encodeParams(Solo.defaultParams(), true))
        assertEquals("3x3", Solo.encodeParams(Solo.defaultParams(), false))
        assertEquals("2x2 Trivial", Solo.presets().first().title)
        assertEquals("4x4 Basic", Solo.presets().last().title)
    }

    @Test
    fun `the parameter grammar matches the C quirks`() {
        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.decodeParams("3x3"))
        assertEquals(Solo.Params(3, 3, 1, 1, false, false), Solo.decodeParams("3x3db"))
        assertEquals(Solo.Params(3, 3, 1, 2, false, false), Solo.decodeParams("3x3di"))
        assertEquals(Solo.Params(3, 3, 1, 3, false, false), Solo.decodeParams("3x3da"))
        assertEquals(Solo.Params(3, 3, 1, 4, false, false), Solo.decodeParams("3x3de"))
        assertEquals(Solo.Params(3, 3, 1, 5, false, false), Solo.decodeParams("3x3du"))
        assertEquals(Solo.Params(9, 1, 1, 1, false, false), Solo.decodeParams("3x3jdb"))
        assertEquals(Solo.Params(3, 3, 7, 0, false, false), Solo.decodeParams("3x3m8"))
        assertEquals(Solo.Params(3, 3, 6, 3, false, false), Solo.decodeParams("3x3md4da"))
        assertEquals(Solo.Params(3, 3, 2, 0, false, false), Solo.decodeParams("3x3r4"))
        assertEquals(Solo.Params(3, 3, 0, 0, false, false), Solo.decodeParams("3x3a"))
        assertEquals(Solo.Params(3, 3, 1, 0, true, false), Solo.decodeParams("3x3x"))
        assertEquals(Solo.Params(3, 3, 1, 0, false, true), Solo.decodeParams("3x3k"))
        // A `d` consumes one character even when it names no difficulty.
        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.decodeParams("3x3dz"))
        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.decodeParams("3x3d"))
        // A `d` at the end does not consume anything.
        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.decodeParams("3x3d"))
        // The digit skip starts at the first character, so a leading
        // sign stops the `x` parse from being seen as a separator.
        assertEquals(Solo.Params(7, 7, 1, 0, true, false), Solo.decodeParams("+7x+7"))
        assertEquals(Solo.Params(0, 0, 1, 0, false, false), Solo.decodeParams("zzz"))
        assertEquals(
            "3x3md4da",
            Solo.encodeParams(Solo.Params(3, 3, 6, 3, false, false), true),
        )
        assertEquals("9jx", Solo.encodeParams(Solo.Params(9, 1, 1, 1, true, false), false))
        // The encoder has no default arm.
        assertEquals("3x3", Solo.encodeParams(Solo.Params(3, 3, 9, 9, false, false), true))
        assertEquals("3x3", Solo.encodeParams(Solo.Params(3, 3, -1, -1, false, false), true))
    }

    @Test
    fun `validation follows the C order`() {
        fun check(c: Int, r: Int, xtype: Boolean = false, killer: Boolean = false): String? =
            Solo.validateParams(Solo.Params(c, r, 1, 0, xtype, killer), true)

        assertNull(check(3, 3))
        assertNull(check(31, 1))
        assertNull(check(3, 2, xtype = true))
        assertNull(check(3, 3, killer = true))
        assertNull(check(3, 0))
        assertNull(check(3, -1))
        assertEquals("Both dimensions must be at least 2", check(1, 3))
        assertEquals("Both dimensions must be at least 2", check(0, 0))
        assertEquals("Both dimensions must be at least 2", check(-1, 3))
        assertEquals(
            "Dimensions greater than 255 are not supported",
            check(256, 3),
        )
        assertEquals(
            "Unable to support more than 31 distinct symbols in a puzzle",
            check(32, 1),
        )
        assertEquals(
            "Unable to support more than 31 distinct symbols in a puzzle",
            check(6, 6, killer = true),
        )
        assertEquals(
            "Killer puzzle dimensions must be smaller than 10",
            check(3, 4, killer = true),
        )
        assertEquals(
            "X-type puzzle dimensions must be larger than 3",
            check(2, 1, xtype = true),
        )
    }

    @Test
    fun `the description grammar and messages match the C parser`() {
        val p = Solo.decodeParams("3x3")
        assertNull(Solo.validateDesc(p, "i".repeat(9)))
        assertNull(Solo.validateDesc(p, "1" + "a".repeat(80)))
        assertEquals(
            "Invalid character in game description",
            Solo.validateDesc(p, "!"),
        )
        assertEquals(
            "Invalid character in game description",
            Solo.validateDesc(p, "."),
        )
        assertEquals(
            "Number out of range in game description",
            Solo.validateDesc(p, "10" + "a".repeat(80)),
        )
        assertEquals(
            "Not enough data to fill grid",
            Solo.validateDesc(p, "a"),
        )
        assertEquals(
            "Too much data to fit in grid",
            Solo.validateDesc(p, "a".repeat(82)),
        )

        val jigsaw = "i1c5b9c8_6f4d3a6b5c2b3a1d5f9_1c9b8c2i,cf_abcb_ab_bbacb__ccaa__ae_d_babbaa_aaab_a__ba_a____bcc_abbe"
        assertNull(Solo.validateDesc(Solo.decodeParams("9jdb"), jigsaw))
        assertEquals(
            "Expected jigsaw block structure in game description",
            Solo.validateDesc(Solo.decodeParams("9jdb"), jigsaw.substringBefore(',')),
        )
        assertEquals(
            "Unexpected data at end of game description",
            Solo.validateDesc(Solo.decodeParams("9jdb"), "$jigsaw,x"),
        )

        val killer = "p,a______aa_baa_aa,b_4_6_7_7d_6b_5b"
        assertNull(Solo.validateDesc(Solo.decodeParams("2x2k"), killer))
        assertEquals(
            "Expected killer block structure in game description",
            Solo.validateDesc(Solo.decodeParams("2x2k"), "p"),
        )
        assertEquals(
            "Expected killer clue grid in game description",
            Solo.validateDesc(Solo.decodeParams("2x2k"), "p,a______aa_baa_aa"),
        )

        assertFailsWith<IllegalArgumentException> { Solo.newState(p, "!") }
        assertFailsWith<IllegalArgumentException> { Solo.newState(p, "a") }
    }

    @Test
    fun `the text form keeps the C layout`() {
        val midend = Midend.newGame(Solo, "3x3", "12345")
        val text = midend.textFormat()
        val lines = text.lines().dropLast(1)
        assertEquals(11, lines.size)
        assertEquals(21, lines.first().length)
        assertTrue(text.endsWith("\n"))
        assertTrue(text.contains("------+-------+------"))
        assertTrue(text.contains(". . | . ."))
        assertFalse(text.contains("?"))

        // Every digit above nine is a letter.
        val wide = Midend.newGame(Solo, "4x4db", "12345")
        val wideText = wide.textFormat()
        assertTrue(wideText.any { it in 'a'..'g' })
        assertTrue(wideText.split('\n').first().contains('|'))

        // A killer puzzle has no text form, as C returns false.
        val killer = Solo.decodeParams("3x3ka")
        assertFalse(Solo.canFormatAsTextNow(killer))
        assertTrue(Solo.canFormatAsTextNow(Solo.decodeParams("3x3")))
        val killerMidend = Midend.newGame(Solo, "3x3ka", "12345")
        assertEquals("", killerMidend.textFormat())

        // X-diagonal empty cells are underscores, not dots.
        val x = Midend.newGame(Solo, "3x3xdb", "12345")
        val xText = x.textFormat()
        assertTrue(xText.contains('_'))
    }

    @Test
    fun `the move grammar matches the C parser`() {
        val midend = Midend.newGame(Solo, "3x3", "12345")
        val state = midend.state as Solo.State
        val solved = midend.auxInfo!!

        fun apply(move: String): Solo.State? = Solo.executeMove(state, move) as Solo.State?

        val entry = apply("R4,4,5")
        assertNotNull(entry)
        assertEquals(5, entry!!.valueAt(4, 4))
        val expectedGrid = state.grid.copyOf()
        expectedGrid[4 * 9 + 4] = 5
        assertEquals(expectedGrid.toList(), entry.grid.toList())
        for (n in 1..9) assertFalse(entry.pencilAt(4, 4, n))

        val cleared = apply("R0,0,0")
        assertNotNull(cleared)
        assertEquals(0, cleared!!.valueAt(0, 0))

        val pencil = apply("P4,4,1")
        assertNotNull(pencil)
        assertTrue(pencil!!.pencilAt(4, 4, 1))
        val unpencil = Solo.executeMove(pencil, "P4,4,1") as Solo.State
        assertFalse(unpencil.pencilAt(4, 4, 1))
        // A `P` with n == 0 takes the R branch and clears the cell.
        val pencilZero = Solo.executeMove(pencil, "P4,4,0") as Solo.State
        assertEquals(0, pencilZero.valueAt(4, 4))

        val marked = apply("M")!!
        for (y in 0 until 9) {
            for (x in 0 until 9) {
                if (marked.valueAt(x, y) == 0) {
                    for (n in 1..9) assertTrue(marked.pencilAt(x, y, n))
                } else {
                    for (n in 1..9) assertFalse(marked.pencilAt(x, y, n))
                }
            }
        }
        assertNotNull(Solo.executeMove(marked, "Mfoo") as Solo.State)

        // The C `sscanf` grammar accepts whitespace, signs, leading
        // zeros, and trailing junk; a bad coordinate or value rejects.
        assertNotNull(apply("R 4, 4, 5"))
        assertNotNull(apply("R+4,+4,+5"))
        assertNotNull(apply("R4,4,5junk"))
        assertNotNull(apply("R4,4,05"))
        assertNotNull(apply("R4,4,-0"))
        assertNull(apply("R0,0,10"))
        assertNull(apply("R-1,0,1"))
        assertNull(apply("R0,0"))
        assertNull(apply("R999999999999,4,5"))
        assertNull(apply("D4,4,5"))
        assertNull(apply(""))
        assertNull(apply("S1,2"))

        val solvedState = apply(solved)
        assertNotNull(solvedState)
        assertTrue(solvedState!!.completed)
        assertTrue(solvedState.cheated)
        assertEquals(81, solvedState.grid.count { it in 1..9 })
    }

    @Test
    fun `a real move completes the board once`() {
        val midend = Midend.newGame(Solo, "3x3", "12345")
        val state = midend.state as Solo.State
        val solved = midend.auxInfo!!.removePrefix("S").split(',').map { it.toInt() }
        var current: Solo.State = state
        for (i in 0 until 81) {
            if (state.grid[i] != 0) continue
            current = Solo.executeMove(current, "R${i % 9},${i / 9},${solved[i]}") as Solo.State
        }
        assertTrue(current.completed)
        assertFalse(current.cheated)
        assertEquals(1, Solo.status(current))
        assertEquals("", Solo.statusText(current, midend.ui))
        // The completion is sticky when a later move breaks the board.
        val broken = Solo.executeMove(current, "R0,0,0") as Solo.State
        assertTrue(broken.completed)
        assertEquals(1, Solo.status(broken))
    }

    @Test
    fun `the solver finds the generated answer and reports failures`() {
        for (params in presets) {
            val midend = Midend.newGame(Solo, params, "12345")
            val state = midend.state as Solo.State
            assertEquals(midend.auxInfo, Solo.solve(state, null), "$params solve")
        }
        // An empty board is ambiguous.
        val empty = Solo.newState(Solo.decodeParams("3x3"), "i".repeat(9))
        assertNull(Solo.solve(empty, null))
        assertEquals(
            "Multiple solutions exist for this puzzle",
            Solo.solveGame(empty, null).error,
        )
        // A board with duplicate clues is impossible.
        val bad = Solo.newState(Solo.decodeParams("3x3"), "1_".repeat(80) + "1")
        assertNull(Solo.solve(bad, null))
        assertEquals(
            "No solution exists for this puzzle",
            Solo.solveGame(bad, null).error,
        )
        // A stored answer is returned unchanged, as C does.
        val state = Solo.newState(Solo.decodeParams("3x3"), "i".repeat(9))
        assertEquals("S1,2", Solo.solve(state, "S1,2"))
    }

    @Test
    fun `a solve uses the initial grid after a move`() {
        val midend = Midend.newGame(Solo, "3x3", "12345")
        val initial = midend.state as Solo.State
        val solvedValue = midend.auxInfo!!.removePrefix("S").split(',')[40].toInt()
        val wrong = if (solvedValue == 9) 8 else solvedValue + 1
        val moved = Solo.executeMove(initial, "R4,4,$wrong") as Solo.State
        assertNotEquals(initial.grid.toList(), moved.grid.toList())
        // Without AUXINFO the solver reads the first state, as C does.
        assertEquals(midend.auxInfo, Solo.solve(moved, null))
        // The second solve on a completed board still uses the first
        // state and returns the same answer.
        val solved = Solo.executeMove(initial, midend.auxInfo!!) as Solo.State
        assertTrue(solved.completed && solved.cheated)
        assertEquals(midend.auxInfo, Solo.solve(solved, null))
    }

    @Test
    fun `key labels cursor and changed state match the C game`() {
        val midend = Midend.newGame(Solo, "3x3", "12345")
        val ui = midend.ui as Solo.Ui
        val state = midend.state as Solo.State
        val request = Solo.requestKeys(Solo.decodeParams("3x3"))
        assertEquals(ArrowMode.ARROWS_LEFT, request.arrowMode)
        assertEquals(11, request.labels.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "Mark"),
            request.labels.map { it.name },
        )
        assertEquals('\b'.code, request.labels[9].button)
        assertTrue(request.labels.none { it.needsArrows })
        // A larger board uses letters for the digits above nine.
        val wide = Solo.requestKeys(Solo.decodeParams("4x4db"))
        assertEquals("a", wide.labels[9].name)
        assertEquals("g", wide.labels[15].name)
        assertEquals("Clear", wide.labels[16].name)

        assertEquals("", Solo.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.hshow = true
        assertEquals("Pencil", Solo.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.hpencil = true
        assertEquals("Ink", Solo.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Solo.currentKeyLabel(ui, state, '5'.code))

        ui.hshow = false
        assertNull(Solo.cursorLocation(ui, state, 48))
        ui.hshow = true
        ui.hx = 2
        ui.hy = 3
        assertEquals(Game.CursorRect(2 * 48 + 24 + 1 + 1, 3 * 48 + 24 + 1 + 1, 48, 48), Solo.cursorLocation(ui, state, 48))
        Solo.setCursorVisible(ui, false)
        assertFalse(ui.hshow)
        Solo.setCursorVisible(ui, true)
        assertTrue(ui.hshow)

        val old = state.copy() as Solo.State
        val completed = state.copy() as Solo.State
        completed.completed = true
        assertTrue(Solo.changedState(ui, old, completed))
        assertFalse(Solo.changedState(ui, completed, completed))
        assertFalse(Solo.changedState(ui, null, completed))
        // A solve sets `cheated`, so it never raises the event.
        val cheated = Solo.executeMove(state, midend.auxInfo!!) as Solo.State
        assertTrue(cheated.completed && cheated.cheated)
        assertFalse(Solo.changedState(ui, old, cheated), "a solve is not an event")
    }

    @Test
    fun `a killer description without a clue is defined`() {
        val p = Solo.decodeParams("2x2k")
        val desc = "3_2_1_4_1_4_3_2_4_3_2_1_2_1_4_3,a______aa_baa_aa,b_4_6_7_7d_6b_5b"
        val state = Solo.newState(p, desc) as Solo.State
        assertFalse(state.completed, "a full unclued board is open")
        val moved = Solo.executeMove(state, "R0,0,3") as Solo.State
        assertFalse(moved.completed)
        assertEquals(0, Solo.status(moved))
        // The unclued puzzle is ambiguous, so the solve returns null.
        val empty = Solo.newState(p, "p,a______aa_baa_aa,b_4_6_7_7d_6b_5b")
        assertNull(Solo.solve(empty, null))
        assertEquals("S1,2", Solo.solve(empty, "S1,2"))
    }

    @Test
    fun `a killer singleton overflow is retried`() {
        // `gen_killer_cages` can merge a singleton into a full cage and
        // write past the cage row. C returns a corrupted description;
        // the port retries the attempt and returns a valid one (D-025).
        for (params in listOf("3jka", "2x2xka")) {
            val random = RandomState.fromSeed("1")
            val first = Solo.newDesc(Solo.decodeParams(params), random)
            assertNull(
                Solo.validateDesc(Solo.decodeParams(params), first.text),
                "$params description validates",
            )
            val second = Solo.newDesc(
                Solo.decodeParams(params),
                RandomState.fromSeed("1"),
            )
            assertEquals(first.text, second.text, "$params determinism")
            // The generated save reloads through the engine.
            val reloaded = Midend.fromGameId(
                Solo,
                Solo.encodeParams(Solo.decodeParams(params), false) + ":" + first.text,
            )
            assertEquals(first.text, reloaded.desc, "$params reload")
        }
    }

    @Test
    fun `hand made parameters are defined before any random draw`() {
        val r = RandomState.fromSeed("1")
        val before = r.encodeState()
        assertFailsWith<IllegalArgumentException> {
            Solo.newDesc(Solo.Params(3, 0, 1, 0, false, false), r)
        }
        assertEquals("solo: the grid has no cells", runCatching {
            Solo.newDesc(Solo.Params(3, -1, 1, 1, false, false), r)
        }.exceptionOrNull()!!.message)
        assertFailsWith<IllegalArgumentException> {
            Solo.newDesc(Solo.Params(3, 3, 1, 6, false, false), r)
        }
        assertFailsWith<IllegalArgumentException> {
            Solo.newDesc(Solo.Params(3, 3, 8, 1, false, false), r)
        }
        assertFailsWith<IllegalArgumentException> {
            Solo.newDesc(Solo.Params(3, 3, 1, 1, false, false, 4), r)
        }
        assertEquals(before, r.encodeState(), "the guards draw no number")
        assertFailsWith<IllegalArgumentException> {
            Solo.newState(Solo.Params(3, 0, 1, 0, false, false), "")
        }
    }

    @Test
    fun `a generated state reloads and keeps the shared initial grid`() {
        val midend = Midend.newGame(Solo, "3x3du", "12345")
        val state = midend.state as Solo.State
        val copy = state.copy() as Solo.State
        assertEquals(state, copy)
        assertEquals(state.grid.toList(), copy.grid.toList())
        assertEquals(
            midend.serialise().serialise(),
            Midend.fromSave(Solo, midend.serialise()).serialise().serialise(),
        )
        // The initial grid survives a move and a copy.
        val solved = midend.auxInfo!!.removePrefix("S").split(',')[40].toInt()
        val wrong = if (solved == 9) 8 else solved + 1
        val moved = Solo.executeMove(state, "R4,4,$wrong") as Solo.State
        assertEquals(state.shared.initialGrid.toList(), moved.shared.initialGrid.toList())
        assertNotEquals(state.grid.toList(), moved.grid.toList())
    }

}
