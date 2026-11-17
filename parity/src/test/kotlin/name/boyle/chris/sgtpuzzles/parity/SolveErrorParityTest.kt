package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Singles
import name.boyle.chris.sgtpuzzles.engine.Slant
import name.boyle.chris.sgtpuzzles.engine.Solo
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic.
 *
 * The `midend_solve` error text of every ported game that has one is
 * compared with the oracle. The save that the oracle loads is the
 * Kotlin save of the same state, so both engines see one board. The two
 * games whose C error cannot reach `midend_solve` are pinned by the
 * comment on their case.
 */
class SolveErrorParityTest {

    private data class Case(val game: Game, val gameId: String)

    /**
     * Cases that a game id can build. `midend_solve` reaches each error
     * through the C code.
     */
    private val cases = listOf(
        Case(Flip, "3x3c:000000000000000000000,008"),
        Case(Lightup, "3x3:4h"),
        Case(Magnets, "4x4de:4112,2222,0332,2222,LRLRLRLRLRLRLRLR"),
        Case(Mosaic, "3x3:444444444"),
        Case(Palisade, "5x5n5:"),
        Case(Pattern, "4x4:2/1/2/1/1.1/2/1/1"),
        Case(Singles, "2x2de:1111"),
        Case(Slant, "2x2:300000000"),
        Case(Slant, "2x2:i"),
        Case(Solo, "3x3:iiiiiiiii"),
        Case(Unruly, "8x8dt:zzo"),
        Case(Unruly, "8x8dt:AAAzzl"),
    )

    @Test
    fun `every solve error matches the oracle`() = OracleClient().use { oracle ->
        for (case in cases) {
            val midend = Midend.fromGameId(case.game, case.gameId)
            val save = midend.serialise().serialise()
            val expected = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull(
                "${case.game.backendName} '${case.gameId}' must fail in C",
                expected,
            )
            assertEquals(
                "${case.game.backendName} '${case.gameId}'",
                expected,
                midend.solveAttempt(),
            )
        }
    }

    /**
     * A board with no work left has no solution: flood when the grid is
     * complete (`flood.c:720-723`), inerta when no gem remains
     * (`inertia.c:749-755`). The Kotlin midend makes the moves and the
     * oracle solves the Kotlin save, so both engines see one board.
     */
    @Test
    fun `an already solved board matches the oracle`() = OracleClient().use { oracle ->
        val flood = Midend.fromGameId(Flood, "4x4c3m0:1112101220200112,4")
        val tile = Flood.preferredTileSize
        for (colour in listOf(2, 0, 1, 2)) {
            val current = flood.state as Flood.State
            val index = current.grid.indexOfFirst {
                it == colour && it != current.grid[0]
            }
            assertTrue("the board must hold colour $colour", index >= 0)
            flood.processKey(
                tile / 2 + tile * (index % current.width),
                tile / 2 + tile * (index / current.width),
                Buttons.LEFT_BUTTON,
                tile,
            )
        }
        assertTrue((flood.state as Flood.State).complete)
        val floodSave = flood.serialise().serialise()
        val floodError = try {
            oracle.solve(floodSave)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("flood already solved", floodError, flood.solveAttempt())
        assertEquals("Puzzle is already solved", floodError)

        val inertia = Midend.fromGameId(Inertia, "2x3:wbgmSs")
        inertia.processKey(0, 0, Buttons.CURSOR_UP, Inertia.preferredTileSize)
        assertEquals(0, (inertia.state as Inertia.State).gems)
        val inertiaSave = inertia.serialise().serialise()
        val inertiaError = try {
            oracle.solve(inertiaSave)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("inertia already solved", inertiaError, inertia.solveAttempt())
        assertEquals("Game is already solved", inertiaError)
    }

    /**
     * Netslide has no local solver: the answer is the `AUXINFO` record.
     * A save without it is a normal save, and C reports
     * "Solution not known for this puzzle" (`netslide.c:890-897`).
     */
    @Test
    fun `a netslide save without aux matches the oracle`() = OracleClient().use { oracle ->
        val withAux = oracle.newGame("netslide", "3x3", SEED).state
        val withoutAux = withAux.lineSequence()
            .filterNot { it.startsWith("AUXINFO ") }
            .joinToString("\n")
        val expected = try {
            oracle.solve(withoutAux)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertNotNull("the oracle must reject a solve without aux", expected)
        val midend = Midend.fromSave(Netslide, SgtpSave.parse(withoutAux))
        assertEquals(expected, midend.solveAttempt())
    }

    /**
     * Filling sets `Sorry, I couldn't find a solution` and still returns
     * the partial answer (`filling.c:1379-1389`), so `midend_solve` never
     * shows the text because the move is non-NULL. The parity check is
     * that both engines answer with a move, and the engine test pins the
     * unused branch.
     */
    @Test
    fun `a stalled filling board solves in both engines`() = OracleClient().use { oracle ->
        val midend = Midend.fromGameId(Filling, "5x5:y")
        val save = midend.serialise().serialise()
        oracle.solve(save)
        /* C sets its error but returns a move, so midend_solve reports
         * success; the midend must do the same. */
        assertEquals(null, midend.solveAttempt())
        assertEquals(2, midend.statePos)
    }

    private companion object {
        const val SEED = "12345"
    }
}
