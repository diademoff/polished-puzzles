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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The seed dialog of the Kotlin backend shows
 * the current seed and a fixed seed gives the same game twice.
 *
 * A game launched from parameters alone is generated with a fresh UUID
 * seed, so the first dialog must show `4x4#<uuid>`. Typing the same
 * fixed seed in a second launch must produce the same game ID, which is
 * the description of the board: the seed is the only input that can
 * change it.
 */
@RunWith(AndroidJUnit4::class)
class FifteenSeedDialogTest {

    @Test
    fun aFixedSeedGivesTheSameGameTwice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seed = "1234"

        val first = launchWithSeed(context, seed, expectUuid = true)
        val second = launchWithSeed(context, seed, expectUuid = false)

        assertEquals("the same seed must describe the same board", first, second)
    }

    private fun launchWithSeed(context: Context, seed: String, expectUuid: Boolean): String {
        GamePlayTestSupport.clearFifteenState(context)
        var gameId = ""
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, "4x4"),
        ).use { scenario ->
            // The parameter-only launch generates a game with a UUID
            // seed. Wait for it, so the dialog reads the live engine.
            GamePlayTestSupport.awaitEngine(scenario) { it.gameId.startsWith("4x4:") }

            openCustomSettings()
            onView(withText(R.string.Seed_)).inRoot(isDialog()).perform(click())
            onView(withText("Fifteen random selection")).check(matches(isDisplayed()))

            val field = onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog())
            if (expectUuid) {
                field.check { view, noViewFound ->
                    if (noViewFound != null) throw noViewFound
                    val text = (view as EditText).text.toString()
                    assertTrue(
                        "the seed field is '$text', not '4x4#<uuid>'",
                        Regex(
                            "4x4#[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
                                "[0-9a-f]{4}-[0-9a-f]{12}",
                        ).matches(text),
                    )
                }
            }
            field.perform(replaceText(seed))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())

            // The generation runs on a worker. The save is written after
            // the new engine is in place, so its SEED record is the
            // point at which the dialog has taken effect.
            GamePlayTestSupport.awaitSavedSeed(context, seed)
            gameId = GamePlayTestSupport.awaitEngine(scenario).gameId
            onView(withText(containsString("Moves: 0"))).check(matches(isDisplayed()))
        }
        return gameId
    }

    private fun openCustomSettings() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText("Fifteen configuration")).inRoot(isDialog())
            .check(matches(isDisplayed()))
    }
}
