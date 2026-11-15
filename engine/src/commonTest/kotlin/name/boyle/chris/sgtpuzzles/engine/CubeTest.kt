package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: cube.
 *
 * Kotlin-only pins for the C tables, the description validator, the
 * initial state, the deep copy and equality, the bottom face, the move
 * grammar, the win, the palette, the geometry, the cursor rectangle, and
 * the keypad direction table.
 */
class CubeTest {

    private val tile = 48

    @Test
    fun `defaults and presets match the C tables`() {
        val defaults = Cube.defaultParams() as Cube.Params
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), defaults)
        assertEquals("c4x4", defaults.text)

        assertEquals(listOf(
                "Cube" to "c4x4",
                "Tetrahedron" to "t1x2",
                "Octahedron" to "o2x2",
                "Icosahedron" to "i3x3",
            ), Cube.presets().map { it.title to it.params })
        for (preset in Cube.presets()) {
            val params = Cube.decodeParams(preset.params)
            assertEquals(preset.params, Cube.encodeParams(params, true))
            assertEquals(preset.params, Cube.encodeParams(params, false))
        }

        assertEquals("Cube", Cube.name)
        assertEquals("cube", Cube.backendName)
        assertEquals(48, Cube.preferredTileSize)
        assertTrue(Cube.wantsStatusBar)
        assertFalse(Cube.canSolve)
        assertFalse(Cube.canFormatAsText)
        assertNull(Cube.solve(Cube.newState(defaults, "205E,4")))
    }

    @Test
    fun `decode params follows atoi and the first character`() {
        assertEquals(Cube.Params(Cube.CUBE, 0, 0), Cube.decodeParams(""))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("4x4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("04x04"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c4x4x5"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c 4x4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c+4x4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 0), Cube.decodeParams("c4x"))
        assertEquals(Cube.Params(Cube.CUBE, -3, -3), Cube.decodeParams("c-3"))
        assertEquals(Cube.Params(Cube.CUBE, 4, -2), Cube.decodeParams("c4x-2"))
        assertEquals(Cube.Params(Cube.CUBE, 0, 0), Cube.decodeParams("C4x4"))
        assertEquals(Cube.Params(Cube.TETRAHEDRON, 0, 0), Cube.decodeParams("tc4x4"))
        assertEquals(Cube.Params(Cube.TETRAHEDRON, 1, 2), Cube.decodeParams("t1x2"))
        assertEquals(Cube.Params(Cube.OCTAHEDRON, 2, 2), Cube.decodeParams("o2x2"))
        assertEquals(Cube.Params(Cube.ICOSAHEDRON, 3, 3), Cube.decodeParams("i3x3"))
    }

    @Test
    fun `validate params keeps the C messages and order`() {
        fun error(text: String): String? =
            Cube.validateParams(Cube.decodeParams(text), true)

        assertEquals(error("c1x1"), "Both grid dimensions must be greater than one")
        assertEquals(error("c4x-2"), "Grid dimensions may not be negative")
        assertEquals(error("t0x0"), "At least one grid dimension must be greater than zero")
        assertEquals(error("t1x1"), "Not enough grid space to place all blue faces")
        assertEquals(error("t0x2"), "Not enough space to place the solid on an empty square")
        assertEquals(error("c2x2"), "Not enough grid space to place all blue faces")
        assertEquals(error("c2x3"), "Not enough space to place the solid on an empty square")
        assertEquals(error("c46341x46341"), "Grid area must not be unreasonably large")
        assertEquals(error("t46341x1"), "Grid area must not be unreasonably large")
        assertEquals(error("t1x46341"), "Grid area must not be unreasonably large")
        assertEquals(Cube.validateParams(Cube.Params(4, 4, 4), true), "Unrecognised solid type")

        for (valid in listOf("c4x4", "t2x2", "t2x1", "t1x3", "t0x3", "t3x0",
            "o2x3", "o3x1", "o1x3", "i2x2", "i4x1", "c4x5", "c5x4", "c2x4", "c4x2")
        ) {
            assertNull(error(valid), "'$valid' must be valid")
            assertNull(Cube.validateParams(Cube.decodeParams(valid), false), "'$valid' full=false")
        }

        // The class-count step enumerates the grid, so a huge accepted
        // area is deliberately not called here.
    }

    @Test
    fun `validate desc pins the exact C strings`() {
        val params = Cube.decodeParams("c4x4")
        assertNull(Cube.validateDesc(params, "205E,4"))
        assertEquals(Cube.validateDesc(params, "205,4"), "Not enough hex digits at start of string")
        assertEquals(Cube.validateDesc(params, "205E4"), "Expected ',' after hex digits")
        assertEquals(Cube.validateDesc(params, "205E,"), "Expected decimal integer after ','")
        assertEquals(Cube.validateDesc(params, "205E,4x"), "Expected decimal integer after ','")
        assertEquals(Cube.validateDesc(params, "205G,4"), "Not enough hex digits at start of string")
        assertEquals(Cube.validateDesc(params, "205E"), "Expected ',' after hex digits")
    }

    @Test
    fun `new desc places one blue square per class and starts outside them`() {
        val expectedFaces = mapOf(
            "c4x4" to 6,
            "t1x2" to 4,
            "o2x2" to 8,
            "i3x3" to 20,
        )
        for ((text, nfaces) in expectedFaces) {
            val params = Cube.decodeParams(text) as Cube.Params
            val midend = Midend.newGame(Cube, text, "12345")
            val state = Cube.newState(params, midend.desc) as Cube.State
            assertEquals(nfaces, state.bluemask.count { it }, "'$text' blue count")
            assertFalse(state.bluemask[state.current], "'$text' starts off blue")
            assertEquals(0, state.completed, "'$text' initial completed")
            assertEquals(0, state.movecount, "'$text' initial movecount")
            assertTrue(state.facecolours.all { it == 0 }, "'$text' face colours start clear")
            assertNull(midend.auxInfo, "'$text' has no aux info")
        }

        assertEquals("205E,4", Midend.newGame(Cube, "c4x4", "12345").desc)
        assertEquals("9108,8", Midend.newGame(Cube, "t1x2", "12345").desc)
        assertEquals("13018B,14", Midend.newGame(Cube, "o2x2", "12345").desc)
        assertEquals("981F3821227120,23", Midend.newGame(Cube, "i3x3", "12345").desc)

        // The hex length is ceil(area / 4).
        assertEquals(4, Midend.newGame(Cube, "c4x4", "12345").desc.substringBefore(',').length)
        assertEquals(4, Midend.newGame(Cube, "t1x2", "12345").desc.substringBefore(',').length)
        assertEquals(6, Midend.newGame(Cube, "o2x2", "12345").desc.substringBefore(',').length)
        assertEquals(14, Midend.newGame(Cube, "i3x3", "12345").desc.substringBefore(',').length)
    }

    @Test
    fun `new state decodes 205E and the starting square`() {
        val params = Cube.decodeParams("c4x4") as Cube.Params
        val state = Cube.newState(params, "205E,4") as Cube.State
        assertEquals(16, state.bluemask.size)
        assertEquals(
            setOf(2, 9, 11, 12, 13, 14),
            state.bluemask.indices.filter { state.bluemask[it] }.toSet(),
        )
        assertEquals(4, state.current)
        assertEquals(6, state.facecolours.size)
        assertTrue(state.facecolours.all { it == 0 })
        assertEquals("Moves: 0", Cube.statusText(state, Game.NoUi))
        assertEquals(0, Cube.status(state))

        // An out-of-range index clamps to zero, and a spare low bit is
        // ignored.
        assertEquals(0, (Cube.newState(params, "205E,99") as Cube.State).current)
        assertEquals(
            Cube.newState(Cube.decodeParams("t1x2"), "4250,0"),
            Cube.newState(Cube.decodeParams("t1x2"), "4257,0"),
        )
    }

    @Test
    fun `copy is deep for the mutable arrays and equality ignores the grid`() {
        val params = Cube.decodeParams("c4x4") as Cube.Params
        val state = Cube.newState(params, "205E,4") as Cube.State
        val copy = state.copy() as Cube.State
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())

        copy.facecolours[0] = 1
        copy.bluemask[0] = true
        copy.movecount = 7
        assertNotEquals(state, copy)
        assertEquals(0, state.facecolours[0])
        assertFalse(state.bluemask[0])
        assertEquals(0, state.movecount)

        val other = Cube.newState(params, "205E,4") as Cube.State
        assertEquals(state, other)
        assertNotEquals(state, copy)
    }

    @Test
    fun `the bottom face is a constant of the static solid`() {
        assertEquals(3, Cube.lowestFace(Cube.decodeParams("c4x4") as Cube.Params))
        assertEquals(3, Cube.lowestFace(Cube.decodeParams("t1x2") as Cube.Params))
        assertEquals(4, Cube.lowestFace(Cube.decodeParams("o2x2") as Cube.Params))
        val icosa = Cube.lowestFace(Cube.decodeParams("i3x3") as Cube.Params)
        assertTrue(icosa in 0 until 20, "icosahedron bottom face $icosa")
    }

    @Test
    fun `the move grammar reads only the first character`() {
        val midend = Midend.newGame(Cube, "c4x4", "12345")
        val state = midend.state
        for (move in listOf("l", "u", "X", "")) {
            assertNull(Cube.executeMove(state, move), "'$move' is invalid")
        }
        for (move in listOf("U", "Ux", "UU")) {
            assertEquals(
                Cube.executeMove(state, "U"),
                Cube.executeMove(state, move),
                "trailing characters are ignored for '$move'",
            )
        }
        // DOWN has no destination on an up-pointing triangle.
        val tetra = Midend.newGame(Cube, "t1x2", "12345")
        assertNull(Cube.executeMove(tetra.state, "D"))
        // LEFT has no destination at the left border of the square grid.
        assertNull(Cube.executeMove(state, "L"))
    }

    @Test
    fun `the 13-move win completes and freezes the text`() {
        val midend = Midend.newGame(Cube, "c4x4", "12345")
        val keys = mapOf(
            'U' to Buttons.CURSOR_UP,
            'D' to Buttons.CURSOR_DOWN,
            'L' to Buttons.CURSOR_LEFT,
            'R' to Buttons.CURSOR_RIGHT,
        )
        for (letter in WIN) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, keys.getValue(letter), tile),
                "outcome before '$letter'",
            )
        }
        val win = midend.state as Cube.State
        assertEquals(13, win.completed)
        assertEquals(13, win.movecount)
        assertTrue(win.facecolours.all { it != 0 })
        assertEquals(1, midend.status())
        assertEquals("COMPLETED! Moves: 13", midend.statusText())
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_UP, tile))
        val postWin = midend.state as Cube.State
        assertEquals(13, postWin.completed)
        assertEquals(14, postWin.movecount)
        assertEquals("COMPLETED! Moves: 13", midend.statusText())
        assertFalse(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(1, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `clicks choose the C bearing and the centre is inert`() {
        val midend = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(KeyOutcome.NoEffect, midend.processKey(48, 96, Buttons.LEFT_BUTTON, tile))
        assertEquals(1, midend.stateCount)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(49, 96, Buttons.LEFT_BUTTON, tile))
        assertEquals("R", (SgtpSave.parse(midend.serialise().serialise()).values("MOVE")).single())

        val up = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(KeyOutcome.SomeEffect, up.processKey(48, 95, Buttons.LEFT_BUTTON, tile))
        assertEquals("U", SgtpSave.parse(up.serialise().serialise()).values("MOVE").single())

        val down = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(KeyOutcome.SomeEffect, down.processKey(48, 97, Buttons.LEFT_BUTTON, tile))
        assertEquals("D", SgtpSave.parse(down.serialise().serialise()).values("MOVE").single())

        val left = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(KeyOutcome.NoEffect, left.processKey(47, 96, Buttons.LEFT_BUTTON, tile))
        assertEquals(1, left.stateCount)

        // The exact 45-degree diagonals resolve to the vertical moves.
        val diagonalUp = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(KeyOutcome.SomeEffect, diagonalUp.processKey(49, 95, Buttons.LEFT_BUTTON, tile))
        assertEquals("U", SgtpSave.parse(diagonalUp.serialise().serialise()).values("MOVE").single())
        val diagonalDown = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(KeyOutcome.SomeEffect, diagonalDown.processKey(47, 97, Buttons.LEFT_BUTTON, tile))
        assertEquals("D", SgtpSave.parse(diagonalDown.serialise().serialise()).values("MOVE").single())
    }

    @Test
    fun `a real click changes the faces and the count`() {
        val midend = Midend.newGame(Cube, "c4x4", "12345")
        val before = midend.state as Cube.State
        val faces = before.facecolours.toList()
        val blues = before.bluemask.toList()
        // Two downward clicks reach the blue square (0,3).
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(48, 144, Buttons.LEFT_BUTTON, tile))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(48, 192, Buttons.LEFT_BUTTON, tile))
        val after = midend.state as Cube.State
        assertNotEquals(faces, after.facecolours.toList())
        assertNotEquals(blues, after.bluemask.toList())
        assertEquals(2, after.movecount)
        assertEquals(3, midend.stateCount)
    }

    @Test
    fun `the keypad table matches the C directions`() {
        val table = mapOf(
            "c4x4" to "-D--xR-U-",
            "t1x2" to "--R-xRUUU",
            "o2x2" to "L-RLxRUUU",
            "i3x3" to "DDDLxRL-R",
        )
        for ((params, directions) in table) {
            for ((index, digit) in ('1'..'9').withIndex()) {
                val midend = Midend.newGame(Cube, params, "12345")
                val expected = when (directions[index]) {
                    '-' -> KeyOutcome.NoEffect
                    'x' -> KeyOutcome.Unused
                    else -> KeyOutcome.SomeEffect
                }
                assertEquals(
                    expected,
                    midend.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or digit.code, tile),
                    "'$params' keypad $digit",
                )
                assertEquals(
                    if (expected == KeyOutcome.SomeEffect) 2 else 1,
                    midend.stateCount,
                    "'$params' keypad $digit states",
                )
            }
            val plain = Midend.newGame(Cube, params, "12345")
            assertEquals(KeyOutcome.Unused, plain.processKey(0, 0, '8'.code, tile))
        }
    }

    @Test
    fun `sizes and cursor rectangles match the C geometry`() {
        val expected = mapOf(
            "c4x4" to Size(240, 240),
            "t1x2" to Size(172, 153),
            "o2x2" to Size(240, 214),
            "i3x3" to Size(364, 326),
        )
        for ((text, size) in expected) {
            assertEquals(size, Cube.computeSize(Cube.decodeParams(text), tile), text)
        }

        val cube = Midend.newGame(Cube, "c4x4", "12345")
        assertEquals(Game.CursorRect(24, 72, 48, 48), cube.cursorLocation(48))
        assertEquals(Game.CursorRect(32, 94, 63, 63), cube.cursorLocation(63))
        assertEquals(Game.CursorRect(41, 123, 82, 82), cube.cursorLocation(82))
        assertEquals(Game.CursorRect(50, 150, 100, 100), cube.cursorLocation(100))

        val tetra = Midend.newGame(Cube, "t1x2", "12345")
        assertEquals(Game.CursorRect(14, 97, 48, 41), tetra.cursorLocation(48))
    }

    @Test
    fun `the palette is the three C colours`() {
        val colours = Cube.colours()
        assertEquals(9, colours.size)
        for (i in 0..2) assertEquals(0.8f, colours[i])
        for (i in 3..5) assertEquals(0.0f, colours[i])
        assertEquals(0.0f, colours[6])
        assertEquals(0.0f, colours[7])
        assertEquals(1.0f, colours[8])

        val custom = Cube.colours(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(0.1f, custom[0])
        assertEquals(0.2f, custom[1])
        assertEquals(0.3f, custom[2])
        assertEquals(1.0f, custom[8])
    }

    @Test
    fun `request keys asks for arrows only`() {
        val request = Cube.requestKeys(Cube.decodeParams("c4x4"))
        assertEquals(ArrowMode.ARROWS_ONLY, request.arrowMode)
        assertTrue(request.labels.isEmpty())
        assertEquals(
            "",
            Cube.currentKeyLabel(
                Game.NoUi,
                Cube.newState(Cube.decodeParams("c4x4"), "205E,4"),
                ' '.code,
            ),
        )
    }

    private companion object {
        val WIN = listOf('U', 'R', 'R', 'D', 'D', 'R', 'L', 'D', 'L', 'L', 'R', 'U', 'D')
    }
}
