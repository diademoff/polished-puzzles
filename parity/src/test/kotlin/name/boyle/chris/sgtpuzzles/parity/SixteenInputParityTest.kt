package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Size
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: sixteen.
 *
 * The layout at the viewports from work/specs/sixteen.md, a pointer
 * click on every frame band, the keyboard ring, and the modified key
 * path are compared with the oracle.
 *
 * C's midend rejects `CTRL` on a key that is not a cursor move before
 * the game sees it, so there is no oracle result for `CTRL` + digit or
 * `CTRL` + click. The engine test pins those cases.
 */
class SixteenInputParityTest {

    @Test
    fun `the layout matches the oracle at every viewport`() = OracleClient().use { oracle ->
        val cases = listOf(
            Layout("4x4", 411, 702, 68, 1, 147),
            Layout("4x4", 720, 1280, 120, 0, 280),
            Layout("4x4", 1024, 600, 100, 212, 0),
            Layout("4x4", 200, 200, 33, 1, 1),
            Layout("4x4", 288, 288, 48, 0, 0),
            Layout("4x4", 380, 380, 63, 1, 1),
            Layout("4x4", 494, 494, 82, 1, 1),
            Layout("4x4", 602, 602, 100, 1, 1),
            Layout("4x4", 1080, 1920, 180, 0, 420),
            Layout("4x3", 411, 702, 68, 1, 181),
            Layout("4x3", 1024, 600, 120, 152, 0),
        )

        for (case in cases) {
            val save = oracle.newGame("sixteen", case.params, SEED)
            val expected = oracle.layout(save.state, case.viewWidth, case.viewHeight)
            val geometry = Midend.newGame(Sixteen, case.params, SEED)
                .geometryFor(case.viewWidth, case.viewHeight)
            val where = "${case.params} in ${case.viewWidth} x ${case.viewHeight}"

            assertEquals("tile $where", case.tile, geometry.tileSize)
            assertEquals("board width $where", expected.width, geometry.width)
            assertEquals("board height $where", expected.height, geometry.height)
            assertEquals("origin x $where", case.originX, geometry.originX)
            assertEquals("origin y $where", case.originY, geometry.originY)
            assertEquals(
                "the board is centred $where",
                (case.viewWidth - expected.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "the board is centred $where",
                (case.viewHeight - expected.height) / 2,
                geometry.originY,
            )
        }

        // A board that exactly fits and one that is one pixel short.
        val exact = Midend.newGame(Sixteen, "4x4", SEED)
        assertEquals(48, exact.largestTileFor(288, 288))
        assertEquals(47, exact.largestTileFor(287, 287))
        assertEquals(Size(282, 282), exact.game.computeSize(exact.params, 47))
    }

    @Test
    fun `a modified click matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("sixteen", "4x4", SEED)
        val probe = Midend.newGame(Sixteen, "4x4", SEED)
        val geometry = probe.geometryFor(411, 702)
        val tile = geometry.tileSize

        // The left border band of row 0.
        val viewX = 31
        val viewY = 215
        val plain = oracle.keySized(
            save.state, viewX, viewY, Buttons.LEFT_BUTTON, 411, 702,
        )
        assertEquals("R0,-1", SgtpSave.parse(plain.state).values("MOVE").single())

        // The C midend strips both modifiers from a click.
        for (modifier in listOf(Buttons.MOD_SHFT, Buttons.MOD_NUM_KEYPAD)) {
            val button = Buttons.LEFT_BUTTON or modifier
            val expected = oracle.keySized(save.state, viewX, viewY, button, 411, 702)
            assertEquals(
                "the oracle ignores modifier $modifier on a click",
                plain.state,
                expected.state,
            )

            val actual = Midend.newGame(Sixteen, "4x4", SEED)
            assertEquals(
                KeyOutcome.SomeEffect,
                actual.processKey(
                    geometry.toGameX(viewX),
                    geometry.toGameY(viewY),
                    button,
                    tile,
                ),
            )
            assertEquals("save with modifier $modifier", expected.state, actual.serialise().serialise())
            assertEquals("id with modifier $modifier", expected.id, actual.id())
            assertTrue(actual.canUndo)
        }
    }

    @Test
    fun `the modified cursor keys match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("sixteen", "4x4", SEED)
        val cases = listOf(
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_LEFT or Buttons.MOD_SHFT),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_DOWN or Buttons.MOD_CTRL),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_DOWN or Buttons.MOD_SHFT),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_SELECT,
                Buttons.MOD_NUM_KEYPAD or '8'.code,
            ),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_SELECT2,
                Buttons.MOD_NUM_KEYPAD or '2'.code,
            ),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT or Buttons.MOD_CTRL),
            listOf(Buttons.CURSOR_UP, Buttons.MOD_NUM_KEYPAD or '8'.code),
        )

        for (sequence in cases) {
            val expected = oracle.keys(save.state, sequence)
            val actual = Midend.newGame(Sixteen, "4x4", SEED)
            for (key in sequence) {
                actual.processKey(0, 0, key, Sixteen.preferredTileSize)
            }
            assertEquals("save for $sequence", expected.state, actual.serialise().serialise())
            assertEquals("id for $sequence", expected.id, actual.id())
            assertEquals("status for $sequence", expected.status, actual.status())
            assertEquals("undo for $sequence", expected.canUndo, actual.canUndo)
            assertEquals("redo for $sequence", expected.canRedo, actual.canRedo)
        }

        // The direction of a modified key is not always the plain one:
        // with `lock_tile`, shift slides without moving the cursor and
        // control moves the cursor with the tile.
        val shiftActual = Midend.newGame(Sixteen, "4x4", SEED)
        for (key in cases[0]) shiftActual.processKey(0, 0, key, Sixteen.preferredTileSize)
        assertEquals("R0,-1", shiftActual.serialise().values("MOVE").single())
        assertEquals(0, (shiftActual.ui as Sixteen.Ui).curY)

        val controlActual = Midend.newGame(Sixteen, "4x4", SEED)
        for (key in cases[1]) controlActual.processKey(0, 0, key, Sixteen.preferredTileSize)
        assertEquals("C0,1", controlActual.serialise().values("MOVE").single())
        assertEquals(1, (controlActual.ui as Sixteen.Ui).curY)
    }

    private data class Layout(
        val params: String,
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private companion object {
        const val SEED = "12345"
    }
}
