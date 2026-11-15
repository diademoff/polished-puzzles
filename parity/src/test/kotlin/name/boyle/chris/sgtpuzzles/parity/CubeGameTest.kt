package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.Size
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: cube.
 *
 * New games, the status, the move grammar, cursor and keypad rotations,
 * the cube-only `UP` sign negation, the 13-move win, undo and redo
 * around completion, the completion event, the palette, the sizes, and
 * the cursor rectangle.
 *
 * A C redraw immediately after a move is the animation frame, so this
 * class compares saves and states, not traces. The strict drawing is in
 * `CubeTraceTest`.
 */
class CubeGameTest {

    @Test
    fun `new games, status, and text match the oracle`() =
        OracleClient().use { oracle ->
            for (params in Cube.presets().map { it.params }) {
                for (seed in listOf("1", "12345", "seed")) {
                    val expected = oracle.newGame("cube", params, seed)
                    val midend = Midend.newGame(Cube, params, seed)
                    assertEquals("'$params' seed '$seed' id", expected.id, midend.id())
                    assertEquals("'$params' seed '$seed' status", expected.status, midend.status())
                    assertFalse("'$params' seed '$seed' undo", midend.canUndo)
                    assertFalse("'$params' seed '$seed' redo", midend.canRedo)
                    assertEquals(
                        "'$params' seed '$seed' save",
                        expected.state,
                        midend.serialise().serialise(),
                    )
                    assertEquals(
                        "'$params' seed '$seed' text",
                        "",
                        oracle.text(expected.state),
                    )
                    assertEquals("'$params' seed '$seed' Kotlin text", "", midend.textFormat())
                    assertEquals(
                        "'$params' seed '$seed' status text",
                        statusOf(oracle.trace(expected.state)),
                        midend.statusText(),
                    )
                }
            }

            assertFalse("cube has no text form", Cube.canFormatAsText)
            assertFalse("cube has no solver", Cube.canSolve)
            assertTrue("cube wants a status bar", Cube.wantsStatusBar)
            assertEquals(48, Cube.preferredTileSize)
            assertEquals("Cube", Cube.name)
            assertEquals("cube", Cube.backendName)
            assertNull("solve returns null", Cube.solve(Midend.newGame(Cube, "", SEED).state))
        }

