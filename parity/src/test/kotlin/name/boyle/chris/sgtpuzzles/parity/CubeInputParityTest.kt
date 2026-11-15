package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: cube.
 *
 * The layout, real square clicks and press-drag-release gestures at
 * tiles 48, 63, 82, and 100, the 45-degree diagonals, a letterbox click
 * and drag that are moves (C has no board-bounds test), the cursor
 * keys, the keypad digits and diagonals, modified cursor keys, the
 * three pointer buttons, the select keys, Enter, Space, TAB, the
 * 13-move win with undo and redo, and the requested keys.
 *
 * Every real rotation must move the die, change the board, and add one
 * move. The face fills follow the C roll: a mixed die gets a new
 * permutation on every rotation. A die whose faces all hold the same
 * colour, as at the empty start and after the win, keeps them. Every
 * vacuous event must change neither the board nor the count.
 */
class CubeInputParityTest {

    /** A viewport and the tile size C picks for it. */
    private data class View(
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private val views = listOf(
        View(240, 240, 48, 0, 0),
        View(315, 315, 63, 0, 0),
        View(410, 410, 82, 0, 0),
        View(500, 500, 100, 0, 0),
        View(411, 702, 82, 0, 146),
    )

    @Test
    fun `the layout matches the oracle at five viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("cube", "c4x4", SEED).state
            for (view in views) {
                val layout = oracle.layout(save, view.viewWidth, view.viewHeight)
                val geometry = Midend.newGame(Cube, "c4x4", SEED)
                    .geometryFor(view.viewWidth, view.viewHeight)
                val label = "${view.viewWidth}x${view.viewHeight}"
                assertEquals("tile at $label", view.tile, geometry.tileSize)
                assertEquals("width at $label", layout.width, geometry.width)
                assertEquals("height at $label", layout.height, geometry.height)
                assertEquals("origin x at $label", view.originX, geometry.originX)
                assertEquals("origin y at $label", view.originY, geometry.originY)
            }

            for (case in listOf(
                Triple(720, 1280, 144),
                Triple(1024, 600, 120),
                Triple(200, 200, 40),
            )) {
                val geometry = Midend.newGame(Cube, "c4x4", SEED)
                    .geometryFor(case.first, case.second)
                assertEquals("tile at ${case.first}x${case.second}", case.third, geometry.tileSize)
            }
        }

    @Test
    fun `real clicks and the exact centre match the oracle at four tiles`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("cube", "c4x4", SEED).state
            for (view in views.filter { it.viewWidth <= 500 }) {
                val label = "tile ${view.tile}"
                val tile = view.tile
                // The initial square is (0,1), so the C centre is
                // ((0+1)*tile, (1+1)*tile).
                val cx = tile
                val cy = 2 * tile

                checkClick(oracle, save, view, cx, cy, KeyOutcome.NoEffect, label)
                checkClick(oracle, save, view, cx + 1, cy, KeyOutcome.SomeEffect, "$label right")
                checkClick(oracle, save, view, cx, cy - 1, KeyOutcome.SomeEffect, "$label up")
                checkClick(oracle, save, view, cx, cy + 1, KeyOutcome.SomeEffect, "$label down")
                // The left border has no square, so LEFT has no
                // destination. This is not a "no move" rule about the
                // pointer: the bearing is still LEFT.
                checkClick(oracle, save, view, cx - 1, cy, KeyOutcome.NoEffect, "$label left")
            }
        }

    @Test
    fun `the exact 45-degree diagonals resolve to the vertical moves`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("cube", "c4x4", SEED).state
            val view = View(315, 315, 63, 0, 0)
            // At tile 63 the centre is (63,126), so (64,125) is the
            // up-right 45-degree diagonal: the strict comparisons make
            // it UP, not RIGHT. The opposite diagonal is DOWN.
            checkClick(oracle, save, view, 64, 125, KeyOutcome.SomeEffect, "up-right diagonal")
            checkClick(oracle, save, view, 62, 127, KeyOutcome.SomeEffect, "down-left diagonal")
        }

    @Test
    fun `a letterbox click is a move, not a no-op`() = OracleClient().use { oracle ->
        val save = oracle.newGame("cube", "c4x4", SEED).state
        val view = View(411, 702, 82, 0, 146)

        // View (0,0) is game (0,-146). C has no bounds test, so the
        // bearing from the centre is UP and the die moves.
        val letterbox = oracle.pointsSized(
            save,
            listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
            view.viewWidth,
            view.viewHeight,
        )
        assertNotEquals("the letterbox click moves", save, letterbox.state.state)
        assertEquals(listOf("U"), movesOf(letterbox.state.state))

        val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
        assertEquals(
            "Kotlin letterbox outcome",
            KeyOutcome.SomeEffect,
            midend.processKey(0, -view.originY, Buttons.LEFT_BUTTON, view.tile),
        )
        assertEquals("Kotlin letterbox save", letterbox.state.state, midend.serialise().serialise())

        // The exact centre in view coordinates is inert.
        checkClick(oracle, save, view, view.tile, 2 * view.tile, KeyOutcome.NoEffect, "letterbox centre")
    }

    @Test
    fun `a press, drag, and release make one move at four tiles and the letterbox`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("cube", "c4x4", SEED).state
            for (view in views) {
                val label = "tile ${view.tile} origin (${view.originX},${view.originY})"
                val cx = view.tile
                val cy = 2 * view.tile
                // The oracle takes view coordinates and subtracts the
                // letterbox origin; Kotlin takes game coordinates.
                val points = listOf(
                    OracleClient.Point(
                        view.originX + cx + 1,
                        view.originY + cy,
                        Buttons.LEFT_BUTTON,
                    ),
                    OracleClient.Point(
                        view.originX + cx + 10,
                        view.originY + cy + 10,
                        Buttons.LEFT_DRAG,
                    ),
                    OracleClient.Point(
                        view.originX + cx + 20,
                        view.originY + cy + 20,
                        Buttons.LEFT_RELEASE,
                    ),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    view.viewWidth,
                    view.viewHeight,
                )
                assertEquals(
                    "$label drag adds no move of its own",
                    listOf("R"),
                    movesOf(expected.state.state),
                )

                val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
                val before = midend.state as Cube.State
                assertEquals(
                    "$label press",
                    KeyOutcome.SomeEffect,
                    midend.processKey(cx + 1, cy, Buttons.LEFT_BUTTON, view.tile),
                )
                assertEquals(
                    "$label drag",
                    KeyOutcome.Unused,
                    midend.processKey(cx + 10, cy + 10, Buttons.LEFT_DRAG, view.tile),
                )
                assertEquals(
                    "$label release",
                    KeyOutcome.Unused,
                    midend.processKey(cx + 20, cy + 20, Buttons.LEFT_RELEASE, view.tile),
                )
                assertEquals("$label drag and release add no state", 2, midend.stateCount)
                assertEquals("$label drag save", expected.state.state, midend.serialise().serialise())
                val after = midend.state as Cube.State
                assertEquals("$label counts one move", before.movecount + 1, after.movecount)
                assertNotEquals("$label moves the die", before.current, after.current)
            }
        }

    @Test
    fun `unused buttons, modifier keys, and plain digits change nothing`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("cube", "c4x4", SEED).state
            val view = views.first { it.tile == 63 }
            val buttons = listOf(
                "RIGHT_BUTTON" to Buttons.RIGHT_BUTTON,
                "MIDDLE_BUTTON" to Buttons.MIDDLE_BUTTON,
                "LEFT_DRAG" to Buttons.LEFT_DRAG,
                "LEFT_RELEASE" to Buttons.LEFT_RELEASE,
                "CURSOR_SELECT" to Buttons.CURSOR_SELECT,
                "CURSOR_SELECT2" to Buttons.CURSOR_SELECT2,
                "Enter" to '\n'.code,
                "Space" to ' '.code,
                "TAB" to '\t'.code,
                "plain 8" to '8'.code,
            )
            for ((label, button) in buttons) {
                val expected = oracle.pointsSized(
                    save,
                    listOf(OracleClient.Point(0, 0, button)),
                    view.viewWidth,
                    view.viewHeight,
                )
                assertEquals("$label keeps the save", save, expected.state.state)

                val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
                val before = midend.state
                assertEquals(
                    "$label is unused",
                    KeyOutcome.Unused,
                    midend.processKey(0, 0, button, view.tile),
                )
                assertEquals("$label adds no state", 1, midend.stateCount)
                assertEquals("$label keeps the board", before, midend.state)
                assertEquals("$label Kotlin save", expected.state.state, midend.serialise().serialise())
            }
        }

    @Test
    fun `modified cursor keys move and the keypad table matches the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("cube", "c4x4", SEED).state
            val modified = listOf(
                Triple("CTRL+UP", Buttons.CURSOR_UP or Buttons.MOD_CTRL, "U"),
                Triple("SHFT+UP", Buttons.CURSOR_UP or Buttons.MOD_SHFT, "U"),
                Triple(
                    "CTRL+SHFT+UP",
                    Buttons.CURSOR_UP or Buttons.MOD_CTRL or Buttons.MOD_SHFT,
                    "U",
                ),
                Triple("CTRL+RIGHT", Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, "R"),
                Triple("SHFT+DOWN", Buttons.CURSOR_DOWN or Buttons.MOD_SHFT, "D"),
                Triple("keypad 8", Buttons.MOD_NUM_KEYPAD or '8'.code, "U"),
            )
            for ((label, key, direction) in modified) {
                val expected = oracle.pointsSized(
                    save,
                    listOf(OracleClient.Point(0, 0, key)),
                    TILE * 5,
                    TILE * 5,
                )
                assertEquals(
                    "$label moves in C",
                    listOf(direction),
                    movesOf(expected.state.state),
                )

                val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
                assertEquals(
                    "$label outcome",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, TILE),
                )
                assertEquals("$label save", expected.state.state, midend.serialise().serialise())
                assertEquals(
                    "$label counts one move",
                    1,
                    (midend.state as Cube.State).movecount,
                )
                assertEquals("$label adds one state", 2, midend.stateCount)
            }

