package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: lightup.
 *
 * New games, text, status, the move grammar, the two click cycles, the
 * key rules, completion, the solver, undo and redo, and the vacuous
 * actions are compared with the oracle.
 *
 * `interpret_move` keeps modifiers, so a modified arrow is unused while
 * `SHFT` on a select is stripped by the midend. The solve tries the
 * current board first and the decoded first state second, so a wrong
 * light must produce an `L` entry that removes it.
 */
class LightupGameTest {

    @Test(timeout = 120_000)
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        for (preset in Lightup.presets()) {
            for (seed in listOf("12345", "1", "seed", "")) {
                val expected = oracle.newGame("lightup", preset.params, seed)
                val actual = Midend.newGame(Lightup, preset.params, seed)
                assertSame("lightup '${preset.params}' seed=$seed", expected, actual)
                assertEquals(
                    "text '${preset.params}' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals("status text", "", actual.statusText())
                assertFalse("no status bar", Lightup.wantsStatusBar)
                assertNull("no AUXINFO", actual.auxInfo)
                assertEquals(
                    "no AUXINFO record",
                    null,
                    SgtpSave.parse(actual.serialise().serialise()).value("AUXINFO"),
                )
            }
        }

        // The default board as the source comment prints it: 15 lines of
        // 16 characters, a clue and a light look different.
        val seven = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        assertEquals(TEXT_7X7, seven.textFormat())
        assertEquals(256, Lightup.computeSize(Lightup.decodeParams("7x7"), 32).width)
    }

