package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.UNRULY
import name.boyle.chris.sgtpuzzles.render.BOARD_LIVE_TAG
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. Game: unruly.
 *
 * D-01 S4: with a hardware keyboard the game keeps its own keys. The
 * board view holds the focus, the arrow keys move the game cursor and
 * the polite live region speaks the new unit, and Enter makes the real
 * move. The board and its units stay in the node tree, so a screen
 * reader can still reach them while the keyboard plays.
 */
@RunWith(AndroidJUnit4::class)
class BoardKeyNavigationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<GamePlay>? = null

    @After
    fun close() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun theArrowKeysMoveTheCursorAndEnterMakesTheMove() {
        val engine = launch()
        assertEquals("the board did not expose its units", 64, unitCount())
        assertTrue("the board view does not hold the focus", gameView().hasFocus())

        // The first arrow reveals the game cursor on the cell it points
        // at; the live region reads that one changed unit.
        pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(
            "Row 1, column 2, white, given, cursor.",
            awaitLiveRegion("Row 1, column 2, white, given, cursor."),
        )

        // The next arrow moves the cursor: the old cell loses the
        // suffix and the new one gains it, so both are read in board
        // order.
        pressKey(KeyEvent.KEYCODE_DPAD_DOWN)
        val both = "Row 1, column 2, white, given. Row 2, column 2, empty, cursor."
        assertEquals(both, awaitLiveRegion(both))

        // Enter is the game's own select, and it writes one real move.
        pressKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(
            "Row 2, column 2, black, cursor.",
            awaitLiveRegion("Row 2, column 2, black, cursor."),
        )
        awaitMoveCount(1)

        // The game keys did not take the board out of the node tree.
        assertEquals(64, unitCount())
        assertTrue("the board node disappeared", boardNodes() > 0)
        assertTrue("the board view lost the focus", gameView().hasFocus())
    }

    // ------------------------------------------------------------------

    private fun launch(): KotlinGameEngine {
        GamePlayTestSupport.clearBackendState(context, UNRULY)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, UNRULY, UNRULY_ID),
        )
        scenario = launched
        val engine = GamePlayTestSupport.awaitEngine(launched)
        composeRule.waitUntil(NODE_TIMEOUT) {
            composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        return engine
    }

    private fun gameView(): View {
        var view: View? = null
        scenario?.onActivity { view = it.findViewById(R.id.game_view) }
        return view ?: error("the game activity is gone")
    }

    private fun pressKey(key: Int) {
        onView(withId(R.id.game_view)).perform(ViewActions.pressKey(key))
        composeRule.waitForIdle()
    }

    private fun boardNodes(): Int =
        composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().size

    private fun unitCount(): Int = composeRule.onAllNodes(
        SemanticsMatcher("is a board unit") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("board-unit-") == true
        },
    ).fetchSemanticsNodes().size

    private fun liveRegion(): String =
        composeRule.onNodeWithTag(BOARD_LIVE_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString(" ")

    private fun awaitLiveRegion(expected: String, deadlineMs: Long = 5_000): String {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var text = ""
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            text = liveRegion()
            if (text == expected) return text
            SystemClock.sleep(50)
        }
        return text
    }

    private fun awaitMoveCount(expected: Int, deadlineMs: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var count = 0
        while (SystemClock.uptimeMillis() < deadline) {
            var engine: KotlinGameEngine? = null
            scenario?.onActivity { engine = it.gameEngine as KotlinGameEngine }
            count = engine?.let { GamePlayTestSupport.moveCount(it) } ?: 0
            if (count == expected) return
            SystemClock.sleep(50)
        }
        assertEquals("the keyboard move was not recorded", expected, count)
    }

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The frozen 8x8 unruly board of the spoken table. */
        const val UNRULY_ID = "8x8:bEACHcBdBDEalcbAbbBa"
    }
}
