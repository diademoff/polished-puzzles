package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: net.
 *
 * The frontend geometry and input matrix: the layout, the release
 * commit at four tile sizes and the letterbox, the middle lock, the
 * drag quadrants and their tie order, the circular drag, the jumble,
 * the cursor and its ring, the origin and source keys, inert events
 * with a positive control, and the extra keys.
 */
class NetInputParityTest {

    private companion object {
        const val SEED = "12345"

        /** `LINE_THICK` for [tile], as `net.c:64`. */
        fun lineThick(tile: Int): Int = (tile + 47) / 48

        /** The input origin `WINDOW_OFFSET + LINE_THICK`. */
        fun pad(tile: Int): Int = 4 + lineThick(tile)

        /** `computeSize` for a 5x5 board. */
        fun five(width: Int, tile: Int): Int = 8 + width * tile + lineThick(tile)
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun save(params: String = "5x5"): String =
        Midend.newGame(Net, params, SEED).serialise().serialise()

    private fun kotlin(save: String): Midend =
        Midend.fromSave(Net, SgtpSave.parse(save))

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tile: Int) {
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        geometry: BoardGeometry,
    ) {
        for (p in points) {
            midend.processKey(
                p.x - geometry.originX,
                p.y - geometry.originY,
                p.button,
                geometry.tileSize,
            )
        }
    }

