package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.PortedGames
import name.boyle.chris.sgtpuzzles.engine.Prefs
import name.boyle.chris.sgtpuzzles.engine.PrefsException
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
 *
 * The matrix is [PortedGames.all], the same engine-side registry that
 * `KotlinGames.gameFor` uses. Both modules see one list, so a ported
 * game cannot be missing from this comparison. The oracle's `games` op
 * is the other side: every registry name must exist there, and the
 * empty/non-empty count is pinned below. The 21 backends with a NULL C
 * `get_prefs` must produce an empty Kotlin schema, and the 19 with a
 * non-NULL one must produce the C items.
 */
class PrefsSchemaParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the matrix covers every ported backend`() = OracleClient().use { oracle ->
        val oracleGames = oracle.games().toSet()
        assertEquals(40, PortedGames.all.size)
        for (game in PortedGames.all) {
            assertEquals(
                "${game.backendName} must be a C game",
                true,
                game.backendName in oracleGames,
            )
        }
    }

    @Test
    fun `the preference schema matches the oracle`() = OracleClient().use { oracle ->
        var withPrefs = 0
        var withoutPrefs = 0
        for (game in PortedGames.all) {
            val expected = oracle.prefs(game.backendName)
            assertEquals(
                "${game.backendName} title",
                "${game.name} preferences",
                expected.title,
            )
            assertEquals(
                "${game.backendName} first item",
                "one-key-shortcuts",
                expected.items.first().kw,
            )
            val gameItems = expected.items.drop(1)
            val actual = game.prefs
            assertEquals("${game.backendName} item count", gameItems.size, actual.size)
            assertEquals(
                "${game.backendName} declares C prefs exactly when the oracle has items",
                gameItems.isNotEmpty(),
                game.declaresPrefs,
            )
            if (gameItems.isEmpty()) {
                withoutPrefs++
            } else {
                withPrefs++
            }
            for (index in actual.indices) {
                assertPrefsItem(
                    "${game.backendName} item $index",
                    actual[index],
                    gameItems[index],
                )
            }
            /* The schema must be encodable, as C assumes a keyword for
             * every choice and a unique keyword for every item. */
            Prefs.defaults(actual)
        }
        /* The C table has 19 non-NULL get_prefs functions and 21 NULL
         * ones among the 40 ported backends. A new port must change this
         * count on purpose, not by accident. */
        assertEquals("games with a C prefs schema", 19, withPrefs)
        assertEquals("games with a NULL C get_prefs", 21, withoutPrefs)
        assertEquals("range has C prefs", true, PortedGames.byBackend("range")!!.declaresPrefs)
    }

    @Test
    fun `a preference text round-trips byte for byte`() = OracleClient().use { oracle ->
        var first = true
        for (game in PortedGames.all) {
            val backend = game.backendName
            val state = save(backend)
            val expectedSchema = oracle.prefs(backend).items.first()
            val fullSchema = listOf(
                ConfigItem.Bool(
                    expectedSchema.name,
                    expectedSchema.boolean!!,
                    expectedSchema.kw!!,
                ),
            ) + game.prefs
            for (text in texts(game.prefs, includeOneKeyShortcuts = first)) {
                val expected = oracle.prefsRoundTrip(backend, state, text)
                val actual = Prefs.parse(fullSchema, text).encode()
                assertEquals("$backend '$text'", expected, actual)
            }
            first = false
        }
    }

    @Test
    fun `a bad preference text returns the C message`() = OracleClient().use { oracle ->
        for (game in PortedGames.all) {
            val backend = game.backendName
            val state = save(backend)
            for (text in badTexts(game.prefs)) {
                val expected = try {
                    oracle.prefsRoundTrip(backend, state, text)
                    null
                } catch (e: OracleException) {
                    e.message
                }
                assertNotNull("$backend '$text' must be an error", expected)

                val actual = try {
                    Prefs.parse(game.prefs, text)
                    null
                } catch (e: PrefsException) {
                    e.message
                }
                assertEquals("$backend '$text'", expected, actual)
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
     * preference type in the schema, an unknown keyword, and for the
     * first game the one-key-shortcuts line.
     */
    private fun texts(schema: List<ConfigItem>, includeOneKeyShortcuts: Boolean): List<String> {
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
        if (includeOneKeyShortcuts) {
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
