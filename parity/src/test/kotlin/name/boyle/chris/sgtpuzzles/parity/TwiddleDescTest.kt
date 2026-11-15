package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: twiddle.
 *
 * Parameters, validation messages, presets, and `params + seed -> DESC`.
 *
 * The generator draws `x`, then `y`, then `r` for every attempt, and a
 * rejected attempt still costs all three draws. A game without a target
 * draws `random_upto(2)` for the total move count before the loop, so a
 * target game and a plain game with the same seed diverge from the first
 * call. The retry fixtures below are the only cheap way to catch a
 * generator that resets the grid, the counters, or the draw order.
 */
class TwiddleDescTest {

    @Test
    fun `parameters decode and encode against the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "",
            "3",
            "3x3",
            "04x04n2",
            "+3x3n2",
            " 3x3n2",
            "3x3n2rroo",
            "3x3n2m0r",
            "3x3n2m10",
            "3x3n2x2",
            "3x3n2n3",
            "3m5x3",
            "3x3n2m0010",
            "3x3n2m+2",
            "3x3n2m0",
        )

        for (text in cases) {
            val expected = oracle.newGame("twiddle", text, SEED)
            val actual = Midend.newGame(Twiddle, text, SEED)
            // An empty parameter string means "use the defaults" in the
            // midend, so decode the effective string, not the empty one.
            val effective = if (text.isEmpty()) {
                Twiddle.encodeParams(Twiddle.defaultParams(), false)
            } else {
                text
            }
            assertEquals("id '$text'", expected.id, actual.id())
            assertNull(
                "validation '$text'",
                Twiddle.validateParams(Twiddle.decodeParams(effective), true),
            )
            // `encode_params` ignores `full`, so the short parameters are
            // the same string and the game id keeps `m`.
            assertEquals(
                "short parameters '$text'",
                Twiddle.encodeParams(Twiddle.decodeParams(effective), true),
                Twiddle.encodeParams(Twiddle.decodeParams(effective), false),
            )
            assertEquals(
                "long parameters '$text'",
                Twiddle.encodeParams(Twiddle.decodeParams(effective), true),
                actual.serialise().value("PARAMS"),
            )
            assertEquals(
                "short parameters '$text'",
                Twiddle.encodeParams(Twiddle.decodeParams(effective), false),
                actual.serialise().value("CPARAMS"),
            )
        }

        // The target is part of the short form.
        val target = Midend.newGame(Twiddle, "3x3n2m10", SEED)
        assertTrue("the id keeps m", target.id().startsWith("3x3n2m10:"))
        assertEquals("3x3n2m10", target.serialise().value("PARAMS"))
        assertEquals("3x3n2m10", target.serialise().value("CPARAMS"))
    }

    @Test
    fun `validation messages match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "3x3n1",
            "2x2n2",
            "1x3n2",
            "3x1n2",
            "3x",
            "x4",
            "0x0",
            "46341x46341n2",
            "100000x100000n2",
            "3x3n2m-1",
        )

        for (text in cases) {
            val expected = try {
                oracle.newGame("twiddle", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "error for '$text'",
                expected,
                Twiddle.validateParams(Twiddle.decodeParams(text), true),
            )
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Twiddle.validateParams(Twiddle.decodeParams(text), false),
            )
        }

        // The largest accepted square board. Do not build a state for it:
        // it needs more memory than the test JVM has.
        assertNull(Twiddle.validateParams(Twiddle.decodeParams("46340x46340n2"), true))
        assertNull(Twiddle.validateParams(Twiddle.decodeParams("3x2n2"), false))
        assertNull(Twiddle.validateParams(Twiddle.decodeParams("2x3n2"), false))
    }

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("twiddle")
        val actual = Twiddle.presets()

        assertEquals("preset count", 8, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // There is no SMALL_SCREEN branch, so the Android list is the
        // desktop list, and the titles are exact.
        assertEquals("3x3 rows only", actual[0].title)
        assertEquals("4x4, rotating 3x3 blocks", actual[5].title)
        assertEquals("6x6, rotating 4x4 blocks", actual[7].title)
    }

    @Test
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val paramsList = Twiddle.presets().map { it.params } +
                listOf(
                    "",
                    "3x3n2m10",
                    "3x3n2m1",
                    "3x3n2m0r",
                    "5x5n3o",
                    "3x2n2",
                    "2x3n2",
                    "2x2n2m4",
                    "3x3n3",
                    "3x3n2r",
                )
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

            for (params in paramsList) {
                for (seed in seeds) {
                    val expected = oracle.newGame("twiddle", params, seed)
                    val actual = Midend.newGame(Twiddle, params, seed)
                    assertEquals("id twiddle '$params' seed=$seed", expected.id, actual.id())
                    assertEquals(
                        "save twiddle '$params' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                }
            }
        }

    @Test
    fun `the retry fixtures match the oracle exactly`() = OracleClient().use { oracle ->
        val fixtures = listOf(
            Triple("2x2n2m4", "1", "2x2n2m4:4,3,2,1"),
            Triple("2x2n2m2", "2", "2x2n2m2:4,3,2,1"),
            Triple("3x3n3", "1", "3x3n3:9,8,7,6,5,4,3,2,1"),
            Triple("3x3n2r", "15", "3x3n2r:1,2,3,3,1,3,2,2,1"),
        )

        for ((params, seed, id) in fixtures) {
            assertEquals("C id for '$params' seed=$seed", id, oracle.newGame("twiddle", params, seed).id)
            assertEquals("Kotlin id for '$params' seed=$seed", id, Midend.newGame(Twiddle, params, seed).id())
        }

        // The second pass keeps the grid and reuses the move count, so a
        // generator that resets the board or draws a new count fails on
        // these literals. The move count itself is not in the id; the
        // status text of the same game pins it.
        assertEquals("Moves: 0 (target 2)", Midend.newGame(Twiddle, "2x2n2m2", "2").statusText())

        // A one-pass generator would agree on the easy seeds but not on
        // the retry seeds. These neighbours are the guard.
        val rowDesc = Midend.newGame(Twiddle, "3x3n2r", "15").id()
        assertNotEquals(oracle.newGame("twiddle", "3x3n2r", "14").id, rowDesc)
        assertNotEquals(oracle.newGame("twiddle", "3x3n2r", "16").id, rowDesc)
    }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() = OracleClient().use { oracle ->
        val sweeps = listOf(
            "3x3n2" to 0..29,
            "3x3n2o" to 0..9,
            "3x3n2r" to 0..9,
            "2x2n2m4" to 0..9,
            "2x2n2" to 0..9,
            "3x3n3" to 0..9,
            "4x4n3" to 0..9,
            "5x5n3o" to 0..4,
            "3x2n2" to 0..9,
        )
        for ((params, seeds) in sweeps) {
            for (seed in seeds) {
                assertEquals(
                    "twiddle '$params' seed=$seed",
                    oracle.newGame("twiddle", params, seed.toString()).id,
                    Midend.newGame(Twiddle, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `orientable and rowsonly descriptions keep the C shape`() = OracleClient().use { oracle ->
        val rows = oracle.newGame("twiddle", "3x3n2r", SEED)
        val oriented = oracle.newGame("twiddle", "3x3n2o", SEED)

        // `rowsonly` repeats numbers, and `orientation` appends a letter
        // with no separator.
        assertTrue(rows.id.substringAfter(':').split(',').toSet().size < 9)
        assertTrue(oriented.id.substringAfter(':').matches(Regex("([0-9]+[uldr])+")))
        assertTrue(rows.id.substringAfter(':').matches(Regex("[0-9]+(,[0-9]+)*")))

        assertEquals(rows.id, Midend.newGame(Twiddle, "3x3n2r", SEED).id())
        assertEquals(oriented.id, Midend.newGame(Twiddle, "3x3n2o", SEED).id())

        // No AUXINFO is ever produced.
        assertNull(Midend.newGame(Twiddle, "3x3n2", SEED).serialise().value("AUXINFO"))
    }

    private companion object {
        const val SEED = "12345"
    }
}
