package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.widget.EditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.TOWERS
import name.boyle.chris.sgtpuzzles.engine.Cancellation
import name.boyle.chris.sgtpuzzles.engine.Game
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. Cancel of a running Kotlin generation on the
 * Pixel 4a 5G.
 *
 * Towers is the cheapest enabled game with a documented slow board:
 * `9du` (9x9 Unreasonable) takes seconds on the phone, so the worker is
 * still running when Cancel is tapped. The case starts the real
 * generation through the real warning dialog and the real progress
 * dialog, taps Cancel, and then checks what the user can observe: the
 * progress dialog is gone, no error dialog appears, the current game
 * keeps its engine, its game ID, its parameters, and its save text, and
 * no second game arrives.
 *
 * It also checks the generator token, because the previous game looks
 * the same whether or not the worker was told to stop: the future was
 * always cancelled at the shell, and the difference is the work that
 * keeps running afterwards. The token is the seam that stops it, and
 * the engine test proves that a cancelled token returns without a
 * board. The case never waits for the heavy board to finish.
 */
@RunWith(AndroidJUnit4::class)
class GenerationCancelTest {

    @Test
    fun aCancelledGenerationStopsTheWorkerAndKeepsTheGame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearBackendState(context, TOWERS)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, TOWERS, "5de"),
        ).use { scenario ->
            val before = GamePlayTestSupport.awaitEngine(scenario) {
                it.currentParams == "5de"
            }

            openSlowBoard()
            onView(withText(Game.SLOW_GENERATION_WARNING))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressDialogButton(android.R.id.button1)
            onView(withText(R.string.starting))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressDialogButton(android.R.id.button2)

            onView(withText(R.string.starting)).check(doesNotExist())
            onView(withText(R.string.Error)).check(doesNotExist())
            GamePlayTestSupport.awaitEngineUnchanged(scenario, before)

            var cancel: Cancellation? = null
            scenario.onActivity { cancel = it.lastGenerationCancel }
            assertTrue(
                "the generator was not told to stop",
                checkNotNull(cancel) { "no generation token was started" }.isCancelled,
            )
            GamePlayTestSupport.awaitEngineUnchanged(scenario, before)
        }
    }

    /** Opens the custom settings dialog with Grid size 9 and Unreasonable. */
    private fun openSlowBoard() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText("Towers configuration")).inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(
            allOf(
                isAssignableFrom(EditText::class.java),
                TestUtils.hasFieldLabel("Grid size"),
            ),
        ).inRoot(isDialog()).perform(replaceText("9"))
        onView(isAssignableFrom(AppCompatSpinner::class.java))
            .inRoot(isDialog())
            .perform(click())
        onData(allOf(`is`(instanceOf(String::class.java)), `is`("Unreasonable")))
            .inRoot(isPlatformPopup())
            .perform(click())
        pressDialogButton(android.R.id.button1)
    }

    private fun pressDialogButton(id: Int) {
        onView(withId(id)).inRoot(isDialog()).perform(click())
    }
}
