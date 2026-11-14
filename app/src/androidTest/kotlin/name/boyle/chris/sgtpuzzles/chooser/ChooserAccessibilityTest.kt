package name.boyle.chris.sgtpuzzles.chooser

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.NET
import name.boyle.chris.sgtpuzzles.ui.SgTPuzzlesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.unit.dp

/**
 * Level: instrumentation. Accessibility checks on the chooser screen.
 *
 * These are the rules from `docs/DESIGN.md` section 11 that a test can
 * check: every clickable node has a spoken label, every target is at
 * least 48dp, and the star action is reachable without a long press.
 * TalkBack traversal itself still needs a manual pass.
 */
@RunWith(AndroidJUnit4::class)
class ChooserAccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private val games = listOf(GUESS, KEEN, NET)

    @Test
    fun everyClickableHasALabel() {
        showScreen()
        val unlabeled = rule.onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .filter { node ->
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                val text = node.config.getOrNull(SemanticsProperties.Text)
                description.isNullOrEmpty() && text.isNullOrEmpty()
            }
        assertTrue(
            "clickable nodes without a label: ${unlabeled.map { it.id }}",
            unlabeled.isEmpty(),
        )
    }

    @Test
    fun everyClickableTargetIsAtLeast48Dp() {
        showScreen()
        val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
            val widthDp = with(rule.density) { node.size.width.toDp() }
            val heightDp = with(rule.density) { node.size.height.toDp() }
            if (widthDp >= 48.dp && heightDp >= 48.dp) {
                null
            } else {
                val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                "${widthDp.value}x${heightDp.value}dp tag=$tag text=$text description=$description"
            }
        }
        assertTrue("clickable targets below 48dp: $small", small.isEmpty())
    }

    @Test
    fun theStarActionIsAvailableWithoutLongPress() {
        showScreen()

        rule.onNodeWithTag(chooserGameTag(GUESS))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun theSectionsAreHeadings() {
        showScreen(starred = setOf(NET))

        rule.onNodeWithTag("section-${R.string.games_starred}")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    private fun showScreen(starred: Set<BackendName> = emptySet()) {
        rule.setContent {
            SgTPuzzlesTheme {
                ChooserScreen(
                    games = games,
                    starred = starred,
                    current = null,
                    compact = false,
                    isNight = false,
                    scrollToken = 0,
                    onGameClick = {},
                    onStarToggle = {},
                    onLoad = {},
                    onSettings = {},
                    onHelp = {},
                    onFeedback = {},
                )
            }
        }
    }
}
