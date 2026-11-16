package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: pattern.
 *
 * Every corpus save is read by the Kotlin midend, rewritten byte for
 * byte, and reloaded by C. A Kotlin save for every preset is loaded by C
 * with the same id, text, and status. The generated saves are
 * byte-identical, which pins the `AUXINFO` encoding. The history cases
 * cover a played click, an undone position with a live redo branch, a
 * `SOLVE`, and a `RESTART`. `PARAMS` and `CPARAMS` are both `WxH` and
 * there is no `UI` record. `default.sgtp` is the `15x15` file.
 */
class PatternSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24
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
    fun `every pattern save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/pattern")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(6, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Pattern, SgtpSave.parse(text))
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
                assertEquals("${file.name} history", 1, midend.stateCount)
                assertFalse("${file.name} canUndo", midend.canUndo)
                assertFalse("${file.name} canRedo", midend.canRedo)
                assertEquals("${file.name} status", 0, midend.status())
            }

            // `default.sgtp` and `15x15.sgtp` are the same file.
            assertEquals(
                File(testdata, "saves/initial/pattern/15x15.sgtp").readText(),
                File(testdata, "saves/initial/pattern/default.sgtp").readText(),
            )
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Pattern.presets().map { it.params }) {
                val midend = Midend.newGame(Pattern, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertEquals("$params status", midend.status(), loaded.status)
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertTrue("$params carries AUXINFO", SgtpSave.parse(text).value("AUXINFO") != null)
                assertEquals("$params PARAMS", params, SgtpSave.parse(text).value("PARAMS"))
                assertEquals("$params CPARAMS", params, SgtpSave.parse(text).value("CPARAMS"))
            }
        }

    @Test
    fun `a generated save is byte-identical to the oracle`() = OracleClient().use { oracle ->
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
        for (params in Pattern.presets().map { it.params }) {
            for (seed in seeds) {
                val expected = oracle.newGame("pattern", params, seed)
                val actual = Midend.newGame(Pattern, params, seed)
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
    fun `a click, a live redo branch, a SOLVE and a RESTART round-trip`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Pattern, "10x10", SEED)
            click(midend, 0, 0, Buttons.LEFT_BUTTON)
            val clicked = midend.serialise().serialise()
            assertEquals(listOf("F0,0,1,1"), midend.serialise().values("MOVE"))
            assertSame("click", oracle.id(clicked), midend)

            click(midend, 2, 2, Buttons.RIGHT_BUTTON)
            val second = midend.serialise().serialise()
            assertEquals(listOf("F0,0,1,1", "E2,2,1,1"), midend.serialise().values("MOVE"))
            assertSame("second click", oracle.id(second), midend)

            assertTrue(midend.undo())
            val undone = midend.serialise().serialise()
            assertEquals("2", SgtpSave.parse(undone).value("STATEPOS"))
            assertEquals("3", SgtpSave.parse(undone).value("NSTATES"))
            assertTrue(midend.canRedo)
            assertSame("undone redo branch", oracle.id(undone), midend)
            assertEquals(
                "the redo branch survives a load",
                second,
                Midend.fromSave(Pattern, SgtpSave.parse(undone)).let {
                    it.redo()
                    it.serialise().serialise()
                },
            )

            val solved = Midend.newGame(Pattern, "10x10", SEED)
            assertTrue(solved.solve())
            val solveText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(solveText).values("SOLVE").size)
            assertSame("solve save", oracle.id(solveText), solved)
            val solveLoaded = Midend.fromSave(Pattern, SgtpSave.parse(solveText))
            assertEquals(solveText, solveLoaded.serialise().serialise())
            assertEquals(1, solveLoaded.status())
            assertFalse(solveLoaded.takeCompletedEvent())

            click(solved, 0, 0, Buttons.LEFT_BUTTON)
            assertTrue(solved.restart())
            val restartText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(restartText).values("RESTART").size)
            assertSame("restart save", oracle.id(restartText), solved)
            val restartLoaded = Midend.fromSave(Pattern, SgtpSave.parse(restartText))
            assertEquals(restartText, restartLoaded.serialise().serialise())
            assertTrue(restartLoaded.canUndo)
            assertFalse(restartLoaded.canRedo)
            assertEquals(0, restartLoaded.status())
        }

    @Test
    fun `the PARAMS and CPARAMS are both WxH and there is no UI record`() =
        OracleClient().use { oracle ->
            for (params in listOf("10x10", "15x15", "20x20", "25x25", "30x30")) {
                val text = File(testdata, "saves/initial/pattern/$params.sgtp").readText()
                val save = SgtpSave.parse(text)
                assertEquals("$params PARAMS", params, save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                assertNull("$params no UI", save.value("UI"))

                val midend = Midend.fromSave(Pattern, save)
                assertEquals(text, midend.serialise().serialise())
                assertSame("$params", oracle.id(text), midend)
            }
        }

    @Test
    fun `the save without AUXINFO solves through the solver`() = OracleClient().use { oracle ->
        val withAux = oracle.newGame("pattern", "10x10", SEED).state
        val withoutAux = SgtpSave.of(
            SgtpSave.parse(withAux).records.filter {
                it.key != "AUXINFO" && it.key != "SEED"
            },
        ).serialise()
        val expected = oracle.solve(withoutAux)
        val midend = Midend.fromSave(Pattern, SgtpSave.parse(withoutAux))
        assertTrue(midend.solve())
        assertEquals(expected.id, midend.id())
        assertEquals(1, midend.status())
        assertEquals(
            SgtpSave.parse(expected.state).values("SOLVE").single(),
            midend.serialise().values("SOLVE").single(),
        )
    }

    @Test
    fun `a suffix description round-trips and rebuilds the immutable cells`() =
        OracleClient().use { oracle ->
            for (desc in listOf(
                "1/1//2,aaAA",
                "2/1/2/1/1.1/2/1/1,dddd",
            )) {
                val params = if (desc.startsWith("1/1")) "2x2" else "4x4"
                val midend = Midend.fromGameId(Pattern, "$params:$desc")
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("'$desc' id", midend.id(), loaded.id)
                assertEquals("'$desc' canonical text", text, loaded.state)
                assertEquals("'$desc' status", 0, loaded.status)

                val reloaded = Midend.fromSave(Pattern, SgtpSave.parse(text))
                assertEquals(text, reloaded.serialise().serialise())
                val state = reloaded.state as Pattern.State
                assertTrue("'$desc' has immutable cells", state.common.immutable.any { it })
                assertEquals("'$desc' id after load", midend.id(), reloaded.id())
            }

            // The all-immutable 2x2 wins with a no-op move, and the save
            // keeps the state.
            val winning = Midend.fromGameId(Pattern, "2x2:1/1//2,aaAA")
            winning.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
            winning.processKey(0, 0, Buttons.CURSOR_SELECT, TILE)
            assertEquals(1, winning.status())
            val wonText = winning.serialise().serialise()
            assertSame("no-op win save", oracle.id(wonText), winning)
        }

    @Test
    fun `an empty seed round-trips`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Pattern, "10x10", "")
        val text = midend.serialise().serialise()
        assertEquals("", SgtpSave.parse(text).value("SEED"))
        assertSame("empty seed", oracle.id(text), midend)
        assertEquals(
            text,
            Midend.fromSave(Pattern, SgtpSave.parse(text)).serialise().serialise(),
        )
    }

    private fun click(midend: Midend, x: Int, y: Int, button: Int) {
        val px = 3 * TILE / 4 + TILE / 2 + TILE * (10 / 5 + 2 + x) + TILE / 2
        val py = 3 * TILE / 4 + TILE / 2 + TILE * (10 / 5 + 2 + y) + TILE / 2
        midend.processKey(px, py, button, TILE)
        val release = when (button) {
            Buttons.LEFT_BUTTON -> Buttons.LEFT_RELEASE
            else -> Buttons.RIGHT_RELEASE
        }
        midend.processKey(px, py, release, TILE)
    }
}
