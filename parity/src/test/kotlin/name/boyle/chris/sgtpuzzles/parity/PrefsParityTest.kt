package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Level: semantic. Game: fifteen and slant.
 *
 * The preference schema and the text encoding are compared with the C
 * `midend_get_config(CFG_PREFS)`, `midend_load_prefs`, and
 * `midend_save_prefs`. The oracle loads the text into a live midend,
 * because C applies preferences to `me->ui` and reads them back from
 * it.
 */
class PrefsParityTest {

    private data class Scheme(val game: Game, val backend: String, val params: String)

    private val games = listOf(
        Scheme(Fifteen, "fifteen", "4x4"),
        Scheme(Slant, "slant", "8x8de"),
    )

    @Test
    fun `the preference schema matches the oracle`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val midend = Midend.newGame(scheme.game, scheme.params, SEED)
            val expected = oracle.prefs(scheme.backend)
            assertEquals(
                "${scheme.backend} title",
                "${scheme.game.name} preferences",
                expected.title,
            )

            val actual = midend.prefItems
            assertEquals("${scheme.backend} item count", expected.items.size, actual.size)
            for (index in actual.indices) {
                assertPrefsItem("${scheme.backend} item $index", actual[index], expected.items[index])
            }
        }
    }

    @Test
    fun `a preference text round-trips byte for byte`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val state = oracle.newGame(scheme.backend, scheme.params, SEED).state
            for (text in texts(scheme.backend)) {
                val expected = oracle.prefsRoundTrip(scheme.backend, state, text)
                val actual = Midend.newGame(
                    scheme.game,
                    scheme.params,
                    SEED,
                    prefs = text,
                ).serialisePrefs()
                assertEquals("${scheme.backend} '$text'", expected, actual)
            }
        }
    }

    @Test
    fun `a bad preference text returns the C message`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val state = oracle.newGame(scheme.backend, scheme.params, SEED).state
            for (text in badTexts(scheme.backend)) {
                val expected = try {
                    oracle.prefsRoundTrip(scheme.backend, state, text)
                    null
                } catch (e: OracleException) {
                    e.message
                }
                assertNotNull("${scheme.backend} '$text' must be an error", expected)

                val actual = Midend.newGame(scheme.game, scheme.params, SEED)
                    .applyPrefs(text)
                assertEquals("${scheme.backend} '$text'", expected, actual)
            }
        }
    }

    @Test
    fun `one-key shortcuts is first and is a midend setting`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val expected = oracle.prefs(scheme.backend)
            assertEquals("one-key-shortcuts", expected.items.first().kw)
            assertEquals(
                "Keyboard shortcuts without Ctrl",
                expected.items.first().name,
            )
            assertEquals("boolean", expected.items.first().type)
            assertEquals(true, expected.items.first().boolean)
        }
    }

    private fun assertPrefsItem(
        context: String,
        actual: ConfigItem,
        expected: OracleClient.ConfigItem,
    ) {
        assertEquals("$context name", actual.name, expected.name)
        assertEquals("$context keyword", actual.kw, expected.kw)
        when (actual) {
            is ConfigItem.Text -> {
                assertEquals("$context type", "string", expected.type)
                assertEquals("$context value", actual.value, expected.value)
            }

            is ConfigItem.Bool -> {
                assertEquals("$context type", "boolean", expected.type)
                assertEquals("$context value", actual.value, expected.boolean)
            }

            is ConfigItem.Choices -> {
                assertEquals("$context type", "choices", expected.type)
                assertEquals("$context choices", actual.choices, expected.choices)
                assertEquals("$context keywords", actual.keywords, expected.choiceKws)
                assertEquals("$context selected", actual.selected, expected.selected)
            }
        }
    }

    private fun texts(backend: String): List<String> = when (backend) {
        "fifteen" -> listOf(
            "one-key-shortcuts=false\narrow-semantics=gap\n",
            "arrow-semantics=tile\nfuture-key=1\n",
            "# a comment\n\narrow-semantics=gap",
        )

        "slant" -> listOf(
            "one-key-shortcuts=false\nleft-button=/\nfade-grounded=true\n",
            "fade-grounded=true\n",
            /* The backslash keyword of slant.c:1716. */
            "left-button=\\\n",
        )

        else -> error("no prefs texts for $backend")
    }

    private fun badTexts(backend: String): List<String> = when (backend) {
        "fifteen" -> listOf(
            "arrow-semantics=sideways\n",
            "arrow-semantics=tr\n ue\n",
            "arrow-semantics",
        )

        "slant" -> listOf(
            "fade-grounded=yes\n",
            "left-button=sideways\n",
            "fade-grounded\n",
        )

        else -> error("no bad prefs texts for $backend")
    }

    private companion object {
        const val SEED = "12345"
    }
}
