package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.buttons.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.ArrowMode as EngineArrowMode
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyLabel
import name.boyle.chris.sgtpuzzles.engine.KeyRequest
import name.boyle.chris.sgtpuzzles.engine.Palisade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val result = keysResultFor(TwoKeyGame, TwoKeyGame.defaultParams(), fullCursor = false)

        assertEquals("a", result.keys)
        assertEquals("b", result.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT_CLICK, result.arrowMode)

        val labels = TwoKeyGame.requestKeys(TwoKeyGame.defaultParams()).labels
        assertEquals(listOf("Alpha", "Beta"), labels.map { it.name })
        assertEquals(listOf(false, true), labels.map { it.needsArrows })
    }

    @Test
    fun `a game that does not use the flag keeps its keys and arrow mode`() {
        val result = keysResultFor(Fifteen, Fifteen.defaultParams(), fullCursor = false)

        assertEquals("", result.keys)
        assertEquals("", result.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_ONLY, result.arrowMode)
    }

    @Test
    fun `the full-cursor flag reaches the game and changes its keys`() {
        val plain = keysResultFor(CursorFlagGame, CursorFlagGame.defaultParams(), fullCursor = false)
        val full = keysResultFor(CursorFlagGame, CursorFlagGame.defaultParams(), fullCursor = true)

        assertEquals("", plain.keys)
        assertEquals("", plain.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT_CLICK, plain.arrowMode)

        assertEquals("", full.keys)
        assertEquals("hjkl", full.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_ONLY, full.arrowMode)
    }

    @Test
    fun `a game without the hack ignores the full-cursor flag`() {
        val plain = keysResultFor(Fifteen, Fifteen.defaultParams(), fullCursor = false)
        val full = keysResultFor(Fifteen, Fifteen.defaultParams(), fullCursor = true)

        assertEquals(plain, full)
        assertEquals(
            Fifteen.requestKeys(Fifteen.defaultParams()),
            Fifteen.requestKeys(Fifteen.defaultParams(), fullCursor = true),
        )
    }

    @Test
    fun `palisade takes the shell full-cursor flag`() {
        val params = Palisade.decodeParams("5x5n5")
        val half = keysResultFor(Palisade, params, fullCursor = false)
        val full = keysResultFor(Palisade, params, fullCursor = true)

        assertEquals("", half.keys)
        assertEquals("", half.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT_CLICK, half.arrowMode)

        assertEquals("", full.keys)
        assertEquals("HJKL", full.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_ONLY, full.arrowMode)

        // The shell flag is the game's second argument, not a parameter.
        val request = Palisade.requestKeys(params, fullCursor = true)
        assertEquals(full.keysIfArrows, request.labels.joinToString("") { it.button.toChar().toString() })
        assertEquals(EngineArrowMode.ARROWS_ONLY, request.arrowMode)
        assertNotEquals(request, Palisade.requestKeys(params))
    }

    @Test
    fun `the diagonal arrow mode reaches the app unchanged`() {
        val result = keysResultFor(DiagonalGame, DiagonalGame.defaultParams(), fullCursor = false)

        assertEquals("a", result.keys)
        assertEquals("b", result.keysIfArrows)
        assertEquals(ArrowMode.ARROWS_DIAGONALS, result.arrowMode)
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

        // `Game by Fifteen` delegates the two-argument overload to
        // Fifteen, so a delegating game overrides both to keep one answer.
        override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
            requestKeys(params)
    }

    /**
     * A test-only game that asks for the diagonal arrow mode, the
     * upstream mode of `inertia`. Every rule is [Fifteen]'s.
     */
    private object DiagonalGame : Game by Fifteen {
        override fun requestKeys(params: Game.Params): KeyRequest = KeyRequest(
            listOf(
                KeyLabel('a'.code, "Alpha"),
                KeyLabel('b'.code, "Beta", needsArrows = true),
            ),
            EngineArrowMode.ARROWS_DIAGONALS,
        )

        override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
            requestKeys(params)
    }

    /**
     * A test-only game that behaves like `palisade` in C: the Android
     * full-cursor flag, not a parameter, selects four arrow labels and
     * `ARROWS_ONLY`; without it the game asks for no labels and
     * `ARROWS_LEFT_RIGHT`. Every other rule is [Fifteen]'s.
     */
    private object CursorFlagGame : Game by Fifteen {
        private val cursorKeys = KeyRequest(
            listOf(
                KeyLabel('h'.code, "Left", needsArrows = true),
                KeyLabel('j'.code, "Bottom", needsArrows = true),
                KeyLabel('k'.code, "Top", needsArrows = true),
                KeyLabel('l'.code, "Right", needsArrows = true),
            ),
            EngineArrowMode.ARROWS_ONLY,
        )

        override fun requestKeys(params: Game.Params): KeyRequest =
            KeyRequest(emptyList(), EngineArrowMode.ARROWS_LEFT_RIGHT)

        override fun requestKeys(params: Game.Params, fullCursor: Boolean): KeyRequest =
            if (fullCursor) cursorKeys else requestKeys(params)
    }
}
