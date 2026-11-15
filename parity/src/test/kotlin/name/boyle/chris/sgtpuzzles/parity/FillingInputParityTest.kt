package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: filling.
 *
 * Layout, real pointer clicks at several tile sizes, a drag selection,
 * the cursor-only fill, and the `w == h == 2` digit rule are compared
 * with the oracle. Drawing and input use one geometry.
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

                val before = SgtpSave.parse(midend.serialise().serialise()).values("MOVE").size
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
                assertEquals(
                    "outcome $where",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, '5'.code, tile),
                )

                val moves = SgtpSave.parse(midend.serialise().serialise()).values("MOVE")
                assertEquals("one move $where", before + 1, moves.size)
                assertEquals(
                    "the move names the clicked cell $where",
                    "${cy * state.width + cx}_5",
                    moves.single(),
                )
                assertEquals("save $where", expected.state.state,
                    midend.serialise().serialise())
                assertEquals("status $where", expected.state.status, midend.status())
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
    )

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
