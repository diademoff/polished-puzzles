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
 * Level: strict. Game: rect.
 *
 * The frozen first frames, the sized and letterboxed frames, the tiny
 * viewport, the cursor, a warm drag, the completed frame, and the
 * cheated solve frame. Rect has no blitter and no thick primitive.
 */
class RectTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun presets() = Rect.presets().map { it.params }

    @Test
    fun `the frozen traces match the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in presets()) {
            val midend = Midend.newGame(Rect, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("rect", params, SEED),
                upstream,
            )
            assertLines("'$params' oracle", expected, actual)

            val golden = File(testdata, "trace/rect/${GenTraces.fileName(params)}.trace")
            assertEquals("'$params' frozen", golden.readText(), actual)

            val state = midend.state as Rect.State
            val lines = actual.lines()
            val numbers = state.grid.count { it != 0 }
            assertTrue("'$params' numbers", numbers > 0)
            assertEquals("'$params' texts", numbers, lines.count { it.startsWith("text ") })
            assertEquals("'$params' changed_state", 1, lines.count { it.startsWith("changed_state ") })
            assertEquals("'$params' start_draw", 1, lines.count { it == "start_draw" })
            assertEquals("'$params' end_draw", 1, lines.count { it == "end_draw" })
            assertEquals("'$params' status", listOf("status \"\""), lines.filter { it.startsWith("status ") })
            assertFalse("'$params' blitter", lines.any { it.startsWith("blitter_") })
            assertFalse("'$params' thick", lines.any { it.startsWith("thick_") })
            assertFalse("'$params' poly", lines.any { it.startsWith("poly ") })

            // One whole-board clear in the line colour, then one update
            // per tile and the two whole-board updates of a first draw.
            val size = Rect.computeSize(Rect.decodeParams(params), 24)
            assertTrue(
                "'$params' clear",
                lines.any { it == "rect 1 1 ${state.w * 24 + 3} ${state.h * 24 + 3} 2" },
            )
            assertEquals("'$params' whole updates", 2, lines.count { it == "update 0 0 ${size.width} ${size.height}" })
            assertEquals(
                "'$params' tile updates",
                state.w * state.h,
                lines.count { it.startsWith("update ") } - 2,
            )
        }
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("7x7", "9x9", "11x11")) {
            val save = oracle.newGame("rect", params, SEED).state
            for (tile in listOf(24, 31, 48, 63)) {
                val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
                val board = Rect.computeSize(Rect.decodeParams(params), tile)
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
            }
        }
    }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        for ((params, view) in listOf(
            "7x7" to (411 to 702),
            "9x9" to (411 to 702),
            "11x11" to (1080 to 1920),
        )) {
            val save = oracle.newGame("rect", params, SEED).state
            val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
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
        for (params in listOf("7x7", "9x9", "11x11")) {
            val save = oracle.newGame("rect", params, SEED).state
            val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
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
    fun `the cursor and keyboard drag frames match the oracle`() = OracleClient().use { oracle ->
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT2,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT2,
            ),
        )
        for (params in listOf("7x7", "9x9")) {
            val save = oracle.newGame("rect", params, SEED).state
            for (sequence in sequences) {
                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = sequence),
                    upstream,
                )
                val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
                for (key in sequence) midend.processKey(0, 0, key, 24)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, 24, statusAlreadyDrawn = true),
                    upstream,
                )
                assertLines("$params ${sequence.size} keys", expected, actual)
            }
        }
    }

    @Test
    fun `the warm drag frame matches the oracle`() = OracleClient().use { oracle ->
        val params = "7x7"
        val save = oracle.newGame("rect", params, SEED).state
        val tile = 24
        val board = Rect.computeSize(Rect.decodeParams(params), tile)
        val points = listOf(
            OracleClient.Point(38, 26, Buttons.LEFT_BUTTON),
            OracleClient.Point(50, 38, Buttons.LEFT_DRAG),
            OracleClient.Point(62, 50, Buttons.LEFT_DRAG),
            OracleClient.Point(62, 50, Buttons.LEFT_RELEASE),
        )
        val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, points, tile),
            upstream,
        )
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, points, board.width, board.height).trace!!,
            upstream,
        )
        assertLines("warm drag", expected, actual)
    }

    @Test
    fun `the in-progress drag preview and status are pinned`() {
        // The oracle cannot record a frame in the middle of an input,
        // because its traced redraw happens after the release. The
        // preview colour and the drag dimension prefix are pinned on
        // the port side.
        val midend = Midend.newGame(Rect, "7x7", SEED)
        val points = listOf(
            OracleClient.Point(14, 14, Buttons.LEFT_BUTTON),
            OracleClient.Point(26, 26, Buttons.LEFT_DRAG),
            OracleClient.Point(38, 38, Buttons.LEFT_DRAG),
        )
        for (p in points) midend.processKey(p.x, p.y, p.button, 24)
        val ui = midend.ui as Rect.Ui
        assertTrue("dragging", ui.dragged)
        val preview = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 24), upstream)
        assertEquals("preview status", listOf("status \"2x2 \""), preview.lines().filter { it.startsWith("status ") })
        assertTrue(
            "preview colour",
            preview.lines().any { it.startsWith("rect ") && it.endsWith(" 5") },
        )

        // Release clears the preview and the status prefix.
        midend.processKey(38, 38, Buttons.LEFT_RELEASE, 24)
        assertFalse("released", ui.dragged)
        val after = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 24), upstream)
        assertEquals("after status", listOf("status \"\""), after.lines().filter { it.startsWith("status ") })
        assertFalse("no preview", after.lines().any { it.startsWith("rect ") && it.endsWith(" 5") })

        // A right drag uses the erase colour.
        val erase = Midend.newGame(Rect, "7x7", SEED)
        val erasePoints = listOf(
            OracleClient.Point(14, 14, Buttons.RIGHT_BUTTON),
            OracleClient.Point(26, 26, Buttons.RIGHT_DRAG),
            OracleClient.Point(38, 38, Buttons.RIGHT_DRAG),
        )
        for (p in erasePoints) erase.processKey(p.x, p.y, p.button, 24)
        val erasePreview = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(erase, 24),
            upstream,
        )
        assertTrue(
            "erase preview colour",
            erasePreview.lines().any { it.startsWith("rect ") && it.endsWith(" 6") },
        )
        assertEquals(
            "erase status",
            listOf("status \"2x2 \""),
            erasePreview.lines().filter { it.startsWith("status ") },
        )
    }

    @Test
    fun `the completed frame matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val aux = Midend.fromSave(Rect, SgtpSave.parse(base.state)).auxInfo!!
        assertTrue("the aux is the S payload", aux.startsWith("S"))
        val moves = toggleMoves(base.state, aux)
        assertTrue("many toggles", moves.size > 10)
        val save = moves.fold(base.state) { text, move -> appendMove(text, move) }
        assertEquals("C wins honestly", 1, oracle.id(save).status)

        val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
        assertEquals("port wins", 1, midend.status())
        assertEquals("completed status", "COMPLETED!", midend.statusText())
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 24), upstream)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("completed frame", expected, actual)
        // Every cell is correct, so every tile draws the correct
        // background colour, which is palette index 1.
        assertTrue(actual.lines().count { it.contains(" 1") && it.startsWith("rect ") } > 0)
    }

    @Test
    fun `the cheated frame matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val solve = SgtpSave.parse(oracle.solve(base.state).state).value("SOLVE")!!
        val save = appendMove(base.state, solve)
        assertEquals("C stays open", 0, oracle.id(save).status)

        val midend = Midend.fromSave(Rect, SgtpSave.parse(save))
        assertEquals("open status", 0, midend.status())
        assertEquals("cheated status", "Auto-solved.", midend.statusText())
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 24), upstream)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("cheated frame", expected, actual)
    }

    /**
     * Turns the AUXINFO edge bits into one `H`/`V` toggle per edge.
     * Each token must be its own MOVE record: `execute_move` parses a
     * single token and ignores trailing text.
     */
    private fun toggleMoves(state: String, aux: String): List<String> {
        val save = SgtpSave.parse(state)
        val params = Rect.decodeParams(save.value("PARAMS")!!) as Rect.Params
        val out = mutableListOf<String>()
        var p = 1
        for (y in 0 until params.h) {
            for (x in 1 until params.w) {
                if (aux[p] == '1') out += "V$x,$y"
                p++
            }
        }
        for (y in 1 until params.h) {
            for (x in 0 until params.w) {
                if (aux[p] == '1') out += "H$x,$y"
                p++
            }
        }
        return out
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "MOVE    :${move.length}:$move\n"
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
