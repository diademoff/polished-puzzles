package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: range.
 *
 * Parameters, presets, the `signed char` decode quirks, validation, and
 * `params + seed -> DESC`.
 *
 * The generator runs two shuffles per attempt and asks the solver for a
 * verdict on every clue removal, so a wrong RNG order or a solver slip
 * changes almost every description. The seeds below cover the golden
 * seed, five more strings, and a numeric sweep.
 */
class RangeDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("range")
        val actual = Range.presets()

        assertEquals("preset count", 4, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // `range.c` has no `SMALL_SCREEN` branch.
        assertEquals(listOf("9 x 6", "12 x 8", "13 x 9", "16 x 11"), actual.map { it.title })
        assertEquals(listOf("9x6", "12x8", "13x9", "16x11"), actual.map { it.params })

        assertEquals("9x6", Range.encodeParams(Range.defaultParams(), true))
        assertEquals("9x6", Range.encodeParams(Range.defaultParams(), false))
    }

    @Test
    fun `the signed char decode matches the oracle`() = OracleClient().use { oracle ->
        // Every accepted row is compared through the full id, so the
        // parameter pair and the description must both match.
        val accepted = listOf(
            "9x6",
            "300x6",
            "+9x6",
            "9X6",
            "9x6x7",
            " 9x6",
            "9x 6",
            "9x+6",
            "9x6 ",
            "9x6junk",
            "3x3",
        )
        for (text in accepted) {
            assertEquals(
                "id for '$text'",
                oracle.newGame("range", text, SEED).id,
                Midend.newGame(Range, text, SEED).id(),
            )
        }

        // The direct decode values, including the truncation.
        assertEquals(Range.Params(44, 6), Range.decodeParams("300x6"))
        assertEquals(Range.Params(9, 9), Range.decodeParams("+9x6"))
        assertEquals(Range.Params(9, 9), Range.decodeParams("9X6"))
        assertEquals(Range.Params(9, 9), Range.decodeParams(" 9x6"))
        assertEquals(Range.Params(9, 6), Range.decodeParams("9x6x7"))
        assertEquals(Range.Params(9, 6), Range.decodeParams("9x 6"))
        assertEquals(Range.Params(9, 6), Range.decodeParams("9x+6"))
        assertEquals(Range.Params(9, 6), Range.decodeParams("9x6 "))
        assertEquals(Range.Params(9, 6), Range.decodeParams("9x6junk"))
        assertEquals(Range.Params(-128, -128), Range.decodeParams("128"))
        assertEquals(Range.Params(0, 0), Range.decodeParams("65536x65536"))

        // The short and the full encoding are the same string because
        // `encode_params` ignores `full`, so `PARAMS == CPARAMS`.
        for (params in listOf("9x6", "300x6", "9x6x7", "3x3")) {
            val p = Range.decodeParams(params)
            assertEquals("full '$params'", Range.encodeParams(p, true), Range.encodeParams(p, false))
        }
    }

    @Test
    fun `validation messages match the oracle in C order`() = OracleClient().use { oracle ->
        val cases = listOf(
            "0x5",
            "5x0",
            "9x",
            "0x9",
            "-9x6",
            "128",
            "65536x65536",
            "127x1",
            "127x2",
            "65x64",
            "1x3",
            "2x3",
            "0x0",
        )
        for (text in cases) {
            val expected = try {
                oracle.newGame("range", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "full error for '$text'",
                expected,
                Range.validateParams(Range.decodeParams(text), true),
            )
        }

        assertEquals("Error: width is less than 1", Range.validateParams(Range.Params(0, 5), true))
        assertEquals("Error: height is less than 1", Range.validateParams(Range.Params(5, 0), true))
        assertEquals(
            "Error: w + h is too big",
            Range.validateParams(Range.Params(127, 2), true),
        )
        assertNull("64x64 is accepted", Range.validateParams(Range.Params(64, 64), true))
        assertEquals(
            "Error: w + h is too big",
            Range.validateParams(Range.Params(65, 64), true),
        )
        assertEquals(
            "Width and height must both be at least 3",
            Range.validateParams(Range.Params(127, 1), true),
        )
        assertNull(
            "a short parameter pair may be 127x1",
            Range.validateParams(Range.Params(127, 1), false),
        )
        assertNull(
            "a game id may be 1x3",
            Range.validateParams(Range.Params(1, 3), false),
        )
        assertEquals(
            "Width and height must both be at least 3",
            Range.validateParams(Range.Params(1, 3), true),
        )
    }

    @Test
    fun `the frozen golden and every seed match the oracle`() = OracleClient().use { oracle ->
        // The frozen golden, entry by entry.
        val golden = Json.parse(
            File(testdata, "golden/range/desc.json").readText(),
        ) as JsonValue.Obj
        val goldenEntries = (golden.field("entries") as JsonValue.Arr).items.map {
            it as JsonValue.Obj
        }
        assertEquals("five golden entries", 5, goldenEntries.size)
        for (entry in goldenEntries) {
            val params = entry.string("params")
            val id = entry.string("id")
            assertEquals("golden '$params'", id, Midend.newGame(Range, params, SEED).id())
            assertEquals("oracle golden '$params'", id, oracle.newGame("range", params, SEED).id)
        }

        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
        for (params in listOf("", "9x6", "12x8", "13x9", "16x11")) {
            for (seed in seeds) {
                val expected = oracle.newGame("range", params, seed)
                val actual = Midend.newGame(Range, params, seed)
                assertEquals("id range '$params' seed=$seed", expected.id, actual.id())
                assertEquals("status '$params' seed=$seed", expected.status, actual.status())
                assertEquals(
                    "state '$params' seed=$seed",
                    expected.state,
                    actual.serialise().serialise(),
                )
            }
        }

        // The five frozen entries, the default twice.
        val entries = listOf(
            "9x6:7_5e4d5g8l7g9d11e8_13",
            "12x8:3a8a3c7a9_4c9o9c6e6l11e8c10o6c12_16a14c13a13a13",
            "13x9:a15g6d14g7d9d13g16e10b7a4b4g9b7a6b12e9g10d13d9g9d6g12a",
            "16x11:b9f8f8c15i2j8k8d9_16a10a18i13c5e11b12b14b4e7c13i8a3a10_5d13k7j3i15c11f7f10b",
            "9x6:7_5e4d5g8l7g9d11e8_13",
        )
        val presets = listOf("", "12x8", "13x9", "16x11", "9x6")
        for (i in entries.indices) {
            assertEquals(entries[i], Midend.newGame(Range, presets[i], "12345").id())
        }
    }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            for (params in listOf("9x6", "12x8")) {
                for (seed in 0..19) {
                    assertEquals(
                        "range '$params' seed=$seed",
                        oracle.newGame("range", params, seed.toString()).id,
                        Midend.newGame(Range, params, seed.toString()).id(),
                    )
                }
            }
            assertEquals(
                "range '16x11' seed=7",
                oracle.newGame("range", "16x11", "7").id,
                Midend.newGame(Range, "16x11", "7").id(),
            )
        }

    @Test
    fun `the retry seeds match the oracle`() = OracleClient().use { oracle ->
        // The engine test pins that these seeds need two attempts. The
        // description is the evidence that the retry draws the same two
        // shuffles as C.
        val cases = listOf("3x3" to "3", "3x5" to "2", "4x4" to "103")
        for ((params, seed) in cases) {
            assertEquals(
                "retry range '$params' seed=$seed",
                oracle.newGame("range", params, seed).id,
                Midend.newGame(Range, params, seed).id(),
            )
        }
        assertEquals(
            "3x3:3_5e5_5",
            Midend.newGame(Range, "3x3", "3").id(),
        )
        assertEquals(
            "3x5:c3b5a7b7c",
            Midend.newGame(Range, "3x5", "2").id(),
        )
    }

    @Test
    fun `descriptions are runs of empty cells and clues`() = OracleClient().use { oracle ->
        for (params in listOf("9x6", "12x8", "3x3")) {
            for (seed in listOf("12345", "1", "seed")) {
                val desc = Midend.newGame(Range, params, seed).desc
                assertTrue(
                    "alphabet of '$desc'",
                    desc.all { it in '0'..'9' || it in 'a'..'z' || it == '_' },
                )
                assertEquals(
                    "desc depth '$params' seed=$seed",
                    oracle.newGame("range", params, seed).id.substringAfter(':'),
                    desc,
                )
            }
        }
    }

    @Test
    fun `newDesc never writes aux info`() {
        for (params in listOf("9x6", "12x8", "13x9", "16x11")) {
            val midend = Midend.newGame(Range, params, SEED)
            assertNull("$params AUXINFO", midend.auxInfo)
            assertNull("$params has no AUXINFO record", midend.serialise().value("AUXINFO"))
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
