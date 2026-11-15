package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Level: semantic. Game: flip.
 *
 * Parameters, validation, presets, the full/short asymmetry, and the
 * `params + seed -> DESC` matrix.
 *
 * The generator has three sharp edges: the RANDOM matrix draws
 * `4wh-2(w+h)` values per attempt from a tree234 group, an all-zero grid
 * is redrawn, and a table with duplicate rows is rebuilt. The seed 1061
 * vector covers the grid retry; the RANDOM descriptions cover the tree
 * order and the retries.
 */
class FlipDescTest {

    @Test
    fun `parameters decode and encode against the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "5",
            "05x05",
            "+5x5",
            " 5x5",
            "5X5",
            "5x5 ",
            "5x5z",
            "5x5C",
            "5x5c",
            "5x5r",
            "5x5cr",
            "5x5cc",
            "5x5rc",
            "5x5rX",
            "3x3",
            "4x4",
        )

        for (text in cases) {
            assertEquals(
                "error for '$text'",
                null,
                Flip.validateParams(Flip.decodeParams(text), true),
            )
            assertEquals(
                "id '$text'",
                oracle.newGame("flip", text, SEED).id,
                Midend.newGame(Flip, text, SEED).id(),
            )
        }

        // A shape suffix is part of the full parameters only. The short
        // form drops it, so the id of a Random game looks like Crosses.
        assertEquals("3x3r", Flip.encodeParams(Flip.decodeParams("3x3r"), true))
        assertEquals("3x3", Flip.encodeParams(Flip.decodeParams("3x3r"), false))
        val random = Midend.newGame(Flip, "3x3r", SEED)
        assertEquals("3x3c", random.serialise().value("PARAMS"))
        assertEquals("3x3r", random.serialise().value("CPARAMS"))
        assertEquals("3x3:${random.desc}", random.id())

        // An empty params field means the defaults on both sides.
        assertEquals(
            oracle.newGame("flip", "", SEED).id,
            Midend.newGame(Flip, "", SEED).id(),
        )
    }

    @Test
    fun `validation messages match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "0x5",
            "5x0",
            "-1x5",
            "x5",
            "5x",
            "100000x100000",
            "65536x65536",
            "46341x46341",
            "46340x46340",
        )

        for (text in cases) {
            val expected = try {
                oracle.newGame("flip", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "error for '$text'",
                expected,
                Flip.validateParams(Flip.decodeParams(text), true),
            )
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Flip.validateParams(Flip.decodeParams(text), false),
            )
        }

        // The area cap is `floor(sqrt(INT_MAX-3)) = 46340` cells. A
        // one-row board at the cap is valid; one more cell is not. Do
        // not build a state: the matrix alone would need gigabytes.
        assertNull(Flip.validateParams(Flip.decodeParams("1x46340"), true))
        assertEquals(
            "Width times height is too large",
            Flip.validateParams(Flip.decodeParams("1x46341"), true),
        )
    }

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("flip")
        val actual = Flip.presets()

        assertEquals("preset count", 6, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // There is no SMALL_SCREEN branch, so the Android list is the
        // oracle list. The params carry the shape, and the short form
        // would drop it.
        assertEquals("3x3 Crosses", actual[0].title)
        assertEquals("3x3c", actual[0].params)
        assertEquals("5x5 Random", actual[5].title)
        assertEquals("5x5r", actual[5].params)
    }

    @Test
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val paramsList = Flip.presets().map { it.params } + listOf("")
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

            for (params in paramsList) {
                for (seed in seeds) {
                    val expected = oracle.newGame("flip", params, seed)
                    val actual = Midend.newGame(Flip, params, seed)
                    assertEquals("id flip '$params' seed=$seed", expected.id, actual.id())
                    assertEquals(
                        "save flip '$params' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                }
            }
        }

    @Test
    fun `the grid retry and the duplicate row retries match`() =
        OracleClient().use { oracle ->
            // The first nine draws give an all-zero grid; the next nine
            // give this description. Without the retry the grid would
            // be `000` and the puzzle would start solved.
            assertEquals(
                "3x3:d074191345d1644c17058,c00",
                oracle.newGame("flip", "3x3c", "1061").id,
            )
            assertEquals(
                oracle.newGame("flip", "3x3c", "1061").id,
                Midend.newGame(Flip, "3x3c", "1061").id(),
            )
            assertEquals("c00", Midend.newGame(Flip, "3x3c", "1061").id().substringAfterLast(','))

            // Seed 12345: one attempt for 3x3r, five for 4x4r, seven
            // for 5x5r. The id depends on the whole attempt sequence.
            assertEquals(
                oracle.newGame("flip", "3x3r", SEED).id,
                Midend.newGame(Flip, "3x3r", SEED).id(),
            )
            assertEquals(
                oracle.newGame("flip", "4x4r", SEED).id,
                Midend.newGame(Flip, "4x4r", SEED).id(),
            )
            assertEquals(
                oracle.newGame("flip", "5x5r", SEED).id,
                Midend.newGame(Flip, "5x5r", SEED).id(),
            )
            assertEquals(
                "3x3:d0781b134cc12c4c070d8,f38",
                Midend.newGame(Flip, "3x3r", SEED).id(),
            )
            assertEquals(
                "4x4:8c00e80071003300cc00c64006303100088c00e802710113008c00ce00270033,9f17",
                Midend.newGame(Flip, "4x4r", SEED).id(),
            )
            assertEquals(
                "5x5:c600007080000cc000070800018c00062100029c400087000008c2000c2100018c000007200010c4000461000018c00043180001ca000e2000011c200042300018c000047000021c0000470000318,62a7668",
                Midend.newGame(Flip, "5x5r", SEED).id(),
            )

            // The Random matrix is not the Crosses matrix.
            assertNotEquals(
                Midend.newGame(Flip, "3x3c", SEED).id().substringBefore(','),
                Midend.newGame(Flip, "3x3r", SEED).id().substringBefore(','),
            )
        }

    @Test
    fun `the rejected attempts are pinned by the ids`() = OracleClient().use { oracle ->
        // The oracle has no draw-count operation. The exact call counts
        // (9, 16, 25, 33, 256, 585) are asserted in `FlipTest`; the ids
        // here are the oracle's proof that the attempt sequences end at
        // the same matrix and grid. A port that skips a rejected grid or
        // a rejected matrix produces a different id for these seeds.
        val cases = listOf(
            "3x3c" to "3x3:d074191345d1644c17058,c90",
            "4x4c" to "4x4:c800e400720031008c804e402720131008c804e402720131008c004e00270013,a41e",
            "5x5c" to FLIP_5X5C,
            "3x3r" to "3x3:d0781b134cc12c4c070d8,f38",
            "4x4r" to "4x4:8c00e80071003300cc00c64006303100088c00e802710113008c00ce00270033,9f17",
            "5x5r" to FLIP_5X5R,
        )
        for ((params, id) in cases) {
            assertEquals("oracle id $params", id, oracle.newGame("flip", params, SEED).id)
            assertEquals("Kotlin id $params", id, Midend.newGame(Flip, params, SEED).id())
        }
    }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            val sweeps = listOf(
                "3x3c" to 0..29,
                "3x3r" to 0..29,
                "4x4c" to 0..9,
                "4x4r" to 0..9,
                "5x5c" to 0..9,
                "5x5r" to 0..9,
                "2x2c" to 0..9,
                "3x5r" to 0..9,
            )
            for ((params, seeds) in sweeps) {
                for (seed in seeds) {
                    assertEquals(
                        "flip '$params' seed=$seed",
                        oracle.newGame("flip", params, seed.toString()).id,
                        Midend.newGame(Flip, params, seed.toString()).id(),
                    )
                }
            }
        }

    private companion object {
        const val SEED = "12345"
        const val FLIP_5X5C = "5x5:c400007100001c4000071000018400043100011c400047100011c400046100010c400047100011c4000471000118400043100011c400047100011c400046100010c000047000011c0000470000118,16db6f0"
        const val FLIP_5X5R = "5x5:c600007080000cc000070800018c00062100029c400087000008c2000c2100018c000007200010c4000461000018c00043180001ca000e2000011c200042300018c000047000021c0000470000318,62a7668"
    }
}
