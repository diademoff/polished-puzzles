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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
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
import name.boyle.chris.sgtpuzzles.chooser.CHOOSER_GRID_TAG
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.render.BOARD_CANVAS_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Level: instrumentation. The shell in the configurations that a
 * portrait, left-to-right, 100% text run on a phone window cannot see.
 *
 * Each case is a P0 or a large-screen rule from `docs/DESIGN.md` section
 * 14: the board was missing in landscape, the button bar was placed
 * outside the window in right-to-left layouts, the settings titles were
 * ellipsized at 200% text, and a tablet window stretched the shell across
 * its width. The cases check the result, not the code that produces it: a
 * canvas that paints, keys inside the window, wrapped titles, a list that
 * keeps the reading measure.
 */
@RunWith(AndroidJUnit4::class)
class ShellLayoutTest {

    /** Finds the board and the key bar composables in the real activity. */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<GamePlay>? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var originalWindowSize: String? = null

    /**
     * The window size override of the device, if any, so every case leaves
     * the display as it found it. The tests simulate a large window on the
     * phone with `wm size`; nothing else about the device is changed.
     */
    @Before
    fun rememberWindowSize() {
        originalWindowSize = WINDOW_SIZE_OVERRIDE.find(shell("wm size"))?.groupValues?.get(1)
    }

    @After
    fun restore() {
        restoreWindowSize()
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

    /**
     * A large window keeps the shell at its reading measure. The chooser
     * list and the settings list are capped at
     * `shell_content_max_width` and centred, so a tablet shows a
     * comfortable column instead of one row across 900dp.
     */
    @Test
    fun theShellKeepsTheReadingMeasureOnAnExpandedWindow() {
        withWindowSize(EXPANDED_WIDTH, EXPANDED_HEIGHT) {
            ActivityScenario.launch<GameChooser>(
                Intent(context, GameChooser::class.java),
            ).use {
                awaitWindowWidth(EXPANDED_MIN_WIDTH_DP)
                val cap = dimensionDp(R.dimen.shell_content_max_width)
                val grid = awaitChooserGridWidth(cap)
                val root = composeRule.onRoot().getUnclippedBoundsInRoot()
                assertEquals(
                    "the capped chooser list is not centred in the ${windowWidthDp()}dp window: $grid in $root",
                    (root.width - grid.width).value / 2,
                    grid.left.value,
                    1f,
                )
            }

            ActivityScenario.launch<PrefsActivity>(
                Intent(context, PrefsActivity::class.java),
            ).use { prefs ->
                awaitWindowWidth(EXPANDED_MIN_WIDTH_DP)
                val capPx = context.resources.getDimensionPixelSize(R.dimen.shell_content_max_width)
                val rows = awaitSettingsRows(prefs)
                assertEquals(
                    "the settings rows are ${rows.width}px wide, not capped at $capPx",
                    capPx,
                    rows.width,
                )
                assertEquals(
                    "the capped settings rows are not centred",
                    (rows.parentLeft + (rows.parentWidth - rows.width) / 2).toFloat(),
                    rows.left.toFloat(),
                    1f,
                )
            }
        }
    }

    /**
     * A resize keeps the game. The window grows while a game is open, so
     * the activity handles `screenSize` in place instead of being
     * recreated: the board is drawn at the new size, the game ID and the
     * save text are the same, the key bar is still usable, and a real tap
     * still moves the tile it hits on the new geometry.
     */
    @Test
    fun theGameSurvivesAWindowResize() {
        val game = launchSolvedFifteen()
        val before = GamePlayTestSupport.awaitEngine(game)
        val gameId = before.gameId

        // The phone window is the control: the same assertions hold here.
        assertTheBoardPaints(game)
        assertTheKeyBarIsUsable(game, "phone window")

        withWindowSize(EXPANDED_WIDTH, EXPANDED_HEIGHT) {
            awaitWindowWidth(EXPANDED_MIN_WIDTH_DP)
            SystemClock.sleep(ROTATION_SETTLE_MS)
            assertTheBoardFrameIsDrawn(game, awaitBoard(game))
            val resized = GamePlayTestSupport.awaitEngine(game)
            assertEquals("the game id changed on resize", gameId, resized.gameId)
            assertEquals(
                "the saved game changed on resize",
                GamePlayTestSupport.stableSave(before),
                GamePlayTestSupport.stableSave(resized),
            )
            assertTheKeyBarIsUsable(game, "expanded window")

            tapTileRow3Column2(game)
            awaitStatus(game, "Moves: 1")
            val afterMove = GamePlayTestSupport.awaitEngine(game)
            assertEquals(
                "the tap after the resize moved the wrong tile",
                listOf("M2,3"),
                SgtpSave.parse(GamePlayTestSupport.saveText(afterMove)).values("MOVE"),
            )

            // A wide window puts the key bar in a side column, which is the
            // arrangement a tablet in landscape uses.
            shell("wm size ${EXPANDED_HEIGHT}x${EXPANDED_WIDTH}")
            awaitWindowWidth(EXPANDED_MIN_WIDTH_DP)
            awaitOrientation(ORIENTATION_LANDSCAPE)
            SystemClock.sleep(ROTATION_SETTLE_MS)
            assertTheBoardFrameIsDrawn(game, awaitBoard(game))
            assertTheKeyBarIsUsable(game, "expanded window, landscape")
            val landscape = GamePlayTestSupport.awaitEngine(game)
            assertEquals("the game id changed in the landscape window", gameId, landscape.gameId)
            assertEquals(
                "the saved game changed in the landscape window",
                GamePlayTestSupport.stableSave(afterMove),
                GamePlayTestSupport.stableSave(landscape),
            )
        }

        // Back at the phone window, the same board is still drawn.
        SystemClock.sleep(ROTATION_SETTLE_MS)
        assertTheBoardPaints(game)
        assertTheKeyBarIsUsable(game, "phone window after the resize")
        assertEquals(
            "the game id changed on the way back to the phone window",
            gameId,
            GamePlayTestSupport.awaitEngine(game).gameId,
        )
    }

    /**
     * The canvas view is present, laid out, and its renderer published a
     * frame. This is the board check for a simulated large window, where
     * the screen coordinates do not fit the physical screenshot.
     */
    private fun assertTheBoardFrameIsDrawn(game: ActivityScenario<GamePlay>, board: BoardGeometry) {
        var canvas: View? = null
        game.onActivity { activity ->
            canvas = activity.findViewById(R.id.game_canvas)
        }
        val view = requireNotNull(canvas)
        assertTrue(
            "the canvas has no size: ${view.width}x${view.height}",
            view.width > 0 && view.height > 0,
        )
        assertTrue("the canvas is not displayed", view.isShown)
        assertTrue(
            "the board geometry is empty: $board",
            board.tileSize > 0 && board.width > 0 && board.height > 0,
        )
        composeRule.onNodeWithTag(BOARD_CANVAS_TAG).assertIsDisplayed()
        val deadline = SystemClock.uptimeMillis() + 5_000
        var ops = 0
        while (SystemClock.uptimeMillis() < deadline) {
            game.onActivity { activity ->
                ops = activity.getGameView().renderFrame.value?.ops?.size ?: 0
            }
            if (ops > 0) return
            SystemClock.sleep(100)
        }
        throw AssertionError("the board frame has no drawing operations")
    }

    /** The Undo key is on screen, is at least 48dp, and stays inside the window. */
    private fun assertTheKeyBarIsUsable(game: ActivityScenario<GamePlay>, where: String) {
        val undo = composeRule.onNodeWithContentDescription("Undo")
        undo.assertIsDisplayed()
        val bounds = undo.getUnclippedBoundsInRoot()
        val keySize = dimensionDp(R.dimen.keySize)
        assertEquals("the Undo key is not $keySize dp wide in the $where", keySize, bounds.width.value, 0.5f)
        assertTrue("the Undo key is below 48dp in the $where", keySize >= 48f)
        assertKeyInsideWindow(game, bounds.left.value, "Undo ($where)")
    }

    /** The canvas view is present, laid out, and paints more than one colour. */
    private fun assertTheBoardPaints(game: ActivityScenario<GamePlay>) {        var canvas: View? = null
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

    /**
     * Runs [body] in a simulated window of [width] by [height] pixels and
     * puts the device window back afterwards. The density is not touched,
     * so a test that changes the size keeps the in-place resize path of
     * `android:configChanges` instead of a recreation.
     */
    private fun withWindowSize(width: Int, height: Int, body: () -> Unit) {
        try {
            shell("wm size ${width}x$height")
            body()
        } finally {
            restoreWindowSize()
        }
    }

    private fun restoreWindowSize() {
        if (originalWindowSize == null) shell("wm size reset") else shell("wm size $originalWindowSize")
    }

    /** Waits until this process sees a window at least [minDp] wide. */
    private fun awaitWindowWidth(minDp: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (context.resources.configuration.screenWidthDp >= minDp) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the window is ${context.resources.configuration.screenWidthDp}dp wide, not $minDp",
        )
    }

    private fun windowWidthDp(): Int = context.resources.configuration.screenWidthDp

    private fun dimensionDp(id: Int): Float {
        val resources = context.resources
        return resources.getDimension(id) / resources.displayMetrics.density
    }

    /** Waits until the chooser list reaches the reading measure [capDp]. */
    private fun awaitChooserGridWidth(capDp: Float): DpRect {
        var bounds: DpRect? = null
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            bounds = composeRule.onNodeWithTag(CHOOSER_GRID_TAG).getUnclippedBoundsInRoot()
            if (kotlin.math.abs(bounds.width.value - capDp) <= 1f) return bounds
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the chooser list is ${bounds?.width?.value}dp wide, not capped at ${capDp}dp",
        )
    }

