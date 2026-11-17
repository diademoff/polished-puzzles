package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic.
 *
 * Fixed vectors recorded from upstream `grid.c` through the oracle, plus
 * the planarity invariants every grid must satisfy. The exhaustive
 * differential comparison lives in `:parity`; this file keeps the port
 * honest without the oracle.
 *
 * A vector pins the dot coordinates, the edge incidence, the face
 * incidence, the bounding box, and the tile size. Changing any of them
 * changes a `loopy` or `pearl` game id.
 */
class GridTest {

    private data class GridCase(
        val type: GridType,
        val w: Int,
        val h: Int,
        val seed: String = "12345",
    )

    private val cases = listOf(
        GridCase(GridType.SQUARE, 2, 2),
        GridCase(GridType.TRIANGULAR, 2, 2),
        GridCase(GridType.HONEYCOMB, 2, 2),
        GridCase(GridType.SNUBSQUARE, 2, 2),
        GridCase(GridType.CAIRO, 2, 2),
        GridCase(GridType.GREATHEXAGONAL, 2, 2),
        GridCase(GridType.KAGOME, 2, 2),
        GridCase(GridType.OCTAGONAL, 2, 2),
        GridCase(GridType.KITES, 2, 2),
        GridCase(GridType.FLORET, 2, 2),
        GridCase(GridType.DODECAGONAL, 2, 2),
        GridCase(GridType.GREATDODECAGONAL, 2, 2),
        GridCase(GridType.GREATGREATDODECAGONAL, 2, 2),
        GridCase(GridType.COMPASSDODECAGONAL, 2, 2),
        GridCase(GridType.PENROSE_P2, 4, 4),
        GridCase(GridType.PENROSE_P3, 5, 5),
        GridCase(GridType.HATS, 4, 4, "424242"),
        GridCase(GridType.SPECTRES, 4, 4),
    )

    private fun build(case: GridCase): Grid {
        val desc = Grid.newDesc(
            case.type,
            case.w,
            case.h,
            RandomState.fromSeed(case.seed),
        )
        return Grid.new(case.type, case.w, case.h, desc)
    }

    @Test
    fun everyGridTypeHasABackendName() {
        assertEquals(18, GridType.entries.size, "types")
        assertEquals(18, GridType.entries.map { it.backend }.toSet().size, "unique names")
        assertEquals("square", GridType.SQUARE.backend, "square")
        assertEquals("kites", GridType.KITES.backend, "kites")
        assertEquals("penrose_p2_kite", GridType.PENROSE_P2.backend, "p2")
        assertEquals("penrose_p3_thick", GridType.PENROSE_P3.backend, "p3")
        assertEquals("spectres", GridType.SPECTRES.backend, "spectres")
        assertEquals(
            GridType.COMPASSDODECAGONAL,
            GridType.fromBackend("compassdodecagonal"),
            "lookup",
        )
        assertNull(GridType.fromBackend("nonexistent"), "unknown lookup")
    }

    @Test
    fun squareMatchesTheRecordedVector() {
        val grid = build(GridCase(GridType.SQUARE, 2, 2))
        assertEquals(20, grid.tileSize, "tilesize")
        assertEquals(0, grid.lowestX, "lowestX")
        assertEquals(0, grid.lowestY, "lowestY")
        assertEquals(40, grid.highestX, "highestX")
        assertEquals(40, grid.highestY, "highestY")

        assertEquals(
            listOf(
                0 to 0, 20 to 0, 20 to 20, 0 to 20, 40 to 0,
                40 to 20, 20 to 40, 0 to 40, 40 to 40,
            ),
            grid.dots.map { it.x to it.y },
            "dots",
        )
        assertEquals(
            listOf(
                listOf(0, 1, 0, -1),
                listOf(1, 2, 0, 1),
                listOf(2, 3, 0, 2),
                listOf(3, 0, 0, -1),
                listOf(1, 4, 1, -1),
                listOf(4, 5, 1, -1),
                listOf(5, 2, 1, 3),
                listOf(2, 6, 2, 3),
                listOf(6, 7, 2, -1),
                listOf(7, 3, 2, -1),
                listOf(5, 8, 3, -1),
                listOf(8, 6, 3, -1),
            ),
            grid.edges.map {
                listOf(
                    it.dot1.index,
                    it.dot2.index,
                    it.face1?.index ?: -1,
                    it.face2?.index ?: -1,
                )
            },
            "edges",
        )
        assertEquals(
            listOf(
                listOf(0, 1, 2, 3),
                listOf(1, 4, 5, 2),
                listOf(3, 2, 6, 7),
                listOf(2, 5, 8, 6),
            ),
            grid.faces.map { face -> face.dots.map { it!!.index } },
            "faces",
        )
    }

