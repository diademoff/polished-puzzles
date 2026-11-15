package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: cube.
 *
 * The layout, real square clicks at tiles 48, 63, 82, and 100, the
 * 45-degree diagonals, a letterbox click that is a move (C has no
 * board-bounds test), the cursor keys, the keypad digits and diagonals,
 * modified cursor keys, the three pointer buttons, the select keys,
 * Enter, Space, TAB, and a drag and release.
 *
 * Every real rotation must add one state and change the faces and the
 * move count. Every vacuous event must change neither.
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
    fun `a drag and release after the press add nothing`() = OracleClient().use { oracle ->
        val save = oracle.newGame("cube", "c4x4", SEED).state
        val view = View(315, 315, 63, 0, 0)
        val points = listOf(
            OracleClient.Point(view.tile + 1, 2 * view.tile, Buttons.LEFT_BUTTON),
            OracleClient.Point(view.tile + 10, 2 * view.tile + 10, Buttons.LEFT_DRAG),
            OracleClient.Point(view.tile + 20, 2 * view.tile + 20, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(
            save,
            points,
            view.viewWidth,
            view.viewHeight,
        )
        assertEquals("a drag adds no move of its own", listOf("R"), movesOf(expected.state.state))

        val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
        assertEquals(
            "press",
            KeyOutcome.SomeEffect,
            midend.processKey(view.tile + 1, 2 * view.tile, Buttons.LEFT_BUTTON, view.tile),
        )
        assertEquals(
            "drag",
            KeyOutcome.Unused,
            midend.processKey(
                view.tile + 10,
                2 * view.tile + 10,
                Buttons.LEFT_DRAG,
                view.tile,
            ),
        )
        assertEquals(
            "release",
            KeyOutcome.Unused,
            midend.processKey(
                view.tile + 20,
                2 * view.tile + 20,
                Buttons.LEFT_RELEASE,
                view.tile,
            ),
        )
        assertEquals("a drag and its release add no state", 2, midend.stateCount)
        assertEquals("a drag save", expected.state.state, midend.serialise().serialise())
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
                "CTRL+UP" to (Buttons.CURSOR_UP or Buttons.MOD_CTRL),
                "SHFT+UP" to (Buttons.CURSOR_UP or Buttons.MOD_SHFT),
                "CTRL+SHFT+UP" to
                    (Buttons.CURSOR_UP or Buttons.MOD_CTRL or Buttons.MOD_SHFT),
                "keypad 8" to (Buttons.MOD_NUM_KEYPAD or '8'.code),
            )
            for ((label, key) in modified) {
                val expected = oracle.pointsSized(
                    save,
                    listOf(OracleClient.Point(0, 0, key)),
                    TILE * 5,
                    TILE * 5,
                )
                assertEquals("$label moves in C", listOf("U"), movesOf(expected.state.state))

                val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
                assertEquals(
                    "$label outcome",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, TILE),
                )
                assertEquals("$label save", expected.state.state, midend.serialise().serialise())
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
        val before = midend.state
        assertEquals(
            "$label outcome",
            expected,
            midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, view.tile),
        )
        assertEquals("$label save", expectedResult.state.state, midend.serialise().serialise())
        assertEquals("$label id", expectedResult.state.id, midend.id())
        assertEquals("$label status", expectedResult.state.status, midend.status())
        if (expected == KeyOutcome.SomeEffect) {
            assertNotEquals("$label changes the board", before, midend.state)
            assertEquals("$label adds one state", 2, midend.stateCount)
        } else {
            assertEquals("$label keeps the board", before, midend.state)
            assertEquals("$label adds no state", 1, midend.stateCount)
        }
    }

    private fun movesOf(state: String): List<String> =
        SgtpSave.parse(state).values("MOVE")

    private companion object {
        const val SEED = "12345"
        const val TILE = 63
    }
}
