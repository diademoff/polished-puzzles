package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SlantGeneration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic. Game: slant.
 *
 * The generator removes clues with a solver, so any difference in the
 * solver changes the description even when the filled grid is the same.
 * The oracle is the only source of truth for both.
 */
class SlantDescTest {

    @Test
    fun `descriptions match the oracle`() = OracleClient().use { oracle ->
        val paramsList = listOf(
            SlantGeneration.Params(2, 2, SlantGeneration.EASY),
            SlantGeneration.Params(4, 4, SlantGeneration.EASY),
            SlantGeneration.Params(4, 4, SlantGeneration.HARD),
            SlantGeneration.Params(5, 5, SlantGeneration.EASY),
            SlantGeneration.Params(5, 5, SlantGeneration.HARD),
            SlantGeneration.Params(6, 4, SlantGeneration.HARD),
            SlantGeneration.Params(8, 8, SlantGeneration.EASY),
            SlantGeneration.Params(8, 8, SlantGeneration.HARD),
            SlantGeneration.Params(12, 10, SlantGeneration.EASY),
            SlantGeneration.Params(12, 10, SlantGeneration.HARD),
        )
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

        for (params in paramsList) {
            for (seed in seeds) {
                val text = SlantGeneration.encode(params, true)
                val expected = oracle.newGame("slant", text, seed).id
                val desc = SlantGeneration.newDesc(params, RandomState.fromSeed(seed)).text
                val actual = SlantGeneration.encode(params, false) + ":" + desc
                assertEquals("slant '$text' seed=$seed", expected, actual)
            }
        }
    }

    @Test
    fun `parameters round-trip and validate`() {
        for (params in listOf(
            SlantGeneration.Params(5, 5, SlantGeneration.EASY),
            SlantGeneration.Params(12, 10, SlantGeneration.HARD),
        )) {
            val full = SlantGeneration.encode(params, true)
            assertEquals(params, SlantGeneration.decode(full))
            assertEquals(full, SlantGeneration.encode(SlantGeneration.decode(full), true))
            assertEquals(null, SlantGeneration.validateParams(params, true))
        }
        assertEquals(
            "Width and height must both be at least two",
            SlantGeneration.validateParams(SlantGeneration.Params(1, 5, 0), true),
        )
    }
}
