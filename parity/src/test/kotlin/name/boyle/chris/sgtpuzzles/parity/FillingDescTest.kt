package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: filling.
 *
 * Parameters, validation, presets, and `params + seed -> DESC`.
 *
 * The generator calls the deduction solver hundreds of times, and the
 * solver decides which clues are removed. A wrong RNG order or a solver
 * slip changes almost every description: the seeds below cover the
 * golden seed, four more strings, and a numeric sweep.
 */
class FillingDescTest {

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("filling")
        val actual = Filling.presets()

        assertEquals("preset count", 3, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // `SMALL_SCREEN` does not change this game.
        assertEquals(listOf("9x7", "13x9", "17x13"), actual.map { it.title })
        assertEquals(listOf("9x7", "13x9", "17x13"), actual.map { it.params })

        assertEquals(13, (Filling.defaultParams() as Filling.Params).width)
        assertEquals(9, (Filling.defaultParams() as Filling.Params).height)
        assertEquals("13x9", Filling.encodeParams(Filling.defaultParams(), true))
        assertEquals("13x9", Filling.encodeParams(Filling.defaultParams(), false))
    }

    @Test
    fun `parameters decode and encode against the oracle`() = OracleClient().use { oracle ->
        // Every row is a real quirk of `decode_params`. The id carries
        // the accepted parameter pair, so it is the evidence.
        val cases = listOf(
            "13x9",
            "9x7",
            "17x13",
            "13",
            " 13x9",
            "+13x9",
            "13X9",
            "13x9junk",
            "13x 9",
            "13x+9",
            "1x1",
            "2x2",
        )
        for (text in cases) {
            assertEquals(
                "decode error for '$text'",
                null,
                Filling.validateParams(Filling.decodeParams(text), true),
            )
            assertEquals(
                "id '$text'",
                oracle.newGame("filling", text, SEED).id,
                Midend.newGame(Filling, text, SEED).id(),
            )
        }

        // `13` and ` 13x9` decode to the same 13x13 pair.
        assertEquals(
            oracle.newGame("filling", "13", SEED).id,
            oracle.newGame("filling", " 13x9", SEED).id,
        )
        assertEquals(
            Midend.newGame(Filling, "13", SEED).id(),
            Midend.newGame(Filling, " 13x9", SEED).id(),
        )

        // The short form is the full form, because `full` is not read.
        assertEquals(
            oracle.newGame("filling", "", SEED).id,
            Midend.newGame(Filling, "", SEED).id(),
        )
        assertEquals(
            "13x9",
            Filling.encodeParams(Filling.decodeParams("13x9"), true),
        )
        assertEquals(
            "13x9",
            Filling.encodeParams(Filling.decodeParams("13x9"), false),
        )
    }

    @Test
    fun `validation messages match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "0x5",
            "5x0",
            "13x",
            "0x0",
            "13x-9",
            "46341x46341",
        )
        for (text in cases) {
            val expected = try {
                oracle.newGame("filling", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "error for '$text'",
                expected,
                Filling.validateParams(Filling.decodeParams(text), true),
            )
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Filling.validateParams(Filling.decodeParams(text), false),
            )
        }

        // The messages, in C order.
        assertEquals(
            "Width must be at least one",
            Filling.validateParams(Filling.Params(0, 5), true),
        )
        assertEquals(
            "Height must be at least one",
            Filling.validateParams(Filling.Params(5, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Filling.validateParams(Filling.Params(46341, 46341), true),
        )
        assertNull(Filling.validateParams(Filling.Params(46340, 46340), true))

        // An empty parameter string means "use the defaults" to a
        // midend, so validation of the raw 0x0 pair is engine only.
        assertEquals(
            "Width must be at least one",
            Filling.validateParams(Filling.decodeParams(""), true),
        )
    }

    @Test
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
            for (params in listOf("9x7", "13x9", "17x13")) {
                for (seed in seeds) {
                    val expected = oracle.newGame("filling", params, seed)
                    val actual = Midend.newGame(Filling, params, seed)
                    assertEquals("id filling '$params' seed=$seed", expected.id, actual.id())
                    assertEquals(
                        "status filling '$params' seed=$seed",
                        expected.status,
                        actual.status(),
                    )
                    assertEquals(
                        "state filling '$params' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                }
            }
        }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            for (params in listOf("9x7", "13x9")) {
                for (seed in 0..19) {
                    assertEquals(
                        "filling '$params' seed=$seed",
                        oracle.newGame("filling", params, seed.toString()).id,
                        Midend.newGame(Filling, params, seed.toString()).id(),
                    )
                }
            }
            // One extra 17x13 generation at a non-golden seed.
            assertEquals(
                "filling '17x13' seed=7",
                oracle.newGame("filling", "17x13", "7").id,
                Midend.newGame(Filling, "17x13", "7").id(),
            )
        }

    @Test
    fun `the smallest board walks the whole generator`() =
        OracleClient().use { oracle ->
            val expected = oracle.newGame("filling", "1x1", SEED)
            val actual = Midend.newGame(Filling, "1x1", SEED)
            assertEquals("1x1:a", expected.id)
            assertEquals(expected.id, actual.id())
            assertEquals(64, expected.width)
            assertEquals(64, expected.height)
            assertEquals(expected.state, actual.serialise().serialise())
            assertEquals("+---+\n|   |\n+---+\n", actual.textFormat())
        }

    @Test
    fun `generated descriptions only use digits and run letters`() =
        OracleClient().use { oracle ->
            for (params in listOf("9x7", "13x9", "17x13")) {
                for (seed in listOf("12345", "1", "seed")) {
                    val desc = Midend.newGame(Filling, params, seed).desc
                    assertTrue(
                        "alphabet of '$desc'",
                        desc.all { it in '1'..'9' || it in 'a'..'z' },
                    )
                    assertEquals(
                        "desc depth '$params' seed=$seed",
                        oracle.newGame("filling", params, seed).id.substringAfter(':'),
                        desc,
                    )
                }
            }
        }

    /**
     * The generated ids are a pure function of the seed. The default id
     * is the golden value; the other two come from the same oracle and
     * pin the seed sweep.
     */
    @Test
    fun `three fixed ids are pinned`() {
        assertEquals(
            "13x9:a4a7f3c4a7a4a49b3c7a6b5a9a5b8a5a6c9b566c3b2b6a66c2a4c4b869992a424e999a33a4a9c99b3e",
            Midend.newGame(Filling, "13x9", "12345").id(),
        )
        assertEquals(
            "9x7:a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55",
            Midend.newGame(Filling, "9x7", "12345").id(),
        )
        assertEquals(
            "17x13:b3a2b83b2c3b4d2a8b6c55a5363a8e6b5c2d8c47a4a557a7c2a3526a9a3b3d4e9a33b74l9b3b77b5a99a5b43a5b764a39b93b85a7a7993a995g2f9b2a2d3b4c5a5b4a84b39446b2a34a",
            Midend.newGame(Filling, "17x13", "12345").id(),
        )
    }

    private companion object {
        const val SEED = "12345"
    }
}
