package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Level: unit. The S8 configuration item types.
 *
 * `kw` defaults to `name` because `android.c` passes the display name
 * as the keyword for a settings item. A preference item carries a real
 * keyword from `get_prefs`. The choice strings stay verbatim, including
 * the leading separator, so `ConfigViewsBuilder` can split them the way
 * C does.
 */
class ConfigItemTest {

    @Test
    fun `a text item defaults its keyword to the name`() {
        val item = ConfigItem.Text("Width", "4")
        assertEquals("Width", item.name)
        assertEquals("Width", item.kw)
        assertEquals("4", item.value)
    }

    @Test
    fun `a boolean item defaults its keyword to the name`() {
        val item = ConfigItem.Bool("Allow loops", true)
        assertEquals("Allow loops", item.name)
        assertEquals("Allow loops", item.kw)
        assertEquals(true, item.value)
    }

    @Test
    fun `a choice item keeps the C strings and can name its keyword`() {
        val item = ConfigItem.Choices(
            name = "Sense of arrow keys",
            choices = ":Move the tile:Move the gap",
            keywords = ":tile:gap",
            selected = 1,
            kw = "arrow-semantics",
        )
        assertEquals("arrow-semantics", item.kw)
        assertEquals(":Move the tile:Move the gap", item.choices)
        assertEquals(":tile:gap", item.keywords)
        assertEquals(1, item.selected)
    }

    @Test
    fun `a settings choice has no keyword list`() {
        val item = ConfigItem.Choices(
            name = "Difficulty",
            choices = ":Easy:Hard",
            keywords = null,
            selected = 0,
        )
        assertNull(item.keywords)
        assertEquals("Difficulty", item.kw)
    }

    @Test
    fun `a separated list uses the first character as the separator`() {
        assertEquals(listOf("Easy", "Hard"), separatedList(":Easy:Hard"))
        assertEquals(listOf("tile", "gap"), separatedList(":tile:gap"))
        assertEquals(listOf("", "small", "large"), separatedList("::small:large"))
        assertEquals(listOf(""), separatedList(":"))
    }

    @Test
    fun `a separated list keeps a backslash keyword`() {
        // slant.c:1714-1716: `:\:/` is the keyword list of `left-button`.
        assertEquals(listOf("\\", "/"), separatedList(":\\:/"))
    }
}