            // The keypad table from the specification, for the initial
            // state of every preset. A '-' means the key has no
            // destination or the direction mask is zero.
            // Columns are digits 1..9. `x` is the unused digit 5, and
            // `-` is a direction with no destination or a zero mask.
            val table = mapOf(
                "c4x4" to "-D--xR-U-",
                "t1x2" to "--R-xRUUU",
                "o2x2" to "L-RLxRUUU",
                "i3x3" to "DDDLxRL-R",
            )
            for ((params, expectedDirections) in table) {
                val presetSave = oracle.newGame("cube", params, SEED).state
                for ((index, digit) in ('1'..'9').withIndex()) {
                    val key = Buttons.MOD_NUM_KEYPAD or digit.code
                    val expected = oracle.pointsSized(
                        presetSave,
                        listOf(OracleClient.Point(0, 0, key)),
                        TILE * 8,
                        TILE * 8,
                    )
                    val midend = Midend.fromSave(Cube, SgtpSave.parse(presetSave))
                    val expectedOutcome = when (expectedDirections[index]) {
                        '-' -> KeyOutcome.NoEffect
                        'x' -> KeyOutcome.Unused
                        else -> KeyOutcome.SomeEffect
                    }
                    assertEquals(
                        "'$params' keypad $digit outcome",
                        expectedOutcome,
                        midend.processKey(0, 0, key, TILE),
                    )
                    assertEquals(
                        "'$params' keypad $digit save",
                        expected.state.state,
                        midend.serialise().serialise(),
                    )
                    if (expectedDirections[index] != '-' &&
                        expectedDirections[index] != 'x'
                    ) {
                        assertEquals(
                            "'$params' keypad $digit moves once",
                            2,
                            midend.stateCount,
                        )
                        assertEquals(
                            "'$params' keypad $digit move",
                            expectedDirections[index].toString(),
                            movesOf(expected.state.state).single(),
                        )
                    }
                }
            }

