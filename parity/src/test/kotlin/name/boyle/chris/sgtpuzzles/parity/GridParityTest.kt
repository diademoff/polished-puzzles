package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Grid
import name.boyle.chris.sgtpuzzles.engine.GridType
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Level: strict. Differential test against upstream `grid.c`.
 *
 * Every generator, description string and random draw is compared with
 * the oracle through the `grid` operation. A difference here changes a
 * `loopy` or `pearl` game id, so the default is that the Kotlin port is
 * wrong.
 *
 * Small aperiodic patches can be empty. Upstream then fails an
 * assertion in `dsf_new(0)`, so the structural tests use sizes and seeds
 * that produce at least one face. The description test covers every
 * size, because it does not build the grid.
 */
class GridParityTest {

    private data class Case(
        val type: GridType,
        val w: Int,
        val h: Int,
        val seed: String = "12345",
    )

    private val descTypes = listOf(
        GridType.SQUARE,
        GridType.HONEYCOMB,
        GridType.TRIANGULAR,
        GridType.SNUBSQUARE,
        GridType.CAIRO,
        GridType.GREATHEXAGONAL,
        GridType.KAGOME,
        GridType.OCTAGONAL,
        GridType.KITES,
        GridType.FLORET,
        GridType.DODECAGONAL,
        GridType.GREATDODECAGONAL,
        GridType.GREATGREATDODECAGONAL,
        GridType.COMPASSDODECAGONAL,
        GridType.PENROSE_P2,
        GridType.PENROSE_P3,
        GridType.HATS,
        GridType.SPECTRES,
    )

    private val descSizes = listOf(1 to 1, 2 to 3, 4 to 4, 5 to 6)
    private val descSeeds = listOf("12345", "0", "seed-with-dashes")

    /** Sizes and seeds that upstream can build without an empty patch. */
    private val gridCases = listOf(
        Case(GridType.SQUARE, 1, 1),
        Case(GridType.SQUARE, 2, 3),
        Case(GridType.SQUARE, 5, 5),
        Case(GridType.SQUARE, 7, 4, "987654"),
        Case(GridType.HONEYCOMB, 1, 1),
        Case(GridType.HONEYCOMB, 2, 2),
        Case(GridType.HONEYCOMB, 4, 3),
        Case(GridType.HONEYCOMB, 5, 5, "987654"),
        Case(GridType.TRIANGULAR, 1, 1),
        Case(GridType.TRIANGULAR, 2, 3),
        Case(GridType.TRIANGULAR, 4, 4),
        Case(GridType.TRIANGULAR, 5, 6, "987654"),
        Case(GridType.SNUBSQUARE, 2, 2),
        Case(GridType.SNUBSQUARE, 4, 3),
        Case(GridType.SNUBSQUARE, 5, 4, "987654"),
        Case(GridType.CAIRO, 2, 2),
        Case(GridType.CAIRO, 3, 4),
        Case(GridType.CAIRO, 5, 3, "987654"),
        Case(GridType.GREATHEXAGONAL, 2, 2),
        Case(GridType.GREATHEXAGONAL, 3, 3),
        Case(GridType.GREATHEXAGONAL, 4, 5, "987654"),
        Case(GridType.KAGOME, 2, 2),
        Case(GridType.KAGOME, 3, 3),
        Case(GridType.KAGOME, 5, 4, "987654"),
        Case(GridType.OCTAGONAL, 2, 2),
        Case(GridType.OCTAGONAL, 3, 4),
        Case(GridType.OCTAGONAL, 4, 3, "987654"),
        Case(GridType.KITES, 2, 2),
        Case(GridType.KITES, 3, 3),
        Case(GridType.KITES, 4, 4, "987654"),
        Case(GridType.FLORET, 2, 2),
        Case(GridType.FLORET, 3, 3),
        Case(GridType.FLORET, 3, 2, "987654"),
        Case(GridType.DODECAGONAL, 2, 2),
        Case(GridType.DODECAGONAL, 3, 3),
        Case(GridType.DODECAGONAL, 4, 4, "987654"),
        Case(GridType.GREATDODECAGONAL, 2, 2),
        Case(GridType.GREATDODECAGONAL, 3, 3),
        Case(GridType.GREATDODECAGONAL, 4, 4, "987654"),
        Case(GridType.GREATGREATDODECAGONAL, 2, 2),
        Case(GridType.GREATGREATDODECAGONAL, 3, 3),
        Case(GridType.GREATGREATDODECAGONAL, 4, 4, "987654"),
        Case(GridType.COMPASSDODECAGONAL, 2, 2),
        Case(GridType.COMPASSDODECAGONAL, 3, 3),
        Case(GridType.COMPASSDODECAGONAL, 4, 4, "987654"),
        Case(GridType.PENROSE_P2, 4, 4),
        Case(GridType.PENROSE_P2, 5, 5, "424242"),
        Case(GridType.PENROSE_P2, 4, 5, "987654"),
        Case(GridType.PENROSE_P3, 5, 5),
        Case(GridType.PENROSE_P3, 4, 5, "424242"),
        Case(GridType.PENROSE_P3, 5, 5, "987654"),
        Case(GridType.HATS, 4, 4, "424242"),
        Case(GridType.HATS, 5, 5),
        Case(GridType.HATS, 5, 5, "987654"),
        Case(GridType.SPECTRES, 4, 4),
        Case(GridType.SPECTRES, 5, 5, "424242"),
        Case(GridType.SPECTRES, 4, 5, "987654"),
    )

