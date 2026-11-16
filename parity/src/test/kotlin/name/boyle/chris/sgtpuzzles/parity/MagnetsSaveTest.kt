package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: magnets.
 *
 * Every corpus save is read by the Kotlin midend, rewritten byte for
 * byte, and reloaded by C. A Kotlin save for every preset is loaded by
 * C with the same id, text, and status. The generated saves are
 * byte-identical, which pins the `AUXINFO` encoding. The history cases
 * cover a played magnet click, a clue toggle, an undone position with a
 * live redo branch, a `SOLVE`, and a `RESTART`. The `PARAMS`/`CPARAMS`
 * split and the absent `UI` record are pinned; `default.sgtp` is the
 * `6x5de` file.
 */
class MagnetsSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `every magnets save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/magnets")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(9, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Magnets, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} keeps AUXINFO",
                    SgtpSave.parse(text).value("AUXINFO"),
                    midend.serialise().value("AUXINFO"),
                )
                assertFalse(
                    "${file.name} has no UI record",
                    midend.serialise().records.any { it.key == "UI" },
                )
            }

            // `default.sgtp` and `6x5de.sgtp` are the same file.
            assertEquals(
                File(testdata, "saves/initial/magnets/6x5de.sgtp").readText(),
                File(testdata, "saves/initial/magnets/default.sgtp").readText(),
            )
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Magnets.presets().map { it.params }) {
                val midend = Midend.newGame(Magnets, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertEquals("$params status", midend.status(), loaded.status)
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertTrue("$params carries AUXINFO", SgtpSave.parse(text).value("AUXINFO") != null)
            }
        }

    @Test
    fun `a generated save is byte-identical to the oracle`() = OracleClient().use { oracle ->
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "", "-7")
        for (params in Magnets.presets().map { it.params }) {
            for (seed in seeds) {
                val expected = oracle.newGame("magnets", params, seed)
                val actual = Midend.newGame(Magnets, params, seed)
                assertEquals("'$params' seed '$seed' id", expected.id, actual.id())
                assertEquals(
                    "'$params' seed '$seed' save",
                    expected.state,
                    actual.serialise().serialise(),
                )
                assertEquals(
                    "'$params' seed '$seed' AUXINFO",
                    SgtpSave.parse(expected.state).value("AUXINFO"),
                    actual.serialise().value("AUXINFO"),
                )
            }
        }
    }

    @Test
    fun `a magnet click, a clue toggle and a live redo branch round-trip`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Magnets, "6x5dt", SEED)
            click(midend, 0, 0, Buttons.LEFT_BUTTON)
            val clicked = midend.serialise().serialise()
            assertEquals(listOf("+0,0"), midend.serialise().values("MOVE"))
            assertSame("click", oracle.id(clicked), midend)

            clickClue(midend, 52, 20)
            val toggled = midend.serialise().serialise()
            assertEquals(listOf("+0,0", "D0,-1"), midend.serialise().values("MOVE"))
            assertSame("clue toggle", oracle.id(toggled), midend)

            assertTrue(midend.undo())
            val undone = midend.serialise().serialise()
            assertEquals("2", SgtpSave.parse(undone).value("STATEPOS"))
            assertEquals("3", SgtpSave.parse(undone).value("NSTATES"))
            assertTrue(midend.canRedo)
            assertSame("undone redo branch", oracle.id(undone), midend)

            val reloaded = Midend.fromSave(Magnets, SgtpSave.parse(undone))
            assertTrue(reloaded.canRedo)
            assertTrue(reloaded.canUndo)
            assertEquals(3, reloaded.stateCount)
            assertEquals(2, reloaded.statePos)

            val redone = oracle.redo(undone)
            assertTrue(midend.redo())
            assertSame("redone", redone, midend)
        }

    @Test
    fun `a SOLVE and a RESTART round-trip in both directions`() =
        OracleClient().use { oracle ->
            val solved = Midend.newGame(Magnets, "6x5de", SEED)
            assertTrue(solved.solve())
            val solveText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(solveText).values("SOLVE").size)
            assertSame("solve save", oracle.id(solveText), solved)

            val loaded = Midend.fromSave(Magnets, SgtpSave.parse(solveText))
            assertEquals(solveText, loaded.serialise().serialise())
            assertEquals(1, loaded.status())
            assertFalse(loaded.takeCompletedEvent())

            click(midend = solved, x = 0, y = 0, button = Buttons.LEFT_BUTTON)
            assertTrue(solved.restart())
            val restartText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(restartText).values("RESTART").size)
            assertSame("restart save", oracle.id(restartText), solved)

            val restartLoaded = Midend.fromSave(Magnets, SgtpSave.parse(restartText))
            assertEquals(restartText, restartLoaded.serialise().serialise())
            assertTrue(restartLoaded.canUndo)
            assertFalse(restartLoaded.canRedo)
            assertEquals(0, restartLoaded.status())
        }

    @Test
    fun `the PARAMS and CPARAMS split is pinned and there is no UI record`() =
        OracleClient().use { oracle ->
            for ((params, longTerm) in listOf(
                "6x5de" to "6x5de",
                "6x5dt" to "6x5de",
                "6x5dtS" to "6x5de",
                "8x7dt" to "8x7de",
                "10x9dtS" to "10x9de",
            )) {
                val text = File(testdata, "saves/initial/magnets/$params.sgtp").readText()
                val save = SgtpSave.parse(text)
                assertEquals("$params PARAMS", longTerm, save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                assertNull("$params no UI", save.value("UI"))

                val midend = Midend.fromSave(Magnets, save)
                assertEquals(text, midend.serialise().serialise())
                assertSame("$params", oracle.id(text), midend)
            }
        }

    @Test
    fun `the save without AUXINFO solves through the fallback`() =
        OracleClient().use { oracle ->
            val withAux = oracle.newGame("magnets", "6x5de", SEED).state
            val withoutAux = SgtpSave.of(
                SgtpSave.parse(withAux).records.filter {
                    it.key != "AUXINFO" && it.key != "SEED"
                },
            ).serialise()
            val expected = oracle.solve(withoutAux)
            val midend = Midend.fromSave(Magnets, SgtpSave.parse(withoutAux))
            assertTrue(midend.solve())
            assertSame("no aux solve", expected, midend)
            assertEquals(
                SgtpSave.parse(expected.state).values("SOLVE").single(),
                midend.serialise().values("SOLVE").single(),
            )
        }

    private fun clickClue(midend: Midend, x: Int, y: Int) {
        midend.processKey(x, y, Buttons.LEFT_BUTTON, TILE)
        midend.processKey(x, y, Buttons.LEFT_RELEASE, TILE)
    }

    private fun click(midend: Midend, x: Int, y: Int, button: Int) {
        val px = (x + 1) * TILE + TILE / 8 + TILE / 2
        val py = (y + 1) * TILE + TILE / 8 + TILE / 2
        midend.processKey(px, py, button, TILE)
        val release = if (button == Buttons.LEFT_BUTTON) {
            Buttons.LEFT_RELEASE
        } else {
            Buttons.RIGHT_RELEASE
        }
        midend.processKey(px, py, release, TILE)
    }
}
