package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: untangle.
 *
 * Kotlin-only pins for the integer helpers, the double `sin`/`cos`
 * circle table, the crossing map and sticky completion, the drag and
 * snap arithmetic, the solver's single-precision rounding, the loose
 * move grammar, and the shared graph.
 */
class UntangleTest {

    @Test
    fun `defaults, presets, and constants match the C table`() {
        assertEquals(Untangle.Params(10), Untangle.defaultParams())
        assertEquals(
            listOf(6, 10, 15, 20, 25).map { "$it points" to "$it" },
            Untangle.presets().map { it.title to it.params },
        )
        assertEquals(64, Untangle.preferredTileSize)
        assertEquals(60, Untangle.DRAG_THRESHOLD)
        assertEquals(6, Untangle.CIRCLE_RADIUS)
        assertEquals(false, Untangle.wantsStatusBar)
        assertEquals(true, Untangle.canSolve)
        assertEquals(false, Untangle.canFormatAsText)
        assertEquals("", Untangle.textFormat(Untangle.newState(Untangle.Params(4), "")))

        // COORDLIMIT: floor(sqrt(3n)).
        for ((n, w) in listOf(4 to 3, 5 to 3, 6 to 4, 10 to 5, 15 to 6, 20 to 7, 25 to 8)) {
            assertEquals(w, Untangle.coordLimit(n), "COORDLIMIT($n)")
        }
    }

    @Test
    fun `squarert is an integer floor square root`() {
        assertEquals(0, Untangle.squarert(0))
        assertEquals(1, Untangle.squarert(1))
        assertEquals(2, Untangle.squarert(8))
        assertEquals(3, Untangle.squarert(9))
        assertEquals(3, Untangle.squarert(10))
        assertEquals(3, Untangle.squarert(15))
        assertEquals(4, Untangle.squarert(16))
        assertEquals(17, Untangle.squarert(300))
        assertEquals(46340, Untangle.squarert(2147483647))
    }

    @Test
    fun `cross counts touching endpoints and rejects collinear gaps`() {
        fun p(x: Long, y: Long) = Untangle.Point(x, y, 1)
        // A proper crossing.
        assertTrue(Untangle.cross(p(0, 0), p(2, 2), p(0, 2), p(2, 0)))
        // Parallel lines.
        assertFalse(Untangle.cross(p(0, 0), p(1, 0), p(0, 1), p(1, 1)))
        // A shared endpoint is on the other line.
        assertTrue(Untangle.cross(p(0, 0), p(1, 1), p(1, 1), p(2, 2)))
        // Collinear and disjoint.
        assertFalse(Untangle.cross(p(0, 0), p(1, 0), p(2, 0), p(3, 0)))
        // Collinear and overlapping.
        assertTrue(Untangle.cross(p(0, 0), p(2, 0), p(1, 0), p(3, 0)))
        // Collinear and touching at an endpoint.
        assertTrue(Untangle.cross(p(0, 0), p(1, 0), p(1, 0), p(2, 0)))
        // The degenerate segment of a point on another segment.
        assertTrue(Untangle.cross(p(0, 0), p(2, 0), p(1, 0), p(1, 0)))
        // Rational points: (0,0)-(1,1) against (0,1/2)-(1,1/2) does
        // not cross; the second line is above the first at their only
        // overlap.
        assertFalse(
            Untangle.cross(
                Untangle.Point(0, 0, 1),
                Untangle.Point(1, 1, 1),
                Untangle.Point(0, 1, 2),
                Untangle.Point(1, 2, 4),
            ),
        )
    }

