package name.boyle.chris.sgtpuzzles

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.preference.PreferenceManager
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.SpokenAction
import name.boyle.chris.sgtpuzzles.engine.SpokenBoard
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. D-01 wave 5: the full spoken table, one case
 * per backend.
 *
 * Each case opens the frozen seed-12345 board that
 * `testdata/spoken/<game>.txt` was written from and compares the live
 * Compose semantics tree with that committed table, unit by unit. The
 * table is the reviewable artifact of D-01: the board label, the shape,
 * the unit order, every label, and every pointer action. The asset comes
 * from the same `testdata/spoken/` directory that
 * `BoardSemanticsGoldenTest` reads, so the engine half and the shell
 * half cannot drift apart.
 *
 * The case launches the game from its ID, so nothing is generated and
 * nothing is timed. It reads the tree TalkBack reads; it does not claim
 * what TalkBack says, which is the manual D-03 pass.
 */
@RunWith(Parameterized::class)
class BoardSpokenTableTest(private val backendName: String) {

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
    fun theLiveBoardMatchesItsCommittedTable() {
        val backend = requireNotNull(backends[backendName]) { "$backendName is not a backend" }
        val table = table(backendName)
        launch(backend, table.id)
        assertBoardNode(backend, table)
        assertUnits(backend, table)
        assertBoardActions(backend)
    }

    // ------------------------------------------------------------------

    private fun launch(backend: BackendName, gameId: String) {
        GamePlayTestSupport.clearBackendState(context, backend)
        // A preference left by another test would change a label or an
        // action, so every case starts from the game's own defaults.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(backend.preferencesName)
            .commit()
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

    private fun assertBoardNode(backend: BackendName, table: SpokenTable) {
        val node = composeRule.onNodeWithTag(BOARD_TAG).fetchSemanticsNode()
        val info = node.config.getOrNull(SemanticsProperties.CollectionInfo)
        if (table.rows == 0 || table.columns == 0) {
            assertNull(
                "${backend.sourceName} reports a cell grid, but its table has none",
                info,
            )
        } else {
            assertNotNull("${backend.sourceName} does not report its cell grid", info)
            assertEquals("${backend.sourceName} rows", table.rows, info!!.rowCount)
            assertEquals("${backend.sourceName} columns", table.columns, info.columnCount)
        }
        val head = if (table.rows > 0 && table.columns > 0) {
            "${backend.displayName}, ${table.rows} by ${table.columns}."
        } else {
            "${backend.displayName}."
        }
        val expected = if (table.summary.isEmpty()) head else "$head ${table.summary}"
        assertEquals(
            "${backend.sourceName} board label",
            expected,
            node.config[SemanticsProperties.ContentDescription].joinToString(" "),
        )
    }

    private fun assertUnits(backend: BackendName, table: SpokenTable) {
        val spoken = spokenBoard()
        assertEquals(
            "${backend.sourceName} speaks a different number of units than its table",
            table.units.size,
            spoken.units.size,
        )
        val nodes = composeRule.onAllNodes(isBoardUnit()).fetchSemanticsNodes()
        assertEquals(
            "${backend.sourceName} unit nodes against the table",
            table.units.size,
            nodes.size,
        )
        val byIndex = nodes.associateBy {
            requireNotNull(it.config.getOrNull(SemanticsProperties.TestTag))
                .substringAfterLast('-').toInt()
        }
        val cells = table.rows * table.columns
        // The table is the engine's board with the game cursor hidden,
        // which is the state the golden builder reads. The shell shows
        // the cursor of a game whose arrow keys are on, so exactly that
        // one unit gains the `, cursor` suffix and nothing else changes.
        val cursorIndex = spoken.units.indexOfFirst { it.text.endsWith(CURSOR) }
        for ((index, expected) in table.units.withIndex()) {
            val node = requireNotNull(byIndex[index]) {
                "${backend.sourceName} has no unit node $index"
            }
            val unit = spoken.units[index]
            assertEquals(
                "${backend.sourceName} unit $index carries different actions than the game",
                unit.actions.map { it.label },
                node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label },
            )
            val line = if (index == cursorIndex) withCursor(expected) else expected
            assertEquals("${backend.sourceName} unit $index", line, dumpUnit(node, unit.actions))
            val item = node.config.getOrNull(SemanticsProperties.CollectionItemInfo)
            if (index < cells) {
                assertNotNull("${backend.sourceName} unit $index has no position", item)
                assertEquals(
                    "${backend.sourceName} unit $index row",
                    index / table.columns,
                    item!!.rowIndex,
                )
                assertEquals(
                    "${backend.sourceName} unit $index column",
                    index % table.columns,
                    item.columnIndex,
                )
                assertEquals("${backend.sourceName} unit $index row span", 1, item.rowSpan)
                assertEquals("${backend.sourceName} unit $index column span", 1, item.columnSpan)
            } else {
                assertNull(
                    "${backend.sourceName} unit $index is outside the grid but has a position",
                    item,
                )
            }
        }
    }

