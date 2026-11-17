package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: magnets.
 *
 * Kotlin-only pins for the parameter quirks, the description decoder,
 * the text format, the move grammar, the magnet and neutral cycles, the
 * clue toggle as a state move, the state copy, the current-key-label
 * table, `changed_state`, the solver with and without `AUXINFO`, and
 * the cost of the preset generators.
 *
 * The strip-clues `ret != -1` assertion has no reachable fixture here,
 * so the class comment in [Magnets] records the release rule the port
 * implements. The generation attempt counts are exposed through
 * [Magnets.newDescWithAttempts] and pinned at seed `12345`.
 */
class MagnetsTest {

    private companion object {
        const val TILE = 32
        const val SEED = "12345"

        const val GOLDEN_6X5DE =
            "221211,12231,302112,11313,LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB"
        const val GOLDEN_6X5DT =
            "222212,22322,312212,22223,LRLRLRTTTTLRBBBBTTTTTTBBBBBBLR"
        const val GOLDEN_6X5DTS =
            "..2..2,2..2.,3122.2,...23,LRLRLRTTTTLRBBBBTTTTTTBBBBBBLR"
        const val GOLDEN_3X3DE =
            "112,211,121,121,LRTLRB*LR"

        const val SOLVE_6X5DE =
            "S;-0,0;+0,1;-0,2;+0,3;-0,4;+1,0;.1,1;.1,2;.1,3;+1,4;" +
                ".2,0;-2,1;+2,2;.2,3;-2,4;.3,0;+3,1;-3,2;+3,3;.3,4;" +
                ".4,0;.4,1;+4,2;-4,3;.4,4;.5,0;.5,1;-5,2;+5,3;-5,4;"
        const val SOLVE_3X3DE =
            "S;+0,0;-0,1;-1,0;+1,1;-1,2;+2,0;-2,1;+2,2;"
    }

    private fun params(text: String): Magnets.Params =
        Magnets.decodeParams(text) as Magnets.Params

    private fun stateOf(paramsText: String, desc: String): Magnets.State =
        Magnets.newState(params(paramsText), desc) as Magnets.State

    @Test
    fun `the presets and the default are the C table`() {
        val presets = Magnets.presets()
        assertEquals(8, presets.size)
        assertEquals(
            listOf(
                "6x5 Easy", "6x5 Tricky", "6x5 Tricky, strip clues",
                "8x7 Easy", "8x7 Tricky", "8x7 Tricky, strip clues",
                "10x9 Tricky", "10x9 Tricky, strip clues",
            ),
            presets.map { it.title },
        )
        assertEquals(
            listOf(
                "6x5de", "6x5dt", "6x5dtS", "8x7de",
                "8x7dt", "8x7dtS", "10x9dt", "10x9dtS",
            ),
            presets.map { it.params },
        )
        assertEquals(
            Magnets.Params(6, 5, Magnets.DIFF_TRICKY, true),
            Magnets.defaultParams(),
        )
    }

    @Test
    fun `decode and encode keep the C quirks`() {
        fun p(text: String): String {
            val decoded = params(text)
            return "${decoded.w}x${decoded.h}d${"et"[decoded.diff]}" +
                if (decoded.stripClues) "S" else ""
        }

        assertEquals("6x6de", p("6"))
        assertEquals("6x5de", p("6x5"))
        assertEquals("6x5de", p("6x5d"))
        assertEquals("6x5de", p("6x5dz"))
        assertEquals("6x5dtS", p("6x5dtS"))
        assertEquals("6x5de", p("6x5dS"))
        assertEquals("6x5deS", p("6x5S"))
        assertEquals("6x5dtS", p("6x5dtSs"))
        assertEquals("6x5de", p("6x5X5"))
        assertEquals("6x6de", p("+6x5"))
        assertEquals("6x5de", p("06x05"))
        assertEquals("6x5de", p("6x5 "))

        val short = Magnets.Params(10, 9, 1, true)
        assertEquals("10x9", Magnets.encodeParams(short, false))
        assertEquals("10x9dtS", Magnets.encodeParams(short, true))
    }

