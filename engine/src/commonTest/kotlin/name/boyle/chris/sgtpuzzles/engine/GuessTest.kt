package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: guess.
 *
 * Kotlin-only pins for the parts that the oracle protocol cannot
 * express, plus the unit-level rules behind the parity suite: the UI
 * character-stream parser, the defined result of the C out-of-bounds
 * backspace, the unmarkable submit, the palette arithmetic, the
 * geometry, and the hidden solution.
 */
class GuessTest {

    private val tile = 32

    @Test
    fun `defaults and presets match the C tables`() {
        val defaults = Guess.defaultParams() as Guess.Params
        assertEquals(6, defaults.ncolours)
        assertEquals(4, defaults.npegs)
        assertEquals(10, defaults.nguesses)
        assertFalse(defaults.allowBlank)
        assertTrue(defaults.allowMultiple)
        assertEquals("c6p4g10Bm", defaults.shortText)
        assertEquals("c6p4g10Bm", defaults.fullText)

        val presets = Guess.presets()
        assertEquals(2, presets.size)
        assertEquals("Standard", presets[0].title)
        assertEquals("c6p4g10Bm", presets[0].params)
        assertEquals("Super", presets[1].title)
        assertEquals("c8p5g12Bm", presets[1].params)

        // `encode_params` ignores `full`, so the two forms are equal for
        // every flag combination.
        for (blank in listOf(false, true)) {
            for (multiple in listOf(false, true)) {
                val p = Guess.Params(4, 4, 10, blank, multiple)
                assertEquals(
                    Guess.encodeParams(p, true),
                    Guess.encodeParams(p, false),
                )
                assertEquals(
                    "c4p4g10" + (if (blank) "b" else "B") +
                        (if (multiple) "m" else "M"),
                    Guess.encodeParams(p, true),
                )
            }
        }
    }

    @Test
    fun `decode params ignores unknown characters and keeps the flags`() {
        val defaults = Guess.Params(6, 4, 10, false, true)
        assertEquals(defaults, Guess.decodeParams(""))
        assertEquals(defaults, Guess.decodeParams("c6p4g10Bm"))
        assertEquals(defaults, Guess.decodeParams("p4c6g10"))
        assertEquals(defaults, Guess.decodeParams("c6x4g10Bm"))
        assertEquals(defaults, Guess.decodeParams("c6p4g10Xm"))
        assertEquals(defaults, Guess.decodeParams("c6p4g10B"))
        assertEquals(defaults, Guess.decodeParams("c6p4g10m"))
        assertEquals(defaults, Guess.decodeParams("6x4"))
        assertEquals(
            Guess.Params(6, 4, 10, true, true),
            Guess.decodeParams("c6p4g10bm"),
        )
        assertEquals(
            Guess.Params(6, 4, 10, false, false),
            Guess.decodeParams("C6P4G10BM"),
        )
        assertEquals(-2, (Guess.decodeParams("c-2p4g10Bm") as Guess.Params).ncolours)
    }

    @Test
    fun `the parameter parser saturates like an int atoi`() {
        fun colours(text: String): Int =
            (Guess.decodeParams(text) as Guess.Params).ncolours

        // C leaves the overflow undefined; the port saturates, as the
        // `Pegs` and `SameGame` helpers do.
        assertEquals(Int.MAX_VALUE, colours("c" + "9".repeat(40)))
        assertEquals(Int.MAX_VALUE, colours("c+" + "9".repeat(40)))
        assertEquals(Int.MIN_VALUE, colours("c-" + "9".repeat(40)))

        // The exact boundary, and one past it on each side.
        assertEquals(Int.MAX_VALUE, colours("c2147483647p4g10Bm"))
        assertEquals(Int.MAX_VALUE, colours("c2147483648p4g10Bm"))
        assertEquals(Int.MIN_VALUE, colours("c-2147483648p4g10Bm"))
        assertEquals(Int.MIN_VALUE, colours("c-2147483649p4g10Bm"))

        // `p` and `g` use the same parser.
        assertEquals(
            Int.MAX_VALUE,
            (Guess.decodeParams("p" + "1".repeat(30)) as Guess.Params).npegs,
        )
        assertEquals(
            Int.MIN_VALUE,
            (Guess.decodeParams("g-" + "1".repeat(30)) as Guess.Params).nguesses,
        )
    }

    @Test
    fun `validation messages are exact and ordered`() {
        fun message(nc: Int, np: Int, ng: Int, blank: Boolean, multiple: Boolean) =
            Guess.validateParams(Guess.Params(nc, np, ng, blank, multiple), true)

        assertEquals(
            "Trivial solutions are uninteresting",
            message(1, 4, 10, false, true),
        )
        assertEquals(
            "Trivial solutions are uninteresting",
            message(6, 1, 10, false, true),
        )
        assertEquals("Too many colours", message(11, 4, 10, false, true))
        assertEquals(
            "Must have at least one guess",
            message(6, 4, 0, false, true),
        )
        assertEquals(
            "Disallowing multiple colours requires at least as many colours as pegs",
            message(3, 4, 10, false, false),
        )
        assertNull(message(10, 10, 1, true, true))
        // `full` is ignored: both calls are the same.
        assertEquals(
            message(3, 4, 10, false, false),
            Guess.validateParams(Guess.Params(3, 4, 10, false, false), false),
        )
    }

