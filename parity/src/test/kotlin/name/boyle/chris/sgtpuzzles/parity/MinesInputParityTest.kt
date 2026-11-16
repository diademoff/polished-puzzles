package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: mines.
 *
 * The layout, the real click paths at several tile sizes and in a
 * letterbox, the flag and chord rules, the cursor, the one-key undo and
 * redo, and the vacuous events that must not add a move.
 *
 * Each vacuous case is followed by a positive control, so a missing
 * input path fails instead of passing silently.
 */
class MinesInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val tile = 20

    @Test
    fun `the layout matches the oracle at several viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("mines", "9x9n10", SEED).state
            val midend = Midend.newGame(Mines, "9x9n10", SEED)
            for (viewport in listOf(
                411 to 702,
                720 to 1280,
                1024 to 600,
                602 to 602,
                196 to 196,
                448 to 448,
            )) {
                val expected = oracle.layout(save, viewport.first, viewport.second)
                val geometry = midend.geometryFor(viewport.first, viewport.second)
                assertEquals(
                    "layout ${viewport.first}x${viewport.second}",
                    "${expected.width}x${expected.height}",
                    "${geometry.width}x${geometry.height}",
                )
                assertTrue(geometry.tileSize >= 1)
            }
        }

    @Test(timeout = 120_000)
    fun `a real click at a non-default tile size matches the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("mines", "9x9n10", SEED).state
            for (tileSize in listOf(20, 48, 63, 82, 100)) {
                val board = Mines.computeSize(Mines.decodeParams("9x9n10"), tileSize)
                val points = clickPoints(4, 4, tileSize)
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Mines, SgtpSave.parse(save))
                apply(midend, points, tileSize)
                assertEquals("tile $tileSize id", expected.state.id, midend.id())
                assertEquals(
                    "tile $tileSize canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("tile $tileSize states", 2, midend.stateCount)
                assertEquals("tile $tileSize status", expected.state.status, midend.status())
                assertEquals("tile $tileSize UI", "D0", midend.serialise().value("UI"))
                assertEquals("tile $tileSize TIME", "0", midend.serialise().value("TIME"))
                assertFalse(
                    "tile $tileSize board changed",
                    midend.textFormat().all { it == '?' || it == '\n' },
                )
                val actualTrace = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tileSize),
                    upstream,
                )
                val expectedTrace = TraceNormalizer.normalize(expected.trace!!, upstream)
                assertEquals(
                    "tile $tileSize final trace",
                    expectedTrace.trimEnd('\n'),
                    actualTrace.trimEnd('\n'),
                )
            }
        }

    @Test(timeout = 120_000)
    fun `a click in a letterbox subtracts the origin`() = OracleClient().use { oracle ->
        val save = oracle.newGame("mines", "9x9n10", SEED).state
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        val geometry = midend.geometryFor(411, 702)
        val gx = 2 * geometry.tileSize + 8 + geometry.tileSize / 2
        val gy = 5 * geometry.tileSize + 8 + geometry.tileSize / 2
        val viewPoints = listOf(
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_BUTTON),
            OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(save, viewPoints, width = 411, height = 702)
        apply(midend, clickPoints(2, 5, geometry.tileSize), geometry.tileSize)
        assertEquals(expected.state.id, midend.id())
        assertEquals(expected.state.state, midend.serialise().serialise())

        // A click in the letterbox margin is outside the board.
        val outside = listOf(
            OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
        )
        val before = midend.stateCount
        val outsideExpected = oracle.pointsSized(
            save,
            outside,
            width = 411,
            height = 702,
        )
        val outsideMidend = Midend.fromSave(Mines, SgtpSave.parse(save))
        apply(outsideMidend, listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)), geometry.tileSize)
        apply(outsideMidend, listOf(OracleClient.Point(0, 0, Buttons.LEFT_RELEASE)), geometry.tileSize)
        assertEquals(1, outsideMidend.stateCount)
        assertTrue(outsideExpected.state.state.contains("NSTATES :1:1"))
        assertEquals(2, before)
    }

    @Test(timeout = 120_000)
    fun `a letterbox at every preset tile size matches the oracle`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("mines", "9x9n10", SEED).state

            for (tileSize in listOf(48, 63, 82, 100)) {
                val board = Mines.computeSize(Mines.decodeParams("9x9n10"), tileSize)
                // Four pixels larger than the board in each direction, so
                // the largest fitting tile is still `tileSize` and the
                // origin is two pixels on both axes.
                val viewWidth = board.width + 4
                val viewHeight = board.height + 4
                val midend = Midend.newGame(Mines, "9x9n10", SEED)
                val geometry = midend.geometryFor(viewWidth, viewHeight)
                assertEquals("tile $tileSize", tileSize, geometry.tileSize)
                assertEquals("origin x at tile $tileSize", 2, geometry.originX)
                assertEquals("origin y at tile $tileSize", 2, geometry.originY)

                val gx = 2 * tileSize + 8 + tileSize / 2
                val gy = 5 * tileSize + 8 + tileSize / 2
                val viewPoints = listOf(
                    OracleClient.Point(gx + 2, gy + 2, Buttons.LEFT_BUTTON),
                    OracleClient.Point(gx + 2, gy + 2, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    save,
                    viewPoints,
                    width = viewWidth,
                    height = viewHeight,
                    recordTrace = true,
                )
                val before = midend.textFormat()
                apply(midend, clickPoints(2, 5, tileSize), tileSize)

                assertEquals("tile $tileSize id", expected.state.id, midend.id())
                assertEquals(
                    "tile $tileSize canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("tile $tileSize status", expected.state.status, midend.status())
                assertEquals("tile $tileSize states", 2, midend.stateCount)
                assertNotEquals("tile $tileSize board changed", before, midend.textFormat())
                assertEquals("tile $tileSize UI", "D0", midend.serialise().value("UI"))
                assertEquals("tile $tileSize TIME", "0", midend.serialise().value("TIME"))
                assertEquals(
                    "tile $tileSize PRIVDESC",
                    "m" + midend.desc.substringAfter(",m"),
                    midend.serialise().value("PRIVDESC"),
                )

                val actualTrace = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, tileSize),
                    upstream,
                )
                val expectedTrace = TraceNormalizer.normalize(expected.trace!!, upstream)
                assertEquals(
                    "tile $tileSize final trace",
                    expectedTrace.trimEnd('\n'),
                    actualTrace.trimEnd('\n'),
                )
            }
        }

    @Test(timeout = 120_000)
    fun `the flag toggles and the flag moves match the oracle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("mines", "9x9n10", SEED).state
            val clicked = oracle.pointsSized(
                start,
                clickPoints(4, 4, tile),
                width = 196,
                height = 196,
            ).state.state

            val expectedFlag = oracle.pointsSized(
                clicked,
                rightPoints(0, 0, tile),
                width = 196,
                height = 196,
            )
            val midend = Midend.fromSave(Mines, SgtpSave.parse(clicked))
            val beforeFlag = (midend.state as Mines.State).grid.copyOf()
            apply(midend, rightPoints(0, 0, tile), tile)
            assertFalse(
                "the flag changes the board",
                (midend.state as Mines.State).grid.contentEquals(beforeFlag),
            )
            assertEquals(expectedFlag.state.state, midend.serialise().serialise())
            assertEquals(3, midend.stateCount)

            val expectedUnflag = oracle.pointsSized(
                expectedFlag.state.state,
                rightPoints(0, 0, tile),
                width = 196,
                height = 196,
            )
            apply(midend, rightPoints(0, 0, tile), tile)
            assertEquals(expectedUnflag.state.state, midend.serialise().serialise())
            assertEquals(4, midend.stateCount)
            assertEquals(-2, (midend.state as Mines.State).grid[0])
        }

    @Test(timeout = 120_000)
    fun `the chord paths match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mines", "9x9n10", SEED).state
        val clicked = oracle.pointsSized(
            start,
            clickPoints(4, 4, tile),
            width = 196,
            height = 196,
        ).state.state

        // Two correct flags and a middle click open the neighbours.
        val flagged = oracle.pointsSized(
            clicked,
            rightPoints(0, 0, tile) + rightPoints(0, 1, tile),
            width = 196,
            height = 196,
        ).state.state
        val expectedChord = oracle.pointsSized(
            flagged,
            middlePoints(1, 0, tile),
            width = 196,
            height = 196,
        )
        val midend = Midend.fromSave(Mines, SgtpSave.parse(flagged))
        val beforeChord = (midend.state as Mines.State).grid.copyOf()
        val beforeStates = midend.stateCount
        apply(midend, middlePoints(1, 0, tile), tile)
        assertEquals(expectedChord.state.id, midend.id())
        assertEquals(expectedChord.state.state, midend.serialise().serialise())
        assertEquals("C1,0", midend.serialise().values("MOVE").last())
        assertEquals("the chord adds one state", beforeStates + 1, midend.stateCount)
        // This chord is correct and every covered neighbour is already
        // flagged, so C accepts `C1,0` and opens nothing. The board
        // stays equal, as the oracle's save says.
        assertTrue((midend.state as Mines.State).grid.contentEquals(beforeChord))

        // A wrong flag makes the chord open the mine, so the board
        // really changes and the death counter rises.
        val wrongFlag = Midend.fromSave(Mines, SgtpSave.parse(clicked))
        apply(wrongFlag, rightPoints(0, 0, tile) + rightPoints(0, 2, tile), tile)
        val wrongSave = wrongFlag.serialise().serialise()
        val wrongExpected = oracle.pointsSized(
            wrongSave,
            middlePoints(1, 1, tile),
            width = 196,
            height = 196,
        )
        val boardBeforeWrong = (wrongFlag.state as Mines.State).grid.copyOf()
        val statesBeforeWrong = wrongFlag.stateCount
        apply(wrongFlag, middlePoints(1, 1, tile), tile)
        assertEquals(wrongExpected.state.id, wrongFlag.id())
        assertEquals(wrongExpected.state.state, wrongFlag.serialise().serialise())
        assertFalse(
            "the wrong-flag chord changes the board",
            (wrongFlag.state as Mines.State).grid.contentEquals(boardBeforeWrong),
        )
        assertEquals(statesBeforeWrong + 1, wrongFlag.stateCount)
        assertEquals(1, (wrongFlag.ui as Mines.Ui).deaths)
        assertEquals("O0,1", wrongFlag.serialise().values("MOVE").last())

        // A right press on the same open cell chords too.
        val rightChord = oracle.pointsSized(
            flagged,
            listOf(OracleClient.Point(1 * tile + 8 + tile / 2, 0 * tile + 8 + tile / 2, Buttons.RIGHT_BUTTON)),
            width = 196,
            height = 196,
        )
        val rightMidend = Midend.fromSave(Mines, SgtpSave.parse(flagged))
        assertEquals(
            KeyOutcome.SomeEffect,
            rightMidend.processKey(
                1 * tile + 8 + tile / 2,
                0 * tile + 8 + tile / 2,
                Buttons.RIGHT_BUTTON,
                tile,
            ),
        )
        assertEquals(rightChord.state.state, rightMidend.serialise().serialise())
        assertEquals("C1,0", rightMidend.serialise().values("MOVE").last())

        // A chord with too few flags is UI-only.
        val tooFew = Midend.fromSave(Mines, SgtpSave.parse(clicked))
        val before = tooFew.stateCount
        val beforeState = tooFew.state
        assertEquals(
            KeyOutcome.SomeEffect,
            tooFew.processKey(
                1 * tile + 8 + tile / 2,
                0 * tile + 8 + tile / 2,
                Buttons.MIDDLE_BUTTON,
                tile,
            ),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            tooFew.processKey(
                1 * tile + 8 + tile / 2,
                0 * tile + 8 + tile / 2,
                Buttons.MIDDLE_RELEASE,
                tile,
            ),
        )
        assertEquals("no state", before, tooFew.stateCount)
        assertEquals("no board change", beforeState, tooFew.state)

        // The positive control: open a covered cell.
        apply(tooFew, clickPoints(0, 3, tile), tile)
        assertEquals(before + 1, tooFew.stateCount)
    }

    @Test(timeout = 120_000)
    fun `a press drag and release make one move`() = OracleClient().use { oracle ->
        val start = oracle.newGame("mines", "9x9n10", SEED).state
        val points = listOf(
            OracleClient.Point(4 * tile + 8 + tile / 2, 4 * tile + 8 + tile / 2, Buttons.LEFT_BUTTON),
            OracleClient.Point(5 * tile + 8 + tile / 2, 5 * tile + 8 + tile / 2, Buttons.LEFT_DRAG),
            OracleClient.Point(5 * tile + 8 + tile / 2, 5 * tile + 8 + tile / 2, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(start, points, width = 196, height = 196)
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        apply(midend, points, tile)
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertEquals(2, midend.stateCount)
        assertEquals("O5,5", midend.serialise().values("MOVE").last())
    }

    @Test
    fun `a real open changes the board and status while an inert press does not`() {
        // 4x4 with one mine: the first open leaves one safe square, so
        // the status text changes as well as the board.
        val midend = Midend.newGame(Mines, "4x4n1", "7")
        val px = 0 * tile + 8 + tile / 2
        val py = 0 * tile + 8 + tile / 2
        val beforeStatus = midend.statusText()
        val beforeGrid = (midend.state as Mines.State).grid.copyOf()

        // An inert press is UI-only: no history, no board, no status.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(px, py, Buttons.LEFT_BUTTON, tile))
        assertEquals(1, midend.stateCount)
        assertTrue((midend.state as Mines.State).grid.contentEquals(beforeGrid))
        assertEquals(beforeStatus, midend.statusText())
        assertFalse(midend.canUndo)

        // The release is the real open: one move, one changed board, a
        // changed status, and a running clock.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(px, py, Buttons.LEFT_RELEASE, tile))
        assertEquals(2, midend.stateCount)
        assertFalse((midend.state as Mines.State).grid.contentEquals(beforeGrid))
        assertNotEquals(beforeStatus, midend.statusText())
        assertEquals("Marked: 0 / 1 (1 safe square remains)", Mines.statusText(midend.state, midend.ui))
        assertTrue(midend.timing)
    }

    @Test
    fun `the cursor walk and both select keys match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("mines", "9x9n10", SEED).state
            val sequence = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT2,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_SELECT,
            )
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Mines, SgtpSave.parse(save))
            for (key in sequence) {
                midend.processKey(0, 0, key, tile)
            }
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(
                listOf("F0,1", "O0,2"),
                midend.serialise().values("MOVE"),
            )

            val ui = midend.ui as Mines.Ui
            assertTrue(ui.curVisible)
            assertEquals(0, ui.curX)
            assertEquals(2, ui.curY)

            // A modified cursor key is not a game key.
            val before = midend.stateCount
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(
                    0,
                    0,
                    Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                    tile,
                ),
            )
            assertEquals(before, midend.stateCount)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, '\t'.code, tile),
            )
            assertEquals(before, midend.stateCount)
        }

    @Test
    fun `request keys is arrow mode three with no labels`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        val save = midend.serialise().serialise()
        for (fullCursor in listOf(false, true)) {
            val expected = oracle.requestKeys(save, fullCursor)
            val actual = Mines.requestKeys(midend.params, fullCursor)
            assertEquals(
                "arrow mode full=$fullCursor",
                if (fullCursor) 1 else 3,
                expected.arrowMode,
            )
            assertEquals(
                "arrow mode full=$fullCursor",
                if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
                actual.arrowMode,
            )
            assertTrue("labels full=$fullCursor", expected.labels.isEmpty())
            assertTrue("labels full=$fullCursor", actual.labels.isEmpty())
        }
    }

    @Test
    fun `undo and redo work from the one-key shortcuts and after a death`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Mines, "9x9n10", SEED)
            apply(midend, clickPoints(4, 4), tile)
            apply(midend, clickPoints(0, 0), tile)
            assertTrue((midend.state as Mines.State).dead)

            val deadSave = midend.serialise().serialise()
            val afterUndo = oracle.keys(deadSave, listOf('u'.code))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'u'.code, tile))
            assertEquals(afterUndo.state, midend.serialise().serialise())
            assertFalse((midend.state as Mines.State).dead)
            assertTrue(midend.canRedo)

            val afterRedo = oracle.keys(midend.serialise().serialise(), listOf('r'.code))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'r'.code, tile))
            assertEquals(afterRedo.state, midend.serialise().serialise())
            assertTrue((midend.state as Mines.State).dead)

            // The UI fake keys are the same shortcuts.
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_UNDO, tile))
            assertFalse((midend.state as Mines.State).dead)
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_REDO, tile))
            assertTrue((midend.state as Mines.State).dead)

            // The public id is not reverted by undo.
            val clicked = Midend.newGame(Mines, "9x9n10", SEED)
            apply(clicked, clickPoints(4, 4), tile)
            val id = clicked.id()
            assertTrue(clicked.undo())
            assertEquals(id, clicked.id())
        }

    @Test
    fun `every vacuous event adds no state and keeps the control valid`() {
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        val before = midend.stateCount

        // A release with no press is dropped by the midend.
        assertEquals(KeyOutcome.Unused, midend.processKey(98, 98, Buttons.LEFT_RELEASE, tile))
        assertEquals(before, midend.stateCount)

        // A middle press on a covered cell is UI-only.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(98, 98, Buttons.MIDDLE_BUTTON, tile))
        assertEquals(before, midend.stateCount)
        midend.processKey(98, 98, Buttons.MIDDLE_RELEASE, tile)
        assertEquals(before, midend.stateCount)

        // A cursor select on an open cell is a no-effect key.
        apply(midend, clickPoints(4, 4), tile)
        val opened = midend.stateCount
        // (1,0) is open with the number 2 on this board.
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile)
        assertEquals(KeyOutcome.NoEffect, midend.processKey(
            0, 0, Buttons.CURSOR_SELECT2, tile,
        ))
        assertEquals(opened, midend.stateCount)

        // A click after a win and after a death adds nothing, but undo
        // still works.
        val win = Midend.newGame(Mines, "4x4n1", "7")
        apply(win, clickPoints(0, 0), tile)
        apply(win, clickPoints(0, 3), tile)
        assertTrue((win.state as Mines.State).won)
        val wonStates = win.stateCount
        assertEquals(KeyOutcome.Unused, win.processKey(98, 98, Buttons.LEFT_BUTTON, tile))
        assertEquals(wonStates, win.stateCount)
        assertTrue(win.undo())

        // The positive control after all the inert events.
        val control = Midend.newGame(Mines, "9x9n10", SEED)
        apply(control, clickPoints(4, 4), tile)
        assertEquals(2, control.stateCount)
    }

    private fun clickPoints(x: Int, y: Int, tileSize: Int = tile): List<OracleClient.Point> {
        val px = x * tileSize + 8 + tileSize / 2
        val py = y * tileSize + 8 + tileSize / 2
        return listOf(
            OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
            OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
        )
    }

    private fun rightPoints(x: Int, y: Int, tileSize: Int = tile): List<OracleClient.Point> {
        val px = x * tileSize + 8 + tileSize / 2
        val py = y * tileSize + 8 + tileSize / 2
        return listOf(
            OracleClient.Point(px, py, Buttons.RIGHT_BUTTON),
            OracleClient.Point(px, py, Buttons.RIGHT_RELEASE),
        )
    }

    private fun middlePoints(x: Int, y: Int, tileSize: Int = tile): List<OracleClient.Point> {
        val px = x * tileSize + 8 + tileSize / 2
        val py = y * tileSize + 8 + tileSize / 2
        return listOf(
            OracleClient.Point(px, py, Buttons.MIDDLE_BUTTON),
            OracleClient.Point(px, py, Buttons.MIDDLE_RELEASE),
        )
    }

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tileSize: Int) {
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, tileSize)
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
