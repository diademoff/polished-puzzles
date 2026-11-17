package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: unit. The preferences parser and encoder.
 *
 * The rules and the error strings are the ones of
 * `midend_deserialise_prefs` and `midend_serialise_prefs`
 * (`midend.c:3056-3295`): one `kw=value` line per item, `true`/`false`
 * for a boolean, a choice keyword for a choice, space stuffing for a
 * newline, comments and blank lines skipped, unknown keywords ignored,
 * and a missing final newline accepted.
 */
class PrefsTest {

    private val schema = listOf(
        ConfigItem.Bool("Keyboard shortcuts without Ctrl", true, "one-key-shortcuts"),
        ConfigItem.Choices(
            name = "Sense of arrow keys",
            choices = ":Move the tile:Move the gap",
            keywords = ":tile:gap",
            selected = 0,
            kw = "arrow-semantics",
        ),
        ConfigItem.Bool("Fade grounded components", false, "fade-grounded"),
    )

    @Test
    fun `defaults come from the schema`() {
        val prefs = Prefs.defaults(schema)
        assertTrue(prefs.bool("one-key-shortcuts"))
        assertEquals(0, prefs.choice("arrow-semantics"))
        assertFalse(prefs.bool("fade-grounded"))
        assertEquals("one-key-shortcuts=true\narrow-semantics=tile\nfade-grounded=false\n", prefs.encode())
    }

    @Test
    fun `parse overrides only the lines it contains`() {
        val prefs = Prefs.parse(
            schema,
            "one-key-shortcuts=false\narrow-semantics=gap\n",
        )
        assertFalse(prefs.bool("one-key-shortcuts"))
        assertEquals(1, prefs.choice("arrow-semantics"))
        assertFalse(prefs.bool("fade-grounded"))
    }

    @Test
    fun `parse starts from the current values`() {
        val current = Prefs.defaults(schema).withValues(
            listOf(ConfigItem.Bool("Fade grounded components", true, "fade-grounded")),
        )
        val parsed = Prefs.parse(current.items, "one-key-shortcuts=false\n")
        assertFalse(parsed.bool("one-key-shortcuts"))
        assertTrue(parsed.bool("fade-grounded"))
        assertEquals(0, parsed.choice("arrow-semantics"))
    }

    @Test
    fun `a text round-trips byte for byte`() {
        val text = "one-key-shortcuts=false\narrow-semantics=gap\nfade-grounded=true\n"
        val parsed = Prefs.parse(schema, text)
        assertEquals(text, parsed.encode())
        assertEquals(text, Prefs.encode(schema, parsed))
    }

    @Test
    fun `an unknown keyword is ignored`() {
        val parsed = Prefs.parse(
            schema,
            "future-key=1\none-key-shortcuts=false\nanother=whatever\n",
        )
        assertFalse(parsed.bool("one-key-shortcuts"))
        assertEquals(
            "one-key-shortcuts=false\narrow-semantics=tile\nfade-grounded=false\n",
            parsed.encode(),
        )
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val text = "# a comment\n\none-key-shortcuts=false\n"
        val parsed = Prefs.parse(schema, text)
        assertFalse(parsed.bool("one-key-shortcuts"))
        assertEquals(
            "one-key-shortcuts=false\narrow-semantics=tile\nfade-grounded=false\n",
            parsed.encode(),
        )
    }

    @Test
    fun `a missing final newline is accepted`() {
        val parsed = Prefs.parse(schema, "one-key-shortcuts=false")
        assertFalse(parsed.bool("one-key-shortcuts"))
    }

    @Test
    fun `a null or empty text keeps the schema values`() {
        assertEquals(Prefs.defaults(schema).encode(), Prefs.parse(schema, null).encode())
        assertEquals(Prefs.defaults(schema).encode(), Prefs.parse(schema, "").encode())
    }

    @Test
    fun `a bad boolean is the C error`() {
        val error = assertFailsWith<PrefsException> {
            Prefs.parse(schema, "fade-grounded=yes\n")
        }
        assertEquals("Value for boolean was not 'true' or 'false'", error.message)
    }

    @Test
    fun `a bad choice is the C error`() {
        val error = assertFailsWith<PrefsException> {
            Prefs.parse(schema, "arrow-semantics=sideways\n")
        }
        assertEquals("Invalid value for enumeration", error.message)
    }

    @Test
    fun `a keyword without an equals sign is the C error`() {
        val expected = assertFailsWith<PrefsException> {
            Prefs.parse(schema, "one-key-shortcuts")
        }
        assertEquals("Partial line at end of preferences file", expected.message)

        val noEquals = assertFailsWith<PrefsException> {
            Prefs.parse(schema, "one-key-shortcuts\n")
        }
        assertEquals("Expected '=' after keyword", noEquals.message)
    }

    @Test
    fun `a stuffed newline is unstuffed by the loader`() {
        val textSchema = listOf(ConfigItem.Text("Note", "first\nsecond", "note"))
        val defaults = Prefs.defaults(textSchema)
        assertEquals("note=first\n second\n", defaults.encode())

        val parsed = Prefs.parse(textSchema, "note=x\n y\n")
        assertEquals("x\ny", parsed.text("note"))
        assertEquals("note=x\n y\n", parsed.encode())
    }

    @Test
    fun `a stuffed newline in a choice value is still invalid`() {
        val error = assertFailsWith<PrefsException> {
            Prefs.parse(schema, "arrow-semantics=tr\n ue\n")
        }
        assertEquals("Invalid value for enumeration", error.message)
    }

    @Test
    fun `withValues applies items by keyword`() {
        val base = Prefs.defaults(schema)
        val edited = base.withValues(
            listOf(
                ConfigItem.Bool("Keyboard shortcuts without Ctrl", false, "one-key-shortcuts"),
                ConfigItem.Choices(
                    "Sense of arrow keys",
                    ":Move the tile:Move the gap",
                    ":tile:gap",
                    1,
                    "arrow-semantics",
                ),
            ),
        )
        assertFalse(edited.bool("one-key-shortcuts"))
        assertEquals(1, edited.choice("arrow-semantics"))
        assertEquals(
            "one-key-shortcuts=false\narrow-semantics=gap\nfade-grounded=false\n",
            edited.encode(),
        )
    }

    @Test
    fun `withValues ignores an unknown keyword and rejects a wrong type`() {
        val base = Prefs.defaults(schema)
        assertEquals(base.encode(), base.withValues(listOf(ConfigItem.Bool("Future", true, "future"))).encode())
        assertFailsWith<IllegalArgumentException> {
            base.withValues(listOf(ConfigItem.Text("Fade", "true", "fade-grounded")))
        }
    }

    @Test
    fun `a schema with a bad keyword is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Prefs.defaults(listOf(ConfigItem.Bool("Bad", true, "Not Valid")))
        }
        assertFailsWith<IllegalArgumentException> {
            Prefs.defaults(
                listOf(
                    ConfigItem.Bool("A", true, "same"),
                    ConfigItem.Bool("B", false, "same"),
                ),
            )
        }
    }

    @Test
    fun `a choice needs a keyword for every name`() {
        assertFailsWith<IllegalArgumentException> {
            Prefs.defaults(
                listOf(
                    ConfigItem.Choices("Two", ":A:B", ":a", 0, "two"),
                ),
            )
        }
    }
}
