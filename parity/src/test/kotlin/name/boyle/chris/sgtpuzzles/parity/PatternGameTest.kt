package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: pattern.
 *
 * New games, text, status, the loose move grammar, the `STYLUS_BASED`
 * three-way cycles on a real press and release, drags and clamping, the
 * cursor keys, a select on an immutable clue cell, the no-op win, undo
 * and redo, an honest completion and its sticky flag, the solver with
 * and without `AUXINFO`, the reachable solver failure, the request-keys
 * mode, and the vacuity guards (a real cycle changes the grid and the
 * move count; an inert click changes neither).
 */
class PatternGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24

        /** The 4x4 board of the C comment: the line solver stalls. */
        const val HARD = "2/1/2/1/1.1/2/1/1"

        /** The same board with the last column filled in by the suffix. */
        const val HARD_SUFFIX = "2/1/2/1/1.1/2/1/1,dddd"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun midend(save: String): Midend =
        Midend.fromSave(Pattern, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `new games, text and status match the oracle`() = OracleClient().use { oracle ->
        for (params in Pattern.presets().map { it.params }) {
            val expected = oracle.newGame("pattern", params, SEED)
            val actual = Midend.newGame(Pattern, params, SEED)
            assertEquals("'$params' id", expected.id, actual.id())
            assertEquals("'$params' width", expected.width, actual.size.width)
            assertEquals("'$params' height", expected.height, actual.size.height)
            assertEquals("'$params' status", 0, actual.status())
            assertEquals("'$params' text", oracle.text(expected.state), actual.textFormat())
            assertEquals("'$params' status text", "", actual.statusText())
            assertFalse("'$params' has no status bar", Pattern.wantsStatusBar)
            assertTrue("'$params' can solve", Pattern.canSolve)
            assertTrue("'$params' can format as text", Pattern.canFormatAsText)
            assertEquals("'$params' history", 1, actual.stateCount)
            assertTrue("'$params' AUXINFO", actual.serialise().value("AUXINFO") != null)
            assertFalse("'$params' no UI record", actual.serialise().records.any { it.key == "UI" })
        }

        // The hand-written boards, including the one the solver cannot
        // finish, still have the C text form.
        for (desc in listOf(HARD, HARD_SUFFIX)) {
            val save = saveText("4x4", desc)
            assertEquals("'$desc' text", oracle.text(save), midend(save).textFormat())
        }
        val two = "1/1//2,aaAA"
        val twoSave = saveText("2x2", two)
        assertEquals("'$two' text", oracle.text(twoSave), midend(twoSave).textFormat())
        assertEquals(0, midend(twoSave).status())

        // The small and non-square boards exercise the clue column
        // widths, the empty clue lists, and the copied bottom border.
        for ((params, desc) in listOf(
            "1x2" to "1//1",
            "2x1" to "/1/1",
            "1x5" to "2///1/1/",
            "3x3" to "2/1/1.1/1.1/2/1",
            "1x12" to "12/" + (0 until 12).joinToString("/") { "1" },
            "1x100" to "100/" + (0 until 100).joinToString("/") { "1" },
        )) {
            val save = saveText(params, desc)
            assertEquals("'$params' text", oracle.text(save), midend(save).textFormat())
        }
    }

    @Test
    fun `the move grammar accepts and rejects the C forms`() = OracleClient().use { oracle ->
        val cases = listOf(
            "F0,0,1,1",
            "E0,0,2,1",
            "U3,2,1,1",
            "F 0, 0, 1, 1",
            "F+0,0,1,1",
            "F-0,0,1,1",
            "F0,0,1,1junk",
            "F0,0,1,1,",
            "F0,0,0,0",
            "F4,0,1,1",
            "F0,0,-1,1",
            "F-1,0,1,1",
            "F0,0,1",
            "F0 ,0,1,1",
            "F0,0,1,1;",
            "X0,0,1,1",
            "",
            "S",
            "S;",
            "S0011",
            "S001100110011001",
            "S0011001100110011",
            "S1111111111111111",
            "S0000000000000000",
        )
        for (move in cases) {
            val save = saveText("4x4", HARD, listOf("MOVE" to move))
            val expected = try {
                oracle.id(save)
            } catch (e: OracleException) {
                null
            }
            val actual = try {
                Midend.fromSave(Pattern, SgtpSave.parse(save))
            } catch (e: RuntimeException) {
                null
            }
            if (expected == null) {
                assertNull("'$move' is rejected", actual)
            } else {
                assertSame("'$move'", expected, requireNotNull(actual))
            }
        }

        // `S` of exactly w*h+1 characters wins and marks the state as
        // cheated, so status is 1.
        val solved = saveText("4x4", HARD, listOf("MOVE" to "S0011001100110011"))
        assertEquals(1, oracle.id(solved).status)
        val solvedMidend = midend(solved)
        assertEquals(1, solvedMidend.status())
        assertTrue((solvedMidend.state as Pattern.State).cheated)

        // A wrong-length S is rejected even though it is a valid string.
        assertNull(Pattern.executeMove(Pattern.newState(Pattern.decodeParams("4x4"), HARD), "S0011"))
    }

    @Test
    fun `the three-way cycles and the drags match the oracle`() =
        OracleClient().use { oracle ->
            val save = saveText("4x4", HARD)

            // The left cycle: unknown -> filled -> empty -> unknown.
            var expected = save
            val left = midend(save)
            for ((round, move) in listOf("F0,0,1,1", "E0,0,1,1", "U0,0,1,1").withIndex()) {
                expected = play(
                    oracle,
                    "left $round",
                    expected,
                    click(0, 0, Buttons.LEFT_BUTTON),
                    left,
                )
                assertEquals("left round $round record", listOf(move), lastMove(expected))
            }
            assertEquals(3, left.serialise().values("MOVE").size)

            // The right cycle: unknown -> empty -> filled -> unknown.
            val right = midend(save)
            var expectedRight = save
            for ((round, move) in listOf("E0,0,1,1", "F0,0,1,1", "U0,0,1,1").withIndex()) {
                expectedRight = play(
                    oracle,
                    "right $round",
                    expectedRight,
                    click(0, 0, Buttons.RIGHT_BUTTON),
                    right,
                )
                assertEquals("right round $round record", listOf(move), lastMove(expectedRight))
            }

            // A press alone is UI only; the release is the move.
            val pressOnly = midend(save)
            val point = click(0, 0, Buttons.LEFT_BUTTON).first()
            assertEquals(
                KeyOutcome.SomeEffect,
                pressOnly.processKey(point.x, point.y, point.button, TILE),
            )
            assertEquals(1, pressOnly.stateCount)
            assertTrue(pressOnly.serialise().values("MOVE").isEmpty())

            // A left drag snaps to the dominant axis and clamps.
            val drag = midend(save)
            val dragged = play(
                oracle,
                "left drag",
                save,
                listOf(
                    OracleClient.Point(px(0), py(0), Buttons.LEFT_BUTTON),
                    OracleClient.Point(px(2), py(1), Buttons.LEFT_DRAG),
                    OracleClient.Point(px(2), py(1), Buttons.LEFT_RELEASE),
                ),
                drag,
            )
            assertEquals("snapped drag record", listOf("F0,0,3,1"), lastMove(dragged))

            val clamp = midend(save)
            val clamped = play(
                oracle,
                "clamped drag",
                save,
                listOf(
                    OracleClient.Point(px(0), py(0), Buttons.LEFT_BUTTON),
                    OracleClient.Point(px(0) + 10 * TILE, py(0), Buttons.LEFT_DRAG),
                    OracleClient.Point(px(0) + 10 * TILE, py(0), Buttons.LEFT_RELEASE),
                ),
                clamp,
            )
            assertEquals("clamped drag record", listOf("F0,0,4,1"), lastMove(clamped))

            // A middle drag does not snap and can mark a rectangle
            // unknown. Fill two cells first.
            val middle = midend(save)
            var middleSave = save
            middleSave = play(oracle, "fill", middleSave, click(0, 0, Buttons.LEFT_BUTTON), middle)
            middleSave = play(oracle, "fill2", middleSave, click(1, 0, Buttons.LEFT_BUTTON), middle)
            middleSave = play(
                oracle,
                "middle drag",
                middleSave,
                listOf(
                    OracleClient.Point(px(0), py(0), Buttons.MIDDLE_BUTTON),
                    OracleClient.Point(px(1), py(1), Buttons.MIDDLE_DRAG),
                    OracleClient.Point(px(1), py(1), Buttons.MIDDLE_RELEASE),
                ),
                middle,
            )
            assertEquals("middle drag record", listOf("U0,0,2,2"), lastMove(middleSave))
        }

    @Test
    fun `a select on an immutable cell adds a state with no visible change`() =
        OracleClient().use { oracle ->
            val save = saveText("4x4", HARD_SUFFIX)
            val expected = oracle.keys(save, listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            ))
            val actual = midend(save)
            for (key in listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            )) {
                actual.processKey(0, 0, key, TILE)
            }
            assertSame("select on an immutable cell", expected, actual)
            assertEquals(2, actual.stateCount)
            assertEquals(listOf("U3,0,1,1"), actual.serialise().values("MOVE"))
            assertEquals(0, actual.status())
            assertEquals(Pattern.GRID_EMPTY, (actual.state as Pattern.State).grid[3])
            assertTrue((actual.state as Pattern.State).common.immutable[3])
        }

    @Test
    fun `the no-op win is reachable from a matching suffix`() = OracleClient().use { oracle ->
        val save = saveText("2x2", "1/1//2,aaAA")
        assertEquals(0, oracle.id(save).status)

        // The first select reveals the hidden cursor; the second turns
        // the empty immutable cell into a full one in a move record, but
        // the cell does not change. The completion test then runs.
        val sequence = listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT)
        val expected = oracle.keys(save, sequence)
        assertEquals(1, expected.status)
        val actual = midend(save)
        for (key in sequence) actual.processKey(0, 0, key, TILE)
        assertSame("no-op win", expected, actual)
        assertEquals(1, actual.status())
        assertEquals(listOf("U0,0,1,1"), actual.serialise().values("MOVE"))
        assertEquals(Pattern.GRID_EMPTY, (actual.state as Pattern.State).grid[0])
        assertTrue(actual.takeCompletedEvent())
        assertFalse(actual.takeCompletedEvent())

        // A zero-size move wins as well, with no cell change at all.
        assertTrue(Pattern.executeMove(Pattern.newState(Pattern.decodeParams("2x2"), "1/1//2,aaAA"), "F0,0,0,0")!!.let {
            Pattern.status(it) == 1
        })
    }

    @Test
    fun `a real win completes once, stays sticky, undoes and redoes`() =
        OracleClient().use { oracle ->
            val save = saveText("2x2", "1/1//2")
            val start = midend(save)
            assertEquals(0, start.status())

            // Fill the two cells of row 1, then mark row 0 empty.
            var expected = save
            val steps = listOf(
                click(0, 1, Buttons.LEFT_BUTTON) to "F0,1,1,1",
                click(1, 1, Buttons.LEFT_BUTTON) to "F1,1,1,1",
                click(0, 0, Buttons.RIGHT_BUTTON) to "E0,0,1,1",
                click(1, 0, Buttons.RIGHT_BUTTON) to "E1,0,1,1",
            )
            for ((index, step) in steps.withIndex()) {
                val (points, move) = step
                expected = play(oracle, "win step $index", expected, points, start)
                assertEquals("win step $index record", listOf(move), lastMove(expected))
                assertEquals(
                    "win step $index status",
                    if (index == steps.lastIndex) 1 else 0,
                    start.status(),
                )
            }
            assertTrue("the win raises the event", start.takeCompletedEvent())
            assertFalse(start.takeCompletedEvent())

            // One more click leaves status 1 and raises no event.
            val after = oracle.pointsSized(expected, click(0, 0, Buttons.LEFT_BUTTON)).state
            assertEquals(1, after.status)
            for (point in click(0, 0, Buttons.LEFT_BUTTON)) {
                start.processKey(point.x, point.y, point.button, TILE)
            }
            assertSame("post-win move", after, start)
            assertEquals(1, start.status())
            assertFalse("a post-win move raises no event", start.takeCompletedEvent())

            // Undo across the win clears the status; redo raises the
            // event again.
            assertTrue(start.undo())
            assertEquals(1, start.status())
            assertTrue(start.undo())
            assertEquals(0, start.status())
            assertTrue(start.redo())
            assertEquals(1, start.status())
            assertTrue("a redo into the win raises the event", start.takeCompletedEvent())

            // A restart rebuilds the initial state.
            assertTrue(start.restart())
            assertEquals(0, start.status())
            assertTrue(start.canUndo)
            assertFalse(start.canRedo)
            assertEquals(1, start.serialise().values("RESTART").size)
        }

    @Test
    fun `the solver works with and without AUXINFO`() = OracleClient().use { oracle ->
        for (params in Pattern.presets().map { it.params }) {
            val oracleNew = oracle.newGame("pattern", params, SEED)
            val expected = oracle.solve(oracleNew.state)
            val expectedMove = SgtpSave.parse(expected.state).values("SOLVE").single()
            assertEquals("'$params' oracle status", 1, expected.status)

            val withAux = Midend.newGame(Pattern, params, SEED)
            val aux = requireNotNull(withAux.auxInfo)
            assertEquals("'$params' aux is the solve", aux, expectedMove)
            assertTrue("'$params' solve", withAux.solve())
            assertEquals("'$params' status", 1, withAux.status())
            assertEquals(
                "'$params' SOLVE",
                expectedMove,
                withAux.serialise().values("SOLVE").single(),
            )
            assertFalse("'$params' solve raises no event", withAux.takeCompletedEvent())

            // Without `AUXINFO` the line solver recomputes the same
            // answer, and a played state still gives the same move.
            val withoutAux = Midend.fromGameId(Pattern, withAux.id())
            assertTrue("'$params' aux-less solve", withoutAux.solve())
            assertEquals(expectedMove, withoutAux.serialise().values("SOLVE").single())
            assertEquals("'$params' aux-less id", expected.id, withoutAux.id())
            assertEquals("'$params' aux-less status", expected.status, withoutAux.status())

            val played = Midend.fromGameId(Pattern, withAux.id())
            applyClick(played, 0, 0, Buttons.LEFT_BUTTON)
            assertTrue("'$params' played solve", played.solve())
            assertEquals(expectedMove, played.serialise().values("SOLVE").single())
        }

        // The reachable failure: a hand-written board that one line at a
        // time cannot finish. C reports the message; the seam returns
        // null because it has no error channel.
        val hardSave = saveText("4x4", HARD)
        val error = try {
            oracle.solve(hardSave)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Solving algorithm cannot complete this puzzle", error)
        val hard = Midend.fromGameId(Pattern, "4x4:$HARD")
        assertNull(Pattern.solve(hard.state, null))
        assertFalse(hard.solve())
        assertEquals(0, hard.status())
        assertTrue(hard.serialise().values("SOLVE").isEmpty())
    }

    @Test
    fun `requestKeys is arrow mode 3 with no labels`() = OracleClient().use { oracle ->
        for (params in Pattern.presets().map { it.params }) {
            val state = oracle.newGame("pattern", params, SEED).state
            for (fullCursor in listOf(false, true)) {
                val expected = oracle.requestKeys(state, fullCursor)
                val actual = Pattern.requestKeys(Pattern.decodeParams(params), fullCursor)
                assertEquals(
                    "'$params' $fullCursor arrow mode",
                    if (fullCursor) 1 else 3,
                    expected.arrowMode,
                )
                assertEquals(
                    "'$params' $fullCursor Kotlin",
                    if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
                    actual.arrowMode,
                )
                assertTrue("'$params' $fullCursor labels", expected.labels.isEmpty())
                assertTrue("'$params' $fullCursor Kotlin labels", actual.labels.isEmpty())
            }
        }
    }

    @Test
    fun `a real cycle changes the grid and the move count and an inert click does not`() =
        OracleClient().use { oracle ->
            val save = saveText("4x4", HARD)

            val cycle = midend(save)
            val before = (cycle.state as Pattern.State).grid.copyOf()
            for ((index, point) in click(0, 0, Buttons.LEFT_BUTTON).withIndex()) {
                val outcome = cycle.processKey(point.x, point.y, point.button, TILE)
                if (index == 0) assertEquals("cycle outcome", KeyOutcome.SomeEffect, outcome)
            }
            val after = cycle.state as Pattern.State
            assertFalse("the grid changes", before.contentEquals(after.grid))
            assertEquals(2, cycle.stateCount)
            assertEquals(listOf("F0,0,1,1"), cycle.serialise().values("MOVE"))
            assertSame("cycle", oracle.pointsSized(save, click(0, 0, Buttons.LEFT_BUTTON)).state, cycle)

            // An inert click in the clue band touches nothing. The band
            // starts at TOCOORD(0)-1.
            val inert = midend(save)
            val inertGrid = (inert.state as Pattern.State).grid.copyOf()
            val clueBand = listOf(
                OracleClient.Point(px(0) - TILE / 2 - 1, py(0), Buttons.LEFT_BUTTON),
                OracleClient.Point(px(0) - TILE / 2 - 1, py(0), Buttons.LEFT_RELEASE),
            )
            for (point in clueBand) {
                assertEquals(
                    "inert outcome",
                    KeyOutcome.Unused,
                    inert.processKey(point.x, point.y, point.button, TILE),
                )
            }
            assertTrue((inert.state as Pattern.State).grid.contentEquals(inertGrid))
            assertEquals(1, inert.stateCount)
            assertTrue(inert.serialise().values("MOVE").isEmpty())
            assertSame("inert", oracle.pointsSized(save, clueBand).state, inert)

            // A middle press on an unknown cell is UI only: the target
            // value is already unknown.
            val middle = midend(save)
            for (point in listOf(
                OracleClient.Point(px(1), py(1), Buttons.MIDDLE_BUTTON),
                OracleClient.Point(px(1), py(1), Buttons.MIDDLE_RELEASE),
            )) {
                assertEquals(
                    "middle outcome",
                    KeyOutcome.SomeEffect,
                    middle.processKey(point.x, point.y, point.button, TILE),
                )
            }
            assertEquals(1, middle.stateCount)

            // A release without a press is inert.
            val release = midend(save)
            assertEquals(
                "release without a press",
                KeyOutcome.Unused,
                release.processKey(px(0), py(0), Buttons.LEFT_RELEASE, TILE),
            )
            assertEquals(1, release.stateCount)
            assertTrue(release.serialise().values("MOVE").isEmpty())
        }

    @Test
    fun `cursor keys and the two select buttons match the oracle`() =
        OracleClient().use { oracle ->
            val save = saveText("4x4", HARD)
            val sequences = listOf(
                listOf(Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_LEFT, Buttons.CURSOR_LEFT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT),
                listOf(Buttons.MOD_CTRL or Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_SELECT, '1'.code, '\b'.code),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val actual = midend(save)
                for (key in sequence) actual.processKey(0, 0, key, TILE)
                assertSame("keys $sequence", expected, actual)
            }

            // A click hides the cursor; the next cursor key reveals it.
            val hidden = midend(save)
            hidden.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
            hidden.processKey(px(0), py(1), Buttons.LEFT_BUTTON, TILE)
            hidden.processKey(px(0), py(1), Buttons.LEFT_RELEASE, TILE)
            assertEquals(2, hidden.stateCount)
        }

    private fun click(x: Int, y: Int, button: Int): List<OracleClient.Point> {
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> Buttons.MIDDLE_RELEASE
        }
        return listOf(
            OracleClient.Point(px(x), py(y), button),
            OracleClient.Point(px(x), py(y), release),
        )
    }

    /** Applies one real press and release to [midend]. */
    private fun applyClick(midend: Midend, x: Int, y: Int, button: Int) {
        for (point in click(x, y, button)) {
            midend.processKey(point.x, point.y, point.button, TILE)
        }
    }

    /** `TOCOORD(w, x)` plus half a tile at tile 24 for the 4x4 board. */
    private fun px(x: Int): Int = 3 * TILE / 4 + TILE / 2 + TILE * (4 / 5 + 2 + x) + TILE / 2

    private fun py(y: Int): Int = 3 * TILE / 4 + TILE / 2 + TILE * (4 / 5 + 2 + y) + TILE / 2

    private fun play(
        oracle: OracleClient,
        label: String,
        save: String,
        points: List<OracleClient.Point>,
        midend: Midend,
    ): String {
        val expected = oracle.pointsSized(save, points)
        for ((index, point) in points.withIndex()) {
            val outcome = midend.processKey(point.x, point.y, point.button, TILE)
            if (index == 0) assertEquals("$label outcome", KeyOutcome.SomeEffect, outcome)
        }
        assertSame(label, expected.state, midend)
        return expected.state.state
    }

    private fun lastMove(save: String): List<String> =
        SgtpSave.parse(save).values("MOVE").takeLast(1)

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Pattern.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }
}
