package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {

    @Test
    fun `parses a flat response`() {
        val value = Json.parse(
            """{"ok":true,"state":"line\nbreak","w":240,"canundo":false}""",
        ) as JsonValue.Obj

        assertEquals(true, value.bool("ok"))
        assertEquals("line\nbreak", value.string("state"))
        assertEquals(240L, value.number("w"))
        assertEquals(false, value.bool("canundo"))
    }

    @Test
    fun `parses arrays of objects`() {
        val value = Json.parse(
            """{"presets":[{"title":"9x9, 10 mines","params":"9x9n10"}],"games":["fifteen"]}""",
        ) as JsonValue.Obj

        assertEquals(listOf("fifteen"), value.stringList("games"))
        val preset = (value.field("presets") as JsonValue.Arr).items.single() as JsonValue.Obj
        assertEquals("9x9, 10 mines", preset.string("title"))
        assertEquals("9x9n10", preset.string("params"))
    }

    @Test
    fun `round trips escapes`() {
        val original = "quote\" backslash\\ newline\n tab\t control\u0001"
        val parsed = Json.parse(Json.quote(original)) as JsonValue.Str
        assertEquals(original, parsed.value)
    }

    @Test
    fun `rejects trailing data`() {
        assertThrows(IllegalArgumentException::class.java) {
            Json.parse("""{"ok":true} extra""")
        }
    }
}
