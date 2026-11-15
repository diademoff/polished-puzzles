package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Size
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: twiddle.
 *
 * The layout, the click band around a region centre, the keyboard
 * corners, the keypad edges, and the cursor rules are compared with the
 * oracle.
 *
 * The click band is one tile wide and centred on the region centre. Its
 * exact edges depend on integer truncation in `(n-1) * t / 2` and
 * `FROMCOORD`, so the tests use the computed edges, not tile centres:
 * a click over a tile corner is rejected for `n = 3`.
 *
 * On `3x3n2` both `w - n` and `h - n` are odd, so every keypad edge and
 * the keypad centre is rejected. The edges are therefore tested on
 * `4x4n2`, and the rejection is pinned on `3x3n2`.
 */
class TwiddleInputParityTest {

    private val threeDesc = "2,5,6,7,3,1,4,9,8"
    private val fourDesc = "3,13,1,15,12,8,7,6,16,4,9,5,2,14,10,11"
    private val fiveDesc =
        "15,4,11,18,1,16,17,6,3,22,19,12,5,10,13,24,9,20,7,14,21,8,23,2,25"

    @Test
    fun `the layout matches the oracle at every viewport`() = OracleClient().use { oracle ->
        val cases = listOf(
            Layout("3x3n2", 192, 192, 48, 0, 0),
            Layout("3x3n2", 251, 251, 63, 0, 0),
            Layout("3x3n2", 328, 328, 82, 0, 0),
            Layout("3x3n2", 400, 400, 100, 0, 0),
            Layout("3x3n2", 411, 702, 103, 0, 145),
            Layout("3x3n2", 288, 400, 72, 0, 56),
            Layout("4x4n2", 328, 328, 65, 2, 2),
            Layout("5x5n3", 251, 251, 41, 3, 3),
            Layout("6x6n4", 400, 400, 57, 1, 1),
            Layout("6x6n4", 411, 702, 58, 2, 148),
        )

        for (case in cases) {
            val save = oracle.newGame("twiddle", case.params, SEED)
            val expected = oracle.layout(save.state, case.viewWidth, case.viewHeight)
            val geometry = Midend.newGame(Twiddle, case.params, SEED)
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
        }

        // A board that exactly fits and one that is one pixel short.
        val exact = Midend.newGame(Twiddle, "3x3n2", SEED)
        assertEquals(48, exact.largestTileFor(192, 192))
        assertEquals(47, exact.largestTileFor(191, 191))
        // Two truncated half-tiles are not one tile.
        assertEquals(Size(3 * 63 + 62, 3 * 63 + 62), exact.game.computeSize(exact.params, 63))
        assertEquals(63, exact.largestTileFor(251, 251))
    }

