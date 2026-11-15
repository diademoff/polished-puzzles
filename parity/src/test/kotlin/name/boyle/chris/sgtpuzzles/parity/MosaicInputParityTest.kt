package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: mosaic.
 *
 * Layout and real pointer input at tiles 48, 63, 82, and 100 and in a
 * letterboxed 411x702 view. Left and right presses cycle a cell, a clue
 * cell behaves like any other cell, and the three drag shapes paint a
 * straight line. The loose move grammar's unknown and empty moves are
 * accepted as a copy. The cursor keys, a hidden-cursor select, and
 * `requestKeys` are compared with the oracle, and the completed board
 * rejects every non-arrow input.
 *
 * Every real press or drag changes the board and adds one move. An inert
 * click, an arrow, a select that only reveals the ring, and an unknown or
 * empty move change neither the board nor the status; C still records one
 * history entry for an accepted move, so the history length is not a
 * no-change signal.
 *
 * The press-without-release trap of `mosaic.c` is pinned here: the midend
 * invents a release for a held button, and mosaic turns it into an `e`
 * fill, so a list of press-only points is not a list of independent
 * clicks.
 */
class MosaicInputParityTest {

    /** One viewport; the tile size is whatever the midend picks for it. */
    private data class View(val label: String, val width: Int, val height: Int)

    private val exactViews = listOf(
        View("528x528", 528, 528),
        View("693x693", 693, 693),
        View("902x902", 902, 902),
        View("1100x1100", 1100, 1100),
    )

    /** 411x702 gives tile 37, a 407x407 board, and origin (2,147). */
    private val letterbox = View("411x702", 411, 702)

