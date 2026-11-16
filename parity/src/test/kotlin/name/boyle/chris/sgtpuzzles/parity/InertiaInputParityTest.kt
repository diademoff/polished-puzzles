package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: inertia.
 *
 * Real input on a 10x8 open board and on the shipped boards. A click is
 * the nearest of eight compass octants of the clicked cell, with no
 * board bounds test, so the click set covers all eight directions at
 * tiles 48, 63, 82, and 100 and in the nonzero-origin 411x702 view.
 * Arrow keys, keypad digits, and the four diagonal keypad digits are
 * compared with the oracle `keys` op; a plain digit, keypad 5, and a
 * modified cursor key are inert.
 *
 * A real move changes the player, the gem count, or both, and adds one
 * state and one move. An inert action changes neither. The final trace
 * is compared only for inert input or for a loaded state, because the
 * oracle has no timer and records the animation frame at `anim_pos == 0`
 * after a real move.
 *
 * The death, the gem collection, the empty `MOVE` record, the
 * `UI D<deaths>` record, the settled cursor rectangle, the diagonal
 * `requestKeys`, and the solve-follow select are pinned here as well.
 */
class InertiaInputParityTest {

    private companion object {
        const val SEED = "12345"
        const val VIEW_WIDTH = 411
        const val VIEW_HEIGHT = 702

        /** The four exact-fit viewports of the 10x8 board. */
        val VIEWS = linkedMapOf(
            48 to Size(505, 409),
            63 to Size(661, 535),
            82 to Size(861, 697),
            100 to Size(1051, 851),
        )

        /** The octant index is the direction: up, up-right, right, ... */
        val OCTANTS = listOf(
            0 to (0 to -1),
            1 to (1 to -1),
            2 to (1 to 0),
            3 to (1 to 1),
            4 to (0 to 1),
            5 to (-1 to 1),
            6 to (-1 to 0),
            7 to (-1 to -1),
        )

        /** `DIRECTION_DIGIT[d]` is the keypad digit of direction `d`. */
        const val DIRECTION_DIGIT = "89632147"

        val OPEN_10X8 = "bbbbbgbbbb" +
            "bbbbbbbbbb" +
            "bbbbbbbbbb" +
            "bbbbbbbbbb" +
            "bbbbSbbbbb" +
            "bbbbbbbbbb" +
            "bbbbbbbbbb" +
            "gbbbbbbbgb"

        val OPEN_15X12 = "bbbbbbbbbbbbbgb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbSbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb" +
            "bbbbbbbbbbbbbbb"

        val OPEN_5X5 = "bbbbb" + "gbbbb" + "bbSbb" + "bbbbb" + "bbbbb"

        /** Two gems: up-left from the start collects one and stops. */
        val TWO_GEM_5X5 = "bbbbb" + "bgbbb" + "bbSbb" + "bbbbb" + "bbbbg"

        fun keypad(digit: Char): Int = Buttons.MOD_NUM_KEYPAD or digit.code
    }

    private fun midend(save: String): Midend =
        Midend.fromSave(Inertia, SgtpSave.parse(save))

    private fun moves(midend: Midend): List<String> = midend.serialise().values("MOVE")

