package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: samegame.
 *
 * New games, text, status text, selection, group removal, score,
 * gravity, completion, impossibility, requested keys, and undo/redo are
 * compared with the oracle.
 *
 * A pointer removal needs two taps. This test drives the Kotlin pointer
 * path and compares each result with the oracle's keyboard path for the
 * same cell (cursor moves plus two selects), so the two paths are
 * cross-checked. `SameGameInputParityTest` compares the taps directly:
 * the oracle's `pointsSized` operation replays both taps inside one
 * midend, and its final redraw carries the selection status text.
 */
class SameGameGameTest {

    @Test
    fun `new games and text match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "5x5c3s2", "10x5c3s2", "2x2c3s2", "3x1c3s2", "5x5c3s1", "5x5c3s2r",
        )) {
            for (seed in listOf("1", "12345", "seed")) {
                val expected = oracle.newGame("samegame", params, seed)
                val actual = Midend.newGame(SameGame, params, seed)
                assertSame("samegame '$params' seed=$seed", expected, actual)
                assertEquals(
                    "text '$params' seed=$seed",
                    oracle.text(expected.state),
                    actual.textFormat(),
                )
            }
        }
    }

    @Test
    fun `requested keys match the oracle`() = OracleClient().use { oracle ->
        val state = oracle.newGame("samegame", "5x5c3s2", SEED)
        val expected = oracle.requestKeys(state.state)
        val actual = SameGame.requestKeys(SameGame.decodeParams("5x5c3s2"))

        assertEquals("arrow mode", arrowMode(expected.arrowMode), actual.arrowMode)
        assertEquals(
            "key labels",
            expected.labels.map { it.label },
            actual.labels.map { it.name },
        )
        assertTrue("C requests no extra keys", expected.labels.isEmpty())
        assertTrue("Kotlin requests no extra keys", actual.labels.isEmpty())
    }

    @Test
    fun `the board layout matches the oracle at odd and even tile sizes`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                Triple("5x5c3s2", 285, 285),
                Triple("5x5c3s2", 373, 373),
                Triple("5x5c3s2", 487, 487),
                Triple("5x5c3s2", 594, 594),
                Triple("5x5c3s2", 411, 702),
                Triple("5x5c3s2", 1024, 600),
                Triple("10x5c3s2", 411, 702),
                Triple("20x15c4s2", 1024, 800),
            )
            for ((params, viewWidth, viewHeight) in cases) {
                val save = oracle.newGame("samegame", params, SEED)
                val expected = oracle.layout(save.state, viewWidth, viewHeight)
                val geometry = Midend.newGame(SameGame, params, SEED)
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
        }

    @Test
    fun `two pointer taps remove a group and match the oracle keyboard path`() =
        OracleClient().use { oracle ->
            val params = "5x5c3s2"
            val seed = "12345"
            val tile = SameGame.preferredTileSize

            var expected = oracle.newGame("samegame", params, seed)
            val actual = Midend.newGame(SameGame, params, seed)
            var changedClicks = 0

            for (step in 0 until 8) {
                val state = actual.state as SameGame.State
                val target = firstGroup(state, 2) ?: break
                val x = target.first
                val y = target.second

                val sequence = ArrayList<Int>()
                repeat(x) { sequence += Buttons.CURSOR_RIGHT }
                repeat(y) { sequence += Buttons.CURSOR_DOWN }

                val oracleBefore = expected.state
                val kotlinBefore = actual.serialise().serialise()
                assertEquals(
                    "one select must not change the oracle save at step $step",
                    oracleBefore,
                    oracle.keys(expected.state, sequence + Buttons.CURSOR_SELECT).state,
                )

                val px = (x + 1) * tile
                val py = (y + 1) * tile
                assertEquals(
                    "first tap at ($x,$y) step $step",
                    KeyOutcome.SomeEffect,
                    actual.processKey(px, py, Buttons.LEFT_BUTTON, tile),
                )
                assertTrue(
                    "the first tap must select a group at ($x,$y)",
                    (actual.ui as SameGame.Ui).nselected >= 2,
                )
                assertEquals(
                    "the first tap must not change the Kotlin save at step $step",
                    kotlinBefore,
                    actual.serialise().serialise(),
                )

                sequence += Buttons.CURSOR_SELECT
                sequence += Buttons.CURSOR_SELECT
                expected = oracle.keys(expected.state, sequence)
                assertEquals(
                    "second tap at ($x,$y) step $step",
                    KeyOutcome.SomeEffect,
                    actual.processKey(px, py, Buttons.LEFT_BUTTON, tile),
                )
                assertSame("removal $step at ($x,$y)", expected, actual)
                assertEquals(
                    "the selection clears after a real move",
                    0,
                    (actual.ui as SameGame.Ui).nselected,
                )
                changedClicks++
            }

            assertTrue(
                "the pointer path changed no state; changed clicks: $changedClicks",
                changedClicks >= 4,
            )

            val expectedStatus = traceStatus(oracle, expected.state)
            assertEquals("score status after the removals", expectedStatus, actual.statusText())
            val score = (actual.state as SameGame.State).score
            assertTrue("the score must be a real result, not zero", score > 0)
            assertEquals(
                "one MOVE record per changed click",
                changedClicks,
                actual.serialise().values("MOVE").size,
            )
        }

    @Test
    fun `score gravity completion and impossibility match the oracle`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                Crafted("2x2c3s2", "1,1,1,1", listOf("M0,1,2,3")),
                Crafted("2x2c3s2", "1,2,3,1", listOf("M0")),
                Crafted("3x3c3s2", "1,2,3,0,3,0,0,0,0", listOf("M0")),
                Crafted("2x2c3s2", "1,2,3,1", listOf("M0,0,0")),
                Crafted("2x2c3s2", "1,2,3,1", listOf("M")),
                Crafted("3x3c3s1", "1,1,1,2,2,2,3,3,3", listOf("M0,1,2")),
                Crafted("2x2c3s2", "0,0,0,0", emptyList()),
                Crafted("2x2c3s2", "1,2,3,1", emptyList()),
            )

            for (case in cases) {
                val save = saveText(case.params, case.desc, case.moves)
                val expected = oracle.id(save)
                val actual = Midend.fromSave(SameGame, SgtpSave.parse(save))

                assertSame("${case.params} '${case.desc}' ${case.moves}", expected, actual)
                assertEquals(
                    "text ${case.params} '${case.desc}' ${case.moves}",
                    oracle.text(save),
                    actual.textFormat(),
                )
                assertEquals(
                    "status text ${case.params} '${case.desc}' ${case.moves}",
                    traceStatus(oracle, save),
                    actual.statusText(),
                )
            }

            val complete = load(oracle, "2x2c3s2", "1,1,1,1", listOf("M0,1,2,3"))
            assertEquals("COMPLETED! Score: 4", complete.second.statusText())
            assertEquals(4, (complete.second.state as SameGame.State).score)
            assertEquals(1, complete.second.status())

            val duplicate = load(oracle, "2x2c3s2", "1,2,3,1", listOf("M0,0,0"))
            assertEquals(1, (duplicate.second.state as SameGame.State).score)
            assertEquals("Cannot move! Score: 1", duplicate.second.statusText())

            val emptyMove = load(oracle, "2x2c3s2", "1,2,3,1", listOf("M"))
            assertEquals("Cannot move! Score: 0", emptyMove.second.statusText())

            // `new_game` does not call `sg_check`, so a blank or dead
            // description still reports a plain score.
            val blank = load(oracle, "2x2c3s2", "0,0,0,0", emptyList())
            assertEquals("Score: 0", blank.second.statusText())
            assertEquals(0, blank.second.status())

            val dead = load(oracle, "2x2c3s2", "1,2,3,1", emptyList())
            assertEquals("Score: 0", dead.second.statusText())
            assertEquals(0, dead.second.status())
        }

    @Test
    fun `undo and redo match the oracle and clear the selection`() =
        OracleClient().use { oracle ->
            val completeSave = saveText("2x2c3s2", "1,1,1,1", listOf("M0,1,2,3"))
            var expected = oracle.id(completeSave)
            val actual = Midend.fromSave(SameGame, SgtpSave.parse(completeSave))
            assertFalse("a loaded game has no fresh completion event", actual.takeCompletedEvent())
            assertEquals(1, actual.status())

            expected = oracle.undo(expected.state)
            assertTrue(actual.undo())
            assertSame("undo from complete", expected, actual)
            assertEquals(0, actual.status())
            assertFalse("undo leaves the completed state", actual.takeCompletedEvent())

            expected = oracle.redo(expected.state)
            assertTrue(actual.redo())
            assertSame("redo into complete", expected, actual)
            assertEquals(1, actual.status())
            assertTrue("redo enters the completed state", actual.takeCompletedEvent())
            assertFalse(actual.takeCompletedEvent())

            val movedSave = saveText("2x2c3s2", "1,1,2,2", listOf("M0,1"))
            expected = oracle.id(movedSave)
            val midend = Midend.fromSave(SameGame, SgtpSave.parse(movedSave))
            val ui = midend.ui as SameGame.Ui
            val tile = SameGame.preferredTileSize

            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(tile, 2 * tile, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(2, ui.nselected)

            expected = oracle.undo(expected.state)
            assertTrue(midend.undo())
            assertSame("undo with a selection", expected, midend)
            assertEquals("undo clears the selection", 0, ui.nselected)

            expected = oracle.redo(expected.state)
            assertTrue(midend.redo())
            assertSame("redo with a selection", expected, midend)
            assertEquals("redo clears the selection", 0, ui.nselected)
        }

    @Test
    fun `an invalid move is rejected by both sides`() = OracleClient().use { oracle ->
        val save = saveText("2x2c3s2", "1,2,3,1", listOf("X1"))
        try {
            oracle.id(save)
            throw AssertionError("the oracle accepted an invalid move")
        } catch (e: OracleException) {
            assertEquals("Save file contained an invalid move", e.message)
        }
        val state = SameGame.newState(SameGame.Params(2, 2, 3, 2, true), "1,2,3,1")
        assertEquals(null, SameGame.executeMove(state, "X1"))
    }

    private fun arrowMode(oracleMode: Int): ArrowMode = when (oracleMode) {
        1 -> ArrowMode.ARROWS_ONLY
        2 -> ArrowMode.ARROWS_LEFT
        3 -> ArrowMode.ARROWS_LEFT_RIGHT
        else -> ArrowMode.NONE
    }

    private data class Crafted(val params: String, val desc: String, val moves: List<String>)

    private fun load(
        oracle: OracleClient,
        params: String,
        desc: String,
        moves: List<String>,
    ): Pair<GameState, Midend> {
        val save = saveText(params, desc, moves)
        return oracle.id(save) to Midend.fromSave(SameGame, SgtpSave.parse(save))
    }

    private fun firstGroup(state: SameGame.State, minSize: Int): Pair<Int, Int>? {
        val w = state.params.width
        val h = state.params.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (state.col(x, y) == 0) continue
                if (componentSize(state, x, y) >= minSize) return x to y
            }
        }
        return null
    }

    private fun componentSize(state: SameGame.State, sx: Int, sy: Int): Int {
        val w = state.params.width
        val h = state.params.height
        val colour = state.col(sx, sy)
        val seen = BooleanArray(state.n)
        val stack = ArrayList<Int>()
        stack.add(sy * w + sx)
        seen[sy * w + sx] = true
        var count = 0
        while (stack.isNotEmpty()) {
            val index = stack.removeAt(stack.size - 1)
            count++
            val x = index % w
            val y = index / w
            if (x > 0 && !seen[index - 1] && state.col(x - 1, y) == colour) {
                seen[index - 1] = true
                stack.add(index - 1)
            }
            if (x + 1 < w && !seen[index + 1] && state.col(x + 1, y) == colour) {
                seen[index + 1] = true
                stack.add(index + 1)
            }
            if (y > 0 && !seen[index - w] && state.col(x, y - 1) == colour) {
                seen[index - w] = true
                stack.add(index - w)
            }
            if (y + 1 < h && !seen[index + w] && state.col(x, y + 1) == colour) {
                seen[index + w] = true
                stack.add(index + w)
            }
        }
        return count
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

    private fun saveText(params: String, desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Same Game"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
