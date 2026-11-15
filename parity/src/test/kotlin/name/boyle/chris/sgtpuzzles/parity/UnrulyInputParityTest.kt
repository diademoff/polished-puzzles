package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: unruly.
 *
 * Layout, real pointer clicks at four tile sizes, the right and middle
 * button mapping, the half-tile boundary, the letterbox, press and drag,
 * the click-then-cursor rule, and the keyboard are compared with the
 * oracle. Drawing and input use one geometry.
 *
 * A real placement must change the grid and add one move; a click on a
 * clue, a click outside the board, a lone drag, and a lone release must
 * change neither.
 */
class UnrulyInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("8x8dt", "10x10de", "14x14dn")) {
            for ((width, height) in listOf(
                411 to 702,
                720 to 1280,
                1024 to 600,
                200 to 200,
            )) {
                val start = oracle.newGame("unruly", params, SEED)
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
        val tile = 48
        val view = 9 * tile
        val cases = listOf(
            listOf(OracleClient.Point(24, 24, Buttons.RIGHT_BUTTON)),
            listOf(
                OracleClient.Point(24, 24, Buttons.LEFT_BUTTON),
                OracleClient.Point(24, 24, Buttons.MIDDLE_BUTTON),
            ),
        )

        for (points in cases) {
            val expected = oracle.pointsSized(save, points, width = view, height = view)
            val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals("save", expected.state.state, midend.serialise().serialise())
            assertEquals("text", oracle.text(expected.state.state), midend.textFormat())
        }

        // The right click writes 0, and the middle click clears it.
        val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
        midend.processKey(24, 24, Buttons.RIGHT_BUTTON, tile)
        assertEquals(listOf("P0,0,0"), midend.serialise().values("MOVE"))
        midend.processKey(24, 24, Buttons.MIDDLE_BUTTON, tile)
        assertEquals(listOf("P0,0,0", "P-,0,0"), midend.serialise().values("MOVE"))

        // A middle click on an empty cell is a no-op: the target is `-`.
        val empty = Midend.fromSave(Unruly, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.NoEffect,
            empty.processKey(24, 24, Buttons.MIDDLE_BUTTON, tile),
        )
        assertEquals(1, empty.stateCount)
    }

    @Test
    fun `the half-tile boundary, the last cells, and the letterbox match the oracle`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            val tile = 48
            val view = 9 * tile

