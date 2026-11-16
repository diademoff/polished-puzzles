package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: untangle.
 *
 * The layout, a real click on every point, a drag that leaves the board,
 * the snap preference through the keyboard drag, the four arrow
 * quadrants, the cursor side effects, the space cycle, modified keys,
 * and the vacuous centre, letterbox, drag-without-press,
 * release-without-press, and letter cases. Every real move changes the
 * board and adds one state; every vacuous event changes neither.
 */
class UntangleInputParityTest {

    private data class View(
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private val views = listOf(
        View(240, 240, 48, 0, 0),
        View(315, 315, 63, 0, 0),
        View(410, 410, 82, 0, 0),
        View(500, 500, 100, 0, 0),
        // 411x702 gives tile 82, a 410x410 board, and origin (0, 146).
        View(411, 702, 82, 0, 146),
    )

    @Test
    fun `the layout matches the oracle at five viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            for (view in views) {
                val layout = oracle.layout(save, view.viewWidth, view.viewHeight)
                val geometry = Midend.newGame(Untangle, "10", SEED)
                    .geometryFor(view.viewWidth, view.viewHeight)
                val label = "${view.viewWidth}x${view.viewHeight}"
                assertEquals("tile at $label", view.tile, geometry.tileSize)
                assertEquals("width at $label", layout.width, geometry.width)
                assertEquals("height at $label", layout.height, geometry.height)
                assertEquals("origin x at $label", view.originX, geometry.originX)
                assertEquals("origin y at $label", view.originY, geometry.originY)
                assertTrue("the board is square at $label", layout.width == layout.height)
            }
        }

    @Test
    fun `a click on every point is one real move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "10", SEED).state
        val reference = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (index in 0 until 10) {
            val (x, y) = pixelOf(reference.state, index, 64)
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points, width = 320, height = 320)
            assertEquals("one move for point $index", 1, movesOf(expected.state.state).size)

            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (p in points) {
                assertEquals(
                    "the click is a real move on point $index",
                    KeyOutcome.SomeEffect,
                    midend.processKey(p.x, p.y, p.button, 64),
                )
            }
            // A click on the point's exact pixel can reproduce the same
            // rational, so the state may compare equal. C still adds a
            // history entry, and that is the change.
            assertEquals("one state for point $index", 2, midend.stateCount)
            assertEquals(
                "the exact move text for point $index",
                "P$index:$x,$y/64",
                movesOf(midend.serialise().serialise()).last(),
            )
            assertSame("point $index", expected.state, midend)
        }
    }

    @Test
    fun `a drag that leaves the board cancels`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "10", SEED).state
        val reference = Midend.fromSave(Untangle, SgtpSave.parse(save))
        val (px, py) = pixelOf(reference.state, 0, 64)

        val cancelled = listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(-100, -100, Buttons.LEFT_DRAG),
            OracleClient.Point(-100, -100, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, cancelled, width = 320, height = 320)
        assertEquals("the oracle cancels", 0, movesOf(expected.state.state).size)
        val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (p in cancelled) midend.processKey(p.x, p.y, p.button, 64)
        assertEquals("no move", 1, midend.stateCount)
        assertSame("cancelled drag", expected.state, midend)

        // The positive control: the same drag inside the board commits.
        val committed = listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px + 80, py + 80, Buttons.LEFT_DRAG),
            OracleClient.Point(px + 80, py + 80, Buttons.LEFT_RELEASE),
        )
        val expectedMove = oracle.pointsSized(save, committed, width = 320, height = 320)
        assertEquals("the oracle commits", 1, movesOf(expectedMove.state.state).size)
        val moveMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (p in committed) moveMidend.processKey(p.x, p.y, p.button, 64)
        assertEquals("one state", 2, moveMidend.stateCount)
        assertSame("committed drag", expectedMove.state, moveMidend)
    }

    @Test
    fun `the snap preference changes the keyboard drag`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "10", SEED).state
        val drag = listOf(
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )

        for (snap in listOf(false, true)) {
            val prefs = if (snap) "snap-to-grid=true" else "snap-to-grid=false"
            val expected = oracle.keys(save, drag, prefs)
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            (midend.ui as Untangle.Ui).snapToGrid = snap
            for (key in drag) midend.processKey(0, 0, key, 64)
            assertSame("snap $snap", expected, midend)
            assertEquals("one move, snap $snap", 1, movesOf(
                midend.serialise().serialise(),
            ).size)
            if (snap) {
                assertTrue(
                    "the snapped denominator is 2*(n-1)",
                    movesOf(midend.serialise().serialise()).last().endsWith("/18"),
                )
            } else {
                assertTrue(
                    "the free denominator is the tile size",
                    movesOf(midend.serialise().serialise()).last().endsWith("/64"),
                )
            }
        }
    }

    @Test
    fun `the four arrow quadrants pick the nearest point`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            for (direction in listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_RIGHT,
            )) {
                val sequence = listOf(
                    direction,
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_LEFT,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_SELECT,
                )
                val expected = oracle.keys(save, sequence)
                val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
                for (key in sequence) midend.processKey(0, 0, key, 64)
                assertSame("direction $direction", expected, midend)
            }
        }

    @Test
    fun `the failed first arrow keeps cursorpoint zero`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val sequence = listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            )
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertEquals(
                "up from point 0 has no candidate",
                KeyOutcome.Unused,
                midend.processKey(0, 0, Buttons.CURSOR_UP, 64),
            )
            assertEquals("the side effect remains", 0, (midend.ui as Untangle.Ui).cursorPoint)
            for (key in sequence.drop(1)) midend.processKey(0, 0, key, 64)
            assertSame("failed first arrow", expected, midend)
            assertEquals(
                "the drag started on point 0",
                true,
                movesOf(midend.serialise().serialise()).last().startsWith("P0:"),
            )
        }

    @Test
    fun `select with no cursor shows it and the next select drags it`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val sequence = listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            )
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertEquals(
                "the first select shows the cursor",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_SELECT, 64),
            )
            assertEquals("no state yet", 1, midend.stateCount)
            assertEquals(0, (midend.ui as Untangle.Ui).cursorPoint)
            for (key in sequence.drop(1)) midend.processKey(0, 0, key, 64)
            assertSame("select with no cursor", expected, midend)
            assertEquals("one move", 2, midend.stateCount)
        }

    @Test
    fun `space and shift tab cycle the cursor`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "10", SEED).state
        // Select shows point 0, space moves to point 1, the next select
        // begins a drag there.
        val forward = listOf(
            Buttons.CURSOR_SELECT,
            ' '.code,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        val forwardExpected = oracle.keys(save, forward)
        val forwardMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (key in forward) forwardMidend.processKey(0, 0, key, 64)
        assertSame("space cycle", forwardExpected, forwardMidend)
        assertTrue(
            "the space selected point 1",
            movesOf(forwardMidend.serialise().serialise()).last().startsWith("P1:"),
        )

        // SHFT+TAB wraps from point 0 to point n-1.
        val backward = listOf(
            Buttons.CURSOR_SELECT,
            Buttons.MOD_SHFT or '\t'.code,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        val backwardExpected = oracle.keys(save, backward)
        val backwardMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (key in backward) backwardMidend.processKey(0, 0, key, 64)
        assertSame("shift tab cycle", backwardExpected, backwardMidend)
        assertTrue(
            "the cycle wraps to point 9",
            movesOf(backwardMidend.serialise().serialise()).last().startsWith("P9:"),
        )
    }

    @Test
    fun `a modified arrow is unused and a plain one works`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            for (modified in listOf(
                Buttons.MOD_CTRL or Buttons.CURSOR_UP,
                Buttons.MOD_SHFT or Buttons.CURSOR_UP,
            )) {
                val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
                assertEquals(
                    "a modified arrow is unused",
                    KeyOutcome.Unused,
                    midend.processKey(0, 0, modified, 64),
                )
                assertEquals(
                    "the cursor did not move",
                    -1,
                    (midend.ui as Untangle.Ui).cursorPoint,
                )
                assertEquals("no state", 1, midend.stateCount)
            }

            // The positive control: the same key without a modifier.
            // The cursor starts on point 0, so down has a candidate.
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_DOWN, 64),
            )
            assertTrue("the cursor is set", (midend.ui as Untangle.Ui).cursorPoint >= 0)
        }

    @Test
    fun `vacuous pointer and key events change neither the board nor the history`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state

            // The board centre is far from every point.
            val centre = listOf(
                OracleClient.Point(160, 160, Buttons.LEFT_BUTTON),
                OracleClient.Point(160, 160, Buttons.LEFT_RELEASE),
            )
            val centreExpected = oracle.pointsSized(save, centre, width = 320, height = 320)
            assertEquals("the oracle is inert", 0, movesOf(centreExpected.state.state).size)
            val centreMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            val before = centreMidend.state
            for (p in centre) {
                assertEquals(
                    "the centre press is inert",
                    KeyOutcome.NoEffect,
                    centreMidend.processKey(p.x, p.y, p.button, 64),
                )
            }
            assertEquals("no move", 1, centreMidend.stateCount)
            assertEquals("no state", before, centreMidend.state)

            // The positive control: a click on point 0.
            val (px, py) = pixelOf(centreMidend.state, 0, 64)
            centreMidend.processKey(px, py, Buttons.LEFT_BUTTON, 64)
            centreMidend.processKey(px, py, Buttons.LEFT_RELEASE, 64)
            assertEquals("the control moves", 2, centreMidend.stateCount)

            // A letterbox click with the origin subtracted.
            val geometry = Midend.newGame(Untangle, "10", SEED).geometryFor(411, 702)
            assertEquals(
                KeyOutcome.NoEffect,
                Midend.fromSave(Untangle, SgtpSave.parse(save))
                    .processKey(-geometry.originX, -geometry.originY, Buttons.LEFT_BUTTON, geometry.tileSize),
            )
            // The guard: the same view point inside the board is a hit.
            val guard = Midend.fromSave(Untangle, SgtpSave.parse(save))
            val (gx, gy) = pixelOf(guard.state, 0, geometry.tileSize)
            assertEquals(
                KeyOutcome.SomeEffect,
                guard.processKey(gx, gy, Buttons.LEFT_BUTTON, geometry.tileSize),
            )

            // A drag and a release with no press.
            val noPress = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.Unused,
                noPress.processKey(100, 100, Buttons.LEFT_DRAG, 64),
            )
            assertEquals(
                KeyOutcome.Unused,
                noPress.processKey(100, 100, Buttons.LEFT_RELEASE, 64),
            )
            assertEquals("no state without a press", 1, noPress.stateCount)

            // A letter and a digit are not game moves.
            for (key in listOf('q'.code, '5'.code, '\b'.code)) {
                val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
                assertEquals(
                    "key $key is unused",
                    KeyOutcome.Unused,
                    midend.processKey(0, 0, key, 64),
                )
                assertEquals("no state for key $key", 1, midend.stateCount)
            }
        }

    @Test
    fun `a middle button press is a real move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "10", SEED).state
        val reference = Midend.fromSave(Untangle, SgtpSave.parse(save))
        val (px, py) = pixelOf(reference.state, 0, 64)
        val points = listOf(
            OracleClient.Point(px, py, Buttons.MIDDLE_BUTTON),
            OracleClient.Point(px, py, Buttons.MIDDLE_RELEASE),
        )
        val expected = oracle.pointsSized(save, points, width = 320, height = 320)
        assertEquals("the oracle accepts the middle button", 1, movesOf(expected.state.state).size)
        val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (p in points) midend.processKey(p.x, p.y, p.button, 64)
        assertEquals("one state", 2, midend.stateCount)
        assertSame("middle button", expected.state, midend)
    }

    @Test
    fun `the cursor ring follows the highlighted point`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertNull("no cursor at first", midend.cursorLocation(64))

            midend.processKey(0, 0, Buttons.CURSOR_SELECT, 64)
            val ring = midend.cursorLocation(64)
            assertTrue("the ring is visible", ring != null)
            val (px, py) = pixelOf(midend.state, 0, 64)
            assertEquals("ring left", px - 6, ring!!.left)
            assertEquals("ring top", py - 6, ring.top)
            assertEquals("ring width", 13, ring.width)
            assertEquals("ring height", 13, ring.height)

            // A keyboard drag ring follows the stored pixel. The
            // arrows move it by tile/2 = 32 pixels.
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, 64)
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 64)
            val dragRing = midend.cursorLocation(64)
            assertEquals("drag ring left", px + 32 - 6, dragRing!!.left)
            assertEquals("drag ring top", py - 6, dragRing.top)
        }

    private fun pixelOf(state: name.boyle.chris.sgtpuzzles.engine.Game.State, index: Int, tile: Int): Pair<Int, Int> {
        val point = (state as Untangle.State).point(index)
        return (point.x * tile / point.d).toInt() to (point.y * tile / point.d).toInt()
    }

    private fun movesOf(state: String): List<String> = SgtpSave.parse(state).values("MOVE")

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"
    }
}
