package name.boyle.chris.sgtpuzzles.chooser

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.NET
import name.boyle.chris.sgtpuzzles.ui.SgTPuzzlesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The chooser screen without the activity.
 *
 * These tests need a device or an emulator. They are not skipped when
 * the fastlane `device_type` argument is absent, unlike the screenshot
 * test.
 */
@RunWith(AndroidJUnit4::class)
class ChooserScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val games = listOf(GUESS, KEEN, NET)

    @Test
    fun aClickOpensTheGame() {
        val clicked = mutableListOf<BackendName>()
        showScreen(onGameClick = { clicked += it })

        rule.onNodeWithTag(chooserGameTag(KEEN)).performClick()

        assertEquals(listOf(KEEN), clicked)
    }

    @Test
    fun aLongPressTogglesTheStar() {
        val toggled = mutableListOf<BackendName>()
        showScreen(onStarToggle = { toggled += it })

        rule.onNodeWithTag(chooserGameTag(NET)).performTouchInput { longClick() }

        assertEquals(listOf(NET), toggled)
    }

    @Test
    fun compactModeShowsNoText() {
        showScreen(compact = true)

        rule.onAllNodesWithText(GUESS.displayName, substring = true).assertCountEquals(0)
        rule.onNodeWithTag(chooserGameTag(GUESS)).assertExists()
    }

    @Test
    fun theStarredSectionComesFirst() {
        showScreen(starred = setOf(NET))

        val starredTop = rule.onNodeWithTag("section-${R.string.games_starred}")
            .fetchSemanticsNode().boundsInRoot.top
        val othersTop = rule.onNodeWithTag("section-${R.string.games_others}")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("the starred section must be above the others", starredTop < othersTop)
    }

    private fun showScreen(
        starred: Set<BackendName> = emptySet(),
        compact: Boolean = false,
        onGameClick: (BackendName) -> Unit = {},
        onStarToggle: (BackendName) -> Unit = {},
    ) {
        rule.setContent {
            SgTPuzzlesTheme {
                ChooserScreen(
                    games = games,
                    starred = starred,
                    current = null,
                    compact = compact,
                    isNight = false,
                    scrollToken = 0,
                    onGameClick = onGameClick,
                    onStarToggle = onStarToggle,
                    onLoad = {},
                    onSettings = {},
                    onHelp = {},
                    onFeedback = {},
                )
            }
        }
    }
}
