package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Undead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: undead.
 *
 * A generated game, the text, the status, real pointer clicks on cells
 * and on the count row, the keyboard, pencil marks, undo and redo, the
 * completion event, the two solve errors, and the hint payload.
 */
class UndeadGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 64

        const val DEFAULT_DESC =
            "1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"

        const val DEFAULT_SOLVE =
            "S;Z0;Z1;Z2;Z3;Z4;G5;V6;V7;V8;V9;Z10;V11"

        const val INCONSISTENT_DESC = "1,5,6,bRdRLbRd,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"
        const val UNSOLVABLE_DESC =
            "3,4,2,RdLaRRbRLRb,1,2,2,2,2,3,0,1,1,2,1,0,0,1,4,0"
    }

    private fun midend(save: String): Midend =
        Midend.fromSave(Undead, SgtpSave.parse(save))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun stateOf(params: String, desc: String): Undead.State =
        Undead.newState(Undead.decodeParams(params), desc) as Undead.State

    private fun saveText(
        params: String,
        desc: String,
        records: List<Pair<String, String>> = emptyList(),
    ): String {
        val n = records.size + 1
        val out = StringBuilder()
        fun record(key: String, value: String) {
            out.append(key.padEnd(8)).append(':').append(value.length).append(':')
                .append(value).append('\n')
        }
        record("SAVEFILE", "Simon Tatham's Portable Puzzle Collection")
        record("VERSION", "1")
        record("GAME", "Undead")
        record("PARAMS", "4x4dn".let { if (params.startsWith("5x5")) "5x5dn" else if (params.startsWith("7x7")) "7x7dn" else it })
        record("CPARAMS", params)
        record("SEED", SEED)
        record("DESC", desc)
        record("NSTATES", n.toString())
        record("STATEPOS", n.toString())
        for ((key, value) in records) record(key, value)
        return out.toString()
    }

    /** The safe centre of the drawn cell at (x, y) for a tile size. */
    private fun cellPoint(x: Int, y: Int, tile: Int): OracleClient.Point =
        OracleClient.Point(
            tile / 4 + x * tile + tile / 2,
            tile / 4 + y * tile + 3 * tile / 2,
            Buttons.LEFT_BUTTON,
        )

    private fun firstFloor(state: Undead.State): Pair<Int, Int> {
        val w = state.common.params.w
        for (y in 1..state.common.params.h) {
            for (x in 1..w) {
                val xi = state.common.xinfo[x + y * (w + 2)]
                if (xi >= 0 && !state.common.fixed[xi]) return x to y
            }
        }
        error("the board has no editable cell")
    }

    private fun firstMirror(state: Undead.State): Pair<Int, Int> {
        val w = state.common.params.w
        for (y in 1..state.common.params.h) {
            for (x in 1..w) {
                if (state.common.xinfo[x + y * (w + 2)] == -1) return x to y
            }
        }
        error("the board has no mirror")
    }

    /** The cursor key sequence that reaches (x, y) from the hidden start. */
    private fun cursorTo(x: Int, y: Int): List<Int> {
        val out = ArrayList<Int>()
        out.add(Buttons.CURSOR_DOWN)
        repeat(x - 1) { out.add(Buttons.CURSOR_RIGHT) }
        repeat(y - 1) { out.add(Buttons.CURSOR_DOWN) }
        return out
    }

    /** Tracks the C cursor so a sequence can visit several cells. */
    private class Keys {
        val sequence = ArrayList<Int>()
        private var x = 0
        private var y = 0

        fun moveTo(targetX: Int, targetY: Int) {
            if (x == 0 && y == 0) {
                sequence.add(Buttons.CURSOR_DOWN)
                x = 1
                y = 1
            }
            while (x < targetX) {
                sequence.add(Buttons.CURSOR_RIGHT)
                x++
            }
            while (x > targetX) {
                sequence.add(Buttons.CURSOR_LEFT)
                x--
            }
            while (y < targetY) {
                sequence.add(Buttons.CURSOR_DOWN)
                y++
            }
            while (y > targetY) {
                sequence.add(Buttons.CURSOR_UP)
                y--
            }
        }

        fun key(button: Int) {
            sequence.add(button)
        }
    }

    @Test
    fun `a generated game matches the oracle`() = OracleClient().use { oracle ->
        for (params in Undead.presets().map { it.params }) {
            val expected = oracle.newGame("undead", params, SEED)
            val actual = Midend.newGame(Undead, params, SEED)
            assertSame("'$params' new game", expected, actual)
            assertEquals("'$params' text", oracle.text(expected.state), actual.textFormat())
            assertEquals("'$params' status text", "", actual.statusText())
            assertEquals("'$params' status", 0, actual.status())
            assertNull("'$params' AUXINFO", actual.serialise().value("AUXINFO"))
            assertFalse(
                "'$params' has no UI record",
                actual.serialise().records.any { it.key == "UI" },
            )
        }
    }

    @Test
    fun `real clicks select a cell and the count row makes the move`() =
        OracleClient().use { oracle ->
            for (params in Undead.presets().map { it.params }) {
                val save = oracle.newGame("undead", params, SEED).state
                val start = midend(save)
                val cell = firstFloor(start.state as Undead.State)
                val size = Undead.computeSize(Undead.decodeParams(params), TILE)
                val geometry = Undead.countsGeometry(TILE, (start.state as Undead.State).common.params.w)
                val cy = (geometry.y1 + geometry.y2) / 2
                val points = listOf(
                    cellPoint(cell.first, cell.second, TILE),
                    OracleClient.Point((geometry.ghostX1 + geometry.ghostX2) / 2, cy, Buttons.LEFT_BUTTON),
                    OracleClient.Point((geometry.vampireX1 + geometry.vampireX2) / 2, cy, Buttons.LEFT_BUTTON),
                    OracleClient.Point((geometry.zombieX1 + geometry.zombieX2) / 2, cy, Buttons.LEFT_BUTTON),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    size.width,
                    size.height,
                ).state

                for (point in points) {
                    assertTrue(
                        "'$params' point $point must be used",
                        start.processKey(point.x, point.y, point.button, TILE) != KeyOutcome.Unused,
                    )
                }
                assertSame("'$params' count row", expected, start)
                assertEquals("'$params' three moves", 3, start.serialise().values("MOVE").size)
                assertEquals("'$params' four states", 4, start.stateCount)
            }
        }

    @Test
    fun `real clicks work at every tile size`() = OracleClient().use { oracle ->
        val params = "4x4dn"
        val save = oracle.newGame("undead", params, SEED).state
        for (tile in listOf(48, 63, 82, 100)) {
            val start = midend(save)
            val cell = firstFloor(start.state as Undead.State)
            val size = Undead.computeSize(Undead.decodeParams(params), tile)
            val points = listOf(cellPoint(cell.first, cell.second, tile))
            val expected = oracle.pointsSized(
                save,
                points,
                size.width,
                size.height,
            ).state
            for (point in points) start.processKey(point.x, point.y, point.button, tile)
            assertSame("tile $tile select", expected, start)
            // A selection is UI only.
            assertEquals("tile $tile states", 1, start.stateCount)
            assertEquals("tile $tile id", Midend.newGame(Undead, params, SEED).id(), start.id())

            // The count row at the same tile is one move.
            val geometry = Undead.countsGeometry(tile, 4)
            val cy = (geometry.y1 + geometry.y2) / 2
            val press = OracleClient.Point(
                (geometry.ghostX1 + geometry.ghostX2) / 2,
                cy,
                Buttons.LEFT_BUTTON,
            )
            val expectedAfter = oracle.pointsSized(
                save,
                listOf(cellPoint(cell.first, cell.second, tile), press),
                size.width,
                size.height,
            ).state
            start.processKey(press.x, press.y, press.button, tile)
            assertSame("tile $tile count", expectedAfter, start)
            assertEquals("tile $tile one move", 1, start.serialise().values("MOVE").size)
        }
    }

    @Test
    fun `the keyboard drives the cursor and the three monsters`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("undead", "4x4dn", SEED).state
            val state = midend(save).state as Undead.State
            val target = firstFloor(state)
            val sequence = cursorTo(target.first, target.second) +
                listOf('G'.code, 'V'.code, 'Z'.code, 'g'.code, 'E'.code)
            val expected = oracle.keys(save, sequence)
            val actual = midend(save)
            for (key in sequence) {
                assertTrue(
                    "key $key must be used",
                    actual.processKey(0, 0, key, TILE) != KeyOutcome.Unused,
                )
            }
            assertSame("keyboard", expected, actual)
            assertEquals("keyboard moves", 5, actual.serialise().values("MOVE").size)
        }

    @Test
    fun `pencil mode toggles the same marks`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val state = midend(save).state as Undead.State
        val target = firstFloor(state)
        val sequence = cursorTo(target.first, target.second) +
            listOf(
                Buttons.CURSOR_SELECT, 'g'.code, 'v'.code, 'z'.code,
                'e'.code, 'E'.code,
            )
        val expected = oracle.keys(save, sequence)
        val actual = midend(save)
        for (key in sequence) actual.processKey(0, 0, key, TILE)
        assertSame("pencil", expected, actual)
    }

    @Test
    fun `undo and redo cross the moves`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val target = firstFloor(midend(save).state as Undead.State)
        val sequence = cursorTo(target.first, target.second) +
            listOf('G'.code, 'V'.code, 'Z'.code)
        val expected = oracle.keys(save, sequence)
        val actual = midend(save)
        for (key in sequence) actual.processKey(0, 0, key, TILE)
        assertSame("played", expected, actual)
        assertEquals(3, actual.serialise().values("MOVE").size)

        val undo1 = oracle.undo(expected.state)
        assertTrue(actual.undo())
        assertSame("undo once", undo1, actual)
        val undo2 = oracle.undo(undo1.state)
        assertTrue(actual.undo())
        assertSame("undo twice", undo2, actual)
        val redo1 = oracle.redo(undo2.state)
        assertTrue(actual.redo())
        assertSame("redo once", redo1, actual)
        val redo2 = oracle.redo(redo1.state)
        assertTrue(actual.redo())
        assertSame("redo twice", redo2, actual)
        assertFalse("no more redo", actual.redo())
    }

    @Test
    fun `a real win raises exactly one completion event`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val start = midend(save)
        val state = start.state as Undead.State
        val solution = IntArray(state.common.numTotal) { 7 }
        val body = DEFAULT_SOLVE.removePrefix("S;")
        for (token in body.split(';')) {
            val type = token[0]
            val id = token.substring(1).toInt()
            solution[id] = when (type) {
                'G' -> 1
                'V' -> 2
                else -> 4
            }
        }
        val keys = Keys()
        for (y in 1..4) {
            for (x in 1..4) {
                val xi = state.common.xinfo[x + y * 6]
                if (xi < 0) continue
                keys.moveTo(x, y)
                keys.key(
                    when (solution[xi]) {
                        1 -> 'G'.code
                        2 -> 'V'.code
                        else -> 'Z'.code
                    },
                )
            }
        }
        val sequence = keys.sequence
        val expected = oracle.keys(save, sequence)
        assertEquals("the win", 1, expected.status)
        for (key in sequence) start.processKey(0, 0, key, TILE)
        assertSame("the win", expected, start)
        assertTrue("the win raises the event", start.takeCompletedEvent())
        assertFalse("the event is consumed once", start.takeCompletedEvent())

        // A post-win move keeps status 1 and raises nothing.
        val extraKeys = Keys()
        // The cursor ends on the last filled cell; move it to (1,1).
        extraKeys.moveTo(4, 4)
        extraKeys.moveTo(1, 1)
        extraKeys.key('E'.code)
        val extra = extraKeys.sequence
        val afterExpected = oracle.keys(expected.state, extra)
        assertEquals(1, afterExpected.status)
        for (key in extra) start.processKey(0, 0, key, TILE)
        assertSame("post win", afterExpected, start)
        assertFalse("post win raises nothing", start.takeCompletedEvent())

        // The win state is sticky, so the first undo still reports 1.
        // The second undo reaches the open state; the second redo raises
        // the event again.
        val undo1 = oracle.undo(afterExpected.state)
        assertTrue(start.undo())
        assertSame("undo post win", undo1, start)
        assertEquals(1, start.status())
        val undo2 = oracle.undo(undo1.state)
        assertTrue(start.undo())
        assertSame("undo win", undo2, start)
        assertEquals(0, start.status())
        val redo1 = oracle.redo(undo2.state)
        assertTrue(start.redo())
        assertSame("redo win", redo1, start)
        assertEquals(1, start.status())
        assertTrue("redo raises the event again", start.takeCompletedEvent())
        val redo2 = oracle.redo(redo1.state)
        assertTrue(start.redo())
        assertSame("redo post win", redo2, start)
        assertEquals(1, start.status())
    }

    @Test
    fun `the hint payload is the C solve payload for every preset`() =
        OracleClient().use { oracle ->
            for (params in Undead.presets().map { it.params }) {
                val save = oracle.newGame("undead", params, SEED).state
                val expected = oracle.solve(save)
                val payload = SgtpSave.parse(expected.state).value("SOLVE")
                assertTrue("'$params' payload is a solve move", payload!!.startsWith("S"))

                val midend = midend(save)
                assertTrue("'$params' solve", midend.solve())
                assertSame("'$params' solve", expected, midend)
                assertEquals(
                    "'$params' SOLVE record",
                    payload,
                    midend.serialise().value("SOLVE"),
                )
                // A solve sets cheated, so an open board still reports 0
                // and the event is not raised.
                assertEquals("'$params' status", 0, midend.status())
                assertFalse("'$params' no event", midend.takeCompletedEvent())
            }
        }

    @Test
    fun `a wrong move does not change the hint payload`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val state = midend(save).state as Undead.State
        val target = firstFloor(state)
        val sequence = cursorTo(target.first, target.second) +
            listOf(if ((state.common.xinfo[target.first + target.second * 6]) == 0) 'V'.code else 'G'.code)
        val wrong = oracle.keys(save, sequence)
        // The move must not accidentally be the solution.
        assertEquals(0, wrong.status)
        val expected = oracle.solve(wrong.state)
        val payload = SgtpSave.parse(expected.state).value("SOLVE")
        val actual = midend(wrong.state)
        assertTrue(actual.solve())
        assertSame("wrong then solve", expected, actual)
        assertEquals(payload, actual.serialise().value("SOLVE"))
    }

    @Test
    fun `a solved board solves again and stays cheated`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val solved = oracle.solve(save)
        val expected = oracle.solve(solved.state)
        val actual = midend(solved.state)
        assertTrue(actual.solve())
        assertSame("solved then solve", expected, actual)
        assertEquals(0, actual.status())
    }

    @Test
    fun `the two solve errors are the C messages`() = OracleClient().use { oracle ->
        val cases = listOf(
            "4x4dn" to INCONSISTENT_DESC,
            "4x4dt" to UNSOLVABLE_DESC,
        )
        for ((params, desc) in cases) {
            val save = saveText(params, desc)
            val expected = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            val midend = midend(save)
            assertFalse("'$desc' does not solve", midend.solve())
            assertEquals("'$desc' keeps one state", 1, midend.stateCount)
            assertEquals(
                "'$desc' is the Kotlin message",
                expected,
                Undead.solveAttempt(midend.state, midend.auxInfo).error,
            )
        }
        assertEquals(
            "Puzzle is inconsistent",
            Undead.solveAttempt(stateOf("4x4dn", INCONSISTENT_DESC), null).error,
        )
        assertEquals(
            "Puzzle is unsolvable",
            Undead.solveAttempt(stateOf("4x4dt", UNSOLVABLE_DESC), null).error,
        )
    }

    @Test
    fun `a restart returns to the initial state`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val state = midend(save).state as Undead.State
        val target = firstFloor(state)
        val sequence = cursorTo(target.first, target.second) + listOf('G'.code)
        val played = midend(save)
        for (key in sequence) played.processKey(0, 0, key, TILE)
        assertTrue(played.restart())
        assertEquals("restart text", Midend.newGame(Undead, "4x4dn", SEED).textFormat(), played.textFormat())
        assertEquals("restart id", Midend.newGame(Undead, "4x4dn", SEED).id(), played.id())
        val restarted = played.serialise().serialise()
        val oracleRestart = oracle.id(restarted)
        assertEquals(0, oracleRestart.status)
        assertEquals(0, played.status())
    }

    @Test
    fun `a fixed cell cannot be clicked`() = OracleClient().use { oracle ->
        val desc = "2,2,12,GVZaaaaaaaaaaaaa,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"
        val save = saveText("4x4dn", desc)
        val midend = midend(save)
        val state = midend.state as Undead.State
        assertTrue(state.common.fixed[0])
        val point = cellPoint(1, 1, TILE)
        val expected = oracle.pointsSized(
            save,
            listOf(point),
            Undead.computeSize(Undead.decodeParams("4x4dn"), TILE).width,
            Undead.computeSize(Undead.decodeParams("4x4dn"), TILE).height,
        ).state
        assertEquals("the click is inert", save, expected.state)
        assertEquals(KeyOutcome.Unused, midend.processKey(point.x, point.y, point.button, TILE))
        assertEquals("no move", 0, midend.serialise().values("MOVE").size)
    }

    @Test
    fun `a mirror and a letterbox click are inert`() = OracleClient().use { oracle ->
        val save = oracle.newGame("undead", "4x4dn", SEED).state
        val midend = midend(save)
        val state = midend.state as Undead.State
        val mirror = firstMirror(state)
        val size = Undead.computeSize(Undead.decodeParams("4x4dn"), TILE)
        val points = listOf(
            cellPoint(mirror.first, mirror.second, TILE),
            OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
            OracleClient.Point(size.width - 1, size.height - 1, Buttons.LEFT_BUTTON),
        )
        val expected = oracle.pointsSized(save, points, size.width, size.height).state
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, TILE)
        }
        assertEquals("the clicks add no move", 0, midend.serialise().values("MOVE").size)
        assertEquals("the state is unchanged", save, expected.state)
    }

    @Test
    fun `the status text is always empty`() {
        val midend = Midend.newGame(Undead, "4x4dn", SEED)
        assertEquals("", midend.statusText())
        assertEquals(0, midend.status())
        assertFalse(Undead.wantsStatusBar)
    }
}
