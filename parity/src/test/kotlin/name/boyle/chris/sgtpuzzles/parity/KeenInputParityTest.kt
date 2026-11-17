package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: keen.
 *
 * The layout, real pointer presses at four tile sizes and a letterbox
 * view, the drag and release cases the midend maps for the game, and the
 * keyboard. Every "no change" case sits next to a case that changes the
 * same board, so a dropped input path cannot pass silently.
 *
 * The `pencil-keep-highlight` mouse case is pinned engine-side: the
 * oracle's `pointsSized` has no preferences field.
 */
class KeenInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val save = File(testdata, "saves/initial/keen/6dn.sgtp").readText()

    private fun midend(): Midend = Midend.fromSave(Keen, SgtpSave.parse(save))

    private fun cellPoint(tile: Int, cell: Int): Pair<Int, Int> {
        val border = tile / 2
        return Pair(
            border + (cell % 6) * tile + 2,
            border + (cell / 6) * tile + 2,
        )
    }

    private fun press(tile: Int, cell: Int, button: Int = Buttons.LEFT_BUTTON):
        List<OracleClient.Point> {
        val (x, y) = cellPoint(tile, cell)
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> button
        }
        return listOf(
            OracleClient.Point(x, y, button),
            OracleClient.Point(x, y, release),
        )
    }

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tile: Int): List<KeyOutcome> =
        points.map { point -> midend.processKey(point.x, point.y, point.button, tile) }

    private fun assertSame(what: String, expected: GameState, midend: Midend) {
        assertEquals("$what save", expected.state, midend.serialise().serialise())
        assertEquals("$what id", expected.id, midend.id())
        assertEquals("$what status", expected.status, midend.status())
        assertEquals("$what undo", expected.canUndo, midend.canUndo)
        assertEquals("$what redo", expected.canRedo, midend.canRedo)
    }

    @Test(timeout = 300_000)
    fun `the layout matches every viewport`() = OracleClient().use { oracle ->
        val viewports = listOf(
            411 to 702,
            720 to 1280,
            1024 to 600,
            200 to 200,
            336 to 336,
            1080 to 1920,
        )
        for ((viewWidth, viewHeight) in viewports) {
            val expected = oracle.layout(save, viewWidth, viewHeight)
            val midend = midend()
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            assertEquals("${viewWidth}x$viewHeight board width", expected.width, geometry.width)
            assertEquals("${viewWidth}x$viewHeight board height", expected.height, geometry.height)
            val board = Keen.computeSize(Keen.decodeParams("6dn"), geometry.tileSize)
            assertEquals("${viewWidth}x$viewHeight computed", board.width, geometry.width)
            assertEquals("${viewWidth}x$viewHeight computed height", board.height, geometry.height)
            assertEquals(
                "${viewWidth}x$viewHeight origin x",
                (viewWidth - expected.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "${viewWidth}x$viewHeight origin y",
                (viewHeight - expected.height) / 2,
                geometry.originY,
            )
        }

        // The four exact tile sizes of the input matrix.
        for ((tile, board) in listOf(
            48 to 336,
            63 to 440,
            82 to 574,
            100 to 700,
        )) {
            val expected = oracle.layout(save, board, board)
            assertEquals("tile $tile board", board, expected.width)
            assertEquals("tile $tile height", board, expected.height)
            assertEquals(
                "tile $tile computed",
                board,
                Keen.computeSize(Keen.decodeParams("6dn"), tile).width,
            )
        }
    }

    @Test(timeout = 600_000)
    fun `real presses at four tile sizes match the oracle`() =
        OracleClient().use { oracle ->
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Keen.computeSize(Keen.decodeParams("6dn"), tile).width
                val steps = listOf(
                    press(tile, 0),
                    press(tile, 1),
                    listOf(OracleClient.Point(0, 0, '6'.code)),
                    press(tile, 2, Buttons.RIGHT_BUTTON),
                    press(tile, 2, Buttons.RIGHT_BUTTON),
                    press(tile, 1, Buttons.RIGHT_BUTTON),
                    listOf(OracleClient.Point(0, 0, '6'.code)),
                )
                val midend = midend()
                val points = ArrayList<OracleClient.Point>()
                for ((index, step) in steps.withIndex()) {
                    points += step
                    val expected = oracle.pointsSized(save, points, board, board)
                    val outcomes = apply(midend, step, tile)
                    assertSame("tile $tile step $index", expected.state, midend)
                    when (index) {
                        0 -> {
                            assertEquals(
                                "tile $tile step $index press",
                                KeyOutcome.SomeEffect,
                                outcomes.first(),
                            )
                            assertEquals(
                                "tile $tile step $index release",
                                KeyOutcome.Unused,
                                outcomes.last(),
                            )
                            assertEquals(
                                "tile $tile step $index adds no move",
                                1,
                                midend.stateCount,
                            )
                        }

                        3, 4, 5 -> {
                            assertEquals(
                                "tile $tile step $index press",
                                KeyOutcome.SomeEffect,
                                outcomes.first(),
                            )
                            assertEquals(
                                "tile $tile step $index release",
                                KeyOutcome.Unused,
                                outcomes.last(),
                            )
                            assertEquals(
                                "tile $tile step $index adds no move",
                                2,
                                midend.stateCount,
                            )
                        }

                        1 -> {
                            assertEquals(
                                "tile $tile step $index press",
                                KeyOutcome.SomeEffect,
                                outcomes.first(),
                            )
                            assertEquals(
                                "tile $tile step $index adds no move",
                                1,
                                midend.stateCount,
                            )
                        }

                        2 -> {
                            assertEquals(
                                "tile $tile digit is a move",
                                KeyOutcome.SomeEffect,
                                outcomes.single(),
                            )
                            assertEquals("tile $tile digit moves", 2, midend.stateCount)
                        }

                        6 -> {
                            assertEquals(
                                "tile $tile hidden digit",
                                KeyOutcome.Unused,
                                outcomes.single(),
                            )
                            assertEquals("tile $tile hidden digit adds no move", 2, midend.stateCount)
                        }
                    }
                }

                // The last step enters 6 on a cell that already holds 6,
                // so it is unused with the default preference.
                val final = oracle.pointsSized(save, points, board, board)
                assertEquals(board, final.state.width)
            }
        }

    @Test(timeout = 300_000)
    fun `the letterbox view uses the found tile and origin`() =
        OracleClient().use { oracle ->
            val midend = midend()
            val geometry = midend.geometryFor(411, 702)
            assertEquals(58, geometry.tileSize)
            assertEquals(2, geometry.originX)
            assertEquals(148, geometry.originY)

            val (gx, gy) = cellPoint(geometry.tileSize, 0)
            val viewPoints = listOf(
                OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_RELEASE),
                OracleClient.Point(0, 0, '6'.code),
            )
            val expected = oracle.pointsSized(save, viewPoints, 411, 702)
            for (point in viewPoints) {
                midend.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("letterbox", expected.state, midend)
        }

    @Test(timeout = 300_000)
    fun `the border bands and the cell edges match the oracle`() =
        OracleClient().use { oracle ->
            val tile = 48
            val taps = listOf(
                // The border band, outside the grid.
                0 to 0,
                23 to 23,
                24 to 24,
                // One pixel past the last cell.
                311 to 311,
                312 to 312,
                // The last cell.
                264 to 264,
            )
            for ((x, y) in taps) {
                val points = listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(save, points, 336, 336)
                val midend = midend()
                val outcomes = apply(midend, points, tile)
                assertSame("tap $x,$y", expected.state, midend)
                assertEquals(
                    "tap $x,$y press",
                    KeyOutcome.SomeEffect,
                    outcomes.first(),
                )
                assertEquals("tap $x,$y adds no move", 1, midend.stateCount)
            }

            // At 24,24 the highlight appears on cell (0,0); at 23,23 the
            // click clears it. The pair is the positive control.
            val inside = midend()
            apply(inside, listOf(OracleClient.Point(24, 24, Buttons.LEFT_BUTTON)), tile)
            val insideTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(inside),
                UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
            )
            val outside = midend()
            apply(outside, listOf(OracleClient.Point(23, 23, Buttons.LEFT_BUTTON)), tile)
            val outsideTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(outside),
                UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
            )
            assertNotEquals("inside draws a highlight", insideTrace, outsideTrace)
            assertTrue(
                "inside paints the highlight",
                insideTrace.lines().any { it == "rect 26 26 46 46 4" },
            )
        }

    @Test(timeout = 300_000)
    fun `drag, release, middle press and a hidden digit are unused`() =
        OracleClient().use { oracle ->
            val tile = 48
            val (x, y) = cellPoint(tile, 0)
            val cases = listOf(
                listOf(OracleClient.Point(x, y, Buttons.LEFT_DRAG)),
                listOf(OracleClient.Point(x, y, Buttons.LEFT_RELEASE)),
                listOf(OracleClient.Point(x, y, Buttons.RIGHT_DRAG)),
                listOf(OracleClient.Point(x, y, Buttons.RIGHT_RELEASE)),
                listOf(OracleClient.Point(x, y, Buttons.MIDDLE_BUTTON)),
                listOf(OracleClient.Point(x, y, '6'.code)),
            )
            for (points in cases) {
                val expected = oracle.pointsSized(save, points, 336, 336)
                val midend = midend()
                val outcomes = apply(midend, points, tile)
                assertSame("case $points", expected.state, midend)
                assertTrue(
                    "case $points is unused",
                    outcomes.all { it == KeyOutcome.Unused },
                )
                assertEquals("case $points adds no move", 1, midend.stateCount)
            }

            // The positive control: a press and a digit do change the
            // state and the move count.
            val live = midend()
            apply(live, press(tile, 0), tile)
            assertEquals(
                KeyOutcome.SomeEffect,
                live.processKey(0, 0, '6'.code, tile),
            )
            assertEquals(2, live.stateCount)
            assertEquals(
                "R0,0,6",
                live.serialise().value("MOVE"),
            )
        }

    @Test(timeout = 300_000)
    fun `a pencil mark on a filled cell is unused`() =
        OracleClient().use { oracle ->
            val tile = 48
            // Fill (0,0) with 6, then try a pencil mark there through a
            // cursor highlight, which is the only way to reach it.
            val start = oracle.pointsSized(
                save,
                press(tile, 0) + listOf(OracleClient.Point(0, 0, '6'.code)),
                336,
                336,
            ).state.state
            val expected = oracle.keys(
                start,
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code),
            )
            val midend = Midend.fromSave(Keen, SgtpSave.parse(start))
            midend.processKey(0, 0, Buttons.CURSOR_UP, tile)
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(0, 0, '3'.code, tile),
            )
            assertSame("pencil on filled", expected, midend)
            assertEquals(2, midend.stateCount)
        }

    @Test(timeout = 300_000)
    fun `the keyboard matches the oracle and the modifier rules`() =
        OracleClient().use { oracle ->
            val sequences = listOf(
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_LEFT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code),
                listOf(Buttons.CURSOR_UP, '6'.code, Buttons.CURSOR_SELECT2),
                listOf(Buttons.CURSOR_UP, '6'.code, '\b'.code),
                listOf('M'.code),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_DOWN),
                listOf(Buttons.MOD_NUM_KEYPAD or '4'.code),
                listOf('\t'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code, '3'.code),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(save, sequence)
                val midend = midend()
                for (key in sequence) midend.processKey(0, 0, key, 48)
                assertSame("keys $sequence", expected, midend)
            }

            // A modified arrow still moves the cursor; the game strips
            // the modifier, so the frame shows the highlight moved.
            val plain = midend()
            plain.processKey(0, 0, Buttons.CURSOR_UP, 48)
            val plainTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(plain),
                UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
            )
            val modified = midend()
            modified.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_UP, 48)
            val modifiedTrace = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(modified),
                UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
            )
            assertEquals("a modified arrow moves", plainTrace, modifiedTrace)
        }

    @Test(timeout = 300_000)
    fun `the preference controls a mouse highlight after a digit`() {
        val tile = 48
        val midend = midend()
        apply(midend, press(tile, 0), tile)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, '6'.code, tile))
        // The default keeps the highlight; the state still changes.
        assertNotNull(midend.cursorLocation(48))
        assertEquals(2, midend.stateCount)

        val off = midend()
        (off.ui as Keen.Ui).pencilKeepHighlight = false
        apply(off, press(tile, 0), tile)
        assertEquals(KeyOutcome.SomeEffect, off.processKey(0, 0, '6'.code, tile))
        assertNull(off.cursorLocation(48))
        assertEquals(2, off.stateCount)
    }

    @Test(timeout = 300_000)
    fun `a same digit with the preference on is unused`() {
        val tile = 48
        val midend = midend()
        apply(midend, press(tile, 0), tile)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, '6'.code, tile))
        assertEquals(2, midend.stateCount)
        // The highlight is still visible on the entered cell.
        assertNotNull(midend.cursorLocation(48))
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, '6'.code, tile))
        assertEquals(2, midend.stateCount)

        // With the preference off the same input hides the highlight and
        // still adds no move. The first entry keeps the highlight
        // because the preference is read at input time.
        val off = midend()
        apply(off, press(tile, 0), tile)
        assertEquals(KeyOutcome.SomeEffect, off.processKey(0, 0, '6'.code, tile))
        assertEquals(2, off.stateCount)
        assertNotNull(off.cursorLocation(48))
        (off.ui as Keen.Ui).pencilKeepHighlight = false
        assertEquals(KeyOutcome.SomeEffect, off.processKey(0, 0, '6'.code, tile))
        assertEquals(2, off.stateCount)
        assertNull(off.cursorLocation(48))
    }
}
