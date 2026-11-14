package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
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
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * In landscape the board is narrower than the view, so the origin is
 * not zero. A tap must subtract that origin exactly once: the shell
 * already removes the zoom letterbox, and the Kotlin backend removes
 * the board origin. A double subtraction shifts the tap into the wrong
 * cell.
 */
class GamePlayLandscapeInputTest {

    @Test
    fun tapUsesTheCentredBoardInLandscape() {
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
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val board = awaitLandscapeBoard(scenario)

            onView(withId(R.id.game_view)).perform(
                GeneralClickAction(
                    Tap.SINGLE,
                    CoordinatesProvider { view ->
                        // A 3x3 board is four tiles high. The middle
                        // cell of the bottom row is one tile below the
                        // centre, which is a quarter of the board side.
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        val side = min(view.width, view.height)
                        floatArrayOf(
                            location[0] + view.width / 2f,
                            location[1] + view.height / 2f + side / 4f,
                        )
                    },
                    Press.FINGER,
                    0,
                    0,
                ),
            )

            onView(withText(containsString("Moves: 1"))).check(matches(isDisplayed()))
            assertTrue("the board is not letterboxed: $board", board.originX > board.tileSize)
        }
    }

    /**
     * The orientation changes before the game view is laid out again.
     * Wait for the landscape board, not only for the configuration flag.
     */
    private fun awaitLandscapeBoard(scenario: ActivityScenario<GamePlay>): BoardGeometry {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var board: BoardGeometry? = null
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.game_view)
                val engine = activity.gameEngine as? KotlinGameEngine
                if (activity.resources.configuration.orientation == ORIENTATION_LANDSCAPE &&
                    view.width > view.height && engine != null
                ) {
                    val candidate = engine.boardGeometry
                    // A portrait 3x3 board has an origin of 1 dp. A
                    // letterboxed landscape board has a wide margin.
                    if (candidate.originX > candidate.tileSize) board = candidate
                }
            }
            board?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("the activity did not lay out a landscape board")
    }
}
