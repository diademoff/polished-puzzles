package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Level: semantic. The API edges, the guard, and the drawing cache of Light Up. */
class LightupTest {

    // ------------------------------------------------------------------
    // Parameters, presets, and the guard
    // ------------------------------------------------------------------

    @Test
    fun `parameters decode, encode, and validate`() {
        val d = Lightup.defaultParams() as Lightup.Params
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), d)
        assertEquals("Light Up", Lightup.name)
        assertEquals("lightup", Lightup.backendName)
        assertEquals(32, Lightup.preferredTileSize)
        assertFalse(Lightup.wantsStatusBar)
        assertTrue(Lightup.canSolve)
        assertTrue(Lightup.canFormatAsText)

        assertEquals("7x7b20s3d0", Lightup.encodeParams(d, true))
        assertEquals("7x7", Lightup.encodeParams(d, false))
        assertEquals(
            "10x10b35s2d2",
            Lightup.encodeParams(Lightup.Params(10, 10, 35, 2, 2), true),
        )
        assertEquals(
            "10x10",
            Lightup.encodeParams(Lightup.Params(10, 10, 35, 2, 2), false),
        )

        assertEquals(
            listOf(
                "7x7 Easy", "7x7 Tricky", "7x7 Hard",
                "10x10 Easy", "10x10 Tricky", "10x10 Hard",
                "14x14 Easy", "14x14 Tricky", "14x14 Hard",
            ),
            Lightup.presets().map { it.title },
        )
        assertEquals(
            listOf(
                "7x7b20s3d0", "7x7b20s3d1", "7x7b20s3d2",
                "10x10b20s2d0", "10x10b20s2d1", "10x10b20s2d2",
                "14x14b20s2d0", "14x14b20s2d1", "14x14b20s2d2",
            ),
            Lightup.presets().map { it.params },
        )

        // The decodes of the specification.
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), Lightup.decodeParams("7x7"))
        assertEquals(Lightup.Params(5, 7, 20, 3, 0), Lightup.decodeParams(" 5x5"))
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), Lightup.decodeParams("+7x7"))
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), Lightup.decodeParams("7X7"))
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), Lightup.decodeParams("7x7x7"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 0), Lightup.decodeParams("5x5s"))
        assertEquals(
            Lightup.Params(5, 5, 20, 0, 0),
            Lightup.decodeParams("5x5b20s0s2d0"),
        )
        assertEquals(Lightup.Params(5, 5, 20, 3, 0), Lightup.decodeParams("5x5d"))
        assertEquals(
            Lightup.Params(5, 5, 20, 0, 0),
            Lightup.decodeParams("5x5b20s0d0d2"),
        )
        assertEquals(Lightup.Params(5, 5, 20, 0, 2), Lightup.decodeParams("5x5b20s0r"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 1), Lightup.decodeParams("5x5b20s0rd1"))
        assertEquals(Lightup.Params(18, 10, 20, 3, 0), Lightup.decodeParams("18x10"))
        assertEquals(
            Lightup.Params(7, 7, 20, 3, 0),
            Lightup.decodeParams("7x7b20s3d0junk"),
        )
        assertEquals(
            Lightup.Params(7, 7, 20, 3, 0),
            Lightup.decodeParams("7x7 "),
            "trailing space is ignored after the last marker",
        )
        // `b` with no digits is `atoi("") == 0`, which the C range test
        // rejects.
        assertEquals(0, (Lightup.decodeParams("5x5b") as Lightup.Params).blackpc)
    }

    @Test
    fun `validation follows the C order and the two guards`() {
        assertEquals(
            "Width and height must be at least 2",
            Lightup.validateParams(Lightup.Params(1, 5, 20, 3, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Lightup.validateParams(Lightup.Params(65536, 65536, 20, 3, 0), true),
        )
        assertEquals(
            "Percentage of black squares must be between 5% and 100%",
            Lightup.validateParams(Lightup.Params(7, 7, 4, 3, 0), true),
        )
        assertEquals(
            "4-fold symmetry is only available with square grids",
            Lightup.validateParams(Lightup.Params(9, 7, 20, 4, 0), true),
        )
        assertEquals(
            "Width or height must be at least 3 for 4-way symmetry",
            Lightup.validateParams(Lightup.Params(2, 2, 20, 3, 0), true),
        )
        assertEquals(
            "Unknown symmetry type",
            Lightup.validateParams(Lightup.Params(7, 7, 20, 5, 0), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Lightup.validateParams(Lightup.Params(7, 7, 20, 3, 3), true),
        )

        // C accepts both of these; the port rejects them (D-006).
        assertEquals(
            "Width times height is too large for this build",
            Lightup.validateParams(Lightup.Params(46340, 46340, 20, 3, 0), true),
        )
        assertEquals(
            "Width times height is too large for this build",
            Lightup.validateParams(Lightup.Params(46340, 46340, 20, 3, 0), false),
        )
        assertEquals(
            "A board of 100% black squares cannot be generated at this difficulty",
            Lightup.validateParams(Lightup.Params(7, 7, 100, 3, 1), true),
        )

        // The boundary is exact and the normal presets are accepted.
        assertNull(Lightup.validateParams(Lightup.Params(1024, 1024, 20, 0, 0), true))
        assertNotNull(Lightup.validateParams(Lightup.Params(1024, 1025, 20, 0, 0), true))
        assertNull(Lightup.validateParams(Lightup.Params(9, 7, 60, 3, 0), true))
        for (preset in Lightup.presets()) {
            assertNull(
                Lightup.validateParams(Lightup.decodeParams(preset.params), true),
                preset.params,
            )
        }
    }

    @Test
    fun `newDesc refuses the two non-terminating parameter sets`() {
        val huge = Lightup.Params(2000, 2000, 20, 0, 0)
        val hugeError = assertFailsWith<IllegalArgumentException> {
            Lightup.newDesc(huge, RandomState.fromSeed("1"))
        }
        assertEquals(
            "Width times height is too large for this build",
            hugeError.message,
        )

        val black = Lightup.Params(7, 7, 100, 3, 1)
        val blackError = assertFailsWith<IllegalArgumentException> {
            Lightup.newDesc(black, RandomState.fromSeed("1"))
        }
        assertEquals(
            "A board of 100% black squares cannot be generated at this difficulty",
            blackError.message,
        )

        // Difficulty 0 is still valid and returns the all-black board.
        val allBlack = Lightup.newDesc(
            Lightup.Params(7, 7, 100, 3, 0),
            RandomState.fromSeed("1"),
        )
        assertEquals("B".repeat(49), allBlack.text)
        assertNull(allBlack.auxInfo)
    }

    @Test
    fun `validateDesc counts cells and characters`() {
        val p = Lightup.decodeParams("3x3b20s0d0")
        assertNull(Lightup.validateDesc(p, "i"))
        assertEquals(
            "Game description shorter than expected",
            Lightup.validateDesc(p, "h"),
        )
        assertEquals(
            "Invalid character in game description",
            Lightup.validateDesc(p, "S"),
        )
        assertEquals(
            "Invalid character in game description",
            Lightup.validateDesc(p, "+"),
        )
        assertEquals(
            "Game description longer than expected",
            Lightup.validateDesc(p, "j"),
        )
        assertEquals(
            "Game description longer than expected",
            Lightup.validateDesc(p, "ii"),
        )
        assertNull(Lightup.validateDesc(p, "BBBBBBBBB"))
        assertNull(Lightup.validateDesc(p, "444444444"))
        // A run may cross a row boundary.
        assertNull(Lightup.validateDesc(Lightup.decodeParams("4x4b20s0d0"), "p"))
    }

    // ------------------------------------------------------------------
    // State and moves
    // ------------------------------------------------------------------

    @Test
    fun `copy is value equal and owns its arrays`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val state = midend.state as Lightup.State
        val copy = state.copy() as Lightup.State

        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())
        assertFalse(state.lights === copy.lights, "lights are copied")
        assertFalse(state.flags === copy.flags, "flags are copied")
        assertSame(state.shared, copy.shared, "the shared clues are immutable")

        val index = 0
        copy.lights[index]++
        copy.flags[index] = copy.flags[index] xor 0x100
        assertNotEquals(state, copy)
        assertEquals(0, state.lights[index])
    }

    @Test
    fun `executeMove copies the state and keeps the original`() {
        val midend = Midend.newGame(Lightup, "7x7b20s0d0", "1")
        val state = midend.state as Lightup.State
        val before = state.flags.copyOf()
        val next = Lightup.executeMove(state, "L1,1") as Lightup.State

        assertTrue(state.flags.contentEquals(before))
        assertTrue(state.flags[1 * state.width + 1] and Lightup.F_LIGHT == 0)
        assertTrue(next.flags[next.width + 1] and Lightup.F_LIGHT != 0)
    }

    @Test
    fun `a hidden cursor select is a no-effect but makes the cursor visible`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val ui = midend.ui as Lightup.Ui
        assertFalse(ui.curVisible)

        val result = Lightup.interpretMove(midend.state, ui, 0, 0, Buttons.CURSOR_SELECT, 32)
        assertEquals(MoveResult.NoEffect, result)
        assertTrue(ui.curVisible)
    }

    @Test
    fun `a negative or out-of-grid click is no-effect and truncated the C way`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val state = midend.state
        val ui = midend.ui as Lightup.Ui

        // FROMCOORD truncates toward zero; every out-of-grid point still
        // gives a cell outside the board.
        assertEquals(
            MoveResult.NoEffect,
            Lightup.interpretMove(state, ui, -100, 0, Buttons.LEFT_BUTTON, 32),
        )
        assertFalse(ui.curVisible)

        ui.curVisible = true
        assertEquals(
            MoveResult.UiUpdate,
            Lightup.interpretMove(state, ui, 1_000_000, 0, Buttons.LEFT_BUTTON, 32),
        )
        assertFalse(ui.curVisible, "a click hides the cursor")
    }

    @Test
    fun `currentKeyLabel never reads outside the grid`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val state = midend.state
        val ui = midend.ui as Lightup.Ui
        ui.curVisible = true

        for ((x, y) in listOf(-5 to 0, 0 to -5, 7 to 0, 0 to 7, 999 to 999)) {
            ui.curX = x
            ui.curY = y
            assertEquals("", Lightup.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
            assertEquals("", Lightup.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        }

        ui.curX = 0
        ui.curY = 0
        assertEquals("", Lightup.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Lightup.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A hidden cursor has no label even on a white cell.
        ui.curX = 1
        ui.curVisible = false
        assertEquals("", Lightup.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `completion is sticky and an S move is not an honest win`() {
        val start = Midend.fromGameId(Lightup, "3x3:i").state
        var solved: Game.State = start
        for (move in listOf("L2,0", "L1,1", "L0,2")) {
            solved = assertNotNull(Lightup.executeMove(solved, move))
        }
        assertEquals(1, Lightup.status(solved))
        val completed = solved as Lightup.State
        assertTrue(completed.completed)

        // Removing a light after the win keeps `completed`.
        val after = assertNotNull(Lightup.executeMove(completed, "I2,0"))
        assertEquals(1, Lightup.status(after))
        assertTrue((after as Lightup.State).completed)

        // `S` sets `used_solve`, and `changed_state` then reports no
        // honest completion.
        val cheated = assertNotNull(
            Lightup.executeMove(start, "S;L2,0;L1,1;L0,2"),
        ) as Lightup.State
        assertTrue(cheated.completed)
        assertTrue(cheated.usedSolve)
        val ui = Lightup.newUi(cheated) as Lightup.Ui
        assertFalse(Lightup.changedState(ui, start, cheated))
        assertFalse(ui.curVisible)

        // A real completion hides the cursor and reports true.
        val honestUi = Lightup.newUi(completed) as Lightup.Ui
        honestUi.curVisible = true
        assertTrue(Lightup.changedState(honestUi, start, completed))
        assertFalse(honestUi.curVisible)
        assertFalse(Lightup.changedState(honestUi, completed, completed))
    }

    @Test
    fun `the solver keeps the initial board and falls back to it`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val state = midend.state as Lightup.State
        val initialLights = state.shared.initialLights.copyOf()
        val initialFlags = state.shared.initialFlags.copyOf()

        // A wrong light at (1,0) is not in the solution, so the current
        // board fails and the answer turns it off.
        val wrong = Lightup.executeMove(state, "L1,0") as Lightup.State
        val answer = assertNotNull(Lightup.solve(wrong))
        assertTrue(answer.startsWith("S;"))
        assertTrue(answer.contains(";L1,0"), "the wrong light is removed")

        // The snapshot the fallback reads is still the decoded board.
        assertTrue(initialLights.contentEquals(state.shared.initialLights))
        assertTrue(initialFlags.contentEquals(state.shared.initialFlags))
        // The current state was not modified by the solve.
        assertTrue((wrong.flags[1] and Lightup.F_LIGHT) != 0)
    }

    @Test
    fun `an insoluble board returns null`() {
        val midend = Midend.fromGameId(Lightup, "3x3:4h")
        assertNull(Lightup.solve(midend.state))
    }

    // ------------------------------------------------------------------
    // Drawing cache and palette
    // ------------------------------------------------------------------

    @Test
    fun `the cache redraws once, then only changed tiles`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val sink = OpSink()

        midend.recordRedraw(sink)
        val first = sink.ops.toList()
        assertEquals("start_draw", first.first())
        assertEquals("end_draw", first.last())
        assertTrue(first.contains("poly -1 1 4 15 15 15 240 240 240 240 15"), "the frame")
        assertEquals(1, first.count { it == "rect 0 0 256 256 0" }, "the clear")
        assertEquals(
            49,
            first.count { it.matches(Regex("rect -?\\d+ -?\\d+ 32 32 \\d+")) },
            "every tile is drawn",
        )

        // A second draw of the same drawstate emits no operation.
        sink.ops.clear()
        midend.recordRedraw(sink)
        assertEquals(listOf("start_draw", "end_draw"), sink.ops)

        // A light changes its cell and every cell it newly lights.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 16, Buttons.LEFT_BUTTON, 32),
        )
        sink.ops.clear()
        midend.recordRedraw(sink)
        assertEquals("start_draw", sink.ops.first())
        assertEquals("end_draw", sink.ops.last())
        assertTrue(sink.ops.contains("rect 48 16 32 32 4"), "the light cell")
        assertTrue(sink.ops.contains("rect 48 48 32 32 4"), "the cell above is lit")
        assertTrue(
            sink.ops.count { it.matches(Regex("rect -?\\d+ -?\\d+ 32 32 \\d+")) } < 49,
            "not a full redraw",
        )
        assertFalse(sink.ops.any { it.startsWith("poly -1 1 4 15 ") }, "no frame in a warm draw")
    }

    @Test
    fun `the preference is not in the cached flags and a forced redraw applies it`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val sink = OpSink()
        // A light at (1,0) and an impossible mark on the lit cell (1,1).
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 16, Buttons.LEFT_BUTTON, 32),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 48, Buttons.RIGHT_BUTTON, 32),
        )
        midend.recordRedraw(sink)
        assertTrue(sink.ops.contains("rect 60 60 8 8 2"), "the blob with the default preference")

        // A preference change alone does not dirty a tile.
        (midend.ui as Lightup.Ui).drawBlobsWhenLit = false
        sink.ops.clear()
        midend.recordRedraw(sink)
        assertEquals(listOf("start_draw", "end_draw"), sink.ops)

        // A forced redraw creates a new drawstate and draws every tile
        // with the new value.
        midend.forceRedraw()
        sink.ops.clear()
        midend.recordRedraw(sink)
        assertTrue(sink.ops.any { it.startsWith("poly -1 1 4 15 ") }, "the frame again")
        assertEquals(
            49,
            sink.ops.count { it.matches(Regex("rect -?\\d+ -?\\d+ 32 32 \\d+")) },
            "every tile again",
        )
        assertFalse(sink.ops.contains("rect 60 60 8 8 2"), "the blob is gone")
    }

    @Test
    fun `colours derive the grid and cursor from the background`() {
        val background = floatArrayOf(0.3f, 0.6f, 0.9f)
        val colours = Lightup.colours(background)
        assertEquals(7 * 3, colours.size)
        for (i in 0..2) {
            assertTrue(abs(colours[0 * 3 + i] - background[i]) < 1e-6f)
            assertTrue(abs(colours[1 * 3 + i] - background[i] / 1.5f) < 1e-6f)
            assertTrue(abs(colours[6 * 3 + i] - background[i] / 2.0f) < 1e-6f)
            assertEquals(0.0f, colours[2 * 3 + i])
            assertEquals(1.0f, colours[3 * 3 + i])
        }
        assertEquals(1.0f, colours[4 * 3])
        assertEquals(1.0f, colours[4 * 3 + 1])
        assertEquals(0.0f, colours[4 * 3 + 2])
        assertEquals(1.0f, colours[5 * 3])
        assertEquals(0.25f, colours[5 * 3 + 1])
        assertEquals(0.25f, colours[5 * 3 + 2])
    }

    private class OpSink : DrawSink {
        val ops = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state"
        }

        override fun startDraw() {
            ops += "start_draw"
        }

        override fun endDraw() {
            ops += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect $x $y $width $height $colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
            ops += "circle $cx $cy $radius $fillColour $outlineColour"
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly $fillColour $outlineColour 4 " + points.joinToString(" ")
        }

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {
            ops += "text $x $y $font $size $align $colour \"$text\""
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update $x $y $width $height"
        }

        override fun status(text: String) {
            ops += "status $text"
        }

        override fun blitterNew(width: Int, height: Int): Int = 1
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