    @Test
    fun `grid descriptions and generator state match upstream`() =
        OracleClient().use { oracle ->
            for (type in descTypes) {
                for ((w, h) in descSizes) {
                    for (seed in descSeeds) {
                        val expected = oracle.gridDesc(type.backend, w, h, seed = seed)
                        val random = RandomState.fromSeed(seed)
                        val actual = Grid.newDesc(type, w, h, random)
                        assertEquals(
                            "desc $type ${w}x$h seed=$seed",
                            expected.desc,
                            actual ?: "",
                        )
                        assertEquals(
                            "state $type ${w}x$h seed=$seed",
                            expected.state,
                            random.encodeState(),
                        )
                    }
                }
            }
        }

    @Test
    fun `grid descriptions round-trip through grid_new`() = OracleClient().use { oracle ->
        for (case in gridCases) {
            val random = RandomState.fromSeed(case.seed)
            val desc = Grid.newDesc(case.type, case.w, case.h, random)
            val expected = oracle.gridNew(case.type.backend, case.w, case.h, desc)
            val actual = Grid.new(case.type, case.w, case.h, desc)
            assertGridEquals(describe(case), expected, actual)

            if (desc != null) {
                assertEquals(
                    "validate ${case.type} '$desc'",
                    oracle.gridValidate(case.type.backend, case.w, case.h, desc),
                    Grid.validateDesc(case.type, case.w, case.h, desc),
                )
            }
        }
    }

    @Test
    fun `grid_new matches upstream for every type`() = OracleClient().use { oracle ->
        for (case in gridCases) {
            val random = RandomState.fromSeed(case.seed)
            val desc = Grid.newDesc(case.type, case.w, case.h, random)
            val expected = oracle.gridNew(case.type.backend, case.w, case.h, desc)
            val actual = Grid.new(case.type, case.w, case.h, desc)
            assertGridEquals(describe(case), expected, actual)
        }
    }

    @Test
    fun `grid_new matches upstream over a size sweep`() = OracleClient().use { oracle ->
        for (type in descTypes.filter {
            it != GridType.PENROSE_P2 &&
                it != GridType.PENROSE_P3 &&
                it != GridType.HATS &&
                it != GridType.SPECTRES
        }) {
            for (w in 1..6) {
                for (h in 1..6) {
                    val random = RandomState.fromSeed("12345")
                    val desc = Grid.newDesc(type, w, h, random)
                    val expected = oracle.gridNew(type.backend, w, h, desc)
                    val actual = Grid.new(type, w, h, desc)
                    assertGridEquals("$type ${w}x$h", expected, actual)
                }
            }
        }
    }

    @Test
    fun `larger grids match upstream`() = OracleClient().use { oracle ->
        val cases = listOf(
            Case(GridType.SQUARE, 20, 20),
            Case(GridType.HONEYCOMB, 15, 15),
            Case(GridType.TRIANGULAR, 15, 15),
            Case(GridType.SNUBSQUARE, 10, 10),
            Case(GridType.CAIRO, 8, 8),
            Case(GridType.GREATHEXAGONAL, 6, 6),
            Case(GridType.KAGOME, 8, 8),
            Case(GridType.OCTAGONAL, 6, 6),
            Case(GridType.KITES, 5, 5),
            Case(GridType.FLORET, 4, 4),
            Case(GridType.DODECAGONAL, 5, 5),
            Case(GridType.GREATDODECAGONAL, 4, 4),
            Case(GridType.GREATGREATDODECAGONAL, 4, 4),
            Case(GridType.COMPASSDODECAGONAL, 4, 4),
            Case(GridType.PENROSE_P2, 8, 8),
            Case(GridType.PENROSE_P3, 8, 8),
            Case(GridType.HATS, 5, 5),
            Case(GridType.SPECTRES, 5, 5),
        )
        for (case in cases) {
            val random = RandomState.fromSeed(case.seed)
            val desc = Grid.newDesc(case.type, case.w, case.h, random)
            val expected = oracle.gridNew(case.type.backend, case.w, case.h, desc)
            val actual = Grid.new(case.type, case.w, case.h, desc)
            assertGridEquals("large ${describe(case)}", expected, actual)
        }
    }

