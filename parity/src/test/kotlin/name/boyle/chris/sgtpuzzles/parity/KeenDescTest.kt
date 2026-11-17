package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Keen
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
 * Level: semantic. Game: keen.
 *
 * The parameters, the ten presets, the decode and validation quirks,
 * `params + seed -> DESC`, `AUXINFO`, the retry fixtures, the three by
 * three difficulty drop, and the two multiplication-only parameter sets.
 *
 * The description tests are the RNG and solver order guards: one Latin
 * generation, two order shuffles, one `random_upto(4)` per
 * domino-capable singleton, and two solver gates. A different draw order
 * or a different box or clue order changes every id.
 */
class KeenDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    private fun handSave(desc: String, params: String = "3dn"): String {
        val records = listOf(
            "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection",
            "VERSION" to "1",
            "GAME" to "Keen",
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
        val expected = oracle.presets("keen")
        val actual = Keen.presets()
        assertEquals(10, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("6x6 Normal", actual[4].title)
        assertEquals("6dn", actual[4].params)
        assertEquals("5x5 Easy, multiplication only", actual[2].title)
        assertEquals("5dem", actual[2].params)

        val defaults = oracle.newGame("keen", "", SEED)
        val midend = Midend.newGame(Keen, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals(defaults.state, midend.serialise().serialise())
        assertEquals("6dn", Keen.encodeParams(midend.params, true))
        assertEquals("6dn", Keen.encodeParams(midend.currentParams, true))
        assertEquals("6", Keen.encodeParams(midend.currentParams, false))
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/keen/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("keen", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 11, entries.size)
            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                val midend = Midend.newGame(Keen, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("keen", params, seed).id,
                    midend.id(),
                )
            }
        }

    @Test(timeout = 600_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in Keen.presets().map { it.params } + "") {
            for (seed in seeds) {
                val expected = oracle.newGame("keen", params, seed)
                val midend = Midend.newGame(Keen, params, seed)
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
        for (params in Keen.presets().map { it.params } + "") {
            for (seed in 0..9) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("keen", params, seed.toString()).id,
                    Midend.newGame(Keen, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun `the retry fixtures match the oracle and reach more than one attempt`() =
        OracleClient().use { oracle ->
            // The engine test pins the attempt count for these seeds.
            val fixtures = listOf(
                "4de" to "1",
                "5de" to "1",
                "5dem" to "1",
                "6de" to "11",
                "6dn" to "4",
                "6dnm" to "9",
                "6dh" to "9",
                "6dx" to "3",
                "6du" to "4",
                "9dn" to "7",
            )
            for ((params, seed) in fixtures) {
                assertEquals(
                    "retry fixture '$params' seed $seed",
                    oracle.newGame("keen", params, seed).id,
                    Midend.newGame(Keen, params, seed).id(),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "6dn",
            "6m",
            "6dnxyz",
            "+5",
            "6dnm",
            "6de",
            "6dem",
            "3dh",
            "9dn",
            " 6dn",
            "6Dn",
            "6dnu",
            "06dn",
        )
        for (params in accepted) {
            val expected = oracle.newGame("keen", params, SEED)
            val midend = Midend.newGame(Keen, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        val rejected = listOf(
            "6dm", "6d", "6dq", "6d5", "2", "10", "0", "-7",
        )
        for (params in rejected) {
            val error = try {
                oracle.newGame("keen", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C rejects '$params'", error)
            assertEquals(
                "message for '$params'",
                error,
                Keen.validateParams(Keen.decodeParams(params), true),
            )
            val kotlinError = try {
                Midend.newGame(Keen, params, SEED)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("Kotlin message for '$params'", error, kotlinError)
        }
    }

    @Test(timeout = 120_000)
    fun `the PARAMS and CPARAMS split keeps the requested difficulty and flag`() =
        OracleClient().use { oracle ->
            for (params in listOf(
                "4de", "5de", "5dem", "6de", "6dn", "6dnm",
                "6dh", "6dx", "6du", "9dn",
            )) {
                val local = Midend.newGame(Keen, params, SEED).serialise()
                val expected = SgtpSave.parse(oracle.newGame("keen", params, SEED).state)
                assertEquals("$params PARAMS", expected.value("PARAMS"), local.value("PARAMS"))
                assertEquals("$params CPARAMS", expected.value("CPARAMS"), local.value("CPARAMS"))
                // The long-term parameters are the short form decoded
                // over the defaults: the width, Normal difficulty, and
                // no multiplication-only flag.
                val long = params.substringBefore('d')
                assertEquals("$params long form", "${long}dn", local.value("PARAMS"))
                assertEquals(params, local.value("CPARAMS"))
            }

            // The state parameters are the long-term ones, so a new game
            // at `5dem` has the flag off and draws the operator.
            val newGame = Midend.newGame(Keen, "5dem", SEED)
            val newState = newGame.state as Keen.State
            assertFalse("new game state flag", newState.params.multiplicationOnly)
            assertTrue(
                "new game id has no flag",
                newGame.id().startsWith("5:"),
            )

            // A load builds the state from CPARAMS, so the flag is on
            // and no operator is drawn.
            val save = oracle.newGame("keen", "5dem", SEED).state
            val loaded = Midend.fromSave(Keen, SgtpSave.parse(save))
            val loadedState = loaded.state as Keen.State
            assertTrue("loaded state flag", loadedState.params.multiplicationOnly)

            // A restart is the other path that uses the long-term
            // parameters (`midend.c:964`), so its state has the flag off
            // again and draws the operator.
            loaded.processKey(0, 0, Buttons.CURSOR_UP, 48)
            loaded.processKey(0, 0, '1'.code, 48)
            assertTrue(loaded.restart())
            assertFalse(
                "restart state flag",
                (loaded.state as Keen.State).params.multiplicationOnly,
            )

            // The record order and the absence of UI, TIME, PRIVDESC.
            val records = Midend.newGame(Keen, "6dnm", SEED).serialise()
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
            for (params in Keen.presets().map { it.params } + "") {
                for (seed in listOf("12345", "1")) {
                    val expected = oracle.newGame("keen", params, seed)
                    val save = SgtpSave.parse(expected.state)
                    val midend = Midend.newGame(Keen, params, seed)
                    val aux = requireNotNull(midend.auxInfo)
                    val a = (midend.currentParams as Keen.Params).w.let { it * it }
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
        for (params in Keen.presets().map { it.params } + "") {
            val random = RandomState.fromSeed("stream")
            val effective = if (params.isEmpty()) "6dn" else params
            Keen.newDesc(Keen.decodeParams(effective), random)
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

    @Test(timeout = 120_000)
    fun `generation is deterministic`() = OracleClient().use { oracle ->
        val first = Midend.newGame(Keen, "6dnm", "another seed")
        val second = Midend.newGame(Keen, "6dnm", "another seed")
        assertEquals(first.id(), second.id())
        assertEquals(first.auxInfo, second.auxInfo)
        assertEquals(first.serialise().serialise(), second.serialise().serialise())
        assertEquals(oracle.newGame("keen", "6dnm", "another seed").id, first.id())
    }

    @Test(timeout = 120_000)
    fun `the three by three difficulty drop keeps the request in CPARAMS`() =
        OracleClient().use { oracle ->
            for (params in listOf("3dh", "3dx", "3du")) {
                val expected = oracle.newGame("keen", params, "1")
                val midend = Midend.newGame(Keen, params, "1")
                assertEquals("$params id", expected.id, midend.id())
                val save = midend.serialise()
                assertEquals("$params PARAMS", "3dn", save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                // The generated board is the Normal one; the engine test
                // pins that the solver grades it as Normal.
                assertTrue(midend.id().startsWith("3:"))
            }
        }

    @Test(timeout = 120_000)
    fun `the description grammar accepts the C descriptions and rejects the C errors`() =
        OracleClient().use { oracle ->
            val accepted = listOf(
                "_baa_3a,m6s1m3a3",
                "_baa_3a,m6s1m3a",
                "_baa_3a,m6s1m3a0",
                "_baa___a,m6s1m3a3",
                "_ba0a0_3a,m6s1m3a3",
                "l,a18",
            )
            for (desc in accepted) {
                val save = handSave(desc)
                val expected = oracle.status(save)
                val midend = Midend.fromSave(Keen, SgtpSave.parse(save))
                assertEquals("accepted '$desc'", expected.id, midend.id())
                assertEquals("accepted '$desc' state", expected.state, midend.serialise().serialise())
            }

            val rejected = listOf(
                "!_baa_3a,m6s1m3a3",
                "z",
                "",
                "a",
                "_baa_3a",
                "_baa_3a!m6s1m3a3",
                "_baa_3a,s6s1m3a3",
                "_baa_3a,d6s1m3a3",
                "_baa_3a,m6s1m3",
                "_baa_3a,m6s1m3x3",
                "_baa_3a,m6s1m3a3a1",
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
                    Keen.validateDesc(Keen.decodeParams("3dn"), desc),
                )
            }
        }

    private companion object {
        const val SEED = "12345"
    }
}