    @Test
    fun `layout picks the four exact tiles and a nonzero letterbox origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("mosaic", PARAMS, SEED).state
            val params = Mosaic.decodeParams(PARAMS)

            for (view in exactViews) {
                val tile = view.width / 11
                val board = Mosaic.computeSize(params, tile)
                assertEquals("board width at ${view.label}", view.width, board.width)
                assertEquals("board height at ${view.label}", view.height, board.height)

                val layout = oracle.layout(save, view.width, view.height)
                val geometry = midend().geometryFor(view.width, view.height)
                assertEquals("tile at ${view.label}", tile, geometry.tileSize)
                assertEquals("width at ${view.label}", layout.width, geometry.width)
                assertEquals("height at ${view.label}", layout.height, geometry.height)
                assertEquals("origin x at ${view.label}", 0, geometry.originX)
                assertEquals("origin y at ${view.label}", 0, geometry.originY)
            }

            val layout = oracle.layout(save, letterbox.width, letterbox.height)
            val geometry = midend().geometryFor(letterbox.width, letterbox.height)
            assertEquals("letterbox tile", 37, geometry.tileSize)
            assertEquals("letterbox width", layout.width, geometry.width)
            assertEquals("letterbox height", layout.height, geometry.height)
            assertEquals(
                "letterbox origin x",
                (letterbox.width - layout.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "letterbox origin y",
                (letterbox.height - layout.height) / 2,
                geometry.originY,
            )
            assertTrue(
                "the letterbox origin is nonzero",
                geometry.originX != 0 || geometry.originY != 0,
            )
            assertEquals("letterbox board width", 407, geometry.width)
            assertEquals("letterbox board height", 407, geometry.height)
            assertEquals("letterbox origin x", 2, geometry.originX)
            assertEquals("letterbox origin y", 147, geometry.originY)
        }

    @Test
    fun `real left and right cycles at the four tiles and the letterbox`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("mosaic", PARAMS, SEED).state
            val cellX = 3
            val cellY = 2

            for (view in exactViews + letterbox) {
                val geometry = midend().geometryFor(view.width, view.height)
                val tile = geometry.tileSize
                val gameX = cellX * tile + tile / 2
                val gameY = cellY * tile + tile / 2
                val viewX = geometry.originX + gameX
                val viewY = geometry.originY + gameY

                for ((label, press, release, values) in listOf(
                    Cycle(
                        "left",
                        Buttons.LEFT_BUTTON,
                        Buttons.LEFT_RELEASE,
                        listOf(
                            Mosaic.STATE_MARKED,
                            Mosaic.STATE_BLANK,
                            Mosaic.STATE_UNMARKED,
                        ),
                    ),
                    Cycle(
                        "right",
                        Buttons.RIGHT_BUTTON,
                        Buttons.RIGHT_RELEASE,
                        listOf(
                            Mosaic.STATE_BLANK,
                            Mosaic.STATE_MARKED,
                            Mosaic.STATE_UNMARKED,
                        ),
                    ),
                )) {
                    val viewPoints = ArrayList<OracleClient.Point>()
                    repeat(values.size) {
                        viewPoints += OracleClient.Point(viewX, viewY, press)
                        viewPoints += OracleClient.Point(viewX, viewY, release)
                    }
                    val expected = oracle.pointsSized(
                        start,
                        viewPoints,
                        width = view.width,
                        height = view.height,
                        recordTrace = true,
                    )

                    val midend = midend()
                    var state = midend.state as Mosaic.State
                    for ((step, value) in values.withIndex()) {
                        val where = "$label step ${step + 1} at ${view.label}"
                        val cells = state.cells.copyOf()
                        val states = midend.stateCount
                        val moves = moves(midend).size

                        assertEquals(
                            "$where outcome",
                            KeyOutcome.SomeEffect,
                            midend.processKey(gameX, gameY, press, tile),
                        )
                        state = midend.state as Mosaic.State
                        assertFalse(
                            "$where changes the board",
                            cells.contentEquals(state.cells),
                        )
                        assertEquals(
                            "$where cycles to $value",
                            value,
                            state.valueAt(cellX, cellY) and Mosaic.STATE_OK_NUM,
                        )
                        assertEquals("$where adds one state", states + 1, midend.stateCount)
                        assertEquals("$where adds one move", moves + 1, moves(midend).size)

                        assertEquals(
                            "$where release outcome",
                            KeyOutcome.Unused,
                            midend.processKey(gameX, gameY, release, tile),
                        )
                        assertEquals(
                            "$where release adds no state",
                            states + 1,
                            midend.stateCount,
                        )
                        assertEquals(
                            "$where release adds no move",
                            moves + 1,
                            moves(midend).size,
                        )
                    }

                    assertEquals(
                        "$label state count at ${view.label}",
                        1 + values.size,
                        midend.stateCount,
                    )
                    assertSame("$label cycle at ${view.label}", expected.state, midend)
                    assertTrace(
                        "$label cycle trace at ${view.label}",
                        requireNotNull(expected.trace),
                        KotlinTraceSink.stateTrace(midend, tile),
                    )
                }
            }
        }

    @Test
    fun `a clue cell cycles like any other cell`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mosaic", PARAMS, SEED).state
        val tile = 48
        val board = Mosaic.computeSize(Mosaic.decodeParams(PARAMS), tile)
        val gameX = tile / 2
        val gameY = tile / 2

        val midend = midend()
        val base = midend.state as Mosaic.State
        assertEquals("(0,0) is a shown clue in the golden board", 2, base.clueAt(0, 0))
        assertTrue("(0,0) is shown", base.isShown(0, 0))
        val text = midend.textFormat()

        val points = listOf(
            OracleClient.Point(gameX, gameY, Buttons.LEFT_BUTTON),
            OracleClient.Point(gameX, gameY, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(
            start,
            points,
            width = board.width,
            height = board.height,
            recordTrace = true,
        )
        val cells = base.cells.copyOf()
        assertEquals(
            "clue press outcome",
            KeyOutcome.SomeEffect,
            midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
        )
        val marked = midend.state as Mosaic.State
        assertEquals(
            "the clue cell is marked",
            Mosaic.STATE_MARKED,
            marked.valueAt(0, 0) and Mosaic.STATE_OK_NUM,
        )
        assertFalse("the clue press changes the board", cells.contentEquals(marked.cells))
        assertEquals("the clue press adds one state", 2, midend.stateCount)
        assertEquals("the clue press adds one move", listOf("t0,0"), moves(midend))
        assertEquals("the clue text never changes", text, midend.textFormat())

        assertEquals(
            "clue release outcome",
            KeyOutcome.Unused,
            midend.processKey(gameX, gameY, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals("the release adds no state", 2, midend.stateCount)
        assertSame("clue click", expected.state, midend)
        assertTrace(
            "clue click trace",
            requireNotNull(expected.trace),
            KotlinTraceSink.stateTrace(midend, tile),
        )
    }

    @Test
    fun `border, margin, middle, and letterbox clicks are inert`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("mosaic", PARAMS, SEED).state
            val tile = 48
            val board = Mosaic.computeSize(Mosaic.decodeParams(PARAMS), tile)
            val border = tile / 2
            val band = listOf(
                "top band" to OracleClient.Point(border - 1, border + tile, Buttons.LEFT_BUTTON),
                "left band" to OracleClient.Point(border + tile, border - 1, Buttons.LEFT_BUTTON),
                "right margin" to OracleClient.Point(10 * tile + border, border + tile, Buttons.LEFT_BUTTON),
                "bottom margin" to OracleClient.Point(border + tile, 10 * tile + border, Buttons.LEFT_BUTTON),
                "middle button" to OracleClient.Point(border + tile, border + tile, Buttons.MIDDLE_BUTTON),
            )

            for ((label, point) in band) {
                val expected = oracle.pointsSized(
                    start,
                    listOf(point),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = midend()
                val before = midend.state as Mosaic.State
                val cells = before.cells.copyOf()
                assertEquals(
                    "$label outcome",
                    KeyOutcome.Unused,
                    midend.processKey(point.x, point.y, point.button, tile),
                )
                assertEquals("$label adds no state", 1, midend.stateCount)
                assertEquals("$label adds no move", 0, moves(midend).size)
                assertArrayEquals("$label keeps the cells", cells, (midend.state as Mosaic.State).cells)
                assertFalse("$label keeps the ring hidden", (midend.ui as Mosaic.Ui).curVisible)
                assertSame(label, expected.state, midend)
                assertTrace(
                    "$label trace",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }

            // A visible ring is hidden by the margin click, which is a UI
            // change with a `Unused` result and no state.
            val visiblePoints = listOf(
                OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT),
                OracleClient.Point(10 * tile + border, border + tile, Buttons.LEFT_BUTTON),
            )
            val visible = oracle.pointsSized(
                start,
                visiblePoints,
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val visibleMidend = midend()
            assertEquals(
                KeyOutcome.SomeEffect,
                visibleMidend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile),
            )
            assertTrue((visibleMidend.ui as Mosaic.Ui).curVisible)
            assertEquals(
                "a margin click with a visible ring is unused",
                KeyOutcome.Unused,
                visibleMidend.processKey(
                    10 * tile + border,
                    border + tile,
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
            )
            assertFalse((visibleMidend.ui as Mosaic.Ui).curVisible)
            assertEquals("the UI change adds no state", 1, visibleMidend.stateCount)
            assertSame("visible margin click", visible.state, visibleMidend)
            assertTrace(
                "visible margin click trace",
                requireNotNull(visible.trace),
                KotlinTraceSink.stateTrace(visibleMidend, tile),
            )

            // A letterbox click outside the board is negative in game
            // coordinates. The oracle subtracts the origin itself.
            val geometry = midend().geometryFor(letterbox.width, letterbox.height)
            val outside = OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)
            val letterboxExpected = oracle.pointsSized(
                start,
                listOf(outside),
                width = letterbox.width,
                height = letterbox.height,
                recordTrace = true,
            )
            val outsideMidend = midend()
            assertEquals(
                "letterbox outcome",
                KeyOutcome.Unused,
                outsideMidend.processKey(
                    outside.x - geometry.originX,
                    outside.y - geometry.originY,
                    outside.button,
                    geometry.tileSize,
                ),
            )
            assertEquals("letterbox adds no state", 1, outsideMidend.stateCount)
            assertEquals("letterbox adds no move", 0, moves(outsideMidend).size)
            assertSame("letterbox click", letterboxExpected.state, outsideMidend)
            assertTrace(
                "letterbox click trace",
                requireNotNull(letterboxExpected.trace),
                KotlinTraceSink.stateTrace(outsideMidend, geometry.tileSize),
            )
        }

    @Test
    fun `horizontal, vertical, diagonal, and fabricated drags match the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("mosaic", PARAMS, SEED).state
            val tile = 48
            val board = Mosaic.computeSize(Mosaic.decodeParams(PARAMS), tile)

