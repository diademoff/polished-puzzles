package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: pearl.
 *
 * The eight frozen first frames, the sized frames, the letterbox, the
 * warm path, and the error, mark, cursor, and drag frames. The trace
 * has no blitter, no text, no polygon, and no thick primitive.
 */
class PearlTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun presets() = Pearl.presets().map { it.params }

    @Test
    fun `the frozen traces match the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in presets()) {
            val midend = Midend.newGame(Pearl, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("pearl", params, SEED),
                upstream,
            )
            assertLines("'$params' oracle", expected, actual)

            val golden = File(testdata, "trace/pearl/${GenTraces.fileName(params)}.trace")
            assertEquals("'$params' frozen", golden.readText(), actual)

            val p = Pearl.decodeParams(params) as Pearl.Params
            val lines = actual.lines()
            assertEquals("'$params' clips", p.w * p.h, lines.count { it.startsWith("clip ") })
            assertEquals("'$params' unclips", p.w * p.h, lines.count { it == "unclip" })
            // The game's whole-board update and the midend's first-draw
            // update, plus one update per cell.
            assertEquals(
                "'$params' updates",
                p.w * p.h + 2,
                lines.count { it.startsWith("update ") },
            )
            // No line or mark: the two grid outlines per cell only.
            assertEquals("'$params' lines", 2 * p.w * p.h, lines.count { it.startsWith("line ") })
            assertEquals("'$params' changed_state", 1, lines.count { it.startsWith("changed_state ") })
            assertEquals("'$params' start_draw", 1, lines.count { it == "start_draw" })
            assertEquals("'$params' end_draw", 1, lines.count { it == "end_draw" })
            assertEquals("'$params' status", 0, lines.count { it.startsWith("status ") })
            assertEquals(
                "'$params' whole update",
                2,
                lines.count { it == "update 0 0 ${midend.size.width} ${midend.size.height}" },
            )
            assertEquals(
                "'$params' clear",
                1,
                lines.count { it == "rect 0 0 ${midend.size.width} ${midend.size.height} 0" },
            )
            // The black grid block at the border.
            val half = (31 - 1) / 2
            val tile = half * 2 + 1
            val border = tile / 2
            val borderWidth = maxOf(tile / 32, 1)
            assertEquals(
                "'$params' black block",
                1,
                lines.count {
                    it.startsWith(
                        "rect ${border - borderWidth} ${border - borderWidth} " +
                            "${p.w * tile + 2 * borderWidth + 1} " +
                            "${p.h * tile + 2 * borderWidth + 1} 6",
                    )
                },
            )
            assertNoBlitter("'$params'", actual)
            assertFalse("'$params' text", lines.any { it.startsWith("text ") })
            assertFalse("'$params' poly", lines.any { it.startsWith("poly ") })
            assertFalse("'$params' thick", lines.any { it.startsWith("thick_") })
        }
    }

    @Test
    fun `sized traces match the oracle at 48 63 82 and 100`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "8x8de", SEED).state
        for ((view, tile) in listOf(422 to 48, 566 to 64, 728 to 82, 890 to 100)) {
            val expected = oracle.pointsSized(save, emptyList(), view, view, recordTrace = true)
            val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
            assertEquals("view $view tile", tile, midend.largestTileFor(view, view))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream,
            )
            assertLines(
                "view $view",
                TraceNormalizer.normalize(expected.trace!!, upstream),
                actual,
            )
            assertTrue(actual.lines().contains("rect 0 0 $view $view 0"))
            assertTrue(actual.lines().contains("update 0 0 $view $view"))
            assertNoBlitter("view $view", actual)
        }
    }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "8x8de", SEED).state
        val expected = oracle.pointsSized(save, emptyList(), 411, 702, recordTrace = true)
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(46, geometry.tileSize)
        assertEquals(3, geometry.originX)
        assertEquals(149, geometry.originY)
        assertEquals(404, geometry.width)
        assertEquals(404, geometry.height)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream,
        )
        assertLines(
            "letterbox",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            actual,
        )
        assertTrue(actual.lines().contains("rect 0 0 404 404 0"))
        assertTrue(actual.lines().contains("update 0 0 404 404"))
    }

    @Test
    fun `the warm path after a click is an empty frame`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "6x6de", SEED).state
        val tile = 31
        val border = tile / 2
        val x = border + tile / 2
        val y = border + tile / 2 + tile / 4 + 1
        val points = listOf(
            OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
            OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.traceWarm(save, points, 216, 216)
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, points, tile),
            upstream,
        )
        assertLines(
            "warm click",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            actual,
        )
        assertEquals(
            "the click's own redraw updated the cache",
            listOf("start_draw", "end_draw"),
            actual.lines().drop(7).filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `the warm path after a cursor move and a drag is empty`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "6x6de", SEED).state
        val tile = 31
        val border = tile / 2
        fun px(x: Int) = border + x * tile + tile / 2
        fun py(y: Int) = border + y * tile + tile / 2

        val cursor = listOf(OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT))
        val expectedCursor = oracle.traceWarm(save, cursor, 216, 216)
        val cursorMidend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val actualCursor = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(cursorMidend, cursor, tile),
            upstream,
        )
        assertLines(
            "warm cursor",
            TraceNormalizer.normalize(expectedCursor.trace!!, upstream),
            actualCursor,
        )

        val drag = listOf(
            OracleClient.Point(px(0), py(0), Buttons.LEFT_BUTTON),
            OracleClient.Point(px(1), py(0), Buttons.LEFT_DRAG),
            OracleClient.Point(px(2), py(0), Buttons.LEFT_DRAG),
            OracleClient.Point(px(2), py(0), Buttons.LEFT_RELEASE),
        )
        val expectedDrag = oracle.traceWarm(save, drag, 216, 216)
        val dragMidend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val actualDrag = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(dragMidend, drag, tile),
            upstream,
        )
        assertLines(
            "warm drag",
            TraceNormalizer.normalize(expectedDrag.trace!!, upstream),
            actualDrag,
        )
    }

    @Test
    fun `a clue error draws the red circle and the red edge`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val midend = Midend.fromGameId(Pearl, base.id)
        val state = midend.state as Pearl.State

        // Find a corner clue with room above and below, and lay a
        // straight line through it. The clue rule marks the clue itself.
        var x = -1
        var y = -1
        for (yy in 1 until state.height - 1) {
            for (xx in 0 until state.width) {
                if (state.clueAt(xx, yy) == 1) {
                    x = xx
                    y = yy
                    break
                }
            }
            if (x >= 0) break
        }
        assertTrue("a corner clue", x >= 0)
        val straight = "F2,$x,$y;F8,$x,${y - 1};F8,$x,$y;F2,$x,${y + 1}"
        // A degree-three cell on a different cell marks its edges too.
        val sx = state.width - 2
        val sy = state.height - 2
        val silly = "F4,$sx,$sy;F1,${sx - 1},$sy;" +
            "F1,$sx,$sy;F4,${sx + 1},$sy;" +
            "F2,$sx,$sy;F8,$sx,${sy - 1}"
        val move = "$straight;$silly"
        val save = appendMove(base.state, move)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        val kotlin = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(kotlin), upstream)
        assertLines("error frame", expected, actual)

        val lines = actual.lines()
        assertTrue(
            "the error clue circle",
            lines.any { it.startsWith("circle ") && it.endsWith(" 11 5 5") },
        )
        assertTrue(
            "a red edge",
            lines.any { it.startsWith("rect ") && it.endsWith(" 5") },
        )
        assertEquals(0, lines.count { it.startsWith("status ") })
        assertNoBlitter("error frame", actual)
    }

    @Test
    fun `a mark and a line draw in band C order`() = OracleClient().use { oracle ->
        val base = oracle.newGame("pearl", "6x6de", SEED)
        val midend = Midend.fromGameId(Pearl, base.id)
        val state = midend.state as Pearl.State
        val move = "F1,0,0;F4,1,0;M8,1,1;M2,1,2"
        val save = appendMove(base.state, move)
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        val kotlin = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(kotlin), upstream)
        assertLines("line and mark", expected, actual)

        val lines = actual.lines()
        // A laid line is a rectangle plus the square end cap, then the
        // mark is two diagonal lines in COL_BLACK.
        assertTrue(lines.any { it.startsWith("rect ") && it.endsWith(" 3") })
        assertTrue(
            "the mark diagonals",
            lines.count { it.startsWith("line ") && it.endsWith(" 3") } >= 2,
        )
        assertFalse(lines.any { it.startsWith("text ") })
    }

    @Test
    fun `a cursor sequence draws the cursor cell`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "6x6de", SEED).state
        val sequence = listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN)
        val expected = TraceNormalizer.normalize(
            oracle.trace(save, sequence = sequence),
            upstream,
        )
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        for (key in sequence) midend.processKey(0, 0, key, 31)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, 31),
            upstream,
        )
        assertLines("cursor", expected, actual)
        assertTrue(
            "the cursor background",
            actual.lines().contains("rect 15 46 31 31 2"),
        )
    }

    @Test
    fun `a drag in progress draws the drag colours`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "6x6de", SEED).state
        val tile = 31
        val border = tile / 2
        fun px(x: Int) = border + x * tile + tile / 2
        fun py(y: Int) = border + y * tile + tile / 2
        val points = listOf(
            OracleClient.Point(px(0), py(0), Buttons.LEFT_BUTTON),
            OracleClient.Point(px(1), py(0), Buttons.LEFT_DRAG),
            OracleClient.Point(px(2), py(0), Buttons.LEFT_DRAG),
        )
        val expected = oracle.pointsSized(save, points, 216, 216, recordTrace = true)
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        for (point in points) midend.processKey(point.x, point.y, point.button, tile)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, tile),
            upstream,
        )
        assertLines(
            "drag preview",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            actual,
        )
        assertTrue(
            "a drag-on rectangle",
            actual.lines().any { it.startsWith("rect ") && it.endsWith(" 8") },
        )
    }

    @Test
    fun `a new drawstate at a new tile size is a first draw again`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pearl", "6x6de", SEED).state
        val midend = Midend.fromSave(Pearl, SgtpSave.parse(save))
        val first = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 48), upstream)
        assertTrue(first.lines().contains("rect 0 0 328 328 0"))
        assertTrue(first.lines().contains("update 0 0 328 328"))
        assertEquals(36, first.lines().count { it.startsWith("clip ") })

        val resized = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 63), upstream)
        assertTrue(resized.lines().contains("rect 0 0 440 440 0"))
        assertTrue(resized.lines().contains("update 0 0 440 440"))

        midend.forceRedraw()
        val forced = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 63), upstream)
        assertTrue(forced.lines().contains("rect 0 0 440 440 0"))
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private fun assertNoBlitter(what: String, trace: String) {
        assertFalse(
            "$what names a blitter",
            trace.lines().any {
                it.startsWith("blitter_new ") || it.startsWith("blitter_free ") ||
                    it.startsWith("blitter_save ") || it.startsWith("blitter_load ")
            },
        )
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
    }
}