    @Test
    fun `validation keeps the C order and messages`() {
        assertEquals(
            "Width must be at least two",
            Magnets.validateParams(Magnets.Params(1, 2, 0, false), true),
        )
        assertEquals(
            "Height must be at least two",
            Magnets.validateParams(Magnets.Params(2, 1, 0, false), true),
        )
        assertEquals(
            "Either width or height must be at least three",
            Magnets.validateParams(Magnets.Params(2, 2, 0, false), true),
        )
        assertEquals(
            "Either width or height must be at least five for Tricky",
            Magnets.validateParams(Magnets.Params(4, 4, 1, false), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Magnets.validateParams(Magnets.Params(5, 5, 2, false), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Magnets.validateParams(Magnets.Params(65536, 65536, 0, false), true),
        )
        assertNull(Magnets.validateParams(Magnets.Params(5, 4, 1, false), true))
        assertNull(Magnets.validateParams(Magnets.Params(4, 5, 1, false), true))
        assertNull(Magnets.validateParams(Magnets.Params(46340, 46340, 0, false), true))
    }

    @Test
    fun `the description decoder rejects the C messages`() {
        val p = params("6x5de")
        assertEquals(
            "Game description too short",
            Magnets.validateDesc(p, "221211"),
        )
        assertEquals(
            "Invalid character in game description",
            Magnets.validateDesc(p, "?01211,12231,302112,11313," + "L".repeat(30)),
        )
        assertEquals(
            "Column counts inconsistent",
            Magnets.validateDesc(p, "921211,12231,302112,11313," + "LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB"),
        )
        assertEquals(
            "Row counts inconsistent",
            Magnets.validateDesc(p, "221211,92231,302112,11313," + "LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB"),
        )
        assertEquals(
            "Domino descriptions inconsistent",
            Magnets.validateDesc(p, "221211,12231,302112,11313," + "LRLRLRTTLRLRBBLRLRTLRLRTBLRLRT"),
        )
        assertNull(Magnets.validateDesc(p, GOLDEN_6X5DE))

        // Trailing text is ignored, and commas inside the domino part
        // are skipped.
        assertNull(Magnets.validateDesc(p, "$GOLDEN_6X5DE,XYZ"))
        assertNull(
            Magnets.validateDesc(
                p,
                "221211,12231,302112,11313," + "LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB".replace("L", ",L"),
            ),
        )
    }

    @Test
    fun `an impossible board has the C solve error`() {
        // Every row is two horizontal dominoes. Four positive magnets in
        // column 0 force every cell of column 1 to be negative, but the
        // bottom count asks for three negatives there. The parse is
        // valid; only the solver sees the contradiction
        // (`magnets.c:1493-1498`).
        val state = stateOf("4x4de", "4112,2222,0332,2222,LRLRLRLRLRLRLRLR")
        assertNull(Magnets.solve(state, null))
        assertEquals("Puzzle is impossible.", Magnets.solveGame(state, null).error)
    }

    @Test
    fun `the text format is the C rectangle`() {
        val text = Magnets.textFormat(stateOf("6x5de", GOLDEN_6X5DE))
        assertEquals(
            "+ 2 2 1 2 1 1\n" +
                " +-----------+\n" +
                "1|   |   |   |1\n" +
                " |-+-+-+-+-+-|\n" +
                "2| | |   |   |1\n" +
                " | + +-+-+-+-|\n" +
                "2| | |   |   |3\n" +
                " |-+-+-+-+-+-|\n" +
                "3| |   |   | |1\n" +
                " | +-+-+-+-+ |\n" +
                "1| |   |   | |3\n" +
                " +-----------+\n" +
                "  3 0 2 1 1 2 -\n",
            text,
        )

        // A strip-clues board writes `.` for a missing clue.
        val stripped = Magnets.textFormat(stateOf("6x5dtS", GOLDEN_6X5DTS))
        assertTrue(stripped.contains("+ . . 2 . . 2\n"))

        // A singleton is `#`, a set neutral `*`, and an empty cell a
        // space. The 3x3 board has one singleton at (0,2).
        val three = stateOf("3x3de", GOLDEN_3X3DE)
        assertTrue(Magnets.textFormat(three).contains("#"))

        val neutral = Magnets.executeMove(three, ".0,0") as Magnets.State
        assertTrue(Magnets.textFormat(neutral).contains("*"))
    }

    @Test
    fun `the move grammar and the cycles match the C state`() {
        val state = stateOf("6x5dt", GOLDEN_6X5DT)

        val positive = Magnets.executeMove(state, "+0,0") as Magnets.State
        assertEquals(Magnets.POSITIVE, positive.grid[0])
        assertEquals(Magnets.NEGATIVE, positive.grid[1])
        assertEquals(Magnets.GS_SET, positive.flags[0] and Magnets.GS_SET)

        val negative = Magnets.executeMove(state, "-0,0") as Magnets.State
        assertEquals(Magnets.NEGATIVE, negative.grid[0])
        assertEquals(Magnets.POSITIVE, negative.grid[1])

        val neutral = Magnets.executeMove(state, ".0,0") as Magnets.State
        assertEquals(Magnets.NEUTRAL, neutral.grid[0])
        assertEquals(Magnets.NEUTRAL, neutral.grid[1])
        assertEquals(Magnets.GS_SET, neutral.flags[1] and Magnets.GS_SET)

        val notNeutral = Magnets.executeMove(state, "?0,0") as Magnets.State
        assertEquals(Magnets.NEUTRAL, notNeutral.grid[0])
        assertEquals(Magnets.GS_NOTNEUTRAL, notNeutral.flags[0] and Magnets.GS_NOTNEUTRAL)
        assertEquals(Magnets.GS_NOTNEUTRAL, notNeutral.flags[1] and Magnets.GS_NOTNEUTRAL)

        val cleared = Magnets.executeMove(neutral, " 0,0") as Magnets.State
        assertEquals(0, cleared.flags[0])
        assertEquals(0, cleared.flags[1])

        // The last write wins, and a trailing separator is accepted.
        val both = Magnets.executeMove(state, "+0,0;.0,0;") as Magnets.State
        assertEquals(Magnets.NEUTRAL, both.grid[0])

        // A bad entry rejects the whole move.
        assertNull(Magnets.executeMove(state, "+0,0junk"))
        assertNull(Magnets.executeMove(state, ";+0,0"))
        assertNull(Magnets.executeMove(state, "+6,0"))
        assertNull(Magnets.executeMove(state, "D-1,-1"))
        assertNull(Magnets.executeMove(state, "D6,5"))
        assertNull(Magnets.executeMove(state, "S;;"))
        assertNull(Magnets.executeMove(state, "S; "))
        assertNull(Magnets.executeMove(state, ""))
        assertNull(Magnets.executeMove(state, "+0"))

        // `%d` accepts white space, a sign, and leading zeros.
        val spaced = Magnets.executeMove(state, "+ 0, 0") as Magnets.State
        assertEquals(Magnets.POSITIVE, spaced.grid[0])
        val zeros = Magnets.executeMove(state, "+00,00") as Magnets.State
        assertEquals(Magnets.POSITIVE, zeros.grid[0])

        // `S` sets `solved` and changes no cell.
        val solved = Magnets.executeMove(state, "S") as Magnets.State
        assertTrue(solved.solved)
        assertTrue(state.grid.contentEquals(solved.grid))
        assertEquals(0, Magnets.status(solved))
    }

    @Test
    fun `a singleton rejects every move on both ends`() {
        val state = stateOf("3x3de", GOLDEN_3X3DE)
        val singleton = 2 * 3 + 0
        assertEquals(singleton, state.common.dominoes[singleton])
        assertNull(Magnets.executeMove(state, "+0,2"))
        assertNull(Magnets.executeMove(state, "-0,2"))
        assertNull(Magnets.executeMove(state, ".0,2"))
        assertNull(Magnets.executeMove(state, "?0,2"))
        assertNull(Magnets.executeMove(state, " 0,2"))
    }

    @Test
    fun `the clue toggle is a state move that keeps the grid`() {
        val state = stateOf("6x5dt", GOLDEN_6X5DT)
        val toggled = Magnets.executeMove(state, "D0,-1") as Magnets.State
        assertTrue(toggled.countsDone[0])
        assertTrue(state.grid.contentEquals(toggled.grid))
        assertEquals(state.flags.toList(), toggled.flags.toList())
        val again = Magnets.executeMove(toggled, "D0,-1") as Magnets.State
        assertFalse(again.countsDone[0])

        // The four clue positions map to the C indices.
        assertTrue((Magnets.executeMove(state, "D6,0") as Magnets.State).countsDone[6])
        assertTrue((Magnets.executeMove(state, "D0,5") as Magnets.State).countsDone[16])
        assertTrue((Magnets.executeMove(state, "D-1,0") as Magnets.State).countsDone[21])
    }

    @Test
    fun `a state copy is deep and shares the counts`() {
        val state = stateOf("6x5dt", GOLDEN_6X5DT)
        val toggled = Magnets.executeMove(state, "D0,-1;+0,0") as Magnets.State
        val copy = toggled.copy() as Magnets.State

        assertSame(toggled.common, copy.common)
        assertFalse(toggled.grid === copy.grid)
        assertFalse(toggled.flags === copy.flags)
        assertFalse(toggled.countsDone === copy.countsDone)
        assertEquals(toggled, copy)
        assertEquals(toggled.hashCode(), copy.hashCode())

        // A move from the copy does not touch the original arrays.
        val moved = Magnets.executeMove(copy, "-0,0") as Magnets.State
        assertEquals(Magnets.POSITIVE, toggled.grid[0])
        assertEquals(Magnets.NEGATIVE, moved.grid[0])
        assertTrue(toggled.countsDone[0])
    }

    @Test
    fun `the solver matches the C answers`() {
        val state = stateOf("6x5de", GOLDEN_6X5DE)
        assertEquals(SOLVE_6X5DE, Magnets.solve(state, null))

        val three = stateOf("3x3de", GOLDEN_3X3DE)
        assertEquals(SOLVE_3X3DE, Magnets.solve(three, null))

        // A user move that matches the answer shortens the diff.
        val matched = Magnets.executeMove(state, "+2,2") as Magnets.State
        val shortened = Magnets.solve(matched, null)!!
        assertTrue(shortened.length < SOLVE_6X5DE.length)
        assertFalse(shortened.contains("+2,2;"))

        // A wrong magnet is overwritten on both ends.
        val wrong = Magnets.executeMove(state, "+0,0") as Magnets.State
        assertEquals(SOLVE_6X5DE, Magnets.solve(wrong, null))

        // The aux path wins over a wrong board and is still diffed
        // against the current state.
        val aux = auxOf("6x5de")
        assertEquals(SOLVE_6X5DE, Magnets.solve(state, aux))
        assertEquals(aux.length, state.wh)
        assertFalse(Magnets.solve(state, "short")!!.isEmpty())
    }

    @Test
    fun `the solve fallback uses the decoded initial state`() {
        // A state loaded from a description has no AUXINFO. A wrong
        // neutral domino makes the first attempt fail; the fallback
        // solves the decoded initial state.
        val initial = stateOf("6x5de", GOLDEN_6X5DE)
        val wrong = Magnets.executeMove(initial, ".0,0") as Magnets.State
        val move = Magnets.solve(wrong, null)
        assertEquals(SOLVE_6X5DE, move)

        // The same answer on a save loaded through the midend.
        val save = "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\nGAME    :7:Magnets\nPARAMS  :5:6x5de\n" +
            "CPARAMS :5:6x5de\nDESC    :56:$GOLDEN_6X5DE\n" +
            "NSTATES :1:2\nSTATEPOS:1:2\nMOVE    :4:.0,0\n"
        val midend = Midend.fromSave(Magnets, SgtpSave.parse(save))
        assertTrue(midend.solve())
        assertEquals(SOLVE_6X5DE, midend.serialise().values("SOLVE").single())
    }

    @Test
    fun `a completion hides the cursor and reports once`() {
        val state = stateOf("6x5de", GOLDEN_6X5DE)
        val ui = Magnets.Ui()
        ui.curVisible = true

        // Apply the C answer as a real move.
        val answer = SOLVE_6X5DE.removePrefix("S;")
        val done = Magnets.executeMove(state, answer) as Magnets.State
        assertEquals(1, Magnets.status(done))
        assertTrue(Magnets.changedState(ui, state, done))
        assertFalse(ui.curVisible)

        // A solved state (the `S` flag) and an already completed state
        // raise no event.
        val solved = Magnets.executeMove(done, "S") as Magnets.State
        assertTrue(solved.cheated)
        assertFalse(Magnets.changedState(ui, state, solved))
        assertFalse(Magnets.changedState(ui, done, done))
    }

    @Test
    fun `the current key labels are the C table`() {
        val state = stateOf("6x5dt", GOLDEN_6X5DT)
        val ui = Magnets.Ui()
        ui.curVisible = true
        assertEquals("+", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("X", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.curVisible = false
        assertEquals("", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Magnets.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A positive cell.
        val positive = Magnets.executeMove(state, "+0,0") as Magnets.State
        ui.curVisible = true
        assertEquals("-", Magnets.currentKeyLabel(ui, positive, Buttons.CURSOR_SELECT))
        assertEquals("", Magnets.currentKeyLabel(ui, positive, Buttons.CURSOR_SELECT2))

        // A set neutral, then a not-neutral cell.
        val neutral = Magnets.executeMove(state, ".0,0") as Magnets.State
        assertEquals("", Magnets.currentKeyLabel(ui, neutral, Buttons.CURSOR_SELECT))
        assertEquals("?", Magnets.currentKeyLabel(ui, neutral, Buttons.CURSOR_SELECT2))
        val notNeutral = Magnets.executeMove(state, "?0,0") as Magnets.State
        assertEquals("Clear", Magnets.currentKeyLabel(ui, notNeutral, Buttons.CURSOR_SELECT2))

        // The singleton label `?` is not a move.
        val three = stateOf("3x3de", GOLDEN_3X3DE)
        val singletonUi = Magnets.Ui()
        singletonUi.curX = 0
        singletonUi.curY = 2
        singletonUi.curVisible = true
        assertEquals("", Magnets.currentKeyLabel(singletonUi, three, Buttons.CURSOR_SELECT))
        assertEquals("?", Magnets.currentKeyLabel(singletonUi, three, Buttons.CURSOR_SELECT2))

        // An out-of-grid cursor is a defined empty label.
        val outside = Magnets.Ui()
        outside.curX = 99
        outside.curVisible = true
        assertEquals("", Magnets.currentKeyLabel(outside, state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `requestKeys has no labels`() {
        val p = params("6x5dt")
        val normal = Magnets.requestKeys(p)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, normal.arrowMode)
        assertTrue(normal.labels.isEmpty())
        val full = Magnets.requestKeys(p, true)
        assertEquals(ArrowMode.ARROWS_ONLY, full.arrowMode)
        assertTrue(full.labels.isEmpty())
    }

    @Test
    fun `computeSize truncates the border separately`() {
        val p = params("6x5dt")
        assertEquals(Size(396, 348), Magnets.computeSize(p, 48))
        assertEquals(Size(518, 455), Magnets.computeSize(p, 63))
        assertEquals(Size(264, 232), Magnets.computeSize(params("6x5dt"), 32))
    }

    @Test
    fun `the preset attempt counts are pinned at the shared seed`() {
        // The counters have no C counterpart, so the measured value is
        // the fixture: a changed retry condition moves it even when the
        // first accepted description happens to match.
        val expected = mapOf(
            "6x5de" to 1,
            "6x5dt" to 37,
            "6x5dtS" to 37,
            "8x7de" to 1,
            "8x7dt" to 22,
            "8x7dtS" to 22,
            "10x9dt" to 16,
            "10x9dtS" to 16,
        )
        assertEquals(expected.keys, Magnets.presets().map { it.params }.toSet())
        for ((paramsText, attempts) in expected) {
            val generation = Magnets.newDescWithAttempts(
                params(paramsText),
                RandomState.fromSeed(SEED),
            )
            assertEquals(attempts, generation.attempts, "$paramsText attempts")
            assertEquals(0, generation.layDominoRetries, "$paramsText retries")
            assertTrue(generation.desc.isNotEmpty(), "$paramsText desc")
        }
    }

    @Test
    fun `the params and move scanners saturate`() {
        // atoi saturates at INT_MAX, so the area check then rejects it.
        val huge = "99999999999999999999"
        val decoded = params("${huge}x5")
        assertEquals(Int.MAX_VALUE, decoded.w)
        assertEquals(5, decoded.h)
        assertEquals(
            "Width times height must not be unreasonably large",
            Magnets.validateParams(decoded, true),
        )

        // The move scanner keeps the C convention: a value outside the
        // long range saturates there, and the assignment to int keeps
        // the low 32 bits. The positive limit is -1, a valid clue
        // column, and the negative limit is 0, which is not a clue.
        val state = stateOf("6x5dt", GOLDEN_6X5DT)
        val toggled = Magnets.executeMove(state, "D$huge,0") as Magnets.State
        assertTrue(toggled.countsDone[21])
        assertNull(Magnets.executeMove(state, "D-$huge,0"))
    }

    @Test
    fun `the strip board keeps missing clues and solves`() {
        val state = stateOf("6x5dtS", GOLDEN_6X5DTS)
        assertTrue(Magnets.textFormat(state).contains("."))
        val move = Magnets.solve(state, auxOf("6x5dtS"))
        assertEquals(152, move!!.length)
        val done = Magnets.executeMove(state, move) as Magnets.State
        assertEquals(1, Magnets.status(done))
    }

    /** The generated `AUXINFO` for one preset at the shared seed. */
    private fun auxOf(paramsText: String): String =
        Magnets.newDescWithAttempts(
            params(paramsText),
            RandomState.fromSeed(SEED),
        ).auxInfo
}
