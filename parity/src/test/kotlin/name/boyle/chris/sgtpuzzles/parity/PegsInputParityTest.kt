package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: pegs.
 *
 * The layout, a real press-drag-release at several tile sizes, taps,
 * cancelled drags, and the keyboard jump.
 *
 * pegs moves only on a release exactly two cells away on a hole. A tap
 * cancels silently, so every "no move" case also asserts that the press
 * was accepted (a drag started, or the press was a valid no-op), so the
 * test cannot pass when the press path is missing.
 */
class PegsInputParityTest {

    @Test
    fun `the layout matches the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            Triple("5x7cross", 411, 702),
            Triple("5x7cross", 720, 1280),
            Triple("5x7cross", 1024, 600),
            Triple("5x7cross", 200, 200),
            Triple("5x7cross", 400, 400),
            Triple("9x9cross", 411, 702),
            Triple("7x7octagon", 720, 1280),
        )
        for ((params, viewWidth, viewHeight) in cases) {
            val midend = Midend.newGame(Pegs, params, SEED)
            val layout = oracle.layout(midend.serialise().serialise(), viewWidth, viewHeight)
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            val where = "'$params' in $viewWidth x $viewHeight"
            assertEquals("width $where", layout.width, geometry.width)
            assertEquals("height $where", layout.height, geometry.height)
            assertEquals("origin x $where", (viewWidth - geometry.width) / 2, geometry.originX)
            assertEquals("origin y $where", (viewHeight - geometry.height) / 2, geometry.originY)
        }

        // Exact-fit viewports for the four tile sizes in the test plan.
        // `computeSize` is `T*w + 2*(T/2)`, so an odd tile keeps one
        // pixel of border on each side.
        for ((tile, view) in exactFits()) {
            val midend = Midend.newGame(Pegs, "5x7cross", SEED)
            val geometry = midend.geometryFor(view.first, view.second)
            assertEquals("tile for $view", tile, geometry.tileSize)
            assertEquals("origin x for $view", 0, geometry.originX)
            assertEquals("origin y for $view", 0, geometry.originY)
            assertEquals(tile * 5 + 2 * (tile / 2), geometry.width)
            assertEquals(tile * 7 + 2 * (tile / 2), geometry.height)
        }

        // A square viewport is letterboxed on both axes.
        val letterbox = Midend.newGame(Pegs, "5x7cross", SEED).geometryFor(300, 300)
        assertTrue(letterbox.originX > 0 && letterbox.originY > 0)
    }

    @Test
    fun `a real drag moves a peg at every tile size`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        for ((tile, view) in exactFits()) {
            val (viewWidth, viewHeight) = view
            val x0 = viewX(2, tile)
            val y0 = viewY(1, tile)
            val x1 = viewX(2, tile)
            val y1 = viewY(3, tile)

            val expected = oracle.gestureSized(
                start.state,
                x0,
                y0,
                x1,
                y1,
                STEPS,
                viewWidth,
                viewHeight,
            )
            val midend = Midend.newGame(Pegs, "5x7cross", SEED)
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            assertEquals(tile, geometry.tileSize)

            var outcome = midend.processKey(
                x0 - geometry.originX,
                y0 - geometry.originY,
                Buttons.LEFT_BUTTON,
                tile,
            )
            assertEquals("the press at tile $tile", KeyOutcome.SomeEffect, outcome)
            assertTrue(
                "the press starts a drag at tile $tile",
                (midend.ui as Pegs.Ui).dragging,
            )
            for (i in 1..STEPS) {
                outcome = midend.processKey(
                    x0 + (x1 - x0) * i / STEPS - geometry.originX,
                    y0 + (y1 - y0) * i / STEPS - geometry.originY,
                    Buttons.LEFT_DRAG,
                    tile,
                )
                assertEquals("the drag at tile $tile", KeyOutcome.SomeEffect, outcome)
            }
            outcome = midend.processKey(
                x1 - geometry.originX,
                y1 - geometry.originY,
                Buttons.LEFT_RELEASE,
                tile,
            )

            assertEquals("the release at tile $tile", KeyOutcome.SomeEffect, outcome)
            assertFalse("the drag ends at tile $tile", (midend.ui as Pegs.Ui).dragging)
            assertEquals(
                "the drag adds one move at tile $tile",
                listOf("2,1-2,3"),
                midend.serialise().values("MOVE"),
            )
            assertEquals(
                "the board changed at tile $tile",
                oracle.text(expected.state),
                midend.textFormat(),
            )
            assertEquals(
                "the state at tile $tile",
                expected.state,
                midend.serialise().serialise(),
            )
        }
    }

    @Test
    fun `a tap never moves but the press is accepted`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val tile = Pegs.preferredTileSize
        val text = midend.textFormat()

        // A tap on a peg starts and cancels a drag.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(viewX(2, tile), viewY(1, tile), Buttons.LEFT_BUTTON, tile),
        )
        assertTrue("the press starts a drag", (midend.ui as Pegs.Ui).dragging)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(viewX(2, tile), viewY(1, tile), Buttons.LEFT_RELEASE, tile),
        )
        assertFalse("the release cancels the drag", (midend.ui as Pegs.Ui).dragging)

        // A tap on a hole is a valid no-op. A tap on an obstacle or on
        // the frame is unused. None starts a drag.
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(viewX(2, tile), viewY(3, tile), Buttons.LEFT_BUTTON, tile),
        )
        assertFalse((midend.ui as Pegs.Ui).dragging)
        midend.processKey(viewX(2, tile), viewY(3, tile), Buttons.LEFT_RELEASE, tile)

        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(viewX(0, tile), viewY(0, tile), Buttons.LEFT_BUTTON, tile),
        )
        assertFalse((midend.ui as Pegs.Ui).dragging)
        midend.processKey(viewX(0, tile), viewY(0, tile), Buttons.LEFT_RELEASE, tile)

        assertEquals(KeyOutcome.Unused, midend.processKey(4, 4, Buttons.LEFT_BUTTON, tile))

        // A press in the letterbox is unused.
        val letterboxed = Midend.newGame(Pegs, "5x7cross", SEED)
        val geometry = letterboxed.geometryFor(411, 702)
        assertTrue(geometry.originX > 0 || geometry.originY > 0)
        assertEquals(
            KeyOutcome.Unused,
            letterboxed.processKey(
                0 - geometry.originX,
                0 - geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )

        assertEquals("the taps change no state", text, midend.textFormat())
        assertEquals(emptyList<String>(), midend.serialise().values("MOVE"))
        assertEquals(1, midend.stateCount)

        // The oracle agrees: a press and release on one cell is no move.
        val expected = oracle.pointsSized(
            start.state,
            listOf(
                OracleClient.Point(viewX(2, tile), viewY(1, tile), Buttons.LEFT_BUTTON),
                OracleClient.Point(viewX(2, tile), viewY(1, tile), Buttons.LEFT_RELEASE),
            ),
            width = 197,
            height = 263,
        )
        assertEquals(start.state, expected.state.state)
        assertEquals(emptyList<String>(), midend.serialise().values("MOVE"))
    }

    @Test
    fun `a drag that is not exactly two cells changes nothing`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val tile = Pegs.preferredTileSize
        val invalidTargets = listOf(
            // One cell away.
            viewX(2, tile) to viewY(2, tile),
            // Three cells away.
            viewX(2, tile) to viewY(4, tile),
            // Diagonal.
            viewX(3, tile) to viewY(3, tile),
            // Off the board.
            -1 to -1,
        )
        for ((targetX, targetY) in invalidTargets) {
            val expected = oracle.gestureSized(
                start.state,
                viewX(2, tile),
                viewY(1, tile),
                targetX,
                targetY,
                STEPS,
                197,
                263,
            )
            val midend = Midend.newGame(Pegs, "5x7cross", SEED)
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(viewX(2, tile), viewY(1, tile), Buttons.LEFT_BUTTON, tile),
            )
            assertTrue((midend.ui as Pegs.Ui).dragging)
            midend.processKey(targetX, targetY, Buttons.LEFT_DRAG, tile)
            assertEquals(
                "a cancelled drag is a UI update",
                KeyOutcome.SomeEffect,
                midend.processKey(targetX, targetY, Buttons.LEFT_RELEASE, tile),
            )
            assertFalse((midend.ui as Pegs.Ui).dragging)
            assertEquals(
                "the cancelled drag at ($targetX,$targetY) changes nothing",
                start.state,
                midend.serialise().serialise(),
            )
            assertEquals(
                "the oracle cancels it too",
                start.state,
                expected.state,
            )
            assertEquals(emptyList<String>(), midend.serialise().values("MOVE"))
        }
    }

    @Test
    fun `the keyboard jump matches the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val sequence = listOf(
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_RIGHT,
        )
        val expected = oracle.keys(start.state, sequence)

        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_DOWN, Pegs.preferredTileSize),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, Pegs.preferredTileSize),
        )
        assertTrue("select arms a jump", (midend.ui as Pegs.Ui).curJumping)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, Pegs.preferredTileSize),
        )
        assertFalse("the jump is finished", (midend.ui as Pegs.Ui).curJumping)

        assertEquals(listOf("0,3-2,3"), midend.serialise().values("MOVE"))
        assertEquals(expected.state, midend.serialise().serialise())
        assertEquals(oracle.text(expected.state), midend.textFormat())
    }

    @Test
    fun `a second select cancels the jump`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val sequence = listOf(
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
        )
        val expected = oracle.keys(start.state, sequence)

        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        for (key in sequence) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, Pegs.preferredTileSize),
            )
        }
        assertFalse((midend.ui as Pegs.Ui).curJumping)
        assertEquals(emptyList<String>(), midend.serialise().values("MOVE"))
        assertEquals(start.state, midend.serialise().serialise())
        assertEquals(expected.state, midend.serialise().serialise())
    }

    @Test
    fun `a modified cursor key is unused`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val tile = Pegs.preferredTileSize

        for (key in listOf(
            Buttons.CURSOR_UP or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
        )) {
            assertEquals(
                "a modified cursor key is unused",
                KeyOutcome.Unused,
                midend.processKey(0, 0, key, tile),
            )
        }
        // The game compares the exact button, so the cursor did not move.
        val ui = midend.ui as Pegs.Ui
        assertEquals(0, ui.curX)
        assertEquals(2, ui.curY)
        assertFalse(ui.curVisible)

        val expected = oracle.keys(
            start.state,
            listOf(
                Buttons.CURSOR_UP or Buttons.MOD_SHFT,
                Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
            ),
        )
        assertEquals(start.state, expected.state)
        assertEquals(start.state, midend.serialise().serialise())
    }

    @Test
    fun `the cursor refuses an obstacle and clamps at the edge`() {
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val ui = midend.ui as Pegs.Ui
        val tile = Pegs.preferredTileSize

        // The cursor starts at (0,2). Down twice reaches (0,4); down
        // again would leave the board at (0,5), an obstacle, so the
        // cursor stays while becoming visible.
        midend.processKey(0, 0, Buttons.CURSOR_DOWN, tile)
        assertTrue(ui.curVisible)
        assertEquals(0, ui.curX)
        assertEquals(3, ui.curY)
        midend.processKey(0, 0, Buttons.CURSOR_DOWN, tile)
        assertEquals(0, ui.curX)
        assertEquals(4, ui.curY)
        midend.processKey(0, 0, Buttons.CURSOR_DOWN, tile)
        assertEquals("an obstacle is refused", 0, ui.curX)
        assertEquals("an obstacle is refused", 4, ui.curY)

        // The left edge clamps.
        midend.processKey(0, 0, Buttons.CURSOR_LEFT, tile)
        assertEquals(0, ui.curX)
    }

    private fun exactFits(): List<Pair<Int, Pair<Int, Int>>> = listOf(
        48 to (288 to 384),
        63 to (377 to 503),
        82 to (492 to 656),
        100 to (600 to 800),
    )

    /** The x centre of a cell in view coordinates for an exact-fit view. */
    private fun viewX(cell: Int, tile: Int): Int = tile / 2 + cell * tile + tile / 2

    private fun viewY(cell: Int, tile: Int): Int = tile / 2 + cell * tile + tile / 2

    private companion object {
        const val SEED = "12345"
        const val STEPS = 4
    }
}
