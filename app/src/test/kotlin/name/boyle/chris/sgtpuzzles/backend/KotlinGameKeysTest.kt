package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.buttons.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.ArrowMode as EngineArrowMode
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyLabel
import name.boyle.chris.sgtpuzzles.engine.KeyRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: unit. `Java_..._requestKeys` in `android.c:784-794` puts a key
 * whose `needs_arrows` is false into `keys` and a key whose
 * `needs_arrows` is true into `keysIfArrows`. The Kotlin backend uses
 * the same split.
 */
class KotlinGameKeysTest {

    @Test
    fun `a key that needs arrows is not in the always-shown keys`() {
        val result = keysResultFor(TwoKeyGame, TwoKeyGame.defaultParams())

        assertEquals("a", result.keys)
        assertEquals("b", result.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT_CLICK, result.arrowMode)

        val labels = TwoKeyGame.requestKeys(TwoKeyGame.defaultParams()).labels
        assertEquals(listOf("Alpha", "Beta"), labels.map { it.name })
        assertEquals(listOf(false, true), labels.map { it.needsArrows })
    }

    @Test
    fun `a game that does not use the flag keeps its keys and arrow mode`() {
        val result = keysResultFor(Fifteen, Fifteen.defaultParams())

        assertEquals("", result.keys)
        assertEquals("", result.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_ONLY, result.arrowMode)
    }

    /**
     * A test-only game that asks for one normal key and one arrow-only
     * key. Every rule is [Fifteen]'s.
     */
    private object TwoKeyGame : Game by Fifteen {
        override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
            listOf(
                KeyLabel('a'.code, "Alpha"),
                KeyLabel('b'.code, "Beta", needsArrows = true),
            ),
            EngineArrowMode.ARROWS_LEFT_RIGHT,
        )
    }
}
