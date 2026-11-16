package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: pattern.
 *
 * Parameters, the five presets, the decode quirks, the validation order,
 * the description grammar and its suffix, and `params + seed -> DESC`
 * for every preset, a list of named seeds, and a numeric sweep. The
 * generation stream is `w*h` draws at limit 100000000 plus one draw only
 * for an odd axis pair, one single-precision pass, and whole-generator
 * retries, so the sweep guards the order. The generated `AUXINFO` is
 * compared as well: it is the generator's answer and it pins the solver.
 */
class PatternDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf("10x10", "15x15", "20x20", "25x25", "30x30")

    private fun id(params: String, seed: String): String =
        Midend.newGame(Pattern, params, seed).id()

    @Test
    fun `presets and the default match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("pattern")
        val actual = Pattern.presets()

        assertEquals(5, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(presets, expected.map { it.params })
        assertEquals(presets, expected.map { it.title })

        // `default_params` is 15x15, so the empty parameter string is the
        // same game as `15x15`. The short and the full encodings agree.
        assertEquals("15x15", Pattern.encodeParams(Pattern.defaultParams(), false))
        assertEquals("15x15", Pattern.encodeParams(Pattern.defaultParams(), true))
        assertEquals(id("15x15", "12345"), id("", "12345"))
        assertEquals("15x15", id("", "12345").substringBefore(':'))
    }

    @Test
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/pattern/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("pattern", golden.string("game"))
        val seed = golden.string("seed")
        assertEquals("12345", seed)
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(6, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expectedId = entry.string("id")
            assertEquals("golden '$params'", expectedId, id(params, seed))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("pattern", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
        for (params in presets) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("pattern", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        val cases = listOf(
            "10x10" to 0..20,
            "15x15" to 0..8,
            "1x2" to 0..10,
            "2x1" to 0..10,
            "2x2" to 0..10,
            "2x3" to 0..10,
            "3x2" to 0..10,
            "3x3" to 0..10,
            "4x4" to 0..10,
            "5x5" to 0..10,
        )
        for ((params, seeds) in cases) {
            for (seed in seeds) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("pattern", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "6", "6x5", "6X5", "+6x5", "06x05", "4x4x4", "4X4", " 4x4", "4x4 ",
            "4x4de", "4x4x5", "004x004",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("pattern", params, "12345").id,
                id(params, "12345"),
            )
        }

        // One number sets both dimensions; a leading sign or space hides
        // the separator, so the board is square.
        assertEquals("6x6", full(oracle, "6"))
        assertEquals("6x6", full(oracle, "+6x5"))
        assertEquals("4x4", full(oracle, "4X4"))
        assertEquals("6x5", full(oracle, "06x05"))
    }

    @Test
    fun `validation messages match the oracle in order`() = OracleClient().use { oracle ->
        for (params in listOf(
            "0x5", "5x0", "0x0", "1x1", "100000x100000",
            "46341x46341", "46342x46340", "2147483647x1", "1x2147483647",
        )) {
            val expected = try {
                oracle.newGame("pattern", params, "1").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Pattern, params, "1")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        // The accepted boundary. Generation at this size is not feasible,
        // so only the validator is asked.
        assertNull(Pattern.validateParams(Pattern.Params(46341, 46340), true))
        assertNull(Pattern.validateParams(Pattern.Params(46340, 46340), true))
        assertEquals(
            "Puzzle must not be unreasonably large",
            Pattern.validateParams(Pattern.Params(46341, 46341), true),
        )
        assertEquals(
            "Grid must contain at least two squares",
            Pattern.validateParams(Pattern.Params(1, 1), true),
        )
        assertEquals(
            "Width and height must both be greater than zero",
            Pattern.validateParams(Pattern.Params(0, 5), true),
        )
    }

    @Test
    fun `the description grammar and suffix agree with the C load path`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                "2x2" to "1/1//2",
                "2x2" to "1/1//2,aaAA",
                "2x2" to "1/1//2,aaaa",
                "2x2" to "1/1//2,z",
                "2x2" to "1/1//2,",
                "2x2" to "1/1//2,aaa",
                "2x2" to "1/1//2,aaaaa",
                "2x2" to "1/1//2,a1",
                "2x2" to "1/1//2/",
                "2x2" to "1/1//2x",
                "2x2" to "0/1//2",
                "2x2" to "1..2/1//2",
                "2x2" to "2.2/1//2",
                "2x2" to "1/1/2.2/2",
                "2x2" to "1/1/2",
                "2x2" to "",
                "1x2" to "1//1",
                "1x5" to "2///1/1/",
                "1x5" to "2///1/1",
                "4x4" to "2/1/2/1/1.1/2/1/1",
                "4x4" to "2/1/2/1/1.1/2/1/1,abAB",
            )
            for ((params, desc) in cases) {
                val p = Pattern.decodeParams(params)
                val message = Pattern.validateDesc(p, desc)
                val oracleLoads = try {
                    oracle.id(saveText(params, desc))
                    true
                } catch (e: OracleException) {
                    false
                }
                assertEquals(
                    "'$params:$desc' validator and C",
                    oracleLoads,
                    message == null,
                )
                if (message == null) {
                    // The loader agrees with the validator.
                    Midend.fromGameId(Pattern, "$params:$desc")
                }
            }

            // A suffix fills and locks the cells that the C loader sees.
            val state = Midend.fromGameId(Pattern, "2x2:1/1//2,aaAA").state as Pattern.State
            assertTrue(state.common.immutable.all { it })
            assertEquals(Pattern.GRID_EMPTY, state.grid[0])
            assertEquals(Pattern.GRID_FULL, state.grid[3])
        }

    @Test
    fun `the generated aux answer matches the oracle AUXINFO`() =
        OracleClient().use { oracle ->
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
            for (params in presets) {
                for (seed in seeds) {
                    val expected = oracle.newGame("pattern", params, seed)
                    val midend = Midend.newGame(Pattern, params, seed)
                    assertEquals("'$params' seed '$seed' id", expected.id, midend.id())
                    assertEquals(
                        "'$params' seed '$seed' AUXINFO",
                        SgtpSave.parse(expected.state).value("AUXINFO"),
                        midend.serialise().value("AUXINFO"),
                    )
                    assertNotNull(midend.auxInfo)
                    val p = Pattern.decodeParams(params) as Pattern.Params
                    assertEquals(
                        "'$params' seed '$seed' aux length",
                        2 * (p.w * p.h + 1),
                        SgtpSave.parse(expected.state).value("AUXINFO")!!.length,
                    )
                }
            }
        }

    @Test
    fun `the attempt counts are the measured values`() {
        // The counters have no C counterpart, so the measured values are
        // the fixture. The engine test pins the small sizes; these are
        // the presets next to the oracle sweep.
        for ((params, attempts) in listOf(
            "10x10" to 4,
            "15x15" to 1,
            "20x20" to 2,
            "25x25" to 6,
            "30x30" to 13,
        )) {
            val generation = Pattern.newDescWithAttempts(
                Pattern.decodeParams(params),
                RandomState.fromSeed("12345"),
            )
            assertEquals("'$params' attempts", attempts, generation.attempts)
            val p = Pattern.decodeParams(params) as Pattern.Params
            assertEquals("'$params' aux", p.w * p.h + 1, generation.auxInfo.length)
            assertTrue("'$params' desc", generation.desc.isNotEmpty())
        }
    }

    /** The full parameter encoding that the oracle reports in `CPARAMS`. */
    private fun full(oracle: OracleClient, params: String): String =
        SgtpSave.parse(oracle.newGame("pattern", params, "12345").state).value("CPARAMS")!!

    private fun saveText(params: String, desc: String): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Pattern.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to "1"
        records += "STATEPOS" to "1"
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }
}
