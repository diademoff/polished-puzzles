package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: galaxies.
 *
 * New game, the move grammar, the solver and its stored answer,
 * completion, the hint, undo and redo, the text form, and the frontend
 * hooks.
 */
class GalaxiesGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = Galaxies.presets().map { it.params }

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Galaxies", Galaxies.name)
        assertEquals("galaxies", Galaxies.backendName)
        assertEquals(32, Galaxies.preferredTileSize)
        assertTrue(Galaxies.canSolve)
        assertTrue(Galaxies.canFormatAsText)
        assertFalse(Galaxies.wantsStatusBar)
        assertFalse(Galaxies.isTimed)
        assertEquals(name.boyle.chris.sgtpuzzles.engine.Game.REQUIRE_RBUTTON, Galaxies.flags)
    }

    @Test
    fun `a new game has the C text status and answer`() = OracleClient().use { oracle ->
        for (params in presets + "") {
            val expected = oracle.newGame("galaxies", params, SEED)
            val midend = Midend.newGame(Galaxies, params, SEED)
            assertEquals("'$params' id", expected.id, midend.id())
            assertEquals("'$params' save", expected.state, midend.serialise().serialise())
            assertEquals("'$params' text", oracle.text(expected.state), midend.textFormat())
            assertEquals("'$params' status", expected.status, midend.status())
            assertEquals("'$params' status text", "", midend.statusText())
            assertFalse("'$params' canUndo", midend.canUndo)
            assertFalse("'$params' canRedo", midend.canRedo)
            assertNotNull("'$params' aux", midend.auxInfo)
            assertTrue("'$params' aux", midend.auxInfo!!.startsWith("S;"))
        }
    }

    @Test
    fun `requestKeys matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val base = oracle.newGame("galaxies", params, SEED)
            val expected = oracle.requestKeys(base.state)
            val request = Galaxies.requestKeys(Galaxies.decodeParams(params))
            val full = Galaxies.requestKeys(Galaxies.decodeParams(params), true)
            assertTrue("'$params' labels", expected.labels.isEmpty())
            assertTrue("'$params' engine labels", request.labels.isEmpty())
            assertTrue("'$params' full-cursor engine labels", full.labels.isEmpty())
            assertEquals("'$params' arrow mode", 3, expected.arrowMode)
            assertEquals(
                "'$params' engine arrow mode",
                name.boyle.chris.sgtpuzzles.engine.ArrowMode.ARROWS_LEFT_RIGHT,
                request.arrowMode,
            )
            assertEquals(
                "'$params' full-cursor arrow mode",
                name.boyle.chris.sgtpuzzles.engine.ArrowMode.ARROWS_ONLY,
                full.arrowMode,
            )
            assertEquals(
                "'$params' full-cursor oracle arrow mode",
                1,
                oracle.requestKeys(base.state, fullCursor = true).arrowMode,
            )
        }
    }

    @Test
    fun `the accepted moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        val dots = (midend.state as Galaxies.State).dotPositions()
        val dot = dots.first()
        val aux = midend.auxInfo!!
        val moves = mutableListOf(
            "",
            "E2,1",
            "E8,11",
            "E1,2",
            "E13,6",
            "E 2,1",
            "E2, 1",
            "E+2,+1",
            "E02,01",
            "E2,1;",
            "A1,1,${dot.first},${dot.second}",
            "A 1,1,${dot.first},${dot.second}",
            "M${dot.first},${dot.second}",
            "S",
            aux,
        )
        val associated = associatedTile(base.state)
        moves += "U${associated.first},${associated.second}"

        for (move in moves) {
            val source = if (move.startsWith("U")) {
                stateWithFirstAssociation(base.state)
            } else {
                base.state
            }
            val save = appendMove(source, move)
            val expected = oracle.id(save)
            val portMidend = Midend.fromSave(Galaxies, SgtpSave.parse(source))
            val state = Galaxies.executeMove(portMidend.state, move)
            assertNotNull("Kotlin must accept '${move.take(20)}'", state)
            assertEquals("'${move.take(20)}' id", base.id, expected.id)
            assertEquals("'${move.take(20)}' status", expected.status, Galaxies.status(state!!))
            val loaded = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            assertEquals("'${move.take(20)}' reload", state, loaded.state)
            // Galaxies stores only DESC and the move history, so the
            // save text is move-invariant; the text form carries the
            // resulting board.
            assertEquals(
                "'${move.take(20)}' text",
                oracle.text(save),
                loaded.textFormat(),
            )
        }
    }

    @Test
    fun `the rejected moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        val dot = (midend.state as Galaxies.State).dotPositions().first()
        val dotToken = "A1,1,${dot.first},${dot.second}"
        val holdSave = appendMove(base.state, "M${dot.first},${dot.second}")
        val moves = listOf(
            "X",
            "E",
            "E1",
            "E1,2,3",
            "E-1,1",
            "E0,1",
            "E14,1",
            "E1,14",
            "E2,14",
            "E2,2",
            "E1,1",
            "E2,1x",
            "E2,1 x",
            "E2,1;;",
            "U1,2",
            "U2,2",
            "M1,1",
            "MOOPS",
            "A1,1,0,0",
            "A1,1,1",
            "A1,1,1,1,1",
            "A0,1,${dot.first},${dot.second}",
            "A14,1,${dot.first},${dot.second}",
            "SX",
            ";",
        )
        for (move in moves) {
            val portMidend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
            assertNull("Kotlin must reject '$move'", Galaxies.executeMove(portMidend.state, move))
            val error = runCatching { oracle.id(appendMove(base.state, move)) }.exceptionOrNull()
            assertNotNull("C must reject '$move'", error)
        }

        // A held dot refuses the association that would use it.
        val portHold = Midend.fromSave(Galaxies, SgtpSave.parse(holdSave))
        assertNull(
            "Kotlin must reject an association with a held dot",
            Galaxies.executeMove(portHold.state, dotToken),
        )
        assertNotNull(
            "C must reject an association with a held dot",
            runCatching { oracle.id(appendMove(holdSave, dotToken)) }.exceptionOrNull(),
        )
    }

    @Test
    fun `the solver and the stored answer match the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val generated = oracle.newGame("galaxies", params, SEED)
            val expected = SgtpSave.parse(oracle.solve(generated.state).state).value("SOLVE")!!
            val midend = Midend.newGame(Galaxies, params, SEED)
            // The stored answer carries the associations as well as the
            // edges; `solve_game` clears them and emits only the edges.
            val aux = midend.auxInfo!!
            val edges = aux.split(';').filter { it.startsWith("E") }
            assertEquals("$params edge answer", "S;" + edges.joinToString(";"), expected)
            assertEquals("$params solve", expected, Galaxies.solve(midend.state, aux))

            val save = appendRecord(generated.state, "SOLVE", expected)
            val solved = oracle.id(save)
            assertTrue("$params solve completes", midend.solve())
            assertEquals("$params solved save", solved.state, midend.serialise().serialise())
            assertEquals("$params solved status", solved.status, midend.status())
            assertEquals("$params source id", generated.id, midend.id())
            assertTrue("$params uses the solve", (midend.state as Galaxies.State).usedSolve)
        }
    }

    @Test
    fun `a solve without an answer matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val generated = oracle.newGame("galaxies", params, SEED)
            val noAux = withoutRecord(generated.state, "AUXINFO")
            val expected = SgtpSave.parse(oracle.solve(noAux).state).value("SOLVE")!!
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(noAux))
            assertNull("$params no stored answer", midend.auxInfo)
            assertEquals("$params solved by the solver", expected, Galaxies.solve(midend.state, null))
        }
    }

    @Test
    fun `associations on a partial board match the oracle`() = OracleClient().use { oracle ->
        // Remove one edge from the stored answer, then associate tiles
        // from several dots. The colour test reads the space
        // coordinates, so a wrong index changes the board (or throws).
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val solve = SgtpSave.parse(oracle.solve(base.state).state).value("SOLVE")!!
        val solved = appendMove(base.state, solve)
        val partial = appendMove(solved, "E1,2")
        val dots = (Midend.fromSave(Galaxies, SgtpSave.parse(partial)).state as Galaxies.State)
            .dotPositions()
            .take(4)
        var checked = 0
        for (y in 1..12) {
            for (x in 1..12) {
                for ((dx, dy) in dots.take(6)) {
                val move = "A$x,$y,$dx,$dy"
                val save = appendMove(partial, move)
                val expected = oracle.id(save)
                val loaded = Midend.fromSave(Galaxies, SgtpSave.parse(save))
                assertEquals("'$move' text", oracle.text(save), loaded.textFormat())
                checked++
                }
            }
        }
        assertTrue("enough associations", checked >= 300)
    }

    @Test
    fun `the solver uses the initial state`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val moved = appendMove(base.state, "E2,1")
        val expected = oracle.solve(moved)
        val payload = SgtpSave.parse(expected.state).value("SOLVE")!!
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(moved))
        assertEquals("moved payload", payload, Galaxies.solve(midend.state, midend.auxInfo))
        assertTrue("solves the moved board", midend.solve())
        assertEquals("moved solved save", expected.state, midend.serialise().serialise())
        assertEquals("moved solved status", expected.status, midend.status())

        // Without the stored answer the solver tries the current state,
        // then the initial one; both runs are compared to C.
        val noAux = withoutRecord(moved, "AUXINFO")
        val expectedNoAux = oracle.solve(noAux)
        val noAuxMidend = Midend.fromSave(Galaxies, SgtpSave.parse(noAux))
        assertNull("no stored answer", noAuxMidend.auxInfo)
        assertEquals(
            "no-aux payload",
            SgtpSave.parse(expectedNoAux.state).value("SOLVE"),
            Galaxies.solve(noAuxMidend.state, null),
        )
    }

    @Test
    fun `a held dot does not block the stored answer`() = OracleClient().use { oracle ->
        // `M` sets F_DOT_HOLD in a hand-written save. The initial state
        // has no held dots, so the stored answer still applies.
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val hold = appendMove(base.state, "M3,1")
        val expected = oracle.solve(hold)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(hold))
        assertEquals(
            "held solve payload",
            SgtpSave.parse(expected.state).value("SOLVE"),
            Galaxies.solve(midend.state, midend.auxInfo),
        )
        assertTrue("held solve", midend.solve())
        assertEquals("held solved save", expected.state, midend.serialise().serialise())
        assertEquals("held solved status", expected.status, midend.status())

        val noAux = withoutRecord(hold, "AUXINFO")
        val expectedNoAux = oracle.solve(noAux)
        val noAuxMidend = Midend.fromSave(Galaxies, SgtpSave.parse(noAux))
        assertEquals(
            "held no-aux payload",
            SgtpSave.parse(expectedNoAux.state).value("SOLVE"),
            Galaxies.solve(noAuxMidend.state, null),
        )
    }

    @Test
    fun `the text form matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val expected = oracle.newGame("galaxies", params, SEED)
            val midend = Midend.newGame(Galaxies, params, SEED)
            assertEquals("$params text", oracle.text(expected.state), midend.textFormat())
        }

        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val withMove = appendMove(base.state, "E2,1")
        val expected = oracle.text(withMove)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(withMove))
        assertEquals("moved text", expected, midend.textFormat())
        assertTrue("the moved text shows the edge", expected.contains("|"))
    }

    @Test
    fun `the hint matches the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val expected = oracle.key(base.state, 0, 0, 'h'.code)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'h'.code, 32))
        assertEquals("hint save", expected.state, midend.serialise().serialise())
        assertEquals("hint changes the board", 1, SgtpSave.parse(expected.state).values("MOVE").size)

        // The hint asks the obvious solver only, so it is a subset of
        // the stored answer in the same token order.
        val hint = SgtpSave.parse(expected.state).values("MOVE").last()
        assertFalse("hint is not empty", hint.isEmpty())
        val aux = midend.auxInfo!!
        val auxTokens = aux.split(';').toSet()
        for (token in hint.split(';')) {
            val normalized = token.replace('A', 'a')
            assertTrue(
                "hint token '$token' is in the answer",
                normalized in auxTokens,
            )
        }
    }

    @Test
    fun `undo and redo across a hint match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("galaxies", "7x7dn", SEED)
        val hinted = oracle.key(base.state, 0, 0, 'h'.code)
        val undone = oracle.undo(hinted.state)
        val redone = oracle.redo(undone.state)

        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        midend.processKey(0, 0, 'h'.code, 32)
        assertEquals("hinted", hinted.state, midend.serialise().serialise())
        assertTrue(midend.canUndo)
        assertTrue(midend.undo())
        assertEquals("undone", undone.state, midend.serialise().serialise())
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)
        assertTrue(midend.redo())
        assertEquals("redone", redone.state, midend.serialise().serialise())
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
    }

    @Test
    fun `a real click completes the game and raises one event`() =
        OracleClient().use { oracle ->
            val base = oracle.newGame("galaxies", "7x7dn", SEED)
            val aux = Midend.fromSave(Galaxies, SgtpSave.parse(base.state)).auxInfo!!
            val edges = aux.split(';').filter { it.startsWith("E") }
            assertTrue("many edges", edges.size > 10)

            val setup = edges.dropLast(1).fold(base.state) { text, move ->
                appendMove(text, move)
            }
            assertEquals("C is open", 0, oracle.id(setup).status)
            val midend = Midend.fromSave(Galaxies, SgtpSave.parse(setup))
            assertEquals("port is open", 0, midend.status())
            assertFalse("no event yet", midend.takeCompletedEvent())

            val last = edges.last()
            val (x, y) = edgeMidpoint(last, 32)
            val expected = oracle.pointsSized(
                setup,
                listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                ),
            )
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(x, y, Buttons.LEFT_BUTTON, 32))
            // A release is not a key the game uses: the click made the
            // move on the press.
            assertEquals(KeyOutcome.Unused, midend.processKey(x, y, Buttons.LEFT_RELEASE, 32))
            assertEquals("win status", 1, midend.status())
            assertEquals("status text", "", midend.statusText())
            assertTrue("completion event", midend.takeCompletedEvent())
            assertFalse("raised once", midend.takeCompletedEvent())
            assertEquals("completed save", expected.state.state, midend.serialise().serialise())

            // A solve after a completed game keeps it completed and uses
            // the solve flag, which suppresses the event.
            assertTrue(midend.solve())
            assertEquals("solved status", 1, midend.status())
            assertFalse("solved event", midend.takeCompletedEvent())
            assertTrue((midend.state as Galaxies.State).usedSolve)

            // A second solve starts from the initial state and diffs
            // against the completed board, so it is the bare `S` mark
            // and toggles nothing.
            val second = oracle.solve(midend.serialise().serialise())
            assertEquals("second payload", "S", SgtpSave.parse(second.state).value("SOLVE"))
            assertTrue("second solve", midend.solve())
            assertEquals("second save", second.state, midend.serialise().serialise())
            assertEquals("second status", 1, midend.status())

            // A completed state is sticky across another move.
            val after = Galaxies.executeMove(midend.state, "E2,1") as Galaxies.State
            assertTrue("completion is sticky", after.completed)
            assertEquals(1, Galaxies.status(after))
        }

    private fun associatedTile(save: String): Pair<Int, Int> {
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
        val state = midend.state as Galaxies.State
        val dots = state.dotPositions()
        val token = "A1,1,${dots.first().first},${dots.first().second}"
        val next = Galaxies.executeMove(state, token) as Galaxies.State
        for (y in 1 until next.sy - 1) {
            for (x in 1 until next.sx - 1) {
                if (next.isAssociated(x, y)) return x to y
            }
        }
        error("the association move associated no tile")
    }

    private fun stateWithFirstAssociation(save: String): String {
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
        val dot = (midend.state as Galaxies.State).dotPositions().first()
        return appendMove(save, "A1,1,${dot.first},${dot.second}")
    }

    private fun appendMove(save: String, move: String): String =
        appendRecord(save, "MOVE", move)

    private fun appendRecord(save: String, key: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        val padded = key.padEnd(8)
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "$padded:${move.length}:$move\n"
    }

    private fun withoutRecord(save: String, key: String): String = save.lines()
        .filterNot { it.startsWith("$key") }
        .joinToString("\n", postfix = "\n")

    /** The view midpoint of an `E<x>,<y>` edge token. */
    private fun edgeMidpoint(move: String, tile: Int): Pair<Int, Int> {
        val parts = move.substring(1).split(',').map { it.toInt() }
        val x = parts[0]
        val y = parts[1]
        return if (x % 2 == 0) {
            (tile + (x / 2) * tile) to (tile + ((y - 1) / 2) * tile + tile / 2)
        } else {
            (tile + ((x - 1) / 2) * tile + tile / 2) to (tile + (y / 2) * tile)
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
