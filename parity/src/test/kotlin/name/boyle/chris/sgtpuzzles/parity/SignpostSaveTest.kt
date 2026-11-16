package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * signpost.
 *
 * Every corpus save is read by the Kotlin midend, rewritten byte for
 * byte, and reloaded by C. A Kotlin save for every preset is loaded by C
 * with the same id, text, and status. The history covers a played link,
 * a cut, an undone position with a live redo branch, a `SOLVE`, a
 * `RESTART`, and a `MOVE H`. The split is pinned: `PARAMS` is always the
 * short form without `c`, `CPARAMS` keeps the corner flag. No save has a
 * `UI` or `AUXINFO` record.
 */
class SignpostSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 48
        const val ID = "4x4:1dcegcefebeghcbh16a"
        const val DESC = "1dcegcefebeghcbh16a"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun saveText(
        params: String,
        cparams: String,
        desc: String,
        records: List<Pair<String, String>> = emptyList(),
        states: Int = 1,
        pos: Int = 1,
    ): String {
        val all = ArrayList<Pair<String, String>>()
        all += "SAVEFILE" to SgtpSave.MAGIC
        all += "VERSION" to SgtpSave.VERSION
        all += "GAME" to Signpost.name
        all += "PARAMS" to params
        all += "CPARAMS" to cparams
        all += "SEED" to SEED
        all += "DESC" to desc
        all += "NSTATES" to states.toString()
        all += "STATEPOS" to pos.toString()
        all.addAll(records)
        return all.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun click(midend: Midend, x0: Int, y0: Int, x1: Int, y1: Int) {
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            midend.processKey(x0, y0, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            midend.processKey(x1, y1, Buttons.LEFT_RELEASE, TILE),
        )
    }

    @Test
    fun `every corpus save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/signpost")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(7, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Signpost, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertFalse(
                    "${file.name} has no UI record",
                    midend.serialise().records.any { it.key == "UI" },
                )
                assertNull("${file.name} has no AUXINFO", midend.serialise().value("AUXINFO"))
                assertEquals("${file.name} history", 1, midend.stateCount)
                assertFalse("${file.name} canUndo", midend.canUndo)
                assertFalse("${file.name} canRedo", midend.canRedo)
                assertEquals("${file.name} status", 0, midend.status())
            }

            // The corpus pins the split: the short form drops the flag.
            val corner = File(testdata, "saves/initial/signpost/4x4c.sgtp").readText()
            assertEquals("4x4", SgtpSave.parse(corner).value("PARAMS"))
            assertEquals("4x4c", SgtpSave.parse(corner).value("CPARAMS"))
            assertEquals(ID, oracle.id(corner).id)
            // `default.sgtp` and `4x4.sgtp` are the same free-ends game.
            assertEquals(
                File(testdata, "saves/initial/signpost/4x4.sgtp").readText(),
                File(testdata, "saves/initial/signpost/default.sgtp").readText(),
            )
        }

    @Test
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Signpost.presets().map { it.params }) {
                for (seed in listOf("1", "12345", "")) {
                    val midend = Midend.newGame(Signpost, params, seed)
                    val text = midend.serialise().serialise()
                    val loaded = oracle.id(text)
                    val where = "'$params' seed '$seed'"
                    assertEquals("$where id", midend.id(), loaded.id)
                    assertEquals("$where canonical text", text, loaded.state)
                    assertEquals("$where text", midend.textFormat(), oracle.text(text))
                    assertEquals("$where status", midend.status(), loaded.status)
                    assertFalse("$where starts with no undo", loaded.canUndo)
                    assertFalse("$where starts with no redo", loaded.canRedo)
                    assertFalse(
                        "$where has no UI",
                        midend.serialise().records.any { it.key == "UI" },
                    )
                    assertNull("$where has no AUXINFO", midend.serialise().value("AUXINFO"))

                    // The short form always drops `c`; the full form keeps it.
                    val short = Signpost.encodeParams(
                        Signpost.decodeParams(params),
                        false,
                    )
                    assertEquals("$where PARAMS", short, SgtpSave.parse(text).value("PARAMS"))
                    assertEquals("$where CPARAMS", params, SgtpSave.parse(text).value("CPARAMS"))
                }
            }
        }

    @Test
    fun `a link, a cut, a live redo branch, a SOLVE and a RESTART round-trip`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Signpost, "4x4c", SEED)
            click(midend, 36, 36, 84, 84)
            val linked = midend.serialise().serialise()
            assertEquals(listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
            assertEquals(2, midend.stateCount)
            assertSame("link", oracle.id(linked), midend)

            // Cell (0,0) is now linked, so a release outside the board
            // cuts it. The cut unlinks one cell.
            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
                midend.processKey(36, 36, Buttons.LEFT_BUTTON, TILE),
            )
            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
                midend.processKey(-50, -50, Buttons.LEFT_RELEASE, TILE),
            )
            assertEquals(listOf("L0,0-1,1", "C0,0"), midend.serialise().values("MOVE"))
            val cut = midend.serialise().serialise()
            assertSame("cut", oracle.id(cut), midend)

            assertTrue(midend.undo())
            val undone = midend.serialise().serialise()
            assertEquals("2", SgtpSave.parse(undone).value("STATEPOS"))
            assertEquals("3", SgtpSave.parse(undone).value("NSTATES"))
            assertTrue(midend.canRedo)
            assertSame("undone redo branch", oracle.id(undone), midend)
            assertEquals(
                "the redo branch survives a load",
                cut,
                Midend.fromSave(Signpost, SgtpSave.parse(undone)).let {
                    assertTrue(it.redo())
                    it.serialise().serialise()
                },
            )

            val solved = Midend.newGame(Signpost, "4x4c", SEED)
            assertTrue(solved.solve())
            val solveText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(solveText).values("SOLVE").size)
            assertEquals(1, solved.status())
            assertSame("solve save", oracle.id(solveText), solved)
            val solveLoaded = Midend.fromSave(Signpost, SgtpSave.parse(solveText))
            assertEquals(solveText, solveLoaded.serialise().serialise())
            assertTrue((solveLoaded.state as Signpost.State).usedSolve)

            val restarted = Midend.newGame(Signpost, "4x4c", SEED)
            click(restarted, 36, 36, 84, 84)
            assertTrue(restarted.restart())
            val restartText = restarted.serialise().serialise()
            assertEquals(1, SgtpSave.parse(restartText).values("RESTART").size)
            assertEquals(0, restarted.status())
            assertSame("restart save", oracle.id(restartText), restarted)
        }

    @Test
    fun `a hand-written MOVE H and SOLVE save load in both directions`() =
        OracleClient().use { oracle ->
            val h = saveText(
                "4x4",
                "4x4c",
                DESC,
                records = listOf("MOVE" to "H"),
                states = 2,
                pos = 2,
            )
            val midend = Midend.fromSave(Signpost, SgtpSave.parse(h))
            assertEquals(h, midend.serialise().serialise())
            val expected = oracle.id(h)
            assertSame("MOVE H", expected, midend)
            // The H move solves the board but does not mark it cheated
            // or raise a completion event.
            assertFalse((midend.state as Signpost.State).usedSolve)
            assertEquals(1, midend.status())
            assertEquals(oracle.text(h), midend.textFormat())
            assertFalse("a load raises no completion event", midend.takeCompletedEvent())

            val s = saveText(
                "4x4",
                "4x4c",
                DESC,
                records = listOf("SOLVE" to "S1d9c11e10g13c4e14f7e3b5e2g8h15c6b12h16a"),
                states = 2,
                pos = 2,
            )
            val solveMidend = Midend.fromSave(Signpost, SgtpSave.parse(s))
            assertEquals(s, solveMidend.serialise().serialise())
            val solveExpected = oracle.id(s)
            assertSame("hand-written SOLVE", solveExpected, solveMidend)
            assertTrue((solveMidend.state as Signpost.State).usedSolve)
            assertEquals(1, solveMidend.status())
            assertFalse("a SOLVE load raises no completion event", solveMidend.takeCompletedEvent())

            // A solved state from the solver has the same shape.
            val fresh = Midend.newGame(Signpost, "4x4c", SEED)
            assertTrue(fresh.solve())
            val fromSolver = fresh.serialise().serialise()
            assertEquals(
                "the solver payload is the hand-written one",
                SgtpSave.parse(fromSolver).value("SOLVE"),
                SgtpSave.parse(s).value("SOLVE"),
            )
        }

    @Test
    fun `a corner PARAMS save is accepted and rewritten byte for byte`() =
        OracleClient().use { oracle ->
            val text = saveText("4x4c", "4x4c", DESC)
            val midend = Midend.fromSave(Signpost, SgtpSave.parse(text))
            assertEquals(text, midend.serialise().serialise())
            val loaded = oracle.id(text)
            assertEquals(text, loaded.state)
            assertEquals(ID, loaded.id)

            // A Kotlin new game writes the split form instead.
            val generated = Midend.newGame(Signpost, "4x4c", SEED).serialise().serialise()
            assertEquals("4x4", SgtpSave.parse(generated).value("PARAMS"))
            assertEquals("4x4c", SgtpSave.parse(generated).value("CPARAMS"))
            assertFalse(generated == text)
        }
}
