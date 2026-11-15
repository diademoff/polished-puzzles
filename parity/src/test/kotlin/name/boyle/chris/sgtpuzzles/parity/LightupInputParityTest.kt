package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: lightup.
 *
 * Layout and real pointer input at tiles 48, 63, 82, and 100 and in a
 * letterboxed view. The two `STYLUS_BASED` cycles, the clue, border,
 * and letterbox no-moves, the cursor keys with their modifiers, a
 * hidden-cursor select, a press-drag-release, and `show-lit-blobs` are
 * compared with the oracle. Every real light or mark changes the grid
 * and adds one move; a UI-only action changes neither.
 *
 * The click bands come from `FROMCOORD` in `lightup.c:81`, so a
 * geometry mistake changes the hit test. The oracle's `pointsSized`
 * applies the letterbox origin itself; the Kotlin calls pass the game
 * coordinates that `BoardGeometry` produces.
 */
class LightupInputParityTest {

    /** One viewport and the tile size C picks for it. */
    private data class View(val label: String, val width: Int, val height: Int, val tile: Int)

    private val exactViews = listOf(
        View("384x384", 384, 384, 48),
        View("503x503", 503, 503, 63),
        View("656x656", 656, 656, 82),
        View("800x800", 800, 800, 100),
    )

    /** 411x702 gives tile 51, a 407x407 board, and origin (2,147). */
    private val letterbox = View("411x702", 411, 702, 51)

    @Test
    fun `layout picks the four exact tiles and a nonzero letterbox origin`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("lightup", PARAMS, SEED).state
            val params = Lightup.decodeParams(PARAMS)

            for (view in exactViews) {
                val board = Lightup.computeSize(params, view.tile)
                assertEquals("board width at ${view.label}", view.width, board.width)
                assertEquals("board height at ${view.label}", view.height, board.height)

                val layout = oracle.layout(save, view.width, view.height)
                val geometry = midend().geometryFor(view.width, view.height)
                assertEquals("tile at ${view.label}", view.tile, geometry.tileSize)
                assertEquals("width at ${view.label}", layout.width, geometry.width)
                assertEquals("height at ${view.label}", layout.height, geometry.height)
                assertEquals("origin x at ${view.label}", 0, geometry.originX)
                assertEquals("origin y at ${view.label}", 0, geometry.originY)
            }