    @Test
    fun `real clicks cycle at every tile size and in the letterbox`() = OracleClient().use { oracle ->
        val start = oracle.newGame("lightup", "7x7b20s3d0", SEED)
        val cases = listOf(
            Triple(48, 384, 384),
            Triple(63, 503, 503),
            Triple(82, 656, 656),
            Triple(100, 800, 800),
            Triple(51, 411, 702),
        )
        for ((tile, viewW, viewH) in cases) {
            val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            val geometry = midend.geometryFor(viewW, viewH)
            assertEquals("tile for ${viewW}x$viewH", tile, geometry.tileSize)
            val gameX = tile + tile / 2
            val gameY = tile / 2
            val viewX = geometry.originX + gameX
            val viewY = geometry.originY + gameY

            var oracleState = start.state
            val buttons = listOf(
                Buttons.LEFT_BUTTON,
                Buttons.LEFT_BUTTON,
                Buttons.LEFT_BUTTON,
                Buttons.RIGHT_BUTTON,
                Buttons.RIGHT_BUTTON,
                Buttons.RIGHT_BUTTON,
            )
            for ((step, button) in buttons.withIndex()) {
                val expected = oracle.pointsSized(
                    oracleState,
                    listOf(OracleClient.Point(viewX, viewY, button)),
                    width = viewW,
                    height = viewH,
                ).state
                assertEquals(
                    "click $step at tile $tile outcome",
                    KeyOutcome.SomeEffect,
                    midend.processKey(gameX, gameY, button, tile),
                )
                oracleState = expected.state
                assertEquals("click $step at tile $tile id", expected.id, midend.id())
                assertEquals("click $step at tile $tile save", expected.state, midend.serialise().serialise())
                assertEquals("click $step at tile $tile states", step + 2, midend.stateCount)
            }

            // A click on the black cell (0,0) adds no state.
            val before = midend.stateCount
            assertEquals(
                KeyOutcome.NoEffect,
                midend.processKey(tile / 2, tile / 2, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals("black click adds no state", before, midend.stateCount)
        }
    }

    @Test
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val params = "7x7b20s0d0"
        val desc = "zw" // 26 + 23 white cells

        val accepted = listOf(
            "L0,0",
            "I0,0",
            "S",
            "S;S",
            "S;L1,1",
            "L1,1;L2,2",
            "L1,1;",
            "S;",
            "L+1,+2",
            "L 1, 2",
            "L1,2;I2,1",
            "L4294967296,0",
            "L1,4294967296",
        )
        for (move in accepted) {
            val text = saveText(params, desc, listOf(move))
            val expected = oracle.id(text)
            val actual = Midend.fromSave(Lightup, SgtpSave.parse(text))
            assertEquals("'$move' id", expected.id, actual.id())
            assertEquals("'$move' status", expected.status, actual.status())
            assertEquals("'$move' text", oracle.text(text), actual.textFormat())
            assertEquals(
                "'$move' move text is stored verbatim",
                listOf(move),
                actual.serialise().values("MOVE"),
            )
        }

        val rejected = listOf(
            "", "L9,9", "L-1,0", "L1", "L1,", "L1,1;;", "X1,1",
            "L1,1junk", "L1,1 ", "L1 ,1", "L1,1,", "L1,,1", ";L1,1",
            "L99999999999999999999,0",
        )
        for (move in rejected) {
            val text = saveText(params, desc, listOf(move))
            val oracleError = try {
                oracle.id(text)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C rejects '$move'", oracleError)
            val kotlinError = try {
                Midend.fromSave(Lightup, SgtpSave.parse(text))
                null
            } catch (e: RuntimeException) {
                e.message
            }
            assertNotNull("Kotlin rejects '$move'", kotlinError)
        }

        // A black cell is rejected by `execute_move`, even with a valid
        // coordinate. Cell (0,0) of the default board is black.
        val default = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        assertNull(Lightup.executeMove(default.state, "L0,0"))
        assertNull(Lightup.executeMove(default.state, "I0,0"))
    }

    @Test
    fun `execute move toggles light and mark exactly`() {
        val midend = Midend.newGame(Lightup, "7x7b20s0d0", "1")
        val start = midend.state as Lightup.State

        fun lights(state: Lightup.State, x: Int, y: Int): Int = state.lights[y * state.width + x]
        fun flags(state: Lightup.State, x: Int, y: Int): Int = state.flags[y * state.width + x]

        // L on empty: a light and the illumination count rises.
        val lit = Lightup.executeMove(start, "L1,1") as Lightup.State
        assertTrue(flags(lit, 1, 1) and 16 != 0)
        assertEquals(1, lights(lit, 1, 1))
        assertEquals(1, lights(lit, 2, 1))
        assertEquals(1, lit.nLights)

        // L on a light: the light is removed.
        val cleared = Lightup.executeMove(lit, "L1,1") as Lightup.State
        assertTrue(flags(cleared, 1, 1) and 16 == 0)
        assertEquals(0, lights(cleared, 1, 1))
        assertEquals(0, cleared.nLights)

        // I on empty: an impossible mark.
        val marked = Lightup.executeMove(start, "I1,1") as Lightup.State
        assertTrue(flags(marked, 1, 1) and 8 != 0)
        assertEquals(0, marked.nLights)

        // I on a mark: the mark is removed.
        val unmarked = Lightup.executeMove(marked, "I1,1") as Lightup.State
        assertTrue(flags(unmarked, 1, 1) and 8 == 0)

        // L on a mark clears the mark and places a light.
        val fromMark = Lightup.executeMove(marked, "L1,1") as Lightup.State
        assertTrue(flags(fromMark, 1, 1) and 8 == 0)
        assertTrue(flags(fromMark, 1, 1) and 16 != 0)

        // I on a light clears the light and toggles the mark.
        val fromLight = Lightup.executeMove(lit, "I1,1") as Lightup.State
        assertTrue(flags(fromLight, 1, 1) and 16 == 0)
        assertTrue(flags(fromLight, 1, 1) and 8 != 0)

        // S sets `used_solve` and changes nothing else.
        val solvedFlag = Lightup.executeMove(start, "S") as Lightup.State
        assertTrue(solvedFlag.usedSolve)
        assertTrue(start.lights.contentEquals(solvedFlag.lights))
        assertTrue(start.flags.contentEquals(solvedFlag.flags))
    }

    @Test
    fun `the left and right click cycles match the oracle`() = OracleClient().use { oracle ->
        val params = "7x7b20s3d0"
        val start = oracle.newGame("lightup", params, "12345")
        val tile = 32
        val view = 8 * tile // exact fit: 7 * 32 + 2 * 16
        val cellX = 1 * tile + tile / 2
        val cellY = 0 * tile + tile / 2

        // The left cycle: empty -> light -> impossible -> empty.
        val leftPoints = listOf(
            OracleClient.Point(cellX, cellY, Buttons.LEFT_BUTTON),
            OracleClient.Point(cellX, cellY, Buttons.LEFT_BUTTON),
            OracleClient.Point(cellX, cellY, Buttons.LEFT_BUTTON),
        )
        // The right cycle: empty -> impossible -> light -> empty.
        val rightPoints = listOf(
            OracleClient.Point(cellX, cellY, Buttons.RIGHT_BUTTON),
            OracleClient.Point(cellX, cellY, Buttons.RIGHT_BUTTON),
            OracleClient.Point(cellX, cellY, Buttons.RIGHT_BUTTON),
        )

        for ((label, points) in listOf("left" to leftPoints, "right" to rightPoints)) {
            var oracleState = start.state
            val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            for ((step, point) in points.withIndex()) {
                val expected = oracle.pointsSized(
                    oracleState,
                    listOf(point),
                    width = view,
                    height = view,
                ).state
                assertEquals(
                    "$label step $step outcome",
                    KeyOutcome.SomeEffect,
                    midend.processKey(cellX, cellY, point.button, tile),
                )
                oracleState = expected.state
                assertEquals("$label step $step id", expected.id, midend.id())
                assertEquals("$label step $step save", expected.state, midend.serialise().serialise())
                assertEquals("$label step $step state count", step + 2, midend.stateCount)
            }
        }
    }

    @Test
    fun `the select keys and i produce the same cycles as a click`() = OracleClient().use { oracle ->
        val params = "7x7b20s3d0"
        val start = oracle.newGame("lightup", params, SEED)
        val tile = 32

        // Each sequence starts with a right arrow, which shows the cursor
        // on (1,0); every later select acts on that cell. C keeps the
        // game_ui only inside one `keys` request, so the expected state
        // is a prefix replay, not a chain of single keys.
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, 'i'.code, 'I'.code),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT2, 'i'.code, 'I'.code),
        )

