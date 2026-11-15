package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: pegs.
 *
 * New games, the text format, the status, a real drag, undo and redo,
 * completion, the absent solver, and the absent auxiliary record.
 *
 * A completed board cannot be described, because a description needs two
 * pegs and one hole. The completion case therefore uses a hand-written
 * save with one `MOVE`, which is the only way upstream can represent it.
 */
class PegsGameTest {

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "5x7cross" to "1",
            "7x7cross" to "12345",
            "7x7octagon" to "seed",
            "7x7random" to "12345",
            "9x9random" to "2",
        )
        for ((params, seed) in cases) {
            val expected = oracle.newGame("pegs", params, seed)
            val actual = Midend.newGame(Pegs, params, seed)
            assertSame("'$params' seed=$seed", expected, actual)
            assertEquals(
                "text '$params' seed=$seed",
                oracle.text(expected.state),
                actual.textFormat(),
            )
            assertEquals("", actual.statusText())
            assertFalse(Pegs.wantsStatusBar)
            assertEquals(0, expected.status)
            val ops = (oracle.trace(expected.state).field("ops") as JsonValue.Arr).items
            assertTrue(
                "pegs writes no status operation",
                ops.none { (it as JsonValue.Obj).string("op") == "status" },
            )
        }
    }

    @Test
    fun `a two-cell drag is one move on both sides`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val expected = oracle.gestureSized(
            start.state,
            GAME_X,
            GAME_Y,
            GAME_X,
            GAME_Y + 2 * TILE,
            STEPS,
            WIDTH,
            HEIGHT,
        )
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)

        val outcome = drag(midend, GAME_X, GAME_Y, GAME_X, GAME_Y + 2 * TILE)
        assertEquals(KeyOutcome.SomeEffect, outcome)
        assertSame("after the drag", expected, midend)
        assertEquals(
            listOf("2,1-2,3"),
            SgtpSave.parse(expected.state).values("MOVE"),
        )
        assertEquals("the move changes the board", oracle.text(expected.state), midend.textFormat())
        assertTrue("the text has a new hole", midend.textFormat().contains(" *-* "))
        assertEquals(0, midend.status())
    }

    @Test
    fun `the move count is the history length`() = OracleClient().use { oracle ->
        var expected = oracle.newGame("pegs", "5x7cross", SEED)
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        assertEquals(1, midend.stateCount)
        assertFalse(midend.canUndo)

        expected = oracle.gestureSized(
            expected.state, GAME_X, GAME_Y, GAME_X, GAME_Y + 2 * TILE,
            STEPS, WIDTH, HEIGHT,
        )
        drag(midend, GAME_X, GAME_Y, GAME_X, GAME_Y + 2 * TILE)
        assertEquals(2, midend.stateCount)
        assertEquals(1, SgtpSave.parse(expected.state).values("MOVE").size)
        assertTrue(midend.canUndo)
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val moved = oracle.gestureSized(
            start.state, GAME_X, GAME_Y, GAME_X, GAME_Y + 2 * TILE,
            STEPS, WIDTH, HEIGHT,
        )
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        drag(midend, GAME_X, GAME_Y, GAME_X, GAME_Y + 2 * TILE)
        assertSame("after the move", moved, midend)

        val undone = oracle.undo(moved.state)
        assertTrue(midend.undo())
        assertSame("after undo", undone, midend)
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)
        assertEquals(oracle.text(undone.state), midend.textFormat())
        assertEquals("", midend.statusText())

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertSame("after redo", redone, midend)
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
        assertEquals(oracle.text(redone.state), midend.textFormat())
    }

    @Test
    fun `a move that leaves one peg completes the game`() = OracleClient().use { oracle ->
        // Two pegs and a hole at the left of a 5x7 board. 32 obstacles
        // fill the rest.
        val desc = "PPH" + "O".repeat(32)
        val save = saveText(desc, listOf("0,0-2,0"))

        val expected = oracle.id(save)
        assertEquals(1, expected.status)
        assertEquals(
            "--*  \n" +
                "     \n".repeat(6),
            oracle.text(save),
        )

        // Kotlin loads the same history. A load raises no completion
        // event, as C does not either.
        val loaded = Midend.fromSave(Pegs, SgtpSave.parse(save))
        assertFalse(loaded.takeCompletedEvent())
        assertSame("completed save", expected, loaded)
        assertEquals(oracle.text(save), loaded.textFormat())
        assertEquals("", loaded.statusText())
        assertEquals(1, loaded.status())

        // A played last move raises the event once.
        val played = Midend.fromGameId(Pegs, "5x7:$desc")
        val outcome = drag(played, centre(0), centre(0), centre(2), centre(0))
        assertEquals(KeyOutcome.SomeEffect, outcome)
        assertTrue(played.takeCompletedEvent())
        assertFalse(played.takeCompletedEvent())
        assertEquals(1, played.status())
        assertEquals(2, played.stateCount)
        assertEquals(desc, played.desc)
    }

    @Test
    fun `the solver is absent on both sides`() = OracleClient().use { oracle ->
        val generated = oracle.newGame("pegs", "5x7cross", SEED)
        try {
            oracle.solve(generated.state)
            throw AssertionError("the oracle solved pegs")
        } catch (e: OracleException) {
            assertEquals("This game does not support the Solve operation", e.message)
        }

        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        assertFalse(Pegs.canSolve)
        assertNull(Pegs.solve(midend.state))
        assertFalse("the midend adds no solve entry", midend.solve())
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `requestKeys match the oracle`() = OracleClient().use { oracle ->
        val game = oracle.newGame("pegs", "5x7cross", SEED)
        val expected = oracle.requestKeys(game.state)
        val actual = Pegs.requestKeys(Pegs.decodeParams("5x7cross"))

        assertEquals(2, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT, actual.arrowMode)
        assertTrue("C requests no extra keys", expected.labels.isEmpty())
        assertTrue("Kotlin requests no extra keys", actual.labels.isEmpty())
    }

    @Test
    fun `the save has no AUXINFO and no UI record`() = OracleClient().use { oracle ->
        val start = oracle.newGame("pegs", "5x7cross", SEED)
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        assertFalse(midend.serialise().records.any { it.key == "AUXINFO" })
        assertFalse(midend.serialise().records.any { it.key == "UI" })

        // A drag and a cursor key do not add a UI record: pegs has no
        // encode_ui.
        drag(midend, GAME_X, GAME_Y, GAME_X, GAME_Y + 2 * TILE)
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE)
        val save = midend.serialise()
        assertFalse(save.records.any { it.key == "AUXINFO" })
        assertFalse(save.records.any { it.key == "UI" })
        assertEquals(
            "the oracle save has neither record",
            emptyList<String>(),
            SgtpSave.parse(start.state).records
                .map { it.key }
                .filter { it == "AUXINFO" || it == "UI" },
        )
    }

    private fun drag(midend: Midend, x0: Int, y0: Int, x1: Int, y1: Int): KeyOutcome {
        val tile = midend.geometryFor(WIDTH, HEIGHT).tileSize
        assertEquals(TILE, tile)
        assertEquals(
            "the press must start a drag",
            KeyOutcome.SomeEffect,
            midend.processKey(x0, y0, Buttons.LEFT_BUTTON, tile),
        )
        for (i in 1..STEPS) {
            midend.processKey(
                x0 + (x1 - x0) * i / STEPS,
                y0 + (y1 - y0) * i / STEPS,
                Buttons.LEFT_DRAG,
                tile,
            )
        }
        return midend.processKey(x1, y1, Buttons.LEFT_RELEASE, tile)
    }

    private fun centre(index: Int): Int = TILE / 2 + index * TILE + TILE / 2

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private fun saveText(desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Pegs"),
            SaveRecord("PARAMS", "5x7cross"),
            SaveRecord("CPARAMS", "5x7cross"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 33
        const val WIDTH = 5 * TILE + 2 * (TILE / 2)
        const val HEIGHT = 7 * TILE + 2 * (TILE / 2)
        const val GAME_X = TILE / 2 + 2 * TILE + TILE / 2
        const val GAME_Y = TILE / 2 + 1 * TILE + TILE / 2
        const val STEPS = 4
    }
}
