package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: pegs.
 *
 * Parameters, presets, the decode quirks, the validation messages, the
 * short/full type asymmetry, the `5x5random` save hole, and
 * `params + seed -> DESC`.
 *
 * The Cross board uses no RNG, so a description test on Cross alone can
 * pass with a dead generator. Random and Octagon are compared for every
 * seed, and a numeric sweep pins the generator order.
 */
class PegsDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("pegs")
        val actual = Pegs.presets()

        assertEquals("preset count", 9, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // `pegs.c` has no SMALL_SCREEN conditional, so Octagon keeps no
        // size in its title and every Random preset is offered.
        assertEquals("Octagon", actual[5].title)
        assertEquals("7x7octagon", actual[5].params)
        assertEquals("Random 5x5", actual[6].title)
        assertEquals("5x5random", actual[6].params)
    }

    @Test
    fun `the frozen descriptions match the goldens`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/pegs/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("pegs", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals("golden entry count", 10, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            val actual = Midend.newGame(Pegs, params, seed).id()
            assertEquals("golden '$params'", id, actual)
            assertEquals(
                "oracle '$params'",
                oracle.newGame("pegs", params, seed).id,
                actual,
            )
        }
    }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = Pegs.presets().map { it.params }.distinct()
        // The empty seed is a real seed: SHA-1 of the empty string.
        val seeds = listOf(
            "1",
            "2",
            "3",
            "12345",
            "seed",
            "another seed",
            "0",
            "-7",
            "",
        )
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("pegs", params, seed).id,
                    Midend.newGame(Pegs, params, seed).id(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep pins the generator order`() = OracleClient().use { oracle ->
        // Every preset is swept; Cross must stay constant and the two
        // RNG types must follow the oracle.
        for (params in Pegs.presets().map { it.params }.distinct()) {
            for (seed in 0..9) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("pegs", params, seed.toString()).id,
                    Midend.newGame(Pegs, params, seed.toString()).id(),
                )
            }
        }
        // A deeper sweep on the RNG-consuming types, where an off-by-one
        // draw or a retry that reseeds changes the board.
        for (params in listOf("5x5random", "7x7random", "9x9random", "7x7octagon")) {
            for (seed in 10..39) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("pegs", params, seed.toString()).id,
                    Midend.newGame(Pegs, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `Cross ignores the seed and the other types use it`() = OracleClient().use { oracle ->
        for (params in listOf("5x7cross", "7x7cross", "5x9cross", "7x9cross", "9x9cross")) {
            val first = Midend.newGame(Pegs, params, "1").id()
            val second = Midend.newGame(Pegs, params, "987654").id()
            assertEquals("Cross must not use RNG '$params'", first, second)
            assertEquals(
                "Cross oracle '$params'",
                oracle.newGame("pegs", params, "1").id,
                first,
            )
        }
        for (params in listOf("7x7octagon", "5x5random", "7x7random", "9x9random")) {
            assertTrue(
                "$params must use the RNG",
                Midend.newGame(Pegs, params, "1").id() !=
                    Midend.newGame(Pegs, params, "987654").id(),
            )
        }
    }

    @Test
    fun `the octagon hole covers all three classes`() = OracleClient().use { oracle ->
        val params = "7x7octagon"
        val holes = ((1..31) + 12345).associateWith { seed ->
            val desc = Midend.newGame(Pegs, params, seed.toString()).desc
            assertEquals(
                "oracle octagon seed=$seed",
                "7x7:$desc",
                oracle.newGame("pegs", params, seed.toString()).id,
            )
            val index = desc.indexOf('H')
            (index % 7) to (index / 7)
        }

        // Class 0 draws three values; classes 1 and 2 draw two. The
        // literal `3` offsets in the C source assume the 7x7 centre.
        val class0 = setOf(
            4 to 0, 2 to 0, 4 to 6, 2 to 6, 0 to 2, 6 to 2, 0 to 4, 6 to 4,
        )
        val class1 = setOf(5 to 3, 1 to 3, 3 to 5, 3 to 1)
        val class2 = setOf(4 to 3, 2 to 3, 3 to 4, 3 to 2)
        assertTrue("a corner hole", holes.values.any { it in class0 })
        assertTrue("a two-from-centre hole", holes.values.any { it in class1 })
        assertTrue("a one-from-centre hole", holes.values.any { it in class2 })
        assertEquals(4 to 0, holes[1])
        assertEquals(4 to 3, holes[2])
        assertEquals(5 to 3, holes[11])
        assertEquals(3 to 1, holes[12345])
    }

    @Test
    fun `a random retry continues the same stream`() = OracleClient().use { oracle ->
        // The engine test pins that 7x7random seed 1 fails the extent
        // test on its first attempt and that seed 0 needs seven
        // attempts. The descriptions below are therefore the second and
        // seventh attempts: a reseed or a fresh stream on retry would
        // give a different board.
        assertEquals(
            "7x7:OOPOOOOPPPPPOOHPPPOOOPPPPPPOPPHPHPPOOPPPPOOOPPOPO",
            Midend.newGame(Pegs, "7x7random", "1").id(),
        )
        assertEquals(
            "7x7:OOPOOOOPPPPPOOHPPPOOOPPPPPPOPPHPHPPOOPPPPOOOPPOPO",
            oracle.newGame("pegs", "7x7random", "1").id,
        )
        assertEquals(
            "7x7:OOOOPOOOOPPPPOOPHPPPOOPPPPOOOPPPHPPPPPPPOOPPPPPPO",
            Midend.newGame(Pegs, "7x7random", "0").id(),
        )
        assertEquals(
            "7x7:OOOOPOOOOPPPPOOPHPPPOOPPPPOOOPPPHPPPPPPPOOPPPPPPO",
            oracle.newGame("pegs", "7x7random", "0").id,
        )
        // A small board retries too, so the behaviour is not specific
        // to the 7x7 board.
        assertEquals(
            "5x5:OOPOOPPHPPOPPPPOPPPPPHPPP",
            Midend.newGame(Pegs, "5x5random", "0").id(),
        )
        assertEquals(
            "5x5:OOPOOPPHPPOPPPPOPPPPPHPPP",
            oracle.newGame("pegs", "5x5random", "0").id,
        )
    }

    @Test
    fun `decode quirks match the oracle`() = OracleClient().use { oracle ->
        // A valid parameter string: compare the description, which
        // identifies the decoded type because the descriptions differ.
        val accepted = listOf(
            "7x7",
            "7",
            "7x7cross",
            "7x7random",
            "7random",
            "5random",
            "7x7octagon",
            "+7x7",
            "7X7",
            "7x7foo",
            "7x7randomjunk",
            "7x7random ",
            "7x7Cross",
            "7x7 RANDOM",
        )
        for (text in accepted) {
            assertEquals(
                "id for '$text'",
                oracle.newGame("pegs", text, SEED).id,
                Midend.newGame(Pegs, text, SEED).id(),
            )
        }

        // An invalid one: the full message is the same.
        for (text in listOf("7x", "cross", "0x0", "5x5", "4x4cross", "5x5octagon")) {
            val expected = try {
                oracle.newGame("pegs", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "message for '$text'",
                expected,
                Pegs.validateParams(Pegs.decodeParams(text), true),
            )
        }
    }

    @Test
    fun `validation messages match the oracle in full mode`() = OracleClient().use { oracle ->
        val fullCases = listOf(
            "1x1cross",
            "3x3cross",
            "0x0",
            "7x",
            "cross",
            "65536x65536",
            "5x5",
            "4x4cross",
            "6x6cross",
            "5x5octagon",
            "7x9octagon",
            "7x7",
            "7x7random",
            "4x4random",
            "10x10random",
            "9x5cross",
            "7x5cross",
            "9x7cross",
        )
        for (text in fullCases) {
            val expected = try {
                oracle.newGame("pegs", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "full message for '$text'",
                expected,
                Pegs.validateParams(Pegs.decodeParams(text), true),
            )
        }

        // The short form is what the save loader uses for CPARAMS.
        // C checks only the sizes, so these are valid in short mode even
        // though full mode rejects them. The oracle exposes no
        // short-mode validation, so this part is a Kotlin-only pin.
        for (text in listOf("1x1cross", "5x5", "4x4cross", "5x5octagon")) {
            assertEquals(
                "short message for '$text'",
                null,
                Pegs.validateParams(Pegs.decodeParams(text), false),
            )
        }
        assertEquals(
            "Width and height must both be at least one",
            Pegs.validateParams(Pegs.decodeParams("0x5"), false),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Pegs.validateParams(Pegs.decodeParams("65536x65536"), false),
        )
    }

    @Test
    fun `the 5x5random save writes a Cross PARAMS that C rejects`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Pegs, "5x5random", SEED)
            val save = midend.serialise()

            // Upstream writes the full encoding of the short params, so
            // PARAMS loses the board type.
            assertEquals("5x5cross", save.value("PARAMS"))
            assertEquals("5x5random", save.value("CPARAMS"))
            assertEquals(
                "the oracle writes the same long-term parameters",
                "5x5cross",
                SgtpSave.parse(
                    oracle.newGame("pegs", "5x5random", SEED).state,
                ).value("PARAMS"),
            )

            // The short id cannot carry the type either.
            assertTrue(midend.id().startsWith("5x5:"))

            // Upstream cannot reload its own save: PARAMS is validated
            // in full mode and `5x5cross` is not a Cross board.
            try {
                oracle.id(save.serialise())
                throw AssertionError("the oracle reloaded a 5x5random save")
            } catch (e: OracleException) {
                assertEquals(
                    "Long-term parameters in save file are invalid",
                    e.message,
                )
            }
            try {
                Midend.fromSave(Pegs, save)
                throw AssertionError("Kotlin reloaded a 5x5random save")
            } catch (e: IllegalStateException) {
                assertEquals(
                    "long-term parameters: " +
                        "This board type is only supported at 5x7, 5x9, 7x7, 7x9, and 9x9",
                    e.message,
                )
            }

            // The other presets do reload: their PARAMS keeps the type.
            for (params in listOf("5x7cross", "7x7octagon", "9x9random")) {
                val other = Midend.newGame(Pegs, params, SEED)
                val reloaded = Midend.fromSave(Pegs, other.serialise())
                assertEquals("reload '$params'", other.id(), reloaded.id())
            }
        }

    private companion object {
        const val SEED = "12345"
    }
}
