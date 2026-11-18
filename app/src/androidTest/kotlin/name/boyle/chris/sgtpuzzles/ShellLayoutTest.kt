package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.width
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.render.BOARD_CANVAS_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Level: instrumentation. The shell in the three configurations that a
 * portrait, left-to-right, 100% text run cannot see.
 *
 * Each case is a P0 from `docs/DESIGN.md` section 14: the board was
 * missing in landscape, the button bar was placed outside the window in
 * right-to-left layouts, and the settings titles were ellipsized at 200%
 * text. The cases check the result, not the code that produces it: a
 * canvas that paints, keys inside the window, wrapped titles.
 */
@RunWith(AndroidJUnit4::class)
class ShellLayoutTest {

    /** Finds the board and the key bar composables in the real activity. */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<GamePlay>? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @After
    fun restore() {
        scenario?.let { game ->
            game.onActivity { it.requestedOrientation = originalOrientation }
            game.close()
        }
        scenario = null
        // A forced landscape outlives the activity while it is the top
        // app, and a transposed display breaks the next class. Wait for
        // the natural rotation, like GamePlayLandscapeInputTest does.
        if (originalOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ) {
            awaitDisplayRotation(Surface.ROTATION_0)
        }
    }

    @Test
    fun theBoardIsDrawnInLandscape() {
        val game = launchSolvedFifteen()
        val before = GamePlayTestSupport.awaitEngine(game)
        val gameId = before.gameId
        val save = GamePlayTestSupport.stableSave(before)

        // Portrait is the control: the same assertions must hold here.
        assertTheBoardPaints(game)

        game.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitOrientation(ORIENTATION_LANDSCAPE)
        awaitDisplayLeftOfRotation0()
        // The platform animates the rotation and the layout settles after
        // the configuration change.
        SystemClock.sleep(ROTATION_SETTLE_MS)

        assertTheBoardPaints(game)
        val rotated = GamePlayTestSupport.awaitEngine(game)
        assertEquals("the game id changed on rotation", gameId, rotated.gameId)
        assertEquals(
            "the saved game changed on rotation",
            save,
            GamePlayTestSupport.stableSave(rotated),
        )

        game.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitOrientation(ORIENTATION_PORTRAIT)
        awaitDisplayRotation(Surface.ROTATION_0)
        assertTheBoardPaints(game)
        assertEquals(
            "the saved game changed on the way back to portrait",
            save,
            GamePlayTestSupport.stableSave(GamePlayTestSupport.awaitEngine(game)),
        )
    }