    private fun pt(x: Int, y: Int, button: Int) = OracleClient.Point(x, y, button)

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun assertTrace(what: String, expected: JsonValue.Obj?, actual: JsonValue.Obj) {
        requireNotNull(expected) { "$what: the oracle sent no trace" }
        val e = TraceNormalizer.normalize(expected, upstream()).lines()
        val a = TraceNormalizer.normalize(actual, upstream()).lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError(
                    "$what: first diff at line ${i + 1}:\n" +
                        "expected: ${e[i]}\nactual:   ${a[i]}",
                )
            }
        }
        assertEquals("$what: line count", e.size, a.size)
    }

    /**
     * The trace after a key. The oracle records a status line only when
     * the key did not already redraw; a refused key has no earlier
     * draw, so it emits the line at the end. Compare everything except
     * the status line; the Kotlin sink primes an unchanged text.
     */
    private fun assertKeyTrace(
        what: String,
        expected: JsonValue.Obj,
        actual: JsonValue.Obj,
    ) {
        val expectedLines = TraceNormalizer.normalize(expected, upstream()).lines()
            .filterNot { it.startsWith("status ") }
        val actualLines = TraceNormalizer.normalize(actual, upstream()).lines()
        assertTrue(
            "$what: unexpected status line",
            actualLines.none { it.startsWith("status ") },
        )
        assertEquals("$what frame", expectedLines, actualLines)
    }

    private fun coldTrace(save: String, tile: Int): JsonValue.Obj =
        KotlinTraceSink.stateTrace(Midend.fromSave(Net, SgtpSave.parse(save)), tile)

    /**
     * The final frame after a rotation. The oracle's own frame is the
     * animation-start frame, which the seam does not have, so reload the
     * moved save and compare the cold draw on both sides.
     */
    private fun assertReloadedTrace(
        what: String,
        moved: String,
        tile: Int,
        width: Int,
        height: Int,
        oracle: OracleClient,
    ) {
        val expected = oracle.pointsSized(
            moved,
            emptyList(),
            width = width,
            height = height,
            recordTrace = true,
        ).trace
        assertTrace(what, expected, coldTrace(moved, tile))
    }

    @Test
    fun `the layout matches the oracle`() = OracleClient().use { oracle ->
        val state = save()
        for ((viewW, viewH) in listOf(
            412 to 702,
            720 to 1280,
            1024 to 600,
            200 to 200,
            249 to 249,
            325 to 325,
            420 to 420,
            511 to 511,
        )) {
            val expected = oracle.layout(state, viewW, viewH)
            val midend = Midend.fromSave(Net, SgtpSave.parse(state))
            val geometry = midend.geometryFor(viewW, viewH)
            assertEquals("$viewW x $viewH board width", expected.width, geometry.width)
            assertEquals("$viewW x $viewH board height", expected.height, geometry.height)
            assertEquals(
                "$viewW x $viewH centred x",
                (viewW - geometry.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "$viewW x $viewH centred y",
                (viewH - geometry.height) / 2,
                geometry.originY,
            )
        }

        // The four exact board sizes choose the intended tile size.
        for (tile in listOf(48, 63, 82, 100)) {
            val size = five(5, tile)
            val geometry = Midend.fromSave(Net, SgtpSave.parse(state))
                .geometryFor(size, size)
            assertEquals("tile $tile", tile, geometry.tileSize)
            assertEquals("tile $tile width", size, geometry.width)
            assertEquals(0, geometry.originX)
            assertEquals(0, geometry.originY)
        }
    }

    @Test
    fun `a click rotates at the release at four tiles and the letterbox`() =
        OracleClient().use { oracle ->
            val state = save()
            for (tile in listOf(48, 63, 82, 100)) {
                val view = five(5, tile)
                val centre = pad(tile) + tile + tile / 2
                val points = listOf(
                    pt(centre, centre, Buttons.LEFT_BUTTON),
                    pt(centre, centre, Buttons.LEFT_RELEASE),
                )
                val result = oracle.pointsSized(
                    state,
                    points,
                    width = view,
                    height = view,
                    recordTrace = true,
                )
                val midend = kotlin(state)
                apply(midend, points, tile)
                assertSame("tile $tile", result.state, midend)
                assertEquals("tile $tile move", listOf("A1,1"), midend.serialise().values("MOVE"))
                assertEquals(2, midend.stateCount)
                assertReloadedTrace(
                    "tile $tile frame",
                    result.state.state,
                    tile,
                    view,
                    view,
                    oracle,
                )
            }

            // The letterbox origin is subtracted before the midend.
            val viewW = 412
            val viewH = 702
            val geometry = Midend.fromSave(Net, SgtpSave.parse(state)).geometryFor(viewW, viewH)
            assertEquals(80, geometry.tileSize)
            assertEquals(1, geometry.originX)
            assertEquals(146, geometry.originY)
            val centre = pad(geometry.tileSize) + geometry.tileSize +
                geometry.tileSize / 2
            val viewPoints = listOf(
                pt(centre + geometry.originX, centre + geometry.originY, Buttons.LEFT_BUTTON),
                pt(centre + geometry.originX, centre + geometry.originY, Buttons.LEFT_RELEASE),
            )
            val result = oracle.pointsSized(
                state,
                viewPoints,
                width = viewW,
                height = viewH,
                recordTrace = true,
            )
            val midend = kotlin(state)
            apply(midend, viewPoints, geometry)
            assertSame("letterbox", result.state, midend)
            assertEquals(listOf("A1,1"), midend.serialise().values("MOVE"))
            assertReloadedTrace(
                "letterbox frame",
                result.state.state,
                geometry.tileSize,
                viewW,
                viewH,
                oracle,
            )
        }

    @Test
    fun `a middle click locks at four tiles and the letterbox`() =
        OracleClient().use { oracle ->
            val state = save()
            for (tile in listOf(48, 63, 82, 100)) {
                val view = five(5, tile)
                val centre = pad(tile) + tile + tile / 2
                val points = listOf(pt(centre, centre, Buttons.MIDDLE_BUTTON))
                val result = oracle.pointsSized(
                    state,
                    points,
                    width = view,
                    height = view,
                    recordTrace = true,
                )
                val midend = kotlin(state)
                apply(midend, points, tile)
                assertSame("tile $tile", result.state, midend)
                assertEquals(listOf("L1,1"), midend.serialise().values("MOVE"))
                assertEquals(2, midend.stateCount)
                // A lock has no rotation animation, so the live frame is
                // exact.
                assertTrace(
                    "tile $tile lock frame",
                    result.trace,
                    KotlinTraceSink.stateTrace(midend, tile),
                )
            }

            val geometry = Midend.fromSave(Net, SgtpSave.parse(state)).geometryFor(412, 702)
            val centre = pad(geometry.tileSize) + geometry.tileSize +
                geometry.tileSize / 2
            val points = listOf(
                pt(
                    centre + geometry.originX,
                    centre + geometry.originY,
                    Buttons.MIDDLE_BUTTON,
                ),
            )
            val result = oracle.pointsSized(
                state,
                points,
                width = 412,
                height = 702,
                recordTrace = true,
            )
            val midend = kotlin(state)
            apply(midend, points, geometry)
            assertSame("letterbox", result.state, midend)
            assertEquals(listOf("L1,1"), midend.serialise().values("MOVE"))
            assertTrace(
                "letterbox lock frame",
                result.trace,
                KotlinTraceSink.stateTrace(midend, geometry.tileSize),
            )
        }

    @Test
    fun `the drag quadrants and the tie order match at four tiles`() =
        OracleClient().use { oracle ->
            val state = save()
            for (tile in listOf(48, 63, 82, 100)) {
                val view = five(5, tile)
                val press = pad(tile) + 1
                val near = pad(tile) + tile - 1 - lineThick(tile)
                for ((name, dragX, dragY, expectedMove) in listOf(
                    Quad("d0", press + 2, press + 2, null),
                    Quad("dF", near, near, "F0,0"),
                    Quad("dA", press, near, "A0,0"),
                    Quad("dC", near, press, "C0,0"),
                )) {
                    val dragOnly = listOf(
                        pt(press, press, Buttons.LEFT_BUTTON),
                        pt(dragX, dragY, Buttons.LEFT_DRAG),
                    )
                    val expectedDrag = oracle.pointsSized(
                        state,
                        dragOnly,
                        width = view,
                        height = view,
                    ).state
                    val midend = kotlin(state)
                    apply(midend, dragOnly, tile)
                    assertSame("tile $tile $name drag", expectedDrag, midend)
                    assertEquals(
                        "tile $tile $name drag count",
                        if (expectedMove == null) 1 else 2,
                        midend.stateCount,
                    )

                    val withRelease = dragOnly + pt(dragX, dragY, Buttons.LEFT_RELEASE)
                    val expectedRelease = oracle.pointsSized(
                        state,
                        withRelease,
                        width = view,
                        height = view,
                    ).state
                    val released = kotlin(state)
                    apply(released, withRelease, tile)
                    assertSame("tile $tile $name release", expectedRelease, released)
                    // A d0 drag leaves `dragged` false, so the release is
                    // a left click. Every other quadrant commits on the
                    // drag.
                    assertEquals(
                        "tile $tile $name release moves",
                        listOf(expectedMove ?: "A0,0"),
                        released.serialise().values("MOVE"),
                    )
                    assertReloadedTrace(
                        "tile $tile $name frame",
                        released.serialise().serialise(),
                        tile,
                        view,
                        view,
                        oracle,
                    )
                }
            }
        }

    private data class Quad(
        val name: String,
        val dragX: Int,
        val dragY: Int,
        val move: String?,
    )

    @Test
    fun `a continuous circular drag emits one move per quarter turn`() =
        OracleClient().use { oracle ->
            val state = save()
            for (tile in listOf(32, 48, 100)) {
                val view = five(5, tile)
                // Press at the top-left corner, then walk clockwise
                // around the tile. Every accepted turn moves the drag
                // start, so each drag is a separate move.
                val start = pad(tile) + 1
                val near = pad(tile) + tile - 1 - lineThick(tile)
                val points = listOf(
                    pt(start, start, Buttons.LEFT_BUTTON),
                    pt(near, start, Buttons.LEFT_DRAG),
                    pt(near, near, Buttons.LEFT_DRAG),
                    pt(start, near, Buttons.LEFT_DRAG),
                    pt(start, start, Buttons.LEFT_DRAG),
                    pt(start, start, Buttons.LEFT_RELEASE),
                )
                val result = oracle.pointsSized(
                    state,
                    points,
                    width = view,
                    height = view,
                )
                val midend = kotlin(state)
                apply(midend, points, tile)
                assertSame("tile $tile", result.state, midend)
                assertEquals(
                    "tile $tile move count",
                    SgtpSave.parse(result.state.state).values("MOVE"),
                    midend.serialise().values("MOVE"),
                )
                assertEquals(
                    "tile $tile moves",
                    listOf("C0,0", "C0,0", "C0,0", "C0,0"),
                    midend.serialise().values("MOVE"),
                )
                assertEquals(5, midend.stateCount)
                assertReloadedTrace(
                    "tile $tile frame",
                    midend.serialise().serialise(),
                    tile,
                    view,
                    view,
                    oracle,
                )
            }
        }

    @Test
    fun `the keyboard rotates, locks, flips, and jumbles at four tiles`() =
        OracleClient().use { oracle ->
            val state = save()
            for (tile in listOf(48, 63, 82, 100)) {
                for (sequence in listOf(
                    listOf('\n'.code),
                    listOf(' '.code),
                    listOf('a'.code, 'd'.code, 'f'.code),
                    listOf('s'.code, 's'.code, 'f'.code),
                    listOf('j'.code),
                )) {
                    val expected = oracle.keys(state, sequence)
                    val midend = kotlin(state)
                    for (button in sequence) midend.processKey(0, 0, button, tile)
                    assertSame("tile $tile $sequence", expected, midend)
                }
            }

            // Enter rotates and Space locks through the midend mapping.
            val enter = kotlin(state)
            enter.processKey(0, 0, '\n'.code, 63)
            assertEquals(listOf("A2,2"), enter.serialise().values("MOVE"))
            val space = kotlin(state)
            space.processKey(0, 0, ' '.code, 63)
            assertEquals(listOf("L2,2"), space.serialise().values("MOVE"))
        }

    @Test
    fun `a real jumble changes the board and the count`() =
        OracleClient().use { oracle ->
            val state = save()
            for (tile in listOf(63, 100)) {
                val midend = kotlin(state)
                val before = (midend.state as Net.State).tiles.copyOf()
                val expected = oracle.keys(state, listOf('j'.code))
                val move = midend.processKey(0, 0, 'j'.code, tile)
                assertEquals("tile $tile outcome", KeyOutcome.SomeEffect, move)
                assertSame("tile $tile", expected, midend)
                assertFalse(
                    "tile $tile changes the board",
                    before.contentEquals((midend.state as Net.State).tiles),
                )
                assertEquals(2, midend.stateCount)
                assertTrue(midend.serialise().values("MOVE").single().startsWith("J"))
                // A `J` move sets no last rotation direction, so the
                // live frame is exact.
                assertKeyTrace(
                    "tile $tile jumble frame",
                    oracle.trace(state, sequence = listOf('j'.code)),
                    KotlinTraceSink.stateTrace(midend, 32, statusAlreadyDrawn = true),
                )
            }
        }

    @Test
    fun `the cursor keys, the select pair, and the ring match the oracle`() =
        OracleClient().use { oracle ->
            val state = save()
            val tile = 63
            for (sequence in listOf(
                listOf(Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
                listOf(Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_SELECT2, Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_SELECT2, Buttons.CURSOR_SELECT),
            )) {
                val expected = oracle.keys(state, sequence)
                val midend = kotlin(state)
                for (key in sequence) midend.processKey(0, 0, key, tile)
                assertSame("keys $sequence", expected, midend)

                // A cursor select is a rotation and the oracle trace then
                // shows the animation-start frame. Every other sequence
                // ends without a rotation, so the live frame is exact.
                if (Buttons.CURSOR_SELECT in sequence &&
                    sequence.last() == Buttons.CURSOR_SELECT &&
                    sequence.first() != Buttons.CURSOR_SELECT2
                ) {
                    continue
                }
                assertKeyTrace(
                    "ring $sequence",
                    oracle.trace(state, sequence = sequence),
                    KotlinTraceSink.stateTrace(midend, 32, statusAlreadyDrawn = true),
                )
            }

            // A bare select on the centre tile rotates it and shows the
            // ring.
            val midend = kotlin(state)
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
            )
            assertEquals(listOf("A2,2"), midend.serialise().values("MOVE"))
            assertTrue((midend.ui as Net.Ui).curVisible)
            assertReloadedTrace(
                "select frame",
                midend.serialise().serialise(),
                tile,
                five(5, tile),
                five(5, tile),
                oracle,
            )

            // A select on a locked tile is inert, but it still reveals
            // the cursor.
            val locked = kotlin(state)
            locked.processKey(0, 0, Buttons.CURSOR_SELECT2, tile)
            assertEquals(
                KeyOutcome.Unused,
                locked.processKey(0, 0, Buttons.CURSOR_SELECT, tile),
            )
            assertEquals(listOf("L2,2"), locked.serialise().values("MOVE"))
            assertTrue((locked.ui as Net.Ui).curVisible)
        }

    @Test
    fun `the origin and source keys change only the UI and the frame`() =
        OracleClient().use { oracle ->
            val tile = 48
            for ((params, sequence) in listOf(
                "5x5w" to listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
                "5x5w" to listOf(Buttons.CURSOR_DOWN or Buttons.MOD_SHFT),
                "5x5" to listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
                "5x5" to listOf(Buttons.CURSOR_LEFT or Buttons.MOD_CTRL),
                "5x5" to listOf(
                    Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT or Buttons.MOD_CTRL,
                ),
            )) {
                val state = save(params)
                val expected = oracle.keys(state, sequence)
                val midend = kotlin(state)
                val before = midend.state.copy()
                for (key in sequence) midend.processKey(0, 0, key, tile)
                assertSame("$params $sequence", expected, midend)
                assertEquals("$params $sequence no history", 1, midend.stateCount)
                assertEquals("$params $sequence state", before, midend.state)

                assertKeyTrace(
                    "$params $sequence frame",
                    oracle.trace(state, sequence = sequence),
                    KotlinTraceSink.stateTrace(midend, 32, statusAlreadyDrawn = true),
                )

                // The oracle writes the same UI record.
                assertEquals(
                    "$params $sequence UI",
                    SgtpSave.parse(expected.state).value("UI"),
                    midend.serialise().value("UI"),
                )
            }
        }

    @Test
    fun `inert events change nothing, with a positive control`() =
        OracleClient().use { oracle ->
            val state = save()
            val tile = 63
            val view = five(5, tile)
            val midend = kotlin(state)
            val before = midend.serialise().serialise()
            val beforeCount = midend.stateCount

            // A click in the letterbox (outside the board).
            midend.processKey(-50, -50, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // The input band on the right edge of tile 0.
            midend.processKey(pad(tile) + tile - 1, pad(tile) + tile / 2, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // One pixel outside the grid.
            midend.processKey(pad(tile) + 5 * tile, pad(tile) + tile / 2, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // The far corner of the viewport.
            midend.processKey(view - 1, view - 1, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // A middle drag with no held button is dropped.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(
                    pad(tile) + tile / 2,
                    pad(tile) + tile / 2,
                    Buttons.MIDDLE_DRAG,
                    tile,
                ),
            )
            // A modified non-cursor key is not a game key.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, 'a'.code or Buttons.MOD_CTRL, tile),
            )
            assertEquals(before, midend.serialise().serialise())
            assertEquals(beforeCount, midend.stateCount)

            // The oracle agrees on the inert gestures.
            val inert = listOf(
                pt(pad(tile) + tile - 1, pad(tile) + tile / 2, Buttons.LEFT_BUTTON),
                pt(pad(tile) + 5 * tile, pad(tile) + tile / 2, Buttons.LEFT_BUTTON),
                pt(view - 1, view - 1, Buttons.LEFT_BUTTON),
            )
            for (p in inert) {
                val expected = oracle.pointsSized(
                    state,
                    listOf(p),
                    width = view,
                    height = view,
                ).state
                assertEquals("inert $p", state, expected.state)
            }

            // A letterbox click outside the board is inert too.
            val geometry = kotlin(state).geometryFor(412, 702)
            val letterbox = kotlin(state)
            assertEquals(
                KeyOutcome.Unused,
                letterbox.processKey(-geometry.originX, -geometry.originY, Buttons.LEFT_BUTTON, 80),
            )
            assertEquals(before, letterbox.serialise().serialise())
            assertEquals(1, letterbox.stateCount)

            // Positive control: a real click in the same fixture moves.
            val positive = listOf(
                pt(pad(tile) + 16, pad(tile) + 16, Buttons.LEFT_BUTTON),
                pt(pad(tile) + 16, pad(tile) + 16, Buttons.LEFT_RELEASE),
            )
            apply(midend, positive, tile)
            assertEquals(2, midend.stateCount)
            assertEquals(listOf("A0,0"), midend.serialise().values("MOVE"))
            assertNotEquals(before, midend.serialise().serialise())
            assertTrue((midend.state as Net.State).tiles.isNotEmpty())
        }

    @Test
    fun `a real rotation, lock, and jumble change the board and the count`() {
        for (tile in listOf(48, 63, 82, 100)) {
            val centre = pad(tile) + 16
            for ((what, keys) in listOf(
                Pair("rotate", listOf(
                    pt(centre, centre, Buttons.LEFT_BUTTON),
                    pt(centre, centre, Buttons.LEFT_RELEASE),
                )),
                Pair("lock", listOf(
                    pt(centre, centre, Buttons.MIDDLE_BUTTON),
                )),
                Pair("jumble", listOf(pt(0, 0, 'j'.code))),
            )) {
                val midend = kotlin(save())
                val before = (midend.state as Net.State).tiles.copyOf()
                var outcome = KeyOutcome.Unused
                for (key in keys) {
                    outcome = midend.processKey(key.x, key.y, key.button, tile)
                }
                assertEquals(
                    "tile $tile $what outcome",
                    KeyOutcome.SomeEffect,
                    outcome,
                )
                assertFalse(
                    "tile $tile $what changes the board",
                    before.contentEquals((midend.state as Net.State).tiles),
                )
                assertEquals("tile $tile $what move count", 1, midend.serialise().values("MOVE").size)
                assertEquals("tile $tile $what state count", 2, midend.stateCount)
                assertTrue("tile $tile $what can undo", midend.canUndo)
            }
        }
    }

    @Test
    fun `an origin move changes only the UI and the frame`() =
        OracleClient().use { oracle ->
            val state = save("5x5w")
            val midend = kotlin(state)
            val beforeState = midend.state.copy()
            val beforeId = midend.id()
            val beforeTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(Midend.fromSave(Net, SgtpSave.parse(state))),
                upstream(),
            )

            val outcome = midend.processKey(
                0,
                0,
                Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
                32,
            )
            assertEquals(KeyOutcome.SomeEffect, outcome)
            assertEquals(1, midend.stateCount)
            assertEquals(beforeState, midend.state)
            assertEquals(beforeId, midend.id())
            assertEquals("O1,0;C2,2", midend.serialise().value("UI"))
            assertNotEquals(
                beforeTrace,
                TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream()),
            )

            // The oracle applies the same UI move and the same record.
            val moved = oracle.keys(
                state,
                listOf(Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
            )
            assertEquals("O1,0;C2,2", SgtpSave.parse(moved.state).value("UI"))
            assertEquals(midend.serialise().serialise(), moved.state)
        }

    @Test
    fun `requestKeys matches the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.requestKeys(save())
        val actual = Net.requestKeys(Net.defaultParams())
        assertEquals(expected.arrowMode, actual.arrowMode.ordinal)
        assertEquals(
            expected.labels.map { Triple(it.label, it.button, it.needsArrows) },
            actual.labels.map { Triple(it.name, it.button, it.needsArrows) },
        )
        assertEquals("ARROWS_ONLY", actual.arrowMode.name)
    }
}
