package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: unruly.
 *
 * Layout, real pointer clicks at four tile sizes, the right and middle
 * button mapping, the half-tile boundary, the letterbox of every board
 * size, press and drag, the click-then-cursor rule, and the keyboard are
 * compared with the oracle. Drawing and input use one geometry.
 *
 * A real placement must change the grid and add one move; a click on a
 * clue, a click outside the board, a lone drag, and a lone release must
 * change neither.
 */
class UnrulyInputParityTest {

    /** A letterboxed 411x702 view of one board size. */
    private data class Letterbox(
        val params: String,
        val desc: String,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private val letterboxes = listOf(
        Letterbox("8x8dt", DESC_8X8, 45, 3, 148),
        Letterbox("10x10de", DESC_10X10, 37, 2, 147),
        Letterbox("14x14dn", DESC_14X14, 27, 3, 148),
    )

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        val presets = listOf(
            "8x8dt" to 8,
            "8x8de" to 8,
            "8x8dn" to 8,
            "10x10de" to 10,
            "10x10dn" to 10,
            "14x14de" to 14,
            "14x14dn" to 14,
        )
        val viewports = listOf(
            411 to 702,
            720 to 1280,
            1024 to 600,
            200 to 200,
        )
        val tiles = listOf(48, 63, 82, 100)

        for ((params, boardWidth) in presets) {
            val start = oracle.newGame("unruly", params, SEED)
            val exact = tiles.map { (boardWidth + 1) * it to (boardWidth + 1) * it }
            for ((width, height) in viewports + exact) {
                val layout = oracle.layout(start.state, width, height)
                val midend = Midend.newGame(Unruly, params, SEED)
                val geometry = midend.geometryFor(width, height)
                val size = Unruly.computeSize(Unruly.decodeParams(params), geometry.tileSize)
                val where = "'$params' in ${width}x$height"

                assertEquals("computeSize width $where", layout.width, size.width)
                assertEquals("computeSize height $where", layout.height, size.height)
                assertEquals("layout width $where", layout.width, geometry.width)
                assertEquals("layout height $where", layout.height, geometry.height)
                assertEquals(
                    "origin x $where",
                    (width - layout.width) / 2,
                    geometry.originX,
                )
                assertEquals(
                    "origin y $where",
                    (height - layout.height) / 2,
                    geometry.originY,
                )
                if (width == height && width % (boardWidth + 1) == 0) {
                    assertEquals("tile $where", width / (boardWidth + 1), geometry.tileSize)
                }
            }
        }
    }

