package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
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
        val files = listOf("default", "6", "10", "15", "20", "25").map {
            File(directory, "$it.sgtp")
        }
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            val midend = Midend.fromSave(Untangle, save)
            val expected = oracle.id(text)

            assertEquals("id ${file.name}", expected.id, midend.id())
            assertEquals("status ${file.name}", expected.status, midend.status())
            assertEquals("undo ${file.name}", expected.canUndo, midend.canUndo)
            assertEquals("redo ${file.name}", expected.canRedo, midend.canRedo)
            assertEquals("save ${file.name}", text, midend.serialise().serialise())

            assertEquals(
                "PARAMS equals CPARAMS in ${file.name}",
                save.value("PARAMS"),
                save.value("CPARAMS"),
            )
            assertNotNull("AUXINFO in ${file.name}", save.value("AUXINFO"))
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
    fun `a kotlin generated save loads in C for every preset and seed`() =
        OracleClient().use { oracle ->
            for (params in listOf("6", "10", "15", "20", "25")) {
                for (seed in listOf("12345", "1", "-7", "another seed", "")) {
                    val midend = Midend.newGame(Untangle, params, seed)
                    val text = midend.serialise().serialise()
                    val expected = oracle.id(text)
                    assertEquals("id '$params' '$seed'", midend.id(), expected.id)
                    assertEquals("save '$params' '$seed'", text, expected.state)
                    val save = SgtpSave.parse(text)
                    assertEquals(save.value("PARAMS"), save.value("CPARAMS"))
                    assertNotNull("AUXINFO '$params' '$seed'", save.value("AUXINFO"))
                    assertNull("UI '$params' '$seed'", save.value("UI"))
                }
            }
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

    private companion object {
        const val SEED = "12345"
    }
}
