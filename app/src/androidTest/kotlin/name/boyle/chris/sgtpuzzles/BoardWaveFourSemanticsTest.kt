package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.BRIDGES
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.LOOPY
import name.boyle.chris.sgtpuzzles.backend.SLANT
import name.boyle.chris.sgtpuzzles.backend.UNTANGLE
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
 * Level: instrumentation. D-01 wave 4: the three shapes that are not a
 * plain cell grid, on frozen small boards.
 *
 * An edge game (`slant`) turns its cell with the two pointer buttons, a
 * drag game (`bridges`) offers the one button that does not need a drag,
 * and a palette game (`guess`) reads its palette and submits from the
 * feedback unit. The last case compares every unit node of `loopy`,
 * `untangle`, and `guess` with its drawn rectangle, so the geometry
 * guard covers an edge, a point, and a palette and not only a cell.
 */
@RunWith(AndroidJUnit4::class)
class BoardWaveFourSemanticsTest {

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
    fun anEdgeCellNamesItsCluesAndTurnsWithTheTwoButtons() {
        launch(SLANT, SLANT_ID)

        val cell = unit(0)
        // The launch shows the game cursor, and the press hides it, so
        // the first label carries the suffix and the turned one does not.
        assertEquals(
            "the slant cell is not labelled with its state and its corner clues",
            "Row 1, column 1, blank, clue 2 up and right, clue 1 down and left, cursor",
            description(cell),
        )
        assertEquals(
            "the cell does not offer the game's two turn buttons",
            listOf("Clockwise", "Anticlockwise"),
            customActions(cell),
        )

        invoke(cell, "Clockwise")
        awaitMove("\\0,0")
        assertEquals(
            "the live region did not read the turned cell",
            "Row 1, column 1, backslash, clue 2 up and right, clue 1 down and left.",
            awaitLiveRegion(
                "Row 1, column 1, backslash, clue 2 up and right, clue 1 down and left.",
            ),
        )
    }

    @Test
    fun anIslandOffersItsMarkAndTheBridgeCellsStayReadOnly() {
        launch(BRIDGES, BRIDGES_ID)

        // The first unit of the frozen board, and its only real button.
        val island = unit(0)
        assertEquals(
            "the island is not labelled with its count",
            "Row 1, column 1, island 3, 0 bridges",
            description(island),
        )
        assertEquals(
            "the island does not offer the click that marks it",
            listOf("Mark"),
            customActions(island),
        )

        // A cell between two islands speaks the bridge and offers no
        // action: a bridge is a drag from one island to another.
        val empty = (0 until unitCount()).map { unit(it) }
            .first { description(it).endsWith("empty") }
        assertEquals(
            "a bridge cell offers an action, but a bridge is a drag",
            emptyList<String>(),
            customActions(empty),
        )

        invoke(island, "Mark")
        awaitMove("M0,0")
        assertEquals(
            "the live region did not read the marked island",
            "Row 1, column 1, island 3, 0 bridges, marked.",
            awaitLiveRegion("Row 1, column 1, island 3, 0 bridges, marked."),
        )
        assertEquals(
            "the island does not offer the reverse mark",
            listOf("Unmark"),
            customActions(unit(0)),
        )
    }

    @Test
    fun thePaletteIsReadOnlyAndTheFeedbackUnitSubmits() {
        launch(GUESS, GUESS_ID)

        // The first palette swatch names its colour and takes no button:
        // a colour is dragged from the palette, not clicked.
        val swatch = unit(0)
        assertEquals("the palette does not name its colour", "red", description(swatch))
        assertEquals(
            "a palette swatch claims an action, but a press only starts a drag",
            emptyList<String>(),
            customActions(swatch),
        )

        // Four digit keys fill the first row, and the feedback unit of
        // that row is the game's submit.
        pressDigits("1212")
        val feedback = (0 until unitCount()).map { unit(it) }
            .first { description(it) == "current row" }
        assertEquals(
            "the feedback unit does not offer the submit",
            listOf("Submit"),
            customActions(feedback),
        )
        invoke(feedback, "Submit")
        awaitMove("G1,2,1,2")
    }

    @Test
    fun everyUnitNodeLandsOnItsOwnRectangle() {
        for ((backend, id) in listOf(
            LOOPY to LOOPY_ID,
            UNTANGLE to UNTANGLE_ID,
            GUESS to GUESS_ID,
        )) {
            launch(backend, id)
            var frame: SpeechFrame? = null
            requireNotNull(scenario).onActivity { frame = readSpeechFrame(it) }
            val read = requireNotNull(frame)
            assertTrue("$backend exposed no units", read.units.isNotEmpty())
            assertEveryUnitLandsOnTheDrawing(composeRule, read, backend.sourceName)
            close()
        }
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
        composeRule.waitForIdle()
    }

    /** The unit nodes, in engine order: one tag per index. */
    private fun unitCount(): Int {
        var count = 0
        while (composeRule.onAllNodesWithTag(boardUnitTag(count))
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            count++
        }
        return count
    }

    private fun unit(index: Int) =
        composeRule.onNodeWithTag(boardUnitTag(index)).fetchSemanticsNode()

    private fun description(node: SemanticsNode): String =
        node.config[SemanticsProperties.ContentDescription].joinToString(" ")

    private fun customActions(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun invoke(node: SemanticsNode, label: String) {
        val action = node.config[SemanticsActions.CustomActions]
            .first { it.label == label }
            .action
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action.invoke() }
        composeRule.waitForIdle()
    }

    private fun pressDigits(digits: String) {
        for (digit in digits) {
            scenario?.onActivity { it.sendKey(0, 0, digit.code) }
            composeRule.waitForIdle()
        }
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

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The frozen 5x5de slant game of seed 12345. */
        const val SLANT_ID = "5x5:a2d1b2a2b3d3b2b21a1c1110"

        /** The frozen 7x7 bridges game of seed 12345. */
        const val BRIDGES_ID = "7x7m2:3e2g6a3c2i1b3h4d4a"

        /** The frozen six-colour Guess game of seed 12345. */
        const val GUESS_ID = "c6p4g10Bm:ac769e34"

        /** The frozen 3x2 dodecagonal loopy game of seed 12345. */
        const val LOOPY_ID = "3x2t13:2c152a3a21a21c31c54c0b"

        /** The frozen six-point untangle game of seed 12345. */
        const val UNTANGLE_ID = "6:0-1,0-2,0-3,0-5,1-3,1-4,1-5,2-3,2-4,2-5,3-4"
    }
}
