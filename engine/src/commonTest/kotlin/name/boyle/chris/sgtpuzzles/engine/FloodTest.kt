package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges of the Flood port. */
class FloodTest {

    @Test
    fun `parameters decode, encode, and validate`() {
        assertEquals(Flood.Params(12, 12, 6, 5), Flood.decodeParams("12x12c6m5"))
        assertEquals(Flood.Params(12, 12, 6, 5), Flood.decodeParams("12x12"))
        // `decode_params` starts from the defaults, so the short form
        // keeps colours 6 and leniency 5.
        assertEquals(Flood.Params(8, 8, 6, 5), Flood.decodeParams("8x8"))
        assertEquals(Flood.Params(12, 12, 3, 5), Flood.decodeParams("12x12c3"))
        assertEquals(Flood.Params(12, 12, 6, 0), Flood.decodeParams("12x12m0"))

        // `atoi` accepts a sign, but the digit cursor does not, so the
        // rest of a signed string is skipped one character at a time.
        assertEquals(Flood.Params(12, 12, 3, 0), Flood.decodeParams("+12x12c3m0"))
        assertEquals(Flood.Params(12, 12, 6, 5), Flood.decodeParams("12X12"))
        assertEquals(Flood.Params(12, 12, 3, 0), Flood.decodeParams("12x12c3m0xyz"))
        assertEquals(Flood.Params(12, 12, 3, -1), Flood.decodeParams("12x12c3m-1"))
        assertEquals(Flood.Params(0, 0, 6, 5), Flood.decodeParams(""))
        assertEquals(Flood.Params(12, 0, 6, 5), Flood.decodeParams("12x"))

        assertEquals("12x12c6m5", Flood.encodeParams(Flood.defaultParams(), true))
        assertEquals("12x12", Flood.encodeParams(Flood.defaultParams(), false))
        assertEquals(
            "12x12c3m0",
            Flood.encodeParams(Flood.decodeParams("12x12c3m0"), true),
        )
        assertEquals("12x12", Flood.encodeParams(Flood.decodeParams("12x12c3m0"), false))

        assertEquals(null, Flood.validateParams(Flood.Params(12, 12, 6, 5), true))
        assertEquals(
            "Grid must contain at least two squares",
            Flood.validateParams(Flood.Params(1, 1, 3, 0), true),
        )
        assertEquals(
            "Grid must contain at least two squares",
            Flood.validateParams(Flood.Params(0, 5, 3, 0), true),
        )
        assertEquals(
            "Width and height must be at least one",
            Flood.validateParams(Flood.Params(-2, -2, 3, 0), true),
        )
        // The product wraps to 2, passes the first test, and then hits
        // the overflow guard, as in C.
        assertEquals(
            "Width times height must not be unreasonably large",
            Flood.validateParams(Flood.Params(3, 1431655766, 3, 0), true),
        )
        assertEquals(
            "Must have between 3 and 10 colours",
            Flood.validateParams(Flood.Params(12, 12, 2, 0), true),
        )
        assertEquals(
            "Must have between 3 and 10 colours",
            Flood.validateParams(Flood.Params(12, 12, 11, 0), true),
        )
        assertEquals(
            "Leniency must be non-negative",
            Flood.validateParams(Flood.Params(12, 12, 3, -1), true),
        )
    }

    @Test
    fun `presets, identity, and the short form asymmetry`() {
        assertEquals(
            listOf(
                "12x12 Easy", "12x12 Medium", "12x12 Hard",
                "16x16 Medium", "16x16 Hard",
                "12x12, 3 colours", "12x12, 4 colours",
            ),
            Flood.presets().map { it.title },
        )
        assertEquals(
            listOf(
                "12x12c6m5", "12x12c6m2", "12x12c6m0",
                "16x16c6m2", "16x16c6m0", "12x12c3m0", "12x12c4m0",
            ),
            Flood.presets().map { it.params },
        )
        assertEquals("12x12c6m5", Flood.encodeParams(Flood.defaultParams(), true))
        assertEquals("12x12", Flood.encodeParams(Flood.defaultParams(), false))
        assertEquals("Flood", Flood.name)
        assertEquals("flood", Flood.backendName)
        assertEquals(32, Flood.preferredTileSize)
        assertTrue(Flood.wantsStatusBar)
        assertTrue(Flood.canSolve)
        assertEquals(ArrowMode.ARROWS_LEFT, Flood.requestKeys(Flood.defaultParams()).arrowMode)
        assertTrue(Flood.requestKeys(Flood.defaultParams()).labels.isEmpty())

        // A parameter set with fewer colours re-encodes to the defaults
        // in the short form, which is what the game id and PARAMS carry.
        val midend = Midend.newGame(Flood, "12x12c3m0", "12345")
        assertTrue(midend.id().startsWith("12x12:"))
        assertEquals("12x12c6m5", midend.serialise().value("PARAMS"))
        assertEquals("12x12c3m0", midend.serialise().value("CPARAMS"))
    }