            fun point(cellX: Int, cellY: Int, button: Int): OracleClient.Point =
                OracleClient.Point(cellX * tile + tile / 2, cellY * tile + tile / 2, button)

            data class Step(val point: OracleClient.Point, val adds: Int)

            data class Shape(val label: String, val steps: List<Step>, val moves: List<String>)

            val horizontal = Shape(
                "horizontal",
                listOf(
                    Step(point(1, 2, Buttons.LEFT_BUTTON), 1),
                    Step(point(4, 2, Buttons.LEFT_DRAG), 1),
                    Step(point(4, 2, Buttons.LEFT_RELEASE), 0),
                ),
                listOf("t1,2", "d4,2,1,2,1"),
            )
            val shapes = listOf(
                horizontal,
                Shape(
                    "vertical",
                    listOf(
                        Step(point(2, 1, Buttons.LEFT_BUTTON), 1),
                        Step(point(2, 4, Buttons.LEFT_DRAG), 1),
                        Step(point(2, 4, Buttons.LEFT_RELEASE), 0),
                    ),
                    listOf("t2,1", "d2,4,2,1,1"),
                ),
                Shape(
                    "diagonal",
                    listOf(
                        Step(point(0, 0, Buttons.LEFT_BUTTON), 1),
                        Step(point(2, 2, Buttons.LEFT_DRAG), 0),
                        Step(point(2, 2, Buttons.LEFT_RELEASE), 0),
                    ),
                    listOf("t0,0"),
                ),
                Shape(
                    "fabricated release",
                    listOf(
                        Step(point(0, 0, Buttons.LEFT_BUTTON), 1),
                        Step(point(1, 0, Buttons.LEFT_BUTTON), 2),
                    ),
                    listOf("t0,0", "e1,0,0,0,1", "t1,0"),
                ),
            )