    @Test
    fun theButtonBarStaysInTheWindowInRtl() {
        val game = launchSolvedFifteen()
        GamePlayTestSupport.awaitEngine(game)

        // The audit found that debug.force_rtl has no effect on this
        // build, so the direction is set on the window, which is what a
        // right-to-left locale configuration does.
        game.onActivity { it.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        composeRule.waitForIdle()
        game.onActivity {
            assertEquals(
                "the test did not switch the window to right-to-left",
                View.LAYOUT_DIRECTION_RTL,
                it.window.decorView.layoutDirection,
            )
        }

        // The board is drawn and does not mirror: the tap below must
        // move the tile on the left of the gap, not the one on the right.
        composeRule.onNodeWithTag(BOARD_CANVAS_TAG).assertIsDisplayed()

        val undo = composeRule.onNodeWithContentDescription("Undo")
        undo.assertIsDisplayed()
        val undoBounds = undo.getUnclippedBoundsInRoot()
        assertEquals("the Undo key is not 48dp wide", KEY_SIZE_DP, undoBounds.width.value, 0.5f)
        assertKeyInsideWindow(game, undoBounds.left.value, "Undo")
        // Undo is in the middle of the bar, so check the last key too.
        val redo = composeRule.onNodeWithContentDescription("Redo")
        redo.assertIsDisplayed()
        assertKeyInsideWindow(game, redo.getUnclippedBoundsInRoot().left.value, "Redo")

        tapTileRow3Column2(game)
        awaitStatus(game, "Moves: 1")
        assertEquals(
            "the tap moved the wrong tile, so the board is mirrored",
            listOf("M2,3"),
            SgtpSave.parse(GamePlayTestSupport.saveText(GamePlayTestSupport.awaitEngine(game)))
                .values("MOVE"),
        )

        undo.performClick()
        awaitStatus(game, "Moves: 0")
    }

    @Test
    fun theSettingsTitlesWrapAtTwoHundredPercentText() {
        val originalScale = fontScale()
        try {
            setFontScale(TWO_HUNDRED_PERCENT)
            awaitFontScale(TWO_HUNDRED_PERCENT)
            ActivityScenario.launch<PrefsActivity>(
                Intent(context, PrefsActivity::class.java),
            ).use { prefs ->
                scrollToPreference(prefs, CONTROLS_REMINDERS)
                onView(withText(R.string.controlsReminders)).check { view, notFound ->
                    assertNull("the settings row is not on screen", notFound)
                    val title = view as TextView
                    val layout = requireNotNull(title.layout) { "the title is not laid out" }
                    val facts = "lines=${layout.lineCount} maxLines=${title.maxLines} " +
                        "ellipsize=${title.ellipsize} text='${layout.text}'"
                    val ellipsized = (0 until layout.lineCount)
                        .sumOf { layout.getEllipsisCount(it) }
                    assertEquals(
                        "the title is ellipsized at 200% text: $facts",
                        0,
                        ellipsized,
                    )
                    assertTrue(
                        "the title is clipped: height ${title.height} < ${layout.height}",
                        title.height >= layout.height,
                    )
                    assertTrue(
                        "the title did not grow with the text: ${layout.lineCount} line(s)",
                        layout.lineCount >= 2,
                    )
                }
            }
        } finally {
            setFontScale(originalScale)
            awaitFontScale(originalScale)
        }
    }

    /** The canvas view is present, laid out, and paints more than one colour. */
    private fun assertTheBoardPaints(game: ActivityScenario<GamePlay>) {
        var canvas: View? = null
        game.onActivity { activity ->
            canvas = activity.findViewById(R.id.game_canvas)
            assertNotNull("the game layout has no game_canvas view", canvas)
        }
        val view = requireNotNull(canvas)
        assertTrue(
            "the canvas has no size: ${view.width}x${view.height}",
            view.width > 0 && view.height > 0,
        )
        assertTrue("the canvas is not displayed", view.isShown)
        composeRule.onNodeWithTag(BOARD_CANVAS_TAG).assertIsDisplayed()

        // After a rotation the new canvas composes before the engine
        // publishes the first frame and its colours, so one sample can
        // catch the empty background. Wait for the board to appear.
        val deadline = SystemClock.uptimeMillis() + 5_000
        var colours = emptySet<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            colours = canvasColours(view)
            if (colours.size >= 2) return
            SystemClock.sleep(100)
        }
        throw AssertionError("the board canvas painted ${colours.size} colour(s): $colours")
    }