        for (sequence in sequences) {
            val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            for (index in sequence.indices) {
                val prefix = sequence.subList(0, index + 1)
                val expected = oracle.keys(start.state, prefix)
                val outcome = midend.processKey(0, 0, sequence[index], tile)
                assertEquals(
                    "sequence $sequence key $index id",
                    expected.id,
                    midend.id(),
                )
                assertEquals(
                    "sequence $sequence key $index save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertTrue("outcome is an effect", outcome != KeyOutcome.Unused)
            }
        }

        // Enter, Return, and Space are mapped by the midend. Space is the
        // second select and cycles the other way.
        val enter = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
        enter.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
        val space = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
        space.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
        assertEquals(
            KeyOutcome.SomeEffect,
            enter.processKey(0, 0, '\n'.code, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            space.processKey(0, 0, ' '.code, tile),
        )
        assertTrue((enter.state as Lightup.State).flags[1] and 16 != 0)
        assertTrue((space.state as Lightup.State).flags[1] and 8 != 0)
    }

    @Test
    fun `modifiers follow the midend rules and are not stripped by the game`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("lightup", "7x7b20s3d0", SEED)
            val tile = 32

            val shiftRight = Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT
            val ctrlRight = Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT
            val shiftSelect = Buttons.MOD_SHFT or Buttons.CURSOR_SELECT
            val ctrlSelect = Buttons.MOD_CTRL or Buttons.CURSOR_SELECT

            // A modified arrow is unused, so the cursor stays hidden and
            // the following select only makes it visible.
            for (modified in listOf(shiftRight, ctrlRight)) {
                val sequence = listOf(modified, Buttons.CURSOR_SELECT)
                val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
                for (index in sequence.indices) {
                    val expected = oracle.keys(start.state, sequence.subList(0, index + 1))
                    midend.processKey(0, 0, sequence[index], tile)
                    assertEquals("key ${sequence[index]} id", expected.id, midend.id())
                    assertEquals(
                        "key ${sequence[index]} save",
                        expected.state,
                        midend.serialise().serialise(),
                    )
                }
                assertEquals("no state for a modified arrow", 1, midend.stateCount)
            }

            // SHFT on a select is stripped by the midend, so it acts. An
            // arrow first puts the cursor on the white cell (1,0); (0,0)
            // is black and a select there is a no-effect.
            val shiftSequence = listOf(
                Buttons.CURSOR_RIGHT,
                shiftSelect,
                shiftSelect,
                shiftSelect,
            )
            val shift = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            for (index in shiftSequence.indices) {
                val prefix = shiftSequence.subList(0, index + 1)
                val expected = oracle.keys(start.state, prefix)
                shift.processKey(0, 0, shiftSequence[index], tile)
                assertEquals("shift select id", expected.id, shift.id())
            }
            assertEquals("shift select adds three moves", 4, shift.stateCount)

            // CTRL on a select is rejected by the midend before the game.
            val ctrl = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            ctrl.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
            assertFalse(
                "CTRL+select is unused",
                ctrl.processKey(0, 0, ctrlSelect, tile) == KeyOutcome.SomeEffect,
            )
            assertEquals("CTRL+select adds no state", 1, ctrl.stateCount)

            // A plain light click and a modified cursor key do not mix.
            val mixed = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            assertEquals(
                KeyOutcome.Unused,
                mixed.processKey(0, 0, shiftRight, tile),
            )
        }

