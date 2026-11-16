package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: tracks.
 *
 * The layout, real clicks in the cell centre and in the four edge
 * bands, drags, the letterbox and the border strip, the right and
 * middle buttons, the keyboard cursor, and the vacuous events that must
 * not add a move.
 *
 * Each vacuous case has a positive control next to it, so a missing
 * input path fails instead of passing silently.
 */
class TracksInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 120_000)
    fun `the layout matches the oracle at several viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tracks", "8x8dt", SEED).state
            val midend = Midend.newGame(Tracks, "8x8dt", SEED)
            for (viewport in listOf(
                411 to 702,
                720 to 1280,
                1024 to 600,
                200 to 200,
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

    @Test(timeout = 300_000)
    fun `a real square click at several tile sizes matches the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tracks", "8x8dt", SEED).state
            for (tileSize in listOf(48, 63, 82, 100)) {
                val geom = geometry(tileSize)
                val board = Tracks.computeSize(Tracks.decodeParams("8x8dt"), tileSize)
                val points = click(geom.cx(2), geom.cy(2))
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
                apply(midend, points, tileSize)
                assertEquals("tile $tileSize id", expected.state.id, midend.id())
                assertEquals(
                    "tile $tileSize canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("tile $tileSize states", 2, midend.stateCount)
                assertEquals("tile $tileSize move", "TS2,2", moveOf(midend))
                assertEquals("tile $tileSize status", expected.state.status, midend.status())
                assertLines(
                    "tile $tileSize trace",
                    TraceNormalizer.normalize(
                        expected.trace!!,
                        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                    ),
                    TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, tileSize),
                        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                    ),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the four edge bands use the C edge move`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        val geom = geometry(63)
        val quarter = geom.tile / 4
        val cases = listOf(
            "TU2,2" to listOf(geom.cx(2), geom.cy(2) - quarter),
            "TD2,2" to listOf(geom.cx(2), geom.cy(2) + quarter),
            "TL2,2" to listOf(geom.cx(2) - quarter, geom.cy(2)),
            "TR2,2" to listOf(geom.cx(2) + quarter, geom.cy(2)),
        )
        for ((move, point) in cases) {
            val points = listOf(
                OracleClient.Point(point[0], point[1], Buttons.LEFT_BUTTON),
                OracleClient.Point(point[0], point[1], Buttons.LEFT_RELEASE),
            )
            val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
            apply(midend, points, 63)
            assertEquals("move for $move", move, moveOf(midend))
            val expected = oracle.pointsSized(
                save,
                points,
                width = geom.size,
                height = geom.size,
            )
            assertEquals("id for $move", expected.state.id, midend.id())
            assertEquals("states for $move", 2, midend.stateCount)
        }

        // One pixel toward the centre leaves the band and becomes a
        // square click.
        val points = click(geom.cx(2), geom.cy(2) - quarter + 1)
        val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
        apply(midend, points, 63)
        assertEquals("one pixel inside the band", "TS2,2", moveOf(midend))

        // The cell centre of the neighbour is a square click there.
        val next = click(geom.cx(3), geom.cy(2))
        val neighbour = Midend.fromSave(Tracks, SgtpSave.parse(save))
        apply(neighbour, next, 63)
        assertEquals("the adjacent cell", "TS3,2", moveOf(neighbour))
    }

    @Test(timeout = 120_000)
    fun `a letterbox and a border click are inert`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val geom = midend.geometryFor(411, 702)
        assertEquals(21, geom.originX)
        assertEquals(167, geom.originY)

        val before = midend.id()
        val text = Tracks.textFormat(midend.state)
        val count = midend.stateCount
        val letterbox = listOf(
            OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, Buttons.LEFT_RELEASE),
        )
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(-geom.originX, -geom.originY, Buttons.LEFT_BUTTON, geom.tileSize),
        )
        // A release always redraws, even when the press was off grid.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(-geom.originX, -geom.originY, Buttons.LEFT_RELEASE, geom.tileSize),
        )
        assertEquals("letterbox state count", count, midend.stateCount)
        assertEquals("letterbox id", before, midend.id())
        assertEquals("letterbox text", text, Tracks.textFormat(midend.state))

        // A press in the BORDER strip maps to -1 as well.
        val borderGeom = geometry(63)
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(1, borderGeom.cy(2), Buttons.LEFT_BUTTON, 63),
        )
        assertEquals("border press", count, midend.stateCount)

        // Positive control: the cell centre is a real move.
        val beforeSflags = (midend.state as Tracks.State).sflags.copyOf()
        val positive = click(borderGeom.cx(2), borderGeom.cy(2))
        apply(midend, positive, 63)
        assertEquals("positive control", count + 1, midend.stateCount)
        assertFalse(
            "positive board",
            (midend.state as Tracks.State).sflags.contentEquals(beforeSflags),
        )

        val expected = oracle.pointsSized(
            save,
            listOf(
                OracleClient.Point(geom.originX, geom.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(geom.originX, geom.originY, Buttons.LEFT_RELEASE),
            ),
            width = 411,
            height = 702,
        )
        val oracleMidend = Midend.fromSave(Tracks, SgtpSave.parse(save))
        oracleMidend.processKey(-geom.originX, -geom.originY, Buttons.LEFT_BUTTON, geom.tileSize)
        oracleMidend.processKey(-geom.originX, -geom.originY, Buttons.LEFT_RELEASE, geom.tileSize)
        assertEquals(expected.state.id, oracleMidend.id())
        assertEquals(1, oracleMidend.stateCount)
    }

    @Test(timeout = 120_000)
    fun `a release after an off-grid press is inert`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val geom = geometry(63)
        // The press is in the border strip, so clickx/clicky stay at
        // their port value 0. The release is in a real cell; the click
        // path then fails the FROMCOORD test and returns UI_UPDATE.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(1, geom.cy(2), Buttons.LEFT_BUTTON, 63),
        )
        val outcome = midend.processKey(geom.cx(2), geom.cy(2), Buttons.LEFT_RELEASE, 63)
        assertEquals(KeyOutcome.SomeEffect, outcome)
        assertEquals("no move after the inert release", 1, midend.stateCount)
    }

    @Test(timeout = 300_000)
    fun `real drags match the oracle at every tile size`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        for (tileSize in listOf(48, 63, 82, 100)) {
            val geom = geometry(tileSize)
            val cases = listOf(
                "horizontal" to listOf(
                    cell(1, 4, geom, Buttons.LEFT_BUTTON),
                    cell(3, 4, geom, Buttons.LEFT_DRAG),
                    cell(5, 4, geom, Buttons.LEFT_DRAG),
                    cell(5, 4, geom, Buttons.LEFT_RELEASE),
                ),
                "vertical" to listOf(
                    cell(2, 1, geom, Buttons.LEFT_BUTTON),
                    cell(2, 2, geom, Buttons.LEFT_DRAG),
                    cell(2, 4, geom, Buttons.LEFT_DRAG),
                    cell(2, 4, geom, Buttons.LEFT_RELEASE),
                ),
                "one cell" to listOf(
                    cell(2, 2, geom, Buttons.LEFT_BUTTON),
                    cell(2, 2, geom, Buttons.LEFT_RELEASE),
                ),
                "clue" to listOf(
                    cell(1, 1, geom, Buttons.LEFT_BUTTON),
                    cell(1, 1, geom, Buttons.LEFT_RELEASE),
                ),
                "diagonal" to listOf(
                    cell(1, 3, geom, Buttons.LEFT_BUTTON),
                    cell(2, 4, geom, Buttons.LEFT_DRAG),
                    cell(3, 5, geom, Buttons.LEFT_RELEASE),
                ),
                "right drag" to listOf(
                    cell(1, 5, geom, Buttons.RIGHT_BUTTON),
                    cell(3, 5, geom, Buttons.RIGHT_DRAG),
                    cell(5, 5, geom, Buttons.RIGHT_DRAG),
                    cell(5, 5, geom, Buttons.RIGHT_RELEASE),
                ),
                "middle drag" to listOf(
                    cell(1, 6, geom, Buttons.MIDDLE_BUTTON),
                    cell(3, 6, geom, Buttons.MIDDLE_DRAG),
                    cell(5, 6, geom, Buttons.MIDDLE_RELEASE),
                ),
            )
            for ((name, points) in cases) {
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = geom.size,
                    height = geom.size,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
                apply(midend, points, geom.tileSize)
                assertEquals("$name tile $tileSize id", expected.state.id, midend.id())
                assertEquals(
                    "$name tile $tileSize canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "$name tile $tileSize status",
                    expected.state.status,
                    midend.status(),
                )
                assertLines(
                    "$name tile $tileSize trace",
                    TraceNormalizer.normalize(
                        expected.trace!!,
                        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                    ),
                    TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, geom.tileSize),
                        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                    ),
                )
            }

            // The diagonal drag collapses to the press cell, so the
            // release takes the click path and its cell does not match
            // the press: the event is inert.
            val diagonal = Midend.fromSave(Tracks, SgtpSave.parse(save))
            apply(diagonal, cases[4].second, geom.tileSize)
            assertEquals("diagonal tile $tileSize", 1, diagonal.stateCount)
            assertEquals("", moveOf(diagonal))

            // A multi-cell drag produces one multi-item move.
            val horizontal = Midend.fromSave(Tracks, SgtpSave.parse(save))
            apply(horizontal, cases[0].second, geom.tileSize)
            assertTrue(moveOf(horizontal).contains(";"))
            assertEquals("horizontal tile $tileSize", 2, horizontal.stateCount)
        }
    }

    @Test(timeout = 120_000)
    fun `a drag over marked cells skips them but still adds a move`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tracks", "8x8dt", SEED).state
            val geom = geometry(48)
            val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
            apply(midend, click(geom.cx(1), geom.cy(3)), geom.tileSize)
            assertEquals(2, midend.stateCount)
            val drag = listOf(
                cell(1, 4, geom, Buttons.LEFT_BUTTON),
                cell(1, 3, geom, Buttons.LEFT_DRAG),
                cell(1, 3, geom, Buttons.LEFT_RELEASE),
            )
            apply(midend, drag, geom.tileSize)
            val move = moveOf(midend)
            assertEquals("only the unmarked cell flips", "TS1,4", move)
            assertEquals(3, midend.stateCount)

            // The oracle agrees on the resulting state.
            val oracleMidend = Midend.fromSave(Tracks, SgtpSave.parse(save))
            apply(oracleMidend, click(geom.cx(1), geom.cy(3)), geom.tileSize)
            val expected = oracle.pointsSized(
                oracleMidend.serialise().serialise(),
                drag,
                width = geom.size,
                height = geom.size,
            )
            assertEquals(expected.state.id, midend.id())

        }

    @Test(timeout = 300_000)
    fun `right and middle clicks follow the C rules`() = OracleClient().use { oracle ->
        val save = oracle.newGame("tracks", "8x8dt", SEED).state
        for (tileSize in listOf(48, 63, 82, 100)) {
            val geom = geometry(tileSize)
            for ((button, move) in listOf(
                Buttons.RIGHT_BUTTON to "NS2,3",
                Buttons.MIDDLE_BUTTON to "TS2,3",
            )) {
                val release = button + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON)
                val points = listOf(
                    OracleClient.Point(geom.cx(2), geom.cy(3), button),
                    OracleClient.Point(geom.cx(2), geom.cy(3), release),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = geom.size,
                    height = geom.size,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
                apply(midend, points, geom.tileSize)
                assertEquals("move for button $button tile $tileSize", move, moveOf(midend))
                assertEquals(
                    "button $button tile $tileSize state",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("button $button tile $tileSize status", expected.state.status, midend.status())
                assertLines(
                    "button $button tile $tileSize trace",
                    TraceNormalizer.normalize(
                        expected.trace!!,
                        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                    ),
                    TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, geom.tileSize),
                        UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                    ),
                )
            }
        }

        // A right click on an edge that carries E_TRACK is refused.
        val geom = geometry(48)
        val trackThenRight = Midend.fromSave(Tracks, SgtpSave.parse(save))
        apply(
            trackThenRight,
            listOf(
                OracleClient.Point(geom.cx(2) - geom.tile / 4, geom.cy(2), Buttons.LEFT_BUTTON),
                OracleClient.Point(geom.cx(2) - geom.tile / 4, geom.cy(2), Buttons.LEFT_RELEASE),
            ),
            geom.tileSize,
        )
        assertEquals("TL2,2", moveOf(trackThenRight))
        val count = trackThenRight.stateCount
        val text = Tracks.textFormat(trackThenRight.state)
        apply(
            trackThenRight,
            listOf(
                OracleClient.Point(geom.cx(2) - geom.tile / 4, geom.cy(2), Buttons.RIGHT_BUTTON),
                OracleClient.Point(geom.cx(2) - geom.tile / 4, geom.cy(2), Buttons.RIGHT_RELEASE),
            ),
            geom.tileSize,
        )
        assertEquals("an E_TRACK edge refuses NOTRACK", count, trackThenRight.stateCount)
        assertEquals(text, Tracks.textFormat(trackThenRight.state))
    }

    @Test(timeout = 120_000)
    fun `a click on a clue is inert but the neighbour flips`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tracks", "8x8dt", SEED).state
            val geom = geometry(48)
            val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
            val before = midend.id()
            val count = midend.stateCount
            apply(midend, click(geom.cx(0), geom.cy(3)), geom.tileSize)
            assertEquals("the entrance clue is inert", count, midend.stateCount)
            assertEquals(before, midend.id())

            apply(midend, click(geom.cx(1), geom.cy(3)), geom.tileSize)
            assertEquals("the neighbour flips", count + 1, midend.stateCount)

            val oracleMidend = Midend.fromSave(Tracks, SgtpSave.parse(save))
            apply(oracleMidend, click(geom.cx(0), geom.cy(3)), geom.tileSize)
            assertEquals(1, oracleMidend.stateCount)
            apply(oracleMidend, click(geom.cx(1), geom.cy(3)), geom.tileSize)
            assertEquals(2, oracleMidend.stateCount)
            assertEquals(oracleMidend.id(), midend.id())
        }

    @Test(timeout = 120_000)
    fun `the keyboard cursor and selects match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("tracks", "8x8dt", SEED).state
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val sequences = listOf(
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT),
                listOf(
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_SELECT2,
                ),
                // The corner skip: a move that lands on both even
                // coordinates takes a second step.
                listOf(
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_DOWN,
                    Buttons.CURSOR_SELECT,
                    Buttons.CURSOR_RIGHT,
                    Buttons.CURSOR_SELECT,
                ),
                // The clamp at 1 and at 2w-1.
                listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT),
                (0 until 20).map { Buttons.CURSOR_RIGHT } +
                    listOf(Buttons.CURSOR_SELECT),
                // A clue centre: six downs from (1,1) reaches the
                // entrance at (0,3).
                listOf(Buttons.CURSOR_RIGHT) + (0 until 6).map { Buttons.CURSOR_DOWN } +
                    listOf(Buttons.CURSOR_SELECT),
            )
            for (sequence in sequences) {
                val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
                var index = 0
                for (key in sequence) {
                    midend.processKey(0, 0, key, 33)
                    index++
                }
                val expectedState = oracle.keys(save, sequence)
                assertEquals("keys $sequence id", expectedState.id, midend.id())
                assertEquals(
                    "keys $sequence canonical",
                    expectedState.state,
                    midend.serialise().serialise(),
                )
                val expected = TraceNormalizer.normalize(
                    oracle.trace(save, sequence = sequence),
                    upstream,
                )
                val actual = TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, 33),
                    upstream,
                )
                assertLines("cursor $sequence", expected, actual)
            }
        }

    @Test(timeout = 120_000)
    fun `a modified arrow is unused and an undo key still works`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT, 33),
        )
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, 33),
        )
        assertEquals(1, midend.stateCount)

        val geom = geometry(33)
        apply(midend, click(geom.cx(2), geom.cy(2)), geom.tileSize)
        assertEquals(2, midend.stateCount)
        // 'u' and 'r' are not game keys: the game returns Unused and
        // the midend's one-key shortcuts undo and redo.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'u'.code, 33))
        assertEquals(1, midend.statePos)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'r'.code, 33))
        assertEquals(2, midend.statePos)
        // 'q' and 'n' are shell keys.
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, 'q'.code, 33))
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, 'n'.code, 33))
    }

    @Test
    fun `current key labels follow the cursor position`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        assertEquals("hidden cursor", "", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 33)
        assertEquals("Track", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("X", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))

        // Select sets the square; the label then says Clear.
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, 33)
        assertEquals("Clear", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))

        // Move to the vertical edge at (2,1).
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 33)
        assertEquals("Track", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        midend.processKey(0, 0, Buttons.CURSOR_SELECT, 33)
        assertEquals("Clear", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))

        // A horizontal edge on a blank cell: right to (3,1), then down
        // three times. (3,2) is above the clue at (1,1) and (3,3) is
        // its centre; (3,4) is above the blank (1,2).
        midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 33)
        repeat(5) { midend.processKey(0, 0, Buttons.CURSOR_DOWN, 33) }
        assertEquals("Track", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("X", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))
        midend.processKey(0, 0, Buttons.CURSOR_SELECT2, 33)
        assertEquals("", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT))
        assertEquals("Clear", Tracks.currentKeyLabel(midend.ui, midend.state, Buttons.CURSOR_SELECT2))
    }

    @Test(timeout = 120_000)
    fun `a real change and an inert click are not the same`() {
        val midend = Midend.newGame(Tracks, "8x8dt", SEED)
        val geom = geometry(63)
        val id = midend.id()
        val count = midend.stateCount
        val status = midend.status()
        val before = (midend.state as Tracks.State).sflags.copyOf()
        apply(midend, click(geom.cx(2), geom.cy(2)), geom.tileSize)
        assertFalse(
            "the board changes",
            (midend.state as Tracks.State).sflags.contentEquals(before),
        )
        assertEquals("one move", count + 1, midend.stateCount)
        assertEquals("status stays open", status, midend.status())

        val after = (midend.state as Tracks.State).sflags.copyOf()
        val afterText = Tracks.textFormat(midend.state)
        val afterId = midend.id()
        val afterCount = midend.stateCount
        apply(midend, click(0, 0), geom.tileSize)
        assertEquals("the inert click adds no move", afterCount, midend.stateCount)
        assertEquals("the inert click keeps the id", afterId, midend.id())
        assertTrue(
            "the inert click keeps the board",
            (midend.state as Tracks.State).sflags.contentEquals(after),
        )
        assertEquals("the inert click keeps the text", afterText, Tracks.textFormat(midend.state))
        assertEquals("the inert click keeps the status", status, midend.status())
    }

    @Test(timeout = 120_000)
    fun `a real click on an error-marked board matches the oracle`() =
        OracleClient().use { oracle ->
            val solved = Midend.newGame(Tracks, "8x8dt", SEED)
            assertTrue(solved.solve())
            val desc = fullPathDesc(
                solved.state as Tracks.State,
                rowDelta = 1,
                rowIndex = 2,
            )
            val base = Midend.fromGameId(Tracks, "8x8:$desc")
            val save = withMove(base.serialise().serialise(), "")
            val full = base.state as Tracks.State
            val blank = (0 until 64).first {
                full.sflags[it] and 1 == 0 && (full.sflags[it] ushr 16) and 15 == 0
            }
            val x = blank % 8
            val y = blank / 8
            val geom = geometry(48)
            val points = listOf(
                OracleClient.Point(geom.cx(x), geom.cy(y), Buttons.RIGHT_BUTTON),
                OracleClient.Point(geom.cx(x), geom.cy(y), Buttons.RIGHT_RELEASE),
            )

            val expected = oracle.pointsSized(
                save,
                points,
                width = geom.size,
                height = geom.size,
                recordTrace = true,
            )
            val midend = Midend.fromSave(Tracks, SgtpSave.parse(save))
            val before = midend.state as Tracks.State
            assertEquals("the load rebuilds the wrong row", 1, before.numErrors[8 + 2])
            apply(midend, points, geom.tileSize)
            val after = midend.state as Tracks.State
            assertEquals("the row stays marked", 1, after.numErrors[8 + 2])
            assertEquals("the new no-track square", 2, after.sflags[blank] and 2)
            assertFalse(after.completed)
            assertEquals("status after the error click", 0, midend.status())

            assertEquals("error id", expected.state.id, midend.id())
            assertEquals("error canonical", expected.state.state, midend.serialise().serialise())
            assertEquals("error status", expected.state.status, midend.status())
            assertLines(
                "error trace",
                TraceNormalizer.normalize(
                    expected.trace!!,
                    UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                ),
                TraceNormalizer.normalize(
                    KotlinTraceSink.stateTrace(midend, geom.tileSize),
                    UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock")),
                ),
            )
        }

    private fun click(x: Int, y: Int): List<OracleClient.Point> = listOf(
        OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
        OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
    )

    private fun cell(x: Int, y: Int, geom: Geom, button: Int): OracleClient.Point =
        OracleClient.Point(geom.cx(x), geom.cy(y), button)

    private fun apply(midend: Midend, points: List<OracleClient.Point>, tileSize: Int) {
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, tileSize)
        }
    }

    private fun moveOf(midend: Midend): String =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE").lastOrNull() ?: ""

    private data class Geom(
        val tileSize: Int,
        val tile: Int,
        val border: Int,
        val size: Int,
    ) {
        fun cx(x: Int): Int = (x + 1) * tile + border + tile / 2
        fun cy(y: Int): Int = (y + 1) * tile + border + tile / 2
    }

    private fun geometry(tileSize: Int): Geom {
        val tile = (tileSize / 6) * 6
        val maxBorder = tile / 8
        var border = maxBorder
        if (border <= 5) border = minOf(tileSize % 6, maxBorder)
        return Geom(tileSize, tile, border, (8 + 2) * tile + 2 * border)
    }

    private fun fullPathDesc(
        state: Tracks.State,
        rowDelta: Int = 0,
        rowIndex: Int = -1,
    ): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder()
        for (i in 0 until w * h) {
            val f = (state.sflags[i] ushr 16) and 15
            if (f != 0) {
                out.append(if (f < 10) '0' + f else 'A' + (f - 10))
            } else {
                out.append('a')
            }
        }
        for (x in 0 until w) {
            out.append(',')
            if (x == state.numbers.colS) out.append('S')
            out.append(state.numbers.values[x])
        }
        for (y in 0 until h) {
            out.append(',')
            if (y == state.numbers.rowS) out.append('S')
            val value = state.numbers.values[w + y] +
                if (y == rowIndex) rowDelta else 0
            out.append(value)
        }
        return out.toString()
    }

    private fun withMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
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
