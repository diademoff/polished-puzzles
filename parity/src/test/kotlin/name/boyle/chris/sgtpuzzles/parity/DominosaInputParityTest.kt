package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic and ui. Game: dominosa.
 *
 * The layout, real clicks in the four halves of a cell, right clicks on
 * a domino, the measured boundary table, the letterbox and border
 * strips, drags and lone releases, the keyboard cursor, the digit
 * highlights, undo and redo, and a real click completion.
 *
 * Each vacuous case has a positive control next to it, so a missing
 * input path fails instead of passing silently.
 */
class DominosaInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 180_000)
    fun `the layout matches the oracle at several viewports`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("dominosa", "3dt", SEED).state
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            for (viewport in listOf(
                411 to 702,
                781 to 781,
                1080 to 1920,
                320 to 480,
                1000 to 1000,
            )) {
                val expected = oracle.layout(save, viewport.first, viewport.second)
                val geometry = midend.geometryFor(viewport.first, viewport.second)
                assertEquals(
                    "layout ${viewport.first}x${viewport.second}",
                    "${expected.width}x${expected.height}",
                    "${geometry.width}x${geometry.height}",
                )
                // The oracle centres the board with `(view - board) / 2`;
                // this checks the letterbox origin at every viewport, not
                // only at the 411x702 one.
                assertEquals(
                    "origin x ${viewport.first}x${viewport.second}",
                    (viewport.first - expected.width) / 2,
                    geometry.originX,
                )
                assertEquals(
                    "origin y ${viewport.first}x${viewport.second}",
                    (viewport.second - expected.height) / 2,
                    geometry.originY,
                )
                assertTrue(geometry.tileSize >= 1)
            }
        }

    @Test(timeout = 300_000)
    fun `a real click in each half matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        for (tileSize in listOf(48, 63, 82, 100)) {
            val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
            val cases = listOf(
                "right" to halfPoint(1, 1, tileSize, 'R'),
                "left" to halfPoint(1, 1, tileSize, 'L'),
                "bottom" to halfPoint(1, 1, tileSize, 'B'),
                "top" to halfPoint(1, 1, tileSize, 'T'),
            )
            for ((name, point) in cases) {
                val points = listOf(
                    OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON),
                    OracleClient.Point(point.first, point.second, Buttons.LEFT_RELEASE),
                )
                val expected = oracle.pointsSized(
                    save,
                    points,
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )
                val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
                apply(midend, points, tileSize)
                val move = moveOf(midend)
                assertEquals("$name tile $tileSize move", expectedMove(1, 1, name), move)
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
                assertEquals("$name tile $tileSize states", 2, midend.stateCount)
                assertLines(
                    "$name tile $tileSize trace",
                    TraceNormalizer.normalize(expected.trace!!, upstream),
                    TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, tileSize),
                        upstream,
                    ),
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun `a placement and a removal match the oracle at every tile size`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("dominosa", "3dt", SEED).state
            for (tileSize in listOf(48, 63, 82, 100)) {
                val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
                val point = halfPoint(1, 1, tileSize, 'R')
                val press = OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON)
                val release = OracleClient.Point(point.first, point.second, Buttons.LEFT_RELEASE)
                val placed = oracle.pointsSized(
                    save,
                    listOf(press, release),
                    width = board.width,
                    height = board.height,
                )
                val expected = oracle.pointsSized(
                    save,
                    listOf(press, release, press, release),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )

                val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
                val start = (midend.state as Dominosa.State).grid.copyOf()
                val count = midend.stateCount

                apply(midend, listOf(press, release), tileSize)
                val placedState = midend.state as Dominosa.State
                assertEquals("tile $tileSize placement move", "D6,7", moveOf(midend))
                assertFalse(
                    "tile $tileSize placement changes the board",
                    placedState.grid.contentEquals(start),
                )
                assertEquals("tile $tileSize placement count", count + 1, midend.stateCount)
                assertEquals("tile $tileSize placement id", placed.state.id, midend.id())
                assertEquals(
                    "tile $tileSize placement canonical",
                    placed.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("tile $tileSize placement status", placed.state.status, midend.status())

                apply(midend, listOf(press, release), tileSize)
                val removedState = midend.state as Dominosa.State
                assertEquals("tile $tileSize removal move", "D6,7", moveOf(midend))
                assertTrue(
                    "tile $tileSize removal restores the board",
                    removedState.grid.contentEquals(start),
                )
                assertEquals("tile $tileSize removal count", count + 2, midend.stateCount)
                assertEquals("tile $tileSize removal id", expected.state.id, midend.id())
                assertEquals(
                    "tile $tileSize removal canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals("tile $tileSize removal status", expected.state.status, midend.status())
                assertLines(
                    "tile $tileSize removal trace",
                    TraceNormalizer.normalize(expected.trace!!, upstream),
                    TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, tileSize),
                        upstream,
                    ),
                )
            }
        }

    @Test(timeout = 600_000)
    fun `a real edge placement and removal match the oracle at every tile size`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val save = oracle.newGame("dominosa", "3dt", SEED).state
            for (tileSize in listOf(48, 63, 82, 100)) {
                val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
                val point = halfPoint(1, 1, tileSize, 'R')
                val press = OracleClient.Point(point.first, point.second, Buttons.RIGHT_BUTTON)
                val release = OracleClient.Point(point.first, point.second, Buttons.RIGHT_RELEASE)
                val lined = oracle.pointsSized(
                    save,
                    listOf(press, release),
                    width = board.width,
                    height = board.height,
                )
                val expected = oracle.pointsSized(
                    save,
                    listOf(press, release, press, release),
                    width = board.width,
                    height = board.height,
                    recordTrace = true,
                )

                val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
                val state = midend.state as Dominosa.State
                assertEquals("tile $tileSize starts empty", 6, state.grid[6])
                assertEquals(7, state.grid[7])

                apply(midend, listOf(press, release), tileSize)
                assertEquals("tile $tileSize edge move", "E6,7", moveOf(midend))
                assertEquals(
                    "tile $tileSize edge echo",
                    0x200,
                    (midend.state as Dominosa.State).edges[6] and 0x200,
                )
                assertEquals(
                    "tile $tileSize edge echo pair",
                    0x100,
                    (midend.state as Dominosa.State).edges[7] and 0x100,
                )
                assertEquals("tile $tileSize edge id", lined.state.id, midend.id())
                assertEquals(
                    "tile $tileSize edge canonical",
                    lined.state.state,
                    midend.serialise().serialise(),
                )

                apply(midend, listOf(press, release), tileSize)
                assertEquals("tile $tileSize edge removal move", "E6,7", moveOf(midend))
                assertEquals(
                    "tile $tileSize edge removal clears the pair",
                    0,
                    (midend.state as Dominosa.State).edges[6],
                )
                assertEquals(
                    "tile $tileSize edge removal clears the echo",
                    0,
                    (midend.state as Dominosa.State).edges[7],
                )
                assertEquals("tile $tileSize edge removal id", expected.state.id, midend.id())
                assertEquals(
                    "tile $tileSize edge removal canonical",
                    expected.state.state,
                    midend.serialise().serialise(),
                )
                assertLines(
                    "tile $tileSize edge removal trace",
                    TraceNormalizer.normalize(expected.trace!!, upstream),
                    TraceNormalizer.normalize(
                        KotlinTraceSink.stateTrace(midend, tileSize),
                        upstream,
                    ),
                )
            }
        }

    @Test(timeout = 120_000)
    fun `the measured boundary table at 208x176`() = OracleClient().use { oracle ->
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val cases = listOf(
            24 to 24 to "",
            23 to 41 to "",
            41 to 23 to "",
            40 to 40 to "",
            41 to 40 to "D0,1",
            39 to 40 to "",
            40 to 25 to "",
            24 to 41 to "",
            40 to 41 to "D0,5",
            55 to 41 to "D0,1",
            56 to 41 to "D0,1",
        )
        for ((point, move) in cases) {
            val points = listOf(OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON))
            val expected = oracle.pointsSized(
                save,
                points,
                width = 208,
                height = 176,
            )
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, 32)
            assertEquals("point $point", move, moveOf(midend))
            assertEquals("point $point id", expected.state.id, midend.id())
            assertEquals("point $point states", if (move.isEmpty()) 1 else 2, midend.stateCount)
        }
    }

    @Test(timeout = 120_000)
    fun `a right click is an edge only on two empty squares`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("dominosa", "3dt", SEED).state
            val point = halfPoint(0, 0, 32, 'R')
            val points = listOf(
                OracleClient.Point(point.first, point.second, Buttons.RIGHT_BUTTON),
                OracleClient.Point(point.first, point.second, Buttons.RIGHT_RELEASE),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                width = 208,
                height = 176,
            )
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            apply(midend, points, 32)
            assertEquals("E0,1", moveOf(midend))
            assertEquals(expected.state.id, midend.id())
            assertEquals(expected.state.state, midend.serialise().serialise())

            // The same edge twice toggles it off.
            apply(midend, points, 32)
            assertEquals("E0,1", moveOf(midend))
            assertEquals(0, (midend.state as Dominosa.State).edges[0])

            // A left click covers the squares; a right click then does
            // nothing and leaves the state and the move count alone.
            apply(midend, listOf(OracleClient.Point(point.first, point.second, Buttons.LEFT_BUTTON)), 32)
            assertEquals("D0,1", moveOf(midend))
            val count = midend.stateCount
            val id = midend.id()
            val text = Dominosa.textFormat(midend.state)
            val outcome = apply(midend, points, 32)
            assertEquals("a covered square refuses the edge", count, midend.stateCount)
            assertEquals(id, midend.id())
            assertEquals(text, Dominosa.textFormat(midend.state))
            assertEquals(listOf(KeyOutcome.NoEffect, KeyOutcome.Unused), outcome)
        }

    @Test(timeout = 120_000)
    fun `a letterbox and a border click are inert`() = OracleClient().use { oracle ->
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val geom = midend.geometryFor(411, 702)
        assertEquals(63, geom.tileSize)
        assertEquals(1, geom.originX)
        assertEquals(178, geom.originY)

        val count = midend.stateCount
        val id = midend.id()
        val text = Dominosa.textFormat(midend.state)
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(-geom.originX, -geom.originY, Buttons.LEFT_BUTTON, geom.tileSize),
        )
        assertEquals("letterbox state count", count, midend.stateCount)
        assertEquals("letterbox id", id, midend.id())
        assertEquals("letterbox text", text, Dominosa.textFormat(midend.state))

        // One click in the middle of each letterbox band: view x = 0 and
        // 410, view y = 0 and 701, with the origin subtracted.
        for ((side, gx, gy) in listOf(
            Triple("top", 100, geom.toGameY(0)),
            Triple("bottom", 100, geom.toGameY(701)),
            Triple("left", geom.toGameX(0), 100),
            Triple("right", geom.toGameX(410), 100),
        )) {
            assertEquals(
                "$side letterbox",
                KeyOutcome.Unused,
                midend.processKey(gx, gy, Buttons.LEFT_BUTTON, geom.tileSize),
            )
            assertEquals("$side state count", count, midend.stateCount)
            assertEquals("$side id", id, midend.id())
            assertEquals("$side text", text, Dominosa.textFormat(midend.state))
        }

        // The view coordinates of the board origin are a real click.
        val point = halfPoint(0, 0, geom.tileSize, 'R')
        val expected = oracle.pointsSized(
            save,
            listOf(
                OracleClient.Point(point.first + geom.originX, point.second + geom.originY, Buttons.LEFT_BUTTON),
                OracleClient.Point(point.first + geom.originX, point.second + geom.originY, Buttons.LEFT_RELEASE),
            ),
            width = 411,
            height = 702,
        )
        midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, geom.tileSize)
        midend.processKey(point.first, point.second, Buttons.LEFT_RELEASE, geom.tileSize)
        assertEquals("positive control", count + 1, midend.stateCount)
        assertEquals("positive id", expected.state.id, midend.id())
        assertEquals("D0,1", moveOf(midend))

        // A click inside the left border strip at tile 63 is outside.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(1, point.second, Buttons.LEFT_BUTTON, geom.tileSize),
        )
        assertEquals(count + 1, midend.stateCount)
    }

    @Test(timeout = 240_000)
    fun `a drag and a lone release are not a move`() {
        for (tileSize in listOf(32, 48, 63, 82, 100)) {
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            val point = halfPoint(1, 1, tileSize, 'R')
            val other = halfPoint(2, 2, tileSize, 'R')

            // A lone release has no held button.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(other.first, other.second, Buttons.LEFT_RELEASE, tileSize),
            )
            assertEquals(1, midend.stateCount)

            // A press makes the move; the drag and release add nothing.
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, tileSize),
            )
            assertEquals(2, midend.stateCount)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(other.first, other.second, Buttons.LEFT_DRAG, tileSize),
            )
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(other.first, other.second, Buttons.LEFT_RELEASE, tileSize),
            )
            assertEquals(2, midend.stateCount)
            assertEquals("D6,7", moveOf(midend))

            // A middle press is unused.
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(point.first, point.second, Buttons.MIDDLE_BUTTON, tileSize),
            )
            assertEquals(2, midend.stateCount)
        }
    }

    @Test(timeout = 120_000)
    fun `a drag in the letterbox matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val geom = midend.geometryFor(411, 702)
        assertEquals(63, geom.tileSize)
        assertEquals(1, geom.originX)
        assertEquals(178, geom.originY)

        val point = halfPoint(1, 1, geom.tileSize, 'R')
        val other = halfPoint(2, 2, geom.tileSize, 'R')
        val view = listOf(
            OracleClient.Point(
                point.first + geom.originX,
                point.second + geom.originY,
                Buttons.LEFT_BUTTON,
            ),
            OracleClient.Point(
                other.first + geom.originX,
                other.second + geom.originY,
                Buttons.LEFT_DRAG,
            ),
            OracleClient.Point(
                other.first + geom.originX,
                other.second + geom.originY,
                Buttons.LEFT_RELEASE,
            ),
        )
        val expected = oracle.pointsSized(
            save,
            view,
            width = 411,
            height = 702,
        )

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, geom.tileSize),
        )
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(other.first, other.second, Buttons.LEFT_DRAG, geom.tileSize),
        )
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(other.first, other.second, Buttons.LEFT_RELEASE, geom.tileSize),
        )
        assertEquals("D6,7", moveOf(midend))
        assertEquals(2, midend.stateCount)
        assertEquals(expected.state.id, midend.id())
        assertEquals(expected.state.state, midend.serialise().serialise())
    }

    @Test(timeout = 180_000)
    fun `the keyboard cursor and selects match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val right = Buttons.CURSOR_RIGHT
        val down = Buttons.CURSOR_DOWN
        val left = Buttons.CURSOR_LEFT
        val up = Buttons.CURSOR_UP
        val sequences = listOf(
            listOf(right, Buttons.CURSOR_SELECT),
            listOf(right, Buttons.CURSOR_SELECT2),
            listOf(right, down, Buttons.CURSOR_SELECT),
            listOf(right, down, down, Buttons.CURSOR_SELECT),
            listOf(right, down, down, Buttons.CURSOR_SELECT2),
            listOf(down, Buttons.CURSOR_SELECT),
            listOf(down, down, Buttons.CURSOR_SELECT2),
            (0 until 12).map { right },
            (0 until 12).map { down },
            listOf(left, up, Buttons.CURSOR_SELECT),
            listOf(right, right, Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT),
            listOf(right, Buttons.CURSOR_SELECT, right, Buttons.CURSOR_SELECT2),
        )
        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            for (key in sequence) midend.processKey(0, 0, key, 32)
            assertEquals("keys $sequence id", expected.id, midend.id())
            assertEquals(
                "keys $sequence canonical",
                expected.state,
                midend.serialise().serialise(),
            )
            assertEquals("keys $sequence status", expected.status, midend.status())

            val trace = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, 32),
                upstream,
            )
            assertLines("cursor $sequence", trace, actual)
        }

        // A select on even parity is a no-op.
        val even = Midend.fromSave(Dominosa, SgtpSave.parse(save))
        assertEquals(
            KeyOutcome.NoEffect,
            even.processKey(0, 0, Buttons.CURSOR_SELECT, 32),
        )
        assertEquals(1, even.stateCount)
    }

    @Test(timeout = 180_000)
    fun `the digit highlights match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("dominosa", "3dt", SEED).state
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val sequences = listOf(
            listOf('0'.code),
            listOf('0'.code, '1'.code),
            listOf('0'.code, '1'.code, '2'.code),
            listOf('0'.code, '1'.code, '0'.code),
            listOf(Buttons.MOD_NUM_KEYPAD or '2'.code),
            listOf('0'.code, Buttons.MOD_NUM_KEYPAD or '2'.code),
        )
        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Dominosa, SgtpSave.parse(save))
            for (key in sequence) midend.processKey(0, 0, key, 32)
            assertEquals("digits $sequence id", expected.id, midend.id())
            assertLines(
                "digits $sequence",
                TraceNormalizer.normalize(oracle.trace(save, sequence = sequence), upstream),
                TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 32), upstream),
            )
        }

        // A digit above n is unused; a letter, TAB, and a modified arrow
        // are unused too.
        for (key in listOf('4'.code, '9'.code, 'p'.code, '\t'.code)) {
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            assertEquals(
                "key $key",
                KeyOutcome.Unused,
                midend.processKey(0, 0, key, 32),
            )
            assertEquals(1, midend.stateCount)
        }
        val modified = Midend.newGame(Dominosa, "3dt", SEED)
        assertEquals(
            KeyOutcome.Unused,
            modified.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT, 32),
        )
        assertEquals(
            KeyOutcome.Unused,
            modified.processKey(0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, 32),
        )
        assertEquals(1, modified.stateCount)

        // A third highlight with both slots taken is a no-op.
        val third = Midend.newGame(Dominosa, "3dt", SEED)
        third.processKey(0, 0, '0'.code, 32)
        third.processKey(0, 0, '1'.code, 32)
        assertEquals(KeyOutcome.NoEffect, third.processKey(0, 0, '2'.code, 32))
        // Pressing one of the two clears it.
        assertEquals(KeyOutcome.SomeEffect, third.processKey(0, 0, '0'.code, 32))
    }

    @Test(timeout = 120_000)
    fun `undo with no history and redo with no branch do nothing`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        assertFalse(midend.canUndo)
        assertFalse(midend.canRedo)
        assertEquals(KeyOutcome.NoEffect, midend.processKey(0, 0, 'u'.code, 32))
        assertEquals(KeyOutcome.NoEffect, midend.processKey(0, 0, 'r'.code, 32))
        assertEquals(1, midend.stateCount)

        val point = halfPoint(1, 1, 32, 'R')
        midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, 32)
        assertEquals(2, midend.stateCount)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'u'.code, 32))
        assertEquals(1, midend.statePos)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'r'.code, 32))
        assertEquals(2, midend.statePos)

        // A restart after a move.
        assertTrue(midend.restart())
        assertEquals(0, midend.status())
        assertEquals(3, midend.stateCount)

        // A solve on an open board, then another solve on the closed one.
        val solved = Midend.newGame(Dominosa, "3dt", SEED)
        assertTrue(solved.solve())
        assertEquals(1, solved.status())
        assertTrue(solved.solve())
        assertEquals(1, solved.status())
    }

    @Test(timeout = 180_000)
    fun `a real click completion matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val state = midend.state as Dominosa.State
        val aux = midend.auxInfo!!

        val points = ArrayList<OracleClient.Point>()
        val w = state.w
        for (i in 0 until state.wh) {
            val x = i % w
            val y = i / w
            when (aux[i]) {
                'L' -> halfPoint(x, y, 32, 'R').let { points += OracleClient.Point(it.first, it.second, Buttons.LEFT_BUTTON) }
                'T' -> halfPoint(x, y, 32, 'B').let { points += OracleClient.Point(it.first, it.second, Buttons.LEFT_BUTTON) }
            }
        }
        assertEquals(10, points.size)

        val save = midend.serialise().serialise()
        val expected = oracle.pointsSized(
            save,
            points,
            width = 208,
            height = 176,
            recordTrace = true,
        )
        for (point in points) {
            midend.processKey(point.x, point.y, point.button, 32)
        }
        assertEquals("the board completes", 1, midend.status())
        assertEquals(11, midend.stateCount)
        assertEquals(expected.state.id, midend.id())
        assertEquals(expected.state.state, midend.serialise().serialise())
        assertEquals(1, expected.state.status)
        assertEquals(
            expected.state.state,
            midend.serialise().serialise(),
        )
        assertLines(
            "completion trace",
            TraceNormalizer.normalize(expected.trace!!, upstream),
            TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend, 32), upstream),
        )

        // The same clicks from scratch without a release still complete:
        // a press makes the move and a release is unused.
        val noRelease = Midend.newGame(Dominosa, "3dt", SEED)
        for (point in points) noRelease.processKey(point.x, point.y, point.button, 32)
        assertEquals(1, noRelease.status())
        assertEquals(11, noRelease.stateCount)
    }

    @Test(timeout = 120_000)
    fun `a real placement and removal change the board and the move count`() {
        val midend = Midend.newGame(Dominosa, "3dt", SEED)
        val point = halfPoint(0, 0, 32, 'R')
        val before = (midend.state as Dominosa.State).grid.copyOf()
        val count = midend.stateCount

        midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, 32)
        midend.processKey(point.first, point.second, Buttons.LEFT_RELEASE, 32)
        val placed = midend.state as Dominosa.State
        assertEquals(1, placed.grid[0])
        assertEquals(0, placed.grid[1])
        assertFalse(placed.grid.contentEquals(before))
        assertEquals(count + 1, midend.stateCount)

        midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, 32)
        midend.processKey(point.first, point.second, Buttons.LEFT_RELEASE, 32)
        val removed = midend.state as Dominosa.State
        assertEquals(0, removed.grid[0])
        assertEquals(1, removed.grid[1])
        assertTrue(removed.grid.contentEquals(before))
        assertEquals(count + 2, midend.stateCount)

        // An inert click changes neither the board nor the move count.
        val id = midend.id()
        val text = Dominosa.textFormat(midend.state)
        val after = midend.stateCount
        assertEquals(KeyOutcome.NoEffect, midend.processKey(24, 24, Buttons.LEFT_BUTTON, 32))
        assertEquals(after, midend.stateCount)
        assertEquals(id, midend.id())
        assertEquals(text, Dominosa.textFormat(midend.state))
        assertEquals(KeyOutcome.NoEffect, midend.processKey(40, 40, Buttons.LEFT_BUTTON, 32))
        assertEquals(after, midend.stateCount)
        assertEquals(id, midend.id())
    }

    @Test(timeout = 120_000)
    fun `a click outside the board is unused at every tile size`() {
        for (tileSize in listOf(48, 63, 82, 100)) {
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(-1, -1, Buttons.LEFT_BUTTON, tileSize),
            )
            assertEquals(
                KeyOutcome.Unused,
                midend.processKey(board.width, board.height, Buttons.LEFT_BUTTON, tileSize),
            )
            assertEquals(1, midend.stateCount)
        }
    }

    @Test(timeout = 120_000)
    fun `the last cell is a move and one pixel past it is outside`() {
        for (tileSize in listOf(48, 63, 82, 100)) {
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            val board = Dominosa.computeSize(Dominosa.decodeParams("3dt"), tileSize)
            val point = halfPoint(4, 3, tileSize, 'L')

            // The bottom-right cell with its left half: a real click.
            assertEquals(
                "tile $tileSize last cell",
                KeyOutcome.SomeEffect,
                midend.processKey(point.first, point.second, Buttons.LEFT_BUTTON, tileSize),
            )
            assertEquals("tile $tileSize last cell move", "D18,19", moveOf(midend))
            assertEquals("tile $tileSize last cell count", 2, midend.stateCount)

            // One pixel past the last cell on each axis is outside.
            assertEquals(
                "tile $tileSize past x",
                KeyOutcome.Unused,
                midend.processKey(board.width, point.second, Buttons.LEFT_BUTTON, tileSize),
            )
            assertEquals(
                "tile $tileSize past y",
                KeyOutcome.Unused,
                midend.processKey(point.first, board.height, Buttons.LEFT_BUTTON, tileSize),
            )
            assertEquals("tile $tileSize state count", 2, midend.stateCount)
        }
    }

    /** One click on a half of cell (x, y): L, R, T, or B. */
    private fun halfPoint(x: Int, y: Int, tileSize: Int, part: Char): Pair<Int, Int> {
        val border = tileSize * 3 / 4
        val cx = border + x * tileSize + tileSize / 2
        val cy = border + y * tileSize + tileSize / 2
        val quarter = tileSize / 4
        return when (part) {
            'R' -> Pair(cx + quarter, cy)
            'L' -> Pair(cx - quarter, cy)
            'B' -> Pair(cx, cy + quarter)
            else -> Pair(cx, cy - quarter)
        }
    }

    private fun expectedMove(x: Int, y: Int, part: String): String {
        val w = 5
        val t = y * w + x
        return when (part) {
            "right" -> "D$t,${t + 1}"
            "left" -> "D${t - 1},$t"
            "bottom" -> "D$t,${t + w}"
            else -> "D${t - w},$t"
        }
    }

    private fun apply(
        midend: Midend,
        points: List<OracleClient.Point>,
        tileSize: Int,
    ): List<KeyOutcome> {
        val outcomes = ArrayList<KeyOutcome>()
        for (point in points) {
            outcomes += midend.processKey(point.x, point.y, point.button, tileSize)
        }
        return outcomes
    }

    private fun moveOf(midend: Midend): String =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE").lastOrNull() ?: ""

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