    private fun player(midend: Midend): Inertia.State = midend.state as Inertia.State

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what can undo", expected.canUndo, actual.canUndo)
        assertEquals("$what can redo", expected.canRedo, actual.canRedo)
    }

    /** A real move changes the player or the gems, and adds one entry. */
    private fun assertRealMove(
        what: String,
        before: Inertia.State,
        states: Int,
        moveCount: Int,
        actual: Midend,
    ) {
        val after = player(actual)
        assertTrue(
            "$what changes the player or the gems",
            after.px != before.px || after.py != before.py || after.gems != before.gems,
        )
        assertEquals("$what adds one state", states + 1, actual.stateCount)
        assertEquals("$what adds one move", moveCount + 1, moves(actual).size)
        assertNotEquals("$what changes the player", before.px to before.py, after.px to after.py)
    }

    private fun assertInert(
        what: String,
        before: Inertia.State,
        states: Int,
        moveCount: Int,
        actual: Midend,
    ) {
        val after = player(actual)
        assertEquals("$what keeps the player", before.px, after.px)
        assertEquals("$what keeps the player", before.py, after.py)
        assertEquals("$what keeps the gems", before.gems, after.gems)
        assertEquals("$what keeps the state count", states, actual.stateCount)
        assertEquals("$what keeps the move count", moveCount, moves(actual).size)
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

    @Test
    fun `layout picks the four exact tiles and a nonzero letterbox origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("inertia", "10x8", SEED).state
            val params = Inertia.decodeParams("10x8")
            for ((tile, board) in VIEWS) {
                assertEquals("board size at $tile", board, Inertia.computeSize(params, tile))
                val layout = oracle.layout(save, board.width, board.height)
                val geometry = midend(save).geometryFor(board.width, board.height)
                assertEquals("tile at $tile", tile, geometry.tileSize)
                assertEquals("width at $tile", layout.width, geometry.width)
                assertEquals("height at $tile", layout.height, geometry.height)
                assertEquals("origin x at $tile", 0, geometry.originX)
                assertEquals("origin y at $tile", 0, geometry.originY)
            }

            val geometry = midend(save).geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
            val layout = oracle.layout(save, VIEW_WIDTH, VIEW_HEIGHT)
            assertEquals("letterbox tile", 39, geometry.tileSize)
            assertEquals("letterbox width", layout.width, geometry.width)
            assertEquals("letterbox height", layout.height, geometry.height)
            assertEquals("letterbox origin x", 1, geometry.originX)
            assertEquals("letterbox origin y", 185, geometry.originY)
            assertNotEquals("the origin x is nonzero", 0, geometry.originX)
            assertNotEquals("the origin y is nonzero", 0, geometry.originY)

            val open15 = midend(saveText("15x12", OPEN_15X12))
            val big = open15.geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
            val bigLayout = oracle.layout(saveText("15x12", OPEN_15X12), VIEW_WIDTH, VIEW_HEIGHT)
            assertEquals("15x12 letterbox tile", 26, big.tileSize)
            assertEquals("15x12 letterbox width", bigLayout.width, big.width)
            assertEquals("15x12 letterbox height", bigLayout.height, big.height)
            assertEquals("15x12 letterbox origin x", 4, big.originX)
            assertEquals("15x12 letterbox origin y", 188, big.originY)

            val small = midend(save).geometryFor(300, 300)
            val smallLayout = oracle.layout(save, 300, 300)
            assertEquals("300x300 tile", 28, small.tileSize)
            assertEquals("300x300 width", smallLayout.width, small.width)
            assertEquals("300x300 height", smallLayout.height, small.height)
            assertEquals("300x300 origin x", 2, small.originX)
            assertEquals("300x300 origin y", 30, small.originY)
        }

    @Test
    fun `nearest-octant clicks at four tiles and the letterbox are real moves`() =
        OracleClient().use { oracle ->
            val save = saveText("10x8", OPEN_10X8)

            for ((tile, board) in VIEWS) {
                for ((direction, delta) in OCTANTS) {
                    val point = cellCenter(4, 4, delta, tile)
                    val expected = oracle.pointsSized(
                        save,
                        listOf(point),
                        board.width,
                        board.height,
                    )
                    val actual = midend(save)
                    val before = player(actual)
                    val states = actual.stateCount
                    val moveCount = moves(actual).size

                    assertEquals(
                        "tile $tile direction $direction outcome",
                        KeyOutcome.SomeEffect,
                        actual.processKey(point.x, point.y, point.button, tile),
                    )
                    assertSame("tile $tile direction $direction", expected.state, actual)
                    assertEquals(
                        "tile $tile direction $direction move",
                        listOf(direction.toString()),
                        moves(actual),
                    )
                    assertRealMove(
                        "tile $tile direction $direction",
                        before,
                        states,
                        moveCount,
                        actual,
                    )
                }
            }

            // The letterboxed view: the app subtracts the origin first.
            val geometry = midend(save).geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
            assertEquals(39, geometry.tileSize)
            for ((direction, delta) in OCTANTS) {
                val game = cellCenter(4, 4, delta, geometry.tileSize)
                val point = OracleClient.Point(
                    game.x + geometry.originX,
                    game.y + geometry.originY,
                    Buttons.LEFT_BUTTON,
                )
                val expected = oracle.pointsSized(
                    save,
                    listOf(point),
                    VIEW_WIDTH,
                    VIEW_HEIGHT,
                )
                val actual = midend(save)
                val before = player(actual)
                val states = actual.stateCount
                val moveCount = moves(actual).size

                assertEquals(
                    "letterbox direction $direction outcome",
                    KeyOutcome.SomeEffect,
                    actual.processKey(game.x, game.y, Buttons.LEFT_BUTTON, geometry.tileSize),
                )
                assertSame("letterbox direction $direction", expected.state, actual)
                assertEquals(
                    "letterbox direction $direction move",
                    listOf(direction.toString()),
                    moves(actual),
                )
                assertRealMove("letterbox direction $direction", before, states, moveCount, actual)
            }
        }

    @Test
    fun `a letterbox click is a real move`() = OracleClient().use { oracle ->
        // The shipped 10x8 board at 411x702: tile 39, origin (1,185).
        val save = oracle.newGame("inertia", "10x8", SEED).state
        val geometry = midend(save).geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
        assertEquals(39, geometry.tileSize)
        assertEquals(1, geometry.originX)
        assertEquals(185, geometry.originY)

        val viewX = 0
        val viewY = 213
        val expected = oracle.pointsSized(
            save,
            listOf(OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON)),
            VIEW_WIDTH,
            VIEW_HEIGHT,
        )
        val actual = midend(save)
        val before = player(actual)
        val states = actual.stateCount
        val moveCount = moves(actual).size
        assertEquals(
            "letterbox outcome",
            KeyOutcome.SomeEffect,
            actual.processKey(
                viewX - geometry.originX,
                viewY - geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )
        assertSame("letterbox", expected.state, actual)
        assertEquals("letterbox move", listOf("6"), moves(actual))
        assertRealMove("letterbox", before, states, moveCount, actual)

        // A hand-written open 15x12 board at the same view has a larger
        // origin on both axes. The top-left view point is still a move.
        val open = saveText("15x12", OPEN_15X12)
        val big = midend(open)
        val bigGeometry = big.geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
        assertEquals(26, bigGeometry.tileSize)
        assertEquals(4, bigGeometry.originX)
        assertEquals(188, bigGeometry.originY)
        val bigExpected = oracle.pointsSized(
            open,
            listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
            VIEW_WIDTH,
            VIEW_HEIGHT,
        )
        val bigBefore = player(big)
        val bigStates = big.stateCount
        val bigMoves = moves(big).size
        assertEquals(
            "15x12 letterbox outcome",
            KeyOutcome.SomeEffect,
            big.processKey(
                0 - bigGeometry.originX,
                0 - bigGeometry.originY,
                Buttons.LEFT_BUTTON,
                bigGeometry.tileSize,
            ),
        )
        assertSame("15x12 letterbox", bigExpected.state, big)
        assertEquals("15x12 letterbox move", listOf("7"), moves(big))
        assertRealMove("15x12 letterbox", bigBefore, bigStates, bigMoves, big)
    }

    @Test
    fun `arrow keypad and diagonal keys make the matching moves`() =
        OracleClient().use { oracle ->
            val save = saveText("10x8", OPEN_10X8)

            for ((key, direction) in listOf(
                Buttons.CURSOR_UP to 0,
                Buttons.CURSOR_RIGHT to 2,
                Buttons.CURSOR_DOWN to 4,
                Buttons.CURSOR_LEFT to 6,
            )) {
                val expected = oracle.keys(save, listOf(key))
                val actual = midend(save)
                val before = player(actual)
                val states = actual.stateCount
                val moveCount = moves(actual).size
                assertEquals("arrow $direction", KeyOutcome.SomeEffect, actual.processKey(0, 0, key, 48))
                assertSame("arrow $direction", expected, actual)
                assertEquals("arrow $direction move", listOf(direction.toString()), moves(actual))
                assertRealMove("arrow $direction", before, states, moveCount, actual)
            }

            for (direction in 0 until 8) {
                val key = keypad(DIRECTION_DIGIT[direction])
                val expected = oracle.keys(save, listOf(key))
                val actual = midend(save)
                val before = player(actual)
                val states = actual.stateCount
                val moveCount = moves(actual).size
                assertEquals(
                    "keypad direction $direction",
                    KeyOutcome.SomeEffect,
                    actual.processKey(0, 0, key, 48),
                )
                assertSame("keypad direction $direction", expected, actual)
                assertEquals(
                    "keypad direction $direction move",
                    listOf(direction.toString()),
                    moves(actual),
                )
                assertRealMove("keypad direction $direction", before, states, moveCount, actual)
            }

            // The four diagonal keys are the odd digits on the keypad.
            for ((digit, direction) in listOf(
                '7' to 7,
                '9' to 1,
                '1' to 5,
                '3' to 3,
            )) {
                val actual = midend(save)
                assertEquals(
                    "diagonal $digit",
                    KeyOutcome.SomeEffect,
                    actual.processKey(0, 0, keypad(digit), 48),
                )
                assertEquals("diagonal $digit move", listOf(direction.toString()), moves(actual))
            }
        }

    @Test
    fun `unused keys change neither the player nor the count`() =
        OracleClient().use { oracle ->
            val save = saveText("10x8", OPEN_10X8)
            for ((label, key) in listOf(
                "plain 8" to '8'.code,
                "keypad 5" to keypad('5'),
                "control right" to (Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT),
                "shift down" to (Buttons.MOD_SHFT or Buttons.CURSOR_DOWN),
                "space" to ' '.code,
                "select" to Buttons.CURSOR_SELECT,
                "select2" to Buttons.CURSOR_SELECT2,
            )) {
                val expected = oracle.keys(save, listOf(key))
                val actual = midend(save)
                val before = player(actual)
                val states = actual.stateCount
                val moveCount = moves(actual).size
                assertNotEquals("$label outcome", KeyOutcome.SomeEffect, actual.processKey(0, 0, key, 48))
                assertSame(label, expected, actual)
                assertInert(label, before, states, moveCount, actual)
                assertEquals("$label keeps the save", save, actual.serialise().serialise())
            }
        }

    @Test
    fun `inert clicks keep the state and match the settled trace`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("inertia", "10x8", SEED).state
            for ((tile, board) in VIEWS) {
                val border = tile / 4
                val playerX = border + 7 * tile + tile / 2
                val playerY = border + tile / 2
                val cases = listOf(
                    "own tile" to OracleClient.Point(playerX, playerY, Buttons.LEFT_BUTTON),
                    // The player starts on the top row, so the octant
                    // above it points into a wall.
                    "wall" to OracleClient.Point(playerX, 0, Buttons.LEFT_BUTTON),
                    "middle" to OracleClient.Point(playerX, playerY, Buttons.MIDDLE_BUTTON),
                    "drag" to OracleClient.Point(playerX, playerY, Buttons.LEFT_DRAG),
                    "release" to OracleClient.Point(playerX, playerY, Buttons.LEFT_RELEASE),
                    // COORD(8)-1 is still the player's cell.
                    "left boundary" to OracleClient.Point(border + 8 * tile - 1, playerY, Buttons.LEFT_BUTTON),
                )
                for ((label, point) in cases) {
                    val expected = oracle.pointsSized(
                        save,
                        listOf(point),
                        board.width,
                        board.height,
                        recordTrace = true,
                    )
                    val actual = midend(save)
                    val before = player(actual)
                    val states = actual.stateCount
                    val moveCount = moves(actual).size
                    assertNotEquals(
                        "tile $tile $label outcome",
                        KeyOutcome.SomeEffect,
                        actual.processKey(point.x, point.y, point.button, tile),
                    )
                    assertSame("tile $tile $label", expected.state, actual)
                    assertInert("tile $tile $label", before, states, moveCount, actual)
                    assertTrace(
                        "tile $tile $label trace",
                        requireNotNull(expected.trace),
                        KotlinTraceSink.stateTrace(actual, tile),
                    )
                }
            }

            // The pixel on the boundary belongs to the next cell and is
            // a real move; the pixel before it is the player's cell.
            val tile = 48
            val board = requireNotNull(VIEWS[tile])
            val border = tile / 4
            val y = border + 4 * tile + tile / 2
            val boundary = OracleClient.Point(border + 5 * tile, y, Buttons.LEFT_BUTTON)
            val boundaryExpected = oracle.pointsSized(
                saveText("10x8", OPEN_10X8),
                listOf(boundary),
                board.width,
                board.height,
            )
            val boundaryActual = midend(saveText("10x8", OPEN_10X8))
            assertEquals(
                "boundary outcome",
                KeyOutcome.SomeEffect,
                boundaryActual.processKey(boundary.x, boundary.y, boundary.button, tile),
            )
            assertSame("boundary", boundaryExpected.state, boundaryActual)
            assertEquals("boundary move", listOf("2"), moves(boundaryActual))

            val inside = OracleClient.Point(border + 5 * tile - 1, y, Buttons.LEFT_BUTTON)
            val insideActual = midend(saveText("10x8", OPEN_10X8))
            val insideBefore = player(insideActual)
            val insideStates = insideActual.stateCount
            val insideMoves = moves(insideActual).size
            assertNotEquals(
                "inside the boundary outcome",
                KeyOutcome.SomeEffect,
                insideActual.processKey(inside.x, inside.y, inside.button, tile),
            )
            assertInert("inside the boundary", insideBefore, insideStates, insideMoves, insideActual)
        }

    @Test
    fun `a death counts once and the UI record keeps the deaths`() =
        OracleClient().use { oracle ->
            val save = saveText("3x2", "Sgmwbs")
            val expected = oracle.keys(save, listOf(keypad('6')))
            val actual = midend(save)
            val before = player(actual)
            val states = actual.stateCount
            val moveCount = moves(actual).size
            assertEquals("death outcome", KeyOutcome.SomeEffect, actual.processKey(0, 0, keypad('6'), 48))
            assertSame("death", expected, actual)
            assertEquals("death move", listOf("2"), moves(actual))
            assertRealMove("death", before, states, moveCount, actual)
            val dead = player(actual)
            assertTrue("the player is dead", dead.dead)
            assertEquals("the gem is collected before the mine", 0, dead.gems)
            assertEquals("the death does not complete", 0, actual.status())
            assertEquals("the death text", "DEAD!   Deaths: 1", actual.statusText())
            assertEquals("the UI record", "D1", actual.serialise().value("UI"))
            assertFalse("the death raises no completion", actual.takeCompletedEvent())

            // A loaded dead state keeps a non-zero death counter.
            val d2 = saveText("3x2", "Sgmwbs", listOf("MOVE" to "2"), "D2")
            val d2Expected = oracle.id(d2)
            val d2Actual = midend(d2)
            assertSame("dead D2", d2Expected, d2Actual)
            assertEquals("dead D2 UI", "D2", d2Actual.serialise().value("UI"))
            assertEquals("dead D2 text", "DEAD!   Deaths: 2", d2Actual.statusText())
            assertEquals("dead D2 game text", oracle.text(d2), d2Actual.textFormat())

            // The three-space separator also follows a win.
            val win = saveText("3x2", "Sgswbb", listOf("MOVE" to "2"), "D2")
            val winExpected = oracle.id(win)
            val winActual = midend(win)
            assertSame("win D2", winExpected, winActual)
            assertEquals("win D2 status", 1, winActual.status())
            assertEquals("win D2 text", "COMPLETED!   Deaths: 2", winActual.statusText())
        }

    @Test
    fun `a gem is collected on the way and the move stops`() =
        OracleClient().use { oracle ->
            val save = saveText("5x5", TWO_GEM_5X5)
            val expected = oracle.keys(save, listOf(keypad('7')))
            val actual = midend(save)
            val before = player(actual)
            val states = actual.stateCount
            val moveCount = moves(actual).size
            assertEquals("gem outcome", KeyOutcome.SomeEffect, actual.processKey(0, 0, keypad('7'), 48))
            assertSame("gem", expected, actual)
            assertEquals("gem move", listOf("7"), moves(actual))
            assertRealMove("gem", before, states, moveCount, actual)
            val after = player(actual)
            assertFalse("the player is alive", after.dead)
            assertEquals("one gem is collected", 1, after.gems)
            assertEquals("the stop ends the move", 0 to 0, after.px to after.py)
            assertEquals("the game is open", 0, actual.status())
            assertFalse("the move does not complete", actual.takeCompletedEvent())
        }

    @Test
    fun `an empty MOVE record is direction zero and traces like the oracle`() =
        OracleClient().use { oracle ->
            val open = saveText("5x5", OPEN_5X5)
            val save = saveText("5x5", OPEN_5X5, listOf("MOVE" to ""))
            val expected = oracle.id(save)
            val actual = midend(save)
            assertSame("empty move", expected, actual)
            assertEquals("the empty move is recorded", listOf(""), moves(actual))
            assertEquals("the empty move adds one state", 2, actual.stateCount)

            val start = player(midend(open))
            val moved = player(actual)
            assertEquals("the empty move goes up", 2, moved.px)
            assertEquals("the empty move goes up", 0, moved.py)
            assertEquals("the empty move collects nothing", start.gems, moved.gems)
            assertEquals("the empty move is not a win", 0, actual.status())

            assertTrace(
                "empty move trace",
                oracle.trace(save),
                KotlinTraceSink.stateTrace(actual),
            )
        }

    @Test
    fun `the cursor rectangle follows the settled player`() =
        OracleClient().use { oracle ->
            val save = saveText("10x8", OPEN_10X8)
            for (tile in VIEWS.keys) {
                val border = tile / 4
                val rect = midend(save).cursorLocation(tile)
                assertEquals(
                    "cursor at $tile",
                    Game.CursorRect(border + 4 * tile, border + 4 * tile, tile, tile),
                    rect,
                )
            }

            val actual = midend(save)
            assertEquals(
                "cursor outcome",
                KeyOutcome.SomeEffect,
                actual.processKey(0, 0, keypad('4'), 48),
            )
            val after = player(actual)
            val border = 48 / 4
            assertEquals(
                "the cursor follows the move",
                Game.CursorRect(border + after.px * 48, border + after.py * 48, 48, 48),
                actual.cursorLocation(48),
            )
        }

    @Test
    fun `requestKeys asks for the diagonal arrows on every preset`() =
        OracleClient().use { oracle ->
            for (preset in Inertia.presets()) {
                val save = oracle.newGame("inertia", preset.params, SEED).state
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(save, fullCursor)
                    val actual = Inertia.requestKeys(
                        Inertia.decodeParams(preset.params),
                        fullCursor,
                    )
                    assertEquals(
                        "'${preset.params}' $fullCursor arrow mode",
                        4,
                        expected.arrowMode,
                    )
                    assertEquals(
                        "'${preset.params}' $fullCursor",
                        ArrowMode.ARROWS_DIAGONALS,
                        actual.arrowMode,
                    )
                    assertTrue("'${preset.params}' $fullCursor labels", expected.labels.isEmpty())
                    assertTrue("'${preset.params}' $fullCursor labels", actual.labels.isEmpty())
                }
            }
        }

    @Test
    fun `a solve-follow select makes the tour move`() = OracleClient().use { oracle ->
        val start = oracle.newGame("inertia", "2x3", "1")
        assertEquals("2x3:mgbsSw", start.id)
        val solved = oracle.solve(start.state)
        val afterOne = oracle.keys(solved.state, listOf(Buttons.CURSOR_SELECT))
        val afterTwo = oracle.keys(afterOne.state, listOf(Buttons.CURSOR_SELECT))
        assertEquals("the tour completes", 1, afterTwo.status)
        assertNotEquals("the first select is a real move", solved.state, afterOne.state)
        assertNotEquals("the second select is a real move", afterOne.state, afterTwo.state)

        val actual = Midend.newGame(Inertia, "2x3", "1")
        assertTrue("the solve", actual.solve())
        assertSame("solved", solved, actual)
        assertEquals(
            "the select is labelled",
            "Advance",
            Inertia.currentKeyLabel(actual.ui, actual.state, Buttons.CURSOR_SELECT),
        )
        assertEquals(
            "a modified select has no label",
            "",
            Inertia.currentKeyLabel(
                actual.ui,
                actual.state,
                Buttons.MOD_CTRL or Buttons.CURSOR_SELECT,
            ),
        )

        val firstState = actual.stateCount
        val firstMoves = moves(actual).size
        val first = player(actual)
        assertEquals(
            "first select outcome",
            KeyOutcome.SomeEffect,
            actual.processKey(0, 0, Buttons.CURSOR_SELECT, 48),
        )
        assertSame("after one select", afterOne, actual)
        assertRealMove("first select", first, firstState, firstMoves, actual)
        assertEquals("the hint advances", 1, player(actual).solnPos)

        val secondState = actual.stateCount
        val secondMoves = moves(actual).size
        val second = player(actual)
        assertEquals(
            "second select outcome",
            KeyOutcome.SomeEffect,
            actual.processKey(0, 0, Buttons.CURSOR_SELECT, 48),
        )
        assertSame("after two selects", afterTwo, actual)
        assertRealMove("second select", second, secondState, secondMoves, actual)
        assertEquals("the tour completes", 1, actual.status())
        assertEquals("the solved text", "Auto-solved.", actual.statusText())
        assertFalse("a solver win raises no completion", actual.takeCompletedEvent())
    }

    /** The center of cell `(px,py) + delta` at [tile], in game coordinates. */
    private fun cellCenter(
        px: Int,
        py: Int,
        delta: Pair<Int, Int>,
        tile: Int,
    ): OracleClient.Point {
        val border = tile / 4
        return OracleClient.Point(
            border + (px + delta.first) * tile + tile / 2,
            border + (py + delta.second) * tile + tile / 2,
            Buttons.LEFT_BUTTON,
        )
    }

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>> = emptyList(),
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
