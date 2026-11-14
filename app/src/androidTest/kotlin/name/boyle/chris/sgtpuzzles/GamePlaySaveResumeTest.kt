package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_BACKEND
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_COMPLETED_PREFIX
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_GAME_PREFIX
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.STATE_PREFS_NAME
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full Android save path: the activity writes the game to
 * SharedPreferences on pause, and the next launch reads it back.
 *
 * The saved text ends with a newline. Android reads such a value back
 * with trailing spaces, so the test also writes that padding into the
 * preference and checks that the Kotlin parser is not confused by it.
 */
class GamePlaySaveResumeTest {

    @Test
    fun kotlinGameResumesWithItsMoves() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
        state.edit()
            .remove(SAVED_BACKEND)
            .remove(SAVED_GAME_PREFIX + FIFTEEN)
            .remove(SAVED_COMPLETED_PREFIX + FIFTEEN)
            .commit()

        val uri = Uri.parse("sgtpuzzles:fifteen:3x3:1,2,3,4,5,6,7,8,0")
        val intent = Intent(Intent.ACTION_VIEW, uri, context, GamePlay::class.java)
        ActivityScenario.launch<GamePlay>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "fifteen is not using the Kotlin backend",
                    activity.gameEngine is KotlinGameEngine,
                )
            }
            onView(withId(R.id.game_view)).perform(ViewActions.pressKey(KEYCODE_DPAD_RIGHT))
            onView(withText(containsString("Moves: 1"))).check(matches(isDisplayed()))
        }

        val saved = state.getString(SAVED_GAME_PREFIX + FIFTEEN, null)
        checkNotNull(saved) { "the activity did not save the game" }
        assertTrue("the saved game does not end with a newline", saved.endsWith("\n"))
        state.edit()
            .putString(SAVED_GAME_PREFIX + FIFTEEN, saved + "    ")
            .commit()

        ActivityScenario.launch<GamePlay>(GamePlay::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the resumed game is not using the Kotlin backend",
                    activity.gameEngine is KotlinGameEngine,
                )
            }
            onView(withText(containsString("Moves: 1"))).check(matches(isDisplayed()))
            onView(withId(R.id.game_view)).perform(ViewActions.pressKey(KEYCODE_DPAD_RIGHT))
            onView(withText(containsString("Moves: 2"))).check(matches(isDisplayed()))
        }
    }
}
