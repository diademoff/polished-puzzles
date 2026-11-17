package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.Prefs
import name.boyle.chris.sgtpuzzles.engine.PrefsException
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.Undead
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Level: semantic.
 *
 * The Kotlin `get_prefs` schema of every ported game is compared with
 * the C `midend_get_config(CFG_PREFS)` item list. The text round trip
 * is compared with `midend_load_prefs` and `midend_save_prefs` on a
 * live midend built from a frozen save in `testdata/saves/initial`, so
 * the encoding, the keyword lookup, the one-key-shortcuts position, and
 * the unknown-keyword rule are compared byte for byte. Nothing is
 * generated.
 */
class PrefsSchemaParityTest {

    private data class Scheme(val game: Game, val backend: String)

    private val games = listOf(
        Scheme(Bridges, "bridges"),
        Scheme(Guess, "guess"),
        Scheme(Net, "net"),
        Scheme(Lightup, "lightup"),
        Scheme(Palisade, "palisade"),
        Scheme(Pearl, "pearl"),
        Scheme(Loopy, "loopy"),
        Scheme(Signpost, "signpost"),
        Scheme(Undead, "undead"),
        Scheme(Untangle, "untangle"),
    )

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the preference schema matches the oracle`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val expected = oracle.prefs(scheme.backend)
            assertEquals(
                "${scheme.backend} title",
                "${scheme.game.name} preferences",
                expected.title,
            )
            assertEquals(
                "${scheme.backend} first item",
                "one-key-shortcuts",
                expected.items.first().kw,
            )
            val gameItems = expected.items.drop(1)
            val actual = scheme.game.prefs
            assertEquals("${scheme.backend} item count", gameItems.size, actual.size)
            for (index in actual.indices) {
                assertPrefsItem(
                    "${scheme.backend} item $index",
                    actual[index],
                    gameItems[index],
                )
            }
            /* The schema must be encodable, as C assumes a keyword for
             * every choice and a unique keyword for every item. */
            Prefs.defaults(actual)
        }
    }

    @Test
    fun `a preference text round-trips byte for byte`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val state = save(scheme.backend)
            val expectedSchema = oracle.prefs(scheme.backend).items.first()
            val fullSchema = listOf(
                ConfigItem.Bool(
                    expectedSchema.name,
                    expectedSchema.boolean!!,
                    expectedSchema.kw!!,
                ),
            ) + scheme.game.prefs
            for (text in texts(scheme.backend, scheme.game.prefs)) {
                val expected = oracle.prefsRoundTrip(scheme.backend, state, text)
                val actual = Prefs.parse(fullSchema, text).encode()
                assertEquals("${scheme.backend} '$text'", expected, actual)
            }
        }
    }

    @Test
    fun `a bad preference text returns the C message`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val state = save(scheme.backend)
            for (text in badTexts(scheme.game.prefs)) {
                val expected = try {
                    oracle.prefsRoundTrip(scheme.backend, state, text)
                    null
                } catch (e: OracleException) {
                    e.message
                }
                assertNotNull("${scheme.backend} '$text' must be an error", expected)

                val actual = try {
                    Prefs.parse(scheme.game.prefs, text)
                    null
                } catch (e: PrefsException) {
                    e.message
                }
                assertEquals("${scheme.backend} '$text'", expected, actual)
            }
        }
    }

    /**
     * The first frozen save of the backend. The synthetic-UI op needs a
     * state only to build the UI, so any initial save of the game works.
     */
    private fun save(backend: String): String {
        val dir = File(testdata, "saves/initial/$backend")
        val file = dir.listFiles { candidate -> candidate.extension == "sgtp" }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?: error("no initial save for $backend")
        return file.readText()
    }

    /**
     * The text cases: the schema defaults, one changed item of every
     * preference type in the schema, an unknown keyword, and the
     * one-key-shortcuts line for the first game.
     */
    private fun texts(backend: String, schema: List<ConfigItem>): List<String> {
        val texts = ArrayList<String>()
        texts += ""
        val changed = StringBuilder()
        for (item in schema) {
            when (item) {
                is ConfigItem.Bool ->
                    changed.append(item.kw).append('=').append(!item.value).append('\n')

                is ConfigItem.Choices -> {
                    val names = separated(item.choices)
                    val keywords = separated(item.keywords!!)
                    val next = (item.selected + 1) % names.size
                    changed.append(item.kw).append('=').append(keywords[next]).append('\n')
                }

                is ConfigItem.Text -> error("${item.kw} is a string preference")
            }
        }
        if (changed.isNotEmpty()) texts += changed.toString()
        texts += "future-key=1\n"
        if (backend == games.first().backend) {
            texts += "one-key-shortcuts=false\n$changed"
        }
        return texts
    }

    private fun badTexts(schema: List<ConfigItem>): List<String> {
        val texts = ArrayList<String>()
        for (item in schema) {
            when (item) {
                is ConfigItem.Bool -> texts += "${item.kw}=yes\n"
                is ConfigItem.Choices -> texts += "${item.kw}=nope\n"
                is ConfigItem.Text -> Unit
            }
        }
        return texts
    }

    /** The C separated list, as the preference schema stores it. */
    private fun separated(text: String): List<String> {
        val separator = text[0]
        return text.substring(1).split(separator)
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
}
