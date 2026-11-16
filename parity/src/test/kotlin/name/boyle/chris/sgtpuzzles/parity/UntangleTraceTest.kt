package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: untangle.
 *
 * Cold traces from `traceNew`, from a loaded save, from an input frame,
 * at four tile sizes, and through the 411x702 letterbox. The palette
 * has colour 0 darker than the board, so the midend's clear and the
 * game's rect differ. There is no blitter, clip, polygon, or status
 * line.
 *
 * Untangle draws a whole frame whenever its cache guard fails, so a
 * move and a cursor change are full-board frames. The warm path is
 * empty: the input's own redraw already updated the cache.
 */
class UntangleTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in listOf("6", "10", "15", "20", "25")) {
                val midend = Midend.newGame(Untangle, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("untangle", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/untangle/$params.trace")
                assertEquals("frozen trace for '$params'", golden.readText(), actual)

                val lines = actual.lines()
                val n = params.toInt()
                val cells = coordLimit(n)
                assertEquals("one start_draw for '$params'", 1, lines.count { it == "start_draw" })
                assertEquals("one end_draw for '$params'", 1, lines.count { it == "end_draw" })
                assertEquals("two rects for '$params'", 2, lines.count { it.startsWith("rect ") })
                assertEquals(
                    "one board rect for '$params'",
                    1,
                    lines.count { it.startsWith("rect ") && it.endsWith(" 1") },
                )
                assertEquals(
                    "one clear for '$params'",
                    1,
                    lines.count { it.startsWith("rect ") && it.endsWith(" 0") },
                )
                assertEquals("two updates for '$params'", 2, lines.count { it.startsWith("update ") })
                assertEquals(
                    "one circle per point for '$params'",
                    n,
                    lines.count { it.startsWith("circle ") },
                )
                assertEquals(
                    "one line per edge for '$params'",
                    midend.desc.count { it == ',' } + 1,
                    lines.count { it.startsWith("line ") },
                )
                assertNoUnusedOps("'$params'", lines)

                val palette = paletteOf(KotlinTraceSink.trace(midend))
                assertEquals(
                    "palette for '$params'",
                    "633,633,633 800,800,800 0,0,0 1000,0,0 0,0,0 0,0,1000 " +
                        "1000,1000,1000 500,500,500 1000,0,0 500,500,500 1000,1000,1000",
                    palette,
                )
                assertEquals(
                    "the board is square at '$params'",
                    cells * 64,
                    midend.size.width,
                )
            }
        }

