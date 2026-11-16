package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: net.
 *
 * The eight frozen first-draw traces, the palette, the clipped edge
 * tiles, sized draws at four tile sizes, the 411x702 letterbox, and
 * warm frames after a press, a drag, a release, and a source move.
 */
class NetTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        val PRESETS = listOf(
            "5x5", "7x7", "9x9", "11x11",
            "5x5w", "7x7w", "9x9w", "11x11w",
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
    fun `the frozen first draws match the oracle for every preset`() {
        OracleClient().use { oracle ->
            for (params in PRESETS) {
                val midend = Midend.newGame(Net, params, SEED)
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.trace(midend),
                    upstream(),
                )
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("net", params, SEED),
                    upstream(),
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/net/$params.trace").readText()
                assertLines("frozen trace for '$params'", golden, actual)

                val ops = actual.lines()
                assertEquals(
                    "'$params' palette",
                    "palette 800,800,800 600,600,600 400,400,400 0,0,0 " +
                        "0,0,1000 0,1000,1000 1000,0,0 1000,0,0",
                    ops.first { it.startsWith("palette ") },
                )
                assertFalse("'$params' has no blitter", ops.any { it.startsWith("blitter_") })
                assertFalse("'$params' has no thick line", ops.any { it.startsWith("thick_line ") })
                assertFalse("'$params' has no text", ops.any { it.startsWith("text ") })
                assertEquals(
                    "'$params' changed_state",
                    1,
                    ops.count { it == "changed_state false false" },
                )
                // The edge tiles outside the grid are clipped.
                assertTrue(
                    "'$params' has a clipped corner",
                    ops.any { it.startsWith("clip 3 3 1 1") },
                )
                // Zero-size grid rectangles are emitted.
                assertTrue(
                    "'$params' has a zero-size rectangle",
                    ops.any { it == "rect 4 4 0 0 2" },
                )
                // A four-point wire endpoint polygon is present.
                assertTrue(
                    "'$params' has an endpoint polygon",
                    ops.any { it.startsWith("poly 3 3 4 ") },
                )
                // The midend's full update.
                assertTrue(
                    "'$params' full update",
                    ops.contains("update 0 0 ${midend.size.width} ${midend.size.height}"),
                )
                // One status line on the cold trace.
                assertEquals(
                    "'$params' status count",
                    1,
                    ops.count { it.startsWith("status ") },
                )
            }
        }

        // The float endpoint box: 32 * 0.24F is 7.6799998, and the
        // +0.5F truncation around the centre gives 12 and 28.
        assertTrue(
            File(testdata, "trace/net/5x5.trace").readText().contains(
                "poly 3 3 4 28 28 28 12 12 12 12 28",
            ),
        )
    }

    @Test
    fun `sized cold traces and warm frames match at four tiles`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("net", "5x5", SEED).state
            val upstream = upstream()
            for ((view, tile) in listOf(
                249 to 48,
                325 to 63,
                420 to 82,
                511 to 100,
            )) {
                val midend = Midend.fromSave(Net, SgtpSave.parse(save))
                val geometry = midend.geometryFor(view, view)
                assertEquals("$view tile", tile, geometry.tileSize)
                assertEquals("$view origin x", 0, geometry.originX)
                assertEquals("$view origin y", 0, geometry.originY)

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

                // A middle click locks the centre tile. A lock has no
                // rotation animation, so the C warm input redraw and
                // the Kotlin warm redraw keep the same cache.
                val point = OracleClient.Point(
                    geometry.tileSize / 2,
                    geometry.tileSize / 2,
                    Buttons.MIDDLE_BUTTON,
                )
                val warmExpected = TraceNormalizer.normalize(
                    oracle.traceWarm(save, listOf(point), view, view).trace!!,
                    upstream,
                )
                val warmMidend = Midend.fromSave(Net, SgtpSave.parse(save))
                val warmActual = TraceNormalizer.normalize(
                    KotlinTraceSink.warmTrace(warmMidend, listOf(point), tile),
                    upstream,
                )
                assertLines("warm lock at $view", warmExpected, warmActual)
                assertEquals(2, warmMidend.stateCount)
            }
        }

    @Test
    fun `the letterboxed cold trace and a warm click match`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("net", "5x5", SEED).state
            val upstream = upstream()
            val midend = Midend.fromSave(Net, SgtpSave.parse(save))
            val geometry = midend.geometryFor(412, 702)
            assertEquals(80, geometry.tileSize)
            assertEquals(1, geometry.originX)
            assertEquals(146, geometry.originY)

            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = 412,
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

            val x = geometry.originX + geometry.tileSize / 2
            val y = geometry.originY + geometry.tileSize / 2

            // A middle click locks the origin cell. A lock has no
            // rotation animation, so a warm trace is exact.
            val points = listOf(
                OracleClient.Point(x, y, Buttons.MIDDLE_BUTTON),
            )
            val warmExpected = TraceNormalizer.normalize(
                oracle.traceWarm(save, points, 412, 702).trace!!,
                upstream,
            )
            val warmMidend = Midend.fromSave(Net, SgtpSave.parse(save))
            val warmActual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(
                    warmMidend,
                    listOf(
                        OracleClient.Point(
                            x - geometry.originX,
                            y - geometry.originY,
                            Buttons.MIDDLE_BUTTON,
                        ),
                    ),
                    geometry.tileSize,
                ),
                upstream,
            )
            assertLines("letterbox warm lock", warmExpected, warmActual)

            // A press and release rotates on the release. The C
            // `pointsSized` response is then traced as a fresh save:
            // a live trace after the release would render the first
            // animation frame, which the seam does not have.
            val click = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val moved = oracle.pointsSized(
                save,
                click,
                width = 412,
                height = 702,
            ).state
            val coldExpected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    moved.state,
                    emptyList(),
                    width = 412,
                    height = 702,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val clickMidend = Midend.fromSave(Net, SgtpSave.parse(save))
            for (p in click) {
                clickMidend.processKey(
                    p.x - geometry.originX,
                    p.y - geometry.originY,
                    p.button,
                    geometry.tileSize,
                )
            }
            val coldActual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(
                    Midend.fromSave(Net, SgtpSave.parse(moved.state)),
                    geometry.tileSize,
                ),
                upstream,
            )
            assertLines("letterbox release trace", coldExpected, coldActual)
            assertEquals(2, clickMidend.stateCount)
            assertEquals(listOf("A0,0"), clickMidend.serialise().values("MOVE"))
        }

    @Test
    fun `the unlocked-loops preference changes only the error colors`() =
        OracleClient().use { oracle ->
            // A board of crosses is full of loops and no cell is
            // locked. With unlocked squares included, every loop edge
            // is an error; without them, no loop edge is.
            val text = Midend.fromGameId(Net, "3x3:fffffffff").serialise().serialise()
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(
                oracle.trace(text, prefs = "unlocked-loops=false"),
                upstream,
            )
            val midend = Midend.fromSave(Net, SgtpSave.parse(text))
            (midend.ui as Net.Ui).unlockedLoops = false
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("unlocked-loops=false", expected, actual)

            val onMidend = Midend.fromSave(Net, SgtpSave.parse(text))
            assertTrue((onMidend.ui as Net.Ui).unlockedLoops)
            val on = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(onMidend, TILE),
                upstream,
            )
            assertNotEquals(on, actual)
            // The preference is not serialised and changes no state.
            assertEquals(midend.id(), onMidend.id())
            assertEquals(
                midend.serialise().serialise(),
                onMidend.serialise().serialise(),
            )
            assertTrue(on.contains(" 7 7"))
            assertFalse(actual.contains(" 7 7"))
        }

    @Test
    fun `a warm frame after a lock emits no unchanged status`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("net", "5x5", SEED).state
            val upstream = upstream()
            val point = OracleClient.Point(5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON)
            val expected = TraceNormalizer.normalize(
                oracle.traceWarm(save, listOf(point)).trace!!,
                upstream,
            )
            val midend = Midend.fromSave(Net, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(midend, listOf(point)),
                upstream,
            )
            assertLines("warm lock", expected, actual)
            assertEquals(
                "a lock does not change the status text",
                0,
                actual.lines().count { it.startsWith("status ") },
            )
            // A settled warm frame draws nothing: the input's own
            // redraw already updated the cache.
            assertEquals(
                "a settled warm frame has no tile draws",
                0,
                actual.lines().count { it.startsWith("clip ") },
            )

            // The input's own frame draws only the changed cells, not
            // the whole (width+2)*(height+2) grid. The oracle cannot
            // record this frame, so the count is pinned directly.
            val inputFrame = KotlinTraceSink.warmFrame(
                Midend.fromSave(Net, SgtpSave.parse(save)),
                listOf(point),
            )
            val inputLines = TraceNormalizer.normalize(inputFrame, upstream())
            val clips = inputLines.lines().count { it.startsWith("clip ") }
            assertTrue("input frame clips $clips cells", clips in 1 until 49)
        }

    @Test
    fun `a drag emits one rotation and the release after it emits none`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("net", "5x5", SEED).state
            val upstream = upstream()
            // Press at the top-left of the tile, drag to the right edge:
            // a quarter turn clockwise, then a release. The C
            // `pointsSized` trace starts a fresh drawstate after the
            // gesture, so the rotation animation does not enter it.
            val points = listOf(
                OracleClient.Point(4 + 1, 4 + 1, Buttons.LEFT_BUTTON),
                OracleClient.Point(4 + 31, 4 + 1, Buttons.LEFT_DRAG),
                OracleClient.Point(4 + 31, 4 + 1, Buttons.LEFT_RELEASE),
            )
            val moved = oracle.pointsSized(save, points).state
            val expected = TraceNormalizer.normalize(
                oracle.trace(moved.state),
                upstream,
            )
            val midend = Midend.fromSave(Net, SgtpSave.parse(save))
            for (p in points) midend.processKey(p.x, p.y, p.button, TILE)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(
                    Midend.fromSave(Net, SgtpSave.parse(moved.state)),
                    TILE,
                ),
                upstream,
            )
            assertLines("drag trace", expected, actual)
            assertEquals(2, midend.stateCount)
            assertEquals(listOf("C0,0"), midend.serialise().values("MOVE"))
        }
}
