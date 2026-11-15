package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: cube.
 *
 * The cold trace of every preset is compared with a fresh oracle trace
 * and with the frozen file. Moved and completed states are loaded from
 * a save with `MOVE` records, so the recorded frame is settled: a live
 * midend would draw the first animation frame, with the old square and
 * the old face colours. Sized traces come from an inert exact-centre
 * click, which forces a fresh drawstate at the viewport's tile size.
 *
 * The trace pins the float geometry: grid coordinates truncate toward
 * zero, face coordinates use `floor`, the projection shears z, and the
 * back-face cull uses the 2-D winding of the sheared points.
 */
class CubeTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            for (params in Cube.presets().map { it.params }) {
                val midend = Midend.newGame(Cube, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("cube", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/cube/${params}.trace")
                assertEquals("frozen trace for '$params'", golden.readText(), actual)
            }
        }

    @Test
    fun `the cold trace has the C operation counts`() = OracleClient().use { oracle ->
        val cases = listOf(
            Quad("c4x4", 27, 19, 16, 3),
            Quad("t1x2", 24, 16, 13, 3),
            Quad("o2x2", 36, 28, 24, 4),
            Quad("i3x3", 72, 64, 54, 10),
        )
        for (case in cases) {
            val ops = (oracle.traceNew("cube", case.params, SEED).field("ops") as JsonValue.Arr)
                .items.filterIsInstance<JsonValue.Obj>()
            assertEquals("${case.params} op count", case.ops, ops.size)
            assertEquals(
                "${case.params} poly count",
                case.polys,
                ops.count { it.string("op") == "poly" },
            )
            // The first `area` polygons are the grid; the rest are the
            // visible faces. A triangle grid and a triangle face have
            // the same point count, so the order, not the shape, splits
            // them.
            val polygons = ops.filter { it.string("op") == "poly" }
            assertEquals(
                "${case.params} face poly count",
                case.faces,
                polygons.size - case.grid,
            )
            assertEquals(
                "${case.params} rect count",
                2,
                ops.count { it.string("op") == "rect" },
            )
            assertEquals(
                "${case.params} update count",
                2,
                ops.count { it.string("op") == "update" },
            )
            assertEquals(
                "${case.params} status count",
                1,
                ops.count { it.string("op") == "status" },
            )
            assertEquals(
                "${case.params} changed_state",
                1,
                ops.count { it.string("op") == "changed_state" },
            )
        }

        val trace = TraceNormalizer.normalize(oracle.traceNew("cube", "c4x4", SEED))
        assertTrue(
            "the palette is part of the trace: $trace",
            trace.contains("palette 800,800,800 0,0,0 0,0,1000"),
        )
    }

