package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges of the Same Game port. */
class SameGameTest {

    @Test
    fun `parameters decode, encode, and validate`() {
        assertEquals(SameGame.Params(5, 5, 3, 2, true), SameGame.decodeParams("5x5c3s2"))
        assertEquals(SameGame.Params(5, 5, 3, 2, true), SameGame.decodeParams("5x5"))
        assertEquals(SameGame.Params(10, 10, 3, 2, true), SameGame.decodeParams("10"))
        assertEquals(SameGame.Params(5, 5, 4, 1, false), SameGame.decodeParams("5x5c4s1r"))

        // `atoi` accepts a sign and white space, but the digit cursor
        // does not, so the tail is ignored.
        assertEquals(SameGame.Params(5, 5, 3, 2, true), SameGame.decodeParams("+5x5c3s2"))
        assertEquals(SameGame.Params(5, 5, 3, 2, true), SameGame.decodeParams(" 5x5c3s2"))
        assertEquals(SameGame.Params(5, 5, 3, 2, true), SameGame.decodeParams("5X5"))
        assertEquals(SameGame.Params(5, 5, 3, 2, true), SameGame.decodeParams("5x5c3s2x"))
        assertEquals(SameGame.Params(5, 5, 3, 2, false), SameGame.decodeParams("5x5c3s2rxyz"))
        assertEquals(SameGame.Params(5, 0, 3, 2, true), SameGame.decodeParams("5x"))
        assertEquals(SameGame.Params(0, 0, 3, 2, true), SameGame.decodeParams(""))

        assertEquals("5x5c3s2", SameGame.encodeParams(SameGame.Params(5, 5, 3, 2, true), true))
        assertEquals("5x5c3s2", SameGame.encodeParams(SameGame.Params(5, 5, 3, 2, false), false))
        assertEquals("5x5c3s2r", SameGame.encodeParams(SameGame.Params(5, 5, 3, 2, false), true))
        assertEquals("10x10c3s2", SameGame.encodeParams(SameGame.decodeParams("10"), false))

        assertEquals(null, SameGame.validateParams(SameGame.Params(5, 5, 3, 2, true), true))
        assertEquals(null, SameGame.validateParams(SameGame.Params(2, 2, 2, 1, false), true))
        assertEquals(
            "Width and height must both be positive",
            SameGame.validateParams(SameGame.Params(0, 5, 3, 2, true), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            SameGame.validateParams(SameGame.Params(100000, 100000, 3, 2, true), true),
        )
        assertEquals(
            "Maximum of 9 colours",
            SameGame.validateParams(SameGame.Params(5, 5, 10, 2, true), true),
        )
        assertEquals(
            "Number of colours must be at least three",
            SameGame.validateParams(SameGame.Params(5, 5, 2, 2, true), true),
        )
        assertEquals(
            "Grid area must be greater than one",
            SameGame.validateParams(SameGame.Params(1, 1, 3, 2, true), true),
        )
        assertEquals(
            "Number of colours must be at least three",
            SameGame.validateParams(SameGame.Params(5, 5, 1, 2, false), true),
        )
        assertEquals(
            "Too many colours makes given grid size impossible",
            SameGame.validateParams(SameGame.Params(2, 2, 9, 2, false), true),
        )
        assertEquals(
            "Scoring system not recognised",
            SameGame.validateParams(SameGame.Params(5, 5, 3, 0, true), true),
        )
        assertEquals(
            "Scoring system not recognised",
            SameGame.validateParams(SameGame.Params(5, 5, 3, 3, true), true),
        )
    }

    @Test
    fun `presets and identity`() {
        assertEquals(listOf("5x5, 3 colours", "10x5, 3 colours", "15x10, 3 colours",
            "15x10, 4 colours", "20x15, 4 colours"), SameGame.presets().map { it.title })
        assertEquals(listOf("5x5c3s2", "10x5c3s2", "15x10c3s2", "15x10c4s2", "20x15c4s2"),
            SameGame.presets().map { it.params })
        assertEquals("5x5c3s2", SameGame.encodeParams(SameGame.defaultParams(), false))
        assertEquals("Same Game", SameGame.name)
        assertEquals("samegame", SameGame.backendName)
        assertEquals(32, SameGame.preferredTileSize)
        assertTrue(SameGame.wantsStatusBar)
        assertFalse(SameGame.canSolve)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, SameGame.requestKeys(SameGame.defaultParams()).arrowMode)
        assertTrue(SameGame.requestKeys(SameGame.defaultParams()).labels.isEmpty())
        assertNull(
            SameGame.solve(
                SameGame.newState(SameGame.Params(5, 5, 3, 2, true), "1,2,3,1,2,3,1,2,3,1,2,3,1,2,3,1,2,3,1,2,3,1,2,3,1"),
            ),
        )
    }

