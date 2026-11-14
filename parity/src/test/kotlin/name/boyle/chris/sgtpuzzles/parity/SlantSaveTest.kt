package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state.
 *
 * Reads every `slant` save in the corpus with the Kotlin midend, writes
 * it back byte for byte, and asks the oracle to load both versions. Slant
 * is the first game whose save carries an obfuscated `AUXINFO`, so this
 * also pins the auxiliary-solution encoding.
 */
class SlantSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every slant save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val saves = File(testdata, "saves")
            val files = saves.walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    val save = SgtpSave.parse(file.readText())
                    save.value("GAME") == Slant.name
                }
                .toList()
            assertTrue("no slant saves in the corpus", files.isNotEmpty())

            for (file in files) {
                val text = file.readText()
                val save = SgtpSave.parse(text)
                val midend = Midend.fromSave(Slant, save)

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
        for (params in listOf("5x5de", "5x5dh", "8x8de", "12x10dh")) {
            val midend = Midend.newGame(Slant, params, "12345")
            val text = midend.serialise().serialise()
            val loaded = oracle.id(text)
            assertEquals("$params id", midend.id(), loaded.id)
            assertEquals("$params state", text, loaded.state)
            assertEquals("$params text", midend.textFormat(), oracle.text(text))
        }
    }
}
