package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. Input at a tile size that is not the game's
 * preferred tile, at two different sizes.
 *
 * The engine picks the tile from the viewport in device-independent
 * pixels. `limitDpi=off` renders one dp as one pixel, so the viewport,
 * and therefore the tile, is larger than at the default `auto`. The
 * test reads the live geometry, so the tap and the swipe use the same
 * values as the drawing layer, not a guess.
 *
 * A swipe on `fifteen` is one move: the shell sends the press at the
 * start cell, then drags and a release that the game ignores.
 */
@RunWith(AndroidJUnit4::class)
class FifteenTileSizeInputTest {

    @Test
    fun tapAndSwipeUseTheSizedBoard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearFifteenState(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val originalLimitDpi = prefs.getString(LIMIT_DPI_KEY, "auto")
        try {
            ActivityScenario.launch<GamePlay>(
                GamePlayTestSupport.intent(context, GamePlayTestSupport.SOLVED_4X4),
            ).use { scenario ->
                val first = awaitSizedBoard(scenario, previousTile = null)
                assertTrue(
                    "the live tile ${first.tileSize} is the preferred tile, so this is not " +
                        "the non-default case",
                    first.tileSize != Fifteen.preferredTileSize,
                )

                // The gap is at (3,3). The tile at (2,3) is one move.
                onView(withId(R.id.game_view)).perform(
                    GeneralClickAction(
                        Tap.SINGLE,
                        cellCentre(first, x = 2, y = 3),
                        Press.FINGER,
                        0,
                        0,
                    ),
                )
                assertStatus(scenario, "Moves: 1")

                prefs.edit().putString(LIMIT_DPI_KEY, "off").commit()
                val second = awaitSizedBoard(scenario, previousTile = first.tileSize)
                assertTrue(
                    "the live tile ${second.tileSize} is the preferred tile, so this is not " +
                        "the non-default case",
                    second.tileSize != Fifteen.preferredTileSize,
                )
                assertTrue(
                    "limitDpi=off did not change the tile size (${first.tileSize})",
                    second.tileSize != first.tileSize,
                )

                // The tap put the gap at (2,3). The tile at (1,3) is one
                // move into the gap, and the drags add nothing.
                onView(withId(R.id.game_view)).perform(
                    GeneralSwipeAction(
                        Swipe.SLOW,
                        cellCentre(second, x = 1, y = 3),
                        cellCentre(second, x = 2, y = 3),
                        Press.FINGER,
                    ),
                )
                assertStatus(scenario, "Moves: 2")
            }
        } finally {
            if (originalLimitDpi == null) {
                prefs.edit().remove(LIMIT_DPI_KEY).commit()
            } else {
                prefs.edit().putString(LIMIT_DPI_KEY, originalLimitDpi).commit()
            }
        }
    }

    /**
     * The cell centre in view coordinates: the board is centred in the
     * view, and the game draws tile `(x, y)` at `border + x * tile`.
     *
     * The engine works in device-independent pixels, so the game
     * coordinate is multiplied by the density the view used for this
     * layout. `wDip` and the view width give that density exactly,
     * including `limitDpi=off`, where it is 1.
     */
    private fun cellCentre(board: BoardGeometry, x: Int, y: Int): CoordinatesProvider =
        CoordinatesProvider { view: View ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val gameView = view as GameView
            val density = view.width.toFloat() / gameView.wDip
            val border = board.tileSize / 2
            floatArrayOf(
                location[0] +
                    density * (board.originX + border + x * board.tileSize + board.tileSize / 2f),
                location[1] +
                    density * (board.originY + border + y * board.tileSize + board.tileSize / 2f),
            )
        }

    private fun assertStatus(scenario: ActivityScenario<GamePlay>, expected: String) {
        scenario.onActivity {
            assertEquals(expected, GamePlayTestSupport.statusText(it))
        }
    }

    /**
     * Waits for the resize that follows the launch or the preference
     * change, then returns the geometry. A [previousTile] of null asks
     * for any size that is not the preferred one.
     */
    private fun awaitSizedBoard(
        scenario: ActivityScenario<GamePlay>,
        previousTile: Int?,
    ): BoardGeometry {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var board: BoardGeometry? = null
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.game_view)
                val engine = activity.gameEngine as? KotlinGameEngine
                if (view.width > 0 && view.height > 0 && engine != null) {
                    val candidate = engine.boardGeometry
                    val changed = previousTile == null || candidate.tileSize != previousTile
                    if (changed && candidate.tileSize != Fifteen.preferredTileSize) {
                        board = candidate
                    }
                }
            }
            board?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("the board did not take a non-preferred tile size")
    }
}
