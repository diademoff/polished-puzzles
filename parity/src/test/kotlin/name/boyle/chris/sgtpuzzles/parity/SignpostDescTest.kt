package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: signpost.
 *
 * Parameters, the six presets, the two encode forms, the decode quirks,
 * the validation order, the description grammar, and `params + seed ->
 * DESC` for the frozen golden rows, every preset, nine named seeds, and a
 * numeric sweep. The sweep is the RNG-order guard: the free-ends pair,
 * the `n-2` fill draws, and the `n-1` shuffle are all observable only in
 * the id. Two retry fixtures pin the fill stream; the strip retry is not
 * reachable for the presets.
 */
class SignpostDescTest {

    private companion object {
        val PARAMS = listOf("4x4", "4x4c", "5x5", "5x5c", "6x6c", "7x7c")
        val SEEDS = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Signpost, params, seed).id()

    private fun full(oracle: OracleClient, params: String): String =
        SgtpSave.parse(oracle.newGame("signpost", params, "12345").state).value("CPARAMS")!!

    private fun saveText(params: String, desc: String): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Signpost.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to "1"
        records += "STATEPOS" to "1"
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    @Test
    fun `presets, the default and the two encodings match the oracle`() =
        OracleClient().use { oracle ->
            val expected = oracle.presets("signpost")
            val actual = Signpost.presets()
            assertEquals(6, expected.size)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals("preset $i title", expected[i].title, actual[i].title)
                assertEquals("preset $i params", expected[i].params, actual[i].params)
            }
            assertEquals(
                listOf("4x4", "4x4, free ends", "5x5", "5x5, free ends", "6x6", "7x7"),
                actual.map { it.title },
            )
            assertEquals(
                listOf("4x4c", "4x4", "5x5c", "5x5", "6x6c", "7x7c"),
                actual.map { it.params },
            )

            // The default is the corner game. The empty parameter string
            // on the new-game path encodes the short form, so it is the
            // free-ends game, as the golden row shows.
            assertEquals("4x4c", Signpost.encodeParams(Signpost.defaultParams(), true))
            assertEquals("4x4", Signpost.encodeParams(Signpost.defaultParams(), false))
            assertEquals(id("4x4", "12345"), id("", "12345"))