    /**
     * The exact `make_circle` table at denominator 64, read from the
     * C build on this host. A different `sin`/`cos` would change the
     * description and the aux answer.
     */
    @Test
    fun `make circle coordinates match the C table for every preset`() {
        val expected = mapOf(
            4 to listOf(96L to 14L, 178L to 96L, 96L to 178L, 14L to 96L),
            5 to listOf(96L to 14L, 174L to 71L, 144L to 162L, 48L to 162L, 18L to 71L),
            6 to listOf(
                128L to 19L, 222L to 73L, 222L to 182L,
                128L to 237L, 34L to 183L, 34L to 73L,
            ),
            10 to listOf(
                160L to 23L, 241L to 49L, 290L to 118L, 290L to 202L,
                241L to 271L, 160L to 297L, 79L to 271L, 30L to 202L,
                30L to 118L, 79L to 49L,
            ),
            15 to listOf(
                192L to 28L, 259L to 42L, 314L to 82L, 348L to 141L,
                355L to 209L, 334L to 274L, 288L to 325L, 226L to 352L,
                158L to 352L, 96L to 325L, 50L to 274L, 29L to 209L,
                36L to 141L, 70L to 82L, 125L to 42L,
            ),
            20 to listOf(
                224L to 32L, 283L to 41L, 337L to 69L, 379L to 111L,
                407L to 165L, 416L to 224L, 407L to 283L, 379L to 337L,
                337L to 379L, 283L to 407L, 224L to 416L, 165L to 407L,
                111L to 379L, 69L to 337L, 41L to 283L, 32L to 224L,
                41L to 165L, 69L to 111L, 111L to 69L, 165L to 41L,
            ),
            25 to listOf(
                256L to 37L, 310L to 44L, 362L to 64L, 406L to 96L,
                441L to 139L, 464L to 188L, 475L to 242L, 471L to 297L,
                454L to 349L, 425L to 396L, 385L to 433L, 337L to 460L,
                283L to 473L, 229L to 473L, 175L to 460L, 127L to 433L,
                87L to 396L, 58L to 349L, 41L to 297L, 37L to 242L,
                48L to 188L, 71L to 139L, 106L to 96L, 150L to 64L,
                202L to 44L,
            ),
        )

        for ((n, rows) in expected) {
            val circle = Untangle.makeCircle(n, Untangle.coordLimit(n))
            assertEquals(n, circle.size, "n = $n")
            for (i in rows.indices) {
                assertEquals(rows[i].first, circle[i].x, "n = $n x[$i]")
                assertEquals(rows[i].second, circle[i].y, "n = $n y[$i]")
                assertEquals(64L, circle[i].d, "n = $n d[$i]")
            }
        }
    }

    @Test
    fun `the frozen corpus aux text decodes with AuxInfo`() {
        val hex = "c3d1ef9ff109d1ee98ac7e74a284c243ffa8a5cc09b10a6ae" +
            "10c953f4768622a7dabd3c381307656711c75200791e5f4fd00f35635aa76"
        assertEquals(
            "S;P0:3,3/2;P1:3,1/2;P2:5,7/2;P3:5,3/2;P4:7,3/2;P5:1,3/2",
            AuxInfo.decode(hex),
        )
    }

    @Test
    fun `the state shares the graph and copies the points and flags`() {
        val state = Untangle.newState(
            Untangle.Params(4),
            "0-1,0-2,0-3,1-2,1-3,2-3",
        ) as Untangle.State
        assertEquals(3, state.w)
        assertEquals(3, state.h)
        assertEquals(6, state.edgeCount)
        assertEquals(6, state.crosses.size)
        assertFalse(state.completed)
        assertEquals(0, Untangle.status(state))

        val copy = state.copy() as Untangle.State
        assertSame(state.graph, copy.graph)
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())

