package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: flip.
 *
 * The layout, the border bands, the letterbox, real clicks, drags, and
 * the cursor keys. The C midend strips modifiers from non-cursor keys
 * and rejects CTRL, so a modified select is a select while a modified
 * cursor key is unused.
 */
class FlipInputParityTest {

    @Test
    fun `the layout matches the oracle at several viewports`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                Quad("5x5c", 411, 702, 68, 1, 147),
                Quad("5x5c", 720, 1280, 120, 0, 280),
                Quad("5x5c", 1024, 600, 100, 212, 0),
                Quad("5x5c", 200, 200, 33, 1, 1),
                Quad("5x5c", 288, 288, 48, 0, 0),
                Quad("5x5c", 380, 380, 63, 1, 1),
                Quad("5x5c", 494, 494, 82, 1, 1),
                Quad("5x5c", 602, 602, 100, 1, 1),
                // One pixel short of an exact fit: the binary search
                // drops the tile by one and the origin is (3,3).
                Quad("5x5c", 287, 287, 47, 3, 3),
                Quad("4x4c", 411, 702, 82, 0, 146),
                Quad("4x4c", 288, 288, 57, 2, 2),
                Quad("4x4c", 380, 380, 76, 0, 0),
                Quad("4x4c", 494, 494, 99, 0, 0),
                Quad("3x3c", 411, 702, 103, 0, 145),
                Quad("3x3c", 200, 200, 50, 0, 0),
                Quad("3x3c", 380, 380, 95, 0, 0),
                Quad("3x3c", 494, 494, 123, 1, 1),
            )

            for (case in cases) {
                val midend = Midend.newGame(Flip, case.params, SEED)
                val geometry = midend.geometryFor(case.viewWidth, case.viewHeight)
                val layout = oracle.layout(midend.serialise().serialise(), case.viewWidth, case.viewHeight)

                assertEquals("tile for ${case.params} ${case.viewWidth}", case.tile, geometry.tileSize)
                assertEquals(
                    "width for ${case.params} ${case.viewWidth}",
                    layout.width,
                    geometry.width,
                )
                assertEquals(
                    "height for ${case.params} ${case.viewWidth}",
                    layout.height,
                    geometry.height,
                )
                assertEquals(
                    "origin x for ${case.params} ${case.viewWidth}",
                    case.originX,
                    geometry.originX,
                )
                assertEquals(
                    "origin y for ${case.params} ${case.viewWidth}",
                    case.originY,
                    geometry.originY,
                )
            }
        }

    @Test
    fun `border bands and the neighbouring cell match the oracle`() =
        OracleClient().use { oracle ->
            val params = "3x3c"
            val save = saveText(params, "d074191345d1644c17058,c90")
            val geometry = Midend.newGame(Flip, params, SEED).geometryFor(192, 192)
            assertEquals(48, geometry.tileSize)
            assertEquals(0, geometry.originX)
            assertEquals(0, geometry.originY)

            val clicks = listOf(
                Triple(24, 24, "M0,0"),
                Triple(167, 48, "M2,0"),
                Triple(24, 48, "M0,0"),
                Triple(72, 72, "M1,1"),
                Triple(0, 48, null),
                Triple(168, 48, null),
                Triple(48, 0, null),
                Triple(48, 168, null),
                Triple(0, 0, null),
                Triple(24, 192, null),
            )

            for ((x, y, move) in clicks) {
                val expected = oracle.keySized(save, x, y, Buttons.LEFT_BUTTON, 192, 192)
                val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
                val beforeText = actual.textFormat()
                val beforeMoves = (actual.state as Flip.State).moves
                val outcome = actual.processKey(
                    geometry.toGameX(x),
                    geometry.toGameY(y),
                    Buttons.LEFT_BUTTON,
                    geometry.tileSize,
                )

                if (move == null) {
                    assertEquals("no move at ($x,$y)", KeyOutcome.SomeEffect, outcome)
                    assertEquals("no record at ($x,$y)", emptyList<String>(), moves(expected.state))
                    assertEquals("no count at ($x,$y)", beforeMoves, (actual.state as Flip.State).moves)
                    assertEquals("no board change at ($x,$y)", beforeText, actual.textFormat())
                } else {
                    assertEquals("move at ($x,$y)", KeyOutcome.SomeEffect, outcome)
                    assertEquals("record at ($x,$y)", listOf(move), moves(expected.state))
                    assertEquals(
                        "one more move at ($x,$y)",
                        beforeMoves + 1,
                        (actual.state as Flip.State).moves,
                    )
                    assertTrue("board change at ($x,$y)", beforeText != actual.textFormat())
                }
                assertEquals("save at ($x,$y)", expected.state, actual.serialise().serialise())
                assertEquals("text at ($x,$y)", oracle.text(expected.state), actual.textFormat())
            }
        }

    @Test
    fun `a click in the letterbox is not a move`() = OracleClient().use { oracle ->
        val params = "3x3c"
        val save = saveText(params, "d074191345d1644c17058,c90")
        val viewWidth = 411
        val viewHeight = 702
        val geometry = Midend.newGame(Flip, params, SEED).geometryFor(viewWidth, viewHeight)
        assertEquals(103, geometry.tileSize)
        assertEquals(0, geometry.originX)
        assertEquals(145, geometry.originY)

        // The letterbox is above and below the board. The border band of
        // the board is another 51 pixels, so the first real click is at
        // origin + 51 + 1.
        val probes = listOf(
            Triple(200, 144, null),
            Triple(200, 145 + 50, null),
            Triple(200, 145 + 51, "M1,0"),
            Triple(200, 145 + 102, "M1,0"),
            Triple(200, 145 + 154, "M1,1"),
            Triple(200, 556, null),
        )

        for ((x, y, move) in probes) {
            val expected = oracle.keySized(save, x, y, Buttons.LEFT_BUTTON, viewWidth, viewHeight)
            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            val beforeText = actual.textFormat()
            val beforeMoves = (actual.state as Flip.State).moves
            val outcome = actual.processKey(
                geometry.toGameX(x),
                geometry.toGameY(y),
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            )
            assertEquals("outcome at ($x,$y)", KeyOutcome.SomeEffect, outcome)
            assertEquals("records at ($x,$y)", moves(expected.state), moves(actual.serialise().serialise()))
            if (move != null) {
                assertEquals("move at ($x,$y)", listOf(move), moves(actual.serialise().serialise()))
                assertEquals(
                    "one more move at ($x,$y)",
                    beforeMoves + 1,
                    (actual.state as Flip.State).moves,
                )
                assertTrue("board change at ($x,$y)", beforeText != actual.textFormat())
            } else {
                assertEquals("no count at ($x,$y)", beforeMoves, (actual.state as Flip.State).moves)
                assertEquals("no board change at ($x,$y)", beforeText, actual.textFormat())
            }
            assertEquals("save at ($x,$y)", expected.state, actual.serialise().serialise())
        }
    }

    @Test
    fun `real clicks at tiles 63, 82, and 100 change a cell`() =
        OracleClient().use { oracle ->
            val params = "5x5c"
            val desc = "c400007100001c4000071000018400043100011c400047100011c400046100010c400047100011c4000471000118400043100011c400047100011c400046100010c000047000011c0000470000118,16db6f0"
            val save = saveText(params, desc)
            val views = listOf(380 to 63, 494 to 82, 602 to 100)

            for ((view, tile) in views) {
                val geometry = Midend.newGame(Flip, params, SEED).geometryFor(view, view)
                assertEquals("tile for view $view", tile, geometry.tileSize)
                assertTrue(
                    "view $view has a letterbox origin",
                    geometry.originX != 0 || geometry.originY != 0,
                )

                // The centre of cell (2,2) in view coordinates.
                val x = geometry.originX + 2 * tile + tile / 2
                val y = geometry.originY + 2 * tile + tile / 2
                val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
                val beforeText = actual.textFormat()
                val beforeMoves = (actual.state as Flip.State).moves
                val expected = oracle.keySized(save, x, y, Buttons.LEFT_BUTTON, view, view)

                assertEquals(
                    "outcome at tile $tile",
                    KeyOutcome.SomeEffect,
                    actual.processKey(
                        geometry.toGameX(x),
                        geometry.toGameY(y),
                        Buttons.LEFT_BUTTON,
                        geometry.tileSize,
                    ),
                )
                assertEquals("record at tile $tile", listOf("M2,2"), moves(expected.state))
                assertEquals(
                    "count at tile $tile",
                    beforeMoves + 1,
                    (actual.state as Flip.State).moves,
                )
                assertTrue("board change at tile $tile", beforeText != actual.textFormat())
                assertEquals(
                    "save at tile $tile",
                    expected.state,
                    actual.serialise().serialise(),
                )
                assertEquals("text at tile $tile", oracle.text(expected.state), actual.textFormat())
                assertEquals("status at tile $tile", expected.status, actual.status())

                // The first game pixel is the border band: no move.
                val after = Midend.fromSave(Flip, SgtpSave.parse(expected.state))
                val afterText = after.textFormat()
                val afterMoves = (after.state as Flip.State).moves
                val border = oracle.keySized(
                    expected.state,
                    geometry.originX,
                    geometry.originY,
                    Buttons.LEFT_BUTTON,
                    view,
                    view,
                )
                assertEquals(
                    "border outcome at tile $tile",
                    KeyOutcome.SomeEffect,
                    after.processKey(
                        geometry.toGameX(geometry.originX),
                        geometry.toGameY(geometry.originY),
                        Buttons.LEFT_BUTTON,
                        geometry.tileSize,
                    ),
                )
                assertEquals(
                    "border count at tile $tile",
                    afterMoves,
                    (after.state as Flip.State).moves,
                )
                assertEquals("border board at tile $tile", afterText, after.textFormat())
                assertEquals(
                    "border save at tile $tile",
                    border.state,
                    after.serialise().serialise(),
                )
            }
        }

    @Test
    fun `the requested keys match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("3x3c", "d074191345d1644c17058,c90")
        val expected = oracle.requestKeys(save)
        assertEquals("C arrow mode", 2, expected.arrowMode)
        assertTrue("C requests no extra keys", expected.labels.isEmpty())

        val actual = Flip.requestKeys(Flip.decodeParams("3x3c"))
        assertEquals("Kotlin arrow mode", ArrowMode.ARROWS_LEFT, actual.arrowMode)
        assertTrue("Kotlin requests no extra keys", actual.labels.isEmpty())
    }

    @Test
    fun `drags, releases, right clicks, and unused keys change nothing`() =
        OracleClient().use { oracle ->
            val params = "3x3c"
            val save = saveText(params, "d074191345d1644c17058,c90")
            val baseText = Midend.fromSave(Flip, SgtpSave.parse(save)).textFormat()

            // A gesture is a DOWN click, some DRAGs, then an UP. The
            // DOWN is a real click; the DRAG and the RELEASE are unused.
            val gesture = oracle.gestureSized(save, 24, 24, 167, 167, 4, 192, 192)
            assertEquals("the DOWN click is one move", listOf("M0,0"), moves(gesture.state))

            val gestureActual = Midend.fromSave(Flip, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                gestureActual.processKey(24, 24, Buttons.LEFT_BUTTON, 48),
            )
            for (step in 1..4) {
                val x = 24 + (167 - 24) * step / 5
                val y = 24 + (167 - 24) * step / 5
                assertEquals(
                    KeyOutcome.Unused,
                    gestureActual.processKey(x, y, Buttons.LEFT_DRAG, 48),
                )
            }
            assertEquals(
                KeyOutcome.Unused,
                gestureActual.processKey(167, 167, Buttons.LEFT_RELEASE, 48),
            )
            assertEquals("gesture save", gesture.state, gestureActual.serialise().serialise())
            assertEquals("the gesture counts one move", 1, (gestureActual.state as Flip.State).moves)
            assertTrue("the gesture changes the board", baseText != gestureActual.textFormat())

            val keys = listOf(
                Buttons.RIGHT_BUTTON,
                Buttons.LEFT_DRAG,
                Buttons.LEFT_RELEASE,
                Buttons.MIDDLE_BUTTON,
                Buttons.MOD_NUM_KEYPAD or '8'.code,
            )
            for (key in keys) {
                val expected = oracle.key(save, 24, 24, key)
                val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
                val outcome = actual.processKey(24, 24, key, 48)
                assertEquals("key $key must be unused", KeyOutcome.Unused, outcome)
                assertEquals("key $key adds no move", emptyList<String>(), moves(expected.state))
                assertEquals("key $key keeps the save", expected.state, actual.serialise().serialise())
            }

            // A second gesture clicks its own DOWN cell.
            assertEquals(
                oracle.text(oracle.keySized(save, 72, 72, Buttons.LEFT_BUTTON, 192, 192).state),
                oracle.text(oracle.gestureSized(save, 72, 72, 120, 120, 2, 192, 192).state),
            )
        }

    @Test
    fun `cursor keys and modifiers match the C midend`() = OracleClient().use { oracle ->
        val params = "3x3c"
        val save = saveText(params, "d074191345d1644c17058,c90")

        val cases = listOf(
            // Cursor reveals the cursor, then clamps.
            listOf(Buttons.CURSOR_UP) to emptyList<String>(),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP) to emptyList(),
            listOf(Buttons.CURSOR_LEFT) to emptyList(),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT) to
                listOf("M1,0"),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT2) to
                listOf("M1,0"),
            // A select with a hidden cursor clicks (0,0).
            listOf(Buttons.CURSOR_SELECT) to listOf("M0,0"),
            listOf(Buttons.CURSOR_SELECT2) to listOf("M0,0"),
            // SHFT is stripped from a select, CTRL is rejected.
            listOf(Buttons.MOD_SHFT or Buttons.CURSOR_SELECT) to listOf("M0,0"),
            listOf(Buttons.MOD_CTRL or Buttons.CURSOR_SELECT) to emptyList(),
            // A cursor key keeps its modifiers, so exact equality fails.
            listOf(Buttons.MOD_SHFT or Buttons.CURSOR_UP) to emptyList(),
            listOf(Buttons.MOD_CTRL or Buttons.CURSOR_UP) to emptyList(),
            // CTRL on a pointer button is rejected.
            listOf(Buttons.MOD_CTRL or Buttons.LEFT_BUTTON) to emptyList(),
            // A keypad digit is not a select. A keypad-modified cursor
            // key is a cursor move: C strips MOD_NUM_KEYPAD from it.
            listOf(Buttons.MOD_NUM_KEYPAD or '8'.code) to emptyList(),
            listOf(Buttons.MOD_NUM_KEYPAD or Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT) to
                listOf("M1,0"),
            listOf(
                Buttons.MOD_NUM_KEYPAD or Buttons.CURSOR_RIGHT,
                Buttons.MOD_NUM_KEYPAD or Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ) to listOf("M2,0"),
        )

        for ((sequence, expectedMoves) in cases) {
            val expected = oracle.keys(save, sequence)
            val actual = Midend.fromSave(Flip, SgtpSave.parse(save))
            val beforeText = actual.textFormat()
            val beforeMoves = (actual.state as Flip.State).moves
            for (key in sequence) {
                actual.processKey(0, 0, key, 48)
            }
            assertEquals("moves for $sequence", expectedMoves, moves(expected.state))
            assertEquals("save for $sequence", expected.state, actual.serialise().serialise())
            assertEquals("status for $sequence", expected.status, actual.status())
            assertEquals(
                "move count for $sequence",
                beforeMoves + expectedMoves.size,
                (actual.state as Flip.State).moves,
            )
            if (expectedMoves.isEmpty()) {
                assertEquals("board for $sequence", beforeText, actual.textFormat())
            } else {
                assertTrue("board change for $sequence", beforeText != actual.textFormat())
            }
        }

        // A modified pointer button at a cell is a normal click: the C
        // midend strips SHFT. The `keys` pointer is (0,0), which is a
        // border pixel, so this case needs a real click coordinate.
        val shiftedClick = Buttons.MOD_SHFT or Buttons.LEFT_BUTTON
        val expectedClick = oracle.keySized(save, 24, 24, shiftedClick, 192, 192)
        val actualClick = Midend.fromSave(Flip, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            actualClick.processKey(24, 24, shiftedClick, 48),
        )
        assertEquals(listOf("M0,0"), moves(expectedClick.state))
        assertEquals(expectedClick.state, actualClick.serialise().serialise())
    }

    private data class Quad(
        val params: String,
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private fun moves(save: String): List<String> = SgtpSave.parse(save).values("MOVE")

    private fun saveText(params: String, desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Flip"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
