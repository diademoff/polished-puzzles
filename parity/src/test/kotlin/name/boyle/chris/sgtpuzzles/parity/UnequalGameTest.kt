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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Level: semantic and strict where a frame is compared. Game: unequal.
 *
 * The new game, the status, the text form, the SOLVE and hint strings,
 * the difficulty result, real clicks and keys, number and pencil
 * entry, the clue toggles, the `P`/`R`/`F`/`M`/`H`/`S` moves, undo and
 * redo, completion, and the palette and size tables.
 */
class UnequalGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun save(params: String = "5de", seed: String = SEED): String =
        Midend.newGame(Unequal, params, seed).serialise().serialise()

    private fun handSave(
        desc: String,
        params: String = "4de",
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection"
        records += "VERSION" to "1"
        records += "GAME" to "Unequal"
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "SEED" to "1"
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        records += history
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun withoutAux(text: String): String =
        text.lineSequence().filterNot { it.startsWith("AUXINFO") }
            .joinToString("\n", postfix = "\n")

    private fun grid(
        order: Int,
        flags: Map<Int, String> = emptyMap(),
        numbers: Map<Int, Int> = emptyMap(),
    ): String {
        val out = StringBuilder()
        for (i in 0 until order * order) {
            out.append(numbers[i] ?: 0)
            out.append(flags[i] ?: "")
            out.append(',')
        }
        return out.toString()
    }

    private fun center(x: Int, y: Int, tile: Int): Pair<Int, Int> {
        val border = tile / 2
        val square = tile + border
        return Pair(
            border + x * square + tile / 2,
            border + y * square + tile / 2,
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

    private fun click(x: Int, y: Int, button: Int, tile: Int): List<OracleClient.Point> {
        val (px, py) = center(x, y, tile)
        return press(px, py, button, tile)
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

    // ------------------------------------------------------------------
    // Parameters and the public flags.
    // ------------------------------------------------------------------

    @Test(timeout = 300_000)
    fun `the new game, status, text and flags are the C values`() =
        OracleClient().use { oracle ->
            assertEquals("Unequal", Unequal.name)
            assertEquals("unequal", Unequal.backendName)
            assertEquals(32, Unequal.preferredTileSize)
            assertFalse(Unequal.wantsStatusBar)
            assertTrue(Unequal.canSolve)
            assertTrue(Unequal.canFormatAsText)
            assertFalse(Unequal.isTimed)
            assertEquals(
                Game.REQUIRE_RBUTTON or Game.REQUIRE_NUMPAD,
                Unequal.flags,
            )

            for (params in Unequal.presets().map { it.params } + "") {
                val start = save(params)
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                assertEquals("$params status", 0, midend.status())
                assertEquals("$params text", oracle.text(start), midend.textFormat())
                val order = (midend.currentParams as Unequal.Params).order
                assertEquals(
                    "$params text length",
                    (2 * order) * (2 * order - 1),
                    midend.textFormat().length,
                )
                assertTrue("$params trailing newline", midend.textFormat().endsWith("\n"))
                assertEquals("$params status text", "", midend.statusText())
                assertEquals(
                    "$params palette size",
                    8,
                    Unequal.colours().size / 3,
                )
            }

            // The default palette of the oracle background.
            val palette = Unequal.colours().map { (it * 1000.0f).roundToInt() }
            assertEquals(
                listOf(
                    800, 800, 800,
                    500, 500, 500,
                    0, 0, 0,
                    0, 480, 0,
                    1000, 0, 0,
                    400, 400, 800,
                    967, 967, 967,
                    633, 633, 633,
                ),
                palette,
            )

            // `computeSize`: T*order + (T/2)*(order-1) + 2*(T/2), with
            // both divisions truncating.
            val sizes = mapOf(
                48 to listOf(312, 384, 456, 528),
                63 to listOf(407, 501, 595, 689),
                82 to listOf(533, 656, 779, 902),
                100 to listOf(650, 800, 950, 1100),
            )
            for ((tile, row) in sizes) {
                for (order in 4..7) {
                    val p = Unequal.decodeParams("${order}de")
                    assertEquals(
                        "$order x $order at tile $tile",
                        row[order - 4],
                        Unequal.computeSize(p, tile).width,
                    )
                }
            }
            for ((tile, order, size) in listOf(
                Triple(51, 5, 405),
                Triple(43, 6, 405),
                Triple(37, 7, 403),
                Triple(63, 4, 407),
            )) {
                val p = Unequal.decodeParams("${order}de")
                assertEquals("tile $tile", size, Unequal.computeSize(p, tile).width)
                assertEquals("tile $tile height", size, Unequal.computeSize(p, tile).height)
            }
        }

    // ------------------------------------------------------------------
    // Keyboard paths.
    // ------------------------------------------------------------------

    @Test(timeout = 600_000)
    fun `key sequences match the oracle in state and frame`() =
        OracleClient().use { oracle ->
            val start = save("5de")
            val sequences = listOf(
                listOf(Buttons.CURSOR_UP),
                listOf(Buttons.CURSOR_UP, '3'.code),
                listOf(Buttons.CURSOR_UP, '3'.code, '\b'.code),
                listOf(Buttons.CURSOR_UP, '0'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '2'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '2'.code, '2'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2, '2'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '2'.code, '\b'.code),
                listOf('M'.code),
                listOf('m'.code),
                listOf('H'.code),
                listOf('h'.code),
                listOf(Buttons.CURSOR_RIGHT, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
                listOf(Buttons.CURSOR_UP, Buttons.MOD_CTRL or Buttons.CURSOR_UP),
                listOf(Buttons.CURSOR_UP, Buttons.MOD_SHFT or Buttons.CURSOR_DOWN),
                listOf(Buttons.MOD_NUM_KEYPAD or '2'.code),
                listOf('\t'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_UP, '3'.code, '3'.code),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(start, sequence)
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                for (key in sequence) midend.processKey(0, 0, key, 48)
                assertSame("state for $sequence", expected, midend)
                assertLines(
                    "frame for $sequence",
                    normalize(oracle.trace(start, sequence)),
                    normalize(KotlinTraceSink.stateTrace(midend)),
                )
            }

            // The move strings are the C grammar, not just equal states.
            val digit = Midend.fromSave(Unequal, SgtpSave.parse(start))
            digit.processKey(0, 0, Buttons.CURSOR_UP, 48)
            digit.processKey(0, 0, '3'.code, 48)
            assertEquals("R0,0,3", digit.serialise().values("MOVE").last())
            assertEquals(3, (digit.state as Unequal.State).nums[0])

            val pencil = Midend.fromSave(Unequal, SgtpSave.parse(start))
            pencil.processKey(0, 0, Buttons.CURSOR_UP, 48)
            pencil.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            assertEquals(KeyOutcome.SomeEffect, pencil.processKey(0, 0, '2'.code, 48))
            assertEquals("P0,0,2", pencil.serialise().values("MOVE").last())
            assertTrue((pencil.state as Unequal.State).hint(0, 0, 1))
            assertEquals(0, (pencil.state as Unequal.State).nums[0])
            assertTrue(pencil.processKey(0, 0, '2'.code, 48) == KeyOutcome.SomeEffect)
            assertFalse(
                "the second P toggles the mark off",
                (pencil.state as Unequal.State).hint(0, 0, 1),
            )

            // `H` fills hints and never completes. `M` fills every hint,
            // including the filled cells.
            val hint = Midend.fromSave(Unequal, SgtpSave.parse(start))
            hint.processKey(0, 0, 'H'.code, 48)
            assertEquals("H", hint.serialise().values("MOVE").last())
            assertEquals(0, hint.status())
            assertTrue((hint.state as Unequal.State).hints.any { it })

            val mark = Midend.fromSave(Unequal, SgtpSave.parse(start))
            mark.processKey(0, 0, 'M'.code, 48)
            assertEquals("M", mark.serialise().values("MOVE").last())
            assertEquals(0, mark.status())
            assertTrue((mark.state as Unequal.State).hints.all { it })

            // A digit with no highlight is unused, and the positive
            // control changes the same board.
            val inert = Midend.fromSave(Unequal, SgtpSave.parse(start))
            assertEquals(KeyOutcome.Unused, inert.processKey(0, 0, '3'.code, 48))
            assertEquals(1, inert.stateCount)
            assertEquals(0, (inert.state as Unequal.State).nums[0])
            val live = Midend.fromSave(Unequal, SgtpSave.parse(start))
            assertEquals(KeyOutcome.SomeEffect, live.processKey(0, 0, Buttons.CURSOR_UP, 48))
            assertEquals(KeyOutcome.SomeEffect, live.processKey(0, 0, '3'.code, 48))
            assertEquals(2, live.stateCount)
            assertEquals(3, (live.state as Unequal.State).nums[0])
        }

    @Test(timeout = 300_000)
    fun `an immutable cell and a pencil mark on a filled cell are unused`() =
        OracleClient().use { oracle ->
            val start = save("5de")
            // The 5de golden has an immutable 3 at (4,4).
            val immutableSeq = List(4) { Buttons.CURSOR_RIGHT } +
                List(4) { Buttons.CURSOR_DOWN } + listOf('1'.code)
            val expected = oracle.keys(start, immutableSeq)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
            for (key in immutableSeq) midend.processKey(0, 0, key, 48)
            assertSame("immutable", expected, midend)
            assertEquals(1, midend.stateCount)

            // Fill (0,0), then try a pencil mark on it.
            val filledSeq = listOf(Buttons.CURSOR_UP, '2'.code)
            val filled = oracle.keys(start, filledSeq).state
            val pencilSeq = listOf(Buttons.CURSOR_SELECT, '3'.code)
            val pencilExpected = oracle.keys(filled, pencilSeq)
            val pencil = Midend.fromSave(Unequal, SgtpSave.parse(filled))
            pencil.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            assertEquals(KeyOutcome.Unused, pencil.processKey(0, 0, '3'.code, 48))
            assertSame("pencil on filled", pencilExpected, pencil)
            assertEquals(2, pencil.stateCount)
        }

    @Test(timeout = 300_000)
    fun `the changed state event is raised only for a real win`() =
        OracleClient().use { oracle ->
            val desc = "0D,0,0,0,0,0,0R,0,0U,"
            val start = handSave(desc, "3de")
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
            val expected = oracle.keys(start, sequence)
            assertEquals("oracle status", 1, expected.status)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
            for (key in sequence) midend.processKey(0, 0, key, 48)
            assertSame("completion", expected, midend)
            assertTrue("completion event", midend.takeCompletedEvent())
            assertFalse("one event only", midend.takeCompletedEvent())
            assertEquals(1, midend.status())

            assertTrue(midend.undo())
            assertEquals("undo status", 0, midend.status())
            assertFalse("no event on undo", midend.takeCompletedEvent())
            assertTrue(midend.redo())
            assertEquals("redo status", 1, midend.status())
            assertTrue("redo event", midend.takeCompletedEvent())

            // A solve also completes the board but never raises the
            // event, as midend_solve ignores the game's return value.
            val solved = Midend.fromSave(Unequal, SgtpSave.parse(save("4de")))
            assertTrue(solved.solve())
            assertEquals(1, solved.status())
            assertFalse("no solve event", solved.takeCompletedEvent())
        }

    // ------------------------------------------------------------------
    // Pointer paths.
    // ------------------------------------------------------------------

    @Test(timeout = 600_000)
    fun `real clicks at the four tile sizes match the oracle`() =
        OracleClient().use { oracle ->
            val start = save("5de")
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Unequal.computeSize(Unequal.decodeParams("5de"), tile).width
                val steps = listOf(
                    click(0, 0, Buttons.LEFT_BUTTON, tile),
                    click(0, 0, Buttons.LEFT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '3'.code)),
                    click(2, 2, Buttons.LEFT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '1'.code)),
                    click(3, 3, Buttons.RIGHT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '2'.code)),
                    listOf(OracleClient.Point(0, 0, 'M'.code)),
                )
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                val points = ArrayList<OracleClient.Point>()
                for ((index, step) in steps.withIndex()) {
                    points += step
                    val expected = oracle.pointsSized(start, points, board, board)
                    val outcomes = apply(midend, step, tile)
                    assertSame("tile $tile step $index", expected.state, midend)
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
                        assertEquals(
                            "tile $tile step $index state count",
                            listOf(1, 1, 2, 2, 3, 3, 4, 5)[index],
                            midend.stateCount,
                        )
                    }
                    when (index) {
                        0 -> {
                            val border = tile / 2
                            assertEquals(
                                "tile $tile first click",
                                Game.CursorRect(border, border, tile, tile),
                                midend.cursorLocation(tile),
                            )
                        }

                        2 -> assertEquals(
                            "tile $tile pencil mark",
                            "P0,0,3",
                            midend.serialise().values("MOVE").last(),
                        )

                        4 -> assertEquals(
                            "tile $tile second pencil mark",
                            "P2,2,1",
                            midend.serialise().values("MOVE").last(),
                        )

                        6 -> assertEquals(
                            "tile $tile right click pencil",
                            "P3,3,2",
                            midend.serialise().values("MOVE").last(),
                        )

                        7 -> {
                            assertEquals(
                                "tile $tile M",
                                "M",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertTrue((midend.state as Unequal.State).hints.all { it })
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
    fun `the clue bands and the inert corner match the oracle`() =
        OracleClient().use { oracle ->
            val start = save("5de")
            val board = 384
            val (rightX, rightY) = band(2, 2, 48, right = true)
            val (downX, downY) = band(1, 0, 48, right = false)

            val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
            val points = ArrayList<OracleClient.Point>()
            val moves = listOf("F2,2,2048", "F2,2,2048", "F1,0,4096", "F2,2,2048")
            val steps = listOf(
                // Cell (2,2) has a right clue; cell (1,0) has a down
                // clue. Any mouse button toggles a band.
                press(rightX, rightY, Buttons.LEFT_BUTTON, 48),
                press(rightX, rightY, Buttons.MIDDLE_BUTTON, 48),
                press(downX, downY, Buttons.RIGHT_BUTTON, 48),
                press(rightX, rightY, Buttons.LEFT_BUTTON, 48),
            )
            for ((index, step) in steps.withIndex()) {
                points += step
                val expected = oracle.pointsSized(start, points, board, board)
                val outcomes = apply(midend, step, 48)
                assertSame("band step $index", expected.state, midend)
                assertEquals("band step $index press", KeyOutcome.SomeEffect, outcomes.first())
                assertEquals(
                    "band step $index move",
                    moves[index],
                    midend.serialise().values("MOVE").last(),
                )
                assertEquals("band step $index count", index + 2, midend.stateCount)
            }
            assertEquals(4, midend.stateCount - 1)

            // A band without a clue is unused. The cell (0,0) of the
            // 5de golden has no right clue and its neighbour has no
            // left clue.
            val (absentX, absentY) = band(0, 0, 48, right = true)
            val absent = press(absentX, absentY, Buttons.LEFT_BUTTON, 48)
            points += absent
            val absentExpected = oracle.pointsSized(start, points, board, board)
            val absentOutcomes = apply(midend, absent, 48)
            assertSame("absent band", absentExpected.state, midend)
            assertEquals("absent band is unused", KeyOutcome.Unused, absentOutcomes.first())
            assertEquals("absent band adds no state", 5, midend.stateCount)

            // The bottom-right GAP x GAP corner of a cell is inert, and
            // a middle press on a cell is unused. Both are next to the
            // band moves above, so a dropped band branch fails.
            val gap = press(24 + 48 + 12, 24 + 48 + 12, Buttons.LEFT_BUTTON, 48)
            points += gap
            val gapExpected = oracle.pointsSized(start, points, board, board)
            val gapOutcomes = apply(midend, gap, 48)
            assertSame("gap corner", gapExpected.state, midend)
            assertTrue("gap corner is inert", gapOutcomes.all { it == KeyOutcome.Unused })
            assertEquals("gap corner adds no state", 5, midend.stateCount)

            val middle = click(0, 0, Buttons.MIDDLE_BUTTON, 48)
            points += middle
            val middleExpected = oracle.pointsSized(start, points, board, board)
            val middleOutcomes = apply(midend, middle, 48)
            assertSame("middle cell", middleExpected.state, midend)
            assertTrue("middle cell is unused", middleOutcomes.all { it == KeyOutcome.Unused })
            assertEquals("middle cell adds no state", 5, midend.stateCount)

            val final = oracle.pointsSized(start, points, board, board, recordTrace = true)
            assertLines(
                "band final trace",
                normalize(final.trace!!),
                normalize(KotlinTraceSink.stateTrace(midend, 48)),
            )
        }

    @Test(timeout = 300_000)
    fun `the letterbox view uses the found tile and origin`() =
        OracleClient().use { oracle ->
            val viewports = listOf(411 to 702, 720 to 1280, 1024 to 600, 200 to 200, 1080 to 1920)
            for (params in listOf("4de", "5de", "6de", "7de")) {
                val start = save(params)
                for ((viewWidth, viewHeight) in viewports) {
                    val expected = oracle.layout(start, viewWidth, viewHeight)
                    val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                    val geometry = midend.geometryFor(viewWidth, viewHeight)
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

            // The 411x702 letterbox: 63, 51, 43, and 37 pixels.
            val tiles = mapOf("4de" to 63, "5de" to 51, "6de" to 43, "7de" to 37)
            val origins = mapOf(
                "4de" to (2 to 147),
                "5de" to (3 to 148),
                "6de" to (3 to 148),
                "7de" to (4 to 149),
            )
            for ((params, tile) in tiles) {
                val start = save(params)
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                val geometry = midend.geometryFor(411, 702)
                assertEquals("$params tile", tile, geometry.tileSize)
                assertEquals("$params origin", origins[params], geometry.originX to geometry.originY)

                // A real click inside the letterbox.
                val (px, py) = center(0, 0, tile)
                val points = press(
                    px + geometry.originX,
                    py + geometry.originY,
                    Buttons.LEFT_BUTTON,
                    tile,
                ) + OracleClient.Point(0, 0, '1'.code)
                val expected = oracle.pointsSized(start, points, 411, 702)
                for (point in points) {
                    midend.processKey(
                        point.x - geometry.originX,
                        point.y - geometry.originY,
                        point.button,
                        tile,
                    )
                }
                assertSame("$params letterbox", expected.state, midend)
                assertEquals(
                    "$params letterbox move",
                    "R0,0,1",
                    midend.serialise().values("MOVE").last(),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `a drag, a release and an absent key are unused`() =
        OracleClient().use { oracle ->
            val start = save("5de")
            val (x, y) = center(0, 0, 48)
            val cases = listOf(
                listOf(OracleClient.Point(x, y, Buttons.LEFT_DRAG)),
                listOf(OracleClient.Point(x, y, Buttons.LEFT_RELEASE)),
                listOf(OracleClient.Point(x, y, Buttons.RIGHT_DRAG)),
                listOf(OracleClient.Point(x, y, Buttons.RIGHT_RELEASE)),
                listOf(OracleClient.Point(x, y, Buttons.MIDDLE_BUTTON)),
                listOf(OracleClient.Point(x, y, '3'.code)),
            )
            for (points in cases) {
                val expected = oracle.pointsSized(start, points, 384, 384)
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                val outcomes = apply(midend, points, 48)
                assertSame("case $points", expected.state, midend)
                assertTrue(
                    "case $points is unused",
                    outcomes.all { it == KeyOutcome.Unused },
                )
                assertEquals("case $points adds no move", 1, midend.stateCount)
                assertEquals("case $points keeps the save", start, midend.serialise().serialise())
            }

            // The positive control: a press and a digit change the
            // state and the move count.
            val live = Midend.fromSave(Unequal, SgtpSave.parse(start))
            apply(live, click(0, 0, Buttons.LEFT_BUTTON, 48), 48)
            assertEquals(KeyOutcome.SomeEffect, live.processKey(0, 0, '3'.code, 48))
            assertEquals(2, live.stateCount)
            assertEquals("R0,0,3", live.serialise().values("MOVE").last())
            assertEquals(3, (live.state as Unequal.State).nums[0])

            // An inert key without a highlight changes neither the
            // board nor the move count.
            val inert = Midend.fromSave(Unequal, SgtpSave.parse(start))
            assertEquals(KeyOutcome.Unused, inert.processKey(0, 0, '3'.code, 48))
            assertEquals(1, inert.stateCount)
            assertEquals(start, inert.serialise().serialise())
            assertEquals(0, inert.status())
        }

    // ------------------------------------------------------------------
    // Moves and history.
    // ------------------------------------------------------------------

    @Test(timeout = 300_000)
    fun `F, M, H and S moves and their undo and redo match the oracle`() =
        OracleClient().use { oracle ->
            val start = save("5de")

            // F toggles a spent bit through a modified cursor move.
            val fSeq = List(2) { Buttons.CURSOR_RIGHT } +
                List(2) { Buttons.CURSOR_DOWN } +
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
            val afterF = oracle.keys(start, fSeq).state
            assertEquals(
                "F2,2,2048",
                SgtpSave.parse(afterF).values("MOVE").last(),
            )
            val f = Midend.fromSave(Unequal, SgtpSave.parse(start))
            for (key in fSeq) f.processKey(0, 0, key, 48)
            assertSame("F", oracle.id(afterF), f)
            assertTrue((f.state as Unequal.State).flags[12] and Unequal.F_SPENT_RIGHT != 0)
            assertTrue(f.undo())
            assertSame("F undo", oracle.undo(afterF), f)
            assertTrue(f.redo())
            assertSame("F redo", oracle.id(afterF), f)

            // A second identical F clears the spent bit.
            val f2 = Midend.fromSave(Unequal, SgtpSave.parse(afterF))
            for (key in fSeq) f2.processKey(0, 0, key, 48)
            val afterF2 = oracle.keys(afterF, fSeq).state
            assertSame("F again", oracle.id(afterF2), f2)
            assertEquals(0, (f2.state as Unequal.State).flags[12] and Unequal.F_SPENT_RIGHT)

            // M.
            val afterM = oracle.keys(start, listOf('M'.code)).state
            val m = Midend.fromSave(Unequal, SgtpSave.parse(start))
            m.processKey(0, 0, 'M'.code, 48)
            assertSame("M", oracle.id(afterM), m)
            assertTrue((m.state as Unequal.State).hints.all { it })
            assertTrue(m.undo())
            assertSame("M undo", oracle.undo(afterM), m)
            assertTrue(m.redo())

            // H.
            val afterH = oracle.keys(start, listOf('H'.code)).state
            val h = Midend.fromSave(Unequal, SgtpSave.parse(start))
            h.processKey(0, 0, 'H'.code, 48)
            assertSame("H", oracle.id(afterH), h)
            assertEquals(0, h.status())
            assertTrue(h.undo())
            assertSame("H undo", oracle.undo(afterH), h)
            assertTrue(h.redo())

            // S: solve plays the stored answer and sets cheated.
            val solved = Midend.fromSave(Unequal, SgtpSave.parse(start))
            assertTrue(solved.solve())
            val expectedSolve = oracle.solve(start)
            assertSame("S", expectedSolve, solved)
            val aux = requireNotNull(solved.auxInfo)
            assertEquals(aux, SgtpSave.parse(expectedSolve.state).value("SOLVE"))
            assertTrue(solved.undo())
            assertSame("S undo", oracle.undo(expectedSolve.state), solved)
            assertTrue(solved.redo())
        }

    @Test(timeout = 600_000)
    fun `the solve move matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Unequal.presets().map { it.params } + "") {
                val start = oracle.newGame("unequal", params, SEED).state
                val expected = oracle.solve(start)
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
                val requested = (midend.currentParams as Unequal.Params)
                assertEquals(
                    "$params difficulty",
                    requested.diff,
                    Unequal.difficulty(midend.state),
                )
                assertTrue("$params solve", midend.solve())
                assertSame("$params solve", expected, midend)
                assertEquals("$params status", 1, midend.status())
                assertTrue("$params can undo", midend.canUndo)
                val aux = requireNotNull(midend.auxInfo)
                assertEquals("$params SOLVE", aux, SgtpSave.parse(expected.state).value("SOLVE"))
                assertFalse("$params no event", midend.takeCompletedEvent())
            }
        }

    @Test(timeout = 600_000)
    fun `the solve without AUXINFO, after a wrong move, and on an ambiguous board`() =
        OracleClient().use { oracle ->
            // Without AUXINFO the C solver runs from the clues.
            val withAux = oracle.newGame("unequal", "5dk", SEED).state
            val noAux = withoutAux(withAux)
            assertNull(SgtpSave.parse(noAux).value("AUXINFO"))
            val expected = oracle.solve(noAux)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(noAux))
            assertNull(midend.auxInfo)
            val direct = Unequal.solve(
                Midend.fromSave(Unequal, SgtpSave.parse(noAux)).state,
                null,
            )
            val solveMove = SgtpSave.parse(expected.state).value("SOLVE")!!
            assertEquals("direct solve", solveMove, direct)
            assertTrue(midend.solve())
            assertSame("solve without aux", expected, midend)

            // A wrong move before the solve leaves the SOLVE record
            // equal to C, although C solves state 0 and the port solves
            // the current state: both clear every non-immutable digit.
            val wrong = oracle.keys(noAux, listOf(Buttons.CURSOR_UP, '1'.code)).state
            assertEquals("R0,0,1", SgtpSave.parse(wrong).values("MOVE").last())
            val wrongExpected = oracle.solve(wrong)
            val wrongMidend = Midend.fromSave(Unequal, SgtpSave.parse(wrong))
            assertTrue(wrongMidend.solve())
            assertSame("solve after a wrong move", wrongExpected, wrongMidend)
            assertEquals(
                "the wrong move is still in the history",
                "R0,0,1",
                SgtpSave.parse(wrongMidend.serialise().serialise()).values("MOVE")[0],
            )

            // An ambiguous board still returns its first solution.
            val ambiguous = handSave(grid(3), "3de")
            val ambiguousExpected = oracle.solve(ambiguous)
            val ambiguousMidend = Midend.fromSave(Unequal, SgtpSave.parse(ambiguous))
            assertTrue(ambiguousMidend.solve())
            assertSame("ambiguous", ambiguousExpected, ambiguousMidend)
            assertEquals(1, ambiguousMidend.status())

            // An impossible hand-written board has no solution.
            val impossible = handSave("1," + "1,".repeat(8), "3de")
            assertTrue(
                "C rejects the impossible solve",
                try {
                    oracle.solve(impossible)
                    false
                } catch (e: OracleException) {
                    true
                },
            )
            val impossibleMidend = Midend.fromSave(Unequal, SgtpSave.parse(impossible))
            assertNull(Unequal.solve(impossibleMidend.state, null))
            assertFalse(impossibleMidend.solve())
        }

    @Test(timeout = 300_000)
    fun `the hint on a duplicate board matches the oracle frame`() =
        OracleClient().use { oracle ->
            // Two 1s in the first column: `latin_solver_alloc` fails on
            // the second, and `H` copies the partial cube into the
            // hints. The frame pins the cube layout, including the
            // transposed slice that `gg_best_clue` reads.
            val desc = grid(4, numbers = mapOf(0 to 1, 4 to 1))
            val start = handSave(desc)
            val sequence = listOf('H'.code)
            val expected = oracle.keys(start, sequence)
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(start))
            midend.processKey(0, 0, 'H'.code, 48)
            assertSame("hint on duplicate", expected, midend)
            assertEquals(0, midend.status())
            assertLines(
                "hint frame",
                normalize(oracle.trace(start, sequence)),
                normalize(KotlinTraceSink.stateTrace(midend)),
            )
            assertTrue(
                "the hint frame has pencil marks",
                normalize(KotlinTraceSink.stateTrace(midend)).contains(" 5 \""),
            )
        }

    @Test(timeout = 300_000)
    fun `the text form covers both modes and cannot see spent or error marks`() =
        OracleClient().use { oracle ->
            val start = save("5de")
            assertEquals(oracle.text(start), Midend.fromSave(Unequal, SgtpSave.parse(start)).textFormat())

            // A spent clue does not change the text.
            val spentSeq = List(2) { Buttons.CURSOR_RIGHT } +
                List(2) { Buttons.CURSOR_DOWN } +
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
            val afterF = oracle.keys(start, spentSeq).state
            assertTrue(
                "the spent bit is set",
                (Midend.fromSave(Unequal, SgtpSave.parse(afterF)).state as Unequal.State)
                    .flags[12] and Unequal.F_SPENT_RIGHT != 0,
            )
            assertEquals(
                "the spent clue leaves the text",
                oracle.text(start),
                Midend.fromSave(Unequal, SgtpSave.parse(afterF)).textFormat(),
            )

            // Both clue spellings: the left cell's R is `>` and the
            // right cell's L without the R is `<`.
            val left = handSave(grid(3, flags = mapOf(0 to "R")), "3de")
            val right = handSave(grid(3, flags = mapOf(1 to "L")), "3de")
            val down = handSave(grid(3, flags = mapOf(3 to "D")), "3de")
            val up = handSave(grid(3, flags = mapOf(6 to "U")), "3de")
            val leftText = Midend.fromSave(Unequal, SgtpSave.parse(left)).textFormat()
            assertEquals(oracle.text(left), leftText)
            assertTrue("the R spelling is >", leftText.contains('>'))
            val rightText = Midend.fromSave(Unequal, SgtpSave.parse(right)).textFormat()
            assertEquals(oracle.text(right), rightText)
            assertTrue("the L spelling is <", rightText.contains('<'))

            val downText = Midend.fromSave(Unequal, SgtpSave.parse(down)).textFormat()
            assertEquals(oracle.text(down), downText)
            assertTrue("the D spelling is v", downText.contains('v'))
            val upText = Midend.fromSave(Unequal, SgtpSave.parse(up)).textFormat()
            assertEquals(oracle.text(up), upText)
            assertTrue("the U spelling is ^", upText.contains('^'))

            // Adjacent mode uses the bars.
            val adj = handSave(
                grid(5, flags = mapOf(0 to "RD", 1 to "L", 5 to "U")),
                "5adk",
            )
            val adjText = Midend.fromSave(Unequal, SgtpSave.parse(adj)).textFormat()
            assertEquals(oracle.text(adj), adjText)
            assertTrue("adjacent has a bar", adjText.contains('|'))
            assertTrue("adjacent has a dash", adjText.contains('-'))

            // Empty cells are `.` in the text and a space in the drawing.
            assertTrue(adjText.contains('.'))
        }

    @Test(timeout = 300_000)
    fun `requestKeys, currentKeyLabel and cursorLocation match the C values`() =
        OracleClient().use { oracle ->
            for (params in Unequal.presets().map { it.params } + "") {
                val start = oracle.newGame("unequal", params, SEED).state
                val decoded = if (params.isEmpty()) {
                    Unequal.defaultParams()
                } else {
                    Unequal.decodeParams(params)
                }
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(start, fullCursor)
                    val actual = Unequal.requestKeys(decoded, fullCursor)
                    assertEquals("$params/$fullCursor arrow mode", 2, expected.arrowMode)
                    assertEquals("$params/$fullCursor enum", ArrowMode.ARROWS_LEFT, actual.arrowMode)
                    assertEquals("$params/$fullCursor count", expected.labels.size, actual.labels.size)
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
                        assertFalse(
                            "$params/$fullCursor needs arrows $i",
                            actual.labels[i].needsArrows,
                        )
                    }
                }
            }

            // Order 10: the first key is `0`, which inserts digit 1,
            // and digit 1 has no key of its own.
            val ten = handSave(grid(10), "10de")
            val tenState = oracle.status(ten)
            val tenMidend = Midend.fromSave(Unequal, SgtpSave.parse(ten))
            val expectedTen = oracle.requestKeys(ten)
            val actualTen = Unequal.requestKeys(Unequal.decodeParams("10de"))
            assertEquals(13, actualTen.labels.size)
            assertEquals("0", expectedTen.labels[0].label)
            assertEquals('0'.code, actualTen.labels[0].button)
            assertEquals("0", actualTen.labels[0].name)
            assertEquals("Clear", actualTen.labels[10].name)
            assertEquals("Mark", actualTen.labels[11].name)
            assertEquals("Mark/Hints", actualTen.labels[12].name)
            for (i in expectedTen.labels.indices) {
                assertEquals("ten label $i", expectedTen.labels[i].label, actualTen.labels[i].name)
                assertEquals("ten button $i", expectedTen.labels[i].button, actualTen.labels[i].button)
            }
            assertEquals(tenState.id, tenMidend.id())
            // `'1'` inserts 2 at order 10, and `'0'` inserts 1.
            val keyed = oracle.keys(ten, listOf(Buttons.CURSOR_UP, '1'.code)).state
            val keyedMidend = Midend.fromSave(Unequal, SgtpSave.parse(ten))
            keyedMidend.processKey(0, 0, Buttons.CURSOR_UP, 48)
            keyedMidend.processKey(0, 0, '1'.code, 48)
            assertSame("ten key", oracle.id(keyed), keyedMidend)
            assertEquals("R0,0,2", keyedMidend.serialise().values("MOVE").last())
            assertEquals(2, (keyedMidend.state as Unequal.State).nums[0])

            val zeroed = oracle.keys(ten, listOf(Buttons.CURSOR_UP, '0'.code)).state
            val zeroMidend = Midend.fromSave(Unequal, SgtpSave.parse(ten))
            zeroMidend.processKey(0, 0, Buttons.CURSOR_UP, 48)
            zeroMidend.processKey(0, 0, '0'.code, 48)
            assertSame("ten zero", oracle.id(zeroed), zeroMidend)
            assertEquals("R0,0,1", zeroMidend.serialise().values("MOVE").last())

            // The text form at order 10: digit 1 is `0` and digit 10 is
            // `9`.
            assertEquals(oracle.text(ten), tenMidend.textFormat())
            val numbered = oracle.keys(
                ten,
                listOf(
                    Buttons.CURSOR_UP, '0'.code,
                    Buttons.CURSOR_RIGHT, '9'.code,
                ),
            ).state
            val numberedMidend = Midend.fromSave(Unequal, SgtpSave.parse(ten))
            numberedMidend.processKey(0, 0, Buttons.CURSOR_UP, 48)
            numberedMidend.processKey(0, 0, '0'.code, 48)
            numberedMidend.processKey(0, 0, Buttons.CURSOR_RIGHT, 48)
            numberedMidend.processKey(0, 0, '9'.code, 48)
            assertSame("ten numbers", oracle.id(numbered), numberedMidend)
            assertEquals(oracle.text(numbered), numberedMidend.textFormat())
            val text = numberedMidend.textFormat()
            assertTrue("digit 1 is 0 and digit 10 is 9", text.startsWith("0 9"))

            // The current key label is a public helper.
            val labelState = save("5de")
            val labelMidend = Midend.fromSave(Unequal, SgtpSave.parse(labelState))
            assertEquals("", Unequal.currentKeyLabel(labelMidend.ui, labelMidend.state, '3'.code))
            assertEquals(
                "",
                Unequal.currentKeyLabel(labelMidend.ui, labelMidend.state, Buttons.CURSOR_SELECT),
            )
            labelMidend.setCursorVisible(true)
            assertEquals(
                "Pencil",
                Unequal.currentKeyLabel(labelMidend.ui, labelMidend.state, Buttons.CURSOR_SELECT),
            )
            assertEquals(
                "Pencil",
                Unequal.currentKeyLabel(labelMidend.ui, labelMidend.state, Buttons.CURSOR_SELECT2),
            )
            labelMidend.processKey(0, 0, Buttons.CURSOR_SELECT, 48)
            assertEquals(
                "Ink",
                Unequal.currentKeyLabel(labelMidend.ui, labelMidend.state, Buttons.CURSOR_SELECT),
            )

            // The cursor is the whole tile, and it is absent when hidden.
            assertEquals(
                Game.CursorRect(24, 24, 48, 48),
                labelMidend.cursorLocation(48),
            )
            labelMidend.setCursorVisible(false)
            assertNull(labelMidend.cursorLocation(48))
        }

    private companion object {
        const val SEED = "12345"
    }
}
