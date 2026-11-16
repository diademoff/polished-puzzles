package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and `ui`. Game: pattern.
 *
 * Layout at the five tile sizes and at two letterboxed viewports, real
 * left and right three-way cycles at tiles 24, 48, 63, 82, and 100 and
 * at a nonzero origin on both axes, the drag snap and clamp rules, the
 * unsnapped middle rectangle, immutable clicks and selects, the cursor
 * ring, the no-op win, and `requestKeys`.
 *
 * Every real cycle changes the grid and adds one move and one state.
 * An inert click and an immutable click change neither. The final state,
 * id, status, history flags, and trace are compared with `pointsSized`
 * where the input is a pointer, and with `keys` and `trace` where it is
 * a key.
 */
class PatternInputParityTest {

    private companion object {
        const val SEED = "12345"

        val PRESETS = listOf("10x10", "15x15", "20x20", "25x25", "30x30")
        val TILES = listOf(24, 48, 63, 82, 100)

        /** The 4x4 board whose last column is a filled, locked clue. */
        const val HARD_SUFFIX = "2/1/2/1/1.1/2/1/1,dddd"

        /** The 2x2 board whose locked clue cells already match. */
        const val NO_OP = "1/1//2,aaAA"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private data class View(
        val tile: Int,
        val originX: Int,
        val originY: Int,
        val viewWidth: Int,
        val viewHeight: Int,
    )

    private fun midend(save: String): Midend =
        Midend.fromSave(Pattern, SgtpSave.parse(save))

    private fun upstream(): String =
        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun assertLines(what: String, expected: String, actual: String) {
        val e = expected.lines()
        val a = actual.lines()
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

    private fun assertTrace(
        what: String,
        expected: JsonValue.Obj,
        actual: Midend,
        tile: Int,
    ) {
        assertLines(
            what,
            TraceNormalizer.normalize(expected, upstream()),
            TraceNormalizer.normalize(KotlinTraceSink.stateTrace(actual, tile), upstream()),
        )
    }

    private fun exactView(save: String, tile: Int): View {
        val size = Pattern.computeSize(midend(save).params, tile)
        return View(tile, 0, 0, size.width, size.height)
    }

    private fun letterboxView(save: String, width: Int, height: Int): View {
        val geometry = midend(save).geometryFor(width, height)
        return View(
            geometry.tileSize,
            geometry.originX,
            geometry.originY,
            width,
            height,
        )
    }

    private fun cellPoint(
        view: View,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        button: Int,
    ): OracleClient.Point = gamePoint(
        view,
        toCoord(width, x, view.tile) + view.tile / 2,
        toCoord(height, y, view.tile) + view.tile / 2,
        button,
    )

    private fun gamePoint(
        view: View,
        gameX: Int,
        gameY: Int,
        button: Int,
    ): OracleClient.Point = OracleClient.Point(
        view.originX + gameX,
        view.originY + gameY,
        button,
    )

    private fun click(
        view: View,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        button: Int,
    ): List<OracleClient.Point> {
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            Buttons.RIGHT_BUTTON -> Buttons.RIGHT_RELEASE
            else -> Buttons.MIDDLE_RELEASE
        }
        return listOf(
            cellPoint(view, width, height, x, y, button),
            cellPoint(view, width, height, x, y, release),
        )
    }

    /** Replays pointer [points] in C and in Kotlin and compares the result. */
    private fun play(
        oracle: OracleClient,
        label: String,
        save: String,
        points: List<OracleClient.Point>,
        view: View,
        actual: Midend,
        recordTrace: Boolean = false,
    ): GameState {
        val expected = oracle.pointsSized(
            save,
            points,
            width = view.viewWidth,
            height = view.viewHeight,
            recordTrace = recordTrace,
        )
        for ((index, point) in points.withIndex()) {
            val outcome = actual.processKey(
                point.x - view.originX,
                point.y - view.originY,
                point.button,
                view.tile,
            )
            if (index == 0) assertEquals("$label press outcome", KeyOutcome.SomeEffect, outcome)
        }
        assertSame(label, expected.state, actual)
        if (recordTrace) {
            assertTrace("$label trace", requireNotNull(expected.trace), actual, view.tile)
        }
        return expected.state
    }

    private fun assertCycles(
        oracle: OracleClient,
        label: String,
        save: String,
        view: View,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ) {
        val cycles = listOf(
            listOf(
                Buttons.LEFT_BUTTON to "F",
                Buttons.LEFT_BUTTON to "E",
                Buttons.LEFT_BUTTON to "U",
            ),
            listOf(
                Buttons.RIGHT_BUTTON to "E",
                Buttons.RIGHT_BUTTON to "F",
                Buttons.RIGHT_BUTTON to "U",
            ),
        )
        for ((cycleIndex, cycle) in cycles.withIndex()) {
            val actual = midend(save)
            var current = save
            for ((round, step) in cycle.withIndex()) {
                val (button, fill) = step
                val points = click(view, width, height, x, y, button)
                val before = (actual.state as Pattern.State).grid.copyOf()
                val expected = play(
                    oracle,
                    "$label cycle $cycleIndex round $round",
                    current,
                    points,
                    view,
                    actual,
                    recordTrace = round == cycle.lastIndex,
                )
                val after = actual.state as Pattern.State
                assertFalse(
                    "$label cycle $cycleIndex round $round changes the grid",
                    before.contentEquals(after.grid),
                )
                assertEquals(
                    "$label cycle $cycleIndex round $round state count",
                    round + 2,
                    actual.stateCount,
                )
                assertEquals(
                    "$label cycle $cycleIndex round $round move",
                    listOf("$fill$x,$y,1,1"),
                    lastMove(expected.state),
                )
                current = expected.state
            }
        }
    }

    @Test
    fun `layout picks the five tiles and two letterbox origins`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            for (tile in TILES) {
                val view = exactView(save, tile)
                val expected = oracle.layout(save, view.viewWidth, view.viewHeight)
                val geometry = midend(save).geometryFor(view.viewWidth, view.viewHeight)
                assertEquals("tile $tile", tile, view.tile)
                assertEquals("tile $tile geometry", tile, geometry.tileSize)
                assertEquals("tile $tile width", expected.width, geometry.width)
                assertEquals("tile $tile height", expected.height, geometry.height)
                assertEquals("tile $tile origin x", 0, geometry.originX)
                assertEquals("tile $tile origin y", 0, geometry.originY)
            }

            // A square viewport just under the next tile leaves an origin
            // on both axes, and 411x702 is the phone view of the trace
            // fixtures.
            for ((width, height, tile, originX, originY) in listOf(
                listOf(781, 781, 48, 6, 6),
                listOf(411, 702, 25, 6, 152),
            )) {
                val view = letterboxView(save, width, height)
                val expected = oracle.layout(save, width, height)
                assertEquals("$width x $height tile", tile, view.tile)
                assertEquals("$width x $height origin x", originX, view.originX)
                assertEquals("$width x $height origin y", originY, view.originY)
                assertEquals(
                    "$width x $height width",
                    expected.width,
                    Pattern.computeSize(Pattern.decodeParams("10x10"), tile).width,
                )
                assertEquals(
                    "$width x $height height",
                    expected.height,
                    Pattern.computeSize(Pattern.decodeParams("10x10"), tile).height,
                )
            }
        }

