package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.CUBE
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.TENTS
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.render.BOARD_LIVE_TAG
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import name.boyle.chris.sgtpuzzles.render.boardUnitTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The D-01 pilot board: `tents` on the frozen
 * 8x8 board of seed 12345, and a 21x21 board above the unit cap.
 *
 * The test reads the Compose semantics tree that TalkBack reads. It
 * does not assert on what TalkBack says: pronunciation and traversal
 * are the manual D-03 pass.
 *
 * The tap is the Compose click action, not a pixel, so this test also
 * proves that a spoken move reaches the engine through the same
 * geometry as a finger.
 */
@RunWith(AndroidJUnit4::class)
class BoardSemanticsTest {

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
    fun theBoardNodeCarriesTheShapeAndTheFourActions() {
        launchTents()
        val info = boardNode().config[SemanticsProperties.CollectionInfo]
        assertEquals("the board does not report its rows", 8, info.rowCount)
        assertEquals("the board does not report its columns", 8, info.columnCount)
        assertEquals(
            "the board is not labelled with the game name and its shape",
            "Tents, 8 by 8.",
            boardLabel(),
        )

        // A fresh board: nothing to undo or redo; restart and solve are
        // the two that the shell always offers.
        assertEquals(listOf("Restart", "Show solution"), boardActions())

        tapCell(row = 3, column = 3)
        awaitActions("Undo", "Restart", "Show solution")

        // Redo appears only after an undo, so the four actions cannot be
        // on one board at once. This is the state that offers Redo.
        action("Undo")
        awaitActions("Redo", "Restart", "Show solution")
    }

    @Test
    fun aCellNodeCarriesItsContentAndItsCollectionPosition() {
        launchTents()
        val node = composeRule.onNodeWithContentDescription("Row 3, column 4, tree")
            .fetchSemanticsNode()
        val item = node.config[SemanticsProperties.CollectionItemInfo]
        assertEquals("the tree is not in row 3", 2, item.rowIndex)
        assertEquals("the tree is not in column 4", 3, item.columnIndex)
        assertEquals(1, item.rowSpan)
        assertEquals(1, item.columnSpan)
        // The cell carries the game's pointer buttons in its own words.
        val actions = node.config.getOrNull(SemanticsActions.CustomActions)
            .orEmpty().map { it.label }
        assertEquals("a tree has no pointer action", emptyList<String>(), actions)
    }

    @Test
    fun aDoubleTapOnACellMakesTheRealMoveAndTheLiveRegionSpeaks() {
        launchTents()
        val before = liveRegion()
        assertEquals("the live region is not silent at launch", " ", before)

        // Row 3, column 3 is next to the tree at row 3, column 4, so one
        // click places a tent there: the same move a finger makes.
        tapCell(row = 3, column = 3)
        awaitMove("T2,2")

        assertEquals("the board did not record one move", 1, moveCount())
        assertEquals(
            "the live region did not read the changed cell",
            "Row 3, column 3, tent.",
            awaitLiveRegion("Row 3, column 3, tent."),
        )

        // The left button walks the game's own cycle: grass, tent,
        // marked grass, grass. Every step is one announcement.
        tapCell(row = 3, column = 3)
        awaitMove("N2,2")
        assertEquals(2, moveCount())
        assertEquals(
            "the live region did not read the marked cell",
            "Row 3, column 3, marked.",
            awaitLiveRegion("Row 3, column 3, marked."),
        )
        tapCell(row = 3, column = 3)
        awaitMove("B2,2")
        assertEquals(3, moveCount())
        assertEquals(
            "the live region did not read the cleared cell",
            "Row 3, column 3, empty.",
            awaitLiveRegion("Row 3, column 3, empty."),
        )
    }

    @Test
    fun theStatusLineIsSilentWhileTheBoardSpeaks() {
        // After D-01 wave 5 every ported backend has board units, so a
        // board change must never be announced twice. The polite
        // fallback for a board without units is pinned by
        // `StatusLiveRegionTest`, which needs no device.
        launchTents()
        setStatus("Moves: 1")
        assertEquals(
            "the status line would announce a change that the board already speaks",
            View.ACCESSIBILITY_LIVE_REGION_NONE,
            statusLiveRegion(),
        )

        close()
        // `cube` is the last backend with a status bar and it has units
        // too.
        launchCube()
        setStatus("Moves: 1")
        assertEquals(
            "a unit board must keep its status line silent",
            View.ACCESSIBILITY_LIVE_REGION_NONE,
            statusLiveRegion(),
        )
    }

