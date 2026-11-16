package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Undead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: undead.
 *
 * Layout at the exact board sizes and at a letterboxed viewport, real
 * presses on cells and on the count row at four tiles and in the
 * letterbox, the one-pixel bands, press, drag, and release, the keyboard
 * ring, the two preferences, and the vacuity guards: a real cell change
 * alters the guess and adds one move, an inert press changes neither.
 */
class UndeadInputParityTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 64
    }

    private fun midend(save: String): Midend =
        Midend.fromSave(Undead, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj, actual: JsonValue.Obj) {
        val upstream = UpstreamLock.commit(
            java.io.File(System.getProperty("testdata")!!).parentFile.resolve("UPSTREAM.lock"),
        )
        val e = TraceNormalizer.normalize(expected, upstream).lines()
        val a = TraceNormalizer.normalize(actual, upstream).lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError("$what: first diff ${i + 1}\n${e[i]}\n${a[i]}")
            }
        }
        assertEquals("$what line count", e.size, a.size)
    }

    private fun firstFloor(state: Undead.State): Pair<Int, Int> {
        val w = state.common.params.w
        for (y in 1..state.common.params.h) {
            for (x in 1..w) {
                val xi = state.common.xinfo[x + y * (w + 2)]
                if (xi >= 0 && !state.common.fixed[xi]) return x to y
            }
        }
        error("the board has no editable cell")
    }

    private fun cellPoint(x: Int, y: Int, tile: Int, originX: Int = 0, originY: Int = 0) =
        OracleClient.Point(
            originX + tile / 4 + x * tile + tile / 2,
            originY + tile / 4 + y * tile + 3 * tile / 2,
            Buttons.LEFT_BUTTON,
        )

    @Test
    fun `layout picks the exact tiles and a letterbox origin`() =
        OracleClient().use { oracle ->
            for ((params, tile) in listOf(
                "4x4dn" to 63,
                "5x5dn" to 55,
                "7x7dn" to 43,
            )) {
                val save = oracle.newGame("undead", params, SEED).state
                val expected = oracle.layout(save, 411, 702)
                val geometry = midend(save).geometryFor(411, 702)
                assertEquals("'$params' tile", tile, geometry.tileSize)
                assertEquals("'$params' width", expected.width, geometry.width)
                assertEquals("'$params' height", expected.height, geometry.height)
                assertEquals("'$params' origin x", (411 - expected.width) / 2, geometry.originX)
                assertEquals("'$params' origin y", (702 - expected.height) / 2, geometry.originY)
            }

            // The measured 4x4 default origins.
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val geometry = midend(save).geometryFor(411, 702)
            assertEquals(1, geometry.originX)
            assertEquals(115, geometry.originY)

            // Odd tile sizes truncate the border: 63 gives 408x471.
            assertEquals(
                408,
                Undead.computeSize(Undead.decodeParams("4x4dn"), 63).width,
            )
            assertEquals(
                471,
                Undead.computeSize(Undead.decodeParams("4x4dn"), 63).height,
            )
            assertEquals(
                532,
                Undead.computeSize(Undead.decodeParams("4x4dn"), 82).width,
            )
            assertEquals(
                750,
                Undead.computeSize(Undead.decodeParams("4x4dn"), 100).height,
            )
        }

    @Test
    fun `a real click at four tiles and in the letterbox matches the oracle`() =
        OracleClient().use { oracle ->
            for (params in Undead.presets().map { it.params }) {
                val save = oracle.newGame("undead", params, SEED).state
                val state = midend(save).state as Undead.State
                val cell = firstFloor(state)

                for (tile in listOf(48, 63, 82, 100)) {
                    val size = Undead.computeSize(Undead.decodeParams(params), tile)
                    val points = listOf(cellPoint(cell.first, cell.second, tile))
                    val expected = oracle.pointsSized(
                        save,
                        points,
                        size.width,
                        size.height,
                        recordTrace = true,
                    )
                    val actual = midend(save)
                    for (point in points) {
                        assertEquals(
                            "'$params' tile $tile",
                            KeyOutcome.SomeEffect,
                            actual.processKey(point.x, point.y, point.button, tile),
                        )
                    }
                    assertSame("'$params' tile $tile select", expected.state, actual)
                    assertTrace(
                        "'$params' tile $tile select",
                        requireNotNull(expected.trace),
                        KotlinTraceSink.stateTrace(actual, tile),
                    )
                    assertEquals("'$params' tile $tile adds no move", 0, actual.serialise().values("MOVE").size)
                    // The selection is UI state: the highlight is on the
                    // cell and it came from the mouse.
                    val ui = actual.ui as Undead.Ui
                    assertTrue("'$params' tile $tile highlight", ui.hshow)
                    assertFalse("'$params' tile $tile not pencil", ui.hpencil)
                    assertFalse("'$params' tile $tile mouse", ui.hcursor)
                    assertEquals("'$params' tile $tile hx", cell.first, ui.hx)
                    assertEquals("'$params' tile $tile hy", cell.second, ui.hy)
                }

                // The 411x702 letterbox. The shell sends view coordinates
                // and subtracts the origin, so the tap still reaches the
                // drawn cell. The state and the final frame, which shows
                // the highlight, are compared with the oracle.
                val geometry = midend(save).geometryFor(411, 702)
                assertTrue("'$params' letterbox has an origin", geometry.originY != 0)
                val view = cellPoint(
                    cell.first,
                    cell.second,
                    geometry.tileSize,
                    geometry.originX,
                    geometry.originY,
                )
                val expected = oracle.pointsSized(save, listOf(view), 411, 702, recordTrace = true)
                val actual = midend(save)
                assertEquals(
                    "'$params' letterbox select",
                    KeyOutcome.SomeEffect,
                    actual.processKey(
                        view.x - geometry.originX,
                        view.y - geometry.originY,
                        view.button,
                        geometry.tileSize,
                    ),
                )
                assertSame("'$params' letterbox select", expected.state, actual)
                assertTrace(
                    "'$params' letterbox select",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(actual, geometry.tileSize),
                )
                assertEquals(0, actual.serialise().values("MOVE").size)
                val ui = actual.ui as Undead.Ui
                assertTrue("'$params' letterbox highlight", ui.hshow)
                assertEquals("'$params' letterbox hx", cell.first, ui.hx)
                assertEquals("'$params' letterbox hy", cell.second, ui.hy)
            }
        }

    @Test
    fun `a real cell change alters the guess and the move count`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val start = midend(save)
            val cell = firstFloor(start.state as Undead.State)
            val before = (start.state as Undead.State).guess.copyOf()
            val geometry = Undead.countsGeometry(TILE, 4)
            val cy = (geometry.y1 + geometry.y2) / 2
            val press = OracleClient.Point(
                (geometry.ghostX1 + geometry.ghostX2) / 2,
                cy,
                Buttons.LEFT_BUTTON,
            )
            val points = listOf(cellPoint(cell.first, cell.second, TILE), press)
            val expected = oracle.pointsSized(save, points, 416, 480).state
            for (point in points) start.processKey(point.x, point.y, point.button, TILE)
            assertSame("cell change", expected, start)
            assertEquals("one move", 1, start.serialise().values("MOVE").size)
            assertNotEquals(before, (start.state as Undead.State).guess)
            assertEquals("status is unchanged", 0, start.status())
        }

    @Test
    fun `a clue click toggles the mark at four tiles and in the letterbox`() =
        OracleClient().use { oracle ->
            val params = "4x4dn"
            val save = oracle.newGame("undead", params, SEED).state
            val initial = (midend(save).state as Undead.State).guess.copyOf()

            for (tile in listOf(48, 63, 82, 100)) {
                val size = Undead.computeSize(Undead.decodeParams(params), tile)
                // (1,0) is the first clue in the top row, not a corner.
                val points = listOf(cellPoint(1, 0, tile))
                val expected = oracle.pointsSized(
                    save,
                    points,
                    size.width,
                    size.height,
                    recordTrace = true,
                )
                val actual = midend(save)
                assertEquals(
                    "tile $tile clue press",
                    KeyOutcome.SomeEffect,
                    actual.processKey(points[0].x, points[0].y, points[0].button, tile),
                )
                assertSame("tile $tile clue", expected.state, actual)
                assertTrace(
                    "tile $tile clue",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(actual, tile),
                )
                assertEquals("tile $tile one move", 1, actual.serialise().values("MOVE").size)
                assertTrue(
                    "tile $tile is a clue move",
                    actual.serialise().values("MOVE").single().startsWith("D"),
                )
                assertTrue(
                    "tile $tile marks the clue",
                    (actual.state as Undead.State).hintsDone.any { it },
                )
                // A clue does not touch the board or the status.
                assertTrue(
                    "tile $tile leaves the guess",
                    initial.contentEquals((actual.state as Undead.State).guess),
                )
                assertEquals("tile $tile status", 0, actual.status())
            }

            // The 411x702 letterbox: the view point carries the measured
            // origin (1,115), and the engine receives the game point.
            val geometry = midend(save).geometryFor(411, 702)
            assertEquals(63, geometry.tileSize)
            assertEquals(1, geometry.originX)
            assertEquals(115, geometry.originY)
            val view = cellPoint(1, 0, geometry.tileSize, geometry.originX, geometry.originY)
            val expected = oracle.pointsSized(save, listOf(view), 411, 702, recordTrace = true)
            val actual = midend(save)
            assertEquals(
                "letterbox clue press",
                KeyOutcome.SomeEffect,
                actual.processKey(
                    view.x - geometry.originX,
                    view.y - geometry.originY,
                    view.button,
                    geometry.tileSize,
                ),
            )
            assertSame("letterbox clue", expected.state, actual)
            assertTrace(
                "letterbox clue",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(actual, geometry.tileSize),
            )
            assertEquals(1, actual.serialise().values("MOVE").size)
            assertTrue((actual.state as Undead.State).hintsDone.any { it })
            assertTrue((actual.state as Undead.State).guess.contentEquals(initial))

            // A second click on the same clue removes the mark.
            val second = oracle.pointsSized(save, listOf(view, view), 411, 702, recordTrace = true)
            val toggle = midend(save)
            for (point in listOf(view, view)) {
                toggle.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("letterbox clue toggle", second.state, toggle)
            assertTrace(
                "letterbox clue toggle",
                requireNotNull(second.trace),
                KotlinTraceSink.stateTrace(toggle, geometry.tileSize),
            )
            assertFalse(
                "the second click removes the mark",
                (toggle.state as Undead.State).hintsDone.any { it },
            )
            assertEquals(2, toggle.serialise().values("MOVE").size)
        }

    @Test
    fun `pencils and the right button are UI only at every tile`() =
        OracleClient().use { oracle ->
            val params = "4x4dn"
            val save = oracle.newGame("undead", params, SEED).state
            val state = midend(save).state as Undead.State
            val cell = firstFloor(state)
            val initial = state.guess.copyOf()

            for (tile in listOf(48, 63, 82, 100)) {
                val size = Undead.computeSize(Undead.decodeParams(params), tile)
                val select = cellPoint(cell.first, cell.second, tile)

                // A second press on the highlighted cell enters pencil
                // mode and changes no state.
                val pencil = oracle.pointsSized(
                    save,
                    listOf(select, select),
                    size.width,
                    size.height,
                    recordTrace = true,
                )
                val actual = midend(save)
                for (point in listOf(select, select)) {
                    actual.processKey(point.x, point.y, point.button, tile)
                }
                assertSame("tile $tile pencil", pencil.state, actual)
                assertTrace(
                    "tile $tile pencil",
                    requireNotNull(pencil.trace),
                    KotlinTraceSink.stateTrace(actual, tile),
                )
                assertEquals("tile $tile pencil adds no move", 0, actual.serialise().values("MOVE").size)
                val pencilUi = actual.ui as Undead.Ui
                assertTrue("tile $tile pencil highlight", pencilUi.hshow)
                assertTrue("tile $tile pencil mode", pencilUi.hpencil)
                assertFalse("tile $tile pencil from mouse", pencilUi.hcursor)

                // A right press on an unknown cell enters pencil mode;
                // a second right press on it hides the highlight.
                val right = select.copy(button = Buttons.RIGHT_BUTTON)
                val rightOnce = oracle.pointsSized(
                    save,
                    listOf(right),
                    size.width,
                    size.height,
                    recordTrace = true,
                )
                val rightMidend = midend(save)
                assertEquals(
                    "tile $tile right press",
                    KeyOutcome.SomeEffect,
                    rightMidend.processKey(right.x, right.y, right.button, tile),
                )
                assertSame("tile $tile right press", rightOnce.state, rightMidend)
                assertTrace(
                    "tile $tile right press",
                    requireNotNull(rightOnce.trace),
                    KotlinTraceSink.stateTrace(rightMidend, tile),
                )
                val rightUi = rightMidend.ui as Undead.Ui
                assertTrue("tile $tile right pencil", rightUi.hpencil)
                assertEquals("tile $tile right hx", cell.first, rightUi.hx)
                assertEquals("tile $tile right no move", 0, rightMidend.serialise().values("MOVE").size)

                val rightTwice = oracle.pointsSized(
                    save,
                    listOf(right, right),
                    size.width,
                    size.height,
                    recordTrace = true,
                )
                val twice = midend(save)
                for (point in listOf(right, right)) {
                    twice.processKey(point.x, point.y, point.button, tile)
                }
                assertSame("tile $tile right twice", rightTwice.state, twice)
                assertTrace(
                    "tile $tile right twice",
                    requireNotNull(rightTwice.trace),
                    KotlinTraceSink.stateTrace(twice, tile),
                )
                val hiddenUi = twice.ui as Undead.Ui
                assertFalse("tile $tile right twice hides", hiddenUi.hshow)
                assertFalse("tile $tile right twice leaves pencil", hiddenUi.hpencil)
                assertEquals("tile $tile right twice hx", 0, hiddenUi.hx)
                assertEquals("tile $tile right twice hy", 0, hiddenUi.hy)
                assertEquals("tile $tile right twice no move", 0, twice.serialise().values("MOVE").size)

                assertTrue(
                    "tile $tile leaves the guess",
                    initial.contentEquals((twice.state as Undead.State).guess),
                )
                assertEquals("tile $tile status", 0, twice.status())
            }

            // The same two UI-only shapes at the 411x702 letterbox,
            // where the view points carry the measured origin.
            val geometry = midend(save).geometryFor(411, 702)
            assertEquals(63, geometry.tileSize)
            val game = cellPoint(cell.first, cell.second, geometry.tileSize)
            fun view(point: OracleClient.Point) = OracleClient.Point(
                geometry.originX + point.x,
                geometry.originY + point.y,
                point.button,
            )

            val pencil = oracle.pointsSized(
                save,
                listOf(view(game), view(game)),
                411,
                702,
                recordTrace = true,
            )
            val letterbox = midend(save)
            for (point in listOf(game, game)) {
                letterbox.processKey(point.x, point.y, point.button, geometry.tileSize)
            }
            assertSame("letterbox pencil", pencil.state, letterbox)
            assertTrace(
                "letterbox pencil",
                requireNotNull(pencil.trace),
                KotlinTraceSink.stateTrace(letterbox, geometry.tileSize),
            )
            val letterboxUi = letterbox.ui as Undead.Ui
            assertTrue("letterbox pencil highlight", letterboxUi.hshow)
            assertTrue("letterbox pencil mode", letterboxUi.hpencil)
            assertEquals("letterbox pencil no move", 0, letterbox.serialise().values("MOVE").size)

            val right = game.copy(button = Buttons.RIGHT_BUTTON)
            val rightTwice = oracle.pointsSized(
                save,
                listOf(view(right), view(right)),
                411,
                702,
                recordTrace = true,
            )
            val letterboxRight = midend(save)
            for (point in listOf(right, right)) {
                letterboxRight.processKey(point.x, point.y, point.button, geometry.tileSize)
            }
            assertSame("letterbox right", rightTwice.state, letterboxRight)
            assertTrace(
                "letterbox right",
                requireNotNull(rightTwice.trace),
                KotlinTraceSink.stateTrace(letterboxRight, geometry.tileSize),
            )
            val letterboxRightUi = letterboxRight.ui as Undead.Ui
            assertFalse("letterbox right hides", letterboxRightUi.hshow)
            assertFalse("letterbox right leaves pencil", letterboxRightUi.hpencil)
            assertEquals("letterbox right no move", 0, letterboxRight.serialise().values("MOVE").size)
            assertEquals("letterbox right status", 0, letterboxRight.status())
        }

    @Test
    fun `an inert click changes neither the board nor the history`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val state = midend(save).state as Undead.State
            val mirror = run {
                val w = 4
                var found: Pair<Int, Int>? = null
                for (y in 1..4) for (x in 1..w) {
                    if (state.common.xinfo[x + y * 6] == -1) found = x to y
                }
                found!!
            }
            val points = listOf(
                cellPoint(mirror.first, mirror.second, TILE),
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                cellPoint(1, 1, TILE, 0, 0).copy(button = Buttons.MIDDLE_BUTTON),
            )
            for (point in points) {
                val midend = midend(save)
                val before = midend.serialise().serialise()
                midend.processKey(point.x, point.y, point.button, TILE)
                assertEquals("point $point changes nothing", before, midend.serialise().serialise())
                assertEquals(0, midend.serialise().values("MOVE").size)
            }
            // The oracle agrees on the state.
            for (point in points) {
                val expected = oracle.pointsSized(save, listOf(point), 416, 480).state
                assertEquals("oracle $point", save, expected.state)
            }
        }

    @Test
    fun `the one-pixel bands match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        // The drawn rect of cell (1,1) is 81..143 x 145..207; the hit
        // area is 81..144 x 146..209.
        val points = listOf(
            OracleClient.Point(81, 176, Buttons.LEFT_BUTTON),
            OracleClient.Point(80, 176, Buttons.LEFT_BUTTON),
            OracleClient.Point(144, 176, Buttons.LEFT_BUTTON),
            OracleClient.Point(145, 176, Buttons.LEFT_BUTTON),
            OracleClient.Point(112, 145, Buttons.LEFT_BUTTON),
            OracleClient.Point(112, 146, Buttons.LEFT_BUTTON),
            OracleClient.Point(112, 208, Buttons.LEFT_BUTTON),
            OracleClient.Point(112, 209, Buttons.LEFT_BUTTON),
            OracleClient.Point(112, 210, Buttons.LEFT_BUTTON),
        )
        for (point in points) {
            val expected = oracle.pointsSized(
                save,
                listOf(point),
                416,
                480,
                recordTrace = true,
            )
            val midend = midend(save)
            midend.processKey(point.x, point.y, point.button, TILE)
            assertTrace(
                "band $point",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(midend),
            )
        }
    }

    @Test
    fun `the count row is strict and uses the drawing rectangles`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val cell = firstFloor(midend(save).state as Undead.State)
            val initial = (midend(save).state as Undead.State).guess.copyOf()
            for (tile in listOf(48, 63, 82, 100)) {
                val geometry = Undead.countsGeometry(tile, 4)
                val cy = (geometry.y1 + geometry.y2) / 2
                val select = cellPoint(cell.first, cell.second, tile)
                val size = Undead.computeSize(Undead.decodeParams("4x4dn"), tile)

                val onLeftEdge = OracleClient.Point(geometry.ghostX1, cy, Buttons.LEFT_BUTTON)
                val justInside = OracleClient.Point(geometry.ghostX1 + 1, cy, Buttons.LEFT_BUTTON)
                val onTop = OracleClient.Point(
                    (geometry.ghostX1 + geometry.ghostX2) / 2,
                    geometry.y1,
                    Buttons.LEFT_BUTTON,
                )
                val belowTop = OracleClient.Point(
                    (geometry.ghostX1 + geometry.ghostX2) / 2,
                    geometry.y1 + 1,
                    Buttons.LEFT_BUTTON,
                )

                // A click on the border moves nothing.
                for (point in listOf(onLeftEdge, onTop)) {
                    val expected = oracle.pointsSized(
                        save,
                        listOf(select, point),
                        size.width,
                        size.height,
                    ).state
                    val actual = midend(save)
                    actual.processKey(select.x, select.y, select.button, tile)
                    actual.processKey(point.x, point.y, point.button, tile)
                    assertEquals("tile $tile edge has no move", 0, actual.serialise().values("MOVE").size)
                    assertEquals("tile $tile edge state", save, expected.state)
                }

                // One pixel inside the strict rectangle makes the move.
                for ((point, letter) in listOf(
                    justInside to "G",
                    OracleClient.Point(
                        (geometry.vampireX1 + geometry.vampireX2) / 2,
                        belowTop.y,
                        Buttons.LEFT_BUTTON,
                    ) to "V",
                    OracleClient.Point(
                        (geometry.zombieX1 + geometry.zombieX2) / 2,
                        belowTop.y,
                        Buttons.LEFT_BUTTON,
                    ) to "Z",
                )) {
                    val expected = oracle.pointsSized(
                        save,
                        listOf(select, point),
                        size.width,
                        size.height,
                    ).state
                    val actual = midend(save)
                    actual.processKey(select.x, select.y, select.button, tile)
                    actual.processKey(point.x, point.y, point.button, tile)
                    assertSame("tile $tile $letter", expected, actual)
                    assertEquals(
                        "tile $tile $letter one move",
                        1,
                        actual.serialise().values("MOVE").size,
                    )
                    assertTrue(
                        "tile $tile $letter payload",
                        actual.serialise().values("MOVE").single().startsWith(letter),
                    )
                    // A real change sets the guess and adds one state.
                    assertFalse(
                        "tile $tile $letter changes the guess",
                        initial.contentEquals((actual.state as Undead.State).guess),
                    )
                    assertEquals("tile $tile $letter status", 0, actual.status())
                    assertEquals("tile $tile $letter two states", 2, actual.stateCount)
                }
            }

            // The 411x702 letterbox, which has the measured origin
            // (1,115). The view points carry the origin; the engine
            // receives the game points.
            val geometry = midend(save).geometryFor(411, 702)
            assertEquals(63, geometry.tileSize)
            val counts = Undead.countsGeometry(geometry.tileSize, 4)
            val gameSelect = cellPoint(cell.first, cell.second, geometry.tileSize)
            val gameGhost = OracleClient.Point(
                (counts.ghostX1 + counts.ghostX2) / 2,
                (counts.y1 + counts.y2) / 2,
                Buttons.LEFT_BUTTON,
            )
            fun view(point: OracleClient.Point) = OracleClient.Point(
                geometry.originX + point.x,
                geometry.originY + point.y,
                point.button,
            )
            val expected = oracle.pointsSized(
                save,
                listOf(view(gameSelect), view(gameGhost)),
                411,
                702,
                recordTrace = true,
            )
            val actual = midend(save)
            for (point in listOf(gameSelect, gameGhost)) {
                assertEquals(
                    "letterbox $point",
                    KeyOutcome.SomeEffect,
                    actual.processKey(point.x, point.y, point.button, geometry.tileSize),
                )
            }
            assertSame("letterbox count row", expected.state, actual)
            assertTrace(
                "letterbox count row",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(actual, geometry.tileSize),
            )
            assertEquals("letterbox one move", 1, actual.serialise().values("MOVE").size)
            assertEquals(
                "letterbox ghost",
                "G",
                actual.serialise().values("MOVE").single().take(1),
            )
            assertEquals("letterbox status", 0, actual.status())
        }

    @Test
    fun `a press, a drag, and a release are one selection`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val points = listOf(
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
                OracleClient.Point(150, 200, Buttons.LEFT_DRAG),
                OracleClient.Point(150, 200, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points, 416, 480).state
            val actual = midend(save)
            for (point in points) {
                actual.processKey(point.x, point.y, point.button, TILE)
            }
            assertSame("drag", expected, actual)
            assertEquals(0, actual.serialise().values("MOVE").size)

            // A press on a count icon followed by a drag is one press.
            val geometry = Undead.countsGeometry(TILE, 4)
            val press = OracleClient.Point(
                (geometry.ghostX1 + geometry.ghostX2) / 2,
                (geometry.y1 + geometry.y2) / 2,
                Buttons.LEFT_BUTTON,
            )
            val sequence = listOf(
                OracleClient.Point(112, 176, Buttons.LEFT_BUTTON),
                press,
                OracleClient.Point(130, 60, Buttons.LEFT_DRAG),
            )
            val expected2 = oracle.pointsSized(save, sequence, 416, 480).state
            val actual2 = midend(save)
            for (point in sequence) actual2.processKey(point.x, point.y, point.button, TILE)
            assertSame("count drag", expected2, actual2)
            assertEquals(1, actual2.serialise().values("MOVE").size)
        }

    @Test
    fun `the keyboard ring and keys match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val sequences = listOf(
            listOf(Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_DOWN, 'G'.code, 'V'.code, 'Z'.code),
            listOf(Buttons.CURSOR_DOWN, '1'.code, '2'.code, '3'.code, 'e'.code, 'E'.code),
            listOf(Buttons.CURSOR_DOWN, 'g'.code, 'v'.code, 'z'.code, 'M'.code),
            listOf(Buttons.CURSOR_DOWN, 'a'.code),
            listOf(Buttons.CURSOR_DOWN, 'A'.code),
            listOf(Buttons.CURSOR_DOWN, '\b'.code),
            listOf(Buttons.CURSOR_DOWN, '0'.code),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT2),
            // A modified arrow is not a cursor move.
            listOf(Buttons.CURSOR_DOWN or Buttons.MOD_SHFT),
            listOf(Buttons.CURSOR_DOWN or Buttons.MOD_CTRL),
            // A digit with the keypad flag is still a digit.
            listOf(Buttons.CURSOR_DOWN, Buttons.MOD_NUM_KEYPAD or '1'.code),
            // A hidden cursor makes a monster key unused.
            listOf('G'.code),
        )
        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val actual = midend(save)
            for (key in sequence) actual.processKey(0, 0, key, TILE)
            assertSame("keys $sequence", expected, actual)
        }
    }

    @Test
    fun `the pencil keep highlight preference changes the mouse highlight`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state

            // The oracle cannot send a pointer click with preferences, so
            // the mouse half is a Kotlin pin; the engine test has the same
            // case and the trace test covers the drawing.
            val kept = midend(save)
            kept.processKey(112, 176, Buttons.LEFT_BUTTON, TILE)
            kept.processKey(0, 0, 'G'.code, TILE)
            assertTrue("the default keeps the mouse highlight", (kept.ui as Undead.Ui).hshow)
            assertEquals(1, kept.serialise().values("MOVE").size)

            val hidden = midend(save)
            (hidden.ui as Undead.Ui).pencilKeepHighlight = false
            hidden.processKey(112, 176, Buttons.LEFT_BUTTON, TILE)
            hidden.processKey(0, 0, 'G'.code, TILE)
            assertFalse("the preference hides the mouse highlight", (hidden.ui as Undead.Ui).hshow)
            assertEquals(1, hidden.serialise().values("MOVE").size)

            // A keyboard highlight is never hidden; the oracle supports
            // the preference on a key sequence, and both sides agree.
            val sequence = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                'G'.code,
            )
            for (pref in listOf("true", "false")) {
                val expected = oracle.keys(
                    save,
                    sequence,
                    prefs = "pencil-keep-highlight=$pref",
                )
                val actual = midend(save)
                (actual.ui as Undead.Ui).pencilKeepHighlight = pref == "true"
                for (key in sequence) actual.processKey(0, 0, key, TILE)
                assertSame("keyboard pref $pref", expected, actual)
            }
        }

    @Test
    fun `the requested keys match the oracle`() = OracleClient().use { oracle ->
        for (params in Undead.presets().map { it.params }) {
            val save = oracle.newGame("undead", params, SEED).state
            val expected = oracle.requestKeys(save)
            val actual = Undead.requestKeys(Undead.decodeParams(params))
            assertEquals("'$params' arrow mode", 2, expected.arrowMode)
            assertEquals("'$params' arrow mode", name.boyle.chris.sgtpuzzles.engine.ArrowMode.ARROWS_LEFT, actual.arrowMode)
            assertEquals(
                "'$params' labels",
                expected.labels.map { Triple(it.label, it.button, it.needsArrows) },
                actual.labels.map { Triple(it.name, it.button, it.needsArrows) },
            )
        }
    }

    @Test
    fun `the final points trace is the oracle trace`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val state = midend(save).state as Undead.State
        val cell = firstFloor(state)
        val points = listOf(cellPoint(cell.first, cell.second, TILE))
        val expected = oracle.pointsSized(
            save,
            points,
            416,
            480,
            recordTrace = true,
        )
        val actual = midend(save)
        for (point in points) actual.processKey(point.x, point.y, point.button, TILE)
        assertTrace(
            "final points trace",
            requireNotNull(expected.trace),
            KotlinTraceSink.stateTrace(actual),
        )
        assertEquals(416, expected.state.width)
        assertEquals(480, expected.state.height)
        assertFalse(actual.serialise().records.any { it.key == "UI" })
    }
}
