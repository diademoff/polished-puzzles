package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unequal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: unequal.
 *
 * The parameters, the twelve presets, the decode and validation quirks,
 * `params + seed -> DESC`, `AUXINFO`, the retry fixtures, and the
 * description grammar.
 *
 * The description tests are the RNG and solver-order guard: one Latin
 * generation, one shuffle of the `order^2` number codes, one shuffle of
 * the `4*order^2` inequality codes, the already shuffled scratch list
 * across retries, the solver-gated assembly and strip, the transposed
 * `gg_best_clue` read, the Adjacent validator gap, and the 50-try
 * fallback.
 */
class UnequalDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    private fun handSave(desc: String, params: String = "4de"): String {
        val records = listOf(
            "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection",
            "VERSION" to "1",
            "GAME" to "Unequal",
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

    /** A grid of [order] by [order] empty cells, with flags at [flags]. */
    private fun grid(
        order: Int,
        flags: Map<Int, String> = emptyMap(),
        numbers: Map<Int, Int> = emptyMap(),
    ): String {
        val out = StringBuilder()
        for (i in 0 until order * order) {
            out.append(numbers[i] ?: 0)
            out.append(flags[i] ?: "")
            out.append(',')
        }
        return out.toString()
    }

    @Test(timeout = 120_000)
    fun `the presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("unequal")
        val actual = Unequal.presets()
        assertEquals(12, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("Unequal: 4x4 Easy", actual[0].title)
        assertEquals("4de", actual[0].params)
        assertEquals("Adjacent: 5x5 Tricky", actual[3].title)
        assertEquals("5adk", actual[3].params)
        assertEquals("Adjacent: 6x6 Tricky", actual[7].title)
        assertEquals("Adjacent: 7x7 Tricky", actual[10].title)

        // The default params are preset 0, so the empty string and `4de`
        // are the same game.
        assertEquals("4de", Unequal.encodeParams(Unequal.defaultParams(), true))
        val empty = oracle.newGame("unequal", "", SEED)
        val local = Midend.newGame(Unequal, "", SEED)
        assertEquals(empty.id, local.id())
        assertEquals(empty.state, local.serialise().serialise())
        assertEquals(empty.id, oracle.newGame("unequal", "4de", SEED).id)
        assertEquals(
            File(testdata, "saves/initial/unequal/4de.sgtp").readText(),
            File(testdata, "saves/initial/unequal/default.sgtp").readText(),
        )
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/unequal/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("unequal", golden.string("game"))
            val seed = golden.string("seed")
            assertEquals(SEED, seed)
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 13, entries.size)
            var sawEmpty = false
            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                if (params.isEmpty()) sawEmpty = true
                val midend = Midend.newGame(Unequal, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("unequal", params, seed).id,
                    midend.id(),
                )
            }
            assertTrue("the empty params entry", sawEmpty)
        }

    @Test(timeout = 600_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in Unequal.presets().map { it.params } + "") {
            for (seed in seeds) {
                val expected = oracle.newGame("unequal", params, seed)
                val midend = Midend.newGame(Unequal, params, seed)
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
                for (key in listOf("DESC", "AUXINFO", "PARAMS", "CPARAMS")) {
                    assertEquals(
                        "seed '$seed' for '$params' $key",
                        expectedSave.value(key),
                        actualSave.value(key),
                    )
                }
            }
        }
    }

    @Test(timeout = 600_000)
    fun `a numeric seed sweep pins the draw order`() = OracleClient().use { oracle ->
        for (params in Unequal.presets().map { it.params } + "") {
            for (seed in 0..20) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("unequal", params, seed.toString()).id,
                    Midend.newGame(Unequal, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine test pins the attempt counts. The Easy, Tricky,
        // Extreme, and Adjacent families each have a retry. The 5dk
        // seed 384 reaches MAXTRIES, so its accepted board is one level
        // easier than CPARAMS asks.
        val fixtures = listOf(
            "4de" to "105",
            "5dk" to "21",
            "5adk" to "47",
            "5dx" to "27",
            "6dk" to "5",
            "6dx" to "3",
            "7dk" to "31",
            "7dx" to "1",
        )
        for ((params, seed) in fixtures) {
            assertEquals(
                "retry fixture '$params' seed $seed",
                oracle.newGame("unequal", params, seed).id,
                Midend.newGame(Unequal, params, seed).id(),
            )
        }

        // The fallback fixture: 50 attempts, then the cosmetic
        // difficulty decrement. The description is still the C one and
        // CPARAMS keeps the request.
        val fallback = oracle.newGame("unequal", "5dk", "384")
        val local = Midend.newGame(Unequal, "5dk", "384")
        assertEquals("fallback id", fallback.id, local.id())
        assertEquals("fallback save", fallback.state, local.serialise().serialise())
        assertEquals("5dk", local.serialise().value("CPARAMS"))
        assertEquals("5de", local.serialise().value("PARAMS"))
        assertEquals(1, Unequal.difficulty(local.state))
    }

    @Test(timeout = 300_000)
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        // Every accepted text decodes over the game defaults, so a text
        // with no `d` keeps Easy. The digit skip starts at the first
        // character, so a sign or a space hides the mode and the `d`.
        val accepted = listOf(
            "4de", "4", "4a", "4dxx", "4q", "+4de", " 4de", "04de",
            "4ade", "4dx", "4dt", "4at", "5adt", "0004de",
        )
        for (params in accepted) {
            val expected = oracle.newGame("unequal", params, SEED)
            val midend = Midend.newGame(Unequal, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        val rejected = listOf("4adk", "4d", "4dq", "-4de", "2", "33de", "0", "40de", "5adq")
        for (params in rejected) {
            val error = try {
                oracle.newGame("unequal", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C rejects '$params'", error)
            assertEquals(
                "message for '$params'",
                error,
                Unequal.validateParams(Unequal.decodeParams(params), true),
            )
            val kotlinError = try {
                Midend.newGame(Unequal, params, SEED)
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
                "4de", "5de", "5dk", "5adk", "5dx", "6dk", "6adk", "7dk", "7adk", "7dx",
            )) {
                val local = Midend.newGame(Unequal, params, SEED).serialise()
                val expected = SgtpSave.parse(oracle.newGame("unequal", params, SEED).state)
                assertEquals("$params PARAMS", expected.value("PARAMS"), local.value("PARAMS"))
                assertEquals("$params CPARAMS", expected.value("CPARAMS"), local.value("CPARAMS"))
                assertEquals(params, local.value("CPARAMS"))
                val short = Midend.newGame(Unequal, params, SEED).id().substringBefore(':')
                assertEquals(
                    "short id for $params",
                    params.substringBefore('d'),
                    short,
                )
                assertTrue("the short id has no d", !short.contains('d'))
            }
        }

    @Test(timeout = 300_000)
    fun `the auxiliary answer is the row-major solution for every preset`() =
        OracleClient().use { oracle ->
            for (params in Unequal.presets().map { it.params } + "") {
                val expected = oracle.newGame("unequal", params, SEED)
                val midend = Midend.newGame(Unequal, params, SEED)
                val aux = requireNotNull(midend.auxInfo)
                val order = (midend.currentParams as Unequal.Params).order
                assertEquals("'$params' aux length", order * order + 1, aux.length)
                assertTrue("'$params' aux prefix", aux.startsWith("S"))
                assertEquals(
                    "'$params' AUXINFO",
                    SgtpSave.parse(expected.state).value("AUXINFO"),
                    midend.serialise().value("AUXINFO"),
                )
                assertEquals(
                    "'$params' solve returns the aux",
                    aux,
                    SgtpSave.parse(oracle.solve(expected.state).state).value("SOLVE"),
                )
            }
        }

    @Test(timeout = 240_000)
    fun `the generator state continues the oracle stream`() = OracleClient().use { oracle ->
        for (params in Unequal.presets().map { it.params } + "") {
            val random = RandomState.fromSeed("stream")
            val effective = if (params.isEmpty()) "4de" else params
            Unequal.newDesc(Unequal.decodeParams(effective), random)
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
        for (params in listOf("4de", "5adk", "7dx")) {
            val first = Midend.newGame(Unequal, params, "another seed")
            val second = Midend.newGame(Unequal, params, "another seed")
            assertEquals(first.id(), second.id())
            assertEquals(first.auxInfo, second.auxInfo)
            assertEquals(first.serialise().serialise(), second.serialise().serialise())
            assertEquals(
                oracle.newGame("unequal", params, "another seed").id,
                first.id(),
            )
        }
    }

    @Test(timeout = 300_000)
    fun `the description grammar accepts the C descriptions and rejects the C errors`() =
        OracleClient().use { oracle ->
            val accepted = listOf(
                grid(4),
                grid(4, flags = mapOf(0 to "R", 5 to "D", 7 to "LU")),
                grid(4, numbers = mapOf(0 to 1, 5 to 2)),
                // The run-length form: `c - 'a' + 1` empty cells.
                "a1," + "0,".repeat(14),
                // A number that contradicts a clue and the Latin rule.
                "1," + "1,".repeat(15),
                // One spelling of a clue loads without the other.
                grid(4, flags = mapOf(0 to "R")),
            )
            for (desc in accepted) {
                val save = handSave(desc)
                val expected = oracle.status(save)
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(save))
                assertEquals("accepted '${desc.take(20)}'", expected.id, midend.id())
                assertEquals(
                    "accepted '${desc.take(20)}' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "accepted '${desc.take(20)}' text",
                    oracle.text(save),
                    midend.textFormat(),
                )
            }

            // The accepted list above includes an empty grid, an
            // immutable number, an R clue with no reciprocal L in
            // Unequal mode, and content that is not a Latin square.
            // C does not check the content.

            val rejected = listOf(
                grid(4) + "0," to "Too much data to fit in grid",
                grid(4) + "aa0," to "Too much data to fit in grid",
                "R," + grid(4).substringAfter(',') to "Expecting number in game description",
                "5," + grid(4).substringAfter(',') to "Number out of range in game description",
                "0x" + grid(4).substringAfter(',') to "Missing separator",
                "0,".repeat(15) to "Not enough data to fill grid",
                "" to "Not enough data to fill grid",
                grid(4, flags = mapOf(3 to "R")) to "Flags go off grid",
                grid(4, flags = mapOf(12 to "D")) to "Flags go off grid",
                grid(4, flags = mapOf(0 to "R", 1 to "L")) to "Flags contradicting each other",
            )
            for ((desc, message) in rejected) {
                val oracleRejected = try {
                    oracle.status(handSave(desc))
                    false
                } catch (e: OracleException) {
                    true
                }
                assertTrue("C rejects '${desc.take(20)}'", oracleRejected)
                assertEquals(
                    "Kotlin message for '${desc.take(20)}'",
                    message,
                    Unequal.validateDesc(Unequal.decodeParams("4de"), desc),
                )
            }

            // In Adjacent mode a flag requires the reciprocal flag; a
            // lone R is a contradiction, a reciprocal pair is accepted.
            val lone = grid(5, flags = mapOf(0 to "R"))
            assertTrue(
                "C rejects the lone R in Adjacent mode",
                try {
                    oracle.status(handSave(lone, "5adk"))
                    false
                } catch (e: OracleException) {
                    true
                },
            )
            assertEquals(
                "Adjacent lone R",
                "Flags contradicting each other",
                Unequal.validateDesc(Unequal.decodeParams("5adk"), lone),
            )
            val pair = grid(5, flags = mapOf(0 to "R", 1 to "L"))
            val pairSave = handSave(pair, "5adk")
            val pairExpected = oracle.status(pairSave)
            val pairMidend = Midend.fromSave(Unequal, SgtpSave.parse(pairSave))
            assertEquals("reciprocal pair", pairExpected.id, pairMidend.id())
            assertEquals(
                "reciprocal pair save",
                pairExpected.state,
                pairMidend.serialise().serialise(),
            )
        }

    private companion object {
        const val SEED = "12345"
    }
}