    @Test
    fun `old-style triangular descriptions still match`() = OracleClient().use { oracle ->
        for (size in listOf(1 to 1, 2 to 2, 5 to 5, 5 to 6)) {
            for (desc in listOf(null, "0")) {
                val expected = oracle.gridNew(
                    GridType.TRIANGULAR.backend,
                    size.first,
                    size.second,
                    desc,
                )
                val actual = Grid.new(GridType.TRIANGULAR, size.first, size.second, desc)
                assertGridEquals(
                    "triangular ${size.first}x${size.second} desc=$desc",
                    expected,
                    actual,
                )
            }
        }
    }

    @Test
    fun `grid description validation messages match`() = OracleClient().use { oracle ->
        // An empty Penrose or spectre description reads uninitialised
        // memory upstream (`params.coords` is freed without being set),
        // so the oracle aborts. Those two inputs are covered by the
        // engine's own validator test instead.
        val invalid = mapOf(
            GridType.TRIANGULAR to listOf("", "00", "1", "x"),
            GridType.PENROSE_P2 to listOf("80", "81", "81A", "81AQ", "Q9AB"),
            GridType.PENROSE_P3 to listOf("80", "81", "81C", "81CX", "81CC"),
            GridType.HATS to listOf("", "1,", "1,2", "1,2,H", "123,1,1,H", "1,2,Z", "1,2,3"),
            GridType.SPECTRES to listOf("0X", "31G", "39G", "C0G"),
            GridType.SQUARE to listOf("", "1"),
        )
        for ((type, descriptions) in invalid) {
            for (desc in descriptions) {
                assertEquals(
                    "validate $type '$desc'",
                    oracle.gridValidate(type.backend, 4, 4, desc),
                    Grid.validateDesc(type, 4, 4, desc),
                )
            }
        }
    }

    @Test
    fun `legacy penrose descriptions match upstream`() = OracleClient().use { oracle ->
        val descriptions = listOf(
            "G0,0,0",
            "G10,-20,36",
            "G-30,40,72",
            "G100,100,0",
            "G2,3,36",
            "G-100,50,324",
        )
        for (type in listOf(GridType.PENROSE_P2, GridType.PENROSE_P3)) {
            for (size in listOf(5 to 5, 8 to 8, 10 to 6)) {
                for (desc in descriptions) {
                    assertEquals(
                        "legacy validate $type ${size.first}x${size.second} '$desc'",
                        oracle.gridValidate(type.backend, size.first, size.second, desc),
                        Grid.validateDesc(type, size.first, size.second, desc),
                    )
                    if (Grid.validateDesc(type, size.first, size.second, desc) != null) continue
                    val expected = oracle.gridNew(
                        type.backend,
                        size.first,
                        size.second,
                        desc,
                    )
                    val actual = Grid.new(type, size.first, size.second, desc)
                    assertGridEquals(
                        "legacy $type ${size.first}x${size.second} '$desc'",
                        expected,
                        actual,
                    )
                }
            }
        }

        for (desc in listOf("Gx", "G1,2", "G0,0,37", "G0,0,-36", "G999999,0,0")) {
            assertEquals(
                "legacy invalid '$desc'",
                oracle.gridValidate(GridType.PENROSE_P2.backend, 5, 5, desc),
                Grid.validateDesc(GridType.PENROSE_P2, 5, 5, desc),
            )
        }
    }

    @Test
    fun `grid compute size matches upstream`() = OracleClient().use { oracle ->
        for (type in descTypes) {
            for ((w, h) in descSizes) {
                val expected = oracle.gridSize(type.backend, w, h)
                val actual = Grid.computeSize(type, w, h)
                assertEquals("tilesize $type ${w}x$h", expected.tileSize, actual.tileSize)
                assertEquals("xextent $type ${w}x$h", expected.xextent, actual.xextent)
                assertEquals("yextent $type ${w}x$h", expected.yextent, actual.yextent)
            }
        }
    }

