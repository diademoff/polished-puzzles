package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Solo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: solo.
 *
 * The move grammar, the real clicks and keys, undo and redo, the
 * completion event, the text form, the solver with and without
 * `AUXINFO`, and the requested keys. Every accepted move is compared
 * with the C text form as well as with the save, because the save text
 * alone does not carry the effect of a move.
 */
class SoloGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 300_000)
    fun `the move grammar matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("solo", "3x3", SEED)
        val solved = Midend.newGame(Solo, "3x3", SEED).auxInfo!!
        val moves = listOf(
            "R4,4,5",
            "R4,4,0",
            "R0,0,9",
            "R0,0,0",
            "P4,4,1",
            "P4,4,0",
            "M",
            "Mfoo",
            "R0,0,10",
            "R-1,0,1",
            "R0,0",
            "R 4, 4, 5",
            "R+4,+4,+5",
            "R4,4,5junk",
            "R4,4,05",
            "R999999999999,4,5",
            "R4,4,-0",
            "D4,4,5",
            "",
            "S1,2",
            "S" + List(81) { "1" }.joinToString(","),
            "S" + List(81) { "0" }.joinToString(","),
            "S" + List(81) { "10" }.joinToString(","),
            solved,
        )
        for (move in moves) {
            val text = appendRecord(base.state, "MOVE", move)
            val expected = runCatching { oracle.id(text) }
            val actual = runCatching { Midend.fromSave(Solo, SgtpSave.parse(text)) }
            assertEquals("'${move.take(20)}' accepts", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                val c = expected.getOrThrow()
                val k = actual.getOrThrow()
                assertEquals("'${move.take(20)}' id", c.id, k.id())
                assertEquals(
                    "'${move.take(20)}' save",
                    c.state,
                    k.serialise().serialise(),
                )
                assertEquals("'${move.take(20)}' status", c.status, k.status())
                assertEquals(
                    "'${move.take(20)}' text",
                    oracle.text(c.state),
                    k.textFormat(),
                )
            }
        }
    }

    @Test(timeout = 300_000)
    fun `the text form matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Solo.presets().map { it.params }) {
                val save = oracle.newGame("solo", params, SEED).state
                val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
                assertEquals("'$params' text", oracle.text(save), midend.textFormat())
            }
        }

    @Test(timeout = 300_000)
    fun `real clicks and digits match at every tile size`() =
        OracleClient().use { oracle ->
            for (tile in listOf(48, 63, 82, 100)) {
                val params = "3x3"
                val size = Solo.computeSize(Solo.decodeParams(params), tile)
                val base = oracle.newGame("solo", params, SEED)
                val midend = Midend.fromSave(Solo, SgtpSave.parse(base.state))
                val (x, y) = firstEmpty(midend)
                val cx = (x + 1) * tile
                val cy = (y + 1) * tile
                val before = (midend.state as Solo.State).grid.copyOf()
                val startSave = midend.serialise().serialise()
                val points = listOf(
                    OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                    OracleClient.Point(0, 0, '5'.code),
                )
                val expected = oracle.pointsSized(
                    base.state,
                    points,
                    size.width,
                    size.height,
                )

                assertEquals(
                    "tile $tile click",
                    KeyOutcome.SomeEffect,
                    midend.processKey(cx, cy, Buttons.LEFT_BUTTON, tile),
                )
                assertTrue(
                    "tile $tile click changes no grid",
                    before.contentEquals((midend.state as Solo.State).grid),
                )
                assertEquals("tile $tile click keeps the save", startSave, midend.serialise().serialise())
                assertEquals("tile $tile digit", KeyOutcome.SomeEffect, midend.processKey(0, 0, '5'.code, tile))

                assertEquals("tile $tile save", expected.state.state, midend.serialise().serialise())
                assertEquals("tile $tile text", oracle.text(expected.state.state), midend.textFormat())
                assertEquals("tile $tile move", "R$x,$y,5", SgtpSave.parse(expected.state.state).value("MOVE"))
                assertEquals("tile $tile states", 2, midend.stateCount)
                assertFalse(
                    "tile $tile grid changed",
                    before.contentEquals((midend.state as Solo.State).grid),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `pencil clear mark cursor and undo redo match the oracle`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("solo", "3x3", SEED)
            val state = Midend.fromSave(Solo, SgtpSave.parse(base.state))
            val (x, y) = firstEmpty(state)
            val cx = (x + 1) * 48
            val cy = (y + 1) * 48

            // Two left clicks toggle pencil mode on Android, then a
            // digit adds a pencil mark.
            val pencilPoints = listOf(
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '3'.code),
            )
            val pencil = oracle.pointsSized(base.state, pencilPoints)
            for (p in pencilPoints) state.processKey(p.x, p.y, p.button, 48)
            assertEquals("pencil save", pencil.state.state, state.serialise().serialise())
            assertEquals("pencil text", oracle.text(pencil.state.state), state.textFormat())
            assertEquals("pencil move", "P$x,$y,3", SgtpSave.parse(pencil.state.state).value("MOVE"))

            // Right click selects pencil mode on an empty cell.
            val right = oracle.pointsSized(
                base.state,
                listOf(OracleClient.Point(cx, cy, Buttons.RIGHT_BUTTON), OracleClient.Point(0, 0, '4'.code)),
            )
            val rightState = Midend.fromSave(Solo, SgtpSave.parse(base.state))
            rightState.processKey(cx, cy, Buttons.RIGHT_BUTTON, 48)
            rightState.processKey(0, 0, '4'.code, 48)
            assertEquals("right pencil save", right.state.state, rightState.serialise().serialise())
            assertEquals("right pencil text", oracle.text(right.state.state), rightState.textFormat())

            // Mark all.
            val mark = oracle.pointsSized(base.state, listOf(OracleClient.Point(0, 0, 'M'.code)))
            val marked = Midend.fromSave(Solo, SgtpSave.parse(base.state))
            marked.processKey(0, 0, 'M'.code, 48)
            assertEquals("mark save", mark.state.state, marked.serialise().serialise())

            // A real entry, then undo and redo.
            val playPoints = listOf(
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '5'.code),
            )
            val play = oracle.pointsSized(base.state, playPoints)
            val played = Midend.fromSave(Solo, SgtpSave.parse(base.state))
            for (p in playPoints) played.processKey(p.x, p.y, p.button, 48)
            assertEquals("play save", play.state.state, played.serialise().serialise())
            assertTrue(played.canUndo)
            val undone = oracle.undo(play.state.state)
            assertTrue(played.undo())
            assertEquals("undo save", undone.state, played.serialise().serialise())
            assertEquals("undo text", oracle.text(undone.state), played.textFormat())
            assertTrue(played.canRedo)
            val redone = oracle.redo(undone.state)
            assertTrue(played.redo())
            assertEquals("redo save", redone.state, played.serialise().serialise())

            // Cursor movement and select.
            val cursorPoints = listOf(
                OracleClient.Point(0, 0, Buttons.CURSOR_RIGHT),
                OracleClient.Point(0, 0, Buttons.CURSOR_DOWN),
                OracleClient.Point(0, 0, Buttons.CURSOR_SELECT),
            )
            val cursor = oracle.pointsSized(base.state, cursorPoints)
            val cursorState = Midend.fromSave(Solo, SgtpSave.parse(base.state))
            for (p in cursorPoints) cursorState.processKey(p.x, p.y, p.button, 48)
            assertEquals("cursor save", cursor.state.state, cursorState.serialise().serialise())
            assertEquals("cursor text", oracle.text(cursor.state.state), cursorState.textFormat())

            // The clear key on a filled user cell.
            val clearPoints = listOf(
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '5'.code),
                OracleClient.Point(cx, cy, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '\b'.code),
            )
            val clear = oracle.pointsSized(base.state, clearPoints)
            val cleared = Midend.fromSave(Solo, SgtpSave.parse(base.state))
            for (p in clearPoints) cleared.processKey(p.x, p.y, p.button, 48)
            assertEquals("clear save", clear.state.state, cleared.serialise().serialise())
            assertEquals("clear text", oracle.text(clear.state.state), cleared.textFormat())
        }

    @Test(timeout = 300_000)
    fun `a real completion raises one event`() = OracleClient().use { oracle ->
        val base = oracle.newGame("solo", "3x3", SEED)
        val midend0 = Midend.fromSave(Solo, SgtpSave.parse(base.state))
        val solved = midend0.auxInfo!!.removePrefix("S").split(',').map { it.toInt() }
        val grid = (midend0.state as Solo.State).grid
        val empty = (0 until 81).filter { grid[it] == 0 }
        assertTrue("enough empty cells", empty.size > 1)
        val last = empty.last()
        var text = base.state
        for (i in empty.dropLast(1)) {
            val x = i % 9
            val y = i / 9
            text = appendRecord(text, "MOVE", "R$x,$y,${solved[i]}")
        }
        val almost = Midend.fromSave(Solo, SgtpSave.parse(text))
        assertEquals("almost status", 0, almost.status())
        val x = last % 9
        val y = last / 9
        val points = listOf(
            OracleClient.Point((x + 1) * 48, (y + 1) * 48, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, '0'.code + solved[last]),
        )
        val expected = oracle.pointsSized(text, points)
        for (p in points) almost.processKey(p.x, p.y, p.button, 48)
        assertEquals("completion save", expected.state.state, almost.serialise().serialise())
        assertEquals("completion status", 1, almost.status())
        assertTrue("completion event", almost.takeCompletedEvent())
        assertFalse("completion event is consumed once", almost.takeCompletedEvent())
        assertEquals("completion text", oracle.text(expected.state.state), almost.textFormat())
    }

    @Test(timeout = 600_000)
    fun `the solver matches the oracle with and without auxinfo`() =
        OracleClient().use { oracle ->
            for (params in listOf("2x2", "3x3", "3x3xdb", "3x3ka", "9jdb", "3x4db")) {
                val base = oracle.newGame("solo", params, SEED)
                val noAux = SgtpSave.of(
                    SgtpSave.parse(base.state).records.filter { it.key != "AUXINFO" },
                ).serialise()

                var midend = Midend.fromSave(Solo, SgtpSave.parse(base.state))
                var expected = oracle.solve(base.state)
                assertTrue("$params aux solve", midend.solve())
                assertEquals("$params aux save", expected.state, midend.serialise().serialise())
                assertEquals("$params aux text", oracle.text(expected.state), midend.textFormat())
                assertEquals("$params aux status", 1, midend.status())

                midend = Midend.fromSave(Solo, SgtpSave.parse(noAux))
                expected = oracle.solve(noAux)
                assertTrue("$params solve", midend.solve())
                assertEquals("$params save", expected.state, midend.serialise().serialise())
                assertEquals("$params text", oracle.text(expected.state), midend.textFormat())

                // A second solve on the completed board.
                val again = Midend.fromSave(Solo, SgtpSave.parse(expected.state))
                val expectedAgain = oracle.solve(expected.state)
                assertTrue("$params second solve", again.solve())
                assertEquals("$params second save", expectedAgain.state, again.serialise().serialise())

                // A solve after a real move, started from the first state.
                val click = listOf(
                    OracleClient.Point(192, 192, Buttons.LEFT_BUTTON),
                    OracleClient.Point(0, 0, '5'.code),
                )
                val moved = oracle.pointsSized(noAux, click)
                val movedMidend = Midend.fromSave(Solo, SgtpSave.parse(noAux))
                for (p in click) movedMidend.processKey(p.x, p.y, p.button, 48)
                val expectedMoved = oracle.solve(moved.state.state)
                assertTrue("$params moved solve", movedMidend.solve())
                assertEquals("$params moved save", expectedMoved.state, movedMidend.serialise().serialise())
                assertEquals("$params moved text", oracle.text(expectedMoved.state), movedMidend.textFormat())
            }
        }

    @Test(timeout = 120_000)
    fun `requestKeys matches the oracle on every preset`() = OracleClient().use { oracle ->
        for (params in Solo.presets().map { it.params }) {
            val save = oracle.newGame("solo", params, SEED).state
            val expected = oracle.requestKeys(save)
            val actual = Solo.requestKeys(Solo.decodeParams(params))
            assertEquals("$params arrow mode", expected.arrowMode, 2)
            assertEquals(
                "$params arrow mode",
                name.boyle.chris.sgtpuzzles.engine.ArrowMode.ARROWS_LEFT,
                actual.arrowMode,
            )
            assertEquals(
                "$params labels",
                expected.labels.map { it.label to it.button },
                actual.labels.map { it.name to it.button },
            )
            assertTrue("$params needs arrows", actual.labels.none { it.needsArrows })
            val parsed = Solo.decodeParams(params) as Solo.Params
            assertEquals("$params key count", parsed.c * parsed.r + 2, actual.labels.size)
        }
    }

    private fun firstEmpty(midend: Midend): Pair<Int, Int> {
        val state = midend.state as Solo.State
        for (y in 0 until state.cr) {
            for (x in 0 until state.cr) {
                if (state.valueAt(x, y) == 0) return x to y
            }
        }
        error("no empty cell")
    }

    private fun appendRecord(save: String, key: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "${key.padEnd(8)}:${move.length}:$move\n"
    }

    private companion object {
        const val SEED = "12345"
    }
}
