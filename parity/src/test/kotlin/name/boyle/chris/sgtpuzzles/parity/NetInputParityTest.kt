package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: net.
 *
 * The frontend geometry and input matrix: the layout, the release
 * commit at four tile sizes and the letterbox, the drag quadrants and
 * their tie order, the keyboard count, inert events with a positive
 * control, and the extra keys.
 */
class NetInputParityTest {

    private companion object {
        const val SEED = "12345"

        /** `computeSize` for a 5x5 board. */
        fun five(width: Int, tile: Int): Int {
            val lt = (tile + 47) / 48
            return 8 + width * tile + lt
        }
    }

    private fun save(params: String = "5x5"): String =
        Midend.newGame(Net, params, SEED).serialise().serialise()

    private fun kotlin(save: String): Midend =
        Midend.fromSave(Net, SgtpSave.parse(save))

    private fun upstream(): String =
        UpstreamLock.commit(
            java.io.File(System.getProperty("testdata")!!)
                .parentFile.resolve("UPSTREAM.lock"),
        )

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tile: Int) {
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)
    }

    private fun pt(x: Int, y: Int, button: Int) = OracleClient.Point(x, y, button)

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
                val centre = 5 + tile + tile / 2
                val points = listOf(
                    pt(centre, centre, Buttons.LEFT_BUTTON),
                    pt(centre, centre, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    state,
                    points,
                    width = view,
                    height = view,
                ).state
                val midend = kotlin(state)
                apply(midend, points, tile)
                assertEquals("tile $tile state", expected.state, midend.serialise().serialise())
                assertEquals("tile $tile move", listOf("A1,1"), midend.serialise().values("MOVE"))
                assertEquals(2, midend.stateCount)
            }

            // The letterbox origin is subtracted before the midend.
            val viewW = 412
            val viewH = 702
            val geometry = Midend.fromSave(Net, SgtpSave.parse(state)).geometryFor(viewW, viewH)
            assertEquals(80, geometry.tileSize)
            assertEquals(1, geometry.originX)
            assertEquals(146, geometry.originY)
            val gameX = 5 + geometry.tileSize + geometry.tileSize / 2
            val gameY = 5 + geometry.tileSize + geometry.tileSize / 2
            val viewPoints = listOf(
                pt(gameX + geometry.originX, gameY + geometry.originY, Buttons.LEFT_BUTTON),
                pt(gameX + geometry.originX, gameY + geometry.originY, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(
                state,
                viewPoints,
                width = viewW,
                height = viewH,
            ).state
            val midend = kotlin(state)
            apply(
                midend,
                viewPoints.map {
                    OracleClient.Point(
                        it.x - geometry.originX,
                        it.y - geometry.originY,
                        it.button,
                    )
                },
                geometry.tileSize,
            )
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(listOf("A1,1"), midend.serialise().values("MOVE"))
        }

    @Test
    fun `the drag quadrants and the tie order match the oracle`() =
        OracleClient().use { oracle ->
            val state = save()
            val tile = 63
            val view = five(5, tile)
            val press = 5 + 1
            val near = 5 + tile - 3
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
                assertEquals("$name drag state", expectedDrag.state, midend.serialise().serialise())
                assertEquals(
                    "$name drag count",
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
                assertEquals(
                    "$name release state",
                    expectedRelease.state,
                    released.serialise().serialise(),
                )
                // A d0 drag leaves `dragged` false, so the release is a
                // left click. Every other quadrant commits on the drag.
                assertEquals(
                    "$name release moves",
                    listOf(expectedMove ?: "A0,0"),
                    released.serialise().values("MOVE"),
                )
            }
        }

    private data class Quad(
        val name: String,
        val dragX: Int,
        val dragY: Int,
        val move: String?,
    )

    @Test
    fun `the keyboard keys match at tile 63`() = OracleClient().use { oracle ->
        val state = save()
        for (sequence in listOf(
            listOf('\n'.code),
            listOf(' '.code),
            listOf('a'.code, 'd'.code, 'f'.code),
            listOf('s'.code, 's'.code, 'f'.code),
            listOf('j'.code),
        )) {
            val expected = oracle.keys(state, sequence)
            val midend = kotlin(state)
            for (button in sequence) midend.processKey(0, 0, button, 63)
            assertEquals(
                "sequence $sequence",
                expected.state,
                midend.serialise().serialise(),
            )
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
            midend.processKey(5 + tile - 1, 5 + tile / 2, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // One pixel outside the grid.
            midend.processKey(5 + 5 * tile, 5 + tile / 2, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // The far corner of the viewport.
            midend.processKey(view - 1, view - 1, Buttons.LEFT_BUTTON, tile)
            assertEquals(before, midend.serialise().serialise())
            // A middle drag with no held button is dropped.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(5 + tile / 2, 5 + tile / 2, Buttons.MIDDLE_DRAG, tile),
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
                pt(5 + tile - 1, 5 + tile / 2, Buttons.LEFT_BUTTON),
                pt(5 + 5 * tile, 5 + tile / 2, Buttons.LEFT_BUTTON),
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

            // Positive control: a real click in the same fixture moves.
            val positive = listOf(
                pt(5 + 16, 5 + 16, Buttons.LEFT_BUTTON),
                pt(5 + 16, 5 + 16, Buttons.LEFT_RELEASE),
            )
            apply(midend, positive, tile)
            assertEquals(2, midend.stateCount)
            assertEquals(listOf("A0,0"), midend.serialise().values("MOVE"))
            assertNotEquals(before, midend.serialise().serialise())
            assertTrue((midend.state as Net.State).tiles.isNotEmpty())
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