    @Test
    fun `moved, completed, and post-win states match the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val cases = listOf(
                "one move" to listOf("U"),
                "turned corner" to listOf("U", "R"),
                "win" to WIN,
                "post-win" to WIN + "U",
                "undo branch" to listOf("D", "R"),
            )
            for ((label, moves) in cases) {
                val save = saveText(DESC, moves)
                val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend),
                    upstream,
                )
                val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
                assertLines("trace for $label", expected, actual)
                assertEquals(
                    "$label status",
                    traceStatus(oracle, save),
                    traceStatusOf(actual),
                )
                assertFalse(
                    "$label is settled, so it has no clip",
                    actual.lines().any { it.startsWith("clip ") },
                )
            }

            val winSave = saveText(DESC, WIN)
            assertEquals("COMPLETED! Moves: 13", traceStatus(oracle, winSave))
            assertEquals(
                "COMPLETED! Moves: 13",
                traceStatus(oracle, saveText(DESC, WIN + "U")),
            )
        }

    @Test
    fun `a rotation sweep on every preset matches the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val keys = listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
                Buttons.MOD_NUM_KEYPAD or '7'.code,
                Buttons.CURSOR_UP,
                Buttons.MOD_NUM_KEYPAD or '3'.code,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.MOD_NUM_KEYPAD or '1'.code,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_UP,
            )
            for (params in Cube.presets().map { it.params }) {
                var oracleSave = oracle.newGame("cube", params, SEED).state
                val midend = Midend.newGame(Cube, params, SEED)
                for ((index, key) in (keys + keys).withIndex()) {
                    val expected = oracle.key(oracleSave, 0, 0, key)
                    oracleSave = expected.state
                    midend.processKey(0, 0, key, Cube.preferredTileSize)
                    assertEquals(
                        "'$params' step $index save",
                        oracleSave,
                        midend.serialise().serialise(),
                    )
                    assertEquals(
                        "'$params' step $index status",
                        expected.status,
                        midend.status(),
                    )

                    // Every fourth frame is a strict settled trace from a
                    // save, never a live post-move animation frame.
                    if (index % 4 == 3) {
                        val actual = TraceNormalizer.normalize(
                            KotlinTraceSink.stateTrace(midend),
                            upstream,
                        )
                        val expectedTrace = TraceNormalizer.normalize(
                            oracle.trace(oracleSave),
                            upstream,
                        )
                        assertLines("'$params' step $index trace", expectedTrace, actual)
                    }
                }
            }
        }

    @Test
    fun `sized traces at 48, 63, 82, and 100 match the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val params = Cube.decodeParams("c4x4") as Cube.Params
            val startSave = oracle.newGame("cube", "c4x4", SEED).state

            for (tile in listOf(48, 63, 82, 100)) {
                val view = Cube.computeSize(params, tile)
                val layout = oracle.layout(startSave, view.width, view.height)
                assertEquals("layout width at tile $tile", view.width, layout.width)
                assertEquals("layout height at tile $tile", view.height, layout.height)

                // The initial state starts on (0,1), so its centre is
                // ((0+1)*tile, (1+1)*tile) in the C square geometry.
                val expected = oracle.pointsSized(
                    startSave,
                    listOf(OracleClient.Point(tile, 2 * tile, Buttons.LEFT_BUTTON)),
                    view.width,
                    view.height,
                    recordTrace = true,
                )
                assertEquals(
                    "the initial click at tile $tile is inert",
                    startSave,
                    expected.state.state,
                )
                val midend = Midend.newGame(Cube, "c4x4", SEED)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tile),
                    upstream,
                )
                val expectedTrace = TraceNormalizer.normalize(
                    requireNotNull(expected.trace),
                    upstream,
                )
                assertLines("initial trace at tile $tile", expectedTrace, actual)

                // A loaded moved state is settled: no animation frame and
                // no status suppression. The current square after UR is
                // (1,1), as the same C square geometry gives.
                val movedSave = saveText(DESC, listOf("U", "R"))
                val moved = Midend.fromSave(Cube, SgtpSave.parse(movedSave))
                val state = moved.state as Cube.State
                val cx = state.current % state.params.d1
                val cy = state.current / state.params.d1
                val movedExpected = oracle.pointsSized(
                    movedSave,
                    listOf(
                        OracleClient.Point(
                            (cx + 1) * tile,
                            (cy + 1) * tile,
                            Buttons.LEFT_BUTTON,
                        ),
                    ),
                    view.width,
                    view.height,
                    recordTrace = true,
                )
                assertEquals(
                    "the moved click at tile $tile is inert",
                    movedSave,
                    movedExpected.state.state,
                )
                val movedActual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(moved, tile),
                    upstream,
                )
                val movedExpectedTrace = TraceNormalizer.normalize(
                    requireNotNull(movedExpected.trace),
                    upstream,
                )
                assertLines("moved trace at tile $tile", movedExpectedTrace, movedActual)
                assertTrue(
                    "the moved trace at tile $tile has the move count",
                    movedActual.contains("status \"Moves: 2\""),
                )
            }
        }

    @Test
    fun `sized triangle traces at 63, 82, and 100 match the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val params = Cube.decodeParams("t1x2") as Cube.Params

            // The save is loaded, so the frame is settled. A live midend
            // would record the first animation frame instead. The move
            // changes the current square and one face colour, and the
            // triangle geometry truncates differently at each size.
            val movedSave = oracle.key(
                oracle.newGame("cube", "t1x2", SEED).state,
                0,
                0,
                Buttons.CURSOR_UP,
            ).state
            val midend = Midend.fromSave(Cube, SgtpSave.parse(movedSave))
            assertEquals(2, midend.stateCount)

            for (tile in listOf(63, 82, 100)) {
                val view = Cube.computeSize(params, tile)
                val layout = oracle.layout(movedSave, view.width, view.height)
                assertEquals("layout width at tile $tile", view.width, layout.width)
                assertEquals("layout height at tile $tile", view.height, layout.height)

                // Sizing alone forces the first draw of a fresh drawstate,
                // so no click is needed; an empty point list keeps the
                // frame inert.
                val expected = oracle.pointsSized(
                    movedSave,
                    emptyList(),
                    view.width,
                    view.height,
                    recordTrace = true,
                )
                assertEquals(
                    "the loaded save at tile $tile is unchanged",
                    movedSave,
                    expected.state.state,
                )
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tile),
                    upstream,
                )
                val expectedTrace = TraceNormalizer.normalize(
                    requireNotNull(expected.trace),
                    upstream,
                )
                assertLines("t1x2 trace at tile $tile", expectedTrace, actual)
                assertTrue(
                    "the triangle trace at tile $tile has the move count",
                    actual.contains("status \"Moves: 1\""),
                )
            }
        }

    private data class Quad(
        val params: String,
        val ops: Int,
        val polys: Int,
        val grid: Int,
        val faces: Int,
    )

    private fun traceStatusOf(trace: String): String? {
        var status: String? = null
        for (line in trace.lines()) {
            if (line.startsWith("status ")) {
                status = line.removePrefix("status ").removeSurrounding("\"")
            }
        }
        return status
    }

    private fun traceStatus(oracle: OracleClient, save: String): String? {
        val ops = (oracle.trace(save).field("ops") as JsonValue.Arr).items
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
    }

    private fun saveText(desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Cube.name),
            SaveRecord("PARAMS", "c4x4"),
            SaveRecord("CPARAMS", "c4x4"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
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
        const val DESC = "205E,4"
        val WIN = listOf("U", "R", "R", "D", "D", "R", "L", "D", "L", "L", "R", "U", "D")
    }
}
