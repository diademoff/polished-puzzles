package name.boyle.chris.sgtpuzzles.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/** Level: unit. Regression for the Android SharedPreferences padding. */
class SavedGameTextTest {

    @Test
    fun `trailing spaces after a newline are removed`() {
        assertEquals("GAME:1:X\n", SavedGameText.trimAndroidPadding("GAME:1:X\n    "))
    }

    @Test
    fun `spaces inside the text are kept`() {
        assertEquals("A B\nC", SavedGameText.trimAndroidPadding("A B\nC"))
    }

    @Test
    fun `a value of spaces becomes empty`() {
        assertEquals("", SavedGameText.trimAndroidPadding("    "))
    }
}
