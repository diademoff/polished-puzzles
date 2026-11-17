package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: map.
 *
 * The new game, the real pointer grammar, the pencil rule, completion,
 * undo and redo, the two solve modes, the two C solve errors, the
 * requested keys, and the absence of a text form.
 */
class MapGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    /** A one-state save for a hand-written map description. */
    private fun handSave(desc: String, params: String = "5x2n5dn"): String =
        "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :3:Map\n" +
            "PARAMS  :${params.length}:$params\n" +
            "CPARAMS :${params.length}:$params\n" +
            "SEED    :5:12345\n" +
            "DESC    :${desc.length}:$desc\n" +
            "NSTATES :1:1\n" +
            "STATEPOS:1:1\n"

    private fun removeRecord(save: String, key: String): String =
        save.lineSequence().filterNot { it.startsWith("$key") }.joinToString("\n", postfix = "\n")

    @Test(timeout = 120_000)
    fun `a new game starts open and a real drag completes it`() = OracleClient().use { oracle ->
        val save = handSave("fh,0123a")
        val expected = oracle.id(save)
        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        assertEquals("5x2n5:fh,0123a", expected.id)
        assertEquals(expected.id, midend.id())
        assertEquals(expected.status, midend.status())
        assertFalse(midend.canUndo)
        assertFalse(midend.canRedo)

        // Press on the clue in region 0 and release on the blank region 4.
        val points = listOf(
            OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
            OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
        )
        val dragged = oracle.pointsSized(save, points, width = 141, height = 81)
        assertEquals("0:4", SgtpSave.parse(dragged.state.state).value("MOVE"))
        assertEquals(1, dragged.state.status)
        assertTrue(dragged.state.canUndo)

        for (point in points) {
            midend.processKey(point.x, point.y, point.button, 20)
        }
        assertEquals(dragged.state.id, midend.id())
        assertEquals(dragged.state.state, midend.serialise().serialise())
        assertEquals(1, midend.status())
        assertEquals(2, midend.stateCount)
        assertEquals("0:4", midend.serialise().value("MOVE"))
        assertTrue(midend.takeCompletedEvent())

        // A press and release on the same clue is UI-only.
        val same = Midend.fromSave(Map, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.SomeEffect,
            same.processKey(30, 30, Buttons.LEFT_BUTTON, 20),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            same.processKey(30, 30, Buttons.LEFT_RELEASE, 20),
        )
        assertEquals(1, same.stateCount)
        assertEquals(0, same.status())
    }

    @Test(timeout = 120_000)
    fun `a right drag toggles one pencil bit`() = OracleClient().use { oracle ->
        val save = handSave("fh,0123a")
        val points = listOf(
            OracleClient.Point(30, 30, Buttons.RIGHT_BUTTON),
            OracleClient.Point(110, 30, Buttons.RIGHT_RELEASE),
        )
        val dragged = oracle.pointsSized(save, points, width = 141, height = 81)
        assertEquals("p0:4", SgtpSave.parse(dragged.state.state).value("MOVE"))
        assertEquals(0, dragged.state.status)

        val midend = Midend.fromSave(Map, SgtpSave.parse(save))
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, 20)
        }
        assertEquals(dragged.state.state, midend.serialise().serialise())
        assertEquals(0, midend.status())
        assertEquals(2, midend.stateCount)

        // A second right drag toggles the bit back.
        val again = oracle.pointsSized(
            dragged.state.state,
            points,
            width = 141,
            height = 81,
        )
        assertEquals(3, SgtpSave.parse(again.state.state).value("NSTATES")!!.toInt())
        assertEquals("p0:4", SgtpSave.parse(again.state.state).value("MOVE"))
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, 20)
        }
        assertEquals(again.state.state, midend.serialise().serialise())

        // A left drag then a right drag to a coloured region is UI-only.
        val open = Midend.fromSave(Map, SgtpSave.parse(handSave("fh,0a0a0")))
        assertEquals(
            KeyOutcome.SomeEffect,
            open.processKey(30, 30, Buttons.LEFT_BUTTON, 20),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            open.processKey(50, 30, Buttons.LEFT_RELEASE, 20),
        )
        assertEquals("0:1", open.serialise().value("MOVE"))
        assertEquals(
            KeyOutcome.SomeEffect,
            open.processKey(30, 30, Buttons.RIGHT_BUTTON, 20),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            open.processKey(50, 30, Buttons.RIGHT_RELEASE, 20),
        )
        // The target is coloured, so no move is added.
        assertEquals(2, open.stateCount)
    }

    @Test(timeout = 120_000)
    fun `completion undo and redo match the oracle`() = OracleClient().use { oracle ->
        val save = handSave("fh,0123a")
        val moved = oracle.pointsSized(
            save,
            listOf(
                OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
            ),
            width = 141,
            height = 81,
        ).state.state
        assertEquals(1, oracle.id(moved).status)

        val undone = oracle.undo(moved).state
        assertEquals(0, oracle.id(undone).status)
        // Undo moves the position back and keeps both states.
        assertEquals(2, SgtpSave.parse(undone).value("NSTATES")!!.toInt())
        assertEquals(1, SgtpSave.parse(undone).value("STATEPOS")!!.toInt())
        val redone = oracle.redo(undone).state
        assertEquals(1, oracle.id(redone).status)

        val midend = Midend.fromSave(Map, SgtpSave.parse(moved))
        assertEquals(1, midend.status())
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertTrue(midend.canRedo)
        assertFalse(midend.takeCompletedEvent())
        assertEquals(undone, midend.serialise().serialise())
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        assertEquals(redone, midend.serialise().serialise())

        // A fully-clued valid board starts at status 0 and a solve
        // reaches 1; the first move sets the sticky flag.
        val full = handSave("fh,01010")
        assertEquals(0, oracle.id(full).status)
        assertEquals(0, Midend.fromSave(Map, SgtpSave.parse(full)).status())
    }

    @Test(timeout = 300_000)
    fun `the solve with AUXINFO matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Map.presets().map { it.params } + "") {
                val save = oracle.newGame("map", params, SEED).state
                val solved = oracle.solve(save)
                val move = SgtpSave.parse(solved.state).value("SOLVE")!!
                assertTrue("'$params' solve starts with S", move.startsWith("S"))
                assertEquals("'$params' solved status", 1, solved.status)

                val midend = Midend.fromSave(Map, SgtpSave.parse(save))
                assertEquals(
                    "'$params' aux solve",
                    move,
                    Map.solve(midend.state, midend.auxInfo),
                )
                assertTrue(midend.solve())
                assertEquals("'$params' status", 1, midend.status())
                assertTrue((midend.state as Map.State).cheated)
                assertEquals(
                    "'$params' solved save",
                    solved.state,
                    midend.serialise().serialise(),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the solve without AUXINFO matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("20x15n30dn", "20x15n30de", "20x15n30dh", "30x25n75dn")) {
            val save = removeRecord(oracle.newGame("map", params, SEED).state, "AUXINFO")
            val solved = oracle.solve(save)
            assertNull("the oracle used no aux", SgtpSave.parse(solved.state).value("AUXINFO"))
            val move = SgtpSave.parse(solved.state).value("SOLVE")!!
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            assertNull(midend.auxInfo)
            assertEquals("'$params' diff solve", move, Map.solve(midend.state, null))
            assertEquals(move, Map.solveAttempt(midend.state, null).move)
            assertNull(Map.solveAttempt(midend.state, null).error)
        }
    }

    @Test(timeout = 120_000)
    fun `the two C solve errors match the oracle`() = OracleClient().use { oracle ->
        for ((desc, message) in listOf(
            "fh,00000" to "Puzzle is inconsistent",
            "fh,0123a" to "Unable to find a unique solution for this puzzle",
        )) {
            val save = handSave(desc)
            val error = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("oracle '$desc'", message, error)
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            assertEquals("engine '$desc'", message, Map.solveError(midend.state, null))
            assertNull(Map.solve(midend.state, null))
            assertFalse(midend.solve())
            assertEquals(1, midend.stateCount)
        }
    }

    @Test(timeout = 120_000)
    fun `the keyboard cursor picks and drops region 0 into region 4`() =
        OracleClient().use { oracle ->
            val save = handSave("fh,0123a")
            // Show the cursor, pick, move right four times, drop.
            val sequence = listOf(
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_SELECT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            )
            val expected = oracle.keys(save, sequence).state
            assertTrue(expected.lines().any { it.startsWith("MOVE") })

            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            for (key in sequence) {
                midend.processKey(0, 0, key, 20)
            }
            assertEquals(expected, midend.serialise().serialise())
            assertEquals(1, midend.status())
            assertEquals("0:4", midend.serialise().value("MOVE"))

            // A modified arrow is not a cursor move, so it changes
            // nothing. A plain arrow after it still moves.
            val plain = Midend.fromSave(Map, SgtpSave.parse(save))
            assertEquals(
                KeyOutcome.Unused,
                plain.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, 20),
            )
            assertEquals(1, plain.stateCount)
            assertEquals(
                KeyOutcome.SomeEffect,
                plain.processKey(0, 0, Buttons.CURSOR_RIGHT, 20),
            )
        }

    @Test(timeout = 120_000)
    fun `the requested keys and the empty text match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("map", "", SEED).state
            val expected = oracle.requestKeys(save)
            val actual = Map.requestKeys(Map.defaultParams())
            assertEquals(3, expected.arrowMode)
            assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
            assertEquals(2, expected.labels.size)
            assertEquals(expected.labels.size, actual.labels.size)
            for (i in expected.labels.indices) {
                assertEquals(expected.labels[i].label, actual.labels[i].name)
                assertEquals(expected.labels[i].button, actual.labels[i].button)
                assertEquals(expected.labels[i].needsArrows, actual.labels[i].needsArrows)
            }
            // The full-cursor hint does not change map.
            assertEquals(expected, oracle.requestKeys(save, fullCursor = true))

            assertEquals("", oracle.text(save))
            val midend = Midend.fromSave(Map, SgtpSave.parse(save))
            assertFalse(Map.canFormatAsText)
            assertEquals("", midend.textFormat())
            assertEquals("", Map.textFormat(midend.state))
            assertFalse(Map.wantsStatusBar)
            assertEquals("", Map.statusText(midend.state, midend.ui))
            assertEquals("", midend.statusText())
        }

    @Test(timeout = 120_000)
    fun `a clue overwritten by a hand-written move is repaired by the solve`() =
        OracleClient().use { oracle ->
            // C calls solve with `states[0]` as the clue array and asserts
            // that a changed region is not a clue, so it aborts here. The
            // port includes the region in the diff and restores the clue.
            val save = handSave("fh,01010")
            val overwritten = save
                .replace("NSTATES :1:1", "NSTATES :1:2")
                .replace("STATEPOS:1:1", "STATEPOS:1:2") +
                "MOVE    :3:2:0\n"
            val midend = Midend.fromSave(Map, SgtpSave.parse(overwritten))
            assertEquals(2, (midend.state as Map.State).colouring[0])
            // The oracle loads the overwritten save and reports the same
            // id, status, and canonical text; only its solve would abort.
            // The test never asks C to solve this save.
            val loaded = oracle.id(overwritten)
            assertEquals(midend.id(), loaded.id)
            assertEquals(midend.status(), loaded.status)
            assertEquals(loaded.state, midend.serialise().serialise())

            val move = Map.solve(midend.state, null)
            assertEquals("S;0:0", move)
            assertTrue(midend.solve())
            assertEquals(0, (midend.state as Map.State).colouring[0])
            assertTrue((midend.state as Map.State).completed)
            assertTrue((midend.state as Map.State).cheated)
        }

    private companion object {
        const val SEED = "12345"
    }
}
