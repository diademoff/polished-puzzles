package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: netslide.
 *
 * The drawing order, coordinates, palette roles, tile cache, barrier
 * loop, cursor arrows, and status line are compared with the oracle.
 *
 * Every cold trace comes from `traceNew` or from a loaded state, so it
 * is a settled frame with no animation. A post-move trace of a live
 * midend would be the first animation frame, which the port does not
 * model; the moved states below are loaded and drawn as settled.
 */
class NetslideTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("netslide").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Netslide, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("netslide", params, SEED),
                upstream,
            )
            assertLines("trace for '$params'", expected, actual)

            val golden = File(testdata, "trace/netslide/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
            assertNoBlitter(actual)
        }
    }

    @Test
    fun `cold traces of loaded states match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        for (params in Netslide.presets().map { it.params }.distinct().sorted()) {
            val save = oracle.newGame("netslide", params, SEED).state
            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            assertLines("loaded trace for '$params'", expected, actual)
            assertNoBlitter(actual)

            // The first frame is a full draw: one clear, one status, and
            // one whole-window update.
            assertEquals(
                "one status line for '$params'",
                1,
                actual.lines().count { it.startsWith("status ") },
            )
            assertTrue("whole-window update for '$params'", actual.contains("update 0 0 "))
        }
    }

    @Test
    fun `cold traces of moved states match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val base = "21c4d5963"
        val moveLists = listOf(
            listOf("R0,1"),
            listOf("R0,-1"),
            // A step of two scrambles the line; a full cycle is an
            // identity. The settled drawing shows the exact board.
            listOf("R0,2"),
            listOf("R0,-2"),
            listOf("R0,3"),
            listOf("C0,1"),
            listOf("C0,-1"),
            listOf("C0,2"),
            listOf("C0,3"),
            listOf("R0,1", "C2,-1"),
            listOf("R1,2", "C1,-1", "R2,1"),
        )

        for (moves in moveLists) {
            val save = saveText("3x3", base, moves)
            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            assertLines("moved trace $moves", expected, actual)
            assertNoBlitter(actual)
        }
    }

    @Test
    fun `cursor traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("3x3", "21c4d5963")
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_LEFT),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_UP,
            ),
            // A click hides the cursor, so the next frame has no arrow.
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.LEFT_BUTTON),
        )

        for (sequence in sequences) {
            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            for (key in sequence) {
                val expectedOutcome = if (key == Buttons.LEFT_BUTTON) {
                    KeyOutcome.Unused
                } else {
                    KeyOutcome.SomeEffect
                }
                assertEquals(
                    "key $key in $sequence",
                    expectedOutcome,
                    midend.processKey(0, 0, key, 48),
                )
            }
            val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )
            // The oracle already drew the same status during the key, so
            // its final frame has no status line. The Kotlin sink has one.
            val expectedLines = expected.lines().filterNot { it.startsWith("status ") }
            val actualLines = actual.lines().filterNot { it.startsWith("status ") }
            assertLines(
                "cursor trace $sequence",
                expectedLines.joinToString("\n"),
                actualLines.joinToString("\n"),
            )
            assertEquals(
                "status for $sequence",
                "status " + Json.quote(midend.statusText()),
                actual.lines().single { it.startsWith("status ") },
            )
            assertNoBlitter(actual)
        }

        // The visible cursor adds exactly one arrow polygon in the
        // powered colour, and its update is the cursor cell.
        val right = TraceNormalizer.normalize(
            oracle.trace(save, sequence = listOf(Buttons.CURSOR_RIGHT)),
            upstream,
        )
        assertEquals(
            1,
            right.lines().count { it.startsWith("poly 5 ") },
        )
        assertTrue(right.contains("update 144 0 48 48"))
    }

    @Test
    fun `warm frames after a cursor key match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("3x3", "21c4d5963")
        val points = listOf(OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT))

        val expected = oracle.traceWarm(save, points)
        val actual = KotlinTraceSink.warmTrace(
            Midend.fromSave(Netslide, SgtpSave.parse(save)),
            points,
        )
        val expectedText = TraceNormalizer.normalize(requireNotNull(expected.trace), upstream)
        val actualText = TraceNormalizer.normalize(actual, upstream)
        assertLines("warm cursor frame", expectedText, actualText)

        // The spec's 97 operations: no tile, no status, no whole-window
        // update, and the per-cell barrier loop on every frame.
        val ops = (requireNotNull(expected.trace).field("ops") as JsonValue.Arr).items
        assertEquals("warm cursor frame size", 97, ops.size)
        assertFalse("no wire line in a warm frame", expectedText.lines().any { it.startsWith("line ") })
        assertFalse(
            "no tile border rect in a warm frame",
            expectedText.contains("rect 48 48 49 49 2"),
        )
        assertFalse("no status in a warm frame", expectedText.lines().any { it.startsWith("status ") })
        assertFalse("no clear in a warm frame", expectedText.contains("rect 0 0 241 241 0"))
        assertTrue("the barrier loop is on every frame", expectedText.contains("rect 48 48 2 1 3"))
        assertNoBlitter(actualText)
    }

    @Test
    fun `warm frames after an inert click match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("3x3", "21c4d5963")
        val points = listOf(OracleClient.Point(72, 72, Buttons.LEFT_BUTTON))

        val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
        val actual = KotlinTraceSink.warmTrace(midend, points)
        val expected = oracle.traceWarm(save, points)
        assertLines(
            "warm inert frame",
            TraceNormalizer.normalize(requireNotNull(expected.trace), upstream),
            TraceNormalizer.normalize(actual, upstream),
        )

        val ops = (requireNotNull(expected.trace).field("ops") as JsonValue.Arr).items
        assertEquals("warm inert frame size", 97, ops.size)
        assertEquals("the inert click adds no history", 1, midend.stateCount)
    }

    @Test
    fun `a warm frame of a moved save redraws the last moved line`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            // The last record is a MOVE, so the loaded state carries the
            // last-moved row and the tile cache rule applies to it. No
            // input follows the cold frame.
            val save = saveText("3x3", "21c4d5963", listOf("R0,1"))
            val points = emptyList<OracleClient.Point>()

            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, points),
                upstream,
            )
            val expected = oracle.traceWarm(save, points)
            val expectedText = TraceNormalizer.normalize(
                requireNotNull(expected.trace),
                upstream,
            )
            assertLines("warm moved frame", expectedText, actual)

            // Row 0 is the last-moved line, so every cell in it is drawn
            // again although the cache holds its byte. The three 49x49
            // border rects in colour 2 are the tile borders; no other
            // tile is redrawn.
            val tileBorders = expectedText.lines().filter { it.matches(TILE_BORDER_RECT) }
            assertEquals(
                listOf(
                    "rect 48 48 49 49 2",
                    "rect 96 48 49 49 2",
                    "rect 144 48 49 49 2",
                ),
                tileBorders,
            )
            val ops = (requireNotNull(expected.trace).field("ops") as JsonValue.Arr).items
            assertEquals("warm moved frame size", 136, ops.size)
            assertEquals("the moved save has two states", 2, midend.stateCount)
            assertFalse(
                "no status in a warm frame",
                expectedText.lines().any { it.startsWith("status ") },
            )
            assertFalse("no clear in a warm frame", expectedText.contains("rect 0 0 241 241 0"))
            assertNoBlitter(actual)
        }

    @Test
    fun `sized cold traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        for (case in listOf(
            Sized("3x3", 241, 241, 48, 0, 0),
            Sized("3x3", 200, 200, 39, 2, 2),
            Sized("3x3", 411, 702, 82, 0, 145),
            Sized("4x4", 411, 702, 68, 1, 146),
            Sized("5x5", 411, 702, 58, 2, 147),
            Sized("4x7", 200, 200, 22, 33, 0),
        )) {
            val save = saveText(case.params, descFor(case.params))
            val probe = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val geometry = probe.geometryFor(case.viewWidth, case.viewHeight)
            assertEquals("tile for ${case.params} at ${case.viewWidth}", case.tile, geometry.tileSize)
            assertEquals("origin x", case.originX, geometry.originX)
            assertEquals("origin y", case.originY, geometry.originY)

            // A view click in the top-left corner is inert.
            val expected = oracle.pointsSized(
                save,
                listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
                case.viewWidth,
                case.viewHeight,
                recordTrace = true,
            )
            val actual = KotlinTraceSink.stateTrace(probe, case.tile)
            assertLines(
                "sized trace ${case.params} at ${case.viewWidth}",
                TraceNormalizer.normalize(requireNotNull(expected.trace), upstream),
                TraceNormalizer.normalize(actual, upstream),
            )
        }
    }

    @Test
    fun `a real slide changes the board and an inert click does not`() = OracleClient().use { oracle ->
        val save = saveText("3x3", "21c4d5963")
        val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
        val before = midend.state as Netslide.State
        val tilesBefore = before.tiles.toList()
        val countBefore = before.moveCount

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(24, 72, Buttons.LEFT_BUTTON, 48),
        )
        val after = midend.state as Netslide.State
        assertNotEquals("the slide changed the board", tilesBefore, after.tiles.toList())
        assertEquals("the slide counted a move", countBefore + 1, after.moveCount)
        assertEquals(2, midend.stateCount)

        val inert = Midend.fromSave(Netslide, SgtpSave.parse(save))
        val inertBefore = inert.state as Netslide.State
        val inertTiles = inertBefore.tiles.toList()
        assertEquals(
            KeyOutcome.Unused,
            inert.processKey(72, 72, Buttons.LEFT_BUTTON, 48),
        )
        val inertAfter = inert.state as Netslide.State
        assertEquals("the inert click changed no tile", inertTiles, inertAfter.tiles.toList())
        assertEquals("the inert click counted no move", 0, inertAfter.moveCount)
        assertEquals("the inert click added no history", 1, inert.stateCount)
    }

    private data class Sized(
        val params: String,
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private fun descFor(params: String): String {
        val area = params.substringBefore('x').toInt() * params.substringAfter('x').substringBefore('w')
            .substringBefore('b').substringBefore('m').toInt()
        return "0".repeat(area)
    }

    private fun assertNoBlitter(trace: String) {
        assertFalse(
            "a netslide trace must have no blitter operation",
            trace.lines().any { it.startsWith("blitter_") },
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

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
        solve: String? = null,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Netslide"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size + (if (solve != null) 1 else 0)).toString()),
            SaveRecord("STATEPOS", (1 + moves.size + (if (solve != null) 1 else 0)).toString()),
        )
        if (solve != null) records += SaveRecord("SOLVE", solve)
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"

        /**
         * A tile border: `draw_tile` draws `rect(bx, by, T+TB, T+TB,
         * COL_BORDER)`, which is 49x49 in colour 2 at tile 48.
         */
        val TILE_BORDER_RECT = Regex("rect \\d+ \\d+ 49 49 2")
    }
}
