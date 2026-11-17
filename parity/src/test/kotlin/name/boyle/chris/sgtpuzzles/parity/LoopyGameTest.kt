package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: loopy.
 *
 * New game, the `%d[ynu]` move grammar, the solver, completion, undo and
 * redo, the text gate, and the frontend defaults. Every accepted move is
 * compared through the C `execute_move`, and every rejected move through
 * the C failure path.
 *
 * Loopy has no `PRIVDESC`, no `UI`, and no `AUXINFO`, and its solver is a
 * plain `S` payload with `%d[yn]` pairs.
 */
class LoopyGameTest {

    private val presets = Loopy.presets().map { it.params }

    @Test
    fun `the game identity and flags are the C game`() {
        assertEquals("Loopy", Loopy.name)
        assertEquals("loopy", Loopy.backendName)
        assertEquals(32, Loopy.preferredTileSize)
        assertTrue(Loopy.canSolve)
        assertTrue(Loopy.canFormatAsText)
        assertFalse(Loopy.wantsStatusBar)
        assertFalse(Loopy.isTimed)
        assertEquals(0, Loopy.flags)
    }

    @Test
    fun `a new game has the C text and status`() = OracleClient().use { oracle ->
        for (params in presets + "") {
            val expected = oracle.newGame("loopy", params, SEED)
            val midend = Midend.newGame(Loopy, params, SEED)
            assertEquals("'$params' id", expected.id, midend.id())
            assertEquals("'$params' status", expected.status, midend.status())
            assertEquals("'$params' save", expected.state, midend.serialise().serialise())
            assertFalse("'$params' canUndo", midend.canUndo)
            assertFalse("'$params' canRedo", midend.canRedo)
            assertNull("'$params' aux", midend.auxInfo)
            // The text form exists only for the square grid; C's
            // `midend_text_format` returns NULL for the other types and
            // the oracle protocol writes that as an empty string.
            assertEquals("'$params' text", oracle.text(expected.state), midend.textFormat())
        }
    }

    @Test
    fun `request keys is the C null hook`() = OracleClient().use { oracle ->
        val save = oracle.newGame("loopy", "7x7t0dh", SEED).state
        val normal = oracle.requestKeys(save)
        val kotlinNormal = Loopy.requestKeys(Loopy.decodeParams("7x7t0dh"))
        assertEquals(3, normal.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, kotlinNormal.arrowMode)
        assertTrue(kotlinNormal.labels.isEmpty())
        assertEquals(
            normal.labels.map { Triple(it.label, it.button, it.needsArrows) },
            kotlinNormal.labels.map { Triple(it.name, it.button, it.needsArrows) },
        )

        val full = oracle.requestKeys(save, fullCursor = true)
        val kotlinFull = Loopy.requestKeys(Loopy.decodeParams("7x7t0dh"), fullCursor = true)
        assertEquals(1, full.arrowMode)
        assertEquals(ArrowMode.ARROWS_ONLY, kotlinFull.arrowMode)
        assertTrue(kotlinFull.labels.isEmpty())
    }