    @Test
    fun `left and right cycles at five tiles match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            for (tile in TILES) {
                val view = exactView(save, tile)
                assertEquals("tile $tile", tile, view.tile)
                assertCycles(oracle, "tile $tile", save, view, 10, 10, 0, 0)
            }
        }

    @Test
    fun `cycles at a nonzero letterbox origin match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            for ((width, height) in listOf(781 to 781, 411 to 702)) {
                val view = letterboxView(save, width, height)
                assertTrue("$width x $height origin x", view.originX > 0)
                assertTrue("$width x $height origin y", view.originY > 0)
                assertCycles(
                    oracle,
                    "$width x $height",
                    save,
                    view,
                    10,
                    10,
                    2,
                    3,
                )
            }
        }

    @Test
    fun `drags snap and clamp and the middle rectangle does not snap`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            for ((width, height) in listOf(
                384 to 384,
                768 to 768,
                1008 to 1008,
                1311 to 1311,
                1600 to 1600,
                900 to 900,
            )) {
                val view = letterboxView(save, width, height)
                val tile = view.tile
                val label = "tile $tile"

                // A diagonal left drag snaps to the dominant axis.
                var current = save
                val actual = midend(save)
                var expected = play(
                    oracle,
                    "$label snapped drag",
                    current,
                    listOf(
                        cellPoint(view, 10, 10, 0, 0, Buttons.LEFT_BUTTON),
                        cellPoint(view, 10, 10, 3, 1, Buttons.LEFT_DRAG),
                        cellPoint(view, 10, 10, 3, 1, Buttons.LEFT_RELEASE),
                    ),
                    view,
                    actual,
                    recordTrace = true,
                )
                assertEquals("$label snapped move", listOf("F0,0,4,1"), lastMove(expected.state))
                assertEquals("$label snapped grid", Pattern.GRID_FULL, cell(expected, 0, 0))
                assertEquals("$label snapped count", 2, actual.stateCount)
                current = expected.state

                // A vertical drag snaps the other way.
                expected = play(
                    oracle,
                    "$label vertical drag",
                    current,
                    listOf(
                        cellPoint(view, 10, 10, 2, 5, Buttons.LEFT_BUTTON),
                        cellPoint(view, 10, 10, 3, 7, Buttons.LEFT_DRAG),
                        cellPoint(view, 10, 10, 3, 7, Buttons.LEFT_RELEASE),
                    ),
                    view,
                    actual,
                    recordTrace = true,
                )
                assertEquals("$label vertical move", listOf("F2,5,1,3"), lastMove(expected.state))
                assertEquals("$label vertical count", 3, actual.stateCount)

                // A drag past the right edge clamps to the last cell.
                expected = play(
                    oracle,
                    "$label clamped drag",
                    expected.state,
                    listOf(
                        cellPoint(view, 10, 10, 8, 2, Buttons.LEFT_BUTTON),
                        gamePoint(
                            view,
                            toCoord(10, 0, tile) + 10 * tile,
                            toCoord(10, 2, tile) + tile / 2,
                            Buttons.LEFT_DRAG,
                        ),
                        gamePoint(
                            view,
                            toCoord(10, 0, tile) + 10 * tile,
                            toCoord(10, 2, tile) + tile / 2,
                            Buttons.LEFT_RELEASE,
                        ),
                    ),
                    view,
                    actual,
                    recordTrace = true,
                )
                assertEquals("$label clamped move", listOf("F8,2,2,1"), lastMove(expected.state))
                assertEquals("$label clamped count", 4, actual.stateCount)

                // A middle drag over two filled cells marks the rectangle
                // unknown and is not snapped.
                val middle = midend(save)
                var middleSave = save
                middleSave = play(
                    oracle,
                    "$width x $height fill 0,0",
                    middleSave,
                    click(view, 10, 10, 0, 0, Buttons.LEFT_BUTTON),
                    view,
                    middle,
                ).state
                middleSave = play(
                    oracle,
                    "$width x $height fill 1,1",
                    middleSave,
                    click(view, 10, 10, 1, 1, Buttons.LEFT_BUTTON),
                    view,
                    middle,
                ).state
                val middleExpected = play(
                    oracle,
                    "$width x $height middle rectangle",
                    middleSave,
                    listOf(
                        cellPoint(view, 10, 10, 0, 0, Buttons.MIDDLE_BUTTON),
                        cellPoint(view, 10, 10, 1, 1, Buttons.MIDDLE_DRAG),
                        cellPoint(view, 10, 10, 1, 1, Buttons.MIDDLE_RELEASE),
                    ),
                    view,
                    middle,
                    recordTrace = true,
                )
                assertEquals(
                    "$width x $height middle move",
                    listOf("U0,0,2,2"),
                    lastMove(middleExpected.state),
                )
                assertEquals("$width x $height middle count", 4, middle.stateCount)

                // A middle press and release on an unknown cell is UI only.
                val noop = midend(save)
                val noopExpected = play(
                    oracle,
                    "$width x $height middle no-op",
                    save,
                    click(view, 10, 10, 4, 4, Buttons.MIDDLE_BUTTON),
                    view,
                    noop,
                    recordTrace = true,
                )
                assertEquals("$width x $height middle no-op count", 1, noop.stateCount)
                assertTrue("$width x $height middle no-op grid", cell(noopExpected, 4, 4) == Pattern.GRID_UNKNOWN)
            }
        }

    @Test
    fun `inert clicks and immutable cells follow the C state rules`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("pattern", "10x10", SEED).state
            val view = exactView(save, 24)

            // The clue band ends one pixel left of TOCOORD(0): Unused.
            val inert = midend(save)
            val inertPoints = listOf(
                gamePoint(
                    view,
                    toCoord(10, 0, view.tile) - 1,
                    toCoord(10, 0, view.tile) + view.tile / 2,
                    Buttons.LEFT_BUTTON,
                ),
                gamePoint(
                    view,
                    toCoord(10, 0, view.tile) - 1,
                    toCoord(10, 0, view.tile) + view.tile / 2,
                    Buttons.LEFT_RELEASE,
                ),
            )
            val inertExpected = oracle.pointsSized(
                save,
                inertPoints,
                width = view.viewWidth,
                height = view.viewHeight,
                recordTrace = true,
            )
            for (point in inertPoints) {
                assertEquals(
                    "clue band outcome",
                    KeyOutcome.Unused,
                    inert.processKey(point.x, point.y, point.button, view.tile),
                )
            }
            assertSame("clue band", inertExpected.state, inert)
            assertTrue("clue band keeps the grid", beforeGrid(save).contentEquals((inert.state as Pattern.State).grid))
            assertEquals("clue band count", 1, inert.stateCount)
            assertTrace("clue band trace", requireNotNull(inertExpected.trace), inert, view.tile)

            // A press at TOCOORD(0) is inside the first cell and moves.
            val live = midend(save)
            val liveExpected = play(
                oracle,
                "first cell left edge",
                save,
                listOf(
                    gamePoint(
                        view,
                        toCoord(10, 0, view.tile),
                        toCoord(10, 0, view.tile) + view.tile / 2,
                        Buttons.LEFT_BUTTON,
                    ),
                    gamePoint(
                        view,
                        toCoord(10, 0, view.tile),
                        toCoord(10, 0, view.tile) + view.tile / 2,
                        Buttons.LEFT_RELEASE,
                    ),
                ),
                view,
                live,
                recordTrace = true,
            )
            assertEquals("first cell move", listOf("F0,0,1,1"), lastMove(liveExpected.state))
            assertEquals("first cell count", 2, live.stateCount)
            assertFalse("first cell changes the grid", beforeGrid(save).contentEquals((live.state as Pattern.State).grid))

            // A tap in the letterbox is inert.
            val letterbox = letterboxView(save, 411, 702)
            val tap = listOf(
                OracleClient.Point(letterbox.originX / 2, letterbox.originY / 2, Buttons.LEFT_BUTTON),
                OracleClient.Point(letterbox.originX / 2, letterbox.originY / 2, Buttons.LEFT_RELEASE),
            )
            val letterboxExpected = oracle.pointsSized(
                save,
                tap,
                width = letterbox.viewWidth,
                height = letterbox.viewHeight,
                recordTrace = true,
            )
            val letterboxActual = midend(save)
            for (point in tap) {
                assertEquals(
                    "letterbox outcome",
                    KeyOutcome.Unused,
                    letterboxActual.processKey(
                        point.x - letterbox.originX,
                        point.y - letterbox.originY,
                        point.button,
                        letterbox.tile,
                    ),
                )
            }
            assertSame("letterbox tap", letterboxExpected.state, letterboxActual)
            assertEquals("letterbox tap count", 1, letterboxActual.stateCount)
            assertTrace("letterbox tap trace", requireNotNull(letterboxExpected.trace), letterboxActual, letterbox.tile)

            // A click on a locked clue cell cycles nothing and adds no
            // move, because the release finds no cell that would change.
            val locked = saveText("4x4", HARD_SUFFIX)
            val lockedView = exactView(locked, 24)
            val lockedActual = midend(locked)
            val lockedExpected = play(
                oracle,
                "immutable click",
                locked,
                click(lockedView, 4, 4, 3, 0, Buttons.LEFT_BUTTON),
                lockedView,
                lockedActual,
                recordTrace = true,
            )
            assertEquals("immutable click count", 1, lockedActual.stateCount)
            assertTrue("immutable click keeps the grid", beforeGrid(locked).contentEquals((lockedActual.state as Pattern.State).grid))
            assertTrue("immutable click has no move", lastMove(lockedExpected.state).isEmpty())
        }

    @Test
    fun `an immutable select adds a state without a visible change`() =
        OracleClient().use { oracle ->
            val save = saveText("4x4", HARD_SUFFIX)
            val sequence = listOf(
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT,
            )
            val expected = oracle.keys(save, sequence)
            val actual = midend(save)
            for (key in sequence) actual.processKey(0, 0, key, 24)
            assertSame("immutable select", expected, actual)
            assertEquals("immutable select count", 2, actual.stateCount)
            assertEquals("immutable select move", listOf("U3,0,1,1"), lastMove(expected.state))
            assertEquals("immutable select status", 0, actual.status())
            assertEquals(
                "immutable select keeps the grid",
                Pattern.GRID_EMPTY,
                (actual.state as Pattern.State).grid[3],
            )
            assertTrue(
                "the selected cell stays immutable",
                (actual.state as Pattern.State).common.immutable[3],
            )
            val trace = oracle.trace(save, sequence)
            assertTrace("immutable select trace", trace, actual, 24)
            assertEquals(
                "the ring is drawn",
                2,
                TraceNormalizer.normalize(trace, upstream())
                    .lines()
                    .count { it.startsWith("poly -1 6 ") },
            )
        }

    @Test
    fun `the cursor ring reaches the corners`() = OracleClient().use { oracle ->
        val save = oracle.newGame("pattern", "10x10", SEED).state
        val sequence = ArrayList<Int>()
        repeat(9) { sequence += Buttons.CURSOR_RIGHT }
        repeat(9) { sequence += Buttons.CURSOR_DOWN }
        repeat(9) { sequence += Buttons.CURSOR_LEFT }
        repeat(9) { sequence += Buttons.CURSOR_UP }

        val expected = oracle.keys(save, sequence)
        val actual = midend(save)
        for (key in sequence) actual.processKey(0, 0, key, 24)
        assertSame("cursor corners", expected, actual)
        assertEquals("the corner walk makes no move", 1, actual.stateCount)

        val traceObject = oracle.trace(save, sequence)
        assertTrace("cursor corners trace", traceObject, actual, 24)
        val trace = TraceNormalizer.normalize(traceObject, upstream())
        assertEquals(
            "two cursor rings",
            2,
            trace.lines().count { it.startsWith("poly -1 6 ") },
        )
    }

    @Test
    fun `the no-op win matches the oracle and raises one event`() =
        OracleClient().use { oracle ->
            val save = saveText("2x2", NO_OP)
            assertEquals(0, oracle.id(save).status)

            val sequence = listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT)
            val expected = oracle.keys(save, sequence)
            assertEquals(1, expected.status)
            val actual = midend(save)
            for (key in sequence) actual.processKey(0, 0, key, 24)
            assertSame("no-op win", expected, actual)
            assertEquals("no-op win count", 2, actual.stateCount)
            assertEquals("no-op win move", listOf("U0,0,1,1"), lastMove(expected.state))
            assertEquals("no-op win status", 1, actual.status())
            assertEquals(
                "no-op win keeps the grid",
                Pattern.GRID_EMPTY,
                (actual.state as Pattern.State).grid[0],
            )
            assertTrue("no-op win raises one event", actual.takeCompletedEvent())
            assertFalse("no second event", actual.takeCompletedEvent())

            val traceObject = oracle.trace(save, sequence)
            assertTrace("no-op win trace", traceObject, actual, 24)
            val trace = TraceNormalizer.normalize(traceObject, upstream())
            assertFalse(
                "the completed frame has no status line",
                trace.lines().any { it.startsWith("status ") },
            )
        }

    @Test
    fun `requestKeys is arrow mode 3 and the full hint is mode 1`() =
        OracleClient().use { oracle ->
            for (params in PRESETS) {
                val state = oracle.newGame("pattern", params, SEED).state
                for (fullCursor in listOf(false, true)) {
                    val expected = oracle.requestKeys(state, fullCursor)
                    val actual = Pattern.requestKeys(Pattern.decodeParams(params), fullCursor)
                    assertEquals(
                        "'$params' $fullCursor C mode",
                        if (fullCursor) 1 else 3,
                        expected.arrowMode,
                    )
                    assertEquals(
                        "'$params' $fullCursor Kotlin mode",
                        if (fullCursor) ArrowMode.ARROWS_ONLY else ArrowMode.ARROWS_LEFT_RIGHT,
                        actual.arrowMode,
                    )
                    assertTrue("'$params' $fullCursor C labels", expected.labels.isEmpty())
                    assertTrue("'$params' $fullCursor Kotlin labels", actual.labels.isEmpty())
                }
            }
        }

    private fun cell(state: GameState, x: Int, y: Int): Int {
        val midend = midend(state.state)
        val pattern = midend.state as Pattern.State
        return pattern.grid[y * pattern.width + x]
    }

    private fun beforeGrid(save: String): IntArray =
        (midend(save).state as Pattern.State).grid.copyOf()

    private fun lastMove(save: String): List<String> =
        SgtpSave.parse(save).values("MOVE").takeLast(1)

    private fun toCoord(d: Int, cell: Int, tile: Int): Int =
        3 * tile / 4 + tile / 2 + tile * (d / 5 + 2 + cell)

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Pattern.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }
}