    /** The bounds of the settings list and of the container it is capped in. */
    private data class SettingsRows(
        val width: Int,
        val left: Int,
        val parentWidth: Int,
        val parentLeft: Int,
    )

    /** Waits until the settings list is narrower than the window. */
    private fun awaitSettingsRows(prefs: ActivityScenario<PrefsActivity>): SettingsRows {
        var rows = SettingsRows(0, 0, 0, 0)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            prefs.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(android.R.id.content) as PreferenceFragmentCompat
                val list = fragment.listView
                val parent = list.parent as View
                val listLocation = IntArray(2).also { list.getLocationOnScreen(it) }
                val parentLocation = IntArray(2).also { parent.getLocationOnScreen(it) }
                rows = SettingsRows(list.width, listLocation[0], parent.width, parentLocation[0])
            }
            if (rows.width > 0 && rows.width < rows.parentWidth) return rows
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the settings list is ${rows.width} of ${rows.parentWidth}px, not capped",
        )
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

        /**
         * A simulated large window: 2400 by 3000 logical pixels at the
         * phone's 420 dpi is 914 by 1143dp, so the window width class is
         * expanded (840dp and wider) and the shape is still portrait.
         */
        const val EXPANDED_WIDTH = 2400
        const val EXPANDED_HEIGHT = 3000
        const val EXPANDED_MIN_WIDTH_DP = 840

        /** The `wm size` line that holds a window size override, if any. */
        val WINDOW_SIZE_OVERRIDE = Regex("Override size: (\\d+x\\d+)")
    }
}
