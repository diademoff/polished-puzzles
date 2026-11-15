package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: palisade.
 *
 * The frozen traces are the first draw of each preset. The other tests
 * cover a loaded save, a cursor sequence, the full-grid cursor, a warm
 * frame after a click and after a reveal, the four tile sizes, the
 * letterbox, the clear-complete-regions preference, and the S answer.
 */
class PalisadeTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the frozen traces match the oracle for every preset`() = OracleClient().use { oracle ->
        val expectedCounts = mapOf(
            "5x5n5" to TraceCounts(rect = 162, clips = 25, updates = 27, texts = 8),
            "8x6n6" to TraceCounts(rect = 304, clips = 48, updates = 50, texts = 19),
            "10x8n8" to TraceCounts(rect = 500, clips = 80, updates = 82, texts = 24),
            "15x12n10" to TraceCounts(rect = 1109, clips = 180, updates = 182, texts = 75),
        )
        assertEquals(
            presets(),
            oracle.presets("palisade").map { it.params },
        )
        for (params in presets()) {
            val midend = Midend.newGame(Palisade, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("palisade", params, SEED),
                upstream,
            )
            assertLines("'$params' oracle", expected, actual)

            val golden = File(testdata, "trace/palisade/${GenTraces.fileName(params)}.trace")
            assertEquals("'$params' frozen", golden.readText(), actual)

            val lines = actual.lines()
            assertEquals("'$params' rects", expectedCounts.getValue(params).rect, lines.count { it.startsWith("rect ") })
            assertEquals("'$params' clips", expectedCounts.getValue(params).clips, lines.count { it.startsWith("clip ") })
            assertEquals("'$params' unclips", expectedCounts.getValue(params).clips, lines.count { it == "unclip" })
            assertEquals("'$params' updates", expectedCounts.getValue(params).updates, lines.count { it.startsWith("update ") })
            assertEquals("'$params' texts", expectedCounts.getValue(params).texts, lines.count { it.startsWith("text ") })
            assertEquals("'$params' status", 1, lines.count { it.startsWith("status ") })
            assertEquals("'$params' changed_state", 1, lines.count { it.startsWith("changed_state ") })
            assertTrue("'$params' clear", lines.contains("rect 0 0 ${midend.size.width} ${midend.size.height} 0"))
            assertTrue("'$params' window update", lines.contains("update 0 0 ${midend.size.width} ${midend.size.height}"))
            // The flash is not ported, so no tile uses COL_FLASH.
            assertFalse(
                "'$params' flash colour",
                lines.any { it.startsWith("rect ") && it.endsWith(" 1") },
            )
            assertNoBlitter("'$params'", actual)
            assertFalse("'$params' circle", lines.any { it.startsWith("circle ") })
            assertFalse("'$params' line", lines.any { it.startsWith("line ") })
            assertFalse("'$params' poly", lines.any { it.startsWith("poly ") })
        }
    }

    @Test
    fun `a loaded save draws one status and no changed_state`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "5x5n5", SEED).state
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("loaded save", expected, actual)
        assertEquals(1, actual.lines().count { it.startsWith("status ") })
        assertEquals(0, actual.lines().count { it.startsWith("changed_state ") })
    }

    @Test
    fun `a cursor sequence draws the ring`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "2x3n3", "d33").state
        val sequence = listOf(
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_RIGHT,
        )
        val expected = TraceNormalizer.normalize(
            oracle.trace(save, sequence = sequence),
            upstream,
        )
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        for (key in sequence) midend.processKey(0, 0, key, TILE)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, TILE, statusAlreadyDrawn = true),
            upstream,
        )
        assertLines("cursor ring", expected, actual)
        assertTrue(actual.lines().any { it.startsWith("poly ") })
        assertTrue(
            "the ring update",
            actual.lines().contains("update 66 66 16 16"),
        )
        assertNoBlitter("cursor ring", actual)
    }

    @Test
    fun `the full-grid cursor trace draws eight corner lines`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "2x3n3", "d33").state
        val sequence = listOf(
            'l'.code,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            'l'.code,
            Buttons.CURSOR_UP,
            'l'.code,
        )
        val expected = TraceNormalizer.normalize(
            oracle.trace(save, sequence = sequence, prefs = "cursor-mode=full"),
            upstream,
        )
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        (midend.ui as Palisade.Ui).legacyCursor = true
        for (key in sequence) midend.processKey(0, 0, key, TILE)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, TILE, statusAlreadyDrawn = true),
            upstream,
        )
        assertLines("full cursor", expected, actual)
        assertEquals("eight corner lines", 8, actual.lines().count { it.startsWith("line ") })
        assertEquals(
            "no ring polygon",
            0,
            actual.lines().count { it.startsWith("poly ") },
        )
        assertNoBlitter("full cursor", actual)
    }

    @Test
    fun `the warm path emits only the changed work`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "5x5n5", SEED).state

        // A real click's own redraw updated the cache, so the recorded
        // warm frame is only start_draw and end_draw.
        val clicked = listOf(
            OracleClient.Point(24 + 40, 24 + 24, Buttons.LEFT_BUTTON),
            OracleClient.Point(24 + 40, 24 + 24, Buttons.LEFT_RELEASE),
        )
        val expectedClicked = oracle.traceWarm(save, clicked, 292, 292)
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        val actualClicked = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, clicked),
            upstream,
        )
        assertLines(
            "warm click",
            TraceNormalizer.normalize(expectedClicked.trace!!, upstream),
            actualClicked,
        )
        assertEquals(
            "warm click ops",
            listOf("start_draw", "end_draw"),
            actualClicked.lines().drop(7).filter { it.isNotEmpty() },
        )

        // A select on a hidden cursor only reveals it, so the warm frame
        // draws the cursor and nothing else.
        val reveal = listOf(OracleClient.Point(0, 0, Buttons.CURSOR_SELECT))
        val expectedReveal = oracle.traceWarm(save, reveal, 292, 292)
        val revealMidend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        val actualReveal = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(revealMidend, reveal),
            upstream,
        )
        assertLines(
            "warm reveal",
            TraceNormalizer.normalize(expectedReveal.trace!!, upstream),
            actualReveal,
        )
        assertTrue(actualReveal.lines().any { it.startsWith("poly ") })
        assertTrue(actualReveal.lines().contains("update 34 34 32 32"))
    }

    @Test
    fun `sized click traces match the oracle at 48 63 82 and 100`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "5x5n5", SEED).state
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Palisade.computeSize(Palisade.decodeParams("5x5n5"), tile)
            val margin = tile / 2
            val x = margin + 7 * tile / 8
            val y = margin + tile / 2
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                board.width,
                board.height,
                recordTrace = true,
            )
            val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
            midend.processKey(x, y, Buttons.LEFT_BUTTON, tile)
            midend.processKey(x, y, Buttons.LEFT_RELEASE, tile)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream,
            )
            assertLines(
                "tile $tile",
                TraceNormalizer.normalize(expected.trace!!, upstream),
                actual,
            )
            assertEquals(
                "tile $tile move",
                listOf("F0,0,2F1,0,8"),
                midend.serialise().values("MOVE"),
            )
            assertEquals("tile $tile size", board, Palisade.computeSize(Palisade.decodeParams("5x5n5"), tile))
            assertNoBlitter("tile $tile", actual)
        }
    }

    @Test
    fun `a letterboxed click trace matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "5x5n5", SEED).state
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(67, geometry.tileSize)
        val viewX = geometry.originX + 3 * geometry.tileSize
        val viewY = geometry.originY + 4 * geometry.tileSize
        val points = listOf(
            OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
            OracleClient.Point(viewX, viewY, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, points, 411, 702, recordTrace = true)
        val clickMidend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        clickMidend.processKey(
            geometry.toGameX(viewX),
            geometry.toGameY(viewY),
            Buttons.LEFT_BUTTON,
            geometry.tileSize,
        )
        clickMidend.processKey(
            geometry.toGameX(viewX),
            geometry.toGameY(viewY),
            Buttons.LEFT_RELEASE,
            geometry.tileSize,
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(clickMidend, geometry.tileSize),
            upstream,
        )
        assertLines(
            "letterbox",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            actual,
        )
        assertNoBlitter("letterbox", actual)
    }

    @Test
    fun `clear-complete-regions changes only the drawing`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        val solved = oracle.solve(start).state
        // The S answer already uses the OFF layer. The honest board uses
        // MAYBE, so the preference is what turns it grey.
        val aux = requireNotNull(SgtpSave.parse(solved).value("SOLVE"))
        val honest = honestMove(aux, 5, 5)
        val honestSave = withMoves(start, honest)

        val expectedPlain = TraceNormalizer.normalize(
            oracle.trace(honestSave),
            upstream,
        )
        val plain = Midend.fromSave(Palisade, SgtpSave.parse(honestSave))
        val actualPlain = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(plain), upstream)
        assertLines("plain honest", expectedPlain, actualPlain)

        val expectedClear = TraceNormalizer.normalize(
            oracle.trace(honestSave, prefs = "clear-complete-regions=true"),
            upstream,
        )
        val clear = Midend.fromSave(Palisade, SgtpSave.parse(honestSave))
        (clear.ui as Palisade.Ui).clearCompleteRegions = true
        val actualClear = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(clear), upstream)
        assertLines("clear honest", expectedClear, actualClear)
        assertTrue(
            "the preference changes the edge colour",
            actualClear.lines().count { it.startsWith("rect ") && it.endsWith(" 4") } >
                actualPlain.lines().count { it.startsWith("rect ") && it.endsWith(" 4") },
        )
        // The preference is drawing only: the state and the text stay.
        assertEquals(plain.id(), clear.id())
        assertEquals(1, clear.status())
        assertEquals(plain.textFormat(), clear.textFormat())
        assertEquals(
            plain.serialise().serialise(),
            clear.serialise().serialise(),
        )
    }

    @Test
    fun `the S answer draws the OFF layer and no flash`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        val solved = oracle.solve(start).state
        val expected = TraceNormalizer.normalize(oracle.trace(solved), upstream)
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(solved))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("S answer", expected, actual)
        assertTrue(
            "OFF edges are grey",
            actual.lines().any { it.startsWith("rect ") && it.endsWith(" 4") },
        )
        assertFalse(
            "no flash colour",
            actual.lines().any { it.startsWith("rect ") && it.endsWith(" 1") },
        )
    }

    @Test
    fun `a new drawstate at a new tile size is a first draw again`() = OracleClient().use { oracle ->
        val save = oracle.newGame("palisade", "5x5n5", SEED).state
        val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
        val first = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 48), upstream)
        assertTrue(first.lines().contains("rect 0 0 292 292 0"))

        // The same midend at another tile size creates a fresh cache, so
        // the clear and the frame update come back.
        val resized = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 63), upstream)
        assertTrue(resized.lines().contains("rect 0 0 382 382 0"))
        assertTrue(resized.lines().contains("update 0 0 382 382"))
        assertEquals(
            "one clip per cell",
            25,
            resized.lines().count { it.startsWith("clip ") },
        )
        // A forced redraw on the same size also restarts the cache.
        midend.forceRedraw()
        val forced = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 63), upstream)
        assertTrue(forced.lines().contains("rect 0 0 382 382 0"))
    }

    private data class TraceCounts(val rect: Int, val clips: Int, val updates: Int, val texts: Int)

    private fun presets(): List<String> = listOf("5x5n5", "8x6n6", "10x8n8", "15x12n10")

    private fun honestMove(aux: String, w: Int, h: Int): String {
        val bits = aux.substring(1)
        val out = StringBuilder()
        for (i in bits.indices) {
            val border = bits[i].code and 15
            val x = i % w
            val y = i / w
            for (dir in listOf(1, 2)) {
                if (border and (1 shl dir) == 0) continue
                val nx = x + (if (dir == 1) 1 else 0)
                val ny = y + (if (dir == 2) 1 else 0)
                if (nx >= w || ny >= h) continue
                out.append("F$x,$y,${1 shl dir}F$nx,$ny,${1 shl (dir xor 2)}")
            }
        }
        return out.toString()
    }

    private fun withMoves(save: String, vararg moves: String): String {
        val parsed = SgtpSave.parse(save)
        val records = ArrayList<SaveRecord>()
        for (record in parsed.records) {
            if (record.key in setOf("NSTATES", "STATEPOS", "MOVE", "SOLVE", "RESTART")) continue
            records += record
        }
        records += SaveRecord("NSTATES", (1 + moves.size).toString())
        records += SaveRecord("STATEPOS", (1 + moves.size).toString())
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
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
        const val TILE = 48
    }
}