    @Test
    fun `descriptions are validated`() {
        val params = SameGame.Params(2, 2, 3, 2, true)
        assertEquals(null, SameGame.validateDesc(params, "1,2,3,1"))
        assertEquals(null, SameGame.validateDesc(params, "0,0,0,0"))
        assertEquals(null, SameGame.validateDesc(params, "01,002,3,1"))
        assertEquals(
            "Expected comma after number",
            SameGame.validateDesc(params, "1,2,3"),
        )
        assertEquals(
            "Not enough numbers in string",
            SameGame.validateDesc(params, "1,2,3,"),
        )
        assertEquals(
            "Expected comma after number",
            SameGame.validateDesc(params, "1 2,3,1"),
        )
        assertEquals(
            "Excess junk at end of string",
            SameGame.validateDesc(params, "1,2,3,1x"),
        )
        assertEquals(
            "Colour out of range",
            SameGame.validateDesc(params, "1,2,3,4"),
        )
        assertEquals(
            "Colour out of range",
            SameGame.validateDesc(params, "10,2,3,1"),
        )
    }

    @Test
    fun `a state copies deeply and keeps its own score`() {
        val state = SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,2,3,1") as SameGame.State
        val copy = state.copy() as SameGame.State
        assertEquals(state, copy)

        copy.tiles[0] = 3
        copy.score = 7
        copy.complete = true
        assertNotEquals(state, copy)
        assertEquals(1, state.tiles[0])
        assertEquals(0, state.score)
        assertFalse(state.complete)
    }

    @Test
    fun `new game does not run the completion check`() {
        val params = SameGame.Params(2, 2, 3, 2, true)
        val blank = SameGame.newState(params, "0,0,0,0") as SameGame.State
        assertFalse(blank.complete)
        assertFalse(blank.impossible)
        assertEquals(0, SameGame.status(blank))
        assertEquals("Score: 0", SameGame.statusText(blank, SameGame.newUi(blank)))

        val dead = SameGame.newState(params, "1,2,3,1") as SameGame.State
        assertFalse(dead.impossible)
        assertEquals(0, SameGame.status(dead))
        assertEquals("Score: 0", SameGame.statusText(dead, SameGame.newUi(dead)))
    }

    @Test
    fun `two taps select and remove a group`() {
        val midend = Midend.fromGameId(SameGame, "2x2c3s2:1,1,2,3")
        val ui = midend.ui as SameGame.Ui
        val tile = SameGame.preferredTileSize
        val x = tile
        val y = tile

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals(2, ui.nselected)
        assertEquals("Score: 0  Selected: 2 (0)", midend.statusText())
        assertEquals(1, midend.stateCount)

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals(0, ui.nselected)
        assertEquals(2, midend.stateCount)
        assertEquals("M0,1", midend.serialise().values("MOVE").single())

        val after = midend.state as SameGame.State
        assertEquals(0, after.score)
        assertEquals("  \n23\n", SameGame.textFormat(after))
        assertEquals(0, SameGame.status(after))
    }

