package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text. Game: untangle.
 *
 * The six corpus saves rewrite byte for byte. A Kotlin-generated save
 * loads in C for every preset and several seeds. Played clicks, drags,
 * an undone position with a live redo branch, a solve, a restart, and
 * the empty move round-trip in both directions. A `MOVE` whose value is
 * `S` fails in both engines.
 */
class UntangleSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the corpus saves rewrite byte for byte`() = OracleClient().use { oracle ->
        val directory = File(testdata, "saves/initial/untangle")
        val files = directory.walkTopDown()
            .filter { it.isFile && it.extension == "sgtp" }
            .sorted()
            .toList()
        assertEquals("every untangle corpus save", 6, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            val midend = Midend.fromSave(Untangle, save)
            val expected = oracle.id(text)

            assertEquals("id ${file.name}", expected.id, midend.id())
            assertEquals("status ${file.name}", expected.status, midend.status())
            assertEquals("undo ${file.name}", expected.canUndo, midend.canUndo)
            assertEquals("redo ${file.name}", expected.canRedo, midend.canRedo)
            assertEquals("text ${file.name}", oracle.text(text), midend.textFormat())
            assertEquals("save ${file.name}", text, midend.serialise().serialise())

            assertEquals(
                "PARAMS equals CPARAMS in ${file.name}",
                save.value("PARAMS"),
                save.value("CPARAMS"),
            )
            assertNotNull("AUXINFO in ${file.name}", save.value("AUXINFO"))
            val params = requireNotNull(save.value("PARAMS"))
            val generated = SgtpSave.parse(
                oracle.newGame("untangle", params, requireNotNull(save.value("SEED"))).state,
            )
            assertEquals(
                "AUXINFO bytes in ${file.name}",
                generated.value("AUXINFO"),
                save.value("AUXINFO"),
            )
            assertNull("no UI record in ${file.name}", save.value("UI"))
            assertEquals("NSTATES in ${file.name}", "1", save.value("NSTATES"))
            assertEquals("STATEPOS in ${file.name}", "1", save.value("STATEPOS"))
        }

        assertEquals(
            "default and 10 are the same game",
            File(directory, "10.sgtp").readText(),
            File(directory, "default.sgtp").readText(),
        )
    }

    @Test
    fun `a kotlin generated save matches the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            for (params in listOf("6", "10", "15", "20", "25")) {
                for (seed in SEEDS) {
                    val midend = Midend.newGame(Untangle, params, seed)
                    val text = midend.serialise().serialise()

                    // Kotlin to C: a fresh game from the same seed, then
                    // a reload of the Kotlin text.
                    val generated = oracle.newGame("untangle", params, seed)
                    assertEquals("generated id '$params' '$seed'", generated.id, midend.id())
                    assertEquals("generated save '$params' '$seed'", generated.state, text)
                    val expected = oracle.id(text)
                    assertEquals("reloaded id '$params' '$seed'", midend.id(), expected.id)
                    assertEquals("reloaded save '$params' '$seed'", text, expected.state)
                    assertEquals("text '$params' '$seed'", oracle.text(text), midend.textFormat())
                    assertEquals("status '$params' '$seed'", expected.status, midend.status())
                    assertEquals("undo '$params' '$seed'", expected.canUndo, midend.canUndo)
                    assertEquals("redo '$params' '$seed'", expected.canRedo, midend.canRedo)

                    // C to Kotlin: load the C game's save and compare.
                    val reloaded = Midend.fromSave(
                        Untangle,
                        SgtpSave.parse(generated.state),
                    )
                    assertSame("C to Kotlin '$params' '$seed'", generated, reloaded)
                    assertEquals(
                        "C to Kotlin save '$params' '$seed'",
                        generated.state,
                        reloaded.serialise().serialise(),
                    )

                    val save = SgtpSave.parse(text)
                    assertEquals(
                        "PARAMS '$params' '$seed'",
                        save.value("PARAMS"),
                        save.value("CPARAMS"),
                    )
                    assertNotNull("AUXINFO '$params' '$seed'", save.value("AUXINFO"))
                    assertEquals(
                        "aux text '$params' '$seed'",
                        SgtpSave.parse(generated.state).value("AUXINFO"),
                        save.value("AUXINFO"),
                    )
                    assertNull("UI '$params' '$seed'", save.value("UI"))
                }
            }
        }

    @Test
    fun `the in-memory history covers a click, a drag, and a live redo`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(save))
            val start = midend.state as Untangle.State
            val reference = Untangle.newState(Untangle.Params(10), midend.desc) as Untangle.State

            // A click on point 1, a few pixels off its pixel: one move,
            // one state, and a real coordinate change.
            val clickPoint = pixel(reference, 1, 64)
            val click = listOf(
                OracleClient.Point(
                    clickPoint.first + 3,
                    clickPoint.second + 2,
                    Buttons.LEFT_BUTTON,
                ),
                OracleClient.Point(
                    clickPoint.first + 3,
                    clickPoint.second + 2,
                    Buttons.LEFT_RELEASE,
                ),
            )
            var expected = oracle.pointsSized(save, click, width = 320, height = 320).state
            for (p in click) midend.processKey(p.x, p.y, p.button, 64)
            assertEquals("one click step", 2, midend.stateCount)
            assertSame("click", expected, midend)
            assertNotEquals(
                "the click changed point 1",
                start.point(1),
                (midend.state as Untangle.State).point(1),
            )

            // A drag of point 0 to a new pixel: a second move.
            val dragPoint = pixel(reference, 0, 64)
            val drag = listOf(
                OracleClient.Point(dragPoint.first, dragPoint.second, Buttons.LEFT_BUTTON),
                OracleClient.Point(
                    dragPoint.first + 32,
                    dragPoint.second + 16,
                    Buttons.LEFT_DRAG,
                ),
                OracleClient.Point(
                    dragPoint.first + 64,
                    dragPoint.second + 64,
                    Buttons.LEFT_RELEASE,
                ),
            )
            expected = oracle.pointsSized(expected.state, drag, width = 320, height = 320).state
            for (p in drag) midend.processKey(p.x, p.y, p.button, 64)
            assertEquals("two history steps", 3, midend.stateCount)
            assertSame("drag", expected, midend)
            assertNotEquals(
                "the drag changed point 0",
                start.point(0),
                (midend.state as Untangle.State).point(0),
            )

            // Undo leaves a live redo branch, and redo restores it.
            expected = oracle.undo(expected.state)
            assertTrue("undo", midend.undo())
            assertSame("after undo", expected, midend)
            assertEquals("at the click", 2, midend.statePos)
            assertTrue("can undo", midend.canUndo)
            assertTrue("can redo", midend.canRedo)

            expected = oracle.redo(expected.state)
            assertTrue("redo", midend.redo())
            assertSame("after redo", expected, midend)
            assertEquals("three states", 3, midend.stateCount)
            assertEquals("status", expected.status, midend.status())
            assertEquals("text", oracle.text(expected.state), midend.textFormat())
        }

    @Test
    fun `a played click and a drag round trip in both directions`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state

            // A click on point 0.
            val click = saveWith(
                save,
                listOf("MOVE" to "P0:160,23/64"),
            )
            val clicked = Midend.fromSave(Untangle, SgtpSave.parse(click))
            val expectedClick = oracle.id(click)
            assertEquals("click id", expectedClick.id, clicked.id())
            assertEquals("click save", click, clicked.serialise().serialise())

            // Press, two drags, release.
            val drag = saveWith(
                click,
                listOf("MOVE" to "P0:240,120/64"),
            )
            val dragged = Midend.fromSave(Untangle, SgtpSave.parse(drag))
            val expectedDrag = oracle.id(drag)
            assertEquals("drag id", expectedDrag.id, dragged.id())
            assertEquals("drag save", drag, dragged.serialise().serialise())
            assertEquals("drag states", 3, dragged.stateCount)
        }

    @Test
    fun `an undone position with a live redo branch round trips`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state
            val undone = saveWith(
                save,
                listOf(
                    "MOVE" to "P0:160,23/64",
                    "MOVE" to "P1:241,49/64",
                ),
                statePos = 2,
            )
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(undone))
            val expected = oracle.id(undone)
            assertEquals("id", expected.id, midend.id())
            assertEquals("save", undone, midend.serialise().serialise())
            assertTrue("can undo", midend.canUndo)
            assertTrue("can redo", midend.canRedo)
            assertEquals("three states", 3, midend.stateCount)
        }

    @Test
    fun `a solve and a restart round trip`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "6", SEED).state
        val solved = oracle.solve(save)
        val midend = Midend.newGame(Untangle, "6", SEED)
        assertTrue(midend.solve())
        assertEquals("solve save", solved.state, midend.serialise().serialise())

        val restarted = midend.restart()
        assertTrue(restarted)
        val text = midend.serialise().serialise()
        assertEquals(
            "a restart appends the description",
            SgtpSave.parse(text).value("RESTART"),
            midend.desc,
        )
        val expected = oracle.id(text)
        assertEquals("restart id", expected.id, midend.id())
        assertEquals("restart save", text, expected.state)
        assertEquals("restart status", expected.status, midend.status())
        assertTrue("the restart is undoable", midend.undo())
        assertEquals("back at the solve", 2, midend.statePos)
    }

    @Test
    fun `the empty move is valid and S alone is invalid in both engines`() =
        OracleClient().use { oracle ->
            val empty = saveWith(
                oracle.newGame("untangle", "10", SEED).state,
                listOf("MOVE" to ""),
            )
            val midend = Midend.fromSave(Untangle, SgtpSave.parse(empty))
            val expected = oracle.id(empty)
            assertEquals("the empty move keeps the id", expected.id, midend.id())
            assertEquals("the empty move is one state", 2, midend.stateCount)
            assertEquals("status", expected.status, midend.status())
            assertEquals("the empty move save", empty, midend.serialise().serialise())
            assertEquals("the empty move text", oracle.text(empty), midend.textFormat())

            val invalid = saveWith(
                oracle.newGame("untangle", "10", SEED).state,
                listOf("MOVE" to "S"),
            )
            try {
                oracle.id(invalid)
                fail("the oracle accepts MOVE S")
            } catch (e: OracleException) {
                assertEquals("oracle message", "Save file contained an invalid move", e.message)
            }
            try {
                Midend.fromSave(Untangle, SgtpSave.parse(invalid))
                fail("Kotlin accepts MOVE S")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "Kotlin message: ${e.message}",
                    e.message.orEmpty().contains("invalid move"),
                )
            }
        }

    @Test
    fun `the move index truncates like C and a mid-move S is accepted`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("untangle", "10", SEED).state

            // glibc reads `%d` as a `long` and keeps the low 32 bits,
            // so 2^32 is point 0, not an out-of-range index.
            val wrapped = saveWith(save, listOf("MOVE" to "P4294967296:1,2/3"))
            val wrappedMidend = Midend.fromSave(Untangle, SgtpSave.parse(wrapped))
            val wrappedExpected = oracle.id(wrapped)
            assertEquals("wrapped id", wrappedExpected.id, wrappedMidend.id())
            assertEquals("wrapped save", wrapped, wrappedMidend.serialise().serialise())
            assertEquals(
                "2^32 is point 0",
                Untangle.Point(1, 2, 3),
                (wrappedMidend.state as Untangle.State).point(0),
            )

            // -2^31-1 truncates to INT_MAX, which is out of range.
            val rejected = saveWith(save, listOf("MOVE" to "P-2147483649:1,2/3"))
            try {
                oracle.id(rejected)
                fail("the oracle accepts P-2147483649")
            } catch (e: OracleException) {
                assertEquals("oracle message", "Save file contained an invalid move", e.message)
            }
            try {
                Midend.fromSave(Untangle, SgtpSave.parse(rejected))
                fail("Kotlin accepts P-2147483649")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "Kotlin message: ${e.message}",
                    e.message.orEmpty().contains("invalid move"),
                )
            }

            // `S` may start any segment, not only the first, so this is
            // one accepted move with two point updates.
            val midS = saveWith(save, listOf("MOVE" to "P0:160,23/64;S;P1:241,49/64"))
            val midMidend = Midend.fromSave(Untangle, SgtpSave.parse(midS))
            val midExpected = oracle.id(midS)
            assertEquals("mid S id", midExpected.id, midMidend.id())
            assertEquals("mid S save", midS, midMidend.serialise().serialise())
            assertEquals(
                "the mid S move is kept verbatim",
                "P0:160,23/64;S;P1:241,49/64",
                SgtpSave.parse(midMidend.serialise().serialise()).values("MOVE").last(),
            )
        }

    /**
     * Appends history records and rewrites `NSTATES` and `STATEPOS`.
     * The base save must have one state.
     */
    private fun saveWith(
        base: String,
        extra: List<Pair<String, String>>,
        statePos: Int = 1 + extra.size,
    ): String {
        val parsed = SgtpSave.parse(base)
        val historyKeys = setOf("MOVE", "SOLVE", "RESTART")
        val history = parsed.records.filter { it.key in historyKeys }
        val header = parsed.records.filter {
            it.key !in historyKeys && it.key != "NSTATES" && it.key != "STATEPOS"
        }
        val baseCount = requireNotNull(parsed.value("NSTATES")).toInt()
        val count = baseCount + extra.size
        val result = ArrayList(header)
        result += SaveRecord("NSTATES", count.toString())
        result += SaveRecord("STATEPOS", statePos.toString())
        result += history
        for ((key, value) in extra) result += SaveRecord(key, value)
        return SgtpSave.of(result).serialise()
    }

    private fun pixel(state: Untangle.State, index: Int, tile: Int): Pair<Int, Int> {
        val point = state.point(index)
        return (point.x * tile / point.d).toInt() to (point.y * tile / point.d).toInt()
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("save $what", expected.state, actual.serialise().serialise())
        assertEquals("id $what", expected.id, actual.id())
        assertEquals("status $what", expected.status, actual.status())
        assertEquals("undo $what", expected.canUndo, actual.canUndo)
        assertEquals("redo $what", expected.canRedo, actual.canRedo)
    }

    private companion object {
        const val SEED = "12345"

        val SEEDS = listOf("12345", "1", "2", "3", "seed", "another seed", "0", "-7", "")
    }
}
