package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Towers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and strict where a frame is compared. Game: towers.
 *
 * The new game, the status, the solve text and the difficulty result,
 * real clicks and keys, number and pencil entry, the `M` and `D` moves,
 * undo and redo, completion by input, and the text form. The highlight
 * is frontend state, so a key sequence that only moves it is compared
 * through a draw trace.
 */
class TowersGameTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun normalize(trace: JsonValue.Obj): String =
        TraceNormalizer.normalize(trace, upstream)

    private fun save(params: String = "5de", seed: String = SEED): String =
        Midend.newGame(Towers, params, seed).serialise().serialise()

    private fun handSave(desc: String, params: String = "3de"): String {
        val records = listOf(
            "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection",
            "VERSION" to "1",
            "GAME" to "Towers",
            "PARAMS" to params,
            "CPARAMS" to params,
            "SEED" to "1",
            "DESC" to desc,
            "NSTATES" to "1",
            "STATEPOS" to "1",
        )
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun center(x: Int, y: Int, tile: Int = 48): Pair<Int, Int> {
        val border = tile * 9 / 8
        return Pair(border + x * tile + tile / 2, border + y * tile + tile / 2)
    }

    private fun press(x: Int, y: Int, button: Int, tile: Int = 48): List<OracleClient.Point> {
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

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tile: Int = 48,
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

    @Test(timeout = 600_000)
    fun `the solve move matches the oracle for every preset`() =
        OracleClient().use { oracle ->
            for (params in Towers.presets().map { it.params } + "") {
                val start = oracle.newGame("towers", params, SEED).state
                val expected = oracle.solve(start)
                val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
                assertTrue("$params solve", midend.solve())
                assertEquals("$params status", 1, midend.status())
                assertTrue("$params can undo", midend.canUndo)
                assertEquals(
                    "$params save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                val solve = SgtpSave.parse(expected.state).value("SOLVE")!!
                assertTrue("$params solve prefix", solve.startsWith("S"))
                assertEquals(
                    "$params solve digits",
                    (midend.currentParams as Towers.Params).w.let { it * it } + 1,
                    solve.length,
                )
                assertEquals("$params solve record", solve, midend.serialise().value("SOLVE"))
            }
        }

    @Test(timeout = 300_000)
    fun `the generated difficulty is the requested one and the three by three clamp`() {
        for (paramsText in Towers.presets().map { it.params } + "") {
            val effective = if (paramsText.isEmpty()) "5de" else paramsText
            val p = Towers.decodeParams(effective) as Towers.Params
            val midend = Midend.newGame(Towers, effective, "12345")
            assertEquals(
                "$effective difficulty",
                p.diff,
                Towers.difficulty(midend.state),
            )
        }
        // A 3x3 Extreme or Unreasonable request generates a Hard board.
        for (paramsText in listOf("3dx", "3du")) {
            val midend = Midend.newGame(Towers, paramsText, "7")
            assertEquals("$paramsText difficulty", 1, Towers.difficulty(midend.state))
        }
    }

    @Test(timeout = 300_000)
    fun `key sequences match the oracle in state and frame`() =
        OracleClient().use { oracle ->
            val start = save()
            val sequences = listOf(
                listOf(Buttons.CURSOR_UP, '3'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT, '2'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT, '3'.code, '3'.code),
                listOf(Buttons.CURSOR_UP, '3'.code, '\b'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_SELECT2),
                listOf('M'.code),
                listOf('m'.code),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT),
                listOf(Buttons.MOD_SHFT or Buttons.CURSOR_UP),
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, Buttons.MOD_CTRL or Buttons.CURSOR_DOWN),
                listOf(Buttons.MOD_NUM_KEYPAD or '3'.code),
                listOf('\t'.code),
                listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_RIGHT),
                listOf(Buttons.CURSOR_UP, '3'.code, '3'.code),
            )
            for (sequence in sequences) {
                val expected = oracle.keys(start, sequence)
                val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
                for (key in sequence) midend.processKey(0, 0, key, 48)
                assertSame("state for $sequence", expected, midend)
                assertEquals(
                    "frame for $sequence",
                    normalize(oracle.trace(start, sequence)),
                    normalize(KotlinTraceSink.stateTrace(midend)),
                )
            }
        }

    @Test(timeout = 600_000)
    fun `real clicks at the four tile sizes match the oracle`() =
        OracleClient().use { oracle ->
            for (tile in listOf(48, 63, 82, 100)) {
                val board = Towers.computeSize(Towers.decodeParams("5de"), tile).width
                val steps = listOf(
                    press(0, 0, Buttons.LEFT_BUTTON, tile),
                    press(0, 0, Buttons.LEFT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '3'.code)),
                    press(3, 0, Buttons.RIGHT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '1'.code)),
                    press(3, 0, Buttons.LEFT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '1'.code)),
                    press(4, 4, Buttons.LEFT_BUTTON, tile),
                    listOf(OracleClient.Point(0, 0, '5'.code)),
                    listOf(OracleClient.Point(0, 0, '\b'.code)),
                )
                val midend = Midend.fromSave(Towers, SgtpSave.parse(save()))
                val points = ArrayList<OracleClient.Point>()
                for ((index, step) in steps.withIndex()) {
                    points += step
                    val expected = oracle.pointsSized(save(), points, board, board)
                    val outcomes = apply(midend, step, tile)
                    assertSame("tile $tile step $index", expected.state, midend)
                    when (index) {
                        0, 1, 3, 5, 7 -> {
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
                                listOf(1, 1, 2, 3, 4)[listOf(0, 1, 3, 5, 7).indexOf(index)],
                                midend.stateCount,
                            )
                        }

                        2 -> {
                            assertEquals(
                                "tile $tile pencil mark",
                                "P0,0,3",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile pencil move count", 2, midend.stateCount)
                            assertEquals(
                                "tile $tile pencil leaves the grid empty",
                                0,
                                (midend.state as Towers.State).grid[0],
                            )
                            assertEquals(
                                "tile $tile pencil bit",
                                1 shl 3,
                                (midend.state as Towers.State).pencil[0],
                            )
                        }

                        4 -> {
                            assertEquals(
                                "tile $tile pencil mark 2",
                                "P3,0,1",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile pencil move count 2", 3, midend.stateCount)
                        }

                        6 -> {
                            assertEquals(
                                "tile $tile real digit",
                                "R3,0,1",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals("tile $tile real digit count", 4, midend.stateCount)
                            assertEquals(
                                "tile $tile real digit value",
                                1,
                                (midend.state as Towers.State).grid[3],
                            )
                        }

                        8 -> {
                            assertEquals(
                                "tile $tile last digit",
                                "R4,4,5",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals(
                                "tile $tile last digit value",
                                5,
                                (midend.state as Towers.State).grid[24],
                            )
                        }

                        9 -> {
                            assertEquals(
                                "tile $tile clear",
                                "R4,4,0",
                                midend.serialise().values("MOVE").last(),
                            )
                            assertEquals(
                                "tile $tile cleared value",
                                0,
                                (midend.state as Towers.State).grid[24],
                            )
                        }
                    }
                }

                val final = oracle.pointsSized(save(), points, board, board, recordTrace = true)
                assertLines(
                    "tile $tile final trace",
                    normalize(final.trace!!),
                    normalize(KotlinTraceSink.stateTrace(midend, tile)),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the letterbox view uses the found tile and origin`() =
        OracleClient().use { oracle ->
            val start = save()
            val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
            val geometry = midend.geometryFor(411, 702)
            assertEquals(56, geometry.tileSize)
            assertEquals(2, geometry.originX)
            assertEquals(148, geometry.originY)
            assertEquals(406, geometry.width)

            val (gx, gy) = center(0, 0, geometry.tileSize)
            val viewPoints = listOf(
                OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(gx + geometry.originX, gy + geometry.originY, Buttons.LEFT_RELEASE),
                OracleClient.Point(0, 0, '4'.code),
            )
            val expected = oracle.pointsSized(start, viewPoints, 411, 702)
            for (point in viewPoints) {
                midend.processKey(
                    point.x - geometry.originX,
                    point.y - geometry.originY,
                    point.button,
                    geometry.tileSize,
                )
            }
            assertSame("letterbox", expected.state, midend)
            assertEquals("letterbox move", "R0,0,4", midend.serialise().values("MOVE").last())
        }

    @Test(timeout = 300_000)
    fun `clue clicks in every band produce D moves`() =
        OracleClient().use { oracle ->
            val start = save()
            // The clue bands at tile 48, in game coordinates.
            val clues = listOf(
                listOf(OracleClient.Point(78, 30, Buttons.LEFT_BUTTON), OracleClient.Point(78, 30, Buttons.LEFT_RELEASE)),
                listOf(OracleClient.Point(78, 318, Buttons.LEFT_BUTTON), OracleClient.Point(78, 318, Buttons.LEFT_RELEASE)),
                listOf(OracleClient.Point(30, 78, Buttons.LEFT_BUTTON), OracleClient.Point(30, 78, Buttons.LEFT_RELEASE)),
                listOf(OracleClient.Point(318, 78, Buttons.LEFT_BUTTON), OracleClient.Point(318, 78, Buttons.LEFT_RELEASE)),
            )
            val moves = listOf("D0,-1", "D0,5", "D-1,0", "D5,0")
            val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
            val points = ArrayList<OracleClient.Point>()
            for ((index, step) in clues.withIndex()) {
                points += step
                val expected = oracle.pointsSized(start, points, 348, 348)
                val outcomes = apply(midend, step)
                assertSame("clue $index", expected.state, midend)
                assertEquals("clue $index move", moves[index], midend.serialise().values("MOVE").last())
                assertTrue("clue $index pressed", outcomes.first() == KeyOutcome.SomeEffect)
                assertEquals("clue $index count", index + 2, midend.stateCount)
                assertTrue(
                    "clue $index done flag",
                    (midend.state as Towers.State).cluesDone[listOf(0, 5, 10, 15)[index]],
                )
            }

            // A click on an absent clue clears the highlight instead. The
            // 5dh save has four empty top clue fields.
            val sparse = File(testdata, "saves/initial/towers/5dh.sgtp").readText()
            val emptyTop = listOf(
                OracleClient.Point(78, 30, Buttons.LEFT_BUTTON),
                OracleClient.Point(78, 30, Buttons.LEFT_RELEASE),
            )
            val expected = oracle.pointsSized(sparse, emptyTop, 348, 348)
            val sparseMidend = Midend.fromSave(Towers, SgtpSave.parse(sparse))
            val outcomes = apply(sparseMidend, emptyTop)
            assertSame("absent clue", expected.state, sparseMidend)
            assertEquals("absent clue adds no move", 1, sparseMidend.stateCount)
            assertEquals("absent clue press", KeyOutcome.SomeEffect, outcomes.first())
        }

    @Test(timeout = 300_000)
    fun `M and the D move are undoable history entries`() =
        OracleClient().use { oracle ->
            val start = save()
            val marked = oracle.keys(start, listOf('M'.code)).state
            val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'M'.code, 48))
            assertEquals("M", midend.serialise().values("MOVE").last())
            assertEquals(0, midend.status())
            assertSame("M", oracle.id(marked), midend)
            assertTrue(midend.undo())
            assertSame("M undo", oracle.undo(marked), midend)
            assertTrue(midend.redo())

            val d = oracle.keys(start, listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT)).state
            val clue = Midend.fromSave(Towers, SgtpSave.parse(start))
            assertEquals(
                KeyOutcome.SomeEffect,
                clue.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT, 48),
            )
            assertEquals("D-1,0", clue.serialise().values("MOVE").last())
            assertSame("D", oracle.id(d), clue)
            assertTrue(clue.undo())
            assertSame("D undo", oracle.undo(d), clue)
            assertTrue(clue.redo())
            assertTrue(clue.undo())
            assertTrue(clue.undo().not())
        }

    @Test(timeout = 300_000)
    fun `undo and redo after a digit, a D move and a solve match the oracle`() =
        OracleClient().use { oracle ->
            val start = save()
            val afterDigit = oracle.keys(
                start,
                listOf(Buttons.CURSOR_UP, '3'.code),
            ).state
            val digit = Midend.fromSave(Towers, SgtpSave.parse(start))
            digit.processKey(0, 0, Buttons.CURSOR_UP, 48)
            digit.processKey(0, 0, '3'.code, 48)
            assertSame("digit", oracle.id(afterDigit), digit)
            assertTrue(digit.undo())
            assertSame("digit undo", oracle.undo(afterDigit), digit)
            assertTrue(digit.redo())
            assertSame("digit redo", oracle.id(afterDigit), digit)

            val sequence = listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT)
            val afterD = oracle.keys(start, sequence).state
            val clue = Midend.fromSave(Towers, SgtpSave.parse(start))
            for (key in sequence) clue.processKey(0, 0, key, 48)
            assertSame("D", oracle.id(afterD), clue)
            assertTrue(clue.undo())
            assertSame("D undo", oracle.undo(afterD), clue)
            assertTrue(clue.redo())

            val afterSolve = oracle.solve(start).state
            val solved = Midend.fromSave(Towers, SgtpSave.parse(start))
            assertTrue(solved.solve())
            assertSame("solve", oracle.id(afterSolve), solved)
            assertTrue(solved.undo())
            assertSame("solve undo", oracle.undo(afterSolve), solved)
            assertTrue(solved.redo())
            assertSame("solve redo", oracle.id(afterSolve), solved)
        }

    @Test(timeout = 300_000)
    fun `completion by real keys matches the oracle and raises one event`() =
        OracleClient().use { oracle ->
            // The completion recipe from the app instrumentation test.
            val desc = "1/2/3/2/2/1/1/2/2/3/2/1"
            val start = handSave(desc)
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
            val midend = Midend.fromSave(Towers, SgtpSave.parse(start))
            for (key in sequence) midend.processKey(0, 0, key, 48)
            assertSame("completion", expected, midend)
            assertTrue("completion event", midend.takeCompletedEvent())
            assertFalse("one event only", midend.takeCompletedEvent())

            assertTrue(midend.undo())
            assertEquals("undo status", 0, midend.status())
            assertTrue(midend.redo())
            assertEquals("redo status", 1, midend.status())
            assertTrue("redo event", midend.takeCompletedEvent())
        }

    @Test(timeout = 300_000)
    fun `the text form matches the oracle in every shape`() =
        OracleClient().use { oracle ->
            // The empty default board.
            val start = save()
            assertEquals(oracle.text(start), Midend.fromSave(Towers, SgtpSave.parse(start)).textFormat())

            // The 5dh save with four empty top clues.
            val sparse = File(testdata, "saves/initial/towers/5dh.sgtp").readText()
            assertEquals(oracle.text(sparse), Midend.fromSave(Towers, SgtpSave.parse(sparse)).textFormat())

            // A moved board with pencil marks: the marks are omitted.
            val points = listOf(
                OracleClient.Point(78, 78, Buttons.LEFT_BUTTON),
                OracleClient.Point(78, 78, Buttons.LEFT_RELEASE),
                OracleClient.Point(0, 0, '3'.code),
                OracleClient.Point(0, 0, 'M'.code),
            )
            val moved = oracle.pointsSized(start, points, 348, 348).state.state
            val movedMidend = Midend.fromSave(Towers, SgtpSave.parse(start))
            for (point in points) movedMidend.processKey(point.x, point.y, point.button, 48)
            assertEquals(oracle.text(moved), movedMidend.textFormat())
            assertTrue((movedMidend.state as Towers.State).pencil.any { it != 0 })

            // A solved board.
            val solved = oracle.solve(start).state
            assertEquals(oracle.text(solved), Midend.fromSave(Towers, SgtpSave.parse(start)).let {
                it.solve()
                it.textFormat()
            })
            assertTrue(
                "trailing newline",
                Midend.fromSave(Towers, SgtpSave.parse(solved)).textFormat().endsWith("\n"),
            )
        }

    @Test(timeout = 300_000)
    fun `status, the key request and the flags are the C values`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("towers", "5de", SEED)
            assertEquals(0, start.status)
            assertEquals(1, oracle.solve(start.state).status)
            assertFalse(Towers.wantsStatusBar)
            assertTrue(Towers.canSolve)
            assertTrue(Towers.canFormatAsText)
            assertFalse(Towers.isTimed)

            for (params in Towers.presets().map { it.params } + "") {
                val state = oracle.newGame("towers", params, SEED).state
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(state, fullCursor)
                    val actual = Towers.requestKeys(
                        Towers.decodeParams(if (params.isEmpty()) "5de" else params),
                        fullCursor,
                    )
                    assertEquals(
                        "$params full=$fullCursor arrow mode",
                        2,
                        expected.arrowMode,
                    )
                    assertEquals(
                        "$params full=$fullCursor enum",
                        ArrowMode.ARROWS_LEFT,
                        actual.arrowMode,
                    )
                    assertEquals("$params full=$fullCursor count", expected.labels.size, actual.labels.size)
                    for (i in expected.labels.indices) {
                        assertEquals(
                            "$params full=$fullCursor label $i",
                            expected.labels[i].label,
                            actual.labels[i].name,
                        )
                        assertEquals(
                            "$params full=$fullCursor button $i",
                            expected.labels[i].button,
                            actual.labels[i].button,
                        )
                        assertEquals(
                            "$params full=$fullCursor arrows $i",
                            expected.labels[i].needsArrows,
                            actual.labels[i].needsArrows,
                        )
                    }
                }
            }
        }

    @Test(timeout = 120_000)
    fun `textFormat is available for a params-aware caller`() {
        // Towers has no `can_format_as_text_now` dependence.
        val midend = Midend.newGame(Towers, "5de", SEED)
        assertTrue(Towers.canFormatAsText)
        assertNotNull(midend.textFormat())
        assertEquals(2 * 25 + 10 * 5 + 8, midend.textFormat().length)
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val expectedLines = expected.trimEnd('\n').lines()
        val actualLines = actual.trimEnd('\n').lines()
        for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("$what line ${i + 1}", expectedLines.getOrNull(i), actualLines.getOrNull(i))
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
