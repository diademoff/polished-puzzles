package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: flood.
 *
 * The oracle chooses a tile size for each viewport, and the test sends
 * the same view coordinates to C through `pointsSized` and to Kotlin
 * through `BoardGeometry`. One tap is one move, so the state, the id,
 * the status, the status text, and the final redraw are compared after
 * every tap.
 *
 * The move count is asserted to grow by one, because a tap that was
 * ignored on both sides would otherwise pass. A tap on a cell that
 * already has the current colour must change nothing, and a tap in the
 * letterbox must change nothing.
 */
class FloodInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        for (case in viewports) {
            val start = oracle.newGame("flood", case.params, SEED)
            val layout = oracle.layout(start.state, case.width, case.height)
            val midend = Midend.newGame(Flood, case.params, SEED)
            val geometry = midend.geometryFor(case.width, case.height)
            val size = Flood.computeSize(Flood.decodeParams(case.params), case.tile)
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
            assertTrue(
                "the board must fit $where: ${geometry.width} x ${geometry.height}",
                geometry.width <= case.width && geometry.height <= case.height,
            )
            assertTrue(
                "the case must have a non-zero letterbox origin $where",
                geometry.originX != 0 || geometry.originY != 0,
            )
        }
    }

    @Test
    fun `one pointer tap floods the same cell at every viewport`() =
        OracleClient().use { oracle ->
            for (case in viewports) {
                val start = oracle.newGame("flood", case.params, SEED)
                val midend = Midend.newGame(Flood, case.params, SEED)
                val state = midend.state as Flood.State
                val geometry = midend.geometryFor(case.width, case.height)
                val (tx, ty) = firstDifferentColour(state)
                val tile = geometry.tileSize
                val viewX = geometry.originX + tx * tile + tile / 2
                val viewY = geometry.originY + ty * tile + tile / 2
                val gameX = geometry.toGameX(viewX)
                val gameY = geometry.toGameY(viewY)
                val where = "${case.label} at ($viewX,$viewY) tile $tile origin " +
                    "(${geometry.originX},${geometry.originY})"

                val startText = midend.serialise().serialise()
                val startMoves = SgtpSave.parse(startText).values("MOVE").size

                val expected = oracle.pointsSized(
                    start.state,
                    listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
                    case.width,
                    case.height,
                    recordTrace = true,
                )

                assertEquals(
                    "Kotlin tap outcome $where",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                val text = midend.serialise().serialise()
                val moves = SgtpSave.parse(text).values("MOVE")

                // A tap that floods must add exactly one move and the
                // move must name the colour of the clicked cell.
                assertEquals(
                    "the tap must add exactly one move $where",
                    startMoves + 1,
                    moves.size,
                )
                assertEquals(
                    "the tap must flood the clicked colour $where",
                    "M${state.grid[ty * state.width + tx]}",
                    moves.single(),
                )
                assertNotEquals("the tap must change the save $where", startText, text)

                assertEquals("save $where", expected.state.state, text)
                assertEquals("id $where", expected.state.id, midend.id())
                assertEquals("status $where", expected.state.status, midend.status())
                assertEquals(
                    "status text $where",
                    midend.statusText(),
                    traceStatus(expected.trace),
                )
                assertTrace(
                    "final drawing $where",
                    expected.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }
        }

    @Test
    fun `a tap on the current colour and a tap in the letterbox change nothing`() =
        OracleClient().use { oracle ->
            // 4x4 at 411x702: tile 82, origin (0,146). Cell (1,0) is
            // colour 1, which is also the colour of the controlled
            // corner, so the tap must not be a move.
            val params = "4x4c3m0"
            val desc = "1112101220200112,4"
            val save = saveText(params, desc, emptyList())
            val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            val tile = geometry.tileSize
            assertEquals(82, tile)
            assertEquals(0, geometry.originX)
            assertEquals(146, geometry.originY)

            val currentColourX = geometry.originX + 1 * tile + tile / 2
            val currentColourY = geometry.originY + 0 * tile + tile / 2

            val current = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(currentColourX, currentColourY, Buttons.LEFT_BUTTON)),
                411,
                702,
            )
            assertEquals(
                "C: a same-colour tap changes nothing",
                save,
                current.state.state,
            )
            assertFalse("C: a same-colour tap adds no history", current.state.canUndo)
            assertEquals(
                "Kotlin: a same-colour tap",
                KeyOutcome.NoEffect,
                midend.processKey(
                    geometry.toGameX(currentColourX),
                    geometry.toGameY(currentColourY),
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
            )
            assertEquals("Kotlin: a same-colour tap changes nothing", save,
                midend.serialise().serialise())

            // View (2,2) is above the board, in the letterbox.
            val letterbox = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(2, 2, Buttons.LEFT_BUTTON)),
                411,
                702,
            )
            assertEquals("C: a letterbox tap changes nothing", save, letterbox.state.state)
            assertEquals(
                "Kotlin: a letterbox tap",
                KeyOutcome.NoEffect,
                midend.processKey(
                    geometry.toGameX(2),
                    geometry.toGameY(2),
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
            )
            assertEquals("Kotlin: a letterbox tap changes nothing", save,
                midend.serialise().serialise())

            // Cell (3,0) is colour 2, so the same geometry does flood.
            val floodX = geometry.originX + 3 * tile + tile / 2
            val floodY = geometry.originY + 0 * tile + tile / 2
            val flooded = oracle.pointsSized(
                save,
                listOf(
                    OracleClient.Point(currentColourX, currentColourY, Buttons.LEFT_BUTTON),
                    OracleClient.Point(2, 2, Buttons.LEFT_BUTTON),
                    OracleClient.Point(floodX, floodY, Buttons.LEFT_BUTTON),
                ),
                411,
                702,
                recordTrace = true,
            )
            for ((x, y) in listOf(
                currentColourX to currentColourY,
                2 to 2,
                floodX to floodY,
            )) {
                midend.processKey(
                    geometry.toGameX(x),
                    geometry.toGameY(y),
                    Buttons.LEFT_BUTTON,
                    tile,
                )
            }
            val text = midend.serialise().serialise()
            assertEquals("the third tap floods", 1, SgtpSave.parse(text).values("MOVE").size)
            assertEquals("M2", SgtpSave.parse(text).values("MOVE").single())
            assertEquals("save after three taps", flooded.state.state, text)
            assertEquals("id after three taps", flooded.state.id, midend.id())
            assertEquals("status text after three taps", midend.statusText(),
                traceStatus(flooded.trace))
            assertTrace(
                "drawing after three taps",
                flooded.trace,
                KotlinTraceSink.stateTrace(midend, tile),
            )
        }

    @Test
    fun `a tap that reaches the move limit is a loss on both sides`() =
        OracleClient().use { oracle ->
            // The limit is one move, so the first flood already loses.
            val params = "4x4c3m0"
            val save = saveText(params, "1112101220200112,1", emptyList())
            val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            val tile = geometry.tileSize
            val viewX = geometry.originX + 3 * tile + tile / 2
            val viewY = geometry.originY + 0 * tile + tile / 2

            val expected = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
                411,
                702,
                recordTrace = true,
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(
                    geometry.toGameX(viewX),
                    geometry.toGameY(viewY),
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
            )

            assertEquals("save", expected.state.state, midend.serialise().serialise())
            assertEquals("id", expected.state.id, midend.id())
            assertEquals("status", -1, expected.state.status)
            assertEquals("Kotlin status", -1, midend.status())
            assertEquals("FAILED! 1 / 1 moves", midend.statusText())
            assertEquals("status text", midend.statusText(), traceStatus(expected.trace))
            assertTrace(
                "drawing at the move limit",
                expected.trace,
                KotlinTraceSink.stateTrace(midend, tile),
            )
        }

    @Test
    fun `a keyboard fill matches the oracle`() = OracleClient().use { oracle ->
        val params = "4x4c3m0"
        val save = saveText(params, "1112101220200112,4", emptyList())
        val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
        val tile = Flood.preferredTileSize

        // The arrow mode decides what the shell's arrow keys mean, so
        // compare it before the keyboard path.
        val expectedKeys = oracle.requestKeys(save)
        assertEquals("C arrow mode", 2, expectedKeys.arrowMode)
        assertEquals(
            "Kotlin arrow mode",
            ArrowMode.ARROWS_LEFT,
            Flood.requestKeys(Flood.decodeParams(params)).arrowMode,
        )
        assertTrue("C requests no extra keys", expectedKeys.labels.isEmpty())
        assertTrue(
            "Kotlin requests no extra keys",
            Flood.requestKeys(Flood.decodeParams(params)).labels.isEmpty(),
        )

        // Right three times, then select: cell (3,0), colour 2.
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
        )
        val expected = oracle.keys(save, sequence)
        for (key in sequence) {
            assertEquals(
                "keyboard outcome for $key",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, key, tile),
            )
        }

        val text = midend.serialise().serialise()
        val moves = SgtpSave.parse(text).values("MOVE")
        assertEquals("the keyboard fill adds one move", 1, moves.size)
        assertEquals("the keyboard fill names M2", "M2", moves.single())
        assertEquals("save", expected.state, text)
        assertEquals("id", expected.id, midend.id())
        assertEquals("status", expected.status, midend.status())
        assertEquals(
            "status text",
            midend.statusText(),
            traceStatus(oracle.trace(text)),
        )

        // The cursor lives in the UI, which is not part of a save file.
        // A fresh load draws no cursor, so compare the redraw of the
        // saved state, not of the live cursor.
        val reloaded = Midend.fromSave(Flood, SgtpSave.parse(text))
        assertTrace(
            "keyboard fill drawing",
            oracle.trace(text),
            KotlinTraceSink.stateTrace(reloaded),
        )
    }

    @Test
    fun `a modified cursor key does not move the cursor`() = OracleClient().use { oracle ->
        val params = "4x4c3m0"
        val save = saveText(params, "1200120012001200,4", emptyList())
        val plain = listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT)
        val modified = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
            Buttons.CURSOR_SELECT,
        )

        // The oracle is the evidence: both sequences flood with colour
        // 2 from cell (1,0), so the modified cursor keys are not cursor
        // moves. A cursor that moved would reach colour 0.
        val expectedPlain = oracle.keys(save, plain)
        val expectedModified = oracle.keys(save, modified)
        assertEquals(
            "a modified cursor key must not move the flood cursor",
            expectedPlain.state,
            expectedModified.state,
        )
        assertEquals(listOf("M2"), SgtpSave.parse(expectedModified.state).values("MOVE"))

        val tile = Flood.preferredTileSize
        val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile),
        )
        for (key in listOf(
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
        )) {
            assertEquals("outcome for $key", KeyOutcome.Unused, midend.processKey(0, 0, key, tile))
            assertEquals("cursor x for $key", 1, (midend.ui as Flood.Ui).cx)
            assertEquals("cursor y for $key", 0, (midend.ui as Flood.Ui).cy)
        }
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(listOf("M2"), midend.serialise().values("MOVE"))
        assertEquals("save", expectedModified.state, midend.serialise().serialise())
        assertEquals("id", expectedModified.id, midend.id())
        assertEquals("status", expectedModified.status, midend.status())
        assertEquals("undo", expectedModified.canUndo, midend.canUndo)
        assertEquals("redo", expectedModified.canRedo, midend.canRedo)
    }

    private data class Viewport(
        val label: String,
        val params: String,
        val width: Int,
        val height: Int,
        val tile: Int,
    )

    private val viewports = listOf(
        // The four view classes the shell must handle.
        Viewport("phone", "12x12c6m5", 411, 702, 31),
        Viewport("tablet", "12x12c6m5", 720, 1280, 55),
        Viewport("landscape", "12x12c6m5", 1024, 600, 46),
        Viewport("tiny", "12x12c6m5", 200, 200, 15),
        // The exact tile sizes from the test plan, each with a
        // letterbox on the vertical axis.
        Viewport("tile48", "12x12c6m5", 624, 700, 48),
        Viewport("tile63", "12x12c6m5", 818, 900, 63),
        Viewport("tile82", "12x12c6m5", 1066, 1200, 82),
        Viewport("tile100", "12x12c6m5", 1300, 1500, 100),
        // The small board at the phone viewport: tile 82 with the
        // board centred below a 146-pixel letterbox.
        Viewport("phone-4x4", "4x4c3m0", 411, 702, 82),
    )

    private fun firstDifferentColour(state: Flood.State): Pair<Int, Int> {
        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                if (state.grid[y * state.width + x] != state.grid[0]) return x to y
            }
        }
        error("a flood board must not start uniform")
    }

    private fun traceStatus(trace: JsonValue.Obj?): String? {
        val ops = (trace?.field("ops") as? JsonValue.Arr)?.items ?: return null
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
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

    private fun saveText(params: String, desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Flood"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
