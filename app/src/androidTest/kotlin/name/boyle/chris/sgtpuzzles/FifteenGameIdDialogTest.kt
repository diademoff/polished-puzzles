package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The game-ID dialog of the Kotlin backend is
 * prefilled with the current ID, and a valid ID becomes the new game
 * without a generation.
 */
@RunWith(AndroidJUnit4::class)
class FifteenGameIdDialogTest {

    @Test
    fun theGameIdDialogPrefillsAndAcceptsAValidId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearFifteenState(context)
        val first = "3x3:1,2,3,4,5,6,7,8,0"
        val wanted = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,0"

        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, first),
        ).use { scenario ->
            val before = GamePlayTestSupport.awaitEngine(scenario)
            // The field shows the short parameters and the description,
            // which is the same text as the game ID.
            assertEquals(first, before.gameId)

            openCustomSettings()
            onView(withText(R.string.Game_ID_)).inRoot(isDialog()).perform(click())

            onView(withText("Fifteen game selection")).check(matches(isDisplayed()))
            onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog())
                .check(matches(withText(first)))
                .perform(replaceText(wanted))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())

            val changed = GamePlayTestSupport.awaitEngine(scenario) { it.gameId == wanted }
            assertEquals(wanted, changed.gameId)
            assertEquals("4x4", changed.currentParams)
            onView(withText(containsString("Moves: 0"))).check(matches(isDisplayed()))
        }
    }

    private fun openCustomSettings() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText("Fifteen configuration")).inRoot(isDialog())
            .check(matches(isDisplayed()))
    }
}
