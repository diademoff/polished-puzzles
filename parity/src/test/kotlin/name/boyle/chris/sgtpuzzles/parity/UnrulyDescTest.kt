package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: unruly.
 *
 * Parameters, presets, validation, the decode quirks, the `u`/`d<c>`
 * asymmetry, the stale-difficulty default, and `params + seed -> DESC`.
 *
 * The generator draws in three places, and the deduction solver decides
 * both the retry and the clue winnow, so a single seed can hide a
 * mistake. The matrix covers all seven presets with the golden seed,
 * eight more seed strings, and a numeric sweep.
 */
class UnrulyDescTest {

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("unruly")
        val actual = Unruly.presets()

        assertEquals("preset count", 7, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // `unruly.c` has no `SMALL_SCREEN` branch, so the app and the
        // oracle expose the same seven presets.
        assertEquals(
            listOf(
                "8x8 Trivial", "8x8 Easy", "8x8 Normal",
                "10x10 Easy", "10x10 Normal",
                "14x14 Easy", "14x14 Normal",
            ),
            actual.map { it.title },
        )
        assertEquals(
            listOf("8x8dt", "8x8de", "8x8dn", "10x10de", "10x10dn", "14x14de", "14x14dn"),
            actual.map { it.params },
        )

        assertEquals(8, (Unruly.defaultParams() as Unruly.Params).width)
        assertEquals(8, (Unruly.defaultParams() as Unruly.Params).height)
        assertEquals(0, (Unruly.defaultParams() as Unruly.Params).diff)
        assertEquals("8x8dt", Unruly.encodeParams(Unruly.defaultParams(), true))
        assertEquals("8x8", Unruly.encodeParams(Unruly.defaultParams(), false))
    }

    @Test
    fun `decode quirks and the u d asymmetry match the oracle`() =
        OracleClient().use { oracle ->
            // Every row is a real `decode_params` quirk. The id carries
            // the accepted parameters, so it is the evidence.
            val cases = listOf(
                "8", "8x8", "8x8x8", "8X8", "8x8 ", " 8x8", "+8x8",
                "8x+8", "8x 8", "8x8u", "8x8ut", "8x8tu", "8x8udn",
                "8x8dte", "8u8", "8x8u8", "8x8dtx", "8x8de", "8x8dn",
            )
            for (text in cases) {
                assertEquals(
                    "id '$text'",
                    oracle.newGame("unruly", text, SEED).id,
                    Midend.newGame(Unruly, text, SEED).id(),
                )
            }

            // The short form keeps `u` and drops `d<c>`.
            assertEquals(
                "8x8:bEACHcBdBDEalcbAbbBa",
                Midend.newGame(Unruly, "8x8dt", SEED).id(),
            )
            assertEquals(
                "8x8u:AjBCcBBeCbhaBCaIbbBaa",
                Midend.newGame(Unruly, "8x8u", SEED).id(),
            )
            assertEquals(
                "8x8u:AjBbFgCbhafIFb",
                Midend.newGame(Unruly, "8x8udn", SEED).id(),
            )
            assertEquals(
                "the same id as 8x8dt",
                Midend.newGame(Unruly, "8x8dt", SEED).id(),
                Midend.newGame(Unruly, "8x8dte", SEED).id(),
            )
            assertEquals(
                "the same id as 8x8u",
                Midend.newGame(Unruly, "8x8u", SEED).id(),
                Midend.newGame(Unruly, "8u8", SEED).id(),
            )
            assertEquals(
                "the same id as 8x8dt",
                Midend.newGame(Unruly, "8x8dt", SEED).id(),
                Midend.newGame(Unruly, "8x8tu", SEED).id(),
            )

            // `8x8d` and `8x8dx` leave `diff` at DIFFCOUNT + 1.
            assertEquals(
                "Unknown difficulty rating",
                validationError(oracle, "8x8d"),
            )
            assertEquals(
                "Unknown difficulty rating",
                validationError(oracle, "8x8dx"),
            )
            assertEquals(
                "Unknown difficulty rating",
                Unruly.validateParams(Unruly.decodeParams("8x8d"), false),
            )
            assertEquals(
                "Unknown difficulty rating",
                Unruly.validateParams(Unruly.decodeParams("8x8dx"), false),
            )

            // `decode_params` does not reset `diff`. Kotlin decodes into
            // a fresh struct, so the base is the default, Trivial. A
            // descriptive id is the one path where C's previous params
            // can leave another value; the id, desc, state, and status
            // are unaffected.
            for (text in listOf("8x8", "8x8u", "8", "8x8ut")) {
                assertEquals(
                    "diff default for '$text'",
                    0,
                    (Unruly.decodeParams(text) as Unruly.Params).diff,
                )
            }
            assertEquals(
                "a full form does set diff",
                2,
                (Unruly.decodeParams("8x8udn") as Unruly.Params).diff,
            )
        }

