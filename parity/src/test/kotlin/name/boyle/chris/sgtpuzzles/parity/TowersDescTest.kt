package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Towers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: towers.
 *
 * The parameters, the seven presets, the decode and validation quirks,
 * `params + seed -> DESC`, `AUXINFO`, the retry fixtures, and the three
 * by three difficulty drop.
 *
 * The description tests are the RNG and solver order guards: one Latin
 * generation, the first order shuffle, the Easy-only empty-grid
 * pre-check before either shuffle, the second order shuffle above Easy,
 * and the exact final gate. A different draw order, a per-attempt solver
 * context, or a tidied cube index changes every id.
 */
class TowersDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    private fun handSave(desc: String, params: String = "3de"): String {
        val records = listOf(
            "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection",
            "VERSION" to "1",
            "GAME" to "Towers",
            "PARAMS" to params,
            "CPARAMS" to params,
            "SEED" to "1",
            "DESC" to desc,
            "NSTATES" to "1",
            "STATEPOS" to "1",
        )
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    @Test(timeout = 120_000)
    fun `the presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("towers")
        val actual = Towers.presets()
        assertEquals(7, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("4x4 Easy", actual[0].title)
        assertEquals("6x6 Unreasonable", actual[6].title)

        val defaults = oracle.newGame("towers", "", SEED)
        val midend = Midend.newGame(Towers, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals(defaults.state, midend.serialise().serialise())
        assertEquals("5de", Towers.encodeParams(midend.params, true))
        assertEquals("5de", Towers.encodeParams(midend.currentParams, true))
        assertEquals("5", Towers.encodeParams(midend.currentParams, false))
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/towers/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("towers", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 8, entries.size)
            var sawEmpty = false
            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                if (params.isEmpty()) sawEmpty = true
                val midend = Midend.newGame(Towers, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("towers", params, seed).id,
                    midend.id(),
                )
            }
            // The empty params entry is the default, so a default change
            // cannot pass.
            assertTrue("the empty params entry", sawEmpty)
        }

    @Test(timeout = 600_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in Towers.presets().map { it.params } + "") {
            for (seed in seeds) {
                val expected = oracle.newGame("towers", params, seed)
                val midend = Midend.newGame(Towers, params, seed)
                assertEquals("seed '$seed' for '$params' id", expected.id, midend.id())
                assertEquals(
                    "seed '$seed' for '$params' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("seed '$seed' for '$params' status", 0, midend.status())
                assertEquals("seed '$seed' for '$params' undo", false, midend.canUndo)
                assertEquals("seed '$seed' for '$params' redo", false, midend.canRedo)

                val expectedSave = SgtpSave.parse(expected.state)
                val actualSave = midend.serialise()
                assertEquals(
                    "seed '$seed' for '$params' DESC",
                    expectedSave.value("DESC"),
                    actualSave.value("DESC"),
                )
                assertEquals(
                    "seed '$seed' for '$params' AUXINFO",
                    expectedSave.value("AUXINFO"),
                    actualSave.value("AUXINFO"),
                )
                assertEquals(
                    "seed '$seed' for '$params' PARAMS",
                    expectedSave.value("PARAMS"),
                    actualSave.value("PARAMS"),
                )
                assertEquals(
                    "seed '$seed' for '$params' CPARAMS",
                    expectedSave.value("CPARAMS"),
                    actualSave.value("CPARAMS"),
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun `a numeric seed sweep pins the draw order`() = OracleClient().use { oracle ->
        for (params in Towers.presets().map { it.params } + "") {
            for (seed in 0..20) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("towers", params, seed.toString()).id,
                    Midend.newGame(Towers, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine test pins the attempt count and the cause. Easy
        // boards with `w <= 5` retry through the empty-grid pre-check;
        // the harder presets retry through the final exact gate.
        val fixtures = listOf(
            "4de" to "6",
            "5de" to "1",
            "5dh" to "16",
            "6dh" to "535",
            "6dx" to "0",
            "6du" to "1",
        )
        for ((params, seed) in fixtures) {
            assertEquals(
                "retry fixture '$params' seed $seed",
                oracle.newGame("towers", params, seed).id,
                Midend.newGame(Towers, params, seed).id(),
            )
        }
    }

    @Test(timeout = 300_000)
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf("5de", "5dh", "5dx", "5du", "5", "5dhx", "+5", "05", " 5", "5de ")
        for (params in accepted) {
            val expected = oracle.newGame("towers", params, SEED)
            val midend = Midend.newGame(Towers, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        val rejected = listOf("5d", "5dq", "5d5", "2", "10", "0", "300", "9d")
        for (params in rejected) {
            val error = try {
                oracle.newGame("towers", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C rejects '$params'", error)
            assertEquals(
                "message for '$params'",
                error,
                Towers.validateParams(Towers.decodeParams(params), true),
            )
            val kotlinError = try {
                Midend.newGame(Towers, params, SEED)
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
                "4de", "5de", "5dh", "6de", "6dh", "6dx", "6du",
            )) {
                val local = Midend.newGame(Towers, params, SEED).serialise()
                val expected = SgtpSave.parse(oracle.newGame("towers", params, SEED).state)
                assertEquals("$params PARAMS", expected.value("PARAMS"), local.value("PARAMS"))
                assertEquals("$params CPARAMS", expected.value("CPARAMS"), local.value("CPARAMS"))
                // The long-term parameters are the bare width decoded
                // over the defaults, so the difficulty is Easy.
                assertEquals("$params long form", params.substring(0, 1) + "de", local.value("PARAMS"))
                assertEquals(params, local.value("CPARAMS"))
                assertTrue("$params short id", Midend.newGame(Towers, params, SEED).id().startsWith(params.substring(0, 1) + ":"))
            }

            val records = Midend.newGame(Towers, "5dh", SEED).serialise()
            assertEquals(
                listOf(
                    "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                    "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
                ),
                records.records.map { it.key },
            )
        }

    @Test(timeout = 300_000)
    fun `the auxiliary answer is the solution for every preset`() =
        OracleClient().use { oracle ->
            for (params in Towers.presets().map { it.params } + "") {
                for (seed in listOf("12345", "1")) {
                    val expected = oracle.newGame("towers", params, seed)
                    val save = SgtpSave.parse(expected.state)
                    val midend = Midend.newGame(Towers, params, seed)
                    val aux = requireNotNull(midend.auxInfo)
                    val a = (midend.currentParams as Towers.Params).w.let { it * it }
                    assertEquals("'$params' aux length", a + 1, aux.length)
                    assertTrue("'$params' aux prefix", aux.startsWith("S"))
                    assertTrue("'$params' aux digits", aux.drop(1).all { it in '1'..'9' })
                    assertEquals(
                        "'$params' seed $seed AUXINFO",
                        save.value("AUXINFO"),
                        midend.serialise().value("AUXINFO"),
                    )
                    assertEquals(
                        "'$params' seed $seed solve",
                        aux,
                        SgtpSave.parse(oracle.solve(expected.state).state).value("SOLVE"),
                    )
                }
            }
        }

    @Test(timeout = 240_000)
    fun `the generator state continues the oracle stream`() = OracleClient().use { oracle ->
        for (params in Towers.presets().map { it.params } + "") {
            val random = RandomState.fromSeed("stream")
            val effective = if (params.isEmpty()) "5de" else params
            Towers.newDesc(Towers.decodeParams(effective), random)
            val encoded = random.encodeState()
            val expected = oracle.randomUpto(9, 6, state = encoded)
            assertEquals(
                "'$params' stream",
                expected.values,
                (0 until 6).map { random.upto(9) },
            )
            assertEquals("'$params' state", expected.state, random.encodeState())
        }
    }

    @Test(timeout = 120_000)
    fun `generation is deterministic`() = OracleClient().use { oracle ->
        val first = Midend.newGame(Towers, "6dx", "another seed")
        val second = Midend.newGame(Towers, "6dx", "another seed")
        assertEquals(first.id(), second.id())
        assertEquals(first.auxInfo, second.auxInfo)
        assertEquals(first.serialise().serialise(), second.serialise().serialise())
        assertEquals(oracle.newGame("towers", "6dx", "another seed").id, first.id())
    }

    @Test(timeout = 120_000)
    fun `the three by three difficulty drop keeps the request in CPARAMS`() =
        OracleClient().use { oracle ->
            val ids = mutableSetOf<String>()
            for (params in listOf("3dh", "3dx", "3du")) {
                val expected = oracle.newGame("towers", params, "7")
                val midend = Midend.newGame(Towers, params, "7")
                assertEquals("$params id", expected.id, midend.id())
                ids += midend.id()
                val save = midend.serialise()
                assertEquals("$params PARAMS", "3de", save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
            }
            assertEquals("one downgraded description", 1, ids.size)
            assertEquals("3:2/////2///1///", ids.single())
        }

    @Test(timeout = 120_000)
    fun `the description grammar accepts the C descriptions and rejects the C errors`() =
        OracleClient().use { oracle ->
            val accepted = listOf(
                "///////////",
                "///////////,aaaaaaaaa",
                "///////////,_1aaaaaaaa",
                "///////////,a_1aaaaaaa",
                "///////////,1_1aaaaaaa",
                "1/1/1/1/1/1/1/1/1/1/1/",
                "1/1/1/1/1/1/1/1/1/1/1/1",
            )
            for (desc in accepted) {
                val save = handSave(desc)
                val expected = oracle.status(save)
                val midend = Midend.fromSave(Towers, SgtpSave.parse(save))
                assertEquals("accepted '$desc'", expected.id, midend.id())
                assertEquals(
                    "accepted '$desc' state",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("accepted '$desc' text", oracle.text(save), midend.textFormat())
            }

            val rejected = listOf(
                "",
                "1/1",
                "1/1/1/1/1/1/1/1/1/1/1x",
                "4/1/1/1/1/1/1/1/1/1/1/1",
                "1/1/1/1/1/1/1/1/1/1/1/1/",
                "1/1/1/1/1/1/1/1/1/1/1/1x",
                "///////////,aaaaaaaa",
                "///////////,aaaaaaaaaa",
                "///////////,aaaaaaaa0",
                "///////////,11aaaaaaa",
                "///////////,",
            )
            for (desc in rejected) {
                val oracleRejected = try {
                    oracle.status(handSave(desc))
                    false
                } catch (e: OracleException) {
                    true
                }
                assertTrue("C rejects '$desc'", oracleRejected)
                assertNotNull(
                    "Kotlin rejects '$desc'",
                    Towers.validateDesc(Towers.decodeParams("3de"), desc),
                )
                // C reports only the generic load error, so the exact
                // game message is pinned by the engine test.
                assertFalse(
                    "Kotlin message for '$desc'",
                    Towers.validateDesc(Towers.decodeParams("3de"), desc).isNullOrEmpty(),
                )
            }
        }

    private companion object {
        const val SEED = "12345"
    }
}
