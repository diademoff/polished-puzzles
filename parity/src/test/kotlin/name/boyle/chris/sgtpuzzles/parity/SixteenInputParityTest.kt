package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
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
 * C's midend rejects `CTRL` on a key that is not a cursor move, and it
 * drops `MOD_NUM_KEYPAD` from a key that is not a digit, before the
 * game sees the key. Those two cases have no game-level oracle result,
 * so they are Kotlin-only: the engine test
 * `the C midend modifier filter applies to the raw button` pins them.
 * Every modifier the C midend does deliver is compared with the oracle
 * here: `MOD_SHFT` and `MOD_CTRL` on a cursor move, and `MOD_NUM_KEYPAD`
 * on a digit in a lock mode.
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
    fun `a real click on every frame slot moves at tile 48, 63, 82, and 100`() =
        OracleClient().use { oracle ->
            val save = saveText(BASE)
            val start = oracle.id(save)

            // 293 x 400 gives tile 48 with a real letterbox on both
            // axes. The square views are the exact tile sizes from the
            // test plan; integer centering leaves a one-pixel origin.
            val cases = listOf(
                ClickCase(293, 400, 48, 2, 56),
                ClickCase(380, 380, 63, 1, 1),
                ClickCase(494, 494, 82, 1, 1),
                ClickCase(602, 602, 100, 1, 1),
            )

            for (case in cases) {
                val probe = Midend.fromSave(Sixteen, SgtpSave.parse(save))
                val geometry = probe.geometryFor(case.viewWidth, case.viewHeight)
                val tile = geometry.tileSize
                val where = "tile $tile in ${case.viewWidth} x ${case.viewHeight}"

                assertEquals("tile $where", case.tile, tile)
                assertEquals("origin x $where", case.originX, geometry.originX)
                assertEquals("origin y $where", case.originY, geometry.originY)
                assertTrue(
                    "a non-zero letterbox origin $where",
                    geometry.originX != 0 || geometry.originY != 0,
                )

                val row = case.originY + tile + tile / 2
                val column = case.originX + tile + tile / 2
                val right = case.originX + (4 + 1) * tile + tile / 2
                val bottom = case.originY + (4 + 1) * tile + tile / 2
                val clicks = listOf(
                    // The four frame bands. The row and the column name
                    // the line, so a wrong origin or band fails here.
                    Triple(case.originX + tile / 2, row, Buttons.LEFT_BUTTON) to "R0,-1",
                    Triple(right, row, Buttons.LEFT_BUTTON) to "R0,1",
                    Triple(column, case.originY + tile / 2, Buttons.LEFT_BUTTON) to "C0,-1",
                    Triple(column, bottom, Buttons.LEFT_BUTTON) to "C0,1",
                    // The right button reverses the direction.
                    Triple(case.originX + tile / 2, row, Buttons.RIGHT_BUTTON) to "R0,1",
                    // The pixel next to the letterbox must move, so the
                    // letterbox case below cannot pass with a broken
                    // origin.
                    Triple(
                        case.originX + 1,
                        case.originY + tile + 1,
                        Buttons.LEFT_BUTTON,
                    ) to "R0,-1",
                )

                for ((click, move) in clicks) {
                    val (viewX, viewY, button) = click
                    val midend = Midend.fromSave(Sixteen, SgtpSave.parse(save))
                    val movesBefore = midend.serialise().values("MOVE").size
                    assertEquals(
                        "outcome for $move at ($viewX,$viewY) $where",
                        KeyOutcome.SomeEffect,
                        midend.processKey(
                            geometry.toGameX(viewX),
                            geometry.toGameY(viewY),
                            button,
                            tile,
                        ),
                    )
                    val moves = midend.serialise().values("MOVE")
                    assertEquals("one move for $move $where", movesBefore + 1, moves.size)
                    assertEquals("move record for $move $where", move, moves.single())
                    assertEquals(
                        "move count for $move $where",
                        1,
                        (midend.state as Sixteen.State).moveCount,
                    )
                    assertEquals("status text for $move $where", "Moves: 1", midend.statusText())

                    val expected = oracle.pointsSized(
                        start.state,
                        listOf(OracleClient.Point(viewX, viewY, button)),
                        case.viewWidth,
                        case.viewHeight,
                    )
                    assertEquals(
                        "C move record for $move $where",
                        listOf(move),
                        SgtpSave.parse(expected.state.state).values("MOVE"),
                    )
                    assertEquals(
                        "save for $move $where",
                        expected.state.state,
                        midend.serialise().serialise(),
                    )
                    assertEquals("id for $move $where", expected.state.id, midend.id())
                    assertEquals("status for $move $where", expected.state.status, midend.status())
                }

                // A click in a cell, on the frame corner, and in the
                // letterbox is a UI update with no move on either side.
                val inert = listOf(
                    Triple(column, row, Buttons.LEFT_BUTTON),
                    Triple(
                        case.originX + tile / 2,
                        case.originY + tile / 2,
                        Buttons.LEFT_BUTTON,
                    ),
                    Triple(case.originX - 1, case.originY - 1, Buttons.LEFT_BUTTON),
                )
                for ((viewX, viewY, button) in inert) {
                    val midend = Midend.fromSave(Sixteen, SgtpSave.parse(save))
                    assertEquals(
                        "an inert click is a UI update at ($viewX,$viewY) $where",
                        KeyOutcome.SomeEffect,
                        midend.processKey(
                            geometry.toGameX(viewX),
                            geometry.toGameY(viewY),
                            button,
                            tile,
                        ),
                    )
                    assertEquals(
                        "an inert click adds no move at ($viewX,$viewY) $where",
                        emptyList<String>(),
                        midend.serialise().values("MOVE"),
                    )
                    assertEquals(
                        "an inert click keeps the save at ($viewX,$viewY) $where",
                        save,
                        midend.serialise().serialise(),
                    )

                    val expected = oracle.pointsSized(
                        start.state,
                        listOf(OracleClient.Point(viewX, viewY, button)),
                        case.viewWidth,
                        case.viewHeight,
                    )
                    assertEquals(
                        "C: an inert click changes nothing at ($viewX,$viewY) $where",
                        save,
                        expected.state.state,
                    )
                }
            }
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
            assertEquals(
                "save with modifier $modifier",
                expected.state,
                actual.serialise().serialise(),
            )
            assertEquals("id with modifier $modifier", expected.id, actual.id())
            assertTrue(actual.canUndo)
            assertEquals(
                "the click adds one move with modifier $modifier",
                listOf("R0,-1"),
                actual.serialise().values("MOVE"),
            )
            assertEquals(
                "move count with modifier $modifier",
                1,
                (actual.state as Sixteen.State).moveCount,
            )
            assertEquals("status text with modifier $modifier", "Moves: 1", actual.statusText())
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
            assertEquals(
                "move count for $sequence",
                SgtpSave.parse(expected.state).values("MOVE").size,
                (actual.state as Sixteen.State).moveCount,
            )
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

    private data class ClickCase(
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private fun saveText(desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Sixteen.name),
            SaveRecord("PARAMS", "4x4"),
            SaveRecord("CPARAMS", "4x4"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val BASE = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
    }
}