    @Test
    fun `the accepted moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("loopy", "7x7t0de", SEED)
        val moves = listOf(
            "",
            "0y",
            "0n",
            "0u",
            "0y1n2u",
            "S",
            "S0y",
            "00y",
            "0y0n",
            "0y1n2u3y4n5u6y7n8u9y",
            // The C digit scan skips no leading digit, so a bare letter
            // parses `atoi("y") == 0` and re-sets edge 0.
            "y",
            "0yy",
        )
        for (move in moves) {
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            val state = Loopy.executeMove(midend.state, move)
            assertNotNull("Kotlin must accept '$move'", state)
            val save = withMoves(base.state, listOf(move))
            val expected = oracle.id(save)
            assertEquals("'$move' id", base.id, expected.id)
            assertEquals("'$move' status", expected.status, Loopy.status(state!!))
            assertEquals("'$move' text", oracle.text(save), Loopy.textFormat(state))
            val loaded = Midend.fromSave(Loopy, SgtpSave.parse(save))
            assertEquals("'$move' reload", state, loaded.state)
        }
    }

    @Test
    fun `the rejected moves match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("loopy", "7x7t0de", SEED)
        val moves = listOf(
            "0",
            "0x",
            "0y1",
            "0y;1n",
            "0 y",
            " 0y",
            "-1y",
            "+1y",
            "0.5y",
            "112y",
            "999y",
            "0y ",
            "0y\n1n",
        )
        for (move in moves) {
            val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
            assertNull("Kotlin must reject '$move'", Loopy.executeMove(midend.state, move))
            val save = withMoves(base.state, listOf(move))
            val error = runCatching { oracle.id(save) }.exceptionOrNull()
            assertNotNull("C must reject '$move'", error)
        }

