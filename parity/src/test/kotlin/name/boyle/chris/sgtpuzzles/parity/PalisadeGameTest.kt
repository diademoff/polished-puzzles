package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * Level: semantic. Game: palisade.
 *
 * New game, text, status, real clicks, both cursor modes, the HJKL
 * aliases, the move grammar quirks, undo and redo, completion, solve,
 * and the layout geometry, all against the oracle.
 */
class PalisadeGameTest {

    private val presets = listOf("5x5n5", "8x6n6", "10x8n8", "15x12n10")

    @Test
    fun `new game, status, and text match the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val expected = oracle.newGame("palisade", params, SEED)
            val actual = Midend.newGame(Palisade, params, SEED)
            assertEquals("$params id", expected.id, actual.id())
            assertEquals("$params status", expected.status, actual.status())
            assertEquals("$params canundo", expected.canUndo, actual.canUndo)
            assertEquals("$params canredo", expected.canRedo, actual.canRedo)
            assertEquals("$params state", expected.state, actual.serialise().serialise())
            assertEquals("$params text", oracle.text(expected.state), actual.textFormat())
            val p = Palisade.decodeParams(params) as Palisade.Params
            assertEquals("Region size: ${p.k}", actual.statusText())
        }
    }

    @Test
    fun `solve matches the oracle with the aux`() = OracleClient().use { oracle ->
        for (params in presets) {
            val start = oracle.newGame("palisade", params, SEED)
            val expected = oracle.solve(start.state)
            val actual = Midend.newGame(Palisade, params, SEED)
            assertTrue("$params solve", actual.solve())
            assertEquals("$params id", expected.id, actual.id())
            assertEquals("$params status", 1, actual.status())
            assertEquals("$params state", expected.state, actual.serialise().serialise())
            assertEquals("$params text", oracle.text(expected.state), actual.textFormat())
        }
    }

    @Test
    fun `solve from the rim matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            val start = oracle.newGame("palisade", params, SEED)
            val withoutAux = withoutRecord(start.state, "AUXINFO")
            val expected = oracle.solve(withoutAux)
            val actual = Midend.fromSave(Palisade, SgtpSave.parse(withoutAux))
            assertNull("$params has no aux", actual.auxInfo)
            assertTrue("$params solve", actual.solve())
            assertEquals("$params id", expected.id, actual.id())
            assertEquals("$params state", expected.state, actual.serialise().serialise())
            assertEquals("$params text", oracle.text(expected.state), actual.textFormat())
        }
    }

    @Test
    fun `real clicks match the oracle at the preferred tile`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED)
        val clicks = listOf(
            OracleClient.Point(121, 77, Buttons.LEFT_BUTTON),
            OracleClient.Point(121, 77, Buttons.LEFT_RELEASE),
            OracleClient.Point(125, 73, Buttons.RIGHT_BUTTON),
            OracleClient.Point(125, 73, Buttons.RIGHT_RELEASE),
            OracleClient.Point(160, 112, Buttons.LEFT_BUTTON),
            OracleClient.Point(160, 112, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(start.state, clicks, 292, 292)
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        for (point in clicks) {
            actual.processKey(point.x, point.y, point.button, 48)
        }
        assertEquals("id", expected.state.id, actual.id())
        assertEquals("state", expected.state.state, actual.serialise().serialise())
        assertEquals("canundo", expected.state.canUndo, actual.canUndo)
        assertEquals(
            listOf("F2,1,8F1,1,2", "F2,1,16F2,0,64", "F2,1,4F2,2,1"),
            actual.serialise().values("MOVE"),
        )
        assertEquals(
            oracle.text(expected.state.state),
            actual.textFormat(),
        )
    }

    @Test
    fun `inert clicks add no move`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED)
        val clicks = listOf(
            // The letterbox at the board origin, the far corner, a
            // margin point, a middle click, a lone drag, a lone release.
            OracleClient.Point(0, 0, Buttons.LEFT_BUTTON),
            OracleClient.Point(291, 291, Buttons.LEFT_BUTTON),
            OracleClient.Point(5, 5, Buttons.LEFT_BUTTON),
            OracleClient.Point(150, 150, Buttons.MIDDLE_BUTTON),
            OracleClient.Point(100, 100, Buttons.LEFT_DRAG),
            OracleClient.Point(100, 100, Buttons.LEFT_RELEASE),
        )
        val expected = oracle.pointsSized(start.state, clicks, 292, 292)
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        for (point in clicks) {
            assertTrue(
                "unused at ${point.x},${point.y}",
                actual.processKey(point.x, point.y, point.button, 48) != KeyOutcome.SomeEffect,
            )
        }
        assertEquals("id", expected.state.id, actual.id())
        assertEquals("state", expected.state.state, actual.serialise().serialise())
        assertFalse("canundo", actual.canUndo)
        assertEquals(0, actual.stateCount - 1)
    }

    @Test
    fun `a real fence changes the board and an inert click changes nothing`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("palisade", "5x5n5", SEED).state

            val played = Midend.fromSave(Palisade, SgtpSave.parse(save))
            val before = (played.state as Palisade.State).borders.copyOf()
            assertEquals(
                KeyOutcome.SomeEffect,
                played.processKey(24 + 40, 24 + 24, Buttons.LEFT_BUTTON, 48),
            )
            played.processKey(24 + 40, 24 + 24, Buttons.LEFT_RELEASE, 48)
            assertEquals("one move", 1, played.stateCount - 1)
            val after = (played.state as Palisade.State).borders
            assertFalse("the borders changed", before.contentEquals(after))
            // The fence is ON on both sides of the edge.
            assertEquals(2, after[0].toInt() and 2)
            assertEquals(8, after[1].toInt() and 8)

            val inert = Midend.fromSave(Palisade, SgtpSave.parse(save))
            val inertBefore = (inert.state as Palisade.State).borders.copyOf()
            assertEquals(
                KeyOutcome.Unused,
                inert.processKey(0, 0, Buttons.LEFT_BUTTON, 48),
            )
            assertEquals(
                KeyOutcome.Unused,
                inert.processKey(0, 0, Buttons.LEFT_RELEASE, 48),
            )
            assertEquals("no move", 0, inert.stateCount - 1)
            assertTrue(
                "the borders did not change",
                inertBefore.contentEquals((inert.state as Palisade.State).borders),
            )
        }

    @Test
    fun `the half-grid cursor sequences match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "2x3n3", "d33")
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        val expected = oracle.keys(start.state, sequence)
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        for (key in sequence) actual.processKey(0, 0, key, 48)
        assertEquals("id", expected.id, actual.id())
        assertEquals("status", expected.status, actual.status())
        assertEquals("state", expected.state, actual.serialise().serialise())
        assertEquals(
            oracle.text(expected.state),
            actual.textFormat(),
        )

        // The letter keys are plain cursor moves in half-grid mode.
        for (keys in listOf(
            listOf('h'.code, 'H'.code, 'j'.code, 'J'.code, 'k'.code, 'K'.code, 'l'.code, 'L'.code),
            listOf('l'.code, Buttons.CURSOR_LEFT, Buttons.CURSOR_SELECT),
            listOf('l'.code, Buttons.CURSOR_LEFT, 'k'.code, 'k'.code, Buttons.CURSOR_SELECT),
        )) {
            val expectedKeys = oracle.keys(start.state, keys)
            val actualKeys = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
            for (key in keys) actualKeys.processKey(0, 0, key, 48)
            assertEquals("id for $keys", expectedKeys.id, actualKeys.id())
            assertEquals("state for $keys", expectedKeys.state, actualKeys.serialise().serialise())
        }

        // A modified arrow is still a cursor move in half-grid mode.
        val modified = listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT)
        val expectedModified = oracle.keys(start.state, modified)
        val actualModified = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        for (key in modified) actualModified.processKey(0, 0, key, 48)
        assertEquals(
            "modified arrow",
            expectedModified.state,
            actualModified.serialise().serialise(),
        )
    }

    @Test
    fun `the full-grid cursor sequence produces oracle-valid moves`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "2x3n3", "d33")
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        (actual.ui as Palisade.Ui).legacyCursor = true
        val sequence = listOf(
            'l'.code,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            'l'.code,
            Buttons.CURSOR_UP,
            'l'.code,
        )
        for (key in sequence) actual.processKey(0, 0, key, 48)
        assertEquals(1, actual.status())
        assertEquals(
            listOf("F0,0,2F1,0,8", "F0,2,2F1,2,8", "F0,1,2F1,1,8"),
            actual.serialise().values("MOVE"),
        )
        // The oracle accepts the produced save and sees a completed board.
        val loaded = oracle.id(actual.serialise().serialise())
        assertEquals("id", actual.id(), loaded.id)
        assertEquals("status", 1, loaded.status)
        assertEquals("text", oracle.text(loaded.state), actual.textFormat())
    }

    @Test
    fun `control and shift toggle an edge only in full-grid mode`() = OracleClient().use { oracle ->
        // In half-grid mode a modified arrow is a plain move.
        val start = oracle.newGame("palisade", "2x3n3", "d33")
        val half = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        half.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, 48)
        half.processKey(0, 0, Buttons.CURSOR_LEFT, 48)
        half.processKey(0, 0, Buttons.MOD_SHFT or Buttons.CURSOR_RIGHT, 48)
        assertEquals("half-grid modified arrows add no move", false, half.canUndo)

        // In full-grid mode control acts like the left button. The pair
        // of moves is the same as a half-grid select.
        val full = Midend.fromSave(Palisade, SgtpSave.parse(start.state))
        (full.ui as Palisade.Ui).legacyCursor = true
        full.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, 48)
        assertEquals(
            listOf("F0,0,2F1,0,8"),
            full.serialise().values("MOVE"),
        )
        assertTrue((full.ui as Palisade.Ui).legacyCursor)
    }

    @Test
    fun `the move grammar quirks load in both engines`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        val valid = listOf(
            "",
            "F0,0,2",
            "F1,0,8",
            "F0,0,2F1,0,8",
            "F+0,+0,+2",
            "F 1, 2, 3",
            "F0,0,16",
            "F0,0,512",
            "F0,0,34",
            "F0,0,2F1,0,8F0,0,2F1,0,8",
            "S" + "A".repeat(25),
        )
        for (move in valid) {
            val save = withMoves(start, move)
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Palisade, SgtpSave.parse(save))
            assertEquals("'$move' id", expected.id, actual.id())
            assertEquals("'$move' status", expected.status, actual.status())
            assertEquals("'$move' text", oracle.text(expected.state), actual.textFormat())
        }

        val invalid = listOf(
            "F0,0,8",
            "F0,0,1",
            "F5,0,2",
            " F0,0,2",
            "F0,0,2F1,0,8x",
            "F0,0",
            "S" + "A".repeat(24),
            "S" + "A".repeat(26),
        )
        for (move in invalid) {
            val save = withMoves(start, move)
            val rejected = try {
                oracle.id(save)
                false
            } catch (e: OracleException) {
                true
            }
            assertTrue("oracle rejects '$move'", rejected)
            val thrown = try {
                Midend.fromSave(Palisade, SgtpSave.parse(save))
                false
            } catch (e: RuntimeException) {
                true
            }
            assertTrue("engine rejects '$move'", thrown)
        }
    }

    @Test
    fun `one-sided, both-bits, and S moves keep the C state`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        // A one-sided right border separates the cells; a one-sided left
        // border does not. Both bits on one edge are accepted.
        val oneSided = withMoves(start, "F0,0,2")
        val bothSides = withMoves(start, "F0,0,34F1,0,136")
        for (save in listOf(oneSided, bothSides)) {
            val expected = oracle.id(save)
            val actual = Midend.fromSave(Palisade, SgtpSave.parse(save))
            assertEquals(expected.id, actual.id())
            assertEquals(expected.status, actual.status())
            assertEquals(oracle.text(expected.state), actual.textFormat())
            val state = actual.state as Palisade.State
            assertEquals(2, state.borderAt(0, 0) and 2)
        }
        // The one-sided left border is invisible to the text.
        val rightOnly = Midend.fromSave(Palisade, SgtpSave.parse(withMoves(start, "F1,0,8")))
        assertEquals(
            oracle.text(withMoves(start, "F1,0,8")),
            rightOnly.textFormat(),
        )
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "2x3n3", "d33")
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        val played = oracle.keys(start.state, sequence)
        val expectedUndo = oracle.undo(played.state)
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(played.state))
        assertEquals("state", played.state, actual.serialise().serialise())
        assertEquals("undo returns", true, actual.undo())
        assertEquals("undo id", expectedUndo.id, actual.id())
        assertEquals("undo state", expectedUndo.state, actual.serialise().serialise())
        assertEquals("undo canundo", expectedUndo.canUndo, actual.canUndo)
        assertEquals("undo canredo", expectedUndo.canRedo, actual.canRedo)
        val expectedRedo = oracle.redo(expectedUndo.state)
        assertEquals("redo returns", true, actual.redo())
        assertEquals("redo state", expectedRedo.state, actual.serialise().serialise())
    }

    @Test
    fun `an honest completion and the completion event`() = OracleClient().use { oracle ->
        val generated = oracle.newGame("palisade", "5x5n5", SEED)
        val aux = decodeSolve(oracle.solve(generated.state).state)
        val honest = honestMove(aux, 5, 5)
        val loaded = oracle.id(withMoves(generated.state, honest))
        assertEquals("oracle status", 1, loaded.status)

        val actual = Midend.newGame(Palisade, "5x5n5", SEED)
        val state = actual.state as Palisade.State
        val completed = Palisade.executeMove(state, honest) as Palisade.State
        assertTrue(completed.completed)
        assertFalse(completed.cheated)
        assertEquals("text", oracle.text(loaded.state), Palisade.textFormat(completed))
        assertTrue(Palisade.changedState(actual.ui, state, completed))

        // A solve is cheated and raises no event.
        val solved = Palisade.executeMove(
            state,
            decodeSolve(oracle.solve(generated.state).state),
        ) as Palisade.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertFalse(Palisade.changedState(actual.ui, state, solved))
        assertEquals("S text", oracle.text(oracle.solve(generated.state).state), Palisade.textFormat(solved))
    }

    @Test
    fun `undo and redo raise the completion event again`() = OracleClient().use { oracle ->
        val midend = Midend.fromGameId(Palisade, "2x3n3:d33")
        for (key in listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )) {
            midend.processKey(0, 0, key, 48)
        }
        assertTrue(midend.takeCompletedEvent())
        assertTrue(midend.undo())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.redo())
        assertTrue(midend.takeCompletedEvent())
        // The oracle agrees about the status on both sides.
        val expected = oracle.undo(midend.serialise().serialise())
        assertEquals(0, expected.status)
    }

    @Test
    fun `request keys and the layouts match the oracle`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED)
        val params = Palisade.decodeParams("5x5n5")

        val half = oracle.requestKeys(start.state)
        assertEquals(3, half.arrowMode)
        assertTrue(half.labels.isEmpty())
        for (request in listOf(
            Palisade.requestKeys(params),
            Palisade.requestKeys(params, fullCursor = false),
        )) {
            assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, request.arrowMode)
            assertTrue(request.labels.isEmpty())
        }

        // The full-grid mode is the Android hint, not a parameter. The
        // game returns four arrow-only labels in C's order.
        val full = oracle.requestKeys(start.state, fullCursor = true)
        assertEquals(1, full.arrowMode)
        assertEquals(listOf("Left", "Bottom", "Top", "Right"), full.labels.map { it.label })
        assertEquals(listOf('H'.code, 'J'.code, 'K'.code, 'L'.code), full.labels.map { it.button })
        assertTrue(full.labels.all { it.needsArrows })
        val fullRequest = Palisade.requestKeys(params, fullCursor = true)
        assertEquals(ArrowMode.ARROWS_ONLY, fullRequest.arrowMode)
        assertEquals(full.labels.map { it.label }, fullRequest.labels.map { it.name })
        assertEquals(full.labels.map { it.button }, fullRequest.labels.map { it.button })
        assertEquals(full.labels.map { it.needsArrows }, fullRequest.labels.map { it.needsArrows })

        val views = listOf(
            411 to 702,
            720 to 1280,
            1024 to 600,
            200 to 200,
            292 to 292,
            1000 to 1000,
        )
        for (params in presets) {
            val state = oracle.newGame("palisade", params, SEED).state
            val midend = Midend.newGame(Palisade, params, SEED)
            for ((width, height) in views) {
                val layout = oracle.layout(state, width, height)
                val geometry = midend.geometryFor(width, height)
                assertEquals("$params ${width}x$height tile", layout.width, geometry.width)
                assertEquals("$params ${width}x$height height", layout.height, geometry.height)
            }
        }

        // The exact tile-size geometry from the specification.
        val midend = Midend.newGame(Palisade, "5x5n5", SEED)
        val geometry = midend.geometryFor(411, 702)
        assertEquals(67, geometry.tileSize)
        assertEquals(2, geometry.originX)
        assertEquals(147, geometry.originY)
        assertEquals(407, geometry.width)
        assertEquals(407, geometry.height)
        val eight = Midend.newGame(Palisade, "8x6n6", SEED).geometryFor(411, 702)
        assertEquals(45, eight.tileSize)
        assertEquals(1, eight.originX)
        assertEquals(192, eight.originY)
        val ten = Midend.newGame(Palisade, "10x8n8", SEED).geometryFor(411, 702)
        assertEquals(37, ten.tileSize)
        assertEquals(1, ten.originX)
        assertEquals(183, ten.originY)
        val fifteen = Midend.newGame(Palisade, "15x12n10", SEED).geometryFor(411, 702)
        assertEquals(25, fifteen.tileSize)
        assertEquals(5, fifteen.originX)
        assertEquals(188, fifteen.originY)
    }

    @Test
    fun `the palette is the six-entry C palette`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED)
        val expected = paletteOf(oracle.trace(start.state))
        val colours = Palisade.colours()
        val actual = (0 until colours.size / 3).joinToString(" ") { index ->
            (0 until 3).joinToString(",") { channel ->
                (colours[index * 3 + channel] * 1000.0f)
                    .roundToLong().toString()
            }
        }
        assertEquals("800,800,800 633,633,633 0,0,0 720,720,0 720,720,720 1000,0,0", expected)
        assertEquals(expected, actual)
    }

    private fun paletteOf(trace: JsonValue.Obj): String {
        val palette = (trace.field("palette") as JsonValue.Arr).items
        return palette.joinToString(" ") { entry ->
            (entry as JsonValue.Arr).items.joinToString(",") {
                (it as JsonValue.Num).value.toString()
            }
        }
    }

    /** Replaces the `SOLVE` text with the decoded solution. */
    private fun decodeSolve(save: String): String =
        requireNotNull(SgtpSave.parse(save).value("SOLVE"))

    /** Builds one interior wall per ON edge, as a single move. */
    private fun honestMove(aux: String, w: Int, h: Int): String {
        val bits = aux.substring(1)
        val out = StringBuilder()
        for (i in bits.indices) {
            val border = bits[i].code and 15
            val x = i % w
            val y = i / w
            for (dir in listOf(1, 2)) {
                if (border and (1 shl dir) == 0) continue
                val nx = x + (if (dir == 1) 1 else 0)
                val ny = y + (if (dir == 2) 1 else 0)
                if (nx >= w || ny >= h) continue
                out.append("F$x,$y,${1 shl dir}F$nx,$ny,${1 shl (dir xor 2)}")
            }
        }
        return out.toString()
    }

    /** Appends MOVE records to a save and moves STATEPOS to the end. */
    private fun withMoves(save: String, vararg moves: String): String {
        val parsed = SgtpSave.parse(save)
        val records = ArrayList<SaveRecord>()
        for (record in parsed.records) {
            if (record.key == "NSTATES" || record.key == "STATEPOS") continue
            if (record.key == "MOVE" || record.key == "SOLVE" || record.key == "RESTART") continue
            records += record
        }
        records += SaveRecord("NSTATES", (1 + moves.size).toString())
        records += SaveRecord("STATEPOS", (1 + moves.size).toString())
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    /** Drops one optional record, for the no-aux solve path. */
    private fun withoutRecord(save: String, key: String): String {
        val records = SgtpSave.parse(save).records.filter { it.key != key }
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
