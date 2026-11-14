package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic. Game: flood.
 *
 * Parameters, validation messages, presets, and `params + seed -> DESC`.
 *
 * The generator draws exactly `w * h` colours in row-major order and
 * retries only while the grid is uniform, so an extra draw or a retry
 * that reseeds changes every description. The seeds below include the
 * ones the golden uses and a numeric sweep that would expose a different
 * generator order.
 */
class FloodDescTest {

    @Test
    fun `parameters decode and encode against the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "12x12c6m5",
            "12x12",
            "8x8",
            "12x12c3",
            "12x12m0",
            "+12x12c3m0",
            "12X12",
            "12x12c3m0xyz",
            "3x2c10m0",
            "1x2c3m0",
            "2x1c3m0",
        )

        for (text in cases) {
            assertEquals(
                "error for '$text'",
                null,
                Flood.validateParams(Flood.decodeParams(text), true),
            )
            assertEquals(
                "id '$text'",
                oracle.newGame("flood", text, SEED).id,
                Midend.newGame(Flood, text, SEED).id(),
            )
        }

        // An empty string decodes to 0x0, which C rejects. The oracle
        // treats an empty params field as "use the defaults", so this
        // message can only be pinned in the Kotlin test.
        assertEquals(
            "Grid must contain at least two squares",
            Flood.validateParams(Flood.decodeParams(""), true),
        )

        // The short form cannot carry colours or leniency, and the
        // decoder starts from the defaults.
        assertEquals(
            oracle.newGame("flood", "8x8", SEED).id,
            Midend.newGame(Flood, "8x8", SEED).id(),
        )
        assertEquals(
            oracle.newGame("flood", "8x8c6m5", SEED).id,
            Midend.newGame(Flood, "8x8", SEED).id(),
        )
    }

    @Test
    fun `validation messages match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "1x1c3m0",
            "0x5c3m0",
            "5x0c3m0",
            "-2x-2c3m0",
            "3x1431655766c3m0",
            "12x12c2m0",
            "12x12c11m0",
            "12x12c3m-1",
        )

        for (text in cases) {
            val expected = try {
                oracle.newGame("flood", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "error for '$text'",
                expected,
                Flood.validateParams(Flood.decodeParams(text), true),
            )
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Flood.validateParams(Flood.decodeParams(text), false),
            )
        }
    }

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("flood")
        val actual = Flood.presets()

        assertEquals("preset count", 7, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // `SMALL_SCREEN` does not change this game, so the list is the
        // upstream list.
        assertEquals("12x12, 3 colours", actual[5].title)
        assertEquals("12x12c3m0", actual[5].params)
    }

    @Test
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val paramsList = Flood.presets().map { it.params }.distinct()
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

            for (params in paramsList) {
                for (seed in seeds) {
                    val expected = oracle.newGame("flood", params, seed)
                    val actual = Midend.newGame(Flood, params, seed)
                    assertEquals("id flood '$params' seed=$seed", expected.id, actual.id())
                    if (seed.isNotEmpty()) {
                        assertEquals(
                            "save flood '$params' seed=$seed",
                            expected.state,
                            actual.serialise().serialise(),
                        )
                    }
                }
            }
        }

    @Test
    fun `description coverage outside the presets matches the oracle`() =
        OracleClient().use { oracle ->
            val paramsList = listOf(
                "1x2c3m0",
                "2x1c3m0",
                "4x4c10m0",
                "3x2c10m0",
                "2x3c3m0",
                "12x12c6m0",
                "12x12c6m2",
                "16x16c6m0",
                "16x16c6m2",
                "12x12c4m0",
            )
            val seeds = listOf("1", "12345", "seed", "-7", "")

            for (params in paramsList) {
                for (seed in seeds) {
                    assertEquals(
                        "id flood '$params' seed=$seed",
                        oracle.newGame("flood", params, seed).id,
                        Midend.newGame(Flood, params, seed).id(),
                    )
                }
            }
        }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            val sweeps = listOf(
                "12x12c6m5" to 0..9,
                "12x12c3m0" to 0..9,
                "4x4c3m0" to 0..29,
            )
            for ((params, seeds) in sweeps) {
                for (seed in seeds) {
                    assertEquals(
                        "flood '$params' seed=$seed",
                        oracle.newGame("flood", params, seed.toString()).id,
                        Midend.newGame(Flood, params, seed.toString()).id(),
                    )
                }
            }
        }

    private companion object {
        const val SEED = "12345"
    }
}
