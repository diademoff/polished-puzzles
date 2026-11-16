package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: mines.
 *
 * Cold traces are compared with the oracle and with the frozen goldens.
 * A cold trace after a click or an undo is a fresh drawstate that shows
 * the start cross, the hover highlight, the cursor, and the dirty-tile
 * cache is bypassed. The warm path records one redraw on a drawstate
 * that already drew the board and handled the click, so it must contain
 * only `start_draw` and `end_draw`.
 */
class MinesTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val tile = 20

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            for (params in oracle.presets("mines").map { it.params }) {
                val midend = Midend.newGame(Mines, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("mines", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/mines/${GenTraces.fileName(params)}.trace",
                )
                assertEquals("frozen trace for '$params'", golden.readText(), actual)
                assertEquals(
                    "one start_draw for '$params'",
                    1,
                    actual.lines().count { it == "start_draw" },
                )
                assertEquals(
                    "one end_draw for '$params'",
                    1,
                    actual.lines().count { it == "end_draw" },
                )
                assertEquals(
                    "one status for '$params'",
                    1,
                    actual.lines().count { it.startsWith("status ") },
                )
                assertTrue(
                    "the first draw clears the window",
                    actual.lines().contains("rect 0 0 ${size(params)} ${size(params)} 0"),
                )
                assertEquals(
                    "the two frame polygons",
                    2,
                    actual.lines().count { Regex("^poly \\d+ \\d+ 5 ").containsMatchIn(it) },
                )
                assertTrue(
                    "the whole window is updated",
                    actual.lines().contains("update 0 0 ${size(params)} ${size(params)}"),
                )
                assertNoBlitter(actual)
            }
        }

    @Test(timeout = 120_000)
    fun `sized traces after a click match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        for ((params, x, y, tileSize) in listOf(
            Quad("9x9n10", 4, 4, 48),
            Quad("9x9n10", 4, 4, 63),
            Quad("9x9n10", 4, 4, 82),
            Quad("9x9n10", 4, 4, 100),
            Quad("16x16n40", 8, 8, 63),
            Quad("16x16n40", 8, 8, 82),
        )) {
            val save = oracle.newGame("mines", params, SEED).state
            val board = Mines.computeSize(Mines.decodeParams(params), tileSize)
            val points = clickPoints(x, y, tileSize)
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

            val midend = Midend.newGame(Mines, params, SEED)
            applyClick(midend, x, y, tileSize)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tileSize),
                upstream,
            )
            assertLines("$params ${x},$y tile $tileSize", expected, actual)
            assertEquals(
                "one frame in a cold sized trace",
                2,
                actual.lines().count { Regex("^poly \\d+ \\d+ 5 ").containsMatchIn(it) },
            )
            assertNoBlitter(actual)
        }
    }

    @Test(timeout = 120_000)
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("mines", "9x9n10", SEED).state
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        val geometry = midend.geometryFor(411, 702)
        assertEquals(43, geometry.tileSize)
        assertEquals(4, geometry.originX)
        assertEquals(149, geometry.originY)

        val gx = 2 * geometry.tileSize + 8 + geometry.tileSize / 2
        val gy = 5 * geometry.tileSize + 8 + geometry.tileSize / 2
        val points = listOf(
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_BUTTON),
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                points,
                width = 411,
                height = 702,
                recordTrace = true,
            ).trace!!,
            upstream,
        )
        midend.processKey(gx, gy, Buttons.LEFT_BUTTON, geometry.tileSize)
        midend.processKey(gx, gy, Buttons.LEFT_RELEASE, geometry.tileSize)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream,
        )
        assertLines("letterbox", expected, actual)
        assertTrue(midend.id().startsWith("9x9:2,5,m"))
        assertNoBlitter(actual)
    }

    @Test(timeout = 120_000)
    fun `a warm frame after a real click is empty`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("mines", "9x9n10", SEED).state
        for (tileSize in listOf(20, 43, 48, 63, 82, 100)) {
            val board = Mines.computeSize(Mines.decodeParams("9x9n10"), tileSize)
            val points = clickPoints(4, 4, tileSize)
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points, board.width, board.height).trace!!,
                upstream,
            )
            val midend = Midend.newGame(Mines, "9x9n10", SEED)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(
                    midend,
                    listOf(
                        OracleClient.Point(points[0].x, points[0].y, points[0].button),
                        OracleClient.Point(points[1].x, points[1].y, points[1].button),
                    ),
                    tileSize = tileSize,
                ),
                upstream,
            )
            assertLines("warm frame at tile $tileSize", expected, actual)
            assertEquals(
                "a warm frame is empty at tile $tileSize",
                listOf("start_draw", "end_draw"),
                actual.lines().dropWhile { !it.startsWith("palette ") }
                    .drop(1).filter { it.isNotEmpty() },
            )
            assertFalse(
                "a warm frame has no frame polygon",
                actual.lines().any { Regex("^poly \\d+ \\d+ 5 ").containsMatchIn(it) },
            )
        }
    }

    @Test(timeout = 120_000)
    fun `the start cross after undo and after a load matches the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val midend = Midend.newGame(Mines, "9x9n10", SEED)
            applyClick(midend, 4, 4)
            assertTrue(midend.undo())
            val save = midend.serialise().serialise()

            val expectedUndo = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val actualUndo = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(Midend.fromSave(Mines, SgtpSave.parse(save))),
                upstream,
            )
            assertLines("undo cross", expectedUndo, actualUndo)
            assertTrue(
                "the start cross draws two lines",
                actualUndo.lines().count { it.startsWith("line ") && it.endsWith(" 10") } >= 2,
            )
            assertTrue(actualUndo.lines().contains("status \"[0:00] Marked: 0 / 10\""))

            // A save that keeps state 0 at the private description and
            // overlays the public click draws the same cross.
            val loaded = SgtpSave.of(
                listOf(
                    SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                    SaveRecord("VERSION", SgtpSave.VERSION),
                    SaveRecord("GAME", Mines.name),
                    SaveRecord("PARAMS", "9x9n8"),
                    SaveRecord("CPARAMS", "9x9n10"),
                    SaveRecord("DESC", "4,4,m9eed07198e89048f2e4d0"),
                    SaveRecord("PRIVDESC", "m9eed07198e89048f2e4d0"),
                    SaveRecord("UI", "D0"),
                    SaveRecord("TIME", "0"),
                    SaveRecord("NSTATES", "1"),
                    SaveRecord("STATEPOS", "1"),
                ),
            ).serialise()
            val expectedLoad = TraceNormalizer.normalize(oracle.trace(loaded), upstream)
            val actualLoad = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(Midend.fromSave(Mines, SgtpSave.parse(loaded))),
                upstream,
            )
            assertLines("loaded cross", expectedLoad, actualLoad)
            assertEquals(
                "the loaded state is all covered",
                true,
                (Midend.fromSave(Mines, SgtpSave.parse(loaded)).state as Mines.State)
                    .grid.all { it == -2 },
            )
        }

    @Test(timeout = 120_000)
    fun `dead won and solved frames match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

        val dead = Midend.newGame(Mines, "9x9n10", SEED)
        applyClick(dead, 4, 4)
        applyClick(dead, 0, 0)
        assertTrue((dead.state as Mines.State).dead)
        assertTrace(oracle, upstream, dead, "dead")

        val killedByChord = Midend.newGame(Mines, "9x9n10", SEED)
        applyClick(killedByChord, 4, 4)
        applyRightClick(killedByChord, 0, 0)
        applyRightClick(killedByChord, 0, 2)
        applyMiddleClick(killedByChord, 1, 1)
        assertTrue((killedByChord.state as Mines.State).dead)
        assertTrue(killedByChord.solve())
        assertTrue(killedByChord.textFormat().contains('!'))
        assertTrace(oracle, upstream, killedByChord, "post-death solve")

        val won = Midend.newGame(Mines, "4x4n1", "7")
        applyClick(won, 0, 0)
        applyClick(won, 0, 3)
        assertTrue((won.state as Mines.State).won)
        assertTrace(oracle, upstream, won, "won")

        val solved = Midend.newGame(Mines, "9x9n10", SEED)
        applyClick(solved, 4, 4)
        assertTrue(solved.solve())
        assertEquals(0, solved.status())
        assertTrace(oracle, upstream, solved, "solved")
    }

    @Test(timeout = 120_000)
    fun `flags hover cursor and the wrong number match the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

            val flagged = Midend.newGame(Mines, "9x9n10", SEED)
            applyClick(flagged, 4, 4)
            applyRightClick(flagged, 0, 0)
            assertTrace(oracle, upstream, flagged, "flag")
            assertTrue(
                "the flag polygon is drawn",
                normalizedTrace(flagged, upstream).lines().any {
                    it.startsWith("poly 13 13 3 ")
                },
            )

            // A press without a release leaves a hover highlight on a
            // covered cell. The pointer position comes from a sized
            // point, as `trace` replays keys at the origin.
            val hoverSave = oracle.newGame("mines", "9x9n10", SEED).state
            val hoverX = 4 * tile + 8 + tile / 2
            val hoverY = 4 * tile + 8 + tile / 2
            val expectedHover = TraceNormalizer.normalize(
                oracle.pointsSized(
                    hoverSave,
                    listOf(OracleClient.Point(hoverX, hoverY, Buttons.LEFT_BUTTON)),
                    width = 196,
                    height = 196,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val hoverMidend = Midend.newGame(Mines, "9x9n10", SEED)
            hoverMidend.processKey(hoverX, hoverY, Buttons.LEFT_BUTTON, tile)
            val actualHover = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(hoverMidend, tile),
                upstream,
            )
            assertLines("hover", expectedHover, actualHover)
            assertTrue(
                "the hovered tile is -22",
                actualHover.lines().contains("rect 88 88 20 20 1"),
            )

            val cursor = Midend.newGame(Mines, "9x9n10", SEED)
            cursor.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
            val cursorSave = saveWithoutHistory(cursor)
            val expectedCursor = TraceNormalizer.normalize(
                oracle.trace(cursorSave, sequence = listOf(Buttons.CURSOR_SELECT)),
                upstream,
            )
            val cursorMidend = Midend.fromSave(Mines, SgtpSave.parse(cursorSave))
            cursorMidend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
            val actualCursor = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(cursorMidend, tile, statusAlreadyDrawn = true),
                upstream,
            )
            assertLines("cursor", expectedCursor, actualCursor)
            assertTrue(
                "the cursor tile uses the cursor background",
                actualCursor.lines().contains("rect 8 8 20 20 19") ||
                    actualCursor.lines().contains("rect 10 10 16 16 19"),
            )

            // (1,1) is a 2 with three covered neighbours; flag them all.
            val wrong = Midend.newGame(Mines, "9x9n10", SEED)
            applyClick(wrong, 4, 4)
            applyRightClick(wrong, 0, 0)
            applyRightClick(wrong, 0, 1)
            applyRightClick(wrong, 0, 2)
            assertTrace(oracle, upstream, wrong, "wrong number")
            assertTrue(
                "the wrong-number tile uses the wrong-number background",
                normalizedTrace(wrong, upstream).lines().contains("rect 28 28 20 20 18"),
            )
        }

    @Test
    fun `the palette is the C palette and there is no blitter`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val midend = Midend.newGame(Mines, "9x9n10", SEED)
            val trace = KotlinTraceSink.trace(midend)
            val palette = (trace.field("palette") as JsonValue.Arr).items
            assertEquals(20, palette.size)
            assertEquals(listOf(800L, 800L, 800L), channels(palette[0]))
            assertEquals(listOf(760L, 760L, 760L), channels(palette[1]))
            assertEquals(listOf(0L, 0L, 1000L), channels(palette[2]))
            assertEquals(listOf(0L, 500L, 0L), channels(palette[3]))
            assertEquals(listOf(1000L, 0L, 0L), channels(palette[4]))
            assertEquals(listOf(0L, 0L, 500L), channels(palette[5]))
            assertEquals(listOf(500L, 0L, 0L), channels(palette[6]))
            assertEquals(listOf(0L, 500L, 500L), channels(palette[7]))
            assertEquals(listOf(0L, 0L, 0L), channels(palette[8]))
            assertEquals(listOf(500L, 500L, 500L), channels(palette[9]))
            assertEquals(listOf(0L, 0L, 0L), channels(palette[10]))
            assertEquals(listOf(1000L, 0L, 0L), channels(palette[11]))
            assertEquals(listOf(1000L, 0L, 0L), channels(palette[12]))
            assertEquals(listOf(1000L, 0L, 0L), channels(palette[13]))
            assertEquals(listOf(0L, 0L, 0L), channels(palette[14]))
            assertEquals(listOf(0L, 0L, 0L), channels(palette[15]))
            assertEquals(listOf(1000L, 1000L, 1000L), channels(palette[16]))
            assertEquals(listOf(533L, 533L, 533L), channels(palette[17]))
            assertEquals(listOf(1000L, 600L, 600L), channels(palette[18]))
            assertEquals(listOf(1000L, 500L, 500L), channels(palette[19]))

            val text = TraceNormalizer.normalize(trace, upstream)
            assertNoBlitter(text)
            assertFalse(text.lines().any { it.startsWith("thick_line ") })
            assertFalse(text.lines().any { it.startsWith("text_fallback ") })

            // The oracle's palette is the same table.
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("mines", "9x9n10", SEED),
                upstream,
            )
            assertEquals(
                "palette line",
                expected.lines().first { it.startsWith("palette ") },
                text.lines().first { it.startsWith("palette ") },
            )
        }

    private data class Quad(val params: String, val x: Int, val y: Int, val tile: Int)

    private fun size(params: String): Int =
        Mines.computeSize(Mines.decodeParams(params), tile).width

    private fun channels(entry: JsonValue): List<Long> =
        (entry as JsonValue.Arr).items.map { (it as JsonValue.Num).value }

    private fun clickPoints(x: Int, y: Int, tileSize: Int): List<OracleClient.Point> {
        val px = x * tileSize + 8 + tileSize / 2
        val py = y * tileSize + 8 + tileSize / 2
        return listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
    }

    private fun applyClick(midend: Midend, x: Int, y: Int, tileSize: Int = tile) {
        for (point in clickPoints(x, y, tileSize)) {
            midend.processKey(point.x, point.y, point.button, tileSize)
        }
    }

    private fun applyRightClick(midend: Midend, x: Int, y: Int, tileSize: Int = tile) {
        val px = x * tileSize + 8 + tileSize / 2
        val py = y * tileSize + 8 + tileSize / 2
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(px, py, Buttons.RIGHT_BUTTON, tileSize),
        )
        midend.processKey(px, py, Buttons.RIGHT_RELEASE, tileSize)
    }

    private fun applyMiddleClick(midend: Midend, x: Int, y: Int, tileSize: Int = tile) {
        val px = x * tileSize + 8 + tileSize / 2
        val py = y * tileSize + 8 + tileSize / 2
        midend.processKey(px, py, Buttons.MIDDLE_BUTTON, tileSize)
        midend.processKey(px, py, Buttons.MIDDLE_RELEASE, tileSize)
    }

    private fun saveWithoutHistory(midend: Midend): String {
        val save = midend.serialise()
        // Strip the history so the oracle replays the key sequence on a
        // save it can also use as the Kotlin source.
        val history = setOf("MOVE", "SOLVE", "RESTART")
        val records = save.records.filterNot { it.key in history }
            .map {
                when (it.key) {
                    "NSTATES" -> SaveRecord("NSTATES", "1")
                    "STATEPOS" -> SaveRecord("STATEPOS", "1")
                    else -> it
                }
            }
        return SgtpSave.of(records).serialise()
    }

    private fun assertTrace(
        oracle: OracleClient,
        upstream: String,
        midend: Midend,
        what: String,
    ) {
        val save = midend.serialise().serialise()
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(Midend.fromSave(Mines, SgtpSave.parse(save))),
            upstream,
        )
        assertLines(what, expected, actual)
        assertNoBlitter(actual)
    }

    private fun normalizedTrace(midend: Midend, upstream: String): String {
        val save = midend.serialise().serialise()
        return TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(Midend.fromSave(Mines, SgtpSave.parse(save))),
            upstream,
        )
    }

    private fun assertNoBlitter(text: String) {
        assertFalse("no blitter_new", text.lines().any { it.startsWith("blitter_new ") })
        assertFalse("no blitter_free", text.lines().any { it.startsWith("blitter_free ") })
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        assertEquals(what, expected.trimEnd('\n'), actual.trimEnd('\n'))
    }

    private companion object {
        const val SEED = "12345"
    }
}
