package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: palisade.
 *
 * Layout and real pointer input at tiles 48, 63, 82, and 100 and in a
 * letterboxed 411x702 view. A click selects the nearest edge of the
 * clicked cell, moves the cursor to it, and toggles the edge on both
 * sides. The half-grid cursor continues from that edge; the full-grid
 * cursor, control and shift, and the `H/J/K/L` letters toggle the same
 * edges in C order.
 *
 * Every real fence changes the border bytes and adds one move. A
 * letterbox click, a margin point, a middle click, a lone drag or
 * release, a click on an outward edge, and a click or select on a
 * both-bits edge change neither the borders nor the count.
 *
 * The two game preferences are not serialized. `cursor-mode` selects the
 * input mode only, and `clear-complete-regions` changes the drawing
 * only; the id, status, text, and save must stay the same.
 */
class PalisadeInputParityTest {

    /** One exact board size: the viewport equals the board, origin (0,0). */
    private val tiles = listOf(48, 63, 82, 100)

    private val boardSizes = mapOf(
        48 to Size(292, 292),
        63 to Size(382, 382),
        82 to Size(499, 499),
        100 to Size(609, 609),
    )

    /**
     * The 411x702 layout of each preset. The tile and the letterbox
     * origin come from the specification and the oracle; every origin
     * is nonzero on both axes.
     */
    private val letterboxes = listOf(
        Layout("5x5n5", 67, 2, 147, 407, 407),
        Layout("8x6n6", 45, 1, 192, 408, 318),
        Layout("10x8n8", 37, 1, 183, 409, 335),
        Layout("15x12n10", 25, 5, 188, 401, 326),
    )

    @Test
    fun `layout picks the four exact tiles and a nonzero letterbox origin`() =
        OracleClient().use { oracle ->
            val params = Palisade.decodeParams(PARAMS)
            val save = oracle.newGame("palisade", PARAMS, SEED).state

            for (tile in tiles) {
                val board = requireNotNull(boardSizes[tile])
                assertEquals("board size at $tile", board, Palisade.computeSize(params, tile))
                val layout = oracle.layout(save, board.width, board.height)
                val geometry = midend().geometryFor(board.width, board.height)
                assertEquals("tile at $tile", tile, geometry.tileSize)
                assertEquals("width at $tile", layout.width, geometry.width)
                assertEquals("height at $tile", layout.height, geometry.height)
                assertEquals("origin x at $tile", 0, geometry.originX)
                assertEquals("origin y at $tile", 0, geometry.originY)
            }

            for (layout in letterboxes) {
                val state = oracle.newGame("palisade", layout.params, SEED).state
                val expected = oracle.layout(state, VIEW_WIDTH, VIEW_HEIGHT)
                val geometry = midend(layout.params).geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
                assertEquals("tile for ${layout.params}", layout.tile, geometry.tileSize)
                assertEquals("width for ${layout.params}", expected.width, geometry.width)
                assertEquals("height for ${layout.params}", expected.height, geometry.height)
                assertEquals("origin x for ${layout.params}", layout.originX, geometry.originX)
                assertEquals("origin y for ${layout.params}", layout.originY, geometry.originY)
                assertEquals("board width for ${layout.params}", layout.width, geometry.width)
                assertEquals("board height for ${layout.params}", layout.height, geometry.height)
                assertNotEquals("origin x for ${layout.params}", 0, geometry.originX)
                assertNotEquals("origin y for ${layout.params}", 0, geometry.originY)
            }
        }

    @Test
    fun `real fence clicks at the four tiles change the border and the count`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("palisade", PARAMS, SEED).state
            val expectedMoves = listOf(
                "F0,0,2F1,0,8",
                "F1,1,8F0,1,2",
                "F2,2,4F2,3,1",
                "F2,2,1F2,1,4",
            )

