package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. Two seam flags that a game can leave at the default.
 *
 * `guess` is the first game that has no text form and that asks for a
 * key which is useful only with the arrow keys.
 */
class SeamFlagTest {

    @Test
    fun `a game without a text form returns an empty string`() {
        assertTrue(Fifteen.canFormatAsText, "a ported game has a text form")
        val played = Midend.newGame(Fifteen, "4x4", SEED)
        assertEquals(Fifteen.textFormat(played.state), played.textFormat())

        val game = NoTextGame()
        val midend = Midend.newGame(game, "4x4", SEED)
        assertFalse(game.canFormatAsText)
        assertEquals(
            "",
            midend.textFormat(),
            "midend_text_format returns NULL for this game, so the seam returns an empty string",
        )
        assertEquals(0, game.textFormatCalls, "the midend must not call the game's textFormat")
    }

    @Test
    fun `a key label carries the arrow-only flag`() {
        val labels = ArrowKeyGame.requestKeys(ArrowKeyGame.defaultParams()).labels
        assertEquals(listOf("Alpha", "Beta"), labels.map { it.name })
        assertEquals(listOf(false, true), labels.map { it.needsArrows })

        val plain = KeyLabel('c'.code, "Gamma")
        assertFalse(plain.needsArrows, "a game that does not use the flag keeps the default")
    }

    private companion object {
        const val SEED = "12345"
    }

    /**
     * A test-only game with no text form, as `guess.c` has
     * `can_format_as_text_ever = false` and a NULL `text_format`.
     */
    private class NoTextGame : Game by Fifteen {
        var textFormatCalls = 0

        override val canFormatAsText: Boolean get() = false

        override fun textFormat(state: Game.State): String {
            textFormatCalls++
            error("textFormat must not be called when canFormatAsText is false")
        }
    }

    /** A test-only game that asks for one normal key and one arrow-only key. */
    private object ArrowKeyGame : Game by Fifteen {
        override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
            listOf(
                KeyLabel('a'.code, "Alpha"),
                KeyLabel('b'.code, "Beta", needsArrows = true),
            ),
            ArrowMode.ARROWS_LEFT_RIGHT,
        )
    }
}
