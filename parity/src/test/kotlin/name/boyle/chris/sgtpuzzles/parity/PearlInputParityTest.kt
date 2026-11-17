package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: pearl.
 *
 * Layout, real clicks at several tile sizes, the click threshold, the
 * border and the last cell, the three buttons, real drags, the keyboard
 * cursor and its modified arrow keys, and the vacuity guards.
 */
class PearlInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout matches the oracle at every viewport and preset`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702, 781 to 781, 1080 to 1920, 320 to 480, 240 to 320,
        )
        for (preset in Pearl.presets()) {
            val midend = Midend.newGame(Pearl, preset.params, SEED)
            val save = midend.serialise().serialise()
            for ((w, h) in viewports) {
                val expected = oracle.layout(save, w, h)
                val geometry = midend.geometryFor(w, h)
                assertEquals("${preset.params} $w x $h width", expected.width, geometry.width)
                assertEquals("${preset.params} $w x $h height", expected.height, geometry.height)
            }
        }
        // The board sizes of the 8x8 preset at the four even/odd tile
        // sizes the trace matrix uses.
        val p = Pearl.decodeParams("8x8dt") as Pearl.Params
        val midend = Midend.newGame(Pearl, "8x8dt", SEED)
        val save = midend.serialise().serialise()
        for ((view, expected) in listOf(278 to 278, 422 to 422, 566 to 566, 728 to 728, 890 to 890)) {
            val layout = oracle.layout(save, view, view)
            assertEquals("8x8 $view", expected, layout.width)
            assertEquals("8x8 $view", expected, midend.geometryFor(view, view).width)
        }
    }

    @Test
    fun `a real click changes the board and an inert click does not`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        for (tile in listOf(31, 48, 63, 82, 100)) {
            val (cx, cy) = centre(0, 0, tile)
            val inert = listOf(
                Point(cx, cy, Buttons.LEFT_BUTTON),
                Point(cx, cy, Buttons.LEFT_RELEASE),
            )
            val inertState = compare(oracle, base.state, inert, "6x6de", tile)
            assertEquals("tile $tile inert moves", 0, inertState.state.countMoves())
            assertFalse("tile $tile inert canUndo", inertState.canUndo)
            assertEquals(
                "tile $tile inert board",
                oracle.text(base.state),
                oracle.text(inertState.state),
            )
            assertEquals("tile $tile inert status", 0, inertState.status)

            val real = listOf(
                Point(cx, cy + effectiveTile(tile) / 4 + 1, Buttons.LEFT_BUTTON),
                Point(cx, cy + effectiveTile(tile) / 4 + 1, Buttons.LEFT_RELEASE),
            )
            val realState = compare(oracle, base.state, real, "6x6de", tile)
            assertEquals("tile $tile real moves", 1, realState.state.countMoves())
            assertTrue("tile $tile real canUndo", realState.canUndo)
            assertEquals("tile $tile real status", 0, realState.status)
            assertTrue(
                "tile $tile real board",
                oracle.text(base.state) != oracle.text(realState.state),
            )
        }
    }

    @Test
    fun `the click threshold is the C tile quarter`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        // The effective TILE is tile - 1 for an even requested size.
        for (requested in listOf(31, 48, 63, 82, 100)) {
            val midend = Midend.fromSave(Pearl, SgtpSave.parse(base.state))
            val tile = midend.largestTileFor(
                Pearl.computeSize(Pearl.decodeParams("6x6de"), requested).width,
                Pearl.computeSize(Pearl.decodeParams("6x6de"), requested).height,
            )
            val (cx, cy) = centre(0, 0, tile)
            val quarter = effectiveTile(tile) / 4

            val inside = listOf(
                Point(cx, cy + quarter - 1, Buttons.LEFT_BUTTON),
                Point(cx, cy + quarter - 1, Buttons.LEFT_RELEASE),
            )
            val insideState = compare(oracle, base.state, inside, "6x6de", tile)
            assertEquals("tile $tile inside", 0, insideState.state.countMoves())

            val outside = listOf(
                Point(cx, cy + quarter, Buttons.LEFT_BUTTON),
                Point(cx, cy + quarter, Buttons.LEFT_RELEASE),
            )
            val outsideState = compare(oracle, base.state, outside, "6x6de", tile)
            assertEquals("tile $tile outside", 1, outsideState.state.countMoves())
        }
    }

    @Test
    fun `the border the last cell and one past it match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val tile = 31
        val board = Pearl.computeSize(Pearl.decodeParams("6x6de"), tile)
        val border = tile / 2
        val (cx, cy) = centre(2, 2, tile)
        val cases = listOf(
            "top border" to listOf(
                Point(cx, border - 1, Buttons.LEFT_BUTTON),
                Point(cx, border - 1, Buttons.LEFT_RELEASE),
            ),
            "left border" to listOf(
                Point(border - 1, cy, Buttons.LEFT_BUTTON),
                Point(border - 1, cy, Buttons.LEFT_RELEASE),
            ),
            "outer corner" to listOf(
                Point(0, 0, Buttons.LEFT_BUTTON),
                Point(0, 0, Buttons.LEFT_RELEASE),
            ),
            "last cell edge" to listOf(
                Point(
                    border + 5 * tile,
                    border + 5 * tile + tile / 2,
                    Buttons.LEFT_BUTTON,
                ),
                Point(
                    border + 5 * tile,
                    border + 5 * tile + tile / 2,
                    Buttons.LEFT_RELEASE,
                ),
            ),
            "right border" to listOf(
                Point(border + 6 * tile, cy, Buttons.LEFT_BUTTON),
                Point(border + 6 * tile, cy, Buttons.LEFT_RELEASE),
            ),
            "bottom border" to listOf(
                Point(cx, border + 6 * tile, Buttons.LEFT_BUTTON),
                Point(cx, border + 6 * tile, Buttons.LEFT_RELEASE),
            ),
            "one past" to listOf(
                Point(border + 6 * tile, border + 6 * tile, Buttons.LEFT_BUTTON),
                Point(border + 6 * tile, border + 6 * tile, Buttons.LEFT_RELEASE),
            ),
        )
        for ((name, points) in cases) {
            val expected = compare(oracle, base.state, points, "6x6de", tile)
            val moves = expected.state.countMoves()
            assertTrue("$name moves $moves", moves <= 1)
            if (name == "last cell edge") {
                assertEquals("$name lays the left edge", 1, moves)
            } else {
                assertEquals("$name is inert", 0, moves)
            }
        }
    }

    @Test
    fun `left middle and right clicks lay a line or a mark`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val tile = 31
        val (cx, cy) = centre(0, 0, tile)
        val y = cy + effectiveTile(tile) / 4 + 1
        for (button in listOf(Buttons.LEFT_BUTTON, Buttons.MIDDLE_BUTTON, Buttons.RIGHT_BUTTON)) {
            val points = listOf(
                Point(cx, y, button),
                Point(cx, y, button + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON)),
            )
            val expected = compare(oracle, base.state, points, "6x6de", tile)
            val move = SgtpSave.parse(expected.state).value("MOVE")!!
            if (button == Buttons.LEFT_BUTTON) {
                assertTrue("left lays a line: $move", move.startsWith("F"))
            } else {
                assertTrue("middle and right lay a mark: $move", move.startsWith("M"))
            }
        }
    }

    @Test
    fun `real drags match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val tile = 31
        val sequences = listOf(
            "two cell" to listOf(
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON),
                Point(gx(1, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(1, tile), gy(0, tile), Buttons.LEFT_RELEASE),
            ),
            "three cell" to listOf(
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON),
                Point(gx(1, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(2, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(2, tile), gy(0, tile), Buttons.LEFT_RELEASE),
            ),
            "backtrack" to listOf(
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON),
                Point(gx(2, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(1, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(1, tile), gy(0, tile), Buttons.LEFT_RELEASE),
            ),
            "loop back" to listOf(
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON),
                Point(gx(1, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(1, tile), gy(1, tile), Buttons.LEFT_DRAG),
                Point(gx(0, tile), gy(1, tile), Buttons.LEFT_DRAG),
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_DRAG),
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_RELEASE),
            ),
            "diagonal jump" to listOf(
                Point(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON),
                Point(gx(1, tile), gy(1, tile), Buttons.LEFT_DRAG),
                Point(gx(1, tile), gy(1, tile), Buttons.LEFT_RELEASE),
            ),
        )
        for ((name, points) in sequences) {
            val expected = compare(oracle, base.state, points, "6x6de", tile)
            if (name == "diagonal jump") {
                assertEquals("$name is inert", 0, expected.state.countMoves())
            } else {
                assertTrue("$name has a move", expected.state.countMoves() >= 1)
            }
        }

        // The same drag at the even and odd tile sizes the app can pick.
        for (dragTile in listOf(48, 63, 82, 100)) {
            val points = listOf(
                Point(gx(0, dragTile), gy(0, dragTile), Buttons.LEFT_BUTTON),
                Point(gx(1, dragTile), gy(0, dragTile), Buttons.LEFT_DRAG),
                Point(gx(1, dragTile), gy(0, dragTile), Buttons.LEFT_RELEASE),
            )
            val expected = compare(oracle, base.state, points, "6x6de", dragTile)
            assertEquals("tile $dragTile drag moves", 1, expected.state.countMoves())
            assertTrue(
                "tile $dragTile drag board",
                oracle.text(base.state) != oracle.text(expected.state),
            )
            assertEquals("tile $dragTile drag status", 0, expected.status)
        }
    }

    @Test
    fun `a drag stops at a no line mark`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        // Lay a mark on the edge (0,0)-(1,0), then drag across it.
        val marked = appendMove(base.state, "M1,0,0;M4,1,0")
        val tile = 31
        val points = listOf(
            Point(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON),
            Point(gx(2, tile), gy(0, tile), Buttons.LEFT_DRAG),
            Point(gx(2, tile), gy(0, tile), Buttons.LEFT_RELEASE),
        )
        val expected = compare(oracle, marked, points, "6x6de", tile)
        assertEquals("the drag is blocked", 1, expected.state.countMoves())
        assertEquals("no new edge", "M1,0,0;M4,1,0", SgtpSave.parse(expected.state).value("MOVE"))
    }

    @Test
    fun `a self-crossing drag is bounded at a real tile size`() {
        // The oracle cannot be asked: C appends the retraced cells past
        // its `sz`-entry drag array. The Kotlin rule is defined in D-019.
        val tile = 48
        val midend = Midend.fromGameId(Pearl, "5x5:y")
        val ui = midend.ui as Pearl.Ui
        val path = listOf(
            0 to 0, 1 to 0, 1 to 1, 0 to 1, 0 to 2, 1 to 2, 2 to 2, 3 to 2,
            3 to 3, 2 to 3, 1 to 3, 0 to 3, 0 to 4, 1 to 4, 2 to 4, 3 to 4,
            4 to 4, 4 to 3, 4 to 2, 4 to 1, 4 to 0, 3 to 0, 3 to 1, 2 to 1,
            2 to 0,
        )

        midend.processKey(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON, tile)
        for ((x, y) in path.drop(1)) {
            midend.processKey(gx(x, tile), gy(y, tile), Buttons.LEFT_DRAG, tile)
        }
        assertEquals(25, ui.ndragcoords)

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(gx(0, tile), gy(0, tile), Buttons.LEFT_DRAG, tile),
        )
        // The extension stops at (1,0) and the loop branch wrote the start
        // into the extra entry.
        assertEquals(25, ui.ndragcoords)
        assertEquals(0, ui.dragcoords[25])

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(gx(0, tile), gy(0, tile), Buttons.LEFT_RELEASE, tile),
        )
        assertEquals(2, midend.stateCount)
        assertEquals(24, midend.serialise().value("MOVE")!!.split(';').size / 2)

        // Positive control at the same tile: a normal two-cell drag.
        val normal = Midend.fromGameId(Pearl, "5x5:y")
        normal.processKey(gx(0, tile), gy(0, tile), Buttons.LEFT_BUTTON, tile)
        normal.processKey(gx(1, tile), gy(0, tile), Buttons.LEFT_DRAG, tile)
        normal.processKey(gx(1, tile), gy(0, tile), Buttons.LEFT_RELEASE, tile)
        assertEquals(2, normal.stateCount)
        assertEquals(1, normal.serialise().values("MOVE").size)
    }

    @Test
    fun `a right drag uses the click rule and a lone release or drag is ignored`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("pearl", "6x6de", SEED)
            val tile = 31
            val (cx, cy) = centre(0, 0, tile)
            val y = cy + effectiveTile(tile) / 4 + 1

            val rightDrag = listOf(
                Point(cx, y, Buttons.RIGHT_BUTTON),
                Point(gx(2, tile), y, Buttons.RIGHT_DRAG),
                Point(cx, y, Buttons.RIGHT_RELEASE),
            )
            val expected = compare(oracle, base.state, rightDrag, "6x6de", tile)
            // The drag itself is unused; the release is still a click and
            // lays a mark.
            assertEquals(1, expected.state.countMoves())
            assertTrue(SgtpSave.parse(expected.state).value("MOVE")!!.startsWith("M"))

            val loneRelease = listOf(Point(cx, y, Buttons.LEFT_RELEASE))
            val lone = compare(oracle, base.state, loneRelease, "6x6de", tile)
            assertEquals(0, lone.state.countMoves())

            val loneDrag = listOf(Point(gx(2, tile), gy(0, tile), Buttons.LEFT_DRAG))
            val dragged = compare(oracle, base.state, loneDrag, "6x6de", tile)
            assertEquals(0, dragged.state.countMoves())
        }

    @Test
    fun `keyboard sequences match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED).state
        val sequences = listOf(
            "show" to listOf(Buttons.CURSOR_SELECT),
            "arrow ring" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
            ),
            "select drag commit" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
            "select2 cancel" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT2,
            ),
            "ctrl arrow line" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
            ),
            "shift arrow mark" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.MOD_SHFT or Buttons.CURSOR_DOWN,
            ),
            "ctrl shift arrow" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.MOD_CTRL or Buttons.MOD_SHFT or Buttons.CURSOR_DOWN,
            ),
            "escape" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                27,
            ),
            "backspace" to listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                '\b'.code,
            ),
            "hint" to listOf('H'.code),
        )
        for ((name, sequence) in sequences) {
            val expected = oracle.keys(base, sequence)
            val midend = Midend.fromSave(Pearl, SgtpSave.parse(base))
            for (key in sequence) midend.processKey(0, 0, key, 31)
            assertEquals("$name save", expected.state, midend.serialise().serialise())
            assertEquals("$name status", expected.status, midend.status())
        }
    }

    @Test
    fun `modified cursor keys while a drag is in progress do nothing`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED).state
        val sequence = listOf(
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_RIGHT,
            Buttons.MOD_CTRL or Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        val expected = oracle.keys(base, sequence)
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(base))
        for (key in sequence) midend.processKey(0, 0, key, 31)
        assertEquals(expected.state, midend.serialise().serialise())

        // The modified key is a no-effect key while a drag is in
        // progress, not an unused one.
        val dragging = Midend.fromSave(Pearl, SgtpSave.parse(base))
        dragging.processKey(0, 0, Buttons.CURSOR_SELECT, 31)
        dragging.processKey(0, 0, Buttons.CURSOR_SELECT, 31)
        dragging.processKey(0, 0, Buttons.CURSOR_RIGHT, 31)
        val before = dragging.serialise().serialise()
        assertEquals(
            KeyOutcome.NoEffect,
            dragging.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_DOWN, 31),
        )
        assertEquals(before, dragging.serialise().serialise())
    }

    @Test
    fun `request keys match the oracle for every preset`() = OracleClient().use { oracle ->
        val allParams = listOf("") + Pearl.presets().map { it.params }
        for (params in allParams) {
            val save = oracle.newGame("pearl", params, SEED).state
            for (fullCursor in listOf(false, true)) {
                val expected = oracle.requestKeys(save, fullCursor)
                val actual = Pearl.requestKeys(Pearl.decodeParams(params), fullCursor)
                val mode = if (fullCursor) 1 else 3
                assertEquals(
                    "'$params' fullCursor $fullCursor C arrow mode",
                    mode,
                    expected.arrowMode,
                )
                assertEquals(
                    "'$params' fullCursor $fullCursor arrow mode",
                    if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
                    actual.arrowMode,
                )
                assertEquals(
                    "'$params' fullCursor $fullCursor labels",
                    expected.labels.map { Triple(it.label, it.button, it.needsArrows) },
                    actual.labels.map { Triple(it.name, it.button, it.needsArrows) },
                )
                assertTrue(
                    "'$params' fullCursor $fullCursor has no extra keys",
                    actual.labels.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `a real mark changes the board and text while an inert click does not`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Pearl, "6x6de", SEED)
            val before = midend.serialise().serialise()
            val textBefore = midend.textFormat()
            val tile = 31
            val (cx, cy) = centre(0, 0, tile)

            assertEquals(KeyOutcome.SomeEffect, midend.processKey(cx, cy, Buttons.LEFT_BUTTON, tile))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(cx, cy, Buttons.LEFT_RELEASE, tile))
            assertEquals(before, midend.serialise().serialise())
            assertEquals(textBefore, midend.textFormat())

            val y = cy + effectiveTile(tile) / 4 + 1
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(cx, y, Buttons.RIGHT_BUTTON, tile))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(cx, y, Buttons.RIGHT_RELEASE, tile))
            assertFalse(before == midend.serialise().serialise())
            assertFalse(textBefore == midend.textFormat())
            assertEquals(1, midend.serialise().values("MOVE").size)

            // The positive control is also accepted by C.
            val c = oracle.pointsSized(
                before,
                listOf(
                    OracleClient.Point(cx, y, Buttons.RIGHT_BUTTON),
                    OracleClient.Point(cx, y, Buttons.RIGHT_RELEASE),
                ),
                216,
                216,
            )
            assertEquals(c.state.state, midend.serialise().serialise())
        }

    @Test
    fun `the line and mark cycle toggles on and off`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val tile = 31
        val (cx, cy) = centre(2, 2, tile)
        val y = cy + effectiveTile(tile) / 4 + 1
        for ((name, button) in listOf(
            "line" to Buttons.LEFT_BUTTON,
            "mark" to Buttons.RIGHT_BUTTON,
        )) {
            val release = button + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON)
            val press = Point(cx, y, button)
            val up = Point(cx, y, release)

            val once = compare(oracle, base.state, listOf(press, up), "6x6de", tile)
            assertEquals("$name on moves", 1, once.state.countMoves())
            assertTrue(
                "$name on board",
                oracle.text(base.state) != oracle.text(once.state),
            )
            assertEquals("$name on status", 0, once.status)

            val twice = compare(
                oracle,
                base.state,
                listOf(press, up, press, up),
                "6x6de",
                tile,
            )
            assertEquals("$name off moves", 2, twice.state.countMoves())
            assertEquals(
                "$name off board",
                oracle.text(base.state),
                oracle.text(twice.state),
            )
            assertEquals("$name off id", base.id, twice.id)
            assertEquals("$name off status", 0, twice.status)
        }
    }

    @Test
    fun `a mark over a line and a line over a mark are inert`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("pearl", "6x6de", SEED)
            val tile = 31
            val (cx, cy) = centre(2, 2, tile)
            val y = cy + effectiveTile(tile) / 4 + 1
            val line = listOf(
                Point(cx, y, Buttons.LEFT_BUTTON),
                Point(cx, y, Buttons.LEFT_RELEASE),
            )
            val mark = listOf(
                Point(cx, y, Buttons.RIGHT_BUTTON),
                Point(cx, y, Buttons.RIGHT_RELEASE),
            )

            val laid = compare(oracle, base.state, line, "6x6de", tile)
            assertEquals(1, laid.state.countMoves())
            val markOverLine = compare(oracle, laid.state, mark, "6x6de", tile)
            assertEquals(
                "the mark over a line adds no move",
                1,
                markOverLine.state.countMoves(),
            )
            assertEquals(
                "the mark over a line keeps the board",
                oracle.text(laid.state),
                oracle.text(markOverLine.state),
            )
            assertEquals("the mark over a line keeps the status", laid.status, markOverLine.status)

            val marked = compare(oracle, base.state, mark, "6x6de", tile)
            assertEquals(1, marked.state.countMoves())
            val lineOverMark = compare(oracle, marked.state, line, "6x6de", tile)
            assertEquals(
                "the line over a mark adds no move",
                1,
                lineOverMark.state.countMoves(),
            )
            assertEquals(
                "the line over a mark keeps the board",
                oracle.text(marked.state),
                oracle.text(lineOverMark.state),
            )

            // The positive controls above are real moves on the same point.
            assertTrue(oracle.text(base.state) != oracle.text(laid.state))
            assertTrue(oracle.text(base.state) != oracle.text(marked.state))
        }

    @Test
    fun `a letterboxed click matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(base.state))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(58, geometry.tileSize)
        assertEquals(6, geometry.originX)
        assertEquals(152, geometry.originY)

        val tile = geometry.tileSize
        val gameX = tile / 2 + 2 * tile + tile / 2
        val gameY = tile / 2 + 2 * tile + tile / 2 + effectiveTile(tile) / 4 + 1
        val viewX = gameX + geometry.originX
        val viewY = gameY + geometry.originY
        val expected = oracle.pointsSized(
            base.state,
            listOf(
                OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                OracleClient.Point(viewX, viewY, Buttons.LEFT_RELEASE),
            ),
            411,
            702,
            recordTrace = true,
        )
        midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile)
        midend.processKey(gameX, gameY, Buttons.LEFT_RELEASE, tile)
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertEquals(expected.state.id, midend.id())
        assertEquals(expected.state.status, midend.status())
        assertEquals(expected.state.canUndo, midend.canUndo)
        assertEquals(expected.state.canRedo, midend.canRedo)
        assertEquals(
            TraceNormalizer.normalize(expected.trace!!, upstream),
            TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, tile), upstream),
        )
        assertEquals(1, midend.serialise().values("MOVE").size)

        // The centre of the same cell is inert.
        val inertMidend = Midend.fromSave(Pearl, SgtpSave.parse(base.state))
        val inertX = tile / 2 + 2 * tile + tile / 2
        val inertY = tile / 2 + 2 * tile + tile / 2
        val inertExpected = oracle.pointsSized(
            base.state,
            listOf(
                OracleClient.Point(inertX + geometry.originX, inertY + geometry.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(inertX + geometry.originX, inertY + geometry.originY, Buttons.LEFT_RELEASE),
            ),
            411,
            702,
            recordTrace = true,
        )
        inertMidend.processKey(inertX, inertY, Buttons.LEFT_BUTTON, tile)
        inertMidend.processKey(inertX, inertY, Buttons.LEFT_RELEASE, tile)
        assertEquals(inertExpected.state.state, inertMidend.serialise().serialise())
        assertEquals(inertExpected.state.id, inertMidend.id())
        assertEquals(inertExpected.state.status, inertMidend.status())
        assertEquals(inertExpected.state.canUndo, inertMidend.canUndo)
        assertEquals(inertExpected.state.canRedo, inertMidend.canRedo)
        assertEquals(
            TraceNormalizer.normalize(inertExpected.trace!!, upstream),
            TraceNormalizer.normalize(KotlinTraceSink.stateTrace(inertMidend, tile), upstream),
        )
        assertEquals(0, inertMidend.serialise().values("MOVE").size)
        assertEquals(
            oracle.text(base.state),
            oracle.text(inertMidend.serialise().serialise()),
        )
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private data class Point(val x: Int, val y: Int, val button: Int)

    /** The effective TILE_SIZE: `tile - 1` for an even requested size. */
    private fun effectiveTile(tile: Int): Int = ((tile - 1) / 2) * 2 + 1

    private fun gx(x: Int, tile: Int): Int {
        val t = effectiveTile(tile)
        return t / 2 + x * t + t / 2
    }

    private fun gy(y: Int, tile: Int): Int {
        val t = effectiveTile(tile)
        return t / 2 + y * t + t / 2
    }

    private fun centre(x: Int, y: Int, tile: Int): Pair<Int, Int> = gx(x, tile) to gy(y, tile)

    /**
     * Sends [points] to one oracle midend sized to the board at [tile],
     * and to one Kotlin midend at the same tile. The board fills the
     * view, so the letterbox origin is zero. The state, id, status,
     * undo/redo, and the final redraw are all compared.
     */
    private fun compare(
        oracle: OracleClient,
        save: String,
        points: List<Point>,
        paramsText: String,
        tile: Int,
    ): GameState {
        val board = Pearl.computeSize(Pearl.decodeParams(paramsText), tile)
        val expected = oracle.pointsSized(
            save,
            points.map { OracleClient.Point(it.x, it.y, it.button) },
            board.width,
            board.height,
            recordTrace = true,
        )
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, tile)
        }
        assertEquals("save", expected.state.state, midend.serialise().serialise())
        assertEquals("id", expected.state.id, midend.id())
        assertEquals("status", expected.state.status, midend.status())
        assertEquals("canUndo", expected.state.canUndo, midend.canUndo)
        assertEquals("canRedo", expected.state.canRedo, midend.canRedo)
        assertEquals(
            "final trace",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, tile), upstream),
        )
        return expected.state
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private fun String.countMoves(): Int =
        SgtpSave.parse(this).values("MOVE").size

    private companion object {
        const val SEED = "12345"
    }
}