    @Test
    fun `descriptions match the frozen vectors on both RNG paths`() {
        // The no-multiple cases pin the retry rule. A retry draws the
        // same peg again, so a port that draws for every peg, or that
        // reseeds, cannot produce these bytes.
        val cases = listOf(
            Triple("c6p4g10Bm", "12345", "ac769e34"),
            Triple("c8p5g12Bm", "12345", "2177d9fe4e"),
            Triple("c3p2g1bm", "0", "3ea7"),
            Triple("c3p2g1bm", "3", "e432"),
            Triple("c3p2g1bm", "4", "c1ac"),
            Triple("c3p3g2BM", "3", "597268"),
            Triple("c4p4g10BM", "1", "5180d9ff"),
            // 23 draws: 19 duplicates are not stored.
            Triple("c4p4g10BM", "9", "79e39f8b"),
            // 6 draws for two pegs: 4 duplicates.
            Triple("c3p2g1BM", "2", "c37a"),
            Triple("c10p10g1bm", "0", "4c7fae417f08320e176e"),
            Triple("c10p2g3bm", "0", "2be1"),
        )
        for ((params, seed, desc) in cases) {
            assertEquals(
                desc,
                (Guess.newDesc(Guess.decodeParams(params), RandomState.fromSeed(seed))).text,
                "$params seed $seed",
            )
        }

        // The hand-checked vectors from the specification, including a
        // 10-colour description and a two-digit color.
        assertEquals(
            listOf(10, 10),
            (Guess.newState(Guess.decodeParams("c10p2g3Bm"), "76e0") as Guess.State)
                .solution.toList(),
        )
        assertEquals(
            listOf(1, 1),
            (Guess.newState(Guess.decodeParams("c3p2g1bm"), "e432") as Guess.State)
                .solution.toList(),
        )
        assertEquals(
            listOf(1, 2),
            (Guess.newState(Guess.decodeParams("c3p2g1bm"), "c1ac") as Guess.State)
                .solution.toList(),
        )
    }

    @Test
    fun `validate desc checks the length and the deobfuscated range`() {
        val three = Guess.decodeParams("c3p2g1bm")
        assertEquals(
            "Game description is wrong length",
            Guess.validateDesc(three, "abc"),
        )
        assertEquals(
            "Game description is wrong length",
            Guess.validateDesc(three, "3ea7ff"),
        )
        assertEquals(
            "Game description is corrupted",
            Guess.validateDesc(three, "zzzz"),
        )
        assertEquals(
            "Game description is corrupted",
            Guess.validateDesc(three, "0000"),
        )
        // Zero is never a generated color.
        val zeroes = AuxInfo.encodeBytes(byteArrayOf(0, 0))
        assertEquals("Game description is corrupted", Guess.validateDesc(three, zeroes))
        // 4 is out of range for three colors.
        val four = AuxInfo.encodeBytes(byteArrayOf(4, 1))
        assertEquals("Game description is corrupted", Guess.validateDesc(three, four))

        // Uppercase hex is accepted, and duplicates are accepted even
        // when multiple colors are disallowed.
        assertNull(Guess.validateDesc(three, "E432"))
        val noMultiple = Guess.decodeParams("c3p2g1BM")
        assertNull(Guess.validateDesc(noMultiple, "e432"))
        assertNull(Guess.validateDesc(noMultiple, "c1ac"))
    }

    @Test
    fun `new state hides the solution and shares it with a copy`() {
        val params = Guess.decodeParams("c3p2g1bm")
        val state = Guess.newState(params, "3ea7") as Guess.State
        assertEquals(listOf(3, 3), state.solution.toList())
        assertEquals(0, state.nextGo)
        assertEquals(0, state.solved)
        assertEquals(1, state.guesses.size)
        assertTrue(state.guesses[0].pegs.all { it == 0 })
        assertTrue(state.guesses[0].feedback.all { it == 0 })
        assertTrue(state.holds.all { !it })

        val copy = state.copy() as Guess.State
        assertSame(state.solution, copy.solution)
        assertEquals(state, copy)
        // The mutable history is copied.
        copy.guesses[0].pegs[0] = 2
        copy.holds[0] = true
        assertNotEquals(state, copy)
        assertEquals(0, state.guesses[0].pegs[0])
        assertFalse(state.holds[0])

        // The case of the hex is not canonicalized by newState.
        val upper = Guess.newState(params, "3EA7") as Guess.State
        assertEquals(state.solution.toList(), upper.solution.toList())
    }

