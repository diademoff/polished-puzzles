package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.NIGHT_MODE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.STATE_PREFS_NAME
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.render.BoardFrame
import name.boyle.chris.sgtpuzzles.ui.SystemColors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The shell after the Material 3 pass: the key
 * bar states, the "Use system colors" setting, and the promise that the
 * board palette is not part of the theme.
 */
@RunWith(AndroidJUnit4::class)
class ShellThemeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var originalSystemColors: Boolean? = null
    private var originalNightMode: String? = null

    @Before
    fun rememberSettings() {
        originalSystemColors =
            if (prefs.contains(SystemColors.KEY)) prefs.getBoolean(SystemColors.KEY, true) else null
        originalNightMode = prefs.getString(NIGHT_MODE_KEY, null)
    }

    @After
    fun restoreSettings() {
        val systemColors = originalSystemColors
        prefs.edit().apply {
            if (systemColors == null) remove(SystemColors.KEY)
            else putBoolean(SystemColors.KEY, systemColors)
            if (originalNightMode == null) remove(NIGHT_MODE_KEY)
            else putString(NIGHT_MODE_KEY, originalNightMode)
        }.commit()
    }

    /** The undo and redo keys keep their enabled state and their job. */
    @Test
    fun theKeyBarKeepsTheUndoAndRedoContract() {
        // The bar is a surface with keys on it, not one flat band: the
        // composable reads these two tokens.
        assertNotEquals(
            "the key bar and its keys must not paint the same color",
            context.getColor(R.color.keyboard_surface),
            context.getColor(R.color.key_surface),
        )

        GamePlayTestSupport.clearFifteenState(context)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, GamePlayTestSupport.SOLVED_4X4),
        ).use { scenario ->
            GamePlayTestSupport.awaitEngine(scenario)

            composeRule.onNodeWithContentDescription("Undo").assertIsDisplayed().assertIsNotEnabled()
            composeRule.onNodeWithContentDescription("Redo").assertIsNotEnabled()

            tapTileRow3Column2(scenario)
            awaitStatus(scenario, "Moves: 1")
            composeRule.onNodeWithContentDescription("Undo").assertIsEnabled()
            composeRule.onNodeWithContentDescription("Redo").assertIsNotEnabled()

            composeRule.onNodeWithContentDescription("Undo").performClick()
            awaitStatus(scenario, "Moves: 0")
            composeRule.onNodeWithContentDescription("Redo").assertIsEnabled()
        }
    }

    /**
     * `docs/DESIGN.md` section 3: the shell roles come from the system
     * palette when the user enables it, and the board palette does not
     * move. The recorded frame and the engine colors are the evidence.
     */
    @Test
    fun theSystemColorsSettingChangesTheShellRolesAndNotTheBoard() {
        assumeTrue("dynamic color needs Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val appTheme = readShellRoles(systemColors = false, night = false)
        val appBoard = captureBoard()

        val systemTheme = readShellRoles(systemColors = true, night = false)
        val systemBoard = captureBoard()

        assertEquals(
            "the app palette must be the theme when the setting is off",
            appTheme.shellPrimary,
            appTheme.primary,
        )
        assertEquals(
            "the app surface must be the theme when the setting is off",
            appTheme.shellSurface,
            appTheme.surface,
        )
        assertEquals(
            "the system palette must be the theme when the setting is on",
            systemTheme.dynamicPrimary,
            systemTheme.primary,
        )
        assertEquals(
            "the system surface must be the theme when the setting is on",
            systemTheme.dynamicSurface,
            systemTheme.surface,
        )
        assertNotEquals(
            "the surface did not change with the system palette",
            appTheme.surface,
            systemTheme.surface,
        )
        assertNotEquals(
            "the setting changed nothing",
            appTheme.primary,
            systemTheme.primary,
        )
        assertTrue("the board recorded no colors", appBoard.colours.isNotEmpty())
        assertArrayEquals(
            "the board palette changed with the shell theme",
            appBoard.colours,
            systemBoard.colours,
        )
        assertEquals(
            "the recorded board frame changed with the shell theme",
            appBoard.frame,
            systemBoard.frame,
        )
    }

    /** The dark palette is its own palette, also with the system colors. */
    @Test
    fun theSystemColorsSettingFollowsTheDarkPalette() {
        assumeTrue("dynamic color needs Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val darkTheme = readShellRoles(systemColors = true, night = true)
        assertEquals(
            "the dark theme must use the system's dark primary",
            darkTheme.dynamicPrimary,
            darkTheme.primary,
        )
        assertNotEquals(
            "the dark theme still uses the app palette",
            darkTheme.shellPrimary,
            darkTheme.primary,
        )
    }

    /**
     * The roles of a settings screen that is built while the settings hold
     * these values.
     *
     * The night mode is applied by the helper service after the first
     * screen exists, and the settings activity is `singleTask`, so the
     * screen is rebuilt until it runs in the requested night mode. Both
     * values are read from one activity, so the app palette and the
     * resolved role share a configuration.
     */
    private fun readShellRoles(systemColors: Boolean, night: Boolean): ShellRoles {
        prefs.edit()
            .putBoolean(SystemColors.KEY, systemColors)
            .putString(NIGHT_MODE_KEY, if (night) "on" else "off")
            .commit()
        var roles: ShellRoles? = null
        ActivityScenario.launch<PrefsActivity>(
            Intent(context, PrefsActivity::class.java),
        ).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + NIGHT_MODE_DEADLINE_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.waitForIdleSync()
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    if (NightModeHelper.isNight(activity.resources.configuration) == night) {
                        // The Material 3 dynamic scheme is the definition of
                        // "from the system palette"; it is read under the same
                        // configuration as the screen.
                        val dynamic = ContextThemeWrapper(
                            activity,
                            if (night) {
                                com.google.android.material.R.style.Theme_Material3_DynamicColors_Dark
                            } else {
                                com.google.android.material.R.style.Theme_Material3_DynamicColors_Light
                            },
                        )
                        roles = ShellRoles(
                            primary = role(activity, androidx.appcompat.R.attr.colorPrimary),
                            surface = role(activity, com.google.android.material.R.attr.colorSurface),
                            shellPrimary = activity.getColor(R.color.shell_primary),
                            shellSurface = activity.getColor(R.color.shell_surface),
                            dynamicPrimary = role(dynamic, androidx.appcompat.R.attr.colorPrimary),
                            dynamicSurface = role(dynamic, com.google.android.material.R.attr.colorSurface),
                        )
                    }
                }
                roles?.let { return@use }
                SystemClock.sleep(200)
            }
        }
        return checkNotNull(roles) { "the settings screen never reached night=$night" }
    }

    /** The engine's palette and recorded frame for the solved 4x4 board. */
    private fun captureBoard(): CapturedBoard {
        GamePlayTestSupport.clearFifteenState(context)
        var captured: CapturedBoard? = null
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, GamePlayTestSupport.SOLVED_4X4),
        ).use { scenario ->
            GamePlayTestSupport.awaitEngine(scenario)
            val deadline = SystemClock.uptimeMillis() + DRAWN_BOARD_DEADLINE_MS
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val colours = activity.getGameView().renderColours.value
                    val frame = activity.getGameView().renderFrame.value
                    if (colours.isNotEmpty() && frame != null) {
                        captured = CapturedBoard(colours.copyOf(), frame)
                    }
                }
                captured?.let { return@use }
                SystemClock.sleep(50)
            }
        }
        return checkNotNull(captured) { "the board drew nothing" }
    }

    /** A tap on the tile left of the gap of the solved 4x4 board. */
    private fun tapTileRow3Column2(scenario: ActivityScenario<GamePlay>) {
        val board = awaitBoard(scenario)
        onView(withId(R.id.game_view)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                { view ->
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

    /** The geometry of the laid-out board, after the launch resize. */
    private fun awaitBoard(scenario: ActivityScenario<GamePlay>): BoardGeometry {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var board: BoardGeometry? = null
            scenario.onActivity { activity ->
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

    private fun awaitStatus(scenario: ActivityScenario<GamePlay>, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var status = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { status = GamePlayTestSupport.statusText(it) }
            if (status.contains(expected)) return
            SystemClock.sleep(50)
        }
        throw AssertionError("the status is '$status', not '$expected'")
    }

    private data class CapturedBoard(val colours: IntArray, val frame: BoardFrame)

    private fun role(context: android.content.Context, attr: Int): Int = checkNotNull(
        MaterialColors.getColorOrNull(context, attr),
    ) { "the theme has no color for attribute $attr" }

    private data class ShellRoles(
        val primary: Int,
        val surface: Int,
        val shellPrimary: Int,
        val shellSurface: Int,
        val dynamicPrimary: Int,
        val dynamicSurface: Int,
    )

    private companion object {
        const val DRAWN_BOARD_DEADLINE_MS = 5_000L
        const val NIGHT_MODE_DEADLINE_MS = 10_000L
    }
}
