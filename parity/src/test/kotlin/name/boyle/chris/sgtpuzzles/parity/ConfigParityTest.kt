package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Level: semantic. Game: fifteen and slant.
 *
 * The Kotlin configuration schema and `custom_params` are compared with
 * the C `game_configure` and `midend_config_to_encoded_params`. The
 * oracle edits the C list by index, so a difference in field order,
 * names, types, defaults, choice strings, parsing, or validation
 * messages fails here.
 */
class ConfigParityTest {

    private data class Scheme(val game: Game, val backend: String)

    private val games = listOf(
        Scheme(Fifteen, "fifteen"),
        Scheme(Slant, "slant"),
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
    fun `default params match the oracle`() = OracleClient().use { oracle ->
        for (scheme in games) {
            assertEquals(
                scheme.backend,
                oracle.defaultParams(scheme.backend),
                scheme.game.encodeParams(scheme.game.defaultParams(), true),
            )
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
    fun `configOK matches the oracle for valid edits`() = OracleClient().use { oracle ->
        for (scheme in games) {
            val params = oracle.presets(scheme.backend).first().params
            for (edits in edits(scheme.game)) {
                assertConfigOK(scheme, oracle, params, edits)
            }
        }
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
                    assertEquals(
                        "$context item $index has no keyword list",
                        null,
                        oracle.choiceKws,
                    )
                    assertNull("$context item $index keywords", item.keywords)
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
        val values = edits.joinToString(";") { "${it.index}=${it.oracle}" }

        val expected = try {
            Encoded.Ok(oracle.configOK(scheme.backend, paramsText, values))
        } catch (e: OracleException) {
            Encoded.Error(e.message ?: "")
        }
        assertEquals(context, expected, kotlinConfigOK(scheme.game, paramsText, edits))
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

    private fun edits(game: Game): List<List<Edit>> = when (game.backendName) {
        "fifteen" -> listOf(
            /* atoi takes the numeric prefix, so "12abc" is 12. */
            listOf(Edit(0, "12abc", "12abc"), Edit(1, "5", "5")),
            /* atoi skips whitespace and accepts a sign. */
            listOf(Edit(0, "+7", "+7"), Edit(1, " 6", " 6")),
            listOf(Edit(0, "1", "1")),
            listOf(Edit(0, "abc", "abc")),
            listOf(
                Edit(0, "100000", "100000"),
                Edit(1, "100000", "100000"),
            ),
        )

        "slant" -> listOf(
            listOf(Edit(0, "12", "12"), Edit(1, "9", "9"), Edit(2, "1", 1)),
            listOf(Edit(0, "5", "5"), Edit(1, "5", "5"), Edit(2, "0", 0)),
            listOf(Edit(0, "1", "1")),
            listOf(
                Edit(0, "100000", "100000"),
                Edit(1, "100000", "100000"),
            ),
        )

        else -> error("no edits for ${game.backendName}")
    }
}
