package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: sixteen.
 *
 * New games, text, status text, moves, the loose move bounds, the cursor
 * modes, the keypad null move, undo/redo, completion, and solve are
 * compared with the oracle.
 *
 * A full-cycle move (`R0,w`, `C0,±h`) changes no tile but still counts as
 * a move, so the move count is asserted separately from the board.
 */
class SixteenGameTest {

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        val paramsList = listOf("3x3", "4x3", "4x4", "5x4", "5x5", "4x4m10", "4x5", "2x2")
        for (params in paramsList) {
            for (seed in listOf("1", "12345", "seed")) {
                val expected = oracle.newGame("sixteen", params, seed)
                val actual = Midend.newGame(Sixteen, params, seed)
                assertSame("sixteen '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text sixteen '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals(
                    "status text sixteen '$params' seed=$seed",
                    traceStatus(oracle, expected.state),
                    actual.statusText(),
                )
            }
        }
    }

    @Test
    fun `requested keys match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.requestKeys(oracle.newGame("sixteen", "4x4", SEED).state)
        val actual = Sixteen.requestKeys(Sixteen.decodeParams("4x4"))

        assertEquals(3, expected.arrowMode)
        assertEquals("arrow mode", ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertTrue("C requests no extra keys", expected.labels.isEmpty())
        assertTrue("Kotlin requests no extra keys", actual.labels.isEmpty())
    }

    @Test
    fun `crafted states match text and status`() = OracleClient().use { oracle ->
        val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val cases = listOf(
            Case("4x4", base, emptyList(), null),
            Case("4x4", base, listOf("R0,1"), null),
            Case("4x4", base, listOf("R0,-1"), null),
            Case("4x4", base, listOf("C0,1"), null),
            Case("4x4", base, listOf("C0,-1"), null),
            Case("4x4", base, listOf("R3,1", "C3,-1"), null),
            // The loose bounds: accepted, no board change, one move each.
            Case("4x4", base, listOf("R0,0"), null),
            Case("4x4", base, listOf("C0,0"), null),
            Case("4x4", base, listOf("R0,4"), null),
            Case("4x4", base, listOf("R0,-4"), null),
            Case("4x4", base, listOf("C0,4"), null),
            Case("4x4", base, listOf("C0,-4"), null),
            // On a tall board `R0,-h` is more than one cycle.
            Case("3x5", "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15", listOf("R0,-5"), null),
            // A solve, a solve plus a move, and two moves after a solve.
            Case("4x4", base, emptyList(), "S"),
            Case("4x4", base, listOf("R0,1"), "S"),
            Case("4x4", base, listOf("R0,1", "C0,1"), "S"),
            // A completion from a real move, with and without a target.
            Case(
                "4x4",
                "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
                listOf("R0,-1"),
                null,
            ),
            Case(
                "4x4m10",
                "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
                listOf("R0,-1"),
                null,
            ),
            Case(
                "4x4m10",
                "4,8,2,16,3,10,6,12,15,7,14,5,13,1,11,9",
                listOf("R0,1"),
                null,
            ),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves, case.solve)
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            val what = "${case.params} ${case.moves} solve=${case.solve}"

            assertSame(what, expected, actual)
            assertEquals("text $what", oracle.text(save), actual.textFormat())
            assertEquals("status text $what", traceStatus(oracle, save), actual.statusText())
            // The move count is one per move string, including a full
            // cycle and a null move, so it comes from the record count.
            assertEquals(
                "move count $what",
                case.moves.size + (if (case.solve != null) 1 else 0),
                (actual.state as Sixteen.State).moveCount,
            )
        }
    }

    @Test
    fun `a click on every frame slot adds one move`() = OracleClient().use { oracle ->
        val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val save = saveText("4x4", base)
        val start = oracle.id(save)
        val probe = Midend.newGame(Sixteen, "4x4", SEED)
        val geometry = probe.geometryFor(VIEW_WIDTH, VIEW_HEIGHT)
        val tile = geometry.tileSize
        assertEquals("the phone viewport picks tile 68", 68, tile)
        assertEquals(1, geometry.originX)
        assertEquals(147, geometry.originY)

        // (view x, view y, button) in the four border bands. The spec
        // table in work/specs/sixteen.md is the source.
        val clicks = listOf(
            Triple(31, 215, Buttons.LEFT_BUTTON),
            Triple(341, 215, Buttons.LEFT_BUTTON),
            Triple(167, 177, Buttons.LEFT_BUTTON),
            Triple(167, 487, Buttons.LEFT_BUTTON),
            Triple(341, 215, Buttons.RIGHT_BUTTON),
            Triple(31, 419, Buttons.RIGHT_BUTTON),
        )
        val expectedMoves = listOf("R0,-1", "R0,1", "C1,-1", "C1,1", "R0,-1", "R3,1")

        for ((index, click) in clicks.withIndex()) {
            val (viewX, viewY, button) = click
            val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            val movesBefore = actual.serialise().values("MOVE").size
            assertEquals(
                "outcome for click $index",
                KeyOutcome.SomeEffect,
                actual.processKey(
                    geometry.toGameX(viewX),
                    geometry.toGameY(viewY),
                    button,
                    tile,
                ),
            )
            assertEquals(
                "one move for click $index at ($viewX,$viewY)",
                movesBefore + 1,
                actual.serialise().values("MOVE").size,
            )
            assertEquals(
                "move record for click $index",
                expectedMoves[index],
                actual.serialise().values("MOVE").single(),
            )

            val expected = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(viewX, viewY, button)),
                VIEW_WIDTH,
                VIEW_HEIGHT,
            )
            assertEquals(
                "MOVE record for click $index",
                SgtpSave.parse(expected.state.state).values("MOVE"),
                actual.serialise().values("MOVE"),
            )
            assertEquals("state for click $index", expected.state.id, actual.id())
        }

        // A click in a cell, on a frame corner, in the letterbox, and
        // outside the widget changes nothing. The neighbouring border
        // clicks above prove that the geometry is not off by a band.
        val inert = listOf(
            Triple(69, 215, Buttons.LEFT_BUTTON),
            Triple(31, 147, Buttons.LEFT_BUTTON),
            Triple(167, 137, Buttons.LEFT_BUTTON),
            Triple(0, 215, Buttons.LEFT_BUTTON),
        )
        for ((index, click) in inert.withIndex()) {
            val (viewX, viewY, button) = click
            val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            assertEquals(
                "a click does not add a move ($index)",
                emptyList<String>(),
                actual.serialise().values("MOVE"),
            )
            val expected = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(viewX, viewY, button)),
                VIEW_WIDTH,
                VIEW_HEIGHT,
            )
            assertEquals(
                "the oracle adds no move ($index)",
                start.state,
                expected.state.state,
            )
            assertEquals(
                KeyOutcome.SomeEffect,
                actual.processKey(
                    geometry.toGameX(viewX),
                    geometry.toGameY(viewY),
                    button,
                    tile,
                ),
            )
            assertEquals(
                "a click in a cell or outside the board is a UI update",
                emptyList<String>(),
                actual.serialise().values("MOVE"),
            )
        }
    }

    @Test
    fun `keyboard moves match the oracle`() = OracleClient().use { oracle ->
        val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val save = saveText("4x4", base)

        val sequences = listOf(
            listOf(Buttons.CURSOR_UP),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_LEFT, Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2),
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_DOWN, Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
        )

        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            for (key in sequence) {
                actual.processKey(0, 0, key, Sixteen.preferredTileSize)
            }
            assertSame("keys $sequence", expected, actual)
        }

        // A keyboard move rotates a line: the first row of the default
        // board becomes `10 15 14 3` after `UP, UP, SELECT`.
        val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
        actual.processKey(0, 0, Buttons.CURSOR_UP, 48)
        actual.processKey(0, 0, Buttons.CURSOR_UP, 48)
        assertEquals(KeyOutcome.SomeEffect, actual.processKey(0, 0, Buttons.CURSOR_SELECT, 48))
        assertEquals("C0,-1", actual.serialise().values("MOVE").single())
        assertEquals("10 15 14  3\n 7 12  5  6\n11  1  8  2\n13  9 16  4\n", actual.textFormat())
    }

    @Test
    fun `cursor modes and the keypad null move match the oracle`() =
        OracleClient().use { oracle ->
            val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
            val save = saveText("4x4", base)
            val pad = Buttons.MOD_NUM_KEYPAD
            val shift = Buttons.MOD_SHFT
            val ctrl = Buttons.MOD_CTRL

            val cases = listOf(
                // Lock tile, then slide: the cursor follows the tile.
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, Buttons.CURSOR_LEFT),
                // Lock position, then slide: the cursor stays.
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2, Buttons.CURSOR_DOWN),
                // Toggle the same lock twice.
                listOf(
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_LEFT,
                ),
                // A keypad digit with no lock changes nothing.
                listOf(Buttons.CURSOR_UP, pad or '8'.code),
                // A keypad digit in a lock mode is the null move `C0,0`.
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, pad or '8'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2, pad or '8'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, pad or '6'.code),
                // Shift slides without moving the cursor, control moves
                // the cursor with the tile.
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_LEFT or shift),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_LEFT or ctrl),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_DOWN or ctrl),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT or shift),
                // A modified cursor key on the ring is unused.
                listOf(
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_LEFT or ctrl,
                ),
                // The four corners of the (w+2) x (h+2) walk are
                // remapped onto the frame. The select names the line, so
                // a wrong remap changes the move.
                listOf(
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_LEFT,
                    Buttons.CURSOR_SELECT,
                ),
                listOf(
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_SELECT,
                ),
                listOf(
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_LEFT,
                    Buttons.CURSOR_SELECT,
                ),
                listOf(
                    Buttons.CURSOR_UP,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_SELECT,
                ),
            )

            // The C cursor position is not part of the save file, so
            // these expected positions come from the move strings the
            // oracle produced for the same sequences (verified above
            // through the saved state).
            val cursorAfter = mapOf(
                cases[0] to (3 to 0),
                cases[1] to (0 to 0),
                // The two selects unlock, so the arrow walks the ring.
                cases[2] to (-1 to 0),
                cases[3] to (0 to 0),
                cases[4] to (0 to 0),
                cases[5] to (0 to 0),
                cases[6] to (0 to 0),
                cases[7] to (0 to 0),
                cases[8] to (3 to 0),
                cases[9] to (0 to 1),
                cases[10] to (0 to 0),
                cases[11] to (0 to -1),
                cases[12] to (-1 to 0),
                cases[13] to (4 to 0),
                cases[14] to (-1 to 3),
                cases[15] to (4 to 3),
            )

            for (sequence in cases) {
                val expected = oracle.keys(save, sequence)
                val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
                val ui = actual.ui as Sixteen.Ui
                for (key in sequence) {
                    actual.processKey(0, 0, key, Sixteen.preferredTileSize)
                }
                assertSame("keys $sequence", expected, actual)
                cursorAfter[sequence]?.let { (cursorX, cursorY) ->
                    assertEquals("cursor x after $sequence", cursorX, ui.curX)
                    assertEquals("cursor y after $sequence", cursorY, ui.curY)
                }
            }

            // The null move is a real history entry with an unchanged
            // board and one more move.
            val nullMove = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            nullMove.processKey(0, 0, Buttons.CURSOR_UP, 48)
            nullMove.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            val movesBefore = nullMove.stateCount
            assertEquals(
                KeyOutcome.SomeEffect,
                nullMove.processKey(0, 0, pad or '8'.code, 48),
            )
            assertEquals("one history entry", movesBefore + 1, nullMove.stateCount)
            assertEquals("C0,0", nullMove.serialise().values("MOVE").single())
            assertEquals("the board is unchanged", base, nullMove.id().substringAfter(':'))
            assertEquals(1, (nullMove.state as Sixteen.State).moveCount)

            // A lock toggle is a UI update and adds no history.
            val lock = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            val lockUi = lock.ui as Sixteen.Ui
            lock.processKey(0, 0, Buttons.CURSOR_UP, 48)
            assertEquals(KeyOutcome.SomeEffect, lock.processKey(0, 0, Buttons.CURSOR_SELECT, 48))
            assertEquals(Sixteen.CursorMode.LOCK_TILE, lockUi.cursorMode)
            assertEquals(1, lock.stateCount)
            assertEquals(KeyOutcome.Unused, lock.processKey(0, 0, Buttons.LEFT_DRAG, 48))
            assertEquals(1, lock.stateCount)
        }

    @Test
    fun `a completed click changes the move count`() = OracleClient().use { oracle ->
        val almost = "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16"
        val save = saveText("4x4", almost)
        val start = oracle.id(save)
        val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))

        assertEquals(0, actual.status())
        assertEquals(KeyOutcome.SomeEffect, actual.processKey(24, 72, Buttons.LEFT_BUTTON, 48))
        assertEquals(1, actual.status())
        assertEquals("COMPLETED!Moves: 1", actual.statusText())
        assertEquals(1, (actual.state as Sixteen.State).moveCount)
        assertTrue(actual.takeCompletedEvent())
        assertFalse(actual.takeCompletedEvent())

        val expected = oracle.pointsSized(
            start.state,
            listOf(OracleClient.Point(24, 72, Buttons.LEFT_BUTTON)),
        )
        assertEquals(1, expected.state.status)
        assertEquals("COMPLETED!Moves: 1", traceStatus(oracle, expected.state.state))
        assertSame("the completing click", expected.state, actual)

        // A full-cycle click (right border of a 1-wide row is not
        // possible), but the null move from a keypad digit is: the board
        // is unchanged and the count still rises.
        val noop = Midend.fromSave(Sixteen, SgtpSave.parse(save))
        noop.processKey(0, 0, Buttons.CURSOR_UP, 48)
        noop.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
        val tilesBefore = (noop.state as Sixteen.State).tiles.toList()
        noop.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '8'.code, 48)
        assertEquals(tilesBefore, (noop.state as Sixteen.State).tiles.toList())
        assertEquals(1, (noop.state as Sixteen.State).moveCount)
        assertEquals(0, noop.status())
    }

    @Test
    fun `a full cycle move is accepted and counted`() = OracleClient().use { oracle ->
        val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        for (move in listOf("R0,0", "C0,0", "R0,4", "R0,-4", "C0,4", "C0,-4")) {
            val save = saveText("4x4", base, listOf(move))
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
            assertSame(move, expected, actual)
            assertEquals("$move keeps the board", base, actual.id().substringAfter(':'))
            assertEquals("$move counts as one move", 1, (actual.state as Sixteen.State).moveCount)
            assertEquals("Moves: 1", actual.statusText())
        }

        // Out-of-bounds moves are rejected on both sides.
        for (move in listOf("R0,5", "R0,-5", "C0,5", "C0,-5")) {
            val save = saveText("4x4", base, listOf(move))
            val oracleError = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("the oracle rejects $move", "Save file contained an invalid move", oracleError)
            val kotlinError = try {
                Midend.fromSave(Sixteen, SgtpSave.parse(save))
                null
            } catch (e: RuntimeException) {
                e.message
            }
            assertNotEquals("Kotlin rejects $move", null, kotlinError)
        }
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val almost = "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16"
        val save = saveText("4x4", almost, listOf("R0,-1"))
        var expected = oracle.id(save)
        val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))
        assertFalse(actual.takeCompletedEvent())
        assertEquals(1, actual.status())

        expected = oracle.undo(expected.state)
        assertTrue(actual.undo())
        assertSame("undo from complete", expected, actual)
        assertEquals(0, actual.status())
        assertEquals("Moves: 0", actual.statusText())
        assertFalse("an undo leaves the completion", actual.takeCompletedEvent())

        expected = oracle.redo(expected.state)
        assertTrue(actual.redo())
        assertSame("redo into complete", expected, actual)
        assertEquals(1, actual.status())
        assertEquals("COMPLETED!Moves: 1", actual.statusText())
        assertTrue("a redo into the completion raises the event", actual.takeCompletedEvent())

        // Undo and redo of an ordinary move.
        val normal = saveText("4x4", "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4", listOf("R0,1"))
        var normalExpected = oracle.id(normal)
        val normalActual = Midend.fromSave(Sixteen, SgtpSave.parse(normal))
        normalExpected = oracle.undo(normalExpected.state)
        assertTrue(normalActual.undo())
        assertSame("undo of a normal move", normalExpected, normalActual)
        normalExpected = oracle.redo(normalExpected.state)
        assertTrue(normalActual.redo())
        assertSame("redo of a normal move", normalExpected, normalActual)
    }

    @Test
    fun `the solve matches the oracle and raises no event`() = OracleClient().use { oracle ->
        val base = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val save = saveText("4x4", base)
        val start = oracle.id(save)
        val actual = Midend.fromSave(Sixteen, SgtpSave.parse(save))

        assertEquals("S", Sixteen.solve(actual.state))
        assertTrue(actual.solve())
        assertEquals("SOLVE", actual.serialise().records.last().key)
        assertEquals("S", actual.serialise().value("SOLVE"))
        assertEquals(1, actual.status())
        assertEquals("Moves since auto-solve: 0", actual.statusText())
        assertFalse("a solve never raises the event", actual.takeCompletedEvent())

        val expected = oracle.solve(start.state)
        assertEquals("S", SgtpSave.parse(expected.state).value("SOLVE"))
        assertEquals(1, expected.status)
        assertEquals("Moves since auto-solve: 0", traceStatus(oracle, expected.state))
        assertSame("the solve", expected, actual)
        assertEquals(
            " 1  2  3  4\n 5  6  7  8\n 9 10 11 12\n13 14 15 16\n",
            actual.textFormat(),
        )

        // C never fails, so a second solve appends a second SOLVE record.
        assertTrue(actual.solve())
        assertEquals(listOf("S", "S"), actual.serialise().values("SOLVE"))
        assertEquals(2, actual.serialise().values("SOLVE").size)
        assertFalse(actual.takeCompletedEvent())

        // A move after the solve counts from the solved position.
        assertEquals(KeyOutcome.SomeEffect, actual.processKey(24, 72, Buttons.LEFT_BUTTON, 48))
        assertEquals("Moves since auto-solve: 1", actual.statusText())
        assertEquals(1, actual.status())
    }

    @Test
    fun `undo from a solved state is a Kotlin-only case`() {
        // C's animation path asserts `abs(dx) == TILE_SIZE` when a whole
        // board is replaced, so the oracle aborts on `<undo>` after a
        // SOLVE (work/specs/sixteen.md, section 10, risk 2). The state
        // transition itself is a normal undo. This test pins the
        // exclusion and the Kotlin behaviour.
        val save = saveText("4x4", "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4", emptyList(), "S")
        val midend = Midend.fromSave(Sixteen, SgtpSave.parse(save))
        assertTrue((midend.state as Sixteen.State).usedSolve)
        assertTrue(midend.undo())
        assertFalse((midend.state as Sixteen.State).usedSolve)
        assertEquals("Moves: 0", midend.statusText())
        assertTrue(midend.canRedo)

        assertTrue(midend.redo())
        assertTrue((midend.state as Sixteen.State).usedSolve)
        assertEquals("Moves since auto-solve: 0", midend.statusText())
    }

    private data class Case(
        val params: String,
        val desc: String,
        val moves: List<String>,
        val solve: String?,
    )

    private fun traceStatus(oracle: OracleClient, save: String): String? {
        val ops = (oracle.trace(save).field("ops") as JsonValue.Arr).items
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
        solve: String? = null,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Sixteen"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size + (if (solve != null) 1 else 0)).toString()),
            SaveRecord("STATEPOS", (1 + moves.size + (if (solve != null) 1 else 0)).toString()),
        )
        if (solve != null) records += SaveRecord("SOLVE", solve)
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val VIEW_WIDTH = 411
        const val VIEW_HEIGHT = 702
    }
}