    @Test
    fun triangularMatchesTheRecordedVector() {
        val grid = Grid.new(GridType.TRIANGULAR, 2, 2, "0")
        assertEquals(18, grid.tileSize, "tilesize")
        assertEquals(0, grid.lowestX, "lowestX")
        assertEquals(0, grid.lowestY, "lowestY")
        assertEquals(90, grid.highestX, "highestX")
        assertEquals(52, grid.highestY, "highestY")
        assertEquals(10, grid.dots.size, "dots")
        assertEquals(19, grid.edges.size, "edges")
        assertEquals(10, grid.faces.size, "faces")
        assertEquals(
            listOf(
                0 to 26, 15 to 0, 30 to 26, 45 to 0, 60 to 26,
                75 to 0, 90 to 26, 15 to 52, 45 to 52, 75 to 52,
            ),
            grid.dots.map { it.x to it.y },
            "dot coordinates",
        )
        assertEquals(
            listOf(
                listOf(0, 1, 0, -1),
                listOf(1, 2, 0, 3),
                listOf(2, 0, 0, 5),
                listOf(2, 3, 1, 3),
                listOf(3, 4, 1, 4),
                listOf(4, 2, 1, 6),
                listOf(4, 5, 2, 4),
                listOf(5, 6, 2, -1),
                listOf(6, 4, 2, 7),
                listOf(1, 3, 3, -1),
                listOf(3, 5, 4, -1),
                listOf(2, 7, 5, 8),
                listOf(7, 0, 5, -1),
                listOf(4, 8, 6, 9),
                listOf(8, 2, 6, 8),
                listOf(6, 9, 7, -1),
                listOf(9, 4, 7, 9),
                listOf(8, 7, 8, -1),
                listOf(9, 8, 9, -1),
            ),
            grid.edges.map {
                listOf(
                    it.dot1.index,
                    it.dot2.index,
                    it.face1?.index ?: -1,
                    it.face2?.index ?: -1,
                )
            },
            "edges",
        )
        assertEquals(
            listOf(
                listOf(0, 1, 2),
                listOf(2, 3, 4),
                listOf(4, 5, 6),
                listOf(1, 3, 2),
                listOf(3, 5, 4),
                listOf(0, 2, 7),
                listOf(2, 4, 8),
                listOf(4, 6, 9),
                listOf(7, 2, 8),
                listOf(8, 4, 9),
            ),
            grid.faces.map { face -> face.dots.map { it!!.index } },
            "faces",
        )
    }

    @Test
    fun composedGridsMatchRecordedVectors() {
        val vectors = mapOf(
            GridType.HONEYCOMB to intArrayOf(16, 19, 4, -30, -26, 75, 104, 45),
            GridType.SNUBSQUARE to intArrayOf(12, 19, 8, 0, 0, 82, 82, 18),
            GridType.CAIRO to intArrayOf(13, 16, 4, 0, 0, 124, 124, 40),
            GridType.GREATHEXAGONAL to intArrayOf(24, 34, 11, -30, -26, 101, 149, 18),
            GridType.KAGOME to intArrayOf(19, 28, 10, -30, -26, 120, 78, 18),
            GridType.OCTAGONAL to intArrayOf(24, 28, 5, 0, 0, 198, 198, 40),
            GridType.KITES to intArrayOf(39, 62, 24, -52, -60, 208, 150, 40),
            GridType.FLORET to intArrayOf(46, 63, 18, -210, -390, 525, 390, 150),
            GridType.DODECAGONAL to intArrayOf(38, 43, 6, -56, -56, 224, 153, 26),
            GridType.GREATDODECAGONAL to intArrayOf(48, 58, 11, -56, -56, 269, 179, 26),
            GridType.GREATGREATDODECAGONAL to
                intArrayOf(58, 74, 17, -56, -56, 302, 198, 26),
            GridType.COMPASSDODECAGONAL to intArrayOf(40, 48, 9, -56, -56, 168, 168, 26),
        )
        for ((type, vector) in vectors) {
            val grid = build(GridCase(type, 2, 2))
            assertEquals(vector[0], grid.dots.size, "$type dots")
            assertEquals(vector[1], grid.edges.size, "$type edges")
            assertEquals(vector[2], grid.faces.size, "$type faces")
            assertEquals(vector[3], grid.lowestX, "$type lowestX")
            assertEquals(vector[4], grid.lowestY, "$type lowestY")
            assertEquals(vector[5], grid.highestX, "$type highestX")
            assertEquals(vector[6], grid.highestY, "$type highestY")
            assertEquals(vector[7], grid.tileSize, "$type tilesize")
        }
    }

