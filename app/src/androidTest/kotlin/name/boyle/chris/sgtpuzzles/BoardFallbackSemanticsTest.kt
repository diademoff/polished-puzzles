package name.boyle.chris.sgtpuzzles

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.backend.GameEngine
import name.boyle.chris.sgtpuzzles.backend.GameEngineImpl
import name.boyle.chris.sgtpuzzles.render.BOARD_LIVE_TAG
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. D-01 C fallback.
 *
 * Every ported backend now runs on the Kotlin engine, so no screen in
 * the app reaches the C fallback by launching a game. The fallback is
 * still the contract for a save restored into the C engine, so this
 * case installs a real C engine in the live game screen: it builds the
 * engine through `GameEngineImpl.fromGameID`, keeps the activity and
 * the game view as its callbacks, and lets the screen draw it.
 *
 * The C engine cannot describe its board, so the shell must speak one
 * unnamed board node with the status text, no units, and the board
 * actions. The drawing pass that produces it runs inside the C engine's
 * own callback, which is where an engine call used to re-enter and
 * crash, so the case also proves the guard by finishing.
 */
@RunWith(AndroidJUnit4::class)
class BoardFallbackSemanticsTest {

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
    fun aCEngineSpeaksOneUnnamedBoardNodeWithTheStatusAndNoUnits() {
        GamePlayTestSupport.clearBackendState(context, FIFTEEN)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, FIFTEEN, SOLVED_4X4),
        )
        scenario = launched
        GamePlayTestSupport.awaitEngine(launched)

        var engine: GameEngine? = null
        launched.onActivity { activity ->
            val view = activity.findViewById<GameView>(R.id.game_view)
            val cEngine = GameEngineImpl.cEngineForTest(
                SOLVED_4X4,
                FIFTEEN,
                activity,
                view,
                null,
            )
            assertNull(
                "the C engine reports a spoken board, so the fallback is untested",
                cEngine.spokenBoard,
            )
            activity.gameEngine = cEngine
            engine = cEngine
            // The same call the activity makes after a game starts: the
            // view asks the engine for its geometry and redraws it.
            view.rebuildBitmap()
        }
        requireNotNull(engine)

        composeRule.waitUntil(NODE_TIMEOUT) {
            composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        val board = composeRule.onNodeWithTag(BOARD_TAG).fetchSemanticsNode()
        assertNull(
            "a C board reports a cell grid",
            board.config.getOrNull(SemanticsProperties.CollectionInfo),
        )
        assertEquals(
            "a C board node is not the game name and the status text",
            "Fifteen. Moves: 0",
            board.config[SemanticsProperties.ContentDescription].joinToString(" "),
        )
        assertEquals(
            "a C board node has units",
            0,
            composeRule.onAllNodes(isBoardUnit()).fetchSemanticsNodes().size,
        )
        assertEquals(
            "a C board lost its board actions",
            listOf("Restart", "Show solution"),
            board.config[SemanticsActions.CustomActions].map { it.label },
        )
        assertEquals(
            "the C fallback announced a board change",
            " ",
            composeRule.onNodeWithTag(BOARD_LIVE_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString(" "),
        )
    }

    private fun isBoardUnit(): SemanticsMatcher =
        SemanticsMatcher("is a board unit") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("board-unit-") == true
        }

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The solved 4x4 board: no generation and no seed. */
        const val SOLVED_4X4 = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,0"
    }
}
