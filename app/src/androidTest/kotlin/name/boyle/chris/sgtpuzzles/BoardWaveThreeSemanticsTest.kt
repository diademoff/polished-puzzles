package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.FLOOD
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.TOWERS
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.render.BOARD_LIVE_TAG
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import name.boyle.chris.sgtpuzzles.render.boardUnitTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. D-01 wave 3: one game with an off-grid clue
 * strip and one whose state is a colour, both on frozen small boards.
 *
 * The test reads the Compose semantics tree that TalkBack reads, so a
 * unit label, a unit action, and the live region are checked without a
 * listening pass; pronunciation stays in D-03.
 */
@RunWith(AndroidJUnit4::class)
class BoardWaveThreeSemanticsTest {

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
    fun aClueUnitCarriesItsCountAndTheDoneAction() {
        launch(TOWERS, TOWERS_ID)

        // The clue strip follows the sixteen cells, top band first.
        val clue = unit(Indices.CLUE_INDEX)
        assertEquals(
            "the clue unit is not labelled with its sight count",
            "Column 1, 1 from the top",
            description(clue),
        )
        assertEquals(
            "the clue does not offer the game's Done toggle",
            listOf("Done"),
            customActions(clue),
        )

        invoke(clue, "Done")
        awaitMove("D0,-1")
        assertEquals(
            "the live region did not read the marked clue",
            "Column 1, 1 from the top, done.",
            awaitLiveRegion("Column 1, 1 from the top, done."),
        )
        assertEquals(
            "the clue does not offer the reverse toggle",
            listOf("Not done"),
            customActions(unit(Indices.CLUE_INDEX)),
        )
    }

    @Test
    fun aCellNamesItsColourAndADoubleTapFloodsIt() {
        launch(FLOOD, FLOOD_ID)

        val cell = unit(1)
        assertEquals(
            "the cell does not name its colour",
            "Row 1, column 2, yellow",
            description(cell),
        )
        assertEquals(
            "the cell does not offer the flood button",
            listOf("Fill"),
            customActions(cell),
        )

        composeRule.onNodeWithContentDescription("Row 1, column 2, yellow")
            .performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
        composeRule.waitForIdle()
        awaitMove("M1")
        assertEquals(
            "the live region did not name the colour that changed",
            "Row 1, column 1, yellow.",
            awaitLiveRegion("Row 1, column 1, yellow."),
        )
    }

    // ------------------------------------------------------------------

    private fun launch(backend: BackendName, gameId: String) {
        GamePlayTestSupport.clearBackendState(context, backend)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, backend, gameId),
        )
        scenario = launched
        GamePlayTestSupport.awaitEngine(launched)
        composeRule.waitUntil(NODE_TIMEOUT) {
            composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun unit(index: Int) =
        composeRule.onNodeWithTag(boardUnitTag(index)).fetchSemanticsNode()

    private fun description(node: SemanticsNode): String =
        node.config[SemanticsProperties.ContentDescription].joinToString(" ")

    private fun customActions(
        node: SemanticsNode,
    ): List<String> = node.config.getOrNull(SemanticsActions.CustomActions)
        .orEmpty().map { it.label }

    private fun invoke(node: SemanticsNode, label: String) {
        val action = node.config[SemanticsActions.CustomActions]
            .first { it.label == label }
            .action
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action.invoke() }
        composeRule.waitForIdle()
    }

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

    private fun awaitMove(move: String, deadlineMs: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var moves = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            moves = moves()
            if (moves.lastOrNull() == move) return
            SystemClock.sleep(50)
        }
        assertEquals("the spoken click did not make the expected move", move, moves.lastOrNull())
    }

    private fun moves(): List<String> {
        var save = ""
        scenario?.onActivity {
            save = GamePlayTestSupport.saveText(it.gameEngine as KotlinGameEngine)
        }
        return SgtpSave.parse(save).values("MOVE")
    }

    /** The unit index of the towers clue strip and the flood cell. */
    private object Indices {
        const val CLUE_INDEX = 16
    }

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The frozen 4de towers game of seed 12345. */
        const val TOWERS_ID = "4:1/2/2/3/3/2/2/1/1/2/3/3/3/3/2/1"

        /**
         * A three by three flood board whose top-left cell is alone in its
         * colour, so one tap floods exactly one cell and the board is not
         * complete afterwards.
         */
        const val FLOOD_ID = "3x3c3m0:011122201,20"
    }
}
