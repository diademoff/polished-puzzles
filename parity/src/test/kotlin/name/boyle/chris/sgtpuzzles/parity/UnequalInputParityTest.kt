package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unequal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: unequal.
 *
 * The layout, real pointer presses at four tile sizes and a letterbox
 * view, the keyboard at both modes, the preference, and the vacuity
 * guards. Every "no change" case sits next to a case that changes the
 * same board.
 */
class UnequalInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun saveFor(params: String): String =
        File(testdata, "saves/initial/unequal/${GenTraces.fileName(params)}.sgtp").readText()

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun midend(params: String = "5de"): Midend =
        Midend.fromSave(Unequal, SgtpSave.parse(saveFor(params)))

    private fun center(x: Int, y: Int, tile: Int): Pair<Int, Int> {
        val border = tile / 2
        val square = tile + border
        return Pair(
            border + x * square + tile / 2,
            border + y * square + tile / 2,
        )
    }

    private fun click(x: Int, y: Int, tile: Int, button: Int = Buttons.LEFT_BUTTON):
        List<OracleClient.Point> {
        val (px, py) = center(x, y, tile)
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> button
        }
        return listOf(
            OracleClient.Point(px, py, button),
            OracleClient.Point(px, py, release),
        )
    }

    /** The centre of the band to the right or below a cell. */
    private fun band(x: Int, y: Int, tile: Int, right: Boolean): Pair<Int, Int> {
        val border = tile / 2
        val square = tile + border
        val ox = border + x * square
        val oy = border + y * square
        return if (right) {
            Pair(ox + tile + tile / 4, oy + tile / 2)
        } else {
            Pair(ox + tile / 2, oy + tile + tile / 4)
        }
    }

    private fun press(x: Int, y: Int, button: Int, tile: Int): List<OracleClient.Point> {
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            Buttons.MIDDLE_BUTTON -> Buttons.MIDDLE_RELEASE
            else -> button
        }
        return listOf(
            OracleClient.Point(x, y, button),
            OracleClient.Point(x, y, release),
        )
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tile: Int,
    ): List<KeyOutcome> = points.map { point ->
        midend.processKey(point.x, point.y, point.button, tile)
    }

    private fun assertSame(what: String, expected: GameState, midend: Midend) {
        assertEquals("$what save", expected.state, midend.serialise().serialise())
        assertEquals("$what id", expected.id, midend.id())
        assertEquals("$what status", expected.status, midend.status())
        assertEquals("$what undo", expected.canUndo, midend.canUndo)
        assertEquals("$what redo", expected.canRedo, midend.canRedo)
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }

    @Test(timeout = 300_000)
    fun `the layout matches every viewport and the four tiles`() =
        OracleClient().use { oracle ->
            val viewports = listOf(
                411 to 702,
                720 to 1280,
                1024 to 600,
                200 to 200,
                1080 to 1920,
            )
            for (params in Unequal.presets().map { it.params }) {
                val start = saveFor(params)
                for ((viewWidth, viewHeight) in viewports) {
                    val expected = oracle.layout(start, viewWidth, viewHeight)
                    val geometry = midend(params).geometryFor(viewWidth, viewHeight)
                    assertEquals(
                        "$params ${viewWidth}x$viewHeight width",
                        expected.width,
                        geometry.width,
                    )
                    assertEquals(
                        "$params ${viewWidth}x$viewHeight height",
                        expected.height,
                        geometry.height,
                    )
                    val board = Unequal.computeSize(
                        Unequal.decodeParams(params),
                        geometry.tileSize,
                    )
                    assertEquals(
                        "$params ${viewWidth}x$viewHeight computed",
                        board.width,
                        geometry.width,
                    )
                    assertEquals(
                        "$params ${viewWidth}x$viewHeight origin x",
                        (viewWidth - expected.width) / 2,
                        geometry.originX,
                    )
                    assertEquals(
                        "$params ${viewWidth}x$viewHeight origin y",
                        (viewHeight - expected.height) / 2,
                        geometry.originY,
                    )
                }
            }

            // The four exact tile sizes for every order.
            val sizes = mapOf(
                4 to listOf(48 to 312, 63 to 407, 82 to 533, 100 to 650),
                5 to listOf(48 to 384, 63 to 501, 82 to 656, 100 to 800),
                6 to listOf(48 to 456, 63 to 595, 82 to 779, 100 to 950),
                7 to listOf(48 to 528, 63 to 689, 82 to 902, 100 to 1100),
            )
            for ((order, table) in sizes) {
                val file = when (order) {
                    4 -> "4de"
                    5 -> "5de"
                    6 -> "6de"
                    else -> "7dk"
                }
                for ((tile, board) in table) {
                    val expected = oracle.layout(saveFor(file), board, board)
                    assertEquals("$order/$tile", board, expected.width)
                    assertEquals(
                        "$order/$tile computed",
                        board,
                        Unequal.computeSize(Unequal.decodeParams(file), tile).width,
                    )
                }
            }
        }

    @Test(timeout = 600_000)
    fun `real presses at four tile sizes match the oracle`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Unequal.computeSize(Unequal.decodeParams("5de"), tile).width
                val steps = listOf(
                    click(0, 0, tile),
                    listOf(OracleClient.Point(0, 0, '2'.code)),
                    click(1, 1, tile, Buttons.RIGHT_BUTTON),
                    listOf(OracleClient.Point(0, 0, '3'.code)),
                    click(2, 2, tile),
                    click(2, 2, tile),
                    listOf(OracleClient.Point(0, 0, '1'.code)),
                )
                val midend = midend()
                val points = ArrayList<OracleClient.Point>()
                var before = midend.serialise().serialise()
                for ((index, step) in steps.withIndex()) {
                    points += step
                    val expected = oracle.pointsSized(start, points, board, board)
                    val outcomes = apply(midend, step, tile)
                    assertSame("tile $tile step $index", expected.state, midend)
                    if (index == 1 || index == 3 || index == 6) {
                        assertNotEquals(
                            "tile $tile step $index changes the board",
                            before,
                            midend.serialise().serialise(),
                        )
                        assertEquals(
                            "tile $tile step $index keeps the status",
                            0,
                            midend.status(),
                        )
                    }
                    before = midend.serialise().serialise()
                    if (step.size == 2) {
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
                    }
                    when (index) {
                        0 -> assertEquals("the first click adds no state", 1, midend.stateCount)
                        4, 5 -> assertEquals(
                            "tile $tile step $index adds no state",
                            3,
                            midend.stateCount,
                        )

                        1 -> {
                            assertEquals("R0,0,2", midend.serialise().values("MOVE").last())
                            assertEquals(2, midend.stateCount)
                            assertEquals(2, (midend.state as Unequal.State).nums[0])
                        }

                        3 -> {
                            assertEquals("P1,1,3", midend.serialise().values("MOVE").last())
                            assertEquals(3, midend.stateCount)
                            assertTrue((midend.state as Unequal.State).hint(1, 1, 2))
                        }

                        6 -> {
                            assertEquals("R2,2,1", midend.serialise().values("MOVE").last())
                            assertEquals(4, midend.stateCount)
                            assertEquals(1, (midend.state as Unequal.State).nums[12])
                        }
                    }
                }

                val final = oracle.pointsSized(start, points, board, board, recordTrace = true)
                assertLines(
                    "tile $tile final trace",
                    normalize(final.trace!!),
                    normalize(KotlinTraceSink.stateTrace(midend, tile)),
                )
            }
        }

    @Test(timeout = 600_000)
    fun `the letterbox view uses the found tile and origin for every preset`() =
        OracleClient().use { oracle ->
            for (params in oracle.presets("unequal").map { it.params }) {
                val start = saveFor(params)
                val midend = midend(params)
                val geometry = midend.geometryFor(411, 702)
                assertEquals(
                    "$params tile",
                    oracle.layout(start, 411, 702).width,
                    geometry.width,
                )

                val (px, py) = center(0, 0, geometry.tileSize)
                val points = listOf(
                    OracleClient.Point(
                        px + geometry.originX,
                        py + geometry.originY,
                        Buttons.LEFT_BUTTON,
                    ),
                    OracleClient.Point(
                        px + geometry.originX,
                        py + geometry.originY,
                        Buttons.LEFT_RELEASE,
                    ),
                )
                val expected = oracle.pointsSized(start, points, 411, 702)
                for (point in points) {
                    midend.processKey(
                        point.x - geometry.originX,
                        point.y - geometry.originY,
                        point.button,
                        geometry.tileSize,
                    )
                }
                assertSame("$params letterbox", expected.state, midend)
                assertEquals(
                    "$params letterbox adds no state",
                    1,
                    midend.stateCount,
                )
                assertTrue(
                    "$params letterbox origin is nonzero",
                    geometry.originX > 0 || geometry.originY > 0,
                )
            }

            // A real digit in the letterbox, at a nonzero origin.
            val start = saveFor("5de")
            val midend = midend()
            val geometry = midend.geometryFor(411, 702)
            val (px, py) = center(0, 0, geometry.tileSize)
            val points = listOf(
                OracleClient.Point(px + geometry.originX, py + geometry.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(px + geometry.originX, py + geometry.originY, Buttons.LEFT_RELEASE),
                OracleClient.Point(0, 0, '4'.code),
            )
            val expected = oracle.pointsSized(start, points, 411, 702)
            for (point in points) {
                midend.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("letterbox digit", expected.state, midend)
            assertEquals("R0,0,4", midend.serialise().values("MOVE").last())
            assertNotNull("the letterbox click shows the cursor", midend.cursorLocation(geometry.tileSize))
        }

    @Test(timeout = 600_000)
    fun `the clue band cycle matches the oracle at every tile size`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Unequal.computeSize(Unequal.decodeParams("5de"), tile).width
                val (rightX, rightY) = band(2, 2, tile, right = true)
                val (downX, downY) = band(1, 0, tile, right = false)
                val steps = listOf(
                    press(rightX, rightY, Buttons.LEFT_BUTTON, tile),
                    press(rightX, rightY, Buttons.LEFT_BUTTON, tile),
                    press(rightX, rightY, Buttons.MIDDLE_BUTTON, tile),
                    press(downX, downY, Buttons.RIGHT_BUTTON, tile),
                )
                val moves = listOf("F2,2,2048", "F2,2,2048", "F2,2,2048", "F1,0,4096")
                val midend = midend()
                val points = ArrayList<OracleClient.Point>()
                for ((index, step) in steps.withIndex()) {
                    points += step
                    val expected = oracle.pointsSized(start, points, board, board)
                    val outcomes = apply(midend, step, tile)
                    assertSame("tile $tile band $index", expected.state, midend)
                    assertEquals(
                        "tile $tile band $index press",
                        KeyOutcome.SomeEffect,
                        outcomes.first(),
                    )
                    assertEquals(
                        "tile $tile band $index release",
                        KeyOutcome.Unused,
                        outcomes.last(),
                    )
                    assertEquals(
                        "tile $tile band $index move",
                        moves[index],
                        midend.serialise().values("MOVE").last(),
                    )
                    assertEquals(
                        "tile $tile band $index count",
                        index + 2,
                        midend.stateCount,
                    )
                    val flags = (midend.state as Unequal.State).flags
                    when (index) {
                        0 -> assertNotEquals(
                            "tile $tile the first click is spent",
                            0,
                            flags[2 * 5 + 2] and Unequal.F_SPENT_RIGHT,
                        )

                        1 -> assertEquals(
                            "tile $tile the second click clears the spent bit",
                            0,
                            flags[2 * 5 + 2] and Unequal.F_SPENT_RIGHT,
                        )

                        2 -> assertNotEquals(
                            "tile $tile the middle click spends again",
                            0,
                            flags[2 * 5 + 2] and Unequal.F_SPENT_RIGHT,
                        )

                        3 -> assertNotEquals(
                            "tile $tile the down clue spends",
                            0,
                            flags[0 * 5 + 1] and Unequal.F_SPENT_DOWN,
                        )
                    }
                    assertEquals("tile $tile band keeps the status", 0, midend.status())
                }

                val final = oracle.pointsSized(start, points, board, board, recordTrace = true)
                assertLines(
                    "tile $tile band final trace",
                    normalize(final.trace!!),
                    normalize(KotlinTraceSink.stateTrace(midend, tile)),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `a clue band in the letterbox view changes one move`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            val midend = midend()
            val geometry = midend.geometryFor(411, 702)
            val (px, py) = band(2, 2, geometry.tileSize, right = true)
            val points = press(
                px + geometry.originX,
                py + geometry.originY,
                Buttons.LEFT_BUTTON,
                geometry.tileSize,
            )
            val expected = oracle.pointsSized(start, points, 411, 702)
            for (point in points) {
                midend.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("letterbox band", expected.state, midend)
            assertEquals("F2,2,2048", midend.serialise().values("MOVE").last())
            assertEquals(2, midend.stateCount)
            assertEquals(0, midend.status())
        }

    @Test(timeout = 300_000)
    fun `duplicate digits from real input mark both cells`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            val tile = 48
            val points = ArrayList<OracleClient.Point>()
            val midend = midend()
            val steps = listOf(
                click(0, 0, tile),
                listOf(OracleClient.Point(0, 0, '1'.code)),
                click(0, 1, tile),
                listOf(OracleClient.Point(0, 0, '1'.code)),
            )
            for ((index, step) in steps.withIndex()) {
                points += step
                val expected = oracle.pointsSized(start, points, 384, 384)
                apply(midend, step, tile)
                assertSame("error step $index", expected.state, midend)
            }
            assertEquals(3, midend.stateCount)
            assertEquals(0, midend.status())
            val state = midend.state as Unequal.State
            assertEquals(1, state.nums[0])
            assertEquals(1, state.nums[5])
            assertNotEquals(0, state.flags[0] and Unequal.F_ERROR)
            assertNotEquals(0, state.flags[5] and Unequal.F_ERROR)

            val final = oracle.pointsSized(start, points, 384, 384, recordTrace = true)
            assertLines(
                "duplicate frame",
                normalize(final.trace!!),
                normalize(KotlinTraceSink.stateTrace(midend, tile)),
            )

            // The positive control: a different digit clears both marks.
            val replaced = listOf(OracleClient.Point(0, 0, '2'.code))
            points += replaced
            val expected = oracle.pointsSized(start, points, 384, 384)
            apply(midend, replaced, tile)
            assertSame("the duplicate clears", expected.state, midend)
            assertEquals(4, midend.stateCount)
            val cleared = midend.state as Unequal.State
            assertEquals(0, cleared.flags[0] and Unequal.F_ERROR)
            assertEquals(0, cleared.flags[5] and Unequal.F_ERROR)
        }

    @Test(timeout = 300_000)
    fun `the cursor and the keys match the oracle at every tile`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            val sequence = listOf(
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                '2'.code,
            )
            val expected = oracle.keys(start, sequence)
            for (tile in listOf(48, 63, 82, 100)) {
                val midend = midend()
                for (key in sequence) midend.processKey(0, 0, key, tile)
                assertSame("tile $tile keys", expected, midend)
                val border = tile / 2
                val square = tile + border
                assertEquals(
                    "tile $tile cursor rect",
                    Game.CursorRect(border + square, border + square, tile, tile),
                    midend.cursorLocation(tile),
                )
            }

            // A hidden cursor becomes visible on the first move. At the
            // top-left edge a visible cursor is a no-effect move.
            val hidden = midend()
            assertEquals(
                KeyOutcome.SomeEffect,
                hidden.processKey(0, 0, Buttons.CURSOR_UP, 48),
            )
            assertNotNull("the cursor is shown", hidden.cursorLocation(48))
            val before = hidden.serialise().serialise()
            assertEquals(
                KeyOutcome.NoEffect,
                hidden.processKey(0, 0, Buttons.CURSOR_UP, 48),
            )
            assertEquals(before, hidden.serialise().serialise())
            assertEquals(
                "the clamped cursor stays in place",
                Game.CursorRect(24, 24, 48, 48),
                hidden.cursorLocation(48),
            )
        }

    @Test(timeout = 600_000)
    fun `the keyboard matches the oracle in both modes`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                "5de" to listOf(
                    listOf(Buttons.CURSOR_UP, '3'.code),
                    listOf(Buttons.CURSOR_UP, '3'.code, '\b'.code),
                    listOf(Buttons.CURSOR_UP, '0'.code),
                    listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '2'.code),
                    listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2, '2'.code),
                    listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT),
                    listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN,
                        Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
                    listOf(Buttons.MOD_SHFT or Buttons.CURSOR_UP),
                    listOf(Buttons.MOD_NUM_KEYPAD or '4'.code),
                    listOf('M'.code),
                    listOf('m'.code),
                    listOf('H'.code),
                    listOf('h'.code),
                    listOf('\t'.code),
                ),
                "5adk" to listOf(
                    listOf(Buttons.CURSOR_RIGHT, '1'.code),
                    listOf(Buttons.CURSOR_RIGHT, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
                    listOf(Buttons.CURSOR_DOWN, Buttons.MOD_CTRL or Buttons.CURSOR_UP),
                    listOf(Buttons.CURSOR_UP, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN),
                    listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '2'.code),
                    listOf('M'.code),
                    listOf('H'.code),
                ),
            )
            for ((params, sequences) in cases) {
                val start = saveFor(params)
                for (sequence in sequences) {
                    val expected = oracle.keys(start, sequence)
                    val midend = midend(params)
                    for (key in sequence) midend.processKey(0, 0, key, 48)
                    assertSame("$params $sequence", expected, midend)
                }
            }

            // The modified arrow toggles a clue without moving the
            // cursor. At (2,2) the right clue is self in Unequal mode.
            val start = saveFor("5de")
            val sequence = List(2) { Buttons.CURSOR_RIGHT } +
                List(2) { Buttons.CURSOR_DOWN } +
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
            val expected = oracle.keys(start, sequence)
            assertEquals("F2,2,2048", SgtpSave.parse(expected.state).values("MOVE").last())
            val midend = midend()
            for (key in sequence) midend.processKey(0, 0, key, 48)
            assertSame("modified arrow", expected, midend)
            assertEquals(
                "the cursor did not move",
                Game.CursorRect(2 * 72 + 24, 2 * 72 + 24, 48, 48),
                midend.cursorLocation(48),
            )

            // A second modified arrow clears the spent bit.
            val second = oracle.keys(expected.state, sequence)
            assertEquals("F2,2,2048", SgtpSave.parse(second.state).values("MOVE").last())
            val again = Midend.fromSave(Unequal, SgtpSave.parse(expected.state))
            for (key in sequence) again.processKey(0, 0, key, 48)
            assertSame("spent cycle", second, again)
            assertEquals(
                0,
                (again.state as Unequal.State).flags[12] and Unequal.F_SPENT_RIGHT,
            )
        }

    @Test(timeout = 600_000)
    fun `undo and redo after input match the oracle`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            val digitSeq = listOf(Buttons.CURSOR_UP, '3'.code)
            val afterDigit = oracle.keys(start, digitSeq).state
            val digit = midend()
            for (key in digitSeq) digit.processKey(0, 0, key, 48)
            assertSame("digit", oracle.id(afterDigit), digit)
            assertTrue(digit.undo())
            assertSame("digit undo", oracle.undo(afterDigit), digit)
            assertTrue(digit.redo())
            assertSame("digit redo", oracle.id(afterDigit), digit)

            val mSeq = listOf('M'.code)
            val afterM = oracle.keys(start, mSeq).state
            val m = midend()
            m.processKey(0, 0, 'M'.code, 48)
            assertSame("M", oracle.id(afterM), m)
            assertTrue(m.undo())
            assertSame("M undo", oracle.undo(afterM), m)
            assertTrue(m.redo())

            val hSeq = listOf('H'.code)
            val afterH = oracle.keys(start, hSeq).state
            val h = midend()
            h.processKey(0, 0, 'H'.code, 48)
            assertSame("H", oracle.id(afterH), h)
            assertTrue(h.undo())
            assertSame("H undo", oracle.undo(afterH), h)
            assertTrue(h.redo())

            val fSeq = List(2) { Buttons.CURSOR_RIGHT } +
                List(2) { Buttons.CURSOR_DOWN } +
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
            val afterF = oracle.keys(start, fSeq).state
            val f = midend()
            for (key in fSeq) f.processKey(0, 0, key, 48)
            assertSame("F", oracle.id(afterF), f)
            assertTrue(f.undo())
            assertSame("F undo", oracle.undo(afterF), f)
            assertTrue(f.redo())

            val afterSolve = oracle.solve(start).state
            val solved = midend()
            assertTrue(solved.solve())
            assertSame("solve", oracle.id(afterSolve), solved)
            assertTrue(solved.undo())
            assertSame("solve undo", oracle.undo(afterSolve), solved)
            assertTrue(solved.redo())
            assertFalse(solved.takeCompletedEvent())
        }

    @Test(timeout = 300_000)
    fun `completion by real input raises one event`() =
        OracleClient().use { oracle ->
            val desc = "0D,0,0,0,0,0,0R,0,0U,"
            val records = listOf(
                "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection",
                "VERSION" to "1",
                "GAME" to "Unequal",
                "PARAMS" to "3de",
                "CPARAMS" to "3de",
                "SEED" to "1",
                "DESC" to desc,
                "NSTATES" to "1",
                "STATEPOS" to "1",
            ).joinToString("") { (key, value) ->
                "${key.padEnd(8)}:${value.length}:$value\n"
            }
            val sequence = listOf(
                Buttons.CURSOR_UP, '3'.code,
                Buttons.CURSOR_RIGHT, '2'.code,
                Buttons.CURSOR_RIGHT, '1'.code,
                Buttons.CURSOR_DOWN, '2'.code,
                Buttons.CURSOR_LEFT, '3'.code,
                Buttons.CURSOR_LEFT, '1'.code,
                Buttons.CURSOR_DOWN, '2'.code,
                Buttons.CURSOR_RIGHT, '1'.code,
                Buttons.CURSOR_RIGHT, '3'.code,
            )
            val expected = oracle.keys(records, sequence)
            assertEquals(1, expected.status)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(records))
            for (key in sequence) midend.processKey(0, 0, key, 48)
            assertSame("completion", expected, midend)
            assertTrue("one completion event", midend.takeCompletedEvent())
            assertFalse("one event only", midend.takeCompletedEvent())
        }

    @Test(timeout = 300_000)
    fun `the pencil-keep-highlight preference is frontend only`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")

            // The default is the C value, and a load keeps it.
            val fresh = midend()
            assertTrue((fresh.ui as Unequal.Ui).pencilKeepHighlight)
            val loaded = Midend.fromSave(Unequal, SgtpSave.parse(start))
            assertTrue((loaded.ui as Unequal.Ui).pencilKeepHighlight)
            assertNull("no UI record", SgtpSave.parse(start).value("UI"))

            // With the preference on, entering the current value with a
            // mouse highlight is a no-op and the highlight stays.
            val kept = midend()
            val keptPoints = click(0, 0, 48) + listOf(OracleClient.Point(0, 0, '3'.code))
            apply(kept, keptPoints, 48)
            val keptState = kept.serialise().serialise()
            val keptCount = kept.stateCount
            assertEquals(KeyOutcome.Unused, kept.processKey(0, 0, '3'.code, 48))
            assertEquals(keptState, kept.serialise().serialise())
            assertEquals(keptCount, kept.stateCount)
            assertNotNull("the highlight stays", kept.cursorLocation(48))

            // The preference is read on every key. Turn it off while
            // the mouse highlight is still shown: the same key is then a
            // UI update that hides the highlight, with no state.
            val hidden = midend()
            apply(hidden, keptPoints, 48)
            (hidden.ui as Unequal.Ui).pencilKeepHighlight = false
            assertNotNull("the highlight is still shown", hidden.cursorLocation(48))
            assertEquals(KeyOutcome.SomeEffect, hidden.processKey(0, 0, '3'.code, 48))
            assertEquals(keptCount, hidden.stateCount)
            assertEquals(keptState, hidden.serialise().serialise())
            assertNull("the highlight hides", hidden.cursorLocation(48))

            // The same preference also hides the highlight after a real
            // entry, which is the C Android behavior.
            val afterEntry = midend()
            (afterEntry.ui as Unequal.Ui).pencilKeepHighlight = false
            apply(afterEntry, click(0, 0, 48), 48)
            assertEquals(KeyOutcome.SomeEffect, afterEntry.processKey(0, 0, '2'.code, 48))
            assertNull("the entry hides the highlight", afterEntry.cursorLocation(48))

            // The drawing reads the highlight state, so the same input
            // frame loses the numeric highlight fill. The oracle cannot
            // set a preference for pointsSized, so this is engine only.
            val framePoints = click(0, 0, 48) + listOf(OracleClient.Point(0, 0, '3'.code))
            val keptFrame = normalize(KotlinTraceSink.warmFrame(midend(), framePoints, 48))
            val hiddenMidend = midend()
            (hiddenMidend.ui as Unequal.Ui).pencilKeepHighlight = false
            val hiddenFrame = normalize(
                KotlinTraceSink.warmFrame(hiddenMidend, framePoints, 48),
            )
            assertTrue(
                "the kept frame fills the highlighted cell",
                keptFrame.lines().contains("rect 24 24 48 48 6"),
            )
            assertFalse(
                "the hidden frame does not highlight the cell",
                hiddenFrame.lines().contains("rect 24 24 48 48 6"),
            )
            assertTrue(
                "the digit is drawn in both frames",
                keptFrame.contains("\"3\"") && hiddenFrame.contains("\"3\""),
            )
            assertNotEquals(keptFrame, hiddenFrame)

            // The keyboard path sets `hcursor`, so the preference cannot
            // change it: the C oracle agrees for both values.
            val keySeq = listOf(Buttons.CURSOR_UP, '3'.code, '3'.code)
            val onPrefs = oracle.keys(start, keySeq, prefs = "pencil-keep-highlight=true")
            val offPrefs = oracle.keys(start, keySeq, prefs = "pencil-keep-highlight=false")
            assertEquals(onPrefs.state, offPrefs.state)
            val local = midend()
            (local.ui as Unequal.Ui).pencilKeepHighlight = false
            for (key in keySeq) local.processKey(0, 0, key, 48)
            assertSame("keyboard ignores the preference", offPrefs, local)
        }

    @Test(timeout = 300_000)
    fun `inert clicks and keys change nothing and the positive control changes`() =
        OracleClient().use { oracle ->
            val start = saveFor("5de")
            val tile = 48

            // A click in the border, one pixel outside the first cell,
            // one past the last cell, a lone drag, and a lone release.
            val cases = listOf(
                "border" to listOf(
                    OracleClient.Point(10, 10, Buttons.LEFT_BUTTON),
                    OracleClient.Point(10, 10, Buttons.LEFT_RELEASE),
                ),
                "outside first" to listOf(
                    OracleClient.Point(23, 23, Buttons.LEFT_BUTTON),
                    OracleClient.Point(23, 23, Buttons.LEFT_RELEASE),
                ),
                "past last" to listOf(
                    OracleClient.Point(384, 384, Buttons.LEFT_BUTTON),
                    OracleClient.Point(384, 384, Buttons.LEFT_RELEASE),
                ),
                "gap corner" to listOf(
                    OracleClient.Point(24 + 48 + 12, 24 + 48 + 12, Buttons.LEFT_BUTTON),
                    OracleClient.Point(24 + 48 + 12, 24 + 48 + 12, Buttons.LEFT_RELEASE),
                ),
                "lone drag" to listOf(
                    OracleClient.Point(48, 48, Buttons.LEFT_DRAG),
                ),
                "lone release" to listOf(
                    OracleClient.Point(48, 48, Buttons.LEFT_RELEASE),
                ),
                "digit without highlight" to listOf(
                    OracleClient.Point(0, 0, '3'.code),
                ),
            )
            for ((name, points) in cases) {
                val expected = oracle.pointsSized(start, points, 384, 384)
                val midend = midend()
                val outcomes = apply(midend, points, tile)
                assertSame(name, expected.state, midend)
                assertEquals("$name adds no state", 1, midend.stateCount)
                assertEquals("$name changes no board", start, midend.serialise().serialise())
                if (name != "border" && name != "outside first" &&
                    name != "past last"
                ) {
                    assertTrue("$name is unused", outcomes.all { it == KeyOutcome.Unused })
                }
            }

            // A press/drag/release adds no state, because only the press
            // reaches the game.
            val (px, py) = center(0, 0, tile)
            val gesture = listOf(
                OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
                OracleClient.Point(px + 20, py + 20, Buttons.LEFT_DRAG),
                OracleClient.Point(px + 20, py + 20, Buttons.LEFT_RELEASE),
            )
            val gestureExpected = oracle.pointsSized(start, gesture, 384, 384)
            val gestureMidend = midend()
            apply(gestureMidend, gesture, tile)
            assertSame("gesture", gestureExpected.state, gestureMidend)
            assertEquals("gesture adds no state", 1, gestureMidend.stateCount)

            // The letterbox is inert outside the board.
            val letterbox = midend()
            val geometry = letterbox.geometryFor(411, 702)
            val outsidePoints = listOf(
                OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
            )
            val outsideExpected = oracle.pointsSized(start, outsidePoints, 411, 702)
            for (point in outsidePoints) {
                letterbox.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("letterbox border", outsideExpected.state, letterbox)
            assertEquals("letterbox border adds no state", 1, letterbox.stateCount)

            // An immutable cell: a real click clears the highlight and a
            // digit then has nothing to act on. The keyboard reaches the
            // immutable guard and is unused with the highlight shown.
            val immutableSeq = List(4) { Buttons.CURSOR_RIGHT } +
                List(4) { Buttons.CURSOR_DOWN } + listOf('1'.code)
            val immutableExpected = oracle.keys(start, immutableSeq)
            val immutable = midend()
            for (key in immutableSeq) immutable.processKey(0, 0, key, 48)
            assertSame("immutable", immutableExpected, immutable)
            assertEquals("immutable adds no state", 1, immutable.stateCount)

            // A pencil entry on a filled cell is unused. The positive
            // control is the same pencil entry on an empty cell.
            val filled = oracle.keys(start, listOf(Buttons.CURSOR_UP, '2'.code)).state
            val pencil = Midend.fromSave(Unequal, SgtpSave.parse(filled))
            pencil.processKey(0, 0, Buttons.CURSOR_UP, 48)
            pencil.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            assertEquals(KeyOutcome.Unused, pencil.processKey(0, 0, '3'.code, 48))
            assertEquals(2, pencil.stateCount)
            val empty = oracle.keys(start, listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '2'.code))
            val emptyMidend = midend()
            emptyMidend.processKey(0, 0, Buttons.CURSOR_UP, 48)
            emptyMidend.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            assertEquals(KeyOutcome.SomeEffect, emptyMidend.processKey(0, 0, '2'.code, 48))
            assertSame("empty pencil", empty, emptyMidend)

            // The positive control next to every inert case: a click and
            // a digit change the board, the status stays open, and the
            // move count grows.
            val live = midend()
            apply(live, click(0, 0, tile), tile)
            assertEquals(KeyOutcome.SomeEffect, live.processKey(0, 0, '3'.code, tile))
            assertEquals(2, live.stateCount)
            assertEquals("R0,0,3", live.serialise().values("MOVE").last())
            assertEquals(3, (live.state as Unequal.State).nums[0])
            assertEquals(0, live.status())
        }

    @Test(timeout = 300_000)
    fun `requestKeys match the oracle and the full cursor hint changes nothing`() =
        OracleClient().use { oracle ->
            val presets = oracle.presets("unequal").map { it.params }
            for (params in presets + "") {
                val start = if (params.isEmpty()) {
                    saveFor("4de")
                } else {
                    saveFor(params)
                }
                val decoded = if (params.isEmpty()) {
                    Unequal.defaultParams()
                } else {
                    Unequal.decodeParams(params)
                }
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(start, fullCursor)
                    val actual = Unequal.requestKeys(decoded, fullCursor)
                    assertEquals("$params/$fullCursor mode", 2, expected.arrowMode)
                    assertEquals("$params/$fullCursor enum", ArrowMode.ARROWS_LEFT, actual.arrowMode)
                    assertEquals("$params/$fullCursor count", expected.labels.size, actual.labels.size)
                    assertEquals(
                        "$params/$fullCursor count is order plus three",
                        (decoded as Unequal.Params).order + 3,
                        actual.labels.size,
                    )
                    for (i in expected.labels.indices) {
                        assertEquals(
                            "$params/$fullCursor label $i",
                            expected.labels[i].label,
                            actual.labels[i].name,
                        )
                        assertEquals(
                            "$params/$fullCursor button $i",
                            expected.labels[i].button,
                            actual.labels[i].button,
                        )
                        assertEquals(
                            "$params/$fullCursor arrows $i",
                            expected.labels[i].needsArrows,
                            actual.labels[i].needsArrows,
                        )
                        assertFalse(
                            "$params/$fullCursor arrows $i",
                            actual.labels[i].needsArrows,
                        )
                    }
                }
            }
        }

}