            // A plain digit without MOD_NUM_KEYPAD is never a move.
            for (digit in '1'..'9') {
                val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
                assertEquals(
                    "plain $digit",
                    KeyOutcome.Unused,
                    midend.processKey(0, 0, digit.code, TILE),
                )
                assertEquals(1, midend.stateCount)
            }
        }

    @Test
    fun `a real rotation moves the die and counts, and lands colours as C does`() =
        OracleClient().use { oracle ->
            // C keeps the solid fixed and rolls the colours: the face
            // permutation changes on every rotation, and the bottom
            // face takes the colour of the landing square
            // (`cube.c:1359-1414`). Every rotation moves the die and
            // counts one move. A die whose faces are all the same
            // colour keeps them; the empty start is that case.
            val save = oracle.newGame("cube", "c4x4", SEED).state
            val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
            var expected = save

            val start = midend.state as Cube.State
            assertEquals(
                "the start has no face colour",
                listOf(0, 0, 0, 0, 0, 0),
                start.facecolours.toList(),
            )

            // DOWN twice: (0,1) to the white (0,2), then the blue (0,3).
            // The first rotation permutes six zero faces and lands on
            // white, so the colours stay zero. The second picks the blue
            // up and fills the bottom face.
            expected = oracle.keys(expected, listOf(Buttons.CURSOR_DOWN)).state
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_DOWN, TILE),
            )
            assertEquals(
                "save after the first DOWN",
                expected,
                midend.serialise().serialise(),
            )
            val first = midend.state as Cube.State
            assertEquals("the first DOWN counts", 1, first.movecount)
            assertEquals("the first DOWN moves the die", 8, first.current)
            assertEquals(
                "the first DOWN keeps the zero faces",
                listOf(0, 0, 0, 0, 0, 0),
                first.facecolours.toList(),
            )

            expected = oracle.keys(expected, listOf(Buttons.CURSOR_DOWN)).state
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_DOWN, TILE),
            )
            assertEquals(
                "save after the second DOWN",
                expected,
                midend.serialise().serialise(),
            )
            val onBlue = midend.state as Cube.State
            assertEquals("two moves counted", 2, onBlue.movecount)
            assertEquals("the die is on (0,3)", 12, onBlue.current)
            assertEquals("the blue landing fills the bottom face", 1, onBlue.facecolours[3])
            assertEquals("three states", 3, midend.stateCount)

            // RIGHT from (0,3) lands on the blue (1,3): the roll
            // permutes the mixed faces, so the fills change, and the
            // bottom face keeps colour 1.
            expected = oracle.keys(expected, listOf(Buttons.CURSOR_RIGHT)).state
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE),
            )
            assertEquals(
                "save after the blue landing",
                expected,
                midend.serialise().serialise(),
            )
            val equal = midend.state as Cube.State
            assertNotEquals(
                "the blue landing changes the face permutation",
                onBlue.facecolours.toList(),
                equal.facecolours.toList(),
            )
            assertEquals("the blue landing keeps the bottom face", 1, equal.facecolours[3])
            assertEquals("the blue landing counts", 3, equal.movecount)
            assertNotEquals("the blue landing moves the die", onBlue.current, equal.current)
            assertEquals("four states", 4, midend.stateCount)

            // LEFT from (1,3) lands on the white (0,3) it left behind:
            // the bottom face clears and the count advances once.
            expected = oracle.keys(expected, listOf(Buttons.CURSOR_LEFT)).state
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_LEFT, TILE),
            )
            val white = midend.state as Cube.State
            assertEquals(
                "save after the white landing",
                expected,
                midend.serialise().serialise(),
            )
            assertEquals("the white landing clears the bottom face", 0, white.facecolours[3])
            assertEquals("the white landing counts", 4, white.movecount)
            assertNotEquals(
                "the white landing changes the fills",
                equal.facecolours.toList(),
                white.facecolours.toList(),
            )
        }

    @Test
    fun `the win, the undo, and the redo match the oracle with the requested keys`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Cube, "c4x4", SEED)
            var expected = oracle.newGame("cube", "c4x4", SEED)
            assertRequestKeys(oracle.requestKeys(expected.state), midend, "new game")

            for (letter in WIN) {
                val key = when (letter) {
                    'U' -> Buttons.CURSOR_UP
                    'D' -> Buttons.CURSOR_DOWN
                    'L' -> Buttons.CURSOR_LEFT
                    'R' -> Buttons.CURSOR_RIGHT
                    else -> error("not a move: $letter")
                }
                expected = oracle.keys(expected.state, listOf(key))
                assertEquals(
                    "outcome before '$letter'",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, TILE),
                )
                assertEquals("save after '$letter'", expected.state, midend.serialise().serialise())
                assertEquals("id after '$letter'", expected.id, midend.id())
                assertEquals("status after '$letter'", expected.status, midend.status())
                assertRequestKeys(oracle.requestKeys(expected.state), midend, "after '$letter'")
            }
            assertEquals("win text", "COMPLETED! Moves: 13", midend.statusText())
            assertEquals("win states", 14, midend.stateCount)

            expected = oracle.undo(expected.state)
            assertTrue("undo", midend.undo())
            assertEquals("undo save", expected.state, midend.serialise().serialise())
            assertEquals("undo id", expected.id, midend.id())
            assertEquals("undo status", expected.status, midend.status())
            assertEquals("undo text", "Moves: 12", midend.statusText())
            assertRequestKeys(oracle.requestKeys(expected.state), midend, "after undo")

            expected = oracle.redo(expected.state)
            assertTrue("redo", midend.redo())
            assertEquals("redo save", expected.state, midend.serialise().serialise())
            assertEquals("redo id", expected.id, midend.id())
            assertEquals("redo status", expected.status, midend.status())
            assertEquals("redo text", "COMPLETED! Moves: 13", midend.statusText())
            assertRequestKeys(oracle.requestKeys(expected.state), midend, "after redo")

            expected = oracle.keys(expected.state, listOf(Buttons.CURSOR_UP))
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_UP, TILE),
            )
            assertEquals("post-win save", expected.state, midend.serialise().serialise())
            assertEquals("post-win text", "COMPLETED! Moves: 13", midend.statusText())
            assertRequestKeys(
                oracle.requestKeys(expected.state),
                midend,
                "after the post-win move",
            )
        }

    private fun assertRequestKeys(
        keys: OracleClient.RequestKeys,
        midend: Midend,
        label: String,
    ) {
        // cube.c:903-908 returns no keys and ANDROID_ARROWS_ONLY (1).
        assertEquals("$label arrow mode", 1, keys.arrowMode)
        assertTrue("$label labels", keys.labels.isEmpty())
        val request = Cube.requestKeys(midend.params)
        assertEquals("$label Kotlin arrow mode", ArrowMode.ARROWS_ONLY, request.arrowMode)
        assertTrue("$label Kotlin labels", request.labels.isEmpty())
    }

    private fun checkClick(
        oracle: OracleClient,
        save: String,
        view: View,
        gameX: Int,
        gameY: Int,
        expected: KeyOutcome,
        label: String,
    ) {
        val expectedResult = oracle.pointsSized(
            save,
            listOf(
                OracleClient.Point(
                    view.originX + gameX,
                    view.originY + gameY,
                    Buttons.LEFT_BUTTON,
                ),
            ),
            view.viewWidth,
            view.viewHeight,
        )
        val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
        val before = midend.state as Cube.State
        assertEquals(
            "$label outcome",
            expected,
            midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, view.tile),
        )
        assertEquals("$label save", expectedResult.state.state, midend.serialise().serialise())
        assertEquals("$label id", expectedResult.state.id, midend.id())
        assertEquals("$label status", expectedResult.state.status, midend.status())
        val after = midend.state as Cube.State
        if (expected == KeyOutcome.SomeEffect) {
            assertNotEquals("$label changes the board", before, after)
            assertEquals("$label adds one state", 2, midend.stateCount)
            assertEquals("$label counts one move", before.movecount + 1, after.movecount)
            assertNotEquals("$label moves the die", before.current, after.current)
        } else {
            assertEquals("$label keeps the board", before, after)
            assertEquals("$label adds no state", 1, midend.stateCount)
            assertEquals("$label keeps the count", before.movecount, after.movecount)
        }
    }

    private fun movesOf(state: String): List<String> =
        SgtpSave.parse(state).values("MOVE")

    private companion object {
        const val SEED = "12345"
        const val TILE = 63
        val WIN = listOf('U', 'R', 'R', 'D', 'D', 'R', 'L', 'D', 'L', 'L', 'R', 'U', 'D')
    }
}