    @Test
    fun `descriptions are validated`() {
        val params = Flood.decodeParams("2x2c3m0")
        assertEquals(null, Flood.validateDesc(params, "0123,4"))
        assertEquals(null, Flood.validateDesc(params, "0000,0"))
        assertEquals(null, Flood.validateDesc(params, "0123,"))
        assertEquals(null, Flood.validateDesc(params, "0123,0123"))
        assertEquals(
            "Not enough data in grid description",
            Flood.validateDesc(params, "012"),
        )
        assertEquals(
            "Bad character in grid description",
            Flood.validateDesc(params, "01a3,4"),
        )
        assertEquals(
            "Colour out of range in grid description",
            Flood.validateDesc(params, "0A23,4"),
        )
        assertEquals(
            "Expected ',' after grid description",
            Flood.validateDesc(params, "01234,4"),
        )
        assertEquals(
            "Badly formatted move limit after grid description",
            Flood.validateDesc(params, "0123,4x"),
        )
        assertEquals(
            "Badly formatted move limit after grid description",
            Flood.validateDesc(params, "0123,-1"),
        )
    }

    @Test
    fun `generated descriptions match the frozen oracle values`() {
        assertEquals(
            "12x12:144213455020311230402553542320000102051001150511215302333534152035244051" +
                "241432023324551101011211522022310121213214532552231503344231334355401013,24",
            Midend.newGame(Flood, "12x12c6m5", "12345").id(),
        )
        assertEquals(
            "4x4:1112101220200112,4",
            Midend.newGame(Flood, "4x4c3m0", "12345").id(),
        )
        assertEquals(
            "12x12:111210122020011200102222212020000102011001120211212020002011220012211021" +
                "211102020212211010112112220221012121021120222221100011201001022001010110,8",
            Midend.newGame(Flood, "12x12c3m0", "12345").id(),
        )
    }

    @Test
    fun `a state copies deeply and keeps its solution`() {
        val state = Flood.newState(Flood.decodeParams("2x2c3m0"), "0112,3") as Flood.State
        val solved = Flood.executeMove(state, "S1,0") as Flood.State
        val copy = solved.copy() as Flood.State
        assertEquals(solved, copy)
        copy.grid[0] = 2
        assertNotEquals(solved, copy)

        val second = solved.copy() as Flood.State
        second.grid[0] = 2
        assertEquals(solved, solved.copy())
        assertEquals(solved.soln, second.soln)
    }

    @Test
    fun `new game reads colours from the description and does not complete`() {
        val params = Flood.decodeParams("2x2c3m0")
        val blank = Flood.newState(params, "0000,4") as Flood.State
        assertEquals(1, blank.colours)
        assertFalse(blank.complete)
        assertEquals(0, blank.moves)
        assertEquals(0, Flood.status(blank))
        assertEquals("0 / 4 moves", Flood.statusText(blank, Flood.newUi(blank)))
        assertEquals("00\n00\n", Flood.textFormat(blank))

        // A description that does not use the top parameter colour
        // lowers the state's colour count, and the solver uses that.
        val state = Flood.newState(Flood.decodeParams("2x2c6m0"), "0101,4") as Flood.State
        assertEquals(2, state.colours)
    }

    @Test
    fun `moves flood the top-left region and count`() {
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")
        val after = Flood.executeMove(state, "M2") as Flood.State
        assertEquals(1, after.moves)
        assertEquals(
            "2222\n2022\n2020\n0112\n",
            Flood.textFormat(after),
        )
        assertFalse(after.complete)
        assertEquals(0, Flood.status(after))
        assertEquals("1 / 4 moves", Flood.statusText(after, Flood.newUi(after)))

        assertNull(Flood.executeMove(state, "M1"))
        assertNull(Flood.executeMove(state, "M3"))
        assertNull(Flood.executeMove(state, "M-1"))
        assertNull(Flood.executeMove(state, "X2"))
        assertNull(Flood.executeMove(state, ""))

        // `sscanf("%d")` ignores everything after the number.
        assertNotNull(Flood.executeMove(state, "M2x"))
        assertNotNull(Flood.executeMove(state, "M 2"))
        assertNotNull(Flood.executeMove(state, "M+2"))
        assertNotNull(Flood.executeMove(state, "M2,2"))
    }