        // A save loader never reaches execute_move with these strings:
        // the C parser rejects the record first. The engine test pins the
        // saturation of an overflowing `atoi`, which C leaves undefined
        // and every caller rejects.
        val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
        assertNull(Loopy.executeMove(midend.state, "99999999999999999999y"))
    }

    @Test
    fun `the text gate follows the grid type`() = OracleClient().use { oracle ->
        for (params in presets) {
            val p = Loopy.decodeParams(params) as Loopy.Params
            val midend = Midend.newGame(Loopy, params, SEED)
            val expected = oracle.text(midend.serialise().serialise())
            assertEquals("$params gate", p.type == 0, Loopy.canFormatAsTextNow(p))
            assertEquals("$params text gate", p.type == 0, expected.isNotEmpty())
            assertEquals("$params text", expected, midend.textFormat())
            if (p.type != 0) assertEquals("$params empty", "", expected)
        }
    }

    @Test
    fun `the solver payload matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in presets) {
                val generated = oracle.newGame("loopy", params, SEED)
                val expected = SgtpSave.parse(oracle.solve(generated.state).state)
                    .value("SOLVE")!!
                val midend = Midend.newGame(Loopy, params, SEED)
                val actual = Loopy.solve(midend.state)
                assertEquals("$params solve", expected, actual)

                // The C save applies the payload as a MOVE; the port does
                // the same, and the cheated flag does not change status.
                val applied = Loopy.executeMove(midend.state, actual!!)!!
                val save = withMoves(generated.state, listOf(actual))
                val solved = oracle.id(save)
                assertEquals("$params solved id", generated.id, solved.id)
                assertEquals("$params solved status", solved.status, Loopy.status(applied))
                assertEquals("$params solved", 1, Loopy.status(applied))
                if ((Loopy.decodeParams(params) as Loopy.Params).type == 0) {
                    assertEquals("$params solved text", oracle.text(save), Loopy.textFormat(applied))
                }
            }
        }

    @Test
    fun `completion is sticky and reported once`() = OracleClient().use { oracle ->
        val params = "7x7t0de"
        val generated = oracle.newGame("loopy", params, SEED)
        val solve = SgtpSave.parse(oracle.solve(generated.state).state).value("SOLVE")!!
        val midend = Midend.newGame(Loopy, params, SEED)
        assertFalse(midend.takeCompletedEvent())

        val clip = midend.serialise().serialise()
        val solvedSave = withMoves(clip, listOf(solve))
        val solved = Midend.fromSave(Loopy, SgtpSave.parse(solvedSave))
        assertEquals("status", oracle.id(solvedSave).status, solved.status())
        assertEquals(1, solved.status())

        // A further move keeps the board solved, as `check_completion`
        // leaves the sticky flag set.
        val after = solved.serialise().serialise()
        val more = withMoves(after, listOf("0n"))
        assertEquals(1, oracle.id(more).status)
        val applied = Loopy.executeMove(solved.state, "0n")!!
        assertEquals(1, Loopy.status(applied))
    }

    @Test
    fun `a real click and undo redo match the oracle`() = OracleClient().use { oracle ->
        val params = "7x7t0de"
        val generated = oracle.newGame("loopy", params, SEED)
        val save = generated.state
        val tile = 48
        val midend = Midend.fromSave(Loopy, SgtpSave.parse(save))
        val (x, y, index) = firstEdgePoint(midend, tile)
        val view = Loopy.computeSize(Loopy.decodeParams(params), tile)
        val points = listOf(
            OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
            OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, points, view.width, view.height)
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("click save", expected.state.state, midend.serialise().serialise())
        assertEquals("click moves", listOf("${index}y"), SgtpSave.parse(expected.state.state).values("MOVE"))

        val undone = oracle.undo(expected.state.state)
        assertTrue(midend.undo())
        assertEquals("undo id", undone.id, midend.id())
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertEquals("redo id", redone.id, midend.id())
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // A second click on the same edge advances the three-state cycle.
        val second = OracleClient.Point(x, y, Buttons.LEFT_BUTTON)
        val third = OracleClient.Point(x, y, Buttons.LEFT_RELEASE)
        val twice = oracle.pointsSized(expected.state.state, listOf(second, third), view.width, view.height)
        midend.processKey(second.x, second.y, second.button, tile)
        midend.processKey(third.x, third.y, third.button, tile)
        assertEquals("second click", twice.state.state, midend.serialise().serialise())
        assertEquals("second click value", "${index}n", SgtpSave.parse(twice.state.state).values("MOVE").last())
        assertNotEquals("the cycle moved", expected.state.state, twice.state.state)
    }

    @Test
    fun `the three mouse buttons match the oracle`() = OracleClient().use { oracle ->
        val params = "6x6t16dh"
        val base = oracle.newGame("loopy", params, SEED)
        val tile = 40
        val view = Loopy.computeSize(Loopy.decodeParams(params), tile)
        for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON, Buttons.MIDDLE_BUTTON)) {
            for (click in 1..3) {
                val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
                val (x, y, index) = firstEdgePoint(midend, tile)
                val points = mutableListOf<OracleClient.Point>()
                repeat(click) {
                    points += OracleClient.Point(x, y, button)
                    points += OracleClient.Point(x, y, button + 6)
                }
                val expected = oracle.pointsSized(base.state, points, view.width, view.height)
                for (p in points) midend.processKey(p.x, p.y, p.button, tile)
                assertEquals("$button click $click save", expected.state.state, midend.serialise().serialise())
                val moves = SgtpSave.parse(expected.state.state).values("MOVE")
                assertEquals("$button click $click moves", click, moves.size)
                val last = moves.last().last()
                assertEquals("$button click $click edge", "$index", moves.last().dropLast(1))
                val expectedChar = when (button) {
                    Buttons.LEFT_BUTTON -> "ynu"[click - 1]
                    Buttons.RIGHT_BUTTON -> "nyu"[click - 1]
                    else -> "uuu"[click - 1]
                }
                assertEquals("$button click $click value", expectedChar, last)
            }
        }
    }

    private fun firstEdgePoint(midend: Midend, tile: Int): Triple<Int, Int, Int> {
        val state = midend.state as Loopy.State
        val g = state.grid
        val index = state.lines.indices.first()
        val e = g.edges[index]
        val gx = (e.dot1.x + e.dot2.x) / 2
        val gy = (e.dot1.y + e.dot2.y) / 2
        val border = tile / 2
        val x = border + (gx - g.lowestX) * tile / g.tileSize
        val y = border + (gy - g.lowestY) * tile / g.tileSize
        return Triple(x, y, index)
    }

    private fun withMoves(save: String, moves: List<String>): String =
        moves.fold(save) { text, move -> appendMove(text, move) }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private companion object {
        const val SEED = "12345"
    }
}