    @Test
    fun `the parameter encoding and the invalid-diff guard are pinned`() {
        fun params(w: Int, h: Int, unique: Boolean, diff: Int) = Unruly.Params(w, h, unique, diff)

        assertEquals("8x8dt", Unruly.encodeParams(params(8, 8, false, 0), true))
        assertEquals("8x8", Unruly.encodeParams(params(8, 8, false, 0), false))
        assertEquals("8x8u", Unruly.encodeParams(params(8, 8, true, 0), false))
        assertEquals("8x8udn", Unruly.encodeParams(params(8, 8, true, 2), true))

        // C reads `unruly_diffchars[diff]` with no bound. Index 3 is the
        // array's NUL terminator, so the suffix is plain `d`; any larger
        // or negative index is out of bounds in C. The port writes the
        // same `d` instead of reading past the array. Validation runs
        // before encoding in the midend, so this is not reachable from a
        // save.
        assertEquals("8x8d", Unruly.encodeParams(params(8, 8, false, 3), true))
        assertEquals("8x8d", Unruly.encodeParams(params(8, 8, false, 99), true))
        assertEquals("8x8d", Unruly.encodeParams(params(8, 8, false, -1), true))
    }

    @Test
    fun `validation messages match the oracle in order`() = OracleClient().use { oracle ->
        val cases = listOf(
            "5x6", "7x7", "4x4", "8x47u", "8x46u", "46x8u", "8x8dq", "8x8d",
        )
        for (text in cases) {
            val expected = validationError(oracle, text)
            assertNotNull("'$text' must be rejected by C", expected)
            assertEquals(
                "error for '$text'",
                expected,
                Unruly.validateParams(Unruly.decodeParams(text), true),
            )
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Unruly.validateParams(Unruly.decodeParams(text), false),
            )
        }

        // The order is odd, then small, then area, then unique, then
        // difficulty.
        assertEquals(
            "Width and height must both be even",
            Unruly.validateParams(Unruly.Params(5, 8, false, 0), true),
        )
        assertEquals(
            "Width and height must both be even",
            Unruly.validateParams(Unruly.Params(8, 7, false, 0), true),
        )
        assertEquals(
            "Width and height must be at least 6",
            Unruly.validateParams(Unruly.Params(4, 4, false, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Unruly.validateParams(Unruly.Params(65536, 65536, false, 0), true),
        )
        assertEquals(
            "Puzzle is too tall for unique-rows mode",
            Unruly.validateParams(Unruly.Params(8, 36, true, 0), true),
        )
        assertEquals(
            "Puzzle is too long for unique-rows mode",
            Unruly.validateParams(Unruly.Params(36, 8, true, 0), true),
        )
        assertEquals(
            "Unknown difficulty rating",
            Unruly.validateParams(Unruly.Params(8, 8, false, 3), true),
        )

        // `8x34u` is the tallest accepted unique 8-wide board
        // (`A177790[4] == 34`). Do not generate it.
        assertNull(Unruly.validateParams(Unruly.Params(8, 34, true, 0), true))
        // The area boundary is the C one; do not generate it.
        assertNull(Unruly.validateParams(Unruly.Params(46340, 46340, false, 0), true))
        assertEquals(
            "Width times height must not be unreasonably large",
            Unruly.validateParams(Unruly.Params(65536, 65536, false, 0), true),
        )
        // C accepts a width of 48 or more in unique mode, because the
        // A177790 test only bounds the short side.
        assertNull(Unruly.validateParams(Unruly.Params(48, 48, true, 0), true))
    }

    @Test(timeout = 120_000)
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (preset in Unruly.presets()) {
                for (seed in seeds) {
                    val expected = oracle.newGame("unruly", preset.params, seed)
                    val actual = Midend.newGame(Unruly, preset.params, seed)
                    assertEquals(
                        "id unruly '${preset.params}' seed=$seed",
                        expected.id,
                        actual.id(),
                    )
                    assertEquals(
                        "status unruly '${preset.params}' seed=$seed",
                        expected.status,
                        actual.status(),
                    )
                    assertEquals(
                        "state unruly '${preset.params}' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                    assertTrue(
                        "description '${preset.params}' seed=$seed",
                        actual.desc.isNotEmpty(),
                    )
                    assertNull(
                        "description weight '${preset.params}' seed=$seed",
                        Unruly.validateDesc(
                            Unruly.decodeParams(preset.params),
                            actual.desc,
                        ),
                    )
                }
            }
        }

