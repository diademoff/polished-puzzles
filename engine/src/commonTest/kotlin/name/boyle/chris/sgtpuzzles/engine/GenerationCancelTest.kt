package name.boyle.chris.sgtpuzzles.engine

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Level: semantic. The cooperative cancel seam.
 *
 * A generator reads its [Cancellation] at the top of an attempt only,
 * so a cancelled run stops without a board and an accepted run keeps
 * the C description. `tracks` is the heaviest measured generator
 * (D-029: 0.75 s on the host for `22x22dh` seed 12345), and `dominosa`
 * is the second family (D-030). The `22x22dh` text below is the C
 * oracle output for the same parameters and seed, so passing a token
 * cannot have changed the board.
 */
class GenerationCancelTest {

    /**
     * Cancels itself after [budgetMs] of work, then lets the next check
     * throw. It proves that the generator checks the token between
     * attempts, not only when it is entered.
     */
    private class CancelAfter(private val budgetMs: Long) : Cancellation() {
        private val start = TimeSource.Monotonic.markNow()

        var checks = 0
            private set

        override fun check() {
            checks++
            if (start.elapsedNow().inWholeMilliseconds >= budgetMs) cancel()
            super.check()
        }
    }

    @Test
    fun `a cancelled tracks generation stops without a board`() {
        val cancel = CancelAfter(50)
        var board: Midend? = null
        val elapsed = measureTimeMillis {
            assertFailsWith<GenerationCancelled> {
                board = Midend.newGame(Tracks, "22x22dh", "12345", cancel = cancel)
            }
        }
        assertNull(board, "a cancelled generation produced a board")
        assertTrue(
            cancel.checks > 1,
            "the generator checked the token ${cancel.checks} time(s), so it never retried",
        )
        assertTrue(elapsed < 5_000, "a cancelled 22x22 tracks generation took $elapsed ms")
    }

    @Test
    fun `a pre-cancelled token stops a dominosa generation`() {
        val cancel = Cancellation()
        cancel.cancel()
        assertFailsWith<GenerationCancelled> {
            Midend.newGame(Dominosa, "9db", "12345", cancel = cancel)
        }
    }

    @Test
    fun `a token changes no description and no save`() {
        val plain = Midend.newGame(Tracks, "22x22dh", "12345")
        val withToken = Midend.newGame(Tracks, "22x22dh", "12345", cancel = Cancellation())

        assertEquals(ORACLE_DESC, plain.desc)
        assertEquals(plain.desc, withToken.desc)
        assertEquals(ORACLE_SAVE, plain.serialise().serialise())
        assertEquals(ORACLE_SAVE, withToken.serialise().serialise())
    }

    private companion object {
        /** The C oracle `DESC` for `tracks 22x22dh` seed `12345`. */
        const val ORACLE_DESC =
            "zd3b6y5g9zc9e5zbCza6zo6qAzq9zzzj6aCzp3CxAztCl,11,13,13,11,6,4,6,3,6,S5,5,5,2," +
                "4,5,5,6,5,2,4,2,2,21,14,10,11,10,6,S6,5,6,3,1,2,2,2,2,3,4,2,5,3,5,2"

        /** The C oracle save for the same game. */
        const val ORACLE_SAVE = "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :12:Train Tracks\n" +
            "PARAMS  :7:22x22dt\n" +
            "CPARAMS :7:22x22dh\n" +
            "SEED    :5:12345\n" +
            "DESC    :144:$ORACLE_DESC\n" +
            "NSTATES :1:1\n" +
            "STATEPOS:1:1\n"
    }
}
