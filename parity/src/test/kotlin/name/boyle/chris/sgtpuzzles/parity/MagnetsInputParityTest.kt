package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and `ui`. Game: magnets.
 *
 * Layout at the exact board sizes and at a letterboxed viewport, real
 * press/release clicks on a cell and a clue at tiles 48, 63, 82, and
 * 100 and in the 411x702 view, the `FROMCOORD` cell and clue bands, the
 * left and right cycles, cursor keys with and without modifiers, the
 * two select buttons, the keys that do nothing, and the vacuity guards.
 */
class MagnetsInputParityTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun midend(save: String): Midend =
        Midend.fromSave(Magnets, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun corpus(params: String): String =
        File(testdata, "saves/initial/magnets/$params.sgtp").readText()

    @Test
    fun `layout picks the exact tiles and a letterbox origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("magnets", "6x5dt", SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val size = Magnets.computeSize(Magnets.decodeParams("6x5dt"), tile)
                val expected = oracle.layout(save, size.width, size.height)
                val geometry = midend(save).geometryFor(size.width, size.height)
                assertEquals("$tile tile", tile, geometry.tileSize)
                assertEquals("$tile width", expected.width, geometry.width)
                assertEquals("$tile height", expected.height, geometry.height)
                assertEquals("$tile origin x", 0, geometry.originX)
                assertEquals("$tile origin y", 0, geometry.originY)
            }

            // Odd tiles truncate the border separately.
            assertEquals(396, Magnets.computeSize(Magnets.decodeParams("6x5dt"), 48).width)
            assertEquals(518, Magnets.computeSize(Magnets.decodeParams("6x5dt"), 63).width)
            assertEquals(348, Magnets.computeSize(Magnets.decodeParams("6x5dt"), 48).height)

            val geometry = midend(save).geometryFor(411, 702)
            val layout = oracle.layout(save, 411, 702)
            assertEquals(49, geometry.tileSize)
            assertEquals(layout.width, geometry.width)
            assertEquals(layout.height, geometry.height)
            assertEquals((411 - geometry.width) / 2, geometry.originX)
            assertEquals((702 - geometry.height) / 2, geometry.originY)
        }

    @Test
    fun `a real click at four tiles and in the letterbox matches the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("magnets", "6x5dt", SEED).state

            for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON)) {
                for (tile in listOf(48, 63, 82, 100)) {
                    val size = Magnets.computeSize(Magnets.decodeParams("6x5dt"), tile)
                    val points = cellClick(0, 0, tile, 0, 0, button)
                    val expected = oracle.pointsSized(
                        save,
                        points,
                        size.width,
                        size.height,
                    ).state
                    val actual = midend(save)
                    val before = actual.state as Magnets.State
                    val beforeGrid = before.grid.copyOf()
                    val beforeFlags = before.flags.copyOf()
                    val beforeCounts = before.countsDone.copyOf()
                    val press = actual.processKey(points[0].x, points[0].y, points[0].button, tile)
                    assertEquals("$button tile $tile outcome", KeyOutcome.SomeEffect, press)
                    actual.processKey(points[1].x, points[1].y, points[1].button, tile)
                    assertSame("$button tile $tile", expected, actual)
                    val after = actual.state as Magnets.State
                    assertTrue(
                        "$button tile $tile changes the grid or the flags",
                        !beforeGrid.contentEquals(after.grid) ||
                            !beforeFlags.contentEquals(after.flags),
                    )
                    assertTrue(
                        "$button tile $tile keeps the clue shading",
                        beforeCounts.contentEquals(after.countsDone),
                    )
                    assertEquals("$button tile $tile move", 1, actual.serialise().values("MOVE").size)
                    assertEquals(2, actual.stateCount)
                }
            }

            // The 411x702 letterbox.
            val geometry = midend(save).geometryFor(411, 702)
            for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON)) {
                val points = cellClick(
                    2,
                    2,
                    geometry.tileSize,
                    geometry.originX,
                    geometry.originY,
                    button,
                )
                val expected = oracle.pointsSized(save, points, 411, 702).state
                val actual = midend(save)
                val before = actual.state as Magnets.State
                val beforeGrid = before.grid.copyOf()
                val beforeFlags = before.flags.copyOf()
                val beforeCounts = before.countsDone.copyOf()
                for ((i, point) in points.withIndex()) {
                    val gameX = point.x - geometry.originX
                    val gameY = point.y - geometry.originY
                    val outcome = actual.processKey(gameX, gameY, point.button, geometry.tileSize)
                    if (i == 0) assertEquals("letterbox outcome", KeyOutcome.SomeEffect, outcome)
                }
                assertSame("letterbox $button", expected, actual)
                val after = actual.state as Magnets.State
                assertTrue(
                    "letterbox $button changes the grid or the flags",
                    !beforeGrid.contentEquals(after.grid) ||
                        !beforeFlags.contentEquals(after.flags),
                )
                assertTrue(
                    "letterbox $button keeps the clue shading",
                    beforeCounts.contentEquals(after.countsDone),
                )
                assertEquals(1, actual.serialise().values("MOVE").size)
                assertEquals(2, actual.stateCount)
            }
        }

    @Test
    fun `a press drag and release at four tiles makes one move at the press`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("magnets", "6x5dt", SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val size = Magnets.computeSize(Magnets.decodeParams("6x5dt"), tile)
                val points = listOf(
                    cellPoint(0, 0, tile, 0, 0, Buttons.LEFT_BUTTON),
                    cellPoint(2, 0, tile, 0, 0, Buttons.LEFT_DRAG),
                    cellPoint(2, 0, tile, 0, 0, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    size.width,
                    size.height,
                ).state
                val actual = midend(save)
                for (point in points) {
                    actual.processKey(point.x, point.y, point.button, tile)
                }
                assertSame("drag tile $tile", expected, actual)
                assertEquals("drag tile $tile move", listOf("+0,0"), actual.serialise().values("MOVE"))
                assertEquals("drag tile $tile count", 2, actual.stateCount)
            }
        }

    @Test
    fun `a clue click at four tiles and in the letterbox matches the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("magnets", "6x5dt", SEED).state
            for (tile in listOf(48, 63, 82, 100)) {
                val size = Magnets.computeSize(Magnets.decodeParams("6x5dt"), tile)
                val points = clueClickTop(0, tile, 0, 0)
                val expected = oracle.pointsSized(save, points, size.width, size.height).state
                val actual = midend(save)
                val before = actual.state as Magnets.State
                val beforeGrid = before.grid.copyOf()
                val beforeCounts = before.countsDone.copyOf()
                for (point in points) actual.processKey(point.x, point.y, point.button, tile)
                assertSame("clue tile $tile", expected, actual)
                val after = actual.state as Magnets.State
                assertTrue("clue tile $tile keeps the grid", beforeGrid.contentEquals(after.grid))
                assertFalse("clue tile $tile changes the shading", beforeCounts.contentEquals(after.countsDone))
                assertEquals("clue tile $tile move", listOf("D0,-1"), actual.serialise().values("MOVE"))
                assertEquals("clue tile $tile count", 2, actual.stateCount)
            }

            val geometry = midend(save).geometryFor(411, 702)
            val points = clueClickTop(0, geometry.tileSize, geometry.originX, geometry.originY)
            val expected = oracle.pointsSized(save, points, 411, 702).state
            val actual = midend(save)
            val before = actual.state as Magnets.State
            val beforeGrid = before.grid.copyOf()
            val beforeCounts = before.countsDone.copyOf()
            for (point in points) {
                actual.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("clue letterbox", expected, actual)
            val after = actual.state as Magnets.State
            assertTrue("clue letterbox keeps the grid", beforeGrid.contentEquals(after.grid))
            assertFalse("clue letterbox changes the shading", beforeCounts.contentEquals(after.countsDone))
            assertEquals("clue letterbox move", listOf("D0,-1"), actual.serialise().values("MOVE"))
            assertEquals("clue letterbox count", 2, actual.stateCount)
        }

    @Test
    fun `the cell bands follow FROMCOORD`() = OracleClient().use { oracle ->
        // The top-left cell starts after the border: x = 36..67 at
        // tile 32. The last band, x = 228..259, is the right clue.
        val save = oracle.newGame("magnets", "6x5dt", SEED).state
        for ((x, y, move) in listOf(
            Triple(36, 36, "+0,0"),
            Triple(52, 36, "+0,0"),
            Triple(68, 52, "+1,0"),
            Triple(35, 52, "D-1,0"),
        )) {
            val points = listOf(
                OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points).state
            val actual = midend(save)
            for (point in points) actual.processKey(point.x, point.y, point.button, TILE)
            assertSame("($x,$y)", expected, actual)
            assertEquals("($x,$y)", listOf(move), actual.serialise().values("MOVE"))
        }

        // Outside the board and not a clue band: Unused, no move.
        // Below the left/top border the truncation still names the clue
        // band (x - BORDER is negative but divides to zero toward zero),
        // so only the far side and the far corners are out of bounds.
        for ((x, y) in listOf(272 to 52, 52 to 272, 400 to 52, 52 to 400, 15 to 15)) {
            val actual = midend(save)
            assertEquals("($x,$y) press", KeyOutcome.Unused,
                actual.processKey(x, y, Buttons.LEFT_BUTTON, TILE))
            assertEquals("($x,$y) release", KeyOutcome.Unused,
                actual.processKey(x, y, Buttons.LEFT_RELEASE, TILE))
            assertEquals(1, actual.stateCount)
            assertTrue(actual.serialise().values("MOVE").isEmpty())
        }
    }

    @Test
    fun `a middle press, a corner and a drag are no-ops`() = OracleClient().use { oracle ->
        val save = oracle.newGame("magnets", "6x5dt", SEED).state
        val sequences = listOf(
            // Middle press and release.
            listOf(
                OracleClient.Point(52, 52, Buttons.MIDDLE_BUTTON),
                OracleClient.Point(52, 52, Buttons.MIDDLE_RELEASE),
            ),
            // A corner click, inside the border but not a clue.
            listOf(
                OracleClient.Point(20, 20, Buttons.LEFT_BUTTON),
                OracleClient.Point(20, 20, Buttons.LEFT_RELEASE),
            ),
            // A press, a drag, and a release: the press is the only move.
            listOf(
                OracleClient.Point(52, 52, Buttons.LEFT_BUTTON),
                OracleClient.Point(120, 52, Buttons.LEFT_DRAG),
                OracleClient.Point(120, 52, Buttons.LEFT_RELEASE),
            ),
            // A lone drag and a lone release.
            listOf(OracleClient.Point(120, 52, Buttons.LEFT_DRAG)),
            listOf(OracleClient.Point(120, 52, Buttons.LEFT_RELEASE)),
        )
        for ((index, points) in sequences.withIndex()) {
            val expected = oracle.pointsSized(save, points).state
            val actual = midend(save)
            val before = actual.state as Magnets.State
            val beforeGrid = before.grid.copyOf()
            val beforeFlags = before.flags.copyOf()
            val beforeCounts = before.countsDone.copyOf()
            for (point in points) actual.processKey(point.x, point.y, point.button, TILE)
            assertSame("sequence $index", expected, actual)
            val after = actual.state as Magnets.State
            val moves = actual.serialise().values("MOVE")
            if (index == 2) {
                assertEquals("the press is one move", 1, moves.size)
                assertEquals(2, actual.stateCount)
            } else {
                assertTrue("no move", moves.isEmpty())
                assertTrue("grid stays", beforeGrid.contentEquals(after.grid))
                assertTrue("flags stay", beforeFlags.contentEquals(after.flags))
                assertTrue("counts stay", beforeCounts.contentEquals(after.countsDone))
                assertEquals(1, actual.stateCount)
            }
        }
    }

    @Test
    fun `the keys that do nothing stay unused`() = OracleClient().use { oracle ->
        val save = oracle.newGame("magnets", "6x5dt", SEED).state
        for (key in listOf(
            0x30, // '0'
            0x31, // '1'
            'a'.code,
            'z'.code,
            '\t'.code,
            '\b'.code,
            0x7F,
            Buttons.MOD_NUM_KEYPAD or '1'.code,
        )) {
            val actual = midend(save)
            assertEquals(
                "key $key",
                KeyOutcome.Unused,
                actual.processKey(0, 0, key, TILE),
            )
            assertEquals(1, actual.stateCount)
        }

        // A modified cursor key is not a cursor move in magnets: the
        // exact-button check leaves it unused. A plain one reveals the
        // cursor.
        for (modified in listOf(
            Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
            Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
        )) {
            val actual = midend(save)
            assertEquals("$modified", KeyOutcome.Unused, actual.processKey(0, 0, modified, TILE))
            assertEquals(1, actual.stateCount)
        }
        val plain = midend(save)
        assertEquals(
            KeyOutcome.SomeEffect,
            plain.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE),
        )
        assertEquals(1, plain.stateCount)
    }

    @Test
    fun `cursor sequences with and without modifiers match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("magnets", "6x5dt", SEED).state
            val sequences = listOf(
                listOf(Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_LEFT),
                listOf(Buttons.CURSOR_UP),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
                listOf(Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val actual = midend(save)
                for (key in sequence) actual.processKey(0, 0, key, TILE)
                assertSame("keys $sequence", expected, actual)
            }
        }

    private fun cellPoint(
        x: Int,
        y: Int,
        tile: Int,
        originX: Int,
        originY: Int,
        button: Int,
    ): OracleClient.Point {
        val px = originX + (x + 1) * tile + tile / 8 + tile / 2
        val py = originY + (y + 1) * tile + tile / 8 + tile / 2
        return OracleClient.Point(px, py, button)
    }

    private fun cellClick(
        x: Int,
        y: Int,
        tile: Int,
        originX: Int,
        originY: Int,
        button: Int,
    ): List<OracleClient.Point> {
        val px = originX + (x + 1) * tile + tile / 8 + tile / 2
        val py = originY + (y + 1) * tile + tile / 8 + tile / 2
        val release = if (button == Buttons.LEFT_BUTTON) {
            Buttons.LEFT_RELEASE
        } else {
            Buttons.RIGHT_RELEASE
        }
        return listOf(
            OracleClient.Point(px, py, button),
            OracleClient.Point(px, py, release),
        )
    }

    private fun clueClickTop(
        x: Int,
        tile: Int,
        originX: Int,
        originY: Int,
    ): List<OracleClient.Point> {
        val px = originX + (x + 1) * tile + tile / 8 + tile / 2
        val py = originY + tile / 8 + tile / 2
        return listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
    }
}
