package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: bridges.
 *
 * The nine frozen first-draw traces, the palette, sized draws at four
 * tile sizes, the 411x702 letterbox, the first-draw double update, and
 * warm frames after a press, a drag, a move, a cursor, and the hints
 * preference.
 */
class BridgesTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24

        val PRESETS = listOf(
            "7x7i30e10m2d0", "7x7i30e10m2d1", "7x7i30e10m2d2",
            "10x10i30e10m2d0", "10x10i30e10m2d1", "10x10i30e10m2d2",
            "15x15i30e10m2d0", "15x15i30e10m2d1", "15x15i30e10m2d2",
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
    fun `the frozen first draws match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in PRESETS) {
                val midend = Midend.newGame(Bridges, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream())
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("bridges", params, SEED),
                    upstream(),
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/bridges/$params.trace").readText()
                assertLines("frozen trace for '$params'", golden, actual)

                val ops = actual.lines()
                assertFalse("'$params' has no status", ops.any { it.startsWith("status ") })
                assertFalse("'$params' has no blitter", ops.any { it.startsWith("blitter_") })
                assertFalse("'$params' has no thick line", ops.any { it.startsWith("thick_line ") })
                assertEquals(
                    "'$params' changed_state",
                    1,
                    ops.count { it == "changed_state false false" },
                )
                assertEquals(
                    "'$params' palette",
                    "palette 800,800,800 0,0,0 967,967,967 633,633,633 250,1000,250 " +
                        "967,967,967 633,633,633 717,717,717 1000,250,250 1000,640,640",
                    ops.first { it.startsWith("palette ") },
                )
                // The game's full update and the midend's full update.
                assertEquals(
                    "'$params' full updates",
                    2,
                    ops.count { it == "update 0 0 ${midend.size.width} ${midend.size.height}" },
                )
            }
        }

    @Test
    fun `sized cold traces and warm frames match at four tiles`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", "7x7i30e10m2d0", SEED).state
            val upstream = upstream()
            for ((view, tile) in listOf(
                384 to 48,
                504 to 63,
                656 to 82,
                800 to 100,
            )) {
                val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
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
                assertEquals(
                    "full updates at $view",
                    2,
                    actual.lines().count {
                        it == "update 0 0 ${geometry.width} ${geometry.height}"
                    },
                )

                // Press the first island, which is at (0, 0).
                val point = OracleClient.Point(tile, tile, Buttons.LEFT_BUTTON)
                val warmExpected = TraceNormalizer.normalize(
                    oracle.traceWarm(save, listOf(point), view, view).trace!!,
                    upstream,
                )
                val warmMidend = Midend.fromSave(Bridges, SgtpSave.parse(save))
                val warmActual = TraceNormalizer.normalize(
                    KotlinTraceSink.warmTrace(warmMidend, listOf(point), tile),
                    upstream,
                )
                assertLines("warm press at $view", warmExpected, warmActual)
                assertEquals(1, warmMidend.stateCount)
                assertTrue(warmMidend.serialise().values("MOVE").isEmpty())
            }
        }

    @Test
    fun `the letterboxed cold trace and a warm press match`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", "7x7i30e10m2d0", SEED).state
            val upstream = upstream()
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
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
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                upstream,
            )
            assertLines("letterbox cold", expected, actual)
            assertEquals(
                2,
                actual.lines().count {
                    it == "update 0 0 ${geometry.width} ${geometry.height}"
                },
            )

            val viewX = geometry.originX + geometry.tileSize / 2 + geometry.tileSize / 2
            val viewY = geometry.originY + geometry.tileSize / 2 + geometry.tileSize / 2
            val viewPoint = OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)
            val warmExpected = TraceNormalizer.normalize(
                oracle.traceWarm(save, listOf(viewPoint), 411, 702).trace!!,
                upstream,
            )
            val warmMidend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            val warmActual = TraceNormalizer.normalize(
                KotlinTraceSink.warmTrace(
                    warmMidend,
                    listOf(
                        OracleClient.Point(
                            viewX - geometry.originX,
                            viewY - geometry.originY,
                            Buttons.LEFT_BUTTON,
                        ),
                    ),
                    geometry.tileSize,
                ),
                upstream,
            )
            assertLines("letterbox warm press", warmExpected, warmActual)
        }

    @Test
    fun `a warm drag and release is one lazy move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", "7x7i30e10m2d0", SEED).state
        val upstream = upstream()
        val points = listOf(
            OracleClient.Point(TILE, TILE, Buttons.LEFT_BUTTON),
            OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_DRAG),
            OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_RELEASE),
        )
        val expected = TraceNormalizer.normalize(
            oracle.traceWarm(save, points).trace!!,
            upstream,
        )
        val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.warmTrace(midend, points),
            upstream,
        )
        assertLines("warm drag", expected, actual)
        assertEquals(2, midend.stateCount)
        // The first island (0,0) is joined to (2,0) with one bridge.
        assertEquals(listOf("L0,0,6,0,1"), midend.serialise().values("MOVE"))
    }

    @Test
    fun `the cursor island uses the cursor background`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", "7x7i30e10m2d0", SEED).state
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = listOf(Buttons.CURSOR_SELECT)),
                upstream,
            )
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("cursor trace", expected, actual)
            // The cursor replaces the CIRCLE's inner background with
            // COL_CURSOR (9).
            assertTrue(actual.contains("circle 24 24 11 9 9"))
        }

    private fun saveText(
        params: String,
        cparams: String,
        desc: String,
        moves: List<String> = emptyList(),
    ): String {
        val records = listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Bridges.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", cparams),
            SaveRecord("SEED", "1"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (moves.size + 1).toString()),
            SaveRecord("STATEPOS", (moves.size + 1).toString()),
        ) + moves.map { SaveRecord("MOVE", it) }
        return SgtpSave.of(records).serialise()
    }

    @Test
    fun `a loop in no-loops mode draws the warning colours`() =
        OracleClient().use { oracle ->
            val save = saveText(
                "3x3i30e10m2Ld0",
                "3x3m2L",
                "2a2aaa2a2",
                listOf("L0,0,2,0,1;L2,0,2,2,1;L2,2,0,2,1;L0,2,0,0,1"),
            )
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("loop warning", expected, actual)
            assertEquals(0, midend.status())
            // At least one warning-coloured rect and one warning circle.
            assertTrue(actual.lines().any {
                it.startsWith("rect ") && it.endsWith(" 8")
            })
            assertTrue(actual.lines().any {
                it.startsWith("circle ") && it.contains(" 8 8")
            })
        }

    @Test
    fun `an asymmetric loop marks only the cycle cells`() =
        OracleClient().use { oracle ->
            // Two four-cycles joined by a single bridge. The four-cycle
            // test above is symmetric, so this board also pins that the
            // warning covers exactly the cells on a cycle: the bridge
            // cell (3,2) is not part of any loop.
            val save = saveText(
                "7x5i30e10m2Ld0",
                "7x5m2L",
                "2a2dg2a3a3a2gd2a2",
                listOf(
                    "L0,0,2,0,1;L2,0,2,2,1;L2,2,0,2,1;L0,2,0,0,1;" +
                        "L2,2,4,2,1;L4,2,6,2,1;L6,2,6,4,1;L6,4,4,4,1;" +
                        "L4,4,4,2,1",
                ),
            )
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("asymmetric loop warning", expected, actual)
            assertEquals(0, midend.status())

            fun warned(x: Int, y: Int): Boolean {
                val left = TILE / 2 + x * TILE
                val top = TILE / 2 + y * TILE
                return actual.lines().any { line ->
                    val p = line.split(' ')
                    p.size == 6 && p[0] == "rect" && p[5] == "8" &&
                        p[1].toInt() in left until left + TILE &&
                        p[2].toInt() in top until top + TILE
                }
            }
            assertTrue("a cell on the first cycle is warned", warned(1, 0))
            assertTrue("a cell on the second cycle is warned", warned(5, 2))
            assertFalse("the joining bridge is not a loop", warned(3, 2))
        }

    @Test
    fun `a solved board draws every island and bridge as marked`() =
        OracleClient().use { oracle ->
            val fresh = oracle.newGame("bridges", "7x7i30e10m2d0", SEED).state
            val solved = oracle.solve(fresh).state
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(oracle.trace(solved), upstream)
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(solved))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("solved trace", expected, actual)

            val state = midend.state as Bridges.State
            assertTrue(state.completed)
            assertTrue(state.solved)
            val ops = actual.lines()

            // Every island draws its centre circle on `DI_BG_MARK`,
            // which is COL_MARK (5).
            val islandRadius = TILE * 12 / 20 - TILE / 8
            for (island in state.islands) {
                val cx = TILE + island.x * TILE
                val cy = TILE + island.y * TILE
                assertTrue(
                    "island (${island.x},${island.y}) is marked",
                    ops.contains("circle $cx $cy $islandRadius 5 5"),
                )
            }

            // Every bridge cell carries a `DL_LOCK` background, which is
            // a COL_MARK rectangle inside the cell.
            fun locked(x: Int, y: Int): Boolean {
                val left = TILE / 2 + x * TILE
                val top = TILE / 2 + y * TILE
                return ops.any { line ->
                    val p = line.split(' ')
                    p.size == 6 && p[0] == "rect" && p[5] == "5" &&
                        p[1].toInt() in left until left + TILE &&
                        p[2].toInt() in top until top + TILE
                }
            }
            var bridges = 0
            for (y in 0 until state.height) {
                for (x in 0 until state.width) {
                    if (state.lines[y * state.width + x] == 0) continue
                    bridges++
                    assertTrue("bridge cell ($x,$y) is locked", locked(x, y))
                }
            }
            assertTrue("the board has bridges", bridges > 0)
        }

    @Test
    fun `an impossible clue draws the warning colours`() =
        OracleClient().use { oracle ->
            val save = saveText("3x3i30e10m4d0", "3x3m4", "4a1f")
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("impossible warning", expected, actual)
            assertTrue(actual.lines().any {
                it.startsWith("circle ") && it.contains(" 8 8")
            })
        }

    @Test
    fun `the hints preference changes only the hints`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("bridges", "7x7i30e10m2d0", SEED).state
            val upstream = upstream()
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, prefs = "show-hints=true"),
                upstream,
            )
            val midend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            (midend.ui as Bridges.Ui).showHints = true
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, TILE),
                upstream,
            )
            assertLines("hints trace", expected, actual)

            val offMidend = Midend.fromSave(Bridges, SgtpSave.parse(save))
            val off = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(offMidend, TILE),
                upstream,
            )
            assertFalse(off == actual)
            // The preference is not serialized and does not change the
            // state, id, or save.
            assertEquals(offMidend.id(), midend.id())
            assertEquals(
                offMidend.serialise().serialise(),
                midend.serialise().serialise(),
            )
        }
}
