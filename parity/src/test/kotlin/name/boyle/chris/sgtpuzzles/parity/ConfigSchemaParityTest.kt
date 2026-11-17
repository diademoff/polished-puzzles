package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Level: semantic.
 *
 * The Kotlin `game_configure` and `custom_params` of every ported game
 * are compared with the C `game_configure` and
 * `midend_config_to_encoded_params`. The oracle edits the C item list by
 * index, so a difference in field order, names, types, defaults, choice
 * strings, parsing, or validation messages fails here. The oracle also
 * supplies the default and the presets, so no expected value comes from
 * the Kotlin game.
 */
class ConfigSchemaParityTest {

    private data class Scheme(val game: Game, val backend: String)

    private val games = listOf(
        Scheme(SameGame, "samegame"),
        Scheme(Flood, "flood"),
        Scheme(Sixteen, "sixteen"),
        Scheme(Flip, "flip"),
        Scheme(Twiddle, "twiddle"),
        Scheme(Pegs, "pegs"),
        Scheme(Guess, "guess"),
        Scheme(BlackBox, "blackbox"),
    )

    @Test
    fun `the default configuration matches the oracle`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val params = scheme.game.encodeParams(scheme.game.defaultParams(), true)
            assertConfig(scheme, params, oracle.config(scheme.backend))
        }
    }

    @Test
    fun `every preset configuration matches the oracle`() = OracleClient().use { oracle ->
        for (scheme in games) {
            for (preset in oracle.presets(scheme.backend)) {
                assertConfig(
                    scheme,
                    preset.params,
                    oracle.config(scheme.backend, preset.params),
                )
            }
        }
    }

    @Test
    fun `configOK matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (scheme in games) {
            for (preset in oracle.presets(scheme.backend)) {
                assertConfigOK(scheme, oracle, preset.params, emptyList())
            }
        }
    }

    @Test
    fun `configOK matches the oracle for a generic valid edit`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val params = oracle.presets(scheme.backend).first().params
            val schema = oracle.config(scheme.backend, params)
            for (edit in genericValidEdits(schema)) {
                assertConfigOK(scheme, oracle, params, listOf(edit))
            }
        }
    }

    @Test
    fun `configOK matches the oracle for an invalid edit`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val params = oracle.presets(scheme.backend).first().params
            val schema = oracle.config(scheme.backend, params)
            val index = schema.items.indexOfFirst { it.type == "string" }
            check(index >= 0) { "${scheme.backend} has no string setting" }
            val edit = Edit(index, "0", "0")
            val expected = oracleConfigOK(oracle, scheme.backend, params, listOf(edit))
            check(expected is Encoded.Error) {
                "${scheme.backend} accepted an invalid edit"
            }
            assertConfigOK(scheme, oracle, params, listOf(edit))
        }
    }

    @Test
    fun `configOK matches the oracle for the quirky readers`() =
        OracleClient().use { oracle ->
            for (scheme in games) {
                val params = oracle.presets(scheme.backend).first().params
                for (edits in quirkyEdits[scheme.backend].orEmpty()) {
                    assertConfigOK(scheme, oracle, params, edits)
                }
            }
        }

    /**
     * One edit of each kind: the first string plus one, the first
     * boolean negated, the first choice advanced by one. The index and
     * the value come from the oracle response, never from the port.
     */
    private fun genericValidEdits(schema: OracleClient.Config): List<Edit> {
        val edits = ArrayList<Edit>()
        val textIndex = schema.items.indexOfFirst { it.type == "string" }
        if (textIndex >= 0) {
            val value = schema.items[textIndex].value!!.toInt()
            edits += Edit(textIndex, (value + 1).toString(), (value + 1).toString())
        }
        val boolIndex = schema.items.indexOfFirst { it.type == "boolean" }
        if (boolIndex >= 0) {
            val value = schema.items[boolIndex].boolean!!
            edits += Edit(boolIndex, (!value).toString(), !value)
        }
        val choiceIndex = schema.items.indexOfFirst { it.type == "choices" }
        if (choiceIndex >= 0) {
            val count = separatedCount(schema.items[choiceIndex].choices!!)
            val selected = (schema.items[choiceIndex].selected!! + 1) % count
            edits += Edit(choiceIndex, selected.toString(), selected)
        }
        return edits
    }

    private fun separatedCount(list: String): Int {
        val separator = list[0]
        return list.count { it == separator }
    }

    private fun assertConfig(
        scheme: Scheme,
        paramsText: String,
        expected: OracleClient.Config,
    ) {
        val context = "${scheme.backend} '$paramsText'"
        val params = scheme.game.decodeParams(paramsText)
        val actual = scheme.game.configure(params)
            ?: error("$context has no configuration schema")

        assertEquals("$context title", "${scheme.game.name} configuration", expected.title)
        assertEquals("$context item count", expected.items.size, actual.size)
        for (index in actual.indices) {
            val item = actual[index]
            val oracle = expected.items[index]
            assertEquals("$context item $index name", item.name, oracle.name)
            when (item) {
                is ConfigItem.Text -> {
                    assertEquals("$context item $index type", "string", oracle.type)
                    assertEquals("$context item $index value", item.value, oracle.value)
                }

                is ConfigItem.Bool -> {
                    assertEquals("$context item $index type", "boolean", oracle.type)
                    assertEquals("$context item $index value", item.value, oracle.boolean)
                }

                is ConfigItem.Choices -> {
                    assertEquals("$context item $index type", "choices", oracle.type)
                    assertEquals("$context item $index choices", item.choices, oracle.choices)
                    assertEquals("$context item $index selected", item.selected, oracle.selected)
                    assertNull("$context item $index keywords", item.keywords)
                    assertNull("$context item $index has no keyword list", oracle.choiceKws)
                }
            }
        }
    }

    private fun assertConfigOK(
        scheme: Scheme,
        oracle: OracleClient,
        paramsText: String,
        edits: List<Edit>,
    ) {
        val context = "${scheme.backend} '$paramsText' edits $edits"
        val expected = oracleConfigOK(oracle, scheme.backend, paramsText, edits)
        assertEquals(context, expected, kotlinConfigOK(scheme.game, paramsText, edits))
    }

    private fun oracleConfigOK(
        oracle: OracleClient,
        backend: String,
        paramsText: String,
        edits: List<Edit>,
    ): Encoded {
        val values = edits.joinToString(";") { "${it.index}=${it.oracle}" }
        return try {
            Encoded.Ok(oracle.configOK(backend, paramsText, values))
        } catch (e: OracleException) {
            Encoded.Error(e.message ?: "")
        }
    }

    private fun kotlinConfigOK(
        game: Game,
        paramsText: String,
        edits: List<Edit>,
    ): Encoded {
        val schema = game.configure(game.decodeParams(paramsText))
            ?: error("${game.backendName} has no configuration schema")
        val edited = schema.mapIndexed { index, item ->
            edits.firstOrNull { it.index == index }?.applyTo(item) ?: item
        }
        val params = game.customParams(edited)
        val message = game.validateParams(params, true)
        return if (message != null) {
            Encoded.Error(message)
        } else {
            Encoded.Ok(game.encodeParams(params, true))
        }
    }

    private data class Edit(val index: Int, val oracle: String, val kotlinValue: Any) {
        fun applyTo(item: ConfigItem): ConfigItem = when (item) {
            is ConfigItem.Text -> item.copy(value = kotlinValue as String)
            is ConfigItem.Bool -> item.copy(value = kotlinValue as Boolean)
            is ConfigItem.Choices -> item.copy(selected = kotlinValue as Int)
        }
    }

    private sealed interface Encoded {
        data class Ok(val params: String) : Encoded
        data class Error(val message: String) : Encoded
    }

    private val quirkyEdits: Map<String, List<List<Edit>>> = mapOf(
        "blackbox" to listOf(
            /* A range, a single number, and the sscanf white-space rules. */
            listOf(Edit(2, "4-7", "4-7")),
            listOf(Edit(2, "4", "4")),
            listOf(Edit(2, " 4- 7", " 4- 7")),
            listOf(Edit(2, "4 -7", "4 -7")),
            listOf(Edit(2, "4-", "4-")),
        ),
        "samegame" to listOf(
            /* scoresub = selected + 1, the default is 2. */
            listOf(Edit(3, "0", 0)),
            listOf(Edit(3, "1", 1)),
        ),
        "flip" to listOf(
            listOf(Edit(2, "1", 1)),
        ),
        "pegs" to listOf(
            listOf(Edit(2, "1", 1)),
            listOf(Edit(2, "2", 2)),
        ),
    )
}
