package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: signpost.
 *
 * The layout, real press-drag-release links at four tile sizes and at a
 * letterboxed origin on both axes, a right-button reverse drag, a release
 * outside the board that cuts, the four no-op shapes, keyboard moves and
 * shortcuts, and the whole-set cut. Every "no move" case also asserts
 * that the press reached the game, so a missing press path cannot pass.
 */
class SignpostInputParityTest {

    private companion object {
        const val SEED = "12345"
        const val STEPS = 4
        const val DESC = "1dcegcefebeghcbh16a"
    }

    private fun exactFits() = listOf(
        48 to (240 to 240),
        63 to (314 to 314),
        82 to (410 to 410),
        100 to (500 to 500),
    )

    private fun centre(cell: Int, tile: Int, border: Int): Int =
        border + cell * tile + tile / 2

    private fun cellPoint(x: Int, y: Int, tile: Int, border: Int): OracleClient.Point =
        OracleClient.Point(centre(x, tile, border), centre(y, tile, border), Buttons.LEFT_BUTTON)

    private fun newMidend(): Midend = Midend.newGame(Signpost, "4x4c", SEED)

    private fun points(vararg points: OracleClient.Point): List<OracleClient.Point> =
        points.toList()

    private fun send(midend: Midend, points: List<OracleClient.Point>, tile: Int): List<KeyOutcome> =
        points.map { midend.processKey(it.x, it.y, it.button, tile) }

