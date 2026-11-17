package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: loopy.
 *
 * The frozen first frames, the sized frames, the letterbox, the tiny
 * viewport, the cursor, the faint-line preference, and the warm path.
 * Loopy is the first game that draws every edge as a thick line, caches
 * a cursor blitter, and prints a clue in every face.
 */
class LoopyTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun presets() = Loopy.presets().map { it.params }

    @Test
    fun `the frozen traces match the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in presets()) {
            val midend = Midend.newGame(Loopy, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("loopy", params, SEED),
                upstream,
            )
            assertLines("'$params' oracle", expected, actual)

            val golden = File(testdata, "trace/loopy/${GenTraces.fileName(params)}.trace")
            assertEquals("'$params' frozen", golden.readText(), actual)

            val state = midend.state as Loopy.State
            val lines = actual.lines()
            val edges = state.grid.edges.size
            val dots = state.grid.dots.size
            val clues = state.clues.count { it >= 0 }

            // Every edge is drawn once, every dot is one circle, and
            // every clue is one text.
            assertEquals("'$params' edges", edges, lines.count { it.startsWith("thick_line ") })
            assertEquals("'$params' dots", dots, lines.count { it.startsWith("circle ") })
            assertEquals("'$params' clues", clues, lines.count { it.startsWith("text ") })
            assertTrue("'$params' clues non-empty", clues > 0)

            // A fresh drawstate allocates its cursor blitter in
            // `new_drawstate`; the first frame has no cursor, so the
            // blitter is neither saved nor loaded.
            assertEquals("'$params' blitter_new", 1, lines.count { it.startsWith("blitter_new ") })
            assertEquals("'$params' blitter_load", 0, lines.count { it.startsWith("blitter_load ") })
            assertEquals("'$params' blitter_save", 0, lines.count { it.startsWith("blitter_save ") })
            assertFalse("'$params' plain line", lines.any { it.startsWith("line ") })
            assertFalse("'$params' poly", lines.any { it.startsWith("poly ") })
            assertFalse("'$params' status", lines.any { it.startsWith("status ") })
            assertEquals("'$params' changed_state", 1, lines.count { it.startsWith("changed_state ") })

            // A fresh board has only unknown edges, so every edge uses
            // the main thickness: `3/32` of the preferred tile of 32.
            val thicknesses = thicknessesOf(lines)
            assertEquals("'$params' thicknesses", setOf(3000), thicknesses)
        }
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        for ((params, tile) in listOf(
            "7x7t0de" to 48,
            "7x7t2dh" to 63,
            "5x5t7dh" to 82,
            "6x6t16dh" to 100,
        )) {
            val save = oracle.newGame("loopy", params, SEED).state
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
            val board = Loopy.computeSize(Loopy.decodeParams(params), tile)
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
            assertTrue(
                actual.lines().contains("rect 0 0 ${board.width} ${board.height} 0"),
            )
            assertTrue(
                actual.lines().contains("update 0 0 ${board.width} ${board.height}"),
            )
        }
    }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        for ((params, view) in listOf(
            "7x7t0de" to (411 to 702),
            "5x5t7dh" to (411 to 702),
            "6x6t16dh" to (1080 to 1920),
        )) {
            val save = oracle.newGame("loopy", params, SEED).state
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
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
        for (params in listOf("7x7t0de", "5x5t7dh", "6x6t16dh")) {
            val save = oracle.newGame("loopy", params, SEED).state
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
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
            listOf(Buttons.CURSOR_RIGHT),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
            ),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT2,
                Buttons.CURSOR_SELECT2,
            ),
        )
        for (params in listOf("7x7t0de", "5x5t7dh", "6x6t16dh")) {
            val save = oracle.newGame("loopy", params, SEED).state
            for (sequence in sequences) {
                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = sequence),
                    upstream,
                )
                val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
                for (key in sequence) midend.processKey(0, 0, key, 32)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, 32),
                    upstream,
                )
                assertLines("$params ${sequence.size} keys", expected, actual)
                val lines = actual.lines()
                if (sequence.last() in listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2)) {
                    assertTrue(
                        "$params cursor save",
                        lines.any { it.startsWith("blitter_save ") },
                    )
                }
            }
        }
    }

    @Test
    fun `faint lines on and off match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("7x7t0de", "5x5t7dh")) {
            // Set the first edge to NO, so the frame has a faint line.
            val base = oracle.newGame("loopy", params, SEED)
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            val tile = 32
            val board = Loopy.computeSize(Loopy.decodeParams(params), tile)
            val (x, y) = firstEdgePoint(midend, tile)
            val points = listOf(
                OracleClient.Point(x, y, Buttons.RIGHT_BUTTON),
                OracleClient.Point(x, y, Buttons.RIGHT_RELEASE),
            )
            val noEdge = oracle.pointsSized(base.state, points, board.width, board.height)
            assertEquals("$params no edge", 1, SgtpSave.parse(noEdge.state.state).values("MOVE").size)
            assertEquals("$params no is n", 'n', SgtpSave.parse(noEdge.state.state).value("MOVE")!!.last())

            val on = TraceNormalizer.normalize(
                oracle.trace(noEdge.state.state),
                upstream,
            )
            val onMidend = Midend.fromSave(Loopy, SgtpSave.parse(noEdge.state.state))
            val onActual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(onMidend, tile),
                upstream,
            )
            assertLines("$params faint on", on, onActual)
            assertTrue(
                "$params faint thickness present",
                1333 in thicknessesOf(onActual.lines()),
            )

            val off = TraceNormalizer.normalize(
                oracle.trace(noEdge.state.state, prefs = "draw-faint-lines=false"),
                upstream,
            )
            val offMidend = Midend.fromSave(Loopy, SgtpSave.parse(noEdge.state.state))
            (offMidend.ui as Loopy.Ui).drawFaintLines = false
            offMidend.forceRedraw()
            val offActual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(offMidend, tile),
                upstream,
            )
            assertLines("$params faint off", off, offActual)

            // The excluded edge is not drawn at all, so the faint
            // thickness disappears from the frame.
            assertFalse(
                "$params no faint thickness",
                1333 in thicknessesOf(offActual.lines()),
            )
        }
    }

    @Test
    fun `the warm click frame matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("7x7t0de", "5x5t7dh")) {
            val save = oracle.newGame("loopy", params, SEED).state
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
            val tile = 32
            val board = Loopy.computeSize(Loopy.decodeParams(params), tile)
            val (x, y) = firstEdgePoint(midend, tile)
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points, board.width, board.height).trace!!,
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, points, tile),
                upstream,
            )
            assertLines("$params warm", expected, actual)
        }
    }

    @Test
    fun `an error frame matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("loopy", "7x7t0de", SEED).state
        val probe = Midend.fromSave(Loopy, SgtpSave.parse(base)).state as Loopy.State
        val dot = probe.grid.dots.first { it.order >= 3 }
        val edges = (0 until minOf(3, dot.order)).map { dot.edges[it]!!.index }
        val move = edges.joinToString("") { "${it}y" }
        val save = appendMove(base, move)
        val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
        val state = midend.state as Loopy.State
        assertTrue("the vertex is overfull", state.lineErrors.any { it })

        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 32), upstream)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("error frame", expected, actual)
        // The mistake colour is the foreground error mark.
        assertTrue(
            "error colour",
            actual.lines().any { it.startsWith("thick_line ") && it.endsWith(" 4") },
        )
    }

    @Test
    fun `a single closed loop frame matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("loopy", "5x5t7dh", SEED).state
        val probe = Midend.fromSave(Loopy, SgtpSave.parse(base)).state as Loopy.State
        val face = probe.grid.faces[0]
        val edges = (0 until face.order).map { face.edge(it).index }
        val move = edges.joinToString("") { "${it}y" }
        val save = appendMove(base, move)
        val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
        val state = midend.state as Loopy.State
        assertTrue("one loop", state.exactlyOneLoop)

        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 32), upstream)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("single loop frame", expected, actual)
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private fun thicknessesOf(lines: List<String>): Set<Int> =
        lines.filter { it.startsWith("thick_line ") }
            .map { it.split(" ")[1].toInt() }
            .toSet()

    private fun firstEdgePoint(midend: Midend, tile: Int): Pair<Int, Int> {
        val state = midend.state as Loopy.State
        val g = state.grid
        val index = state.lines.indices.first()
        val e = g.edges[index]
        val gx = (e.dot1.x + e.dot2.x) / 2
        val gy = (e.dot1.y + e.dot2.y) / 2
        val border = tile / 2
        return Pair(
            border + (gx - g.lowestX) * tile / g.tileSize,
            border + (gy - g.lowestY) * tile / g.tileSize,
        )
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError(
                    "$what: first diff at line ${i + 1}\n" +
                        "expected: ${e[i]}\nactual:   ${a[i]}",
                )
            }
        }
        if (e.size != a.size) throw AssertionError("$what: ${e.size} against ${a.size} lines")
    }

    private companion object {
        const val SEED = "12345"
    }
}
