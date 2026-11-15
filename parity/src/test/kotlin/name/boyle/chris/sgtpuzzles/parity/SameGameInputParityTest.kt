package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: samegame.
 *
 * The oracle chooses a board size for each viewport, and the test sends
 * the same view coordinates to C and to Kotlin through `BoardGeometry`.
 * The `pointsSized` oracle operation replays a list of clicks inside one
 * midend, so a select and a remove see the same `game_ui`.
 *
 * A removal needs two taps, and both steps are compared. The first tap
 * must leave the save unchanged and still select a group, so the test
 * cannot pass because the taps were ignored. The final redraw carries
 * exactly one status line, which pins the selection text and the score.
 */
class SameGameInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        for (case in viewports) {
            val start = oracle.newGame("samegame", case.params, SEED)
            val layout = oracle.layout(start.state, case.width, case.height)
            val midend = Midend.newGame(SameGame, case.params, SEED)
            val geometry = midend.geometryFor(case.width, case.height)
            val size = SameGame.computeSize(SameGame.decodeParams(case.params), case.tile)
            val where = "${case.label}: '${case.params}' in ${case.width} x ${case.height}"

            assertEquals("tile $where", case.tile, geometry.tileSize)
            assertEquals("computeSize width $where", layout.width, size.width)
            assertEquals("computeSize height $where", layout.height, size.height)
            assertEquals("layout width $where", layout.width, geometry.width)
            assertEquals("layout height $where", layout.height, geometry.height)
            assertEquals(
                "origin x $where",
                (case.width - layout.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "origin y $where",
                (case.height - layout.height) / 2,
                geometry.originY,
            )
            assertTrue(
                "the board must fit $where: ${geometry.width} x ${geometry.height}",
                geometry.width <= case.width && geometry.height <= case.height,
            )
            if (case.letterboxed) {
                assertTrue(
                    "a letterboxed case must have a non-zero origin $where",
                    geometry.originX != 0 || geometry.originY != 0,
                )
            }
        }
    }

    @Test
    fun `the sized trace header reports the drawn size`() = OracleClient().use { oracle ->
        for (case in viewports) {
            val start = oracle.newGame("samegame", case.params, SEED)
            val midend = Midend.newGame(SameGame, case.params, SEED)
            val geometry = midend.geometryFor(case.width, case.height)
            val preferred = SameGame.computeSize(
                SameGame.decodeParams(case.params),
                SameGame.preferredTileSize,
            )
            val where = "${case.label}: '${case.params}' in ${case.width} x ${case.height}"

            assertNotEquals(
                "the drawn size must differ from the preferred size $where",
                preferred.width,
                geometry.width,
            )

            val expected = oracle.pointsSized(
                start.state,
                emptyList(),
                case.width,
                case.height,
                recordTrace = true,
            ).trace
            assertTraceSize("C trace $where", expected, geometry.width, geometry.height)
            assertTraceSize(
                "Kotlin trace $where",
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
                geometry.width,
                geometry.height,
            )
        }
    }

    @Test
    fun `two pointer taps remove a group at every viewport`() =
        OracleClient().use { oracle ->
            for (case in viewports) {
                val start = oracle.newGame("samegame", case.params, SEED)
                val midend = Midend.newGame(SameGame, case.params, SEED)
                val state = midend.state as SameGame.State
                val target = firstGroup(state, 2)
                    ?: error("no group of two tiles in '${case.params}'")

                val geometry = midend.geometryFor(case.width, case.height)
                val viewX = geometry.originX + (target.first + 1) * geometry.tileSize
                val viewY = geometry.originY + (target.second + 1) * geometry.tileSize
                val gameX = geometry.toGameX(viewX)
                val gameY = geometry.toGameY(viewY)
                val tile = geometry.tileSize
                val where = "${case.label} at ($viewX,$viewY) tile $tile origin " +
                    "(${geometry.originX},${geometry.originY})"

                // The first tap only selects. The save must not change,
                // in C or in Kotlin, and the selection text must appear.
                val firstSave = midend.serialise().serialise()
                val firstOracle = oracle.pointsSized(
                    start.state,
                    listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
                    case.width,
                    case.height,
                    recordTrace = true,
                )
                assertEquals(
                    "C first tap must not change the save $where",
                    start.state,
                    firstOracle.state.state,
                )
                assertFalse("C first tap must not add history $where", firstOracle.state.canUndo)
                assertEquals(
                    "first tap id $where",
                    firstOracle.state.id,
                    midend.id(),
                )
                assertEquals(
                    "first tap integer status $where",
                    firstOracle.state.status,
                    midend.status(),
                )

                assertEquals(
                    "Kotlin first tap outcome $where",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertTrue(
                    "the first tap must select a group $where",
                    (midend.ui as SameGame.Ui).nselected >= 2,
                )
                assertEquals(
                    "Kotlin first tap must not change the save $where",
                    firstSave,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "first tap status $where",
                    midend.statusText(),
                    traceStatus(firstOracle.trace),
                )
                assertTrue(
                    "the first tap must show the selection $where",
                    midend.statusText().contains("Selected:"),
                )
                assertTrace(
                    "first tap drawing $where",
                    firstOracle.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )

                // The second tap removes the group. The state, the ID,
                // the status, and the final status line must match.
                val secondOracle = oracle.pointsSized(
                    start.state,
                    listOf(
                        OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                    ),
                    case.width,
                    case.height,
                    recordTrace = true,
                )
                assertEquals(
                    "Kotlin second tap outcome $where",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
                )
                assertNotEquals(
                    "the second tap must change the save $where",
                    firstSave,
                    midend.serialise().serialise(),
                )
                assertTrue("the second tap must add history $where", secondOracle.state.canUndo)
                assertEquals(
                    "save $where",
                    secondOracle.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("id $where", secondOracle.state.id, midend.id())
                assertEquals("status $where", secondOracle.state.status, midend.status())
                assertTrue("the second tap must undo $where", midend.canUndo)
                assertEquals(
                    "the score text must replace the selection $where",
                    midend.statusText(),
                    traceStatus(secondOracle.trace),
                )
                assertFalse(
                    "no selection text after the removal $where",
                    midend.statusText().contains("Selected:"),
                )
                assertTrace(
                    "final drawing $where",
                    secondOracle.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }
        }

    @Test
    fun `a modified cursor key does not move the cursor`() = OracleClient().use { oracle ->
        val params = "5x2c3s2"
        val save = saveText(params, "1,1,0,0,0,0,0,0,0,0")
        val plain = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
        )
        val modified = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
        )

        // The oracle is the evidence: both sequences remove the group at
        // (0,0) and (1,0), so the two modified cursor keys are not
        // cursor moves. A cursor that moved would reach a blank cell.
        val expectedPlain = oracle.keys(save, plain)
        val expectedModified = oracle.keys(save, modified)
        assertEquals(
            "a modified cursor key must not move the samegame cursor",
            expectedPlain.state,
            expectedModified.state,
        )
        assertEquals(listOf("M0,1"), SgtpSave.parse(expectedModified.state).values("MOVE"))

        val tile = SameGame.preferredTileSize
        val midend = Midend.fromSave(SameGame, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile),
        )
        for (key in listOf(
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
        )) {
            assertEquals("outcome for $key", KeyOutcome.Unused, midend.processKey(0, 0, key, tile))
            assertEquals("cursor x for $key", 1, (midend.ui as SameGame.Ui).xsel)
            assertEquals("cursor y for $key", 0, (midend.ui as SameGame.Ui).ysel)
        }
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(listOf("M0,1"), midend.serialise().values("MOVE"))
        assertEquals("save", expectedModified.state, midend.serialise().serialise())
        assertEquals("id", expectedModified.id, midend.id())
        assertEquals("status", expectedModified.status, midend.status())
        assertEquals("undo", expectedModified.canUndo, midend.canUndo)
        assertEquals("redo", expectedModified.canRedo, midend.canRedo)
    }

    private fun saveText(params: String, desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Same Game"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
    }

    private data class Viewport(
        val label: String,
        val params: String,
        val width: Int,
        val height: Int,
        val tile: Int,
        val letterboxed: Boolean = false,
    )

    private val viewports = listOf(
        // The four view classes the shell must handle.
        Viewport("phone", "5x5c3s2", 411, 702, 69, letterboxed = true),
        Viewport("tablet", "5x5c3s2", 720, 1280, 121, letterboxed = true),
        Viewport("landscape", "5x5c3s2", 1024, 600, 101, letterboxed = true),
        Viewport("tiny", "5x5c3s2", 200, 200, 33, letterboxed = true),
        // Even and odd tile sizes, each with a letterbox on at least
        // one axis, plus the exact-fit square.
        Viewport("tile48-square", "5x5c3s2", 285, 285, 48),
        Viewport("tile48-letterbox", "10x5c3s2", 530, 300, 48, letterboxed = true),
        Viewport("tile63-letterbox", "10x5c3s2", 690, 380, 63, letterboxed = true),
        Viewport("tile82-letterbox", "10x5c3s2", 900, 600, 82, letterboxed = true),
        Viewport("tile100-letterbox", "10x5c3s2", 1100, 600, 100, letterboxed = true),
        Viewport("tile100-tall", "5x5c3s2", 594, 598, 100, letterboxed = true),
        // A non-square board at the phone viewport.
        Viewport("phone-nonsquare", "10x5c3s2", 411, 702, 37, letterboxed = true),
    )

    private fun firstGroup(state: SameGame.State, minSize: Int): Pair<Int, Int>? {
        val w = state.params.width
        val h = state.params.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (state.col(x, y) == 0) continue
                if (componentSize(state, x, y) >= minSize) return x to y
            }
        }
        return null
    }

    private fun componentSize(state: SameGame.State, sx: Int, sy: Int): Int {
        val w = state.params.width
        val h = state.params.height
        val colour = state.col(sx, sy)
        val seen = BooleanArray(state.n)
        val stack = ArrayList<Int>()
        stack.add(sy * w + sx)
        seen[sy * w + sx] = true
        var count = 0
        while (stack.isNotEmpty()) {
            val index = stack.removeAt(stack.size - 1)
            count++
            val x = index % w
            val y = index / w
            if (x > 0 && !seen[index - 1] && state.col(x - 1, y) == colour) {
                seen[index - 1] = true
                stack.add(index - 1)
            }
            if (x + 1 < w && !seen[index + 1] && state.col(x + 1, y) == colour) {
                seen[index + 1] = true
                stack.add(index + 1)
            }
            if (y > 0 && !seen[index - w] && state.col(x, y - 1) == colour) {
                seen[index - w] = true
                stack.add(index - w)
            }
            if (y + 1 < h && !seen[index + w] && state.col(x, y + 1) == colour) {
                seen[index + w] = true
                stack.add(index + w)
            }
        }
        return count
    }

    private fun traceStatus(trace: JsonValue.Obj?): String? {
        val ops = (trace?.field("ops") as? JsonValue.Arr)?.items ?: return null
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
    }

    private fun assertTraceSize(what: String, trace: JsonValue.Obj?, width: Int, height: Int) {
        requireNotNull(trace) { "$what: the oracle sent no trace" }
        assertEquals("width $what", width.toLong(), trace.number("w"))
        assertEquals("height $what", height.toLong(), trace.number("h"))
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        val e = TraceNormalizer.normalize(expected, upstream).lines()
        val a = TraceNormalizer.normalize(actual, upstream).lines()
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
    }
}