    private data class AperiodicVector(
        val desc: String,
        val dots: Int,
        val edges: Int,
        val faces: Int,
        val lowestX: Int,
        val lowestY: Int,
        val highestX: Int,
        val highestY: Int,
        val tileSize: Int,
    )

    @Test
    fun aperiodicGridsMatchRecordedVectors() {
        val expected = mapOf(
            GridType.PENROSE_P2 to AperiodicVector(
                "81BABAVVBB", 9, 12, 4, -7, -37, 393, 363, 100,
            ),
            GridType.PENROSE_P3 to AperiodicVector(
                "81XXYCXYC", 7, 9, 3, 30, -21, 530, 479, 100,
            ),
            GridType.HATS to AperiodicVector(
                "6,0,1,12,1,P", 57, 62, 6, 0, 0, 210, 176, 32,
            ),
            GridType.SPECTRES to AperiodicVector(
                "31200F", 39, 42, 4, 13, -29, 237, 195, 32,
            ),
        )
        for (case in cases.filter { it.type in expected }) {
            val random = RandomState.fromSeed(case.seed)
            val desc = Grid.newDesc(case.type, case.w, case.h, random)
            val vector = expected.getValue(case.type)
            assertEquals(vector.desc, desc, "${case.type} desc")
            val grid = Grid.new(case.type, case.w, case.h, desc)
            assertEquals(vector.dots, grid.dots.size, "${case.type} dots")
            assertEquals(vector.edges, grid.edges.size, "${case.type} edges")
            assertEquals(vector.faces, grid.faces.size, "${case.type} faces")
            assertEquals(vector.lowestX, grid.lowestX, "${case.type} lowestX")
            assertEquals(vector.lowestY, grid.lowestY, "${case.type} lowestY")
            assertEquals(vector.highestX, grid.highestX, "${case.type} highestX")
            assertEquals(vector.highestY, grid.highestY, "${case.type} highestY")
            assertEquals(vector.tileSize, grid.tileSize, "${case.type} tilesize")
        }
    }

    @Test
    fun everyGridSatisfiesThePlanarityInvariants() {
        for (case in cases) {
            checkInvariants("${case.type} ${case.w}x${case.h}", build(case))
        }

        // Empty patches are legal at small sizes: upstream produces them
        // too, and they have no incidence to check.
        val empty = Grid.new(GridType.CAIRO, 1, 1, null)
        assertEquals(0, empty.faces.size, "empty faces")
        assertEquals(0, empty.edges.size, "empty edges")
        assertEquals(0, empty.dots.size, "empty dots")
    }

