package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * samegame.
 *
 * Every `samegame` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes
 * a Kotlin-written save and lets C read it.
 *
 * The last test pins the parameter asymmetry that upstream has: a
 * non-soluble game is generated from `...r`, but the long-term
 * `PARAMS` record keeps the short form and the game ID uses it. The
 * full form survives only in `CPARAMS`. This is upstream behaviour and
 * must not be "fixed".
 */
class SameGameSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every samegame save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val saves = File(testdata, "saves")
            val files = saves.walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == SameGame.name
                }
                .sorted()
                .toList()
            assertTrue("no samegame saves in the corpus", files.isNotEmpty())

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(SameGame, SgtpSave.parse(text))

                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
            }
        }

    @Test
    fun `a Kotlin save loads in the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("5x5c3s2", "10x5c3s2", "15x10c4s2", "20x15c4s2")) {
            val midend = Midend.newGame(SameGame, params, SEED)
            val text = midend.serialise().serialise()
            val loaded = oracle.id(text)
            assertEquals("$params id", midend.id(), loaded.id)
            assertEquals("$params canonical text", text, loaded.state)
            assertEquals("$params text", midend.textFormat(), oracle.text(text))
            assertEquals("$params status", midend.status(), loaded.status)
            assertFalse("$params starts with no undo", loaded.canUndo)
        }
    }

    @Test
    fun `a non-soluble game keeps the short form in PARAMS`() = OracleClient().use { oracle ->
        // `encode_params` writes `r` only in the full form, so a save
        // cannot put solubility in PARAMS and the ID cannot carry it.
        val midend = Midend.newGame(SameGame, "7x3c4s1r", SEED)
        val save = midend.serialise()

        assertEquals("PARAMS", "7x3c4s1", save.value("PARAMS"))
        assertEquals("CPARAMS", "7x3c4s1r", save.value("CPARAMS"))
        assertTrue("the ID uses the short form", midend.id().startsWith("7x3c4s1:"))
        assertFalse(
            "the ID cannot carry solubility",
            midend.id().startsWith("7x3c4s1r:"),
        )

        val text = save.serialise()
        val loaded = oracle.id(text)
        assertEquals("the oracle reads the Kotlin save", text, loaded.state)
        assertEquals("id", midend.id(), loaded.id)

        val expected = oracle.newGame("samegame", "7x3c4s1r", SEED)
        assertEquals("the oracle generated the same save", expected.state, text)
        assertEquals("the oracle generated the same id", expected.id, midend.id())
    }

    private companion object {
        const val SEED = "12345"
    }
}
