package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: mosaic.
 *
 * Cold traces are compared with the oracle and with the frozen goldens.
 * The warm path, the cursor ring, the sized traces, and the letterboxed
 * view are compared one by one. `mosaic.c` has no blitter, no line, no
 * circle, and no polygon, so no trace may name one.
 */
class MosaicTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val paramsList = oracle.presets("mosaic").map { it.params }.distinct().sorted()
            assertEquals(
                listOf("10x10", "15x15", "25x25", "3x3", "50x50h0", "5x5"),
                paramsList,
            )

            for (params in paramsList) {
                val midend = Midend.newGame(Mosaic, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("mosaic", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/mosaic/${GenTraces.fileName(params)}.trace")
                assertEquals("frozen trace for '$params'", golden.readText(), actual)

                assertNoBlitter("'$params'", actual)
                assertFalse("'$params' draws a line", actual.lines().any { it.startsWith("line ") })
                assertFalse("'$params' draws a circle", actual.lines().any { it.startsWith("circle ") })
                assertFalse("'$params' draws a polygon", actual.lines().any { it.startsWith("poly ") })
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
                    "one status line for '$params'",
                    1,
                    actual.lines().count { it.startsWith("status ") },
                )
                assertTrue(
                    "the first draw clears the window",
                    actual.lines().contains(
                        "rect 0 0 ${midend.size.width} ${midend.size.height} 0",
                    ),
                )
                assertTrue(
                    "the first draw updates the window",
                    actual.lines().contains(
                        "update 0 0 ${midend.size.width} ${midend.size.height}",
                    ),
                )
            }

            // The palette: only the background is not a literal.
            val palette = paletteOf(KotlinTraceSink.trace(Midend.newGame(Mosaic, "3x3", SEED)))
            assertEquals(
                "800,800,800 580,769,745 0,400,388 78,78,78 925,925,925 392,392,392 1000,0,0 1000,784,784",
                palette,
            )
        }

    @Test
    fun `the 3x3 cold trace draws the margins and every cell`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("mosaic", "3x3", SEED).state
            val trace = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val lines = trace.lines()

            // Cell (0,0) is clue 4.
            assertTrue("the first cell clip", lines.contains("clip 14 14 32 32"))
            assertTrue("the top grid line", lines.contains("rect 14 14 32 1 2"))
            assertTrue("the left grid line", lines.contains("rect 14 14 1 32 2"))
            assertTrue("the unmarked fill", lines.contains("rect 15 15 31 31 1"))
            assertTrue("the clue text", lines.contains("text 31 31 1 19 257 3 \"4\""))

            // The right margin column draws only its left line.
            assertTrue("the margin column clip", lines.contains("clip 110 14 32 32"))
            assertTrue("the margin column line", lines.contains("rect 110 14 1 32 2"))
            assertFalse(
                "the margin column has no top line",
                lines.contains("rect 110 14 32 1 2"),
            )
            // The bottom margin row draws only its top line.
            assertTrue("the margin row clip", lines.contains("clip 14 110 32 32"))
            assertTrue("the margin row line", lines.contains("rect 14 110 32 1 2"))
            assertFalse(
                "the margin row has no left line",
                lines.contains("rect 14 110 1 32 2"),
            )
            // The corner emits only the clip and the update.
            assertTrue("the corner clip", lines.contains("clip 110 110 32 32"))
            assertFalse(
                "the corner draws no fill",
                lines.contains("rect 111 111 31 31 1"),
            )
        }

    @Test
    fun `a cursor trace reveals the ring`() = OracleClient().use { oracle ->
        val save = oracle.newGame("mosaic", "3x3", SEED).state
        val expected = TraceNormalizer.normalize(
            oracle.trace(save, sequence = listOf(Buttons.CURSOR_DOWN)),
            upstream,
        )
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_DOWN, TILE),
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, TILE, statusAlreadyDrawn = true),
            upstream,
        )
        assertLines("cursor trace", expected, actual)
        // The ring is the top and left lines of (0,1), the left line of
        // (1,1), the top line of (0,2), and the 1 px corner of (1,2):
        // five rectangles in COL_CURSOR.
        assertEquals(
            "five cursor rectangles",
            5,
            actual.lines().count { it.startsWith("rect ") && it.endsWith(" 7") },
        )
        assertNoBlitter("cursor trace", actual)
    }

    @Test
    fun `a moved board draws the new state`() = OracleClient().use { oracle ->
        val save = oracle.newGame("mosaic", "3x3", SEED).state
        val points = listOf(
            OracleClient.Point(32, 64, Buttons.LEFT_BUTTON),
            OracleClient.Point(32, 64, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(save, points, width = 128, height = 128, recordTrace = true).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        for (point in points) midend.processKey(point.x, point.y, point.button, TILE)
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("moved board", expected, actual)
        // The clue (0,1) is now marked: black fill and light text. The
        // value is consistent (one marked of six cells against clue 4),
        // so the cell has no solved or error flag.
        assertTrue(
            "the marked clue fill",
            actual.lines().contains("rect 15 47 31 31 3"),
        )
        assertTrue(
            "the marked clue text",
            actual.lines().contains("text 31 63 1 19 257 4 \"4\""),
        )
        assertNoBlitter("moved board", actual)
    }

    @Test
    fun `the warm path emits only the changed cell`() = OracleClient().use { oracle ->
        val save = oracle.newGame("mosaic", "3x3", SEED).state

        // A press and release at (1,0). The press cycles the cell and the
        // release at the same cell is a no-op.
        val changedPoints = listOf(
            OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 32, Buttons.LEFT_RELEASE),
        )
        val changed = oracle.traceWarm(save, changedPoints, width = 128, height = 128)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, changedPoints),
            upstream,
        )
        val expected = TraceNormalizer.normalize(changed.trace!!, upstream)
        assertLines("warm changed cell", expected, actual)
        // The press's own redraw filled the cache, so the recorded warm
        // frame has no operations at all.
        assertEquals(
            "the changed cell was already drawn by the input",
            0,
            actual.lines().count { it.startsWith("clip ") },
        )
        assertNoBlitter("warm changed cell", actual)

        // A press and release in the top band changes nothing.
        val quietPoints = listOf(
            OracleClient.Point(15, 15, Buttons.LEFT_BUTTON),
            OracleClient.Point(15, 15, Buttons.LEFT_RELEASE),
        )
        val quiet = oracle.traceWarm(save, quietPoints, width = 128, height = 128)
        val quietMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        val quietActual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(quietMidend, quietPoints),
            upstream,
        )
        assertLines(
            "warm quiet frame",
            TraceNormalizer.normalize(quiet.trace!!, upstream),
            quietActual,
        )
    }

    @Test
    fun `sized traces match the oracle at every tile size`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mosaic", "10x10", SEED)
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Mosaic.computeSize(Mosaic.decodeParams("10x10"), tile)
            val points = listOf(
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    start.state,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.newGame(Mosaic, "10x10", SEED)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, Buttons.LEFT_BUTTON, tile),
            )
            midend.processKey(0, 0, Buttons.LEFT_RELEASE, tile)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream,
            )
            assertLines("tile $tile", expected, actual)
            assertEquals(
                "tile $tile size",
                board,
                Mosaic.computeSize(Mosaic.decodeParams("10x10"), tile),
            )
            // The text size is ts*3/5 at the top left clue.
            assertTrue(
                "tile $tile text size",
                actual.lines().any {
                    it.startsWith("text ${tile / 2 - 1 + tile / 2} ${tile / 2 - 1 + tile / 2} 1 ") &&
                        it.contains(" ${tile * 3 / 5} ")
                },
            )
            assertNoBlitter("tile $tile", actual)
        }
    }

    @Test
    fun `a letterboxed trace matches the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mosaic", "10x10", SEED)
        val midend = Midend.newGame(Mosaic, "10x10", SEED)
        val geometry = midend.geometryFor(411, 702)
        val tile = geometry.tileSize
        assertEquals("the letterbox tile", 37, tile)
        assertEquals("the letterbox origin x", 2, geometry.originX)
        assertEquals("the letterbox origin y", 147, geometry.originY)

        // A real click on cell (2,3) through the view origin.
        val viewX = geometry.originX + 3 * tile
        val viewY = geometry.originY + 4 * tile
        val points = listOf(
            OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
            OracleClient.Point(viewX, viewY, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                start.state,
                points,
                411,
                702,
                recordTrace = true,
            ).trace!!,
            upstream,
        )
        val clickMidend = Midend.fromSave(Mosaic, SgtpSave.parse(start.state))
        assertEquals(
            KeyOutcome.SomeEffect,
            clickMidend.processKey(
                geometry.toGameX(viewX),
                geometry.toGameY(viewY),
                Buttons.LEFT_BUTTON,
                tile,
            ),
        )
        clickMidend.processKey(
            geometry.toGameX(viewX),
            geometry.toGameY(viewY),
            Buttons.LEFT_RELEASE,
            tile,
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(clickMidend, tile),
            upstream,
        )
        assertLines("letterbox trace", expected, actual)
        assertNoBlitter("letterbox trace", actual)
    }

    @Test
    fun `a raw last state of minus one is invisible`() = OracleClient().use { oracle ->
        // A hand-written drag stores -1, which collides with the
        // drawstate sentinel, so the cell has no draw operations.
        val invisible = historySave("d1,0,0,0,4294967295")
        val expected = TraceNormalizer.normalize(oracle.trace(invisible), upstream)
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(invisible))
        assertEquals(-1, (midend.state as Mosaic.State).valueAt(1, 0))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("invisible cell", expected, actual)
        assertFalse(
            "cell (1,0) has no clip",
            actual.lines().contains("clip 46 14 32 32"),
        )

        // The value 7 is visible: MARKED wins the fill, and SOLVED
        // wins the text color over BLANK.
        val seven = historySave("d1,0,0,0,7")
        val sevenExpected = TraceNormalizer.normalize(oracle.trace(seven), upstream)
        val sevenMidend = Midend.fromSave(Mosaic, SgtpSave.parse(seven))
        val sevenActual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(sevenMidend),
            upstream,
        )
        assertLines("last state 7", sevenExpected, sevenActual)
        assertTrue("the value 7 fills black", sevenActual.lines().contains("rect 47 15 31 31 3"))
    }

    @Test
    fun `sized warm traces match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mosaic", "10x10", SEED)

        // Tile 48: a real press and release on cell (2,2).
        val tile = 48
        val view = 11 * tile
        val viewPoint = OracleClient.Point(3 * tile, 3 * tile, Buttons.LEFT_BUTTON)
        val releasePoint = OracleClient.Point(3 * tile, 3 * tile, Buttons.LEFT_RELEASE)
        val expected = oracle.traceWarm(
            start.state,
            listOf(viewPoint, releasePoint),
            width = view,
            height = view,
        )
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(start.state))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(
                midend,
                listOf(
                    OracleClient.Point(2 * tile + tile, 2 * tile + tile, Buttons.LEFT_BUTTON),
                    OracleClient.Point(2 * tile + tile, 2 * tile + tile, Buttons.LEFT_RELEASE),
                ),
                tile,
            ),
            upstream,
        )
        assertLines("warm tile 48", TraceNormalizer.normalize(expected.trace!!, upstream), actual)
        assertNoBlitter("warm tile 48", actual)

        // The 411x702 letterbox: the oracle subtracts the origin, and
        // the Kotlin points are already in game coordinates.
        val geometry = midend.geometryFor(411, 702)
        val letterbox = oracle.traceWarm(
            start.state,
            listOf(
                OracleClient.Point(geometry.originX + 3 * geometry.tileSize, geometry.originY + 4 * geometry.tileSize, Buttons.LEFT_BUTTON),
                OracleClient.Point(geometry.originX + 3 * geometry.tileSize, geometry.originY + 4 * geometry.tileSize, Buttons.LEFT_RELEASE),
            ),
            411,
            702,
        )
        val letterboxMidend = Midend.fromSave(Mosaic, SgtpSave.parse(start.state))
        val letterboxActual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(
                letterboxMidend,
                listOf(
                    OracleClient.Point(3 * geometry.tileSize, 4 * geometry.tileSize, Buttons.LEFT_BUTTON),
                    OracleClient.Point(3 * geometry.tileSize, 4 * geometry.tileSize, Buttons.LEFT_RELEASE),
                ),
                geometry.tileSize,
            ),
            upstream,
        )
        assertLines(
            "warm letterbox",
            TraceNormalizer.normalize(letterbox.trace!!, upstream),
            letterboxActual,
        )
        assertNoBlitter("warm letterbox", letterboxActual)
    }

    @Test
    fun `clue flags outside the changed block are kept`() = OracleClient().use { oracle ->
        // Mark the four cells of the corner clue 4, which solves it, then
        // change (2,2). That block does not contain (0,0), so the clue
        // keeps its SOLVED bit: the flags are history, not a function of
        // the board.
        val save = historySave("t0,0", "t1,0", "t0,1", "t1,1", "t2,2")
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        val state = midend.state as Mosaic.State
        assertEquals(
            Mosaic.STATE_MARKED or Mosaic.STATE_SOLVED,
            state.cells[0],
        )
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertLines("solved clue history", expected, actual)
        assertTrue(
            "the clue text uses COL_TEXT_SOLVED",
            actual.lines().contains("text 31 31 1 19 257 5 \"4\""),
        )
    }

    private fun historySave(vararg moves: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Mosaic.name),
            SaveRecord("PARAMS", "3x3"),
            SaveRecord("CPARAMS", "3x3"),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", "4b47d"),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private fun paletteOf(trace: JsonValue.Obj): String {
        val palette = (trace.field("palette") as JsonValue.Arr).items
        return palette.joinToString(" ") { entry ->
            (entry as JsonValue.Arr).items.joinToString(",") {
                (it as JsonValue.Num).value.toString()
            }
        }
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
        const val TILE = 32
    }
}