            for (shape in shapes) {
                val label = shape.label
                val steps = shape.steps
                val expectedMoves = shape.moves
                val viewPoints = steps.map { it.point }
                val expected = oracle.pointsSized(
                    start,
                    viewPoints,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = midend()
                for ((step, action) in steps.withIndex()) {
                    val cells = (midend.state as Mosaic.State).cells.copyOf()
                    val states = midend.stateCount
                    val moves = moves(midend).size
                    midend.processKey(action.point.x, action.point.y, action.point.button, tile)
                    if (action.adds > 0) {
                        assertFalse(
                            "$label step $step changes the board",
                            cells.contentEquals((midend.state as Mosaic.State).cells),
                        )
                        assertEquals("$label step $step adds states", states + action.adds, midend.stateCount)
                        assertEquals("$label step $step adds moves", moves + action.adds, moves(midend).size)
                    } else {
                        assertEquals("$label step $step adds no state", states, midend.stateCount)
                        assertEquals("$label step $step adds no move", moves, moves(midend).size)
                    }
                }
                assertEquals("$label moves", expectedMoves, moves(midend))
                assertSame(label, expected.state, midend)
                assertTrace(
                    "$label trace",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }

            // The horizontal drag marks (1,2) to (4,2), and the press
            // marks the source cell.
            val values = midend()
            for (step in horizontal.steps) {
                values.processKey(step.point.x, step.point.y, step.point.button, tile)
            }
            val afterHorizontal = values.state as Mosaic.State
            for (x in 1..4) {
                assertEquals(
                    "the horizontal drag marks ($x,2)",
                    Mosaic.STATE_MARKED,
                    afterHorizontal.valueAt(x, 2) and Mosaic.STATE_OK_NUM,
                )
            }

            // The fabricated release paints the skipped cell with the
            // press value and then the second press cycles it again.
            val fabricated = midend()
            for (step in shapes[3].steps) {
                fabricated.processKey(step.point.x, step.point.y, step.point.button, tile)
            }
            val afterFabricated = fabricated.state as Mosaic.State
            assertEquals(
                "the fabricated fill leaves (1,0) blank",
                Mosaic.STATE_BLANK,
                afterFabricated.valueAt(1, 0) and Mosaic.STATE_OK_NUM,
            )
        }

    @Test
    fun `unknown and empty moves are accepted copies`() = OracleClient().use { oracle ->
        val base = Midend.fromSave(
            Mosaic,
            SgtpSave.parse(historySave("3x3", DESC, emptyList(), 1)),
        )
        val baseState = base.state as Mosaic.State
        val baseId = base.id()
        val baseStatus = base.status()
        val baseCells = baseState.cells.copyOf()

        for (move in listOf("", "X", "y1,0", "q9,9")) {
            val copy = Mosaic.executeMove(baseState, move) as Mosaic.State
            assertArrayEquals("'$move' keeps the cells", baseCells, copy.cells)
            assertEquals("'$move' keeps the status", baseStatus, Mosaic.status(copy))

            // Through the save loader C records one history entry and
            // rebuilds the same board.
            val save = historySave("3x3", DESC, listOf("MOVE" to move), 2)
            val expected = oracle.id(save)
            val loaded = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals("'$move' id", baseId, expected.id)
            assertEquals("'$move' Kotlin id", baseId, loaded.id())
            assertEquals("'$move' status", baseStatus, loaded.status())
            assertArrayEquals(
                "'$move' keeps the board through the loader",
                baseCells,
                (loaded.state as Mosaic.State).cells,
            )
            assertEquals("'$move' is one history entry", 2, loaded.stateCount)
            assertEquals("'$move' save", expected.state, loaded.serialise().serialise())
            assertEquals("'$move' text", oracle.text(expected.state), loaded.textFormat())
        }
    }

    @Test
    fun `cursor keys, modified keys, and selects follow the rules`() =
        OracleClient().use { oracle ->
            val save = historySave("3x3", DESC, emptyList(), 1)
            val tile = 32

            // An arrow moves before it reveals, so the first DOWN on a
            // hidden cursor at (0,0) leaves the ring at (0,1).
            val arrows = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_LEFT,
            )
            val moved = oracle.keys(save, arrows)
            val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            for ((index, arrow) in arrows.withIndex()) {
                assertEquals(
                    "arrow $index outcome",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, arrow, tile),
                )
                assertEquals("arrow $index adds no state", 1, midend.stateCount)
            }
            val ui = midend.ui as Mosaic.Ui
            assertTrue("the first arrow reveals the ring", ui.curVisible)
            assertEquals("arrows end on (0,0)", 0, ui.curX)
            assertEquals("arrows end on (0,0)", 0, ui.curY)
            assertEquals("arrows add no move", 0, moves(midend).size)
            assertSame("arrows", moved, midend)

