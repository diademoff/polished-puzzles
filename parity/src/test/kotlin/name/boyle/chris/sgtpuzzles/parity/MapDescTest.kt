package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: map.
 *
 * The parameters, the six presets, the decode and validation quirks, the
 * `PARAMS`/`CPARAMS` split, `params + seed -> DESC`, `AUXINFO`, and the
 * two random streams.
 *
 * The description tests are the RNG-order guards: the seed placement and
 * weighted extension in `genmap`, one `random_upto` plus one `shuffle`
 * per `fourcolour` node in reverse trial order, and one region shuffle
 * before clue removal. A different order changes every golden.
 */
class MapDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    @Test(timeout = 120_000)
    fun `the presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("map")
        val actual = Map.presets()
        assertEquals(6, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("20x15, 30 regions, Easy", actual[0].title)
        assertEquals("20x15n30de", actual[0].params)
        assertEquals("30x25, 75 regions, Hard", actual[5].title)
        assertEquals("30x25n75dh", actual[5].params)

        val defaults = oracle.newGame("map", "", SEED)
        val midend = Midend.newGame(Map, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals(defaults.state, midend.serialise().serialise())
        assertEquals("20x15n30dn", Map.encodeParams(midend.params, true))
        assertEquals("20x15n30dn", Map.encodeParams(midend.currentParams, true))
        assertEquals("20x15n30", Map.encodeParams(midend.currentParams, false))
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/map/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("map", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 7, entries.size)
            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                val midend = Midend.newGame(Map, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("map", params, seed).id,
                    midend.id(),
                )
            }
        }

    @Test(timeout = 600_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in Map.presets().map { it.params } + "") {
            for (seed in seeds) {
                val expected = oracle.newGame("map", params, seed)
                val midend = Midend.newGame(Map, params, seed)
                assertEquals("seed '$seed' for '$params' id", expected.id, midend.id())
                assertEquals(
                    "seed '$seed' for '$params' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("seed '$seed' for '$params' status", 0, midend.status())
            }
        }
    }

    @Test(timeout = 600_000)
    fun `a numeric seed sweep pins the draw order`() = OracleClient().use { oracle ->
        for (params in Map.presets().map { it.params } + "") {
            for (seed in 0..9) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("map", params, seed.toString()).id,
                    Midend.newGame(Map, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine test proves that these seeds reach the gate, the
        // retry loop, and the difficulty drop.
        val fixtures = listOf(
            "20x15n30dh" to "0",
            "20x15n30du" to "0",
            "30x25n75dh" to "0",
            "5x2n5dh" to "0",
            "5x2n5dh" to "1",
            "5x2n5dh" to "2",
            "5x2n5du" to "3",
        )
        for ((params, seed) in fixtures) {
            assertEquals(
                "retry fixture '$params' seed $seed",
                oracle.newGame("map", params, seed).id,
                Midend.newGame(Map, params, seed).id(),
            )
        }
    }

    @Test(timeout = 120_000)
    fun `the generator state continues the oracle stream`() = OracleClient().use { oracle ->
        for (params in Map.presets().map { it.params } + "") {
            val random = RandomState.fromSeed("stream")
            val effective = if (params.isEmpty()) {
                Map.encodeParams(Map.defaultParams(), false)
            } else {
                params
            }
            Map.newDesc(Map.decodeParams(effective), random)
            val encoded = random.encodeState()
            val expected = oracle.randomUpto(8, 6, state = encoded)
            assertEquals(
                "'$params' stream",
                expected.values,
                (0 until 6).map { random.upto(8) },
            )
            assertEquals("'$params' state", expected.state, random.encodeState())
        }
    }

    @Test(timeout = 300_000)
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "20x15",
            "20x15n37",
            "20x15n30",
            "20x15n30d",
            "20x15n30dz",
            "20x15n30de",
            "20x15n30dn",
            "20x15n30du",
            "20x15n30x",
            "20x15n30d4",
            " 20x15n30",
            "20x15n030",
            "30x25n75dn",
        )
        for (params in accepted) {
            val expected = oracle.newGame("map", params, SEED)
            val midend = Midend.newGame(Map, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        val rejected = listOf(
            "1x1", "1x20", "20x1", "20x15n4", "20x15n0", "20x15n999",
            "20x15n3.5", "-4x5", "2x2", "20x15nabc",
        )
        for (params in rejected) {
            val error = try {
                oracle.newGame("map", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C rejects '$params'", error)
            assertEquals(
                "message for '$params'",
                error,
                Map.validateParams(Map.decodeParams(params), true),
            )
            val kotlinError = try {
                Midend.newGame(Map, params, SEED)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("Kotlin message for '$params'", error, kotlinError)
        }
    }

    @Test(timeout = 120_000)
    fun `the PARAMS and CPARAMS split keeps the requested difficulty`() =
        OracleClient().use { oracle ->
            for (params in listOf(
                "20x15n30de", "20x15n30dn", "20x15n30dh", "20x15n30du",
                "30x25n75dn", "30x25n75dh",
            )) {
                val local = Midend.newGame(Map, params, SEED).serialise()
                val expected = SgtpSave.parse(oracle.newGame("map", params, SEED).state)
                assertEquals("$params PARAMS", expected.value("PARAMS"), local.value("PARAMS"))
                assertEquals("$params CPARAMS", expected.value("CPARAMS"), local.value("CPARAMS"))
                // The long-term parameters are rebuilt from the short
                // form, which decodes to Normal.
                assertEquals(params.substringBefore('d') + "dn", local.value("PARAMS"))
                assertEquals(params, local.value("CPARAMS"))
            }
            // The id has no difficulty.
            assertTrue(Midend.newGame(Map, "20x15n30du", SEED).id().startsWith("20x15n30:"))
            // Easy and Normal produce different descriptions.
            assertFalse(
                Midend.newGame(Map, "20x15n30de", SEED).desc ==
                    Midend.newGame(Map, "20x15n30dn", SEED).desc,
            )
            // The record order and the absence of UI, TIME, PRIVDESC.
            val save = Midend.newGame(Map, "20x15n30de", SEED).serialise()
            assertEquals(
                listOf(
                    "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                    "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
                ),
                save.records.map { it.key },
            )
        }

    @Test(timeout = 600_000)
    fun `the auxiliary answer is the full colouring for every preset`() =
        OracleClient().use { oracle ->
            for (params in Map.presets().map { it.params } + "") {
                for (seed in listOf("12345", "1")) {
                    val expected = oracle.newGame("map", params, seed)
                    val save = SgtpSave.parse(expected.state)
                    val midend = Midend.newGame(Map, params, seed)
                    val aux = requireNotNull(midend.auxInfo)
                    assertTrue("'$params' aux prefix", aux.startsWith("S;"))
                    assertEquals(
                        "'$params' seed $seed AUXINFO",
                        save.value("AUXINFO"),
                        midend.serialise().value("AUXINFO"),
                    )
                    val parts = aux.substring(2).split(';')
                    assertEquals("'$params' aux entries", (midend.params as Map.Params).n, parts.size)
                    for (i in parts.indices) {
                        val (colour, region) = parts[i].split(':').map { it.toInt() }
                        assertEquals(i, region)
                        assertTrue(colour in 0..3)
                    }
                    // The oracle's solve with AUXINFO is the same text.
                    val solved = SgtpSave.parse(oracle.solve(expected.state).state)
                    assertEquals("'$params' solve", aux, solved.value("SOLVE"))
                    // The encoded form is even-length lowercase hex.
                    val hex = save.value("AUXINFO")!!
                    assertTrue(hex.length % 2 == 0)
                    assertTrue(hex.all { it in "0123456789abcdef" })
                }
            }
        }

    @Test(timeout = 120_000)
    fun `the description text seeds the same stream as C`() = OracleClient().use { oracle ->
        // `new_game` calls `random_new(desc, strlen(desc))`. The oracle
        // can encode the initial state of any seed, so this pins the
        // exact seeding step without exposing the geometry.
        val save = oracle.newGame("map", "20x15n30dn", SEED).state
        val desc = SgtpSave.parse(save).value("DESC")!!
        val expected = oracle.randomUpto(8, 0, seed = desc)
        assertEquals(expected.state, RandomState.fromSeed(desc).encodeState())
        assertEquals(emptyList<Long>(), expected.values)
    }

    private companion object {
        const val SEED = "12345"
    }
}
