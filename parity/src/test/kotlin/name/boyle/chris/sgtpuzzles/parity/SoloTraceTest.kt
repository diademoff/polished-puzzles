package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Solo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: solo.
 *
 * The frozen first frames for all sixteen presets, sized and
 * letterboxed views, the tiny viewport, cursor, pencil, and error
 * frames, and the warm path. The game has no blitter; every frame
 * outlines each block with one `thick_poly` at 3.000 and clips every
 * cell.
 */
class SoloTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test(timeout = 300_000)
    fun `the frozen traces match the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in Solo.presets().map { it.params }) {
            val midend = Midend.newGame(Solo, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("solo", params, SEED),
                upstream,
            )
            assertLines("'$params' oracle", expected, actual)

            val golden = File(testdata, "trace/solo/${GenTraces.fileName(params)}.trace")
            assertEquals("'$params' frozen", golden.readText(), actual)

            val state = midend.state as Solo.State
            val lines = actual.lines()
            val cr = state.cr
            assertEquals("'$params' changed_state", 1, lines.count { it.startsWith("changed_state ") })
            assertEquals("'$params' start_draw", 1, lines.count { it == "start_draw" })
            assertEquals("'$params' end_draw", 1, lines.count { it == "end_draw" })
            assertFalse("'$params' status", lines.any { it.startsWith("status ") })
            assertFalse("'$params' blitter_new", lines.any { it.startsWith("blitter_new ") })
            assertFalse("'$params' blitter_free", lines.any { it.startsWith("blitter_free ") })
            assertFalse("'$params' blitter_save", lines.any { it.startsWith("blitter_save ") })
            assertFalse("'$params' blitter_load", lines.any { it.startsWith("blitter_load ") })
            assertEquals("'$params' clips", cr * cr, lines.count { it.startsWith("clip ") })
            assertEquals("'$params' unclips", cr * cr, lines.count { it == "unclip" })
            assertEquals(
                "'$params' updates",
                cr * cr + 2,
                lines.count { it.startsWith("update ") },
            )
            assertEquals(
                "'$params' block outlines",
                cr,
                lines.count { it.startsWith("thick_poly ") },
            )
            assertTrue(
                "'$params' thick polygons are 3.000",
                lines.filter { it.startsWith("thick_poly ") }.all { it.startsWith("thick_poly 3000 ") },
            )
            if (state.killer) {
                assertTrue("'$params' cage lines", lines.count { it.startsWith("line ") } > 0)
                assertEquals(
                    "'$params' killer texts",
                    state.kgrid!!.count { it != 0 },
                    lines.count { it.startsWith("text ") },
                )
            } else {
                assertFalse("'$params' cage lines", lines.any { it.startsWith("line ") })
                assertEquals(
                    "'$params' clue texts",
                    state.grid.count { it != 0 },
                    lines.count { it.startsWith("text ") },
                )
            }
            // The first frame clears the whole window, paints the thick
            // grid rectangle, and updates the whole board twice: once
            // from the game and once from the midend.
            val tile = 48
            val side = cr * tile + 2 * (tile / 2) + 1
            assertTrue(
                "'$params' window clear",
                lines.contains("rect 0 0 $side $side 0"),
            )
            assertEquals(
                "'$params' whole-board updates",
                2,
                lines.count { it == "update 0 0 $side $side" },
            )
        }
    }

    @Test(timeout = 300_000)
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("3x3", "3x3xdb", "9jdb", "3x3ka", "4x4db")) {
            val save = oracle.newGame("solo", params, SEED).state
            for (tile in listOf(32, 48, 63, 82, 100)) {
                val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
                val board = Solo.computeSize(Solo.decodeParams(params), tile)
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
                    "$params tile $tile clear",
                    actual.lines().contains("rect 0 0 ${board.width} ${board.height} 0"),
                )
            }
        }
    }

    @Test(timeout = 300_000)
    fun `a letterboxed and tiny view match the oracle`() = OracleClient().use { oracle ->
        for ((params, view) in listOf(
            "3x3" to (411 to 702),
            "9jdb" to (411 to 702),
            "4x4db" to (411 to 702),
            "3x3" to (200 to 200),
            "3x3xdb" to (200 to 200),
        )) {
            val save = oracle.newGame("solo", params, SEED).state
            val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
            val geometry = midend.geometryFor(view.first, view.second)
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
            assertLines("$params ${view.first}x${view.second}", expected, actual)
            assertEquals(
                "$params ${view.first}x${view.second} size",
                geometry.width,
                Solo.computeSize(Solo.decodeParams(params), geometry.tileSize).width,
            )
        }
    }

    @Test(timeout = 120_000)
    fun `cursor pencil and error frames match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("solo", "3x3", SEED).state
        val state = Midend.fromSave(Solo, SgtpSave.parse(save)).state as Solo.State

        // Cursor frame: one right arrow shows the ring.
        run {
            val expected = oracle.trace(save, sequence = listOf(Buttons.CURSOR_RIGHT))
            val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 48)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("cursor", TraceNormalizer.normalize(expected, upstream), actual)
            assertTrue("cursor highlight", actual.lines().any { it.endsWith(" 6") })
        }

        // Pencil frame: select an empty cell, toggle pencil mode, and
        // add a mark.
        val (ex, ey) = firstEmpty(state)
        val cx = (ex + 1) * 48
        val cy = (ey + 1) * 48
        run {
            val points = listOf(
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '3'.code),
            )
            val expected = oracle.pointsSized(save, points, recordTrace = true).trace!!
            val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
            for (p in points) midend.processKey(p.x, p.y, p.button, 48)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("pencil", TraceNormalizer.normalize(expected, upstream), actual)
            assertTrue("pencil triangle", actual.lines().any { it.startsWith("poly ") })
            assertTrue("pencil text color", actual.lines().any { it.startsWith("text ") && it.contains(" 8 ") })
        }

        // Error frame: put a duplicate value in a row.
        run {
            val row = 0
            var emptyX = -1
            for (x in 0 until 9) if (state.valueAt(x, row) == 0) {
                emptyX = x
                break
            }
            val duplicate = (0 until 9).map { state.valueAt(it, row) }.first { it != 0 }
            val points = listOf(
                OracleClient.Point(
                    (emptyX + 1) * 48,
                    (row + 1) * 48,
                    Buttons.LEFT_BUTTON,
                ),
                OracleClient.Point(0, 0, '0'.code + duplicate),
            )
            val expected = oracle.pointsSized(save, points, recordTrace = true).trace!!
            val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
            for (p in points) midend.processKey(p.x, p.y, p.button, 48)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("error", TraceNormalizer.normalize(expected, upstream), actual)
            assertTrue(
                "error text color",
                actual.lines().any { it.startsWith("text ") && it.contains(" 7 ") },
            )
        }
    }

    @Test(timeout = 120_000)
    fun `the warm path matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("solo", "3x3", SEED).state
        val state = Midend.fromSave(Solo, SgtpSave.parse(save)).state as Solo.State
        val (x, y) = firstEmpty(state)
        val points = listOf(
            OracleClient.Point((x + 1) * 48, (y + 1) * 48, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, '5'.code),
        )
        val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, points),
            upstream,
        )
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, points).trace!!,
            upstream,
        )
        assertLines("warm", expected, actual)
        // The input redraw advanced the cache, so the final settled
        // frame has the unconditional block outlines and no cell redraw:
        // no whole-board clear, no whole-board update, and no clip.
        assertFalse(actual.lines().any { it.startsWith("rect 0 0 ") })
        assertFalse(actual.lines().any { it.startsWith("update 0 0 ") })
        assertEquals("settled warm clips", 0, actual.lines().count { it.startsWith("clip ") })
        assertEquals(
            "warm block outlines",
            (midend.state as Solo.State).cr,
            actual.lines().count { it.startsWith("thick_poly ") },
        )
        assertEquals("warm start", 1, actual.lines().count { it == "start_draw" })
    }

    @Test(timeout = 60_000)
    fun `a killer save with an unclued cage still draws`() {
        // The full solved grid of the no-clue save. C aborts in
        // `check_killer_cage_sum` while it draws this board; the port
        // marks the cage as an error instead (D-024).
        val desc = "3_2_1_4_1_4_3_2_4_3_2_1_2_1_4_3,a______aa_baa_aa,b_4_6_7_7d_6b_5b"
        val text = buildString {
            append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
            append("VERSION :1:1\n")
            append("GAME    :4:Solo\n")
            append("PARAMS  :4:2x2k\n")
            append("CPARAMS :5:2x2ka\n")
            append("DESC    :${desc.length}:$desc\n")
            append("NSTATES :1:1\n")
            append("STATEPOS:1:1\n")
        }
        val midend = Midend.fromSave(Solo, SgtpSave.parse(text))
        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        assertTrue(actual.lines().any { it.startsWith("line ") && it.endsWith(" 7") })
        assertEquals("thick polygons", 4, actual.lines().count { it.startsWith("thick_poly ") })
    }

    private fun firstEmpty(state: Solo.State): Pair<Int, Int> {
        for (y in 0 until state.cr) {
            for (x in 0 until state.cr) {
                if (state.valueAt(x, y) == 0) return x to y
            }
        }
        error("no empty cell")
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) {
            assertEquals("$what line ${i + 1}", e.getOrNull(i), a.getOrNull(i))
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
