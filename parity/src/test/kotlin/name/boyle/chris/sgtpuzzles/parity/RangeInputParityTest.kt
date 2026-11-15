package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: range.
 *
 * Layout, the real pointer path at several tile sizes, the click cycle,
 * the clue, border, and middle no-moves, the cursor and the keyboard,
 * the shift-paint path, and the letterboxed views are compared with the
 * oracle. Drawing and input use one geometry.
 */
class RangeInputParityTest {

    private val viewports = listOf(
        Viewport("411x702", 411, 702),
        Viewport("720x1280", 720, 1280),
        Viewport("1024x600", 1024, 600),
        Viewport("200x200", 200, 200),
    )

    @Test
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("9x6", "12x8", "13x9", "16x11")) {
            val start = oracle.newGame("range", params, SEED)
            for (case in viewports) {
                val layout = oracle.layout(start.state, case.width, case.height)
                val midend = Midend.newGame(Range, params, SEED)
                val geometry = midend.geometryFor(case.width, case.height)
                val size = Range.computeSize(Range.decodeParams(params), geometry.tileSize)
                val where = "${case.label}: '$params'"

                assertEquals("computeSize width $where", layout.width, size.width)
                assertEquals("computeSize height $where", layout.height, size.height)
                assertEquals("layout width $where", layout.width, geometry.width)
                assertEquals("layout height $where", layout.height, geometry.height)
                assertEquals(
                    "origin x $where",
                    (case.width - layout.width) / 2,
                    geometry.originX,
                )
                assertEquals(
                    "origin y $where",
                    (case.height - layout.height) / 2,
                    geometry.originY,
                )
            }

            // The exact board size picks the same tile back.
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Range.computeSize(Range.decodeParams(params), tile)
                val layout = oracle.layout(start.state, board.width, board.height)
                assertEquals("tile $tile for '$params'", board.width, layout.width)
                assertEquals("tile $tile for '$params'", board.height, layout.height)
                assertEquals(
                    "tile $tile for '$params'",
                    tile,
                    Midend.newGame(Range, params, SEED)
                        .geometryFor(board.width, board.height).tileSize,
                )
            }
        }
    }

    @Test
    fun `a click cycles black, dot, empty and the right button starts with a dot`() =
        OracleClient().use { oracle ->
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Range.computeSize(Range.decodeParams("9x6"), tile)
                val start = oracle.newGame("range", "9x6", SEED)
                val midend = Midend.fromSave(Range, SgtpSave.parse(start.state))
                val (x, y) = cellPoint(midend, 2, 0, tile)
                val where = "tile $tile at ($x,$y)"

                val left = listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                )
                val expectedLeft = oracle.pointsSized(
                    start.state,
                    left,
                    board.width,
                    board.height,
                )
                // Play the cycle one click at a time. Every click is a
                // real move: it changes the cell, adds one MOVE, and
                // adds one state.
                val leftValues = listOf(Range.BLACK, Range.WHITE, Range.EMPTY)
                val leftMoves = listOf("B,0,2", "W,0,2", "E,0,2")
                for (i in leftValues.indices) {
                    val before = midend.serialise().serialise()
                    assertEquals(
                        "left click ${i + 1} $where",
                        KeyOutcome.SomeEffect,
                        midend.processKey(x, y, Buttons.LEFT_BUTTON, tile),
                    )
                    assertEquals(
                        "left click ${i + 1} grid $where",
                        leftValues[i],
                        (midend.state as Range.State).grid[2],
                    )
                    assertNotEquals(
                        "left click ${i + 1} save $where",
                        before,
                        midend.serialise().serialise(),
                    )
                    assertEquals(
                        "left click ${i + 1} moves $where",
                        leftMoves.subList(0, i + 1),
                        SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
                    )
                    assertEquals(
                        "left click ${i + 1} states $where",
                        2 + i,
                        midend.stateCount,
                    )
                }
                assertEquals("left cycle $where", expectedLeft.state.state,
                    midend.serialise().serialise())

                val rightMidend = Midend.fromSave(Range, SgtpSave.parse(start.state))
                val right = listOf(
                    OracleClient.Point(x, y, Buttons.RIGHT_BUTTON),
                    OracleClient.Point(x, y, Buttons.RIGHT_BUTTON),
                    OracleClient.Point(x, y, Buttons.RIGHT_BUTTON),
                )
                val expectedRight = oracle.pointsSized(
                    start.state,
                    right,
                    board.width,
                    board.height,
                )
                val rightValues = listOf(Range.WHITE, Range.BLACK, Range.EMPTY)
                val rightMoves = listOf("W,0,2", "B,0,2", "E,0,2")
                for (i in rightValues.indices) {
                    val before = rightMidend.serialise().serialise()
                    assertEquals(
                        "right click ${i + 1} $where",
                        KeyOutcome.SomeEffect,
                        rightMidend.processKey(x, y, Buttons.RIGHT_BUTTON, tile),
                    )
                    assertEquals(
                        "right click ${i + 1} grid $where",
                        rightValues[i],
                        (rightMidend.state as Range.State).grid[2],
                    )
                    assertNotEquals(
                        "right click ${i + 1} save $where",
                        before,
                        rightMidend.serialise().serialise(),
                    )
                    assertEquals(
                        "right click ${i + 1} moves $where",
                        rightMoves.subList(0, i + 1),
                        SgtpSave.parse(rightMidend.serialise().serialise()).values("MOVE"),
                    )
                    assertEquals(
                        "right click ${i + 1} states $where",
                        2 + i,
                        rightMidend.stateCount,
                    )
                }
                assertEquals("right cycle $where", expectedRight.state.state,
                    rightMidend.serialise().serialise())

                // Vacuity guard: a fourth left click starts the cycle
                // again and adds one more state.
                val before = midend.serialise().serialise()
                assertEquals(
                    KeyOutcome.SomeEffect,
                    midend.processKey(x, y, Buttons.LEFT_BUTTON, tile),
                )
                assertNotEquals(before, midend.serialise().serialise())
                assertEquals(5, midend.stateCount)
                assertEquals(Range.BLACK, (midend.state as Range.State).grid[2])
            }
        }

    @Test
    fun `clue, border, and middle clicks are no-moves`() = OracleClient().use { oracle ->
        val save = oracle.newGame("range", "9x6", SEED).state

        // The click bands of `range.c:1360-1362` at tile 48 in the
        // 480x336 board.
        val bands = listOf(
            Triple(120, 24, Buttons.LEFT_BUTTON),
            Triple(119, 24, Buttons.LEFT_BUTTON),
            Triple(120, 72, Buttons.LEFT_BUTTON),
            Triple(120, 71, Buttons.LEFT_BUTTON),
            Triple(0, 0, Buttons.LEFT_BUTTON),
            Triple(468, 24, Buttons.LEFT_BUTTON),
            Triple(24, 24, Buttons.LEFT_BUTTON),
        )
        // Three band points are real moves and the rest are no-moves.
        // The saved state is the same as C's in every case.
        for ((x, y, button) in bands) {
            val expected = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(x, y, button)),
                480,
                336,
            )
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            val gridBefore = (midend.state as Range.State).grid.copyOf()
            val outcome = midend.processKey(x, y, button, 48)
            val inCell = x == 120 && (y == 24 || y == 71 || y == 72)
            assertEquals("($x,$y) outcome", inCell, outcome == KeyOutcome.SomeEffect)
            assertEquals("($x,$y) state count", if (inCell) 2 else 1, midend.stateCount)
            assertEquals("($x,$y) C save", expected.state.state,
                midend.serialise().serialise())

            // Vacuity guard: the three in-cell clicks change the grid,
            // every other band changes neither the grid nor the history.
            val gridAfter = (midend.state as Range.State).grid
            if (inCell) {
                assertTrue("($x,$y) changes the grid", !gridBefore.contentEquals(gridAfter))
            } else {
                assertTrue("($x,$y) keeps the grid", gridBefore.contentEquals(gridAfter))
            }
            assertEquals(
                "($x,$y) clue or border",
                if (inCell) 1 else 0,
                SgtpSave.parse(midend.serialise().serialise()).values("MOVE").size,
            )
        }

        // A clue click with each button: no move, but the cursor moves
        // and the ring hides.
        val tile = 32
        val clueMidend = Midend.fromSave(Range, SgtpSave.parse(save))
        val ui = clueMidend.ui as Range.Ui
        ui.cursorShow = true
        for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON, Buttons.MIDDLE_BUTTON)) {
            val gridBefore = (clueMidend.state as Range.State).grid.copyOf()
            val outcome = clueMidend.processKey(24, 24, button, tile)
            assertEquals("clue click $button", KeyOutcome.Unused, outcome)
            assertEquals("clue click $button state", 1, clueMidend.stateCount)
            assertTrue(
                "clue click $button keeps the grid",
                gridBefore.contentEquals((clueMidend.state as Range.State).grid),
            )
            assertEquals("clue click $button cursor", "0,0", "${ui.r},${ui.c}")
            assertEquals("clue click $button hides the ring", false, ui.cursorShow)
            ui.cursorShow = true
        }

        // A middle click on an empty cell is also a no-move, and it
        // moves the cursor and hides the ring.
        val middle = Midend.fromSave(Range, SgtpSave.parse(save))
        val middleUi = middle.ui as Range.Ui
        middleUi.cursorShow = true
        val middleGrid = (middle.state as Range.State).grid.copyOf()
        assertEquals(
            KeyOutcome.Unused,
            middle.processKey(96, 32, Buttons.MIDDLE_BUTTON, tile),
        )
        assertEquals("a middle click adds no state", 1, middle.stateCount)
        assertEquals("a middle click moves the cursor", "0,2", "${middleUi.r},${middleUi.c}")
        assertTrue("a middle click hides the ring", !middleUi.cursorShow)
        assertTrue(
            "a middle click keeps the grid",
            middleGrid.contentEquals((middle.state as Range.State).grid),
        )

        // The click moved the cursor, so DOWN DOWN Enter paints (1,0).
        val chain = Midend.fromSave(Range, SgtpSave.parse(save))
        val points = listOf(
            OracleClient.Point(24, 24, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
            OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
            OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
        )
        val expected = oracle.pointsSized(save, points)
        play(chain, points, tile)
        assertEquals("click then keys", expected.state.state, chain.serialise().serialise())
        assertEquals(
            listOf("B,1,0"),
            SgtpSave.parse(chain.serialise().serialise()).values("MOVE"),
        )
    }

    @Test
    fun `a press, drag, and release makes one move at the press`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("range", "9x6", SEED).state
            val midend = Midend.fromSave(Range, SgtpSave.parse(save))
            val (x0, y0) = cellPoint(midend, 2, 0, 32)
            val (x1, y1) = cellPoint(midend, 3, 1, 32)
            val points = listOf(
                OracleClient.Point(x0, y0, Buttons.LEFT_BUTTON),
                OracleClient.Point(x1, y1, Buttons.LEFT_DRAG),
                OracleClient.Point(x1, y1, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points)
            play(midend, points, 32)
            assertEquals("press frame", expected.state.state, midend.serialise().serialise())
            assertEquals(
                listOf("B,0,2"),
                SgtpSave.parse(midend.serialise().serialise()).values("MOVE"),
            )

            // A lone drag or release does nothing.
            for (action in listOf(Buttons.LEFT_DRAG, Buttons.LEFT_RELEASE)) {
                val lone = Midend.fromSave(Range, SgtpSave.parse(save))
                assertEquals(
                    KeyOutcome.Unused,
                    lone.processKey(x0, y0, action, 32),
                )
                assertEquals("lone $action", save, lone.serialise().serialise())
            }

            // A drag after a press but no release adds one move only.
            val dragOnly = Midend.fromSave(Range, SgtpSave.parse(save))
            val dragPoints = listOf(
                OracleClient.Point(x0, y0, Buttons.LEFT_BUTTON),
                OracleClient.Point(x1, y1, Buttons.LEFT_DRAG),
            )
            val dragExpected = oracle.pointsSized(save, dragPoints)
            play(dragOnly, dragPoints, 32)
            assertEquals("drag only", dragExpected.state.state, dragOnly.serialise().serialise())
        }

    @Test
    fun `cursor, keyboard, and shift paint match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("range", "9x6", SEED).state

        // An arrow with a hidden cursor reveals the ring and does not
        // move; a second arrow moves.
        val reveal = listOf(
            OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
            OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
            OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
        )
        assertEquals(
            "reveal and paint",
            oracle.keys(save, reveal.map { it.button }).state,
            playKeys(save, reveal.map { it.button }),
        )
        val revealed = Midend.fromSave(Range, SgtpSave.parse(save))
        val ui = revealed.ui as Range.Ui
        assertEquals(
            KeyOutcome.SomeEffect,
            revealed.processKey(0, 0, Buttons.CURSOR_DOWN, 32),
        )
        assertTrue(ui.cursorShow)
        assertEquals("0,0", "${ui.r},${ui.c}")

        // An arrow at the top edge reports an update but does not move.
        assertEquals(
            KeyOutcome.SomeEffect,
            revealed.processKey(0, 0, Buttons.CURSOR_UP, 32),
        )
        assertEquals("0,0", "${ui.r},${ui.c}")

        // Enter is backwards and Space is forwards on the empty (1,0).
        val enterKeys = listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_DOWN, '\n'.code)
        assertEquals(
            oracle.keys(save, enterKeys).state,
            playKeys(save, enterKeys),
        )
        val enter = Midend.fromSave(Range, SgtpSave.parse(save))
        enter.processKey(0, 0, Buttons.CURSOR_DOWN, 32)
        enter.processKey(0, 0, Buttons.CURSOR_DOWN, 32)
        assertEquals(KeyOutcome.SomeEffect, enter.processKey(0, 0, '\n'.code, 32))
        assertEquals(listOf("B,1,0"), SgtpSave.parse(enter.serialise().serialise()).values("MOVE"))
        val space = Midend.fromSave(Range, SgtpSave.parse(save))
        space.processKey(0, 0, Buttons.CURSOR_DOWN, 32)
        space.processKey(0, 0, Buttons.CURSOR_DOWN, 32)
        assertEquals(KeyOutcome.SomeEffect, space.processKey(0, 0, ' '.code, 32))
        assertEquals(listOf("W,1,0"), SgtpSave.parse(space.serialise().serialise()).values("MOVE"))

        // A hidden ring makes a select unused and a CTRL+arrow does not
        // even reveal it.
        val hidden = Midend.fromSave(Range, SgtpSave.parse(save))
        assertEquals(KeyOutcome.Unused, hidden.processKey(0, 0, Buttons.CURSOR_SELECT, 32))
        assertTrue(!(hidden.ui as Range.Ui).cursorShow)
        assertEquals(
            KeyOutcome.Unused,
            hidden.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, 32),
        )
        assertTrue("CTRL+arrow keeps the ring hidden", !(hidden.ui as Range.Ui).cursorShow)
        assertEquals(
            oracle.keys(save, listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)).state,
            hidden.serialise().serialise(),
        )

        // A keypad digit is not a range key.
        val digit = Midend.fromSave(Range, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.Unused,
            digit.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '1'.code, 32),
        )

        // Shift+arrow with a hidden ring only reveals.
        val paint = Midend.fromSave(Range, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            paint.processKey(0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN, 32),
        )
        assertEquals(1, paint.stateCount)
        // From the clue (0,0) to the empty (1,0): one white cell.
        assertEquals(
            KeyOutcome.SomeEffect,
            paint.processKey(0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN, 32),
        )
        // From the white (1,0) to the empty (2,0): one white cell.
        assertEquals(
            KeyOutcome.SomeEffect,
            paint.processKey(0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN, 32),
        )
        // From the white (2,0) to the empty (3,0): one white cell.
        assertEquals(
            KeyOutcome.SomeEffect,
            paint.processKey(0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN, 32),
        )
        assertEquals(
            listOf("W,1,0", "W,2,0", "W,3,0"),
            SgtpSave.parse(paint.serialise().serialise()).values("MOVE"),
        )
        assertEquals(
            oracle.keys(
                save,
                List(4) { Buttons.MOD_SHFT or Buttons.CURSOR_DOWN },
            ).state,
            paint.serialise().serialise(),
        )

        // A shift+arrow where both cells are non-empty is a UI update.
        // The sequence reveals the ring, walks to (8,0), paints it with
        // a shift towards the clue (7,0), then points from the clue back
        // at the white cell.
        val bothKeys = listOf(
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
            Buttons.MOD_SHFT or Buttons.CURSOR_LEFT,
            Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
        )
        assertEquals(
            "both cells non-empty",
            oracle.keys(save, bothKeys).state,
            playKeys(save, bothKeys),
        )
        val both = Midend.fromSave(Range, SgtpSave.parse(save))
        for (key in bothKeys) both.processKey(0, 0, key, 32)
        assertEquals("the paint adds one state", 2, both.stateCount)
        assertEquals(
            listOf("W,0,8"),
            SgtpSave.parse(both.serialise().serialise()).values("MOVE"),
        )
    }

    @Test
    fun `a letterboxed view keeps one geometry for every preset`() = OracleClient().use { oracle ->
        var nonZeroOrigin = false
        for (params in listOf("9x6", "12x8", "13x9", "16x11")) {
            val start = oracle.newGame("range", params, SEED)
            val midend = Midend.fromSave(Range, SgtpSave.parse(start.state))
            val geometry = midend.geometryFor(411, 702)
            val tile = geometry.tileSize
            val paramsValue = Range.decodeParams(params)
            val board = Range.computeSize(paramsValue, tile)

            assertEquals("$params board width", geometry.width, board.width)
            assertEquals("$params board height", geometry.height, board.height)
            assertEquals("$params tile", midend.largestTileFor(411, 702), tile)
            if (geometry.originX != 0 || geometry.originY != 0) nonZeroOrigin = true

            // Click the first empty cell through the view origin.
            val state = midend.state as Range.State
            val (cx, cy) = firstEmptyCell(state)
            val viewX = geometry.originX + cx * tile + tile / 2
            val viewY = geometry.originY + cy * tile + tile / 2
            val expected = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
                411,
                702,
            )
            val gridBefore = (midend.state as Range.State).grid.copyOf()
            midend.processKey(
                geometry.toGameX(viewX),
                geometry.toGameY(viewY),
                Buttons.LEFT_BUTTON,
                tile,
            )
            assertEquals("$params letterbox click", expected.state.state,
                midend.serialise().serialise())
            assertEquals("$params one move", 1, midend.stateCount - 1)
            assertTrue(
                "$params letterbox click changes the grid",
                !gridBefore.contentEquals((midend.state as Range.State).grid),
            )

            // A click outside the board is unused and changes nothing.
            val outside = Midend.fromSave(Range, SgtpSave.parse(start.state))
            val before = outside.serialise().serialise()
            val outsideGrid = (outside.state as Range.State).grid.copyOf()
            assertEquals(
                KeyOutcome.Unused,
                outside.processKey(
                    -geometry.originX,
                    -geometry.originY,
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
            )
            assertEquals("$params letterbox outside", before, outside.serialise().serialise())
            assertTrue(
                "$params letterbox outside keeps the grid",
                outsideGrid.contentEquals((outside.state as Range.State).grid),
            )
        }
        assertTrue("at least one letterbox origin is nonzero", nonZeroOrigin)
    }

    /**
     * `range.c:1368-1375` swaps the two mouse buttons, not the actions,
     * and only for `LEFT_BUTTON`/`RIGHT_BUTTON`. The oracle's
     * `pointsSized` cannot apply a preference, so the rule is pinned on
     * the engine side.
     */
    @Test
    fun `the mouse button order preference swaps the buttons`() {
        val midend = Midend.newGame(Range, "9x6", SEED)
        val ui = midend.ui as Range.Ui
        val tile = 32
        val x = 96
        val y = 32 // cell (0,2), empty

        assertEquals("default left", "B,0,2", clickMove(midend, x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals("default right", "W,0,2", clickMove(midend, x, y, Buttons.RIGHT_BUTTON, tile))
        ui.swapButtons = true
        assertEquals("swapped left", "W,0,2", clickMove(midend, x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals("swapped right", "B,0,2", clickMove(midend, x, y, Buttons.RIGHT_BUTTON, tile))
        // The labels do not consult the preference.
        ui.cursorShow = true
        ui.r = 0
        ui.c = 2
        assertEquals("Fill", Range.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Dot", Range.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))
    }

    /**
     * Clicks a fresh copy of the game at (0,2), keeping the UI that the
     * test configured, and returns the new move.
     */
    private fun clickMove(
        midend: Midend,
        x: Int,
        y: Int,
        button: Int,
        tile: Int,
    ): String {
        val swap = (midend.ui as Range.Ui).swapButtons
        val fresh = Midend.newGame(Range, "9x6", SEED)
        (fresh.ui as Range.Ui).swapButtons = swap
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(x, y, button, tile))
        return SgtpSave.parse(fresh.serialise().serialise()).values("MOVE").single()
    }

    private fun play(midend: Midend, points: List<OracleClient.Point>, tile: Int) {
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, tile)
        }
    }

    private fun playKeys(state: String, sequence: List<Int>): String {
        val midend = Midend.fromSave(Range, SgtpSave.parse(state))
        for (key in sequence) midend.processKey(0, 0, key, 32)
        return midend.serialise().serialise()
    }

    /** The game coordinates of the centre of cell (x, y). */
    private fun cellPoint(midend: Midend, x: Int, y: Int, tile: Int): Pair<Int, Int> =
        Pair(tile / 2 + tile * x + tile / 2, tile / 2 + tile * y + tile / 2)

    private fun firstEmptyCell(state: Range.State): Pair<Int, Int> {
        for (r in 0 until state.height) {
            for (c in 0 until state.width) {
                if (state.grid[r * state.width + c] == Range.EMPTY) return Pair(c, r)
            }
        }
        error("the 9x6 clue board always has an empty cell")
    }

    private data class Viewport(val label: String, val width: Int, val height: Int)

    private companion object {
        const val SEED = "12345"
    }
}