    @Test(timeout = 120_000)
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            for (params in listOf("8x8dt", "10x10de", "14x14dn")) {
                for (seed in 0..19) {
                    assertEquals(
                        "unruly '$params' seed=$seed",
                        oracle.newGame("unruly", params, seed.toString()).id,
                        Midend.newGame(Unruly, params, seed.toString()).id(),
                    )
                }
            }
        }

    @Test
    fun `the golden ids are pinned`() {
        assertEquals(
            "8x8:bEACHcBdBDEalcbAbbBa",
            Midend.newGame(Unruly, "8x8dt", "12345").id(),
        )
        assertEquals(
            "8x8:bEACHcBdBDEaleAbbc",
            Midend.newGame(Unruly, "8x8de", "12345").id(),
        )
        assertEquals(
            "8x8:bEACHcBdFEaleAbbc",
            Midend.newGame(Unruly, "8x8dn", "12345").id(),
        )
        assertEquals(
            "8x8u:AjBCcBBeCbhaBCaIbbBaa",
            Midend.newGame(Unruly, "8x8u", "12345").id(),
        )
        assertEquals(
            "8x8u:AjBbFgCbhafIFb",
            Midend.newGame(Unruly, "8x8udn", "12345").id(),
        )
        assertEquals(
            "10x10:CcGDBgbCcbfbhdbBCdEcbkbABdd",
            Midend.newGame(Unruly, "10x10de", "12345").id(),
        )
        assertEquals(
            "10x10:CcGDBgbfbfbhdbBgEabbkbABdd",
            Midend.newGame(Unruly, "10x10dn", "12345").id(),
        )
        assertEquals(
            "14x14:BEdbFBgAAMeehaBhaHAeHAhCFFBCCAdnkDbdAcKDcec",
            Midend.newGame(Unruly, "14x14de", "12345").id(),
        )
        assertEquals(
            "14x14:BEdbFBgAAMeBccbcCddabFfHiCEAFBFejbbkfdAcgDDcec",
            Midend.newGame(Unruly, "14x14dn", "12345").id(),
        )
    }

    @Test
    fun `generated descriptions use the run alphabet without y`() {
        // The encoder flushes a 25-run as `z`/`Z` and restarts the run,
        // so it never writes `y`/`Y`; a hand-written `y` still decodes.
        for (params in Unruly.presets().map { it.params }) {
            for (seed in listOf("12345", "1", "seed")) {
                val desc = Midend.newGame(Unruly, params, seed).desc
                assertTrue(
                    "alphabet of '$desc'",
                    desc.all { it in 'a'..'z' || it in 'A'..'Z' },
                )
                assertTrue("no y in '$desc'", 'y' !in desc)
                assertTrue("no Y in '$desc'", 'Y' !in desc)
            }
        }
    }

    private fun validationError(oracle: OracleClient, params: String): String? =
        try {
            oracle.newGame("unruly", params, SEED)
            null
        } catch (e: OracleException) {
            e.message
        }

    private companion object {
        const val SEED = "12345"
    }
}
