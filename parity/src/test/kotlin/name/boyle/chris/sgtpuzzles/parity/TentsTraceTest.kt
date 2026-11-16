package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: tents.
 *
 * The first redraw of a drawstate is compared with the oracle and with
 * the frozen traces. The warm path is a settled frame: the press has
 * already updated the cache during the suppressed part of the
 * sequence, so only the always-redrawn grid lines remain.
 *
 * The frozen files also prove that `started` is never set: every warm
 * frame repeats the 18 grid lines.
 */
class TentsTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        val PRESETS = listOf(
            "8x8de",
            "8x8dt",
            "10x10de",
            "10x10dt",
            "15x15de",
            "15x15dt",
        )

        const val GOLDEN_DESC =
            "ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

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

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in PRESETS) {
                val midend = Midend.newGame(Tents, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream())
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("tents", params, SEED),
                    upstream(),
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/tents/$params.trace").readText()
                assertLines("frozen trace for '$params'", golden, actual)

                val ops = actual.lines()
                assertFalse("'$params' has no status", ops.any { it.startsWith("status ") })
                assertFalse("'$params' has no blitter", ops.any { it.startsWith("blitter_") })
                assertEquals(
                    "'$params' changed_state",
                    1,
                    ops.count { it == "changed_state false false" },
                )
                assertEquals(
                    "'$params' palette",
                    "palette 800,800,800 0,0,0 700,1000,500 600,400,0 0,700,0 800,700,0 " +
                        "1000,0,0 1000,1000,1000 600,0,0",
                    ops.first { it.startsWith("palette ") },
                )
                val cells = when (params) {
                    "8x8de", "8x8dt" -> 64
                    "10x10de", "10x10dt" -> 100
                    else -> 225
                }
                assertEquals(
                    "'$params' grid lines",
                    Math.sqrt(cells.toDouble()).toInt() * 2 + 2,
                    ops.count { it.startsWith("line ") },
                )
            }
        }

    @Test
    fun `the operation shapes match the frozen counts`() = OracleClient().use { oracle ->
        val upstream = upstream()
        for ((params, expectedOps) in listOf(
            "8x8de" to 463,
            "10x10de" to 707,
            "15x15de" to 1522,
        )) {
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.trace(Midend.newGame(Tents, params, SEED)),
                upstream,
            )
            // Seven header lines, then the operations. Kotlin `lines()`
            // keeps the empty element after the final newline.
            assertEquals("'$params' operation count", expectedOps, actual.lines().size - 8)
        }
    }

    @Test
    fun `the tree and the cursor ring use the measured coordinates`() =
        OracleClient().use { oracle ->
            val upstream = upstream()
            val save = oracle.newGame("tents", "8x8de", SEED).state
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(Midend.fromSave(Tents, SgtpSave.parse(save))),
                upstream,
            )
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            assertLines("fresh trace", expected, actual)

            // The tree at (1,0), tile 32: trunk and five leaf circles.
            assertTrue(actual.contains("rect 62 25 5 19 3"))
            assertTrue(actual.contains("circle 64 28 8 4 4"))
            assertTrue(actual.contains("circle 70 24 4 4 4"))
            assertTrue(actual.contains("circle 58 24 4 4 4"))
            assertTrue(actual.contains("circle 72 30 4 4 4"))
            assertTrue(actual.contains("circle 56 30 4 4 4"))

            // A visible cursor at (0,0) is a four-point outline.
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
            val withCursor = TraceNormalizer.normalize(
                oracle.trace(save, sequence = listOf(Buttons.CURSOR_SELECT)),
                upstream,
            )
            val kotlinCursor = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("cursor trace", withCursor, kotlinCursor)
            assertTrue(kotlinCursor.contains("poly -1 1 4 20 20 20 44 44 44 44 20"))
        }

    @Test
    fun `adjacent tents draw two clipped diamonds`() = OracleClient().use { oracle ->
        val save = saveText("8x8de", GOLDEN_DESC, listOf("MOVE" to "T2,0;T2,1"))
        val upstream = upstream()
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(Midend.fromSave(Tents, SgtpSave.parse(save))),
            upstream,
        )
        assertLines("adjacent tents", expected, actual)

        // Each shared edge is drawn twice, once per adjacent cell, and
        // clipped to its half. The edge between (2,0) and (2,1) has
        // its diamond centre at (96,48).
        assertEquals(
            2,
            actual.lines().count { it == "poly 6 1 4 84 48 96 36 108 48 96 60" },
        )
        // Four exclamation marks, two per diamond.
        assertEquals(2, actual.lines().count { it == "rect 94 42 5 7 7" })
        assertEquals(2, actual.lines().count { it == "rect 94 51 5 4 7" })
    }

    @Test
    fun `a warm frame after a press keeps only the grid lines`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tents", "8x8de", SEED).state
            val upstream = upstream()
            val point = OracleClient.Point(48, 48, Buttons.LEFT_BUTTON)
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, listOf(point)).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, listOf(point)),
                upstream,
            )
            assertLines("warm press", expected, actual)

            val ops = actual.lines().drop(7).filter { it.isNotEmpty() }
            assertEquals("one start draw", 1, ops.count { it == "start_draw" })
            assertEquals("one end draw", 1, ops.count { it == "end_draw" })
            assertEquals("18 grid lines", 18, ops.count { it.startsWith("line ") })
            assertEquals(
                listOf("start_draw") + ops.filter { it.startsWith("line ") } + "end_draw",
                ops,
            )
            assertEquals(1, midend.stateCount)
            assertTrue(midend.serialise().values("MOVE").isEmpty())
        }

    @Test
    fun `a warm frame after a drag and release is the same shape`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tents", "8x8de", SEED).state
            val upstream = upstream()
            val points = listOf(
                OracleClient.Point(48, 48, Buttons.LEFT_BUTTON),
                OracleClient.Point(80, 48, Buttons.LEFT_DRAG),
                OracleClient.Point(80, 48, Buttons.LEFT_RELEASE),
            )
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, points),
                upstream,
            )
            assertLines("warm drag", expected, actual)
            assertEquals(
                18,
                actual.lines().count { it.startsWith("line ") },
            )
            assertEquals(2, midend.stateCount)
            assertEquals(listOf("N2,1"), midend.serialise().values("MOVE"))
        }

    @Test
    fun `sized cold traces match at odd and even tiles`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tents", "8x8de", SEED).state
        val upstream = upstream()
        for (view in listOf(480, 629, 820, 1000)) {
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            val geometry = midend.geometryFor(view, view)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = view,
                    height = view,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                upstream,
            )
            assertLines("cold trace at $view", expected, actual)
            assertEquals(
                "size at $view",
                "size ${geometry.width} ${geometry.height}",
                actual.lines().first { it.startsWith("size ") },
            )
        }
    }

    @Test
    fun `a letterboxed cold trace and a warm press match`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tents", "8x8de", SEED).state
            val upstream = upstream()
            val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            assertEquals(41, geometry.tileSize)
            assertEquals(1, geometry.originX)
            assertEquals(146, geometry.originY)

            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = 411,
                    height = 702,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                upstream,
            )
            assertLines("letterbox cold", expected, actual)

            // Press the centre of blank cell (5,5) in view coordinates.
            val viewX = geometry.originX + 5 * geometry.tileSize + geometry.tileSize / 2
            val viewY = geometry.originY + 5 * geometry.tileSize + geometry.tileSize / 2
            val point = OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)
            val warmExpected = TraceNormalizer.normalize(
                oracle.traceWarm(save, listOf(point), 411, 702).trace!!,
                upstream,
            )
            val warmMidend = Midend.fromSave(Tents, SgtpSave.parse(save))
            val gamePoint = OracleClient.Point(
                viewX - geometry.originX,
                viewY - geometry.originY,
                Buttons.LEFT_BUTTON,
            )
            val warmActual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(warmMidend, listOf(gamePoint), geometry.tileSize),
                upstream,
            )
            assertLines("letterbox warm press", warmExpected, warmActual)
        }

    @Test
    fun `a warm press at four tiles is only the grid lines`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tents", "8x8de", SEED).state
            val upstream = upstream()
            for ((view, tile) in listOf(
                480 to 48,
                629 to 63,
                820 to 82,
                1000 to 100,
            )) {
                // The board fills the view, so the origin is zero.
                val point = OracleClient.Point(5 * tile + tile, 5 * tile + tile, Buttons.LEFT_BUTTON)
                val expected = TraceNormalizer.normalize(
                    oracle.traceWarm(save, listOf(point), view, view).trace!!,
                    upstream,
                )
                val midend = Midend.fromSave(Tents, SgtpSave.parse(save))
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.warmTrace(midend, listOf(point), tile),
                    upstream,
                )
                assertLines("warm press at $view", expected, actual)
                assertEquals(
                    "18 grid lines at $view",
                    18,
                    actual.lines().count { it.startsWith("line ") },
                )
                assertEquals(1, midend.stateCount)
            }
        }

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>>,
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Tents.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