    @Test
    fun `the layout matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        for ((viewWidth, viewHeight) in listOf(
            411 to 702,
            720 to 1280,
            1024 to 600,
            200 to 200,
            240 to 240,
            410 to 410,
        )) {
            val midend = newMidend()
            val layout = oracle.layout(save, viewWidth, viewHeight)
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            val where = "$viewWidth x $viewHeight"
            assertEquals("width $where", layout.width, geometry.width)
            assertEquals("height $where", layout.height, geometry.height)
            assertEquals("origin x $where", (viewWidth - geometry.width) / 2, geometry.originX)
            assertEquals("origin y $where", (viewHeight - geometry.height) / 2, geometry.originY)
        }

        // Exact fits for the four tiles of the test plan.
        for ((tile, view) in exactFits()) {
            val geometry = newMidend().geometryFor(view.first, view.second)
            assertEquals("tile for $view", tile, geometry.tileSize)
            assertEquals("origin x for $view", 0, geometry.originX)
            assertEquals("origin y for $view", 0, geometry.originY)
            assertEquals(tile * 4 + 2 * (tile / 2), geometry.width)
            assertEquals(tile * 4 + 2 * (tile / 2), geometry.height)
        }

        // The 411x702 view is a letterbox: tile 82 for 4x4c, tile 51
        // for 7x7c.
        val four = newMidend().geometryFor(411, 702)
        assertEquals(82, four.tileSize)
        assertEquals(0, four.originX)
        assertEquals(146, four.originY)
        val seven = Midend.newGame(Signpost, "7x7c", SEED).geometryFor(411, 702)
        assertEquals(51, seven.tileSize)
        assertEquals(2, seven.originX)
        assertEquals(147, seven.originY)
    }

    @Test
    fun `a real drag links at every tile size`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        for ((tile, view) in exactFits()) {
            val (viewWidth, viewHeight) = view
            val border = tile / 2
            val x0 = centre(0, tile, border)
            val y0 = centre(0, tile, border)
            val x1 = centre(1, tile, border)
            val y1 = centre(1, tile, border)

            val expected = oracle.gestureSized(
                save, x0, y0, x1, y1, STEPS, viewWidth, viewHeight,
            )
            val midend = newMidend()
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            assertEquals(tile, geometry.tileSize)

            var outcome = midend.processKey(
                x0 - geometry.originX, y0 - geometry.originY, Buttons.LEFT_BUTTON, tile,
            )
            assertEquals("press at tile $tile", KeyOutcome.SomeEffect, outcome)
            assertTrue("press starts a drag at tile $tile", (midend.ui as Signpost.Ui).dragging)
            for (i in 1..STEPS) {
                outcome = midend.processKey(
                    x0 + (x1 - x0) * i / STEPS - geometry.originX,
                    y0 + (y1 - y0) * i / STEPS - geometry.originY,
                    Buttons.LEFT_DRAG,
                    tile,
                )
                assertEquals("drag at tile $tile", KeyOutcome.SomeEffect, outcome)
            }
            outcome = midend.processKey(
                x1 - geometry.originX, y1 - geometry.originY, Buttons.LEFT_RELEASE, tile,
            )
            assertEquals("release at tile $tile", KeyOutcome.SomeEffect, outcome)
            assertFalse("drag ends at tile $tile", (midend.ui as Signpost.Ui).dragging)

            assertEquals("one move at tile $tile", listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
            assertEquals("move count at tile $tile", 2, midend.stateCount)
            assertEquals("state at tile $tile", expected.state, midend.serialise().serialise())
            assertEquals("text at tile $tile", oracle.text(expected.state), midend.textFormat())
        }
    }

    @Test
    fun `a right drag links in the reverse order`() = OracleClient().use { oracle ->
        val tile = 48
        val border = 24
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val sequence = points(
            OracleClient.Point(centre(1, tile, border), centre(1, tile, border), Buttons.RIGHT_BUTTON),
            OracleClient.Point(centre(0, tile, border), centre(0, tile, border), Buttons.RIGHT_RELEASE),
        )
        val expected = oracle.pointsSized(save, sequence)

        val midend = newMidend()
        val outcomes = send(midend, sequence, tile)
        assertTrue(outcomes.all { it == KeyOutcome.SomeEffect })
        assertEquals(listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertEquals(2, midend.stateCount)
    }

    @Test
    fun `a release outside the board cuts, and a click on the source does not`() =
        OracleClient().use { oracle ->
            val tile = 48
            val b = 24
            val save = oracle.newGame("signpost", "4x4c", SEED).state
            val sequence = points(
                // link 1 to 2
                OracleClient.Point(centre(0, tile, b), centre(0, tile, b), Buttons.LEFT_BUTTON),
                OracleClient.Point(centre(1, tile, b), centre(1, tile, b), Buttons.LEFT_RELEASE),
                // press the linked cell and release outside: a cut
                OracleClient.Point(centre(0, tile, b), centre(0, tile, b), Buttons.LEFT_BUTTON),
                OracleClient.Point(-10, -10, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, sequence)
            val midend = newMidend()
            val outcomes = send(midend, sequence, tile)
            assertTrue(outcomes.all { it == KeyOutcome.SomeEffect })
            assertEquals(listOf("L0,0-1,1", "C0,0"), midend.serialise().values("MOVE"))
            assertEquals(expected.state.state, midend.serialise().serialise())

            // A press and release on the same source is a click, not a
            // cancelled drag and not a move.
            val click = points(
                OracleClient.Point(centre(0, tile, b), centre(0, tile, b), Buttons.LEFT_BUTTON),
                OracleClient.Point(centre(0, tile, b), centre(0, tile, b), Buttons.LEFT_RELEASE),
            )
            val before = midend.serialise().serialise()
            val count = midend.stateCount
            val clickOutcomes = send(midend, click, tile)
            assertEquals(
                listOf(KeyOutcome.SomeEffect, KeyOutcome.SomeEffect),
                clickOutcomes,
            )
            assertEquals("a click adds no state", count, midend.stateCount)
            assertEquals("a click changes no board", before, midend.serialise().serialise())
        }

    @Test
    fun `a letterboxed drag and an inert margin click match`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val midend = newMidend()
        val geometry = midend.geometryFor(411, 702)
        assertEquals(82, geometry.tileSize)
        val tile = geometry.tileSize
        val border = tile / 2
        // Game coordinates: the shell subtracts the letterbox origin.
        val gx0 = border
        val gy0 = border
        val gx1 = border + tile + tile / 2
        val gy1 = border + tile + tile / 2

        // A margin click while the cursor is visible clears it and adds
        // no state.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
        val margin = points(
            OracleClient.Point(10, 10, Buttons.LEFT_BUTTON),
            OracleClient.Point(10, 10, Buttons.LEFT_RELEASE),
        )
        val before = midend.serialise().serialise()
        val count = midend.stateCount
        val marginOutcomes = midend.let {
            margin.map { point ->
                it.processKey(
                    point.x,
                    point.y,
                    point.button,
                    tile,
                )
            }
        }
        assertEquals(KeyOutcome.Unused, marginOutcomes[0])
        assertEquals(KeyOutcome.Unused, marginOutcomes[1])
        assertEquals("a margin click adds no state", count, midend.stateCount)
        assertEquals("a margin click changes no board", before, midend.serialise().serialise())

        // One pixel inside the grid is a move. The oracle takes view
        // coordinates and subtracts the origin; the midend takes game
        // coordinates.
        val oracleSequence = points(
            OracleClient.Point(
                geometry.originX + gx0,
                geometry.originY + gy0,
                Buttons.LEFT_BUTTON,
            ),
            OracleClient.Point(
                geometry.originX + gx1,
                geometry.originY + gy1,
                Buttons.LEFT_RELEASE,
            ),
        )
        val expected = oracle.pointsSized(
            midend.serialise().serialise(),
            oracleSequence,
            width = 411,
            height = 702,
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(gx0, gy0, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(gx1, gy1, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals(listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
        assertEquals(expected.state.state, midend.serialise().serialise())
    }

    @Test
    fun `a letterbox with a nonzero origin links at the same cell`() =
        OracleClient().use { oracle ->
            val params = "7x7c"
            val save = oracle.newGame("signpost", params, SEED).state
            val midend = Midend.newGame(Signpost, params, SEED)
            val geometry = midend.geometryFor(411, 702)
            assertEquals(51, geometry.tileSize)
            assertEquals(2, geometry.originX)
            assertEquals(147, geometry.originY)

            val tile = geometry.tileSize
            val border = tile / 2
            // Cell (0,0) is number 1 and points south at (0,1).
            val gx0 = border
            val gy0 = border
            val gx1 = border
            val gy1 = border + tile + tile / 2

            // The oracle takes view coordinates; the midend takes game
            // coordinates, so the shell has already subtracted the
            // letterbox origin on both axes.
            val sequence = points(
                OracleClient.Point(
                    geometry.originX + gx0,
                    geometry.originY + gy0,
                    Buttons.LEFT_BUTTON,
                ),
                OracleClient.Point(
                    geometry.originX + gx1,
                    geometry.originY + gy1,
                    Buttons.LEFT_DRAG,
                ),
                OracleClient.Point(
                    geometry.originX + gx1,
                    geometry.originY + gy1,
                    Buttons.LEFT_RELEASE,
                ),
            )
            val expected = oracle.pointsSized(
                save,
                sequence,
                width = 411,
                height = 702,
            )

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(gx0, gy0, Buttons.LEFT_BUTTON, tile),
            )
            assertTrue("the press starts a drag", (midend.ui as Signpost.Ui).dragging)
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(gx1, gy1, Buttons.LEFT_DRAG, tile),
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(gx1, gy1, Buttons.LEFT_RELEASE, tile),
            )
            assertFalse("the release ends the drag", (midend.ui as Signpost.Ui).dragging)
            assertEquals(listOf("L0,0-0,1"), midend.serialise().values("MOVE"))
            assertEquals("one state", 2, midend.stateCount)
            assertEquals(expected.state.state, midend.serialise().serialise())
            assertEquals(oracle.text(expected.state.state), midend.textFormat())
        }

    @Test
    fun `the no-op shapes change neither board nor count`() = OracleClient().use { oracle ->
        val tile = 48
        val b = 24
        val finalCell = centre(3, tile, b) // cell (3,3), immutable 16
        val firstCell = centre(0, tile, b) // cell (0,0), immutable 1
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val midend = newMidend()
        val start = midend.serialise().serialise()

        // A left press on the final immutable number is unused.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(finalCell, finalCell, Buttons.LEFT_BUTTON, tile),
        )
        // A right press on the first immutable number is unused.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(firstCell, firstCell, Buttons.RIGHT_BUTTON, tile),
        )
        // A lone release has no held button.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(firstCell, firstCell, Buttons.LEFT_RELEASE, tile),
        )
        // A drag that never leaves its source cell is a click.
        val noChange = points(
            OracleClient.Point(firstCell, firstCell, Buttons.LEFT_BUTTON),
            OracleClient.Point(firstCell + 2, firstCell + 2, Buttons.LEFT_DRAG),
            OracleClient.Point(firstCell + 2, firstCell + 2, Buttons.LEFT_RELEASE),
        )
        val outcomes = send(midend, noChange, tile)
        assertEquals(
            listOf(KeyOutcome.SomeEffect, KeyOutcome.SomeEffect, KeyOutcome.SomeEffect),
            outcomes,
        )
        assertEquals("no-op shapes add no state", 1, midend.stateCount)
        assertEquals("no-op shapes change no board", start, midend.serialise().serialise())

        // A real link moves the chain and the count: the vacuity guard.
        val real = points(
            OracleClient.Point(centre(0, tile, b), centre(0, tile, b), Buttons.LEFT_BUTTON),
            OracleClient.Point(centre(1, tile, b), centre(1, tile, b), Buttons.LEFT_RELEASE),
        )
        send(midend, real, tile)
        assertEquals(2, midend.stateCount)
        assertEquals(listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
        assertNotEquals(start, midend.serialise().serialise())
        assertEquals(
            oracle.pointsSized(save, real).state.state,
            midend.serialise().serialise(),
        )
    }

    @Test
    fun `the keyboard matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
            listOf(Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
            listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            ),
            listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            ),
            listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
                '\b'.code,
            ),
            listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
                'x'.code,
                'X'.code,
            ),
            listOf(Buttons.CURSOR_SELECT, 'x'.code),
            listOf(Buttons.CURSOR_SELECT, 'X'.code),
            listOf('\t'.code),
            listOf('5'.code or Buttons.MOD_NUM_KEYPAD),
            listOf('u'.code),
            listOf('r'.code),
        )
        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
            val outcomes = sequence.map { key -> midend.processKey(0, 0, key, 48) }
            assertEquals(
                "'$sequence' state",
                expected.state,
                midend.serialise().serialise(),
            )
            assertEquals(
                "'$sequence' count",
                requireNotNull(SgtpSave.parse(expected.state).value("NSTATES")).toInt(),
                midend.stateCount,
            )
            assertTrue("'$sequence' has an outcome", outcomes.isNotEmpty())
            assertEquals("'$sequence' status", expected.status, midend.status())

            // The key request never changes with the frontend state.
            val request = Signpost.requestKeys(Signpost.defaultParams())
            assertEquals(1, request.labels.size)
            assertEquals("Clear", request.labels[0].name)
            assertEquals('\b'.code, request.labels[0].button)
            assertFalse(request.labels[0].needsArrows)
        }
    }

    @Test
    fun `a keyboard drag links and the labels describe it`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val sequence = listOf(
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_DOWN,
        )
        val expected = oracle.keys(save, sequence)
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
        for (key in sequence) midend.processKey(0, 0, key, 48)
        assertEquals(expected.state, midend.serialise().serialise())

        val ui = midend.ui as Signpost.Ui
        assertTrue(ui.cshow)
        assertTrue(ui.dragging)
        assertEquals(1, ui.cx)
        assertEquals(1, ui.cy)
        assertEquals("To here", Signpost.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("To here", Signpost.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))

        // Moving the cursor changes the labels as the drag becomes
        // invalid for this direction.
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 48)
        assertEquals("Cancel", Signpost.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Cancel", Signpost.currentKeyLabel(ui, midend.state, Buttons.CURSOR_SELECT2))

        // Finish the drag from (0,0) to (1,1) and compare the link.
        midend.processKey(0, 0, Buttons.CURSOR_LEFT, 48)
        val finish = midend.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
        assertEquals(KeyOutcome.SomeEffect, finish)
        assertEquals(listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
    }

    @Test
    fun `a right-drag cut of a colour group matches the oracle`() = OracleClient().use { oracle ->
        val tile = 48
        val b = 24
        // First build a two-cell coloured chain: 1 -> 2, then link 2 to
        // the cell below.
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val build = points(
            cellPoint(1, 0, tile, b).copy(button = Buttons.LEFT_BUTTON),
            cellPoint(2, 0, tile, b).copy(button = Buttons.LEFT_RELEASE),
            cellPoint(2, 0, tile, b).copy(button = Buttons.LEFT_BUTTON),
            cellPoint(2, 1, tile, b).copy(button = Buttons.LEFT_RELEASE),
        )
        val built = oracle.pointsSized(save, build)
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(built.state.state))

        // The second cell is linked; a right drag from it to outside the
        // board cuts the whole colour set.
        val cut = points(
            cellPoint(1, 0, tile, b).copy(button = Buttons.RIGHT_BUTTON),
            OracleClient.Point(-10, -10, Buttons.RIGHT_RELEASE),
        )
        val expected = oracle.pointsSized(built.state.state, cut)
        val outcomes = send(midend, cut, tile)
        assertTrue(outcomes.all { it == KeyOutcome.SomeEffect })
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertEquals(
            listOf("L1,0-2,0", "L2,0-2,1", "X1,0"),
            midend.serialise().values("MOVE"),
        )
    }
}