    private fun checkInvariants(label: String, grid: Grid) {
        assertEquals(grid.dots.indices.toList(), grid.dots.map { it.index }, "$label dot indices")
        assertEquals(grid.edges.indices.toList(), grid.edges.map { it.index }, "$label edge indices")
        assertEquals(grid.faces.indices.toList(), grid.faces.map { it.index }, "$label face indices")

        if (grid.dots.isNotEmpty()) {
            val recentred = grid.type == GridType.PENROSE_P2 ||
                grid.type == GridType.PENROSE_P3 ||
                grid.type == GridType.SPECTRES
            if (recentred) {
                // Penrose and spectre patches are placed in the rectangle
                // promised by the width and height, so the bounding box can
                // extend past the outermost dot.
                assertTrue(grid.lowestX <= grid.dots.minOf { it.x }, "$label lowestX")
                assertTrue(grid.lowestY <= grid.dots.minOf { it.y }, "$label lowestY")
                assertTrue(grid.highestX >= grid.dots.maxOf { it.x }, "$label highestX")
                assertTrue(grid.highestY >= grid.dots.maxOf { it.y }, "$label highestY")
            } else {
                assertEquals(grid.dots.minOf { it.x }, grid.lowestX, "$label lowestX")
                assertEquals(grid.dots.minOf { it.y }, grid.lowestY, "$label lowestY")
                assertEquals(grid.dots.maxOf { it.x }, grid.highestX, "$label highestX")
                assertEquals(grid.dots.maxOf { it.y }, grid.highestY, "$label highestY")
            }
        }

        for (edge in grid.edges) {
            assertEquals(grid.dots[edge.dot1.index], edge.dot1, "$label edge ${edge.index} dot1")
            assertEquals(grid.dots[edge.dot2.index], edge.dot2, "$label edge ${edge.index} dot2")
            assertTrue(edge.dot1 !== edge.dot2, "$label edge ${edge.index} joins one dot twice")
            edge.face1?.let {
                assertEquals(grid.faces[it.index], it, "$label edge ${edge.index} face1")
            }
            edge.face2?.let {
                assertEquals(grid.faces[it.index], it, "$label edge ${edge.index} face2")
            }
            if (edge.face1 != null && edge.face2 != null) {
                assertTrue(
                    edge.face1 !== edge.face2,
                    "$label edge ${edge.index} has the same face twice",
                )
            }

            assertEquals(
                1,
                edge.dot1.edges.count { it === edge },
                "$label edge ${edge.index} in dot1",
            )
            assertEquals(
                1,
                edge.dot2.edges.count { it === edge },
                "$label edge ${edge.index} in dot2",
            )
        }

        for (face in grid.faces) {
            assertEquals(face.order, face.dots.size, "$label face ${face.index} dots")
            assertEquals(face.order, face.edges.size, "$label face ${face.index} edges")
            assertTrue(face.order >= 3, "$label face ${face.index} order ${face.order}")
            for (i in 0 until face.order) {
                val from = face.dots[i]
                val to = face.dots[(i + 1) % face.order]
                val edge = face.edges[i]
                assertNotNull(from, "$label face ${face.index} dot $i")
                assertNotNull(to, "$label face ${face.index} dot ${(i + 1) % face.order}")
                assertNotNull(edge, "$label face ${face.index} edge $i")
                assertEquals(grid.dots[from.index], from, "$label face ${face.index} dot $i")
                assertEquals(grid.edges[edge.index], edge, "$label face ${face.index} edge $i")
                assertTrue(
                    (edge.dot1 === from && edge.dot2 === to) ||
                        (edge.dot1 === to && edge.dot2 === from),
                    "$label face ${face.index} edge $i joins its dots",
                )
            }
        }

        for (dot in grid.dots) {
            assertEquals(dot.order, dot.edges.size, "$label dot ${dot.index} edges")
            assertEquals(dot.order, dot.faces.size, "$label dot ${dot.index} faces")
            for (edge in dot.edges) {
                assertNotNull(edge, "$label dot ${dot.index} edge")
                assertTrue(
                    edge.dot1 === dot || edge.dot2 === dot,
                    "$label dot ${dot.index} owns a foreign edge",
                )
                assertEquals(
                    1,
                    dot.edges.count { it === edge },
                    "$label dot ${dot.index} edge once",
                )
            }
            for (face in dot.faces) {
                if (face != null) {
                    assertTrue(
                        (0 until face.order).any { face.dots[it] === dot },
                        "$label dot ${dot.index} lists a face that does not contain it",
                    )
                    assertEquals(
                        1,
                        dot.faces.count { it === face },
                        "$label dot ${dot.index} face once",
                    )
                }
            }

            // Around a dot the lists alternate: face i, edge i, face i+1,
            // edge i+1. A null face is the infinite outside face.
            for (i in 0 until dot.order) {
                val edge = dot.edges[i]!!
                for (position in intArrayOf(i, (i + 1) % dot.order)) {
                    val face = dot.faces[position] ?: continue
                    assertTrue(
                        (0 until face.order).any { face.edges[it] === edge },
                        "$label dot ${dot.index} edge $i is not on face ${face.index}",
                    )
                }
            }
        }
    }

    @Test
    fun nearestEdgeFindsTheExpectedEdge() {
        val grid = Grid.new(GridType.SQUARE, 5, 5, null)
        assertEquals(0, Grid.nearestEdge(grid, 10, 5)?.index, "top left edge")
        assertEquals(14, Grid.nearestEdge(grid, 95, 10)?.index, "top right edge")
        assertEquals(50, Grid.nearestEdge(grid, 5, 95)?.index, "bottom left edge")
        assertNull(Grid.nearestEdge(grid, -1000, -1000), "far outside")
        assertNull(Grid.nearestEdge(grid, 1000, 1000), "far outside")
    }