        copy.pts[0] = Untangle.Point(1, 1, 1)
        assertNotEquals(state, copy)
    }

    @Test
    fun `an empty description loads completed and status one`() {
        val state = Untangle.newState(Untangle.Params(10), "") as Untangle.State
        assertEquals(0, state.edgeCount)
        assertTrue(state.completed)
        assertEquals(1, Untangle.status(state))
    }

    @Test(timeout = 120_000)
    fun `completion is sticky after a real move`() {
        val midend = Midend.newGame(Untangle, "4", "12345")
        val start = midend.state as Untangle.State
        assertFalse(start.completed)

        // The solver's answer is crossing-free for this graph.
        val solved = Untangle.executeMove(
            start,
            requireNotNull(Untangle.solve(start, midend.auxInfo)),
        ) as Untangle.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertTrue(solved.justSolved)
        assertEquals(1, Untangle.status(solved))

        // Move point 0 back to its crossing position on the circle.
        val moved = Untangle.executeMove(solved, "P0:96,14/64") as Untangle.State
        assertTrue(moved.crosses.any { it })
        assertTrue(moved.completed, "a crossing never clears completed")
        assertEquals(1, Untangle.status(moved))
        assertFalse(moved.justSolved)
        assertTrue(moved.cheated)
    }

    @Test
    fun `changed state reports only a real completion`() {
        val crossing = Untangle.newState(
            Untangle.Params(4),
            "0-1,0-2,0-3,1-2,1-3,2-3",
        ) as Untangle.State
        val complete = crossing.copy() as Untangle.State
        complete.completed = true
        val ui = Untangle.Ui()

        assertTrue(Untangle.changedState(ui, crossing, complete))
        assertEquals(-1, ui.dragPoint)

        complete.cheated = true
        assertFalse(Untangle.changedState(ui, crossing, complete), "a solve or a cheat never reports")

        complete.cheated = false
        assertFalse(Untangle.changedState(ui, null, complete), "a new game has no event")
        assertFalse(Untangle.changedState(ui, complete, complete), "a completed state has no event")
    }

    @Test
    fun `the move grammar accepts and rejects the C forms`() {
        val ten = Untangle.newState(Untangle.Params(10), "0-1,1-2,2-3")

        for (move in listOf(
            "",
            "P0:1,2/3",
            "P0:1,2/3;P1:4,5/6",
            "P0:1,2/3P1:4,5/6",
            // `S` may start the second segment, not only the first.
            "P0:1,2/3;S;P1:4,5/6",
            "P0:1,2/3;SP1:4,5/6",
            "S;P0:1,2/3",
            "SP0:1,2/3",
            "P 0: 1,2/3",
            "P+0:1,2/3",
            // `P9` is the last valid index at n = 10.
            "P9:1,2/3",
        )) {
            assertNotNull(Untangle.executeMove(ten, move), "accepted '$move'")
        }

        for (move in listOf(
            "S",
            "SS;P0:1,2/3",
            "S;S;P0:1,2/3",
            "P0:1,2/3;S",
            "P0:1,2/0",
            "P10:1,2/3",
            "P-1:1,2/3",
            // `%d` saturates at the long range and keeps the low 32
            // bits, so this is INT_MAX, out of range.
            "P-2147483649:1,2/3",
            "xP0:1,2/3",
            "P0:1,2/3junk",
            "P0:1,2",
            "P0:1 ,2/3",
        )) {
            assertNull(Untangle.executeMove(ten, move), "rejected '$move'")
        }

        // The empty move duplicates the state.
        val empty = Untangle.executeMove(ten, "") as Untangle.State
        assertEquals(ten, empty)

        // `S` sets both flags, and a later real move clears just_solved.
        val solved = Untangle.executeMove(ten, "S;P0:1,2/3") as Untangle.State
        assertTrue(solved.cheated)
        assertTrue(solved.justSolved)
        val after = Untangle.executeMove(solved, "P0:1,2/3") as Untangle.State
        assertTrue(after.cheated)
        assertFalse(after.justSolved)

        // A mid-move `S` sets the flags and the two segments apply.
        val midS = Untangle.executeMove(ten, "P0:1,2/3;S;P1:4,5/6") as Untangle.State
        assertEquals(Untangle.Point(1, 2, 3), midS.point(0))
        assertEquals(Untangle.Point(4, 5, 6), midS.point(1))
        assertTrue(midS.cheated)
        assertTrue(midS.justSolved)

        // 2^32 truncates to 0, so this moves point 0 and leaves the
        // other points alone.
        val wrapped = Untangle.executeMove(ten, "P4294967296:1,2/3") as Untangle.State
        assertEquals(Untangle.Point(1, 2, 3), wrapped.point(0))
        assertEquals((ten as Untangle.State).point(1), wrapped.point(1))
    }

    @Test
    fun `normsq truncates with integer division`() {
        assertEquals(1.0f, Untangle.normsq(Untangle.Point(3, 1, 3)))
        assertEquals(1.0f, Untangle.normsq(Untangle.Point(1, 4, 3)))
        assertEquals(0.0f, Untangle.normsq(Untangle.Point(1, 1, 3)))
        assertEquals(2.0f, Untangle.normsq(Untangle.Point(3, 3, 3)))
    }

    @Test
    fun `place dragged point truncates toward zero with and without snap`() {
        val state = Untangle.newState(Untangle.Params(10), "0-1,1-2,2-3") as Untangle.State
        val ui = Untangle.Ui()

        Untangle.placeDraggedPoint(state, ui, 64, 160, 23)
        assertEquals(Untangle.Point(160, 23, 64), ui.newPoint)

        // A negative pointer keeps the sign without snap.
        Untangle.placeDraggedPoint(state, ui, 64, -1, -1)
        assertEquals(Untangle.Point(-1, -1, 64), ui.newPoint)

        // With snap, d = n-1 = 9, and the grid is 5 by 5 at tile 64.
        ui.snapToGrid = true
        Untangle.placeDraggedPoint(state, ui, 64, 100, 100)
        assertEquals(Untangle.Point(25, 25, 18), ui.newPoint)

        // One pixel outside truncates to grid 0 and commits.
        Untangle.placeDraggedPoint(state, ui, 64, -1, -1)
        assertEquals(Untangle.Point(5, 5, 18), ui.newPoint)

        // 36 pixels outside reaches grid -1 and cancels the release.
        Untangle.placeDraggedPoint(state, ui, 64, -36, -36)
        assertEquals(Untangle.Point(-5, -5, 18), ui.newPoint)
    }

    @Test
    fun `point under mouse uses the fixed 60 pixel radius`() {
        val state = Untangle.newState(Untangle.Params(10), "0-1,1-2,2-3") as Untangle.State
        // Point 0 is at (160, 23) at tile 64. Going up keeps every
        // other point more than 60 pixels away.
        assertEquals(0, Untangle.pointUnderMouse(state, 64, 160, 23))
        assertEquals(0, Untangle.pointUnderMouse(state, 64, 160, 23 - 59))
        // The comparison is `<=`, so exactly 60 pixels is still a hit.
        assertEquals(0, Untangle.pointUnderMouse(state, 64, 160, 23 - 60))
        assertEquals(-1, Untangle.pointUnderMouse(state, 64, 160, 23 - 61))
        // A click at the board centre is far from every point.
        assertEquals(-1, Untangle.pointUnderMouse(state, 64, 160, 160))
    }

    @Test
    fun `point under mouse and the cursor break a tie to the lowest index`() {
        // Both points are on the x axis. At (19, 10) the second one is
        // nearer, and at (20, 10) they are both 10 pixels away.
        val state = pointerState(Untangle.Point(30, 10, 1), Untangle.Point(10, 10, 1))
        assertEquals(
            1,
            Untangle.pointUnderMouse(state, 1, 19, 10),
            "the nearest point wins",
        )
        assertEquals(0, Untangle.pointUnderMouse(state, 1, 20, 10), "a tie goes to index 0")

        // The cursor quadrant search uses the same rule. Both points
        // are 200 away on the two edges of the right quadrant.
        val quadrantTie = triangle(
            Untangle.Point(10, 10, 1),
            Untangle.Point(10, -10, 1),
        )
        val ui = Untangle.Ui(cursorPoint = 0)
        assertEquals(
            MoveResult.UiUpdate,
            Untangle.interpretMove(quadrantTie, ui, 0, 0, Buttons.CURSOR_RIGHT, 64),
        )
        assertEquals(1, ui.cursorPoint, "a tie goes to the lowest index")

        // A nearer point on the second edge wins over the first one.
        val nearer = triangle(
            Untangle.Point(20, 20, 1),
            Untangle.Point(10, -10, 1),
        )
        val cursor = Untangle.Ui(cursorPoint = 0)
        Untangle.interpretMove(nearer, cursor, 0, 0, Buttons.CURSOR_RIGHT, 64)
        assertEquals(2, cursor.cursorPoint, "the nearest point wins")
    }

    /** Three points with an empty graph, for the pointer and cursor tests. */
    private fun pointerState(first: Untangle.Point, second: Untangle.Point): Untangle.State =
        Untangle.State(
            params = Untangle.Params(3),
            w = 4,
            h = 4,
            pts = arrayOf(first, second, Untangle.Point(100, 100, 1)),
            graph = Untangle.Graph(emptyList()),
            crosses = BooleanArray(0),
            completed = false,
            cheated = false,
            justSolved = false,
        )

    /** Point 0 at the origin, then two candidates on the right edge. */
    private fun triangle(first: Untangle.Point, second: Untangle.Point): Untangle.State =
        Untangle.State(
            params = Untangle.Params(3),
            w = 4,
            h = 4,
            pts = arrayOf(Untangle.Point(0, 0, 1), first, second),
            graph = Untangle.Graph(emptyList()),
            crosses = BooleanArray(0),
            completed = false,
            cheated = false,
            justSolved = false,
        )

    @Test
    fun `the solve error strings are the C strings`() {
        val state = Untangle.newState(Untangle.Params(4), "0-1,1-2,2-3")
        assertEquals(
            null to Untangle.ERROR_NO_AUX,
            Untangle.solveWithError(state, null),
        )
        assertEquals(
            null to Untangle.ERROR_BAD_AUX,
            Untangle.solveWithError(state, "not an answer"),
        )
        assertEquals(
            null to Untangle.ERROR_BAD_AUX,
            Untangle.solveWithError(state, ""),
        )
        assertNull(Untangle.solve(state, null))

        // The aux point index is `%d` too: 2^32 truncates to point 0
        // and the answer parses, while -2^31-1 truncates to INT_MAX,
        // which is not point 0.
        val aux = "S;P4294967296:1,2/3;P1:4,5/6;P2:7,8/9;P3:1,1/1"
        assertNotNull(Untangle.solve(state, aux), "2^32 is point 0")
        assertEquals(
            null to Untangle.ERROR_BAD_AUX,
            Untangle.solveWithError(
                state,
                "S;P-2147483649:1,2/3;P1:4,5/6;P2:7,8/9;P3:1,1/1",
            ),
        )
    }

    @Test
    fun `the solver picks the identity and rounds with plus a half`() {
        // Four aux points form a rectangle around the centre. The
        // current state is the same, so the identity symmetry wins.
        for ((numerator, expected) in listOf(1L to 0L, 2L to 1L)) {
            val pts = arrayOf(
                Untangle.Point(numerator, 1, 8),
                Untangle.Point(23, 1, 8),
                Untangle.Point(1, 23, 8),
                Untangle.Point(23, 23, 8),
            )
            val state = Untangle.State(
                params = Untangle.Params(4),
                w = 3,
                h = 3,
                pts = pts,
                graph = Untangle.Graph(emptyList()),
                crosses = BooleanArray(0),
                completed = false,
                cheated = false,
                justSolved = false,
            )
            val aux = "S;P0:$numerator,1/8;P1:23,1/8;P2:1,23/8;P3:23,23/8"
            val move = Untangle.solve(state, aux)
            assertNotNull(move)
            assertTrue(
                move!!.startsWith("S;P0:"),
                "the identity keeps the order: $move",
            )
            assertEquals("S;P0:$expected,0/2;P1:6,0/2;P2:0,6/2;P3:6,6/2", move)

            // A negative value truncates toward zero, not toward -inf.
            val negative = state.copy() as Untangle.State
            negative.pts[0] = Untangle.Point(-5, 1, 8)
            val negativeAux = "S;P0:-5,1/8;P1:23,1/8;P2:1,23/8;P3:23,23/8"
            assertEquals(
                "S;P0:0,0/2;P1:6,0/2;P2:0,6/2;P3:6,6/2",
                Untangle.solve(negative, negativeAux),
            )
        }
    }

    @Test
    fun `preferences default off and are not serialised`() {
        val state = Untangle.newState(Untangle.Params(4), "0-1,1-2,2-3")
        val ui = Untangle.newUi(state) as Untangle.Ui
        assertEquals(-1, ui.dragPoint)
        assertEquals(-1, ui.cursorPoint)
        assertFalse(ui.snapToGrid)
        assertFalse(ui.showCrossedEdges)
        assertFalse(ui.vertexNumbers)
        assertFalse(ui.justDragged)
        assertFalse(ui.justMoved)
        assertEquals(0f, ui.animLength)
        assertEquals(Untangle.Point(0, 0, 1), ui.newPoint)

        // There is no encode_ui and no decode_ui.
        assertNull(Untangle.encodeUi(ui))
    }

    @Test
    fun `the cursor rectangle is thirteen pixels around the point`() {
        val state = Untangle.newState(Untangle.Params(10), "0-1,1-2,2-3")
        val ui = Untangle.Ui()
        assertNull(Untangle.cursorLocation(ui, state, 64))

        ui.cursorPoint = 0
        assertEquals(Game.CursorRect(154, 17, 13, 13), Untangle.cursorLocation(ui, state, 64))

        ui.cursorPoint = -1
        ui.dragPoint = 0
        ui.newPoint = Untangle.Point(32, 64, 64)
        assertEquals(Game.CursorRect(26, 58, 13, 13), Untangle.cursorLocation(ui, state, 64))
    }
}
