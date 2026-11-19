package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Level: semantic. The measured generation caps.
 *
 * D-007, D-008, and D-014 gained tighter numbers, and D-026 to D-034
 * add a cap to every other game whose generator was effectively
 * endless. Every cap is a generation guard: it depends only on the
 * parameters, it runs before the first RNG draw, and it leaves
 * `validateParams` and the load path at the C behavior. These tests pin
 * the exact number, the fixed message, and the untouched random state
 * one step above each cap. The accepted side, the C `DESC`, and an
 * above-cap save that still loads live in
 * `parity/.../GenerationLimitTest`.
 */
class GenerationLimitTest {

    private fun assertRefusedAbove(
        game: Game,
        params: String,
        message: String,
    ) {
        val random = RandomState.fromSeed("12345")
        val before = random.encodeState()
        val error = assertFailsWith<IllegalArgumentException>(params) {
            game.newDesc(game.decodeParams(params), random)
        }
        assertEquals(message, error.message, params)
        assertEquals(before, random.encodeState(), "$params must draw nothing")
    }

    @Test
    fun `the caps are the measured numbers`() {
        assertEquals(90_000, Netslide.MAX_AREA)
        assertEquals(50, Palisade.MAX_REGIONS)
        assertEquals(40_000, Inertia.MAX_AREA)
        assertEquals(484, Tents.MAX_AREA)
        assertEquals(10_000, Net.MAX_AREA)
        assertEquals(484, Tracks.MAX_AREA)
        assertEquals(9, Dominosa.MAX_N)
        assertEquals(100, Map.MAX_GENERATION_REGIONS)
        assertEquals(400, Singles.MAX_AREA)
        assertEquals(121, Pearl.MAX_AREA)
        assertEquals(400, Loopy.MAX_AREA)
        // D-036: 500 by 500 cells, and a triangle board counts its cells.
        assertEquals(250_000, Cube.MAX_AREA)
        assertEquals(250_000L, Cube.areaOf(500, 500, 4))
        assertEquals(250_500L, Cube.areaOf(501, 500, 4))
        assertEquals(301_056L, Cube.areaOf(224, 224, 3))

        assertEquals(25, Solo.maxSymbols(soloDiff("5x5db")))
        assertEquals(25, Solo.maxSymbols(soloDiff("5x5di")))
        assertEquals(16, Solo.maxSymbols(soloDiff("4x4da")))
        assertEquals(16, Solo.maxSymbols(soloDiff("4x4de")))
        assertEquals(9, Solo.maxSymbols(soloDiff("3x3du")))
    }

    private fun soloDiff(text: String): Int =
        (Solo.decodeParams(text) as Solo.Params).diff

    @Test
    fun `netslide refuses an area above 90,000 before any draw`() {
        assertEquals(300 * 300, Netslide.MAX_AREA)
        assertRefusedAbove(
            Netslide,
            "301x300b1",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `palisade refuses more than 50 regions before any draw`() {
        // 26x20n10 is 52 regions, the first measured size above 50; it
        // is fast if the guard is removed, so this test fails rather
        // than hangs.
        assertRefusedAbove(
            Palisade,
            "26x20n10",
            "Too many regions to generate in this build",
        )
    }

    @Test
    fun `inertia refuses an area above 40,000 before any draw`() {
        assertRefusedAbove(
            Inertia,
            "201x200",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `tents refuses an area above 484 before any draw`() {
        assertRefusedAbove(
            Tents,
            "23x22dt",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `net refuses an area above 10,000 before any draw`() {
        assertRefusedAbove(
            Net,
            "101x100",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `tracks refuses an area above 484 before any draw`() {
        assertRefusedAbove(
            Tracks,
            "23x22dh",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `dominosa refuses a maximum number above 9 before any draw`() {
        assertRefusedAbove(
            Dominosa,
            "10db",
            "Maximum number is too large for this build",
        )
    }

    @Test
    fun `map refuses more than 100 regions at generation only`() {
        assertRefusedAbove(
            Map,
            "100x100n101dn",
            "Too many regions for this build",
        )
        // The load path keeps the 4096-region structural limit, so a
        // save above the generation cap is not refused here.
        assertNotEquals(
            Map.REGIONS_TOO_LARGE,
            Map.validateDesc(Map.Params(100, 100, 101, 1), "x"),
        )
    }

    @Test
    fun `singles refuses an area above 400 before any draw`() {
        assertRefusedAbove(
            Singles,
            "21x20de",
            "Width times height is too large for this build",
        )
        // D-017 keeps its own message at a side of 62.
        assertRefusedAbove(Singles, "62x2de", "Puzzle is too large")
    }

    @Test
    fun `pearl refuses an area above 121 before any draw`() {
        assertRefusedAbove(
            Pearl,
            "12x11de",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `loopy refuses an area above 400 before any draw`() {
        assertRefusedAbove(
            Loopy,
            "21x20t0dh",
            "Width times height is too large for this build",
        )
    }

    @Test
    fun `cube refuses an area above 250,000 before any draw`() {
        // 501x500 is 250,500 cells, the first size above the cap. The
        // guard runs before the enumeration, so this test does not
        // allocate a board.
        assertRefusedAbove(Cube, "c501x500", "Grid area is too large for this build")
        // A triangle solid counts its cells too: 6 * 224^2 is 301,056.
        assertRefusedAbove(Cube, "i224x224", "Grid area is too large for this build")
        // D-036 is generation-only: the validator keeps the C answer,
        // and a huge accepted area is never enumerated here.
        assertEquals(
            null,
            Cube.validateParams(Cube.decodeParams("c501x500"), true),
        )
    }

    @Test
    fun `solo refuses every difficulty band above its cap before any draw`() {
        val message = "Board size and difficulty are too large to generate in this build"
        // Simple is 25 symbols: 2x13 is 26.
        assertRefusedAbove(Solo, "2x13db", message)
        // Set and Extreme are 16 symbols: 2x9 is 18.
        assertRefusedAbove(Solo, "2x9da", message)
        assertRefusedAbove(Solo, "2x9de", message)
        // Recursive is 9 symbols: 2x5 is 10.
        assertRefusedAbove(Solo, "2x5du", message)
    }

    @Test
    fun `the solo guard accepts each band at its boundary`() {
        val simple = soloDiff("5x5db")
        val set = soloDiff("4x4da")
        val recursive = soloDiff("3x3du")
        Solo.requireGeneratableSize(5, 5, simple)
        Solo.requireGeneratableSize(4, 4, set)
        Solo.requireGeneratableSize(3, 3, recursive)
        assertEquals(
            Solo.SYMBOLS_TOO_LARGE,
            assertFailsWith<IllegalArgumentException> {
                Solo.requireGeneratableSize(5, 6, simple)
            }.message,
        )
        assertEquals(
            Solo.SYMBOLS_TOO_LARGE,
            assertFailsWith<IllegalArgumentException> {
                Solo.requireGeneratableSize(4, 5, set)
            }.message,
        )
        assertEquals(
            Solo.SYMBOLS_TOO_LARGE,
            assertFailsWith<IllegalArgumentException> {
                Solo.requireGeneratableSize(3, 4, recursive)
            }.message,
        )
    }

    @Test
    fun `the map generation cap leaves the load cap alone`() {
        Map.requireGeneratableRegions(100)
        assertEquals(
            Map.REGIONS_TOO_LARGE,
            assertFailsWith<IllegalArgumentException> {
                Map.requireGeneratableRegions(101)
            }.message,
        )
        Map.requirePortableBoard(1024, 1024, 4096)
        assertEquals(4096, Map.MAX_REGIONS)
    }
}
