package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: inertia.
 *
 * The first redraw of a drawstate is compared with the oracle and with
 * the frozen traces. The warm path is a settled frame with no pending
 * animation: no input, or an inert click on the player's own tile. A
 * warm frame after a real move is not compared, because the oracle has
 * no timer and records the animation frame at `anim_pos == 0`, which a
 * port without animation cannot reproduce. That case is pinned
 * Kotlin-only with an operation check.
 *
 * The cold frames also pin the order: the cells, then `inertia_follow`,
 * then the status, then the blitter save and the player sprite.
 */
class InertiaTraceTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        val OPEN_5X5 = "bbbbb" + "gbbbb" + "bbSbb" + "bbbbb" + "bbbbb"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until minOf(e.size, a.size)) {
            assertEquals("$what line $i", e[i], a[i])
        }
        assertEquals("$what line count", e.size, a.size)
    }

    /**
     * A warm trace has no `blitter_new`, because the allocation happened
     * before recording. The normalizer requires a live id, so a synthetic
     * allocation is inserted and removed from the text, as the guess
     * trace test does.
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

    private fun assertNoDrawOfOldFrame(what: String, text: String) {
        assertFalse("$what has no blitter_new", text.lines().any { it.startsWith("blitter_new ") })
        assertFalse("$what has no grid line", text.lines().any { it.startsWith("line ") })
    }

    private fun assertFollowBeforeStatus(text: String, solved: Boolean) {
        val lines = text.lines()
        val follow = lines.indexOf("inertia_follow $solved")
        val status = lines.indexOfFirst { it.startsWith("status ") }
        val save = lines.indexOfFirst { it.startsWith("blitter_save ") }
        assertTrue("the follow callback is present", follow >= 0)
        assertTrue("the status is present", status >= 0)
        assertTrue("the sprite is saved", save >= 0)
        assertTrue("follow before the status", follow < status)
        assertTrue("the status is before the sprite", status < save)
    }

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            for (params in listOf("10x8", "15x12", "20x16")) {
                val midend = Midend.newGame(Inertia, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("inertia", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/inertia/$params.trace").readText()
                assertEquals("frozen trace for '$params'", golden, actual)
                assertEquals(
                    "one blitter for '$params'",
                    1,
                    actual.lines().count { it.startsWith("blitter_new ") },
                )
                assertEquals(
                    "no blitter_free for '$params'",
                    0,
                    actual.lines().count { it.startsWith("blitter_free ") },
                )
                assertFollowBeforeStatus(actual, solved = false)
            }
        }

    @Test
    fun `cold traces from loaded saves match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val cases = linkedMapOf(
            "initial" to saveText("10x8", golden10x8(), emptyList()),
            "dead" to saveText("3x2", "Sgmwbs", listOf("MOVE" to "2"), "D1"),
            "completed" to saveText("3x2", "Sgswbb", listOf("MOVE" to "2")),
            "solved" to saveText("2x3", "mgbsSw", listOf("SOLVE" to "S1045")),
            "mid-follow" to saveText(
                "2x3",
                "mgbsSw",
                listOf("SOLVE" to "S1045", "MOVE" to "1"),
            ),
            "deaths" to saveText("10x8", golden10x8(), emptyList(), "D3"),
            "restarted" to saveText(
                "2x3",
                "wbgmSs",
                listOf("MOVE" to "0", "RESTART" to "wbgmSs"),
            ),
        )
        for ((name, save) in cases) {
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("$name trace", expected, actual)

            assertEquals(
                "$name has one status",
                1,
                actual.lines().count { it.startsWith("status ") },
            )
            val solved = name == "solved" || name == "mid-follow"
            assertFollowBeforeStatus(actual, solved = solved)
            assertEquals(
                "$name follow count",
                1,
                actual.lines().count { it.startsWith("inertia_follow ") },
            )
        }
    }

    @Test
    fun `a dead frame draws the star and the dead status`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText("3x2", "Sgmwbs", listOf("MOVE" to "2"), "D1")
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(Midend.fromSave(Inertia, SgtpSave.parse(save))),
            upstream,
        )
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("dead trace", expected, actual)

        val star = actual.lines().firstOrNull { it.startsWith("poly 5 1 ") }
        assertTrue("the dead player is a star", star != null)
        // "poly", the fill, the outline, the point count, and 32
        // coordinates.
        val tokens = star!!.split(' ')
        assertEquals("the star has 16 points", 36, tokens.size)
        assertEquals("the star point count", "16", tokens[3])
        assertTrue(
            "the dead status is drawn",
            actual.lines().contains("status \"DEAD!   Deaths: 1\""),
        )
    }

    @Test
    fun `the solved and completed status texts are drawn`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val cases = listOf(
            "solved" to saveText("2x3", "mgbsSw", listOf("SOLVE" to "S1045")),
            "auto-solved" to saveText(
                "2x3",
                "mgbsSw",
                listOf("SOLVE" to "S1045", "MOVE" to "1", "MOVE" to "0"),
            ),
            "completed" to saveText("3x2", "Sgswbb", listOf("MOVE" to "2")),
        )
        for ((name, save) in cases) {
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(Midend.fromSave(Inertia, SgtpSave.parse(save))),
                upstream,
            )
            val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
            assertLines("$name trace", expected, actual)
        }
        // The exact strings, not only the comparison.
        val solved = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(
                Midend.fromSave(
                    Inertia,
                    SgtpSave.parse(cases[0].second),
                ),
            ),
            upstream,
        )
        assertTrue(solved.contains("status \"Auto-solver used. Gems: 1\""))
        assertTrue(solved.contains("inertia_follow true"))
    }

    @Test
    fun `sized cold traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("inertia", "10x8", SEED).state
        val views = listOf(
            411 to 702,
            505 to 409,
            661 to 535,
            861 to 697,
            1051 to 851,
        )
        for ((viewWidth, viewHeight) in views) {
            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = viewWidth,
                    height = viewHeight,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                upstream,
            )
            assertLines("trace at ${viewWidth}x$viewHeight", expected, actual)
            assertEquals(
                "the header is the drawn board size at ${viewWidth}x$viewHeight",
                "size ${geometry.width} ${geometry.height}",
                actual.lines().first { it.startsWith("size ") },
            )
        }
    }

    @Test
    fun `a warm frame with no input matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("inertia", "10x8", SEED).state

        val expected = normalizedWarm(oracle.traceWarm(save, emptyList()).trace!!, upstream)
        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        val actual = normalizedWarm(KotlinTraceSink.warmTrace(midend, emptyList()), upstream)
        assertLines("warm no input", expected, actual)

        // The exact shape: start_draw, blitter_load, update,
        // inertia_follow, blitter_save, the player, update, end_draw.
        assertEquals(
            listOf(
                "start_draw",
                "blitter_load 1 232 8",
                "update 232 8 32 32",
                "inertia_follow false",
                "blitter_save 1 232 8",
                "circle 248 24 10 4 1",
                "update 232 8 32 32",
                "end_draw",
            ),
            actual.lines().drop(7).filter { it.isNotEmpty() },
        )
        assertNoDrawOfOldFrame("warm no input", actual)
        assertFalse("a warm frame has no status", actual.lines().any { it.startsWith("status ") })
    }

    @Test
    fun `a warm frame after an inert click matches the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("inertia", "10x8", SEED).state
            // The player's own tile at the preferred size.
            val point = OracleClient.Point(248, 24, Buttons.LEFT_BUTTON)

            val expected = normalizedWarm(oracle.traceWarm(save, listOf(point)).trace!!, upstream)
            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            val before = midend.state as Inertia.State
            val actual = normalizedWarm(
                KotlinTraceSink.warmTrace(midend, listOf(point)),
                upstream,
            )
            assertLines("warm inert click", expected, actual)
            assertNoDrawOfOldFrame("warm inert click", actual)

            // The inert click changes neither the player, the gems, nor
            // the history.
            val after = midend.state as Inertia.State
            assertEquals(before.px, after.px)
            assertEquals(before.py, after.py)
            assertEquals(before.gems, after.gems)
            assertEquals(1, midend.stateCount)
            assertTrue(midend.serialise().values("MOVE").isEmpty())
        }

    @Test
    fun `a warm frame after an inert click matches the oracle when sized`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("inertia", "10x8", SEED).state
            for ((viewWidth, viewHeight) in listOf(411 to 702, 505 to 409, 661 to 535)) {
                val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
                val geometry = midend.geometryFor(viewWidth, viewHeight)
                val tile = geometry.tileSize
                val border = tile / 4
                val viewX = geometry.originX + border + 7 * tile + tile / 2
                val viewY = geometry.originY + border + 0 * tile + tile / 2
                val point = OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)
                val gamePoint = OracleClient.Point(
                    viewX - geometry.originX,
                    viewY - geometry.originY,
                    Buttons.LEFT_BUTTON,
                )

                val expected = normalizedWarm(
                    oracle.traceWarm(save, listOf(point), viewWidth, viewHeight).trace!!,
                    upstream,
                )
                val actual = normalizedWarm(
                    KotlinTraceSink.warmTrace(midend, listOf(gamePoint), tile),
                    upstream,
                )
                assertLines("warm inert click $viewWidth x $viewHeight", expected, actual)
                assertNoDrawOfOldFrame("warm inert click $viewWidth x $viewHeight", actual)
            }
        }

    @Test
    fun `a warm frame after a real move is Kotlin-only`() {
        // The oracle records the frame at `anim_pos == 0`, where the
        // player is still at the old position and the changed cells are
        // not drawn. A port without animation cannot match it, so this
        // is an engine-side check, as the spec requires.
        val midend = Midend.newGame(Inertia, "10x8", SEED)
        val before = midend.state as Inertia.State
        val point = OracleClient.Point(0, 0, Buttons.MOD_NUM_KEYPAD or '2'.code)

        // The trace has no `blitter_new`, so the normalizer would reject
        // the dangling load. Read the raw operations instead.
        val actual = KotlinTraceSink.warmTrace(midend, listOf(point))
        val ops = (actual.field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .map { it.string("op") }
        assertFalse("the grid lines are cached", ops.contains("line"))
        assertFalse("no new blitter", ops.contains("blitter_new"))
        assertTrue("the old sprite is erased", ops.contains("blitter_load"))
        assertTrue("the new sprite is saved", ops.contains("blitter_save"))
        assertTrue("the follow callback is present", ops.contains("inertia_follow"))

        val after = midend.state as Inertia.State
        assertNotEquals("the move changes the player", before.py, after.py)
        assertEquals(2, midend.stateCount)
    }

    @Test
    fun `the empty move and the revive save trace like the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val open = saveText("5x5", OPEN_5X5, listOf("MOVE" to ""))
            val revive = saveText(
                "3x2",
                "Sgmbgs",
                listOf("MOVE" to "2", "SOLVE" to "S1375"),
            )
            for ((name, save) in listOf("empty move" to open, "revive" to revive)) {
                val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
                val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend),
                    upstream,
                )
                assertLines("$name trace", expected, actual)
                if (name == "revive") {
                    assertTrue(actual.contains("inertia_follow true"))
                    assertTrue(actual.contains("status \"Auto-solver used. Gems: 1\""))
                }
            }
        }

    @Test
    fun `a real move is not vacuous`() {
        // A wrong direction still adds a move, so the guard checks the
        // player, the collector, and the history too.
        val midend = Midend.fromSave(
            Inertia,
            SgtpSave.parse(saveText("5x5", OPEN_5X5, emptyList())),
        )
        val before = midend.state as Inertia.State
        midend.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '8'.code, TILE)
        val after = midend.state as Inertia.State
        assertEquals(before.px, after.px)
        assertNotEquals(before.py, after.py)
        assertEquals(before.gems, after.gems)
        assertEquals(2, midend.stateCount)
        assertEquals(listOf("0"), midend.serialise().values("MOVE"))
    }

    private fun golden10x8(): String =
        "bswmsgmSbmbmgmgbwgbbmgmwgwwswswmbmssbgmswbsbggbsswgwggbgsmswwswmwssbgbmmwgsbgmwm"

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>>,
        ui: String = "D0",
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Inertia.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "UI" to ui
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
