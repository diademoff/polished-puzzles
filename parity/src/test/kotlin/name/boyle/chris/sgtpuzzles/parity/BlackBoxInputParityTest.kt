package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic and input geometry. Game: blackbox.
 *
 * The layout, real laser clicks at tiles 48, 63, 82, and 100, a
 * letterboxed laser click, a ball toggle, a right-button lock and its
 * clear path, a reveal, a UI-only flash, an unavailable reveal, the
 * cursor keys, the requested keys, and the vacuous letterbox,
 * frame-band, grid-corner, one-pixel-outside, drag, modified-cursor-key,
 * and keypad-digit cases. The cell centres come from the C
 * `game_compute_size` and `TODRAW` arithmetic in
 * `work/specs/blackbox.md`, not from the Kotlin geometry, so a geometry
 * mistake changes the hit test. Every real laser or toggle must change
 * the board and add one state; a UI-only flash, an unavailable reveal,
 * and every vacuous event must change neither.
 */
class BlackBoxInputParityTest {

    /** One viewport, the tile size C picks, and its letterbox origin. */
    private data class View(
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int,
        val originY: Int,
    )

    private val views = listOf(
        View(384, 384, 48, 0, 0),
        View(504, 504, 63, 0, 0),
        View(656, 656, 82, 0, 0),
        View(800, 800, 100, 0, 0),
        // 411x702 gives tile 51, a 408x408 board, and origin (1,147).
        View(411, 702, 51, 1, 147),
    )