    @Test
    fun aBoardAboveTheCapReportsTheCursorOnly() {
        launchTents(ABOVE_CAP_ID)
        val info = boardNode().config[SemanticsProperties.CollectionInfo]
        assertEquals(21, info.rowCount)
        assertEquals(21, info.columnCount)
        assertEquals(
            "a board above the cap exposes one unit, the cursor cell",
            1,
            unitCount(),
        )
        val unit = composeRule.onNodeWithTag(boardUnitTag(0)).fetchSemanticsNode()
        val label = unit.config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue(
            "the cursor unit is not the first cell: '$label'",
            label.startsWith("Row 1, column 1, empty"),
        )
    }

    @Test
    fun theBoardDoesNotMirrorInRightToLeft() {
        launchTents()
        val before = composeRule.onNodeWithTag(boardUnitTag(0)).getUnclippedBoundsInRoot()

        scenario?.onActivity { it.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        composeRule.waitForIdle()
        scenario?.onActivity {
            assertEquals(
                "the test did not switch the window to right-to-left",
                View.LAYOUT_DIRECTION_RTL,
                it.window.decorView.layoutDirection,
            )
        }

        // The board does not mirror, so the first cell keeps its place
        // and the shell around the board flips instead (D-01 fallback).
        val after = composeRule.onNodeWithTag(boardUnitTag(0)).getUnclippedBoundsInRoot()
        assertEquals(
            "the board mirrored its first cell in right-to-left",
            before.left.value,
            after.left.value,
            1f,
        )
        assertEquals(
            "the board moved its first cell in right-to-left",
            before.top.value,
            after.top.value,
            1f,
        )
    }

    // ------------------------------------------------------------------

    private fun launchTents(gameId: String = TENTS_ID): KotlinGameEngine {
        GamePlayTestSupport.clearBackendState(context, TENTS)
        return launch(TENTS, gameId)
    }

    /**
     * A `cube` launch, which now has a Kotlin engine and board units
     * too; the board node is the ready signal.
     */
    private fun launchCube() {
        GamePlayTestSupport.clearBackendState(context, CUBE)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, CUBE, CUBE_ID),
        )
        scenario = launched
        awaitBoardNode()
    }

    private fun launch(backend: BackendName, gameId: String): KotlinGameEngine {
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, backend, gameId),
        )
        scenario = launched
        val engine = GamePlayTestSupport.awaitEngine(launched)
        awaitBoardNode()
        return engine
    }

    /** Waits for the first drawing pass, which is where the speech lands. */
    private fun awaitBoardNode() {
        composeRule.waitUntil(NODE_TIMEOUT) {
            composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun boardNode() = composeRule.onNodeWithTag(BOARD_TAG).fetchSemanticsNode()

    private fun boardLabel(): String =
        boardNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

    private fun boardActions(): List<String> =
        boardNode().config[SemanticsActions.CustomActions].map { it.label }

    /** The number of unit nodes, found by their test tag prefix. */
    private fun unitCount(): Int = composeRule.onAllNodes(
        SemanticsMatcher("is a board unit") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("board-unit-") == true
        },
    ).fetchSemanticsNodes().size

    private fun action(label: String) {
        val action = boardNode().config[SemanticsActions.CustomActions]
            .first { it.label == label }
            .action
        // A custom action touches views and the engine, so it runs on the
        // main thread like the accessibility dispatch that calls it.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action.invoke() }
        composeRule.waitForIdle()
    }

    private fun awaitActions(vararg expected: String, deadlineMs: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var actions = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            actions = boardActions()
            if (actions == expected.toList()) return
            SystemClock.sleep(50)
        }
        assertEquals("the board actions did not reach the expected set", expected.toList(), actions)
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

    private fun moveCount(): Int = moves().size

    private fun statusLiveRegion(): Int {
        var region = View.ACCESSIBILITY_LIVE_REGION_NONE
        scenario?.onActivity {
            region = it.findViewById<View>(R.id.status_bar).accessibilityLiveRegion
        }
        return region
    }

    /** Writes a status text and waits for the view to take it. */
    private fun setStatus(text: String) {
        scenario?.onActivity { it.setStatus(text) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Activates the unit of a 1-based cell through its click action, the
     * one TalkBack's double tap performs. The engine then receives the
     * same left press and release pair at the unit centre as a finger.
     */
    private fun tapCell(row: Int, column: Int) {
        composeRule.onNodeWithTag(boardUnitTag((row - 1) * 8 + (column - 1)))
            .performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
        composeRule.waitForIdle()
    }

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The frozen 8x8de tents game of seed 12345. */
        const val TENTS_ID = "8x8de:ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"

        /** The frozen 4x4 cube game of seed 12345. */
        const val CUBE_ID = "c4x4:205E,4"

        /**
         * A 21x21 board of blank cells with zero clues, above the cap.
         * The last run ends on the grid, so the description is valid.
         */
        val ABOVE_CAP_ID = "21x21:" + "z".repeat(17) + "p" + ",0".repeat(42)
    }
}
