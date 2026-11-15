package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: filling.
 *
 * Layout, real pointer clicks at several tile sizes, a drag selection,
 * the cursor-only fill, the digit-only key, the clear key, the
 * out-of-grid cursor, and the `w == h == 2` digit rule are compared
 * with the oracle. Drawing and input use one geometry.
 *
 * Real clicks, click and digit, cursor and digit, digit alone, clear,
 * and the out-of-grid cursor run at tiles 48, 63, 82, and 100. Each
 * tile also has a letterboxed viewport with a nonzero origin. A real
 * fill must change the board and add one move; a selection click, a
 * clear with nothing to clear, and an out-of-grid cursor must change
 * neither.
 */
class FillingInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        for (case in viewports) {
            val start = oracle.newGame("filling", case.params, SEED)
            val layout = oracle.layout(start.state, case.width, case.height)
            val midend = Midend.newGame(Filling, case.params, SEED)
            val geometry = midend.geometryFor(case.width, case.height)
            val size = Filling.computeSize(Filling.decodeParams(case.params), geometry.tileSize)
            val where = "${case.label}: '${case.params}' in ${case.width} x ${case.height}"

            assertEquals("tile $where", case.tile, geometry.tileSize)
            assertEquals("computeSize width $where", layout.width, size.width)
            assertEquals("computeSize height $where", layout.height, size.height)
            assertEquals("layout width $where", layout.width, geometry.width)
            assertEquals("layout height $where", layout.height, geometry.height)
            assertEquals(
                "origin x $where",
                (case.width - layout.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "origin y $where",
                (case.height - layout.height) / 2,
                geometry.originY,
            )
        }
    }

    @Test
    fun `one click and one digit fill the same cell at every tile size`() =
        OracleClient().use { oracle ->
            for (case in viewports) {
                val start = oracle.newGame("filling", case.params, SEED)
                val midend = Midend.fromSave(Filling, SgtpSave.parse(start.state))
                val state = midend.state as Filling.State
                val geometry = midend.geometryFor(case.width, case.height)
                val tile = geometry.tileSize
                val (cx, cy) = firstEmptyCell(state)
                val viewX = geometry.originX + cx * tile + tile / 2
                val viewY = geometry.originY + cy * tile + tile / 2
                val gameX = geometry.toGameX(viewX)
                val gameY = geometry.toGameY(viewY)
                val where = "${case.label} at ($viewX,$viewY) tile $tile origin " +
                    "(${geometry.originX},${geometry.originY})"

                val startSave = midend.serialise().serialise()
                val before = state.board.copyOf()
                val expected = oracle.pointsSized(
                    start.state,
                    listOf(
                        OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(viewX, viewY, '5'.code),
                    ),
                    case.width,
                    case.height,
                    recordTrace = true,
                )

                assertEquals(
                    "outcome $where",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertTrue(
                    "a click leaves the board alone $where",
                    before.contentEquals((midend.state as Filling.State).board),
                )
                assertEquals("a click keeps the save $where", startSave,
                    midend.serialise().serialise())
                assertEquals("a click adds no state $where", 1, midend.stateCount)

                assertEquals(
                    "outcome $where",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, '5'.code, tile),
                )

                val moves = SgtpSave.parse(midend.serialise().serialise()).values("MOVE")
                assertEquals("one move $where", 1, moves.size)
                assertEquals(
                    "the move names the clicked cell $where",
                    "${cy * state.width + cx}_5",
                    moves.single(),
                )
                assertEquals("save $where", expected.state.state,
                    midend.serialise().serialise())
                assertEquals("status $where", expected.state.status, midend.status())
                val after = (midend.state as Filling.State).board
                assertNotEquals(
                    "a real fill changes the board $where",
                    before.toList(),
                    after.toList(),
                )
                assertEquals("a real fill adds one state $where", 2, midend.stateCount)
                assertTrace(
                    "drawing $where",
                    expected.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }
        }

    @Test
    fun `a drag selects several cells and one digit fills them`() =
        OracleClient().use { oracle ->
            val params = "13x9"
            val start = oracle.newGame("filling", params, SEED)
            val midend = Midend.fromSave(Filling, SgtpSave.parse(start.state))
            val state = midend.state as Filling.State
            val geometry = midend.geometryFor(720, 1280)
            val tile = geometry.tileSize
            val cells = emptyCells(state).take(3)
            assertEquals("three empty cells are needed", 3, cells.size)

            fun point(cell: Pair<Int, Int>, button: Int): OracleClient.Point {
                val (x, y) = cell
                return OracleClient.Point(
                    geometry.originX + x * tile + tile / 2,
                    geometry.originY + y * tile + tile / 2,
                    button,
                )
            }

            val points = listOf(
                point(cells[0], Buttons.LEFT_BUTTON),
                point(cells[1], Buttons.LEFT_DRAG),
                point(cells[2], Buttons.LEFT_DRAG),
                point(cells[0], '5'.code),
            )
            val expected = oracle.pointsSized(
                start.state,
                points,
                width = 720,
                height = 1280,
            )

            midend.processKey(
                geometry.toGameX(points[0].x),
                geometry.toGameY(points[0].y),
                Buttons.LEFT_BUTTON,
                tile,
            )
            for (point in points.subList(1, 3)) {
                midend.processKey(
                    geometry.toGameX(point.x),
                    geometry.toGameY(point.y),
                    Buttons.LEFT_DRAG,
                    tile,
                )
            }
            midend.processKey(
                geometry.toGameX(points[3].x),
                geometry.toGameY(points[3].y),
                '5'.code,
                tile,
            )

            val indices = cells.map { (x, y) -> y * state.width + x }.sorted()
            val expectedMove = indices.joinToString(",") + "_5"
            assertEquals(
                "the drag fills every dragged cell in index order",
                expectedMove,
                SgtpSave.parse(midend.serialise().serialise()).values("MOVE").single(),
            )
            assertEquals("save", expected.state.state, midend.serialise().serialise())
            assertEquals("status", expected.state.status, midend.status())
        }

    @Test
    fun `a cursor-only fill matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("9x7", DESC_9X7)
        val expected = oracle.keys(
            save,
            listOf(Buttons.CURSOR_LEFT, '5'.code),
        )
        val midend = Midend.fromSave(Filling, SgtpSave.parse(save))
        val tile = Filling.preferredTileSize

        assertEquals(KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_LEFT, tile))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, '5'.code, tile))
        assertEquals("0_5", SgtpSave.parse(midend.serialise().serialise()).values("MOVE").single())
        assertEquals("save", expected.state, midend.serialise().serialise())
        assertEquals("status", expected.status, midend.status())
    }

    @Test
    fun `a cursor fill, a digit alone, a clear, and an out-of-grid cursor at four tiles`() =
        OracleClient().use { oracle ->
            val clickSave = oracle.newGame("filling", "13x9", SEED).state
            val cursorSave = oracle.newGame("filling", "9x7", SEED).state

            for (case in letterboxViews) {
                val label = "${case.label} at ${case.width}x${case.height}"
                val tile = case.tile

                // Cursor and digit. A fresh midend hides the cursor at
                // (0,0). The left key shows it and clamps it there, and
                // the digit fills the empty first cell.
                val cursorExpected = oracle.keys(
                    cursorSave,
                    listOf(Buttons.CURSOR_LEFT, '5'.code),
                )
                val cursor = Midend.fromSave(Filling, SgtpSave.parse(cursorSave))
                assertEquals(
                    "$label cursor key outcome",
                    KeyOutcome.SomeEffect,
                    cursor.processKey(0, 0, Buttons.CURSOR_LEFT, tile),
                )
                assertEquals(
                    "$label cursor key alone keeps the save",
                    cursorSave,
                    cursor.serialise().serialise(),
                )
                assertEquals(
                    "$label cursor digit outcome",
                    KeyOutcome.SomeEffect,
                    cursor.processKey(0, 0, '5'.code, tile),
                )
                assertEquals(
                    "$label cursor fill save",
                    cursorExpected.state,
                    cursor.serialise().serialise(),
                )
                assertEquals(
                    "$label cursor fill move",
                    listOf("0_5"),
                    SgtpSave.parse(cursor.serialise().serialise()).values("MOVE"),
                )
                assertEquals("$label cursor fill adds one state", 2, cursor.stateCount)
                assertNotEquals(
                    "$label cursor fill changes the board",
                    0,
                    (cursor.state as Filling.State).board[0],
                )

                // The click, digit, and clear cases use one empty cell
                // of the 13x9 default board.
                val probe = Midend.fromSave(Filling, SgtpSave.parse(clickSave))
                val geometry = probe.geometryFor(case.width, case.height)
                assertEquals("tile $label", tile, geometry.tileSize)
                assertTrue(
                    "$label must have a nonzero letterbox origin",
                    geometry.originX > 0 && geometry.originY > 0,
                )
                val state = probe.state as Filling.State
                val (cx, cy) = firstEmptyCell(state)
                val cell = cy * state.width + cx
                val gameX = cx * tile + tile / 2
                val gameY = cy * tile + tile / 2
                val viewX = geometry.originX + gameX
                val viewY = geometry.originY + gameY
                val board = state.board.copyOf()

                // A real click only selects: no state, no move, no board.
                val clickExpected = oracle.pointsSized(
                    clickSave,
                    listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
                    case.width,
                    case.height,
                )
                assertEquals("$label click keeps C's save", clickSave,
                    clickExpected.state.state)
                assertEquals(
                    "$label click outcome",
                    KeyOutcome.SomeEffect,
                    probe.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertEquals("$label click adds no state", 1, probe.stateCount)
                assertEquals("$label click keeps the save", clickSave,
                    probe.serialise().serialise())
                assertTrue(
                    "$label click keeps the board",
                    board.contentEquals((probe.state as Filling.State).board),
                )

                // A digit with no selection and a hidden cursor does
                // nothing.
                val digit = Midend.fromSave(Filling, SgtpSave.parse(clickSave))
                val digitExpected = oracle.pointsSized(
                    clickSave,
                    listOf(OracleClient.Point(viewX, viewY, '5'.code)),
                    case.width,
                    case.height,
                )
                assertEquals("$label digit keeps C's save", clickSave,
                    digitExpected.state.state)
                assertEquals(
                    "$label digit outcome",
                    KeyOutcome.NoEffect,
                    digit.processKey(gameX, gameY, '5'.code, tile),
                )
                assertEquals("$label digit adds no state", 1, digit.stateCount)
                assertEquals("$label digit keeps the save", clickSave,
                    digit.serialise().serialise())
                assertTrue(
                    "$label digit keeps the board",
                    board.contentEquals((digit.state as Filling.State).board),
                )

                // A clear with a selection but an empty cell clears the
                // selection and adds no move.
                val clear = Midend.fromSave(Filling, SgtpSave.parse(clickSave))
                val clearExpected = oracle.pointsSized(
                    clickSave,
                    listOf(
                        OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(viewX, viewY, '\b'.code),
                    ),
                    case.width,
                    case.height,
                )
                assertEquals("$label clear keeps C's save", clickSave,
                    clearExpected.state.state)
                assertEquals(
                    KeyOutcome.SomeEffect,
                    clear.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertEquals(
                    "$label clear outcome",
                    KeyOutcome.SomeEffect,
                    clear.processKey(gameX, gameY, '\b'.code, tile),
                )
                assertEquals("$label clear adds no state", 1, clear.stateCount)
                assertEquals("$label clear keeps the save", clickSave,
                    clear.serialise().serialise())

                // A clear on a filled cell is a real move: the board
                // changes and one state is added.
                val fillAndClear = Midend.fromSave(Filling, SgtpSave.parse(clickSave))
                val fillClearExpected = oracle.pointsSized(
                    clickSave,
                    listOf(
                        OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(viewX, viewY, '5'.code),
                        OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(viewX, viewY, '\b'.code),
                    ),
                    case.width,
                    case.height,
                )
                assertEquals(
                    KeyOutcome.SomeEffect,
                    fillAndClear.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertEquals(
                    KeyOutcome.SomeEffect,
                    fillAndClear.processKey(gameX, gameY, '5'.code, tile),
                )
                assertEquals(
                    "$label clear fills the cell",
                    5,
                    (fillAndClear.state as Filling.State).board[cell],
                )
                assertEquals(
                    KeyOutcome.SomeEffect,
                    fillAndClear.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertEquals(
                    "$label clear filled outcome",
                    KeyOutcome.SomeEffect,
                    fillAndClear.processKey(gameX, gameY, '\b'.code, tile),
                )
                assertEquals(
                    "$label clear filled save",
                    fillClearExpected.state.state,
                    fillAndClear.serialise().serialise(),
                )
                assertEquals(
                    "$label fill and clear move",
                    listOf("${cell}_5", "${cell}_0"),
                    SgtpSave.parse(fillAndClear.serialise().serialise()).values("MOVE"),
                )
                assertEquals(
                    "$label fill and clear adds two states",
                    3,
                    fillAndClear.stateCount,
                )
                assertEquals(
                    "$label clear restores the empty cell",
                    0,
                    (fillAndClear.state as Filling.State).board[cell],
                )

                // The letterbox click moves the cursor outside the grid
                // but changes nothing. The C result can be compared
                // here: the click does not read the clue board.
                val outside = Midend.fromSave(Filling, SgtpSave.parse(clickSave))
                val letterboxExpected = oracle.pointsSized(
                    clickSave,
                    listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
                    case.width,
                    case.height,
                )
                assertEquals("$label letterbox keeps C's save", clickSave,
                    letterboxExpected.state.state)
                assertEquals(
                    "$label letterbox outcome",
                    KeyOutcome.SomeEffect,
                    outside.processKey(
                        -geometry.originX,
                        -geometry.originY,
                        Buttons.LEFT_BUTTON,
                        tile,
                    ),
                )
                assertEquals("$label letterbox adds no state", 1, outside.stateCount)
                assertEquals("$label letterbox keeps the save", clickSave,
                    outside.serialise().serialise())
                val outsideUi = outside.ui as Filling.Ui
                assertTrue(
                    "$label letterbox cursor is outside the grid",
                    outsideUi.curX < 0 || outsideUi.curX >= state.width ||
                        outsideUi.curY < 0 || outsideUi.curY >= state.height,
                )

                // C indexes `clues[w*cur_y+cur_x]` in the cursor-select
                // paths without a bounds test, so the out-of-grid guard
                // is pinned here and not compared with the oracle.
                val outX = -geometry.originX
                val outY = -geometry.originY
                assertEquals(
                    KeyOutcome.SomeEffect,
                    outside.processKey(outX, outY, Buttons.CURSOR_SELECT, tile),
                )
                assertEquals(
                    KeyOutcome.SomeEffect,
                    outside.processKey(outX, outY, Buttons.CURSOR_SELECT, tile),
                )
                assertEquals(
                    KeyOutcome.SomeEffect,
                    outside.processKey(outX, outY, Buttons.CURSOR_SELECT2, tile),
                )
                assertEquals(
                    "$label out-of-grid digit outcome",
                    KeyOutcome.NoEffect,
                    outside.processKey(outX, outY, '5'.code, tile),
                )
                assertEquals("$label out-of-grid adds no state", 1, outside.stateCount)
                assertEquals("$label out-of-grid keeps the save", clickSave,
                    outside.serialise().serialise())
                assertTrue(
                    "$label out-of-grid keeps the board",
                    board.contentEquals((outside.state as Filling.State).board),
                )
            }
        }

    @Test
    fun `the two by two digit rule matches the oracle`() = OracleClient().use { oracle ->
        // On `2x2`, the digit 3 is allowed even though `max(w, h)` is 2.
        val square = saveText("2x2", "3aaa")
        val squareExpected = oracle.pointsSized(
            square,
            listOf(
                OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(64, 32, '3'.code),
            ),
        )
        val squareMidend = Midend.fromSave(Filling, SgtpSave.parse(square))
        val tile = Filling.preferredTileSize
        squareMidend.processKey(64, 32, Buttons.LEFT_BUTTON, tile)
        squareMidend.processKey(64, 32, '3'.code, tile)
        assertEquals(
            "1_3",
            SgtpSave.parse(squareMidend.serialise().serialise()).values("MOVE").single(),
        )
        assertEquals("2x2 save", squareExpected.state.state,
            squareMidend.serialise().serialise())

        // On `1x2`, the same digit is above `max(w, h)` and is unused.
        val line = saveText("1x2", "2a")
        val lineExpected = oracle.pointsSized(
            line,
            listOf(
                OracleClient.Point(32, 64, Buttons.LEFT_BUTTON),
                OracleClient.Point(32, 64, '3'.code),
            ),
        )
        val lineMidend = Midend.fromSave(Filling, SgtpSave.parse(line))
        lineMidend.processKey(32, 64, Buttons.LEFT_BUTTON, tile)
        assertEquals(KeyOutcome.Unused, lineMidend.processKey(32, 64, '3'.code, tile))
        assertEquals("1x2 adds no move", line, lineMidend.serialise().serialise())
        assertEquals("1x2 state", lineExpected.state.state,
            lineMidend.serialise().serialise())

        // The same cell accepts a legal digit.
        assertEquals(KeyOutcome.SomeEffect, lineMidend.processKey(32, 64, '1'.code, tile))
        assertEquals("1_1", SgtpSave.parse(lineMidend.serialise().serialise()).values("MOVE").single())
    }

    private fun firstEmptyCell(state: Filling.State): Pair<Int, Int> =
        emptyCells(state).first()

    private fun emptyCells(state: Filling.State): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                if (state.shared.clues[y * state.width + x] == 0) cells += x to y
            }
        }
        return cells
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        val e = TraceNormalizer.normalize(expected, upstream).lines()
        val a = TraceNormalizer.normalize(actual, upstream).lines()
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

    private data class Viewport(
        val label: String,
        val params: String,
        val width: Int,
        val height: Int,
        val tile: Int,
    )

    private val letterboxViews = listOf(
        // The exact board is 14 x 10 tiles; each viewport is a few
        // pixels larger in both directions but too small for the next
        // tile size, so the tile stays fixed and the origin is nonzero.
        Viewport("tile48-letterbox", "13x9", 680, 520, 48),
        Viewport("tile63-letterbox", "13x9", 890, 650, 63),
        Viewport("tile82-letterbox", "13x9", 1155, 840, 82),
        Viewport("tile100-letterbox", "13x9", 1410, 1020, 100),
    )

    private val viewports = listOf(
        Viewport("phone", "13x9", 411, 702, 29),
        Viewport("tablet", "13x9", 720, 1280, 51),
        Viewport("landscape", "13x9", 1024, 600, 60),
        Viewport("tile48", "13x9", 672, 480, 48),
        Viewport("tile63", "13x9", 882, 630, 63),
        Viewport("tile82", "13x9", 1148, 820, 82),
        Viewport("tile100", "13x9", 1400, 1000, 100),
        Viewport("phone-9x7", "9x7", 411, 702, 41),
        Viewport("tile60-9x7", "9x7", 672, 480, 60),
    ) + letterboxViews

    private fun saveText(params: String, desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Filling.name),
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
        const val DESC_9X7 = "a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55"
    }
}
