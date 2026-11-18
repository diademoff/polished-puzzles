package name.boyle.chris.sgtpuzzles.chooser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.NET
import name.boyle.chris.sgtpuzzles.ui.SgTPuzzlesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    @Test
    fun theRowIsOneTalkableItemWithAChangeableStarState() {
        var starred by mutableStateOf(emptySet<BackendName>())
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
                    onStarToggle = { game ->
                        starred = if (game in starred) starred - game else starred + game
                    },
                    onLoad = {},
                    onSettings = {},
                    onHelp = {},
                    onFeedback = {},
                )
            }
        }
        val starAction = context.getString(R.string.star_game)

        // The name and the description are on the clickable node itself,
        // not on a child that a service has to find.
        val row = rule.onNodeWithTag(chooserGameTag(GUESS))
        row.assert(hasClickAction())
        val before = row.fetchSemanticsNode()
        val spoken = before.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            .orEmpty()
        assertTrue("the row does not carry the game name: '$spoken'", spoken.contains(GUESS.displayName))
        assertTrue(
            "the row does not carry the game description: '$spoken'",
            spoken.contains(context.getString(GUESS.description)),
        )
        assertNull(
            "the unstarred row claims a star state",
            before.config.getOrNull(SemanticsProperties.StateDescription),
        )

        // The star is an action with a spoken state, not a visual badge.
        val actions = before.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val action = actions.firstOrNull { it.label == starAction }
        assertNotNull("no custom action spoken as '$starAction' in ${actions.map { it.label }}", action)
        action!!.action()
        rule.waitForIdle()

        val after = rule.onNodeWithTag(chooserGameTag(GUESS)).fetchSemanticsNode()
        assertEquals(
            "the star state was not spoken after the action",
            context.getString(R.string.starred),
            after.config.getOrNull(SemanticsProperties.StateDescription),
        )
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