    @Test
    fun `the solve string and the solution grammar match C`() {
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")
        assertEquals("S2,0,1,2", Flood.solve(state))

        val solved = Flood.executeMove(state, "S2,0,1,2") as Flood.State
        assertTrue(solved.cheated)
        assertEquals(0, solved.moves)
        assertEquals(0, solved.solnpos)
        assertEquals(listOf(2, 0, 1, 2), solved.soln?.moves)
        assertFalse(solved.complete)
        assertEquals("Auto-solver used. 0 / 4 moves",
            Flood.statusText(solved, Flood.newUi(solved)))

        // `atoi` on an empty token is 0, so `S2,,1` is [2, 0, 1].
        val empty = Flood.executeMove(state, "S2,,1") as Flood.State
        assertEquals(listOf(2, 0, 1), empty.soln?.moves)

        assertNull(Flood.executeMove(state, "S2,0,1,2,"))
        assertNull(Flood.executeMove(state, "S2,0,1,2x"))
        assertNull(Flood.executeMove(state, "S2,0,0"))
        assertNull(Flood.executeMove(state, "S1"))
        assertNull(Flood.executeMove(state, "S"))
        assertNull(Flood.executeMove(state, "S-1"))

        // A completed state has no solution.
        var complete: Game.State = state
        for (move in listOf("M2", "M0", "M1", "M2")) {
            complete = Flood.executeMove(complete, move)!!
        }
        assertTrue((complete as Flood.State).complete)
        assertNull(Flood.solve(complete))

        // A uniform description is complete as far as `completed()` is
        // concerned, so the solver loop runs zero times. C then returns
        // an uninitialized buffer and aborts in `assert_printable_ascii`;
        // the port returns the empty string. The `S-1` path in
        // `FloodSolver` is therefore unreachable from a real state.
        val uniform = Flood.newState(Flood.decodeParams("2x2c3m0"), "0000,4")
        assertEquals("", Flood.solve(uniform))
        assertNull(Flood.executeMove(uniform, "S-1"))
    }

    @Test
    fun `completion, the move limit, and the status text`() {
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")
        var after: Flood.State = state as Flood.State
        for (move in listOf("M2", "M0", "M1", "M2")) {
            after = Flood.executeMove(after, move) as Flood.State
        }
        assertTrue(after.complete)
        assertEquals(1, Flood.status(after))
        assertEquals("COMPLETED! 4 / 4 moves", Flood.statusText(after, Flood.newUi(after)))

        val over = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,2")
        var lost: Flood.State = over as Flood.State
        for (move in listOf("M2", "M0", "M1", "M2")) {
            lost = Flood.executeMove(lost, move) as Flood.State
        }
        assertTrue(lost.complete)
        assertEquals(-1, Flood.status(lost))
        assertEquals("FAILED! 4 / 2 moves", Flood.statusText(lost, Flood.newUi(lost)))

        val zero = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,0")
        assertEquals(-1, Flood.status(zero))
        assertEquals("FAILED! 0 / 0 moves", Flood.statusText(zero, Flood.newUi(zero)))
    }

    @Test
    fun `a solve path advances and drops as in C`() {
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")
        val solved = Flood.executeMove(state, "S2,0,1,2") as Flood.State

        val first = Flood.executeMove(solved, "M2") as Flood.State
        assertEquals(1, first.solnpos)
        assertNotNull(first.soln)

        val wrong = Flood.executeMove(first, "M1") as Flood.State
        assertNull(wrong.soln)
        assertEquals(0, wrong.solnpos)
    }

    @Test
    fun `completion is reported only for an honest complete state`() {
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")
        val ui = Flood.newUi(state)
        var after: Flood.State = state as Flood.State
        var reported = 0
        var old: Game.State? = state
        for (move in listOf("M2", "M0", "M1", "M2")) {
            after = Flood.executeMove(after, move) as Flood.State
            if (Flood.changedState(ui, old, after)) reported++
            old = after
        }
        assertEquals(1, reported)

        // A completed cheated state reports nothing.
        val solved = Flood.executeMove(state, "S2,0,1,2") as Flood.State
        var cheated: Flood.State = solved
        old = solved
        reported = 0
        for (move in listOf("M2", "M0", "M1", "M2")) {
            cheated = Flood.executeMove(cheated, move) as Flood.State
            if (Flood.changedState(ui, old, cheated)) reported++
            old = cheated
        }
        assertTrue(cheated.complete)
        assertTrue(cheated.cheated)
        assertEquals(0, reported)
    }

