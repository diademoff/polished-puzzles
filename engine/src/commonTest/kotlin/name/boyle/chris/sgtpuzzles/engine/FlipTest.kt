package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges of the Flip port. */
class FlipTest {

    @Test
    fun `parameters decode, encode, and validate`() {
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("05x05"))
        // `atoi` accepts the sign but the digit cursor stops at it, so the
        // `x` is not seen and the height stays equal to the width.
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("+5x5"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams(" 5x5"))
        // An uppercase X is not the separator.
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5X5"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5x5 "))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5x5z"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5x5C"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.RANDOM), Flip.decodeParams("5x5r"))
        // Only the first shape character is read.
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5x5cr"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("5x5cc"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.RANDOM), Flip.decodeParams("5x5rc"))
        assertEquals(Flip.Params(5, 5, Flip.MatrixType.RANDOM), Flip.decodeParams("5x5rX"))
        assertEquals(Flip.Params(0, 0, Flip.MatrixType.CROSSES), Flip.decodeParams(""))
        assertEquals(Flip.Params(5, 0, Flip.MatrixType.CROSSES), Flip.decodeParams("5x"))
        assertEquals(Flip.Params(0, 5, Flip.MatrixType.CROSSES), Flip.decodeParams("x5"))
        assertEquals(Flip.Params(-5, -5, Flip.MatrixType.CROSSES), Flip.decodeParams("-5x5"))

        assertEquals("5x5c", Flip.encodeParams(Flip.defaultParams(), true))
        assertEquals("5x5", Flip.encodeParams(Flip.defaultParams(), false))
        assertEquals(
            "3x3r",
            Flip.encodeParams(Flip.Params(3, 3, Flip.MatrixType.RANDOM), true),
        )
        assertEquals(
            "3x3",
            Flip.encodeParams(Flip.Params(3, 3, Flip.MatrixType.RANDOM), false),
        )

        assertEquals(null, Flip.validateParams(Flip.Params(5, 5, Flip.MatrixType.CROSSES), true))
        assertEquals(
            "Width and height must both be greater than zero",
            Flip.validateParams(Flip.Params(0, 5, Flip.MatrixType.CROSSES), true),
        )
        assertEquals(
            "Width and height must both be greater than zero",
            Flip.validateParams(Flip.Params(5, 0, Flip.MatrixType.RANDOM), false),
        )
        assertEquals(
            "Width and height must both be greater than zero",
            Flip.validateParams(Flip.Params(-1, 5, Flip.MatrixType.CROSSES), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Flip.validateParams(Flip.Params(100000, 100000, Flip.MatrixType.CROSSES), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Flip.validateParams(Flip.Params(46341, 46341, Flip.MatrixType.CROSSES), true),
        )
        assertEquals(
            "Width times height is too large",
            Flip.validateParams(Flip.Params(46340, 46340, Flip.MatrixType.CROSSES), true),
        )
    }

    @Test
    fun `presets, identity, keys, and the short form asymmetry`() {
        assertEquals(
            listOf(
                "3x3 Crosses", "4x4 Crosses", "5x5 Crosses",
                "3x3 Random", "4x4 Random", "5x5 Random",
            ),
            Flip.presets().map { it.title },
        )
        assertEquals(
            listOf("3x3c", "4x4c", "5x5c", "3x3r", "4x4r", "5x5r"),
            Flip.presets().map { it.params },
        )
        assertEquals("Flip", Flip.name)
        assertEquals("flip", Flip.backendName)
        assertEquals(48, Flip.preferredTileSize)
        assertTrue(Flip.wantsStatusBar)
        assertTrue(Flip.canSolve)

        val keys = Flip.requestKeys(Flip.defaultParams())
        assertEquals(ArrowMode.ARROWS_LEFT, keys.arrowMode)
        assertTrue(keys.labels.isEmpty())

        // The game id is the short form, so it drops the shape. A seeded
        // Random game keeps Crosses in PARAMS and Random in CPARAMS.
        val midend = Midend.newGame(Flip, "3x3r", "12345")
        assertEquals("3x3:${midend.desc}", midend.id())
        assertEquals("3x3c", midend.serialise().value("PARAMS"))
        assertEquals("3x3r", midend.serialise().value("CPARAMS"))
        assertFalse(midend.id().startsWith("3x3r"))
    }

    @Test
    fun `the frozen descriptions are reproduced`() {
        // The golden file testdata/golden/flip/desc.json, seed 12345.
        val golden = mapOf(
            "" to "5x5:c400007100001c4000071000018400043100011c400047100011c400046100010c400047100011c4000471000118400043100011c400047100011c400046100010c000047000011c0000470000118,16db6f0",
            "3x3c" to "3x3:d074191345d1644c17058,c90",
            "3x3r" to "3x3:d0781b134cc12c4c070d8,f38",
            "4x4c" to "4x4:c800e400720031008c804e402720131008c804e402720131008c004e00270013,a41e",
            "4x4r" to "4x4:8c00e80071003300cc00c64006303100088c00e802710113008c00ce00270033,9f17",
            "5x5c" to "5x5:c400007100001c4000071000018400043100011c400047100011c400046100010c400047100011c4000471000118400043100011c400047100011c400046100010c000047000011c0000470000118,16db6f0",
            "5x5r" to "5x5:c600007080000cc000070800018c00062100029c400087000008c2000c2100018c000007200010c4000461000018c00043180001ca000e2000011c200042300018c000047000021c0000470000318,62a7668",
        )
        for ((params, id) in golden) {
            assertEquals(id, Midend.newGame(Flip, params, "12345").id(), "params '$params'")
        }

        // The grid retry: the first nine draws give an all-zero grid and
        // the second nine give this description.
        assertEquals(
            "3x3:d074191345d1644c17058,c00",
            Midend.newGame(Flip, "3x3c", "1061").id(),
        )

        // The Crosses matrix is fixed, so only the grid changes.
        assertEquals(
            "f5dafd0",
            Midend.newGame(Flip, "5x5c", "1").id().substringAfterLast(','),
        )
        assertEquals(
            "3d8c7f0",
            Midend.newGame(Flip, "5x5c", "abc").id().substringAfterLast(','),
        )
        assertEquals(
            "98fd670",
            Midend.newGame(Flip, "5x5c", "").id().substringAfterLast(','),
        )

        // The Random matrix is not the Crosses matrix.
        assertNotEquals(
            Midend.newGame(Flip, "3x3c", "12345").id().substringBefore(','),
            Midend.newGame(Flip, "3x3r", "12345").id().substringBefore(','),
        )
    }

    @Test
    fun `the generator consumes the exact number of draws`() {
        // The seed 12345 counts from the specification: one draw per
        // matrix pick, one per grid row, and retries included.
        assertEquals(33, countDraws("3x3r"))
        assertEquals(256, countDraws("4x4r"))
        assertEquals(585, countDraws("5x5r"))
        assertEquals(9, countDraws("3x3c"))
        assertEquals(16, countDraws("4x4c"))
        assertEquals(25, countDraws("5x5c"))
        // The 3x3c seed 1061 grid retry: two attempts of nine draws.
        assertEquals(18, countDraws("3x3c", "1061"))
    }

    @Test
    fun `states, text, and status`() {
        val params = Flip.decodeParams("3x3c")
        val state = Flip.newState(params, "d074191345d1644c17058,c90") as Flip.State as Flip.State
        assertEquals(9, state.count)
        assertEquals(0, state.moves)
        assertFalse(state.completed)
        assertFalse(state.cheated)
        assertFalse(state.hintsActive)
        assertEquals(
            "+---+---+---+\n" +
                "|   |   |   |\n" +
                "| ##|###|.. |\n" +
                "| # | # | . |\n" +
                "+---+---+---+\n" +
                "| . | # | . |\n" +
                "| ..|###|.. |\n" +
                "| . | # | . |\n" +
                "+---+---+---+\n" +
                "| . | # | . |\n" +
                "| ..|###|.. |\n" +
                "|   |   |   |\n" +
                "+---+---+---+\n",
            Flip.textFormat(state),
        )
        assertEquals("Moves: 0", Flip.statusText(state, Flip.newUi(state)))
        assertEquals(0, Flip.status(state))

        val once = Flip.executeMove(state, "M0,1") as Flip.State
        assertEquals(1, once.moves)
        assertEquals("Moves: 1", Flip.statusText(once, Flip.newUi(once)))

        val hinted = Flip.executeMove(state, "S000110011") as Flip.State
        assertTrue(hinted.cheated)
        assertTrue(hinted.hintsActive)
        assertEquals(0, Flip.status(hinted))
        assertEquals("Auto-solver used. Moves: 0", Flip.statusText(hinted, Flip.newUi(hinted)))

        // An all-zero grid description has no lit square in the text.
        val dark = Flip.newState(params, "000000000000000000000,000")
        assertFalse((dark as Flip.State).grid.any { it.toInt() and 1 != 0 })
        assertFalse(Flip.textFormat(dark).contains('#'))

        // The copy owns its grid but shares the immutable matrix.
        val copy = state.copy() as Flip.State
        assertTrue(copy.matrix === state.matrix)
        copy.grid[0] = (state.grid[0].toInt() xor 1).toByte()
        assertNotEquals(copy.grid[0], state.grid[0])

        // A completed state has a sticky flag and a frozen move count.
        var completed: Game.State = state
        for (move in listOf("M0,1", "M1,1", "M1,2", "M2,2")) {
            completed = Flip.executeMove(completed, move)!!
        }
        val done = completed as Flip.State
        assertEquals(4, done.moves)
        assertTrue(done.completed)
        assertEquals(1, Flip.status(done))
        assertEquals("COMPLETED! Moves: 4", Flip.statusText(done, Flip.newUi(done)))

        val after = Flip.executeMove(done, "M0,0") as Flip.State
        assertTrue(after.completed)
        assertEquals(4, after.moves)
        assertFalse(after.hintsActive)

        // A cheated completion uses the other pair of strings.
        var auto: Game.State = hinted
        for (move in listOf("M0,1", "M1,1", "M1,2", "M2,2")) {
            auto = Flip.executeMove(auto, move)!!
        }
        assertTrue((auto as Flip.State).completed)
        assertEquals(4, auto.moves)
        assertEquals("Auto-solved. Moves: 4", Flip.statusText(auto, Flip.newUi(auto)))
        assertFalse(Flip.changedState(Flip.newUi(auto), done, auto))
    }

    @Test
    fun `the move grammar matches sscanf and the board bounds`() {
        val state = Flip.newState(Flip.decodeParams("3x3c"), "d074191345d1644c17058,c90") as Flip.State
        val reference = Flip.executeMove(state, "M1,0") as Flip.State

        for (move in listOf(
            "M+1,0", "M01,0", "M1,0junk", "M1,0,0", "M 1,0", "M1, 0",
        )) {
            val actual = Flip.executeMove(state, move) as Flip.State
            assertEquals(reference.grid.toList(), actual.grid.toList(), "move '$move'")
            assertEquals(reference.moves, actual.moves, "move '$move'")
        }

        for (move in listOf(
            "M1", "M-1,0", "M3,0", "M0,3", "Mx,0", "M", "S", "s000000000",
            "x", "", "M1;0", "M1 ,0",
        )) {
            assertNull(Flip.executeMove(state, move), "move '$move' must be invalid")
        }

        // `S` needs exactly wh digits.
        assertNull(Flip.executeMove(state, "S" + "0".repeat(10)))
        val hints = Flip.executeMove(state, "S" + "0".repeat(9)) as Flip.State
        assertTrue(hints.cheated)
        assertTrue(hints.hintsActive)
        assertEquals(state.grid.toList(), hints.grid.toList())
        assertEquals(state.moves, hints.moves)

        // A click toggles exactly one matrix row and the clicked hint bit.
        val clicked = Flip.executeMove(state, "M2,2") as Flip.State
        val wh = 9
        val row = (2 * 3 + 2) * wh
        for (j in 0 until wh) {
            val expected = (state.grid[j].toInt() xor state.matrix[row + j].toInt() xor
                (if (j == 8) 2 else 0))
            assertEquals(expected, clicked.grid[j].toInt(), "cell $j")
        }
    }

    @Test
    fun `the solver returns a hint mask, not a completed board`() {
        val state = Flip.newState(Flip.decodeParams("3x3c"), "d074191345d1644c17058,c90") as Flip.State
        val text = Flip.textFormat(state)

        assertEquals("S000110011", Flip.solve(state))
        // Solving alone changes nothing.
        assertEquals(text, Flip.textFormat(state))
        assertEquals(0, Flip.status(state))
        assertEquals("Moves: 0", Flip.statusText(state, Flip.newUi(state)))

        val hinted = Flip.executeMove(state, "S000110011") as Flip.State
        assertTrue(hinted.cheated)
        assertTrue(hinted.hintsActive)
        assertEquals(0, Flip.status(hinted))
        assertEquals(text, Flip.textFormat(hinted))
        assertEquals("Auto-solver used. Moves: 0", Flip.statusText(hinted, Flip.newUi(hinted)))
        val hintedCells = (0 until 9).filter { hinted.grid[it].toInt() and 2 != 0 }
        assertEquals(listOf(3, 4, 7, 8), hintedCells)

        // The player finishes the game by clicking the hinted cells.
        var next: Game.State = hinted
        var sawCompletion = false
        for (move in listOf("M0,1", "M1,1", "M1,2", "M2,2")) {
            val previous = next
            next = Flip.executeMove(next, move)!!
            if (Flip.changedState(Flip.newUi(next), previous, next)) sawCompletion = true
        }
        assertEquals(1, Flip.status(next))
        assertEquals("Auto-solved. Moves: 4", Flip.statusText(next, Flip.newUi(next)))
        assertFalse(sawCompletion, "a cheated completion raises no event")

        // A hand-written insoluble position has no solution. The seam has
        // no error out-parameter, so C's message cannot be returned.
        val insoluble = Flip.newState(
            Flip.decodeParams("3x3c"),
            "000000000000000000000,008",
        )
        assertNull(Flip.solve(insoluble))

        // An honest completion does raise the event.
        var honest: Game.State = state
        var raised = false
        for (move in listOf("M0,1", "M1,1", "M1,2", "M2,2")) {
            val previous = honest
            honest = Flip.executeMove(honest, move)!!
            if (Flip.changedState(Flip.newUi(honest), previous, honest)) raised = true
        }
        assertTrue(raised)
    }

    @Test
    fun `the palette, the size, and the drawing order`() {
        val colours = Flip.colours()
        assertEquals(7 * 3, colours.size)
        assertEquals(
            listOf(
                800, 800, 800,
                267, 267, 267,
                1000, 1000, 1000,
                533, 533, 533,
                533, 533, 533,
                1000, 0, 0,
                800, 0, 0,
            ),
            colours.map { (it * 1000.0f).roundToInt() },
        )

        assertEquals(Size(288, 288), Flip.computeSize(Flip.defaultParams(), 48))
        // The odd tile gives `(w+1)T - 1`, because the border is `T/2`.
        assertEquals(Size(377, 377), Flip.computeSize(Flip.defaultParams(), 63))
        assertEquals(Size(284, 284), Flip.computeSize(Flip.decodeParams("4x4c"), 57))
        assertEquals(Size(192, 192), Flip.computeSize(Flip.decodeParams("3x3c"), 48))

        val state = Flip.newState(Flip.decodeParams("3x3c"), "d074191345d1644c17058,c90") as Flip.State
        val plain = RecordingSink()
        Flip.redraw(state, Flip.newUi(state), plain, 48)

        // Eight grid lines, then the diagram lines: 9 self entries are
        // rectangles and the other 24 matrix entries are four lines each.
        assertEquals(8 + 24 * 4, plain.lines.size)
        assertEquals(1 + 9, plain.updates.size)
        assertEquals(9, plain.clips.size)
        assertEquals(9, plain.unclips)
        assertTrue(plain.statuses.isEmpty(), "the midend writes the status line")
        assertEquals(listOf(0, 0, 192, 192), plain.updates.first())
        assertEquals(listOf(25, 25, 47, 47), plain.clips.first())
        // The cursor changes only the diagram colour and adds no op.
        val ui = Flip.newUi(state) as Flip.Ui
        Flip.setCursorVisible(ui, true)
        val cursor = RecordingSink()
        Flip.redraw(state, ui, cursor, 48)
        assertEquals(plain.lines.size, cursor.lines.size)
        assertEquals(0, plain.lines.count { it.colour == 6 })
        // Cell (0,0) has three matrix entries: its self rectangle plus
        // `4(k-1)` colour-6 lines.
        assertEquals(8, cursor.lines.count { it.colour == 6 })
        assertEquals(1, cursor.rects.count { it[4] == 6 })

        // A hint draws three nested rectangles of four lines per cell.
        val hinted = Flip.executeMove(state, "S000110011") as Flip.State
        val hintSink = RecordingSink()
        Flip.redraw(hinted, Flip.newUi(hinted), hintSink, 48)
        assertEquals(12 * 4, hintSink.lines.count { it.colour == 5 })

        // A zero matrix draws no diagram at all.
        val dark = Flip.newState(Flip.decodeParams("3x3c"), "000000000000000000000,001")
        val darkSink = RecordingSink()
        Flip.redraw(dark, Flip.newUi(dark), darkSink, 48)
        assertEquals(8, darkSink.lines.size)
        assertEquals(9, darkSink.rects.size)
    }

    @Test
    fun `clicks, drags, the cursor, and the move text`() {
        val params = Flip.decodeParams("3x3c")
        val state = Flip.newState(params, "d074191345d1644c17058,c90") as Flip.State
        val ui = Flip.newUi(state) as Flip.Ui
        val tile = 48

        // FROMCOORD at tile 48: cell c covers [24+48c, 72+48c).
        assertEquals(MoveResult.Move("M0,0"), Flip.interpretMove(state, ui, 24, 24, Buttons.LEFT_BUTTON, tile))
        assertEquals(MoveResult.Move("M2,0"), Flip.interpretMove(state, ui, 167, 48, Buttons.LEFT_BUTTON, tile))
        assertEquals(MoveResult.Move("M0,0"), Flip.interpretMove(state, ui, 24, 48, Buttons.LEFT_BUTTON, tile))
        for ((x, y) in listOf(
            0 to 0, 0 to 48, 168 to 48, 48 to 0, 48 to 168, 24 to 192,
        )) {
            assertEquals(
                MoveResult.UiUpdate,
                Flip.interpretMove(state, ui, x, y, Buttons.LEFT_BUTTON, tile),
                "border click at ($x,$y)",
            )
        }

        assertEquals(MoveResult.Unused, Flip.interpretMove(state, ui, 24, 24, Buttons.RIGHT_BUTTON, tile))
        assertEquals(MoveResult.Unused, Flip.interpretMove(state, ui, 24, 24, Buttons.LEFT_DRAG, tile))

        // Cursor keys clamp and reveal the cursor.
        assertFalse(ui.cdraw)
        assertEquals(MoveResult.UiUpdate, Flip.interpretMove(state, ui, 0, 0, Buttons.CURSOR_UP, tile))
        assertTrue(ui.cdraw)
        assertEquals(0, ui.cx)
        assertEquals(0, ui.cy)
        assertEquals(MoveResult.NoEffect, Flip.interpretMove(state, ui, 0, 0, Buttons.CURSOR_UP, tile))
        assertEquals(MoveResult.UiUpdate, Flip.interpretMove(state, ui, 0, 0, Buttons.CURSOR_RIGHT, tile))
        assertEquals(MoveResult.Move("M1,0"), Flip.interpretMove(state, ui, 0, 0, Buttons.CURSOR_SELECT, tile))

        // A select with a hidden cursor clicks (0,0).
        val hidden = Flip.newUi(state) as Flip.Ui
        assertEquals(MoveResult.Move("M0,0"), Flip.interpretMove(state, hidden, 0, 0, Buttons.CURSOR_SELECT2, tile))
        assertTrue(hidden.cdraw)

        assertEquals(Game.CursorRect(24, 24, 48, 48), Flip.cursorLocation(hidden, state, tile))

        // The C midend strips SHFT from a non-cursor key and rejects CTRL.
        assertEquals(
            MoveResult.Move("M0,0"),
            Flip.interpretMove(state, hidden, 0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(
            MoveResult.Unused,
            Flip.interpretMove(state, hidden, 0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_SELECT, tile),
        )
        assertEquals(
            MoveResult.Unused,
            Flip.interpretMove(state, hidden, 24, 24, Buttons.MOD_CTRL or Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(
            MoveResult.Unused,
            Flip.interpretMove(state, hidden, 0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_UP, tile),
        )

        assertEquals("Flip", Flip.currentKeyLabel(hidden, state, Buttons.CURSOR_SELECT))
        assertEquals("Flip", Flip.currentKeyLabel(hidden, state, Buttons.CURSOR_SELECT2))
        assertEquals("", Flip.currentKeyLabel(hidden, state, Buttons.LEFT_BUTTON))
        assertEquals(
            "",
            Flip.currentKeyLabel(hidden, state, Buttons.MOD_SHFT or Buttons.CURSOR_SELECT),
        )

        // A hand-written empty matrix row is a no-effect click, but a
        // cursor select still reveals the cursor.
        val dark = Flip.newState(params, "000000000000000000000,001")
        val darkUi = Flip.newUi(dark) as Flip.Ui
        assertEquals(MoveResult.NoEffect, Flip.interpretMove(dark, darkUi, 24, 24, Buttons.LEFT_BUTTON, tile))
        assertEquals(MoveResult.NoEffect, Flip.interpretMove(dark, darkUi, 0, 0, Buttons.CURSOR_SELECT, tile))
        assertTrue(darkUi.cdraw)
    }

    @Test
    fun `clicks complete the game and undo and redo follow the history`() {
        val midend = Midend.newGame(Flip, "3x3c", "12345")
        val tile = 48
        val cells = listOf(0 to 1, 1 to 1, 1 to 2, 2 to 2)

        for ((x, y) in cells) {
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(x * tile + tile / 2, y * tile + tile / 2, Buttons.LEFT_BUTTON, tile),
            )
        }
        assertEquals(1, midend.status())
        assertEquals("COMPLETED! Moves: 4", midend.statusText())
        assertEquals(4, (midend.state as Flip.State).moves)
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
        assertEquals(listOf("M0,1", "M1,1", "M1,2", "M2,2"), midend.serialise().values("MOVE"))

        // Crossing the completion backwards is not a completion event.
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertEquals("Moves: 3", midend.statusText())
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertEquals("COMPLETED! Moves: 4", midend.statusText())

        // A click after completion changes the board but not the count.
        val before = midend.textFormat()
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 24, Buttons.LEFT_BUTTON, tile))
        assertEquals("COMPLETED! Moves: 4", midend.statusText())
        assertNotEquals(before, midend.textFormat())

        // A restart appends a RESTART record with the description.
        assertTrue(midend.restart())
        assertEquals(0, midend.status())
        assertEquals("Moves: 0", midend.statusText())
        assertEquals(midend.desc, midend.serialise().records.last().value)
        assertEquals("RESTART", midend.serialise().records.last().key)
        // At the first state a restart does nothing.
        assertFalse(Midend.newGame(Flip, "3x3c", "12345").restart())
    }

    private fun countDraws(params: String, seed: String = "12345"): Int {
        val p = Flip.decodeParams(params) as Flip.Params
        val random = RandomState.fromSeed(seed)
        var draws = 0
        val desc = FlipGeneration.newDesc(
            p.width,
            p.height,
            p.matrixType == Flip.MatrixType.RANDOM,
        ) { limit ->
            draws++
            random.upto(limit)
        }
        assertEquals(
            desc,
            Midend.newGame(Flip, params, seed).desc,
            "the counted generator must produce the game description",
        )
        return draws
    }

    private class RecordingSink : DrawSink {
        data class Line(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val colour: Int)

        val lines = ArrayList<Line>()
        val rects = ArrayList<List<Int>>()
        val updates = ArrayList<List<Int>>()
        val clips = ArrayList<List<Int>>()
        val statuses = ArrayList<String>()
        var unclips = 0

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            rects += listOf(x, y, width, height, colour)
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            lines += Line(x1, y1, x2, y2, colour)
        }

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}

        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            clips += listOf(x, y, width, height)
        }

        override fun unclip() {
            unclips++
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            updates += listOf(x, y, width, height)
        }

        override fun status(text: String) {
            statuses += text
        }
    }
}