    @Test
    fun `the layout matches the oracle at five viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", PARAMS, SEED).state
            for (view in views) {
                val layout = oracle.layout(save, view.viewWidth, view.viewHeight)
                val geometry = Midend.newGame(BlackBox, PARAMS, SEED)
                    .geometryFor(view.viewWidth, view.viewHeight)
                val label = "${view.viewWidth}x${view.viewHeight}"

                assertEquals("tile at $label", view.tile, geometry.tileSize)
                assertEquals("width at $label", layout.width, geometry.width)
                assertEquals("height at $label", layout.height, geometry.height)
                assertEquals(
                    "origin x at $label",
                    (view.viewWidth - layout.width) / 2,
                    geometry.originX,
                )
                assertEquals(
                    "origin y at $label",
                    (view.viewHeight - layout.height) / 2,
                    geometry.originY,
                )
                assertEquals("oracle origin x at $label", view.originX, geometry.originX)
                assertEquals("oracle origin y at $label", view.originY, geometry.originY)
            }
        }

    @Test
    fun `a real laser click at every tile size matches the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", PARAMS, SEED).state
            for (view in views) {
                val label = "${view.viewWidth}x${view.viewHeight} tile ${view.tile}"
                // The top range cell (1,0) is laser F0. Its centre is
                // TODRAW(1) + tile/2 in game coordinates.
                val gameX = view.tile * 2
                val gameY = view.tile
                val points = listOf(
                    OracleClient.Point(
                        view.originX + gameX,
                        view.originY + gameY,
                        Buttons.LEFT_BUTTON,
                    ),
                    OracleClient.Point(
                        view.originX + gameX,
                        view.originY + gameY,
                        Buttons.LEFT_RELEASE,
                    ),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = view.viewWidth,
                    height = view.viewHeight,
                    recordTrace = true,
                )
                assertEquals("laser at $label", listOf("F0"), movesOf(expected.state.state))

                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
                val before = midend.state
                for (point in points) {
                    assertEquals(
                        "outcome at $label",
                        KeyOutcome.SomeEffect,
                        midend.processKey(
                            point.x - view.originX,
                            point.y - view.originY,
                            point.button,
                            view.tile,
                        ),
                    )
                }

                assertNotEquals("board change at $label", before, midend.state)
                assertEquals("one move at $label", 2, midend.stateCount)
                assertSame("laser at $label", expected.state, midend)
                assertEquals(
                    "status text at $label",
                    statusOf(requireNotNull(expected.trace)),
                    midend.statusText(),
                )
                assertTrace(
                    "laser trace at $label",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(midend, view.tile),
                )
            }
        }

    @Test
    fun `a ball toggle, a lock, and a reveal change the board and the count`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", PARAMS, SEED).state
            // Tile 63 is not the preferred tile, so the hit test cannot
            // pass by accident on the default geometry.
            val view = View(504, 504, 63, 0, 0)

            val arena = OracleClient.Point(view.tile * 2, view.tile * 2, Buttons.LEFT_BUTTON)
            val arenaUp = OracleClient.Point(arena.x, arena.y, Buttons.LEFT_RELEASE)
            val ball = oracle.pointsSized(
                save,
                listOf(arena, arenaUp),
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals("ball toggle", listOf("T1,1"), movesOf(ball.state.state))
            assertToggle(
                "ball toggle",
                save,
                listOf(arena, arenaUp),
                view,
                ball,
                "T1,1",
            )

            val lock = oracle.pointsSized(
                save,
                listOf(
                    OracleClient.Point(arena.x, arena.y, Buttons.RIGHT_BUTTON),
                    OracleClient.Point(arena.x, arena.y, Buttons.RIGHT_RELEASE),
                ),
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals("lock", listOf("LB1,1"), movesOf(lock.state.state))
            assertToggle(
                "lock",
                save,
                listOf(
                    OracleClient.Point(arena.x, arena.y, Buttons.RIGHT_BUTTON),
                    OracleClient.Point(arena.x, arena.y, Buttons.RIGHT_RELEASE),
                ),
                view,
                lock,
                "LB1,1",
            )

            // A right click on the top range cell toggles column 1.
            // The second click counts five locks and clears them.
            val range = OracleClient.Point(view.tile * 2, view.tile, Buttons.RIGHT_BUTTON)
            val rangeUp = OracleClient.Point(range.x, range.y, Buttons.RIGHT_RELEASE)
            val column = oracle.pointsSized(
                save,
                listOf(range, rangeUp),
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals("column lock", listOf("LC1"), movesOf(column.state.state))
            assertToggle(
                "column lock",
                save,
                listOf(range, rangeUp),
                view,
                column,
                "LC1",
            )

            val clearSave = column.state.state
            val clear = oracle.pointsSized(
                clearSave,
                listOf(range, rangeUp),
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals(
                "column clear",
                listOf("LC1", "LC1"),
                movesOf(clear.state.state),
            )
            val clearMidend = Midend.fromSave(BlackBox, SgtpSave.parse(clearSave))
            val locked = clearMidend.state
            assertEquals(
                "clear outcome",
                KeyOutcome.SomeEffect,
                clearMidend.processKey(range.x, range.y, Buttons.RIGHT_BUTTON, view.tile),
            )
            assertEquals(
                "clear release",
                KeyOutcome.Unused,
                clearMidend.processKey(range.x, range.y, Buttons.RIGHT_RELEASE, view.tile),
            )
            assertNotEquals("clear changes the locks", locked, clearMidend.state)
            assertEquals("clear adds one state", 3, clearMidend.stateCount)
            assertSame("clear", clear.state, clearMidend)
            assertTrace(
                "clear trace",
                requireNotNull(clear.trace),
                KotlinTraceSink.stateTrace(clearMidend, view.tile),
            )

            // A right click on the right range cell toggles row 2, so
            // the row branch is not left to the move-grammar test.
            val row = OracleClient.Point(view.tile * 7, view.tile * 3, Buttons.RIGHT_BUTTON)
            val rowUp = OracleClient.Point(row.x, row.y, Buttons.RIGHT_RELEASE)
            val rowLock = oracle.pointsSized(
                save,
                listOf(row, rowUp),
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals("row lock", listOf("LR2"), movesOf(rowLock.state.state))
            assertToggle(
                "row lock",
                save,
                listOf(row, rowUp),
                view,
                rowLock,
                "LR2",
            )

            // Three wrong guesses make the reveal button reachable. The
            // reveal is a real move, so it changes the board and the
            // count.
            val guessed = historySave(
                listOf(
                    "MOVE" to "T1,1",
                    "MOVE" to "T2,1",
                    "MOVE" to "T3,1",
                ),
            )
            val revealPoints = listOf(
                OracleClient.Point(view.tile, view.tile, Buttons.LEFT_BUTTON),
                OracleClient.Point(view.tile, view.tile, Buttons.LEFT_RELEASE),
            )
            val reveal = oracle.pointsSized(
                guessed,
                revealPoints,
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals(
                "reveal",
                listOf("T1,1", "T2,1", "T3,1", "R"),
                movesOf(reveal.state.state),
            )
            assertToggle(
                "reveal",
                guessed,
                revealPoints,
                view,
                reveal,
                "R",
                expectedCount = 5,
            )
            assertEquals("wrong reveal status", 0, reveal.state.status)
            assertEquals(
                "wrong reveal text",
                "Wrong! Guess again. (1 incorrect attempt)",
                statusOf(requireNotNull(reveal.trace)),
            )
        }

    @Test
    fun `a UI-only flash and an unavailable reveal change neither`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("blackbox", PARAMS, SEED).state
            val view = View(504, 504, 63, 0, 0)
            val laser = OracleClient.Point(view.tile * 2, view.tile, Buttons.LEFT_BUTTON)
            val laserUp = OracleClient.Point(laser.x, laser.y, Buttons.LEFT_RELEASE)

            // Fire F0 once, then press the same laser again. The second
            // press only flashes, so it adds no state and no move.
            val fired = oracle.pointsSized(
                start,
                listOf(laser, laserUp),
                width = view.viewWidth,
                height = view.viewHeight,
            ).state.state
            assertEquals("one fired laser", listOf("F0"), movesOf(fired))

            val flash = oracle.pointsSized(
                fired,
                listOf(laser),
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            assertEquals("flash adds no move", fired, flash.state.state)

            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(fired))
            val before = midend.state
            assertEquals(
                "flash outcome",
                KeyOutcome.SomeEffect,
                midend.processKey(laser.x, laser.y, Buttons.LEFT_BUTTON, view.tile),
            )
            assertEquals("flash adds no state", 2, midend.stateCount)
            assertEquals("flash keeps the board", before, midend.state)
            assertSame("flash", flash.state, midend)
            assertEquals(
                "flash status text",
                statusOf(requireNotNull(flash.trace)),
                midend.statusText(),
            )
            assertTrace(
                "flash drawing",
                requireNotNull(flash.trace),
                KotlinTraceSink.stateTrace(midend, view.tile),
            )

            // A reveal below the minimum guess count is unused: it adds
            // no state and changes nothing.
            val reveal = listOf(
                OracleClient.Point(view.tile, view.tile, Buttons.LEFT_BUTTON),
                OracleClient.Point(view.tile, view.tile, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                start,
                reveal,
                width = view.viewWidth,
                height = view.viewHeight,
            )
            assertEquals("unavailable reveal keeps the save", start, expected.state.state)

            val unavailable = Midend.fromSave(BlackBox, SgtpSave.parse(start))
            val unavailableBefore = unavailable.state
            assertEquals(
                "unavailable reveal is unused",
                KeyOutcome.Unused,
                unavailable.processKey(view.tile, view.tile, Buttons.LEFT_BUTTON, view.tile),
            )
            assertEquals("unavailable reveal adds no state", 1, unavailable.stateCount)
            assertEquals("unavailable reveal keeps the board", unavailableBefore, unavailable.state)
            assertSame("unavailable reveal", expected.state, unavailable)
        }

    @Test
    fun `vacuous clicks change nothing and the adjacent real click acts`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", PARAMS, SEED).state
            // The letterboxed viewport, so the origin is not zero and a
            // coordinate mistake cannot cancel out.
            val view = View(411, 702, 51, 1, 147)

            fun at(x: Int, y: Int, button: Int = Buttons.LEFT_BUTTON) =
                OracleClient.Point(x, y, button)

            // Viewport coordinates of a game point, for the positive
            // control and for the corners.
            fun game(x: Int, y: Int, button: Int = Buttons.LEFT_BUTTON) =
                at(view.originX + x, view.originY + y, button)

            val corners = listOf(
                "corner (0,h+1)" to game(view.tile, view.tile * 7),
                "corner (w+1,0)" to game(view.tile * 7, view.tile),
                "corner (w+1,h+1)" to game(view.tile * 7, view.tile * 7),
            )
            val cases = listOf(
                // Above the board origin: game y is -47 at this viewport.
                "letterbox" to listOf(at(200, 100), at(200, 100, Buttons.LEFT_RELEASE)),
                // Game x maps to w+2 = 7, the frame band outside the grid.
                "frame band" to listOf(
                    game(395, 200),
                    game(395, 200, Buttons.LEFT_RELEASE),
                ),
                // The tap just left of tile 0: FROMDRAW(24) is -1.
                "one pixel outside a tile" to listOf(
                    game(24, 200),
                    game(24, 200, Buttons.LEFT_RELEASE),
                ),
            ) + corners.map { (label, centre) ->
                label to listOf(
                    centre,
                    at(centre.x, centre.y, Buttons.LEFT_RELEASE),
                )
            }

            for ((label, points) in cases) {
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = view.viewWidth,
                    height = view.viewHeight,
                )
                // C does nothing: the save, the moves, and the history
                // are unchanged.
                assertEquals(label, save, expected.state.state)
                assertEquals(label, emptyList<String>(), movesOf(expected.state.state))

                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
                val before = midend.state
                for (event in points) {
                    midend.processKey(
                        event.x - view.originX,
                        event.y - view.originY,
                        event.button,
                        view.tile,
                    )
                }
                assertEquals(label, 1, midend.stateCount)
                assertEquals(label, before, midend.state)
                assertSame(label, expected.state, midend)
            }

            // The positive control: the same harness on the arena cell
            // next to the letterbox and frame cases does act.
            val real = listOf(
                game(2 * view.tile, 2 * view.tile),
                game(2 * view.tile, 2 * view.tile, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                save,
                real,
                width = view.viewWidth,
                height = view.viewHeight,
            )
            assertEquals("T1,1", movesOf(expected.state.state).single())
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            val before = midend.state
            for (event in real) {
                midend.processKey(
                    event.x - view.originX,
                    event.y - view.originY,
                    event.button,
                    view.tile,
                )
            }
            assertEquals("the positive control acts", 2, midend.stateCount)
            assertNotEquals("the positive control changes the board", before, midend.state)
            assertSame("the positive control", expected.state, midend)
        }

    @Test
    fun `a drag does nothing after the press acts`() = OracleClient().use { oracle ->
        val save = oracle.newGame("blackbox", PARAMS, SEED).state
        val view = View(504, 504, 63, 0, 0)
        fun centre(gx: Int, gy: Int) = OracleClient.Point(
            view.originX + view.tile * (gx + 1),
            view.originY + view.tile * (gy + 1),
            Buttons.LEFT_BUTTON,
        )

        val press = centre(1, 1)
        val over = centre(2, 2)
        val points = listOf(
            press,
            OracleClient.Point(over.x, over.y, Buttons.LEFT_DRAG),
            OracleClient.Point(over.x, over.y, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(
            save,
            points,
            width = view.viewWidth,
            height = view.viewHeight,
        )
        assertEquals(
            "a drag adds no move of its own",
            listOf("T1,1"),
            movesOf(expected.state.state),
        )

        val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
        val before = midend.state
        assertEquals(
            "drag press",
            KeyOutcome.SomeEffect,
            midend.processKey(press.x, press.y, Buttons.LEFT_BUTTON, view.tile),
        )
        val afterPress = midend.state
        assertEquals(
            "drag",
            KeyOutcome.Unused,
            midend.processKey(over.x, over.y, Buttons.LEFT_DRAG, view.tile),
        )
        assertEquals("a drag adds no state", 2, midend.stateCount)
        assertEquals("a drag keeps the board", afterPress, midend.state)
        assertEquals(
            "drag release",
            KeyOutcome.SomeEffect,
            midend.processKey(over.x, over.y, Buttons.LEFT_RELEASE, view.tile),
        )
        assertEquals("a drag and its release add no state", 2, midend.stateCount)
        assertEquals("a drag to (2,2) leaves that cell alone", 0, guessAt(afterPress, 2, 2))
        assertNotEquals("the press acts", before, midend.state)
        assertSame("a drag", expected.state, midend)
    }

    @Test
    fun `a modified cursor key and a keypad digit are unused`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", PARAMS, SEED).state
            val view = View(504, 504, 63, 0, 0)
            val keys = listOf(
                "SHFT+RIGHT" to (Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
                "CTRL+RIGHT" to (Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL),
                "CTRL+SHFT+RIGHT" to
                    (Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL or Buttons.MOD_SHFT),
                "keypad 1" to ('1'.code or Buttons.MOD_NUM_KEYPAD),
            )

            for ((label, key) in keys) {
                val points = listOf(OracleClient.Point(0, 0, key))
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = view.viewWidth,
                    height = view.viewHeight,
                    recordTrace = true,
                )
                assertEquals(label, save, expected.state.state)

                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
                val before = midend.state
                val cursorBefore = (midend.ui as BlackBox.Ui).curX
                assertEquals(
                    label,
                    KeyOutcome.Unused,
                    midend.processKey(0, 0, key, view.tile),
                )
                assertEquals("$label adds no state", 1, midend.stateCount)
                assertEquals("$label keeps the board", before, midend.state)
                assertEquals(
                    "$label moves no cursor",
                    cursorBefore,
                    (midend.ui as BlackBox.Ui).curX,
                )
                assertSame(label, expected.state, midend)
                assertTrace(
                    "$label trace",
                    requireNotNull(expected.trace),
                    KotlinTraceSink.stateTrace(midend, view.tile),
                )
            }

            // The positive control: an unmodified cursor key is a UI
            // update, so the same harness can see the difference.
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            val before = midend.state
            assertEquals(
                "unmodified cursor key",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_RIGHT, view.tile),
            )
            assertEquals("a cursor move adds no state", 1, midend.stateCount)
            assertEquals("a cursor move keeps the board", before, midend.state)
            assertEquals(2, (midend.ui as BlackBox.Ui).curX)
        }

    @Test
    fun `the cursor keys and a keyboard toggle match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("blackbox", PARAMS, SEED).state

            // Moving the cursor is UI only: no MOVE, no extra state.
            val moves = listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_UP,
            )
            val moved = oracle.pointsSized(
                save,
                moves.map { OracleClient.Point(0, 0, it) },
                width = TILE * 8,
                height = TILE * 8,
                recordTrace = true,
            )
            assertEquals("cursor keys add no move", emptyList<String>(), movesOf(moved.state.state))
            val moveMidend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            for (key in moves) {
                assertEquals(
                    "cursor key $key",
                    KeyOutcome.SomeEffect,
                    moveMidend.processKey(0, 0, key, TILE),
                )
            }
            assertEquals("cursor keys add no state", 1, moveMidend.stateCount)
            assertSame("cursor keys", moved.state, moveMidend)
            assertTrace(
                "cursor trace",
                requireNotNull(moved.trace),
                KotlinTraceSink.stateTrace(moveMidend, TILE),
            )

            // The first select shows the cursor, and the second toggles
            // the ball under it. That is a real move.
            val toggle = listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            )
            val toggled = oracle.pointsSized(
                save,
                toggle.map { OracleClient.Point(0, 0, it) },
                width = TILE * 8,
                height = TILE * 8,
                recordTrace = true,
            )
            assertEquals("keyboard toggle", listOf("T2,1"), movesOf(toggled.state.state))
            val toggleMidend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            val before = toggleMidend.state
            for (key in toggle) {
                assertEquals(
                    "toggle key $key",
                    KeyOutcome.SomeEffect,
                    toggleMidend.processKey(0, 0, key, TILE),
                )
            }
            assertNotEquals("keyboard toggle changes the board", before, toggleMidend.state)
            assertEquals("keyboard toggle adds one state", 2, toggleMidend.stateCount)
            assertSame("keyboard toggle", toggled.state, toggleMidend)
            assertTrace(
                "keyboard toggle trace",
                requireNotNull(toggled.trace),
                KotlinTraceSink.stateTrace(toggleMidend, TILE),
            )
        }

    @Test
    fun `requestKeys matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("blackbox", PARAMS, SEED).state
        val expected = oracle.requestKeys(save)
        val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
        val actual = BlackBox.requestKeys(midend.params)

        assertEquals(3, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertTrue("oracle labels", expected.labels.isEmpty())
        assertTrue("Kotlin labels", actual.labels.isEmpty())
    }

    private fun assertToggle(
        what: String,
        save: String,
        points: List<OracleClient.Point>,
        view: View,
        expected: OracleClient.PointResult,
        move: String,
        expectedCount: Int = 2,
    ) {
        val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
        val before = midend.state
        for (point in points) {
            val expectedOutcome = if (point.button == Buttons.RIGHT_RELEASE) {
                KeyOutcome.Unused
            } else {
                KeyOutcome.SomeEffect
            }
            assertEquals(
                "$what outcome",
                expectedOutcome,
                midend.processKey(
                    point.x - view.originX,
                    point.y - view.originY,
                    point.button,
                    view.tile,
                ),
            )
        }
        assertNotEquals("$what changes the board", before, midend.state)
        assertEquals("$what adds one state", expectedCount, midend.stateCount)
        assertSame(what, expected.state, midend)
        assertEquals(
            "$what status",
            expected.state.status,
            midend.status(),
        )
        if (expected.trace != null) {
            assertEquals(
                "$what status text",
                statusOf(expected.trace),
                midend.statusText(),
            )
            assertTrace(
                "$what trace",
                expected.trace,
                KotlinTraceSink.stateTrace(midend, view.tile),
            )
        }
        assertEquals("$what oracle move", move, movesOf(expected.state.state).last())
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
        assertEquals("UI $what", uiOf(expected.state), BlackBox.encodeUi(actual.ui))
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

    private fun movesOf(state: String): List<String> =
        SgtpSave.parse(state).values("MOVE")

    private fun guessAt(state: Game.State, x: Int, y: Int): Int =
        (state as BlackBox.State).gridAt(x, y) and BlackBox.BALL_GUESS

    private fun uiOf(state: String): String =
        requireNotNull(SgtpSave.parse(state).value("UI")) { "save has no UI record" }

    private fun statusOf(trace: JsonValue.Obj): String =
        (trace.field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .filter { it.string("op") == "status" }
            .also { assertEquals("one status line", 1, it.size) }
            .single()
            .string("text")

    private fun historySave(records: List<Pair<String, String>>): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", BlackBox.name),
            SaveRecord("PARAMS", PARAMS),
            SaveRecord("CPARAMS", PARAMS),
            SaveRecord("DESC", DESC),
            SaveRecord("UI", "E0"),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", (1 + records.size).toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "w5h5m3M3"
        const val DESC = "7f1b9a74d74b19f6"
        const val TILE = 32
    }
}
