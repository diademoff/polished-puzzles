package name.boyle.chris.sgtpuzzles.screenshot

import android.content.Context
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import name.boyle.chris.sgtpuzzles.GameChooser
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CHOOSER_STYLE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.NIGHT_MODE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.STATE_PREFS_NAME
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.cleanstatusbar.CleanStatusBar
import java.io.FileInputStream

/**
 * Level: instrumentation. The chooser, compared pixel by pixel with a
 * committed golden.
 *
 * The scene is fixed: default preferences, list or grid mode, light or
 * dark theme, no saved game, no current game. The status bar is excluded
 * from the comparison. Changing any of that, or the AVD, changes the
 * pixels on purpose.
 *
 * Goldens are local: `screens/` is git-ignored, so a fresh checkout skips
 * these tests. Record them once on the reference AVD and keep them in
 * the working tree. To regenerate:
 *
 * ```sh
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -e recordScreens true \
 *   -e class name.boyle.chris.sgtpuzzles.screenshot.ChooserScreenshotTest \
 *   name.boyle.chris.sgtpuzzles.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/name.boyle.chris.sgtpuzzles/files/screens \
 *   app/src/androidTest/assets/screens
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ChooserScreenshotTest {

    @Test
    fun chooserListLight() {
        withChooser(darkTheme = false, compact = false) {
            ScreenshotGoldens.compare("chooser-list-light")
        }
    }

    @Test
    fun chooserListDark() {
        withChooser(darkTheme = true, compact = false) {
            ScreenshotGoldens.compare("chooser-list-dark")
        }
    }

    @Test
    fun chooserGridLight() {
        withChooser(darkTheme = false, compact = true) {
            ScreenshotGoldens.compare("chooser-grid-light")
        }
    }

    @After
    fun reset() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        appPrefs.edit().clear().commit()
        appState.edit().clear().commit()
    }

    private fun withChooser(darkTheme: Boolean, compact: Boolean, block: () -> Unit) {
        appPrefs.edit()
            .clear()
            .putString(NIGHT_MODE_KEY, if (darkTheme) "on" else "off")
            .putString(CHOOSER_STYLE_KEY, if (compact) "grid" else "list")
            .commit()
        appState.edit().clear().commit()
        // Set the mode before the activity exists; the night mode service
        // then keeps the same value from the preference.
        AppCompatDelegate.setDefaultNightMode(
            if (darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
        ActivityScenario.launch(GameChooser::class.java).use { scenario ->
            // Another test may have saved a game after the state was
            // cleared. Recreating the activity makes it read the cleared
            // state again, so there is no current game and no scroll.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            appState.edit().clear().commit()
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // Compose has no Espresso idling resource here; give the first
            // frame and the icon painters time to settle.
            SystemClock.sleep(1500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            waitForNoToast()
            block()
        }
    }

    /**
     * A game test may leave a controls reminder on screen, and toasts are
     * drawn by the system over any activity. Wait until the toast windows
     * are gone; otherwise the picture is not the screen we mean to check.
     */
    private fun waitForNoToast(timeoutMs: Long = 60_000) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val dump = automation.executeShellCommand("dumpsys window windows").use { pfd ->
                FileInputStream(pfd.fileDescriptor).readBytes().decodeToString()
            }
            if (!dump.contains("Toast")) return
            SystemClock.sleep(250)
        }
        throw AssertionError("a toast did not go away within ${timeoutMs}ms")
    }

    companion object {
        private val context: Context get() = ApplicationProvider.getApplicationContext()
        private val appPrefs get() = PreferenceManager.getDefaultSharedPreferences(context)
        private val appState get() = context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)

        // Keep the status bar clean and stable. Its rows are excluded from
        // the comparison, but a demo clock and hidden notifications also
        // stop the rest of the layout from moving.
        private val cleanStatusBar = CleanStatusBar()
            .setClock("1200")
            .setBatteryLevel(100)
            .setBatteryPlugged(false)
            .setShowNotifications(false)

        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            cleanStatusBar.enable()
        }

        @JvmStatic
        @AfterClass
        fun afterAll() {
            CleanStatusBar.disable()
        }
    }
}