    /**
     * A fresh board offers Restart and, for a game with a solver, the
     * solution; Undo and Redo appear only after a move and an undo, which
     * `BoardSemanticsTest` pins on the pilot. The same rule holds at
     * every board size, so the cap does not change the board actions.
     */
    private fun assertBoardActions(backend: BackendName) {
        val engine = GamePlayTestSupport.awaitEngine(requireNotNull(scenario))
        val solve = engine.uiVisibility and UI_SOLVE != 0
        val expected = listOf("Restart") + if (solve) listOf("Show solution") else emptyList()
        assertEquals(
            "${backend.sourceName} board actions",
            expected,
            boardActions(),
        )
    }

    private fun boardActions(): List<String> =
        composeRule.onNodeWithTag(BOARD_TAG)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .map { it.label }

    /**
     * The unit line of the committed table: the label the node carries,
     * then the pointer buttons the game offers. The node's own actions
     * carry the label only, so the button value comes from the game's
     * board, and the case above proves the two label lists are equal.
     */
    private fun dumpUnit(node: SemanticsNode, actions: List<SpokenAction>): String {
        val label = node.config[SemanticsProperties.ContentDescription].joinToString(" ")
        val text = actions.joinToString(", ") { "${it.label} (${buttonName(it.button)})" }
        return if (text.isEmpty()) label else "$label | $text"
    }

    /** A table line with the visible cursor suffix the shell adds. */
    private fun withCursor(line: String): String = when {
        line.endsWith(CURSOR) || line.contains("$CURSOR | ") -> line
        " | " in line -> line.replace(" | ", "$CURSOR | ")
        else -> line + CURSOR
    }

    /** The board the live engine describes, read on the main thread. */
    private fun spokenBoard(): SpokenBoard {
        var board: SpokenBoard? = null
        requireNotNull(scenario).onActivity { board = it.gameEngine.spokenBoard }
        return requireNotNull(board) {
            "${backendName} has no spoken board, so the table cannot describe it"
        }
    }

    private fun buttonName(button: Int): String = when (button) {
        Buttons.LEFT_BUTTON -> "left"
        Buttons.RIGHT_BUTTON -> "right"
        Buttons.MIDDLE_BUTTON -> "middle"
        else -> button.toString()
    }

    private fun isBoardUnit(): SemanticsMatcher =
        SemanticsMatcher("is a board unit") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith("board-unit-") == true
        }

    /** The committed spoken table of one backend, from the test APK assets. */
    private fun table(backend: String): SpokenTable {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("$backend.txt").bufferedReader().use { it.readText() }
        val lines = text.lines()
        var id = ""
        var rows = 0
        var columns = 0
        var summary = ""
        val units = mutableListOf<String>()
        for (line in lines) {
            when {
                line.isEmpty() -> Unit
                line.startsWith("# id=") -> id = line.removePrefix("# id=")
                line.startsWith("rows=") -> rows = line.removePrefix("rows=").toInt()
                line.startsWith("columns=") -> columns = line.removePrefix("columns=").toInt()
                line.startsWith("summary=") -> summary = line.removePrefix("summary=")
                line.startsWith("#") -> Unit
                else -> units += line
            }
        }
        require(id.isNotEmpty()) { "$backend has no frozen id in its spoken table" }
        return SpokenTable(id, rows, columns, summary, units)
    }

    private data class SpokenTable(
        val id: String,
        val rows: Int,
        val columns: Int,
        val summary: String,
        val units: List<String>,
    )

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The suffix a game adds to the unit the arrow keys move. */
        const val CURSOR = ", cursor"

        /** `GamePlay.UIVisibility.SOLVE`, the bit the shell reads for Solve. */
        const val UI_SOLVE = 8

        val backends: Map<String, BackendName> =
            BackendName.all.associateBy { it.sourceName }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> =
            BackendName.all.map { arrayOf(it.sourceName) }.sortedBy { it[0] }
    }
}
