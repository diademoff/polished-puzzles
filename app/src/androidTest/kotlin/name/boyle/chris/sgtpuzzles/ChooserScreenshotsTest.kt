package name.boyle.chris.sgtpuzzles

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.LOOPY
import name.boyle.chris.sgtpuzzles.backend.MOSAIC
import name.boyle.chris.sgtpuzzles.backend.SAMEGAME
import name.boyle.chris.sgtpuzzles.backend.TRACKS
import name.boyle.chris.sgtpuzzles.backend.UNTANGLE
import name.boyle.chris.sgtpuzzles.chooser.CHOOSER_GRID_TAG
import name.boyle.chris.sgtpuzzles.chooser.chooserGameTag
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CHOOSER_STYLE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.STATE_PREFS_NAME
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.cleanstatusbar.CleanStatusBar

@RunWith(AndroidJUnit4::class)
class ChooserScreenshotsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<GameChooser>()

    @Test
    fun testTakeScreenshots() {
        when (TestUtils.fastlaneDeviceTypeOrSkipTest) {
            "tenInch" -> {
                scrollToAndScreenshot(GUESS, "07_chooser")
                scrollToAndScreenshot(UNTANGLE, "08_chooser")
            }

            "sevenInch" -> {
                scrollToAndScreenshot(GUESS, "06_chooser")
                scrollToAndScreenshot(SAMEGAME, "07_chooser")
                scrollToAndScreenshot(UNTANGLE, "08_chooser")
            }

            "phone" -> {
                prefs.edit().putString(CHOOSER_STYLE_KEY, "grid")
                    // Star 3 arbitrarily chosen extra games so that every
                    // icon fits in fewer rows.
                    .putBoolean("starred_$LOOPY", true)
                    .putBoolean("starred_$MOSAIC", true)
                    .putBoolean("starred_$TRACKS", true)
                    .apply()
                composeRule.waitForIdle()
                // Compact mode shows icons only; the name is the icon's
                // content description.
                composeRule.onAllNodesWithText(GUESS.displayName, substring = true)
                    .assertCountEquals(0)
                assertIconVisible(GUESS)
                assertIconVisible(UNTANGLE)
                Screengrab.screenshot("08_chooser_grid")
                prefs.edit().clear().apply()
            }
        }
    }

    private fun assertIconVisible(backend: BackendName) {
        scrollTo(backend)
        composeRule.onNodeWithTag(chooserGameTag(backend)).assertIsDisplayed()
    }

    private fun scrollToAndScreenshot(backend: BackendName, screenshotName: String) {
        scrollTo(backend)
        composeRule.onNodeWithTag(chooserGameTag(backend)).assertIsDisplayed()
        composeRule.waitForIdle()
        Screengrab.screenshot(screenshotName)
    }

    private fun scrollTo(backend: BackendName) {
        composeRule.onNodeWithTag(CHOOSER_GRID_TAG)
            .performScrollToNode(hasTestTag(chooserGameTag(backend)))
        composeRule.waitForIdle()
    }

    companion object {
        private val prefs =
            PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())

        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            CleanStatusBar.enableWithDefaults()
            prefs.edit().clear().apply()
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        @JvmStatic
        @AfterClass
        fun afterAll() {
            CleanStatusBar.disable()
        }
    }
}
