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
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.GALAXIES
import name.boyle.chris.sgtpuzzles.backend.MOSAIC
import name.boyle.chris.sgtpuzzles.backend.TOWERS
import name.boyle.chris.sgtpuzzles.engine.Game
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The slow-generation warning on the Pixel 4a 5G.
 *
 * Towers is the cheapest enabled game with a documented slow board:
 * `docs/PARITY.md` records `9du` (9x9 Unreasonable) at seconds, while
 * every preset is at most 6x6. The test opens the real type menu and the
 * real settings dialog, so it covers the shell path that asks the engine
 * hook. It never waits for the heavy board: Cancel is checked against
 * the live engine, and Continue is cancelled as soon as its progress
 * dialog appears.
 *
 * Mosaic is the slowest game of the fifth enablement wave: its
 * aggressive `100x100` board takes 30 to 45 seconds in C. That case only
 * checks the warning and Cancel, so the heavy generation never starts;
 * the Continue branch is covered by the Towers case.
 *
 * Galaxies is the slow case of the sixth enablement wave: every board
 * above 225 cells warns, so a custom `16x16` is the smallest board that
 * does. The case checks the warning and Cancel only, so no generation
 * starts.
 */
@RunWith(AndroidJUnit4::class)
class SlowGenerationDialogTest {

    @Test
    fun aSlowBoardWarnsAndCancelKeepsTheCurrentGame() {
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
            pressDialogButton(android.R.id.button2)
            GamePlayTestSupport.awaitEngineUnchanged(scenario, before)

            openSlowBoard()
            onView(withText(Game.SLOW_GENERATION_WARNING))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressDialogButton(android.R.id.button1)
            onView(withText(R.string.starting))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressDialogButton(android.R.id.button2)
            GamePlayTestSupport.awaitEngineUnchanged(scenario, before)
        }
    }

    @Test
    fun aSlowMosaicBoardWarnsAndCancelKeepsTheCurrentGame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearBackendState(context, MOSAIC)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, MOSAIC, "10x10"),
        ).use { scenario ->
            val before = GamePlayTestSupport.awaitEngine(scenario) {
                it.currentParams == "10x10"
            }

            openSlowMosaicBoard()
            onView(withText(Game.SLOW_GENERATION_WARNING))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressDialogButton(android.R.id.button2)
            GamePlayTestSupport.awaitEngineUnchanged(scenario, before)
        }
    }

    @Test
    fun aSlowGalaxiesBoardWarnsAndCancelKeepsTheCurrentGame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearBackendState(context, GALAXIES)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, GALAXIES, "7x7dn"),
        ).use { scenario ->
            val before = GamePlayTestSupport.awaitEngine(scenario) {
                it.currentParams == "7x7dn"
            }

            openSlowGalaxiesBoard()
            onView(withText(Game.SLOW_GENERATION_WARNING))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressDialogButton(android.R.id.button2)
            GamePlayTestSupport.awaitEngineUnchanged(scenario, before)
        }
    }

    /** Opens the custom settings dialog with a 16x16 galaxies board. */
    private fun openSlowGalaxiesBoard() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText("Galaxies configuration")).inRoot(isDialog())
            .check(matches(isDisplayed()))
        for (label in listOf("Width", "Height")) {
            onView(
                allOf(
                    isAssignableFrom(EditText::class.java),
                    TestUtils.hasFieldLabel(label),
                ),
            ).inRoot(isDialog()).perform(replaceText("16"))
        }
        pressDialogButton(android.R.id.button1)
    }

    /** Opens the custom settings dialog with a 100x100 aggressive board. */
    private fun openSlowMosaicBoard() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText("Mosaic configuration")).inRoot(isDialog())
            .check(matches(isDisplayed()))
        for (label in listOf("Height", "Width")) {
            onView(
                allOf(
                    isAssignableFrom(EditText::class.java),
                    TestUtils.hasFieldLabel(label),
                ),
            ).inRoot(isDialog()).perform(replaceText("100"))
        }
        pressDialogButton(android.R.id.button1)
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
