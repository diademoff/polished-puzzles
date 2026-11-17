package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: strict. Game: galaxies, engine side.
 *
 * The parameter grammar, the validation order, the description parser,
 * the text form, the completion check, the key labels, and the cursor
 * rectangles. The oracle compares the descriptions, the frames, and the
 * pointer input; these tests pin the internal rules.
 */
class GalaxiesTest {

    private val presets = Galaxies.presets().map { it.params }

    @Test
    fun `the preset table and defaults are the C game`() {
        assertEquals(
            listOf("7x7dn", "7x7du", "10x10dn", "10x10du", "15x15dn", "15x15du"),
            presets,
        )
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.defaultParams())
        assertEquals("7x7dn", Galaxies.encodeParams(Galaxies.defaultParams(), true))
        assertEquals("7x7", Galaxies.encodeParams(Galaxies.defaultParams(), false))
        assertEquals("7x7 Normal", Galaxies.presets().first().title)
        assertEquals("7x7 Unreasonable", Galaxies.presets()[1].title)
    }

    @Test
    fun `the parameter grammar matches the C quirks`() {
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7dn"))
        assertEquals(Galaxies.Params(7, 7, 1), Galaxies.decodeParams("7x7du"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7d"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7dz"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7dnx"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("007x7"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("+7x+7"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7X7"))
        assertEquals(Galaxies.Params(0, 0, 0), Galaxies.decodeParams("zzz"))
        // A `d` consumes one character even when it is not a letter.
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7 ddu"))
        // A huge number saturates instead of wrapping.
        assertEquals(Int.MAX_VALUE, (Galaxies.decodeParams("999999999999") as Galaxies.Params).w)
    }

    @Test
    fun `validation follows the C order and the area bound`() {
        fun check(w: Int, h: Int, diff: Int = 0): String? =
            Galaxies.validateParams(Galaxies.Params(w, h, diff), true)

        assertNull(check(3, 3))
        assertNull(check(7, 7))
        assertNull(check(23169, 23169))
        assertEquals(
            "Width and height must both be at least 3",
            check(2, 3),
        )
        assertEquals(
            "Width and height must both be at least 3",
            check(7, 2),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            check(23170, 23170),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            check(46340, 46340),
        )
        // C asserts on a difficulty outside the decoded range; the port
        // returns a message (D-023).
        assertEquals("Unknown difficulty level", check(7, 7, -1))
        assertEquals("Unknown difficulty level", check(7, 7, 2))
        assertNull(check(7, 7, 1))
    }

    @Test
    fun `the encoder defines an out-of-range difficulty`() {
        // C writes the NUL at index 2, so the string ends after `d`.
        assertEquals("7x7d", Galaxies.encodeParams(Galaxies.Params(7, 7, 2), true))
        assertEquals("7x7d", Galaxies.encodeParams(Galaxies.Params(7, 7, 3), true))
        assertEquals("7x7d", Galaxies.encodeParams(Galaxies.Params(7, 7, -1), true))
        assertEquals("7x7", Galaxies.encodeParams(Galaxies.Params(7, 7, 3), false))
    }

    @Test
    fun `the description grammar matches the C parser`() {
        val p = Galaxies.decodeParams("7x7dn")
        assertEquals(
            "Invalid character in game description",
            Galaxies.validateDesc(p, "!"),
        )
        assertEquals(
            "Dots too close together",
            Galaxies.validateDesc(p, "aa"),
        )
        assertNull(Galaxies.validateDesc(p, "cjrbhizuisfvf"))
        assertNull(Galaxies.validateDesc(p, ""))
        assertEquals(
            "Too much data to fit in grid",
            Galaxies.validateDesc(p, "yyyyyyyy"),
        )

        assertFailsWith<IllegalArgumentException> { Galaxies.newState(p, "!") }
        assertFailsWith<IllegalArgumentException> { Galaxies.newState(p, "aa") }

        val tiny = Galaxies.newState(Galaxies.decodeParams("3x3dn"), "c") as Galaxies.State
        assertEquals(1, tiny.dotCount)
        assertEquals(listOf(3 to 1), tiny.dotPositions())
    }

    @Test
    fun `the text form shows dots associations and edges`() {
        val midend = Midend.newGame(Galaxies, "3x3dn", "1")
        val state = midend.state as Galaxies.State
        val text = Galaxies.textFormat(state)
        assertTrue(text.contains('o'), "dots")
        assertFalse(text.contains('?'), "no query marks")
        // Every row has `sx` characters plus a newline; the frame is the
        // 7x7 space array of a 3x3 board.
        val lines = text.lines().dropLast(1)
        assertEquals(7, lines.size)
        assertEquals(8, text.lines().size)
        for (line in lines) assertEquals(7, line.length)
        assertTrue(lines.any { it.contains('+') }, "vertices")
        assertTrue(lines.first().all { it == '+' || it == '-' }, "boundary edges")

        val assocToken = midend.auxInfo!!.split(';').first { it.startsWith("a") }
        val associated = Galaxies.executeMove(state, assocToken) as Galaxies.State
        val moved = Galaxies.textFormat(associated)
        assertTrue(moved.contains('W') || moved.contains('B'), "association mark")
    }

    @Test
    fun `a solve mark completes the board and sets the solve flag`() {
        val midend = Midend.newGame(Galaxies, "7x7dn", "12345")
        val state = midend.state as Galaxies.State
        val aux = midend.auxInfo!!
        val solved = Galaxies.executeMove(state, aux) as Galaxies.State
        assertTrue(solved.completed, "completed")
        assertTrue(solved.usedSolve, "used solve")
        assertEquals(1, Galaxies.status(solved))
        // The answer only sets edges and associations; the text form is
        // a complete board with no query marks.
        assertFalse(Galaxies.textFormat(solved).contains('?'))

        // An empty move is a copy, not a rejection.
        val copy = Galaxies.executeMove(state, "") as Galaxies.State
        assertEquals(state, copy)
        assertFalse(copy.completed)
    }

    @Test
    fun `currentKeyLabel names the cursor action`() {
        val midend = Midend.newGame(Galaxies, "7x7dn", "12345")
        val ui = midend.ui as Galaxies.Ui
        val state = midend.state as Galaxies.State
        assertEquals("", Galaxies.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        val dot = state.dotPositions().first()
        ui.curVisible = true
        ui.curX = dot.first
        ui.curY = dot.second
        assertEquals("New arrow", Galaxies.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        // An associated tile moves its arrow.
        val assocToken = midend.auxInfo!!.split(';').first { it.startsWith("a") }
        val associated = Galaxies.executeMove(state, assocToken) as Galaxies.State
        val tile = firstAssociated(associated)
        ui.curX = tile.first
        ui.curY = tile.second
        assertEquals("Move arrow", Galaxies.currentKeyLabel(ui, associated, Buttons.CURSOR_SELECT))

        // A legal empty edge is `Edge`; a set edge is `Clear`.
        val edge = firstLegalEdge(state)
        ui.curX = edge.first
        ui.curY = edge.second
        assertEquals("Edge", Galaxies.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        val withEdge = Galaxies.executeMove(state, "E${edge.first},${edge.second}") as Galaxies.State
        assertEquals("Clear", Galaxies.currentKeyLabel(ui, withEdge, Buttons.CURSOR_SELECT))

        // A drag from a dot: the source cancels, a legal target places.
        val target = assocToken.substring(1).split(',').map { it.toInt() }
        ui.dragging = true
        ui.srcX = dot.first
        ui.srcY = dot.second
        ui.dotX = dot.first
        ui.dotY = dot.second
        ui.curX = dot.first
        ui.curY = dot.second
        assertEquals("Cancel", Galaxies.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.curX = target[0]
        ui.curY = target[1]
        assertEquals("Place", Galaxies.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        // An illegal target on a drag that started from a dot cancels.
        ui.curX = 0
        ui.curY = 0
        assertEquals("Cancel", Galaxies.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        // A drag from an associated tile removes the arrow when the
        // target is not legal.
        val fromDot = associated.associatedDot(tile.first, tile.second)!!
        ui.dragging = true
        ui.srcX = tile.first
        ui.srcY = tile.second
        ui.dotX = fromDot.first
        ui.dotY = fromDot.second
        ui.curX = 0
        ui.curY = 0
        assertEquals("Remove", Galaxies.currentKeyLabel(ui, associated, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `cursorLocation uses the three cursor shapes`() {
        val midend = Midend.newGame(Galaxies, "7x7dn", "12345")
        val ui = midend.ui as Galaxies.Ui
        val state = midend.state as Galaxies.State
        assertNull(Galaxies.cursorLocation(ui, state, 32))

        ui.curVisible = true
        // A tile cursor is a 17x17 ring around the tile centre.
        ui.curX = 1
        ui.curY = 1
        assertEquals(
            Game.CursorRect(40, 40, 17, 17),
            Galaxies.cursorLocation(ui, state, 32),
        )
        // An edge cursor is a narrow bar.
        ui.curX = 2
        ui.curY = 1
        assertEquals(
            Game.CursorRect(62, 40, 5, 17),
            Galaxies.cursorLocation(ui, state, 32),
        )
        // A vertex cursor is a small square.
        ui.curX = 2
        ui.curY = 2
        assertEquals(
            Game.CursorRect(62, 62, 5, 5),
            Galaxies.cursorLocation(ui, state, 32),
        )
        // A dot cursor is a wider ring.
        val dot = state.dotPositions().first()
        ui.curX = dot.first
        ui.curY = dot.second
        assertEquals(
            Game.CursorRect(
                dot.first * 32 / 2 + 32 - 8,
                dot.second * 32 / 2 + 32 - 8,
                17,
                17,
            ),
            Galaxies.cursorLocation(ui, state, 32),
        )
    }

    @Test
    fun `the generator is deterministic and the answer starts with S`() {
        val p = Galaxies.decodeParams("7x7dn")
        val first = Galaxies.newDesc(p, RandomState.fromSeed("12345"))
        val second = Galaxies.newDesc(p, RandomState.fromSeed("12345"))
        assertEquals(first, second)
        assertNotNull(first.auxInfo)
        assertTrue(first.auxInfo!!.startsWith("S;"))
        val other = Galaxies.newDesc(p, RandomState.fromSeed("12346"))
        assertTrue(first.text != other.text)
    }

    private fun firstAssociated(state: Galaxies.State): Pair<Int, Int> {
        for (y in 1 until state.sy - 1) {
            for (x in 1 until state.sx - 1) {
                if (state.isAssociated(x, y)) return x to y
            }
        }
        error("no associated tile")
    }

    private fun firstLegalEdge(state: Galaxies.State): Pair<Int, Int> {
        for (y in 1 until state.sy - 1) {
            for (x in 1 until state.sx - 1) {
                val isEdge = (x % 2 == 0) != (y % 2 == 0)
                if (!isEdge) continue
                if (state.isEdgeSet(x, y)) continue
                // The same dot test as `edge_placement_legal`.
                val a = state.flagsAt(x, y)
                val b = state.flagsAt(x and 1.inv(), y and 1.inv())
                val c = state.flagsAt((x + 1) and 1.inv(), (y + 1) and 1.inv())
                if (((a or b or c) and 1) == 0) return x to y
            }
        }
        error("no legal edge")
    }
}
