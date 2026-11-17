package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: galaxies.
 *
 * The frozen first frames, the sized and letterboxed frames, the tiny
 * viewport, the cursor on a tile, an edge, a vertex, and a dot, the
 * mid-drag preview, and the warm path. Galaxies allocates three
 * blitters and draws arrows with three lines.
 */
class GalaxiesTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun presets() = Galaxies.presets().map { it.params }

    @Test
    fun `the frozen traces match the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in presets()) {
            val midend = Midend.newGame(Galaxies, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("galaxies", params, SEED),
                upstream,
            )
            assertLines("'$params' oracle", expected, actual)

            val golden = File(testdata, "trace/galaxies/${GenTraces.fileName(params)}.trace")
            assertEquals("'$params' frozen", golden.readText(), actual)

            val state = midend.state as Galaxies.State
            val lines = actual.lines()
            assertEquals("'$params' changed_state", 1, lines.count { it.startsWith("changed_state ") })
            assertEquals("'$params' start_draw", 1, lines.count { it == "start_draw" })
            assertEquals("'$params' end_draw", 1, lines.count { it == "end_draw" })
            assertFalse("'$params' status", lines.any { it.startsWith("status ") })
            assertFalse("'$params' text", lines.any { it.startsWith("text ") })
            assertFalse("'$params' poly in a cold frame", lines.any { it.startsWith("poly ") })
            assertFalse("'$params' line in a cold frame", lines.any { it.startsWith("line ") })
            assertEquals("'$params' blitters", 3, lines.count { it.startsWith("blitter_new ") })
            assertFalse("'$params' blitter free", lines.any { it.startsWith("blitter_free ") })
            assertFalse("'$params' blitter save", lines.any { it.startsWith("blitter_save ") })
            assertEquals(
                "'$params' updates",
                2 + state.w * state.h,
                lines.count { it.startsWith("update ") },
            )
            val tile = 32
            assertTrue(
                "'$params' frame",
                lines.any {
                    it == "rect ${tile - 1} ${tile - 1} " +
                        "${state.w * tile + 3} ${state.h * tile + 3} 6"
                },
            )
            assertTrue("'$params' grid lines", lines.count { it.endsWith(" 5") } > 0)
            assertTrue("'$params' edges", lines.count { it.endsWith(" 6") } > 0)
            assertTrue("'$params' dots", lines.count { it.startsWith("circle ") } > 0)
            assertTrue("'$params' dot count", state.dotCount > 0)
        }
    }

    @Test
    fun `a black-dot frame matches the oracle`() = OracleClient().use { oracle ->
        // The black dot uses its own palette entry and marker, so a
        // parser that drops F_DOT_BLACK changes the frame.
        val desc = "Azx"
        val save = buildString {
            append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
            append("VERSION :1:1\n")
            append("GAME    :8:Galaxies\n")
            append("PARAMS  :5:7x7dn\n")
            append("CPARAMS :5:7x7dn\n")
            append("DESC    :3:$desc\n")
            append("NSTATES :1:1\n")
            append("STATEPOS:1:1\n")
        }
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("black dot", expected, actual)
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("7x7dn", "10x10dn", "10x10du")) {
            val save = oracle.newGame("galaxies", params, SEED).state
            for (tile in listOf(32, 48, 63, 82, 100)) {
                val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
                val board = Galaxies.computeSize(Galaxies.decodeParams(params), tile)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tile),
                    upstream,
                )
                val expected = TraceNormalizer.normalize(
                    oracle.pointsSized(
                        save,
                        emptyList(),
                        board.width,
                        board.height,
                        recordTrace = true,
                    ).trace!!,
                    upstream,
                )
                assertLines("$params tile $tile", expected, actual)
                assertTrue(actual.lines().contains("rect 0 0 ${board.width} ${board.height} 0"))
                val state = midend.state as Galaxies.State
                assertTrue(
                    "$params tile $tile dots",
                    actual.lines().count { it.startsWith("circle ") } >= state.dotCount,
                )
            }
        }
    }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        for ((params, view) in listOf(
            "7x7dn" to (411 to 702),
            "10x10du" to (411 to 702),
            "15x15dn" to (1080 to 1920),
        )) {
            val save = oracle.newGame("galaxies", params, SEED).state
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            val geometry = midend.geometryFor(view.first, view.second)
            assertTrue("$params origin x", geometry.originX >= 0)
            assertTrue("$params origin y", geometry.originY >= 0)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                upstream,
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    view.first,
                    view.second,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            assertLines("$params letterbox", expected, actual)
        }
    }

    @Test
    fun `a tiny viewport matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("7x7dn", "10x10dn")) {
            val save = oracle.newGame("galaxies", params, SEED).state
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            val geometry = midend.geometryFor(200, 200)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                upstream,
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(save, emptyList(), 200, 200, recordTrace = true).trace!!,
                upstream,
            )
            assertLines("$params tiny ${geometry.tileSize}", expected, actual)
        }
    }

    @Test
    fun `the cursor frames match the oracle`() = OracleClient().use { oracle ->
        val sequences = listOf(
            // A tile cursor: a polygon outline.
            listOf(Buttons.CURSOR_RIGHT),
            // An edge cursor and a vertex cursor: rectangles.
            listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_DOWN, Buttons.CURSOR_RIGHT),
            // Cursor moves are clamped at every border.
            List(20) { Buttons.CURSOR_LEFT },
            List(20) { Buttons.CURSOR_UP },
        )
        for (params in listOf("7x7dn", "10x10dn")) {
            val save = oracle.newGame("galaxies", params, SEED).state
            for (sequence in sequences) {
                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = sequence),
                    upstream,
                )
                val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
                for (key in sequence) midend.processKey(0, 0, key, 32)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, 32, statusAlreadyDrawn = true),
                    upstream,
                )
                assertLines("$params ${sequence.size} keys", expected, actual)
            }
        }
    }

    @Test
    fun `the cursor on a dot and a keyboard drag match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("galaxies", "7x7dn", SEED).state
            val probe = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            val state = probe.state as Galaxies.State
            val dot = state.dotPositions().first()
            // The cursor starts at (1,1). Move it to the first dot, pick
            // the arrow up, move two cells, and drop it on the first
            // target that accepts the association.
            val sequence = cursorSequence(dot.first, dot.second) + Buttons.CURSOR_SELECT
            val targets = listOf(
                dot.first + 2 to dot.second,
                dot.first - 2 to dot.second,
                dot.first to dot.second + 2,
                dot.first to dot.second - 2,
            )
            var dropped = false
            for ((tx, ty) in targets) {
                if (tx < 1 || ty < 1 || tx > state.sx - 2 || ty > state.sy - 2) continue
                val candidate = sequence + cursorSequenceTo(dot, tx, ty) + Buttons.CURSOR_SELECT
                val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
                for (key in candidate) midend.processKey(0, 0, key, 32)
                if (!midend.canUndo) continue

                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = candidate),
                    upstream,
                )
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, 32, statusAlreadyDrawn = true),
                    upstream,
                )
                assertLines("dot and drag to $tx,$ty", expected, actual)
                assertTrue(
                    "the dropped association is visible",
                    actual.lines().count { it.startsWith("line ") } >= 3,
                )
                dropped = true
                break
            }
            assertTrue("a drop target was found", dropped)
        }

    @Test
    fun `the warm drag path matches the oracle`() = OracleClient().use { oracle ->
        val params = "7x7dn"
        val save = oracle.newGame("galaxies", params, SEED).state
        // Stop at the drag, so the recorded frame still shows the two
        // live sprites and is not an empty frame.
        val points = rightDrag(save).dropLast(1)
        val actual = normalizedWarm(
            KotlinTraceSink.warmTrace(Midend.fromSave(Galaxies, SgtpSave.parse(save)), points, 32),
            upstream,
        )
        val expected = normalizedWarm(
            oracle.traceWarm(save, points).trace!!,
            upstream,
        )
        assertLines("warm drag", expected, actual)
        val lines = actual.lines()
        assertTrue("non-vacuous warm frame", lines.size > 2)
        assertEquals("two arrow sprites", 2, lines.count { it.startsWith("blitter_save ") })
        assertEquals("six lines", 6, lines.count { it.startsWith("line ") })
    }

    @Test
    fun `the mid-drag preview is pinned on the port side`() {
        // The oracle records one redraw after a whole input sequence, so
        // it cannot show the frame in the middle of a right drag. The
        // preview shows the same arrow over the drag point and over its
        // mirror, each saved to its own blitter.
        val midend = Midend.newGame(Galaxies, "7x7dn", SEED)
        val save = midend.serialise().serialise()
        val points = rightDrag(save)
        // The frame of the drag input itself, on a warm drawstate.
        val frame = normalizedWarm(
            KotlinTraceSink.warmFrame(midend, points.dropLast(1), 32),
            upstream,
        )
        val ui = midend.ui as Galaxies.Ui
        assertTrue("dragging", ui.dragging)
        val lines = frame.lines()
        assertEquals("two arrow sprites", 2, lines.count { it.startsWith("blitter_save ") })
        assertEquals("two clips", 2, lines.count { it.startsWith("clip ") })
        assertEquals("two unclips", 2, lines.count { it == "unclip" })
        assertEquals("six lines", 6, lines.count { it.startsWith("line ") })
        // The drag point and the mirror sit at half-tile offsets from a
        // dot, so no tile outline changed.
        assertFalse("no cursor", lines.any { it.startsWith("poly ") })
    }

    private fun cursorSequenceTo(from: Pair<Int, Int>, tx: Int, ty: Int): List<Int> {
        val out = mutableListOf<Int>()
        repeat(kotlin.math.abs(tx - from.first)) {
            out += if (tx > from.first) Buttons.CURSOR_RIGHT else Buttons.CURSOR_LEFT
        }
        repeat(kotlin.math.abs(ty - from.second)) {
            out += if (ty > from.second) Buttons.CURSOR_DOWN else Buttons.CURSOR_UP
        }
        return out
    }

    private fun cursorSequence(dotX: Int, dotY: Int): List<Int> {
        val out = mutableListOf<Int>()
        // The cursor starts at (1,1).
        repeat(dotX - 1) { out += Buttons.CURSOR_RIGHT }
        repeat(dotY - 1) { out += Buttons.CURSOR_DOWN }
        return out
    }

    /** A right press, drag, and release from the first dot of the answer. */
    private fun rightDrag(save: String): List<OracleClient.Point> {
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
        val aux = midend.auxInfo!!
        for (token in aux.split(';')) {
            if (!token.startsWith("a")) continue
            val parts = token.substring(1).split(',').map { it.toInt() }
            val x = parts[0]
            val y = parts[1]
            val dx = parts[2]
            val dy = parts[3]
            val pressX = dx * 16 + 32
            val pressY = dy * 16 + 32
            val releaseX = 32 + ((x - 1) / 2) * 32 + 16
            val releaseY = 32 + ((y - 1) / 2) * 32 + 16
            val points = listOf(
                OracleClient.Point(pressX, pressY, Buttons.RIGHT_BUTTON),
                OracleClient.Point(
                    (pressX + releaseX) / 2,
                    (pressY + releaseY) / 2,
                    Buttons.RIGHT_DRAG,
                ),
                OracleClient.Point(releaseX, releaseY, Buttons.RIGHT_RELEASE),
            )
            val probe = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            for (p in points) probe.processKey(p.x, p.y, p.button, 32)
            if (probe.canUndo) return points
        }
        error("no association of the stored answer makes a drag move")
    }


    /**
     * A warm trace drops its `blitter_new` operations but keeps the id
     * counter, so the normalizer needs a synthetic allocation for each
     * id before it accepts the trace. The synthetic line is removed
     * again, as `GuessTraceTest` does.
     */
    private fun normalizedWarm(trace: JsonValue.Obj, upstream: String): String {
        val ops = (trace.field("ops") as JsonValue.Arr).items.toMutableList()
        val ids = ops.asSequence()
            .filterIsInstance<JsonValue.Obj>()
            .mapNotNull { (it.fields["id"] as? JsonValue.Num)?.value }
            .distinct()
            .toList()
        ops.addAll(
            0,
            ids.map { id ->
                JsonValue.Obj(
                    linkedMapOf(
                        "op" to JsonValue.Str("blitter_new"),
                        "id" to JsonValue.Num(id),
                        "w" to JsonValue.Num(1),
                        "h" to JsonValue.Num(1),
                    ),
                )
            },
        )
        val patched = JsonValue.Obj(
            LinkedHashMap(trace.fields).apply { put("ops", JsonValue.Arr(ops)) },
        )
        return TraceNormalizer.normalize(patched, upstream)
            .lines()
            .filterNot { it.startsWith("blitter_new ") }
            .joinToString("\n", postfix = "\n")
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError(
                    "$what line ${i + 1}:\n expected: ${e[i]}\n   actual: ${a[i]}\n" +
                        "context:\n" + e.drop(maxOf(0, i - 3)).take(8).joinToString("\n") {
                            "  E $it"
                        } + "\n" + a.drop(maxOf(0, i - 3)).take(8).joinToString("\n") { "  A $it" },
                )
            }
        }
        assertEquals("$what line count", e.size, a.size)
    }

    private companion object {
        const val SEED = "12345"
    }
}
