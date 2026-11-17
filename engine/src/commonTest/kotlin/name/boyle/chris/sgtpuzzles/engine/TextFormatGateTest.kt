package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: a test-only game whose text gate reads the
 * parameters.
 *
 * `midend_text_format` asks `can_format_as_text_ever` and then
 * `can_format_as_text_now(params)` before it calls `text_format`
 * (`midend.c:2087-2094`). `loopy` is the first upstream game that
 * rejects one parameter set and accepts another (`loopy.c:1144-1149`),
 * so the seam needs the params-aware gate and the midend must honour it.
 */
class TextFormatGateTest {

    /**
     * The gate accepts `4x4` and rejects `5x5`, so a midend that ignores
     * the gate calls the formatter for both and records two calls.
     */
    @Test
    fun `the midend skips the formatter when the params gate rejects`() {
        val game = GatedGame()

        val accepted = Midend.newGame(game, "4x4", SEED)
        assertTrue(game.canFormatAsTextNow(accepted.currentParams), "4x4 is accepted")
        val text = accepted.textFormat()
        assertNotEquals("", text, "the accepted params keep the board text")
        assertEquals(1, game.formatCalls, "the accepted params reach the formatter")

        val rejected = Midend.newGame(game, "5x5", SEED)
        assertFalse(game.canFormatAsTextNow(rejected.currentParams), "5x5 is rejected")
        assertEquals("", rejected.textFormat(), "the rejected params have no text form")
        assertEquals(
            1,
            game.formatCalls,
            "the rejected params must not reach the formatter",
        )
    }

    /**
     * The default gate answers the static flag, so a game that already
     * declares no text form keeps answering false for every parameter
     * set and a game that has one keeps answering true.
     */
    @Test
    fun `the default gate follows the static flag`() {
        assertTrue(Fifteen.canFormatAsTextNow(Fifteen.defaultParams()))
        assertFalse(Netslide.canFormatAsTextNow(Netslide.defaultParams()))
    }

    private companion object {
        const val SEED = "12345"
        const val REJECTED = "5x5"
    }

    /**
     * Delegates every rule to `fifteen` and rejects one parameter set,
     * the way `loopy` rejects a grid whose type is not square.
     */
    private class GatedGame : Game by Fifteen {
        var formatCalls = 0

        override fun canFormatAsTextNow(params: Game.Params): Boolean =
            Fifteen.encodeParams(params, true) != REJECTED

        override fun textFormat(state: Game.State): String {
            formatCalls++
            return Fifteen.textFormat(state)
        }
    }
}
