package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: inertia.
 *
 * New games, the text format, the status, real direction moves, death
 * and gem collection, the empty move, the letterbox click, undo and
 * redo, completion, the solver and its hints, the `S` payload, and the
 * `dup_game` revive quirk.
 *
 * Every move is compared as the full save, not only as a move count. A
 * death also compares the gem count, the status, and the death counter.
 */
class InertiaGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        val OPEN_5X5 = "bbbbb" + "gbbbb" + "bbSbb" + "bbbbb" + "bbbbb"

        /** MOD_NUM_KEYPAD plus the keypad digit of each direction. */
        fun keypad(digit: Char): Int = Buttons.MOD_NUM_KEYPAD or digit.code

        val DIRECTION_DIGIT = "89632147"
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        for (params in Inertia.presets().map { it.params }.distinct()) {
            val expected = oracle.newGame("inertia", params, SEED)
            val actual = Midend.newGame(Inertia, params, SEED)
            assertSame("'$params'", expected, actual)
            assertEquals("'$params' text", oracle.text(expected.state), actual.textFormat())
            assertEquals(0, expected.status)

            // The status text is the one in the traced redraw.
            val ops = (oracle.trace(expected.state).field("ops") as JsonValue.Arr).items
            val status = ops.filterIsInstance<JsonValue.Obj>()
                .last { it.string("op") == "status" }
                .string("text")
            assertEquals("'$params' status text", status, actual.statusText())
            assertTrue("the status mentions the gems", status.startsWith("Gems: "))
        }
    }

    @Test
    fun `every direction key is one move on the open board`() = OracleClient().use { oracle ->
        val save = saveText("5x5", OPEN_5X5, emptyList())
        val before = Midend.fromSave(Inertia, SgtpSave.parse(save))
        val beforeText = before.textFormat()

        for (dir in 0 until 8) {
            val key = keypad(DIRECTION_DIGIT[dir])
            val expected = oracle.keys(save, listOf(key))
            val actual = Midend.fromSave(Inertia, SgtpSave.parse(save))

            assertEquals("direction $dir", KeyOutcome.SomeEffect, actual.processKey(0, 0, key, TILE))
            assertSame("direction $dir", expected, actual)
            assertEquals(
                "direction $dir move record",
                listOf(dir.toString()),
                actual.serialise().values("MOVE"),
            )
            assertEquals("direction $dir history", 2, actual.stateCount)
            assertEquals("direction $dir gems", 1, (actual.state as Inertia.State).gems)
            assertNotEquals(
                "direction $dir changes the board",
                beforeText,
                actual.textFormat(),
            )
            assertTrue(
                "direction $dir moves the player",
                (actual.state as Inertia.State).px != 2 ||
                    (actual.state as Inertia.State).py != 2,
            )
        }
    }

    @Test
    fun `a real move and an inert key are not vacuous`() = OracleClient().use { oracle ->
        val save = saveText("5x5", OPEN_5X5, emptyList())
        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        val start = midend.state as Inertia.State
        val startText = midend.textFormat()

        // A keypad digit the game does not use changes nothing.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, keypad('5'), TILE),
        )
        assertEquals(1, midend.stateCount)
        assertTrue(midend.serialise().values("MOVE").isEmpty())
        assertEquals(startText, midend.textFormat())
        assertEquals(start, midend.state)

        // A real move changes the player, the text, and the move count.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, keypad('8'), TILE),
        )
        assertEquals(2, midend.stateCount)
        assertEquals(listOf("0"), midend.serialise().values("MOVE"))
        assertNotEquals(startText, midend.textFormat())
        assertEquals(start.gems, (midend.state as Inertia.State).gems)
        assertEquals(start.px, (midend.state as Inertia.State).px)
        assertEquals(0, (midend.state as Inertia.State).py)
    }

    @Test
    fun `a death counts once and does not complete`() = OracleClient().use { oracle ->
        val save = saveText("3x2", "Sgmwbs", emptyList())
        val expected = oracle.keys(save, listOf(keypad('6')))
        assertEquals(0, expected.status)

        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, keypad('6'), TILE),
        )
        assertSame("death", expected, midend)
        val dead = midend.state as Inertia.State
        assertTrue(dead.dead)
        assertEquals(0, dead.gems)
        assertEquals(0, midend.status())
        assertEquals("DEAD!   Deaths: 1", midend.statusText())
        assertEquals("D1", midend.serialise().value("UI"))
        assertFalse(midend.takeCompletedEvent())
        assertEquals(
            "+---+---+---+\n" +
                "|( )|   |:-(|\n" +
                "+---+---+---+\n" +
                "|XXX|   |( )|\n" +
                "+---+---+---+\n",
            midend.textFormat(),
        )

        // A redo into the death does not count again.
        assertTrue(midend.undo())
        assertEquals("D1", midend.serialise().value("UI"))
        assertTrue(midend.redo())
        assertEquals("D1", midend.serialise().value("UI"))
        assertEquals("DEAD!   Deaths: 1", midend.statusText())
    }

    @Test
    fun `a gem before a mine dies with zero gems`() = OracleClient().use { oracle ->
        val desc = "bbmbb" + "bbgbb" + "bbSbb" + "bbbbb" + "bbbbb"
        val save = saveText("5x5", desc, emptyList())
        val expected = oracle.keys(save, listOf(keypad('8')))
        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        midend.processKey(0, 0, keypad('8'), TILE)
        assertSame("gem then mine", expected, midend)
        val dead = midend.state as Inertia.State
        assertTrue(dead.dead)
        assertEquals(0, dead.gems)
        assertEquals(0, midend.status())
        assertEquals("DEAD!   Deaths: 1", midend.statusText())
    }

    @Test
    fun `a mine before a gem leaves the gem`() = OracleClient().use { oracle ->
        val desc = "bbgbb" + "bbmbb" + "bbSbb" + "bbbbb" + "bbbbb"
        val save = saveText("5x5", desc, emptyList())
        val expected = oracle.keys(save, listOf(keypad('8')))
        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        midend.processKey(0, 0, keypad('8'), TILE)
        assertSame("mine then gem", expected, midend)
        val dead = midend.state as Inertia.State
        assertTrue(dead.dead)
        assertEquals(1, dead.gems)
        assertEquals(0, midend.status())
    }

    @Test
    fun `a real win completes once, and undo and redo keep the event`() =
        OracleClient().use { oracle ->
            val save = saveText("3x2", "Sgswbb", emptyList())
            val expected = oracle.keys(save, listOf(keypad('6')))
            assertEquals(1, expected.status)

            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, keypad('6'), TILE),
            )
            assertSame("win", expected, midend)
            assertEquals(1, midend.status())
            assertEquals("COMPLETED!", midend.statusText())
            assertTrue(midend.takeCompletedEvent())
            assertFalse(midend.takeCompletedEvent())

            val undone = oracle.undo(expected.state)
            assertTrue(midend.undo())
            assertSame("undo", undone, midend)
            assertFalse(midend.takeCompletedEvent())
            assertEquals(0, midend.status())

            val redone = oracle.redo(undone.state)
            assertTrue(midend.redo())
            assertSame("redo", redone, midend)
            assertTrue(midend.takeCompletedEvent())
            assertEquals(1, midend.status())
        }

    @Test
    fun `a click in the letterbox is a real move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("inertia", "10x8", SEED).state
        val expected = oracle.keySized(save, 0, 213, Buttons.LEFT_BUTTON, 411, 702)
        assertEquals(listOf("6"), SgtpSave.parse(expected.state).values("MOVE"))

        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        val geometry = midend.geometryFor(411, 702)
        assertEquals(39, geometry.tileSize)
        assertEquals(1, geometry.originX)
        assertEquals(185, geometry.originY)
        val before = midend.textFormat()
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(
                0 - geometry.originX,
                213 - geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )
        assertSame("letterbox click", expected, midend)
        assertNotEquals("the letterbox click moves", before, midend.textFormat())
        assertEquals(2, midend.stateCount)
    }

    @Test
    fun `a click on the player tile is inert`() = OracleClient().use { oracle ->
        val save = oracle.newGame("inertia", "10x8", SEED).state
        val start = SgtpSave.parse(save)
        val expected = oracle.keySized(save, 248, 24, Buttons.LEFT_BUTTON, 337, 273)
        assertEquals(save, expected.state)
        assertFalse(expected.canUndo)

        val midend = Midend.fromSave(Inertia, start)
        val text = midend.textFormat()
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(248, 24, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(1, midend.stateCount)
        assertTrue(midend.serialise().values("MOVE").isEmpty())
        assertEquals(text, midend.textFormat())
        assertEquals(save, midend.serialise().serialise())
    }

    @Test
    fun `a click into a wall has no effect`() = OracleClient().use { oracle ->
        val save = oracle.newGame("inertia", "10x8", SEED).state
        val expected = oracle.keySized(save, 248, 0, Buttons.LEFT_BUTTON, 337, 273)
        assertEquals(save, expected.state)

        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(248, 0, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(1, midend.stateCount)
        assertEquals(save, midend.serialise().serialise())
    }

    @Test
    fun `an empty move record is direction zero`() = OracleClient().use { oracle ->
        val save = saveText("5x5", OPEN_5X5, listOf("MOVE" to ""))
        val expected = oracle.id(save)
        val loaded = Midend.fromSave(Inertia, SgtpSave.parse(save))
        assertSame("empty move", expected, loaded)
        assertEquals(listOf(""), loaded.serialise().values("MOVE"))

        // The player moved from (2,2) to (2,0), and the gem remains.
        val moved = loaded.state as Inertia.State
        assertEquals(2, moved.px)
        assertEquals(0, moved.py)
        assertEquals(1, moved.gems)
        assertEquals(0, loaded.status())
        assertEquals(
            oracle.text(save),
            loaded.textFormat(),
        )
    }

    @Test
    fun `the solver, its save record, and its hints match the oracle`() =
        OracleClient().use { oracle ->
            for ((params, seed) in listOf(
                "2x3" to "12345",
                "3x2" to "12345",
                "2x4" to "12345",
                "2x3" to "1",
                "4x4" to "1",
                "10x8" to "12345",
            )) {
                val start = oracle.newGame("inertia", params, seed)
                val expected = oracle.solve(start.state)
                assertEquals("$params solve status", 0, expected.status)

                val midend = Midend.newGame(Inertia, params, seed)
                assertTrue("$params solve", midend.solve())
                assertSame("$params solved", expected, midend)
                val saved = SgtpSave.parse(expected.state)
                val solve = saved.values("SOLVE").single()
                assertEquals("$params SOLVE text", solve, midend.serialise().values("SOLVE").single())
                assertEquals("$params status text", "Auto-solver used. Gems: " +
                    (midend.state as Inertia.State).gems, midend.statusText())
                assertTrue((midend.state as Inertia.State).cheated)
                assertFalse(midend.takeCompletedEvent())

                // The first select follows the stored tour.
                val next = oracle.keys(expected.state, listOf(Buttons.CURSOR_SELECT))
                assertEquals(
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
                )
                assertSame("$params follow", next, midend)
                val followed = midend.state as Inertia.State
                if (followed.gems == 0) {
                    // The first tour move collected the last gem, so
                    // C discards the tour instead of advancing it.
                    assertNull(followed.soln)
                } else {
                    assertEquals("$params hint advance", 1, followed.solnPos)
                }
            }
        }

    @Test
    fun `following a tour to the last gem auto-solves`() = OracleClient().use { oracle ->
        val start = oracle.newGame("inertia", "2x3", "1")
        val solved = oracle.solve(start.state)
        assertEquals(
            listOf("S1045"),
            SgtpSave.parse(solved.state).values("SOLVE"),
        )

        val afterOne = oracle.keys(solved.state, listOf(Buttons.CURSOR_SELECT))
        val afterTwo = oracle.keys(afterOne.state, listOf(Buttons.CURSOR_SELECT))
        assertEquals(1, afterTwo.status)

        val midend = Midend.newGame(Inertia, "2x3", "1")
        assertTrue(midend.solve())
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
        assertSame("after one hint", afterOne, midend)
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
        assertSame("after two hints", afterTwo, midend)
        assertEquals(1, midend.status())
        assertEquals("Auto-solved.", midend.statusText())
        assertFalse(midend.takeCompletedEvent())
        assertNull((midend.state as Inertia.State).soln)
        assertTrue((midend.state as Inertia.State).cheated)
    }

    @Test
    fun `a failed solve leaves the history alone`() = OracleClient().use { oracle ->
        // An already solved board.
        val completed = oracle.keys(
            saveText("2x3", "wbgmSs", emptyList()),
            listOf(keypad('8')),
        )
        assertEquals(1, completed.status)
        val solvedError = try {
            oracle.solve(completed.state)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Game is already solved", solvedError)

        val midend = Midend.fromSave(Inertia, SgtpSave.parse(completed.state))
        assertFalse(midend.solve())
        assertEquals(completed.state, midend.serialise().serialise())

        // A dead board.
        val dead = oracle.keys(
            saveText("3x2", "Sgmbgs", emptyList()),
            listOf(keypad('6')),
        )
        val deadError = try {
            oracle.solve(dead.state)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals(
            "Unable to find a solution from this starting point",
            deadError,
        )
        val deadMidend = Midend.fromSave(Inertia, SgtpSave.parse(dead.state))
        assertFalse(deadMidend.solve())
        assertEquals(dead.state, deadMidend.serialise().serialise())
    }

    @Test
    fun `a saved S payload loads, and a select is a defined NoEffect`() =
        OracleClient().use { oracle ->
            val save = saveText("5x5", OPEN_5X5, listOf("SOLVE" to "S8"))
            val expected = oracle.id(save)
            assertEquals(0, expected.status)

            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            assertSame("S8 load", expected, midend)
            assertEquals(listOf("S8"), midend.serialise().values("SOLVE"))
            assertTrue((midend.state as Inertia.State).cheated)

            // C aborts at `midend.c:1052` here. The port returns a
            // defined NoEffect and keeps the state.
            assertEquals(
                KeyOutcome.NoEffect,
                midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
            )
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(2, midend.stateCount)
        }

    @Test
    fun `a saved SOLVE after a fatal move revives the player`() =
        OracleClient().use { oracle ->
            val save = saveText(
                "3x2",
                "Sgmbgs",
                listOf("MOVE" to "2", "SOLVE" to "S1375"),
            )
            val expected = oracle.id(save)
            assertEquals(0, expected.status)

            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            assertSame("revive", expected, midend)
            val revived = midend.state as Inertia.State
            assertFalse(revived.dead)
            assertTrue(revived.cheated)
            assertEquals(1, revived.gems)
            assertEquals("Auto-solver used. Gems: 1", midend.statusText())
            assertEquals(
                listOf("2", "S1375"),
                midend.serialise().records
                    .filter { it.key == "MOVE" || it.key == "SOLVE" }
                    .map { it.value },
            )
        }

    @Test
    fun `a dead state with UI D2 keeps the death counter`() = OracleClient().use { oracle ->
        val save = saveText(
            "3x2",
            "Sgmwbs",
            listOf("MOVE" to "2"),
            ui = "D2",
        )
        val expected = oracle.id(save)
        assertEquals(0, expected.status)

        val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
        assertSame("dead D2", expected, midend)
        assertEquals("D2", midend.serialise().value("UI"))
        assertEquals("DEAD!   Deaths: 2", midend.statusText())
        assertEquals(oracle.text(save), midend.textFormat())
    }

    @Test
    fun `requestKeys match the oracle on every preset`() = OracleClient().use { oracle ->
        for (params in Inertia.presets().map { it.params }.distinct()) {
            val game = oracle.newGame("inertia", params, SEED)
            for (fullCursor in listOf(false, true)) {
                val expected = oracle.requestKeys(game.state, fullCursor)
                val actual = Inertia.requestKeys(
                    Inertia.decodeParams(params),
                    fullCursor,
                )
                assertEquals("'$params' $fullCursor arrow mode", 4, expected.arrowMode)
                assertEquals("'$params' $fullCursor", ArrowMode.ARROWS_DIAGONALS, actual.arrowMode)
                assertTrue("'$params' $fullCursor labels", expected.labels.isEmpty())
                assertTrue("'$params' $fullCursor", actual.labels.isEmpty())
            }
        }
    }

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>>,
        ui: String = "D0",
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Inertia.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "UI" to ui
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