    @Test
    fun `a lone tile is not selected, a blank keeps the selection, and a border click is unused`() {
        val lone = Midend.fromGameId(SameGame, "2x2c3s2:1,1,2,0")
        val loneUi = lone.ui as SameGame.Ui
        val tile = SameGame.preferredTileSize

        // Cell (0,1) is a lone square: the click changes only the UI.
        assertEquals(
            KeyOutcome.SomeEffect,
            lone.processKey(tile, 2 * tile, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(0, loneUi.nselected)
        assertFalse(loneUi.displaysel)
        assertEquals(1, lone.stateCount)

        // Outside the board is unused and hides the cursor.
        assertEquals(KeyOutcome.SomeEffect, lone.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
        assertTrue(loneUi.displaysel)
        assertEquals(KeyOutcome.Unused, lone.processKey(0, 0, Buttons.LEFT_BUTTON, tile))
        assertFalse(loneUi.displaysel)

        // Click a blank cell: no effect, and the selection does not change.
        val other = Midend.fromGameId(SameGame, "2x2c3s2:1,1,2,0")
        val otherUi = other.ui as SameGame.Ui
        assertEquals(
            KeyOutcome.SomeEffect,
            other.processKey(tile, tile, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(2, otherUi.nselected)
        assertEquals(
            KeyOutcome.NoEffect,
            other.processKey(2 * tile, 2 * tile, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(2, otherUi.nselected)
        assertEquals(1, other.stateCount)
    }

    @Test
    fun `a right click on a selected group only clears it`() {
        val midend = Midend.fromGameId(SameGame, "2x2c3s2:1,1,2,3")
        val ui = midend.ui as SameGame.Ui
        val tile = SameGame.preferredTileSize
        val x = tile
        val y = tile

        midend.processKey(x, y, Buttons.LEFT_BUTTON, tile)
        assertEquals(2, ui.nselected)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.RIGHT_BUTTON, tile))
        assertEquals(0, ui.nselected)
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `the move grammar is as permissive as C`() {
        val params = SameGame.Params(2, 2, 3, 2, true)
        val state = SameGame.newState(params, "1,2,3,1")

        val noop = move(state, "M")
        assertNotNull(noop)
        assertEquals(0, noop.score)
        assertEquals("12\n31\n", SameGame.textFormat(noop))

        val single = move(state, "M0")
        assertNotNull(single)
        assertEquals(0, single.score)

        val duplicate = move(state, "M0,0,0")
        assertNotNull(duplicate)
        assertEquals(1, duplicate.score)

        assertNotNull(move(state, "M0,"))
        assertNotNull(move(state, "M00"))

        assertNull(move(state, ""))
        assertNull(move(state, "X1"))
        assertNull(move(state, "M,0"))
        assertNull(move(state, "M0,,1"))
        assertNull(move(state, "M 0"))
        assertNull(move(state, "M-1"))
        assertNull(move(state, "M4"))
    }

    @Test
    fun `gravity moves tiles down and columns left`() {
        val params = SameGame.Params(3, 3, 3, 2, true)
        val state = SameGame.newState(params, "1,2,3,0,3,0,0,0,0")
        val after = move(state, "M0")!!
        assertEquals("   \n2  \n33 \n", SameGame.textFormat(after))
        assertEquals(0, after.score)
        assertEquals(0, SameGame.status(after))

        val compaction = move(after, "M3")
        assertNotNull(compaction)
        assertEquals("   \n   \n33 \n", SameGame.textFormat(compaction))
    }

    @Test
    fun `completion impossible and their status text`() {
        val params = SameGame.Params(2, 2, 3, 2, true)

        val win = move(SameGame.newState(params, "1,1,1,1"), "M0,1,2,3")!!
        assertEquals(1, SameGame.status(win))
        assertTrue(win.complete)
        assertTrue(win.impossible)
        assertEquals(4, win.score)
        assertEquals(
            "COMPLETED! Score: 4",
            SameGame.statusText(win, SameGame.newUi(win)),
        )
        assertEquals("  \n  \n", SameGame.textFormat(win))

        val dead = move(SameGame.newState(params, "1,2,3,1"), "M0")!!
        assertEquals(0, SameGame.status(dead))
        assertTrue(dead.impossible)
        assertEquals(
            "Cannot move! Score: 0",
            SameGame.statusText(dead, SameGame.newUi(dead)),
        )

        val blankMove = move(SameGame.newState(params, "1,2,3,1"), "M")!!
        assertTrue(blankMove.impossible)
        assertEquals("Cannot move! Score: 0", SameGame.statusText(blankMove, SameGame.newUi(blankMove)))
    }

    @Test
    fun `selection clears on every state change and completion is reported once`() {
        val midend = Midend.fromGameId(SameGame, "2x2c3s2:1,1,1,1")
        val ui = midend.ui as SameGame.Ui
        val tile = SameGame.preferredTileSize
        val x = tile
        val y = tile

        assertFalse(midend.takeCompletedEvent())
        midend.processKey(x, y, Buttons.LEFT_BUTTON, tile)
        assertEquals(4, ui.nselected)
        midend.processKey(x, y, Buttons.LEFT_BUTTON, tile)
        assertEquals(0, ui.nselected)
        assertEquals("COMPLETED! Score: 4", midend.statusText())
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertEquals(0, ui.nselected)
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.canRedo)

        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `the cursor is reported even when it is not drawn`() {
        val state = SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,1,1,1")
        val win = move(state, "M0,1,2,3")!!
        val ui = SameGame.newUi(win)
        assertEquals(null, SameGame.cursorLocation(ui, win, 32))

        SameGame.setCursorVisible(ui, true)
        assertEquals(Game.CursorRect(16, 16, 32, 32), SameGame.cursorLocation(ui, win, 32))

        val sink = RecordingSink()
        SameGame.redraw(win, ui, sink, 32)
        assertTrue(sink.lines.isEmpty(), "a completed board hides the cursor")
        assertTrue(sink.rects.none { it.colour == COL_IMPOSSIBLE })
    }

    @Test
    fun `current key labels match upstream`() {
        val state = SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,1,2,3")
        val ui = SameGame.newUi(state) as SameGame.Ui

        ui.xsel = 0
        ui.ysel = 0
        assertEquals("Select", SameGame.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        assertEquals(
            MoveResult.UiUpdate,
            SameGame.interpretMove(state, ui, 0, 0, Buttons.CURSOR_SELECT, 32),
        )
        assertEquals(2, ui.nselected)
        assertEquals("Remove", SameGame.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Unselect", SameGame.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A lone square with something selected offers Unselect.
        ui.xsel = 0
        ui.ysel = 1
        assertEquals("Unselect", SameGame.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        // A blank cell has no label.
        val blank = SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,1,2,0") as SameGame.State
        val blankUi = SameGame.newUi(blank) as SameGame.Ui
        blankUi.xsel = 1
        blankUi.ysel = 1
        assertEquals("", SameGame.currentKeyLabel(blankUi, blank, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `the palette matches the frozen board colours`() {
        val colours = SameGame.colours()
        assertEquals(14 * 3, colours.size)
        val expected = listOf(
            800, 800, 800,
            0, 0, 1000,
            0, 500, 0,
            1000, 0, 0,
            700, 700, 0,
            1000, 0, 1000,
            0, 800, 800,
            500, 500, 1000,
            200, 800, 200,
            1000, 500, 500,
            0, 0, 0,
            1000, 1000, 1000,
            967, 967, 967,
            633, 633, 633,
        )
        assertEquals(expected, colours.map { (it * 1000.0f).roundToInt() })
    }

    @Test
    fun `the board size uses integer division at odd tile sizes`() {
        val params = SameGame.Params(5, 5, 3, 2, true)
        assertEquals(Size(190, 190), SameGame.computeSize(params, 32))
        assertEquals(Size(285, 285), SameGame.computeSize(params, 48))
        assertEquals(Size(373, 373), SameGame.computeSize(params, 63))
        assertEquals(Size(409, 409), SameGame.computeSize(params, 69))
        assertEquals(Size(487, 487), SameGame.computeSize(params, 82))
        assertEquals(Size(594, 594), SameGame.computeSize(params, 100))
        assertEquals(Size(599, 599), SameGame.computeSize(params, 101))

        assertEquals(
            Size(404, 219),
            SameGame.computeSize(SameGame.Params(10, 5, 3, 2, true), 37),
        )
        assertEquals(
            Size(670, 510),
            SameGame.computeSize(SameGame.Params(20, 15, 4, 2, true), 32),
        )
    }

    @Test
    fun `drawing covers the board and the selection`() {
        val state = SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,1,2,3")
        val ui = SameGame.newUi(state) as SameGame.Ui

        val plain = RecordingSink()
        SameGame.redraw(state, ui, plain, 32)
        assertEquals(2, plain.polys.size)
        assertEquals(4, plain.updates.size)
        assertEquals(0, plain.lines.size)
        assertEquals(0, plain.rects.count { it.colour == COL_SEL })

        // Tap the two-tile group at the top.
        val result = SameGame.interpretMove(state, ui, 48, 16, Buttons.LEFT_BUTTON, 32)
        assertEquals(MoveResult.UiUpdate, result)
        assertEquals(2, ui.nselected)

        val selected = RecordingSink()
        SameGame.redraw(state, ui, selected, 32)
        assertTrue(selected.rects.count { it.colour == COL_SEL } >= 2)

        // The cursor over a selected tile uses the lowlight colour.
        ui.xsel = 0
        ui.ysel = 0
        ui.displaysel = true
        val cursor = RecordingSink()
        SameGame.redraw(state, ui, cursor, 32)
        assertEquals(4, cursor.lines.size)
        assertTrue(cursor.lines.all { it.colour == COL_LOWLIGHT })

        // The cursor over an unselected tile uses the highlight colour.
        val freshUi = SameGame.newUi(state) as SameGame.Ui
        freshUi.displaysel = true
        val highlight = RecordingSink()
        SameGame.redraw(state, freshUi, highlight, 32)
        assertEquals(4, highlight.lines.size)
        assertTrue(highlight.lines.all { it.colour == COL_HIGHLIGHT })

        val dead = move(
            SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,2,3,1"),
            "M0",
        )!!
        val deadSink = RecordingSink()
        SameGame.redraw(dead, SameGame.newUi(dead), deadSink, 32)
        assertEquals(3, deadSink.rects.count { it.colour == COL_IMPOSSIBLE })
    }

    private fun move(state: Game.State, text: String): SameGame.State? =
        SameGame.executeMove(state, text) as SameGame.State?

    private class RecordingSink : DrawSink {
        data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val colour: Int)
        data class Line(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val colour: Int)
        data class Update(val x: Int, val y: Int, val w: Int, val h: Int)

        val rects = ArrayList<Rect>()
        val lines = ArrayList<Line>()
        val updates = ArrayList<Update>()
        val polys = ArrayList<Int>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            rects += Rect(x, y, width, height, colour)
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            lines += Line(x1, y1, x2, y2, colour)
        }

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            polys += fillColour
        }

        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {
            updates += Update(x, y, width, height)
        }

        override fun status(text: String) {}
    }

    private companion object {
        const val COL_IMPOSSIBLE = 10
        const val COL_SEL = 11
        const val COL_HIGHLIGHT = 12
        const val COL_LOWLIGHT = 13
    }
}
