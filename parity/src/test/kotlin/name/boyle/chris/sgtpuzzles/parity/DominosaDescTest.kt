package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: dominosa.
 *
 * Parameters, presets, the decode and validation quirks, the
 * `PARAMS`/`CPARAMS` split, and `params + seed -> DESC`.
 *
 * The description tests are the RNG-order guards: the laydomino shuffle
 * and repair passes, the allocator shuffles, the `random_upto(2)` per
 * placement, and the two solver gates all change every description when
 * they move.
 */
class DominosaDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("dominosa")
        val actual = Dominosa.presets()
        assertEquals(12, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("Order 3, Trivial", actual[0].title)
        assertEquals("3dt", actual[0].params)
        assertEquals("Order 6, Extreme", actual[11].title)
        assertEquals("6de", actual[11].params)

        val defaults = oracle.newGame("dominosa", "", SEED)
        val midend = Midend.newGame(Dominosa, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals(defaults.state, midend.serialise().serialise())
        assertEquals("6db", Dominosa.encodeParams(midend.params, true))
        assertEquals("6db", Dominosa.encodeParams(midend.currentParams, true))
        assertEquals("6", Dominosa.encodeParams(midend.currentParams, false))
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/dominosa/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("dominosa", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 13, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                val midend = Midend.newGame(Dominosa, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("dominosa", params, seed).id,
                    midend.id(),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = Dominosa.presets().map { it.params } + ""
        val seeds = listOf(
            "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
        )
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("dominosa", params, seed).id,
                    Midend.newGame(Dominosa, params, seed).id(),
                )
            }
        }
    }

    @Test(timeout = 300_000)
    fun `a numeric seed sweep pins the draw order`() = OracleClient().use { oracle ->
        val sweeps = listOf(
            "3dt" to 0..19,
            "6dt" to 0..19,
            "6db" to 0..19,
            "6dh" to 0..9,
            "6de" to 0..9,
            "" to 0..19,
        )
        for ((params, seeds) in sweeps) {
            for (seed in seeds) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("dominosa", params, seed.toString()).id,
                    Midend.newGame(Dominosa, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test(timeout = 120_000)
    fun `the generator state continues the oracle stream`() = OracleClient().use { oracle ->
        val random = RandomState.fromSeed(SEED)
        Dominosa.newDesc(Dominosa.decodeParams("6db"), random)
        val encoded = random.encodeState()
        val expected = oracle.randomUpto(8, 6, state = encoded)
        assertEquals(expected.values, (0 until 6).map { random.upto(8) })
        assertEquals(expected.state, random.encodeState())
    }

    @Test(timeout = 300_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine test proves that these seeds reach the retry paths:
        // the Trivial and Basic allocators, the Hard Basic gate and easy
        // opening, and an Extreme board that needs the forcing chain.
        val fixtures = listOf(
            "6dt" to "0",
            "6db" to "1",
            "6db" to "8",
            "6dh" to "1",
            "6dh" to "0",
            "6de" to "0",
            "6de" to "1",
        )
        for ((params, seed) in fixtures) {
            assertEquals(
                "retry fixture '$params' seed $seed",
                oracle.newGame("dominosa", params, seed).id,
                Midend.newGame(Dominosa, params, seed).id(),
            )
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "6",
            "6dh",
            "6dth",
            "6dte",
            "6dbt",
            "6da",
            "6a",
            "+6",
            " 6",
            "06",
            "6x",
            "6junk",
            "1dt",
            "1db",
            "1de",
            "1da",
            "2dh",
            "2de",
            "2da",
        )
        for (params in accepted) {
            val expected = oracle.newGame("dominosa", params, SEED)
            val midend = Midend.newGame(Dominosa, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        // D-030 caps generation at 9; validation still decodes and
        // accepts 12, as C does, and only the generator refuses it.
        assertNull(Dominosa.validateParams(Dominosa.decodeParams("12"), true))
        assertEquals(
            "Maximum number is too large for this build",
            runCatching { Midend.newGame(Dominosa, "12", SEED) }
                .exceptionOrNull()?.message,
        )

        val rejected = listOf("6d", "6dx", "6d0", "6d?", "6ad", "0", "-1", "46340")
        for (params in rejected) {
            val error = try {
                oracle.newGame("dominosa", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertTrue("C rejects '$params'", error != null)
            val kotlinError = try {
                Midend.newGame(Dominosa, params, SEED)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("message for '$params'", error, kotlinError)
        }
    }

    @Test(timeout = 120_000)
    fun `the 46339 boundary is accepted and 46340 is not`() {
        // The oracle would allocate gigabytes for 46339, so this pins the
        // C arithmetic only. The measured C message for a save with
        // `PARAMS 46340` is `Long-term parameters in save file are
        // invalid`; the game-level call is the filter below.
        assertNull(
            Dominosa.validateParams(Dominosa.Params(46339, 1), full = true),
        )
        assertEquals(
            "Maximum face number must not be unreasonably large",
            Dominosa.validateParams(Dominosa.Params(46340, 1), full = true),
        )
    }

    @Test
    fun `the PARAMS and CPARAMS split keeps the real difficulty`() = OracleClient().use { oracle ->
        for (params in listOf("3dt", "1de", "2dh", "6de")) {
            val local = Midend.newGame(Dominosa, params, SEED).serialise()
            val expected = SgtpSave.parse(oracle.newGame("dominosa", params, SEED).state)
            assertEquals("$params PARAMS", expected.value("PARAMS"), local.value("PARAMS"))
            assertEquals("$params CPARAMS", expected.value("CPARAMS"), local.value("CPARAMS"))
            assertFalse(local.value("CPARAMS")!!.startsWith("4634"))
        }
        assertEquals("3db", Midend.newGame(Dominosa, "3dt", SEED).serialise().value("PARAMS"))
        assertEquals("3dt", Midend.newGame(Dominosa, "3dt", SEED).serialise().value("CPARAMS"))
        assertEquals("1db", Midend.newGame(Dominosa, "1de", SEED).serialise().value("PARAMS"))
        assertEquals("1de", Midend.newGame(Dominosa, "1de", SEED).serialise().value("CPARAMS"))
        assertEquals("2db", Midend.newGame(Dominosa, "2dh", SEED).serialise().value("PARAMS"))
        assertEquals("2dh", Midend.newGame(Dominosa, "2dh", SEED).serialise().value("CPARAMS"))

        // No UI and no PRIVDESC; the generator writes AUXINFO.
        val save = Midend.newGame(Dominosa, "6db", SEED).serialise()
        assertNull(save.value("UI"))
        assertNull(save.value("PRIVDESC"))
        assertNull(save.value("TIME"))
        assertEquals(
            listOf(
                "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
            ),
            save.records.map { it.key },
        )
    }

    @Test(timeout = 300_000)
    fun `the small-board difficulty cap matches the oracle`() = OracleClient().use { oracle ->
        for (seed in listOf("1", "2", "3", "12345")) {
            for (params in listOf("1dt", "1db", "1de", "1da", "2dt", "2db", "2dh", "2de", "2da")) {
                assertEquals(
                    "$params seed $seed",
                    oracle.newGame("dominosa", params, seed).id,
                    Midend.newGame(Dominosa, params, seed).id(),
                )
            }
            assertEquals(
                "seed $seed 1dt/1db",
                Midend.newGame(Dominosa, "1dt", seed).desc,
                Midend.newGame(Dominosa, "1db", seed).desc,
            )
            assertEquals(
                "seed $seed 1dt/1de",
                Midend.newGame(Dominosa, "1dt", seed).desc,
                Midend.newGame(Dominosa, "1de", seed).desc,
            )
            assertEquals(
                "seed $seed 2db/2dh",
                Midend.newGame(Dominosa, "2db", seed).desc,
                Midend.newGame(Dominosa, "2dh", seed).desc,
            )
        }
    }

    @Test(timeout = 300_000)
    fun `the auxiliary answer solves every preset like the oracle`() =
        OracleClient().use { oracle ->
            for (params in Dominosa.presets().map { it.params } + "") {
                val save = oracle.newGame("dominosa", params, SEED).state
                val solved = oracle.solve(save)
                val payload = SgtpSave.parse(solved.state).value("SOLVE")
                assertTrue("the solve is an S move for '$params'", payload!!.startsWith("S"))

                val midend = Midend.newGame(Dominosa, params, SEED)
                assertEquals(
                    "aux solve for '$params'",
                    payload,
                    Dominosa.solve(midend.state, midend.auxInfo),
                )
            }
        }

    private companion object {
        const val SEED = "12345"
    }
}
