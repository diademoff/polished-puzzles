package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.PortedGames
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.Rect
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.Singles
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import name.boyle.chris.sgtpuzzles.engine.Slant
import name.boyle.chris.sgtpuzzles.engine.Solo
import name.boyle.chris.sgtpuzzles.engine.Tents
import name.boyle.chris.sgtpuzzles.engine.Towers
import name.boyle.chris.sgtpuzzles.engine.Tracks
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import name.boyle.chris.sgtpuzzles.engine.Undead
import name.boyle.chris.sgtpuzzles.engine.Unequal
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

    /**
     * The games enabled by the waves of 2026-11-18 and 2026-11-19, in
     * wave order. The eighth wave is the last two backends.
     */
    private val enabledGames = setOf(
        FIFTEEN, SAMEGAME, FLOOD, PEGS,
        SIXTEEN, FLIP, TWIDDLE, GUESS,
        BLACKBOX, LIGHTUP, UNDEAD, RECT,
        SLANT, RANGE, TOWERS, UNEQUAL,
        MAGNETS, PATTERN, MOSAIC, SIGNPOST, UNTANGLE,
        GALAXIES, BRIDGES, UNRULY, FILLING, MINES,
        NETSLIDE, PALISADE, INERTIA, TENTS, NET, TRACKS,
        DOMINOSA, MAP, KEEN, SINGLES, PEARL, LOOPY,
        SOLO, CUBE,
    )

    @Test
    fun `the enabled waves use the Kotlin backend`() {
        for (backend in enabledGames) {
            assertTrue(
                "$backend must run on the Kotlin backend",
                KotlinGames.handles(backend),
            )
        }
    }

    @Test
    fun `slant is registered and handled`() {
        assertEquals(Slant, KotlinGames.gameFor(SLANT))
        assertTrue(KotlinGames.handles(SLANT))
    }

    @Test
    fun `samegame is registered and handled`() {
        assertEquals(SameGame, KotlinGames.gameFor(SAMEGAME))
        assertTrue(KotlinGames.handles(SAMEGAME))
    }

    @Test
    fun `flood is registered and handled`() {
        assertEquals(Flood, KotlinGames.gameFor(FLOOD))
        assertTrue(KotlinGames.handles(FLOOD))
    }

    @Test
    fun `sixteen is registered and handled`() {
        assertEquals(Sixteen, KotlinGames.gameFor(SIXTEEN))
        assertTrue(KotlinGames.handles(SIXTEEN))
    }

    @Test
    fun `flip is registered and handled`() {
        assertEquals(Flip, KotlinGames.gameFor(FLIP))
        assertTrue(KotlinGames.handles(FLIP))
    }

    @Test
    fun `twiddle is registered and handled`() {
        assertEquals(Twiddle, KotlinGames.gameFor(TWIDDLE))
        assertTrue(KotlinGames.handles(TWIDDLE))
    }

    @Test
    fun `pegs is registered and handled`() {
        assertEquals(Pegs, KotlinGames.gameFor(PEGS))
        assertTrue(KotlinGames.handles(PEGS))
    }

    @Test
    fun `guess is registered and handled`() {
        assertEquals(Guess, KotlinGames.gameFor(GUESS))
        assertTrue(KotlinGames.handles(GUESS))
    }

    @Test
    fun `blackbox is registered and handled`() {
        assertEquals(BlackBox, KotlinGames.gameFor(BLACKBOX))
        assertTrue(KotlinGames.handles(BLACKBOX))
    }

    @Test
    fun `lightup is registered and handled`() {
        assertEquals(Lightup, KotlinGames.gameFor(LIGHTUP))
        assertTrue(KotlinGames.handles(LIGHTUP))
    }

    @Test
    fun `undead is registered and handled`() {
        assertEquals(Undead, KotlinGames.gameFor(UNDEAD))
        assertTrue(KotlinGames.handles(UNDEAD))
    }

    @Test
    fun `rect is registered and handled`() {
        assertEquals(Rect, KotlinGames.gameFor(RECT))
        assertTrue(KotlinGames.handles(RECT))
    }

    @Test
    fun `bridges is registered and handled`() {
        assertEquals(Bridges, KotlinGames.gameFor(BRIDGES))
        assertTrue(KotlinGames.handles(BRIDGES))
    }

    @Test
    fun `cube is registered and handled`() {
        assertEquals(Cube, KotlinGames.gameFor(CUBE))
        assertTrue(KotlinGames.handles(CUBE))
    }

    @Test
    fun `filling is registered and handled`() {
        assertEquals(Filling, KotlinGames.gameFor(FILLING))
        assertTrue(KotlinGames.handles(FILLING))
    }

    @Test
    fun `unruly is registered and handled`() {
        assertEquals(Unruly, KotlinGames.gameFor(UNRULY))
        assertTrue(KotlinGames.handles(UNRULY))
    }

    @Test
    fun `range is registered and handled`() {
        assertEquals(Range, KotlinGames.gameFor(RANGE))
        assertTrue(KotlinGames.handles(RANGE))
    }

    @Test
    fun `mosaic is registered and handled`() {
        assertEquals(Mosaic, KotlinGames.gameFor(MOSAIC))
        assertTrue(KotlinGames.handles(MOSAIC))
    }

    @Test
    fun `magnets is registered and handled`() {
        assertEquals(Magnets, KotlinGames.gameFor(MAGNETS))
        assertTrue(KotlinGames.handles(MAGNETS))
    }

    @Test
    fun `pattern is registered and handled`() {
        assertEquals(Pattern, KotlinGames.gameFor(PATTERN))
        assertTrue(KotlinGames.handles(PATTERN))
    }

    @Test
    fun `signpost is registered and handled`() {
        assertEquals(Signpost, KotlinGames.gameFor(SIGNPOST))
        assertTrue(KotlinGames.handles(SIGNPOST))
    }

    @Test
    fun `untangle is registered and handled`() {
        assertEquals(Untangle, KotlinGames.gameFor(UNTANGLE))
        assertTrue(KotlinGames.handles(UNTANGLE))
    }

    @Test
    fun `mines is registered and handled`() {
        assertEquals(Mines, KotlinGames.gameFor(MINES))
        assertTrue(KotlinGames.handles(MINES))
    }

    @Test
    fun `towers is registered and handled`() {
        assertEquals(Towers, KotlinGames.gameFor(TOWERS))
        assertTrue(KotlinGames.handles(TOWERS))
    }

    @Test
    fun `unequal is registered and handled`() {
        assertEquals(Unequal, KotlinGames.gameFor(UNEQUAL))
        assertTrue(KotlinGames.handles(UNEQUAL))
    }

    @Test
    fun `solo is registered and handled`() {
        assertEquals(Solo, KotlinGames.gameFor(SOLO))
        assertTrue(KotlinGames.handles(SOLO))
        val actual = KotlinGames.generate(SOLO, "3x3", "12345")
        assertTrue(actual.contains("GAME    :4:Solo"))
        assertTrue(actual.contains("DESC    :55:1_4c3_9b2a6_7j6a5_2b3_2e6b3a7b8e9_7b5_8a1j2_8a9b7_6c3_5"))
        assertTrue(actual.contains("AUXINFO :"))
    }

    @Test
    fun `the solo generator matches the frozen save`() {
        val expected = File(testdata, "saves/initial/solo/3x3.sgtp").readText()
        val actual = KotlinGames.generate(SOLO, "3x3", "12345")
        assertEquals(expected, actual)
    }

    @Test
    fun `the rect generator matches the frozen save`() {
        val expected = File(testdata, "saves/initial/rect/7x7.sgtp").readText()
        val actual = KotlinGames.generate(RECT, "7x7", "12345")
        assertEquals(expected, actual)
    }

    @Test
    fun `galaxies is registered and handled`() {
        assertEquals(Galaxies, KotlinGames.gameFor(GALAXIES))
        assertTrue(KotlinGames.handles(GALAXIES))
        val actual = KotlinGames.generate(GALAXIES, "7x7dn", "12345")
        assertTrue(actual.contains("GAME    :8:Galaxies"))
        assertTrue(actual.contains("DESC    :13:cjrbhizuisfvf"))
        assertTrue(actual.contains("AUXINFO :"))
    }

    @Test
    fun `the loopy generator matches the frozen save`() {
        val expected = File(testdata, "saves/initial/loopy/7x7t0dh.sgtp").readText()
        val actual = KotlinGames.generate(LOOPY, "7x7t0dh", "12345")
        assertEquals(expected, actual)
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
    fun `the seventh wave is registered and handled`() {
        val seventh = listOf(
            NETSLIDE to Netslide,
            PALISADE to Palisade,
            INERTIA to Inertia,
            TENTS to Tents,
            NET to Net,
            TRACKS to Tracks,
            DOMINOSA to Dominosa,
            MAP to Map,
            KEEN to Keen,
            SINGLES to Singles,
            PEARL to Pearl,
            LOOPY to Loopy,
        )
        for ((backend, game) in seventh) {
            assertEquals("$backend engine", game, KotlinGames.gameFor(backend))
            assertTrue("$backend must run on the Kotlin backend", KotlinGames.handles(backend))
        }
    }

    @Test
    fun `the enabled set is exactly the eight waves`() {
        val enabled = BackendName.all.filter { KotlinGames.handles(it) }.toSet()
        assertEquals(
            "the eight waves are fifteen, samegame, flood, pegs, sixteen, flip, " +
                "twiddle, guess, blackbox, lightup, undead, rect, slant, range, " +
                "towers, unequal, magnets, pattern, mosaic, signpost, untangle, " +
                "galaxies, bridges, unruly, filling, mines, netslide, palisade, " +
                "inertia, tents, net, tracks, dominosa, map, keen, singles, " +
                "pearl, loopy, solo, and cube",
            enabledGames,
            enabled,
        )
        for (backend in BackendName.all) {
            assertEquals(
                "$backend must use Kotlin only in an enablement wave",
                backend in enabledGames,
                KotlinGames.handles(backend),
            )
        }
    }

    @Test
    fun `each enabled generator writes a save that loads with its backend`() {
        val cases = listOf(
            Triple(FIFTEEN, "4x4", "fifteen/4x4.sgtp"),
            Triple(SAMEGAME, "5x5c3s2", "samegame/default.sgtp"),
            Triple(FLOOD, "12x12c6m5", "flood/default.sgtp"),
            Triple(PEGS, "7x7cross", "pegs/default.sgtp"),
            Triple(SIXTEEN, "4x4", "sixteen/default.sgtp"),
            Triple(FLIP, "5x5c", "flip/default.sgtp"),
            Triple(TWIDDLE, "3x3n2", "twiddle/default.sgtp"),
            Triple(GUESS, "c6p4g10Bm", "guess/default.sgtp"),
            Triple(BLACKBOX, "w5h5m3M3", "blackbox/w5h5m3M3.sgtp"),
            Triple(LIGHTUP, "7x7b20s3d0", "lightup/7x7b20s3d0.sgtp"),
            Triple(UNDEAD, "4x4dn", "undead/4x4dn.sgtp"),
            Triple(RECT, "7x7", "rect/7x7.sgtp"),
            Triple(SLANT, "5x5de", "slant/5x5de.sgtp"),
            Triple(RANGE, "9x6", "range/9x6.sgtp"),
            Triple(TOWERS, "5de", "towers/5de.sgtp"),
            Triple(UNEQUAL, "4de", "unequal/4de.sgtp"),
            Triple(MAGNETS, "6x5de", "magnets/6x5de.sgtp"),
            Triple(PATTERN, "15x15", "pattern/15x15.sgtp"),
            Triple(MOSAIC, "10x10", "mosaic/10x10.sgtp"),
            Triple(SIGNPOST, "4x4c", "signpost/4x4c.sgtp"),
            Triple(UNTANGLE, "10", "untangle/10.sgtp"),
            Triple(GALAXIES, "7x7dn", "galaxies/7x7dn.sgtp"),
            Triple(BRIDGES, "7x7i30e10m2d1", "bridges/7x7i30e10m2d1.sgtp"),
            Triple(UNRULY, "8x8dt", "unruly/8x8dt.sgtp"),
            Triple(FILLING, "9x7", "filling/9x7.sgtp"),
            Triple(MINES, "9x9n10", "mines/9x9n10.sgtp"),
            Triple(NETSLIDE, "3x3", "netslide/3x3.sgtp"),
            Triple(PALISADE, "5x5n5", "palisade/5x5n5.sgtp"),
            Triple(INERTIA, "10x8", "inertia/10x8.sgtp"),
            Triple(TENTS, "8x8de", "tents/8x8de.sgtp"),
            Triple(NET, "5x5", "net/5x5.sgtp"),
            Triple(TRACKS, "8x8dt", "tracks/8x8dt.sgtp"),
            Triple(DOMINOSA, "6db", "dominosa/6db.sgtp"),
            Triple(MAP, "20x15n30dn", "map/20x15n30dn.sgtp"),
            Triple(KEEN, "6dn", "keen/6dn.sgtp"),
            Triple(SINGLES, "5x5de", "singles/5x5de.sgtp"),
            Triple(PEARL, "8x8de", "pearl/8x8de.sgtp"),
            Triple(LOOPY, "7x7t0de", "loopy/7x7t0de.sgtp"),
            Triple(SOLO, "2x2", "solo/2x2.sgtp"),
            Triple(CUBE, "c4x4", "cube/c4x4.sgtp"),
        )
        for ((backend, params, path) in cases) {
            val text = KotlinGames.generate(backend, params, "12345")
            assertEquals(
                "$backend must match the frozen save",
                File(testdata, "saves/initial/$path").readText(),
                text,
            )
            val save = SgtpSave.parse(text)
            val game = requireNotNull(KotlinGames.gameFor(backend))
            assertEquals(
                "$backend wrote the wrong GAME record",
                game.name,
                save.value("GAME"),
            )
            assertEquals(
                "$backend must resolve to the named backend",
                backend.sourceName,
                game.backendName,
            )
            val midend = Midend.fromSave(game, save)
            assertEquals(
                "$backend must reload its own save unchanged",
                text,
                midend.serialise().serialise(),
            )
        }
    }

    @Test
    fun `the engine registry is every backend and nothing else`() {
        val ported = PortedGames.all.map { it.backendName }.toSet()
        assertEquals(
            "every BackendName must have exactly one Kotlin backend",
            BackendName.all.map { it.sourceName }.toSet(),
            ported,
        )
        assertEquals(40, PortedGames.all.size)
        assertEquals(
            "two registry entries share a game object",
            ported.size,
            PortedGames.all.size,
        )
        for (backend in BackendName.all) {
            assertTrue(
                "$backend must resolve through the registry",
                KotlinGames.gameFor(backend) === PortedGames.byBackend(backend.sourceName),
            )
        }
    }

    /**
     * The gate must not enable a game whose C `get_prefs` is non-NULL
     * while its Kotlin schema is missing. C truth for
     * [Game.declaresPrefs] is checked against the oracle in
     * `PrefsSchemaParityTest`; this test checks that the app's gate
     * rejects both mismatches and that every registered game agrees with
     * itself.
     */
    @Test
    fun `the prefs gate rejects a missing or stray schema`() {
        assertTrue(KotlinGames.isSelectable(Slant))

        val missingSchema = object : Game by Slant {
            override val declaresPrefs: Boolean get() = true
            override val prefs: List<ConfigItem> get() = emptyList()
        }
        assertFalse(
            "a C prefs game without a Kotlin schema must not be selectable",
            KotlinGames.isSelectable(missingSchema),
        )

        val straySchema = object : Game by Fifteen {
            override val declaresPrefs: Boolean get() = false
            override val prefs: List<ConfigItem> get() = Slant.prefs
        }
        assertFalse(
            "a schema without the declaresPrefs flag must not be selectable",
            KotlinGames.isSelectable(straySchema),
        )

        for (game in PortedGames.all) {
            assertEquals(
                "${game.backendName} declares its C prefs exactly when it has a schema",
                game.declaresPrefs,
                game.prefs.isNotEmpty(),
            )
        }
    }

    @Test
    fun `defaultParams is the engine full default for every known game`() {
        var known = 0
        for (backend in BackendName.all) {
            val game = KotlinGames.gameFor(backend) ?: continue
            known++
            assertEquals(
                backend.toString(),
                game.encodeParams(game.defaultParams(), true),
                KotlinGames.defaultParams(backend),
            )
        }
        assertEquals(40, known)
    }

    @Test
    fun `the fifteen generator is a pure function of its arguments`() {
        val first = KotlinGames.generate(FIFTEEN, "4x4", "12345")
        val second = KotlinGames.generate(FIFTEEN, "4x4", "12345")
        assertEquals(first, second)
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
