package name.boyle.chris.sgtpuzzles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.engine.BOARD_UNIT_CAP
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.PortedGames
import name.boyle.chris.sgtpuzzles.engine.spokenLabel
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import name.boyle.chris.sgtpuzzles.render.BoardSemantics
import name.boyle.chris.sgtpuzzles.render.BoardSpeech
import name.boyle.chris.sgtpuzzles.render.boardUnitTag
import name.boyle.chris.sgtpuzzles.render.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. D-01 S2, the cursor contract above
 * [BOARD_UNIT_CAP].
 *
 * D-01 exposes one node per unit at or below 400 units. Above it a game
 * returns the cursor unit alone and the arrow keys move it, and the
 * board node keeps the four board actions. Each case builds the board
 * from a hand-written description or from the C description frozen in
 * the table, so no case generates a board, and none opens the game
 * screen: the unit count is what the engine answers and the node and its
 * actions are what the shell composable renders from that answer.
 *
 * The state is only loaded, never drawn, so a 40,200-cell `inertia`
 * board costs the same as a small one.
 */
@RunWith(Parameterized::class)
class BoardCapSemanticsTest(private val game: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aboveTheCapOnlyTheCursorUnitIsSpokenAndTheBoardKeepsItsActions() {
        val backend = requireNotNull(PortedGames.byBackend(game)) { "$game is not ported" }
        val name = BackendName.all.first { it.sourceName == game }
        val testCase = aboveCapIds.getValue(game)
        val midend = Midend.fromGameId(backend, testCase.id)

        // A fresh board hides its game cursor, as C does, so the player
        // starts with the board node alone. One arrow press places it.
        backend.setCursorVisible(midend.ui, false)
        val hidden = backend.spokenBoard(midend.state, midend.ui, TILE)
        assertTrue(
            "$game is not above the unit cap: ${hidden.rows} by ${hidden.columns} " +
                "is ${hidden.rows * hidden.columns} cells",
            hidden.rows * hidden.columns > BOARD_UNIT_CAP || testCase.unitCapped,
        )
        assertTrue(
            "$game hides its cursor but speaks ${hidden.units.size} units",
            hidden.units.size <= 1,
        )

        midend.setCursorVisible(true)
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, TILE)
        val board = backend.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(
            "$game speaks more than the cursor unit above the cap",
            1,
            board.units.size,
        )
        val unit = board.units[0]

        val speech = BoardSpeech(
            board = board,
            name = name.displayName,
            status = midend.statusText(),
            canUndo = true,
            canRedo = true,
            canSolve = true,
        )
        assertEquals("$game loses the cursor unit in the shell", listOf(unit), speech.units())

        composeRule.setContent {
            BoardSemantics(
                speech = speech,
                matrix = IDENTITY,
                onUnitClick = { _, _ -> },
                onUndo = {},
                onRedo = {},
                onRestart = {},
                onSolve = {},
            )
        }
        composeRule.waitForIdle()

        val boardNode = composeRule.onNodeWithTag(BOARD_TAG).fetchSemanticsNode()
        val info = boardNode.config.getOrNull(SemanticsProperties.CollectionInfo)
        if (board.rows > 0 && board.columns > 0) {
            // The shape is still spoken above the cap; only the units
            // are reduced to the cursor.
            assertEquals("$game rows", board.rows, info?.rowCount)
            assertEquals("$game columns", board.columns, info?.columnCount)
        } else {
            assertNull("$game reports a cell grid but has none", info)
        }
        assertEquals(
            "$game board actions",
            listOf("Undo", "Redo", "Restart", "Show solution"),
            boardNode.config[SemanticsActions.CustomActions].map { it.label },
        )

        val units = composeRule.onAllNodes(isBoardUnit()).fetchSemanticsNodes()
        assertEquals("$game speaks more than one unit node", 1, units.size)
        assertEquals(
            "$game cursor unit label",
            unit.spokenLabel(),
            units[0].config[SemanticsProperties.ContentDescription].joinToString(" "),
        )
        assertEquals(
            "$game cursor unit node is not the cursor unit",
            unit.spokenLabel(),
            composeRule.onNodeWithTag(boardUnitTag(0))
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
        const val TILE = 32

        /** The identity matrix: a unit node is placed at its own rectangle. */
        val IDENTITY = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

        /**
         * One above-cap board per game whose cap `docs/DESIGN.md`
         * section 11 documents, each built from a hand-written
         * description or from the C-generated description frozen here.
         * Every board is above 400 units, and every one loads without
         * generating anything. [CapCase.unitCapped] marks the games whose
         * cap comes from the unit count and not from the cell count:
         * `loopy`, `guess`, and `untangle` report no cell grid at all,
         * and a 20x20 `blackbox` arena is exactly 400 cells plus 80
         * laser sites and the reveal button.
         */
        val aboveCapIds: Map<String, CapCase> = linkedMapOf(
            // 520 cells: one unit per cell, plus the interior borders.
            "palisade" to CapCase("26x20n10:" + "0".repeat(26 * 20)),
            // 420 cells and 164 clickable bands.
            "netslide" to CapCase("21x20b1:" + "0".repeat(21 * 20)),
            // 420 cells.
            "net" to CapCase("21x20:" + "0".repeat(21 * 20)),
            // 420 faces, two edges each.
            "loopy" to CapCase("21x20t0dh:" + "0".repeat(21 * 20), unitCapped = true),
            // 441 cells of trees and zero clues.
            "tents" to CapCase("21x21:" + "z".repeat(17) + "p" + ",0".repeat(42)),
            // 506 cells, one no-clue square each.
            "tracks" to CapCase(
                "23x22dh:" + "0".repeat(23 * 22) +
                    ",S0" + ",0".repeat(22) + ",S0" + ",0".repeat(21),
            ),
            // 420 cells.
            "singles" to CapCase("21x20de:" + "1".repeat(21 * 20)),
            // 40,200 cells: one start, one gem, and blanks.
            "inertia" to CapCase("201x200:" + "Sg" + "b".repeat(201 * 200 - 2)),
            // 420 squares of the cube solid.
            "cube" to CapCase("c21x20:" + "0".repeat((21 * 20 + 3) / 4) + ",0"),
            // 401 points on one chain. A drag is the only move, so no
            // unit offers an action.
            "untangle" to CapCase(
                "401:" + (0 until 400).joinToString(",") { "$it-${it + 1}" },
                unitCapped = true,
            ),
            // 400 arena cells, 80 laser sites, and the reveal button: 481.
            "blackbox" to CapCase("w20h20m3M3:99a9e14f420e017e", unitCapped = true),
            // 200 guess rows: 6 swatches, and 5 units per row.
            "guess" to CapCase("c6p4g100Bm:ac769e34", unitCapped = true),
            // 500 regions, 40 of them coloured.
            "map" to CapCase(
                "25x20n40:abiaccbafagaacmbabaabacfaababcaaaabcfaabababdhmafadfgbaaaasaaaacacl" +
                    "bcadagbdaidaddbcdcabdbbgaaeabbaabbhebaadarabaackbfaaaaaabkbfbqbaaabbcdgbeb" +
                    "czfapdfbiatbeagbcaaejctbaaaabakcaadajacacaccdbcbccfababaabdabdabbanaaceaca" +
                    "adcabbaaiadbaaaaaacaeadbcdcchbbeabbfcababcbadeaacakbaaaacbhaaaaagaibbaeaeaad" +
                    "cbcap,c1a123b3b23h13f31c1230",
            ),
            // 600 cells, ten mines. The description is the C layout.
            "mines" to CapCase(
                "30x20:r10,u,8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d" +
                    "4e8908980d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b8402",
            ),
        )

        /** One above-cap state: its frozen id, and why it is above the cap. */
        data class CapCase(val id: String, val unitCapped: Boolean = false)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> = aboveCapIds.keys.map { arrayOf(it) }
    }
}