            for ((params, corner) in listOf(
                "4x4c" to "4x4c",
                "4x4" to "4x4",
                "5x5c" to "5x5c",
                "5x5" to "5x5",
                "6x6c" to "6x6c",
                "7x7c" to "7x7c",
            )) {
                assertEquals("full '$params'", corner, full(oracle, params))
            }
            // The id never carries the corner flag.
            for (params in PARAMS) {
                assertFalse("'$params' id flags the corner", id(params, "12345").contains("c:"))
            }
        }

    @Test
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/signpost/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("signpost", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(7, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, "12345"))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("signpost", params, "12345").id,
                id(params, "12345"),
            )
        }
    }

    @Test
    fun `every preset and named seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in PARAMS) {
            for (seed in SEEDS) {
                assertEquals(
                    "'$params' seed '$seed'",
                    oracle.newGame("signpost", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator stream`() = OracleClient().use { oracle ->
        for ((params, seeds) in listOf(
            "4x4" to 0..60,
            "4x4c" to 0..60,
            "5x5c" to 0..20,
            "4x5c" to 0..20,
        )) {
            for (seed in seeds) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("signpost", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the retry fixtures are pinned`() = OracleClient().use { oracle ->
        // `4x4` seed 3 needs two fills: the first fill fails, the second
        // succeeds on the same stream. Hoisting the free-ends pair out of
        // the fill loop changes this id.
        val generation = Signpost.newDescWithAttempts(
            Signpost.decodeParams("4x4"),
            name.boyle.chris.sgtpuzzles.engine.RandomState.fromSeed("3"),
        )
        assertEquals(2, generation.fillRetries)
        assertEquals(0, generation.stripRetries)
        assertEquals("4x4:ec16afcad1faaegbhca", id("4x4", "3"))
        assertEquals(
            "oracle retry id",
            oracle.newGame("signpost", "4x4", "3").id,
            id("4x4", "3"),
        )

        // The strip retry is dead for the presets: adding the last number
        // always gives the solver a full board, so every strip succeeds.
        for (params in PARAMS) {
            val p = Signpost.decodeParams(params)
            for (seed in 0..40) {
                val g = Signpost.newDescWithAttempts(
                    p,
                    name.boyle.chris.sgtpuzzles.engine.RandomState.fromSeed(seed.toString()),
                )
                assertEquals("'$params' seed=$seed strip retries", 0, g.stripRetries)
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "4", "4c", "4x4junk", "+4x4", " 4x4", "04x04", "4x4c5",
            "4x4x4", "4X4", "4x4 ", "004x004", "7x7cc", "7x7c0",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("signpost", params, "12345").id,
                id(params, "12345"),
            )
        }

        // One number sets both dimensions; a leading sign or space hides
        // the separator; the flag follows the height digits.
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4"))
        assertEquals(Signpost.Params(4, 4, true), Signpost.decodeParams("4c"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4x4junk"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("+4x4"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams(" 4x4"))
        assertEquals(Signpost.Params(4, -4, false), Signpost.decodeParams("4x-4"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("04x04"))
        assertEquals(Signpost.Params(4, 4, true), Signpost.decodeParams("4x4c5"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4x4x4"))
    }

    @Test
    fun `validation follows the oracle and the C order`() = OracleClient().use { oracle ->
        for (params in listOf(
            "0x4", "4x0", "0x0", "1x1", "-1x4", "4x-1",
            "46341x46341", "65536x65536",
        )) {
            val expected = try {
                oracle.newGame("signpost", params, "1").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Signpost, params, "1")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        // The accepted boundary. Generation at this size is not feasible,
        // so only the validator is asked.
        assertNull(Signpost.validateParams(Signpost.Params(46340, 46340, true), true))
        assertEquals(
            "Width times height must not be unreasonably large",
            Signpost.validateParams(Signpost.Params(46341, 46341, true), true),
        )
        assertEquals(
            "Width and height cannot both be one",
            Signpost.validateParams(Signpost.Params(1, 1, true), true),
        )
        assertNull(Signpost.validateParams(Signpost.Params(1, 1, false), false))
        assertEquals(
            "Width must be at least one",
            Signpost.validateParams(Signpost.Params(0, 0, true), true),
        )
        assertEquals(
            "Height must be at least one",
            Signpost.validateParams(Signpost.Params(4, 0, true), true),
        )
    }

    @Test
    fun `the description grammar agrees with the C loader`() = OracleClient().use { oracle ->
        val blank = "a".repeat(16)
        val cases = listOf(
            "4x4" to blank,
            "4x4" to "0a".repeat(16),
            "4x4" to "16a" + "a".repeat(15),
            "4x4" to "1a" + "a".repeat(14) + "16a",
            "4x4" to "1a".repeat(16),
            "4x4" to "16a".repeat(16),
            "4x4" to "17a" + "a".repeat(15),
            "4x4" to "1a".repeat(16) + "1",
            "4x4" to "a".repeat(15),
            "4x4" to "z" + "a".repeat(15),
            "4x4" to "",
            "4x4" to "1a" + "a".repeat(15),
            "5x5" to "a".repeat(25),
            "5x5" to "1a" + "a".repeat(23) + "25a",
        )
        for ((params, desc) in cases) {
            val message = Signpost.validateDesc(Signpost.decodeParams(params), desc)
            val oracleLoads = try {
                oracle.id(saveText(params, desc))
                true
            } catch (e: OracleException) {
                false
            }
            assertEquals("'$params:$desc' validator and C", oracleLoads, message == null)
            if (message == null) {
                val save = saveText(params, desc)
                val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
                val loaded = oracle.id(save)
                assertEquals("'$params:$desc' id", loaded.id, midend.id())
                assertEquals("'$params:$desc' text", oracle.text(save), midend.textFormat())
                assertEquals("'$params:$desc' status", loaded.status, midend.status())
            }
        }

        // The exact C messages, pinned here because a save load reports
        // only a generic replacement.
        val p = Signpost.decodeParams("4x4")
        assertEquals(
            "Game description shorter than expected",
            Signpost.validateDesc(p, "a".repeat(15)),
        )
        assertEquals(
            "Game description longer than expected",
            Signpost.validateDesc(p, blank + "a"),
        )
        assertEquals("Number out of range", Signpost.validateDesc(p, "17a" + "a".repeat(15)))
        assertEquals(
            "Invalid character in game description",
            Signpost.validateDesc(p, "z" + "a".repeat(15)),
        )
    }

    @Test
    fun `a generated game has no aux text`() = OracleClient().use { oracle ->
        for (params in PARAMS) {
            for (seed in listOf("1", "12345", "another seed", "")) {
                val midend = Midend.newGame(Signpost, params, seed)
                assertNull("'$params' seed '$seed' aux", midend.auxInfo)
                val save = midend.serialise()
                assertNull("'$params' seed '$seed' AUXINFO", save.value("AUXINFO"))
                assertFalse(
                    "'$params' seed '$seed' UI",
                    save.records.any { it.key == "UI" },
                )
                val oracleSave = oracle.newGame("signpost", params, seed)
                assertNull(
                    "'$params' seed '$seed' oracle AUXINFO",
                    SgtpSave.parse(oracleSave.state).value("AUXINFO"),
                )
                assertEquals(
                    "'$params' seed '$seed' save",
                    oracleSave.state,
                    save.serialise(),
                )
            }
        }
        assertNotNull(Midend.newGame(Signpost, "4x4c", "12345").serialise().value("CPARAMS"))
    }
}
