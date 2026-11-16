package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Size
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: untangle.
 *
 * A new game, the empty text form, the status, real clicks and drags
 * at several tile sizes, the fixed 60-pixel hit radius, undo and redo,
 * completion and its event, the solver, and the solver failure. Every
 * real move must change the board and add one state; every vacuous
 * event must change neither.
 */
class UntangleGameTest {

    private val presets = listOf("6", "10", "15", "20", "25")

    @Test(timeout = 120_000)
    fun `a new game matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in presets) {
            val expected = oracle.newGame("untangle", params, SEED)
            val actual = Midend.newGame(Untangle, params, SEED)
            assertSame("new '$params'", expected, actual)
            assertEquals("status '$params'", 0, actual.status())
            assertEquals("status text '$params'", "", actual.statusText())
            assertEquals("text '$params'", "", actual.textFormat())
            assertEquals("oracle text '$params'", oracle.text(expected.state), actual.textFormat())
            assertFalse("can format '$params'", Untangle.canFormatAsText)
            assertEquals(
                "size '$params'",
                Size(expected.width, expected.height),
                Untangle.computeSize(Untangle.decodeParams(params), 64),
            )
        }
    }

    @Test
    fun `request keys keeps the C arrow mode`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "6", SEED).state
        val expected = oracle.requestKeys(save)
        assertEquals(3, expected.arrowMode)
        assertTrue(expected.labels.isEmpty())

        val actual = Untangle.requestKeys(Untangle.decodeParams("6"))
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertEquals(3, expected.arrowMode)
        assertTrue(actual.labels.isEmpty())
    }

    @Test
    fun `a click on a point is a real move at every tile size`() =
        OracleClient().use { oracle ->
            val params = "10"
            val save = oracle.newGame("untangle", params, SEED).state
            val midend = Midend.newGame(Untangle, params, SEED)
            val point = pixelOf(midend.state, 0, 64)

            for (tile in listOf(48, 63, 82, 100)) {
                val board = Untangle.computeSize(Untangle.decodeParams(params), tile)
                val x = point.first * tile / 64
                val y = point.second * tile / 64
                val points = listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                assertEquals("one move at tile $tile", 1, movesOf(expected.state.state).size)

                val actual = Midend.fromSave(Untangle, SgtpSave.parse(save))
                val before = actual.state
                for (p in points) {
                    assertEquals(
                        "outcome at tile $tile",
                        KeyOutcome.SomeEffect,
                        actual.processKey(p.x, p.y, p.button, tile),
                    )
                }
                assertNotEquals("board changes at tile $tile", before, actual.state)
                assertEquals("one history step at tile $tile", 2, actual.stateCount)
                assertSame("click at tile $tile", expected.state, actual)
                assertEquals(
                    "the move text at tile $tile",
                    "P0:$x,$y/$tile",
                    movesOf(actual.serialise().serialise()).last(),
                )
                assertTrace(
                    "click trace at tile $tile",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(actual, tile),
                )
            }

            // At tile 64 the click writes the exact current rational.
            val tile = 64
            val x = point.first
            val y = point.second
            val clickMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            clickMidend.processKey(x, y, Buttons.LEFT_BUTTON, tile)
            clickMidend.processKey(x, y, Buttons.LEFT_RELEASE, tile)
            assertEquals("P0:160,23/64", movesOf(clickMidend.serialise().serialise()).last())
        }

    @Test
    fun `the 60 pixel hit radius separates a hit from a miss`() =
        OracleClient().use { oracle ->
            val params = "10"
            val save = oracle.newGame("untangle", params, SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Untangle.computeSize(Untangle.decodeParams(params), tile)
                val (px, py) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 0, tile)
                // Toward the centre from the top point, every other
                // point is out of range at 61 pixels, and the hit point
                // stays on the board so the release commits.
                val missY = py + 61
                val hitY = py + 59
                val miss = oracle.pointsSized(
                    save,
                    listOf(
                        OracleClient.Point(px, missY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(px, missY, Buttons.LEFT_RELEASE),
                    ),
                    width = board.width,
                    height = board.height,
                )
                assertEquals("the oracle misses at tile $tile", 0, movesOf(miss.state.state).size)

                val missMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
                assertEquals(
                    "press misses at tile $tile",
                    KeyOutcome.NoEffect,
                    missMidend.processKey(px, missY, Buttons.LEFT_BUTTON, tile),
                )
                assertEquals(
                    "release misses at tile $tile",
                    KeyOutcome.NoEffect,
                    missMidend.processKey(px, missY, Buttons.LEFT_RELEASE, tile),
                )
                assertEquals("no state at tile $tile", 1, missMidend.stateCount)

                val hit = oracle.pointsSized(
                    save,
                    listOf(
                        OracleClient.Point(px, hitY, Buttons.LEFT_BUTTON),
                        OracleClient.Point(px, hitY, Buttons.LEFT_RELEASE),
                    ),
                    width = board.width,
                    height = board.height,
                )
                assertEquals("the oracle hits at tile $tile", 1, movesOf(hit.state.state).size)

                val hitMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
                hitMidend.processKey(px, hitY, Buttons.LEFT_BUTTON, tile)
                hitMidend.processKey(px, hitY, Buttons.LEFT_RELEASE, tile)
                assertEquals("one state at tile $tile", 2, hitMidend.stateCount)
                assertSame("hit at tile $tile", hit.state, hitMidend)
            }

            // The radius is inclusive. Point 1 is at (241, 49) at tile
            // 64; at (301, 49) it is exactly 60 pixels away and no
            // other point is inside 61 pixels.
            val (hx, hy) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 1, 64)
            assertEquals("point 1 at tile 64", 241 to 49, hx to hy)
            val edge = listOf(
                OracleClient.Point(hx + 60, hy, Buttons.LEFT_BUTTON),
                OracleClient.Point(hx + 60, hy, Buttons.LEFT_RELEASE),
            )
            val edgeExpected = oracle.pointsSized(save, edge, width = 320, height = 320)
            assertEquals(
                "the oracle hits at exactly 60 pixels",
                1,
                movesOf(edgeExpected.state.state).size,
            )
            val edgeMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (p in edge) {
                assertEquals(
                    "exactly 60 pixels is a hit",
                    KeyOutcome.SomeEffect,
                    edgeMidend.processKey(p.x, p.y, p.button, 64),
                )
            }
            assertSame("60 pixels", edgeExpected.state, edgeMidend)
            assertEquals(
                "the boundary move text",
                "P1:301,49/64",
                movesOf(edgeMidend.serialise().serialise()).last(),
            )

            val outside = listOf(
                OracleClient.Point(hx + 61, hy, Buttons.LEFT_BUTTON),
                OracleClient.Point(hx + 61, hy, Buttons.LEFT_RELEASE),
            )
            val outsideExpected = oracle.pointsSized(save, outside, width = 320, height = 320)
            assertEquals(
                "the oracle misses at 61 pixels",
                0,
                movesOf(outsideExpected.state.state).size,
            )
            val outsideMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (p in outside) {
                assertEquals(
                    "61 pixels is a miss",
                    KeyOutcome.NoEffect,
                    outsideMidend.processKey(p.x, p.y, p.button, 64),
                )
            }
            assertEquals("no state at 61 pixels", 1, outsideMidend.stateCount)
        }

    @Test
    fun `a real drag commits one move at the release`() = OracleClient().use { oracle ->
        val params = "10"
        val save = oracle.newGame("untangle", params, SEED).state
        for (tile in listOf(48, 63, 82, 100)) {
            val board = Untangle.computeSize(Untangle.decodeParams(params), tile)
            val (px, py) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 0, tile)
            val points = listOf(
                OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
                OracleClient.Point(px + tile / 2, py + tile / 4, Buttons.LEFT_DRAG),
                OracleClient.Point(px + tile, py + tile / 2, Buttons.LEFT_DRAG),
                OracleClient.Point(px + tile, py + tile / 2, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                width = board.width,
                height = board.height,
            )
            assertEquals("one move at tile $tile", 1, movesOf(expected.state.state).size)

            val actual = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (p in points) {
                assertNotEquals(
                    "the drag is consumed at tile $tile",
                    KeyOutcome.Unused,
                    actual.processKey(p.x, p.y, p.button, tile),
                )
            }
            assertEquals("one history step at tile $tile", 2, actual.stateCount)
            assertSame("drag at tile $tile", expected.state, actual)
            assertEquals(
                "the last drag point wins at tile $tile",
                "P0:${px + tile},${py + tile / 2}/$tile",
                movesOf(actual.serialise().serialise()).last(),
            )
        }
    }

    @Test
    fun `undo and redo follow the history and match the oracle`() =
        OracleClient().use { oracle ->
            val params = "6"
            val save = oracle.newGame("untangle", params, SEED).state
            val (px, py) = pixelOf(Midend.newGame(Untangle, params, SEED).state, 0, 64)
            val (qx, qy) = pixelOf(Midend.newGame(Untangle, params, SEED).state, 1, 64)

            var expected = oracle.pointsSized(
                save,
                listOf(
                    OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
                    OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
                ),
                width = 256,
                height = 256,
            ).state
            expected = oracle.pointsSized(
                expected.state,
                listOf(
                    OracleClient.Point(qx, qy, Buttons.LEFT_BUTTON),
                    OracleClient.Point(qx, qy, Buttons.LEFT_RELEASE),
                ),
                width = 256,
                height = 256,
            ).state
            assertEquals("two moves", 2, movesOf(expected.state).size)

            val actual = Midend.fromSave(Untangle, SgtpSave.parse(save))
            actual.processKey(px, py, Buttons.LEFT_BUTTON, 64)
            actual.processKey(px, py, Buttons.LEFT_RELEASE, 64)
            actual.processKey(qx, qy, Buttons.LEFT_BUTTON, 64)
            actual.processKey(qx, qy, Buttons.LEFT_RELEASE, 64)
            assertSame("after two clicks", expected, actual)

            expected = oracle.undo(expected.state)
            actual.undo()
            assertSame("undo once", expected, actual)
            expected = oracle.undo(expected.state)
            actual.undo()
            assertSame("undo twice", expected, actual)
            assertEquals("at the first state", 1, actual.statePos)
            expected = oracle.redo(expected.state)
            assertTrue(actual.redo())
            assertSame("redo once", expected, actual)
            assertEquals("a redo branch", 3, actual.stateCount)
            assertTrue("can undo", actual.canUndo)
            assertTrue("can redo", actual.canRedo)
        }

    @Test
    fun `a real completing move and its redo raise exactly one event`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "4", SEED).state
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            assertFalse("a fresh preset crosses", midend.takeCompletedEvent())
            assertEquals("open", 0, midend.status())

            // Drag point 0 from (96,14) to (96,100) at tile 64.
            val points = listOf(
                OracleClient.Point(96, 14, Buttons.LEFT_BUTTON),
                OracleClient.Point(96, 57, Buttons.LEFT_DRAG),
                OracleClient.Point(96, 100, Buttons.LEFT_DRAG),
                OracleClient.Point(96, 100, Buttons.LEFT_RELEASE),
            )
            val completed = oracle.pointsSized(save, points, width = 192, height = 192)
            assertEquals("the oracle completes", 1, completed.state.status)
            for (p in points) midend.processKey(p.x, p.y, p.button, 64)
            assertSame("completing move", completed.state, midend)
            assertEquals("complete", 1, midend.status())
            assertTrue("the completion event", midend.takeCompletedEvent())
            assertFalse("only once", midend.takeCompletedEvent())

            assertTrue(midend.undo())
            assertEquals("open again", 0, midend.status())
            assertTrue(midend.redo())
            assertEquals("complete again", 1, midend.status())
            assertTrue("the redo event", midend.takeCompletedEvent())
            assertFalse("the redo event is once", midend.takeCompletedEvent())
        }

    @Test(timeout = 120_000)
    fun `every preset solves exactly like the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val save = oracle.newGame("untangle", params, SEED).state
            val expected = oracle.solve(save)
            val actual = Midend.newGame(Untangle, params, SEED)
            assertEquals("solves '$params'", true, actual.solve())
            assertEquals("one SOLVE step '$params'", 2, actual.stateCount)
            assertEquals(
                "the SOLVE record '$params'",
                SgtpSave.parse(expected.state).values("SOLVE"),
                SgtpSave.parse(actual.serialise().serialise()).values("SOLVE"),
            )
            assertSame("solved '$params'", expected, actual)
            assertEquals("status 1 '$params'", 1, actual.status())
            assertEquals("cheated, not a win", false, actual.takeCompletedEvent())
        }
    }

    @Test
    fun `the aux point index truncates like the C sscanf`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "6", SEED).state
        val midend = Midend.newGame(Untangle, "6", SEED)
        val aux = requireNotNull(midend.auxInfo)
        assertTrue("the aux starts at point 0: $aux", aux.startsWith("S;P0:"))
        val solved = SgtpSave.parse(oracle.solve(save).state).values("SOLVE").single()

        // glibc reads `%d` as a `long` and keeps the low 32 bits, so
        // 2^32 is point 0 and the answer is the same as without it.
        assertEquals(
            "2^32 is point 0",
            solved,
            Untangle.solve(midend.state, aux.replaceFirst(";P0:", ";P4294967296:")),
        )

        // -2^31-1 truncates to INT_MAX, which is not point 0. The
        // oracle reports "Internal error: aux_info badly formatted"
        // for that aux; the seam drops the message and returns null.
        assertNull(
            Untangle.solve(midend.state, aux.replaceFirst(";P0:", ";P-2147483649:")),
        )
    }

    @Test
    fun `a move then a solve and a solved board still solve`() =
        OracleClient().use { oracle ->
            val params = "10"
            val save = oracle.newGame("untangle", params, SEED).state
            val (px, py) = pixelOf(Midend.fromSave(Untangle, SgtpSave.parse(save)).state, 0, 64)
            val click = listOf(
                OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
                OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
            )
            val clicked = oracle.pointsSized(save, click, width = 320, height = 320).state
            val expected = oracle.solve(clicked.state)

            val actual = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (p in click) actual.processKey(p.x, p.y, p.button, 64)
            assertTrue(actual.solve())
            assertSame("solve after a move", expected, actual)

            val solvedAgain = oracle.solve(expected.state)
            assertTrue(actual.solve())
            assertSame("solve of a solved board", solvedAgain, actual)
            assertEquals("still solved", 1, actual.status())
        }

    @Test
    fun `a game id without AUXINFO cannot solve`() = OracleClient().use { oracle ->
        val id = oracle.newGame("untangle", "6", SEED).id
        val midend = Midend.fromGameId(Untangle, id)
        assertNull(midend.auxInfo)
        val count = midend.stateCount
        assertFalse(midend.solve())
        assertEquals("no state was added", count, midend.stateCount)
        assertEquals("still open", 0, midend.status())
    }

    @Test
    fun `the cursor keys and a keyboard drag match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state

            // One arrow highlights point 0 from the default cursor.
            val down = listOf(Buttons.CURSOR_DOWN)
            val expected = oracle.keys(save, down)
            val actual = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (key in down) actual.processKey(0, 0, key, 64)
            assertSame("cursor down", expected, actual)
            assertTrue(
                "the cursor moved",
                (actual.ui as Untangle.Ui).cursorPoint >= 0,
            )
            val cursor = actual.cursorLocation(64)
            assertTrue("a cursor rectangle", cursor != null)

            // Select, two arrows, select: one real move.
            val sequence = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            )
            val dragged = oracle.keys(save, sequence)
            assertEquals("one move", 1, movesOf(dragged.state).size)
            val dragMidend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            for (key in sequence) dragMidend.processKey(0, 0, key, 64)
            assertSame("keyboard drag", dragged, dragMidend)
            assertEquals("one state", 2, dragMidend.stateCount)
        }

    private fun pixelOf(state: Game.State, index: Int, tileSize: Int): Pair<Int, Int> {
        val point = (state as Untangle.State).point(index)
        return (point.x * tileSize / point.d).toInt() to
            (point.y * tileSize / point.d).toInt()
    }

    private fun movesOf(state: String): List<String> = SgtpSave.parse(state).values("MOVE")

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj, actual: JsonValue.Obj) {
        val e = TraceNormalizer.normalize(expected).lines()
        val a = TraceNormalizer.normalize(actual).lines()
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
