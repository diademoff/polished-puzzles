package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic. The dialog name lookup.
 *
 * `configItemWithName` (`android.c:402-414`) scans the open item list
 * from the first item and returns the first name match. The Kotlin map
 * must do the same, because `configSet*` passes the display name and C
 * never assumes unique names.
 */
class ConfigNameIndexTest {

    @Test
    fun `a duplicate name resolves to the first item`() {
        val items = listOf(
            ConfigItem.Bool("Same", true),
            ConfigItem.Bool("Other", false),
            ConfigItem.Bool("Same", false),
        )
        assertEquals(mapOf("Same" to 0, "Other" to 1), configNameIndex(items))
    }
}