    @Test
    fun `the move grammar follows the first character`() = OracleClient().use { oracle ->
        // Every move with a destination is replayed and compared.
        val accepted = listOf("R", "U", "D", "Rx", "UU", "Ux", "Rjunk")
        for (move in accepted) {
            val save = saveText("c4x4", DESC, listOf(move))
            val expected = oracle.id(save)
            val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
            assertEquals("id for '$move'", expected.id, midend.id())
            assertEquals("status for '$move'", expected.status, midend.status())
            assertEquals(
                "save for '$move'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        // A lowercase letter, an unknown letter, an empty move string,
        // and a direction with no destination are rejected.
        val rejected = listOf("L", "l", "u", "X", "")
        for (move in rejected) {
            val save = saveText("c4x4", DESC, listOf(move))
            assertEquals(
                "C message for '$move'",
                "Save file contained an invalid move",
                try {
                    oracle.id(save)
                    null
                } catch (e: OracleException) {
                    e.message
                },
            )
            assertNull("Kotlin rejects '$move'", moveResult("c4x4", DESC, move))
        }

        // Trailing characters are ignored: `Ux` is `U`, `UU` is `U`, and
        // `Rx` is `R`.
        assertEquals(moveResult("c4x4", DESC, "U"), moveResult("c4x4", DESC, "Ux"))
        assertEquals(moveResult("c4x4", DESC, "U"), moveResult("c4x4", DESC, "UU"))
        assertEquals(moveResult("c4x4", DESC, "R"), moveResult("c4x4", DESC, "Rx"))

        // DOWN has no destination on an up-pointing triangle.
        val tetra = Midend.newGame(Cube, "t1x2", SEED)
        assertEquals(
            "oracle rejects DOWN on t1x2",
            "Save file contained an invalid move",
            try {
                oracle.id(saveText("t1x2", tetra.desc, listOf("D")))
                null
            } catch (e: OracleException) {
                e.message
            },
        )
        assertNull("Kotlin rejects DOWN on t1x2", moveResult("t1x2", tetra.desc, "D"))
    }

    @Test
    fun `cursor and keypad rotations match the oracle on every preset`() =
        OracleClient().use { oracle ->
            val cursor = mapOf(
                'U' to Buttons.CURSOR_UP,
                'D' to Buttons.CURSOR_DOWN,
                'L' to Buttons.CURSOR_LEFT,
                'R' to Buttons.CURSOR_RIGHT,
            )
            val sequences = listOf(
                "U", "D", "L", "R", "UR", "URD", "URDL", "RRDD",
            )
            for (params in Cube.presets().map { it.params }) {
                val start = oracle.newGame("cube", params, SEED).state
                for (text in sequences) {
                    val keys = text.map { cursor.getValue(it) }
                    val expected = oracle.keys(start, keys)
                    val midend = Midend.newGame(Cube, params, SEED)
                    for (key in keys) {
                        midend.processKey(0, 0, key, Cube.preferredTileSize)
                    }
                    assertEquals("'$params' cursor '$text' id", expected.id, midend.id())
                    assertEquals("'$params' cursor '$text' status", expected.status, midend.status())
                    assertEquals(
                        "'$params' cursor '$text' save",
                        expected.state,
                        midend.serialise().serialise(),
                    )
                }

                // The keypad digits, including the diagonals. A plain
                // digit is not a move.
                for (digit in '1'..'9') {
                    val key = Buttons.MOD_NUM_KEYPAD or digit.code
                    val expected = oracle.keys(start, listOf(key))
                    val single = Midend.newGame(Cube, params, SEED)
                    single.processKey(0, 0, key, Cube.preferredTileSize)
                    assertEquals(
                        "'$params' keypad $digit save",
                        expected.state,
                        single.serialise().serialise(),
                    )
                    assertEquals("'$params' keypad $digit status", expected.status, single.status())

                    val plain = Midend.newGame(Cube, params, SEED)
                    assertEquals(
                        "'$params' plain digit $digit is unused",
                        KeyOutcome.Unused,
                        plain.processKey(0, 0, digit.code, Cube.preferredTileSize),
                    )
                    assertEquals("'$params' plain digit $digit adds no state", 1, plain.stateCount)
                }
            }
        }

    @Test
    fun `the cube UP sign negation is pinned by the oracle`() =
        OracleClient().use { oracle ->
            // DD lands on the blue square (0,3), so the bottom face
            // becomes blue. From (0,2), UP and DOWN are both open and
            // roll that blue face differently. The cube-only negation of
            // the angle for UP is the only rule that can choose between
            // the two alignments.
            val prefix = listOf("D", "D", "U")
            val beforeSave = oracle.keys(
                oracle.newGame("cube", "c4x4", SEED).state,
                prefix.map { cursorKey(it.first()) },
            ).state
            val before = Midend.fromSave(Cube, SgtpSave.parse(beforeSave))
            val beforeState = before.state as Cube.State
            assertTrue(
                "the bottom face is blue before the UP/DOWN choice",
                beforeState.facecolours.any { it != 0 },
            )

            val upMidend = Midend.fromSave(Cube, SgtpSave.parse(beforeSave))
            assertEquals(
                KeyOutcome.SomeEffect,
                upMidend.processKey(0, 0, Buttons.CURSOR_UP, Cube.preferredTileSize),
            )
            val downMidend = Midend.fromSave(Cube, SgtpSave.parse(beforeSave))
            assertEquals(
                KeyOutcome.SomeEffect,
                downMidend.processKey(0, 0, Buttons.CURSOR_DOWN, Cube.preferredTileSize),
            )
            val upState = upMidend.state as Cube.State
            val downState = downMidend.state as Cube.State

            assertNotEquals(
                "UP and DOWN produce different face permutations",
                upState.facecolours.toList(),
                downState.facecolours.toList(),
            )

            val upSave = saveText(
                "c4x4",
                DESC,
                listOf("D", "D", "U", "U"),
            )
            val downSave = saveText(
                "c4x4",
                DESC,
                listOf("D", "D", "U", "D"),
            )
            val upExpected = oracle.keys(beforeSave, listOf(Buttons.CURSOR_UP))
            val downExpected = oracle.keys(beforeSave, listOf(Buttons.CURSOR_DOWN))
            assertEquals("UP save", upExpected.state, upMidend.serialise().serialise())
            assertEquals("DOWN save", downExpected.state, downMidend.serialise().serialise())
            assertEquals("UP status", upExpected.status, upMidend.status())
            assertEquals("DOWN status", downExpected.status, downMidend.status())

            // The face fills of the settled traces pin the exact colours.
            assertTrue(
                "the oracle traces differ",
                polyFills(oracle.trace(upSave)) != polyFills(oracle.trace(downSave)),
            )
            assertEquals(
                "UP face fills",
                polyFills(oracle.trace(upSave)),
                polyFills(KotlinTraceSink.stateTrace(upMidend)),
            )
            assertEquals(
                "DOWN face fills",
                polyFills(oracle.trace(downSave)),
                polyFills(KotlinTraceSink.stateTrace(downMidend)),
            )
        }

    @Test
    fun `the 13-move win completes and freezes the count`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("cube", "c4x4", SEED)
            assertEquals("c4x4:205E,4", start.id)

            val midend = Midend.newGame(Cube, "c4x4", SEED)
            var expected = start
            for (letter in WIN) {
                val key = cursorKey(letter)
                expected = oracle.keys(expected.state, listOf(key))
                assertEquals(
                    "outcome before '$letter'",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, Cube.preferredTileSize),
                )
                assertEquals("save after '$letter'", expected.state, midend.serialise().serialise())
                assertEquals("id after '$letter'", expected.id, midend.id())
                assertEquals("status after '$letter'", expected.status, midend.status())
            }

            assertEquals("win status", 1, midend.status())
            assertEquals("win text", "COMPLETED! Moves: 13", midend.statusText())
            assertEquals("win completed", 13, (midend.state as Cube.State).completed)
            assertEquals("win move count", 13, (midend.state as Cube.State).movecount)
            assertEquals("win state count", 14, midend.stateCount)
            assertEquals("win can undo", true, midend.canUndo)
            assertEquals("win can redo", false, midend.canRedo)
            assertEquals("win trace status", "COMPLETED! Moves: 13", statusOf(oracle.trace(expected.state)))
            assertEquals("win event", true, midend.takeCompletedEvent())
            assertEquals("win event is consumed", false, midend.takeCompletedEvent())

            // One more UP rolls the blue die: the count advances, the
            // completion count and the text stay frozen.
            val postWin = oracle.keys(expected.state, listOf(Buttons.CURSOR_UP))
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, Buttons.CURSOR_UP, Cube.preferredTileSize),
            )
            assertEquals("post-win save", postWin.state, midend.serialise().serialise())
            assertEquals("post-win status", 1, midend.status())
            assertEquals("post-win text", "COMPLETED! Moves: 13", midend.statusText())
            assertEquals("post-win move count", 14, (midend.state as Cube.State).movecount)
            assertEquals("post-win completed", 13, (midend.state as Cube.State).completed)
            assertTrue(
                "post-win faces stay blue",
                (midend.state as Cube.State).facecolours.all { it != 0 },
            )
            // A post-win move does not raise the completion event again.
            assertEquals("post-win event", false, midend.takeCompletedEvent())
        }

    @Test
    fun `undo and redo around completion raise the event once`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("cube", "c4x4", SEED)
            val midend = Midend.newGame(Cube, "c4x4", SEED)
            var expected = start.state
            for (letter in WIN) {
                expected = oracle.keys(expected, listOf(cursorKey(letter))).state
                midend.processKey(0, 0, cursorKey(letter), Cube.preferredTileSize)
            }
            assertTrue("the win raised the event", midend.takeCompletedEvent())
            assertEquals("COMPLETED! Moves: 13", midend.statusText())

            // Undo out of the completed state: status 0, no event.
            val beforeWin = oracle.undo(expected)
            assertTrue("undo", midend.undo())
            assertEquals("undo status", 0, midend.status())
            assertEquals("undo text", "Moves: 12", midend.statusText())
            assertEquals("undo save", beforeWin.state, midend.serialise().serialise())
            assertEquals("undo event", false, midend.takeCompletedEvent())
            assertEquals("undo keeps the redo branch", true, midend.canRedo)

            // Redo into the completed state: status 1, the event again.
            val afterWin = oracle.redo(beforeWin.state)
            assertTrue("redo", midend.redo())
            assertEquals("redo status", 1, midend.status())
            assertEquals("redo text", "COMPLETED! Moves: 13", midend.statusText())
            assertEquals("redo save", afterWin.state, midend.serialise().serialise())
            assertEquals("redo event", true, midend.takeCompletedEvent())
            assertEquals("redo event is consumed", false, midend.takeCompletedEvent())

            // A further undo from the completed state, then another undo:
            // the event stays false and the status stays open.
            assertTrue("second undo", midend.undo())
            assertEquals("second undo event", false, midend.takeCompletedEvent())
            assertEquals(0, midend.status())

            // A restart from an open state cannot complete.
            assertTrue("restart", midend.restart())
            assertEquals("restart status", 0, midend.status())
            assertEquals("restart text", "Moves: 0", midend.statusText())
            assertEquals("restart event", false, midend.takeCompletedEvent())
            assertEquals(0, (midend.state as Cube.State).movecount)
            assertEquals(0, (midend.state as Cube.State).completed)
        }

    @Test
    fun `the palette and the request keys match the oracle`() =
        OracleClient().use { oracle ->
            val expected = FloatArray(9)
            expected[0] = 0.8f
            expected[1] = 0.8f
            expected[2] = 0.8f
            expected[8] = 1.0f
            assertArrayEquals("colours", expected, Cube.colours())
            val background = floatArrayOf(0.25f, 0.5f, 0.75f)
            val custom = Cube.colours(background)
            for (i in 0..2) assertEquals("background $i", background[i], custom[i])
            assertEquals(0.0f, custom[3])
            assertEquals(0.0f, custom[4])
            assertEquals(0.0f, custom[5])
            assertEquals(0.0f, custom[6])
            assertEquals(0.0f, custom[7])
            assertEquals(1.0f, custom[8])

            val save = oracle.newGame("cube", "", SEED).state
            val keys = oracle.requestKeys(save)
            assertEquals(1, keys.arrowMode)
            assertTrue("oracle labels", keys.labels.isEmpty())
            val request = Cube.requestKeys(Cube.decodeParams("c4x4"))
            assertEquals(ArrowMode.ARROWS_ONLY, request.arrowMode)
            assertTrue("Kotlin labels", request.labels.isEmpty())
        }

    @Test
    fun `sizes and the cursor rectangle match the C geometry`() =
        OracleClient().use { oracle ->
            for (params in Cube.presets()) {
                val p = Cube.decodeParams(params.params) as Cube.Params
                val save = oracle.newGame("cube", params.params, SEED).state
                for (tile in listOf(48, 63, 82, 100)) {
                    val size = Cube.computeSize(p, tile)
                    val layout = oracle.layout(save, size.width, size.height)
                    assertEquals("'${params.params}' width at $tile", size.width, layout.width)
                    assertEquals("'${params.params}' height at $tile", size.height, layout.height)
                }
            }

            val cubeParams = Cube.decodeParams("c4x4") as Cube.Params
            val cube = Midend.newGame(Cube, "c4x4", SEED)
            assertEquals(
                "c4x4 current at tile 48",
                Game.CursorRect(24, 72, 48, 48),
                cube.cursorLocation(48),
            )
            assertEquals(
                "c4x4 current at tile 63",
                Game.CursorRect(32, 94, 63, 63),
                cube.cursorLocation(63),
            )
            assertEquals(
                "c4x4 current at tile 82",
                Game.CursorRect(41, 123, 82, 82),
                cube.cursorLocation(82),
            )
            assertEquals(
                "c4x4 current at tile 100",
                Game.CursorRect(50, 150, 100, 100),
                cube.cursorLocation(100),
            )
            assertEquals(
                "c4x4 size at tile 48",
                Size(240, 240),
                Cube.computeSize(cubeParams, 48),
            )

            val tetra = Midend.newGame(Cube, "t1x2", SEED)
            assertEquals(
                "t1x2 current at tile 48",
                Game.CursorRect(14, 97, 48, 41),
                tetra.cursorLocation(48),
            )
            val tetraParams = Cube.decodeParams("t1x2") as Cube.Params
            for (tile in listOf(48, 63, 82, 100)) {
                val rect = tetra.cursorLocation(tile)
                val size = Cube.computeSize(tetraParams, tile)
                assertTrue("t$tile left", rect!!.left >= 0)
                assertTrue("t$tile top", rect.top >= 0)
                assertTrue("t$tile width", rect.width > 0)
                assertTrue("t$tile height", rect.height > 0)
                assertTrue("t$tile within width", rect.left + rect.width <= size.width)
                assertTrue("t$tile within height", rect.top + rect.height <= size.height)
            }
        }

    @Test
    fun `a rotation changes the faces and the count, an inert click does neither`() {
        // The starting square (0,1) is not blue. A click on its exact
        // centre is inert. Two downward clicks reach the blue square
        // (0,3), which swaps a colour into the bottom face.
        val inert = Midend.newGame(Cube, "c4x4", SEED)
        val inertBefore = inert.state as Cube.State
        val facesBefore = inertBefore.facecolours.toList()
        val blueBefore = inertBefore.bluemask.toList()
        val countBefore = inertBefore.movecount

        assertEquals(
            KeyOutcome.NoEffect,
            inert.processKey(48, 96, Buttons.LEFT_BUTTON, Cube.preferredTileSize),
        )
        val afterInert = inert.state as Cube.State
        assertEquals("the inert click keeps the faces", facesBefore, afterInert.facecolours.toList())
        assertEquals("the inert click keeps the bluemask", blueBefore, afterInert.bluemask.toList())
        assertEquals("the inert click keeps the count", countBefore, afterInert.movecount)
        assertEquals("the inert click adds no state", 1, inert.stateCount)

        val midend = Midend.newGame(Cube, "c4x4", SEED)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 144, Buttons.LEFT_BUTTON, Cube.preferredTileSize),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(48, 192, Buttons.LEFT_BUTTON, Cube.preferredTileSize),
        )
        val after = midend.state as Cube.State
        assertNotEquals("the rotation changes the face colours", facesBefore, after.facecolours.toList())
        assertNotEquals("the rotation changes the bluemask", blueBefore, after.bluemask.toList())
        assertEquals("the rotation counts the moves", countBefore + 2, after.movecount)
        assertEquals("the rotation adds two states", 3, midend.stateCount)
    }

    private fun moveResult(params: String, desc: String, move: String): String? {
        val midend = Midend.fromGameId(Cube, "$params:$desc")
        return Cube.executeMove(midend.state, move)?.let { "ok" }
    }

    private fun polyFills(trace: JsonValue.Obj): List<Int> =
        (trace.field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .filter { it.string("op") == "poly" }
            .map { it.number("fill").toInt() }

    private fun statusOf(trace: JsonValue.Obj): String =
        (trace.field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .filter { it.string("op") == "status" }
            .also { assertEquals("one status line", 1, it.size) }
            .single()
            .string("text")

    private fun cursorKey(letter: Char): Int = when (letter) {
        'U' -> Buttons.CURSOR_UP
        'D' -> Buttons.CURSOR_DOWN
        'L' -> Buttons.CURSOR_LEFT
        'R' -> Buttons.CURSOR_RIGHT
        else -> error("not a move: $letter")
    }

    private fun saveText(params: String, desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Cube.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private fun assertArrayEquals(what: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$what length", expected.size, actual.size)
        for (i in expected.indices) assertEquals("$what[$i]", expected[i], actual[i])
    }

    private companion object {
        const val SEED = "12345"
        const val DESC = "205E,4"
        val WIN = listOf('U', 'R', 'R', 'D', 'D', 'R', 'L', 'D', 'L', 'L', 'R', 'U', 'D')
    }
}