    @Test
    fun `the click path changes the move count`() {
        val midend = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        val tile = Flood.preferredTileSize
        val movesBefore = midend.serialise().values("MOVE").size

        // Cell (3,0) has colour 2; the top-left cell has colour 1.
        val x = 4 * tile
        val y = tile
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals(movesBefore + 1, midend.serialise().values("MOVE").size)
        assertEquals("M2", midend.serialise().values("MOVE").single())
        assertEquals(0, midend.status())

        // The same click now selects the current colour, so it adds
        // nothing.
        assertEquals(KeyOutcome.NoEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals(movesBefore + 1, midend.serialise().values("MOVE").size)
    }

    @Test
    fun `the solve path cheats and raises no completion event`() {
        val midend = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        assertEquals("S2,0,1,2", Flood.solve(midend.state))

        assertTrue(midend.solve())
        val solved = midend.state as Flood.State
        assertTrue(solved.cheated)
        assertEquals(0, solved.moves)
        assertFalse(midend.takeCompletedEvent())
        assertEquals("Auto-solver used. 0 / 4 moves", midend.statusText())

        val tile = Flood.preferredTileSize
        repeat(4) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_SELECT2, tile),
            )
            assertFalse(
                midend.takeCompletedEvent(),
                "a solve completion must not be reported",
            )
        }
        assertEquals(1, midend.status())
        assertEquals("Auto-solved. 4 / 4 moves", midend.statusText())
        assertEquals(4, (midend.state as Flood.State).moves)
    }

    @Test
    fun `undo and redo restore moves and completion`() {
        val midend = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        val tile = Flood.preferredTileSize
        for (move in listOf("M2", "M0", "M1", "M2")) {
            val before = midend.state as Flood.State
            val x = cellWithColour(midend.state as Flood.State, move.removePrefix("M").toInt())
            assertEquals(KeyOutcome.SomeEffect,
                midend.processKey(x.first, x.second, Buttons.LEFT_BUTTON, tile))
            assertNotEquals(before, midend.state)
        }
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertEquals("3 / 4 moves", midend.statusText())
        assertFalse(midend.takeCompletedEvent())

        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `the cursor clamps, hides on a click, and is reported`() {
        val midend = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        val ui = midend.ui as Flood.Ui
        val tile = Flood.preferredTileSize

        assertNull(Flood.cursorLocation(ui, midend.state, tile))
        assertEquals(KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_UP, tile))
        assertTrue(ui.cursorVisible)
        assertEquals(0, ui.cx)
        assertEquals(0, ui.cy)
        assertEquals(Game.CursorRect(16, 16, tile, tile),
            Flood.cursorLocation(ui, midend.state, tile))

        // The cursor does not wrap: a second up key is a no-effect key.
        assertEquals(KeyOutcome.NoEffect,
            midend.processKey(0, 0, Buttons.CURSOR_UP, tile))
        assertEquals(KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_DOWN, tile))
        assertEquals(1, ui.cy)

        // A left click hides the cursor. A click on the current colour
        // is only a UI update; it adds no move.
        val moves = midend.stateCount
        assertEquals(KeyOutcome.SomeEffect,
            midend.processKey(Flood.preferredTileSize, Flood.preferredTileSize,
                Buttons.LEFT_BUTTON, tile))
        assertFalse(ui.cursorVisible)
        assertEquals(moves, midend.stateCount)
    }

    @Test
    fun `keyboard select fills and select2 advances`() {
        val midend = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        val ui = midend.ui as Flood.Ui
        val tile = Flood.preferredTileSize

        // The cursor starts hidden. `CURSOR_SELECT` does not make it
        // visible, so a keyboard fill works at a hidden cursor.
        assertFalse(ui.cursorVisible)
        ui.cx = 3
        ui.cy = 0
        assertEquals("Fill", Flood.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals(KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals("M2", midend.serialise().values("MOVE").single())
        assertEquals(1, (midend.state as Flood.State).moves)
        assertFalse(ui.cursorVisible)

        // A fresh game with a stored solution offers Advance.
        val solved = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        solveAndCheck(solved)
        val solvedUi = solved.ui as Flood.Ui
        assertEquals("Advance",
            Flood.currentKeyLabel(solvedUi, solved.state, Buttons.CURSOR_SELECT2))
        assertEquals(KeyOutcome.SomeEffect,
            solved.processKey(0, 0, Buttons.CURSOR_SELECT2, tile))
        assertEquals(1, (solved.state as Flood.State).solnpos)
    }

    @Test
    fun `the palette matches the frozen board colours`() {
        val colours = Flood.colours()
        assertEquals(14 * 3, colours.size)
        val expected = listOf(
            800, 800, 800,
            0, 0, 0,
            1000, 0, 0,
            1000, 1000, 0,
            0, 1000, 0,
            200, 300, 1000,
            1000, 500, 0,
            500, 0, 700,
            500, 300, 300,
            400, 800, 1000,
            700, 1000, 700,
            1000, 600, 1000,
            967, 967, 967,
            633, 633, 633,
        )
        assertEquals(expected, colours.map { (it * 1000.0f).roundToInt() })
    }

    @Test
    fun `the board size uses the half tile border`() {
        assertEquals(Size(160, 160), Flood.computeSize(Flood.decodeParams("4x4c3m0"), 32))
        assertEquals(Size(416, 416), Flood.computeSize(Flood.defaultParams(), 32))
        assertEquals(Size(402, 402), Flood.computeSize(Flood.defaultParams(), 31))
        assertEquals(Size(598, 598), Flood.computeSize(Flood.defaultParams(), 46))
        assertEquals(Size(194, 194), Flood.computeSize(Flood.defaultParams(), 15))
        assertEquals(Size(410, 410), Flood.computeSize(Flood.decodeParams("4x4c3m0"), 82))
    }

    @Test
    fun `drawing covers the board, the cursor, and the solver preview`() {
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")
        val ui = Flood.newUi(state) as Flood.Ui

        val plain = RecordingSink()
        Flood.redraw(state, ui, Game.NoDrawState, plain, 32)
        assertEquals(2, plain.polys.size)
        assertEquals(16, plain.updates.size)
        assertEquals(0, plain.circles.size)
        assertTrue(plain.rects.any { it.colour == 1 })

        ui.cursorVisible = true
        ui.cx = 3
        ui.cy = 0
        val cursor = RecordingSink()
        Flood.redraw(state, ui, Game.NoDrawState, cursor, 32)
        assertTrue(cursor.polys.any { it.fill == -1 })
        assertTrue(cursor.polys.filter { it.fill == -1 }.all { it.colour == 1 })

        val solved = Flood.executeMove(state, "S2,0,1,2") as Flood.State
        val preview = RecordingSink()
        Flood.redraw(solved, Flood.newUi(solved), Game.NoDrawState, preview, 32)
        assertEquals(4, preview.circles.size)

        // At tile 15, `SEP_WIDTH` is 0, so no border or corner squares
        // are drawn. The separator rectangle is still drawn first.
        val tiny = RecordingSink()
        Flood.redraw(state, Flood.newUi(state), Game.NoDrawState, tiny, 15)
        assertEquals(2, tiny.polys.size)
        assertEquals(16, tiny.rects.count { it.w == 15 && it.h == 15 })
    }

    private fun solveAndCheck(midend: Midend) {
        assertEquals("S2,0,1,2", Flood.solve(midend.state))
        assertTrue(midend.solve())
    }

    private fun cellWithColour(state: Flood.State, colour: Int): Pair<Int, Int> {
        val tile = Flood.preferredTileSize
        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                if (state.colourAt(x, y) == colour) {
                    // The centre of the cell for the default tile size.
                    return (x + 1) * tile to (y + 1) * tile
                }
            }
        }
        error("no cell has colour $colour")
    }

    private class RecordingSink : DrawSink {
        data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val colour: Int)
        data class Poly(val fill: Int, val colour: Int)
        data class Circle(val cx: Int, val cy: Int, val radius: Int)
        data class Update(val x: Int, val y: Int, val w: Int, val h: Int)

        val rects = ArrayList<Rect>()
        val polys = ArrayList<Poly>()
        val circles = ArrayList<Circle>()
        val updates = ArrayList<Update>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            rects += Rect(x, y, width, height, colour)
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
            circles += Circle(cx, cy, radius)
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            polys += Poly(fillColour, outlineColour)
        }

        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {
            updates += Update(x, y, width, height)
        }

        override fun status(text: String) {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
