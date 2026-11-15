package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: blackbox.
 *
 * Parameters, presets, the ignored `full` argument, the `atoi` decoder,
 * the C validation messages, `params + seed -> DESC`, the five frozen
 * descriptions, the exact RNG order (the count draw, x before y, and a
 * whole-pair retry), the permissive description validator, and the
 * generation guard for an impossible ball range.
 */
class BlackBoxDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the default and the five presets match the oracle`() =
        OracleClient().use { oracle ->
            val expected = oracle.presets("blackbox")
            val actual = BlackBox.presets()
            assertEquals(5, expected.size)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals("preset $i title", expected[i].title, actual[i].title)
                assertEquals("preset $i params", expected[i].params, actual[i].params)
            }
            assertEquals(
                listOf(
                    "5x5, 3 balls" to "w5h5m3M3",
                    "8x8, 5 balls" to "w8h8m5M5",
                    "8x8, 3-6 balls" to "w8h8m3M6",
                    "10x10, 5 balls" to "w10h10m5M5",
                    "10x10, 4-10 balls" to "w10h10m4M10",
                ),
                actual.map { it.title to it.params },
            )

            val defaults = oracle.newGame("blackbox", "", SEED)
            val midend = Midend.newGame(BlackBox, "", SEED)
            assertEquals(defaults.id, midend.id())
            assertEquals(
                BlackBox.Params(8, 8, 5, 5),
                BlackBox.decodeParams(""),
            )
            assertEquals("w8h8m5M5", BlackBox.encodeParams(midend.params, true))
            assertEquals("w8h8m5M5", BlackBox.encodeParams(midend.params, false))
        }

    @Test
    fun `encode params ignores full for every preset`() {
        for (preset in BlackBox.presets()) {
            val params = BlackBox.decodeParams(preset.params)
            assertEquals(
                preset.params,
                BlackBox.encodeParams(params, true),
            )
            assertEquals(
                preset.params,
                BlackBox.encodeParams(params, false),
            )
        }
    }

    @Test
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/blackbox/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("blackbox", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 6, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                assertEquals("golden '$params'", id, Midend.newGame(BlackBox, params, seed).id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("blackbox", params, seed).id,
                    Midend.newGame(BlackBox, params, seed).id(),
                )
            }

            // The five preset descriptions quoted by the specification.
            assertEquals(
                "w5h5m3M3:7f1b9a74d74b19f6",
                Midend.newGame(BlackBox, "w5h5m3M3", seed).id(),
            )
            assertEquals(
                "w8h8m5M5:925fa66636a72ed6ae1c5015",
                Midend.newGame(BlackBox, "w8h8m5M5", seed).id(),
            )
            assertEquals(
                "w8h8m3M6:f9086f6b7379c992",
                Midend.newGame(BlackBox, "w8h8m3M6", seed).id(),
            )
            assertEquals(
                "w10h10m5M5:b4111bd12ceebd7a720a0c43",
                Midend.newGame(BlackBox, "w10h10m5M5", seed).id(),
            )
            assertEquals(
                "w10h10m4M10:5d8138cc34822efd87ac16de",
                Midend.newGame(BlackBox, "w10h10m4M10", seed).id(),
            )
        }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = BlackBox.presets().map { it.params }.distinct()
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("blackbox", params, seed).id,
                    Midend.newGame(BlackBox, params, seed).id(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep pins the generator order`() = OracleClient().use { oracle ->
        for (params in BlackBox.presets().map { it.params }.distinct() +
            listOf("w2h2m1M1", "w3h3m3M3", "w4h6m2M5")
        ) {
            for (seed in 0..19) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("blackbox", params, seed.toString()).id,
                    Midend.newGame(BlackBox, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `the count draw and the collision retry match the oracle`() =
        OracleClient().use { oracle ->
            // w8h8m3M6 seed 12345 draws a zero count, so it does not
            // exercise the first random_upto. Find a seed that does.
            val countSeed = (0..40).first {
                RandomState.fromSeed(it.toString()).upto(4) > 0
            }
            assertEquals(
                "count draw seed $countSeed",
                oracle.newGame("blackbox", "w8h8m3M6", countSeed.toString()).id,
                Midend.newGame(BlackBox, "w8h8m3M6", countSeed.toString()).id(),
            )

            // Find a seed whose generation rejects at least one pair, so
            // the do/while retry runs. A replay in C order counts the
            // attempts and rebuilds the description.
            val collision = (0..200).asSequence()
                .map { it to replay("w3h3m3M3", it.toString()) }
                .first { it.second.retries > 0 }
            assertEquals(
                "a collision seed was found",
                true,
                collision.second.retries > 0,
            )
            val params = BlackBox.decodeParams("w3h3m3M3") as BlackBox.Params
            val midend = Midend.newGame(BlackBox, "w3h3m3M3", collision.first.toString())
            val state = BlackBox.newState(params, midend.desc) as BlackBox.State
            val placed = ArrayList<Pair<Int, Int>>()
            for (y in 0 until state.h) {
                for (x in 0 until state.w) {
                    if (state.gridAt(x + 1, y + 1) and BlackBox.BALL_CORRECT != 0) {
                        placed += x to y
                    }
                }
            }
            assertEquals(
                "retry ball set",
                collision.second.balls.sortedWith(compareBy({ it.first }, { it.second })),
                placed.sortedWith(compareBy({ it.first }, { it.second })),
            )
            assertEquals(
                "retry id for seed ${collision.first}",
                oracle.newGame("blackbox", "w3h3m3M3", collision.first.toString()).id,
                midend.id(),
            )
        }

    @Test
    fun `decode quirks match the oracle`() = OracleClient().use { oracle ->
        // The decoder starts from the defaults, ignores unknown
        // characters, and lets the last value win.
        val accepted = listOf(
            "",
            "5x5",
            "m3",
            "xw5yh5m2M2",
            "w5h5m2M2zzz",
            "w5h5m2M2w3h3",
            "w 5 h 5 m 2 M 2",
            "w+5h+5m+2M+2",
        )
        for (text in accepted) {
            assertEquals(
                "id for '$text'",
                oracle.newGame("blackbox", text, SEED).id,
                Midend.newGame(BlackBox, text, SEED).id(),
            )
        }

        // A lowercase m and an uppercase M are different fields; the
        // letters are case-sensitive, so W5H5M2M2 keeps the default
        // minimum of five and fails.
        val rejected = listOf(
            "W5H5M2M2",
            "w5h5m-1M3",
            "w5h5m4M3",
            "w1h5m1M1",
            "w5h1m1M1",
            "w256h5m1M1",
            "w5h256m1M1",
            "w5h5m0M3",
            "w5h5m25M25",
            "w5h5m-5M-5",
        )
        for (text in rejected) {
            val expected = try {
                oracle.newGame("blackbox", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "message for '$text'",
                expected,
                BlackBox.validateParams(BlackBox.decodeParams(text), true),
            )
        }

        assertEquals(
            BlackBox.Params(8, 8, 3, 5),
            BlackBox.decodeParams("m3"),
        )
        assertEquals(
            BlackBox.Params(3, 3, 2, 2),
            BlackBox.decodeParams("w5h5m2M2w3h3"),
        )
    }

    @Test
    fun `custom params parse the ball field and encode like the oracle`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                Triple("5", "5", "3-6") to "w5h5m3M6",
                Triple("8", "8", "3-6") to "w8h8m3M6",
                Triple("10", "10", "4-10") to "w10h10m4M10",
                Triple("5", "5", "5") to "w5h5m5M5",
                Triple("5", "5", "5-") to "w5h5m5M5",
                Triple("3", "3", "1-9") to "w3h3m1M9",
                Triple("5", "5", "3 - 6") to "w5h5m3M3",
                Triple("5", "5", "3--6") to "w5h5m3M-6",
            )
            for ((fields, encoded) in cases) {
                val params = BlackBox.customParams(fields.first, fields.second, fields.third)
                assertEquals("$fields", encoded, BlackBox.encodeParams(params, true))
                try {
                    val expected = oracle.newGame("blackbox", encoded, SEED).id
                    assertEquals("$fields id", expected, Midend.newGame(BlackBox, encoded, SEED).id())
                } catch (e: OracleException) {
                    assertEquals(
                        "$fields message",
                        e.message,
                        BlackBox.validateParams(params, true),
                    )
                }
            }

            assertEquals(
                "Negative number of balls",
                BlackBox.validateParams(BlackBox.customParams("5", "5", "-5"), true),
            )
            assertEquals(
                "Number of balls must be at least one",
                BlackBox.validateParams(BlackBox.customParams("5", "5", "abc"), true),
            )
        }

    @Test
    fun `validate desc accepts the C permissive forms`() = OracleClient().use { oracle ->
        // A duplicate ball, uppercase hex, and a range that contains the
        // count are all valid in C and are not normalized.
        val valid = listOf(
            "w5h5m3M3" to "7f1b9a74d74b19f6",
            "w5h5m3M3" to "18d7854bc6569634",
            "w5h5m3M3" to "7F1B9A74D74B19F6",
            "w5h5m3M6" to "7f1b9a74d74b19f6",
        )
        for ((params, desc) in valid) {
            val save = saveText(params, desc)
            val expected = oracle.id(save)
            assertNull(
                "'$params:$desc' must be valid",
                BlackBox.validateDesc(BlackBox.decodeParams(params), desc),
            )
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(save))
            assertEquals("'$params:$desc' id", expected.id, midend.id())
            assertEquals("'$params:$desc' status", 0, midend.status())
            assertEquals("'$params:$desc' text", "", midend.textFormat())
        }

        val invalid = listOf(
            "w5h5m3M3" to "7f1b9a74d74b19f",
            "w5h5m3M3" to "7f1b9a74d74b19f6aa",
            "w5h5m2M2" to "7f1b9a74d74b19f6",
            "w5h5m4M4" to "7f1b9a74d74b19f6",
            "w5h5m3M3" to "7f1b9a74d74b19fz",
            "w3h3m3M3" to "17259c5c7686269f",
        )
        for ((params, desc) in invalid) {
            val save = saveText(params, desc)
            val expected = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "oracle message for '$params:$desc'",
                "Game description in save file is invalid",
                expected,
            )
            try {
                Midend.fromSave(BlackBox, SgtpSave.parse(save))
                fail("Kotlin loaded '$params:$desc'")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "'$params:$desc' Kotlin message: ${e.message}",
                    e.message!!.startsWith("game description: "),
                )
            }
        }

        // The length test runs before any decode, so a two-character
        // description is a message and not a negative array size.
        assertEquals(
            "Game description is wrong length",
            BlackBox.validateDesc(BlackBox.decodeParams("w5h5m3M3"), "ab"),
        )
        assertEquals(
            "Game description is wrong length",
            BlackBox.validateDesc(BlackBox.decodeParams("w5h5m3M3"), ""),
        )
    }

    @Test(timeout = 5_000)
    fun `an impossible ball range is rejected before generation`() =
        OracleClient().use { oracle ->
            // C validation accepts this, and its generator can then
            // loop forever. The Kotlin validator keeps the C message...
            val params = BlackBox.decodeParams("w3h3m1M100")
            assertNull(BlackBox.validateParams(params, true))
            assertNull(
                BlackBox.validateParams(
                    BlackBox.decodeParams("w3h3m1M100"),
                    false,
                ),
            )

            // ...and generation refuses it with a clear error.
            try {
                Midend.newGame(BlackBox, "w3h3m1M100", SEED)
                fail("generation accepted 100 balls on a 3x3 grid")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "guard message: ${e.message}",
                    e.message!!.contains("cannot place up to 100 balls") &&
                        e.message!!.contains("3x3"),
                )
            }

            // Feasible ranges are unchanged, including the full grid.
            for (text in BlackBox.presets().map { it.params } +
                listOf("w3h3m1M9", "w2h2m1M4", "w3h4m2M7")
            ) {
                assertEquals(
                    "feasible '$text'",
                    oracle.newGame("blackbox", text, SEED).id,
                    Midend.newGame(BlackBox, text, SEED).id(),
                )
            }
            assertNotEquals(
                "the guard did not change a valid description",
                "",
                Midend.newGame(BlackBox, "w3h3m1M9", SEED).desc,
            )
        }

    private data class Replay(val balls: List<Pair<Int, Int>>, val retries: Int)

    /**
     * Replays `new_game_desc` with the C call order: the count draw,
     * then x and y per ball, and both again on a collision. The ball set
     * must equal the port's description.
     */
    private fun replay(paramsText: String, seed: String): Replay {
        val params = BlackBox.decodeParams(paramsText) as BlackBox.Params
        val random = RandomState.fromSeed(seed)
        var nballs = params.minBalls
        if (params.maxBalls > params.minBalls) {
            nballs += random.upto((params.maxBalls - params.minBalls + 1).toLong()).toInt()
        }
        val used = BooleanArray(params.w * params.h)
        val balls = ArrayList<Pair<Int, Int>>(nballs)
        var retries = 0
        for (i in 0 until nballs) {
            var x: Int
            var y: Int
            do {
                x = random.upto(params.w.toLong()).toInt()
                y = random.upto(params.h.toLong()).toInt()
                if (used[y * params.w + x]) retries++
            } while (used[y * params.w + x])
            used[y * params.w + x] = true
            balls += x to y
        }
        return Replay(balls, retries)
    }

    private fun saveText(params: String, desc: String): String = SgtpSave.of(
        listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", BlackBox.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("UI", "E0"),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        ),
    ).serialise()

    private companion object {
        const val SEED = "12345"
    }
}
