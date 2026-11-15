package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import name.boyle.chris.sgtpuzzles.engine.Slant
import name.boyle.chris.sgtpuzzles.engine.Twiddle
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
    fun `slant is dual-run but the app still uses C`() {
        assertEquals(Slant, KotlinGames.gameFor(SLANT))
        assertFalse(KotlinGames.handles(SLANT))
    }

    @Test
    fun `samegame is registered but the app still uses C`() {
        assertEquals(SameGame, KotlinGames.gameFor(SAMEGAME))
        assertFalse(KotlinGames.handles(SAMEGAME))
    }

    @Test
    fun `flood is registered but the app still uses C`() {
        assertEquals(Flood, KotlinGames.gameFor(FLOOD))
        assertFalse(KotlinGames.handles(FLOOD))
    }

    @Test
    fun `sixteen is registered but the app still uses C`() {
        assertEquals(Sixteen, KotlinGames.gameFor(SIXTEEN))
        assertFalse(KotlinGames.handles(SIXTEEN))
    }

    @Test
    fun `flip is registered but the app still uses C`() {
        assertEquals(Flip, KotlinGames.gameFor(FLIP))
        assertFalse(KotlinGames.handles(FLIP))
    }

    @Test
    fun `twiddle is registered but the app still uses C`() {
        assertEquals(Twiddle, KotlinGames.gameFor(TWIDDLE))
        assertFalse(KotlinGames.handles(TWIDDLE))
    }

    @Test
    fun `the sixteen generator matches the frozen save`() {
        val expected = File(testdata, "saves/initial/sixteen/4x4.sgtp").readText()
        val actual = KotlinGames.generate(SIXTEEN, "4x4", "12345")
        assertEquals(expected, actual)
    }

    @Test
    fun `the slant generator matches the frozen save`() {
        val expected = File(testdata, "saves/initial/slant/5x5de.sgtp").readText()
        val actual = KotlinGames.generate(SLANT, "5x5de", "12345")
        assertEquals(expected, actual)
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