    @Test
    fun `a real click at every band edge matches the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            ClickCase("3x3n2", threeDesc, 192, 192, 48, 2),
            ClickCase("3x3n2", threeDesc, 251, 251, 63, 2),
            ClickCase("3x3n2", threeDesc, 328, 328, 82, 2),
            ClickCase("3x3n2", threeDesc, 400, 400, 100, 2),
            ClickCase("3x3n2", threeDesc, 411, 702, 103, 2),
            ClickCase("4x4n3", fourDesc, 240, 240, 48, 3),
            ClickCase("4x4n3", fourDesc, 314, 314, 63, 3),
            ClickCase("4x4n3", fourDesc, 514, 514, 103, 3),
            ClickCase("5x5n3", fiveDesc, 251, 251, 41, 3),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc)
            val base = oracle.id(save)
            val probe = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            val geometry = probe.geometryFor(case.viewWidth, case.viewHeight)
            val tile = geometry.tileSize
            val params = Twiddle.decodeParams(case.params) as Twiddle.Params
            val where = "${case.params} tile $tile in ${case.viewWidth} x ${case.viewHeight}"

            assertEquals("tile $where", case.tile, tile)

            val offset = (case.n - 1) * tile / 2

            // The accepted band for origin k, in game coordinates.
            fun low(k: Int): Int = (k + 1) * tile + offset + tile / 2 - tile

            fun high(k: Int): Int = (k + 2) * tile - 1 + offset + tile / 2 - tile

            for (ky in 0..params.height - params.n) {
                for (kx in 0..params.width - params.n) {
                    val move = "M$kx,$ky,1"
                    val accepted = listOf(
                        point(low(kx), low(ky)),
                        point(high(kx), high(ky)),
                    )
                    for (point in accepted) {
                        val viewX = geometry.originX + point.x
                        val viewY = geometry.originY + point.y
                        assertClick(
                            oracle, save, base, case.viewWidth, case.viewHeight,
                            geometry, tile, viewX, viewY,
                            Buttons.LEFT_BUTTON, move, where,
                        )
                    }

                    // One pixel outside the board edge is not accepted.
                    val rejected = ArrayList<OracleClient.Point>()
                    if (kx == 0) rejected += point(low(0) - 1, low(ky))
                    if (kx == params.width - params.n) {
                        rejected += point(high(kx) + 1, high(ky))
                    }
                    if (ky == 0) rejected += point(low(kx), low(0) - 1)
                    if (ky == params.height - params.n) {
                        rejected += point(high(kx), high(ky) + 1)
                    }
                    for (point in rejected) {
                        assertInertClick(
                            oracle, save, case.viewWidth, case.viewHeight,
                            geometry, tile,
                            geometry.originX + point.x,
                            geometry.originY + point.y,
                            Buttons.LEFT_BUTTON, where,
                        )
                    }
                }
            }

            // The right button reverses the direction.
            val first = point(low(0), low(0))
            assertClick(
                oracle, save, base, case.viewWidth, case.viewHeight,
                geometry, tile,
                geometry.originX + first.x, geometry.originY + first.y,
                Buttons.RIGHT_BUTTON, "M0,0,-1", where,
            )

            // A middle click is not a game key.
            assertInertClick(
                oracle, save, case.viewWidth, case.viewHeight,
                geometry, tile,
                geometry.originX + first.x, geometry.originY + first.y,
                Buttons.MIDDLE_BUTTON, where,
            )
        }
    }

    @Test
    fun `a click in the letterbox and in the outer frame pixel is inert`() =
        OracleClient().use { oracle ->
            val save = saveText("3x3n2", threeDesc)
            val geometry = Midend.fromSave(Twiddle, SgtpSave.parse(save)).geometryFor(411, 702)
            val tile = geometry.tileSize
            assertEquals(103, tile)
            assertEquals(145, geometry.originY)

            assertInertClick(
                oracle, save, 411, 702, geometry, tile, 0, 0,
                Buttons.LEFT_BUTTON, "the view corner",
            )
            assertInertClick(
                oracle, save, 411, 702, geometry, tile, 1, geometry.originY - 1,
                Buttons.LEFT_BUTTON, "above the board",
            )
            assertInertClick(
                oracle, save, 411, 702, geometry, tile, geometry.originX + 1,
                geometry.originY + geometry.height - 1, Buttons.LEFT_BUTTON,
                "the bottom frame pixel",
            )
            assertInertClick(
                oracle, save, 411, 702, geometry, tile, geometry.originX - 1,
                geometry.originY + 1, Buttons.LEFT_BUTTON, "left of the board",
            )
        }

    @Test
    fun `a drag and a release do nothing without a press`() = OracleClient().use { oracle ->
        val save = saveText("3x3n2", threeDesc)
        for (button in listOf(Buttons.LEFT_DRAG, Buttons.LEFT_RELEASE)) {
            val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(72, 72, button, 48),
            )
            assertEquals("a lone $button adds no move", save, midend.serialise().serialise())

            val expected = oracle.pointsSized(
                save, listOf(point(72, 72, button)), 192, 192,
            )
            assertEquals("C: a lone $button adds no move", save, expected.state.state)
        }
    }

    @Test
    fun `a press, drag, and release produce one move at the press`() =
        OracleClient().use { oracle ->
            val save = saveText("3x3n2", threeDesc)
            val geometry = Midend.fromSave(Twiddle, SgtpSave.parse(save)).geometryFor(192, 192)
            val press = point(72, 72)
            val dragTo = point(120, 72)

            val sequence = listOf(
                point(press.x, press.y, Buttons.LEFT_BUTTON),
                point(dragTo.x, dragTo.y, Buttons.LEFT_DRAG),
                point(dragTo.x, dragTo.y, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, sequence, 192, 192)
            assertEquals(
                listOf("M0,0,1"),
                SgtpSave.parse(expected.state.state).values("MOVE"),
            )

            val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            for (point in sequence) {
                midend.processKey(
                    geometry.toGameX(point.x),
                    geometry.toGameY(point.y),
                    point.button,
                    48,
                )
            }
            assertEquals("one move from the gesture", expected.state.state, midend.serialise().serialise())
            assertEquals(1, midend.serialise().values("MOVE").size)
            assertEquals(1, (midend.state as Twiddle.State).moveCount)
            assertEquals(0, (midend.state as Twiddle.State).lastX)
            assertEquals(0, (midend.state as Twiddle.State).lastY)

            // A swipe that starts in the letterbox does nothing.
            val fromLetterbox = listOf(
                point(0, 0, Buttons.LEFT_BUTTON),
                point(72, 72, Buttons.LEFT_DRAG),
                point(72, 72, Buttons.LEFT_RELEASE),
            )
            val expectedLetterbox = oracle.pointsSized(save, fromLetterbox, 192, 192)
            assertEquals(save, expectedLetterbox.state.state)
            val fromAndroid = Midend.fromSave(Twiddle, SgtpSave.parse(save))
            for (point in fromLetterbox) {
                fromAndroid.processKey(
                    geometry.toGameX(point.x),
                    geometry.toGameY(point.y),
                    point.button,
                    48,
                )
            }
            assertEquals(save, fromAndroid.serialise().serialise())

            // A swipe from a valid region into another region moves at the
            // press origin only.
            val across = listOf(
                point(72, 72, Buttons.LEFT_BUTTON),
                point(120, 72, Buttons.LEFT_DRAG),
                point(120, 72, Buttons.LEFT_RELEASE),
            )
            assertEquals(
                expected.state.state,
                oracle.pointsSized(save, across, 192, 192).state.state,
            )
        }

    @Test
    fun `the keyboard and the cursor keys match the oracle`() = OracleClient().use { oracle ->
        val three = oracle.newGame("twiddle", "3x3n2", SEED)
        val four = oracle.newGame("twiddle", "4x4n2", SEED)

        val sequences = listOf(
            three to listOf('a'.code),
            three to listOf('b'.code),
            three to listOf('c'.code),
            three to listOf('d'.code),
            three to listOf('A'.code),
            three to listOf('B'.code),
            three to listOf('C'.code),
            three to listOf('D'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '7'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '9'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '1'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '3'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '8'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '2'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '4'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '6'.code),
            three to listOf(Buttons.MOD_NUM_KEYPAD + '5'.code),
            three to listOf('8'.code),
            four to listOf(Buttons.MOD_NUM_KEYPAD + '8'.code),
            four to listOf(Buttons.MOD_NUM_KEYPAD + '2'.code),
            four to listOf(Buttons.MOD_NUM_KEYPAD + '4'.code),
            four to listOf(Buttons.MOD_NUM_KEYPAD + '6'.code),
            four to listOf(Buttons.MOD_NUM_KEYPAD + '5'.code),
            three to listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
            three to listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2),
            three to listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
            three to listOf(
                Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
                Buttons.CURSOR_SELECT,
            ),
            three to listOf(
                Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
                Buttons.CURSOR_SELECT,
            ),
            three to listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP),
            three to listOf(Buttons.CURSOR_LEFT),
        )

        val moveCounts = ArrayList<Int>()
        for ((save, sequence) in sequences) {
            val expected = oracle.keys(save.state, sequence)
            val actual = Midend.fromSave(Twiddle, SgtpSave.parse(save.state))
            for (key in sequence) {
                actual.processKey(0, 0, key, Twiddle.preferredTileSize)
            }
            val what = "${save.id} keys $sequence"
            assertEquals("save for $what", expected.state, actual.serialise().serialise())
            assertEquals("id for $what", expected.id, actual.id())
            assertEquals("status for $what", expected.status, actual.status())
            assertEquals("canundo for $what", expected.canUndo, actual.canUndo)
            assertEquals("canredo for $what", expected.canRedo, actual.canRedo)
            moveCounts += actual.serialise().values("MOVE").size
        }

        // On 3x3n2 every keypad edge and the centre is rejected; on 4x4n2
        // every one of them is a move. A test that claims to cover keypad
        // edges on the default preset covers nothing.
        for (i in 12..16) {
            assertEquals("3x3 keypad entry $i adds no move", 0, moveCounts[i])
        }
        for (i in 18..22) {
            assertEquals("4x4 keypad entry $i adds one move", 1, moveCounts[i])
        }
        assertTrue("a plain 8 adds no move", moveCounts[17] == 0)
    }

    @Test
    fun `an inert click does not hide the cursor`() = OracleClient().use { oracle ->
        val save = saveText("3x3n2", threeDesc)
        val geometry = Midend.fromSave(Twiddle, SgtpSave.parse(save)).geometryFor(192, 192)

        // Show the cursor, click in the letterbox, then turn left. The
        // inert click returns MOVE_UNUSED and leaves the cursor alone, so
        // the select still rotates at (0, 0).
        val sequence = listOf(
            point(0, 0, Buttons.CURSOR_UP),
            point(0, 0, Buttons.LEFT_BUTTON),
            point(0, 0, Buttons.CURSOR_SELECT),
        )
        val expected = oracle.pointsSized(save, sequence, 192, 192)
        assertEquals(listOf("M0,0,1"), SgtpSave.parse(expected.state.state).values("MOVE"))

        val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        for (point in sequence) {
            midend.processKey(
                geometry.toGameX(point.x),
                geometry.toGameY(point.y),
                point.button,
                48,
            )
        }
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertEquals(1, (midend.state as Twiddle.State).moveCount)

        // A valid click hides the cursor, so the following select only
        // shows it again.
        val validThenSelect = listOf(
            point(0, 0, Buttons.CURSOR_UP),
            point(72, 72, Buttons.LEFT_BUTTON),
            point(0, 0, Buttons.CURSOR_SELECT),
        )
        val validExpected = oracle.pointsSized(save, validThenSelect, 192, 192)
        assertEquals(
            listOf("M0,0,1"),
            SgtpSave.parse(validExpected.state.state).values("MOVE"),
        )
        val validMidend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        for (point in validThenSelect) {
            validMidend.processKey(
                geometry.toGameX(point.x),
                geometry.toGameY(point.y),
                point.button,
                48,
            )
        }
        assertEquals(validExpected.state.state, validMidend.serialise().serialise())
    }

    private fun assertClick(
        oracle: OracleClient,
        save: String,
        base: GameState,
        viewWidth: Int,
        viewHeight: Int,
        geometry: BoardGeometry,
        tile: Int,
        viewX: Int,
        viewY: Int,
        button: Int,
        move: String,
        where: String,
    ) {
        val expected = oracle.pointsSized(
            base.state,
            listOf(point(viewX, viewY, button)),
            viewWidth,
            viewHeight,
        )
        val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        val outcome = midend.processKey(
            geometry.toGameX(viewX),
            geometry.toGameY(viewY),
            button,
            tile,
        )
        val what = "($viewX,$viewY) button $button $where"
        assertEquals("outcome $what", KeyOutcome.SomeEffect, outcome)
        assertEquals("move record $what", listOf(move), SgtpSave.parse(expected.state.state).values("MOVE"))
        assertEquals("save $what", expected.state.state, midend.serialise().serialise())
        assertEquals("id $what", expected.state.id, midend.id())
        assertEquals("status $what", expected.state.status, midend.status())
        assertEquals("move count $what", 1, (midend.state as Twiddle.State).moveCount)
    }

    private fun assertInertClick(
        oracle: OracleClient,
        save: String,
        viewWidth: Int,
        viewHeight: Int,
        geometry: BoardGeometry,
        tile: Int,
        viewX: Int,
        viewY: Int,
        button: Int,
        where: String,
    ) {
        val expected = oracle.pointsSized(
            save,
            listOf(point(viewX, viewY, button)),
            viewWidth,
            viewHeight,
        )
        val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        val outcome = midend.processKey(
            geometry.toGameX(viewX),
            geometry.toGameY(viewY),
            button,
            tile,
        )
        val what = "($viewX,$viewY) button $button $where"
        assertEquals("an inert click is unused $what", KeyOutcome.Unused, outcome)
        assertEquals("an inert click keeps the save $what", save, midend.serialise().serialise())
        assertEquals("C: an inert click keeps the save $what", save, expected.state.state)
    }

    private fun saveText(params: String, desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Twiddle.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
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
        val params: String,
        val desc: String,
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val n: Int,
    )

    private fun point(x: Int, y: Int, button: Int = Buttons.LEFT_BUTTON) =
        OracleClient.Point(x, y, button)

    private companion object {
        const val SEED = "12345"
    }
}
