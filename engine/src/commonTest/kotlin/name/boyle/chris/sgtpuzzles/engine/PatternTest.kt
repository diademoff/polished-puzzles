package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: pattern.
 *
 * Kotlin-only pins for the parameter quirks, the description grammar and
 * its messages, the small descriptions, the state shape, the completion
 * flag, the solver, the text format, the palette, the geometry, the
 * current-key-label table, and the generator attempt counts. The rest of
 * the game is compared with the oracle in the `:parity` module.
 *
 * The attempt counters have no C counterpart, so the measured values are
 * the fixture: a changed retry condition moves them even when the first
 * accepted description happens to match.
 */
class PatternTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24
    }

    private fun params(text: String): Pattern.Params =
        Pattern.decodeParams(text) as Pattern.Params

    private fun stateOf(paramsText: String, desc: String): Pattern.State =
        Pattern.newState(params(paramsText), desc) as Pattern.State

    private fun desc(paramsText: String, seed: String = SEED): String =
        Pattern.newDesc(params(paramsText), RandomState.fromSeed(seed)).text

    @Test
    fun `the presets and the default are the C table`() {
        assertEquals(Pattern.Params(15, 15), Pattern.defaultParams())
        assertEquals(
            listOf("10x10", "15x15", "20x20", "25x25", "30x30"),
            Pattern.presets().map { it.title },
        )
        assertEquals(
            listOf("10x10", "15x15", "20x20", "25x25", "30x30"),
            Pattern.presets().map { it.params },
        )
        assertEquals(5, Pattern.presets().size)
    }

    @Test
    fun `decode keeps the C quirks and encode ignores full`() {
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams("10"))
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams("10x10"))
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams("10X10"))
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams("+10x10"))
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams(" 10x10"))
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams("010x010"))
        assertEquals(Pattern.Params(10, 10), Pattern.decodeParams("10x10x7"))
        assertEquals(Pattern.Params(6, 6), Pattern.decodeParams("+6x5"))
        assertEquals(Pattern.Params(6, 6), Pattern.decodeParams("6X5"))
        assertEquals(Pattern.Params(6, 5), Pattern.decodeParams("06x05"))
        assertEquals(Pattern.Params(4, 4), Pattern.decodeParams("4x4x4"))
        assertEquals(Pattern.Params(4, 4), Pattern.decodeParams("4x4 "))
        assertEquals(Pattern.Params(0, 0), Pattern.decodeParams(""))
        // `x5` has a zero width but reads the height after the separator.
        assertEquals(Pattern.Params(0, 5), Pattern.decodeParams("x5"))
        assertEquals(Pattern.Params(10, 0), Pattern.decodeParams("10x"))

        for (p in listOf(Pattern.Params(15, 15), Pattern.Params(3, 7))) {
            assertEquals("${p.w}x${p.h}", Pattern.encodeParams(p, false))
            assertEquals("${p.w}x${p.h}", Pattern.encodeParams(p, true))
        }
    }

    @Test
    fun `validation keeps the C order and boundaries`() {
        for (p in listOf(
            Pattern.Params(0, 5),
            Pattern.Params(5, 0),
            Pattern.Params(0, 0),
            Pattern.Params(-3, 2),
        )) {
            assertEquals(
                "Width and height must both be greater than zero",
                Pattern.validateParams(p, true),
            )
        }
        assertEquals(
            "Grid must contain at least two squares",
            Pattern.validateParams(Pattern.Params(1, 1), true),
        )
        for (p in listOf(
            Pattern.Params(Int.MAX_VALUE, 1),
            Pattern.Params(1, Int.MAX_VALUE),
            Pattern.Params(46341, 46341),
            Pattern.Params(46342, 46340),
        )) {
            assertEquals(
                "Puzzle must not be unreasonably large",
                Pattern.validateParams(p, true),
            )
        }
        // The C validator accepts an area up to INT_MAX. Generation at
        // that size is not feasible; no test calls newDesc there.
        assertNull(Pattern.validateParams(Pattern.Params(46341, 46340), true))
        assertNull(Pattern.validateParams(Pattern.Params(46340, 46340), false))
        assertNull(Pattern.validateParams(Pattern.Params(2, 1), true))
        assertNull(Pattern.validateParams(Pattern.Params(1, 2), true))
        assertNull(Pattern.validateParams(Pattern.Params(30, 30), true))

        // atoi saturates, so a huge width becomes Int.MAX_VALUE and the
        // area check rejects it before any allocation.
        val huge = params("99999999999999999999x5")
        assertEquals(Int.MAX_VALUE, huge.w)
        assertEquals(5, huge.h)
        assertEquals(
            "Puzzle must not be unreasonably large",
            Pattern.validateParams(huge, true),
        )
    }

    @Test
    fun `validateDesc returns the C messages in order`() {
        val p = params("2x2")
        assertNull(Pattern.validateDesc(p, "1/1//2"))
        assertNull(Pattern.validateDesc(p, "1/1//2,aaAA"))
        assertNull(Pattern.validateDesc(p, "1/1//2,aaaa"))

        assertEquals(
            "too few row/column specifications",
            Pattern.validateDesc(p, ""),
        )
        assertEquals(
            "too many row/column specifications",
            Pattern.validateDesc(p, "1/1//2/"),
        )
        assertEquals(
            "all clues must be positive",
            Pattern.validateDesc(p, "0/1//2"),
        )
        // An empty element reads `atoi` of the separator, which is zero.
        assertEquals(
            "all clues must be positive",
            Pattern.validateDesc(p, "1..2/1//2"),
        )
        assertEquals(
            "at least one column contains more numbers than will fit",
            Pattern.validateDesc(p, "2.2/1//2"),
        )
        assertEquals(
            "at least one row contains more numbers than will fit",
            Pattern.validateDesc(p, "1/1/2.2/2"),
        )
        assertEquals(
            "unrecognised character in game specification",
            Pattern.validateDesc(p, "1/1//2x"),
        )

        assertEquals(
            "too little data in clue-squares section",
            Pattern.validateDesc(p, "1/1//2,"),
        )
        assertEquals(
            "too little data in clue-squares section",
            Pattern.validateDesc(p, "1/1//2,aaa"),
        )
        assertEquals(
            "too much data in clue-squares section",
            Pattern.validateDesc(p, "1/1//2,aaaaa"),
        )
        assertEquals(
            "too much data in clue-squares section",
            Pattern.validateDesc(p, "1/1//2,z"),
        )
        assertEquals(
            "unrecognised character in clue-squares section",
            Pattern.validateDesc(p, "1/1//2,a1"),
        )
    }

    @Test
    fun `the small descriptions are the measured C strings`() {
        assertEquals("1//1", desc("1x2"))
        assertEquals("/1/1", desc("2x1"))
        assertEquals("2///1/1/", desc("1x5"))
        assertEquals("//1/1//2", desc("5x1"))
        assertEquals("1/1//2", desc("2x2"))
        assertEquals("1/2/2/1/", desc("2x3"))
        assertEquals("2/1//2/1", desc("3x2"))
        assertEquals("2/1/1.1/1.1/2/1", desc("3x3"))
        assertEquals("3/1/2/2/2/1/1.2/2", desc("4x4"))
        assertEquals("1.1.1/1.3/3/2/1/2/1/3/3/2.2", desc("5x5"))
    }

    @Test
    fun `the preset attempt counts are pinned at the shared seed`() {
        val expected = mapOf(
            "10x10" to 4,
            "15x15" to 1,
            "20x20" to 2,
            "25x25" to 6,
            "30x30" to 13,
            "1x2" to 1,
            "2x1" to 1,
            "1x5" to 1,
            "5x1" to 1,
            "2x2" to 1,
            "2x3" to 2,
            "3x2" to 4,
            "3x3" to 150,
            "4x4" to 8,
            "5x5" to 5,
        )
        for ((paramsText, attempts) in expected) {
            val generation = Pattern.newDescWithAttempts(
                params(paramsText),
                RandomState.fromSeed(SEED),
            )
            assertEquals(attempts, generation.attempts, "$paramsText attempts")
            assertTrue(generation.desc.isNotEmpty(), "$paramsText desc")
            val p = params(paramsText)
            assertEquals(
                p.w * p.h + 1,
                generation.auxInfo.length,
                "$paramsText aux length",
            )
        }

        // A retry is data-dependent: seed `0` is the first seed above
        // one attempt for these small boards.
        for ((paramsText, attempts) in listOf(
            "2x3" to 4,
            "3x2" to 8,
            "3x3" to 96,
            "4x4" to 10,
            "5x5" to 11,
        )) {
            val generation = Pattern.newDescWithAttempts(
                params(paramsText),
                RandomState.fromSeed("0"),
            )
            assertEquals(attempts, generation.attempts, "$paramsText seed 0")
        }
    }

    @Test
    fun `the suffix fills and locks the clue cells`() {
        val state = stateOf("2x2", "1/1//2,aaAA")
        assertEquals(Pattern.GRID_EMPTY, state.grid[0])
        assertEquals(Pattern.GRID_EMPTY, state.grid[1])
        assertEquals(Pattern.GRID_FULL, state.grid[2])
        assertEquals(Pattern.GRID_FULL, state.grid[3])
        assertTrue(state.common.immutable.all { it })
        assertFalse(state.completed)
        assertFalse(state.cheated)
        assertEquals(0, Pattern.status(state))

        // The clue cells already match, but new_game does not run the
        // completion test. Any accepted move does, even a zero-size one.
        val won = Pattern.executeMove(state, "F0,0,0,0") as Pattern.State
        assertTrue(won.completed)
        assertFalse(won.cheated)
        assertEquals(1, Pattern.status(won))
        assertTrue(won.grid.contentEquals(state.grid))
    }

    @Test
    fun `the state copy is deep and shares the clue data`() {
        val state = stateOf("2x2", "1/1//2,aaAA")
        val copy = state.copy() as Pattern.State
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())
        assertSame(state.common, copy.common)
        assertFalse(state.grid === copy.grid)

        copy.grid[0] = Pattern.GRID_FULL
        assertFalse(state == copy)
        assertEquals(Pattern.GRID_EMPTY, state.grid[0])
    }

    @Test
    fun `the solver answer equals the generated aux`() {
        for (paramsText in listOf("10x10", "15x15", "20x20", "25x25", "30x30")) {
            val midend = Midend.newGame(Pattern, paramsText, SEED)
            val state = midend.state as Pattern.State
            val aux = requireNotNull(midend.auxInfo)
            val p = params(paramsText)
            assertEquals(p.w * p.h + 1, aux.length)
            assertEquals("S", aux.substring(0, 1))
            assertEquals(aux, Pattern.solve(state, null), "$paramsText aux from the solver")
            assertEquals(aux, Pattern.solve(state, aux), "$paramsText aux short circuit")
            val solved = Pattern.executeMove(state, aux) as Pattern.State
            assertEquals(1, Pattern.status(solved))
            assertTrue(solved.cheated)
        }
    }

    @Test
    fun `the hand-written hard board has no solver answer`() {
        // The 4x4 example in the C comment cannot be finished one line at
        // a time, so `solve_game` returns the C error. The seam has no
        // error channel, so solve returns null.
        val state = stateOf("4x4", "2/1/2/1/1.1/2/1/1")
        assertNull(Pattern.solve(state, null))
        assertEquals(
            "Solving algorithm cannot complete this puzzle",
            Pattern.solveGame(state, null).error,
        )
        assertEquals(0, Pattern.status(state))
        assertEquals(0, Pattern.status(state.copy() as Pattern.State))
    }

    @Test
    fun `the text format is the C rectangle`() {
        // The generated 4x4 at the shared seed, measured against C.
        assertEquals(
            "     3  1  2  2 \n" +
                "   +--+--+--+--+\n" +
                "  2|  |  |  |  |\n" +
                "   +--+--+--+--+\n" +
                "  1|  |  |  |  |\n" +
                "   +--+--+--+--+\n" +
                "1 2|  |  |  |  |\n" +
                "   +--+--+--+--+\n" +
                "  2|  |  |  |  |\n" +
                "   +--+--+--+--+\n",
            Pattern.textFormat(Midend.newGame(Pattern, "4x4", SEED).state),
        )

        // The 4x4 hard board, with a two-clue row.
        assertEquals(
            "     2  1  2  1 \n" +
                "   +--+--+--+--+\n" +
                "1 1|  |  |  |  |\n" +
                "   +--+--+--+--+\n" +
                "  2|  |  |  |  |\n" +
                "   +--+--+--+--+\n" +
                "  1|  |  |  |  |\n" +
                "   +--+--+--+--+\n" +
                "  1|  |  |  |  |\n" +
                "   +--+--+--+--+\n",
            Pattern.textFormat(stateOf("4x4", "2/1/2/1/1.1/2/1/1")),
        )

        // A two-digit column clue widens the cell to three characters.
        val twelve = Pattern.textFormat(
            Midend.fromGameId(Pattern, "1x12:12/" + (0 until 12).joinToString("/") { "1" }).state,
        )
        assertTrue(twelve.startsWith("  12 \n +--+\n1|  |\n"))
        // A three-digit clue widens it to four.
        val hundred = Pattern.textFormat(
            Midend.fromGameId(
                Pattern,
                "1x100:100/" + (0 until 100).joinToString("/") { "1" },
            ).state,
        )
        assertTrue(hundred.startsWith("  100 \n +---+\n1|   |\n"))
    }

    @Test
    fun `the status is completed or open and the bar is off`() {
        val state = stateOf("2x2", "1/1//2")
        assertEquals(0, Pattern.status(state))
        assertFalse(Pattern.wantsStatusBar)
        assertEquals("", Pattern.statusText(state, Pattern.Ui()))
        assertTrue(Pattern.canSolve)
        assertTrue(Pattern.canFormatAsText)
    }

    @Test
    fun `changedState reports an honest completion only`() {
        val ui = Pattern.Ui()
        val start = stateOf("2x2", "1/1//2")
        // Row 1 is the two filled cells; row 0 must be marked empty so
        // no cell stays unknown.
        val filled = Pattern.executeMove(start, "F0,1,2,1") as Pattern.State
        val won = Pattern.executeMove(filled, "E0,0,2,1") as Pattern.State
        assertTrue(won.completed)
        assertFalse(won.cheated)
        assertTrue(Pattern.changedState(ui, start, won))
        assertFalse(Pattern.changedState(ui, won, won), "already complete")
        assertFalse(Pattern.changedState(ui, null, won), "no old state")

        // A solve sets completed and cheated, and raises no event.
        val solved = Pattern.executeMove(start, "S0011") as Pattern.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertFalse(Pattern.changedState(ui, start, solved))
    }

    @Test
    fun `the current key labels are the C table`() {
        val state = stateOf("2x2", "1/1//2")
        val ui = Pattern.Ui()
        ui.curVisible = true

        ui.curX = 0
        ui.curY = 0
        assertEquals("Black", Pattern.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("White", Pattern.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        val full = Pattern.executeMove(state, "F0,0,1,1") as Pattern.State
        assertEquals("White", Pattern.currentKeyLabel(ui, full, Buttons.CURSOR_SELECT))
        assertEquals("Grey", Pattern.currentKeyLabel(ui, full, Buttons.CURSOR_SELECT2))

        val empty = Pattern.executeMove(state, "E0,0,1,1") as Pattern.State
        assertEquals("Grey", Pattern.currentKeyLabel(ui, empty, Buttons.CURSOR_SELECT))
        assertEquals("Black", Pattern.currentKeyLabel(ui, empty, Buttons.CURSOR_SELECT2))

        ui.curVisible = false
        assertEquals("", Pattern.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.curVisible = true
        assertEquals("", Pattern.currentKeyLabel(ui, state, Buttons.LEFT_BUTTON))
        assertEquals("", Pattern.currentKeyLabel(ui, state, Buttons.CURSOR_UP))

        val outside = Pattern.Ui()
        outside.curVisible = true
        outside.curX = 99
        assertEquals("", Pattern.currentKeyLabel(outside, state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `requestKeys has no labels and the C arrow modes`() {
        val p = params("10x10")
        val normal = Pattern.requestKeys(p)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, normal.arrowMode)
        assertTrue(normal.labels.isEmpty())
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, Pattern.requestKeys(p, false).arrowMode)
        assertEquals(ArrowMode.ARROWS_ONLY, Pattern.requestKeys(p, true).arrowMode)
    }

    @Test
    fun `computeSize and the palette are the C values`() {
        for ((text, size) in listOf(
            "10x10" to Size(384, 384),
            "15x15" to Size(528, 528),
            "20x20" to Size(672, 672),
            "25x25" to Size(816, 816),
            "30x30" to Size(960, 960),
            "1x2" to Size(120, 144),
            "2x1" to Size(144, 120),
        )) {
            assertEquals(size, Pattern.computeSize(params(text), TILE), text)
        }

        val colours = Pattern.colours()
        assertEquals(Pattern.NCOLOURS * 3, colours.size)
        val expected = floatArrayOf(
            0.8f, 0.8f, 0.8f, // background
            1f, 1f, 1f, // empty
            0f, 0f, 0f, // full
            0f, 0f, 0f, // text
            0.5f, 0.5f, 0.5f, // unknown
            0.3f, 0.3f, 0.3f, // grid
            1f, 0.25f, 0.25f, // cursor
            1f, 0f, 0f, // error
            0.5f, 0.5f, 0.5f, // cursor guide
        )
        for (i in expected.indices) assertEquals(expected[i], colours[i], "colour $i")

        val shell = Pattern.colours(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(0.1f, shell[0])
        assertEquals(0.2f, shell[1])
        assertEquals(0.3f, shell[2])
    }

    @Test
    fun `the derived font size follows the clues`() {
        // 15x15 has a column clue of 10, so the small font is used.
        val fifteen = Midend.newGame(Pattern, "15x15", SEED).state as Pattern.State
        assertEquals(Pattern.FS_SMALL, fifteen.common.fontSize)
        // 10x10 has only one-digit column clues and few row clues.
        val ten = Midend.newGame(Pattern, "10x10", SEED).state as Pattern.State
        assertEquals(Pattern.FS_LARGE, ten.common.fontSize)
        // A row with many clues also selects the small font.
        val many = stateOf(
            "15x15",
            (0 until 15).joinToString("/") { "1" } + "/" +
                (0 until 15).joinToString("/") { "1.1.1.1.1" },
        )
        assertEquals(Pattern.FS_SMALL, many.common.fontSize)
    }
}
