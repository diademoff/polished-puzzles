package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Towers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: towers.
 *
 * The layout, real pointer presses at four tile sizes and a letterbox
 * view, the 3D hit test through real clicks, the clue bands, the drag
 * and release cases the midend maps for the game, and the keyboard.
 * Every "no change" case sits next to a case that changes the same
 * board, so a dropped input path cannot pass silently.
 */
class TowersInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val save = File(testdata, "saves/initial/towers/5de.sgtp").readText()
    private val sparse = File(testdata, "saves/initial/towers/5dh.sgtp").readText()

    private fun midend(): Midend = Midend.fromSave(Towers, SgtpSave.parse(save))

    private fun center(x: Int, y: Int, tile: Int = 48): Pair<Int, Int> {
        val border = tile * 9 / 8
        return Pair(border + x * tile + tile / 2, border + y * tile + tile / 2)
    }

    private fun press(x: Int, y: Int, button: Int = Buttons.LEFT_BUTTON, tile: Int = 48):
        List<OracleClient.Point> {
        val (px, py) = center(x, y, tile)
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> button
        }
        return listOf(
            OracleClient.Point(px, py, button),
            OracleClient.Point(px, py, release),
        )
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tile: Int = 48,
    ): List<KeyOutcome> = points.map { point ->
        midend.processKey(point.x, point.y, point.button, tile)
    }

    private fun assertSame(what: String, expected: GameState, midend: Midend) {
        assertEquals("$what save", expected.state, midend.serialise().serialise())
        assertEquals("$what id", expected.id, midend.id())
        assertEquals("$what status", expected.status, midend.status())
        assertEquals("$what undo", expected.canUndo, midend.canUndo)
        assertEquals("$what redo", expected.canRedo, midend.canRedo)
    }

    @Test(timeout = 300_000)
    fun `the layout matches every viewport`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702,
            720 to 1280,
            1024 to 600,
            200 to 200,
            1080 to 1920,
        )
        for ((viewWidth, viewHeight) in viewports) {
            val expected = oracle.layout(save, viewWidth, viewHeight)
            val midend = midend()
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            assertEquals("${viewWidth}x$viewHeight board width", expected.width, geometry.width)
            assertEquals("${viewWidth}x$viewHeight board height", expected.height, geometry.height)
            val board = Towers.computeSize(Towers.decodeParams("5de"), geometry.tileSize)
            assertEquals("${viewWidth}x$viewHeight computed", board.width, geometry.width)
            assertEquals("${viewWidth}x$viewHeight computed height", board.height, geometry.height)
            assertEquals(
                "${viewWidth}x$viewHeight origin x",
                (viewWidth - expected.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "${viewWidth}x$viewHeight origin y",
                (viewHeight - expected.height) / 2,
                geometry.originY,
            )
        }

        // The four exact tile sizes of the input matrix.
        for ((tile, board) in listOf(
            48 to 348,
            63 to 455,
            82 to 594,
            100 to 724,
        )) {
            val expected = oracle.layout(save, board, board)
            assertEquals("tile $tile board", board, expected.width)
            assertEquals("tile $tile height", board, expected.height)
            assertEquals(
                "tile $tile computed",
                board,
                Towers.computeSize(Towers.decodeParams("5de"), tile).width,
            )
        }
    }

    @Test(timeout = 300_000)
    fun `requestKeys matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in Towers.presets().map { it.params } + "") {
            val start = oracle.newGame("towers", params, "12345").state
            for (fullCursor in listOf(false, true)) {
                val expected = oracle.requestKeys(start, fullCursor)
                val decoded = if (params.isEmpty()) {
                    Towers.defaultParams()
                } else {
                    Towers.decodeParams(params)
                }
                val actual = Towers.requestKeys(decoded, fullCursor)
                assertEquals("$params/$fullCursor arrow mode", 2, expected.arrowMode)
                assertEquals("$params/$fullCursor arrows", ArrowMode.ARROWS_LEFT, actual.arrowMode)
                assertEquals("$params/$fullCursor key count", expected.labels.size, actual.labels.size)
                for (i in expected.labels.indices) {
                    assertEquals(
                        "$params/$fullCursor label $i text",
                        expected.labels[i].label,
                        actual.labels[i].name,
                    )
                    assertEquals(
                        "$params/$fullCursor label $i button",
                        expected.labels[i].button,
                        actual.labels[i].button,
                    )
                    assertEquals(
                        "$params/$fullCursor label $i needsArrows",
                        expected.labels[i].needsArrows,
                        actual.labels[i].needsArrows,
                    )
                    assertFalse("$params/$fullCursor label $i is not arrow-only", actual.labels[i].needsArrows)
                }
            }
        }
    }

    @Test(timeout = 600_000)
    fun `real presses at four tile sizes match the oracle`() =
        OracleClient().use { oracle ->
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Towers.computeSize(Towers.decodeParams("5de"), tile).width
                val steps = listOf(
                    press(0, 0, tile = tile),
                    press(0, 0, tile = tile),
                    listOf(OracleClient.Point(0, 0, '3'.code)),
                    press(1, 0, tile = tile),
                    listOf(OracleClient.Point(0, 0, '2'.code)),
                    press(2, 0, Buttons.RIGHT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '4'.code)),
                    press(4, 4, tile = tile),
                    listOf(OracleClient.Point(0, 0, '0'.code)),
                    listOf(OracleClient.Point(0, 0, 'M'.code)),
                    press(3, 0, tile = tile),
                    press(3, 0, tile = tile),
                    listOf(OracleClient.Point(0, 0, '5'.code)),
                )
                val midend = midend()
                val points = ArrayList<OracleClient.Point>()
                for ((index, step) in steps.withIndex()) {
                    points += step
                    val expected = oracle.pointsSized(save, points, board, board)
                    val outcomes = apply(midend, step, tile)
                    assertSame("tile $tile step $index", expected.state, midend)
                    if (step.size == 2) {
                        assertEquals(
                            "tile $tile step $index press",
                            KeyOutcome.SomeEffect,
                            outcomes.first(),
                        )
                        assertEquals(
                            "tile $tile step $index release",
                            KeyOutcome.Unused,
                            outcomes.last(),
                        )
                    }
                    when (index) {
                        0 -> {
                            assertEquals("tile $tile first click", 1, midend.stateCount)
                            assertNotNull("tile $tile highlight", midend.cursorLocation(tile))
                        }

                        1 -> {
                            assertEquals("tile $tile second click", 1, midend.stateCount)
                            assertEquals(
                                "tile $tile pencil highlight",
                                Game.CursorRect(
                                    tile * 9 / 8,
                                    tile * 9 / 8,
                                    tile,
                                    tile,
                                ),
                                midend.cursorLocation(tile),
                            )
                        }

                        2 -> {
                            assertEquals(
                                "tile $tile pencil move",
                                "P0,0,3",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile pencil count", 2, midend.stateCount)
                            assertEquals(
                                "tile $tile pencil leaves the grid empty",
                                0,
                                (midend.state as Towers.State).grid[0],
                            )
                            assertEquals(
                                "tile $tile pencil bitmap",
                                1 shl 3,
                                (midend.state as Towers.State).pencil[0],
                            )
                        }

                        3 -> {
                            assertEquals("tile $tile move to next cell", 2, midend.stateCount)
                        }

                        4 -> {
                            assertEquals(
                                "tile $tile second pencil move",
                                "P1,0,2",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile second pencil count", 3, midend.stateCount)
                            assertEquals(
                                "tile $tile second pencil bitmap",
                                1 shl 2,
                                (midend.state as Towers.State).pencil[1],
                            )
                        }

                        5 -> {
                            assertEquals("tile $tile right click", 3, midend.stateCount)
                        }

                        6 -> {
                            assertEquals(
                                "tile $tile pencil under right click",
                                "P2,0,4",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile third pencil count", 4, midend.stateCount)
                            assertEquals(
                                "tile $tile third pencil bitmap",
                                1 shl 4,
                                (midend.state as Towers.State).pencil[2],
                            )
                        }

                        7 -> {
                            assertEquals("tile $tile clear target", 4, midend.stateCount)
                            assertEquals(
                                "tile $tile target is empty",
                                0,
                                (midend.state as Towers.State).grid[24],
                            )
                        }

                        8 -> {
                            // A clear of an already empty highlighted cell
                            // is a no-op with the default preference.
                            assertEquals(
                                "tile $tile inert clear outcome",
                                KeyOutcome.Unused,
                                outcomes.single(),
                            )
                            assertEquals("tile $tile inert clear", 4, midend.stateCount)
                            assertEquals(
                                "tile $tile inert clear leaves the board",
                                0,
                                (midend.state as Towers.State).grid[24],
                            )
                        }

                        9 -> {
                            assertEquals(
                                "tile $tile M outcome",
                                KeyOutcome.SomeEffect,
                                outcomes.single(),
                            )
                            assertEquals(
                                "tile $tile M",
                                "M",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile M count", 5, midend.stateCount)
                            assertEquals("tile $tile M status", 0, midend.status())
                            assertTrue(
                                "tile $tile M marks the empty cells",
                                (midend.state as Towers.State).pencil[0] != 0,
                            )
                            assertTrue(
                                "tile $tile M leaves the grid empty",
                                (midend.state as Towers.State).grid.all { it == 0 },
                            )
                        }

                        10 -> {
                            assertEquals("tile $tile real digit target", 5, midend.stateCount)
                        }

                        11 -> {
                            assertEquals(
                                "tile $tile second click keeps the move count",
                                5,
                                midend.stateCount,
                            )
                        }

                        12 -> {
                            assertEquals(
                                "tile $tile real digit move",
                                "R3,0,5",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile real digit count", 6, midend.stateCount)
                            assertEquals("tile $tile real digit status", 0, midend.status())
                            assertEquals(
                                "tile $tile real digit fills the cell",
                                5,
                                (midend.state as Towers.State).grid[3],
                            )
                            assertEquals(
                                "tile $tile real digit clears the pencil",
                                0,
                                (midend.state as Towers.State).pencil[3],
                            )
                        }
                    }
                }

                val final = oracle.pointsSized(save, points, board, board, recordTrace = true)
                assertLines(
                    "tile $tile final trace",
                    normalize(final.trace!!),
                    normalize(KotlinTraceSink.stateTrace(midend, tile)),
                )
            }
        }

    @Test(timeout = 600_000)
    fun `the letterbox view uses the found tile and origin`() =
        OracleClient().use { oracle ->
            val midend = midend()
            val geometry = midend.geometryFor(411, 702)
            assertEquals(56, geometry.tileSize)
            assertEquals(2, geometry.originX)
            assertEquals(148, geometry.originY)
            val tile = geometry.tileSize

            val steps = listOf(
                press(0, 0, tile = tile),
                press(0, 0, tile = tile),
                listOf(OracleClient.Point(0, 0, '3'.code)),
                press(4, 4, tile = tile),
                press(4, 4, tile = tile),
                listOf(OracleClient.Point(0, 0, '1'.code)),
                listOf(OracleClient.Point(0, 0, 'M'.code)),
                listOf(OracleClient.Point(0, 0, Buttons.CURSOR_UP)),
                listOf(OracleClient.Point(0, 0, Buttons.CURSOR_SELECT)),
                listOf(OracleClient.Point(0, 0, '2'.code)),
            )
            val viewPoints = ArrayList<OracleClient.Point>()
            for ((index, step) in steps.withIndex()) {
                viewPoints += step.map {
                    OracleClient.Point(
                        it.x + geometry.originX,
                        it.y + geometry.originY,
                        it.button,
                    )
                }
                val expected = oracle.pointsSized(save, viewPoints, 411, 702)
                val outcomes = step.map {
                    midend.processKey(it.x, it.y, it.button, tile)
                }
                assertSame("letterbox step $index", expected.state, midend)
                if (step.size == 2) {
                    assertEquals(
                        "letterbox step $index press",
                        KeyOutcome.SomeEffect,
                        outcomes.first(),
                    )
                    assertEquals(
                        "letterbox step $index release",
                        KeyOutcome.Unused,
                        outcomes.last(),
                    )
                }
                when (index) {
                    1 -> assertEquals("letterbox pencil toggle", 1, midend.stateCount)
                    2 -> {
                        assertEquals(
                            "letterbox pencil move",
                            "P0,0,3",
                            midend.serialise().values("MOVE").last(),
                        )
                        assertEquals("letterbox pencil count", 2, midend.stateCount)
                        assertEquals(
                            "letterbox pencil leaves the grid empty",
                            0,
                            (midend.state as Towers.State).grid[0],
                        )
                    }

                    4 -> assertEquals("letterbox second click", 2, midend.stateCount)

                    5 -> {
                        assertEquals(
                            "letterbox real digit move",
                            "R4,4,1",
                            midend.serialise().values("MOVE").last(),
                        )
                        assertEquals("letterbox real digit count", 3, midend.stateCount)
                        assertEquals(
                            "letterbox real digit fills the cell",
                            1,
                            (midend.state as Towers.State).grid[24],
                        )
                    }

                    6 -> {
                        assertEquals(
                            "letterbox M",
                            "M",
                            midend.serialise().values("MOVE").last(),
                        )
                        assertEquals("letterbox M count", 4, midend.stateCount)
                    }

                    9 -> {
                        assertEquals(
                            "letterbox key pencil move",
                            "P4,3,2",
                            midend.serialise().values("MOVE").last(),
                        )
                        assertEquals("letterbox key pencil count", 5, midend.stateCount)
                    }
                }
            }

            val final = oracle.pointsSized(
                save,
                viewPoints,
                411,
                702,
                recordTrace = true,
            )
            assertLines(
                "letterbox final trace",
                normalize(final.trace!!),
                normalize(KotlinTraceSink.stateTrace(midend, tile)),
            )
            assertEquals(5, midend.stateCount)
            assertEquals(0, midend.status())
        }

    @Test(timeout = 600_000)
    fun `real clicks on the 3D tower faces select the tower cell`() =
        OracleClient().use { oracle ->
            // Put a height-3 tower at (0,0), then click the protrusions.
            val withTower = oracle.keys(save, listOf(Buttons.CURSOR_UP, '3'.code)).state
            val clicks = listOf(
                // Inside the top face, above the grid.
                90 to 50,
                // Inside the upper-left triangle.
                56 to 52,
                // One pixel further left: outside the triangle.
                54 to 52,
                // A centre click of the geometric cell.
                78 to 78,
            )
            for ((x, y) in clicks) {
                val points = listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(withTower, points, 348, 348)
                val midend = Midend.fromSave(Towers, SgtpSave.parse(withTower))
                val outcomes = apply(midend, points)
                assertSame("click $x,$y", expected.state, midend)
                assertEquals("click $x,$y press", KeyOutcome.SomeEffect, outcomes.first())
            }

            // The tower's cell is selected by a click in its top face;
            // 2D mode would put the same click in the clue band.
            val inTop = listOf(
                OracleClient.Point(90, 50, Buttons.LEFT_BUTTON),
                OracleClient.Point(90, 50, Buttons.LEFT_RELEASE),
            )
            val threeD = Midend.fromSave(Towers, SgtpSave.parse(withTower))
            apply(threeD, inTop)
            assertEquals(
                "3D selects the tower cell",
                Game.CursorRect(54, 54, 48, 48),
                threeD.cursorLocation(48),
            )
            val twoD = Midend.fromSave(Towers, SgtpSave.parse(withTower))
            (twoD.ui as Towers.Ui).threeD = false
            apply(twoD, inTop)
            assertNull("2D clears the highlight", twoD.cursorLocation(48))

            // A click on the top face of a second tower selects that
            // tower. The two towers overlap in the hit test order.
            val twoTowers = oracle.pointsSized(
                withTower,
                press(1, 0) + listOf(OracleClient.Point(0, 0, '3'.code)),
                348,
                348,
            ).state.state
            val overlap = listOf(
                OracleClient.Point(120, 50, Buttons.LEFT_BUTTON),
                OracleClient.Point(120, 50, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(twoTowers, overlap, 348, 348)
            val midend = Midend.fromSave(Towers, SgtpSave.parse(twoTowers))
            apply(midend, overlap)
            assertSame("second tower", expected.state, midend)
            assertEquals(
                "the second tower is selected",
                Game.CursorRect(102, 54, 48, 48),
                midend.cursorLocation(48),
            )
        }

    @Test(timeout = 600_000)
    fun `a real click in the lower-right triangle selects the tower cell`() =
        OracleClient().use { oracle ->
            // Two height-3 towers at (0,0) and (1,0). The tower at
            // (0,0) protrudes into the bottom-right triangle, and its
            // top face ends one pixel above the click, so only that
            // triangle can select the tower.
            val towers = oracle.keys(
                save,
                listOf(
                    Buttons.CURSOR_UP, '3'.code,
                    Buttons.CURSOR_RIGHT, '3'.code,
                ),
            ).state
            val inside = listOf(
                OracleClient.Point(103, 96, Buttons.LEFT_BUTTON),
                OracleClient.Point(103, 96, Buttons.LEFT_RELEASE),
            )
            val clicked = Midend.fromSave(Towers, SgtpSave.parse(towers))
            apply(clicked, inside)
            assertEquals(
                "the lower-right triangle selects the tower cell",
                Game.CursorRect(54, 54, 48, 48),
                clicked.cursorLocation(48),
            )

            // The digit is the observable difference: the oracle applies
            // it to (0,0), while the geometric cell would be (1,0).
            val withDigit = inside + OracleClient.Point(0, 0, '2'.code)
            val expected = oracle.pointsSized(towers, withDigit, 348, 348)
            val midend = Midend.fromSave(Towers, SgtpSave.parse(towers))
            apply(midend, withDigit)
            assertSame("lower-right triangle", expected.state, midend)
            assertEquals(
                "the lower-right triangle move",
                "R0,0,2",
                midend.serialise().values("MOVE").last(),
            )
            assertEquals(
                "the tower cell takes the digit",
                2,
                (midend.state as Towers.State).grid[0],
            )

            // One pixel below the triangle is outside it and selects
            // the geometric cell (1,0).
            val outside = listOf(
                OracleClient.Point(105, 96, Buttons.LEFT_BUTTON),
                OracleClient.Point(105, 96, Buttons.LEFT_RELEASE),
            )
            val outsideMidend = Midend.fromSave(Towers, SgtpSave.parse(towers))
            apply(outsideMidend, outside)
            assertEquals(
                "the point below the triangle selects the geometric cell",
                Game.CursorRect(102, 54, 48, 48),
                outsideMidend.cursorLocation(48),
            )
            val outsideDigit = outside + OracleClient.Point(0, 0, '2'.code)
            val outsideExpected = oracle.pointsSized(towers, outsideDigit, 348, 348)
            apply(outsideMidend, listOf(OracleClient.Point(0, 0, '2'.code)))
            assertSame("outside the lower-right triangle", outsideExpected.state, outsideMidend)
            assertEquals(
                "the geometric cell move",
                "R1,0,2",
                outsideMidend.serialise().values("MOVE").last(),
            )
        }

    @Test(timeout = 300_000)
    fun `the clue bands, the border and an absent clue match the oracle`() =
        OracleClient().use { oracle ->
            // A click beyond the clue band clears the highlight and is a
            // UI update, not an unused key.
            val outside = listOf(
                OracleClient.Point(10, 10, Buttons.LEFT_BUTTON),
                OracleClient.Point(10, 10, Buttons.LEFT_RELEASE),
            )
            val outsideExpected = oracle.pointsSized(save, outside, 348, 348)
            val outsideMidend = midend()
            val outcomes = apply(outsideMidend, outside)
            assertSame("outside", outsideExpected.state, outsideMidend)
            assertEquals("outside press", KeyOutcome.SomeEffect, outcomes.first())
            assertEquals("outside adds no move", 1, outsideMidend.stateCount)

            // A drawn clue click is a D move.
            val clue = listOf(
                OracleClient.Point(30, 78, Buttons.LEFT_BUTTON),
                OracleClient.Point(30, 78, Buttons.LEFT_RELEASE),
            )
            val clueExpected = oracle.pointsSized(save, clue, 348, 348)
            val clueMidend = midend()
            apply(clueMidend, clue)
            assertSame("clue", clueExpected.state, clueMidend)
            assertEquals("clue move", "D-1,0", clueMidend.serialise().values("MOVE").last())

            // The same click on an absent clue in 5dh clears instead.
            val absentExpected = oracle.pointsSized(sparse, listOf(
                OracleClient.Point(78, 30, Buttons.LEFT_BUTTON),
                OracleClient.Point(78, 30, Buttons.LEFT_RELEASE),
            ), 348, 348)
            val absentMidend = Midend.fromSave(Towers, SgtpSave.parse(sparse))
            val absentOutcomes = apply(
                absentMidend,
                listOf(
                    OracleClient.Point(78, 30, Buttons.LEFT_BUTTON),
                    OracleClient.Point(78, 30, Buttons.LEFT_RELEASE),
                ),
            )
            assertSame("absent clue", absentExpected.state, absentMidend)
            assertEquals("absent clue adds no move", 1, absentMidend.stateCount)
            assertEquals("absent clue press", KeyOutcome.SomeEffect, absentOutcomes.first())

            // A left click on an immutable given moves the highlight
            // there and hides it. In 5dh the given 2 is at (3,1).
            val given = press(3, 1)
            val givenExpected = oracle.pointsSized(sparse, given, 348, 348)
            val givenMidend = Midend.fromSave(Towers, SgtpSave.parse(sparse))
            apply(givenMidend, given)
            assertSame("given", givenExpected.state, givenMidend)
            assertEquals(
                "the given is filled",
                2,
                (givenMidend.state as Towers.State).grid[8],
            )
            assertNull("the given hides the highlight", givenMidend.cursorLocation(48))
        }

    @Test(timeout = 300_000)
    fun `real clicks that repeat a digit raise the error colour`() =
        OracleClient().use { oracle ->
            val points = press(0, 0) + listOf(OracleClient.Point(0, 0, '1'.code)) +
                press(1, 0) + listOf(OracleClient.Point(0, 0, '1'.code))
            val expected = oracle.pointsSized(save, points, 348, 348, recordTrace = true)
            val midend = midend()
            apply(midend, points)
            assertSame("duplicate", expected.state, midend)
            assertEquals("duplicate status", 0, midend.status())
            assertEquals("duplicate move count", 3, midend.stateCount)
            assertEquals(
                "duplicate move",
                "R1,0,1",
                midend.serialise().values("MOVE").last(),
            )
            assertEquals(
                "the board changed",
                listOf(1, 1),
                (midend.state as Towers.State).grid.take(2),
            )
            val actual = normalize(KotlinTraceSink.stateTrace(midend, 48))
            assertLines("error frame", normalize(expected.trace!!), actual)
            assertTrue(
                "the repeated digits are red",
                actual.lines().count {
                    it.startsWith("text ") && it.endsWith(" 4 \"1\"")
                } >= 2,
            )
        }

    @Test(timeout = 300_000)
    fun `drag, release, middle press and a hidden digit are unused`() =
        OracleClient().use { oracle ->
            val tile = 48
            val (x, y) = center(0, 0)
            val cases = listOf(
                listOf(OracleClient.Point(x, y, Buttons.LEFT_DRAG)),
                listOf(OracleClient.Point(x, y, Buttons.LEFT_RELEASE)),
                listOf(OracleClient.Point(x, y, Buttons.RIGHT_DRAG)),
                listOf(OracleClient.Point(x, y, Buttons.RIGHT_RELEASE)),
                listOf(OracleClient.Point(x, y, Buttons.MIDDLE_BUTTON)),
                listOf(OracleClient.Point(x, y, '3'.code)),
            )
            for (points in cases) {
                val expected = oracle.pointsSized(save, points, 348, 348)
                val midend = midend()
                val outcomes = apply(midend, points, tile)
                assertSame("case $points", expected.state, midend)
                assertTrue(
                    "case $points is unused",
                    outcomes.all { it == KeyOutcome.Unused },
                )
                assertEquals("case $points adds no move", 1, midend.stateCount)
                assertEquals(
                    "case $points changes no board",
                    save,
                    midend.serialise().serialise(),
                )
                assertEquals("case $points keeps status 0", 0, midend.status())
            }

            // The positive control: a press and a digit do change the
            // state and the move count.
            val live = midend()
            apply(live, press(0, 0), tile)
            assertEquals(
                KeyOutcome.SomeEffect,
                live.processKey(0, 0, '3'.code, tile),
            )
            assertEquals(2, live.stateCount)
            assertEquals("R0,0,3", live.serialise().values("MOVE").last())
            assertEquals(
                "the board changed",
                3,
                (live.state as Towers.State).grid[0],
            )

            // An inert click changes neither the board nor the status.
            val inert = midend()
            assertEquals(
                KeyOutcome.SomeEffect,
                inert.processKey(10, 10, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(1, inert.stateCount)
            assertEquals(0, inert.status())
            assertTrue((inert.state as Towers.State).grid.all { it == 0 })
        }

    @Test(timeout = 300_000)
    fun `the keyboard matches the oracle and the modifier rules`() =
        OracleClient().use { oracle ->
            val sequences = listOf(
                listOf(Buttons.CURSOR_UP, '3'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, '2'.code),
                listOf(Buttons.CURSOR_UP, '3'.code, '\b'.code),
                listOf(Buttons.CURSOR_UP, '3'.code, Buttons.CURSOR_SELECT2),
                listOf('M'.code),
                listOf('m'.code),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_UP),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN),
                listOf(Buttons.MOD_NUM_KEYPAD or '4'.code),
                listOf('\t'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code, '3'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val midend = midend()
                for (key in sequence) midend.processKey(0, 0, key, 48)
                assertSame("keys $sequence", expected, midend)
            }

            // A modified arrow is the D move path, and a miss is unused.
            val clue = midend()
            assertEquals(
                KeyOutcome.SomeEffect,
                clue.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT, 48),
            )
            assertEquals("D-1,0", clue.serialise().values("MOVE").last())
            // A modified arrow that misses a clue returns unused, not a
            // no-op. The cursor at (0,0) has a clue on the left, so move
            // it away first and then miss.
            val miss = Midend.fromSave(Towers, SgtpSave.parse(sparse))
            miss.processKey(0, 0, Buttons.CURSOR_UP, 48)
            assertEquals(
                KeyOutcome.Unused,
                miss.processKey(0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_UP, 48),
            )
        }

    @Test(timeout = 300_000)
    fun `a pencil mark on a filled cell is unused`() =
        OracleClient().use { oracle ->
            val tile = 48
            // Fill (0,0) through a cursor, then toggle the pencil and try
            // a mark on the filled cell.
            val start = oracle.keys(save, listOf(Buttons.CURSOR_UP, '3'.code)).state
            val expected = oracle.keys(
                start,
                listOf(Buttons.CURSOR_SELECT, '3'.code),
            )
            val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, '3'.code, tile),
            )
            assertSame("pencil on filled", expected, midend)
            assertEquals(2, midend.stateCount)
        }

    @Test(timeout = 300_000)
    fun `undo and redo after input match the oracle`() =
        OracleClient().use { oracle ->
            val afterDigit = oracle.keys(save, listOf(Buttons.CURSOR_UP, '3'.code)).state
            val digit = midend()
            apply(digit, press(0, 0))
            digit.processKey(0, 0, '3'.code, 48)
            assertSame("digit", oracle.id(afterDigit), digit)
            assertTrue(digit.undo())
            assertSame("digit undo", oracle.undo(afterDigit), digit)
            assertTrue(digit.redo())
            assertSame("digit redo", oracle.id(afterDigit), digit)

            val afterD = oracle.keys(save, listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT)).state
            val clue = midend()
            clue.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT, 48)
            assertSame("D", oracle.id(afterD), clue)
            assertTrue(clue.undo())
            assertSame("D undo", oracle.undo(afterD), clue)
            assertTrue(clue.redo())
            assertSame("D redo", oracle.id(afterD), clue)

            val afterSolve = oracle.solve(save).state
            val solved = midend()
            assertTrue(solved.solve())
            assertSame("solve", oracle.id(afterSolve), solved)
            assertTrue(solved.undo())
            assertSame("solve undo", oracle.undo(afterSolve), solved)
            assertTrue(solved.redo())
            assertSame("solve redo", oracle.id(afterSolve), solved)
            assertFalse(solved.takeCompletedEvent())
        }

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(
            trace,
            UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
        )

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }
}
