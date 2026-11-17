package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges of the Filling port. */
class FillingTest {

    @Test
    fun `parameters decode, encode, and validate`() {
        assertEquals(Filling.Params(13, 9), Filling.decodeParams("13x9"))
        assertEquals(Filling.Params(9, 7), Filling.decodeParams("9x7"))
        assertEquals(Filling.Params(17, 13), Filling.decodeParams("17x13"))

        // The height starts equal to the width.
        assertEquals(Filling.Params(13, 13), Filling.decodeParams("13"))
        assertEquals(Filling.Params(0, 5), Filling.decodeParams("0x5"))
        assertEquals(Filling.Params(13, 0), Filling.decodeParams("13x"))
        assertEquals(Filling.Params(13, -9), Filling.decodeParams("13x-9"))

        // Only a lower-case `x` is a separator, and the digit skip
        // starts at the beginning of the string, so a sign or a space
        // hides it.
        assertEquals(Filling.Params(13, 13), Filling.decodeParams("13X9"))
        assertEquals(Filling.Params(13, 13), Filling.decodeParams(" 13x9"))
        assertEquals(Filling.Params(13, 13), Filling.decodeParams("+13x9"))
        assertEquals(Filling.Params(13, 9), Filling.decodeParams("13x9junk"))

        // The height `atoi` skips white space and a sign.
        assertEquals(Filling.Params(13, 9), Filling.decodeParams("13x 9"))
        assertEquals(Filling.Params(13, 9), Filling.decodeParams("13x+9"))

        assertEquals("13x9", Filling.encodeParams(Filling.defaultParams(), true))
        assertEquals("13x9", Filling.encodeParams(Filling.defaultParams(), false))
        assertEquals("13x13", Filling.encodeParams(Filling.decodeParams("13"), true))

        assertEquals(
            listOf("9x7", "13x9", "17x13"),
            Filling.presets().map { it.title },
        )
        assertEquals(
            listOf("9x7", "13x9", "17x13"),
            Filling.presets().map { it.params },
        )
        assertEquals(Filling.Params(13, 9), Filling.defaultParams())
        assertEquals("Filling", Filling.name)
        assertEquals("filling", Filling.backendName)
        assertEquals(32, Filling.preferredTileSize)
        assertFalse(Filling.wantsStatusBar)
        assertTrue(Filling.canSolve)
        assertTrue(Filling.canFormatAsText)
    }

