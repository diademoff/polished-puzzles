package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. The `midend_solve` error channel.
 *
 * C returns one of three messages (`midend.c:2096-2123`): no solver, no
 * move (the game's message when it has one, otherwise
 * `Solve operation failed`), and a move that `execute_move` rejects.
 * `solve()` keeps the old boolean result.
 */
class SolveOutcomeTest {

    @Test
    fun `a solve appends the solve entry`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED)
        assertNull(midend.solveAttempt())
        assertEquals(2, midend.statePos)
        assertEquals(1, midend.status())
        assertTrue(midend.serialise().serialise().contains("SOLVE"))
    }

    @Test
    fun `a game without a solver is the first C message`() {
        val game = object : Game by Fifteen {
            override val canSolve: Boolean get() = false
        }
        val midend = Midend.newGame(game, "4x4", SEED)
        assertEquals(
            "This game does not support the Solve operation",
            midend.solveAttempt(),
        )
        assertFalse(midend.solve())
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `a null move without a game message is the second C message`() {
        val game = object : Game by Fifteen {
            override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
                SolveOutcome(null, null)

            // `Game by Fifteen` delegates the three-argument overload
            // to Fifteen, so a delegating game overrides both.
            override fun solveGame(
                state: Game.State,
                auxInfo: String?,
                initialState: Game.State,
            ): SolveOutcome = solveGame(state, auxInfo)
        }
        val midend = Midend.newGame(game, "4x4", SEED)
        assertEquals("Solve operation failed", midend.solveAttempt())
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `a null move keeps the game message`() {
        val game = object : Game by Fifteen {
            override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
                SolveOutcome(null, "The puzzle is already solved")

            override fun solveGame(
                state: Game.State,
                auxInfo: String?,
                initialState: Game.State,
            ): SolveOutcome = solveGame(state, auxInfo)
        }
        val midend = Midend.newGame(game, "4x4", SEED)
        assertEquals("The puzzle is already solved", midend.solveAttempt())
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `a rejected move is the third C message`() {
        val game = object : Game by Fifteen {
            override fun executeMove(state: Game.State, move: String): Game.State? = null
        }
        val midend = Midend.newGame(game, "4x4", SEED)
        assertEquals("Solve unavailable (already solved?)", midend.solveAttempt())
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `solve keeps the boolean result`() {
        val game = object : Game by Fifteen {
            override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
                SolveOutcome(null, null)

            override fun solveGame(
                state: Game.State,
                auxInfo: String?,
                initialState: Game.State,
            ): SolveOutcome = solveGame(state, auxInfo)
        }
        assertFalse(Midend.newGame(game, "4x4", SEED).solve())
        assertTrue(Midend.newGame(Fifteen, "4x4", SEED).solve())
    }

    @Test
    fun `a game with a C error string keeps it`() {
        val params = Untangle.decodeParams("10")
        val state = Untangle.newState(
            params,
            Untangle.newDesc(params, RandomState.fromSeed(SEED)).text,
        )
        assertEquals(
            SolveOutcome(null, "Solution not known for this puzzle"),
            Untangle.solveGame(state, null),
        )
    }

    @Test
    fun `mines reports an unstarted game`() {
        val params = Mines.decodeParams("9x9n10")
        val state = Mines.newState(
            params,
            Mines.newDesc(params, RandomState.fromSeed(SEED)).text,
        )
        assertEquals(
            SolveOutcome(null, "Game has not been started yet"),
            Mines.solveGame(state, null),
        )
    }

    @Test
    fun `pearl reports a bad aux character`() {
        val params = Pearl.decodeParams("6x6de")
        val state = Pearl.newState(
            params,
            Pearl.newDesc(params, RandomState.fromSeed(SEED)).text,
        )
        assertEquals(
            SolveOutcome(null, "invalid char in aux"),
            Pearl.solveGame(state, "X"),
        )
    }

    private companion object {
        const val SEED = "12345"
    }
}
