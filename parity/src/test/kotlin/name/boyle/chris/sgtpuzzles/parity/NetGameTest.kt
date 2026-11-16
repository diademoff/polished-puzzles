package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: net.
 *
 * A live game against the oracle: clicks, the release commit, the
 * rotation drag, locks, the keyboard, the origin and source moves, the
 * status text, the Jumble with the oracle's NUL platform seed, solve
 * from the initial state, and undo/redo.
 */
class NetGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val FIVE = "5x5:1863151ea86e7e3c7abc21a32"
        const val WRAP = "5x5w:8642ec1e6b11ae6cac23c395b"

        /** The decoded `AUXINFO` of the frozen `5x5w` save: a solution. */
        const val SOLVED_DESC = "1681ec17cb2157695c1c3c35e"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun newSave(params: String = "5x5", seed: String = SEED): String =
        Midend.newGame(Net, params, seed).serialise().serialise()

    /**
     * The frozen `5x5w` save with the solved description and a move
     * history. The source stays at the `newUi` centre (2,2).
     */
    private fun solvedSave(vararg moves: String): String {
        val base = File(testdata, "saves/initial/net/5x5w.sgtp").readText()
        val records = SgtpSave.parse(base).records.map {
            when (it.key) {
                "DESC" -> SaveRecord("DESC", SOLVED_DESC)
                "NSTATES" -> SaveRecord("NSTATES", (moves.size + 1).toString())
                "STATEPOS" -> SaveRecord("STATEPOS", (moves.size + 1).toString())
                else -> it
            }
        } + moves.map { SaveRecord("MOVE", it) }
        return SgtpSave.of(records).serialise()
    }

    /** The one `status` text of the oracle's cold trace of [save]. */
    private fun oracleStatus(oracle: OracleClient, save: String): Pair<Int, String> {
        val state = oracle.id(save)
        val trace = oracle.trace(state.state)
        val text = (trace.field("ops") as JsonValue.Arr).items
            .map { it as JsonValue.Obj }
            .first { it.string("op") == "status" }
            .string("text")
        return state.status to text
    }

    private fun kotlin(save: String): Midend =
        Midend.fromSave(Net, SgtpSave.parse(save))

    private fun point(x: Int, y: Int, button: Int) = OracleClient.Point(x, y, button)

    @Test
    fun `a new game matches the oracle`() = OracleClient().use { oracle ->
        for (params in Net.presets().map { it.params }) {
            val expected = oracle.newGame("net", params, SEED)
            val actual = Midend.newGame(Net, params, SEED)
            assertEquals("'$params' id", expected.id, actual.id())
            assertEquals("'$params' status", expected.status, actual.status())
            assertEquals("'$params' text", oracle.text(expected.state), actual.textFormat())
            assertEquals("'$params' save", expected.state, actual.serialise().serialise())
            assertEquals("'$params' canUndo", expected.canUndo, actual.canUndo)
            assertEquals("'$params' canRedo", expected.canRedo, actual.canRedo)
            assertEquals(
                "'$params' UI",
                SgtpSave.parse(expected.state).value("UI"),
                actual.serialise().value("UI"),
            )
        }
        assertEquals(FIVE, Midend.newGame(Net, "5x5", SEED).id())
        assertEquals(WRAP, Midend.newGame(Net, "5x5w", SEED).id())
    }

    @Test
    fun `a click rotates on the release`() = OracleClient().use { oracle ->
        val save = newSave()
        val points = listOf(
            point(5 + 16, 5 + 16, Buttons.LEFT_BUTTON),
            point(5 + 16, 5 + 16, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, points).state
        val midend = kotlin(save)
        for (p in points) midend.processKey(p.x, p.y, p.button, TILE)

        assertEquals(expected.id, midend.id())
        assertEquals(expected.state, midend.serialise().serialise())
        assertEquals(2, midend.stateCount)
        assertEquals(listOf("A0,0"), midend.serialise().values("MOVE"))

        // The press alone makes no move and adds no history.
        val pressOnly = kotlin(save)
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.Unused,
            pressOnly.processKey(5 + 16, 5 + 16, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(1, pressOnly.stateCount)

        // A left click on a locked tile is inert.
        val locked = oracle.pointsSized(
            save,
            listOf(point(5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON)),
        ).state
        val inertExpected = oracle.pointsSized(
            locked.state,
            listOf(
                point(5 + 16, 5 + 16, Buttons.LEFT_BUTTON),
                point(5 + 16, 5 + 16, Buttons.LEFT_RELEASE),
            ),
        ).state
        val inert = kotlin(locked.state)
        inert.processKey(5 + 16, 5 + 16, Buttons.LEFT_BUTTON, TILE)
        inert.processKey(5 + 16, 5 + 16, Buttons.LEFT_RELEASE, TILE)
        assertEquals(inertExpected.state, inert.serialise().serialise())
        assertEquals(2, inert.stateCount)
    }

    @Test
    fun `a middle click locks`() = OracleClient().use { oracle ->
        val save = newSave()
        val points = listOf(point(5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON))
        val expected = oracle.pointsSized(save, points).state
        val midend = kotlin(save)
        midend.processKey(points[0].x, points[0].y, points[0].button, TILE)
        assertEquals(expected.state, midend.serialise().serialise())
        assertEquals(listOf("L0,0"), midend.serialise().values("MOVE"))

        // The same middle click unlocks.
        val unlockedExpected = oracle.pointsSized(expected.state, points).state
        val unlocked = kotlin(expected.state)
        unlocked.processKey(points[0].x, points[0].y, points[0].button, TILE)
        assertEquals(unlockedExpected.state, unlocked.serialise().serialise())
    }

    @Test
    fun `the four drag quadrants produce A, C, F, and none`() =
        OracleClient().use { oracle ->
            val save = newSave()
            // Start at the top-left corner of the tile and drag to the
            // four corners. The C distance order is d0, dF, dA, dC.
            val start = 5 + 1
            val end = 5 + 30
            for ((name, dragX, dragY, expectedMove) in listOf(
                // The start quadrant drag is inert, but the release
                // without a committed turn is a left click.
                Quad("start", 5 + 3, 5 + 3, "A0,0"),
                Quad("flip", end, end, "F0,0"),
                Quad("anticlockwise", 5 + 1, end, "A0,0"),
                Quad("clockwise", end, 5 + 1, "C0,0"),
            )) {
                val points = listOf(
                    point(start, start, Buttons.LEFT_BUTTON),
                    point(dragX, dragY, Buttons.LEFT_DRAG),
                    point(dragX, dragY, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(save, points).state
                val midend = kotlin(save)
                for (p in points) midend.processKey(p.x, p.y, p.button, TILE)
                assertEquals("$name state", expected.state, midend.serialise().serialise())
                assertEquals(
                    "$name moves",
                    listOf(expectedMove),
                    midend.serialise().values("MOVE"),
                )
            }
            assertTrue(end > start)
        }

    @Test
    fun `a drag that stays in the start quadrant adds no move`() =
        OracleClient().use { oracle ->
            val save = newSave()
            // A drag-only gesture: no release, so the click path is
            // not reached. The drag is in the d0 quadrant.
            val points = listOf(
                point(5 + 1, 5 + 1, Buttons.LEFT_BUTTON),
                point(5 + 3, 5 + 3, Buttons.LEFT_DRAG),
            )
            val expected = oracle.pointsSized(save, points).state
            val midend = kotlin(save)
            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.Unused,
                midend.processKey(points[0].x, points[0].y, points[0].button, TILE),
            )
            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.Unused,
                midend.processKey(points[1].x, points[1].y, points[1].button, TILE),
            )
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(1, midend.stateCount)
            assertTrue(expected.state.contains("NSTATES :1:1"))
        }

    private data class Quad(
        val name: String,
        val dragX: Int,
        val dragY: Int,
        val move: String?,
    )

    @Test
    fun `a continuous circular drag emits one move per quarter turn`() =
        OracleClient().use { oracle ->
            val save = newSave()
            // Press at the top-left corner, then move clockwise around
            // the tile. Every accepted turn moves the drag start.
            val points = listOf(
                point(5 + 1, 5 + 1, Buttons.LEFT_BUTTON),
                point(5 + 30, 5 + 1, Buttons.LEFT_DRAG),
                point(5 + 30, 5 + 30, Buttons.LEFT_DRAG),
                point(5 + 1, 5 + 30, Buttons.LEFT_DRAG),
                point(5 + 1, 5 + 1, Buttons.LEFT_DRAG),
                point(5 + 1, 5 + 1, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(save, points).state
            val midend = kotlin(save)
            for (p in points) midend.processKey(p.x, p.y, p.button, TILE)
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(
                SgtpSave.parse(expected.state).values("MOVE"),
                midend.serialise().values("MOVE"),
            )
            assertEquals(
                listOf("C0,0", "C0,0", "C0,0", "C0,0"),
                midend.serialise().values("MOVE"),
            )
            assertEquals(5, midend.stateCount)
        }

    @Test
    fun `the keyboard rotates, locks, flips, and jumbles`() =
        OracleClient().use { oracle ->
            val save = newSave()
            val sequence = listOf(
                'a'.code, 's'.code, 'd'.code, 'f'.code, 'j'.code,
            )
            val expected = oracle.keys(save, sequence)
            val midend = kotlin(save)
            for (button in sequence) midend.processKey(0, 0, button, TILE)
            assertEquals(expected.state, midend.serialise().serialise())
            // The lock makes the following rotate and flip inert.
            assertEquals(4, midend.stateCount)
            assertEquals(
                listOf("A2,2", "L2,2"),
                midend.serialise().values("MOVE").take(2),
            )
            assertEquals(3, midend.serialise().values("MOVE").size)
            // The Jumble skips the locked centre, so its text differs
            // from a fresh jumble; the oracle comparison above pins it.
            assertTrue(midend.serialise().values("MOVE").last().startsWith("J"))
        }

    @Test
    fun `the origin and source keys match the oracle`() = OracleClient().use { oracle ->
        for (save in listOf(newSave("5x5w"), newSave("5x5"))) {
            for (sequence in listOf(
                listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
                listOf(Buttons.CURSOR_UP or Buttons.MOD_SHFT),
                listOf(Buttons.CURSOR_LEFT or Buttons.MOD_CTRL),
                listOf(Buttons.CURSOR_DOWN or Buttons.MOD_CTRL),
                listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT or Buttons.MOD_CTRL),
                listOf(Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            )) {
                val expected = oracle.keys(save, sequence)
                val midend = kotlin(save)
                for (button in sequence) {
                    midend.processKey(0, 0, button, TILE)
                }
                assertEquals(expected.state, midend.serialise().serialise())
                assertEquals(1, midend.stateCount)
            }
        }
    }

    @Test
    fun `the status text follows the source tile`() = OracleClient().use { oracle ->
        val save = newSave()
        val traceSave = oracle.keys(
            save,
            listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL),
        )
        val trace = oracle.trace(traceSave.state)
        val status = (trace.field("ops") as JsonValue.Arr).items
            .map { it as JsonValue.Obj }
            .first { it.string("op") == "status" }
            .string("text")
        val midend = kotlin(traceSave.state)
        assertEquals(status, midend.statusText())
        assertTrue(status.startsWith("Active: "))

        // A completed board shows the banner without a counter.
        val solvedSave = oracle.solve(save).state
        val solvedTrace = oracle.trace(solvedSave)
        val solvedStatus = (solvedTrace.field("ops") as JsonValue.Arr).items
            .map { it as JsonValue.Obj }
            .first { it.string("op") == "status" }
            .string("text")
        assertEquals(solvedStatus, kotlin(solvedSave).statusText())
        assertEquals("Auto-solved. ", solvedStatus)
    }

    /**
     * The sticky `completed` flag, as a save replay. The first lock
     * completes the solved board. A later rotation breaks the network,
     * but C keeps the flag because `execute_move` only sets it
     * (`net.c:2532-2555`) and `dup_game` copies it. A port that
     * recomputed completion would report status 0 and no banner.
     */
    @Test
    fun `completion stays set after a win, a rotation, and a lock`() =
        OracleClient().use { oracle ->
            for ((moves, status, text) in listOf(
                Triple(listOf("L0,0"), 1, "COMPLETED! "),
                Triple(listOf("L0,0", "A2,2"), 1, "COMPLETED! Active: 1/25"),
                Triple(listOf("L0,0", "A2,2", "L1,1"), 1, "COMPLETED! Active: 1/25"),
                Triple(listOf("L0,0", "A0,0"), 1, "COMPLETED! Active: 24/25"),
            )) {
                val save = solvedSave(*moves.toTypedArray())
                val (oracleStatus, oracleText) = oracleStatus(oracle, save)
                assertEquals("$moves oracle status", status, oracleStatus)
                assertEquals("$moves oracle text", text, oracleText)

                val midend = Midend.fromSave(Net, SgtpSave.parse(save))
                assertEquals("$moves status", oracleStatus, midend.status())
                assertEquals("$moves text", oracleText, midend.statusText())
            }
        }

    /**
     * The `Auto-solved. Active: a/n2` branch. A save can carry the
     * moves that reach it: a lock completes the board, a rotation
     * breaks the network without clearing `completed`, and a bare `S`
     * sets `used_solve` without changing the board. The source tile is
     * not blank and `a < n2`, so the counter is appended to the
     * banner.
     */
    @Test
    fun `a save reaches the auto-solved counter branch`() =
        OracleClient().use { oracle ->
            val save = solvedSave("L0,0", "A2,2", "S")
            val (status, text) = oracleStatus(oracle, save)
            assertEquals(1, status)
            assertEquals("Auto-solved. Active: 1/25", text)

            val midend = Midend.fromSave(Net, SgtpSave.parse(save))
            assertEquals("S", midend.serialise().values("MOVE").last())
            assertEquals(1, midend.status())
            assertEquals(text, midend.statusText())
        }

    @Test
    fun `solve from the initial state and after real moves matches the oracle`() =
        OracleClient().use { oracle ->
            val save = newSave()
            val expected = oracle.solve(save)
            val midend = kotlin(save)
            assertTrue(midend.solve())
            assertEquals(expected.id, midend.id())
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(1, expected.status)
            assertEquals(1, midend.status())

            // Move first: C solves states[0] but diffs the current
            // state, so the SOLVE text must refer to the moved board.
            val played = oracle.pointsSized(
                save,
                listOf(
                    point(5 + 16, 5 + 16, Buttons.LEFT_BUTTON),
                    point(5 + 16, 5 + 16, Buttons.LEFT_RELEASE),
                ),
            ).state
            val expectedAfter = oracle.solve(played.state)
            val after = kotlin(played.state)
            assertTrue(after.solve())
            assertEquals(expectedAfter.id, after.id())
            assertEquals(expectedAfter.state, after.serialise().serialise())
            assertTrue(after.serialise().values("SOLVE").single().startsWith("S"))
        }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val save = newSave()
        val played = oracle.keys(save, listOf('a'.code, 's'.code, 'j'.code))
        for (steps in 1..3) {
            var expected = played
            for (i in 0 until steps) expected = oracle.undo(expected.state)
            val midend = kotlin(played.state)
            repeat(steps) { assertTrue(midend.undo()) }
            assertEquals("undo $steps id", expected.id, midend.id())
            assertEquals("undo $steps state", expected.state, midend.serialise().serialise())
            assertEquals("undo $steps status", expected.status, midend.status())

            val redone = oracle.redo(expected.state)
            assertTrue(midend.redo())
            assertEquals("redo $steps id", redone.id, midend.id())
            assertEquals("redo $steps state", redone.state, midend.serialise().serialise())
        }

        // A new move truncates the redo branch. The cursor tile is
        // locked, so a lock toggle is the move that does it.
        val midend = kotlin(played.state)
        assertTrue(midend.undo())
        midend.processKey(0, 0, 's'.code, TILE)
        assertFalse(midend.canRedo)
    }

    @Test
    fun `the UI record and requestKeys match the oracle`() = OracleClient().use { oracle ->
        val save = newSave("5x5w")
        val midend = kotlin(save)
        assertEquals("O0,0;C2,2", midend.serialise().value("UI"))
        assertEquals("O0,0;C2,2", Net.encodeUi(midend.ui))

        val moved = oracle.keys(
            save,
            listOf(
                Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
                Buttons.CURSOR_DOWN or Buttons.MOD_CTRL,
            ),
        )
        val movedKotlin = kotlin(save)
        movedKotlin.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT, TILE)
        movedKotlin.processKey(0, 0, Buttons.CURSOR_DOWN or Buttons.MOD_CTRL, TILE)
        assertEquals(moved.state, movedKotlin.serialise().serialise())
        assertEquals(movedKotlin.serialise().value("UI"), SgtpSave.parse(moved.state).value("UI"))

        val expectedKeys = oracle.requestKeys(save)
        val actualKeys = Net.requestKeys(Net.defaultParams())
        assertEquals(expectedKeys.arrowMode, actualKeys.arrowMode.ordinal)
        assertEquals(expectedKeys.labels.size, actualKeys.labels.size)
        for (i in expectedKeys.labels.indices) {
            assertEquals(expectedKeys.labels[i].label, actualKeys.labels[i].name)
            assertEquals(expectedKeys.labels[i].button, actualKeys.labels[i].button)
            assertEquals(expectedKeys.labels[i].needsArrows, actualKeys.labels[i].needsArrows)
        }
    }
}