    @Test
    fun `state toString omits the solution and keeps the visible fields`() {
        val params = Guess.decodeParams("c3p2g1bm")
        val state = Guess.newState(params, "3ea7") as Guess.State
        assertEquals(listOf(3, 3), state.solution.toList())

        // The visible fields are in the line, so a log is useful.
        val text = state.toString()
        assertTrue(text.contains("Guess.State("))
        assertTrue(text.contains(params.toString()))
        assertTrue(text.contains("nextGo=0"))
        assertTrue(text.contains("solved=0"))

        // The hidden answer is not, in any common rendering.
        assertFalse(text.contains("solution"))
        assertFalse(text.contains("Solution"))
        assertFalse(text.contains(state.solution.contentToString()))
        assertFalse(text.contains(state.solution.joinToString(",")))
        assertFalse(text.contains(state.solution.joinToString(" ")))
    }

    @Test
    fun `the move parser follows atoi and the character stream`() {
        val blanksAllowed = Guess.decodeParams("c3p2g1bm")
        val noBlanks = Guess.decodeParams("c3p2g1BM")
        fun row(params: Game.Params, move: String): List<Int>? {
            val next = Guess.executeMove(Guess.newState(params, "3ea7"), move)
                ?: return null
            return (next as Guess.State).guesses[0].pegs.toList()
        }

        assertEquals(listOf(1, 2), row(noBlanks, "G1,2"))
        assertEquals(listOf(1, 1), row(noBlanks, "G 1, 2"))
        assertEquals(listOf(1, 1), row(noBlanks, "G+1,+2"))
        assertEquals(listOf(1, 2), row(noBlanks, "G1,2junk"))
        assertEquals(listOf(1, 2), row(noBlanks, "G1,2,3"))
        assertNull(row(noBlanks, "G0,1"))
        assertNull(row(noBlanks, "G1"))
        assertNull(row(noBlanks, "G4,1"))
        assertNull(row(noBlanks, "G-1,1"))
        assertNull(row(noBlanks, "s"))
        assertNull(row(noBlanks, "X1,2"))
        assertNull(row(noBlanks, ""))

        // With blanks, a missing component and an explicit zero are both 0.
        assertEquals(listOf(1, 0), row(blanksAllowed, "G1,"))
        assertEquals(listOf(1, 0), row(blanksAllowed, "G1"))
        assertEquals(listOf(0, 1), row(blanksAllowed, "G0,1"))
    }

    @Test
    fun `feedback packs place hints before colour hints`() {
        val params = Guess.decodeParams("c4p4g10bm")
        val desc = AuxInfo.encodeBytes(byteArrayOf(1, 2, 3, 4))

        fun feedback(move: String): List<Int> {
            val state = Guess.newState(params, desc)
            val next = Guess.executeMove(state, move) as Guess.State
            return next.guesses[0].feedback.toList()
        }

        assertEquals(
            listOf(
                Guess.FEEDBACK_CORRECTPLACE,
                Guess.FEEDBACK_CORRECTPLACE,
                Guess.FEEDBACK_CORRECTCOLOUR,
                Guess.FEEDBACK_CORRECTCOLOUR,
            ),
            feedback("G1,2,4,3"),
        )
        assertEquals(
            listOf(
                Guess.FEEDBACK_CORRECTCOLOUR,
                Guess.FEEDBACK_CORRECTCOLOUR,
                Guess.FEEDBACK_CORRECTCOLOUR,
                Guess.FEEDBACK_CORRECTCOLOUR,
            ),
            feedback("G2,1,4,3"),
        )
        assertEquals(
            listOf(
                Guess.FEEDBACK_CORRECTPLACE,
                Guess.FEEDBACK_CORRECTPLACE,
                0,
                0,
            ),
            feedback("G1,1,3,3"),
        )
        assertEquals(listOf(0, 0, 0, 0), feedback("G0,0,0,0"))

        // Duplicates: only two of the four 1s match.
        val dupParams = Guess.decodeParams("c4p4g10bm")
        val dupDesc = AuxInfo.encodeBytes(byteArrayOf(1, 1, 2, 2))
        val dup = Guess.newState(dupParams, dupDesc)
        val dupNext = Guess.executeMove(dup, "G1,1,1,1") as Guess.State
        assertEquals(
            listOf(
                Guess.FEEDBACK_CORRECTPLACE,
                Guess.FEEDBACK_CORRECTPLACE,
                0,
                0,
            ),
            dupNext.guesses[0].feedback.toList(),
        )
    }

    @Test
    fun `a win keeps the row and a last wrong guess loses`() {
        val params = Guess.decodeParams("c3p2g1bm")
        val start = Guess.newState(params, "3ea7")
        val win = Guess.executeMove(start, "G3,3") as Guess.State
        assertEquals(1, win.solved)
        assertEquals(0, win.nextGo)
        assertEquals(0, Guess.status(start))
        assertEquals(1, Guess.status(win))
        assertNull(Guess.executeMove(win, "G3,3"), "no guess after a win")
        assertEquals(-1, (Guess.executeMove(win, "S") as Guess.State).solved)

        val loss = Guess.executeMove(start, "G1,1") as Guess.State
        assertEquals(-1, loss.solved)
        assertEquals(1, loss.nextGo)
        assertEquals(-1, Guess.status(loss))
    }

