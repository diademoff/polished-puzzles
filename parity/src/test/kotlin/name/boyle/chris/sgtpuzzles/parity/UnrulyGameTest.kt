package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: unruly.
 *
 * New games, text, status, the move grammar, the solver, completion, undo
 * and redo, restart, and the real pointer path are compared with the
 * oracle.
 *
 * The solve is run on the decoded first state, so the after-click case is
 * the one that would catch a port that solves the current board.
 */
class UnrulyGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    @Test
    fun `new games, text, and status match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("8x8dt", "8x8de", "8x8dn", "8x8udt", "6x6dt", "10x10de", "14x14dn")) {
            for (seed in listOf("12345", "1", "seed")) {
                val expected = oracle.newGame("unruly", params, seed)
                val actual = Midend.newGame(Unruly, params, seed)
                assertSame("unruly '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text unruly '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
                assertEquals("status text '$params'", "", actual.statusText())
                assertFalse("no status bar", Unruly.wantsStatusBar)
                assertNullStatusLine(oracle, expected.state)
            }
        }

        // The reference board from the source comment, byte for byte,
        // including the trailing space and the final newline.
        val eight = Unruly.newState(Unruly.decodeParams("8x8dt"), DESC_8X8)
        assertEquals(TEXT_8X8, Unruly.textFormat(eight))
    }

    @Test
    fun `requested keys match the oracle`() = OracleClient().use { oracle ->
        for (preset in Unruly.presets()) {
            val game = oracle.newGame("unruly", preset.params, SEED)
            val expected = oracle.requestKeys(game.state)
            val actual = Unruly.requestKeys(Unruly.decodeParams(preset.params))

            assertEquals("arrow mode for '${preset.params}'", 3, expected.arrowMode)
            assertEquals(
                "Kotlin arrow mode",
                ArrowMode.ARROWS_LEFT_RIGHT,
                actual.arrowMode,
            )
            assertEquals("C labels", emptyList<OracleClient.KeyLabel>(), expected.labels)
            assertTrue("no labels", actual.labels.isEmpty())
        }
    }

    @Test
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "P1,0,0",
            "P1,0,0junk",
            "P1, 0,0",
            "P1,+0,0",
            "P1,00,0",
            "P0,0,0",
            "P-,0,0",
            "P1,2,7",
            "P1,2,7 ",
            "P1,2,7x",
            "P1,4294967296,0",
            "P1,0,4294967296",
            "P1,-4294967296,0",
            "P1,-99999999999999999999,0",
            "P1,0,0;",
            "S" + "0".repeat(64),
            "S" + "1".repeat(64),
            "S" + "0".repeat(65),
            "S" + "0".repeat(64) + "junk",
        )
        for (move in accepted) {
            val save = saveText("8x8dt", DESC_8X8, listOf(move))
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Unruly, SgtpSave.parse(save))
            assertEquals("'$move' id", expected.id, actual.id())
            assertEquals("'$move' status", expected.status, actual.status())
            assertEquals("'$move' text", oracle.text(save), actual.textFormat())
            assertEquals(
                "'$move' move text is stored verbatim",
                listOf(move),
                actual.serialise().values("MOVE"),
            )
        }

        val rejected = listOf(
            "P1,0,7", "P1,0,8", "P1,0,-1", "P1,-1,0",
            "P2,0,0", "Px,0,0", "P0,1,0", "P-,3,3",
            "P", "P1", "P1,", "P1,0", "P1,0,",
            "P 1,0,0", "P,0,0", "P1;0,0", "P+1,0,0",
            "P1,99999999999999999999,0",
            "", "X", "S", "SS" + "0".repeat(63),
            "S" + "0".repeat(63), "S" + "2".repeat(64),
        )
        for (move in rejected) {
            val save = saveText("8x8dt", DESC_8X8, listOf(move))
            assertNotNull(
                "'$move' must be rejected by C",
                runCatching { oracle.id(save) }.exceptionOrNull(),
            )
            val actual = runCatching {
                Midend.fromSave(Unruly, SgtpSave.parse(save))
            }
            assertTrue("'$move' must be rejected", actual.isFailure)
        }

        // A duplicated move appends two states on both sides.
        val twice = Midend.fromSave(
            Unruly,
            SgtpSave.parse(saveText("8x8dt", DESC_8X8, listOf("P1,0,0", "P1,0,0"))),
        )
        assertEquals(3, twice.stateCount)
        assertEquals(listOf("P1,0,0", "P1,0,0"), twice.serialise().values("MOVE"))
        assertTrue((twice.state as Unruly.State).grid[0] == 1)
    }

    @Test
    fun `every preset solves to the oracle answer`() = OracleClient().use { oracle ->
        for (preset in Unruly.presets()) {
            val generated = oracle.newGame("unruly", preset.params, SEED)
            val expectedSolve = SgtpSave.parse(oracle.solve(generated.state).state)
                .value("SOLVE")!!
            val midend = Midend.newGame(Unruly, preset.params, SEED)
            val solved = Unruly.solve(midend.state)

            assertEquals("solve '${preset.params}'", expectedSolve, solved)
            assertEquals("solve length", (midend.state as Unruly.State).grid.size + 1, solved!!.length)
            assertTrue("S prefix", solved.startsWith("S"))
            assertTrue("only digits", solved.drop(1).all { it == '0' || it == '1' })

            assertTrue(midend.solve())
            assertEquals("status after solve", 1, midend.status())
            assertEquals("cheated", true, (midend.state as Unruly.State).cheated)
            assertFalse("a solve raises no completion event", midend.takeCompletedEvent())
            assertEquals(
                "save after solve",
                oracle.solve(generated.state).state,
                midend.serialise().serialise(),
            )
        }
    }

    @Test
    fun `a stalled or invalid board returns null and C names the error`() =
        OracleClient().use { oracle ->
            for ((desc, message) in listOf(
                "zzo" to "No solution found.",
                "AAAzzl" to "Puzzle is invalid.",
            )) {
                val save = saveText("8x8dt", desc, emptyList())
                val error = runCatching { oracle.solve(save) }.exceptionOrNull()
                assertNotNull("C must reject '$desc'", error)
                assertEquals("C error for '$desc'", message, (error as OracleException).message)

                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                assertNull("Kotlin solve for '$desc'", Unruly.solve(midend.state))
                assertFalse("the failed solve is not applied", midend.solve())
                assertEquals("a failed solve adds no state", 1, midend.stateCount)
            }
        }

    @Test
    fun `an already solved board solves to itself`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_8X8, emptyList())
        val solvedSave = oracle.solve(save)
        val solved = Midend.fromSave(Unruly, SgtpSave.parse(solvedSave.state))

        assertEquals(1, solved.status())
        assertEquals(SOLVE_8X8, Unruly.solve(solved.state))
        assertEquals(
            "the answer is the same board",
            SgtpSave.parse(solvedSave.state).value("SOLVE"),
            Unruly.solve(solved.state),
        )
    }

    @Test
    fun `a solve reads the decoded initial grid after a click`() = OracleClient().use { oracle ->
        // One right click at cell (0,0) writes a 0 that contradicts the
        // answer. C still reads `states[0]`, so the SOLVE record is
        // identical to the fresh one and the click is overwritten.
        val save = saveText("8x8dt", DESC_8X8, emptyList())
        val expected = oracle.pointsSized(
            save,
            listOf(OracleClient.Point(24, 24, Buttons.RIGHT_BUTTON)),
            width = 432,
            height = 432,
        )
        assertEquals(
            "the click makes a contradicting move",
            listOf("P0,0,0"),
            SgtpSave.parse(expected.state.state).values("MOVE"),
        )
        val solvedAfterClick = oracle.solve(expected.state.state)
        val expectedSolve = SgtpSave.parse(solvedAfterClick.state)
        assertEquals(
            "the answer ignores the click",
            SgtpSave.parse(oracle.solve(save).state).value("SOLVE"),
            expectedSolve.value("SOLVE"),
        )

        val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 24, Buttons.RIGHT_BUTTON, 48))
        assertEquals(listOf("P0,0,0"), midend.serialise().values("MOVE"))
        assertEquals(
            "the answer is the initial grid's",
            expectedSolve.value("SOLVE"),
            Unruly.solve(midend.state),
        )
        assertTrue(midend.solve())
        assertEquals("save after solve", solvedAfterClick.state, midend.serialise().serialise())
        assertEquals("status", 1, midend.status())
    }

    @Test
    fun `completion, undo, redo, and restart match the oracle`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_ONE_EMPTY, emptyList())
        val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
        val tile = 32

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(16, 16, Buttons.LEFT_BUTTON, tile))
        assertEquals(1, midend.status())
        assertTrue("the final move raises the event", midend.takeCompletedEvent())

        val completed = oracle.id(midend.serialise().serialise())
        assertEquals("completed id", completed.id, midend.id())
        assertEquals("completed status", 1, completed.status)
        assertTrue((midend.state as Unruly.State).completed)
        assertFalse((midend.state as Unruly.State).cheated)

        val undone = oracle.undo(completed.state)
        assertTrue(midend.undo())
        assertEquals("undo id", undone.id, midend.id())
        assertEquals("undo status", 0, midend.status())
        assertFalse("undo leaves the completed state", midend.takeCompletedEvent())

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertEquals("redo id", redone.id, midend.id())
        assertEquals("redo status", 1, midend.status())
        assertTrue("redo raises the event again", midend.takeCompletedEvent())

        // A restart appends the description and returns to status 0.
        assertTrue(midend.restart())
        assertEquals(0, midend.status())
        assertEquals(
            "restart record",
            listOf(DESC_ONE_EMPTY),
            midend.serialise().values("RESTART"),
        )
        val restartSave = historySave(
            listOf("MOVE" to "P1,0,0", "RESTART" to DESC_ONE_EMPTY),
            statePos = 3,
        )
        val expectedRestart = oracle.id(restartSave)
        assertEquals("restart id", expectedRestart.id, midend.id())
        assertEquals("restart status", expectedRestart.status, 0)
        assertEquals("restart text", oracle.text(restartSave), midend.textFormat())
        assertEquals(
            "restart canonical text",
            restartSave,
            midend.serialise().serialise(),
        )
    }

    @Test
    fun `real clicks drive the 8x8 board to completion`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_8X8, emptyList())
        val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
        val state = midend.state as Unruly.State
        val tile = 48
        val view = 9 * tile
        val points = ArrayList<OracleClient.Point>()
        val cells = ArrayList<Int>()

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val index = y * 8 + x
                if (state.shared.immutable[index]) continue
                cells += index
                val button = if (SOLVE_8X8[index + 1] == '1') {
                    Buttons.LEFT_BUTTON
                } else {
                    Buttons.RIGHT_BUTTON
                }
                points += OracleClient.Point(
                    x * tile + tile / 2,
                    y * tile + tile / 2,
                    button,
                )
            }
        }
        assertEquals("45 non-clue cells", 45, cells.size)

        var lastStatus = 0
        for (step in points.indices) {
            val expected = oracle.pointsSized(
                save,
                points.subList(0, step + 1),
                width = view,
                height = view,
            )
            val outcome = midend.processKey(points[step].x, points[step].y, points[step].button, tile)
            assertEquals("step $step outcome", KeyOutcome.SomeEffect, outcome)
            assertEquals("step $step save", expected.state.state, midend.serialise().serialise())
            assertEquals("step $step status", expected.state.status, midend.status())
            assertEquals("step $step text", oracle.text(expected.state.state), midend.textFormat())
            assertEquals("step $step moves", step + 1, midend.serialise().values("MOVE").size)

            if (step < points.size - 1) {
                assertEquals("step $step is open", 0, midend.status())
            }
            lastStatus = midend.status()
        }
        assertEquals("the last click completes the game", 1, lastStatus)
        assertTrue("one completion event", midend.takeCompletedEvent())
        assertFalse("only one completion event", midend.takeCompletedEvent())

        // One more move keeps the flag sticky.
        val completedSave = midend.serialise().serialise()
        val extra = oracle.pointsSized(
            completedSave,
            listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
            width = view,
            height = view,
        )
        midend.processKey(0, 0, Buttons.LEFT_BUTTON, tile)
        assertEquals("post-win status", 1, midend.status())
        assertEquals("post-win save", extra.state.state, midend.serialise().serialise())
        assertFalse("no second completion event", midend.takeCompletedEvent())
    }

    @Test
    fun `clicks outside the board, on a clue, and a middle no-op change nothing`() =
        OracleClient().use { oracle ->
            val save = saveText("8x8dt", DESC_8X8, emptyList())
            val tile = 48
            val noMovePoints = listOf(
                // Left of the half-tile margin, and the border row.
                OracleClient.Point(23, 23, Buttons.LEFT_BUTTON),
                OracleClient.Point(23, 24, Buttons.LEFT_BUTTON),
                OracleClient.Point(24, 23, Buttons.LEFT_BUTTON),
                // A clue cell at (1,0).
                OracleClient.Point(72, 24, Buttons.LEFT_BUTTON),
                // The last cell of the last row is empty, but x == w2 one
                // pixel later is outside the grid.
                OracleClient.Point(407, 24, Buttons.LEFT_BUTTON),
                OracleClient.Point(408, 24, Buttons.LEFT_BUTTON),
                OracleClient.Point(431, 431, Buttons.LEFT_BUTTON),
                // A middle click on the empty cell (1,1): the target is
                // `-` and the cell is already empty.
                OracleClient.Point(100, 100, Buttons.MIDDLE_BUTTON),
            )
            for (point in noMovePoints) {
                // Each point starts from a fresh midend, because a click
                // changes the Android cursor and the next point would
                // otherwise see it.
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(save))
                val expected = oracle.pointsSized(
                    save,
                    listOf(point),
                    width = 9 * tile,
                    height = 9 * tile,
                )
                assertEquals(
                    "C does not move for ${point.x},${point.y}",
                    save,
                    expected.state.state,
                )
                midend.processKey(point.x, point.y, point.button, tile)
                assertEquals(
                    "no move for ${point.x},${point.y}",
                    1,
                    midend.stateCount,
                )
                assertEquals(
                    "no save change for ${point.x},${point.y}",
                    save,
                    midend.serialise().serialise(),
                )
            }

            // The exact cell corner is the first pixel of cell (0,0).
            val corner = Midend.fromSave(Unruly, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.SomeEffect,
                corner.processKey(24, 24, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(listOf("P1,0,0"), corner.serialise().values("MOVE"))
        }

    @Test
    fun `a warm click frame is empty on both sides`() = OracleClient().use { oracle ->
        val save = saveText("8x8dt", DESC_8X8, emptyList())

        val one = listOf(OracleClient.Point(24, 24, Buttons.LEFT_BUTTON))
        val expectedOne = oracle.traceWarm(save, one, width = 432, height = 432)
        val midendOne = Midend.fromSave(Unruly, SgtpSave.parse(save))
        val actualOne = KotlinTraceSink.warmTrace(midendOne, one, 48)
        assertTrace("warm one click", expectedOne.trace, actualOne)
        assertEquals(
            "the input already drew the click",
            listOf("start_draw", "end_draw"),
            opNames(actualOne),
        )
        assertEquals(listOf("P1,0,0"), midendOne.serialise().values("MOVE"))

        val two = one + OracleClient.Point(24, 24, Buttons.RIGHT_BUTTON)
        val expectedTwo = oracle.traceWarm(save, two, width = 432, height = 432)
        val midendTwo = Midend.fromSave(Unruly, SgtpSave.parse(save))
        val actualTwo = KotlinTraceSink.warmTrace(midendTwo, two, 48)
        assertTrace("warm two clicks", expectedTwo.trace, actualTwo)
        assertEquals(
            "the input already drew both clicks",
            listOf("start_draw", "end_draw"),
            opNames(actualTwo),
        )
        assertEquals(listOf("P1,0,0", "P-,0,0"), midendTwo.serialise().values("MOVE"))
    }

    private fun opNames(trace: JsonValue.Obj): List<String> =
        (trace.field("ops") as JsonValue.Arr).items.map {
            ((it as JsonValue.Obj).fields["op"] as JsonValue.Str).value
        }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        val e = TraceNormalizer.normalize(expected, upstream).lines()
        val a = TraceNormalizer.normalize(actual, upstream).lines()
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

    private fun assertNullStatusLine(oracle: OracleClient, save: String) {
        val ops = (oracle.trace(save).field("ops") as JsonValue.Arr).items
        assertTrue(
            "unruly draws no status line",
            ops.none { ((it as JsonValue.Obj).fields["op"] as JsonValue.Str).value == "status" },
        )
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String>,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Unruly.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private fun historySave(
        history: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Unruly.name),
            SaveRecord("PARAMS", "8x8dt"),
            SaveRecord("CPARAMS", "8x8dt"),
            SaveRecord("DESC", DESC_ONE_EMPTY),
            SaveRecord("NSTATES", (1 + history.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in history) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_8X8 = "bEACHcBdBDEalcbAbbBa"
        const val DESC_ONE_EMPTY =
            "BaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAAAaaAAaaaaAAaaAAa"
        const val SOLVE_8X8 =
            "S1001001101101100001100111100110011010010001101011010110001001011"
        const val TEXT_8X8 =
            ". 0 . . . . 1 1 \n" +
                ". . 1 . . . . . \n" +
                ". . 1 . . 0 . 1 \n" +
                ". . . 0 . 1 . . \n" +
                ". 1 . . . . 1 0 \n" +
                ". . . . . . . . \n" +
                ". . . 0 . . 0 . \n" +
                "0 1 . 0 . 0 . 1 \n"
    }
}
