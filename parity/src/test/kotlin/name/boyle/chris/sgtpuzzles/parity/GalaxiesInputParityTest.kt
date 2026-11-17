package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: galaxies.
 *
 * Layout, the edge rounding, real edge clicks and right-button arrow
 * drags at several tile sizes and through a letterbox, the keyboard
 * cursor, the modifiers, the inert shapes, undo and redo, and a real
 * completion. The click, drag, and key sequences are replayed by the C
 * midend.
 */
class GalaxiesInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `layout matches the oracle at every viewport and preset`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702, 781 to 781, 1080 to 1920, 320 to 480, 240 to 320,
        )
        for (preset in Galaxies.presets()) {
            val midend = Midend.newGame(Galaxies, preset.params, SEED)
            val save = midend.serialise().serialise()
            for ((w, h) in viewports) {
                val expected = oracle.layout(save, w, h)
                val geometry = midend.geometryFor(w, h)
                assertEquals("${preset.params} $w x $h width", expected.width, geometry.width)
                assertEquals("${preset.params} $w x $h height", expected.height, geometry.height)
            }
        }
    }

    @Test
    fun `the edge rounding matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val tile = 32
        // `SCOORD(x) = x * tile / 2 + tile`. A vertical edge at (2,1) is
        // at (2*tile, tile + tile/2); its vertex (2,2) is (2*tile, 2*tile).
        val cases = mapOf(
            "vertical edge" to (2 * tile to tile + tile / 2),
            "horizontal edge" to (tile + tile / 2 to 2 * tile),
            "vertex" to (2 * tile to 2 * tile),
            "tile centre" to (tile + tile / 2 to tile + tile / 2),
            "margin" to (2 to 2),
        )
        for ((name, point) in cases) {
            val points = listOf(
                OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON),
                OracleClient.Point(point.first, point.second, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(base.state, points)
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
            for (p in points) midend.processKey(p.x, p.y, p.button, tile)
            assertEquals("$name save", expected.state.state, midend.serialise().serialise())
            val moves = SgtpSave.parse(expected.state.state).values("MOVE")
            when (name) {
                "vertical edge" -> assertEquals("$name move", listOf("E2,1"), moves)
                "horizontal edge" -> assertEquals("$name move", listOf("E1,2"), moves)
                // A click exactly on a vertex rounds to the horizontal
                // edge, because the distance comparison is not strict.
                "vertex" -> assertEquals("$name move", listOf("E3,2"), moves)
                // A tile centre is equidistant from two edges; the
                // horizontal one wins the tie.
                "tile centre" -> assertEquals("$name move", listOf("E1,2"), moves)
                else -> assertTrue("$name no move", moves.isEmpty())
            }
        }
    }

    @Test
    fun `real edge clicks match the oracle at several tile sizes`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("galaxies", "7x7dn", SEED)
            for (tile in listOf(48, 63, 82, 100)) {
                val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
                val board = Galaxies.computeSize(Galaxies.decodeParams("7x7dn"), tile)
                val points = listOf(
                    OracleClient.Point(2 * tile, tile + tile / 2, Buttons.LEFT_BUTTON),
                    OracleClient.Point(2 * tile, tile + tile / 2, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    base.state,
                    points,
                    board.width,
                    board.height,
                )
                for (p in points) midend.processKey(p.x, p.y, p.button, tile)
                assertEquals(
                    "tile $tile save",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "tile $tile move",
                    listOf("E2,1"),
                    SgtpSave.parse(expected.state.state).values("MOVE"),
                )
                assertTrue("tile $tile canUndo", midend.canUndo)

                // A second click toggles the edge back.
                val again = oracle.pointsSized(
                    expected.state.state,
                    points,
                    board.width,
                    board.height,
                )
                for (p in points) midend.processKey(p.x, p.y, p.button, tile)
                assertEquals(
                    "tile $tile twice",
                    again.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "tile $tile twice moves",
                    listOf("E2,1", "E2,1"),
                    SgtpSave.parse(again.state.state).values("MOVE"),
                )
            }
        }

    @Test
    fun `a letterboxed edge click matches the oracle`() = OracleClient().use { oracle ->
        for ((params, view) in listOf(
            "7x7dn" to (411 to 702),
            "10x10dn" to (411 to 702),
            "15x15dn" to (1080 to 1920),
        )) {
            val base = oracle.newGame("galaxies", params, SEED)
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
            val geometry = midend.geometryFor(view.first, view.second)
            val tile = geometry.tileSize
            val points = listOf(
                OracleClient.Point(
                    geometry.originX + 2 * tile,
                    geometry.originY + tile + tile / 2,
                    Buttons.LEFT_BUTTON,
                ),
                OracleClient.Point(
                    geometry.originX + 2 * tile,
                    geometry.originY + tile + tile / 2,
                    Buttons.LEFT_RELEASE,
                ),
            )
            val expected = oracle.pointsSized(base.state, points, view.first, view.second)
            for (p in points) {
                midend.processKey(
                    p.x - geometry.originX,
                    p.y - geometry.originY,
                    p.button,
                    tile,
                )
            }
            assertEquals("$params letterbox", expected.state.state, midend.serialise().serialise())
            assertEquals(
                "$params letterbox move",
                1,
                SgtpSave.parse(expected.state.state).values("MOVE").size,
            )
        }
    }

    @Test
    fun `clicks outside the board and in the margin change nothing`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("galaxies", "7x7dn", SEED)
            val view = 411 to 702
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
            val geometry = midend.geometryFor(view.first, view.second)
            val outside = listOf(
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
                OracleClient.Point(view.first - 1, view.second - 1, Buttons.LEFT_BUTTON),
                OracleClient.Point(geometry.originX - 5, 100, Buttons.LEFT_BUTTON),
                OracleClient.Point(geometry.originX - 5, 100, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(base.state, outside, view.first, view.second)
            for (p in outside) {
                midend.processKey(
                    p.x - geometry.originX,
                    p.y - geometry.originY,
                    p.button,
                    geometry.tileSize,
                )
            }
            assertEquals("outside save", expected.state.state, midend.serialise().serialise())
            assertEquals(
                "outside moves",
                0,
                SgtpSave.parse(expected.state.state).values("MOVE").size,
            )
        }

    @Test
    fun `a dot in the way makes the edge inert`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        val state = midend.state as Galaxies.State
        val tile = 32
        // An edge with a dot on one of its end vertices is not legal.
        val vertexDot = state.dotPositions().firstOrNull {
            it.first % 2 == 0 && it.second % 2 == 0
        }
        if (vertexDot == null) return@use
        val (dx, dy) = vertexDot
        val edgeX = if (dy + 1 <= state.sy - 2) dx to dy + 1 else dx to dy - 1
        val points = listOf(
            OracleClient.Point(
                edgeX.first * tile / 2 + tile,
                edgeX.second * tile / 2 + tile,
                Buttons.LEFT_BUTTON,
            ),
            OracleClient.Point(
                edgeX.first * tile / 2 + tile,
                edgeX.second * tile / 2 + tile,
                Buttons.LEFT_RELEASE,
            ),
        )
        val expected = oracle.pointsSized(base.state, points)
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("inert save", expected.state.state, midend.serialise().serialise())
        assertEquals("inert moves", 0, SgtpSave.parse(expected.state.state).values("MOVE").size)
    }

    @Test
    fun `real right drags match the oracle at several tile sizes`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("galaxies", "7x7dn", SEED)
            for (tile in listOf(48, 63, 82, 100)) {
                val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
                val board = Galaxies.computeSize(Galaxies.decodeParams("7x7dn"), tile)
                val points = firstDrag(base.state, tile)
                val expected = oracle.pointsSized(
                    base.state,
                    points,
                    board.width,
                    board.height,
                )
                for (p in points) midend.processKey(p.x, p.y, p.button, tile)
                assertEquals(
                    "tile $tile drag",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                val move = SgtpSave.parse(expected.state.state).values("MOVE").last()
                assertTrue("tile $tile move '$move'", move.startsWith("A"))
            }
        }

    @Test
    fun `a drag off the associated tile removes the arrow`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val aux = Midend.fromSave(Galaxies, SgtpSave.parse(base.state)).auxInfo!!
        val association = aux.split(';').first { it.startsWith("a") }.substring(1)
            .split(',').map { it.toInt() }
        val associated = appendMove(base.state, "A${association[0]},${association[1]},${association[2]},${association[3]}")
        val tile = 63
        val board = Galaxies.computeSize(Galaxies.decodeParams("7x7dn"), tile)
        val x = association[0]
        val y = association[1]
        val points = listOf(
            OracleClient.Point(
                x * tile / 2 + tile,
                y * tile / 2 + tile,
                Buttons.RIGHT_BUTTON,
            ),
            OracleClient.Point(
                x * tile / 2 + tile,
                y * tile / 2 + tile,
                Buttons.RIGHT_DRAG,
            ),
            // Release in the margin, outside the board.
            OracleClient.Point(0, 0, Buttons.RIGHT_RELEASE),
        )
        val expected = oracle.pointsSized(associated, points, board.width, board.height)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(associated))
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("unassociate save", expected.state.state, midend.serialise().serialise())
        assertEquals(
            "unassociate move",
            "U$x,$y",
            SgtpSave.parse(expected.state.state).values("MOVE").last(),
        )
    }

    @Test
    fun `inert pointer shapes change nothing`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val tile = 48
        val cases = mapOf(
            "right press on a tile" to listOf(
                OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.RIGHT_BUTTON),
                OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.RIGHT_RELEASE),
            ),
            "release without a press" to listOf(
                OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.RIGHT_RELEASE),
            ),
            "drag without a press" to listOf(
                OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.RIGHT_DRAG),
            ),
            "middle click" to listOf(
                OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.MIDDLE_BUTTON),
                OracleClient.Point(tile + tile / 2, tile + tile / 2, Buttons.MIDDLE_RELEASE),
            ),
        )
        for ((name, points) in cases) {
            val expected = oracle.pointsSized(base.state, points)
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
            for (p in points) midend.processKey(p.x, p.y, p.button, tile)
            assertEquals("$name save", expected.state.state, midend.serialise().serialise())
            assertEquals("$name moves", 0, SgtpSave.parse(expected.state.state).values("MOVE").size)
        }
    }

    @Test
    fun `a modifier on a click is stripped`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val tile = 32
        val points = listOf(
            OracleClient.Point(2 * tile, tile + tile / 2, Buttons.MOD_SHFT or Buttons.LEFT_BUTTON),
            OracleClient.Point(2 * tile, tile + tile / 2, Buttons.MOD_CTRL or Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(base.state, points)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("modifier save", expected.state.state, midend.serialise().serialise())
        assertEquals(
            "modifier move",
            listOf("E2,1"),
            SgtpSave.parse(expected.state.state).values("MOVE"),
        )
    }

    @Test
    fun `the keyboard model matches the oracle`() = OracleClient().use { oracle ->
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, 27),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, '\b'.code),
            // The hint key is a move of its own.
            listOf('h'.code),
            listOf('H'.code),
            // The shortcuts undo, redo, and solve.
            listOf('h'.code, 'u'.code, 'r'.code),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
            List(20) { Buttons.CURSOR_UP },
        )
        for (params in listOf("7x7dn", "10x10dn")) {
            val base = oracle.newGame("galaxies", params, SEED)
            for (sequence in sequences) {
                val expected = oracle.keys(base.state, sequence)
                val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
                for (key in sequence) midend.processKey(0, 0, key, 32)
                assertEquals(
                    "$params ${sequence.size} keys",
                    expected.state,
                    midend.serialise().serialise(),
                )
            }
        }
    }

    @Test
    fun `undo and redo after a real click match the oracle`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("galaxies", "7x7dn", SEED)
            val tile = 63
            val board = Galaxies.computeSize(Galaxies.decodeParams("7x7dn"), tile)
            val points = listOf(
                OracleClient.Point(2 * tile, tile + tile / 2, Buttons.LEFT_BUTTON),
                OracleClient.Point(2 * tile, tile + tile / 2, Buttons.LEFT_RELEASE),
            )
            val clicked = oracle.pointsSized(base.state, points, board.width, board.height)
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
            for (p in points) midend.processKey(p.x, p.y, p.button, tile)
            assertEquals("clicked", clicked.state.state, midend.serialise().serialise())

            val undone = oracle.undo(clicked.state.state)
            assertTrue(midend.undo())
            assertEquals("undo", undone.state, midend.serialise().serialise())
            assertFalse(midend.canUndo)
            assertTrue(midend.canRedo)
            val redone = oracle.redo(undone.state)
            assertTrue(midend.redo())
            assertEquals("redo", redone.state, midend.serialise().serialise())
            assertTrue(midend.canUndo)
            assertFalse(midend.canRedo)
        }

    @Test
    fun `a real click completes the game at a non-default tile`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("galaxies", "7x7dn", SEED)
            val aux = Midend.fromSave(Galaxies, SgtpSave.parse(base.state)).auxInfo!!
            val edges = aux.split(';').filter { it.startsWith("E") }
            val setup = edges.dropLast(1).fold(base.state) { text, move ->
                appendMove(text, move)
            }
            assertEquals("C is open", 0, oracle.id(setup).status)
            val tile = 63
            val board = Galaxies.computeSize(Galaxies.decodeParams("7x7dn"), tile)
            val (x, y) = edgeMidpoint(edges.last(), tile)
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(setup, points, board.width, board.height)
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(setup))
            assertFalse("no event yet", midend.takeCompletedEvent())
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, tile))
            assertEquals(KeyOutcome.Unused, midend.processKey(x, y, Buttons.LEFT_RELEASE, tile))
            assertEquals("win", 1, midend.status())
            assertTrue("event", midend.takeCompletedEvent())
            assertFalse("one event", midend.takeCompletedEvent())
            assertEquals("completed save", expected.state.state, midend.serialise().serialise())
        }

    @Test
    fun `the post-click frame is not vacuous`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val tile = 48
        val board = Galaxies.computeSize(Galaxies.decodeParams("7x7dn"), tile)
        val points = listOf(
            OracleClient.Point(2 * tile, tile + tile / 2, Buttons.LEFT_BUTTON),
            OracleClient.Point(2 * tile, tile + tile / 2, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(
            base.state,
            points,
            board.width,
            board.height,
            recordTrace = true,
        )
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, tile),
            upstream,
        )
        val expectedTrace = TraceNormalizer.normalize(expected.trace!!, upstream)
        val e = expectedTrace.lines()
        val a = actual.lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError("post-click frame line ${i + 1}:\nE ${e[i]}\nA ${a[i]}")
            }
        }
        assertEquals("post-click line count", e.size, a.size)
        assertTrue("post-click circles", a.count { it.startsWith("circle ") } > 0)
        assertTrue("post-click edges", a.count { it.endsWith(" 6") } > 0)
        assertTrue("post-click updates", a.count { it.startsWith("update ") } > 2)
    }

    private fun firstDrag(save: String, tile: Int): List<OracleClient.Point> {
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
        val aux = midend.auxInfo!!
        for (token in aux.split(';')) {
            if (!token.startsWith("a")) continue
            val parts = token.substring(1).split(',').map { it.toInt() }
            val x = parts[0]
            val y = parts[1]
            val dx = parts[2]
            val dy = parts[3]
            val pressX = dx * tile / 2 + tile
            val pressY = dy * tile / 2 + tile
            val releaseX = tile + ((x - 1) / 2) * tile + tile / 2
            val releaseY = tile + ((y - 1) / 2) * tile + tile / 2
            val points = listOf(
                OracleClient.Point(pressX, pressY, Buttons.RIGHT_BUTTON),
                OracleClient.Point(
                    (pressX + releaseX) / 2,
                    (pressY + releaseY) / 2,
                    Buttons.RIGHT_DRAG,
                ),
                OracleClient.Point(releaseX, releaseY, Buttons.RIGHT_RELEASE),
            )
            val probe = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            for (p in points) probe.processKey(p.x, p.y, p.button, tile)
            if (probe.canUndo) return points
        }
        error("no association of the stored answer makes a drag move")
    }

    /** The view point of an `E<x>,<y>` edge token. */
    private fun edgeMidpoint(move: String, tile: Int): Pair<Int, Int> {
        val parts = move.substring(1).split(',').map { it.toInt() }
        val x = parts[0]
        val y = parts[1]
        return (x * tile / 2 + tile) to (y * tile / 2 + tile)
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "MOVE    :${move.length}:$move\n"
    }

    private companion object {
        const val SEED = "12345"
    }
}
