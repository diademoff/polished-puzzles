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
     * The backends that return a spoken board after wave 4: every ported
     * game except the two that still run on the C engine. A backend
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
        "loopy",
        "slant",
        "pearl",
        "tracks",
        "bridges",
        "palisade",
        "guess",
        "map",
        "blackbox",
        "untangle",
        "net",
        "netslide",
        "inertia",
        "galaxies",
        "pattern",
        // Wave 5: the last two backends.
        "solo",
        "cube",
    )

    /**
     * The backends whose cell grid is not a rectangle at every preset.
     * `cube` lays the cube solid out as a rectangle and the other three
     * solids as rows of triangles, so a preset may report no grid at
     * all; a unit then names its row and triangle in its text.
     */
    private val shapedCells = setOf("cube")

    /**
     * The backends whose units are not one per cell, so their unit count
     * can pass the cap while the cell grid is still under it: the edges
     * of `loopy`, the points of `untangle`, the palette and pegs of
     * `guess`, the borders of `palisade`, the laser ring of `blackbox`,
     * and the moving bands of `netslide`. Such a preset returns the
     * cursor unit alone, which is the cap contract.
     */
    private val cappedByUnits = setOf(
        "loopy",
        "guess",
        "untangle",
        "palisade",
        "blackbox",
        "netslide",
    )

    /**
     * The backends with no cell grid at all. Their board has no row or
     * column, so every unit is outside the grid.
     */
    private val noGrid = setOf("loopy", "guess", "untangle")

    @Test
    fun `every preset of every ported game reports a bounded board`() {
        var checked = 0
        var shapedGridSeen = false
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
                for (unit in board.units) {
                    assertInside(where, unit, size.width, size.height)
                }

                // A game with no cell grid, and a shaped game at a preset
                // whose cells are not a rectangle, report no grid at all.
                val shapeless = game.backendName in noGrid ||
                    (game.backendName in shapedCells &&
                        (board.rows == 0 || board.columns == 0))
                if (shapeless) {
                    assertTrue("$where: a board without a cell grid has rows", board.rows == 0)
                    assertTrue("$where: a board without a cell grid has columns", board.columns == 0)
                    for (unit in board.units) {
                        assertEquals("$where: a unit of a board without a grid has a row", 0, unit.row)
                        assertEquals(
                            "$where: a unit of a board without a grid has a column",
                            0,
                            unit.column,
                        )
                    }
                    assertTrue(
                        "$where: ${board.units.size} units is above the cap",
                        board.units.size <= BOARD_UNIT_CAP,
                    )
                    checked++
                    continue
                }
                if (game.backendName in shapedCells) shapedGridSeen = true

                assertTrue("$where: the grid has no rows", board.rows > 0)
                assertTrue("$where: the grid has no columns", board.columns > 0)

                val cells = board.rows * board.columns
                if (cells > BOARD_UNIT_CAP) {
                    assertTrue(
                        "$where: a board above the cap exposes at most one unit, not " +
                            "${board.units.size}",
                        board.units.size <= 1,
                    )
                } else if (board.units.size <= 1 && cells > 1) {
                    assertTrue(
                        "$where: only a game whose units are not cells may return the " +
                            "cursor unit alone under the cell cap",
                        game.backendName in cappedByUnits,
                    )
                } else {
                    assertGrid(where, board)
                }
                checked++
            }
        }
        assertTrue("checked only $checked boards", checked >= PortedGames.all.size)
        assertTrue("no shaped board reported its rectangular preset", shapedGridSeen)
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
