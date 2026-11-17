package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: map.
 *
 * The frozen first frames, the sized frames at four tile sizes and a
 * letterbox viewport, the warm frames that exercise the dirty-cell cache
 * and the one-cell blitter, the label and stipple preferences, the error
 * diamond, and the cursor epsilon.
 *
 * The description text seeds the smoothing, so two descriptions with the
 * same edge list paint different triangles; that is pinned here through
 * the public save and trace path.
 */
class MapTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun handSave(desc: String, params: String = "5x2n5dn"): String =
        "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :3:Map\n" +
            "PARAMS  :${params.length}:$params\n" +
            "CPARAMS :${params.length}:$params\n" +
            "SEED    :5:12345\n" +
            "DESC    :${desc.length}:$desc\n" +
            "NSTATES :1:1\n" +
            "STATEPOS:1:1\n"

    private fun withMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    @Test(timeout = 300_000)
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("map").map { it.params }) {
                val midend = Midend.newGame(Map, params, SEED)
                val actual = normalize(KotlinTraceSink.trace(midend))
                val expected = normalize(oracle.traceNew("map", params, SEED))
                assertLines("trace for '$params'", expected, actual)

                val golden = File(
                    testdata,
                    "trace/map/${GenTraces.fileName(params)}.trace",
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
                assertTrue(
                    "'$params' has the changed_state line",
                    actual.lines().contains("changed_state false false"),
                )
                assertTrue(
                    "'$params' allocates one blitter before start_draw",
                    actual.lines().indexOf("blitter_new 1 23 23") <
                        actual.lines().indexOf("start_draw"),
                )
                assertTrue(
                    "'$params' palette",
                    actual.lines().contains(
                        "palette 800,800,800 0,0,0 627,157,133 290,698,290 " +
                            "322,690,788 710,698,98 1000,0,0 1000,1000,1000 0,0,0",
                    ),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the first frame draws the grid background and every cell`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("map", "20x15n30dn", SEED).state
            val trace = normalize(oracle.trace(save))
            // The midend clears the window, then the game paints the grid
            // background, then every cell has a clip/unclip/update triple.
            assertTrue(trace.lines().contains("rect 0 0 441 341 0"))
            assertTrue(trace.lines().contains("rect 20 20 401 301 1"))
            assertTrue(trace.lines().contains("update 20 20 401 301"))
            assertEquals(300, trace.lines().count { it.startsWith("clip ") })
            assertEquals(300, trace.lines().count { it == "unclip" })
            // A diagonally split cell is a three-point polygon:
            // poly, fill, outline, and three coordinate pairs.
            assertTrue(
                "a triangle is drawn",
                trace.lines().any { it.startsWith("poly ") && it.split(' ').size == 10 },
            )
            // No text without a label preference.
            assertTrue(trace.lines().none { it.startsWith("text ") })
        }

    @Test(timeout = 600_000)
    fun `the sized fresh traces match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("map", "20x15n30dn", SEED).state
        val p = Map.decodeParams("20x15n30")
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Map.computeSize(p, tile)
            val points = listOf(
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, tile)
            }
            val actual = normalize(KotlinTraceSink.stateTrace(midend, tile))
            assertLines("tile $tile", normalize(expected.trace!!), actual)
            assertEquals("tile $tile headers", expected.state.state, midend.serialise().serialise())
            assertTrue(
                "tile $tile size",
                actual.lines().contains("size ${board.width} ${board.height}"),
            )
            assertTrue(
                "tile $tile origin",
                actual.lines().contains("rect ${tile} ${tile} ${(20 * tile) + 1} ${(15 * tile) + 1} 1"),
            )
        }
    }

    @Test(timeout = 300_000)
    fun `the letterbox frame uses the found tile and origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("map", "20x15n30dn", SEED).state
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            val geometry = midend.geometryFor(411, 702)
            assertEquals(18, geometry.tileSize)
            val points = listOf(
                OracleClient.Point(geometry.originX, geometry.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(geometry.originX, geometry.originY, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                width = 411,
                height = 702,
                recordTrace = true,
            )
            for (point in points) {
                midend.processKey(0, 0, point.button, geometry.tileSize)
            }
            val actual = normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            )
            assertLines("letterbox", normalize(expected.trace!!), actual)
            assertTrue(
                "letterbox size",
                actual.lines().contains("size ${geometry.width} ${geometry.height}"),
            )
        }

    @Test(timeout = 300_000)
    fun `the warm frames keep the cache and the blitter`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("map", "20x15n30dn", SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Map.computeSize(Map.decodeParams("20x15n30"), tile)
                val centre = tile + tile / 2
                val points = listOf(
                    OracleClient.Point(centre, centre, Buttons.LEFT_BUTTON),
                    OracleClient.Point(centre + 2 * tile, centre, Buttons.LEFT_DRAG),
                )
                val expected = oracle.traceWarm(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                )
                val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                val actual = normalizeWarm(
                    KotlinTraceSink.warmTrace(midend, points, tile),
                )
                assertLines("warm tile $tile", normalizeWarm(expected.trace!!), actual)
                assertTrue(
                    "warm tile $tile has no blitter_new",
                    actual.lines().none { it.startsWith("blitter_new") },
                )
                assertTrue(
                    "warm tile $tile saves the sprite",
                    actual.lines().any { it.startsWith("blitter_save 1 ") },
                )
                assertTrue(
                    "warm tile $tile draws the blob",
                    actual.lines().any { it.startsWith("circle ") },
                )
                assertTrue(
                    "warm tile $tile has no full-window clear",
                    actual.lines().none { it == "rect 0 0 ${board.width} ${board.height} 0" },
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the letterbox warm frame keeps the blitter`() = OracleClient().use { oracle ->
        val save = oracle.newGame("map", "20x15n30dn", SEED).state
        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        val tile = geometry.tileSize
        val gamePoints = listOf(
            OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.LEFT_BUTTON),
            OracleClient.Point(3 * tile + tile / 2, tile + tile / 2, Buttons.LEFT_DRAG),
        )
        val viewPoints = gamePoints.map {
            OracleClient.Point(it.x + geometry.originX, it.y + geometry.originY, it.button)
        }
        val expected = oracle.traceWarm(save, viewPoints, 411, 702)
        val actual = normalizeWarm(KotlinTraceSink.warmTrace(midend, gamePoints, tile))
        assertLines("letterbox warm", normalizeWarm(expected.trace!!), actual)
        assertTrue(
            "the letterbox warm frame saves the sprite",
            actual.lines().any { it.startsWith("blitter_save 1 ") },
        )
        assertTrue(
            "the letterbox warm frame has no blitter_new",
            actual.lines().none { it.startsWith("blitter_new ") },
        )
    }

    @Test(timeout = 300_000)
    fun `the warm path of a real move redraws only the changed cells`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            val points = listOf(
                OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.traceWarm(save, points, width = 141, height = 81)
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            val actual = normalizeWarm(KotlinTraceSink.warmTrace(midend, points, 20))
            assertLines("warm move", normalizeWarm(expected.trace!!), actual)
            assertEquals("0:4", midend.serialise().value("MOVE"))
            // The release frame already drew the changed cell with
            // recording off, so the recorded frame is empty. The move
            // itself is the control.
            assertTrue(
                "the warm frame after a release is empty",
                actual.lines().none { it.startsWith("clip ") },
            )
            assertEquals(2, midend.stateCount)
        }

    @Test(timeout = 300_000)
    fun `an inert warm click draws an empty frame`() = OracleClient().use { oracle ->
        val save = handSave("fh,0123a")
        val points = listOf(
            OracleClient.Point(5, 5, Buttons.LEFT_BUTTON),
            OracleClient.Point(5, 5, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.traceWarm(save, points, width = 141, height = 81)
        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        val actual = normalizeWarm(KotlinTraceSink.warmTrace(midend, points, 20))
        assertLines("warm border", normalizeWarm(expected.trace!!), actual)
        assertEquals(1, midend.stateCount)
        // Only the frame markers: no cell, no update, no sprite.
        assertTrue(
            "an inert click draws nothing:\n$actual",
            actual.lines().none {
                it.startsWith("clip ") || it.startsWith("rect ") ||
                    it.startsWith("circle ") || it.startsWith("update ") ||
                    it.startsWith("text ") || it.startsWith("poly ") ||
                    it.startsWith("blitter_")
            },
        )
    }

    @Test(timeout = 300_000)
    fun `the label preferences draw the C text`() = OracleClient().use { oracle ->
        val save = oracle.newGame("map", "20x15n30dn", SEED).state
        for ((pref, value) in listOf(
            "show-labels=regions" to 1,
            "show-labels=colours" to 2,
        )) {
            val expected = normalize(oracle.trace(save, prefs = "$pref\n"))
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            (midend.ui as Map.Ui).showLabels = value
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("'$pref'", expected, actual)
            assertTrue(
                "'$pref' draws text",
                actual.lines().count { it.startsWith("text ") } > 0,
            )
        }

        // Colour labels include the pencil letters: a0:4 on the hand
        // board marks region 4, so the blank half shows 'a'.
        val marked = withMove(handSave("fh,0123a"), "p0:4")
        val expected = normalize(
            oracle.trace(marked, prefs = "show-labels=colours\n"),
        )
        val midend = Midend.fromSave(Map, SgtpSave.parse(marked))
        (midend.ui as Map.Ui).showLabels = 2
        val actual = normalize(KotlinTraceSink.stateTrace(midend))
        assertLines("pencil colour labels", expected, actual)
        assertTrue(
            "the pencil letter is drawn",
            actual.lines().any { it.endsWith("\"a\"") },
        )
    }

    @Test(timeout = 300_000)
    fun `the stipple preference changes the pencil radius`() =
        OracleClient().use { oracle ->
            val marked = withMove(handSave("fh,0123a"), "p0:4")
            for ((pref, large) in listOf(
                "stipple-style=small" to false,
                "stipple-style=large" to true,
            )) {
                val expected = normalize(oracle.trace(marked, prefs = "$pref\n"))
                val midend = Midend.fromSave(Map, SgtpSave.parse(marked))
                (midend.ui as Map.Ui).largeStipples = large
                val actual = normalize(KotlinTraceSink.stateTrace(midend))
                assertLines("'$pref'", expected, actual)
                val radius = if (large) 20 / 4 else 20 / 7
                assertTrue(
                    "'$pref' radius $radius",
                    actual.lines().any {
                        it.startsWith("circle ") &&
                            it.split(' ').getOrNull(3) == radius.toString() &&
                            it.endsWith(" 2 2")
                    },
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the error marker and the cursor match the oracle`() =
        OracleClient().use { oracle ->
            // Two adjacent regions with the same colour: a red diamond
            // and a white exclamation mark.
            val conflicting = withMove(handSave("fh,0a0a0"), "0:1")
            val expected = normalize(oracle.trace(conflicting))
            val midend = Midend.fromSave(Map, SgtpSave.parse(conflicting))
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("error marker", expected, actual)
            assertTrue(
                "the diamond is filled with COL_ERROR",
                actual.lines().any { it.startsWith("poly 6 1 ") },
            )
            assertTrue(
                "the exclamation stem is white",
                actual.lines().any { it.startsWith("rect ") && it.endsWith(" 7") },
            )

            // The cursor uses the small radius and the epsilon from the
            // last arrow key.
            val save = handSave("fh,0123a")
            for (key in listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_RIGHT,
            )) {
                val oracleTrace = normalize(oracle.trace(save, sequence = listOf(key)))
                val cursor = Midend.fromSave(Map, SgtpSave.parse(save))
                cursor.processKey(0, 0, key, 20)
                val cursorTrace = normalize(KotlinTraceSink.stateTrace(cursor))
                assertLines("cursor $key", oracleTrace, cursorTrace)
                assertTrue(
                    "cursor $key small circle",
                    cursorTrace.lines().any {
                        it.startsWith("circle ") &&
                            it.split(' ').getOrNull(3) == (20 / 4).toString()
                    },
                )
                assertTrue(
                    "cursor $key blitter save",
                    cursorTrace.lines().any { it.startsWith("blitter_save 1 ") },
                )
            }

            // The four epsilon directions move the quadrant choice on a
            // split cell. The full trace comparison above covers the
            // drawn position; this pins the move strings the quadrants
            // produce.
            val sequences = listOf(
                // Right four times, then drop.
                listOf(
                    Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_SELECT,
                ),
                // Right four times, down, then drop with a DOWN epsilon.
                listOf(
                    Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT,
                ),
                // Right four times, up, then drop with an UP epsilon.
                listOf(
                    Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_UP, Buttons.CURSOR_SELECT,
                ),
            )
            for (sequence in sequences) {
                val expectedState = oracle.keys(save, sequence).state
                val cursor = Midend.fromSave(Map, SgtpSave.parse(save))
                for (key in sequence) cursor.processKey(0, 0, key, 20)
                assertEquals(
                    "cursor sequence ${sequence.size}",
                    expectedState,
                    cursor.serialise().serialise(),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the description text decides the diagonals`() = OracleClient().use { oracle ->
        val baseDesc = oracle.newGame("map", "20x15n30dn", SEED).state
        val desc = SgtpSave.parse(baseDesc).value("DESC")!!
        val edge = desc.substringBefore(',')
        val otherDesc = "$edge,0zc"
        val baseSave = handSave(desc, "20x15n30dn")
        val otherSave = handSave(otherDesc, "20x15n30dn")

        val baseTrace = normalize(oracle.trace(baseSave))
        val otherTrace = normalize(oracle.trace(otherSave))
        assertNotEquals(
            "the two descriptions paint different diagonals",
            baseTrace.lines().filter { it.startsWith("poly ") },
            otherTrace.lines().filter { it.startsWith("poly ") },
        )

        for (save in listOf(baseSave, otherSave)) {
            val expected = normalize(oracle.trace(save))
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            val actual = normalize(KotlinTraceSink.stateTrace(midend))
            assertLines("description ${SgtpSave.parse(save).value("DESC")!!.take(8)}", expected, actual)
        }
    }

    /**
     * Normalizes a warm trace, which has no `blitter_new` by design.
     * Inject a dummy allocation for every blitter the trace uses, run
     * the normalizer, then drop the injected lines. This still rejects a
     * dangling id or a bad primitive.
     */
    private fun normalizeWarm(trace: JsonValue.Obj): String {
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
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