    @Test
    fun `move count and draft change on a real guess`() {
        val midend = Midend.newGame(Guess, "", "0")
        val before = midend.serialise().serialise()
        assertEquals(1, midend.stateCount)
        assertFalse(midend.canUndo)

        // 1, 1, 1, 1 is not the solution 3, 3, 4, 1.
        for (key in listOf('1', '1', '1', '1')) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key.code, tile),
            )
        }
        assertEquals("1,1,1,1", (midend.ui as Guess.Ui).currPegs.joinToString(","))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(2, midend.stateCount)
        assertTrue(midend.canUndo)
        assertNotEquals(before, midend.serialise().serialise())
        // The draft is cleared because nothing is held.
        assertEquals("0,0,0,0", (midend.ui as Guess.Ui).currPegs.joinToString(","))
    }

    @Test
    fun `a win raises the completion event once and clears the draft`() {
        val midend = Midend.newGame(Guess, "", "0")
        for (key in listOf('3', '3', '4', '1')) {
            midend.processKey(0, 0, key.code, tile)
        }
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(1, midend.status())
        assertEquals(
            listOf("G3,3,4,1"),
            SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
        )
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
        assertEquals("0,0,0,0", (midend.ui as Guess.Ui).currPegs.joinToString(","))
        assertEquals(
            "false,false,false,false",
            (midend.ui as Guess.Ui).holds.joinToString(","),
        )
    }

    @Test
    fun `holds echo into the next draft and undo clears them`() {
        // Holds echo into the next draft and undo clears them.
        val midend = Midend.newGame(Guess, "", "0")
        // Drag 3,3,4,1 into the row.
        drag(midend, 3, 0)
        drag(midend, 3, 1)
        drag(midend, 4, 2)
        drag(midend, 1, 3)
        val ui = midend.ui as Guess.Ui
        assertEquals("3,3,4,1", ui.currPegs.joinToString(","))

        // Right-click the first two pegs.
        rightClick(midend, pegCentre(0))
        rightClick(midend, pegCentre(1))
        assertTrue(ui.holds[0])
        assertTrue(ui.holds[1])

        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(hintX, firstRowY, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(hintX, firstRowY, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals(
            listOf("G3_,3_,4,1"),
            SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
        )
        // The winning move clears the draft and the holds.
        assertEquals("0,0,0,0", ui.currPegs.joinToString(","))
        assertEquals("false,false,false,false", ui.holds.joinToString(","))

        assertTrue(midend.undo())
        assertEquals("0,0,0,0", ui.currPegs.joinToString(","))
        assertTrue(midend.redo())
        assertEquals("0,0,0,0", ui.currPegs.joinToString(","))
    }

    @Test
    fun `a non-winning held guess echoes the held pegs`() {
        val midend = Midend.newGame(Guess, "", "0")
        // 2,2,2,2 is not the solution 3,3,4,1.
        for (key in listOf('2', '2', '2', '2')) {
            midend.processKey(0, 0, key.code, tile)
        }
        val ui = midend.ui as Guess.Ui
        rightClick(midend, pegCentre(0))
        rightClick(midend, pegCentre(1))
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(hintX, firstRowY, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(hintX, firstRowY, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals("2_,2_,0,0", Guess.encodeUi(ui))
        assertTrue(midend.undo())
        assertEquals("0,0,0,0", Guess.encodeUi(ui))
        assertTrue(midend.redo())
        assertEquals("2_,2_,0,0", Guess.encodeUi(ui))
    }

    @Test
    fun `a drag out of a current peg clears it`() {
        val midend = Midend.newGame(Guess, "", "0")
        val ui = midend.ui as Guess.Ui
        drag(midend, 3, 0)
        assertEquals(3, ui.currPegs[0])

        midend.processKey(pegCentre(0), firstRowY, Buttons.LEFT_BUTTON, tile)
        midend.processKey(10, 300, Buttons.LEFT_DRAG, tile)
        midend.processKey(10, 300, Buttons.LEFT_RELEASE, tile)
        assertEquals(0, ui.currPegs[0])
        assertEquals(0, ui.dragCol)
        assertFalse(ui.displayCur)
    }

    @Test
    fun `a drag from a past guess copies into the current row`() {
        val midend = Midend.newGame(Guess, "", "0")
        for (key in listOf('1', '1', '1', '1')) {
            midend.processKey(0, 0, key.code, tile)
        }
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
        assertEquals(1, (midend.state as Guess.State).nextGo)

        midend.processKey(pegCentre(0), firstRowY, Buttons.LEFT_BUTTON, tile)
        midend.processKey(pegCentre(1), secondRowY, Buttons.LEFT_DRAG, tile)
        midend.processKey(pegCentre(1), secondRowY, Buttons.LEFT_RELEASE, tile)
        assertEquals("0,1,0,0", (midend.ui as Guess.Ui).currPegs.joinToString(","))
    }

    @Test
    fun `the label key toggles the preference without a history entry`() {
        val midend = Midend.newGame(Guess, "", "0")
        val ui = midend.ui as Guess.Ui
        assertFalse(ui.showLabels)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'l'.code, tile))
        assertTrue(ui.showLabels)
        assertEquals(1, midend.stateCount)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'L'.code, tile))
        assertFalse(ui.showLabels)

        // It works on a solved game; every other key is unused.
        midend.solve()
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'l'.code, tile))
        assertTrue(ui.showLabels)
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, '1'.code, tile))
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, 'h'.code, tile))
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
    }

    @Test
    fun `reveal S sets the loss status and undo returns to the game`() {
        val midend = Midend.newGame(Guess, "", "0")
        assertTrue(midend.solve())
        assertEquals(-1, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertEquals(listOf("S"), SgtpSave.parse(midend.serialise().serialise()).values("SOLVE"))
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertTrue(midend.redo())
        assertEquals(-1, midend.status())

        // A repeated solve is accepted, as in the vendored revision.
        assertTrue(midend.solve())
        assertEquals(2, SgtpSave.parse(midend.serialise().serialise()).values("SOLVE").size)
        assertEquals(-1, midend.status())
    }

    @Test
    fun `the hint cache survives a move and resets on undo`() {
        val midend = Midend.newGame(Guess, "", "0")
        val ui = midend.ui as Guess.Ui
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'h'.code, tile))
        assertEquals("1,1,1,1", ui.currPegs.joinToString(","))
        assertNotNull(ui.hint)
        assertEquals(4, ui.pegCur)

        // A second hint on the same state returns the same candidate.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'h'.code, tile))
        assertEquals("1,1,1,1", ui.currPegs.joinToString(","))

        midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
        assertEquals(1, (midend.state as Guess.State).nextGo)
        assertNotNull(ui.hint)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'h'.code, tile))
        assertEquals("1,2,2,2", ui.currPegs.joinToString(","))

        assertTrue(midend.undo())
        assertNull(ui.hint)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'h'.code, tile))
        assertEquals("1,1,1,1", ui.currPegs.joinToString(","))
    }

    @Test
    fun `markable follows the draft and the blanks rule`() {
        val strict = Guess.decodeParams("c3p2g1BM") as Guess.Params
        val blank = Guess.decodeParams("c3p2g1bM") as Guess.Params
        val blankMultiple = Guess.decodeParams("c3p2g1bm") as Guess.Params
        assertFalse(Guess.isMarkable(strict, intArrayOf(1, 0)))
        assertTrue(Guess.isMarkable(strict, intArrayOf(1, 2)))
        assertFalse(Guess.isMarkable(strict, intArrayOf(1, 1)))
        // A blank game needs at least one set peg, not all of them.
        assertFalse(Guess.isMarkable(blank, intArrayOf(0, 0)))
        assertTrue(Guess.isMarkable(blank, intArrayOf(0, 1)))
        assertTrue(Guess.isMarkable(blankMultiple, intArrayOf(1, 1)))
        // A hold on an empty peg does not count as a set peg.
        val ui = Guess.newUi(Guess.newState(strict, "e432")) as Guess.Ui
        ui.holds[0] = true
        assertFalse(ui.markable)
        assertEquals("0_,0", Guess.encodeUi(ui))
    }

    @Test
    fun `the UI record writes and re-reads the exact character stream`() {
        val state = Guess.newState(Guess.decodeParams("c3p2g1bm"), "3ea7")
        fun decode(text: String): String =
            Guess.encodeUi(Guess.decodeUi(state, text))!!

        assertEquals("0,0", decode("0,0"))
        assertEquals("3,0", decode("3,0"))
        assertEquals("3_,0", decode("3_,0"))
        assertEquals("1_,1_", decode("1_,1_"))
        assertEquals("0,0", decode("9,0"))
        assertEquals("0,0", decode("-1,0"))
        assertEquals("1,0", decode("1,-2"))
        assertEquals("1,0", decode("1"))
        assertEquals("1,0", decode("1,"))
        assertEquals("1,0", decode("1,0,2"))
        assertEquals("3,3", decode(" 3 , 1 "))
        assertEquals("3,1", decode("03,01"))
        assertEquals("1,0", decode("1junk,2"))
        assertEquals("0,0", decode("10,10"))
        assertEquals("0,1", decode("10,1"))
        assertEquals("3,3", decode("3 3,1"))
        assertEquals("3,0", decode("3,,1"))
        assertEquals("2_,3", decode("2_3,1"))
        assertEquals("0,3", decode(",3"))

        // The legacy form has no holds.
        val legacy = Guess.decodeUi(state, "1,0") as Guess.Ui
        assertFalse(legacy.holds[0])
        assertFalse(legacy.holds[1])
        // `markable` is recomputed by the decoder.
        assertTrue((Guess.decodeUi(state, "1,1") as Guess.Ui).markable)
    }

    @Test
    fun `backspace at the submit slot is defined instead of out of bounds`() {
        val midend = Midend.newGame(Guess, "", "0")
        midend.processKey(0, 0, 'h'.code, tile)
        val ui = midend.ui as Guess.Ui
        assertEquals(4, ui.pegCur)
        assertTrue(ui.displayCur)

        // C reads and writes pegs[npegs]. The port treats the slot as
        // empty: it changes nothing when the cursor is visible.
        assertEquals(
            MoveResult.NoEffect,
            Guess.interpretMove(midend.state, ui, 0, 0, '\b'.code, tile),
        )
        assertEquals("1,1,1,1", ui.currPegs.joinToString(","))
        assertEquals(4, ui.pegCur)

        // With the cursor hidden the same read would be 0, so C only
        // makes the cursor visible. The port does exactly that.
        ui.displayCur = false
        assertEquals(
            MoveResult.UiUpdate,
            Guess.interpretMove(midend.state, ui, 0, 0, '\b'.code, tile),
        )
        assertTrue(ui.displayCur)
        assertEquals("1,1,1,1", ui.currPegs.joinToString(","))
    }

    @Test
    fun `an unmarkable submit is a rejected move, not a crash`() {
        val midend = Midend.newGame(Guess, "", "0")
        val ui = midend.ui as Guess.Ui
        // A full draft is markable, and the last digit moves the cursor
        // to the Submit slot.
        for (key in listOf(49, 50, 51, 52)) {
            midend.processKey(0, 0, key, tile)
        }
        assertTrue(ui.markable)
        assertEquals(4, ui.pegCur)

        // Drag the first peg out of the row. This clears it and leaves
        // the cursor at Submit, so the draft is no longer markable.
        midend.processKey(96, 33, Buttons.LEFT_BUTTON, tile)
        midend.processKey(10, 300, Buttons.LEFT_DRAG, tile)
        midend.processKey(10, 300, Buttons.LEFT_RELEASE, tile)
        assertFalse(ui.markable)
        assertEquals(4, ui.pegCur)
        assertEquals("0,2,3,4", ui.currPegs.joinToString(","))

        // C builds the move anyway, and an assertion build aborts in
        // the midend. The port lets the midend map the rejected move to
        // NoEffect, which is C's release-build result.
        assertEquals(
            MoveResult.Move("G0,2,3,4"),
            Guess.interpretMove(midend.state, ui, 0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(1, midend.stateCount)
        assertEquals(0, midend.status())
    }

    @Test
    fun `current key labels are the C strings`() {
        val midend = Midend.newGame(Guess, "", "0")
        val ui = midend.ui as Guess.Ui
        ui.pegCur = 0
        assertEquals(
            "Place",
            Guess.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT),
        )
        assertEquals(
            "Hold",
            Guess.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2),
        )
        ui.pegCur = 4
        assertEquals(
            "Submit",
            Guess.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT),
        )
        assertEquals(
            "",
            Guess.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2),
        )
        assertEquals("", Guess.currentKeyLabel(ui, midend.state, 'x'.code))
        midend.solve()
        ui.pegCur = 0
        assertEquals(
            "",
            Guess.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT),
        )
    }

    @Test
    fun `the cursor rectangle is the C formula`() {
        val midend = Midend.newGame(Guess, "c3p2g1bm", "12345")
        val ui = midend.ui as Guess.Ui
        assertNull(Guess.cursorLocation(ui, midend.state, tile))
        ui.displayCur = true
        assertEquals(Game.CursorRect(79, 31, 33, 33), Guess.cursorLocation(ui, midend.state, tile))
        ui.pegCur = 2
        assertEquals(Game.CursorRect(149, 31, 33, 33), Guess.cursorLocation(ui, midend.state, tile))
        ui.colourCur = 2
        ui.pegCur = 11
        // The Submit slot is not clamped: it is one pegsz past the row.
        assertEquals(Game.CursorRect(464, 31, 33, 33), Guess.cursorLocation(ui, midend.state, tile))
        midend.solve()
        assertNull(Guess.cursorLocation(ui, midend.state, tile))
    }

    @Test
    fun `the palette dims a light background and derives empty`() {
        fun channel(colours: FloatArray, index: Int, channel: Int): Float =
            colours[index * 3 + channel]

        val grey = Guess.colours()
        assertEquals(18, grey.size / 3)
        for (c in 0..2) assertEquals(0.8f, channel(grey, 0, c), 1e-6f)
        // COL_EMPTY is two thirds of the background.
        for (c in 0..2) assertEquals(0.53333336f, channel(grey, 5, c), 1e-6f)
        // COL_1 is red, COL_CORRECTPLACE black, COL_CORRECTCOLOUR white.
        assertEquals(1.0f, channel(grey, 6, 0), 0f)
        assertEquals(0.0f, channel(grey, 6, 1), 0f)
        assertEquals(0.0f, channel(grey, 16, 1), 0f)
        assertEquals(1.0f, channel(grey, 17, 2), 0f)
        // COL_FLASH and COL_HOLD are distinct from EMPTY.
        assertEquals(0.5f, channel(grey, 3, 0), 0f)
        assertEquals(0.5f, channel(grey, 4, 1), 0f)

        val white = Guess.colours(floatArrayOf(1.0f, 1.0f, 1.0f))
        for (c in 0..2) assertEquals(0.8333333f, channel(white, 0, c), 1e-6f)
        for (c in 0..2) assertEquals(0.5555556f, channel(white, 5, c), 1e-6f)

        val black = Guess.colours(floatArrayOf(0.0f, 0.0f, 0.0f))
        for (c in 0..2) assertEquals(0.0f, channel(black, 0, c), 0f)
        for (c in 0..2) assertEquals(0.0f, channel(black, 5, c), 0f)

        // A saturated channel is dimmed, not clipped.
        val red = Guess.colours(floatArrayOf(1.0f, 0.5f, 0.25f))
        assertEquals(1.0f / 1.2f, channel(red, 0, 0), 1e-6f)
        assertEquals(0.5f / 1.2f, channel(red, 0, 1), 1e-6f)
        assertEquals(0.25f / 1.2f, channel(red, 0, 2), 1e-6f)
    }

    @Test
    fun `compute size matches the C examples`() {
        val three = Guess.decodeParams("c3p2g1bm")
        val sizes = mapOf(
            32 to Size(178, 135),
            48 to Size(267, 202),
            63 to Size(350, 265),
            82 to Size(456, 345),
            100 to Size(555, 420),
        )
        for ((tileSize, expected) in sizes) {
            assertEquals(expected, Guess.computeSize(three, tileSize), "tile $tileSize")
        }
        assertEquals(Size(263, 420), Guess.computeSize(Guess.defaultParams(), 32))
        assertEquals(
            Size(312, 490),
            Guess.computeSize(
                Guess.decodeParams("c8p5g12Bm"),
                32,
            ),
        )
    }

    @Test
    fun `the drawstate has one blitter and frees it through its sink`() {
        val midend = Midend.newGame(Guess, "", "0")
        val sink = RecordingSink()
        val drawState = midend.ensureDrawState(tile, sink) as Guess.DrawState
        assertEquals(listOf("blitter_new 1 34 34"), sink.ops)
        assertEquals(tile, drawState.tileSize)
        assertEquals(10, drawState.guesses.size)
        assertTrue(drawState.guesses.all { row -> row.pegs.all { it == -1 } })
        assertEquals(6, drawState.colours.npegs)
        assertFalse(drawState.started)

        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
        midend.recordRedraw(sink)
        assertSame(drawState, midend.drawState)

        sink.ops.clear()
        midend.freeDrawState()
        assertEquals(listOf("blitter_free 1"), sink.ops)
    }

    @Test
    fun `keyboard edge cases follow the midend canonicalization`() {
        val midend = Midend.newGame(Guess, "c10p4g3bm", "12345")
        val ui = midend.ui as Guess.Ui
        // ':' is '0' + 10.
        midend.processKey(0, 0, ':'.code, tile)
        assertEquals(10, ui.currPegs[0])
        // '0' means 10 with ten colors.
        midend.processKey(0, 0, '0'.code, tile)
        assertEquals(10, ui.currPegs[1])
        // A keypad digit is not the plain digit.
        val before = Guess.encodeUi(ui)
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, '5'.code or Buttons.MOD_NUM_KEYPAD, tile),
        )
        assertEquals(before, Guess.encodeUi(ui))
        // A modified cursor key is not a cursor move in guess.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, Buttons.CURSOR_LEFT or Buttons.MOD_CTRL, tile),
        )
    }

    @Test
    fun `a game without a text format never calls textFormat`() {
        assertFalse(Guess.canFormatAsText)
        val game = object : Game by Guess {
            override fun textFormat(state: Game.State): String =
                error("guess has no text format")
        }
        val midend = Midend.newGame(game, "", "12345")
        assertEquals("", midend.textFormat())
    }

    @Test
    fun `the hidden solution does not leak into the public text`() {
        val midend = Midend.newGame(Guess, "", "0")
        // The solution is 3,3,4,1 for this seed.
        assertEquals(listOf(3, 3, 4, 1), (midend.state as Guess.State).solution.toList())
        assertEquals("", midend.statusText())
        assertEquals("", midend.textFormat())
        assertFalse(Guess.wantsStatusBar)

        val save = midend.serialise()
        assertEquals(null, save.value("AUXINFO"))
        assertEquals("0,0,0,0", save.value("UI"))
        val text = save.serialise()
        assertFalse(text.contains("3,3,4,1"))
        assertFalse(text.contains("3 3 4 1"))
        assertEquals("c6p4g10Bm:2bebff56", midend.id())

        // C reveals it only through the loss status.
        assertTrue(midend.solve())
        assertEquals(-1, midend.status())
        val revealed = midend.serialise()
        assertEquals(null, revealed.value("AUXINFO"))
        assertEquals("0,0,0,0", revealed.value("UI"))
    }

    @Test
    fun `a malformed saved UI still loads`() {
        val save = SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Guess.name),
                SaveRecord("PARAMS", "c3p2g1bm"),
                SaveRecord("CPARAMS", "c3p2g1bm"),
                SaveRecord("SEED", "12345"),
                SaveRecord("DESC", "3ea7"),
                SaveRecord("UI", "1junk,2"),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        )
        val midend = Midend.fromSave(Guess, save)
        val ui = midend.ui as Guess.Ui
        assertEquals("1,0", Guess.encodeUi(ui))
        // `c3p2g1bm` allows blanks, so one set peg is enough.
        assertTrue(ui.markable)
        assertEquals("c3p2g1bm:3ea7", midend.id())
    }

    @Test
    fun `a save without a UI record starts with a fresh UI`() {
        val save = SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Guess.name),
                SaveRecord("PARAMS", "c3p2g1bm"),
                SaveRecord("CPARAMS", "c3p2g1bm"),
                SaveRecord("DESC", "3ea7"),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        )
        val midend = Midend.fromSave(Guess, save)
        assertEquals("0,0", Guess.encodeUi(midend.ui))
    }

    @Test
    fun `a restart clears the draft and keeps the history`() {
        val midend = Midend.newGame(Guess, "", "0")
        for (key in listOf('1', '1', '1', '1')) {
            midend.processKey(0, 0, key.code, tile)
        }
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
        assertEquals(2, midend.stateCount)
        assertTrue(midend.restart())
        assertEquals(3, midend.stateCount)
        assertEquals("0,0,0,0", Guess.encodeUi(midend.ui))
        assertEquals(0, (midend.state as Guess.State).nextGo)
        assertTrue(midend.undo())
        assertEquals(1, (midend.state as Guess.State).nextGo)
    }

    @Test
    fun `the description is the only place that holds the answer`() {
        // The generator output is obfuscated; the raw solution bytes are
        // not a substring of the save file.
        val midend = Midend.newGame(Guess, "", "0")
        val solution = (midend.state as Guess.State).solution
        val text = midend.serialise().serialise()
        assertFalse(text.contains(solution.joinToString(",")))
        assertFalse(text.contains(solution.joinToString(" ")))
        // The id keeps the description verbatim, including hex case.
        val upper = Midend.fromGameId(Guess, "c3p2g1bm:E432")
        assertEquals("c3p2g1bm:E432", upper.id())
        assertEquals(
            (Guess.newState(Guess.decodeParams("c3p2g1bm"), "e432") as Guess.State)
                .solution.toList(),
            (upper.state as Guess.State).solution.toList(),
        )
    }

    @Test
    fun `a solution seed cannot be read from the id of another seed`() {
        assertFailsWith<IllegalStateException> {
            Midend.fromGameId(Guess, "c3p2g1bm:zzzz")
        }
    }

    private fun drag(midend: Midend, colour: Int, peg: Int) {
        val y = firstRowY
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(colourX, colourY(colour), Buttons.LEFT_BUTTON, tile),
        )
        midend.processKey(pegCentre(peg), y, Buttons.LEFT_DRAG, tile)
        midend.processKey(pegCentre(peg), y, Buttons.LEFT_RELEASE, tile)
    }

    private fun rightClick(midend: Midend, x: Int) {
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(x, firstRowY, Buttons.RIGHT_BUTTON, tile),
        )
        midend.processKey(x, firstRowY, Buttons.RIGHT_RELEASE, tile)
    }

    /**
     * Default `c6p4g10Bm` at tile 32: board 263 x 420, the colour bar
     * at x 16, the first colour centre at y 106 + 16, and the first
     * guess row at y 33.
     */
    private val colourX = 32

    private fun colourY(colour: Int): Int = 106 + (colour - 1) * 35 + 16

    private fun pegCentre(peg: Int): Int = 80 + peg * 35 + 16

    private val firstRowY = 33
    private val secondRowY = 68
    private val hintX = 230

    /** Records the drawing operations as compact text. */
    private class RecordingSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        val ops = ArrayList<String>()
        private var nextBlitterId = 1

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
            ops += "rect $x $y $width $height $colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line $x1 $y1 $x2 $y2 $colour"
        }

        override fun thickLine(x1: Int, y1: Int, x2: Int, y2: Int, thickness: Float, colour: Int) {}

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle $cx $cy $radius $fillColour $outlineColour"
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly ${points.joinToString(" ")} $fillColour $outlineColour"
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
            ops += "text $x $y $font $size $align $colour $text"
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

        override fun status(text: String) {
            ops += "status $text"
        }

        override fun blitterNew(width: Int, height: Int): Int {
            val id = nextBlitterId++
            ops += "blitter_new $id $width $height"
            return id
        }

        override fun blitterFree(id: Int) {
            ops += "blitter_free $id"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save $id $x $y"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load $id $x $y"
        }
    }
}