            // An arrow at the edge with a visible ring is a no-effect.
            val edge = listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP)
            val edgeExpected = oracle.keys(save, edge)
            val edgeMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals(
                "the reveal is an effect",
                KeyOutcome.SomeEffect,
                edgeMidend.processKey(0, 0, edge[0], tile),
            )
            assertEquals(
                "the edge arrow is a no-effect",
                KeyOutcome.NoEffect,
                edgeMidend.processKey(0, 0, edge[1], tile),
            )
            assertEquals("the edge arrow adds no state", 1, edgeMidend.stateCount)
            assertSame("edge arrow", edgeExpected, edgeMidend)

            // A select with a hidden cursor only reveals it and places
            // nothing; the next select acts.
            val reveal = listOf(Buttons.CURSOR_SELECT)
            val revealExpected = oracle.keys(save, reveal)
            val revealMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals(
                "a hidden-cursor select reveals",
                KeyOutcome.SomeEffect,
                revealMidend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
            )
            assertTrue((revealMidend.ui as Mosaic.Ui).curVisible)
            assertEquals("the reveal adds no state", 1, revealMidend.stateCount)
            assertEquals("the reveal adds no move", 0, moves(revealMidend).size)
            assertSame("hidden-cursor select", revealExpected, revealMidend)

            // Enter is CURSOR_SELECT and Space is CURSOR_SELECT2. Both
            // act on the cursor cell, after the ring is made visible.
            for ((label, key, expectedMove) in listOf(
                Triple("Enter", '\n'.code, "t0,1"),
                Triple("Space", ' '.code, "T0,1"),
            )) {
                val sequence = listOf(Buttons.CURSOR_DOWN, key)
                val expected = oracle.keys(save, sequence)
                val selectMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
                selectMidend.processKey(0, 0, Buttons.CURSOR_DOWN, tile)
                assertEquals(
                    "$label outcome",
                    KeyOutcome.SomeEffect,
                    selectMidend.processKey(0, 0, key, tile),
                )
                assertEquals("$label adds one state", 2, selectMidend.stateCount)
                assertEquals("$label move", listOf(expectedMove), moves(selectMidend))
                assertSame(label, expected, selectMidend)
            }

            // A modified arrow is not the exact button, so it is unused
            // and leaves the ring hidden.
            for (modified in listOf(
                Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
            )) {
                val modifiedMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
                assertEquals(
                    "modified arrow $modified",
                    KeyOutcome.Unused,
                    modifiedMidend.processKey(0, 0, modified, tile),
                )
                assertFalse(
                    "modified arrow keeps the ring hidden",
                    (modifiedMidend.ui as Mosaic.Ui).curVisible,
                )
                assertEquals("modified arrow adds no state", 1, modifiedMidend.stateCount)
                assertEquals("modified arrow adds no move", 0, moves(modifiedMidend).size)
                assertSame(
                    "modified arrow $modified",
                    oracle.keys(save, listOf(modified)),
                    modifiedMidend,
                )
            }

            // A keypad digit is unused.
            val keypad = '1'.code or Buttons.MOD_NUM_KEYPAD
            val keypadMidend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            assertEquals(
                "a keypad digit is unused",
                KeyOutcome.Unused,
                keypadMidend.processKey(0, 0, keypad, tile),
            )
            assertEquals("the keypad digit adds no state", 1, keypadMidend.stateCount)
            assertSame("keypad digit", oracle.keys(save, listOf(keypad)), keypadMidend)

            // u and r are shortcuts while the game returns Unused.
            val acted = Midend.fromSave(Mosaic, SgtpSave.parse(save))
            acted.processKey(0, 0, Buttons.CURSOR_DOWN, tile)
            acted.processKey(0, 0, '\n'.code, tile)
            val afterSelect = acted.serialise().serialise()
            assertEquals(
                "u undoes",
                KeyOutcome.SomeEffect,
                acted.processKey(0, 0, 'u'.code, tile),
            )
            assertEquals("undo reopens the board", 0, acted.status())
            assertEquals(
                "r redoes",
                KeyOutcome.SomeEffect,
                acted.processKey(0, 0, 'r'.code, tile),
            )
            assertEquals("redo restores the select", afterSelect, acted.serialise().serialise())
            assertEquals(
                "u and r",
                afterSelect,
                oracle.keys(save, listOf(Buttons.CURSOR_DOWN, '\n'.code)).state,
            )
        }

    @Test
    fun `a completed board freezes every non-arrow input`() = OracleClient().use { oracle ->
        val save = historySave("3x3", DESC, emptyList(), 1)
        val tile = 32
        val view = 4 * tile
        val winningCells = listOf(
            0 to 0,
            1 to 0,
            2 to 0,
            0 to 1,
            1 to 1,
            2 to 1,
            2 to 2,
        )
        val blankCells = listOf(0 to 2, 1 to 2)

        val winning = ArrayList<OracleClient.Point>()
        for ((x, y) in winningCells) {
            winning += OracleClient.Point(x * tile + tile / 2, y * tile + tile / 2, Buttons.LEFT_BUTTON)
            winning += OracleClient.Point(x * tile + tile / 2, y * tile + tile / 2, Buttons.LEFT_RELEASE)
        }
        for ((x, y) in blankCells) {
            winning += OracleClient.Point(x * tile + tile / 2, y * tile + tile / 2, Buttons.RIGHT_BUTTON)
            winning += OracleClient.Point(x * tile + tile / 2, y * tile + tile / 2, Buttons.RIGHT_RELEASE)
        }

        val expected = oracle.pointsSized(
            save,
            winning,
            width = view,
            height = view,
            recordTrace = true,
        )
        val midend = Midend.fromSave(Mosaic, SgtpSave.parse(save))
        midend.takeCompletedEvent()
        for (point in winning) midend.processKey(point.x, point.y, point.button, tile)

        assertEquals("the sequence completes the board", 1, midend.status())
        assertEquals("the sequence text", "COMPLETED!", midend.statusText())
        assertTrue("the sequence raises the event", midend.takeCompletedEvent())
        assertSame("winning sequence", expected.state, midend)
        assertTrace(
            "winning sequence trace",
            requireNotNull(expected.trace),
            KotlinTraceSink.stateTrace(midend, tile),
        )

        val frozenSave = midend.serialise().serialise()
        val frozenCount = midend.stateCount
        val frozenCells = (midend.state as Mosaic.State).cells.copyOf()
        val frozen = listOf(
            OracleClient.Point(tile / 2, tile / 2, Buttons.LEFT_BUTTON),
            OracleClient.Point(tile + tile / 2, tile / 2, Buttons.LEFT_DRAG),
            OracleClient.Point(tile + tile / 2, tile / 2, Buttons.LEFT_RELEASE),
            OracleClient.Point(tile / 2, tile / 2, Buttons.RIGHT_BUTTON),
            OracleClient.Point(tile / 2, tile / 2, Buttons.RIGHT_RELEASE),
            OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
            OracleClient.Point(0, 0, Buttons.CURSOR_SELECT2),
        )
        val frozenExpected = oracle.pointsSized(
            frozenSave,
            frozen,
            width = view,
            height = view,
            recordTrace = true,
        )
        for ((index, point) in frozen.withIndex()) {
            assertEquals(
                "frozen input $index",
                KeyOutcome.Unused,
                midend.processKey(point.x, point.y, point.button, tile),
            )
            assertEquals("frozen input $index adds no state", frozenCount, midend.stateCount)
            assertEquals("frozen input $index keeps the save", frozenSave, midend.serialise().serialise())
            assertArrayEquals(
                "frozen input $index keeps the cells",
                frozenCells,
                (midend.state as Mosaic.State).cells,
            )
        }
        assertSame("frozen inputs", frozenExpected.state, midend)
        assertTrace(
            "frozen inputs trace",
            requireNotNull(frozenExpected.trace),
            KotlinTraceSink.stateTrace(midend, tile),
        )

        // An arrow still moves and reveals.
        val arrowExpected = oracle.keys(frozenSave, listOf(Buttons.CURSOR_DOWN))
        assertEquals(
            "the arrow still moves",
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_DOWN, tile),
        )
        val ui = midend.ui as Mosaic.Ui
        assertTrue("the arrow reveals the ring", ui.curVisible)
        assertEquals("the arrow moves the cursor", 1, ui.curY)
        assertEquals("the arrow adds no state", frozenCount, midend.stateCount)
        assertSame("arrow after completion", arrowExpected, midend)
    }

    @Test
    fun `requestKeys matches the oracle in every preset`() = OracleClient().use { oracle ->
        for (preset in Mosaic.presets()) {
            val start = oracle.newGame("mosaic", preset.params, SEED)
            val expected = oracle.requestKeys(start.state)
            val actual = Mosaic.requestKeys(Mosaic.decodeParams(preset.params))
            assertEquals("arrow mode for '${preset.params}'", 3, expected.arrowMode)
            assertEquals(
                "Kotlin arrow mode for '${preset.params}'",
                ArrowMode.ARROWS_LEFT_RIGHT,
                actual.arrowMode,
            )
            assertTrue("no C labels for '${preset.params}'", expected.labels.isEmpty())
            assertTrue("no Kotlin labels for '${preset.params}'", actual.labels.isEmpty())
        }
    }

    /** One press and one release cycle with its three values. */
    private data class Cycle(
        val label: String,
        val press: Int,
        val release: Int,
        val values: List<Int>,
    )

    private fun midend(): Midend = Midend.newGame(Mosaic, PARAMS, SEED)

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Mosaic.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private fun moves(midend: Midend): List<String> =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE")

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what save", expected.state, actual.serialise().serialise())
        assertEquals("$what can undo", expected.canUndo, actual.canUndo)
        assertEquals("$what can redo", expected.canRedo, actual.canRedo)
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

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "10x10"
        const val DESC = "4b47d"
    }
}
