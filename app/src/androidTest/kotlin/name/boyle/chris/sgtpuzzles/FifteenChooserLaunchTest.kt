package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.chooser.CHOOSER_GRID_TAG
import name.boyle.chris.sgtpuzzles.chooser.chooserGameTag
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The real chooser opens the real game.
 *
 * This is the only test that covers the chooser-to-game path: the
 * chooser starts `GamePlay` with the `sgtpuzzles:` URI, and `GamePlay`
 * generates the default game through the Kotlin backend.
 */
@RunWith(AndroidJUnit4::class)
class FifteenChooserLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<GameChooser>()

    @Test
    fun theChooserOpensFifteen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearFifteenState(context)

        composeRule.onNodeWithTag(CHOOSER_GRID_TAG)
            .performScrollToNode(hasTestTag(chooserGameTag(FIFTEEN)))
        composeRule.waitForIdle()
        // The chooser ignores a tap for 300 ms after onResume, so the
        // second half of a double tap cannot open a game. The rule has
        // just resumed the activity, so let that guard expire.
        SystemClock.sleep(350)
        composeRule.onNodeWithTag(chooserGameTag(FIFTEEN)).performClick()

        val activity = awaitGamePlay()
        assertTrue("fifteen is not using the Kotlin backend", activity.gameEngine is KotlinGameEngine)
        onView(withId(R.id.game_view)).check(matches(isDisplayed()))
        onView(withText(containsString("Moves: 0"))).check(matches(isDisplayed()))
    }

    /**
     * The chooser rule owns the chooser, not the game activity, so the
     * new activity is found through the lifecycle monitor. Waiting for
     * a resumed GamePlay with an engine also waits out the generation
     * worker.
     */
    private fun awaitGamePlay(deadlineMs: Long = 10_000): GamePlay {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var found: GamePlay? = null
            instrumentation.runOnMainSync {
                for (activity in ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                ) {
                    if (activity is GamePlay && activity.gameEngine is KotlinGameEngine) {
                        found = activity
                        break
                    }
                }
            }
            found?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("the chooser did not open a Kotlin GamePlay activity")
    }
}
