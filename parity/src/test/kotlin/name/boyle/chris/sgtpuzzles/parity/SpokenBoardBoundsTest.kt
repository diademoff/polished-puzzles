package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BOARD_UNIT_CAP
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.PortedGames
import name.boyle.chris.sgtpuzzles.engine.SpokenBoard
import name.boyle.chris.sgtpuzzles.engine.SpokenUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Games: every ported backend and every preset.
 *
 * The cross-game guarantees of D-01: a board either has the seam or is
 * empty, a unit rectangle lies inside the board, the grid is complete
 * and row-major, grid units do not overlap, the cap is honoured, and a
 * cell label never repeats its own coordinate.
 *
 * The state of each preset comes from the frozen seed-12345 ID in
 * `testdata/golden`, so the test builds no game and no description.
 */
class SpokenBoardBoundsTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    /**
     * The backends that return a spoken board after wave 3. A backend
     * outside this set must return the empty default.
     */
    private val covered = setOf(
        "fifteen",
        "sixteen",
        "flip",
        "twiddle",
        "mines",
        "mosaic",
        "signpost",
        "rect",
        "singles",
        "unruly",
        "tents",
        "samegame",
        "flood",
        "filling",
        "pegs",
        "lightup",
        "magnets",
        "range",
        "towers",
        "unequal",
        "keen",
        "undead",
        "dominosa",
    )

    @Test
    fun `every preset of every ported game reports a bounded board`() {
        var checked = 0
        for (game in PortedGames.all) {
            val entries = entries(game)
            assertTrue("${game.backendName} has no golden entry", entries.isNotEmpty())
            for ((params, id) in entries) {
                val where = "${game.backendName} '$params' seed-12345"
                val midend = Midend.fromGameId(game, id)
                val board = game.spokenBoard(midend.state, midend.ui, TILE)
                if (game.backendName !in covered) {
                    assertEquals(
                        "$where: a game without the hook must return the empty default",
                        SpokenBoard(0, 0, "", emptyList()),
                        board,
                    )
                    checked++
                    continue
                }

                val size = game.computeSize(midend.params, midend.ui, TILE)
                assertTrue("$where: the grid has no rows", board.rows > 0)
                assertTrue("$where: the grid has no columns", board.columns > 0)

                val cells = board.rows * board.columns
                for (unit in board.units) {
                    assertInside(where, unit, size.width, size.height)
                }

                if (cells > BOARD_UNIT_CAP) {
                    assertTrue(
                        "$where: a board above the cap exposes at most one unit, not " +
                            "${board.units.size}",
                        board.units.size <= 1,
                    )
                } else {
                    assertGrid(where, board)
                }
                checked++
            }
        }
        assertTrue("checked only $checked boards", checked >= PortedGames.all.size)
    }

    private fun assertGrid(where: String, board: SpokenBoard) {
        val cells = board.rows * board.columns
        assertTrue(
            "$where: ${board.units.size} units for a $cells cell grid",
            board.units.size >= cells,
        )

        val grid = board.units.take(cells)
        for ((index, unit) in grid.withIndex()) {
            val row = index / board.columns + 1
            val column = index % board.columns + 1
            assertEquals("$where: unit $index is not row-major", row, unit.row)
            assertEquals("$where: unit $index is not row-major", column, unit.column)
            assertFalse(
                "$where: the cell label repeats its coordinate: '${unit.text}'",
                unit.text.matches(Regex("""^Row \d+, column \d+, .*""")),
            )
        }

        for ((index, unit) in board.units.withIndex()) {
            if (index < cells) continue
            assertEquals("$where: an extra unit is in the grid", 0, unit.row)
            assertEquals("$where: an extra unit is in the grid", 0, unit.column)
        }

        for (i in 0 until cells) {
            for (j in i + 1 until cells) {
                assertFalse(
                    "$where: units $i and $j overlap",
                    overlaps(grid[i], grid[j]),
                )
            }
        }
    }

    private fun assertInside(where: String, unit: SpokenUnit, width: Int, height: Int) {
        assertTrue("$where: a unit has no width", unit.width > 0)
        assertTrue("$where: a unit has no height", unit.height > 0)
        assertTrue("$where: a unit starts left of the board", unit.left >= 0)
        assertTrue("$where: a unit starts above the board", unit.top >= 0)
        assertTrue(
            "$where: a unit ends outside the ${width}x$height board",
            unit.left + unit.width <= width && unit.top + unit.height <= height,
        )
    }

    private fun overlaps(a: SpokenUnit, b: SpokenUnit): Boolean =
        a.left < b.left + b.width && b.left < a.left + a.width &&
            a.top < b.top + b.height && b.top < a.top + a.height

    private fun entries(game: Game): List<Pair<String, String>> {
        val file = File(testdata, "golden/${game.backendName}/desc.json")
        val golden = Json.parse(file.readText()) as JsonValue.Obj
        return (golden.field("entries") as JsonValue.Arr).items.map {
            val entry = it as JsonValue.Obj
            entry.string("params") to entry.string("id")
        }
    }

    private companion object {
        const val TILE = 32
    }
}