    @Test
    fun `the left click cycle places one, zero, then clears`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            for (tile in listOf(48, 63, 82, 100)) {
                val view = 9 * tile
                val x = tile / 2
                val y = tile / 2
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val points = ArrayList<OracleClient.Point>()
                val steps = listOf(
                    "P1,0,0",
                    "P0,0,0",
                    "P-,0,0",
                )

                for ((step, move) in steps.withIndex()) {
                    val beforeGrid = (midend.state as Unruly.State).grid.copyOf()
                    val beforeCount = midend.stateCount
                    points += OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
                    val expected = oracle.pointsSized(
                        save,
                        points,
                        width = view,
                        height = view,
                    )
                    assertEquals(
                        "tile $tile step $step outcome",
                        KeyOutcome.SomeEffect,
                        midend.processKey(x, y, Buttons.LEFT_BUTTON, tile),
                    )
                    assertEquals(
                        "tile $tile step $step move",
                        move,
                        midend.serialise().values("MOVE").last(),
                    )
                    assertEquals(
                        "tile $tile step $step adds one state",
                        beforeCount + 1,
                        midend.stateCount,
                    )
                    assertNotEquals(
                        "tile $tile step $step changes cell 0,0",
                        beforeGrid[0],
                        (midend.state as Unruly.State).grid[0],
                    )
                    assertEquals(
                        "tile $tile step $step save",
                        expected.state.state,
                        midend.serialise().serialise(),
                    )
                    assertEquals(
                        "tile $tile step $step text",
                        oracle.text(expected.state.state),
                        midend.textFormat(),
                    )
                }
                assertEquals("three history entries", 4, midend.stateCount)
            }
        }

    @Test
    fun `right and middle clicks use the C mapping`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_8X8)
        for (tile in listOf(48, 63, 82, 100)) {
            val view = 9 * tile
            val cellX = tile / 2
            val cellY = tile / 2
            val cases = listOf(
                listOf(OracleClient.Point(cellX, cellY, Buttons.RIGHT_BUTTON)) to
                    listOf("P0,0,0"),
                listOf(
                    OracleClient.Point(cellX, cellY, Buttons.LEFT_BUTTON),
                    OracleClient.Point(cellX, cellY, Buttons.MIDDLE_BUTTON),
                ) to listOf("P1,0,0", "P-,0,0"),
            )

            for ((points, moves) in cases) {
                val expected = oracle.pointsSized(save, points, width = view, height = view)
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                for (point in points) {
                    assertEquals(
                        "tile $tile $moves outcome",
                        KeyOutcome.SomeEffect,
                        midend.processKey(point.x, point.y, point.button, tile),
                    )
                }
                assertEquals("tile $tile $moves", moves, midend.serialise().values("MOVE"))
                assertEquals("tile $tile $moves states", 1 + moves.size, midend.stateCount)
                assertEquals("tile $tile $moves save", expected.state.state,
                    midend.serialise().serialise())
                assertEquals("tile $tile $moves text", oracle.text(expected.state.state),
                    midend.textFormat())
            }

            // A middle click on an empty cell is a no-op: the target is
            // `-` and the cursor is hidden.
            val empty = Midend.fromSave(Unruly, SgtpSave.parse(save))
            assertEquals(
                "tile $tile middle no-op",
                KeyOutcome.NoEffect,
                empty.processKey(cellX, cellY, Buttons.MIDDLE_BUTTON, tile),
            )
            assertEquals(1, empty.stateCount)
            assertEquals(save, empty.serialise().serialise())
        }
    }

    @Test
    fun `the half-tile boundary, the last cells, and the letterbox match the oracle`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            for (tile in listOf(48, 63, 82, 100)) {
                val view = 9 * tile
                fun at(cell: Int): Int = cell * tile + tile / 2
                val points = listOf(
                    OracleClient.Point(tile / 2 - 1, tile / 2 - 1, Buttons.LEFT_BUTTON),
                    OracleClient.Point(at(0), at(0), Buttons.LEFT_BUTTON),
                    OracleClient.Point(at(7), at(0), Buttons.LEFT_BUTTON), // clue (7,0)
                    OracleClient.Point(at(8), at(0), Buttons.LEFT_BUTTON), // gx == w2
                    OracleClient.Point(at(7), at(7), Buttons.LEFT_BUTTON), // clue (7,7)
                    OracleClient.Point(at(6), at(7), Buttons.LEFT_BUTTON), // empty (6,7)
                    OracleClient.Point(view - 1, view - 1, Buttons.LEFT_BUTTON), // gx == w2
                )
                val expected = oracle.pointsSized(save, points, width = view, height = view)
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                for (point in points) {
                    midend.processKey(point.x, point.y, Buttons.LEFT_BUTTON, tile)
                }
                assertEquals("tile $tile boundary save", expected.state.state,
                    midend.serialise().serialise())
                assertEquals(
                    "tile $tile boundary moves",
                    listOf("P1,0,0", "P1,6,7"),
                    midend.serialise().values("MOVE"),
                )
                assertEquals("tile $tile boundary states", 3, midend.stateCount)

                // The border pixel and the two out-of-grid pixels are
                // inert: no move, no state, and no save change.
                val inert = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val startSave = inert.serialise().serialise()
                for (point in listOf(points[0], points[3], points[6])) {
                    val before = (inert.state as Unruly.State).grid.copyOf()
                    assertEquals(
                        "tile $tile inert ${point.x},${point.y}",
                        KeyOutcome.Unused,
                        inert.processKey(point.x, point.y, Buttons.LEFT_BUTTON, tile),
                    )
                    assertEquals("tile $tile inert states", 1, inert.stateCount)
                    assertTrue(before.contentEquals((inert.state as Unruly.State).grid))
                }
                assertEquals("tile $tile inert save", startSave,
                    inert.serialise().serialise())

                // A clue click is UI only: it hides the cursor and
                // changes neither the grid nor the save.
                val clue = Midend.fromSave(Unruly, SgtpSave.parse(save))
                assertEquals(KeyOutcome.SomeEffect,
                    clue.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
                assertEquals(
                    "tile $tile clue click",
                    KeyOutcome.SomeEffect,
                    clue.processKey(at(7), at(0), Buttons.LEFT_BUTTON, tile),
                )
                assertEquals("tile $tile clue click states", 1, clue.stateCount)
                assertEquals("tile $tile clue click save", startSave,
                    clue.serialise().serialise())
                assertEquals(0, clue.serialise().values("MOVE").size)
            }

            // The letterboxed 411x702 viewport of every board size.
            for (case in letterboxes) {
                val letterbox = Midend.fromSave(
                    Unruly,
                    SgtpSave.parse(saveText(case.params, case.desc)),
                )
                val geometry = letterbox.geometryFor(411, 702)
                assertEquals("tile for ${case.params}", case.tile, geometry.tileSize)
                assertEquals("origin x for ${case.params}", case.originX, geometry.originX)
                assertEquals("origin y for ${case.params}", case.originY, geometry.originY)
                val startSave = letterbox.serialise().serialise()

                // A click in the margin is outside the board.
                assertEquals(
                    "margin click for ${case.params}",
                    KeyOutcome.Unused,
                    letterbox.processKey(
                        -geometry.originX + 1,
                        -geometry.originY + 1,
                        Buttons.LEFT_BUTTON,
                        geometry.tileSize,
                    ),
                )
                assertEquals(
                    "a margin click adds no state",
                    1,
                    letterbox.stateCount,
                )
                assertEquals(startSave, letterbox.serialise().serialise())

                // The first cell of the board is one half tile in from
                // the game origin.
                val cellX = geometry.tileSize / 2
                val cellY = geometry.tileSize / 2
                val expectedCell = oracle.pointsSized(
                    saveText(case.params, case.desc),
                    listOf(
                        OracleClient.Point(
                            geometry.originX + cellX,
                            geometry.originY + cellY,
                            Buttons.LEFT_BUTTON,
                        ),
                    ),
                    width = 411,
                    height = 702,
                )
                assertEquals(
                    "cell click for ${case.params}",
                    KeyOutcome.SomeEffect,
                    letterbox.processKey(
                        cellX,
                        cellY,
                        Buttons.LEFT_BUTTON,
                        geometry.tileSize,
                    ),
                )
                assertEquals("cell click states for ${case.params}", 2,
                    letterbox.stateCount)
                assertEquals(listOf("P1,0,0"), letterbox.serialise().values("MOVE"))
                assertEquals(
                    "letterbox cell save for ${case.params}",
                    expectedCell.state.state,
                    letterbox.serialise().serialise(),
                )
                assertEquals(
                    "letterbox cell text for ${case.params}",
                    oracle.text(expectedCell.state.state),
                    letterbox.textFormat(),
                )
            }
        }

    @Test
    fun `a press drag and release makes one move at the press`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            for (tile in listOf(48, 63, 82, 100)) {
                val view = 9 * tile
                fun at(cell: Int): Int = cell * tile + tile / 2
                val points = listOf(
                    OracleClient.Point(at(0), at(0), Buttons.LEFT_BUTTON),
                    OracleClient.Point(at(2), at(2), Buttons.LEFT_DRAG),
                    OracleClient.Point(at(4), at(4), Buttons.LEFT_DRAG),
                    OracleClient.Point(at(4), at(4), Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(save, points, width = view, height = view)
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val before = (midend.state as Unruly.State).grid.copyOf()
                assertEquals(
                    "tile $tile press outcome",
                    KeyOutcome.SomeEffect,
                    midend.processKey(
                        points[0].x, points[0].y, points[0].button, tile,
                    ),
                )
                for (point in points.drop(1)) {
                    assertEquals(
                        "tile $tile drag outcome",
                        KeyOutcome.Unused,
                        midend.processKey(point.x, point.y, point.button, tile),
                    )
                }
                assertEquals("tile $tile drag save", expected.state.state,
                    midend.serialise().serialise())
                assertEquals("tile $tile drag move", listOf("P1,0,0"),
                    midend.serialise().values("MOVE"))
                assertEquals("tile $tile drag states", 2, midend.stateCount)
                assertNotEquals("tile $tile drag cell", before[0],
                    (midend.state as Unruly.State).grid[0])
            }

            // A lone drag and a lone release are ignored by the midend,
            // so they change nothing.
            for (tile in listOf(48, 63, 82, 100)) {
                val lone = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val before = (lone.state as Unruly.State).grid.copyOf()
                val x = 3 * tile + tile / 2
                assertEquals(
                    "tile $tile lone drag",
                    KeyOutcome.Unused,
                    lone.processKey(x, x, Buttons.LEFT_DRAG, tile),
                )
                assertEquals(
                    "tile $tile lone release",
                    KeyOutcome.Unused,
                    lone.processKey(x, x, Buttons.LEFT_RELEASE, tile),
                )
                assertEquals(1, lone.stateCount)
                assertTrue(before.contentEquals((lone.state as Unruly.State).grid))
                assertEquals(save, lone.serialise().serialise())
            }
        }

    @Test
    fun `a click then a cursor key and a select use the clicked cell`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            for (tile in listOf(48, 63, 82, 100)) {
                val view = 9 * tile
                fun at(cell: Int): Int = cell * tile + tile / 2

                // The click at (3,3) is the clue at (3,3): it only moves
                // the Android cursor and hides the ring. RIGHT then goes
                // to (4,3), and SELECT places 1 there.
                val one = listOf(
                    OracleClient.Point(at(3), at(3), Buttons.LEFT_BUTTON),
                    OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT),
                    OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                )
                val expectedOne = oracle.pointsSized(save, one, width = view, height = view)
                val midendOne = Midend.fromSave(Unruly, SgtpSave.parse(save))
                for (point in one) {
                    midendOne.processKey(point.x, point.y, point.button, tile)
                }
                assertEquals("tile $tile click cursor select move", listOf("P1,4,3"),
                    midendOne.serialise().values("MOVE"))
                assertEquals("tile $tile click cursor select states", 2,
                    midendOne.stateCount)
                assertEquals("tile $tile click cursor select save", expectedOne.state.state,
                    midendOne.serialise().serialise())

                // The clue click is UI only. With a visible ring it is a
                // UI update: the android cursor moves to the clicked
                // cell, the ring hides, and neither the grid nor the
                // save changes.
                val uiOnly = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val uiStart = uiOnly.serialise().serialise()
                val beforeClue = (uiOnly.state as Unruly.State).grid.copyOf()
                assertEquals(KeyOutcome.SomeEffect,
                    uiOnly.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
                assertEquals(
                    "tile $tile clue click outcome",
                    KeyOutcome.SomeEffect,
                    uiOnly.processKey(at(3), at(3), Buttons.LEFT_BUTTON, tile),
                )
                val ui = uiOnly.ui as Unruly.Ui
                assertEquals("tile $tile clue cursor x", 3, ui.curX)
                assertEquals("tile $tile clue cursor y", 3, ui.curY)
                assertFalse("tile $tile clue hides the ring", ui.curVisible)
                assertEquals("tile $tile clue click states", 1, uiOnly.stateCount)
                assertEquals("tile $tile clue click save", uiStart,
                    uiOnly.serialise().serialise())
                assertTrue("tile $tile clue click grid",
                    beforeClue.contentEquals((uiOnly.state as Unruly.State).grid))

                // The same click without the cursor key leaves the ring
                // hidden, so SELECT is unused and there is no move.
                val two = listOf(
                    OracleClient.Point(at(3), at(3), Buttons.LEFT_BUTTON),
                    OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                )
                val expectedTwo = oracle.pointsSized(save, two, width = view, height = view)
                val midendTwo = Midend.fromSave(Unruly, SgtpSave.parse(save))
                for (point in two) {
                    midendTwo.processKey(point.x, point.y, point.button, tile)
                }
                assertEquals("tile $tile no move", emptyList<String>(),
                    midendTwo.serialise().values("MOVE"))
                assertEquals("tile $tile no move states", 1, midendTwo.stateCount)
                assertEquals("tile $tile no move save", expectedTwo.state.state,
                    midendTwo.serialise().serialise())

                // A click on the empty cell (4,4), then UP to (4,3), then
                // SELECT: two moves.
                val three = listOf(
                    OracleClient.Point(at(4), at(4), Buttons.LEFT_BUTTON),
                    OracleClient.Point(0, 0, Buttons.CURSOR_UP),
                    OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
                )
                val expectedThree = oracle.pointsSized(save, three, width = view, height = view)
                val midendThree = Midend.fromSave(Unruly, SgtpSave.parse(save))
                for (point in three) {
                    midendThree.processKey(point.x, point.y, point.button, tile)
                }
                assertEquals(
                    "tile $tile click up select moves",
                    listOf("P1,4,4", "P1,4,3"),
                    midendThree.serialise().values("MOVE"),
                )
                assertEquals("tile $tile click up select states", 3,
                    midendThree.stateCount)
                assertEquals("tile $tile click up select save", expectedThree.state.state,
                    midendThree.serialise().serialise())
            }
        }

    @Test
    fun `keyboard sequences match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_8X8)

        val cases = listOf(
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT, '0'.code),
            listOf(Buttons.CURSOR_RIGHT, '\b'.code),
            listOf(Buttons.CURSOR_RIGHT, '0'.code),
            listOf(Buttons.CURSOR_RIGHT, '1'.code),
            listOf(Buttons.CURSOR_RIGHT, '2'.code),
            listOf('1'.code, '\b'.code),
            // A modified cursor key is stripped, so it moves the cursor.
            listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, Buttons.CURSOR_SELECT),
            // A keypad digit places.
            listOf(
                Buttons.CURSOR_LEFT,
                '1'.code or Buttons.MOD_NUM_KEYPAD,
            ),
            // A clamped edge key with a visible cursor is a no-effect.
            listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_LEFT),
        )

        for (sequence in cases) {
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (button in sequence) {
                midend.processKey(0, 0, button, 32)
            }
            assertEquals("keys $sequence save", expected.state, midend.serialise().serialise())
            assertEquals("keys $sequence status", expected.status, midend.status())
        }
    }

    @Test
    fun `a real placement changes the grid and a clue click does not`() {
        val midend = Midend.fromSave(Unruly, SgtpSave.parse(saveText("8x8dt", DESC_8X8)))
        val tile = 48
        val before = (midend.state as Unruly.State).grid.copyOf()
        val startSave = midend.serialise().serialise()

        // A clue click is UI only, but it does hide the cursor.
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 24, Buttons.LEFT_BUTTON, tile))
        assertEquals("a clue click adds no state", 1, midend.stateCount)
        assertEquals(startSave, midend.serialise().serialise())
        assertTrue(before.contentEquals((midend.state as Unruly.State).grid))

        // A real placement changes the grid, the move count, and the
        // save.
        assertEquals("cell (0,0) starts empty", 0, before[0])
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 24, Buttons.LEFT_BUTTON, tile))
        assertEquals(2, midend.stateCount)
        assertNotEquals(before[0], (midend.state as Unruly.State).grid[0])
        assertEquals(listOf("P1,0,0"), midend.serialise().values("MOVE"))
    }

    private fun saveText(params: String, desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Unruly.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_8X8 = "bEACHcBdBDEalcbAbbBa"
        const val DESC_10X10 = "CcGDBgbCcbfbhdbBCdEcbkbABdd"
        const val DESC_14X14 = "BEdbFBgAAMeBccbcCddabFfHiCEAFBFejbbkfdAcgDDcec"
    }
}