            val layout = oracle.layout(save, letterbox.width, letterbox.height)
            val geometry = midend().geometryFor(letterbox.width, letterbox.height)
            assertEquals("letterbox tile", letterbox.tile, geometry.tileSize)
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
    fun `real stylus cycles at the four tiles and in the letterbox`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("lightup", PARAMS, SEED).state
            for (view in exactViews + letterbox) {
                val geometry = midend().geometryFor(view.width, view.height)
                assertEquals("tile at ${view.label}", view.tile, geometry.tileSize)
                val gameX = 4 * view.tile + view.tile / 2
                val gameY = view.tile / 2
                val viewX = geometry.originX + gameX
                val viewY = geometry.originY + gameY

                for ((label, press, release) in listOf(
                    Triple("left", Buttons.LEFT_BUTTON, Buttons.LEFT_RELEASE),
                    Triple("right", Buttons.RIGHT_BUTTON, Buttons.RIGHT_RELEASE),
                )) {
                    val viewPoints = ArrayList<OracleClient.Point>()
                    repeat(CYCLE_STEPS) {
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
                    var state = midend.state as Lightup.State
                    for (step in 0 until CYCLE_STEPS) {
                        val where = "$label step ${step + 1} at ${view.label}"
                        val lights = state.lights.copyOf()
                        val flags = state.flags.copyOf()
                        val states = midend.stateCount
                        val moves = moves(midend).size

                        assertEquals(
                            "$where outcome",
                            KeyOutcome.SomeEffect,
                            midend.processKey(gameX, gameY, press, view.tile),
                        )
                        state = midend.state as Lightup.State
                        assertTrue(
                            "$where changes the grid",
                            !lights.contentEquals(state.lights) ||
                                !flags.contentEquals(state.flags),
                        )
                        assertEquals("$where adds one state", states + 1, midend.stateCount)
                        assertEquals("$where adds one move", moves + 1, moves(midend).size)

                        assertEquals(
                            "$where release outcome",
                            KeyOutcome.Unused,
                            midend.processKey(gameX, gameY, release, view.tile),
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

                    assertEquals("$label state count at ${view.label}", 1 + CYCLE_STEPS, midend.stateCount)
                    assertSame("$label cycle at ${view.label}", expected.state, midend)
                    assertTrace(
                        "$label cycle trace at ${view.label}",
                        requireNotNull(expected.trace),
                        KotlinTraceSink.stateTrace(midend, view.tile),
                    )
                }
            }
        }

    @Test
    fun `clue, border, and letterbox clicks are inert`() = OracleClient().use { oracle ->
        val start = oracle.newGame("lightup", PARAMS, SEED).state
        val tile = 48
        val board = Lightup.computeSize(Lightup.decodeParams(PARAMS), tile)
        val border = tile / 2
        val band = listOf(
            Triple(
                "black cell",
                OracleClient.Point(border, border, Buttons.LEFT_BUTTON),
                KeyOutcome.NoEffect,
            ),
            Triple(
                "clue cell",
                OracleClient.Point(6 * tile + border, border, Buttons.LEFT_BUTTON),
                KeyOutcome.NoEffect,
            ),
            Triple(
                "left border",
                OracleClient.Point(border - 1, border + 16, Buttons.LEFT_BUTTON),
                KeyOutcome.NoEffect,
            ),
            Triple(
                "top border",
                OracleClient.Point(border + 16, border - 1, Buttons.LEFT_BUTTON),
                KeyOutcome.NoEffect,
            ),
            Triple(
                "right border",
                OracleClient.Point(border + 16, board.width - border, Buttons.LEFT_BUTTON),
                KeyOutcome.NoEffect,
            ),
            Triple(
                "bottom border",
                OracleClient.Point(border + 16, board.height - border, Buttons.LEFT_BUTTON),
                KeyOutcome.NoEffect,
            ),
            Triple(
                "middle button",
                OracleClient.Point(border + 16, border + 16, Buttons.MIDDLE_BUTTON),
                KeyOutcome.Unused,
            ),
        )

        for ((label, point, expectedOutcome) in band) {
            val expected = oracle.pointsSized(
                start,
                listOf(point),
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val midend = midend()
            val before = midend.state as Lightup.State
            val lights = before.lights.copyOf()
            val flags = before.flags.copyOf()

            assertEquals(
                "$label outcome",
                expectedOutcome,
                midend.processKey(point.x, point.y, point.button, tile),
            )
            assertEquals("$label adds no state", 1, midend.stateCount)
            assertEquals("$label adds no move", 0, moves(midend).size)
            assertTrue(
                "$label keeps the lights",
                lights.contentEquals((midend.state as Lightup.State).lights),
            )
            assertTrue(
                "$label keeps the flags",
                flags.contentEquals((midend.state as Lightup.State).flags),
            )
            assertSame(label, expected.state, midend)
            assertTrace(
                "$label trace",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(midend, tile),
            )
        }

        // A visible cursor turns the same border click into a UI update
        // that hides the ring and still adds no state.
        val visiblePoints = listOf(
            OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT),
            OracleClient.Point(border - 1, border + 16, Buttons.LEFT_BUTTON),
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
        assertEquals(
            "a border click with a visible cursor is a UI update",
            KeyOutcome.SomeEffect,
            visibleMidend.processKey(border - 1, border + 16, Buttons.LEFT_BUTTON, tile),
        )
        assertFalse((visibleMidend.ui as Lightup.Ui).curVisible)
        assertEquals("the UI update adds no state", 1, visibleMidend.stateCount)
        assertSame("visible border click", visible.state, visibleMidend)
        assertTrace(
            "visible border click trace",
            requireNotNull(visible.trace),
            KotlinTraceSink.stateTrace(visibleMidend, tile),
        )

        // A click in the letterbox has no cell. The oracle subtracts the
        // view origin; the Kotlin call passes the resulting game
        // coordinates, which are negative here.
        val letterboxGeometry = midend().geometryFor(letterbox.width, letterbox.height)
        val outside = OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)
        val letterboxExpected = oracle.pointsSized(
            start,
            listOf(outside),
            width = letterbox.width,
            height = letterbox.height,
            recordTrace = true,
        )
        val outsideMidend = midend()
        val outsideBefore = outsideMidend.state as Lightup.State
        assertEquals(
            "letterbox outcome",
            KeyOutcome.NoEffect,
            outsideMidend.processKey(
                outside.x - letterboxGeometry.originX,
                outside.y - letterboxGeometry.originY,
                outside.button,
                letterbox.tile,
            ),
        )
        assertEquals("letterbox adds no state", 1, outsideMidend.stateCount)
        assertEquals("letterbox adds no move", 0, moves(outsideMidend).size)
        assertTrue(
            "letterbox keeps the lights",
            (outsideBefore.lights).contentEquals((outsideMidend.state as Lightup.State).lights),
        )
        assertSame("letterbox click", letterboxExpected.state, outsideMidend)
        assertTrace(
            "letterbox click trace",
            requireNotNull(letterboxExpected.trace),
            KotlinTraceSink.stateTrace(outsideMidend, letterbox.tile),
        )
    }

    @Test
    fun `cursor keys, modified keys, and a hidden-cursor select follow the rules`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("lightup", PARAMS, SEED).state
            val tile = 32

            // An arrow reveals the ring and moves the cursor; none of the
            // four keys is a move.
            val arrows = listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_UP,
            )
            val moved = oracle.keys(save, arrows)
            val midend = midend()
            for (index in arrows.indices) {
                assertEquals(
                    "arrow $index",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, arrows[index], tile),
                )
            }
            assertEquals("arrows add no state", 1, midend.stateCount)
            assertEquals("arrows add no move", 0, moves(midend).size)
            assertEquals("arrows end on (0,0)", "0,0", cursor(midend))
            assertSame("arrows", moved, midend)