    /** The colours of a 20 by 20 sample grid inside the canvas bounds. */
    private fun canvasColours(view: View): Set<Int> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue(
            "the canvas is outside the screenshot: $location in ${shot.width}x${shot.height}",
            location[0] >= 0 && location[1] >= 0 &&
                location[0] + view.width <= shot.width &&
                location[1] + view.height <= shot.height,
        )
        val colours = mutableSetOf<Int>()
        for (i in 1..20) {
            for (j in 1..20) {
                colours += shot.getPixel(
                    location[0] + view.width * i / 21,
                    location[1] + view.height * j / 21,
                )
            }
        }
        return colours
    }

    /**
     * The key is placed with absolute offsets inside the key bar, which
     * is an Android view inside the window. Its window position is the
     * bar position plus the position in the bar. A key 48dp wide that
     * starts at [keyLeftDp] must end before the bar and the window do.
     */
    private fun assertKeyInsideWindow(
        game: ActivityScenario<GamePlay>,
        keyLeftDp: Float,
        name: String,
    ) {
        var barX = 0
        var barWidth = 0
        var windowWidth = 0
        var density = 0f
        game.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.buttons)
            val barLocation = IntArray(2)
            bar.getLocationOnScreen(barLocation)
            barX = barLocation[0]
            barWidth = bar.width
            windowWidth = activity.window.decorView.width
            density = activity.resources.displayMetrics.density
        }
        val left = barX + (keyLeftDp * density).toInt()
        val right = left + (KEY_SIZE_DP * density).toInt()
        assertTrue(
            "$name is outside the window: $left..$right, bar $barX..${barX + barWidth}, " +
                "window width $windowWidth",
            left >= barX && right <= barX + barWidth && right <= windowWidth,
        )
    }

    private fun launchSolvedFifteen(): ActivityScenario<GamePlay> {
        GamePlayTestSupport.clearFifteenState(context)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, GamePlayTestSupport.SOLVED_4X4),
        )
        scenario = launched
        launched.onActivity { originalOrientation = it.requestedOrientation }
        return launched
    }

    /**
     * A tap in the cell at row 3, column 2 of the solved 4x4 board, the
     * tile on the left of the gap. A mirrored board would move the tile
     * at column 1 instead, which saves as `M1,3`.
     */
    private fun tapTileRow3Column2(game: ActivityScenario<GamePlay>) {
        val board = awaitBoard(game)
        onView(withId(R.id.game_view)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                CoordinatesProvider { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val density = view.width.toFloat() / (view as GameView).wDip
                    floatArrayOf(
                        location[0] + density * (board.originX + 2.5f * board.tileSize),
                        location[1] + density * (board.originY + 3.5f * board.tileSize),
                    )
                },
                Press.FINGER,
                0,
                0,
            ),
        )
    }

    /**
     * Waits for the resize that follows the launch. Until it lands, the
     * engine still reports the preferred geometry, which is not the
     * geometry the view uses.
     */
    private fun awaitBoard(game: ActivityScenario<GamePlay>): BoardGeometry {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var board: BoardGeometry? = null
            game.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.game_view)
                val engine = activity.gameEngine as? KotlinGameEngine
                if (view.width > 0 && view.height > 0 && engine != null) {
                    board = engine.boardGeometry
                }
            }
            board?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("the board did not reach a laid-out geometry")
    }

    private fun awaitStatus(game: ActivityScenario<GamePlay>, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var status = ""
        while (SystemClock.uptimeMillis() < deadline) {
            game.onActivity { status = GamePlayTestSupport.statusText(it) }
            if (status.contains(expected)) return
            SystemClock.sleep(50)
        }
        throw AssertionError("the status is '$status', not '$expected'")
    }

    private fun awaitOrientation(orientation: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var current = -1
            scenario?.onActivity { current = it.resources.configuration.orientation }
            if (current == orientation) return
            SystemClock.sleep(50)
        }
        throw AssertionError("the activity did not reach orientation $orientation")
    }

    private fun awaitDisplayLeftOfRotation0() {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (displayRotation() != Surface.ROTATION_0) return
            SystemClock.sleep(50)
        }
        throw AssertionError("the display did not leave rotation 0")
    }

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
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.defaultDisplay.rotation
    }

    private fun scrollToPreference(prefs: ActivityScenario<PrefsActivity>, key: String) {
        prefs.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(android.R.id.content) as PreferenceFragmentCompat
            fragment.scrollToPreference(key)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun fontScale(): Float =
        shell("settings get system font_scale").trim().toFloatOrNull() ?: 1f

    private fun setFontScale(scale: Float) {
        shell("settings put system font_scale $scale")
    }

    /** Waits until this process sees the new text scale in its resources. */
    private fun awaitFontScale(expected: Float) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (context.resources.configuration.fontScale == expected) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the text scale is ${context.resources.configuration.fontScale}, not $expected",
        )
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { file ->
            FileInputStream(file.fileDescriptor).readBytes().decodeToString()
        }
    }

    private companion object {
        const val TWO_HUNDRED_PERCENT = 2.0f
        const val CONTROLS_REMINDERS = "controlsReminders"
        /** `R.dimen.keySize`. */
        const val KEY_SIZE_DP = 48f

        /** The platform rotation animation, during which touches are dropped. */
        const val ROTATION_SETTLE_MS = 600L
    }
}
