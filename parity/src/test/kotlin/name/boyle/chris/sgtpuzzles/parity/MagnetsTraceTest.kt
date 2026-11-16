package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: magnets.
 *
 * The fresh redraw of every preset is compared with the oracle and with
 * the frozen traces, including the two corner symbols and the two
 * whole-window updates of the first draw. Sized cold traces run at
 * tiles 48, 63, 82, and 100 and in a letterboxed 411x702 viewport. The
 * warm path is compared after a real click and a clue toggle, and a
 * Kotlin-only settled frame pins the exact changed tiles and the one
 * shaded clue.
 */
class MagnetsTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        val PRESETS = listOf(
            "6x5de", "6x5dt", "6x5dtS",
            "8x7de", "8x7dt", "8x7dtS",
            "10x9dt", "10x9dtS",
        )
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
                val midend = Midend.newGame(Magnets, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream())
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("magnets", params, SEED),
                    upstream(),
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/magnets/$params.trace").readText()
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
                    "palette 800,800,800 967,967,967 633,633,633 0,0,0 1000,0,0 " +
                        "900,900,900 533,533,533 100,600,100 0,0,0 800,0,0 200,200,1000",
                    ops.first { it.startsWith("palette ") },
                )
                // The first draw updates the whole window twice: once
                // from the game and once from the midend.
                val size = Magnets.computeSize(Magnets.decodeParams(params), TILE)
                assertEquals(
                    "'$params' whole-window updates",
                    2,
                    ops.count { it == "update 0 0 ${size.width} ${size.height}" },
                )
                assertTrue("'$params' corner plus", ops.contains("rect 12 18 17 5 3"))
                val w = size.width
                val h = size.height
                assertTrue("'$params' corner minus", ops.contains("rect ${w - 28} ${h - 22} 17 5 3"))
            }
        }

    @Test
    fun `sized cold traces match at four tiles`() = OracleClient().use { oracle ->
        val save = oracle.newGame("magnets", "6x5de", SEED).state
        val upstream = upstream()
        for (tile in listOf(48, 63, 82, 100)) {
            val params = Magnets.decodeParams("6x5de")
            val size = Magnets.computeSize(params, tile)
            val midend = Midend.fromSave(Magnets, SgtpSave.parse(save))
            val geometry = midend.geometryFor(size.width, size.height)
            assertEquals("tile $tile", tile, geometry.tileSize)
            assertEquals("origin x at $tile", 0, geometry.originX)
            assertEquals("origin y at $tile", 0, geometry.originY)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = size.width,
                    height = size.height,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream,
            )
            assertLines("cold trace at tile $tile", expected, actual)
            assertEquals(
                "size at tile $tile",
                "size ${size.width} ${size.height}",
                actual.lines().first { it.startsWith("size ") },
            )
        }
    }

    @Test
    fun `a letterboxed cold trace matches`() = OracleClient().use { oracle ->
        val save = oracle.newGame("magnets", "6x5dt", SEED).state
        val upstream = upstream()
        val midend = Midend.fromSave(Magnets, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(49, geometry.tileSize)
        assertEquals(3, geometry.originX)
        assertEquals(173, geometry.originY)

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
    }

    @Test
    fun `a warm frame after a click matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("magnets", "6x5dt", SEED).state
        val upstream = upstream()
        val point = OracleClient.Point(52, 52, Buttons.LEFT_BUTTON)
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, listOf(point)).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Magnets, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, listOf(point)),
            upstream,
        )
        assertLines("warm click", expected, actual)
        assertEquals(
            listOf("start_draw", "end_draw"),
            actual.lines().drop(7).filter { it.isNotEmpty() },
        )
        assertEquals(2, midend.stateCount)

        // A clue toggle has the same settled shape.
        val cluePoint = OracleClient.Point(52, 20, Buttons.LEFT_BUTTON)
        val clueExpected = TraceNormalizer.normalize(
            oracle.traceWarm(save, listOf(cluePoint)).trace!!,
            upstream,
        )
        val clueMidend = Midend.fromSave(Magnets, SgtpSave.parse(save))
        val clueActual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(clueMidend, listOf(cluePoint)),
            upstream,
        )
        assertLines("warm clue", clueExpected, clueActual)
        assertEquals(
            listOf("start_draw", "end_draw"),
            clueActual.lines().drop(7).filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `a settled frame draws only the changed cells and the shaded clue`() {
        // A magnet click changes the clicked cell and its partner. Draw
        // the initial board into the cache, apply the move without a
        // draw, then record one frame. This is the warm path the oracle
        // cannot expose because its operation redraws after the input.
        val midend = Midend.newGame(Magnets, "6x5dt", SEED)
        val sink = KotlinTraceSink()
        sink.recording = false
        midend.recordRedraw(sink)
        midend.processKey(52, 52, Buttons.LEFT_BUTTON, TILE)

        sink.ops.clear()
        sink.recording = true
        midend.recordRedraw(sink)

        val ops = sink.ops.map { opName(it) }
        assertEquals("one start draw", 1, ops.count { it == "start_draw" })
        assertEquals("one end draw", 1, ops.count { it == "end_draw" })
        assertFalse(
            "no whole-window clear",
            sink.ops.any {
                opName(it) == "rect" && num(it, "x") == 0 && num(it, "y") == 0 &&
                    num(it, "w") == 264
            },
        )
        assertEquals("two changed tiles", 2, ops.count { it == "update" })
        assertTrue("the changed tiles are drawn", ops.count { it == "rect" } >= 8)

        // A clue toggle changes only the shading of one clue: no tile
        // and exactly one clue rectangle.
        val clue = Midend.newGame(Magnets, "6x5dt", SEED)
        val clueSink = KotlinTraceSink()
        clueSink.recording = false
        clue.recordRedraw(clueSink)
        clue.processKey(52, 20, Buttons.LEFT_BUTTON, TILE)
        clueSink.ops.clear()
        clueSink.recording = true
        clue.recordRedraw(clueSink)
        val clueRects = clueSink.ops.filter { opName(it) == "rect" }
        assertEquals("one clue rectangle", 1, clueRects.size)
        assertEquals("clue x", 36, num(clueRects.single(), "x"))
        assertEquals("clue y", 4, num(clueRects.single(), "y"))
        assertEquals("clue update", 1, clueSink.ops.count { opName(it) == "update" })
    }

    private fun opName(value: JsonValue.Obj): String =
        (value.fields["op"] as JsonValue.Str).value

    private fun num(value: JsonValue.Obj, key: String): Int =
        (value.fields[key] as? JsonValue.Num)?.value?.toInt() ?: Int.MIN_VALUE

    @Test
    fun `an inert click leaves the settled frame empty`() = OracleClient().use { oracle ->
        val save = oracle.newGame("magnets", "6x5dt", SEED).state
        val upstream = upstream()
        val corner = OracleClient.Point(20, 20, Buttons.LEFT_BUTTON)
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, listOf(corner)).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Magnets, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, listOf(corner)),
            upstream,
        )
        assertLines("warm inert", expected, actual)
        assertEquals(
            listOf("start_draw", "end_draw"),
            actual.lines().drop(7).filter { it.isNotEmpty() },
        )
        assertEquals(1, midend.stateCount)
    }
}
