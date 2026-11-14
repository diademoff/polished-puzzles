package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: flood.
 *
 * New games, text, status text, moves, completion, the move limit, the
 * solver, the pointer and keyboard paths, undo/redo, and the layout are
 * compared with the oracle.
 *
 * The 4x4 board is deliberately asymmetric, so a transposed grid fails.
 * The solve string is compared token by token, not only by length,
 * because the greedy choice also defines the number in `DESC`.
 */
class FloodGameTest {

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        val paramsList = listOf("4x4c3m0", "12x12c6m5", "3x2c10m0", "2x3c3m0", "1x2c3m0")
        for (params in paramsList) {
            for (seed in listOf("1", "12345", "seed")) {
                val expected = oracle.newGame("flood", params, seed)
                val actual = Midend.newGame(Flood, params, seed)
                assertSame("flood '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text flood '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals(
                    "status text flood '$params' seed=$seed",
                    traceStatus(oracle, expected.state),
                    actual.statusText(),
                )
            }
        }
    }

    @Test
    fun `requested keys match the oracle`() = OracleClient().use { oracle ->
        val game = oracle.newGame("flood", "4x4c3m0", SEED)
        val expected = oracle.requestKeys(game.state)
        val actual = Flood.requestKeys(Flood.decodeParams("4x4c3m0"))

        assertEquals("arrow mode", 2, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT, actual.arrowMode)
        assertEquals("key labels", expected.labels.map { it.label }, actual.labels.map { it.name })
        assertTrue("C requests no extra keys", expected.labels.isEmpty())
        assertTrue("Kotlin requests no extra keys", actual.labels.isEmpty())
    }

    @Test
    fun `crafted states match text and status`() = OracleClient().use { oracle ->
        val base = "1112101220200112"
        val cases = listOf(
            Crafted("4x4c3m0", "$base,4", emptyList(), null),
            Crafted("4x4c3m0", "$base,4", listOf("M2"), null),
            Crafted("4x4c3m0", "$base,4", listOf("M2", "M0", "M1", "M2"), null),
            Crafted("4x4c3m0", "$base,2", listOf("M2", "M0", "M1", "M2"), null),
            Crafted("4x4c3m0", "$base,0", emptyList(), null),
            Crafted("4x4c3m0", "$base,4", emptyList(), "S2,0,1,2"),
            Crafted(
                "4x4c3m0",
                "$base,4",
                listOf("M2", "M0", "M1", "M2"),
                "S2,0,1,2",
            ),
            Crafted("2x2c3m0", "0000,4", emptyList(), null),
            Crafted("2x2c3m0", "0101,3", emptyList(), null),
        )

        for (case in cases) {
            val save = saveText(case.params, case.desc, case.moves, case.solve)
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Flood, SgtpSave.parse(save))
            val what = "${case.params} '${case.desc}' ${case.moves} solve=${case.solve}"

            assertSame(what, expected, actual)
            assertEquals("text $what", oracle.text(save), actual.textFormat())
            assertEquals("status text $what", traceStatus(oracle, save), actual.statusText())
        }

        // Explicit strings from the status table.
        val complete = load(oracle, "4x4c3m0", "$base,4",
            listOf("M2", "M0", "M1", "M2"), null)
        assertEquals("COMPLETED! 4 / 4 moves", complete.second.statusText())
        assertEquals(1, complete.second.status())

        val over = load(oracle, "4x4c3m0", "$base,2",
            listOf("M2", "M0", "M1", "M2"), null)
        assertEquals("FAILED! 4 / 2 moves", over.second.statusText())
        assertEquals(-1, over.second.status())

        val zero = load(oracle, "4x4c3m0", "$base,0", emptyList(), null)
        assertEquals("FAILED! 0 / 0 moves", zero.second.statusText())
        assertEquals(-1, zero.second.status())

        // `new_game` does not compute completeness, so a uniform
        // description is still open and reports the plain move count.
        val uniform = load(oracle, "2x2c3m0", "0000,4", emptyList(), null)
        assertEquals("0 / 4 moves", uniform.second.statusText())
        assertEquals(0, uniform.second.status())
    }

    @Test
    fun `the solve string and cheated flag match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("4x4c3m0", "1112101220200112,4", emptyList(), null)
        val state = Flood.newState(Flood.decodeParams("4x4c3m0"), "1112101220200112,4")

        val solved = oracle.solve(save)
        val solveRecord = SgtpSave.parse(solved.state).value("SOLVE")
        assertEquals("SOLVE record", "S2,0,1,2", solveRecord)
        assertEquals("Kotlin solve string", solveRecord, Flood.solve(state))
        assertTrue("the solve adds one history entry", solved.canUndo)
        assertEquals("the solve does not change the status", 0, solved.status)

        val executed = Flood.executeMove(state, solveRecord!!) as Flood.State
        assertTrue("an S move cheats", executed.cheated)
        assertEquals("an S move does not move", 0, executed.moves)
        assertFalse("an S move does not complete", executed.complete)
        assertEquals("S2,0,1,2", solveRecord)
        assertEquals(
            "Auto-solver used. 0 / 4 moves",
            traceStatus(oracle, solved.state),
        )
        assertEquals(
            "Auto-solver used. 0 / 4 moves",
            Flood.statusText(executed, Flood.newUi(executed)),
        )

        // Solving a completed state fails in C; the seam has no error
        // channel, so Kotlin returns null.
        val completeSave = saveText(
            "4x4c3m0",
            "1112101220200112,4",
            listOf("M2", "M0", "M1", "M2"),
            null,
        )
        try {
            oracle.solve(completeSave)
            throw AssertionError("the oracle solved a completed state")
        } catch (e: OracleException) {
            assertEquals("Puzzle is already solved", e.message)
        }
        val complete = Midend.fromSave(Flood, SgtpSave.parse(completeSave))
        assertEquals(null, Flood.solve(complete.state))

        // The move sequence, not only its length, is part of the
        // contract. Compare the whole S string for every preset.
        for (params in Flood.presets().map { it.params }.distinct()) {
            val generated = oracle.newGame("flood", params, SEED)
            val solveRecord = SgtpSave.parse(oracle.solve(generated.state).state)
                .value("SOLVE")
            val kotlin = Midend.newGame(Flood, params, SEED)
            assertEquals(
                "solve string flood '$params'",
                solveRecord,
                Flood.solve(kotlin.state),
            )
            assertTrue("a generated game needs a solution", solveRecord!!.isNotEmpty())
        }

        // `solve_game` reads `colours` from the state, not from the
        // parameters. This description uses two colours although the
        // parameters allow six.
        val lowSave = saveText("2x2c6m0", "0101,3", emptyList(), null)
        val lowState = Flood.newState(Flood.decodeParams("2x2c6m0"), "0101,3") as Flood.State
        assertEquals(2, lowState.colours)
        val lowRecord = SgtpSave.parse(oracle.solve(lowSave).state).value("SOLVE")
        assertEquals("S1", lowRecord)
        assertEquals(lowRecord, Flood.solve(lowState))
    }

    @Test
    fun `a pointer click adds one move on both sides`() = OracleClient().use { oracle ->
        val params = "4x4c3m0"
        val save = saveText(params, "1112101220200112,4", emptyList(), null)
        val actual = Midend.fromSave(Flood, SgtpSave.parse(save))

        // Viewport 411x702 picks tile 82 and letterbox origin (0,146).
        // View (287,187) is cell (3,0), colour 2.
        val expected = oracle.pointsSized(
            save,
            listOf(OracleClient.Point(287, 187, Buttons.LEFT_BUTTON)),
            width = 411,
            height = 702,
        )
        val geometry = actual.geometryFor(411, 702)
        assertEquals(82, geometry.tileSize)
        assertEquals(0, geometry.originX)
        assertEquals(146, geometry.originY)

        val movesBefore = actual.serialise().values("MOVE").size
        assertEquals(
            KeyOutcome.SomeEffect,
            actual.processKey(
                287 - geometry.originX,
                187 - geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )
        assertEquals("the click adds one move", movesBefore + 1,
            actual.serialise().values("MOVE").size)
        assertEquals("M2", actual.serialise().values("MOVE").single())
        assertEquals("move record", "MOVE    :2:M2",
            expected.state.state.lines().first { it.startsWith("MOVE") })
        assertSame("after one pointer click", expected.state, actual)

        // The same click now names the current colour, so neither side
        // adds a move.
        val again = oracle.pointsSized(
            expected.state.state,
            listOf(OracleClient.Point(287, 187, Buttons.LEFT_BUTTON)),
            width = 411,
            height = 702,
        )
        assertEquals(movesBefore + 1,
            SgtpSave.parse(again.state.state).values("MOVE").size)
        assertEquals(
            KeyOutcome.NoEffect,
            actual.processKey(
                287 - geometry.originX,
                187 - geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            ),
        )
        assertEquals(movesBefore + 1,
            actual.serialise().values("MOVE").size)
    }

    @Test
    fun `keyboard cursor and select match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("4x4c3m0", "1112101220200112,4", emptyList(), null)
        val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
        val tile = Flood.preferredTileSize

        // Right three times, then select, fills cell (3,0), colour 2.
        val expected = oracle.keys(
            save,
            listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ),
        ).state
        for (key in listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
        )) {
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, key, tile))
        }
        assertEquals("M2", midend.serialise().values("MOVE").single())
        assertSame("after the keyboard fill", oracle.id(expected), midend)

        // Select alone, and select2 without a solution, change nothing.
        for (key in listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2)) {
            assertEquals(
                KeyOutcome.NoEffect,
                midend.processKey(0, 0, key, tile),
            )
        }
        // A right button and a drag are unused.
        for (key in listOf(Buttons.RIGHT_BUTTON, Buttons.LEFT_DRAG, Buttons.LEFT_RELEASE)) {
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, key, tile),
            )
        }
    }

    @Test
    fun `select2 advances a stored solution`() = OracleClient().use { oracle ->
        val save = saveText("4x4c3m0", "1112101220200112,4", emptyList(), "S2,0,1,2")
        val expected = oracle.id(save)
        val midend = Midend.fromSave(Flood, SgtpSave.parse(save))
        val tile = Flood.preferredTileSize

        assertEquals(2, midend.stateCount)
        assertEquals(KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT2, tile))
        assertEquals("the stored solution advances", 1,
            (midend.state as Flood.State).solnpos)
        assertEquals("M2", midend.serialise().values("MOVE").last())

        // The same key on the oracle produces the same move.
        val oracleAfter = oracle.keys(expected.state, listOf(Buttons.CURSOR_SELECT2))
        assertEquals(
            SgtpSave.parse(oracleAfter.state).values("MOVE").last(),
            midend.serialise().values("MOVE").last(),
        )
    }

    @Test
    fun `a solve then the solution completes without a completion event`() =
        OracleClient().use { oracle ->
            val base = saveText("4x4c3m0", "1112101220200112,4", emptyList(), null)
            val midend = Midend.fromSave(Flood, SgtpSave.parse(base))
            val tile = Flood.preferredTileSize

            assertFalse("a loaded game has no completion event",
                midend.takeCompletedEvent())
            assertTrue(midend.solve())
            assertTrue((midend.state as Flood.State).cheated)
            assertFalse("a solve raises no completion event",
                midend.takeCompletedEvent())

            repeat(4) {
                assertEquals(KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, Buttons.CURSOR_SELECT2, tile))
                assertFalse("a cheated completion is not reported",
                    midend.takeCompletedEvent())
            }

            assertEquals(1, midend.status())
            assertEquals("Auto-solved. 4 / 4 moves", midend.statusText())

            // The same history in C has the same status and text.
            val played = saveText(
                "4x4c3m0",
                "1112101220200112,4",
                listOf("M2", "M0", "M1", "M2"),
                "S2,0,1,2",
            )
            val expected = oracle.id(played)
            assertEquals(1, expected.status)
            assertEquals("Auto-solved. 4 / 4 moves", traceStatus(oracle, played))
            assertEquals(expected.id, midend.id())
            assertEquals(
                "the history matches the oracle",
                expected.state,
                midend.serialise().serialise(),
            )
        }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val save = saveText(
            "4x4c3m0",
            "1112101220200112,4",
            listOf("M2", "M0", "M1", "M2"),
            null,
        )
        var expected = oracle.id(save)
        val actual = Midend.fromSave(Flood, SgtpSave.parse(save))
        assertFalse(actual.takeCompletedEvent())
        assertEquals(1, actual.status())

        expected = oracle.undo(expected.state)
        assertTrue(actual.undo())
        assertSame("undo from complete", expected, actual)
        assertEquals(0, actual.status())
        assertEquals("3 / 4 moves", actual.statusText())
        assertFalse("undo leaves the completed state", actual.takeCompletedEvent())

        expected = oracle.redo(expected.state)
        assertTrue(actual.redo())
        assertSame("redo into complete", expected, actual)
        assertEquals(1, actual.status())
        assertTrue("redo enters the completed state", actual.takeCompletedEvent())
        assertFalse(actual.takeCompletedEvent())
    }

    @Test
    fun `the layout matches the oracle at four viewports`() = OracleClient().use { oracle ->
        val cases = listOf(
            Triple("12x12c6m5", 411, 702),
            Triple("12x12c6m5", 720, 1280),
            Triple("12x12c6m5", 1024, 600),
            Triple("12x12c6m5", 200, 200),
            Triple("4x4c3m0", 411, 702),
            Triple("16x16c6m0", 1024, 800),
        )
        for ((params, viewWidth, viewHeight) in cases) {
            val save = oracle.newGame("flood", params, SEED)
            val expected = oracle.layout(save.state, viewWidth, viewHeight)
            val geometry = Midend.newGame(Flood, params, SEED)
                .geometryFor(viewWidth, viewHeight)
            assertEquals(
                "width '$params' in $viewWidth x $viewHeight",
                expected.width,
                geometry.width,
            )
            assertEquals(
                "height '$params' in $viewWidth x $viewHeight",
                expected.height,
                geometry.height,
            )
        }

        // The four exact tile sizes from the test plan.
        for ((view, tile) in listOf(
            624 to 48,
            818 to 63,
            1066 to 82,
            1300 to 100,
        )) {
            val midend = Midend.newGame(Flood, "12x12c6m5", SEED)
            assertEquals("tile for $view", tile, midend.largestTileFor(view, view))
        }
    }

    private data class Crafted(
        val params: String,
        val desc: String,
        val moves: List<String>,
        val solve: String?,
    )

    private fun load(
        oracle: OracleClient,
        params: String,
        desc: String,
        moves: List<String>,
        solve: String?,
    ): Pair<GameState, Midend> {
        val save = saveText(params, desc, moves, solve)
        return oracle.id(save) to Midend.fromSave(Flood, SgtpSave.parse(save))
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
        moves: List<String>,
        solve: String?,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Flood"),
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