            for (tile in tiles) {
                val board = requireNotNull(boardSizes[tile])
                val margin = tile / 2
                val step = 7 * tile / 8
                val half = tile / 2
                val points = ArrayList<OracleClient.Point>()
                fun fence(x: Int, y: Int) {
                    points += OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
                    points += OracleClient.Point(x, y, Buttons.LEFT_RELEASE)
                }
                // Right edge of (0,0), left edge of (1,1), bottom and
                // top edges of (2,2).
                fence(margin + step, margin + half)
                fence(margin + tile + tile / 8, margin + tile + half)
                fence(margin + 2 * tile + half, margin + 2 * tile + step)
                fence(margin + 2 * tile + half, margin + 2 * tile + tile / 8)

                val expected = oracle.pointsSized(
                    save,
                    points,
                    board.width,
                    board.height,
                    recordTrace = true,
                )
                val actual = midend()
                for ((index, point) in points.withIndex()) {
                    val where = "tile $tile point $index"
                    if (index % 2 == 0) {
                        val borders = borders(actual)
                        val states = actual.stateCount
                        val moves = moves(actual).size
                        assertEquals(
                            "$where outcome",
                            KeyOutcome.SomeEffect,
                            actual.processKey(point.x, point.y, point.button, tile),
                        )
                        assertFalse(
                            "$where changes the border",
                            borders.contentEquals(borders(actual)),
                        )
                        assertEquals("$where adds one state", states + 1, actual.stateCount)
                        assertEquals("$where adds one move", moves + 1, moves(actual).size)
                    } else {
                        val borders = borders(actual)
                        val states = actual.stateCount
                        val moves = moves(actual)
                        assertEquals(
                            "$where release outcome",
                            KeyOutcome.Unused,
                            actual.processKey(point.x, point.y, point.button, tile),
                        )
                        assertArrayEquals("$where keeps the border", borders, borders(actual))
                        assertEquals("$where adds no state", states, actual.stateCount)
                        assertEquals("$where adds no move", moves, moves(actual))
                    }
                }
                assertEquals("tile $tile moves", expectedMoves, moves(actual))
                assertEquals("tile $tile state count", 1 + expectedMoves.size, actual.stateCount)
                assertSame("tile $tile", expected.state, actual)
                assertTrace(
                    "tile $tile",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(actual, tile),
                )
            }
        }

    @Test
    fun `a letterboxed view sends the same game coordinates`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", PARAMS, SEED).state
        val geometry = midend().geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
        assertEquals(67, geometry.tileSize)
        assertEquals(2, geometry.originX)
        assertEquals(147, geometry.originY)

        val tile = geometry.tileSize
        val margin = tile / 2
        val gamePoints = listOf(
            OracleClient.Point(margin + 7 * tile / 8, margin + tile / 2, Buttons.LEFT_BUTTON),
            OracleClient.Point(margin + 7 * tile / 8, margin + tile / 2, Buttons.LEFT_RELEASE),
            OracleClient.Point(
                margin + 3 * tile + tile / 2,
                margin + 3 * tile + 7 * tile / 8,
                Buttons.LEFT_BUTTON,
            ),
            OracleClient.Point(
                margin + 3 * tile + tile / 2,
                margin + 3 * tile + 7 * tile / 8,
                Buttons.LEFT_RELEASE,
            ),
        )
        val viewPoints = gamePoints.map {
            OracleClient.Point(it.x + geometry.originX, it.y + geometry.originY, it.button)
        }
        val expected = oracle.pointsSized(
            save,
            viewPoints,
            VIEW_WIDTH,
            VIEW_HEIGHT,
            recordTrace = true,
        )
        val actual = midend()
        for (point in viewPoints) {
            actual.processKey(
                point.x - geometry.originX,
                point.y - geometry.originY,
                point.button,
                tile,
            )
        }
        assertEquals(
            "letterbox moves",
            listOf("F0,0,2F1,0,8", "F3,3,4F3,4,1"),
            moves(actual),
        )
        assertEquals("letterbox state count", 3, actual.stateCount)
        assertSame("letterbox", expected.state, actual)
        assertTrace(
            "letterbox",
            requireNotNull(expected.trace),
            KotlinTraceSink.stateTrace(actual, tile),
        )

        // A click in the letterbox is outside the board. The origin
        // subtraction makes the game coordinate negative.
        val outside = OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)
        val outsideExpected = oracle.pointsSized(
            save,
            listOf(outside),
            VIEW_WIDTH,
            VIEW_HEIGHT,
            recordTrace = true,
        )
        val outsideMidend = midend()
        assertEquals(
            "letterbox click outcome",
            KeyOutcome.Unused,
            outsideMidend.processKey(
                outside.x - geometry.originX,
                outside.y - geometry.originY,
                outside.button,
                tile,
            ),
        )
        assertEquals("letterbox click adds no state", 1, outsideMidend.stateCount)
        assertEquals("letterbox click adds no move", 0, moves(outsideMidend).size)
        assertSame("letterbox click", outsideExpected.state, outsideMidend)
        assertTrace(
            "letterbox click",
            requireNotNull(outsideExpected.trace),
            KotlinTraceSink.stateTrace(outsideMidend, tile),
        )
    }

    @Test
    fun `the half-grid cursor continues from the clicked edge`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("palisade", PARAMS, SEED).state
            val tile = 48
            val margin = tile / 2
            val points = listOf(
                OracleClient.Point(margin + 7 * tile / 8, margin + tile / 2, Buttons.LEFT_BUTTON),
                OracleClient.Point(margin + 7 * tile / 8, margin + tile / 2, Buttons.LEFT_RELEASE),
                // The first select only reveals the ring; the next three
                // cycle the clicked edge ON -> OFF -> maybe -> ON.
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                // Down to the tile center, where a select does nothing,
                // then down to the left edge of (1,1).
                OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                requireNotNull(boardSizes[tile]).width,
                requireNotNull(boardSizes[tile]).height,
                recordTrace = true,
            )
            val expectedMoves = listOf(
                "F0,0,2F1,0,8",
                "F1,0,136F0,0,34",
                "F1,0,128F0,0,32",
                "F1,0,8F0,0,2",
                "F1,1,8F0,1,2",
            )

            val actual = midend()
            val ui = actual.ui as Palisade.Ui
            assertEquals(
                "click outcome",
                KeyOutcome.SomeEffect,
                actual.processKey(points[0].x, points[0].y, points[0].button, tile),
            )
            assertEquals("the click moves the cursor to the edge", 2, ui.x)
            assertEquals("the click moves the cursor to the edge", 1, ui.y)
            assertFalse("the click hides the ring", ui.show)
            assertEquals(
                "release outcome",
                KeyOutcome.Unused,
                actual.processKey(points[1].x, points[1].y, points[1].button, tile),
            )

            assertEquals(
                "the first select reveals",
                KeyOutcome.SomeEffect,
                actual.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
            )
            assertTrue("the ring is visible", ui.show)
            assertEquals("the reveal adds no state", 2, actual.stateCount)
            assertEquals("the reveal adds no move", 1, moves(actual).size)

            for ((index, point) in points.drop(3).withIndex()) {
                actual.processKey(point.x, point.y, point.button, tile)
                if (index < 3) {
                    assertEquals("select $index keeps the cursor", 2, ui.x)
                    assertEquals("select $index keeps the cursor", 1, ui.y)
                }
            }

            assertEquals("moves", expectedMoves, moves(actual))
            assertEquals("state count", 1 + expectedMoves.size, actual.stateCount)
            assertSame("half-grid sequence", expected.state, actual)
            assertTrace(
                "half-grid sequence",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(actual, tile),
            )
        }

    @Test
    fun `the full-grid cursor control shift and HJKL match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("palisade", PARAMS, SEED).state
            val control = listOf(
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
            )
            val expectedControl = oracle.keys(save, control, FULL_CURSOR)
            val controlMidend = midend()
            (controlMidend.ui as Palisade.Ui).legacyCursor = true
            for (key in control) {
                assertEquals(
                    "control outcome",
                    KeyOutcome.SomeEffect,
                    controlMidend.processKey(0, 0, key, TILE),
                )
            }
            assertEquals(
                "control cycles ON, OFF, maybe",
                listOf("F0,0,2F1,0,8", "F0,0,34F1,0,136", "F0,0,32F1,0,128"),
                moves(controlMidend),
            )
            assertSame("control", expectedControl, controlMidend)

            val shift = listOf(
                Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
                Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
                Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
            )
            val expectedShift = oracle.keys(save, shift, FULL_CURSOR)
            val shiftMidend = midend()
            (shiftMidend.ui as Palisade.Ui).legacyCursor = true
            for (key in shift) {
                assertEquals(
                    "shift outcome",
                    KeyOutcome.SomeEffect,
                    shiftMidend.processKey(0, 0, key, TILE),
                )
            }
            assertEquals(
                "shift cycles OFF, ON, maybe",
                listOf("F0,0,32F1,0,128", "F0,0,34F1,0,136", "F0,0,2F1,0,8"),
                moves(shiftMidend),
            )
            assertSame("shift", expectedShift, shiftMidend)

            // Without the preference control and shift only move the
            // half-grid cursor, so the board stays open.
            val expectedHalf = oracle.keys(save, control)
            val halfMidend = midend()
            for (key in control) {
                assertEquals(
                    "half-grid modified arrow",
                    KeyOutcome.SomeEffect,
                    halfMidend.processKey(0, 0, key, TILE),
                )
            }
            assertEquals("half-grid modified arrows add no move", 0, moves(halfMidend).size)
            assertSame("half-grid modified arrows", expectedHalf, halfMidend)

            // Lower case is control and upper case is shift. The last
            // four letters point out of the top-left cell, which is the
            // outward-edge case: no move, no state.
            val letters = listOf('l', 'j', 'L', 'J', 'h', 'k', 'H', 'K').map { it.code }
            val expectedLetters = oracle.keys(save, letters, FULL_CURSOR)
            val letterMidend = midend()
            (letterMidend.ui as Palisade.Ui).legacyCursor = true
            for ((index, key) in letters.withIndex()) {
                val states = letterMidend.stateCount
                val outcome = letterMidend.processKey(0, 0, key, TILE)
                if (index < 4) {
                    assertEquals("letter '$key' adds one state", states + 1, letterMidend.stateCount)
                    assertEquals("letter '$key' outcome", KeyOutcome.SomeEffect, outcome)
                } else {
                    assertEquals("letter '$key' adds no state", states, letterMidend.stateCount)
                    assertEquals("letter '$key' outcome", KeyOutcome.Unused, outcome)
                }
            }
            assertEquals(
                "HJKL moves",
                listOf("F0,0,2F1,0,8", "F0,0,4F0,1,1", "F0,0,2F1,0,8", "F0,0,4F0,1,1"),
                moves(letterMidend),
            )
            assertSame("HJKL", expectedLetters, letterMidend)

            // The same letters are plain cursor moves in half-grid mode.
            val expectedHalfLetters = oracle.keys(save, letters)
            val halfLetters = midend()
            for (key in letters) {
                assertEquals(
                    "half-grid letter '$key'",
                    KeyOutcome.SomeEffect,
                    halfLetters.processKey(0, 0, key, TILE),
                )
            }
            assertEquals("half-grid letters add no move", 0, moves(halfLetters).size)
            assertSame("half-grid letters", expectedHalfLetters, halfLetters)
        }

    @Test
    fun `the full-grid completion sequence matches the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "2x3n3", "d33").state
        val sequence = listOf(
            'l'.code,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            'l'.code,
            Buttons.CURSOR_UP,
            'l'.code,
        )
        val expected = oracle.keys(start, sequence, FULL_CURSOR)
        assertEquals("the oracle completes the board", 1, expected.status)

        val actual = Midend.fromSave(Palisade, SgtpSave.parse(start))
        (actual.ui as Palisade.Ui).legacyCursor = true
        for (key in sequence) {
            assertEquals(
                "completion key $key",
                KeyOutcome.SomeEffect,
                actual.processKey(0, 0, key, TILE),
            )
        }
        assertEquals("status", 1, actual.status())
        assertEquals(
            "moves",
            listOf("F0,0,2F1,0,8", "F0,2,2F1,2,8", "F0,1,2F1,1,8"),
            moves(actual),
        )
        assertTrue("the completion event fires", actual.takeCompletedEvent())
        assertSame("completion", expected, actual)
    }

    @Test
    fun `empty one-sided and both-bits moves keep the C state`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("palisade", PARAMS, SEED).state
            val initialBorders = borders(Midend.fromSave(Palisade, SgtpSave.parse(start)))
            for ((move, changes) in listOf(
                "" to false,
                "F0,0,2" to true,
                "F1,0,8" to true,
                "F1,1,136" to true,
            )) {
                val save = withMoves(start, move)
                val expected = oracle.id(save)
                val actual = Midend.fromSave(Palisade, SgtpSave.parse(save))
                assertEquals("'$move' id", expected.id, actual.id())
                assertEquals("'$move' status", expected.status, actual.status())
                assertEquals("'$move' text", oracle.text(save), actual.textFormat())
                assertEquals("'$move' save", expected.state, actual.serialise().serialise())
                assertEquals("'$move' keeps one move", listOf(move), moves(actual))
                if (changes) {
                    assertFalse(
                        "'$move' changes the border",
                        initialBorders.contentEquals(borders(actual)),
                    )
                } else {
                    assertArrayEquals(
                        "'$move' keeps the border",
                        initialBorders,
                        borders(actual),
                    )
                }
            }

            // A one-sided right border separates the two cells; a
            // one-sided left border does not. C keeps both.
            val oneSided = Midend.fromSave(
                Palisade,
                SgtpSave.parse(withMoves(start, "F0,0,2")),
            )
            val state = oneSided.state as Palisade.State
            assertEquals("the one-sided right border is ON", 2, state.borderAt(0, 0) and 2)
            assertEquals("the right neighbor is untouched", 0, state.borderAt(1, 0) and 8)

            // The direct move changes only the named cell.
            val before = (Palisade.newState(Palisade.decodeParams(PARAMS), DESC) as Palisade.State)
                .borders.copyOf()
            val oneCell = Palisade.executeMove(
                Palisade.newState(Palisade.decodeParams(PARAMS), DESC),
                "F0,0,2",
            ) as Palisade.State
            assertEquals("the direct one-sided move", 2, oneCell.borders[0].toInt() and 2)
            for (i in 1 until before.size) {
                assertEquals("cell $i is untouched", before[i], oneCell.borders[i])
            }
        }

    @Test
    fun `inert clicks change neither the border nor the count`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", PARAMS, SEED).state
        val tile = 48
        val margin = tile / 2
        val board = requireNotNull(boardSizes[tile])
        val cases = listOf(
            "letterbox" to OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
            "far corner" to OracleClient.Point(291, 291, Buttons.LEFT_BUTTON),
            "margin" to OracleClient.Point(5, 5, Buttons.LEFT_BUTTON),
            "middle button" to OracleClient.Point(150, 150, Buttons.MIDDLE_BUTTON),
            "lone drag" to OracleClient.Point(100, 100, Buttons.LEFT_DRAG),
            "lone release" to OracleClient.Point(100, 100, Buttons.LEFT_RELEASE),
            // In bounds cell (0,0), but above the tile band: the
            // selected edge points out of the board.
            "top outward edge" to OracleClient.Point(margin + margin, margin - 1, Buttons.LEFT_BUTTON),
            // In bounds cell (4,4); its right edge is the board edge.
            "right outward edge" to
                OracleClient.Point(margin + 4 * tile + 7 * tile / 8, margin + 4 * tile + margin, Buttons.LEFT_BUTTON),
            "bottom outward edge" to
                OracleClient.Point(margin + 4 * tile + margin, margin + 4 * tile + 7 * tile / 8, Buttons.LEFT_BUTTON),
        )

        for ((label, point) in cases) {
            val expected = oracle.pointsSized(
                save,
                listOf(point),
                board.width,
                board.height,
                recordTrace = true,
            )
            val actual = midend()
            val borders = borders(actual)
            assertNotEquals(
                "$label outcome",
                KeyOutcome.SomeEffect,
                actual.processKey(point.x, point.y, point.button, tile),
            )
            assertEquals("$label adds no state", 1, actual.stateCount)
            assertEquals("$label adds no move", 0, moves(actual).size)
            assertArrayEquals("$label keeps the border", borders, borders(actual))
            assertSame(label, expected.state, actual)
            assertTrace(
                "$label trace",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(actual, tile),
            )
        }
    }

    @Test
    fun `a both-bits edge ignores clicks and selects`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", PARAMS, SEED).state
        // Both the ON and the OFF bit on the left edge of (1,1). C
        // accepts the state and then has no cycle case for it.
        val save = withMoves(start, "F1,1,136")
        assertEquals("the save loads", "5x5n5:$DESC", oracle.id(save).id)
        val tile = 48
        val margin = tile / 2

        val selectSequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
        )
        val expectedSelect = oracle.pointsSized(
            save,
            selectSequence.map { OracleClient.Point(0, 0, it) },
            requireNotNull(boardSizes[tile]).width,
            requireNotNull(boardSizes[tile]).height,
            recordTrace = true,
        )
        val selectMidend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        for (key in selectSequence) selectMidend.processKey(0, 0, key, tile)
        assertEquals("the select adds no state", 2, selectMidend.stateCount)
        assertEquals("the select adds no move", listOf("F1,1,136"), moves(selectMidend))
        assertSame("both-bits select", expectedSelect.state, selectMidend)
        assertTrace(
            "both-bits select",
            requireNotNull(expectedSelect.trace),
            KotlinTraceSink.stateTrace(selectMidend, tile),
        )

        val click = listOf(
            OracleClient.Point(margin + tile + tile / 8, margin + tile + margin, Buttons.LEFT_BUTTON),
            OracleClient.Point(margin + tile + tile / 8, margin + tile + margin, Buttons.LEFT_RELEASE),
        )
        val expectedClick = oracle.pointsSized(
            save,
            click,
            requireNotNull(boardSizes[tile]).width,
            requireNotNull(boardSizes[tile]).height,
            recordTrace = true,
        )
        val clickMidend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        for (point in click) {
            assertNotEquals(
                "both-bits click",
                KeyOutcome.SomeEffect,
                clickMidend.processKey(point.x, point.y, point.button, tile),
            )
        }
        assertEquals("the click adds no state", 2, clickMidend.stateCount)
        assertEquals("the click adds no move", listOf("F1,1,136"), moves(clickMidend))
        assertSame("both-bits click", expectedClick.state, clickMidend)
        assertTrace(
            "both-bits click",
            requireNotNull(expectedClick.trace),
            KotlinTraceSink.stateTrace(clickMidend, tile),
        )
    }

    @Test
    fun `cursor-mode and clear-complete-regions change no state`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("palisade", PARAMS, SEED).state
            val sequence = listOf(
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
            )

            // The pref is the same input mode the app passes through
            // `palisadeFullCursor`, and the oracle applies it to the
            // live game_ui before the keys run.
            val expectedFull = oracle.keys(start, sequence, FULL_CURSOR)
            val full = Midend.fromSave(Palisade, SgtpSave.parse(start))
            (full.ui as Palisade.Ui).legacyCursor = true
            for (key in sequence) full.processKey(0, 0, key, TILE)
            assertEquals("full-mode moves", 1, moves(full).size)
            assertSame("cursor-mode=full", expectedFull, full)

            val expectedHalf = oracle.keys(start, sequence)
            val half = Midend.fromSave(Palisade, SgtpSave.parse(start))
            for (key in sequence) half.processKey(0, 0, key, TILE)
            assertEquals("half-mode moves", 0, moves(half).size)
            assertSame("cursor-mode=half", expectedHalf, half)

            // The preference alone is not state. The same save with the
            // pref on and off has the same id, status, text, and board;
            // only the input mode and the cursor shape differ.
            val prefOnly = oracle.keys(start, emptyList(), FULL_CURSOR)
            val prefOff = oracle.keys(start, emptyList())
            assertEquals("the pref keeps the id", prefOff.id, prefOnly.id)
            assertEquals("the pref keeps the status", prefOff.status, prefOnly.status)
            assertEquals(
                "the pref keeps the save",
                prefOff.state,
                prefOnly.state,
            )
            assertEquals("the pref keeps the text", oracle.text(prefOff.state), oracle.text(prefOnly.state))
            val prefMidend = Midend.fromSave(Palisade, SgtpSave.parse(start))
            (prefMidend.ui as Palisade.Ui).legacyCursor = true
            assertEquals("the pref alone keeps the save", start, prefMidend.serialise().serialise())
            assertEquals("the pref alone keeps the id", prefOff.id, prefMidend.id())

            // clear-complete-regions is drawing only. The honest F
            // board has MAYBE edges, which the pref turns grey.
            val solved = oracle.solve(start).state
            val aux = requireNotNull(SgtpSave.parse(solved).value("SOLVE"))
            val honestSave = withMoves(start, honestMove(aux, 5, 5))
            val plainTrace = oracle.trace(honestSave)
            val clearTrace = oracle.trace(honestSave, prefs = "clear-complete-regions=true")
            val plain = Midend.fromSave(Palisade, SgtpSave.parse(honestSave))
            val clear = Midend.fromSave(Palisade, SgtpSave.parse(honestSave))
            (clear.ui as Palisade.Ui).clearCompleteRegions = true

            assertEquals("the pref keeps the id", plain.id(), clear.id())
            assertEquals("the pref keeps the status", plain.status(), clear.status())
            assertEquals("the pref keeps the text", plain.textFormat(), clear.textFormat())
            assertEquals(
                "the pref keeps the save",
                plain.serialise().serialise(),
                clear.serialise().serialise(),
            )
            assertEquals("the plain text", oracle.text(honestSave), plain.textFormat())
            assertTrace(
                "the plain honest trace",
                plainTrace,
                KotlinTraceSink.stateTrace(plain, TILE),
            )
            assertTrace(
                "clear-complete-regions trace",
                clearTrace,
                KotlinTraceSink.stateTrace(clear, TILE),
            )
            assertTrue(
                "the pref changes only the drawing",
                plainTrace != clearTrace,
            )
        }

    /** One layout row of the 411x702 issue: params, tile, origin, board. */
    private data class Layout(
        val params: String,
        val tile: Int,
        val originX: Int,
        val originY: Int,
        val width: Int,
        val height: Int,
    )

    private fun midend(params: String = PARAMS): Midend =
        Midend.newGame(Palisade, params, SEED)

    private fun borders(midend: Midend): ByteArray =
        (midend.state as Palisade.State).borders.copyOf()

    private fun moves(midend: Midend): List<String> =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE")

    /** Appends one `MOVE` record and moves `STATEPOS` to the end. */
    private fun withMoves(save: String, move: String): String {
        val records = SgtpSave.parse(save).records.filter {
            it.key != "NSTATES" && it.key != "STATEPOS" &&
                it.key != "MOVE" && it.key != "SOLVE" && it.key != "RESTART"
        } + listOf(
            SaveRecord("NSTATES", "2"),
            SaveRecord("STATEPOS", "2"),
            SaveRecord("MOVE", move),
        )
        return SgtpSave.of(records).serialise()
    }

    /** Builds one interior wall per ON edge, as a single move. */
    private fun honestMove(aux: String, w: Int, h: Int): String {
        val bits = aux.substring(1)
        val out = StringBuilder()
        for (i in bits.indices) {
            val border = bits[i].code and 15
            val x = i % w
            val y = i / w
            for (dir in listOf(1, 2)) {
                if (border and (1 shl dir) == 0) continue
                val nx = x + (if (dir == 1) 1 else 0)
                val ny = y + (if (dir == 2) 1 else 0)
                if (nx >= w || ny >= h) continue
                out.append("F$x,$y,${1 shl dir}F$nx,$ny,${1 shl (dir xor 2)}")
            }
        }
        return out.toString()
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what save", expected.state, actual.serialise().serialise())
        assertEquals("$what can undo", expected.canUndo, actual.canUndo)
        assertEquals("$what can redo", expected.canRedo, actual.canRedo)
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj, actual: JsonValue.Obj) {
        val e = TraceNormalizer.normalize(expected).lines()
        val a = TraceNormalizer.normalize(actual).lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError(
                    "$what: first diff at line ${i + 1}:\n" +
                        "expected: ${e[i]}\nactual:   ${a[i]}",
                )
            }
        }
        assertEquals("$what: line count", e.size, a.size)
    }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "5x5n5"
        const val DESC = "a313d3d3f32a1"
        const val TILE = 48
        const val VIEW_WIDTH = 411
        const val VIEW_HEIGHT = 702
        const val FULL_CURSOR = "cursor-mode=full"
    }
}
