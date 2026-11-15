package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: netslide.
 *
 * New games, status text, the loose move grammar, real row and column
 * slides including `|d| > 1` and `|d| = w`, pointer bands, the keyboard
 * ring, undo and redo, completion, solve, and the save file in both
 * directions.
 *
 * A full-cycle move changes no tile but still counts one move, so the
 * board and the move count are asserted separately. A click that lands
 * in a cell, a corner, or the centre band is `Unused`, not a UI update,
 * and the oracle adds no move for it.
 */
class NetslideGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        val paramsList = listOf(
            "3x3b1",
            "3x3",
            "3x3w",
            "4x4b1",
            "5x5",
            "4x4m10",
            "4x7",
            "2x2",
            "3x3b0.5",
            "3x3wm2",
        )
        for (params in paramsList) {
            for (seed in listOf("1", "12345", "seed")) {
                val expected = oracle.newGame("netslide", params, seed)
                val actual = Midend.newGame(Netslide, params, seed)
                assertSame("netslide '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text netslide '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals(
                    "status text netslide '$params' seed=$seed",
                    traceStatus(oracle, expected.state),
                    actual.statusText(),
                )
                assertEquals("no text form", "", actual.textFormat())
                assertFalse(Netslide.canFormatAsText)
            }
        }
    }

    @Test
    fun `requested keys match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.requestKeys(oracle.newGame("netslide", "3x3b1", SEED).state)
        val actual = Netslide.requestKeys(Netslide.decodeParams("3x3b1"))

        assertEquals(3, expected.arrowMode)
        assertEquals("arrow mode", ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertTrue("C requests no extra keys", expected.labels.isEmpty())
        assertTrue("Kotlin requests no extra keys", actual.labels.isEmpty())
    }

    @Test
    fun `row and column slides match the oracle`() = OracleClient().use { oracle ->
        val base = "21c4d5963"
        val moves = listOf(
            "R0,1",
            "R0,-1",
            "R0,2",
            "R0,-2",
            "R1,1",
            "R2,-1",
            "R0,3",
            "R0,-3",
            "C0,1",
            "C0,-1",
            "C0,2",
            "C0,-2",
            "C1,1",
            "C2,-1",
            "C0,3",
            "C0,-3",
            // The loose `sscanf` grammar: junk, spaces, signs, and
            // leading zeros are all accepted.
            "R0,1junk",
            "R0, 1",
            "R+0,+1",
            "R00,01",
            "R0,1 ",
        )

        for (move in moves) {
            val save = saveText("3x3", base, listOf(move))
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))
            assertSame("move '$move'", expected, actual)
            assertEquals(
                "status text '$move'",
                traceStatus(oracle, save),
                actual.statusText(),
            )
            assertEquals("one MOVE record for '$move'", listOf(move), actual.serialise().values("MOVE"))
            assertEquals("one move for '$move'", 1, (actual.state as Netslide.State).moveCount)
        }

        // A step of two is a scramble and rewrites the row in the
        // visit order; a full cycle is an identity but still counts a
        // move. The board is checked directly because a moved state is
        // not present in the save file.
        val initialBoard = (Midend.fromSave(Netslide, SgtpSave.parse(saveText("3x3", base)))
            .state as Netslide.State).tiles.toList()
        val scrambled = Midend.fromSave(
            Netslide,
            SgtpSave.parse(saveText("3x3", base, listOf("R0,2"))),
        ).state as Netslide.State
        assertEquals(listOf(12, 2, 1), listOf(scrambled.tileAt(0, 0), scrambled.tileAt(1, 0), scrambled.tileAt(2, 0)))
        assertEquals(1, scrambled.moveCount)
        assertNotEquals(initialBoard, scrambled.tiles.toList())

        val fullCycle = Midend.fromSave(
            Netslide,
            SgtpSave.parse(saveText("3x3", base, listOf("R0,3"))),
        ).state as Netslide.State
        assertEquals("a full cycle keeps the board", initialBoard, fullCycle.tiles.toList())
        assertEquals(1, fullCycle.moveCount)
        assertEquals(0, Netslide.status(fullCycle))

        // Out-of-bounds moves are rejected on both sides.
        for (move in listOf("R0,4", "R0,-4", "C0,4", "C0,-4", "R0,0", "R3,1", "C3,1", "R0", "X0,1")) {
            val save = saveText("3x3", base, listOf(move))
            val oracleError = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "the oracle rejects '$move'",
                "Save file contained an invalid move",
                oracleError,
            )
            val kotlinError = try {
                Midend.fromSave(Netslide, SgtpSave.parse(save))
                null
            } catch (e: RuntimeException) {
                e.message
            }
            assertNotEquals("Kotlin rejects '$move'", null, kotlinError)
        }
    }

    @Test
    fun `a completing move matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("3x3", "5c19d6234", listOf("R0,-1"))
        val expected = oracle.id(save)
        val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))

        assertEquals(1, expected.status)
        assertEquals(1, actual.status())
        assertSame("the completing move", expected, actual)
        assertEquals("COMPLETED! Moves: 1 Active: 9/9", actual.statusText())
        assertEquals("COMPLETED! Moves: 1 Active: 9/9", traceStatus(oracle, save))
        assertEquals(1, (actual.state as Netslide.State).completed)
        // The loaded state raises no event; a live completing click
        // raises exactly one.
        assertFalse("a load raises no event", actual.takeCompletedEvent())

        val almost = saveText("3x3", "5c19d6234")
        val live = Midend.fromSave(Netslide, SgtpSave.parse(almost))
        assertEquals(0, live.status())
        assertEquals(KeyOutcome.SomeEffect, live.processKey(216, 72, Buttons.LEFT_BUTTON, 48))
        assertEquals(1, live.status())
        assertEquals("COMPLETED! Moves: 1 Active: 9/9", live.statusText())
        assertTrue("a live completion raises the event", live.takeCompletedEvent())
        assertEquals(
            1,
            oracle.pointsSized(
                oracle.id(almost).state,
                listOf(OracleClient.Point(216, 72, Buttons.LEFT_BUTTON)),
            ).state.status,
        )
    }

    @Test
    fun `pointer bands and inert clicks match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("3x3", "21c4d5963")
        val start = oracle.id(save)

        // Game coordinates at tile 48 with no letterbox; the board is
        // 241x241, so the view and game origins agree.
        val clicks = listOf(
            Triple(24, 72, Buttons.LEFT_BUTTON) to "R0,1",
            Triple(24, 168, Buttons.LEFT_BUTTON) to "R2,1",
            Triple(216, 72, Buttons.LEFT_BUTTON) to "R0,-1",
            Triple(216, 72, Buttons.RIGHT_BUTTON) to "R0,1",
            Triple(72, 24, Buttons.LEFT_BUTTON) to "C0,1",
            Triple(168, 24, Buttons.LEFT_BUTTON) to "C2,1",
            Triple(72, 216, Buttons.LEFT_BUTTON) to "C0,-1",
        )

        for ((click, move) in clicks) {
            val (x, y, button) = click
            val expected = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(x, y, button)),
                241,
                241,
            )
            val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val tilesBefore = (actual.state as Netslide.State).tiles.toList()
            assertEquals(
                "outcome for ($x,$y)",
                KeyOutcome.SomeEffect,
                actual.processKey(x, y, button, 48),
            )
            assertEquals("one MOVE at ($x,$y) button $button", listOf(move), actual.serialise().values("MOVE"))
            assertEquals("id at ($x,$y)", expected.state.id, actual.id())
            assertEquals("one move at ($x,$y)", 1, (actual.state as Netslide.State).moveCount)
            assertNotEquals(
                "a real slide changes the board at ($x,$y)",
                tilesBefore,
                (actual.state as Netslide.State).tiles.toList(),
            )
        }

        // A click in a cell, a corner, the centre band, the letterbox,
        // or outside the board adds no move and changes nothing.
        val inert = listOf(
            Triple(72, 72, Buttons.LEFT_BUTTON),
            Triple(24, 24, Buttons.LEFT_BUTTON),
            Triple(24, 120, Buttons.LEFT_BUTTON),
            Triple(120, 24, Buttons.LEFT_BUTTON),
            Triple(120, 120, Buttons.LEFT_BUTTON),
            Triple(0, 120, Buttons.LEFT_BUTTON),
            Triple(240, 120, Buttons.LEFT_BUTTON),
            Triple(241, 120, Buttons.LEFT_BUTTON),
            Triple(72, 72, Buttons.RIGHT_BUTTON),
        )
        for ((x, y, button) in inert) {
            val expected = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(x, y, button)),
                241,
                241,
            )
            assertEquals("the oracle adds no move at ($x,$y)", start.state, expected.state.state)

            val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val state = actual.state as Netslide.State
            val tilesBefore = state.tiles.toList()
            val countBefore = state.moveCount
            assertEquals(
                "an inert click is unused at ($x,$y)",
                KeyOutcome.Unused,
                actual.processKey(x, y, button, 48),
            )
            assertEquals("no move at ($x,$y)", emptyList<String>(), actual.serialise().values("MOVE"))
            assertEquals("no history at ($x,$y)", 1, actual.stateCount)
            assertEquals("no board change at ($x,$y)", tilesBefore, (actual.state as Netslide.State).tiles.toList())
            assertEquals("no move count at ($x,$y)", countBefore, (actual.state as Netslide.State).moveCount)
        }

        // A press-drag-release gesture makes exactly one move, at the
        // press; the drag and the release are unused.
        val gesture = Midend.fromSave(Netslide, SgtpSave.parse(save))
        assertEquals(KeyOutcome.SomeEffect, gesture.processKey(24, 72, Buttons.LEFT_BUTTON, 48))
        assertEquals(KeyOutcome.Unused, gesture.processKey(72, 72, Buttons.LEFT_DRAG, 48))
        assertEquals(KeyOutcome.Unused, gesture.processKey(72, 72, Buttons.LEFT_RELEASE, 48))
        // The press made one move, and the drag and release made none.
        assertEquals(1, gesture.stateCount - 1)
        assertEquals(listOf("R0,1"), gesture.serialise().values("MOVE"))

        val lone = Midend.fromSave(Netslide, SgtpSave.parse(save))
        assertEquals(KeyOutcome.Unused, lone.processKey(72, 72, Buttons.LEFT_RELEASE, 48))
        assertEquals(1, lone.stateCount)
    }

    @Test
    fun `the keyboard ring matches the oracle`() = OracleClient().use { oracle ->
        val save = saveText("3x3", "21c4d5963")
        val ctrl = Buttons.MOD_CTRL
        val pad = Buttons.MOD_NUM_KEYPAD

        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
            // RIGHT does nothing on the right column; DOWN is the way
            // around the ring there.
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
            ),
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_LEFT,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_UP,
            ),
            // A select with a hidden cursor only shows it.
            listOf(Buttons.CURSOR_SELECT),
            // Then the same position selects column 0 backwards.
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
            // The right column at row 0 selects row 0 backwards.
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
            // A select at the top-right corner selects column 2 forward.
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            ),
            // Netslide strips modifiers at entry, so a modified cursor
            // key still moves the cursor.
            listOf(Buttons.CURSOR_RIGHT or ctrl),
            listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT, Buttons.CURSOR_SELECT),
            // A keypad digit is not a game key.
            listOf(Buttons.CURSOR_RIGHT, pad or '8'.code),
        )

        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))
            for (key in sequence) {
                actual.processKey(0, 0, key, 48)
            }
            assertSame("keys $sequence", expected, actual)
            assertEquals(
                "status text for keys $sequence",
                traceStatus(oracle, expected.state),
                actual.statusText(),
            )
        }

        // RIGHT from (0,-1) moves to the top-right arrow and turns the
        // cursor on but adds no history.
        val cursor = Midend.fromSave(Netslide, SgtpSave.parse(save))
        val ui = cursor.ui as Netslide.Ui
        assertEquals(KeyOutcome.SomeEffect, cursor.processKey(0, 0, Buttons.CURSOR_RIGHT, 48))
        assertEquals(2, ui.curX)
        assertEquals(-1, ui.curY)
        assertTrue(ui.cursorVisible)
        assertEquals(1, cursor.stateCount)
        assertEquals(emptyList<String>(), cursor.serialise().values("MOVE"))
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("3x3", "21c4d5963", listOf("R0,1"))
        var expected = oracle.id(save)
        val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))
        assertTrue(actual.canUndo)
        assertFalse(actual.canRedo)

        expected = oracle.undo(expected.state)
        assertTrue(actual.undo())
        assertSame("undo", expected, actual)
        assertFalse(actual.canUndo)
        assertTrue(actual.canRedo)
        assertEquals("Moves: 0 Active: 4/9", actual.statusText())

        expected = oracle.redo(expected.state)
        assertTrue(actual.redo())
        assertSame("redo", expected, actual)
        assertTrue(actual.canUndo)
        assertFalse(actual.canRedo)

        // A completed move undone and redone raises the event only on
        // the redo.
        val completing = saveText("3x3", "5c19d6234", listOf("R0,-1"))
        var completingExpected = oracle.id(completing)
        val completingActual = Midend.fromSave(Netslide, SgtpSave.parse(completing))
        assertFalse("a load raises no event", completingActual.takeCompletedEvent())
        completingExpected = oracle.undo(completingExpected.state)
        assertTrue(completingActual.undo())
        assertSame("undo from complete", completingExpected, completingActual)
        assertEquals(0, completingActual.status())
        assertFalse("an undo raises no event", completingActual.takeCompletedEvent())
        completingExpected = oracle.redo(completingExpected.state)
        assertTrue(completingActual.redo())
        assertSame("redo into complete", completingExpected, completingActual)
        assertTrue("a redo into the completion raises the event", completingActual.takeCompletedEvent())
    }

    @Test
    fun `restart returns to the same description`() = OracleClient().use { oracle ->
        val save = saveText("3x3", "21c4d5963", listOf("R0,1"))
        val initial = oracle.id(saveText("3x3", "21c4d5963"))
        val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))

        assertTrue(actual.restart())
        assertEquals(initial.id, actual.id())
        assertEquals(initial.status, actual.status())
        assertEquals("RESTART", actual.serialise().records.last().key)
        assertEquals("21c4d5963", actual.serialise().value("RESTART"))
        assertEquals(traceStatus(oracle, saveText("3x3", "21c4d5963")), actual.statusText())

        // At the first state there is nothing to restart.
        val fresh = Midend.fromSave(Netslide, SgtpSave.parse(saveText("3x3", "21c4d5963")))
        assertFalse(fresh.restart())
        assertEquals(1, fresh.stateCount)
    }

    @Test
    fun `the solve matches the oracle`() = OracleClient().use { oracle ->
        val generated = oracle.newGame("netslide", "3x3b1", SEED)
        val save = generated.state
        val solvedText = oracle.solve(save).state
        assertEquals("SOLVE", SgtpSave.parse(solvedText).records.last().key)
        assertEquals("S15c9d6234", SgtpSave.parse(solvedText).value("SOLVE"))

        val actual = Midend.fromSave(Netslide, SgtpSave.parse(save))
        assertTrue(actual.solve())
        assertEquals("S15c9d6234", actual.serialise().value("SOLVE"))
        assertEquals("Moves since auto-solve: 0 Active: 9/9", actual.statusText())
        assertEquals("Moves since auto-solve: 0 Active: 9/9", traceStatus(oracle, solvedText))
        assertEquals(1, actual.status())
        assertFalse("a solve never raises the event", actual.takeCompletedEvent())
        assertEquals("S15c9d6234", Netslide.solve(actual.state, actual.auxInfo))

        // A save without AUXINFO cannot solve and appends nothing.
        val stripped = stripAuxInfo(save)
        val unsolved = Midend.fromSave(Netslide, SgtpSave.parse(stripped))
        val oracleError = try {
            oracle.solve(stripped)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Solution not known for this puzzle", oracleError)
        assertFalse(unsolved.solve())
        assertEquals(1, unsolved.stateCount)

        // A real move after the solve counts from the solve move and
        // raises no completion event.
        assertTrue(actual.solve())
        assertEquals(
            KeyOutcome.SomeEffect,
            actual.processKey(24, 72, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(actual.statusText().startsWith("Moves since auto-solve: 1 "))
        assertEquals(1, actual.status())
        assertFalse(actual.takeCompletedEvent())
    }

    @Test
    fun `the save file round trips in both directions`() = OracleClient().use { oracle ->
        val paramsList = Netslide.presets().map { it.params } + listOf(
            "4x4m10",
            "3x3b0.5",
            "3x3wm2",
            "4x7",
        )
        for (params in paramsList) {
            val generated = oracle.newGame("netslide", params, SEED)
            val expected = SgtpSave.parse(generated.state)

            val actual = Midend.newGame(Netslide, params, SEED)
            val written = actual.serialise().serialise()
            assertEquals("save text for '$params'", generated.state, written)
            // A Kotlin save loads in C with the same id and text.
            val reloaded = oracle.id(written)
            assertEquals("C id for '$params'", generated.id, reloaded.id)
            assertEquals("C status for '$params'", generated.status, reloaded.status)
            assertEquals("C save text for '$params'", generated.state, reloaded.state)

            // The C save loads in Kotlin and keeps its AUXINFO.
            val loaded = Midend.fromSave(Netslide, SgtpSave.parse(generated.state))
            assertEquals("Kotlin save text for '$params'", generated.state, loaded.serialise().serialise())
            assertEquals("AUXINFO for '$params'", expected.value("AUXINFO"), loaded.serialise().value("AUXINFO"))
            assertTrue("netslide writes no UI record", loaded.serialise().value("UI") == null)
            assertTrue("netslide writes no PRIVDESC", loaded.serialise().value("PRIVDESC") == null)
        }

        // Every corpus save loads, keeps its AUXINFO, and rewrites
        // byte for byte. The oracle then reads the rewritten text.
        val dir = File(testdata, "saves/initial/netslide")
        val files = dir.listFiles()?.filter { it.extension == "sgtp" }?.sorted() ?: emptyList()
        assertEquals("the corpus covers every preset and the default", 10, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertTrue("AUXINFO in ${file.name}", save.value("AUXINFO") != null)
            assertTrue("no UI record in ${file.name}", save.value("UI") == null)

            val loaded = Midend.fromSave(Netslide, save)
            assertEquals("rewrite ${file.name}", text, loaded.serialise().serialise())
            assertEquals(
                "C reload of ${file.name}",
                oracle.id(text).id,
                oracle.id(loaded.serialise().serialise()).id,
            )
        }
    }

    private fun stripAuxInfo(save: String): String {
        val parsed = SgtpSave.parse(save)
        return SgtpSave.of(parsed.records.filterNot { it.key == "AUXINFO" }).serialise()
    }

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
            SaveRecord("GAME", "Netslide"),
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
    }
}