    @Test
    fun `a hidden cursor select is a no-effect that shows the cursor`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val ui = midend.ui as Lightup.Ui
        assertFalse(ui.curVisible)

        // Put the cursor on the white cell (1,0) while it is visible,
        // then hide it as a click would.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32),
        )
        assertTrue(ui.curVisible)
        midend.setCursorVisible(false)

        // A select with a hidden cursor is a no-effect, but C sets the
        // visibility flag before it returns.
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, 32),
        )
        assertTrue("the cursor appears", ui.curVisible)
        assertEquals("no move", 1, midend.stateCount)

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, 32),
        )
        assertEquals("the second select moves", 2, midend.stateCount)
    }

    @Test
    fun `clicks outside the grid are vacuous and follow the cursor rule`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("lightup", "7x7b20s3d0", SEED)
            val tile = 32
            val view = 8 * tile
            val blackX = 16
            val blackY = 16
            val ring = listOf(
                OracleClient.Point(4, 16, Buttons.LEFT_BUTTON),
                OracleClient.Point(250, 16, Buttons.LEFT_BUTTON),
                OracleClient.Point(16, 4, Buttons.LEFT_BUTTON),
                OracleClient.Point(16, 250, Buttons.LEFT_BUTTON),
                OracleClient.Point(blackX, blackY, Buttons.LEFT_BUTTON),
                OracleClient.Point(blackX, blackY, Buttons.RIGHT_BUTTON),
            )

            for (point in ring) {
                val midend = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
                val expected = oracle.pointsSized(
                    start.state,
                    listOf(point),
                    width = view,
                    height = view,
                ).state
                assertEquals(
                    "point ${point.x},${point.y} outcome",
                    KeyOutcome.NoEffect,
                    midend.processKey(point.x, point.y, point.button, tile),
                )
                assertEquals("point ${point.x},${point.y} id", expected.id, midend.id())
                assertEquals("no state", 1, midend.stateCount)
            }

            // With a visible cursor the same click is a UI update.
            val visible = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            visible.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
            assertEquals(
                KeyOutcome.SomeEffect,
                visible.processKey(4, 16, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals("still no state", 1, visible.stateCount)
            assertFalse((visible.ui as Lightup.Ui).curVisible)

            // A middle button, a lone drag, and a lone release are unused.
            val unused = Midend.fromSave(Lightup, SgtpSave.parse(start.state))
            assertEquals(
                KeyOutcome.Unused,
                unused.processKey(16, 16, Buttons.MIDDLE_BUTTON, tile),
            )
            assertEquals(
                KeyOutcome.Unused,
                unused.processKey(16, 16, Buttons.LEFT_DRAG, tile),
            )
            assertEquals(
                KeyOutcome.Unused,
                unused.processKey(16, 16, Buttons.LEFT_RELEASE, tile),
            )
            assertEquals("no state", 1, unused.stateCount)
        }

    @Test
    fun `an arrow at an edge is a no-effect with a visible cursor`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_LEFT, 32),
        )
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, Buttons.CURSOR_LEFT, 32),
        )
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `completion is sticky and needs no clue`() = OracleClient().use { oracle ->
        // A clue-less 3x3 board is won by three lights on the diagonal
        // opposite the one they do not overlap: (2,0), (1,1), (0,2).
        val midend = Midend.fromGameId(Lightup, "3x3:i")
        fun click(x: Int, y: Int) = midend.processKey(x * 32 + 16, y * 32 + 16, Buttons.LEFT_BUTTON, 32)
        assertEquals(KeyOutcome.SomeEffect, click(2, 0))
        assertEquals(KeyOutcome.SomeEffect, click(1, 1))
        assertEquals("not won after two lights", 0, midend.status())
        assertEquals(KeyOutcome.SomeEffect, click(0, 2))
        assertEquals("won", 1, midend.status())
        assertTrue("the win raises the event", midend.takeCompletedEvent())
        assertEquals("won text", oracle.text(midend.serialise().serialise()), midend.textFormat())

        // A left click on a light emits `I`, so it clears the light and
        // marks the cell. The status stays 1.
        assertEquals(KeyOutcome.SomeEffect, click(2, 0))
        assertEquals("post-win status", 1, midend.status())
        assertFalse("a post-win move is not a new completion", midend.takeCompletedEvent())
        assertEquals("post-win text", oracle.text(midend.serialise().serialise()), midend.textFormat())
        assertTrue("the text marks the cell", midend.textFormat().contains('x'))

        // A board with one cell unlit has status 0.
        val open = Midend.fromGameId(Lightup, "3x3:i")
        assertEquals(KeyOutcome.SomeEffect, open.processKey(80, 16, Buttons.LEFT_BUTTON, 32))
        assertEquals(KeyOutcome.SomeEffect, open.processKey(48, 48, Buttons.LEFT_BUTTON, 32))
        assertEquals(0, open.status())
    }

    @Test
    fun `completion events follow undo redo and restart`() {
        // A live game owns the history and raises the completion event.
        val won = Midend.fromGameId(Lightup, "3x3:i")
        won.processKey(80, 16, Buttons.LEFT_BUTTON, 32)
        won.processKey(48, 48, Buttons.LEFT_BUTTON, 32)
        won.processKey(16, 80, Buttons.LEFT_BUTTON, 32)
        assertEquals(1, won.status())
        assertTrue("the win raises the event", won.takeCompletedEvent())

        assertTrue(won.undo())
        assertFalse("undo from the win raises nothing", won.takeCompletedEvent())
        assertEquals(0, won.status())
        assertTrue(won.redo())
        assertTrue("redo into the win raises again", won.takeCompletedEvent())
        assertEquals(1, won.status())

        // Restart from a played position is not a completion.
        assertTrue(won.restart())
        assertFalse(won.takeCompletedEvent())
        assertEquals(0, won.status())

        // A real move after the win keeps the sticky status.
        won.processKey(80, 16, Buttons.LEFT_BUTTON, 32)
        won.processKey(48, 48, Buttons.LEFT_BUTTON, 32)
        won.processKey(16, 80, Buttons.LEFT_BUTTON, 32)
        won.takeCompletedEvent()
        won.processKey(80, 16, Buttons.LEFT_BUTTON, 32)
        assertEquals(1, won.status())
        assertFalse("a post-win move is not a completion", won.takeCompletedEvent())
    }

    @Test
    fun `the solver matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (preset in Lightup.presets()) {
            val start = oracle.newGame("lightup", preset.params, SEED)
            val expected = oracle.solve(start.state)
            val expectedMove = SgtpSave.parse(expected.state).value("SOLVE")
            assertNotNull("C solved ${preset.params}", expectedMove)
            assertEquals(
                "solve save for ${preset.params}",
                expected.state,
                Midend.fromSave(Lightup, SgtpSave.parse(start.state)).let {
                    assertTrue("Kotlin solved ${preset.params}", it.solve())
                    it.serialise().serialise()
                },
            )

            val solved = Lightup.solve(
                Midend.fromSave(Lightup, SgtpSave.parse(start.state)).state,
            )
            assertEquals("SOLVE ${preset.params}", expectedMove, solved)

            val lights = expectedMove!!.split(';').count { it.startsWith("L") }
            val marks = expectedMove.split(';').count { it.startsWith("I") }
            assertEquals(
                "L count ${preset.params}",
                EXPECTED_LIGHTS.getValue(preset.params),
                lights,
            )
            assertEquals(
                "I count ${preset.params}",
                EXPECTED_MARKS.getValue(preset.params),
                marks,
            )
            assertTrue("some lights at ${preset.params}", lights > 0)
            assertTrue(
                "the answer is column-major",
                columnMajor(solved!!),
            )
        }
    }

    @Test
    fun `a solve after a move uses the current board first and the first board second`() =
        OracleClient().use { oracle ->
            // A wrong light is removed by the fallback to the initial
            // board: the answer carries an L entry for (1,0).
            val litSave = saveText("7x7b20s3d0", DESC_7X7, listOf("L1,0"), seed = SEED)
            val litExpected = oracle.solve(litSave)
            val litMidend = Midend.fromSave(Lightup, SgtpSave.parse(litSave))
            assertTrue(litMidend.solve())
            assertEquals("solve after a light", litExpected.state, litMidend.serialise().serialise())
            val litMove = SgtpSave.parse(litExpected.state).value("SOLVE")!!
            assertTrue("the wrong light is turned off", litMove.contains(";L1,0"))

            // Two marks force the fallback: (0,3) is a solution light, so
            // the current board fails, and (1,0) is not a light in the
            // initial answer, so the diff carries an I entry.
            val markedSave = saveText(
                "7x7b20s3d0",
                DESC_7X7,
                listOf("I0,3", "I1,0"),
                seed = SEED,
            )
            val markedExpected = oracle.solve(markedSave)
            val markedMidend = Midend.fromSave(Lightup, SgtpSave.parse(markedSave))
            assertTrue(markedMidend.solve())
            assertEquals("solve after marks", markedExpected.state, markedMidend.serialise().serialise())
            val markedMove = SgtpSave.parse(markedExpected.state).value("SOLVE")!!
            assertTrue("the player's mark is cleared", markedMove.contains(";I1,0"))
            assertTrue("the solution light is placed", markedMove.contains(";L0,3"))

            // A completed board solves to `S` alone.
            val won = saveText("3x3b20s0d0", "i", listOf("L2,0", "L1,1", "L0,2"))
            assertEquals(1, oracle.id(won).status)
            assertEquals("S", oracle.solve(won).let { SgtpSave.parse(it.state).value("SOLVE") })
            assertEquals(
                "S",
                Lightup.solve(Midend.fromSave(Lightup, SgtpSave.parse(won)).state),
            )

            // An insoluble hand-written description has no answer.
            val impossible = saveText("3x3b20s0d0", "4h", emptyList())
            assertNotNull(
                "the oracle may load the description",
                oracle.id(impossible),
            )
            val error = try {
                oracle.solve(impossible)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("the oracle has no solution", error)
            assertNull(
                "the port has no answer",
                Lightup.solve(Midend.fromSave(Lightup, SgtpSave.parse(impossible)).state),
            )
        }

    @Test
    fun `aux info is ignored by the solve`() = OracleClient().use { oracle ->
        val start = oracle.newGame("lightup", "7x7b20s3d0", SEED)
        val desc = SgtpSave.parse(start.state).value("DESC")!!
        // A real AUXINFO value from a game that generates one; lightup
        // never writes this record.
        val foreign = oracle.newGame("netslide", "3x3b1", SEED)
        val aux = SgtpSave.parse(foreign.state).value("AUXINFO")!!
        val text = saveText("7x7b20s3d0", desc, emptyList(), auxInfo = aux, seed = SEED)
        val midend = Midend.fromSave(Lightup, SgtpSave.parse(text))
        assertEquals("AUXINFO round-trips", aux, SgtpSave.parse(midend.serialise().serialise()).value("AUXINFO"))
        val expected = oracle.solve(text)
        assertTrue(midend.solve())
        assertEquals("solve with AUXINFO", expected.state, midend.serialise().serialise())
    }

    @Test
    fun `requested keys and the current key labels match the rules`() = OracleClient().use { oracle ->
        for (preset in Lightup.presets()) {
            val game = oracle.newGame("lightup", preset.params, SEED)
            val expected = oracle.requestKeys(game.state)
            val actual = Lightup.requestKeys(Lightup.decodeParams(preset.params))
            assertEquals("arrow mode for '${preset.params}'", 3, expected.arrowMode)
            assertEquals("Kotlin arrow mode", ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
            assertEquals("C labels", emptyList<OracleClient.KeyLabel>(), expected.labels)
            assertTrue("no labels", actual.labels.isEmpty())
        }

        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val state = midend.state
        val ui = midend.ui as Lightup.Ui
        fun label(button: Int) = Lightup.currentKeyLabel(ui, state, button)

        // Hidden: no label for either key.
        assertEquals("", label(Buttons.CURSOR_SELECT))
        assertEquals("", label(Buttons.CURSOR_SELECT2))

        ui.curVisible = true
        // A clue cell has no label.
        ui.curX = 0
        ui.curY = 0
        assertEquals("", label(Buttons.CURSOR_SELECT))
        assertEquals("", label(Buttons.CURSOR_SELECT2))

        // An empty white cell.
        ui.curX = 1
        ui.curY = 0
        assertEquals("Light", label(Buttons.CURSOR_SELECT))
        assertEquals("Mark", label(Buttons.CURSOR_SELECT2))

        // A light.
        val lit = Midend.fromSave(
            Lightup,
            SgtpSave.parse(saveText("7x7b20s3d0", DESC_7X7, listOf("L1,0"))),
        )
        val litUi = lit.ui as Lightup.Ui
        litUi.curVisible = true
        litUi.curX = 1
        litUi.curY = 0
        assertEquals("Clear", Lightup.currentKeyLabel(litUi, lit.state, Buttons.CURSOR_SELECT))
        assertEquals("", Lightup.currentKeyLabel(litUi, lit.state, Buttons.CURSOR_SELECT2))

        // An impossible mark.
        val marked = Midend.fromSave(
            Lightup,
            SgtpSave.parse(saveText("7x7b20s3d0", DESC_7X7, listOf("I1,0"))),
        )
        val markedUi = marked.ui as Lightup.Ui
        markedUi.curVisible = true
        markedUi.curX = 1
        markedUi.curY = 0
        assertEquals("", Lightup.currentKeyLabel(markedUi, marked.state, Buttons.CURSOR_SELECT))
        assertEquals("Clear", Lightup.currentKeyLabel(markedUi, marked.state, Buttons.CURSOR_SELECT2))

        // A cursor written outside the grid reads no cell.
        ui.curX = -1
        ui.curY = 0
        assertEquals("", label(Buttons.CURSOR_SELECT))
        ui.curX = 99
        assertEquals("", label(Buttons.CURSOR_SELECT))
        ui.curY = 99
        assertEquals("", label(Buttons.CURSOR_SELECT2))
    }

    @Test
    fun `a real light or mark changes the board, a UI action does not`() {
        val midend = Midend.newGame(Lightup, "7x7b20s3d0", "12345")
        val before = midend.state as Lightup.State
        val beforeLights = before.lights.copyOf()
        val beforeFlags = before.flags.copyOf()

        // An arrow only shows the cursor.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32),
        )
        assertEquals("the arrow adds no state", 1, midend.stateCount)
        val afterArrow = midend.state as Lightup.State
        assertTrue("lights unchanged", beforeLights.contentEquals(afterArrow.lights))
        assertTrue("flags unchanged", beforeFlags.contentEquals(afterArrow.flags))

        // A real click changes the grid and adds one move.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 16, Buttons.LEFT_BUTTON, 32),
        )
        assertEquals("the click adds one state", 2, midend.stateCount)
        val afterClick = midend.state as Lightup.State
        assertFalse("lights changed", beforeLights.contentEquals(afterClick.lights))
        assertFalse("flags changed", beforeFlags.contentEquals(afterClick.flags))

        // A real mark also changes the grid and adds one move.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(80, 16, Buttons.RIGHT_BUTTON, 32),
        )
        assertEquals("the mark adds one state", 3, midend.stateCount)
        val afterMark = midend.state as Lightup.State
        assertFalse("marks changed", afterClick.flags.contentEquals(afterMark.flags))
    }

    @Test
    fun `layout follows the C size formula`() = OracleClient().use { oracle ->
        for (case in listOf(
            Triple("7x7b20s3d0", 411, 702),
            Triple("10x10b20s2d1", 384, 384),
            Triple("14x14b20s2d2", 720, 1280),
            Triple("7x7b20s3d0", 200, 200),
        )) {
            val start = oracle.newGame("lightup", case.first, SEED)
            val layout = oracle.layout(start.state, case.second, case.third)
            val midend = Midend.newGame(Lightup, case.first, SEED)
            val geometry = midend.geometryFor(case.second, case.third)
            assertEquals("width ${case.first}", layout.width, geometry.width)
            assertEquals("height ${case.first}", layout.height, geometry.height)
        }
    }

    private fun columnMajor(move: String): Boolean {
        var previous = -1
        for (part in move.split(';').drop(1)) {
            if (part.isEmpty()) continue
            val (x, y) = part.substring(1).split(',').map { it.toInt() }
            val key = x * 1000 + y
            if (key < previous) return false
            previous = key
        }
        return true
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what save", expected.state, actual.serialise().serialise())
        assertEquals("$what can undo", expected.canUndo, actual.canUndo)
        assertEquals("$what can redo", expected.canRedo, actual.canRedo)
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
        auxInfo: String? = null,
        seed: String? = null,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Lightup.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
        )
        if (seed != null) records += SaveRecord("SEED", seed)
        records += SaveRecord("DESC", desc)
        if (auxInfo != null) records += SaveRecord("AUXINFO", auxInfo)
        records += SaveRecord("NSTATES", (1 + moves.size).toString())
        records += SaveRecord("STATEPOS", (1 + moves.size).toString())
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_7X7 = "Be1j4e3aBeBj1e1"

        /** The solve count per preset at seed `12345`, from the oracle. */
        val EXPECTED_LIGHTS = mapOf(
            "7x7b20s3d0" to 10,
            "7x7b20s3d1" to 9,
            "7x7b20s3d2" to 9,
            "10x10b20s2d0" to 19,
            "10x10b20s2d1" to 16,
            "10x10b20s2d2" to 18,
            "14x14b20s2d0" to 37,
            "14x14b20s2d1" to 30,
            "14x14b20s2d2" to 30,
        )

        val EXPECTED_MARKS = mapOf(
            "7x7b20s3d0" to 0,
            "7x7b20s3d1" to 3,
            "7x7b20s3d2" to 24,
            "10x10b20s2d0" to 7,
            "10x10b20s2d1" to 34,
            "10x10b20s2d2" to 21,
            "14x14b20s2d0" to 27,
            "14x14b20s2d1" to 44,
            "14x14b20s2d2" to 45,
        )
        val TEXT_7X7 = """
            +-+-+-+-+-+-+-+
            |#| | | | | |1|
            +-+-+-+-+-+-+-+
            | | | | | | | |
            +-+-+-+-+-+-+-+
            | | | |4| | | |
            +-+-+-+-+-+-+-+
            | | |3| |#| | |
            +-+-+-+-+-+-+-+
            | | | |#| | | |
            +-+-+-+-+-+-+-+
            | | | | | | | |
            +-+-+-+-+-+-+-+
            |1| | | | | |1|
            +-+-+-+-+-+-+-+
        """.trimIndent() + "\n"
    }
}