            val points = listOf(
                OracleClient.Point(23, 23, Buttons.LEFT_BUTTON), // border
                OracleClient.Point(24, 24, Buttons.LEFT_BUTTON), // cell 0,0
                OracleClient.Point(407, 24, Buttons.LEFT_BUTTON), // clue (7,0)
                OracleClient.Point(408, 24, Buttons.LEFT_BUTTON), // gx == w2
                OracleClient.Point(360, 360, Buttons.LEFT_BUTTON), // clue (7,7)
                OracleClient.Point(312, 360, Buttons.LEFT_BUTTON), // empty (6,7)
                OracleClient.Point(431, 431, Buttons.LEFT_BUTTON), // gx == w2
            )
            val expected = oracle.pointsSized(save, points, width = view, height = view)
            val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, Buttons.LEFT_BUTTON, tile)
            }
            assertEquals("boundary save", expected.state.state, midend.serialise().serialise())
            assertEquals(
                "boundary moves",
                listOf("P1,0,0", "P1,6,7"),
                midend.serialise().values("MOVE"),
            )

            // The letterbox of the 411x702 viewport: tile 45, origin
            // (3,148). A click in the margin is outside the board.
            val letterbox = Midend.fromSave(Unruly, SgtpSave.parse(save))
            val geometry = letterbox.geometryFor(411, 702)
            assertEquals(45, geometry.tileSize)
            assertEquals(3, geometry.originX)
            assertEquals(148, geometry.originY)
            val letterboxSave = letterbox.serialise().serialise()
            assertEquals(
                KeyOutcome.Unused,
                letterbox.processKey(
                    -geometry.originX + 1,
                    -geometry.originY + 1,
                    Buttons.LEFT_BUTTON,
                    geometry.tileSize,
                ),
            )
            assertEquals(
                "a click in the margin adds no state",
                1,
                letterbox.stateCount,
            )
            assertEquals(letterboxSave, letterbox.serialise().serialise())

            // The first cell of the board is one tile in from the origin.
            val cellX = geometry.originX + 45 / 2
            val cellY = geometry.originY + 45 / 2
            val expectedCell = oracle.pointsSized(
                save,
                listOf(
                    OracleClient.Point(
                        geometry.originX + 45 / 2,
                        geometry.originY + 45 / 2,
                        Buttons.LEFT_BUTTON,
                    ),
                ),
                width = 411,
                height = 702,
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                letterbox.processKey(
                    cellX - geometry.originX,
                    cellY - geometry.originY,
                    Buttons.LEFT_BUTTON,
                    45,
                ),
            )
            assertEquals(
                "letterbox cell save",
                expectedCell.state.state,
                letterbox.serialise().serialise(),
            )
        }

    @Test
    fun `a press drag and release makes one move at the press`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            val tile = 48
            val view = 9 * tile
            val points = listOf(
                OracleClient.Point(24, 24, Buttons.LEFT_BUTTON),
                OracleClient.Point(120, 120, Buttons.LEFT_DRAG),
                OracleClient.Point(216, 216, Buttons.LEFT_DRAG),
                OracleClient.Point(216, 216, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points, width = view, height = view)
            val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals("drag save", expected.state.state, midend.serialise().serialise())
            assertEquals("drag move", listOf("P1,0,0"), midend.serialise().values("MOVE"))

            // A lone drag and a lone release are ignored by the midend,
            // so they change nothing.
            val lone = Midend.fromSave(Unruly, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.Unused,
                lone.processKey(120, 120, Buttons.LEFT_DRAG, tile),
            )
            assertEquals(
                KeyOutcome.Unused,
                lone.processKey(120, 120, Buttons.LEFT_RELEASE, tile),
            )
            assertEquals(1, lone.stateCount)
            assertEquals(save, lone.serialise().serialise())
        }

    @Test
    fun `a click then a cursor key and a select use the clicked cell`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8)
            val tile = 48
            val view = 9 * tile

            // The click at 192,192 is the clue at (3,3): it only moves
            // the Android cursor and hides the ring. RIGHT then goes to
            // (4,3), and SELECT places 1 there.
            val one = listOf(
                OracleClient.Point(192, 192, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
            )
            val expectedOne = oracle.pointsSized(save, one, width = view, height = view)
            val midendOne = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (point in one) {
                midendOne.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals("click cursor select move", listOf("P1,4,3"),
                midendOne.serialise().values("MOVE"))
            assertEquals("click cursor select save", expectedOne.state.state,
                midendOne.serialise().serialise())

            // The same click without the cursor key leaves the ring
            // hidden, so SELECT is unused and there is no move.
            val two = listOf(
                OracleClient.Point(192, 192, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
            )
            val expectedTwo = oracle.pointsSized(save, two, width = view, height = view)
            val midendTwo = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (point in two) {
                midendTwo.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals("no move", emptyList<String>(), midendTwo.serialise().values("MOVE"))
            assertEquals("no move save", expectedTwo.state.state,
                midendTwo.serialise().serialise())

            // A click on the empty cell (4,4), then UP to (4,3), then
            // SELECT: two moves.
            val three = listOf(
                OracleClient.Point(216, 216, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.CURSOR_UP),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
            )
            val expectedThree = oracle.pointsSized(save, three, width = view, height = view)
            val midendThree = Midend.fromSave(Unruly, SgtpSave.parse(save))
            for (point in three) {
                midendThree.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals(
                "click up select moves",
                listOf("P1,4,4", "P1,4,3"),
                midendThree.serialise().values("MOVE"),
            )
            assertEquals("click up select save", expectedThree.state.state,
                midendThree.serialise().serialise())
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
    }
}
