package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import name.boyle.chris.sgtpuzzles.engine.Slant
import name.boyle.chris.sgtpuzzles.engine.Tents
import name.boyle.chris.sgtpuzzles.engine.Tracks
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import name.boyle.chris.sgtpuzzles.engine.Undead
import name.boyle.chris.sgtpuzzles.engine.Unruly
import name.boyle.chris.sgtpuzzles.engine.Untangle
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
    fun `pegs is registered but the app still uses C`() {
        assertEquals(Pegs, KotlinGames.gameFor(PEGS))
        assertFalse(KotlinGames.handles(PEGS))
    }

    @Test
    fun `guess is registered but the app still uses C`() {
        assertEquals(Guess, KotlinGames.gameFor(GUESS))
        assertFalse(KotlinGames.handles(GUESS))
    }

    @Test
    fun `blackbox is registered but the app still uses C`() {
        assertEquals(BlackBox, KotlinGames.gameFor(BLACKBOX))
        assertFalse(KotlinGames.handles(BLACKBOX))
    }

    @Test
    fun `bridges is registered but the app still uses C`() {
        assertEquals(Bridges, KotlinGames.gameFor(BRIDGES))
        assertFalse(KotlinGames.handles(BRIDGES))
    }

    @Test
    fun `cube is registered but the app still uses C`() {
        assertEquals(Cube, KotlinGames.gameFor(CUBE))
        assertFalse(KotlinGames.handles(CUBE))
    }

    @Test
    fun `filling is registered but the app still uses C`() {
        assertEquals(Filling, KotlinGames.gameFor(FILLING))
        assertFalse(KotlinGames.handles(FILLING))
    }

    @Test
    fun `netslide is registered but the app still uses C`() {
        assertEquals(Netslide, KotlinGames.gameFor(NETSLIDE))
        assertFalse(KotlinGames.handles(NETSLIDE))
    }

    @Test
    fun `net is registered but the app still uses C`() {
        assertEquals(Net, KotlinGames.gameFor(NET))
        assertFalse(KotlinGames.handles(NET))
    }

    @Test
    fun `unruly is registered but the app still uses C`() {
        assertEquals(Unruly, KotlinGames.gameFor(UNRULY))
        assertFalse(KotlinGames.handles(UNRULY))
    }

    @Test
    fun `range is registered but the app still uses C`() {
        assertEquals(Range, KotlinGames.gameFor(RANGE))
        assertFalse(KotlinGames.handles(RANGE))
    }

    @Test
    fun `lightup is registered but the app still uses C`() {
        assertEquals(Lightup, KotlinGames.gameFor(LIGHTUP))
        assertFalse(KotlinGames.handles(LIGHTUP))
    }

    @Test
    fun `mosaic is registered but the app still uses C`() {
        assertEquals(Mosaic, KotlinGames.gameFor(MOSAIC))
        assertFalse(KotlinGames.handles(MOSAIC))
    }

    @Test
    fun `palisade is registered but the app still uses C`() {
        assertEquals(Palisade, KotlinGames.gameFor(PALISADE))
        assertFalse(KotlinGames.handles(PALISADE))
    }

    @Test
    fun `inertia is registered but the app still uses C`() {
        assertEquals(Inertia, KotlinGames.gameFor(INERTIA))
        assertFalse(KotlinGames.handles(INERTIA))
    }

    @Test
    fun `tents is registered but the app still uses C`() {
        assertEquals(Tents, KotlinGames.gameFor(TENTS))
        assertFalse(KotlinGames.handles(TENTS))
    }

    @Test
    fun `magnets is registered but the app still uses C`() {
        assertEquals(Magnets, KotlinGames.gameFor(MAGNETS))
        assertFalse(KotlinGames.handles(MAGNETS))
    }

    @Test
    fun `pattern is registered but the app still uses C`() {
        assertEquals(Pattern, KotlinGames.gameFor(PATTERN))
        assertFalse(KotlinGames.handles(PATTERN))
    }

    @Test
    fun `signpost is registered but the app still uses C`() {
        assertEquals(Signpost, KotlinGames.gameFor(SIGNPOST))
        assertFalse(KotlinGames.handles(SIGNPOST))
    }

    @Test
    fun `undead is registered but the app still uses C`() {
        assertEquals(Undead, KotlinGames.gameFor(UNDEAD))
        assertFalse(KotlinGames.handles(UNDEAD))
    }

    @Test
    fun `untangle is registered but the app still uses C`() {
        assertEquals(Untangle, KotlinGames.gameFor(UNTANGLE))
        assertFalse(KotlinGames.handles(UNTANGLE))
    }

    @Test
    fun `mines is registered but the app still uses C`() {
        assertEquals(Mines, KotlinGames.gameFor(MINES))
        assertFalse(KotlinGames.handles(MINES))
    }

    @Test
    fun `tracks is registered but the app still uses C`() {
        assertEquals(Tracks, KotlinGames.gameFor(TRACKS))
        assertFalse(KotlinGames.handles(TRACKS))
    }

    @Test
    fun `the mines generator matches the frozen save`() {
        val expected = File(testdata, "saves/initial/mines/9x9n10.sgtp").readText()
        val actual = KotlinGames.generate(MINES, "9x9n10", "12345")
        assertEquals(expected, actual)
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
    fun `the platform seed is 16 bytes`() {
        val seed = platformSeed()
        assertEquals(16, seed.size)
    }

    @Test
    fun `a fresh seed is printable`() {
        val seed = KotlinGames.newSeed()
        assertTrue(seed.isNotEmpty())
        assertTrue(seed.all { it.code in 32..126 })
    }
}
