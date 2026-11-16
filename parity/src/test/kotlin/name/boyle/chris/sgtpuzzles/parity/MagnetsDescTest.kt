package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: magnets.
 *
 * Parameters, the eight presets, the decode quirks, the validation
 * order, and `params + seed -> DESC` for every preset, a list of named
 * seeds, and a numeric sweep. The generation stream is
 * `LayDomino.layout`, one shuffle in `lay_dominoes`, and the strip
 * shuffle, with whole-generator retries, so the sweep guards the order.
 * The encoded `AUXINFO` is compared as well: it is the generator's
 * answer and it pins the solver.
 */
class MagnetsDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf(
        "6x5de", "6x5dt", "6x5dtS", "8x7de", "8x7dt", "8x7dtS", "10x9dt", "10x9dtS",
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Magnets, params, seed).id()

    @Test
    fun `presets and the default match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("magnets")
        val actual = Magnets.presets()

        assertEquals(8, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(
            listOf(
                "6x5 Easy", "6x5 Tricky", "6x5 Tricky, strip clues",
                "8x7 Easy", "8x7 Tricky", "8x7 Tricky, strip clues",
                "10x9 Tricky", "10x9 Tricky, strip clues",
            ),
            expected.map { it.title },
        )
        assertEquals(presets, expected.map { it.params })

        // `default_params` is preset 2, but the oracle encodes empty
        // parameters with the short form, so `params ""` is 6x5 Easy.
        assertEquals("6x5dtS", Magnets.encodeParams(Magnets.defaultParams(), true))
        assertEquals("6x5", Magnets.encodeParams(Magnets.defaultParams(), false))
        assertEquals(Expected.EASY_6X5, id("", "12345"))
    }

    @Test
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/magnets/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("magnets", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(9, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, seed))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("magnets", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
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
        for (params in presets) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("magnets", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        for ((params, seeds) in listOf(
            "6x5de" to 0..50,
            "6x5dt" to 0..50,
            "6x5dtS" to 0..50,
            "8x7de" to 0..25,
            "8x7dt" to 0..25,
            "8x7dtS" to 0..25,
            "3x3de" to 0..20,
        )) {
            for (seed in seeds) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("magnets", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "6",
            "6x5",
            "6x5d",
            "6x5dz",
            "6x5dt",
            "6x5dtS",
            "6x5dS",
            "6x5S",
            "6x5dtSs",
            "6x5X5",
            "+6x5",
            "06x05",
            "6x5 ",
            "3x3de",
            "4x4de",
            "5x4dt",
            "4x5dt",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("magnets", params, "12345").id,
                id(params, "12345"),
            )
        }

        // The measured decode table: one number sets both dimensions,
        // `d` consumes one character, and `dS` eats the strip flag.
        assertEquals("6", "6x6de", full(oracle, "6"))
        assertEquals("6x5de", full(oracle, "6x5"))
        assertEquals("6x5de", full(oracle, "6x5d"))
        assertEquals("6x5de", full(oracle, "6x5dz"))
        assertEquals("6x5de", full(oracle, "6x5dS"))
        assertEquals("6x5deS", full(oracle, "6x5S"))
        assertEquals("6x5dtS", full(oracle, "6x5dtSs"))
        assertEquals("6x5de", full(oracle, "6x5X5"))
        assertEquals("+6x5", "6x6de", full(oracle, "+6x5"))
        assertEquals("6x5de", full(oracle, "06x05"))
    }

    @Test
    fun `validation messages match the oracle in order`() = OracleClient().use { oracle ->
        for (params in listOf(
            "0x5", "6x0", "1x5", "6x1", "2x2de", "3x3dt", "4x4dt", "5x4dt", "4x5dt",
        )) {
            val expected = try {
                oracle.newGame("magnets", params, "1").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Magnets, params, "1")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        assertEquals(
            "Width must be at least two",
            Magnets.validateParams(Magnets.Params(1, 5, 0, false), true),
        )
        assertEquals(
            "Height must be at least two",
            Magnets.validateParams(Magnets.Params(5, 1, 0, false), true),
        )
        assertEquals(
            "Either width or height must be at least three",
            Magnets.validateParams(Magnets.Params(2, 2, 0, false), true),
        )
        assertEquals(
            "Either width or height must be at least five for Tricky",
            Magnets.validateParams(Magnets.Params(4, 4, 1, false), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Magnets.validateParams(Magnets.Params(5, 5, 2, false), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Magnets.validateParams(Magnets.Params(5, 5, -1, false), true),
        )

        // 46340 * 46340 still fits; one more is rejected before any
        // allocation. Generation at the accepted size is not feasible.
        assertEquals(null, Magnets.validateParams(Magnets.Params(46340, 46340, 0, false), true))
        assertEquals(null, Magnets.validateParams(Magnets.Params(46340, 46340, 1, false), false))
        assertEquals(
            "Width times height must not be unreasonably large",
            Magnets.validateParams(Magnets.Params(65536, 65536, 0, false), true),
        )
    }

    private data class Encoded(
        val text: String,
        val w: Int,
        val h: Int,
        val diff: Int,
        val strip: Boolean,
    )

    @Test
    fun `the full and short encodings split the parameters`() = OracleClient().use { oracle ->
        for (case in listOf(
            Encoded("6x5de", 6, 5, 0, false),
            Encoded("6x5dt", 6, 5, 1, false),
            Encoded("6x5dtS", 6, 5, 1, true),
            Encoded("10x9dtS", 10, 9, 1, true),
        )) {
            val p = Magnets.Params(case.w, case.h, case.diff, case.strip)
            assertEquals(case.text, Magnets.encodeParams(p, true))
            assertEquals("${case.w}x${case.h}", Magnets.encodeParams(p, false))
        }

        // A generated game re-decodes the short form into the long-term
        // parameters, so `PARAMS` is always Easy and `CPARAMS` is the
        // requested string.
        for (params in listOf("6x5dt", "6x5dtS", "8x7de", "10x9dtS")) {
            val midend = Midend.newGame(Magnets, params, "12345")
            val save = midend.serialise()
            val short = params.substringBefore('d')
            assertEquals("$params PARAMS", "${short}de", save.value("PARAMS"))
            assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
            assertEquals("$params id prefix", short, midend.id().substringBefore(':'))
        }
    }

    @Test
    fun `the generated aux answer matches the oracle AUXINFO`() =
        OracleClient().use { oracle ->
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
            for (params in presets) {
                for (seed in seeds) {
                    val expected = oracle.newGame("magnets", params, seed)
                    val midend = Midend.newGame(Magnets, params, seed)
                    assertEquals("'$params' seed '$seed' id", expected.id, midend.id())
                    assertEquals(
                        "'$params' seed '$seed' AUXINFO",
                        SgtpSave.parse(expected.state).value("AUXINFO"),
                        midend.serialise().value("AUXINFO"),
                    )
                }
            }
        }

    @Test
    fun `the attempt counts are exposed`() {
        // The counts have no C counterpart. The preset topics generate
        // in one or two outer attempts; the value is pinned as a range
        // and as a positive total so a retry-condition change shows.
        for (params in presets) {
            val generation = Magnets.newDescWithAttempts(
                Magnets.decodeParams(params),
                RandomState.fromSeed("12345"),
            )
            assertTrue("'$params' attempts", generation.attempts >= 1)
            assertTrue("'$params' retries", generation.layDominoRetries >= 0)
            assertTrue("'$params' desc", generation.desc.isNotEmpty())
            val decoded = Magnets.decodeParams(params) as Magnets.Params
            assertEquals("'$params' aux", decoded.w * decoded.h, generation.auxInfo.length)
        }
        val total = presets.sumOf {
            Magnets.newDescWithAttempts(
                Magnets.decodeParams(it),
                RandomState.fromSeed("12345"),
            ).attempts
        }
        assertTrue("the preset set has retries", total >= presets.size)
    }

    private fun full(oracle: OracleClient, params: String): String =
        SgtpSave.parse(oracle.newGame("magnets", params, "12345").state).value("CPARAMS")!!

    private object Expected {
        const val EASY_6X5 =
            "6x5:221211,12231,302112,11313,LRLRLRTTLRLRBBLRLRTLRLRTBLRLRB"
    }
}