    @Test
    fun `validation messages are the C messages in the C order`() {
        assertNull(Filling.validateParams(Filling.Params(13, 9), true))
        assertEquals(
            "Width must be at least one",
            Filling.validateParams(Filling.Params(0, 5), true),
        )
        assertEquals(
            "Height must be at least one",
            Filling.validateParams(Filling.Params(5, 0), true),
        )
        assertEquals(
            "Width must be at least one",
            Filling.validateParams(Filling.Params(0, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Filling.validateParams(Filling.Params(46341, 46341), true),
        )
        // C accepts 46340 squared; the boundary must not move.
        assertNull(Filling.validateParams(Filling.Params(46340, 46340), true))
        // `full` is not read, so both forms give the same answer.
        assertEquals(
            "Width must be at least one",
            Filling.validateParams(Filling.Params(0, 5), false),
        )
    }

    @Test
    fun `validateDesc accepts the wide clue alphabet and the run arithmetic`() {
        val line = Filling.decodeParams("9x7")
        val thirteen = Filling.decodeParams("13x9")
        val wide = Filling.decodeParams("17x13")

        // `m` is `'0' + max(max(w, h), 3)`.
        assertNull(Filling.validateDesc(line, "0".repeat(63)))
        assertEquals(
            "Invalid character ':' in game description",
            Filling.validateDesc(line, ":".repeat(63)),
        )
        assertNull(Filling.validateDesc(thirteen, "=".repeat(117)))
        assertEquals(
            "Invalid character '>' in game description",
            Filling.validateDesc(thirteen, ">".repeat(117)),
        )
        assertNull(Filling.validateDesc(wide, "A".repeat(221)))
        assertEquals(
            "Invalid character 'B' in game description",
            Filling.validateDesc(wide, "B".repeat(221)),
        )

        // Lower-case letters are runs, checked before the clue test.
        assertNull(Filling.validateDesc(line, "zzk"))
        assertNull(Filling.validateDesc(Filling.decodeParams("1x26"), "z"))
        assertEquals(
            "Not enough data to fill grid",
            Filling.validateDesc(Filling.decodeParams("1x27"), "z"),
        )
        assertNull(Filling.validateDesc(Filling.decodeParams("1x27"), "za"))
        assertNull(Filling.validateDesc(Filling.decodeParams("1x52"), "zz"))
        assertEquals(
            "Not enough data to fill grid",
            Filling.validateDesc(Filling.decodeParams("1x52"), "z"),
        )
        assertNull(Filling.validateDesc(Filling.decodeParams("1x53"), "zza"))
        assertEquals(
            "Too much data to fit in grid",
            Filling.validateDesc(Filling.decodeParams("1x53"), "zzz"),
        )

        assertEquals(
            "Not enough data to fill grid",
            Filling.validateDesc(line, ""),
        )
        assertEquals(
            "Too much data to fit in grid",
            Filling.validateDesc(Filling.decodeParams("1x1"), "aa"),
        )
        assertEquals(
            "Invalid character 'X' in game description",
            Filling.validateDesc(line, "X"),
        )

        // A `0` is an accepted one-cell clue. It decodes to an empty
        // cell, so the generated alphabet never contains it.
        assertNull(Filling.validateDesc(Filling.decodeParams("1x1"), "0"))
        assertNull(Filling.validateDesc(Filling.decodeParams("10x1"), ":i"))
    }

    @Test
    fun `new state decodes clues and does not complete`() {
        val state = Filling.newState(
            Filling.decodeParams("9x7"),
            "a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55",
        ) as Filling.State
        assertEquals(63, state.board.size)
        assertEquals(63, state.shared.clues.size)
        assertTrue(state.board.contentEquals(state.shared.clues))
        assertFalse(state.completed)
        assertFalse(state.cheated)
        assertEquals(0, Filling.status(state))
        assertEquals("", Filling.statusText(state, Filling.newUi(state)))
        assertEquals(9, state.width)
        assertEquals(7, state.height)

        // A clue above 9 decodes with the C subtraction.
        val wide = Filling.newState(Filling.decodeParams("10x1"), ":i") as Filling.State
        assertEquals(10, wide.board[0])
        assertTrue(Filling.textFormat(wide).lines()[1].contains(':'))
        val seventeen = Filling.newState(Filling.decodeParams("17x13"), "?zzzzzzzzl")
        assertEquals('?', Filling.textFormat(seventeen).lines()[1][2])

        // The 9x7 text is the oracle text, byte for byte.
        assertEquals(
            "+---+---+---+---+---+---+---+---+---+\n" +
                "|   | 8 |   | 8 |   | 4 | 6 | 6 |   |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| 2 |   |   | 8 |   |   | 6 | 6 |   |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| 2 |   | 7 | 7 | 7 | 7 | 6 | 6 | 5 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "|   |   |   |   | 7 |   |   | 9 |   |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "|   |   |   |   | 7 | 9 |   |   |   |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| 2 | 9 | 9 |   |   | 9 |   |   |   |\n" +
                "+---+---+---+---+---+---+---+---+---+\n" +
                "| 6 |   |   |   | 5 |   |   | 5 | 5 |\n" +
                "+---+---+---+---+---+---+---+---+---+\n",
            Filling.textFormat(state),
        )

        // A 1x1 board is the smallest form.
        assertEquals(
            "+---+\n|   |\n+---+\n",
            Filling.textFormat(Filling.newState(Filling.decodeParams("1x1"), "a")),
        )
    }

    @Test
    fun `the move grammar follows strtol with base zero`() {
        val state = Filling.newState(Filling.decodeParams("9x7"), DESC_9X7)
        val sz = 63

        assertEquals(5, (Filling.executeMove(state, "3_5") as Filling.State).board[3])
        assertEquals(5, (Filling.executeMove(state, "3_05") as Filling.State).board[3])
        assertEquals(8, (Filling.executeMove(state, "3_010") as Filling.State).board[3])
        assertEquals(9, (Filling.executeMove(state, "3_0x9") as Filling.State).board[3])

        val two = Filling.executeMove(state, "1,2_0x5") as Filling.State
        assertEquals(5, two.board[1])
        assertEquals(5, two.board[2])

        // C assigns the parsed `long` to an `int`: the low 32 bits
        // decide, so 2^32 becomes 0. This matches the oracle.
        assertEquals(0, (Filling.executeMove(state, "3_4294967296") as Filling.State).board[3])
        assertEquals(5, (Filling.executeMove(state, "4294967296_5") as Filling.State).board[0])

        assertNull(Filling.executeMove(state, "3_10"))
        assertNull(Filling.executeMove(state, "63_9"))
        assertNull(Filling.executeMove(state, "1,2_5x"))
        assertNull(Filling.executeMove(state, "1,2,_5"))
        assertNull(Filling.executeMove(state, "_5"))
        assertNull(Filling.executeMove(state, ""))
        assertNull(Filling.executeMove(state, "0"))
        assertNull(Filling.executeMove(state, "3_08"))
        assertNull(Filling.executeMove(state, "3_99999999999999999999"))
        assertNotNull(Filling.executeMove(state, "62_9"))
        assertNotNull(Filling.executeMove(state, "0_ 5"))
        assertNotNull(Filling.executeMove(state, "0_+5"))

        // Duplicate indices are harmless.
        val duplicate = Filling.executeMove(state, "0,0_5") as Filling.State
        assertEquals(5, duplicate.board[0])

        // An `s` move needs exactly `sz + 1` characters. There is no
        // range check, so `A` is 17 and a value can be empty.
        assertNull(Filling.executeMove(state, "s"))
        assertNull(Filling.executeMove(state, "s" + "0".repeat(sz - 1)))
        assertNull(Filling.executeMove(state, "s" + "0".repeat(sz + 1)))
        val solved = Filling.executeMove(state, "s" + "A".repeat(sz)) as Filling.State
        assertTrue(solved.cheated)
        assertTrue(solved.board.all { it == 17 })
        val cleared = Filling.executeMove(state, "s" + "0".repeat(sz)) as Filling.State
        assertTrue(cleared.board.all { it == 0 })
    }

    @Test
    fun `completion is sticky and only an honest win is reported`() {
        val start = Filling.newState(Filling.decodeParams("1x2"), "2a")
        val ui = Filling.newUi(start)

        val completed = Filling.executeMove(start, "1_2") as Filling.State
        assertTrue(completed.completed)
        assertEquals(1, Filling.status(completed))
        assertTrue(Filling.changedState(ui, start, completed))

        // The flag is sticky: a later move keeps the status, but the old
        // state was already complete, so no event is reported.
        val later = Filling.executeMove(completed, "1_1") as Filling.State
        assertTrue(later.completed)
        assertFalse(Filling.changedState(ui, completed, later))

        // A cheated completion reports nothing.
        val cheated = Filling.executeMove(start, "s22") as Filling.State
        assertTrue(cheated.cheated)
        assertTrue(cheated.completed)
        assertFalse(Filling.changedState(ui, start, cheated))

        // An incomplete new state reports nothing, and the first state
        // has no old state.
        assertFalse(Filling.changedState(ui, null, completed))
        assertFalse(Filling.changedState(ui, completed, start))
    }

    @Test
    fun `undo and redo report the completion event again`() {
        val midend = Midend.fromGameId(Filling, "1x2:2a")
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())

        val tile = Filling.preferredTileSize
        // Cell (0,1) is the empty cell of the 1x2 board.
        val x = 32
        val y = 64
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, tile))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, '2'.code, tile))
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent(), "undo leaves the completed state")

        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent(), "redo enters the completed state")
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `a click selects and a digit fills the selection`() {
        val midend = Midend.fromGameId(Filling, "9x7:$DESC_9X7")
        val ui = midend.ui as Filling.Ui
        val tile = Filling.preferredTileSize
        val sz = (midend.state as Filling.State).board.size

        // A mouse down clears the selection, then selects cell (0,0).
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 32, Buttons.LEFT_BUTTON, tile))
        assertNotNull(ui.sel)
        assertTrue(ui.sel!![0])
        assertEquals(0, ui.curX)
        assertEquals(0, ui.curY)
        assertFalse(ui.curVisible)

        // The digit turns the selection into one move.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 32, '5'.code, tile))
        assertEquals("0_5", midend.serialise().values("MOVE").single())
        assertNull(ui.sel, "a move clears the selection")

        // A click on a clue cell allocates an all-false selection, so a
        // digit is a UI update, not a no-effect key.
        val fresh = Midend.fromGameId(Filling, "9x7:$DESC_9X7")
        val freshUi = fresh.ui as Filling.Ui
        val clueIndex = (fresh.state as Filling.State).shared.clues.indexOfFirst { it != 0 }
        val clueX = (clueIndex % 9) * tile + tile
        val clueY = (clueIndex / 9) * tile + tile
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(clueX, clueY, Buttons.LEFT_BUTTON, tile))
        assertNotNull(freshUi.sel)
        assertTrue(freshUi.sel!!.none { it })
        assertEquals(
            KeyOutcome.SomeEffect,
            fresh.processKey(clueX, clueY, '5'.code, tile),
        )
        assertNull(freshUi.sel, "the empty selection is cleared")
        assertEquals(1, fresh.stateCount)

        // A digit with no selection and a hidden cursor is a no-effect
        // key at the game level.
        val direct = Midend.fromGameId(Filling, "9x7:$DESC_9X7")
        val directState = direct.state
        val directUi = direct.ui
        assertNull((directUi as Filling.Ui).sel)
        assertEquals(
            MoveResult.NoEffect,
            Filling.interpretMove(directState, directUi, 0, 0, '5'.code, tile),
        )

        // A digit that changes nothing but has a selection is a UI
        // update, because the selection is cleared.
        (directUi as Filling.Ui).sel = BooleanArray(sz)
        assertEquals(
            MoveResult.UiUpdate,
            Filling.interpretMove(directState, directUi, 0, 0, '0'.code, tile),
        )
        assertNull(directUi.sel)
    }

    @Test
    fun `cursor select toggles, dragging, and escape work`() {
        val midend = Midend.fromGameId(Filling, "2x2:22aa")
        val ui = midend.ui as Filling.Ui
        val tile = Filling.preferredTileSize

        // The cursor starts hidden. The first select shows it and does
        // nothing else.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertTrue(ui.curVisible)
        assertNull(ui.sel)

        // The second press starts dragging. Cell (0,0) is a clue, so
        // the selection is allocated but stays empty.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertTrue(ui.keyDragging)
        assertNotNull(ui.sel)
        assertTrue(ui.sel!!.none { it })

        // A cursor move while dragging selects the new cell. Cells (0,1)
        // and (1,1) are empty.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_DOWN, tile))
        assertTrue(ui.sel!![2])
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile))
        assertTrue(ui.sel!![3])
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, '3'.code, tile))
        assertEquals("2,3_3", midend.serialise().values("MOVE").single())
        assertNull(ui.sel, "a move clears the selection")
        assertFalse(ui.keyDragging)

        // Select2 toggles one cell and frees an empty selection.
        val toggling = Midend.fromGameId(Filling, "2x2:22aa")
        val toggleUi = toggling.ui as Filling.Ui
        toggling.processKey(0, 0, Buttons.CURSOR_LEFT, tile)
        toggling.processKey(0, 0, Buttons.CURSOR_DOWN, tile)
        assertEquals(
            KeyOutcome.SomeEffect,
            toggling.processKey(0, 0, Buttons.CURSOR_SELECT2, tile),
        )
        assertTrue(toggleUi.sel!![2])
        toggling.processKey(0, 0, Buttons.CURSOR_SELECT2, tile)
        assertNull(toggleUi.sel, "the empty selection is freed")

        // Esc clears a selection.
        toggling.processKey(0, 0, Buttons.CURSOR_SELECT2, tile)
        assertNotNull(toggleUi.sel)
        assertEquals(KeyOutcome.SomeEffect, toggling.processKey(0, 0, 27, tile))
        assertNull(toggleUi.sel)
        assertFalse(toggleUi.keyDragging)
    }

    @Test
    fun `an out-of-grid cursor addresses no cell`() {
        val midend = Midend.fromGameId(Filling, "2x2:22aa")
        val ui = midend.ui as Filling.Ui
        val tile = Filling.preferredTileSize
        val state = midend.state

        // A click on the border leaves the Android cursor at (-1,-1).
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.LEFT_BUTTON, tile))
        assertEquals(-1, ui.curX)
        assertEquals(-1, ui.curY)
        assertNull(ui.sel)

        // Showing the cursor and starting a drag must not read the board
        // out of bounds, and must select nothing.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertNotNull(ui.sel)
        assertTrue(ui.sel!!.none { it })

        // The same for select2, which frees the empty selection.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT2, tile))
        assertNull(ui.sel)

        // The key label offers nothing for a cursor that is not on a
        // cell.
        ui.curVisible = true
        assertEquals(
            "",
            Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2),
        )
    }

    @Test
    fun `current key labels cover the four strings and the null selection`() {
        val state = Filling.newState(Filling.decodeParams("2x2"), "22aa") as Filling.State
        val ui = Filling.newUi(state) as Filling.Ui
        val hidden = Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT)
        assertEquals("", hidden)

        // Cell (0,1) is empty; cell (0,0) is a clue.
        ui.curVisible = true
        ui.curX = 0
        ui.curY = 1
        assertEquals("Multiselect", Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.keyDragging = true
        assertEquals("Stop", Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.keyDragging = false

        // C reads `ui->sel` with no NULL test here. The port treats a
        // null selection as "not selected" and returns Select.
        assertNull(ui.sel)
        assertEquals("Select", Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        ui.sel = BooleanArray(4)
        ui.sel!![2] = true
        assertEquals("Deselect", Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        // A clue cell has no label, and so has an unused key.
        ui.curY = 0
        assertEquals("", Filling.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        ui.curY = 1
        assertEquals("", Filling.currentKeyLabel(ui, state, '5'.code))
    }

    @Test
    fun `requested keys, size, and the palette match C`() {
        val keys = Filling.requestKeys(Filling.decodeParams("13x9"))
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, keys.arrowMode)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "Clear"),
            keys.labels.map { it.name },
        )
        assertEquals(
            listOf(49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 8),
            keys.labels.map { it.button },
        )
        assertTrue(keys.labels.none { it.needsArrows })

        assertEquals(Size(448, 320), Filling.computeSize(Filling.decodeParams("13x9"), 32))
        assertEquals(Size(320, 256), Filling.computeSize(Filling.decodeParams("9x7"), 32))
        assertEquals(Size(576, 448), Filling.computeSize(Filling.decodeParams("17x13"), 32))
        assertEquals(Size(406, 290), Filling.computeSize(Filling.decodeParams("13x9"), 29))
        assertEquals(Size(64, 96), Filling.computeSize(Filling.decodeParams("1x2"), 32))

        val colours = Filling.colours()
        assertEquals(7 * 3, colours.size)
        assertEquals(
            listOf(
                800, 800, 800,
                0, 0, 0,
                560, 560, 560,
                720, 720, 720,
                1000, 680, 680,
                0, 480, 0,
                400, 400, 400,
            ),
            colours.map { (it * 1000.0f).roundToInt() },
        )
    }

    @Test
    fun `the drawing cache draws once and then only changed cells`() {
        val state = Filling.newState(Filling.decodeParams("2x2"), "22aa") as Filling.State
        val ui = Filling.newUi(state) as Filling.Ui
        val sink = RecordingSink()
        val ds = Filling.newDrawState(state, 32, sink)
        assertFalse((ds as Filling.DrawState).started)

        val first = RecordingSink()
        Filling.redraw(state, ui, ds, first, 32)
        assertTrue(ds.started)
        assertEquals(4, first.clips.size)
        assertEquals(5, first.updates.size, "one whole-grid update plus one per cell")
        assertTrue(first.rects.any { it.x == 15 && it.y == 15 && it.width == 67 && it.height == 67 })

        // A second redraw of the same state and UI draws nothing: no
        // black rectangle, no whole-grid update, no cell.
        val second = RecordingSink()
        Filling.redraw(state, ui, ds, second, 32)
        assertTrue(second.ops.isEmpty(), "a warm redraw must be empty")

        // A selection changes one cell only.
        ui.sel = BooleanArray(4)
        ui.sel!![0] = true
        val selected = RecordingSink()
        Filling.redraw(state, ui, ds, selected, 32)
        assertEquals(1, selected.clips.size)
        assertEquals(1, selected.updates.size)
        assertTrue(selected.rects.any { it.colour == 2 })

        // Clearing the selection redraws its cell; settle the cache.
        ui.sel = null
        Filling.redraw(state, ui, ds, RecordingSink(), 32)

        // A visible cursor changes one cell only and draws the ring.
        ui.curVisible = true
        ui.curX = 1
        ui.curY = 0
        val cursor = RecordingSink()
        Filling.redraw(state, ui, ds, cursor, 32)
        assertEquals(1, cursor.clips.size)
        assertTrue(cursor.polys.any { it.fill == -1 && it.outline == 6 })

        // A move changes the board. This tiny board touches all four
        // cells, because the region flags and borders spread, but it
        // never repeats the whole-grid update.
        ui.curVisible = false
        Filling.redraw(state, ui, ds, RecordingSink(), 32)
        val moved = Filling.executeMove(state, "0_5") as Filling.State
        val afterMove = RecordingSink()
        Filling.redraw(moved, ui, ds, afterMove, 32)
        assertEquals(4, afterMove.clips.size)
        assertEquals(4, afterMove.updates.size)
        assertFalse(afterMove.updates.contains(Update(0, 0, 96, 96)))
        assertTrue(afterMove.updates.contains(Update(16, 16, 32, 32)))

        // The cache is warm for the new board too.
        val settled = RecordingSink()
        Filling.redraw(moved, ui, ds, settled, 32)
        assertTrue(settled.ops.isEmpty())

        assertEquals(5, moved.board[0])
        assertEquals(2, state.board[0], "the clue board is not touched")
    }

    @Test
    fun `the solver matches fixed oracle strings`() {
        // The 9x7 preset at seed 12345, from the oracle.
        val nine = Midend.newGame(Filling, "9x7", "12345")
        assertEquals(
            "s888844665288844665287777665999977995299979995299669999666655555",
            Filling.solve(nine.state),
        )

        // An already solved fixed point: the solver returns it as is.
        val solved = Filling.newState(Filling.decodeParams("1x2"), "22")
        assertEquals("s22", Filling.solve(solved))

        // The 5x5 all-empty board stalls and C returns the partial move.
        val stalled = Filling.newState(Filling.decodeParams("5x5"), "y")
        assertEquals("s" + "0".repeat(25), Filling.solve(stalled))
        // C still returns the partial answer, so the midend never shows
        // this error (`filling.c:1384-1387`); the seam keeps both halves.
        val stalledOutcome = Filling.solveGame(stalled, null)
        assertEquals("s" + "0".repeat(25), stalledOutcome.move)
        assertEquals("Sorry, I couldn't find a solution", stalledOutcome.error)

        // Two adjacent 1 clues make an overfull region. C asserts at
        // `filling.c:796` and the shipped C code keeps assertions, so C
        // aborts. The port skips the region and fills the two empty
        // cells as 2s. This is deviation D-004.
        val overfull = Filling.newState(Filling.decodeParams("2x2"), "1a1a")
        assertEquals("s1212", Filling.solve(overfull))
    }

    @Test
    fun `a solve of an overfull one region skips it instead of aborting`() {
        // `2x2:1a1a` has two 1 clues on a diagonal, and the solver can
        // still build an overfull region of 1s. C asserts
        // `s->board[i] != 1` at `filling.c:796`. The shipped C code
        // keeps assertions (`app/src/main/jni/cmake/setup.cmake`), so
        // the oracle aborts and cannot be asked for this board. Kotlin
        // takes the guarded path, skips the region, and returns the
        // best board. This is deviation D-004.
        val midend = Midend.fromGameId(Filling, "2x2:1a1a")
        assertEquals("s1212", Filling.solve(midend.state))

        assertTrue(midend.solve(), "the solve is applied")
        assertEquals("s1212", midend.serialise().values("SOLVE").single())
        assertTrue((midend.state as Filling.State).cheated)
        // The two 1s of `s1212` are adjacent in the left column, so the
        // region has two cells and the completion check leaves the game
        // open. The solve still counts as a cheated solve.
        assertFalse((midend.state as Filling.State).completed)
        assertEquals(0, midend.status(), "status stays 0")
        assertEquals(
            "+---+---+\n| 1 | 2 |\n+---+---+\n| 1 | 2 |\n+---+---+\n",
            midend.textFormat(),
        )
    }

    @Test
    fun `a real fill changes the board and a click alone does not`() {
        val midend = Midend.fromGameId(Filling, "9x7:$DESC_9X7")
        val tile = Filling.preferredTileSize
        val before = (midend.state as Filling.State).board.copyOf()

        // A click only selects.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 32, Buttons.LEFT_BUTTON, tile))
        assertTrue(before.contentEquals((midend.state as Filling.State).board))
        assertEquals(1, midend.stateCount)

        // A fill changes the board and adds one state.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 32, '5'.code, tile))
        assertNotEquals(0, (midend.state as Filling.State).board[0])
        assertEquals(2, midend.stateCount)
        assertEquals(1, midend.serialise().values("MOVE").size)
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int, val colour: Int)
    private data class Poly(val fill: Int, val outline: Int)
    private data class Update(val x: Int, val y: Int, val width: Int, val height: Int)

    private class RecordingSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        val ops = ArrayList<String>()
        val rects = ArrayList<Rect>()
        val polys = ArrayList<Poly>()
        val clips = ArrayList<Update>()
        val updates = ArrayList<Update>()

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
            ops += "rect"
            rects += Rect(x, y, width, height, colour)
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
            ops += "circle"
        }
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly"
            polys += Poly(fillColour, outlineColour)
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
            ops += "text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
            clips += Update(x, y, width, height)
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update"
            updates += Update(x, y, width, height)
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun blitterNew(width: Int, height: Int): Int = 0

        override fun blitterFree(id: Int) {}

        override fun blitterSave(id: Int, x: Int, y: Int) {}

        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }

    private companion object {
        const val DESC_9X7 = "a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55"
    }
}
