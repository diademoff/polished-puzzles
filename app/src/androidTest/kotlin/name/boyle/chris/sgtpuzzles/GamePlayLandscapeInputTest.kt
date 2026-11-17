package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
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
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.min

/**
 * In landscape the board is narrower than the view, so the origin is
 * not zero. A tap must subtract that origin exactly once: the shell
 * already removes the zoom letterbox, and the Kotlin backend removes
 * the board origin. A double subtraction shifts the tap into the wrong
 * cell.
 *
 * The activity is closed in [restore], which first returns the display
 * to the orientation it had before this class. Without that, the forced
 * landscape leaks into the next test class and a whole-screen capture
 * is rotated.
 */
class GamePlayLandscapeInputTest {

    private lateinit var scenario: ActivityScenario<GamePlay>
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Before
    fun launch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearFifteenState(context)

        val uri = Uri.parse("sgtpuzzles:fifteen:3x3:1,2,3,4,5,6,7,8,0")
        val intent = Intent(Intent.ACTION_VIEW, uri, context, GamePlay::class.java)
        scenario = ActivityScenario.launch(intent)
        scenario.onActivity {
            // Record the request before the test forces landscape, so
            // @After can put the activity back where it found it.
            originalOrientation = it.requestedOrientation
        }
    }

    @After
    fun restore() {
        if (!::scenario.isInitialized) return
        scenario.onActivity {
            it.requestedOrientation = originalOrientation
        }
        // The activity must finish before the system releases the forced
        // landscape: while it is the top app, the display keeps its last
        // fixed rotation.
        scenario.close()
        // A portrait or unspecified request must settle on the natural
        // rotation before the next class captures the screen. A
        // landscape preference is restored as it was, so it is not
        // waited for.
        if (originalOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ) {
            awaitDisplayRotation(Surface.ROTATION_0)
        }
    }

    @Test
    fun tapUsesTheCentredBoardInLandscape() {
        scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val board = awaitLandscapeBoard(scenario)
        // The platform rotates the display with an animation and drops
        // touches until it ends. The user cannot tap the board during it
        // either, so the tap waits for the display to be landscape and
        // for the animation window to pass.
        awaitLandscapeDisplay()
        SystemClock.sleep(ROTATION_SETTLE_MS)

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

    /**
     * Waits until the display itself is no longer at rotation 0. The
     * activity's configuration can change before the display does.
     */
    private fun awaitLandscapeDisplay() {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (displayRotation() != Surface.ROTATION_0) return
            SystemClock.sleep(50)
        }
        throw AssertionError("the display did not leave rotation 0")
    }

    /**
     * The display rotates asynchronously, after the activity has seen
     * the new configuration. A test must not return while it is still
     * landscape, or the next whole-screen capture is transposed.
     */
    private fun awaitDisplayRotation(rotation: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (displayRotation() == rotation) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the display did not return to rotation $rotation; it is ${displayRotation()}",
        )
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.defaultDisplay.rotation
    }

    private companion object {
        /** The platform rotation animation, during which touches are dropped. */
        const val ROTATION_SETTLE_MS = 600L
    }
}
