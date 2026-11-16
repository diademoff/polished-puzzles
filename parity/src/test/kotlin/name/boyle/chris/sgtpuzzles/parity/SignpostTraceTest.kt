package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: signpost.
 *
 * The fresh redraw of every preset is compared with the oracle and with
 * the frozen traces, including the 78-colour palette and the op counts
 * of `signpost.c`. Sized cold traces run at tiles 48, 63, 82, and 100
 * and in a letterboxed 411x702 viewport. The warm path covers a press,
 * a drag, a release, and a keyboard drag: the blitter erase and save, the
 * dim flags, and the changed tiles are the cache-exactness guards.
 * `traceWarm` records only the final redraw, so the erase on the release
 * frame is not in a compared trace; the engine test records that frame
 * itself. No frame has a flash or a status line.
 */
class SignpostTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 48

        val PRESETS = listOf("4x4", "4x4c", "5x5", "5x5c", "6x6c", "7x7c")

        const val PALETTE =
            "palette 800,800,800 967,967,967 633,633,633 615,615,615 400,400,400 1000,0,0 " +
                "200,1000,200 0,0,0 720,720,720 800,800,800 0,0,0 0,0,0 0,0,900 697,697,967 " +
                "996,996,996 996,625,477 594,980,594 496,996,828 574,438,855 996,645,0 " +
                "527,805,977 996,996,0 996,811,736 795,803,535 545,988,711 535,717,842 " +
                "785,541,428 762,725,488 762,900,488 996,903,368 697,697,697 697,438,334 " +
                "416,686,416 347,697,580 402,306,599 697,451,0 369,563,684 697,697,0 " +
                "697,567,515 556,562,375 381,692,498 375,502,589 550,379,299 533,507,342 " +
                "533,630,342 697,632,258 896,896,896 896,563,429 534,882,534 446,896,745 " +
                "517,394,770 896,580,0 475,724,879 896,896,0 896,729,663 715,722,482 " +
                "490,889,640 482,645,758 707,487,385 686,652,439 686,810,439 896,813,331 " +
                "898,898,898 898,713,638 697,890,697 648,898,814 687,619,828 898,722,400 " +
                "664,802,888 898,898,400 898,805,768 797,801,668 672,894,755 668,758,821 " +
                "793,671,614 781,762,644 781,850,644 898,852,584"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    /**
     * Normalizes a warm trace, which has no `blitter_new` by design.
     * Inject a dummy allocation for every blitter the trace uses, run
     * the normalizer (so a dangling id or a bad primitive still fails),
     * then drop the injected lines.
     */
    private fun normalizedWarm(trace: JsonValue.Obj, upstream: String): String {
        val ops = (trace.field("ops") as JsonValue.Arr).items.toMutableList()
        val ids = ops.asSequence()
            .filterIsInstance<JsonValue.Obj>()
            .mapNotNull { (it.fields["id"] as? JsonValue.Num)?.value }
            .distinct()
            .toList()
        ops.addAll(
            0,
            ids.map { id ->
                JsonValue.Obj(
                    linkedMapOf(
                        "op" to JsonValue.Str("blitter_new"),
                        "id" to JsonValue.Num(id),
                        "w" to JsonValue.Num(1),
                        "h" to JsonValue.Num(1),
                    ),
                )
            },
        )
        val patched = JsonValue.Obj(
            LinkedHashMap(trace.fields).apply { put("ops", JsonValue.Arr(ops)) },
        )
        return TraceNormalizer.normalize(patched, upstream)
            .lines()
            .filterNot { it.startsWith("blitter_new ") }
            .joinToString("\n", postfix = "\n")
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

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in PRESETS) {
                val midend = Midend.newGame(Signpost, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream())
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("signpost", params, SEED),
                    upstream(),
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/signpost/$params.trace").readText()
                assertLines("frozen trace for '$params'", golden, actual)

                val ops = actual.lines()
                assertFalse("'$params' has no status", ops.any { it.startsWith("status ") })
                assertEquals(
                    "'$params' palette",
                    PALETTE,
                    ops.first { it.startsWith("palette ") },
                )
                assertEquals(
                    "'$params' changed_state",
                    1,
                    ops.count { it == "changed_state false false" },
                )
                assertEquals(
                    "'$params' blitter",
                    1,
                    ops.count { it == "blitter_new 1 $TILE $TILE" },
                )
                val entry = Signpost.decodeParams(params) as Signpost.Params
                val n = entry.w * entry.h
                assertEquals("'$params' rects", n + 1, ops.count { it.startsWith("rect ") })
                assertEquals("'$params' polys", 2 * n + 1, ops.count { it.startsWith("poly ") })
                val state = midend.state as Signpost.State
                val dots = (0 until state.n).count {
                    state.prev[it] == -1 && state.nums[it] != 1
                }
                assertEquals("'$params' circles", dots, ops.count { it.startsWith("circle ") })
                assertEquals("'$params' updates", n + 2, ops.count { it.startsWith("update ") })
            }
        }

    @Test
    fun `sized cold traces match at four tiles`() = OracleClient().use { oracle ->
        for ((params, tile) in listOf(
            "4x4c" to 48,
            "4x4c" to 63,
            "4x4c" to 82,
            "4x4c" to 100,
            "7x7c" to 48,
        )) {
            val save = oracle.newGame("signpost", params, SEED).state
            val p = Signpost.decodeParams(params)
            val size = Signpost.computeSize(p, tile)
            val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
            val geometry = midend.geometryFor(size.width, size.height)
            assertEquals("$params tile $tile", tile, geometry.tileSize)
            assertEquals("$params origin x at $tile", 0, geometry.originX)
            assertEquals("$params origin y at $tile", 0, geometry.originY)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = size.width,
                    height = size.height,
                    recordTrace = true,
                ).trace!!,
                upstream(),
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream(),
            )
            assertLines("$params cold trace at tile $tile", expected, actual)
            assertEquals(
                "$params size at tile $tile",
                "size ${size.width} ${size.height}",
                actual.lines().first { it.startsWith("size ") },
            )
        }
    }

    @Test
    fun `a letterboxed cold trace matches`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(82, geometry.tileSize)
        assertEquals(0, geometry.originX)
        assertEquals(146, geometry.originY)

        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                emptyList(),
                width = 411,
                height = 702,
                recordTrace = true,
            ).trace!!,
            upstream(),
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream(),
        )
        assertLines("letterboxed cold trace", expected, actual)
    }

    @Test
    fun `a letterboxed 7x7 cold trace matches`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "7x7c", SEED).state
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(51, geometry.tileSize)
        assertEquals(2, geometry.originX)
        assertEquals(147, geometry.originY)

        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                emptyList(),
                width = 411,
                height = 702,
                recordTrace = true,
            ).trace!!,
            upstream(),
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            upstream(),
        )
        assertLines("letterboxed 7x7 cold trace", expected, actual)
    }

    @Test
    fun `a press, a drag, a release and a warm frame match`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("signpost", "4x4c", SEED).state
            // Cell (0,0) is number 1 and points SE at (1,1). The left drag
            // links it.
            val points = listOf(
                OracleClient.Point(36, 36, Buttons.LEFT_BUTTON),
                OracleClient.Point(60, 60, Buttons.LEFT_DRAG),
                OracleClient.Point(84, 84, Buttons.LEFT_DRAG),
                OracleClient.Point(84, 84, Buttons.LEFT_RELEASE),
            )
            val expected = normalizedWarm(oracle.traceWarm(save, points).trace!!, upstream())
            val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
            val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, points), upstream())
            assertLines("warm link trace", expected, actual)
            assertEquals("one move", listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
        }

    @Test
    fun `a mid-drag warm frame matches`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        val points = listOf(
            OracleClient.Point(36, 36, Buttons.LEFT_BUTTON),
            OracleClient.Point(60, 60, Buttons.LEFT_DRAG),
            OracleClient.Point(84, 84, Buttons.LEFT_DRAG),
        )
        val expected = normalizedWarm(oracle.traceWarm(save, points).trace!!, upstream())
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
        val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, points), upstream())
        assertLines("mid-drag trace", expected, actual)
        val ops = actual.lines()
        assertTrue("the sprite is saved", ops.any { it.startsWith("blitter_save 1 ") })
        assertTrue("the sprite is erased", ops.any { it.startsWith("blitter_load 1 ") })
    }

    @Test
    fun `a cut preview outside the board matches`() = OracleClient().use { oracle ->
        val save = oracle.newGame("signpost", "4x4c", SEED).state
        // Press cell (0,0), then drag past the top-left corner.
        val points = listOf(
            OracleClient.Point(36, 36, Buttons.LEFT_BUTTON),
            OracleClient.Point(5, 5, Buttons.LEFT_DRAG),
        )
        val expected = normalizedWarm(oracle.traceWarm(save, points).trace!!, upstream())
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
        val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, points), upstream())
        assertLines("cut preview trace", expected, actual)
    }

    @Test
    fun `a keyboard drag moves the ring and the sprite together`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("signpost", "4x4c", SEED).state
            val keys = listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
            )
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = keys),
                upstream(),
            )
            val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
            for (key in keys) {
                midend.processKey(
                    0,
                    0,
                    key,
                    TILE,
                )
            }
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream(),
            )
            assertLines("keyboard drag trace", expected, actual)
        }
}
