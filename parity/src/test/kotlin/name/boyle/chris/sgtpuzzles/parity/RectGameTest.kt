package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Rect
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: rect.
 *
 * New game, the move grammar, the solver and its stored answer,
 * completion, undo and redo, the text form, and the frontend hooks.
 */
class RectGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = Rect.presets().map { it.params }

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Rectangles", Rect.name)
        assertEquals("rect", Rect.backendName)
        assertEquals(24, Rect.preferredTileSize)
        assertTrue(Rect.canSolve)
        assertTrue(Rect.canFormatAsText)
        assertTrue(Rect.wantsStatusBar)
        assertFalse(Rect.isTimed)
        assertEquals(0, Rect.flags)
    }

    @Test
    fun `a new game has the C text status and answer`() = OracleClient().use { oracle ->
        for (params in presets + "") {
            val expected = oracle.newGame("rect", params, SEED)
            val midend = Midend.newGame(Rect, params, SEED)
            assertEquals("'$params' id", expected.id, midend.id())
            assertEquals("'$params' save", expected.state, midend.serialise().serialise())
            assertEquals("'$params' text", oracle.text(expected.state), midend.textFormat())
            assertEquals("'$params' status", expected.status, midend.status())
            assertEquals("'$params' status text", "", midend.statusText())
            assertFalse("'$params' canUndo", midend.canUndo)
            assertFalse("'$params' canRedo", midend.canRedo)
            assertNotNull("'$params' aux", midend.auxInfo)
            assertTrue("'$params' aux", midend.auxInfo!!.startsWith("S"))
        }
    }

    @Test
    fun `the accepted moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val aux = SgtpSave.parse(oracle.solve(base.state).state).value("SOLVE")!!
        val moves = listOf(
            "H0,1",
            "H1,1",
            "H2,2",
            "V1,0",
            "V3,4",
            "H 1,1",
            "H+1,+1",
            "H01,01",
            "H1,1x",
            "R0,0,2,2",
            "R0,0,0,0",
            "E0,0,3,3",
            "R 1,1,2,2",
            "R1,1,3,3,4",
            "S1",
            aux,
        )
        for (move in moves) {
            val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
            val state = Rect.executeMove(midend.state, move)
            assertNotNull("Kotlin must accept '${move.take(20)}'", state)
            val save = appendMove(base.state, move)
            val expected = oracle.id(save)
            assertEquals("'${move.take(20)}' id", base.id, expected.id)
            assertEquals("'${move.take(20)}' status", expected.status, Rect.status(state!!))
            val loaded = Midend.fromSave(Rect, SgtpSave.parse(save))
            assertEquals("'${move.take(20)}' reload", state, loaded.state)
        }
    }

    @Test
    fun `the rejected moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val moves = listOf(
            "",
            "X",
            "H",
            "H1",
            "H1,0",
            "H1,7",
            "H7,1",
            "V0,1",
            "V1,7",
            "V7,1",
            "R1,1,3",
            "R-1,0,1,1",
            "R0,0,8,1",
            "R0,0,1,8",
            "E1,1",
        )
        for (move in moves) {
            val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
            assertNull("Kotlin must reject '$move'", Rect.executeMove(midend.state, move))
            val save = appendMove(base.state, move)
            val error = runCatching { oracle.id(save) }.exceptionOrNull()
            assertNotNull("C must reject '$move'", error)
        }

        // `sscanf` ignores trailing text, so a well-formed prefix is
        // accepted even with junk after it.
        val trailing = appendMove(base.state, "H1,1junk")
        assertEquals(base.id, oracle.id(trailing).id)
        assertEquals(1, SgtpSave.parse(oracle.id(trailing).state).values("MOVE").size)
    }

    @Test
    fun `the solver and the stored answer match the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val generated = oracle.newGame("rect", params, SEED)
            val expected = SgtpSave.parse(oracle.solve(generated.state).state).value("SOLVE")!!
            val midend = Midend.newGame(Rect, params, SEED)
            assertEquals("$params stored", expected, midend.auxInfo)
            assertEquals("$params solve", expected, Rect.solve(midend.state, null))
            assertEquals("$params solve with aux", expected, Rect.solve(midend.state, midend.auxInfo))

            val applied = Rect.executeMove(midend.state, expected)!!
            val save = appendMove(generated.state, expected)
            val solved = oracle.id(save)
            assertEquals("$params solved status", solved.status, Rect.status(applied))
            assertEquals("$params cheated status", 0, solved.status)
            assertEquals("$params source id", generated.id, solved.id)
        }

        // The non-unique mode stores one tiling; a solver run with no
        // answer may find another valid one, so only the stored answer
        // is comparable.
        val loose = oracle.newGame("rect", "7x7a", SEED)
        val looseMidend = Midend.newGame(Rect, "7x7a", SEED)
        assertEquals(
            SgtpSave.parse(oracle.solve(loose.state).state).value("SOLVE"),
            Rect.solve(looseMidend.state, looseMidend.auxInfo),
        )
    }

    @Test
    fun `the text form matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets + "7x7a") {
            val expected = oracle.newGame("rect", params, SEED)
            val midend = Midend.newGame(Rect, params, SEED)
            assertEquals("$params text", oracle.text(expected.state), midend.textFormat())
        }
    }

    @Test
    fun `a completed game is sticky and reports one event`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val aux = Midend.fromSave(Rect, SgtpSave.parse(base.state)).auxInfo!!
        val moves = toggleMoves(base.state, aux)
        assertTrue("many edges", moves.size > 10)

        // Apply all but one edge as separate moves, then click the last
        // one for real.
        val last = moves.last()
        val setup = moves.dropLast(1).fold(base.state) { text, move -> appendMove(text, move) }
        assertEquals("C is open", 0, oracle.id(setup).status)
        val midend = Midend.fromSave(Rect, SgtpSave.parse(setup))
        val state = midend.state as Rect.State
        assertEquals("open status", 0, midend.status())
        assertFalse("no event yet", midend.takeCompletedEvent())

        val (x, y) = midpoint(last, 24)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, 24))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_RELEASE, 24))
        assertEquals("win status", 1, midend.status())
        assertEquals("status text", "COMPLETED!", midend.statusText())
        assertTrue("completion event", midend.takeCompletedEvent())
        assertFalse("raised once", midend.takeCompletedEvent())

        // A further toggle keeps the game completed.
        val after = Rect.executeMove(midend.state, last)!!
        assertEquals(1, Rect.status(after))
        assertNotEquals("a real change", state.completed, (after as Rect.State).completed)
    }

    @Test
    fun `undo and redo across a click match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("rect", "7x7", SEED)
        val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))
        val points = listOf(
            OracleClient.Point(38, 26, Buttons.LEFT_BUTTON),
            OracleClient.Point(38, 26, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(base.state, points, 173, 173)
        for (p in points) midend.processKey(p.x, p.y, p.button, 24)
        assertEquals("click save", expected.state.state, midend.serialise().serialise())

        val undone = oracle.undo(expected.state.state)
        assertTrue(midend.undo())
        assertEquals("undo save", undone.state, midend.serialise().serialise())
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertEquals("redo save", redone.state, midend.serialise().serialise())
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // The S payload is one undoable SOLVE entry in the port.
        assertTrue(midend.solve())
        assertEquals("solved status", 0, midend.status())
        assertEquals("Auto-solved.", midend.statusText())
        assertTrue(midend.undo())
        assertEquals("restored status", 0, midend.status())
        assertEquals("restored text", "", midend.statusText())
    }

    private fun midpoint(move: String, tile: Int): Pair<Int, Int> {
        val parts = move.substring(1).split(',').map { it.toInt() }
        val x = parts[0]
        val y = parts[1]
        return if (move[0] == 'H') {
            (2 + x * tile + tile / 2) to (2 + y * tile)
        } else {
            (2 + x * tile) to (2 + y * tile + tile / 2)
        }
    }

    /** The AUXINFO edge bits as one `H`/`V` toggle per edge. */
    private fun toggleMoves(state: String, aux: String): List<String> {
        val save = SgtpSave.parse(state)
        val params = Rect.decodeParams(save.value("PARAMS")!!) as Rect.Params
        val out = mutableListOf<String>()
        var p = 1
        for (y in 0 until params.h) {
            for (x in 1 until params.w) {
                if (aux[p] == '1') out += "V$x,$y"
                p++
            }
        }
        for (y in 1 until params.h) {
            for (x in 0 until params.w) {
                if (aux[p] == '1') out += "H$x,$y"
                p++
            }
        }
        return out
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "MOVE    :${move.length}:$move\n"
    }

    private companion object {
        const val SEED = "12345"
    }
}