    @Test
    fun findIncentreFindsTheSquareCentres() {
        val grid = Grid.new(GridType.SQUARE, 2, 2, null)
        grid.faces.forEach { Grid.findIncentre(it) }
        assertEquals(
            listOf(
                GridPoint(10, 10),
                GridPoint(30, 10),
                GridPoint(10, 30),
                GridPoint(30, 30),
            ),
            grid.faces.map { it.incentre },
            "incentres",
        )
        assertTrue(grid.faces.all { it.hasIncentre }, "all computed")

        // The second call is a no-op and keeps the recorded value.
        val first = grid.faces[0].incentre
        Grid.findIncentre(grid.faces[0])
        assertEquals(first, grid.faces[0].incentre, "cached")
    }

    @Test
    fun computeSizeMatchesTheRecordedVectors() {
        assertEquals(GridSize(20, 100, 100), Grid.computeSize(GridType.SQUARE, 5, 5), "square")
        assertEquals(
            GridSize(45, 240, 286),
            Grid.computeSize(GridType.HONEYCOMB, 5, 5),
            "honeycomb",
        )
        assertEquals(
            GridSize(18, 180, 130),
            Grid.computeSize(GridType.TRIANGULAR, 5, 5),
            "triangular",
        )
        assertEquals(
            GridSize(100, 500, 500),
            Grid.computeSize(GridType.PENROSE_P2, 5, 5),
            "p2",
        )
        assertEquals(
            GridSize(100, 500, 500),
            Grid.computeSize(GridType.PENROSE_P3, 5, 5),
            "p3",
        )
        assertEquals(GridSize(32, 280, 240), Grid.computeSize(GridType.HATS, 5, 5), "hats")
        assertEquals(GridSize(32, 280, 280), Grid.computeSize(GridType.SPECTRES, 5, 5), "spectres")
    }

