package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Level: semantic. The Kotlin backend selection and its generator. */
class KotlinGamesTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `fifteen uses the Kotlin backend`() {
        assertTrue(KotlinGames.handles(FIFTEEN))
        assertFalse(KotlinGames.handles(MINES))
    }

    @Test
    fun `the generated save is the frozen oracle save`() {
        val expected = File(testdata, "saves/initial/fifteen/4x4.sgtp").readText()
        val actual = KotlinGames.generate(FIFTEEN, "4x4", "12345")
        assertEquals(expected, actual)
        assertEquals("Fifteen", SgtpSave.parse(actual).value("GAME"))
    }

    @Test
    fun `a fresh seed is printable`() {
        val seed = KotlinGames.newSeed()
        assertTrue(seed.isNotEmpty())
        assertTrue(seed.all { it.code in 32..126 })
    }
}
