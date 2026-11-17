package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Rect
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: rect.
 *
 * Layout, the corner/edge/centre click resolution, real clicks and
 * drags at several tile sizes, the letterbox, the keyboard drag model,
 * the modifier strip, and the key labels. The click, drag, and key
 * sequences are replayed by the C midend.
 */
class RectInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `layout matches the oracle at every viewport and preset`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702, 781 to 781, 1080 to 1920, 320 to 480, 240 to 320,
        )
        for (preset in Rect.presets()) {
            val midend = Midend.newGame(Rect, preset.params, SEED)
            val save = midend.serialise().serialise()
            for ((w, h) in viewports) {
                val expected = oracle.layout(save, w, h)
                val geometry = midend.geometryFor(w, h)
                assertEquals("${preset.params} $w x $h width", expected.width, geometry.width)
                assertEquals("${preset.params} $w x $h height", expected.height, geometry.height)
            }
        }
    }

    @Test
    fun `the corner edge and centre resolution matches the oracle`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("rect", "7x7", SEED)
            // Tile 24, border 2: grid vertex (2, 2) is view (50, 50),
            // the horizontal edge midpoint above the next square is
            // (62, 50), and the centre of square (0, 0) is (14, 14).
            val cases = mapOf(
                "corner" to (50 to 50),
                "edge" to (62 to 50),
                "centre" to (14 to 14),
                "vertical edge" to (50 to 62),
            )
            for ((name, point) in cases) {
                val points = listOf(
                    OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON),
                    OracleClient.Point(point.first, point.second, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(base.state, points)
                val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
                for (p in points) midend.processKey(p.x, p.y, p.button, 24)
                assertEquals("$name save", expected.state.state, midend.serialise().serialise())
                val moves = SgtpSave.parse(expected.state.state).values("MOVE")
                when (name) {
                    "corner", "centre" -> assertTrue("$name no move", moves.isEmpty())
                    "edge" -> assertEquals("$name move", listOf("H2,2"), moves)
                    else -> assertEquals("$name move", listOf("V2,2"), moves)
                }
            }
        }

    @Test
    fun `a right click toggles the same edge`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val points = listOf(
            OracleClient.Point(38, 26, Buttons.RIGHT_BUTTON),
            OracleClient.Point(38, 26, Buttons.RIGHT_RELEASE),
        )
        val expected = oracle.pointsSized(base.state, points, 173, 173)
        val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
        for (p in points) midend.processKey(p.x, p.y, p.button, 24)
        assertEquals("right click save", expected.state.state, midend.serialise().serialise())
        assertEquals(
            "right click move",
            listOf("H1,1"),
            SgtpSave.parse(expected.state.state).values("MOVE"),
        )
        // A second right click toggles it back.
        val again = oracle.pointsSized(expected.state.state, points, 173, 173)
        for (p in points) midend.processKey(p.x, p.y, p.button, 24)
        assertEquals("right click twice", again.state.state, midend.serialise().serialise())
        assertEquals(
            "back to empty",
            listOf("H1,1", "H1,1"),
            SgtpSave.parse(again.state.state).values("MOVE"),
        )
    }

    @Test
    fun `a real click matches the oracle at several tile sizes`() = OracleClient().use { oracle ->
        for (params in listOf("7x7", "9x9", "11x11")) {
            val base = oracle.newGame("rect", params, SEED)
            for (tile in listOf(24, 31, 48, 63)) {
                val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
                val board = Rect.computeSize(Rect.decodeParams(params), tile)
                val x = 2 * tile + 2
                val y = 2 * tile + 2
                val points = listOf(
                    OracleClient.Point(x, y - tile / 2, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y - tile / 2, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    base.state,
                    points,
                    board.width,
                    board.height,
                )
                for (p in points) midend.processKey(p.x, p.y, p.button, tile)
                assertEquals(
                    "$params tile $tile",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "$params tile $tile moves",
                    1,
                    SgtpSave.parse(expected.state.state).values("MOVE").size,
                )
            }
        }
    }

    @Test
    fun `a letterboxed click matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("7x7", "9x9", "11x11")) {
            val base = oracle.newGame("rect", params, SEED)
            val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
            val geometry = midend.geometryFor(411, 702)
            val x = 2 * geometry.tileSize + 2
            val y = 2 * geometry.tileSize + 2
            val points = listOf(
                OracleClient.Point(x, y - geometry.tileSize / 2, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y - geometry.tileSize / 2, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(base.state, points, 411, 702)
            // The C points are view coordinates; the midend takes board
            // coordinates, as the app adapter subtracts the origin.
            for (p in points) {
                midend.processKey(
                    p.x - geometry.originX,
                    p.y - geometry.originY,
                    p.button,
                    geometry.tileSize,
                )
            }
            assertEquals(
                "$params letterbox",
                expected.state.state,
                midend.serialise().serialise(),
            )
        }
    }

    @Test
    fun `clicks outside the board and in the margin change nothing`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("rect", "7x7", SEED)
            val view = 411 to 702
            val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
            val geometry = midend.geometryFor(view.first, view.second)
            val outside = listOf(
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
                OracleClient.Point(view.first - 1, view.second - 1, Buttons.LEFT_BUTTON),
                OracleClient.Point(view.first - 1, view.second - 1, Buttons.LEFT_RELEASE),
                OracleClient.Point(geometry.originX - 5, 100, Buttons.LEFT_BUTTON),
                OracleClient.Point(geometry.originX - 5, 100, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(base.state, outside, view.first, view.second)
            for (p in outside) {
                midend.processKey(
                    p.x - geometry.originX,
                    p.y - geometry.originY,
                    p.button,
                    geometry.tileSize,
                )
            }
            assertEquals("outside save", expected.state.state, midend.serialise().serialise())
            assertEquals("outside moves", 0, SgtpSave.parse(expected.state.state).values("MOVE").size)
        }

    @Test
    fun `real drags match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val tile = 24
        val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
        val point = { x: Int, y: Int, button: Int -> OracleClient.Point(x, y, button) }
        val cases = mapOf(
            "right drag" to listOf(
                point(38, 26, Buttons.LEFT_BUTTON),
                point(50, 38, Buttons.LEFT_DRAG),
                point(62, 50, Buttons.LEFT_DRAG),
                point(62, 50, Buttons.LEFT_RELEASE),
            ),
            "left drag" to listOf(
                point(62, 50, Buttons.LEFT_BUTTON),
                point(74, 62, Buttons.LEFT_DRAG),
                point(74, 62, Buttons.LEFT_RELEASE),
            ),
            "outside drag" to listOf(
                point(38, 26, Buttons.LEFT_BUTTON),
                point(38 + 30 * tile, 26, Buttons.LEFT_DRAG),
                point(38 + 30 * tile, 26, Buttons.LEFT_RELEASE),
            ),
            "button mismatch" to listOf(
                point(38, 26, Buttons.RIGHT_BUTTON),
                point(50, 38, Buttons.RIGHT_DRAG),
                point(62, 50, Buttons.LEFT_DRAG),
                point(62, 50, Buttons.LEFT_RELEASE),
            ),
        )
        for ((name, points) in cases) {
            val expected = oracle.pointsSized(base.state, points, 173, 173)
            val m = Midend.fromSave(Rect, SgtpSave.parse(base.state))
            for (p in points) m.processKey(p.x, p.y, p.button, tile)
            assertEquals("$name save", expected.state.state, m.serialise().serialise())
            if (name == "right drag") {
                assertEquals(
                    "right drag move",
                    "R1,1,2,1",
                    SgtpSave.parse(expected.state.state).values("MOVE").last(),
                )
            }
        }

        // An erase drag over the interior of a larger box produces an
        // `E`: the first right drag outlines a 2x2 area, then a 3x3
        // erase covers its edges.
        val moved = oracle.pointsSized(
            base.state,
            listOf(
                point(14, 14, Buttons.LEFT_BUTTON),
                point(26, 26, Buttons.LEFT_DRAG),
                point(38, 38, Buttons.LEFT_DRAG),
                point(38, 38, Buttons.LEFT_RELEASE),
            ),
        )
        assertEquals(
            "outline move",
            "R0,0,2,2",
            SgtpSave.parse(moved.state.state).values("MOVE").last(),
        )
        val erasePoints = listOf(
            point(14, 14, Buttons.RIGHT_BUTTON),
            point(26, 26, Buttons.RIGHT_DRAG),
            point(62, 62, Buttons.RIGHT_DRAG),
            point(62, 62, Buttons.RIGHT_RELEASE),
        )
        val erased = oracle.pointsSized(moved.state.state, erasePoints, 173, 173)
        val eraseMidend = Midend.fromSave(Rect, SgtpSave.parse(moved.state.state))
        for (p in erasePoints) eraseMidend.processKey(p.x, p.y, p.button, tile)
        assertEquals("erase save", erased.state.state, eraseMidend.serialise().serialise())
        assertEquals(
            "erase move",
            "E0,0,3,3",
            SgtpSave.parse(erased.state.state).values("MOVE").last(),
        )
    }

    @Test
    fun `the keyboard drag model matches the oracle`() = OracleClient().use { oracle ->
        val sequences = listOf(
            listOf(Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
            listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
            listOf(
                Buttons.CURSOR_SELECT2,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT2,
            ),
            listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT2,
            ),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
            ),
            listOf(Buttons.CURSOR_SELECT, 27),
            listOf(Buttons.CURSOR_SELECT, '\b'.code),
        )
        for (params in listOf("7x7", "9x9")) {
            val base = oracle.newGame("rect", params, SEED)
            for (sequence in sequences) {
                val expected = oracle.keys(base.state, sequence)
                val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
                for (key in sequence) midend.processKey(0, 0, key, 24)
                assertEquals(
                    "$params ${sequence.size} keys",
                    expected.state,
                    midend.serialise().serialise(),
                )
            }
        }
    }

    @Test
    fun `a modifier on a click is stripped`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val points = listOf(
            OracleClient.Point(38, 26, Buttons.MOD_SHFT or Buttons.LEFT_BUTTON),
            OracleClient.Point(38, 26, Buttons.MOD_CTRL or Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(base.state, points)
        val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
        for (p in points) midend.processKey(p.x, p.y, p.button, 24)
        assertEquals("modifier save", expected.state.state, midend.serialise().serialise())
    }

    @Test
    fun `the key labels follow the drag state`() {
        val base = Midend.newGame(Rect, "7x7", SEED)
        val ui = base.ui as Rect.Ui
        val state = base.state
        assertEquals("hidden", "", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        ui.curVisible = true
        assertEquals("mark", "Mark", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("erase", "Erase", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        assertEquals("other key", "", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_RIGHT))

        ui.curDragging = true
        assertEquals("cancel", "Cancel", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.dragged = true
        assertEquals("done", "Done", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("cancel other", "Cancel", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.erasing = true
        assertEquals("done erase", "Done", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        assertEquals("cancel mark", "Cancel", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))

        ui.curDragging = false
        ui.dragStartX = 5
        assertEquals("mouse drag", "", Rect.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `request keys is the C null hook`() = OracleClient().use { oracle ->
        val save = oracle.newGame("rect", "7x7", SEED).state
        val normal = oracle.requestKeys(save)
        val kotlinNormal = Rect.requestKeys(Rect.decodeParams("7x7"))
        assertEquals(3, normal.arrowMode)
        assertEquals(name.boyle.chris.sgtpuzzles.engine.ArrowMode.ARROWS_LEFT_RIGHT, kotlinNormal.arrowMode)
        assertTrue(kotlinNormal.labels.isEmpty())

        val full = oracle.requestKeys(save, fullCursor = true)
        val kotlinFull = Rect.requestKeys(Rect.decodeParams("7x7"), fullCursor = true)
        assertEquals(1, full.arrowMode)
        assertEquals(name.boyle.chris.sgtpuzzles.engine.ArrowMode.ARROWS_ONLY, kotlinFull.arrowMode)
        assertTrue(kotlinFull.labels.isEmpty())
    }

    private companion object {
        const val SEED = "12345"
    }
}