    @Test
    fun validateParamsAndDescReportTheSameMessages() {
        assertEquals(
            "Width and height must both be positive",
            Grid.validateParams(GridType.SQUARE, 0, 5),
            "non-positive size",
        )
        assertNull(Grid.validateParams(GridType.SQUARE, 5, 5), "valid size")

        assertEquals(
            "Grid description strings not used with this grid type",
            Grid.validateDesc(GridType.SQUARE, 5, 5, "anything"),
            "unused description",
        )
        assertNull(Grid.validateDesc(GridType.SQUARE, 5, 5, null), "absent description")

        assertNull(Grid.validateDesc(GridType.TRIANGULAR, 5, 5, null), "old triangular")
        assertNull(Grid.validateDesc(GridType.TRIANGULAR, 5, 5, "0"), "new triangular")
        assertEquals(
            "Unrecognised grid description.",
            Grid.validateDesc(GridType.TRIANGULAR, 5, 5, "1"),
            "bad triangular",
        )

        assertEquals(
            "Missing grid description string.",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, null),
            "missing penrose",
        )
        assertEquals(
            "empty grid description",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, ""),
            "empty penrose",
        )
        assertEquals(
            "expected digit at start of grid description",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, "Q9AB"),
            "bad orientation",
        )
        assertEquals(
            "expected digit as second char of grid description",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, "89AB"),
            "bad start vertex",
        )
        assertEquals(
            "expected tile letter in grid description",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, "81ZZ"),
            "bad letter",
        )
        assertEquals(
            "expected at least one coordinate",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, "81"),
            "no coordinates",
        )
        assertEquals(
            "invalid pair of consecutive coordinates",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, "81VA"),
            "bad coordinate pair",
        )
        assertEquals(
            "invalid coordinate letter",
            PenroseTiling.paramsInvalid(
                PenroseTiling.PatchParams(8, 1, charArrayOf('C', 'Z')),
                PenroseTiling.P2,
            ),
            "invalid coordinate letter",
        )

        assertEquals(
            "expected digit or A,B at start of grid description",
            Grid.validateDesc(GridType.SPECTRES, 5, 5, "C0G"),
            "bad spectre orientation",
        )
        assertEquals(
            "expected at least one numeric coordinate",
            Grid.validateDesc(GridType.SPECTRES, 5, 5, "0X"),
            "no spectre coordinates",
        )
        assertEquals(
            "coordinate out of range",
            Grid.validateDesc(GridType.SPECTRES, 5, 5, "39G"),
            "spectre range",
        )

        assertEquals(
            "expected ',' in grid description",
            Grid.validateDesc(GridType.HATS, 5, 5, "1,2"),
            "hat comma",
        )
        assertEquals(
            "invalid character in grid description",
            Grid.validateDesc(GridType.HATS, 5, 5, "1,2,Z"),
            "hat final",
        )
        assertEquals(
            "Grid parameters require at least three coordinates",
            Grid.validateDesc(GridType.HATS, 5, 5, "1,2,H"),
            "hat count",
        )
        assertEquals(
            "Grid parameters contain an invalid final metatile",
            HatTiling.paramsInvalid(HatTiling.PatchParams(intArrayOf(1, 2, 3), 'Q')),
            "hat metatile letter",
        )
        assertEquals(
            "Grid parameters contain an invalid kite index",
            HatTiling.paramsInvalid(HatTiling.PatchParams(intArrayOf(9, 0, 0), 'H')),
            "hat kite",
        )
        assertEquals(
            "Grid parameters contain an invalid metatile index",
            HatTiling.paramsInvalid(HatTiling.PatchParams(intArrayOf(0, 0, 7), 'T')),
            "hat meta",
        )
        assertEquals(
            "Grid parameters contain an invalid hat index",
            HatTiling.paramsInvalid(HatTiling.PatchParams(intArrayOf(0, 4, 0), 'T')),
            "hat index",
        )
    }

    @Test
    fun legacyPenroseMatchesTheRecordedVector() {
        val p2 = Grid.new(GridType.PENROSE_P2, 5, 5, "G0,0,0")
        assertEquals(100, p2.tileSize, "p2 tilesize")
        assertEquals(16, p2.dots.size, "p2 dots")
        assertEquals(10, p2.faces.size, "p2 faces")
        assertEquals(-250, p2.lowestX, "p2 lowestX")
        assertEquals(-226, p2.lowestY, "p2 lowestY")
        assertEquals(250, p2.highestX, "p2 highestX")
        assertEquals(274, p2.highestY, "p2 highestY")
        checkInvariants("legacy p2", p2)

        val p3 = Grid.new(GridType.PENROSE_P3, 5, 5, "G0,0,0")
        assertEquals(100, p3.tileSize, "p3 tilesize")
        assertEquals(11, p3.dots.size, "p3 dots")
        assertEquals(5, p3.faces.size, "p3 faces")
        assertEquals(-250, p3.lowestX, "p3 lowestX")
        assertEquals(-269, p3.lowestY, "p3 lowestY")
        assertEquals(250, p3.highestX, "p3 highestX")
        assertEquals(231, p3.highestY, "p3 highestY")
        checkInvariants("legacy p3", p3)
    }

    @Test
    fun theUpstreamAbortPathsAreDefined() {
        // D-018: these inputs kill upstream. Keep the safe Kotlin result
        // pinned so a change cannot bring the abort back.
        assertEquals(
            "empty grid description",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, ""),
            "empty penrose description",
        )
        assertEquals(
            "empty grid description",
            Grid.validateDesc(GridType.SPECTRES, 5, 5, ""),
            "empty spectre description",
        )
        assertEquals(
            "expected digit as second char of grid description",
            Grid.validateDesc(GridType.PENROSE_P2, 5, 5, "8"),
            "one-character penrose description",
        )
        assertEquals(
            "expected digit in grid description",
            Grid.validateDesc(GridType.SPECTRES, 5, 5, "0"),
            "one-character spectre description",
        )

        val empty = Grid.new(GridType.PENROSE_P2, 2, 2, "81B")
        assertEquals(0, empty.faces.size, "empty penrose faces")
        assertEquals(0, empty.edges.size, "empty penrose edges")
        assertEquals(0, empty.dots.size, "empty penrose dots")
    }

    @Test
    fun descriptionsAreStableAndSeeded() {
        for (type in listOf(
            GridType.PENROSE_P2,
            GridType.PENROSE_P3,
            GridType.HATS,
            GridType.SPECTRES,
        )) {
            val first = Grid.newDesc(type, 4, 4, RandomState.fromSeed("12345"))
            val second = Grid.newDesc(type, 4, 4, RandomState.fromSeed("12345"))
            val other = Grid.newDesc(type, 4, 4, RandomState.fromSeed("12346"))
            assertNotNull(first, "$type description")
            assertEquals(first, second, "$type deterministic")
            assertTrue(first != other, "$type should depend on the seed")
        }
        assertEquals(
            "0",
            Grid.newDesc(GridType.TRIANGULAR, 4, 4, RandomState.fromSeed("1")),
            "triangular",
        )
        assertNull(
            Grid.newDesc(GridType.SQUARE, 4, 4, RandomState.fromSeed("1")),
            "square",
        )
    }
}