    @Test
    fun `nearest edge matches upstream at every dot and outside point`() =
        OracleClient().use { oracle ->
            for (case in gridCases) {
                val random = RandomState.fromSeed(case.seed)
                val desc = Grid.newDesc(case.type, case.w, case.h, random)
                val grid = Grid.new(case.type, case.w, case.h, desc)
                val points = grid.dots.map { it.x to it.y } +
                    listOf(
                        grid.lowestX - 100 to grid.lowestY - 100,
                        grid.highestX + 100 to grid.highestY + 100,
                        grid.lowestX to grid.highestY,
                        grid.highestX to grid.lowestY,
                    )
                for ((x, y) in points) {
                    assertEquals(
                        "nearest ${describe(case)} at $x,$y",
                        oracle.gridNearest(case.type.backend, case.w, case.h, x, y, desc),
                        Grid.nearestEdge(grid, x, y)?.index ?: -1,
                    )
                }
            }
        }

    @Test
    fun `find incentre matches upstream`() = OracleClient().use { oracle ->
        for (case in gridCases) {
            val random = RandomState.fromSeed(case.seed)
            val desc = Grid.newDesc(case.type, case.w, case.h, random)
            val grid = Grid.new(case.type, case.w, case.h, desc)
            grid.faces.forEach { Grid.findIncentre(it) }
            val expected = oracle.gridNew(
                case.type.backend,
                case.w,
                case.h,
                desc,
                incentre = true,
            )
            assertEquals("face count ${describe(case)}", expected.faces.size, grid.faces.size)
            for (i in grid.faces.indices) {
                val face = grid.faces[i]
                assertNotNull("incentre ${describe(case)} face $i", face.incentre)
                assertEquals(
                    "incentre x ${describe(case)} face $i",
                    expected.faces[i].ix,
                    face.incentre!!.x,
                )
                assertEquals(
                    "incentre y ${describe(case)} face $i",
                    expected.faces[i].iy,
                    face.incentre!!.y,
                )
            }
        }
    }

    private fun describe(case: Case): String =
        "${case.type} ${case.w}x${case.h} seed=${case.seed}"

    private fun assertGridEquals(label: String, expected: OracleClient.GridData, actual: Grid) {
        assertEquals("$label lowestX", expected.lowestX, actual.lowestX)
        assertEquals("$label lowestY", expected.lowestY, actual.lowestY)
        assertEquals("$label highestX", expected.highestX, actual.highestX)
        assertEquals("$label highestY", expected.highestY, actual.highestY)
        assertEquals("$label tilesize", expected.tileSize, actual.tileSize)
        assertEquals("$label dots", expected.dots.size, actual.dots.size)
        assertEquals("$label edges", expected.edges.size, actual.edges.size)
        assertEquals("$label faces", expected.faces.size, actual.faces.size)

        for (i in actual.dots.indices) {
            val e = expected.dots[i]
            val a = actual.dots[i]
            assertEquals("$label dot $i index", e.index, a.index)
            assertEquals("$label dot $i x", e.x, a.x)
            assertEquals("$label dot $i y", e.y, a.y)
            assertEquals(
                "$label dot $i edges",
                e.edges,
                a.edges.map { it?.index ?: -1 },
            )
            assertEquals(
                "$label dot $i faces",
                e.faces,
                a.faces.map { it?.index ?: -1 },
            )
        }
        for (i in actual.edges.indices) {
            val e = expected.edges[i]
            val a = actual.edges[i]
            assertEquals("$label edge $i index", e.index, a.index)
            assertEquals("$label edge $i dot1", e.dot1, a.dot1.index)
            assertEquals("$label edge $i dot2", e.dot2, a.dot2.index)
            assertEquals("$label edge $i face1", e.face1, a.face1?.index ?: -1)
            assertEquals("$label edge $i face2", e.face2, a.face2?.index ?: -1)
        }
        for (i in actual.faces.indices) {
            val e = expected.faces[i]
            val a = actual.faces[i]
            assertEquals("$label face $i index", e.index, a.index)
            assertEquals("$label face $i order", e.order, a.order)
            assertEquals("$label face $i edges", e.edges, a.edges.map { it?.index ?: -1 })
            assertEquals("$label face $i dots", e.dots, a.dots.map { it?.index ?: -1 })
            assertEquals("$label face $i hasIncentre", e.hasIncentre, a.hasIncentre)
            if (e.hasIncentre) {
                assertEquals("$label face $i incentre x", e.ix, a.incentre?.x)
                assertEquals("$label face $i incentre y", e.iy, a.incentre?.y)
            }
        }
    }
}
