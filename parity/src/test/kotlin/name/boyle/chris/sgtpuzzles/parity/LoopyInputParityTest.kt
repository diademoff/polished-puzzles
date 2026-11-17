package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and ui. Game: loopy.
 *
 * Layout, real clicks at several tile sizes, the letterbox, a drag that
 * upstream ignores, the keyboard cursor, both select buttons, and the
 * auto-follow preferences. The click and key sequences are replayed by
 * the C midend, so every comparison includes the game UI that a save
 * does not carry.
 */
class LoopyInputParityTest {

    @Test
    fun `layout matches the oracle at every viewport and preset`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702, 781 to 781, 1080 to 1920, 320 to 480, 240 to 320,
        )
        for (preset in Loopy.presets()) {
            val midend = Midend.newGame(Loopy, preset.params, SEED)
            val save = midend.serialise().serialise()
            for ((w, h) in viewports) {
                val expected = oracle.layout(save, w, h)
                val geometry = midend.geometryFor(w, h)
                assertEquals(
                    "${preset.params} $w x $h width",
                    expected.width,
                    geometry.width,
                )
                assertEquals(
                    "${preset.params} $w x $h height",
                    expected.height,
                    geometry.height,
                )
            }
        }
    }

    @Test
    fun `a real click matches the oracle for every preset and tile size`() =
        OracleClient().use { oracle ->
            for (preset in Loopy.presets()) {
                val base = oracle.newGame("loopy", preset.params, SEED)
                for (tile in listOf(24, 32, 48, 63)) {
                    val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
                    val (x, y, index) = firstEdgePoint(midend, tile)
                    val board = Loopy.computeSize(Loopy.decodeParams(preset.params), tile)
                    val points = listOf(
                        OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                        OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                    )
                    val expected = oracle.pointsSized(
                        base.state,
                        points,
                        board.width,
                        board.height,
                    )
                    for (p in points) midend.processKey(p.x, p.y, p.button, tile)
                    assertEquals(
                        "${preset.params} tile $tile",
                        expected.state.state,
                        midend.serialise().serialise(),
                    )
                    val moves = SgtpSave.parse(expected.state.state).values("MOVE")
                    assertEquals("${preset.params} tile $tile moves", 1, moves.size)
                    assertEquals("${preset.params} tile $tile edge", "$index", moves[0].dropLast(1))
                    assertEquals("${preset.params} tile $tile first", 'y', moves[0].last())
                }
            }
        }

    @Test
    fun `a letterboxed click matches the oracle`() = OracleClient().use { oracle ->
        for (preset in listOf("7x7t0de", "5x5t7dh", "6x6t16dh", "3x2t13dh")) {
            val base = oracle.newGame("loopy", preset, SEED)
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            val geometry = midend.geometryFor(411, 702)
            val (gx, gy, index) = firstEdgePoint(midend, geometry.tileSize)
            val x = gx + geometry.originX
            val y = gy + geometry.originY
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(base.state, points, 411, 702)
            // The C points are view coordinates; the midend takes board
            // coordinates, as the app adapter subtracts the origin first.
            midend.processKey(gx, gy, Buttons.LEFT_BUTTON, geometry.tileSize)
            midend.processKey(gx, gy, Buttons.LEFT_RELEASE, geometry.tileSize)
            assertEquals("$preset letterbox", expected.state.state, midend.serialise().serialise())
            val moves = SgtpSave.parse(expected.state.state).values("MOVE")
            assertEquals("$preset letterbox moves", listOf("${index}y"), moves)
        }
    }

    @Test
    fun `a drag changes nothing in C and in the port`() = OracleClient().use { oracle ->
        for (preset in listOf("7x7t0de", "5x5t7dh", "6x6t16dh")) {
            val base = oracle.newGame("loopy", preset, SEED)
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            val geometry = midend.geometryFor(411, 702)
            val (gx, gy, index) = firstEdgePoint(midend, geometry.tileSize)
            val x0 = gx + geometry.originX
            val y0 = gy + geometry.originY
            val x1 = x0 + 4 * geometry.tileSize
            val y1 = y0 + 3 * geometry.tileSize

            midend.processKey(gx, gy, Buttons.LEFT_BUTTON, geometry.tileSize)
            val steps = 4
            for (i in 1..steps) {
                midend.processKey(
                    gx + (x1 - x0) * i / steps,
                    gy + (y1 - y0) * i / steps,
                    Buttons.LEFT_DRAG,
                    geometry.tileSize,
                )
            }
            midend.processKey(
                gx + (x1 - x0),
                gy + (y1 - y0),
                Buttons.LEFT_RELEASE,
                geometry.tileSize,
            )
            val expected = oracle.gestureSized(base.state, x0, y0, x1, y1, steps, 411, 702)
            assertEquals("$preset drag", expected.state, midend.serialise().serialise())
            val moves = SgtpSave.parse(expected.state).values("MOVE")
            assertEquals("$preset drag only the press", 1, moves.size)
            assertEquals("$preset drag press edge", "${index}y", moves[0])
            assertEquals("$preset drag id", base.id, midend.id())
        }
    }

    @Test
    fun `the keyboard cursor matches the oracle for every preset`() = OracleClient().use { oracle ->
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT2,
            Buttons.CURSOR_SELECT2,
            Buttons.CURSOR_SELECT2,
        )
        for (preset in Loopy.presets()) {
            val base = oracle.newGame("loopy", preset.params, SEED)
            val expected = oracle.keys(base.state, sequence)
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            val outcomes = sequence.map { midend.processKey(0, 0, it, 32) }
            assertEquals("${preset.params} keys save", expected.state, midend.serialise().serialise())
            assertEquals("${preset.params} keys id", base.id, midend.id())
            assertTrue(
                "${preset.params} cursor moved",
                outcomes.take(4).any { it == KeyOutcome.SomeEffect },
            )
            assertEquals(
                "${preset.params} three cycles",
                6,
                SgtpSave.parse(expected.state).values("MOVE").size,
            )
        }
    }

    @Test
    fun `auto-follow matches the oracle in both modes`() = OracleClient().use { oracle ->
        // The walk ends at the top-left corner, where each endpoint has
        // exactly one other edge, so both auto-follow modes extend the
        // move. The plain run leaves a one-edge move.
        val sequence = List(20) { Buttons.CURSOR_LEFT } +
            List(20) { Buttons.CURSOR_UP } +
            listOf(Buttons.CURSOR_SELECT)
        val modes = mapOf(
            "auto-follow=fixed" to Loopy.AutoFollow.FIXED,
            "auto-follow=adaptive" to Loopy.AutoFollow.ADAPTIVE,
        )
        for (preset in listOf("7x7t0de", "5x5t7dh", "3x2t13dh")) {
            val base = oracle.newGame("loopy", preset, SEED)
            val plain = oracle.keys(base.state, sequence)
            for ((prefs, mode) in modes) {
                val expected = oracle.keys(base.state, sequence, prefs)
                val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
                (midend.ui as Loopy.Ui).autoFollow = mode
                for (key in sequence) midend.processKey(0, 0, key, 32)
                assertEquals(
                    "$preset $prefs save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                // The mode is visible: at least one select extended the
                // move, so the save carries more than the plain run.
                assertNotEquals("$preset $prefs differs", plain.state, expected.state)
            }
        }
    }

    @Test
    fun `the three states cycle through both select buttons`() = OracleClient().use { oracle ->
        val prefix = listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT)
        val selects = listOf(
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_SELECT2,
        )
        for (preset in listOf("7x7t0de", "5x5t7dh")) {
            val base = oracle.newGame("loopy", preset, SEED)
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            for (key in prefix) midend.processKey(0, 0, key, 32)
            val values = mutableListOf<Char>()
            for ((i, select) in selects.withIndex()) {
                // The cursor is frontend state, so every comparison
                // replays the whole prefix from the same loaded save.
                val expected = oracle.keys(base.state, prefix + selects.take(i + 1))
                midend.processKey(0, 0, select, 32)
                assertEquals("$preset select $i", expected.state, midend.serialise().serialise())
                values += SgtpSave.parse(expected.state).values("MOVE").last().last()
            }
            // CURSOR_SELECT toggles yes and unknown; it never sets no.
            assertEquals("$preset cycle values", listOf('y', 'u', 'y', 'u'), values)
            assertEquals(
                "$preset same edge",
                1,
                SgtpSave.parse(oracle.keys(base.state, prefix + selects).state)
                    .values("MOVE").map { it.dropLast(1) }.toSet().size,
            )
        }
    }

    @Test
    fun `a modifier on a cursor move is unused`() = OracleClient().use { oracle ->
        val base = oracle.newGame("loopy", "7x7t0de", SEED)
        val sequence = listOf(Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT)
        val expected = oracle.keys(base.state, sequence)
        val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
        val outcomes = sequence.map { midend.processKey(0, 0, it, 32) }
        assertEquals("modifier save", expected.state, midend.serialise().serialise())
        assertEquals("modified key", KeyOutcome.Unused, outcomes[0])
        assertEquals("plain key", KeyOutcome.SomeEffect, outcomes[1])
        assertTrue("no move", SgtpSave.parse(expected.state).values("MOVE").isEmpty())
    }

    private fun firstEdgePoint(midend: Midend, tile: Int): Triple<Int, Int, Int> {
        val state = midend.state as Loopy.State
        val g = state.grid
        val index = state.lines.indices.first()
        val e = g.edges[index]
        val gx = (e.dot1.x + e.dot2.x) / 2
        val gy = (e.dot1.y + e.dot2.y) / 2
        val border = tile / 2
        return Triple(
            border + (gx - g.lowestX) * tile / g.tileSize,
            border + (gy - g.lowestY) * tile / g.tileSize,
            index,
        )
    }

    private companion object {
        const val SEED = "12345"
    }
}