    @Test
    fun `a cold trace from a loaded save matches the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            assertLines("loaded save trace", expected, actual)
        }

    @Test
    fun `the cursor frame highlights the point in colour seven`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = listOf(Buttons.CURSOR_DOWN)),
                upstream,
            )
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_DOWN, 64),
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("cursor trace", expected, actual)
            assertTrue(
                "one cursor point",
                actual.lines().any { it.startsWith("circle ") && it.endsWith(" 7 4") },
            )
            assertNoUnusedOps("cursor trace", actual.lines())
        }

    @Test
    fun `the vertex style and crossed edges preferences change the frame`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state

            // Numbers replace the circles: a background disc, then text.
            val numberExpected = TraceNormalizer.normalize(
                oracle.trace(save, prefs = "vertex-style=number"),
                upstream,
            )
            val numberMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            (numberMidend.ui as Untangle.Ui).vertexNumbers = true
            val numberActual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(numberMidend),
                upstream,
            )
            assertLines("vertex numbers", numberExpected, numberActual)
            val numberLines = numberActual.lines()
            assertTrue(
                "a background disc",
                numberLines.any { it.startsWith("circle ") && it.endsWith(" 1 1") },
            )
            assertTrue(
                "a number",
                numberLines.any { it.startsWith("text ") && it.contains(" 1 18 257 5 ") },
            )
            assertEquals(
                "one text per point",
                10,
                numberLines.count { it.startsWith("text ") },
            )

            // Crossed edges use colour 3.
            val crossedExpected = TraceNormalizer.normalize(
                oracle.trace(save, prefs = "show-crossed-edges=true"),
                upstream,
            )
            val crossedMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            (crossedMidend.ui as Untangle.Ui).showCrossedEdges = true
            val crossedActual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(crossedMidend),
                upstream,
            )
            assertLines("crossed edges", crossedExpected, crossedActual)
            assertTrue(
                "a crossed edge",
                crossedActual.lines().any { it.startsWith("line ") && it.endsWith(" 3") },
            )
            assertTrue(
                "a plain edge too",
                crossedActual.lines().any { it.startsWith("line ") && it.endsWith(" 2") },
            )
        }

    @Test
    fun `the press frame draws the dragged point and its neighbours`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val (px, py) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 0)
            val points = listOf(OracleClient.Point(px, py, Buttons.LEFT_BUTTON))
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = 320,
                    height = 320,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(px, py, Buttons.LEFT_BUTTON, 64),
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("press frame", expected, actual)
            assertTrue(
                "the dragged point",
                actual.lines().any { it.startsWith("circle ") && it.endsWith(" 6 4") },
            )
            assertTrue(
                "a neighbour point",
                actual.lines().any { it.startsWith("circle ") && it.endsWith(" 8 4") },
            )
        }

    @Test
    fun `the release frame draws the settled board`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val (px, py) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 0)
            val points = listOf(
                OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
                OracleClient.Point(px + 30, py + 20, Buttons.LEFT_DRAG),
                OracleClient.Point(px + 60, py + 40, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    points,
                    width = 320,
                    height = 320,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (p in points) midend.processKey(p.x, p.y, p.button, 64)
            assertEquals("one move", 2, midend.stateCount)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("release frame", expected, actual)
            assertEquals(
                "no drag point in a settled frame",
                0,
                actual.lines().count { it.startsWith("circle ") && it.endsWith(" 6 4") },
            )
        }

    @Test
    fun `sized traces match the oracle at every tile size`() =
        OracleClient().use { oracle ->
            val params = "10"
            val save = oracle.newGame("untangle", params, SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Untangle.computeSize(Untangle.decodeParams(params), tile)
                val points = listOf(
                    OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                    OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
                )
                val expected = TraceNormalizer.normalize(
                    oracle.pointsSized(
                        save,
                        points,
                        width = board.width,
                        height = board.height,
                        recordTrace = true,
                    ).trace!!,
                    upstream,
                )
                val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
                for (p in points) {
                    assertEquals(
                        "the corner is inert at tile $tile",
                        KeyOutcome.NoEffect,
                        midend.processKey(p.x, p.y, p.button, tile),
                    )
                }
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tile),
                    upstream,
                )
                assertLines("tile $tile", expected, actual)
                assertEquals(
                    "tile $tile size",
                    board.width,
                    midend.game.computeSize(midend.params, tile).width,
                )
                assertTrue(
                    "tile $tile line",
                    actual.lines().any { it.startsWith("line ") },
                )
                assertEquals(
                    "tile $tile circles",
                    10,
                    actual.lines().count { it.startsWith("circle ") },
                )
                assertNoUnusedOps("tile $tile", actual.lines())
            }
        }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val params = "10"
        val save = oracle.newGame("untangle", params, SEED).state
        val layout = oracle.layout(save, 411, 702)
        val midend = Midend.newGame(Untangle, params, SEED)
        val geometry = midend.geometryFor(411, 702)
        assertEquals("tile", layout.width / coordLimit(10), geometry.tileSize)
        assertEquals("board width", layout.width, geometry.width)
        assertEquals("board height", layout.height, geometry.height)
        assertEquals("origin x", (411 - layout.width) / 2, geometry.originX)
        assertEquals("origin y", (702 - layout.height) / 2, geometry.originY)
        assertTrue("a letterbox", geometry.originX > 0 || geometry.originY > 0)

        val (px, py) = pixelOf(midend.state, 0, geometry.tileSize)
        val viewX = geometry.originX + px
        val viewY = geometry.originY + py
        val points = listOf(
            OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
            OracleClient.Point(viewX, viewY, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(save, points, 411, 702, recordTrace = true).trace!!,
            upstream,
        )
        val clickMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        for (p in points) {
            assertEquals(
                "the click is a move",
                KeyOutcome.SomeEffect,
                clickMidend.processKey(
                    p.x - geometry.originX,
                    p.y - geometry.originY,
                    p.button,
                    geometry.tileSize,
                ),
            )
        }
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(clickMidend, geometry.tileSize),
            upstream,
        )
        assertLines("letterbox trace", expected, actual)
    }

    @Test
    fun `the warm settled frame is empty`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "10", SEED).state
        val (px, py) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 0)
        val points = listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.traceWarm(save, points, width = 320, height = 320)
        val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        val actual = KotlinTraceSink.warmTrace(midend, points)
        assertLines(
            "warm settled frame",
            TraceNormalizer.normalize(requireNotNull(expected.trace), upstream),
            TraceNormalizer.normalize(actual, upstream),
        )
        val ops = opsOf(actual)
        assertEquals("only the draw pair", 2, ops.size)
        assertEquals(listOf("start_draw", "end_draw"), ops)

        // The positive control: the input's own frames are not empty.
        val pressMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        val press = KotlinTraceSink.warmFrame(pressMidend, points.take(1))
        assertTrue("the press frame draws", opsOf(press).size > 2)
        assertTrue(
            "the dragged point",
            TraceNormalizer.normalize(press, upstream).lines()
                .any { it.startsWith("circle ") && it.endsWith(" 6 4") },
        )
        val releaseMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        val release = KotlinTraceSink.warmFrame(releaseMidend, points)
        assertTrue("the release frame draws", opsOf(release).size > 2)
        assertEquals("the release is one move", 2, releaseMidend.stateCount)

        // A second click cycle settles to the empty frame again.
        val second = oracle.traceWarm(save, points, width = 320, height = 320)
        val secondMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
        val secondActual = KotlinTraceSink.warmTrace(secondMidend, points)
        assertEquals(listOf("start_draw", "end_draw"), opsOf(secondActual))
        assertEquals(
            "the oracle also settles",
            opsOf(requireNotNull(second.trace)),
            opsOf(secondActual),
        )
    }

    private fun pixelOf(state: name.boyle.chris.sgtpuzzles.engine.Game.State, index: Int): Pair<Int, Int> {
        val point = (state as Untangle.State).point(index)
        return (point.x * 64 / point.d).toInt() to (point.y * 64 / point.d).toInt()
    }

    private fun pixelOf(
        state: name.boyle.chris.sgtpuzzles.engine.Game.State,
        index: Int,
        tileSize: Int,
    ): Pair<Int, Int> {
        val point = (state as Untangle.State).point(index)
        return (point.x * tileSize / point.d).toInt() to
            (point.y * tileSize / point.d).toInt()
    }

    private fun opsOf(trace: JsonValue.Obj): List<String> =
        (trace.field("ops") as JsonValue.Arr).items.map { (it as JsonValue.Obj).string("op") }

    private fun paletteOf(trace: JsonValue.Obj): String =
        (trace.field("palette") as JsonValue.Arr).items.joinToString(" ") { entry ->
            (entry as JsonValue.Arr).items.joinToString(",") {
                (it as JsonValue.Num).value.toString()
            }
        }

    private fun assertNoUnusedOps(what: String, lines: List<String>) {
        for (prefix in listOf("status ", "clip ", "unclip", "poly ", "thick_", "blitter_")) {
            assertFalse(
                "$what must not draw $prefix",
                lines.any { it.startsWith(prefix) },
            )
        }
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

    private fun coordLimit(n: Int): Int {
        var d = n.toLong() * 3
        var a = 0L
        var b = 1L shl 30
        do {
            a = a shr 1
            val di = 2 * a + b
            if (di <= d) {
                d -= di
                a += b
            }
            b = b shr 2
        } while (b != 0L)
        return a.toInt()
    }

    private companion object {
        const val SEED = "12345"
    }
}
