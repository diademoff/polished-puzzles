package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The custom settings dialog of the Kotlin
 * backend is the engine schema, and it validates with the C message.
 *
 * The dialog is opened through the real type menu, so the test also
 * covers the shell path that reaches `KotlinGameEngine.configEvent`.
 */
@RunWith(AndroidJUnit4::class)
class FifteenSettingsDialogTest {

    @Test
    fun customSizeBuildsANewGameAndInvalidSizeKeepsTheOldOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearFifteenState(context)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, GamePlayTestSupport.SOLVED_4X4),
        ).use { scenario ->
            val before = GamePlayTestSupport.awaitEngine(scenario)
            assertEquals("4x4", before.currentParams)

            openCustomSettings()
            dialogField("Width").check(matches(withText("4")))
            dialogField("Height").check(matches(withText("4")))
            dialogField("Width").perform(replaceText("3"))
            dialogField("Height").perform(replaceText("3"))
            pressOk()

            val changed = GamePlayTestSupport.awaitEngine(scenario) {
                it.currentParams == "3x3" && it.gameId.startsWith("3x3:")
            }
            assertEquals("3x3", changed.currentParams)
            val tiles = changed.gameId.substringAfter(':').split(',')
            assertEquals("a 3x3 game ID describes nine tiles", 9, tiles.size)
            assertEquals(
                "the shell geometry still uses the 3x3 parameters",
                3 * changed.boardGeometry.tileSize + 2 * (changed.boardGeometry.tileSize / 2),
                changed.boardGeometry.width,
            )

            openCustomSettings()
            dialogField("Width").perform(replaceText("1"))
            pressOk()

            onView(withText("Width and height must both be at least two"))
                .check(matches(isDisplayed()))
            // The dialog reports the C message and starts nothing. A
            // generation that the dialog started by mistake would replace
            // the engine on the worker, so hold the old engine for a
            // bounded window instead of reading it once.
            GamePlayTestSupport.awaitEngineUnchanged(scenario, changed)
            val afterInvalid = GamePlayTestSupport.awaitEngine(scenario)
            assertEquals(changed.gameId, afterInvalid.gameId)
            assertEquals("3x3", afterInvalid.currentParams)
        }
    }

    private fun openCustomSettings() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText("Fifteen configuration")).inRoot(isDialog())
            .check(matches(isDisplayed()))
    }

    /** The text field whose floating label is [label], as `ConfigViewsBuilder` builds it. */
    private fun dialogField(label: String) =
        onView(
            allOf(
                isAssignableFrom(EditText::class.java),
                TestUtils.hasFieldLabel(label),
            ),
        ).inRoot(isDialog())

    private fun pressOk() {
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
    }
}