            // A modified arrow is not a cursor move, so it is unused and
            // the cursor stays hidden. A select then shows the cursor but
            // is a no-effect.
            for (modified in listOf(
                Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
            )) {
                val sequence = listOf(modified, Buttons.CURSOR_SELECT)
                val expected = oracle.keys(save, sequence)
                val modifiedMidend = midend()
                assertEquals(
                    "modified arrow $modified",
                    KeyOutcome.Unused,
                    modifiedMidend.processKey(0, 0, modified, tile),
                )
                assertEquals("a modified arrow moves no cursor", "0,0", cursor(modifiedMidend))
                assertFalse(
                    "a modified arrow keeps the ring hidden",
                    (modifiedMidend.ui as Lightup.Ui).curVisible,
                )
                assertEquals(
                    "a hidden-cursor select is a no-effect",
                    KeyOutcome.NoEffect,
                    modifiedMidend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
                )
                assertTrue(
                    "a hidden-cursor select shows the ring",
                    (modifiedMidend.ui as Lightup.Ui).curVisible,
                )
                assertEquals("no state", 1, modifiedMidend.stateCount)
                assertEquals("no move", 0, moves(modifiedMidend).size)
                assertSame("modified arrow then select", expected, modifiedMidend)
            }

            // SHFT on a select is stripped by the midend, so it acts on
            // the white cell (1,0). CTRL on a select is rejected before
            // the game.
            val shift = listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.MOD_SHFT or Buttons.CURSOR_SELECT,
            )
            val shiftExpected = oracle.keys(save, shift)
            val shiftMidend = midend()
            shiftMidend.processKey(0, 0, shift[0], tile)
            assertEquals(
                "SHFT+select acts",
                KeyOutcome.SomeEffect,
                shiftMidend.processKey(0, 0, shift[1], tile),
            )
            assertEquals("SHFT+select adds one state", 2, shiftMidend.stateCount)
            assertEquals(listOf("L1,0"), moves(shiftMidend))
            assertSame("SHFT+select", shiftExpected, shiftMidend)

            val ctrlSelect = Buttons.MOD_CTRL or Buttons.CURSOR_SELECT
            val ctrlMidend = midend()
            ctrlMidend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
            assertEquals(
                "CTRL+select is unused",
                KeyOutcome.Unused,
                ctrlMidend.processKey(0, 0, ctrlSelect, tile),
            )
            assertEquals("CTRL+select adds no state", 1, ctrlMidend.stateCount)
            assertEquals("CTRL+select adds no move", 0, moves(ctrlMidend).size)

            // A keypad digit, TAB, and backspace are not lightup keys.
            for (key in listOf(
                Buttons.MOD_NUM_KEYPAD or '1'.code,
                '\t'.code,
                '\b'.code,
            )) {
                val unusedMidend = midend()
                assertEquals(
                    "key $key is unused",
                    KeyOutcome.Unused,
                    unusedMidend.processKey(0, 0, key, tile),
                )
                assertEquals("key $key adds no state", 1, unusedMidend.stateCount)
                assertEquals("key $key adds no move", 0, moves(unusedMidend).size)
                assertFalse(
                    "key $key keeps the ring hidden",
                    (unusedMidend.ui as Lightup.Ui).curVisible,
                )
                assertSame("key $key", oracle.keys(save, listOf(key)), unusedMidend)
            }

            // A select with a hidden cursor is a no-effect but C sets
            // the visibility flag before it returns.
            val hidden = midend()
            hidden.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
            hidden.setCursorVisible(false)
            assertEquals(
                "hidden-cursor select",
                KeyOutcome.NoEffect,
                hidden.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
            )
            assertTrue("the select shows the ring", (hidden.ui as Lightup.Ui).curVisible)
            assertEquals("the select adds no state", 1, hidden.stateCount)
            assertEquals(
                "the next select acts",
                KeyOutcome.SomeEffect,
                hidden.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
            )
            assertEquals("the acting select adds one state", 2, hidden.stateCount)
        }

    @Test
    fun `a press drag and release makes one move at the press`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("lightup", PARAMS, SEED).state
            val tile = 48
            val board = Lightup.computeSize(Lightup.decodeParams(PARAMS), tile)
            val x0 = 4 * tile + tile / 2
            val y0 = tile / 2
            val x1 = 5 * tile + tile / 2
            val y1 = 2 * tile + tile / 2

            val points = listOf(
                OracleClient.Point(x0, y0, Buttons.LEFT_BUTTON),
                OracleClient.Point(x1, y1, Buttons.LEFT_DRAG),
                OracleClient.Point(x1, y1, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                start,
                points,
                width = board.width,
                height = board.height,
                recordTrace = true,
            )
            val midend = midend()
            val before = midend.state as Lightup.State
            val lights = before.lights.copyOf()
            for (point in points) {
                midend.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals("one state", 2, midend.stateCount)
            assertEquals("one move", listOf("L4,0"), moves(midend))
            assertNotEquals(
                "the press changes the board",
                lights.toList(),
                (midend.state as Lightup.State).lights.toList(),
            )
            assertSame("press drag release", expected.state, midend)
            assertTrace(
                "press drag release trace",
                requireNotNull(expected.trace),
                KotlinTraceSink.stateTrace(midend, tile),
            )

            // A press without a release also makes one move.
            val dragOnly = listOf(
                OracleClient.Point(x0, y0, Buttons.LEFT_BUTTON),
                OracleClient.Point(x1, y1, Buttons.LEFT_DRAG),
            )
            val dragExpected = oracle.pointsSized(
                start,
                dragOnly,
                width = board.width,
                height = board.height,
            )
            val dragMidend = midend()
            for (point in dragOnly) {
                dragMidend.processKey(point.x, point.y, point.button, tile)
            }
            assertEquals("a press and drag makes one move", 2, dragMidend.stateCount)
            assertSame("press and drag", dragExpected.state, dragMidend)

            // A lone drag or release is unused and changes nothing.
            for (action in listOf(Buttons.LEFT_DRAG, Buttons.LEFT_RELEASE)) {
                val lone = midend()
                assertEquals(
                    "lone $action",
                    KeyOutcome.Unused,
                    lone.processKey(x1, y1, action, tile),
                )
                assertEquals("lone $action adds no state", 1, lone.stateCount)
                assertEquals("lone $action adds no move", 0, moves(lone).size)
                assertEquals("lone $action keeps the save", start, lone.serialise().serialise())
            }
        }

    @Test
    fun `show-lit-blobs changes the trace but not the state or the cached flags`() =
        OracleClient().use { oracle ->
            val midend = midend()
            val tile = 32
            // A light at (1,0) lights (1,1), where a right click puts an
            // impossible mark, so the blob is visible.
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(48, 16, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(48, 48, Buttons.RIGHT_BUTTON, tile),
            )
            val save = midend.serialise().serialise()
            val id = midend.id()
            val status = midend.status()
            val state = midend.state
            val ui = midend.ui as Lightup.Ui

            var onOps: List<String>? = null
            var offOps: List<String>? = null
            for (on in listOf(true, false)) {
                ui.drawBlobsWhenLit = on
                val expected = oracle.trace(save, prefs = "show-lit-blobs=$on\n")
                val actual = KotlinTraceSink.stateTrace(midend, tile)
                assertTrace("show-lit-blobs=$on", expected, actual)
                if (on) onOps = operations(TraceNormalizer.normalize(expected))
                else offOps = operations(TraceNormalizer.normalize(expected))
                assertEquals("the preference changes no state", state, midend.state)
                assertEquals("the preference changes no id", id, midend.id())
                assertEquals("the preference changes no status", status, midend.status())
                assertEquals("the save is unchanged", save, midend.serialise().serialise())
            }
            assertEquals("blob on operations", 152, requireNotNull(onOps).size)
            assertEquals("blob off operations", 151, requireNotNull(offOps).size)
            assertEquals(
                "the only difference is the blob",
                listOf("rect 60 60 8 8 2"),
                requireNotNull(onOps).filter { it !in requireNotNull(offOps).toSet() },
            )

            // The preference is read while drawing, never cached per cell.
            val sink = KotlinTraceSink()
            ui.drawBlobsWhenLit = true
            val drawState = Lightup.newDrawState(midend.state, tile, sink) as Lightup.DrawState
            Lightup.redraw(midend.state, ui, drawState, sink, tile)
            val onFlags = drawState.flags.copyOf()
            ui.drawBlobsWhenLit = false
            Lightup.redraw(midend.state, ui, drawState, sink, tile)
            assertArrayEquals(
                "the preference does not enter DrawState.flags",
                onFlags,
                drawState.flags,
            )
        }

    @Test
    fun `requestKeys matches the oracle in every preset`() = OracleClient().use { oracle ->
        for (preset in Lightup.presets()) {
            val start = oracle.newGame("lightup", preset.params, SEED)
            val expected = oracle.requestKeys(start.state)
            val actual = Lightup.requestKeys(Lightup.decodeParams(preset.params))
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

    private fun midend(): Midend = Midend.newGame(Lightup, PARAMS, SEED)

    private fun moves(midend: Midend): List<String> =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE")

    private fun cursor(midend: Midend): String {
        val ui = midend.ui as Lightup.Ui
        return "${ui.curX},${ui.curY}"
    }

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

    /** The operation lines, after the trace header and the palette. */
    private fun operations(trace: String): List<String> =
        trace.lines()
            .dropWhile { !it.startsWith("palette ") }
            .drop(1)
            .filter { it.isNotEmpty() }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "7x7b20s3d0"

        /** The two cycles have three states: empty, light, impossible. */
        const val CYCLE_STEPS = 3
    }
}
